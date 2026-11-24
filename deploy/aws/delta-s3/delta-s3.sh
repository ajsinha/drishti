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

# Creates, checks and removes the Delta Lake on S3 connector's AWS resources (bucket, prefix, IAM policy): runs delta_s3.py beside it with the Python you run
# Drishti with: DRISHTI_PYTHON, else python3. Needs boto3 (and pyarrow for check); no AWS CLI.
# Every argument is passed on: create | check | remove | policy, --region, --profile, --env FILE, --dry-run, --yes; --help for the rest.
# Guide: docs/guides/CLUSTER_GUIDE.md.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
python="${DRISHTI_PYTHON:-python3}"
exec "$python" "$here/delta_s3.py" "$@"
