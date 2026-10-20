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

This is the complete reference for configuring Drishti: every `drishti.*` setting of the server, every
setting of the web console, the environment variables that feed them, and how the layers combine. Each
section gives the default, what the setting does, and an example you can copy.

If you only want to get something running, read [How configuration works](#how-configuration-works) and
[Common recipes](#common-recipes) first, then look keys up in the reference as you need them.

Contents

1. [How configuration works](#how-configuration-works)
2. [Placeholders: `${NAME:default}`](#placeholders-namedefault) and [every placeholder of `application.yaml`](#every-placeholder-in-the-servers-applicationyaml)
3. [Value formats](#value-formats)
4. [Common recipes](#common-recipes)
5. [Server reference](#server-reference) (`drishti.*`)
6. [Connector settings, plugin by plugin](#connector-settings-plugin-by-plugin)
7. [Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles)
8. [Spring settings you may need](#spring-settings-you-may-need)
9. [Console reference](#console-reference)
10. [Environment variable index](#environment-variable-index)

---

## How configuration works

Drishti has two programs and each has its own configuration:

| Program | Bundled file | Local override (git-ignored) | Env-var form | Argument form |
|---|---|---|---|---|
| Server (Java, port 18480) | `drishti-server/src/main/resources/application.yaml` (inside the jar) | `./application.local.yaml` in the server's **working directory** | `DRISHTI_SOURCES_FETCHTIMEOUT=3s` | `--drishti.sources.fetch-timeout=3s` |
| Console (Python, port 17480) | `drishti-console/config/application.yaml` | `drishti-console/config/application.local.yaml` | `DRISHTI_CONSOLE__BACKEND__TIMEOUT_SECONDS=10` | `--backend.timeout_seconds=10` |

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

The longest a source read may take is `drishti.sources.fetch-timeout`, `5s` in the bundled file (2s before 2026-10-18: the first read of a date on a large table loads its id index first).

1. Create `application.local.yaml` in the directory you start the server from (the repository root when
   you run `java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar`):

   ```yaml
   drishti:
     sources:
       fetch-timeout: 3s
   ```

   Now the effective value is `3s`.

2. Start the server with an environment variable as well:

   ```bash
   DRISHTI_SOURCES_FETCHTIMEOUT=4s java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
   ```

   The effective value is `4s`: the environment beats the local file.

3. Add an argument:

   ```bash
   DRISHTI_SOURCES_FETCHTIMEOUT=4s java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar \
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

There is no endpoint that dumps the configuration, but most settings show up somewhere you can read:

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

#### Pass `-Djava.io.tmpdir=` to Java

Setting `java.io.tmpdir` inside a running JVM does not move `Files.createTempFile` or `File.createTempFile`: since JDK 17 they use the value the JVM **started** with. DuckDB (60 MB a start), RocksDB and CatBoost unpack their native libraries with those calls, so without the flag they land in the system `/tmp`, and every server killed without a clean stop (`kill -9`, a test teardown, a container stopped hard) leaves its copy there. `/tmp` is often a small in-memory file system with a per-user quota; once it fills, other programs (a browser, the console's tests) fail. So start the server with the flag, pointing at the server's temp base:

```bash
mkdir -p data/tmp/server        # once: the server makes it too, but the JVM warns on a first start when it does not exist yet
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
```

or, where editing the command is awkward (a service manager, a container, a wrapper script), through the environment: `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/opt/drishti/data/tmp/server` (Java prints `Picked up JAVA_TOOL_OPTIONS` once). With another data folder or `DRISHTI_TMP_DIR`, use that base: `<data dir>/tmp/server`. Everything Drishti ships passes it already: `tools/windows/start-server.ps1`, the IntelliJ run configurations, `deploy/docker/server.Dockerfile` (in `JAVA_TOOL_OPTIONS`), the `run-server.sh`/`.ps1` that `tools/packmake.py` writes, the loaders `tools/load-*.sh`, and every java the tests and tools start (`tools/javacmd.py`; a guard test fails on one that does not). `./mvnw` gives Maven's own JVM `<repository>/data/tmp/maven` (`DRISHTI_MAVEN_TMP` names another), and the test JVMs get `<module>/target/tmp`, which `mvn clean` removes.

At start the server

- **warns** when the flag was not passed (on the command line, `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS`): the log line `java.io.tmpdir was not set when Java started, so it is the system temp folder (/tmp): …` names the exact flag for this server, and Admin, Health shows it under *Temp folders* until the server is started with it;
- **removes stale native-library copies** (`libduckdb_java*`, `librocksdbjni*`, `libcatboost*`, jansi, `sqlite-*`, `snappy-*`, lz4, zstd, netty; files only, no links, older than 10 minutes) from the top of its own temp base, never from `/tmp` or anywhere else. A copy another live server still maps is harmless to remove on Linux and macOS; on Windows the delete fails and the file is skipped quietly. Admin, Health shows how many were removed.

Copies already in `/tmp` from earlier runs are not touched: remove them by hand when no Drishti JVM is running (`ls /tmp/libduckdb_java* /tmp/librocksdbjni*`).

Admin, Health shows both folders (path, size and file count). All main code makes temporary files through one helper (`TempFiles` in the server, `core/tmpdir.py` in the console); an architecture test and a console test fail the build if anything else calls `Files.createTempFile`, `Files.createTempDirectory`, `File.createTempFile` or `tempfile.*`.

### External libraries: `external_lib` (`DRISHTI_EXTERNAL_LIB`)

One folder for your own libraries, loaded at start with no rebuild ([external_lib/README.md](../../external_lib/README.md)):

| Folder | What goes in it | Loaded by |
|---|---|---|
| `<base>/java` | jars: JDBC drivers, connector plugins, libraries a plugin needs | the **server**: every jar joins its class path at start (the jar is launched by Spring Boot's `PropertiesLauncher`, whose `loader.properties` names this folder; `-Dloader.path=` overrides it) |
| `<base>/python` | modules, packages, pure-Python `.whl` / `.zip` / `.egg` files | the **console**: on `sys.path` at start, after the console's own folder; sandboxed Python items see the paths but import a package only when it is on `bi.python.packages` |

| Process | Setting | Variable | Default |
|---|---|---|---|
| Server | system property `drishti.external-lib` | `DRISHTI_EXTERNAL_LIB` | `external_lib` in the working directory |
| Console | `external_lib` | `DRISHTI_EXTERNAL_LIB` | `external_lib` in the repository root |

Restart a process after adding or removing a library. Admin, Health lists the jars and the Python items each process
found. Only administrators should be able to write to the folder: what is in it runs inside the server and the console.
The repository keeps the folder empty (`.gitignore`).

### Every placeholder in the server's `application.yaml`

Generated from `drishti-server/src/main/resources/application.yaml` (and checked against it by
`drishti-console/tests/test_docs_placeholders.py`, so the table cannot drift). "Sets" is the key the placeholder fills. Profile
files (`application-files.yaml`, `application-postgres.yaml`, …) and packs have placeholders of their own, listed in
[Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles).

| Variable | Sets | Default | What it does |
|---|---|---|---|
| `DRISHTI_PACKS_OVERLAY` | `spring.config.import` | `./data/packs/added.yaml` | The file of packs an administrator loaded from Admin → Packs; imported at start (`spring.config.import`) and named again by `drishti.packs.overlay`. Written by the server, not by hand. |
| `DRISHTI_PORT` | `server.port` | `18480` | The server's HTTP port. |
| `DRISHTI_PRODUCT` | `drishti.branding.product` | `Drishti` | The product name shown in pages and messages. |
| `DRISHTI_PLUGIN_DIR` | `drishti.sources.plugin-dir` | empty | A directory of extra plugin jars; empty: none. |
| `DRISHTI_HTTP_PROXY_URL` | `drishti.http.proxy.url` | empty | The site's outbound HTTP proxy (`http://host:port`); empty: none. See [`drishti.http.proxy`](#drishtihttpproxy--the-sites-outbound-http-proxy). |
| `DRISHTI_HTTP_PROXY_USERNAME` | `drishti.http.proxy.username` | empty | The proxy's Basic-authentication user. |
| `DRISHTI_HTTP_PROXY_PASSWORD` | `drishti.http.proxy.password` | empty | The proxy's password: an environment variable, never a literal in a file. |
| `DRISHTI_DEMO_ENABLED` | `drishti.sources.plugins.demo.enabled` | `true` | Run the demo source (the packs' sample data). |
| `DRISHTI_OFFLINE` | `drishti.offline.enabled` | `false` | Offline mode, for a machine with no internet: connectors of the internet plugins (`drishti.offline.plugins`: `news`, `yfinance`, `feed`) never start, whatever their files say; Admin → Connectors shows them *off (offline mode)*. See [`drishti.offline`](#drishtioffline--offline-mode-no-internet). |
| `DRISHTI_NET_DIR` | `drishti.net.dir` | `./config/net` | Drishti*net's folder (`node.yaml`, `peers/*.yaml`, `gossip.yaml`, `routing.yaml`, `access.yaml`); without it, or with `enabled: false` in its `node.yaml`, the server is a single server. See [`drishti.net`](#drishtinet--drishtinet-the-cluster-folder) and [NET.md](NET.md). |
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
| `DRISHTI_PUBLIC_URL` | `drishti.embed.issuer` | empty | This server's public URL: the `iss` of embed tokens. |
| `DRISHTI_CONSOLE_URL` | `drishti.embed.audiences` | empty | The console's public URL: the audience embed tokens are made for. |
| `DRISHTI_EMBED_KEY_FILE` | `drishti.embed.signing-key` | empty | PEM file with the ES256 key pair that signs embed tokens (empty: made at start). |
| `DRISHTI_OIDC_ISSUER` | `drishti.security.oidc.issuer` | empty | The OIDC issuer URL. |
| `DRISHTI_OIDC_CLIENT_ID` | `drishti.security.oidc.client-id` | empty | The OIDC client id. |
| `DRISHTI_ACCESS_LOG` | `drishti.access-log.enabled` | `true` | Record who looked at what (Admin → Access). |
| `DRISHTI_COLLAB_ENABLED` | `drishti.collab.enabled` | `true` | Share with a note, the directory and the inbox ([COLLABORATION.md](../architecture/COLLABORATION.md)). |
| `DRISHTI_COLLAB_DIR` | `drishti.collab.dir` | `./data/collab` | Where the file store keeps shares and inbox rows (`drishti.collab.store: file`), and exports. |
| `DRISHTI_CONSOLE_URL` | `drishti.collab.console-url` | empty | The console's address, put in front of `/share/sh_…` in the links shares carry; email stays off while it is empty. |
| `DRISHTI_MAIL_FROM` | `drishti.collab.email.from` | `drishti@localhost` | The From address of collaboration email. |
| `DRISHTI_MASK_COPIES` | `drishti.security.mask-copies` | `false` | Also scrub exact copies of a masked value inside other text of the same document. |
| `DRISHTI_MAX_SWALLOW` | `server.tomcat.max-swallow-size` | `64MB` | How much of an oversized body Tomcat drains after answering 413, so the client reads the answer. |
| `DRISHTI_REPORTS_ENABLED` | `drishti.reports.enabled` | `true` | Run the scheduled-reports scheduler on this server. |
| `DRISHTI_REPORTS_DIR` | `drishti.reports.folder` | `./data/reports` | Where report files are written. |
| `DRISHTI_SUTRA_WATCH` | `drishti.rachana.watch` | `auto` | How Sutra hot reload notices edits: `auto` (file events, polling when they are unavailable) or `poll`. |
| `DRISHTI_CALC_ENABLED` | `drishti.calc.enabled` | `true` | Allow Calc (Python in the browser). |
| `DRISHTI_LAYOUTS_ENABLED` | `drishti.layouts.enabled` | `true` | Allow personal layouts. |
| `DRISHTI_PIVOT_ENABLED` | `drishti.pivot.enabled` | `true` | Allow the Pivot tab. |
| `DRISHTI_ASK_ENABLED` | `drishti.explain.ask.enabled` | `false` | Turn on Ask about this page (also list packs in `drishti.explain.ask.packs`). |
| `DRISHTI_ASK_ENDPOINT` | `drishti.explain.ask.endpoint` | empty | **Deprecated** (a provider file in `config/ai` replaces it; it still works, as provider `ask`, with a start-up warning). The model endpoint URL for Ask. |
| `DRISHTI_ASK_MODEL` | `drishti.explain.ask.model` | empty | The model Ask uses: a logical model of the AI layer (`provider/model`); with the deprecated `endpoint`, the model id sent to it. |
| `DRISHTI_ASK_KEY` | `drishti.explain.ask.api-key` | empty | **Deprecated** with `endpoint`: put `api-key: ${ENV}` in the provider file. The model endpoint credential (environment only; never logged). |
| `DRISHTI_AI_ENABLED` | `drishti.ai.enabled` | `false` | Turn on the LLM provider layer ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md)): provider files in `drishti.ai.dir` are read and models become callable. Off: no file is read and no model is called. |
| `DRISHTI_AI_DIR` | `drishti.ai.dir` | `./config/ai` | The folder of provider files, one YAML per provider (type, `base-url`, `api-key: ${ENV}`, models with logical names). Watched and reloaded live (`drishti.ai.watch`: `auto` / `poll` / `off`, `poll-ms`). Provider types, their keys and one copy-paste file each: [AI_PROVIDERS.md section 11](../architecture/AI_PROVIDERS.md#11-more-providers-the-way-sajhamcpserver-has-them); examples `config/ai/<type>.yaml.example`, all `enabled: false`. |
| `DRISHTI_AI_DEFAULT_MODEL` | `drishti.ai.default-model` | empty | The model used when a call names none: `provider/model`, or a model name only one provider has. |
| `DRISHTI_MCP_ENABLED` | `drishti.mcp.enabled` | `false` | Turn on the MCP client ([MCP_CLIENT.md](../architecture/MCP_CLIENT.md)): server files in `drishti.mcp.dir` are read and their tools become callable through the policy. |
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
| `DRISHTI_SUTRA_REVIEW` | `drishti.governance.enabled` | `true` | A Studio save is a proposal an approver makes live. |
| `DRISHTI_SUTRA_FOUR_EYES` | `drishti.governance.four-eyes` | `true` | Nobody approves their own proposal (with security on). |
| `DRISHTI_GOVERNANCE_DIR` | `drishti.governance.dir` | `./data/governance` | Where proposals are kept. |
| `DRISHTI_DATA_DIR` | `drishti.data.dir` | `./data` | The data folder: the umbrella every runtime path below defaults under (see [The data folder](#the-data-folder-and-the-config-folder)). |
| `DRISHTI_INDEX_DIR` | `drishti.index.dir` | empty | The multi-day type-ahead index's segment files, one folder per connector; empty means `<data dir>/index` (`./data/index`). See [`drishti.index`](#drishtiindex--the-multi-day-type-ahead-index). |
| `DRISHTI_BI_DIR` | `drishti.bi.dir` | `./config/bi` | Where Rupaka (BI) files live: `datasets/`, `reports/`, `boards/`, `notebooks/`, `python/`. Never inside a pack. |
| `DRISHTI_BI_TRANSFORM_PYTHON` | `drishti.bi.transform.python` | empty | The Python that runs a derived dataset's Transform: it needs `pandas` and `pyarrow`, normally the console's `drishti-console/.venv/bin/python`. Empty: that file when it exists, else `python3`. |
| `DRISHTI_BI_TRANSFORM_RUNNER` | `drishti.bi.transform.runner` | empty | The console's `core/transform_cli.py`, which the server starts to run a Transform in the sandboxed runner. Empty: looked for in `drishti-console/core` from the working directory and its parents. |
| `DRISHTI_BI_TRANSFORM_CONSOLE_URL` | `drishti.bi.transform.console-url` | empty | (wave B) The console's Py-node endpoint base URL (`http://console:17480`): Transforms and Python measures run there (`/internal/py/*`). Empty: the server starts `transform_cli.py` itself. |
| `DRISHTI_BI_TRANSFORM_CONSOLE_TOKEN` | `drishti.bi.transform.console-token` | empty | (wave B) The shared secret sent as `X-Drishti-Runner-Token` on every Py-node call; the console refuses a call without it. |
| `DRISHTI_BI_NODE` | `drishti.bi.exec.node` | empty | (wave B) This Java node's name when several Java nodes serve the site (`drishti.bi.exec.nodes` lists them); empty: one node. |
| `DRISHTI_BI_MIGRATE_ON_START` | `drishti.bi.migrate.on-start` | `true` | At start, converts stored items once: old-syntax expressions to DEL v2, then `rachana: 1` datasets to datasets and models with their reports rebound (each change audited, the text before kept in the item's history). `false`: a bi-admin runs `drishti.py bi migrate` instead ([drishti.bi.migrate](#drishtibimigrate--migrations-of-stored-bi-items)). |
| `DRISHTI_BI_STORE` | `drishti.bi.query.store-dir` | `./data/bi-store` | The BI store: the typed Parquet layers (columns, rollups, live snapshots) the dataset query engine writes and reads, and DuckDB's spill folder. Rebuildable from the connectors; never commit it. |
| `DRISHTI_PACKS_DIR` | `drishti.packs.dir` | `./config/packs` | The packs directory. If it does not exist and the old `./packs` does, `./packs` is used with a warning (one release). |
| `DRISHTI_PACKS_EXTRA_DIRS` | `drishti.packs.extra-dirs` | empty | Further pack folders, comma-separated, looked in after the packs directory (the shipped packs win a name): `examples/packs` makes the retired example packs (finance, genomics, logistics, retail-banking, climate-risk, operational-risk, liquidity-risk) loadable by name ([PACKS.md](../guides/PACKS.md#retired-example-packs-examplespacks)). |
| `DRISHTI_PACKS_OVERLAY` | `drishti.packs.overlay` | `./data/packs/added.yaml` | The file of packs an administrator loaded from Admin → Packs; imported at start (`spring.config.import`) and named again by `drishti.packs.overlay`. Written by the server, not by hand. |
| `DRISHTI_PACKS_SAMPLES` | `drishti.packs.samples` | `visible` | Who sees sample packs: `visible`, `developers` (author or admin) or `hidden`; Admin → Packs can override it. |
| `DRISHTI_PACKS_SAMPLES_FILE` | `drishti.packs.samples-file` | `./data/packs/samples-mode` | Where the Admin → Packs choice for sample packs is saved. |
| `DRISHTI_PACKS_WATCH` | `drishti.packs.watch` | `auto` | Live pack reload: `auto`, `poll` or `off`. |
| `DRISHTI_PACKS_INSTALLED` | `drishti.packs.installed-dir` | `./data/packs/installed` | Where packs installed from a registry are kept. |
| `DRISHTI_CONNECTORS_DIR` | `drishti.sources.connectors-dir` | `./config/connectors` | The connector folder: one YAML file per connector, the file name being the connector's name (Admin → Connectors). Watched; a change applies without a restart. See [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md). |
| `DRISHTI_PACKS_SETTINGS` | `drishti.packs.settings-dir` | `./data/packs/settings` | Legacy: where the earlier per-pack data-source override files were. Read once at start and migrated into connector files; the originals are kept in `migrated/`. |
| `DRISHTI_UNINSTALLED_DIR` | `drishti.admin.uninstalled-dir` | `./data/uninstalled` | Where Admin → Artifacts moves what it uninstalls: one folder per uninstall, `<timestamp>-<kind>-<name>/`, holding the artifact as it was. See [`drishti.admin`](#drishtiadmin--uninstalling-packs-connectors-and-bi-files-safely). |
| `DRISHTI_UNINSTALLED_KEEP` | `drishti.admin.uninstalled-keep` | `30d` | How long an uninstalled artifact is kept before it is removed (pruned once a day); `0` keeps them for ever. |
| `DRISHTI_LOADS_DIR` | `drishti.loads.dir` | `./data/loads` | The data-load history per pack and the administrators' expectation overrides ([DATA_LOADS.md](../guides/DATA_LOADS.md)). |
| `DRISHTI_PACKS_DEPLOY_HISTORY` | `drishti.packs.deploy.history-file` | `./data/packs/deploy-history.jsonl` | The history of archive deployments and rollbacks. |
| `DRISHTI_PACK_REGISTRY` | `drishti.packs.registry.url` | empty | A signed pack registry (a folder, `file:` or `https:` URL with `index.json`); empty: none. |
| `DRISHTI_PACKS` | `drishti.packs.enabled` | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets` | The packs to load (comma list); a pack's parents load with it. The default is the seven shipped packs. |
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
| `DRISHTI_BACKEND_URLS` | `backend.urls` | empty | Several servers of one Drishti*net cluster, comma-separated; one is enough (the console learns the rest). Empty: `backend.url` alone, as before. See [The console and several servers](#the-console-and-several-servers-drishtinet). |
| `DRISHTI_BACKEND_DISCOVER` | `backend.discover` | `true` | With `backend.urls`: pull the member list from the server in use every `discover_every` and keep it. |
| `DRISHTI_CONSOLE_DATA_DIR` | `backend.data_dir` | empty | What the console keeps between runs (`net/servers.json`); empty: `<data dir>/console`. |
| `DRISHTI_CONSOLE_BACKEND_CHECK_SECONDS` | `backend.check_seconds` | `5` | How often the console asks each server if it is there (`0` = never). Pages show a banner and recover by themselves when it returns. |
| `DRISHTI_CONSOLE_TMP_DIR` | `tmp_dir` | empty | Base of the console's temp folder `drishti-console-<pid>` (empty: `DRISHTI_TMP_DIR`, then `<data dir>/tmp/console` when started by `run_drishti_web.py`), one per worker process, removed on exit; left-over ones of dead processes are listed on Health, never deleted automatically. |
| `DRISHTI_EXTERNAL_LIB` | `external_lib` | empty | Base of the external library folder (empty: `external_lib` in the repository root); `<base>/python` is importable. The server reads the same variable for `<base>/java`. |
| `DRISHTI_PRODUCT` | `ui.product` | `Drishti` | The product name shown in pages. |
| `DRISHTI_USER` | `ui.user` | `ash` | The acting user while sign-in is off. |
| `DRISHTI_ABOUT_PREFETCH` | `ui.about_prefetch` | `true` | Fetch *About this page* when a view goes idle, so labels with a glossary entry are underlined at once; costs one explain per page view. |
| `DRISHTI_EMBED_ENABLED` | `embed.enabled` | `false` | Serve `/embed/v1/` for host applications registered at the server (ELEMENTS.md). |
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
| `DRISHTI_CALC_ENABLED` | `calc.enabled` | `true` | Offer Calc. |
| `DRISHTI_BI_ENABLED` | `bi.enabled` | `true` | Rupaka phase 1: the BI top-bar menu, the `/bi` pages and the `RUPAKA` keyword. The server has its own switch, `drishti.bi.enabled`. |
| `DRISHTI_BI_LIVE_MIN_INTERVAL_MS` | `bi.live.min_interval_ms` | `250` | Rupaka phase 5: a report or board redraws its live visuals at most this often, never two rounds at once; a burst of ticks is one redraw (100 to 5000). |
| `DRISHTI_BI_LIVE_STALE_SECONDS` | `bi.live.stale_seconds` | `30` | A live report or board that has heard nothing from the server (not even its 5-second status) for this long says **Stale** (5 to 600). |
| `DRISHTI_BI_BOARD_ROTATE_SECONDS` | `bi.board.rotate_seconds` | `30` | A board in kiosk mode turns to its next page after this long, unless the board sets `rotate:` (3 to 3600). |
| `DRISHTI_BI_PYTHON_ENABLED` | `bi.python.enabled` | `true` | Rupaka phase 6 ([PYTHON_BI.md](../guides/PYTHON_BI.md)): the console's Python runner. Off: a python visual's server target answers `DRS-9074` (the browser target still works where an item allows it). |
| `DRISHTI_BI_PYTHON_WORKERS` | `bi.python.workers` | `2` | Worker processes at once; a call waits `bi.python.queue_seconds` (`5`) for a place, then `DRS-9074`. |
| `DRISHTI_BI_PYTHON_QUEUE_SECONDS` | `bi.python.queue_seconds` | `5` | How long a Python call waits for a free worker. |
| `DRISHTI_BI_PYTHON_MAX_TIMEOUT` | `bi.python.max_timeout_seconds` | `60` | The most a manifest's `timeout` may be: the CPU and wall-clock limit of one call. |
| `DRISHTI_BI_PYTHON_MAX_MEMORY_MB` | `bi.python.max_memory_mb` | `1024` | The most a manifest's `memory` may be, on top of what the interpreter and its packages need to start. |
| `DRISHTI_BI_PYTHON_MAX_INPUT_BYTES` | `bi.python.max_input_bytes` | `16000000` | Largest Arrow table handed to Python (`DRS-9072`). |
| `DRISHTI_BI_PYTHON_MAX_OUTPUT_BYTES` | `bi.python.max_output_bytes` | `1000000` | Largest answer (a chart option, an image, a measure's rows) (`DRS-9072`). |
| (no variable) | `bi.python.packages` | `[pandas, numpy, pyarrow, scipy, statsmodels]` | The allow-list of packages Python items may use; one not installed is refused when used. Equal to the server's `drishti.bi.report.python-packages`. |
| (no variable) | `bi.python.max_groups`, `network_namespace`, `scratch_dir` | `5000`, `auto`, system temp | Most groups a measure is called for; `auto`, `require` or `off` for the no-network namespace (the audit hook is always on); where scratch folders are made. |
| `DRISHTI_BI_PYTHON_FORK_SERVER` | `bi.python.fork_server` | `true` | (wave B) Python calls run in children of one **fork-server** per console: a parent that has imported pandas, numpy and pyarrow forks one child per call (its own session, limits, audit hook and scratch folder), so a call does not pay for an interpreter's start. `false`: a new process per call. `/bi/python/status` says `forkServer: {on, running, forks, failed}`. |
| `DRISHTI_BI_PYTHON_WARM` | `bi.python.warm` | `true` | (2.4.0) Start the fork-server when the console starts, so the first Python call (a report, a Transform's step run) is warm too; `/bi/python/status` says `forkServer.warm` and `warmedMs`. |
| (no variable) | `bi.python.cache_entries`, `cache_max_bytes`, `cache_seconds` | `256`, `64000000`, `600` | (wave B) The **L4 result cache** of Python answers, by the item's code, its parameters and the exact input bytes (already masked for the reader): most entries, most bytes, how long one is kept. `0` entries turns it off. A cell is never cached. |
| `DRISHTI_BI_TRANSFORM_CONSOLE_TOKEN` | `bi.python.runner_token` | empty | (wave B) The secret the server sends as `X-Drishti-Runner-Token` on every call of the console's **Py-node endpoint** (`/internal/py/transform`, `/measure`, `/column`; [API_GUIDE.md](../guides/API_GUIDE.md)); the same variable sets the server's `drishti.bi.transform.console-token`. Empty: the endpoint is off (`503 DRS-9074`); a wrong token is `403 DRS-9073`. |
| `DRISHTI_BI_EXPLORE_MAX_ROWS` | `bi.explore.max_rows` | `10000` | Rupaka phase 9 ([ANALYSE.md](../guides/ANALYSE.md)): the most rows of an Analyse extract (the reader's rows, cut here; 10 to 100000). Without `pyarrow` on the console the server's JSON limit (`drishti.bi.query.json-max-rows`, 5000) also applies. |
| (no variable) | `bi.explore.max_fields`, `sql_max_rows`, `sql_max_seconds`, `sql_max_chars` | `60`, `5000`, `5`, `20000` | Most columns of an extract; most rows a SQL cell returns; a SQL cell running longer is stopped; longest SQL text (`DRS-9072` for each). |
| (no variable) | `bi.explore.max_extracts` | `6` | Most further extracts in one notebook, besides the first (`extract("name")` in a cell, a table of that name in SQL; `DRS-9072`). See [ANALYSE.md section 2](../guides/ANALYSE.md#2-the-extract). |
| (no variable) | `bi.explore.cell_timeout_seconds`, `cell_memory_mb`, `cell_max_chars`, `max_cells` | `20`, `512`, `65536`, `60` | A Python cell's time and memory on the server (also capped by `bi.python.max_timeout_seconds` and `max_memory_mb`); longest cell; most cells in a notebook. |
| (no variable) | `bi.explore.quant_engine` | `stand-in` | The engine behind `quant.price(...)` in a Python cell ([QUANT_SERVICE.md](../architecture/QUANT_SERVICE.md#16-the-consoles-quantengine-slot-built-lean)); only `stand-in` is built in. |
| `DRISHTI_BI_TRANSFORM_PREVIEW_ROWS` | `bi.transform.preview_rows` | `1000` | (2.4.0) The Transform editor ([PYTHON_BI.md section 17](../guides/PYTHON_BI.md#17-data-prep-the-transform-editor-240)): rows of each input its step-by-step run starts from, asked of the server as the person (10 to 50000; the server's `drishti.bi.transform.max-input-rows` also caps it). |
| (no variable) | `bi.transform.timeout_seconds`, `memory_mb`, `snippets_file` | `20`, `512`, empty | The step run's time and memory (also capped by `bi.python.max_timeout_seconds`, `max_memory_mb`); a file of more data-prep snippets read after `drishti-console/config/prep-snippets.yaml`. |
| `DRISHTI_BI_PYCONSOLE_ENABLED` | `bi.pyconsole.enabled` | `true` | (2.4.0) The browser Python console at `/pyconsole` ([ANALYSE.md section 16](../guides/ANALYSE.md#16-the-browser-python-console)); it also needs Calc's runtime (`tools/fetch-pyodide.sh`). |
| `DRISHTI_BI_PYCONSOLE_MAX_ROWS` | `bi.pyconsole.max_rows` | `20000` | Most rows of an extract handed to the browser (the reader's masked rows, cut here; 10 to 100000). |
| `DRISHTI_LAYOUTS_ENABLED` | `layouts.enabled` | `true` | Offer layout mode. |
| `DRISHTI_PACKS_DIR` | `packs.dir` | `../config/packs` | The packs directory (a relative path resolves from `drishti-console/`). |
| `DRISHTI_PACKS_EXTRA_DIRS` | `packs.extra_dirs` | empty | Further pack folders, comma-separated, as on the server (relative paths resolve from `drishti-console/`, so `../examples/packs`). |
| `DRISHTI_PACKS` | `packs.enabled` | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets` | Only used when the server cannot be asked which packs are on. |
| `DRISHTI_LAKE_ROOTS` | `lakes.roots` | empty | Admin → Data loads → Lakes ([TOOLS.md](../guides/TOOLS.md#4-lake-build-a-large-json-export-into-a-delta-lake)): the lake roots this console may write, comma-separated (relative paths resolve from `drishti-console/`); empty: `<data dir>/delta`, the server's own root. The page offers no other folder. |
| `DRISHTI_JAR` | `lakes.jar` | empty | The drishti-server exec jar whose Java writer the page's builds use (`lakes.engine: auto`); empty: the newest `drishti-server/target/drishti-server-*-exec.jar`, else the Python `deltalake` package. |
| `DRISHTI_DATA_DIR` | `lakes.data_dir` | `../data` | Admin → Lakes → Maintain ([LAKE_MAINTENANCE.md](LAKE_MAINTENANCE.md)): the data folder whose `lakes/lake-maintenance.yaml` (the file `lake maintain` reads by default) and `lakes/maintenance-history.jsonl` the page uses (relative paths resolve from `drishti-console/`). |
| `DRISHTI_LAKE_MAINTENANCE_SCHEDULES` | `lakes.maintenance_schedules` | `true` | Run the lake-maintenance schedules in this console; `false` when `lake maintain --daemon` runs them instead (run them in one place). |
| (no variable) | `lakes.max_upload_mb`, `scan_docs`, `engine`, `python`, `keep_min`, `jobs` | `4096`, `20000`, `auto`, the console's Python, `240`, `2` | Largest upload (streamed to the console's temp folder); documents read to suggest the id, date and classifiers; `auto`, `drishti` or `deltalake`; the Python that runs `tools/lake/build.py`; minutes an upload and a finished build are kept; builds at once. |
| `DRISHTI_AI_ENABLED` | `ai.enabled` | `false` | Turn on the console's own LLM provider layer (the recommended provider is `type: server`, which delegates to the server so keys stay there). |
| `DRISHTI_AI_DIR` | `ai.dir` | `./config/ai` | The folder of provider files (the same format and, if you share the folder, the same files as the server). |
| `DRISHTI_AI_DEFAULT_MODEL` | `ai.default_model` | empty | The model used when a call names none (`provider/model`). |
| `DRISHTI_MCP_ENABLED` | `mcp.enabled` | `false` | Turn on the console's MCP client. |
| `DRISHTI_MCP_DIR` | `mcp.dir` | `./config/mcp` | The folder of MCP server files. |
| `DRISHTI_RETRY_ENABLED` | `retry.enabled` | `true` | Try again what failed (the console's AI providers and MCP servers); `false` turns the retries off. |
| `DRISHTI_RETRY_INITIAL` | `retry.initial` | `5s` | Wait before the first retry. |
| `DRISHTI_RETRY_MAX` | `retry.max` | `5m` | No wait is longer than this. |

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
| `drishti.retry.alerts.*` | (none) | the general value | The retries of an alert's email, webhook and text; how many tries is `drishti.alerts.measure.max-attempts` (below). A pending retry is kept in memory: a restart drops it and the alert's history keeps the last text it was given |
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

Several servers acting as one ([NET.md](NET.md), the design [DRISHTI_NET.md](../architecture/DRISHTI_NET.md)). Like connectors, Drishti*net is
configured in a folder of small YAML files, never in `application.yaml`, which says only where the folder is and how it is watched. Without the
folder, or while its `node.yaml` does not say `enabled: true`, the server is a single server exactly as before.

| Setting | Default | Meaning |
|---|---|---|
| `drishti.net.dir` | `./config/net` (`DRISHTI_NET_DIR`) | The folder; created on the first write (Admin → Net, `drishti.py net`). Ready-made files are in `config/net.examples/`. |
| `drishti.net.watch` | `auto` (`DRISHTI_NET_WATCH`) | `auto`: file events, polling as a fallback; `poll`: polling only; `off`: read at start only. |
| `drishti.net.poll` | `5s` | How often the folder is looked at when polling (and, with events, at least this often anyway). |

The files, each checked against its schema (a refused file keeps the previous settings in force and shows its error in Admin → Net):

| File | Settings (default) |
|---|---|
| `node.yaml` | `enabled` (`false`), `node-id` (generated once, kept in `<data dir>/net/node-id`), `advertise-url` (this machine's primary IPv4 address with `server.port`; never `localhost`, `127.x.x.x`, `::1`, `0.0.0.0` or `::`), `tags` (`[]`), `peer-only-packs` (`[]`), `secret: ${DRISHTI_NET_SECRET}` and `previous-secret: ${DRISHTI_NET_PREVIOUS_SECRET}` (environment references only, at least 32 bytes), `tls.*` (mutual TLS) |
| `peers/<name>.yaml` | `url` (a real address, as `advertise-url`), `tags`, `tls.*` (one file per seed) |
| `gossip.yaml` | `interval` (`1s`), `fanout` (`3`), `suspect-after` (`5s`), `indirect-probes` (`2`), `dead-after` (`30s`), `forget-after` (`24h`), `persist-every` (`10m`) |
| `routing.yaml` | `forward-timeout` (`5s`), `scatter-budget` (`3s`), `suggest-budget` (`150ms`), `link-budget` (`1s`: a view's linked entity that only a peer holds), `max-hops` (`1`; `0` forwards nothing), `weights.latency` (`1.0`), `user-state` (`replicate` or `off`: user state and console sign-in sessions) |
| `access.yaml` | `roles: { <role>: { nodes: [<node id or tag>, ...] } }` (a role not listed reaches every node) |

`enabled: true` without the key is refused at start and at every reload, as the server refuses a missing token secret with security on; so is
an `advertise-url` or a seed `url` that is a loopback or wildcard address (the message names this machine's address to use instead), and
`enabled: true` without `advertise-url` on a machine with no address but loopback. Members advertise only real addresses
([NET.md section 2](NET.md#2-a-cluster-of-three)). What gossip learns (the member table) is state, kept in `<data dir>/net/peers.json`, never in this folder. Every file in
[NET.md section 3](NET.md#3-the-files-of-confignet).

### The console and several servers (Drishti*net)

With Drishti*net on ([NET.md](NET.md)), the console takes several servers instead of one, so it always has one to fall back to:

```yaml
# drishti-console/config/application.yaml
backend:
  urls: ${DRISHTI_BACKEND_URLS:}       # e.g. https://drishti-a:8480 (one is enough; empty: backend.url, as before)
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
`drishti.mcp.enabled` (console `ai.enabled` / `mcp.enabled`) is true; see [AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md)
and [MCP_CLIENT.md](../architecture/MCP_CLIENT.md).

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
DRISHTI_PORT=18490 java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
```

You should see `Tomcat started on port 18490` in the log. Point the console at it with
`DRISHTI_BACKEND_URL=http://127.0.0.1:18490`.

### Load more packs

```bash
DRISHTI_PACKS=market-risk,world-economy java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
```

Packs a listed pack `extends:` (or the older `requires:`) are loaded too, dependencies first. Then
`curl -s localhost:18480/api/v1/packs` lists each loaded pack. If the console runs from another directory,
set `DRISHTI_PACKS_DIR` to an absolute path so both programs find the same folder.

### Turn security on (production)

```bash
export DRISHTI_SECURITY_ENABLED=true
export DRISHTI_TOKEN_SECRET="$(openssl rand -base64 48)"   # at least 32 bytes; give the console the same value
export DRISHTI_SEED_ADMIN=false
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
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
DRISHTI_CALENDAR=USNY+GBLO java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
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
    fetch-timeout: 5s
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
| `fetch-timeout` | `5s` | The longest a single source read may take (`DRS-1004`). Slower reads show the entity as unavailable instead of holding the view. |
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
([HISTORY_QUERIES.md](../architecture/HISTORY_QUERIES.md), `GET /api/v1/history/{kind}/{id}`). These limits follow a settings reload
without a restart. A pack's `history:` block can lower `max-days` and set `default-days` per kind ([PACKS.md](../guides/PACKS.md)).

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
| `drishti.history.cache-mb` | `64` | Memory for cached answers. An entry is keyed by the kind's data version, so a new load never serves stale rows; a kind whose connector gives no version is not cached. Admin → Cache → `history` empties it. |

### `drishti.packs` — domain packs

| Key | Default | Meaning |
|---|---|---|
| `dir` | `./config/packs` (`DRISHTI_PACKS_DIR`) | The folder holding one sub-folder per pack. Relative to the server's working directory. The console reads the same variable (its default `../config/packs` is relative to `drishti-console/`), so use an absolute path when you set it. |
| `extra-dirs` | empty (`DRISHTI_PACKS_EXTRA_DIRS`) | Further pack folders, comma-separated, looked in after `dir`; `examples/packs` holds the retired example packs. A pack there may extend the shipped ones. |
| `enabled` | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets` (`DRISHTI_PACKS`) | Comma-separated packs to load, in order. Packs they `extends:` (or the older `requires:`) are loaded as well. A missing pack stops start-up with a clear message. |
| `default-for-users` | empty = every installed pack (`DRISHTI_DEFAULT_PACKS`) | The packs a user sees until an admin assigns packs to them. |
| `samples` | `visible` (`DRISHTI_PACKS_SAMPLES`) | Who sees sample packs (`sample: true` in `pack.yaml`): `visible` (everyone), `developers` (only users with the author or admin power; use this once real packs exist) or `hidden` (nobody; sample packs are not loaded and their connectors do not start). The setting an administrator saves in Admin → Packs overrides it. |
| `samples-file` | `./data/packs/samples-mode` (`DRISHTI_PACKS_SAMPLES_FILE`) | Where that saved setting is kept (one word). Delete it, or press *Use the configuration*, to let `samples` decide again. |
| `watch` | `auto` (`DRISHTI_PACKS_WATCH`) | Live pack reload: `auto` (file events in `dir` and `installed-dir`, polling as the safety net), `poll`, or `off` (only Admin → Packs changes packs while the server runs). |
| `poll` | `5s` | How often the pack folders are looked at (also the safety net beside file events). |
| `reload-debounce` | `750ms` | A burst of changes is acted on once the folders have been quiet this long. |

The pack loader also writes some keys for the rest of the server (`drishti.packs.loaded`,
`drishti.packs.kinds.*`, `drishti.packs.overrides`, `drishti.rachana.pack-dirs`,
`drishti.rachana.pack-formats-files`, `drishti.inference.pack-semantics-files`,
`drishti.sources.plugins.demo.settings.dirs`). Do not set them yourself. See [PACKS.md](../guides/PACKS.md).

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

The console has its own copy under `ui:` (`product`, `tagline`, `copyright`, `notice`; see [`ui`](#ui)): set both when you rebrand.

### `drishti.identity` — users, passwords and the identity database

Users, roles, saved workspaces and the audit trail live in one database (SQLite by default, PostgreSQL for several
servers). The environment variables of this section are in the [placeholder table](#every-placeholder-in-the-servers-applicationyaml).

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
| `users-file` / `audit-file` / `preferences-dir` | `./data/identity/users.json` / `./data/identity/audit.jsonl` / `./data/identity/preferences` | The files of releases before 1.10, imported once into an empty database and renamed `*.imported`. |

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
| `roles.<role>.calc` | `false` | May use Calc, Python in the browser on what the role opens ([PYTHON_CALC.md](../guides/PYTHON_CALC.md#9-roles-who-may-use-calc)). |
| `roles.<role>.layout` | `true` | May customise layouts: layout mode (`Alt+L`) and personal layouts ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)). On unless set to `false`; the bundled `viewer` sets it to `false`. |
| `roles.<role>.collaborate` | `true` | May share, comment and mention ([COLLABORATION.md](../architecture/COLLABORATION.md)). On unless set to `false`; a role saved by an earlier release keeps it without a migration. |
| `roles.<role>.compliance` | `false` | May read any share, place and release legal holds, export the collaboration record and verify hash chains (`/admin/collab/holds`, `/exports`, `/verify`). Not implied by `admin`: grant it to the compliance role. Moderation, retention runs and permanent removal are `admin`. |
| `registered-users-only` | `true` | A signed bearer token is refused (`401`) when its user was deleted or disabled in the identity store, so a token no longer outlives the account; the console's own service identity is exempt. `false` trusts the token alone. |
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
mask written before the 2.3.0 UPPER_SNAKE rename keeps masking. An entry that names no field of the loaded packs' samples
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

In the console's `config/application.yaml` (or `--embed.key=value`). Off by default; see [ELEMENTS.md](../architecture/ELEMENTS.md) (step 7).

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
| `embed.lifecycle` | empty | Announces the end of an embed API major: `{"1": {deprecation: 2027-01-01, sunset: 2027-07-01, link: https://drishti.example/migrating}}`. From the `deprecation` day every `/embed/v1/` answer carries `Deprecation: @<epoch>` (RFC 9745) and, whenever a `sunset` is set, `Sunset: <HTTP date>` (RFC 8594) and a `Link: <...>; rel="deprecation"`; from the `sunset` day the embed calls are `410 DRS-8006`. Nothing is announced by default. See [ELEMENTS.md](../architecture/ELEMENTS.md#13-distribution-and-versioning). |

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
| `fingerprint-cache-size` | `100000` | Data-shape fingerprints kept, one per (entity, generation). |
| `bind-parallelism` | `0` (one thread per CPU core) | Threads binding panels. `0` or a negative value means one per core. |

Raise the cache sizes when many distinct entities are viewed and the cache hit rates in **Admin → Health** are
low. See [PERFORMANCE.md](PERFORMANCE.md).

### `drishti.explain` — About this page

`GET /api/v1/views/{kind}/{id}/explain` answers the page's **?** and F1 drawer: where the data came from, why the page
looks the way it does, and the pack's text about it ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md)). It is derived
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
| `endpoint` | empty (`DRISHTI_ASK_ENDPOINT`) | The model's URL: an OpenAI-compatible chat completions URL, or an Anthropic Messages URL. |
| `api` | `openai` | `openai` or `anthropic`: the request and response shape. |
| `model` | empty (`DRISHTI_ASK_MODEL`) | The model name sent to the endpoint. |
| `api-key` | empty (`DRISHTI_ASK_KEY`) | Sent as `Authorization: Bearer` (`openai`) or `x-api-key` (`anthropic`). Environment only; never logged or returned. |
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
the page's own values ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md#pack-schema-additions)).

| Key | Default | Meaning |
|---|---|---|
| `max-text` | `600` | Longest About text a pack may write for one entry, in characters; a longer one is a load problem (`DRS-204x`). |
| `max-rendered` | `1000` | Longest text after its expressions are filled in; the rest is cut with `…`. |
| `packs` | set by the server | The packs' About files and their `extends` lineage, written by the pack loader. Do not set it. |

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

A Design (`/api/v1/builder/designs`, **Build → My designs**) keeps a user's samples, Sutra and notes on the server, so work
survives a console restart and shows on every console. Only its owner can reach it (anyone else gets `404 DRS-5006`).
Sample documents are never logged; deleting a Design deletes its samples at once. A limit answers `413 DRS-5005`.

| Key | Default | Meaning |
|---|---|---|
| `store` | `file` | `file`: `data/designs/<user>/<id>/design.json` and `samples/`, created readable by the server's account only (`rwx------`). `jpa`: the identity database (`drishti_design`, `drishti_design_sample`), for several server replicas. |
| `dir` | `./data/designs` | The file store's directory. |
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
| `pivot.<kind>` | from packs | A Pivot tab on the kind's results: `true`, or the pivot as a JSON string (`pivot:` in `pack.yaml`, [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#pivot-a-pivot-tab-on-search-results)). |

### `drishti.calc` — Python in the browser

Calc ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)) runs Python in the user's browser; the server never runs the code. It
decides who may use it (roles with `calc`), keeps each user's snippets, and serves whole columns of a day.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_CALC_ENABLED`) | Calc at all. Off: nobody may use it, and its endpoints refuse with `403 DRS-5002`. The console has its own switch, `calc.enabled`, read from the same variable. |
| `max-column-rows` | `250000` | `drishti.columns()` (`GET /api/v1/search/columns/{kind}`) returns at most this many entities, the first by id; the browser holds them all. |
| `columns-budget` | `20s` | How long a columns read may wait for its source (the first read of a business day loads its columns). |
| `max-snippet-chars` | `50000` | A saved snippet's code, at most (never more than 60,000: a saved document is at most 64 KB). Each user keeps at most 50 snippets. |

Which views offer Calc is a pack's choice (`python: { enabled: true }` in `pack.yaml`), not configuration; see
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#calc-python-snippets).

### `drishti.bi` — Rupaka (business intelligence), phase 1: the shell and the catalogue

Workspaces, items, versions and the catalogue ([BI_GUIDE.md](../guides/BI_GUIDE.md)). Content lives in the identity database; the BI folder holds
shipped, read-only files. The BI powers are granted on roles, see below.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | The BI endpoints answer. Off: `403 DRS-9006`, and the console (which asks the server) hides the BI menu. |
| `dir` | `./config/bi` (`DRISHTI_BI_DIR`) | The BI folder: `datasets/<id>.dataset.yaml`, `reports/<id>.report.yaml`, `boards/<id>.board.yaml`, `notebooks/<id>.notebook.yaml`, `python/`. Shipped files appear as published, read-only items of the workspace *Shipped content*. Not inside a pack. See `config/bi/README.md` and [RUPAKA.md](../architecture/RUPAKA.md), "Where BI files live". |
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

**Console, compressed static files.** The console serves `/static/...` compressed (the 1.1 MB `echarts.min.js` travels as 370 KB gzipped; until 2.2 the 36 MB DuckDB-Wasm module was the large one): `static.compress` (default `true`), `static.compress_min_bytes` (`1024`; smaller files are sent as they are),
`static.brotli_quality` (`5`; Brotli is used when the `brotli` package is installed and the browser accepts it, else gzip) and `static.cache_dir` (empty: the system temp folder, `drishti-console-static`). Each file is compressed once per version
(path, size and modification time) and served from the cache; range requests, images and fonts go as they are.

### `drishti.bi.query` — the dataset query engine (phase 2)

The embedded DuckDB that answers dataset queries ([DATASETS.md](../guides/DATASETS.md), [BI_GUIDE.md](../guides/BI_GUIDE.md) section 13) and the layers it keeps. It starts its connection on the first query. Every limit
answers with its own code (the codes are in [API_GUIDE.md](../guides/API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2)). Defaults are what a laptop can carry.

| Key | Default | Meaning |
|---|---|---|
| `store-dir` | `./data/bi-store` (`DRISHTI_BI_STORE`) | The BI store: `columns/<dataset>/<table>/date=<d>/part-<id>.parquet` (the file its `meta.json` names), `rollups/...`, `live/...`, `duckdb-tmp/`. Anything in it can be rebuilt from the connectors. |
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

How many rows each kind of visual asks the engine for ([REPORTS.md](../guides/REPORTS.md) section 11). The engine's own limits (`drishti.bi.query.*`, above) still apply on top; a visual's own `limit:` replaces the default of its kind.

| Key | Default | Meaning |
|---|---|---|
| `chart-rows` | `500` | Most categories one chart asks for (a chart says when it was cut). |
| `table-rows` | `1000` | Most rows a table or matrix asks for. |
| `slicer-values` | `1000` | Most distinct values a slicer lists. |
| `show-rows` | `1000` | Most rows *Show the rows* returns. |
| `top-candidates` | `10000` | Most values a top-N filter ranks before it keeps its N. |
| `distribution-rows` | `20000` | Most members a **histogram** or **box plot** reads (the server bins or summarises them; the picture is a few dozen rows). |
| `totals-max-rows` | `2000` | Most rows one level of **true totals** (a matrix's subtotal level or a table's total) may return. |
| `perspective-rows` | `20000` | Most rows a 2.1 `perspective-*` visual is sent. Since 2.3.0 the page draws them in Drishti's own grid (no Perspective, no WebAssembly); until then the browser held and pivoted them (the retest of [RUPAKA_POC.md](../architecture/RUPAKA_POC.md#12-the-perspective-retest-phase-5) measured 10,000 rows at 21 MB of JS heap). |
| `python-rows` | `10000` | Most rows a **python visual** hands the Python runner ([PYTHON_BI.md](../guides/PYTHON_BI.md), phase 6). |
| `python-packages` | `pandas, numpy, pyarrow, scipy, statsmodels` | The packages a Python item's manifest may list (publishing refuses another: `DRS-9070`). Keep it equal to the console's `bi.python.packages`, which is what the runner enforces. |
| `python-max-seconds` | `60` | The largest `timeout` a Python manifest may ask for. |
| `python-max-memory-mb` | `1024` | The largest `memory` a Python manifest may ask for, in megabytes. |

### `drishti.bi.transform` — Python Transforms and composed datasets

A **Python dataset** (2.2; before it, a derived dataset) is made by a Python Transform; the server starts the console's sandboxed runner (`drishti-console/core/transform_cli.py`, which uses `core/pyrunner.py`) for each run, hands it the inputs as Arrow files and reads the frame back as Parquet ([DATASETS.md section 11](../guides/DATASETS.md#112-derived-datasets-a-python-transform), [PYTHON_BI.md section 13](../guides/PYTHON_BI.md#13-transforms-a-table-in-a-table-out)). The result is kept per reader and version of the inputs. The package allow-list, `timeout` and `memory` limits are the Python items' own (`drishti.bi.report.python-*`, and the console's `bi.python.*` inside the runner). `compose-source-max-rows` also limits a **composed** dataset's sources.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: a derived dataset answers `DRS-9074`. |
| `python` (`DRISHTI_BI_TRANSFORM_PYTHON`) | empty | The Python that has `pandas` and `pyarrow`, normally the console's `.venv/bin/python`. Empty: `drishti-console/.venv/bin/python` when it exists, else `python3`. |
| `runner` (`DRISHTI_BI_TRANSFORM_RUNNER`) | empty | The console's `core/transform_cli.py`. Empty: looked for in `drishti-console/core` from the working directory and its parents; not found is `DRS-9074`. |
| `work-dir` | empty | Where each run's scratch folder is made (removed afterwards). Empty: `<drishti.bi.query.store-dir>/transform-tmp`. |
| `cache-ttl` | `30m` | How long a Transform's result is served to the reader it was made for (and only while the inputs are unchanged). |
| `cache-entries` | `20` | Results kept per derived dataset (a reader and a version of the inputs each); the oldest go first. |
| `max-input-rows` | `50000` | Most rows of one input frame handed to a Transform (`DRS-9072`). |
| `max-rows` | `100000` | Most rows a Transform may return (`DRS-9072`). |
| `max-bytes` | `33554432` | Largest result file (`DRS-9072`). |
| `timeout` | `60s` | Longest a run may take (the item's own `timeout` may be shorter; `DRS-9072`, HTTP 504). |
| `max-concurrent` | `2` | Transforms running at once on the server. |
| `queue-wait` | `5s` | How long a run waits for a place before `DRS-9074`. |
| `compose-source-max-rows` | `50000` | Most rows one source of a composed dataset may answer (`DRS-9022`): group it by less or filter it in `from`. |
| `console-url` (`DRISHTI_BI_TRANSFORM_CONSOLE_URL`) | empty | (wave B) The console's Py-node endpoint: Transforms and Python measures are `POST {console-url}/internal/py/transform` and `/internal/py/measure` ([API_GUIDE.md](../guides/API_GUIDE.md)); `max-concurrent`, `queue-wait` and `timeout` apply to those calls. Empty: the process runner above. |
| `console-token` (`DRISHTI_BI_TRANSFORM_CONSOLE_TOKEN`) | empty | (wave B) The shared secret of those calls (`X-Drishti-Runner-Token`). |

### `drishti.bi.refresh` — scheduled dataset refresh

A dataset's `refresh:` schedule rebuilds its columns and rollup layers ([DATASETS.md section 7.2](../guides/DATASETS.md#72-scheduled-refresh)); a failed run is retried with the shared retry policy, area `bi` (`drishti.retry.bi.*`, falling back to `drishti.retry.*`: see Retries above).
Times are in `drishti.business-date.zone`, business days by `drishti.business-date.calendar`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: no schedule runs (a data-load signal and `POST /datasets/{id}/refresh` still do). |
| `tick` | `30s` | How often the scheduler looks for a refresh that is due or a retry whose wait is over (a schedule runs within one tick of its time). |
| `max-attempts` | `4` | Tries for one scheduled refresh (the first included) before it is left `failed` until its next scheduled time (1 to 20). |
| `load-delay` | `2s` | For `after-load`: how long after the data-load signal the refresh starts. |
| `history` | `25` | Past runs of each job kept and shown under **Recent refreshes** (0 to 500). The state and the history are written to `<drishti.bi.query.store-dir>/refresh/state.json`, so they survive a restart; a run in progress when the server stopped shows as `interrupted`, and a time that passed while it was down is run once at start-up. |

### `drishti.bi.ai` — BI with AI (stage 2)

The BI hooks on the AI layer ([BI_AI.md](../guides/BI_AI.md)). They also need `drishti.ai.enabled` (off by default) and a provider ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md));
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

Live tables kept in memory by the engine and the streams that tell browsers they changed ([DATASETS.md](../guides/DATASETS.md#71-live-tables-phase-5), [REPORTS.md](../guides/REPORTS.md#13-live-visuals-and-perspective-phase-5), [BOARDS.md](../guides/BOARDS.md),
[BI_GUIDE.md](../guides/BI_GUIDE.md) section 18). A table starts on first use and closes when nobody has used it for `idle-close`. The console's own settings are `bi.live.min_interval_ms`, `bi.live.stale_seconds`, `bi.board.rotate_seconds` and, for the Python runner, `bi.python.*` (the environment table above; [PYTHON_BI.md](../guides/PYTHON_BI.md) section 9).

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
| `incremental-aggregates` | `true` | The rollups a dataset declares over a live table are kept by group as rows change (sums and counts by difference; a lost minimum or maximum is read again for its group alone), and a query a rollup can answer reads them ([DATASETS.md section 7.1](../guides/DATASETS.md#71-live-tables-phase-5)). `false`: every measure of a live table is computed over its rows on each query. |

### `drishti.bi.mq` — the model layer and the model query language (2.2, wave W1)

Data models, pack-generated datasets and the model query language (`mq: 1`) of [BI_MODEL_SPEC.md](../architecture/BI_MODEL_SPEC.md): the bounds of one query and of
standing queries (`POST /api/v1/bi/mq`, `/mq/subscribe`, [API_GUIDE.md](../guides/API_GUIDE.md#models-and-the-model-query-language-apiv1bi-wave-w1)). A standing query's
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
| `maintenance-budget` | `250ms` | (wave B) Per model, the time keeping its standing queries current may take in each second; past it the model's groups are recomputed every `tick` and say `tick` (status and the `drishti_bi_sq_mode` metric) until a second spends less than half of it. |
| `tick` | `5s` | (wave B) How often a standing query in tick mode is recomputed: windows, time intelligence and Python measures, and every query of a model over its maintenance budget. |

Wave B ([BI_DESIGN.md §4.6](../architecture/BI_DESIGN.md#46-standing-queries-and-incremental-maintenance)): subscriptions are grouped by plan hash and security
signature (one evaluation per group, `subscribers` in the subscribe answer); a group is kept current by difference (`sum`, `count`, `avg` of the live table's
own columns, the reader's rules worked out per changed row), by asking the touched groups again (`min`, `max`, `distinct`, rules the engine works out), by
asking the whole question again (contexts, fields of other tables, loads and refreshes), or on the tick. A group no subscriber reads stays 30 s.

### `drishti.bi.exec` — execution, connectors and scale (wave B, 2.3.0)

How the query engine shares the server ([BI_DESIGN.md §6.3 to §6.7](../architecture/BI_DESIGN.md#63-concurrency-fairness-and-memory)): work classes and
slots, per-person and per-model shares, memory and cost budgets, per-connector fragment pools and circuit breakers, and the Java nodes of a site that runs
more than one ([OPERATIONS.md, Scale](OPERATIONS.md#bi-scale-wave-b)).

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
| `nodes.<name>` | (none) | Every Java node of the site, name to base URL (`nodes.java-1: http://10.0.0.5:18480`). Each model's shard (its live tables and standing queries) is owned by one node, chosen by consistent hashing over the names; another node forwards subscriptions to the owner (`503 DRS-9184` while the owner cannot be reached) and takes over a lost node's shards. *As built in 2.3.0:* the directory, forwarding and failover are proven with two nodes in one process; the HTTP transport between Java nodes is not built yet, so run one Java node. |

### `drishti.bi.plan` — the logical plan, the result cache and the guards

Every question becomes one logical plan ([BI_DESIGN.md §3.6 and §4](../architecture/BI_DESIGN.md#4-query-architecture)); answers are cached by plan hash,
security signature and data generation (the L1 cache of §6.1: readers with identical effective security share an entry).

| Key | Default | Meaning |
|---|---|---|
| `cache-entries` | `2000` | Answers the result cache keeps; `0` turns it off (the engine's own cache, `drishti.bi.query.cache-*`, still applies). |
| `cache-ttl` | `15m` | How long an answer for the current state is served before it is asked again, even when no data generation moved (a `direct` table's source can change unseen). An as-of answer has no time to live. |
| `cache-idle` | `1h` | An answer nobody asked for this long is dropped. |
| `guards` | `refuse` | `refuse`: a sum across currencies (`DRS-9158`) and a balance added across dates (`DRS-9159`) are refused (wave A); `warn`: answered with a warning, as in W1. |

### `drishti.bi.sql` — Drishti SQL

Read-only SQL over models ([SQL.md](../guides/SQL.md)): `POST /api/v1/bi/sql`, SQL view datasets, models' SQL tests.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: `/bi/sql` answers `403 DRS-9006`, and SQL view datasets and SQL tests cannot run. |
| `max-leaves` | `8` | Model queries one composite statement may ask (its `SELECT`s over the model's tables); more is `DRS-9179`. |
| `composite-memory` | `256MB` | Memory of the in-memory engine a composite statement's outer query runs in (it reads the leaves' answers and nothing else). |
| `composite-timeout` | `30s` | Longest the outer query of a composite statement may run. |
| `max-rows` | `100000` | Most rows a composite statement answers (`DRS-9022` past it). An MQ-shaped statement keeps the model query's limits. |

### `drishti.bi.expr` — the expression language

The Drishti expression language (DEL v2, [EXPRESSIONS.md](../guides/EXPRESSIONS.md)): how the old expression syntax is treated, and the parse cache.

| Key | Default | Meaning |
|---|---|---|
| `legacy` | `refuse` | The old expression syntax (`[Measure]`, `&&`, `c ? a : b`): `refuse` (the default since the end of wave A) refuses it with `DRS-9100`, the DEL v2 text as the fix; `warn` reads it with a `DRS-9100` warning in Check, for a site converting its own items (`drishti.py bi migrate --expressions`). An item with `expressions: 2` refuses it whatever this says. A rule written as a YAML boolean (`where: true`) is DEL's `True`. |
| `parse-cache` | `10000` | Parsed expressions kept in memory (keyed by text); `0` turns the cache off. |

Items **saved before** the old syntax was refused are not left broken: at start the server converts them (`drishti.bi.migrate`, below).

### `drishti.bi.migrate` — migrations of stored BI items

What the server does at start with BI items it **stores** (drafts, proposals and the published text of every workspace item; shipped files in
`drishti.bi.dir` are converted with the CLI instead, [CLI_GUIDE.md](../guides/CLI_GUIDE.md#bi-expr-bi-migrate-bi-sql-the-bi-languages)). Every
migration is **idempotent** (a converted item has nothing left to convert, so the second start changes nothing), **audited** (`bi-expressions-migrated`
per item, `bi-migration-run` per run), keeps **the text before as a saved state** of the item's history (*History*, *Restore*), and **never changes an
item silently**: an item it cannot convert keeps its text and is listed in Admin, BI settings (`migrationProblems`), on the item's page and at
`GET /api/v1/bi/migrate/problems` ([TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md#bi-migrations-22-wave-a)).

| Key | Default | Meaning |
|---|---|---|
| `on-start` | `true` (`DRISHTI_BI_MIGRATE_ON_START`) | Run the migrations once at start, before the server takes requests: expressions in the old syntax become DEL v2 ([EXPRESSIONS.md section 10](../guides/EXPRESSIONS.md#10-the-old-syntax-and-the-converter)), then `rachana: 1` datasets become datasets and models and their reports are rebound ([BI_MODEL_SPEC.md 8.3.1](../architecture/BI_MODEL_SPEC.md#831-as-built-22-wave-a-task-6)): the dataset item becomes the model item (same id), each table a new dataset item of the same workspace, owner and audience; a role restricted on one table is never given rows it did not see. A published text that changes becomes the item's next published version (the old version stays in its version list); a draft stays a draft, a proposal stays proposed. `false` leaves stored items as they are: a bi-admin runs the same with `drishti.py bi migrate --expressions [--dry-run]`. |
| `actor` | `system` | The name the saved states, the item's governance events and the audit log give the start-up run (a run from the API or CLI names the bi-admin who ran it). |

### `drishti.distribution`: export and subscriptions

Export of a BI report, page, visual or Sutra view as PDF, PNG, Excel or CSV, and subscriptions (email on a schedule), [DISTRIBUTION.md](../guides/DISTRIBUTION.md). Mail goes through the server's
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

Under `drishti.distribution.statements` (2.6.0, statement pages: [STATEMENTS.md](../guides/STATEMENTS.md)):

| Key | Default | Meaning |
|---|---|---|
| `max-pages` | `500` | Most pages one statement may run to; a longer one is refused (`422 DRS-9038`), never cut |
| `max-rows` | `10000` | Most rows of one table band; past it the band is cut and says so |
| `paper` | `A4` | The paper of a statement whose `statement:` names none (`A4`, `A3`, `A5`, `Letter`, `Legal`) |
| `band-queries` | `2` | How many bands of one statement are asked at the same time (1 to 8), each as the reader, within the query limits |

### `drishti.alerts.measure` — alerts on numbers and board notifications

Alerts on a visual, a dataset measure or a Sutra field, and notifications from board tiles (phase 7, part 2: [DISTRIBUTION.md section 4](../guides/DISTRIBUTION.md#4-alerts-on-numbers)). Every key has a default;
change one in `application.yaml` or with `DRISHTI_ALERTS_MEASURE_*`.

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

Where an alert or a subscription may send besides the inbox, mail and the chat bridges ([DISTRIBUTION.md section 6](../guides/DISTRIBUTION.md#6-webhooks-and-text-messages)). Nothing here is on until an administrator defines it: a person can pick only the **names** of the webhooks defined here, never type a URL, and a text goes only to a number its owner gave. Neither carries a figure
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

Saved states, review, certification, impact analysis and bundles ([BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md)).

| Key | Default | Meaning |
|---|---|---|
| `revisions-keep` | `200` | Saved states (revisions) kept per item; the oldest go first, but a saved state that is a published version is never dropped. Every change of the body is one. |
| `preview-rows` | `20` | Rows of data a reviewer sees in the preview of a dataset or a report under review (at most 500). The reviewer's own row rules and masks apply. |
| `refuse-breaking` | `true` | Refuse (`409 DRS-9040`) publishing a dataset change that removes or renames a field, measure or hierarchy a **published** report uses. A `bi-admin` can publish with `force`. Off: the publish goes ahead and answers with warnings. |
| `revoke-certification` | `true` | Take certification away automatically from a certified dataset that changed incompatibly, and from certified reports broken by such a change; the owner and whoever certified are told. |
| `refuse-failing-tests` | `true` | Refuse (`409 DRS-9043`) publishing or approving a report or dataset whose expected values (`tests:`) fail or cannot run; a `bi-admin` can publish with `force`. Off: Check still shows the results, a publish goes ahead ([REPORTS.md section 17](../guides/REPORTS.md#17-expected-values-tests)). |
| `bundle-max-bytes` | `8388608` | Largest BI bundle an import reads (`422 DRS-9041` above it). |
| `imports-dir` | blank: `<drishti.data.dir>/bi/imports` | Where each import keeps what it replaced, one JSON file per import, so *Roll back this import* can put it back ([BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md#rolling-back-an-import)). Back it up with the identity database. |
| `imports-keep` | `20` | Imports whose replaced states are kept (the oldest go first). |
| `pack-workspace` | `packs` | The shared workspace that holds the BI content of deployed packs (`config/bi` inside a pack archive); made at the first deploy that carries some ([BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md#bi-content-in-a-pack-archive)). |
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

The Pivot tab ([USER_GUIDE.md](../guides/USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)) appears only where a
Sutra (`pivot:` on a table or ladder) or a pack (`pivot:` beside a kind's `columns:`) opts in; these keys bound it.
There is no role power: anyone who may open a kind may pivot and save; promoting a pivot to a Sutra needs `author`,
`drishti.rachana.studio-save` and goes through review. Saved pivots live in the preference store (namespace `pivots`).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_PIVOT_ENABLED`) | Off: no Pivot tab anywhere (the view's table data and search results no longer carry `pivot`), and `/api/v1/views/…/records`, `/api/v1/search/pivot/**` and `/api/v1/me/pivots/**` refuse. |
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
[PANELS.md](../guides/PANELS.md#how-a-panel-behaves).

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

The signal your ETL sends when a batch has landed, its history, and the late-data check; see [DATA_LOADS.md](../guides/DATA_LOADS.md). What a
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

### `drishti.alerts` — alert history

| Key | Default | Meaning |
|---|---|---|
| `keep` | `1000` | Fired alerts kept per user, in the identity database (`drishti_alert`), so they survive restarts. Older ones are pruned as new ones arrive. |

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

Admin → Artifacts ([ARTIFACTS.md](ARTIFACTS.md)) moves what it uninstalls into an archive instead of deleting it. All values are read at start.

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

See [LIVE.md](../architecture/LIVE.md).

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
| `semantics-file` | none | A site file that replaces the bundled semantic hints (`inference/semantics.yaml`). Packs' `config/semantics.yaml` files are tried first. See [INFERENCE.md](../architecture/INFERENCE.md). |

---

## Connector settings, plugin by plugin

These go under `drishti.sources.plugins.<plugin>.settings` (single instance) or
`drishti.sources.connectors.<name>.settings` (named instance). Every plugin takes `source-name`, the name shown
in provenance and health (default: the connector's name, or the plugin's name). Writing a plugin of your own is
described in [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

Every plugin also takes **`stale-after`**: a duration (`15m`, `2h`, `4d`). When the connector has received nothing new
for longer than that, it is *stale*: Admin → Health shows it in amber and turns the overall status `DEGRADED`, and
views of its entities show a banner saying the data may be out of date. Without it a connector is never stale. It
only means something for sources that know when they last got data (Kafka, ActiveMQ, RabbitMQ, Delta, file, feeds,
demo). The packs set it for the trading stream (`DRISHTI_STREAM_STALE_AFTER`, default `15m`) and the daily public
feeds (`4d`, which covers a weekend and a holiday).

```yaml
drishti:
  sources:
    connectors:
      trading-stream:
        settings:
          stale-after: 5m        # trades arrive all day: five quiet minutes means something is wrong
```

### `delta` — Delta Lake

Layout: `<root>/<domain>/<kind>/business_date=yyyy-MM-dd/` holding `(id, doc)` rows.

| Setting | Default | Meaning |
|---|---|---|
| `root` | `./data/delta` (packs use `DRISHTI_DELTA_ROOT`) | Local directory (`C:\lakes\drishti` on Windows), or a URI such as `s3a://bucket/lake`, `s3://…`, `abfs://…`, `gs://…`. |
| `engine` | `DRISHTI_DELTA_ENGINE`, else `native` | Which Delta Kernel engine reads the lake. `native`: no Hadoop; local disk through `java.nio` and S3 (`s3://`, `s3a://`) through the AWS SDK; the one that works on Windows without `winutils.exe`. `hadoop`: Kernel's default engine over Hadoop's file systems; also reads `abfs://`, `gs://` and HDFS. `auto`: `native` on Windows, `hadoop` elsewhere. A `native` connector on an `abfs://` or `gs://` root refuses to start and says to use `hadoop`. Health shows `UP (engine: native)`; the cache stats carry `engine`. See [DELTA_CONNECTOR.md › Engines](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop). |
| `domain` | empty | The data-domain sub-folder (`reference`, `trading`, `legal`). |
| `kinds` | every table found | Comma list. |
| `mode.<kind>` | `snapshot` | `snapshot`: a full copy every business date, read within `lookback-days`. `effective`: a row only when the entity changes, no limit. |
| `lookback-days` | `10` | How far back a snapshot read looks for the latest partition. |
| `id-column` / `doc-column` / `date-column` | `id` / `doc` / `business_date` | Column names. |
| `refresh-seconds` | `10` | How often each table's latest version is checked. |
| `cache-mb` | not set: shared, 5 % of the heap | Whole days kept in memory, by size, for small tables that are not laid out. |
| `layout.<kind>.columns` | none | Document paths stored as columns beside the document (see [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#large-kinds-the-lake-layout)); searches, pick lists, derived kinds and impact read them instead of documents. `layout.<kind>.sort-by`, `file-rows`, `row-group-rows` tell the writers how to lay the table out. |
| `id-map-mb` | not set: shared, 20 % of the heap | Each day's ids and the file each is in, read from the id column alone, kept by size. |
| `doc-cache-mb` | not set: shared, 5 % of the heap | Single documents read recently. |
| `doc-disk-cache.enabled` | `true` | One RocksDB per connector (its own folder, files and compactions; compacted hourly, so expired files go without writes; a cache that cannot open is skipped with a WARN and tried again every 30 s for 10 minutes). A second tier for single documents, on disk (RocksDB) under `doc-disk-cache.dir` (default `<data dir>/cache/<connector>/docs`): a document read once is answered without decompressing its Parquet pages, also after a restart. Keyed by the table version it was read from, so never stale. Measured on 362,804 ENDUR trades: 17 % less CPU per read for documents already read (2026-10-18). |
| `doc-disk-cache.max-gb`, `.ttl`, `.reset-at`, `.zone` | `1`, `12h`, `02:00`, `America/New_York` | Its size cap (the oldest files go first), time limit (entries written longer ago go), and daily clearing. |
| `id-map-disk.enabled`, `id-map-disk.dir` | `true`, `<data dir>/cache/<connector>/idmaps` | Each table date's id map written once per table version and read through a memory map: no heap per id, and after a restart a date's first read needs no scan (3 ms instead of 0.7 s for 362,804 ids, 2026-10-18). Other versions' files go when a new one is written; a purge (Admin → Caches) deletes them. |
| `columns-cache-mb` | not set: shared, 20 % of the heap | A day's promoted columns, read once and kept by size (the newest day is loaded in the background). |
| (memory budget) | — | Delta connectors that do not set `cache-mb`, `id-map-mb`, `columns-cache-mb` or `doc-cache-mb` share one cache of each, sized from `-Xmx` (5 %, 20 %, 20 %, 5 %: half the heap in all, however many connectors run); a connector that sets one keeps a cache of its own of that size. Admin → Caches shows each shared cache's size against its cap. |
| `max-concurrent-reads` | `16` | Single-document reads at once (each decodes one row group). |
| `max-queued-reads` | `4096` | Single-document reads that may wait for one of those slots. More are refused at once (`DRS-1003 … reads already wait … the server is busy, try again`), and a waiting read whose caller has stopped waiting (its time-out, `drishti.sources.fetch-timeout`) leaves the queue and never reads. |
| `max-load-rows` | `200000` | A table without columns has a whole day loaded for reverse lookups only up to this many rows. |
| `warm-dates` | `3` | After start, in the background, each table's newest N dates get their id map and one document read, so the first read of a recent past date is not the slow one. `0` turns it off. |
| `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`, `s3.path-style` | empty, empty, empty, empty, `true` with an endpoint | Shorthands for an `s3a://` (or, native engine, `s3://`) root; both engines read them. Without keys the AWS chain is used (environment, profile, instance role). |
| `s3.read-block-kb` | `1024` | Native engine: the smallest ranged GET (a Parquet footer, a deletion vector); larger reads are one GET of their range. |
| `hadoop.<key>` | none | Passed to Hadoop as `<key>` (any `fs.s3a.*` option); with the native engine, Kernel's options (`delta.kernel.default.parquet.reader.batch-size`, …) are read from here or from `kernel.<key>`. |

```yaml
drishti:
  sources:
    connectors:
      risk-lake:
        plugin: delta
        settings:
          root: s3a://bank-lake/drishti
          domain: risk
          s3.endpoint: https://minio.internal:9000
          s3.access-key: ${LAKE_ACCESS_KEY}
          s3.secret-key: ${LAKE_SECRET_KEY}
```

TLS to an S3-compatible store on a private CA: the shared [`tls.*`](#tls--tls-for-connectors) settings with `s3.endpoint: https://...`, read by the native engine ([DELTA_CONNECTOR.md, Security](../connectors/DELTA_CONNECTOR.md#security-tls-and-credentials)); refused with `engine: hadoop`.

### `jdbc` — a database

The JDBC driver jar goes on the class path or in `plugin-dir`.

| Setting | Default | Meaning |
|---|---|---|
| `url`, `user`, `password` | empty | Connection. Keep the password in the environment. |
| `pool-size` | `20` (`drishti.db.pool.size`) | Connections the pool opens at most; the rest of the [pool settings](#drishtidbpool--database-connection-pools) (`pool-min-idle`, `pool-idle-timeout`, `pool-max-lifetime`, `pool-connection-timeout`, `pool-leak-threshold`, `statement-timeout`) are read here too. A broken connection is replaced; the connector starts even if the database is down. |
| `query.<kind>` | none | Query mode: SQL with one `?` for the id, or named `:id` and `:asOf` (the business date). The first row is the document: each column a field, or a column named `json` as the whole document; optional `generation` and `business_date` columns. A `json`/`jsonb` column becomes nested data. |
| `query.<kind>.<part>`, `part-shape.<kind>.<part>` | none, `list` | Query mode: more of the entity from other tables; its rows become the field `<part>` (a list, or with `object` the first row). Parts run at once with the entity's query. |
| `ids.<kind>` | none | Query mode: a day's ids (and optionally a title) for type-ahead, read every `refresh-seconds`. |
| `columns.<kind>` | none | Query mode: a day's id and promoted fields (`:asOf`), for searches, pick lists, derived kinds, impact and reverse lookups without documents. |
| `reverse.<kind>` | none | Query mode: the ids of the kind that reference `:target`. All query-mode keys: [JDBC_QUERIES.md](../connectors/JDBC_QUERIES.md). |
| `json-columns` | none | Query mode: comma list of text columns that hold JSON (`legs, extras`), parsed into nested data like a `json`/`jsonb` column; a cell that is not JSON stays text. |
| `table` | empty | Table mode: every kind of a domain in one PostgreSQL table of `(kind, id, business_date, doc jsonb, <promoted columns>)` rows, partitioned by month ([POSTGRES_CONNECTOR.md](../connectors/POSTGRES_CONNECTOR.md)). |
| `mode.<kind>` | `snapshot` | Table mode: `snapshot` or `effective`. |
| `kinds`, `kind-column`, `id-column`, `doc-column`, `date-column`, `lookback-days` | the kinds the table holds, `kind`, `id`, `doc`, `business_date`, `10` | Table mode column names and look-back. |
| `layout.<kind>.columns` | none | Table mode: the paths the pack promotes, each a column of the table, read by searches, pick lists, derived kinds, impact and reverse lookups instead of documents. |
| `refresh-seconds` | `60` | Table mode: how often each kind's dates and the newest day's ids (type-ahead, kept in memory) are re-read. |
| `scan-threads` | `4` | Table mode: id ranges of a day read at once (on as many pooled connections) when reading a day's columns. |
| `columns-cache-mb`, `columns-seconds` | `1024`, `300` | Table mode: memory for days of promoted columns, and how long a day is kept (a new load clears them). |
| `max-load-rows`, `reverse-index` | `200000`, `true` | Table mode: the most documents a reverse lookup reads for a kind without promoted link columns; `false` turns reverse lookups off. |

```yaml
drishti:
  sources:
    plugins:
      jdbc:
        enabled: true
        settings:
          url: jdbc:postgresql://db:5432/trades
          user: drishti
          password: ${TRADES_DB_PASSWORD}
          query.trade: >-
            SELECT json FROM trades WHERE trade_id = :id AND business_date =
            (SELECT MAX(business_date) FROM trades WHERE trade_id = :id AND business_date <= :asOf)
```

TLS for `jdbc:postgresql://`: the shared [`tls.*`](#tls--tls-for-connectors) settings (`tls.enabled: true` plus a CA, optionally a client certificate), handed to the driver as `sslmode=verify-full` (or `require` with `tls.verify-hostname: false`); the mapping, and the options for MySQL, SQL Server and Oracle, are in [POSTGRES_CONNECTOR.md, Security](../connectors/POSTGRES_CONNECTOR.md#security-tls-and-credentials).

### `file` — JSON or CSV files on disk

Layout: `<root>/<kind>/<id>.json` (or `.csv`), and dated `<root>/<yyyy-MM-dd>/<kind>/<id>.json`.

| Setting | Default | Meaning |
|---|---|---|
| `root` | `./data/feeds` in the bundled file (`DRISHTI_FEEDS`) | The folder. |
| `rescan-seconds` | `30` | How often the search index and dated folders are refreshed. |
| `lookback-days` | `10` | How far back a dated read looks. |

### `rest` — an HTTP/JSON service

| Setting | Default | Meaning |
|---|---|---|
| `base-url` | required | `https://risk.internal/api` |
| `path` | `/{kind}/{id}` | Appended to the base URL. |
| `kinds` | empty = any | Comma list. |
| `timeout-ms` | `2000` | Both the connect timeout and the request timeout. |
| `generation-header` | `ETag` | A response header holding a numeric version; otherwise the fetch time. |
| `header.<Name>` | none | Request headers, e.g. `header.Authorization: Bearer ${SERVICE_TOKEN}`. |
| `source-name` | `rest` (a connector: its name) | The name shown in provenance and Health. |

A 404 answer means "not held here". Any other status below 400 is the document (`204`, other `2xx` and `3xx` bodies
are parsed; redirects are not followed; an empty body is an empty document, found); 400 or more is a failed read.
Full detail: [REST_CONNECTOR.md](../connectors/REST_CONNECTOR.md).

TLS: an `https://` `base-url` with the shared [`tls.*`](#tls--tls-for-connectors) settings for a private CA or mutual TLS ([REST_CONNECTOR.md, TLS](../connectors/REST_CONNECTOR.md#tls-for-the-rest-connector)).
Proxy: a `proxy:` block, or the site default [`drishti.http.proxy`](#drishtihttpproxy--the-sites-outbound-http-proxy) ([REST_CONNECTOR.md, Outbound proxy](../connectors/REST_CONNECTOR.md#outbound-proxy)).

### `tls.*` — TLS for connectors

Every connector that connects to a server over the network reads its TLS settings from these keys (Kafka, ActiveMQ,
RabbitMQ, Redis, REST, the feeds, S3, MongoDB, PostgreSQL, Aerospike, Cassandra, Delta and Iceberg; the matrix is in [TLS.md](../connectors/TLS.md#connector-matrix)). Every key is optional. Passwords are `${ENV}` placeholders or `…-password-file` paths,
never written in a document. Full reference, with every source form, the openssl/keytool commands and rotation:
[TLS.md](../connectors/TLS.md).

| Setting | Default | Meaning |
|---|---|---|
| `tls.enabled` | `false` | Use TLS (a connector whose address says so, `amqps://`, `ssl://`, Kafka `security.protocol: SSL`, uses it without this; `true` against a plain address is a start-up error). |
| `tls.ca-file` | none | Trust: a PEM file with one or many CA certificates (or the PEM text). |
| `tls.truststore`, `tls.truststore-password`, `tls.truststore-type` | none, none, detected | Trust: a PKCS12 or JKS truststore. |
| `tls.trust-jvm-default` | `true` unless a CA or truststore is given | Also trust the JVM's authorities (merged with the CA or store). |
| `tls.cert-file`, `tls.key-file`, `tls.key-password` | none | Client identity (mutual TLS) from PEM: certificate chain, private key (PKCS#8, PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8), key password. |
| `tls.keystore`, `tls.keystore-password`, `tls.keystore-type`, `tls.key-alias` | none, none, detected, the only key | Client identity from a PKCS12 or JKS keystore (use this or the PEM pair). |
| `tls.protocols` | `TLSv1.3,TLSv1.2` | Protocol versions offered. |
| `tls.cipher-suites` | the JVM's | Cipher suites. |
| `tls.verify-hostname` | `true` | Check the server's name against its certificate; `false` warns at every start. |
| `tls.insecure-trust-all` | `false` | Accept any certificate; refused unless the environment has `DRISHTI_ALLOW_INSECURE_TLS=true` (development only). |

Each `…-password` has a `…-password-file` twin. A wrong file or password stops the connector at start with the setting, the
file and the reason; a certificate within 30 days of expiry adds a sentence to the source's health text.

### `kafka` — a live stream

| Setting | Default | Meaning |
|---|---|---|
| `bootstrap-servers` | `localhost:9092` | Brokers. |
| `topics` | empty | Comma list. |
| `kind`, `id-field` | none, `id` | Mapped messages: the whole value is the document of this kind, keyed by this field. Per topic: `kind.<topic>`, `id-field.<topic>`. Without them each message is an envelope `{"kind","id","doc"}`. |
| `mode` | `state` | `state`: the stream is the store (memory: about 0.4–0.5 GB per million entities for the index and type-ahead, estimated, plus `cache-mb`). `ticks`: another store serves documents and the stream only ticks open views; it keeps nothing, but still replays the topic from the beginning at start. |
| `cache-mb` | `256` | Recently read documents kept in memory. |
| `search` | `true` | Keep identifiers for type-ahead. |
| `poll-ms` | `200` | Consumer poll interval. |
| `client.<property>` | none | Any Kafka consumer property, e.g. `client.security.protocol: SASL_SSL`; applied last, so it wins over the security settings below. |
| `flavour` | `apache` | `confluent` is the Confluent Cloud preset (`SASL_SSL`, `PLAIN` with `api-key` / `api-secret`, `client.dns.lookup=use_all_dns_ips`, tuned timeouts). |
| `security.protocol` | inferred | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`; with the [`tls.*`](#tls--tls-for-connectors) settings for the TLS part. |
| `sasl.mechanism` | none | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `OAUTHBEARER` or `GSSAPI`. |
| `sasl.username`, `sasl.password` (or `-file`) | none | PLAIN and SCRAM credentials (`api-key`, `api-secret` on Confluent Cloud). |
| `sasl.oauth.token-endpoint`, `sasl.oauth.client-id`, `sasl.oauth.client-secret`, `sasl.oauth.scope` | none | OAUTHBEARER, OIDC client credentials. |
| `sasl.kerberos.principal`, `sasl.kerberos.keytab`, `sasl.kerberos.use-ticket-cache`, `sasl.kerberos.service-name`, `sasl.kerberos.krb5-conf` | none, none, `false`, `kafka`, the JVM's | GSSAPI (Kerberos). |
| `value-format` | `string`, or `confluent` with a registry | `confluent`: also read the Confluent wire format (JSON Schema, Avro). |
| `schema-registry.url`, `schema-registry.basic-auth`, `schema-registry.bearer-token`, `schema-registry.timeout-ms`, `schema-registry.tls.*` | none, none, none, `10000`, the JVM's trust | The Schema Registry the Avro schema ids are resolved against. |
| `disk-cache.enabled` | `false` | Also write every message to a RocksDB store on local disk. |
| `disk-cache.root` / `disk-cache.dir` | `./data/cache` / `<root>/<connector>` | Where the store lives. |
| `disk-cache.max-gb` | `10` | Size budget. |
| `disk-cache.reset-at` / `disk-cache.zone` | `02:00` / `America/New_York` | Nightly clearing time. |
| `source-name` | `kafka` (a connector: its name) | The name shown in provenance and Health. |

Only a null value (a tombstone), or an envelope with `"doc": null`, deletes; an empty value does not. A delete is
pushed to open views (they say the entity was deleted, and when) and takes the id out of type-ahead at once. Full detail:
[KAFKA_CONNECTOR.md](../connectors/KAFKA_CONNECTOR.md); security in
[its section 11](../connectors/KAFKA_CONNECTOR.md#11-security-tls-sasl-and-confluent).

### `activemq` and `rabbitmq` — message queues

Both keep the latest document of every entity in their own RocksDB store, because a queue delivers each
message once.

| Setting | Plugin | Default | Meaning |
|---|---|---|---|
| `broker-url` | activemq | `failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=30000` | The failover transport reconnects by itself. |
| `max-redeliveries` | activemq | `-1` | Redeliveries of a message the state store could not keep before the broker dead-letters it; `-1` is without limit. |
| `destinations` | activemq | empty | `queue:trades,topic:quotes`; a bare name is a queue. Topics use durable subscriptions, which stay on the broker after a topic is removed here. Each idle destination costs a 50 ms wait per polling loop. |
| `user`, `password`, `client-id` | activemq | none, none, `drishti-<source-name>` | |
| `uri` | rabbitmq | `amqp://guest:guest@localhost:5672/%2f` | `amqps://…:5671/…` connects over TLS. |
| `auth-mechanism` | rabbitmq | `plain` | `external` logs in with the client certificate (needs `amqps://` and a client identity). |
| `tls.*` | both | none | TLS for `ssl://` (ActiveMQ) and `amqps://` (RabbitMQ) endpoints: [`tls.*`](#tls--tls-for-connectors). |
| `queues` | rabbitmq | empty | `trades,quotes`, all on one channel. Empty: `UP`, consuming nothing. |
| `declare` | rabbitmq | `true` | Declare the queues durable. |
| `bind.<queue>` | rabbitmq | none | `exchange:routing.key` to bind a declared queue. |
| `prefetch`, `heartbeat-seconds`, `recovery-interval-ms` | rabbitmq | `100`, `20`, `2000` | |
| `kind`, `kind.<destination>`, `id-field`, `id-field.<destination>` | both | none, `id` | As for Kafka. |
| `cache-mb` | both | `128` | Memory cache. |
| `state.root` / `state.dir` | both | `./data/state` / `<root>/<source-name>` | The RocksDB store, one per connector. Back it up: it survives restarts and is never cleared unless configured. It keeps the latest value of each entity (level compaction), so its size follows the entities held, not the messages. |
| `state.durability` | both | `sync` | How a write is kept before the message is acknowledged. `sync`: the write-ahead log is synced on every write, so neither a crash nor a power loss loses an acknowledged message (one disk sync per message: typically thousands a second on an SSD). `wal`: the log is not synced (survives a crash of the process; a power loss can lose the last moments). `none`: no log (fastest; a crash can lose up to the 32 MB write buffer). Any other value fails the connector's start. |
| `state.max-gb` | both | `10` | This connector's disk budget (decimal allowed), compared with the store's size (`stateMb`: table files plus write buffers). Each connector has its own. |
| `state.when-full` | both | `evict-oldest` | Past the budget. `evict-oldest`: the entities written longest ago are removed until the store is under 90% of the budget, then it is compacted; each eviction is logged at WARN and counted (`evicted`). `warn`: nothing is removed; health reads `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)`. Any other value stops the connector at start (`failedToStart`), so a typo never evicts. |
| `state.check-seconds` | both | `60` | How often the store's size is checked against the budget. |
| `state.reset-at` / `state.zone` | both | `never` / `America/New_York` | Optional daily clearing time; clears the disk store only. |
| `source-name` | both | `activemq` / `rabbitmq` (a connector: its name) | The name shown in provenance and Health; names the default state folder. |

A message is acknowledged only after the state store has kept it. A message the store cannot keep (disk full, an I/O
error) is not acknowledged: RabbitMQ requeues it and ActiveMQ redelivers it a second later (without limit: `max-redeliveries`, `-1`; a number
sends the message to `ActiveMQ.DLQ` after that many), and health reads
`DOWN: <reason> (messages are not acknowledged and come again)` until one is kept again. A message the connector
cannot read (not JSON, no kind or id) is acknowledged, counted in `rejected` and dropped: there is no dead-lettering.
Cache figures add `durability`, `budgetMb` and `evicted`. Full detail:
[ACTIVEMQ_CONNECTOR.md](../connectors/ACTIVEMQ_CONNECTOR.md), [RABBITMQ_CONNECTOR.md](../connectors/RABBITMQ_CONNECTOR.md).

### `s3` — documents in S3 or an S3-compatible store

Layout as the file connector's per-entity form: `<prefix><kind>/<id>.json` and `<prefix><yyyy-MM-dd>/<kind>/<id>.json`.

| Setting | Default | Meaning |
|---|---|---|
| `bucket` | required | |
| `prefix` | empty | e.g. `risk/` |
| `region` | `us-east-1` | |
| `endpoint` | empty | For S3-compatible stores (MinIO, Ceph); path-style addressing then (`path-style`, `true`). |
| `path-style` | `true` | Path-style addressing, used with `endpoint`. |
| `access-key`, `secret-key` | empty | Static credentials, with no session token (temporary STS keys do not work here); otherwise the AWS credential chain. |
| `rescan-seconds` | `60` | How often identifiers and dates are listed. |
| `cache-seconds`, `cache-entries` | `30`, `10000` | Read cache (misses are cached too). |
| `lookback-days` | `10` | How far back an older date folder may answer for an entity missing from newer ones. |
| `source-name` | `s3` (a connector: its name) | The name shown in provenance and Health. |

There is no `mode.<kind>`: a picked date reads the newest date folder on or before it that holds the entity (within
`lookback-days`), then the undated object. A folder named like a date that is not a real one is ignored. Without
`kinds`, the kinds served are those of the last successful listing, and an empty listing serves every kind. Connect
(5 s) and socket (20 s) timeouts are fixed in the code. Full detail: [S3_CONNECTOR.md](../connectors/S3_CONNECTOR.md).

TLS: an `https://` `endpoint` with the shared [`tls.*`](#tls--tls-for-connectors) settings for a private CA or a client certificate (`tls.verify-hostname: false` is refused here; [S3_CONNECTOR.md, TLS](../connectors/S3_CONNECTOR.md#tls-for-the-s3-connector)).

### `iceberg` — Apache Iceberg

Path-based tables or a REST catalog (Polaris, Snowflake Open Catalog, Glue), in the same layout as Delta Lake. Design,
loading and maintenance: [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md); the two engines: its
[section 17](../connectors/ICEBERG_CONNECTOR.md#17-engines-native-and-library).

| Setting | Default | Meaning |
|---|---|---|
| `engine` | `native` (`DRISHTI_ICEBERG_ENGINE`) | `native`: drishti-iceberg, no Hadoop (local disk and S3; Windows; left to the default, a root it cannot reach is read by the library); `library`: Apache Iceberg Java over Hadoop (also Azure, Google Cloud Storage, HDFS); `auto`: native on Windows, library elsewhere |
| `catalog` | `hadoop` | `hadoop` (path-based tables) or `rest` |
| `root` | none (`root` or `uri` is required; the plugin stays idle without one; the `iceberg` profile sets `./data/iceberg`) | `hadoop`: the lake, a folder or `s3a://…` (`abfs://`, `gs://`, `hdfs://`) |
| `domain` | empty | the data domain: the folder under `root`, or the REST namespace |
| `namespace` | the domain | `rest`: the namespace (dots separate levels) |
| `uri` | none | `rest`: the catalog's URI |
| `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`, `prefix`, `io-impl` | none | `rest`: passed to the catalog |
| `catalog.<key>` | — | `rest`: any other catalog property (`catalog.rest.sigv4-enabled` …) |
| `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`, `s3.path-style` | — | object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) |
| `hadoop.<key>` | — | `library`: passed to Hadoop as `<key>` |
| `metadata-location.<kind>` | none | `native`: the kind's table named by its metadata file, read-only |
| `relocate` | none | `native`: `<old prefix> -> <new prefix>; …` reads a table copied elsewhere |
| `vended-credentials` | `true` | `native`, `rest`: ask the catalog for each table's own S3 credentials |
| `lz4-decoder` | `safe` | `native`: the LZ4 page decoder, `safe` or `fast` |
| `kinds` | every table found (new ones too) | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | promoted paths ([ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md#5-declaring-the-layout-in-a-pack)) |
| `refresh-seconds` | `10` | how often each table's current snapshot is checked |
| `layout-cache` | `64` | planned snapshots kept |
| `id-map-mb` | `1024` | memory for days' id maps |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `doc-cache-mb` | `256` | memory for recently read documents |
| `cache-mb` | `512` | memory for small days read whole for reverse lookups |
| `max-concurrent-reads` | `16` | single-document reads at once |
| `max-queued-reads` | `4096` | single-document reads that may wait for a slot; more are refused at once, and a read whose caller has gone leaves the queue |
| `max-load-rows` | `200000` | the largest day read whole for reverse lookups when a table has no promoted columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | the connector's name | the name shown in provenance and Health |
| `stale-after` | none | warn when no new data arrived for this long (engine setting) |

TLS for a REST catalog's object store on a private CA: the shared [`tls.*`](#tls--tls-for-connectors) settings with `s3.endpoint: https://...` ([ICEBERG_CONNECTOR.md, Security](../connectors/ICEBERG_CONNECTOR.md#security-tls-and-credentials)); on the `library` engine refused with `catalog: hadoop`, on `native` accepted for path-based tables too.

### `iceberg-external` — Iceberg tables Drishti does not write

Tables of Spark, Flink, Trino, Snowflake, Glue, dbt or pyiceberg, in their own layout, read-only through drishti-iceberg: rows as
documents, ids from identifier fields or columns, dates from a column or the partition spec, every column searchable
([ICEBERG_EXTERNAL_CONNECTOR.md](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md)).

| Setting | Default | Meaning |
|---|---|---|
| the catalog's (`catalog`, `root`, `uri`, `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`, `prefix`, `catalog.<key>`, `metadata-location.<kind>`, `relocate`, `vended-credentials`, `s3.*`, `tls.*`, `proxy.*`) | as `iceberg` | where the tables are |
| `engine` | `auto` | `native` or `auto` (both drishti-iceberg); `library` is refused |
| `tables.<kind>.table` | the kind | `namespace.table` |
| `tables.<kind>.id`, `.id-separator` | the identifier fields, `:` | the id columns (several joined) |
| `tables.<kind>.date`, `.date-from`, `.date-zone` | the partition spec's date, none, `UTC` | the business date: a date column, or a timestamp's `day` in a zone; none: current only |
| `tables.<kind>.mode` | `snapshot` | `snapshot` or `effective` |
| `tables.<kind>.document` | `row` | `row`, or `column:<name>` (a JSON column) |
| `tables.<kind>.columns.exclude`, `.columns.rename.<column>`, `.links.<column>`, `.depth` | none, none, none, `2` | columns left out, renamed, naming other kinds, nesting searched |
| `tables`, `namespace` | none | `tables: auto` serves every table of `namespace` |
| `decimals`, `binary` | `number`, `base64` | `string` keeps decimals' precision; `omit` leaves binary out |
| `id-index-mb`, `id-index-warn-rows`, `max-index-rows` | `1024`, `5000000`, `10000000` | the id index of unsorted tables; the Health hint; the rows type-ahead and columns take |
| `refresh-seconds`, `lookback-days`, `doc-cache-mb`, `columns-cache-mb`, `max-concurrent-reads`, `source-name` | `10`, `10`, `256`, `1024`, `16`, `iceberg-external` | as `iceberg` |

### `duckdb` — one embedded DuckDB file

Every data domain in one DuckDB file (a schema per domain, `<domain>.entities`), read in-process with no database
server; a load writes a new file and renames it over the old one while the server keeps answering. Design, loading,
sizing and measurements: [DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `path` | required | the DuckDB file; connectors naming the same path share one read-only DuckDB instance |
| `table` | required | `schema.entities`, the data domain this connector serves |
| `memory-limit` | `1GB` | DuckDB's memory limit for the shared instance (native memory, outside the Java heap); the first connector on a file sets it |
| `threads` | every core | DuckDB's threads for the shared instance; the first connector on a file sets it |
| `pool-size` | `4` | reads of this connector at once; at least `scan-threads` plus the concurrent reads you expect. DuckDB is an embedded, in-process file, so there are no connections to pool: this is a limit on reads, not a [connection pool](#drishtidbpool--database-connection-pools), and `drishti.db.pool.*` does not apply |
| `kinds` | the kinds the file holds | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | promoted paths ([DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md#3-declaring-the-promoted-columns)) |
| `refresh-seconds` | `10` | how often the file is checked for a new load (one `stat` when nothing changed) |
| `scan-threads` | `4` | id ranges of a day read at once |
| `columns-cache-mb` | `1024` | memory for days of promoted columns |
| `columns-seconds` | `300` | how long a day's columns are kept (a new file clears them at once) |
| `max-load-rows` | `200000` | the most documents a reverse lookup reads for a kind without promoted link columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | `duckdb` (a connector: its name) | the name shown in provenance and Health |

Retention is the loader's `--keep-days N` (`tools/load-duckdb.sh`); the connector has no retention setting.

### `mongodb`

A document per entity per business day, in a collection per data domain. Design, sizing and loader options:
[MONGODB_CONNECTOR.md](../connectors/MONGODB_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `uri` | none (required; the plugin stays idle without it; the `mongodb` profile sets `mongodb://localhost:27017`) | connection string: hosts, replica set, credentials, TLS |
| `database` | `drishti` | the database |
| `collection` | the connector's `domain`, else `drishti` | the domain's collection (`<collection>_columns` beside it) |
| `read-preference` | `primary` | `primary`, `primaryPreferred`, `secondary`, `secondaryPreferred` or `nearest` |
| `kinds`, `mode.<kind>`, `lookback-days` | what the collection holds, `snapshot`, `10` | As for Delta. |
| `layout.<kind>.columns` | none | the promoted fields |
| `refresh-seconds` | `60` | how often the kinds' dates and the ids are re-read |
| `read-threads`, `heavy-reads`, `batch-size` | `8`, `2`, `5000` | id ranges read at once for a day's columns; day reads at once; documents per cursor batch |
| `columns-cache-mb`, `columns-seconds` | `1024`, `300` | memory for days' column sets; after this a day is re-read in the background |
| `max-load-rows`, `reverse-index` | `200000`, `true` | the most documents a reverse lookup reads without promoted fields; `false` turns reverse lookups off |
| `connect-timeout-ms` | `3000` | connection and server-selection timeout |
| `pool-size` and the other `pool-*` keys, `statement-timeout` | `20` ... | The driver's connection pool: `maxPoolSize`, `minPoolSize`, idle and life times, `maxWaitTime`, socket read timeout; see [`drishti.db.pool`](#drishtidbpool--database-connection-pools). |

TLS and certificate log-in: `tls.enabled: true` (or `tls=true` in the `uri`) with the shared [`tls.*`](#tls--tls-for-connectors) settings, and `auth-mechanism: x509` (optionally `x509-user`) to log in with the client certificate ([MONGODB_CONNECTOR.md, Security](../connectors/MONGODB_CONNECTOR.md#security-tls-certificate-log-in-and-credentials)).

### `redis`

Today and recent days in memory, with live updates; history behind it in Delta Lake. Design, sizing and every option:
[REDIS_CONNECTOR.md](../connectors/REDIS_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `uri` | none (required; the plugin stays idle without it) | `redis://host:6379`; `rediss://` for TLS; credentials in the URI; several comma-separated for Cluster seeds |
| `cluster` | `false` | Redis Cluster |
| `user`, `password` | none | credentials (override the URI's) |
| `domain` | `drishti` | the data domain: the key prefix |
| `kinds`, `mode.<kind>`, `lookback-days` | what `<domain>:kinds` lists, `snapshot`, `10` | As for Delta. |
| `layout.<kind>.columns` | none | the promoted fields, read from each day's column hash |
| `refresh-seconds` | `60` | how often the kinds' days and the ids are re-read |
| `columns-cache-mb`, `heavy-reads` | `1024`, `2` | memory for days' column sets; days read at once |
| `max-load-rows`, `reverse-index` | `200000`, `true` | the most documents a reverse lookup reads without promoted fields; `false` turns reverse lookups off |
| `live` | `true` | subscribe to `<domain>:changes` and push changed entities to open views |
| `timeout-ms` | `5000` | connect and command timeout |
| `pool-size`, `pool-min-idle`, `pool-idle-timeout`, `pool-leak-threshold`, `pool-pipeline-depth` | `20`, `2`, `10m`, `60s`, `32` | The connection pool: Lettuce connections are multiplexed, so one carries many commands; another is opened only when every open one has `pool-pipeline-depth` commands in flight, up to `pool-size`, and extra idle ones are closed. See [`drishti.db.pool`](#drishtidbpool--database-connection-pools). |

TLS: a `rediss://` `uri` with the shared [`tls.*`](#tls--tls-for-connectors) settings for a private CA or a client certificate; the Lettuce `verifyPeer` URI option still works, deprecated ([REDIS_CONNECTOR.md, Security](../connectors/REDIS_CONNECTOR.md#security-tls-and-credentials)).

### `aerospike`

A data domain is three sets: `<set>` (a record per entity per business date, key `kind/id/yyyyMMdd`, bins `kind`,
`id`, `date`, `doc` and the promoted fields), `<set>_ix` (a record per entity, key `kind/id`, bin `dates`) and
`<set>_kinds` (a record per kind, bin `dates`). See the [connector guide](../connectors/AEROSPIKE_CONNECTOR.md#walk-through-step-by-step)
and [AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `hosts` | `localhost:3000` | Seed hosts, `host:port`, comma-separated. |
| `namespace` | `test` | |
| `set` | the connector's `domain`, else `drishti` | The domain's set; the index and kinds sets add `_ix` and `_kinds`. |
| `kinds` | what the kinds set lists | Kinds served. |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective`, as for Delta. |
| `lookback-days` | `10` | How far back a snapshot kind's newest date may fall. |
| `refresh-seconds` | `60` | How often the kinds' dates and the ids (the index set) are re-read. |
| `reverse-index` | `true` | `false` turns reverse lookups off. |
| `layout.<kind>.columns` | none | Fields promoted to bins (usually from the pack); searches, pick lists, derived kinds, impact and reverse lookups read a day's bins instead of its documents. |
| `scan-threads` | `8` | Partition ranges scanned at once when a day is scanned. |
| `columns-cache-mb` | `1024` | Memory for days of promoted bins. |
| `columns-seconds` | `300` | A day's bins are read again after this. |
| `max-load-rows` | `200000` | Record limit of a reverse-lookup scan over documents (kinds without promoted bins). |
| `connect-timeout-ms` | `3000` | |
| `user`, `password` | none | Security-enabled clusters. |
| `pool-size`, `pool-min-idle`, `pool-idle-timeout`, `statement-timeout` | `20`, `2`, `10m`, `5m` | The client's connection pool, **per node**: `maxConnsPerNode`, `minConnsPerNode`, `maxSocketIdle`, read and write `totalTimeout`. See [`drishti.db.pool`](#drishtidbpool--database-connection-pools). |
| `source-name` | `aerospike` (a connector: its name) | Provenance source. |

Retention is the records' time to live, set by the loader (`tools/load-aerospike.sh --ttl-days N`); the connector
has no retention setting.

TLS and certificate log-in: `tls.enabled: true` with the shared [`tls.*`](#tls--tls-for-connectors) settings, `tls-name` (the name the server certificate carries; default each host's name), `auth-mode: pki` to log in with the client certificate. The server's TLS is Enterprise Edition ([AEROSPIKE_CONNECTOR.md, Security](../connectors/AEROSPIKE_CONNECTOR.md#security-tls-certificate-log-in-and-credentials)).

### `cassandra`

Apache Cassandra 4 and 5, ScyllaDB, DataStax Astra (Amazon Keyspaces through AWS's SigV4 plug-in). A data domain is a keyspace: a table
per kind (`<kind>`: a partition per business day, generation and bucket, keyed by `id`; columns `doc` and the promoted fields), `entity_days`
(the days each entity has) and `kind_days` (each kind's days and the generation readers follow). The loader writes them
(`tools/load-cassandra.sh`); see [CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `contact-points` | required unless `secure-connect-bundle` | `host:port` list, comma-separated (port `9042` when left out). |
| `local-datacenter` | required unless `secure-connect-bundle` | The data centre the driver prefers (`datacenter1` on a single node). |
| `keyspace` | the connector's `domain`, else `drishti` | |
| `kinds` | the keyspace's tables made by the loader | Kinds served. |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective`, as for Delta. |
| `layout.<kind>.columns` | none | Promoted fields (usually from the pack): searches, pick lists, derived kinds, impact and reverse lookups read a day's columns instead of its documents. A path the table has no column for is read from documents. |
| `consistency` | `LOCAL_QUORUM` | Read consistency; `LOCAL_ONE` for speed where staleness is acceptable. |
| `lookback-days` | `10` | How far back a snapshot kind's newest day may fall. |
| `refresh-seconds` | `60` | How often `kind_days` (the catalogue) is re-read; a day's ids are re-read when its generation changes. |
| `page-size`, `scan-threads` | `5000`, `8` | Rows per page and partitions read at once when a day's columns or documents are read. |
| `columns-cache-mb`, `doc-cache-mb` | `1024`, `64` | Memory for days of promoted columns and for documents. |
| `max-load-rows` | `200000` | Documents read at most by a reverse lookup of a kind without promoted columns (and ids of an effective kind). |
| `reverse-index` | `true` | `false` turns reverse lookups off. |
| `connections-per-node`, `max-concurrent-reads` | `2`, `256` | The driver's pool (each connection carries many requests) and the requests in flight at once; more wait in the driver's queue. |
| `request-timeout-ms`, `connect-timeout-ms` | `5000`, `5000` | Per statement; connecting (a cluster down is retried in the background and Health says DOWN). |
| `speculative-executions`, `speculative-delay-ms` | `0`, `100` | Extra copies of a slow read to other replicas (off by default). |
| `username`, `password` | none | Password log-in; `password` is `${ENV}` or `file:`. |
| `secure-connect-bundle`, `token` | none | DataStax Astra: the bundle `.zip` and the application token. |
| `sigv4`, `sigv4-region` | `false`, `AWS_REGION` | Amazon Keyspaces: SigV4 log-in; AWS's plug-in jar goes in the plugin folder. |
| `source-name` | `cassandra` (a connector: its name) | Provenance source. |

Retention is the loader's: `--ttl-days N` (cells expire; new tables get one-day time windows) or `--keep-days N` (whole partitions
dropped). TLS: `tls.enabled: true` with the shared [`tls.*`](#tls--tls-for-connectors) settings. `proxy.*` is refused: the native
protocol is TCP.

### `feed` — public data feeds

One connector per feed (the market-data pack declares them, all off by default).

| Setting | Default | Meaning |
|---|---|---|
| `feed` | required | `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury`, `fred` |
| `refresh-minutes` | `60` | |
| `timeout-seconds` | `20` | Request timeout. The connect timeout is 10 s, fixed in the code. |
| `url` | the feed's own | Override; `file:` URLs are read directly. |
| `api-key`, `series` | empty, `DGS10,DFF` | FRED only. |
| `user-agent` | `public-data-feed-connector` | The `User-Agent` header; the market-data pack sets `<product> public data feed connector`. |
| `source-name` | the feed's name (a connector: its name) | The name shown in provenance and Health. |

The history for picked dates is the window the last fetch returned. A failed fetch, or one that yields no series at all,
keeps the last good data (`DOWN: the feed returned no data (serving the last data)`); a single-series feed answering
with no rows replaces its data with none and stays `UP`. A purge clears the data, then
refetches. `stale-after` does not notice a publisher that stopped publishing, since every successful parse counts as
new data. Full detail: [FEEDS_CONNECTOR.md](../connectors/FEEDS_CONNECTOR.md).

TLS for a mirror on a private CA: `url: https://...` with the shared [`tls.*`](#tls--tls-for-connectors) settings ([FEEDS_CONNECTOR.md, Security](../connectors/FEEDS_CONNECTOR.md#10-security)).

### `news` and `yfinance` — connectors that compute data on demand

Nothing is stored: each document is worked out when asked for (the news about the name in the id; the quote of the ticker in the id) and cached for a short time. Both are off as shipped (`enabled: false`), because the names and tickers they are asked about leave the network (to Google and to Yahoo), and the Yahoo endpoint is unofficial and restricted to samples and internal demonstrations. Every setting, its default and the failure behaviour are in [NEWS.md](../connectors/NEWS.md#6-settings) and [YFINANCE.md](../connectors/YFINANCE.md#6-settings). Common to both: `base-url`, `user-agent`, `timeout-seconds` (10), `cache-ttl-seconds` (600 news, 60 quotes), `cache-max-entities` / `cache-max-symbols`, `max-bytes` (2,000,000), `retries` (2), `retry-initial`, `retry-max`, `retry-factor`, `retry-jitter`, `id-prefix`, `kind`, `source-name`, and the shared `tls.*`. News only: `language`, `country`, `query-template`, `relevance`, `allow-sources`, `deny-sources`, `aliases.<Name>`, `max-items`. Yahoo only: `range`, `interval`. Pages that read them through a panel's `source:` need `drishti.graph.link-budget` raised above the 40 ms default (a web call is slower), or show the panel as pending on the first view.

### `derived` — kinds computed from other kinds

| Setting | Default | Meaning |
|---|---|---|
| `<kind>.from` | required | The kind it is built from. One `<kind>.from` per derived kind the connector serves. |
| `<kind>.group-by` | required | Rachana-EL over a member: its group (the derived entity's id). |
| `<kind>.where` | none | Rachana-EL: only members for which it is true count. |
| `<kind>.id-field` | `id` | A field that holds the key besides `id`. |
| `<kind>.members` | `members` | The field listing the members' ids. |
| `<kind>.fields.<name>` | none | `count`, or `sum`/`avg`/`min`/`max`/`distinct`/`first` followed by an expression. |
| `<kind>.rows.<column>` | none | One row per member with these columns (field `rows`, or `<kind>.rows-field`). |
| `<kind>.max-members` | `1000` | Members listed (and rows) on a page; `memberCount` and every aggregate count all of them. |
| `zone` | `America/New_York` | The zone whose calendar date `today()` is for the latest pages (a picked date's pages use that date). |
| `store` | `heap` (`disk` in the trading pack's `trade-pages`) | `heap`: every page is built and kept in memory (small books). `disk`: the pages live under `store-dir`, the heap keeps each group's totals only; complete at any number of members (1.16 million trades a day measured in an 8 GB heap, 2026-10-18). |
| `store-dir` | `<data dir>/derived/<connector>` | The pages' only folder (`store: disk`); emptied at start, one sub-folder per computation, deleted when a newer one replaces it. |
| `retire-after` | `60s` | How long replaced pages on disk are kept for reads still running. |
| `rebuild-delay` | `5s` | `store: disk`: after a purge (a data-load signal, Admin → Caches) the latest pages are rebuilt in the background this long after the last purge of a burst. A purge never starts a second full read beside one that runs: the running build is kept for its readers and the next reader after it builds once more. |
| (restart) | — | With `store: disk`, the pages of the previous run are served again at start when built by the same definitions (`pages.json` in their folder); their data version is then checked as for any build. Without that, grouped pages would wait for the first build after every restart. |
| `version-check` | `10s` | How often the members' data version is asked; a new version (a load) rebuilds, nothing else does. |
| `refresh` | `30s` | Rebuild age, used only when a source cannot say its data's version. |
| `max-stream` | `100000` | `store: heap`: members kept from a one-pass read (a WARN and Admin → Health DEGRADED say when it cuts). Not used with `store: disk`. |
| `max-scan` | `50000` | Members read one at a time, for sources that cannot read a day in one pass. |

Admin → Caches (or `drishti.py server caches purge <connector>`) drops a derived connector's pages; with `store: disk` the
latest date is rebuilt from the lake at once, the old pages serving until the new ones are ready.

In a pack the settings may be nested (`book-pnl: { from: trade, fields: { mtm: sum $.mtm } }`): nested maps become
dotted keys. Worked example: [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds).

### `demo` — sample data

| Setting | Default | Meaning |
|---|---|---|
| `dirs` | from packs | Sample directories. |
| `ticking` | `true` | Live documents move while someone watches. |
| `tick-ms` | `400` | Tick interval. |

The shipped `application.yaml` sets only `drishti.sources.plugins.demo.enabled` (`DRISHTI_DEMO_ENABLED`); `ticking`
and `tick-ms` are the code's defaults. Full detail: [DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md).

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
| `DRISHTI_KAFKA_BOOTSTRAP` | `localhost:9092` | trading | Its brokers. |
| `DRISHTI_TRADING_TOPIC` | `drishti.trading.trades` | trading | Its topic. |
| `DRISHTI_STREAM_DISK_CACHE` | `true` | trading | Its disk cache on or off. |
| `DRISHTI_CACHE_ROOT` | `./data/cache` | trading | Disk cache root (one sub-directory per connector). |
| `DRISHTI_STREAM_CACHE_GB` | `10` | trading | Disk cache size. |
| `DRISHTI_CACHE_RESET_AT` | `02:00` | trading | Nightly clearing time (New York). |
| `DRISHTI_FEED_NYFED_SOFR`, `DRISHTI_FEED_ECB_ESTR`, `DRISHTI_FEED_ECB_FX`, `DRISHTI_FEED_US_TREASURY`, `DRISHTI_FEED_FRED` | `false` | market-data | Turn each public feed on. |
| `FRED_API_KEY`, `DRISHTI_FRED_SERIES` | empty, `DGS10,DFF` | market-data | FRED needs a key. |

Example: the trading pack with its live Kafka stream.

```bash
DRISHTI_PACKS=trading DRISHTI_STREAM_TRADING=true DRISHTI_KAFKA_BOOTSTRAP=kafka:9092 \
  java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
```

`curl -s localhost:18480/api/v1/sources` should then list `trading-stream`.

### Profiles: another store instead of Delta Lake

`SPRING_PROFILES_ACTIVE=<profile>` (`postgres`, `aerospike`, `duckdb`, `files`, `iceberg`, `mongodb` or `redis`) loads
`application-<profile>.yaml`, which redefines the banking data-domain connectors (`reference-store`,
`market-store`, `trade-endur`, `risk-store`, `credit-store`, `collateral-store`) to read a database instead of
the lake. The packs still decide kinds, routes and modes.

**Deprecated since 1.19.** The profiles still work and the server logs a warning that names the equivalent connector file; the way forward is one YAML file per connector in `config/connectors/` ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)), with an example for every store in `config/connectors.examples/`.

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
  DRISHTI_PG_PASSWORD="$PG_PASSWORD" java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.6.5-exec.jar
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
| `backend.timeout_seconds` | `5` | Per call to the server. |
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
| `product` / `tagline` | `Drishti` / `Any data. Any domain. One grammar.` | Shown in the top bar and sign-in page. |
| `user`, `user_display`, `desk` | `ash` (`DRISHTI_USER`), `Ash`, `Rates desk` | The acting user when `auth.enabled` is false (local development only). |
| `user_cache_seconds` | `10` | How long a console worker reuses what the server said about a user (settings, packs, view layouts, saved pivots, BI powers) before asking again. A change made on this console is seen at once by all its workers (the user stamps, see `server.workers`); this bounds how long another console behind the same address shows the old choice. |
| `about_prefetch` | `true` (`DRISHTI_ABOUT_PREFETCH`) | Fetch the page's *About this page* answer once the view is idle, so table headers, strip and key-value labels that have a glossary entry are underlined (with a hint) before the drawer is opened. It costs the server one explain per page view: the view is derived again for the caller and kept `drishti.explain.cache-ttl`, so opening the drawer afterwards is free. Set `false` on a busy installation; the labels are then underlined when the drawer is first opened ([PERFORMANCE.md](PERFORMANCE.md#about-this-page-and-its-cost)). |
| `clock_tz` / `clock_label` | `America/New_York` / `NY` | The top-bar clock. |
| `landing_examples` | `4` | How many example commands the landing page plays. They are the example commands (`console.examples`) of the packs switched on, so the landing page never names an entity of a pack that is not installed; with none, it shows the form `<MNEMONIC> <ID> <GO>`. Studio's first preview is the first of these examples too. |
| `product_native` / `product_meaning` | `दृष्टि` / `Drishti (दृष्टि) means <em>sight</em>.` | The product name in its own script (blank for none) and one line on its meaning, on the About page. |
| `copyright` / `notice` | the Drishti copyright and a ban on unauthorised copying | The legal lines in the footer and on the About page. Change them here, never in code; the server has the same under `drishti.branding`. |
| `error_advice` | a short text for each of `DRS-1001`, `DRS-1002`, `DRS-1003`, `DRS-1004`, `DRS-1007`, `DRS-4003`, `DRS-4004`, and `default` | What an error page tells the user to do, by the code the server answered with. `{kind}` and `{id}` in a text become the entity asked for; a code with no entry gets `default`. Edit it so the advice fits your site (who to ask, which channel). |
| `showcase` | four screenshots of the retired finance example pack | The landing page's pictures: `{slug, title, cmd, sutra}` each, the image being `web/static/img/shot-<slug>.png`. A caption names its `cmd` only when that command is one of the packs' examples. |

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
| `packs.enabled` | the seven shipped packs (`DRISHTI_PACKS`) | Used only when the server cannot be asked which packs are enabled. |
| `help.docs_dir` | `../docs` | The documents rendered in the help centre's reference section. |
| `studio.examples_dir` | `../docs/guides/examples` | The Rachana examples (`<name>.sutra.yaml`, `<name>.json`, `<name>.md`) that the Build workbench (**Build → New screen → Examples**, the File menu) and **Help → Examples** offer. Only names of complete example sets present there are served. |
| `ui.studio_example` | `all-panels-showcase` | The example Studio opens on when no entity or Sutra is asked for. Blank, or a name that is not there: Studio opens on the first example entity of the user's packs, as before. |
| `builder.max_samples` | `50` | Build workbench, **New** (`/build/new`): files in one upload, and samples in all (a `.jsonl` line is one). Over it, the whole upload is refused (`413 DRS-5005`). Keep it at or under the server's `drishti.builder.max-samples`. |
| `builder.max_file_mb` | `5` | Largest single file; a larger one is left out and reported, the rest are shaped. |
| `builder.max_total_mb` | `25` | All files together; over it the upload is refused. |
| `builder.studio_kind` | `sample` | The kind a new Design and the workbench preview a brought document as (a pasted document has no kind of its own). |
| `builder.pack_sample_docs` | `200` | **Build → New pack** (`/build/pack/new`, [Schema to pack](../guides/SCHEMA_TO_PACK.md)): documents the browser keeps per data file and sends to the console. A sample, never the file. |
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

The file also holds `studio.enabled: true`, which the current console does not read; whether Studio can save
is decided by the server (`drishti.rachana.studio-save`).

### `calc`

| Key | Default | Meaning |
|---|---|---|
| `calc.enabled` | `true` (`DRISHTI_CALC_ENABLED`) | Off: no view offers Calc and the console's `/api/calc/*` routes refuse, whatever packs and roles say ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)). |

### `layouts`

| Key | Default | Meaning |
|---|---|---|
| `layouts.enabled` | `true` (`DRISHTI_LAYOUTS_ENABLED`) | Off: no view offers layout mode and saved personal layouts are not applied, whatever roles say ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)). |
| `layouts.min_span` | `3` | The narrowest width, in columns of the 12-column grid (1 to 12), that layout mode lets a user give a panel. |
| `form.collapse_on_submit` | `true` | `FORM <pack>`: after a search, fold the form to a one-line summary of its criteria so the results get the space (Alt+F or the summary opens it again). |

### `live`

| Key | Default | Meaning |
|---|---|---|
| `live.max_subscriptions` | `32` (`DRISHTI_LIVE_MAX_SUBSCRIPTIONS`) | Live subscriptions one browser's channel carries at most: views across all its tabs and workspace panes, the alerts bell, monitors (a view open in several tabs counts once). Each holds one stream to the server from `backend.pool_size`, so keep it well below that. One over the limit is answered with `gone`, `DRS-5003`, and the view shows `Static` ([LIVE.md](../architecture/LIVE.md#one-connection-per-browser)). |

The Python runtime is a folder, not a setting: `drishti-console/web/static/vendor/pyodide/`, installed by
`tools/fetch-pyodide.sh` and served at `/pyodide/<version>/` when present
([PYTHON_CALC.md](../guides/PYTHON_CALC.md#12-installing-the-python-runtime)).

---

## Environment variable index

Server (S), console (C), or both.

| Variable | Used by | Sets |
|---|---|---|
| `DRISHTI_PORT` | S | `server.port` |
| `DRISHTI_ALLOW_INSECURE_TLS` | S | allows a connector's `tls.insecure-trust-all` ([`tls.*`](#tls--tls-for-connectors)) |
| `DRISHTI_PACKS` | S, C | `drishti.packs.enabled`, `packs.enabled` |
| `DRISHTI_PACKS_DIR` | S, C | `drishti.packs.dir`, `packs.dir` |
| `DRISHTI_PACKS_EXTRA_DIRS` | S, C | `drishti.packs.extra-dirs`, `packs.extra_dirs` |
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
| `DRISHTI_SUTRA_REVIEW`, `DRISHTI_SUTRA_FOUR_EYES`, `DRISHTI_GOVERNANCE_DIR` | S | `drishti.governance.*` |
| `DRISHTI_SECURITY_ENABLED` | S | `drishti.security.enabled` |
| `DRISHTI_CALC_ENABLED` | S, C | `drishti.calc.enabled`, `calc.enabled` |
| `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | C | `live.max_subscriptions` |
| `DRISHTI_TOKEN_SECRET` | S, C | `drishti.security.secret`, `auth.token_secret` |
| `DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID` | S, C | `drishti.security.oidc.*`, `auth.oidc.*` |
| `DRISHTI_EMBED_ENABLED` | S, C | `drishti.embed.enabled`, `embed.enabled` |
| `DRISHTI_PUBLIC_URL`, `DRISHTI_CONSOLE_URL`, `DRISHTI_EMBED_KEY_FILE` | S | `drishti.embed.issuer`, `.audiences`, `.signing-key` |
| `DRISHTI_OIDC_CLIENT_SECRET`, `DRISHTI_OIDC_REDIRECT_URI` | C | `auth.oidc.*` |
| `DRISHTI_SEED_ADMIN` and other identity variables | S | see [USER_MANAGEMENT.md](USER_MANAGEMENT.md) |
| `DRISHTI_DELTA_ROOT`, `DRISHTI_DELTA_ENGINE`, `DRISHTI_LAKE_ENABLED`, `DRISHTI_STREAM_*`, `DRISHTI_KAFKA_BOOTSTRAP`, `DRISHTI_TRADING_TOPIC`, `DRISHTI_CACHE_*`, `DRISHTI_FEED_*`, `FRED_API_KEY`, `DRISHTI_FRED_SERIES` | S (packs) | [pack connectors](#environment-variables-used-by-the-packs-and-profiles) |
| `SPRING_PROFILES_ACTIVE`, `DRISHTI_PG_*`, `DRISHTI_AEROSPIKE_*`, `DRISHTI_DUCKDB_*`, `DRISHTI_FILES_ROOT`, `DRISHTI_ICEBERG_*`, `DRISHTI_MONGODB_*`, `DRISHTI_REDIS_*` | S | [profiles](#profiles-another-store-instead-of-delta-lake) |
| `DRISHTI_CONSOLE_HOST`, `DRISHTI_CONSOLE_PORT`, `DRISHTI_BACKEND_URL`, `DRISHTI_USER` | C | `server.*`, `backend.url`, `ui.user` |
| `DRISHTI_AUTH_ENABLED`, `DRISHTI_SESSION_SECRET`, `DRISHTI_SECURE_COOKIE` | C | `auth.*` |
| `DRISHTI_CONSOLE__<SECTION>__<KEY>` | C | any console key |
| `DRISHTI_<RELAXED_KEY>` (e.g. `DRISHTI_LIVE_HEARTBEAT=20s`) | S | any server key |

The Docker compose files in `deploy/` show these variables in use; see [OPERATIONS.md](OPERATIONS.md).
