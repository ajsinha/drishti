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

# Stops what bin\start-all.ps1 started, the console first, by their pid files (bin/stop-all.sh on Linux and macOS). With --all-nodes,
# every server node of the data folder too.
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\stop-all.ps1 [--data-dir DIR] [--env-file FILE] [--all-nodes]

Stops the console, then the server (bin\stop-console.ps1, bin\stop-server.ps1), by their pid files only.

  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>\data)
  --env-file FILE     read DRISHTI_DATA_DIR from this file
  --all-nodes         every server pid file (the single server and every cluster node), not only server.pid
  -h, --help          this text

Exit codes: 0 stopped or not running; 2 a wrong option.
'@

$o = Read-Options $args @{ datadir = 'value'; envfile = 'value'; allnodes = 'switch' }
if ($o['help']) { Write-Host $usage; exit 0 }
$common = @()
if ($o['datadir']) { $common += @('--data-dir', $o['datadir']) }
if ($o['envfile']) { $common += @('--env-file', $o['envfile']) }
& (Join-Path $PSScriptRoot 'stop-console.ps1') @common
$serverArgs = $common
if ($o['allnodes']) { $serverArgs += '--all-nodes' }
& (Join-Path $PSScriptRoot 'stop-server.ps1') @serverArgs
exit 0
