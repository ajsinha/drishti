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

# One server and one console on this machine, in the background: bin\start-server.ps1, then bin\start-console.ps1 pointed at it
# (bin/start-all.sh on Linux and macOS). A cluster is started node by node with bin\start-server.ps1 --node.
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\start-all.ps1 [--demo] [--env-file FILE] [--data-dir DIR] [--server-port N] [--console-port N] [--heap SIZE]

Starts one server and one console on this machine in the background (the simplest case), the console pointed at the server's LAN
address. The options go to bin\start-server.ps1 and bin\start-console.ps1 (see their --help).

  --demo              the demo with DEVELOPMENT secrets: NOT FOR PRODUCTION
  --env-file FILE     the secrets and settings of both (KEY=VALUE lines)
  --data-dir DIR      the data folder of both
  --server-port N     the server's port (default 18480)
  --console-port N    the console's port (default 17480)
  --heap SIZE         the server's heap (-Xmx)
  -h, --help          this text

Exit codes: 0 both up; otherwise the failing script's code. bin\stop-all.ps1 stops both.
'@

$o = Read-Options $args @{ demo = 'switch'; envfile = 'value'; datadir = 'value'; serverport = 'value'; consoleport = 'value'; heap = 'value' }
if ($o['help']) { Write-Host $usage; exit 0 }
$common = @()
if ($o['demo']) { $common += '--demo' }
if ($o['envfile']) { $common += @('--env-file', $o['envfile']) }
if ($o['datadir']) { $common += @('--data-dir', $o['datadir']) }
$serverPort = Opt $o 'serverport' $script:DefaultServerPort
$serverArgs = $common + @('--port', $serverPort, '--background')
if ($o['heap']) { $serverArgs += @('--heap', $o['heap']) }
$hostIp = Get-LanIp
if (-not $hostIp) { $hostIp = 'localhost' }
$consoleArgs = $common + @('--server', "http://${hostIp}:$serverPort", '--background')
if ($o['consoleport']) { $consoleArgs += @('--port', $o['consoleport']) }
& (Join-Path $PSScriptRoot 'start-server.ps1') @serverArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $PSScriptRoot 'start-console.ps1') @consoleArgs
exit $LASTEXITCODE
