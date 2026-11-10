#!/usr/bin/env python3
# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The AWS side of Drishti's Delta Lake on S3 connector (delta plugin, root: s3://bucket/prefix): the bucket, the lake's prefix and the
IAM policy the server and the loaders need, created, checked and removed with boto3 (no AWS CLI, so Windows works).

    delta_s3.py create --bucket acme-drishti-lake --prefix delta [--role drishti-server] [--kms-key ARN] [--read-only]
    delta_s3.py check  --bucket acme-drishti-lake --prefix delta [--table risk/pnl-vector]
    delta_s3.py remove --bucket acme-drishti-lake --prefix delta [--purge] [--delete-bucket] --yes
    delta_s3.py policy --bucket acme-drishti-lake --prefix delta [--read-only]     the IAM policy document, printed (no AWS call)

Every command takes --region/--profile (else AWS_REGION/AWS_PROFILE and the default credential chain), --env FILE and --dry-run. The
bucket and prefix default to DRISHTI_DELTA_S3_BUCKET and DRISHTI_DELTA_S3_PREFIX, else deploy/aws/env's LAKE_BUCKET and LAKE_PREFIX.
`check` reads one table end to end the way Drishti does: the Delta log (the last checkpoint, then the commits after it), the live files,
and the first one's Parquet footer and first row group, with ranged GETs. A production lake's bucket (KMS, VPC endpoint, lifecycle, access
logs) is made by deploy/aws/delta-lake/setup-buckets.sh; this script makes a plain, private, encrypted one for the connector.
Exit: 0 done (or the check passed), 1 a step failed (named), 2 usage. Guide: deploy/aws/delta-s3/README.md."""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import drishti_aws as A  # noqa: E402

COMMIT = re.compile(r"(\d{20})\.json$")
CHECKPOINT = re.compile(r"(\d{20})\.checkpoint(?:\.\d{10}\.\d{10})?\.parquet$")


def clean_prefix(prefix: str) -> str:
    return prefix.strip().strip("/")


def policy_document(region: str, bucket: str, prefix: str, read_only: bool = False, kms_key: str | None = None) -> dict:
    """The IAM policy of a Drishti server (read) or loader (read, write and delete: commits, compaction, vacuum) on one prefix."""
    part = A.partition(region)
    objects = f"arn:{part}:s3:::{bucket}/{prefix}/*"
    statements = [
        {"Sid": "DrishtiDeltaList", "Effect": "Allow", "Action": ["s3:ListBucket", "s3:GetBucketLocation"],
         "Resource": f"arn:{part}:s3:::{bucket}", "Condition": {"StringLike": {"s3:prefix": [prefix, f"{prefix}/*"]}}},
        {"Sid": "DrishtiDeltaRead", "Effect": "Allow", "Action": ["s3:GetObject"], "Resource": objects},
    ]
    if not read_only:
        statements.append({"Sid": "DrishtiDeltaWrite", "Effect": "Allow",
                           "Action": ["s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"], "Resource": objects})
    if kms_key:
        statements.append({"Sid": "DrishtiDeltaKms", "Effect": "Allow",
                           "Action": ["kms:Decrypt"] + ([] if read_only else ["kms:GenerateDataKey"]), "Resource": kms_key})
    return {"Version": "2012-10-17", "Statement": statements}


def policy_name(args) -> str:
    return args.policy_name or f"drishti-delta-s3-{args.bucket}" + ("-read" if getattr(args, "read_only", False) else "")


def connector_yaml(bucket: str, prefix: str, region: str) -> str:
    return (f"plugin: delta\nkinds: [...]\nsettings:\n  root: s3://{bucket}/{prefix}\n  domain: risk\n  engine: native\n"
            f"  s3.region: {region}\n")


class DeltaS3Tool:
    def __init__(self, args, sess=None, clients=None):
        self.args = args
        self.run = A.Runner(args.dry_run)
        self.sess = sess
        self.clients = clients or {}
        self.prefix = clean_prefix(args.prefix)

    def client(self, name: str):
        if name not in self.clients:
            self.clients[name] = self.sess.client(name)
        return self.clients[name]

    def region(self) -> str:
        return self.args.region or (self.sess.region_name if self.sess else "")

    def identity(self) -> tuple[str, str]:
        try:
            sts = self.client("sts").get_caller_identity()
        except Exception as e:  # noqa: BLE001
            if self.args.dry_run:
                A.say("no AWS credentials: the plan assumes nothing exists yet")
                return "<no credentials>", "<account>"
            raise A.Failed(f"no AWS credentials in the default chain ({type(e).__name__}): set a profile, environment keys or a role") from e
        return sts["Arn"], sts["Account"]

    def bucket_exists(self) -> bool:
        try:
            self.client("s3").head_bucket(Bucket=self.args.bucket)
            return True
        except Exception as e:  # noqa: BLE001
            if A.error_code(e) in ("404", "NoSuchBucket", "NotFound") or self.args.dry_run:
                return False
            if A.error_code(e) in ("403", "AccessDenied"):
                raise A.Failed(f"bucket {self.args.bucket}: access denied (another account owns the name, or this principal may not see it)") from e
            raise

    def policy_arn(self, account: str) -> str:
        return f"arn:{A.partition(self.region())}:iam::{account}:policy/{policy_name(self.args)}"

    # --- create ---------------------------------------------------------------------------------------------------------------

    def create(self) -> int:
        who, account = self.identity()
        A.say(f"as {who} in {self.region()}")
        s3, b = self.client("s3"), self.args.bucket
        if self.bucket_exists():
            A.say(f"bucket {b} exists: kept, its protections applied again")
        else:
            params = {"Bucket": b, "ObjectOwnership": "BucketOwnerEnforced"}
            if self.region() != "us-east-1":
                params["CreateBucketConfiguration"] = {"LocationConstraint": self.region()}
            self.run.do(s3, "create_bucket", **params)
        self.run.do(s3, "put_public_access_block", Bucket=b, PublicAccessBlockConfiguration={
            "BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True})
        rule = {"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "aws:kms", "KMSMasterKeyID": self.args.kms_key},
                "BucketKeyEnabled": True} if self.args.kms_key else {"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}
        self.run.do(s3, "put_bucket_encryption", Bucket=b, ServerSideEncryptionConfiguration={"Rules": [rule]})
        self.run.do(s3, "put_object", Bucket=b, Key=f"{self.prefix}/", Body=b"")        # the lake's prefix, visible in the console
        self.ensure_policy(account)
        print(json.dumps({"root": f"s3://{b}/{self.prefix}", "region": self.region(), "policy": policy_name(self.args)}, indent=2))
        A.say("the connector file's settings (config/connectors.examples/delta-s3/):\n" + connector_yaml(b, self.prefix, self.region()))
        return A.EXIT_OK

    def ensure_policy(self, account: str) -> None:
        iam = self.client("iam")
        parn = self.policy_arn(account)
        doc = json.dumps(policy_document(self.region(), self.args.bucket, self.prefix, self.args.read_only, self.args.kms_key))
        A.ensure_policy(self.run, iam, parn, policy_name(self.args), doc, f"Drishti Delta Lake connector on s3://{self.args.bucket}/{self.prefix}", self.args.dry_run)
        if self.args.role:
            self.run.do(iam, "attach_role_policy", RoleName=self.args.role, PolicyArn=parn)

    # --- check ----------------------------------------------------------------------------------------------------------------

    def tables(self) -> list[str]:
        """<domain>/<table> under the prefix that hold a _delta_log (the Delta connector's layout: <root>/<domain>/<table>)."""
        s3, out = self.client("s3"), []

        def folders(prefix: str) -> list[str]:
            names = []
            for page in s3.get_paginator("list_objects_v2").paginate(Bucket=self.args.bucket, Prefix=prefix, Delimiter="/"):
                names += [c["Prefix"] for c in page.get("CommonPrefixes", [])]
            return names

        for domain in folders(f"{self.prefix}/"):
            for table in folders(domain):
                r = s3.list_objects_v2(Bucket=self.args.bucket, Prefix=f"{table}_delta_log/", MaxKeys=1)
                if r.get("KeyCount", len(r.get("Contents", []))):
                    out.append(table[len(self.prefix) + 1:].rstrip("/"))
        return out

    def check(self) -> int:
        who, account = self.identity()
        A.say(f"as {who} in {self.region()}")
        steps: list[str] = []

        def ok(msg):
            steps.append(msg)
            A.say("ok: " + msg)

        if not self.bucket_exists():
            raise A.Failed(f"bucket {self.args.bucket}: not there")
        ok(f"bucket {self.args.bucket}")
        if not self.args.skip_policy:
            try:
                self.client("iam").get_policy(PolicyArn=self.policy_arn(account))
                ok(f"policy {policy_name(self.args)}")
            except Exception as e:  # noqa: BLE001
                raise A.Failed(f"policy {policy_name(self.args)}: {A.error_code(e) or e} (create it, or --skip-policy)") from e
        table = self.args.table.strip("/") if self.args.table else None
        if table is None:
            found = self.tables()
            ok(f"prefix s3://{self.args.bucket}/{self.prefix}/: {len(found)} Delta tables")
            if not found:
                ok("no table yet: load one (lake build, the parallel loader) and check again to read it end to end")
                print(json.dumps({"root": f"s3://{self.args.bucket}/{self.prefix}", "steps": steps}, indent=2))
                return A.EXIT_OK
            table = found[0]
        result = self.read_table(f"{self.prefix}/{table}")
        ok(f"read {table} end to end: {json.dumps(result)}")
        print(json.dumps({"root": f"s3://{self.args.bucket}/{self.prefix}", "table": table, "steps": steps, "read": result}, indent=2))
        return A.EXIT_OK

    def read_table(self, key: str) -> dict:
        """The table's log replayed (the last checkpoint, then the commits after it) and its first live file read."""
        s3, b = self.client("s3"), self.args.bucket
        log = f"{key}/_delta_log/"
        names = []
        for page in s3.get_paginator("list_objects_v2").paginate(Bucket=b, Prefix=log):
            names += [o["Key"][len(log):] for o in page.get("Contents", [])]
        commits = sorted(int(m.group(1)) for n in names if (m := COMMIT.match(n)))
        if not commits and not any(CHECKPOINT.match(n) for n in names):
            raise A.Failed(f"s3://{b}/{log}: no commits (not a Delta table)")
        live: dict[str, dict] = {}
        start = 0
        checkpoint = None
        if "_last_checkpoint" in names:
            checkpoint = json.loads(s3.get_object(Bucket=b, Key=log + "_last_checkpoint")["Body"].read())["version"]
            parts = sorted(n for n in names if (m := CHECKPOINT.match(n)) and int(m.group(1)) == checkpoint)
            if not parts:
                raise A.Failed(f"_last_checkpoint names version {checkpoint}, which has no classic checkpoint file this check reads")
            for part in parts:
                for add in self._checkpoint_adds(s3, b, log + part):
                    live[add["path"]] = add
            start = checkpoint + 1
        version = checkpoint if checkpoint is not None else -1
        for v in (c for c in commits if c >= start):
            body = s3.get_object(Bucket=b, Key=f"{log}{v:020d}.json")["Body"].read().decode("utf-8")
            for line in body.splitlines():
                if not line.strip():
                    continue
                action = json.loads(line)
                if "add" in action:
                    live[action["add"]["path"]] = action["add"]
                elif "remove" in action:
                    live.pop(action["remove"]["path"], None)
            version = v
        if not live:
            return {"version": version, "files": 0, "note": "the table holds no live file"}
        path, add = sorted(live.items())[0]
        uri = path if "://" in path else f"s3://{b}/{key}/{urllib.parse.unquote(path)}"
        out = A.read_parquet(s3, uri)
        stats = json.loads(add["stats"]) if isinstance(add.get("stats"), str) and add["stats"] else {}
        out.update({"version": version, "checkpoint": checkpoint, "files": len(live), "stats-records": stats.get("numRecords")})
        if out["stats-records"] is not None and out["stats-records"] != out["rows"] and not add.get("deletionVector"):
            raise A.Failed(f"{uri}: the log says {out['stats-records']} rows, the file holds {out['rows']}")
        return out

    @staticmethod
    def _checkpoint_adds(s3, bucket: str, key: str):
        import pyarrow.parquet as pq
        data = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
        t = pq.read_table(io.BytesIO(data), columns=["add"]).column("add").to_pylist()
        return [a for a in t if a]

    # --- remove ---------------------------------------------------------------------------------------------------------------

    def remove(self) -> int:
        if not self.args.yes and not self.args.dry_run:
            raise A.Failed("remove deletes AWS resources: add --yes (or --dry-run to see what it would do)")
        who, account = self.identity()
        A.say(f"as {who} in {self.region()}")
        iam, s3, b = self.client("iam"), self.client("s3"), self.args.bucket
        parn = self.policy_arn(account)
        A.delete_policy(self.run, iam, parn, policy_name(self.args), self.args.dry_run)
        if not self.bucket_exists():
            A.say(f"bucket {b}: not there")
            return A.EXIT_OK
        keys = []
        for page in s3.get_paginator("list_objects_v2").paginate(Bucket=b, Prefix=f"{self.prefix}/"):
            keys += [o["Key"] for o in page.get("Contents", [])]
        tables = [k for k in keys if k.endswith("/_delta_log/") or "/_delta_log/" in k and COMMIT.search(k)]
        if keys and not self.args.purge:
            A.say(f"s3://{b}/{self.prefix}/ holds {len(keys)} objects ({len({t.split('/_delta_log/')[0] for t in tables})} tables): "
                  "kept (add --purge to delete them)")
        elif keys:
            for i in range(0, len(keys), 1000):
                self.run.do(s3, "delete_objects", Bucket=b, Delete={"Objects": [{"Key": k} for k in keys[i:i + 1000]], "Quiet": True})
        if self.args.delete_bucket:
            others = s3.list_objects_v2(Bucket=b, MaxKeys=1000).get("Contents", [])
            others = [o["Key"] for o in others if not (self.args.purge and o["Key"].startswith(f"{self.prefix}/"))]
            if others:
                raise A.Failed(f"bucket {b} still holds {len(others)}+ objects outside what was purged ({others[0]}, ...): it is kept")
            if keys and not self.args.purge:
                raise A.Failed(f"bucket {b} still holds the lake: add --purge to delete it with the bucket")
            self.run.do(s3, "delete_bucket", Bucket=b)
        return A.EXIT_OK


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="delta_s3.py", description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    for name, text in (("create", "create the bucket (private, encrypted), the lake's prefix and the IAM policy (kept when they exist)"),
                       ("check", "check them and read one Delta table end to end"),
                       ("remove", "remove the policy (the lake's objects and the bucket only when asked)"),
                       ("policy", "print the IAM policy document (no AWS call)")):
        c = A.common_options(sub.add_parser(name, help=text, description=text))
        c.add_argument("--bucket", default=None, help="the bucket (default: DRISHTI_DELTA_S3_BUCKET, else LAKE_BUCKET)")
        c.add_argument("--prefix", default=None, help="the lake's prefix, the connector's root under the bucket "
                                                      "(default: DRISHTI_DELTA_S3_PREFIX, else LAKE_PREFIX, else delta)")
        c.add_argument("--policy-name", default=None, help="the IAM policy (default: drishti-delta-s3-<bucket>[-read])")
        c.add_argument("--read-only", action="store_true", help="a server's policy: reads only (the loader's adds writes and deletes)")
        c.add_argument("--kms-key", default=None, help="a KMS key ARN: the bucket's default encryption (create) and the policy's kms:*")
        if name == "create":
            c.add_argument("--role", default=None, help="an IAM role to attach the policy to (the ECS task role)")
        if name == "check":
            c.add_argument("--table", default=None, help="the table to read, <domain>/<table> (default: the first found)")
            c.add_argument("--skip-policy", action="store_true", help="do not look for the IAM policy (a role managed elsewhere)")
        if name == "remove":
            c.add_argument("--purge", action="store_true", help="also delete every object under the prefix (the tables)")
            c.add_argument("--delete-bucket", action="store_true", help="also delete the bucket (only when nothing else is in it)")
    return p


def main(argv=None, sess=None, clients=None) -> int:
    args = parser().parse_args(argv)
    A.load_env_file(args.env)
    args.bucket = args.bucket or A.env("DRISHTI_DELTA_S3_BUCKET", "LAKE_BUCKET")
    args.prefix = clean_prefix(args.prefix or A.env("DRISHTI_DELTA_S3_PREFIX", "LAKE_PREFIX", default="delta"))
    if not args.bucket:
        A.say("name the bucket: --bucket NAME (or DRISHTI_DELTA_S3_BUCKET)")
        return A.EXIT_USAGE
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", args.bucket):
        A.say(f"--bucket {args.bucket}: 3 to 63 lower-case letters, digits, dots and hyphens")
        return A.EXIT_USAGE
    if not args.prefix:
        A.say("--prefix is empty: the lake needs its own prefix in the bucket (such as delta)")
        return A.EXIT_USAGE
    if args.command == "policy":
        print(json.dumps(policy_document(A.region_of(args) or "us-east-1", args.bucket, args.prefix, args.read_only, args.kms_key), indent=2))
        return A.EXIT_OK
    if args.dry_run:
        A.say("dry run: nothing is changed; the calls that would change something follow")
    tool = DeltaS3Tool(args, sess if sess is not None or clients else A.session(args), clients)
    return {"create": tool.create, "check": tool.check, "remove": tool.remove}[args.command]()


if __name__ == "__main__":
    sys.exit(A.run(main))
