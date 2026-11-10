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

# Starts a Drishti server on Windows, alone or as a node of a Drishti*net cluster: Java 21 or newer, the native Delta engine (no Hadoop,
# no winutils.exe). The same options as bin/start-server.sh (Linux and macOS), as --name value or -Name value; bin\start-server.cmd runs
# it from cmd or a double-click. Every option: --help, bin\README.md and docs\DEPLOYMENT_AND_QA_GUIDE.md section 6.
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$usage = @'
usage: bin\start-server.ps1 [options]        (or bin\start-server.cmd [options])

Starts the Drishti server: finds Java 21+, the newest drishti-server-*-exec.jar, sets the native Delta engine, the temporary folder,
the native memory settings and the logs under the data folder, waits for /actuator/health and prints the address other machines open.

  --port N            the server's port (default DRISHTI_PORT, else 18480)
  --heap SIZE         the heap limit, -Xmx (default 2g; 4g with --demo)
  --jar FILE          the server jar (default the newest drishti-server\target\drishti-server-*-exec.jar, then drishti-server*.jar here)
  --java-home DIR     the JDK (default DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH)
  --packs LIST        the packs to load (DRISHTI_PACKS; default the server's own, or the demo's ten with --demo)
  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>\data): run\, logs\, tmp\ and dumps\ are under it
  --env-file FILE     KEY=VALUE lines put into the server's environment (secrets stay off the command line; values never printed)
  --demo              the offline demo on the lakes, with DEVELOPMENT secrets and the drishti-dev-admin account: NOT FOR PRODUCTION
                      (-DemoLakes, the old name, is the same)
  --engine NAME       the Delta engine: native, hadoop or auto (default native on Windows; hadoop needs winutils.exe)
  --lake-root DIR     the Delta lake root (DRISHTI_DELTA_ROOT)
  --iceberg           serve the stores from Iceberg tables (copies config\connectors.examples\iceberg\*.yaml into config\connectors)
  --iceberg-root DIR  the Iceberg tables' root (DRISHTI_ICEBERG_ROOT, default data\iceberg), with --iceberg
  --java-opt FLAG     one more JVM flag (repeatable)
  --server-arg ARG    one more server argument, e.g. --drishti.x=y (repeatable)
  --workdir DIR       the server's working folder (default <drishti>)
  --background        detach: log in <data>\logs\server[-<node>].log, pid in <data>\run\server[-<node>].pid
  --wait SECONDS      how long to wait for the health answer (default 300)
  --distribution NAME risk, finance, genomics, all (the default) or a file path (DRISHTI_DISTRIBUTION); docs\architecture\DISTRIBUTIONS.md
  --stop              the same as bin\stop-server.ps1 (kept for the old tools\windows\start-server.ps1 -Stop)

 A cluster node (docs\guides\CLUSTER_GUIDE.md):
  --node NAME         the node's id; its pid, log and temp folder are named after it, so several run on one machine
  --node-dir DIR      the node's Drishti*net folder (node.yaml, gossip.yaml, peers\); default <data>\nodes\<node>\net, made when missing
  --node-data DIR     the node's own data folder (the server's DRISHTI_DATA_DIR); default <data>. Several nodes on one machine each
                      need their own: e.g. <data>\nodes\<node>\data
  --seed HOST:PORT    a member to join through (repeatable, or comma-separated): written to <node-dir>\peers\ when not there yet
  --advertise IP      the address the node advertises (default this machine's real LAN address; never 127.0.0.1, localhost, 0.0.0.0)

  -h, --help          this text

Environment: DRISHTI_JAVA_HOME, JAVA_HOME, DRISHTI_PORT, DRISHTI_DATA_DIR, DRISHTI_PACKS, DRISHTI_NET_SECRET (a cluster node),
DRISHTI_TOKEN_SECRET, DRISHTI_PYTHON. Exit codes: 0 up (or, in the foreground, stopped cleanly);
1 failed to start; 2 a wrong option; 3 already running or the port is in use.

examples:
  bin\start-server.cmd --demo --background
  bin\start-server.ps1 -EnvFile C:\drishti\server.env -Heap 8g -Background
  bin\start-server.ps1 --node drishti-a --port 18480 --env-file C:\drishti\cluster.env --background
  bin\start-server.ps1 --node drishti-b --port 18480 --seed 192.168.1.20:18480 --env-file C:\drishti\cluster.env --background
'@

$o = Read-Options $args @{ port = 'value'; heap = 'value'; jar = 'value'; javahome = 'value'; packs = 'value'; datadir = 'value'; envfile = 'value'
    demo = 'switch'; demolakes = 'switch'; engine = 'value'; lakeroot = 'value'; iceberg = 'switch'; icebergroot = 'value'; javaopt = 'list'
    serverarg = 'list'; workdir = 'value'; background = 'switch'; wait = 'value'; stop = 'switch'; node = 'value'; nodedir = 'value'
    nodedata = 'value'; seed = 'list'; advertise = 'value'; distribution = 'value' }
if ($o['help']) { Write-Host $usage; exit 0 }
$node = Opt $o 'node'
if ($o['stop']) {
    $stopArgs = @()
    if ($node) { $stopArgs += @('--node', $node) }
    if ($o['datadir']) { $stopArgs += @('--data-dir', $o['datadir']) }
    & (Join-Path $PSScriptRoot 'stop-server.ps1') @stopArgs
    exit $LASTEXITCODE
}
$demo = [bool]($o['demo'] -or $o['demolakes'])
$engine = Opt $o 'engine' 'native'
if ($engine -notin @('native', 'hadoop', 'auto')) { Fail '--engine is native, hadoop or auto' 2 }
if ($node -and $node -notmatch '^[a-z0-9][a-z0-9-]*$') { Fail "--node ${node}: lower-case letters, digits and dashes" 2 }
if ($o['advertise'] -and (Test-LoopbackOrWildcard $o['advertise'])) { Fail "--advertise $($o['advertise']) is a loopback or wildcard address: members advertise an address other machines can call" 2 }
if (-not $node -and ($o['nodedir'] -or $o['nodedata'] -or $o['seed'] -or $o['advertise'])) {
    Fail '--node-dir, --node-data, --seed and --advertise belong to a cluster node: give --node <name> too' 2
}

if ($o['envfile']) { Import-EnvFile $o['envfile'] }
if ($o['distribution']) { $env:DRISHTI_DISTRIBUTION = $o['distribution'] }   # the server reads it as drishti.distribution
$data = Get-DataDir (Opt $o 'datadir')
$serverData = Get-DataDir (Opt $o 'nodedata' $data)
$env:DRISHTI_DATA_DIR = $serverData
$port = [int](Opt $o 'port' $(if ($env:DRISHTI_PORT) { $env:DRISHTI_PORT } else { $script:DefaultServerPort }))
$stem = if ($node) { "server-$node" } else { 'server' }
$label = if ($node) { $node } else { 'server' }
$pidFile = Join-Path $data "run\$stem.pid"
$log = Join-Path $data "logs\$stem.log"
$tmp = Join-Path $data "tmp\$stem"
$wait = [int](Opt $o 'wait' 300)
$workdir = (Resolve-Path (Opt $o 'workdir' $script:DrishtiRoot)).Path

$existing = Get-PidOf $pidFile
if (Test-Ours $existing $script:ServerMark) {
    Write-Host "${label}: already running (pid $existing, port $(Get-PidField $pidFile 'port')); bin\stop-server.ps1$(if ($node) { " --node $node" }) stops it"
    exit 3
}
if (Test-Path $pidFile) { Remove-Item $pidFile -Force; Write-Host "${label}: stale $pidFile removed (pid $existing is not running)" }
# two servers never share a data folder (its identity database, saved members and inbox are one server's)
foreach ($f in @(Get-ChildItem -Path (Join-Path $data 'run') -Filter 'server*.pid' -ErrorAction SilentlyContinue)) {
    if ($f.FullName -ne $pidFile -and (Get-PidField $f.FullName 'data') -eq $serverData -and (Test-Ours (Get-PidOf $f.FullName) $script:ServerMark)) {
        Fail "$($f.BaseName) (pid $(Get-PidOf $f.FullName)) already uses the data folder ${serverData}: give this node its own with --node-data" 3
    }
}
if (Test-PortInUse $port) { Fail "port $port is in use by another process: stop it, or pick another with --port" 3 }

# Java 21 or newer
$javaHome = Opt $o 'javahome' $(if ($env:DRISHTI_JAVA_HOME) { $env:DRISHTI_JAVA_HOME } else { $env:JAVA_HOME })
$java = if ($javaHome) { Join-Path $javaHome 'bin\java.exe' } else { 'java' }
if ($javaHome -and -not (Test-Path $java)) { Fail "no java.exe under $javaHome\bin: set --java-home (or DRISHTI_JAVA_HOME) to a JDK 21 folder" }
if (-not $javaHome -and -not (Get-Command java -ErrorAction SilentlyContinue)) { Fail 'no Java: install Temurin JDK 21 and set DRISHTI_JAVA_HOME or pass --java-home' }
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'                 # java -version writes to stderr
$version = (& $java -version 2>&1 | Out-String)
$ErrorActionPreference = $previous
$major = if ($version -match 'version "(\d+)') { [int]$Matches[1] } else { 0 }
if ($major -lt 21) { Fail "Drishti runs on Java 21 or newer; $java says: $version" }

# the jar
$jar = Opt $o 'jar'
if (-not $jar) {
    $candidates = @(Get-ChildItem -Path (Join-Path $script:DrishtiRoot 'drishti-server\target') -Filter 'drishti-server-*-exec.jar' -ErrorAction SilentlyContinue) +
                  @(Get-ChildItem -Path $script:DrishtiRoot -Filter 'drishti-server*.jar' -ErrorAction SilentlyContinue)
    if ($candidates.Count -eq 0) { Fail 'no server jar: build it (.\mvnw.cmd -q package -DskipTests) or copy drishti-server-<version>-exec.jar here, or pass --jar' }
    $jar = ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not (Test-Path $jar)) { Fail "no jar $jar" }
if (-not (Test-Path (Join-Path $script:DrishtiRoot 'config\packs'))) { Warn "no config\packs\ in $($script:DrishtiRoot): the server starts without domain packs" }

# the demo: what the IntelliJ run configuration 'Drishti server (demo lakes)' sets; the development secrets only where none is set
$packs = Opt $o 'packs'
$heap = Opt $o 'heap'
$devSecrets = @()
if ($demo) {
    if (-not $packs) { $packs = $env:DRISHTI_PACKS }
    if (-not $packs) { $packs = 'core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds' }
    if (-not $heap) { $heap = '4g' }
    $env:DRISHTI_DEMO_ENABLED = 'false'               # the lakes are the only data: no built-in samples
    $env:DRISHTI_VAR_SYSTEM_BASKETS_SEED = 'true'    # the demo's system baskets, imported once into an empty store
    $env:DRISHTI_OFFLINE = 'true'                     # quotes and news from the committed snapshot; no website is contacted
    $env:DRISHTI_SECURITY_ENABLED = 'true'
    $env:DRISHTI_SEED_ADMIN = 'true'
    $env:DRISHTI_STUDIO_SAVE = 'true'
    if (-not $env:DRISHTI_LENSES) { $env:DRISHTI_LENSES = 'risk,world,market,trading' }
    if (-not $env:DRISHTI_EXTERNAL_LIB) { $env:DRISHTI_EXTERNAL_LIB = Join-Path $script:DrishtiRoot 'external_lib' }
    if (-not $env:DRISHTI_SEED_USERNAME) { $env:DRISHTI_SEED_USERNAME = 'drishti-dev-admin' }
    $devSecrets += Set-DevSecret 'DRISHTI_SEED_PASSWORD' 'drishti-dev-admin123'
    $devSecrets += Set-DevSecret 'DRISHTI_TOKEN_SECRET' 'drishti-dev-only-token-secret-change-me-0123456789'
    $devSecrets += Set-DevSecret 'DRISHTI_SESSION_SECRET' 'drishti-dev-only-session-secret-change-me-0123456789'
    if ($node) {
        $devSecrets += Set-DevSecret 'DRISHTI_NET_SECRET' 'drishti-dev-only-cluster-key-change-me-0123456789'
    } else {
        if (-not $env:DRISHTI_DATALOAD_ENABLED) { $env:DRISHTI_DATALOAD_ENABLED = 'true' }
        if (-not $env:DRISHTI_DATALOAD_OWNER) { $env:DRISHTI_DATALOAD_OWNER = 'true' }
    }
}
$devSecrets = @($devSecrets | Where-Object { $_ })
if (-not $heap) { $heap = '2g' }
if ($packs) { $env:DRISHTI_PACKS = $packs }
$env:DRISHTI_DELTA_ENGINE = $engine
if ($o['lakeroot']) { $env:DRISHTI_DELTA_ROOT = $o['lakeroot'] }
if ($o['iceberg']) {
    # Iceberg without Hadoop: the native engine (drishti-iceberg) needs no winutils.exe
    $env:DRISHTI_ICEBERG_ENGINE = 'native'
    $env:DRISHTI_ICEBERG_ROOT = Opt $o 'icebergroot' $(if ($env:DRISHTI_ICEBERG_ROOT) { $env:DRISHTI_ICEBERG_ROOT } else { Join-Path $script:DrishtiRoot 'data\iceberg' })
    $connectors = Join-Path $script:DrishtiRoot 'config\connectors'
    New-Item -ItemType Directory -Force -Path $connectors | Out-Null
    foreach ($f in Get-ChildItem -Path (Join-Path $script:DrishtiRoot 'config\connectors.examples\iceberg') -Filter '*.yaml') {
        $to = Join-Path $connectors $f.Name
        if (-not (Test-Path $to)) { Copy-Item $f.FullName $to; Write-Host "connector file $($f.Name) copied into config\connectors" }
    }
}
$env:DRISHTI_PORT = "$port"

# a cluster node: its Drishti*net folder, its seeds, the address it advertises
$header = (Get-Content -LiteralPath $PSCommandPath -TotalCount 13) -join "`r`n"
$urlHost = Get-LanIp
if ($node) {
    $nodeDir = Opt $o 'nodedir' (Join-Path $data "nodes\$node\net")
    New-Item -ItemType Directory -Force -Path (Join-Path $nodeDir 'peers') | Out-Null
    $nodeDir = (Resolve-Path $nodeDir).Path
    $nodeYaml = Join-Path $nodeDir 'node.yaml'
    if (-not (Test-Path $nodeYaml)) {
        Set-Content -LiteralPath $nodeYaml -Encoding UTF8 -Value @($header,
            "# $node, made by bin\start-server.ps1 (docs\guides\CLUSTER_GUIDE.md). References only: the key and the address come from the environment.",
            'enabled: true', "node-id: $node", 'advertise-url: http://${DRISHTI_ADVERTISE_HOST}:${DRISHTI_PORT}     # this machine''s real address (bin\start-server sets both)',
            'secret: ${DRISHTI_NET_SECRET}')
        Write-Host "${node}: made $nodeYaml"
    }
    foreach ($seed in @($o['seed'] | Where-Object { $_ } | ForEach-Object { $_ -split ',' } | Where-Object { $_ })) {
        $s = ($seed -replace '^https?://', '').TrimEnd('/')
        if ($s -notmatch ':') { $s = "${s}:$($script:DefaultServerPort)" }
        if (Test-LoopbackOrWildcard ($s -replace ':\d+$', '')) { Fail "--seed ${s}: a seed is named by its real address, never a loopback or wildcard one" 2 }
        $known = @(Get-ChildItem -Path (Join-Path $nodeDir 'peers') -Filter '*.yaml' | Where-Object { (Get-Content $_.FullName -Raw) -match [regex]::Escape($s) })
        if ($known.Count -eq 0) {
            $file = Join-Path $nodeDir ("peers\seed-" + ($s -replace '[:.]', '-') + '.yaml')
            Set-Content -LiteralPath $file -Encoding UTF8 -Value @($header, "# A seed of $node (bin\start-server.ps1 --seed).", "url: http://$s")
            Write-Host "${node}: seed $s written to $file"
        }
    }
    $urlHost = Get-AdvertiseHost (Opt $o 'advertise')
    $env:DRISHTI_ADVERTISE_HOST = $urlHost
    $env:DRISHTI_NET_DIR = $nodeDir
    if (-not $env:DRISHTI_NET_SECRET) { Fail 'a cluster node needs the cluster key DRISHTI_NET_SECRET (32+ bytes, the same on every node): put it in the --env-file' }
}
if (-not $urlHost) { $urlHost = 'localhost' }

# the JVM: temp folder, heap, native memory (CLUSTER_GUIDE.md, Resident memory; glibc's arena setting is not for Windows), a heap dump
# and a rotated GC log (OPERATIONS_GUIDE.md). The GC log's path is relative to the working folder: -Xlog reads a colon as a separator,
# so a drive letter cannot appear in it; with the data folder on another drive there is no GC log.
$dumps = Join-Path $data "dumps\$label"
New-Item -ItemType Directory -Force -Path $tmp, (Join-Path $data 'logs'), (Join-Path $data 'run'), $dumps | Out-Null
$env:DRISHTI_TMP_DIR = $tmp
$javaArgs = @("-Djava.io.tmpdir=$tmp", "-Xmx$heap", '-XX:MaxDirectMemorySize=256m', '-XX:TrimNativeHeapInterval=60000',
    '-Djdk.nio.maxCachedBufferSize=262144', '-XX:NativeMemoryTracking=summary', '-XX:+HeapDumpOnOutOfMemoryError', "-XX:HeapDumpPath=$dumps")
Push-Location $workdir
try { $gcRel = (Resolve-Path -Relative (Join-Path $data 'logs')) } catch { $gcRel = '' }
Pop-Location
if ($gcRel -and $gcRel -notmatch ':') { $javaArgs += ('-Xlog:gc*:file=' + ($gcRel -replace '\\', '/') + "/gc-$stem-%p.log:time,uptime:filecount=5,filesize=20m") }
else { Warn 'the data folder is on another drive than the working folder: no GC log' }
if ($major -ge 25) { $javaArgs = @('-XX:+UseCompactObjectHeaders') + $javaArgs }
# direct memory beyond 256 MB: the in-memory lake's stores and Arrow's budget, from the one place that sizes them (a later flag wins)
$py = Get-ToolsPython
if ($py) {
    Push-Location $workdir
    try {
        $extra = 0; $arrow = 0
        [void][int]::TryParse(("$(& $py (Join-Path $script:DrishtiRoot 'tools\lake\memory_caps.py') --extra-only 2>$null)").Trim(), [ref]$extra)
        [void][int]::TryParse(("$(& $py (Join-Path $script:DrishtiRoot 'tools\lake\memory_caps.py') --arrow-only 2>$null)").Trim(), [ref]$arrow)
        if ($extra -gt 0) { $javaArgs += "-XX:MaxDirectMemorySize=$(256 + $extra)m" }
        if ($arrow -gt 0) { $javaArgs += "-Ddrishti.arrow.max-bytes=$([int64]$arrow * 1048576)" }
    } catch { } finally { Pop-Location }
}
$javaArgs += @($o['javaopt'] | Where-Object { $_ })
$javaArgs += @('-jar', $jar, "--server.port=$port")
if ($o['background']) { $javaArgs += @("--drishti.logging.file=$log", '--logging.threshold.console=OFF') }
$javaArgs += @($o['serverarg'] | Where-Object { $_ }) + @($o['--'] | Where-Object { $_ })

$url = "http://${urlHost}:$port"
$health = "http://127.0.0.1:$port/actuator/health"
$packsShown = if ($env:DRISHTI_PACKS) { $env:DRISHTI_PACKS } else { 'the default' }
Write-Host "Drishti $(if ($node) { "node $node " })server: $(Split-Path $jar -Leaf) on Java $major, port $port, heap $heap, Delta engine $engine, packs $packsShown, data $serverData"
if ($node) { Write-Host "  Drishti*net folder $nodeDir, advertising $url" }
if ($devSecrets.Count -gt 0) { Warn "DEVELOPMENT secrets in use (NOT FOR PRODUCTION): $($devSecrets -join ' '). Put your own in an --env-file for anything shared." }
$fields = @{ port = $port; url = $url; health = $health; data = $serverData; distribution = $(if ($env:DRISHTI_DISTRIBUTION) { $env:DRISHTI_DISTRIBUTION } else { 'all' }) }

if ($o['background']) {
    $p = Start-Process -FilePath $java -ArgumentList (Join-ProcessArgs $javaArgs) -WorkingDirectory $workdir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $data "logs\$stem.out.log") -RedirectStandardError (Join-Path $data "logs\$stem.err.log")
    Write-PidFile $pidFile $p.Id $fields
    Write-Host "${label}: started (pid $($p.Id)); log $log; waiting up to $wait s for $health"
    if (Wait-Health $health $wait $p) { Write-Host "${label}: up on $url"; exit 0 }
    if ($p.HasExited) {
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        Write-Host "${label}: stopped while starting: see $log and $(Join-Path $data "logs\$stem.err.log")"
    } else {
        Write-Host "${label}: still starting after $wait s (pid $($p.Id)): see $log; bin\status.ps1 shows when it answers"
    }
    exit 1
}
# the foreground: Ctrl+C stops it; the pid file is there while it runs, so bin\status.ps1 and bin\stop-server.ps1 see it
$p = Start-Process -FilePath $java -ArgumentList (Join-ProcessArgs $javaArgs) -WorkingDirectory $workdir -PassThru -NoNewWindow
Write-PidFile $pidFile $p.Id $fields
try {
    if (Wait-Health $health $wait $p) { Write-Host "${label}: up on $url" }
    $p.WaitForExit()
    exit $p.ExitCode
} finally {
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}
