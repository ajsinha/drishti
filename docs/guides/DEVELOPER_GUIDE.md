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

# Developer guide: connectors, plugins, modules and the API

This guide is for people who change Drishti's code or connect it to other systems. It explains how the repository is laid out,
how to build, run and test it from a fresh clone, which rules the build enforces, how a request (a view, a BI question, a live
tick) travels through the code, and gives worked recipes for the changes people make most often. Then it shows how to choose,
combine and write a **connector** (a source plugin), and how to call the server's **API** from scripts, notebooks and Excel.
Every class, path and command here exists in the repository; the recipes follow the idioms of the real classes they imitate.
Every endpoint, with its parameters and a captured answer, and every error code are in [API_REFERENCE.md](API_REFERENCE.md).

If you only want to run Drishti, read [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine); to run it from IntelliJ IDEA and PyCharm, read
[OPERATIONS_GUIDE.md (IDE)](OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm). For the design and its reasons, read [ARCHITECTURE.md](../architecture/ARCHITECTURE.md), the BI model
architecture [ARCHITECTURE.md section 28](../architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) and the [decision records](../architecture/adr/README.md).

The API references are at **Help → Developer**, generated from the code: the Javadoc of the [plugin and connector SPI](/help/reference/latest/java/drishti-api/index.html) and the [shard machinery](/help/reference/latest/java/drishti-compute/index.html) with [`ShardedMeasure`](/help/reference/latest/java/drishti-server/com/ash/drishti/server/shard/ShardedMeasure.html), and the Python references of the [compute worker SDK](/help/reference/latest/python/drishti_compute/engine.html), [BI Python items](/help/reference/latest/python/core/python_items.html), [`drishti.py`](/help/reference/latest/python/drishti.html) and [`drishti_client`](/help/reference/latest/python/drishti_client.html), the pack generator helpers ([`readme`](/help/reference/latest/python/readme.html), [`packsamples`](/help/reference/latest/python/packsamples.html), [`upper`](/help/reference/latest/python/upper.html), [`walks`](/help/reference/latest/python/walks.html)) and the test-data helpers ([`samplegen`](/help/reference/latest/python/samplegen/dates.html)). What you add to these surfaces is documented as you write it: the drill fails on a public Java member without a comment, a broken `{@link}`, or a public Python function without a docstring and type hints (`python3 tools/api_docs.py check`; [Tools](CLI_REFERENCE.md#api-references)).

## Contents

1. [The repository](#1-the-repository)
2. [Building and running from source](#2-building-and-running-from-source)
3. [Project rules](#3-project-rules)
4. [How a request travels through the code](#4-how-a-request-travels-through-the-code)
5. [Recipes](#5-recipes)
6. [Testing strategy](#6-testing-strategy)
7. [Debugging](#7-debugging)
8. [Common pitfalls](#8-common-pitfalls)
9. [Releasing](#9-releasing)
10. [Connectors: choosing, combining and writing one](#10-connectors-choosing-combining-and-writing-one)
11. [The API: calling the server from scripts, notebooks and Excel](#11-the-api-calling-the-server-from-scripts-notebooks-and-excel)

## 1. The repository

Drishti has two programs. The **server** is one Spring Boot 3.5 application on Java 21 or newer, production runs 21
(`drishti-server`), built from a Maven reactor of internal modules; it reads every source, lays out every screen, answers every BI
question and pushes every live change. The **console** is a FastAPI and Jinja2 web application in `drishti-console/` that renders the
server's JSON; the browser draws what it is sent and computes no number of its own (the BI rule *the servers compute, the browser
draws*, [ARCHITECTURE.md section 28](../architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer)). Industries are **packs** (`config/packs/`),
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
| `drishti-ai` | `ai` | The LLM provider layer and the MCP client: providers (Anthropic, OpenAI-compatible through Spring AI, a mock), model registry, call policy, prompt masking, MCP clients over streamable HTTP. Depends on `api` and `common` only (`ArchitectureRulesTest.aiLayerDependsOneWay`). **Off by default**; see [AI_GUIDE.md](AI_GUIDE.md#1-model-providers) and [AI_GUIDE.md](AI_GUIDE.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy). | `LlmGateway`, `LlmRegistry`, `LlmProvider`, `AnthropicProvider`, `SpringAiOpenAiProvider`, `McpGateway`, `McpRegistry`, `McpClient` |
| `drishti-diskcache` | `diskcache` | A disk-backed, size-bounded, self-clearing cache (RocksDB) for live connector state. | `DiskCache` |
| `drishti-messaging` | `messaging` | The latest-state store and live fan-out shared by the ActiveMQ and RabbitMQ connectors. | `MessageStateSource` |
| `drishti-lakeio` | `lakeio` | Lake file access shared by the native Delta and Iceberg engines, no Hadoop: `Storage` (local disk through `java.nio`, Windows drive letters, backslashes and UNC shares included; S3 through the AWS SDK, with conditional PUTs for commits), byte ranges, Parquet page codecs (`NativeCodecs`, reading and writing) and the per-file Parquet footer cache. Other schemes come from a `StorageProvider` another module registers (`mem://`, the in-memory lake): the engines ask `StorageProviders` and never name that module. | `Storage`, `LocalStorage`, `S3Storage`, `LocalPaths`, `NativeCodecs`, `ParquetFooters`, `StorageProvider`, `StorageProviders` |
| `drishti-iceberg` | `iceberg` | Drishti's own Apache Iceberg engine without Hadoop, on `drishti-lakeio`: metadata JSON kept whole, path-based, metadata-file and REST catalogs (JDK HTTP client, OAuth2, ETag, vended credentials, SigV4), Avro manifests, planning with partition and metric pruning, field-id Parquet reads with schema evolution and deletes, the Parquet writer with Iceberg metrics, safe commits and the table operations. Its tests prove the writer loads no Hadoop `Configuration`. | `Table`, `catalog.Catalogs`, `scan.Planner`, `read.TaskReader`, `write.Operations`, `write.ParquetDataWriter` |
| `drishti-deltalake` | `deltalake` | A Delta Kernel engine without Hadoop on `drishti-lakeio` (local disk, Windows paths included, and S3; Parquet through parquet-java with the shared input files and codecs); and Drishti's own Delta writer (`lake build --engine drishti`). Its tests prove in an isolated class loader that no Hadoop `FileSystem`, `Shell` or `Configuration` loads. | `NativeEngine`, `write.LakeManifest` |
| `drishti-memlake` | `memlake` | The in-memory lake, plain Java with no Spring ([IN_MEMORY_LAKE.md](../architecture/DATA_ARCHITECTURE.md#9-the-in-memory-lake-delta-tables-that-never-touch-a-disk)): the `mem://` memory stores (Parquet files in off-heap chunks under a hard cap; registered with `drishti-lakeio` as the `mem` storage provider), the tables and their replenish jobs, the eviction ladder, automated compaction, the owner node's binding, the never-on-disk guard and the host checks. The server keeps only its Spring wiring (`server.memlake`: settings binding, beans, the Admin, Lakes controller). | `MemoryLake`, `MemoryLakeSettings`, `Retention`, `MemoryBinding`, `store.MemoryStores`, `store.MemoryStorage` |
| `drishti-lakeload` | `lakeload` | The parallel lake loader: JSON Lines from a folder or S3 into Delta or Iceberg, readers, parsers and writers on every core, memory bounded by `memory.budget-mb` (sorted runs spilled to disk), one commit per business date ([PARALLEL_LOADER.md](../architecture/DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-drishti-lakeload)). | `LakeLoad`, `Loader`, `RowParser`, `Runs`, `DeltaTarget`, `IcebergTarget` |
| `drishti-testkit` | `testkit` | Contract tests every source plugin of a kind must pass. | `DatedSourceContract`, `MessageSourceContract`, `BrokerOutage` |
| `drishti-module-runtime` | `module.runtime` | The module runtime ([§1.5](#15-the-module-runtime)): the `.dmod` package and its hash list, the manifest, verification V1–V8, discovery, the dependency resolver, a class loader and a child Spring context per module, the live route table, the registry of contributions and the unload sequence with its leak check; and the module tool ([§1.6](#16-the-module-sdk): `module.tool`, the one packer and the conformance checks, `target/drishti-module-tool.jar`). Spring and Jackson only; one implementation for the kernel and, from i4, the console. | `ModuleRuntime`, `tool.ModuleTool`, `tool.ModuleConformance`, `DmodManifest`, `Dmod`, `ModuleVerifier`, `DependencyResolver`, `ModuleClassLoader`, `RouteTable`, `Contributions`, `LeakCheck` |
| `drishti-sdk/drishti-module-maven-plugin`, `-archetype`, `-parent` | `sdk` | The Module SDK ([§1.6](#16-the-module-sdk)): the archetype of a module project, the parent it inherits, and the plugin that validates, packs the `.dmod` and runs the conformance checks C1–C15. | `ValidateMojo`, `AssembleMojo`, `ConformanceMojo` (over `module.tool`) |
| `modules/banking/banking-compute`, `banking-service` | `banking` | The banking module's sources, built on their own (`./mvnw -f modules/banking/pom.xml`; artifacts `drishti-compute-banking`, `drishti-banking`): the maths, then the services, storage, Spring wiring and API. The kernel internals it still reaches are listed in `modules/banking/kernel-internals.txt` (`BankingReachTest`: the list only shrinks). | `BankingModule`, `var.*`, `exposure.*`, `compute.var.*` |
| `drishti-app`, `drishti-kernel-app` | (none) | The runnable jars, no code: `drishti-app` the full jar (the kernel with banking, the default, `drishti-server/target/drishti-server-<v>-exec.jar`); `drishti-kernel-app` the thin jar (the kernel alone, `drishti-kernel-app/target/drishti-kernel-<v>-exec.jar`, Health: *no modules: the kernel runs alone*). | |
| `drishti-server` | `server` | The Spring Boot application: REST and SSE controllers, security, alerts, Sutra governance, and one sub-package per feature area (`admin`, `ai`, `alerts`, `bi`, `calc`, `cli`, `collab`, `connectors`, `deploy`, `design`, `dist`, `embed`, `explain`, `governance`, `layout`, `loads`, `notify`, `pivot`, `registry`, `reports`). `bi` is the BI engine (section 4.4). | `DrishtiApplication`, `api.*Controller`, `security.Entitlements`, `security.TokenFilter`, `alerts.AlertEngine`, `governance.SutraGovernance`, `bi.mq.MqService`, `bi.engine.QueryService` |
| `drishti-it` | `it` | Repository-wide rules, as tests only. | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest`, `NoBlockingUnderSynchronizedTest` |
| `drishti-benchmarks` | `benchmarks` | JMH benchmarks of the hot paths (fingerprint, Rachana-EL, formats, cold inference). | `HotPathBenchmark` |

### 1.2 Source plugins

Each plugin is its own module under `plugins/`, depends on `drishti-api` only (plus `drishti-common` and `drishti-testkit` for
tests), and registers itself in `src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin`. The server depends on all of
them, so they are on its class path; each starts only when enabled or used by a named connector, one YAML file per connector in
`config/connectors/` ([DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors), [section 10](#10-connectors-choosing-combining-and-writing-one)).

| Module | Plugin name | Reads | Extra internal dependency |
|---|---|---|---|
| `drishti-plugin-demo` | `demo` | The enabled packs' `samples/` (`<kind>.jsonl`); random-walk ticks ([the demo plugin's README](../../plugins/drishti-plugin-demo/README.md)) | — |
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
| `drishti-plugin-feeds` | `feed` | Public feeds: NY Fed SOFR, ECB €STR and FX, US Treasury, FRED ([the feeds plugin's README](../../plugins/drishti-plugin-feeds/README.md)) | — |
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
drishti-memlake                   (api, common, engine, lakeio, deltalake; no Spring code: ArchitectureRulesTest)
drishti-lakeload                  (api, lakeio, deltalake, iceberg)
plugins/*                         (api; kafka adds diskcache; activemq and rabbitmq add messaging; delta adds deltalake; iceberg and iceberg-external add iceberg)
drishti-server                    (engine, identity, packs, ai, lakeload, memlake, and all nineteen plugins)
drishti-it                        (server, testkit)
drishti-benchmarks                (inference)
```

`ArchitectureRulesTest` keeps these one-way (see [§3.4](#34-architecture-rules)): nothing below the engine may see `engine` or
`server`, the engine may not see `server`, `api` sees nothing internal, `ai` sees only `api` and `common`, `memlake` sees neither the
server nor Spring, and the lake engines and connectors never see `memlake` (they reach `mem://` through `lakeio`'s `StorageProviders`). The reactor also builds
`docs/guides/examples/connector`, the worked example plugin of [section 10](#1013-a-worked-example-the-dayfolder-plugin).

### 1.4 Everything else

| Folder | What is in it |
|---|---|
| `drishti-console/` | The web console: `run_drishti_web.py` (entry point), `core/` (app factory, backend client, auth, business date, packs, config loader, `ai/` for the console's LLM clients, and one module per feature such as `forms.py`, `bi.py`, `controls.py`, `calc.py`, `retry.py`), `routes/` (one router per area), `web/templates/` (Jinja2, with `_macros/panels.html` holding one macro per panel kind), `web/embed/` and `web/elements/` (Drishti Elements, the `<drishti-view>` web component), `web/static/` (`js/`, `css/`, `img/`, and third-party code under `vendor/`), `web/guides/` (in-app guides), `config/` (`application.yaml`, `help.yaml`, `workspaces.yaml`), `tests/` (pytest, with a fake backend in `conftest.py`; 255 files, 89 of them Chromium tests named `*_browser.py`) |
| `config/packs/<domain>/<name>/` | One domain pack: `pack.yaml`, `sutras/`, `samples/` (`catalog.jsonl` and one `<kind>.jsonl` per kind), `config/` (formats, semantics, help, workspaces), `guides/`, `python/` (Calc snippets) and `tests/`. Seven ship: `core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`, `world-economy`, `news-and-markets`. The trading pack's Sutras are organised `sutras/common/` (shared blocks), `sutras/endur/`, `sutras/calypso/`, `sutras/murex/` (one per product type) and `sutras/pages/` (cross-system pages); the folders are organisation only: each Sutra's `match` (`SOURCE_SYSTEM` and `PRODUCT_TYPE`) decides ([BUILD_GUIDE.md](BUILD_GUIDE.md)) |
| `examples/packs/` | The retired packs (`finance`, `genomics`, `logistics`, `retail-banking`, `climate-risk`, `operational-risk`, `liquidity-risk`), kept as examples and as fixtures for tests written against them; loaded only with `DRISHTI_PACKS_EXTRA_DIRS=examples/packs` |
| `config/connectors/`, `config/connectors.examples/` | The site's connector files (written by the server at its first start from the packs' templates; not committed) and ready-made examples per store ([DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors)) |
| `config/bi/` | Shipped BI files: datasets, models, reports, boards |
| `run-configs/` | The IntelliJ IDEA run configurations (IntelliJ opens the repository root; [OPERATIONS_GUIDE.md (IDE)](OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm)): **Drishti build server jar**, **Drishti server**, **Drishti server (demo lakes)**, **Drishti server (debug-friendly)**, **Drishti server (QA profile)**. The PyCharm ones (PyCharm opens `drishti-console/`, so the two IDEs never share one `.idea`) are in `drishti-console/run-configs/`: **Drishti console**, **Build demo lakes**, **Load ENDUR file into the lake**, the QA console and the CLI tools |
| `data/testdata/` | The committed test data: sixty business days of every dataset the seven packs read (5,000 trades a day per source system), `.jsonl.gz`, with `testdata.yaml` and a README ([data/testdata/README.md](../../data/testdata/README.md)) |
| `tools/drishti.py` | The one command line over the tools below and the server's REST: `sutra`, `pack ...` (`new`, `make`, `check`, `about-check`, `diff`, `i18n`, `publish`, `install`, `regenerate`, `catalogue`, `bundle`, `verify`, `deploy`, `rollback`, `keygen`), `data ingest|load|profile`, `server health|packs|smoke`, `connector ...`, `bi ...`, `view get|explain`, `design ...`, `lake build|publish|scan|status|demo|maintain`, `doctor`, `completion`, `docs shots`; the `test_cli_*.py` and `test_drishti_cli.py` suites cover it. See [CLI_REFERENCE.md](CLI_REFERENCE.md) and [CLI_REFERENCE.md](CLI_REFERENCE.md) |
| `tools/lake/` | Delta lakes from Python: `build.py` (`lake build`: a large JSON export streamed into a Delta table, flat memory, partitions, promoted columns, the data-load signal or the BI-store stamp), `demo.py` (`lake demo`: the demo lakes from `data/testdata`), `scan.py`, `laketables.py` (`lake status`), `maintain.py` (retention, compaction, checkpoints, vacuum, `relayout`), each with its tests |
| `tools/testdata/` | `make_testdata.py` (writes `data/testdata`, deterministic, `--check`), `universe.py` (the fictitious counterparties, books and legal entities), `test_testdata.py`, `test_deterministic.py` (the same bytes on every Python) |
| `tools/packgen/` | Pack generators: `banking/` (the five banking packs from one taxonomy, `make_data.py` for their samples and `--lake`/`--jsonl`), `trades/` (the common trade schema every source system writes, `schema.py`, and the ENDUR, CALYPSO and MUREX generators), `common/` (`packbuild.py`, `packsamples.py`, `walks.py`, `upper.py`), `world/`, `economics/`, `politics/`, and the retired packs' generators |
| `tools/samplegen/` | Sample-history helpers: `lake.py` (a pack's sample history as JSONL loader rows), `load_rows.py` (loads such rows into Delta), `layout.py` (the pack-declared lake layout: promoted columns, sorted files), `bulk_trades.py` (a large trading book for one source system: `--trades`, `--days`, `--system`), `stream.py` (Kafka ticker), `dates.py` (business-day calendars) |
| `tools/load-*.sh` | Load the samples (and a generated book) into PostgreSQL, Iceberg, DuckDB, MongoDB, Redis, Aerospike, Cassandra, JSON-lines files or Delta ([DATA_GUIDE.md section 11](DATA_GUIDE.md#11-the-demo-data)) |
| `tools/bench/` | The scale benchmark: `scale.sh <store> <trades-a-day>`, `measure.py`, `report.py`; `results/` keeps each run's raw JSON lines; see [BENCHMARK.md](../admin/BENCHMARK.md) |
| `tools/packgen/pack_from_jsonl.py`, `tools/packmake.py`, `tools/schemapack.py`, `tools/packbundle.py`, `tools/packform.py`, `tools/packdiff.py`, `tools/packi18n.py`, `tools/packregen.py`, `tools/sutragen.py`, `tools/authoring.py` | Pack authoring: a pack from JSON lines or from schemas, regeneration of unedited generated files, bundles, the `form:` section, `pack diff`, `pack i18n`; the `tools/drishti.py` commands call them (see [BUILD_GUIDE.md](BUILD_GUIDE.md) and [CLI_REFERENCE.md](CLI_REFERENCE.md)) |
| `tools/realdata/`, `tools/docs/`, `tools/elements-demo/`, `tools/windows/`, `tools/maps/` | The real-data builds of the world-economy pack ([DATA_GUIDE.md (world-economy)](EXTERNAL_DATA.md#the-world-economy-data-in-detail)); the documentation screenshots (`tools/docs/screenshots.py`, `shots/`, one module per guide and tutorial); a sample host page for Drishti Elements; the PowerShell starters ([OPERATIONS_GUIDE.md (Windows)](OPERATIONS_GUIDE.md#4-drishti-on-windows)); the map region builder |
| `tools/packreg/` | `packreg.py`: signing keys, publishing packs to a signed registry, verifying one (ADR-018); `test_packreg.py` |
| `tools/` (files) | `drill.sh` (verify and publish), `fetch-pyodide.sh` (installs Calc's Python runtime, pinned and verified, into `drishti-console/web/static/vendor/pyodide/`), `elements_sheet.py` (regenerates the Elements stylesheet), `make_icons.py` (favicon and app icons), `ingest_jsonl.py` and `ingest_watch.py` (`data ingest`), `serverpacks.py`, `serverconnectors.py`, `doctor.py`, `completion.py`, `viewcmd.py`, `dataprofile.py`, `license_headers.py` (check or insert the copyright header), `test_no_hidden_names.py` (no hidden names, section 8), `rachana/md_to_yaml.py` (converts Markdown Sutras to `*.sutra.yaml`) |
| `deploy/` | `server.Dockerfile`, `console.Dockerfile`, `compose.yaml`, `compose.data.yaml`, `lake-maintenance.yaml`, `grafana/` |
| `config/license-header.txt` | The text of the copyright header that `license_headers.py` inserts |
| `data/` | The **data folder** (`DRISHTI_DATA_DIR`): everything Drishti writes at run time, all git-ignored except `testdata/`: `delta/` (the demo lakes), `bi-store/` (BI columns and rollups), `connectors/` (`generated/` and `history/`), `identity/`, `feeds/`, `packs/` (`added.yaml`, installed archives), `loads/`, `governance/`, `collab/`, `reports/`, `qa/` (the QA profile), `build/` (tool output and staging), `drill/` (the drill's logs) |
| `docs/` | These documents, by audience: `guides/` (getting started, user, developer, API, Rachana, reports and troubleshooting guides), `tutorials/` (18 hands-on tutorials), `connectors/` (the connector and plugin guides, a design document per store, demo data), `admin/` (operations, configuration, users, performance; the runbooks are in OPERATIONS_GUIDE.md), `architecture/` (architecture, design notes and the ADRs under `architecture/adr/`); `README.md` is the index; reference mockups and logos under `requirements/` |
| `.github/workflows/fast.yml`, `windows.yml` | CI: `fast.yml` runs `./mvnw -B -q verify` and the header check on Java 21 and 25 and `pytest` for the console on Python 3.13; `windows.yml` builds on Windows, runs the native Delta engine, the Delta connector and server tests, and asks a server on a small lake for a view, a search and a past date |

### 1.5 The module runtime

`drishti-module-runtime` loads **modules**: separately built packages (`<id>-<version>.dmod`) installed unpacked under a module folder
as `<folder>/<id>/<version>/` with the package beside them (the design: [KERNEL_AND_MODULES.md sections 3, 4, 9.8 and 12](../architecture/KERNEL_AND_MODULES.md#3-the-module-package-dmod)).
It is a library; a host (the kernel, from i4 also the console) makes one `ModuleRuntime` and adds only its own kind of contribution through
`ModuleHost`. With no module folder, or an empty one, it does nothing.

```java
ModuleRuntime rt = new ModuleRuntime(KernelFacts.ofKernel(), kernelContext, kernelClassLoader, host, tombstonesDir);
List<ModuleHealth> rows = rt.start(List.of(modulesDir), ModuleDiscovery.readActive(activeYaml), id -> enabled(id));
rt.routes().lookup("/m/genomics/variants/c1");          // 200 draw it, 308 moved, 404 off, 410 removed (a tombstone)
rt.unload("genomics", ModuleRuntime.Leave.REMOVE, hot);  // the design's steps 1-9; hot drops the class loader and checks it is collected
```

| Step | Class | What it does |
|---|---|---|
| Package | `Dmod` | packs a folder (`module.yaml` first and stored, `META-INF/DMOD.MF`: `<sha-256>  <path>` per file), unpacks one (a path leaving the folder is refused), verifies an unpacked folder and the package beside it against the hash list. Signatures (`DMOD.SIG`) arrive in j: an absent one is accepted with a Health note |
| Manifest | `DmodManifest` | every field of the design's `module.yaml`; V1 refuses an unknown field, a missing required one, `format` other than 1, an unreadable version or range, and `taint` |
| Discovery | `ModuleDiscovery` | `<folder>/<id>/<version>/module.yaml` in folder order (first wins), the version `active.yaml` names, else the highest |
| Checks | `ModuleVerifier`, `Claims` | V1 (id and version name the folders), V2 (hashes; no executables; no kernel API classes in a jar; native code only when declared and allowed), V3 (the API ranges the host knows), V5 (licence), V6 (API prefix, aliases, words, kinds and lenses against the kernel's reserved names and every loaded module), V7 (data needs parse; satisfiable when the host knows its kinds), V8 (memory budget: a warning, a refusal when strict) |
| Order | `DependencyResolver` | V4: topological by `requires`, then by id; a missing, out-of-range, refused or conflicting module refuses its dependants; a cycle is refused with its path |
| Code | `ModuleClassLoader` | the JDK, then the shared list (`KernelFacts.SHARED`: the kernel API, Spring, Jackson, SLF4J, Arrow, Micrometer, Jakarta) from the kernel, then the module's own jars child-first; every other kernel class is invisible. It is a Spring `SmartClassLoader`, so Spring keeps no cache of the module's classes |
| Context | `ModuleRuntime` | one `AnnotationConfigApplicationContext` per module, parent the host's, with the module's loader; `java.spring` classes and `java.entry` registered |
| Routes | `RouteTable` | the API namespace `/api/v1/<prefix>/…` and the format-2 `plugin.yaml` routes under `/m/<id>/…`, with aliases; immutable snapshots swapped atomically, looked up without a lock |
| Unload | `Contributions`, `LeakCheck` | a host records each surface with the action that withdraws it; unload runs the design's steps in order, a failing step does not stop the next (its entry stays for a retry); hot unload drops the loader and checks it is collected |

A refused module is absent: no class loaded, no route, only its Health row with the check (`V1`–`V8`, `START`) and the words. The tests
build a small module from Java source at test time (`TestModule`), load, route and unload it (leak-free, five times over) and cover every
refusal.

### 1.6 The Module SDK

A module is built apart from the kernel, against the kernel API (the design: [KERNEL_AND_MODULES.md section 8](../architecture/KERNEL_AND_MODULES.md#8-the-module-sdk-and-the-developer-path)).
**A `.dmod` is a documented zip anyone can produce** ([KERNEL_AND_MODULES.md section 3](../architecture/KERNEL_AND_MODULES.md#3-the-module-package-dmod)):
`module.yaml` first, then the module's folders (`java/`, `console/`, `data/`, `packs/`, `docs/` …) and `META-INF/DMOD.MF`, the SHA-256 of
every file. Any build that produces one is fine, **as long as it passes `check`** (the fifteen conformance checks below). Maven is one way,
not the only one: the packer and the checks are one implementation in `drishti-module-runtime` (`com.ash.drishti.module.tool`), reached
three ways:

| Way | For | Commands |
|---|---|---|
| **Maven** | a Maven project (the archetype makes one) | `mvn verify`: the plugin's `drishti:validate`, `drishti:assemble`, `drishti:conformance` |
| **The tool** | Gradle, Bazel, a script, or `javac` and a folder | `java -jar drishti-module-tool.jar pack <folder> [--jar <jar>] [--out <dir>]`, then `check <.dmod>` and `inspect <.dmod>` |
| **The command line** | Linux, macOS and Windows, with Java and Python only | `python3 tools/drishti.py module pack <folder>`, `module check <.dmod>`, `module inspect <.dmod>` (it runs the tool's jar) |

The tool is built with the runtime: `drishti-module-runtime/target/drishti-module-tool.jar` (one self-contained jar; `--tool` or
`DRISHTI_MODULE_TOOL` points the command line at another copy). `pack` takes a folder in the `.dmod` layout (`module.yaml` at its root);
the jar named in `module.yaml`'s `java.jars` is either already under `java/` or given with `--jar`. `check` takes a `.dmod` or a folder
(packed first) and `--project <dir>` (the sources C10 reads) and `--cycles <n>` (C15); exit 0 when every check passed, 1 when one failed or
the module was refused, 2 for usage. `inspect` prints the manifest, the parts and the hash list's verdict. A Gradle build, for example:

```bash
gradle jar                                          # build/libs/greet.jar, compiled against drishti-kernel-api (compileOnly)
java -jar drishti-module-tool.jar pack src/dmod --jar build/libs/greet.jar --out build/dmod
java -jar drishti-module-tool.jar check build/dmod/greet-0.1.0.dmod --project .
```

For Maven the SDK is three more artifacts of this repository, `com.ash.drishti:*` at the product's version:

| Artifact | What it is |
|---|---|
| `drishti-module-archetype` | makes a module project that builds green on its first `mvn verify`: `src/dmod/module.yaml`, a function (`Greeting`, a bean of the module's Spring configuration) with its route and page (`console/plugin.yaml` format 2, `console/pages/hello.html`), a style on the theme tokens, a kind with its data contract and the data needs, `docs/` (README with front matter, Help, a guide, a tutorial) and a JUnit test |
| `drishti-module-parent` | the parent the project inherits: Java 21; the kernel API and Spring as `provided` (the kernel supplies them; never inside a `.dmod`); JUnit and AssertJ; the plugin's executions |
| `drishti-module-maven-plugin` | a thin wrapper over the tool's classes: `drishti:validate` (validate phase: the manifest reads, its id and version are the pom's), `drishti:assemble` (package: `src/dmod/` plus the project's jar under `java/`, packed by the module runtime's packer into `target/dmod/<id>-<version>.dmod` with its hash list and `.sha256`), `drishti:conformance` (verify: the checks C1–C15) |

**Quickstart with Maven: archetype → build → load.**

```bash
./mvnw -q install -pl .,drishti-sdk/drishti-module-maven-plugin,drishti-sdk/drishti-module-archetype,drishti-sdk/drishti-module-parent -am -DskipTests
mvn -B archetype:generate -DarchetypeGroupId=com.ash.drishti -DarchetypeArtifactId=drishti-module-archetype \
    -DarchetypeVersion=2.9.11.g -DgroupId=com.example.greet -DartifactId=greet -Dpackage=com.example.greet
cd greet && mvn verify          # the test, target/dmod/greet-0.1.0.dmod, then "C1 No kernel internals: passed" ... C15
cp target/dmod/greet-0.1.0.dmod <data folder>/modules/    # auto-deploy (a single node): stored, checked, installed and loaded
curl -s localhost:18480/api/v1/greet/greeting/Asha         # the module's REST API, mounted by the kernel
rm <data folder>/modules/greet-0.1.0.dmod                  # removed the same way: its paths answer 410, its tombstone is kept
```

(To keep your `~/.m2` untouched while trying it, add `-Dmaven.repo.local=<folder> -Dmaven.repo.local.tail=$HOME/.m2/repository` to each
Maven command: installs go to the folder, reads fall back to `~/.m2`.) The `.dmod` dropped into the module folder (`drishti.modules.path`,
default `<data folder>/modules`) goes through the module deployment as an administrator's would: its hash list, the fifteen checks, then
installed under `modules/installed/<id>/<version>/` and loaded. Auto-deploy is on for a single node and off in a cluster, where a module is
deployed by plan → apply ([CLUSTER_GUIDE.md](CLUSTER_GUIDE.md)). **Production loads only verified `.dmod` packages.**

**Edit → reload, in development mode.** While you work on a module, the kernel can load its build output without packing a `.dmod` and
reload it whenever it changes, as Tomcat runs an unpacked web application:

```bash
# once: a development server (single node, never the prod or production profile) pointed at the module's exploded build output
DRISHTI_MODULES_DEV=true DRISHTI_MODULES_PATH=$PWD/target/dmod-stage/greet bin/start-server.sh
# the loop: edit, rebuild, and the kernel reloads it within drishti.modules.autodeploy-seconds (unload, leak check, load)
mvn -q package -DskipTests      # the server log says "development mode: greet changed: unloaded (leak-free)", then "loaded"
```

`target/dmod-stage/<id>/` is the folder `drishti:assemble` packs (module.yaml at its root, the jars under `java/`). A folder of
`<id>/<version>/` folders works too. Development mode loads it **unverified** (no hash list, no checks), and Health and Admin → Modules mark
the module *development (unverified)*. `drishti.modules.dev` is refused in a cluster and under the `prod` or `production` profile: the
server logs the refusal and loads only verified packages. Run `mvn verify` (the fifteen checks) before you ship.

**Several jars.** `java.jars` names the module's own jars (the project's first: a service jar, a compute jar ...); `java.lib` names
third-party jars, packed under the package's `lib/`. Every jar of the package is named in one of the two (V1 refuses one that is not); all of them are
the module's class path, its own first. `drishti:assemble` takes the others from the project's compile and runtime dependencies by file
name (`commons-math3-3.6.1.jar`); the tool takes them with `pack <folder> --jar <own jar> --more <jar>,<jar>`.

```yaml
java: {jars: [genomics-service.jar, genomics-compute.jar], lib: [commons-math3-3.6.1.jar], entry: com.example.genomics.GenomicsModule,
       spring: [com.example.genomics.ModuleConfig]}
```

A library every module and connector of the site shares (a JDBC driver, a company's own jar) goes into the shared lib folder instead
(`drishti.lib.path`, default `<data folder>/lib`; [ADMIN_GUIDE.md](ADMIN_GUIDE.md)): a module sees it after its own jars.

### 1.7 What a module can contribute

A loaded module's beans (of its Spring configuration) and the `META-INF/services` entries of its jars that are of one of the kernel's
extension types are seen by the kernel while the module is loaded and gone when it unloads: each one is recorded in the registry of
contributions and withdrawn in the unload's own step ([KERNEL_AND_MODULES.md section 12](../architecture/KERNEL_AND_MODULES.md)). Only
the kernel API (`com.ash.drishti.kernel.api`) and the entity types its signatures use (`com.ash.drishti.api`: `EntityRef`,
`EntityDocument`, `AsOf`, `DataNode`) are visible to a module.

| Type (kernel API) | What the kernel does with it |
|---|---|
| `data.KindProvider` | routes the kinds it computes like any source: views, Rachana, the pivot, BI |
| `SourcePlugin` (the plugin API, `com.ash.drishti.api`): a **connector extension** | routed under its own name while the module is loaded, started with `module-dir` (the installed module) and `data-dir` (`<data>/modules/<id>`); the genomics module's VCF reader (section 1.9). A connector only one module's kinds use ships inside the module; a jar every part may use (a JDBC driver) goes in the lib folder |
| `function.FunctionProvider` | adds its functions to Rachana (the archetype's `<id>_greet(name)` in any expression, Sutra or measure); a name already taken refuses the start |
| `engines.ComputeEngine` | lists and starts it as the engine registry would at start (its switch and needs apply) |
| `@ModuleApi` + `@RestController` | mounts its endpoints under `/api/v1/<prefix>/` (its manifest's `api.prefix`); off: 404, removed: 410 |
| `module.DrishtiModule` (the manifest's `java.entry`) | started with the kernel's `ModuleContext` (kinds as rows, settings `drishti.module.<id>.*`, its data folder, audit, the business date); stopped before its context closes |
| `approvals.ApprovalSource`, `explain.Explainer`, `view.ViewAugmenter`, `pivot.PivotSourceProvider` | the Approvals queue, Explain this number, the views, the pivot's sources |
| `health.HealthFigures`, `capacity.CapacityFigures` | Health and the capacity page |
| `vocabulary.DomainVocabulary`, `official.OfficialResults`, `modules.ApiAlias` | the domain words, the readers of official results, old API paths |
| `ai.AiToolProvider` | held for the AI layer |

Pages and their routes come from the console part (`console/plugin.yaml` format 2, served under `/m/<id>/`), through the live route table.

**First-party modules and the allowance** ([KERNEL_AND_MODULES.md section 3.3](../architecture/KERNEL_AND_MODULES.md#33-the-manifest-moduleyaml)).
The product's own modules say `origin: first-party`; the kernel believes it only when the module's id and the SHA-256 of its
`META-INF/DMOD.MF` are in the `first-party.yaml` of the same build (`java -jar drishti-module-tool.jar first-party DIR --out FILE` writes
it; `modules/dist` does in the build). Only such a module may declare `kernel-internals:` (a list, or a file the packer writes in): its
class loader then asks the kernel for exactly those packages, C1 lets them through, and the packer stamps `kernel-build:`, the one kernel
build it loads on (`pack --kernel-build ID`, default the kernel on the tool's class path). Anything else is refused in words: an allowance
on a third-party module or on a first-party claim that does not verify (C1), or another kernel build ("built for kernel X, this is Y:
install the matching module", V3). `module inspect` and `drishti.py module list` print the origin; Health's module rows carry it.
`check --with a.dmod,b.dmod` installs the modules it requires beside it for the runtime checks; a module with an allowance is made with its
beans lazy there (the harness has no kernel beans).

**What the bridge also connects for a first-party module's allowance** (banking's beans, as the in-process wiring had them): a connector
type (`com.ash.drishti.api.SourcePlugin` in `META-INF/services`: the connectors whose files name it start, and stop when it leaves), the
engines' catalogue entries (`META-INF/drishti/engines.yaml` in its jar) and their defaults (`engines/<id>.yaml`, added to the settings
last, so the site's `config/engines/<id>.yaml` wins), engine caches (`CacheGroup`, joining the memory budget), readiness gates, drains,
cache reports, ready rules and Flight forms, a REST controller whose every path is already in its namespace (`/api/v1/<prefix>/...`,
mounted as it is), and its class loader, so a pack's reactive connector naming one of its engine classes finds it while it is loaded.
The module's context gets the host's conversion service (`"a,b"` to a list, durations, sizes) and is refreshed with its own loader as the
thread's, so a playground's built-in template is read from the module's jar.

**Leaving without a trace.** When the module unloads, every kernel registry it joined directly (the load service's ready listeners, the
disk budget's folders, the admission gate's load slots and open heaps, the dates engines hold open) drops what belongs to the module's
class loader (`ModuleLoaders`), the shared JSON and conversion caches are emptied of its types, and the leak check waits up to about ten
seconds for a stopping engine's last tasks. Removing banking from a running server and installing it again answers the same VaR, and the
first class loader is collected (`leakFree: true` in the deployment's report).

**The conformance checks** ([KERNEL_AND_MODULES.md section 8.6](../architecture/KERNEL_AND_MODULES.md#86-the-conformance-suite)), each
failure a sentence naming the file and the fix:

| Check | How the tool (and so the plugin) checks it |
|---|---|
| C1 No kernel internals | every class file's constant pool: a type of `com.ash.drishti` outside `kernel.api`, the plugin API (`com.ash.drishti.api`) and the module's own packages fails, except a verified first-party module's declared allowance (below) |
| C2 No bespoke UI | pages draw no `<table>`, `<canvas>` or `<svg>`; scripts import only `@drishti/elements-*` and their own files |
| C3 Theme tokens only | CSS: no hex, `rgb()`/`hsl()` or named colours outside `var(--…)`, no `!important` |
| C4 `drishti_` asset prefix | every file under `console/static/` and `elements/` is `drishti_<name>`; no vendored library |
| C5 CSP-clean | no inline `<script>`, `on*=` handler, `javascript:` URL, `eval`, `new Function` or remote origin |
| C6 Routes namespaced | `plugin.yaml` format 2, unique route ids, paths inside the module (served under `/m/<id>/`), each page present; no code in the console part |
| C7 Help, guide, tutorial present | README with front matter, the Help file, at least one guide and one tutorial, every relative link and image of `docs/` present |
| C8 Data contracts present | each provided kind has `packs/<pack>/contracts/<kind>.yaml` with `contract-format: 1`, a `binding-hint:` and no `storage:`; each need names a contract range |
| C9 No secrets, no connectors | private keys, cloud keys, `jdbc:` anywhere; connector and secret keys (`connector:`, `password:`, `token:` …) in YAML |
| C10 Licence headers | the publisher's copyright at the top of every Java source, script and style |
| C11 Functions answer | loaded into `ModuleRuntime`, every function word has a route that answers 200 |
| C12 Two people at once | no static field that is not final, and no static collection that is not unmodifiable (the class initialiser's `List.of`, `Map.copyOf`, `Collections.unmodifiable…`, `Stream.toList`), in the module's classes; a field that is one value for the whole engine on purpose carries `@EngineWide("why")` (kernel API) |
| C13 Off means gone | switched off, every route answers 404 and nothing stays loaded |
| C14 Absent everywhere | removed, every route answers 410, no contribution is left, Health says REMOVED |
| C15 Class loader unloads | 50 load/unload cycles (`--cycles`, in Maven `-Ddrishti.conformance.cycles`); the class loader is collected each time |

`ModuleToolTest` (in `drishti-module-runtime`) runs these paths without Maven: it generates a module from the archetype's templates,
compiles it, packs it, passes the fifteen checks, and loads, routes and unloads it; it builds a second one with `javac` and a folder and
takes it through the tool's `pack`, `check` and `inspect` into the runtime; a broken copy fails the checks with their words.

### 1.7 A module's console part

The console hosts a module's `console/` part **declaratively** (design: [KERNEL_AND_MODULES.md sections 4.7, 9 and 14.2.1](../architecture/KERNEL_AND_MODULES.md#47-the-console-part-loaded-by-the-drishti-console)):
it reads the files the kernel verified and runs none of them, so a module brings no Python, no Java and no route registration to the
console. Everything is declared in `console/plugin.yaml` (`format: 2`):

| Key | What it gives | Example (the archetype) |
|---|---|---|
| `routes:` | pages under `/m/<id>/…`: `page:` a template of the module (tier 2), `sutra:` a Sutra, opened as the kernel's page of its `function:` (tier 1); the word opens the route (`GREET asha` → `/m/greet/hello/asha`) | `{id: hello, path: "/hello/{name}", page: hello.html, function: GREET}` |
| `menus:` | top-bar entries: `menu:` a menu of the product (`help`, `views`, `economies` …) or a new one of the module; `route:` + `params:`, `href:` or `cmd:` | `{menu: help, label: Say hello, route: hello, params: {name: world}}` |
| `home: cards:` | Home's quick cards (`lens:` to show one in one lens only) | `{title: Say hello, route: hello, params: {name: world}, cmd: GREET world}` |
| `ai: tools:` | the tools the assistant may offer (a name, a description, the route or word it opens; Admin → AI lists them) | `{name: greet_greet, description: Greets a person, route: hello}` |

Help comes from `docs/help.yaml` (`sections:` with `pages:` of `file:` relative to it; one Help section each, `/help/section/module-<id>`).

**Templates** live in `console/templates/` (or `console/pages/`) and are rendered in the module's **own Jinja environment**: they extend
`kernel/page.html` (the console's shell: top bar, command line, theme; blocks `title`, `assets`, `page` or `content`, `aside`, `scripts`),
use the Elements macros of `kernel/elements.html` through `elements` (`elements.card`, `elements.grid`, `elements.header`,
`elements.notice`) and the phone parts of `kernel/phone.html`, and name their own files `<id>:<path>`. Nothing else is reachable: a
template that includes or extends any other console template is refused. The context has `module` (id, version, title), `route`,
`params`, `asset(path)` (`/m/<id>/static/<version>/<path>`, cached a year: the version busts it), `url_for(route_id, **params)` and
`api(path)`, besides the shell's own values.

**Statics** are `console/static/drishti_<module>-*.js|css` (the `drishti_` prefix, theme tokens only); a domain Elements library is
`elements/<lib>/`, served at `/m/<id>/elements/<lib>/<version>/`. Drishti Elements and the vendored libraries are the console's: **a part
that carries a copy of one (by content), a `vendor/` folder, a library with an Elements name, code (`.py`, `.jar`, `.class` …) or an
unprefixed static file is refused whole**, and Admin → Modules and `GET /api/modules/hosted` say why.

**Where parts come from.** The server's module registry (`GET /public/modules/console`; an active version's part arrives as a zip from
`/public/modules/console/<id>/<version>`, kept under the console's data folder, `module-parts/<id>/<version>/`), and the modules the product
ships (`modules/<id>/`, `module_manifests_dir`) while the server lists them loaded. The console asks every
`modules.parts_refresh_seconds` (5) and at once after an apply in Admin → Modules.

**When the module goes** (switched off or removed) every surface goes with it: its pages answer the Module removed A page (410 removed,
404 off), its statics 410/404, its menu entries, Home cards, words (*… is not available: needs module X*), Help section, AI tools and lens
links disappear; an open page of it is frozen under the notice within 15 seconds; saved work that uses it (Sutras, BI reports and
dashboards, baskets in Build; saved searches in Forms; alert rules) stays, marked *needs module X* and not openable, and comes back on reinstall. Nothing of it
is kept per person.

**A page reads its module's API** through the console: `api('genes/TP53')` in a template is `/m/<id>/api/genes/TP53`, which the console
reads from the server's `/api/v1/<id>/genes/TP53` as the person (GET only; 410 removed, 404 off). The page's own script draws the rows
with Drishti Elements (`Drishti.elements.grid`, `Drishti.elements.load('drishti-chart')`).

### 1.8 A module's own visual (the custom-visual SDK)

A module that needs a chart Drishti does not have ships it **as a Drishti Elements extension**, never as a drawing of its own: a **kind
of drishti-chart's registry** (`registerKind(name, {build, label, family, cartesian, sample})`), built from drishti-chart's primitives
(`buildScatter`, `buildSeriesChart`, `buildWaterfall`, the annotations, `toneColour`), in the module's **domain library**
`elements/drishti-<module>/drishti_<module>.js`. Because the chart card draws it, the kind gets the theme, tooltips, Explain on a mark, the
data table, export, zoom and the keyboard like every kind, and the conformance checks hold (no canvas, svg or table of its own, no colour:
tones `good`, `bad`, `warn`, `accent`, `muted` come from the theme).

```bash
mvn drishti:visual -Dname=lollipop -Dtitle="Variant lollipop"     # in a module project made from the archetype
```

writes the library (once) with a kind `<module>.lollipop` started on `buildSeriesChart`, and declares it in `console/plugin.yaml`:

```yaml
visuals:
  - kind: genomics.lollipop
    library: drishti-genomics
    file: drishti_genomics.js
    title: Variant lollipop
    summary: "A protein's variants: a stem and head at each amino acid position ..."
```

**Where it shows.** The module's pages load the library (`/m/<id>/elements/<library>/<module version>/<file>`) and draw the kind with
`new Chart(el, {kind: 'genomics.lollipop', ...})`. The **Elements gallery** asks the console for the hosted modules' visuals
(`GET /api/elements/visuals`), loads each library and gives each kind a page drawn from its `sample()`; a module that is off or removed
is not listed, so its visual is gone from the gallery (and its library answers 404 or 410). The library registers the kind once
(`kindOf` first) and exposes `Drishti.elements.visuals.<module>.ready`.

### 1.9 The genomics sample, step by step

`modules/genomics` is a third party's module (publisher Helix Lab, a sample) made from the archetype and finished by hand: it uses
`drishti-kernel-api` and the plugin API only (C1) and carries no finance. Drishti Genomics (`--distribution genomics`) runs the thin
kernel with it alone ([DISTRIBUTIONS.md section 4](../architecture/DISTRIBUTIONS.md#4-drishti-genomics-sample)).

1. **Generated.** `mvn archetype:generate -DarchetypeArtifactId=drishti-module-archetype -DgroupId=org.helixlab -DartifactId=genomics
   -Dpackage=org.helixlab.genomics -Ddomain=genomics` (the first commit of the module is the archetype's output, unchanged).
2. **The VCF reader** (`VcfConnector`, a `SourcePlugin`): reads `data/samples/*.vcf` inside the package and `<data>/modules/genomics/vcf/`
   (plain or gzipped VCF 4.x); one `variant` per alternate allele, the samples and their `##SAMPLE` cohorts (`Vcf`, `GenomicsData`, one
   immutable snapshot).
3. **The kinds** `gene` and `cohort` (`GenomicsKinds`, a `KindProvider`), computed from the variants and `gene.jsonl`; the contracts in
   `packs/genomics/contracts/`.
4. **The API** (`GenomicsController`, `@ModuleApi("genomics")`): `/api/v1/genomics/variants`, `/genes/{id}`, `/cohorts/{id}`; the Rachana
   function `genomics_af_class(af)`.
5. **One page folder**, `console/pages/explorer.html`, for the six routes (`VARIANT`, `GENE`, `COHORT` and their lists), drawn with the
   Elements macros and `drishti_genomics-pages.js`; the gene's page draws `genomics.lollipop` (section 1.8).
6. **Synthetic data**: `tools/make_samples.py` (seeded) writes two cohorts of twelve samples and 120 variants over eight genes.
7. **Built in the kernel's reactor**: `./mvnw -o package -pl modules/genomics -am` packs `target/dmod/genomics-0.1.0.dmod` and runs the
   fifteen checks at `package`. `GenomicsDistributionServerTest` boots Drishti Genomics with the package dropped in;
   `test_genomics_walk_browser.py` walks it in a browser on the thin jar.

## 2. Building and running from source

### 2.1 Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 or newer (any vendor; Java 21 is the production target) | The enforcer rule in `pom.xml` (`requireJavaVersion [21,)`) stops an older Java with `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …` The bytecode targets 21 (`maven.compiler.release`), so one jar runs on 21 and 25; `.java-version` says `25`, the version to develop on. On Java 25 run the server with `-XX:+UseCompactObjectHeaders` (about 10% less heap); Java 21 has no such flag and refuses to start with it. Java 21 pins a virtual thread's carrier while it blocks inside `synchronized`, so main code uses a `ReentrantLock` for anything that does I/O (`NoBlockingUnderSynchronizedTest` enforces it; see [CLUSTER_GUIDE.md](../architecture/ARCHITECTURE.md#14-concurrency-and-performance)). |
| Python | 3.13 ([the minimum and why](OPERATIONS_GUIDE.md#21-prerequisites)) | The console and the tools. CI uses 3.13. |
| uv | any recent | Creates the console's environment (`uv venv --python 3.13`), which works where the system Python has no `ensurepip` (Debian and Ubuntu without `python3.X-venv`); runs some tools with `uv run --with …` so nothing is installed globally. |
| Docker | optional | Only for the Testcontainers tests (PostgreSQL, Aerospike, Cassandra, ScyllaDB, ActiveMQ, RabbitMQ, MinIO) and `deploy/docker/compose.yaml`. |

Set `JAVA_HOME` in every terminal:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v
```

You should see `Java version: 21…` in the output. Maven itself comes with the repository (`./mvnw`, Maven 3.9.12).

**From a fresh clone to a running stack** (what [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine) and
[data/testdata/README.md](../../data/testdata/README.md) walk through; measured on a 24-core laptop):

```bash
git clone https://github.com/ajsinha/drishti.git && cd drishti
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
./mvnw -q package -DskipTests -pl drishti-app -am                 # 1 min 12 s with ~/.m2 filled
drishti-console/.venv/bin/python tools/drishti.py lake demo           # about 15 min (943 s): 86 Delta tables, sixty days, under data/delta/demo
```

then the server and the console as the run configurations start them ([OPERATIONS_GUIDE.md, Steps 6 and 7](OPERATIONS_GUIDE.md#25-start-the-server)).

### 2.2 Maven commands

Run them from the repository root.

| You want to… | Command |
|---|---|
| Build everything, no tests | `./mvnw -q package -DskipTests` |
| Build only the server and what it needs | `./mvnw -q package -DskipTests -pl drishti-app -am` |
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
`~/.cache/drishti/`, 53 MB kept; see [USER_GUIDE.md](USER_GUIDE.md#12-installing-the-python-runtime)). Without it the console runs
as usual, `GET /healthz` says `"pythonRuntime": "not installed: run tools/fetch-pyodide.sh"` and the Calc panel says how to install it.

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
./mvnw -q package -DskipTests -pl drishti-app -am
DRISHTI_DEMO_ENABLED=false DRISHTI_STUDIO_SAVE=true \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

Server settings follow the same precedence as the console: `drishti-server/src/main/resources/application.yaml`, then
`./application.local.yaml` (git-ignored; imported by `spring.config.import`), then environment variables, then `--key=value`
arguments. For example `--drishti.live.frame=100ms` or `--server.port=18481`. The run configurations in `run-configs/` carry the
settings of each way of starting it (sign-in on with the development admin, the demo source on or off, the QA profile on its own ports
and data folder); [OPERATIONS_GUIDE.md (IDE)](OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm) lists them.

The development loop that works best:

| You changed | Do |
|---|---|
| A Sutra under `config/packs/*/sutras/` or a site Sutra directory | Nothing: `drishti.rachana.hot-reload` reloads it; an invalid edit keeps the last good version |
| A connector file in `config/connectors/` | Nothing: the folder is watched and only that connector restarts |
| A BI file under `config/bi/` | Nothing: the folder is watched (`drishti.bi.watch`, `BiFolder`); a changed file becomes the next version, one that does not parse keeps its last good version and is listed as a problem |
| Java code | Rebuild the module and the server jar (`./mvnw -q -o -pl drishti-app -am package -DskipTests`), restart the server |
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
no merge, and it may run on any branch), `--keep-going` (run every job to its end after one has failed, to see every failure at
once; by default the first failure stops the rest), `--gate` (the gate only, stage 1 below, then stop: no lanes and no push, on any
branch; a check of a few minutes before a drill), `--affected [--base REF] [--dry-run]` (only the Maven modules and test files the diff can reach, for a builder's
iterations: [TEST_HARNESS.md](../architecture/TEST_HARNESS.md) section 3.16; `tools/docs/shots_affected.py` does the same for screenshots). The VaR proof of a patch is the
in-process `VarGoldenMatrixTest`; the server-against-server API comparison runs for releases only. `DRILL_MVN_ARGS="..."` adds Maven arguments to every Maven run (for example
`-Ddrishti.test.forks=1` to run the test classes serially). `DRILL_BROWSER_WORKERS` and `DRILL_OTHER_WORKERS` set the console
lanes' workers (the browser lane's default comes from the memory available), `DRILL_BROWSER_RERUNS` the browser-lane files re-run at a time (default 1), `DRILL_LANE_LIMIT` a console lane's
time limit in seconds (default three hours), `DRILL_GATE_THREADS` the gate build's Maven threads (default `1C`, one per CPU). The
script prints
`drill: mode = full` or `drill: mode = docs`, the machine's CPUs and memory with the workers it chose, each job's time, then a
summary. It is costly (it runs everything): run it once per release, and targeted tests in between.

**Which mode.** The commits since `origin/develop` decide: when every changed file is under `docs/` or `tools/docs/`, is a `*.md` file
or is a `help.yaml`, the drill runs in docs mode by itself; otherwise it runs in full mode. `tools/drill.sh --docs` on anything else is
refused, with the files that are not documentation listed.

**Before anything runs**, the drill refuses to start unless the current branch is `develop` (`drill: must be on develop`; not checked
with `--no-push`), the working tree is clean (`drill: commit your changes first`) and the console's Python environment exists.

**Stage 1, the gate** (a few minutes, all at once; the first failure stops the drill and prints the failed job's log):

- **Static checks** (`gate-static.log`): the copyright headers (`tools/license_headers.py`), CONFIGURATION.md current
  (`tools/settings_catalogue.py --check`), the data contracts and their pages (`tools/contracts.py check`, `docs --check`), the data
  samples' pages, docstrings and type hints of the public Python API (`tools/api_docs.py check-python`), and every pack generator's
  `--check`.
- **The docs and help console tests** (`gate-docs.log`): `test_docs_*.py`, `test_help*.py` and `test_tools_guide.py`.
- **The quick file checks** (`gate-checks.log`): every test marked `static` (it reads files and starts no server, console or
  browser), found by `tools/drillkit/drill_plan.py static`; the console lanes then run with `-m "not static"`, so each runs once.
- **One Java 21 build of the working tree** (`gate-java.log`; `JAVA21_HOME`): `./mvnw -q -o clean package spotless:check -P api-docs -T 1C`,
  with Error Prone, Javadoc with doclint, Spotless and the Java 21 bytecode rule, running only the rule tests (`drishti-it`,
  `TokenScopeGuardTest`, `ArrowAnswersTest`). Its jar is the one stage 2 starts.

**Stage 2** (the long runs side by side, each with its own log in `<data dir>/drill/`; all must pass):

- **The console suite in two lanes**: a **browser lane** (every file that drives Chromium or starts a live stack on the scratch
  ports, handed out by test on as many workers as the memory holds) and an **other lane** (the rest, against the fake backend, a
  file per worker). Each lane runs longest first, from the seconds each file took last time (`<data dir>/drill/durations.json`);
  a browser file over 300 s is flagged in `budget-browser.txt`. How the lanes are sized and why is in
  [TEST_HARNESS.md](../architecture/TEST_HARNESS.md).
- **Re-runs.** A failed console test runs once more, file by file, without xdist: one that then passes is printed as `FLAKY` and
  logged in `<data dir>/drill/flakes.log`; one listed in `tools/drill-known.txt` (its pytest id, `|`, the reason) is reported as known
  and accepted; one that fails twice fails the lane. Each re-run's output is in `rerun-<file>.log`.
- **The Python tool tests** in two lanes (`tools-a.log`, `tools-b.log`): every `tools/**/test_*.py` and `tests/` folder, found rather
  than listed, each file in its own pytest process under a 15-minute limit, plus `make_testdata.py --check` and
  `tools/api_docs.py check-java`.
- **Java 21** (`java21.log`), the production runtime: `./mvnw -q -o clean verify -P api-docs`, every test and rule, containers
  included, in a copy of the committed tree (`DRISHTI_DRILL_COPY_DIR`), test classes in parallel forks (`drishti.test.forks`, 4).
  Anything a test writes at a fixed path carries `${surefire.forkNumber}`; a test must not change a JVM-wide setting for the classes
  after it in the same fork. A failed test runs once more (`FLAKY` when it then passes).
- **Java 25** (`java25.log`; `JAVA25_HOME`, skipped when absent): the same tests in a copy of its own, Docker hidden, at the lowest
  priority.

**After the tests** the drill checks that they left `config/` and the data folder (`DRISHTI_DATA_DIR`, else `data/`) as they found
them: a file added or changed there fails the drill with its name (a server running from the repository turns the data-folder check
into a warning). Every test writes to its own scratch folder (`@TempDir`, pytest's `tmp_path`, `--connectors-dir`,
`DRISHTI_CONNECTORS_DIR`), and the drill's temporary files go to `data/tmp/drill` (`DRISHTI_DRILL_TMP`), never the system `/tmp`.

**Then** it pushes `develop`, fast-forwards `main` to `develop`, pushes `main`, returns to `develop`, and prints
`drilled (full): <the last commit>`.

**Docs mode** runs the gate without its Java build, then the other documentation console tests and the Python tool tests, then the
same checks, push and merge.

**Where the time goes.** On a 24-CPU, 64 GB machine the gate takes about four minutes; the browser lane is the longest job (its
slowest file, `test_render_console_browser.py`, about twenty minutes); Java 21 takes about 25 minutes and Java 25 about 28. A full
drill that passes takes about an hour; one that fails at the gate stops within a few minutes.

The drill removes its own temporary files on exit. The console suite needs `pytest-xdist` 3.7 or newer (in `requirements-test.txt`).
The inotify watch limit of a loaded desktop (`fs.inotify.max_user_watches`) can make the Sutra hot-reload tests fail in any mode:
that is the machine, not the drill.

## 3. Project rules

Some rules are enforced by tests, so a plain `./mvnw verify` fails when they are broken. Others are conventions that reviews hold to.
Both kinds are listed here.

| Rule | Enforced by |
|---|---|
| Every source file carries the copyright header | `LicenseHeaderTest`, `tools/license_headers.py` (drill, CI) |
| No non-UI source file over 2000 lines | `SourceFileSizeTest`; `drishti-console/tests/test_assets_policy.py` for console Python |
| One-way module dependencies, a Spring-free SPI, controllers only in `server`, no field injection, no `Serializable` | `ArchitectureRulesTest` |
| No `synchronized` around blocking I/O (virtual threads) | `NoBlockingUnderSynchronizedTest` |
| Front end vendored: no CDN, no external URL, no inline script, handler or style | `drishti-console/tests/test_assets_policy.py`, the console's Content Security Policy |
| Phone: no sideways scroll, targets of at least 44 px | `test_page_widths_browser.py` and the pages' own browser tests |
| Every sitemap page and top-bar link opens clean and within 5 s; the key pages laid out at 1366 and 1920 px, dark and light | `test_page_walk_browser.py` (TEST_HARNESS.md, "The page walk": how to run it alone or against a running console) |
| No hidden file or folder of our own; everything written at run time under the data folder | `tools/test_no_hidden_names.py` |
| Generated files match their generators | `--check` in the drill |
| Sutras shown in docs and pack guides are valid | `DocumentedSutrasTest` |
| Every pack Sutra loads without problems | `PackSutrasTest` |
| Warm views stay fast | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99` |
| Every relative link and anchor in the documents resolves; an obsolete design is deleted, not archived: git keeps its history, so move any live content into the current document first, and a document that still mentions it names the release tag where it last lived, as text | `drishti-console/tests/test_docs_rot.py` |
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

### 3.3 Error Prone and Spotless

- **Error Prone** checks every compile. A finding at error level stops the build with the check's name, for example
  `[ReturnValueIgnored] Return value of 'apply' must be used`. Fix the code. If the finding is intended, add
  `@SuppressWarnings("ReturnValueIgnored")` on the smallest element, with a comment saying why. Warnings (missing `@Override`, Javadoc
  summaries) do not fail the build but are worth fixing when you touch the file.
- **Spotless** runs at `verify`. On a failure it prints the diff; `./mvnw -o -q spotless:apply -pl <module>` fixes unused imports,
  trailing whitespace, missing final newlines and tabs. Run it before every commit of Java code.

### 3.4 Architecture rules

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

### 3.5 Object-oriented, concurrent and configuration-driven

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
  [the error code table](API_REFERENCE.md#error-code-table) and a test keeps that table in step with `ErrorCode`.
- **Imperfect data never fails a view.** A panel whose data does not fit is returned empty ("No data available"), never an exception
  (`Binder.bind`, `Emptiness`, `ImperfectDataTest`).
- **The Rachana-EL is closed.** Functions must be pure, total (never throw on odd input) and cheap; adding one is an amendment to
  ADR-003.

### 3.6 Data access: no SQL in code

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

### 3.7 The front end is vendored

The console uses Bootstrap, Bootstrap Icons, ECharts (and ECharts GL), CodeMirror (with its Python mode), Vega and Vega-Lite (the
`vega-lite` visual and a Python visual's output), copied under
`drishti-console/web/static/vendor/`; `vendor/VENDOR.md` lists each library with its version, licence, size and whether the repository
holds it. No FINOS Perspective or DuckDB-Wasm: **the servers compute every number and the
browser draws**, so no BI page loads WebAssembly (a report's `perspective-*` visuals are drawn as Drishti's own grid of the server's
answer). Pyodide, for Calc only, is installed by script, not committed (below). There is no npm, no build step and no CDN (ADR-006).
The Content Security Policy in `drishti-console/core/app.py` is:

```text
default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self';
connect-src 'self'; frame-src 'self'; worker-src 'self'; frame-ancestors 'self'
```

Two files have their own, narrower policy. Calc's worker, `/static/js/drishti_calc-worker.js`, is served with
`default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src <host>/static/ <host>/pyodide/`, so it alone may compile
WebAssembly (Pyodide) and it can fetch nothing but static files and the runtime (never an `/api` route, which would carry the user's
session).
For Pyodide, `tools/fetch-pyodide.sh` downloads a pinned release, checks its SHA-256 and unpacks what Calc needs into
`drishti-console/web/static/vendor/pyodide/` (git-ignored); the console serves it at `/pyodide/<version>/`
([USER_GUIDE.md](USER_GUIDE.md#12-installing-the-python-runtime)).

So templates may not contain inline `<script>` blocks, `on…=` handlers or `style=` attributes, and nothing may load from another origin.
`drishti-console/tests/test_assets_policy.py` checks all of this. Behaviour goes in a file under `web/static/js/`, wired by `data-*`
attributes; widths and colours come from classes and `data-*` values that a script applies (bars and gauges use `data-w`, see
`drishti_view.js`).

### 3.8 Git

- Work happens on `develop`; `main` only moves by fast-forward through the drill.
- Commits are authored as the repository owner (`ajsinha`). Do not add co-author or tool-attribution trailers.
- Commit messages say what changed for a user or an operator, in plain sentences (see `git log`).

## 4. How a request travels through the code

This section follows `TRD MX-20000001 <GO>` from the keyboard to the screen, then the live updates that follow, then a BI question
and its live push.

### 4.1 From the command line to a view

| Step | Where | What happens |
|---|---|---|
| 1 | `drishti-console/web/templates/terminal/_topbar.html`, `web/static/js/drishti_command.js` | The command line is a plain form (`action="/go"`). `drishti_command.js` adds the dropdown: it calls the console's one search, `GET /api/search?in=command` (`core/search.py`), which calls the server's `GET /api/v1/command/suggest` (`CommandController.suggest` → `SuggestionService`), filtered by `Entitlements.filter`. With a union of trade connectors, the hits of all three are merged. |
| 2 | `drishti-console/routes/terminal_routes.py` `go()` | Text that looks like a search (`<MN> where …`, `order by`, `limit`) is redirected to `/s`. Otherwise `BackendClient.command()` posts `{"text": "TRD MX-20000001"}` to `POST /api/v1/command`. |
| 3 | `drishti-console/core/backend.py` `BackendClient._send` | Every call carries the caller (`X-Drishti-User`, and `Authorization: Bearer …` when sign-in is on) and the business date (`X-Drishti-As-Of`, from `core/asof.py`, which `AuthGate` in `core/app.py` sets from the `drishti_asof` cookie). An unreachable server becomes `BackendError(503, "DRS-5003", …)`. |
| 4 | `server.security.PathGuard`, `server.security.TokenFilter` | `PathGuard` first refuses a request line under `/api` or `/actuator` that does not spell its path plainly (`;`, a needless `%`-escape, a dot or empty segment: `400 DRS-5001`); filters decide on `RequestPaths.routed`, the decoded path that is served, never the raw URI. For every `/api/v1/**` request: with security off, the caller is `Principal.anonymous(X-Drishti-User)` with every role; with it on, the bearer token is verified (`TokenVerifier`: a session token signed with `DRISHTI_TOKEN_SECRET`, or a personal API token `drk_…`) or the answer is `401 DRS-5010`. The principal is a request attribute (`Principal.ATTRIBUTE`). |
| 5 | `server.api.AsOfResolver` | Gives any controller parameter of type `AsOf` the request's business date: `X-Drishti-As-Of`, else `?asOf=`, else the current business date (`BusinessDates.parse`). |
| 6 | `server.api.CommandController.command` | `CommandParser.parse` turns the text into an `EntityRef` (`trade/MX-20000001`). `Entitlements.requireOpen` checks the caller's roles and active packs (`403 DRS-5002`). If the entity exists (`SourceRouter.fetchAll`), the answer is its ref (`{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD",…}`); otherwise the text becomes a pick list (`SearchQuery.pick` + `StructuredSearch.run`), and exactly one match opens directly. An unreadable command is `400 DRS-4001`; a search that names a field the kind does not have is `400 DRS-4004` with suggestions (`no trade has a field 'NOTIONAL'; did you mean LEGS.CASH_FLOWS.NOTIONAL, LEGS.NOTIONAL?`). |
| 7 | `terminal_routes.go()` | Redirects (303) to `/v/trade/MX-20000001`, or to `/s?q=…` for a pick list. |
| 8 | `terminal_routes.view()` | `BackendClient.view()` calls `GET /api/v1/views/trade/MX-20000001`. |
| 9 | `server.api.ViewController.view` | `requireOpen` again, then `ViewPipeline.view(ref, asOf)` (timed as the `drishti.view` metric), then `Entitlements.restrict` (disables links the caller may not follow), then `RecentEntities.touch` (feeds "recent" in the dropdown). |
| 10 | `engine.ViewPipeline` | The pipeline (below) returns a `ViewModel`. |
| 11 | `terminal_routes.view()` | Splits the panels into `main` and `right` by `area`, renders `terminal/view.html`. Each panel is drawn by the `panel(p)` macro in `web/templates/_macros/panels.html`; `web/static/js/drishti_view.js` adds charts (vendored ECharts), tabs, F-keys and the raw-JSON drawer. |

### 4.2 Inside `ViewPipeline`

`ViewPipeline` (`drishti-engine`) documents itself as "command → fetch → classify → fingerprint → layout → link → bind". In code:

1. **Fetch.** `SourceRouter.fetch(ref, asOf)` picks the plugins that serve the kind (the kind's route, the union it belongs to, named
   connectors, `default-route`), tries dated sources first for a picked date, and gives up after `drishti.sources.fetch-timeout` (10s).
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
| 1 | `terminal/view.html` | `drishti_live.js` is loaded only when `vm.provenance.live` is true. A picked business date is a static snapshot: no stream. A Delta lake is not live (its documents change by day); live entities come from a stream (Kafka, ActiveMQ, RabbitMQ, Redis) or the demo source. |
| 2 | `web/static/js/drishti_live.js`, `web/static/js/drishti_channel.js`, `web/static/js/drishti_live-hub.js` | `drishti_live.js` subscribes `view:trade/MX-20000001` on `window.Drishti.Channel`. `drishti_channel.js` hands every tab's subscriptions to one hub per browser (`drishti_live-hub.js`, in a SharedWorker, or in a tab elected with a Web Lock that relays over a BroadcastChannel), which keeps **one** `EventSource` for the whole browser, to the console's `GET /api/channel?s=…`, and adds or removes subscriptions with `POST /api/channel/{cid}` instead of reconnecting. Workspace panes (iframes) share their parent's subscriptions. Tests: `tests/test_live_hub.py` (the protocol, in Node), `tests/test_live_tabs_browser.py` (Chromium). |
| 3 | `drishti-console/routes/api_routes.py` `channel()` | For each subscription it opens an upstream stream (`BackendClient.stream` → `GET /api/v1/views/{kind}/{id}/stream`; `alerts`, `monitor:<name>` and `mq:<id>` go to their own server streams), in **detached** tasks, and multiplexes everything into one SSE response as `{"ch": "<subscription>", "d": …}`. Frames are rewritten by `_view_event`: each patched panel is rendered to HTML with the same `panels.html` macro as first paint (charts stay data). A watchdog ends a channel nobody has read for five seconds; a comment every ~15s keeps proxies from closing it. |
| 4 | `server.api.StreamController.stream` | Takes a slot from `LiveStreamSlots` (cap `drishti.live.max-streams`), builds the initial view, and creates a `ViewStream`. A writer on a virtual thread sends a `view` event, then a `frame` event per frame, or a heartbeat comment (`drishti.live.heartbeat`, 15s). Each client has its own latest-wins `FrameMailbox`, so a slow client never slows others. |
| 5 | `engine.live.TopicHub` | One topic per live entity, shared by every view of it, holding a single source subscription (`SourceRouter.subscribe` → the plugin's `subscribe`). Ticks land in a latest-wins slot and are delivered at most once per frame (`drishti.live.frame`, 50ms). The subscription closes with the last listener. |
| 6 | `engine.live.ViewStream`, `PatchDiffer` | On a tick, the view is rebuilt (`ViewPipeline.build`) and `PatchDiffer.diff(before, after)` produces `Patch`es; they travel as a `Frame`. A deletion (`EntityDocument.deleted()`) is sent as one `deleted` patch instead, and nothing is rebuilt until the entity comes back (`restored`). |
| 7 | `drishti_live.js` | Applies the patches in place: strip cells get new text and tone, panels are swapped for the server-rendered HTML, charts move to new data, and changed values flash. A `deleted` patch greys the view under a "deleted at" banner. |

[DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#1-live-updates) has the details: frames, coalescing, reconnects and the latency metrics.

### 4.4 A BI question: model query, DEL and SQL

The BI engine lives in `drishti-server` under `server.bi` and follows the model architecture of
[ARCHITECTURE.md section 28](../architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer): **datasets** (one governed table each, every pack kind publishing
one automatically, `bi.layer.PackDatasets`), **data models** (datasets in a star, relationships, measures written in **DEL**, the
Drishti Expression Language, [RACHANA_REFERENCE.md › Rachana-EL expressions](RACHANA_REFERENCE.md#rachana-el-expressions)), and **consumers** (reports, boards, notebooks, alerts, subscriptions,
exports, embeds, AI) each bound to one model. Every consumer asks a model the same way: a **model query** (MQ), a versioned JSON or YAML
question ([the API reference](API_REFERENCE.md#5-models-and-the-model-query-language-apiv1bi)). Drishti SQL ([the API reference](API_REFERENCE.md#8-drishti-sql-apiv1bisql)) is a second front end onto the same
plan. Nothing is computed in the browser.

| Step | Where | What happens |
|---|---|---|
| 1 | `web/static/js/drishti_bi-consume.js` (a report, a board, Analyse) | A visual becomes one model query; the browser posts it to the console's `/bi/api/mq` (or `/bi/api/models/{id}/query`), which passes it to the server as the signed-in user. |
| 2 | `server.bi.mq.MqController` (`POST /api/v1/bi/mq`), `bi.sql.SqlController` (`POST /api/v1/bi/sql`) | Read on the model is checked (`BiAccess`), then `MqParser` (or `DsqlParser` for SQL) reads the question. |
| 3 | `bi.mq.MqService`, `MqValidator`, `MqCompiler` | Validates the question against the model (names, types, additivity, the currency guard, `DRS-91xx` problems with the field to fix), and compiles it to the engine's `QueryRequest`s: relative windows from the as-of date and the calendar, `top` as its own grouped request, totals per level for measures that do not add up. |
| 4 | `bi.plan.Planner`, `SecurityRewriter`, `Guards`, `Optimiser`, `TierPlanner` | The logical plan: each measure aggregated at its own grain, facts met on shared fields, the reader's row rules and masks applied **before** any aggregation or join, guards (a join that would repeat rows is refused), then each node placed on a tier: the source (a SQL store that can aggregate), the console backend (Python nodes) or the Java tier. |
| 5 | `bi.plan.ResultCache`, `bi.engine.QueryService` | The answer cache, keyed by plan hash, security signature and the model data's **generation** (any load, refresh or live batch moves it, so a stale answer is never served). Then admission (server and per-user concurrency) and the smallest layer that can answer. |
| 6 | `bi.engine.Layers` | The BI store (`<data>/bi-store`): typed Parquet **columns** per table and business date, read once from the connectors (`ServerSourceGateway` → the union of trade connectors, a lake, a database) and **rollups** built from them; single-flight per partition. A data-load signal (`POST /api/v1/packs/{pack}/loads`, sent by `lake build --signal` and `lake demo`) clears the date's partitions; each partition also records the source data version it was read from (a Delta date's files), so a rebuilt lake is read again even without a signal. |
| 7 | `bi.engine.DuckDbEngine`, `SqlEmitter` | One in-process DuckDB runs the parameterised SQL over the layer's Parquet files, each query with a watchdog at the time limit; row and byte limits apply. |
| 8 | `MqService` | Writes the one answer shape: typed columns with stable ids, additivity and masks, the rows, the totals, and the meta (as-of, dates, layer, tables, masks, warnings, cache, time, the data generation). The console draws it with ECharts or Vega-Lite. |

`EXPLAIN` (an MQ with `explain: true`, or `EXPLAIN SELECT …` in SQL) answers the plan with the tier and time of each node.

### 4.5 Live BI: pushed, not polled

| Step | Where | What happens |
|---|---|---|
| 1 | `drishti_bi-consume.js` `follow()` | A live visual posts its model query to `/bi/api/mq/subscribe` (`POST /api/v1/bi/mq/subscribe`, `{query, throttle, deliver}`) and gets its first answer and a subscription id, then subscribes `mq:<id>` on the browser's one channel (section 4.3). A closed tab ends its standing queries (`DELETE …/mq/subscribe/{id}`, sent with `keepalive`); an unread one is dropped after `drishti.bi.mq.idle-close` (60 s). |
| 2 | `bi.engine.LiveTables` | For a dataset whose time mode is `live`, one in-memory table per live table, started on first use: it reads the kind once and subscribes to its entities through the connectors that push (a stream, the demo source); nothing in it is per reader. |
| 3 | `bi.engine.GenerationBus` | Every change of the data a model reads is published once, in order: a live batch with its change set, a data load or refresh for a business date (`backdated` when the date is past), a history sample. |
| 4 | `bi.mq.StandingQueries`, `StandingQueryRegistry`, `Maintainer` | Only the subscriptions on the model whose data moved are evaluated, grouped by (plan hash, security signature): one evaluation per group however many viewers share it, each delivered to its own throttled, latest-wins mailbox. A group is kept current by difference (sums and counts), by re-asking the touched groups, or on a tick when past the model's maintenance budget. |
| 5 | `MqController` `GET /api/v1/bi/mq/stream` → the console's channel | Server-sent `answer` events (at most one per throttle; the latest state wins) and `status` events. A visual's chip says **Live** while changes flow, **Listening** while it follows data that has not changed (a daily lake), and why a subscription was refused. |

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
import com.ash.drishti.kernel.api.security.Principal;
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

**4. Document it.** Add the endpoint to [API_REFERENCE.md](API_REFERENCE.md) and the new code to its [error code table](API_REFERENCE.md#error-code-table), and to the code table in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#21-troubleshooting) (the console tests `test_docs_api_endpoints.py` and `test_docs_error_codes.py` fail when one is
missing). The OpenAPI document at `/api/docs` picks the endpoint up by itself.

**5. Call it from the console** (if a page needs it): add a method to `BackendClient` in `drishti-console/core/backend.py`, following
its pattern (`return await self._get("/mnemonics", ident)`), and a matching method to `FakeBackend` in
`drishti-console/tests/conftest.py`.

### 5.2 Add a source plugin

See [section 10](#105-the-sourceplugin-spi): the SPI, testing with the testkit contracts and a
worked, contract-tested plugin end to end. Declare its settings (`settingSpecs()`, or `drishti/plugin-settings.yaml` for a shipped
plugin) so Admin → Connectors builds its form; if the BI store should notice when its data changes without a data-load signal,
implement `SourcePlugin.dataVersion(kind, date)` (the Delta plugin answers each date's file set). Then add the module to the plugin table
in §1.2 and in `README.md`.

### 5.3 Add a Rachana-EL function

**Goal:** `round(x, digits?)` rounds half up: `round(2.345, 2)` is `2.35`.

Adding a function changes the grammar: record it as an amendment to
[ADR-003](../architecture/adr/003-sutra-is-yaml-with-a-closed-expression-language.md). Functions must stay pure, total (odd input gives
`null`, never an exception) and cheap. (Rachana-EL lays out views; BI measures are written in DEL, which has its own catalogue,
`bi.expr.FunctionCatalog`, and its own guide, [RACHANA_REFERENCE.md › Rachana-EL expressions](RACHANA_REFERENCE.md#rachana-el-expressions).)

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
[BUILD_GUIDE.md](BUILD_GUIDE.md).

### 5.4 Add a panel kind

See [RACHANA_REFERENCE.md (developing a panel kind)](RACHANA_REFERENCE.md#developing-a-panel-kind): the complete, tested walk-through (the `metric` tile end
to end: grammar, binder, console, workbench, export, accessibility and the guard tests).

### 5.5 Add a pack

See [BUILD_GUIDE.md](BUILD_GUIDE.md): the complete walk-through (a help-desk pack from an empty folder), every
`pack.yaml` key, generators, signing and the tests that cover packs. Users and admins: the packs that ship, turning them on and who sees which are in [BUILD_GUIDE.md](BUILD_GUIDE.md). Give the pack's documents
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
{% block scripts %}<script src="/static/js/drishti_command.js?v={{ ASSET_V }}"></script><script src="/static/js/sources.js?v={{ ASSET_V }}"></script>{% endblock %}
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
`web/static/img/drishti_mark.svg`), filling a navy tile with the eye's outline in white, so they read on light and dark tab bars. They
are generated, never drawn by hand, into `drishti-console/web/static/img/icons/`:

| File | Size | Used by |
|---|---|---|
| `drishti_favicon.ico` | 16, 32 and 48 px in one file | `/favicon.ico` (browsers ask for it on their own) and `<link rel="icon">` |
| `drishti_favicon.svg` | scalable | `<link rel="icon" type="image/svg+xml">` in `base.html`; modern browsers prefer it |
| `drishti_favicon-16.png`, `drishti_favicon-32.png`, `drishti_favicon-48.png` | as named | the `.ico`; 16 and 32 px use heavier strokes so the mark survives at tab size |
| `drishti_apple-touch-icon.png` | 180 px, square corners | iOS "Add to Home Screen" (iOS rounds the corners) |
| `drishti_icon-192.png`, `drishti_icon-512.png`, `drishti_icon-maskable-512.png` | as named | `drishti_manifest.webmanifest`; the maskable one keeps the mark inside the 80 % safe zone |

```bash
python3 tools/make_icons.py            # writes every file above (needs Inkscape and ImageMagick)
python3 tools/make_icons.py --check    # exit 1 when an icon is missing or drishti_favicon.svg is not what the script makes
```

Change the colours, the fill or the stroke weights in the script, never in the files. `tests/test_icons.py` checks that the files
exist, that `/favicon.ico` answers, and that the pages and the manifest link them.

#### Regenerating the element stylesheet

`<drishti-view>` (Drishti Elements, [EMBED_GUIDE.md](EMBED_GUIDE.md)) styles its Shadow DOM with a **generated** sheet, not the
console's own: `drishti-console/web/elements/drishti-view.css` and `drishti-view.manifest.json`. It is the console's tokens, theme,
terminal, layout and gradients sheets with `:root`, `html` and `body` rewritten to `:host`, no `@font-face` (the element registers the
icon font with the document's `FontFace` API), and of Bootstrap Icons only the glyphs that `templates/_macros/panels.html`,
`templates/_macros/view.html` and the scripts that run inside the element name. Regenerate it after changing any of those sheets, a
macro, `drishti_bi.css` or the report styles, or an icon in such a script:

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

### 5.9 The multi-select and the date fields

The console draws its pickers with **drishti-inputs** (Drishti Elements, [ELEMENTS.md](../architecture/ELEMENTS.md) section 8.2),
loaded on every page by `base.html`. A form stays a plain server form: `drishti_app.js` calls `upgradeFields(document)`, which turns
every `<input type="date">` into the business-date picker (the field becomes a hidden input with the same name and value, so the form
sends `2026-11-06` as before) and every group marked `data-multiselect` with more than six checkboxes into the multi-select (chips, a
search field, *Select all* and *Clear*; the **real checkboxes** stay in the form, so the criteria encoding is unchanged). The
query-by-form renderer marks every enum field with more than three values `data-multiselect` (`terminal/form.html`; the renderer
decides by option count, no pack is hard-coded); a dependent list's labels carry `qf-dep-off` when they do not apply and the
multi-select offers only the others. `drishti_controls.js` builds a Sutra's `multi-select`, `date` and `date-range` controls with
`MultiSelect`, `DatePicker` and `DateRangePicker` (the server's presets). Inside `<drishti-view>` drishti-inputs is one of the
allow-listed enhancer bundles (`routes/embed_routes.py` `ENHANCERS`, `web/embed/assets.js`), loaded before `controls`, with its style
sheet in the view sheet and on the host page (a picker's list floats in the page's body). Tests: `test_form_browser.py` (multi-select,
search, keyboard, URL, phone), `test_controls_browser.py`, `test_embed_controls_browser.py`.

### 5.10 Write a view enhancer (the `init(root)` contract)

A script that makes a rendered view interactive (sorting, charts, popovers) must work on the console's page and inside a
`<drishti-view>` Shadow DOM, from one copy of the code ([ARCHITECTURE.md section 30](../architecture/ARCHITECTURE.md)). The contract:

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

### 5.11 Retry and live-reload utilities

Two small, Spring-light pieces in `drishti-common` serve every area that keeps something running from files (packs, connectors, AI
providers, MCP servers, BI artifacts). Operator view: [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#138-retries-what-failed-is-tried-again-by-itself).

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
| Micro-benchmarks | `drishti-benchmarks` | JMH, run by hand | `HotPathBenchmark` ([CLUSTER_GUIDE.md](CLUSTER_GUIDE.md#16-tuning) has the commands) |
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
  JUnit's `@TempDir`. Never write under a bare `target/...` path: per-test folders there survive every run until `mvn clean`;
  a Python test that runs `lake build` or `lake demo` sets `DRISHTI_DATA_DIR` or `DRISHTI_BI_STORE` to a temporary folder and
  passes `--server` (or `--no-signal`) so it never writes into the repository's `data/` or signals a real server.
- Keep local test data small (a few thousand trades): a laptop is not a scale-test machine.
- Prefer one assertion chain that reads like the behaviour (`assertThat(p99).as("warm p99 ms").isLessThan(50)`).
- Name tests as sentences: `aMissingFigureIsAnEmptyPanelNotAnError`.

## 7. Debugging

### 7.1 Where to look

With sign-in on (the run configurations), every `/api/v1` call needs `-H "Authorization: Bearer <token>"`: a personal API token
([OPERATIONS_GUIDE.md, Step 10](OPERATIONS_GUIDE.md#28-a-personal-api-token-for-the-command-line)). With it off, add
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
| Is a Sutra broken? | `curl -s localhost:18480/api/v1/sutras/problems` (`{}` when none); see [OPERATIONS_GUIDE.md › A Sutra is broken](OPERATIONS_GUIDE.md#202-a-sutra-is-broken-or-an-edit-does-not-show) |
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
LOGGING_LEVEL_COM_ASH_DRISHTI=DEBUG java -jar drishti-server/target/drishti-server-*-exec.jar
```

The console runs Uvicorn with access logs off (`access_log=False` in `run_drishti_web.py`). The run configuration **Drishti server
(debug-friendly)** is the one to start with **Debug** in IntelliJ: sign-in off (no token to mint while you step through), a 1 GB heap, port
18580 and the QA data folder `data/qa/data`, so it runs beside a normal server ([OPERATIONS_GUIDE.md (IDE)](OPERATIONS_GUIDE.md#3-other-ways-to-build-and-run-maven-intellij-idea-and-pycharm)).

### 7.3 Debugging a live view

1. Does the view say **Live** in the top bar? A picked business date is static: no stream is opened. On the demo lakes with the demo
   source off nothing ticks: start with `DRISHTI_DEMO_ENABLED=true` and open a live sample (a curve, an FX spot, a trade valuation,
   [the demo plugin's README](../../plugins/drishti-plugin-demo/README.md)).
2. `provenance.live` in the view JSON must be `true`; only live sources (`SourceCapabilities.live`) tick.
3. Watch the server stream directly; you should see an `event:view` and then `event:frame` lines:

   ```bash
   curl -sN localhost:18480/api/v1/views/ir-curve/CRV-USD-BASIS/stream -H "Authorization: Bearer $DRISHTI_TOKEN" | head -c 600
   ```

4. There should be exactly one `/api/channel?s=…` request for the whole browser, staying open: in the network panel of the SharedWorker
   (`chrome://inspect/#workers`), or of the leader tab when there is no SharedWorker. `Drishti.Channel.transport()` in a tab's console
   says which (`shared-worker`, `leader`, `follower`).
5. If ticks arrive but the page does not change, check the browser console for errors in `drishti_live.js`.

For a live BI visual: its chip says **Live**, **Listening** (following, nothing has changed: a daily lake) or why it was refused; the
server's `GET /api/v1/bi/mq/stream` sends `answer` and `status` events (section 4.5).

## 8. Common pitfalls

1. **Starlette cancel scopes in streaming responses.** In `drishti-console/routes/api_routes.py`, upstream work for `/api/channel` runs
   in tasks created with a **fresh** `contextvars.Context` (`detached()`), carrying only the business date and "known at". A task started
   from the request's context inherits Starlette's (anyio's) cancel scope, which, once the request has ended, cancels every `await` in it
   for good: clean-up never completes, the console's connections to the server stay open, the pool runs out, and the UI freezes. End
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
   [§3.5](#35-object-oriented-concurrent-and-configuration-driven)).
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

### 8.1 Why these few names start with a dot

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
| `_rels/.rels` | the Excel file format (OOXML) names it so: a workbook template's package relationships (the Capacity and Playground templates) |

Everything else is visible. The user's own `~/.cache/drishti*` (downloads of the fetch tools) follows the operating system's convention
and is outside the repository.

## 9. Releasing

Releases are numbered `MAJOR.MINOR.PATCH` and tagged `vX.Y.Z` on `main` (`git tag` lists `v1.0.0` …). The last `Release X.Y.Z` commit
(`git show`) shows every file a bump touches.

1. **Start clean on `develop`** with everything for the release merged and drilled.
2. **Bump the version** everywhere it is written. Find the old version first:

   ```bash
   git grep -n -F '<the old version>' -- '*pom.xml' drishti-console/core/app.py deploy README.md docs
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
   [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine) says, and follow the tutorials; `curl -s localhost:18480/api/v1/about` reports the
   `version`.
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
   [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md) covers deployment and upgrades.

## 10. Connectors: choosing, combining and writing one

This chapter is for the person who connects Drishti to data: choosing among the nineteen plugins that ship, combining
and operating them, and, when none fits, writing a new one. The exact settings, data layouts, health texts and limits of each
connector live in its README (`plugins/drishti-plugin-<name>/README.md`; the built-in types in `docs/connectors/types/<type>/README.md`),
and this chapter links there instead of repeating them. Named connectors are **files**, one YAML file per connector in
`config/connectors/`: the folder, the format, live reload and the Admin page are in [DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors).

| If you want to… | Go to |
|---|---|
| understand the words (entity, kind, connector, route, dated, live) and how a read picks a connector | [Concepts in plain words](#101-concepts-in-plain-words) |
| pick a connector for your data | [Which connector should I use?](#103-which-connector-should-i-use) |
| see each shipped connector in one screen, with a minimal configuration | [The shipped connectors](DATA_GUIDE.md#82-which-connector) |
| write your own connector | [The SourcePlugin SPI](#105-the-sourceplugin-spi) and [A worked example](#1013-a-worked-example-the-dayfolder-plugin) |
| run several connectors together, check them, fix them | [Combining connectors](DATA_GUIDE.md#84-combining-connectors), [Operating connectors](DATA_GUIDE.md#912-health-and-problems) (health, caches, disk state, timeouts and capacity), [Troubleshooting](OPERATIONS_GUIDE.md#216-connectors) |

### 10.1 Concepts in plain words

#### Entities, kinds and documents

Everything Drishti shows is an **entity**: a trade, a counterparty, a curve, a margin call. Each entity has a
**kind** (`trade`, `counterparty`, [`ir-curve`](../contracts/samples/market-data.md#ir-curve) ([contract](../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve))) and an **id** (`MX-20000001`, `CP-ALDERSHOT`). The data behind an
entity is one **document**: a JSON object, as your system holds it. Drishti lays the document out on screen by
itself (and with a Sutra, if a pack ships one for the kind), so you do not reshape your data for Drishti; you only
tell Drishti where to find it.

A user opens an entity by typing `<MNEMONIC> <ID> <GO>` in the terminal, for example `TRD MX-20000001 <GO>`. The mnemonic
(`TRD`) comes from a pack and stands for a kind (`trade`). A connector can serve any kind, but a user can only type a
kind that some enabled pack gives a mnemonic (or an id pattern) to; through the API any kind can be read
(`GET /api/v1/views/{kind}/{id}`).

#### Plugin, connector, connector file, route

| Word | What it is | Example |
|---|---|---|
| **plugin** | a piece of software that knows how to talk to one *type* of store | `jdbc` talks to databases, `kafka` to Kafka |
| **connector** | one plugin pointed at one *particular* store, with a name and its own settings | `desk-db`: the `jdbc` plugin, pointed at `jdbc:postgresql://db.bank.example/desk` |
| **connector file** | one YAML file, `config/connectors/<name>.yaml`; the file name is the connector's name. Connectors belong to the site, not to a pack ([DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors)) | `config/connectors/trade-endur.yaml` (Delta Lake) |
| **pack template** | what a pack suggests for a connector it names (its `pack.yaml` `connectors:` mapping or `connector-templates:`); the server writes it to the file once, at the first start, and never overwrites the file | the trading pack's templates for `trade-endur` (Delta Lake) and `trading-stream` (Kafka) |
| **site connector** | a connector file no pack suggests | anything you add yourself |
| **route** | "for this kind, ask this connector first" | `trade: trade-endur` |
| **default route** | the connector asked second, for every kind | `demo` (the sample data), as shipped |

A plugin can also run **as itself**, without a connector name, under `drishti.sources.plugins.<plugin>`. That is how
the shipped `demo` and `file` run. It is fine for one store; for anything more, use named connectors: you can run the
same plugin several times, each connector shows under its own name in health, and documents say which connector they
came from.

#### Dated and undated; snapshot and effective

A **dated** connector keeps history: ask it for 29 September and it answers with the data for 29 September. Delta
Lake, the database table mode, Aerospike, dated folders on disk or in S3, and the public feeds are dated. An
**undated** connector only knows "now": a REST service, a Kafka topic, a message queue.

Dated stores keep each kind in one of two ways:

| Mode | Stored | A read for a date returns |
|---|---|---|
| `snapshot` (the default) | every entity, in full, for every business date | the newest date on or before the one asked, but no older than `lookback-days` (10); an entity missing from that date is gone |
| `effective` | a row only when the entity changes (reference data: counterparties, books) | each entity's last row on or before the date, however old |

#### Live and picked dates

The top bar of the console has a business date. By default it shows **Live** (the current business date, latest
version): views can tick as new data arrives. When a user picks an earlier date, or the current date explicitly, the
view is a **static snapshot** for that date. API callers choose the same way with `X-Drishti-As-Of: 2026-09-29` or
`?asOf=2026-09-29`; `X-Drishti-Known-At` / `?knownAt=` asks for the data *as it was known* at an instant (before
later corrections; Delta Lake and Iceberg only: other dated connectors refuse it with `DRS-1007`).

A **live** connector pushes changes to open views (Kafka, ActiveMQ, RabbitMQ, and the demo samples). The others are
read when a view opens.

#### How a request picks a connector

This is the single most useful thing to understand. For a read of `<kind>/<id>`, Drishti
(`SourceRouter` in `drishti-engine`) makes a list of candidates:

1. the kind's **route**, if it names a running connector that serves the kind;
2. the **default route** (`demo`), if it serves the kind;
3. **every other** running connector that serves the kind, **in the order they were registered** (a connector keeps its place in the order when its file changes or it is restarted). A connector with no `kinds` list serves *every* kind
   (the shipped `demo` and `file` do; so does a Kafka or message-queue connector before its first message, unless you
   give it `kinds`).

Then it re-orders the list, keeping the order inside each group:

- **For a picked date:** dated connectors first, then undated ones. History comes from history.
- **For Live:** live connectors first, and among them a real stream before the default route's samples; then the
  rest.

Drishti asks the candidates one by one. A connector that does not hold the entity says so and the next is asked. The
first that holds it answers. A connector that **holds the date** is authoritative for it: an entity it does not list
for that date is not held, and the connectors behind it are not asked (`DRS-1001 recent-files holds trade for
2026-09-29 and does not list trade/MX-30000006`). A recent store that dropped a trade is not overruled by the lake
behind it, and the view agrees with a search of the same date. Only a date the connector does not hold passes on (a
recent store of the last weeks in front of years in a lake). Which connectors can tell: the file connector over dated
JSON-lines snapshots (a date is held when a day's file serves it, within `lookback-days`); others cannot yet tell, and
an entity they do not hold passes to the next as before (see [`coverage`](#coveragekind-asof-which-dates-you-hold) in the SPI). A connector
that **fails** (the database is down, the service answers 500, a file or
table it holds cannot be read, a store it has not reached yet so it cannot tell what it holds) stops the read with
`DRS-1003 <connector> failed reading <kind>/<id>`: Drishti does not silently show you another store's data instead.
When the connector says what to do (a Delta date in a codec its engine does not read), the error says so too
(`…: trade 2026-09-30 cannot be read: …; rewrite the date with Snappy or ZSTD …`). The whole read must finish
within `drishti.sources.fetch-timeout` (10 s) or it ends with `DRS-1004`. If nobody holds the entity the answer is
`DRS-1001`; if no connector serves the kind at all, `DRS-1002` (rare, because `demo` and `file` serve every kind).
A read *as known at* an instant (`knownAt`) is only put to connectors that keep earlier versions (Delta Lake,
Iceberg). A dated connector without them that may hold the date stops the read with `DRS-1007` (400) naming it
(`recent-files keeps no earlier versions, so a read 'as known at' an instant cannot be answered from it …`), rather
than showing today's data as if it were what was known then; one that does not hold the date is passed over.
Undated connectors have one version and are read as usual (the view says the data is current).

Live updates follow the read: the ticks come from the first live connector that holds the entity, in the same order
(a real stream before the samples). So if a trade comes from Kafka, it ticks from Kafka.

Search (the type-ahead under the command line) asks every connector that supports search and merges the hits.
A structured search (`TRD where …`) lists the kind the same way and reads what it lists, in read order up to the
first connector that holds the date (the ones behind it are not listed, as they are not read); a connector that fails, does
not answer within the search budget, says its list of the kind is incomplete (a table it could not index), or cannot
answer a *known at* search, makes the answer `partial: true` and is named in it with why (`failed`), so a failure
never reads as "nothing matched".
Reverse lookups (what refers to this entity) ask every connector that supports them.

**A real example.** On a server running the trading pack with its Delta Lake and the demo samples on (and the Kafka
stream off), the same trade is read three ways:

```bash
B=http://localhost:18480/api/v1
curl -s $B/entities/trade/MX-20000001/raw | jq -c .provenance
curl -s "$B/entities/trade/MX-20000001/raw?asOf=2026-09-29" | jq -c .provenance
curl -s "$B/entities/trade/MX-20000001/raw?asOf=2026-08-01" | jq -c .provenance
```

You should see:

```json
{"source":"murex-rates","generation":2,"fetchedAt":"2026-10-01T01:57:52.264798849Z","live":true,"businessDate":null}
{"source":"trade-endur","generation":7,"fetchedAt":"2026-10-01T01:57:52.290912912Z","live":false,"businessDate":"2026-09-29"}
{"source":"murex-rates","generation":2,"fetchedAt":"2026-10-01T01:59:42.090962906Z","live":true,"businessDate":null}
```

- **Live**: the demo samples are live, so they go first; the sample's own source name (`murex-rates`) is shown.
- **29 September**: dated sources go first, so the lake (`trade-endur`) answers, with `businessDate`.
- **1 August**: before the lake's first date (the sample lake holds ten business days), so the lake does not hold it
  and the undated samples answer. The console says so above the view:
  *No data held for 2026-08-01: the current data of murex-rates, a source that keeps no dates*

#### Where configuration goes

A connector is configured by **one file**, `config/connectors/<name>.yaml`, edited in your editor, in **Admin → Connectors**, or with
`drishti.py connector apply`, and applied to the running server without a restart. A pack does not carry connection settings: it **names** the connectors it
reads through (`connectors: [trade-endur]` and `routes:` in its `pack.yaml`) and may **suggest** a template for each. Precedence, highest first
(the full table and the rules are in [DATA_GUIDE.md section 9](DATA_GUIDE.md#precedence)):

| Source | Role |
|---|---|
| environment variables, and `drishti.sources.connectors.*` in `application.yaml` / `application.local.yaml` (**deprecated**) | override a file setting by setting until you move them to the file; a warning names the file to write |
| `config/connectors/<name>.yaml` | the site's definition; it **replaces** the pack's template for that name **wholesale** (settings are not merged) |
| the pack's template (`connectors:` mapping or `connector-templates:` in `pack.yaml`) | the pack's suggestion; written to the file once at the first start, and used in memory when the file cannot be written |

A file with no template of that name is a new site connector. A connector file holds `plugin`, `enabled`, `kinds`, `description` and `settings`; nesting under
`settings` is flattened to dotted keys. Values like `${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}` are placeholders: the environment variable if set, else the default
after the colon. A **credential** setting (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`) may only be an `${ENV_VAR}` or a
`file:/path` reference ([DATA_GUIDE.md section 9, Credentials and TLS](DATA_GUIDE.md#95-credentials-and-tls)). The server's own settings (`drishti.sources.fetch-timeout`,
`routes`, `plugins.<plugin>`) stay in `application.yaml` and `application.local.yaml`.

### 10.2 Several connectors of one plugin: unions and stores in turn

A connector is a *named instance* of a plugin, one file in `config/connectors/<name>.yaml` (the file name is its name;
[DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors)). You may run as many instances of a plugin as you like; each has its own
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

Which of them answers a read is decided as in [How a request picks a connector](#how-a-request-picks-a-connector):
a store that holds the date is authoritative for it, so keep the dated stores of one kind **disjoint by date**: here `recent-files`
holds the last 10 days, `archive-files` anything older. Give a `file` or `kafka` instance a `kinds:` list: without one it serves
every kind.

What a server answered with the two file connectors above,
with the files `feeds/recent/trading/2026-10-01/trade.jsonl` (`MX-1`) and
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
endpoint) is listed under `failedToStart` and retried by itself ([DATA_GUIDE.md section 9, retries](DATA_GUIDE.md#a-connector-that-fails-to-start-or-loses-its-connection-is-retried)).
One `DOWN` connector does not take the others with it.

Connectors defined under `drishti.sources.connectors` in a site file loaded with
`--spring.config.additional-location` are deprecated but read (flagged in the log and on Admin → Connectors; it overrides a file setting by
setting); move each block to its own file ([DATA_GUIDE.md section 9, section 11](DATA_GUIDE.md#911-moving-off-applicationyaml-and-the-old-data-source-override)).

### 10.3 Which connector should I use?

Whichever you choose, it is configured as a connector file (`config/connectors/<name>.yaml`), and every document in the list below ends with an
"As a connector file" section holding a complete example. Ready-made files for the databases, caches and lakes are in
[`config/connectors.examples/`](../../config/connectors.examples).

#### By situation

| Your data is… | Use | Why | Watch out for |
|---|---|---|---|
| JSON or CSV files dropped in a folder by a nightly job | [`file`](DATA_GUIDE.md#82-which-connector) | no infrastructure; one folder per business date gives history | search sees new files after `rescan-seconds`; no live updates |
| behind an in-house HTTP/JSON API, one entity per GET | [`rest`](DATA_GUIDE.md#82-which-connector) | uses the API the owning system already has | undated, no search, no reverse lookups; every view is a request to that service |
| in an existing database schema | [`jdbc` query mode](DATA_GUIDE.md#82-which-connector) | one SQL statement per kind; your tables stay as they are | no search or reverse lookups; you write the SQL for dates |
| loaded by you into PostgreSQL for Drishti | [`jdbc` table mode](DATA_GUIDE.md#82-which-connector) | one table per data domain, dated, search and reverse lookups from SQL | you run the load; size the indexes |
| in a data lake (Delta Lake on disk, S3, MinIO) | [`delta`](DATA_GUIDE.md#82-which-connector) | the shipped default; years of history, *known at* time travel, search, reverse lookups, no Spark | partitions by `business_date`; keep it bounded with `tools/lake/maintain.py` |
| in Aerospike | [`aerospike`](DATA_GUIDE.md#82-which-connector) | sub-millisecond key reads (two per view), millions of entities a day, retention by record TTL | searches and reverse lookups need the pack's promoted fields (`layout`) |
| on Kafka topics (latest state per entity) | [`kafka`](DATA_GUIDE.md#82-which-connector) | live views that tick; the topic is the state | undated: pair it with a lake for history |
| on ActiveMQ queues or topics | [`activemq`](DATA_GUIDE.md#82-which-connector) | live; the connector keeps the latest document per entity on local disk | a queue delivers once: the local state store is the only copy |
| on RabbitMQ queues | [`rabbitmq`](DATA_GUIDE.md#82-which-connector) | as ActiveMQ, AMQP 0-9-1 | as ActiveMQ |
| JSON objects in a bucket (S3, MinIO, Ceph) | [`s3`](DATA_GUIDE.md#82-which-connector) | the `file` layout in object storage, dated by folder | cost of listing large buckets; reads cached `cache-seconds` |
| a total or summary of another kind (a book's P&L from its trades) | `derived` ([BUILD_GUIDE.md](BUILD_GUIDE.md)) | computed by the server from the other kind, through whichever connectors serve it | reads every member once per `refresh`; keep `max-scan` above the member count |
| public rates and FX | [`feed`](DATA_GUIDE.md#82-which-connector) | NY Fed SOFR, ECB €STR and FX, US Treasury curve, FRED; switched on by one variable each | needs internet (or a mirror); FRED needs a free key |
| headlines about a named entity, computed on demand | [`news`](DATA_GUIDE.md#82-which-connector) | Google News RSS, filtered, de-duplicated and cached per name; nothing stored | needs internet; names leave the network; off by default |
| a quote and price history for a ticker, computed on demand | [`yfinance`](DATA_GUIDE.md#82-which-connector) | Yahoo Finance's unofficial chart endpoint; samples and internal demos only | needs internet; unofficial and restricted; off by default |

#### What each connector can do

| Connector | History (picked dates) | Live ticks | Search | Reverse lookups | Long-lived connection | Local disk |
|---|---|---|---|---|---|---|
| `demo` | no | yes | yes | yes | — | — |
| `file` | yes, by folder | no | yes | yes (JSON lines) | — | — |
| `rest` | no | no | no | no | — | — |
| `news`, `yfinance` | no (computed now) | no | only names or symbols already asked for | no | — | a bounded in-memory cache |
| `jdbc` query mode | when a query uses `:asOf` | no | no | no | a small pool | — |
| `jdbc` table mode | yes (`snapshot`/`effective`) | no | yes | yes | a small pool | — |
| `delta` | yes (`snapshot`/`effective`, *known at*) | no | yes | yes | — | read cache in memory |
| `aerospike` | yes (`snapshot`/`effective`) | no | yes | yes, from promoted bins (`reverse-index`) | cluster client | promoted bins cached in memory |
| `iceberg` | yes (`snapshot`/`effective`, *known at*) | no | yes | yes, from promoted columns | — | read cache in memory |
| `duckdb` | yes (`snapshot`/`effective`) | no | yes | yes, from promoted columns | — (the file is read in-process) | the DuckDB file; a day's promoted columns cached in memory |
| `mongodb` | yes (`snapshot`/`effective`) | no | yes | yes, from promoted fields (`reverse-index`) | driver pool | promoted fields cached in memory |
| `redis` | recent days (`snapshot`/`effective`) | yes (`<domain>:changes`) | yes | yes, from promoted columns | Lettuce connection | everything in Redis memory |
| `kafka` | no | yes | yes (`state` mode) | no | consumer | optional disk cache |
| `activemq`, `rabbitmq` | no | yes | yes | no | broker connection | state store (required) |
| `s3` | yes, by folder | no | yes | no | — | — |
| `feed` | yes, recent history | no | yes | no | — | — |
| `derived` | yes, wherever its members have it | no | yes | no | — | computations cached per date |

**For BI datasets** the `delta`, `duckdb`, `jdbc` (table mode) and `file` connectors implement `scan` over their promoted columns, and `duckdb` and `jdbc` (table mode) expose a `sqlTarget` for `direct` tables
([Bulk reads for BI](#bulk-reads-for-bi-scan-and-sqltarget)); every other connector is read record by record, with a warning that a large table will be slow.

**Rules of thumb.**

- **History lives in a dated store; "now" can live in a stream.** The banking packs ship exactly this: a Delta Lake
  connector per data domain, plus a Kafka connector for live trades (off until switched on). See
  [Combining connectors](DATA_GUIDE.md#84-combining-connectors).
- **Reverse lookups** (the *Linked entities* panel, impact analysis with F8) need a connector that indexes references:
  Delta Lake, PostgreSQL table mode, Aerospike, or the demo samples.
- **Cost.** `rest` and `jdbc` query mode put one request on your system per view opened (plus linked entities).
  Delta Lake, S3 and Aerospike read from storage Drishti does not load. Kafka, ActiveMQ and RabbitMQ read everything
  that arrives, whether or not anyone is looking.

### 10.4 Before you start: the common steps

Every chapter below uses the same few commands. They are collected here once.

**1. Build once** (from the repository root; see [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine) for Java 21 or newer and the
console's Python environment):

```bash
./mvnw -q package -DskipTests
```

**2. Choose the packs.** A pack gives your kinds their mnemonics and its connectors. The examples use the banking
packs; `counterparty-risk` pulls in `trading`, `market-data` and `core`, and `market-risk` adds stress
results:

```bash
export DRISHTI_PACKS=counterparty-risk,market-risk
```

**3. Put each connector in a file in `config/connectors/`**, in the folder you start the server from (the repository root when developing). The file name is
the connector's name (`config/connectors/desk-db.yaml` is the connector `desk-db`); copy one of `config/connectors.examples/` or write it in **Admin → Connectors**.
The folder is created on the first write, and a pack's suggested connectors are written there at the first start. Every chapter shows the file to write:

```yaml
# config/connectors/desk-db.yaml
plugin: jdbc
kinds: [trade]
settings:
  url: ${DESK_DB_URL}
```

Routes (`kind -> connector`) are the pack's `routes:`; the server's own settings stay in `application.local.yaml` (git-ignored, imported at start through
`spring.config.import: optional:file:./application.local.yaml`, so a missing file is fine).

**4. Start the server**, then the console in a second terminal:

```bash
java -jar drishti-server/target/drishti-server-*-exec.jar
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

The server listens on `http://localhost:18480`, the console on `http://localhost:17480`. Configuration is read at
start, except connector files: **a change to a file in `config/connectors/` is applied to that connector within seconds; after changing `application.local.yaml`, a pack or an
environment variable, restart the server.**

**5. Check the connectors.** For anyone (security on or off):

```bash
curl -s http://localhost:18480/api/v1/sources | jq -c '.sources[] | {name, kinds, health}'
curl -s http://localhost:18480/api/v1/sources | jq '.failures'
```

For administrators (with security on, add `-H "Authorization: Bearer $TOKEN"`; see
[Minting a token for a script](#minting-a-token-for-a-script)):

```bash
curl -s http://localhost:18480/api/v1/admin/health | jq '{status, summary, failedToStart}'
curl -s http://localhost:18480/api/v1/admin/health | jq '.sources[] | select(.name=="trade-endur")'
```

A healthy connector looks like this (real output from a running server):

```json
{
  "name": "trade-endur",
  "version": "1.0",
  "status": "UP",
  "health": "UP",
  "kinds": ["trade"],
  "live": false,
  "dated": true,
  "search": true,
  "reads": { "reads": 14, "found": 5, "notHeld": 9, "errors": 0, "lastError": null, "lastErrorAt": null,
             "lastOkAt": "2026-10-01T01:48:56.510246522Z", "p50Ms": 2.938, "p99Ms": 72.586 },
  "cache": { "timeTravel": 0, "partitions": 3, "tables": 1 }
}
```

In the console the same information is **Admin → Health** (`/admin/health`): a table of connectors (Connector,
Status, Kinds, Reads, Errors, Last error, Cache), the packs with their connectors, and anything that *failed to
start*. It refreshes every 5 seconds.

**6. Read one entity directly** to see which connector answered, before you look at the console:

```bash
curl -s http://localhost:18480/api/v1/entities/<kind>/<id>/raw | jq -c '{ref, provenance}'
```

`provenance.source` is the connector (or, for the demo samples, the sample's own source name); `businessDate` is
the date the document is for (`null` for undated connectors); `live` says whether it can tick.

### 10.5 The SourcePlugin SPI

A source plugin brings entity documents from one system into Drishti. Plugins depend only on `drishti-api` and the small `drishti-common` (no
Spring, no Jackson), so they stay small and cannot clash with the server's libraries. The interface is
`com.ash.drishti.api.SourcePlugin`; the types around it (`EntityRef`, `EntityDocument`, `AsOf`, `Provenance`,
`PluginManifest`, `SourceCapabilities`, `SourceContext`, `HitIndex`, `ColumnSet`, `DateCoverage`, `SettingSpec`, `ScanRequest`, `ArrowScan`, `SqlTarget`) are in the same
package. Only two methods are abstract beyond `manifest`/`start`/`fetch`: everything else has a default that says "I do
not do that", so a first plugin is three methods long.

**The API reference.** Every public type and member of `drishti-api` is in the Javadoc at **Help → Developer**: [`SourcePlugin`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/SourcePlugin.html), [`SourceContext`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/SourceContext.html), [`PluginManifest`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/PluginManifest.html), [`SourceCapabilities`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/SourceCapabilities.html), [`EntityDocument`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/EntityDocument.html), [`AsOf`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/AsOf.html), [`ScanRequest`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/ScanRequest.html), [`HitIndex`](/help/reference/latest/java/drishti-api/com/ash/drishti/api/HitIndex.html), the shared [TLS](/help/reference/latest/java/drishti-api/com/ash/drishti/api/tls/package-summary.html) and [proxy](/help/reference/latest/java/drishti-api/com/ash/drishti/api/proxy/package-summary.html) modules and the [compute engine SPI](/help/reference/latest/java/drishti-api/com/ash/drishti/api/compute/package-summary.html). The console builds it with `python3 tools/api_docs.py build --maven` (the links open the newest version built).

```java
public interface SourcePlugin extends AutoCloseable {
    PluginManifest manifest();
    void start(SourceContext context) throws Exception;
    Optional<EntityDocument> fetch(EntityRef ref) throws Exception;
    default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception;
    default DateCoverage coverage(String kind, AsOf asOf);
    default boolean timeTravel();
    default Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener);
    default boolean pushes(EntityRef ref);
    default java.time.Instant lastUpdate();
    default Optional<String> dataVersion(String kind);
    default Optional<String> dataVersion(String kind, LocalDate date);
    default Optional<String> tableIdentity(String kind);
    default java.util.Set<String> columnar(String kind);
    default Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) throws Exception;
    default boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) throws Exception;
    default Optional<ArrowScan> scan(ScanRequest request) throws Exception;
    default HistoryRead history(HistoryRequest request) throws Exception;
    default Optional<NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to);
    default Optional<SqlTarget> sqlTarget();
    default List<EntityRef> reverse(EntityRef target, String kind);
    default List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf);
    default List<EntityHit> search(String kind, String text, int limit);
    default List<EntityHit> search(String kind, String text, int limit, AsOf asOf);
    default Optional<String> listingProblem(String kind);
    default Map<String, Object> cacheStats();
    default Optional<DatedHitIndex> typeAheadIndex();
    default Optional<PoolStats> poolStats();
    default boolean dependsOn(String kind);
    default void purgeCaches();
    default Optional<FieldNote> describeField(String kind, String field);
    default Map<LocalDate, Long> inventory(String kind, int maxDates);
    default String health();
    default void close();
    default List<SettingSpec> settingSpecs();
}
```

#### The vocabulary

| Type | What it is |
|---|---|
| `EntityRef(kind, id)` | the address of one entity; both parts non-blank |
| `EntityDocument(ref, data, provenance, deleted)` | a document: `data` is a `DataNode` tree (object, array, value; `get`, `at(path)`, `asText`, `asDouble`), `deleted` marks a tombstone pushed to open views |
| `Provenance(source, generation, fetchedAt, live, businessDate)` | where the document came from; the console shows it in *How this view was built* |
| `AsOf(businessDate, knownAt, live)` | which data is wanted: `AsOf.LATEST` (live, current), `AsOf.of(date)` (a picked business date), optionally `knownAt` (as known at an instant, before later corrections) |
| `EntityHit(ref, title, subtitle)` | one line of type-ahead |
| `SettingSpec(name, type, required, defaultValue, description, secret, group)` | one setting the plugin reads, as the connector form and validator see it ([`settingSpecs()`](#settingspecs-describing-your-settings)) |

#### `manifest()`: name, version, kinds, capabilities

```java
new PluginManifest("dayfolder", "1.0", kinds, new SourceCapabilities(live, reverseLookup, search, dated))
```

- `name` is the plugin's name, the value of `plugin:` in a connector. A plugin running as itself is also known by it.
- `kinds` lists the kinds served. An **empty set means any kind**: the plugin is a candidate for every read and
  answers "not held" for what it does not have. A plugin that learns its kinds from its store has an empty set until
  it has looked.
- `SourceCapabilities(live, reverseLookup, search, dated)` must be honest: the engine calls an optional method only
  when its capability is declared. `dated = true` means the plugin overrides the `AsOf` overloads and stamps
  `Provenance.businessDate`; dated sources always receive a concrete date.

#### `start(SourceContext)`: settings, JSON parser, scheduler

`SourceContext` gives a plugin everything it may use from the host:

| Method | Use |
|---|---|
| `settings()` / `setting(key, fallback)` | the connector's flat `Map<String, String>`; `setting` returns the fallback for a missing or blank value. Nested maps in a pack arrive as dotted keys, lists as comma lists. Prefix families (`query.<kind>`, `mode.<kind>`, `header.<Name>`) are read by iterating the map |
| `parseJson(InputStream)` | the host's JSON parser, so the plugin carries none: returns a `DataNode` |
| `scheduler()` | a shared `ScheduledExecutorService` for background refreshes; cancel your tasks in `close()` |
| `reader()` | reads *other* kinds through the server's routing (`list`, `read`); for connectors built on others, such as `derived`. Use it when serving reads, not inside `start` (the router is built after the plugins start). It applies no entitlements: the server redacts what it serves |

`start` runs once. If the plugin cannot run without a setting, throw `PluginNotConfigured`: the plugin is then
*installed but not configured*, stays idle and is not counted as a failure (see
[Reconnecting and failures](#1010-reconnecting-and-failures)). Any other exception fails the start and shows under
`failedToStart` in Admin → Health; the server still runs. **A store that is down at start should not fail the
start**: start a background task that retries and report `DOWN` through `health()` until it answers.

#### `fetch(ref)` and `fetch(ref, asOf)`: reading one entity

```java
Optional<EntityDocument> fetch(EntityRef ref) throws Exception;
default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception { return fetch(ref); }
```

- Called concurrently from many virtual threads: it may block (JDBC, HTTP) but must be thread-safe.
- **Return `Optional.empty()` only when you know you do not hold the entity** (no row, no key, a date outside what you
  keep). Anything else throws: the store is down, a file is there but unreadable, a line does not parse, and also *not
  connected yet*. An empty answer for a failure would let the next store answer with its own, different data. See
  [Failure semantics](#failure-semantics).
- **Dated sources** override the second form, read the date from `asOf.businessDate()` and stamp
  `Provenance.businessDate` with the date the document is *for*, which may be earlier than the one asked (the last
  business day that has data). Undated sources need do nothing: the default ignores the date, and the view tells the
  user the data is current.
- `Provenance.source` is the name users see (take it from the `source-name` setting, defaulting to the connector's
  name). `generation` must be monotonic: higher means newer. A file's modification time, a table version, a counter
  or an ETag number all work.

#### `coverage(kind, asOf)`: which dates you hold

`DateCoverage` is `HELD`, `NOT_HELD` or `UNKNOWN` (the default). It must be cheap: no remote call. A source that
reports `HELD` for a date is **authoritative** for it: an entity it does not list is not held, the read stops with
`DRS-1001` naming the source, the sources behind it are not asked, and a structured search lists the kind only up to
it, so views and searches agree. Only a date you report `NOT_HELD` passes on to older stores. When you cannot say,
return `UNKNOWN` and the next source is asked as before.

#### `timeTravel()`: *known at* reads

Return `true` only if you keep earlier versions and honour `AsOf.knownAt` (Delta Lake, Iceberg). A read *as known at*
an instant is never put to a dated plugin that does not, and may hold the date: it ends with `DRS-1007` (400) naming
the source, and a search is partial.

#### `search(kind, text, limit[, asOf])` and `HitIndex`

`search` powers the type-ahead under the command line and structured searches, so it **must answer from memory**: it
runs on every keystroke. `HitIndex` is a ready-made, lock-free index: call `replaceAll(hits)` after each scan,
`add`/`remove` for single changes, and `search(kind, text, limit)` ranks ids by prefix, then by containment, then by
title. Declare `search = true` in the capabilities. The four-argument form defaults to the three-argument one.

#### `listingProblem(kind)`: an honest "partial"

If the index of a kind could not be rebuilt (a table you cannot read, a scan that failed), keep the previous index and
say so: return `Optional.of("reason")`, which must name what cannot be read **without secrets**. Structured searches
that list the kind are then marked `partial: true` with that reason, instead of looking exact. Empty means complete.

#### `columnar(kind)` and `columns(kind, paths, asOf)`: promoted fields as columns

For stores that keep a pack's promoted fields beside the document (Delta, PostgreSQL table mode, DuckDB, Redis,
MongoDB, Aerospike, Iceberg). `columnar` lists the document paths held as columns; `columns` returns every entity of
the kind on the date, column by column (`ColumnSet`: `ids`, `numbers`, `texts`, `mixed`), so searches, pick lists,
derived kinds and impact analysis read columns instead of documents. Return `Optional.empty()` when you cannot answer
as columns and documents are read instead. `columns` is only called with paths `columnar` lists. A plugin that does not
keep columns leaves both as they are.

#### `documents(kind, asOf, sink)` and `dataVersion(kind[, date])`: one pass over a day, and when it changed

`documents` hands every document of the kind on the business date to `sink`, in one pass over the store (a Delta table streams
the date's files one at a time), for connectors built from other kinds: the grouped pages of a `derived` connector read a day
this way instead of one `fetch` per member, so a million members cost one sequential read. Return `false`, having given
nothing to `sink`, when you cannot read so (the caller then reads entity by entity, up to its `max-scan`); `true` when you
read the date, even if it held nothing. Give each document as you read it; do not collect the day first.

`dataVersion` names the kind's data as the store holds it now (a Delta table's version and commit time), and the dated form
the data of one business date; empty when you cannot tell. It must be cheap: the BI store and the grouped pages ask it often
(every `version-check`, 10 s) and rebuild what they built from the data only when it changes. A source that cannot say its
version leaves the grouped pages on their `refresh` timer. `dependsOn(kind)` is true for a connector built from that kind
(the `derived` one), so a data-load signal for the kind purges it too.

#### `tableIdentity(kind)`: the same data on several servers

With Drishti*net ([OPERATIONS_GUIDE.md](CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)) several servers may read the same table. `tableIdentity` names where the kind's data lives so that every
server reading the same data says the same: the Delta connector gives the table's id from its log (`delta:<id>`), else its location (an object
store's address, or a local path with the machine's name, since the same path on another machine is another table). Two servers whose sources
name the same identity are *replicas* of the kind and a search asks only one; different identities are *shards* and a search asks all. Return
empty (the default) when you cannot be sure: the source is then taken as unique to its server, which is always safe (asked, and duplicates
removed by id). It must be cheap.

#### Bulk reads for BI: `scan` and `sqlTarget`

The BI engine ([ARCHITECTURE.md section 28](../architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer)) reads **whole tables** through the same connectors, by their logical names. Two **optional** methods say how, both default to "no":

```java
default Optional<ArrowScan> scan(ScanRequest request) throws Exception      // columns of a kind over a date range, as column batches
default Optional<SqlTarget> sqlTarget()                                      // where this store's SQL tables are, for pushdown
```

* **`scan(ScanRequest)`**: `ScanRequest(kind, paths, filters, from, to, batchRows)` asks for chosen document paths (no leading `$.`), optional conjunctive filters (`EQ NE LT LE GT GE IN`), and a business-date range (`from == null`: the
  source's latest day; an undated source ignores the range). Answer `Optional.empty()` when you cannot give **every** path as a column; the engine then reads records and warns that a large table will be slow. Otherwise return an
  `ArrowScan`: call `next()` for a `ColumnBatch` (the date, the entity ids, and one array per path: `String[]` for text, `double[]` with `NaN` for an absent number, `boolean[]`) until it returns null, then `close()`; `warnings()` after the last batch lists
  anything to show. The batch types are the Arrow type model without Arrow's memory libraries; the engine writes the Parquet layer from them. **A scan returns a date's own rows only**: a snapshot source that answers a date it
  does not hold with an earlier day must skip that day, or a range would count it twice.
* **The easy way.** A source that already promotes columns implements `columnar` and `columns` (above); give `scan` in one line: `return ColumnScans.over(this, request);`. It walks the days of the range, calls your `columns`, applies the filters, cuts batches, and
  skips days that are not the date asked. The Delta, DuckDB, PostgreSQL (table mode) and file connectors do exactly this, so **what they can scan is what they promote** (`layout.<kind>.columns`); a dataset that names a column they do not promote is read from
  documents instead. They do not yet push filters into their own storage: the filters are applied to the column set after it is read.
* **`sqlTarget()`**: for SQL stores. A `SqlTarget` gives the `dialect()` (`duckdb`, `postgresql`), a `relation(kind)` (the table expression, the kind column, the business-date column, the id column, and a function from a document path to its quoted column or
  null) and `with(work)`, which runs work on a connection borrowed from **your** pool. The engine compiles one SELECT over the relation, values bound as parameters, and runs it there (`mode: direct` tables). The DuckDB connector (the `entities` table of its
  file) and the PostgreSQL connector (table mode) provide it; Delta and files do not (their pushdown is the scan above). A connector wrapper (`ConnectorInstance`, the named connector) forwards both methods.

Test it as the engine does: [`ColumnScansTest`](../../drishti-api/src/test/java/com/ash/drishti/api/ColumnScansTest.java) is a stub source and the range, filter, batch and refusal cases; the engine's tests read a small generated lake through a stub connector.

#### `history(request)` and `days(ref, from, to)`: one entity over business dates

`TRD 1000015 30` (history queries, [DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time)) reads one entity on many business dates.

```java
default HistoryRead history(HistoryRequest request) throws Exception     // the entity on each date it holds, oldest first
default Optional<NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to)   // the dates it holds the entity, no document read
```

* **`HistoryRequest(ref, from, to, dates, fields, knownAt, lastDays, budget, parallelDays)`**: the dates `from`..`to` (both included);
  `dates` are the business dates of that span from the server's calendar (a plugin has no calendar; empty means every calendar day);
  `fields`, when not empty, are the top-level fields wanted (you may return whole documents); `knownAt` asks for time travel (only when
  `timeTravel()` is true); `lastDays > 0` asks for only the last N dates with data on or before `to`; `budget` is how long the whole read
  may take; `parallelDays` how many dates the default reads at once.
* **`HistoryRead(days, covered, failed)`**: `days` are `HistoryDay(date, document, versions)`, one per date the source holds the entity
  on, where the document's provenance names that date. A date answered by an **earlier** date (a snapshot lookback, an effective kind
  carrying a change forward) is not a day. `versions` is how many versions of the date the store keeps, or `null` when it cannot say
  cheaply. `covered` are the dates `coverage()` says `HELD`; the router asks the next connector of the kind only for the others (Redis in
  front of Delta). `failed` maps a date to why it could not be read (`HistoryReads.TIMED_OUT` when the budget ran out).
* **The default**, `HistoryReads.perDay`, reads date by date through `fetch(ref, asOf)`, newest first, `parallelDays` at once on
  virtual threads, and stops early for `lastDays`. When `days()` is present it reads only those dates. Every connector therefore has
  history on day one; override `history` (and `days`) only when your layout can do better: one range query, a list of the entity's
  dates, a read of promoted columns without the document.
* The contract (`DatedSourceContract`) holds the default and every fast path to the same answers, including
  `historyMatchesFetchDayByDay`: each day's document equals `fetch(ref, AsOf.of(date))`.
* `ConnectorInstance` (the named connector) forwards both methods for the kinds it serves.

#### `reverse(target, kind[, asOf])`: what refers to this entity

Returns the entities of `kind` that refer to `target` (for example the trades whose netting set is `NS-A`): this feeds
the *Linked entities* panel and impact analysis. Declare `reverseLookup = true`. The server asks every source that
declares it and merges the answers. The date form defaults to the undated one.

#### `subscribe(ref, listener)`, `pushes(ref)` and `lastUpdate()`: live data

A live plugin (`live = true`) returns a `Subscription` whose `close()` stops the pushes; each call to `listener` with a
new `EntityDocument` repaints open views (a document with `deleted = true` says the entity was removed). The default
throws, and is never called for a plugin that does not declare `live`. Subscriptions follow the read: they go to the
first live source that holds the entity, so ticks come from the source that answered. `pushes(ref)` returns `true` for
a plugin that pushes a kind that another source serves (a Kafka connector in `ticks` mode over a lake). `lastUpdate()`
says when new data last arrived (null when it cannot tell, as for a database read on demand): views show how old their
data is, and the connector's `stale-after` setting turns it into a warning.

#### `health()`, `cacheStats()`, `purgeCaches()`, `close()`

| Method | Contract |
|---|---|
| `health()` | `"UP"`, `"UP (detail)"`, `"DEGRADED: reason"` (serving, but some data cannot be read: amber in Admin → Health, overall status `DEGRADED`) or anything else, which is *down* (`"DOWN: reason (reconnecting)"`). Cheap, no secrets, no remote call |
| `cacheStats()` | a small `Map` for Admin → Caches (entry counts, sizes, hits); empty when nothing is cached |
| `purgeCaches()` | drop everything cached (memory and disk) so the next reads refill from the source of truth. Called by an admin at any time; must be safe while reads are in flight. Never purge the only copy of data (the message-queue state stores do not) |
| `close()` | stop background tasks and release connections; called at shutdown and when a connector is reloaded |

#### `describeField(kind, field)` and `inventory(kind, maxDates)`: optional explanations

Both default to "nothing to say" and are never needed for a first plugin.

- `describeField` returns a `SourcePlugin.FieldNote(means, formula, origin)` for a field of one of your kinds, which **About this page** shows in its glossary.
  A connector that computes fields (the `derived` connector) says there "sum of mtm over the trades" and the formula as configured; a store has nothing to add.
- `inventory` returns, newest business date first, the dates the source holds for a kind and how many entities each has (at most `maxDates`), or an empty map when it
  cannot say. **Admin → Packs → Data source → Test connection** shows it. It may read data, so it is never called on a request path.

#### `settingSpecs()`: describing your settings

Optional. A connector is a file whose `settings:` the plugin reads ([Configuration binding](#107-configuration-binding)), and **Admin → Connectors** generates its
form, and the validator checks the file, from a description of those settings. The plugin gives it by overriding

```java
default List<SettingSpec> settingSpecs()      // the default declares none
```

returning one `com.ash.drishti.api.SettingSpec` per setting:

```java
public record SettingSpec(String name, String type, boolean required, String defaultValue,
                          String description, boolean secret, String group)
```

| Component | Meaning |
|---|---|
| `name` | the setting key as the plugin reads it (`root`, `bootstrap-servers`). A trailing `.*` covers a whole subtree (`layout.*`, `mode.*`, `query.*`), so free-form keys the plugin reads per kind are not reported as typos |
| `type` | `string` (the default when blank), `int`, `boolean`, `duration`, `path`, `list` or `enum:a\|b\|c` |
| `required` | true when the plugin cannot start without it; the form marks it and the validator refuses a file without it |
| `defaultValue` | what the plugin uses when the setting is absent, or null; shown as the field's placeholder |
| `description` | one line, shown under the field |
| `secret` | true for a credential: the form accepts only an `${ENV_VAR}` or a `file:/path` reference for it, and the validator refuses a literal value |
| `group` | the form section (`connection`, `security`, `tuning`, `tls`, …; `general` when blank) |

```java
@Override public List<SettingSpec> settingSpecs() {
    return List.of(
        new SettingSpec("root", "path", true, null, "Folder of day files", false, "connection"),
        new SettingSpec("rescan-seconds", "int", false, "30", "How often the folder is listed", false, "tuning"),
        new SettingSpec("mode.*", "string", false, null, "Per-kind read mode (mode.<kind>)", false, "mapping"));
}
```

A setting a file carries that no spec covers is a **warning** (a likely typo), never a refusal. The shipped plugins are described in
`drishti-engine/src/main/resources/drishti/plugin-settings.yaml`, which also holds the settings every plugin shares (`source-name`, `mode.*`, `domain`,
`stale-after`) and the shared `tls.*` block; **a plugin that overrides `settingSpecs()` wins** over its entry there, and a plugin that declares nothing falls back
to that catalogue. A plugin of yours that declares nothing still works: its form then offers only the free-form **Other settings** list.

#### Failure semantics

The router asks the candidate sources one by one (see [How a request picks a connector](#how-a-request-picks-a-connector)).
What your plugin throws decides what the user sees:

| Your plugin | Result |
|---|---|
| returns `Optional.empty()` | not held here: the next source is asked |
| holds the date (`coverage` = `HELD`) and returns empty | not held, and authoritative: `DRS-1001`, no further source asked |
| throws any exception | the read ends with `DRS-1003 <connector> failed reading <kind>/<id>`; the **store order** does not matter: a failing store stops the read, and Drishti never silently shows another store's data instead |
| throws `UnreadableData(message, cause)` | as above, but the *message* is shown in the `DRS-1003` error and with the searches it makes partial. Use it when the data is there but cannot be read for a reason the reader can act on (an unsupported codec, a line that is not JSON). The message must carry no secrets: any other exception's message only reaches the log and Admin → Health |
| takes longer than `drishti.sources.fetch-timeout` | the whole read ends with `DRS-1004` |
| no source holds the entity / no source serves the kind | `DRS-1001` / `DRS-1002` |

So a plugin returns empty **only** when it knows it does not hold the entity. A plugin that learns its kinds from its
store and has not yet reached it cannot tell what it holds, so it throws until its first catalogue read succeeds (the
`jdbc`, `duckdb` and `mongodb` plugins do).

### 10.6 Registration and discovery

Drishti finds plugins with the JDK's `ServiceLoader` (`PluginDiscovery` in `drishti-engine`). Two steps:

1. Put the plugin's fully qualified class name, one per line, in
   `META-INF/services/com.ash.drishti.api.SourcePlugin` inside the jar:

   ```text
   com.ash.drishti.examples.dayfolder.DayFolderSourcePlugin
   ```

   The class needs a public no-argument constructor.
2. Make the jar visible: put it in the server's **lib folder** (`drishti.lib.path`, default `<data folder>/lib`). Every jar there
   is loaded at start into one lib class loader under the kernel's (it never replaces a kernel class); a jar added needs no rebuild,
   only a restart. A shipped plugin is a Maven dependency of `drishti-server` instead. A connector that only one module's kinds use
   ships **inside that module** (the genomics module's VCF reader, section 1.8): it goes with the module.

A plugin whose name no connector uses and that is not listed under `plugins` starts with empty settings, and, if it
needs one, stays idle (see below). `GET /api/v1/sources` lists the running connectors; the failures are under
`failures`.

### 10.7 Configuration binding

A plugin runs in one of two ways. **As itself**, under `drishti.sources.plugins.<plugin>`: one instance, one set of
settings. **As named connectors**, each a **file** in `config/connectors/` (`config/connectors/<name>.yaml`; the file name is the connector's name): the
same plugin several times, each with its own settings, name and, optionally, kinds. A pack names the connectors it reads through and may suggest a template for
each, which the server writes to the file at the first start ([DATA_GUIDE.md section 9](DATA_GUIDE.md#9-connector-files-and-admin--connectors)).

```yaml
# application.local.yaml: the server's own settings
drishti:
  sources:
    lib:
      path: ./data/lib                          # extra jars (connectors, JDBC drivers), in one lib class loader
```

```yaml
# config/connectors/desk-days.yaml
plugin: dayfolder
kinds: [trade, counterparty]
settings: { root: ./data/days, id-field: id, mode.counterparty: effective }
```

and the pack that reads it routes the kind: `connectors: [desk-days]` and `routes: { trade: desk-days }` in its `pack.yaml`.

A connector file has these keys: `plugin`, `enabled` (default true), `kinds` (limits what it serves: reads, subscriptions,
reverse lookups and search for other kinds answer nothing) `description` (free text) and `settings` (nesting is flattened to dotted keys, and the plugin is handed a `Map<String,
String>`; `source-name` defaults to the connector's name, so documents say which connector they came from). A definition under `drishti.sources.connectors.<name>` in
`application.yaml` is deprecated but read. **The
settings of each shipped plugin, with defaults, are in that connector's own document** (the table in
[CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin) lists the plugin names a connector
can name); a plugin of yours documents its own.

Rules worth knowing when you write a plugin:

- **Which instances start.** Every plugin on the class path runs as itself unless `plugins.<name>.enabled` is `false`,
  *except* a plugin used by any named connector: that one runs as itself only if it is also listed under `plugins`.
  A plugin that is neither listed nor used by a connector starts with empty settings; one that needs a setting then
  throws `PluginNotConfigured` from `start`, the log says *installed but not configured*, and health does not count it
  as a failure. Do the same for your plugin when it has nothing to run with.
- **Names.** Routes, health and *How this view was built* use the connector's name. A plugin running as itself is known
  by its manifest name or its `source-name`.
- **Setting keys with dots** (`mode.trade`, `header.Authorization`) are literal keys. In a connector file write them
  flat (`mode.trade: effective`) or nested (`mode: {trade: effective}`); both arrive as the same dotted key. In a pack's `pack.yaml` template write them
  flat. Only in the deprecated `application.yaml` form does Spring drop characters other than letters, digits, `-` and `.` from map keys unless the key is
  bracketed and quoted: `"[kind.desk_orders]": order`.
- **Values are strings.** YAML numbers and booleans are converted; parse them yourself and fail with a clear message.
  Placeholders (`${DESK_ROOT:./data}`) are resolved when the connector starts, in connector files as well as in packs, so secrets stay out of
  files; a credential setting may only be a reference (`${ENV_VAR}` or `file:/path`).
- **Describe your settings.** Override [`settingSpecs()`](#settingspecs-describing-your-settings) so Admin → Connectors can generate the form for your plugin and check its files.
- **Routing order** for a kind is its route, then the `default-route`, then every other source serving it, re-ordered
  for a picked date (dated first) or Live (live first): see [How a request picks a connector](#how-a-request-picks-a-connector).

### 10.8 TLS for a connector: the shared module

A connector that connects to a server over the network gets TLS from one shared module in the plugin API,
`com.ash.drishti.api.tls` in `drishti-api` (pure JDK; no Spring, no extra dependency), so that every connector offers the
same settings, the same source forms and the same start-up messages. Operators learn the `tls.*` keys once:
[DATA_GUIDE.md section 10](DATA_GUIDE.md#10-tls-and-the-outbound-proxy). A connector that opens a TLS connection should use it instead of the JVM-wide `javax.net.ssl.*` properties.

| Class | What it is |
|---|---|
| `TlsSettings` | a record parsed from the connector's settings under a prefix (`TlsSettings.from(ctx.settings())` for `tls.`; `TlsSettings.parse(settings, "other.tls.")` for a second endpoint). Resolves `${ENV}` placeholders and `…-file` password files; its `toString` never prints a secret. |
| `TlsContexts.build(settings)` | validates everything and returns a `TlsMaterial`; throws `TlsException` (a `RuntimeException`) whose message names the setting, the file and the reason. |
| `TlsMaterial` | the result: `sslContext()`, `keyManagers()` (null when there is no client identity), `trustManagers()`, `sslParameters()` (protocols, cipher suites, and `HTTPS` endpoint identification when hostname verification is on), `socketFactory()`; the raw parts for a library that wants PEM or a key store instead (`clientKey()`, `clientChain()`, `trusted()`, `usesJvmDefaultTrust()`); `certificates()` with their expiry; `warnings()`; `annotate(health, now)`. |
| `Secrets` | `Secrets.get(settings, key)` and `Secrets.secret(settings, key)` for any setting that may hold a secret (`${ENV}` placeholders, the `-file` twin). Use it for connector-specific credentials too. |
| `PemReader` | PEM certificates and private keys (PKCS#8, encrypted PKCS#8, PKCS#1 RSA, SEC1 EC) if you need them on their own. |

**Using it in a connector** (the three steps every shipped connector follows):

```java
@Override
public void start(SourceContext ctx) {
    TlsSettings tls = TlsSettings.from(ctx.settings());
    boolean secure = tls.enabled() || ctx.setting("url", "").startsWith("https:");     // whatever your address says
    if (tls.enabled() && !secure) {
        throw new TlsException("tls.enabled is true but url is not https://");         // never a silent downgrade
    }
    this.tlsMaterial = secure ? TlsContexts.build(tls) : null;                          // fails the start with the file and the reason
    ...
}

// when you create a client:
client.sslContext(tlsMaterial.sslContext());                  // or tlsMaterial.socketFactory(), or keyManagers()/trustManagers()
socket.setSSLParameters(tlsMaterial.sslParameters());         // protocols, ciphers, the hostname check: on every SSLSocket/SSLEngine

@Override
public String health() {
    return tlsMaterial == null ? current : tlsMaterial.annotate(current, Instant.now());   // "UP (TLS certificate … expires in 12 days …)"
}
```

Rules the shipped connectors follow, which a new one should too:

1. **Build at start**, so a wrong file or password stops the connector with a clear message
   instead of failing at the first connection. Build once; reuse the material for reconnections.
2. **Never downgrade.** `tls.enabled: true` against a plain address is an error.
3. **Apply `sslParameters()` to every socket or engine**, not only the context: hostname verification, protocols and
   cipher suites live there. A library that takes only an `SSLContext` and cannot take parameters needs its own
   hostname option (ActiveMQ's `socket.verifyHostName`).
4. **Do not log or show secrets**; `TlsSettings.toString()` and `TlsMaterial.warnings()` carry none.
5. **Wrap `annotate` around `health()`** so an expiring certificate reaches Admin → Health.
6. **Test with generated certificates**: `drishti-testkit` has `TestPki` (a CA and certificates made at test time with the
   JDK alone, written as PEM in every key format, PKCS12 or JKS, with chosen validity), so tests need neither keytool nor
   openssl. The shipped connectors' tests (`KafkaSecureBrokerTest`, `ActiveMqTlsTest`, `RabbitMqTlsTest`) run a real
   broker in Docker with them; `TlsContextsTest` covers the module itself.
7. **Document the keys** by pointing at [DATA_GUIDE.md section 10](DATA_GUIDE.md#10-tls-and-the-outbound-proxy) and showing a complete example for your protocol.

Where a client library builds its own TLS from properties (Kafka), translate from the `TlsMaterial` raw parts: see
`KafkaSecurity` in `drishti-plugin-kafka`, which hands Kafka the PEM text it understands.

Other shapes the shipped connectors show, for a library that wants something other than an `SSLContext`:

- **The JDK `HttpClient`** (REST, feeds): `TlsHttp.material(settings, url, "base-url")` does the whole start-up rule (null for
  `http://`, a `TlsException` for `tls.*` beside a plain URL, the built material for `https://`) and `TlsHttp.apply(builder, material)`
  gives the client the context, protocols and ciphers, and honours `tls.verify-hostname: false`, which the JDK client would
  otherwise ignore.
- **A driver that loads its socket factory by class name** (PostgreSQL's `sslfactory`): register the `TlsMaterial` under a
  generated id and pass the id as the driver's factory argument (`PgSslFactory` in `drishti-plugin-jdbc`); unregister on close.
- **A client that takes its own policy object** (Aerospike's `TlsPolicy`, MongoDB's `SslSettings`, Lettuce's `SslOptions`, the AWS
  SDK's `TlsTrustManagersProvider`): copy `sslContext()`, `trustManagers()`/`keyManagers()`, the protocols and the cipher suites
  from the material; see `AerospikeTls`, `MongoTls`, `RedisTls` and `S3SourcePlugin.tlsMaterial`.
- **A library with no hook** (Hadoop's S3A has none for a private CA): say so at start when `tls.*` is set, and name the JVM
  truststore as the way out, as the Delta and Iceberg connectors do, rather than ignoring the settings.
- **The settings form** is generated for you: `TlsSettings.settingSpecs("tls.")` is what Admin -> Connectors offers, and a test
  (`SettingCatalogueTlsTest`) fails if it drifts from what `TlsSettings` reads. A plugin with `tls: true` in
  `plugin-settings.yaml` gets the section; a second endpoint lists its prefix under `tls-prefixes`.
- **Test** with `TlsFixture` in `drishti-testkit` (a CA, a server certificate for `localhost` and `127.0.0.1`, a client
  certificate, a second CA to prove refusal, PEM and PKCS12 files, and a server `SSLContext` for an in-process HTTPS server).

### 10.9 Outbound HTTP proxy: the shared module

A plugin that speaks HTTP should honour the site's forward proxy the same way every shipped one does. The settings (`proxy.url`, `proxy.username`, `proxy.password`,
`proxy.password-file`, `proxy.no-proxy`, `proxy.enabled`, or the bare `proxy: none`), the site default `drishti.http.proxy.*` and the rules are in
[DATA_GUIDE.md section 10 (proxy)](DATA_GUIDE.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers); the code is `com.ash.drishti.api.proxy.ProxySettings` in `drishti-api` (Spring-free, like the TLS module). In `start`:

```java
ProxySettings proxy = ProxySettings.resolve(ctx.settings(), baseUrl);   // connector block, else site default, else environment, else none
HttpClient.Builder b = proxy.apply(HttpClient.newBuilder());            // selector (honours no-proxy) and Basic authenticator
...
HttpResponse<String> r = proxy.send(client, request, BodyHandlers.ofString());   // I/O errors come back in plain words (407, CONNECT refused, DNS)
...
public String health() { return proxy.annotate("UP", baseUrl); }        // "... via proxy host:port (connector)" or "(proxy ... not used for host: no-proxy)"
```

`resolve` throws `ProxyException` for a malformed block (a literal password, an `https://` proxy, a username without a password): let it fail the start with its message.
A library with its own proxy settings (the AWS SDK, Hadoop's S3A, the Iceberg REST client) is given `host()`, `port()`, `username()` and `password()` instead of
`apply` (`appliesTo(host)` and `uri()` help; `decided()` says to switch the SDK's own reading of the JVM's proxy properties off); see `S3SourcePlugin`,
`HadoopLake` and `SchemaRegistryClient`. Never print `password()`: `describe()`, `note()`, `annotate()` and `toString()` never include it. The form of **Admin -> Connectors** offers the **Proxy** section to a plugin with `proxy: true` in
`plugin-settings.yaml` (generated from `ProxySettings.settingSpecs()`, so a plugin does not list the keys itself). Test with `FakeProxy` in `drishti-testkit`: a forward
proxy on a local port that tunnels `CONNECT`, counts what passes and can demand Basic credentials.

### 10.10 Reconnecting and failures

Every connector recovers from an outage without a restart of Drishti, and starts even when its store is down. Your
plugin should too: **start must not fail because the store is down.** Keep the connection lazy or retry in the
background, answer `health()` with `DOWN: … (reconnecting)` meanwhile, and throw from `fetch` (never return empty) until
you know what you hold. How each shipped connector does it:

| Connector | How |
|---|---|
| `jdbc` | a pool of lazy slots: each opens its connection on first use and reopens it when broken; table kinds are listed in the background (every 10 s) until the database answers |
| `kafka` | the Kafka client rides out a broker outage and carries on (health `DOWN: no connection to the broker (reconnecting)` after 10 s without one); a supervisor recreates the consumer after a fatal error (backoff 1 s → 30 s) and resumes at the last applied offset (a restart of the server replays the topic from the beginning, in both modes) |
| `iceberg` | each table's snapshot is checked every `refresh-seconds`; a failed check keeps the last one, and the next succeeds by itself |
| `duckdb` | nothing to reconnect to: the file is read in-process. A missing or unopenable file shows `DOWN: no DuckDB file at …` or `DOWN: DuckDB file … not opened: …`; the file is checked every `refresh-seconds` and opened once it is there. A file replaced by a load is reopened as a new generation, and reads in flight finish on the old one |
| `mongodb` | the driver connects in the background; reads of kinds not yet catalogued answer "not held"; the next refresh fills the catalogue |
| `redis` | Lettuce reconnects by itself; the next refresh refills the catalogue and the `<domain>:changes` subscription resumes. While health says `DOWN`, reads fail at once (`fail-fast`) and a check every `recheck-ms` (1 s) resumes them as soon as Redis answers |
| `aerospike` | the client tends the cluster in the background (`failIfNotConnected` off); the next refresh refills the catalogue |
| `activemq` | the failover transport reconnects; its interruptions show in health; a supervisor rebuilds the session after any other failure |
| `rabbitmq` | a supervisor retries until the first connection succeeds; then the client's automatic recovery reconnects and re-subscribes (a consumer the broker cancels, `DOWN: consumer cancelled on <queue>`, is not re-subscribed until a restart) |
| `delta`, `file`, `rest`, `s3`, feeds, `news`, `yfinance` | nothing long-lived to lose: each call reads or connects afresh |

While a store is down its reads fail (views say which source failed) and `health` reports `DOWN: … (reconnecting)`.

**What the host adds.** A plugin that throws from `start` (other than `PluginNotConfigured`), or whose `health()` stays *down* after it was up, is retried by the
server itself with the shared backoff `drishti.retry.connectors.*` (5 s doubling to 5 min, 20 % jitter; errors no retry can fix are retried only every `max`): see
[DATA_GUIDE.md section 9](DATA_GUIDE.md#a-connector-that-fails-to-start-or-loses-its-connection-is-retried). A plugin that retries inside (HTTP calls to a service)
uses `RetryPolicy` from `drishti-common` in the same shape (`RetryPolicy.of(lookup, area)`), as the `news` and `yfinance` plugins do with their `retry-*` settings.

### 10.11 Connectors that compute data on demand

A connector does not have to sit in front of a store. `fetch(ref)` is Java code, and what it returns can be **worked out when it is asked
for**: a web search, a calculation, a call to an in-house service, a join of several systems. Two shipped connectors are written this way
and are the examples to read: [`news`](../../plugins/drishti-plugin-news/README.md) (headlines for the name in the id, from Google News RSS) and [`yfinance`](../../plugins/drishti-plugin-yfinance/README.md) (a quote
and price history for the ticker in the id, from Yahoo Finance). Neither has a database, a Delta table or a file behind it.

The pattern, in six rules:

1. **The id is the question.** `NEWS-Federal_Reserve`, `QUOTE-AAPL`. Parse it defensively (length, characters, prefix); an id that is not
   yours, or names nothing, is `Optional.empty()`, not an error. A pack's document holds the id, and a panel reads the answer with
   `source: "link($.newsId, 'news')"`.
2. **Do nothing at `start`.** There is no entity yet, and the world may be unreachable. Read and validate the settings (fail with the
   setting's name), build the HTTP client, and return. Health is `UP` until a call fails.
3. **Bound everything.** A timeout on every call, a limit on the bytes read, a limit on the cache. The work runs on a virtual thread
   and must never run longer than its timeout.
4. **Cache what is dear to compute**, keyed by what the answer depends on (not by the id when two ids share one expensive call: `NEWS-x` and
   `NEWS-x~all` share one web call), with a TTL and a maximum size, and make concurrent callers for one key share a single call.
   Serve an older answer when a refresh fails rather than nothing, and say so in `health()`.
5. **Fail loudly, retry sensibly.** Throw from `fetch` when the work fails (never return empty, which means "not held" and would tell the
   view the entity does not exist). Repeat only what waiting can fix (connection errors, timeouts, 429, 5xx) with the shared backoff
   (`RetryPolicy` from `drishti-common`, the shape of `drishti.retry.*`), and stop at once for the rest (403, an oversized body).
6. **Treat the answer as hostile.** Parse XML with DOCTYPEs and external entities off; parse JSON for the fields you need and read the
   wrong type as missing; allow only `http` and `https` links; strip markup from text.

Show the logic in the document. The news connector returns, beside the headlines, the list of steps that chose them and how many each
dropped (`pipeline`), so a person who wonders why a headline is missing can see which step removed it.

What it costs the rest of the system: a view waits for linked entities only `drishti.graph.link-budget` (40 ms), so the first view of a
slow computed entity shows its panel as pending, and the next shows it from the cache. Raise the budget if you build pages on such
connectors ([CLUSTER_GUIDE.md](CLUSTER_GUIDE.md#16-tuning)). And data that leaves the network must be said so wherever it is switched on:
both shipped connectors are `enabled: false` as shipped and name what they send in their documents and in the pack's pages.

Test it without the network: a local `com.sun.net.httpserver.HttpServer` serving canned answers (and a bad answer for every rule above:
an XXE attempt, an oversized body, a slow response, two 503s then success), and the contract of a source that is not dated. See
`FakeNewsServer` and `NewsSourcePluginTest` in `plugins/drishti-plugin-news`, and `FakeYahooServer` in `plugins/drishti-plugin-yfinance`.

### 10.12 Testing with drishti-testkit

`drishti-testkit` holds the contract tests every plugin must pass: write the setup, inherit the assertions. Add it as
a test dependency (with `drishti-common` for its JSON codec).

| Class | For | You supply | It checks |
|---|---|---|---|
| `DatedSourceContract` | any dated store | `plugin()`: the started plugin over a store holding `ROWS` (trades as `snapshot` data over three business days, counterparties as `effective` data), with `mode.counterparty = effective`; `context(settings)` builds a `SourceContext` | it is dated and serves `trade` and `counterparty`; each business date reads its own data; a snapshot kind takes the newest date on or before the one asked, an entity absent from it is gone; an effective kind carries the last change forward; reverse lookups follow the date; search finds ids; `history` gives one day per date held (snapshot lookback days and carried effective days left out), the last N days, the fields asked, `days()` agreeing with it, and each day equal to `fetch` of that date |
| `MessageSourceContract` | a message-queue connector, against a real broker | `start(Path state)`, `send(destination, id, body, deleted)` | documents and envelopes become entities; updates reach live views; deletes remove; the latest state survives a restart of Drishti; messages sent while Drishti was down arrive when it returns; search finds what arrived |
| `BrokerOutage` | a message connector's recovery | `broker(port)` (kill it by closing), `start(port, state)`, `send(port, destination, body)` | a connector started before its broker exists connects when the broker comes up, and again after the broker is killed and started on the same port |

The `file`, `jdbc`, `delta`, `iceberg`, `duckdb`, `mongodb`, `redis` and `aerospike` plugins all extend
`DatedSourceContract`, and `activemq` and `rabbitmq` extend `MessageSourceContract` and `BrokerOutage`; the tests of
the stores that need a server run it in Docker with Testcontainers and skip where Docker is not reachable. Use Testcontainers or a
port nothing else listens on for your own store; never point a test at a service that is already running.

`DatedSourceContract.plugin()` is called more than once per test, so build the plugin once and cache it. The teaching
plugin's test is the whole recipe:

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayFolderContractTest extends DatedSourceContract {
    private SourcePlugin plugin;

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin == null) {
            Path folder = Files.createTempDirectory("dayfolder-contract");
            // write ROWS as <kind>/<date>.jsonl, one line per entity ...
            DayFolderSourcePlugin p = new DayFolderSourcePlugin();
            p.start(context(Map.of("root", folder.toString(), "id-field.trade", "tradeId",
                    "mode.counterparty", "effective", "link.trade.netting-set", "nettingSet", "rescan-seconds", "0")));
            plugin = p;
        }
        return plugin;
    }
}
```

The contract covers the happy path. **Add your own tests for the failure semantics**, which are where plugins go wrong:
`PluginNotConfigured` without the required setting, `UnreadableData` for a bad line, `health()` going `DOWN` or
`DEGRADED` and healing, `Optional.empty()` versus a thrown exception, and discovery through `ServiceLoader`
(`DayFolderFailureTest` does each).

### 10.13 A worked example: the dayfolder plugin

`docs/guides/examples/connector/` is a complete, small, **dated** source plugin written for teaching. It is built and
tested with the rest of the project (`./mvnw verify`), but it is **not shipped**: the server does not depend on it,
it is not one of the nineteen plugins, and it exists to be copied.

```text
docs/guides/examples/connector/
  pom.xml                                          depends on drishti-api (+ drishti-common and testkit for tests)
  src/main/java/.../DayFolderSourcePlugin.java     the plugin (under 300 lines)
  src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin
  src/test/java/.../DayFolderContractTest.java     the DatedSourceContract
  src/test/java/.../DayFolderFailureTest.java      discovery, not configured, unreadable data, a failed rescan
  sample/                                          a folder you can point it at
```

**The data.** A folder `<root>/<kind>/<yyyy-MM-dd>.jsonl`, one JSON document per line:

```text
sample/trade/2026-09-29.jsonl         {"id":"T-1","book":"RATES-1","mtm":100.5,"nettingSet":"NS-A"}  ...
sample/trade/2026-09-30.jsonl         three trades (T-3 appears only here)
sample/counterparty/2026-09-29.jsonl  {"id":"CP-X","name":"Acme Bank","rating":"A"}
```

**How it maps onto the SPI**, method by method:

| SPI | In the plugin |
|---|---|
| `manifest` | `new PluginManifest("dayfolder", "1.0", kinds, new SourceCapabilities(false, true, true, true))`: not live, reverse lookups, search, dated. The kinds are the folders found; before `start` the set is empty (any kind) |
| `start` | reads `root` (throws `PluginNotConfigured` when missing), loads the whole folder once (fails loudly if it cannot), then schedules a rescan every `rescan-seconds` on `ctx.scheduler()` |
| the scan | builds an immutable `Snapshot` (kind → day → id → `DataNode`), parsed with `ctx.parseJson`, and swaps it in with one volatile write; concurrent reads see the old or the new, never half. A bad line throws `UnreadableData("trade/2026-09-28.jsonl line 2 is not a document")` naming the file and line and no content |
| `fetch(ref, asOf)` | `view(kind, date)`: for a `snapshot` kind the newest day on or before the date; for an `effective` kind every day up to it, merged. Returns the entity as an `EntityDocument` whose `Provenance` carries the day the document is for. Empty only because it is not in that view |
| `coverage` | `HELD` when the kind has a day on or before the date, `NOT_HELD` when it has none, `UNKNOWN` without a date |
| `search` | a `HitIndex` rebuilt by each scan: answers from memory |
| `reverse` | setting `link.<kind>.<target-kind>` names the document field that refers to the target; returns the ids in the date's view whose field equals the target id |
| `listingProblem` / `health` | after a failed rescan the previous scan keeps serving, `health()` says `DEGRADED: … (serving the previous scan)` and `listingProblem` makes searches partial; the next good scan heals both |
| `cacheStats` / `purgeCaches` | the counts of the snapshot; a purge rescans |
| `close` | cancels the rescan |

The settings it reads are in the class's javadoc: `root`, `source-name`, `id-field` and `id-field.<kind>`,
`mode.<kind>` (`snapshot` or `effective`), `link.<kind>.<target-kind>`, `rescan-seconds`.

**Run its tests** (they run in every `./mvnw verify`):

```bash
./mvnw -q -o test -pl docs/guides/examples/connector -am -Dtest='DayFolder*' -Dsurefire.failIfNoSpecifiedTests=false
```

**Serve a view from it.** Build the jar, drop it in a plugin folder, and declare a connector:

```bash
./mvnw -q -o package -DskipTests -pl docs/guides/examples/connector -am
mkdir -p plugins-extra && cp docs/guides/examples/connector/target/drishti-example-dayfolder-*.jar plugins-extra/
```

```yaml
# application.local.yaml
drishti:
  sources:
    routes: { trade: desk-days, counterparty: desk-days }
```

```yaml
# config/connectors/desk-days.yaml
plugin: dayfolder
kinds: [trade, counterparty]
settings: { root: docs/guides/examples/connector/sample, source-name: desk-days, mode.counterparty: effective,
            link.trade.netting-set: nettingSet }
```

Restart (the plugin jar is loaded at start; the connector file is applied within seconds), then `curl -s localhost:18480/api/v1/sources | jq -c '.sources[] | select(.name=="desk-days")'` and open
`trade T-1` in the console (a pack that gives `trade` a mnemonic is needed for typing it; the API reads any kind).
Pick 29 September in the business-date picker: the view shows an MTM of 101 (100.5 in the file); pick the 30th: 112. *How this view was built*
names `desk-days`.

![Admin → Health: desk-days (the example plugin) UP, a database and a broker that nothing answers on DOWN with the reason, and the shipped connectors](../connectors/img/connectors/sources-health.jpg)

![The business-date picker on a dated source](../connectors/img/connectors/business-date-picker.jpg)

![A view served by the example plugin](../connectors/img/connectors/dayfolder-view.jpg)

![How this view was built: the provenance names the source](../connectors/img/connectors/provenance.jpg)

**What to take from it.** Keep the scan separate from the reads; swap state atomically; never return empty for a
failure; keep search in memory; say `DEGRADED` instead of lying; test the failure paths as well as the contract. What
it leaves out, on purpose: streaming a large file instead of reading it whole, a persistent index, columns
(`columnar`/`columns`), live updates and time travel. The shipped plugins show each of those: the table below says
which.

| To learn… | Read the shipped plugin |
|---|---|
| persistent indexes, id offsets, columns | `drishti-plugin-file` (JSON lines), `drishti-plugin-duckdb` |
| live updates, supervisors, backoff | `drishti-plugin-kafka`, `drishti-plugin-rabbitmq` |
| time travel (`knownAt`) | `drishti-plugin-delta`, `drishti-plugin-iceberg` |
| lazy connection slots and reconnecting | `drishti-plugin-jdbc` |
| a plugin built on other kinds | the engine's `derived` source (uses `SourceContext.reader()`) |

### 10.14 The README standard for connectors

Every connector **type** has a `README.md`: each plugin at its root (`plugins/drishti-plugin-<x>/README.md`, carried in its jar at
`drishti/readme/<artifactId>/README.md`) and each built-in type (`derived`, `net`, `reactive`) in `docs/connectors/types/<type>/README.md`. Every
folder of ready-made connector **templates** (`config/connectors.examples/<x>/`) has one too. One or two pages each, for people, for
Admin → Connectors (a connector's page shows its type's README) and for AI (`GET /api/v1/admin/connectors/types/{type}/readme`). A new
plugin needs one before it merges: `tools/test_readmes.py` lists the plugin folders, the built-ins and the template folders from the disk
and fails on a missing README, a missing or invalid front matter, an empty section or a stale generated block.

The licence comment, a YAML front matter, `# The <x> connector`, then six `## ` sections in this order:

```markdown
---
connector: dayfolder          # the name a connector file's plugin: gives (templates: template: <folder>)
category: plugin              # plugin | built-in | template
summary: One line.
capabilities: [fetch, search, history]   # of fetch, search, subscribe, history, reverse-lookup, bulk-scan, write
settings: []                  # written by tools/connector_readmes.py from the settings catalogue
auth: How it signs in (secrets as ${ENV} or file:).
licence: The client library, its licence, bundled or not.
windows: What differs on Windows (no .exe may be placed on the QA machine).
doc: plugins/drishti-plugin-dayfolder/README.md   # the full document: for a plugin, its README itself
---
# The dayfolder connector
## What it reads
## Settings
<!-- settings:begin (generated by tools/connector_readmes.py from drishti-engine/src/main/resources/drishti/plugin-settings.yaml; edit the catalogue, not this table) -->
<!-- settings:end -->
## Data structure              # the record it returns: the field mapping, the id rule, nested structures; for a store the table
                               # layout or DDL; when generic, "depends on the source; mapping via <settings>"
## Examples                    # a connector file; a template folder's README ends with <!-- files:begin --> <!-- files:end -->
## Troubleshooting
## See also                    # the guides and documents it relates to
```

`python3 tools/connector_readmes.py` fills the generated parts: a type's settings table and its `settings:` list from
`plugin-settings.yaml` (the catalogue Admin → Connectors builds its form from), a template folder's table of files and its `files:` and
`connectors:` lists from the files. `--check` fails when they are stale. A type the catalogue does not list (the built-ins) keeps a
hand-written settings table and `settings:` list. A plugin's README is its one document: deeper sections (how it reads, dates,
loading, limits) may follow Troubleshooting, before See also.

## 11. The API: calling the server from scripts, notebooks and Excel

This chapter is for anyone who calls the Drishti server directly: to script a report, feed another tool, check a deployment, or
see what the console does behind the scenes. It covers what you need before the first call, tokens and clients, authentication,
the business date, errors and Arrow answers. Every endpoint, grouped by what it is for, with a `curl` command you can paste and an
abbreviated copy of a real answer, is in [API_REFERENCE.md](API_REFERENCE.md). Live streaming in depth is in
[DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#1-live-updates); users, roles and sign-in in
[ADMIN_GUIDE.md](ADMIN_GUIDE.md).

### 11.1 Before you start

| What | Value |
|---|---|
| Server base URL | `http://localhost:18480` (port set by `server.port`) |
| API prefix | `/api/v1` — every path in the [API reference](API_REFERENCE.md) is under it unless it says otherwise |
| Format | JSON in and out (`application/json`), except Sutra text (`text/yaml`), the Rachana schema (`application/schema+json`) and live streams (`text/event-stream`) |
| Errors | RFC 7807 `application/problem+json` with a stable `code` such as `DRS-1001` |
| OpenAPI document | `http://localhost:18480/api/docs` |
| Swagger UI (try calls in a browser) | `http://localhost:18480/api/docs/ui` |

You need `curl`. `jq` is optional but makes the output readable; every example below pipes through it. On
Ubuntu/Debian: `sudo apt install curl jq`.

To save typing, set a variable once in your shell:

```bash
B=http://localhost:18480/api/v1
```

All examples below use `$B`. They were captured against a development server with security off and the
banking packs installed; your ids, numbers and times will differ.

### 11.2 Your first five calls

Work through these in order. If all five answer, the API is working for you.

**1. Is the server up and what is it running?**

```bash
curl -s $B/about | jq '{product, version, java, uptimeSeconds, securityEnabled, sutras: (.sutras | length)}'
```

You should see:

```json
{
  "product": "Drishti",
  "version": "2.5.0",
  "java": "21.0.12.1 (Ubuntu)",
  "uptimeSeconds": 6913,
  "securityEnabled": false,
  "sutras": 223
}
```

If `securityEnabled` is `true`, read [Who you are](#114-who-you-are-authentication) first: every call needs a token.

**2. What is "today"?**

```bash
curl -s $B/business-date | jq '{current, selected, live, previous, calendar, zone}'
```

```json
{ "current": "2026-09-30", "selected": "2026-09-30", "live": true,
  "previous": "2026-09-29", "calendar": "USNY", "zone": "America/New_York" }
```

**3. Find something to look at.** The type-ahead takes what you would type in the console's command line.
`TRD ` (with a trailing space) lists trades:

```bash
curl -s "$B/command/suggest?q=TRD%20&limit=2" | jq -c '.[] | {complete, subtitle}'
```

```
{"complete":"TRD MX-20000001","subtitle":"Trade · Interest rate swap (fixed/float) · Meridian Reinsurance Ltd · AUD 242m"}
{"complete":"TRD MX-20000002","subtitle":"Trade · Interest rate swap (fixed/float) · Halcyon Shipping plc · EUR 110m"}
```

**4. Open it.** A view is the screen the console draws, as data:

```bash
curl -s $B/views/trade/MX-20000001 | jq -r '.strip[] | "\(.label): \(.text)"'
```

```
Notional: AUD 242,000,000
Direction: Receive fixed
Trade date: 2025-07-25
…
MTM (USD): +1,603,277
```

**5. See the document behind it.**

```bash
curl -s $B/entities/trade/MX-20000001/raw | jq '.data | {tradeId, productType, assetClass}'
```

```json
{ "tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", "assetClass": "Rates" }
```

### 11.3 Clients: scripts, notebooks and Excel

Everything you can open in the terminal you can also read from code, as yourself, with a **personal API token**.
A token reads what you may read (your roles and packs, at the time of each call) and, unless you gave it a **write scope**,
**never changes anything**: any request other than `GET` with a read-only token is refused (`403 DRS-5002 API tokens only
read`), except the `POST`s that only read (`/pivot/query`, `/pivot/decompose`, `/pivot/estimate`, `/pivot/expand`, `/pivot/compare`, `/pivot/rows`, `/pivot/export`, `/command`, the allow-list
`drishti.security.token-read-posts`, which also lets a token ask a BI model, `POST /api/v1/bi/mq`). Tools and CI that must change things get a token with the scope for
that job; see "Personal API tokens" in [CLI_REFERENCE.md](CLI_REFERENCE.md) and below.

#### Make a token

1. Open **My account → API tokens**.
2. Give it a name that says what uses it (`Risk notebook`, `Desk P&L workbook`) and, if you like, a lifetime in days
   (1–366; blank for no expiry). Tick a **scope** only if the token must change things (the page explains each, and the [scope table](API_REFERENCE.md#28-personal-api-tokens) lists what each opens; the
   list is `drishti.security.token-scopes`). A token with a write scope must expire (90 days at most).
3. Press **Create token**. You see the token once, for example `drk_7QhK2mPq9xZa_4kq…` (`drk_`, a 12-character id, `_`,
   a 43-character secret). **Copy it now**: Drishti keeps only a hash of the secret and cannot show it again.
4. The list shows each token's id, when it was created, when it expires, when it was last used, and **Revoke**.

Send it in the `Authorization` header:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "https://drishti.bank.example:18480/api/v1/entities/trade/MX-20000001/raw" | jq .data.QUANTITY
```

You should see the trade's quantity: on the demo lakes `242000000` (the MUREX interest-rate swap's notional; the trades carry no MTM,
that is in the market-risk pack's trade valuations, `TVAL`).

Keep tokens like passwords: in an environment variable or a secrets store, never in a shared file. If one leaks,
revoke it: it stops at once. A token also stops when its owner is disabled, and when it expires. Administrators see
and revoke everyone's tokens in **Admin → Tokens**; every creation and revocation is in the audit log
(`token-created`, `token-revoked`).

#### Python

`clients/python/drishti_client.py` is one file that uses Python's standard library only: copy it next to your
notebook, or put its folder on `PYTHONPATH`.

```python
from drishti_client import Drishti

d = Drishti("https://drishti.bank.example:18480", token="drk_…")   # or set DRISHTI_URL and DRISHTI_TOKEN

d.field("trade", "MX-20000001", "QUANTITY")            # 242000000.0
d.field("trade", "MX-20000001", "LEGS[0].NOTIONAL")    # a path into the document: 242000000.0
doc = d.document("trade", "MX-20000001")               # the whole document (masked fields stay masked)

rows = d.search("TRD PRODUCT_TYPE=REPO")            # a pick list: one dict per entity, with the pack's key columns (96 repos)
# [{'kind': 'trade', 'id': 'CLY-3000265', 'title': 'CLY-3000265', 'Product type': 'REPO', 'Source system': 'CALYPSO', …, 'Quantity': 180000000, …}, …]

d.search("TRD where QUANTITY > 1bn order by QUANTITY desc limit 20", as_of="2026-10-14")   # a past business date
d.diff("trade", "MX-20000022", "2026-10-07", "2026-10-16")                                    # what changed between two dates
h = d.history("trade", "1000015", days=30)          # the entity over its last 30 business days with data (rows oldest first)
d.history("equity", "EQ-CSCA", range_="3M", fields=["PRICE"])   # 30D, 2W, 1Y, YTD, MTD, QTD; or date_from=, date_to=; diff=True
d.history_rows("trade", "1000015")                  # one flat dict per day, newest first: {'date': '2026-10-19', 'QUANTITY': 72000.0, …, 'changed': ['PRICE']}
df = d.to_pandas("CPTY RATING=BBB")                 # a pandas DataFrame, if pandas is installed: 3 rows, columns kind, id, title, Rating, Name, …
```

A refusal raises `DrishtiError` with the server's code: `e.code == "DRS-5002"` (not yours to open), `"DRS-1001"` (no
source holds it), `"DRS-5010"` (the token is unknown, revoked or expired).

From the shell, the same client writes CSV:

```bash
export DRISHTI_URL=https://drishti.bank.example:18480 DRISHTI_TOKEN=drk_…
python3 clients/python/drishti_client.py search "TRD PRODUCT_TYPE=REPO" > repos.csv
python3 clients/python/drishti_client.py field trade MX-20000001 QUANTITY          # 242000000.0
python3 clients/python/drishti_client.py --as-of 2026-10-14 document trade MX-20000001
```

#### Excel

Any search or pick list is also CSV at `GET /api/v1/search/csv?q=…`: a header row (`kind`, `id`, `title`, then the
columns) and one row per entity, values unformatted. Text that a spreadsheet would run as a formula (starting with
`=`, `+`, `-` or `@`) is prefixed with `'`.

**Power Query** (Data → Get Data → From Other Sources → Blank Query → Advanced Editor):

```
let
    Url    = "https://drishti.bank.example:18480/api/v1/search/csv",
    Token  = "drk_…",
    Source = Csv.Document(Web.Contents(Url, [Query = [q = "TRD PRODUCT_TYPE=REPO"],
                                             Headers = [Authorization = "Bearer " & Token]]),
                          [Delimiter = ",", Encoding = 65001, QuoteStyle = QuoteStyle.Csv]),
    Table  = Table.PromoteHeaders(Source, [PromoteAllScalars = true])
in
    Table
```

Press **Close & Load**: the sheet fills with the 96 repos of the demo lakes and their key columns (`kind,id,title,Product type,Source
system,Trade status,Trade date,Buy sell code,Currency,Quantity,Maturity date,Portfolio,Counterparty`); **Refresh** reads them again.
For another query change `q`; for a past business date add `Headers = [Authorization = …, #"X-Drishti-As-Of" = "2026-10-14"]`.
When Excel asks how to connect, choose **Anonymous**: the token in the header is the credential. Better than writing
the token into the query: keep it in a named cell or a Power Query parameter only you can see.

#### Any other language

It is plain HTTP and JSON; the [API reference](API_REFERENCE.md) lists every endpoint. The ones scripts use most:

| Read | `GET` |
|---|---|
| a document | `/api/v1/entities/{kind}/{id}/raw` |
| a view (strip, panels, provenance) | `/api/v1/views/{kind}/{id}` |
| a pick list or search | `/api/v1/search?q=…` (JSON) or `/api/v1/search/csv?q=…` (CSV) |
| what changed between dates | `/api/v1/history/{kind}/{id}/diff?from=…&to=…` |
| a pack's kinds | `/api/v1/packs/{code}/overview` |
| the BI catalogue, an item, a dataset or report | `/api/v1/bi/catalogue`, `/api/v1/bi/items/{id}`, `/api/v1/bi/datasets/{id}`, `/api/v1/bi/reports/{id}` |

A BI question is a model query, a `POST` with the fields, measures and filters in the body (`/api/v1/bi/mq`);
it only reads, so a read-only token may call it, and the answer carries the
owner's row rules and masks ([the model query in the API reference](API_REFERENCE.md#5-models-and-the-model-query-language-apiv1bi)):

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' "$DRISHTI_URL/api/v1/bi/mq" \
  -d '{"mq":1,"model":"trades-basic","define":{"N":"count(trade)"},"select":{"fields":["trade.SOURCE_SYSTEM"],"measures":["N"]},"time":{"as-of":"2026-10-16"}}'
# "rows":[["CALYPSO",5000],["ENDUR",5000],["MUREX",5000]]
```

Add `X-Drishti-As-Of: 2026-10-14` for a past business date and `X-Drishti-Known-At: 2026-10-14T14:30:00Z` for the
data as known at an instant (stores with time travel only; others answer `DRS-1007`). For an id with a `/` (or `\`),
put `~` in its place and the id in the query: `/api/v1/entities/trade/~/raw?id=sl%2Fash-6`; the Python client does
this by itself.

### 11.4 Who you are: authentication

Every request under `/api/v1/` passes through the server's token filter (`TokenFilter`). What it does
depends on one setting, `drishti.security.enabled` (environment variable `DRISHTI_SECURITY_ENABLED`,
default `false`).

**Paths must be written plainly.** The token filter (and the guard on `/actuator` and `/api/docs`) decides on the
path the server serves, after decoding. Under `/api` and `/actuator` a request line that spells a path in any other
form is refused, security on or off, with `400 DRS-5001` problem+json before any other check: a path parameter
(`/api/v1;x/packs`), a percent-encoded letter, digit, `- . _ ~`, `/` or `\` (`/api/%761/packs`), a malformed escape,
or a dot or empty segment (`/api/./v1/packs`, `/api/v1/../v1/packs`, `/api//v1/packs`). Encode only what is data in
a name or an id (a space as `%20`, `;` as `%3B`, `%` as `%25`, `(` `)`, non-ASCII letters): those are accepted, as
`urllib.parse.quote(name, safe='')` and `encodeURIComponent` write them. The query string is not affected.

```bash
curl -s $B';x/packs'
# {"title":"bad request","status":400,"code":"DRS-5001","detail":"path parameters (;) are not accepted"}
```

#### Security off (local development)

No token is needed. The caller's name is taken from the optional `X-Drishti-User` header (default
`anonymous`), and that caller has every role. The name only matters for per-user data: settings,
workspaces, monitors, alerts, recent entities in the type-ahead, and chosen packs.

```bash
curl -s -H "X-Drishti-User: ash" $B/me/settings
```

Pack choices still apply with security off: a kind whose pack is not active for the named user is refused
with `403 DRS-5002`.

#### Security on (production)

Every `/api/v1/` call must carry `Authorization: Bearer <token>`. The token is a compact JWT signed with
**HS256** using the shared secret `drishti.security.secret` (environment variable `DRISHTI_TOKEN_SECRET`, at
least 32 bytes; the server refuses to start with a shorter one). The server checks:

| Check | Refusal (`detail`) |
|---|---|
| header present and starts with `Bearer ` | `missing bearer token` |
| three dot-separated parts | `malformed token` |
| header `alg` is exactly `HS256` (`none` and every other algorithm are rejected) | `unsupported token algorithm` |
| signature matches (constant-time compare) | `bad token signature` |
| `exp` is present and not past (plus `drishti.security.clock-skew`, default 30 s) | `token expired` |
| `sub` is not empty | `token has no subject` |
| anything unreadable | `unreadable token` |

The claims the server reads are `sub` (the user name), `roles` (a list of role names) and `exp` (expiry,
seconds since the epoch). A refusal is a `401` answered by the filter itself:

```json
{"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 missing bearer token"}
```

#### How the console does it

The console never forwards the user's password or a long-lived credential. In `drishti-console/core/auth.py`:

1. The user signs in on the console. The console verifies the password by calling `POST /api/v1/auth/login`
   with its own **service** identity (user `console`, role `service`) — the only identity allowed to call
   that endpoint (or `POST /auth/oidc` for single sign-on).
2. The console opens a **session** on the server (`POST /api/v1/auth/sessions`, service identity) and keeps its id,
   with the user's name, in a signed cookie (`drishti_session`, HMAC-SHA256 with `auth.session_secret`). On each
   request it asks the server whether the session still stands and who the user is now
   (`GET /api/v1/auth/sessions/{id}`: enabled, roles, whether a password change is due), and reuses the answer for
   `auth.recheck_seconds` (default 10). So disabling or demoting a user takes effect within that time, and at once on
   the console the administrator used. Signing out (`POST /logout`) ends the session on the server
   (`DELETE /api/v1/auth/sessions/{id}`), so a copy of the cookie stops working. Disabling, deleting or resetting the
   password of a user ends all their sessions; enabling them again does not bring an old one back.
3. For **every** backend call it mints a fresh token with `auth.token_secret` (the same value as the server's
   `drishti.security.secret`), lifetime `auth.token_ttl_seconds` (default 300), carrying the user's **current** roles,
   and sends two headers:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJqZG9lIiwicm9sZXMiOlsidHJhZGVyIl0sImV4cCI6MTc5MDAwMDAwMH0.…
X-Drishti-User: jdoe
```

   (With security on, the server ignores `X-Drishti-User`; the token's `sub` decides.) It also sends
   `X-Drishti-As-Of` and, when set, `X-Drishti-Known-At` — see the next section.

#### Minting a token for a script

When security is on, a script can mint its own token exactly as the console does. This needs the shared
secret, so run it only where that secret is legitimately available:

```bash
TOKEN=$(DRISHTI_TOKEN_SECRET="$DRISHTI_TOKEN_SECRET" python3 - <<'PY'
import base64, hashlib, hmac, json, os, time
b = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
h = b(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
c = b(json.dumps({"sub": "report-bot", "roles": ["viewer"], "exp": int(time.time()) + 300}, separators=(",", ":")).encode())
s = b(hmac.new(os.environ["DRISHTI_TOKEN_SECRET"].encode(), f"{h}.{c}".encode(), hashlib.sha256).digest())
print(f"{h}.{c}.{s}")
PY
)
curl -s -H "Authorization: Bearer $TOKEN" $B/about | jq .version
```

You should see the version string. The roles you put in the token decide what the
script may open (roles are defined under `drishti.security.roles`; see
[ADMIN_GUIDE.md](ADMIN_GUIDE.md)). Keep the lifetime short.

#### What roles allow

| Ability | Granted by |
|---|---|
| open entities of a kind | a role whose `kinds` lists the kind or `*`, **and** the kind's pack active for the user |
| every field unmasked (otherwise the fields in `drishti.security.redact` read `•••` on every endpoint: see below) | a role with `raw: true` |
| save Sutras from Studio | a role with `author: true` (and `drishti.rachana.studio-save: true`) |
| approve or reject Sutra proposals | a role with `approve: true`, or an admin |
| user administration, audit log, health, caches | a role with `admin: true` |
| `POST /auth/login`, `POST /auth/oidc`, `/auth/sessions` | the `service` role (the console) |
| everything | the role `*` (what every caller gets with security off) |

#### Field masks

For a caller without `raw`, every field named in `drishti.security.redact` (at any depth, and everything under it)
reads `•••` in every answer that shows or is computed from its value (masking is by field; see *Copies of a masked value* below for text in other fields): `/entities/…/raw`, `/search` (rows, CSV,
compare), `/history`, `/search/columns`, the search pivot endpoints, `/views/{kind}/{id}` (strip, title, every panel,
table totals, keys and links, values a Sutra computes from the field), `/views/{kind}/{id}/explain`, the view and
monitor streams, `/me/monitors/{name}`, `/studio/preview`, `/impact/{kind}/{id}`, `/command/suggest` and `/phrase`;
alert rules are evaluated on the document as their owner may see it. The server masks the documents in one place
(`Entitlements.redactor`) before anything reads them, so nothing about a masked field can be probed: a condition on
it is never true, ordering by it does not order, the type-ahead does not match it, Impact does not list entities
tied to the analysed one only through it, and a total over it reads `•••`.
A signed token whose user was deleted or disabled is refused with `401` (`drishti.security.registered-users-only`). A personal API token only reads, so `POST /views/{kind}/{id}/ask` answers `403` for it.

Shares, their notes and inbox rows are masked answers too: a note's copies of a masked value read `•••` for a reader without `raw`, and a notice carries no data value, only who, what kind and which id.

A `redact` entry with dots names a field by the end of its path (arrays are not a step): `lifecycle.timeline.description` masks
every `description` under `lifecycle.timeline`, and no other `description`. Names match in any case and convention
(`counterpartyId` masks `COUNTERPARTY_ID`, `trader_id` masks `TRADER_ID`), so a mask written in another naming convention
keeps masking; an entry that names no field of the loaded packs' samples is logged at start-up (`DRS-6011`).

**Copies of a masked value (`drishti.security.mask-copies`, default `false`).** Masking works on fields. With the setting on, after
masking, the server also replaces every exact occurrence of a masked field's original value inside the *other* text of the same
document with `•••`: with `trader` masked, "Captured by J. Smith" reads "Captured by •••". It runs in the same place as the masks
(`Entitlements.redactor`), so it holds on every path above, including Calc columns and the builder's previews, checks and evidence.
Best effort, bounded and exact: text values of at least `mask-copies-min-length` characters (3), numbers of four digits or more
rendered as text; case, spacing and spelling variants ("j. smith", "SMITH, J.") are **not** matched; a document with more than
`mask-copies-max-nodes` nodes (50 000) is not scanned (its masked fields are still masked) and at most `mask-copies-max-values` (64)
distinct values are scrubbed per document. Mask the text fields that carry sensitive values, and fix the source so they do not.

### 11.5 Which day: the business date

Every **read** (views, raw, search, history, impact, suggestions, monitors and streams) answers for a
business date. You choose it with a header or a query parameter:

| Header | Query parameter | Value | Meaning |
|---|---|---|---|
| `X-Drishti-As-Of` | `asOf` | `live` or empty (default) | today's business date, streaming |
| `X-Drishti-As-Of` | `asOf` | `2026-09-29` | that date, as a static snapshot |
| `X-Drishti-Known-At` | `knownAt` | `2026-09-29T21:00:00Z` (ISO instant) | the data as it was known at that instant (Delta Lake and Iceberg time travel) |

The header wins when both are given. (On the console's own pages the same two values are the query parameters `asOf` and
`knownAt` of `/v/{kind}/{id}`, read for that request only and never saved: that is how a copied *Share* link pins a
date; `gen` is the generation seen, shown as evidence in the page banner and never used to read data.) Rules:

- A weekend or holiday rolls back to the previous business day of the calendar.
- `knownAt` is answered only by stores that keep versions (Delta Lake, Iceberg). A dated store without them that may
  hold the date answers `400 DRS-1007` naming the connector, never today's data; a search names it in `failed` and is
  `partial`. Undated sources have one version and answer as usual.
- A picked date is a snapshot: views say `provenance.live = false`, and a stream sends one `view` event and closes.
- A future date, or one before the history window (`earliest`), is `400 DRS-4003`.
- `provenance.businessDate` in a view is the date the source actually read; it is `null` for sources that are not dated.

Try it — Sunday 27 September rolls back to Friday 25 September:

```bash
curl -s -H "X-Drishti-As-Of: 2026-09-27" $B/business-date | jq -c '{current, selected, live}'
```

```json
{"current":"2026-09-30","selected":"2026-09-25","live":false}
```

With a "known at" instant, as query parameters this time:

```bash
curl -s "$B/business-date?asOf=2026-09-29&knownAt=2026-09-29T21:00:00Z" | jq -c '{selected, live, knownAt}'
```

```json
{"selected":"2026-09-29","live":false,"knownAt":"2026-09-29T21:00:00Z"}
```

A date in the future:

```bash
curl -s -H "X-Drishti-As-Of: 2030-01-01" $B/business-date
```

```json
{"type":"about:blank","title":"bad business date","status":400,
 "detail":"DRS-4003 business date 2030-01-01 is in the future","instance":"/api/v1/business-date","code":"DRS-4003"}
```

### 11.6 When something goes wrong: errors

Every error is RFC 7807 `application/problem+json`. The fields:

| Field | Meaning |
|---|---|
| `status` | the HTTP status, repeated in the body |
| `title` | the error's name in words (`entity not found`, `bad search`, …) |
| `code` | the stable Drishti code, `DRS-nnnn` — match on this in scripts, never on `detail` |
| `detail` | a human explanation; it starts with the code |
| `instance` | the path that was called |
| `problems` | only for Sutra errors: the list of problems, each `{code, message, location: {file, line, column}}` |

Example — an id no source knows:

```bash
curl -s -i $B/views/trade/IRS-99999 | sed -n '1p;/^{/p'
```

```
HTTP/1.1 404
{"type":"about:blank","title":"entity not found","status":404,"detail":"DRS-1001 no source holds trade/IRS-99999","instance":"/api/v1/views/trade/IRS-99999","code":"DRS-1001"}
```

Three catch-alls are worth knowing: a bad argument anywhere (`IllegalArgumentException`) becomes
`400 DRS-5001`; a body that is not JSON, is empty, or is JSON of the wrong shape (a list for an object) is
`400 DRS-5001` (`the request body is not valid JSON`, …); and a request with no caller attached becomes `401 DRS-5010`. Server errors (`5xx`) are also
logged on the server with the stack trace. The full list is in the [error code table](API_REFERENCE.md#error-code-table).

**When the server is busy.** New heavy work (a sign-in, opening a VaR or exposure date that is not open yet, an Export data download, a BI
run, a bulk load) may be asked to wait: `503` with `Retry-After` and a code `DRS-9501` to `DRS-9505`, `DRS-9506` (an Arrow Flight read) or `DRS-9507` (a large pivot). The body adds `busy: true`, `title`
and `because` (the same words the console and `drishti.py` show) and `retryAfterSeconds`. A script waits that many seconds and asks
again, a few times at most; reads of what is already open are never asked to wait. Busy protection is described in
[OPERATIONS_GUIDE.md section 12.5](OPERATIONS_GUIDE.md#147-busy-protection-refuse-politely-never-fall-over).

### 11.7 Answers as Arrow: the Accept header

Two answers come as an **Apache Arrow IPC stream** when the caller asks with `Accept: application/vnd.apache.arrow.stream`:
`POST /api/v1/bi/reports/{id}/query` (a visual's answer) and `GET /api/v1/risk/var/scope/contributions` (a contribution table). The stream is
the JSON answer, cell for cell: its table (`rows`) is the record batch, and everything else in the answer travels in the schema's
custom metadata as JSON (`drishti.answer`), with `drishti.table` saying where the table goes back (`{"shape": "rows", "at": "rows"}` for
a list of lists named by `columns`; `{"shape": "records", "at": "rows", "absent": [...]}` for a list of objects, `absent` naming the keys
some objects leave out). Text that repeats (desks, books, currencies, dates) is a dictionary column; integers are 64-bit, numbers
doubles, flags booleans. A table Arrow is not written with here (an object or a list in a cell, a column mixing text and numbers) comes
as JSON with `200`, as does every call without that `Accept`: read the `Content-Type`. The console's `/api/var/...` and `/bi/api/...`
pass the header on and answer the same way. Why these two and not others, with the measurements: [DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6](DATA_GUIDE.md#6-arrow-to-the-browser-where-it-pays).

```bash
curl -s -H "Authorization: Bearer $T" -H "Accept: application/vnd.apache.arrow.stream" \
  "$B/var/scope/contributions?scope=NODE:MGMT~DESK-RATES&by=trade" -o contrib.arrow
python - <<'EOF'
import json, pyarrow as pa
t = pa.ipc.open_stream(open("contrib.arrow", "rb").read()).read_all()
answer = json.loads(t.schema.metadata[b"drishti.answer"])      # var, es, sumComponent, ... (all but the rows)
print(answer["var"], t.num_rows, t.column_names[:6])
EOF
```

`drishti-console/core/arrow_answer.py` (`decode`) rebuilds the whole JSON answer from such a stream; in a browser page,
`Drishti.table.fromArrow` (`/static/js/drishti_table.js`) does.

### 11.8 Scripting recipes

**Export a strip for a list of trades as CSV.**

```bash
for id in MX-20000001 MX-20000002 MX-20000003; do
  curl -s $B/views/trade/$id | jq -r --arg id "$id" '[$id, (.strip[] | select(.label=="MTM (USD)") | .text)] | @csv'
done
```

```
"MX-20000001","+1,603,277"
"MX-20000002","…"
"MX-20000003","…"
```

For numbers rather than formatted text, read the raw document instead:
`curl -s $B/entities/trade/MX-20000001/raw | jq .data.mtm`.

**The ten largest exposures yesterday.**

```bash
curl -s -G -H "X-Drishti-As-Of: 2026-09-29" $B/search \
  --data-urlencode "q=TRD where mtm > 0 order by mtm desc limit 10" | jq -r '.rows[] | "\(.ref.id)\t\(.values["$.mtm"])"'
```

**Fail a deployment check when anything is unhealthy.**

```bash
status=$(curl -s $B/admin/health | jq -r .status)
[ "$status" = "OK" ] || { echo "Drishti is $status"; exit 1; }
```

**Handle errors by code, not by message.**

```bash
resp=$(curl -s -w '\n%{http_code}' $B/views/trade/MX-29999999)
code=$(echo "$resp" | tail -1); body=$(echo "$resp" | head -n -1)
if [ "$code" != 200 ]; then
  case $(echo "$body" | jq -r .code) in
    DRS-1001) echo "no such trade" ;;
    DRS-1004) echo "source slow; retry later" ;;
    *)        echo "failed: $(echo "$body" | jq -r .detail)" ;;
  esac
fi
```

**Watch one value change live.**

```bash
curl -s -N $B/views/trade/MX-20000001/stream | grep --line-buffered '^data:{"seq"' \
  | sed -u 's/^data://' | jq -r --unbuffered '.patches[] | select(.op=="strip") | .cell | "\(.label) \(.text)"'
```

You should see a line such as `MTM (USD) +1,595,251` each time the value ticks.
