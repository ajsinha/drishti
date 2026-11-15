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
  Checks that the Polars lake-loader engine can run on this Windows machine with no .exe anywhere (POLARS_LOADER_DESIGN.md section 6.3).

.DESCRIPTION
  The three checks of the design, in order; the script stops at the first that fails, because nothing else depends on a wheel that
  does not load:
    1. No .exe inside the wheels (and none installed): the wheels of requirements-polars.txt are listed, not run.
    2. The libraries load: polars, deltalake and pyarrow are imported by the console's Python (drishti-console\.venv\Scripts\python.exe,
       else python on the PATH).
    3. A Delta table is written and read back through Polars (a scratch table under data\tmp, removed afterwards).
  Every step prints PASS or FAIL and why; the exit code is 0 only when all three pass. Needs the optional libraries installed
  (pip install -r drishti-console\requirements-polars.txt); a machine with no network uses a wheel folder (-Wheels), built on a machine
  that has one with -Download. Pure PowerShell and Python: this script has no .exe of its own.
  Not decided by this script: a policy that blocks unsigned DLLs loaded from a user's folder shows as FAIL in step 2, and a CPU without
  AVX2 needs polars[rtcompat] (the step 2 message says which). Document: docs\architecture\POLARS_LOADER_DESIGN.md section 6.3.
.PARAMETER Wheels
  The folder holding the wheels to list in step 1. Default: the folder -Download wrote, else wheels\ under data\tmp is downloaded to.
.PARAMETER Download
  Only download the wheels (pip download --only-binary=:all: for win_amd64, Python 3.13) into -Wheels (default data\tmp\polars-wheels) and
  exit; install on a machine with no network with: pip install --no-index --find-links <folder> -r drishti-console\requirements-polars.txt
.EXAMPLE
  .\tools\windows\polars-check.ps1
.EXAMPLE
  .\tools\windows\polars-check.ps1 -Download -Wheels D:\wheels
.EXAMPLE
  .\tools\windows\polars-check.ps1 -Wheels D:\wheels
#>
param(
  [string]$Wheels = '',
  [switch]$Download
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$py = Join-Path $repo 'drishti-console\.venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }
$req = Join-Path $repo 'drishti-console\requirements-polars.txt'
$scratch = Join-Path $repo 'data\tmp'
if (-not $Wheels) { $Wheels = Join-Path $scratch 'polars-wheels' }

function Step([string]$name, [bool]$ok, [string]$why) {
  if ($ok) { Write-Host "PASS  $name" } else { Write-Host "FAIL  $name : $why"; exit 1 }
}

if ($Download -or -not (Test-Path $Wheels) -or -not (Get-ChildItem $Wheels -Filter *.whl -ErrorAction SilentlyContinue)) {
  New-Item -ItemType Directory -Force $Wheels | Out-Null
  & $py -m pip download --only-binary=:all: --platform win_amd64 --python-version 3.13 -r $req -d $Wheels
  if ($LASTEXITCODE -ne 0) { Step 'wheels downloaded' $false "pip download failed (exit $LASTEXITCODE)" }
  Write-Host "wheels in $Wheels"
  if ($Download) { exit 0 }
}

# 1. no .exe inside the wheels
$found = @()
foreach ($w in Get-ChildItem $Wheels -Filter *.whl) {
  $listing = & $py -m zipfile -l $w.FullName
  $found += $listing | Select-String '\.exe'
}
Step '1. no .exe inside the wheels' ($found.Count -eq 0) ($found -join '; ')

# 2. the libraries load
$out = & $py -c "import polars, deltalake, pyarrow; print(polars.__version__, deltalake.__version__, pyarrow.__version__)" 2>&1
Step '2. the libraries load' ($LASTEXITCODE -eq 0) ("$out`n  an old CPU needs polars[rtcompat]; a blocked DLL is the machine's policy")
Write-Host "      versions: $out"

# 3. a table is written and read back
$table = Join-Path $scratch 'polars-check'
Remove-Item -Recurse -Force $table -ErrorAction SilentlyContinue
$w = & $py -c "import polars as pl, sys; pl.DataFrame({'id':['a']}).write_delta(sys.argv[1])" $table 2>&1
Step '3a. a Delta table is written' ($LASTEXITCODE -eq 0) "$w"
$r = & $py -c "import polars as pl, sys; print(pl.read_delta(sys.argv[1]).shape)" $table 2>&1
$ok = ($LASTEXITCODE -eq 0) -and ("$r" -match '\(1, 1\)')
Remove-Item -Recurse -Force $table -ErrorAction SilentlyContinue
Step '3b. the table is read back' $ok "$r"
Write-Host 'All three checks passed: build work may depend on the Polars wheels on this machine.'
exit 0
