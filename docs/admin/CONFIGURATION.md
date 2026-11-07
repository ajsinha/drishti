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
# Configuration

This is the one document for configuring Drishti, and the only one: every setting of every kind (the server, the console, every
connector plugin, packs, editions, the content store, BI runs, write-back, compute, Drishti*net, the UI kit, the AI provider and MCP
server files), the environment variables that feed them, and how the layers combine. Other documents explain a feature and link
here for its settings. The console shows the same document at **Help → Configuration** (`/help/configuration`), searchable by key,
environment variable or words. **Admin → Configuration** (`/admin/configuration`) shows every setting by tier (everyday, admin,
expert: the *Tier* column below) with the value in effect on the server and the console, where it came from, and a change proposed
for a second administrator to approve ([OPERATIONS.md, The configuration editor](OPERATIONS.md#the-configuration-editor)).

The first half is written by hand: how configuration works, placeholders, value formats, recipes, and notes area by area with
examples. The second half, [Every setting](#every-setting), is generated from the files themselves by `tools/settings_catalogue.py`
(each key's meaning is its own comment in the file), in one row shape everywhere: key, type, default, environment variable, what it
applies to, meaning, restart or live, and since. The drill runs `python3 tools/settings_catalogue.py --check`, which fails when a key
has no meaning, a default is missing, an environment variable is not in the index, or this document is out of date. To change a
meaning, change the comment in the source file and run the tool.

If you only want to get something running, read [How configuration works](#how-configuration-works) and
[Common recipes](#common-recipes) first, then look keys up in [Every setting](#every-setting) as you need them.

Contents

1. [How configuration works](#how-configuration-works)
2. [Placeholders: `${NAME:default}`](#placeholders-namedefault)
3. [Value formats](#value-formats)
4. [Common recipes](#common-recipes)
5. [Server notes, area by area](#server-reference) (`drishti.*`)
6. [Connector settings, plugin by plugin](#connector-settings-plugin-by-plugin)
7. [Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles)
8. [Spring settings you may need](#spring-settings-you-may-need)
9. [Console notes](#console-reference)
10. [Every setting](#every-setting) (generated): [the server](#the-server-applicationyaml), [the console](#the-console-applicationyaml),
    [the console's other files](#the-consoles-other-files), [connectors](#connectors-every-plugins-settings), [packs](#packs-packyaml),
    [editions](#editions-configeditions), [the content store](#the-content-store-configstorestoreyaml), [BI runs](#bi-runs-configrunsrunsyaml),
    [write-back](#write-back-configedit), [compute](#compute-workers-configcomputecomputeyaml), [Drishti*net](#drishtinet-confignet),
    [the UI kit](#the-ui-kit-configui-kitbrandyaml), [AI and MCP](#ai-providers-and-mcp-servers-configai-configmcp) and the
    [environment variable index](#environment-variable-index)

---

## How configuration works

Drishti has two programs and each has its own configuration:

| Program | Bundled file | Local override (git-ignored) | Env-var form | Argument form |
|---|---|---|---|---|
| Server (Java, port 18480) | `drishti-server/src/main/resources/application.yaml` (inside the jar) | `./application.local.yaml` in the server's **working directory** | `DRISHTI_SOURCES_FETCHTIMEOUT=3s` | `--drishti.sources.fetch-timeout=3s` |
| Console (Python, port 17480) | `drishti-console/config/application.yaml` | `drishti-console/config/application.local.yaml` | `DRISHTI_CONSOLE__BACKEND__TIMEOUT_SECONDS=30` | `--backend.timeout_seconds=30` |

Secrets never go in a tracked file. Put them in environment variables (or in the git-ignored local file on a
developer machine).

### Precedence

Lowest to highest; a later layer wins:

```
pack.yaml contributions  <  application.yaml  <  application.local.yaml  <  environment variables  <  --arguments
```

* **Packs** (`config/packs/<name>/pack.yaml`) contribute mnemonics, roles, connectors, routes, link fields and so on.
  They are added as the lowest-precedence source, so anything the site writes in its own configuration
  overrides a pack.
* **`application.yaml`** is the bundled default.
* **`application.local.yaml`** is loaded by the bundled file itself
  (`spring.config.import: optional:file:./application.local.yaml`). The path is relative to the directory you
  start the server from. If the file does not exist nothing happens (`optional:`).
* **Environment variables** override both files.
* **Command-line arguments** (`--key=value` after the jar name) override everything.

### Worked example: four layers, one key

The longest a source read may take is `drishti.sources.fetch-timeout`, `10s` in the bundled file: the first read of a date on a large table loads its id index first, and a panel's own source (another pack's data) is read within the same limit.

1. Create `application.local.yaml` in the directory you start the server from (the repository root when
   you run `java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar`):

   ```yaml
   drishti:
     sources:
       fetch-timeout: 3s
   ```

   Now the effective value is `3s`.

2. Start the server with an environment variable as well:

   ```bash
   DRISHTI_SOURCES_FETCHTIMEOUT=4s java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
   ```

   The effective value is `4s`: the environment beats the local file.

3. Add an argument:

   ```bash
   DRISHTI_SOURCES_FETCHTIMEOUT=4s java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar \
       --drishti.sources.fetch-timeout=5s
   ```

   The effective value is `5s`: arguments beat everything.

### Two kinds of environment variable

The server understands environment variables in two ways. Knowing the difference saves confusion.

1. **Relaxed names.** Any key can be set from the environment by upper-casing it, turning `.` into `_` and
   dropping `-`: `drishti.sources.fetch-timeout` becomes `DRISHTI_SOURCES_FETCHTIMEOUT`. Spring Boot also
   accepts the older form with `-` turned into `_` (`DRISHTI_SOURCES_FETCH_TIMEOUT`). Relaxed names work for
   every key, whether or not the YAML mentions a variable.
2. **Named placeholders.** Some values in `application.yaml` and in the packs are written as
   `${DRISHTI_TOKEN_SECRET:}` or `${DRISHTI_LAKE_ENABLED:true}`: "use this variable, else the default after the
   colon". These short names (`DRISHTI_PACKS`, `DRISHTI_TOKEN_SECRET`, `DRISHTI_DELTA_ROOT`) are the documented
   ones; they are listed in the [index](#environment-variable-index).

One consequence: a placeholder only lives in the file that contains it. If your `application.local.yaml` sets
`drishti.security.secret: something`, then `DRISHTI_TOKEN_SECRET` is no longer consulted for that key (the
local file replaced the line holding the placeholder), while the relaxed name `DRISHTI_SECURITY_SECRET` would
still win. Keep secrets out of files and you never meet this.

Maps and lists (`drishti.sources.routes`, `drishti.security.roles`, `drishti.graph.id-patterns`) are much
easier to write in YAML than as environment variables. Put them in `application.local.yaml`.

### Seeing what is in effect

Admin → Configuration shows the value in effect of every key of the server's and the console's `application.yaml` (and of the engines' files) and where it came from (`GET /api/v1/admin/settings` is its answer for the server). Most settings also show up somewhere you can read:

| To check | Ask | Look for |
|---|---|---|
| business date, calendar, zone, history | `curl -s localhost:18480/api/v1/business-date` | `"calendar":"USNY","zone":"America/New_York","earliest":"2021-09-30"` |
| Studio save, governance | `curl -s localhost:18480/api/v1/studio/settings` | `{"approve":true,"review":true,"save":true}` |
| which sources and connectors started | `curl -s localhost:18480/api/v1/sources` | one entry per started plugin or connector, with `"health":"UP"` |
| packs loaded | `curl -s localhost:18480/api/v1/packs` | one entry per pack |
| everything at once | `curl -s localhost:18480/api/v1/admin/health` (admin only when security is on) | `"summary":{"packsWithProblems":0,"failedToStart":0,"sourcesDown":0,"sourcesDegraded":0,…}` |

A connector that failed to start appears in the `failedToStart` map, name to reason (for example
`"ops-lake": "no plugin named 'delta2'"`), which is usually a mistyped plugin name or setting.

---

## Placeholders: `${NAME:default}`

Many values in `application.yaml` are written `${NAME:default}`, for example `port: ${DRISHTI_PORT:18480}`.

**The server (Spring).** These are Spring property placeholders. At start Spring looks for a property called `NAME`
in every property source, and uses the first it finds, else the text after the colon:

| Where `NAME` is set | Example | Consulted |
|---|---|---|
| a command-line argument | `--DRISHTI_PORT=18481` | yes (highest) |
| a Java system property | `java -DDRISHTI_PORT=18481 -jar …` | yes |
| an environment variable | `DRISHTI_PORT=18481 java -jar …` | yes |
| none of them | | the default, `18480` |

Each row was tried against a scratch server. Points to know:

* **An empty default.** `${DRISHTI_PLUGIN_DIR:}` has an empty default: nothing is set when the variable is absent. A variable that is
  *set to empty* (`DRISHTI_PACKS=`) gives the empty string, not the default.
* **Which applies, the placeholder or the relaxed name?** Both exist for a key such as `drishti.packs.enabled`
  (written `${DRISHTI_PACKS:core,market-data,…}` in the file). The placeholder is how the *file's own value* is computed.
  The relaxed name (`DRISHTI_PACKS_ENABLED`, [above](#two-kinds-of-environment-variable)) overrides the key itself, so
  with both set the relaxed one wins: `DRISHTI_PACKS=market-risk DRISHTI_PACKS_ENABLED=world-economy` loads only `world-economy`.
  A key with no placeholder in the file has only the relaxed name.
* **Precedence** of the sources, highest first: command-line arguments, Java system properties, environment
  variables, config files (a profile's file such as `application-files.yaml` over `application.yaml`). So
  `--server.port=18989` beats `DRISHTI_PORT=18988` (tried: the server listened on 18989). The placeholder
  is resolved against all of them at once, so `DRISHTI_PORT` as an argument, property or environment variable is
  the same placeholder.
* **Nested.** The default may itself be a placeholder: `${A:${B:18480}}` uses `A`, else `B`, else `18480` (tried
  with `--server.port='${NOPE_A:${NOPE_B:18989}}'`). A placeholder may also sit inside a longer value
  (`optional:file:${DRISHTI_PACKS_OVERLAY:${drishti.data.dir:./data}/packs/added.yaml}`, `s3a://${LAKE_BUCKET:bank-lake}/drishti`).
  The bundled file nests none.
* **Your own files** can use the syntax anywhere (`password: ${TRADES_DB_PASSWORD}`).

**The console (Python).** `drishti-console/core/config.py` reads the same syntax, with differences: `NAME` is an
**environment variable only** (no system properties, no other config keys); the default after the colon is text up to
the first `}` (no nesting); a missing variable with no default is empty; and the result is typed (`true`/`false`
become booleans, digits numbers). Precedence, lowest first: `drishti-console/config/application.yaml`, `drishti-console/config/application.local.yaml`,
then `DRISHTI_CONSOLE__A__B` environment variables, then `--a.b=value` arguments. The console has no flag
for choosing another config *directory*; see [QUICKSTART](../guides/GETTING_STARTED.md).

### The data folder and the config folder

Everything the product owner puts in files lives in one of two places, both relative to the server's working directory:

| Folder | Setting (variable) | Holds |
|---|---|---|
| `config/connectors/` | `drishti.sources.connectors-dir` (`DRISHTI_CONNECTORS_DIR`) | One file per connector ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)). |
| `config/packs/` | `drishti.packs.dir` (`DRISHTI_PACKS_DIR`) | The domain packs. |
| `config/bi/` | `drishti.bi.dir` (`DRISHTI_BI_DIR`) | Rupaka (BI) files: `datasets/`, `reports/`, `boards/`, `notebooks/`, `python/` (`config/bi/README.md`); shipped files are read-only items of the catalogue ([BI_GUIDE.md](../guides/BI_GUIDE.md)). |
| `data/` | `drishti.data.dir` (`DRISHTI_DATA_DIR`) | Everything written at run time. |

`drishti.data.dir` is the umbrella for every runtime path. Each of these defaults to a folder under it, and each keeps its own
setting, which still overrides the default:

| Under the data folder | Setting |
|---|---|
| `identity/` (database, users file, audit file, preferences) | `drishti.identity.*` |
| `packs/added.yaml`, `packs/installed/`, `packs/settings/`, `packs/samples-mode`, `packs/deploy-history.jsonl` | `drishti.packs.overlay`, `installed-dir`, `settings-dir`, `samples-file`, `deploy.history-file` |
| `delta/`, `files/`, `iceberg/`, `duckdb/` (the lake and store roots the packs' connector suggestions use) | `DRISHTI_DELTA_ROOT`, `DRISHTI_FILES_ROOT`, `DRISHTI_ICEBERG_ROOT`, `DRISHTI_DUCKDB_PATH` |
| `cache/` (stream disk caches), `state/` (message-source state), `feeds/` (the bundled file source) | `DRISHTI_CACHE_ROOT`, `state.root`, `DRISHTI_FEEDS` |
| `connectors/history/` (every earlier text of a connector file, for Restore), `connectors/generated/` (markers of connector files written from a pack template) | beside `drishti.sources.connectors-dir`; the files themselves stay in `config/connectors/` |
| `qa/` (the IDE QA profile: its own data folder, token, lake and packs), `build/` (the default output of `pack make`, `schema pack` and `pack catalogue`), `drill/` (the logs of `tools/drill.sh`), `dev-sutras/` (Studio file binding) | `DRISHTI_DATA_DIR` for the tools; `drishti.builder.dev-dir` |
| `loads/`, `collab/`, `designs/`, `reports/`, `governance/` | `drishti.loads.dir`, `drishti.collab.dir`, `drishti.builder.designs.dir`, `drishti.reports.folder`, `drishti.governance.dir` |

Move the whole tree with one setting: `DRISHTI_DATA_DIR=/var/lib/drishti` puts the identity database at
`/var/lib/drishti/identity/drishti.db` and the lake at `/var/lib/drishti/delta`. The plugins read it too (the server publishes the
resolved value to them as the system property `drishti.data.dir`, and command-line tools read `DRISHTI_DATA_DIR`).
The `./data/...` defaults shown in this page are the defaults of this setting.

**Moved:** the packs folder was `./packs` and is now `./config/packs` (`git mv packs config/packs`). For one release a server
whose packs folder does not exist, and that finds `./packs`, uses `./packs` and logs a warning; the console does the same with `../packs`.

### Temporary folders: `drishti.tmp-dir` and the console's `tmp_dir`

Both processes keep **one folder of their own** for everything temporary, so a crash or a full `/tmp` can be traced to a process and nothing is left behind.

| Process | Setting | Variable | Default | The folder |
|---|---|---|---|---|
| Server | `drishti.tmp-dir` (or `--drishti.tmp-dir=` on the command line) | `DRISHTI_TMP_DIR` | `<data dir>/tmp/server` (`./data/tmp/server`; the console's is `<data dir>/tmp/console`; the system temp folder before 2026-10-18: it can be a small in-memory file system) | `<base>/drishti-<pid>` |
| Console | `tmp_dir` | `DRISHTI_CONSOLE_TMP_DIR`, then `DRISHTI_TMP_DIR` | `<data dir>/tmp/console` when started by `run_drishti_web.py` (the system temp folder before 2026-10-18) | `<base>/drishti-console-<pid>` |

At start each process (1) **leaves the folders of dead processes alone**: removing them automatically was disabled (2026-10-17) because it is not safe on every machine (a reused process id, or a link inside a folder, could lead a delete outside the folder); they are listed on Admin, Health for a person to remove by hand, (2) makes its own, and (3) points the language's temp setting at it before anything loads: the server sets the `java.io.tmpdir` property (DuckDB spills into its own subfolder; sqlite-jdbc and snappy, which read the property, unpack there), the console sets `tempfile.tempdir` (so uploads, exports, the static-file compression cache, the Python runner's scratch and the new-pack wizard land there). On a clean shutdown the folder is removed. The server reads the setting before Spring starts, so it comes from the command line, the system property or the variable, not from `application.yaml`. Files that are written and then moved over a target (atomic saves) are made in the target's own folder, since a move across disks is not atomic; they are made by the same helper.

**The console removes its crash leftovers at start** (`core/tmpdir.py`, `remove_stale`), safely by construction: only a real
folder (never a link) directly under the base, named `drishti-console-<digits>`, holding the console's marker file, untouched for ten
minutes, whose process is gone; its contents are removed without following any link. Not on Windows (asking whether a process lives would
end it there): the folders are listed. The server's leftovers are still only listed (Admin, Health).

### Logs, the cache budget, heap dumps and the disk guard: `drishti.logging`, `drishti.memory`, `drishti.disk`

| Key | Default | Meaning |
|---|---|---|
| `drishti.logging.file` | empty (`DRISHTI_LOG_FILE`) | The server writes its log to this file itself, rotated; empty: standard output only (containers). `drishti-stack` sets `run/logs/<node>.log`. |
| `drishti.logging.max-file-mb` | `100` | One log file's size before it rolls (also daily); rolled files are compressed (`.gz`). |
| `drishti.logging.max-total-mb` | `1024` | Every rolled file together; the oldest go beyond. |
| `drishti.logging.max-files` | `30` | Rolled days kept at most. |
| `drishti.memory.cache-share` | `0.5` | The share of `-Xmx` every cache in the one cache budget may take together, divided into weighted pools: the Delta connectors' shared caches (`connector.parts`, `.idmaps`, `.columns`, `.documents`), the engines' answers (`engine.answers`: VaR and exposure answers, exposure trade paths, BI results) and `small` (the viewers' views). A member's own limit (a connector's `cache-mb`, `drishti.var.view-cache-mb`) holds when smaller than its share. Admin → Caches shows the budget and each pool. |
| `drishti.memory.weights.<pool>` | `connector.idmaps` 20, `connector.columns` 20, `connector.documents` 6, `connector.parts` 4, `engine.answers` 20, `engine.layouts` 10, `small` 20 | Each pool's weight: its share is the budget × weight ÷ the weights' sum. |
| `drishti.memory.squeeze-at` | `0.85` | When the heap after a collection (the old generation's collection-usage threshold) passes this share of `-Xmx`, the connectors' and small pools are halved for a minute; the answers keep theirs. |
| `drishti.disk.min-free-percent` | `10` | The free-space guard: a hot file, shard book or currency's book that would leave less than this share of its disk free is not built (`503 DRS-5218`) after every folder has freed down to 90% of its cap; a date already open keeps answering. |
| `drishti.disk.min-free-gb` | `20` | The guard in GB; both must hold. |
| `drishti.disk.check-interval` | `60s` | How often every folder is measured and brought under its cap (Admin → Health → Disk shows them). |
| `drishti.disk.max-dumps` | `2` | Heap dumps kept in the JVM's `-XX:HeapDumpPath` folder; the oldest beyond go at start. |
| `drishti.disk.dumps-dir` | `<data dir>/dumps` | The dump folder when the JVM's `-XX:HeapDumpPath` does not name one. |

#### Pass `-Djava.io.tmpdir=` to Java

Setting `java.io.tmpdir` inside a running JVM does not move `Files.createTempFile` or `File.createTempFile`: since JDK 17 they use the value the JVM **started** with. DuckDB (60 MB a start), RocksDB and CatBoost unpack their native libraries with those calls, so without the flag they land in the system `/tmp`, and every server killed without a clean stop (`kill -9`, a test teardown, a container stopped hard) leaves its copy there. `/tmp` is often a small in-memory file system with a per-user quota; once it fills, other programs (a browser, the console's tests) fail. So start the server with the flag, pointing at the server's temp base:

```bash
mkdir -p data/tmp/server        # once: the server makes it too, but the JVM warns on a first start when it does not exist yet
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

or, where editing the command is awkward (a service manager, a container, a wrapper script), through the environment: `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/opt/drishti/data/tmp/server` (Java prints `Picked up JAVA_TOOL_OPTIONS` once). With another data folder or `DRISHTI_TMP_DIR`, use that base: `<data dir>/tmp/server`. Everything Drishti ships passes it already: `tools/windows/start-server.ps1`, the IntelliJ run configurations, `deploy/docker/server.Dockerfile` (in `JAVA_TOOL_OPTIONS`), the `run-server.sh`/`.ps1` that `tools/packmake.py` writes, the loaders `tools/load-*.sh`, and every java the tests and tools start (`tools/javacmd.py`; a guard test fails on one that does not). `./mvnw` gives Maven's own JVM `<repository>/data/tmp/maven` (`DRISHTI_MAVEN_TMP` names another), and the test JVMs get `<module>/target/tmp`, which `mvn clean` removes.

At start the server

- **warns** when the flag was not passed (on the command line, `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS`): the log line `java.io.tmpdir was not set when Java started, so it is the system temp folder (/tmp): …` names the exact flag for this server, and Admin, Health shows it under *Temp folders* until the server is started with it;
- **removes stale native-library copies** (`libduckdb_java*`, `librocksdbjni*`, `libcatboost*`, jansi, `sqlite-*`, `snappy-*`, lz4, zstd, netty; files only, no links, older than 10 minutes) from the top of its own temp base, never from `/tmp` or anywhere else. A copy another live server still maps is harmless to remove on Linux and macOS; on Windows the delete fails and the file is skipped quietly. Admin, Health shows how many were removed.

Copies already in `/tmp` from earlier runs are not touched: remove them by hand when no Drishti JVM is running (`ls /tmp/libduckdb_java* /tmp/librocksdbjni*`).

Admin, Health shows both folders (path, size and file count). All main code makes temporary files through one helper (`TempFiles` in the server, `core/tmpdir.py` in the console); an architecture test and a console test fail the build if anything else calls `Files.createTempFile`, `Files.createTempDirectory`, `File.createTempFile` or `tempfile.*`.

### JVM flags for a long run

Besides `-Djava.io.tmpdir=`, every launcher Drishti ships starts the server with these (they are JVM flags, not settings, so they go on
the command line or in `JAVA_TOOL_OPTIONS`):

| Flag | Why |
|---|---|
| `-XX:MaxDirectMemorySize=256m` | caps direct buffers |
| `-Djdk.nio.maxCachedBufferSize=262144` | Java 21 keeps a temporary direct buffer as large as each channel read into a heap array cached on every thread; capping the cache at 256 KB stops that pinning the 256 MB (it failed every lake read after 13 hours). The lake loaders set it too |
| `-XX:TrimNativeHeapInterval=60000` | gives freed native memory back every minute |
| `-XX:NativeMemoryTracking=summary` | `jcmd <pid> VM.native_memory summary` |
| `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<data dir>/dumps` | a heap dump when the heap runs out |
| `-Xlog:gc*:file=<data dir>/logs/gc-%p.log:time,uptime:filecount=5,filesize=20m` | a rotated GC log (100 MB at most); make the folder first, or the JVM does not start |
| `-Ddrishti.open-dates.net-grace-seconds=120` (the default; no launcher passes it) | the byte safety net (`drishti.var.open-heap-mb`, `drishti.ccr.exposure.open-heap-mb`) never closes a date, nor its currency and stress books, read in the last this many seconds, so two dates asked in turn by many users do not close and reload each other at a small heap; idle dates still leave, a new load without room is still refused (`DRS-5204`); `0` turns it off ([VAR_GUIDE.md 26.8](../guides/VAR_GUIDE.md#268-dates-in-memory-over-years-of-history)). Likewise `-Ddrishti.parquet.footer-cache-mb=64`: the parsed Parquet footers kept, weighed at about 700 bytes a column chunk |

Where each launcher puts the dumps and GC logs, and how to read them: [OPERATIONS.md](OPERATIONS.md#121a-direct-memory-and-the-diagnostics-of-a-long-run).
Kafka connectors fetch at most 4 MB a poll and 1 MB a partition; `client.fetch.max.bytes` and `client.max.partition.fetch.bytes` in the connector file change that.

### External libraries: `external_lib` (`DRISHTI_EXTERNAL_LIB`)

One folder for your own libraries, loaded at start with no rebuild ([external_lib/README.md](../../external_lib/README.md)):

| Folder | What goes in it | Loaded by |
|---|---|---|
| `<base>/java` | jars: JDBC drivers, connector plugins, libraries a plugin needs | the **server**: every jar joins its class path at start (the jar is launched by Spring Boot's `PropertiesLauncher`, whose `loader.properties` names this folder; `-Dloader.path=` overrides it) |
| `<base>/python` | modules, packages, pure-Python `.whl` / `.zip` / `.egg` files | the **console**: on `sys.path` at start, after the console's own folder (the console's own tools; Python items run on the server's compute pool, whose libraries are pinned in `config/compute/locks/python.lock`) |

| Process | Setting | Variable | Default |
|---|---|---|---|
| Server | system property `drishti.external-lib` | `DRISHTI_EXTERNAL_LIB` | `external_lib` in the working directory |
| Console | `external_lib` | `DRISHTI_EXTERNAL_LIB` | `external_lib` in the repository root |

Restart a process after adding or removing a library. Admin, Health lists the jars and the Python items each process
found. Only administrators should be able to write to the folder: what is in it runs inside the server and the console.
The repository keeps the folder empty (`.gitignore`).

### Every placeholder

Generated from `drishti-server/src/main/resources/application.yaml` (and checked against it by
`drishti-console/tests/test_docs_placeholders.py`, so the table cannot drift). "Sets" is the key the placeholder fills. Profile
files (`application-files.yaml`, `application-postgres.yaml`, …) and packs have placeholders of their own, listed in
[Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles).

| Variable | Sets | Default | What it does |
|---|---|---|---|
| `DRISHTI_PACKS_OVERLAY` | `spring.config.import` | `./data/packs/added.yaml` | The file of packs an administrator loaded from Admin → Packs; imported at start (`spring.config.import`) and named again by `drishti.packs.overlay`. Written by the server, not by hand. |
| `DRISHTI_PORT` | `server.port` | `18480` | The server's HTTP port. |
| `DRISHTI_HTTP2` | `server.http2.enabled` | `true` | HTTP/2 on the server's port as well as HTTP/1.1: Drishti*net peers multiplex their forwarded reads over one connection (`h2c` by the upgrade on plain HTTP, ALPN on TLS; up to 1,000 streams per connection), and browsers over TLS get it too. `false`: HTTP/1.1 only; peers then fall back to it on the same call ([OPERATIONS.md › Drishti*net: running several servers as one, section 7](OPERATIONS.md#7-routing-where-a-request-goes)). |
| `DRISHTI_PRODUCT` | `drishti.branding.product` | `Drishti` | The product name shown in pages and messages. |
| `DRISHTI_EDITION` | `drishti.edition` | empty | The edition the server runs ([EDITIONS.md](EDITIONS.md)): empty is the full Drishti; `risk` is Drishti Risk; `world` is Drishti World; `market` is Drishti Market; `trading` is Drishti Trading. Every member of a cluster sets the same; the console learns it from the server. A restart applies it. |
| `DRISHTI_EDITIONS_DIR` | `drishti.editions.dir` | `./config/editions` | The folder of the edition files (and `features.yaml`, `default-packs.yaml`). |
| `DRISHTI_LENSES` | `drishti.lenses` | `risk,world,market,trading` | The editions a person may open as a lens on a full instance (`risk`, `world`, `market`, `trading`, or several: `risk,world,market,trading`, the default); empty: no lens link. Ignored when the server runs an edition. A lens hides, it does not secure. |
| `DRISHTI_PLUGIN_DIR` | `drishti.sources.plugin-dir` | empty | A directory of extra plugin jars; empty: none. |
| `DRISHTI_HTTP_PROXY_URL` | `drishti.http.proxy.url` | empty | The site's outbound HTTP proxy (`http://host:port`); empty: none. See [`drishti.http.proxy`](#drishtihttpproxy--the-sites-outbound-http-proxy). |
| `DRISHTI_HTTP_PROXY_USERNAME` | `drishti.http.proxy.username` | empty | The proxy's Basic-authentication user. |
| `DRISHTI_HTTP_PROXY_PASSWORD` | `drishti.http.proxy.password` | empty | The proxy's password: an environment variable, never a literal in a file. |
| `DRISHTI_DEMO_ENABLED` | `drishti.sources.plugins.demo.enabled` | `true` | Run the demo source (the packs' sample data). |
| `DRISHTI_OFFLINE` | `drishti.offline.enabled` | `false` | Offline mode, for a machine with no internet: connectors of the internet plugins (`drishti.offline.plugins`: `news`, `yfinance`, `feed`) never start, whatever their files say; Admin → Connectors shows them *off (offline mode)*. See [`drishti.offline`](#drishtioffline--offline-mode-no-internet). |
| `DRISHTI_NET_DIR` | `drishti.net.dir` | `./config/net` | Drishti*net's folder (`node.yaml`, `peers/*.yaml`, `gossip.yaml`, `routing.yaml`, `access.yaml`); without it, or with `enabled: false` in its `node.yaml`, the server is a single server. See [`drishti.net`](#drishtinet--drishtinet-the-cluster-folder) and [OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one). |
| `DRISHTI_NET_WATCH` | `drishti.net.watch` | `auto` | How the folder is watched: `auto` (file events, polling as a fallback), `poll` or `off` (read at start only). |
| `DRISHTI_FEEDS` | `drishti.sources.plugins.file.settings.root` | `./data/feeds` | The folder the bundled `file` source reads. |
| `DRISHTI_REST_ENABLED` | `drishti.sources.plugins.rest.enabled` | `false` | Run the `rest` source. |
| `DRISHTI_REST_URL` | `drishti.sources.plugins.rest.settings.base-url` | `http://localhost:9000/api` | The REST source's base URL. |
| `DRISHTI_JDBC_ENABLED` | `drishti.sources.plugins.jdbc.enabled` | `false` | Run the `jdbc` source as itself (one instance). |
| `DRISHTI_JDBC_URL` | `drishti.sources.plugins.jdbc.settings.url` | empty | The JDBC URL of that instance. |
| `DRISHTI_JDBC_USER` | `drishti.sources.plugins.jdbc.settings.user` | empty | Its database user. |
| `DRISHTI_JDBC_PASSWORD` | `drishti.sources.plugins.jdbc.settings.password` | empty | Its database password. |
| `DRISHTI_ACTIVEMQ_ENABLED` | `drishti.sources.plugins.activemq.enabled` | `false` | Run the `activemq` source as itself. |
| `DRISHTI_RABBITMQ_ENABLED` | `drishti.sources.plugins.rabbitmq.enabled` | `false` | Run the `rabbitmq` source as itself. |
| `DRISHTI_S3_ENABLED` | `drishti.sources.plugins.s3.enabled` | `false` | Run the `s3` source as itself. |
| `DRISHTI_CALENDAR` | `drishti.business-date.calendar` | `USNY` | The holiday calendar that rolls the business date back (`USNY`, `GBLO`, `EUTA`, `JPTO`, `CATO` (Toronto), or joined with `+`). |
| `DRISHTI_SECURITY_ENABLED` | `drishti.security.enabled` | `false` | Turn sign-in and role checks on. |
| `DRISHTI_TOKEN_SECRET` | `drishti.security.secret` | empty | The token signing secret (at least 32 bytes), shared with the console. |
| `DRISHTI_METRICS_TOKEN` | `drishti.security.metrics-token` | empty | A bearer token for Prometheus to scrape `/actuator/prometheus` with security on. |
| `DRISHTI_OIDC_ENABLED` | `drishti.security.oidc.enabled` | `false` | Verify single-sign-on ID tokens. |
| `DRISHTI_EMBED_ENABLED` | `drishti.embed.enabled` | `false` | Embedded views for other web applications: the token endpoint and embed tokens. Needs security on. |
| `DRISHTI_EMBED_SIGN_IN_WINDOW` | `drishti.embed.sign-in-window` | `true` | The renderer's sign-in window (`auth: 'popup'`): this server makes the embed token after the console's single sign-on in a window a registered host page opened on a click. Needs single sign-on. |
| `DRISHTI_PUBLIC_URL` | `drishti.embed.issuer` | empty | This server's public URL: the `iss` of embed tokens. |
| `DRISHTI_CONSOLE_URL` | `drishti.embed.audiences` | empty | The console's public URL: the audience embed tokens are made for. |
| `DRISHTI_EMBED_KEY_FILE` | `drishti.embed.signing-key` | empty | PEM file with the ES256 key pair that signs embed tokens (empty: made at start). |
| `DRISHTI_OIDC_ISSUER` | `drishti.security.oidc.issuer` | empty | The OIDC issuer URL. |
| `DRISHTI_OIDC_CLIENT_ID` | `drishti.security.oidc.client-id` | empty | The OIDC client id. |
| `DRISHTI_ACCESS_LOG` | `drishti.access-log.enabled` | `true` | Record who looked at what (Admin → Access). |
| `DRISHTI_COLLAB_ENABLED` | `drishti.collab.enabled` | `true` | Share with a note, the directory and the inbox ([COLLABORATION.md](../architecture/COLLABORATION.md)). |
| `DRISHTI_COLLAB_DIR` | `drishti.collab.dir` | `./data/collab` | Where the file store keeps shares and inbox rows (`drishti.collab.store: file`), and exports. |
| (no variable) | `drishti.collab.store` | `content` | Where threads, notes, shares, the inbox, the outbox, holds and seals are kept: `content` (the content store, every node of a cluster; the database tables and `drishti.collab.dir` files are imported once), or one node only `jpa` (the identity database) or `file`. [DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9](../architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches). |
| (no variable) | `drishti.audit.store` | `content` | The audit trail: `content` (per-node day segments in the content store, merged when read; `drishti_audit` imported once) or `jpa` (the identity database, one node). |
| (no variable) | `drishti.embed.store` | `content` | Embed applications: `content` (`admin/embed-apps/`, `drishti_embed_app` imported once) or `jpa`. |
| (no variable) | `drishti.packs.state-store` | `content` | Packs switched off in Admin: `content` (`admin/pack-state.json`, `drishti_pack_state` imported once) or `jpa`. |
| (no variable) | `drishti.loads.store` | `content` | Load history: `content` (`loads/<pack>.json`, each pack's `drishti.loads.dir` file imported on first read) or `file` (one node). |
| (no variable) | `drishti.loads.config-store` | `content` | Per-pack data loads overrides saved through Admin: `content` (`loads/config/<pack>.yaml` in the content store, every node; the `drishti.loads.dir/config` files imported once and kept) or `file` (one node). |
| (no variable) | `drishti.connectors.store` | `content` | Connector edits made through Admin: `content` (a shared overlay `config/connectors/<name>.yaml` in the content store that every node applies over its file) or `file` (written into the node's own `config/connectors`, one node). |
| (no variable) | `drishti.connectors.template-updates` | (from `config/connectors-settings.yaml`, else `additive`) | Connectors behind their pack's template: `additive` adds missing kinds and settings automatically (keeping edits), `notify` only lists them in Admin → Connectors → Template updates, `off`. Set it in `config/connectors-settings.yaml` (`template-updates:`; the file is named by `drishti.connectors.settings-file`); this property overrides the file. |
| (no variable) | `drishti.connectors.template-check-ms` | `60000` | How often connectors are compared with their pack's template. |
| (no variable) | `drishti.store.watch-ms` | `2000` | How often each node asks the content store what changed, to apply another node's shared edits (connectors, load overrides, pack state). |
| (no variable) | `drishti.governance.store` | `content` | Sutra proposals: `content` (`governance/proposals/`, the `drishti.governance.dir` folder imported once) or `file` (one node). |
| `DRISHTI_CONSOLE_URL` | `drishti.collab.console-url` | empty | The console's address, put in front of `/share/sh_…` in the links shares carry; email stays off while it is empty. |
| `DRISHTI_MAIL_FROM` | `drishti.collab.email.from` | `drishti@localhost` | The From address of collaboration email. |
| `DRISHTI_MASK_COPIES` | `drishti.security.mask-copies` | `false` | Also scrub exact copies of a masked value inside other text of the same document. |
| `DRISHTI_MAX_SWALLOW` | `server.tomcat.max-swallow-size` | `64MB` | How much of an oversized body Tomcat drains after answering 413, so the client reads the answer. |
| `DRISHTI_REPORTS_ENABLED` | `drishti.reports.enabled` | `true` | Run the scheduled-reports scheduler on this server. |
| `DRISHTI_REPORTS_DIR` | `drishti.reports.folder` | `./data/reports` | Where report files are written. |
| `DRISHTI_SUTRA_WATCH` | `drishti.rachana.watch` | `auto` | How Sutra hot reload notices edits: `auto` (file events, polling when they are unavailable) or `poll`. |
| `DRISHTI_LABS_CALC` | `drishti.calc.enabled` | `false` | Labs: allow Calc (Python in the browser, a scratch pad that never publishes). |
| `DRISHTI_VAR_ENABLED` | `drishti.var.enabled` | `true` | Run the VaR engine (`/api/v1/var`, the VaR explorer). |
| `DRISHTI_VAR_THREADS` | `drishti.var.threads` | `0` | Cores the VaR engine's vector sums use (`0`: every core). |
| `DRISHTI_VAR_MEMORY_MB` | `drishti.var.memory-budget-mb` | `0` | Most memory one date's P&L vectors take (`0`: a quarter of the heap). |
| `DRISHTI_VAR_TIER` | `drishti.var.tier.mode` | `mapped` | Where a loaded date's vectors live: `mapped` (a memory-mapped float32 hot file per date) or `heap` (float64 on the heap). |
| `DRISHTI_VAR_HOT_DIR` | `drishti.var.tier.dir` | `./data/var/hot` | The VaR hot files' folder (one folder per business date); under the data folder unless set. |
| `DRISHTI_VAR_CURRENCIES` | `drishti.var.currencies` | `USD,EUR,CAD,JPY,GBP` | The reporting currencies built ahead after each date's USD book (USD always). |
| `DRISHTI_VAR_CURRENCY_RETENTION_DATES` | `drishti.var.currency-retention-dates` | `5` | The newest business dates whose listed currencies are built ahead and kept on disk. |
| `DRISHTI_LOG_FILE` | `drishti.logging.file` | empty | The server writes its log to this file itself, rotated (100 MB a file, 1 GB in all); empty: standard output only. |
| `DRISHTI_VAR_VERSION_CHECK` | `drishti.var.version-check` | `30s` | The version watch's interval for an open VaR date (`0`: off). |
| `DRISHTI_VAR_SMAT_MAX_DAYS` | `drishti.var.smat.max-days` | `60` | The most business dates one scenario matrix (`SMAT`) reads. |
| `DRISHTI_VAR_MAX_DISK_GB` | `drishti.var.tier.max-disk-gb` | `0` | The most disk the VaR hot files take; `0`: half the disk. |
| `DRISHTI_VAR_KEEP_HOT_FILES` | `drishti.var.tier.keep-on-shutdown` | `false` | Keep the VaR hot files across restarts (the server images set `true`). |
| `DRISHTI_EXPOSURE_VERSION_CHECK` | `drishti.ccr.exposure.version-check` | `30s` | The version watch's interval for an open exposure date (`0`: off). |
| `DRISHTI_EXPOSURE_MAX_DISK_GB` | `drishti.ccr.exposure.max-disk-gb` | `0` | The most disk the exposure hot files take; `0`: a quarter of the disk; it wins over the retention. |
| `DRISHTI_EXPOSURE_KEEP_HOT_FILES` | `drishti.ccr.exposure.keep-on-shutdown` | `false` | Keep the exposure hot files across restarts (the server images set `true`). |
| `DRISHTI_VAR_INTRADAY_KAFKA_WINDOW` | `drishti.var.intraday.kafka.window` | `5m` | The PV topic's window: everything received in it is appended to the lake as one intraday batch and applied (`30s`, `5m`, `1h`). |
| `DRISHTI_VAR_INTRADAY_KAFKA_MAX_TRADES` | `drishti.var.intraday.kafka.max-trades` | `500` | A window is applied earlier once this many trades wait. |
| `DRISHTI_VAR_CONFIDENCE` | `drishti.var.confidence` | `0.99` | VaR's confidence: 0.99 or 0.95 (refused at start outside (0.5, 1)); every label follows it; limits are set against it. |
| `DRISHTI_VAR_ES_CONFIDENCE` | `drishti.var.es-confidence` | `0.975` | Expected shortfall's confidence. |
| `DRISHTI_VAR_TEN_DAY_METHOD` | `drishti.var.ten-day-method` | `sqrt10` | What a 10-day VaR without a named method is: `sqrt10` (the 1-day × √10) or `sum` (10 consecutive days, overlapping windows). |
| `DRISHTI_VAR_SCENARIO_WINDOW` | `drishti.var.scenario-window` | `250` | The scenarios used: N ≥ 20, the newest N of each date's set (250: one year, the Basel minimum), or `all` (the scenario set's length). |
| `DRISHTI_VAR_ROOT_NAME` | `drishti.var.root-name` | empty | The name of the top of the VaR tree (every hierarchy's root node); empty keeps the hierarchy data's `NAME` (*Drishti Bank* in the demo). `drishti.var.root-names` (a map, hierarchy to name) wins for one hierarchy. Read when a date loads: restart the server (or reload the dates) after changing it. |
| `DRISHTI_VAR_OFFICIAL` | `drishti.var.official.enabled` | `true` | Capture every node officially at the end of each loaded date, and publish it to `risk-results/var-official`. |
| `DRISHTI_VAR_OFFICIAL_FORMAT` | `drishti.var.official.format` | `delta` | `iceberg` publishes the official VaR results to Iceberg; BI's `var-results` connector follows by itself. |
| `DRISHTI_EXPOSURE_OFFICIAL_FORMAT` | `drishti.ccr.exposure.official.format` | `delta` | The same for the official exposure results and BI's `exposure-results`. |
| `DRISHTI_DELTA_ROOT` | `drishti.var.official.root` | `./data/delta` | The Delta root the official VaR results are published under (`risk-results/var-official`); the packs' Delta connectors use the same variable. |
| `DRISHTI_EXPOSURE_ENABLED` | `drishti.ccr.exposure.enabled` | `true` | Run the exposure engine (`/api/v1/exposure`, `EXP <scope>`, the counterparty-risk pack's `exposure-engine` connector). |
| `DRISHTI_EXPOSURE_MEMORY_GB` | `drishti.ccr.exposure.memory-budget-gb` | `0` | Most memory one date's netting-set value matrices take while it loads (`0`: a quarter of the heap). |
| `DRISHTI_EXPOSURE_HOT_DIR` | `drishti.ccr.exposure.hot-folder` | `./data/ccr/hot` | The exposure hot files' folder (one folder per business date); under the data folder unless set. |
| `DRISHTI_EXPOSURE_TIER` | `drishti.ccr.exposure.tier` | `mapped` | Where a loaded date's matrices live: `mapped` (a memory-mapped float32 hot file per date) or `heap` (float64 on the heap). |
| `DRISHTI_EXPOSURE_OFFICIAL` | `drishti.ccr.exposure.official.enabled` | `true` | Capture every netting set, counterparty, group and hierarchy node officially at the end of each loaded date, and publish to `risk-results/exposure-official` and `exposure-official-cpty`. |
| `DRISHTI_COMPUTE_EXPOSURE_WORKERS` | `compute.pools.exposure.workers.max` (`config/compute/compute.yaml`) | `4` | Python workers of the compute pool `exposure` (the Python exposure engine: the exact pre-deal what-if, method `paths`) on this node; the compute workers run only with `DRISHTI_COMPUTE_ENABLED=true`, else the exact what-if answers `503 DRS-5446`. |
| `DRISHTI_COMPUTE_PYTHON_WORKERS` | `compute.pools.python.workers.max` (`config/compute/compute.yaml`) | `4` | Python workers of the compute pool `python` on this node: every Python run of code people write (Python items and visuals, Python-driven tables, Notebook cells), in the strict sandbox ([PYTHON_COMPUTE.md section 19](../architecture/PYTHON_COMPUTE.md#19-the-python-runtime-for-code-people-write)). |
| `DRISHTI_LAYOUTS_ENABLED` | `drishti.layouts.enabled` | `true` | Allow personal layouts. |
| `DRISHTI_PIVOT_ENABLED` | `drishti.pivot.enabled` | `true` | Allow the Pivot tab. |
| `DRISHTI_ASK_ENABLED` | `drishti.explain.ask.enabled` | `false` | Turn on Ask about this page (also list packs in `drishti.explain.ask.packs`). |
| `DRISHTI_ASK_ENDPOINT` | `drishti.explain.ask.endpoint` | empty | **Deprecated** (a provider file in `config/ai` replaces it; it still works, as provider `ask`, with a start-up warning). The model endpoint URL for Ask. |
| `DRISHTI_ASK_MODEL` | `drishti.explain.ask.model` | empty | The model Ask uses: a logical model of the AI layer (`provider/model`); with the deprecated `endpoint`, the model id sent to it. |
| `DRISHTI_ASK_KEY` | `drishti.explain.ask.api-key` | empty | **Deprecated** with `endpoint`: put `api-key: ${ENV}` in the provider file. The model endpoint credential (environment only; never logged). |
| `DRISHTI_AI_ENABLED` | `drishti.ai.enabled` | `false` | Turn on the LLM provider layer ([AI.md](AI.md#1-model-providers)): provider files in `drishti.ai.dir` are read and models become callable. Off: no file is read and no model is called. |
| `DRISHTI_AI_DIR` | `drishti.ai.dir` | `./config/ai` | The folder of provider files, one YAML per provider (type, `base-url`, `api-key: ${ENV}`, models with logical names). Watched and reloaded live (`drishti.ai.watch`: `auto` / `poll` / `off`, `poll-ms`). Provider types, their keys and one copy-paste file each: [AI.md › Model providers, section 11](AI.md#11-more-providers-the-way-sajhamcpserver-has-them); examples `config/ai/<type>.yaml.example`, all `enabled: false`. |
| `DRISHTI_AI_DEFAULT_MODEL` | `drishti.ai.default-model` | empty | The model used when a call names none: `provider/model`, or a model name only one provider has. |
| `DRISHTI_MCP_ENABLED` | `drishti.mcp.enabled` | `false` | Turn on the MCP client ([AI.md](AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)): server files in `drishti.mcp.dir` are read and their tools become callable through the policy. |
| `DRISHTI_MCP_DIR` | `drishti.mcp.dir` | `./config/mcp` | The folder of MCP server files, one YAML per server (transport, `url`, `headers` as `${ENV}`, `allowed-tools`). Watched and reloaded live. |
| `DRISHTI_IDENTITY_DB_URL` | `drishti.identity.database-url` | `jdbc:sqlite:./data/identity/drishti.db` | The identity database (users, roles, workspaces, audit); SQLite by default, or `jdbc:postgresql://…`. |
| `DRISHTI_IDENTITY_DB_USER` | `drishti.identity.database-user` | empty | Its user. |
| `DRISHTI_IDENTITY_DB_PASSWORD` | `drishti.identity.database-password` | empty | Its password. |
| `DRISHTI_USERS_FILE` | `drishti.identity.users-file` | `./data/identity/users.json` | The pre-1.10 users file, imported once into an empty database. |
| `DRISHTI_AUDIT_FILE` | `drishti.identity.audit-file` | `./data/identity/audit.jsonl` | The pre-1.10 audit file, imported once. |
| `DRISHTI_FORCE_PW_CHANGE_ON_CREATE` | `drishti.identity.force-password-change-on-create` | `false` | Force a password change for newly created users. |
| `DRISHTI_FORCE_PW_CHANGE_ON_RESET` | `drishti.identity.force-password-change-on-reset` | `false` | Force a password change after an administrator reset. |
| `DRISHTI_SEED_ADMIN` | `drishti.identity.seed-admin` | `true` | Create the development admin on first start with no users; `false` for production. |
| `DRISHTI_SEED_USERNAME` | `drishti.identity.seed-username` | `drishti-dev-admin` | The seeded administrator's user name; with `DRISHTI_SEED_PASSWORD` it is the first administrator you chose (`deploy/docker/compose.yaml` requires both). |
| `DRISHTI_SEED_PASSWORD` | `drishti.identity.seed-password` | `drishti-dev-admin123` | Its password. Only the built-in default shows the "default password" warning. |
| `DRISHTI_API_DOCS` | `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled` | `true` | The OpenAPI description and its UI; set explicitly so a start does not warn they are on by default. |
| `DRISHTI_SUTRAS` | `drishti.rachana.dirs` | `./sutras` | Site Sutra directories, scanned recursively, beside the packs' Sutras. |
| `DRISHTI_STUDIO_SAVE` | `drishti.rachana.studio-save` | `false` | Let Sutra Studio save files (authors only). |
| `DRISHTI_UI_KIT_DIR` | `drishti.ui-kit.dir` | `./config/ui-kit` | The Drishti UI kit: fragments, assets, brand; loaded whatever packs are enabled. |
| `DRISHTI_UI_KIT_SITE_DIR` | `drishti.ui-kit.site-dir` | `./data/ui-kit` | The site's UI kit overrides: `brand.yaml` and `assets/`. |
| (property only) | `drishti.views.selection-ttl` | `PT30M` | How long a Sutra Next selection's token lives unused (renewed on each use); held in the issuing node's memory (RACHANA_REFERENCE.md 7). |
| `DRISHTI_SUTRA_REVIEW` | `drishti.governance.enabled` | `true` | A Studio save is a proposal an approver makes live. |
| `DRISHTI_SUTRA_FOUR_EYES` | `drishti.governance.four-eyes` | `true` | Nobody approves their own proposal (with security on). |
| `DRISHTI_GOVERNANCE_DIR` | `drishti.governance.dir` | `./data/governance` | Where proposals are kept with `drishti.governance.store: file`; with `content` (the default) the folder is imported once and left in place. |
| `DRISHTI_DATA_DIR` | `drishti.data.dir` | `./data` | The data folder: the umbrella every runtime path below defaults under (see [The data folder](#the-data-folder-and-the-config-folder)). |
| `DRISHTI_STORE_FILE` | `drishti.store.file` | `./config/store/store.yaml` | The content store's settings file ([The content store](#the-content-store-configstorestoreyaml)); a missing file means the filesystem store under the data folder. |
| `DRISHTI_EDIT_FILE` | `drishti.edit.file` | `./config/edit/edit.yaml` | Write-back's settings file ([Write-back](#write-back-configedit)); its siblings `lanes.yaml`, `policy.yaml`, `retention.yaml` are read from the same folder; a missing file means write-back off. |
| `DRISHTI_STORE_ROOT` | `drishti.store.root` | empty | The filesystem content store's root, over the file's `filesystem.root` (empty: the file's, else `<data folder>/fstore`). |
| `DRISHTI_BI_STORE` | `drishti.bi.store` | `content` | Where BI workspaces, items, versions, revisions, events, marks, views and settings live: `content` (the content store, every node of a cluster; the identity database's `drishti_bi_*` rows are imported once) or `jpa` (this node's identity database only). See DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9. |
| `DRISHTI_BI_RUNS_STORE` | `drishti.bi.runs.store` | `content` | Where BI runs, their shares, schedules, results and snapshots live: `content` (every node reads them; leases are compare-and-sets in the store; the `drishti_bi_run*` rows and `<data>/bi-runs` are imported once) or `local` (this node's identity database and `store.dir`). |
| `DRISHTI_BI_STORE_LIST_TTL` | `drishti.bi.store-list-ttl` | `1s` | How old the per-node snapshot behind BI and run listings may be (a single item or run is always read fresh; this node's writes drop it at once). |
| `DRISHTI_INDEX_DIR` | `drishti.index.dir` | empty | The multi-day type-ahead index's segment files, one folder per connector; empty means `<data dir>/index` (`./data/index`). See [`drishti.index`](#drishtiindex--the-multi-day-type-ahead-index). |
| `DRISHTI_BI_DIR` | `drishti.bi.dir` | `./config/bi` | Where Rupaka (BI) files live: `datasets/`, `reports/`, `boards/`, `notebooks/`, `python/`. Never inside a pack. |
| `DRISHTI_BI_NODE` | `drishti.bi.exec.node` | empty | This Java node's name when several Java nodes serve the site (`drishti.bi.exec.nodes` lists them); empty: one node. |
| `DRISHTI_BI_RUNS_FILE` | `drishti.bi.runs.file` | `./config/runs/runs.yaml` | The BI runs settings file ([BI runs](#bi-runs-configrunsrunsyaml)); a missing file means the defaults. |
| `DRISHTI_BI_MIGRATE_ON_START` | `drishti.bi.migrate.on-start` | `true` | At start, moves stored items once: every stored dataset and model into the table layer, then 2.3 boards into reports (each change audited, the text before kept in the item's history). `false`: the items stay unread until they are moved ([drishti.bi.migrate](#drishtibimigrate--migrations-of-stored-bi-items)). |
| `DRISHTI_BI_STORE` | `drishti.bi.query.store-dir` | `./data/bi-store` | The BI store: the typed Parquet layers (columns, rollups, live snapshots) the dataset query engine writes and reads, and DuckDB's spill folder. Rebuildable from the connectors; never commit it. |
| `DRISHTI_PACKS_DIR` | `drishti.packs.dir` | `./config/packs` | The packs directory. If it does not exist and the old `./packs` does, `./packs` is used with a warning (one release). |
| `DRISHTI_PACKS_EXTRA_DIRS` | `drishti.packs.extra-dirs` | empty | Further pack folders, comma-separated, looked in after the packs directory (the shipped packs win a name): `examples/packs` makes the retired example packs (finance, genomics, logistics, retail-banking, climate-risk, operational-risk, liquidity-risk) loadable by name ([PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#retired-example-packs-examplespacks)). |
| `DRISHTI_PACKS_OVERLAY` | `drishti.packs.overlay` | `./data/packs/added.yaml` | The file of packs an administrator loaded from Admin → Packs; imported at start (`spring.config.import`) and named again by `drishti.packs.overlay`. Written by the server, not by hand. |
| `DRISHTI_PACKS_SAMPLES` | `drishti.packs.samples` | `visible` | Who sees sample packs: `visible`, `developers` (author or admin) or `hidden`; Admin → Packs can override it. |
| `DRISHTI_PACKS_SAMPLES_FILE` | `drishti.packs.samples-file` | `./data/packs/samples-mode` | Where the Admin → Packs choice for sample packs is saved. |
| `DRISHTI_PACKS_WATCH` | `drishti.packs.watch` | `auto` | Live pack reload: `auto`, `poll` or `off`. |
| `DRISHTI_PACKS_INSTALLED` | `drishti.packs.installed-dir` | `./data/packs/installed` | Where packs installed from a registry are kept. |
| `DRISHTI_CONNECTORS_DIR` | `drishti.sources.connectors-dir` | `./config/connectors` | The connector folder: one YAML file per connector, the file name being the connector's name (Admin → Connectors). Watched; a change applies without a restart. See [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md). |
| `DRISHTI_PACKS_SETTINGS` | `drishti.packs.settings-dir` | `./data/packs/settings` | Legacy: where the earlier per-pack data-source override files were. Read once at start and migrated into connector files; the originals are kept in `migrated/`. |
| `DRISHTI_UNINSTALLED_DIR` | `drishti.admin.uninstalled-dir` | `./data/uninstalled` | Where Admin → Artifacts moves what it uninstalls: one folder per uninstall, `<timestamp>-<kind>-<name>/`, holding the artifact as it was. See [`drishti.admin`](#drishtiadmin--uninstalling-packs-connectors-and-bi-files-safely). |
| `DRISHTI_UNINSTALLED_KEEP` | `drishti.admin.uninstalled-keep` | `30d` | How long an uninstalled artifact is kept before it is removed (pruned once a day); `0` keeps them for ever. |
| `DRISHTI_LOADS_DIR` | `drishti.loads.dir` | `./data/loads` | The data-load history per pack and the administrators' expectation overrides ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)). |
| `DRISHTI_PACKS_DEPLOY_HISTORY` | `drishti.packs.deploy.history-file` | `./data/packs/deploy-history.jsonl` | The history of archive deployments and rollbacks. |
| `DRISHTI_PACK_REGISTRY` | `drishti.packs.registry.url` | empty | A signed pack registry (a folder, `file:` or `https:` URL with `index.json`); empty: none. |
| `DRISHTI_PACKS` | `drishti.packs.enabled` | `default` | The packs to load (comma list); a pack's parents load with it. `default`: the edition's packs (`drishti.edition`), else the seven shipped packs of `config/editions/default-packs.yaml` (the one default list). |
| `DRISHTI_DEFAULT_PACKS` | `drishti.packs.default-for-users` | empty | Packs new users get; empty: every installed pack. |
| `DRISHTI_SUGGEST_LIMIT` | `drishti.commands.suggest-limit` | `25` | Entries in the command line's dropdown. |

### Every placeholder in the console's `application.yaml`

From `drishti-console/config/application.yaml`, resolved from environment variables only.

| Variable | Sets | Default | What it does |
|---|---|---|---|
| `DRISHTI_CONSOLE_HOST` | `server.host` | `0.0.0.0` | The address the console listens on (every interface; `127.0.0.1` keeps it to this machine). |
| `DRISHTI_CONSOLE_PORT` | `server.port` | `17480` | The console's port. |
| `DRISHTI_CONSOLE_WORKERS` | `server.workers` | `4` | Processes serving the console's pages (they share the port; one Python process uses one core). `1` runs a single process. |
| `DRISHTI_CONSOLE_SHUTDOWN_SECONDS` | `server.shutdown_seconds` | `5` | On a stop, how long open streams (the live channel of reports, boards and views) may stay before they are closed; the browser says *Disconnected* and reconnects to the next console. |
| `DRISHTI_BACKEND_URL` | `backend.url` | `http://127.0.0.1:18480` | Where the server is. |
| `DRISHTI_BACKEND_URLS` | `backend.urls` | empty | Several servers of one Drishti*net cluster, comma-separated; one is enough (the console learns the rest). Empty: `backend.url` alone. See [The console and several servers](#the-console-and-several-servers-drishtinet). |
| `DRISHTI_BACKEND_DISCOVER` | `backend.discover` | `true` | With `backend.urls`: pull the member list from the server in use every `discover_every` and keep it. |
| `DRISHTI_CONSOLE_DATA_DIR` | `backend.data_dir` | empty | What the console keeps between runs (`net/servers.json`); empty: `<data dir>/console`. |
| `DRISHTI_CONSOLE_BACKEND_CHECK_SECONDS` | `backend.check_seconds` | `5` | How often the console asks each server if it is there (`0` = never). Pages show a banner and recover by themselves when it returns. |
| `DRISHTI_CONSOLE_TMP_DIR` | `tmp_dir` | empty | Base of the console's temp folder `drishti-console-<pid>` (empty: `DRISHTI_TMP_DIR`, then `<data dir>/tmp/console` when started by `run_drishti_web.py`), one per worker process, removed on exit; left-over ones of dead processes are listed on Health, never deleted automatically. |
| `DRISHTI_EXTERNAL_LIB` | `external_lib` | empty | Base of the external library folder (empty: `external_lib` in the repository root); `<base>/python` is importable. The server reads the same variable for `<base>/java`. |
| `DRISHTI_PRODUCT` | `ui.product` | `Drishti` | The product name shown in pages. |
| `DRISHTI_USER` | `ui.user` | `ash` | The acting user while sign-in is off. |
| `DRISHTI_ABOUT_PREFETCH` | `ui.about_prefetch` | `true` | Fetch *About this page* when a view goes idle, so labels with a glossary entry are underlined at once; costs one explain per page view. |
| `DRISHTI_VIEWS_REFRESH_MIN_SECONDS` | `views.refresh_min_seconds` | `5` | The fastest any panel refreshes (a Sutra's `refresh:`, [RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, section 9](../guides/RACHANA_REFERENCE.md#9-live-panels-refresh-control-and-freshness)), in seconds (1 to 3600): a Sutra or a reader asking for faster gets this. |
| `DRISHTI_UI_DESIGNER` | `ui.designer` | `false` | The Sutra designer: **Customize** on every entity page, **Describe** under Build, New screen, and the **Outline** and **Grid** modes ([RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31](../guides/RACHANA_REFERENCE.md#31-the-sutra-designer-customize-describe-outline-and-grid)). Off: pages and the Build workbench are unchanged. |
| `DRISHTI_HOME_FACTS` | `ui.facts.enabled` | `true` | Home's **Did you know** tile, the fact of the day for a signed-in user ([USER_GUIDE.md](../guides/USER_GUIDE.md#home-the-fact-of-the-day)). `false`: no tile. |
| `DRISHTI_EMBED_ENABLED` | `embed.enabled` | `false` | Serve `/embed/v1/` (Elements 1.x, HTML) and `/embed/v2/` (Elements 2.0 and the client-side renderer, the ViewModel as JSON) for host applications registered at the server (CLIENT_RENDERER.md). |
| `DRISHTI_EMBED_SIGN_IN` | `embed.sign_in.enabled` | `true` | The sign-in window of `auth: 'popup'` (`/embed/v2/sign-in`), through `auth.oidc`. |
| `DRISHTI_AUTH_ENABLED` | `auth.enabled` | `false` | Turn console sign-in on. |
| `DRISHTI_SESSION_SECRET` | `auth.session_secret` | empty | The session cookie secret (at least 32 characters); environment only. |
| `DRISHTI_TOKEN_SECRET` | `auth.token_secret` | empty | The token secret shared with the server's `drishti.security.secret`. |
| `DRISHTI_SESSION_RECHECK_SECONDS` | `auth.recheck_seconds` | `10` | How often a session is re-checked against the server. |
| `DRISHTI_SECURE_COOKIE` | `auth.secure_cookie` | `true` | Mark cookies `Secure` (`false` only for plain-HTTP development with sign-in on). |
| `DRISHTI_OIDC_ENABLED` | `auth.oidc.enabled` | `false` | Run the single-sign-on flow. |
| `DRISHTI_OIDC_ISSUER` | `auth.oidc.issuer` | empty | The OIDC issuer URL. |
| `DRISHTI_OIDC_CLIENT_ID` | `auth.oidc.client_id` | empty | The OIDC client id. |
| `DRISHTI_OIDC_CLIENT_SECRET` | `auth.oidc.client_secret` | empty | The OIDC client secret; empty for a public client (PKCE alone). |
| `DRISHTI_OIDC_REDIRECT_URI` | `auth.oidc.redirect_uri` | empty | The redirect URI; empty: `<console>/auth/oidc/callback`. |
| `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | `live.max_subscriptions` | `32` | Live subscriptions one browser may hold. |
| `DRISHTI_LABS_CALC` | `labs.calc` | `false` | Labs: offer Calc. |
| `DRISHTI_BI_ENABLED` | `bi.enabled` | `true` | Rupaka phase 1: the BI top-bar menu, the `/bi` pages and the `RUPAKA` keyword. The server has its own switch, `drishti.bi.enabled`. |
| `DRISHTI_BI_LIVE_MIN_INTERVAL_MS` | `bi.live.min_interval_ms` | `250` | Rupaka phase 5: a report or board redraws its live visuals at most this often, never two rounds at once; a burst of ticks is one redraw (100 to 5000). |
| `DRISHTI_BI_LIVE_STALE_SECONDS` | `bi.live.stale_seconds` | `30` | A live report or board that has heard nothing from the server (not even its 5-second status) for this long says **Stale** (5 to 600). |
| `DRISHTI_BI_BOARD_ROTATE_SECONDS` | `bi.board.rotate_seconds` | `30` | A board in kiosk mode turns to its next page after this long, unless the board sets `rotate:` (3 to 3600). |
| `DRISHTI_BI_NOTEBOOK_MAX_ROWS` | `bi.notebook.max_rows` | `10000` | The Notebook ([USER_GUIDE.md › The Notebook](../guides/USER_GUIDE.md#the-notebook)): the most rows of an extract (the reader's rows, cut here; 10 to 100000). |
| (no variable) | `bi.notebook.max_fields`, `sql_max_rows`, `sql_max_chars` | `60`, `5000`, `20000` | Most columns of an extract; most rows a SQL cell returns (run by the server); the longest SQL text. |
| (no variable) | `bi.notebook.max_extracts` | `6` | Most further extracts in one notebook, besides the first (`extract("name")` in a Python cell; `DRS-9072`). |
| (no variable) | `bi.notebook.cell_timeout_seconds`, `cell_memory_mb`, `cell_max_chars`, `max_cells` | `20`, `512`, `65536`, `60` | A Python cell's time and memory on the server's compute pool; the longest cell; the most cells in a notebook. |
| `DRISHTI_BI_TRANSFORM_PREVIEW_ROWS` | `bi.transform.preview_rows` | `1000` | The Transform editor ([BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17](../guides/BI_GUIDE.md#17-data-prep-the-transform-editor)): rows of each input its step-by-step run starts from, asked of the server as the person (10 to 50000; the server's `drishti.bi.transform.max-input-rows` also caps it). |
| (no variable) | `bi.transform.timeout_seconds`, `memory_mb`, `snippets_file` | `20`, `512`, empty | The step run's time and memory (also capped by `drishti.bi.report.python-max-seconds`, `python-max-memory-mb`); a file of more data-prep snippets read after `drishti-console/config/prep-snippets.yaml`. |
| `DRISHTI_LAYOUTS_ENABLED` | `layouts.enabled` | `true` | Offer layout mode. |
| `DRISHTI_PACKS_DIR` | `packs.dir` | `../config/packs` | The packs directory (a relative path resolves from `drishti-console/`). |
| `DRISHTI_PACKS_EXTRA_DIRS` | `packs.extra_dirs` | empty | Further pack folders, comma-separated, as on the server (relative paths resolve from `drishti-console/`, so `../examples/packs`). |
| `DRISHTI_PACKS_INSTALLED` | `packs.installed_dir` | empty | The server's installed packs (registry and Deploy archive); empty: `<data dir>/packs/installed`. Read first as the server does, so an installed pack's guides, examples and workspaces reach the console. |
| `DRISHTI_PACKS` | `packs.enabled` | `default` | Only used when the server cannot be asked which packs are on; `default`: `config/editions/default-packs.yaml`. |
| `DRISHTI_EDITIONS_DIR` | `editions.dir` | `../config/editions` | The folder of the edition files, the same the server reads (relative to `drishti-console/`). |
| `DRISHTI_LAKE_ROOTS` | `lakes.roots` | empty | Admin → Data loads → Lakes ([TOOLS.md](../guides/TOOLS.md#4-lake-build-a-large-json-export-into-a-delta-lake)): the lake roots this console may write, comma-separated (relative paths resolve from `drishti-console/`); empty: `<data dir>/delta`, the server's own root. The page offers no other folder. |
| `DRISHTI_JAR` | `lakes.jar` | empty | The drishti-server exec jar whose Java writer the page's builds use (`lakes.engine: auto`); empty: the newest `drishti-server/target/drishti-server-*-exec.jar`, else the Python `deltalake` package. |
| `DRISHTI_DATA_DIR` | `lakes.data_dir` | `../data` | Admin → Lakes → Maintain ([OPERATIONS.md](OPERATIONS.md#20-lake-maintenance-admin--lakes--maintain-and-lake-maintain)): the data folder whose `lakes/lake-maintenance.yaml` (the file `lake maintain` reads by default) and `lakes/maintenance-history.jsonl` the page uses (relative paths resolve from `drishti-console/`). |
| `DRISHTI_LAKE_MAINTENANCE_SCHEDULES` | `lakes.maintenance_schedules` | `true` | Run the lake-maintenance schedules in this console; `false` when `lake maintain --daemon` runs them instead (run them in one place). |

In `lake-maintenance.yaml`: a lake's `format` is `delta` (the default), `iceberg`, or `auto` (Iceberg tables under a local root
found beside Delta ones); `orphan-hours` (72, the least allowed) is read for Iceberg tables only; `iceberg-settings` (a mapping) is
passed to an Iceberg lake's maintenance as `--set key=value` (a REST catalog such as Glue: `catalog`, `uri`, `warehouse`,
`catalog.rest.*`). [OPERATIONS.md › Lake maintenance: Admin → Lakes → Maintain and `lake maintain`, section 8](OPERATIONS.md#8-iceberg-lakes). `lake build --format delta|iceberg` (default
`delta`) and `--iceberg-set KEY=VALUE` choose and configure the format of a build ([TOOLS.md](../guides/TOOLS.md)).
| (no variable) | `lakes.max_upload_mb`, `scan_docs`, `engine`, `python`, `keep_min`, `jobs` | `4096`, `20000`, `auto`, the console's Python, `240`, `2` | Largest upload (streamed to the console's temp folder); documents read to suggest the id, date and classifiers; `auto`, `drishti` or `deltalake`; the Python that runs `tools/lake/build.py`; minutes an upload and a finished build are kept; builds at once. |
| `DRISHTI_AI_ENABLED` | `ai.enabled` | `false` | Turn on the console's own LLM provider layer (the recommended provider is `type: server`, which delegates to the server so keys stay there). |
| `DRISHTI_AI_DIR` | `ai.dir` | `./config/ai` | The folder of provider files (the same format and, if you share the folder, the same files as the server). |
| `DRISHTI_AI_DEFAULT_MODEL` | `ai.default_model` | empty | The model used when a call names none (`provider/model`). |
| `DRISHTI_MCP_ENABLED` | `mcp.enabled` | `false` | Turn on the console's MCP client. |
| `DRISHTI_MCP_DIR` | `mcp.dir` | `./config/mcp` | The folder of MCP server files. |
| `DRISHTI_RETRY_ENABLED` | `retry.enabled` | `true` | Try again what failed (the console's AI providers and MCP servers); `false` turns the retries off. |
| `DRISHTI_RETRY_INITIAL` | `retry.initial` | `5s` | Wait before the first retry. |
| `DRISHTI_RETRY_MAX` | `retry.max` | `5m` | No wait is longer than this. |
Every `${NAME:default}` of the server's and the console's `application.yaml` (and of every other file) is in the generated reference:
the *Env var* column of [the server's](#the-server-applicationyaml) and [the console's](#the-console-applicationyaml) tables names the
variable beside its key and default, and the [environment variable index](#environment-variable-index) lists every variable once, with
what it sets. Profile files (`application-files.yaml`, `application-postgres.yaml`, …) and packs have placeholders of their own, in the
index too and explained in [Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles).

### Retry settings

What failed is tried again by itself, with exponential backoff and jitter; see [OPERATIONS.md](OPERATIONS.md#retries-what-failed-is-tried-again-by-itself).
The server reads `drishti.retry.*` (packs refused, connectors, AI providers, MCP servers) and the console reads `retry.*` (its own AI
providers and MCP servers; server names with hyphens, console names with underscores). A per-area override replaces any key for one
area: `drishti.retry.<area>.<key>` on the server (areas `packs`, `connectors`, `ai`, `mcp`, `distribution`, `bi`, `alerts`) and `retry.<area>.<key>` in the console
(areas `ai`, `mcp`).

| Server | Console | Default | Meaning |
|---|---|---|---|
| `drishti.retry.enabled` | `retry.enabled` | `true` | Retry at all; `false` leaves a failure to the next file change or an administrator's Retry now |
| `drishti.retry.initial` | `retry.initial` | `5s` | Wait before the first retry (`500ms`, `5s`, `2m`, `1h`) |
| `drishti.retry.factor` | `retry.factor` | `2.0` | Each further wait is this many times the one before |
| `drishti.retry.max` | `retry.max` | `5m` | No wait is longer than this (and a permanent failure waits this long) |
| `drishti.retry.jitter` | `retry.jitter` | `0.2` | Each wait varies by up to this fraction either way (`0` = none), so many failing things do not retry together |
| `drishti.retry.permanent-after` | `retry.permanent_after` | `3` | A failure waiting cannot fix (refused credentials, a setting that is wrong) seen this many times in a row is treated as permanent and retried only every `max` |
| `drishti.retry.<area>.<key>` | `retry.<area>.<key>` | the general value | Per-area override of any key above |
| `drishti.retry.alerts.*` | (none) | the general value | The retries of an alert's email, webhook and text; how many tries is `drishti.alerts.max-attempts` (below). A pending retry is kept in memory: a restart drops it and the alert's history keeps the last text it was given |
| (none) | `ai.poll_seconds` | `5` | How often the console looks at `config/ai` and `config/mcp` for a changed file and for a retry that is due |

### `drishti.db.pool` — database connection pools

Every database connection goes through a pool of a size you set, **20 by default**, and is borrowed for one unit of work (a query, a batch, a day's
publish) and returned at once: never held across a stream to a client, a network wait, a live subscription or user think-time. This section is the site
default; a connector's own `pool-*` keys (in its file under `config/connectors/`) win over it, and the shipped example files simply inherit it. Read once
at start; a connector's own keys are live-reloaded with its file. Full guide: [CONNECTOR_FILES.md, Connection pools](../connectors/CONNECTOR_FILES.md#14-connection-pools).

| Setting | Default | Meaning |
|---|---|---|
| `size` (connector: `pool-size`) | `20` | The most connections the connector opens at once. A caller beyond it waits `pool-connection-timeout`. |
| `min-idle` (`pool-min-idle`) | `2` | Idle connections kept when quiet. |
| `idle-timeout` (`pool-idle-timeout`) | `10m` | An idle connection beyond `pool-min-idle` is closed after this long. |
| `max-lifetime` (`pool-max-lifetime`) | `30m` | A connection is replaced after this long, so a firewall or a failover never meets an old one. |
| `connection-timeout` (`pool-connection-timeout`) | `10s` | How long a caller waits for a free connection before it fails (`no free connection in the <name> pool within 10000 ms (20 of 20 in use, 3 waiting, longest borrow 41500 ms)`). |
| `leak-threshold` (`pool-leak-threshold`) | `60s` | A connection borrowed longer than this is logged once as a WARNING with the stack of the code that borrowed it (`0` turns it off). |
| `statement-timeout` (`statement-timeout`) | `5m` | The longest one statement or operation may run (PostgreSQL `statement_timeout`; MongoDB socket read timeout; Aerospike read and write `totalTimeout`; `0` for none). |

Applies to the JDBC/PostgreSQL connector and its loader, MongoDB, Redis, Aerospike and the identity database on PostgreSQL. Not pooled, by design:
**SQLite** (the default identity database) and **DuckDB**, embedded files opened inside the server process, with no network, no server connection limit and
nothing to exhaust; they still close everything they open (an architecture test checks it). A time is seconds, or a number with `ms`, `s`, `m` or `h`.
A value that does not parse stops the start naming the setting. Sizing: the database's `max_connections` must cover the sum of the pools of every
server instance ([OPERATIONS.md, Sizing the connection pools](OPERATIONS.md#sizing-the-database-connection-pools)). Environment: the loaders read
`DRISHTI_DB_POOL_SIZE`.

### `drishti.http.proxy` — the site's outbound HTTP proxy

Every HTTP-based connector (REST, feeds, news, yfinance, S3, Delta and Iceberg on S3, the Kafka schema registry), AI provider and MCP server reaches its
target through this proxy unless its own file has a `proxy:` block or `proxy: none`. Off by default. `http://` and `https://` targets both go through an
`http://` proxy (https via `CONNECT`, keeping the connector's `tls.*`). Read once at start. The reference, with the no-proxy entries, the masking, what each connector does and
the troubleshooting table, is [TLS.md (proxy)](../connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers).

| Setting | Default | Meaning |
|---|---|---|
| `drishti.http.proxy.url` | empty (off) | The proxy, `http://host:port`. `https://proxy` is refused (a proxy reached over TLS is not supported). |
| `drishti.http.proxy.username`, `drishti.http.proxy.password` | empty | Basic authentication; give both or neither. The password is an `${ENV}` placeholder or the environment variable `DRISHTI_HTTP_PROXY_PASSWORD`; a literal written in a configuration file stops the server at start. |
| `drishti.http.proxy.no-proxy` | empty list | Hosts that go direct: names (and their sub-domains), `.suffix`, addresses, CIDR blocks, `*`. |
| `drishti.http.proxy.enabled` | `true` | `false` keeps the settings but switches the site default off. |
| `drishti.http.proxy.from-environment` | `false` | When no `url` is set, honour the standard `HTTPS_PROXY`, `HTTP_PROXY` and `NO_PROXY` variables (by the target's scheme). |

The console's AI and MCP clients read the same `DRISHTI_HTTP_PROXY_URL`, `_USERNAME`, `_PASSWORD`, `_NO_PROXY`, `_ENABLED` and `_FROM_ENVIRONMENT` environment variables. A connector's
own `proxy.*` block is a connector setting (a row of `proxy.*` in each connector's settings table), not a server setting.

### `drishti.offline` — offline mode (no internet)

For a machine with no internet, such as a demonstration laptop: with `DRISHTI_OFFLINE=true` no connector of an internet plugin starts,
whatever its file says, so nothing tries the network, nothing waits out a timeout and no connector turns health red. Such a connector is
reported in the state `OFFLINE`, shown as **off (offline mode)** in Admin → Connectors (no problem, no retry); its kinds are served by
the other connectors that hold them. The shipped packs keep a snapshot for exactly that: the news-and-markets pack's `news-store` serves
`quote` and `news` from the market snapshot committed in `data/testdata/news-and-markets` (built into the lake by `drishti.py lake demo`;
[data/testdata/README.md](../../data/testdata/README.md#the-market-snapshot-news-and-markets-offline)), and the market-data pack's
`market-store` serves the kinds of its public-data feeds. The run configuration **Drishti server (demo lakes)** sets it. Read once at
start: a connector file saved in Admin while the server runs cannot start an internet connector either; restart without it to go live.

The same holds with offline mode **off** for a connector of an internet plugin whose file switches it off (its shipped state, such as
`DRISHTI_NEWS_ENABLED` unset): its kinds are read from the other connectors that serve them, exactly as in offline mode, and Admin →
Connectors shows it as **off (served from the snapshot)** with those connectors in `servedBy` (`GET /api/v1/admin/connectors`). An entity
none of them holds is `DRS-1001` naming them (and saying so when they have no table for the kind yet), not `DRS-1002` "connector news is
disabled".

| Setting | Default | Meaning |
|---|---|---|
| `drishti.offline.enabled` | `false` (`DRISHTI_OFFLINE`) | `true`, `on`, `yes` or `1` turns offline mode on. |
| `drishti.offline.plugins` | `news,yfinance,feed` | The plugins whose connectors reach the internet (comma-separated, by plugin name, not connector name): Google News, Yahoo Finance, and the public-data feeds (US Treasury, ECB, NY Fed, FRED). Add `rest` or another plugin when a site's connectors of it reach the internet too. |

### `drishti.net` — Drishti*net, the cluster folder

Several servers acting as one ([OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one), the design [CLUSTER_DEPLOYMENT.md](../architecture/CLUSTER_DEPLOYMENT.md#appendix-a-drishtinet-many-servers-one-drishti)). Like connectors, Drishti*net is
configured in a folder of small YAML files, never in `application.yaml`, which says only where the folder is and how it is watched. Without the
folder, or while its `node.yaml` does not say `enabled: true`, the server is a single server.

| Setting | Default | Meaning |
|---|---|---|
| `drishti.net.dir` | `./config/net` (`DRISHTI_NET_DIR`) | The folder; created on the first write (Admin → Net, `drishti.py net`). Ready-made files are in `config/net.examples/`. |
| `drishti.net.watch` | `auto` (`DRISHTI_NET_WATCH`) | `auto`: file events, polling as a fallback; `poll`: polling only; `off`: read at start only. |
| `drishti.net.poll` | `5s` | How often the folder is looked at when polling (and, with events, at least this often anyway). |

The files, each checked against its schema (a refused file keeps the previous settings in force and shows its error in Admin → Net):

| File | Settings (default) |
|---|---|
| `node.yaml` | `enabled` (`false`), `node-id` (generated once, kept in `<data dir>/net/node-id`), `advertise-url` (this machine's primary IPv4 address with `server.port`; never `localhost`, `127.x.x.x`, `::1`, `0.0.0.0` or `::`), `tags` (`[]`), `peer-only-packs` (`[]`), `secret: ${DRISHTI_NET_SECRET}` and `previous-secret: ${DRISHTI_NET_PREVIOUS_SECRET}` (environment references only, at least 32 bytes), `tls.*` (mutual TLS), `gossip-port` (`auto`: a free port of its own for gossip, probes and digests, advertised through gossip; a port number; or `off`: the server's port. [OPERATIONS.md › Drishti*net: running several servers as one, section 4](OPERATIONS.md#4-membership-how-the-nodes-find-each-other)) |
| `peers/<name>.yaml` | `url` (a real address, as `advertise-url`), `tags`, `tls.*` (one file per seed) |
| `gossip.yaml` | `interval` (`1s`), `fanout` (`3`), `suspect-after` (`5s`), `indirect-probes` (`2`), `dead-after` (`30s`), `forget-after` (`24h`), `persist-every` (`10m`) |
| `routing.yaml` | `forward-timeout` (`5s`), `scatter-budget` (`3s`), `suggest-budget` (`150ms`), `link-budget` (`1s`: a view's linked entity that only a peer holds), `max-hops` (`1`; `0` forwards nothing), `weights.latency` (`1.0`), `user-state` (`replicate` or `off`: user state and console sign-in sessions), `join-wait` (`2s`), `join-grace` (`30s`), `peer-protocol` (`http2`: peer data multiplexed over one HTTP/2 connection per peer, a peer without it answered in HTTP/1.1; or `http1`) |
| `access.yaml` | `roles: { <role>: { nodes: [<node id or tag>, ...] } }` (a role not listed reaches every node) |

`enabled: true` without the key is refused at start and at every reload, as the server refuses a missing token secret with security on; so is
an `advertise-url` or a seed `url` that is a loopback or wildcard address (the message names this machine's address to use instead), and
`enabled: true` without `advertise-url` on a machine with no address but loopback. Members advertise only real addresses
([OPERATIONS.md › Drishti*net: running several servers as one, section 2](OPERATIONS.md#2-a-cluster-of-three)). What gossip learns (the member table) is state, kept in `<data dir>/net/peers.json`, never in this folder. Every file in
[OPERATIONS.md › Drishti*net: running several servers as one, section 3](OPERATIONS.md#3-the-files-of-confignet).

### The console and several servers (Drishti*net)

With Drishti*net on ([OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one)), the console takes several servers instead of one, so it always has one to fall back to:

```yaml
# drishti-console/config/application.yaml
backend:
  urls: ${DRISHTI_BACKEND_URLS:}       # e.g. https://drishti-a:8480 (one is enough; empty: backend.url)
  discover: true                       # pull the cluster's member list from the server in use and keep it
  discover_every: 60s
  check_every: 5s
  fail_after: 2
```

Each session (a browser's sign-in) keeps to one server while it answers: the configured ones first, then the discovered ones nearest first. A server
that refuses connections, times out or answers 502/503 `fail_after` times in a row (requests or health checks) is set aside and the session moves to
the next; a read that failed on the way is tried once on another server, a write never is (the page says it did not save). The list is saved to
`<console data dir>/net/servers.json`; a member the cluster reports dead or left is dropped from it, the configured `urls` never are. A set-aside
server that answers again is used for new sessions after a minute. Every server needs the same token secret (or OIDC issuer), so the session
carries over. Without Drishti*net, `backend.urls` still works as a list of identical servers.

### AI and MCP policy settings

The server's `drishti.ai.policy.*` and `drishti.mcp.*` settings and the console's `ai.policy.*` and `mcp.*` settings
(server names with hyphens, console names with underscores). Everything is off unless `drishti.ai.enabled` /
`drishti.mcp.enabled` (console `ai.enabled` / `mcp.enabled`) is true; see [AI.md](AI.md#1-model-providers)
and [AI.md](AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy).

| Server | Console | Default | Meaning |
|---|---|---|---|
| `drishti.ai.policy.mask-redacted` | `ai.policy.mask_redacted` | `true` | Mask the `drishti.security.redact` fields (and `mask-patterns`) in every prompt |
| `drishti.ai.policy.mask-patterns` | `ai.policy.mask_patterns` | `[]` | Extra regular expressions replaced by the mask in every prompt |
| `drishti.ai.policy.audit` | `ai.policy.audit` | `true` | One access-log row per call (who, feature, model, sizes, tokens, outcome; never the text) |
| `drishti.ai.policy.per-user-per-minute` | `ai.policy.per_user_per_minute` | `0` | Calls a user may make per minute (`0` = no limit; over it: `DRS-4017`) |
| `drishti.ai.policy.per-user-per-day` | `ai.policy.per_user_per_day` | `0` | Calls a user may make per day (`0` = no limit) |
| `drishti.ai.policy.cost-per-user-per-day` | `ai.policy.cost_per_user_per_day` | `0` | Cost a user may spend per day, in the unit of the models' `cost-per-1k-*` prices (`0` = no limit) |
| `drishti.ai.policy.features` | `ai.policy.features` | `{}` | Per feature: allowed roles and its own limits, e.g. `"[ask]": {roles: [risk], per-user-per-minute: 6}` (refused: `DRS-4018`) |
| `drishti.mcp.server-roles` | `mcp.server_roles` | `{}` | Per MCP server, the roles allowed (absent: every signed-in user) |
| `drishti.mcp.tool-roles` | `mcp.tool_roles` | `{}` | Per `server/tool`, the roles allowed |
| `drishti.mcp.server-features` | `mcp.server_features` | `{}` | Per MCP server, the features allowed to call it |


---

## Value formats

| Type | Written as | Examples |
|---|---|---|
| Duration | a number with a unit: `ms`, `s`, `m`, `h`, `d` (or ISO-8601 `PT2S`) | `50ms`, `2s`, `15m` |
| Period (dates) | ISO-8601 period | `P5Y` (five years), `P18M`, `P90D` |
| Boolean | `true` / `false` | `true` |
| List | YAML list, or a comma-separated string (handy in env vars) | `[market-risk, trading]` or `market-risk,trading` |
| Map | YAML mapping | `routes: { trade: trade-endur }` |
| Plugin and connector settings | always strings; quote numbers if you like | `pool-size: "4"` or `pool-size: 4` |

---

## Common recipes

### Change the port

```bash
DRISHTI_PORT=18490 java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

You should see `Tomcat started on port 18490` in the log. Point the console at it with
`DRISHTI_BACKEND_URL=http://127.0.0.1:18490`.

### Load more packs

```bash
DRISHTI_PACKS=market-risk,world-economy java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

Packs a listed pack `extends:` (or the older `requires:`) are loaded too, dependencies first. Then
`curl -s localhost:18480/api/v1/packs` lists each loaded pack. If the console runs from another directory,
set `DRISHTI_PACKS_DIR` to an absolute path so both programs find the same folder.

### Turn security on (production)

```bash
export DRISHTI_SECURITY_ENABLED=true
export DRISHTI_TOKEN_SECRET="$(openssl rand -base64 48)"   # at least 32 bytes; give the console the same value
export DRISHTI_SEED_ADMIN=false
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

The console needs `DRISHTI_AUTH_ENABLED=true`, the same `DRISHTI_TOKEN_SECRET`, and its own
`DRISHTI_SESSION_SECRET` (at least 32 characters). With security on, a call without a token is refused:

```bash
curl -s localhost:18480/api/v1/packs
```

You should see an RFC 7807 problem with status 401. A secret shorter than 32 bytes stops the server at
start-up with `drishti.security.secret must be at least 32 bytes when security is enabled`.

### Send one kind to a different source

```yaml
# application.local.yaml
drishti:
  sources:
    routes:
      trade: trade-endur       # a connector or plugin name
```

### Add a site Delta Lake connector

Create the file `config/connectors/ops-lake.yaml` (the file name is the connector's name):

```yaml
plugin: delta
kinds: [counterparty]
settings:
  root: /data/lake          # or s3a://my-bucket/lake
  domain: reference
  mode.counterparty: effective
```

No restart: the folder is watched, so within a few seconds `curl -s localhost:18480/api/v1/sources` shows an entry named
`ops-lake`. Route a kind to it with `drishti.sources.routes.shipment: ops-lake` or from a pack's `routes:`. The same can be done in
Admin → Connectors. Defining the connector under `drishti.sources.connectors` in `application.yaml` still works but is deprecated
(see below).

### Use a different holiday calendar

```bash
DRISHTI_CALENDAR=USNY+GBLO java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

`curl -s localhost:18480/api/v1/business-date` then shows `"calendar":"USNY+GBLO"`.

---

## Server reference

Each table lists the key relative to its section, the default (and the named environment variable, if any),
and what it does. "From packs" means the bundled file leaves it empty and the enabled packs fill it in.

### `drishti.sources` — where data comes from

```yaml
drishti:
  sources:
    fetch-timeout: 10s
    plugin-dir: /opt/drishti/plugins
    default-route: demo
    routes: { trade: trade-endur }
    plugins:
      rest: { enabled: true, settings: { base-url: "https://risk.internal/api" } }
    connectors:                  # deprecated: use config/connectors/<name>.yaml
      risk-lake: { plugin: delta, settings: { root: /data/lake, domain: risk } }
    connectors-dir: ./config/connectors
    connectors-watch: auto
    connectors-poll: 5s
    connectors-drain: 3s
    connectors-generate: true
```

| Key | Default | Meaning |
|---|---|---|
| `fetch-timeout` | `10s` | The longest a single source read may take (`DRS-1004`). Slower reads show the entity as unavailable instead of holding the view; a panel's own `source:` read past it, or failing, shows the panel's failed state (connector and why), never its empty text. Keep the console's `backend.timeout_seconds` (15) longer. |
| `plugin-dir` | empty (`DRISHTI_PLUGIN_DIR`) | A directory of extra plugin jars. Each jar gets its own class loader. Plugins on the class path are always found. |
| `default-route` | `demo` | The plugin or connector tried first for a kind that has no route. |
| `routes.<kind>` | `{}` plus from packs | Which plugin or connector serves a kind. Packs route their kinds to their connectors (`trade: trade-endur`). |
| `plugins.<name>.enabled` | `true` for any plugin not listed | Whether the plugin runs as itself (one instance). A plugin used only through `connectors` does not also run as itself unless it is listed here. |
| `plugins.<name>.settings.*` | per plugin | Settings for that single instance; see [connector settings](#connector-settings-plugin-by-plugin). |
| `connectors-dir` | `./config/connectors` (`DRISHTI_CONNECTORS_DIR`) | The folder of connector files: one `<name>.yaml` per connector, the file name being the connector's name. Created on the first write. Details: [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md). |
| `connectors-watch` | `auto` | How the folder is watched. `auto`: file events, falling back to polling; `poll`: polling only; `off`: the files are read at start only (a change then needs a restart). |
| `connectors-poll` | `5s` | How often the files are looked at: the fallback when file events are unavailable, and a safety net beside them. |
| `connectors-drain` | `3s` | A connector being restarted or stopped stays open this long so that reads in flight finish. |
| `connectors-generate` | `true` | At the first start, write a connector file from the pack's template when no file of that name exists. Never overwrites a file. `false`: packs' templates are used in memory only and nothing is written. |
| `connectors.<name>.plugin` | required (**deprecated**: put the connector in `connectors-dir`) | A named instance of a plugin (`delta`, `jdbc`, `file`, `rest`, `kafka`, `aerospike`, `cassandra`, `redis`, `mongodb`, `iceberg`, `iceberg-external`, `duckdb`, `activemq`, `rabbitmq`, `s3`, `feed`, `news`, `yfinance`). Run a plugin as often as you like. |
| `connectors.<name>.enabled` | `true` | Switch one connector off without deleting it. |
| `connectors.<name>.kinds` | what the plugin reports | Restrict or declare the kinds it serves. |
| `connectors.<name>.settings.*` | per plugin | Settings for this instance. `source-name` defaults to the connector's name. |

**Deprecated: `connectors.<name>` in `application.yaml`.** Connectors are site resources kept one file each in `connectors-dir`; a pack
only names them (its `connectors:` mapping is read as a template from which a missing file is generated). Connectors still defined
under `drishti.sources.connectors` (in `application.yaml`, an `application-<profile>.yaml` or environment variables such as
`DRISHTI_SOURCES_CONNECTORS_<NAME>_SETTINGS_<KEY>`) keep working and override the file of the same name setting by setting, but the
server logs a warning at start for each, naming the file that is equivalent, and Admin → Connectors flags them. The per-store
profiles (`postgres`, `duckdb`, `aerospike`, `mongodb`, `iceberg`, `redis`, `files`) are deprecated in the same way; their equivalents are
the example files in `config/connectors.examples/<store>/`. A file in `connectors-dir` replaces the pack's template wholesale.
Scope `packs:admin` also covers writes to `/api/v1/admin/connectors/**`. Full guide: [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).

What the bundled file sets under `plugins`:

| Plugin | `enabled` default | Bundled settings |
|---|---|---|
| `demo` | `true` (`DRISHTI_DEMO_ENABLED`) | sample directories come from the packs |
| `file` | `true` | `root: ${DRISHTI_FEEDS:${drishti.data.dir:./data}/feeds}`, `source-name: feed-file`, `rescan-seconds: 30` |
| `rest` | `false` (`DRISHTI_REST_ENABLED`) | `base-url: ${DRISHTI_REST_URL:http://localhost:9000/api}`, `path: /{kind}/{id}`, `source-name: rest`, `timeout-ms: 2000` |
| `jdbc` | `false` (`DRISHTI_JDBC_ENABLED`) | `url`, `user`, `password` from `DRISHTI_JDBC_URL`, `DRISHTI_JDBC_USER`, `DRISHTI_JDBC_PASSWORD`; `source-name: jdbc` (`pool-size` is inherited from [`drishti.db.pool`](#drishtidbpool--database-connection-pools)) |
| `delta` | `false` | runs only as named connectors (the packs declare one per data domain) |
| `aerospike` | `false` | runs only as named connectors (profile `aerospike`) |
| `cassandra` | `false` | runs only as named connectors (`config/connectors.examples/cassandra/`) |
| `activemq` | `false` (`DRISHTI_ACTIVEMQ_ENABLED`) | configure it, or use it as a named connector |
| `rabbitmq` | `false` (`DRISHTI_RABBITMQ_ENABLED`) | configure it, or use it as a named connector |
| `s3` | `false` (`DRISHTI_S3_ENABLED`) | configure it, or use it as a named connector |

### `drishti.business-date` — which day you are looking at

Every read is for a business date. The default date is "today" in `zone`, rolled back over weekends and
holidays of `calendar`. Users can pick another date in the top bar.

| Key | Default | Meaning |
|---|---|---|
| `calendar` | `USNY` (`DRISHTI_CALENDAR`) | `USNY`, `GBLO`, `EUTA`, `JPTO`, `CATO` (Toronto: a weekend holiday is kept the Monday after), `WEEKENDS` (weekends only), or a joint calendar such as `USNY+GBLO`. An unknown name stops start-up with `unknown calendar …`. |
| `zone` | `America/New_York` | Whose clock decides what "today" is. Any Java zone id (`Europe/London`). |
| `history` | `P5Y` | How far back users may pick a date. |

Example: a London desk.

```yaml
drishti:
  business-date:
    calendar: GBLO
    zone: Europe/London
    history: P2Y
```

### `drishti.history` — history queries

`TRD 1000015 30 <GO>` shows an entity over the business days it has data
([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time), `GET /api/v1/history/{kind}/{id}`). These limits follow a settings reload
without a restart. A pack's `history:` block can lower `max-days` and set `default-days` per kind ([PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which)).

| Key | Default | Meaning |
|---|---|---|
| `drishti.history.enabled` | `true` | Off: no history is read and the endpoint answers `DRS-1012`. |
| `drishti.history.default-days` | `30` | The range when a link or the CLI names none. |
| `drishti.history.max-days` | `366` | The most business days one request may span; more is `DRS-4021`. A pack's `max-days` can only lower it. |
| `drishti.history.max-rows` | `1000` | The most rows returned; the oldest are dropped and the answer says `truncated`. |
| `drishti.history.max-columns` | `200` | The most columns. |
| `drishti.history.timeout` | `10s` | The whole request. Days not read by then are listed in `problems` and the answer is `partial` (`DRS-1014` when no day was read). |
| `drishti.history.parallel-days` | `8` | Days read at once per request by the default per-day loop (connectors with a fast path read their own way). |
| `drishti.history.max-concurrent` | `16` | History requests running at once on this server; the next waits up to `timeout`, then `DRS-1014`. |
| `drishti.history.cache-mb` | `256` | Heap for cached answers, one budget for every key and caller signature (256, after soak 4: 64 MB held so few answers that the busiest server hit 1 %; lower it on a small heap; the least recently used go first; an entry counts the heap it really takes, about 2.3 to 3.2 times the earlier estimate). An entry is keyed by the kind's data version, so a new load never serves stale rows; a kind whose connector gives no version is not cached. Admin → Cache → `history` empties it. |

### `drishti.packs` — domain packs

| Key | Default | Meaning |
|---|---|---|
| `dir` | `./config/packs` (`DRISHTI_PACKS_DIR`) | The folder holding one sub-folder per pack. Relative to the server's working directory. The console reads the same variable (its default `../config/packs` is relative to `drishti-console/`), so use an absolute path when you set it. |
| `extra-dirs` | empty (`DRISHTI_PACKS_EXTRA_DIRS`) | Further pack folders, comma-separated, looked in after `dir`; `examples/packs` holds the retired example packs. A pack there may extend the shipped ones. |
| `enabled` | `default` (`DRISHTI_PACKS`): the edition's packs, else `config/editions/default-packs.yaml` (the seven shipped packs) | Comma-separated packs to load, in order. Packs they `extends:` (or the older `requires:`) are loaded as well. A missing pack stops start-up with a clear message. |
| `default-for-users` | empty = every installed pack (`DRISHTI_DEFAULT_PACKS`) | The packs a user sees until an admin assigns packs to them. |
| `samples` | `visible` (`DRISHTI_PACKS_SAMPLES`) | Who sees sample packs (`sample: true` in `pack.yaml`): `visible` (everyone), `developers` (only users with the author or admin power; use this once real packs exist) or `hidden` (nobody; sample packs are not loaded and their connectors do not start). The setting an administrator saves in Admin → Packs overrides it. |
| `samples-file` | `./data/packs/samples-mode` (`DRISHTI_PACKS_SAMPLES_FILE`) | Where that saved setting is kept (one word). Delete it, or press *Use the configuration*, to let `samples` decide again. |
| `watch` | `auto` (`DRISHTI_PACKS_WATCH`) | Live pack reload: `auto` (file events in `dir` and `installed-dir`, polling as the safety net), `poll`, or `off` (only Admin → Packs changes packs while the server runs). |
| `poll` | `5s` | How often the pack folders are looked at (also the safety net beside file events). |
| `reload-debounce` | `750ms` | A burst of changes is acted on once the folders have been quiet this long. |

The pack loader also writes some keys for the rest of the server (`drishti.packs.loaded`,
`drishti.packs.kinds.*`, `drishti.packs.overrides`, `drishti.rachana.pack-dirs`,
`drishti.rachana.pack-formats-files`, `drishti.inference.pack-semantics-files`,
`drishti.sources.plugins.demo.settings.dirs`). Do not set them yourself. See [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which).

### `drishti.packs.registry` — the signed pack registry

| Key | Default | Meaning |
|---|---|---|
| `drishti.packs.installed-dir` | `./data/packs/installed` (`DRISHTI_PACKS_INSTALLED`) | Where registry installs go; packs here win over `drishti.packs.dir`. Replaced versions are kept under `previous-versions/`. |
| `url` | empty (`DRISHTI_PACK_REGISTRY`) | A folder, `file:` or `https:` URL holding `index.json` and the archives. Empty: no registry. |
| `trusted-keys.<publisher>` | none | The publisher's Ed25519 public key (base64 DER, as `tools/packreg/packreg.py keygen` prints it). Packs signed by anyone else are refused. |
| `max-archive-mb` / `max-unpacked-mb` | `50` / `200` | Size limits for an archive and for what it unpacks to. |
| `allow-http` | `false` | Accept plain `http:` registries. For tests only. |

See [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#a-signed-pack-registry-publishing-and-installing).

### `drishti.packs.deploy` and `drishti.packs.settings-dir` — deploy an archive, and the legacy data-source overrides

Admin → Packs → **Deploy an archive** ([OPERATIONALISING.md, section 17](../guides/OPERATIONALISING.md#17-deploy-from-admin--packs-connectors-history-and-roll-back)). The **Data source** panel is now a read-only view of the pack's connectors, with links to Admin → Connectors.
An uploaded archive (the pack only, never data) is checked, previewed against the running version, swapped into `drishti.packs.installed-dir` and loaded with the in-place restart.

| Key | Default | Meaning |
|---|---|---|
| `DRISHTI_PACKS_SETTINGS` | `drishti.packs.settings-dir` | `./data/packs/settings` (`DRISHTI_PACKS_SETTINGS`) | **Legacy, migrated.** The folder where the earlier Admin → Packs → Data source panel wrote one `<pack>.yaml` per pack (connector `enabled` and `settings`). At the first start after the upgrade each override is moved into the connector file of the same name (applied on top of the existing file or, when there is none, of the pack's template); the original is kept in `<settings-dir>/migrated/<pack>.yaml.<timestamp>`, the file's earlier text goes to `<data dir>/connectors/history/`, and the step is audited as `connector-migrated`. An override that cannot be placed (no file and no template say which plugin) is left in place and reported. Nothing writes to this folder any more; it is read only for the migration. **Precedence, highest first: `drishti.sources.connectors` in the site configuration (deprecated), the connector file, the pack's template.** Credentials may only be `${ENV_NAME}` references. |
| `drishti.packs.deploy.max-archive-mb` | `50` | The largest archive accepted (HTTP 413 above it, decided from the declared length before the body is read). The console's own limit is `packs.deploy_max_mb` in its `application.yaml`; keep it at or under this. |
| `drishti.packs.deploy.max-unpacked-mb` / `max-files` | `200` / `10000` | What an archive may unpack to (a zip bomb stops here). |
| `drishti.packs.deploy.keep-versions` | `5` | Previous versions kept per pack under `<installed-dir>/previous-versions/<pack>/` for a rollback; older ones are deleted. |
| `drishti.packs.deploy.require-signature` | `false` | `true`: only archives signed by a publisher in `drishti.packs.registry.trusted-keys` (Ed25519 over the archive bytes; headers `X-Drishti-Signature`, `X-Drishti-Publisher`). Unsigned archives are otherwise accepted with a NOTE. |
| `drishti.packs.deploy.require-manifest` | `true` | An archive must carry `MANIFEST.json` (`drishti.py pack bundle` writes it) so every file's checksum is checked. |
| `drishti.packs.deploy.staging-minutes` | `30` | How long a verified upload waits for its confirmation before it is discarded. |
| `drishti.packs.deploy.history-file` | `./data/packs/deploy-history.jsonl` (`DRISHTI_PACKS_DEPLOY_HISTORY`) | Every deployment, rollback and reverted attempt, one JSON object per line. |
| `drishti.packs.deploy.probe-dates` / `probe-timeout-seconds` | `3` / `30` | Test connection: business dates listed per kind, and how long to wait for one source. |

The token scope that opens these writes to a personal API token is `packs:admin` (`drishti.security.token-scopes`); the user still needs the `admin` role.

### `drishti.rachana` — Sutras

| Key | Default | Meaning |
|---|---|---|
| `dirs` | `./sutras` (`DRISHTI_SUTRAS`) | Site Sutra directories, scanned recursively for `*.sutra.yaml`, in addition to the packs' Sutras (any other `.yaml`, `.yml` or `.sutra.md` file there is reported as `DRS-2004`). Studio saves into the first one, as `<domain>/<name>.v<N>.sutra.yaml`. Several: `DRISHTI_SUTRAS=/srv/sutras,/srv/more`. |
| `hot-reload` | `true` | Watch the directories; an edited Sutra is used by the next view. An invalid edit keeps the last good version. |
| `reload-debounce` | `250ms` | Quiet time after a burst of file events before one reload. |
| `watch` | `auto` (`DRISHTI_SUTRA_WATCH`) | How hot reload notices edits. `auto` uses the operating system's file events and, when they are not available (Linux: *User limit of inotify watches reached*), polls instead; `poll` always polls (network file systems, where file events are unreliable). Admin → Health shows `hotReload: WATCHING` or `POLLING`; both pick up edits. |
| `poll-interval` | `2s` | How often polling looks at the Sutra files (paths, sizes, modification times). |
| `formats-file` | none | A site file that overrides or adds named formats (on top of the packs' `config/formats.yaml`). |
| `expression-cache-size` | `10000` | Compiled Rachana-EL expressions kept in memory. |
| `max-expression-depth` | `200` | The deepest a Rachana-EL expression may nest (parentheses, calls, and each operand of a chain such as `a + b + c`). Deeper is `DRS-2101`: a Sutra is not loaded, an alert rule or search condition is refused. It keeps parsing and evaluation within the stack; raise it only for a real need. Applies to Sutras, alert rules, search conditions, history paths and derived kinds. |
| `max-expression-length` | `10000` | The longest a Rachana-EL expression may be, in characters. Longer is `DRS-2101`. |
| `studio-save` | `false` (`DRISHTI_STUDIO_SAVE`) | Let Sutra Studio save files (authors only). Turn on in authoring environments. With it off, a save returns 403 `saving from Studio is disabled (drishti.rachana.studio-save)`. |

### `drishti.ui-kit` — the Drishti UI kit

Platform UI vocabulary the server always loads, whatever packs are enabled ([RACHANA_REFERENCE.md, The Drishti UI
kit](../guides/RACHANA_REFERENCE.md#3a-the-drishti-ui-kit)): the fragments every Sutra may use (`{ use: series-line@1 }`), the
icons, images and logos, and the brand. Read with `GET /api/v1/ui-kit` and `GET /api/v1/ui-kit/assets/{path}`
([API_GUIDE.md](../guides/API_GUIDE.md)).

| Key | Default | Meaning |
|---|---|---|
| `dir` | `./config/ui-kit` (`DRISHTI_UI_KIT_DIR`) | The kit folder: `fragments/` (`<name>.v<N>.fragment.yaml`, read with the Sutras at start and at every Sutra reload), `assets/icons`, `assets/images`, `assets/logos`, `brand.yaml`. The CLI (`sutra lint|test`) reads the same setting (system property or environment variable), else `./config/ui-kit`, else a `config/ui-kit` above the paths it checks. When the folder has no `fragments/`, the server uses the `ui-kit` folder beside the packs folder (`drishti.packs.dir`: `config/packs` finds `config/ui-kit`) if that has them; when neither has, it logs a warning and screens that use a kit fragment do not draw (`DRS-2048`). |
| `site-dir` | `<data folder>/ui-kit` (`DRISHTI_UI_KIT_SITE_DIR`) | The site's overrides, never the kit itself: `brand.yaml` (its keys win over the kit's, map by map: `name: Acme Risk`, `themes: { light: { accent: "#0b5" } }`) and `assets/` (a file wins over the kit's file of the same path, e.g. `assets/logos/acme.svg`). Read at each request. |

A pack's own fragments stay in its Sutra folders, namespaced by the pack (`trading/deal-ticket@1`); one named like a kit fragment
loses the plain name to the kit, with a `DRS-2048` warning in the log.

### `drishti.governance` — reviewing Sutra changes

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_SUTRA_REVIEW`) | A Studio save becomes a proposal; an approver (a role with `approve`, or an admin) makes it live. Off: a save goes live at once. |
| `four-eyes` | `true` (`DRISHTI_SUTRA_FOUR_EYES`) | Nobody approves their own proposal. Applies only with security on (with security off everyone is the same anonymous user). |
| `dir` | `./data/governance` (`DRISHTI_GOVERNANCE_DIR`) | Where proposals and their history are kept. Back it up. |

Check: `curl -s localhost:18480/api/v1/studio/settings` returns `{"approve":…,"review":…,"save":…}`.

### `drishti.branding` — the product's name and notices

| Key | Default | Meaning |
|---|---|---|
| `product` | `Drishti` (`DRISHTI_PRODUCT`) | The product name in the About answer and messages. |
| `tagline` | `Any data. Any domain. One grammar.` | The line under the name. |
| `owner` | `Ashutosh Sinha` | The owner named in the About answer. |
| `copyright` | the Drishti copyright line | Returned by `GET /api/v1/about`. |
| `notice` | `Unauthorised copying, use or distribution is prohibited.` | The legal notice beside it (also in the public `/public/about`). |

The console has its own copy under `ui:` (`product`, `tagline`, `owner`, `copyright`, `notice`; see [`ui`](#ui)): set both when you rebrand.
Messages name the product's features after `product` (`<product> SQL` in a SQL error, `<product>*net` in a cluster answer); no server
message writes the name in code, and `BrandingLiteralTest` (drishti-it) fails on a literal. Header names (`X-Drishti-…`) and the
`DRISHTI_` variables are identifiers and keep their names.

### `drishti.edition` — editions and lenses

An edition turns Drishti into a focused product with one setting ([EDITIONS.md](EDITIONS.md), the design in
[its section 13](EDITIONS.md#13-the-design)). The edition file is a layer under the site's configuration: every key below it can still be set
by the site.

| Key | Default | Meaning |
|---|---|---|
| `drishti.edition` | empty (`DRISHTI_EDITION`) | The edition: empty is the full Drishti, exactly as without editions; `risk` is Drishti Risk (`config/editions/risk.yaml`), `world` Drishti World (`config/editions/world.yaml`), `market` Drishti Market (`config/editions/market.yaml`), `trading` Drishti Trading (`config/editions/trading.yaml`). Names a file in `drishti.editions.dir`; a name with no file stops the start. Set the same on every member of a cluster. |
| `drishti.editions.dir` | `./config/editions` (`DRISHTI_EDITIONS_DIR`) | The folder of the edition files, `features.yaml` (which settings each feature presets) and `default-packs.yaml` (the one default pack list). |
| `drishti.edition-spec.*` | the edition file's keys | The site's overrides of the edition file, key by key: `drishti.edition-spec.commands.also-allow[0]=FRTB` adds a command, `drishti.edition-spec.packs.allow-only=false` lets other packs load, a list (`bi.datasets`) replaces the file's. |
| `drishti.lenses` | `risk,world,market,trading` (`DRISHTI_LENSES`; empty turns lenses off) | The editions a person may open as a lens on a full instance ("Open Drishti Risk", "Open Drishti World", "Open Drishti Market", "Open Drishti Trading"), comma list: `risk,world,market,trading` offers the four, and a person inside one switches to another. A lens hides, it does not secure. Ignored with an edition. |

The console's side: `editions.dir` (`DRISHTI_EDITIONS_DIR`, default `../config/editions`); an `edition:` block in its
`application.local.yaml` overrides the edition file's keys for the console (menus, Home, help, words; an edition's Home landings are its `home.landings`, drawn by its `home.template` with its `home.titles`: Drishti Risk's `desk`, `command`, `map`, Drishti World's `desk`, `explorer`, `map`, Drishti Market's `desk`, `explorer`, `quality`, Drishti Trading's `trader`, `desk`, `explorer`; Drishti Market's `quality.stale-days`, default 2, is the Quality landing's stale threshold in business days); Drishti Trading's `trading:` block: `trading.people` (who is who: `{user: {trader: TRDR-ASHAH}}` or `{user: {desk: DESK-COMM, role: desk-head}}`; the console's own `trading.people` in its `application.local.yaml` adds to the edition file's and wins for a name in both), `trading.match-records` (true: else a matching trader record), `trading.landing-by-role` (`{desk-head: desk, trader: trader}`, below the person's own choice), `trading.near-limit` (0.8), `trading.limit-days` (5), `trading.pnl-var-days` (60), `trading.max-rows` (20000), `trading.live-statuses`; a person's View as choice is their `tradingView` setting ([EDITIONS.md section 12](EDITIONS.md#12-drishti-trading)); `lens.roles` is the hook for
role-based access to the lens link (read, not wired). Its own feature switches, each on by default and preset by an edition through
`config/editions/features.yaml`: `features.build` (Build: new screens, packs, designs, reviews, changes), `features.forms` (`FORM`, `/forms`),
`features.datamap`, `features.data` (Export data, `DATA`), `features.workspaces`, `features.monitors`, `features.alerts`, `features.lakes` (Admin → Lakes), `features.reports`,
`features.collaboration` (Admin → Collaboration). `/studio` (the Build workbench's entry) follows `features.build`. A page whose switch is off answers 404.

Branding keys of the console beside `ui.product`: `ui.placeholder` (the command line's placeholder; blank: `config/sitemap.yaml`'s),
`ui.logo` (an image under `/static/`, or a `data:` URL, drawn instead of the eye mark), `ui.favicon` and `ui.apple_touch_icon` (the
browser and home-screen icons; the generated `/manifest.webmanifest` follows them). The console takes the server's product name and
tagline from its about answer unless its own `ui.product` is set. The advice for `DRS-1017` and `DRS-4023` is in `ui.error_advice`.

### `drishti.identity` — users, passwords and the identity database

Users, roles, saved workspaces and the audit trail live in one database (SQLite by default, PostgreSQL for several
servers). The environment variables of this section are in the [environment variable index](#environment-variable-index).

| Key | Default | Meaning |
|---|---|---|
| `database-url` / `database-user` / `database-password` | `jdbc:sqlite:./data/identity/drishti.db` / empty / empty (`DRISHTI_IDENTITY_DB_URL`, `_USER`, `_PASSWORD`) | The database; `jdbc:postgresql://host:5432/drishti` for PostgreSQL. |
| `database-pool-size` | unset | Connections in the pool. Unset: PostgreSQL uses [`drishti.db.pool.size`](#drishtidbpool--database-connection-pools) (`20`), with its times and leak detection; SQLite is an embedded file with one writer at a time, is not pooled in that sense, and keeps `8`. |
| `refresh-seconds` | `15` | How soon servers sharing one database see each other's role and pack changes. |
| `iterations` | `240000` | PBKDF2-HMAC-SHA256 iterations for new password hashes. |
| `min-password-length` | `10` | The shortest password accepted. |
| `max-failed-attempts` / `lockout` | `5` / `15m` | Failed sign-ins before an account locks, and for how long. |
| `force-password-change-on-create` / `-on-reset` | `false` / `false` (`DRISHTI_FORCE_PW_CHANGE_ON_CREATE`, `_ON_RESET`) | Require a new password at the first sign-in of a new user, or after an administrator's reset. |
| `seed-admin` | `true` (`DRISHTI_SEED_ADMIN`) | On first start with no users, create the development administrator. `false` in production. |
| `seed-username` / `seed-password` | `drishti-dev-admin` / the built-in development password (`DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD`) | Its sign-in. Change the password at once; the console warns until you do. |
| `seed-roles` / `seed-display-name` | `[admin]` / `Drishti dev admin` | Its roles and the name shown for it. |
| `users-file` / `audit-file` / `preferences-dir` | `./data/identity/users.json` / `./data/identity/audit.jsonl` / `./data/identity/preferences` | Files of an older identity store, imported once into an empty database and renamed `*.imported`. |

### `drishti.security` — tokens, roles, field masks

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_SECURITY_ENABLED`) | Require a signed bearer token (HS256) on every `/api/**` call. Off is for local development only. |
| `secret` | empty (`DRISHTI_TOKEN_SECRET`) | The HS256 key, shared with the console's `auth.token_secret`. At least 32 bytes when security is on. |
| `clock-skew` | `30s` | Tolerated clock difference when checking a token's expiry. |
| `roles.<role>.kinds` | see below | Entity kinds the role may open; `"*"` means all. |
| `roles.<role>.raw` | `false` | Sees every field: nothing in `redact` is masked for the role, anywhere. |
| `roles.<role>.author` | `false` | May save Sutras from Studio (a proposal when governance is on). |
| `roles.<role>.approve` | `false` | May approve or reject proposed Sutras. |
| `roles.<role>.admin` | `false` | May manage users, read the audit log, approve Sutras. |
| `roles.<role>.calc` | `false` | May use Calc, Python in the browser on what the role opens ([USER_GUIDE.md](../guides/USER_GUIDE.md#9-roles-who-may-use-calc)). |
| `roles.<role>.layout` | `true` | May customise layouts: layout mode (`Alt+L`) and personal layouts ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)). On unless set to `false`; the bundled `viewer` sets it to `false`. |
| `roles.<role>.collaborate` | `true` | May share, comment and mention ([COLLABORATION.md](../architecture/COLLABORATION.md)). On unless set to `false`; a role saved by an earlier release keeps it without a migration. |
| `roles.<role>.compliance` | `false` | May read any share, place and release legal holds, export the collaboration record and verify hash chains (`/admin/collab/holds`, `/exports`, `/verify`). Not implied by `admin`: grant it to the compliance role. Moderation, retention runs and permanent removal are `admin`. |
| `registered-users-only` | `true` | A signed bearer token is refused (`401`) when its user was deleted or disabled in the identity store, so a token never outlives the account; the console's own service identity is exempt. `false` trusts the token alone. |
| `mask-copies` | `false` | Opt-in (`DRISHTI_MASK_COPIES`): after masking, exact copies of a masked field's value inside the other text of the same document also read `•••` ("Captured by J. Smith" with `trader` masked). Exact and case-sensitive; best effort. |
| `mask-copies-min-length` | `3` | Shortest text value scrubbed as a copy; numbers are scrubbed from four digits. |
| `mask-copies-max-nodes` | `50000` | A document with more nodes is not scanned for copies (its masked fields are still masked). |
| `mask-copies-max-values` | `64` | Most distinct masked values scrubbed per document. |
| `redact` | `[trader, TRADER_ID, TRADER_FIRST_NAME, TRADER_LAST_NAME, counterpartyId, patientName, lifecycle.timeline.description]` | Field names masked for roles without `raw`, on every path that shows or reads their values ([field masks](#field-masks)). |

The bundled roles; packs add domain roles (trading: `trader`; market-risk: `market-risk`; counterparty-risk: `credit-risk`):

```yaml
drishti:
  security:
    roles:
      viewer:   { kinds: ["*"], layout: false }
      author:   { kinds: ["*"], raw: true, author: true, calc: true }
      approver: { kinds: ["*"], raw: true, author: true, approve: true, calc: true }
      pivot-approver: { kinds: ["*"] }
      admin:    { kinds: ["*"], raw: true, author: true, admin: true, calc: true }
```

Example: a role that sees only trades and books, with no raw access.

```yaml
drishti:
  security:
    roles:
      sales: { kinds: [trade, book] }
```

#### Field masks

For a role without `raw`, every field named in `redact` reads `•••` wherever the user could see or infer its value.
An entry with dots (`lifecycle.timeline.description`) masks a field by the end of its path; arrays are not a step.
Names match in any case and convention: `counterpartyId`, `COUNTERPARTY_ID` and `counterparty-id` are the same field, so a
mask written in another naming convention keeps masking. An entry that names no field of the loaded packs' samples
masks nothing: the server logs it at start-up as `DRS-6011` and Admin → Health lists it under `masksMatchingNothing`.
A name matches at any depth (`trader` masks `$.trader` and `$.confirmation.trader`), and the mask covers everything
under the field (with `counterparty` listed, `counterparty.name` and `counterparty.id` read `•••` too). The masking is
done once, on the server, before anything reads the document, so it is the same on every path:

- raw JSON (`F9`), search rows, search CSV, compare, history and its diff, Calc's columns and documents;
- pivots: a masked field groups under `•••` and is never added up;
- views: the header strip, the title, tables and their totals, key/value panels, tabs, charts, links and function keys,
  the panel records behind the Pivot tab, and any value a Sutra computes from a masked field (`fmt($.mtm, …)`,
  `$.mtm / 1e6`, a link built from a masked id); a chart leaves masked points out;
- the live stream, monitors, the console's panel CSV export and Sutra Studio previews;
- Impact (`F8`): an entity tied to the analysed one only through a masked field is not listed (a search on that field
  finds nothing either); a masked measure and the total of a group that holds one read `•••`;
- the type-ahead: entities are described from their view title as the user sees it, and a masked value is neither
  shown nor matched; phrase search names no values of a masked field;
- alert rules: a rule sees the document as its owner may (owner's current roles), so a rule on a masked field never
  fires and a message shows `•••`.

A masked field cannot be probed: a condition on it is never true (`mtm > 0` and `!(mtm > 0)` alike), ordering by it
does not order, and the type-ahead does not match it. A mask hides the field it names, nothing else: a value the
document also holds under another name (cash-flow PVs that add up to a masked MTM, say) stays visible unless that field
is listed too.

### `drishti.embed` — embedded views for other web applications

Off by default. A host application's backend buys a short embed token at the server (RFC 8693); every embed call is checked against the registered application and the user, and always masks. See [API_GUIDE.md](../guides/API_GUIDE.md#embedding-views-in-another-web-application) for the exchange and [USER_MANAGEMENT.md](USER_MANAGEMENT.md#embedding-host-applications) for registering an application.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_EMBED_ENABLED`) | Switch embedding on. Without it there is no token endpoint and embed tokens are refused (`DRS-8001`). The server refuses to start with it on while `drishti.security.enabled` is off: an embed token is only checked when security is on. |
| `issuer` | empty (`DRISHTI_PUBLIC_URL`) | The `iss` of embed tokens: this server's public URL. |
| `audiences` | empty (`DRISHTI_CONSOLE_URL`) | The consoles that may accept embed tokens (their public URLs). A token request names one (`audience`); the first is the default. A call whose token names another is refused. |
| `signing-key` | empty (`DRISHTI_EMBED_KEY_FILE`) | PEM file with a PKCS#8 EC `PRIVATE KEY` and its `PUBLIC KEY` block (P-256, ES256): `openssl ecparam -name prime256v1 -genkey -noout \| openssl pkcs8 -topk8 -nocrypt > key.pem` and append `openssl ec -pubout` of it. Empty: a key is made at start (a warning is logged), so tokens do not survive a restart and a second server cannot verify them. Published at `GET /api/v1/embed/jwks`. |
| `token-max-seconds` | `900` | The longest life of an embed token, and the cap on an application's own `tokenSeconds` (default 300, at least 30). |
| `mask` | `always` | Embed calls mask the fields in `drishti.security.redact` whatever the user's roles say. Only `always` exists. |
| `allow-wildcard-origins` | `false` | Let an application register `https://*.suffix` origins (review apps). |
| `assertion-max-age` | `60s` | The longest life (`exp` minus `iat`) of a client assertion or user assertion a host signs. |
| `sign-in-window` | `true` (`DRISHTI_EMBED_SIGN_IN_WINDOW`) | The renderer's optional sign-in window (`auth: 'popup'`, [CLIENT_RENDERER.md 10.1](../architecture/CLIENT_RENDERER.md#101-signing-in-on-another-site-the-token-exchange-and-an-optional-sign-in-window)): a registered host's page with no backend signs its user in through single sign-on in a window opened on a click, and this server makes the embed token (`POST /api/v1/embed/sign-in-window`, the console only). Needs `drishti.security.oidc`. `false` refuses it (`403 DRS-8003`); hosts keep the token exchange. |
| `scopes` | `embed:view`, `embed:about`, `embed:report`, `embed:search` | Each embed scope with the `METHOD path` patterns it opens. A token calls nothing else, and nothing writes (the one POST that reads, `POST /api/v1/bi/reports/*/query`, is listed in `embed:report`). Keys keep their colon in brackets: `[embed:view]`, `[embed:about]`, `[embed:report]`, `[embed:search]`. `embed:search` opens `GET /api/v1/embed/search` for `<drishti-search>` (a masked, read-only search across Drishti, cut to the application's kinds). `embed:report` opens a published BI report for `<drishti-report>` and only for a host application whose `kinds` list names `report:<id>` or `report:*`. |
| `limits.tokens-per-minute` | `120` | Token requests per host application per minute (`429 DRS-8004`). |
| `limits.views-per-minute` | `600` | The default `callsPerMinute` of a new application: calls per application per minute. |
| `limits.views-per-user-per-minute` | `60` | The default `userCallsPerMinute` of a new application. |
| `limits.searches-per-user-per-minute` | `30` | Searches one user may make per application per minute through `<drishti-search>` (scope `embed:search`), on top of the call rates (`429 DRS-8004`). |
| `search.max-results` | `10` | Most results one search returns (1 to 50). |
| `search.min-chars` | `2` | Shortest query that is searched; a shorter one gets an empty answer and a note. |
| `apps` | empty | Host applications declared in configuration (GitOps), shown in Admin → Embedding as *from config* and read-only; see below. |

**Declaring applications in configuration** (`application.yaml`, a pack's settings or the environment). Each entry takes the fields of a registration; the client secret is never written, only its hash, `printf '%s' "$SECRET" | sha256sum`. An entry is checked like a registration when the server starts, and an invalid one stops the start with its id and the problem. A configured application cannot be changed, rotated, disabled or deleted through the API or the page (`409`); an id in both places is the configured one.

```yaml
drishti:
  embed:
    apps:
      - id: client-crm
        name: Client CRM
        origins: ["https://crm.bank.example"]
        kinds: [trade, counterparty]          # empty: every kind the user's roles open
        scopes: ["embed:view"]
        secret-sha256: 20b67fe342b673443bdc90ed8cb3855e255991539dce4a9c15c252c6a39f1475   # or jwks: '{"keys": [...]}' (or both)
        token-seconds: 300
        calls-per-minute: 600
        user-calls-per-minute: 60
        enabled: true
```

Usage counters (tokens issued, calls, refusals by code, live streams, last use) are held in memory since the start, shown in Admin → Embedding and exported as `drishti.embed.tokens`, `.calls`, `.refusals` (tag `code`) and `.streams`, each tagged `app`. There is nothing to configure: only registered applications are counted, so the set is bounded by the registry.

### Console `embed` — the console's side of embedded views

In the console's `config/application.yaml` (or `--embed.key=value`). Off by default; see [CLIENT_RENDERER.md](../architecture/CLIENT_RENDERER.md#20-drishti-elements-the-design) (step 7).

| Key | Default | Meaning |
|---|---|---|
| `embed.enabled` | `false` (`DRISHTI_EMBED_ENABLED`) | Serve `/embed/v1/...`. Off: nothing under `/embed/` exists. Needs the server's `drishti.embed.enabled` and `drishti.security.enabled`. |
| `embed.origins_ttl_seconds` | `60` | How long the registered origins (the CORS allow-list, from the server's `/api/v1/embed/apps/origins`) are reused. A new application's origin works within this time. |
| `embed.jwks_ttl_seconds` | `300` | How long the server's embed signing keys (`/api/v1/embed/jwks`) are reused. |
| `embed.jwks_min_refetch_seconds` | `10` | A token naming an unknown key id (the server rotated its key) refetches the key set, but at most this often. |
| `embed.audiences` | empty | Token audiences this console accepts (its public URL). Empty accepts what the server issued (the server checks its own `drishti.embed.audiences`). |
| `embed.rate_per_minute` | `600` | Calls per host application per minute; over it `429 DRS-8004` with `Retry-After`. `0` = unlimited. |
| `embed.max_streams` | `64` | Live subscriptions all embed channels together may hold open (each uses a connection of `backend.pool_size`). Beyond it a channel open is `429 DRS-8004`. `0` = unlimited. |
| `embed.recheck_seconds` | `60` | An open stream asks the server this often whether its token still passes (user, application, scopes). |
| `embed.token_grace_seconds` | `30` | After a stream's token expires, how long the console waits for a fresh one (`POST /embed/v1/channel/{cid}/token`) before ending the stream with `DRS-8001`. |
| `embed.compress_min_bytes` | `512` | Payloads smaller than this are not compressed (Brotli when installed, else gzip). |
| `embed.sign_in.enabled` | `true` (`DRISHTI_EMBED_SIGN_IN`) | The sign-in window of `auth: 'popup'` on this console (`/embed/v2/sign-in`, [CLIENT_RENDERER.md 10.1](../architecture/CLIENT_RENDERER.md#101-signing-in-on-another-site-the-token-exchange-and-an-optional-sign-in-window)). Needs `auth.oidc.enabled` and `auth.token_secret`; off, or without them, the window answers `DRS-8003` and pages keep the token exchange. |
| `embed.sign_in.redirect_uri` | empty | Where the provider sends the window back; empty: `<console>/embed/v2/sign-in/callback`. Register it at the provider beside `auth.oidc.redirect_uri`. |
| `embed.sign_in.refresh` | `true` | Renew the window's token silently with the provider's refresh token while the SSO session lasts (the refresh token sealed with AES-GCM, held in the page's memory, unreadable there). `false`: the person clicks again when the token ends. |
| `embed.sign_in.max_hours` | `0` | The longest a window sign-in is renewed; `0`: `auth.session_hours`. |
| `embed.lifecycle` | empty | Announces the end of an embed API major: `{"1": {deprecation: 2027-01-01, sunset: 2027-07-01, link: https://drishti.example/migrating}}`. From the `deprecation` day every `/embed/v1/` answer carries `Deprecation: @<epoch>` (RFC 9745) and, whenever a `sunset` is set, `Sunset: <HTTP date>` (RFC 8594) and a `Link: <...>; rel="deprecation"`; from the `sunset` day the embed calls are `410 DRS-8006`. Nothing is announced by default. See [CLIENT_RENDERER.md](../architecture/CLIENT_RENDERER.md#13-distribution-and-versioning). |

### `drishti.security.oidc` — single sign-on

The console runs the browser sign-in flow; the server verifies the provider's ID token itself (signature,
issuer, audience, expiry, nonce). Both halves must be configured (see [console `auth.oidc`](#auth)).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_OIDC_ENABLED`) | Accept sign-ins from the provider. |
| `issuer` | empty (`DRISHTI_OIDC_ISSUER`) | The provider's issuer URL; its discovery document is `<issuer>/.well-known/openid-configuration`. A trailing `/` is removed. |
| `client-id` | empty (`DRISHTI_OIDC_CLIENT_ID`) | This application's client id (the ID token's audience). |
| `jwks-uri` | discovered | The provider's key set, when it should not be discovered. |
| `username-claim` | `preferred_username` | The claim that becomes the Drishti user name (`email` is common). |
| `display-claim` | `name` | The claim shown as the user's name. |
| `groups-claim` | `groups` | The claim listing the user's groups. A dotted path works (`realm_access.roles`). |
| `role-map` | `{}` | Provider group to Drishti roles. A role the map names that `drishti.security.roles` does not define is logged as a warning. |
| `default-roles` | `[]` | Roles for a user none of whose groups map. Empty refuses such users. |
| `roles-from-provider` | `true` | The provider's groups replace the user's roles at every sign-in (false: only at first sign-in). |
| `algorithms` | `[RS256, ES256]` | Accepted signature algorithms. Only `RS256/384/512`, `PS256/384/512`, `ES256/384/512` are allowed; anything else stops start-up. |
| `clock-skew` | `60s` | Tolerance for `exp`, `iat` and `nbf`. |

Example (Keycloak):

```yaml
drishti:
  security:
    oidc:
      enabled: true
      issuer: https://login.bank.example/realms/staff
      client-id: drishti
      groups-claim: realm_access.roles
      role-map:
        desk-rates: [trader]
        market-risk: [risk]
      default-roles: [viewer]
```

### Identity

Users, passwords, lockout, the audit log and the identity database are configured under
`drishti.identity.*`. See [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

### `drishti.engine` — the view pipeline

| Key | Default | Meaning |
|---|---|---|
| `layout-cache-size` | `10000` | Effective layouts kept, one per (Sutra version, kind, data shape). |
| `fingerprint-cache-size` | `100000` | Data-shape fingerprints kept, one per (entity, generation; in a cluster also the caller's security signature), a few hundred bytes each (about 30 MB full). |
| `bind-parallelism` | `0` (one thread per CPU core) | Threads binding panels. `0` or a negative value means one per core. |

Raise the cache sizes when many distinct entities are viewed and the cache hit rates in **Admin → Health** are
low. See [PERFORMANCE.md](PERFORMANCE.md).

### `drishti.explain` — About this page

`GET /api/v1/views/{kind}/{id}/explain` answers the page's **?** and F1 drawer: where the data came from, why the page
looks the way it does, and the pack's text about it ([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help)). It is derived
only when asked, by the same code as the view, for the caller (so masks and rights hold), and kept a short while.

| Key | Default | Meaning |
|---|---|---|
| `cache-size` | `2000` | Answers kept, one per user, page, business date and generation. |
| `cache-ttl` | `60s` | How long an answer is served before it is derived again. **Admin → Health → purge** of the `engine` caches clears it too. |

#### `drishti.explain.ask` — Ask about this page (optional, off by default)

A question box in the drawer that answers from the page's own explanation with a language model you run or rent. **Nothing is sent
anywhere unless you turn it on**, and then only for the packs you list. The server makes the call (the console is a proxy), the key
comes from the environment only, and the model gets no tools. See [the privacy note](OPERATIONS.md#ask-about-this-page-and-your-data).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_ASK_ENABLED`) | The global switch. While `false` no network call is ever attempted and `POST …/ask` answers `404 DRS-4007`. |
| `packs` | `[]` | The packs where the box is drawn (per-pack opt-in); empty means none. `DRISHTI_EXPLAIN_ASK_PACKS=market-risk,counterparty-risk`. |
| `model` | empty (`DRISHTI_ASK_MODEL`) | The model Ask uses: a logical model of the AI layer, `<provider>/<model>` (a provider file in `drishti.ai.dir` with its `type`, `base-url` and `api-key: ${ENV}`; [AI.md](AI.md)). |
| `values` | `labels-only` | `labels-only` sends labels, definitions and structure, never a rendered value of the page. `shown` also sends the page's rendered sentence (its figures, as the asking user may see them). A value masked for the user is never sent in either mode. |
| `max-question-chars` | `500` | Longer questions are refused (`400`). |
| `max-prompt-kb` | `24` | The whole prompt; the pack guide text is cut first, then glossary entries. |
| `max-guide-chars` | `6000` | The most of the pack's guide section put in the prompt. |
| `max-answer-tokens` / `max-answer-chars` | `400` / `2000` | The model's output cap and the cut applied to what comes back. |
| `timeout` | `15s` | Slower than this is `504 DRS-4008`. |
| `per-user-per-minute` / `per-user-per-day` | `6` / `100` | Over either is `429 DRS-4009`. A call that fails (the model errors or times out) still uses one of the allowance. |
| `log-questions` | `true` | The question text goes to the [access log](OPERATIONS.md#9b-the-access-log) (action `ask`, with the outcome). `false` records only its length. The answer is never stored. |
| `anthropic-version` | `2023-06-01` | The `anthropic-version` header sent when `api: anthropic`. |
| `log-answers` | `false` | `true` also writes the answer text to the server log (`drishti.ask`). |

### `drishti.about` — the packs' About text

Each pack may carry `config/about.yaml`: what a kind means, and notes per panel, written with Sutra expressions over
the page's own values ([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#pack-schema-additions)).

| Key | Default | Meaning |
|---|---|---|
| `max-text` | `600` | Longest About text a pack may write for one entry, in characters; a longer one is a load problem (`DRS-204x`). |
| `max-rendered` | `1000` | Longest text after its expressions are filled in; the rest is cut with `…`. |
| `packs` | set by the server | The packs' About files and their `extends` lineage, written by the pack loader. Do not set it. |

### `drishti.datamap` — the data map

The data map (`DATAMAP <GO>`, `GET /api/v1/datamap`; [USER_GUIDE.md](../guides/USER_GUIDE.md#the-data-map)) counts every stored kind and
checks every join the data contracts declare against the data, in the background, once per business date and set of loaded packs.

| Key | Default | Meaning |
|---|---|---|
| `max-rows` | `20000` | The most rows of one kind read to count it and to check its joins; a larger kind is counted as `20,000+` and its joins are marked *a sample*. |
| `ttl` | `PT15M` | How long a built map is kept before it is read again (a load may have changed the data). |
| `read-budget` | `PT30S` | The time one read (a listing, a column read) may take before the kind is reported as not read. |
| `threads` | `4` | Kinds read at once. |

The console's `drishti-console/config/datamap.yaml` holds the map's presentation only: domain names, their order and which packs each holds, kinds to
draw dashed beyond those whose contract says `class: computed`, and the coverage under which a join is a finding (`low-coverage`, 0.95).
A pack it does not name is a domain of its own.

### `drishti.data-view` — Export data

Export data (`EXPORT <GO>`, `/api/v1/data`; [DATA_GUIDE.md](../guides/DATA_GUIDE.md#6-export-data-download-a-dataset)) lists every stored
dataset and streams a day of one as `.jsonl.gz`. `enabled` switches the API (the edition feature `data` presets it, with the console's
`features.data`); `internal-only` names the datasets whose sources' terms restrict reuse, and `internal-only-download: false` refuses
them (the edition feature `internal-only-data: off` presets it; the four editions ship so). `count-budget` bounds a row count,
`batch-rows` the rows a column read hands over at a time, and `list-limit` with `read-budget` a source that cannot read a day in one pass.
The console's `data_view.*` say how long it waits for the list, a count, and between two parts of a download.

### `drishti.builder` — the Screen Builder's shape extractor

`POST /api/v1/builder/shape` merges sample JSON documents into one JSON Schema with roles. Input over a limit is refused
with `413 DRS-5005` before it is all parsed.

| Key | Default | Meaning |
|---|---|---|
| `max-samples` | `50` | Documents in one request. |
| `max-file-mb` | `5` | Largest single document, in megabytes. |
| `max-total-mb` | `25` | Largest request body, in megabytes. A body over it is answered `413` `DRS-5005` with its size and the limit before it is read (a chunked body: on overflow), by the `drishti.http.request-limits` rule `/api/v1/builder/` = this value; `server.tomcat.max-swallow-size` (`DRISHTI_MAX_SWALLOW`, 64MB) lets Tomcat drain the rest so the client reads the answer, not a reset. |
| `max-depth` | `64` | Deepest nesting of one document. |
| `enum-max-distinct` | `12` | Text with at most this many distinct values is an `enum` ... |
| `enum-min-seen` | `3` | ... when it was seen at least this many times and some value repeats. |
| `examples` | `3` | Example values per path in the report (masked values stay masked). |
| `rare-below` | `0.5` | A field present in fewer than this share of its records is listed as rare; auto-design also leaves such a field out of the strip and the Details panel. |
| `prune-share` | `0.5` | Auto-design (`POST /builder/design`): a panel or strip figure empty or in error for more than this share of the samples is dropped, or demoted to its first alternative. |
| `strip-max` | `6` | Most figures in a drafted strip. |
| `max-panels`, `max-side-panels` | `16`, `4` | Most panels auto-design puts in the main and the right column (the links panel is extra); the rest are dropped and listed. |
| `line-min-points` | `5` | Dated rows shorter than this are drafted as a ladder, not a line. |
| `max-alternatives` | `2` | Runner-up kinds kept with each drafted panel. |
| `sample-max-items` | `50` | The synthetic sampler (a Design started from a JSON Schema) generates arrays up to this long; a schema asking for more (`minItems: 2000000000`) is clamped and the answer lists a problem. Nothing beyond the limits is allocated. |
| `sample-max-string` | `1000` | ... strings up to this many characters. |
| `sample-max-depth` | `8` | ... nesting up to this deep. |
| `sample-max-kb` | `256` | ... and at most this many kilobytes in one generated document. |
| `code-names` | `year, version, seq, sequence, no, num, number, code, zip, postcode` | Last word of a whole-number field's name that makes it a code (a label, not an amount: not summed, no thousands separator). |
| `fraction-names` | `utilisation, utilization, usage, used, ratio, share, coverage, fill, pct, percent` | Words in the name of a number between 0 and 1 that make auto-design draft a gauge of 1. |
| `limit-names` | `limit, max, cap, threshold, budget, capacity` | Field names that hold a limit for a measure beside them (a gauge's maximum). |
| `status-words`, `status-names`, `id-names`, `label-names`, `ohlc-names`, `graph-node-names`, `graph-edge-names`, `long-text-chars` | built in | The vocabulary the role rules read: values that are states, field names that hold a state or an id, names of a row's label, the four candle fields, the lists of a graph, the length of prose. Set one to replace its list. |

### `drishti.builder.designs` — the Build workbench's Designs

A Design (`/api/v1/builder/designs`, **Build → Build home → My drafts**) keeps a user's samples, Sutra and notes on the server, so work
survives a console restart and shows on every console. Only its owner can reach it (anyone else gets `404 DRS-5006`).
Sample documents are never logged; deleting a Design deletes its samples at once. A limit answers `413 DRS-5005`.

| Key | Default | Meaning |
|---|---|---|
| `store` | `content` | `content` (default): the content store ([below](#the-content-store-configstorestoreyaml)), `designs/<user>/<id>/design.json` and `samples/`, reached by every node of a cluster; a save changed by another node meanwhile answers `409 DRS-5057`. One node only: `file` (`data/designs/<user>/<id>/design.json` and `samples/`, readable by the server's account only) or `jpa` (the identity database, `drishti_design`, `drishti_design_sample`). |
| `dir` | `./data/designs` | The old file store's directory. With `content`, what it holds is imported once at start and the folder renamed `designs-imported-<time>` (kept; delete it when you are happy). |
| `max-per-user` | `50` | Named Designs one user keeps. Scratch (unnamed) Designs do not count toward it. |
| `max-samples` | `50` | Samples in one Design. |
| `max-mb` | `25` | Megabytes in one Design: its sample documents, Sutra, notes and tests (a reference to a stored entity counts nothing). |
| `max-user-mb` | `250` | Megabytes of samples, Sutra, notes and tests across one user's Designs. |
| `max-sutra-kb` | `1024` | Largest Sutra text of one Design (`413 DRS-5005` beyond it). |
| `max-notes-kb` | `256` | Largest notes text. |
| `max-tests-kb` | `1024` | Largest tests (the JSON of the list). |
| `max-scratch` | `10` | Scratch (unnamed) Designs one user keeps. Opening an example, a Help link or a `/studio` address makes one; when the cap is reached the oldest scratch Design is deleted to make room. **My designs** has *Delete scratch designs* (`DELETE /api/v1/builder/designs?scratch=true`). |
| `scratch-ttl` | `1d` | An unnamed (scratch) Design is deleted this long after it was last touched. |
| `named-ttl` | `90d` | A named Design is deleted this long after it was last touched (an edit, or opening it, touches it). |
| `warn-after` | `75d` | A named Design untouched this long is listed with a warning that it will expire. |
| `sweep-interval` | `1h` | How often expired Designs are deleted. |
| `max-ops` | `100` | Steps of the operation log a Design keeps for undo and redo (the oldest are dropped). |

### The content store: `config/store/store.yaml`

User content that every node of a Drishti*net cluster must reach (Build workbench Designs; comment threads, notes, shares, the inbox and
outbox, legal holds; embed apps, pack switches, the audit trail and Sutra proposals; BI items, workspaces and runs next) is kept in the **content store** ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches)). Its settings are
in their own file, named by `drishti.store.file` (`DRISHTI_STORE_FILE`, default `./config/store/store.yaml`), never in
`application.yaml`. A missing file or key means the default. Ready-made variants: `config/store.examples/` (`laptop.yaml`,
`cluster-nfs.yaml`, `postgres.yaml`, `s3.yaml`).

| Key | Default | Meaning |
|---|---|---|
| `backend` | `filesystem` | `filesystem`, `sqlite`, `postgres` or `s3`. |
| `filesystem.root` | `<data folder>/fstore` | The folder (`data/fstore`). On a cluster it must be a **shared mount** (NFS, EFS, SMB) at the same path on every node. `DRISHTI_STORE_ROOT` overrides it. |
| `sqlite.url` | `jdbc:sqlite:<data folder>/identity/drishti.db` | The Drishti database itself (one node). The table `drishti_content` is in its complete schema file. |
| `postgres.url`, `user`, `password`, `pool` | none, `drishti`, `${DRISHTI_STORE_PASSWORD:}`, `4` | Usually the Drishti database. Needed for `backend: postgres`. |
| `s3.bucket`, `prefix`, `region`, `endpoint`, `path-style`, `access-key`, `secret-key` | none, empty, `us-east-1`, empty (AWS), `false`, `${DRISHTI_STORE_ACCESS_KEY:}`, `${DRISHTI_STORE_SECRET_KEY:}` | S3 or a compatible store that honours `If-Match` / `If-None-Match` (MinIO: `endpoint` and `path-style: true`). Empty keys: the AWS default credentials chain. |

Every node of a cluster must use the same settings; a node that finds another node recorded with a different root logs
`content store roots differ`. Writes are compare-and-set: an item changed elsewhere since it was read answers `409 DRS-5057`.
The start logs `content store: <backend> <root> (settings <file>)`.

### Write-back: `config/edit/`

Write-back ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons); the administrator's guide [WRITE_BACK.md, for administrators](../guides/WRITE_BACK.md#for-administrators-write-back-is-off-as-shipped)) has its own folder, named by
`drishti.edit.file` (`DRISHTI_EDIT_FILE`, default `./config/edit/edit.yaml`; its siblings `lanes.yaml`, `policy.yaml` and `retention.yaml`
are read from the same folder). A missing file or key means the default. **Off as shipped**: `edit.enabled: false`, so no change is
accepted anywhere and no Submit is drawn; a fresh install or an upgrade is read-only. The edit log, lanes, approvals, overlays and owned
values are tables of the Drishti database (the identity database's pool; [DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built)).

| File and key | Default | Meaning |
|---|---|---|
| `edit.yaml` `edit.enabled` | `${DRISHTI_EDIT_ENABLED:false}` | The site switch. With it off every write answers `403 DRS-9301`; overlays applied earlier are still shown. |
| `edit.yaml` `edit.log` | `auto` | Where the edit log lives: `auto`, `sqlite` or `postgres`. **auto**: a PostgreSQL Drishti database → `postgres` (lanes spread over the members, failover by lease); a node alone → `sqlite`; a cluster with `edit.primary` → the primary's SQLite; otherwise edits are refused in the cluster (reads carry on). An explicit value is checked: `postgres` needs a PostgreSQL database, `sqlite` in a cluster needs `edit.primary`; else the server starts with editing off and says why. |
| `edit.yaml` `edit.primary` | empty | In a cluster without PostgreSQL: the node id of the edit primary. It owns every lane; the other nodes forward edits to it and copy its overlays. No failover: while it is down, edits pause (`503 DRS-9306`). |
| `edit.yaml` `edit.follow` | `1s` | How often the other nodes copy the primary's applied overlays and owned values (and at once when told). |
| `lanes.yaml` `lanes.lanes` | `64` | P lanes, a power of two; a record's lane is `murmur3(kind:id) mod P`. Change it only with the lanes drained. |
| `lanes.yaml` `lanes.max-lane-depth` | `1000` | A lane deeper than this refuses new changes: `503 DRS-9304`, the draft is kept. |
| `lanes.yaml` `lanes.lane-warn` | `2s` | Admin → Edits warns when a lane's oldest change has waited longer. |
| `lanes.yaml` `lanes.lane-lease` | `10s` | A lane's lease; its owner renews it every third of this, another node may take the lane only after it runs out. |
| `lanes.yaml` `lanes.command-timeout` | `30s` | A source command sent and not confirmed for longer is listed as unconfirmed on Admin → Edits. |
| `policy.yaml` `policy.approval.overlay` / `owned` / `source` | `none` / `none` / `one` | Approvals a change needs per target (`none`, `one`, `two`); a form's `save.approval` may ask for more, never fewer. |
| `policy.yaml` `policy.approver-roles` | `[]` | Who approves; empty: holders of the approve power and administrators. |
| `policy.yaml` `policy.self-approval` | `false` | Four eyes: nobody approves their own change. |
| `policy.yaml` `policy.restate-role` / `override-role` | `restate` / `override` | A change to a past business date needs the first; *Apply mine anyway* over a newer value the second. |
| `policy.yaml` `policy.kinds.<kind>.approval.<target>` | none | A kind's stricter approval. |
| `retention.yaml` `retention.keep` | `7y` | How long the edit log is kept (finished entries older are deleted, audited `edit-log-purged`). |
| `retention.yaml` `retention.hot` | `90d` | The hot window (archiving older entries to signed files is planned). |

The `edit` power is a role's (Admin → Roles; the shipped `admin` role has it), and an API token needs the `edit` scope
(`drishti.security.token-scopes`). A connector writes to its source only when its file declares `writes:` **and** an administrator
switches its writes on in Admin → Edits ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md#writes-saving-to-the-source)).

### `drishti.builder.file-binding` and `dev-dir` — development file binding

| Key | Default | Meaning |
|---|---|---|
| `drishti.builder.file-binding` (`DRISHTI_BUILDER_FILE_BINDING`) | `false` | Let a Design be bound to a file in the author's **own development folder** (**Ship -> Bind to a file** in the workbench; `POST /api/v1/builder/designs/{id}/bind`). Saving writes that file and an edit made in an IDE is read back (`POST .../sync`). Needs the author right and `drishti.rachana.studio-save`. The folder is never one the registry loads, so **Save to file never makes anything live**: going live is always Ship -> Propose, then an approver. With it off every bind answers `403 DRS-5002`. |
| `drishti.builder.dev-dir` (no dedicated variable; relaxed `DRISHTI_BUILDER_DEVDIR`) | `./data/dev-sutras` | Where bound files live, one folder per user (`<dev-dir>/<user>/`). It must lie **outside** `drishti.rachana.dirs` (otherwise a saved file would be hot-reloaded and go live without review; binding then answers `403`). Names that are symbolic links, or go through one, are refused; a file is written through a new temporary file renamed into place. Only administrators are shown the absolute path (`GET /builder/designs/binding`). |

For development servers: leave binding off on authoring and production servers.

Read-only share links (`POST /api/v1/builder/designs/{id}/share`) need no setting: the link shows a Design's Sutra, operations and
sample names, never sample contents, and its owner can revoke it. Proposals made from the workbench carry the check matrix, the
sample names and the notes to the reviewer (`GET /api/v1/sutras/proposals/{id}` returns them as `evidence`).

### `drishti.commands` — the command line

| Key | Default | Meaning |
|---|---|---|
| `mnemonics.<CODE>.kind` / `.label` | from packs | A mnemonic (`TRD`) opens this kind; the label shows in suggestions. A site entry overrides a pack's. |
| `suggest-limit` | `25` (`DRISHTI_SUGGEST_LIMIT`) | Entries in the command line's dropdown; the list scrolls. This default is not capped; a request's own `limit` is, at 50. |
| `suggest-budget` | `30ms` | Type-ahead answers with what the sources returned by then. |
| `recent-size` | `20` | Recently opened entities remembered per user. |

Example: add a site mnemonic.

```yaml
drishti:
  commands:
    mnemonics:
      SHP: { kind: shipment, label: Shipment }
```

### `drishti.index` — the multi-day type-ahead index

A connector keeps the newest business day's ids for type-ahead. These **connector settings** (in a connector's
`settings:`, for the Delta, Iceberg, DuckDB, PostgreSQL/JDBC, file and S3 connectors) keep several days, each answering
only for its own date ([ARCHITECTURE.md section 9.1](../architecture/ARCHITECTURE.md#91-several-business-days)). A kind
overrides any of them with `index.kinds.<kind>.<setting>`.

| Setting | Default | Meaning |
|---|---|---|
| `index.days` | `1` | Business days kept. 1 is today's behaviour. |
| `index.max-memory` | `600MB` | Most the kept days may use on the heap (an estimate, summed over the connector's kinds). Above it the oldest closed days move to disk (or are dropped when there is no disk). `0` = no cap. |
| `index.storage` | `heap` | `heap`: closed days stay in memory until `max-memory`. `mmap`: closed days go to segment files at once (read with positional reads, not a memory map, so a removed file frees its disk immediately); only the newest day stays on the heap. |
| `index.max-disk` | `2GB` | Most disk the connector's segment files may use; the oldest days drop above it. `0` = no cap. |
| `index.reload-every` | `10m` | How often each *past* day is compared with its source and rebuilt when the source changed (a source with no version, such as S3, is rebuilt every cycle). `30s`, `10m`, `2h`, `1d`; a plain number is seconds; `0` = never. The newest day keeps its own faster refresh. Per kind: `index.kinds.<kind>.reload-every`. Admin can also reload by hand ([OPERATIONS.md section 12.2](OPERATIONS.md#122-sizing-the-multi-day-type-ahead-index)). |
| `index.block-cache` | `16` | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file). `0` = none. |
| `index.warm` | `background` | `background`: past days are built after start, newest first. `lazy`: a day is built on the first search for it. |
| `index.any-recent-day` | `false` | A bare id on the newest date also suggests ids seen only on earlier kept days, marked `last seen <date>`. |

| Server setting | Default | Meaning |
|---|---|---|
| `drishti.index.dir` (`DRISHTI_INDEX_DIR`; or `-Ddrishti.index.dir=/var/lib/drishti/index`) | `<data dir>/index` (`./data/index`) | Where segment files live, one folder per connector. Never the temporary folder, so they survive a restart and are reused when the source is unchanged. |

```yaml
# config/connectors/trading-lake.yaml
plugin: delta
settings:
  root: s3a://lake/trading
  index:
    days: 5
    max-memory: 600MB
    storage: mmap
    kinds:
      trade: { days: 10 }
```

### `drishti.search` — structured search

Not in the bundled file; the defaults apply until you set them.

| Key | Default | Meaning |
|---|---|---|
| `max-scan` | `20000` | At most this many entities of a kind are read per search; the result says when it stopped short. |
| `budget` | `3s` | Time a search may take to list and read; slower sources are left out. |
| `columns.<kind>` | from packs | A kind's key fields in pick lists and searches (`columns:` in `pack.yaml`). |
| `pivot.<kind>` | from packs | Pivot on the kind's results (the pivot screen): `true`, or the pivot as a JSON string (`pivot:` in `pack.yaml`, [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#pivot-pivot-on-search-results)). |

### `drishti.calc` — Python in the browser

Calc ([USER_GUIDE.md](../guides/USER_GUIDE.md#calc-python-on-any-view)) runs Python in the user's browser; the server never runs the code. It
decides who may use it (roles with `calc`), keeps each user's snippets, and serves whole columns of a day.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_LABS_CALC`) | The Labs switch: Calc at all, off as shipped. Off: nobody may use it, and its endpoints refuse with `403 DRS-5002`. The console has its own switch, `labs.calc`, read from the same variable. |
| `max-column-rows` | `250000` | `drishti.columns()` (`GET /api/v1/search/columns/{kind}`) returns at most this many entities, the first by id; the browser holds them all. |
| `columns-budget` | `20s` | How long a columns read may wait for its source (the first read of a business day loads its columns). |
| `max-snippet-chars` | `50000` | A saved snippet's code, at most (never more than 60,000: a saved document is at most 64 KB). Each user keeps at most 50 snippets. |

Which views offer Calc is a pack's choice (`python: { enabled: true }` in `pack.yaml`), not configuration; see
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#calc-python-snippets).

### `drishti.var` — the VaR engine

The VaR engine (the complete guide [VAR_GUIDE.md](../guides/VAR_GUIDE.md): the explorer, the data contract in Part II, how the
engine works in Part III; its settings in [section 12](../guides/VAR_GUIDE.md#12-settings)) computes VaR, expected shortfall, contributions, history and backtests for any scope from
the trades' stored 509-day P&L vectors, as the caller, when asked. Nothing it answers is stored except captures: the ones people make,
and the official end-of-day captures of every node, which it also publishes to `risk-results/var-official` for other systems.
Positions are derived from the trades when a date loads, by the rules of a loaded pack's `positions:` section (the market-risk pack's),
else by `drishti.var.positions`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_VAR_ENABLED`) | The engine at all. Off: every VaR request answers `403 DRS-5002` (*the VaR engine is switched off on this server*), the explorer included, and no official capture runs. |
| `confidence` | `0.99` (`DRISHTI_VAR_CONFIDENCE`) | VaR's confidence: 0.99 or 0.95; refused at start outside (0.5, 1). Every label (console, CLI, Home facts) follows it; limits are set against it, so a change changes limit use; backtesting keeps 99% (the Basel light). `confidence=` per request recomputes the headline, never official. |
| `es-confidence` | `0.975` (`DRISHTI_VAR_ES_CONFIDENCE`) | Expected shortfall's confidence; refused outside (0.5, 1). |
| `confidences` | `[0.95, 0.975, 0.99, 0.995]` | The VaR confidences a person may choose on the VaR page (Confidence), in a command (`VAR BANK 95`), the API (`confidence=`) and the CLI; each refused at start outside (0.5, 1); `confidence` is always offered and is the default. Another rank of the same sorted vector: nothing is stored. Official records stay at `confidence`. [VAR_GUIDE.md section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen). |
| `es-confidences` | `[0.95, 0.975, 0.99]` | The ES confidences that may be chosen (the page's ES, `ES 95` in a command, `esConfidence=`); `es-confidence` always offered. |
| `ten-day-method` | `sqrt10` (`DRISHTI_VAR_TEN_DAY_METHOD`) | The 10-day VaR when the method is not named (`VAR BANK 10D`, `horizon=10d`): `sqrt10`, the 1-day VaR, ES and every vector-derived figure × √10 (the conservative common practice; assumes independent days and a linear book), or `sum`, each 10-day scenario the sum of 10 consecutive 1-day scenario P&Ls (N − 9 overlapping windows: 509 give 500; captures trends and clustering; still today's positions and sensitivities). Anything else is refused at start. Both are always available by name. |
| `scenario-window` | `250` (`DRISHTI_VAR_SCENARIO_WINDOW`) | N ≥ 20 or `all`: the newest N scenarios of each date's set, every row cut to them at load; k = ceil(N (1 − confidence)); a shorter set is used whole (a warning in the provenance); in the hot file's digest; official records state it. `window=` per request recomputes the headline, never official. [VAR_GUIDE.md section 18.5](../guides/VAR_GUIDE.md#185-the-scenario-window-and-the-confidences). |
| `rank-rule` | `kth-worst` | `kth-worst` (k = ceil(N (1 − confidence)): the 6th worst of 509) or `interpolated`. |
| `component-window` | `5` | Component VaR averages this many scenarios ranked around the VaR scenario (k−2 to k+2). |
| `threads` | `0` (`DRISHTI_VAR_THREADS`) | Cores the vector sums use; `0` is every core. |
| `memory-budget-mb` | `0` (`DRISHTI_VAR_MEMORY_MB`) | Most memory one date's vectors take; `0` is a quarter of the heap. The heap tier's position vectors (summed once and kept) take at most a quarter of it a date; past that a position's vector is summed on each ask. Measured on the heap tier at 8 bytes a value, on the mapped tier at 4 (the float32 hot copy). A date over it is refused whole (`503 DRS-5204`). |
| `hot-dates` | `10` (`DRISHTI_VAR_HOT_DATES`) | Business dates kept open (mapped, or on the heap): the least recently used leaves first; the date just used, the newest business date and the dates pinned in memory always stay (VAR_GUIDE section 26.8). |
| `refusal-ttl-seconds` | `300` | A date refused for room (capacity, disk) is remembered this long: `503` at once with `Retry-After`, no lake read. |
| `open-heap-mb` | `0` (`DRISHTI_VAR_OPEN_HEAP_MB`) | The byte safety net over every open book's heap; `0` is automatic (0.4 × heap − 512 MB − load-threads × 1.6 GB, at least a tenth of the heap). A date read in the last `-Ddrishti.open-dates.net-grace-seconds` (120) is never closed by the net ([JVM flags](#jvm-flags-for-a-long-run)). |
| `fiscal-year-end` | blank (`DRISHTI_FISCAL_YEAR_END`) | The fiscal year-end, `MM-DD`, for the `fiscal-year-end` pin. |
| `pinned` | every rule off | Pinned dates (`month-end`, `quarter-end`, `year-end`, `fiscal-year-end`, `stress-window`, `dates`): `back` and `keep: disk|memory` (VAR_GUIDE section 26.8). |
| `version-check` | `30s` (`DRISHTI_VAR_VERSION_CHECK`) | The version watch: on a request, an open date compares its lake version (the connector's own, refreshed every `refresh-seconds`) with the one it was loaded from at most this often; when it moved without a data-load signal, the date is reloaded in the background and swapped in, the answers meanwhile carry a warning. One reload a date at a time; a failed one keeps the old date and says why. A date with intraday updates applied is left to its signal. `0` switches the watch off. |
| `load-threads` | `2` | The threads that load business dates from the lake (and shard books, and listed currencies on first ask): a pool of their own (`drishti-var-load-*`, `drishti-var-shard-load-*`), never the common pool that parallel streams share. A load waiting for another on the pool is compensated, never deadlocked. |
| `view-cache-mb` | `64` | The most memory the viewers' views take (each a bitset of the trades a role set sees, per date, version and currency); an idle view goes after an hour, and a date's views go when the date leaves memory or is reloaded ([RESOURCES_AND_SCALE.md 5.3](../architecture/RESOURCES_AND_SCALE.md#53-releases-with-the-date-d4)). |
| `tier.mode` | `mapped` (`DRISHTI_VAR_TIER`) | `mapped`: each loaded date is written once to a local **hot file** (float32 position rows in hierarchy order, the trades of fungible positions, the aggregates in float64, columns and links) and memory-mapped; the heap keeps only the aggregates, columns and links, and a history date opens again from its file without reading the lake ([VAR_GUIDE.md section 26](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file)). `heap`: every trade's vector in float64 on the heap. |
| `tier.dir` | `${DRISHTI_VAR_HOT_DIR:${drishti.data.dir:./data}/var/hot}` | The hot files, one folder per business date (`inputs-<digest>.vhot`; the load's `spill-*.f64` and `build-*.part` while it runs). Local disk: about 2.3 GB a date per million trades. |
| `tier.disk-dates` | `60` | Hot files kept on disk; the least recently used beyond are deleted (never an open date's). |
| `tier.max-disk-gb` | `0` (`DRISHTI_VAR_MAX_DISK_GB`) | The most disk every hot file takes (dates, listed currencies, shard books); `0` is half the disk's size (PO-4). The least recently used beyond go, never an open one; `disk-dates` stays a second bound. |
| `tier.keep-on-shutdown` | `false` (`DRISHTI_VAR_KEEP_HOT_FILES`; the server images: `true`, PO-10) | `false`: the hot files are deleted when the server stops, and any a stopped server left are deleted at start; `true`: kept, and reused while their inputs' digest matches. |
| `tier.close-grace-seconds` | `60` | How long an evicted date stays mapped for the requests still reading it before it is unmapped. |
| `history-days` | `250` | The history and backtest window, in business days. |
| `component-scale-to-var` | `true` | Components are scaled to add up to the VaR exactly (`false`: to the smoothed VaR). |
| `parallel-rows` | `4096` | The fewest rows a sum splits across threads. |
| `rows-per-chunk` | `65536` | Rows per chunk of the in-memory vector store. |
| `histogram-bins` | `42` | Bins of the P&L distribution sent to the screen. |
| `max-parts` | `5000` | Most parts a contribution table computes at once; more: the largest by gross size (market value, PV, quantity), and the answer says so. |
| `path-page` | `60` | Rows of the ranked P&L path per page. |
| `smat.max-days` | `${DRISHTI_VAR_SMAT_MAX_DAYS:60}` | The most business dates one **scenario matrix** reads (`SMAT <id> N`, `GET /api/v1/var/matrix`); N asked above it is cut to it and the answer says so. Each date not open in memory is one read by id (a trade or a position its source sends whole), an official capture (a node), or one date opened by the engine (anything else), one at a time ([VAR_GUIDE.md section 3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat)). |
| `kinds.vector`, `kinds.position`, `kinds.hierarchy`, `kinds.scenario-set`, `kinds.portfolio` | `pnl-vector`, `position`, `risk-hierarchy`, `scenario-set`, `portfolio` | The kinds the engine reads (the market-risk pack's), and `position`, the kind its derived positions are served as (the `var-positions` connector). The data-load signal of an input kind (and of `trade`) reloads that date and captures it officially. |
| `kinds.scenario-pv`, `kinds.fx-scenario`, `kinds.fx-spot` | `scenario-pv`, `fx-scenario`, `fx-spot` | Trades sent as **scenario PVs** in their own currency (beside or instead of P&L vectors; converted to USD P&L when the date loads), each currency's **supplied** scenario FX, and the **FX history** scenario rates are derived from when none is supplied (the market-data pack's `fx-spot`). `scenario-pv` and `fx-scenario` are inputs (their data-load signal reloads the date); `fx-spot` is not ([VAR_GUIDE.md section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency)). |
| `access` | `{}` | Per role and hierarchy node, `positions` or `aggregate`: an aggregate-only role sees figures but no positions or trades below that node (`drishti.var.access.<role>[MGMT~DESK-RATES]=aggregate`). |
| `positions.live-statuses` | `[NEW, VALIDATED, AMENDED]` | Trade statuses (the vector rows' `TRADE_STATUS`) that count on their date; a row with no status counts; a matured trade (`MATURITY_DATE` before the date) never does. Used when no loaded pack has a `positions:` section. |
| `positions.rules` | `[]` | First match wins: `{name, groups: [PRODUCT_GROUP…], types: [PRODUCT_TYPE…], key: [SECURITY_ID, ISIN…]}`; a trade's position is `POS-<TT_BOOK>~<the first key field it carries>`, else (no rule, no key) one position per trade, `POS-<TT_BOOK>~<TRADE_ID>` ([VAR_GUIDE.md section 15.6](../guides/VAR_GUIDE.md#156-positions-derived-by-drishti)). A loaded pack's `positions:` section wins. |
| `official.enabled` | `true` (`DRISHTI_VAR_OFFICIAL`) | Capture every node of every hierarchy officially when a date's inputs are complete (after the data-load signal of an input, and once at start for the newest date without a record). The node history reads these captures. |
| `official.publish` | `true` | Also append the official figures to `<root>/risk-results/var-official` (Delta or Iceberg by `official.format`, append-only: a restated date adds rows with the next `RESULT_VERSION`). No engine reads that folder. |
| `official.root` | `${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}` | The root the results are published under: a local or shared folder, or `s3://bucket/prefix` (before, an `s3://` root ended in `publishError`). |
| `official.format` | `delta` | `delta` (Drishti's lake writer) or `iceberg` (Drishti's Iceberg engine: append commits only, 7 years of snapshots, every metadata file kept); anything else is refused at start. [VAR_GUIDE.md section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results). |
| `official.storage` | none | The `s3.*` settings for an `s3://` root, as in a connector file: `s3.region`, `s3.endpoint`, `s3.access-key`/`s3.secret-key` (none: the default AWS credential chain), `s3.path-style`. Each Delta commit on S3 is an exclusive create. |
| `official.retention-years` | `7` | The published table keeps its log and its removed files this long (`delta.logRetentionDuration`, `delta.deletedFileRetentionDuration`): its time travel reaches back that far. |
| `official.on-start` | `true` | At start, capture the newest date when it has no official record yet (a lake built while the server was down). |
| `currencies` | `USD,EUR,CAD,JPY,GBP` (`DRISHTI_VAR_CURRENCIES`) | The reporting currencies **built ahead**: once a date's USD book has loaded, a background thread builds each listed currency's book into its own hot file (`<hot>/<date>-<CCY>/`), so a VaR in it never waits on its first ask; USD always, first. Any other currency with scenario FX is built on first ask. Mapped tier only (on the heap tier they stay first-ask). About 2.5 KB a trade a currency a date on disk ([VAR_GUIDE.md section 15.11](../guides/VAR_GUIDE.md#1511-reporting-currencies-built-ahead-per-trade-currency-totals-and-the-picker)). |
| `intraday.kafka.window` | `5m` (`DRISHTI_VAR_INTRADAY_KAFKA_WINDOW`) | The PV topic's micro-batch window (the `pv-updates` connector, [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)): every record received in it is appended to the lake as one intraday batch, then applied. |
| `intraday.kafka.max-trades` | `500` (`DRISHTI_VAR_INTRADAY_KAFKA_MAX_TRADES`) | A window is applied earlier once this many trades wait; an administrator's **Apply now** applies it at once. |
| `root-name` | empty (`DRISHTI_VAR_ROOT_NAME`) | The name every hierarchy's root node shows (the top of the VaR tree, the breadcrumbs, the headline, the command line, `var-result`, captures and new official records); empty keeps the data's `NAME`. Records captured before a change keep the name they were captured with. The name is part of a hot file's digest, so a change rebuilds the date's file at its next load. |
| `inputs` | none (`default: {form: any, grain: any}`) | An optional pin per source system of what its PV records are: `inputs: {ENDUR: {form: pv, grain: position}, CALYPSO: {form: pnl, grain: trade}, default: {form: any, grain: any}}` (`form` pv, pnl or any; `grain` trade, position or any). Every record says what it is (`VALUE_TYPE`, `GRAIN`); a pin is a cross-check: a record that disagrees is refused (`DRS-5209`). Read at start (a change needs a restart and applies from the next date loaded). [VAR_GUIDE.md section 15.13](../guides/VAR_GUIDE.md#1513-inputs-any-form-any-grain). |
| `root-names` | none | One hierarchy's root name, over `root-name`: `root-names: {MGMT: "Group", LEGAL: "Group legal entities"}` (hierarchy ids in any case). |
| `hierarchies` | none (a commented example) | The hierarchies, id to settings: `title`, `source` (`risk-hierarchy`: the rows, book-based; `columns`: built from the trades' columns, the default when `levels` is given), `levels` (`"LIMIT_LETTER > DESK"` or a list), `level-names`, `root` (`{id, name}`), `ids` (`path`, default: `LL~LL-0003/DESK-RATES`; `leaf`: `MGMT~DESK-RATES`, when each value has one parent), `word` (the scope word, `LL:LL-0003`), `limits` (the kind the limits come from, `var-limit`). Wins over a loaded pack's `hierarchies:` block (the market-risk pack's: MGMT and LEGAL from the rows, LL from `LIMIT_LETTER > DESK`); neither: MGMT and LEGAL from the rows. Checked at start (`DRS-5213`). The exposure engine builds the same hierarchies. [VAR_GUIDE.md section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns). |
| `kinds.limit` | `var-limit` | The nodes' VaR limits by hierarchy and node id, in USD, read for a hierarchy whose `limits` names it; a node without a row has no limit. An input: its data-load signal reloads the date ([VAR_GUIDE.md section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits)). |
| `currency-retention-dates` | `5` (`DRISHTI_VAR_CURRENCY_RETENTION_DATES`) | The newest business dates whose listed currencies are built ahead and kept; older dates' listed-currency hot files are deleted after each build (asked again, they are built on first ask). |
| `official.currencies` | `[USD]` | The reporting currencies captured officially: USD always (first, and the only one published to `risk-results/var-official`); each other one (`[USD, EUR]`) is captured from the date's book in that currency and kept in the content store (`var/official/<CCY>/<date>.json`), read by `GET /api/v1/var/official?ccy=EUR` and by a node's history in that currency. |
| `official.jsonl` | `true` | Also write each published date as JSON Lines beside the table, one row a line: `<root>/risk-results/jsonl/var-official/<date>/var-official-v<table version>.jsonl`, one file per commit ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)). |

#### `drishti.var.sharding` — VaR sharding across Drishti*net

Off by default: what is sharded is the market-risk pack's `sharding:` block (`enabled`, `key`, `kinds`, `target-shard-mb`, `replicas`); with
no block and no `enabled` here every date loads whole. The same five keys here win over the block when set; a value that is not
valid refuses the start. Design and operation: [VAR_SHARDING.md](../architecture/VAR_SHARDING.md), [OPERATIONS.md](OPERATIONS.md#var-shards).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | unset (the pack decides; off without a block) | Sharding on or off. |
| `key` | `[LEGAL_ENTITY, DESK]` | The shard key: `[LEGAL_ENTITY, DESK]` or `[LEGAL_ENTITY]` (the hot file's order; a key value is never split). |
| `kinds` | `[pnl-vector, scenario-pv]` | The kinds sharded: the trades' scenario P&L (both are always sharded together); every other kind is read whole by each member. |
| `target-shard-mb` | `2048` | A shard's target size (its float32 rows); a desk larger than it is a shard of its own. |
| `replicas` | `2` | Each shard's owners. |
| `stable-seconds` | `30` | How long the members must stay the same before shards move to new owners. |
| `grace-seconds` | `30` | How long a new owner must have been ready before the old one releases a shard. |
| `coordinator-only` | `false` | This member owns no shard: it puts dates together and coordinates queries only. |
| `zone-tag` | none | The member tag prefix naming its zone (`az`: a tag `az:us-east-1a`); a second owner goes to another zone. |
| `pins` | none | A shard (`S3` or its range) pinned to a member id, its first owner whatever the hashing; the same on every member (tests). |
| `drain` | `false` | Start drained: hand any shard over and take none (also `POST /api/v1/var/shards/drain`, `drishti.py var shards --drain`). |
| `call-timeout-seconds` | `30` | A partial sum's time on an owner before the next member is asked. |
| `skeleton-timeout-seconds` | `600` | An owner's time to build and send a shard's skeleton. |
| `rebalance-seconds` | `2` | How often a member looks at the members and its shards. |
| `release-after-seconds` | `900` | A shard book is released (unmapped; its hot file stays for the disk budget) once no member has had its date open this long, the date is not among the lake's newest `hot-dates`, and nothing read the book here meanwhile. |

### `drishti.ccr.exposure` — the exposure engine

The exposure engine (the guide and data contract [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md), its settings in [section 12](../guides/EXPOSURE_GUIDE.md#12-settings)) computes exposure profiles (EE, ENE, effective EE, PFE at any
confidence, EPE, EEPE, the peaks, CE, EAD under the internal model) of any counterparty scope (`NSET:`, `CPTY:`, `GROUP:`, `LE:`,
`DESK:`, `BOOK:`, `TRD:`, `BANK`, combined with `+ - &`) from an upstream simulation's paths (`simulation-run`, `exposure-path`): netting
per path, each CSA's collateral per path with the margin period of risk, as the caller, when asked. Nothing it answers is stored except
captures: the ones people make, and the official end-of-day captures of every netting set, counterparty, group and hierarchy node, which
it also publishes to `risk-results/exposure-official` and `exposure-official-cpty`. The netting-set and counterparty pages show its
figures as they open. The exact pre-deal what-if (method `paths`) runs in the Python exposure engine, the compute pool `exposure` of
`config/compute/compute.yaml` (`drishti_compute.exposure.whatif:ExposureWhatIfEngine`, standard library only, up to
`DRISHTI_COMPUTE_EXPOSURE_WORKERS` workers of 2 GB, timeout 60 s), behind the reactive connector `exposure-whatif`; it needs the
compute workers on (`DRISHTI_COMPUTE_ENABLED=true`), otherwise that method answers `503 DRS-5446` and the `addon` proxy still works.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_EXPOSURE_ENABLED`) | The engine at all. Off: every exposure request answers `403 DRS-5002` (*the exposure engine is switched off on this server*), and the pages show no exposure. |
| `confidence` | `[0.95, 0.99]` | The PFE confidences every answer computes (any `q` in (0.5, 1) may be asked per request). |
| `limit-confidence` | `0.95` | The PFE a credit limit is set on: the default `q` of an answer and of the official capture. |
| `rank-rule` | `kth` | `kth` (the ceil(P q)-th smallest exposure) or `interpolated`. |
| `collateral-model` | `lagged` | `lagged` (what the CSA calls at the look-back point a margin period of risk earlier; today's collateral where there is none), `current` (today's collateral on every path) or `none`. The hot file's exposure matrices are built with it; another model per request is computed from the value matrices. |
| `im-model` | `current` | `current` (the initial margin held today, the collateral holding's `IM_HELD`, on every path) or `none`. |
| `alpha` | `1.4` | EAD under the internal model = alpha × EEPE (informational). |
| `contribution-window` | `21` | Paths ranked around the PFE quantile a PFE contribution averages over. |
| `hot-dates` | `10` (`DRISHTI_EXPOSURE_HOT_DATES`) | Business dates kept open: the least recently used leaves first; the date just used, the newest business date and the dates pinned in memory always stay (as VaR's). `refusal-ttl-seconds`, `open-heap-mb`, `fiscal-year-end` and `pinned` as VaR's. |
| `load-threads` | `2` | The threads that load business dates from the lake: a pool of their own (`drishti-exposure-load-*`), never the common pool. |
| `version-check` | `30s` (`DRISHTI_EXPOSURE_VERSION_CHECK`) | The version watch, as VaR's: an open date restated in the lake without a signal is reloaded in the background within this interval (`0`: off). |
| `view-cache-mb` | `16` | The most memory the viewers' views take (per date, version and role set); idle ones go after an hour, a date's with the date. |
| `memory-budget-gb` | `0` (`DRISHTI_EXPOSURE_MEMORY_GB`) | Most memory one date's netting-set value matrices take while it loads (float64, G × P per set); `0` is a quarter of the heap. A date over it is refused whole (`503 DRS-5445`). |
| `hot-folder` | `${DRISHTI_EXPOSURE_HOT_DIR:${drishti.data.dir:./data}/ccr/hot}` | The hot files, one folder per business date (`run-<digest>.xhot`, format `DRSHEXP1`: each set's value, exposure and negative exposure in float32, one row of P paths per grid date). Local disk: sets × P × (G + 2 × primary dates) × 4 bytes a date. |
| `tier` | `mapped` (`DRISHTI_EXPOSURE_TIER`) | `mapped`: each loaded date written once to its hot file and memory-mapped (reopened without the lake while its inputs are unchanged); `heap`: float64 matrices on the heap. |
| `disk-dates` | `60` | Hot files kept on disk; the least recently used beyond are deleted, never an open date's nor one the retention keeps (`retention.set-matrices-*`). |
| `max-disk-gb` | `0` (`DRISHTI_EXPOSURE_MAX_DISK_GB`) | The most disk the hot files take; `0` is a quarter of the disk's size (PO-4). **It wins over the retention** (PO-3): a retained date beyond it is deleted from local disk and read from the lake when asked. |
| `keep-on-shutdown` | `false` (`DRISHTI_EXPOSURE_KEEP_HOT_FILES`; the server images: `true`) | `false`: the hot files are deleted when the server stops, and any a stopped server left at start; `true`: kept and reused while their digest matches. |
| `close-grace-seconds` | `60` | How long an evicted date stays mapped for the requests still reading it. |
| `max-scope-netting-sets` | `20000` | The most whole netting sets an interactive answer sums; more: `503 DRS-5443` (the official capture computes the bank). |
| `max-parts` | `5000` | Most parts a contribution table returns. |
| `histogram-bins` | `42` | Bins of an exposure distribution (`/scope/distribution`). |
| `t0-tolerance` | `0.10` | The load lists a trade whose paths' mean at the first grid date departs from its MtM today by more than this share of it (or 5% of its notional, the larger). |
| `kinds.run`, `kinds.paths`, `kinds.netting-set`, `kinds.csa`, `kinds.collateral`, `kinds.counterparty`, `kinds.group`, `kinds.hierarchy` | `simulation-run`, `exposure-path`, `netting-set`, `csa`, `collateral-balance`, `counterparty`, `counterparty-group`, `risk-hierarchy` | The kinds the engine reads. The data-load signal of the first five reloads that date and captures it officially. |
| `kinds.limit` | `credit-limit` | The stored credit limits, read as stored for the explorer's limit column and a counterparty's limit use (LIM stays parked: usage is not computed against them; the stored limits were sized on stored profiles, so the use reads low). |
| `access` | `{}` | Per role and counterparty, group or legal entity: `trades`, `aggregate` (figures, no netting set or trade below it) or `none` (`drishti.ccr.exposure.access.<role>[CP-ALDERSHOT]=aggregate`); a role with entries sees nothing else, a role without sees all at `trades` when it may open `exposure-path`. |
| `official.enabled` | `true` (`DRISHTI_EXPOSURE_OFFICIAL`) | Capture every netting set, counterparty, group and hierarchy node officially when a date's run is complete (after the data-load signal, and once at start). |
| `official.publish` | `true` | Also append the official figures to `<root>/risk-results/exposure-official` (hierarchy nodes) and `exposure-official-cpty` (netting sets, counterparties, groups: restricted). Append-only; no engine reads them. |
| `official.root` | `${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}` | The root the results are published under: a folder or `s3://bucket/prefix`. |
| `official.format` | `delta` | `delta` or `iceberg`, as VaR's `drishti.var.official.format`. |
| `official.storage` | none | The `s3.*` settings for an `s3://` root, as VaR's `drishti.var.official.storage`. |
| `official.retention-years` | `7` | The published tables keep their log and removed files this long (time travel). |
| `official.on-start` | `true` | At start, capture the newest date when it has no official record yet. |
| `official.jsonl` | `true` | Also write each published date as JSON Lines beside the tables: `<root>/risk-results/jsonl/<exposure-official or exposure-official-cpty>/<date>/<table>-v<table version>.jsonl`, one file per commit. |
| `retention.trade-paths-days` | `20` | Full trade paths are kept for the last 20 business dates ... |
| `retention.trade-paths-month-end-years` | `1` | ... and each month-end for a year. The lake's owner applies it; `GET /api/v1/exposure/retention` lists each date and whether its paths are beyond their window (EXPOSURE_GUIDE.md section 27.3, decided 2026-10-26). |
| `retention.set-matrices-years` | `1` | The netting-set matrices (the hot files) of every date within a year are kept on disk ... |
| `retention.set-matrices-month-end-years` | `7` | ... and each month-end's for seven years. |
| `retention.statistics-years` | `7` | The official records (every scope's figures and profile) are kept seven years; older ones are removed after each capture. |
| `retention.trade-contributions-years` | `7` | Every trade's EE contributions per primary date, kept beside each official record, seven years. |

### `drishti.bi` — Rupaka (business intelligence), phase 1: the shell and the catalogue

Workspaces, items, versions and the catalogue ([BI_GUIDE.md](../guides/BI_GUIDE.md)). Content lives in the identity database; the BI folder holds
shipped, read-only files. The BI powers are granted on roles, see below.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | The BI endpoints answer. Off: `403 DRS-9006`, and the console (which asks the server) hides the BI menu. |
| `dir` | `./config/bi` (`DRISHTI_BI_DIR`) | The BI folder: `reports/<id>.report.yaml`, `boards/<id>.board.yaml`, `notebooks/<id>.notebook.yaml`, `python/`. Shipped files appear as published, read-only items of the workspace *Shipped content*. Not inside a pack. See `config/bi/README.md` and [BI_DESIGN.md](../architecture/BI_DESIGN.md#16-rupaka-the-original-design-and-every-phase), "Where BI files live". |
| `watch` | `auto` | How the BI folder is watched, live, with no restart: `auto` (file events with a polling safety net), `poll`, or `off` (the folder is read on the first request after `folder-refresh`). A file added appears, a file changed becomes the next version, a file deleted goes; a bad file keeps its last good version and is listed in Admin, BI settings and on Health. |
| `folder-refresh` | `5s` | The polling interval of the watcher (with `watch: off`, how long one read is reused). |
| `workspace-creation` | `authors` | Who creates shared workspaces: `authors` (`bi-author`), `stewards` (`bi-steward`) or `admins` (`bi-admin` only). An administrator can change it at run time in Admin, BI settings (kept in the identity database; it then overrides this). |
| `max-body-bytes` | `262144` | The largest YAML body one item may hold (`422 DRS-9003` above it). |
| `versions-keep` | `50` | Published versions kept per item; the oldest go first. |
| `usage-days` | `30` | Days of views counted as an item's usage (at most 365). |
| `recent` | `12` | Rows under Recent, Certified and Shared on Home. |
| `list-limit` | `200` | Most rows one catalogue or workspace list returns (at most 2000). |

**BI powers on roles.** Under `drishti.security.roles.<role>` the key `bi` is a list of `bi`, `bi-author`, `bi-steward`, `bi-admin`
(see [USER_MANAGEMENT.md](USER_MANAGEMENT.md#bi-powers-rupaka)); the built-in `viewer` holds `[bi]`, `author` and `approver` hold
`[bi, bi-author]`, `admin` holds all four. A role defined in Admin, Roles gets its powers from Admin, BI settings. Review before publish reuses
`drishti.governance.four-eyes` and the `approve` power.

**Console.** `bi.enabled` (`DRISHTI_BI_ENABLED`, default `true`) switches the BI menu, the `/bi` pages and the `RUPAKA` keyword on or off in the console.

**Console, compressed static files.** The console serves `/static/...` compressed (the 1.1 MB `echarts.min.js` travels as 370 KB gzipped): `static.compress` (default `true`), `static.compress_min_bytes` (`1024`; smaller files are sent as they are),
`static.brotli_quality` (`5`; Brotli is used when the `brotli` package is installed and the browser accepts it, else gzip) and `static.cache_dir` (empty: the system temp folder, `drishti-console-static`). Each file is compressed once per version
(path, size and modification time) and served from the cache; range requests, images and fonts go as they are.

### `drishti.bi.query` — the dataset query engine (phase 2)

The embedded DuckDB that answers dataset queries ([BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules), [BI_GUIDE.md](../guides/BI_GUIDE.md) section 13) and the layers it keeps. It starts its connection on the first query. Every limit
answers with its own code (the codes are in [API_GUIDE.md](../guides/API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2)). Defaults are what a laptop can carry.

| Key | Default | Meaning |
|---|---|---|
| `store-dir` | `./data/bi-store` (`DRISHTI_BI_STORE`) | The BI store: `columns/<dataset>/<table>/date=<d>/part-<id>.parquet` (the file its `meta.json` names), `rollups/...`, `live/...`, `duckdb-tmp/`. Anything in it can be rebuilt from the connectors. |
| `store-max-gb` | `50` | The most disk the layers take: beyond it the least recently built dates go (a table's newest date is always kept); a removed date is built again when a page asks for it. Checked at start and every ten minutes. |
| `store-max-dates` | `260` | The most business dates each table (and rollup) keeps in its layer; the oldest beyond go. `0`: no limit by dates. |
| `timeout` | `30s` | Longest a query may run; it is then cancelled (`504 DRS-9024`). |
| `max-rows` | `100000` | Most rows a query returns without a `limit` (`413 DRS-9022`). |
| `max-bytes` | `67108864` | Most bytes of an Arrow answer (`413 DRS-9023`). |
| `max-concurrent` | `8` | Queries running at once on the server (`429 DRS-9025`). |
| `max-concurrent-per-user` | `3` | Queries running at once for one user (`429 DRS-9025`). |
| `admission` | `2s` | How long a query waits for a free slot before it is refused as busy. |
| `json-max-rows` | `5000` | Most rows of an answer in JSON (`413 DRS-9022`; ask for `format=arrow`). |
| `cache-entries` | `200` | Results kept in the result cache; `0` switches it off. The key is dataset, version, query, the reader's row-rule set and masks, and the dataset's data generation, so a data load or a changed dataset can never serve an old answer. |
| `cache-max-bytes` | `67108864` | The cache's budget (entries weigh at least `cache-max-bytes / cache-entries`). |
| `cache-ttl` | `15m` | Longest a cached result is served. |
| `memory-limit` | `768MB` | DuckDB's memory limit (it spills to `store-dir/duckdb-tmp`). |
| `threads` | `2` | DuckDB's worker threads. |
| `scan-batch-rows` | `65536` | Rows in one batch when a layer is built from a connector's scan. |
| `record-read-max` | `50000` | Most documents read one by one when a connector cannot scan the declared columns, or a table is over an array (`each`); the build stops there and warns. |
| `record-read-budget` | `60s` | How long that document read may take. |
| `latest-ttl` | `30s` | How long "the latest business date" of a table is trusted before the source is asked again (only when the newest date held is older than the current business date). |
| `eager-rebuild` | `true` | On a data-load signal, read the date again in the background (`false`: on the next query that needs it). |
| `live-ttl` | `5s` | How long a `live` table's snapshot is served before the connector is read again. Used only when `drishti.bi.live.enabled` is `false` (live tables are then the phase 2 snapshot); otherwise a live table is kept in memory and updated as the source pushes ([`drishti.bi.live`](#drishtibilive--streaming-bi-phase-5)). |
| `infer-sample` | `5000` | Values of a column the relationship finder looks at, and documents it reads for a table whose connector promotes no columns. |
| `infer-min-match` | `0.8` | The share of a column's distinct values found among another table's ids for a relationship to be proposed. |

### `drishti.bi.report` — reports (phase 3)

How many rows each kind of visual asks the engine for ([BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave) section 11). The engine's own limits (`drishti.bi.query.*`, above) still apply on top; a visual's own `limit:` replaces the default of its kind.

| Key | Default | Meaning |
|---|---|---|
| `chart-rows` | `500` | Most categories one chart asks for (a chart says when it was cut). |
| `table-rows` | `1000` | Most rows a table or matrix asks for. |
| `slicer-values` | `1000` | Most distinct values a slicer lists. |
| `show-rows` | `1000` | Most rows *Show the rows* returns. |
| `top-candidates` | `10000` | Most values a top-N filter ranks before it keeps its N. |
| `distribution-rows` | `20000` | Most members a **histogram** or **box plot** reads (the server bins or summarises them; the picture is a few dozen rows). |
| `totals-max-rows` | `2000` | Most rows one level of **true totals** (a matrix's subtotal level or a table's total) may return. |
| `perspective-rows` | `20000` | Most rows a 2.1 `perspective-*` visual is sent. The page draws them in Drishti's own grid (no Perspective, no WebAssembly); until then the browser held and pivoted them (the retest, in RUPAKA_POC.md, since deleted, measured 10,000 rows at 21 MB of JS heap). |
| `python-rows` | `10000` | Most rows a **python visual** hands the Python runner ([BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python), phase 6). |
| `python-packages` | `pandas, numpy, pyarrow, scipy` | The packages a Python item's manifest may list (publishing refuses another: `DRS-9070`); only those `config/compute/locks/python.lock` pins are offered (a test holds the two together). |
| `python-max-seconds` | `60` | The largest `timeout` a Python manifest may ask for. |
| `python-max-memory-mb` | `1024` | The largest `memory` a Python manifest may ask for, in megabytes. |

### `drishti.bi.transform` — Python Transforms and composed datasets

A **Python dataset** (or a Python-driven table) is made by a Python Transform, run on the server's compute pool `python` ([PYTHON_COMPUTE.md section 19](../architecture/PYTHON_COMPUTE.md#19-the-python-runtime-for-code-people-write): `compute.pools.python` in `config/compute/compute.yaml`, its strict sandbox and pinned libraries); the server reads the inputs as the reader, hands them over as Arrow files and reads the frame back as Parquet, checked against the declared columns and types ([BI_GUIDE.md › Python in BI, section 13](../guides/BI_GUIDE.md#13-transforms-a-table-in-a-table-out)). The result is kept per reader and version of the inputs. The package allow-list, `timeout` and `memory` limits are the Python items' own (`drishti.bi.report.python-*`). `compose-source-max-rows` also limits a **composed** dataset's sources.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: every Python run (Transforms, Python measures and visuals, Notebook cells, the sample run) answers `DRS-9074`. |
| `cache-ttl` | `30m` | How long a Transform's result is served to the reader it was made for (and only while the inputs are unchanged). |
| `cache-entries` | `20` | Results kept per derived dataset (a reader and a version of the inputs each); the oldest go first. |
| `max-input-rows` | `50000` | Most rows of one input frame handed to a Transform (`DRS-9072`). |
| `max-rows` | `100000` | Most rows a Transform may return (`DRS-9072`). |
| `max-bytes` | `33554432` | Largest result file (`DRS-9072`). |
| `timeout` | `60s` | Longest a run may take (the item's own `timeout` may be shorter; `DRS-9072`, HTTP 504). |
| `max-concurrent` | `2` | Transforms running at once on the server. |
| `queue-wait` | `5s` | How long a run waits for a place before `DRS-9074`. |
| `compose-source-max-rows` | `50000` | Most rows one source of a composed dataset may answer (`DRS-9022`): group it by less or filter it in `from`. |

### `drishti.bi.refresh` — scheduled dataset refresh

A dataset's `refresh:` schedule rebuilds its columns and rollup layers ([BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 7.2](../guides/BI_GUIDE.md#72-scheduled-refresh)); a failed run is retried with the shared retry policy, area `bi` (`drishti.retry.bi.*`, falling back to `drishti.retry.*`: see Retries above).
Times are in `drishti.business-date.zone`, business days by `drishti.business-date.calendar`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: no schedule runs (a data-load signal and `POST /datasets/{id}/refresh` still do). |
| `tick` | `30s` | How often the scheduler looks for a refresh that is due or a retry whose wait is over (a schedule runs within one tick of its time). |
| `max-attempts` | `4` | Tries for one scheduled refresh (the first included) before it is left `failed` until its next scheduled time (1 to 20). |
| `load-delay` | `2s` | For `after-load`: how long after the data-load signal the refresh starts. |
| `history` | `25` | Past runs of each job kept and shown under **Recent refreshes** (0 to 500). The state and the history are written to `<drishti.bi.query.store-dir>/refresh/state.json`, so they survive a restart; a run in progress when the server stopped shows as `interrupted`, and a time that passed while it was down is run once at start-up. |

### `drishti.bi.ai` — BI with AI (stage 2)

The BI hooks on the AI layer ([BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number)). They also need `drishti.ai.enabled` (off by default) and a provider ([AI.md](AI.md#1-model-providers));
the policy's feature names are `bi-draft`, `bi-ask`, `bi-explain`, the authoring helpers `bi-suggest`, `bi-explain-checks` and `bi-helper`, and a subscription's narrative summary `subscription-narrative` (`drishti.ai.policy.features`, above).

| Setting | Default | Meaning |
|---|---|---|
| `model` | blank | `provider/model` for the hooks; blank is `drishti.ai.default-model` |
| `values` | `labels-only` | `labels-only`: the model sees names, types and descriptions only. `aggregates`: it may also word the reader's own masked aggregate results (sends their figures to the provider) |
| `max-question-chars` | `600` | Longest question or description |
| `max-tokens` | `2000` | Longest answer asked of the model |
| `timeout-seconds` | `30` | One model call |
| `ask-rows` | `200` | Most rows an Ask answer holds, and the most the model sees of them in `aggregates` mode |
| `explain-dimensions` | `3` | Dimensions Explain breaks a number down by |
| `explain-members` | `5` | Members listed per dimension |
| `suggestions` | `6` | Most visuals the designer copilot proposes at once |
| `helper-items` | `8` | Most items the dataset helper proposes per kind (relationships, measures, column descriptions) |

### `drishti.bi.live` — streaming BI (phase 5)

Live tables kept in memory by the engine and the streams that tell browsers they changed ([BI_GUIDE.md](../guides/BI_GUIDE.md#71-live-tables-phase-5), [BI_GUIDE.md](../guides/BI_GUIDE.md#13-live-visuals), [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 23](../guides/BI_GUIDE.md#23-boards-report-pages-of-live-tiles),
[BI_GUIDE.md](../guides/BI_GUIDE.md) section 18). A table starts on first use and closes when nobody has used it for `idle-close`. The console's own settings are `bi.live.min_interval_ms`, `bi.live.stale_seconds`, `bi.board.rotate_seconds` (Python runs on the server's compute pool: [BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python) section 9).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: a `mode: live` table is the phase 2 snapshot of the connector's current state (`drishti.bi.query.live-ttl`); nothing is kept in memory or streamed. |
| `max-rows` | `50000` | Most rows one live table holds, whatever the dataset's own `live: { rows }` asks; the rows changed longest ago are evicted past it. |
| `batch` | `100ms` | How often the changes that arrived are applied to a table (changes of one entity inside the interval are merged; one transaction with the retention). |
| `frame` | `250ms` | The shortest time between two `tick` events to one browser. The latest state is sent: a slow browser skips ticks, never queues them. |
| `status` | `5s` | How often a stream says how its tables are doing when nothing changed (also its heartbeat). |
| `follow-max` | `2000` | Most entities one live table follows; past it the table stops adding and says so in its state. |
| `resync` | `30s` | How often the kind is listed again: new entities are read and followed, a connector that cannot push is read again. |
| `idle-close` | `10m` | A live table nobody has queried or streamed for this long is closed and its subscriptions are given back. |
| `stale-after` | `30s` | A feed that pushes but has delivered nothing for this long is shown as **stale**. |
| `max-streams` | `100` | Live report streams open at once on the server (`429 DRS-9051`). |
| `max-streams-per-user` | `10` | Live report streams open at once for one person (`429 DRS-9051`). A browser holds one per distinct report on its open reports and boards. |
| `incremental-aggregates` | `true` | The rollups a dataset declares over a live table are kept by group as rows change (sums and counts by difference; a lost minimum or maximum is read again for its group alone), and a query a rollup can answer reads them ([BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 7.1](../guides/BI_GUIDE.md#71-live-tables-phase-5)). `false`: every measure of a live table is computed over its rows on each query. |

<a id="drishtibimq--the-model-layer-and-the-model-query-language-22-wave-w1"></a>
### `drishti.bi.mq` — the model layer and the model query language

Data models, pack-generated datasets and the model query language (`mq: 1`) of [BI_DESIGN.md](../architecture/BI_DESIGN.md#15-the-model-layer-the-specification): the bounds of one query and of
standing queries (`POST /api/v1/bi/mq`, `/mq/subscribe`, [API_GUIDE.md](../guides/API_GUIDE.md#models-and-the-model-query-language-apiv1bi)). A standing query's
default throttle is `drishti.bi.live.frame`; its stream's heartbeat is `drishti.bi.live.status`.

| Key | Default | Meaning |
|---|---|---|
| `max-days` | `400` | The longest date window one model query may read (`time.window`), in days; longer is `422 DRS-9157`. |
| `grouped-limit` | `500` | The rows a grouped query answers when it gives no `limit`. |
| `rows-limit` | `10000` | The rows a `shape: rows` extract answers when it gives no `limit`. |
| `max-limit` | `100000` | The most rows any model query may ask for (a larger `limit` is cut to it; the engine's own `drishti.bi.query.max-rows` still applies). |
| `max-subscriptions` | `500` | Standing queries open at once on the server (`429 DRS-9181`). |
| `max-subscriptions-per-user` | `20` | Standing queries open at once for one person (`429 DRS-9182`). |
| `min-throttle` | `100ms` | The shortest throttle a subscription may ask for (a shorter one is raised to it). |
| `idle-close` | `60s` | A subscription that no stream has read for this long is dropped (a tab closed without ending its standing queries frees them within a minute; the console also ends them on `pagehide`). |
| `idle-close` | `10m` | A subscription that no stream has read for this long is dropped. |
| `pack-live` | `true` | A pack-generated dataset (`pack.<pack>.<kind>`) is `live` when the kind's connector pushes (decision D6), else `snapshot`; `false` keeps every one a snapshot. |
| `maintenance-budget` | `250ms` | Per model, the time keeping its standing queries current may take in each second; past it the model's groups are recomputed every `tick` and say `tick` (status and the `drishti_bi_sq_mode` metric) until a second spends less than half of it. |
| `tick` | `5s` | How often a standing query in tick mode is recomputed: windows, time intelligence and Python measures, and every query of a model over its maintenance budget. |

Standing queries ([BI_DESIGN.md §4.6](../architecture/BI_DESIGN.md#46-standing-queries-and-incremental-maintenance)): subscriptions are grouped by plan hash and security
signature (one evaluation per group, `subscribers` in the subscribe answer); a group is kept current by difference (`sum`, `count`, `avg` of the live table's
own columns, the reader's rules worked out per changed row), by asking the touched groups again (`min`, `max`, `distinct`, rules the engine works out), by
asking the whole question again (contexts, fields of other tables, loads and refreshes), or on the tick. A group no subscriber reads stays 30 s.

### `drishti.bi.exec` — execution, connectors and scale

How the query engine shares the server ([BI_DESIGN.md §6.3 to §6.7](../architecture/BI_DESIGN.md#63-concurrency-fairness-and-memory)): work classes and
slots, per-person and per-model shares, memory and cost budgets, per-connector fragment pools and circuit breakers, and the Java nodes of a site that runs
more than one ([OPERATIONS.md, Scale](OPERATIONS.md#bi-scale)).

| Key | Default | Meaning |
|---|---|---|
| `per-model-share` | `0.5` | The share of the server's slots (`drishti.bi.query.max-concurrent`) one model may hold at once: a hot model cannot take the server. Queries wait in **work classes**: **interactive** (a person's question) before **standing** (standing-query maintenance) before **background**; a person's share is `drishti.bi.query.max-concurrent-per-user` (standing and background work do not count against it). A query waits `drishti.bi.query.admission`, then `429 DRS-9025` with `Retry-After`. A query that needs more memory than the engine's `drishti.bi.query.memory-limit`, even spilling, is `413 DRS-9028`. |
| `queue` | `2s` | How long a source fragment waits for its connector's pool (`max-fragments`) before `429 DRS-9025`. |
| `interactive` | `30s` | Longest an interactive query runs before it is cancelled (`504 DRS-9024`; a shorter `options.budget.millis` or `drishti.bi.query.timeout` wins). |
| `background` | `300s` | Longest background work runs (refresh, precompute, standing-query maintenance). |
| `max-estimated-rows` | `200000000` | The planner's estimate of rows a query reads (from the layers' row counts) past which it is refused before it runs (`413 DRS-9029`, with the estimate and the fix: a date filter, a rollup's fields); a query's `options.budget.rows` may set its own. |
| `max-fragments` | `8` | Source fragments (layer builds, direct queries) one connector runs at once; `connectors.<name>.max-fragments` overrides it per connector. Past it a fragment waits `queue`, then `DRS-9025`. |
| `breaker-failures` | `5` | Consecutive failures of a connector that open its circuit breaker: while open every fragment for it is refused at once (`502 DRS-9026`, the connector named). |
| `breaker-open` | `30s` | How long an open breaker refuses; then one fragment may try (half open): its success closes the breaker. |
| `connectors.<name>.max-fragments` | (`max-fragments`) | Per connector (its manifest name): the fragments it runs at once. |
| `node` (`DRISHTI_BI_NODE`) | empty | This Java node's name when several Java nodes serve the site; empty: one node. |
| `nodes.<name>` | (none) | Every Java node of the site, name to base URL (`nodes.java-1: http://10.0.0.5:18480`). Each model's shard (its live tables and standing queries) is owned by one node, chosen by consistent hashing over the names; another node forwards subscriptions to the owner (`503 DRS-9184` while the owner cannot be reached) and takes over a lost node's shards. *Implementation:* the directory, forwarding and failover are proven with two nodes in one process; the HTTP transport between Java nodes is not built yet, so run one Java node. |
| `background-share` | `0.25` | (BI runs) The most of the slots **background** work (BI runs, dataset refresh) holds at once: with 8 slots, 2. Refresh enters admission as background work. A waiting background run is never refused for busy; it waits its turn. |
| `standing-share` | `0.25` | The most of the slots standing-query maintenance holds at once. With both shares at 0.25, at least half the slots are always free for interactive questions ([BI_DESIGN.md › BI runs: every BI query as a bounded, visible job, section 10](../architecture/BI_DESIGN.md#10-fairness-and-admission)). |
| `fragments-share` | `0.5` | The most of a connector's `max-fragments` the layer builds of background work hold at once, so an interactive build of one date always finds a place. |

### BI runs: `config/runs/runs.yaml`

Every BI question can run as a bounded, visible job ([BI_DESIGN.md](../architecture/BI_DESIGN.md#17-bi-runs-every-bi-query-as-a-bounded-visible-job), API in [API_GUIDE.md](../guides/API_GUIDE.md#bi-runs-apiv1biruns)).
Its settings have their own file; `application.yaml` holds only the pointer `drishti.bi.runs.file` (`DRISHTI_BI_RUNS_FILE`, default
`./config/runs/runs.yaml`). Every key has its default, so a missing file or key means the default. Ready-made variants are in
`config/runs.examples/` (`laptop.yaml`, `cluster.yaml`, `off.yaml`). Read at start; a change needs a restart.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: `Prefer: respond-async` is ignored and every question answers synchronously; `POST /api/v1/bi/runs` answers `DRS-9006`. |
| `inline.wait` | `2s` | How long a request waits before its run becomes a visible job. |
| `inline.max-wait` | `10s` | The most a caller may ask for (`Prefer: wait=`). |
| `interactive.max-seconds`, `max-rows`, `max-build-dates` | `10`, `50000000`, `2` | The bounds of the interactive tier (the planner, section 5). |
| `confirm.above-seconds`, `above-rows`, `unknown`, `ttl` | `60`, `500000000`, `true`, `15m` | When an ad-hoc heavy run asks for confirmation first (`409 DRS-9082`); `unknown: true` also asks when the planner cannot size it (a connector without row statistics, nothing built yet). An unconfirmed run is cancelled after `ttl`. |
| `estimate.low-factor`, `high-factor`, `rates.*` | `0.5`, `2.0`, seeds | The estimate's range and the seed rates the planner starts from; finished runs replace them with measured ones (in memory, learnt again after a start). |
| `queue.per-user` | `5` | A person's background runs queued or running (`429 DRS-9081`). |
| `queue.max` | `200` | All queued runs on this node (`DRS-9081`). |
| `workers` | `2` | Background runs executing at once on this node (fair share: the next is the oldest of the person with the fewest running). |
| `run.max-duration` | `4h` | A background run's own time budget (the engine's `drishti.bi.query.timeout` and `drishti.bi.exec.background` still apply). |
| `engine.*` | `2GB`, `2`, `20GB`, `5000000000`, `0` | The run engine: `memory-limit`, `threads`, `max-spill`, `max-read-rows` (a background run's row budget), `prefer-external-above-seconds`. |
| `store.dir` | `${drishti.data.dir}/bi-runs` | Where results are kept (`results/<xx>/<key>/answer.json`), under the data folder. On AWS a shared EFS folder at the same path on every node. |
| `store.max-bytes`, `max-bytes-per-user` | `20GB`, `2GB` | The budget of unpromoted results (the sweeper removes the least recently opened first). Over `max-bytes-per-user`, that person's least recently opened results go first and nobody else's (each result's `owner.txt` names whose it is). |
| `store.max-result-rows`, `max-result-bytes` | `50000000`, `2GB` | One result's limits (`413 DRS-9086`). |
| `store.min-seconds`, `keep-interactive` | `1`, `true` | An interactive answer that took at least this long is stored for reuse by the same rights. |
| `retention.days`, `max-days`, `sweep` | `30`, `365`, `1h` | A result is kept this long after its last open; *Keep longer* allows up to `max-days`; the sweeper runs at start and this often. |
| `restart.resume-running`, `max-attempts`, `requeue-changed-model` | `true`, `2`, `false` | After a restart a queued or running run starts again as its runner, or fails `DRS-9085` with a reason. |
| `lease.renew`, `lease.ttl` | `10s`, `45s` | The running node's lease on a run (a cluster). |
| `cancel.grace` | `5s` | Admin flags a cancelled run whose resources are not released by then. |
| `workspace-visibility` | `owner-and-shared` | `members`: workspace members see others' run rows, never results. |
| `promote.enabled`, `versions-keep` | `true`, `5` | Promotion to a snapshot dataset. |
| `notify.on` | `[done, failed]` | Which ends of a visible run put a `bi-run` notice on the bell. |
| `suggest.min-runs`, `window`, `min-seconds` | `5`, `7d`, `60` | Repeated heavy questions become rollup suggestions: a model, grain and filters' shape asked as an ad-hoc heavy run at least `min-runs` times in `window`, with a median of at least `min-seconds`, is listed in Admin → BI → Runs with its rollup drafted. Accepting adds it to the model's draft; the decisions are kept in `<store.dir>/suggestions.json`. |

### `drishti.bi.plan` — the logical plan, the result cache and the guards

Every question becomes one logical plan ([BI_DESIGN.md §3.6 and §4](../architecture/BI_DESIGN.md#4-query-architecture)); answers are cached by plan hash,
security signature and data generation (the L1 cache of §6.1: readers with identical effective security share an entry).

| Key | Default | Meaning |
|---|---|---|
| `cache-entries` | `2000` | Answers the result cache keeps; `0` turns it off (the engine's own cache, `drishti.bi.query.cache-*`, still applies). |
| `cache-ttl` | `15m` | How long an answer for the current state is served before it is asked again, even when no data generation moved (a `direct` table's source can change unseen). An as-of answer has no time to live. |
| `cache-idle` | `1h` | An answer nobody asked for this long is dropped. |
| `guards` | `refuse` | `refuse`: a sum across currencies (`DRS-9158`) and a balance added across dates (`DRS-9159`) are refused; `warn`: answered with a warning. |

### `drishti.bi.sql` — Drishti SQL

Read-only SQL over models ([API_GUIDE.md › Drishti SQL](../guides/API_GUIDE.md#drishti-sql-apiv1bisql)): `POST /api/v1/bi/sql`, SQL view datasets, models' SQL tests.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: `/bi/sql` answers `403 DRS-9006`, and SQL view datasets and SQL tests cannot run. |
| `max-leaves` | `8` | Model queries one composite statement may ask (its `SELECT`s over the model's tables); more is `DRS-9179`. |
| `composite-memory` | `256MB` | Memory of the in-memory engine a composite statement's outer query runs in (it reads the leaves' answers and nothing else). |
| `composite-timeout` | `30s` | Longest the outer query of a composite statement may run. |
| `max-rows` | `100000` | Most rows a composite statement answers (`DRS-9022` past it). An MQ-shaped statement keeps the model query's limits. |

### `drishti.bi.expr` — the expression language

The Drishti expression language (DEL v2, [RACHANA_REFERENCE.md › Rachana-EL expressions](../guides/RACHANA_REFERENCE.md#rachana-el-expressions)): how the old expression syntax is treated, and the parse cache.

| Key | Default | Meaning |
|---|---|---|
| `legacy` | `refuse` | The old expression syntax (`[Measure]`, `&&`, `c ? a : b`): `refuse` (the default) refuses it with `DRS-9100`, the DEL v2 text as the fix; `warn` reads it with a `DRS-9100` warning in Check, for a site converting its own items (`drishti.py bi migrate --expressions`). An item with `expressions: 2` refuses it whatever this says. A rule written as a YAML boolean (`where: true`) is DEL's `True`. |
| `parse-cache` | `10000` | Parsed expressions kept in memory (keyed by text); `0` turns the cache off. |

Items **saved before** the old syntax was refused are not left broken: at start the server converts them (`drishti.bi.migrate`, below).

### `drishti.bi.migrate` — migrations of stored BI items

What the server does at start with BI items it **stores** (drafts, proposals and the published text of every workspace item; shipped files in
`drishti.bi.dir` are converted with the CLI instead, [CLI_GUIDE.md](../guides/CLI_GUIDE.md#bi-expr-bi-migrate-bi-sql-the-bi-languages)). Every
migration is **idempotent** (a converted item has nothing left to convert, so the second start changes nothing), **audited** (`bi-expressions-migrated`
per item, `bi-migration-run` per run), keeps **the text before as a saved state** of the item's history (*History*, *Restore*), and **never changes an
item silently**: an item it cannot convert keeps its text and is listed in Admin, BI settings (`migrationProblems`), on the item's page and at
`GET /api/v1/bi/migrate/problems` ([TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md#bi-after-an-upgrade-migrations-of-stored-items)).

| Key | Default | Meaning |
|---|---|---|
| `on-start` | `true` (`DRISHTI_BI_MIGRATE_ON_START`) | Run the migrations once at start, before the server takes requests: expressions in the old syntax become DEL v2 ([RACHANA_REFERENCE.md › Rachana-EL expressions](../guides/RACHANA_REFERENCE.md#rachana-el-expressions)), then `rachana: 1` datasets become datasets and models and their reports are rebound ([BI_DESIGN.md 8.3.1](../architecture/BI_DESIGN.md#831-implementation-task-6)): the dataset item becomes the model item (same id), each table a new dataset item of the same workspace, owner and audience; a role restricted on one table is never given rows it did not see. A published text that changes becomes the item's next published version (the old version stays in its version list); a draft stays a draft, a proposal stays proposed. `false` leaves stored items as they are: a bi-admin runs the same with `drishti.py bi migrate --expressions [--dry-run]`. |
| `actor` | `system` | The name the saved states, the item's governance events and the audit log give the start-up run (a run from the API or CLI names the bi-admin who ran it). |

### `drishti.distribution`: export and subscriptions

Export of a BI report, page, visual or Sutra view as PDF, PNG, Excel or CSV, and subscriptions (email on a schedule), [BI_GUIDE.md](../guides/BI_GUIDE.md#33-distribution-scheduled-reports-export-subscriptions-alerts-and-embedding). Mail goes through the server's
mail settings (`spring.mail.host`, `spring.mail.port`, `drishti.collab.email.from`, `drishti.collab.console-url`). Retries use the shared policy with its own area: `drishti.retry.distribution.*` (see Retries above), falling back to
`drishti.retry.*`.

Under `drishti.distribution.export`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns every export off (`422 DRS-9060`) |
| `max-rows` | `50000` | Most rows of one table in an Excel or CSV export; a table is cut there and its sheet says so |
| `pdf-max-rows` | `500` | Most rows drawn per table in a PDF or PNG |
| `pdf-chart-rows` | `15` | Most rows of the table that follows a chart in a PDF or PNG (the chart shows the shape; the Excel/CSV export holds the rows) |
| `max-bytes` | `26214400` | Largest file an export may produce (`422 DRS-9060` over it) |
| `timeout` | `60s` | Longest an export may take |
| `max-visuals` | `40` | Most visuals one export holds |
| `width` | `1000` | Width of the page layout in pixels (the PDF scales it to the page; at least 600) |
| `landscape` | `true` | PDF pages are landscape A4; `false`: portrait |
| `png-max-height` | `12000` | Tallest PNG in pixels; past it a line says how much is left out |

Under `drishti.distribution.subscriptions`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` stops the scheduler and refuses new subscriptions (existing ones are kept). Run the scheduler on one server of a group |
| `per-user` | `20` | Most subscriptions one person keeps (the preference store allows 50) |
| `max-recipients` | `200` | Most recipients (roles expanded) one run reaches; over it the run fails and says so |
| `tick` | `30s` | How often due subscriptions and retries are looked for |
| `keep-runs` | `30` | Runs kept in a subscription's history |
| `max-attempts` | `4` | Tries for one delivery, with the shared retry policy between them |
| `email-rows` | `25` | Most rows per table in an email whose body holds the report (`html` format) |
| `load-delay` | `5s` | After a data load, wait this long before an `after-load` subscription runs, so the BI layers rebuild |
| `narrative-rows` | `15` | Most rows per table the model is shown when it writes a subscription's narrative summary (`narrative: true`; only in `drishti.bi.ai.values: aggregates`, and only from the recipient's own unmasked columns) |

Under `drishti.distribution.statements` (statement pages: [BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 24](../guides/BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader)):

| Key | Default | Meaning |
|---|---|---|
| `max-pages` | `500` | Most pages one statement may run to; a longer one is refused (`422 DRS-9038`), never cut |
| `max-rows` | `10000` | Most rows of one table band; past it the band is cut and says so |
| `paper` | `A4` | The paper of a statement whose `statement:` names none (`A4`, `A3`, `A5`, `Letter`, `Legal`) |
| `band-queries` | `2` | How many bands of one statement are asked at the same time (1 to 8), each as the reader, within the query limits |

### `drishti.alerts` — the one alert engine

Every alert rule (a visual, a dataset or model measure, a Sutra field, a Rachana-EL rule on one entity), the notifications from board tiles,
and the lifecycle of every alert raised (raised, acknowledged, resolved; snooze; owners), the platform alerts included
([USER_GUIDE.md › Alerts](../guides/USER_GUIDE.md#alerts); [BI_GUIDE.md, section 4](../guides/BI_GUIDE.md#4-alerts-on-numbers)). Every key has a
default; change one in `application.yaml` or with `DRISHTI_ALERTS_*`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` stops the evaluator and refuses new alerts (`422 DRS-9062`); stored alerts are kept. Run it on **one** server of a group |
| `per-user` | `20` | Most alerts one person owns (the preference store allows 50 of a kind) |
| `tick` | `15s` | How often scheduled alerts, "no data" deadlines and the wait after a load are looked at |
| `load-delay` | `5s` | After a data load, wait this long before the alerts that follow loads look, so the BI layers rebuild first |
| `live-poll` | `2s` | How often the live tables behind live alerts are checked for a change |
| `live-debounce` | `10s` | The quiet time a live alert waits after the last change before looking, when the alert does not set its own |
| `min-debounce` | `1s` | The shortest debounce an alert may ask for |
| `live-max-wait` | `2m` | While changes never stop, a live alert still looks at least this often |
| `min-gap` | `5s` | Two looks of one alert for one person closer than this are one (a look by hand ignores it) |
| `cooldown` | `30m` | After an alert fires it will not fire again for the same person within this, unless the alert sets its own (`0` for none) |
| `keep-history` | `50` | Events kept in an alert's history (a board tile keeps 15; the preference document is 64 KB) |
| `boards` | `true` | `false` turns notifications from board tiles off (the tiles still colour) |
| `max-audience` | `300` | Most people one board tile notifies; a board with more readers tells nobody and says so in the history |
| `email` | `true` | `false` stops alert email (a save that asks for it is refused). Mail goes through the server's mail settings, as subscriptions do (`spring.mail.*`, `drishti.collab.email.from`, `drishti.collab.console-url`) |
| `bridges` | `true` | `false` stops chat bridge posts for alerts (the routes in `drishti.collab.bridges.webhooks` must also list the event `alert`) |
| `max-attempts` | `4` | Tries in all for one alert delivery (email, webhook, text), with the shared retry policy `drishti.retry.alerts.*` between them; a refused address, a refused host or a 4xx answer is not retried. `1` turns alert retries off |
| `webhooks` | `true` | `false` stops webhook posts for alerts whatever an alert asks for (a save that asks for one is refused); the webhooks themselves are `drishti.notify.webhooks` |
| `sms` | `true` | `false` stops text messages for alerts (a save that asks for one is refused); the gateway is `drishti.notify.sms` |

### `drishti.notify` — webhooks and text messages for alerts and subscriptions

Where an alert or a subscription may send besides the inbox, mail and the chat bridges ([BI_GUIDE.md › Distribution: scheduled reports, export, subscriptions, alerts and embedding, section 6](../guides/BI_GUIDE.md#6-webhooks-and-text-messages)). Nothing here is on until an administrator defines it: a person can pick only the **names** of the webhooks defined here, never type a URL, and a text goes only to a number its owner gave. Neither carries a figure
(a webhook post names the alert or subscription and where it opens; a text holds its title and a link, and only with `sms.include-values: true` the alert's one-line message). A `${ENV}` reference in a secret or a header is resolved from the process environment when the message is sent; a variable that is not set is a permanent failure that names the variable, never the value.

| Key | Default | Meaning |
|---|---|---|
| `webhook.allowed-hosts` | empty | The only hosts a webhook may point at: exact names, or `*.corp.example` for any name under a suffix, separated by commas. Empty: no webhook is sent. Checked at every send, so changing a webhook's URL to another host stops it |
| `webhook.allow-http` | `false` | `true` lets a webhook use `http://` (testing only); otherwise `https://` is required |
| `webhook.timeout` | `10s` | One post |
| `webhooks.<name>.url` | none | The URL of the webhook called `<name>` (the name an alert or subscription picks) |
| `webhooks.<name>.secret` | none | `${ENV}` reference to the signing secret. Every post carries `X-Drishti-Timestamp` (epoch seconds) and `X-Drishti-Signature: sha256=<hex>`, the HMAC-SHA256 of `<timestamp>.<body>` keyed with the secret, so the receiver can check who sent it and refuse a replay. Without a secret the post is unsigned |
| `webhooks.<name>.header.<Header>` | none | A fixed header (`${ENV}` allowed), for example an API key |
| `webhooks.<name>.tls.*`, `.proxy.*` | the site defaults | The same TLS (`tls.ca-file`, `tls.truststore`, `tls.cert-file`, ... [TLS.md](../connectors/TLS.md)) and proxy (`proxy.url`, `proxy.username`, `proxy.password`, `proxy.no-proxy`, `drishti.http.proxy.*`) keys as a connector |
| `sms.url` | none | The HTTP gateway's URL; `{to}`, `{text}` and `{sender}` are replaced by the number, the text and `sms.sender`, URL-encoded. No vendor is built in: any HTTP SMS gateway fits |
| `sms.method` | `POST` | `POST` or `GET` |
| `sms.body` | `{"to":"{to}","message":"{text}"}` | The POST body template (the same placeholders, escaped for the content type: JSON-escaped for `application/json`, form-encoded for `application/x-www-form-urlencoded`) |
| `sms.content-type` | `application/json` | The body's content type |
| `sms.header.<Header>` | none | A header, for example `sms.header.Authorization: Bearer ${SMS_TOKEN}` |
| `sms.sender` | blank | The sender id for `{sender}` |
| `sms.max-chars` | `160` | A text is cut to this many characters (20 to 1000) with an ellipsis; line breaks become spaces |
| `sms.include-values` | `false` | `true` lets an alert's text carry its one-line message (with the person's own figure). A masked figure is never judged or sent whatever this says |
| `sms.timeout` | `10s` | One call |
| `sms.tls.*`, `sms.proxy.*` | the site defaults | As for a webhook |

```yaml
drishti:
  notify:
    webhook:
      allowed-hosts: hooks.corp.example, "*.ops.example"
    webhooks:
      ops-room:
        url: https://hooks.corp.example/drishti
        secret: ${OPS_HOOK_SECRET}
        header: { X-Team: risk }
    sms:
      url: https://sms.example/v1/send
      body: '{"to":"{to}","from":"{sender}","body":"{text}"}'
      sender: Drishti
      header: { Authorization: "Bearer ${SMS_TOKEN}" }
```

### `drishti.bi.governance` — publishing and governance (phase 4)

Saved states, review, certification, impact analysis and bundles ([BI_GUIDE.md](../guides/BI_GUIDE.md#32-publishing-and-governance-versions-review-certification-lineage-impact-environments)).

| Key | Default | Meaning |
|---|---|---|
| `revisions-keep` | `200` | Saved states (revisions) kept per item; the oldest go first, but a saved state that is a published version is never dropped. Every change of the body is one. |
| `preview-rows` | `20` | Rows of data a reviewer sees in the preview of a dataset or a report under review (at most 500). The reviewer's own row rules and masks apply. |
| `refuse-breaking` | `true` | Refuse (`409 DRS-9040`) publishing a dataset change that removes or renames a field, measure or hierarchy a **published** report uses. A `bi-admin` can publish with `force`. Off: the publish goes ahead and answers with warnings. |
| `revoke-certification` | `true` | Take certification away automatically from a certified dataset that changed incompatibly, and from certified reports broken by such a change; the owner and whoever certified are told. |
| `refuse-failing-tests` | `true` | Refuse (`409 DRS-9043`) publishing or approving a report or dataset whose expected values (`tests:`) fail or cannot run; a `bi-admin` can publish with `force`. Off: Check still shows the results, a publish goes ahead ([BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 17](../guides/BI_GUIDE.md#17-expected-values-tests)). |
| `bundle-max-bytes` | `8388608` | Largest BI bundle an import reads (`422 DRS-9041` above it). |
| `imports-dir` | blank: `<drishti.data.dir>/bi/imports` | Where each import keeps what it replaced, one JSON file per import, so *Roll back this import* can put it back ([BI_GUIDE.md](../guides/BI_GUIDE.md#rolling-back-an-import)). Back it up with the identity database. |
| `imports-keep` | `20` | Imports whose replaced states are kept (the oldest go first). |
| `pack-workspace` | `packs` | The shared workspace that holds the BI content of deployed packs (`config/bi` inside a pack archive); made at the first deploy that carries some ([BI_GUIDE.md](../guides/BI_GUIDE.md#bi-content-in-a-pack-archive)). |
| `tell-people` | `true` | Tell people in the inbox (the bell) when a review is asked for (reviewers), decided (the author) or a certification is revoked (the owner and whoever certified). Off: no inbox rows; the audit log and the item's history still record everything. |

### `drishti.bi.poc` — removed

The Rupaka phase 0 proof of concept and its settings were removed in 2.2; a `drishti.bi.poc` block left in a site's configuration is ignored.

### `drishti.layouts` — personal layouts

Layout mode ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)): each user's arrangement of
a Sutra's panels, kept per user and Sutra in the preference store (namespace `layouts`, at most 50 per user) and
applied by the console when it draws that user's views. Who may is a role power (`roles.<role>.layout`, above).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_LAYOUTS_ENABLED`) | Layout mode at all. Off: nobody may keep a layout, `/api/v1/me/layouts/**` refuses with `403 DRS-5002`, and `GET /api/v1/me/layouts` says `enabled: false`. The console has its own switch, `layouts.enabled`, read from the same variable. |

Promoting a layout to a Sutra also needs `drishti.rachana.studio-save: true` and a role with `author`; it is reviewed
as any Studio save (`drishti.governance`).

### `drishti.pivot` — the Pivot tab

The Pivot tab ([USER_GUIDE.md](../guides/USER_GUIDE.md#pivot-slice-a-table-your-way)) appears only where a
Sutra (`pivot:` on a table or ladder) or a pack (`pivot:` beside a kind's `columns:`) opts in; these keys bound it.
There is no role power: anyone who may open a kind may pivot and save; promoting a pivot to a Sutra needs `author`,
`drishti.rachana.studio-save` and goes through review. Saved pivots live in the preference store (namespace `pivots`).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_PIVOT_ENABLED`) | Off: no Pivot tab anywhere (the view's table data and search results carry no `pivot`), and `/api/v1/views/…/records`, `/api/v1/search/pivot/**` and `/api/v1/me/pivots/**` refuse. |
| `max-records` | `50000` | Rows of one panel sent to the browser to pivot, whatever the table's `limit`; beyond it the pivot says *the first 50000 of N rows*. |
| `max-row-keys` | `2000` | Innermost row groups a pivot shows; further groups are left out of the grid but counted in the totals (`moreRows`). |
| `max-column-keys` | `200` | Column groups a pivot shows (`moreColumns`). |
| `document-scan` | `20000` | Entities a search pivot reads as documents, when a field is not kept as a column and the user asks for a document read; more makes the result partial. |
| `drill-page` | `200` | Underlying entities one drill-down page of a search pivot returns, at most. |
| `budget` | `20s` | How long a search pivot waits for a business day's columns (the first read of a day loads them) or for its documents. |

### `drishti.panels` — chart and aggregate panel limits

Not in the bundled file; the defaults apply until you set them. They bound how much of one document the
`histogram`, `pivot`, `scatter`, `candlestick`, `waterfall`, `graph` and `timeline` panels read, so a very long list
cannot make a view slow or a page heavy. A panel that stops short says so (*N more*, *N left out*). See
[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#how-a-panel-behaves).

| Key | Default | Meaning |
|---|---|---|
| `max-values` | `100000` | Numbers a histogram bins, and rows a pivot aggregates. |
| `max-points` | `5000` | Points a scatter draws, bars a waterfall draws, and bars a candlestick keeps (the latest). |
| `max-nodes` | `300` | Nodes a graph draws; edges are capped at twice this. |
| `max-events` | `500` | Events a timeline lists (the latest). |
| `pivot-rows` | `200` | Row keys a pivot shows; the rest are counted under the table. |
| `pivot-columns` | `40` | Column keys a pivot shows; values under further keys still count in the row totals. |
| `tree-depth` | `12` | Levels a table or ladder with `children` descends; deeper rows are left out. |
| `tree-rows` | `10000` | Rows in all (every level) one such table builds; further rows are left out. |
| `map-entities` | `1000` | Entities of a kind a `map` panel reads to know which countries have a page (`link`) and to shade them (`shade`). |
| `map-cache-seconds` | `60` | How long those entities are kept for the next map (the shapes themselves are static files). |
| `map-budget-millis` | `5000` | The time a map gives its entities to arrive; a slower read leaves it unshaded, with the reason under it. |

### `drishti.loads` — data loads (a batch landed)

The signal your ETL sends when a batch has landed, its history, and the late-data check; see [DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed). What a
pack expects and who is told is the pack's `loads:` section and the administrator's override file (`<dir>/config/<pack>.yaml`), not a server setting.

| Key | Default | Meaning |
|---|---|---|
| `dir` | `./data/loads` (`DRISHTI_LOADS_DIR`) | Where the load history (`<pack>.json`) and the expectation overrides (`config/<pack>.yaml`) are kept. |
| `keep` | `2000` | Loads kept per pack. The oldest go first. |
| `scheduler` | `true` | `false` turns the late-data check off; the API and the Data loads page still work. |
| `check-interval` | `60s` | How often expectations are checked for late data. |
| `verify-limit` | `1000` | Most entities counted when a load is verified (a count at the limit reads "or more"). |
| `verify-timeout` | `20s` | How long the verify, alerts and smoke steps may each wait for the data. |
| `lookback-days` | `3` | Business days before today that are still checked for missing data. |
| `default-notify-roles` | `[admin]` | Roles told about a load or late data when the pack names no roles or users. |
| `smoke-max` | `20` | Most sample entities a pack may ask to be opened after a ready load. |
| `refresh-window` | `5s` | Signals within this time of a connector's last refresh are merged: the first refreshes the connector at once, the others join one more refresh at the end of the window (each still looks for the connector's newest version, so its checks see the new data). At most one refresh runs and one waits per connector, however many signals arrive (a 60-day backfill sends 60). `0s` refreshes on every signal. |
| `upload-max-mb` | `2048` | **Load data** from the browser (Admin → Packs → a pack → Load data, `POST /api/v1/admin/packs/{pack}/data`): the largest file. The file is streamed, one document in memory at a time, so this bounds disk and time, not memory; the console streams it on without holding it, under its own cap `builder.pack_load_max_mb`. |

The token scope that opens `POST /api/v1/packs/{pack}/loads` to a personal API token is `loads:write` (`drishti.security.token-scopes`); the
user still needs a role that opens the kind. The expectation settings (`/api/v1/admin/loads/**`) are never open to a token.

### `drishti.approvals` — the one Approvals queue

Every four-eyes item in one inbox ([USER_GUIDE.md › Approvals](../guides/USER_GUIDE.md#approvals)): how near a due date pins an item on
top, how long after its period a report run is due, and the size of the question threads. The keys are in the generated table below.

### `drishti.access-log` — who looked at what

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_ACCESS_LOG`) | Record every answered view, raw document, history read, search and CSV export in `drishti_access` (Admin → Access). |
| `keep-days` | `90` | Older events are pruned once a day. |
| `queue` | `100000` | Events waiting to be written. Recording never slows a read: events are written in batches every second, and when the queue is full they are dropped and counted (Admin → Access says how many). |

### `drishti.collab` — share with a note, and the inbox

Design: [COLLABORATION.md](../architecture/COLLABORATION.md). A person writes a note on a view, picks people and roles from the
directory, and Drishti notifies them in the app (always) and by email (off until configured); the link reopens the view
pinned to the same business date and "known at" instant, with the **recipient's** rights. Shares, recipients and inbox rows are in
the identity database (`drishti_share`, `drishti_share_recipient`, `drishti_inbox`, `drishti_outbox`, the thread, comment, revision, mention
and follow tables, `drishti_collab_hold`; back them up with the rest of the identity database, [OPERATIONS.md](OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export)). Who may share is the role power `roles.<role>.collaborate`; who may read any share is `compliance` (see
[`drishti.security`](#drishtisecurity--tokens-roles-field-masks)).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_COLLAB_ENABLED`) | Everything below. Off: every collaboration endpoint answers `403 DRS-7004`, and `GET /api/v1/collab` says `enabled: false`. |
| `store` | `jpa` | Where shares and inbox rows are kept: `jpa` (the identity database, SQLite or PostgreSQL) or `file` (`dir`, one server only). The server refuses to start with `file` while `drishti.identity.database-url` is PostgreSQL. |
| `dir` | `./data/collab` (`DRISHTI_COLLAB_DIR`) | The file store's folder, and exports. |
| `console-url` | empty (`DRISHTI_CONSOLE_URL`) | The console's address; a share's `link` is this plus `/share/sh_…` (relative when empty). Email stays off while it is empty. |
| `directory.scope` | `shared-packs` | `shared-packs`: the picker lists only users who share at least one assigned pack with the caller (and roles by those people); `all`: every enabled user, for small firms. |
| `directory.min-query` | `2` | Fewest characters a directory search needs. |
| `directory.limit` | `10` | Most entries one search returns. |
| `mentionable-roles` | `["*"]` | Roles usable as groups (`*` for all); `admin` and `service` never are. |
| `max-group-size` | `200` | Largest role a share may expand to. |
| `share.max-recipients` | `25` | Names (people and roles) in one share. |
| `share.max-expanded` | `200` | People once roles are expanded. |
| `share.max-text` | `2000` | Longest note, in characters. |
| `share.undeliverable` | `tell` | `tell`: the sender learns who was not notified and why (they learn a colleague lacks a right, never data); `silent`: they do not. |
| `share.post-to-thread` | `false` | The default for also posting the note to the discussion (a thread with the note as its first comment, by the sender); `GET /api/v1/collab` reports it. The console's share dialog has a tick *Also start a discussion on the view*, ticked on opening when this key is `true`. The server applies the key when a share request does not say: a request that sets `postToThread` (true or false) always wins. |
| `share.max-view-state` | `4000` | Longest view state a share carries, in characters: the control values and column filters the sender had on screen (`ctl.legs=2&f.cashflows.rate=gt%3A1m`). The recipient opens the view with them, the picture and the email draw with them. A longer one is refused (`DRS-5001`, 400); a share sent without any is unchanged. |
| `threads.max-text`, `threads.max-per-entity`, `threads.edit-window`, `threads.page-size` | `4000`, `500`, `15m`, `50` | Comment thread limits: longest comment (characters), threads per entity, how long after writing the author may edit (after it, retract only; every revision is kept), and comments per page. |
| `text.on-masked-copy` | `warn` | A masked field's value typed into a note: `warn` tells the sender, `reject` refuses it (`DRS-7011`), `allow` says nothing. Whatever is chosen, the value reads `•••` for every reader without `raw` except its author, who reads their own text as written. **A sender without `raw` is never warned or refused** (a signal would let them test guesses of a hidden value): each such hit is written to the audit log as `collab.masked-copy`. Only the document's own masked values are found, whole, after normalising (case, spacing, zero-width characters, NFKC, common Greek and Cyrillic look-alikes); a part of a value, or a look-alike outside that table, is not found; use `text.deny-patterns` for values known from elsewhere. |
| `text.deny-patterns` | `[]` | Regular expressions refused in notes and comments (for example a medical record number format). |
| `limits.shares-per-minute` | `5` | Shares one user sends a minute (`429 DRS-7003`, with `Retry-After`). |
| `limits.shares-per-day` | `100` | Shares one user sends in 24 hours (counted in the store, so it survives restarts and holds across servers). |
| `limits.comments-per-minute` | `10` | Comments (and share replies) one user writes a minute (`429 DRS-7003`). |
| `limits.directory-per-minute` | `60` | Directory searches one user makes a minute. |
| `limits.mails-per-recipient-per-hour` | `30` | Emails queued for one recipient in an hour; more are not queued (the bell still rings) and a warning is logged. |
| `inbox.keep` | `1000` | Newest inbox rows kept per user. |
| `inbox.keep-days` | `180` | Days an inbox row is kept; older rows are removed every `retention.interval` (and at start). |
| `inbox.poll` | `5s` | How often a server looks for rows another server wrote to the shared database, only while a stream is open, so a notice reaches a browser on any server within seconds. |
| `inbox.coalesce` | `60s` | Several notices to one person about one thread within this long become one email. Applies to thread notices (mentions and replies); a share is always one email. |
| `email.enabled` | `false` | The email channel; also needs `spring.mail.host`, `console-url` and `drishti.security.enabled` (with sign-in off the server refuses to start with this on: identities are not real). Asking for email while it is off is `503 DRS-7012`. See [Email](#email-springmail-and-the-outbox). |
| `email.content` | `comment` | `comment`: the product, the sender, the kind and id, the panel, the date and the note as that recipient may read it: copies of masked values `•••`, and `{$.path}` quotes filled with the value from the recipient's own view (`•••` where their role masks the field). So an email can carry figures; a pack that must not sends `link-only`. `title`: the same without the note. `link-only`: the sender's name and the link only, no value. Per pack: `packs.<pack>.email.content`. |
| `email.from` | `drishti@localhost` (`DRISHTI_MAIL_FROM`) | The From address. |
| `email.coalesce-window` | `10s` | Notices for one recipient inside this window are sent as one **digest** email (template `digest.*`), each notice still checked against the person's opt-outs, rights and masking when it is sent. `0` turns it off: every notice is its own email, sent at once. A lone notice goes out after this short delay (a first-send delay); only notices that arrive for the same person meanwhile join it, so one notice is never held for minutes. |
| `email.templates-dir` | empty | A folder with `share.subject`, `share.txt`, `share.html` (and `mention.*`, `reply.*`, `digest.*`, `test.*`) that replace the built-in ones, file by file. Variables: `${product}`, `${headline}`, `${detail}`, `${link}`; nothing else, and a note is never read as a template. |
| `outbox.enabled` | `true` | This server sends the email outbox; turn it off on servers of a group that should not (the rows are still written, any dispatching server sends them). |
| `outbox.tick`, `outbox.batch`, `outbox.max-attempts`, `outbox.backoff`, `outbox.max-backoff`, `outbox.lease`, `outbox.keep-sent-days` | `2s`, `50`, `8`, `30s`, `1h`, `60s`, `30` | The dispatcher: how often it looks, rows per tick, attempts before a row is dead, the retry delay (doubling to the maximum), how long a claimed row is held, and days a sent row is kept. |
| `retention.keep-days` | `0` | Days shares and threads are kept (a thread by its last activity, a share by its creation); `0` keeps them forever, so no record is destroyed by default. A legal hold always wins. |
| `retention.kinds` | `{}` | Days per entity kind, by kind name in configuration (`{ trade: 2555, gene: 0 }`); beats the pack's days and the default; `0` keeps that kind forever. |
| `packs.<pack>.retention-days` | unset | Days for every kind the pack owns (a number); beats the default. Order, most specific first: `retention.kinds.<kind>`, `packs.<pack>.retention-days`, `retention.keep-days`. |
| `retention.interval` | `24h` | How often the purge runs. It starts only when some retention above 0 is configured, so a default install never runs it. See [OPERATIONS.md](OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export). |
| `export-keep` | `24h` | How long a finished compliance export is kept before it is deleted (it is also deleted when downloaded: once). Files are written under `<dir>/exports`. |
| `packs` | `{}` | Per-pack overrides by pack name, in configuration: `packs.genomics.share-enabled: false` switches sharing off for that pack's kinds (`403 DRS-7004`); `packs.genomics.email.content: link-only` makes email about that pack's kinds carry neither the id nor the note. |
| `bridges.enabled` | `false` | Chat bridges (build step 9): post "comment + link" (or, for an alert, the alert and its condition) to Teams, Slack or a signed webhook for the events a route lists. Posts go through the outbox (retries, backoff and dead letters as for email) and never carry a data value. See [OPERATIONS.md](OPERATIONS.md#9a-4-chat-bridges-teams-slack-webhooks). |
| `bridges.render-as` | `viewer` | The role whose view of masked values the posted note follows: the note is written as a person with only that role would read it, so with the bundled `viewer` (no `raw`) a value copied from a masked field reads `•••`. Value quotes (`{$.mtm}`) are filled from that role's view of the document (a masked field stays `•••`), so posts can carry figures; an administrator may name a raw role for a restricted channel, and a `link-only` pack posts only the link. |
| `bridges.allow` | `[]` | URL prefixes a bridge's URL must start with (written out in full, no wildcards: `[https://hooks.slack.com/services/, https://contoso.webhook.office.com/]`). Empty means no bridge can post: configuration alone cannot send data to a host nobody approved. |
| `bridges.per-minute` | `30` | Posts per bridge per minute; the rest wait in the outbox and go a minute later (no attempt is counted). |
| `bridges.timeout` | `10s` | Connect and response time for one post. Redirects are never followed. |
| `bridges.max-note` | `500` | Characters of the note or comment a post carries (cut with an ellipsis). |
| `bridges.webhooks` | `[]` | The bridges, a list of `{name, format, url-env, secret-env, routes}`: `name` (letters, digits, `-`, `_`; it is the outbox recipient), `format` (`json`: signed generic webhook; `teams`: incoming webhook or Workflows; `slack`: incoming webhook), `url-env` (the **environment variable** holding the URL: Teams and Slack URLs are secrets, so the URL is never in configuration), `secret-env` (the variable holding the HMAC key, `json` only), and `routes`: a list of `{packs, kinds, events}` where `events` are `share`, `comment`, `mention` and `alert` (an alert on a number or a board tile fired: the post names the alert and holds no number; `kinds` match a Sutra kind for a field alert and `bi` for every other alert) and an empty `packs` or `kinds` means any. A typo in a name, format or event stops the server at start. Example: `[{name: risk-desk, format: teams, url-env: BRIDGE_RISK_DESK_URL, routes: [{packs: [market-risk], events: [share, comment]}]}]`. |
| `snapshots.enabled` | `false` | Watermarked snapshots (build step 10): a share may include a PNG of the view, drawn on the server (Java2D, headless, the JDK's own fonts; no browser, nothing downloaded). Off by default. A pack overrides it: `packs.genomics.snapshots.enabled: false` forbids pictures of that pack's kinds (`403 DRS-7015`); `true` allows them for a pack when the global setting is off. The picture is made for the **most restrictive recipient** (any recipient without `raw` masks it for all; a panel any recipient may not open is left out) and rides the share's page and, under `email.content: comment`, its email as an attachment. |
| `snapshots.width` | `960` | Pixels wide (480 to 2400). |
| `snapshots.max-height` | `1800` | Most pixels tall (200 to 8000); panels that do not fit are replaced by one line saying how many were left out. |
| `snapshots.max-panels`, `snapshots.max-rows` | `8`, `12` | Most panels drawn, and most rows of a table, list or timeline. |
| `snapshots.max-bytes` | `1500000` | Largest PNG; a larger one refuses the share (`503 DRS-7016`). |
| `snapshots.timeout` | `5s` | Longest one drawing may take (`503 DRS-7016`). Two pictures are drawn at a time. |
| `snapshots.cache-size`, `snapshots.cache-ttl` | `50`, `10m` | Views and pictures kept, by view, generation and rights profile (and the share for the picture). |
| `snapshots.recipients` | `count` | What the watermark says of the people: `count` ("3 recipients") or `names` (up to five display names). |

#### Email (`spring.mail`) and the outbox

Email is off until three things are set: `drishti.collab.email.enabled: true`, an SMTP host, and `drishti.collab.console-url` (the links). The
SMTP client is Spring Boot's: the standard `spring.mail.*` keys (or `SPRING_MAIL_HOST` and so on in the environment); no mail client exists while
`spring.mail.host` is unset.

| Key | Default | Meaning |
|---|---|---|
| `spring.mail.host` | unset | The SMTP server. Setting it switches the client on. |
| `spring.mail.port` | `25` (Spring's) | Usually `587`. |
| `spring.mail.username`, `spring.mail.password` | unset | Sign-in to the server, when it needs one; keep the password in the environment, not the file. |
| `spring.mail.properties.mail.smtp.starttls.required` | `false` (Jakarta Mail's) | `true` refuses to send unless the connection is upgraded to TLS. Set it on for any server reached over a network. |
| `spring.mail.properties.mail.smtp.connectiontimeout`, `...smtp.timeout` | none (Jakarta Mail's: wait forever) | Milliseconds to connect and to wait for a reply. Set both (`10000`); otherwise a stalled server holds the dispatcher. |
| `management.health.mail.enabled` | `false` (set by Drishti) | The mail health check is off, so an unreachable mail server never turns `/readyz` red; the outbox retries instead. |

Each queued email is a row in `drishti_outbox` (or `outbox/{pending,sent,dead}/` for the file store) naming the recipient, the template and the share;
the message itself is built when it is sent, for the recipient's rights then. Metrics: `drishti.collab.mail{outcome=sent|retry|dead|cancelled}`
(counters) and `drishti.collab.outbox{state=pending|dead}` (gauges). Operating it: [OPERATIONS.md](OPERATIONS.md#9a-2-collaboration-email-and-the-outbox).
A person chooses which events are emailed under `notify.email.{share,mention,reply}` in `PATCH /api/v1/me/settings` (all default to on).

### `drishti.reports` — scheduled reports

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_REPORTS_ENABLED`) | The scheduler. With several servers on one identity database, turn it on for one only, or a report runs once per server. Reports can always be run by hand. |
| `folder` | `./data/reports` (`DRISHTI_REPORTS_DIR`) | Folder deliveries: `<folder>/<user>/<report>/<report>-<business date>-<HHmm>.csv`, written atomically. |
| `webhooks` | `[]` | URL prefixes a report may post to (`https://hooks.bank.example/drishti/`). Empty: no webhooks. A report cannot post anywhere else, so it cannot be used to reach internal addresses. |
| `per-user` | `20` | Reports a person may keep. |
| `tick` | `30s` | How often the scheduler looks for due reports. |
| `keep-runs` | `20` | Runs remembered per report (time, rows, where it went, or the error). |

### `drishti.admin` — uninstalling packs, connectors and BI files safely

Admin → Artifacts ([OPERATIONALISING.md section 18](../guides/OPERATIONALISING.md#18-admin--artifacts-install-and-uninstall-safely)) moves what it uninstalls into an archive instead of deleting it. All values are read at start.

| Key | Default | Meaning |
|---|---|---|
| `uninstalled-dir` | `./data/uninstalled` (`DRISHTI_UNINSTALLED_DIR`) | The archive: `<timestamp>-<kind>-<name>/` per uninstall, with `payload/` (the folder or file as it was) and `uninstalled.json` (who, when, the impact, where it came from). Keep it on the same disk as `config/` and `data/` so an uninstall is one atomic move; on another disk it is copied, then removed. |
| `uninstalled-keep` | `30d` (`DRISHTI_UNINSTALLED_KEEP`) | Archives older than this are deleted for good by the daily pruning (an audit event `artifact-pruned` names them). `0` keeps them for ever. |
| `uninstalled-prune-interval` | `1d` | How often the pruning runs (and once a minute after start). `0` switches it off; Delete permanently in the console still works. |
| `hidden-bi-file` | `./data/admin/bi-hidden.txt` | The shipped BI files an administrator disabled: catalogue ids, one per line. A disabled file stays in `config/bi`; the catalogue does not show it. |
| `bi-upload-max-bytes` | `1048576` | The largest BI file Admin → Artifacts accepts. |

### `drishti.live` — live updates

| Key | Default | Meaning |
|---|---|---|
| `frame` | `50ms` | Ticks arriving within one frame are merged; the latest wins. |
| `heartbeat` | `15s` | Interval of the SSE keep-alive comment, so proxies do not close idle streams. Keep it below your proxy's idle timeout. |
| `max-streams` | `20000` | Live streams (views and monitors) the server keeps open at once; further requests are refused. |
| `window` | `30s` | Rolling window for the live latency p99 shown in the top bar. |

See [DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#1-live-updates).

### `drishti.graph` — links between entities

| Key | Default | Meaning |
|---|---|---|
| `id-patterns[]` (`pattern`, `kind`) | from packs | Identifier regex to kind, tried in order, for values whose kind is not stated. A site list replaces the packs' list. |
| `fields.<field>` (`kind`, `label`) | from packs | A document field that references another entity. |
| `badges.<kind>` | from packs | Rachana-EL over the linked document, shown beside the link (`EE 4.1m`). |
| `link-budget` | `40ms` | Linked entities slower than this show as *pending* and fill in later. With Drishti*net, a linked entity that only a peer holds waits routing.yaml's `link-budget` (`1s`) instead. |
| `impact.follow` | from packs | Reference fields followed upward to find what dependents roll into (impact analysis). |
| `impact.measures.<kind>` | from packs | Rachana-EL summed per group (`$.mtm`). |
| `impact.formats.<kind>` | from packs | The format for that measure. |

Example:

```yaml
drishti:
  graph:
    link-budget: 80ms
    fields:
      carrierId: { kind: carrier, label: Carrier }
    badges:
      carrier: "$.rating"
```

### `drishti.inference` — views for kinds without a Sutra

| Key | Default | Meaning |
|---|---|---|
| `semantics-file` | none | A site file that replaces the bundled semantic hints (`inference/semantics.yaml`). Packs' `config/semantics.yaml` files are tried first. See [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra). |

---

## Connector settings, plugin by plugin

A connector's settings go in its **connector file**, `config/connectors/<name>.yaml`, under `settings:`
([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)); each plugin's generated README lists every setting it reads. A plugin running
under its own name (the demo plugin) takes them under `drishti.sources.plugins.<plugin>.settings`. Named connectors under
`drishti.sources.connectors.<name>.settings` in `application.yaml` still start but are **deprecated**: the server logs a warning naming the
file to write, and they override a file setting by setting until moved
([CONNECTOR_FILES.md section 11](../connectors/CONNECTOR_FILES.md#11-moving-off-applicationyaml-and-the-old-data-source-override)). The
tables below use the same keys either way. Every plugin takes `source-name`, the name shown
in provenance and health (default: the connector's name, or the plugin's name). Writing a plugin of your own is
described in [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

Every plugin also takes **`stale-after`**: a duration (`15m`, `2h`, `4d`). When the connector has received nothing new
for longer than that, it is *stale*: Admin → Health shows it in amber and turns the overall status `DEGRADED`, and
views of its entities show a banner saying the data may be out of date. Without it a connector is never stale. It
only means something for sources that know when they last got data (Kafka, ActiveMQ, RabbitMQ, Delta, file, feeds,
demo). The packs set it for the trading stream (`DRISHTI_STREAM_STALE_AFTER`, default `15m`) and the daily public
feeds (`4d`, which covers a weekend and a holiday).

```yaml
# config/connectors/trading-stream.yaml
plugin: kafka
settings:
  stale-after: 5m        # trades arrive all day: five quiet minutes means something is wrong
```

### The Delta and Iceberg disk caches

Both lake connectors (`delta`, `iceberg`) keep an id-map folder and a document cache on local disk per connector. Nested
(`id-map-disk: { max-gb: 20 }`) and flat (`id-map-disk.max-gb: 20`) forms are the same setting. Sizing, the safeguards and how to
read Admin → Caches: [OPERATIONS.md section 12.3](OPERATIONS.md#123-sizing-caches-and-disk-per-connector).

| Setting | Default | Meaning |
|---|---|---|
| `id-map-disk.enabled` | `true` | Each table date's id map written once per table version and memory-mapped: no heap per id, kept across restarts. |
| `id-map-disk.dir` | `<data dir>/cache/<connector>/idmaps` | Its folder (a local disk, one server's). |
| `id-map-disk.max-gb` | `5` | The folder's cap: the least recently used maps beyond it are unmapped, then deleted; never a map in use (mapped, read or written in the last five minutes, a date open in VaR or exposure). A map alone over the cap is kept and warned about once per date and version. About 75 MB per million ids per table and date. |
| `id-map-disk.idle-release` | `30m` | A map not read for that long is unmapped (a release, not a delete: the file stays and the next read maps it again); never while a read is inside it, nor for a date open in VaR or exposure. The cap can then trim its file. At least `5m`, refused at start below that. |
| `id-map-disk.ttl` | off (empty or `0`) | Drops a map not used (mapped or read) for that long, ±10% per file. At least `24h`: a lower value fails the connector's start, naming the setting and the minimum. Never the latest and previous dates, today's, a date open in VaR or exposure, a map being read. |
| `doc-disk-cache.enabled` | `true` | Documents already read kept on disk (RocksDB, one per connector), keyed by the table version (Delta) or the day's files (Iceberg). |
| `doc-disk-cache.dir` | `<data dir>/cache/<connector>/docs` | Its folder. |
| `doc-disk-cache.max-gb` | `1` | Its cap; the oldest files go first. |
| `doc-disk-cache.ttl` | `12h` | Entries written longer ago go (compacted hourly). |
| `doc-disk-cache.reset-at` | `02:00` | The daily clearing. |
| `doc-disk-cache.zone` | `America/New_York` | The zone of `reset-at`. |
| `refresh-seconds` | `10` | How often the connector checks each table's latest version: without a data-load signal, the longest a read may answer the previous version after a commit. |

### Where each plugin's settings are

Each plugin's settings, with type, default and meaning, are listed in one place: the plugin's README, generated from the settings catalogue
(`drishti-engine/src/main/resources/drishti/plugin-settings.yaml`, the one Admin → Connectors builds its form from). The connector's own
document explains how it reads, its layout, scale and limits.

| Plugin | Reads | Settings | Reference |
|---|---|---|---|
| <a id="delta--delta-lake"></a>`delta` | Delta Lake tables, a table per kind, on disk or S3 | [README, Settings](../../plugins/drishti-plugin-delta/README.md#settings) | [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) (layout and row groups: [section 3](../connectors/DELTA_CONNECTOR.md#3-what-is-on-disk)) |
| <a id="jdbc--a-database"></a>`jdbc` | a database: table mode (Drishti's table) or query mode (your SQL); the driver jar on the class path or in `plugin-dir` | [README, Settings](../../plugins/drishti-plugin-jdbc/README.md#settings) | [POSTGRES_CONNECTOR.md](../connectors/POSTGRES_CONNECTOR.md), [JDBC_QUERIES.md](../connectors/JDBC_QUERIES.md) |
| <a id="file--json-or-csv-files-on-disk"></a>`file` | JSON Lines in dated folders, `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl` | [README, Settings](../../plugins/drishti-plugin-file/README.md#settings) | [FILE_CONNECTOR.md](../connectors/FILE_CONNECTOR.md) |
| <a id="rest--an-httpjson-service"></a>`rest` | one entity per GET from an HTTP/JSON service | [README, Settings](../../plugins/drishti-plugin-rest/README.md#settings) | [REST_CONNECTOR.md](../connectors/REST_CONNECTOR.md) |
| <a id="tls--tls-for-connectors"></a>`tls.*` | TLS for every connector that connects over the network (shared; each `…-password` has a `…-password-file` twin) | [TLS.md](../connectors/TLS.md) | [TLS.md, connector matrix](../connectors/TLS.md#connector-matrix) |
| <a id="kafka--a-live-stream"></a>`kafka` | a live stream; a null value or `"doc": null` deletes | [README, Settings](../../plugins/drishti-plugin-kafka/README.md#settings) | [KAFKA_CONNECTOR.md](../connectors/KAFKA_CONNECTOR.md) (security: [section 11](../connectors/KAFKA_CONNECTOR.md#11-security-tls-sasl-and-confluent)) |
| <a id="activemq-and-rabbitmq--message-queues"></a>`activemq`, `rabbitmq` | message queues; the latest document of every entity in a local RocksDB state store | [README, Settings](../../plugins/drishti-plugin-activemq/README.md#settings), [README, Settings](../../plugins/drishti-plugin-rabbitmq/README.md#settings) | [ACTIVEMQ_CONNECTOR.md](../connectors/ACTIVEMQ_CONNECTOR.md), [RABBITMQ_CONNECTOR.md](../connectors/RABBITMQ_CONNECTOR.md) |
| <a id="s3--documents-in-s3-or-an-s3-compatible-store"></a>`s3` | a JSON object per entity in S3 or an S3-compatible store | [README, Settings](../../plugins/drishti-plugin-s3/README.md#settings) | [S3_CONNECTOR.md](../connectors/S3_CONNECTOR.md) |
| <a id="iceberg--apache-iceberg"></a>`iceberg` | Iceberg tables in Drishti's layout, path-based or a REST catalog | [README, Settings](../../plugins/drishti-plugin-iceberg/README.md#settings) | [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md) (engines: [section 17](../connectors/ICEBERG_CONNECTOR.md#17-engines-native-and-library)) |
| <a id="iceberg-external--iceberg-tables-drishti-does-not-write"></a>`iceberg-external` | Iceberg tables other engines write, read-only | [README, Settings](../../plugins/drishti-plugin-iceberg-external/README.md#settings) | [ICEBERG_EXTERNAL_CONNECTOR.md](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md) |
| <a id="duckdb--one-embedded-duckdb-file"></a>`duckdb` | every data domain in one DuckDB file; retention is the loader's `--keep-days` | [README, Settings](../../plugins/drishti-plugin-duckdb/README.md#settings) | [DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md) |
| <a id="mongodb"></a>`mongodb` | a document per entity per business day, a collection per domain | [README, Settings](../../plugins/drishti-plugin-mongodb/README.md#settings) | [MONGODB_CONNECTOR.md](../connectors/MONGODB_CONNECTOR.md) |
| <a id="redis"></a>`redis` | today and recent days in memory, with live updates | [README, Settings](../../plugins/drishti-plugin-redis/README.md#settings) | [REDIS_CONNECTOR.md](../connectors/REDIS_CONNECTOR.md) |
| <a id="aerospike"></a>`aerospike` | three sets per domain; retention is the records' time to live, set by the loader | [README, Settings](../../plugins/drishti-plugin-aerospike/README.md#settings) | [AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md) |
| <a id="cassandra"></a>`cassandra` | Cassandra, ScyllaDB, Astra, Keyspaces; retention is the loader's (`--ttl-days`, `--keep-days`); `proxy.*` refused | [README, Settings](../../plugins/drishti-plugin-cassandra/README.md#settings) | [CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md) |
| <a id="feed--public-data-feeds"></a>`feed` | public data feeds, one connector per feed (off by default) | [README, Settings](../../plugins/drishti-plugin-feeds/README.md#settings) | [FEEDS_CONNECTOR.md](../connectors/FEEDS_CONNECTOR.md) |
| <a id="news-and-yfinance--connectors-that-compute-data-on-demand"></a>`news`, `yfinance` | computed on demand, nothing stored; off as shipped (names leave the network); pages reading them through `source:` need `drishti.graph.link-budget` raised above 40 ms | [README, Settings](../../plugins/drishti-plugin-news/README.md#settings), [README, Settings](../../plugins/drishti-plugin-yfinance/README.md#settings) | [NEWS.md](../connectors/NEWS.md), [YFINANCE.md](../connectors/YFINANCE.md) |
| <a id="derived--kinds-computed-from-other-kinds"></a>`derived` | kinds computed from other kinds (built in) | [types/derived/README.md](../connectors/types/derived/README.md) | [PACK_DEVELOPER_GUIDE.md Part II](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds) |
| <a id="demo--sample-data"></a>`demo` | the packs' samples; the shipped `application.yaml` sets only `drishti.sources.plugins.demo.enabled` (`DRISHTI_DEMO_ENABLED`) | [README, Settings](../../plugins/drishti-plugin-demo/README.md#settings) | [DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md) |

---

## Environment variables used by the packs and profiles

Packs declare their connectors in `pack.yaml` with placeholders, so you switch them with environment variables:

| Variable | Default | Pack | Effect |
|---|---|---|---|
| `DRISHTI_DELTA_ROOT` | `./data/delta` | every shipped pack | Root of every pack's Delta Lake connector. |
| `DRISHTI_DELTA_ENGINE` | `native` | every Delta connector without its own `engine` | `native`, `hadoop` or `auto` (see [`delta`](#delta--delta-lake)). |
| `DRISHTI_LAKE_ENABLED` | `true` | every shipped pack | Switch every pack's data connector off (the demo samples still answer). Leave it `true` with the `postgres`, `aerospike` or `duckdb` profile: those profiles change the plugin of the same connectors, and this switch would turn them off too. |
| `DRISHTI_ALLOW_INSECURE_TLS` | unset | every connector with `tls.*` | Must be `true` for a connector to run with `tls.insecure-trust-all: true` (development only). |
| `DRISHTI_STREAM_TRADING` | `false` | trading | Turn on the `trading-stream` Kafka connector. |
| `DRISHTI_KAFKA_BOOTSTRAP` | `localhost:9092` | trading, market-risk | Its brokers (and the PV topic's). |
| `DRISHTI_TRADING_TOPIC` | `drishti.trading.trades` | trading | Its topic. |
| `DRISHTI_STREAM_DISK_CACHE` | `true` | trading | Its disk cache on or off. |
| `DRISHTI_CACHE_ROOT` | `./data/cache` | trading | Disk cache root (one sub-directory per connector). |
| `DRISHTI_STREAM_CACHE_GB` | `10` | trading | Disk cache size. |
| `DRISHTI_CACHE_RESET_AT` | `02:00` | trading | Nightly clearing time (New York). |
| `DRISHTI_VAR_PV_STREAM` | `false` | market-risk | turn on the `pv-updates` connector (plugin `var-pv-stream`): the pricing systems' PV topic, windowed into intraday batches of the risk lake ([VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)). |
| `DRISHTI_VAR_PV_TOPIC` | `drishti.risk.pv-updates` | market-risk | Its topic: each message's value JSONL text, one or more records of `scenario-pv` or `pnl-vector` (a cancel a cancel line). The window is the server's `drishti.var.intraday.kafka.window`. |
| `DRISHTI_FEED_NYFED_SOFR`, `DRISHTI_FEED_ECB_ESTR`, `DRISHTI_FEED_ECB_FX`, `DRISHTI_FEED_US_TREASURY`, `DRISHTI_FEED_FRED` | `false` | market-data | Turn each public feed on. |
| `FRED_API_KEY`, `DRISHTI_FRED_SERIES` | empty, `DGS10,DFF` | market-data | FRED needs a key. |

Example: the trading pack with its live Kafka stream.

```bash
DRISHTI_PACKS=trading DRISHTI_STREAM_TRADING=true DRISHTI_KAFKA_BOOTSTRAP=kafka:9092 \
  java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

`curl -s localhost:18480/api/v1/sources` should then list `trading-stream`.

### Profiles: another store instead of Delta Lake

`SPRING_PROFILES_ACTIVE=<profile>` (`postgres`, `aerospike`, `duckdb`, `files`, `iceberg`, `mongodb` or `redis`) loads
`application-<profile>.yaml`, which redefines the banking data-domain connectors (`reference-store`,
`market-store`, `trade-endur`, `risk-store`, `credit-store`, `collateral-store`) to read a database instead of
the lake. The packs still decide kinds, routes and modes.

**Deprecated.** The profiles still work and the server logs a warning that names the equivalent connector file; the way forward is one YAML file per connector in `config/connectors/` ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)), with an example for every store in `config/connectors.examples/`.

| Profile | Variables | Defaults |
|---|---|---|
| `postgres` | `DRISHTI_PG_URL`, `DRISHTI_PG_USER`, `DRISHTI_PG_PASSWORD` | `jdbc:postgresql://localhost:5432/drishti`, `drishti`, `drishti` (tables `<domain>.entities`) |
| `aerospike` | `DRISHTI_AEROSPIKE_HOSTS`, `DRISHTI_AEROSPIKE_NAMESPACE` | `localhost:3000`, `test` |
| `files` | `DRISHTI_FILES_ROOT` | `./data/files` (`<root>/<domain>/<date>/<kind>.jsonl`) |
| `iceberg` | `DRISHTI_ICEBERG_ROOT`, `DRISHTI_ICEBERG_CATALOG`, `DRISHTI_ICEBERG_URI`, `DRISHTI_ICEBERG_WAREHOUSE`, `DRISHTI_ICEBERG_CREDENTIAL` | `./data/iceberg`, `hadoop` (path-based tables); for a REST catalog `rest` and its URI, warehouse and credential |
| `mongodb` | `DRISHTI_MONGODB_URI`, `DRISHTI_MONGODB_DATABASE` | `mongodb://localhost:27017`, `drishti` |
| `redis` | `DRISHTI_REDIS_URI`, `DRISHTI_REDIS_CLUSTER` | `redis://localhost:6379`, `false` |
| `duckdb` | `DRISHTI_DUCKDB_PATH`, `DRISHTI_DUCKDB_MEMORY` | `data/duckdb/drishti.duckdb`, `1GB` (one file, a schema per domain, `<domain>.entities`; `memory-limit` of the shared DuckDB instance) |

```bash
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PG_URL=jdbc:postgresql://db:5432/drishti \
  DRISHTI_PG_PASSWORD="$PG_PASSWORD" java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.11.d-exec.jar
```

---

## Spring settings you may need

| Key | Bundled value | Meaning |
|---|---|---|
| `server.port` | `${DRISHTI_PORT:18480}` | HTTP port. |
| `server.shutdown` | `graceful` | Finish requests in flight on stop. |
| `server.compression` | on for JSON and `text/event-stream` | |
| `spring.threads.virtual.enabled` | `true` | Every request runs on a virtual thread. Leave it on. |
| `spring.profiles.active` | none (`SPRING_PROFILES_ACTIVE`) | `postgres`, `aerospike`, `duckdb`, `files`, `iceberg`, `mongodb` or `redis` ([above](#profiles-another-store-instead-of-delta-lake); deprecated, connector files replace them). |
| `spring.config.additional-location` | none (`SPRING_CONFIG_ADDITIONAL_LOCATION`) | Another config file or folder read **in addition to** the bundled one, e.g. `file:/etc/drishti/site.yaml`. See [QUICKSTART](../guides/GETTING_STARTED.md). |
| `spring.config.location` | none | Config files read **instead of** the defaults: the bundled `application.yaml` is then not read unless you list `classpath:/application.yaml` first. |
| `spring.config.import` | the local file and the packs overlay (bundled) | `optional:file:./application.local.yaml` and `optional:file:${DRISHTI_PACKS_OVERLAY:${drishti.data.dir:./data}/packs/added.yaml}`. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Actuator endpoints: `/actuator/health` (with `/liveness` and `/readiness` probes), `/actuator/prometheus` (timer `drishti.view`, gauges `drishti.live.*`). |
| `drishti.security.token-read-posts` | `/api/v1/search/pivot/**`, `/api/v1/command` | The `POST` paths (ant patterns) a personal API token may call because they only read. Every other non-`GET` request stays refused for a token. |
| `drishti.security.token-scopes` | `design:write`, `design:approve`, `packs:admin`, `loads:write` | The write scopes a personal API token may be given: for each, a `description` (shown on the account page) and the `allow` list of `METHOD path` patterns it opens (`*` for any method). Keys with a colon are written `"[design:write]":`. A scope is only a door: the controller still checks the user's roles at the time of each call. A write no scope opens is refused. |
| `drishti.security.token-never` | users, roles, tokens, sign-in, caches, reports, collaboration, `/me/**` | `METHOD path` patterns no token may ever write, whatever its scopes. `TokenScopeGuardTest` fails the build when a non-`GET` endpoint is in none of `token-read-posts`, a scope or this list. |
| `drishti.security.token-write-max-days` | `90` | The longest life, in days, of a token with a write scope (which must expire). Read-only tokens keep 1-366 days or no expiry. |
| `springdoc.api-docs.path` / `springdoc.swagger-ui.path` | `/api/docs` / `/api/docs/ui` | The OpenAPI description and its UI. |
| `logging.level.<package>` | Spring default (`INFO`) | e.g. `--logging.level.com.ash.drishti=DEBUG` |

---

## Console reference

File: `drishti-console/config/application.yaml`. Local override: `drishti-console/config/application.local.yaml`.
Precedence (low to high): the file, the local file, `DRISHTI_CONSOLE__…` variables, `--key=value` arguments.
`${VAR:default}` placeholders in the files are resolved from the environment.

The environment form is `DRISHTI_CONSOLE__` followed by the key with `.` written as `__`, in any case:

```bash
DRISHTI_CONSOLE__SERVER__PORT=17481 python drishti-console/run_drishti_web.py
# same as
python drishti-console/run_drishti_web.py --server.port=17481
```

You should see uvicorn report `Uvicorn running on http://127.0.0.1:17481`.

### `server`, `backend`

| Key | Default | Meaning |
|---|---|---|
| `server.host` | `0.0.0.0` (`DRISHTI_CONSOLE_HOST`) | Listening address: every interface, so the console opens from other machines and phones. Use `127.0.0.1` behind a proxy on the same host, or to keep it to this machine. |
| `server.port` | `17480` (`DRISHTI_CONSOLE_PORT`) | Listening port. |
| `server.workers` | `4` (`DRISHTI_CONSOLE_WORKERS`) | Worker processes on that port (`run_drishti_web.py`). Each keeps short-lived copies of what the server says about a user (settings, packs, view layouts, saved pivots, BI powers, the session check); a change made through one worker writes the user's stamp under the console's data folder (`<console data>/user-stamps`, one small file per user) and every worker reads its copy again at its next page, in every browser of the user. A change to a role (its BI powers in Admin, BI settings; anything in Admin, Roles; a role deleted) writes the stamp for everyone, so every holder's copies are read again at once. Consoles behind one address that share no data folder see each other's changes within `ui.user_cache_seconds` (10 s), sessions within `auth.recheck_seconds`. |
| `server.shutdown_seconds` | `5` (`DRISHTI_CONSOLE_SHUTDOWN_SECONDS`) | On a stop, the longest an open stream keeps a worker; then it is closed and the worker ends. |
| `backend.url` | `http://127.0.0.1:18480` (`DRISHTI_BACKEND_URL`) | The Drishti server. |
| `backend.timeout_seconds` | `15` | Per call to the server. Longer than the server's own read limit (`drishti.sources.fetch-timeout`, 10 s), so the server's answer, including a timed-out read's `DRS-1004`, comes through. |
| `backend.name` | the product name (`ui.product`) | The name of the single server when `servers` is not set; the picker and the top bar show it. |
| `backend.check_seconds` | `5` (`DRISHTI_CONSOLE_BACKEND_CHECK_SECONDS`) | How often the console's background watch asks each server (the default and every one of `servers`) whether it is reachable, with a 2 s timeout. It keeps reachable / since / last error / version, logs each change once, and answers `GET /healthz/backend`, which every page's banner polls. Shown on Admin > Health (Console watch). `0` turns the watch off. |
| `backend.urls` | empty (`DRISHTI_BACKEND_URLS`) | Several servers of one Drishti*net cluster ([The console and several servers](#the-console-and-several-servers-drishtinet)). |
| `backend.discover` | `true` (`DRISHTI_BACKEND_DISCOVER`) | Pull the cluster's member list from the server in use and keep it. |
| `backend.discover_every` | `60s` | How often the member list is pulled again. |
| `backend.check_every` | `5s` | How often each server of the list is checked (`GET /public/about`). |
| `backend.fail_after` | `2` | Failed requests or checks in a row before a server is set aside. |
| `backend.data_dir` | empty (`DRISHTI_CONSOLE_DATA_DIR`) | The console's own folder (`net/servers.json`); empty: `<data dir>/console`. |
| `backend.pool_size` | `64` | Pooled HTTP connections to the server (per server, when there are several). |
| `collab.bridge_test_timeout_seconds` | `30` | How long Admin > Collaboration waits for a bridge **Test** (the server waits for the bridge's own `drishti.collab.bridges.timeout`, default 10 s, so keep this longer). When it runs out the page says `DRS-7013` and names `drishti.collab.bridges.timeout`. |

### `servers` — one console, many servers

Leave it out and the console has one server, `backend.url`. List several and people pick one, then sign in to it
(ADR-016). Each server keeps its own users, roles, packs and data: a session on one gives nothing on another, and
each server trusts only tokens signed with its own secret.

```yaml
servers:
  - id: open                       # lower-case letters, digits, dashes; appears in links (/connect/open, ?srv=open)
    name: Drishti
    url: http://drishti-a:18480
    description: Everyone's server
    color: "#2a9d8f"               # the dot beside the name in the top bar
    token_secret: ${DRISHTI_TOKEN_SECRET_OPEN:}     # that server's drishti.security.secret (default: auth.token_secret)
  - id: rates-desk
    name: Rates desk
    url: https://drishti-rates.internal:18480
    color: "#e76f51"
    token_secret: ${DRISHTI_TOKEN_SECRET_RATES:}
  - id: preprod
    name: Pre-production
    url: http://drishti-pre:18480
    listed: false                  # not in the picker; reachable by the link /connect/preprod
```

| Key | Default | Meaning |
|---|---|---|
| `servers[].id` | required | Its name in links and cookies. Only ids in this list are ever reached: a link cannot point the console at another address. |
| `servers[].name`, `description`, `color` | id, empty, the link colour | What the picker shows. |
| `servers[].url` | required | The server's address. |
| `servers[].token_secret` | `auth.token_secret` | The secret shared with that server (`drishti.security.secret` there). Give each server its own: a leaked secret then opens one server, not all. |
| `servers[].listed` | `true` | `false` hides it from the picker; `/connect/<id>` still reaches it. |

The first server is the default for a browser that has not chosen one. The choice is kept per browser (cookie
`drishti_server`, a year), and each server's session in its own cookie (`drishti_session_<id>`), so you can stay
signed in to several and switch without signing in again. Signing out signs out of the current server only. Single
sign-on settings (`auth.oidc`) apply to every server; each server verifies the ID token itself.

### `ui`

| Key | Default | Meaning |
|---|---|---|
| `default_theme` | `terminal` | `terminal`, `light`, `wallstreet`, `blue`, `green`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson`, `terminal-blue`. Users can choose their own. |
| `product` / `tagline` | `Drishti` / `Any data. Any domain. One grammar.` | Shown in the top bar and sign-in page, and wherever a page or script names the product: its features follow it (`<product> SQL`, `<product> Elements`, `<product>*net`). No template or script writes the name itself (templates use `{{ PRODUCT }}`, scripts read `<body data-product>`); a guard test (`tests/test_branding_guard.py`) fails on a literal. |
| `headline` / `headline_accent` | `See what your data hasn't told you yet.` / `hasn't told you yet.` | The landing page's headline and the part of it drawn in the theme's gradient; the tagline sits under it as the subtitle. Blank `headline`: the tagline is the headline (its accent `One grammar.`). |
| `owner` / `owner_email` | `Ashutosh Sinha` / `ajsinha@gmail.com` | Who holds the rights, and an address beside the name (blank for none): the Author section of the About page. The server has `drishti.branding.owner`. |
| `user`, `user_display`, `desk` | `ash` (`DRISHTI_USER`), `Ash`, `Rates desk` | The acting user when `auth.enabled` is false (local development only). |
| `user_cache_seconds` | `10` | How long a console worker reuses what the server said about a user (settings, packs, view layouts, saved pivots, BI powers) before asking again. A change made on this console is seen at once by all its workers (the user stamps, see `server.workers`); this bounds how long another console behind the same address shows the old choice. |
| `about_prefetch` | `true` (`DRISHTI_ABOUT_PREFETCH`) | Fetch the page's *About this page* answer once the view is idle, so table headers, strip and key-value labels that have a glossary entry are underlined (with a hint) before the drawer is opened. It costs the server one explain per page view: the view is derived again for the caller and kept `drishti.explain.cache-ttl`, so opening the drawer afterwards is free. Set `false` on a busy installation; the labels are then underlined when the drawer is first opened ([PERFORMANCE.md](PERFORMANCE.md#about-this-page-and-its-cost)). |
| `designer` | `false` (`DRISHTI_UI_DESIGNER`) | The Sutra designer ([RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31](../guides/RACHANA_REFERENCE.md#31-the-sutra-designer-customize-describe-outline-and-grid)): a **Customize** button on every entity page (move, hide and add panels, choose a table's columns, say what a click shows; kept as the user's own Design, *My page · &lt;kind&gt;*, which then draws that page for that user only) **Describe** (Build, New screen: a screen from one command-style line, read by a deterministic parser), **Outline** (the screen as a list to rearrange) and **Grid** (the screen as a spreadsheet of formulas); each person's first mode is `designerMode` in `/me/settings`. Every mode edits the same Sutra YAML; the pack's Sutra is never changed. Off: no button, `/designer/*` answers 404 (`/designer/new` goes to Build, New screen) and pages are drawn by the server. |
| `clock_tz` / `clock_label` | `America/New_York` / `NY` | The top-bar clock. |
| `landing_examples` | `4` | How many example commands the landing page plays. They are the example commands (`console.examples`) of the packs switched on, so the landing page never names an entity of a pack that is not installed; with none, it shows the form `<MNEMONIC> <ID> <GO>`. Studio's first preview is the first of these examples too. |
| `product_native` / `product_meaning` | `दृष्टि` / `Drishti (दृष्टि) means <em>sight</em>.` | The product name in its own script (blank for none) and one line on its meaning, on the About page. |
| `copyright` / `notice` | the Drishti copyright and a ban on unauthorised copying | The legal lines in the footer and on the About page. Change them here, never in code; the server has the same under `drishti.branding`. |
| `error_advice` | a short text for each of `DRS-1001`, `DRS-1002`, `DRS-1003`, `DRS-1004`, `DRS-1016`, `DRS-1007`, `DRS-4003`, `DRS-4004`, and `default` | What an error page tells the user to do, by the code the server answered with. `{kind}` and `{id}` in a text become the entity asked for; a code with no entry gets `default`. Edit it so the advice fits your site (who to ask, which channel). |
| `showcase` | four screenshots of the retired finance example pack | The landing page's pictures: `{slug, title, cmd, sutra}` each, the image being `web/static/img/shot-<slug>.png`. A caption names its `cmd` only when that command is one of the packs' examples. |
| `facts.enabled` | `${DRISHTI_HOME_FACTS:true}` | Home's **Did you know** tile (the fact of the day, signed in): the facts each pack declares in `config/facts.yaml`, computed as the user on the business date ([USER_GUIDE.md](../guides/USER_GUIDE.md#home-the-fact-of-the-day), [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#facts-for-home)). `false`: no tile. |
| `facts.cache_seconds` | `21600` | How long the facts of one user, business date and set of packs are kept before they are computed again. |
| `facts.timeout_seconds` | `90` | A fact that takes longer is left out of the tile. |
| `home_cards` | Market risk (`VAR BANK`), Exposure (`EXP BANK`), Funds and ETFs (`FUND SPY`), Data map (`DATAMAP`) | The quick cards under Home's headline, signed in: `{title, cmd, pack, icon}` each; a card naming a `pack` is shown only while that pack is on for the user. |

### `embed` — embedded views

Off by default: with `enabled: false` nothing under `/embed/` exists. Host applications and tokens are the **server's** (`drishti.embed.*`, [API_GUIDE.md](../guides/API_GUIDE.md#embedding-views-in-another-web-application)); the console serves the embed routes, passes the embed token to the server on every call, and learns the registered origins for CORS from the server.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_EMBED_ENABLED`) | Serve `/embed/v1/` (view, channel, resolve, the element's script and sheet). |
| `origins_ttl_seconds` | `60` | How long the console keeps the origins its server's host applications registered (the CORS allow-list). A new or changed application is known within this time. |

### `auth`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_AUTH_ENABLED`) | Require sign-in. |
| `session_secret` | empty (`DRISHTI_SESSION_SECRET`) | Signs the session cookie. At least 32 characters when auth is on, or the console will not start. |
| `token_secret` | empty (`DRISHTI_TOKEN_SECRET`) | Signs the short-lived tokens sent to the server; must equal the server's `drishti.security.secret`. |
| `token_ttl_seconds` | `300` | Lifetime of each server token. |
| `session_hours` | `10` | Session lifetime (the cookie's and the server session's). |
| `recheck_seconds` | `10` (`DRISHTI_SESSION_RECHECK_SECONDS`) | How long the console reuses the server's answer to "does this session still stand, and who is the user now?" (enabled, roles, password change due). Disabling, demoting or signing out takes effect within this time, and at once on the console where it was done: every worker process of that console checks the user's stamp before it reuses an answer. `0` asks on every request. |
| `allowed_origins` | `[]` | Origins besides the console's own (the request's `Host`) allowed to send state-changing requests; for a proxy that changes the `Host` header. Requests from any other origin are refused (`403 DRS-5002`). |
| `secure_cookie` | `true` (`DRISHTI_SECURE_COOKIE`) | Cookie sent over HTTPS only. Set `false` only for plain-HTTP testing. |
| `oidc.enabled` | `false` (`DRISHTI_OIDC_ENABLED`) | Show the single sign-on button. |
| `oidc.issuer` | empty (`DRISHTI_OIDC_ISSUER`) | Same issuer as the server. |
| `oidc.client_id` | empty (`DRISHTI_OIDC_CLIENT_ID`) | Same client id as the server. |
| `oidc.client_secret` | empty (`DRISHTI_OIDC_CLIENT_SECRET`) | Empty for a public client (PKCE alone). |
| `oidc.token_auth` | `client_secret_basic` | Or `client_secret_post`. |
| `oidc.scopes` | `openid profile email` | |
| `oidc.label` | `Sign in with single sign-on` | Button text. |
| `oidc.redirect_uri` | `<console>/auth/oidc/callback` (`DRISHTI_OIDC_REDIRECT_URI`) | Register this at the provider. Set it explicitly behind a proxy. |

Note that `DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID` and `DRISHTI_TOKEN_SECRET` are
read by both programs, so one set of variables configures both halves.

### `packs`, `help`

| Key | Default | Meaning |
|---|---|---|
| `packs.dir` | `../config/packs` (`DRISHTI_PACKS_DIR`), relative to `drishti-console/` | Where pack content (examples, guides) is read. |
| `packs.extra_dirs` | empty (`DRISHTI_PACKS_EXTRA_DIRS`) | Further pack folders, comma-separated, read after `packs.dir` (the retired example packs: `../examples/packs`). |
| `packs.installed_dir` | `<data dir>/packs/installed` (`DRISHTI_PACKS_INSTALLED`) | The server's `drishti.packs.installed-dir`: packs installed from the registry or by Deploy archive, read before `packs.dir` (they win a name, as on the server). Point it at the same folder as the server's. |
| `packs.enabled` | the seven shipped packs (`DRISHTI_PACKS`) | Used only when the server cannot be asked which packs are enabled. |
| `help.docs_dir` | `../docs` | The documents rendered in the help centre's reference section. |
| `studio.examples_dir` | `../docs/guides/examples` | The Rachana examples (`<name>.sutra.yaml`, `<name>.json`, `<name>.md`) that the Build workbench (**Build → New screen → Examples**, the File menu) and **Help → Examples** offer. Only names of complete example sets present there are served. |
| `ui.studio_example` | `all-panels-showcase` | The example Studio opens on when no entity or Sutra is asked for. Blank, or a name that is not there: Studio opens on the first example entity of the user's packs. |
| `builder.max_samples` | `50` | Build workbench, **New** (`/build/new`): files in one upload, and samples in all (a `.jsonl` line is one). Over it, the whole upload is refused (`413 DRS-5005`). Keep it at or under the server's `drishti.builder.max-samples`. |
| `builder.max_file_mb` | `5` | Largest single file; a larger one is left out and reported, the rest are shaped. |
| `builder.max_total_mb` | `25` | All files together; over it the upload is refused. |
| `builder.studio_kind` | `sample` | The kind a new Design and the workbench preview a brought document as (a pasted document has no kind of its own). |
| `builder.pack_sample_docs` | `200` | **Build → New pack** (`/build/pack/new`, [Schema to pack](../guides/DATA_GUIDE.md#5-schema-to-pack-generate-a-pack-from-json-schema)): documents the browser keeps per data file and sends to the console. A sample, never the file. |
| `builder.pack_read_max_rows` | `500000` | The most rows per file the page may be told to read (the page streams the file and keeps a sample; memory holds only the sample). |
| `builder.pack_read_max_mb` | `1024` | The most MB per file the page may be told to read before it stops the file and says so. |
| `builder.pack_max_files` | `200` | Schema and data files in one plan. Over it: `413 DRS-5005`. |
| `builder.pack_max_docs` | `5000` | Sampled documents in one plan request. |
| `builder.pack_profile_rows_per_type` | `10000` | **Build → New pack**: rows of each type (each value of the type column, such as `PRODUCT_TYPE`) the page profiles as it streams the file: how often each field has a value, its types, digits written as text, and the size of every nested array at every depth. Every row is still counted, so the list of types and their row counts is exact. A type with fewer rows is profiled entirely. |
| `builder.pack_load_max_mb` | `2048` | **Admin → Packs → a pack → Load data**: the largest data file the console streams on to the server (never held in memory); the server has its own `drishti.loads.upload-max-mb`. |
| `builder.pack_design_samples_per_type` | `50` | Documents of each type the page keeps to design that type's Sutra: spread over the whole file and chosen for variety (optional arrays present and absent, different nested shapes); anonymised by default. |
| `builder.pack_max_schema_kb` | `512` | The largest single schema file. |
| `builder.pack_max_sutras` | `200` | Sutras one generated pack may have (a match column with many values makes many). |
| `builder.pack_jobs_per_user` | `3` | Preview jobs one person may have running at once, and kept after they finish. Over it: `429 DRS-5005`. |
| `builder.pack_job_ttl_min` | `60` | Minutes a finished preview (with its bundle) is kept in memory for the person who started it. |
| `builder.pack_draft_concurrency` | `4` | Sutras drafted at once against the server (`POST /api/v1/builder/design`, one per Sutra). |
| `builder.tools_dir` | `../tools` | The repository's `tools/` folder, relative to `drishti-console/`: the console imports `packbundle.py` from it to make the bundle in the format of `pack bundle`. The console image copies that one file; without it the page plans and previews but cannot build a bundle (`501`). |

`config/workspaces.yaml` holds the console's own starter workspaces (`templates`, none by default; packs add theirs)
and `blank`, what a new workspace starts as (*New workspace* on `/w`): a `layout` (`2col`, `3col`, `2x2`, `1+2`) and one
to four empty `panes`.

Whether a design can save Sutra files is decided by the server (`drishti.rachana.studio-save`).

### `calc`

| Key | Default | Meaning |
|---|---|---|
| `labs.calc` | `false` (`DRISHTI_LABS_CALC`) | Labs: Calc, a scratch pad in the browser that never publishes. Off (as shipped): no view offers Calc and the console's `/api/calc/*` routes refuse, whatever packs and roles say ([USER_GUIDE.md](../guides/USER_GUIDE.md#calc-python-on-any-view)). |

### `layouts`

| Key | Default | Meaning |
|---|---|---|
| `layouts.enabled` | `true` (`DRISHTI_LAYOUTS_ENABLED`) | Off: no view offers layout mode and saved personal layouts are not applied, whatever roles say ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)). |
| `layouts.min_span` | `3` | The narrowest width, in columns of the 12-column grid (1 to 12), that layout mode lets a user give a panel. |
| `form.collapse_on_submit` | `true` | `FORM <pack>`: after a search, fold the form to a one-line summary of its criteria so the results get the space (Alt+F or the summary opens it again). |

### `live`

| Key | Default | Meaning |
|---|---|---|
| `live.max_subscriptions` | `32` (`DRISHTI_LIVE_MAX_SUBSCRIPTIONS`) | Live subscriptions one browser's channel carries at most: views across all its tabs and workspace panes, the alerts bell, monitors (a view open in several tabs counts once). Each holds one stream to the server from `backend.pool_size`, so keep it well below that. One over the limit is answered with `gone`, `DRS-5003`, and the view shows `Static` ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#one-connection-per-browser)). |

The Python runtime is a folder, not a setting: `drishti-console/web/static/vendor/pyodide/`, installed by
`tools/fetch-pyodide.sh` and served at `/pyodide/<version>/` when present
([USER_GUIDE.md](../guides/USER_GUIDE.md#12-installing-the-python-runtime)).

---

## Where the environment variables are

Every environment variable, server (`DRISHTI_*` placeholders, Spring's relaxed names), console (placeholders and
`DRISHTI_CONSOLE__<SECTION>__<KEY>`), packs, profiles and tools, is in the generated
[environment variable index](#environment-variable-index) at the end, with what it sets, its default and its meaning. The Docker
compose files in `deploy/` show these variables in use; see [OPERATIONS.md](OPERATIONS.md).

| Variable | Used by | Sets |
|---|---|---|
| `DRISHTI_PORT` | S | `server.port` |
| `DRISHTI_ALLOW_INSECURE_TLS` | S | allows a connector's `tls.insecure-trust-all` ([`tls.*`](#tls--tls-for-connectors)) |
| `DRISHTI_PACKS` | S, C | `drishti.packs.enabled`, `packs.enabled` |
| `DRISHTI_PACKS_DIR` | S, C | `drishti.packs.dir`, `packs.dir` |
| `DRISHTI_PACKS_EXTRA_DIRS` | S, C | `drishti.packs.extra-dirs`, `packs.extra_dirs` |
| `DRISHTI_PACKS_INSTALLED` | S, C | `drishti.packs.installed-dir`, `packs.installed_dir` |
| `DRISHTI_DEFAULT_PACKS` | S | `drishti.packs.default-for-users` |
| `DRISHTI_PLUGIN_DIR` | S | `drishti.sources.plugin-dir` |
| `DRISHTI_DEMO_ENABLED` | S | `drishti.sources.plugins.demo.enabled` |
| `DRISHTI_OFFLINE` | S | `drishti.offline.enabled` |
| `DRISHTI_FEEDS` | S | `drishti.sources.plugins.file.settings.root` |
| `DRISHTI_REST_ENABLED`, `DRISHTI_REST_URL` | S | the `rest` plugin |
| `DRISHTI_JDBC_ENABLED`, `DRISHTI_JDBC_URL`, `DRISHTI_JDBC_USER`, `DRISHTI_JDBC_PASSWORD` | S | the `jdbc` plugin |
| `DRISHTI_ACTIVEMQ_ENABLED`, `DRISHTI_RABBITMQ_ENABLED`, `DRISHTI_S3_ENABLED` | S | those plugins |
| `DRISHTI_CALENDAR` | S | `drishti.business-date.calendar` |
| `DRISHTI_SUTRAS` | S | `drishti.rachana.dirs` |
| `DRISHTI_STUDIO_SAVE` | S | `drishti.rachana.studio-save` |
| `DRISHTI_UI_KIT_DIR`, `DRISHTI_UI_KIT_SITE_DIR` | S | `drishti.ui-kit.dir`, `drishti.ui-kit.site-dir` |
| `DRISHTI_SUTRA_REVIEW`, `DRISHTI_SUTRA_FOUR_EYES`, `DRISHTI_GOVERNANCE_DIR` | S | `drishti.governance.*` |
| `DRISHTI_SECURITY_ENABLED` | S | `drishti.security.enabled` |
| `DRISHTI_LABS_CALC` | S, C | `drishti.calc.enabled`, `labs.calc` |
| `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | C | `live.max_subscriptions` |
| `DRISHTI_TOKEN_SECRET` | S, C | `drishti.security.secret`, `auth.token_secret` |
| `DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID` | S, C | `drishti.security.oidc.*`, `auth.oidc.*` |
| `DRISHTI_EMBED_ENABLED` | S, C | `drishti.embed.enabled`, `embed.enabled` |
| `DRISHTI_EMBED_SIGN_IN_WINDOW`, `DRISHTI_EMBED_SIGN_IN` | S, C | `drishti.embed.sign-in-window`, `embed.sign_in.enabled` |
| `DRISHTI_PUBLIC_URL`, `DRISHTI_CONSOLE_URL`, `DRISHTI_EMBED_KEY_FILE` | S | `drishti.embed.issuer`, `.audiences`, `.signing-key` |
| `DRISHTI_OIDC_CLIENT_SECRET`, `DRISHTI_OIDC_REDIRECT_URI` | C | `auth.oidc.*` |
| `DRISHTI_SEED_ADMIN` and other identity variables | S | see [USER_MANAGEMENT.md](USER_MANAGEMENT.md) |
| `DRISHTI_DELTA_ROOT`, `DRISHTI_DELTA_ENGINE`, `DRISHTI_LAKE_ENABLED`, `DRISHTI_STREAM_*`, `DRISHTI_KAFKA_BOOTSTRAP`, `DRISHTI_TRADING_TOPIC`, `DRISHTI_VAR_PV_*`, `DRISHTI_CACHE_*`, `DRISHTI_FEED_*`, `FRED_API_KEY`, `DRISHTI_FRED_SERIES` | S (packs) | [pack connectors](#environment-variables-used-by-the-packs-and-profiles) |
| `SPRING_PROFILES_ACTIVE`, `DRISHTI_PG_*`, `DRISHTI_AEROSPIKE_*`, `DRISHTI_DUCKDB_*`, `DRISHTI_FILES_ROOT`, `DRISHTI_ICEBERG_*`, `DRISHTI_MONGODB_*`, `DRISHTI_REDIS_*` | S | [profiles](#profiles-another-store-instead-of-delta-lake) |
| `DRISHTI_CONSOLE_HOST`, `DRISHTI_CONSOLE_PORT`, `DRISHTI_BACKEND_URL`, `DRISHTI_USER` | C | `server.*`, `backend.url`, `ui.user` |
| `DRISHTI_AUTH_ENABLED`, `DRISHTI_SESSION_SECRET`, `DRISHTI_SECURE_COOKIE` | C | `auth.*` |
| `DRISHTI_CONSOLE__<SECTION>__<KEY>` | C | any console key |
| `DRISHTI_<RELAXED_KEY>` (e.g. `DRISHTI_LIVE_HEARTBEAT=20s`) | S | any server key |
<!-- generated: settings begin (tools/settings_catalogue.py writes everything down to the end marker; edit the sources, not this) -->

## Every setting

Every key of every kind: 2,156 settings in 18 areas, and 330 environment variables. Each row has the same shape: the key, its type, its default (*none*: no default, the setting is off or unset until given; *empty*: an empty value), the environment variable that sets it, what it applies to, its meaning, whether a change needs a restart or is picked up live, and the release that added it (blank: before 2.6). Generated by `tools/settings_catalogue.py` from the files themselves, each meaning the file's own comment; `--check` fails the drill when a key has no meaning or the page is out of date. The console shows this page searchable at Help → Configuration (`/help/configuration`), with the values in effect for an administrator.

Every setting has a **tier** (the last column; Admin → Configuration shows them by tier): **everyday** 18, **admin** 1,725, **expert** 413. *Everyday* is what a business lead changes (the lenses, the VaR and exposure confidences, the scenario window, the feature switches); *admin* is what an operator sets; *expert* is performance and internals. The tiers are kept in `tools/settings_schemas.yaml` (`tiers`), one place.

| Area | Settings |
|---|---|
| [The server: `application.yaml`](#the-server-applicationyaml) | 545 |
| [Compute engines: `config/engines/*.yaml`](#compute-engines-configenginesyaml) | 323 |
| [The console: `application.yaml`](#the-console-applicationyaml) | 187 |
| [The console's other files](#the-consoles-other-files) | 106 |
| [Connectors: every plugin's settings](#connectors-every-plugins-settings) | 535 |
| [Packs: `pack.yaml`](#packs-packyaml) | 137 |
| [Editions: `config/editions/`](#editions-configeditions) | 70 |
| [The content store: `config/store/store.yaml`](#the-content-store-configstorestoreyaml) | 14 |
| [BI runs: `config/runs/runs.yaml`](#bi-runs-configrunsrunsyaml) | 48 |
| [Write-back: `config/edit/`](#write-back-configedit) | 18 |
| [Compute workers: `config/compute/compute.yaml`](#compute-workers-configcomputecomputeyaml) | 23 |
| [Drishti*net: `config/net/`](#drishtinet-confignet) | 27 |
| [Platform alert rules: `config/alerts/platform-alerts.yaml`](#platform-alert-rules-configalertsplatform-alertsyaml) | 13 |
| [Report definitions: `config/reports/`](#report-definitions-configreports) | 23 |
| [SIMM parameter files: `drishti.simm.parameters-dir`](#simm-parameter-files-drishtisimmparameters-dir) | 29 |
| [The data inbox's routing rules: `config/dataload.yaml`](#the-data-inboxs-routing-rules-configdataloadyaml) | 2 |
| [The UI kit: `config/ui-kit/brand.yaml`](#the-ui-kit-configui-kitbrandyaml) | 19 |
| [AI providers and MCP servers: `config/ai/`, `config/mcp/`](#ai-providers-and-mcp-servers-configai-configmcp) | 37 |
| [Environment variable index](#environment-variable-index) | 330 |

Sutras' own configuration keys (the server's `drishti.rachana.*`, `drishti.governance.*` and `drishti.ui-kit.*`, the console's `views.refresh_min_seconds`) are rows of the server and console tables below; the Sutra grammar itself is [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md).

### The server: `application.yaml`

`drishti-server/src/main/resources/application.yaml`, inside the jar. Override a key in `application.local.yaml`, with an environment variable (the one named, or Spring's relaxed name) or a `--key=value` argument ([Precedence](#precedence)).

#### `spring`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `spring.application.name` | string | `drishti` |  | server | the application's name in logs and metrics | restart |  | admin |
| `spring.mvc.async.request-timeout` | duration | `30m` | `DRISHTI_ASYNC_TIMEOUT` | server | how long a streamed answer may take: a workbook download (the VaR Playground, the Why it moved working, the capacity workbook), a BI export; an answer cut at this limit arrives incomplete | restart |  | expert |
| `spring.threads.virtual.enabled` | boolean | `true` |  | server | every request runs on a virtual thread; fetches and link fan-out block cheaply | restart |  | expert |
| `spring.ai.model.chat` | string | `none` |  | server | Spring AI's chat auto-configuration: off (none) | restart |  | admin |
| `spring.ai.model.embedding` | string | `none` |  | server | Spring AI's embedding auto-configuration: off | restart |  | admin |
| `spring.ai.model.image` | string | `none` |  | server | Spring AI's image auto-configuration: off | restart |  | admin |
| `spring.ai.model.moderation` | string | `none` |  | server | Spring AI's moderation auto-configuration: off | restart |  | admin |
| `spring.ai.model.audio.speech` | string | `none` |  | server | Spring AI's speech auto-configuration: off | restart |  | admin |
| `spring.ai.model.audio.transcription` | string | `none` |  | server | Spring AI's transcription auto-configuration: off | restart |  | admin |
| `spring.main.banner-mode` | boolean | `off` |  | server | no Spring banner at start | restart |  | admin |
| `spring.config.import` | list | empty |  | server | files read after this one: the engines' files (config/engines/&lt;id&gt;.yaml), application.local.yaml in the working directory, then the packs overlay Admin -&gt; Packs writes (DRISHTI\_PACKS\_OVERLAY, default &lt;data dir&gt;/packs/added.yaml) | restart |  | admin |
| `spring.jpa.open-in-view` | boolean | `false` |  | server | no lazy loading while a view renders: the identity services load what they return | restart |  | admin |

#### `logging`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `logging.file.name` | string | empty |  | server | the log file, from drishti.logging.file (empty: standard output only) | restart |  | admin |
| `logging.logback.rollingpolicy.max-file-size` | size | `100MB` |  | server | one file's size before it rolls | restart |  | admin |
| `logging.logback.rollingpolicy.total-size-cap` | size | `1024MB` |  | server | every rolled file together; the oldest go beyond | restart |  | admin |
| `logging.logback.rollingpolicy.max-history` | integer | `30` |  | server | rolled days kept at most | restart |  | admin |
| `logging.level` | map | `{…}` |  | server | log level per package (logging.level.&lt;package&gt;: DEBUG, INFO, WARN, ERROR); the shipped entries quieten the Delta kernel, Parquet and Hibernate | restart |  | admin |

#### `server`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `server.port` | integer | `18480` | `DRISHTI_PORT` | server | the server's HTTP port | restart |  | admin |
| `server.shutdown` | string | `graceful` |  | server | graceful: requests in flight finish on a stop | restart |  | admin |
| `server.http2.enabled` | boolean | `true` | `DRISHTI_HTTP2` | server | HTTP/2 on the server's port: Drishti\*net peers multiplex their forwarded reads over one connection (h2c by the upgrade on plain HTTP, ALPN on TLS); browsers over TLS get it too. false: HTTP/1.1 only (peers then fall back to it on the same call). | restart |  | admin |
| `server.tomcat.max-swallow-size` | size | `64MB` | `DRISHTI_MAX_SWALLOW` | server | After a 413 for an oversized body, Tomcat drains up to this much of the rest so the client reads the answer, not a reset. | restart |  | admin |
| `server.compression.enabled` | boolean | `true` |  | server | compress JSON answers and event streams | restart |  | admin |
| `server.compression.mime-types` | string | `application/json,text/event-stream` |  | server | the content types compressed | restart |  | admin |

#### `management`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `management.endpoints.web.exposure.include` | string | `health,info,prometheus,metrics` |  | server | the actuator endpoints served: /actuator/health (liveness and readiness probes), info, prometheus (timer drishti.view, gauges drishti.live.\*), metrics | restart |  | admin |
| `management.health.mail.enabled` | boolean | `false` |  | server | a mail server that is down is retried by the outbox; it must not turn the server's health red | restart |  | admin |
| `management.endpoint.health.probes.enabled` | boolean | `true` |  | server | /actuator/health/liveness and /actuator/health/readiness for Kubernetes probes | restart |  | admin |
| `management.endpoint.health.group.readiness.include` | string | `readinessState,nodeReadiness` |  | server | readiness that means ready (CD1, CLUSTER\_DEPLOYMENT.md section 9.1): Spring's own state and this node's (drishti.node.readiness): DOWN while joining, while an engine's newest date opens or an owned shard is not ready; OUT\_OF\_SERVICE while drained. Only here: /actuator/health itself and liveness never wait for it | restart |  | admin |

#### `drishti`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.edition` | string | empty | `DRISHTI_EDITION` | server | The edition (docs/admin/EDITIONS.md): blank is the full Drishti; risk is Drishti Risk. It names config/editions/&lt;name&gt;.yaml, read as a layer under the site's configuration before the packs load; every member of a cluster sets the same value, and the console learns it from /api/v1/about. A change needs a restart. | restart |  | admin |
| `drishti.lenses` | string | `risk,world,market,trading` | `DRISHTI_LENSES` | server | Lenses (EDITIONS.md › The design as built, section 8): editions a person may open on a full instance ("Open Drishti Risk"), for their own session; no restart, every pack stays loaded. A lens hides, it does not secure. On by default (product owner, 2026-10-30): the four lenses; DRISHTI\_LENSES= (empty) turns them off. Ignored with an edition. | live |  | everyday |

#### `drishti.editions`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.editions.dir` | string | `./config/editions` | `DRISHTI_EDITIONS_DIR` | server | the folder of the edition files (and features.yaml, default-packs.yaml) | restart |  | admin |

#### `drishti.data`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.data.dir` | string | `./data` | `DRISHTI_DATA_DIR` | server | The umbrella folder every runtime path below defaults under (identity database, caches, loads, collab, designs, reports, the pack overlay and settings, governance, the sample feeds). Each path's own setting still overrides its default. | restart |  | admin |

#### `drishti.logging`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.logging.file` | string | empty | `DRISHTI_LOG_FILE` | server | empty: standard output only (containers); drishti-stack sets run/logs/&lt;node&gt;.log | restart |  | admin |
| `drishti.logging.max-file-mb` | integer | `100` |  | server | one file's size before it rolls (also daily); rolled files compressed | restart |  | expert |
| `drishti.logging.max-total-mb` | integer | `1024` |  | server | every rolled file together; the oldest go beyond | restart |  | expert |
| `drishti.logging.max-files` | integer | `30` |  | server | rolled days kept at most | restart |  | admin |

#### `drishti.memory`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.memory.cache-share` | number | `0.5` |  | server | the share of -Xmx every cache in the budget may take together, in weighted pools | restart |  | expert |
| `drishti.memory.squeeze-at` | number | `0.85` |  | server | the heap after a collection past this share of -Xmx halves the connectors' and small pools for a minute | restart |  | expert |

#### `drishti.admission`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.admission.enabled` | boolean | `true` | `DRISHTI_ADMISSION_ENABLED` | server | false: nothing waits (Admin -&gt; Health still shows the readings) | restart |  | expert |
| `drishti.admission.memory-warn` | number | `0.80` |  | server | the heap after the last collection past this share of -Xmx: Health says "getting full" | restart |  | expert |
| `drishti.admission.memory-refuse-heavy` | number | `0.85` |  | server | past this, new date loads, downloads, BI runs and bulk loads wait (503 DRS-9502 to DRS-9505, Retry-After) | restart |  | expert |
| `drishti.admission.memory-refuse-sign-in` | number | `0.92` |  | server | past this, new sign-ins wait too (DRS-9501); people signed in keep working | restart |  | expert |
| `drishti.admission.memory-free-min-mb` | integer | `768` |  | server | the memory levels act only while the heap left after the last collection is also under this | restart |  | expert |
| `drishti.admission.queued-loads` | integer | `2` |  | server | date loads that may wait behind the running ones (each engine's load-threads) before a new one waits | restart |  | expert |
| `drishti.admission.open-heap-refuse` | number | `1.2` |  | server | new date loads wait when the open dates' heap passes this share of its safety net (the dates in use are well over it) | restart |  | expert |
| `drishti.admission.request-handlers` | integer | `200` |  | server | requests answered at once before the server counts as full | restart |  | expert |
| `drishti.admission.requests-warn` | number | `0.75` |  | server | share of request-handlers busy: Health says "getting full" | restart |  | expert |
| `drishti.admission.requests-refuse` | number | `0.90` |  | server | past this share, new heavy work waits | restart |  | expert |
| `drishti.admission.queue-refuse` | integer | `50` |  | server | new heavy work waits when more requests than this wait beyond request-handlers | restart |  | expert |
| `drishti.admission.disk-warn-gb` | integer | `20` |  | server | free disk of the data folder under this: Health says "getting full" | restart |  | expert |
| `drishti.admission.disk-refuse-gb` | integer | `10` |  | server | under this, new date loads, downloads and bulk loads wait | restart |  | expert |
| `drishti.admission.retry-after.sign-in` | integer | `45` |  | server | a new sign-in (DRS-9501) | restart |  | expert |
| `drishti.admission.retry-after.load` | integer | `30` |  | server | a new VaR or exposure date (DRS-9502) | restart |  | expert |
| `drishti.admission.retry-after.download` | integer | `30` |  | server | an Export data download (DRS-9503) | restart |  | expert |
| `drishti.admission.retry-after.bi-run` | integer | `60` |  | server | a BI run (DRS-9504) | restart |  | expert |
| `drishti.admission.retry-after.bulk-load` | integer | `120` |  | server | a bulk load of a pack's data (DRS-9505) | restart |  | expert |
| `drishti.admission.retry-after.pivot-view` | integer | `20` |  | server | a large pivot view (DRS-9507) | restart |  | expert |
| `drishti.admission.keep` | integer | `50` |  | server | refusals kept for Admin -&gt; Health (in memory) | restart |  | expert |
| `drishti.admission.override-max-minutes` | integer | `240` |  | server | the longest an operator's override may last (it ends by itself) | restart |  | expert |

#### `drishti.disk`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.disk.min-free-percent` | integer | `10` |  | server | the free-space guard: a build that would leave less than this share of the disk free is refused (DRS-5218)... | restart |  | admin |
| `drishti.disk.min-free-gb` | integer | `20` |  | server | ...or less than this many GB (both must hold) | restart |  | admin |
| `drishti.disk.check-interval` | duration | `60s` |  | server | every folder measured and brought under its cap this often | restart |  | expert |
| `drishti.disk.max-dumps` | integer | `2` |  | server | heap dumps kept in the -XX:HeapDumpPath folder; the oldest beyond go at start | restart |  | admin |
| `drishti.disk.dumps-dir` | string | `./data/dumps` |  | server | the dump folder when -XX:HeapDumpPath does not name one | restart |  | admin |

#### `drishti.native`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.native.cache-dir` | string | `./data/native-cache` | `DRISHTI_NATIVE_CACHE_DIR` | server | where the bundled native libraries are unpacked (JavaCPP's cache), under the data folder | restart |  | expert |
| `drishti.native.max-bytes-mb` | integer | `1024` |  | server | the most off-heap memory JavaCPP may allocate itself (org.bytedeco.javacpp.maxBytes); BLAS calls take Java arrays | restart |  | expert |
| `drishti.native.blas.mode` | string | `auto` | `DRISHTI_NATIVE_BLAS_MODE` | server | auto: the bundled OpenBLAS (Linux and Windows x86-64), else the Java kernels with a warning, an alert and Health DEGRADED; java: the Java kernels, chosen (no warning) | restart |  | expert |
| `drishti.native.blas.max-call-mb` | integer | `256` |  | server | a BLAS call whose operands pass this many MB runs on Java (bounds what one native call copies) | restart |  | expert |
| `drishti.native.blas.canary-interval` | duration | `6h` |  | server | the self-test against Java is run again this often (0: only at start); a failure switches OpenBLAS off for the run | restart |  | expert |
| `drishti.native.blas.gemm-min-sets` | integer | `32` |  | server | a 1,024-row tile of many-scope sums is one dgemm when at least this many sets share it (tuned on one machine: BENCHMARK.md section 17) | restart |  | expert |
| `drishti.native.blas.gemm-min-density` | number | `0.25` |  | server | ...and at least this share of its (set, row) pairs are members; sparser tiles are added in Java | restart |  | expert |

#### `drishti.engines`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.dir` | string | `./config/engines` | `DRISHTI_ENGINES_DIR` | server | the folder of the engines' files Admin -&gt; Engines writes (the imports above name the same folder) | restart |  | admin |
| `drishti.engines.drain` | duration | `30s` |  | server | an engine turned off live: requests in flight finish within this, then are cancelled | restart |  | admin |
| `drishti.engines.watch-seconds` | integer | `5` |  | server | how often the engine files are compared with what was read (an edit by hand takes effect as from the page) | restart |  | admin |

#### `drishti.metrics`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.metrics.enabled` | boolean | `true` | `DRISHTI_METRICS_ENABLED` | server | export the platform gauges (caches, memory budget, disk, sources, feeds, loads) on /actuator/prometheus and evaluate the alert rules | restart |  | admin |
| `drishti.metrics.sample-interval` | duration | `15s` |  | server | how often the gauges are sampled and the alert rules evaluated | restart |  | expert |
| `drishti.metrics.disk-interval` | duration | `60s` |  | server | how often the disk folders are measured for the gauges (a folder's size walks its files) | restart |  | expert |
| `drishti.metrics.alerts.enabled` | boolean | `true` | `DRISHTI_ALERTS_ENABLED` | server | evaluate the platform alert rules (Admin -&gt; Health; the alert engine lists them: Alerts, GET /api/v1/admin/alerts) | restart |  | admin |
| `drishti.metrics.alerts.file` | string | `./config/alerts/platform-alerts.yaml` | `DRISHTI_ALERTS_FILE` | server | the rules: metric, threshold, how long, severity (a missing file: no rules, and Health says so) | restart |  | admin |
| `drishti.metrics.alerts.notify-roles` | string | `admin` |  | server | the roles told in their inbox (and the bell) when an alert opens, comma-separated; blank: nobody | restart |  | admin |
| `drishti.metrics.alerts.keep` | integer | `200` |  | server | alerts opened and closed kept for the page, newest first (in memory: a restart starts afresh) | restart |  | admin |

#### `drishti.store`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.store.file` | string | `./config/store/store.yaml` | `DRISHTI_STORE_FILE` | server | The content store (docs/architecture/DATA\_ARCHITECTURE.md): user content every node of a cluster can reach. Its settings are in their own file. | restart |  | admin |
| `drishti.store.root` | string | empty | `DRISHTI_STORE_ROOT` | server | the filesystem backend's root, over the file's filesystem.root (empty: the file's, else &lt;data folder&gt;/fstore) | restart |  | admin |

#### `drishti.edit`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.edit.file` | string | `./config/edit/edit.yaml` | `DRISHTI_EDIT_FILE` | server | Write-back (docs/guides/WRITE\_BACK.md): its settings are in their own folder (edit.yaml, lanes.yaml, policy.yaml, retention.yaml). Off as shipped (edit.enabled: false there): a fresh install or an upgrade is read-only. | restart |  | admin |

#### `drishti.index`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.index.dir` | string | empty | `DRISHTI_INDEX_DIR` | server | Where the connectors' multi-day type-ahead index keeps closed days as block-compressed segment files (one subfolder per connector). Blank: &lt;data dir&gt;/index, i.e. ./data/index. Size is capped per connector by its index.max-disk; a day that rolls out of the window, is evicted by the cap or is rebuilt gives its disk back at once. | restart |  | admin |

#### `drishti.branding`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.branding.product` | string | `Drishti` | `DRISHTI_PRODUCT` | server | The product's name and legal notices, wherever people see them (pages, the About answer, messages). Change them here, not in code; the console has the same keys under ui: in drishti-console/config/application.yaml. | restart |  | admin |
| `drishti.branding.tagline` | string | `Any data. Any domain. One grammar.` |  | server | the line under the name | restart |  | admin |
| `drishti.branding.owner` | string | `Ashutosh Sinha` |  | server | who holds the rights, named in the About answer | restart |  | admin |
| `drishti.branding.copyright` | string | `Copyright (c) 2026 Ashutosh Sinha. All rights reserved. Proprietary and confi...` |  | server | the copyright line of the About answer (GET /api/v1/about) | restart |  | admin |
| `drishti.branding.notice` | string | `Unauthorised copying, use or distribution is prohibited.` |  | server | the legal notice beside it (also in the public /public/about) | restart |  | admin |

#### `drishti.offline`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.offline.enabled` | boolean | `false` | `DRISHTI_OFFLINE` | server | Offline mode, for a machine with no internet (a demonstration laptop): connectors of the plugins below, whose data comes from public web services, never start, whatever their files say; Admin -&gt; Connectors shows them "off (offline mode)", and their kinds are served by the other connectors that hold them (news-and-markets: news-store, built from the captured snapshot by `drishti.py lake demo`). | restart |  | admin |
| `drishti.offline.plugins` | string | `news,yfinance,feed` |  | server | the public-data feed plugin too (US Treasury, ECB, NY Fed, FRED: market-data pack) | restart |  | admin |

#### `drishti.net`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.net.dir` | string | `./config/net` | `DRISHTI_NET_DIR` | server | created on first write; ready-made files in config/net.examples/ | restart |  | admin |
| `drishti.net.watch` | string | `auto` | `DRISHTI_NET_WATCH` | server | auto (file events, polling as a fallback) \| poll \| off | restart |  | admin |
| `drishti.net.poll` | duration | `5s` |  | server | how often the folder is looked at when polling (and, with file events, at least this often anyway) | restart |  | expert |

#### `drishti.node`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.node.readiness.enabled` | boolean | `true` |  | server | false: readiness is Spring's alone (up once started) | restart |  | admin |
| `drishti.node.readiness.joining` | boolean | `true` |  | server | DOWN while this node joins Drishti\*net (its peers' digests not had yet; at most join-grace) | restart |  | admin |
| `drishti.node.readiness.engines` | boolean | `true` |  | server | DOWN while an enabled engine's (VaR, exposure) newest date is not open here; the check opens it | restart |  | admin |
| `drishti.node.readiness.shards` | boolean | `true` |  | server | DOWN while a VaR shard this member owns of a date in use is not ready here | restart |  | expert |
| `drishti.node.drain.settle-seconds` | integer | `5` |  | server | after the drain, how long until no peer forwards work here (the flag reaches every peer by gossip) | restart |  | admin |

#### `drishti.sources`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.sources.fetch-timeout` | duration | `10s` |  | server | Longest a single source read may take before the view shows the entity as unavailable (DRS-1004), and a panel's own source (another pack's data, for example) before the panel shows that it failed. 10 s: the first read of a business date on a large table loads that date's id index first (300K trades: a few seconds), and a slow source is not reported as missing. | restart |  | expert |
| `drishti.sources.plugin-dir` | string | empty | `DRISHTI_PLUGIN_DIR` | server | Optional directory of extra plugin jars; each gets its own class loader. | restart |  | admin |
| `drishti.sources.default-route` | string | `demo` |  | server | kind -&gt; plugin. Kinds without a route try default-route first, then any plugin that serves them. | restart |  | admin |
| `drishti.sources.routes` | map | `{}` |  | server | kind -&gt; plugin or connector that serves it (packs add theirs: trade: trade-endur) | restart |  | admin |
| `drishti.sources.connectors-dir` | string | `./config/connectors` | `DRISHTI_CONNECTORS_DIR` | server | Named connectors: several instances of a plugin, each with its own settings. They live in FILES, one per connector: &lt;connectors-dir&gt;/&lt;name&gt;.yaml the file name is the connector's logical name; packs refer to it by that name. The folder is watched: a changed file restarts only that connector, a new file starts one, a deleted file stops it. See docs/connectors/CONNECTOR\_FILES.md and Admin -&gt; Connectors. Ready-made files: config/connectors.examples/. created on first write | live |  | admin |
| `drishti.sources.connectors-watch` | string | `auto` |  | server | auto (file events, polling as a fallback) \| poll \| off | restart |  | admin |
| `drishti.sources.connectors-poll` | duration | `5s` |  | server | how often the files are looked at (also the safety net beside file events) | restart |  | expert |
| `drishti.sources.connectors-drain` | duration | `3s` |  | server | a connector being restarted or stopped stays open this long so reads in flight finish | restart |  | admin |
| `drishti.sources.connectors-generate` | boolean | `true` |  | server | write a connector file from a pack's connector template when none of that name exists | restart |  | admin |
| `drishti.sources.connectors` | map | `{}` |  | server | DEPRECATED: connectors defined here still work (and override the file of the same name, setting by setting), but the server logs a warning naming the equivalent file. Move them to &lt;connectors-dir&gt;/&lt;name&gt;.yaml. | restart |  | admin |

#### `drishti.sources.plugins`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.sources.plugins.demo.enabled` | boolean | `true` | `DRISHTI_DEMO_ENABLED` | server | run the demo source (the packs' sample data) | restart |  | admin |
| `drishti.sources.plugins.file.enabled` | boolean | `true` |  | server | run the bundled file source (JSON Lines under its root) | restart |  | admin |
| `drishti.sources.plugins.file.settings.root` | string | `./data/feeds` | `DRISHTI_FEEDS` | server | the folder the bundled file source reads | restart |  | admin |
| `drishti.sources.plugins.file.settings.source-name` | string | `feed-file` |  | server | the name its documents carry as provenance | restart |  | admin |
| `drishti.sources.plugins.file.settings.rescan-seconds` | integer | `30` |  | server | how often the folder is scanned for new or changed files | restart |  | admin |
| `drishti.sources.plugins.rest.enabled` | boolean | `false` | `DRISHTI_REST_ENABLED` | server | run the rest source as itself (one instance) | restart |  | admin |
| `drishti.sources.plugins.rest.settings.base-url` | string | `http://localhost:9000/api` | `DRISHTI_REST_URL` | server | the REST service's base URL | restart |  | admin |
| `drishti.sources.plugins.rest.settings.path` | string | `/{kind}/{id}` |  | server | the path of one entity under the base URL ({kind} and {id} filled in) | restart |  | admin |
| `drishti.sources.plugins.rest.settings.source-name` | string | `rest` |  | server | the name its documents carry as provenance | restart |  | admin |
| `drishti.sources.plugins.rest.settings.timeout-ms` | integer | `2000` |  | server | connect and request timeout, in milliseconds | restart |  | expert |
| `drishti.sources.plugins.jdbc.enabled` | boolean | `false` | `DRISHTI_JDBC_ENABLED` | server | run the jdbc source as itself (one instance) | restart |  | admin |
| `drishti.sources.plugins.jdbc.settings.url` | string | empty | `DRISHTI_JDBC_URL` | server | the JDBC URL of that instance | restart |  | admin |
| `drishti.sources.plugins.jdbc.settings.user` | string | empty | `DRISHTI_JDBC_USER` | server | its database user | restart |  | admin |
| `drishti.sources.plugins.jdbc.settings.password` | string | empty | `DRISHTI_JDBC_PASSWORD` | server | its database password (from the environment) (secret) | restart |  | admin |
| `drishti.sources.plugins.jdbc.settings.source-name` | string | `jdbc` |  | server | the name its documents carry as provenance | restart |  | admin |
| `drishti.sources.plugins.delta.enabled` | boolean | `false` |  | server | false: the delta plugin runs only as named connectors, never as itself | restart |  | admin |
| `drishti.sources.plugins.aerospike.enabled` | boolean | `false` |  | server | false: only as named connectors | restart |  | admin |
| `drishti.sources.plugins.cassandra.enabled` | boolean | `false` |  | server | false: only as named connectors (config/connectors.examples/cassandra/) | restart |  | admin |
| `drishti.sources.plugins.activemq.enabled` | boolean | `false` | `DRISHTI_ACTIVEMQ_ENABLED` | server | run the activemq source as itself | restart |  | admin |
| `drishti.sources.plugins.rabbitmq.enabled` | boolean | `false` | `DRISHTI_RABBITMQ_ENABLED` | server | run the rabbitmq source as itself | restart |  | admin |
| `drishti.sources.plugins.s3.enabled` | boolean | `false` | `DRISHTI_S3_ENABLED` | server | run the s3 source as itself | restart |  | admin |

#### `drishti.business-date`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.business-date.calendar` | string | `USNY` | `DRISHTI_CALENDAR` | server | Every read is for a business date. The default is today in `zone`, rolled back to a business day of `calendar` (weekends and holidays roll to the previous business day). Users pick another date in the top bar; dated sources (Delta Lake, dated folders, databases with a date column) then read that date. USNY, GBLO, EUTA, JPTO, CATO or joint, e.g. USNY+GBLO | restart |  | admin |
| `drishti.business-date.zone` | string | `America/New_York` |  | server | whose clock decides what today is (any Java zone id: Europe/London) | restart |  | admin |
| `drishti.business-date.history` | duration | `P5Y` |  | server | how far back users may go | restart |  | admin |

#### `drishti.history`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.history.enabled` | boolean | `true` |  | server | History queries (TRD 1000015 30): one entity over the business days it has data. docs/architecture/DATA\_ARCHITECTURE.md off: no history is read; the endpoint answers DRS-1012 | restart |  | admin |
| `drishti.history.default-days` | integer | `30` |  | server | the range when a link or the CLI names none | restart |  | admin |
| `drishti.history.max-days` | integer | `366` |  | server | the most business days one request may span (a pack's max-days can only lower it) | restart |  | admin |
| `drishti.history.max-rows` | integer | `1000` |  | server | the most rows returned | restart |  | expert |
| `drishti.history.max-columns` | integer | `200` |  | server | the most columns | restart |  | admin |
| `drishti.history.timeout` | duration | `10s` |  | server | the whole request; days not read by then make the answer partial (DRS-1014) | restart |  | expert |
| `drishti.history.parallel-days` | integer | `8` |  | server | days read at once per request by the default per-day loop | restart |  | expert |
| `drishti.history.max-concurrent` | integer | `16` |  | server | history requests running at once on this server | restart |  | expert |
| `drishti.history.cache-mb` | integer | `256` |  | server | heap for cached answers (keyed by the data's version), one budget for every caller | restart |  | expert |

#### `drishti.security`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.security.enabled` | boolean | `false` | `DRISHTI_SECURITY_ENABLED` | server | Off for local development. Production: enabled: true and DRISHTI\_TOKEN\_SECRET (&gt;= 32 bytes, shared with the console). | restart |  | admin |
| `drishti.security.secret` | string | empty | `DRISHTI_TOKEN_SECRET` | server | the token signing secret (at least 32 bytes), shared with the console; from the environment only | restart |  | admin |
| `drishti.security.registered-users-only` | boolean | `true` |  | server | a signed token is refused when its user was deleted or disabled (false: trust the token alone) | restart |  | admin |
| `drishti.security.clock-skew` | duration | `30s` |  | server | clock difference tolerated when checking a token's expiry | restart |  | admin |
| `drishti.security.metrics-token` | string | empty | `DRISHTI_METRICS_TOKEN` | server | With security on, /actuator (except /actuator/health) needs an admin token or this one, for Prometheus: scrape\_configs: \[{ job\_name: drishti, metrics\_path: /actuator/prometheus, bearer\_token: &lt;this&gt; }] (secret) | restart |  | admin |
| `drishti.security.token-read-posts` | list | empty |  | server | A personal API token reads by default: GET and HEAD, plus these POSTs, which carry a query in the body and change nothing (a pivot of a search, a command line). Add a path only if it writes nothing. | restart |  | admin |
| `drishti.security.token-scopes` | map | `{…}` |  | server | WRITE SCOPES (TOKEN\_SCOPES in docs/guides/API\_GUIDE.md): a token is made with `read` (the default: all of the above) or with scopes from this list (keys in \[brackets] keep the colon); each opens the METHOD path patterns under `allow` (\* = any method). A scope is only a door: the controller behind it still checks the roles of the token's user at the time of the call, so a token never does more than its user. A write that no scope opens, and nothing in token-never, is refused. Every non-GET endpoint must be declared in token-read-posts, a scope or token-never (TokenScopeGuardTest fails the build otherwise). | restart |  | admin |
| `drishti.security.token-never` | list | empty |  | server | never for a token, whatever its scopes: tokens, users, sign-in, personal and collaboration state | restart |  | admin |
| `drishti.security.token-write-max-days` | integer | `90` |  | server | a token with a write scope must expire, within this many days (read tokens: 1-366 or never) | restart |  | admin |
| `drishti.security.roles` | map | `{…}` |  | server | the built-in roles: kinds ("\*" all), raw (see unmasked JSON), author, approve, admin, calc, layout, edit and bi powers; packs add roles of their own | restart |  | admin |
| `drishti.security.redact` | list | `[trader, TRADER_ID, TRADER_FIRST_NAME, TRADER_LAST_NAME, counterpartyId, pati...` |  | server | Fields masked (•••) for roles without raw: raw JSON, views, search, Impact, type-ahead, alerts. A name masks that field at any depth, in any case and convention (counterpartyId masks COUNTERPARTY\_ID); an entry with dots masks by the end of the path. An entry that names no field of the loaded packs' samples is logged at start-up (DRS-6011) and listed in Admin -&gt; Health. trader, patientName and lifecycle.timeline.description are the camelCase example packs' (examples/packs); counterpartyId masks the core pack's COUNTERPARTY\_ID as it masked banking-core's counterpartyId before its rename. | restart |  | admin |
| `drishti.security.mask-copies` | boolean | `false` | `DRISHTI_MASK_COPIES` | server | Opt-in: also replace exact copies of a masked field's value found in the OTHER text of the same document (best effort: exact, case-sensitive; text of at least min-length characters, numbers of 4+ digits). Documents over max-nodes are not scanned. | restart |  | admin |
| `drishti.security.mask-copies-min-length` | integer | `3` |  | server | shortest text value scrubbed as a copy (numbers from four digits) | restart |  | admin |
| `drishti.security.mask-copies-max-nodes` | integer | `50000` |  | server | a document with more nodes is not scanned for copies (its masked fields are still masked) | restart |  | admin |
| `drishti.security.mask-copies-max-values` | integer | `64` |  | server | most distinct masked values scrubbed per document | restart |  | admin |

#### `drishti.security.oidc`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.security.oidc.enabled` | boolean | `false` | `DRISHTI_OIDC_ENABLED` | server | verify single-sign-on ID tokens | restart |  | admin |
| `drishti.security.oidc.issuer` | string | empty | `DRISHTI_OIDC_ISSUER` | server | the OIDC issuer URL | restart |  | admin |
| `drishti.security.oidc.client-id` | string | empty | `DRISHTI_OIDC_CLIENT_ID` | server | the OIDC client id (the token's audience) | restart |  | admin |
| `drishti.security.oidc.username-claim` | string | `preferred_username` |  | server | the claim that becomes the user name (email is common) | restart |  | admin |
| `drishti.security.oidc.display-claim` | string | `name` |  | server | the claim shown as the user's name | restart |  | admin |
| `drishti.security.oidc.groups-claim` | string | `groups` |  | server | a dotted path works too: realm\_access.roles | restart |  | admin |
| `drishti.security.oidc.role-map` | map | `{}` |  | server | provider group -&gt; Drishti roles, e.g. { desk-rates: \[trader], market-risk: \[risk] } | restart |  | admin |
| `drishti.security.oidc.default-roles` | list | `[]` |  | server | roles when no group maps; empty refuses such users | restart |  | admin |
| `drishti.security.oidc.roles-from-provider` | boolean | `true` |  | server | the provider's groups replace the roles at every sign-in | restart |  | admin |
| `drishti.security.oidc.algorithms` | list | `[RS256, ES256]` |  | server | accepted signature algorithms: RS256/384/512, PS256/384/512, ES256/384/512; anything else stops start-up | restart |  | admin |

#### `drishti.embed`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.embed.enabled` | boolean | `false` | `DRISHTI_EMBED_ENABLED` | server | off: no token endpoint, embed tokens refused. Needs drishti.security.enabled | restart |  | admin |
| `drishti.embed.issuer` | string | empty | `DRISHTI_PUBLIC_URL` | server | the iss of embed tokens: this server's public URL | restart |  | admin |
| `drishti.embed.audiences` | list | `[""]` | `DRISHTI_CONSOLE_URL` | server | the consoles that may accept embed tokens (their public URLs); the first is the default | restart |  | admin |
| `drishti.embed.signing-key` | string | empty | `DRISHTI_EMBED_KEY_FILE` | server | PEM with a PKCS#8 EC PRIVATE KEY and a PUBLIC KEY block (ES256); empty = generated at start (secret) | restart |  | admin |
| `drishti.embed.token-max-seconds` | integer | `900` |  | server | longest life of an embed token (an application's own lifetime may be shorter) | restart |  | admin |
| `drishti.embed.mask` | string | `always` |  | server | embed calls always mask drishti.security.redact fields, whatever the user's roles | restart |  | admin |
| `drishti.embed.allow-wildcard-origins` | boolean | `false` |  | server | let an application register https://\*.suffix origins (review apps) | restart |  | admin |
| `drishti.embed.assertion-max-age` | duration | `60s` |  | server | longest life of a client or user assertion a host signs | restart |  | admin |
| `drishti.embed.sign-in-window` | boolean | `true` | `DRISHTI_EMBED_SIGN_IN_WINDOW` | server | the renderer's auth: 'popup' (CLIENT\_RENDERER.md 10.1): a registered host's page signs its user in | restart |  | admin |
| `drishti.embed.scopes` | map | `{…}` |  | server | what each embed scope opens; a token calls nothing else, and never writes | restart |  | admin |
| `drishti.embed.search.max-results` | integer | `10` |  | server | most results one search returns | restart |  | admin |
| `drishti.embed.search.min-chars` | integer | `2` |  | server | shortest query searched | restart |  | admin |
| `drishti.embed.limits.tokens-per-minute` | integer | `120` |  | server | exchanges per host application | restart |  | admin |
| `drishti.embed.limits.views-per-minute` | integer | `600` |  | server | calls per host application (the default for a new registration) | restart |  | admin |
| `drishti.embed.limits.views-per-user-per-minute` | integer | `60` |  | server | calls per user (the default for a new registration) | restart |  | admin |
| `drishti.embed.limits.searches-per-user-per-minute` | integer | `30` |  | server | searches per user and application through &lt;drishti-search&gt;, on top of the call rates | restart |  | admin |
| `drishti.embed.apps` | list | `[]` |  | server | host applications declared here (GitOps): read-only in Admin → Embedding ("from config"), see docs/admin/CONFIGURATION.md | restart |  | admin |

#### `drishti.retry`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.retry.enabled` | boolean | `true` |  | server | retry at all; false leaves a failure to the next file change or an administrator's Retry now | restart |  | expert |
| `drishti.retry.initial` | duration | `5s` |  | server | wait before the first retry | restart |  | expert |
| `drishti.retry.factor` | number | `2.0` |  | server | each further wait is this many times the one before | restart |  | expert |
| `drishti.retry.max` | duration | `5m` |  | server | no wait is longer than this | restart |  | expert |
| `drishti.retry.jitter` | number | `0.2` |  | server | each wait varies by up to this fraction either way | restart |  | expert |
| `drishti.retry.permanent-after` | integer | `3` |  | server | a failure waiting cannot fix (refused credentials, bad settings) seen this often in a row is retried only every max | restart |  | expert |

#### `drishti.ai`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.ai.enabled` | boolean | `false` | `DRISHTI_AI_ENABLED` | server | turn on the LLM provider layer: provider files in drishti.ai.dir are read and models become callable | restart |  | admin |
| `drishti.ai.dir` | string | `./config/ai` | `DRISHTI_AI_DIR` | server | one YAML per provider: type, base-url, api-key as ${ENV}, models with logical names | restart |  | admin |
| `drishti.ai.watch` | string | `auto` |  | server | auto \| poll \| off: how edits to the folder are noticed (live reload, like connectors) | restart |  | admin |
| `drishti.ai.poll-ms` | integer | `5000` |  | server | how often the folder is looked at, in milliseconds (polling, and the safety net beside file events) | restart |  | expert |
| `drishti.ai.default-model` | string | empty | `DRISHTI_AI_DEFAULT_MODEL` | server | provider/model, or a model name only one provider has; used when a call names none | restart |  | admin |
| `drishti.ai.policy.mask-redacted` | boolean | `true` |  | server | mask drishti.security.redact fields (and mask-patterns) in every prompt | restart |  | admin |
| `drishti.ai.policy.mask-patterns` | list | `[]` |  | server | extra regular expressions replaced by the mask in every prompt | restart |  | admin |
| `drishti.ai.policy.audit` | boolean | `true` |  | server | one access-log row per call (action ai): who, feature, model, sizes, tokens, outcome; never the text | restart |  | admin |
| `drishti.ai.policy.per-user-per-minute` | integer | `0` |  | server | 0 = no limit | restart |  | admin |
| `drishti.ai.policy.per-user-per-day` | integer | `0` |  | server | calls a user may make per day (0 = no limit) | restart |  | admin |
| `drishti.ai.policy.cost-per-user-per-day` | integer | `0` |  | server | in the unit of the models' cost-per-1k-\* prices | restart |  | admin |
| `drishti.ai.policy.features` | map | `{}` |  | server | per feature: "\[ask]": {roles: \[..], per-user-per-minute: 6} | restart |  | admin |

#### `drishti.mcp`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.mcp.enabled` | boolean | `false` | `DRISHTI_MCP_ENABLED` | server | turn on the MCP client: server files in drishti.mcp.dir are read and their tools become callable | restart |  | admin |
| `drishti.mcp.dir` | string | `./config/mcp` | `DRISHTI_MCP_DIR` | server | one YAML per MCP server: transport, url, headers as ${ENV}, allowed-tools | restart |  | admin |
| `drishti.mcp.watch` | string | `auto` |  | server | auto \| poll \| off: how edits to the folder are noticed (live reload) | restart |  | admin |
| `drishti.mcp.poll-ms` | integer | `5000` |  | server | how often the folder is looked at, in milliseconds | restart |  | expert |
| `drishti.mcp.audit` | boolean | `true` |  | server | one access-log row per tool call | restart |  | admin |
| `drishti.mcp.server-roles` | map | `{}` |  | server | "\[server]": \[roles] absent = every signed-in user | restart |  | admin |
| `drishti.mcp.tool-roles` | map | `{}` |  | server | "\[server/tool]": \[roles] | restart |  | admin |
| `drishti.mcp.server-features` | map | `{}` |  | server | "\[server]": \[features] | restart |  | admin |

#### `drishti.approvals`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.approvals.due-soon-days` | integer | `3` |  | server | an item due within this many days (or overdue) is pinned at the top as due soon | restart |  | admin |
| `drishti.approvals.due-days.var-report` | integer | `30` |  | server | a regulatory report run (VAR\_GUIDE.md section 18.14) | restart |  | admin |
| `drishti.approvals.per-kind` | integer | `200` |  | server | most items one kind adds to one list | restart |  | admin |
| `drishti.approvals.max-question` | integer | `2000` |  | server | longest question or answer on an item, in characters | restart |  | admin |
| `drishti.approvals.max-questions` | integer | `200` |  | server | most questions and answers kept on one item | restart |  | admin |

#### `drishti.alerts`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.alerts.enabled` | boolean | `true` |  | server | false stops the evaluator and refuses new alert rules (stored ones are kept) | restart |  | admin |

#### `drishti.access-log`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.access-log.enabled` | boolean | `true` | `DRISHTI_ACCESS_LOG` | server | record who looked at what (Admin -&gt; Access) | restart |  | admin |
| `drishti.access-log.keep-days` | integer | `90` |  | server | older events are pruned once a day | restart |  | admin |
| `drishti.access-log.queue` | integer | `100000` |  | server | events waiting to be written at most; beyond it they are dropped and counted | restart |  | expert |

#### `drishti.collab`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.enabled` | boolean | `true` | `DRISHTI_COLLAB_ENABLED` | server | share with a note, the directory and the inbox (docs/architecture/COLLABORATION.md) | restart |  | admin |
| `drishti.collab.store` | string | `content` |  | server | content: the content store, every cluster node (default) \| jpa: the identity database, one node \| file: one server only | restart |  | admin |
| `drishti.collab.dir` | string | `./data/collab` | `DRISHTI_COLLAB_DIR` | server | the file store, and exports | restart |  | admin |
| `drishti.collab.console-url` | string | empty | `DRISHTI_CONSOLE_URL` | server | links in emails and shares; empty turns email off | restart |  | admin |
| `drishti.collab.mentionable-roles` | list | `["*"]` |  | server | roles usable as groups (admin and service never) | restart |  | admin |
| `drishti.collab.max-group-size` | integer | `200` |  | server | largest role a share or mention may expand to | restart |  | admin |
| `drishti.collab.export-keep` | duration | `24h` |  | server | how long a finished compliance export is kept before it is deleted (also deleted once downloaded) | restart |  | admin |
| `drishti.collab.packs` | map | `{}` |  | server | per-pack overrides, by pack name: news-and-markets: { share-enabled: false } | restart |  | admin |

#### `drishti.collab.directory`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.directory.scope` | string | `shared-packs` |  | server | shared-packs: only users who share a pack with the caller \| all | restart |  | admin |
| `drishti.collab.directory.min-query` | integer | `2` |  | server | fewest characters a directory search needs | restart |  | admin |
| `drishti.collab.directory.limit` | integer | `10` |  | server | most entries one directory search returns | restart |  | admin |

#### `drishti.collab.share`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.share.max-recipients` | integer | `25` |  | server | names (people and roles) in one share | restart |  | admin |
| `drishti.collab.share.max-expanded` | integer | `200` |  | server | people after roles are expanded | restart |  | admin |
| `drishti.collab.share.max-text` | integer | `2000` |  | server | longest note, in characters | restart |  | admin |
| `drishti.collab.share.undeliverable` | string | `tell` |  | server | tell: the sender learns who was not notified and why \| silent | restart |  | admin |
| `drishti.collab.share.post-to-thread` | boolean | `false` |  | server | the default when a share request does not say (the dialog's tick, the server) | restart |  | admin |
| `drishti.collab.share.max-view-state` | integer | `4000` |  | server | longest view state (control values and column filters) a share carries, in characters | restart |  | admin |

#### `drishti.collab.threads`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.threads.max-text` | integer | `4000` |  | server | longest comment, in characters | restart |  | admin |
| `drishti.collab.threads.max-per-entity` | integer | `500` |  | server | most threads on one entity | restart |  | admin |
| `drishti.collab.threads.edit-window` | duration | `15m` |  | server | how long after writing the author may edit a comment (after it, retract only) | restart |  | admin |
| `drishti.collab.threads.page-size` | integer | `50` |  | server | comments per page of a thread | restart |  | expert |

#### `drishti.collab.text`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.text.on-masked-copy` | string | `warn` |  | server | a masked field's value typed into a note: warn \| reject \| allow (it is always scrubbed for readers without raw) | restart |  | admin |
| `drishti.collab.text.deny-patterns` | list | `[]` |  | server | regular expressions refused in notes and comments | restart |  | admin |

#### `drishti.collab.limits`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.limits.shares-per-minute` | integer | `5` |  | server | shares one user sends a minute (429 DRS-7003, with Retry-After) | restart |  | admin |
| `drishti.collab.limits.shares-per-day` | integer | `100` |  | server | shares one user sends in 24 hours (counted in the store: survives restarts, holds across servers) | restart |  | admin |
| `drishti.collab.limits.comments-per-minute` | integer | `10` |  | server | comments (and share replies) one user writes a minute (429 DRS-7003) | restart |  | admin |
| `drishti.collab.limits.directory-per-minute` | integer | `60` |  | server | directory searches one user makes a minute | restart |  | admin |
| `drishti.collab.limits.mails-per-recipient-per-hour` | integer | `30` |  | server | emails queued for one recipient in an hour; more are not queued (the bell still rings) and a warning is logged | restart |  | admin |

#### `drishti.collab.inbox`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.inbox.keep` | integer | `1000` |  | server | newest rows kept per user | restart |  | admin |
| `drishti.collab.inbox.keep-days` | integer | `180` |  | server | days an inbox row is kept; older rows are removed every retention interval (and at start) | restart |  | admin |
| `drishti.collab.inbox.poll` | duration | `5s` |  | server | a server looks for rows other servers wrote, only while a stream is open | restart |  | expert |
| `drishti.collab.inbox.coalesce` | duration | `60s` |  | server | several notices to one person about one thread within this long become one email (a share is always its own) | restart |  | expert |

#### `drishti.collab.email`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.email.enabled` | boolean | `false` |  | server | also needs spring.mail.host and console-url, and security on | restart |  | admin |
| `drishti.collab.email.content` | string | `comment` |  | server | link-only \| title \| comment | restart |  | admin |
| `drishti.collab.email.from` | string | `drishti@localhost` | `DRISHTI_MAIL_FROM` | server | the From address of collaboration email | restart |  | admin |
| `drishti.collab.email.templates-dir` | string | `""` |  | server | a folder whose share.\*, mention.\*, reply.\*, digest.\*, test.\* files replace the built-in email templates, file by file; empty: built-in | restart |  | admin |
| `drishti.collab.email.coalesce-window` | duration | `10s` |  | server | a lone notice goes after this short delay; notices for one person arriving meanwhile join it as one digest; 0 = every notice at once | restart |  | expert |

#### `drishti.collab.outbox`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.outbox.enabled` | boolean | `true` |  | server | this server sends the email outbox; off on servers of a group that should not (rows are still written, any dispatching server sends them) | restart |  | admin |
| `drishti.collab.outbox.tick` | duration | `2s` |  | server | how often the dispatcher looks for rows to send | restart |  | admin |
| `drishti.collab.outbox.batch` | integer | `50` |  | server | rows sent per tick | restart |  | expert |
| `drishti.collab.outbox.max-attempts` | integer | `8` |  | server | attempts before a row is dead (Admin -&gt; Collaboration lists it) | restart |  | admin |
| `drishti.collab.outbox.backoff` | duration | `30s` |  | server | the first retry delay, doubling up to max-backoff | restart |  | expert |
| `drishti.collab.outbox.max-backoff` | duration | `1h` |  | server | the longest retry delay | restart |  | expert |
| `drishti.collab.outbox.lease` | duration | `60s` |  | server | how long a claimed row is held by one server before another may take it | restart |  | admin |
| `drishti.collab.outbox.keep-sent-days` | integer | `30` |  | server | days a sent row is kept | restart |  | admin |

#### `drishti.collab.retention`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.retention.keep-days` | integer | `0` |  | server | shares and threads; 0 keeps them forever | restart |  | admin |
| `drishti.collab.retention.kinds` | map | `{}` |  | server | days per entity kind, by kind name: { trade: 2555 }; beats the pack and the default | restart |  | admin |
| `drishti.collab.retention.interval` | duration | `24h` |  | server | how often the purge runs; it only runs when some retention above 0 is set (a pack: packs.&lt;pack&gt;.retention-days) | restart |  | expert |

#### `drishti.collab.bridges`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.bridges.enabled` | boolean | `false` |  | server | chat bridges: post a comment and its link (or an alert) to Teams, Slack or a signed webhook | restart |  | admin |
| `drishti.collab.bridges.render-as` | string | `viewer` |  | server | the role whose masks the posted text follows (the default has no raw) | restart |  | admin |
| `drishti.collab.bridges.allow` | list | `[]` |  | server | URL prefixes a bridge URL must start with; empty: no bridge can post | restart |  | admin |
| `drishti.collab.bridges.per-minute` | integer | `30` |  | server | posts per bridge per minute; the rest wait in the outbox | restart |  | admin |
| `drishti.collab.bridges.timeout` | duration | `10s` |  | server | connect and response time for one post; redirects are never followed | restart |  | expert |
| `drishti.collab.bridges.max-note` | integer | `500` |  | server | characters of the note a post carries | restart |  | admin |
| `drishti.collab.bridges.webhooks` | list | `[]` |  | server | \[{ name: risk-teams, format: teams, url-env: BRIDGE\_RISK\_URL, routes: \[{ packs: \[..], kinds: \[..], events: \[share, comment, mention, alert] }] }] | restart |  | admin |

#### `drishti.collab.snapshots`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.collab.snapshots.enabled` | boolean | `false` |  | server | off: no share carries a picture | restart |  | admin |
| `drishti.collab.snapshots.width` | integer | `960` |  | server | pixels wide | restart |  | admin |
| `drishti.collab.snapshots.max-height` | integer | `1800` |  | server | pixels tall at most; the rest of the view is left out, with a line saying so | restart |  | admin |
| `drishti.collab.snapshots.max-panels` | integer | `8` |  | server | most panels drawn in a snapshot, and most rows of a table, list or timeline | restart |  | admin |
| `drishti.collab.snapshots.max-rows` | integer | `12` |  | server | rows drawn of a table, list or timeline | restart |  | expert |
| `drishti.collab.snapshots.max-bytes` | integer | `1500000` |  | server | largest PNG; larger is refused | restart |  | expert |
| `drishti.collab.snapshots.timeout` | duration | `5s` |  | server | longest one drawing may take | restart |  | expert |
| `drishti.collab.snapshots.cache-size` | integer | `50` |  | server | pictures kept by view, generation and rights profile | restart |  | expert |
| `drishti.collab.snapshots.cache-ttl` | duration | `10m` |  | server | how long snapshot views and pictures are kept, by view, generation and rights profile | restart |  | expert |
| `drishti.collab.snapshots.recipients` | string | `count` |  | server | the watermark names: count \| names | restart |  | admin |

#### `drishti.admin`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.admin.uninstalled-dir` | string | `./data/uninstalled` | `DRISHTI_UNINSTALLED_DIR` | server | an uninstall MOVES the artifact here; nothing is deleted | restart |  | admin |
| `drishti.admin.uninstalled-keep` | duration | `30d` | `DRISHTI_UNINSTALLED_KEEP` | server | archives older than this are removed, once a day; 0 keeps them for ever | restart |  | admin |
| `drishti.admin.uninstalled-prune-interval` | duration | `1d` |  | server | how often the archive is pruned | restart |  | expert |
| `drishti.admin.hidden-bi-file` | string | `./data/admin/bi-hidden.txt` |  | server | the shipped BI files an administrator disabled (hidden from the catalogue) | restart |  | admin |
| `drishti.admin.bi-upload-max-bytes` | integer | `1048576` |  | server | the largest BI file Admin, Artifacts accepts | restart |  | expert |

#### `drishti.reports`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.reports.enabled` | boolean | `true` | `DRISHTI_REPORTS_ENABLED` | server | the scheduler; run it on one server of a group (reports still run by hand) | restart |  | admin |
| `drishti.reports.folder` | string | `./data/reports` | `DRISHTI_REPORTS_DIR` | server | where report files are written | restart |  | admin |
| `drishti.reports.webhooks` | list | `[]` |  | server | URL prefixes reports may post to, e.g. \[https://hooks.bank.example/drishti/]; empty: none | restart |  | admin |
| `drishti.reports.per-user` | integer | `20` |  | server | reports a person may keep | restart |  | admin |
| `drishti.reports.tick` | duration | `30s` |  | server | how often the scheduler looks for due reports | restart |  | admin |
| `drishti.reports.keep-runs` | integer | `20` |  | server | runs remembered per report (time, rows, where it went, or the error) | restart |  | admin |

#### `drishti.calc`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.calc.enabled` | boolean | `false` | `DRISHTI_LABS_CALC` | server | Labs: off as shipped; on also needs a role with calc, and a pack that enables it for the view's kind | restart |  | admin |
| `drishti.calc.max-column-rows` | integer | `250000` |  | server | drishti.columns() returns at most this many entities (the browser holds them all) | restart |  | admin |
| `drishti.calc.columns-budget` | duration | `20s` |  | server | how long a columns read may wait (the first read of a day loads its columns) | restart |  | admin |
| `drishti.calc.max-snippet-chars` | integer | `50000` |  | server | a saved snippet's code (each user keeps at most 50 snippets) | restart |  | admin |

#### `drishti.bi`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.enabled` | boolean | `true` |  | server | Rupaka phase 1 (shell and catalogue): false answers DRS-9006 and the console hides the BI menu | restart |  | admin |
| `drishti.bi.dir` | string | `./config/bi` | `DRISHTI_BI_DIR` | server | Rupaka files: datasets/, reports/, boards/, notebooks/, python/ (config/bi/README.md); never inside a pack | restart |  | admin |
| `drishti.bi.watch` | string | `auto` |  | server | watch the BI folder: auto (file events + polling), poll, off. Files added, changed or deleted are picked up live; a bad file keeps its last good version | restart |  | admin |
| `drishti.bi.folder-refresh` | duration | `5s` |  | server | the polling interval (with watch: off, how long a read is reused) | restart |  | admin |
| `drishti.bi.workspace-creation` | string | `authors` |  | server | who creates shared workspaces: authors (bi-author), stewards (bi-steward), admins (bi-admin); Admin, BI settings changes it at run time | restart |  | admin |
| `drishti.bi.max-body-bytes` | integer | `262144` |  | server | largest YAML body of one item | restart |  | expert |
| `drishti.bi.versions-keep` | integer | `50` |  | server | published versions kept per item | restart |  | admin |
| `drishti.bi.usage-days` | integer | `30` |  | server | days of views counted as usage | restart |  | admin |
| `drishti.bi.recent` | integer | `12` |  | server | rows under Recent, Certified and Shared on Home | restart |  | admin |
| `drishti.bi.list-limit` | integer | `200` |  | server | most rows one catalogue or workspace page returns | restart |  | admin |

#### `drishti.bi.ai`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.ai.model` | string | `""` |  | server | provider/model; blank = drishti.ai.default-model | restart |  | admin |
| `drishti.bi.ai.values` | string | `labels-only` |  | server | labels-only: the model sees names, types, descriptions, never a value \| aggregates: it may also word the reader's own masked aggregates | restart |  | admin |
| `drishti.bi.ai.max-question-chars` | integer | `600` |  | server | longest question or description sent to the model | restart |  | admin |
| `drishti.bi.ai.max-tokens` | integer | `2000` |  | server | longest answer asked of the model | restart |  | admin |
| `drishti.bi.ai.timeout-seconds` | integer | `30` |  | server | the longest one model call may take | restart |  | expert |
| `drishti.bi.ai.ask-rows` | integer | `200` |  | server | most rows an answer holds (and the model sees, in aggregates mode) | restart |  | admin |
| `drishti.bi.ai.explain-dimensions` | integer | `3` |  | server | explain this number: dimensions broken down | restart |  | admin |
| `drishti.bi.ai.explain-members` | integer | `5` |  | server | members listed per dimension | restart |  | admin |
| `drishti.bi.ai.suggestions` | integer | `6` |  | server | designer copilot: most visuals proposed at once | restart |  | admin |
| `drishti.bi.ai.helper-items` | integer | `8` |  | server | dataset helper: most items proposed per kind (relationships, measures, column descriptions) | restart |  | admin |

#### `drishti.bi.query`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.query.store-dir` | string | `./data/bi-store` | `DRISHTI_BI_STORE` | server | the BI store: typed Parquet layers (columns, rollups) written here; never under the repository | restart |  | admin |
| `drishti.bi.query.store-max-gb` | integer | `50` |  | server | the most disk the layers take; beyond it the least recently built dates go (never a table's newest) | restart |  | admin |
| `drishti.bi.query.store-max-dates` | integer | `260` |  | server | the most business dates a table keeps in its layer (the oldest beyond go); checked at start and every 10 min | restart |  | admin |
| `drishti.bi.query.timeout` | duration | `30s` |  | server | longest a query may run (DRS-9024) | restart |  | expert |
| `drishti.bi.query.max-rows` | integer | `100000` |  | server | most rows a query returns (DRS-9022) | restart |  | expert |
| `drishti.bi.query.max-bytes` | integer | `67108864` |  | server | most bytes of an Arrow answer (DRS-9023) | restart |  | expert |
| `drishti.bi.query.max-concurrent` | integer | `8` |  | server | queries running at once on the server (DRS-9025) | restart |  | expert |
| `drishti.bi.query.max-concurrent-per-user` | integer | `3` |  | server | queries running at once for one user (DRS-9025) | restart |  | expert |
| `drishti.bi.query.admission` | duration | `2s` |  | server | how long a query waits for a free slot before it is refused | restart |  | expert |
| `drishti.bi.query.json-max-rows` | integer | `5000` |  | server | most rows of an answer in JSON (larger results are asked for as Arrow) | restart |  | expert |
| `drishti.bi.query.cache-entries` | integer | `200` |  | server | results kept; 0 switches the result cache off | restart |  | expert |
| `drishti.bi.query.cache-max-bytes` | integer | `67108864` |  | server | the cache's budget | restart |  | expert |
| `drishti.bi.query.cache-ttl` | duration | `15m` |  | server | longest a cached result is served | restart |  | expert |
| `drishti.bi.query.memory-limit` | size | `768MB` |  | server | DuckDB's memory limit | restart |  | expert |
| `drishti.bi.query.threads` | integer | `2` |  | server | DuckDB's worker threads | restart |  | expert |
| `drishti.bi.query.scan-batch-rows` | integer | `65536` |  | server | rows in one batch when a layer is built from a connector | restart |  | expert |
| `drishti.bi.query.record-read-max` | integer | `50000` |  | server | most documents read one by one when a connector cannot scan columns (the slow path) | restart |  | admin |
| `drishti.bi.query.record-read-budget` | duration | `60s` |  | server | how long that read may take | restart |  | admin |
| `drishti.bi.query.latest-ttl` | duration | `30s` |  | server | how long "the latest business date" of a table is trusted | restart |  | admin |
| `drishti.bi.query.eager-rebuild` | boolean | `true` |  | server | on a data-load signal, rebuild the date in the background (false: on the next query) | restart |  | admin |
| `drishti.bi.query.live-ttl` | duration | `5s` |  | server | how long a live table's snapshot is served | restart |  | admin |
| `drishti.bi.query.infer-sample` | integer | `5000` |  | server | values of a column the relationship finder looks at | restart |  | admin |
| `drishti.bi.query.infer-min-match` | number | `0.8` |  | server | the share of values found among the other table's ids for a relationship to be proposed | restart |  | admin |

#### `drishti.bi.transform`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.transform.enabled` | boolean | `true` |  | server | false: every Python run (Transforms, Python measures and visuals, Notebook cells) answers DRS-9074; the code runs on the compute pool python (config/compute/compute.yaml) | restart |  | admin |
| `drishti.bi.transform.cache-ttl` | duration | `30m` |  | server | a Transform's result is kept for the reader it was made for this long (and until the inputs change) | restart |  | expert |
| `drishti.bi.transform.cache-entries` | integer | `20` |  | server | results kept per derived dataset; the oldest go first | restart |  | expert |
| `drishti.bi.transform.max-input-rows` | integer | `50000` |  | server | most rows of one input frame handed to a Transform | restart |  | admin |
| `drishti.bi.transform.max-rows` | integer | `100000` |  | server | most rows a Transform may return (DRS-9072) | restart |  | expert |
| `drishti.bi.transform.max-bytes` | integer | `33554432` |  | server | largest result file (DRS-9072) | restart |  | expert |
| `drishti.bi.transform.timeout` | duration | `60s` |  | server | longest a run may take (the item's own timeout may be shorter) | restart |  | expert |
| `drishti.bi.transform.max-concurrent` | integer | `2` |  | server | Transforms running at once | restart |  | expert |
| `drishti.bi.transform.queue-wait` | duration | `5s` |  | server | how long a run waits for a place before DRS-9074 | restart |  | expert |
| `drishti.bi.transform.compose-source-max-rows` | integer | `50000` |  | server | most rows one source of a composed dataset may answer (DRS-9022) | restart |  | expert |

#### `drishti.bi.exec`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.exec.per-model-share` | number | `0.5` |  | server | the share of drishti.bi.query.max-concurrent one model may hold at once (work classes: interactive, standing, background) | restart |  | admin |
| `drishti.bi.exec.queue` | duration | `2s` |  | server | how long a source fragment waits for its connector's pool before DRS-9025 | restart |  | expert |
| `drishti.bi.exec.interactive` | duration | `30s` |  | server | longest an interactive query runs (DRS-9024) | restart |  | admin |
| `drishti.bi.exec.background` | duration | `300s` |  | server | longest background work runs (refresh, precompute, standing-query maintenance) | restart |  | admin |
| `drishti.bi.exec.max-estimated-rows` | integer | `200000000` |  | server | the planner's estimate of rows read past which a query is refused (DRS-9029) unless options.budget allows more | restart |  | admin |
| `drishti.bi.exec.max-fragments` | integer | `8` |  | server | source fragments one connector runs at once (per connector: connectors.&lt;name&gt;.max-fragments) | restart |  | admin |
| `drishti.bi.exec.breaker-failures` | integer | `5` |  | server | consecutive failures that open a connector's circuit breaker (DRS-9026 at once while open) | restart |  | admin |
| `drishti.bi.exec.breaker-open` | duration | `30s` |  | server | how long an open breaker refuses before one fragment may try again | restart |  | admin |
| `drishti.bi.exec.node` | string | empty | `DRISHTI_BI_NODE` | server | this Java node's name when several serve the site (blank: one node) | restart |  | admin |
| `drishti.bi.exec.background-share` | number | `0.25` |  | server | BI runs: the most of the slots background work (runs, dataset refresh) holds at once (docs/architecture/BI\_DESIGN.md 10.1) | restart |  | admin |
| `drishti.bi.exec.standing-share` | number | `0.25` |  | server | the most of the slots standing-query maintenance holds at once; interactive work keeps the rest | restart |  | admin |
| `drishti.bi.exec.fragments-share` | number | `0.5` |  | server | the most of a connector's max-fragments the layer builds of background runs hold at once | restart |  | admin |

#### `drishti.bi.runs`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.runs.file` | string | `./config/runs/runs.yaml` | `DRISHTI_BI_RUNS_FILE` | server | BI runs (docs/architecture/BI\_DESIGN.md); ready-made in config/runs.examples/ | restart |  | admin |

#### `drishti.bi.plan`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.plan.cache-entries` | integer | `2000` |  | server | answers cached by plan hash, security signature and data generation (0: off) | restart |  | expert |
| `drishti.bi.plan.cache-ttl` | duration | `15m` |  | server | an answer for the current state is asked again after this; as-of answers have no ttl | restart |  | expert |
| `drishti.bi.plan.cache-idle` | duration | `1h` |  | server | an answer nobody asked for this long is dropped | restart |  | expert |
| `drishti.bi.plan.guards` | string | `refuse` |  | server | refuse (the default) or warn: sums across currencies (DRS-9158), balances added across dates (DRS-9159) | restart |  | admin |

#### `drishti.bi.sql`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.sql.enabled` | boolean | `true` |  | server | false: /bi/sql answers DRS-9006 and SQL view datasets cannot be read | restart |  | admin |
| `drishti.bi.sql.max-leaves` | integer | `8` |  | server | model queries one composite statement may ask | restart |  | admin |
| `drishti.bi.sql.composite-memory` | size | `256MB` |  | server | memory of the in-memory engine a composite statement's outer query runs in | restart |  | expert |
| `drishti.bi.sql.composite-timeout` | duration | `30s` |  | server | longest the outer query of a composite statement may run | restart |  | expert |
| `drishti.bi.sql.max-rows` | integer | `100000` |  | server | most rows a composite statement answers | restart |  | expert |

#### `drishti.bi.expr`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.expr.legacy` | string | `refuse` |  | server | the old expression syntax: refuse (DRS-9100 with the DEL v2 text as the fix; the default) or warn (read with DRS-9100) | restart |  | admin |
| `drishti.bi.expr.parse-cache` | integer | `10000` |  | server | parsed expressions kept (0: off) | restart |  | expert |

#### `drishti.bi.migrate`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.bi.migrate.on-start` | boolean | `true` | `DRISHTI_BI_MIGRATE_ON_START` | server | at start: stored datasets and models into the table layer, 2.3 boards into reports; audited, the text before kept; idempotent | restart |  | admin |
| `drishti.bi.migrate.actor` | string | `system` |  | server | the name the history, the item's events and the audit log give the start-up run | restart |  | admin |

#### `drishti.layouts`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.layouts.enabled` | boolean | `true` | `DRISHTI_LAYOUTS_ENABLED` | server | also needs a role with layout (every role but viewer, unless configured) | restart |  | admin |

#### `drishti.explain`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.explain.cache-size` | integer | `2000` |  | server | answers kept; per user, page, business date and generation (masks and rights are per user) | restart |  | expert |
| `drishti.explain.cache-ttl` | duration | `60s` |  | server | how long an answer is served before it is derived again | restart |  | expert |

#### `drishti.explain.ask`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.explain.ask.enabled` | boolean | `false` | `DRISHTI_ASK_ENABLED` | server | turn on Ask about this page (also list packs in drishti.explain.ask.packs) | restart |  | admin |
| `drishti.explain.ask.model` | string | empty | `DRISHTI_ASK_MODEL` | server | the model Ask uses: a logical model of the AI layer (provider/model; a provider file in drishti.ai.dir, docs/admin/AI.md) | restart |  | admin |
| `drishti.explain.ask.packs` | list | `[]` |  | server | packs where the Ask box is on; empty = none (per-pack opt-in) | restart |  | admin |
| `drishti.explain.ask.values` | string | `labels-only` |  | server | labels-only \| shown: whether the page's rendered text (values) goes into the prompt; masked values never do | restart |  | admin |
| `drishti.explain.ask.max-question-chars` | integer | `500` |  | server | longer questions are refused (400) | restart |  | admin |
| `drishti.explain.ask.max-prompt-kb` | integer | `24` |  | server | the whole prompt; the pack guide is cut first | restart |  | admin |
| `drishti.explain.ask.max-guide-chars` | integer | `6000` |  | server | the most of the pack's guide section put in the prompt | restart |  | admin |
| `drishti.explain.ask.max-answer-tokens` | integer | `400` |  | server | the model's output cap | restart |  | admin |
| `drishti.explain.ask.max-answer-chars` | integer | `2000` |  | server | the cut applied to what comes back | restart |  | admin |
| `drishti.explain.ask.timeout` | duration | `15s` |  | server | slower than this is 504 DRS-4008 | restart |  | expert |
| `drishti.explain.ask.per-user-per-minute` | integer | `6` |  | server | questions one user may ask a minute (over it: 429 DRS-4009; a failed call still counts) | restart |  | admin |
| `drishti.explain.ask.per-user-per-day` | integer | `100` |  | server | questions one user may ask a day (over it: 429 DRS-4009) | restart |  | admin |
| `drishti.explain.ask.log-questions` | boolean | `true` |  | server | the question text goes to the access log (action ask); answers are never stored | restart |  | admin |
| `drishti.explain.ask.log-answers` | boolean | `false` |  | server | true also writes the answer text to the server log | restart |  | admin |

#### `drishti.pivot`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.pivot.enabled` | boolean | `true` | `DRISHTI_PIVOT_ENABLED` | server | the old name of the pivot engine's switch (config/engines/pivot.yaml); false: no Pivot anywhere | restart |  | admin |
| `drishti.pivot.max-records` | integer | `50000` |  | server | the most rows of a panel a pivot of it reads (the table's limit does not apply); more is refused, never cut short | restart |  | admin |

#### `drishti.tables`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.tables.root` | string | `./config/tables` | `DRISHTI_TABLES_ROOT` | server | the definitions' folder: config/tables/&lt;domain&gt;/&lt;name&gt;.table.yaml and .model.yaml | restart |  | admin |
| `drishti.tables.read-max-rows` | integer | `2000000` |  | server | the most rows one read of a virtual table stages; more is refused, never cut short | restart |  | expert |
| `drishti.tables.scan-warn-rows` | integer | `1000000` |  | server | rows above which an unfiltered side of a model's join across connectors is warned about; a model's own scan-warn-rows wins | restart |  | admin |

#### `drishti.identity`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.identity.database-url` | string | `jdbc:sqlite:./data/identity/drishti.db` | `DRISHTI_IDENTITY_DB_URL` | server | Users, roles defined in Admin → Roles, saved workspaces/monitors/settings and the audit trail live in one database (JPA). SQLite by default: a single file, nothing to install. For PostgreSQL: DRISHTI\_IDENTITY\_DB\_URL=jdbc:postgresql://db:5432/drishti DRISHTI\_IDENTITY\_DB\_USER=drishti DRISHTI\_IDENTITY\_DB\_PASSWORD=... The schema (drishti-identity: db/schema-sqlite.sql, db/schema-postgres.sql) is applied at start; no migrations. | restart |  | admin |
| `drishti.identity.database-user` | string | empty | `DRISHTI_IDENTITY_DB_USER` | server | the identity database's user (PostgreSQL) | restart |  | admin |
| `drishti.identity.database-password` | string | empty | `DRISHTI_IDENTITY_DB_PASSWORD` | server | its password, from the environment (secret) | restart |  | admin |
| `drishti.identity.refresh-seconds` | integer | `15` |  | server | servers sharing one database see each other's role and pack changes this soon | restart |  | admin |
| `drishti.identity.users-file` | string | `./data/identity/users.json` | `DRISHTI_USERS_FILE` | server | The files of releases before 1.10, imported once into an empty database and renamed \*.imported. | restart |  | admin |
| `drishti.identity.audit-file` | string | `./data/identity/audit.jsonl` | `DRISHTI_AUDIT_FILE` | server | the pre-1.10 audit file, imported into the database once | restart |  | admin |
| `drishti.identity.iterations` | integer | `240000` |  | server | PBKDF2-HMAC-SHA256 | restart |  | admin |
| `drishti.identity.min-password-length` | integer | `10` |  | server | the shortest password accepted | restart |  | admin |
| `drishti.identity.max-failed-attempts` | integer | `5` |  | server | failed sign-ins before an account locks | restart |  | admin |
| `drishti.identity.lockout` | duration | `15m` |  | server | how long a locked account stays locked | restart |  | admin |
| `drishti.identity.force-password-change-on-create` | boolean | `false` | `DRISHTI_FORCE_PW_CHANGE_ON_CREATE` | server | Nobody is forced to change a password unless configured here (an admin can still tick it per user). (secret) | restart |  | admin |
| `drishti.identity.force-password-change-on-reset` | boolean | `false` | `DRISHTI_FORCE_PW_CHANGE_ON_RESET` | server | after an administrator resets a password, the user must change it at the next sign-in (secret) | restart |  | admin |
| `drishti.identity.seed-admin` | boolean | `true` | `DRISHTI_SEED_ADMIN` | server | On first start with no users, create a development admin. Change its password at once; the console warns until you do. Turn off (DRISHTI\_SEED\_ADMIN=false) for production. | restart |  | admin |
| `drishti.identity.seed-username` | string | `drishti-dev-admin` | `DRISHTI_SEED_USERNAME` | server | With DRISHTI\_SEED\_USERNAME / DRISHTI\_SEED\_PASSWORD set (deploy/docker/compose.yaml requires both) it is the first administrator you chose, not the development admin. | restart |  | admin |
| `drishti.identity.seed-password` | string | `drishti-dev-admin123` | `DRISHTI_SEED_PASSWORD` | server | the first administrator's password; only this built-in default shows the default password warning (secret) | restart |  | admin |
| `drishti.identity.seed-roles` | list | `[admin]` |  | server | the first administrator's roles | restart |  | admin |
| `drishti.identity.seed-display-name` | string | `Drishti dev admin` |  | server | the name shown for the first administrator | restart |  | admin |

#### `drishti.rachana`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.rachana.dirs` | string | `./sutras` | `DRISHTI_SUTRAS` | server | Site Sutra directories, scanned recursively, in addition to the enabled packs' Sutras. | restart |  | admin |
| `drishti.rachana.hot-reload` | boolean | `true` |  | server | Edit a Sutra and the next view uses it; an invalid edit keeps the last good version. | restart |  | admin |
| `drishti.rachana.reload-debounce` | duration | `250ms` |  | server | quiet time after a burst of file events before one reload | restart |  | expert |
| `drishti.rachana.watch` | string | `auto` | `DRISHTI_SUTRA_WATCH` | server | auto: file events, else polling (inotify limit reached); poll: always poll (network file systems) | restart |  | admin |
| `drishti.rachana.poll-interval` | duration | `2s` |  | server | how often polling looks at the Sutra files | restart |  | expert |
| `drishti.rachana.max-expression-depth` | integer | `200` |  | server | Bounds on every Rachana-EL expression (Sutras, alert rules, search conditions): deeper or longer is DRS-2101. | restart |  | admin |
| `drishti.rachana.max-expression-length` | integer | `10000` |  | server | the longest a Rachana-EL expression may be, in characters (longer: DRS-2101) | restart |  | admin |
| `drishti.rachana.studio-save` | boolean | `false` | `DRISHTI_STUDIO_SAVE` | server | Let Sutra Studio save files (authors only). Off by default; turn on in authoring environments. | restart |  | admin |

#### `drishti.ui-kit`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.ui-kit.dir` | string | `./config/ui-kit` | `DRISHTI_UI_KIT_DIR` | server | The Drishti UI kit (docs/guides/RACHANA\_REFERENCE.md, "The Drishti UI kit"): platform UI vocabulary loaded whatever packs are enabled: fragments/ (used by any Sutra as { use: series-line@1 }), assets/ (icons, images, logos) and brand.yaml. | restart |  | admin |
| `drishti.ui-kit.site-dir` | string | `./data/ui-kit` | `DRISHTI_UI_KIT_SITE_DIR` | server | The site's overrides: brand.yaml (its keys win over the kit's) and assets/ (a file wins over the kit's of the same path). | restart |  | admin |

#### `drishti.governance`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.governance.enabled` | boolean | `true` | `DRISHTI_SUTRA_REVIEW` | server | With review on, a Studio save is a proposal; an approver (role flag approve, or an admin) makes it live. | restart |  | admin |
| `drishti.governance.four-eyes` | boolean | `true` | `DRISHTI_SUTRA_FOUR_EYES` | server | nobody approves their own proposal (when security is on) | restart |  | admin |
| `drishti.governance.dir` | string | `./data/governance` | `DRISHTI_GOVERNANCE_DIR` | server | where proposals are kept with store: file; with content (the default) the folder is imported once and left in place | restart |  | admin |

#### `drishti.engine`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engine.layout-cache-size` | integer | `10000` |  | server | effective layouts, one per (Sutra version, kind, data shape) | restart |  | expert |
| `drishti.engine.fingerprint-cache-size` | integer | `100000` |  | server | one per (entity, generation; in a cluster, caller signature): about 30 MB full | restart |  | expert |
| `drishti.engine.bind-parallelism` | integer | `0` |  | server | 0 = one binding thread per core | restart |  | expert |

#### `drishti.db`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.db.pool.size` | integer | `20` |  | server | most connections open at once; a connector's own pool-size wins (SQLite and DuckDB are embedded and not pooled) | restart |  | expert |
| `drishti.db.pool.min-idle` | integer | `2` |  | server | idle connections kept when quiet | restart |  | expert |
| `drishti.db.pool.idle-timeout` | duration | `10m` |  | server | an idle connection beyond min-idle is closed after this long | restart |  | expert |
| `drishti.db.pool.max-lifetime` | duration | `30m` |  | server | a connection is replaced after this long | restart |  | expert |
| `drishti.db.pool.connection-timeout` | duration | `10s` |  | server | a caller waits this long for a free connection, then fails | restart |  | expert |
| `drishti.db.pool.leak-threshold` | duration | `60s` |  | server | a connection borrowed longer than this is logged with the borrower's stack (0 = off) | restart |  | expert |
| `drishti.db.pool.statement-timeout` | duration | `5m` |  | server | longest one statement may run (PostgreSQL, MongoDB and Aerospike operations; 0 = none) | restart |  | expert |

#### `drishti.http`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.http.proxy.url` | string | empty | `DRISHTI_HTTP_PROXY_URL` | server | http://host:port; http and https targets both go through it (https via CONNECT) | restart |  | admin |
| `drishti.http.proxy.username` | string | empty | `DRISHTI_HTTP_PROXY_USERNAME` | server | Basic proxy authentication | restart |  | admin |
| `drishti.http.proxy.password` | string | empty | `DRISHTI_HTTP_PROXY_PASSWORD` | server | only a ${ENV} placeholder (a literal in this file is refused at start) (secret) | restart |  | admin |
| `drishti.http.proxy.no-proxy` | list | `[]` |  | server | hosts that go direct: names, .suffixes, addresses, CIDR blocks (10.0.0.0/8) | restart |  | admin |
| `drishti.http.proxy.enabled` | boolean | `true` |  | server | false switches it off without removing it | restart |  | admin |
| `drishti.http.proxy.from-environment` | boolean | `false` |  | server | true: when url is empty, honour HTTPS\_PROXY / HTTP\_PROXY / NO\_PROXY | restart |  | admin |
| `drishti.http.request-limits.enabled` | boolean | `true` |  | server | false: no body is refused for its size here (Tomcat's own limits remain) | restart |  | admin |
| `drishti.http.request-limits.rules` | list | empty |  | server | {prefix, max-mb} each: a body to a path under the prefix larger than max-mb is refused; the first rule that matches wins | restart |  | admin |

#### `drishti.builder`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.builder.max-samples` | integer | `50` |  | server | Screen Builder: the shape extractor (POST /api/v1/builder/shape). Over-limit input is refused with DRS-5003. documents per request | restart |  | admin |
| `drishti.builder.max-file-mb` | integer | `5` |  | server | largest single document | restart |  | expert |
| `drishti.builder.max-total-mb` | integer | `25` |  | server | largest request | restart |  | expert |
| `drishti.builder.max-depth` | integer | `64` |  | server | deepest nesting of one document | restart |  | admin |
| `drishti.builder.enum-max-distinct` | integer | `12` |  | server | text with at most this many distinct values (seen at least enum-min-seen times) is an enum | restart |  | admin |
| `drishti.builder.enum-min-seen` | integer | `3` |  | server | a text field counts as an enum only when seen at least this many times (and within enum-max-distinct values) | restart |  | admin |
| `drishti.builder.examples` | integer | `3` |  | server | example values per path in the report | restart |  | admin |
| `drishti.builder.rare-below` | number | `0.5` |  | server | fields present in fewer than this share of their records are listed as rare | restart |  | admin |
| `drishti.builder.prune-share` | number | `0.5` |  | server | a panel or strip figure empty or failing for more than this share of the samples is dropped or demoted | restart |  | admin |
| `drishti.builder.strip-max` | integer | `6` |  | server | figures in a drafted strip | restart |  | admin |
| `drishti.builder.max-panels` | integer | `16` |  | server | panels in the drafted main column | restart |  | admin |
| `drishti.builder.max-side-panels` | integer | `4` |  | server | panels in the drafted right column (besides the links panel) | restart |  | admin |
| `drishti.builder.line-min-points` | integer | `5` |  | server | dated rows shorter than this are drafted as a ladder, not a line | restart |  | admin |
| `drishti.builder.max-alternatives` | integer | `2` |  | server | runner-up kinds kept with each drafted panel | restart |  | expert |
| `drishti.builder.limit-names` | list | `[limit, max, cap, threshold, budget, capacity]` |  | server | names that hold a limit beside a measure (a gauge's maximum) | restart |  | admin |
| `drishti.builder.file-binding` | boolean | `false` |  | server | development servers only: a design may be bound to a file in your own folder of dev-dir (save writes it, IDE edits come back); never makes anything live | restart |  | admin |
| `drishti.builder.dev-dir` | string | `./data/dev-sutras` |  | server | where bound files live, one folder per user; must be OUTSIDE drishti.rachana.dirs (going live is always propose + approve) | restart |  | admin |
| `drishti.builder.sample-max-items` | integer | `50` |  | server | synthetic sampler: longest array it generates (a schema asking for more is clamped, with a problem) | restart |  | admin |
| `drishti.builder.sample-max-string` | integer | `1000` |  | server | ... longest string | restart |  | admin |
| `drishti.builder.sample-max-depth` | integer | `8` |  | server | ... deepest nesting | restart |  | admin |
| `drishti.builder.sample-max-kb` | integer | `256` |  | server | ... most kilobytes in one generated document | restart |  | admin |
| `drishti.builder.fraction-names` | list | `[utilisation, utilization, usage, used, ratio, share, coverage, fill, pct, pe...` |  | server | words in a 0..1 number's name that make it a gauge (of 1) | restart |  | admin |
| `drishti.builder.code-names` | list | `[year, version, seq, sequence, no, num, number, code, zip, postcode]` |  | server | words in a whole-number field's name that make it a code (shown as a label, never summed or formatted as an amount) | restart |  | admin |
| `drishti.builder.designs.store` | string | `content` |  | server | content (the content store, config/store/store.yaml: every node of a cluster sees them; docs/architecture/DATA\_ARCHITECTURE.md), or, one node only: file (dir below) or jpa (the identity database) | restart |  | admin |
| `drishti.builder.designs.dir` | string | `./data/designs` |  | server | the old file store: with store content, what is here is imported once at start and the folder renamed designs-imported-&lt;time&gt; | restart |  | admin |
| `drishti.builder.designs.max-per-user` | integer | `50` |  | server | named designs one user keeps (scratch ones: max-scratch) | restart |  | admin |
| `drishti.builder.designs.max-samples` | integer | `50` |  | server | samples in one design | restart |  | admin |
| `drishti.builder.designs.max-mb` | integer | `25` |  | server | megabytes of samples, Sutra, notes and tests in one design | restart |  | expert |
| `drishti.builder.designs.max-user-mb` | integer | `250` |  | server | megabytes of samples, Sutra, notes and tests across one user's designs | restart |  | expert |
| `drishti.builder.designs.max-sutra-kb` | integer | `1024` |  | server | largest Sutra text of one design (DRS-5005 beyond it; counted in max-mb and max-user-mb) | restart |  | admin |
| `drishti.builder.designs.max-notes-kb` | integer | `256` |  | server | largest notes text | restart |  | admin |
| `drishti.builder.designs.max-tests-kb` | integer | `1024` |  | server | largest tests | restart |  | admin |
| `drishti.builder.designs.max-scratch` | integer | `10` |  | server | scratch (unnamed) designs one user keeps; the oldest is deleted to make room; they do not count toward max-per-user | restart |  | admin |
| `drishti.builder.designs.scratch-ttl` | duration | `1d` |  | server | an unnamed design is deleted this long after it was last touched | restart |  | admin |
| `drishti.builder.designs.named-ttl` | duration | `90d` |  | server | a named design, likewise | restart |  | admin |
| `drishti.builder.designs.warn-after` | duration | `75d` |  | server | a named design untouched this long is listed with an expiry warning | restart |  | admin |
| `drishti.builder.designs.sweep-interval` | duration | `1h` |  | server | how often expired designs are deleted | restart |  | expert |
| `drishti.builder.designs.max-ops` | integer | `100` |  | server | steps of the operation log a design keeps for undo and redo | restart |  | admin |

#### `drishti.loads`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.loads.dir` | string | `./data/loads` | `DRISHTI_LOADS_DIR` | server | history per pack, and the administrators' expectation overrides (config/&lt;pack&gt;.yaml) | restart |  | admin |
| `drishti.loads.keep` | integer | `2000` |  | server | loads kept per pack | restart |  | admin |
| `drishti.loads.scheduler` | boolean | `true` |  | server | false: no late-data check (the API and the page still work) | restart |  | admin |
| `drishti.loads.check-interval` | duration | `60s` |  | server | how often expectations are checked for late data | restart |  | expert |
| `drishti.loads.verify-limit` | integer | `1000` |  | server | most entities counted when a load is verified | restart |  | admin |
| `drishti.loads.verify-timeout` | duration | `20s` |  | server | how long the verify, alerts and smoke steps may each wait | restart |  | expert |
| `drishti.loads.lookback-days` | integer | `3` |  | server | business days before today still checked for missing data | restart |  | admin |
| `drishti.loads.default-notify-roles` | list | `[admin]` |  | server | told when a pack names no roles or users | restart |  | admin |
| `drishti.loads.smoke-max` | integer | `20` |  | server | most sample entities a pack may ask to be opened | restart |  | admin |

#### `drishti.under-construction`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.under-construction.file` | string | `./config/under-construction.yaml` | `DRISHTI_UNDER_CONSTRUCTION_FILE` | server | absent: under-construction.yaml beside the packs folder, else nothing registered; none: nothing | restart |  | admin |

#### `drishti.packs`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.packs.dir` | string | `./config/packs` | `DRISHTI_PACKS_DIR` | server | Domain packs (config/packs/&lt;name&gt;/pack.yaml): Sutras, mnemonics, links, badges, roles, vocabulary, samples. Site settings below always override what a pack contributes. | restart |  | admin |
| `drishti.packs.extra-dirs` | string | empty | `DRISHTI_PACKS_EXTRA_DIRS` | server | Further pack folders, comma-separated, looked in after dir: examples/packs holds the retired example packs (finance, genomics, logistics, retail-banking, climate-risk, operational-risk, liquidity-risk); name one in enabled to load it. | restart |  | admin |
| `drishti.packs.overlay` | string | `./data/packs/added.yaml` | `DRISHTI_PACKS_OVERLAY` | server | Packs loaded from Admin → Packs are listed here (drishti.packs.added) and read at every start; see spring.config.import. | restart |  | admin |
| `drishti.packs.installed-dir` | string | `./data/packs/installed` | `DRISHTI_PACKS_INSTALLED` | server | Packs installed from a signed registry (Admin → Packs → From the registry); looked in before dir, so they win. | restart |  | admin |
| `drishti.packs.settings-dir` | string | `./data/packs/settings` | `DRISHTI_PACKS_SETTINGS` | server | The administrator's data-source overrides, one file per pack (Admin → Packs → Data source); redeploying a pack keeps them. | restart |  | admin |
| `drishti.packs.enabled` | string | `default` | `DRISHTI_PACKS` | server | default: the edition's packs (drishti.edition), else config/editions/default-packs.yaml | restart |  | admin |
| `drishti.packs.samples` | string | `visible` | `DRISHTI_PACKS_SAMPLES` | server | Sample packs (sample: true in pack.yaml) are demonstrations. visible: everyone sees them; developers: only users with the author or admin power; hidden: nobody, they are not loaded and their connectors do not start. Admin -&gt; Packs ("Hide samples from business users") saves a choice that overrides this. Use developers once real packs exist. | restart |  | admin |
| `drishti.packs.samples-file` | string | `./data/packs/samples-mode` | `DRISHTI_PACKS_SAMPLES_FILE` | server | where the Admin -&gt; Packs choice for sample packs is saved (one word); delete it to let drishti.packs.samples decide again | restart |  | admin |
| `drishti.packs.watch` | string | `auto` | `DRISHTI_PACKS_WATCH` | server | The packs folder is watched: a new pack folder is loaded, an edited one re-checked and swapped in, a deleted one unloaded, with no restart. auto: file events with polling as the safety net; poll: look every `poll`; off: only Admin -&gt; Packs acts. | live |  | admin |
| `drishti.packs.poll` | duration | `5s` |  | server | how often the folders are looked at (also the safety net beside file events) | restart |  | expert |
| `drishti.packs.reload-debounce` | duration | `750ms` |  | server | a burst of changes is acted on once it has stopped for this long | restart |  | expert |
| `drishti.packs.default-for-users` | string | empty | `DRISHTI_DEFAULT_PACKS` | server | Packs new users get until an admin says otherwise (empty: every installed pack). | restart |  | admin |

#### `drishti.packs.registry`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.packs.registry.url` | string | empty | `DRISHTI_PACK_REGISTRY` | server | a folder, file: or https: URL with index.json; empty: no registry | restart |  | admin |
| `drishti.packs.registry.trusted-keys` | map | `{}` |  | server | publisher -&gt; Ed25519 public key (tools/packreg keygen prints it) | restart |  | admin |
| `drishti.packs.registry.max-archive-mb` | integer | `50` |  | server | largest pack archive the registry accepts | restart |  | expert |
| `drishti.packs.registry.max-unpacked-mb` | integer | `200` |  | server | largest an archive may unpack to | restart |  | expert |

#### `drishti.packs.deploy`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.packs.deploy.max-archive-mb` | integer | `50` |  | server | larger uploads are refused with 413 | restart |  | expert |
| `drishti.packs.deploy.max-unpacked-mb` | integer | `200` |  | server | largest a deployed archive may unpack to (a zip bomb stops here) | restart |  | expert |
| `drishti.packs.deploy.max-files` | integer | `10000` |  | server | most files a deployed archive may hold | restart |  | admin |
| `drishti.packs.deploy.keep-versions` | integer | `5` |  | server | previous versions kept per pack for a rollback | restart |  | admin |
| `drishti.packs.deploy.require-signature` | boolean | `false` |  | server | true: only archives signed by a publisher in registry.trusted-keys | restart |  | admin |
| `drishti.packs.deploy.require-manifest` | boolean | `true` |  | server | an archive needs MANIFEST.json (drishti.py pack bundle writes it) | restart |  | admin |
| `drishti.packs.deploy.staging-minutes` | integer | `30` |  | server | a verified upload waits this long for its confirmation | restart |  | admin |
| `drishti.packs.deploy.history-file` | string | `./data/packs/deploy-history.jsonl` | `DRISHTI_PACKS_DEPLOY_HISTORY` | server | the history of archive deployments and rollbacks | restart |  | admin |
| `drishti.packs.deploy.probe-dates` | integer | `3` |  | server | business dates listed per kind by Test connection | restart |  | admin |
| `drishti.packs.deploy.probe-timeout-seconds` | integer | `30` |  | server | Test connection: how long to wait for one source to list its business dates | restart |  | expert |

#### `drishti.commands`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.commands.suggest-limit` | integer | `25` | `DRISHTI_SUGGEST_LIMIT` | server | Mnemonics come from the enabled packs; add or override site mnemonics under `mnemonics:`. entries in the command line's dropdown (the list scrolls) | restart |  | admin |
| `drishti.commands.suggest-budget` | duration | `30ms` |  | server | type-ahead answers with what the sources returned by then | restart |  | admin |
| `drishti.commands.recent-size` | integer | `20` |  | server | recently opened entities remembered per user | restart |  | admin |

#### `drishti.data-view`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.data-view.enabled` | boolean | `true` |  | server | false: /api/v1/data refuses every call (DRS-5002) and the console hides the view | restart |  | admin |
| `drishti.data-view.internal-only` | list | `[economy, economic-forecast, fiscal-position, quote, news, price-history]` |  | server | datasets whose sources' terms restrict reuse (IMF, Yahoo, the news headlines; tools/packgen/common/data\_retrieval.yaml) | restart |  | admin |
| `drishti.data-view.internal-only-download` | boolean | `true` |  | server | false: datasets marked internal only are listed, marked and refused (an edition's internal-only-data: off does the same) | restart |  | admin |
| `drishti.data-view.count-budget` | duration | `PT60S` |  | server | the longest one row count reads before it answers with "at least" | restart |  | admin |
| `drishti.data-view.batch-rows` | integer | `8192` |  | server | rows a column read hands over at a time while a download streams | restart |  | expert |
| `drishti.data-view.list-limit` | integer | `200000` |  | server | most entities read from a source that cannot read a day in one pass (it lists them, then reads 500 at a time) | restart |  | admin |
| `drishti.data-view.read-budget` | duration | `PT30S` |  | server | one listing, or one chunk of 500 entities, from such a source | restart |  | admin |

#### `drishti.datamap`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.datamap.max-rows` | integer | `20000` |  | server | rows of one kind read to count it and check its joins | restart |  | expert |
| `drishti.datamap.ttl` | duration | `PT15M` |  | server | a built map is read again after this | restart |  | admin |
| `drishti.datamap.read-budget` | duration | `PT30S` |  | server | one listing or column read | restart |  | admin |
| `drishti.datamap.threads` | integer | `4` |  | server | kinds read at once | restart |  | expert |

#### `drishti.live`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.live.frame` | duration | `50ms` |  | server | ticks within one frame coalesce (latest wins) | restart |  | admin |
| `drishti.live.heartbeat` | duration | `15s` |  | server | SSE keep-alive comment | restart |  | admin |
| `drishti.live.max-streams` | integer | `20000` |  | server | live streams (views and monitors) the server keeps open at once; further requests are refused | restart |  | admin |
| `drishti.live.window` | duration | `30s` |  | server | rolling window for the p99 shown in the top bar | restart |  | admin |

#### `drishti.graph`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.graph.link-budget` | duration | `40ms` |  | server | linked entities slower than this show as pending | restart |  | admin |

#### `springdoc`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `springdoc.api-docs.path` | string | `/api/docs` |  | server | the OpenAPI description | restart |  | admin |
| `springdoc.api-docs.enabled` | boolean | `true` | `DRISHTI_API_DOCS` | server | set explicitly, so a start does not warn that they are on by default | restart |  | admin |
| `springdoc.swagger-ui.path` | string | `/api/docs/ui` |  | server | the OpenAPI UI | restart |  | admin |
| `springdoc.swagger-ui.enabled` | boolean | `true` | `DRISHTI_API_DOCS` | server | serve the OpenAPI UI (false: the description only) | restart |  | admin |

### Compute engines: `config/engines/*.yaml`

One file per compute engine (VaR, exposure, sensitivities, FRTB-SA, SIMM, SA-CCR, FVA, the two cubes, the in-memory lake, the data inbox), `config/engines/<engine>.yaml` (`DRISHTI_ENGINES_DIR`), imported by `application.yaml` after the jar's own copy of the same file: its switch (`drishti.engines.<engine>.enabled`) and every setting of that engine, under the keys they always had. `application.local.yaml`, the environment and the command line still win over a file. Admin → Engines and `drishti.py server engines` write only the switch and `changed`; every other key is edited in the file ([COMPUTE_ENGINES.md](../architecture/COMPUTE_ENGINES.md), [OPERATIONS.md](OPERATIONS.md#21-compute-engines-on-or-off)). The old switch names are listed last: they still work, with a warning at start (`DRS-1024`).

#### `drishti.engines.var`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.var.enabled` | boolean | `true` | `DRISHTI_VAR_ENABLED` | server (each node its own) | run the VaR engine on this node: /api/v1/var, VAR, SMAT, PLAY VAR, regulatory VaR (live) | live |  | admin |
| `drishti.engines.var.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.var`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.confidence` | number | `0.99` | `DRISHTI_VAR_CONFIDENCE` | server (each node its own) | VaR's confidence: 0.99 or 0.95 (refused at start outside (0.5, 1)) | restart |  | everyday |
| `drishti.var.es-confidence` | number | `0.975` | `DRISHTI_VAR_ES_CONFIDENCE` | server (each node its own) | expected shortfall's | restart |  | everyday |
| `drishti.var.confidences` | list | `[0.95, 0.975, 0.99, 0.995]` |  | server (each node its own) | the VaR confidences offered on the VaR page, in commands and the API (the default is always offered) | restart |  | everyday |
| `drishti.var.es-confidences` | list | `[0.95, 0.975, 0.99]` |  | server (each node its own) | the ES confidences offered the same way (es-confidence is always offered) | restart |  | everyday |
| `drishti.var.ten-day-method` | string | `sqrt10` | `DRISHTI_VAR_TEN_DAY_METHOD` | server (each node its own) | 10-day VaR when the method is not named: sqrt10 (1-day x sqrt 10) or sum (10 consecutive days) | restart |  | admin |
| `drishti.var.scenario-window` | integer | `250` | `DRISHTI_VAR_SCENARIO_WINDOW` | server (each node its own) | the newest N scenarios of each date's set (250: one year, the Basel minimum; at least 20), or all (the whole set, 509 in the demo) | restart |  | everyday |
| `drishti.var.rank-rule` | string | `kth-worst` |  | server (each node its own) | kth-worst (k = ceil(N (1 - a)): the 6th worst of 509) or interpolated | restart |  | admin |
| `drishti.var.component-window` | integer | `5` |  | server (each node its own) | component VaR averages the scenarios ranked k-2 .. k+2, scaled to add up to the VaR | restart |  | admin |
| `drishti.var.threads` | integer | `0` | `DRISHTI_VAR_THREADS` | server (each node its own) | cores the vector sums use (0: every core) | restart |  | expert |
| `drishti.var.memory-budget-mb` | integer | `0` | `DRISHTI_VAR_MEMORY_MB` | server (each node its own) | heap tier only: most memory one date's vectors take (0: a quarter of the heap); over it: 503 DRS-5204. The mapped tier is bounded by the disk (hard) and the page cache (advisory) | restart |  | expert |
| `drishti.var.hot-dates` | integer | `10` | `DRISHTI_VAR_HOT_DATES` | server (each node its own) | business dates kept open: the least recently used leaves first; the date just used, the newest business date and the dates pinned in memory always stay | restart |  | admin |
| `drishti.var.refusal-ttl-seconds` | integer | `300` |  | server (each node its own) | a date refused for room (capacity, disk) is remembered this long: asked again, 503 at once with Retry-After, no lake read; forgotten sooner when room is freed (0: not remembered) | restart |  | admin |
| `drishti.var.open-heap-mb` | integer | `0` | `DRISHTI_VAR_OPEN_HEAP_MB` | server (each node its own) | the byte safety net over every open book's heap (0: automatic, 0.4 x heap - 512 MB - load-threads x 1.6 GB, at least a tenth of the heap); over it the least recently used dates' currency, then stress books, then whole dates leave | restart |  | expert |
| `drishti.var.fiscal-year-end` | string | empty | `DRISHTI_FISCAL_YEAR_END` | server (each node its own) | the bank's fiscal year-end, MM-DD (for example 10-31); blank: none (the fiscal-year-end pin is off) | restart |  | admin |
| `drishti.var.view-cache-mb` | integer | `64` |  | server (each node its own) | most memory the viewers' views (bitsets per date, version, currency, role set) take; idle 1 h, released with the date | restart |  | expert |
| `drishti.var.load-threads` | integer | `2` |  | server (each node its own) | threads that load dates (and shard books, currencies) from the lake: their own pool, never the common pool | restart |  | expert |
| `drishti.var.version-check` | duration | `30s` | `DRISHTI_VAR_VERSION_CHECK` | server (each node its own) | an open date compares its lake version this often on a request; moved: reloaded in the background (0: off) | restart |  | admin |
| `drishti.var.history-days` | integer | `250` |  | server (each node its own) | history and backtest window | restart |  | admin |
| `drishti.var.component-scale-to-var` | boolean | `true` |  | server (each node its own) | components scaled to add up to the VaR exactly (false: to the smoothed VaR) | restart |  | admin |
| `drishti.var.parallel-rows` | integer | `4096` |  | server (each node its own) | the fewest rows a sum splits across threads | restart |  | expert |
| `drishti.var.rows-per-chunk` | integer | `65536` |  | server (each node its own) | rows per chunk of the in-memory vector store | restart |  | expert |
| `drishti.var.histogram-bins` | integer | `42` |  | server (each node its own) | bins of the P&amp;L distribution sent to the screen | restart |  | admin |
| `drishti.var.max-parts` | integer | `5000` |  | server (each node its own) | most rows a contribution table lists (more: the largest by gross size, and said); totals are over all | restart |  | admin |
| `drishti.var.path-page` | integer | `60` |  | server (each node its own) | rows of the ranked P&amp;L path per page | restart |  | admin |
| `drishti.var.currencies` | string | `USD,EUR,CAD,JPY,GBP` | `DRISHTI_VAR_CURRENCIES` | server (each node its own) | reporting currencies built ahead after the USD book (background, own hot files; USD always); others on first ask | restart |  | admin |
| `drishti.var.currency-retention-dates` | integer | `5` | `DRISHTI_VAR_CURRENCY_RETENTION_DATES` | server (each node its own) | newest business dates whose listed currencies are built ahead and kept on disk | restart |  | admin |
| `drishti.var.root-name` | string | empty | `DRISHTI_VAR_ROOT_NAME` | server (each node its own) | the top of the VaR tree (every hierarchy's root node); blank keeps the hierarchy data's NAME | restart |  | admin |
| `drishti.var.access` | map | `{}` |  | server (each node its own) | per role, per node: positions \| aggregate (drishti.var.access.&lt;role&gt;\[MGMT~DESK-RATES]=aggregate) | restart |  | admin |

#### `drishti.var.stress`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.stress.enabled` | boolean | `true` | `DRISHTI_VAR_STRESS_ENABLED` | server (each node its own) | offer Normal / Stressed (off: a stressed request is refused; the stress set's rows are still kept apart) | restart |  | admin |
| `drishti.var.stress.id-prefix` | string | `HS-STRESS-` |  | server (each node its own) | a scenario set is the stress set when its SET\_TYPE is STRESS, or (no SET\_TYPE) its id starts with this | restart |  | admin |
| `drishti.var.stress.average-weeks` | integer | `12` |  | server (each node its own) | the stressed VaR average over the official records: this many weeks to the date | restart |  | admin |

#### `drishti.var.stress-window`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.stress-window.enabled` | boolean | `true` | `DRISHTI_VAR_STRESS_WINDOW_ENABLED` | server (each node its own) | the tool and its API (off: refused in words) | restart |  | admin |
| `drishti.var.stress-window.kind` | string | `pnl-history` |  | server (each node its own) | the kind of the long-history P&amp;L rows (one per hierarchy node and date) | restart |  | admin |
| `drishti.var.stress-window.length` | integer | `250` |  | server (each node its own) | the days of a window (about 12 months); a request may ask for another (at least 20) | restart |  | admin |
| `drishti.var.stress-window.top` | integer | `5` |  | server (each node its own) | the candidate windows listed, none overlapping another | restart |  | admin |
| `drishti.var.stress-window.alert-threshold` | number | `0.05` |  | server (each node its own) | alert when the worst window's stressed VaR exceeds the current window's by more than this (5%) | restart |  | admin |

#### `drishti.var.pinned`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.pinned.month-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | each month's last business day (for example back: 13) | restart |  | expert |
| `drishti.var.pinned.quarter-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | March, June, September, December (for example back: 8) | restart |  | expert |
| `drishti.var.pinned.year-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | December (for example back: 7) | restart |  | expert |
| `drishti.var.pinned.fiscal-year-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | the fiscal year's last business day (needs fiscal-year-end) | restart |  | expert |
| `drishti.var.pinned.stress-window` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | 1: the first and last dates of the stress window in use (known once stressed VaR is asked) | restart |  | expert |
| `drishti.var.pinned.dates` | list | `[]` |  | server (each node its own) | any dates the bank names (yyyy-MM-dd: a crisis day, an audit date) | restart |  | expert |
| `drishti.var.pinned.dates-keep` | string | `disk` |  | server (each node its own) | how the named dates are kept: disk or memory | restart |  | expert |

#### `drishti.var.read`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.read.threads` | integer | `0` | `DRISHTI_VAR_READ_THREADS` | server (each node its own) | files (or runs of row groups) decoded at once for one load, delivered in file order (0: half the cores, at most 8; 1: on the load's own thread) | restart |  | expert |
| `drishti.var.read.buffer-mb` | integer | `64` |  | server (each node its own) | most decoded rows waiting for one load, in MB (never above a thirty-second of the heap) | restart |  | expert |

#### `drishti.var.smat`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.smat.max-days` | integer | `60` | `DRISHTI_VAR_SMAT_MAX_DAYS` | server (each node its own) | the most business dates a scenario matrix (SMAT &lt;id&gt; N, GET /api/v1/var/matrix) reads | restart |  | admin |

#### `drishti.var.playground`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.playground.enabled` | boolean | `true` | `DRISHTI_VAR_PLAYGROUND_ENABLED` | server (each node its own) | the screen, the command and the export | restart |  | admin |
| `drishti.var.playground.max-positions` | integer | `500` |  | server (each node its own) | positions the screen shows as their own columns (the largest by \|component VaR\|; the rest in Rest of scope); at most 500, above is refused at start | restart |  | admin |
| `drishti.var.playground.incremental-positions` | integer | `50` |  | server (each node its own) | positions that get incremental VaR (the largest by \|component VaR\|): each needs its own re-ranked column | restart |  | admin |
| `drishti.var.playground.max-ids` | integer | `50` |  | server (each node its own) | ids PLAY VAR &lt;id&gt; &lt;id&gt; ... takes (give a scope for more) | restart |  | admin |
| `drishti.var.playground.template` | string | `""` |  | server (each node its own) | a bank's own workbook template: a folder with the built-in one's files and style names (blank: the built-in one) | restart |  | admin |
| `drishti.var.playground.export-choices` | list | `[100, 500, 1000, 10000, 0]` |  | server (each node its own) | the export's drop-down: positions as their own columns (0: All); the rest in Rest of scope | restart |  | admin |
| `drishti.var.playground.export-default` | integer | `500` |  | server (each node its own) | the choice selected first | restart |  | admin |
| `drishti.var.playground.export-max-cells` | integer | `20000000` | `DRISHTI_VAR_PLAYGROUND_EXPORT_MAX_CELLS` | server (each node its own) | the most cells one export may hold (about 19,000 positions at 509 scenarios, some 160 MB of server memory while it is written); larger is refused in words | restart |  | admin |

#### `drishti.var.explain`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.explain.enabled` | boolean | `true` | `DRISHTI_VAR_EXPLAIN_ENABLED` | server (each node its own) | the tab, the command, the API and the workbook | restart |  | admin |
| `drishti.var.explain.order` | list | `[restatement, hierarchy, trades-out, window, market, trades-in]` |  | server (each node its own) | the causes in sequence, each a full VaR state; restatement and trades-out before market, trades-in after, window next to market | restart |  | admin |
| `drishti.var.explain.window-lines` | string | `two` |  | server (each node its own) | two (oldest scenario dropped, newest added) or one (one window-roll line) | restart |  | admin |
| `drishti.var.explain.split-amendments` | boolean | `true` |  | server (each node its own) | amended trades as their own steps when the feed marks them (TRADE\_ACTION or TRADE\_STATUS AMEND/AMENDED) | restart |  | admin |
| `drishti.var.explain.top` | integer | `10` |  | server (each node its own) | contributors listed per step before "the others and the interaction" | restart |  | admin |
| `drishti.var.explain.drivers-max` | integer | `2000` |  | server (each node its own) | most trades of one step whose incremental VaR is computed (the rest are in "the others") | restart |  | admin |
| `drishti.var.explain.cache-minutes` | integer | `10` |  | server (each node its own) | answers kept this long, never stored | restart |  | expert |
| `drishti.var.explain.cache-mb` | integer | `32` |  | server (each node its own) | most memory the kept answers take (weighed in bytes) | restart |  | expert |
| `drishti.var.explain.matrix-max` | integer | `60` |  | server (each node its own) | most child nodes the Matrix view explains | restart |  | admin |

#### `drishti.var.parametric`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.parametric.enabled` | boolean | `true` | `DRISHTI_VAR_PARAMETRIC_ENABLED` | server (each node its own) | the parametric block on the VaR answers, the PVAR alias, the playground's Parametric tab and pvar(); off: none of them (asked: refused in words) | restart |  | admin |
| `drishti.var.parametric.weighting` | string | `equal` |  | server (each node its own) | equal (1/N, sigma with N - 1) or ewma (RiskMetrics, lambda below) | restart |  | admin |
| `drishti.var.parametric.lambda` | number | `0.94` |  | server (each node its own) | EWMA's decay, in (0.5, 1); read only with ewma | restart |  | admin |
| `drishti.var.parametric.horizon-rule` | string | `lens` |  | server (each node its own) | lens (the lens's own vector: sqrt10 scales mu and sigma by root 10, sum reads the 10-day windows) or square-root-of-time (mu x h, sigma x root h from the 1-day vector) | restart |  | admin |
| `drishti.var.parametric.curve-points` | integer | `121` |  | server (each node its own) | points of the normal curve the P&amp;L path answer carries (21 to 1001) | restart |  | admin |
| `drishti.var.parametric.matrix-max-parts` | integer | `60` |  | server (each node its own) | most parts of a correlation matrix (the largest by component VaR beyond, said truncated) | restart |  | admin |
| `drishti.var.parametric.show-curve` | boolean | `true` |  | server (each node its own) | the Normal curve toggle starts on (each person's choice is then remembered) | restart |  | admin |

#### `drishti.var.intraday`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.intraday.kafka.window` | duration | `5m` | `DRISHTI_VAR_INTRADAY_KAFKA_WINDOW` | server (each node its own) | everything received in a window: one intraday batch in the lake, applied | restart |  | expert |
| `drishti.var.intraday.kafka.max-trades` | integer | `500` | `DRISHTI_VAR_INTRADAY_KAFKA_MAX_TRADES` | server (each node its own) | a window applied earlier once this many trades wait | restart |  | expert |

#### `drishti.var.sharding`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.sharding.release-after-seconds` | integer | `900` |  | server (each node its own) | a shard book of a date nobody has open (outside the newest hot-dates), unread this long, is released | restart |  | expert |

#### `drishti.var.tier`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.tier.mode` | string | `mapped` | `DRISHTI_VAR_TIER` | server (each node its own) | mapped: each date a float32 hot file, memory-mapped (heap: aggregates, columns, links); heap: float64 rows on the heap | restart |  | admin |
| `drishti.var.tier.dir` | string | `./data/var/hot` | `DRISHTI_VAR_HOT_DIR` | server (each node its own) | one folder per business date; never under the repository | restart |  | admin |
| `drishti.var.tier.disk-dates` | integer | `60` |  | server (each node its own) | hot files kept on disk (least recently used beyond are deleted); a date not open maps its file again, no lake read | restart |  | admin |
| `drishti.var.tier.max-disk-gb` | integer | `0` | `DRISHTI_VAR_MAX_DISK_GB` | server (each node its own) | most disk all hot files take (dates, currencies, shard books); 0: half the disk; least recently used beyond go | restart |  | admin |
| `drishti.var.tier.keep-on-shutdown` | boolean | `false` | `DRISHTI_VAR_KEEP_HOT_FILES` | server (each node its own) | false: deleted at shutdown (and any left over at start); the server images set true (PO-10) | restart |  | admin |
| `drishti.var.tier.close-grace-seconds` | integer | `60` |  | server (each node its own) | an evicted date stays mapped this long for the requests still reading it | restart |  | admin |

#### `drishti.var.kinds`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.kinds.vector` | string | `pnl-vector` |  | server (each node its own) | the kind of the stored P&amp;L vectors | restart |  | admin |
| `drishti.var.kinds.position` | string | `position` |  | server (each node its own) | the kind the engine's derived positions are served as (the var-positions connector) | restart |  | admin |
| `drishti.var.kinds.hierarchy` | string | `risk-hierarchy` |  | server (each node its own) | the kind of the risk hierarchies | restart |  | admin |
| `drishti.var.kinds.scenario-set` | string | `scenario-set` |  | server (each node its own) | the kind of the scenario sets (the dates of the historical scenarios) | restart |  | admin |
| `drishti.var.kinds.portfolio` | string | `portfolio` |  | server (each node its own) | the kind of the stored portfolios | restart |  | admin |
| `drishti.var.kinds.scenario-pv` | string | `scenario-pv` |  | server (each node its own) | trades sent as scenario PVs in their own currency, converted to USD P&amp;L at load (beside or instead of vectors) | restart |  | admin |
| `drishti.var.kinds.fx-scenario` | string | `fx-scenario` |  | server (each node its own) | each currency's rate under each scenario, when the batch supplies it (USD per unit) | restart |  | admin |
| `drishti.var.kinds.fx-spot` | string | `fx-spot` |  | server (each node its own) | the FX history scenario rates are derived from when not supplied (the market-data pack's) | restart |  | admin |
| `drishti.var.kinds.limit` | string | `var-limit` |  | server (each node its own) | the nodes' VaR limits by hierarchy and node id (USD), for a hierarchy whose limits: names it | restart |  | admin |
| `drishti.var.kinds.valuation` | string | `trade-valuation` |  | server (each node its own) | the trade valuations a VaR by a trade column the vectors do not carry joins by trade id (by=attribute:TRADER\_ID) | restart |  | admin |

#### `drishti.var.positions`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.positions.live-statuses` | list | `[NEW, VALIDATED, AMENDED]` |  | server (each node its own) | trade statuses that count on their date (a row with no status counts) | restart |  | admin |
| `drishti.var.positions.rules` | list | `[]` |  | server (each node its own) | first match wins: {name, groups: \[PRODUCT\_GROUP...], types: \[PRODUCT\_TYPE...], key: \[SECURITY\_ID, ISIN...]}; none: one position per trade | restart |  | admin |

#### `drishti.var.official`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.official.enabled` | boolean | `true` | `DRISHTI_VAR_OFFICIAL` | server (each node its own) | capture every node of every hierarchy when a date's inputs are complete (after the data-load signal) | restart |  | admin |
| `drishti.var.official.publish` | boolean | `true` |  | server (each node its own) | also append them to &lt;root&gt;/risk-results/var-official (append-only) for other systems | restart |  | admin |
| `drishti.var.official.root` | string | `./data/delta` | `DRISHTI_DELTA_ROOT` | server (each node its own) | a folder or s3://bucket/prefix | restart |  | admin |
| `drishti.var.official.format` | string | `delta` | `DRISHTI_VAR_OFFICIAL_FORMAT` | server (each node its own) | delta or iceberg; BI's var-results connector follows it (generated as plugin iceberg) | restart |  | admin |
| `drishti.var.official.storage` | map | `{}` |  | server (each node its own) | the s3.\* settings for an s3:// root (s3.region, s3.endpoint, keys; none: the default credential chain) | restart |  | admin |
| `drishti.var.official.retention-years` | integer | `7` |  | server (each node its own) | the published table keeps its log and removed files (time travel) this long | restart |  | admin |
| `drishti.var.official.on-start` | boolean | `true` |  | server (each node its own) | at start, capture the newest date when it has no official record yet | restart |  | admin |
| `drishti.var.official.currencies` | list | `[USD]` |  | server (each node its own) | reporting currencies captured officially (USD always, and the only one published); e.g. \[USD, EUR] | restart |  | admin |
| `drishti.var.official.jsonl` | boolean | `true` |  | server (each node its own) | also write each published date as JSON Lines: &lt;root&gt;/risk-results/jsonl/var-official/&lt;date&gt;/ | restart |  | admin |

#### `drishti.regulatory`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.regulatory.fiscal-year-end` | string | `10-31` | `DRISHTI_FISCAL_YEAR_END` | server (each node its own) | the last day of the fiscal year, MM-DD: FY2026 is 2025-11-01 to 2026-10-31 with 10-31 | restart |  | admin |
| `drishti.regulatory.pnl-kind` | string | `desk-pnl` |  | server (each node its own) | the kind with each desk's actual and hypothetical P&amp;L per business date (the backtest's input) | restart |  | admin |
| `drishti.regulatory.average-days` | integer | `60` |  | server (each node its own) | the business days of the VaR average the capital formula takes | restart |  | admin |
| `drishti.regulatory.reports-dir` | string | `./config/reports` | `DRISHTI_REPORTS_DIR` | server (each node its own) | the report definitions, one YAML per report (docs/guides/VAR\_GUIDE.md section 18.14) | restart |  | admin |
| `drishti.regulatory.synthetic-history` | boolean | `false` | `DRISHTI_SYNTHETIC_HISTORY` | server (each node its own) | a demo server may seed synthetic official records before its oldest one; never on a bank's server | restart |  | admin |
| `drishti.regulatory.backtesting.window` | integer | `250` |  | server (each node its own) | the business days counted | restart |  | admin |
| `drishti.regulatory.backtesting.confidences` | list | `[0.99, 0.975]` |  | server (each node its own) | the VaR confidences tested; the first sets the zone and the multiplier (each node's record keeps its VaR at the others) | restart |  | admin |
| `drishti.regulatory.backtesting.green` | integer | `4` |  | server (each node its own) | the most exceptions of the green zone | restart |  | admin |
| `drishti.regulatory.backtesting.amber` | integer | `9` |  | server (each node its own) | the most exceptions of the amber zone; more is red | restart |  | admin |
| `drishti.regulatory.backtesting.multipliers` | list | `[3.00, 3.00, 3.00, 3.00, 3.00, 3.40, 3.50, 3.65, 3.75, 3.85, 4.00]` |  | server (each node its own) | the capital multiplier by exceptions, the last for that many or more (US 12 CFR 217.204) | restart |  | admin |
| `drishti.regulatory.backtesting.count` | string | `actual` |  | server (each node its own) | the count the zone and multiplier read: actual (the US rule), hypothetical, or higher (of the two) | restart |  | admin |

#### `drishti.engines.exposure`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.exposure.enabled` | boolean | `true` | `DRISHTI_EXPOSURE_ENABLED` | server (each node its own) | run the exposure engine on this node: /api/v1/exposure, EXP, the exposure-engine connector (live) | live |  | admin |
| `drishti.engines.exposure.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.ccr.exposure`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.ccr.exposure.confidence` | list | `[0.95, 0.99]` |  | server (each node its own) | PFE confidences computed for every answer (any q in (0.5, 1) may be asked) | restart |  | everyday |
| `drishti.ccr.exposure.limit-confidence` | number | `0.95` |  | server (each node its own) | the PFE credit limits are set on; the default q of an answer | restart |  | everyday |
| `drishti.ccr.exposure.rank-rule` | string | `kth` |  | server (each node its own) | kth (the ceil(P q)-th smallest exposure) or interpolated | restart |  | admin |
| `drishti.ccr.exposure.collateral-model` | string | `lagged` |  | server (each node its own) | lagged (called at the look-back point), current (today's collateral on every path) or none | restart |  | admin |
| `drishti.ccr.exposure.im-model` | string | `current` |  | server (each node its own) | current (the initial margin held today on every path) or none | restart |  | admin |
| `drishti.ccr.exposure.alpha` | number | `1.4` |  | server (each node its own) | EAD\_IMM = alpha x EEPE (informational) | restart |  | admin |
| `drishti.ccr.exposure.contribution-window` | integer | `21` |  | server (each node its own) | paths around the PFE quantile a PFE contribution averages over | restart |  | admin |
| `drishti.ccr.exposure.hot-dates` | integer | `10` | `DRISHTI_EXPOSURE_HOT_DATES` | server (each node its own) | business dates kept open: the least recently used leaves first; the date just used, the newest business date and the dates pinned in memory always stay (as VaR's) | restart |  | admin |
| `drishti.ccr.exposure.refusal-ttl-seconds` | integer | `300` |  | server (each node its own) | a date refused for room (capacity, disk) is remembered this long: 503 at once with Retry-After, no lake read (0: not remembered) | restart |  | admin |
| `drishti.ccr.exposure.open-heap-mb` | integer | `0` | `DRISHTI_EXPOSURE_OPEN_HEAP_MB` | server (each node its own) | the byte safety net over the open dates' heap (0: automatic, as VaR's); over it the least recently used dates leave | restart |  | expert |
| `drishti.ccr.exposure.fiscal-year-end` | string | empty | `DRISHTI_FISCAL_YEAR_END` | server (each node its own) | the bank's fiscal year-end, MM-DD; blank: none | restart |  | admin |
| `drishti.ccr.exposure.pinned.month-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | each month's last business day (back: how many; 0: off) | restart |  | expert |
| `drishti.ccr.exposure.pinned.quarter-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | March, June, September, December | restart |  | expert |
| `drishti.ccr.exposure.pinned.year-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | December | restart |  | expert |
| `drishti.ccr.exposure.pinned.fiscal-year-end` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | the fiscal year's last business day (needs fiscal-year-end) | restart |  | expert |
| `drishti.ccr.exposure.pinned.stress-window` | map | `{ back: 0, keep: disk }` |  | server (each node its own) | exposure has no stress window: unused | restart |  | expert |
| `drishti.ccr.exposure.pinned.dates` | list | `[]` |  | server (each node its own) | any dates the bank names (yyyy-MM-dd) | restart |  | expert |
| `drishti.ccr.exposure.pinned.dates-keep` | string | `disk` |  | server (each node its own) | how the named dates are kept: disk or memory | restart |  | expert |
| `drishti.ccr.exposure.view-cache-mb` | integer | `16` |  | server (each node its own) | most memory the viewers' views take; idle 1 h, released with the date | restart |  | expert |
| `drishti.ccr.exposure.load-threads` | integer | `2` |  | server (each node its own) | threads that load dates from the lake: their own pool, never the common pool | restart |  | expert |
| `drishti.ccr.exposure.version-check` | duration | `30s` | `DRISHTI_EXPOSURE_VERSION_CHECK` | server (each node its own) | an open date compares its lake version this often; moved: reloaded in the background (0: off) | restart |  | admin |
| `drishti.ccr.exposure.memory-budget-gb` | integer | `0` | `DRISHTI_EXPOSURE_MEMORY_GB` | server (each node its own) | most memory one date's netting-set matrices take while it loads (0: a quarter of the heap); over it: 503 DRS-5445 | restart |  | expert |
| `drishti.ccr.exposure.hot-folder` | string | `./data/ccr/hot` | `DRISHTI_EXPOSURE_HOT_DIR` | server (each node its own) | the hot files, one folder per business date; never under the repository | restart |  | admin |
| `drishti.ccr.exposure.tier` | string | `mapped` | `DRISHTI_EXPOSURE_TIER` | server (each node its own) | mapped: each date a float32 hot file, memory-mapped; heap: float64 on the heap | restart |  | admin |
| `drishti.ccr.exposure.disk-dates` | integer | `60` |  | server (each node its own) | hot files kept on disk (least recently used beyond are deleted, unless the retention keeps them) | restart |  | admin |
| `drishti.ccr.exposure.max-disk-gb` | integer | `0` | `DRISHTI_EXPOSURE_MAX_DISK_GB` | server (each node its own) | most disk the hot files take; 0: a quarter of the disk; it wins over the retention (PO-3) | restart |  | admin |
| `drishti.ccr.exposure.keep-on-shutdown` | boolean | `false` | `DRISHTI_EXPOSURE_KEEP_HOT_FILES` | server (each node its own) | false: deleted at shutdown; the server images set true (PO-10) | restart |  | admin |
| `drishti.ccr.exposure.close-grace-seconds` | integer | `60` |  | server (each node its own) | an evicted date stays mapped this long for the requests still reading it | restart |  | admin |
| `drishti.ccr.exposure.max-scope-netting-sets` | integer | `20000` |  | server (each node its own) | the most whole netting sets an interactive answer sums (more: 503 DRS-5443; the official capture computes it) | restart |  | admin |
| `drishti.ccr.exposure.max-parts` | integer | `5000` |  | server (each node its own) | most parts a contribution table computes at once | restart |  | admin |
| `drishti.ccr.exposure.histogram-bins` | integer | `42` |  | server (each node its own) | bins of an exposure distribution | restart |  | admin |
| `drishti.ccr.exposure.t0-tolerance` | number | `0.10` |  | server (each node its own) | a trade's paths' mean at the first grid date is listed when it departs from its MtM today by more (or 5% of notional) | restart |  | admin |
| `drishti.ccr.exposure.kinds.run` | string | `simulation-run` |  | server (each node its own) | the kind of the simulation runs (a data-load signal of it reloads that date) | restart |  | admin |
| `drishti.ccr.exposure.kinds.paths` | string | `exposure-path` |  | server (each node its own) | the kind of the per-trade exposure paths | restart |  | admin |
| `drishti.ccr.exposure.kinds.netting-set` | string | `netting-set` |  | server (each node its own) | the kind of the netting sets | restart |  | admin |
| `drishti.ccr.exposure.kinds.csa` | string | `csa` |  | server (each node its own) | the kind of the credit support annexes | restart |  | admin |
| `drishti.ccr.exposure.kinds.collateral` | string | `collateral-balance` |  | server (each node its own) | the kind of the collateral balances | restart |  | admin |
| `drishti.ccr.exposure.kinds.counterparty` | string | `counterparty` |  | server (each node its own) | the kind of the counterparties (the core pack's) | restart |  | admin |
| `drishti.ccr.exposure.kinds.group` | string | `counterparty-group` |  | server (each node its own) | the kind of the counterparty groups | restart |  | admin |
| `drishti.ccr.exposure.kinds.hierarchy` | string | `risk-hierarchy` |  | server (each node its own) | the kind of the risk hierarchies (the market-risk pack's) | restart |  | admin |
| `drishti.ccr.exposure.kinds.limit` | string | `credit-limit` |  | server (each node its own) | the stored credit limits, read as stored (the explorer's limit column; LIM stays parked) | restart |  | admin |
| `drishti.ccr.exposure.access` | map | `{}` |  | server (each node its own) | per role, per counterparty, group or legal entity: trades \| aggregate \| none (drishti.ccr.exposure.access.&lt;role&gt;\[CP-ALDERSHOT]=aggregate) | restart |  | admin |
| `drishti.ccr.exposure.official.enabled` | boolean | `true` | `DRISHTI_EXPOSURE_OFFICIAL` | server (each node its own) | capture every netting set, counterparty, group and hierarchy node when a date's run is complete | restart |  | admin |
| `drishti.ccr.exposure.official.publish` | boolean | `true` |  | server (each node its own) | also append them to &lt;root&gt;/risk-results/exposure-official and exposure-official-cpty (append-only) | restart |  | admin |
| `drishti.ccr.exposure.official.root` | string | `./data/delta` | `DRISHTI_DELTA_ROOT` | server (each node its own) | a folder or s3://bucket/prefix | restart |  | admin |
| `drishti.ccr.exposure.official.format` | string | `delta` | `DRISHTI_EXPOSURE_OFFICIAL_FORMAT` | server (each node its own) | delta or iceberg; BI's exposure-results connector follows it | restart |  | admin |
| `drishti.ccr.exposure.official.storage` | map | `{}` |  | server (each node its own) | the s3.\* settings for an s3:// root, as drishti.var.official.storage | restart |  | admin |
| `drishti.ccr.exposure.official.retention-years` | integer | `7` |  | server (each node its own) | the published tables keep their log and removed files (time travel) this long | restart |  | admin |
| `drishti.ccr.exposure.official.on-start` | boolean | `true` |  | server (each node its own) | at start, capture the newest date when it has no official record yet | restart |  | admin |
| `drishti.ccr.exposure.official.jsonl` | boolean | `true` |  | server (each node its own) | also write each published date as JSON Lines: &lt;root&gt;/risk-results/jsonl/&lt;table&gt;/&lt;date&gt;/ | restart |  | admin |
| `drishti.ccr.exposure.retention.trade-paths-days` | integer | `20` |  | server (each node its own) | full trade paths: the last 20 business dates ... | restart |  | admin |
| `drishti.ccr.exposure.retention.trade-paths-month-end-years` | integer | `1` |  | server (each node its own) | ... and each month-end for a year (the lake's owner applies it; GET /api/v1/exposure/retention lists the dates beyond) | restart |  | admin |
| `drishti.ccr.exposure.retention.set-matrices-years` | integer | `1` |  | server (each node its own) | netting-set matrices (the hot files): every date for a year ... | restart |  | admin |
| `drishti.ccr.exposure.retention.set-matrices-month-end-years` | integer | `7` |  | server (each node its own) | ... and each month-end for seven | restart |  | admin |
| `drishti.ccr.exposure.retention.statistics-years` | integer | `7` |  | server (each node its own) | the official records (statistics per set, counterparty, group and node): every date, seven years | restart |  | admin |
| `drishti.ccr.exposure.retention.trade-contributions-years` | integer | `7` |  | server (each node its own) | every trade's EE contributions per primary date: every date, seven years | restart |  | admin |

#### `drishti.engines.sensitivities`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.sensitivities.enabled` | boolean | `true` | `DRISHTI_SENSITIVITIES_ENABLED` | server (each node its own) | run position sensitivities on this node: /api/v1/sensitivities, /api/v1/risk-factors; FRTB-SA and SIMM need it (live) | live |  | admin |
| `drishti.engines.sensitivities.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.sensitivities`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.sensitivities.sensitivity-kind` | string | `position-sensitivity` |  | server (each node its own) | the position sensitivities (SENS) | restart |  | admin |
| `drishti.sensitivities.factor-kind` | string | `risk-factor` |  | server (each node its own) | the factor dictionary (RFAC) | restart |  | admin |
| `drishti.sensitivities.cache-minutes` | integer | `10` |  | server (each node its own) | how long a date's sensitivities stay in memory after they are read | restart |  | expert |

#### `drishti.engines.frtb-sa`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.frtb-sa.enabled` | boolean | `true` | `DRISHTI_FRTB_ENABLED` | server (each node its own) | run FRTB-SA on this node: /api/v1/frtb, FRSA, PLAY FRTB; needs var and sensitivities (live) | live |  | admin |
| `drishti.engines.frtb-sa.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.frtb`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.frtb.parameters-dir` | string | `./config/packs/market-risk/capital` | `DRISHTI_FRTB_PARAMETERS` | server (each node its own) | frtb-sa-sbm.yaml and frtb-sa-drc-rrao.yaml (else the market-risk pack's capital/) | restart |  | admin |
| `drishti.frtb.default-risk-kind` | string | `default-risk` |  | server (each node its own) | the DRC inputs; none served: "DRC not computed", never 0 | restart |  | admin |
| `drishti.frtb.residual-risk-kind` | string | `residual-risk` |  | server (each node its own) | the RRAO inputs; none served: derived from the positions' product types and notionals | restart |  | admin |
| `drishti.frtb.rrao-from-positions` | boolean | `true` |  | server (each node its own) | derive the RRAO from PRODUCT\_TYPE (the parameter file's mapping) and the VaR book's notionals | restart |  | admin |
| `drishti.frtb.max-factors` | integer | `2000` |  | server (each node its own) | the most factors one playground grid and workbook holds | restart |  | admin |
| `drishti.frtb.recipes-dir` | string | `./data/frtb/recipes` |  | server (each node its own) | saved playground recipes (PLAY FRTB RUN &lt;id&gt;) | restart |  | admin |
| `drishti.frtb.playground-template` | string | `""` |  | server (each node its own) | a bank's workbook template folder; blank: the built-in playground/frtb-sa/template/ | restart |  | admin |

#### `drishti.engines.simm`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.simm.enabled` | boolean | `true` | `DRISHTI_SIMM_ENABLED` | server (each node its own) | run SIMM on this node: /api/v1/simm, SIMM, PLAY SIMM; needs sensitivities (live) | live |  | admin |
| `drishti.engines.simm.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.simm`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.simm.parameters-dir` | string | `./config/simm` | `DRISHTI_SIMM_PARAMETERS` | server (each node its own) | the bank's licensed parameter files, one YAML a SIMM version (none ships: ISDA's numbers are licensed) | restart |  | admin |
| `drishti.simm.default-version` | string | `""` |  | server (each node its own) | the version for a CSA naming none and for analytic scopes; blank: the installed one with the latest effective date | restart |  | admin |
| `drishti.simm.netting-set-kind` | string | `netting-set` |  | server (each node its own) | the netting sets and their trades | restart |  | admin |
| `drishti.simm.csa-kind` | string | `csa` |  | server (each node its own) | the CSAs; their SIMM terms (SIMM\_VERSION, IM\_COLLECT\_REGIMES, IM\_POST\_REGIMES, IM\_CALCULATION\_CURRENCY, add-ons) when present | restart |  | admin |
| `drishti.simm.add-on-kind` | string | `simm-addon` |  | server (each node its own) | the notional add-on lines; none served: no notional add-on | restart |  | admin |
| `drishti.simm.external-kind` | string | `crif-external` |  | server (each node its own) | the counterparty's call (HEADER rows) for the comparison; none served: "no call" | restart |  | admin |
| `drishti.simm.stored-kind` | string | `simm` |  | server (each node its own) | the stored figures shown, labelled "stored, not computed", for netting sets without sensitivities | restart |  | admin |
| `drishti.simm.default-product-class` | string | `RatesFX` |  | server (each node its own) | the product class of a PRODUCT\_GROUP not in drishti.simm.product-classes (built-in mapping: SIMM\_GUIDE.md section 3.3) | restart |  | admin |
| `drishti.simm.max-factors` | integer | `1500` |  | server (each node its own) | the most factors one playground grid and workbook holds | restart |  | admin |
| `drishti.simm.recipes-dir` | string | `./data/simm/recipes` |  | server (each node its own) | saved playground recipes (PLAY SIMM RUN &lt;id&gt;) | restart |  | admin |
| `drishti.simm.cache-minutes` | integer | `10` |  | server (each node its own) | how long a date's netting sets and CSAs stay in memory after they are read | restart |  | expert |
| `drishti.simm.playground-template` | string | `""` |  | server (each node its own) | a bank's workbook template folder; blank: the built-in playground/simm/template/ | restart |  | admin |

#### `drishti.engines.mr-cube`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.mr-cube.enabled` | boolean | `true` | `DRISHTI_MR_CUBE_ENABLED` | server (each node its own) | run the MR cube on this node: MCUBE, /api/v1/pivot/mr, the VaR source's hierarchy levels and MR measures; needs var and pivot | restart |  | admin |
| `drishti.engines.mr-cube.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.cube`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.cube.mr.sbm-max-cells` | integer | `60` |  | server (each node its own) | the most cells whose FRTB-SA SBM one view computes (a run of the FRTB-SA engine per cell scope) | restart |  | admin |
| `drishti.cube.mr.stressed-max-cells` | integer | `100` |  | server (each node its own) | the most cells whose stressed VaR one view computes, scope by scope | restart |  | admin |
| `drishti.cube.mr.sparkline-dates` | integer | `20` |  | server (each node its own) | the open business dates a sparkline reads (a date not open here is not opened for one) | restart |  | admin |
| `drishti.cube.mr.sparkline-max-cells` | integer | `600` |  | server (each node its own) | the most cells of a view that get a sparkline | restart |  | admin |
| `drishti.cube.mr.what-if-rows` | integer | `40` |  | server (each node its own) | the most rows the what-if side by side computes (the total always added) | restart |  | admin |
| `drishti.cube.mr.risk-classes` | map | `{}` |  | server (each node its own) | RISK\_CLASS: a class to its product groups, in column order; empty: IR, CR, FX, EQ, CM of the demo's groups (MR\_CUBE\_GUIDE.md section 3) | restart |  | admin |
| `drishti.cube.mr.grids` | map | `{}` |  | server (each node its own) | the ladders' bucket grids, each bucket label: from-to in years ({ BANK-IR-TENOR: { 0-2Y: "0-2", 10Y+: "10-" } }); empty: BANK-IR-TENOR and FRTB-GIRR | restart |  | admin |
| `drishti.cube.mr.ladder-limits` | map | `{}` |  | server (each node its own) | two-sided DV01 limits per currency and bucket ({ "USD 2-5Y": 1500000 }); empty: no limit bars | restart |  | admin |

#### `drishti.engines.memory-lake`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.memory-lake.enabled` | boolean | `false` | `DRISHTI_MEMORY_LAKE_ENABLED` | server (each node its own) | run the in-memory lake on this node; off as shipped: turning it on needs a restart (the stores' caps set the direct-memory limit at start) | restart |  | expert |
| `drishti.engines.memory-lake.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | expert |

#### `drishti.lake`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.lake.memory.stores` | map | `{}` |  | server (each node its own) | the old place of the memory stores, read for one more release with a warning: a store is now a lake, config/lakes/&lt;store&gt;.yaml with root: mem://&lt;store&gt; and memory: { max-mb (the hard cap), chunk-kb (1024), contract, owner (true), idle-trim-minutes (10) } (docs/guides/IN\_MEMORY\_LAKE\_GUIDE.md); the caps add to -XX:MaxDirectMemorySize | restart |  | expert |
| `drishti.lake.memory.tables` | list | `[]` |  | server (each node its own) | the tables and their replenish jobs: name, store, folder, source { connector, kind, file, url, headers, ids, ids-from }, mode (replace, append, merge), key, sort-by, business-date, columns, every or cron, signal, warm-up, pin, keep-versions (2), codec (zstd) | restart |  | expert |
| `drishti.lake.memory.history-keep` | integer | `50` |  | server (each node its own) | the runs kept per table for Admin -&gt; Lakes and drishti.py lake memory jobs (in memory) | restart |  | expert |
| `drishti.lake.memory.job-threads` | integer | `2` |  | server (each node its own) | replenish jobs that may run at once (one per table at most) | restart |  | expert |
| `drishti.lake.memory.compaction.enabled` | boolean | `true` |  | server (each node its own) | rewrite a table when it would save memory: small files merged, replaced rows dropped, appends re-sorted | restart |  | expert |
| `drishti.lake.memory.compaction.idle-only` | boolean | `false` |  | server (each node its own) | true: only while no request is being answered | restart |  | expert |
| `drishti.lake.memory.compaction.idle-seconds` | integer | `30` |  | server (each node its own) | the quiet that counts as idle | restart |  | expert |
| `drishti.lake.memory.compaction.headroom-mb` | integer | `512` |  | server (each node its own) | memory kept free for a rewrite's new files beside the old (at most a quarter of the store): compaction never causes an eviction | restart |  | expert |
| `drishti.lake.memory.compaction.small-file-mb` | integer | `8` |  | server (each node its own) | files below this are merged | restart |  | expert |
| `drishti.lake.memory.compaction.dead-rows` | number | `0.2` |  | server (each node its own) | a merge-mode table is rewritten when this share of its rows was replaced since its last rewrite | restart |  | expert |
| `drishti.lake.memory.compaction.resort-after` | integer | `20` |  | server (each node its own) | appends after which a table is rewritten sorted by its sort-by | restart |  | expert |
| `drishti.lake.memory.compaction.every-seconds` | integer | `60` |  | server (each node its own) | how often the compactor looks, besides after every commit | restart |  | expert |

#### `drishti.engines.dataload`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.dataload.enabled` | boolean | `false` | `DRISHTI_DATALOAD_ENABLED` | server (each node its own) | run the data inbox on this node: files dropped in &lt;dir&gt;/inbox with a .ready marker are loaded into the lake every interval; off as shipped | restart |  | admin |
| `drishti.engines.dataload.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.dataload`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.dataload.owner` | boolean | `true` | `DRISHTI_DATALOAD_OWNER` | server (each node its own) | this node runs the inbox's cycles; in a cluster set it true on one node only (the folder lock also keeps a second runner out) | restart |  | admin |
| `drishti.dataload.dir` | string | `./data/dataload` | `DRISHTI_DATALOAD_DIR` | server (each node its own) | the inbox's folder: inbox/, inprocess/, outbox/, failed/, ledger/ and inbox.lock, all on one file system | restart |  | admin |
| `drishti.dataload.interval` | duration | `10m` |  | server (each node its own) | how often a cycle runs; a cycle due while a run still loads is skipped (runs never overlap); Load now starts one at once | restart |  | expert |
| `drishti.dataload.stable-seconds` | integer | `30` |  | server (each node its own) | a file (and its marker) is taken once unchanged this long, even with its .ready file | restart |  | admin |
| `drishti.dataload.max-files` | integer | `0` |  | server (each node its own) | the most files one run takes, oldest first (0: no limit, the whole ready batch); when set, the rest wait for the next cycle | restart |  | admin |
| `drishti.dataload.max-gb` | integer | `0` |  | server (each node its own) | the most gigabytes one run takes (0: no limit); when set, the first file is always taken | restart |  | admin |
| `drishti.dataload.loader-heap` | string | `4g` | `DRISHTI_DATALOAD_LOADER_HEAP` | server (each node its own) | the loader's own heap (the child Java process's -Xmx): a load never uses the server's heap | restart |  | expert |
| `drishti.dataload.load-timeout` | duration | `2h` |  | server (each node its own) | a load running longer is stopped and its files go back to the inbox with the reason | restart |  | expert |
| `drishti.dataload.reject-max-percent` | integer | `0` |  | server (each node its own) | the share of rows a load may reject and still commit (exit 2, rejects in the report); over it nothing is committed and the file goes to failed/ | restart |  | admin |
| `drishti.dataload.reload-duplicates` | boolean | `false` |  | server (each node its own) | false: a file whose SHA-256 was loaded before is not loaded again (to the outbox, "already loaded"); true: loaded again | restart |  | admin |
| `drishti.dataload.keep-outbox-days` | integer | `30` |  | server (each node its own) | outbox/ and failed/ day folders older than this are deleted (0: kept for ever); the ledger is kept | restart |  | admin |
| `drishti.dataload.lake` | string | `demo` | `DRISHTI_DATALOAD_LAKE` | server (each node its own) | the lake (config/lakes/&lt;lake&gt;.yaml, docs/guides/DATA\_LAKE\_GUIDE.md) the tables are written to; a kind the lake file lacks gets its pack's recommended entry copied there first (a rule's lake: wins for its files; a folder is the old form, read with one warning) | restart |  | admin |
| `drishti.dataload.rules-file` | string | `./config/dataload.yaml` | `DRISHTI_DATALOAD_RULES` | server (each node its own) | the routing rules: a file-name pattern to a pack and kind, read again every cycle | restart |  | admin |
| `drishti.dataload.nice` | boolean | `true` |  | server (each node its own) | run the loader at a lower priority (nice 10) where the system has nice; never on Windows | restart |  | admin |
| `drishti.dataload.default-mode` | string | `append` |  | server (each node its own) | a file's load mode when its .ready file says none: append (the rows are added) or replace (each business date of the file replaced) | restart |  | admin |
| `drishti.dataload.new-tables` | boolean | `false` |  | server (each node its own) | whether a file may create a table no pack has (its .ready file's new-table: true and pack:); false: such a file goes to failed/ with the known tables | restart |  | admin |

#### `drishti.engines.pivot`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.pivot.enabled` | boolean | `true` | `DRISHTI_PIVOT_ENGINE_ENABLED` | server (each node its own) | run the pivot engine on this node: /api/v1/pivot, PIVOT, Views -&gt; Pivot, drishti.py pivot (live); its old name drishti.pivot.enabled still works | live |  | admin |
| `drishti.engines.pivot.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |

#### `drishti.pivot`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.pivot.max-cells` | integer | `20000` |  | server (each node its own) | visible cells a view may have, totals included; over it: refused with the count (DRS-5702), never cut short | restart |  | admin |
| `drishti.pivot.max-holistic-cells` | integer | `5000` |  | server (each node its own) | the same for a view with a holistic measure (VaR, an exact median, an exact distinct count) | restart |  | admin |
| `drishti.pivot.busy-cells` | integer | `2000` |  | server (each node its own) | a view of more cells asks busy protection before it opens deeper levels (DRS-9507) | restart |  | admin |
| `drishti.pivot.cache-mb` | integer | `512` |  | server (each node its own) | the partial-state cache, weighed in bytes, in the one memory budget (pivot.states) | restart |  | expert |
| `drishti.pivot.provenance-kept` | integer | `500` |  | server (each node its own) | answers whose provenance (per cell) is kept for the popover | restart |  | admin |
| `drishti.pivot.local-shards` | integer | `1` |  | server (each node its own) | split a mergeable plan into this many parts on this node and merge them in a fixed order (1: one pass) | restart |  | expert |
| `drishti.pivot.stage-max-rows` | integer | `5000000` |  | server (each node its own) | the most rows of one table read for a pivot (a table over it is refused in words, never cut short) | restart |  | expert |
| `drishti.pivot.stage-keep` | integer | `16` |  | server (each node its own) | tables read for pivots kept in the executor (least recently used dropped) | restart |  | admin |
| `drishti.pivot.duckdb.connections` | integer | `0` |  | server (each node its own) | statements at once (0: one per four cores) | restart |  | expert |
| `drishti.pivot.duckdb.threads` | integer | `0` |  | server (each node its own) | DuckDB's threads per statement (0: its default) | restart |  | expert |
| `drishti.pivot.duckdb.memory-limit-mb` | integer | `4096` |  | server (each node its own) | DuckDB's memory limit | restart |  | expert |
| `drishti.pivot.duckdb.temp-directory` | string | `""` |  | server (each node its own) | where DuckDB may spill (blank: nowhere, so memory-only data is never written) | restart |  | expert |
| `drishti.pivot.estimate.confirm-above-seconds` | integer | `60` |  | server (each node its own) | an estimate over this asks the person first | restart |  | admin |
| `drishti.pivot.estimate.confirm-above-rows` | integer | `500000000` |  | server (each node its own) | an estimate over this many rows asks the person first | restart |  | admin |
| `drishti.pivot.aggregate-only.min-members-per-cell` | integer | `1` |  | server (each node its own) | a cell with fewer members shows no figures (1: no limit; a site may set 5) | restart |  | admin |
| `drishti.pivot.functions.formula` | boolean | `true` |  | server (each node its own) | DEL formulas of the built-ins as custom functions | restart |  | admin |
| `drishti.pivot.functions.sql-macros` | boolean | `true` |  | server (each node its own) | DuckDB SQL macros as custom functions (a formula of built-ins is rewritten into states; any other is holistic) | restart |  | admin |
| `drishti.pivot.functions.require-approval` | boolean | `true` |  | server (each node its own) | sharing a function needs an approver who is not its author (audited) | restart |  | admin |
| `drishti.pivot.functions.approver-role` | string | `pivot-approver` |  | server (each node its own) | members of this role approve functions; administrators always may | restart |  | admin |
| `drishti.pivot.functions.holistic-max-rows` | integer | `5000000` |  | server (each node its own) | the most rows of one cell a holistic custom function receives (over it: refused for that cell, never cut) | restart |  | expert |
| `drishti.pivot.functions.holistic-max-mb` | integer | `512` |  | server (each node its own) | the most megabytes of one cell's rows for a holistic custom function | restart |  | expert |
| `drishti.pivot.functions.harness.tolerance` | string | `1.0e-12` |  | server (each node its own) | the relative difference split-and-merge may show against one pass (a function's own tolerance wins) | restart |  | admin |
| `drishti.pivot.functions.harness.seeds` | integer | `5` |  | server (each node its own) | seeds of the random splits into 2, 3 and 16 parts | restart |  | admin |
| `drishti.pivot.functions.harness.max-ns-per-row` | integer | `2000` |  | server (each node its own) | the cost limit a row (judged on samples of 1,000 rows or more) | restart |  | admin |
| `drishti.pivot.functions.python.enabled` | boolean | `false` |  | server (each node its own) | Python custom functions on this node (the pivot-fn pool); off as shipped | restart |  | admin |
| `drishti.pivot.functions.python.interpreter` | string | `""` |  | server (each node its own) | the Python to run (blank: python3, python on Windows) | restart |  | admin |
| `drishti.pivot.functions.python.workers` | integer | `2` |  | server (each node its own) | sandboxed worker processes in the pivot-fn pool | restart |  | expert |
| `drishti.pivot.functions.python.timeout-seconds` | integer | `30` |  | server (each node its own) | the longest one call (a batch, a merge) may take; its worker is stopped past it | restart |  | expert |
| `drishti.pivot.functions.python.memory-mb` | integer | `1024` |  | server (each node its own) | memory a worker may use | restart |  | expert |
| `drishti.pivot.functions.python.batch-rows` | integer | `65536` |  | server (each node its own) | rows in each batch a function is fed (Python and Java plug-ins without an SQL form) | restart |  | expert |
| `drishti.pivot.functions.java.enabled` | boolean | `false` |  | server (each node its own) | Java plug-in functions (a jar approved by an administrator, pinned by its SHA-256); off as shipped | restart |  | admin |
| `drishti.pivot.functions.java.dir` | string | `./plugins/pivot-functions` |  | server (each node its own) | the plug-in folder | restart |  | admin |
| `drishti.pivot.export.xlsx-max-rows` | integer | `1000000` |  | server (each node its own) | the most rows of an xlsx export (Excel holds 1,048,576 a sheet): over it, CSV or Arrow is offered | restart |  | expert |
| `drishti.pivot.export.rows-max` | integer | `1000000` |  | server (each node its own) | the most member rows an export of the underlying rows writes | restart |  | admin |
| `drishti.pivot.sketches.hll-precision` | integer | `12` |  | server (each node its own) | HyperLogLog: 2^p registers (11 to 16); 12: 4,096 registers, ±1.6% (1.04/√m) | restart |  | admin |
| `drishti.pivot.sketches.kll-k` | integer | `200` |  | server (each node its own) | KLL's k: ±1.65% of rank at 200 (with SQL's quantile points ±1.8%) | restart |  | admin |
| `drishti.pivot.sketches.top-capacity-factor` | integer | `10` |  | server (each node its own) | Space-Saving keeps this many counters per value shown by top(x, n) | restart |  | admin |
| `drishti.pivot.sketches.approximate-above-rows` | integer | `10000000` |  | server (each node its own) | a view reading more rows answers count\_distinct, median and percentile by their sketches (≈; exact on request); 0: never by size | restart |  | admin |
| `drishti.pivot.progressive.enabled` | boolean | `true` |  | server (each node its own) | false: one exact answer only | restart |  | admin |
| `drishti.pivot.progressive.after-ms` | integer | `2000` |  | server (each node its own) | a view estimated over this answers ≈ from a sample first | restart |  | expert |
| `drishti.pivot.progressive.sample-fraction` | number | `0.02` |  | server (each node its own) | the share of rows the first answer reads | restart |  | admin |
| `drishti.pivot.rollups.enabled` | boolean | `true` |  | server (each node its own) | rollups answer views and are refreshed | restart |  | admin |
| `drishti.pivot.rollups.refresh-seconds` | integer | `60` |  | server (each node its own) | how often each rollup's source version is checked: a load is merged in within this | restart |  | admin |
| `drishti.pivot.rollups.advise-after-scans` | integer | `10` |  | server (each node its own) | a grain scanned this many times is proposed as a rollup (GET /api/v1/pivot/rollups, advice) | restart |  | admin |
| `drishti.pivot.rollups.dir` | string | `""` |  | server (each node its own) | where rollups are kept (blank: &lt;data dir&gt;/pivot/rollups) | restart |  | admin |
| `drishti.pivot.rollups.definitions` | list | `[]` |  | server (each node its own) | rollups: {id, source, grain: \[DESK, TRADE\_DATE:month], functions: \[sum(NOTIONAL\_USD), count()], dates: latest\|all} | restart |  | admin |
| `drishti.pivot.live.enabled` | boolean | `true` |  | server (each node its own) | false: no Live switch, POST /api/v1/pivot/live refused (DRS-5721) | restart |  | admin |
| `drishti.pivot.live.max-per-person` | integer | `5` |  | server (each node its own) | live views one person may hold | restart |  | admin |
| `drishti.pivot.live.max-total` | integer | `200` |  | server (each node its own) | live views this node holds | restart |  | admin |
| `drishti.pivot.live.min-interval-ms` | integer | `1000` |  | server (each node its own) | the least time between two recomputes of one view; changes in between are coalesced (the latest wins) | restart |  | expert |
| `drishti.pivot.live.max-cells` | integer | `5000` |  | server (each node its own) | the most visible cells a live view keeps current | restart |  | admin |
| `drishti.pivot.live.max-rows` | integer | `50000000` |  | server (each node its own) | the most rows an exact live recompute may read (estimated); a larger view is live only when it asks ≈ (0: no limit) | restart |  | expert |
| `drishti.pivot.live.idle-seconds` | integer | `60` |  | server (each node its own) | a live view no stream reads is ended after this | restart |  | admin |
| `drishti.pivot.live.heartbeat-seconds` | integer | `15` |  | server (each node its own) | a status event to every live stream this often (also when idle views are ended) | restart |  | admin |
| `drishti.pivot.cluster.enabled` | boolean | `true` |  | server (each node its own) | false: this node computes its views alone | restart |  | admin |
| `drishti.pivot.cluster.hedge-after-seconds` | integer | `20` |  | server (each node its own) | a node's share not answered by then is asked of the next node too; the first answer wins | restart |  | admin |
| `drishti.pivot.cluster.deadline-seconds` | integer | `300` |  | server (each node its own) | a share with no answer by then refuses the view in words (DRS-5720), never a partial figure | restart |  | admin |
| `drishti.pivot.cluster.min-rows` | integer | `5000000` |  | server (each node its own) | views estimated under this many rows stay on this node | restart |  | admin |

#### `drishti.engines.flight`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.flight.enabled` | boolean | `false` | `DRISHTI_FLIGHT_ENABLED` | server (each node its own) | answer Arrow Flight on this node; off as shipped: turning it on needs a restart (the port opens at start) | restart |  | admin |
| `drishti.engines.flight.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | admin |
| `drishti.engines.flight.bind` | string | `0.0.0.0` | `DRISHTI_FLIGHT_BIND` | server (each node its own) | the address the port opens on (0.0.0.0: every interface) | restart |  | admin |
| `drishti.engines.flight.port` | integer | `18815` | `DRISHTI_FLIGHT_PORT` | server (each node its own) | the Flight port (gRPC over HTTP/2); open it in firewalls, TCP pass-through in load balancers | restart |  | admin |
| `drishti.engines.flight.advertise` | string | empty | `DRISHTI_FLIGHT_ADVERTISE` | server (each node its own) | the address clients are given in GetFlightInfo; empty: the node's real address (never localhost or 0.0.0.0) | restart |  | admin |
| `drishti.engines.flight.tls.certificate` | string | `./config/tls/server.pem` | `DRISHTI_FLIGHT_TLS_CERT` | server (each node its own) | the certificate chain the port presents | restart |  | admin |
| `drishti.engines.flight.tls.private-key` | string | `./config/tls/server.key` | `DRISHTI_FLIGHT_TLS_KEY` | server (each node its own) | its private key, PKCS#8 PEM (secret) | restart |  | admin |
| `drishti.engines.flight.tls.client-ca` | string | empty | `DRISHTI_FLIGHT_TLS_CLIENT_CA` | server (each node its own) | a CA bundle: set it and every client must present a certificate it signed (mutual TLS) | restart |  | admin |
| `drishti.engines.flight.memory.total-mb` | integer | `4096` | `DRISHTI_FLIGHT_MEMORY_MB` | server (each node its own) | every Flight read of this node together; the launchers add it to the direct-memory limit (tools/lake/memory\_caps.py) | restart |  | expert |
| `drishti.engines.flight.memory.request-mb` | integer | `1024` |  | server (each node its own) | one read at most (a role's limit may be lower) | restart |  | expert |
| `drishti.engines.flight.batch-rows` | integer | `65536` |  | server (each node its own) | rows in one record batch sent | restart |  | expert |
| `drishti.engines.flight.session-minutes` | integer | `60` |  | server (each node its own) | how long the session given at the handshake lasts before the token is checked again | restart |  | admin |
| `drishti.engines.flight.readers-hours` | integer | `24` |  | server (each node its own) | how long finished reads are listed on Admin -&gt; Data out (the audit keeps them for good) | restart |  | admin |
| `drishti.engines.flight.limits.default` | map | `{ rows: 50000000, gb: 8, minutes: 10, memory-mb: 512 }` |  | server (each node its own) | a person whose roles name no limits: 50 million rows, 8 GB, 10 minutes, 512 MB of Arrow memory per read | restart |  | admin |
| `drishti.engines.flight.limits.roles` | map | `{}` |  | server (each node its own) | e.g. risk-manager: { rows: 200000000, gb: 32, minutes: 30, memory-mb: 1024 } | restart |  | admin |

#### `drishti.engines.openblas`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.engines.openblas.enabled` | boolean | `false` | `DRISHTI_OPENBLAS_ENABLED` | server (each node its own) | off as shipped (the Java kernels, the same figures); on: linear algebra on the bundled OpenBLAS on this node (live, no restart) for faster many-scope sums | live |  | expert |
| `drishti.engines.openblas.changed` | map | `{ by: "", at: "", reason: "" }` |  | server (each node its own) | the last change made from Admin -&gt; Engines or drishti.py (written by the server) | restart |  | expert |

#### Old names (still accepted)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `drishti.var.enabled` | boolean | none |  | server | The old name of drishti.engines.var.enabled (VaR): still accepted, and when set above the shipped files it decides, with a warning at start (DRS-1024). Move it to config/engines/var.yaml or use Admin → Engines. | live | 2.9.11 | admin |
| `drishti.ccr.exposure.enabled` | boolean | none |  | server | The old name of drishti.engines.exposure.enabled (Exposure): still accepted, and when set above the shipped files it decides, with a warning at start (DRS-1024). Move it to config/engines/exposure.yaml or use Admin → Engines. | live | 2.9.11 | admin |
| `drishti.frtb.enabled` | boolean | none |  | server | The old name of drishti.engines.frtb-sa.enabled (FRTB-SA): still accepted, and when set above the shipped files it decides, with a warning at start (DRS-1024). Move it to config/engines/frtb-sa.yaml or use Admin → Engines. | live | 2.9.11 | admin |
| `drishti.simm.enabled` | boolean | none |  | server | The old name of drishti.engines.simm.enabled (SIMM): still accepted, and when set above the shipped files it decides, with a warning at start (DRS-1024). Move it to config/engines/simm.yaml or use Admin → Engines. | live | 2.9.11 | admin |
| `drishti.pivot.enabled` | boolean | none |  | server | The old name of drishti.engines.pivot.enabled (Pivot): still accepted, and when set above the shipped files it decides, with a warning at start (DRS-1024). Move it to config/engines/pivot.yaml or use Admin → Engines. | live | 2.9.11 | admin |

### The console: `application.yaml`

`drishti-console/config/application.yaml`. Override a key in `config/application.local.yaml`, with the variable named, with `DRISHTI_CONSOLE__SECTION__KEY` or a `--section.key=value` argument.

#### `server`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `server.host` | string | `0.0.0.0` | `DRISHTI_CONSOLE_HOST` | console | every interface, so other machines (and phones) can open it; 127.0.0.1 = this machine only | restart |  | admin |
| `server.port` | integer | `17480` | `DRISHTI_CONSOLE_PORT` | console | the console's HTTP port | restart |  | admin |
| `server.workers` | integer | `4` | `DRISHTI_CONSOLE_WORKERS` | console | processes serving pages (one Python process uses one core: ~40 pages/s measured); 1 = one process | restart |  | expert |
| `server.shutdown_seconds` | integer | `5` | `DRISHTI_CONSOLE_SHUTDOWN_SECONDS` | console | on a stop, how long open streams (live pages) may stay before they are closed; the browser then reconnects | restart |  | admin |

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `tmp_dir` | string | empty | `DRISHTI_CONSOLE_TMP_DIR` | console | base of the console's temp folder drishti-console-&lt;pid&gt; (blank: DRISHTI\_TMP\_DIR, then &lt;data dir&gt;/tmp/console when started by run\_drishti\_web.py); removed on exit; left-over ones are listed on Health, never deleted automatically | restart |  | admin |
| `external_lib` | string | empty | `DRISHTI_EXTERNAL_LIB` | console | base of the external library folder (blank: external\_lib in the repository root); &lt;base&gt;/python is importable, &lt;base&gt;/java is the server's | restart |  | admin |

#### `backend`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `backend.url` | string | `http://127.0.0.1:18480` | `DRISHTI_BACKEND_URL` | console | where the server is (one server; backend.urls for several) | restart |  | admin |
| `backend.timeout_seconds` | integer | `15` |  | console | per call to the server; longer than the server's own read limit (drishti.sources.fetch-timeout, 10 s), so its answer comes through | restart |  | expert |
| `backend.pool_size` | integer | `64` |  | console | pooled HTTP connections to the server (per server, when there are several) | restart |  | expert |
| `backend.check_seconds` | integer | `5` | `DRISHTI_CONSOLE_BACKEND_CHECK_SECONDS` | console | how often the console asks each server if it is there (0 = never); pages show a banner and recover when it returns | restart |  | admin |
| `backend.urls` | string | empty | `DRISHTI_BACKEND_URLS` | console | Several servers of one Drishti\*net cluster (docs/admin/OPERATIONS.md, "The console and several servers"): one is enough, the console learns the rest. Empty: backend.url, as before. Each session keeps to one server while it answers and falls over to the next when it does not. e.g. https://drishti-a:8480,https://drishti-b:8480 | restart |  | admin |
| `backend.discover` | boolean | `true` | `DRISHTI_BACKEND_DISCOVER` | console | pull the cluster's member list from the server in use, and keep it (&lt;console data dir&gt;/net/servers.json) | restart |  | admin |
| `backend.discover_every` | duration | `60s` |  | console | how often the list is pulled again | restart |  | admin |
| `backend.check_every` | duration | `5s` |  | console | how often each listed server's health is looked at | restart |  | admin |
| `backend.fail_after` | integer | `2` |  | console | failed requests (or health checks) in a row before a server is set aside | restart |  | admin |
| `backend.data_dir` | string | empty | `DRISHTI_CONSOLE_DATA_DIR` | console | what the console keeps between runs (net/servers.json); blank: &lt;data dir&gt;/console | restart |  | admin |

#### `ai`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `ai.enabled` | boolean | `false` | `DRISHTI_AI_ENABLED` | console | turn on the console's own LLM provider layer | restart |  | admin |
| `ai.dir` | string | `./config/ai` | `DRISHTI_AI_DIR` | console | one YAML per provider; the recommended console provider is type: server (keys stay on the server) | restart |  | admin |
| `ai.default_model` | string | empty | `DRISHTI_AI_DEFAULT_MODEL` | console | provider/model, or a model name only one provider has | restart |  | admin |
| `ai.poll_seconds` | integer | `5` |  | console | how often config/ai and config/mcp are looked at: a changed file is picked up with no restart | live |  | expert |
| `ai.redact` | list | `[]` |  | console | field names masked in every prompt (the same names as the server's drishti.security.redact) | restart |  | admin |
| `ai.policy` | map | `{mask_redacted: true, audit: true, per_user_per_minute: 0, per_user_per_day: ...` |  | console | the console's AI policy, as the server's drishti.ai.policy: mask\_redacted, audit, per-user and cost limits, mask\_patterns, per-feature roles | restart |  | admin |

#### `mcp`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `mcp.enabled` | boolean | `false` | `DRISHTI_MCP_ENABLED` | console | turn on the console's MCP client | restart |  | admin |
| `mcp.dir` | string | `./config/mcp` | `DRISHTI_MCP_DIR` | console | the folder of MCP server files (one YAML per server) | restart |  | admin |
| `mcp.server_roles` | map | `{}` |  | console | server -&gt; roles allowed | restart |  | admin |
| `mcp.tool_roles` | map | `{}` |  | console | "server/tool" -&gt; roles allowed | restart |  | admin |
| `mcp.server_features` | map | `{}` |  | console | server -&gt; features allowed | restart |  | admin |

#### `retry`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `retry.enabled` | boolean | `true` | `DRISHTI_RETRY_ENABLED` | console | retry what failed (the console's AI providers and MCP servers); false turns the retries off | restart |  | expert |
| `retry.initial` | duration | `5s` | `DRISHTI_RETRY_INITIAL` | console | wait before the first retry | restart |  | expert |
| `retry.factor` | number | `2.0` |  | console | each further wait is this many times the one before | restart |  | expert |
| `retry.max` | duration | `5m` | `DRISHTI_RETRY_MAX` | console | no wait is longer than this | restart |  | expert |
| `retry.jitter` | number | `0.2` |  | console | each wait varies by up to this fraction either way | restart |  | expert |
| `retry.permanent_after` | integer | `3` |  | console | a failure waiting cannot fix (refused credentials, bad settings) seen this often in a row is retried only every max | restart |  | expert |

#### `collab`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `collab.bridge_test_timeout_seconds` | integer | `30` |  | console | Admin &gt; Collaboration, bridge Test: how long the console waits for the server, which waits for the bridge's own timeout (drishti.collab.bridges.timeout, default 10 s). Keep it longer than that. | restart |  | expert |

#### `views`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `views.refresh_min_seconds` | integer | `5` | `DRISHTI_VIEWS_REFRESH_MIN_SECONDS` | console | The fastest any panel refreshes (refresh: in a Sutra, RACHANA\_REFERENCE.md 9), in seconds: a Sutra or a reader asking for faster gets this. | restart |  | admin |

#### `ui`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `ui.about_prefetch` | boolean | `true` | `DRISHTI_ABOUT_PREFETCH` | console | About this page: fetch the page's explanation once the page is idle, so labels with a glossary entry are underlined before the drawer opens. Costs the server one explain (a re-run of the view, cached for drishti.explain.cache-ttl) per page view; set false on a busy installation and the labels are underlined when the drawer is first opened. | restart |  | expert |
| `ui.designer` | boolean | `false` | `DRISHTI_UI_DESIGNER` | console | The Sutra designer (docs/guides/RACHANA\_REFERENCE.md › Screen designer and the Build workbench, section 31): Customize on every entity page and Describe under Build -&gt; New screen. A user's changes are their own Design, never the pack's Sutra. Off: pages and the screen designer are exactly as before. | restart |  | admin |
| `ui.error_advice` | map | `{…}` |  | console | What an error page advises, by the DRS code the server answered with ({kind} and {id} fill in); "default" for any other | restart |  | admin |
| `ui.studio_example` | string | `all-panels-showcase` |  | console | the example Studio opens on (a name under studio.examples\_dir); blank or missing: the first pack example | restart |  | admin |
| `ui.default_theme` | string | `terminal` |  | console | terminal \| light \| wallstreet \| blue \| green \| crimson \| crimson-dark \| terminal-green \| terminal-crimson \| terminal-blue | restart |  | admin |
| `ui.product` | string | `Drishti` | `DRISHTI_PRODUCT` | console | The product's name and legal notices on every page; nothing user-visible names them in code. The server has the same under drishti.branding. | restart |  | admin |
| `ui.tagline` | string | `Any data. Any domain. One grammar.` |  | console | the line under the product name, in the top bar, the sign-in page and the landing page | restart |  | admin |
| `ui.headline` | string | `See what your data hasn't told you yet.` |  | console | The landing page's headline (blank: the tagline is the headline); its accent is the part drawn in the theme's gradient. When a headline is set, the tagline sits under it as the subtitle. | restart |  | admin |
| `ui.headline_accent` | string | `hasn't told you yet.` |  | console | the part of the headline drawn in the theme's gradient | restart |  | admin |
| `ui.product_native` | string | `दृष्टि` |  | console | the name in its own script, beside it on the About page (blank for none) | restart |  | expert |
| `ui.product_meaning` | string | `Drishti (दृष्टि) means <em>sight</em>.` |  | console | one line on the name's meaning, on the About page | restart |  | admin |
| `ui.copyright` | string | `Copyright © 2026 Ashutosh Sinha. All rights reserved. Proprietary and confide...` |  | console | the copyright line in the footer and on the About page (the server's drishti.branding.copyright) | restart |  | admin |
| `ui.notice` | string | `Unauthorised copying, use or distribution is prohibited.` |  | console | the legal notice beside it | restart |  | admin |
| `ui.owner` | string | `Ashutosh Sinha` |  | console | who holds the rights: the About page's Author (the server's drishti.branding.owner) | restart |  | admin |
| `ui.owner_email` | string | `ajsinha@gmail.com` |  | console | beside the owner on the About page (blank for none) | restart |  | admin |
| `ui.user` | string | `ash` | `DRISHTI_USER` | console | acting user until sign-in lands | restart |  | admin |
| `ui.user_cache_seconds` | integer | `10` |  | console | how long a worker reuses what the server says about a user (settings, packs, layouts, pivots); a change on this console reaches every worker at once through the user stamps (&lt;console data&gt;/user-stamps); this bounds another console behind the same address | restart |  | expert |
| `ui.user_display` | string | `Ash` |  | console | the acting user's display name when auth.enabled is false (local development only) | restart |  | admin |
| `ui.desk` | string | `Rates desk` |  | console | the acting user's desk when auth.enabled is false | restart |  | admin |
| `ui.clock_tz` | string | `America/New_York` |  | console | the top-bar clock's time zone | restart |  | admin |
| `ui.clock_label` | string | `NY` |  | console | the top-bar clock's label | restart |  | admin |
| `ui.landing_examples` | integer | `4` |  | console | The public landing page. Its example commands (the hero animation, "Keyboard first") are the example commands of the packs switched on (each pack's console.examples), so they only name entities that exist; landing\_examples caps them. The showcase is the four screenshots under web/static/img/drishti\_shot-&lt;slug&gt;.png; a caption names its command only when that command is one of the packs' examples (the pictured pack is installed). | restart |  | admin |
| `ui.showcase` | list | empty |  | console | the landing page's pictures, {slug, title, cmd, sutra} each: web/static/img/drishti\_shot-&lt;slug&gt;.png, its caption naming cmd only when that command is one of the packs' examples | restart |  | admin |
| `ui.placeholder` | string | `""` |  | console | the command line's placeholder; blank: config/sitemap.yaml's (an edition's menus.placeholder presets it) | restart |  | admin |
| `ui.logo` | string | `""` |  | console | a logo image (a URL under /static/, or a data: URL) instead of the eye mark, in the top bar and on public pages; blank: the eye | restart |  | admin |
| `ui.favicon` | string | `""` |  | console | the browser icon (a URL); blank: /favicon.ico (the eye) | restart |  | admin |
| `ui.apple_touch_icon` | string | `""` |  | console | the home-screen icon (a URL); blank: the eye's | restart |  | admin |
| `ui.home_cards` | list | empty |  | console | the quick cards under Home's headline, {title, cmd, pack, icon} each; one naming a pack shows only while that pack is on (an edition's home.cards replace them) | restart |  | admin |

#### `ui.phone`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `ui.phone.enabled` | boolean | `true` | `DRISHTI_UI_PHONE` | console | true: a phone or tablet gets the tiered page; false: every screen gets every panel, as before | restart |  | admin |
| `ui.phone.kinds` | map | `{}` |  | console | a panel kind's tier over the built-in one, such as { table: more, map: desk } | restart |  | admin |
| `ui.phone.rules` | list | `[]` |  | console | the site's tiers and caps: \[{ kind: provenance, tier: desk }, { panel: "\*contrib\*", max: more }]; keys panel, kind, title, sutra, entity (globs), tier or max | restart |  | admin |
| `ui.phone.packs` | map | `{}` |  | console | a pack's rules, for the entity kinds it owns: { market-risk: \[{ panel: "\*contrib\*", tier: more }] } | restart |  | admin |
| `ui.phone.pages` | map | `{}` |  | console | the VaR and exposure pages and the landings: { var: { contrib: glance, tiles: { max: glance } } } | restart |  | admin |

#### `ui.facts`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `ui.facts.enabled` | boolean | `true` | `DRISHTI_HOME_FACTS` | console | Home's Did you know tile, the fact of the day for a signed-in user; false: no tile | restart |  | admin |
| `ui.facts.cache_seconds` | integer | `21600` |  | console | how long the facts of one user, business date and set of packs are kept | restart |  | expert |
| `ui.facts.timeout_seconds` | integer | `90` |  | console | a fact taking longer is left out | restart |  | expert |

#### `embed`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `embed.enabled` | boolean | `false` | `DRISHTI_EMBED_ENABLED` | console | serve /embed/ for host applications registered at the server; false: nothing under /embed/ is served | restart |  | admin |
| `embed.origins_ttl_seconds` | integer | `60` |  | console | how long the registered origins (the CORS allow-list, from the server) are reused | restart |  | admin |
| `embed.jwks_ttl_seconds` | integer | `300` |  | console | Console-side checks of the server's embed tokens (ES256, key set from the server's /api/v1/embed/jwks). The server checks every token again on every call; these refuse forgeries early and bound what embedding can cost the console. how long the server's signing keys are reused | restart |  | admin |
| `embed.jwks_min_refetch_seconds` | integer | `10` |  | console | a token naming an unknown key id (rotation) refetches the key set, at most this often | restart |  | admin |
| `embed.audiences` | list | `[]` |  | console | accepted token audiences (the console's public URL); empty accepts what the server issued | restart |  | admin |
| `embed.rate_per_minute` | integer | `600` |  | console | calls per host application per minute (429 DRS-8004 with Retry-After); 0 = unlimited | restart |  | admin |
| `embed.max_streams` | integer | `64` |  | console | live subscriptions all embed channels together hold open (of backend.pool\_size); 0 = unlimited | restart |  | admin |
| `embed.recheck_seconds` | integer | `60` |  | console | an open stream asks the server this often whether its token still passes (user, app, scopes) | restart |  | admin |
| `embed.token_grace_seconds` | integer | `30` |  | console | after a token expires, how long the stream waits for a fresh one before gone DRS-8001 | restart |  | admin |
| `embed.compress_min_bytes` | integer | `512` |  | console | payloads smaller than this are sent uncompressed (br if the browser accepts it and brotli is installed, else gzip) | restart |  | expert |
| `embed.sign_in.enabled` | boolean | `true` | `DRISHTI_EMBED_SIGN_IN` | console | needs auth.oidc.enabled; off: the window answers DRS-8003 and pages keep the token exchange | restart |  | admin |
| `embed.sign_in.redirect_uri` | string | `""` |  | console | default &lt;console&gt;/embed/v2/sign-in/callback: register it at the provider beside auth.oidc.redirect\_uri | restart |  | admin |
| `embed.sign_in.refresh` | boolean | `true` |  | console | renew silently with the provider's refresh token (sealed, in the page's memory) while the SSO session lasts | restart |  | admin |
| `embed.sign_in.max_hours` | integer | `0` |  | console | longest a window sign-in is renewed; 0: auth.session\_hours | restart |  | admin |
| `embed.lifecycle` | map | `{}` |  | console | announce the end of an embed API major: {"1": {deprecation: 2027-01-01, sunset: 2027-07-01, link: https://...}} -&gt; Deprecation and Sunset headers, then 410 DRS-8006 | restart |  | admin |

#### `auth`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `auth.enabled` | boolean | `false` | `DRISHTI_AUTH_ENABLED` | console | off for local development | restart |  | admin |
| `auth.session_secret` | string | empty | `DRISHTI_SESSION_SECRET` | console | &gt;= 32 characters; from the environment only (secret) | restart |  | admin |
| `auth.token_secret` | string | empty | `DRISHTI_TOKEN_SECRET` | console | shared with the server (drishti.security.secret) | restart |  | admin |
| `auth.token_ttl_seconds` | integer | `300` |  | console | lifetime of each token the console mints for the server | restart |  | admin |
| `auth.session_hours` | integer | `10` |  | console | session lifetime (the cookie's and the server session's) | restart |  | admin |
| `auth.recheck_seconds` | integer | `10` | `DRISHTI_SESSION_RECHECK_SECONDS` | console | How long the console reuses the server's answer to "does this session still stand, and who is the user now?" (enabled, roles, password change due). Disabling, demoting or signing out takes effect within this time (at once on the console an administrator used). 0 asks the server on every request. | restart |  | admin |
| `auth.allowed_origins` | list | `[]` |  | console | State-changing requests must come from the console's own pages (Origin or Referer = the request's host). Behind a proxy that changes the Host header, list the public origin(s) here, e.g. \[https://drishti.bank.example]. | restart |  | admin |
| `auth.secure_cookie` | boolean | `true` | `DRISHTI_SECURE_COOKIE` | console | mark cookies Secure (false only for plain-HTTP development with sign-in on) | restart |  | admin |
| `auth.oidc.enabled` | boolean | `false` | `DRISHTI_OIDC_ENABLED` | console | run the single-sign-on flow | restart |  | admin |
| `auth.oidc.issuer` | string | empty | `DRISHTI_OIDC_ISSUER` | console | e.g. https://login.bank.example/realms/staff | restart |  | admin |
| `auth.oidc.client_id` | string | empty | `DRISHTI_OIDC_CLIENT_ID` | console | the OIDC client id | restart |  | admin |
| `auth.oidc.client_secret` | string | empty | `DRISHTI_OIDC_CLIENT_SECRET` | console | from the environment only; empty for a public client (PKCE alone) (secret) | restart |  | admin |
| `auth.oidc.token_auth` | string | `client_secret_basic` |  | console | or client\_secret\_post | restart |  | admin |
| `auth.oidc.scopes` | string | `openid profile email` |  | console | the scopes asked of the provider | restart |  | admin |
| `auth.oidc.label` | string | `Sign in with single sign-on` |  | console | the sign-in button's text | restart |  | admin |
| `auth.oidc.redirect_uri` | string | empty | `DRISHTI_OIDC_REDIRECT_URI` | console | default: &lt;console&gt;/auth/oidc/callback | restart |  | admin |

#### `live`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `live.max_subscriptions` | integer | `32` | `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | console | Subscriptions one browser's channel carries at most (views across all its tabs and workspace panes, the alerts bell, monitors; a view open in several tabs counts once). Each holds one stream to the server from the backend pool (backend.pool\_size). Beyond it a view says it is not live, and why, instead of silently not ticking. | restart |  | admin |
| `live.relay.across_hosts` | boolean | `false` | `DRISHTI_CONSOLE_RELAY_ACROSS_HOSTS` | console | true: consoles on several hosts behind one load balancer pass live-channel calls to each other, so the load balancer needs no stickiness | restart |  | admin |
| `live.relay.advertise` | string | empty | `DRISHTI_CONSOLE_RELAY_ADVERTISE` | console | the address the other consoles reach this one at (blank: this machine's LAN address; never a loopback one) | restart |  | admin |
| `live.relay.bind` | string | `0.0.0.0` | `DRISHTI_CONSOLE_RELAY_BIND` | console | the interface the relay listens on across hosts (within one host it listens on loopback only) | restart |  | admin |
| `live.relay.port` | integer | `17490` | `DRISHTI_CONSOLE_RELAY_PORT` | console | across hosts, worker n listens on port + n (port .. port + server.workers - 1): open these between the consoles; 0 = any free port | restart |  | admin |
| `live.relay.timeout_seconds` | integer | `3` |  | console | how long a worker waits for the holder of a channel to answer | restart |  | expert |
| `live.relay.window_seconds` | integer | `30` |  | console | a relay message is accepted this long after it was signed (allow for the hosts' clock difference); each only once | restart |  | admin |

#### `under_construction`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `under_construction.file` | string | `../config/under-construction.yaml` | `DRISHTI_UNDER_CONSTRUCTION_FILE` | console | the same file the server reads; absent: nothing registered | restart |  | admin |

#### `editions`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `editions.dir` | string | `../config/editions` | `DRISHTI_EDITIONS_DIR` | console | the same folder the server reads (config/editions/&lt;name&gt;.yaml, features.yaml) | restart |  | admin |

#### `lens`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lens.roles` | map | `{}` |  | console | hook for role-based access later ({role: {link: true, start: risk}}): read, not wired | restart |  | admin |

#### `features`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `features.build` | boolean | `true` |  | console | Build: new screens, new packs, designs, reviews and changes (/build, /review) | restart |  | everyday |
| `features.forms` | boolean | `true` |  | console | query by form (FORM, /forms) | restart |  | everyday |
| `features.datamap` | boolean | `true` |  | console | the data map (DATAMAP, /datamap) | restart |  | everyday |
| `features.data` | boolean | `true` |  | console | Export data (EXPORT, /data): every dataset, a simple selection, a day downloaded as .jsonl.gz | restart |  | everyday |
| `features.workspaces` | boolean | `true` |  | console | /w | restart |  | everyday |
| `features.monitors` | boolean | `true` |  | console | /m | restart |  | everyday |
| `features.alerts` | boolean | `true` |  | console | /alerts | restart |  | everyday |
| `features.lakes` | boolean | `true` |  | console | Admin -&gt; Lakes | restart |  | everyday |
| `features.reports` | boolean | `true` |  | console | /reports | restart |  | everyday |
| `features.collaboration` | boolean | `true` |  | console | Admin -&gt; Collaboration | restart |  | everyday |
| `features.examples_dir` | string | `../docs/guides/examples` |  | console | the Rachana examples Studio and the help centre offer (docs/guides/examples/README.md) | restart |  | admin |

#### `builder`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `builder.max_samples` | integer | `50` |  | console | files in one upload, and samples (a .jsonl line is one) in all | restart |  | admin |
| `builder.max_file_mb` | integer | `5` |  | console | largest single file | restart |  | expert |
| `builder.max_total_mb` | integer | `25` |  | console | all files together | restart |  | expert |
| `builder.pack_sample_docs` | integer | `200` |  | console | Build -&gt; New pack: documents the browser keeps per data file and sends (a sample, never the file) | restart |  | admin |
| `builder.pack_read_max_rows` | integer | `500000` |  | console | the most rows per file the page may be told to read (it streams and samples; nothing is held but the sample) | restart |  | admin |
| `builder.pack_read_max_mb` | integer | `1024` |  | console | the most MB per file the page may be told to read | restart |  | expert |
| `builder.pack_max_files` | integer | `200` |  | console | schema and data files in one plan | restart |  | admin |
| `builder.pack_max_docs` | integer | `5000` |  | console | sampled documents in one plan request | restart |  | admin |
| `builder.pack_profile_rows_per_type` | integer | `10000` |  | console | Build -&gt; New pack: rows of each type (value of the split column) the page profiles (fields filled, array sizes); every row is counted | restart |  | admin |
| `builder.pack_load_max_mb` | integer | `2048` |  | console | Admin -&gt; Packs -&gt; a pack -&gt; Load data: the largest file the console streams on to the server (which has drishti.loads.upload-max-mb) | restart |  | expert |
| `builder.pack_design_samples_per_type` | integer | `50` |  | console | documents of each type the page keeps (spread over the file, varied in shape, anonymised) to design that type's Sutra | restart |  | admin |
| `builder.pack_max_schema_kb` | integer | `512` |  | console | largest single schema file | restart |  | admin |
| `builder.pack_max_sutras` | integer | `200` |  | console | Sutras one generated pack may have | restart |  | admin |
| `builder.pack_jobs_per_user` | integer | `3` |  | console | preview jobs one person may have running (or kept) at a time | restart |  | admin |
| `builder.pack_job_ttl_min` | integer | `60` |  | console | minutes a finished preview (and its bundle) is kept | restart |  | admin |
| `builder.pack_draft_concurrency` | integer | `4` |  | console | Sutras drafted at once against the server | restart |  | expert |
| `builder.tools_dir` | string | `../tools` |  | console | the repository's tools/ folder (packbundle.py makes the bundle); relative to drishti-console/ | restart |  | admin |
| `builder.studio_kind` | string | `sample` |  | console | the kind a new Design and "Open in Studio" preview a brought document as (a pasted document has no kind of its own) | restart |  | admin |

#### `lakes`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lakes.roots` | string | empty | `DRISHTI_LAKE_ROOTS` | console | the lake roots this console may write, comma-separated (the Delta connectors' root); empty: &lt;data dir&gt;/delta, as the server | restart |  | admin |
| `lakes.max_upload_mb` | integer | `4096` |  | console | largest file uploaded (streamed to the console's temporary folder, never held in memory) | restart |  | expert |
| `lakes.scan_docs` | integer | `20000` |  | console | documents read to suggest the id, date and classifiers (the build reads them all) | restart |  | admin |
| `lakes.engine` | string | `auto` |  | console | auto (Drishti's Java writer when lakes.jar or the server jar is found, else deltalake), drishti, deltalake | restart |  | admin |
| `lakes.jar` | string | empty | `DRISHTI_JAR` | console | the drishti-server exec jar that carries the writer (default: drishti-server/target) | restart |  | admin |
| `lakes.python` | string | `""` |  | console | the Python that runs tools/lake/build.py (default: the console's own) | restart |  | admin |
| `lakes.keep_min` | integer | `240` |  | console | minutes an upload and a finished build are kept | restart |  | admin |
| `lakes.jobs` | integer | `2` |  | console | builds running at once on this console | restart |  | admin |
| `lakes.data_dir` | string | `../data` | `DRISHTI_DATA_DIR` | console | &lt;data&gt;: Lakes -&gt; Maintain keeps &lt;data&gt;/lakes/lake-maintenance.yaml (also lake maintain's default) and its history there | restart |  | admin |
| `lakes.maintenance_schedules` | boolean | `true` | `DRISHTI_LAKE_MAINTENANCE_SCHEDULES` | console | run the lake-maintenance schedules in this console (false: run lake maintain --daemon instead) | restart |  | admin |

#### `data_view`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `data_view.list_timeout_seconds` | integer | `30` |  | console | how long the dataset list may take (the server reads the dates each dataset holds) | restart |  | expert |
| `data_view.count_timeout_seconds` | integer | `90` |  | console | how long a row count may take (the server's own limit is drishti.data-view.count-budget, 60 s) | restart |  | expert |
| `data_view.download_idle_seconds` | integer | `300` |  | console | the longest pause between two parts of a download before the console gives up on it | restart |  | admin |

#### `form`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `form.collapse_on_submit` | boolean | `true` |  | console | after Search the form folds to one line of its criteria so the results get the room; Show form (Alt+F) brings it back | restart |  | admin |

#### `labs`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `labs.calc` | boolean | `false` | `DRISHTI_LABS_CALC` | console | Calc (Alt+C): Python in your browser on a view's rows, a scratch pad that never publishes; on also needs a role with calc and a pack that enables it | restart |  | admin |

#### `layouts`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layouts.enabled` | boolean | `true` | `DRISHTI_LAYOUTS_ENABLED` | console | false: no view offers it and saved layouts are not applied | restart |  | admin |
| `layouts.min_span` | integer | `3` |  | console | the narrowest a panel can be made in layout mode, in columns of 12 (narrower is unreadable) | restart |  | admin |

#### `help`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `help.docs_dir` | string | `../docs` |  | console | the repository docs rendered as the help centre's reference section | restart |  | admin |
| `help.api_docs_dir` | string | `web/apidocs` | `DRISHTI_API_DOCS_DIR` | console | Help -&gt; Developer (IMPLEMENTATION\_PLAN row 2g): the Java and Python API references tools/api\_docs.py build writes here, one folder per version (generated at build time, never committed); relative to the console folder. Missing: the page says how to build them. | restart |  | admin |
| `help.openapi_url` | string | empty | `DRISHTI_OPENAPI_URL` | console | the address of the server's OpenAPI UI as a browser reaches it (blank: the page names /api/docs/ui on the server) | restart |  | admin |

#### `packs`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `packs.dir` | string | `../config/packs` | `DRISHTI_PACKS_DIR` | console | the same packs directory the server uses (one variable sets both) | restart |  | admin |
| `packs.installed_dir` | string | empty | `DRISHTI_PACKS_INSTALLED` | console | packs installed from the registry or by Deploy archive (the server's drishti.packs.installed-dir); blank: &lt;data dir&gt;/packs/installed | restart |  | admin |
| `packs.extra_dirs` | string | empty | `DRISHTI_PACKS_EXTRA_DIRS` | console | further pack folders, comma-separated (examples/packs: the retired example packs), as on the server | restart |  | admin |
| `packs.enabled` | string | `default` | `DRISHTI_PACKS` | console | used only when the server cannot be asked which packs are enabled; default: config/editions/default-packs.yaml | restart |  | admin |
| `packs.deploy_max_mb` | integer | `50` |  | console | largest pack archive Admin → Packs → Deploy archive takes (the server's drishti.packs.deploy.max-archive-mb refuses the same way, 413) | restart |  | expert |

#### `static`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `static.compress` | boolean | `true` |  | console | answer a request that accepts br or gzip with the compressed file (Brotli when the brotli package is installed); false: bytes as they are | restart |  | admin |
| `static.compress_min_bytes` | integer | `1024` |  | console | smaller files are sent as they are | restart |  | expert |
| `static.brotli_quality` | integer | `5` |  | console | 0 to 11; each file is compressed once per version and kept in cache\_dir | restart |  | admin |
| `static.cache_dir` | string | `""` |  | console | where the compressed copies are kept (empty: the system temp folder, drishti-console-static) | restart |  | expert |

#### `bi`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bi.enabled` | boolean | `true` | `DRISHTI_BI_ENABLED` | console | false: no BI top-bar menu, no /bi pages (the server's drishti.bi.enabled is separate) | restart |  | admin |

#### `bi.live`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bi.live.min_interval_ms` | integer | `250` | `DRISHTI_BI_LIVE_MIN_INTERVAL_MS` | console | a report redraws its live visuals at most this often (a burst of ticks becomes one redraw); 100 to 5000 | restart |  | expert |
| `bi.live.stale_seconds` | integer | `30` | `DRISHTI_BI_LIVE_STALE_SECONDS` | console | no tick or status from the server for this long: the report says "Stale"; 5 to 600 | restart |  | admin |

#### `bi.board`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bi.board.rotate_seconds` | integer | `30` | `DRISHTI_BI_BOARD_ROTATE_SECONDS` | console | a board in kiosk mode turns to its next page after this long, unless the board sets rotate:; 3 to 3600 | restart |  | admin |

#### `bi.notebook`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bi.notebook.max_rows` | integer | `10000` | `DRISHTI_BI_NOTEBOOK_MAX_ROWS` | console | most rows of an extract (the reader's rows, cut here); 10 to 100000 | restart |  | admin |
| `bi.notebook.max_fields` | integer | `60` |  | console | most columns of an extract | restart |  | admin |
| `bi.notebook.sql_max_rows` | integer | `5000` |  | console | most rows a SQL cell returns | restart |  | admin |
| `bi.notebook.sql_max_chars` | integer | `20000` |  | console | longest SQL text | restart |  | admin |
| `bi.notebook.cell_timeout_seconds` | integer | `20` |  | console | a Python cell's time limit on the server's compute pool (also capped by drishti.bi.report.python-max-seconds) | restart |  | expert |
| `bi.notebook.cell_memory_mb` | integer | `512` |  | console | a Python cell's memory on the pool (also capped by drishti.bi.report.python-max-memory-mb) | restart |  | expert |
| `bi.notebook.cell_max_chars` | integer | `65536` |  | console | longest Python cell | restart |  | admin |
| `bi.notebook.max_cells` | integer | `60` |  | console | most cells in a notebook | restart |  | admin |
| `bi.notebook.max_extracts` | integer | `6` |  | console | most further extracts in a notebook (extract("name") in a cell), besides the first | restart |  | admin |

#### `bi.transform`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bi.transform.preview_rows` | integer | `1000` | `DRISHTI_BI_TRANSFORM_PREVIEW_ROWS` | console | rows of each input the step-by-step run starts from (the reader's rows, asked of the server); 10 to 50000 | restart |  | admin |
| `bi.transform.timeout_seconds` | integer | `20` |  | console | the step-by-step run's time limit (also capped by drishti.bi.report.python-max-seconds) | restart |  | expert |
| `bi.transform.memory_mb` | integer | `512` |  | console | its memory (also capped by drishti.bi.report.python-max-memory-mb) | restart |  | expert |
| `bi.transform.snippets_file` | string | `""` |  | console | more data-prep snippets (a list under snippets:), read after config/prep-snippets.yaml | restart |  | admin |

### The console's other files

The console's content files under `drishti-console/config/`: menus, the help centre's catalogue, starter workspaces, the data map's presentation, the comparison page and the data-prep snippets. Their keys come from their schemas (`tools/settings_schemas.yaml`), checked against what the shipped files use.

#### `sitemap.yaml`: the one home of navigation (the top bar, the Admin tabs, the BI rail, Help's sections, the footer)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `menus` | list | `shipped` |  | console | The top bar's menus, in order (core/menus.py draws them; an edition's menus.&lt;menu-id&gt; lists the items it keeps) | live |  | admin |
| `menus[].id` | string | none |  | console | The menu's id (what an edition's menus.&lt;id&gt; names) | live |  | admin |
| `menus[].label` | string | none |  | console | Its name in the top bar | live |  | admin |
| `menus[].icon` | string | none |  | console | Its Bootstrap icon name | live |  | admin |
| `menus[].domain` | string | `core` |  | console | The domain module the menu belongs to (banking, economy; core when not said): the domain split moves its entries | live |  | admin |
| `menus[].also` | list | `[]` |  | console | Items listed in other menus that this menu's strip also draws, by id (the Admin tabs' BI settings and My account, the BI rail's Pivot and Build home), without listing them twice | live |  | admin |
| `menus[].personal` | boolean | `false` |  | console | The person's own menu (My account): never drawn as a top menu | live |  | admin |
| `menus[].subject` | boolean | `false` |  | console | A lens's subject menu (Curves, Economies ...): drawn only where an edition's menus.top names it | live |  | admin |
| `menus[].gather` | list | `none` |  | console | A menu that gathers the columns of other menus by id (the Trading lens's one Markets menu gathers Curves, Volatility, FX and Credit), each drawn as its own column | live |  | admin |
| `menus[].engines` | boolean | `false` |  | console | The Engines menu: a column per compute engine, drawn for the engines running in the cluster that the person's role opens | live |  | admin |
| `menus[].search-over` | number | `4` |  | console | The Engines menu: its search box shows when more engines than this run | live |  | admin |
| `menus[].admin-link` | map | `none` |  | console | The Engines menu: its Admin -&gt; Engines link {href, label, requires} (administrators) | live |  | admin |
| `menus[].admin-link.href` | string | none |  | console | Where it goes (/admin/engines) | live |  | admin |
| `menus[].admin-link.label` | string | none |  | console | Its text | live |  | admin |
| `menus[].admin-link.requires` | list | `[]` |  | console | Conditions that must all hold for it to show (as an item's requires) | live |  | admin |
| `menus[].columns` | list | none |  | console | The menu's columns, each a title and its items | live |  | admin |
| `menus[].columns[].title` | string | none |  | console | A column's heading | live |  | admin |
| `menus[].columns[].domain` | string | `the menu's` |  | console | The domain module of the column's items | live |  | admin |
| `menus[].columns[].engine` | string | `none` |  | console | The Engines menu: the column's engine (its id in config/engines.yaml); drawn only while it runs on a server of the cluster | live |  | admin |
| `menus[].columns[].word` | string | empty |  | console | The Engines menu: the engine's command word, shown in the column's head | live |  | admin |
| `menus[].columns[].opens` | list | `[]` |  | console | The Engines menu: who opens the engine, any of role:&lt;name&gt; or pack:&lt;name&gt; (a pack assigned to the person); administrators always; none: everyone. The engine's command words follow it (left out of the type-ahead, refused when typed); Help does not | live |  | admin |
| `menus[].columns[].items` | list | none |  | console | A column's items | live |  | admin |
| `menus[].columns[].items[].id` | string | none |  | console | The item's id (what an edition lists to keep it) | live |  | admin |
| `menus[].columns[].items[].href` | string | none |  | console | Where it goes (blank with cmd: the command run; with fill: the command line filled) | live |  | admin |
| `menus[].columns[].items[].cmd` | string | empty |  | console | The command it runs (VAR BANK), shown beside its label | live |  | admin |
| `menus[].columns[].items[].fill` | string | empty |  | console | Words it puts in the command line for the person to finish (EXP CPTY), shown beside its label | live |  | admin |
| `menus[].columns[].items[].icon` | string | none |  | console | Its icon | live |  | admin |
| `menus[].columns[].items[].label` | string | none |  | console | Its name ({product} and {tutorials} filled in) | live |  | admin |
| `menus[].columns[].items[].desc` | string | none |  | console | The line under it | live |  | admin |
| `menus[].columns[].items[].match` | string | empty |  | console | The path prefix that marks it current; blank: the href only | live |  | admin |
| `menus[].columns[].items[].tab` | string | `its id` |  | console | The Admin tab it marks current (the page's tab) | live |  | admin |
| `menus[].columns[].items[].rail` | boolean | `true` |  | console | The BI menu: false keeps the item out of the BI rail (the top bar's menu only) | live |  | admin |
| `menus[].columns[].items[].domain` | string | `the column's` |  | console | The domain module of the item | live |  | admin |
| `menus[].columns[].items[].requires` | list | `[]` |  | console | Conditions that must all hold for it to show: admin, bi, bi-admin, designer, edition, feature:&lt;name&gt;, word:&lt;WORD&gt;, admin-page:&lt;id&gt;, pack:&lt;name&gt;, cmd:&lt;WORD&gt;, route:&lt;path&gt; | live |  | admin |
| `footer` | list | `shipped` |  | console | The footer's links: {id, item, label} each, each an item of the menus | live |  | admin |
| `footer[].id` | string | none |  | console | The link's id (what an edition's menus.footer names) | live |  | admin |
| `footer[].item` | string | none |  | console | The menu item it links to, by id (its address and icon) | live |  | admin |
| `footer[].label` | string | `the item's` |  | console | Its shorter text | live |  | admin |
| `help-sections` | list | `shipped` |  | console | Help's sections, in display order: a tile each on the help centre's front door, opening /help/section/&lt;id&gt;; their pages are in help.yaml | live |  | admin |
| `help-sections[].id` | string | none |  | console | The section's id (help.yaml's sections\[].id; an edition's help.categories names it) | live |  | admin |
| `help-sections[].name` | string | none |  | console | Its name | live |  | admin |
| `help-sections[].icon` | string | none |  | console | Its icon | live |  | admin |
| `help-sections[].blurb` | string | none |  | console | Its one line on the tile | live |  | admin |
| `help-sections[].roles` | list | `[]` |  | console | Who sees the section: none, everyone; else administrators and holders of these roles (Internal: administrators) | live |  | admin |
| `help-sections[].domain` | string | `core` |  | console | The domain module the section belongs to | live |  | admin |
| `in-context` | map | `shipped` |  | console | Pages no menu lists because they open from another page: the address and where it is reached from (the sitemap guard holds each to a listed page) | live |  | admin |
| `in-context.<name>` | string | none |  | console | One address and the page it is reached from | live |  | admin |
| `not-pages` | list | `shipped` |  | console | Addresses the console answers that are not pages (data for a page's script, redirects of old addresses, sign-in, health, files) | live |  | admin |
| `placeholder` | string | `shipped` |  | console | The command line's placeholder (ui.placeholder or an edition's menus.placeholder wins) | live |  | admin |

#### `help.yaml`: the help centre's catalogue

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `most-used` | list | `shipped` |  | console | The most used pages, under the search box of the help centre's front door: {slug, label} each (a page the person cannot see is left out) | live |  | admin |
| `most-used[].slug` | string | none |  | console | The page | live |  | admin |
| `most-used[].label` | string | `its title` |  | console | A shorter name on its chip | live |  | admin |
| `aliases` | map | `shipped` |  | console | Old section names to the section that took them over (a pack guide's category, an edition's help.categories, /help#cat-&lt;id&gt;) | live |  | admin |
| `aliases.<name>` | string | none |  | console | One old name and its section's id | live |  | admin |
| `documents` | map | `shipped` |  | console | An unlisted document's section by its top folder under docs/ ("" the top-level \*.md, default any other) | live |  | admin |
| `documents.<name>` | string | none |  | console | One folder and its section's id | live |  | admin |
| `pack-group` | string | `From the packs` |  | console | The group a pack's own guides join, at the end of their section | live |  | admin |
| `sections` | list | `shipped` |  | console | The pages of the help centre's sections, by section id (the sections' order and names: sitemap.yaml help-sections:): a tile each on the front door, opening /help/section/&lt;id&gt; | live |  | admin |
| `sections[].id` | string | none |  | console | The section's id: its name, icon, line and who sees it are the sitemap's (sitemap.yaml help-sections:) | live |  | admin |
| `sections[].groups` | list | none |  | console | Its groups of pages, in order | live |  | admin |
| `sections[].groups[].title` | string | none |  | console | A group's heading | live |  | admin |
| `sections[].groups[].words` | list | `[]` |  | console | Command words shown beside the heading | live |  | admin |
| `sections[].groups[].guides` | list | none |  | console | Its pages, in order; each page belongs to exactly one section | live |  | admin |
| `sections[].groups[].guides[].slug` | string | none |  | console | The page's address, /help/&lt;slug&gt; | live |  | admin |
| `sections[].groups[].guides[].doc` | string | `none` |  | console | A repository document under docs/ rendered as it is; none: the console's own web/guides/&lt;slug&gt;.md | live |  | admin |
| `sections[].groups[].guides[].title` | string | none |  | console | Its title ({product} filled in) | live |  | admin |
| `sections[].groups[].guides[].icon` | string | none |  | console | Its icon | live |  | admin |
| `sections[].groups[].guides[].kind` | string | `guide` |  | console | guide, tutorial or knowledge: the badge shown | live |  | admin |
| `sections[].groups[].guides[].summary` | string | none |  | console | The row's summary, also searched | live |  | admin |
| `sections[].groups[].guides[].packs` | list | `[]` |  | console | The packs it is about: an edition that keeps none of them hides it | live |  | admin |
| `sections[].groups[].guides[].roles` | list | `[]` |  | console | Who may see it: none, everyone; else only an administrator or a person holding one of these roles (others get 404 and do not find it) | live |  | admin |
| `sections[].groups[].guides[].engine` | string | `none` |  | console | The compute engine it is about: left out while it runs on no server (an administrator sees it greyed); open to anyone who can open Help otherwise | live |  | admin |
| `sections[].groups[].guides[].pair` | string | `none` |  | console | The tutorial drawn beside this guide on its row (a page of the same group) | live |  | admin |
| `moved` | map | `shipped` |  | console | Retired addresses: old slug -&gt; slug or slug#anchor, answered with a 301 | live |  | admin |
| `moved.<name>` | string | none |  | console | One retired slug and the page (and section) that took it over | live |  | admin |
| `knowledge` | map | `shipped` |  | console | "What does this mean?" beside a data page: screen or kind -&gt; the Knowledge page or glossary entry that explains it | live |  | admin |
| `knowledge.<name>` | string | none |  | console | One screen or kind and its Knowledge target | live |  | admin |
| `contextual` | map | `shipped` |  | console | F1: screen -&gt; the guide it opens | live |  | admin |
| `contextual.<name>` | string | none |  | console | One screen and its guide's slug | live |  | admin |

#### `workspaces.yaml`: the starter workspaces

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `templates` | map | `{}` |  | console | Core starter workspaces by name (packs add their own in config/packs/&lt;pack&gt;/config/workspaces.yaml) | live |  | admin |
| `templates.*` | map | none |  | console | One starter workspace: its layout and panes, as blank below | live |  | admin |
| `blank` | map | `shipped` |  | console | What a new, blank workspace starts as | live |  | admin |
| `blank.layout` | string | `2col` |  | console | Its layout: 2col, 3col, 2x2 or 1+2 | live |  | admin |
| `blank.panes` | list | `two empty panes` |  | console | 1 to 4 panes, each {ref, follows, title}: the command it shows, the pane it follows, its title | live |  | admin |
| `blank.panes[].ref` | string | `none` |  | console | The command a pane opens with; none: empty | live |  | admin |
| `blank.panes[].follows` | string | `none` |  | console | The pane whose selection it follows | live |  | admin |
| `blank.panes[].title` | string | empty |  | console | The pane's title | live |  | admin |

#### `datamap.yaml`: the data map's presentation

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `domains` | list | `one domain per pack` |  | console | The order domains are drawn in, each domain's name and the packs it holds; a pack not named becomes a domain of its own after these | live |  | admin |
| `domains[].id` | string | none |  | console | The domain's id | live |  | admin |
| `domains[].title` | string | none |  | console | Its name on the map | live |  | admin |
| `domains[].packs` | list | none |  | console | The packs it holds | live |  | admin |
| `computed` | list | `[]` |  | console | Kinds drawn dashed as computed on demand, besides those whose data contract says class: computed | live |  | admin |
| `low-coverage` | number | `0.95` |  | console | A join is a finding when fewer than this share of the ids it holds name an entity that exists | live |  | admin |

#### `prep-snippets.yaml`: the Transform editor's data-prep snippets

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `snippets` | list | `shipped` |  | console | The snippets offered in the Transform editor (bi.transform.snippets\_file adds more) | restart |  | admin |
| `snippets[].id` | string | none |  | console | The snippet's id | restart |  | admin |
| `snippets[].title` | string | none |  | console | Its name in the list | restart |  | admin |
| `snippets[].group` | string | none |  | console | The group it is listed under | restart |  | admin |
| `snippets[].description` | string | none |  | console | What it does | restart |  | admin |
| `snippets[].code` | text | none |  | console | The Python it writes, with {name} for each parameter | restart |  | admin |
| `snippets[].params` | list | none |  | console | Its parameters, each {name, label, kind, default, options} | restart |  | admin |
| `snippets[].params[].name` | string | none |  | console | The parameter's name in the code | restart |  | admin |
| `snippets[].params[].label` | string | none |  | console | Its label in the form | restart |  | admin |
| `snippets[].params[].kind` | string | none |  | console | Its input: column, columns, text, number, choice, ... | restart |  | admin |
| `snippets[].params[].default` | string | none |  | console | Its value until changed | restart |  | admin |
| `snippets[].params[].options` | list | none |  | console | The choices of a choice parameter | restart |  | admin |
| `snippets[].example.*` | map | none |  | console | A worked example the tests run: inputs (tables), params and the expected output | restart |  | admin |

### Connectors: every plugin's settings

A connector's settings go under `settings:` in its file, `config/connectors/<name>.yaml` ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)); the same catalogue builds the form in Admin → Connectors. A changed file restarts that connector only. Every plugin takes the common settings; a plugin marked TLS or proxy also takes the shared `tls.*` or `proxy.*` settings.

#### Every plugin

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `source-name` | string | none |  | every connector | The name documents carry as their provenance (default: the connector's name) | live (the connector restarts) |  | admin |
| `mode.*` | string | none |  | every connector | Per-kind read mode (mode.&lt;kind&gt;) | live (the connector restarts) |  | admin |
| `domain` | string | none |  | every connector | The data domain (folder, schema or key prefix) the connector reads | live (the connector restarts) |  | admin |
| `stale-after` | duration | none |  | every connector | Mark the source stale when it has received nothing for this long (15m, 2h, 1d) | live (the connector restarts) |  | admin |
| `union` | boolean | `false` |  | every connector | This connector holds one slice of its kinds (one source system of several, ids unique across them): reads, searches, type-ahead and BI tables ask every union connector of the kind together | live (the connector restarts) |  | admin |

#### Shared: `tls.*`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `enabled` | boolean | `false` |  | every plugin marked TLS | Use TLS. A connector whose address already says so (amqps://, ssl://) uses it without this; true against a plain address is a start-up error | live (the connector restarts) |  | admin |
| `ca-file` | path | none |  | every plugin marked TLS | Trust: a PEM file of one or many CA certificates, or the PEM text itself | live (the connector restarts) |  | admin |
| `truststore` | path | none |  | every plugin marked TLS | Trust: a PKCS12 or JKS truststore | live (the connector restarts) |  | admin |
| `truststore-password` | string | none |  | every plugin marked TLS | Truststore password: ${ENV} placeholder, or use truststore-password-file (secret) | live (the connector restarts) |  | admin |
| `truststore-password-file` | path | none |  | every plugin marked TLS | File whose first line is the truststore password | live (the connector restarts) |  | admin |
| `truststore-type` | one of PKCS12, JKS | `detected` |  | every plugin marked TLS | Truststore format | live (the connector restarts) |  | admin |
| `trust-jvm-default` | boolean | `true when no ca-file or truststore` |  | every plugin marked TLS | Also trust the JVM's public authorities (true beside ca-file or truststore merges them) | live (the connector restarts) |  | expert |
| `cert-file` | path | none |  | every plugin marked TLS | Client identity (mutual TLS): PEM certificate chain, leaf first | live (the connector restarts) |  | admin |
| `key-file` | path | none |  | every plugin marked TLS | Client identity: PEM private key (PKCS#8, PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8) | live (the connector restarts) |  | admin |
| `key-password` | string | none |  | every plugin marked TLS | Password of an encrypted PEM key or of the key in a keystore: ${ENV} placeholder, or key-password-file (secret) | live (the connector restarts) |  | admin |
| `key-password-file` | path | none |  | every plugin marked TLS | File whose first line is the key password | live (the connector restarts) |  | admin |
| `keystore` | path | none |  | every plugin marked TLS | Client identity: a PKCS12 or JKS keystore (use this or cert-file with key-file) | live (the connector restarts) |  | admin |
| `keystore-password` | string | none |  | every plugin marked TLS | Keystore password: ${ENV} placeholder, or use keystore-password-file (secret) | live (the connector restarts) |  | admin |
| `keystore-password-file` | path | none |  | every plugin marked TLS | File whose first line is the keystore password | live (the connector restarts) |  | admin |
| `keystore-type` | one of PKCS12, JKS | `detected` |  | every plugin marked TLS | Keystore format | live (the connector restarts) |  | admin |
| `key-alias` | string | `the only key` |  | every plugin marked TLS | Which key of the keystore to present (secret) | live (the connector restarts) |  | admin |
| `protocols` | list | `TLSv1.3,TLSv1.2` |  | every plugin marked TLS | Protocol versions offered | live (the connector restarts) |  | admin |
| `cipher-suites` | list | `the JVM's` |  | every plugin marked TLS | Cipher suites offered | live (the connector restarts) |  | admin |
| `verify-hostname` | boolean | `true` |  | every plugin marked TLS | Check the server's name against its certificate (false logs a warning at every start) | live (the connector restarts) |  | admin |
| `insecure-trust-all` | boolean | `false` |  | every plugin marked TLS | Do not check the server certificate; development only, refused unless DRISHTI\_ALLOW\_INSECURE\_TLS=true | live (the connector restarts) |  | admin |

#### Shared: `proxy.*`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `proxy` | string | none |  | every plugin marked proxy | Write 'proxy: none' to go direct for this connector even when the site has a default proxy; or use the proxy.\* block | live (the connector restarts) |  | admin |
| `proxy.url` | string | none |  | every plugin marked proxy | Outbound HTTP proxy, http://host:port (https targets are tunnelled with CONNECT). Absent: the site default (drishti.http.proxy.\*) applies | live (the connector restarts) |  | admin |
| `proxy.username` | string | none |  | every plugin marked proxy | Proxy user (Basic authentication) | live (the connector restarts) |  | admin |
| `proxy.password` | string | none |  | every plugin marked proxy | Proxy password: ${ENV} placeholder, or use proxy.password-file (secret) | live (the connector restarts) |  | admin |
| `proxy.password-file` | path | none |  | every plugin marked proxy | File whose first line is the proxy password | live (the connector restarts) |  | admin |
| `proxy.no-proxy` | list | none |  | every plugin marked proxy | Hosts that bypass the proxy: names, .suffixes, addresses, CIDR blocks (10.0.0.0/8) | live (the connector restarts) |  | admin |
| `proxy.enabled` | boolean | `true` |  | every plugin marked proxy | false switches the proxy off for this connector | live (the connector restarts) |  | admin |

#### `activemq` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `broker-url` | string | `failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay...` |  | `activemq` connectors | OpenWire URL; `failover:(…)` reconnects by itself; `ssl://` endpoints use TLS (ACTIVEMQ\_CONNECTOR.md section 14.2) | live (the connector restarts) |  | admin |
| `destinations` | list | required |  | `activemq` connectors | Comma list: `queue:<name>`, `topic:<name>`, or `<name>` (a queue) | live (the connector restarts) |  | admin |
| `user` | string | none |  | `activemq` connectors | User | live (the connector restarts) |  | admin |
| `password` | string | none |  | `activemq` connectors | Password: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `client-id` | string | `drishti-<source-name>` |  | `activemq` connectors | JMS client id; durable subscriptions are named `drishti-<source-name>-<topic>` | live (the connector restarts) |  | admin |
| `kind` | string | none |  | `activemq` connectors | Kind of the messages (kind.&lt;queue&gt; sets it per queue or topic) | live (the connector restarts) |  | admin |
| `id-field` | string | `id` |  | `activemq` connectors | The body's id field, every destination | live (the connector restarts) |  | admin |
| `kind.*` | string | none |  | `activemq` connectors | Kind per destination: kind.&lt;destination&gt; | live (the connector restarts) |  | admin |
| `id-field.*` | string | none |  | `activemq` connectors | Id field per kind: id-field.&lt;kind&gt; | live (the connector restarts) |  | admin |
| `cache-mb` | integer | `128` |  | `activemq` connectors | Entities kept in memory | live (the connector restarts) |  | expert |
| `state.dir` | path | `<state.root>/<source-name>` |  | `activemq` connectors | Folder of the received-state store; default: &lt;state.root&gt;/&lt;connector name&gt; | live (the connector restarts) |  | admin |
| `state.root` | path | `<data dir>/state` |  | `activemq` connectors | Parent of the per-connector state folder | live (the connector restarts) |  | admin |
| `state.max-gb` | string | `10` |  | `activemq` connectors | This connector's disk budget (decimal allowed); what happens past it is `state.when-full` (ACTIVEMQ\_CONNECTOR.md section 7.3) | live (the connector restarts) |  | admin |
| `state.reset-at` | string | `never` |  | `activemq` connectors | Clock time (HH:mm) to clear the state store each day, or never | live (the connector restarts) |  | admin |
| `state.zone` | string | `America/New_York` |  | `activemq` connectors | Time zone of state.reset-at | live (the connector restarts) |  | admin |
| `state.durability` | one of none, wal, sync | `sync` |  | `activemq` connectors | `sync` (write-ahead log synced on every write: no acknowledged message lost on a crash or a power loss), `wal` (not synced: survives a crash of the process, a power loss can lose the last moments), `none` (no log: a crash can lose up to the 32 MB write buffer) (ACTIVEMQ\_CONNECTOR.md section 7.1) | live (the connector restarts) |  | admin |
| `state.when-full` | one of evict-oldest, warn | `evict-oldest` |  | `activemq` connectors | `evict-oldest`: past the budget, remove the entities written longest ago until under 90% of it, then compact (logged, counted in `evicted`); `warn`: remove nothing, say so in health | live (the connector restarts) |  | admin |
| `state.check-seconds` | integer | `60` |  | `activemq` connectors | How often the store's size is checked against the budget | live (the connector restarts) |  | admin |
| `max-redeliveries` | integer | `-1` |  | `activemq` connectors | Redeliveries of a message the state store could not keep before the broker dead-letters it; `-1`: without limit | live (the connector restarts) |  | admin |

#### `aerospike` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `aerospike` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c | live (the connector restarts) |  | admin |
| `hosts` | string | `localhost:3000` |  | `aerospike` connectors | host:port list, comma separated | live (the connector restarts) |  | admin |
| `namespace` | string | `test` |  | `aerospike` connectors | Namespace | live (the connector restarts) |  | admin |
| `set` | string | none |  | `aerospike` connectors | The data domain's set (`<set>_ix` and `<set>_kinds` beside it) | live (the connector restarts) |  | admin |
| `domain` | string | none |  | `aerospike` connectors | Domain | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `aerospike` connectors | Comma list of kinds to serve | live (the connector restarts) |  | admin |
| `user` | string | none |  | `aerospike` connectors | User (leave out with auth-mode: pki) | live (the connector restarts) |  | admin |
| `password` | string | none |  | `aerospike` connectors | Password: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `auth-mode` | one of internal, external, pki | none |  | `aerospike` connectors | pki logs in with the client certificate (tls.cert-file + tls.key-file, or tls.keystore) | live (the connector restarts) |  | admin |
| `tls-name` | string | none |  | `aerospike` connectors | The name the server certificate carries (the server's tls-name); default: each host's own name | live (the connector restarts) |  | admin |
| `connect-timeout-ms` | integer | `3000` |  | `aerospike` connectors | Connect timeout | live (the connector restarts) |  | expert |
| `pool-size` | integer | `20` |  | `aerospike` connectors | Connections to each node at most (default: drishti.db.pool.size) | live (the connector restarts) |  | expert |
| `pool-min-idle` | integer | `2` |  | `aerospike` connectors | Idle connections kept when quiet (default: drishti.db.pool.min-idle) | live (the connector restarts) |  | expert |
| `pool-idle-timeout` | duration | `10m` |  | `aerospike` connectors | An idle connection beyond pool-min-idle is closed after this long | live (the connector restarts) |  | expert |
| `pool-max-lifetime` | duration | `30m` |  | `aerospike` connectors | A connection is replaced after this long | live (the connector restarts) |  | expert |
| `pool-connection-timeout` | duration | `10s` |  | `aerospike` connectors | How long a caller waits for a free connection before failing | live (the connector restarts) |  | expert |
| `pool-leak-threshold` | duration | `60s` |  | `aerospike` connectors | A connection borrowed (or a command in flight) longer than this is logged with the borrower's stack; 0 = off | live (the connector restarts) |  | expert |
| `statement-timeout` | duration | `5m` |  | `aerospike` connectors | Longest one statement or operation may run; 0 = none | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `60` |  | `aerospike` connectors | How often the kinds' dates and the ids are re-read | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `aerospike` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `aerospike` connectors | The most documents scanned for a reverse lookup on a kind without promoted bins | live (the connector restarts) |  | admin |
| `scan-threads` | integer | `8` |  | `aerospike` connectors | Partition ranges scanned at once when reading a day's columns | live (the connector restarts) |  | expert |
| `columns-cache-mb` | integer | `1024` |  | `aerospike` connectors | Memory for days' column sets | live (the connector restarts) |  | expert |
| `columns-seconds` | integer | `300` |  | `aerospike` connectors | How long a day's column set is kept before it is read again | live (the connector restarts) |  | admin |
| `reverse-index` | boolean | `true` |  | `aerospike` connectors | Build the reverse index | live (the connector restarts) |  | admin |

#### `cassandra` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `cassandra` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c (columns the loader created) | live (the connector restarts) |  | admin |
| `contact-points` | string | none |  | `cassandra` connectors | host:port list, comma separated (port 9042 when left out); required unless secure-connect-bundle is set | live (the connector restarts) |  | admin |
| `local-datacenter` | string | none |  | `cassandra` connectors | The data centre the driver prefers (nodetool status names it; datacenter1 on one node); required unless secure-connect-bundle is set | live (the connector restarts) |  | admin |
| `keyspace` | string | none |  | `cassandra` connectors | The keyspace (default: the domain) | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `cassandra` connectors | Kinds to read (default: the tables the loader made) | live (the connector restarts) |  | admin |
| `username` | string | none |  | `cassandra` connectors | User name | live (the connector restarts) |  | admin |
| `password` | string | none |  | `cassandra` connectors | Password: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `secure-connect-bundle` | path | none |  | `cassandra` connectors | DataStax Astra: the secure connect bundle (.zip); replaces contact-points and local-datacenter | live (the connector restarts) |  | admin |
| `token` | string | none |  | `cassandra` connectors | DataStax Astra: the application token (AstraCS:...): ${ENV\_VAR} (secret) | live (the connector restarts) |  | admin |
| `sigv4` | boolean | `false` |  | `cassandra` connectors | Amazon Keyspaces: SigV4 log-in through AWS's driver plug-in (put it in the plugin folder) with the default AWS credential chain | live (the connector restarts) |  | admin |
| `sigv4-region` | string | none |  | `cassandra` connectors | Amazon Keyspaces: the region (default: AWS\_REGION) | live (the connector restarts) |  | admin |
| `consistency` | one of LOCAL\_QUORUM, LOCAL\_ONE, QUORUM, ONE, TWO, THREE, ALL, EACH\_QUORUM | `LOCAL_QUORUM` |  | `cassandra` connectors | Read consistency; LOCAL\_ONE for speed where staleness is acceptable | live (the connector restarts) |  | admin |
| `connections-per-node` | integer | `2` |  | `cassandra` connectors | Connections to each node (each carries many requests at once) | live (the connector restarts) |  | admin |
| `max-concurrent-reads` | integer | `256` |  | `cassandra` connectors | Requests in flight at most; further ones wait in the driver's queue | live (the connector restarts) |  | expert |
| `request-timeout-ms` | integer | `5000` |  | `cassandra` connectors | Longest one statement may run | live (the connector restarts) |  | expert |
| `connect-timeout-ms` | integer | `5000` |  | `cassandra` connectors | Connect timeout (a cluster not reachable is retried in the background) | live (the connector restarts) |  | expert |
| `speculative-executions` | integer | `0` |  | `cassandra` connectors | Extra copies of a slow read sent to other replicas (0: off; reads are idempotent) | live (the connector restarts) |  | admin |
| `speculative-delay-ms` | integer | `100` |  | `cassandra` connectors | How long a read waits before a speculative copy is sent | live (the connector restarts) |  | expert |
| `page-size` | integer | `5000` |  | `cassandra` connectors | Rows per page when a day's columns or documents are read | live (the connector restarts) |  | expert |
| `scan-threads` | integer | `8` |  | `cassandra` connectors | A day's partitions read at once | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `60` |  | `cassandra` connectors | Re-read interval of the catalogue (kind\_days) and the ids | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `cassandra` connectors | A snapshot kind is read on its newest day at most this many days before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `cassandra` connectors | Documents read at most for a reverse lookup without promoted columns, and ids of an effective kind | live (the connector restarts) |  | admin |
| `columns-cache-mb` | integer | `1024` |  | `cassandra` connectors | Memory for the days' promoted columns | live (the connector restarts) |  | expert |
| `doc-cache-mb` | integer | `64` |  | `cassandra` connectors | Memory for documents read | live (the connector restarts) |  | expert |
| `reverse-index` | boolean | `true` |  | `cassandra` connectors | Answer reverse lookups | live (the connector restarts) |  | admin |

#### `delta` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lake` | string | none |  | `delta` connectors | The lake this connector reads (config/lakes/&lt;lake&gt;.yaml, docs/guides/DATA\_LAKE\_GUIDE.md): root, storage, catalog, table folders, promoted columns and vectors come from its file; with lake set, root, domain, table.&lt;kind&gt; and layout.&lt;kind&gt;.\* are refused here. Not set: the older form (those keys in this file, one warning) | live (the connector restarts) |  | admin |
| `lake-tables` | list | none |  | `delta` connectors | With lake: the lake file's entries this connector reads (trading.trade\_endur), where a kind has several tables; not set: the one table of each kind | live (the connector restarts) |  | admin |
| `root` | path | `<data dir>/delta` |  | `delta` connectors | Lake root (folder, or s3://bucket/prefix); default: the delta folder of the data directory | live (the connector restarts) |  | admin |
| `domain` | string | none |  | `delta` connectors | The data-domain folder under the root; may not contain `..` or start with `/` | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `delta` connectors | Kinds to serve (comma list); not set: every table found under the domain | live (the connector restarts) |  | admin |
| `mode.*` | string | none |  | `delta` connectors | Per-kind read mode | live (the connector restarts) |  | admin |
| `layout.*` | string | none |  | `delta` connectors | Per-kind layout: layout.&lt;kind&gt;.columns (promoted document paths), layout.&lt;kind&gt;.type-ahead (promoted columns whose values the type-ahead also matches: a name finds its id) | live (the connector restarts) |  | admin |
| `table.*` | string | none |  | `delta` connectors | Per-kind table folder under the domain when it is not the kind's name (table.trade: trade\_endur) | live (the connector restarts) |  | admin |
| `hadoop.*` | string | none |  | `delta` connectors | Any Hadoop configuration property (hadoop.fs.s3a.connection.maximum) | live (the connector restarts) |  | admin |
| `engine` | string | `native` | `DRISHTI_DELTA_ENGINE` | `delta` connectors | Reader engine: native (Delta Kernel without Hadoop; Windows), hadoop (also abfs://, gs://) or auto (native on Windows); not set: DRISHTI\_DELTA\_ENGINE, else native | live (the connector restarts) |  | admin |
| `date-column` | string | `business_date` |  | `delta` connectors | Business date column | live (the connector restarts) |  | admin |
| `id-column` | string | `id` |  | `delta` connectors | Entity id column | live (the connector restarts) |  | admin |
| `doc-column` | string | `doc` |  | `delta` connectors | Document column | live (the connector restarts) |  | admin |
| `warm-dates` | integer | `3` |  | `delta` connectors | After start, in the background, each table's newest N dates get their id map and one document read, so the first read of a recent past date is not the slow one (cold about 5x a warm read: 40-70 ms against 10 ms on the samples, more on a large day); the warm-up reads take their own read slots, so a page never waits behind them; `0` turns it off | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `10` |  | `delta` connectors | How often each table's latest version is checked; the type-ahead index is rebuilt every six checks | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `delta` connectors | How far back (days) a snapshot read looks for the newest day on or before the date asked; effective tables have no limit | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `delta` connectors | The largest day loaded whole for reverse lookups when a table has no promoted columns | live (the connector restarts) |  | admin |
| `max-concurrent-reads` | integer | `16` |  | `delta` connectors | Single-document reads at once (each decodes one row group of documents) | live (the connector restarts) |  | expert |
| `max-queued-reads` | integer | `4096` |  | `delta` connectors | Document reads that may wait for a slot; more are refused at once, and a read whose caller has gone leaves the queue | live (the connector restarts) |  | expert |
| `cache-mb` | integer | none |  | `delta` connectors | Memory for whole days of small tables, in MB; not set: the shared cache of every Delta connector, 5 % of the heap; set: a cache of this connector's own | live (the connector restarts) |  | expert |
| `doc-cache-mb` | integer | none |  | `delta` connectors | Memory for recently read documents, in MB; not set: the shared cache, 5 % of the heap; set: a cache of this connector's own | live (the connector restarts) |  | expert |
| `doc-disk-cache.enabled` | boolean | `true` |  | `delta` connectors | Documents already read also kept on disk (RocksDB, one per connector): answered without decompressing Parquet pages, also after a restart | live (the connector restarts) |  | expert |
| `doc-disk-cache.dir` | path | `<data dir>/cache/<connector>/docs` |  | `delta` connectors | Documents read before, in a RocksDB on disk, kept across restarts (DELTA\_CONNECTOR.md section 6.3) | live (the connector restarts) |  | expert |
| `doc-disk-cache.max-gb` | number | `1` |  | `delta` connectors | Its size cap; the oldest files go first | live (the connector restarts) |  | expert |
| `doc-disk-cache.ttl` | duration | `12h` |  | `delta` connectors | Entries written longer ago go (compacted hourly) | live (the connector restarts) |  | expert |
| `doc-disk-cache.reset-at` | string | `02:00` |  | `delta` connectors | Daily clearing time | live (the connector restarts) |  | expert |
| `doc-disk-cache.zone` | string | `America/New_York` |  | `delta` connectors | Zone of the daily clearing | live (the connector restarts) |  | expert |
| `id-map-disk.enabled` | boolean | `true` |  | `delta` connectors | Each table date id map written once per table version and memory-mapped: no heap per id, kept across restarts | live (the connector restarts) |  | admin |
| `id-map-disk.dir` | path | `<data dir>/cache/<connector>/idmaps` |  | `delta` connectors | Id maps written once per table version and date, read through a memory map (DELTA\_CONNECTOR.md section 6.2) | live (the connector restarts) |  | admin |
| `id-map-disk.max-gb` | number | `5` |  | `delta` connectors | The most disk the id map files take; the least recently used beyond it are unmapped, then deleted, never a map in use (mapped, read or written in the last five minutes, a date open in VaR or exposure); a map alone over it is kept and warned about (OPERATIONS.md section 12.3) | live (the connector restarts) |  | admin |
| `id-map-disk.ttl` | duration | none |  | `delta` connectors | Off when empty or 0; at least 24h. Drops a map not used (mapped or read) for that long, give or take 10%; never the latest and previous dates, today, a date open in VaR or exposure (OPERATIONS.md section 12.3) | live (the connector restarts) |  | admin |
| `id-map-disk.idle-release` | duration | `30m` |  | `delta` connectors | A map not read for that long is unmapped (the file stays; the next read maps it again), never while a read is inside it nor for a date open in VaR or exposure: the cap then trims its file when the folder is over max-gb. At least 5m (OPERATIONS.md section 12.3) | live (the connector restarts) |  | admin |
| `columns-cache-mb` | integer | none |  | `delta` connectors | Memory for days' column sets, in MB; not set: the shared cache, 20 % of the heap; set: a cache of this connector's own | live (the connector restarts) |  | expert |
| `id-map-mb` | integer | none |  | `delta` connectors | Memory for days' id maps, in MB; not set: the shared cache, 20 % of the heap; set: a cache of this connector's own | live (the connector restarts) |  | expert |
| `lz4-via-native` | boolean | `true` |  | `delta` connectors | hadoop engine only: read LZ4 files through the native engine's decoder | live (the connector restarts) |  | expert |
| `lz4-decoder` | one of safe, fast | `safe` |  | `delta` connectors | native engine: the LZ4 page decoder, safe (pure Java) or fast (JNI when available) | live (the connector restarts) |  | admin |
| `s3.endpoint` | string | none |  | `delta` connectors | An S3-compatible store (MinIO, Ceph); also sets path-style access and TLS by scheme | live (the connector restarts) |  | admin |
| `s3.region` | string | none |  | `delta` connectors | S3 region | live (the connector restarts) |  | admin |
| `s3.path-style` | boolean | `true` |  | `delta` connectors | Path-style S3 addressing; not set: the native engine uses it only with s3.endpoint, the hadoop engine always | live (the connector restarts) |  | admin |
| `s3.access-key` | string | none |  | `delta` connectors | S3 access key: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `s3.secret-key` | string | none |  | `delta` connectors | S3 secret key: ${ENV\_VAR} or file:/path | live (the connector restarts) |  | admin |
| `index.days` | integer | `1` |  | `delta` connectors | Business days of ids kept for type-ahead (1 = the newest day only) | live (the connector restarts) |  | admin |
| `index.max-memory` | string | `600MB` |  | `delta` connectors | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) | live (the connector restarts) |  | expert |
| `index.warm` | one of background, lazy | `background` |  | `delta` connectors | Build past days in the background after start, or on the first search for them | live (the connector restarts) |  | expert |
| `index.any-recent-day` | boolean | `false` |  | `delta` connectors | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) | live (the connector restarts) |  | admin |
| `index.storage` | one of heap, mmap | `heap` |  | `delta` connectors | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once | live (the connector restarts) |  | admin |
| `index.max-disk` | string | `2GB` |  | `delta` connectors | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap | live (the connector restarts) |  | admin |
| `index.reload-every` | string | `10m` |  | `delta` connectors | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh | live (the connector restarts) |  | admin |
| `index.block-cache` | integer | `16` |  | `delta` connectors | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none | live (the connector restarts) |  | expert |
| `index.kinds.*` | string | none |  | `delta` connectors | Per-kind override of any index setting (index.kinds.&lt;kind&gt;.days) | live (the connector restarts) |  | admin |

#### `demo`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `dirs` | list | none |  | `demo` connectors | Sample folders | live (the connector restarts) |  | admin |
| `ticking` | boolean | `true` |  | `demo` connectors | Tick values for live demos (documents marked live move while someone watches) | live (the connector restarts) |  | admin |
| `tick-ms` | integer | `400` |  | `demo` connectors | Tick interval, in milliseconds | live (the connector restarts) |  | expert |

#### `duckdb`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `duckdb` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c | live (the connector restarts) |  | admin |
| `path` | path | required |  | `duckdb` connectors | The DuckDB file; connectors with the same path share one instance | live (the connector restarts) |  | admin |
| `table` | string | none |  | `duckdb` connectors | `schema.entities`, the data domain this connector serves | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `duckdb` connectors | Comma list of kinds to serve | live (the connector restarts) |  | admin |
| `memory-limit` | string | `1GB` |  | `duckdb` connectors | DuckDB's memory limit for the shared instance (native memory, outside the heap); the first connector on a file sets it | live (the connector restarts) |  | expert |
| `threads` | integer | none |  | `duckdb` connectors | DuckDB's threads for the shared instance; the first connector on a file sets it | live (the connector restarts) |  | admin |
| `pool-size` | integer | `4` |  | `duckdb` connectors | Reads of this connector at once; at least `scan-threads` plus the concurrent reads you expect | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `10` |  | `duckdb` connectors | How often the file is checked for a new load (one `stat` when nothing changed) | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `duckdb` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `duckdb` connectors | The most documents a reverse lookup reads for a kind without promoted link columns | live (the connector restarts) |  | admin |
| `scan-threads` | integer | `4` |  | `duckdb` connectors | Id ranges of a day read at once | live (the connector restarts) |  | expert |
| `columns-cache-mb` | integer | `1024` |  | `duckdb` connectors | Memory for days of promoted columns | live (the connector restarts) |  | expert |
| `columns-seconds` | integer | `300` |  | `duckdb` connectors | How long a day's columns are kept (a new file clears them at once) | live (the connector restarts) |  | admin |
| `reverse-index` | boolean | `true` |  | `duckdb` connectors | Build the reverse index | live (the connector restarts) |  | admin |
| `index.days` | integer | `1` |  | `duckdb` connectors | Business days of ids kept for type-ahead (1 = the newest day only) | live (the connector restarts) |  | admin |
| `index.max-memory` | string | `600MB` |  | `duckdb` connectors | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) | live (the connector restarts) |  | expert |
| `index.warm` | one of background, lazy | `background` |  | `duckdb` connectors | Build past days in the background after start, or on the first search for them | live (the connector restarts) |  | expert |
| `index.any-recent-day` | boolean | `false` |  | `duckdb` connectors | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) | live (the connector restarts) |  | admin |
| `index.storage` | one of heap, mmap | `heap` |  | `duckdb` connectors | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once | live (the connector restarts) |  | admin |
| `index.max-disk` | string | `2GB` |  | `duckdb` connectors | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap | live (the connector restarts) |  | admin |
| `index.reload-every` | string | `10m` |  | `duckdb` connectors | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh | live (the connector restarts) |  | admin |
| `index.block-cache` | integer | `16` |  | `duckdb` connectors | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none | live (the connector restarts) |  | expert |
| `index.kinds.*` | string | none |  | `duckdb` connectors | Per-kind override of any index setting (index.kinds.&lt;kind&gt;.days) | live (the connector restarts) |  | admin |

#### `feed` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `url` | string | none |  | `feed` connectors | An override; `file:` URLs are read from disk; for `us-treasury` and `fred` a comma list (one per month, or one per series in the order of `series`) | live (the connector restarts) |  | admin |
| `feed` | string | none |  | `feed` connectors | `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury` or `fred`; without it the plugin stays idle, an unknown value fails to start | live (the connector restarts) |  | admin |
| `series` | list | `DGS10DFF` |  | `feed` connectors | FRED series ids, comma separated (`fred` only); each becomes `FIX-FRED-<series>` | live (the connector restarts) |  | admin |
| `api-key` | string | none |  | `feed` connectors | API key: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `user-agent` | string | `public-data-feed-connector` |  | `feed` connectors | The `User-Agent` header; the pack sets `<product> public data feed connector` | live (the connector restarts) |  | admin |
| `refresh-minutes` | integer | `60` |  | `feed` connectors | Minutes between the end of one fetch and the start of the next (a whole number); the first fetch is at start | live (the connector restarts) |  | admin |
| `timeout-seconds` | integer | `20` |  | `feed` connectors | The request timeout of each HTTP request (a whole number); the connect timeout is a fixed 10 s | live (the connector restarts) |  | expert |

#### `file`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `file` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c | live (the connector restarts) |  | admin |
| `root` | path | `<data dir>/feeds` |  | `file` connectors | Folder of documents; default: the feeds folder of the data directory | live (the connector restarts) |  | admin |
| `domain` | string | none |  | `file` connectors | Domain | live (the connector restarts) |  | admin |
| `id-field` | string | `id` |  | `file` connectors | Field holding the entity id | live (the connector restarts) |  | admin |
| `rescan-seconds` | integer | `30` |  | `file` connectors | How often the folders are listed again | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `file` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `business-zone` | string | `America/New_York` |  | `file` connectors | Time zone whose today is the business date the health check compares the folders with (the 0-rows warning) | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `file` connectors | Lines a reverse lookup reads for a kind without promoted link fields | live (the connector restarts) |  | admin |
| `max-document-mb` | integer | `64` |  | `file` connectors | The longest line (and so document, or string in it); a longer line is skipped and counted | live (the connector restarts) |  | expert |
| `max-nesting-depth` | integer | `1000` |  | `file` connectors | The deepest nesting of objects and arrays in a line; a deeper line is skipped and counted | live (the connector restarts) |  | admin |
| `index-cache-mb` | integer | `1024` |  | `file` connectors | Index cache size | live (the connector restarts) |  | expert |
| `index.days` | integer | `1` |  | `file` connectors | Business days of ids kept for type-ahead (1 = the newest day only) | live (the connector restarts) |  | admin |
| `index.max-memory` | string | `600MB` |  | `file` connectors | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) | live (the connector restarts) |  | expert |
| `index.warm` | one of background, lazy | `background` |  | `file` connectors | Build past days in the background after start, or on the first search for them | live (the connector restarts) |  | expert |
| `index.any-recent-day` | boolean | `false` |  | `file` connectors | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) | live (the connector restarts) |  | admin |
| `index.storage` | one of heap, mmap | `heap` |  | `file` connectors | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once | live (the connector restarts) |  | admin |
| `index.max-disk` | string | `2GB` |  | `file` connectors | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap | live (the connector restarts) |  | admin |
| `index.reload-every` | string | `10m` |  | `file` connectors | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh | live (the connector restarts) |  | admin |
| `index.block-cache` | integer | `16` |  | `file` connectors | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none | live (the connector restarts) |  | expert |
| `index.kinds.*` | string | none |  | `file` connectors | Per-kind override of any index setting (index.kinds.&lt;kind&gt;.days) | live (the connector restarts) |  | admin |

#### `iceberg` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lake` | string | none |  | `iceberg` connectors | The lake this connector reads (config/lakes/&lt;lake&gt;.yaml, docs/guides/DATA\_LAKE\_GUIDE.md): root, storage, catalog, table folders, promoted columns and vectors come from its file; with lake set, root, domain, table.&lt;kind&gt; and layout.&lt;kind&gt;.\* are refused here. Not set: the older form (those keys in this file, one warning) | live (the connector restarts) |  | admin |
| `lake-tables` | list | none |  | `iceberg` connectors | With lake: the lake file's entries this connector reads (trading.trade\_endur), where a kind has several tables; not set: the one table of each kind | live (the connector restarts) |  | admin |
| `layout.*` | string | none |  | `iceberg` connectors | Per-kind layout: layout.&lt;kind&gt;.columns (promoted document paths), layout.&lt;kind&gt;.vectors (list&lt;double&gt; columns such as a P&amp;L vector, read in bulk and put back into documents) | live (the connector restarts) |  | admin |
| `doc-disk-cache.enabled` | boolean | `true` |  | `iceberg` connectors | Documents already read also kept on disk (RocksDB, one per connector), keyed by the date's files: answered without reading a data file, also after a restart | live (the connector restarts) |  | expert |
| `doc-disk-cache.dir` | path | `<data dir>/cache/<connector>/docs` |  | `iceberg` connectors | Its folder | live (the connector restarts) |  | expert |
| `doc-disk-cache.max-gb` | number | `1` |  | `iceberg` connectors | Its size cap; the oldest files go first | live (the connector restarts) |  | expert |
| `doc-disk-cache.ttl` | duration | `12h` |  | `iceberg` connectors | Entries written longer ago go (compacted hourly) | live (the connector restarts) |  | expert |
| `doc-disk-cache.reset-at` | string | `02:00` |  | `iceberg` connectors | Daily clearing time | live (the connector restarts) |  | expert |
| `doc-disk-cache.zone` | string | `America/New_York` |  | `iceberg` connectors | Zone of the daily clearing | live (the connector restarts) |  | expert |
| `id-map-disk.enabled` | boolean | `true` |  | `iceberg` connectors | Each business date's id map written once per date's files and memory-mapped: no heap per id, kept across restarts and commits of other dates | live (the connector restarts) |  | admin |
| `id-map-disk.dir` | path | `<data dir>/cache/<connector>/idmaps` |  | `iceberg` connectors | each date's id map written once and memory-mapped, kept across restarts | live (the connector restarts) |  | admin |
| `id-map-disk.max-gb` | number | `5` |  | `iceberg` connectors | The most disk the id map files take; the least recently used beyond it are unmapped, then deleted, never a map in use (mapped, read or written in the last five minutes, a date open in VaR or exposure); a map alone over it is kept and warned about (OPERATIONS.md section 12.3) | live (the connector restarts) |  | admin |
| `id-map-disk.ttl` | duration | none |  | `iceberg` connectors | Off when empty or 0; at least 24h. Drops a map not used (mapped or read) for that long, give or take 10%; never the latest and previous dates, today, a date open in VaR or exposure (OPERATIONS.md section 12.3) | live (the connector restarts) |  | admin |
| `id-map-disk.idle-release` | duration | `30m` |  | `iceberg` connectors | A map not read for that long is unmapped (the file stays; the next read maps it again), never while a read is inside it nor for a date open in VaR or exposure: the cap then trims its file when the folder is over max-gb. At least 5m (OPERATIONS.md section 12.3) | live (the connector restarts) |  | admin |
| `id-map-mb` | integer | `1024` |  | `iceberg` connectors | Id maps kept in the heap (MB) | live (the connector restarts) |  | expert |
| `doc-cache-mb` | integer | `256` |  | `iceberg` connectors | Documents kept in the heap (MB) | live (the connector restarts) |  | expert |
| `columns-cache-mb` | integer | `1024` |  | `iceberg` connectors | Promoted columns kept in the heap (MB) | live (the connector restarts) |  | expert |
| `cache-mb` | integer | `512` |  | `iceberg` connectors | Whole small days kept for reverse lookups (MB) | live (the connector restarts) |  | expert |
| `source-name` | string | none |  | `iceberg` connectors | The name the connector's documents carry as their source | live (the connector restarts) |  | admin |
| `table.*` | string | none |  | `iceberg` connectors | Per-kind table name under the namespace when it is not the kind's name (table.trade: trade\_calypso) | live (the connector restarts) |  | admin |
| `engine` | one of native, library, auto | `native` |  | `iceberg` connectors | native: drishti-iceberg, no Hadoop (local disk and S3; Windows without winutils.exe; left to the default, a root it cannot reach is read by the library); library: Apache Iceberg Java over Hadoop (also Azure, Google Cloud Storage, HDFS); auto: native on Windows, library elsewhere. DRISHTI\_ICEBERG\_ENGINE sets it for every connector | live (the connector restarts) |  | admin |
| `metadata-location.*` | string | none |  | `iceberg` connectors | A table named by its metadata file, read-only (metadata-location.&lt;kind&gt;: s3://…/v12.metadata.json); native engine | live (the connector restarts) |  | admin |
| `relocate` | string | none |  | `iceberg` connectors | `native`: `<old folder> -> <new folder>; …` reads a table copied elsewhere (an S3 table synced to a laptop, a folder moved after loading: Health gives the pair to copy, ICEBERG\_CONNECTOR.md section 14). Each side is a plain folder (`/data/ice1`, `C:\lake` or `C:/lake`, `\\fs\share\lake`) or a URI (`file:/data/ice1`, `file:///C:/lake`, `s3://bucket/wh`; `s3a://` and `s3n://` are the same bucket): both are read into the `file:` form the metadata names its files in, so the plain form and the `file:` form are the same pair. A folder matches whole folders (`/data/ice1` is not `/data/ice10`). Quote the value in YAML when it holds a backslash: `relocate: 'C:\old -> D:\new'`. A pair that cannot be read fails the start, naming it. The library engine does not read it, and Health says so | live (the connector restarts) |  | admin |
| `vended-credentials` | boolean | `true` |  | `iceberg` connectors | `native`, `rest`: ask the catalog for each table's own S3 credentials (`X-Iceberg-Access-Delegation`), renewed before they expire (secret) | live (the connector restarts) |  | admin |
| `lz4-decoder` | one of safe, fast | `safe` |  | `iceberg` connectors | The LZ4 page decoder of the native engine: safe (pure Java) or fast (JNI when present) | live (the connector restarts) |  | admin |
| `hadoop.*` | string | none |  | `iceberg` connectors | Any Hadoop configuration property (hadoop.fs.s3a.connection.maximum) | live (the connector restarts) |  | admin |
| `catalog.*` | string | none |  | `iceberg` connectors | Any Iceberg catalog property (catalog.&lt;property&gt;) | live (the connector restarts) |  | admin |
| `warehouse` | string | none |  | `iceberg` connectors | REST catalog warehouse | live (the connector restarts) |  | admin |
| `credential` | string | none |  | `iceberg` connectors | REST catalog credential: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `root` | path | none |  | `iceberg` connectors | `hadoop`: the lake, a folder or `s3a://…` (`abfs://`, `gs://`, `hdfs://`) | live (the connector restarts) |  | admin |
| `uri` | string | none |  | `iceberg` connectors | `rest`: the catalog's URI | live (the connector restarts) |  | admin |
| `catalog` | string | `hadoop` |  | `iceberg` connectors | `hadoop` (path-based tables), `rest`, or `s3tables` (AWS S3 Tables: its Iceberg REST endpoint, the settings from `table-bucket-arn`) | live (the connector restarts) |  | admin |
| `table-bucket-arn` | string | none |  | `iceberg` connectors | `s3tables`: the table bucket, `arn:aws:s3tables:<region>:<account>:bucket/<name>`; the endpoint, SigV4 signing (`s3tables`), region, warehouse and REST prefix follow from it, the domain is the namespace and a kind is read from its table with underscores (`pnl-vector` is `pnl_vector`). Blank: the connector stays idle. Native engine only | live (the connector restarts) |  | admin |
| `namespace` | string | none |  | `iceberg` connectors | `rest`: the namespace (dots separate levels) | live (the connector restarts) |  | admin |
| `domain` | string | none |  | `iceberg` connectors | Domain | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `iceberg` connectors | Comma list of kinds to serve | live (the connector restarts) |  | admin |
| `mode.*` | string | none |  | `iceberg` connectors | Per-kind read mode | live (the connector restarts) |  | admin |
| `refresh-seconds` | integer | `10` |  | `iceberg` connectors | How often each table's current snapshot is checked | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `iceberg` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `iceberg` connectors | The largest day read whole for reverse lookups when a table has no promoted columns | live (the connector restarts) |  | admin |
| `max-concurrent-reads` | integer | `16` |  | `iceberg` connectors | Single-document reads at once | live (the connector restarts) |  | expert |
| `max-queued-reads` | integer | `4096` |  | `iceberg` connectors | Document reads that may wait for a slot; more are refused at once, and a read whose caller has gone leaves the queue | live (the connector restarts) |  | expert |
| `layout-cache` | integer | `64` |  | `iceberg` connectors | Layout cache entries | live (the connector restarts) |  | expert |
| `reverse-index` | boolean | `true` |  | `iceberg` connectors | Build the reverse index | live (the connector restarts) |  | admin |
| `s3.endpoint` | string | none |  | `iceberg` connectors | Object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) | live (the connector restarts) |  | admin |
| `s3.region` | string | none |  | `iceberg` connectors | Object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) | live (the connector restarts) |  | admin |
| `s3.path-style` | boolean | `true` |  | `iceberg` connectors | Path-style S3 addressing for S3A and S3FileIO | live (the connector restarts) |  | admin |
| `s3.access-key` | string | none |  | `iceberg` connectors | Object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) (secret) | live (the connector restarts) |  | admin |
| `s3.secret-key` | string | none |  | `iceberg` connectors | Object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) (secret) | live (the connector restarts) |  | admin |
| `index.days` | integer | `1` |  | `iceberg` connectors | Business days of ids kept for type-ahead (1 = the newest day only) | live (the connector restarts) |  | admin |
| `index.max-memory` | string | `600MB` |  | `iceberg` connectors | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) | live (the connector restarts) |  | expert |
| `index.warm` | one of background, lazy | `background` |  | `iceberg` connectors | Build past days in the background after start, or on the first search for them | live (the connector restarts) |  | expert |
| `index.any-recent-day` | boolean | `false` |  | `iceberg` connectors | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) | live (the connector restarts) |  | admin |
| `index.storage` | one of heap, mmap | `heap` |  | `iceberg` connectors | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once | live (the connector restarts) |  | admin |
| `index.max-disk` | string | `2GB` |  | `iceberg` connectors | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap | live (the connector restarts) |  | admin |
| `index.reload-every` | string | `10m` |  | `iceberg` connectors | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh | live (the connector restarts) |  | admin |
| `index.block-cache` | integer | `16` |  | `iceberg` connectors | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none | live (the connector restarts) |  | expert |
| `index.kinds.*` | string | none |  | `iceberg` connectors | Per-kind override of any index setting (index.kinds.&lt;kind&gt;.days) | live (the connector restarts) |  | admin |

#### `iceberg-external` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `tables.*` | string | none |  | `iceberg-external` connectors | Per kind: tables.&lt;kind&gt;.table (namespace.table), id (columns, joined by id-separator), id-separator (:), date, date-from (day), date-zone (UTC), mode (snapshot\|effective), document (row\|column:&lt;name&gt;), columns.exclude, columns.rename.&lt;column&gt;, links.&lt;column&gt; (a kind), depth (2); or tables: auto with namespace | live (the connector restarts) |  | admin |
| `tables` | string | none |  | `iceberg-external` connectors | auto: every table of the namespace is a kind (its name in lower case with -), its id from the identifier fields, its date from the partition spec | live (the connector restarts) |  | admin |
| `engine` | one of native, auto | `auto` |  | `iceberg-external` connectors | `native` or `auto`: both read through drishti-iceberg; `library` is refused (it is the `iceberg` connector's only) | live (the connector restarts) |  | admin |
| `root` | path | none |  | `iceberg-external` connectors | Path-based tables' root (a folder or s3://…); tables.&lt;kind&gt;.table names the folder under it | live (the connector restarts) |  | admin |
| `uri` | string | none |  | `iceberg-external` connectors | A REST catalog's URI (Polaris, Snowflake Open Catalog, Glue, Unity, Nessie, Lakekeeper) | live (the connector restarts) |  | admin |
| `catalog` | string | none |  | `iceberg-external` connectors | hadoop (path-based, the default), rest, or s3tables (AWS S3 Tables, the settings from table-bucket-arn) | live (the connector restarts) |  | admin |
| `table-bucket-arn` | string | none |  | `iceberg-external` connectors | `s3tables`: the table bucket's ARN (arn:aws:s3tables:&lt;region&gt;:&lt;account&gt;:bucket/&lt;name&gt;); the REST settings follow from it | live (the connector restarts) |  | admin |
| `warehouse` | string | none |  | `iceberg-external` connectors | REST catalog warehouse | live (the connector restarts) |  | admin |
| `credential` | string | none |  | `iceberg-external` connectors | REST catalog OAuth2 client credentials (id:secret): ${ENV\_VAR} or file:/path | live (the connector restarts) |  | admin |
| `token` | string | none |  | `iceberg-external` connectors | A fixed REST catalog bearer token: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `scope` | string | none |  | `iceberg-external` connectors | The OAuth2 scope (catalog) | live (the connector restarts) |  | admin |
| `oauth2-server-uri` | string | none |  | `iceberg-external` connectors | Where OAuth2 tokens come from (default: the catalog's /v1/oauth/tokens) | live (the connector restarts) |  | admin |
| `prefix` | string | none |  | `iceberg-external` connectors | The REST catalog's path prefix (default: what /v1/config says) | live (the connector restarts) |  | admin |
| `namespace` | string | none |  | `iceberg-external` connectors | The namespace of tables: auto (dots separate levels) | live (the connector restarts) |  | admin |
| `catalog.*` | string | none |  | `iceberg-external` connectors | Iceberg catalog properties (catalog.rest.sigv4-enabled, catalog.rest.signing-region, catalog.rest.signing-name) | live (the connector restarts) |  | admin |
| `metadata-location.*` | string | none |  | `iceberg-external` connectors | A table named by its metadata file, read as kind &lt;kind&gt; (metadata-location.&lt;kind&gt;) | live (the connector restarts) |  | admin |
| `relocate` | string | none |  | `iceberg-external` connectors | Read tables copied elsewhere: '&lt;old prefix&gt; -&gt; &lt;new prefix&gt;; …' | live (the connector restarts) |  | admin |
| `vended-credentials` | boolean | `true` |  | `iceberg-external` connectors | Ask a REST catalog for each table's own S3 credentials (secret) | live (the connector restarts) |  | admin |
| `decimals` | one of number, string | `number` |  | `iceberg-external` connectors | Decimals in documents as JSON numbers, or as text keeping full precision | live (the connector restarts) |  | admin |
| `binary` | one of base64, omit | `base64` |  | `iceberg-external` connectors | Fixed and binary columns in documents as Base64 text, or left out | live (the connector restarts) |  | admin |
| `id-index-mb` | integer | `1024` |  | `iceberg-external` connectors | Memory for the id indexes of tables not sorted by their id (about 60 B per id; least-used days dropped first) | live (the connector restarts) |  | expert |
| `id-index-warn-rows` | integer | `5000000` |  | `iceberg-external` connectors | Health suggests sorting a table by its id when one day's id index passes this | live (the connector restarts) |  | admin |
| `max-index-rows` | integer | `10000000` |  | `iceberg-external` connectors | Most rows type-ahead and column reads take from one day (or a table with no date); partial beyond it | live (the connector restarts) |  | admin |
| `refresh-seconds` | integer | `10` |  | `iceberg-external` connectors | How often each table's current snapshot is checked | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `iceberg-external` connectors | How far back a snapshot kind looks for the newest date on or before the one asked | live (the connector restarts) |  | admin |
| `doc-cache-mb` | integer | `256` |  | `iceberg-external` connectors | Memory for recently read documents | live (the connector restarts) |  | expert |
| `columns-cache-mb` | integer | `1024` |  | `iceberg-external` connectors | Memory for a day's searchable columns | live (the connector restarts) |  | expert |
| `max-concurrent-reads` | integer | `16` |  | `iceberg-external` connectors | Documents read at once at most | live (the connector restarts) |  | expert |
| `max-queued-reads` | integer | `4096` |  | `iceberg-external` connectors | Document reads that may wait for a slot; more are refused at once, and a read whose caller has gone leaves the queue | live (the connector restarts) |  | expert |
| `source-name` | string | `iceberg-external` |  | `iceberg-external` connectors | The connector's name in provenance (iceberg-external) | live (the connector restarts) |  | admin |
| `s3.endpoint` | string | none |  | `iceberg-external` connectors | S3-compatible endpoint | live (the connector restarts) |  | admin |
| `s3.region` | string | none |  | `iceberg-external` connectors | S3 region | live (the connector restarts) |  | admin |
| `s3.path-style` | boolean | `true` |  | `iceberg-external` connectors | Path-style S3 addressing | live (the connector restarts) |  | admin |
| `s3.access-key` | string | none |  | `iceberg-external` connectors | S3 access key: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `s3.secret-key` | string | none |  | `iceberg-external` connectors | S3 secret key: ${ENV\_VAR} or file:/path | live (the connector restarts) |  | admin |
| `s3.read-block-kb` | integer | `1024` |  | `iceberg-external` connectors | The smallest ranged GET | live (the connector restarts) |  | admin |

#### `jdbc` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `jdbc` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c | live (the connector restarts) |  | admin |
| `url` | string | required |  | `jdbc` connectors | JDBC URL, e.g. jdbc:postgresql://host:5432/db | live (the connector restarts) |  | admin |
| `user` | string | none |  | `jdbc` connectors | Database user | live (the connector restarts) |  | admin |
| `password` | string | none |  | `jdbc` connectors | Database password: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `table` | string | none |  | `jdbc` connectors | `schema.table`; setting it turns table mode on | live (the connector restarts) |  | admin |
| `kind-column` | string | `kind` |  | `jdbc` connectors | Column holding the kind | live (the connector restarts) |  | admin |
| `id-column` | string | `id` |  | `jdbc` connectors | Column holding the entity id | live (the connector restarts) |  | admin |
| `doc-column` | string | `doc` |  | `jdbc` connectors | Column holding the JSON document | live (the connector restarts) |  | admin |
| `date-column` | string | `business_date` |  | `jdbc` connectors | Column holding the business date | live (the connector restarts) |  | admin |
| `json-columns` | list | none |  | `jdbc` connectors | Columns that hold JSON | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `jdbc` connectors | Comma list of kinds to serve | live (the connector restarts) |  | admin |
| `zone` | string | none |  | `jdbc` connectors | Time zone of the date column | live (the connector restarts) |  | admin |
| `pool-size` | integer | `20` |  | `jdbc` connectors | Connections at most (the site default `drishti.db.pool.size`); at least `scan-threads` plus the concurrent reads you expect. All the pool settings are read too | live (the connector restarts) |  | expert |
| `pool-min-idle` | integer | `2` |  | `jdbc` connectors | Idle connections kept when quiet (default: drishti.db.pool.min-idle) | live (the connector restarts) |  | expert |
| `pool-idle-timeout` | duration | `10m` |  | `jdbc` connectors | An idle connection beyond pool-min-idle is closed after this long | live (the connector restarts) |  | expert |
| `pool-max-lifetime` | duration | `30m` |  | `jdbc` connectors | A connection is replaced after this long | live (the connector restarts) |  | expert |
| `pool-connection-timeout` | duration | `10s` |  | `jdbc` connectors | How long a caller waits for a free connection before failing | live (the connector restarts) |  | expert |
| `pool-leak-threshold` | duration | `60s` |  | `jdbc` connectors | A connection borrowed (or a command in flight) longer than this is logged with the borrower's stack; 0 = off | live (the connector restarts) |  | expert |
| `statement-timeout` | duration | `5m` |  | `jdbc` connectors | Longest one statement or operation may run; 0 = none | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `60` |  | `jdbc` connectors | How often the catalogue is read again | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `jdbc` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `jdbc` connectors | The most documents a reverse lookup reads for a kind without promoted link columns | live (the connector restarts) |  | admin |
| `scan-threads` | integer | `4` |  | `jdbc` connectors | Id ranges of a day read at once | live (the connector restarts) |  | expert |
| `columns-cache-mb` | integer | `1024` |  | `jdbc` connectors | Memory for days of promoted columns | live (the connector restarts) |  | expert |
| `columns-seconds` | integer | `300` |  | `jdbc` connectors | How long a day's columns are kept (a new load clears them at once) | live (the connector restarts) |  | admin |
| `reverse-index` | boolean | `true` |  | `jdbc` connectors | Build the reverse index | live (the connector restarts) |  | admin |
| `query.*` | string | none |  | `jdbc` connectors | Custom SQL per purpose (query.trade, ...) | live (the connector restarts) |  | admin |
| `ids.*` | string | none |  | `jdbc` connectors | A day's ids (and optionally a title) for type-ahead: ids.&lt;kind&gt;, SQL with :asOf | live (the connector restarts) |  | admin |
| `columns.*` | string | none |  | `jdbc` connectors | A day's id and promoted fields, one row per entity: columns.&lt;kind&gt;, SQL with :asOf | live (the connector restarts) |  | admin |
| `reverse.*` | string | none |  | `jdbc` connectors | Ids of the kind's entities that reference :target: reverse.&lt;kind&gt; | live (the connector restarts) |  | admin |
| `part-shape.*` | string | none |  | `jdbc` connectors | part-shape.&lt;kind&gt;.&lt;part&gt;: object takes the first row of a part query as an object | live (the connector restarts) |  | admin |
| `index.days` | integer | `1` |  | `jdbc` connectors | Business days of ids kept for type-ahead (1 = the newest day only) | live (the connector restarts) |  | admin |
| `index.max-memory` | string | `600MB` |  | `jdbc` connectors | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) | live (the connector restarts) |  | expert |
| `index.warm` | one of background, lazy | `background` |  | `jdbc` connectors | Build past days in the background after start, or on the first search for them | live (the connector restarts) |  | expert |
| `index.any-recent-day` | boolean | `false` |  | `jdbc` connectors | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) | live (the connector restarts) |  | admin |
| `index.storage` | one of heap, mmap | `heap` |  | `jdbc` connectors | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once | live (the connector restarts) |  | admin |
| `index.max-disk` | string | `2GB` |  | `jdbc` connectors | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap | live (the connector restarts) |  | admin |
| `index.reload-every` | string | `10m` |  | `jdbc` connectors | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh | live (the connector restarts) |  | admin |
| `index.block-cache` | integer | `16` |  | `jdbc` connectors | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none | live (the connector restarts) |  | expert |
| `index.kinds.*` | string | none |  | `jdbc` connectors | Per-kind override of any index setting (index.kinds.&lt;kind&gt;.days) | live (the connector restarts) |  | admin |

#### `kafka` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bootstrap-servers` | string | `localhost:9092` |  | `kafka` connectors | Broker list host:port,... | live (the connector restarts) |  | admin |
| `topics` | list | required |  | `kafka` connectors | Comma list; without it the plugin stays idle (`PluginNotConfigured`) | live (the connector restarts) |  | admin |
| `kind` | string | none |  | `kafka` connectors | Mapped messages on every topic: their kind | live (the connector restarts) |  | admin |
| `id-field` | string | `id` |  | `kafka` connectors | Mapped messages without a key: the id field, every topic | live (the connector restarts) |  | admin |
| `kind.*` | string | none |  | `kafka` connectors | Kind per topic: kind.&lt;topic&gt; | live (the connector restarts) |  | admin |
| `id-field.*` | string | none |  | `kafka` connectors | Id field per kind: id-field.&lt;kind&gt; | live (the connector restarts) |  | admin |
| `mode` | one of state, ticks | `state` |  | `kafka` connectors | `state` (index, caches, reads) or `ticks` (no reads; pushes to views of its kinds) | live (the connector restarts) |  | admin |
| `search` | boolean | `true` |  | `kafka` connectors | Keep ids for type-ahead (state mode only) | live (the connector restarts) |  | admin |
| `poll-ms` | integer | `200` |  | `kafka` connectors | Poll interval | live (the connector restarts) |  | expert |
| `cache-mb` | integer | `256` |  | `kafka` connectors | Memory cache, weighed by message length | live (the connector restarts) |  | expert |
| `disk-cache.*` | string | none |  | `kafka` connectors | On-disk cache (enabled, dir, max-gb, ...) | live (the connector restarts) |  | expert |
| `client.*` | string | none |  | `kafka` connectors | Any Kafka client property (client.max.poll.records); applied last, wins over everything above. Fetches default to `fetch.max.bytes` 4 MB and `max.partition.fetch.bytes` 1 MB (Kafka's own default is 50 MB; each poll thread reads a fetch through a temporary direct buffer): `client.fetch.max.bytes` and `client.max.partition.fetch.bytes` raise them | live (the connector restarts) |  | admin |
| `value-format` | one of string, confluent | none |  | `kafka` connectors | string (default), or confluent to read the Confluent wire format (implied by schema-registry.url) | live (the connector restarts) |  | admin |
| `flavour` | one of apache, confluent | `apache` |  | `kafka` connectors | confluent: Confluent Cloud defaults (SASL PLAIN with api-key and api-secret, DNS lookup of all addresses) | live (the connector restarts) |  | admin |
| `security.protocol` | one of PLAINTEXT, SSL, SASL\_PLAINTEXT, SASL\_SSL | none |  | `kafka` connectors | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`. Absent: `SASL_SSL` if `sasl.mechanism` is set and TLS is wanted (`tls.enabled: true`), `SASL_PLAINTEXT` with a mechanism and no TLS, `SSL` with `tls.enabled`, else `PLAINTEXT`. Always write it down in production. | live (the connector restarts) |  | admin |
| `sasl.mechanism` | one of PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER, GSSAPI | none |  | `kafka` connectors | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `OAUTHBEARER` or `GSSAPI`. | live (the connector restarts) |  | admin |
| `sasl.username` | string | none |  | `kafka` connectors | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. | live (the connector restarts) |  | admin |
| `sasl.password` | string | none |  | `kafka` connectors | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. | live (the connector restarts) |  | admin |
| `sasl.password-file` | path | none |  | `kafka` connectors | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. | live (the connector restarts) |  | admin |
| `api-key` | string | none |  | `kafka` connectors | Confluent Cloud API key (same as sasl.username) (secret) | live (the connector restarts) |  | admin |
| `api-secret` | string | none |  | `kafka` connectors | Confluent Cloud API secret (same as sasl.password): ${ENV} placeholder or api-secret-file | live (the connector restarts) |  | admin |
| `api-secret-file` | path | none |  | `kafka` connectors | File whose first line is the Confluent Cloud API secret | live (the connector restarts) |  | admin |
| `sasl.oauth.token-endpoint` | string | none |  | `kafka` connectors | OAUTHBEARER: the identity provider's token URL. (secret) | live (the connector restarts) |  | admin |
| `sasl.oauth.client-id` | string | none |  | `kafka` connectors | OAUTHBEARER: the client-credentials pair. | live (the connector restarts) |  | admin |
| `sasl.oauth.client-secret` | string | none |  | `kafka` connectors | OAUTHBEARER client secret: ${ENV} placeholder or sasl.oauth.client-secret-file | live (the connector restarts) |  | admin |
| `sasl.oauth.client-secret-file` | path | none |  | `kafka` connectors | File whose first line is the OAUTHBEARER client secret | live (the connector restarts) |  | admin |
| `sasl.oauth.scope` | string | none |  | `kafka` connectors | OAUTHBEARER: the scope to request. | live (the connector restarts) |  | admin |
| `sasl.kerberos.principal` | string | none |  | `kafka` connectors | GSSAPI: e.g. `drishti@BANK.EXAMPLE`. | live (the connector restarts) |  | admin |
| `sasl.kerberos.keytab` | path | none |  | `kafka` connectors | GSSAPI keytab file | live (the connector restarts) |  | admin |
| `sasl.kerberos.service-name` | string | `kafka` |  | `kafka` connectors | GSSAPI broker service name | live (the connector restarts) |  | admin |
| `sasl.kerberos.use-ticket-cache` | boolean | `false` |  | `kafka` connectors | GSSAPI: use the ticket cache instead of a keytab | live (the connector restarts) |  | expert |
| `sasl.kerberos.krb5-conf` | path | none |  | `kafka` connectors | GSSAPI: path of `krb5.conf` (sets `java.security.krb5.conf` if the JVM has none yet). | live (the connector restarts) |  | admin |
| `schema-registry.url` | string | none |  | `kafka` connectors | Confluent Schema Registry URL (http:// or https://); reads Avro, Protobuf and JSON Schema payloads | live (the connector restarts) |  | admin |
| `schema-registry.basic-auth` | string | none |  | `kafka` connectors | KEY:SECRET for the registry: ${SR\_KEY}:${SR\_SECRET}, or schema-registry.basic-auth-file | live (the connector restarts) |  | admin |
| `schema-registry.basic-auth-file` | path | none |  | `kafka` connectors | File whose first line is KEY:SECRET | live (the connector restarts) |  | admin |
| `schema-registry.bearer-token` | string | none |  | `kafka` connectors | Bearer token for the registry (not with basic-auth): ${ENV} placeholder or schema-registry.bearer-token-file (secret) | live (the connector restarts) |  | admin |
| `schema-registry.bearer-token-file` | path | none |  | `kafka` connectors | File whose first line is the bearer token | live (the connector restarts) |  | admin |
| `schema-registry.timeout-ms` | integer | `10000` |  | `kafka` connectors | Connect and read timeout for a registry call. | live (the connector restarts) |  | expert |

#### `mongodb` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `mongodb` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c | live (the connector restarts) |  | admin |
| `uri` | string | required |  | `mongodb` connectors | Connection string: hosts, replica set, credentials, TLS, compressors | live (the connector restarts) |  | admin |
| `database` | string | `drishti` |  | `mongodb` connectors | Database | live (the connector restarts) |  | admin |
| `collection` | string | none |  | `mongodb` connectors | The domain's collection (`<collection>_columns` beside it) | live (the connector restarts) |  | admin |
| `domain` | string | none |  | `mongodb` connectors | Domain | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `mongodb` connectors | Comma list of kinds to serve | live (the connector restarts) |  | admin |
| `doc-format` | string | none |  | `mongodb` connectors | Document format | live (the connector restarts) |  | admin |
| `read-preference` | string | `primary` |  | `mongodb` connectors | `primary`, `primaryPreferred`, `secondary`, `secondaryPreferred` or `nearest` | live (the connector restarts) |  | admin |
| `auth-mechanism` | one of x509 | none |  | `mongodb` connectors | x509 logs in with the client certificate (needs tls.enabled and tls.cert-file + tls.key-file, or tls.keystore) | live (the connector restarts) |  | admin |
| `x509-user` | string | none |  | `mongodb` connectors | The certificate subject to log in as (default: the server derives it from the certificate) | live (the connector restarts) |  | admin |
| `connect-timeout-ms` | integer | `3000` |  | `mongodb` connectors | Connection and server-selection timeout | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `60` |  | `mongodb` connectors | How often the kinds' dates and the ids are re-read | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `mongodb` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `mongodb` connectors | The most documents read for a reverse lookup on a kind without promoted fields | live (the connector restarts) |  | admin |
| `batch-size` | integer | `5000` |  | `mongodb` connectors | Documents per cursor batch for day reads | live (the connector restarts) |  | expert |
| `batch` | integer | none |  | `mongodb` connectors | Batch | live (the connector restarts) |  | expert |
| `read-threads` | integer | `8` |  | `mongodb` connectors | Id ranges read at once when a day's columns are read | live (the connector restarts) |  | expert |
| `in-flight` | integer | none |  | `mongodb` connectors | Reads in flight | live (the connector restarts) |  | admin |
| `heavy-reads` | integer | `2` |  | `mongodb` connectors | Day reads at once per connector | live (the connector restarts) |  | admin |
| `ttl-days` | integer | none |  | `mongodb` connectors | Days to keep | live (the connector restarts) |  | admin |
| `columns-seconds` | integer | `300` |  | `mongodb` connectors | After this a day's column set is re-read in the background (the last read is served meanwhile) | live (the connector restarts) |  | admin |
| `columns-cache-mb` | integer | `1024` |  | `mongodb` connectors | Memory for days' column sets | live (the connector restarts) |  | expert |
| `reverse-index` | boolean | `true` |  | `mongodb` connectors | Build the reverse index | live (the connector restarts) |  | admin |

#### `news` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `base-url` | string | `https://news.google.com/rss/search` |  | `news` connectors | RSS search address (default Google News) | live (the connector restarts) |  | admin |
| `language` | string | `en` |  | `news` connectors | Edition language, e.g. en | live (the connector restarts) |  | admin |
| `country` | string | `US` |  | `news` connectors | Edition country (`gl`, and `ceid=<country>:<language>`) | live (the connector restarts) |  | admin |
| `user-agent` | string | none |  | `news` connectors | User-Agent header | live (the connector restarts) |  | admin |
| `query-template` | string | `{name}` |  | `news` connectors | The search text; `{name}` is the entity name. `"{name}" when:7d` narrows to the last week | live (the connector restarts) |  | admin |
| `id-prefix` | string | `NEWS-` |  | `news` connectors | Prefix of entity ids (NEWS-) | live (the connector restarts) |  | admin |
| `kind` | string | `news` |  | `news` connectors | Kind served (news) | live (the connector restarts) |  | admin |
| `relevance` | string | `title` |  | `news` connectors | title: keep headlines naming the entity or an alias; off: keep all | live (the connector restarts) |  | admin |
| `allow-sources` | list | none |  | `news` connectors | Comma list: only these publishers (case-insensitive) | live (the connector restarts) |  | admin |
| `deny-sources` | list | none |  | `news` connectors | Comma list: never these publishers | live (the connector restarts) |  | admin |
| `aliases.*` | string | none |  | `news` connectors | Other names of an entity: aliases.&lt;Name\_with\_underscores&gt;: a, b | live (the connector restarts) |  | admin |
| `max-items` | integer | `10` |  | `news` connectors | Headlines per entity (1 to 100) | live (the connector restarts) |  | admin |
| `timeout-seconds` | integer | `10` |  | `news` connectors | One HTTP attempt (1 to 120) | live (the connector restarts) |  | expert |
| `cache-ttl-seconds` | integer | `600` |  | `news` connectors | How long an entity's headlines are reused; `0` never reuses | live (the connector restarts) |  | expert |
| `cache-max-entities` | integer | `200` |  | `news` connectors | Entities cached | live (the connector restarts) |  | expert |
| `max-bytes` | integer | `2000000` |  | `news` connectors | Largest response accepted | live (the connector restarts) |  | expert |
| `retries` | integer | `2` |  | `news` connectors | Repeats of a failed request | live (the connector restarts) |  | admin |
| `retry-initial` | string | `300ms` |  | `news` connectors | First wait before a repeat (300ms) | live (the connector restarts) |  | expert |
| `retry-max` | string | `3s` |  | `news` connectors | Longest wait (3s) | live (the connector restarts) |  | expert |
| `retry-factor` | string | `2` |  | `news` connectors | Growth of the wait (2) | live (the connector restarts) |  | expert |
| `retry-jitter` | string | `0.2` |  | `news` connectors | Random spread of each wait, so many failing reads do not repeat together | live (the connector restarts) |  | expert |

#### `rabbitmq` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `uri` | string | `amqp://guest:guest@localhost:5672/%2f` |  | `rabbitmq` connectors | AMQP URI with user, password, host, port and vhost; `amqps://` for TLS (port 5671 by default) | live (the connector restarts) |  | admin |
| `auth-mechanism` | one of plain, external | `plain` |  | `rabbitmq` connectors | external logs in with the client certificate (needs amqps:// and tls.cert-file or tls.keystore) | live (the connector restarts) |  | admin |
| `queues` | list | required |  | `rabbitmq` connectors | Comma list of queues to consume; empty means the connector connects and consumes nothing | live (the connector restarts) |  | expert |
| `bind.*` | string | none |  | `rabbitmq` connectors | Queue bindings | live (the connector restarts) |  | admin |
| `kind` | string | none |  | `rabbitmq` connectors | Kind of the messages (kind.&lt;queue&gt; sets it per queue or topic) | live (the connector restarts) |  | admin |
| `id-field` | string | `id` |  | `rabbitmq` connectors | The body's id field, for every queue with a kind | live (the connector restarts) |  | admin |
| `kind.*` | string | none |  | `rabbitmq` connectors | Kind per queue: kind.&lt;queue&gt; | live (the connector restarts) |  | admin |
| `id-field.*` | string | none |  | `rabbitmq` connectors | Id field per kind: id-field.&lt;kind&gt; | live (the connector restarts) |  | admin |
| `cache-mb` | integer | `128` |  | `rabbitmq` connectors | Entities kept in memory | live (the connector restarts) |  | expert |
| `state.dir` | path | `<state.root>/<source-name>` |  | `rabbitmq` connectors | Folder of the received-state store; default: &lt;state.root&gt;/&lt;connector name&gt; | live (the connector restarts) |  | admin |
| `state.root` | path | `<data dir>/state` |  | `rabbitmq` connectors | Parent of the per-connector state folder | live (the connector restarts) |  | admin |
| `state.max-gb` | string | `10` |  | `rabbitmq` connectors | This connector's disk budget (decimal values accepted); what happens past it is `state.when-full` | live (the connector restarts) |  | admin |
| `state.reset-at` | string | `never` |  | `rabbitmq` connectors | Clock time (HH:mm) to clear the state store each day, or never | live (the connector restarts) |  | admin |
| `state.zone` | string | `America/New_York` |  | `rabbitmq` connectors | Time zone of state.reset-at | live (the connector restarts) |  | admin |
| `state.durability` | one of none, wal, sync | `sync` |  | `rabbitmq` connectors | `sync` (write-ahead log synced on every write: no acknowledged message lost on a crash or a power loss), `wal` (not synced: survives a crash of the process, a power loss can lose the last moments), `none` (no log: a crash can lose up to the 32 MB memtable) (RABBITMQ\_CONNECTOR.md section 6.1) | live (the connector restarts) |  | admin |
| `state.when-full` | one of evict-oldest, warn | `evict-oldest` |  | `rabbitmq` connectors | `evict-oldest`: past the budget, remove the entities written longest ago until under 90% of it, then compact (logged, counted in `evicted`); `warn`: remove nothing, say so in health (RABBITMQ\_CONNECTOR.md section 6.3) | live (the connector restarts) |  | admin |
| `state.check-seconds` | integer | `60` |  | `rabbitmq` connectors | How often the store's size is checked against the budget | live (the connector restarts) |  | admin |
| `declare` | boolean | `true` |  | `rabbitmq` connectors | Declare each queue durable (no arguments) and bind it; `false` uses existing queues and ignores `bind.*` | live (the connector restarts) |  | admin |
| `prefetch` | integer | `100` |  | `rabbitmq` connectors | Unacknowledged messages in flight per consumer (`basicQos`) | live (the connector restarts) |  | expert |
| `heartbeat-seconds` | integer | `20` |  | `rabbitmq` connectors | The requested AMQP heartbeat | live (the connector restarts) |  | admin |
| `recovery-interval-ms` | integer | `2000` |  | `rabbitmq` connectors | The automatic recovery's wait between reconnection attempts | live (the connector restarts) |  | expert |

#### `redis` (TLS)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `layout.*` | string | none |  | `redis` connectors | Per-kind promoted columns: layout.&lt;kind&gt;.columns: a, b, c | live (the connector restarts) |  | admin |
| `uri` | string | required |  | `redis` connectors | Lettuce URI, e.g. `redis://localhost:6379`; `rediss://` for TLS, `redis://user:secret@host:port/db` for credentials; several comma-separated for Cluster seed nodes | live (the connector restarts) |  | admin |
| `user` | string | none |  | `redis` connectors | User | live (the connector restarts) |  | admin |
| `password` | string | none |  | `redis` connectors | Password: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `cluster` | boolean | `false` |  | `redis` connectors | `true` for Redis Cluster (also implied by several URIs) | live (the connector restarts) |  | admin |
| `domain` | string | `drishti` |  | `redis` connectors | Domain | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `redis` connectors | Comma list of kinds to serve | live (the connector restarts) |  | admin |
| `codec` | string | none |  | `redis` connectors | Document codec | live (the connector restarts) |  | admin |
| `live` | boolean | `true` |  | `redis` connectors | Subscribe to `<domain>:changes` and push changed entities to open views | live (the connector restarts) |  | admin |
| `fail-fast` | boolean | `true` |  | `redis` connectors | While health says Redis is down, reads and column reads fail at once (`DRS-1003`, a message without host or credentials) instead of each waiting `timeout-ms`; `false` to always wait | live (the connector restarts) |  | admin |
| `timeout-ms` | integer | `5000` |  | `redis` connectors | Connect and command timeout | live (the connector restarts) |  | expert |
| `pool-size` | integer | `20` |  | `redis` connectors | Most connections open at once (default: drishti.db.pool.size); more are opened only when the open ones are busy | live (the connector restarts) |  | expert |
| `pool-min-idle` | integer | `2` |  | `redis` connectors | Idle connections kept when quiet (default: drishti.db.pool.min-idle) | live (the connector restarts) |  | expert |
| `pool-idle-timeout` | duration | `10m` |  | `redis` connectors | An idle connection beyond pool-min-idle is closed after this long | live (the connector restarts) |  | expert |
| `pool-leak-threshold` | duration | `60s` |  | `redis` connectors | A connection borrowed (or a command in flight) longer than this is logged with the borrower's stack; 0 = off | live (the connector restarts) |  | expert |
| `pool-pipeline-depth` | integer | `32` |  | `redis` connectors | Commands in flight on every open connection before another is opened | live (the connector restarts) |  | expert |
| `refresh-seconds` | integer | `60` |  | `redis` connectors | How often the kinds' days, the ids and `updated` are re-read (also on every announced day) | live (the connector restarts) |  | admin |
| `lookback-days` | integer | `10` |  | `redis` connectors | How far back a snapshot read looks for the newest day on or before the date asked | live (the connector restarts) |  | admin |
| `max-load-rows` | integer | `200000` |  | `redis` connectors | The most documents read for a reverse lookup on a kind without promoted fields | live (the connector restarts) |  | admin |
| `threads` | integer | none |  | `redis` connectors | Threads | live (the connector restarts) |  | admin |
| `in-flight` | integer | none |  | `redis` connectors | Reads in flight | live (the connector restarts) |  | admin |
| `heavy-reads` | integer | `2` |  | `redis` connectors | Days' column sets read at once | live (the connector restarts) |  | admin |
| `ttl-days` | integer | none |  | `redis` connectors | Days to keep | live (the connector restarts) |  | admin |
| `level` | integer | none |  | `redis` connectors | Compression level | live (the connector restarts) |  | admin |
| `chunk-rows` | integer | none |  | `redis` connectors | Rows per chunk | live (the connector restarts) |  | expert |
| `dict-kb` | integer | none |  | `redis` connectors | Dictionary size | live (the connector restarts) |  | admin |
| `dict-samples` | integer | none |  | `redis` connectors | Dictionary samples | live (the connector restarts) |  | admin |
| `recheck-ms` | integer | `1000` |  | `redis` connectors | How often a down Redis is asked again (`PING`); reads resume at its first answer | live (the connector restarts) |  | expert |
| `keep-replaced-seconds` | integer | none |  | `redis` connectors | Keep replaced data this long | live (the connector restarts) |  | admin |
| `columns-cache-mb` | integer | `1024` |  | `redis` connectors | Memory for days' column sets | live (the connector restarts) |  | expert |
| `reverse-index` | boolean | `true` |  | `redis` connectors | Build the reverse index | live (the connector restarts) |  | admin |

#### `rest` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `base-url` | string | required |  | `rest` connectors | The service root; trailing `/` are removed. Empty: the connector fails to start | live (the connector restarts) |  | admin |
| `path` | string | `/{kind}/{id}` |  | `rest` connectors | Appended to `base-url`; `{kind}` and `{id}` are replaced by the URL-encoded kind and id | live (the connector restarts) |  | admin |
| `kinds` | list | none |  | `rest` connectors | Comma list of kinds served; a connector's own `kinds:` list takes precedence | live (the connector restarts) |  | admin |
| `generation-header` | string | `ETag` |  | `rest` connectors | Response header holding a numeric version (quotes removed); otherwise the fetch time in milliseconds | live (the connector restarts) |  | admin |
| `timeout-ms` | integer | `2000` |  | `rest` connectors | Connect timeout and request timeout, in milliseconds | live (the connector restarts) |  | expert |
| `header.*` | string | none |  | `rest` connectors | A request header: header.&lt;Name&gt; (header.Authorization: Bearer ${SERVICE\_TOKEN}) | live (the connector restarts) |  | admin |

#### `s3` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bucket` | string | required |  | `s3` connectors | Bucket | live (the connector restarts) |  | admin |
| `prefix` | string | none |  | `s3` connectors | Key prefix | live (the connector restarts) |  | admin |
| `endpoint` | string | none |  | `s3` connectors | S3-compatible endpoint | live (the connector restarts) |  | admin |
| `region` | string | none |  | `s3` connectors | Region | live (the connector restarts) |  | admin |
| `path-style` | boolean | `true` |  | `s3` connectors | Path-style addressing (with an endpoint; AWS itself: virtual-hosted) | live (the connector restarts) |  | admin |
| `access-key` | string | none |  | `s3` connectors | Access key: ${ENV\_VAR} or file:/path (secret) | live (the connector restarts) |  | admin |
| `secret-key` | string | none |  | `s3` connectors | Secret key: ${ENV\_VAR} or file:/path | live (the connector restarts) |  | admin |
| `rescan-seconds` | integer | none |  | `s3` connectors | Rescan interval | live (the connector restarts) |  | admin |
| `lookback-days` | integer | none |  | `s3` connectors | Days of history to index | live (the connector restarts) |  | admin |
| `cache-entries` | integer | none |  | `s3` connectors | Cache entries | live (the connector restarts) |  | expert |
| `cache-seconds` | integer | none |  | `s3` connectors | Cache lifetime | live (the connector restarts) |  | expert |
| `index.days` | integer | `1` |  | `s3` connectors | Business days of ids kept for type-ahead (1 = the newest day only) | live (the connector restarts) |  | admin |
| `index.max-memory` | string | `600MB` |  | `s3` connectors | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) | live (the connector restarts) |  | expert |
| `index.warm` | one of background, lazy | `background` |  | `s3` connectors | Build past days in the background after start, or on the first search for them | live (the connector restarts) |  | expert |
| `index.any-recent-day` | boolean | `false` |  | `s3` connectors | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) | live (the connector restarts) |  | admin |
| `index.storage` | one of heap, mmap | `heap` |  | `s3` connectors | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once | live (the connector restarts) |  | admin |
| `index.max-disk` | string | `2GB` |  | `s3` connectors | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap | live (the connector restarts) |  | admin |
| `index.reload-every` | string | `10m` |  | `s3` connectors | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh | live (the connector restarts) |  | admin |
| `index.block-cache` | integer | `16` |  | `s3` connectors | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none | live (the connector restarts) |  | expert |
| `index.kinds.*` | string | none |  | `s3` connectors | Per-kind override of any index setting (index.kinds.&lt;kind&gt;.days) | live (the connector restarts) |  | admin |

#### `yfinance` (TLS, proxy)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `base-url` | string | `https://query1.finance.yahoo.com/v8/finance/chart` |  | `yfinance` connectors | Chart endpoint without the symbol | live (the connector restarts) |  | admin |
| `user-agent` | string | none |  | `yfinance` connectors | The `User-Agent` header; Yahoo refuses an empty one | live (the connector restarts) |  | admin |
| `range` | string | `6mo` |  | `yfinance` connectors | History span: 1mo, 3mo, 6mo, 1y, 5y, ytd, max ... | live (the connector restarts) |  | admin |
| `interval` | string | `1d` |  | `yfinance` connectors | Bar size: `1m 2m 5m 15m 30m 60m 90m 1h 1d 5d 1wk 1mo 3mo` (Yahoo limits intraday bars to short ranges) | live (the connector restarts) |  | expert |
| `id-prefix` | string | `QUOTE-` |  | `yfinance` connectors | Prefix of entity ids (QUOTE-) | live (the connector restarts) |  | admin |
| `benchmark` | string | none |  | `yfinance` connectors | Symbol the price-history analytics compute beta against (SPY) | live (the connector restarts) |  | admin |
| `kind` | string | `quote` |  | `yfinance` connectors | Kind served (quote) | live (the connector restarts) |  | admin |
| `timeout-seconds` | integer | `10` |  | `yfinance` connectors | One HTTP attempt (1 to 120) | live (the connector restarts) |  | expert |
| `cache-ttl-seconds` | integer | `60` |  | `yfinance` connectors | How long a symbol's answer is reused; `0` never reuses | live (the connector restarts) |  | expert |
| `cache-max-symbols` | integer | `100` |  | `yfinance` connectors | Symbols cached (least recently used leave first) | live (the connector restarts) |  | expert |
| `max-bytes` | integer | `2000000` |  | `yfinance` connectors | Largest response accepted | live (the connector restarts) |  | expert |
| `retries` | integer | `2` |  | `yfinance` connectors | Repeats of a failed request | live (the connector restarts) |  | admin |
| `retry-initial` | string | `400ms` |  | `yfinance` connectors | First wait before a repeat (400ms) | live (the connector restarts) |  | expert |
| `retry-max` | string | `4s` |  | `yfinance` connectors | Longest wait (4s) | live (the connector restarts) |  | expert |
| `retry-factor` | string | `2` |  | `yfinance` connectors | Growth of the wait (2) | live (the connector restarts) |  | expert |
| `retry-jitter` | string | `0.2` |  | `yfinance` connectors | Random spread of the wait (0.2) | live (the connector restarts) |  | expert |

#### A connector file: `config/connectors/<name>.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `plugin` | string | none |  | the connector | The plugin it runs (delta, jdbc, file, rest, kafka, aerospike, cassandra, redis, mongodb, iceberg, iceberg-external, duckdb, activemq, rabbitmq, s3, feed, news, yfinance, ...) | live (the connector restarts) |  | admin |
| `enabled` | boolean | `true` |  | the connector | false: the connector stays stopped, its file kept | live (the connector restarts) |  | admin |
| `description` | string | empty |  | the connector | A line shown in Admin -&gt; Connectors | live (the connector restarts) |  | admin |
| `kinds` | list | `what the plugin reports` |  | the connector | The kinds it serves | live (the connector restarts) |  | admin |
| `settings` | map | `{}` |  | the connector | The plugin's settings, as the tables above (every plugin, the shared tls.\* and proxy.\*, the plugin's own) | live (the connector restarts) |  | admin |
| `settings.*` | map | none |  | the connector | One setting of the plugin | live (the connector restarts) |  | admin |

#### A lake file: `config/lakes/<lake>.yaml` (docs/guides/DATA_LAKE_GUIDE.md)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lake` | string | none |  | every writer and reader of the lake | The lake's name: the file's name; connectors (lake:), loads (target.lake) and lake build (--lake) refer to it | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `description` | string | empty |  | every writer and reader of the lake | A line about the lake | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `format` | string | `delta` |  | every writer and reader of the lake | delta or iceberg; a table may say otherwise | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `root` | string | none |  | every writer and reader of the lake | Where the lake is: a local folder, s3://bucket/prefix, or mem://&lt;store&gt; for an in-memory store | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `storage` | map | `{}` |  | every writer and reader of the lake | How to reach the root (region, endpoint, path-style ...): the platform's role or ${ENV} references, never a secret | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `storage.*` | string | none |  | every writer and reader of the lake | One storage setting (given to a Delta connector as s3.&lt;key&gt;) | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `catalog` | map | `none` |  | every writer and reader of the lake | Iceberg's catalog: type none (path tables), rest, glue or s3tables, with its settings (uri, warehouse, token as ${ENV}) | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `catalog.*` | string | none |  | every writer and reader of the lake | One catalog setting | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `memory` | map | `none` |  | every writer and reader of the lake | An in-memory store's (root: mem://&lt;store&gt;) max-mb (the hard cap), chunk-kb, contract, owner, idle-trim-minutes | live for connectors (read again when a file changes); the next load for writers |  | expert |
| `memory.*` | string | none |  | every writer and reader of the lake | One memory setting of the store | live for connectors (read again when a file changes); the next load for writers |  | expert |
| `defaults` | map | `{}` |  | every writer and reader of the lake | Table keys that apply to every table unless it says otherwise | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `defaults.*` | string | none |  | every writer and reader of the lake | One table key's default | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `tables` | map | `{}` |  | every writer and reader of the lake | The tables by &lt;domain&gt;.&lt;kind&gt; (or &lt;domain&gt;.&lt;table&gt; with kind: when a kind has several tables) | live for connectors (read again when a file changes); the next load for writers |  | admin |
| `tables.*` | map | none |  | every writer and reader of the lake | One table's entry: kind, path, format, partition-by (after business\_date, which is always first; at most four), promote, vectors, flatten, id, date, latest-by, load-mode (replace or append), sort-by, file-rows, row-group-rows, file-size-mb, row-group-mb, compression, vector-compression, retention, maintenance, time-travel, properties | live for connectors (read again when a file changes); the next load for writers |  | admin |

### Packs: `pack.yaml`

`config/packs/<pack>/pack.yaml`, read by the server's pack loader (`PackLoader`) and the console. A changed pack is reloaded while the server runs ([Live reload](../guides/PACK_DEVELOPER_GUIDE.md#live-reload-the-packs-folder-is-watched)). Guide: [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#packyaml-key-by-key).

#### `pack.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `pack` | string | none |  | the pack | Required: the pack's name, equal to its folder's name (else the pack is refused) | live |  | admin |
| `version` | string | `0` |  | the pack | X.Y.Z, shown in About, Admin -&gt; Health and Admin -&gt; Packs and checked by deploys and the registry | live |  | admin |
| `code` | string | `none` |  | the pack | The pack's short code: typed alone it opens the pack's overview (/p/&lt;pack&gt;); must not equal a mnemonic | live |  | admin |
| `title` | string | `the pack's name` |  | the pack | Its name in menus, help and admin pages | live |  | admin |
| `description` | string | empty |  | the pack | One paragraph for About and the help centre | live |  | admin |
| `sample` | boolean | `false` |  | the pack | A demonstration pack: drishti.packs.samples can hide it from business users | live |  | admin |
| `extends` | list | `[]` |  | the pack | Packs inherited (their kinds, links, roles, connectors), loaded with this one; requires is the older name | live |  | admin |
| `requires` | list | `[]` |  | the pack | The older name of extends | live |  | admin |
| `kinds` | list | `[]` |  | the pack | The kinds this pack owns; a kind belongs to exactly one installed pack | live |  | admin |
| `mnemonics` | map | `{}` |  | the pack | The commands: MNEMONIC -&gt; {kind, label} | live |  | admin |
| `mnemonics.<name>` | map | none |  | the pack | One command | live |  | admin |
| `mnemonics.<name>.kind` | string | none |  | the pack | The kind it opens | live |  | admin |
| `mnemonics.<name>.label` | string | `the mnemonic` |  | the pack | Its name in FUNC, menus and the type-ahead | live |  | admin |
| `graph` | map | `{}` |  | the pack | How the pack's entities link | live |  | admin |
| `graph.id-patterns` | list | `[]` |  | the pack | A bare id's kind recognised by a regular expression | live |  | admin |
| `graph.id-patterns[].pattern` | string | none |  | the pack | The regular expression | live |  | admin |
| `graph.id-patterns[].kind` | string | none |  | the pack | The kind an id it matches is | live |  | admin |
| `graph.fields` | map | `{}` |  | the pack | Document fields whose values are ids of another kind, so they become links | live |  | admin |
| `graph.fields.<name>` | map | none |  | the pack | One linking field | live |  | admin |
| `graph.fields.<name>.kind` | string | none |  | the pack | The kind its values are ids of | live |  | admin |
| `graph.fields.<name>.label` | string | `none` |  | the pack | The link's label | live |  | admin |
| `graph.fields.<name>.scope` | string | `all` |  | the pack | pack: a link in this pack's kinds only; all: everywhere | live |  | admin |
| `graph.badges` | map | `{}` |  | the pack | kind -&gt; an expression whose text is shown beside a link to it, read from the target | live |  | admin |
| `graph.badges.<name>` | string | none |  | the pack | One kind's badge expression | live |  | admin |
| `graph.impact` | map | `{}` |  | the pack | What F8 (Impact) rolls up and sums | live |  | admin |
| `graph.impact.follow` | list | `[]` |  | the pack | The link fields Impact follows | live |  | admin |
| `graph.impact.measures` | map | `{}` |  | the pack | kind -&gt; the expression Impact sums | live |  | admin |
| `graph.impact.measures.<name>` | string | none |  | the pack | One kind's measure | live |  | admin |
| `graph.impact.formats` | map | `{}` |  | the pack | kind -&gt; the format of its measure | live |  | admin |
| `graph.impact.formats.<name>` | string | none |  | the pack | One kind's format | live |  | admin |
| `columns` | map | `{}` |  | the pack | kind -&gt; the key fields of its pick lists and search results | live |  | admin |
| `columns.<name>` | list | none |  | the pack | One kind's key fields | live |  | admin |
| `pivot` | map | `{}` |  | the pack | kind -&gt; Pivot (the pivot screen on the kind) on its search results: rows, columns, values, filters, fields | live |  | admin |
| `pivot.<name>` | map | none |  | the pack | One kind's pivot | live |  | admin |
| `pivot.<name>.rows` | list | none |  | the pack | Fields down the side | live |  | admin |
| `pivot.<name>.columns` | list | none |  | the pack | Fields across the top | live |  | admin |
| `pivot.<name>.values` | list | none |  | the pack | The cells, {field, agg} each | live |  | admin |
| `pivot.<name>.values[].field` | string | none |  | the pack | The field summed (or counted) | live |  | admin |
| `pivot.<name>.values[].agg` | string | `sum` |  | the pack | sum, count, avg, min or max | live |  | admin |
| `pivot.<name>.filters` | list | none |  | the pack | Fields offered as filters | live |  | admin |
| `pivot.<name>.fields` | list | none |  | the pack | Further fields the person may drag in | live |  | admin |
| `form` | map | `{}` |  | the pack | kind -&gt; its search form (FORM &lt;pack&gt;): the fields, in order | live |  | admin |
| `form.<name>` | list | none |  | the pack | One kind's form fields | live |  | admin |
| `form.<name>[].path` | string | none |  | the pack | The field's path in the document | live |  | admin |
| `form.<name>[].label` | string | `the path in words` |  | the pack | Its heading | live |  | admin |
| `form.<name>[].type` | string | `text` |  | the pack | text, number, amount, date, enum, id or bool | live |  | admin |
| `form.<name>[].operators` | list | `the type's` |  | the pack | The operators offered, the first the default | live |  | admin |
| `form.<name>[].order` | integer | `as written` |  | the pack | Its position (lower first) | live |  | admin |
| `form.<name>[].group` | string | `none` |  | the pack | A heading the field sits under | live |  | admin |
| `form.<name>[].help` | string | `none` |  | the pack | One line under the field | live |  | admin |
| `form.<name>[].values` | list | `the values in the data` |  | the pack | For an enum: the values offered | live |  | admin |
| `form.<name>[].depends` | string | `none` |  | the pack | For an enum: another field; only values that occur with its chosen values are offered | live |  | admin |
| `history` | map | `{}` |  | the pack | kind -&gt; how it looks over time (EQ EQ-CSCA 3M); any dated kind has a history without it | live |  | admin |
| `history.<name>` | map | none |  | the pack | One kind's history | live |  | admin |
| `history.<name>.enabled` | boolean | `true` |  | the pack | false: the kind has no history (DRS-1012) | live |  | admin |
| `history.<name>.shape` | string | `market with chart, else document` |  | the pack | How the history page is laid out | live |  | admin |
| `history.<name>.columns` | list | `the kind's key fields` |  | the pack | The table's columns, {path, format} each | live |  | admin |
| `history.<name>.columns[].path` | string | none |  | the pack | A column's field | live |  | admin |
| `history.<name>.columns[].format` | string | `inferred` |  | the pack | Its format | live |  | admin |
| `history.<name>.chart` | list | `none` |  | the pack | Numeric fields charted (at most 4), {path, format} each; a chart field is always a column | live |  | admin |
| `history.<name>.chart[].path` | string | none |  | the pack | A charted field | live |  | admin |
| `history.<name>.chart[].format` | string | `inferred` |  | the pack | Its format | live |  | admin |
| `history.<name>.headline` | string | `the first chart field` |  | the pack | The field the last, change, high, low, range and average are taken on | live |  | admin |
| `history.<name>.exclude` | list | `none` |  | the pack | Paths left out of the comparison of days | live |  | admin |
| `history.<name>.ignore-changes` | list | `none` |  | the pack | Paths whose changes do not count as a change | live |  | admin |
| `history.<name>.parts` | list | `every top-level list or object` |  | the pack | The parts compared day by day | live |  | admin |
| `history.<name>.series` | map | `none` |  | the pack | A list inside the newest document with a date per item: {path, date, value or values} | live |  | admin |
| `history.<name>.series.path` | string | none |  | the pack | The list's path | live |  | admin |
| `history.<name>.series.date` | string | none |  | the pack | Each item's date field | live |  | admin |
| `history.<name>.series.value` | string | none |  | the pack | Each item's value field | live |  | admin |
| `history.<name>.default-days` | integer | `drishti.history.default-days (30)` |  | the pack | The range when none is named | live |  | admin |
| `history.<name>.max-days` | integer | `drishti.history.max-days (366)` |  | the pack | Can only lower the server's | live |  | admin |
| `roles` | map | `{}` |  | the pack | Roles this pack adds: name -&gt; its powers | live |  | admin |
| `roles.<name>` | map | none |  | the pack | One role | live |  | admin |
| `roles.<name>.kinds` | list | none |  | the pack | The kinds the role may open ("\*": all) | live |  | admin |
| `roles.<name>.raw` | boolean | `false` |  | the pack | Sees every field: nothing in drishti.security.redact is masked | live |  | admin |
| `roles.<name>.calc` | boolean | `false` |  | the pack | May use Calc (Python in the browser) | live |  | admin |
| `roles.<name>.*` | boolean | `false` |  | the pack | The other powers, as drishti.security.roles: author, approve, admin, layout, edit, bi | live |  | admin |
| `python` | map | `{}` |  | the pack | Calc on this pack's kinds and its starter snippets | live |  | admin |
| `python.enabled` | boolean | `false` |  | the pack | Calc offered on the pack's kinds | live |  | admin |
| `python.snippets` | list | `[]` |  | the pack | Starter snippets, {title, description, kinds, code} each (also python/\*.py) | live |  | admin |
| `python.snippets[].title` | string | none |  | the pack | The snippet's name | live |  | admin |
| `python.snippets[].description` | string | none |  | the pack | What it does | live |  | admin |
| `python.snippets[].kinds` | list | none |  | the pack | The kinds it is offered on | live |  | admin |
| `python.snippets[].code` | text | none |  | the pack | The Python | live |  | admin |
| `connectors` | list or map | `[]` |  | the pack | The connectors the pack reads through: a list names site connectors; a mapping names them and is each one's template, written to config/connectors/&lt;name&gt;.yaml at the first start when no such file exists | live |  | admin |
| `connectors.<name>` | map | none |  | the pack | One connector's template: plugin, enabled, kinds, settings (the connector file's keys), or a reactive connector's engine, triggers, cache, limits | live |  | admin |
| `connectors.<name>.*` | map | none |  | the pack | A template's keys, as a connector file (above) or a reactive connector (docs/connectors/REACTIVE\_CONNECTOR.md) | live |  | admin |
| `connector-templates` | map | `{}` |  | the pack | Templates to go with a list of connector names, as connectors.&lt;name&gt; | live |  | admin |
| `connector-templates.<name>` | map | none |  | the pack | One template | live |  | admin |
| `connector-templates.<name>.*` | map | none |  | the pack | Its keys, as a connector file | live |  | admin |
| `routes` | map | `{}` |  | the pack | kind -&gt; the connector that answers it (drishti.sources.routes) | live |  | admin |
| `routes.<name>` | string | none |  | the pack | One kind's connector | live |  | admin |
| `ingest` | map | `{}` |  | the pack | For the tools only (drishti.py data ingest --pack, lake build --pack, pack check): each kind's id and business-date fields; ignored by the server | live |  | admin |
| `lake` | map | `none` |  | the pack | The tables the pack recommends (docs/guides/DATA\_LAKE\_GUIDE.md section 12): lake.tables, the shape of a lake file's tables:, copied into a lake file the first time a writer meets the kind; its Delta and Iceberg templates name lake: and lake-tables: | live |  | admin |
| `lake.*` | map | none |  | the pack | lake.tables.&lt;domain&gt;.&lt;table&gt;: one recommended entry (kind, path, partition-by, promote, vectors, sort-by, file-rows, row-group-rows, flatten, ...) | live |  | admin |
| `ingest.<name>` | map | none |  | the pack | One kind | live |  | admin |
| `ingest.<name>.key` | string | none |  | the pack | Its id field | live |  | admin |
| `ingest.<name>.date` | string | `BUSINESS_DATE` |  | the pack | Its business-date field | live |  | admin |
| `ingest.<name>.flatten` | map | `{}` |  | the pack | Values inside lists that lake build writes as plain fields and columns: field -&gt; path | live |  | admin |
| `ingest.<name>.flatten.*` | string | none |  | the pack | One flattened field | live |  | admin |
| `alerts` | list | `[]` |  | the pack | Suggested alert rules, {kind, name, when, message, severity} each | live |  | admin |
| `alerts[].kind` | string | none |  | the pack | The kind watched | live |  | admin |
| `alerts[].name` | string | none |  | the pack | The rule's name | live |  | admin |
| `alerts[].when` | string | none |  | the pack | The condition, an expression on the document | live |  | admin |
| `alerts[].message` | string | none |  | the pack | What the alert says | live |  | admin |
| `alerts[].severity` | string | `info` |  | the pack | info, warning or critical | live |  | admin |
| `positions` | map | `drishti.var.positions` |  | the pack | How the VaR engine derives positions from the trades of a date (VAR\_GUIDE.md section 15.6) | live |  | admin |
| `positions.live-statuses` | list | none |  | the pack | The TRADE\_STATUS values that count | live |  | admin |
| `positions.rules` | list | none |  | the pack | The rules, the first that holds a trade keys its position | live |  | admin |
| `positions.rules[].name` | string | none |  | the pack | The rule's name | live |  | admin |
| `positions.rules[].groups` | list | `[]` |  | the pack | PRODUCT\_GROUP values it holds | live |  | admin |
| `positions.rules[].types` | list | `[]` |  | the pack | PRODUCT\_TYPE values it holds | live |  | admin |
| `positions.rules[].key` | list | `none: one position per trade` |  | the pack | The fields keying the position, the first the trade carries | live |  | admin |
| `hierarchies` | map | `{}` |  | the pack | The hierarchies the VaR and exposure engines build (VAR\_GUIDE.md section 16); drishti.var.hierarchies wins | live |  | admin |
| `hierarchies.<name>` | map | none |  | the pack | One hierarchy | live |  | admin |
| `hierarchies.<name>.title` | string | none |  | the pack | Its name | live |  | admin |
| `hierarchies.<name>.source` | string | `columns when levels are given` |  | the pack | risk-hierarchy (the stored rows) or columns (built from the trades' own columns) | live |  | admin |
| `hierarchies.<name>.levels` | string | `none` |  | the pack | The columns, top first: LIMIT\_LETTER &gt; DESK | live |  | admin |
| `hierarchies.<name>.ids` | string | `path` |  | the pack | path or leaf: how node ids are made | live |  | admin |
| `hierarchies.<name>.word` | string | `none` |  | the pack | The scope word naming a node (LL:LL-0003) | live |  | admin |
| `hierarchies.<name>.limits` | string | `none` |  | the pack | The kind its nodes' VaR limits come from (var-limit) | live |  | admin |
| `loads` | map | `{}` |  | the pack | Which kinds the ETL is expected to land each business day, by when, and who is told (DATA\_GUIDE.md) | live |  | admin |
| `loads.*` | map | none |  | the pack | The data-load expectations (DATA\_GUIDE.md › Telling Drishti new data has landed, section 6) | live |  | admin |
| `sutras` | string | `sutras` |  | the pack | The pack's Sutra folder, read recursively | live |  | admin |
| `formats` | string | `config/formats.yaml` |  | the pack | The pack's named formats | live |  | admin |
| `semantics` | string | `config/semantics.yaml` |  | the pack | Inference hints: roles, labels, acronyms, idFields | live |  | admin |
| `samples` | string | `samples` |  | the pack | Sample documents for the demo source | live |  | admin |
| `about` | string | `config/about.yaml` |  | the pack | The About this page words and glossary | live |  | admin |
| `console` | map | `{}` |  | the pack | What the console reads from the pack | live |  | admin |
| `console.examples` | list | `[]` |  | the pack | Example commands, \[command, description] each, on /t and the landing page | live |  | admin |
| `console.workspaces` | string | `none` |  | the pack | The pack's starter workspaces file | live |  | admin |
| `console.help` | string | `none` |  | the pack | The pack's help cards and F1 targets file | live |  | admin |
| `console.monitors` | map | `{}` |  | the pack | Starter monitors: name -&gt; \[{kind, id}] | live |  | admin |
| `console.monitors.<name>` | list | none |  | the pack | One monitor's entities | live |  | admin |
| `console.monitors.<name>[].kind` | string | none |  | the pack | An entity's kind | live |  | admin |
| `console.monitors.<name>[].id` | string | none |  | the pack | Its id | live |  | admin |

### Editions: `config/editions/`

An edition file (`config/editions/<name>.yaml`), the feature presets (`features.yaml`) and the default packs (`default-packs.yaml`), read by the server (`Editions.java`) and the console (`core/edition.py`). The site's own configuration wins over any key: `drishti.edition-spec.<key>` on the server, `edition.<key>` in the console ([EDITIONS.md](EDITIONS.md)).

#### An edition file: `config/editions/<name>.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `edition` | string | `market` |  | an edition (server and console) | Drishti Market: the bank's own market data in one product: curves, fixings, FX, volatility surfaces and cubes, credit curves, equities, inflation, commodities, bonds and correlations, the inputs that feed risk and valuations. No trades and no VaR. Chosen by drishti.edition: market on every server (DRISHTI\_EDITION=market), or offered as a lens beside the others (drishti.lenses: risk,world,market). The site's configuration wins over any key (drishti.edition-spec.&lt;key&gt; on the server, edition.&lt;key&gt; in the console). docs/admin/EDITIONS.md (how to run it, then the design as built). | restart |  | admin |
| `title` | string | `Drishti Risk` |  | an edition (server and console) | the edition's name, in the top bar, the About page and the lens menu | restart |  | admin |
| `description` | string | `VaR and counterparty exposure, from your own pricing data, in one place` |  | an edition (server and console) | one line on what the edition is for (About, the lens menu) | restart |  | admin |
| `packs.enabled` | list | `[core, market-data]` |  | an edition (server and console) | market-data extends core (issuers and holiday calendars); no trading, no risk pack | restart |  | admin |
| `packs.allow-only` | boolean | `true` |  | an edition (server and console) | Admin -&gt; Packs, Deploy archive and the registry refuse any other pack (DRS-1017) | restart |  | admin |
| `commands.allow` | string | empty |  | an edition (server and console) | the engines still read every kind of the packs above | restart |  | admin |
| `commands.words` | map | `{ BI: on, RUPAKA: off, FORM: off, FUNC: on, WHAT: on, DATAMAP: off, DATA: on,...` |  | an edition (server and console) | left out: regulatory (FRTB, SACCR, CVA, SIMM), stress (SCN, STR, SSPNL), collateral operations (COLL, MC), the exposure engine's runs, paths and official records (XRUN, XPATH, XOFC), VaR's scenario inputs (SPV, FXS, SSET, VOFF), macro economics | restart |  | admin |
| `features` | map | `{…}` |  | an edition (server and console) | presets of existing switches (config/editions/features.yaml); not named: the product's default | restart |  | admin |
| `bi.datasets` | list | `[bi.market_data_*]` |  | an edition (server and console) | (a table named bi.market\_data\_... or the model market-data, when one is added), never the | restart |  | admin |
| `bi.models` | list | `[market-data]` |  | an edition (server and console) | VaR or exposure reports; an empty list would offer every BI item of the product | restart |  | admin |
| `menus.top` | list | `[views, curves, volatility, fx, credit, bi, admin, help]` |  | an edition (server and console) | the top menus by id, in order; one not named is not drawn | restart |  | admin |
| `menus.views` | list | `[terminal, search, functions, datamap, data, reports, workspaces, monitors, a...` |  | an edition (server and console) | the Views menu's items by id, in order (drishti-console/config/sitemap.yaml) | restart |  | admin |
| `menus.help` | list | `[help-centre, knowledge, install, about]` |  | an edition (server and console) | the Help menu's items by id | restart |  | admin |
| `menus.footer` | list | `[help, about, release-notes]` |  | an edition (server and console) | the footer's links by id | restart |  | admin |
| `menus.placeholder` | string | `"VAR BANK, VAR DESK RATES, EXP CPTY:CP-ALDERSHOT, VRES DESK:RATES …  then <GO>"` |  | an edition (server and console) | the command line's placeholder text | restart |  | admin |
| `home.landing` | string | `desk` |  | an edition (server and console) | the default landing: desk (A, Market desk), explorer (B, Explorer), quality (C, Quality); today: the product's Home | restart |  | admin |
| `home.landings` | list | `[desk, explorer, quality]` |  | an edition (server and console) | the landings a person may pick on Home (their choice is kept in their settings) | restart |  | admin |
| `home.template` | string | `home/market_landing.html` |  | an edition (server and console) | the page that draws them (it names its script and style: drishti\_market-landing.js, drishti\_market-landing.css) | restart |  | admin |
| `home.titles` | map | `{ desk: Risk desk, command: Command first, map: Risk map }` |  | an edition (server and console) | the landings' names, by landing | restart |  | admin |
| `home.landing-by-role` | map | `{}` |  | an edition (server and console) | {role: landing}: read, wins over the person's choice when set; wired to role-based access later | restart |  | admin |
| `home.chips` | list | `[CRV CRV-USD-OIS, CRV CRV-CAD-GOVT, FXF FXF-EURUSD, FXV FXV-USDJPY, IRV IRV-U...` |  | an edition (server and console) | B's chips | restart |  | admin |
| `home.sections` | map | `{ stats: off, grammar: off, pipeline: off, capabilities: off, views: off }` |  | an edition (server and console) | the product's Home (signed out, or landing=today) | restart |  | admin |
| `home.cards` | list | empty |  | an edition (server and console) | quick cards on Home (replace ui.home\_cards) | restart |  | admin |
| `quality.stale-days` | integer | `2` |  | an edition (server and console) | stale: data older than the business date by more than this many business days | restart |  | admin |
| `help.categories` | list | `[get-started, risk, analytics, data, operate, reference, knowledge, internal]` |  | an edition (server and console) | the help sections kept (config/help.yaml): Build hidden | restart |  | admin |
| `help.knowledge` | list | `[market-data]` |  | an edition (server and console) | the Market data Knowledge page (the rest hidden) | restart |  | admin |
| `help.unlisted-slugs` | string | `refuse` |  | an edition (server and console) | a guide not in the help centre answers 404 (no reaching hidden guides by URL) | restart |  | admin |
| `admin.pages` | list | `[users, roles, packs, commands, tokens, access, connectors, artifacts, loads,...` |  | an edition (server and console) | the Admin pages kept, by id; the others answer 404 | restart |  | admin |
| `branding.product` | string | `Drishti Risk` |  | an edition (server and console) | the product name the edition shows (a site's own branding wins) | restart |  | admin |
| `branding.tagline` | string | `VaR and exposure from your own pricing data` |  | an edition (server and console) | the line under it | restart |  | admin |
| `phone.rules` | list | empty |  | an edition (server and console) | tiers and caps by panel, kind, title, Sutra or entity kind (globs): a VaR contribution panel waits under More | restart |  | admin |
| `phone.pages` | map | `{}` |  | an edition (server and console) | the VaR and exposure pages' parts, as ui.phone.pages: { var: { hist: glance } } | restart |  | admin |
| `home.hierarchies` | list | `[MGMT]` |  | an edition (server and console) | the hierarchy the Risk map opens on (its switch lists every hierarchy the engine holds) | restart |  | admin |
| `menus.markets` | list | `[crv, repo, fix, irv, cpv, fxv, fx-spot, fxf, cds, bnd, eq, eqx, cmd, cmdc]` |  | an edition (server and console) | the one Markets menu: rates, vols, FX, credit, equities and commodities | restart |  | admin |
| `trading.people.drishti-dev-admin` | map | `{ trader: TRDR-ASHAH }` |  | an edition (server and console) | the demo's administrator reads A. Shah's books (the demo data's rates trader) | restart |  | admin |
| `trading.match-records` | boolean | `true` |  | an edition (server and console) | fall back to a matching trader record (TRADER\_ID = TRDR-&lt;USER&gt;, or the same NAME) | restart |  | admin |
| `trading.landing-by-role` | map | `{ desk-head: desk, trader: trader }` |  | an edition (server and console) | the same {role: landing} hook for the trading role: below the person's own choice | restart |  | admin |
| `trading.live-statuses` | list | `[NEW, VALIDATED, AMENDED]` |  | an edition (server and console) | a trade counts in DV01, top lists and the check when its status is one of these | restart |  | admin |
| `trading.near-limit` | number | `0.8` |  | an edition (server and console) | a book at or above this share of its VaR limit is "near limit"; above 1 it breaches | restart |  | admin |
| `trading.limit-days` | integer | `5` |  | an edition (server and console) | the Desk head's limit use by book: this many business dates (the official records) | restart |  | admin |
| `trading.pnl-var-days` | integer | `60` |  | an edition (server and console) | the Desk head's P&amp;L against VaR: this many days of the desk's official history | restart |  | admin |
| `trading.max-rows` | integer | `20000` |  | an edition (server and console) | the most trade valuations read per date for a scope (more: the figures say partial) | restart |  | admin |
| `home.topics` | list | `[TOPIC-RATES, TOPIC-INFLATION, TOPIC-OIL, TOPIC-FED, TOPIC-ECB, TOPIC-AI]` |  | an edition (server and console) | A's news by topic (each topic's latest stories) | restart |  | admin |

#### Feature presets: `features.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `bi` | on, off or edition | none |  | server and console | The feature bi an edition file names under features: off presets false, on or edition true; presets drishti.bi.enabled on the server and bi.enabled in the console. edition: BI with the edition's datasets only (bi.datasets) | restart |  | admin |
| `calc` | on, off or edition | none |  | server and console | The feature calc an edition file names under features: off presets false, on or edition true; presets drishti.calc.enabled on the server and labs.calc in the console. Labs: Calc, Python in the browser, a scratch pad; off as shipped | restart |  | admin |
| `layouts` | on, off or edition | none |  | server and console | The feature layouts an edition file names under features: off presets false, on or edition true; presets drishti.layouts.enabled on the server and layouts.enabled in the console | restart |  | admin |
| `pivot` | on, off or edition | none |  | server and console | The feature pivot an edition file names under features: off presets false, on or edition true; presets drishti.pivot.enabled on the server | restart |  | admin |
| `reports` | on, off or edition | none |  | server and console | The feature reports an edition file names under features: off presets false, on or edition true; presets drishti.reports.enabled on the server and features.reports in the console | restart |  | admin |
| `collaboration` | on, off or edition | none |  | server and console | The feature collaboration an edition file names under features: off presets false, on or edition true; presets drishti.collab.enabled on the server and features.collaboration in the console | restart |  | admin |
| `var` | on, off or edition | none |  | server and console | The feature var an edition file names under features: off presets false, on or edition true; presets drishti.engines.var.enabled on the server. the compute engines' switches (config/engines/&lt;id&gt;.yaml, COMPUTE\_ENGINES.md) | restart |  | admin |
| `exposure` | on, off or edition | none |  | server and console | The feature exposure an edition file names under features: off presets false, on or edition true; presets drishti.engines.exposure.enabled on the server | restart |  | admin |
| `frtb-sa` | on, off or edition | none |  | server and console | The feature frtb-sa an edition file names under features: off presets false, on or edition true; presets drishti.engines.frtb-sa.enabled on the server | restart |  | admin |
| `simm` | on, off or edition | none |  | server and console | The feature simm an edition file names under features: off presets false, on or edition true; presets drishti.engines.simm.enabled on the server | restart |  | admin |
| `governance` | on, off or edition | none |  | server and console | The feature governance an edition file names under features: off presets false, on or edition true; presets drishti.governance.enabled on the server | restart |  | admin |
| `ai` | on, off or edition | none |  | server and console | The feature ai an edition file names under features: off presets false, on or edition true; presets drishti.ai.enabled on the server and ai.enabled in the console | restart |  | admin |
| `mcp` | on, off or edition | none |  | server and console | The feature mcp an edition file names under features: off presets false, on or edition true; presets drishti.mcp.enabled on the server and mcp.enabled in the console | restart |  | admin |
| `embed` | on, off or edition | none |  | server and console | The feature embed an edition file names under features: off presets false, on or edition true; presets drishti.embed.enabled on the server and embed.enabled in the console | restart |  | admin |
| `python` | on, off or edition | none |  | server and console | The feature python an edition file names under features: off presets false, on or edition true; presets drishti.bi.transform.enabled on the server | restart |  | admin |
| `designer` | on, off or edition | none |  | server and console | The feature designer an edition file names under features: off presets false, on or edition true; presets ui.designer in the console | restart |  | admin |
| `facts` | on, off or edition | none |  | server and console | The feature facts an edition file names under features: off presets false, on or edition true; presets ui.facts.enabled in the console | restart |  | admin |
| `build` | on, off or edition | none |  | server and console | The feature build an edition file names under features: off presets false, on or edition true; presets features.build in the console. Build: new screens, new packs, designs, reviews, changes | restart |  | admin |
| `forms` | on, off or edition | none |  | server and console | The feature forms an edition file names under features: off presets false, on or edition true; presets features.forms in the console. query by form (FORM, /forms) | restart |  | admin |
| `datamap` | on, off or edition | none |  | server and console | The feature datamap an edition file names under features: off presets false, on or edition true; presets features.datamap in the console. the data map (DATAMAP, /datamap) | restart |  | admin |
| `data` | on, off or edition | none |  | server and console | The feature data an edition file names under features: off presets false, on or edition true; presets drishti.data-view.enabled on the server and features.data in the console. Export data (EXPORT, /data): every dataset, a day as .jsonl.gz | restart |  | admin |
| `internal-only-data` | on, off or edition | none |  | server and console | The feature internal-only-data an edition file names under features: off presets false, on or edition true; presets drishti.data-view.internal-only-download on the server. off: Export data refuses datasets marked internal only | restart |  | admin |
| `workspaces` | on, off or edition | none |  | server and console | The feature workspaces an edition file names under features: off presets false, on or edition true; presets features.workspaces in the console. /w | restart |  | admin |
| `monitors` | on, off or edition | none |  | server and console | The feature monitors an edition file names under features: off presets false, on or edition true; presets features.monitors in the console. /m | restart |  | admin |
| `alerts` | on, off or edition | none |  | server and console | The feature alerts an edition file names under features: off presets false, on or edition true; presets features.alerts in the console. /alerts | restart |  | admin |
| `lakes` | on, off or edition | none |  | server and console | The feature lakes an edition file names under features: off presets false, on or edition true; presets features.lakes in the console. Admin -&gt; Lakes | restart |  | admin |

#### The default packs: `default-packs.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `packs` | list | `[core, market-data, trading, counterparty-risk, market-risk, world-economy, n...` |  | server and console | The packs a server enables when neither DRISHTI\_PACKS (drishti.packs.enabled) nor an edition names them: the full Drishti. The one list (docs/admin/EDITIONS.md › The design as built, section 5.1): the server reads it from its jar (drishti-packs copies this file in at build), the console from this folder; Docker and the stack pass DRISHTI\_PACKS through only when it is set. | restart |  | admin |

### The content store: `config/store/store.yaml`

Where user content is kept so every node of a cluster reaches it ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches)); `drishti.store.file` names the file.

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `backend` | string | `filesystem` |  | every node (the same everywhere) | Project Drishti · Any data. Any domain. One grammar. Copyright (c) 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. PROPRIETARY AND CONFIDENTIAL. This file is the confidential and proprietary property of Ashutosh Sinha. Unauthorised copying, use, modification, distribution or disclosure of this file, via any medium, is strictly prohibited except with the express prior written permission of the copyright holder. See the LICENSE file in the root of this repository for the full terms. The content store (docs/architecture/DATA\_ARCHITECTURE.md): where user content (Build workbench Designs today) is kept so that every node of a Drishti\*net cluster reaches it. The server reads this file at start (drishti.store.file in application.yaml); a missing file or key means the value shown here. Every node of a cluster must use the same settings. Examples: config/store.examples/. filesystem \| sqlite \| postgres \| s3 | restart |  | admin |
| `filesystem.root` | string | `./data/fstore` |  | every node (the same everywhere) | A folder; on a cluster it must be a SHARED mount (NFS, EFS, SMB) at the same path on every node. DRISHTI\_STORE\_ROOT overrides it. | restart |  | admin |
| `sqlite.url` | string | `jdbc:sqlite:./data/identity/drishti.db` |  | every node (the same everywhere) | the Drishti database itself (one node) | restart |  | admin |
| `postgres.url` | string | `""` |  | every node (the same everywhere) | e.g. jdbc:postgresql://db:5432/drishti (the Drishti database) | restart |  | admin |
| `postgres.user` | string | `drishti` |  | every node (the same everywhere) | the database user | restart |  | admin |
| `postgres.password` | string | empty | `DRISHTI_STORE_PASSWORD` | every node (the same everywhere) | its password, from the environment only (secret) | restart |  | admin |
| `postgres.pool` | integer | `4` |  | every node (the same everywhere) | connections the store keeps at most | restart |  | expert |
| `s3.bucket` | string | `""` |  | every node (the same everywhere) | the bucket (required with backend: s3) | restart |  | admin |
| `s3.prefix` | string | `""` |  | every node (the same everywhere) | a key prefix inside the bucket; empty: the bucket's root | restart |  | admin |
| `s3.region` | string | `us-east-1` |  | every node (the same everywhere) | the bucket's region | restart |  | admin |
| `s3.endpoint` | string | `""` |  | every node (the same everywhere) | empty for AWS; MinIO or another S3-compatible store that honours If-Match / If-None-Match | restart |  | admin |
| `s3.path-style` | boolean | `false` |  | every node (the same everywhere) | path-style addressing (most S3-compatible stores need true) | restart |  | admin |
| `s3.access-key` | string | empty | `DRISHTI_STORE_ACCESS_KEY` | every node (the same everywhere) | empty: the AWS default credentials chain (secret) | restart |  | admin |
| `s3.secret-key` | string | empty | `DRISHTI_STORE_SECRET_KEY` | every node (the same everywhere) | its secret key, from the environment only | restart |  | admin |

### BI runs: `config/runs/runs.yaml`

Every BI question as a bounded, visible job ([BI_DESIGN.md](../architecture/BI_DESIGN.md#17-bi-runs-every-bi-query-as-a-bounded-visible-job)); `drishti.bi.runs.file` names the file. Ready-made variants: `config/runs.examples/`.

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `enabled` | boolean | `true` |  | server | Project Drishti · Any data. Any domain. One grammar. Copyright (c) 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. PROPRIETARY AND CONFIDENTIAL. This file is the confidential and proprietary property of Ashutosh Sinha. Unauthorised copying, use, modification, distribution or disclosure of this file, via any medium, is strictly prohibited except with the express prior written permission of the copyright holder. See the LICENSE file in the root of this repository for the full terms. BI runs (docs/architecture/BI\_DESIGN.md › BI runs: every BI query as a bounded, visible job, section 15): every BI question as a bounded, visible job. The server reads this file at start (drishti.bi.runs.file in application.yaml); every key has its default, so a missing key or file means the value shown here. Ready-made variants are in config/runs.examples/. false: every question runs synchronously, with no Runs tab | restart |  | admin |
| `workers` | integer | `2` |  | server | runs executing at once on this node | restart |  | expert |
| `engines` | map | `{}` |  | server | external engines (groundwork for a later release): athena: {...}, trino: {...}, spark: {...} | restart |  | admin |
| `workspace-visibility` | string | `owner-and-shared` |  | server | owner-and-shared \| members (members see others' run rows, never results) | restart |  | admin |

#### `inline`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `inline.wait` | duration | `2s` |  | server | how long a request waits before its run becomes a visible job | restart |  | admin |
| `inline.max-wait` | duration | `10s` |  | server | the most a caller may ask for (Prefer: wait=) | restart |  | admin |

#### `interactive`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `interactive.max-seconds` | integer | `10` |  | server | the estimate's high end | restart |  | admin |
| `interactive.max-rows` | integer | `50000000` |  | server | rows read | restart |  | expert |
| `interactive.max-build-dates` | integer | `2` |  | server | partitions it may build from the source | restart |  | admin |

#### `confirm`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `confirm.above-seconds` | integer | `60` |  | server | ad-hoc heavy runs above this ask first | restart |  | admin |
| `confirm.above-rows` | integer | `500000000` |  | server | ad-hoc heavy runs reading more rows than this ask first | restart |  | admin |
| `confirm.unknown` | boolean | `true` |  | server | an estimate with basis unknown asks first | restart |  | admin |
| `confirm.ttl` | duration | `15m` |  | server | an unconfirmed run is discarded after this | restart |  | admin |

#### `queue`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `queue.per-user` | integer | `5` |  | server | a person's visible runs queued + running (DRS-9081) | restart |  | expert |
| `queue.max` | integer | `200` |  | server | all queued runs on this node (DRS-9081) | restart |  | expert |

#### `run`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `run.max-duration` | duration | `4h` |  | server | a run's time limit (DRS-9024) | restart |  | admin |

#### `engine`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `engine.memory-limit` | size | `2GB` |  | server | the run engine's memory limit (native memory, outside the heap) | restart |  | expert |
| `engine.threads` | integer | `2` |  | server | its threads | restart |  | expert |
| `engine.max-spill` | size | `20GB` |  | server | the most it may spill to disk | restart |  | expert |
| `engine.max-read-rows` | integer | `5000000000` |  | server | past it, and no split or external engine: DRS-9086 | restart |  | admin |
| `engine.prefer-external-above-seconds` | integer | `0` |  | server | 0: never prefer an external engine while local fits | restart |  | admin |

#### `estimate`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `estimate.low-factor` | number | `0.5` |  | server | the range before 20 runs of history narrow it | restart |  | admin |
| `estimate.high-factor` | number | `2.0` |  | server | the high end of the estimate's range, as a factor of the point estimate | restart |  | admin |
| `estimate.rates.scan-rows-per-second` | integer | `50000000` |  | server | DuckDB over Parquet layers | restart |  | admin |
| `estimate.rates.build-rows-per-second` | integer | `300000` |  | server | a connector's column scan into a layer | restart |  | admin |
| `estimate.rates.record-rows-per-second` | integer | `2000` |  | server | the record-by-record path | restart |  | admin |

#### `store`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `store.dir` | string | `./data/bi-runs` |  | server | where run results are kept on this node | restart |  | admin |
| `store.max-bytes` | size | `20GB` |  | server | unpromoted results on this node | restart |  | expert |
| `store.max-bytes-per-user` | size | `2GB` |  | server | unpromoted results one person may keep on this node | restart |  | expert |
| `store.max-result-rows` | integer | `50000000` |  | server | DRS-9086 | restart |  | admin |
| `store.max-result-bytes` | size | `2GB` |  | server | DRS-9086 | restart |  | expert |
| `store.min-seconds` | integer | `1` |  | server | an interactive answer faster than this is not stored | restart |  | admin |
| `store.keep-interactive` | boolean | `true` |  | server | keep interactive answers (above min-seconds) as runs too | restart |  | admin |

#### `retention`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `retention.days` | integer | `30` |  | server | after the last open, unless promoted or kept | restart |  | admin |
| `retention.max-days` | integer | `365` |  | server | the most "Keep longer" allows | restart |  | admin |
| `retention.sweep` | duration | `1h` |  | server | how often expired results are removed | restart |  | admin |

#### `restart`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `restart.resume-running` | boolean | `true` |  | server | a run interrupted by a restart starts again | restart |  | admin |
| `restart.max-attempts` | integer | `2` |  | server | then it fails with DRS-9085 | restart |  | admin |
| `restart.requeue-changed-model` | boolean | `false` |  | server | a queued run whose model changed fails instead of running on the new model | restart |  | expert |

#### `lease`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lease.renew` | duration | `10s` |  | server | how often a running run renews its lease | restart |  | admin |
| `lease.ttl` | duration | `45s` |  | server | a run whose lease is older than this is taken as dead (its node stopped) | restart |  | admin |

#### `cancel`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `cancel.grace` | duration | `5s` |  | server | Admin flags a cancelled run whose resources are not released by then | restart |  | admin |

#### `promote`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `promote.enabled` | boolean | `true` |  | server | results may be promoted (kept as a named snapshot) | restart |  | admin |
| `promote.versions-keep` | integer | `5` |  | server | promoted versions kept per result | restart |  | admin |

#### `notify`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `notify.on` | list | `[done, failed]` |  | server | also: cancelled-by-others (always on), started-again (always on) | restart |  | admin |

#### `suggest`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `suggest.min-runs` | integer | `5` |  | server | ad-hoc heavy runs of one shape ... | restart |  | admin |
| `suggest.window` | duration | `7d` |  | server | ... within this window ... | restart |  | admin |
| `suggest.min-seconds` | integer | `60` |  | server | ... each at least this long | restart |  | admin |

### Write-back: `config/edit/`

Write-back's four files ([WRITE_BACK.md](../guides/WRITE_BACK.md): the administrator's guide and, in its section 16, the design); `drishti.edit.file` names `edit.yaml`, the others sit beside it.

#### `edit.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `edit.enabled` | boolean | `false` | `DRISHTI_EDIT_ENABLED` | server | the write-back switch: false refuses every change (UI, CLI, API) and shows no Submit | restart |  | admin |
| `edit.log` | string | `auto` |  | server | auto \| sqlite \| postgres: where the edit log lives (the auto rule: a PostgreSQL Drishti database -&gt; postgres; | restart |  | admin |
| `edit.primary` | string | `""` |  | server | in a cluster without PostgreSQL: the node id of the edit primary (no failover: edits pause while it is down) | restart |  | admin |
| `edit.follow` | duration | `1s` |  | server | how often the other nodes copy the primary's applied overlays and owned values | restart |  | admin |

#### `lanes.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `lanes.lanes` | integer | `64` |  | server | P, a power of two; change it only with the lanes drained (new changes wait, the lanes empty, P changes) | restart |  | admin |
| `lanes.max-lane-depth` | integer | `1000` |  | server | a lane deeper than this refuses new changes: "busy, try again" (DRS-9304); the draft is kept | restart |  | admin |
| `lanes.lane-warn` | duration | `2s` |  | server | Admin -&gt; Edits warns when a lane's oldest change has waited longer | restart |  | admin |
| `lanes.lane-lease` | duration | `10s` |  | server | a lane's owner renews its lease every third of this; another node may take the lane only after it runs out | restart |  | admin |
| `lanes.command-timeout` | duration | `30s` |  | server | a source command sent and not confirmed for longer is shown as unconfirmed on Admin -&gt; Edits | restart |  | expert |

#### `policy.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `policy.approval.overlay` | string | `none` |  | server | none \| one \| two: a correction kept in Drishti | restart |  | admin |
| `policy.approval.owned` | string | `none` |  | server | Drishti's own records | restart |  | admin |
| `policy.approval.source` | string | `one` |  | server | an amendment sent to a source system through a connector's writes: | restart |  | admin |
| `policy.approver-roles` | list | `[]` |  | server | who approves; empty: holders of the approve power and administrators | restart |  | admin |
| `policy.self-approval` | boolean | `false` |  | server | four eyes: nobody approves their own change | restart |  | admin |
| `policy.restate-role` | string | `restate` |  | server | a change to a past business date (a restatement) needs this role | restart |  | admin |
| `policy.override-role` | string | `override` |  | server | "Apply mine anyway" on a conflict needs this role | restart |  | admin |

#### `retention.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `retention.keep` | duration | `7y` |  | server | 7y, 400d, ... never shorter than the site's policy | restart |  | admin |
| `retention.hot` | duration | `90d` |  | server | the hot window (archiving older entries to signed files under &lt;data dir&gt;/edit/archive/ is planned) | restart |  | admin |

### Compute workers: `config/compute/compute.yaml`

The supervisor, the Python pools and their environments ([OPERATIONS.md](OPERATIONS.md#19-compute-workers)).

#### `compute`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `compute.enabled` | boolean | `false` | `DRISHTI_COMPUTE_ENABLED` | server | run compute workers; false (as shipped) starts none | restart |  | admin |
| `compute.python` | string | empty | `DRISHTI_PYTHON` | server | the interpreter workers and environments use; empty: python3 (python on Windows) | restart |  | admin |
| `compute.build` | string | `manual` |  | server | manual (Admin -&gt; Compute -&gt; Build) or on-approval: when an approved environment is built | restart |  | admin |
| `compute.approval.roles` | list | `[]` |  | server | roles whose administrators may approve requirements; empty: any administrator (never the requester) | restart |  | admin |
| `compute.scheduling.max-inflight-per-user` | integer | `4` |  | server | one person's requests running at once on a pool; the rest wait behind other people's | restart |  | admin |
| `compute.scheduling.queue` | map | `{ interactive: 256, event: 10000, batch: 100000 }` |  | server | waiting requests per pool and class; a full queue answers "busy" (DRS-9203) | restart |  | expert |
| `compute.fan-out.chunk-rows` | integer | `5000` |  | server | rows per chunk when one request is split over many workers | restart |  | expert |
| `compute.fan-out.peers` | boolean | `true` |  | server | also send chunks to the other Drishti\*net nodes that serve the pool | restart |  | admin |
| `compute.limits.max-result-bytes` | size | `256MB` |  | server | the largest result table a worker may hand back | restart |  | expert |
| `compute.pools.<pool>.type` | string | `python` |  | server | the worker type: python | restart |  | expert |
| `compute.pools.<pool>.engines` | list | `[drishti_compute.samples.vectors:VectorEngine, drishti_compute.samples.paths:...` |  | server | the engines the pool serves, module:Class each | restart |  | expert |
| `compute.pools.<pool>.environment` | string | `system` |  | server | the site's interpreter and the SDK; or { python: "3.12", lock: locks/risk.lock, index: shipped } | restart |  | expert |
| `compute.pools.<pool>.workers.min` | integer | `0` |  | server | kept warm | restart |  | expert |
| `compute.pools.<pool>.workers.max` | integer | `4` | `DRISHTI_COMPUTE_SAMPLE_WORKERS` | server | as many as this node should run: configuration only, no limit in the code | restart |  | expert |
| `compute.pools.<pool>.mode` | string | `auto` |  | server | fork on Linux (a fresh child per request), warm on Windows (reset and recycled) | restart |  | expert |
| `compute.pools.<pool>.memory` | size | `1GB` |  | server | per worker | restart |  | expert |
| `compute.pools.<pool>.cpu-seconds` | integer | `300` |  | server | per request | restart |  | expert |
| `compute.pools.<pool>.recycle-after` | integer | `200` |  | server | requests, then the worker is replaced (50 in warm mode when unset) | restart |  | expert |
| `compute.pools.<pool>.batch-share` | number | `0.25` |  | server | batch requests hold at most this share of the workers | restart |  | expert |
| `compute.pools.<pool>.idle-stop` | duration | `5m` |  | server | a worker idle this long is stopped (down to min) | restart |  | expert |
| `compute.pools.<pool>.timeout` | duration | `30s` |  | server | an interactive request's budget | restart |  | expert |
| `compute.pools.<pool>.timeout-batch` | duration | `10m` |  | server | a batch request's budget | restart |  | expert |
| `compute.pools.<pool>.isolation` | string | `strict` |  | server | no network (a namespace with no interface) and files held by the kernel (Landlock): Linux only; elsewhere no run | restart |  | expert |

### Drishti*net: `config/net/`

The cluster folder (`drishti.net.dir`), watched while the server runs ([OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one)); the shipped examples are in `config/net.examples/`.

#### `node.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `enabled` | boolean | `true` |  | each member | Project Drishti · Any data. Any domain. One grammar. Copyright (c) 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. PROPRIETARY AND CONFIDENTIAL. This file is the confidential and proprietary property of Ashutosh Sinha. Unauthorised copying, use, modification, distribution or disclosure of this file, via any medium, is strictly prohibited except with the express prior written permission of the copyright holder. See the LICENSE file in the root of this repository for the full terms. Example: copy to config/net/node.yaml (the server's drishti.net.dir) to make this server a member of a Drishti\*net cluster (docs/admin/OPERATIONS.md). Applied live: enabled false -&gt; true joins, true -&gt; false leaves cleanly. Without this file the server is a single server exactly as before. | live |  | admin |
| `advertise-url` | string | `https://drishti-b.internal:8480` |  | each member | the address peers and consoles call this node at: a name or the machine's real IP, | live |  | admin |
| `tags` | list | `[region:emea, tier:risk]` |  | each member | never localhost, 127.x.x.x, ::1, 0.0.0.0 or :: (refused). Left out: this machine's primary IPv4 address (the default route's interface) with the server's port, e.g. http://192.168.1.20:8480 for nodes per role (access.yaml) | live |  | admin |
| `peer-only-packs` | list | `[]` |  | each member | packs served to peers only, hidden from this node's own users | live |  | admin |
| `memory-stores` | list | `[]` |  | each member | the in-memory lake's stores this node owns (fills, compacts and serves; IN\_MEMORY\_LAKE\_GUIDE.md): the | live |  | expert |
| `secret` | string | none | `DRISHTI_NET_SECRET` | each member | the cluster key: an environment reference only, at least 32 bytes, the same on every node (secret) | live |  | admin |
| `gossip-port` | string | `auto` |  | each member | gossip, probes and digests on a port of their own with their own platform threads, so a server busy | live |  | admin |

#### `gossip.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `interval` | duration | `1s` |  | each member | Project Drishti · Any data. Any domain. One grammar. Copyright (c) 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. PROPRIETARY AND CONFIDENTIAL. This file is the confidential and proprietary property of Ashutosh Sinha. Unauthorised copying, use, modification, distribution or disclosure of this file, via any medium, is strictly prohibited except with the express prior written permission of the copyright holder. See the LICENSE file in the root of this repository for the full terms. Example: copy to config/net/gossip.yaml to change the gossip protocol's timings (these are the defaults). Applied on the next round. a round: a push-pull exchange with `fanout` random members | live |  | expert |
| `fanout` | integer | `3` |  | each member | members each round exchanges with | live |  | admin |
| `suspect-after` | duration | `5s` |  | each member | a member whose heartbeat has not risen this long is probed | live |  | admin |
| `indirect-probes` | integer | `2` |  | each member | ... directly and through this many other members | live |  | admin |
| `dead-after` | duration | `30s` |  | each member | suspect this long: dead | live |  | admin |
| `forget-after` | duration | `24h` |  | each member | dead or left members are remembered this long, so a stale table cannot bring them back | live |  | admin |
| `persist-every` | duration | `10m` |  | each member | how often &lt;data dir&gt;/net/peers.json is saved (also on every join or leave and at shutdown) | live |  | admin |

#### `routing.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `forward-timeout` | duration | `5s` |  | each member | Project Drishti · Any data. Any domain. One grammar. Copyright (c) 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. PROPRIETARY AND CONFIDENTIAL. This file is the confidential and proprietary property of Ashutosh Sinha. Unauthorised copying, use, modification, distribution or disclosure of this file, via any medium, is strictly prohibited except with the express prior written permission of the copyright holder. See the LICENSE file in the root of this repository for the full terms. Example: copy to config/net/routing.yaml to change how requests travel between nodes (these are the defaults). a forwarded view or a peer fetch | live |  | expert |
| `scatter-budget` | duration | `3s` |  | each member | a peer's share of a scattered search; later, the answer is partial and names the peer | live |  | admin |
| `suggest-budget` | duration | `150ms` |  | each member | the same for type-ahead | live |  | admin |
| `link-budget` | duration | `1s` |  | each member | a view's linked entity that only a peer holds (the local ones: drishti.graph.link-budget, 40ms) | live |  | admin |
| `join-wait` | duration | `2s` |  | each member | a node just started or rejoined holds a read nothing here holds this long for its peers' digests, | live |  | admin |
| `join-grace` | duration | `30s` |  | each member | a node counts as joining at most this long after it joined (every peer down: it answers alone) | live |  | admin |
| `max-hops` | integer | `1` |  | each member | a forwarded request is never forwarded again; 0 switches forwarding off | live |  | admin |
| `weights.latency` | number | `1.0` |  | each member | how much a peer's latency counts when choosing between peers that can answer | live |  | expert |
| `user-state` | string | `replicate` |  | each member | saved searches, layouts, pivots copied to the other nodes (off: kept where written) | live |  | admin |
| `peer-protocol` | string | `http2` |  | each member | forwarded reads, scatter-gather, peer history and the live relay multiplexed over one HTTP/2 | live |  | admin |

#### `access.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `roles.<role>.nodes` | list | `[]` |  | each member | every node | live |  | admin |

#### `peers/<name>.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `url` | string | `https://drishti-a.internal:8480` |  | each member | a name or the real IP of drishti-a: never localhost, 127.x.x.x or 0.0.0.0 (refused) | live |  | admin |
| `tags` | list | `[region:emea]` |  | each member | the tags it is expected to carry (Admin -&gt; Net warns when gossip says otherwise) | live |  | admin |

### Platform alert rules: `config/alerts/platform-alerts.yaml`

The rules each server evaluates against its own metrics ([OPERATIONS.md](OPERATIONS.md#136-platform-metrics-and-alerts)); `drishti.metrics.alerts.file` names the file, read again when it changes. A rule's name is the site's own.

#### `rules`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `rules.<rule>.metric` | string | `jvm.gc.live.data.size` |  | server | the meter read, by its Micrometer name: here the old generation after the last collection | live |  | admin |
| `rules.<rule>.divide-by` | string | `jvm.gc.max.data.size` |  | server | a second meter the value is divided by (its series with the same tags): here its maximum | live |  | admin |
| `rules.<rule>.above` | number | `0.85` |  | server | fire above this value: here 85% of the maximum | live |  | admin |
| `rules.<rule>.for` | duration | `10m` |  | server | how long the value must stay over before the alert opens (0: at once) | live |  | admin |
| `rules.<rule>.severity` | string | `warn` |  | server | info, warn or critical | live |  | admin |
| `rules.<rule>.format` | string | `percent` |  | server | how {value} and {threshold} are written: number, percent, bytes or seconds | live |  | admin |
| `rules.<rule>.summary` | string | `"the heap after a collection is {value} of its maximum"` |  | server | the alert's line: {value}, {threshold} and each tag's name in braces are filled in | live |  | admin |
| `rules.<rule>.enabled` | boolean | `true` |  | server | false switches the rule off | live |  | admin |
| `rules.<rule>.function` | string | `increase` |  | server | value (the meter's value, or a counter's count; the default) or increase (a counter's rise over window) | live |  | admin |
| `rules.<rule>.window` | duration | `1h` |  | server | the span of increase | live |  | admin |
| `rules.<rule>.tags` | map | `{id: direct}` |  | server | only the series with these tags: here the direct buffers | live |  | admin |
| `rules.<rule>.below` | integer | `2` |  | server | fire below this value (give exactly one of above and below): here twice the guard | live |  | admin |
| `rules.<rule>.aggregate` | string | `sum` |  | server | each (one alert per series; the default), sum or max over the series: here every path summed | live |  | admin |

### Report definitions: `config/reports/`

One YAML per regulatory report (`drishti.regulatory.reports-dir`), read when a run is made: its rows, columns, checks, outputs and sign-off chain ([VAR_GUIDE.md section 18.14](../guides/VAR_GUIDE.md#1814-regulatory-reports-definitions-runs-checks-and-sign-off)).

#### A report definition: `config/reports/<report>.yaml` (docs/guides/VAR_GUIDE.md section 18.14)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `report` | string | none |  | server | The report's id: the file's name without .yaml | live (read when a run is made) |  | admin |
| `title` | string | `the id` |  | server | Its title on the screens and in the files | live (read when a run is made) |  | admin |
| `description` | string | empty |  | server | A line in the list of reports | live (read when a run is made) |  | admin |
| `type` | string | `var-table` |  | server | var-table (rows of official nodes, a diversification row and a total) or backtesting-chart (a node's backtest) | live (read when a run is made) |  | admin |
| `period` | string | `fiscal-year` |  | server | fiscal-year or fiscal-quarter, on drishti.regulatory.fiscal-year-end | live (read when a run is made) |  | admin |
| `measure` | string | `var` |  | server | The official record's figure: var, es, svar or ses | live (read when a run is made) |  | admin |
| `units` | string | `units` |  | server | units or thousands: how the files show the figures | live (read when a run is made) |  | admin |
| `node` | string | none |  | server | backtesting-chart: the node (MGMT~BANK) | live (read when a run is made) |  | admin |
| `rows` | list | none |  | server | var-table: the rows, in order | live (read when a run is made) |  | admin |
| `rows[].label` | string | none |  | server | The row's label | live (read when a run is made) |  | admin |
| `rows[].code` | string | none |  | server | A short code shown beside it (IR, FX) | live (read when a run is made) |  | admin |
| `rows[].node` | string | none |  | server | The official node the row reads | live (read when a run is made) |  | admin |
| `rows[].compute` | string | none |  | server | diversification: the total less the rows (instead of a node) | live (read when a run is made) |  | admin |
| `rows[].total` | boolean | `false` |  | server | The total row (one per var-table) | live (read when a run is made) |  | admin |
| `columns` | list | `[period-end, average, high, low, prior-period-end, movement]` |  | server | The period statistics shown | live (read when a run is made) |  | admin |
| `checks` | list | `[]` |  | server | The checks run before review: total-equals-official, rows-sum-to-total, no-missing-records, same-total-as (report), movement-needs-note (threshold), backtesting-exceptions, window-complete | live (read when a run is made) |  | admin |
| `checks[].check` | string | none |  | server | The check's name | live (read when a run is made) |  | admin |
| `checks[].report` | string | none |  | server | same-total-as: the other report | live (read when a run is made) |  | admin |
| `checks[].threshold` | number | `0.10` |  | server | movement-needs-note: the movement that needs a note | live (read when a run is made) |  | admin |
| `outputs` | list | `[xlsx, csv, pdf]` |  | server | The files the run gives after its last approval | live (read when a run is made) |  | admin |
| `sign-off` | list | `[maker, checker, approver]` |  | server | The sign-off steps in order, maker first, one person a step | live (read when a run is made) |  | admin |
| `step-titles` | map | `{}` |  | server | A step's title on the screens ({approver: CRO}) | live (read when a run is made) |  | admin |
| `step-titles.*` | string | none |  | server | One step's title | live (read when a run is made) |  | admin |

### SIMM parameter files: `drishti.simm.parameters-dir`

One YAML per ISDA SIMM version (`drishti.simm.parameters-dir`, default `./config/simm`), read and checked at start: the bank's licensed numbers, never shipped. The structure is `docs/architecture/capital-parameters/simm.yaml`, every number empty; a version with a number missing is refused with `DRS-5333` ([SIMM_GUIDE.md section 4](../guides/SIMM_GUIDE.md#4-the-licensed-parameter-file)).

#### A SIMM version's parameter file: `<drishti.simm.parameters-dir>/<version>.yaml` (docs/guides/SIMM_GUIDE.md section 4)

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `version` | string | `none (licensed)` |  | server | The SIMM version as the CSA names it (2.8+2512); a netting set's CSA picks its file by it | restart |  | admin |
| `effective-from` | date | `none (licensed)` |  | server | The version's effective date; the default version is the installed one with the latest | restart |  | admin |
| `source` | string | empty |  | server | Where the numbers come from (the licensed methodology document) | restart |  | admin |
| `holding-period-days` | integer | `10` |  | server | SIMM's margin period of risk (a methodology constant) | restart |  | admin |
| `calculation-currency` | string | `USD` |  | server | The currency the thresholds are set in; converted at the date's FX for another calculation currency | restart |  | admin |
| `regimes` | list | `none (licensed)` |  | server | The regime codes the version recognises; a CSA's other code is ignored and named (DRS-5334) | restart |  | admin |
| `product-classes` | list | `[RatesFX, Credit, Equity, Commodity]` |  | server | SIMM's four product classes (added, no correlation between them) | restart |  | admin |
| `risk-classes` | list | `[InterestRate, CreditQ, CreditNonQ, Equity, Commodity, FX]` |  | server | The six risk classes, the axis of risk-class-correlation | restart |  | admin |
| `risk-class-correlation` | list | `none (licensed)` |  | server | ψ: the 6 × 6 correlation between risk classes within a product class (symmetric, unit diagonal) | restart |  | admin |
| `InterestRate` | map | `none (licensed)` |  | server | The InterestRate risk class's numbers (the keys below) | restart |  | admin |
| `InterestRate.tenors` | list | `none (licensed)` |  | server | The rates vertices (2w to 30y), the axis of the risk weights and the tenor correlation | restart |  | admin |
| `InterestRate.buckets` | list | `none (licensed)` |  | server | The volatility groups (the dictionary's SIMM\_BUCKET of a rates factor) | restart |  | admin |
| `InterestRate.delta-risk-weights.*` | list | `none (licensed)` |  | server | Per bucket, one risk weight per tenor (per bp) | restart |  | admin |
| `InterestRate.inflation-risk-weight` | number | `none (licensed)` |  | server | The inflation delta risk weight (per bp) | restart |  | admin |
| `InterestRate.cross-currency-basis-risk-weight` | number | `none (licensed)` |  | server | The cross-currency basis risk weight (per bp) | restart |  | admin |
| `InterestRate.tenor-correlation` | list | `none (licensed)` |  | server | The tenor × tenor correlation (also the vega and, squared, the curvature correlation) | restart |  | admin |
| `InterestRate.sub-curve-correlation` | number | `none (licensed)` |  | server | φ: two sub-curves of one currency | restart |  | admin |
| `InterestRate.inflation-correlation` | number | `none (licensed)` |  | server | Inflation to a curve of its currency | restart |  | admin |
| `InterestRate.cross-currency-basis-correlation` | number | `none (licensed)` |  | server | The basis to anything of its currency | restart |  | admin |
| `InterestRate.across-currencies` | number | `none (licensed)` |  | server | γ across currencies (with g\_bc from the currencies' concentration factors) | restart |  | admin |
| `InterestRate.vega-risk-weight` | number | `none (licensed)` |  | server | The rates vega risk weight | restart |  | admin |
| `InterestRate.historical-volatility-ratio` | number | `none (licensed)` |  | server | HVR\_IR: the rates curvature margin is multiplied by HVR\_IR⁻² | restart |  | admin |
| `InterestRate.threshold-groups.*` | list | `none (licensed)` |  | server | The currencies of each concentration group (any other currency: other) | restart |  | admin |
| `InterestRate.concentration-thresholds.*` | map | `none (licensed)` |  | server | delta (per bp) and vega thresholds by group, other included | restart |  | admin |
| `CreditQ.*` | map | `none (licensed)` |  | server | tenors, buckets, delta-risk-weights (by bucket and Residual, per bp), correlation (same-issuer, different-issuer, residual-same-issuer, residual-different-issuer), across-buckets (bucket × bucket), base-correlation-risk-weight, base-correlation-correlation, vega-risk-weight, concentration-thresholds (delta and vega by bucket) | restart |  | admin |
| `CreditNonQ.*` | map | `none (licensed)` |  | server | as CreditQ, with across-buckets one number and no base correlation | restart |  | admin |
| `Equity.*` | map | `none (licensed)` |  | server | buckets, delta-risk-weights (by bucket and Residual, per 1 %), correlation (by bucket), across-buckets (bucket × bucket), vega-risk-weight (one number or by bucket), historical-volatility-ratio, zero-curvature-buckets (volatility indices), concentration-thresholds | restart |  | admin |
| `Commodity.*` | map | `none (licensed)` |  | server | buckets, delta-risk-weights (by bucket, per 1 %), correlation (by bucket), across-buckets, vega-risk-weight, historical-volatility-ratio, concentration-thresholds | restart |  | admin |
| `FX.*` | map | `none (licensed)` |  | server | high-volatility-currencies, delta-risk-weights (regular-regular, regular-high, high-regular, high-high), correlation (by the calculation currency's group: regular-regular, regular-high, high-high), vega-correlation, vega-risk-weight, historical-volatility-ratio, threshold-groups, concentration-thresholds | restart |  | admin |

### The data inbox's routing rules: `config/dataload.yaml`

Where a file dropped in the data inbox goes: a file-name pattern to a pack and kind (`drishti.dataload.rules-file`), read again at every cycle ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#7-the-data-inbox-drop-files-and-they-load), [DATA_LAKE_GUIDE.md](../guides/DATA_LAKE_GUIDE.md)). The inbox's own settings are in `config/engines/dataload.yaml`.

#### `dataload`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `dataload.rules` | list | `[]` |  | server (the inbox's owner) | the rules, first match wins: match (a glob on the file name), pack, kind, and optionally connector, table, date-from, date-field, key, lake, format | live |  | admin |
| `dataload.unmatched` | string | `fail` |  | server (the inbox's owner) | a file no rule matches goes to failed/ with the reason; fail is the only choice (a file is never guessed) | live |  | admin |

### The UI kit: `config/ui-kit/brand.yaml`

The site's brand for pages built with the Drishti UI kit (`drishti.ui-kit.dir`).

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `name` | string | `Drishti` |  | server | The Drishti UI kit's brand (RACHANA\_REFERENCE.md, "The Drishti UI kit"): the name, the logos and the colours per theme that a screen, a dynamic screen or a Gen UI stream may use. A site overrides any key in &lt;data folder&gt;/ui-kit/brand.yaml (drishti.ui-kit.brand-override): its keys win over these, map by map. Logo and icon values are paths under the kit's assets/ folder (a site's &lt;data folder&gt;/ui-kit/assets/ is looked in first); GET /api/v1/ui-kit gives each its versioned URL. | restart |  | admin |
| `tagline` | string | `Any data. Any domain. One grammar.` |  | server | the line under the name | restart |  | admin |

#### `logos`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `logos.full` | string | `logos/drishti-logo.png` |  | server | the full logo (a path under assets/) | restart |  | admin |
| `logos.mark` | string | `logos/drishti-mark.svg` |  | server | the mark alone | restart |  | admin |
| `logos.icon` | string | `logos/drishti-icon.png` |  | server | the square icon | restart |  | admin |

#### `icons`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `icons.favicon` | string | `icons/favicon.svg` |  | server | the browser icon (SVG) | restart |  | admin |
| `icons.favicon-png` | string | `icons/favicon-32.png` |  | server | the browser icon (32 px PNG) | restart |  | admin |
| `icons.app-192` | string | `icons/icon-192.png` |  | server | the home-screen icon, 192 px | restart |  | admin |
| `icons.app-512` | string | `icons/icon-512.png` |  | server | the home-screen icon, 512 px | restart |  | admin |

#### `themes`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `themes.<theme>.bg` | string | `"#0e1628"` |  | server | the page background (--d-bg) | restart |  | admin |
| `themes.<theme>.surface` | string | `"#16213a"` |  | server | panels and cards (--d-surface) | restart |  | admin |
| `themes.<theme>.border` | string | `"#26345a"` |  | server | lines and borders (--d-border) | restart |  | admin |
| `themes.<theme>.ink` | string | `"#e7ecf5"` |  | server | the text (--d-ink) | restart |  | admin |
| `themes.<theme>.muted` | string | `"#93a0b8"` |  | server | secondary text (--d-muted) | restart |  | admin |
| `themes.<theme>.accent` | string | `"#e8a33a"` |  | server | the accent: buttons, the focus, highlights (--d-accent) | restart |  | admin |
| `themes.<theme>.on-accent` | string | `"#14100a"` |  | server | text on the accent (--d-on-accent) | restart |  | admin |
| `themes.<theme>.link` | string | `"#7fb2ff"` |  | server | links (--d-link) | restart |  | admin |
| `themes.<theme>.pos` | string | `"#7fb2ff"` |  | server | positive numbers (--d-pos) | restart |  | admin |
| `themes.<theme>.neg` | string | `"#f2955a"` |  | server | negative numbers (--d-neg) | restart |  | admin |

### AI providers and MCP servers: `config/ai/`, `config/mcp/`

One YAML per provider in `config/ai/` and per MCP server in `config/mcp/`, for the server (`drishti.ai.dir`, `drishti.mcp.dir`) and the console (`ai.dir`, `mcp.dir`); the folders are looked at every few seconds, so a changed file needs no restart. Every key a shipped example (`*.yaml.example`) uses ([AI.md › Model providers](AI.md#1-model-providers), [AI.md › The MCP client](AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)).

#### A provider file: `config/ai/<name>.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `type` | string | `anthropic` |  | server and console | Copy to config/ai/anthropic.yaml. The Anthropic Messages API. | live |  | admin |
| `base-url` | string | `https://my-resource.openai.azure.com` |  | server and console | also read from AZURE\_OPENAI\_ENDPOINT | live |  | admin |
| `api-key` | string | none | `COHERE_API_KEY` | server and console | COHERE\_API\_KEY or CO\_API\_KEY is also read when this line is left out (secret) | live |  | admin |
| `anthropic-version` | string | `"2023-06-01"` |  | server and console | the anthropic-version header sent with every call | live |  | admin |
| `timeout` | duration | `60s` |  | server and console | Credentials: the standard AWS chain (environment, shared profile, SSO, container/instance roles, IRSA) unless you name them: profile: my-profile aws-access-key-id: ${AWS\_ACCESS\_KEY\_ID} aws-secret-access-key: ${AWS\_SECRET\_ACCESS\_KEY} aws-session-token: ${AWS\_SESSION\_TOKEN} base-url: https://vpce-....bedrock-runtime.us-east-1.vpce.amazonaws.com # an endpoint override embedding-input-type: search\_document # Cohere embedding models | live |  | expert |
| `default-model` | string | `fast` |  | server and console | the model a call naming none uses (a logical name of this file) | live |  | admin |
| `models` | list | empty |  | server and console | live-models: true # list what the server serves (GET /models); on by default for the keyless presets the server's own models are not in the catalogue | live |  | admin |
| `models[].name` | string | `fast` |  | server and console | the logical name features use | live |  | admin |
| `models[].id` | string | `claude-sonnet-4-5` |  | server and console | the provider's model id | live |  | admin |
| `models[].max-tokens` | integer | `400` |  | server and console | the longest answer asked of the model unless a feature asks less | live |  | admin |
| `models[].capabilities` | list | `[chat, tool-calls, json-mode]` |  | server and console | what it can do: chat, streaming, tool-calls, embeddings, json-mode; a call needing more is refused | live |  | admin |
| `enabled` | boolean | `false` |  | server and console | false: the file is read but the provider stays off | live |  | admin |
| `api-style` | string | `v1` |  | server and console | v1: /openai/v1/...; deployments: /openai/deployments/&lt;name&gt;/...?api-version= | live |  | admin |
| `auth` | string | `api_key` |  | server and console | api-version: 2024-10-21 # for api-style: deployments api\_key \| bearer \| entra | live |  | admin |
| `deployments.<model>` | string | `my-gpt4o-deployment` |  | server and console | model id: deployment name; each is also a model of this provider | live |  | admin |
| `region` | string | `us-east-1` |  | server and console | also AWS\_REGION / AWS\_DEFAULT\_REGION | live |  | admin |
| `retries` | integer | `0` |  | server and console | extra attempts after a failure | live |  | admin |
| `models[].kind` | string | `embedding` |  | server and console | chat (the default) or embedding | live |  | admin |
| `models[].cost-per-1k-input` | number | `0.001` |  | server and console | so cost limits and audit can be exercised | live |  | admin |
| `models[].cost-per-1k-output` | number | `0.0006` |  | server and console | price per 1,000 output tokens | live |  | admin |
| `mock.latency-ms` | integer | `0` |  | server and console | reply: "a fixed answer" # omit to echo the last user message simulated delay | live |  | expert |
| `mock.dimensions` | integer | `8` |  | server and console | fail: "simulated outage" # every call fails with this message fail-every: 5 # every 5th call fails embedding vector size | live |  | admin |
| `mock.rules` | list | empty |  | server and console | the first rule whose regex is found in the last user message applies | live |  | admin |
| `completions-path` | string | `/v1/chat/completions` |  | server and console | Azure: /openai/deployments/&lt;name&gt;/chat/completions?api-version=... | live |  | admin |
| `models[].temperature` | number | `0.2` |  | server and console | the sampling temperature features get unless they ask otherwise | live |  | admin |

#### An MCP server file: `config/mcp/<name>.yaml`

| Key | Type | Default | Env var | Applies to | Meaning | Restart or live | Since | Tier |
|---|---|---|---|---|---|---|---|---|
| `transport` | string | `mock` |  | server and console | Copy to config/mcp/mock.yaml: an in-process MCP server with scripted tools, resources and prompts. No network. | live |  | admin |
| `mock.tools` | list | empty |  | server and console | the scripted tools | live |  | admin |
| `mock.tools[].name` | string | `add` |  | server and console | the tool's name | live |  | admin |
| `mock.tools[].description` | string | `Adds two numbers` |  | server and console | what the tool says it does | live |  | admin |
| `mock.tools[].reply` | string | `"sum of ${a} and ${b}"` |  | server and console | ${arg} is replaced by the call's argument; omit to echo the arguments | live |  | admin |
| `mock.tools[].error` | boolean | `true` |  | server and console | the result is flagged as an error | live |  | admin |
| `mock.resources` | list | empty |  | server and console | the scripted resources, {uri, text} each | live |  | admin |
| `mock.prompts` | list | empty |  | server and console | the scripted prompts, {name, description} each | live |  | admin |
| `url` | string | `http://localhost:3002/mcp` |  | server and console | SAJHA's server.port; behind TLS use https:// (and tls.\* below) | live |  | admin |
| `timeout` | duration | `30s` |  | server and console | the longest one call may take | live |  | expert |
| `headers.<header>` | string | none | `SAJHA_API_KEY` | server and console | a SAJHA API key (Admin &gt; API keys); or Authorization: Bearer ${SAJHA\_TOKEN} | live |  | admin |
| `allowed-tools` | list | empty |  | server and console | empty = every tool the key can see (SAJHA lists ~500); list the few Drishti may use | live |  | admin |

### Environment variable index

Every environment variable a source names (`${NAME:default}`) or the code reads, and what it sets. Any server key can also be set by Spring's relaxed name (`DRISHTI_HISTORY_MAXDAYS` is `drishti.history.max-days`) and any console key by `DRISHTI_CONSOLE__SECTION__KEY` ([Two kinds of environment variable](#two-kinds-of-environment-variable)).

| Variable | Sets | Default | Read by | Meaning |
|---|---|---|---|---|
| `COHERE_API_KEY` | `api-key` (AI) | none | AI | COHERE\_API\_KEY or CO\_API\_KEY is also read when this line is left out |
| `DRISHTI_ABOUT_DIR` | pack generators | none | tools | Where the pack generators write the About files |
| `DRISHTI_ABOUT_PREFETCH` | `ui.about_prefetch` | `true` | console | About this page: fetch the page's explanation once the page is idle, so labels with a glossary entry are underlined before the drawer opens. Costs the server one explain (a re-run of the view, cached for drishti.explain.cache-ttl) per page view; set false on a busy installation and the labels are underlined when the drawer is first opened. |
| `DRISHTI_ACCESS_LOG` | `drishti.access-log.enabled` | `true` | server | record who looked at what (Admin -&gt; Access) |
| `DRISHTI_ACTIVEMQ_ENABLED` | `drishti.sources.plugins.activemq.enabled` | `false` | server | run the activemq source as itself |
| `DRISHTI_ADMISSION_ENABLED` | `drishti.admission.enabled` | `true` | server | false: nothing waits (Admin -&gt; Health still shows the readings) |
| `DRISHTI_AEROSPIKE_HOSTS` | aerospike profile | `localhost:3000` | profile | The aerospike profile's hosts |
| `DRISHTI_AEROSPIKE_NAMESPACE` | aerospike profile | `test` | profile | Its namespace |
| `DRISHTI_AI_DEFAULT_MODEL` | `drishti.ai.default-model`, `ai.default_model` | empty | console, server | provider/model, or a model name only one provider has; used when a call names none |
| `DRISHTI_AI_DIR` | `drishti.ai.dir`, `ai.dir` | `./config/ai` | console, server | one YAML per provider: type, base-url, api-key as ${ENV}, models with logical names |
| `DRISHTI_AI_ENABLED` | `drishti.ai.enabled`, `ai.enabled` | `false` | console, server | turn on the LLM provider layer: provider files in drishti.ai.dir are read and models become callable |
| `DRISHTI_ALERTS_ENABLED` | `drishti.metrics.alerts.enabled` | `true` | server | evaluate the platform alert rules (Admin -&gt; Health; the alert engine lists them: Alerts, GET /api/v1/admin/alerts) |
| `DRISHTI_ALERTS_FILE` | `drishti.metrics.alerts.file` | `./config/alerts/platform-alerts.yaml` | server | the rules: metric, threshold, how long, severity (a missing file: no rules, and Health says so) |
| `DRISHTI_ALLOW_INSECURE_TLS` | tls.insecure-trust-all | none | server | Must be true for a connector to run with tls.insecure-trust-all: true (development only) |
| `DRISHTI_API_DOCS` | `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled` | `true` | server | set explicitly, so a start does not warn that they are on by default |
| `DRISHTI_API_DOCS_DIR` | `help.api_docs_dir` | `web/apidocs` | console | Help -&gt; Developer (IMPLEMENTATION\_PLAN row 2g): the Java and Python API references tools/api\_docs.py build writes here, one folder per version (generated at build time, never committed); relative to the console folder. Missing: the page says how to build them. |
| `DRISHTI_ARCHIVE_MAX_MB` | the loaders' archives (lake build, lake load, load\_rows.py, convert; the Java loader's source.archive.max-mb) | `102400` | tools | The most an archive (.zip, .tar.gz, .tgz, .tar) may expand to, in MB: its entries' declared sizes, and what it expands to as it is read (DATA\_GUIDE.md section 2.7) |
| `DRISHTI_ARCHIVE_MAX_MEMBERS` | the loaders' archives (source.archive.max-members) | `100000` | tools | The most entries an archive may hold |
| `DRISHTI_ARCHIVE_MAX_RATIO` | the loaders' archives (source.archive.max-ratio) | `200` | tools | The most an archive may expand to, as a multiple of its own size (at least 1 MB is always allowed): the zip-bomb guard |
| `DRISHTI_ARROW_EXCHANGE_MB` | the launchers' -XX:MaxDirectMemorySize and -Ddrishti.arrow.max-bytes | `512` | tools | Arrow's budget in MB beyond Arrow Flight: the compute workers' exchange and BI answers sent as Arrow (the official Arrow library's buffers are direct memory); added to the direct-memory limit with Flight's memory.total-mb when its engine is on (tools/lake/memory\_caps.py) |
| `DRISHTI_ARROW_MB` | the Windows and ECS launchers' -XX:MaxDirectMemorySize and -Ddrishti.arrow.max-bytes | `512` | tools | Arrow's whole budget in MB, used as it is (an ECS task has no Python: set it to what tools/lake/memory\_caps.py --arrow-only prints); unset, the Windows launcher asks memory\_caps.py and the ECS entrypoint uses 512 |
| `DRISHTI_ARTIFACTS_CONSOLE_PORT` | screenshot tools | none | tools | The console port of the artifacts screenshots |
| `DRISHTI_ARTIFACTS_SERVER_PORT` | screenshot tools | none | tools | The server port of the artifacts screenshots |
| `DRISHTI_ASK_ENABLED` | `drishti.explain.ask.enabled` | `false` | server | turn on Ask about this page (also list packs in drishti.explain.ask.packs) |
| `DRISHTI_ASK_MODEL` | `drishti.explain.ask.model` | empty | server | the model Ask uses: a logical model of the AI layer (provider/model; a provider file in drishti.ai.dir, docs/admin/AI.md) |
| `DRISHTI_ASYNC_TIMEOUT` | `spring.mvc.async.request-timeout` | `30m` | server | how long a streamed answer may take: a workbook download (the VaR Playground, the Why it moved working, the capacity workbook), a BI export; an answer cut at this limit arrives incomplete |
| `DRISHTI_AUDIT_FILE` | `drishti.identity.audit-file` | `./data/identity/audit.jsonl` | server | the pre-1.10 audit file, imported into the database once |
| `DRISHTI_AUTH_ENABLED` | `auth.enabled` | `false` | console | off for local development |
| `DRISHTI_BACKEND_DISCOVER` | `backend.discover` | `true` | console | pull the cluster's member list from the server in use, and keep it (&lt;console data dir&gt;/net/servers.json) |
| `DRISHTI_BACKEND_URL` | `backend.url` | `http://127.0.0.1:18480` | console | where the server is (one server; backend.urls for several) |
| `DRISHTI_BACKEND_URLS` | `backend.urls` | empty | console | Several servers of one Drishti\*net cluster (docs/admin/OPERATIONS.md, "The console and several servers"): one is enough, the console learns the rest. Empty: backend.url, as before. Each session keeps to one server while it answers and falls over to the next when it does not. e.g. https://drishti-a:8480,https://drishti-b:8480 |
| `DRISHTI_BI_BOARD_ROTATE_SECONDS` | `bi.board.rotate_seconds` | `30` | console | a board in kiosk mode turns to its next page after this long, unless the board sets rotate:; 3 to 3600 |
| `DRISHTI_BI_DIR` | `drishti.bi.dir` | `./config/bi` | server | Rupaka files: datasets/, reports/, boards/, notebooks/, python/ (config/bi/README.md); never inside a pack |
| `DRISHTI_BI_ENABLED` | `bi.enabled` | `true` | console | false: no BI top-bar menu, no /bi pages (the server's drishti.bi.enabled is separate) |
| `DRISHTI_BI_LIVE_MIN_INTERVAL_MS` | `bi.live.min_interval_ms` | `250` | console | a report redraws its live visuals at most this often (a burst of ticks becomes one redraw); 100 to 5000 |
| `DRISHTI_BI_LIVE_STALE_SECONDS` | `bi.live.stale_seconds` | `30` | console | no tick or status from the server for this long: the report says "Stale"; 5 to 600 |
| `DRISHTI_BI_MIGRATE_ON_START` | `drishti.bi.migrate.on-start` | `true` | server | at start: stored datasets and models into the table layer, 2.3 boards into reports; audited, the text before kept; idempotent |
| `DRISHTI_BI_NODE` | `drishti.bi.exec.node` | empty | server | this Java node's name when several serve the site (blank: one node) |
| `DRISHTI_BI_NOTEBOOK_MAX_ROWS` | `bi.notebook.max_rows` | `10000` | console | most rows of an extract (the reader's rows, cut here); 10 to 100000 |
| `DRISHTI_BI_RUNS_FILE` | `drishti.bi.runs.file` | `./config/runs/runs.yaml` | server | BI runs (docs/architecture/BI\_DESIGN.md); ready-made in config/runs.examples/ |
| `DRISHTI_BI_STORE` | `drishti.bi.query.store-dir` | `./data/bi-store` | server | the BI store: typed Parquet layers (columns, rollups) written here; never under the repository |
| `DRISHTI_BI_TRANSFORM_PREVIEW_ROWS` | `bi.transform.preview_rows` | `1000` | console | rows of each input the step-by-step run starts from (the reader's rows, asked of the server); 10 to 50000 |
| `DRISHTI_CACHE_RESET_AT` | trading-stream cache reset | `02:00` | packs | The disk cache's nightly clearing time (New York) |
| `DRISHTI_CACHE_ROOT` | trading-stream cache root | `./data/cache` | packs | Disk cache root (one folder per connector) |
| `DRISHTI_CALENDAR` | `drishti.business-date.calendar` | `USNY` | server | Every read is for a business date. The default is today in `zone`, rolled back to a business day of `calendar` (weekends and holidays roll to the previous business day). Users pick another date in the top bar; dated sources (Delta Lake, dated folders, databases with a date column) then read that date. USNY, GBLO, EUTA, JPTO, CATO or joint, e.g. USNY+GBLO |
| `DRISHTI_CCR_DERIVED_ENABLED` | counterparty-risk's derived connector | `true` | packs | The counterparty-risk pack's derived kinds (netting-set and counterparty totals) on or off |
| `DRISHTI_COLLAB_DIR` | `drishti.collab.dir` | `./data/collab` | server | the file store, and exports |
| `DRISHTI_COLLAB_ENABLED` | `drishti.collab.enabled` | `true` | server | share with a note, the directory and the inbox (docs/architecture/COLLABORATION.md) |
| `DRISHTI_COMPUTE_ENABLED` | `compute.enabled` (compute) | `false` | compute | run compute workers; false (as shipped) starts none |
| `DRISHTI_COMPUTE_SAMPLE_WORKERS` | `compute.pools.<pool>.workers.max` (compute) | `4` | compute | as many as this node should run: configuration only, no limit in the code |
| `DRISHTI_CONNECTORS_DIR` | `drishti.sources.connectors-dir` | `./config/connectors` | server | Named connectors: several instances of a plugin, each with its own settings. They live in FILES, one per connector: &lt;connectors-dir&gt;/&lt;name&gt;.yaml the file name is the connector's logical name; packs refer to it by that name. The folder is watched: a changed file restarts only that connector, a new file starts one, a deleted file stops it. See docs/connectors/CONNECTOR\_FILES.md and Admin -&gt; Connectors. Ready-made files: config/connectors.examples/. created on first write |
| `DRISHTI_CONSOLE_BACKEND_CHECK_SECONDS` | `backend.check_seconds` | `5` | console | how often the console asks each server if it is there (0 = never); pages show a banner and recover when it returns |
| `DRISHTI_CONSOLE_DATA_DIR` | `backend.data_dir` | empty | console | what the console keeps between runs (net/servers.json); blank: &lt;data dir&gt;/console |
| `DRISHTI_CONSOLE_HOST` | `server.host` | `0.0.0.0` | console | every interface, so other machines (and phones) can open it; 127.0.0.1 = this machine only |
| `DRISHTI_CONSOLE_PORT` | `server.port` | `17480` | console | the console's HTTP port |
| `DRISHTI_CONSOLE_RELAY_ACROSS_HOSTS` | `live.relay.across_hosts` | `false` | console | true: consoles on several hosts behind one load balancer pass live-channel calls to each other, so the load balancer needs no stickiness |
| `DRISHTI_CONSOLE_RELAY_ADVERTISE` | `live.relay.advertise` | empty | console | the address the other consoles reach this one at (blank: this machine's LAN address; never a loopback one) |
| `DRISHTI_CONSOLE_RELAY_BIND` | `live.relay.bind` | `0.0.0.0` | console | the interface the relay listens on across hosts (within one host it listens on loopback only) |
| `DRISHTI_CONSOLE_RELAY_KEY` | the live-channel relay key | none | console | Signs the relay messages between the console's workers and, with live.relay.across\_hosts, between consoles: the same secret on every console (the launcher makes one for its own workers when it is not set; CLUSTER\_DEPLOYMENT.md section 9.4) |
| `DRISHTI_CONSOLE_RELAY_KEY_MADE` | (set by the launcher) | none | console | Set by run\_drishti\_web.py when it made the relay key itself: the relay then stays on this host |
| `DRISHTI_CONSOLE_RELAY_PORT` | `live.relay.port` | `17490` | console | across hosts, worker n listens on port + n (port .. port + server.workers - 1): open these between the consoles; 0 = any free port |
| `DRISHTI_CONSOLE_SHUTDOWN_SECONDS` | `server.shutdown_seconds` | `5` | console | on a stop, how long open streams (live pages) may stay before they are closed; the browser then reconnects |
| `DRISHTI_CONSOLE_TMP_DIR` | `tmp_dir` | empty | console | base of the console's temp folder drishti-console-&lt;pid&gt; (blank: DRISHTI\_TMP\_DIR, then &lt;data dir&gt;/tmp/console when started by run\_drishti\_web.py); removed on exit; left-over ones are listed on Health, never deleted automatically |
| `DRISHTI_CONSOLE_URL` | `drishti.embed.audiences`, `drishti.collab.console-url` | `[""]` | server | the consoles that may accept embed tokens (their public URLs); the first is the default |
| `DRISHTI_CONSOLE_WORKERS` | `server.workers` | `4` | console | processes serving pages (one Python process uses one core: ~40 pages/s measured); 1 = one process |
| `DRISHTI_DATALOAD_DIR` | `drishti.dataload.dir` (engine) | `./data/dataload` | engine | the inbox's folder: inbox/, inprocess/, outbox/, failed/, ledger/ and inbox.lock, all on one file system |
| `DRISHTI_DATALOAD_ENABLED` | `drishti.engines.dataload.enabled` (engine) | `false` | engine | run the data inbox on this node: files dropped in &lt;dir&gt;/inbox with a .ready marker are loaded into the lake every interval; off as shipped |
| `DRISHTI_DATALOAD_LAKE` | `drishti.dataload.lake` (engine), drishti.dataload.lake | `demo` | engine, server | the lake (config/lakes/&lt;lake&gt;.yaml, docs/guides/DATA\_LAKE\_GUIDE.md) the tables are written to; a kind the lake file lacks gets its pack's recommended entry copied there first (a rule's lake: wins for its files; a folder is the old form, read with one warning) |
| `DRISHTI_DATALOAD_LOADER_HEAP` | `drishti.dataload.loader-heap` (engine) | `4g` | engine | the loader's own heap (the child Java process's -Xmx): a load never uses the server's heap |
| `DRISHTI_DATALOAD_OWNER` | `drishti.dataload.owner` (engine) | `true` | engine | this node runs the inbox's cycles; in a cluster set it true on one node only (the folder lock also keeps a second runner out) |
| `DRISHTI_DATALOAD_RULES` | `drishti.dataload.rules-file` (engine) | `./config/dataload.yaml` | engine | the routing rules: a file-name pattern to a pack and kind, read again every cycle |
| `DRISHTI_DATA_DIR` | `drishti.data.dir`, `lakes.data_dir` | `./data` | console, server | The umbrella folder every runtime path below defaults under (identity database, caches, loads, collab, designs, reports, the pack overlay and settings, governance, the sample feeds). Each path's own setting still overrides its default. |
| `DRISHTI_DEFAULT_PACKS` | `drishti.packs.default-for-users` | empty | server | Packs new users get until an admin says otherwise (empty: every installed pack). |
| `DRISHTI_DELTA_ENGINE` | `engine` (connector), delta engine | `native` | connector, packs | Reader engine: native (Delta Kernel without Hadoop; Windows), hadoop (also abfs://, gs://) or auto (native on Windows); not set: DRISHTI\_DELTA\_ENGINE, else native |
| `DRISHTI_DELTA_ROOT` | `drishti.var.official.root` (engine), `drishti.ccr.exposure.official.root` (engine), the demo lake's root (config/lakes/demo.yaml) | `./data/delta` | engine, packs | a folder or s3://bucket/prefix |
| `DRISHTI_DEMO_ENABLED` | `drishti.sources.plugins.demo.enabled` | `true` | server | run the demo source (the packs' sample data) |
| `DRISHTI_DOCTOR_PORTS` | drishti.py doctor | none | tools | The ports doctor checks |
| `DRISHTI_DRAIN_TOKEN` | rolling.py, deploy/bin/drishti-node.sh | none | tools | An administrator's personal API token with the node:drain scope: the stop hooks and the rolling upgrade runner drain a server with it (secret; else DRISHTI\_DRAIN\_TOKEN\_FILE, else DRISHTI\_TOKEN) |
| `DRISHTI_DRAIN_WAIT` | deploy/bin/drishti-node.sh | `90` | tools | Seconds the stop hook waits for safeToStop after the drain (ECS on Fargate: at most 120 s for the whole stop) |
| `DRISHTI_DUCKDB_MEMORY` | duckdb profile | `1GB` | profile | The shared DuckDB instance's memory limit |
| `DRISHTI_DUCKDB_PATH` | duckdb profile | `./data/duckdb/drishti.duckdb` | profile | The duckdb profile's file (one file, a schema per domain) |
| `DRISHTI_EDITION` | `drishti.edition` | empty | server | The edition (docs/admin/EDITIONS.md): blank is the full Drishti; risk is Drishti Risk. It names config/editions/&lt;name&gt;.yaml, read as a layer under the site's configuration before the packs load; every member of a cluster sets the same value, and the console learns it from /api/v1/about. A change needs a restart. |
| `DRISHTI_EDITIONS_DIR` | `drishti.editions.dir`, `editions.dir` | `./config/editions` | console, server | the folder of the edition files (and features.yaml, default-packs.yaml) |
| `DRISHTI_EDIT_ENABLED` | `edit.enabled` (write-back) | `false` | write-back | the write-back switch: false refuses every change (UI, CLI, API) and shows no Submit |
| `DRISHTI_EDIT_FILE` | `drishti.edit.file` | `./config/edit/edit.yaml` | server | Write-back (docs/guides/WRITE\_BACK.md): its settings are in their own folder (edit.yaml, lanes.yaml, policy.yaml, retention.yaml). Off as shipped (edit.enabled: false there): a fresh install or an upgrade is read-only. |
| `DRISHTI_EMBED_ENABLED` | `drishti.embed.enabled`, `embed.enabled` | `false` | console, server | off: no token endpoint, embed tokens refused. Needs drishti.security.enabled |
| `DRISHTI_EMBED_KEY_FILE` | `drishti.embed.signing-key` | empty | server | PEM with a PKCS#8 EC PRIVATE KEY and a PUBLIC KEY block (ES256); empty = generated at start |
| `DRISHTI_EMBED_SIGN_IN` | `embed.sign_in.enabled` | `true` | console | needs auth.oidc.enabled; off: the window answers DRS-8003 and pages keep the token exchange |
| `DRISHTI_EMBED_SIGN_IN_WINDOW` | `drishti.embed.sign-in-window` | `true` | server | the renderer's auth: 'popup' (CLIENT\_RENDERER.md 10.1): a registered host's page signs its user in |
| `DRISHTI_ENGINES_DIR` | `drishti.engines.dir` | `./config/engines` | server | the folder of the engines' files Admin -&gt; Engines writes (the imports above name the same folder) |
| `DRISHTI_EXPOSURE_ENABLED` | `drishti.engines.exposure.enabled` (engine) | `true` | engine | run the exposure engine on this node: /api/v1/exposure, EXP, the exposure-engine connector (live) |
| `DRISHTI_EXPOSURE_HOT_DATES` | `drishti.ccr.exposure.hot-dates` (engine) | `10` | engine | business dates kept open: the least recently used leaves first; the date just used, the newest business date and the dates pinned in memory always stay (as VaR's) |
| `DRISHTI_EXPOSURE_HOT_DIR` | `drishti.ccr.exposure.hot-folder` (engine) | `./data/ccr/hot` | engine | the hot files, one folder per business date; never under the repository |
| `DRISHTI_EXPOSURE_KEEP_HOT_FILES` | `drishti.ccr.exposure.keep-on-shutdown` (engine) | `false` | engine | false: deleted at shutdown; the server images set true (PO-10) |
| `DRISHTI_EXPOSURE_MAX_DISK_GB` | `drishti.ccr.exposure.max-disk-gb` (engine) | `0` | engine | most disk the hot files take; 0: a quarter of the disk; it wins over the retention (PO-3) |
| `DRISHTI_EXPOSURE_MEMORY_GB` | `drishti.ccr.exposure.memory-budget-gb` (engine) | `0` | engine | most memory one date's netting-set matrices take while it loads (0: a quarter of the heap); over it: 503 DRS-5445 |
| `DRISHTI_EXPOSURE_OFFICIAL` | `drishti.ccr.exposure.official.enabled` (engine) | `true` | engine | capture every netting set, counterparty, group and hierarchy node when a date's run is complete |
| `DRISHTI_EXPOSURE_OFFICIAL_FORMAT` | `drishti.ccr.exposure.official.format` (engine) | `delta` | engine | delta or iceberg; BI's exposure-results connector follows it |
| `DRISHTI_EXPOSURE_OPEN_HEAP_MB` | `drishti.ccr.exposure.open-heap-mb` (engine) | `0` | engine | the byte safety net over the open dates' heap (0: automatic, as VaR's); over it the least recently used dates leave |
| `DRISHTI_EXPOSURE_TIER` | `drishti.ccr.exposure.tier` (engine) | `mapped` | engine | mapped: each date a float32 hot file, memory-mapped; heap: float64 on the heap |
| `DRISHTI_EXPOSURE_VERSION_CHECK` | `drishti.ccr.exposure.version-check` (engine) | `30s` | engine | an open date compares its lake version this often; moved: reloaded in the background (0: off) |
| `DRISHTI_EXTERNAL_LIB` | `external_lib` | empty | console | base of the external library folder (blank: external\_lib in the repository root); &lt;base&gt;/python is importable, &lt;base&gt;/java is the server's |
| `DRISHTI_FEEDS` | `drishti.sources.plugins.file.settings.root` | `./data/feeds` | server | the folder the bundled file source reads |
| `DRISHTI_FEED_ECB_ESTR` | ecb-estr-feed enabled | `false` | packs | The ECB euro short-term rate feed on |
| `DRISHTI_FEED_ECB_FX` | ecb-fx-feed enabled | `false` | packs | The ECB reference FX rates feed on |
| `DRISHTI_FEED_FRED` | fred-feed enabled | `false` | packs | The FRED feed on (needs FRED\_API\_KEY) |
| `DRISHTI_FEED_NYFED_SOFR` | nyfed-sofr-feed enabled | `false` | packs | The NY Fed SOFR public feed on |
| `DRISHTI_FEED_US_TREASURY` | us-treasury-feed enabled | `false` | packs | The US Treasury yield curve feed on |
| `DRISHTI_FILES_ROOT` | files profile | `./data/files` | profile | The files profile's root (&lt;root&gt;/&lt;domain&gt;/&lt;date&gt;/&lt;kind&gt;.jsonl) |
| `DRISHTI_FISCAL_YEAR_END` | `drishti.var.fiscal-year-end` (engine), `drishti.regulatory.fiscal-year-end` (engine), `drishti.ccr.exposure.fiscal-year-end` (engine) | empty | engine | the bank's fiscal year-end, MM-DD (for example 10-31); blank: none (the fiscal-year-end pin is off) |
| `DRISHTI_FLIGHT_ADVERTISE` | `drishti.engines.flight.advertise` (engine) | empty | engine | the address clients are given in GetFlightInfo; empty: the node's real address (never localhost or 0.0.0.0) |
| `DRISHTI_FLIGHT_BIND` | `drishti.engines.flight.bind` (engine) | `0.0.0.0` | engine | the address the port opens on (0.0.0.0: every interface) |
| `DRISHTI_FLIGHT_ENABLED` | `drishti.engines.flight.enabled` (engine) | `false` | engine | answer Arrow Flight on this node; off as shipped: turning it on needs a restart (the port opens at start) |
| `DRISHTI_FLIGHT_MEMORY_MB` | `drishti.engines.flight.memory.total-mb` (engine) | `4096` | engine | every Flight read of this node together; the launchers add it to the direct-memory limit (tools/lake/memory\_caps.py) |
| `DRISHTI_FLIGHT_PORT` | `drishti.engines.flight.port` (engine) | `18815` | engine | the Flight port (gRPC over HTTP/2); open it in firewalls, TCP pass-through in load balancers |
| `DRISHTI_FLIGHT_TLS_CERT` | `drishti.engines.flight.tls.certificate` (engine) | `./config/tls/server.pem` | engine | the certificate chain the port presents |
| `DRISHTI_FLIGHT_TLS_CLIENT_CA` | `drishti.engines.flight.tls.client-ca` (engine) | empty | engine | a CA bundle: set it and every client must present a certificate it signed (mutual TLS) |
| `DRISHTI_FLIGHT_TLS_KEY` | `drishti.engines.flight.tls.private-key` (engine) | `./config/tls/server.key` | engine | its private key, PKCS#8 PEM |
| `DRISHTI_FORCE_PW_CHANGE_ON_CREATE` | `drishti.identity.force-password-change-on-create` | `false` | server | Nobody is forced to change a password unless configured here (an admin can still tick it per user). |
| `DRISHTI_FORCE_PW_CHANGE_ON_RESET` | `drishti.identity.force-password-change-on-reset` | `false` | server | after an administrator resets a password, the user must change it at the next sign-in |
| `DRISHTI_FRED_SERIES` | fred-feed series | `DGS10,DFF` | packs | The FRED series read |
| `DRISHTI_FRTB_ENABLED` | `drishti.engines.frtb-sa.enabled` (engine) | `true` | engine | run FRTB-SA on this node: /api/v1/frtb, FRSA, PLAY FRTB; needs var and sensitivities (live) |
| `DRISHTI_FRTB_PARAMETERS` | `drishti.frtb.parameters-dir` (engine) | `./config/packs/market-risk/capital` | engine | frtb-sa-sbm.yaml and frtb-sa-drc-rrao.yaml (else the market-risk pack's capital/) |
| `DRISHTI_GLEIF_USER_AGENT` | gleif fetch | none | tools | The User-Agent sent to GLEIF by the gleif refresh script |
| `DRISHTI_GOVERNANCE_DIR` | `drishti.governance.dir` | `./data/governance` | server | where proposals are kept with store: file; with content (the default) the folder is imported once and left in place |
| `DRISHTI_HEARTBEAT_SECONDS` | build\_demo\_lakes.py | `30` | tools | How often a long lake build prints that it is alive |
| `DRISHTI_HOME_FACTS` | `ui.facts.enabled` | `true` | console | Home's Did you know tile, the fact of the day for a signed-in user; false: no tile |
| `DRISHTI_HTTP2` | `server.http2.enabled` | `true` | server | HTTP/2 on the server's port: Drishti\*net peers multiplex their forwarded reads over one connection (h2c by the upgrade on plain HTTP, ALPN on TLS); browsers over TLS get it too. false: HTTP/1.1 only (peers then fall back to it on the same call). |
| `DRISHTI_HTTP_PROXY_PASSWORD` | `drishti.http.proxy.password` | empty | server | only a ${ENV} placeholder (a literal in this file is refused at start) |
| `DRISHTI_HTTP_PROXY_URL` | `drishti.http.proxy.url` | empty | server | http://host:port; http and https targets both go through it (https via CONNECT) |
| `DRISHTI_HTTP_PROXY_USERNAME` | `drishti.http.proxy.username` | empty | server | Basic proxy authentication |
| `DRISHTI_ICEBERG_CATALOG` | iceberg profile | `hadoop` | profile | hadoop (path-based tables) or rest |
| `DRISHTI_ICEBERG_CLASSPATH` | Iceberg loader | none | tools | The class path of the Iceberg loader |
| `DRISHTI_ICEBERG_CREDENTIAL` | iceberg profile | empty | profile | A REST catalog's credential (secret) |
| `DRISHTI_ICEBERG_ROOT` | iceberg profile | `./data/iceberg` | profile | The iceberg profile's root (path-based tables); also the lake tools' Iceberg root |
| `DRISHTI_ICEBERG_URI` | iceberg profile | empty | profile | A REST catalog's URI |
| `DRISHTI_ICEBERG_WAREHOUSE` | iceberg profile | empty | profile | A REST catalog's warehouse |
| `DRISHTI_IDENTITY_DB_PASSWORD` | `drishti.identity.database-password` | empty | server | its password, from the environment |
| `DRISHTI_IDENTITY_DB_URL` | `drishti.identity.database-url` | `jdbc:sqlite:./data/identity/drishti.db` | server | Users, roles defined in Admin → Roles, saved workspaces/monitors/settings and the audit trail live in one database (JPA). SQLite by default: a single file, nothing to install. For PostgreSQL: DRISHTI\_IDENTITY\_DB\_URL=jdbc:postgresql://db:5432/drishti DRISHTI\_IDENTITY\_DB\_USER=drishti DRISHTI\_IDENTITY\_DB\_PASSWORD=... The schema (drishti-identity: db/schema-sqlite.sql, db/schema-postgres.sql) is applied at start; no migrations. |
| `DRISHTI_IDENTITY_DB_USER` | `drishti.identity.database-user` | empty | server | the identity database's user (PostgreSQL) |
| `DRISHTI_INDEX_DIR` | `drishti.index.dir` | empty | server | Where the connectors' multi-day type-ahead index keeps closed days as block-compressed segment files (one subfolder per connector). Blank: &lt;data dir&gt;/index, i.e. ./data/index. Size is capped per connector by its index.max-disk; a day that rolls out of the window, is evicted by the cap or is rebuilt gives its disk back at once. |
| `DRISHTI_JAR` | `lakes.jar` | empty | console | the drishti-server exec jar that carries the writer (default: drishti-server/target) |
| `DRISHTI_JAR_DIR` | screenshot tools | `drishti-server/target` | tools | Where the screenshot tools find the server jar |
| `DRISHTI_JDBC_ENABLED` | `drishti.sources.plugins.jdbc.enabled` | `false` | server | run the jdbc source as itself (one instance) |
| `DRISHTI_JDBC_PASSWORD` | `drishti.sources.plugins.jdbc.settings.password` | empty | server | its database password (from the environment) |
| `DRISHTI_JDBC_URL` | `drishti.sources.plugins.jdbc.settings.url` | empty | server | the JDBC URL of that instance |
| `DRISHTI_JDBC_USER` | `drishti.sources.plugins.jdbc.settings.user` | empty | server | its database user |
| `DRISHTI_KAFKA_BOOTSTRAP` | Kafka brokers | `localhost:9092` | packs | The brokers of the trading stream and the VaR PV topic |
| `DRISHTI_LABS_CALC` | `drishti.calc.enabled`, `labs.calc` | `false` | console, server | Labs: off as shipped; on also needs a role with calc, and a pack that enables it for the view's kind |
| `DRISHTI_LAKES_DIR` | drishti.lakes.dir | `./config/lakes` | server | The lake folder: one config/lakes/&lt;lake&gt;.yaml per lake, read by the server, the loader, the data inbox and the tools (docs/guides/DATA\_LAKE\_GUIDE.md) |
| `DRISHTI_LAKES_RELOCATE_ON_START` | drishti.lakes.relocate-on-start | `true` | server | At start, move a demo lake built in the older layout (&lt;data&gt;/delta/&lt;domain&gt;) into &lt;data&gt;/delta/demo once, safely (verified, moved back on any error, never a delete); false: only by drishti.py lake relocate demo (docs/admin/OPERATIONS.md) |
| `DRISHTI_LAKE_CLASSPATH` | lake load | none | tools | The class path of the Java lake loader |
| `DRISHTI_LAKE_ENABLED` | the packs' data connectors | `true` | packs | false switches every pack's data connector off (the demo samples still answer) |
| `DRISHTI_LAKE_HEAP` | lake build | none | tools | The Java lake writer's heap |
| `DRISHTI_LAKE_LOADER_HEAP` | lake load | none | tools | The Java lake loader's heap |
| `DRISHTI_LAKE_MAINTENANCE_SCHEDULES` | `lakes.maintenance_schedules` | `true` | console | run the lake-maintenance schedules in this console (false: run lake maintain --daemon instead) |
| `DRISHTI_LAKE_MEMORY_` | one store's cap for the launchers | empty | tools | Prefix: DRISHTI\_LAKE\_MEMORY\_&lt;STORE&gt;\_MAX\_MB overrides one in-memory lake store's max-mb when the launchers compute the direct-memory limit (tools/lake/memory\_caps.py) |
| `DRISHTI_LAKE_MEMORY_MB` | the launchers' -XX:MaxDirectMemorySize | empty | tools | The in-memory lake stores' caps in MB, summed; when set, the launchers raise the JVM's direct-memory limit by it instead of reading the stores from the configuration (tools/lake/memory\_caps.py) |
| `DRISHTI_LAKE_ROOTS` | `lakes.roots` | empty | console | the lake roots this console may write, comma-separated (the Delta connectors' root); empty: &lt;data dir&gt;/delta, as the server |
| `DRISHTI_LAKE_ROW_GROUP_MB` | lake writer | none | tools | Row-group size of the Java lake writer, in MB |
| `DRISHTI_LAYOUTS_ENABLED` | `drishti.layouts.enabled`, `layouts.enabled` | `true` | console, server | also needs a role with layout (every role but viewer, unless configured) |
| `DRISHTI_LENSES` | `drishti.lenses` | `risk,world,market,trading` | server | Lenses (EDITIONS.md › The design as built, section 8): editions a person may open on a full instance ("Open Drishti Risk"), for their own session; no restart, every pack stays loaded. A lens hides, it does not secure. On by default (product owner, 2026-10-30): the four lenses; DRISHTI\_LENSES= (empty) turns them off. Ignored with an edition. |
| `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | `live.max_subscriptions` | `32` | console | Subscriptions one browser's channel carries at most (views across all its tabs and workspace panes, the alerts bell, monitors; a view open in several tabs counts once). Each holds one stream to the server from the backend pool (backend.pool\_size). Beyond it a view says it is not live, and why, instead of silently not ticking. |
| `DRISHTI_LOADER_HEAP` | store loaders | none | tools | The heap of the Java store loaders (Iceberg, ...) |
| `DRISHTI_LOADS_DIR` | `drishti.loads.dir` | `./data/loads` | server | history per pack, and the administrators' expectation overrides (config/&lt;pack&gt;.yaml) |
| `DRISHTI_LOG_FILE` | `drishti.logging.file` | empty | server | empty: standard output only (containers); drishti-stack sets run/logs/&lt;node&gt;.log |
| `DRISHTI_MAIL_FROM` | `drishti.collab.email.from` | `drishti@localhost` | server | the From address of collaboration email |
| `DRISHTI_MASK_COPIES` | `drishti.security.mask-copies` | `false` | server | Opt-in: also replace exact copies of a masked field's value found in the OTHER text of the same document (best effort: exact, case-sensitive; text of at least min-length characters, numbers of 4+ digits). Documents over max-nodes are not scanned. |
| `DRISHTI_MAX_SWALLOW` | `server.tomcat.max-swallow-size` | `64MB` | server | After a 413 for an oversized body, Tomcat drains up to this much of the rest so the client reads the answer, not a reset. |
| `DRISHTI_MCP_DIR` | `drishti.mcp.dir`, `mcp.dir` | `./config/mcp` | console, server | one YAML per MCP server: transport, url, headers as ${ENV}, allowed-tools |
| `DRISHTI_MCP_ENABLED` | `drishti.mcp.enabled`, `mcp.enabled` | `false` | console, server | turn on the MCP client: server files in drishti.mcp.dir are read and their tools become callable |
| `DRISHTI_MEMORY_LAKE_ENABLED` | `drishti.engines.memory-lake.enabled` (engine) | `false` | engine | run the in-memory lake on this node; off as shipped: turning it on needs a restart (the stores' caps set the direct-memory limit at start) |
| `DRISHTI_METRICS_ENABLED` | `drishti.metrics.enabled` | `true` | server | export the platform gauges (caches, memory budget, disk, sources, feeds, loads) on /actuator/prometheus and evaluate the alert rules |
| `DRISHTI_METRICS_TOKEN` | `drishti.security.metrics-token` | empty | server | With security on, /actuator (except /actuator/health) needs an admin token or this one, for Prometheus: scrape\_configs: \[{ job\_name: drishti, metrics\_path: /actuator/prometheus, bearer\_token: &lt;this&gt; }] |
| `DRISHTI_MINIO_BIN` | S3 simulator tests | none | tools | A MinIO server binary for the lake tools' S3 tests |
| `DRISHTI_MINIO_SHA256` | S3 simulator tests | none | tools | Its expected SHA-256 |
| `DRISHTI_MINIO_URL` | S3 simulator tests | none | tools | Where that binary is downloaded from |
| `DRISHTI_MONGODB_DATABASE` | mongodb profile | `drishti` | profile | Its database |
| `DRISHTI_MONGODB_URI` | mongodb profile | `mongodb://localhost:27017` | profile | The mongodb profile's server |
| `DRISHTI_MR_CUBE_ENABLED` | `drishti.engines.mr-cube.enabled` (engine) | `true` | engine | run the MR cube on this node: MCUBE, /api/v1/pivot/mr, the VaR source's hierarchy levels and MR measures; needs var and pivot |
| `DRISHTI_MR_DERIVED_ENABLED` | market-risk's derived connector | `true` | packs | The market-risk pack's derived kinds on or off |
| `DRISHTI_NATIVE_BLAS_MODE` | `drishti.native.blas.mode` | `auto` | server | auto: the bundled OpenBLAS (Linux and Windows x86-64), else the Java kernels with a warning, an alert and Health DEGRADED; java: the Java kernels, chosen (no warning) |
| `DRISHTI_NATIVE_CACHE_DIR` | `drishti.native.cache-dir` | `./data/native-cache` | server | where the bundled native libraries are unpacked (JavaCPP's cache), under the data folder |
| `DRISHTI_NET_DIR` | `drishti.net.dir` | `./config/net` | server | created on first write; ready-made files in config/net.examples/ |
| `DRISHTI_NET_SECRET` | `secret` (net) | none | net | the cluster key: an environment reference only, at least 32 bytes, the same on every node |
| `DRISHTI_NET_WATCH` | `drishti.net.watch` | `auto` | server | auto (file events, polling as a fallback) \| poll \| off |
| `DRISHTI_NEWS_ENABLED` | news connector enabled | `false` | packs | The news-and-markets pack's news connector on (names leave the network) |
| `DRISHTI_NEWS_URL` | news base URL | `https://news.google.com/rss/search` | packs | Where the news connector asks |
| `DRISHTI_NODE_URL` | deploy/bin/drishti-node.sh | `http://localhost:18480` | tools | The server the stop hook drains and checks |
| `DRISHTI_OFFLINE` | `drishti.offline.enabled` | `false` | server | Offline mode, for a machine with no internet (a demonstration laptop): connectors of the plugins below, whose data comes from public web services, never start, whatever their files say; Admin -&gt; Connectors shows them "off (offline mode)", and their kinds are served by the other connectors that hold them (news-and-markets: news-store, built from the captured snapshot by `drishti.py lake demo`). |
| `DRISHTI_OIDC_CLIENT_ID` | `drishti.security.oidc.client-id`, `auth.oidc.client_id` | empty | console, server | the OIDC client id (the token's audience) |
| `DRISHTI_OIDC_CLIENT_SECRET` | `auth.oidc.client_secret` | empty | console | from the environment only; empty for a public client (PKCE alone) |
| `DRISHTI_OIDC_ENABLED` | `drishti.security.oidc.enabled`, `auth.oidc.enabled` | `false` | console, server | verify single-sign-on ID tokens |
| `DRISHTI_OIDC_ISSUER` | `drishti.security.oidc.issuer`, `auth.oidc.issuer` | empty | console, server | the OIDC issuer URL |
| `DRISHTI_OIDC_REDIRECT_URI` | `auth.oidc.redirect_uri` | empty | console | default: &lt;console&gt;/auth/oidc/callback |
| `DRISHTI_OPENAPI_URL` | `help.openapi_url` | empty | console | the address of the server's OpenAPI UI as a browser reaches it (blank: the page names /api/docs/ui on the server) |
| `DRISHTI_OPENBLAS_ENABLED` | `drishti.engines.openblas.enabled` (engine) | `false` | engine | off as shipped (the Java kernels, the same figures); on: linear algebra on the bundled OpenBLAS on this node (live, no restart) for faster many-scope sums |
| `DRISHTI_PACKS` | `drishti.packs.enabled`, `packs.enabled` | `default` | console, server | default: the edition's packs (drishti.edition), else config/editions/default-packs.yaml |
| `DRISHTI_PACKS_DEPLOY_HISTORY` | `drishti.packs.deploy.history-file` | `./data/packs/deploy-history.jsonl` | server | the history of archive deployments and rollbacks |
| `DRISHTI_PACKS_DIR` | `drishti.packs.dir`, `packs.dir` | `./config/packs` | console, server | Domain packs (config/packs/&lt;name&gt;/pack.yaml): Sutras, mnemonics, links, badges, roles, vocabulary, samples. Site settings below always override what a pack contributes. |
| `DRISHTI_PACKS_EXTRA_DIRS` | `drishti.packs.extra-dirs`, `packs.extra_dirs` | empty | console, server | Further pack folders, comma-separated, looked in after dir: examples/packs holds the retired example packs (finance, genomics, logistics, retail-banking, climate-risk, operational-risk, liquidity-risk); name one in enabled to load it. |
| `DRISHTI_PACKS_INSTALLED` | `drishti.packs.installed-dir`, `packs.installed_dir` | `./data/packs/installed` | console, server | Packs installed from a signed registry (Admin → Packs → From the registry); looked in before dir, so they win. |
| `DRISHTI_PACKS_OUT` | pack generators | none | tools | Where the pack generators write |
| `DRISHTI_PACKS_OVERLAY` | `drishti.packs.overlay` | `./data/packs/added.yaml` | server | Packs loaded from Admin → Packs are listed here (drishti.packs.added) and read at every start; see spring.config.import. |
| `DRISHTI_PACKS_SAMPLES` | `drishti.packs.samples` | `visible` | server | Sample packs (sample: true in pack.yaml) are demonstrations. visible: everyone sees them; developers: only users with the author or admin power; hidden: nobody, they are not loaded and their connectors do not start. Admin -&gt; Packs ("Hide samples from business users") saves a choice that overrides this. Use developers once real packs exist. |
| `DRISHTI_PACKS_SAMPLES_FILE` | `drishti.packs.samples-file` | `./data/packs/samples-mode` | server | where the Admin -&gt; Packs choice for sample packs is saved (one word); delete it to let drishti.packs.samples decide again |
| `DRISHTI_PACKS_SETTINGS` | `drishti.packs.settings-dir` | `./data/packs/settings` | server | The administrator's data-source overrides, one file per pack (Admin → Packs → Data source); redeploying a pack keeps them. |
| `DRISHTI_PACKS_WATCH` | `drishti.packs.watch` | `auto` | server | The packs folder is watched: a new pack folder is loaded, an edited one re-checked and swapped in, a deleted one unloaded, with no restart. auto: file events with polling as the safety net; poll: look every `poll`; off: only Admin -&gt; Packs acts. |
| `DRISHTI_PACK_REGISTRY` | `drishti.packs.registry.url` | empty | server | a folder, file: or https: URL with index.json; empty: no registry |
| `DRISHTI_PG_PASSWORD` | postgres profile | `drishti` | profile | Its password (secret) |
| `DRISHTI_PG_URL` | postgres profile | `jdbc:postgresql://localhost:5432/drishti` | profile | The postgres profile's database (tables &lt;domain&gt;.entities) |
| `DRISHTI_PG_USER` | postgres profile | `drishti` | profile | Its user |
| `DRISHTI_PIVOT_ENABLED` | `drishti.pivot.enabled` | `true` | server | the old name of the pivot engine's switch (config/engines/pivot.yaml); false: no Pivot anywhere |
| `DRISHTI_PIVOT_ENGINE_ENABLED` | `drishti.engines.pivot.enabled` (engine) | `true` | engine | run the pivot engine on this node: /api/v1/pivot, PIVOT, Views -&gt; Pivot, drishti.py pivot (live); its old name drishti.pivot.enabled still works |
| `DRISHTI_PLUGIN_DIR` | `drishti.sources.plugin-dir` | empty | server | Optional directory of extra plugin jars; each gets its own class loader. |
| `DRISHTI_PORT` | `server.port` | `18480` | server | the server's HTTP port |
| `DRISHTI_PRODUCT` | `drishti.branding.product`, `ui.product` | `Drishti` | console, server | The product's name and legal notices, wherever people see them (pages, the About answer, messages). Change them here, not in code; the console has the same keys under ui: in drishti-console/config/application.yaml. |
| `DRISHTI_PUBLIC_URL` | `drishti.embed.issuer` | empty | server | the iss of embed tokens: this server's public URL |
| `DRISHTI_PYTHON` | `compute.python` (compute) | empty | compute | the interpreter workers and environments use; empty: python3 (python on Windows) |
| `DRISHTI_RABBITMQ_ENABLED` | `drishti.sources.plugins.rabbitmq.enabled` | `false` | server | run the rabbitmq source as itself |
| `DRISHTI_REDIS_CLUSTER` | redis profile | `false` | profile | Redis Cluster rather than one server |
| `DRISHTI_REDIS_PASSWORD` | the Redis loader | none | tools | The Redis loader's password (secret) |
| `DRISHTI_REDIS_URI` | redis profile | `redis://localhost:6379` | profile | The redis profile's server |
| `DRISHTI_REDIS_USER` | the Redis loader | none | tools | The Redis loader's user |
| `DRISHTI_REPORTS_DIR` | `drishti.reports.folder`, `drishti.regulatory.reports-dir` (engine) | `./data/reports` | engine, server | where report files are written |
| `DRISHTI_REPORTS_ENABLED` | `drishti.reports.enabled` | `true` | server | the scheduler; run it on one server of a group (reports still run by hand) |
| `DRISHTI_REST_ENABLED` | `drishti.sources.plugins.rest.enabled` | `false` | server | run the rest source as itself (one instance) |
| `DRISHTI_REST_URL` | `drishti.sources.plugins.rest.settings.base-url` | `http://localhost:9000/api` | server | the REST service's base URL |
| `DRISHTI_RETRY_ENABLED` | `retry.enabled` | `true` | console | retry what failed (the console's AI providers and MCP servers); false turns the retries off |
| `DRISHTI_RETRY_INITIAL` | `retry.initial` | `5s` | console | wait before the first retry |
| `DRISHTI_RETRY_MAX` | `retry.max` | `5m` | console | no wait is longer than this |
| `DRISHTI_S3_ENABLED` | `drishti.sources.plugins.s3.enabled` | `false` | server | run the s3 source as itself |
| `DRISHTI_SECURE_COOKIE` | `auth.secure_cookie` | `true` | console | mark cookies Secure (false only for plain-HTTP development with sign-in on) |
| `DRISHTI_SECURITY_ENABLED` | `drishti.security.enabled` | `false` | server | Off for local development. Production: enabled: true and DRISHTI\_TOKEN\_SECRET (&gt;= 32 bytes, shared with the console). |
| `DRISHTI_SEC_USER_AGENT` | SEC fetch | none | tools | The User-Agent (with a contact) the SEC requires of the us-equities refresh script |
| `DRISHTI_SEED_ADMIN` | `drishti.identity.seed-admin` | `true` | server | On first start with no users, create a development admin. Change its password at once; the console warns until you do. Turn off (DRISHTI\_SEED\_ADMIN=false) for production. |
| `DRISHTI_SEED_PASSWORD` | `drishti.identity.seed-password` | `drishti-dev-admin123` | server | the first administrator's password; only this built-in default shows the default password warning |
| `DRISHTI_SEED_USERNAME` | `drishti.identity.seed-username` | `drishti-dev-admin` | server | With DRISHTI\_SEED\_USERNAME / DRISHTI\_SEED\_PASSWORD set (deploy/docker/compose.yaml requires both) it is the first administrator you chose, not the development admin. |
| `DRISHTI_SENSITIVITIES_ENABLED` | `drishti.engines.sensitivities.enabled` (engine) | `true` | engine | run position sensitivities on this node: /api/v1/sensitivities, /api/v1/risk-factors; FRTB-SA and SIMM need it (live) |
| `DRISHTI_SERVER` | drishti.py | none | tools | The server the command line talks to |
| `DRISHTI_SESSION_RECHECK_SECONDS` | `auth.recheck_seconds` | `10` | console | How long the console reuses the server's answer to "does this session still stand, and who is the user now?" (enabled, roles, password change due). Disabling, demoting or signing out takes effect within this time (at once on the console an administrator used). 0 asks the server on every request. |
| `DRISHTI_SESSION_SECRET` | `auth.session_secret` | empty | console | &gt;= 32 characters; from the environment only |
| `DRISHTI_SHOTS_CONSOLE_PORT` | screenshot tools | none | tools | The port of the console they start |
| `DRISHTI_SHOTS_DELTA_ROOT` | screenshot tools | none | tools | The lake they read |
| `DRISHTI_SHOTS_HOST_PORT` | screenshot tools | none | tools | The port of the embedding host page they start |
| `DRISHTI_SHOTS_INDEX` | screenshot tools | none | tools | Which shots of a tutorial to take |
| `DRISHTI_SHOTS_LAKE_END` | screenshot tools | none | tools | The last business date of the lake they build |
| `DRISHTI_SHOTS_SERVER_JAR` | screenshot tools | none | tools | The server jar they run |
| `DRISHTI_SHOTS_SERVER_PORT` | screenshot tools | none | tools | The port of the server the screenshot tools start |
| `DRISHTI_SHOTS_WORK` | screenshot tools | none | tools | The screenshot tools' working folder |
| `DRISHTI_SIMM_ENABLED` | `drishti.engines.simm.enabled` (engine) | `true` | engine | run SIMM on this node: /api/v1/simm, SIMM, PLAY SIMM; needs sensitivities (live) |
| `DRISHTI_SIMM_PARAMETERS` | `drishti.simm.parameters-dir` (engine) | `./config/simm` | engine | the bank's licensed parameter files, one YAML a SIMM version (none ships: ISDA's numbers are licensed) |
| `DRISHTI_STORE_ACCESS_KEY` | `s3.access-key` (store) | empty | store | empty: the AWS default credentials chain |
| `DRISHTI_STORE_FILE` | `drishti.store.file` | `./config/store/store.yaml` | server | The content store (docs/architecture/DATA\_ARCHITECTURE.md): user content every node of a cluster can reach. Its settings are in their own file. |
| `DRISHTI_STORE_PASSWORD` | `postgres.password` (store) | empty | store | its password, from the environment only |
| `DRISHTI_STORE_ROOT` | `drishti.store.root` | empty | server | the filesystem backend's root, over the file's filesystem.root (empty: the file's, else &lt;data folder&gt;/fstore) |
| `DRISHTI_STORE_SECRET_KEY` | `s3.secret-key` (store) | empty | store | its secret key, from the environment only |
| `DRISHTI_STREAM_CACHE_GB` | trading-stream cache size | `10` | packs | The trading stream's disk cache size, in GB |
| `DRISHTI_STREAM_DISK_CACHE` | trading-stream disk cache | `true` | packs | The trading stream's disk cache on or off |
| `DRISHTI_STREAM_STALE_AFTER` | trading-stream stale-after | `15m` | packs | The trading stream is stale after this long without a message |
| `DRISHTI_STREAM_TRADING` | trading-stream enabled | `false` | packs | Turn on the trading pack's trading-stream Kafka connector |
| `DRISHTI_STUDIO_SAVE` | `drishti.rachana.studio-save` | `false` | server | Let Sutra Studio save files (authors only). Off by default; turn on in authoring environments. |
| `DRISHTI_SUGGEST_LIMIT` | `drishti.commands.suggest-limit` | `25` | server | Mnemonics come from the enabled packs; add or override site mnemonics under `mnemonics:`. entries in the command line's dropdown (the list scrolls) |
| `DRISHTI_SUTRAS` | `drishti.rachana.dirs` | `./sutras` | server | Site Sutra directories, scanned recursively, in addition to the enabled packs' Sutras. |
| `DRISHTI_SUTRA_FOUR_EYES` | `drishti.governance.four-eyes` | `true` | server | nobody approves their own proposal (when security is on) |
| `DRISHTI_SUTRA_REVIEW` | `drishti.governance.enabled` | `true` | server | With review on, a Studio save is a proposal; an approver (role flag approve, or an admin) makes it live. |
| `DRISHTI_SUTRA_WATCH` | `drishti.rachana.watch` | `auto` | server | auto: file events, else polling (inotify limit reached); poll: always poll (network file systems) |
| `DRISHTI_SYNTHETIC_HISTORY` | `drishti.regulatory.synthetic-history` (engine) | `false` | engine | a demo server may seed synthetic official records before its oldest one; never on a bank's server |
| `DRISHTI_T35_PASSWORD` | screenshot tools | none | tools | Tutorial 35's shots: that reader's password |
| `DRISHTI_T35_TOKEN_FILE` | screenshot tools | none | tools | Tutorial 35's shots: a scratch file the new token is kept in for the later steps |
| `DRISHTI_T35_USER` | screenshot tools | none | tools | Tutorial 35's shots: the reader who signs in (default the administrator) |
| `DRISHTI_T36_WORK` | screenshot tools | none | tools | Tutorial 36's shots: the working folder of the data inbox run |
| `DRISHTI_T37_FN` | screenshot tools | `fn:you/wret@1` | tools | Tutorial 37's shots: the saved function shown |
| `DRISHTI_T37_PASSWORD` | screenshot tools | none | tools | Tutorial 37's shots: that author's password |
| `DRISHTI_T37_USER` | screenshot tools | none | tools | Tutorial 37's shots: the function's author who signs in |
| `DRISHTI_TABLES_ROOT` | `drishti.tables.root` | `./config/tables` | server | the definitions' folder: config/tables/&lt;domain&gt;/&lt;name&gt;.table.yaml and .model.yaml |
| `DRISHTI_TMP_DIR` | java.io.tmpdir, the console's tmp\_dir | `<data dir>/tmp` | server, console, tools | The base of every temporary folder Drishti makes |
| `DRISHTI_TOKEN` | drishti.py | none | tools | A personal API token for the command line and tools (secret) |
| `DRISHTI_TOKEN_SECRET` | `drishti.security.secret`, `auth.token_secret` | empty | console, server | the token signing secret (at least 32 bytes), shared with the console; from the environment only |
| `DRISHTI_TOOLS_CACHE` | tools | none | tools | Where tools keep downloads (the MinIO binary, ...) |
| `DRISHTI_TRADING_TOPIC` | trading-stream topic | `drishti.trading.trades` | packs | The trading stream's topic |
| `DRISHTI_UI_DESIGNER` | `ui.designer` | `false` | console | The Sutra designer (docs/guides/RACHANA\_REFERENCE.md › Screen designer and the Build workbench, section 31): Customize on every entity page and Describe under Build -&gt; New screen. A user's changes are their own Design, never the pack's Sutra. Off: pages and the screen designer are exactly as before. |
| `DRISHTI_UI_KIT_DIR` | `drishti.ui-kit.dir` | `./config/ui-kit` | server | The Drishti UI kit (docs/guides/RACHANA\_REFERENCE.md, "The Drishti UI kit"): platform UI vocabulary loaded whatever packs are enabled: fragments/ (used by any Sutra as { use: series-line@1 }), assets/ (icons, images, logos) and brand.yaml. |
| `DRISHTI_UI_KIT_SITE_DIR` | `drishti.ui-kit.site-dir` | `./data/ui-kit` | server | The site's overrides: brand.yaml (its keys win over the kit's) and assets/ (a file wins over the kit's of the same path). |
| `DRISHTI_UI_PHONE` | `ui.phone.enabled` | `true` | console | true: a phone or tablet gets the tiered page; false: every screen gets every panel, as before |
| `DRISHTI_UNDER_CONSTRUCTION_FILE` | `drishti.under-construction.file`, `under_construction.file` | `./config/under-construction.yaml` | console, server | absent: under-construction.yaml beside the packs folder, else nothing registered; none: nothing |
| `DRISHTI_UNINSTALLED_DIR` | `drishti.admin.uninstalled-dir` | `./data/uninstalled` | server | an uninstall MOVES the artifact here; nothing is deleted |
| `DRISHTI_UNINSTALLED_KEEP` | `drishti.admin.uninstalled-keep` | `30d` | server | archives older than this are removed, once a day; 0 keeps them for ever |
| `DRISHTI_USER` | `ui.user` | `ash` | console | acting user until sign-in lands |
| `DRISHTI_USERS_FILE` | `drishti.identity.users-file` | `./data/identity/users.json` | server | The files of releases before 1.10, imported once into an empty database and renamed \*.imported. |
| `DRISHTI_US_EQUITIES_BENCHMARK` | us-equities benchmark | `SPY` | packs | The index an equity's beta is measured against |
| `DRISHTI_US_EQUITIES_PRICES_ENABLED` | us-equities-prices enabled | `false` | packs | The us-equities pack's live prices (internal use only) |
| `DRISHTI_VAR_CONFIDENCE` | `drishti.var.confidence` (engine) | `0.99` | engine | VaR's confidence: 0.99 or 0.95 (refused at start outside (0.5, 1)) |
| `DRISHTI_VAR_CURRENCIES` | `drishti.var.currencies` (engine) | `USD,EUR,CAD,JPY,GBP` | engine | reporting currencies built ahead after the USD book (background, own hot files; USD always); others on first ask |
| `DRISHTI_VAR_CURRENCY_RETENTION_DATES` | `drishti.var.currency-retention-dates` (engine) | `5` | engine | newest business dates whose listed currencies are built ahead and kept on disk |
| `DRISHTI_VAR_ENABLED` | `drishti.engines.var.enabled` (engine) | `true` | engine | run the VaR engine on this node: /api/v1/var, VAR, SMAT, PLAY VAR, regulatory VaR (live) |
| `DRISHTI_VAR_ES_CONFIDENCE` | `drishti.var.es-confidence` (engine) | `0.975` | engine | expected shortfall's |
| `DRISHTI_VAR_EXPLAIN_ENABLED` | `drishti.var.explain.enabled` (engine) | `true` | engine | the tab, the command, the API and the workbook |
| `DRISHTI_VAR_HOT_DATES` | `drishti.var.hot-dates` (engine) | `10` | engine | business dates kept open: the least recently used leaves first; the date just used, the newest business date and the dates pinned in memory always stay |
| `DRISHTI_VAR_HOT_DIR` | `drishti.var.tier.dir` (engine) | `./data/var/hot` | engine | one folder per business date; never under the repository |
| `DRISHTI_VAR_INTRADAY_KAFKA_MAX_TRADES` | `drishti.var.intraday.kafka.max-trades` (engine) | `500` | engine | a window applied earlier once this many trades wait |
| `DRISHTI_VAR_INTRADAY_KAFKA_WINDOW` | `drishti.var.intraday.kafka.window` (engine) | `5m` | engine | everything received in a window: one intraday batch in the lake, applied |
| `DRISHTI_VAR_KEEP_HOT_FILES` | `drishti.var.tier.keep-on-shutdown` (engine) | `false` | engine | false: deleted at shutdown (and any left over at start); the server images set true (PO-10) |
| `DRISHTI_VAR_MAX_DISK_GB` | `drishti.var.tier.max-disk-gb` (engine) | `0` | engine | most disk all hot files take (dates, currencies, shard books); 0: half the disk; least recently used beyond go |
| `DRISHTI_VAR_MEMORY_MB` | `drishti.var.memory-budget-mb` (engine) | `0` | engine | heap tier only: most memory one date's vectors take (0: a quarter of the heap); over it: 503 DRS-5204. The mapped tier is bounded by the disk (hard) and the page cache (advisory) |
| `DRISHTI_VAR_OFFICIAL` | `drishti.var.official.enabled` (engine) | `true` | engine | capture every node of every hierarchy when a date's inputs are complete (after the data-load signal) |
| `DRISHTI_VAR_OFFICIAL_FORMAT` | `drishti.var.official.format` (engine) | `delta` | engine | delta or iceberg; BI's var-results connector follows it (generated as plugin iceberg) |
| `DRISHTI_VAR_OPEN_HEAP_MB` | `drishti.var.open-heap-mb` (engine) | `0` | engine | the byte safety net over every open book's heap (0: automatic, 0.4 x heap - 512 MB - load-threads x 1.6 GB, at least a tenth of the heap); over it the least recently used dates' currency, then stress books, then whole dates leave |
| `DRISHTI_VAR_PARAMETRIC_ENABLED` | `drishti.var.parametric.enabled` (engine) | `true` | engine | the parametric block on the VaR answers, the PVAR alias, the playground's Parametric tab and pvar(); off: none of them (asked: refused in words) |
| `DRISHTI_VAR_PLAYGROUND_ENABLED` | `drishti.var.playground.enabled` (engine) | `true` | engine | the screen, the command and the export |
| `DRISHTI_VAR_PLAYGROUND_EXPORT_MAX_CELLS` | `drishti.var.playground.export-max-cells` (engine) | `20000000` | engine | the most cells one export may hold (about 19,000 positions at 509 scenarios, some 160 MB of server memory while it is written); larger is refused in words |
| `DRISHTI_VAR_PV_STREAM` | pv-updates enabled | `false` | packs | Turn on the market-risk pack's pv-updates connector: the pricing systems' PV topic, windowed into intraday batches |
| `DRISHTI_VAR_PV_TOPIC` | pv-updates topic | `drishti.risk.pv-updates` | packs | Its topic: JSON Lines of scenario-pv or pnl-vector records per message |
| `DRISHTI_VAR_READ_THREADS` | `drishti.var.read.threads` (engine) | `0` | engine | files (or runs of row groups) decoded at once for one load, delivered in file order (0: half the cores, at most 8; 1: on the load's own thread) |
| `DRISHTI_VAR_ROOT_NAME` | `drishti.var.root-name` (engine) | empty | engine | the top of the VaR tree (every hierarchy's root node); blank keeps the hierarchy data's NAME |
| `DRISHTI_VAR_SCENARIO_WINDOW` | `drishti.var.scenario-window` (engine) | `250` | engine | the newest N scenarios of each date's set (250: one year, the Basel minimum; at least 20), or all (the whole set, 509 in the demo) |
| `DRISHTI_VAR_SMAT_MAX_DAYS` | `drishti.var.smat.max-days` (engine) | `60` | engine | the most business dates a scenario matrix (SMAT &lt;id&gt; N, GET /api/v1/var/matrix) reads |
| `DRISHTI_VAR_STRESS_ENABLED` | `drishti.var.stress.enabled` (engine) | `true` | engine | offer Normal / Stressed (off: a stressed request is refused; the stress set's rows are still kept apart) |
| `DRISHTI_VAR_STRESS_WINDOW_ENABLED` | `drishti.var.stress-window.enabled` (engine) | `true` | engine | the tool and its API (off: refused in words) |
| `DRISHTI_VAR_TEN_DAY_METHOD` | `drishti.var.ten-day-method` (engine) | `sqrt10` | engine | 10-day VaR when the method is not named: sqrt10 (1-day x sqrt 10) or sum (10 consecutive days) |
| `DRISHTI_VAR_THREADS` | `drishti.var.threads` (engine) | `0` | engine | cores the vector sums use (0: every core) |
| `DRISHTI_VAR_TIER` | `drishti.var.tier.mode` (engine) | `mapped` | engine | mapped: each date a float32 hot file, memory-mapped (heap: aggregates, columns, links); heap: float64 rows on the heap |
| `DRISHTI_VAR_VERSION_CHECK` | `drishti.var.version-check` (engine) | `30s` | engine | an open date compares its lake version this often on a request; moved: reloaded in the background (0: off) |
| `DRISHTI_VIEWS_REFRESH_MIN_SECONDS` | `views.refresh_min_seconds` | `5` | console | The fastest any panel refreshes (refresh: in a Sutra, RACHANA\_REFERENCE.md 9), in seconds: a Sutra or a reader asking for faster gets this. |
| `DRISHTI_WAIT_BUSY` | drishti.py | `false` | tools | 1: every command waits when the server is busy (503 DRS-9501 to DRS-9505, busy protection) for its Retry-After and tries again, three times at most (data get --wait does the same for one download) |
| `DRISHTI_YFINANCE_ENABLED` | yfinance connector enabled | `false` | packs | The news-and-markets pack's yfinance connector on |
| `DRISHTI_YFINANCE_URL` | yfinance base URL | `https://query1.finance.yahoo.com/v8/finance/chart` | packs | Where the yfinance connector asks |
| `FRED_API_KEY` | fred-feed api key | empty | packs | The FRED API key (secret) |
| `SAJHA_API_KEY` | `headers.<header>` (AI) | none | AI | a SAJHA API key (Admin &gt; API keys); or Authorization: Bearer ${SAJHA\_TOKEN} |
| `SPRING_PROFILES_ACTIVE` | spring.profiles.active | none | server | A profile: postgres, aerospike, duckdb, files, iceberg, mongodb or redis (deprecated: connector files replace them) |

<!-- generated: settings end -->
