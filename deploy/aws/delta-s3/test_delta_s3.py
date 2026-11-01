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

"""delta_s3.py without an AWS account: the S3, IAM and STS calls that make, check and remove resources answered by botocore's
Stubber (each call and its parameters checked, in order); the check's reads by an in-memory S3 holding real Delta tables written
with the deltalake package (commits only, and a checkpoint followed by more commits, with an overwrite that removes files).

    python -m pytest -q -p no:cacheprovider deploy/aws/delta-s3
"""
from __future__ import annotations

import io
import json
import os
import sys
from pathlib import Path

import boto3
import pytest
from botocore.stub import ANY, Stubber

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "lib"))
import delta_s3 as D  # noqa: E402
import drishti_aws as A  # noqa: E402

REGION, ACCOUNT, BUCKET = "eu-west-2", "111122223333", "acme-drishti-lake"
POLICY = f"arn:aws:iam::{ACCOUNT}:policy/drishti-delta-s3-{BUCKET}"
SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY-NEVER-PRINTED"


def boto(service):
    return boto3.Session(region_name=REGION, aws_access_key_id="AKIDEXAMPLE", aws_secret_access_key=SECRET).client(service)


def sts_stub():
    sts = boto("sts")
    st = Stubber(sts)
    st.add_response("get_caller_identity", {"Arn": f"arn:aws:sts::{ACCOUNT}:assumed-role/ops/me", "Account": ACCOUNT, "UserId": "x"}, {})
    return sts, st


class FakeS3:
    """A bucket in memory: head_bucket, list_objects_v2 (prefix, delimiter, MaxKeys) and its paginator, head and ranged get."""

    def __init__(self, objects: dict[str, bytes]):
        self.objects = objects
        self.ranged = []

    def head_bucket(self, Bucket):
        return {}

    def head_object(self, Bucket, Key):
        return {"ContentLength": len(self.objects[Key])}

    def get_object(self, Bucket, Key, Range=None):
        data = self.objects[Key]
        if Range:
            self.ranged.append(Key)
            a, b = Range.removeprefix("bytes=").split("-")
            data = data[int(a):int(b) + 1]
        return {"Body": io.BytesIO(data)}

    def list_objects_v2(self, Bucket, Prefix="", Delimiter=None, MaxKeys=1000, **_):
        keys = sorted(k for k in self.objects if k.startswith(Prefix))
        if Delimiter:
            common = sorted({Prefix + k[len(Prefix):].split(Delimiter)[0] + Delimiter for k in keys if Delimiter in k[len(Prefix):]})
            contents = [k for k in keys if Delimiter not in k[len(Prefix):]]
            return {"CommonPrefixes": [{"Prefix": c} for c in common], "Contents": [{"Key": k} for k in contents][:MaxKeys],
                    "KeyCount": len(common) + len(contents)}
        return {"Contents": [{"Key": k} for k in keys[:MaxKeys]], "KeyCount": min(len(keys), MaxKeys)}

    def get_paginator(self, name):
        fake = self

        class P:
            def paginate(self, **kw):
                yield fake.list_objects_v2(**kw)
        return P()


def lake(tmp_path, checkpoint: bool) -> dict[str, bytes]:
    """A Delta table risk/pnl-vector written with the deltalake package, as objects under the prefix delta/."""
    pytest.importorskip("deltalake")
    import pyarrow as pa
    from deltalake import DeltaTable, write_deltalake
    t = tmp_path / "delta" / "risk" / "pnl-vector"

    def rows(n, day):
        return pa.table({"id": [f"P-{i}" for i in range(n)], "doc": ["{}"] * n, "business_date": [day] * n})

    write_deltalake(str(t), rows(30, "2026-10-29"), partition_by=["business_date"])
    write_deltalake(str(t), rows(40, "2026-10-30"), partition_by=["business_date"], mode="append")
    if checkpoint:
        DeltaTable(str(t)).create_checkpoint()
    write_deltalake(str(t), rows(25, "2026-10-30"), partition_by=["business_date"], mode="overwrite",
                    predicate="business_date = '2026-10-30'")
    out = {}
    for f in (tmp_path / "delta").rglob("*"):
        if f.is_file():
            out[f.relative_to(tmp_path).as_posix()] = f.read_bytes()
    out["delta/"] = b""
    return out


@pytest.mark.parametrize("checkpoint", [False, True], ids=["commits", "checkpoint"])
def test_check_finds_the_table_and_reads_it_end_to_end(tmp_path, capsys, checkpoint):
    sts, s1 = sts_stub()
    iam = boto("iam")
    s2 = Stubber(iam)
    s2.add_response("get_policy", {"Policy": {"PolicyName": "p", "Arn": POLICY}}, {"PolicyArn": POLICY})
    s3 = FakeS3(lake(tmp_path, checkpoint))
    with s1, s2:
        rc = D.main(["check", "--bucket", BUCKET, "--prefix", "/delta/", "--region", REGION], clients={"sts": sts, "iam": iam, "s3": s3})
    assert rc == 0
    out = capsys.readouterr()
    report = json.loads(out.out)
    assert report["table"] == "risk/pnl-vector" and report["root"] == f"s3://{BUCKET}/delta"
    read = report["read"]
    assert read["files"] == 2                                          # the overwritten day's first file was removed
    assert read["rows"] in (25, 30) and read["stats-records"] == read["rows"]
    assert read["checkpoint"] == (1 if checkpoint else None) and read["version"] == 2
    assert s3.ranged and all(k.endswith(".parquet") for k in s3.ranged)
    assert SECRET not in out.out + out.err


def test_check_says_when_the_lake_holds_no_table_yet(capsys):
    sts, s1 = sts_stub()
    with s1:
        rc = D.main(["check", "--bucket", BUCKET, "--prefix", "delta", "--region", REGION, "--skip-policy"],
                    clients={"sts": sts, "s3": FakeS3({"delta/": b""})})
    assert rc == 0 and "no table yet" in capsys.readouterr().err


def test_create_makes_a_private_encrypted_bucket_the_prefix_and_the_policy(capsys):
    sts, s1 = sts_stub()
    s3, iam = boto("s3"), boto("iam")
    s2, s3s = Stubber(iam), Stubber(s3)
    s3s.add_client_error("head_bucket", service_error_code="404", http_status_code=404, expected_params={"Bucket": BUCKET})
    s3s.add_response("create_bucket", {}, {"Bucket": BUCKET, "ObjectOwnership": "BucketOwnerEnforced",
                                           "CreateBucketConfiguration": {"LocationConstraint": REGION}})
    s3s.add_response("put_public_access_block", {}, {"Bucket": BUCKET, "PublicAccessBlockConfiguration": {
        "BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True}})
    s3s.add_response("put_bucket_encryption", {}, {"Bucket": BUCKET, "ServerSideEncryptionConfiguration": {
        "Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]}})
    s3s.add_response("put_object", {}, {"Bucket": BUCKET, "Key": "lake/delta/", "Body": b""})
    s2.add_client_error("get_policy", service_error_code="NoSuchEntity", http_status_code=404, expected_params={"PolicyArn": POLICY})
    s2.add_response("create_policy", {"Policy": {"PolicyName": "p", "Arn": POLICY}},
                    {"PolicyName": f"drishti-delta-s3-{BUCKET}", "PolicyDocument": ANY, "Description": ANY})
    with s1, s2, s3s:
        rc = D.main(["create", "--bucket", BUCKET, "--prefix", "lake/delta", "--region", REGION], clients={"sts": sts, "iam": iam, "s3": s3})
        s2.assert_no_pending_responses()
        s3s.assert_no_pending_responses()
    assert rc == 0
    out = capsys.readouterr()
    assert json.loads(out.out)["root"] == f"s3://{BUCKET}/lake/delta"
    assert f"root: s3://{BUCKET}/lake/delta" in out.err and f"s3.region: {REGION}" in out.err


def test_the_policy_is_held_to_the_prefix_and_read_only_drops_writes(capsys):
    doc = D.policy_document(REGION, BUCKET, "delta")
    assert doc["Statement"][0]["Condition"] == {"StringLike": {"s3:prefix": ["delta", "delta/*"]}}
    assert {s["Resource"] for s in doc["Statement"][1:]} == {f"arn:aws:s3:::{BUCKET}/delta/*"}
    assert "s3:DeleteObject" in doc["Statement"][2]["Action"]                     # the loader's compaction and vacuum
    ro = D.policy_document(REGION, BUCKET, "delta", read_only=True, kms_key="arn:aws:kms:eu-west-2:1:key/k")
    assert [s["Sid"] for s in ro["Statement"]] == ["DrishtiDeltaList", "DrishtiDeltaRead", "DrishtiDeltaKms"]
    assert ro["Statement"][2]["Action"] == ["kms:Decrypt"]
    assert D.main(["policy", "--bucket", BUCKET, "--prefix", "delta", "--region", "cn-north-1"]) == 0
    assert "arn:aws-cn:s3:::" in capsys.readouterr().out


def test_remove_keeps_the_lake_unless_purged_and_needs_yes(capsys):
    assert A.run(lambda argv: D.main(["remove", "--bucket", BUCKET, "--prefix", "delta", "--region", REGION], clients={"sts": object()})) == 1
    assert "add --yes" in capsys.readouterr().err
    sts, s1 = sts_stub()
    iam = boto("iam")
    s2 = Stubber(iam)
    s2.add_client_error("get_policy", service_error_code="NoSuchEntity", http_status_code=404, expected_params={"PolicyArn": POLICY})
    fake = FakeS3({"delta/risk/pnl-vector/_delta_log/00000000000000000000.json": b"{}", "delta/risk/pnl-vector/x.parquet": b"x"})
    with s1, s2:
        rc = D.main(["remove", "--bucket", BUCKET, "--prefix", "delta", "--region", REGION, "--yes"], clients={"sts": sts, "iam": iam, "s3": fake})
    assert rc == 0
    err = capsys.readouterr().err
    assert "holds 2 objects (1 tables): kept" in err and "policy drishti-delta-s3-acme-drishti-lake: not there" in err


def test_a_dry_run_without_credentials_prints_the_plan(capsys):
    class NoCreds:
        def get_caller_identity(self):
            raise RuntimeError("no credentials")

    class NoS3:
        def head_bucket(self, Bucket):
            raise RuntimeError("no credentials")

    iam = boto("iam")
    s2 = Stubber(iam)
    s2.add_client_error("get_policy", service_error_code="AccessDenied", http_status_code=403, expected_params={"PolicyArn": ANY})
    with s2:
        rc = D.main(["create", "--bucket", BUCKET, "--prefix", "delta", "--region", REGION, "--dry-run"],
                    clients={"sts": NoCreds(), "s3": NoS3(), "iam": iam})
    assert rc == 0
    err = capsys.readouterr().err
    assert "would call" in err and "create_bucket" in err and "create_policy" in err


def test_bad_names_are_usage_errors():
    assert D.main(["check", "--bucket", "Bad_Bucket", "--prefix", "delta"]) == 2
    assert D.main(["check", "--bucket", BUCKET, "--prefix", "/"]) == 2


@pytest.mark.skipif(not os.environ.get("DRISHTI_DELTA_S3_LIVE"), reason="a live AWS run: set DRISHTI_DELTA_S3_LIVE=1, AWS credentials, "
                    "AWS_REGION, DRISHTI_DELTA_S3_BUCKET and DRISHTI_DELTA_S3_PREFIX")
def test_live_check_against_aws():
    assert D.main(["check", "--skip-policy"]) == 0

