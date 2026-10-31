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
  Refreshes the news-and-markets pack's offline snapshot (Yahoo Finance quotes, Google News headlines) from its public sources (the PowerShell form of tools/publicdata/news_and_markets.py).

.DESCRIPTION
  Runs tools\publicdata\news_and_markets.py with the console's Python environment (drishti-console\.venv, made by
  tools\windows\start-console.ps1) when it exists, else DRISHTI_PYTHON or python. Python only: no other program is needed.
  Every argument is passed on unchanged: status, or refresh [SOURCE ...] --years N --since YEAR --scope A,B --out DIR
  --dry-run --internal-ok. Guide: docs\guides\DATA_GUIDE.md.

.EXAMPLE
  .\tools\windows\publicdata-news-and-markets.ps1 status
.EXAMPLE
  .\tools\windows\publicdata-news-and-markets.ps1 refresh --dry-run
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = Join-Path $root 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = if ($env:DRISHTI_PYTHON) { $env:DRISHTI_PYTHON } else { 'python' } }
Push-Location $root
try {
    & $python (Join-Path $root 'tools\publicdata\news_and_markets.py') @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
