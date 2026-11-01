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

<#
.SYNOPSIS
  Creates, checks and removes the Delta Lake on S3 connector's AWS resources: the bucket, the lake's prefix, the IAM policy (the
  PowerShell form of deploy/aws/delta-s3/delta_s3.py).

.DESCRIPTION
  Runs deploy\aws\delta-s3\delta_s3.py with the console's Python environment (drishti-console\.venv) when it exists, else
  DRISHTI_PYTHON or python. Python with boto3 (and pyarrow for check) only: no AWS CLI, no other program. Region and profile from
  --region and --profile, else AWS_REGION and AWS_PROFILE; credentials from the AWS default chain, never printed. Every argument is
  passed on: create | check | remove | policy, --env FILE, --dry-run, --yes; --help for the rest. Guide: deploy\aws\delta-s3\README.md.

.EXAMPLE
  .\deploy\aws\delta-s3\delta-s3.ps1 create --bucket acme-drishti-lake --prefix delta --role drishti-server --dry-run
.EXAMPLE
  .\deploy\aws\delta-s3\delta-s3.ps1 check --bucket acme-drishti-lake --prefix delta --table risk/pnl-vector
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$python = Join-Path $root 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = if ($env:DRISHTI_PYTHON) { $env:DRISHTI_PYTHON } else { 'python' } }
& $python (Join-Path $PSScriptRoot 'delta_s3.py') @args
exit $LASTEXITCODE
