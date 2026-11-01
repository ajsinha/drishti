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

# Creates, checks and removes the S3 Tables connector's AWS resources (table bucket, namespace, IAM policy): runs s3_tables.py beside it with the console's Python
# environment (drishti-console/.venv) when it exists, else DRISHTI_PYTHON or python3. Needs boto3 (and pyarrow for check); no AWS CLI.
# Every argument is passed on: create | check | remove | policy, --region, --profile, --env FILE, --dry-run, --yes; --help for the rest.
# Guide: deploy/aws/s3-tables/README.md.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
python="$root/drishti-console/.venv/bin/python"
[[ -x "$python" ]] || python="${DRISHTI_PYTHON:-python3}"
exec "$python" "$here/s3_tables.py" "$@"
