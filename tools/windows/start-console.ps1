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
  Starts the Drishti console (the web front end) on Windows.

.DESCRIPTION
  Runs from the Drishti folder. On first use it makes a Python environment in console\.venv (with uv if it is
  installed, else python -m venv) and installs console\requirements.txt; then it starts the console, which talks to
  the server at -Backend. Open http://localhost:17480/t once it says Uvicorn is running.

.EXAMPLE
  .\tools\windows\start-console.ps1
.EXAMPLE
  .\tools\windows\start-console.ps1 -Backend http://127.0.0.1:18481 -Port 17481
#>
param(
    # The Python 3.11+ to build the environment with (when uv is not installed).
    [string]$Python = 'python',
    # The console's port (DRISHTI_CONSOLE_PORT).
    [int]$Port = $(if ($env:DRISHTI_CONSOLE_PORT) { [int]$env:DRISHTI_CONSOLE_PORT } else { 17480 }),
    # The server (DRISHTI_BACKEND_URL).
    [string]$Backend = $(if ($env:DRISHTI_BACKEND_URL) { $env:DRISHTI_BACKEND_URL } else { 'http://127.0.0.1:18480' }),
    # Rebuild the environment (after console\requirements.txt changed).
    [switch]$Reinstall
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$venv = Join-Path $root 'console\.venv'
$venvPython = Join-Path $venv 'Scripts\python.exe'
$requirements = Join-Path $root 'console\requirements.txt'

if ($Reinstall -and (Test-Path $venv)) { Remove-Item -Recurse -Force $venv }
if (-not (Test-Path $venvPython)) {
    Write-Host 'Making the console''s Python environment in console\.venv ...'
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        & uv venv $venv
        if ($LASTEXITCODE -ne 0) { throw 'uv venv failed.' }
        & uv pip install --python $venvPython -r $requirements
    } else {
        & $Python -m venv $venv
        if ($LASTEXITCODE -ne 0) { throw "$Python -m venv failed: install Python 3.11 or newer (python.org or winget install Python.Python.3.13)." }
        & $venvPython -m pip install -r $requirements
    }
    if ($LASTEXITCODE -ne 0) { throw 'Installing console\requirements.txt failed.' }
}

$env:DRISHTI_CONSOLE_PORT = "$Port"
$env:DRISHTI_BACKEND_URL = $Backend
Write-Host "Drishti console on http://localhost:$Port/t (server $Backend)"
& $venvPython (Join-Path $root 'console\run_drishti_web.py')
exit $LASTEXITCODE
