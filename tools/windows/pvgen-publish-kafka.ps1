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
  Intraday updates published to the PV topic, each message JSONL text of several records (needs kafka-python) (the PowerShell form of tools/pvgen/publish_kafka.py).

.DESCRIPTION
  Runs tools/pvgen/publish_kafka.py with the console's Python (drishti-console\.venv\Scripts\python.exe, else python on the PATH),
  passing every argument on. Pure Python: no .exe of its own. Writes JSONL only (one JSON object per line, the contract a
  pricing system sends). Guide: docs\guides\VAR_GUIDE.md section 15.12; on Windows: docs\guides\QA_ON_WINDOWS_WITH_CUSTOM_DATA.md.
.EXAMPLE
  .\tools\windows\pvgen-publish-kafka.ps1 --bootstrap localhost:9092 --rate 5 --seconds 60
.EXAMPLE
  .\tools\windows\pvgen-publish-kafka.ps1 --dry-run --seconds 5
.EXAMPLE
  .\tools\windows\pvgen-publish-kafka.ps1 --per-message 10 --save data/pvgen/sent.jsonl
.EXAMPLE
  .\tools\windows\pvgen-publish-kafka.ps1 --help
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$py = Join-Path $repo 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }
& $py (Join-Path $repo 'tools\pvgen\publish_kafka.py') @args
exit $LASTEXITCODE
