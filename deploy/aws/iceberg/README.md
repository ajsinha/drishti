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
# deploy/aws/iceberg: Iceberg tables in S3 (2.9.5)

The Iceberg twins of `deploy/aws/delta-lake/`, for the tables a deployment chooses to keep in Apache Iceberg. **Delta Lake stays the
default**; a table is here only when `tables.example` (your copy: `ICEBERG_TABLES_FILE`) lists it. The guide, with the variables, the
order to run them in and the IAM: [AWS_DEPLOYMENT.md section 22](../../../docs/architecture/AWS_DEPLOYMENT.md#22-iceberg-on-aws-295).
The connector side: [ICEBERG_CONNECTOR.md section 19](../../../docs/connectors/ICEBERG_CONNECTOR.md#19-aws-glue-s3-tables-and-the-iam-they-need-295).

| File | What it does |
|---|---|
| `load-day.sh` | one business day of every listed table: the landing dumps synced locally, `lake build --format iceberg` stages them and Drishti's Iceberg loader commits (no publish step: an Iceberg commit is atomic), its CloudWatch metrics sent |
| `backfill.sh` | `load-day.sh` for each date of a range or a list, oldest first; each date replaces itself, so a stopped backfill can be run again |
| `maintain.sh` | nightly: retention (never inside the 7-year floor of `risk` and `risk-results`), relayout, snapshot expiry and orphan files, by `drishti.py lake maintain`, one table at a time under its lock; then `setup-lifecycle.sh` |
| `lake-maintenance.s3.yaml` | the maintenance file `maintain.sh` uses (`format: iceberg`, the S3 root or a REST catalog) |
| `setup-lifecycle.sh` | storage tiers by business date for `data/`, beside the Delta rules (kept), never `metadata/`, no expiry |
| `setup-alarms.sh` | the CloudWatch alarms of the Iceberg tables (idempotent) |
| `report.sh` | health and size per table from its current metadata only (read only, apart from the metrics it sends) |
| `setup-iam.sh`, `iam/*.json` | the loader's, the server's and the maintenance's policies (Glue `GetDatabase/GetTable(s)/CreateTable/UpdateTable`, Lake Formation `GetDataAccess`, S3 on the tables' prefix) |
| `check-conditional-writes.sh` | proves the bucket honours conditional PUTs, which a path-based table's commits rely on |
| `check-glue.sh` | proves Drishti's Iceberg engine against the real Glue Iceberg REST catalog (and, with `--s3-tables ARN`, S3 Tables): `GlueLiveTest` creates, reads back bit for bit and drops a table |
| `tables.example` | the tables, one a line |

**Status (2.9.5).** Every script is tested with `--help` and `--dry-run` (`tools/test_deploy_aws.py`). **Not run against AWS**: the
Glue and S3 Tables live runs (`check-glue.sh`) need an AWS account and are the product owner's to run; the format guard of an `s3://`
official root is tested on S3Mock only; shellcheck was not run on this machine.

Every script takes `--help` and `--dry-run`, and reads `deploy/aws/env` (`--env FILE` or `DRISHTI_AWS_ENV`; copy it from `deploy/aws/env.example`).
