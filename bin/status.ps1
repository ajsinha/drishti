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

# Every Drishti process this machine started with bin\: its pid, port, health answer and LAN address, from the pid files of the data
# folder (bin/status.sh on Linux and macOS).
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\status.ps1 [--data-dir DIR ...] [--env-file FILE] [--cluster FILE]

For each pid file in <data>\run (server.pid, server-<node>.pid, console.pid): the process id, whether it runs, its port, whether
its health URL answers, and the address other machines open.

  --data-dir DIR      the data folder (repeatable; default DRISHTI_DATA_DIR, else <drishti>\data)
  --env-file FILE     read DRISHTI_DATA_DIR from this file
  --cluster FILE      a cluster file: also the members each node sees and the console's servers
  -h, --help          this text

Exit codes: 0 every process found runs and answers (or none was started); 1 one is not running or not answering; 2 a wrong option.
'@

if ($args -contains '--cluster') {
    $py = Get-ToolsPython
    if (-not $py) { Fail 'no Python found for --cluster' 1 }
    & $py (Join-Path $PSScriptRoot 'lib\cluster.py') status @args
    exit $LASTEXITCODE
}
$o = Read-Options $args @{ datadir = 'list'; envfile = 'value' }
if ($o['help']) { Write-Host $usage; exit 0 }
if ($o['envfile']) { Import-EnvFile $o['envfile'] -Quiet }
$dirs = @($o['datadir'] | Where-Object { $_ })
if ($dirs.Count -eq 0) { $dirs = @('') }
$rc = 0
$any = $false
Write-Host ('{0,-22} {1,8}  {2,-10} {3,-6} {4,-10} {5,-16} {6}' -f 'process', 'pid', 'state', 'port', 'health', 'distribution', 'address')
foreach ($d in $dirs) {
    $data = Get-DataDir $d
    $files = @(Get-ChildItem -Path (Join-Path $data 'run') -Filter 'server*.pid' -ErrorAction SilentlyContinue | Sort-Object Name) +
             @(Get-ChildItem -Path (Join-Path $data 'run') -Filter 'console*.pid' -ErrorAction SilentlyContinue | Sort-Object Name)
    foreach ($f in $files) {
        $any = $true
        $mark = if ($f.BaseName -like 'console*') { $script:ConsoleMark } else { $script:ServerMark }
        $id = Get-PidOf $f.FullName
        $state = 'running'
        if (-not (Test-Ours $id $mark)) { $state = 'stale pid'; $rc = 1 }
        $health = Get-PidField $f.FullName 'health'
        $answer = '-'
        if ($health) { if (Test-HttpOk $health) { $answer = 'UP' } else { $answer = 'no answer'; $rc = 1 } }
        $dist = Get-PidField $f.FullName 'distribution'
        if (-not $dist) { $dist = 'all' }
        Write-Host ('{0,-22} {1,8}  {2,-10} {3,-6} {4,-10} {5,-16} {6}' -f $f.BaseName, $id, $state, (Get-PidField $f.FullName 'port'), $answer, $dist, (Get-PidField $f.FullName 'url'))
    }
}
if (-not $any) { Write-Host '(no Drishti process started with bin\ in this data folder)' }
exit $rc
