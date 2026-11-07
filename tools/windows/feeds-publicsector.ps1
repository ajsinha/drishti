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
  Runs the public-sector feeds (World Bank WDI, FRED and the Bank of Canada, Statistics Canada, OECD, official press releases,
  GDELT) (the PowerShell form of tools/feeds/publicsector/run.py).

.DESCRIPTION
  Runs tools\feeds\publicsector\run.py with the console's Python environment (drishti-console\.venv, made by
  tools\windows\start-console.ps1) when it exists, else DRISHTI_PYTHON or python. Python only: no other program is needed.
  Every argument is passed on unchanged: list, or run FEED ... | --all with --inbox, --load, --dry-run, --full, --as-of.
  Exit codes: 0 written or nothing new, 2 a pull failed, 3 a key is missing (FRED: DRISHTI_FRED_API_KEY).
  For Task Scheduler, run it daily with "run --all --inbox". Guide: docs\guides\PUBLIC_SECTOR_GUIDE.md.

.EXAMPLE
  .\tools\windows\feeds-publicsector.ps1 list
.EXAMPLE
  .\tools\windows\feeds-publicsector.ps1 run --all --inbox
.EXAMPLE
  .\tools\windows\feeds-publicsector.ps1 run oecd --dry-run
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = Join-Path $root 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = if ($env:DRISHTI_PYTHON) { $env:DRISHTI_PYTHON } else { 'python' } }
Push-Location $root
try {
    & $python (Join-Path $root 'tools\feeds\publicsector\run.py') @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
