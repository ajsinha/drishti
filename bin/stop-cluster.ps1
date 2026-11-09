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

# Stops a cluster's console and nodes by their pid files (never by name), or only the nodes named.
# The cluster file is drishti-stack/stack.yaml by default (bin/README.md; bin/stop-cluster.sh on Linux and macOS).
. (Join-Path $PSScriptRoot 'lib\common.ps1')

if ($args -contains '-h' -or $args -contains '--help') {
    Write-Host 'usage: bin\stop-cluster.ps1 [--cluster FILE] [--data-dir DIR] [node ...]   (default cluster file: drishti-stack\stack.yaml)'
    exit 0
}
$py = Get-ToolsPython
if (-not $py) { Fail 'no Python found: make the console''s environment first (bin\start-console.ps1 does it)' 1 }
& $py (Join-Path $PSScriptRoot 'lib\cluster.py') stop @args
exit $LASTEXITCODE
