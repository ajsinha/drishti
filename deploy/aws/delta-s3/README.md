<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# deploy/aws/delta-s3: the Delta Lake on S3 connector's AWS resources

The AWS side of the **Delta Lake on S3 connector** (the `delta` plugin with `root: s3://<bucket>/<prefix>`,
[DELTA_CONNECTOR.md section 17](../../../docs/connectors/DELTA_CONNECTOR.md#17-delta-lake-on-aws-s3)): a bucket, the lake's prefix in
it, and the IAM policy the server (read) and the loaders (read, write and delete) need. Python with `boto3`, so it runs the same on
Linux, macOS and Windows with no AWS CLI; the `.sh` and `.ps1` files only start the Python with the console's environment
(`drishti-console/.venv`), else `DRISHTI_PYTHON` or `python3`.

**Why a folder beside `deploy/aws/delta-lake/`.** `delta-lake/` is the production lake's pipeline in bash over the AWS CLI, driven by
`deploy/aws/env`: buckets with KMS, a VPC endpoint, access logs and tiers, the nightly load, maintenance and alarms. This folder is the
connector's own small set, in Python like `deploy/aws/s3-tables/`, its twin: one bucket and prefix, its policy, and a check that reads
a table. They share the variables (`--env deploy/aws/env` gives `LAKE_BUCKET` and `LAKE_PREFIX` as defaults) and never overlap: on a
bucket made by `delta-lake/setup-buckets.sh`, run only `check` here.

| File | What it does |
|---|---|
| `delta_s3.py` | the commands below |
| `delta-s3.sh`, `delta-s3.ps1` | run `delta_s3.py` with every argument passed on |
| `test_delta_s3.py` | its tests, with no AWS account (botocore's Stubber; an in-memory S3 holding real Delta tables) |

## The commands

| Command | What it does |
|---|---|
| `create --bucket B --prefix P [--role R] [--kms-key K] [--read-only] [--policy-name N]` | creates the bucket if missing (owner-enforced objects, Block Public Access, default encryption: SSE-S3, or SSE-KMS with `--kms-key`), the prefix's marker, and the managed policy `drishti-delta-s3-<bucket>`, attached to the role; what exists is kept (a policy gets a new default version). Prints the root and the connector settings to paste |
| `check --bucket B --prefix P [--table D/T] [--skip-policy]` | the bucket and the policy; the Delta tables under the prefix (`<domain>/<table>` with a `_delta_log/`); then one table read end to end the way Drishti reads it: the last checkpoint and the commits after it replayed to the live files, and the first one's Parquet footer and first row group (ranged GETs); its row count must equal the log's statistics |
| `remove --bucket B --prefix P --yes [--purge] [--delete-bucket]` | detaches and deletes the policy; the objects under the prefix only with `--purge`; the bucket only with `--delete-bucket`, and only when nothing else is in it |
| `policy --bucket B --prefix P [--read-only] [--kms-key K]` | prints the policy document, with no AWS call |

Every command takes `--region` and `--profile` (else `AWS_REGION` and `AWS_PROFILE`), `--env FILE` and `--dry-run` (prints each call
that would change something, makes none). `--bucket` defaults to `DRISHTI_DELTA_S3_BUCKET`, else `LAKE_BUCKET`; `--prefix` to
`DRISHTI_DELTA_S3_PREFIX`, else `LAKE_PREFIX`, else `delta`. Exit codes: 0 done or the check passed, 1 a step failed (it is named), 2 usage.

**Secrets.** Credentials come from the AWS default chain and stay in the session: nothing prints them, a printed call masks every key
that looks secret, and the only identity shown is the caller's ARN.

## The policy

| Statement | Actions | Resources |
|---|---|---|
| list | `s3:ListBucket`, `s3:GetBucketLocation` | the bucket, with `s3:prefix` limited to the lake's prefix |
| read | `s3:GetObject` | `<bucket>/<prefix>/*` |
| write (loader; left out with `--read-only`) | `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` | the same (commits, compaction and vacuum) |
| KMS (with `--kms-key`) | `kms:Decrypt`, and `kms:GenerateDataKey` for a writer | the key |

## Examples

```bash
deploy/aws/delta-s3/delta-s3.sh create --bucket acme-drishti-lake --prefix delta --role drishti-server --dry-run
deploy/aws/delta-s3/delta-s3.sh create --bucket acme-drishti-lake --prefix delta --role drishti-server
deploy/aws/delta-s3/delta-s3.sh check --bucket acme-drishti-lake --prefix delta --table risk/pnl-vector
deploy/aws/delta-s3/delta-s3.sh check --env deploy/aws/env --skip-policy        # the production lake's bucket and prefix
deploy/aws/delta-s3/delta-s3.sh remove --bucket acme-drishti-lake --prefix delta --yes
```

```powershell
.\deploy\aws\delta-s3\delta-s3.ps1 check --bucket acme-drishti-lake --prefix delta --profile drishti --region eu-west-2
```

## Status

The tests run with no AWS account: `python -m pytest -q -p no:cacheprovider deploy/aws/delta-s3` (the S3, IAM and STS calls on
botocore's Stubber; `check` on an in-memory S3 holding Delta tables written by the deltalake package, with commits only and with a
checkpoint). The connector itself is tested on S3Mock (`DeltaOnAwsS3Test`: the shipped template, credentials from the default chain).
**Not run against AWS**: no AWS account is available to the build, so `create`, `check` and `remove` have not been run live; that run
waits for an AWS sandbox account. With one, `DRISHTI_DELTA_S3_LIVE=1 python -m pytest deploy/aws/delta-s3` runs `check` against it.
