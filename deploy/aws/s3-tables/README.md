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
# deploy/aws/s3-tables: the S3 Tables connector's AWS resources

The AWS side of the **S3 Tables connector** (the `iceberg` plugin with `catalog: s3tables`,
[ICEBERG_CONNECTOR.md section 20](../../../plugins/drishti-plugin-iceberg/README.md#aws-s3-tables)): a table bucket, a namespace for a
Drishti domain, and the IAM policy the server (read) and the loaders (read and write) need. Python with `boto3`, so it runs the same
on Linux, macOS and Windows with no AWS CLI; the `.sh` and `.ps1` files only start the Python with the console's environment
(`drishti-console/.venv`), else `DRISHTI_PYTHON` or `python3`.

| File | What it does |
|---|---|
| `s3_tables.py` | the commands below |
| `s3-tables.sh`, `s3-tables.ps1` | run `s3_tables.py` with every argument passed on |
| `test_s3_tables.py` | its tests, with no AWS account (botocore's Stubber, an in-memory S3, a fake Iceberg REST endpoint) |

## The commands

| Command | What it does |
|---|---|
| `create --bucket B --namespace NS [--role R] [--read-only] [--policy-name P]` | creates the table bucket, the namespace (the domain as an S3 Tables name: `risk-results` is `risk_results`) and the managed policy `drishti-s3tables-<bucket>`, and attaches it to the role; what exists is kept (a policy gets a new default version). Prints the ARN and the connector settings to paste |
| `check --bucket B --namespace NS [--table T] [--arn A] [--skip-policy]` | the bucket, the namespace, its tables and the policy; then one table read end to end the way Drishti reads it: `GetTableMetadataLocation`, the Iceberg REST endpoint (`/v1/config`, then `loadTable`, both signed with SigV4 for `s3tables` exactly as Drishti's Java client signs them), the metadata file, the manifest list, a manifest, and the first live data file's Parquet footer and first row group (ranged GETs); its row count must equal the manifest's |
| `remove --bucket B --namespace NS --yes [--delete-tables] [--delete-bucket]` | detaches and deletes the policy and deletes the namespace; a namespace that holds tables is refused unless `--delete-tables`; the bucket only with `--delete-bucket`, and only when no other namespace is in it |
| `policy --bucket B [--read-only]` | prints the policy document, with no AWS call (for a security review) |

Every command takes `--region` and `--profile` (else `AWS_REGION` and `AWS_PROFILE`), `--env FILE` (a `KEY=VALUE` file such as
`deploy/aws/env`; variables already set win) and `--dry-run` (prints each call that would change something, makes none; without
credentials it plans as if nothing existed). `--bucket` defaults to `DRISHTI_S3TABLES_BUCKET`, `--namespace` to
`DRISHTI_S3TABLES_NAMESPACE`, else `risk`. Exit codes: 0 done or the check passed, 1 a step failed (it is named), 2 usage.

**Secrets.** Credentials come from the AWS default chain (a profile, environment variables, an instance or task role) and stay in the
session: nothing prints them, a printed call masks every key that looks secret, and the only identity shown is the caller's ARN.

## The policy

| Statement | Actions | Resources |
|---|---|---|
| read (server and loader) | `s3tables:GetTableBucket`, `ListNamespaces`, `GetNamespace`, `ListTables`, `GetTable`, `GetTableMetadataLocation`, `GetTableData` | the table bucket and `bucket/<name>/table/*` |
| write (loader; left out with `--read-only`) | `s3tables:CreateNamespace`, `CreateTable`, `UpdateTableMetadataLocation`, `PutTableData` | the same |

S3 Tables reaches the table data through `s3tables:GetTableData` and `PutTableData`, so no `s3:*` statement is needed, and it vends no
credentials: the connector sets `vended-credentials: false` and reads with its own principal. S3 Tables compacts tables and expires
snapshots itself, so Drishti's lake maintenance is not run on them.

## Examples

```bash
deploy/aws/s3-tables/s3-tables.sh create --bucket drishti-risk --namespace risk --role drishti-server --dry-run
deploy/aws/s3-tables/s3-tables.sh create --bucket drishti-risk --namespace risk --role drishti-server
deploy/aws/s3-tables/s3-tables.sh check --bucket drishti-risk --namespace risk --table pnl-vector
deploy/aws/s3-tables/s3-tables.sh remove --bucket drishti-risk --namespace risk --delete-tables --delete-bucket --yes
```

```powershell
.\deploy\aws\s3-tables\s3-tables.ps1 check --bucket drishti-risk --namespace risk --profile drishti --region eu-west-2
```

## Status

The tests run with no AWS account: `python -m pytest -q -p no:cacheprovider deploy/aws/s3-tables` (the S3 Tables, IAM and STS calls
on botocore's Stubber, the table's files on an in-memory S3, the Iceberg REST endpoint on a fake that checks the signature, the
signatures against the ones recorded for drishti-iceberg's Java signer). **Not run against AWS**: no AWS account is available to the
build, so `create`, `check` and `remove` have not been run live; that run waits for an AWS sandbox account. With one,
`DRISHTI_S3TABLES_LIVE=1 python -m pytest deploy/aws/s3-tables` runs `check` against it, and
`deploy/aws/iceberg/check-glue.sh --s3-tables <ARN>` runs Drishti's own write, read and drop.
