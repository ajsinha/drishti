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
  Loads the banking packs' data into Apache Iceberg tables on Windows (the PowerShell form of tools/load-iceberg.sh).

.DESCRIPTION
  Writes the sample documents (ten business days) as Iceberg tables under -Root (default data\iceberg), one per kind,
  in the layout the Iceberg connector reads; with -Trades also a book of that many trades over -Days days, in the same
  load (the samples and the book as one input, so each day holds both). The loader runs on the native engine (drishti-iceberg): no Hadoop, so
  no winutils.exe and no HADOOP_HOME. Its sort spills to -SpillDir, by default under the data folder (data\tmp\iceberg),
  never the system temp folder. Needs Java 21 or newer (JAVA_HOME or java on the PATH) and uv, or a Python with pyyaml
  (and deltalake and pyarrow for -Trades).

.EXAMPLE
  .\tools\windows\load-iceberg.ps1
.EXAMPLE
  .\tools\windows\load-iceberg.ps1 -Root D:\lakes\iceberg -Trades 50000 -Days 3 -KeepDays 10
.EXAMPLE
  .\tools\windows\load-iceberg.ps1 -Root s3://risk-lake/iceberg -Set 's3.endpoint=http://localhost:9000','s3.access-key=K','s3.secret-key=S'
#>
param(
    # The tables' root folder (a Windows path, or s3://bucket/prefix).
    [string]$Root = 'data\iceberg',
    # A trade book of this many trades a day (0: only the samples).
    [int]$Trades = 0,
    # The business days the trade book covers.
    [int]$Days = 3,
    # Remove each loaded table's business days older than its newest N (0: keep everything).
    [int]$KeepDays = 0,
    # The engine that writes: native (no Hadoop, the one that works on Windows), library or auto.
    [string]$Engine = 'native',
    # Where the loader's sort spills its runs (about the size of the loaded JSON).
    [string]$SpillDir = '',
    # Any connector setting, key=value (s3.endpoint, s3.access-key, catalog settings for a REST catalog).
    [string[]]$Set = @(),
    # The Java heap of the loader.
    [string]$Heap = '4g',
    # The Python to use when uv is not installed.
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo

$data = if ($env:DRISHTI_DATA_DIR) { $env:DRISHTI_DATA_DIR } else { 'data' }
if (-not $SpillDir) { $SpillDir = Join-Path $data 'tmp\iceberg' }
$jtmp = Join-Path $data 'tmp\server'
New-Item -ItemType Directory -Force -Path $SpillDir, $jtmp | Out-Null

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

function Invoke-Tool([string[]]$withs, [string[]]$toolArgs) {
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        $uvArgs = @('run', '-q')
        foreach ($w in $withs) { $uvArgs += @('--with', $w) }
        & uv @uvArgs python @toolArgs
    } else {
        & $Python @toolArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "python $($toolArgs -join ' ') failed. Without uv, install the libraries first: $Python -m pip install $($withs -join ' ')"
    }
}

# the loader's input: the samples, then the book, in one file, so each business day of a table is written once and holds both
# (a second load of the same days would replace them: QA2-05)
$jsonl = Join-Path $data 'banking.jsonl'
Invoke-Tool @('pyyaml') @('tools/packgen/banking/make_data.py', '--jsonl', $jsonl)
if ($Trades -gt 0) {
    $book = Join-Path $data 'iceberg-book.jsonl'
    Invoke-Tool @('deltalake', 'pyarrow', 'pyyaml') @('tools/samplegen/bulk_trades.py', '--trades', "$Trades", '--days', "$Days", '--jsonl', $book)
    Get-Content $book | Add-Content $jsonl
    Remove-Item $book
}

& .\mvnw.cmd -q -o install -DskipTests -pl plugins/drishti-plugin-iceberg -am
if ($LASTEXITCODE -ne 0) { throw 'building the Iceberg plugin failed' }
$cpFile = Join-Path $jtmp 'iceberg-classpath.txt'
& .\mvnw.cmd -q -o dependency:build-classpath -pl plugins/drishti-plugin-iceberg "-Dmdep.outputFile=$cpFile"
if ($LASTEXITCODE -ne 0) { throw 'resolving the Iceberg plugin class path failed' }
$cp = 'plugins\drishti-plugin-iceberg\target\classes;' + (Get-Content $cpFile -Raw).Trim()
Remove-Item $cpFile

$loaderArgs = @('--engine', $Engine, '--spill-dir', $SpillDir)
if ($KeepDays -gt 0) { $loaderArgs += @('--keep-days', "$KeepDays") }
foreach ($kv in $Set) { $loaderArgs += @('--set', $kv) }
$jvm = @("-Djava.io.tmpdir=$jtmp", "-Xmx$Heap", '-Djdk.nio.maxCachedBufferSize=262144', '-cp', $cp, 'com.ash.drishti.plugin.iceberg.IcebergLoader')

& $java @jvm $jsonl $Root @loaderArgs
if ($LASTEXITCODE -ne 0) {
    # the loader said why in one line (which bucket, which endpoint, what to check: QA3B-12); no PowerShell error record on top of it
    $code = $LASTEXITCODE
    Remove-Item $jsonl -ErrorAction SilentlyContinue
    [Console]::Error.WriteLine('load-iceberg: the load did not finish: see the line above')
    exit $code
}
Remove-Item $jsonl
Write-Host "Iceberg tables ready under $Root (engine: $Engine)"
