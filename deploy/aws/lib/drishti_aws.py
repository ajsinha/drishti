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

"""What the Python AWS tool scripts share (deploy/aws/s3-tables/s3_tables.py, deploy/aws/delta-s3/delta_s3.py): the common options
(--region, --profile, --env, --dry-run, --yes), a boto3 session from them, a runner that prints every changing AWS call and makes none
in a dry run, SigV4 signing of a plain HTTPS request (the S3 Tables Iceberg REST check), and a file-like view of an S3 object read with
ranged GETs (a Parquet footer without the whole file).

Secrets are never printed: credentials stay in the session, a printed call has every key that looks secret masked, and the only
identity shown is the caller's ARN."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SECRET_KEY = re.compile(r"secret|token|password|credential|session", re.I)
EXIT_OK, EXIT_FAILED, EXIT_USAGE = 0, 1, 2


class Failed(Exception):
    """A step that did not work: the script prints the message and exits 1."""


def say(msg: str) -> None:
    print(f"{Path(sys.argv[0]).name}: {msg}", file=sys.stderr)


def masked(value):
    """A value with every secret-looking key masked (for printing a call)."""
    if isinstance(value, dict):
        return {k: ("***" if SECRET_KEY.search(str(k)) else masked(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [masked(v) for v in value]
    return value


def load_env_file(path: str | None) -> None:
    """KEY=VALUE lines (deploy/aws/env, the bash scripts' settings file) into the environment, never over a variable already set.
    ${VAR} references to earlier keys are expanded; quotes and trailing comments are dropped."""
    if not path:
        return
    p = Path(path)
    if not p.is_file():
        raise Failed(f"no settings file {path}")
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip().removeprefix("export ").strip()
        value = value.strip()
        if value[:1] in "\"'":
            q = value[0]
            value = value[1:value.find(q, 1)] if value.find(q, 1) > 0 else value[1:]
        else:
            value = value.split(" #", 1)[0].split("\t#", 1)[0].strip()
        value = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", lambda m: os.environ.get(m.group(1), ""), value)
        if key and key not in os.environ:
            os.environ[key] = value


def common_options(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--region", default=None, help="AWS region (default: AWS_REGION, AWS_DEFAULT_REGION, then the profile's)")
    p.add_argument("--profile", default=None, help="AWS profile (default: AWS_PROFILE, else the default credential chain)")
    p.add_argument("--env", default=None, help="a KEY=VALUE settings file, such as deploy/aws/env (variables already set win)")
    p.add_argument("--dry-run", action="store_true", help="print the AWS calls that would change something; change nothing")
    p.add_argument("--yes", action="store_true", help="confirm a removal (needed for anything that deletes)")
    return p


def env(*names: str, default: str = "") -> str:
    """The first of the environment variables that is set and is not a <placeholder>."""
    for n in names:
        v = os.environ.get(n, "").strip()
        if v and not (v.startswith("<") and v.endswith(">")):
            return v
    return default


def region_of(args) -> str:
    return args.region or env("AWS_REGION", "AWS_DEFAULT_REGION")


def session(args):
    """A boto3 session for --profile/AWS_PROFILE and --region/AWS_REGION (credentials from the default chain)."""
    try:
        import boto3
        import botocore.exceptions
    except ImportError as e:
        raise Failed("boto3 is not installed for this Python: pip install boto3 pyarrow, or set DRISHTI_PYTHON to drishti-console's .venv") from e
    profile = args.profile or env("AWS_PROFILE") or None
    try:
        s = boto3.Session(profile_name=profile, region_name=region_of(args) or None)
    except botocore.exceptions.ProfileNotFound as e:
        raise Failed(f"no AWS profile {profile} (~/.aws/config)") from e
    if not s.region_name:
        raise Failed("no region: give --region or set AWS_REGION")
    return s


def partition(region: str) -> str:
    return "aws-cn" if region.startswith("cn-") else "aws-us-gov" if region.startswith("us-gov-") else "aws"


class Runner:
    """Makes the AWS calls that change something, printing each first; in a dry run prints them only. Reads go through ask()."""

    def __init__(self, dry_run: bool, out=None):
        self.dry_run = dry_run
        self.out = out or sys.stderr
        self.calls: list[str] = []

    def _show(self, client, op: str, params: dict) -> str:
        service = client.meta.service_model.service_name if hasattr(client, "meta") else "aws"
        text = f"{service}.{op} {json.dumps(masked(params), sort_keys=True, default=str)}"
        self.calls.append(text)
        return text

    def do(self, client, op: str, **params):
        print(("would call " if self.dry_run else "+ ") + self._show(client, op, params), file=self.out)
        if self.dry_run:
            return None
        return getattr(client, op)(**params)

    def ask(self, client, op: str, **params):
        """A read. In a dry run it is made too when the client can (reads change nothing), so the plan is accurate."""
        return getattr(client, op)(**params)


def error_code(e: Exception) -> str:
    return str(getattr(e, "response", {}).get("Error", {}).get("Code", "")) if hasattr(e, "response") else ""


def who(sess) -> str:
    """The caller's ARN (the only identity ever printed)."""
    try:
        return sess.client("sts").get_caller_identity()["Arn"]
    except Exception as e:  # noqa: BLE001 - any failure here means no usable credentials
        raise Failed(f"no AWS credentials in the default chain ({type(e).__name__}): set a profile, environment keys or a role") from e


def account(sess) -> str:
    return sess.client("sts").get_caller_identity()["Account"]


# --- SigV4 for a plain HTTPS request (S3 Tables' Iceberg REST endpoint) ------------------------------------------------------------

def sign(credentials, method: str, url: str, region: str, service: str, body: bytes | None = None,
         when: datetime | None = None) -> dict[str, str]:
    """The headers of a SigV4-signed request, shaped as Drishti's Java REST client sends it (drishti-iceberg SigV4): Accept and,
    with a body, Content-Type, X-Amz-Content-Sha256 of the payload, X-Amz-Date, the session token when there is one, Authorization.
    ``when`` fixes the clock (tests compare with recorded signatures)."""
    import botocore.auth
    from botocore.awsrequest import AWSRequest
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    headers["X-Amz-Content-SHA256"] = hashlib.sha256(body or b"").hexdigest()
    req = AWSRequest(method=method, url=url, data=body or b"", headers=headers)
    signer = botocore.auth.SigV4Auth(credentials, service, region)
    if when is None:
        signer.add_auth(req)
    else:
        real = botocore.auth.get_current_datetime
        botocore.auth.get_current_datetime = lambda: when.astimezone(timezone.utc).replace(tzinfo=None)
        try:
            signer.add_auth(req)
        finally:
            botocore.auth.get_current_datetime = real
    return dict(req.headers.items())


def http_json(method: str, url: str, headers: dict[str, str], body: bytes | None = None, timeout: float = 30.0):
    """(status, parsed JSON or None) of an HTTPS request; HTTP errors are answers, not exceptions."""
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - https URLs built by the script
            raw = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read(), e.code
    try:
        return status, json.loads(raw) if raw else None
    except ValueError:
        return status, {"text": raw[:500].decode("utf-8", "replace")}


# --- An S3 object as a seekable file (pyarrow reads a Parquet footer with ranged GETs) ---------------------------------------------

class S3RangeFile(io.RawIOBase):
    """A read-only, seekable view of one S3 object; each read is one ranged GET."""

    def __init__(self, s3, bucket: str, key: str, size: int | None = None):
        self.s3, self.bucket, self.key = s3, bucket, key
        self.size = size if size is not None else s3.head_object(Bucket=bucket, Key=key)["ContentLength"]
        self.pos = 0
        self.gets = 0

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return True

    def tell(self) -> int:
        return self.pos

    def seek(self, offset: int, whence: int = 0) -> int:
        self.pos = offset if whence == 0 else self.pos + offset if whence == 1 else self.size + offset
        return self.pos

    def readinto(self, b) -> int:
        if self.pos >= self.size or len(b) == 0:
            return 0
        end = min(self.size, self.pos + len(b)) - 1
        data = self.s3.get_object(Bucket=self.bucket, Key=self.key, Range=f"bytes={self.pos}-{end}")["Body"].read()
        self.gets += 1
        b[:len(data)] = data
        self.pos += len(data)
        return len(data)


def split_s3(uri: str) -> tuple[str, str]:
    """(bucket, key) of s3://bucket/key (s3a:// and s3n:// too)."""
    m = re.match(r"s3[an]?://([^/]+)/?(.*)$", uri)
    if not m:
        raise Failed(f"not an S3 location: {uri}")
    return m.group(1), m.group(2)


def read_parquet(s3, uri: str) -> dict:
    """One Parquet file read through its footer and first row group: rows, row groups, columns, rows read, GETs made."""
    import pyarrow.parquet as pq
    bucket, key = split_s3(uri)
    raw = S3RangeFile(s3, bucket, key)
    with io.BufferedReader(raw, buffer_size=1 << 20) as f:
        pf = pq.ParquetFile(f)
        meta = pf.metadata
        first = pf.read_row_group(0).num_rows if meta.num_row_groups else 0
    return {"file": uri, "bytes": raw.size, "rows": meta.num_rows, "row_groups": meta.num_row_groups,
            "columns": meta.num_columns, "first_row_group_rows": first, "gets": raw.gets}


def run(main, argv=None) -> int:
    """Runs a script's main with the shared exit codes: 0 done, 1 a step failed (its message printed), 2 usage."""
    try:
        return main(argv)
    except Failed as e:
        say(str(e))
        return EXIT_FAILED
    except KeyboardInterrupt:
        say("interrupted")
        return EXIT_FAILED
