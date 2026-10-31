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
  Refreshes the funds pack (SEC N-PORT, N-CEN and risk/return summary data sets, ticker files; GLEIF) (the PowerShell form of tools/publicdata/funds.py).

.DESCRIPTION
  Runs tools\publicdata\funds.py with the console's Python environment (drishti-console\.venv, made by
  tools\windows\start-console.ps1) when it exists, else DRISHTI_PYTHON or python. Python only: no other program is needed.
  Every argument is passed on unchanged: status, or refresh [SOURCE ...] --years N --since YEAR --scope A,B --out DIR
  --into-pack --offline --dry-run. The downloads (about 700 MB) stay in the data folder. Guide: docs\guides\DATA_GUIDE.md.

.EXAMPLE
  .\tools\windows\publicdata-funds.ps1 status
.EXAMPLE
  .\tools\windows\publicdata-funds.ps1 refresh --dry-run
.EXAMPLE
  .\tools\windows\publicdata-funds.ps1 refresh --scope FUNDS:300,HOLDINGS:1000
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = Join-Path $root 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = if ($env:DRISHTI_PYTHON) { $env:DRISHTI_PYTHON } else { 'python' } }
Push-Location $root
try {
    & $python (Join-Path $root 'tools\publicdata\funds.py') @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
