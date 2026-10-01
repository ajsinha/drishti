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
  Builds the banking packs' Delta Lake on Windows (the PowerShell form of tools/load-delta.sh).

.DESCRIPTION
  Writes the sample documents as ten business days of history under -Root (default data\delta), in the layout each
  pack declares; with -Trades it then replaces the trade table with a book of that many trades over -Days days.
  Uses uv when it is installed (it fetches deltalake, pyarrow and pyyaml itself); otherwise the given Python, which
  must have them (python -m pip install deltalake pyarrow pyyaml). The server reads a new load within a minute.

.EXAMPLE
  .\tools\windows\load-delta.ps1
.EXAMPLE
  .\tools\windows\load-delta.ps1 -Root D:\lakes\drishti -Trades 50000 -Days 3
#>
param(
    # The lake's root folder.
    [string]$Root = 'data\delta',
    # A trade book of this many trades a day (0: only the samples).
    [int]$Trades = 0,
    # The business days the trade book covers.
    [int]$Days = 3,
    # The Python to use when uv is not installed.
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo

function Invoke-Tool([string[]]$toolArgs) {
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        & uv run -q --with deltalake --with pyarrow --with pyyaml python @toolArgs
    } else {
        & $Python @toolArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "python $($toolArgs -join ' ') failed. Without uv, install the libraries first: $Python -m pip install deltalake pyarrow pyyaml"
    }
}

Invoke-Tool @('tools/packgen/banking/make_data.py', '--lake', $Root)
if ($Trades -gt 0) {
    Invoke-Tool @('tools/samplegen/bulk_trades.py', '--root', $Root, '--trades', "$Trades", '--days', "$Days")
}
Write-Host "Lake ready under $Root"
