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
  Creates, checks and removes the S3 Tables connector's AWS resources: the table bucket, a namespace, the IAM policy (the
  PowerShell form of deploy/aws/s3-tables/s3_tables.py).

.DESCRIPTION
  Runs deploy\aws\s3-tables\s3_tables.py with the console's Python environment (drishti-console\.venv) when it exists, else
  DRISHTI_PYTHON or python. Python with boto3 (and pyarrow for check) only: no AWS CLI, no other program. Region and profile from
  --region and --profile, else AWS_REGION and AWS_PROFILE; credentials from the AWS default chain, never printed. Every argument is
  passed on: create | check | remove | policy, --env FILE, --dry-run, --yes; --help for the rest. Guide: deploy\aws\s3-tables\README.md.

.EXAMPLE
  .\deploy\aws\s3-tables\s3-tables.ps1 create --bucket drishti-risk --namespace risk --role drishti-server --dry-run
.EXAMPLE
  .\deploy\aws\s3-tables\s3-tables.ps1 check --bucket drishti-risk --namespace risk --table pnl-vector
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$python = Join-Path $root 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = if ($env:DRISHTI_PYTHON) { $env:DRISHTI_PYTHON } else { 'python' } }
& $python (Join-Path $PSScriptRoot 's3_tables.py') @args
exit $LASTEXITCODE
