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

# Stops a Drishti server started by bin\start-server.ps1, by its pid file only (never by name). bin/stop-server.sh on Linux and macOS.
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\stop-server.ps1 [--node NAME | --all-nodes] [--data-dir DIR] [--env-file FILE] [--timeout SECONDS]

Stops the server whose process id is in <data>\run\server[-<node>].pid: a graceful taskkill first, Stop-Process -Force after the
timeout; then removes the pid file. A pid file whose process is gone is removed and said so. Never stops by name.

  --node NAME         the cluster node to stop (its pid file server-<node>.pid); without it, the single server (server.pid)
  --all-nodes         every server pid file of the data folder (the single server and every node)
  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>\data)
  --env-file FILE     read DRISHTI_DATA_DIR (and the rest) from this file, as bin\start-server.ps1 did
  --timeout SECONDS   how long to wait after the graceful attempt (default 60)
  -h, --help          this text

Exit codes: 0 stopped or not running; 2 a wrong option.
'@

$o = Read-Options $args @{ node = 'value'; allnodes = 'switch'; datadir = 'value'; envfile = 'value'; timeout = 'value' }
if ($o['help']) { Write-Host $usage; exit 0 }
if ($o['node'] -and $o['allnodes']) { Fail '--node and --all-nodes: one or the other' 2 }
if ($o['envfile']) { Import-EnvFile $o['envfile'] -Quiet }
$data = Get-DataDir (Opt $o 'datadir')
$timeout = [int](Opt $o 'timeout' 60)
if ($o['allnodes']) {
    $files = @(Get-ChildItem -Path (Join-Path $data 'run') -Filter 'server*.pid' -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { Write-Host "no server pid file in $(Join-Path $data 'run'): nothing to stop" }
    foreach ($f in $files) { Stop-ByPidFile $f.FullName ($f.BaseName -replace '^server-', '') $script:ServerMark $timeout }
    exit 0
}
$node = Opt $o 'node'
$stem = if ($node) { "server-$node" } else { 'server' }
Stop-ByPidFile (Join-Path $data "run\$stem.pid") $(if ($node) { $node } else { 'server' }) $script:ServerMark $timeout
exit 0
