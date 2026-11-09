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

"""The AWS side of Drishti's S3 Tables connector (iceberg plugin, catalog: s3tables): a table bucket, a namespace for a Drishti domain
and the IAM policy the server and the loaders need, created, checked and removed with boto3 (no AWS CLI, so Windows works).

    s3_tables.py create --bucket drishti-risk --namespace risk [--role drishti-server] [--read-only]
    s3_tables.py check  --bucket drishti-risk --namespace risk [--table pnl_vector]
    s3_tables.py remove --bucket drishti-risk --namespace risk [--delete-tables] [--delete-bucket] --yes
    s3_tables.py policy --bucket drishti-risk [--read-only]          the IAM policy document, printed (no AWS call)

Every command takes --region/--profile (else AWS_REGION/AWS_PROFILE and the default credential chain), --env FILE and --dry-run.
`check` reads one table end to end the way Drishti does: the S3 Tables API, then the Iceberg REST endpoint with SigV4 (config,
loadTable), the metadata file, the manifest list, a manifest and the first data file's Parquet footer and first row group, from S3.
Exit: 0 done (or the check passed), 1 a step failed (named), 2 usage. Guide: deploy/aws/s3-tables/README.md."""
from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import drishti_aws as A  # noqa: E402
import mini_avro  # noqa: E402

READ_ACTIONS = ["s3tables:GetTableBucket", "s3tables:ListNamespaces", "s3tables:GetNamespace", "s3tables:ListTables",
                "s3tables:GetTable", "s3tables:GetTableMetadataLocation", "s3tables:GetTableData"]
WRITE_ACTIONS = ["s3tables:CreateNamespace", "s3tables:CreateTable", "s3tables:UpdateTableMetadataLocation", "s3tables:PutTableData"]


def bucket_arn(region: str, account: str, bucket: str) -> str:
    return f"arn:{A.partition(region)}:s3tables:{region}:{account}:bucket/{bucket}"


def endpoint(region: str) -> str:
    return f"https://s3tables.{region}.amazonaws.com{'.cn' if region.startswith('cn-') else ''}/iceberg"


def s3_name(name: str) -> str:
    """A kind or domain as an S3 Tables name, as the connector maps it (pnl-vector -> pnl_vector)."""
    return name.strip().lower().replace("-", "_").replace(".", "_")


def policy_document(arn: str, read_only: bool = False) -> dict:
    """The IAM policy of a Drishti server (read) or loader (read and write) on one table bucket."""
    return {"Version": "2012-10-17", "Statement": [
        {"Sid": "DrishtiS3TablesRead", "Effect": "Allow", "Action": READ_ACTIONS, "Resource": [arn, f"{arn}/table/*"]},
    ] + ([] if read_only else [
        {"Sid": "DrishtiS3TablesWrite", "Effect": "Allow", "Action": WRITE_ACTIONS, "Resource": [arn, f"{arn}/table/*"]},
    ])}


def policy_name(args) -> str:
    return args.policy_name or f"drishti-s3tables-{args.bucket}" + ("-read" if getattr(args, "read_only", False) else "")


def connector_yaml(arn: str, domain: str) -> str:
    return (f"plugin: iceberg\nkinds: [...]\nsettings:\n  catalog: s3tables\n  table-bucket-arn: {arn}\n  domain: {domain}\n"
            f"  engine: native\n")


class S3TablesTool:
    """The three commands over one session (clients injected by the tests)."""

    def __init__(self, args, sess=None, clients=None, http=None):
        self.args = args
        self.run = A.Runner(args.dry_run)
        self.sess = sess
        self.clients = clients or {}
        self.http = http or A.http_json

    def client(self, name: str):
        if name not in self.clients:
            self.clients[name] = self.sess.client(name)
        return self.clients[name]

    def identity(self) -> tuple[str, str]:
        try:
            sts = self.client("sts").get_caller_identity()
        except Exception as e:  # noqa: BLE001
            if self.args.dry_run:
                A.say("no AWS credentials: the plan assumes nothing exists yet")
                return "<no credentials>", "<account>"
            raise A.Failed(f"no AWS credentials in the default chain ({type(e).__name__}): set a profile, environment keys or a role") from e
        return sts["Arn"], sts["Account"]

    def region(self) -> str:
        return self.args.region or (self.sess.region_name if self.sess else "")

    # --- create ---------------------------------------------------------------------------------------------------------------

    def find_bucket(self, account: str) -> str | None:
        arn = bucket_arn(self.region(), account, self.args.bucket)
        try:
            self.client("s3tables").get_table_bucket(tableBucketARN=arn)
            return arn
        except Exception as e:  # noqa: BLE001
            if A.error_code(e) in ("NotFoundException", "NoSuchBucket", "NotFound") or self.args.dry_run:
                return None
            raise

    def create(self) -> int:
        who, account = self.identity()
        A.say(f"as {who} in {self.region()}")
        s3t = self.client("s3tables")
        arn = self.find_bucket(account)
        if arn:
            A.say(f"table bucket {self.args.bucket} exists: kept")
        else:
            r = self.run.do(s3t, "create_table_bucket", name=self.args.bucket)
            arn = (r or {}).get("arn") or bucket_arn(self.region(), account, self.args.bucket)
        ns = s3_name(self.args.namespace)
        if self.namespace_exists(arn, ns):
            A.say(f"namespace {ns} exists: kept")
        else:
            self.run.do(s3t, "create_namespace", tableBucketARN=arn, namespace=[ns])
        self.ensure_policy(account, arn)
        print(json.dumps({"table-bucket-arn": arn, "namespace": ns, "endpoint": endpoint(self.region()),
                          "policy": policy_name(self.args)}, indent=2))
        A.say("the connector file's settings (config/connectors.examples/s3tables/):\n" + connector_yaml(arn, self.args.namespace))
        return A.EXIT_OK

    def namespace_exists(self, arn: str, ns: str) -> bool:
        try:
            self.client("s3tables").get_namespace(tableBucketARN=arn, namespace=ns)
            return True
        except Exception as e:  # noqa: BLE001
            if A.error_code(e) in ("NotFoundException", "NotFound"):
                return False
            if self.args.dry_run:
                return False
            raise

    def policy_arn(self, account: str) -> str:
        return f"arn:{A.partition(self.region())}:iam::{account}:policy/{policy_name(self.args)}"

    def ensure_policy(self, account: str, arn: str) -> None:
        iam = self.client("iam")
        parn = self.policy_arn(account)
        doc = json.dumps(policy_document(arn, self.args.read_only))
        A.ensure_policy(self.run, iam, parn, policy_name(self.args), doc, f"Drishti S3 Tables connector on {self.args.bucket}", self.args.dry_run)
        if self.args.role:
            self.run.do(iam, "attach_role_policy", RoleName=self.args.role, PolicyArn=parn)

    # --- check ----------------------------------------------------------------------------------------------------------------

    def check(self) -> int:
        who, account = self.identity()
        A.say(f"as {who} in {self.region()}")
        s3t = self.client("s3tables")
        arn = self.args.arn or bucket_arn(self.region(), account, self.args.bucket)
        steps: list[str] = []

        def ok(msg):
            steps.append(msg)
            A.say("ok: " + msg)

        try:
            s3t.get_table_bucket(tableBucketARN=arn)
        except Exception as e:  # noqa: BLE001
            raise A.Failed(f"table bucket {arn}: {A.error_code(e) or e}") from e
        ok(f"table bucket {arn}")
        ns = s3_name(self.args.namespace)
        try:
            s3t.get_namespace(tableBucketARN=arn, namespace=ns)
        except Exception as e:  # noqa: BLE001
            raise A.Failed(f"namespace {ns}: {A.error_code(e) or e}") from e
        tables = [t["name"] for t in s3t.list_tables(tableBucketARN=arn, namespace=ns).get("tables", [])]
        ok(f"namespace {ns}: {len(tables)} tables")
        if not self.args.skip_policy:
            try:
                self.client("iam").get_policy(PolicyArn=self.policy_arn(account))
                ok(f"policy {policy_name(self.args)}")
            except Exception as e:  # noqa: BLE001
                raise A.Failed(f"policy {policy_name(self.args)}: {A.error_code(e) or e} (create it, or --skip-policy)") from e
        table = s3_name(self.args.table) if self.args.table else (tables[0] if tables else None)
        if table is None:
            ok("no table yet: load one (Drishti's loader, catalog: s3tables) and check again to read it end to end")
            return A.EXIT_OK
        loc = s3t.get_table_metadata_location(tableBucketARN=arn, namespace=ns, name=table)["metadataLocation"]
        ok(f"table {ns}.{table}: metadata {loc}")
        rest_loc = self.rest_load(arn, ns, table)
        if rest_loc and rest_loc != loc:
            raise A.Failed(f"the REST endpoint names {rest_loc}, the S3 Tables API {loc}")
        ok(f"Iceberg REST endpoint {endpoint(self.region())}: config and loadTable signed for s3tables")
        result = self.read_table(loc)
        ok(f"read end to end: {json.dumps(result)}")
        print(json.dumps({"table-bucket-arn": arn, "namespace": ns, "table": table, "steps": steps, "read": result}, indent=2))
        return A.EXIT_OK

    def rest_load(self, arn: str, ns: str, table: str) -> str | None:
        """config then loadTable through the Iceberg REST endpoint, signed as Drishti signs them; the metadata location it names."""
        creds = self.sess.get_credentials().get_frozen_credentials() if self.sess else None
        base = endpoint(self.region())
        url = f"{base}/v1/config?warehouse={urllib.parse.quote(arn, safe='')}"
        status, body = self.http("GET", url, A.sign(creds, "GET", url, self.region(), "s3tables"))
        if status != 200:
            raise A.Failed(f"GET {url}: HTTP {status} {body}")
        prefix = (body or {}).get("overrides", {}).get("prefix") or urllib.parse.quote(arn, safe="")
        if ":" in prefix or "/" in prefix:
            prefix = urllib.parse.quote(prefix, safe="")
        url = f"{base}/v1/{prefix}/namespaces/{urllib.parse.quote(ns, safe='')}/tables/{urllib.parse.quote(table, safe='')}"
        status, body = self.http("GET", url, A.sign(creds, "GET", url, self.region(), "s3tables"))
        if status != 200:
            raise A.Failed(f"loadTable {ns}.{table}: HTTP {status} {body}")
        return (body or {}).get("metadata-location")

    def read_table(self, metadata_location: str) -> dict:
        s3 = self.client("s3")
        b, k = A.split_s3(metadata_location)
        meta = json.loads(s3.get_object(Bucket=b, Key=k)["Body"].read())
        snap_id = meta.get("current-snapshot-id")
        if snap_id in (None, -1):
            return {"format-version": meta.get("format-version"), "snapshot": None, "note": "the table has no snapshot (no data yet)"}
        snap = next(s for s in meta.get("snapshots", []) if s["snapshot-id"] == snap_id)
        b, k = A.split_s3(snap["manifest-list"])
        _, manifests = mini_avro.read(s3.get_object(Bucket=b, Key=k)["Body"].read())
        data_manifests = [m for m in manifests if m.get("content", 0) == 0]
        if not data_manifests:
            return {"snapshot": snap_id, "manifests": 0, "note": "the snapshot lists no data manifests"}
        b, k = A.split_s3(data_manifests[0]["manifest_path"])
        _, entries = mini_avro.read(s3.get_object(Bucket=b, Key=k)["Body"].read())
        live = [e["data_file"] for e in entries if e.get("status") != 2]
        if not live:
            return {"snapshot": snap_id, "manifests": len(manifests), "note": "the first manifest holds no live data file"}
        out = A.read_parquet(s3, live[0]["file_path"])
        out.update({"snapshot": snap_id, "manifests": len(manifests), "summary-records": snap.get("summary", {}).get("total-records"),
                    "manifest-record-count": live[0].get("record_count")})
        if out["manifest-record-count"] is not None and out["manifest-record-count"] != out["rows"]:
            raise A.Failed(f"{live[0]['file_path']}: the manifest says {out['manifest-record-count']} rows, the file holds {out['rows']}")
        return out

    # --- remove ---------------------------------------------------------------------------------------------------------------

    def remove(self) -> int:
        if not self.args.yes and not self.args.dry_run:
            raise A.Failed("remove deletes AWS resources: add --yes (or --dry-run to see what it would do)")
        who, account = self.identity()
        A.say(f"as {who} in {self.region()}")
        s3t, iam = self.client("s3tables"), self.client("iam")
        arn = bucket_arn(self.region(), account, self.args.bucket)
        ns = s3_name(self.args.namespace)
        exists = self.namespace_exists(arn, ns)
        if exists:
            tables = [t["name"] for t in s3t.list_tables(tableBucketARN=arn, namespace=ns).get("tables", [])]
            if tables and not self.args.delete_tables:
                raise A.Failed(f"namespace {ns} holds {len(tables)} tables ({', '.join(tables[:5])}): add --delete-tables to delete them")
            for t in tables:
                self.run.do(s3t, "delete_table", tableBucketARN=arn, namespace=ns, name=t)
            self.run.do(s3t, "delete_namespace", tableBucketARN=arn, namespace=ns)
        else:
            A.say(f"namespace {ns}: not there")
        parn = self.policy_arn(account)
        A.delete_policy(self.run, iam, parn, policy_name(self.args), self.args.dry_run)
        if self.args.delete_bucket:
            left = [n["namespace"] for n in s3t.list_namespaces(tableBucketARN=arn).get("namespaces", [])] if self.find_bucket(account) else None
            if left is None:
                A.say(f"table bucket {self.args.bucket}: not there")
            else:
                others = [n for n in left if n != [ns]]           # the namespace just removed is still listed in a dry run
                if others:
                    raise A.Failed(f"table bucket {self.args.bucket} still holds the namespaces {others}: remove them first")
                self.run.do(s3t, "delete_table_bucket", tableBucketARN=arn)
        return A.EXIT_OK


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="s3_tables.py", description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    for name, text in (("create", "create the table bucket, the namespace and the IAM policy (kept when they exist)"),
                       ("check", "check them and read one table end to end"),
                       ("remove", "remove the namespace and the policy (the tables and the bucket only when asked)"),
                       ("policy", "print the IAM policy document (no AWS call)")):
        c = A.common_options(sub.add_parser(name, help=text, description=text))
        c.add_argument("--bucket", default=A.env("DRISHTI_S3TABLES_BUCKET"), help="the table bucket (default: DRISHTI_S3TABLES_BUCKET)")
        c.add_argument("--policy-name", default=None, help="the IAM policy (default: drishti-s3tables-<bucket>[-read])")
        c.add_argument("--read-only", action="store_true", help="a server's policy: reads only (the loader's adds the writes)")
        if name != "policy":
            c.add_argument("--namespace", default=A.env("DRISHTI_S3TABLES_NAMESPACE", default="risk"),
                           help="the namespace, a Drishti domain (default: DRISHTI_S3TABLES_NAMESPACE, else risk)")
        if name == "create":
            c.add_argument("--role", default=None, help="an IAM role to attach the policy to (the ECS task role)")
        if name == "check":
            c.add_argument("--arn", default=A.env("DRISHTI_S3TABLES_ARN"), help="the table bucket ARN (default: built from --bucket)")
            c.add_argument("--table", default=None, help="the table to read (a kind such as pnl-vector; default: the first)")
            c.add_argument("--skip-policy", action="store_true", help="do not look for the IAM policy (a role managed elsewhere)")
        if name == "remove":
            c.add_argument("--delete-tables", action="store_true", help="also delete the namespace's tables and their data")
            c.add_argument("--delete-bucket", action="store_true", help="also delete the table bucket (it must hold no other namespace)")
    return p


def main(argv=None, sess=None, clients=None, http=None) -> int:
    args = parser().parse_args(argv)
    A.load_env_file(args.env)
    if not args.bucket and not getattr(args, "arn", None):
        A.say("name the table bucket: --bucket NAME (or DRISHTI_S3TABLES_BUCKET)")
        return A.EXIT_USAGE
    if args.bucket and not args.bucket.replace("-", "").isalnum():
        A.say(f"--bucket {args.bucket}: 3 to 63 lower-case letters, digits and hyphens")
        return A.EXIT_USAGE
    if not args.bucket:
        args.bucket = args.arn.rsplit("/", 1)[-1]
    if args.command == "policy":
        region = A.region_of(args) or "<region>"
        print(json.dumps(policy_document(bucket_arn(region, "<account>", args.bucket), args.read_only), indent=2))
        return A.EXIT_OK
    if args.dry_run:
        A.say("dry run: nothing is changed; the calls that would change something follow")
    tool = S3TablesTool(args, sess if sess is not None or clients else A.session(args), clients, http)
    return {"create": tool.create, "check": tool.check, "remove": tool.remove}[args.command]()


if __name__ == "__main__":
    sys.exit(A.run(main))
