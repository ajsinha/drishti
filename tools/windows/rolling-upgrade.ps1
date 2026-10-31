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
  The rolling upgrade runner on Windows (the PowerShell form of tools/rolling-upgrade.sh; CD3, CLUSTER_DEPLOYMENT.md section 10.1).

.DESCRIPTION
  One node at a time: consoles, BI and data-serving, coordinators, then owners one zone at a time; each drained, stopped, upgraded,
  started and waited for until ready. Any failure stops the run (resume with -From). -DryRun prints every step and calls nothing.
  The inventory is deploy\rolling.example.yaml's shape; its commands run in the shell Python starts (cmd.exe on Windows). The token is
  an administrator's personal API token with the node:drain scope (-TokenFile, else DRISHTI_DRAIN_TOKEN). Uses uv when installed,
  otherwise the given Python, which must have pyyaml (or give a JSON inventory).

.EXAMPLE
  .\tools\windows\rolling-upgrade.ps1 -Inventory my-cluster.yaml -Version 2.9.7 -DryRun
.EXAMPLE
  .\tools\windows\rolling-upgrade.ps1 -Inventory my-cluster.yaml -Version 2.9.7 -From var-3
#>
param(
    [Parameter(Mandatory = $true)][string]$Inventory,
    [string]$Version = '',
    [switch]$DryRun,
    [string]$From = '',
    [string]$Only = '',
    [string]$TokenFile = '',
    # The Python to use when uv is not installed.
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
$a = @('tools/rolling.py', '--inventory', $Inventory)
if ($Version) { $a += @('--version', $Version) }
if ($DryRun) { $a += '--dry-run' }
if ($From) { $a += @('--from', $From) }
if ($Only) { $a += @('--only', $Only) }
if ($TokenFile) { $a += @('--token-file', $TokenFile) }
if (Get-Command uv -ErrorAction SilentlyContinue) {
    & uv run -q --with pyyaml python @a
} else {
    & $Python @a
}
exit $LASTEXITCODE
