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
  Intraday batches of amended, new and cancelled trades as JSONL (each its own BATCH_ID, RUN_KIND INTRADAY) (the PowerShell form of tools/pvgen/intraday.py).

.DESCRIPTION
  Runs tools/pvgen/intraday.py with the console's Python (drishti-console\.venv\Scripts\python.exe, else python on the PATH),
  passing every argument on. Pure Python: no .exe of its own. Writes JSONL only (one JSON object per line, the contract a
  pricing system sends). Guide: docs\guides\VAR_GUIDE.md section 15.12; on Windows: docs\guides\QA_ON_WINDOWS_WITH_CUSTOM_DATA.md.
.EXAMPLE
  .\tools\windows\pvgen-intraday.ps1 --batches 20 --trades-per-batch 15
.EXAMPLE
  .\tools\windows\pvgen-intraday.ps1 --batches 5 --dry-run
.EXAMPLE
  .\tools\windows\pvgen-intraday.ps1 --date 2026-10-27 --lake data/delta --server http://127.0.0.1:18480 --every 30s
.EXAMPLE
  .\tools\windows\pvgen-intraday.ps1 --help
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$py = Join-Path $repo 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }
& $py (Join-Path $repo 'tools\pvgen\intraday.py') @args
exit $LASTEXITCODE
