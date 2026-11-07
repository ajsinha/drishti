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
  Runs a Drishti data feed (the PowerShell form of tools/feeds/feed.sh): pull external data and load it into the lakes.

.DESCRIPTION
  Runs `tools\drishti.py feed` with the console's Python environment (drishti-console\.venv, made by tools\windows\start-console.ps1)
  when it exists, else DRISHTI_PYTHON or python. Python and Java only: no other program is needed. Every argument is passed on
  unchanged: list, run <feed>|--all [--date D | --since D] [--load java|python|inbox|none] [--dry-run] [--snapshot], status,
  schedule --windows. The exit code is the command's (0 ok, 1 failed, 2 refused, 3 locked, 4 partial), so Task Scheduler's
  "Last Run Result" shows it. Guide: docs\guides\FEEDS_GUIDE.md.

.EXAMPLE
  .\tools\windows\feed.ps1 list
.EXAMPLE
  .\tools\windows\feed.ps1 run gleif
.EXAMPLE
  .\tools\windows\feed.ps1 run --all --load inbox
.EXAMPLE
  .\tools\windows\feed.ps1 schedule --windows
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = Join-Path $root 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = if ($env:DRISHTI_PYTHON) { $env:DRISHTI_PYTHON } else { 'python' } }
Push-Location $root
try {
    & $python (Join-Path $root 'tools\drishti.py') feed @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
