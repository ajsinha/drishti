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

# What the bin\*.ps1 scripts share (bin\README.md): the options, the data folder, the env-file, this machine's real address, process
# ids, health. Dot-sourced, never run. Windows PowerShell 5.1 and PowerShell 7: no ??, no ternary, no && in the code. The Linux and
# macOS twin is bin/lib/common.sh. Every option is accepted as --name value or -Name value (bin\README.md).

$ErrorActionPreference = 'Stop'
$script:DrishtiRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:DefaultServerPort = 18480
$script:DefaultConsolePort = 17480
$script:ServerMark = '-jar'
$script:ConsoleMark = 'run_drishti_web.py'

function Fail([string]$Message, [int]$Code = 1) {
    [Console]::Error.WriteLine("error: $Message")
    exit $Code
}

function Warn([string]$Message) { [Console]::Error.WriteLine("warning: $Message") }

# Read-Options ARGS SPEC: SPEC maps an option name (lower case, no dashes: nodedir) to 'switch', 'value' or 'list'. --node-dir, -NodeDir
# and -nodedir are the same option. A comma list typed in PowerShell arrives as an array and is joined back with commas. '--' ends the
# options: the rest is in the result's '--' entry. -h, -?, --help and -Help set 'help'.
function Read-Options([object[]]$Argv, [hashtable]$Spec) {
    $out = @{ '--' = @() }
    $i = 0
    while ($i -lt $Argv.Count) {
        $a = $Argv[$i]
        if ($a -is [array]) { $a = ($a -join ',') }
        $a = [string]$a
        if ($a -eq '--') {
            if ($i + 1 -lt $Argv.Count) { $out['--'] = @($Argv[($i + 1)..($Argv.Count - 1)] | ForEach-Object { [string]$_ }) }
            break
        }
        $name = $a.TrimStart('-').Replace('-', '').ToLowerInvariant()
        if ($a -in @('-h', '-?', '/?') -or $name -eq 'help') { $out['help'] = $true; $i++; continue }
        if (-not $a.StartsWith('-') -or -not $Spec.ContainsKey($name)) { Fail "unknown option $a (see --help)" 2 }
        $kind = $Spec[$name]
        if ($kind -eq 'switch') { $out[$name] = $true; $i++; continue }
        if ($i + 1 -ge $Argv.Count) { Fail "$a needs a value (see --help)" 2 }
        $v = $Argv[$i + 1]
        if ($v -is [array]) { $v = ($v -join ',') }
        if ($kind -eq 'list' -and $out.ContainsKey($name)) { $out[$name] = @($out[$name]) + @([string]$v) } elseif ($kind -eq 'list') { $out[$name] = @([string]$v) } else { $out[$name] = [string]$v }
        $i += 2
    }
    return $out
}

function Opt([hashtable]$Opts, [string]$Name, $Default = '') {
    if ($Opts.ContainsKey($Name)) { return $Opts[$Name] }
    return $Default
}

# Import-EnvFile FILE: KEY=VALUE lines (blank lines, # comments and a leading 'export ' allowed; one pair of quotes around the value is
# removed) put into this process's environment, so they reach the process it starts. The values are never printed.
function Import-EnvFile([string]$File, [switch]$Quiet) {
    if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { Fail "no env-file $File" }
    $n = 0
    foreach ($line in Get-Content -LiteralPath $File) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        if ($t.StartsWith('export ')) { $t = $t.Substring(7) }
        $eq = $t.IndexOf('=')
        if ($eq -lt 1) { Warn "${File}: a line without KEY=VALUE was skipped"; continue }
        $key = $t.Substring(0, $eq).Trim()
        $value = $t.Substring($eq + 1)
        if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { Warn "${File}: '$key' is not a variable name; skipped"; continue }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        $n++
    }
    if (-not $Quiet) { Write-Host "env-file ${File}: $n setting(s) read (values not shown)" }
}

# Get-DataDir [DIR]: the data folder: DIR (--data-dir), else DRISHTI_DATA_DIR, else the repository's data\; made, and absolute
function Get-DataDir([string]$Dir = '') {
    if (-not $Dir) { $Dir = $env:DRISHTI_DATA_DIR }
    if (-not $Dir) { $Dir = Join-Path $script:DrishtiRoot 'data' }
    if (-not [IO.Path]::IsPathRooted($Dir)) { $Dir = Join-Path (Get-Location).Path $Dir }
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    return (Resolve-Path -LiteralPath $Dir).Path
}

function Test-LoopbackOrWildcard([string]$Address) {
    return (-not $Address) -or $Address -eq 'localhost' -or $Address.StartsWith('127.') -or $Address -eq '0.0.0.0' -or $Address -eq '::' -or
        $Address -eq '::1' -or $Address -eq '[::1]' -or $Address.StartsWith('169.254.')
}

# Get-LanIp: this machine's primary IPv4 address (the default route's interface; never loopback or APIPA); empty when it has none
function Get-LanIp {
    try {
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop | Sort-Object RouteMetric, InterfaceMetric | Select-Object -First 1
        if ($route) {
            $ip = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction Stop |
                Where-Object { -not (Test-LoopbackOrWildcard $_.IPAddress) } | Select-Object -First 1
            if ($ip) { return $ip.IPAddress }
        }
        $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | Where-Object { -not (Test-LoopbackOrWildcard $_.IPAddress) } |
            Sort-Object InterfaceMetric | Select-Object -First 1
        if ($ip) { return $ip.IPAddress }
    } catch { }
    try {
        foreach ($a in [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName())) {
            if ($a.AddressFamily -eq 'InterNetwork' -and -not (Test-LoopbackOrWildcard $a.ToString())) { return $a.ToString() }
        }
    } catch { }
    return ''
}

function Get-AdvertiseHost([string]$Address = '') {
    if ($Address) {
        if (Test-LoopbackOrWildcard $Address) { Fail "--advertise $Address is a loopback or wildcard address: members advertise an address other machines can call ($(Get-LanIp))" }
        return $Address
    }
    $ip = Get-LanIp
    if (-not $ip) { Fail 'this machine has no address but loopback: give the one to advertise with --advertise <ip>' }
    return $ip
}

function Test-HttpOk([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300)
    } catch { return $false }
}

function Test-PortInUse([int]$Port) {
    try {
        $l = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
        if ($l) { return $true }
    } catch { }
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $Port); return $true } catch { return $false } finally { $c.Close() }
}

# a pid file: the process id on its first line, then port=, url=, health=, data= lines
function Get-PidOf([string]$File) {
    if (-not (Test-Path -LiteralPath $File)) { return 0 }
    $first = (Get-Content -LiteralPath $File -TotalCount 1)
    $n = 0
    if ([int]::TryParse(("$first").Trim(), [ref]$n)) { return $n }
    return 0
}

function Get-PidField([string]$File, [string]$Key) {
    if (-not (Test-Path -LiteralPath $File)) { return '' }
    foreach ($line in Get-Content -LiteralPath $File) { if ($line.StartsWith("$Key=")) { return $line.Substring($Key.Length + 1) } }
    return ''
}

function Write-PidFile([string]$File, [int]$ProcessId, [hashtable]$Fields) {
    $lines = @("$ProcessId") + @($Fields.Keys | Sort-Object | ForEach-Object { "$_=$($Fields[$_])" })
    New-Item -ItemType Directory -Force -Path (Split-Path $File) | Out-Null
    Set-Content -LiteralPath $File -Value $lines -Encoding ASCII
}

# Test-Ours PID MARK: the process runs and its command line contains MARK (so a reused process id is never taken for ours)
function Test-Ours([int]$ProcessId, [string]$Mark) {
    if ($ProcessId -le 0) { return $false }
    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $false }
    try {
        $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction Stop).CommandLine
        return ("$cmd").Contains($Mark)
    } catch { return $true }
}

function Wait-Health([string]$Url, [int]$Seconds, $Process) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) { return $false }
        if (Test-HttpOk $Url) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Stop-ByPidFile FILE NAME MARK SECONDS: stop the one process of FILE (a graceful taskkill first, Stop-Process -Force after SECONDS);
# never by name
function Stop-ByPidFile([string]$File, [string]$Name, [string]$Mark, [int]$Seconds) {
    if (-not (Test-Path -LiteralPath $File)) { Write-Host "${Name}: not running (no $File)"; return }
    $id = Get-PidOf $File
    if (-not (Test-Ours $id $Mark)) {
        Remove-Item -LiteralPath $File -Force
        Write-Host "${Name}: not running (pid $id is gone or is not Drishti's); stale $File removed"
        return
    }
    & taskkill.exe /PID $id 2>&1 | Out-Null          # graceful: asks the process to close (a console process may refuse)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline -and (Get-Process -Id $id -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 500 }
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
        Stop-Process -Id $id -Force
        Write-Host "${Name}: stopped with Stop-Process -Force after $Seconds s (pid $id)"
    } else {
        Write-Host "${Name}: stopped (pid $id)"
    }
    Remove-Item -LiteralPath $File -Force -ErrorAction SilentlyContinue
}

# Get-ToolsPython: the Python to run repository tools with (DRISHTI_PYTHON, the console's environment, python); empty when none
function Get-ToolsPython {
    if ($env:DRISHTI_PYTHON) { return $env:DRISHTI_PYTHON }
    $venv = Join-Path $script:DrishtiRoot 'drishti-console\.venv\Scripts\python.exe'
    if (Test-Path $venv) { return $venv }
    $py = Get-Command python -ErrorAction SilentlyContinue
    if ($py) { return $py.Source }
    return ''
}

# Join-ProcessArgs ARGS: one command line for Start-Process (Windows PowerShell 5.1 does not quote the elements of -ArgumentList)
function Join-ProcessArgs([string[]]$Items) {
    return (($Items | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' ')
}

# Set-DevSecret NAME VALUE: a development value only where none is set; returns the name when it was used
function Set-DevSecret([string]$Name, [string]$Value) {
    if (-not [Environment]::GetEnvironmentVariable($Name, 'Process')) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
        return $Name
    }
    return $null
}
