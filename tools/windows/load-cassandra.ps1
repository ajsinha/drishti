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
  Loads the banking packs' data into Apache Cassandra on Windows (the PowerShell form of tools/load-cassandra.sh).

.DESCRIPTION
  Writes the sample documents (ten business days) and, with -Trades, a book of that many trades a day over -Days days, in
  the layout the Cassandra connector reads (docs/connectors/CASSANDRA_CONNECTOR.md): a keyspace per data domain, a table
  per kind with a partition per business day, generation and bucket. Each day is loaded under a new generation and appears
  whole. -Create creates the tables (without it the loader prints the CQL for an administrator); -CreateKeyspace 1 makes a
  development keyspace. Other loader options (--ttl-days, --keep-days, --setting tls.enabled=true …) go in -LoaderArgs.
  Needs a JDK 21 (DRISHTI_JAVA_HOME or JAVA_HOME) and uv (or a Python with pyyaml, deltalake and pyarrow).

.EXAMPLE
  .\tools\windows\load-cassandra.ps1 -Create -CreateKeyspace 1
.EXAMPLE
  .\tools\windows\load-cassandra.ps1 -ContactPoints cass1:9042,cass2:9042 -Datacenter dc1 -Trades 10000 -Days 3 -LoaderArgs '--ttl-days','90'
#>
param(
    # host:port list of the cluster's nodes.
    [string]$ContactPoints = 'localhost:9042',
    # The data centre the driver prefers (datacenter1 on a single node).
    [string]$Datacenter = 'datacenter1',
    # Create the tables (and add promoted columns).
    [switch]$Create,
    # Create a development keyspace with this replication factor (0: the administrator's keyspace must exist).
    [int]$CreateKeyspace = 0,
    # A trade book of this many trades a day (0: only the samples).
    [int]$Trades = 0,
    # The business days the trade book covers.
    [int]$Days = 3,
    # More loader options, passed unchanged.
    [string[]]$LoaderArgs = @(),
    # The JDK folder; default DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH.
    [string]$JavaHome = $(if ($env:DRISHTI_JAVA_HOME) { $env:DRISHTI_JAVA_HOME } else { $env:JAVA_HOME }),
    # The Python to use when uv is not installed.
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo
$data = if ($env:DRISHTI_DATA_DIR) { $env:DRISHTI_DATA_DIR } else { Join-Path $repo 'data' }
$tmpDir = if ($env:DRISHTI_TMP_DIR) { $env:DRISHTI_TMP_DIR } else { Join-Path $data 'tmp\server' }
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java' }

function Invoke-Tool([string[]]$toolArgs) {
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        & uv run -q --with deltalake --with pyarrow --with pyyaml python @toolArgs
    } else {
        & $Python @toolArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "python $($toolArgs -join ' ') failed. Without uv, install the libraries first: $Python -m pip install deltalake pyarrow pyyaml"
    }
}

# the loader's input: the samples, then the book, one JSON line per entity per day
$jsonl = Join-Path $data 'cassandra.jsonl'
Invoke-Tool @('tools/packgen/banking/make_data.py', '--jsonl', $jsonl)
if ($Trades -gt 0) {
    $book = Join-Path $data 'cassandra-book.jsonl'
    Invoke-Tool @('tools/samplegen/bulk_trades.py', '--trades', "$Trades", '--days', "$Days", '--jsonl', $book)
    Get-Content $book | Add-Content $jsonl
    Remove-Item $book
}

& .\mvnw.cmd -q -o install -DskipTests -pl plugins/drishti-plugin-cassandra -am
if ($LASTEXITCODE -ne 0) { throw 'the plugin did not build' }
$cpFile = Join-Path $tmpDir 'cassandra-classpath.txt'
& .\mvnw.cmd -q -o dependency:build-classpath -pl plugins/drishti-plugin-cassandra "-Dmdep.outputFile=$cpFile"
if ($LASTEXITCODE -ne 0) { throw 'the class path could not be resolved' }
$cp = 'plugins\drishti-plugin-cassandra\target\classes;' + (Get-Content $cpFile -Raw).Trim()
Remove-Item $cpFile

$loaderArgs = @($jsonl, $ContactPoints, $Datacenter)
if ($Create) { $loaderArgs += '--create' }
if ($CreateKeyspace -gt 0) { $loaderArgs += @('--create-keyspace', "$CreateKeyspace") }
$loaderArgs += $LoaderArgs
& $java "-Djava.io.tmpdir=$tmpDir" "-Djdk.nio.maxCachedBufferSize=262144" -cp $cp com.ash.drishti.plugin.cassandra.CassandraLoader @loaderArgs
if ($LASTEXITCODE -ne 0) { throw "the load failed (exit $LASTEXITCODE)" }
Remove-Item $jsonl
