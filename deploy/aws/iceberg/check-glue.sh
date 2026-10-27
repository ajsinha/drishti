#!/usr/bin/env bash
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

# Proves that Drishti's own Iceberg engine works against the real AWS Glue Iceberg REST catalog (and, with --s3-tables, S3 Tables) in your
# account: GlueLiveTest of plugins/drishti-plugin-iceberg creates a Glue database and a table of P&L vectors with Drishti's loader, reads
# them back through the connector (documents and vectors(), bit for bit), then drops both. Nothing else in the account is touched. The
# test is skipped everywhere else (it runs only with DRISHTI_GLUE_LIVE=1, which this script sets).
USAGE="check-glue.sh [--s3-tables ARN] [--keep] [--dry-run] [--env FILE]

Needs: AWS credentials in the default chain (a profile, environment variables or an instance or task role) allowed glue:*Database*,
glue:*Table*, s3:GetObject/PutObject/DeleteObject/ListBucket under s3://LAKE_BUCKET/ICEBERG_PREFIX/_drishti-glue-check/ (the policies of
setup-iam.sh plus glue:DeleteTable and glue:DeleteDatabase), Java 21 and this repository (it runs ./mvnw).
--s3-tables ARN  also the same test against S3 Tables' Iceberg REST endpoint (https://s3tables.<region>.amazonaws.com/iceberg, signing
                 name s3tables) on the table bucket ARN (arn:aws:s3tables:<region>:<account>:bucket/<name>); its result is recorded,
                 a failure is reported without failing the Glue check
--keep           keep the database and table afterwards (to look at them in the console or Athena)
Exit: 0 Glue works end to end; 1 it does not (the test's report names the step)."
source "$(dirname "$0")/../lib/common.sh"
drishti_init "$@"
need AWS_REGION LAKE_BUCKET ICEBERG_PREFIX
S3T=""; KEEP=0
set -- "${ARGS[@]+"${ARGS[@]}"}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --s3-tables) S3T="$2"; shift ;;
    --keep) KEEP=1 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done
if ! who=$(aws_ask sts get-caller-identity --query Arn --output text); then
  [[ "$DRY_RUN" == 1 ]] || die "no AWS credentials in the default chain (aws sts get-caller-identity failed): set a profile or keys first"
fi
say "as ${who:-<the default chain>} in $AWS_REGION"
envs=(DRISHTI_GLUE_LIVE=1 "DRISHTI_GLUE_REGION=$AWS_REGION" "DRISHTI_GLUE_CATALOG_ID=${GLUE_CATALOG_ID:-${AWS_ACCOUNT_ID:-}}"
      "DRISHTI_GLUE_WAREHOUSE=s3://$LAKE_BUCKET/$ICEBERG_PREFIX/_drishti-glue-check" "DRISHTI_GLUE_KEEP=$KEEP")
[[ -n "$S3T" ]] && envs+=(DRISHTI_S3TABLES_LIVE=1 "DRISHTI_S3TABLES_ARN=$S3T")
run env "${envs[@]}" "$REPO_DIR/mvnw" -q -f "$REPO_DIR/pom.xml" -pl plugins/drishti-plugin-iceberg -am test -Dtest=GlueLiveTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
report="$REPO_DIR/plugins/drishti-plugin-iceberg/target/glue-live-report.txt"
if [[ "$DRY_RUN" == 0 && -f "$report" ]]; then
  cat "$report"
fi
say "Glue works end to end with Drishti's Iceberg engine"
