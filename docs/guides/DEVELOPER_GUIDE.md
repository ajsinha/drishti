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

# Developer guide

This is the handbook for changing Drishti's code. It explains how the repository is laid out, how to build, run and test it from a
fresh clone, which rules the build enforces, how a request (a view, a BI question, a live tick) travels through the code, and then
gives worked recipes for the changes people make most often. Every class, path and command here exists in the repository (checked
against this release on a fresh clone); the recipes follow the idioms of the real classes they imitate.

If you only want to run Drishti, read [GETTING_STARTED.md](GETTING_STARTED.md); to run it from IntelliJ IDEA and PyCharm, read
[IDE_GUIDE.md](IDE_GUIDE.md). For the design and its reasons, read [ARCHITECTURE.md](../architecture/ARCHITECTURE.md), the BI model
architecture [BI_MODEL_ARCHITECTURE.md](../architecture/BI_MODEL_ARCHITECTURE.md) and the [decision records](../architecture/adr/README.md).

**Contents**

1. [The repository](#1-the-repository)
2. [Building and running from source](#2-building-and-running-from-source)
3. [Project rules](#3-project-rules)
4. [How a request travels through the code](#4-how-a-request-travels-through-the-code)
5. [Recipes](#5-recipes)
6. [Testing strategy](#6-testing-strategy)
7. [Debugging](#7-debugging)
8. [Common pitfalls](#8-common-pitfalls)
9. [Releasing](#9-releasing)

---

## 1. The repository

Drishti has two programs. The **server** is one Spring Boot 3.5 application on Java 21 or newer, production runs 21
(`drishti-server`), built from a Maven reactor of internal modules; it reads every source, lays out every screen, answers every BI
question and pushes every live change. The **console** is a FastAPI and Jinja2 web application in `drishti-console/` that renders the
server's JSON; the browser draws what it is sent and computes no number of its own (the BI rule *the servers compute, the browser
draws*, [BI_MODEL_ARCHITECTURE.md](../architecture/BI_MODEL_ARCHITECTURE.md) section 2c). Industries are **packs** (`config/packs/`),
which are content and configuration, not code: seven ship (`core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`,
`world-economy`, `news-and-markets`), every attribute of their documents in UPPER_SNAKE.

### 1.1 Java modules

All Java code lives under the package `com.ash.drishti`. The parent POM (`pom.xml`, artifact `drishti-parent`) inherits from
`spring-boot-starter-parent`, which aligns library versions, so there is no separate BOM module.

| Module | Package | What it owns | Key classes |
|---|---|---|---|
| `drishti-api` | `api` | The plugin SPI and data model. **No Spring**, no internal dependencies. | `SourcePlugin`, `PluginManifest`, `SourceCapabilities`, `SourceContext`, `EntityRef`, `EntityDocument`, `Provenance`, `AsOf`, `DataNode`, `HitIndex`, `PluginNotConfigured` |
| `drishti-common` | `common` | Error codes, the exception type, JSON, shape fingerprints, business calendars, branding, a pack's samples (`PackSamples`), retries and live settings. | `ErrorCode`, `DrishtiException`, `JsonCodec`, `ShapeFingerprinter`, `BusinessCalendar`, `PackSamples`, `RetryPolicy`, `RetryBoard`, `LiveSettings` |
| `drishti-rachana` | `rachana` | The Rachana grammar: the Sutra model, the YAML parser (`*.sutra.yaml`, `rachana: 1`), the registry with hot reload, the JSON Schema of the language, Rachana-EL, formats and tones. | `SutraRegistry`, `SutraMatcher`, `parse.SutraParser`, `parse.SutraBuilder`, `RachanaSchema`, `model.PanelKind`, `el.ElCompiler`, `el.Functions`, `format.Formats` |
| `drishti-inference` | `inference` | Layout inference: semantic hints, rules, the merge of a Sutra with inferred panels. | `InferenceEngine`, `Rules`, `Semantics`, `LayoutMerger`, `EffectiveLayout` |
| `drishti-graph` | `graph` | Entity references: which fields link to which kinds, and link badges. | `ReferenceCatalog`, `BadgeRenderer`, `GraphProperties` |
| `drishti-engine` | `engine` | Sources and routing (including a **union** of connectors that serve one kind together), the view pipeline, binding, the ViewModel, commands and type-ahead, search, history, impact, business dates, live topics. | `ViewPipeline`, `source.SourceRegistry`, `source.SourceRouter`, `source.ConnectorInstance`, `bind.Binder`, `view.ViewModel`, `view.PanelData`, `live.TopicHub`, `live.ViewStream`, `command.CommandParser`, `search.StructuredSearch` |
| `drishti-identity` | `identity` | Users, passwords, roles, lockout, preferences, pack switches and the audit trail, in SQLite or PostgreSQL through JPA. | `UserService`, `JpaUserStore`, `RoleStore`, `PackStateStore`, `JpaAuditLog`, `IdentityDatabase`, `db.IdentityRepositories` |
| `drishti-packs` | `packs` | Loads the enabled packs and turns them into the lowest-precedence property source; writes connector files from the packs' templates. | `PackLoader`, `PackLineage`, `PackEnvironmentPostProcessor` (registered in `META-INF/spring.factories`), `ConnectorBootstrap` |
| `drishti-ai` | `ai` | The LLM provider layer and the MCP client: providers (Anthropic, OpenAI-compatible through Spring AI, a mock), model registry, call policy, prompt masking, MCP clients over streamable HTTP. Depends on `api` and `common` only (`ArchitectureRulesTest.aiLayerDependsOneWay`). **Off by default**; see [AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md) and [MCP_CLIENT.md](../architecture/MCP_CLIENT.md). | `LlmGateway`, `LlmRegistry`, `LlmProvider`, `AnthropicProvider`, `SpringAiOpenAiProvider`, `McpGateway`, `McpRegistry`, `McpClient` |
| `drishti-diskcache` | `diskcache` | A disk-backed, size-bounded, self-clearing cache (RocksDB) for live connector state. | `DiskCache` |
| `drishti-messaging` | `messaging` | The latest-state store and live fan-out shared by the ActiveMQ and RabbitMQ connectors. | `MessageStateSource` |
| `drishti-lakeio` | `lakeio` | Lake file access shared by the native Delta and Iceberg engines, no Hadoop: `Storage` (local disk through `java.nio`, Windows drive letters, backslashes and UNC shares included; S3 through the AWS SDK, with conditional PUTs for commits), byte ranges, Parquet page codecs (`NativeCodecs`, reading and writing) and the per-file Parquet footer cache. | `Storage`, `LocalStorage`, `S3Storage`, `LocalPaths`, `NativeCodecs`, `ParquetFooters` |
| `drishti-iceberg` | `iceberg` | Drishti's own Apache Iceberg engine without Hadoop, on `drishti-lakeio`: metadata JSON kept whole, path-based, metadata-file and REST catalogs (JDK HTTP client, OAuth2, ETag, vended credentials, SigV4), Avro manifests, planning with partition and metric pruning, field-id Parquet reads with schema evolution and deletes, the Parquet writer with Iceberg metrics, safe commits and the table operations. Its tests prove the writer loads no Hadoop `Configuration`. | `Table`, `catalog.Catalogs`, `scan.Planner`, `read.TaskReader`, `write.Operations`, `write.ParquetDataWriter` |
| `drishti-deltalake` | `deltalake` | A Delta Kernel engine without Hadoop on `drishti-lakeio` (local disk, Windows paths included, and S3; Parquet through parquet-java with the shared input files and codecs); and Drishti's own Delta writer (`lake build --engine drishti`). Its tests prove in an isolated class loader that no Hadoop `FileSystem`, `Shell` or `Configuration` loads. | `NativeEngine`, `write.LakeManifest` |
| `drishti-testkit` | `testkit` | Contract tests every source plugin of a kind must pass. | `DatedSourceContract`, `MessageSourceContract`, `BrokerOutage` |
| `drishti-server` | `server` | The Spring Boot application: REST and SSE controllers, security, alerts, Sutra governance, and one sub-package per feature area (`admin`, `ai`, `alerts`, `bi`, `calc`, `cli`, `collab`, `connectors`, `deploy`, `design`, `dist`, `embed`, `explain`, `governance`, `layout`, `loads`, `notify`, `pivot`, `registry`, `reports`). `bi` is Rūpaka, the BI engine (section 4.4). | `DrishtiApplication`, `api.*Controller`, `security.Entitlements`, `security.TokenFilter`, `alerts.AlertEngine`, `governance.SutraGovernance`, `bi.mq.MqService`, `bi.engine.QueryService` |
| `drishti-it` | `it` | Repository-wide rules, as tests only. | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest`, `NoBlockingUnderSynchronizedTest` |
| `drishti-benchmarks` | `benchmarks` | JMH benchmarks of the hot paths (fingerprint, Rachana-EL, formats, cold inference). | `HotPathBenchmark` |

### 1.2 Source plugins

Each plugin is its own module under `plugins/`, depends on `drishti-api` only (plus `drishti-common` and `drishti-testkit` for
tests), and registers itself in `src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin`. The server depends on all of
them, so they are on its class path; each starts only when enabled or used by a named connector, one YAML file per connector in
`config/connectors/` ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md)).

| Module | Plugin name | Reads | Extra internal dependency |
|---|---|---|---|
| `drishti-plugin-demo` | `demo` | The enabled packs' `samples/` (`<kind>.jsonl`); random-walk ticks ([DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md)) | — |
| `drishti-plugin-file` | `file` | JSON or JSON Lines documents in a folder, dated by folder | — |
| `drishti-plugin-rest` | `rest` | An HTTP/JSON service | — |
| `drishti-plugin-jdbc` | `jdbc` | A database by query, or the PostgreSQL entity-table mode | — |
| `drishti-plugin-delta` | `delta` | Delta Lake tables through Delta Kernel (no Spark), with time travel; engine `native` (no Hadoop) or `hadoop`; the demo lakes and every shipped pack's store | `drishti-deltalake` |
| `drishti-plugin-aerospike` | `aerospike` | A set per data domain: a record per entity per business date with promoted bins, an index record per entity, a record per kind (`AerospikeLayout`); `AerospikeLoader` writes it | — |
| `drishti-plugin-cassandra` | `cassandra` | Apache Cassandra, ScyllaDB, Astra: a keyspace per data domain, a partition per business day, generation and bucket keyed by id, `entity_days` and `kind_days` (`CassandraLayout`); `CassandraLoader` writes it, a day under a new generation | — |
| `drishti-plugin-redis` | `redis` | Redis (standalone or Cluster): today and recent days in memory, with live updates | — |
| `drishti-plugin-mongodb` | `mongodb` | A document per entity per business day, in a collection per data domain | — |
| `drishti-plugin-iceberg` | `iceberg` | Apache Iceberg tables in Drishti's layout (path-based or a REST catalog), with time travel; engine `native` (drishti-iceberg, no Hadoop) or `library` | `drishti-iceberg` |
| `drishti-plugin-iceberg-external` | `iceberg-external` | Iceberg tables other engines write, rows as documents, read-only, through drishti-iceberg | `drishti-iceberg` |
| `drishti-plugin-duckdb` | `duckdb` | Every data domain in one embedded DuckDB file | — |
| `drishti-plugin-feeds` | `feed` | Public feeds: NY Fed SOFR, ECB €STR and FX, US Treasury, FRED ([FEEDS_CONNECTOR.md](../connectors/FEEDS_CONNECTOR.md)) | — |
| `drishti-plugin-news` | `news` | Headlines for a named entity, computed on demand from Google News RSS (code, not a store) | `drishti-common` |
| `drishti-plugin-yfinance` | `yfinance` | A quote and price history for a ticker, computed on demand from Yahoo Finance | `drishti-common` |
| `drishti-plugin-kafka` | `kafka` | Compacted topics of entity documents | `drishti-diskcache` |
| `drishti-plugin-activemq` | `activemq` | ActiveMQ queues and topics | `drishti-messaging` |
| `drishti-plugin-rabbitmq` | `rabbitmq` | RabbitMQ queues | `drishti-messaging` |
| `drishti-plugin-s3` | `s3` | Documents in Amazon S3 or an S3-compatible store | — |

Besides these plugins, the engine has one built-in plugin type, `derived`: entities computed from other kinds (the trading
pack's `trade-pages` connector makes the counterparty, portfolio, legal-entity and index pages from the `trade` kind).

### 1.3 How the modules depend on each other

Compile-scope dependencies, from the POMs:

```text
drishti-api                       (nothing internal; no Spring)
└── drishti-common
    ├── drishti-ai                 (api, common; Spring AI)
    ├── drishti-rachana
    │   ├── drishti-inference ──┐
    │   └── drishti-graph ──────┴── drishti-engine
    ├── drishti-identity
    ├── drishti-packs
    └── drishti-testkit            (api, common)
drishti-diskcache                 (nothing internal)
drishti-messaging                 (api, diskcache)
drishti-lakeio                    (api)
drishti-deltalake                 (lakeio)
drishti-iceberg                   (api, lakeio)
plugins/*                         (api; kafka adds diskcache; activemq and rabbitmq add messaging; delta adds deltalake; iceberg and iceberg-external add iceberg)
drishti-server                    (engine, identity, packs, ai, and all nineteen plugins)
drishti-it                        (server, testkit)
drishti-benchmarks                (inference)
```

`ArchitectureRulesTest` keeps these one-way (see [§3.3](#33-architecture-rules)): nothing below the engine may see `engine` or
`server`, the engine may not see `server`, `api` sees nothing internal, and `ai` sees only `api` and `common`. The reactor also builds
`docs/guides/examples/connector`, the worked example plugin of [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

### 1.4 Everything else

| Folder | What is in it |
|---|---|
| `drishti-console/` | The web console: `run_drishti_web.py` (entry point), `core/` (app factory, backend client, auth, business date, packs, config loader, `ai/` for the console's LLM clients, and one module per feature such as `forms.py`, `bi.py`, `controls.py`, `calc.py`, `retry.py`), `routes/` (one router per area), `web/templates/` (Jinja2, with `_macros/panels.html` holding one macro per panel kind), `web/embed/` and `web/elements/` (Drishti Elements, the `<drishti-view>` web component), `web/static/` (`js/`, `css/`, `img/`, and third-party code under `vendor/`), `web/guides/` (in-app guides), `config/` (`application.yaml`, `help.yaml`, `workspaces.yaml`, `competitive.yaml`), `tests/` (pytest, with a fake backend in `conftest.py`; 255 files, 89 of them Chromium tests named `*_browser.py`) |
| `config/packs/<name>/` | One domain pack: `pack.yaml`, `sutras/`, `samples/` (`catalog.jsonl` and one `<kind>.jsonl` per kind), `config/` (formats, semantics, help, workspaces), `guides/`, `python/` (Calc snippets) and `tests/`. Seven ship: `core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`, `world-economy`, `news-and-markets`. The trading pack's Sutras are organised `sutras/common/` (shared blocks), `sutras/endur/`, `sutras/calypso/`, `sutras/murex/` (one per product type) and `sutras/pages/` (cross-system pages); the folders are organisation only: each Sutra's `match` (`SOURCE_SYSTEM` and `PRODUCT_TYPE`) decides ([SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md)) |
| `examples/packs/` | The retired packs (`finance`, `genomics`, `logistics`, `retail-banking`, `climate-risk`, `operational-risk`, `liquidity-risk`), kept as examples and as fixtures for tests written against them; loaded only with `DRISHTI_PACKS_EXTRA_DIRS=examples/packs` |
| `config/connectors/`, `config/connectors.examples/` | The site's connector files (written by the server at its first start from the packs' templates; not committed) and ready-made examples per store ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)) |
| `config/bi/` | Shipped BI files: datasets, models, reports, boards |
| `run-configs/` | The IntelliJ IDEA run configurations (IntelliJ opens the repository root; [IDE_GUIDE.md](IDE_GUIDE.md)): **Drishti build server jar**, **Drishti server**, **Drishti server (demo lakes)**, **Drishti server (debug-friendly)**, **Drishti server (QA profile)**. The PyCharm ones (PyCharm opens `drishti-console/`, so the two IDEs never share one `.idea`) are in `drishti-console/run-configs/`: **Drishti console**, **Build demo lakes**, **Load ENDUR file into the lake**, the QA console and the CLI tools |
| `data/testdata/` | The committed test data: sixty business days of every dataset the seven packs read (5,000 trades a day per source system), `.jsonl.gz`, with `testdata.yaml` and a README ([data/testdata/README.md](../../data/testdata/README.md)) |
| `tools/drishti.py` | The one command line over the tools below and the server's REST: `sutra`, `pack ...` (`new`, `make`, `check`, `about-check`, `diff`, `i18n`, `publish`, `install`, `regenerate`, `catalogue`, `bundle`, `verify`, `deploy`, `rollback`, `keygen`), `data ingest|load|profile`, `server health|packs|smoke`, `connector ...`, `bi ...`, `view get|explain`, `design ...`, `lake build|publish|scan|status|demo|maintain`, `doctor`, `completion`, `docs shots`; the `test_cli_*.py` and `test_drishti_cli.py` suites cover it. See [CLI_GUIDE.md](CLI_GUIDE.md) and [TOOLS.md](TOOLS.md) |
| `tools/lake/` | Delta lakes from Python: `build.py` (`lake build`: a large JSON export streamed into a Delta table, flat memory, partitions, promoted columns, the data-load signal or the BI-store stamp), `demo.py` (`lake demo`: the demo lakes from `data/testdata`), `scan.py`, `laketables.py` (`lake status`), `maintain.py` (retention, compaction, checkpoints, vacuum, `relayout`), each with its tests |
| `tools/testdata/` | `make_testdata.py` (writes `data/testdata`, deterministic, `--check`), `universe.py` (the fictitious counterparties, books and legal entities), `test_testdata.py`, `test_deterministic.py` (the same bytes on every Python) |
| `tools/packgen/` | Pack generators: `banking/` (the five banking packs from one taxonomy, `make_data.py` for their samples and `--lake`/`--jsonl`), `trades/` (the common trade schema every source system writes, `schema.py`, and the ENDUR, CALYPSO and MUREX generators), `common/` (`packbuild.py`, `packsamples.py`, `walks.py`, `upper.py`), `world/`, `economics/`, `politics/`, and the retired packs' generators |
| `tools/samplegen/` | Sample-history helpers: `lake.py` (a pack's sample history as JSONL loader rows), `load_rows.py` (loads such rows into Delta), `layout.py` (the pack-declared lake layout: promoted columns, sorted files), `bulk_trades.py` (a large trading book for one source system: `--trades`, `--days`, `--system`), `stream.py` (Kafka ticker), `dates.py` (business-day calendars) |
| `tools/load-*.sh` | Load the samples (and a generated book) into PostgreSQL, Iceberg, DuckDB, MongoDB, Redis, Aerospike, Cassandra, JSON-lines files or Delta ([DEMO_DATA.md](../connectors/DEMO_DATA.md)) |
| `tools/bench/` | The scale benchmark: `scale.sh <store> <trades-a-day>`, `measure.py`, `report.py`; `results/` keeps each run's raw JSON lines; see [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md) |
| `tools/packgen/pack_from_jsonl.py`, `tools/packmake.py`, `tools/schemapack.py`, `tools/packbundle.py`, `tools/packform.py`, `tools/packdiff.py`, `tools/packi18n.py`, `tools/packregen.py`, `tools/sutragen.py`, `tools/authoring.py` | Pack authoring: a pack from JSON lines or from schemas, regeneration of unedited generated files, bundles, the `form:` section, `pack diff`, `pack i18n`; the `tools/drishti.py` commands call them (see [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md), [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md), [CLI_GUIDE.md](CLI_GUIDE.md)) |
| `tools/realdata/`, `tools/docs/`, `tools/elements-demo/`, `tools/windows/`, `tools/maps/` | The real-data builds of the world-economy pack ([REAL_DATA.md](REAL_DATA.md)); the documentation screenshots (`tools/docs/screenshots.py`, `shots/`, one module per guide and tutorial); a sample host page for Drishti Elements; the PowerShell starters ([GETTING_STARTED.md (Windows)](GETTING_STARTED.md#drishti-on-windows)); the map region builder |
| `tools/packreg/` | `packreg.py`: signing keys, publishing packs to a signed registry, verifying one (ADR-018); `test_packreg.py` |
| `tools/` (files) | `drill.sh` (verify and publish), `fetch-pyodide.sh` (installs Calc's Python runtime, pinned and verified, into `drishti-console/web/static/vendor/pyodide/`), `elements_sheet.py` (regenerates the Elements stylesheet), `make_icons.py` (favicon and app icons), `ingest_jsonl.py` and `ingest_watch.py` (`data ingest`), `serverpacks.py`, `serverconnectors.py`, `doctor.py`, `completion.py`, `viewcmd.py`, `dataprofile.py`, `license_headers.py` (check or insert the copyright header), `test_no_hidden_names.py` (no hidden names, section 8), `rachana/md_to_yaml.py` (converts Markdown Sutras read before 1.11 to `*.sutra.yaml`) |
| `deploy/` | `server.Dockerfile`, `console.Dockerfile`, `compose.yaml`, `compose.data.yaml`, `lake-maintenance.yaml`, `grafana/` |
| `config/license-header.txt` | The text of the copyright header that `license_headers.py` inserts |
| `data/` | The **data folder** (`DRISHTI_DATA_DIR`): everything Drishti writes at run time, all git-ignored except `testdata/`: `delta/` (the demo lakes), `bi-store/` (BI columns and rollups), `connectors/` (`generated/` and `history/`), `identity/`, `feeds/`, `packs/` (`added.yaml`, installed archives), `loads/`, `governance/`, `collab/`, `reports/`, `qa/` (the QA profile), `build/` (tool output and staging), `drill/` (the drill's logs) |
| `docs/` | These documents, by audience: `guides/` (getting started, user, developer, API, Rachana, BI and troubleshooting guides), `tutorials/` (18 hands-on tutorials), `connectors/` (the connector and plugin guides, a design document per store, demo data), `admin/` (operations, configuration, users, performance, and the runbooks under `admin/runbooks/`), `architecture/` (architecture, design notes and the ADRs under `architecture/adr/`), `qa/`; `README.md` is the index; reference mockups and logos under `requirements/` |
| `.github/workflows/fast.yml`, `windows.yml` | CI: `fast.yml` runs `./mvnw -B -q verify` and the header check on Java 21 and 25 and `pytest` for the console on Python 3.13; `windows.yml` builds on Windows, runs the native Delta engine, the Delta connector and server tests, and asks a server on a small lake for a view, a search and a past date |

---

## 2. Building and running from source

### 2.1 Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 or newer (any vendor; Java 21 is the production target) | The enforcer rule in `pom.xml` (`requireJavaVersion [21,)`) stops an older Java with `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …` The bytecode targets 21 (`maven.compiler.release`), so one jar runs on 21 and 25; `.java-version` says `25`, the version to develop on. On Java 25 run the server with `-XX:+UseCompactObjectHeaders` (about 10% less heap); Java 21 has no such flag and refuses to start with it. Java 21 pins a virtual thread's carrier while it blocks inside `synchronized`, so main code uses a `ReentrantLock` for anything that does I/O (`NoBlockingUnderSynchronizedTest` enforces it; see [PERFORMANCE.md](../admin/PERFORMANCE.md#java-21-and-virtual-thread-pinning)). |
| Python | 3.11 or newer (3.13 recommended) | The console and the tools. CI uses 3.13. |
| uv | any recent | Creates the console's environment (`uv venv --python 3.13`), which works where the system Python has no `ensurepip` (Debian and Ubuntu without `python3.X-venv`); runs some tools with `uv run --with …` so nothing is installed globally. |
| Docker | optional | Only for the Testcontainers tests (PostgreSQL, Aerospike, Cassandra, ScyllaDB, ActiveMQ, RabbitMQ, MinIO) and `deploy/docker/compose.yaml`. |

Set `JAVA_HOME` in every terminal:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v
```

You should see `Java version: 21…` in the output. Maven itself comes with the repository (`./mvnw`, Maven 3.9.12).

**From a fresh clone to a running stack** (what [GETTING_STARTED.md](GETTING_STARTED.md) and
[data/testdata/README.md](../../data/testdata/README.md) walk through; measured on a 24-core laptop):

```bash
git clone https://github.com/ajsinha/drishti.git && cd drishti
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
./mvnw -q package -DskipTests -pl drishti-server -am                 # 1 min 12 s with ~/.m2 filled
drishti-console/.venv/bin/python tools/drishti.py lake demo           # about 15 min (943 s): 82 Delta tables, sixty days, under data/delta
```

then the server and the console as the run configurations start them ([GETTING_STARTED.md, Steps 6 and 7](GETTING_STARTED.md#step-6--start-the-server)).

### 2.2 Maven commands

Run them from the repository root.

| You want to… | Command |
|---|---|
| Build everything, no tests | `./mvnw -q package -DskipTests` |
| Build only the server and what it needs | `./mvnw -q package -DskipTests -pl drishti-server -am` |
| Build and run every test and rule | `./mvnw -q verify` |
| The same, without touching the network (what the drill runs) | `./mvnw -q -o verify` |
| Build one module and what it needs | `./mvnw -q -pl drishti-engine -am package -DskipTests` |
| Run one module's tests | `./mvnw -q -o -pl drishti-rachana -am test` |
| Run one test class | `./mvnw -q -o -pl drishti-server -am test -Dtest=ViewPipelineTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Run one test method | `./mvnw -q -o -pl drishti-rachana -am test -Dtest='ElTest#pathsOperatorsAndFunctions' -Dsurefire.failIfNoSpecifiedTests=false` |
| Run only the repository rules | `./mvnw -q -o -pl drishti-it -am verify -Dtest='LicenseHeaderTest,SourceFileSizeTest,ArchitectureRulesTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| Format before committing | `./mvnw -o -q spotless:apply -pl <module>` |

What the flags mean:

- `-q` quiet: a successful build prints little or nothing.
- `-o` offline: use only what is already in `~/.m2`. It fails on a fresh machine; run once without `-o` first.
- `-pl <module>` build only that module; `-am` ("also make") builds the modules it depends on first. Without `-am`, Maven takes the
  upstream modules from `~/.m2`, which may be stale.
- `-DskipTests` compiles tests but does not run them.
- `-Dtest=…` selects tests; `-Dsurefire.failIfNoSpecifiedTests=false` stops the upstream modules that `-am` pulls in from failing
  because they hold no such test.

The server jar is `drishti-server/target/drishti-server-<version>-exec.jar` (the `exec` classifier is set on the
`spring-boot-maven-plugin` in `drishti-server/pom.xml`). The plain `drishti-server-<version>.jar` beside it is the library jar, not the
application.

> **Note:** tests read the same `${ENV:default}` placeholders as the running server. Run Maven in a shell where `DRISHTI_PACKS`,
> `DRISHTI_SECURITY_ENABLED` and the other `DRISHTI_*` variables are **not** set (the run configurations set them only for the server
> they start). The server's tests set their own packs in `drishti-server/pom.xml` (`drishti.packs.extra-dirs` is `examples/packs` and
> `drishti.packs.enabled` is `finance`, so the tests written against the retired `finance` pack, such as `ApiTest` with its
> `IRS-48213`, keep it as a fixture).

### 2.3 Tests that need Docker

Tests annotated `@Testcontainers(disabledWithoutDocker = true)` start real services in Docker and are **skipped** (not failed) when
Docker is not reachable:

| Test | Starts |
|---|---|
| `drishti-identity` `PostgresIdentityStoreTest` | PostgreSQL 18 |
| `drishti-plugin-jdbc` `PostgresEntityTableTest`, `PostgresReconnectTest` | PostgreSQL |
| `drishti-plugin-aerospike` `AerospikeSourcePluginTest` | Aerospike Community Edition |
| `drishti-plugin-cassandra` (every test class but `CassandraLayoutTest`) | one Cassandra 5 node shared by the module's classes, ScyllaDB for `ScyllaSourcePluginTest`, a TLS node for `CassandraTlsTest` |
| `drishti-plugin-activemq` `ActiveMqSourcePluginTest`, `ActiveMqOutageTest` | ActiveMQ |
| `drishti-plugin-rabbitmq` `RabbitMqSourcePluginTest`, `RabbitMqOutageTest` | RabbitMQ |
| `drishti-plugin-delta` `DeltaOnS3Test` | an S3-compatible store |

`DeltaAfterMaintenanceTest` instead assumes `uv` is installed (it runs `tools/lake/maintain.py`) and is skipped otherwise. The Kafka
tests use an embedded broker from `spring-kafka-test` and need no Docker. The tests never use a fixed port: Testcontainers and the
in-test servers take free ones, so they run beside a Drishti, a PostgreSQL or a Kafka you already run.

A green build on a machine without Docker has therefore not run the PostgreSQL schema check. Run the identity tests on a machine with
Docker before you merge a schema change ([§5.6](#56-add-a-table-to-the-identity-database)).

### 2.4 The console from source

```bash
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements-test.txt   # requirements.txt, plus pytest-xdist, Playwright and the test extras
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

You should see `Uvicorn running on http://0.0.0.0:17480` (`server.host` is every interface by default; `DRISHTI_CONSOLE_HOST=127.0.0.1`
for this machine only). `drishti-console/requirements.txt` holds FastAPI, Uvicorn, Jinja2, httpx, PyYAML, Markdown, pytest,
`cryptography`, numpy, pandas, pyarrow and deltalake (the last two for `lake build` and `lake demo`, so the console's Python is also the
tools' Python), boto3 for the Bedrock AI provider and, optionally, `brotli`. Without uv, `python3 -m venv drishti-console/.venv` and
`drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt` do the same where the system Python has `ensurepip`.

For Calc (Python on a view, `Alt+C`), install its runtime once: `tools/fetch-pyodide.sh` (about 340 MB downloaded to
`~/.cache/drishti/`, 53 MB kept; see [PYTHON_CALC.md](PYTHON_CALC.md#12-installing-the-python-runtime)). Without it the console runs
as before, `GET /healthz` says `"pythonRuntime": "not installed: run tools/fetch-pyodide.sh"` and the Calc panel says how to install it.

The console reads `drishti-console/config/application.yaml`, then `drishti-console/config/application.local.yaml` if present
(git-ignored), then environment variables, then `--key=value` arguments:

```bash
# three ways to move the console to port 17481
DRISHTI_CONSOLE_PORT=17481 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
DRISHTI_CONSOLE__SERVER__PORT=17481 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py --server.port=17481
```

`DRISHTI_CONSOLE__A__B` sets any key `a.b` (the prefix is `DRISHTI_CONSOLE__`, double underscores separate the levels). Named
variables such as `DRISHTI_CONSOLE_PORT` and `DRISHTI_BACKEND_URL` exist only where the YAML says `${NAME:default}`. Against a server
with sign-in on, the console needs the same `DRISHTI_TOKEN_SECRET` and `DRISHTI_AUTH_ENABLED=true` (the run configuration **Drishti
console** sets them); without them every call answers `401 DRS-5010` and the console's log says `business date unavailable: DRS-5010
missing bearer token`.

### 2.5 Running the server from source

Build the jar, then start it from the repository root (the server finds `./config/packs` and `./data` relative to the working
directory):

```bash
./mvnw -q package -DskipTests -pl drishti-server -am
DRISHTI_DEMO_ENABLED=false DRISHTI_STUDIO_SAVE=true \
  java -jar drishti-server/target/drishti-server-2.9.2-exec.jar
```

Server settings follow the same precedence as the console: `drishti-server/src/main/resources/application.yaml`, then
`./application.local.yaml` (git-ignored; imported by `spring.config.import`), then environment variables, then `--key=value`
arguments. For example `--drishti.live.frame=100ms` or `--server.port=18481`. The run configurations in `run-configs/` carry the
settings of each way of starting it (sign-in on with the development admin, the demo source on or off, the QA profile on its own ports
and data folder); [IDE_GUIDE.md](IDE_GUIDE.md) lists them.

The development loop that works best:

| You changed | Do |
|---|---|
| A Sutra under `config/packs/*/sutras/` or a site Sutra directory | Nothing: `drishti.rachana.hot-reload` reloads it; an invalid edit keeps the last good version |
| A connector file in `config/connectors/` | Nothing: the folder is watched and only that connector restarts |
| A BI file under `config/bi/` | Nothing: the folder is watched (`drishti.bi.watch`, `BiFolder`); a changed file becomes the next version, one that does not parse keeps its last good version and is listed as a problem |
| Java code | Rebuild the module and the server jar (`./mvnw -q -o -pl drishti-server -am package -DskipTests`), restart the server |
| `pack.yaml` or anything in a pack folder | Nothing, usually: the packs folder is watched (`drishti.packs.watch`, `PackReloader`) and the running packs are reconciled with it; a change that cannot be applied live keeps the last version running, is refused with the reason in the log and on Admin → Packs, and is tried again by itself |
| The demo lakes | `drishti.py lake demo` again: a running server is told (or notices a rebuilt Delta date by itself), so no restart |
| A console template | Reload the page |
| Console Python, or anything under `drishti-console/web/static/` | Restart the console (the asset fingerprint is computed at start-up; see [§8](#8-common-pitfalls)) |

### 2.6 The drill: `tools/drill.sh`

"Drill it" is how work reaches `main`. The script verifies everything, and only then publishes:

```bash
tools/drill.sh
```

Options: `--docs` (the light drill, below), `--full` (the full drill even when only docs changed), `--no-push` (verify only: no push,
no merge, and it may run on any branch). `DRILL_MVN_ARGS="..."` adds Maven arguments to both Java runs (for example
`-Ddrishti.test.forks=1` to run the test classes serially). The script prints `drill: mode = full` or `drill: mode = docs`, then each
stage's time, then a summary. It is costly (it runs everything): run it once per wave or release, and targeted tests in between.

**Which mode.** The commits since `origin/develop` decide: when every changed file is under `docs/` or `tools/docs/`, is a `*.md` file
or is a `help.yaml`, the drill runs in docs mode by itself; otherwise it runs in full mode. `tools/drill.sh --docs` on anything else is
refused, with the files that are not documentation listed.

**Full mode**, in order (it stops at the first failure, `set -euo pipefail`):

1. Refuses to run unless the current branch is `develop` (`drill: must be on develop`; not checked with `--no-push`) and the working
   tree is clean (`drill: commit your changes first`).
2. `python3 tools/license_headers.py`: every file carries the copyright header.
3. The whole Java build, every test and every rule, offline, on **Java 21** (`JAVA21_HOME`, default
   `/usr/lib/jvm/java-21-openjdk-amd64`), the production runtime, containers included, after `mvn clean` (`./mvnw -q -o clean verify`:
   nothing an earlier build or an interrupted run left in `target/` is built on). It builds the jar the console tests start. Test
   classes run in parallel JVM forks inside each module (`drishti.test.forks`, 4 by default, set in the root `pom.xml`; each fork is
   reused for many classes). Anything a test writes at a fixed path carries `${surefire.forkNumber}` (see `drishti-server/pom.xml`);
   Testcontainers and the in-test servers use free ports. A test must not clear or change a JVM-wide setting for the classes that follow
   it in the same fork.
4. Then three jobs at the same time, each with its own log in `<data dir>/drill/` (`java25.log`, `console.log`, `tools.log`) and its
   own timing; all must pass, and the tail of each failed log is printed:
   - **Java 25**, the forward-compatibility run (`JAVA25_HOME`; skipped with a message when that JDK is absent): the same tests again,
     with Docker hidden so the container tests (already run on 21) are skipped. It runs in a **copy of the committed tree** (a
     `git archive` of `HEAD` into a temporary directory, removed afterwards) with its own `target/` directories, and two test forks.
   - **The console suite**, `pytest -n <min(4, cpus/2)> --dist loadfile drishti-console/tests`: pytest-xdist workers each take whole
     test files, so a browser file keeps its module and session fixtures. Every live server and console in the tests starts on ports
     offset by the worker, in the worker's own scratch directory; the stand-in server object's state is put back after each test
     (`conftest.py`). If it fails, the failed tests (and only those, read from the run's own `FAILED`/`ERROR` summary, since
     `pytest.ini` turns the pytest cache off) run once more, serially, one file at a time and each file under a 20-minute limit (a
     browser that dies mid-test cannot block the drill): a test that passes the second time is printed as `FLAKY` and appended to
     `<data dir>/drill/flakes.log` with the commit; a test that fails twice, a file that hits the limit, or a run that failed without a
     failed test to re-run (a collection error), stops the drill. The Java 25 run re-runs a failed test once too
     (`-Dsurefire.rerunFailingTestsCount=1`): it shares the machine with the console suite, and surefire reports such a test as a flake.
   - **The Python tool tests**: every generator that supports `--check` (generated pack content must match what the generator writes
     now), the `unittest` suites of `tools/` (`samplegen`, `packreg`, `test_sutragen`, `test_ingest_jsonl`, `test_drishti_cli`,
     `test_pack_bundle`, `test_no_hidden_names`, `test_packmake`, `test_pack_authoring`, `test_cli_extras`, `test_pack_deploy`,
     `test_cli_bi`, `test_cli_connectors`, `test_cli_loads`, `test_pack_form`, `test_schemapack`, `tools/testdata/test_testdata.py`),
     and, if `uv` is installed, the lake tests with `deltalake`, `pyarrow` and `pyyaml` supplied by `uv run --with`
     (`samplegen/test_layout.py`, `lake/test_maintain.py`, `lake/test_demo.py`), each under a 10-minute `timeout`. (Tools that start
     worker processes after pyarrow or deltalake have started threads must spawn them, never fork: `bulk_trades.py` uses a `spawn`
     pool.) `tools/lake/test_build.py` is not in the list: run it by hand
     (`cd tools/lake && ../../drishti-console/.venv/bin/python -m unittest test_build`).
5. Pushes `develop`, fast-forwards `main` to `develop` (a fast-forward-only merge), pushes `main`, and returns to `develop`. It prints
   `drilled (full): <the last commit>`.

**Docs mode** runs the licence check, the documentation console tests (`test_docs_*.py`, `test_help*.py`, `test_guide_images.py`,
`test_examples.py`) and the Python tool tests, concurrently, then the same push and merge. No Java build, no browser tests.

The drill removes its own temporary files on exit. The console suite needs `pytest-xdist` (in `requirements-test.txt`). The inotify
watch limit of a loaded desktop (`fs.inotify.max_user_watches`) can make the Sutra hot-reload tests fail in any mode: that is the
machine, not the drill.

### 2.7 One config, several instances of the same connector

A connector is a *named instance* of a plugin, one file in `config/connectors/<name>.yaml` (the file name is its name;
[CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)). You may run as many instances of a plugin as you like; each has its own
settings, its own `source-name` (shown in provenance), its own entry in Admin → Health and in `GET /api/v1/sources`, and its own cache.

**Several slices of one kind: a union.** The shipped trading pack is the example: three `delta` connectors, `trade-endur`,
`trade-calypso` and `trade-murex`, each reading one source system's table (`table.trade: trade_endur`, …) and each marked
`union: "true"`. A union serves one kind **together**: a read of `trade/MX-20000001` asks all three (ids are unique across them), a
search or a type-ahead merges their hits, labelled by source, and a BI table over `trade` reads the three tables as one
(`source.SourceRouter`, `bi.engine.SourceGateway.plugins`; tests: `UnionConnectorsTest`, `UnionTableTest`). On the demo lakes:

```
$ drishti.py connector test trade-endur --server http://localhost:18480 --token-file ~/drishti-token
trade-endur (delta): reachable, health UP (engine: native), 14 ms
  kind   business date  rows  note
  trade  2026-10-16     5000
  trade  2026-10-15     5000
  trade  2026-10-14     5000
```

and `GET /api/v1/sources` lists `trade-calypso`, `trade-endur`, `trade-murex` (and the derived `trade-pages`) as four sources. Adding a
fourth source system is one more file with `union: "true"` and its own table; no pack changes.

**Several stores of one kind, asked in turn.** Without `union`, instances serving one kind are asked one after another and the first
that holds the entity answers. Two `file` connectors split by age, with the kind's `routes:` entry deciding which is asked first:

```yaml
# config/connectors/recent-files.yaml: the last weeks, written nightly by an export job
plugin: file
kinds: [trade]
settings:
  root: ${FEEDS_RECENT:./data/feeds/recent}
  domain: trading
  lookback-days: 10
  rescan-seconds: 30
  source-name: recent-feed
  stale-after: 2d
```

```yaml
# config/connectors/archive-files.yaml: older months, a different folder
plugin: file
kinds: [trade]
settings:
  root: ${FEEDS_ARCHIVE:./data/feeds/archive}
  domain: trading
  lookback-days: 400
  rescan-seconds: 300
  source-name: archive-feed
```

with `drishti.sources.routes.trade: recent-files` in the site configuration (or a pack's `routes:`). Other plugins follow the same
shape: a Delta connector on S3 (`root: s3a://…`, `s3.endpoint`, `s3.access-key: ${LAKE_ACCESS_KEY}`), a JDBC connector in table mode
(`url`, `user`, `password: ${PG_PASSWORD}`, `table: orders.entities`) or in query mode (`query.<kind>` and `ids.<kind>` SQL), a Kafka
connector in `mode: ticks` (it only pushes changes to open views; the documents come from the dated stores) or `mode: state`.

**How a kind is routed across them.**

* `kinds:` says which kinds an instance serves. Without it, an instance serves what its plugin reports (a `file` or `kafka` instance
  with no `kinds:` serves **every** kind, so give them `kinds:`; a `jdbc` query-mode instance serves the kinds that have a
  `query.<kind>`).
* For a read of `trade/MX-1` Drishti makes a list: the kind's `routes:` entry first (`trade: recent-files`), then `default-route`
  (`demo`, when it runs), then every other running instance that serves `trade`, in a stable order kept across restarts and reloads. For
  a picked date, dated stores (file, Delta, JDBC) go before undated ones; for Live, live ones (Kafka) go first. Union members are not in
  this list one after another: they are asked together, as one.
* The list is asked one by one and the first store that holds the entity answers. **A store that holds the date is authoritative for
  it**: an entity its file for that date does not list is *not held*, and the stores behind it are not asked
  (`DRS-1001 recent-files holds trade for 2026-10-01 and does not list trade/MX-0`). Only a date it does not hold passes on to the next
  store. So keep the dated stores of one kind **disjoint by date**: here `recent-files` holds the last 10 days, `archive-files`
  anything older.
* A store that **fails** (database down, service error) stops the read with `DRS-1003 <name> failed reading …`; it does not fall
  through to another store's data.

What a server answered with the two file connectors above (captured in an earlier release, when the same two connectors were defined
in the server's configuration; the router is the same for files), the files `feeds/recent/trading/2026-10-01/trade.jsonl` (`MX-1`) and
`feeds/archive/trading/2026-06-30/trade.jsonl` (`MX-0`):

```
GET /api/v1/entities/trade/MX-1/raw?asOf=2026-10-01  -> 200, "source":"recent-feed"
GET /api/v1/entities/trade/MX-0/raw?asOf=2026-06-30  -> 200, "source":"archive-feed"   (recent-files does not hold that date)
GET /api/v1/entities/trade/MX-0/raw?asOf=2026-10-01  -> 404 DRS-1001 recent-files holds trade for 2026-10-01 and does not list trade/MX-0
```

**What happens when an external system is not there.** The server **starts** (`/actuator/health` is `UP`); `GET /api/v1/admin/health`
says `DEGRADED` and each connector's health is its own: a local folder or lake reads `UP`, a missing lake folder `DOWN: cannot reach
<root>/<domain> (engine: native)`, a JDBC connector `DOWN: <driver message> (reconnecting)` until the database returns, a Kafka
connector `UP` with no data (its consumer retries), and a connector that cannot even start (a Delta lake on an unreachable S3
endpoint) is listed under `failedToStart` and retried by itself ([CONNECTOR_FILES.md, retries](../connectors/CONNECTOR_FILES.md#a-connector-that-fails-to-start-or-loses-its-connection-is-retried)).
One `DOWN` connector does not take the others with it.

The deprecated form, every connector under `drishti.sources.connectors` in a site file loaded with
`--spring.config.additional-location`, still works (flagged in the log and on Admin → Connectors; it overrides a file setting by
setting); move each block to its own file ([CONNECTOR_FILES.md, section 11](../connectors/CONNECTOR_FILES.md#11-moving-off-applicationyaml-and-the-old-data-source-override)).

---

## 3. Project rules

Some rules are enforced by tests, so a plain `./mvnw verify` fails when they are broken. Others are conventions that reviews hold to.
Both kinds are listed here.

| Rule | Enforced by |
|---|---|
| Every source file carries the copyright header | `LicenseHeaderTest`, `tools/license_headers.py` (drill, CI) |
| No non-UI source file over 1500 lines | `SourceFileSizeTest`; `drishti-console/tests/test_assets_policy.py` for console Python |
| One-way module dependencies, a Spring-free SPI, controllers only in `server`, no field injection, no `Serializable` | `ArchitectureRulesTest` |
| No `synchronized` around blocking I/O (virtual threads) | `NoBlockingUnderSynchronizedTest` |
| Front end vendored: no CDN, no external URL, no inline script, handler or style | `drishti-console/tests/test_assets_policy.py`, the console's Content Security Policy |
| Phone: no sideways scroll, targets of at least 44 px | `test_page_widths_browser.py` and the pages' own browser tests |
| No hidden file or folder of our own; everything written at run time under the data folder | `tools/test_no_hidden_names.py` |
| Generated files match their generators | `--check` in the drill |
| Sutras shown in docs and pack guides are valid | `DocumentedSutrasTest` |
| Every pack Sutra loads without problems | `PackSutrasTest` |
| Warm views stay fast | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99` |
| Object-oriented, concurrent, configuration-driven code; virtual threads; no SQL in code; schema files per database; git author only | Review (this section) |

### 3.1 The copyright header

Every file whose suffix is `.java`, `.py`, `.js`, `.css`, `.html`, `.yaml`, `.yml`, `.xml`, `.md`, `.properties` or `.sh` must contain
`Copyright (c) 2026 Ashutosh Sinha` within its first 2000 characters. Build output, `.git`, `.venv`, `vendor`, `recorded`,
`__pycache__`, `.mvn`, `.idea` and `docs/requirements` are skipped, as are `mvnw`, `mvnw.cmd` and `LICENSE`. JSON files cannot hold
comments and are exempt. SQL files carry the header in `--` comments by convention.

Check, then insert what is missing:

```bash
python3 tools/license_headers.py          # lists "missing <file>" and exits 1 if any file lacks it
python3 tools/license_headers.py --fix    # inserts the header and prints "fixed <file>"
```

The header text is `config/license-header.txt`, written in the comment style of each suffix: `/* … */` for Java, JavaScript and CSS;
`#` for Python, YAML, properties and shell (after a `#!` line if there is one); `<!-- … -->` for XML and Markdown (after an `<?xml`
line); `{# … #}` for Jinja templates (`.html`). A Markdown document therefore starts like this one.

### 3.2 File size

`SourceFileSizeTest` fails when any `.java`, `.py`, `.yaml`, `.yml` or `.xml` file outside `drishti-console/web/` has more than 1500
lines (`MAX_LINES = 1500`). UI templates, styles and scripts under `drishti-console/web/` are exempt. When a class approaches the
limit, split it by responsibility (the banking generator, for example, is a dozen focused modules under `tools/packgen/banking/`).

### Error Prone and Spotless

- **Error Prone** checks every compile. A finding at error level stops the build with the check's name, for example
  `[ReturnValueIgnored] Return value of 'apply' must be used`. Fix the code. If the finding is intended, add
  `@SuppressWarnings("ReturnValueIgnored")` on the smallest element, with a comment saying why. Warnings (missing `@Override`, Javadoc
  summaries) do not fail the build but are worth fixing when you touch the file.
- **Spotless** runs at `verify`. On a failure it prints the diff; `./mvnw -o -q spotless:apply -pl <module>` fixes unused imports,
  trailing whitespace, missing final newlines and tabs. Run it before every commit of Java code.

### 3.3 Architecture rules

`drishti-it/src/test/java/com/ash/drishti/it/ArchitectureRulesTest.java` checks the compiled main code (tests are excluded) with
ArchUnit:

| Rule | Meaning |
|---|---|
| `apiIsSpringFree` | Nothing in `com.ash.drishti.api..` may use `org.springframework..`. A plugin must not need Spring. |
| `apiDependsOnNothingInternal` | `api` may not use `common`, `rachana`, `engine` or `server`. |
| `lowerLayersDoNotSeeEngine` | `common`, `rachana`, `inference`, `graph` and `identity` may not use `engine` or `server`. |
| `engineDoesNotSeeServer` | `engine` may not use `server`. |
| `controllersOnlyInServer` | `@RestController` classes live only in `com.ash.drishti.server..`. |
| `noFieldInjection` | No field is annotated `@Autowired`. Use constructor injection. |
| `aiLayerDependsOneWay` | Nothing in `com.ash.drishti.ai..` may use `engine`, `server`, `identity`, `rachana` or `packs`: the AI layer sees only the SPI and `common`. |
| `noSerializable` | No class implements `java.io.Serializable`, except enums, throwables and JPA `@Embeddable` keys (JPA requires composite keys to be serialisable). |

Tests may use `@Autowired` fields (`@Autowired MockMvc mvc;`), because test classes are not analysed.

### 3.4 Object-oriented, concurrent and configuration-driven

- **Beans per module.** Each module contributes its beans from its own `@Configuration` class (`EngineConfiguration`,
  `RachanaConfiguration`, `IdentityConfiguration`, `SecurityConfiguration`, `BiConfiguration`, `MqConfiguration`, …).
  `DrishtiApplication` imports them. Domain classes take their collaborators in the constructor and are plain Java, so they unit-test
  without Spring (ADR-001).
- **Configuration, not constants.** Every limit, timeout, path and switch is a property under `drishti.*`, bound to an immutable
  `@ConfigurationProperties` record whose compact constructor supplies the defaults (`LiveProperties`, `SearchProperties`,
  `GovernanceProperties`, `QueryProperties`). Industry vocabulary belongs in packs, never in core code (ADR-010): no attribute name such
  as `TRADE_ID` appears in Java. The product name and legal notices are configuration too (`drishti.branding.*` on the server, `ui.*` in
  the console): a message names the product with `Branding.sqlName()`, `Branding.netName()` or `Branding.name(fallback)`, a template
  with `{{ PRODUCT }}`, a script with `document.body.dataset.product`; `BrandingLiteralTest` and `tests/test_branding_guard.py` fail on
  a literal.
- **Virtual threads.** `spring.threads.virtual.enabled: true` runs every request on a virtual thread. Blocking I/O (a source read, a
  link fan-out) is fine and cheap. `EngineConfiguration` provides `drishtiVirtualExecutor` (a thread per task) for fetches and SSE
  writers, `drishtiBindPool` (a `ForkJoinPool`, `drishti.engine.bind-parallelism`, 0 = one per core) for CPU-bound panel binding, and
  `drishtiFrameScheduler` (two platform threads) for live frames.
- **Do not hold a monitor across I/O.** Guard I/O with a `ReentrantLock`, not `synchronized`, so a blocked virtual thread releases its
  carrier. The code says so where it matters, for example in `TopicHub` (`a ReentrantLock: the upstream subscribe may do I/O, and
  synchronized would pin`), `FileAuditLog` and the BI store's single-flight builds (`bi.engine.Layers`).
- **Thread safety is the default expectation.** `SourcePlugin` implementations are called concurrently from many virtual threads.
  Shared state is either immutable and swapped whole (`HitIndex` keeps an immutable list in an `AtomicReference`; `PackStateStore` keeps
  an immutable snapshot map that readers use lock-free), or guarded by a lock for writers only. Each class says in its Javadoc whether
  it is thread-safe. Concurrency has its own tests (`HitIndexConcurrencyTest`, `UserServiceConcurrencyTest`, `TopicHubTest`,
  `LiveStreamSlotsTest`).
- **One exception type with a stable code.** Code that crosses a module boundary throws `DrishtiException(ErrorCode, message)`.
  `ApiExceptionHandler` turns it into RFC 7807 `problem+json` with `code: "DRS-nnnn"`. Codes are grouped by first digit (1 sources and
  data, 2 Sutra, 3 inference, 4 engine and graph, 5 API, 6 identity, 7 collaboration, 8 embedding, 9 BI) and are never reused; every code is in
  [API_GUIDE.md](API_GUIDE.md) and a test keeps that table in step with `ErrorCode`.
- **Imperfect data never fails a view.** A panel whose data does not fit is returned empty ("No data available"), never an exception
  (`Binder.bind`, `Emptiness`, `ImperfectDataTest`).
- **The Rachana-EL is closed.** Functions must be pure, total (never throw on odd input) and cheap; adding one is an amendment to
  ADR-003.

### 3.5 Data access: no SQL in code

The identity database (`drishti-identity`) is reached only through Spring Data JPA:

- Repositories are interfaces nested in `db.IdentityRepositories` and use **derived queries only**
  (`findBySubjectOrActorOrderByIdDesc`), never `@Query` strings or JDBC.
- The schema is two files, `drishti-identity/src/main/resources/db/schema-sqlite.sql` and `db/schema-postgres.sql`. `IdentityDatabase`
  applies the one for the configured URL at every start. Every statement is idempotent (`CREATE TABLE IF NOT EXISTS`), so the file *is*
  the schema: there are **no migrations**.
- Hibernate never changes the schema. On PostgreSQL it **validates** the entities against it (`hibernate.hbm2ddl.auto: validate`), so a
  mismatch stops start-up. SQLite types columns by value, so the check cannot apply there; the identity contract tests round-trip every
  column on both databases instead.

The BI engine's SQL is not hand-written either: `bi.engine.SqlEmitter` and `SqlCompiler` emit parameterised DuckDB SQL from the plan
(values are always parameters; names are checked against the model and quoted by `Dialect.quote`), and `QueryEngineTest` proves that
names and values from a request never change the SQL's shape.

### 3.6 The front end is vendored

The console uses Bootstrap, Bootstrap Icons, ECharts (and ECharts GL), CodeMirror (with its Python mode), Vega and Vega-Lite (the
`vega-lite` visual and a Python visual's output) and Graphic Walker (Calc's `drishti_bi.walk`, in a sandboxed frame), copied under
`drishti-console/web/static/vendor/`; `vendor/VENDOR.md` lists each library with its version, licence, size and whether the repository
holds it. FINOS Perspective, DuckDB-Wasm and Apache Arrow JS were removed in 2.3.0 (wave B): **the servers compute every number and the
browser draws**, so no BI page loads WebAssembly (a 2.1 report's `perspective-*` visuals are drawn as Drishti's own grid of the server's
answer). Pyodide, for Calc only, is installed by script, not committed (below). There is no npm, no build step and no CDN (ADR-006).
The Content Security Policy in `drishti-console/core/app.py` is:

```text
default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self';
connect-src 'self'; frame-src 'self'; worker-src 'self'; frame-ancestors 'self'
```

Two files have their own, narrower policy. Calc's worker, `/static/js/calc-worker.js`, is served with
`default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src <host>/static/ <host>/pyodide/`, so it alone may compile
WebAssembly (Pyodide) and it can fetch nothing but static files and the runtime (never an `/api` route, which would carry the user's
session). Calc's Graphic Walker page, `/static/calc/walker.html`, needs `unsafe-eval` and inline styles for the library, so it is a
sandboxed frame (`sandbox allow-scripts`, no same-origin) with `connect-src 'none'`: the rows it is given by message cannot leave it.
For Pyodide, `tools/fetch-pyodide.sh` downloads a pinned release, checks its SHA-256 and unpacks what Calc needs into
`drishti-console/web/static/vendor/pyodide/` (git-ignored); the console serves it at `/pyodide/<version>/`
([PYTHON_CALC.md](PYTHON_CALC.md#12-installing-the-python-runtime)).

So templates may not contain inline `<script>` blocks, `on…=` handlers or `style=` attributes, and nothing may load from another origin.
`drishti-console/tests/test_assets_policy.py` checks all of this. Behaviour goes in a file under `web/static/js/`, wired by `data-*`
attributes; widths and colours come from classes and `data-*` values that a script applies (bars and gauges use `data-w`, see
`view.js`).

### 3.7 Git

- Work happens on `develop`; `main` only moves by fast-forward through the drill.
- Commits are authored as the repository owner (`ajsinha`). Do not add co-author or tool-attribution trailers.
- Commit messages say what changed for a user or an operator, in plain sentences (see `git log`).

---

## 4. How a request travels through the code

This section follows `TRD MX-20000001 <GO>` from the keyboard to the screen, then the live updates that follow, then a BI question
and its live push.

### 4.1 From the command line to a view

| Step | Where | What happens |
|---|---|---|
| 1 | `drishti-console/web/templates/terminal/_topbar.html`, `web/static/js/command.js` | The command line is a plain form (`action="/go"`). `command.js` adds the dropdown: it calls the console's `GET /api/suggest`, which calls the server's `GET /api/v1/command/suggest` (`CommandController.suggest` → `SuggestionService`), filtered by `Entitlements.filter`. With a union of trade connectors, the hits of all three are merged. |
| 2 | `drishti-console/routes/terminal_routes.py` `go()` | Text that looks like a search (`<MN> where …`, `order by`, `limit`) is redirected to `/s`. Otherwise `BackendClient.command()` posts `{"text": "TRD MX-20000001"}` to `POST /api/v1/command`. |
| 3 | `drishti-console/core/backend.py` `BackendClient._send` | Every call carries the caller (`X-Drishti-User`, and `Authorization: Bearer …` when sign-in is on) and the business date (`X-Drishti-As-Of`, from `core/asof.py`, which `AuthGate` in `core/app.py` sets from the `drishti_asof` cookie). An unreachable server becomes `BackendError(503, "DRS-5003", …)`. |
| 4 | `server.security.PathGuard`, `server.security.TokenFilter` | `PathGuard` first refuses a request line under `/api` or `/actuator` that does not spell its path plainly (`;`, a needless `%`-escape, a dot or empty segment: `400 DRS-5001`); filters decide on `RequestPaths.routed`, the decoded path that is served, never the raw URI. For every `/api/v1/**` request: with security off, the caller is `Principal.anonymous(X-Drishti-User)` with every role; with it on, the bearer token is verified (`TokenVerifier`: a session token signed with `DRISHTI_TOKEN_SECRET`, or a personal API token `drk_…`) or the answer is `401 DRS-5010`. The principal is a request attribute (`Principal.ATTRIBUTE`). |
| 5 | `server.api.AsOfResolver` | Gives any controller parameter of type `AsOf` the request's business date: `X-Drishti-As-Of`, else `?asOf=`, else the current business date (`BusinessDates.parse`). |
| 6 | `server.api.CommandController.command` | `CommandParser.parse` turns the text into an `EntityRef` (`trade/MX-20000001`). `Entitlements.requireOpen` checks the caller's roles and active packs (`403 DRS-5002`). If the entity exists (`SourceRouter.fetchAll`), the answer is its ref (`{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD",…}`); otherwise the text becomes a pick list (`SearchQuery.pick` + `StructuredSearch.run`), and exactly one match opens directly. An unreadable command is `400 DRS-4001`; a search that names a field the kind does not have is `400 DRS-4004` with suggestions (`no trade has a field 'NOTIONAL'; did you mean LEGS.CASH_FLOWS.NOTIONAL, LEGS.NOTIONAL?`). |
| 7 | `terminal_routes.go()` | Redirects (303) to `/v/trade/MX-20000001`, or to `/s?q=…` for a pick list. |
| 8 | `terminal_routes.view()` | `BackendClient.view()` calls `GET /api/v1/views/trade/MX-20000001`. |
| 9 | `server.api.ViewController.view` | `requireOpen` again, then `ViewPipeline.view(ref, asOf)` (timed as the `drishti.view` metric), then `Entitlements.restrict` (disables links the caller may not follow), then `RecentEntities.touch` (feeds "recent" in the dropdown). |
| 10 | `engine.ViewPipeline` | The pipeline (below) returns a `ViewModel`. |
| 11 | `terminal_routes.view()` | Splits the panels into `main` and `right` by `area`, renders `terminal/view.html`. Each panel is drawn by the `panel(p)` macro in `web/templates/_macros/panels.html`; `web/static/js/view.js` adds charts (vendored ECharts), tabs, F-keys and the raw-JSON drawer. |

### 4.2 Inside `ViewPipeline`

`ViewPipeline` (`drishti-engine`) documents itself as "command → fetch → classify → fingerprint → layout → link → bind". In code:

1. **Fetch.** `SourceRouter.fetch(ref, asOf)` picks the plugins that serve the kind (the kind's route, the union it belongs to, named
   connectors, `default-route`), tries dated sources first for a picked date, and gives up after `drishti.sources.fetch-timeout` (5s).
   No source holding it is `404 DRS-1001`.
2. **Match a Sutra.** `SutraMatcher.match(kind, data)` asks `SutraRegistry` for the highest-priority Sutra of the kind whose
   `match.where` expression holds for this document (for a trade: `SOURCE_SYSTEM` and `PRODUCT_TYPE`; `MX-20000001` matches the MUREX
   `irs-fixfloat` Sutra). None means inference alone.
3. **Fingerprint the shape.** `ShapeFingerprinter` hashes the document's structure (not its values). It is cached per (entity,
   generation, business date).
4. **Lay out.** `LayoutMerger.merge(sutra, data, kind)` merges the Sutra with inferred panels into an `EffectiveLayout`. Layouts are
   cached in Caffeine per (Sutra *object identity*, kind, fingerprint), so the cost of inference is paid once per shape, and a reloaded
   Sutra can never be served an older layout. A registry change clears the cache.
5. **Link.** `ReferenceCatalog.discover` finds fields that reference other entities (from the packs' `graph.fields` and
   `graph.id-patterns`: `COUNTERPARTY` → `counterparty`, `PORTFOLIO` → `book`, …), and line charts may name a `source` entity. All are
   fetched in parallel by `SourceRouter.fetchAll` within `drishti.graph.link-budget` (40ms); late ones show as pending.
6. **Bind.** Each panel is bound by `Binder.bind(panel, BindContext)` in parallel on `drishtiBindPool`. Expressions are compiled once by
   `ElCompiler` (Rachana-EL) and values formatted by `Formats` and toned by `Tones`. The strip, title and F-keys are bound the same way.
7. **Return** a `ViewModel(ref, mnemonic, title, strip, panels, keys, provenance, timings)`. `provenance` carries the layout label, the
   fingerprint, source, generation, `live` and business date; `timings` has `fetch`, `layout`, `links`, `bind` and `total` in
   milliseconds. On the demo lakes, `GET /api/v1/views/trade/MX-20000001` with `X-Drishti-As-Of: 2026-10-15` answered
   `"layout": "Sutra irs-fixfloat v1 + inference"` and `timings` `{"fetch": 2.95, "layout": 0.57, "links": 5.57, "bind": 3.01,
   "total": 12.09}`.

### 4.3 Live updates of a view

| Step | Where | What happens |
|---|---|---|
| 1 | `terminal/view.html` | `live.js` is loaded only when `vm.provenance.live` is true. A picked business date is a static snapshot: no stream. A Delta lake is not live (its documents change by day); live entities come from a stream (Kafka, ActiveMQ, RabbitMQ, Redis) or the demo source. |
| 2 | `web/static/js/live.js`, `web/static/js/channel.js`, `web/static/js/live-hub.js` | `live.js` subscribes `view:trade/MX-20000001` on `window.DrishtiChannel`. `channel.js` hands every tab's subscriptions to one hub per browser (`live-hub.js`, in a SharedWorker, or in a tab elected with a Web Lock that relays over a BroadcastChannel), which keeps **one** `EventSource` for the whole browser, to the console's `GET /api/channel?s=…`, and adds or removes subscriptions with `POST /api/channel/{cid}` instead of reconnecting. Workspace panes (iframes) share their parent's subscriptions. Tests: `tests/test_live_hub.py` (the protocol, in Node), `tests/test_live_tabs_browser.py` (Chromium). |
| 3 | `drishti-console/routes/api_routes.py` `channel()` | For each subscription it opens an upstream stream (`BackendClient.stream` → `GET /api/v1/views/{kind}/{id}/stream`; `alerts`, `monitor:<name>` and `mq:<id>` go to their own server streams), in **detached** tasks, and multiplexes everything into one SSE response as `{"ch": "<subscription>", "d": …}`. Frames are rewritten by `_view_event`: each patched panel is rendered to HTML with the same `panels.html` macro as first paint (charts stay data). A watchdog ends a channel nobody has read for five seconds; a comment every ~15s keeps proxies from closing it. |
| 4 | `server.api.StreamController.stream` | Takes a slot from `LiveStreamSlots` (cap `drishti.live.max-streams`), builds the initial view, and creates a `ViewStream`. A writer on a virtual thread sends a `view` event, then a `frame` event per frame, or a heartbeat comment (`drishti.live.heartbeat`, 15s). Each client has its own latest-wins `FrameMailbox`, so a slow client never slows others. |
| 5 | `engine.live.TopicHub` | One topic per live entity, shared by every view of it, holding a single source subscription (`SourceRouter.subscribe` → the plugin's `subscribe`). Ticks land in a latest-wins slot and are delivered at most once per frame (`drishti.live.frame`, 50ms). The subscription closes with the last listener. |
| 6 | `engine.live.ViewStream`, `PatchDiffer` | On a tick, the view is rebuilt (`ViewPipeline.build`) and `PatchDiffer.diff(before, after)` produces `Patch`es; they travel as a `Frame`. A deletion (`EntityDocument.deleted()`) is sent as one `deleted` patch instead, and nothing is rebuilt until the entity comes back (`restored`). |
| 7 | `live.js` | Applies the patches in place: strip cells get new text and tone, panels are swapped for the server-rendered HTML, charts move to new data, and changed values flash. A `deleted` patch greys the view under a "deleted at" banner. |

[LIVE.md](../architecture/LIVE.md) has the details: frames, coalescing, reconnects and the latency metrics.

### 4.4 A BI question: model query, DEL and SQL

Rūpaka, the BI engine, lives in `drishti-server` under `server.bi` and follows the model architecture of
[BI_MODEL_ARCHITECTURE.md](../architecture/BI_MODEL_ARCHITECTURE.md): **datasets** (one governed table each, every pack kind publishing
one automatically, `bi.layer.PackDatasets`), **data models** (datasets in a star, relationships, measures written in **DEL**, the
Drishti Expression Language, [EXPRESSIONS.md](EXPRESSIONS.md)), and **consumers** (reports, boards, notebooks, alerts, subscriptions,
exports, embeds, AI) each bound to one model. Every consumer asks a model the same way: a **model query** (MQ), a versioned JSON or YAML
question ([API_GUIDE.md](API_GUIDE.md), BI_MODEL_SPEC.md section 5). Drishti SQL ([SQL.md](SQL.md)) is a second front end onto the same
plan. Nothing is computed in the browser.

| Step | Where | What happens |
|---|---|---|
| 1 | `web/static/js/bi-consume.js` (a report, a board, Analyse) | A visual becomes one model query; the browser posts it to the console's `/bi/api/mq` (or `/bi/api/models/{id}/query`), which passes it to the server as the signed-in user. |
| 2 | `server.bi.mq.MqController` (`POST /api/v1/bi/mq`), `bi.sql.SqlController` (`POST /api/v1/bi/sql`) | Read on the model is checked (`BiAccess`), then `MqParser` (or `DsqlParser` for SQL) reads the question. |
| 3 | `bi.mq.MqService`, `MqValidator`, `MqCompiler` | Validates the question against the model (names, types, additivity, the currency guard, `DRS-91xx` problems with the field to fix), and compiles it to the engine's `QueryRequest`s: relative windows from the as-of date and the calendar, `top` as its own grouped request, totals per level for measures that do not add up. |
| 4 | `bi.plan.Planner`, `SecurityRewriter`, `Guards`, `Optimiser`, `TierPlanner` | The logical plan: each measure aggregated at its own grain, facts met on shared fields, the reader's row rules and masks applied **before** any aggregation or join (stage 4), guards (a join that would repeat rows is refused), then each node placed on a tier: the source (a SQL store that can aggregate), the console backend (Python nodes) or the Java tier. |
| 5 | `bi.plan.ResultCache`, `bi.engine.QueryService` | The answer cache, keyed by plan hash, security signature and the model data's **generation** (any load, refresh or live batch moves it, so a stale answer is never served). Then admission (server and per-user concurrency) and the smallest layer that can answer. |
| 6 | `bi.engine.Layers` | The BI store (`<data>/bi-store`): typed Parquet **columns** per table and business date, read once from the connectors (`ServerSourceGateway` → the union of trade connectors, a lake, a database) and **rollups** built from them; single-flight per partition. A data-load signal (`POST /api/v1/packs/{pack}/loads`, sent by `lake build --signal` and `lake demo`) clears the date's partitions; each partition also records the source data version it was read from (a Delta date's files), so a rebuilt lake is read again even without a signal. |
| 7 | `bi.engine.DuckDbEngine`, `SqlEmitter` | One in-process DuckDB runs the parameterised SQL over the layer's Parquet files, each query with a watchdog at the time limit; row and byte limits apply. |
| 8 | `MqService` | Writes the one answer shape: typed columns with stable ids, additivity and masks, the rows, the totals, and the meta (as-of, dates, layer, tables, masks, warnings, cache, time, the data generation). The console draws it with ECharts or Vega-Lite. |

`EXPLAIN` (an MQ with `explain: true`, or `EXPLAIN SELECT …` in SQL) answers the plan with the tier and time of each node.

### 4.5 Live BI: pushed, not polled

| Step | Where | What happens |
|---|---|---|
| 1 | `bi-consume.js` `follow()` | A live visual posts its model query to `/bi/api/mq/subscribe` (`POST /api/v1/bi/mq/subscribe`, `{query, throttle, deliver}`) and gets its first answer and a subscription id, then subscribes `mq:<id>` on the browser's one channel (section 4.3). A closed tab ends its standing queries (`DELETE …/mq/subscribe/{id}`, sent with `keepalive`); an unread one is dropped after `drishti.bi.mq.idle-close` (60 s). |
| 2 | `bi.engine.LiveTables` | For a dataset whose time mode is `live`, one in-memory table per live table, started on first use: it reads the kind once and subscribes to its entities through the connectors that push (a stream, the demo source); nothing in it is per reader. |
| 3 | `bi.engine.GenerationBus` | Every change of the data a model reads is published once, in order: a live batch with its change set, a data load or refresh for a business date (`backdated` when the date is past), a history sample. |
| 4 | `bi.mq.StandingQueries`, `StandingQueryRegistry`, `Maintainer` | Only the subscriptions on the model whose data moved are evaluated, grouped by (plan hash, security signature): one evaluation per group however many viewers share it, each delivered to its own throttled, latest-wins mailbox. A group is kept current by difference (sums and counts), by re-asking the touched groups, or on a tick when past the model's maintenance budget. |
| 5 | `MqController` `GET /api/v1/bi/mq/stream` → the console's channel | Server-sent `answer` events (at most one per throttle; the latest state wins) and `status` events. A visual's chip says **Live** while changes flow, **Listening** while it follows data that has not changed (a daily lake), and why a subscription was refused. |

---

## 5. Recipes

Each recipe lists every file you touch. New files need the copyright header (`python3 tools/license_headers.py --fix` inserts it); the
snippets below leave it out to save space.

### 5.1 Add a REST endpoint

**Goal:** `GET /api/v1/mnemonics` lists the mnemonics the caller may use; `GET /api/v1/mnemonics/{code}` gives one, or `404 DRS-4005`.
(There is no such endpoint today: on the demo stack `GET /api/v1/mnemonics` answers `404`.)

**1. Add the error code** to `drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java`, in its group (4 = engine and graph),
with the next free number. Never reuse a code.

```java
    BAD_SEARCH("DRS-4004", 400),
    MNEMONIC_NOT_FOUND("DRS-4005", 404),
```

**2. Write the controller** in `drishti-server/src/main/java/com/ash/drishti/server/api/MnemonicController.java`. Controllers live in
`server.api` (an ArchUnit rule), take their collaborators in the constructor, read the caller from the request attribute, and check
entitlements before doing anything:

```java
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.command.CommandsProperties;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The mnemonics the caller may type: code, kind and label, within the kinds the caller may open. */
@RestController
@RequestMapping("/api/v1/mnemonics")
public class MnemonicController {

    /** One mnemonic. */
    public record MnemonicInfo(String code, String kind, String label) {}

    private final Mnemonics mnemonics;
    private final Entitlements entitlements;

    public MnemonicController(Mnemonics mnemonics, Entitlements entitlements) {
        this.mnemonics = mnemonics;
        this.entitlements = entitlements;
    }

    @GetMapping
    public List<MnemonicInfo> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return mnemonics.all().entrySet().stream()
                .filter(e -> entitlements.mayOpen(p, e.getValue().kind()))
                .map(e -> new MnemonicInfo(e.getKey(), e.getValue().kind(), e.getValue().label()))
                .toList();
    }

    @GetMapping("/{code}")
    public MnemonicInfo one(@PathVariable String code, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        CommandsProperties.Mnemonic m = mnemonics.of(code)
                .orElseThrow(() -> new DrishtiException(ErrorCode.MNEMONIC_NOT_FOUND, "no mnemonic '" + code + "'"));
        entitlements.requireOpen(p, m.kind());
        return new MnemonicInfo(code.toUpperCase(Locale.ROOT), m.kind(), m.label());
    }
}
```

Points to copy from the existing controllers:

- `@RequestAttribute(Principal.ATTRIBUTE) Principal p` is how every controller learns the caller.
- `entitlements.requireOpen(p, kind)` (roles and active packs), `requireAdmin(p)` for `/api/v1/admin/**`, and
  `mayOpen`/`filter`/`redact`/`restrict` to trim answers. Field masks have one source, `entitlements.redactor(p)`: pass it to every
  engine call that reads documents for the caller (`pipeline.view(ref, asOf, redactor)`, `records`, `ViewStream`, `impact.analyse`,
  `search.run`), never mask an answer after the fact. They throw `DrishtiException(FORBIDDEN, …)`, which becomes `403 DRS-5002`.
- Add a parameter of type `AsOf` when the answer depends on the business date; `AsOfResolver` fills it.
- Throw `DrishtiException`; never build error responses by hand. `ApiExceptionHandler` writes the `problem+json` with the code, and
  logs 5xx errors.
- Admin actions that change something are audited: `users.recordAudit(p.user(), "cache-purged", name, detail)` in `CacheController` is
  the pattern.
- A personal API token reads only, unless it was made with a scope that opens this `METHOD path`: a write endpoint adds its pattern to
  `drishti.security.token-scopes` in `application.yaml` (`TokenScopeGuardTest` fails when a write endpoint is in no scope).

**3. Test it with MockMvc** in `drishti-server/src/test/java/com/ash/drishti/server/MnemonicApiTest.java`. With security off (the
default), pass the user in `X-Drishti-User`. To test refusals, turn security on and mint a token with
`TokenVerifier.mint(user, roles, ttlSeconds)`, as `CacheAdminTest` does. Name the shipped packs the test needs: here
`counterparty-risk` (which brings `core`, `market-data` and `trading`), whose `credit-risk` role opens every kind and whose
`trader` role (from `trading`) cannot open netting sets:

```java
package com.ash.drishti.server;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Mnemonics are listed within what the caller may open; an unknown one is DRS-4005. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class MnemonicApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void listsResolvesAndRefuses() throws Exception {
        String risk = "Bearer " + tokens.mint("rita", List.of("credit-risk"), 300);
        mvc.perform(get("/api/v1/mnemonics").header("Authorization", risk)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='NSET')].kind").value(hasItem("netting-set")));
        mvc.perform(get("/api/v1/mnemonics/nset").header("Authorization", risk))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("NSET"));
        mvc.perform(get("/api/v1/mnemonics/NOPE").header("Authorization", risk))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4005"));

        String trader = "Bearer " + tokens.mint("tom", List.of("trader"), 300);   // the trading pack's trader role cannot open netting sets
        mvc.perform(get("/api/v1/mnemonics/NSET").header("Authorization", trader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/mnemonics")).andExpect(status().isUnauthorized());   // no token
    }
}
```

**4. Document it.** Add the endpoint to [API_GUIDE.md](API_GUIDE.md) and the new code to its error-code table, and to the code table in
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) (the console tests `test_docs_api_endpoints.py` and `test_docs_error_codes.py` fail when one is
missing). The OpenAPI document at `/api/docs` picks the endpoint up by itself.

**5. Call it from the console** (if a page needs it): add a method to `BackendClient` in `drishti-console/core/backend.py`, following
its pattern (`return await self._get("/mnemonics", ident)`), and a matching method to `FakeBackend` in
`drishti-console/tests/conftest.py`.

### 5.2 Add a source plugin

See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md): the SPI, testing with the testkit contracts and a
worked, contract-tested plugin end to end. Declare its settings (`settingSpecs()`, or `drishti/plugin-settings.yaml` for a shipped
plugin) so Admin → Connectors builds its form; if the BI store should notice when its data changes without a data-load signal,
implement `SourcePlugin.dataVersion(kind, date)` (the Delta plugin answers each date's file set). Then add the module to the plugin table
in §1.2 and in `README.md`.

### 5.3 Add a Rachana-EL function

**Goal:** `round(x, digits?)` rounds half up: `round(2.345, 2)` is `2.35`.

Adding a function changes the grammar: record it as an amendment to
[ADR-003](../architecture/adr/003-sutra-is-yaml-with-a-closed-expression-language.md). Functions must stay pure, total (odd input gives
`null`, never an exception) and cheap. (Rachana-EL lays out views; BI measures are written in DEL, which has its own catalogue,
`bi.expr.FunctionCatalog`, and its own guide, [EXPRESSIONS.md](EXPRESSIONS.md).)

**1. Register it** in `drishti-rachana/src/main/java/com/ash/drishti/rachana/el/Functions.java`. `ARITY` holds `{min, max}` arguments
(the parser checks it and says `unknown function '…'; known: …` for a name not in it), and `FUNCTIONS` holds the implementation:

```java
    static final Map<String, int[]> ARITY = Map.ofEntries(
            // … the existing entries …
            Map.entry("contains", new int[] {2, 2}), Map.entry("startsWith", new int[] {2, 2}),
            Map.entry("round", new int[] {1, 2}));

    private static final Map<String, ElFunction> FUNCTIONS = Map.ofEntries(
            // … the existing entries …
            Map.entry("round", Functions::round));

    /** Half-up rounding to 0-12 decimals; null for anything that is not a finite number. */
    private static Object round(java.util.List<Object> a, EvalContext c) {
        double v = Values.number(a.get(0));
        if (!Double.isFinite(v)) {
            return null;
        }
        int digits = a.size() > 1 ? (int) Math.max(0, Math.min(12, Values.number(a.get(1)))) : 0;
        return Values.normalise(java.math.BigDecimal.valueOf(v).setScale(digits, java.math.RoundingMode.HALF_UP).doubleValue());
    }
```

`Values.number` turns any value (a number, numeric text, a boolean) into a `double` (`NaN` when it cannot), and `Values.normalise` makes
whole results `Long`, so `round(7.5)` prints `8`.

**2. Test it** in `drishti-rachana/src/test/java/com/ash/drishti/rachana/el/ElTest.java`, with its `eval` helper over the test document
(`notional` is 50,000,000; the test document is the unit tests' own, so its names need not follow a pack's):

```java
    @Test
    void roundsHalfUpAndIsTotal() {
        assertThat(eval("round(2.345, 2)")).isEqualTo(2.35);
        assertThat(eval("round(7.5)")).isEqualTo(8L);
        assertThat(eval("round($.notional / 1000000)")).isEqualTo(50L);
        assertThat(eval("round($.nope, 2)")).isNull();
        assertThat(eval("round('abc')")).isNull();
    }
```

**3. Document it** in the functions row of [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) and in
[SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md).

### 5.4 Add a panel kind

See [PANELS.md (developing a panel kind)](PANELS.md#developing-a-panel-kind): the complete, tested walk-through (the `metric` tile end
to end: grammar, binder, console, workbench, export, accessibility and the guard tests).

### 5.5 Add a pack

See [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md): the complete walk-through (a help-desk pack from an empty folder), every
`pack.yaml` key, generators, signing and the tests that cover packs. Users and admins: [PACKS.md](PACKS.md). Give the pack's documents
UPPER_SNAKE attribute names, its samples as `samples/<kind>.jsonl`, its connectors as templates (the server writes the files), and its
Sutras a folder layout that suits it (folders are organisation only).

### 5.6 Add a table to the identity database

**Goal:** a `drishti_notice` table: site-wide notices that administrators switch on and off.

**1. The entity**, `drishti-identity/src/main/java/com/ash/drishti/identity/db/NoticeEntity.java`, in the style of `PackStateEntity`
(public fields, explicit column names):

```java
package com.ash.drishti.identity.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A site-wide notice ({@code drishti_notice}), shown while active. */
@Entity
@Table(name = "drishti_notice")
public class NoticeEntity {

    @Id
    @Column(name = "name", length = 64)
    public String name;

    @Column(name = "text", nullable = false)
    public String text = "";

    @Column(name = "active", nullable = false)
    public boolean active;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    public String updatedBy = "";
}
```

A composite key is an `@Embeddable` class (the one place `Serializable` is allowed); see `PreferenceEntity.Key`.

**2. The repository**, nested in `db/IdentityRepositories.java`. Derived queries only:

```java
    public interface Notices extends JpaRepository<NoticeEntity, String> {
        List<NoticeEntity> findByActiveTrueOrderByName();
    }
```

**3. Both schema files.** Add the table to **both** `db/schema-sqlite.sql` and `db/schema-postgres.sql`, idempotent, with matching
names. Follow the existing type mapping: SQLite `TEXT`, `INTEGER` for booleans, `TIMESTAMP`; PostgreSQL `VARCHAR(n)`/`TEXT`, `BOOLEAN`,
`TIMESTAMP WITH TIME ZONE`.

```sql
-- schema-sqlite.sql
CREATE TABLE IF NOT EXISTS drishti_notice (
    name       TEXT    PRIMARY KEY,
    text       TEXT    NOT NULL DEFAULT '',
    active     INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by TEXT    NOT NULL DEFAULT ''
);
```

```sql
-- schema-postgres.sql
CREATE TABLE IF NOT EXISTS drishti_notice (
    name       VARCHAR(64)  PRIMARY KEY,
    text       TEXT         NOT NULL DEFAULT '',
    active     BOOLEAN      NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64)  NOT NULL DEFAULT ''
);
```

There are no migrations. A **new table** is created on the next start of an existing installation. A **new column on an existing
table** is not added by `CREATE TABLE IF NOT EXISTS`; it needs its own idempotent statement in each file (and a release note), so prefer
new tables or nullable columns, and say in the release notes what an upgrade does. Hibernate validates the PostgreSQL schema at
start-up, so a column the entity has and the file lacks stops the server.

**4. The store**, `drishti-identity/src/main/java/com/ash/drishti/identity/NoticeStore.java`, in the style of `PackStateStore`: an
immutable snapshot for readers, a lock for writers, every change in a transaction and audited:

```java
package com.ash.drishti.identity;

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.NoticeEntity;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionTemplate;

/** Site-wide notices: readers see an immutable snapshot (lock-free); changes are serialised and audited. */
public final class NoticeStore {

    public record Notice(String name, String text, Instant updatedAt, String updatedBy) {}

    private final IdentityRepositories.Notices notices;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ReentrantLock writes = new ReentrantLock();
    private volatile List<Notice> active;

    public NoticeStore(IdentityRepositories.Notices notices, TransactionTemplate tx, AuditLog audit) {
        this.notices = notices;
        this.tx = tx;
        this.audit = audit;
        reload();
    }

    private void reload() {
        active = tx.execute(s -> notices.findByActiveTrueOrderByName().stream()
                .map(e -> new Notice(e.name, e.text, e.updatedAt, e.updatedBy)).toList());
    }

    /** The active notices; lock-free. */
    public List<Notice> active() {
        return active;
    }

    public void set(String name, String text, boolean on, String actor) {
        writes.lock();
        try {
            tx.executeWithoutResult(s -> {
                NoticeEntity e = notices.findById(name).orElseGet(NoticeEntity::new);
                e.name = name;
                e.text = text;
                e.active = on;
                e.updatedAt = Instant.now();
                e.updatedBy = actor;
                notices.save(e);
            });
            reload();
            audit.record(actor, "notice-set", name, on ? "on" : "off");
        } finally {
            writes.unlock();
        }
    }
}
```

**5. The bean**, in `IdentityConfiguration`:

```java
    @Bean
    public NoticeStore noticeStore(IdentityRepositories.Notices notices, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new NoticeStore(notices, identityTransactions, auditLog);
    }
```

**6. The contract test.** Add a test to `drishti-identity/src/test/java/com/ash/drishti/identity/IdentityStoreContract.java`. It runs
twice: `SqliteIdentityStoreTest` (always) and `PostgresIdentityStoreTest` (PostgreSQL 18 in Docker; skipped without Docker). The
contract's `schemaIsIdempotentSoAStartAgainstAnExistingDatabaseChangesNothing` already checks that a second start against the same
database works.

```java
    @Test
    void noticesRoundTripOnEveryDatabase() {
        NoticeStore notices = bean(NoticeStore.class);
        notices.set("maintenance", "Read-only from 18:00 NY", true, "drishti-dev-admin");
        assertThat(notices.active()).extracting(NoticeStore.Notice::text).containsExactly("Read-only from 18:00 NY");
        notices.set("maintenance", "", false, "drishti-dev-admin");
        assertThat(notices.active()).isEmpty();
        assertThat(bean(AuditLog.class).recent(10, "maintenance")).extracting(AuditLog.Event::action).contains("notice-set");
    }
```

Run it on both databases before you merge (Docker must be running for the PostgreSQL half):

```bash
./mvnw -q -o -pl drishti-identity -am test -Dtest='SqliteIdentityStoreTest,PostgresIdentityStoreTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### 5.7 Add a configuration property

**Goal:** `drishti.notices.max-length` (default 500), settable as `DRISHTI_NOTICE_MAX_LENGTH`, used by `NoticeStore` from the previous
recipe (apply that one first).

**1. A record** with its defaults in the compact constructor and each key documented as a `@param`, in
`drishti-identity/src/main/java/com/ash/drishti/identity/NoticeProperties.java`:

```java
package com.ash.drishti.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.notices.*}.
 *
 * @param maxLength the longest notice text an administrator may set
 */
@ConfigurationProperties("drishti.notices")
public record NoticeProperties(Integer maxLength) {

    public NoticeProperties {
        maxLength = maxLength == null || maxLength <= 0 ? 500 : maxLength;
    }
}
```

Boxed types (`Integer`, `Boolean`, `Duration`) let the constructor tell "not set" from a value. If a record has a second constructor,
mark the canonical one `@ConstructorBinding`, as `SearchProperties` does.

**2. Register it** on the module's configuration class: in `IdentityConfiguration`,
`@EnableConfigurationProperties(IdentityProperties.class)` becomes

```java
@EnableConfigurationProperties({IdentityProperties.class, NoticeProperties.class})
```

and inject it where it is used. The `noticeStore` bean takes it as one more parameter:

```java
    @Bean
    public NoticeStore noticeStore(IdentityRepositories.Notices notices, TransactionTemplate identityTransactions, JpaAuditLog auditLog,
            NoticeProperties noticeProperties) {
        return new NoticeStore(notices, identityTransactions, auditLog, noticeProperties.maxLength());
    }
```

and `NoticeStore` keeps the limit and checks it first in `set` (add the imports `com.ash.drishti.common.DrishtiException` and
`com.ash.drishti.common.ErrorCode`):

```java
    private final int maxLength;

    public NoticeStore(IdentityRepositories.Notices notices, TransactionTemplate tx, AuditLog audit, int maxLength) {
        this.notices = notices;
        this.tx = tx;
        this.audit = audit;
        this.maxLength = maxLength;
        reload();
    }

    public void set(String name, String text, boolean on, String actor) {
        if (text.length() > maxLength) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a notice is at most " + maxLength + " characters");
        }
        writes.lock();
        // … as before …
```

To add a key to an **existing** record instead, add a component to it. Every `new XxxProperties(...)` call in tests then needs the
extra argument (for example `ImperfectDataTest` constructs `RachanaProperties` directly).

**3. Put it in `drishti-server/src/main/resources/application.yaml`** with an environment override and a comment saying what it does.
The file has one `drishti:` block: add `notices:` inside it (for example after `identity:`), not a second `drishti:` key, which YAML
rejects as a duplicate:

```yaml
drishti:
  notices:
    max-length: ${DRISHTI_NOTICE_MAX_LENGTH:500}   # longest notice text admins may set
```

The default in the YAML and in the record must agree: the record's default applies when the key is absent (`SearchProperties` keys are
not in the bundled file at all).

**4. Document it** in [CONFIGURATION.md](../admin/CONFIGURATION.md), in the section for its prefix: key, default, meaning, and the
environment variable (the console test `test_docs_settings.py` fails when a key of `application.yaml` is missing there).

**5. Test it** where the behaviour is, with the property set in the test: `@SpringBootTest(properties = "drishti.notices.max-length=40")`,
or in the identity contract by adding `props.put("drishti.notices.max-length", "40");` in `open()` (40, so the previous recipe's
23-character notice still fits) and a test beside `noticesRoundTripOnEveryDatabase`:

```java
    @Test
    void aNoticeLongerThanTheConfiguredMaximumIsRefused() {
        assertThatThrownBy(() -> bean(NoticeStore.class).set("long", "x".repeat(41), true, "drishti-dev-admin"))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("at most 40");
    }
```

Then run `./mvnw -o -pl drishti-identity -am install` and `./mvnw -o test -pl drishti-server` (the server starts with the new property
bound).

**In the console** the pattern is the same YAML: add the key to `drishti-console/config/application.yaml` as
`${DRISHTI_SOMETHING:default}` and read it with `settings.get("section.key", default)`. Every console key can also be set as
`DRISHTI_CONSOLE__SECTION__KEY` without a named variable.

### 5.8 Add a console page

**Goal:** `/sources`, a page listing the connectors the server runs, their kinds and health.

**1. The route**, `drishti-console/routes/source_routes.py`. Pages call the server through `request.app.state.backend` with
`ident(request)`, turn a `BackendError` into a message, and render with `render`, which adds the signed-in user, packs, business date
and settings to the template context:

```python
"""Sources at a glance: every connector the server runs, the kinds it serves and its health."""
from __future__ import annotations

from fastapi import APIRouter, Request

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


@router.get("/sources")
async def sources(request: Request):
    try:
        data, error = await request.app.state.backend.sources(ident(request)), None
    except BackendError as e:
        data, error = {"sources": [], "failures": {}}, e
    return render(request, "sources.html", sources=data.get("sources", []), failures=data.get("failures", {}),
                  error=error, screen="sources")
```

**2. Register it** in `create_app` in `drishti-console/core/app.py` (import `source_routes` beside the other routers and
`app.include_router(source_routes.router)`), and add `"/sources"` to the `PROTECTED` tuple in the same file. **A path that is not in
`PROTECTED` is public** when sign-in is on. To list it in the top bar, add an entry to the menus in
`web/templates/terminal/_topbar.html`.

**3. The template**, `drishti-console/web/templates/sources.html`, extends `base.html` like the other terminal pages (the admin and
workspace templates are good models):

```jinja
{% extends "base.html" %}
{% block title %}Sources · {{ PRODUCT }}{% endblock %}
{% block body_class %}terminal{% endblock %}
{% block nav %}{% include "terminal/_topbar.html" %}{% endblock %}
{% block content %}
<div class="adm">
  <h1>Sources</h1>
  {% if error %}<p class="pnl-err" role="alert">{{ error.code }} {{ error.detail }}</p>{% endif %}
  <label for="srcFilter" class="visually-hidden">Filter</label>
  <input id="srcFilter" class="studio-in mono" placeholder="Filter by name or kind" data-src-filter>
  <div class="tbl-wrap"><table class="tbl">
    <thead><tr><th>Source</th><th>Kinds</th><th>Live</th><th>Health</th></tr></thead>
    <tbody>{% for s in sources %}<tr data-src-row="{{ s.name }} {{ s.kinds | join(' ') }}">
      <td class="mono">{{ s.name }}</td><td class="mono">{{ s.kinds | join(', ') or 'any' }}</td>
      <td>{{ 'yes' if s.live else 'no' }}</td><td class="mono">{{ s.health }}</td></tr>
    {% else %}<tr><td colspan="4">No sources.</td></tr>{% endfor %}</tbody>
  </table></div>
</div>
{% endblock %}
{% block scripts %}<script src="/static/js/command.js?v={{ ASSET_V }}"></script><script src="/static/js/sources.js?v={{ ASSET_V }}"></script>{% endblock %}
```

Jinja autoescapes, so values from the server are safe to print. `ASSET_V` is the asset version plus a fingerprint of `static/js` and
`static/css`: always add `?v={{ ASSET_V }}` to your own script and style URLs. A table must not scroll the page sideways at 390 px
(`tbl-wrap` scrolls it inside itself), and every control needs a 44 px target on a phone.

**4. The script**, `drishti-console/web/static/js/sources.js`. No inline script: the page wires behaviour by `data-*` attributes, and
the script is an IIFE in strict mode like the others:

```js
/* Sources page: filters the table as you type. */
(function () {
  'use strict';
  var box = document.querySelector('[data-src-filter]');
  if (!box) { return; }
  var rows = document.querySelectorAll('[data-src-row]');
  box.addEventListener('input', function () {
    var q = box.value.trim().toLowerCase();
    rows.forEach(function (r) { r.hidden = q !== '' && r.getAttribute('data-src-row').toLowerCase().indexOf(q) < 0; });
  });
})();
```

**5. The test.** The `client` fixture in `drishti-console/tests/conftest.py` builds the real app with a `FakeBackend` in place of the
server. Add the method your page calls to `FakeBackend`:

```python
    async def sources(self, ident=None):
        return {"sources": [{"name": "trade-endur", "version": "1.0", "kinds": ["trade"], "live": False, "search": True,
                             "reverseLookup": True, "health": "UP (engine: native)"},
                            {"name": "credit-store", "version": "1.0", "kinds": ["netting-set"], "live": False,
                             "search": True, "reverseLookup": True, "health": "UP (engine: native)"}],
                "failures": {}}
```

and a test file, `drishti-console/tests/test_sources.py`:

```python
"""The sources page lists connectors and their kinds, under the CSP."""


def test_sources_page_lists_connectors(client):
    r = client.get("/sources")
    assert r.status_code == 200
    assert 'data-src-row="credit-store netting-set"' in r.text
    assert "/static/js/sources.js?v=" in r.text
    assert "script-src 'self'" in r.headers["content-security-policy"]
```

Run the console tests:

```bash
drishti-console/.venv/bin/python -m pytest -q -p no:cacheprovider drishti-console/tests/test_sources.py -k connectors
drishti-console/.venv/bin/python -m pytest -q -p no:cacheprovider -n 4 --dist loadfile drishti-console/tests        # the whole suite
```

(`-p no:cacheprovider`: no `.pytest_cache` folder.) `test_assets_policy.py` will fail if the template gains an inline script, handler or
style, or an external URL.

#### Regenerating the favicon and app icons

The browser-tab icon, the home-screen icons and the app manifest's icons are all the logo mark (the amber braces and eye of
`web/static/img/drishti-mark.svg`), filling a navy tile with the eye's outline in white, so they read on light and dark tab bars. They
are generated, never drawn by hand, into `drishti-console/web/static/img/icons/`:

| File | Size | Used by |
|---|---|---|
| `favicon.ico` | 16, 32 and 48 px in one file | `/favicon.ico` (browsers ask for it on their own) and `<link rel="icon">` |
| `favicon.svg` | scalable | `<link rel="icon" type="image/svg+xml">` in `base.html`; modern browsers prefer it |
| `favicon-16.png`, `favicon-32.png`, `favicon-48.png` | as named | the `.ico`; 16 and 32 px use heavier strokes so the mark survives at tab size |
| `apple-touch-icon.png` | 180 px, square corners | iOS "Add to Home Screen" (iOS rounds the corners) |
| `icon-192.png`, `icon-512.png`, `icon-maskable-512.png` | as named | `manifest.webmanifest`; the maskable one keeps the mark inside the 80 % safe zone |

```bash
python3 tools/make_icons.py            # writes every file above (needs Inkscape and ImageMagick)
python3 tools/make_icons.py --check    # exit 1 when an icon is missing or favicon.svg is not what the script makes
```

Change the colours, the fill or the stroke weights in the script, never in the files. `tests/test_icons.py` checks that the files
exist, that `/favicon.ico` answers, and that the pages and the manifest link them.

#### Regenerating the element stylesheet

`<drishti-view>` (Drishti Elements, `docs/architecture/ELEMENTS.md`) styles its Shadow DOM with a **generated** sheet, not the
console's own: `drishti-console/web/elements/drishti-view.css` and `drishti-view.manifest.json`. It is the console's tokens, theme,
terminal, layout and gradients sheets with `:root`, `html` and `body` rewritten to `:host`, no `@font-face` (the element registers the
icon font with the document's `FontFace` API), and of Bootstrap Icons only the glyphs that `templates/_macros/panels.html`,
`templates/_macros/view.html` and the scripts that run inside the element name. Regenerate it after changing any of those sheets, a
macro, `bi.css` or the report styles, or an icon in such a script:

```bash
python3 tools/elements_sheet.py            # write the sheet and its manifest; prints size and glyph count
python3 tools/elements_sheet.py --check    # exit 1 when the committed files are stale
python3 tools/elements_sheet.py --icons    # which glyph is named where
```

The output is reproducible and its version is the first 12 hex digits of its hash (served at
`/embed/v1/elements/<version>/drishti-view.css`, cacheable for ever). `tests/test_element_sheet.py` fails when the committed sheet is
stale or a macro uses a glyph the sheet lacks: run the tool and commit the two files. A name that is not a Bootstrap Icons glyph makes
the tool fail and says where it is named.

**Writing element CSS: width rules become container rules.** Write responsive rules in the console's sheets as usual
(`@media (max-width: 640px) { ... }`). The generator rewrites every width-based `@media` rule into
`@container drishti (max-width: 640px) { ... }`, so an embedded view answers to the width of its **element** (the host element is a
size container, `container: drishti / inline-size`), not to the host page's viewport; `:host` inside such a rule becomes `.view` (a
container cannot style itself) and `vw` becomes `cqw`. Non-width media (`prefers-color-scheme`, `prefers-reduced-motion`, `print`,
`hover`, `pointer`) are kept. Do not write `@container` by hand in the console's sheets, and do not use `100vw` or `vh` for something
inside the element's tree where the element's own size is meant. Element-only rules (finger-sized controls, the About sheet's width)
live in `ELEMENT_ADDENDUM` in `core/element_sheet.py`. `test_element_sheet.py` fails if a width `@media` rule is left in the sheet.

---

### 5.8a The shared multi-select dropdown

`static/js/multiselect.js` (vendored with the console, no library) is the one multi-select the console draws: a `<details>` whose
`<summary>` shows "All", the value, or "3 selected: ABS, REPO, +1", and whose pop-up holds a search box, *Select all* / *Clear* and a
labelled group of **real checkboxes** (so form submission, keyboard and screen readers are the browser's own; 44 px rows on touch; theme
tokens in `css/controls.css`). Two callers: the query-by-form renderer marks every enum field with more than 3 values `data-ms`
(`terminal/form.html`; up to 3 stay a checkbox row, so the renderer decides by option count and no pack is hard-coded) and
`multiselect.js` wraps it on load, keeping the checkboxes' names and so the criteria encoding; and `controls.js` builds the
`multi-select` control from `window.drishtiModules.multiselect.decorate(details, {label, boxes, onChange})`. Inside `<drishti-view>` it is
one of the allow-listed enhancer scripts (`routes/embed_routes.py` `ENHANCERS`, `web/embed/assets.js`), loaded before `controls`.
Tests: `test_form_browser.py` (dropdown, search, keyboard, URL, phone), `test_controls_browser.py`.

### 5.9 Write a view enhancer (the `init(root)` contract)

A script that makes a rendered view interactive (sorting, charts, popovers) must work on the console's page and inside a
`<drishti-view>` Shadow DOM, from one copy of the code (docs/architecture/ELEMENTS.md, section 12). The contract:

- Wrap the code in `function init(root, options)`; `root` is `document` or a `ShadowRoot`. Query with `root.querySelector*` and
  `root.getElementById`, never `document.*` for view content; use `root.activeElement` and `root.elementFromPoint`.
- Watch for new panels with a `MutationObserver` on `root === document ? document.body : root`; listen for keys and events on `root`
  (never on `document` or `window` when root is a shadow root), so a host page's keys are untouched.
- Take everything environmental from `options`: `fetch(url, init)` and `url(path)` (the embed API differs from the console's paths),
  feature switches (`save`, `exports`, `guideKey`), callbacks (`open`, `reload`). No `location`, no `localStorage` keys that collide,
  no fixed positions computed against the window without the host's origin (`root.host.getBoundingClientRect()`).
- Read theme tokens from `getComputedStyle(root.host || document.documentElement)`; size with a window `resize` for the document and a
  `ResizeObserver` on `root.host` for a shadow root.
- Return `{ dispose }` (remove listeners and observers). Register `window.drishtiModules.<name> = { init }`; start `init(document)`
  unless `document.currentScript` has `data-manual`; publish console globals only in that case.
- Add the script to `ENHANCERS` in `routes/embed_routes.py` and to `boot` in `web/embed/enhancers.js` (and `ENHANCERS` in
  `web/embed/assets.js`); extend `test_embed_elements_browser.py` to prove it inside the element. The console's own browser tests must
  pass unchanged.

### 5.10 Retry and live-reload utilities

Two small, Spring-light pieces in `drishti-common` serve every area that keeps something running from files (packs, connectors, AI
providers, MCP servers, BI artifacts). Operator view: [OPERATIONS.md](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself).

**Retrying what failed** (`com.ash.drishti.common`, no Spring, virtual-thread friendly, locks are `ReentrantLock`, nothing blocks under
`synchronized`):

| Class | Use |
|---|---|
| `RetryPolicy.of(env::getProperty, "bi")` | reads `drishti.retry.*` then the area's `drishti.retry.bi.*` (`enabled`, `initial`, `factor`, `max`, `jitter`, `permanent-after`); `baseDelay(attempt)`, `delay(attempt, permanent, random)`; `looksPermanent(error)` classes refused credentials and bad settings |
| `RetryTracker` | the state of one failing thing: `failed(error, permanentHint)` plans the next try, `due()`, `retryNow()`, `succeeded()`, `status()` |
| `RetryBoard` | the failing things of one area by name: `failed(key, error, hint)` (logs failures 1, 2, 4, 8, ...), `succeeded(key)` (logs the recovery, returns true if it had failed), `due()`, `retryNow(key)`, `retryAllNow()`, `statuses()`, `forget(key)`, and a `Listener` for the audit log; `RetryBoard.describe(status, now)` is the JSON the Admin pages read |

The owner supplies the timer: call `board.due()` from a scheduler, a watcher pass or a request, try each key, and report the outcome
with `failed` / `succeeded`. Do the attempt outside any lock the board does not own. The console twin is `core/retry.py`
(`RetryPolicy.of(settings, "ai")`, `RetryBoard`).

**Following settings without a restart** (`LiveSettings`): a bean built from settings a pack contributes implements
`Runnable prepare(Environment candidate)`. The pack reloader calls it with a *candidate* environment (the current one with the new pack
settings in it); `prepare` binds, validates and builds the new state and returns the commit that swaps it in. Throw from `prepare`, never
from the commit: a throw refuses the whole reload and nothing has changed. The commit only assigns a `volatile` reference (or a
copy-on-write structure), so a reader sees the old state or the new one, never a mixture; take one local snapshot per call when you read
the state more than once. Examples: `ReferenceCatalog`, `SearchProperties`, `RoleCatalog`, `Formats`, `AboutCatalog`, `SourceRouter`,
`SourceRegistry` (plain plugins' settings).

## 6. Testing strategy

| Layer | Where | How | Examples |
|---|---|---|---|
| Unit | every module, `src/test/java` | JUnit 5 and AssertJ, plain constructors, no Spring | `DataNodeTest`, `ShapeFingerprinterTest`, `SearchQueryTest`, `DocumentDiffTest`, `FormatsTest`, `SutraParserTest`, `IdColumnTest` |
| Property-based | `drishti-rachana`, `drishti-engine` | plain JUnit 5 `@ParameterizedTest` over seeded generators (`SplittableRandom`/`Random`, base seed overridable with `-Ddrishti.test.seed`); failures print the case index, seed and input, with a greedy shrink | `ElTest` (random arithmetic), `ShapePropertyTest` |
| Concurrency | api, engine, identity, server | many threads on one object, then invariants | `HitIndexConcurrencyTest`, `TopicHubTest`, `UserServiceConcurrencyTest`, `LiveStreamSlotsTest` |
| Contract | `drishti-testkit` and subclasses | one abstract test class, one subclass per implementation | `DatedSourceContract` (Delta, Delta on S3, PostgreSQL table mode, Aerospike, Cassandra, ScyllaDB), `MessageSourceContract` and `BrokerOutage` (ActiveMQ, RabbitMQ), `IdentityStoreContract` (SQLite, PostgreSQL) |
| Spring and HTTP | `drishti-server` | `@SpringBootTest` + `@AutoConfigureMockMvc`, properties set per test class | `ApiTest`, `SecurityTest`, `CacheAdminTest`, `IdentityApiTest`, `WorkspaceApiTest`, `StructuredSearchTest`, `LiveTest` |
| Whole pipeline | `drishti-server` | `ViewPipeline` from the context, real packs | `ViewPipelineTest` (the finance pack's four mockups, value by value), `BankingPacksTest`, `DomainPacksTest`, `SamplePacksTest`, `ImperfectDataTest`, `BusinessDateTest` |
| Multi-source trades | engine, server | the union of connectors, read, searched and aggregated as one | `UnionConnectorsTest`, `UnionTableTest` |
| BI engine | `drishti-server` `bi.*` | a small generated lake (`TestLake`), the real DuckDB engine | `QueryEngineTest` (layers, rollups, caching, a data load, a rebuilt source date, row rules and masks for several readers, SQL injection attempts), `DatasetRefresherTest`, `LiveTablesTest`, `TimeIntelligenceTest` |
| Real services | plugins, identity | Testcontainers, skipped without Docker ([§2.3](#23-tests-that-need-docker)) | `PostgresIdentityStoreTest`, `AerospikeSourcePluginTest`, `RabbitMqOutageTest` |
| Repository rules | `drishti-it`, `tools/` | ArchUnit and file walks | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest`, `NoBlockingUnderSynchronizedTest`, `tools/test_no_hidden_names.py` |
| Documentation | rachana, server, console | the docs are parsed as code | `DocumentedSutrasTest` (every complete Sutra, a ```` ```yaml ```` block starting `rachana:`, in `docs/`, `drishti-console/web/guides/` and pack guides), `RachanaReferenceExampleTest`, `PackSutrasTest`; console `test_docs_*.py` (endpoints, error codes, settings, plugins, placeholders), `test_help*.py`, `test_tutorials.py`, `test_guide_images.py`, `test_tools_guide.py` |
| Performance gate | `drishti-server` | timed in the test | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99`: 300 warm-up views, then 2000 timed; p99 must be under 50ms and the layout cache hit rate above 0.99 |
| Micro-benchmarks | `drishti-benchmarks` | JMH, run by hand | `HotPathBenchmark` ([PERFORMANCE.md](../admin/PERFORMANCE.md) has the commands) |
| Console | `drishti-console/tests` | pytest with `FakeBackend`; fixtures in `tests/fixtures/` are ViewModels captured from the real server | `test_terminal.py` (every panel kind with broken data), `test_assets_policy.py` (no CDN, no inline code, Python size), `test_contrast.py` (theme contrast), `test_help.py` (every catalogued guide renders) |
| Python tools | `tools/` | `unittest` | `tools/samplegen/test_samplegen.py`, `tools/lake/test_build.py`, `tools/lake/test_demo.py`, `tools/lake/test_maintain.py`, `tools/testdata/test_testdata.py` |
| Generated content | `tools/packgen`, pack tools | `--check` | run by the drill |
| In the browser | your machine (`drishti-console/tests/*_browser.py`) | Playwright with Chromium; skipped when not installed | `test_live_tabs_browser.py`, `test_page_widths_browser.py` (no sideways scroll at 390, 1600 and 2560 px; images fit), `test_workspace_keys_browser.py`, `test_calc_browsers.py`. Install with `uv pip install -r drishti-console/requirements-test.txt` and `drishti-console/.venv/bin/python -m playwright install chromium`. Run **one browser test file at a time** on a laptop. They do not replace a look by hand: after a UI change, open the page in the running console in at least one dark and one light theme and on a phone width, use the keys (F-keys, `/`, `Alt+←`, table keys), watch a live view tick, and check the browser console for CSP violations. |

Habits that keep the suite fast and reliable:

- Turn the demo plugin's ticking off in Spring tests that do not test live updates:
  `"drishti.sources.plugins.demo.settings.ticking=false"`.
- Name the packs a test needs (`"drishti.packs.enabled=trading"`) rather than relying on the default. The build sets
  `drishti.packs.extra-dirs` to `examples/packs` and the default to `finance` for the server tests, so the tests written against the
  retired `finance` and `logistics` packs keep them as fixtures; a new test should name shipped packs and UPPER_SNAKE fields.
- Server tests write only into their fork's run folder, `target/test-run/fork<N>/` (`drishti.test.dir`, `drishti-server/pom.xml`),
  which the fork removes when it ends (`TestRun` closes the Spring test contexts first), so a run leaves nothing in `target/` but the
  build's output and never touches `./data`. A test names its folders `${drishti.test.dir}/...` in its properties, seeds a folder before
  its context starts with `TestRun.dir(name)`, and takes a temporary folder from `TestRun.temp(prefix)`; a test without Spring uses
  JUnit's `@TempDir`. Never write under a bare `target/...` path: per-test folders there survive every run until `mvn clean` (2.7.0
  found twelve million files, each content store importing the seals a shared collaboration folder had piled up); a Python test that runs `lake build` or `lake demo` sets `DRISHTI_DATA_DIR` or `DRISHTI_BI_STORE` to a temporary folder and
  passes `--server` (or `--no-signal`) so it never writes into the repository's `data/` or signals a real server.
- Keep local test data small (a few thousand trades): a laptop is not a scale-test machine.
- Prefer one assertion chain that reads like the behaviour (`assertThat(p99).as("warm p99 ms").isLessThan(50)`).
- Name tests as sentences: `aMissingFigureIsAnEmptyPanelNotAnError`.

---

## 7. Debugging

### 7.1 Where to look

With sign-in on (the run configurations), every `/api/v1` call needs `-H "Authorization: Bearer <token>"`: a personal API token
([GETTING_STARTED.md, Step 10](GETTING_STARTED.md#step-10--a-personal-api-token-for-the-command-line)). With it off, add
`-H 'X-Drishti-User: ash'` to act as a user. The answers below were captured on the demo lakes.

| Question | Ask |
|---|---|
| Is the server up? | `curl -s localhost:18480/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}` (`/actuator/health/liveness` and `/readiness` for probes; open even with sign-in on) |
| Is the console up, and can it reach the server? | `curl -s localhost:17480/healthz` (the process: `{"status":"UP","pythonRuntime":…}`) and `curl -s localhost:17480/readyz` (`503` while the server is unreachable or refuses the console) |
| Which packs, sources and problems? | `curl -s localhost:18480/api/v1/admin/health \| python3 -m json.tool`: version, uptime, heap, every source with status, health (`UP (engine: native)`), kinds, live/dated/search and read counts, `failedToStart`, pack problems, connectors, BI. *Admin → Health* shows the same; `drishti.py doctor --server … --token-file …` sums it up. |
| What does the server return for a view? | `curl -s localhost:18480/api/v1/views/trade/MX-20000001 \| python3 -m json.tool`. Add `-H 'X-Drishti-As-Of: 2026-10-15'` for a past date; `drishti.py view get trade/MX-20000001` prints it as text |
| What did the source send? | `F9` in the view, or `curl -s localhost:18480/api/v1/entities/trade/MX-20000001/raw` (provenance `"source":"trade-murex"`; masked fields read `•••` for roles without `raw`) |
| Which Sutra, how long? | The view JSON's `provenance.layout` (`Sutra irs-fixfloat v1 + inference`, or `inference only`) and `timings` (`fetch`, `layout`, `links`, `bind`, `total` in ms) |
| What would inference do on its own? | `curl -s localhost:18480/api/v1/studio/inferred/trade/MX-20000001` returns the inferred Sutra as YAML (`text/yaml`, starting with a comment, then `rachana: 1`, `sutra: trade-custom`) |
| Is a Sutra broken? | `curl -s localhost:18480/api/v1/sutras/problems` (`{}` when none); see [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md) |
| Which sources run? | `curl -s localhost:18480/api/v1/sources` (and `failures`); `drishti.py connector list` for the connector files |
| What changed between dates? | `curl -s localhost:18480/api/v1/history/trade/MX-20000001/diff` |
| How are live streams doing? | `curl -s localhost:18480/api/v1/health/live` → `{"streams":0,"topics":2000,"frames":0,"p50Ms":0.0,"p99Ms":0.0}` |
| What did a BI question do? | the answer's `meta` (layer, tables, cache, time, generation) or `explain: true` in the model query; *Admin → BI* and the model's page |
| What is in the BI store? | `<data>/bi-store/columns/<dataset>/<table>/date=<d>/meta.json` (rows, how it was read, the source version) |
| Metrics | `/actuator/metrics/drishti.view`, `/actuator/prometheus` (`drishti_view_seconds`, `drishti_live_*`) |
| Every endpoint | `/api/docs` (OpenAPI JSON) and `/api/docs/ui` (Swagger UI). With security on, `/api/docs` needs a token and `/actuator` (except health) an admin token or `DRISHTI_METRICS_TOKEN`. |

### 7.2 Logs

Both programs log to standard output. The server logs each plugin it starts (`source plugin started: …`), plugins left idle (`…
installed but not configured …`), the connector files it generated (`connectors: connector file trade-endur.yaml generated from the
template of pack 'trading'`), failures with stack traces, the identity database it opened (`identity database ready: …`), a lake rebuilt
while it was stopped (`The lake was rebuilt while the server was stopped …`), and every 5xx answer (`request failed: …`). Raise the
level for Drishti's packages with Spring's usual environment form:

```bash
LOGGING_LEVEL_COM_ASH_DRISHTI=DEBUG java -jar drishti-server/target/drishti-server-2.9.2-exec.jar
```

The console runs Uvicorn with access logs off (`access_log=False` in `run_drishti_web.py`). The run configuration **Drishti server
(debug-friendly)** is the one to start with **Debug** in IntelliJ: sign-in off (no token to mint while you step through), a 1 GB heap, port
18580 and the QA data folder `data/qa/data`, so it runs beside a normal server ([IDE_GUIDE.md](IDE_GUIDE.md)).

### 7.3 Debugging a live view

1. Does the view say **Live** in the top bar? A picked business date is static: no stream is opened. On the demo lakes with the demo
   source off nothing ticks: start with `DRISHTI_DEMO_ENABLED=true` and open a live sample (a curve, an FX spot, a trade valuation,
   [DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md)).
2. `provenance.live` in the view JSON must be `true`; only live sources (`SourceCapabilities.live`) tick.
3. Watch the server stream directly; you should see an `event:view` and then `event:frame` lines:

   ```bash
   curl -sN localhost:18480/api/v1/views/ir-curve/CRV-USD-BASIS/stream -H "Authorization: Bearer $DRISHTI_TOKEN" | head -c 600
   ```

4. There should be exactly one `/api/channel?s=…` request for the whole browser, staying open: in the network panel of the SharedWorker
   (`chrome://inspect/#workers`), or of the leader tab when there is no SharedWorker. `DrishtiChannel.transport()` in a tab's console
   says which (`shared-worker`, `leader`, `follower`).
5. If ticks arrive but the page does not change, check the browser console for errors in `live.js`.

For a live BI visual: its chip says **Live**, **Listening** (following, nothing has changed: a daily lake) or why it was refused; the
server's `GET /api/v1/bi/mq/stream` sends `answer` and `status` events (section 4.5).

---

## 8. Common pitfalls

1. **Starlette cancel scopes in streaming responses.** In `drishti-console/routes/api_routes.py`, upstream work for `/api/channel` runs
   in tasks created with a **fresh** `contextvars.Context` (`detached()`), carrying only the business date and "known at". A task started
   from the request's context inherits Starlette's (anyio's) cancel scope, which, once the request has ended, cancels every `await` in it
   for good: clean-up never completed, the console's connections to the server stayed open, the pool ran out, and the UI froze. End
   streams by **closing the upstream response**, never by cancelling a task that is reading it. Follow the same pattern in any new
   streaming route.
2. **Stale scripts after a change.** `ASSET_V` (in `drishti-console/core/app.py`) is `ASSET_VERSION` plus a hash of the names, sizes and
   modification times of `web/static/js/*` and `web/static/css/*`, computed **once at start-up**. Restart the console after editing
   scripts or styles, or browsers keep the cached file. Files you add elsewhere (images, vendor upgrades) are not fingerprinted; change
   their name or bump `ASSET_VERSION`.
3. **Editing generated files.** Generated `pack.yaml`, Sutras, samples, guides, test data and `help.yaml` are overwritten by the next
   generator run, and the drill's `--check` rejects a hand edit. The banking packs' generated guides copy their header from the first 15
   lines of `docs/guides/RACHANA_REFERENCE.md` (`tools/packgen/banking/make_docs.py`), so editing that document's header makes them
   stale too. Connector files the server generated are regenerated when the pack's template changes, unless you edited them.
4. **Sutras in documentation are compiled.** `DocumentedSutrasTest` parses every ```` ```yaml ```` block whose first line after any
   comments is `rachana:` in `docs/`, `drishti-console/web/guides/` and pack guides, and compiles its expressions. Such a block must be a
   complete, valid Sutra. A fragment (a panel, a strip) is a `yaml` block without `rachana:`; a block that is deliberately abridged
   contains `…` and is skipped.
5. **Environment variables leak into tests.** See [§2.2](#22-maven-commands): unset `DRISHTI_*` before running Maven.
6. **A page that is public by accident.** New console paths must be added to `PROTECTED` in `drishti-console/core/app.py`. `/t` and `/s`
   are matched exactly (they are prefixes of public paths such as `/static`); the rest by prefix.
7. **Inline code under the CSP.** `onclick=`, `<script>…</script>` and `style="…"` are blocked by the browser and rejected by
   `test_assets_policy.py`. Use a script file and `data-*` attributes.
8. **A plugin that starts by surprise.** Plugins are enabled by default when they are on the class path. Disable them in
   `application.yaml` until configured, or throw `PluginNotConfigured` from `start`.
9. **`synchronized` around I/O** pins a virtual thread to its carrier. Use `ReentrantLock` (see
   [§3.4](#34-object-oriented-concurrent-and-configuration-driven)).
10. **Adding a record component** to a `@ConfigurationProperties` record breaks every direct constructor call in tests; a record with two
    constructors needs `@ConstructorBinding` on the canonical one.
11. **SQLite hides schema mismatches.** Hibernate validates only on PostgreSQL. Run the identity contract on PostgreSQL (Docker) after
    changing an entity or a schema file.
12. **Packs that clash.** Two unrelated packs defining the same mnemonic, role, route or connector stop the server. The retired `finance`
    pack and the seven both define `TRD` and `CRV`; never enable them together.
13. **Layouts that do not change.** The layout cache is keyed by the Sutra object, kind and data shape; a hot reload clears it. If you
    changed inference code or semantic hints instead, restart, or purge the engine cache from *Admin → Caches*.
14. **A test that reads the repository** (`DocumentedSutrasTest`, `ImperfectDataTest`, the `drishti-it` rules) resolves paths relative
    to its module (`../docs`, `../config/packs`). Run Maven from the repository root.
15. **A hidden file or folder.** Drishti makes none, in the repository or at run time: a person must be able to see what is where. Name a
    new file or folder with a visible name (`history/`, `generated/`, `drishti-temp-folder.txt`, a temp file `name-xxxx.tmp`, never
    `.history`). Everything the server, the console and the tools write goes under the data folder (`DRISHTI_DATA_DIR`, default
    `./data`), never the repository root. When a name changes, read the old one once and rename it with a log line (`LegacyNames` in
    Java; see `packregen._gen_dir` in Python), never lose data. `tools/test_no_hidden_names.py` fails on a new hidden name or a tracked
    one.
16. **camelCase in a pack.** The shipped packs' documents, Sutras, BI files, samples and test data are UPPER_SNAKE (`TRADE_ID`,
    `PRODUCT_TYPE`, `LEGS[].CASH_FLOWS[].RESETS`). A Sutra path, a DEL measure or a search written in camelCase finds nothing (a search
    says `DRS-4004 no trade has a field …; did you mean …`). The retired example packs are camelCase; do not copy their names.
17. **A test that signals the user's server.** `lake demo` and `lake build --signal` talk to `DRISHTI_SERVER`, default
    `http://localhost:18480`. A test or script must pass its own `--server` (or `--no-signal`) and its own data folder.

### Why these few names start with a dot

Only names an external tool looks for by exactly that name keep the dot:

| Name | Why |
|---|---|
| `.gitignore`, `.gitattributes`, `.git/` | Git |
| `.dockerignore` | Docker |
| `.editorconfig` | editors |
| `.java-version` | version managers (jenv, SDKMAN) |
| `.mvn/` | the Maven wrapper |
| `.github/` | GitHub |
| `.idea/` | IntelliJ IDEA at the root, PyCharm in `drishti-console/` (our run configurations are in the visible `run-configs/` and `drishti-console/run-configs/`) |
| `.venv/` | the Python convention for a virtual environment (git-ignored) |
| `.claude/` | Claude Code |

Everything else is visible. The user's own `~/.cache/drishti*` (downloads of the fetch tools) follows the operating system's convention
and is outside the repository.

---

## 9. Releasing

Releases are numbered `MAJOR.MINOR.PATCH` and tagged `vX.Y.Z` on `main` (`git tag` lists `v1.0.0` …). The release commit for 1.9.0
(`Release 1.9.0`) shows every file a bump touches.

1. **Start clean on `develop`** with everything for the release merged and drilled.
2. **Bump the version** everywhere it is written. Find the old version first:

   ```bash
   git grep -n -F '2.5.0' -- '*pom.xml' drishti-console/core/app.py deploy README.md docs
   ```

   | File | What changes |
   |---|---|
   | `pom.xml` and every module's `pom.xml` (including `plugins/*/pom.xml`) | the project `<version>` and each module's `<parent><version>` |
   | `drishti-console/core/app.py` | `ASSET_VERSION`, so browsers fetch the new scripts |
   | `deploy/docker/compose.yaml`, `deploy/docker/server.Dockerfile`, `deploy/docker/console.Dockerfile` | image tags `drishti-server:<version>`, `drishti-console:<version>` |
   | `deploy/docker/ecs/*.Dockerfile`, `deploy/aws/env.example` (`DRISHTI_VERSION`) | the ECS image tags `drishti-server-ecs`, `drishti-console-ecs`, `drishti-loader-ecs` |
   | `README.md`, `docs/*.md` | the jar name `drishti-server-<version>-exec.jar` and versions in examples |

   Leave historical entries (old changelog sections) alone.
3. **Write the release text.** Add a section at the top of `CHANGELOG.md` (`## X.Y.Z — <headline> (<date>)`, then what changed,
   grouped, with the settings and endpoints involved) and of `RELEASE_NOTES.md` (`# Drishti X.Y.Z — release notes`, a short, user-facing
   summary; the previous release's heading becomes `# Previous release: Drishti …`). Add upgrade notes when the identity schema,
   configuration keys, on-disk formats or pack attribute names change.
4. **Verify locally on a fresh clone.** Clone, build the jar, `lake demo`, start the server and the console as
   [GETTING_STARTED.md](GETTING_STARTED.md) says, and follow the tutorials; `curl -s localhost:18480/api/v1/about` reports the
   `version` (on 2.6.7: `"version":"2.6.7"`).
5. **Commit** as `Release X.Y.Z` on `develop`.
6. **Drill:** `tools/drill.sh`. Nothing reaches `main` unless the whole build, every test, the console tests, the header check and the
   generator checks pass.
7. **Tag `main`** with an annotated tag and push it:

   ```bash
   git tag -a vX.Y.Z -m "Drishti X.Y.Z: <headline>" main
   git push origin vX.Y.Z
   ```

8. **Images (optional):** `docker build -f deploy/docker/server.Dockerfile -t drishti-server:X.Y.Z .` and
   `docker build -f deploy/docker/console.Dockerfile -t drishti-console:X.Y.Z .` from the repository root, after building the jar.
   [OPERATIONS.md](../admin/OPERATIONS.md) covers deployment and upgrades.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
