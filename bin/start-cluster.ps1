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

# Starts every node of a cluster file not yet running, then its console; or only the nodes named ("console" for the console).
# The cluster file is drishti-stack/stack.yaml by default (bin/README.md; bin/start-cluster.sh on Linux and macOS).
. (Join-Path $PSScriptRoot 'lib\common.ps1')

if ($args -contains '-h' -or $args -contains '--help') {
    Write-Host 'usage: bin\start-cluster.ps1 [--cluster FILE] [--data-dir DIR] [node ...]   (default cluster file: drishti-stack\stack.yaml)'
    exit 0
}
$py = Get-ToolsPython
if (-not $py) { Fail 'no Python found: make the console''s environment first (bin\start-console.ps1 does it)' 1 }
& $py (Join-Path $PSScriptRoot 'lib\cluster.py') start @args
exit $LASTEXITCODE
