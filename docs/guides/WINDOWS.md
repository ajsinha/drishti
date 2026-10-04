<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Drishti on Windows

Drishti runs on Windows 10 and 11 (and Windows Server 2019 or later) without WSL, Docker or Hadoop. This guide covers
the same ground as [QUICKSTART.md](QUICKSTART.md) in PowerShell: installing Java (21 or newer, 25 recommended) and Python, building the server
or copying a built one, loading the demo lake, starting the server and the console, what does not work on Windows,
and what to do when something goes wrong.

Why it works without Hadoop: the Delta Lake connector reads through its **native engine** (module
`drishti-deltalake`), which uses Java's own file access for local disk and the AWS SDK for S3. Hadoop's local file
system, and with it `winutils.exe`, is never used. See [DELTA_CONNECTOR.md › Engines](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop).

> **Status.** The native engine is tested on Linux on every build, including its Windows path rules (drive letters,
> backslashes, UNC shares) as unit tests. The `windows` GitHub Actions job builds, tests and starts the server on
> `windows-latest`. Report what you find on your machine.

## Contents

1. [Install Java and Python](#1-install-java-and-python)
2. [Get the server: build it, or copy it](#2-get-the-server-build-it-or-copy-it)
3. [Load the demo lake](#3-load-the-demo-lake)
4. [Start the server](#4-start-the-server)
5. [Start the console](#5-start-the-console)
6. [The Delta engine on Windows](#6-the-delta-engine-on-windows)
7. [What does not work on Windows](#7-what-does-not-work-on-windows)
8. [Troubleshooting](#8-troubleshooting)

## 1. Install Java and Python

| Tool | Version | Install with | Check with |
|---|---|---|---|
| JDK | **21 or newer; 25 recommended** (Temurin or any OpenJDK) | `winget install EclipseAdoptium.Temurin.25.JDK`, or the MSI from adoptium.net | `java -version` shows `openjdk version "25…` (or 21…) |
| Python | 3.11 or newer | `winget install Python.Python.3.13`, or python.org (tick *Add python.exe to PATH*) | `python --version` |
| uv | optional, recommended | `winget install astral-sh.uv` | `uv --version` |
| git | to build from source | `winget install Git.Git` | `git --version` |

The Java you run Drishti with does not have to be the default Java. The scripts take its folder as `-JavaHome`, or from
`DRISHTI_JAVA_HOME`:

```powershell
$env:DRISHTI_JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot'   # your JDK folder
```

If PowerShell refuses to run the scripts (*running scripts is disabled on this system*), allow local scripts for your
user once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

## 2. Get the server: build it, or copy it

**Build it** from a clone (the repository brings its own Maven, `mvnw.cmd`):

```powershell
git clone https://github.com/ajsinha/drishti.git
cd drishti
$env:JAVA_HOME = $env:DRISHTI_JAVA_HOME
.\mvnw.cmd -q package -DskipTests
dir drishti-server\target\*-exec.jar
```

You should see `drishti-server-1.15.0-exec.jar`. To also run the tests of the Delta engine and connector:
`.\mvnw.cmd -q verify -pl drishti-deltalake,plugins/drishti-plugin-delta -am` (tests that need Docker skip themselves).

**Or copy** a server built elsewhere (on Linux, or by CI): Java bytecode is the same on every system. Make a folder,
for example `C:\drishti`, holding:

| Copy | From | Why |
|---|---|---|
| `drishti-server-1.15.0-exec.jar` | `drishti-server\target\` | the server (all its libraries are inside) |
| `packs\` | the repository root | the domain packs (Sutras, mnemonics, samples) |
| `console\` | the repository root | only if you will run the console on this machine |
| `tools\windows\`, `tools\packgen\`, `tools\samplegen\` | the repository's `tools\` | the scripts, and the lake generator if you will build the demo lake here |
| `data\` | an existing installation, if any | the lake (`data\delta`), users (`data\identity`), feeds, reports; start without it and it is created |
| `application.local.yaml` | your own, if any | site settings; read from the folder the server starts in |

Without a clone, put the scripts in `C:\drishti\tools\windows\`: they work from the folder two levels above
themselves, and look for the jar in `drishti-server\target\` and then in that folder.

## 3. Load the demo lake

Live views work without it. Past business dates, **Compare** and searches over the history need the banking packs'
lake under `data\delta` (ten business days ending 30 September 2026):

```powershell
.\tools\windows\load-delta.ps1
```

With uv installed it fetches `deltalake`, `pyarrow` and `pyyaml` itself; without uv, install them first:
`python -m pip install deltalake pyarrow pyyaml`. A bigger trade book: `.\tools\windows\load-delta.ps1 -Trades 50000`
(about 20 MB a day per 10,000 trades). Another folder: `-Root D:\lakes\drishti`, and start the server with
`-LakeRoot D:\lakes\drishti`.

## 4. Start the server

```powershell
.\tools\windows\start-server.ps1 -Packs market-risk
```

The script checks that Java is 25, sets `DRISHTI_DELTA_ENGINE=native`, and starts the jar with
`-XX:+UseCompactObjectHeaders` (10–20% faster and 10% less heap on Java 25; the script leaves the flag out on Java 21, which refuses to start with it). It runs in the window until `Ctrl+C`.
Its options:

| Option | Default | Meaning |
|---|---|---|
| `-JavaHome` | `DRISHTI_JAVA_HOME`, then `JAVA_HOME`, then `java` on the PATH | the JDK folder (21 or newer) |
| `-Packs` | `DRISHTI_PACKS`, else the server's default (`finance`) | the packs to load, e.g. `market-risk,counterparty-risk` |
| `-Port` | `DRISHTI_PORT`, else `18480` | the server's port |
| `-LakeRoot` | `DRISHTI_DELTA_ROOT`, else `.\data\delta` | the Delta Lake |
| `-Engine` | `DRISHTI_DELTA_ENGINE`, else `native` | `native`, `hadoop` (needs `winutils.exe`) or `auto` |
| `-Heap` | `2g` | the heap limit (8 GB per million entities a day for a large lake) |
| `-Jar` | the newest `drishti-server-*-exec.jar` | another jar |
| `-Background` | off | start without a window: log in `data\logs\server.log`, process id in `data\server.pid` |
| `-Stop` | | stop a server started with `-Background` |

Check it from another PowerShell window:

```powershell
Invoke-RestMethod http://localhost:18480/actuator/health
(Invoke-RestMethod http://localhost:18480/api/v1/admin/health).sources | Select-Object name, health
```

You should see `UP`, then each lake connector (`trading-store`, `market-store`, …) as `UP (engine: native)`.

To run the jar by hand instead:

```powershell
$env:DRISHTI_PACKS = 'market-risk'
& "$env:DRISHTI_JAVA_HOME\bin\java.exe" -XX:+UseCompactObjectHeaders -Xmx2g -jar drishti-server\target\drishti-server-1.15.0-exec.jar
```

The native Delta engine is the default, so nothing else is needed.

## 5. Start the console

In a second PowerShell window:

```powershell
.\tools\windows\start-console.ps1
```

The first run makes `console\.venv` and installs the console's libraries (with uv if present, else
`python -m venv` and pip). Open **http://localhost:17480/t**. Options: `-Port` (17480), `-Backend`
(`http://127.0.0.1:18480`), `-Python` (the Python to build the environment with), `-Reinstall`.

From here the [QUICKSTART.md](QUICKSTART.md#7-your-first-eight-commands) steps 7 to 9 are the same.

## 6. The Delta engine on Windows

Every Delta connector has an `engine` setting; `DRISHTI_DELTA_ENGINE` sets it for those that do not set their own.

| Value | On Windows |
|---|---|
| `native` (the default) | works: local folders (`C:\lakes\drishti`, `\\fileserver\lakes`, `file:/C:/lakes`) and S3 |
| `auto` | the same as `native` on Windows (and `hadoop` on Linux) |
| `hadoop` | needs Hadoop's `winutils.exe` and `HADOOP_HOME`; use it only for a lake in Azure (`abfs://`) or Google Cloud Storage (`gs://`), which the native engine does not read |

A lake in S3 reads the same on Windows, with the connector's `s3.endpoint`, `s3.access-key`, `s3.secret-key`,
`s3.region` ([CONFIGURATION.md](../admin/CONFIGURATION.md#delta--delta-lake)). Paths with spaces
(`C:\Users\Jane Doe\drishti\data\delta`) are fine.

## 7. What does not work on Windows

| What | Why | Instead |
|---|---|---|
| The Iceberg connector (profile `iceberg`) | it reads through Hadoop's file systems, which need `winutils.exe` | keep the default Delta Lake; the Iceberg connector is idle unless the `iceberg` profile is set |
| `engine: hadoop` on a local lake | Hadoop's local file system needs `winutils.exe` | `engine: native` (the default) |
| Replacing a DuckDB file while the server reads it | Windows cannot rename a file over one that is open ([DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md)) | load into a new file and switch the connector's `path`, or stop the server first |
| The bash scripts in `tools\*.sh` (`load-delta.sh`, `load-postgres.sh`, `drill.sh`, …) | they need bash | `tools\windows\*.ps1` for the server, the console and the Delta lake; for the others, the Python tools they call run as they are (`python tools\…`), or use Git Bash or WSL |
| `docker compose` deployments (`deploy\`) | Linux containers | Docker Desktop runs them as on Linux; the jar runs natively as above |

The Aerospike, PostgreSQL, Kafka, RabbitMQ, ActiveMQ, Redis, MongoDB, S3 and REST connectors are plain network clients
and work on Windows; their servers are yours to run.

## 8. Troubleshooting

| You see | Cause | Do this |
|---|---|---|
| `Drishti runs on Java 21 or newer (25 recommended); … says: … version "17…` | the script found an older Java | pass `-JavaHome` or set `DRISHTI_JAVA_HOME` to a JDK 21 or newer folder |
| `running scripts is disabled on this system` | PowerShell's execution policy | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| `HADOOP_HOME and hadoop.home.dir are unset`, `Could not locate executable winutils.exe` | a Delta connector on `engine: hadoop` | remove `engine: hadoop` and unset `DRISHTI_DELTA_ENGINE` (or set it to `native`) |
| Health: `DOWN: cannot reach C:\…\data\delta\trading (engine: native)` | no lake there | run `load-delta.ps1`, or start with `-LakeRoot` pointing at the lake |
| Health: `DOWN: no Delta tables under …` | the folder exists but has no `<kind>\_delta_log` | check the root and the pack's `domain` folder (`trading`, `market`, …) |
| `the native Delta engine reads local disk and S3 …, not abfs://…` | an Azure or GCS lake with the native engine | set the connector's `engine: hadoop` (and install `winutils.exe`) |
| `Port 18480 was already in use` | another server | `-Port 18481`, and the console with `-Backend http://127.0.0.1:18481` |
| The console says `DRS-5003 backend unreachable` | the server is not up yet, or on another port | wait for `Started DrishtiApplication`; check `-Backend` |
| `load-delta.ps1`: `No module named 'deltalake'` | no uv, and the libraries are not installed | `python -m pip install deltalake pyarrow pyyaml`, or install uv |
| Windows Defender slows the first start | it scans the jar's native libraries (RocksDB, DuckDB, SQLite, Snappy) as they unpack | let the first start finish; later starts are faster |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
