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

"""s3_tables.py without an AWS account: the S3 Tables, IAM and STS calls answered by botocore's Stubber (each call and its parameters
checked, in order), the table's files by an in-memory S3 (ranged GETs), the Iceberg REST endpoint by a fake that checks the SigV4
signature, and the signatures against the ones recorded for drishti-iceberg's Java signer (one fixture for both languages).

    python -m pytest -q -p no:cacheprovider deploy/aws/s3-tables
"""
from __future__ import annotations

import io
import json
import sys
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import boto3
import pytest
from botocore.credentials import Credentials
from botocore.stub import ANY, Stubber

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "lib"))
import drishti_aws as A  # noqa: E402
import mini_avro  # noqa: E402
import s3_tables as T  # noqa: E402

REGION, ACCOUNT, BUCKET = "eu-west-2", "111122223333", "drishti-risk"
ARN = f"arn:aws:s3tables:{REGION}:{ACCOUNT}:bucket/{BUCKET}"
POLICY = f"arn:aws:iam::{ACCOUNT}:policy/drishti-s3tables-{BUCKET}"
SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY-NEVER-PRINTED"


def boto(service):
    return boto3.Session(region_name=REGION, aws_access_key_id="AKIDEXAMPLE", aws_secret_access_key=SECRET).client(service)


def not_found(stub, op, code, params):
    stub.add_client_error(op, service_error_code=code, http_status_code=404, expected_params=params)


class FakeS3:
    """Objects in memory; get_object honours Range as S3 does."""

    def __init__(self, objects):
        self.objects = objects
        self.calls = []

    def head_object(self, Bucket, Key):
        return {"ContentLength": len(self.objects[(Bucket, Key)])}

    def get_object(self, Bucket, Key, Range=None):
        self.calls.append((Bucket, Key, Range))
        data = self.objects[(Bucket, Key)]
        if Range:
            a, b = Range.removeprefix("bytes=").split("-")
            data = data[int(a):int(b) + 1]
        return {"Body": io.BytesIO(data)}


def parquet_bytes(rows: int) -> bytes:
    import pyarrow as pa
    import pyarrow.parquet as pq
    buf = io.BytesIO()
    pq.write_table(pa.table({"id": [f"P-{i}" for i in range(rows)], "doc": ["{}"] * rows}), buf, row_group_size=50)
    return buf.getvalue()


MANIFEST_LIST = {"type": "record", "name": "manifest_file", "fields": [
    {"name": "manifest_path", "type": "string"}, {"name": "manifest_length", "type": "long"},
    {"name": "partition_spec_id", "type": "int"}, {"name": "content", "type": "int"}, {"name": "added_snapshot_id", "type": "long"}]}
MANIFEST = {"type": "record", "name": "manifest_entry", "fields": [
    {"name": "status", "type": "int"}, {"name": "snapshot_id", "type": ["null", "long"]},
    {"name": "data_file", "type": {"type": "record", "name": "r2", "fields": [
        {"name": "content", "type": "int"}, {"name": "file_path", "type": "string"}, {"name": "file_format", "type": "string"},
        {"name": "record_count", "type": "long"}]}}]}


def table_files(rows=120):
    """A one-snapshot Iceberg table as S3 Tables stores it (under the bucket's alias), a removed file before the live one."""
    b = "0123abcd--table-s3"
    meta = {"format-version": 2, "current-snapshot-id": 7, "snapshots": [
        {"snapshot-id": 7, "manifest-list": f"s3://{b}/metadata/snap-7.avro", "summary": {"total-records": str(rows)}}]}
    mlist = mini_avro.write(MANIFEST_LIST, [{"manifest_path": f"s3://{b}/metadata/m0.avro", "manifest_length": 1,
                                             "partition_spec_id": 0, "content": 0, "added_snapshot_id": 7}])
    manifest = mini_avro.write(MANIFEST, [
        {"status": 2, "snapshot_id": 6, "data_file": {"content": 0, "file_path": f"s3://{b}/data/old.parquet", "file_format": "PARQUET", "record_count": 3}},
        {"status": 1, "snapshot_id": 7, "data_file": {"content": 0, "file_path": f"s3://{b}/data/f1.parquet", "file_format": "PARQUET", "record_count": rows}}])
    return f"s3://{b}/metadata/00001.metadata.json", {(b, "metadata/00001.metadata.json"): json.dumps(meta).encode(),
                                                      (b, "metadata/snap-7.avro"): mlist, (b, "metadata/m0.avro"): manifest,
                                                      (b, "data/f1.parquet"): parquet_bytes(rows)}


def sts_stub():
    sts = boto("sts")
    st = Stubber(sts)
    st.add_response("get_caller_identity", {"Arn": f"arn:aws:sts::{ACCOUNT}:assumed-role/ops/me", "Account": ACCOUNT, "UserId": "x"}, {})
    return sts, st


def test_the_policy_document_reads_and_writes_only_the_one_bucket(capsys):
    doc = T.policy_document(ARN)
    assert [s["Sid"] for s in doc["Statement"]] == ["DrishtiS3TablesRead", "DrishtiS3TablesWrite"]
    assert all(r in (ARN, f"{ARN}/table/*") for s in doc["Statement"] for r in s["Resource"])
    assert "s3tables:GetTableData" in doc["Statement"][0]["Action"] and "s3tables:PutTableData" in doc["Statement"][1]["Action"]
    assert len(T.policy_document(ARN, read_only=True)["Statement"]) == 1
    assert T.main(["policy", "--bucket", BUCKET, "--region", REGION, "--read-only"]) == 0
    printed = json.loads(capsys.readouterr().out)
    assert printed["Statement"][0]["Resource"][0] == f"arn:aws:s3tables:{REGION}:<account>:bucket/{BUCKET}"


def test_create_makes_the_bucket_the_namespace_and_the_policy_and_attaches_it(capsys):
    sts, s1 = sts_stub()
    s3t, iam = boto("s3tables"), boto("iam")
    s2, s3 = Stubber(s3t), Stubber(iam)
    not_found(s2, "get_table_bucket", "NotFoundException", {"tableBucketARN": ARN})
    s2.add_response("create_table_bucket", {"arn": ARN}, {"name": BUCKET})
    not_found(s2, "get_namespace", "NotFoundException", {"tableBucketARN": ARN, "namespace": "risk_results"})
    s2.add_response("create_namespace", {"tableBucketARN": ARN, "namespace": ["risk_results"]},
                    {"tableBucketARN": ARN, "namespace": ["risk_results"]})
    not_found(s3, "get_policy", "NoSuchEntity", {"PolicyArn": POLICY})
    s3.add_response("create_policy", {"Policy": {"PolicyName": "p", "Arn": POLICY}},
                    {"PolicyName": f"drishti-s3tables-{BUCKET}", "PolicyDocument": ANY, "Description": ANY})
    s3.add_response("attach_role_policy", {}, {"RoleName": "drishti-server", "PolicyArn": POLICY})
    with s1, s2, s3:
        rc = T.main(["create", "--bucket", BUCKET, "--namespace", "risk-results", "--region", REGION, "--role", "drishti-server"],
                    clients={"sts": sts, "s3tables": s3t, "iam": iam})
        s2.assert_no_pending_responses()
        s3.assert_no_pending_responses()
    assert rc == 0
    out = capsys.readouterr()
    assert json.loads(out.out) == {"table-bucket-arn": ARN, "namespace": "risk_results",
                                   "endpoint": f"https://s3tables.{REGION}.amazonaws.com/iceberg", "policy": f"drishti-s3tables-{BUCKET}"}
    assert "catalog: s3tables" in out.err and f"table-bucket-arn: {ARN}" in out.err


def test_create_keeps_what_exists_and_a_dry_run_changes_nothing(capsys):
    sts, s1 = sts_stub()
    s3t, iam = boto("s3tables"), boto("iam")
    s2, s3 = Stubber(s3t), Stubber(iam)
    s2.add_response("get_table_bucket", {"arn": ARN, "name": BUCKET, "ownerAccountId": ACCOUNT, "createdAt": datetime(2026, 1, 1)},
                    {"tableBucketARN": ARN})
    s2.add_response("get_namespace", {"namespace": ["risk"], "createdAt": datetime(2026, 1, 1), "createdBy": ACCOUNT, "ownerAccountId": ACCOUNT},
                    {"tableBucketARN": ARN, "namespace": "risk"})
    s3.add_response("get_policy", {"Policy": {"PolicyName": "p", "Arn": POLICY}}, {"PolicyArn": POLICY})
    s3.add_response("list_policy_versions", {"Versions": [{"VersionId": "v1", "IsDefaultVersion": True, "CreateDate": datetime(2026, 1, 1)}]},
                    {"PolicyArn": POLICY})
    with s1, s2, s3:                                   # no create_* response is stubbed: a call would fail the test
        rc = T.main(["create", "--bucket", BUCKET, "--namespace", "risk", "--region", REGION, "--dry-run"],
                    clients={"sts": sts, "s3tables": s3t, "iam": iam})
    assert rc == 0
    err = capsys.readouterr().err
    assert "table bucket drishti-risk exists: kept" in err and "namespace risk exists: kept" in err
    assert "would call iam.create_policy_version" in err and "+ " not in err.replace("+ aws", "")


class FakeRest:
    """S3 Tables' Iceberg REST endpoint: refuses what is not signed for s3tables in the region; answers config and loadTable."""

    def __init__(self, metadata_location):
        self.loc = metadata_location
        self.urls = []

    def __call__(self, method, url, headers, body=None):
        self.urls.append(url)
        auth = headers.get("Authorization", "")
        if not auth.startswith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/") or f"/{REGION}/s3tables/aws4_request" not in auth:
            return 403, {"error": {"message": "unsigned"}}
        enc = urllib.parse.quote(ARN, safe="")
        base = f"https://s3tables.{REGION}.amazonaws.com/iceberg/v1"
        if url == f"{base}/config?warehouse={enc}":
            return 200, {"defaults": {}, "overrides": {"prefix": enc}}
        if url == f"{base}/{enc}/namespaces/risk/tables/pnl_vector":
            return 200, {"metadata-location": self.loc, "metadata": {}}
        return 404, {"error": {"message": url}}


def check_stubs(loc):
    sts, s1 = sts_stub()
    s3t, iam = boto("s3tables"), boto("iam")
    s2, s3 = Stubber(s3t), Stubber(iam)
    s2.add_response("get_table_bucket", {"arn": ARN, "name": BUCKET, "ownerAccountId": ACCOUNT, "createdAt": datetime(2026, 1, 1)},
                    {"tableBucketARN": ARN})
    s2.add_response("get_namespace", {"namespace": ["risk"], "createdAt": datetime(2026, 1, 1), "createdBy": ACCOUNT, "ownerAccountId": ACCOUNT},
                    {"tableBucketARN": ARN, "namespace": "risk"})
    s2.add_response("list_tables", {"tables": [{"namespace": ["risk"], "name": "pnl_vector", "type": "customer",
                                                "tableARN": f"{ARN}/table/x", "createdAt": datetime(2026, 1, 1),
                                                "modifiedAt": datetime(2026, 1, 1)}]},
                    {"tableBucketARN": ARN, "namespace": "risk"})
    s3.add_response("get_policy", {"Policy": {"PolicyName": "p", "Arn": POLICY}}, {"PolicyArn": POLICY})
    s2.add_response("get_table_metadata_location", {"versionToken": "v", "metadataLocation": loc, "warehouseLocation": "s3://w"},
                    {"tableBucketARN": ARN, "namespace": "risk", "name": "pnl_vector"})
    return (s1, s2, s3), {"sts": sts, "s3tables": s3t, "iam": iam}


def test_check_reads_one_table_end_to_end_and_prints_no_secret(capsys):
    loc, objects = table_files(120)
    stubs, clients = check_stubs(loc)
    clients["s3"] = FakeS3(objects)
    rest = FakeRest(loc)
    sess = SimpleNamespace(region_name=REGION, get_credentials=lambda: Credentials("AKIDEXAMPLE", SECRET))
    with stubs[0], stubs[1], stubs[2]:
        rc = T.main(["check", "--bucket", BUCKET, "--namespace", "risk", "--table", "pnl-vector", "--region", REGION],
                    sess=sess, clients=clients, http=rest)
    assert rc == 0
    out = capsys.readouterr()
    report = json.loads(out.out)
    assert report["table"] == "pnl_vector"
    assert report["read"]["rows"] == 120 and report["read"]["manifest-record-count"] == 120 and report["read"]["first_row_group_rows"] == 50
    assert report["read"]["file"].endswith("/data/f1.parquet")                  # the removed file (status 2) is skipped
    assert all(r for (_, k, r) in clients["s3"].calls if k.endswith(".parquet"))  # the data file is read by ranges only
    assert len(rest.urls) == 2
    assert SECRET not in out.out + out.err


def test_check_fails_naming_the_step_when_the_rest_endpoint_refuses(capsys):
    loc, objects = table_files()
    stubs, clients = check_stubs(loc)
    clients["s3"] = FakeS3(objects)
    sess = SimpleNamespace(region_name=REGION, get_credentials=lambda: Credentials("SOMEONEELSE", SECRET))
    with stubs[0], stubs[1], stubs[2]:
        rc = A.run(lambda argv: T.main(["check", "--bucket", BUCKET, "--namespace", "risk", "--table", "pnl-vector", "--region", REGION],
                                       sess=sess, clients=clients, http=FakeRest(loc)))
    assert rc == 1
    err = capsys.readouterr().err
    assert "/v1/config" in err and "HTTP 403" in err


def test_remove_needs_yes_and_never_deletes_tables_unasked(capsys):
    assert A.run(lambda argv: T.main(["remove", "--bucket", BUCKET, "--region", REGION], clients={"sts": object()})) == 1
    assert "add --yes" in capsys.readouterr().err
    sts, s1 = sts_stub()
    s3t = boto("s3tables")
    s2 = Stubber(s3t)
    s2.add_response("get_namespace", {"namespace": ["risk"], "createdAt": datetime(2026, 1, 1), "createdBy": ACCOUNT, "ownerAccountId": ACCOUNT},
                    {"tableBucketARN": ARN, "namespace": "risk"})
    s2.add_response("list_tables", {"tables": [{"namespace": ["risk"], "name": "pnl_vector", "type": "customer",
                                                "tableARN": f"{ARN}/table/x", "createdAt": datetime(2026, 1, 1),
                                                "modifiedAt": datetime(2026, 1, 1)}]}, {"tableBucketARN": ARN, "namespace": "risk"})
    with s1, s2:
        rc = A.run(lambda argv: T.main(["remove", "--bucket", BUCKET, "--namespace", "risk", "--region", REGION, "--yes"],
                                       clients={"sts": sts, "s3tables": s3t, "iam": boto("iam")}))
    assert rc == 1 and "add --delete-tables" in capsys.readouterr().err


def test_remove_deletes_the_tables_the_namespace_the_policy_and_the_bucket_when_asked():
    sts, s1 = sts_stub()
    s3t, iam = boto("s3tables"), boto("iam")
    s2, s3 = Stubber(s3t), Stubber(iam)
    s2.add_response("get_namespace", {"namespace": ["risk"], "createdAt": datetime(2026, 1, 1), "createdBy": ACCOUNT, "ownerAccountId": ACCOUNT},
                    {"tableBucketARN": ARN, "namespace": "risk"})
    s2.add_response("list_tables", {"tables": [{"namespace": ["risk"], "name": "pnl_vector", "type": "customer",
                                                "tableARN": f"{ARN}/table/x", "createdAt": datetime(2026, 1, 1),
                                                "modifiedAt": datetime(2026, 1, 1)}]}, {"tableBucketARN": ARN, "namespace": "risk"})
    s2.add_response("delete_table", {}, {"tableBucketARN": ARN, "namespace": "risk", "name": "pnl_vector"})
    s2.add_response("delete_namespace", {}, {"tableBucketARN": ARN, "namespace": "risk"})
    s3.add_response("get_policy", {"Policy": {"PolicyName": "p", "Arn": POLICY}}, {"PolicyArn": POLICY})
    s3.add_response("list_entities_for_policy", {"PolicyRoles": [{"RoleName": "drishti-server"}], "PolicyUsers": [], "PolicyGroups": []},
                    {"PolicyArn": POLICY})
    s3.add_response("detach_role_policy", {}, {"RoleName": "drishti-server", "PolicyArn": POLICY})
    s3.add_response("list_policy_versions", {"Versions": [
        {"VersionId": "v2", "IsDefaultVersion": True, "CreateDate": datetime(2026, 1, 2)},
        {"VersionId": "v1", "IsDefaultVersion": False, "CreateDate": datetime(2026, 1, 1)}]}, {"PolicyArn": POLICY})
    s3.add_response("delete_policy_version", {}, {"PolicyArn": POLICY, "VersionId": "v1"})
    s3.add_response("delete_policy", {}, {"PolicyArn": POLICY})
    s2.add_response("get_table_bucket", {"arn": ARN, "name": BUCKET, "ownerAccountId": ACCOUNT, "createdAt": datetime(2026, 1, 1)},
                    {"tableBucketARN": ARN})
    s2.add_response("list_namespaces", {"namespaces": []}, {"tableBucketARN": ARN})
    s2.add_response("delete_table_bucket", {}, {"tableBucketARN": ARN})
    with s1, s2, s3:
        rc = T.main(["remove", "--bucket", BUCKET, "--namespace", "risk", "--region", REGION, "--yes", "--delete-tables", "--delete-bucket"],
                    clients={"sts": sts, "s3tables": s3t, "iam": iam})
        s2.assert_no_pending_responses()
        s3.assert_no_pending_responses()
    assert rc == 0


def test_the_signatures_are_the_ones_drishti_iceberg_is_held_to():
    rec = json.loads((Path(__file__).with_name("sigv4-recorded.json")).read_text())
    creds = Credentials(rec["access-key-id"], rec["secret-access-key"])
    when = datetime.fromisoformat(rec["time"].replace("Z", "+00:00")).astimezone(timezone.utc)
    for r in rec["requests"]:
        h = A.sign(creds, r["method"], r["url"], rec["region"], rec["service"], r["body"].encode() if r["body"] else None, when)
        assert h["Authorization"] == r["authorization"], r["url"]
        assert h["X-Amz-Content-SHA256"] == r["x-amz-content-sha256"]


def test_names_and_endpoints_follow_the_connector():
    assert T.s3_name("pnl-vector") == "pnl_vector" and T.s3_name("Risk.Desk") == "risk_desk"
    assert T.endpoint("cn-north-1") == "https://s3tables.cn-north-1.amazonaws.com.cn/iceberg"
    assert T.bucket_arn("us-gov-west-1", ACCOUNT, "x1y") == f"arn:aws-us-gov:s3tables:us-gov-west-1:{ACCOUNT}:bucket/x1y"


def test_a_settings_file_fills_what_the_environment_does_not(tmp_path, monkeypatch):
    monkeypatch.delenv("DRISHTI_S3TABLES_BUCKET", raising=False)
    monkeypatch.setenv("AWS_REGION", "eu-west-1")
    f = tmp_path / "env"
    f.write_text('AWS_REGION="<eu-west-2>"\nORG=acme\nDRISHTI_S3TABLES_BUCKET=${ORG}-risk   # the bucket\n')
    A.load_env_file(str(f))
    import os
    assert os.environ["DRISHTI_S3TABLES_BUCKET"] == "acme-risk" and os.environ["AWS_REGION"] == "eu-west-1"
    monkeypatch.delenv("DRISHTI_S3TABLES_BUCKET")
    monkeypatch.delenv("ORG")


@pytest.mark.skipif(not __import__("os").environ.get("DRISHTI_S3TABLES_LIVE"), reason="a live AWS run: set DRISHTI_S3TABLES_LIVE=1, "
                    "AWS credentials, AWS_REGION and DRISHTI_S3TABLES_BUCKET")
def test_live_check_against_aws():
    assert T.main(["check", "--namespace", __import__("os").environ.get("DRISHTI_S3TABLES_NAMESPACE", "risk"), "--skip-policy"]) == 0
