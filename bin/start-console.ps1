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

# Starts the Drishti console (the web front end) on Windows, pointed at a server or at the entry nodes of a cluster. The same options as
# bin/start-console.sh (Linux and macOS), as --name value or -Name value; bin\start-console.cmd runs it from cmd or a double-click.
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\start-console.ps1 [options]        (or bin\start-console.cmd [options])

Starts the console: finds its Python (makes drishti-console\.venv on first use), points it at the server, waits for /healthz and
prints the address people open.

  --server URLS       the server, or a cluster's entry nodes, comma-separated (http://<ip>:18480,...): the console uses the first that
                      answers and learns the other members from the cluster. Default DRISHTI_SERVER_URL (from the environment or the
                      --env-file), else this machine's LAN address :18480 (with a warning). -Backend, the old name, is the same
  --port N            the console's port (default DRISHTI_CONSOLE_PORT, else 17480)
  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>\data): run\console.pid, logs\console.log
  --env-file FILE     KEY=VALUE lines put into the console's environment (the token and session secrets; values never printed)
  --demo              the demo's settings with DEVELOPMENT secrets (the same as bin\start-server.ps1 --demo): NOT FOR PRODUCTION
  --python FILE       the Python 3.11+ to make the environment with when uv is not installed (default python)
  --reinstall         remake drishti-console\.venv (after drishti-console\requirements.txt changed)
  --background        detach: log in <data>\logs\console.log, pid in <data>\run\console.pid
  --wait SECONDS      how long to wait for the health answer (default 90)
  -h, --help          this text

Environment: DRISHTI_SERVER_URL, DRISHTI_PYTHON (the console's Python; default drishti-console\.venv), DRISHTI_CONSOLE_PORT,
DRISHTI_DATA_DIR, DRISHTI_TOKEN_SECRET and DRISHTI_SESSION_SECRET (the same as the servers'). Exit codes: 0 up (or, in the
foreground, stopped cleanly); 1 failed to start; 2 a wrong option; 3 already running or the port is in use.

examples:
  bin\start-console.cmd --demo --background
  bin\start-console.ps1 -Server http://192.168.1.20:18480 -EnvFile C:\drishti\console.env -Background
'@

$o = Read-Options $args @{ server = 'list'; backend = 'list'; port = 'value'; datadir = 'value'; envfile = 'value'; demo = 'switch'
    python = 'value'; reinstall = 'switch'; background = 'switch'; wait = 'value' }
if ($o['help']) { Write-Host $usage; exit 0 }
if ($o['envfile']) { Import-EnvFile $o['envfile'] }
$data = Get-DataDir (Opt $o 'datadir')
$env:DRISHTI_DATA_DIR = $data
$port = [int](Opt $o 'port' $(if ($env:DRISHTI_CONSOLE_PORT) { $env:DRISHTI_CONSOLE_PORT } else { $script:DefaultConsolePort }))
$pidFile = Join-Path $data 'run\console.pid'
$log = Join-Path $data 'logs\console.log'
$wait = [int](Opt $o 'wait' 90)

$existing = Get-PidOf $pidFile
if (Test-Ours $existing $script:ConsoleMark) {
    Write-Host "console: already running (pid $existing, port $(Get-PidField $pidFile 'port')); bin\stop-console.ps1 stops it"
    exit 3
}
if (Test-Path $pidFile) { Remove-Item $pidFile -Force; Write-Host "console: stale $pidFile removed (pid $existing is not running)" }
if (Test-PortInUse $port) { Fail "port $port is in use by another process: stop it, or pick another with --port" 3 }

# the console's Python: DRISHTI_PYTHON, else drishti-console\.venv (made on first use, with uv when it is installed)
$venv = Join-Path $script:DrishtiRoot 'drishti-console\.venv'
$requirements = Join-Path $script:DrishtiRoot 'drishti-console\requirements.txt'
if ($env:DRISHTI_PYTHON) {
    $py = $env:DRISHTI_PYTHON
} else {
    $py = Join-Path $venv 'Scripts\python.exe'
    if ($o['reinstall'] -and (Test-Path $venv)) { Remove-Item -Recurse -Force $venv }
    if (-not (Test-Path $py)) {
        Write-Host 'making the console''s Python environment in drishti-console\.venv ...'
        if (Get-Command uv -ErrorAction SilentlyContinue) {
            & uv venv $venv
            if ($LASTEXITCODE -ne 0) { Fail 'uv venv failed' }
            & uv pip install --python $py -r $requirements
        } else {
            $base = Opt $o 'python' 'python'
            & $base -m venv $venv
            if ($LASTEXITCODE -ne 0) { Fail "$base -m venv failed: install Python 3.11 or newer (python.org or winget install Python.Python.3.13), or pass --python" }
            & $py -m pip install -r $requirements
        }
        if ($LASTEXITCODE -ne 0) { Fail 'installing drishti-console\requirements.txt failed' }
    }
}

$devSecrets = @()
if ($o['demo']) {
    $env:DRISHTI_AUTH_ENABLED = 'true'
    $env:DRISHTI_SECURE_COOKIE = 'false'
    if (-not $env:DRISHTI_PACKS) { $env:DRISHTI_PACKS = 'core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds' }
    $devSecrets += Set-DevSecret 'DRISHTI_TOKEN_SECRET' 'drishti-dev-only-token-secret-change-me-0123456789'
    $devSecrets += Set-DevSecret 'DRISHTI_SESSION_SECRET' 'drishti-dev-only-session-secret-change-me-0123456789'
}
$devSecrets = @($devSecrets | Where-Object { $_ })

# the servers: --server, else DRISHTI_SERVER_URL, else this machine's LAN address
$hostIp = Get-LanIp
if (-not $hostIp) { $hostIp = 'localhost' }
$given = @($o['server']) + @($o['backend']) | Where-Object { $_ }
$servers = ($given -join ',')
if (-not $servers) { $servers = $env:DRISHTI_SERVER_URL }
if (-not $servers) {
    $servers = "http://${hostIp}:$($script:DefaultServerPort)"
    Warn "no --server (or DRISHTI_SERVER_URL): using this machine's $servers"
}
$list = @()
$first = ''
foreach ($s in ($servers -split ',' | Where-Object { $_ })) {
    $s = $s.Trim()
    if ($s -notmatch '^https?://') { $s = "http://$s" }
    $s = $s.TrimEnd('/')
    $h = ($s -replace '^https?://', '') -replace '[:/].*$', ''
    if (Test-LoopbackOrWildcard $h) { Warn "$s is a loopback address: in a cluster give the entry node's real address, as the members advertise it" }
    $list += $s
    if (-not $first -and (Test-HttpOk "$s/actuator/health")) { $first = $s }
}
if (-not $first) {
    $first = $list[0]
    Warn "no server answers yet at $($list -join ','): the console starts and keeps trying"
}
$env:DRISHTI_BACKEND_URLS = ($list -join ',')
$env:DRISHTI_BACKEND_URL = $first
$env:DRISHTI_CONSOLE_PORT = "$port"

$url = "http://${hostIp}:$port"
$health = "http://127.0.0.1:$port/healthz"
Write-Host "Drishti console on port $port, given $($env:DRISHTI_BACKEND_URLS) (using $first first), data $data"
if ($devSecrets.Count -gt 0) { Warn "DEVELOPMENT secrets in use (NOT FOR PRODUCTION): $($devSecrets -join ' '). Put your own in an --env-file for anything shared." }
New-Item -ItemType Directory -Force -Path (Join-Path $data 'run'), (Join-Path $data 'logs') | Out-Null
$fields = @{ port = $port; url = $url; health = $health; data = $data }
$runArgs = Join-ProcessArgs @((Join-Path $script:DrishtiRoot 'drishti-console\run_drishti_web.py'), "--server.port=$port")

if ($o['background']) {
    $p = Start-Process -FilePath $py -ArgumentList $runArgs -WorkingDirectory $script:DrishtiRoot -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError (Join-Path $data 'logs\console.err.log')
    Write-PidFile $pidFile $p.Id $fields
    Write-Host "console: started (pid $($p.Id)); log $log; waiting up to $wait s for $health"
    if (Wait-Health $health $wait $p) { Write-Host "console: up on $url"; exit 0 }
    if ($p.HasExited) { Remove-Item $pidFile -Force -ErrorAction SilentlyContinue }
    Write-Host "console: did not answer in $wait s: see $log and $(Join-Path $data 'logs\console.err.log')"
    exit 1
}
$p = Start-Process -FilePath $py -ArgumentList $runArgs -WorkingDirectory $script:DrishtiRoot -PassThru -NoNewWindow
Write-PidFile $pidFile $p.Id $fields
try {
    if (Wait-Health $health $wait $p) { Write-Host "console: up on $url" }
    $p.WaitForExit()
    exit $p.ExitCode
} finally {
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}
