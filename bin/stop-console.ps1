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

# Stops the Drishti console started by bin\start-console.ps1, by its pid file only (never by name). bin/stop-console.sh on Linux and macOS.
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\stop-console.ps1 [--data-dir DIR] [--env-file FILE] [--timeout SECONDS]

Stops the console whose process id is in <data>\run\console.pid: a graceful taskkill first, Stop-Process -Force after the timeout;
then removes the pid file. A pid file whose process is gone is removed and said so. Never stops by name.

  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>\data)
  --env-file FILE     read DRISHTI_DATA_DIR (and the rest) from this file, as bin\start-console.ps1 did
  --timeout SECONDS   how long to wait after the graceful attempt (default 30)
  -h, --help          this text

Exit codes: 0 stopped or not running; 2 a wrong option.
'@

$o = Read-Options $args @{ datadir = 'value'; envfile = 'value'; timeout = 'value' }
if ($o['help']) { Write-Host $usage; exit 0 }
if ($o['envfile']) { Import-EnvFile $o['envfile'] -Quiet }
$data = Get-DataDir (Opt $o 'datadir')
Stop-ByPidFile (Join-Path $data 'run\console.pid') 'console' $script:ConsoleMark ([int](Opt $o 'timeout' 30))
exit 0
