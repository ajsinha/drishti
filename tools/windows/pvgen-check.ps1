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
  Recomputes the VaR the engine should show from the JSONL files and compares it with the server (the PowerShell form of tools/pvgen/check.py).

.DESCRIPTION
  Runs tools/pvgen/check.py with the console's Python (drishti-console\.venv\Scripts\python.exe, else python on the PATH),
  passing every argument on. Pure Python: no .exe of its own. Writes JSONL only (one JSON object per line, the contract a
  pricing system sends). Guide: docs\guides\VAR_GUIDE.md section 15.12; on Windows: docs\qa\windows-custom-data.md.
.EXAMPLE
  .\tools\windows\pvgen-check.ps1 --morning data/pvgen/morning-2026-10-27 --intraday data/pvgen/intraday-2026-10-27 --server http://127.0.0.1:18480
.EXAMPLE
  .\tools\windows\pvgen-check.ps1 --morning data/pvgen/morning-2026-09-30 --scope BANK
.EXAMPLE
  .\tools\windows\pvgen-check.ps1 --help
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$py = Join-Path $repo 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }
& $py (Join-Path $repo 'tools\pvgen\check.py') @args
exit $LASTEXITCODE
