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
  Starts the Drishti server on Windows: Java 21 or newer (25 recommended), the native Delta engine (no Hadoop, no winutils.exe).

.DESCRIPTION
  Runs from the Drishti folder (a clone, or a copy holding the server jar, packs\ and data\). Finds the server jar,
  checks that Java is 21 or newer, sets the Delta engine to native and starts the server with -XX:+UseCompactObjectHeaders on Java 25+.
  In the foreground by default (Ctrl+C stops it); with -Background it starts in a window of its own, writes its
  process id to data\server.pid and its log to data\logs\server.log (stop it with -Stop).

.EXAMPLE
  .\tools\windows\start-server.ps1
.EXAMPLE
  .\tools\windows\start-server.ps1 -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot' -Packs market-risk -Background
.EXAMPLE
  .\tools\windows\start-server.ps1 -Stop
#>
param(
    # The JDK 25 folder; default: DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH.
    [string]$JavaHome = $(if ($env:DRISHTI_JAVA_HOME) { $env:DRISHTI_JAVA_HOME } else { $env:JAVA_HOME }),
    # The server jar; default: drishti-server\target\drishti-server-*-exec.jar, then drishti-server*.jar in the folder.
    [string]$Jar = '',
    # The packs to load (DRISHTI_PACKS); default: what DRISHTI_PACKS says, else the server's default (finance).
    [string]$Packs = $env:DRISHTI_PACKS,
    # The server's port (DRISHTI_PORT).
    [int]$Port = $(if ($env:DRISHTI_PORT) { [int]$env:DRISHTI_PORT } else { 18480 }),
    # The Delta Lake root (DRISHTI_DELTA_ROOT).
    [string]$LakeRoot = $(if ($env:DRISHTI_DELTA_ROOT) { $env:DRISHTI_DELTA_ROOT } else { '.\data\delta' }),
    # The Delta engine (DRISHTI_DELTA_ENGINE): native works on Windows; hadoop needs winutils.exe.
    [ValidateSet('native', 'hadoop', 'auto')]
    [string]$Engine = $(if ($env:DRISHTI_DELTA_ENGINE) { $env:DRISHTI_DELTA_ENGINE } else { 'native' }),
    # The heap limit, e.g. 2g or 8g.
    [string]$Heap = '2g',
    # Start in the background (log in data\logs\server.log, process id in data\server.pid).
    [switch]$Background,
    # Stop a server started with -Background.
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$pidFile = Join-Path $root 'data\server.pid'

if ($Stop) {
    if (-not (Test-Path $pidFile)) { Write-Host 'No data\server.pid: no server was started with -Background.'; exit 0 }
    $serverPid = [int](Get-Content $pidFile -Raw)
    $p = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
    if ($p) { Stop-Process -Id $serverPid; Write-Host "Stopped the server (process $serverPid)." } else { Write-Host "Process $serverPid is not running." }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    exit 0
}

# Java 21 or newer (25 recommended)
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java' }
if ($JavaHome -and -not (Test-Path $java)) { throw "No java.exe under $JavaHome\bin: set -JavaHome (or DRISHTI_JAVA_HOME) to a JDK 21 or newer folder." }
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'                 # java -version writes to stderr
$version = (& $java -version 2>&1 | Out-String)
$ErrorActionPreference = $previous
$major = if ($version -match 'version "(\d+)') { [int]$Matches[1] } else { 0 }
if ($major -lt 21) {
    throw "Drishti runs on Java 21 or newer (25 recommended); $java says:`n$version`nInstall Temurin JDK 25 and pass -JavaHome (or set DRISHTI_JAVA_HOME)."
}

# the jar
if (-not $Jar) {
    $candidates = @(Get-ChildItem -Path (Join-Path $root 'drishti-server\target') -Filter 'drishti-server-*-exec.jar' -ErrorAction SilentlyContinue) +
                  @(Get-ChildItem -Path $root -Filter 'drishti-server*.jar' -ErrorAction SilentlyContinue)
    if ($candidates.Count -eq 0) { throw 'No server jar: build it (.\mvnw.cmd -q package -DskipTests) or copy drishti-server-<version>-exec.jar here.' }
    $Jar = ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not (Test-Path (Join-Path $root 'packs'))) { Write-Warning "No packs\ folder in $root: the server will start without domain packs." }

$env:DRISHTI_DELTA_ENGINE = $Engine
$env:DRISHTI_PORT = "$Port"
$env:DRISHTI_DELTA_ROOT = $LakeRoot
if ($Packs) { $env:DRISHTI_PACKS = $Packs }
# compact object headers exist from Java 25 (Java 21 refuses to start with the flag)
$javaArgs = @("-Xmx$Heap", '-jar', $Jar)
if ($major -ge 25) { $javaArgs = @('-XX:+UseCompactObjectHeaders') + $javaArgs }
Write-Host "Drishti server: $Jar on port $Port, Delta engine $Engine, lake $LakeRoot, packs $(if ($Packs) { $Packs } else { '(default)' })"

if ($Background) {
    $logs = Join-Path $root 'data\logs'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $p = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'server.err.log')
    Set-Content -Path $pidFile -Value $p.Id
    Write-Host "Started (process $($p.Id)); log: data\logs\server.log. Waiting for http://localhost:$Port/actuator/health ..."
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 2
        if ($p.HasExited) { throw "The server stopped; see data\logs\server.log and data\logs\server.err.log." }
        try {
            $h = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 2
            if ($h.status -eq 'UP') { Write-Host 'The server is up.'; exit 0 }
        } catch { }
    }
    throw 'The server did not come up in four minutes; see data\logs\server.log.'
}
& $java @javaArgs
exit $LASTEXITCODE
