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

This is the handbook for changing Drishti's code. It explains how the repository is laid out, how to build,
run and test it, which rules the build enforces, how a request travels through the code, and then gives
worked recipes for the changes people make most often. Every class, path and command here exists in the
repository; the recipes follow the idioms of the real classes they imitate.

If you only want to run Drishti, read [QUICKSTART.md](QUICKSTART.md) or [GETTING_STARTED.md](GETTING_STARTED.md).
For the design and its reasons, read [ARCHITECTURE.md](ARCHITECTURE.md) and the [decision records](adr/README.md).

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

Drishti has two programs. The **server** is one Spring Boot 3.5 application on OpenJDK 21 (`drishti-server`),
built from a Maven reactor of internal modules. The **console** is a FastAPI and Jinja2 web application in
`console/` that renders the server's JSON. Industries are **packs** (`packs/`), which are content and
configuration, not code.

### 1.1 Java modules

All Java code lives under the package `com.ash.drishti`. The parent POM (`pom.xml`, artifact `drishti-parent`)
inherits from `spring-boot-starter-parent`, which aligns library versions, so there is no separate BOM module.

| Module | Package | What it owns | Key classes |
|---|---|---|---|
| `drishti-api` | `api` | The plugin SPI and data model. **No Spring**, no internal dependencies. | `SourcePlugin`, `PluginManifest`, `SourceCapabilities`, `SourceContext`, `EntityRef`, `EntityDocument`, `Provenance`, `AsOf`, `DataNode`, `HitIndex`, `PluginNotConfigured` |
| `drishti-common` | `common` | Error codes, the exception type, JSON, shape fingerprints, business calendars, branding. | `ErrorCode`, `DrishtiException`, `JsonCodec`, `ShapeFingerprinter`, `BusinessCalendar` |
| `drishti-rachana` | `rachana` | The Rachana grammar: the Sutra model, the Markdown/YAML parser, the registry with hot reload, Rachana-EL, formats and tones. | `SutraRegistry`, `SutraMatcher`, `parse.SutraParser`, `parse.SutraBuilder`, `model.PanelKind`, `el.ElCompiler`, `el.Functions`, `format.Formats` |
| `drishti-inference` | `inference` | Layout inference: semantic hints, rules, the merge of a Sutra with inferred panels. | `InferenceEngine`, `Rules`, `Semantics`, `LayoutMerger`, `EffectiveLayout` |
| `drishti-graph` | `graph` | Entity references: which fields link to which kinds, and link badges. | `ReferenceCatalog`, `BadgeRenderer`, `GraphProperties` |
| `drishti-engine` | `engine` | Sources and routing, the view pipeline, binding, the ViewModel, commands and type-ahead, search, history, impact, business dates, live topics. | `ViewPipeline`, `source.SourceRegistry`, `source.SourceRouter`, `bind.Binder`, `view.ViewModel`, `view.PanelData`, `live.TopicHub`, `live.ViewStream`, `command.CommandParser`, `search.StructuredSearch` |
| `drishti-identity` | `identity` | Users, passwords, roles, lockout, preferences, pack switches and the audit trail, in SQLite or PostgreSQL through JPA. | `UserService`, `JpaUserStore`, `RoleStore`, `PackStateStore`, `JpaAuditLog`, `IdentityDatabase`, `db.IdentityRepositories` |
| `drishti-packs` | `packs` | Loads the enabled packs and turns them into the lowest-precedence property source. | `PackLoader`, `PackLineage`, `PackEnvironmentPostProcessor` (registered in `META-INF/spring.factories`) |
| `drishti-diskcache` | `diskcache` | A disk-backed, size-bounded, self-clearing cache (RocksDB) for live connector state. | `DiskCache` |
| `drishti-messaging` | `messaging` | The latest-state store and live fan-out shared by the ActiveMQ and RabbitMQ connectors. | `MessageStateSource` |
| `drishti-testkit` | `testkit` | Contract tests every source plugin of a kind must pass. | `DatedSourceContract`, `MessageSourceContract`, `BrokerOutage` |
| `drishti-server` | `server` | The Spring Boot application: REST and SSE controllers, security, alerts, Sutra governance. | `DrishtiApplication`, `api.*Controller`, `security.Entitlements`, `security.TokenFilter`, `alerts.AlertEngine`, `governance.SutraGovernance` |
| `drishti-it` | `it` | Repository-wide rules, as tests only. | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest` |
| `drishti-benchmarks` | `benchmarks` | JMH benchmarks of the hot paths (fingerprint, Rachana-EL, formats, cold inference). | `HotPathBenchmark` |

### 1.2 Source plugins

Each plugin is its own module under `plugins/`, depends on `drishti-api` only (plus `drishti-common` and
`drishti-testkit` for tests), and registers itself in `src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin`.
The server depends on all of them, so they are on its class path; each starts only when enabled or used by a
named connector (see [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md)).

| Module | Plugin name | Reads | Extra internal dependency |
|---|---|---|---|
| `drishti-plugin-demo` | `demo` | The enabled packs' `samples/` folders; random-walk ticks | — |
| `drishti-plugin-file` | `file` | `<root>/<kind>/<id>.json` or `.csv`, and dated `<root>/<yyyy-MM-dd>/<kind>/<id>.json` | — |
| `drishti-plugin-rest` | `rest` | An HTTP/JSON service | — |
| `drishti-plugin-jdbc` | `jdbc` | A database by query, or the PostgreSQL entity-table mode | — |
| `drishti-plugin-delta` | `delta` | Delta Lake tables through Delta Kernel (no Spark), with time travel | — |
| `drishti-plugin-aerospike` | `aerospike` | A record per entity per data domain, a bin per business date | — |
| `drishti-plugin-feeds` | (one per feed) | Public feeds: NY Fed SOFR, ECB €STR and FX, US Treasury, FRED | — |
| `drishti-plugin-kafka` | `kafka` | Compacted topics of entity documents | `drishti-diskcache` |
| `drishti-plugin-activemq` | `activemq` | ActiveMQ queues and topics | `drishti-messaging` |
| `drishti-plugin-rabbitmq` | `rabbitmq` | RabbitMQ queues | `drishti-messaging` |
| `drishti-plugin-s3` | `s3` | Documents in Amazon S3 or an S3-compatible store | — |

### 1.3 How the modules depend on each other

Compile-scope dependencies, from the POMs:

```text
drishti-api                       (nothing internal; no Spring)
└── drishti-common
    ├── drishti-rachana
    │   ├── drishti-inference ──┐
    │   └── drishti-graph ──────┴── drishti-engine
    ├── drishti-identity
    ├── drishti-packs
    └── drishti-testkit            (api, common)
drishti-diskcache                 (nothing internal)
drishti-messaging                 (api, diskcache)
plugins/*                         (api; kafka adds diskcache; activemq and rabbitmq add messaging)
drishti-server                    (engine, identity, packs, and all eleven plugins)
drishti-it                        (server, testkit)
drishti-benchmarks                (inference)
```

`ArchitectureRulesTest` keeps these one-way (see [§3.3](#33-architecture-rules)): nothing below the engine may see
`engine` or `server`, the engine may not see `server`, and `api` sees nothing internal.

### 1.4 Everything else

| Folder | What is in it |
|---|---|
| `console/` | The web console: `run_drishti_web.py` (entry point), `core/` (app factory, backend client, auth, business date, packs, config loader), `routes/` (one router per area), `web/templates/` (Jinja2, with `_macros/panels.html` holding one macro per panel kind), `web/static/` (`js/`, `css/`, `img/`, and third-party code under `vendor/`), `web/guides/` (in-app guides), `config/` (`application.yaml`, `help.yaml`, `workspaces.yaml`, `competitive.yaml`), `tests/` (pytest, with a fake backend in `conftest.py`) |
| `packs/<name>/` | One domain pack: `pack.yaml`, `sutras/`, `samples/`, `config/` (formats, semantics, help, workspaces), `guides/`. Fourteen ship. |
| `tools/packgen/` | Pack generators: `banking/` (five banking packs from one taxonomy), `common/packbuild.py` (the shared builder), and one `make.py` per other generated pack (`climate`, `economics`, `genomics`, `liquidity`, `oprisk`, `politics`, `retail`) |
| `tools/samplegen/` | Sample-history helpers: `lake.py` (Delta Lake writer), `pgload.py` (PostgreSQL loader), `stream.py` (Kafka ticker), plus `test_samplegen.py` |
| `tools/lake/` | `maintain.py`: Delta Lake retention, compaction, checkpoints and vacuum; `test_maintain.py` |
| `tools/` (files) | `drill.sh` (verify and publish), `license_headers.py` (check or insert the copyright header), `sutra_to_md.py` (YAML Sutra to Markdown Sutra), `load-aerospike.sh` |
| `deploy/` | `server.Dockerfile`, `console.Dockerfile`, `compose.yaml`, `compose.data.yaml`, `lake-maintenance.yaml`, `grafana/` |
| `config/license-header.txt` | The text of the copyright header that `license_headers.py` inserts |
| `data/` | Runtime data, mostly git-ignored: `delta/` (sample lake), `identity/` (the SQLite database), `governance/` (Sutra proposals), `feeds/` |
| `docs/` | These documents, the ADRs under `adr/`, the runbooks under `runbooks/`, and the reference mockups under `requirements/` |
| `.github/workflows/fast.yml` | CI: `./mvnw -B -q verify` and the header check on Java 21; `pytest` for the console on Python 3.13 |

---

## 2. Building and running from source

### 2.1 Prerequisites

| Tool | Version | Notes |
|---|---|---|
| OpenJDK | 21 | The enforcer rule in `pom.xml` (`requireJavaVersion [21,22)`) stops any other version with `Drishti builds and runs on OpenJDK 21.` `.java-version` says `21`. |
| Python | 3.11 or newer | The console and the tools. CI uses 3.13. |
| uv | any recent | Creates the console's environment, and runs the lake tools with `uv run --with …` so nothing is installed globally. |
| Docker | optional | Only for the Testcontainers tests (PostgreSQL, Aerospike, ActiveMQ, RabbitMQ, MinIO) and `deploy/compose.yaml`. |

Set `JAVA_HOME` in every terminal:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v
```

You should see `Java version: 21…` in the output. Maven itself comes with the repository (`./mvnw`).

### 2.2 Maven commands

Run them from the repository root.

| You want to… | Command |
|---|---|
| Build everything, no tests | `./mvnw -q package -DskipTests` |
| Build and run every test and rule | `./mvnw -q verify` |
| The same, without touching the network (what the drill runs) | `./mvnw -q -o verify` |
| Build one module and what it needs | `./mvnw -q -pl drishti-engine -am package -DskipTests` |
| Run one module's tests | `./mvnw -q -o -pl drishti-rachana -am test` |
| Run one test class | `./mvnw -q -o -pl drishti-server -am test -Dtest=ViewPipelineTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Run one test method | `./mvnw -q -o -pl drishti-rachana -am test -Dtest='ElTest#pathsOperatorsAndFunctions' -Dsurefire.failIfNoSpecifiedTests=false` |
| Run only the repository rules | `./mvnw -q -o -pl drishti-it -am verify -Dtest='LicenseHeaderTest,SourceFileSizeTest,ArchitectureRulesTest' -Dsurefire.failIfNoSpecifiedTests=false` |

What the flags mean:

- `-q` quiet: a successful build prints little or nothing.
- `-o` offline: use only what is already in `~/.m2`. It fails on a fresh machine; run once without `-o` first.
- `-pl <module>` build only that module; `-am` ("also make") builds the modules it depends on first. Without
  `-am`, Maven takes the upstream modules from `~/.m2`, which may be stale.
- `-DskipTests` compiles tests but does not run them.
- `-Dtest=…` selects tests; `-Dsurefire.failIfNoSpecifiedTests=false` stops the upstream modules that `-am`
  pulls in from failing because they hold no such test.

The server jar is `drishti-server/target/drishti-server-<version>-exec.jar` (the `exec` classifier is set on the
`spring-boot-maven-plugin` in `drishti-server/pom.xml`). The plain `drishti-server-<version>.jar` beside it is the
library jar, not the application.

> **Note:** tests read the same `${ENV:default}` placeholders as the running server. Run Maven in a shell
> where `DRISHTI_PACKS`, `DRISHTI_SECURITY_ENABLED` and the other `DRISHTI_*` variables are **not** set, or tests
> that rely on the defaults (for example `ApiTest`, which expects the `finance` pack's `IRS-48213`) fail.

### 2.3 Tests that need Docker

Tests annotated `@Testcontainers(disabledWithoutDocker = true)` start real services in Docker and are **skipped**
(not failed) when Docker is not reachable:

| Test | Starts |
|---|---|
| `drishti-identity` `PostgresIdentityStoreTest` | PostgreSQL 18 |
| `drishti-plugin-jdbc` `PostgresEntityTableTest`, `PostgresReconnectTest` | PostgreSQL |
| `drishti-plugin-aerospike` `AerospikeSourcePluginTest` | Aerospike Community Edition |
| `drishti-plugin-activemq` `ActiveMqSourcePluginTest`, `ActiveMqOutageTest` | ActiveMQ |
| `drishti-plugin-rabbitmq` `RabbitMqSourcePluginTest`, `RabbitMqOutageTest` | RabbitMQ |
| `drishti-plugin-delta` `DeltaOnS3Test` | an S3-compatible store |

`DeltaAfterMaintenanceTest` instead assumes `uv` is installed (it runs `tools/lake/maintain.py`) and is skipped
otherwise. The Kafka tests use an embedded broker from `spring-kafka-test` and need no Docker.

A green build on a machine without Docker has therefore not run the PostgreSQL schema check. Run the identity
tests on a machine with Docker before you merge a schema change ([§5.6](#56-add-a-table-to-the-identity-database)).

### 2.4 The console from source

```bash
uv venv console/.venv
uv pip install --python console/.venv/bin/python -r console/requirements.txt
console/.venv/bin/python console/run_drishti_web.py
```

You should see `Uvicorn running on http://127.0.0.1:17480`. `console/requirements.txt` holds FastAPI, Uvicorn,
Jinja2, httpx, PyYAML, Markdown and pytest. Without uv, `python3 -m venv console/.venv` and
`console/.venv/bin/pip install -r console/requirements.txt` do the same.

The console reads `console/config/application.yaml`, then `console/config/application.local.yaml` if present
(git-ignored), then environment variables, then `--key=value` arguments:

```bash
# three ways to move the console to port 17481
DRISHTI_CONSOLE_PORT=17481 console/.venv/bin/python console/run_drishti_web.py
DRISHTI_CONSOLE__SERVER__PORT=17481 console/.venv/bin/python console/run_drishti_web.py
console/.venv/bin/python console/run_drishti_web.py --server.port=17481
```

`DRISHTI_CONSOLE__A__B` sets any key `a.b` (the prefix is `DRISHTI_CONSOLE__`, double underscores separate the
levels). Named variables such as `DRISHTI_CONSOLE_PORT` and `DRISHTI_BACKEND_URL` exist only where the YAML says
`${NAME:default}`.

### 2.5 Running the server from source

Build the jar, then start it from the repository root (the server finds `./packs` and `./data` relative to the
working directory):

```bash
./mvnw -q package -DskipTests
DRISHTI_PACKS=market-risk,counterparty-risk DRISHTI_STUDIO_SAVE=true \
  java -jar drishti-server/target/drishti-server-1.10.2-exec.jar
```

Server settings follow the same precedence as the console: `drishti-server/src/main/resources/application.yaml`,
then `./application.local.yaml` (git-ignored; imported by `spring.config.import`), then environment variables,
then `--key=value` arguments. For example `--drishti.live.frame=100ms` or `--server.port=18481`.

The development loop that works best:

| You changed | Do |
|---|---|
| A Sutra under `packs/*/sutras/` or a site Sutra directory | Nothing: `drishti.rachana.hot-reload` reloads it; an invalid edit keeps the last good version |
| Java code | Rebuild the module and the server jar (`./mvnw -q -o -pl drishti-server -am package -DskipTests`), restart the server |
| `pack.yaml` | Restart the server (packs become properties at start-up) |
| A console template | Reload the page |
| Console Python, or anything under `console/web/static/` | Restart the console (the asset fingerprint is computed at start-up; see [§8](#8-common-pitfalls)) |

### 2.6 The drill: `tools/drill.sh`

"Drill it" is how work reaches `main`. The script verifies everything, and only then publishes:

```bash
tools/drill.sh
```

What it does, in order (it stops at the first failure, `set -euo pipefail`):

1. Refuses to run unless the current branch is `develop` (`drill: must be on develop`) and the working tree is
   clean (`drill: commit your changes first`).
2. `python3 tools/license_headers.py`: every file carries the copyright header.
3. Every generator that supports `--check` (`packs/*/tools/make_*.py` and `tools/packgen/*/make*.py` containing
   the string `--check`) runs with `--check`: generated pack content must match what the generator would write now.
4. `python3 -m unittest -q tools/samplegen/test_samplegen.py`.
5. If `uv` is installed: the lake-maintenance tests (`tools/lake/test_maintain.py`) with `deltalake`, `pyarrow` and
   `pyyaml` supplied by `uv run --with`.
6. `./mvnw -q -o verify`: the whole Java build, every test and every rule, offline.
7. `console/.venv/bin/python -m pytest -q console/tests`.
8. Pushes `develop`, fast-forwards `main` to `develop` (`git merge --ff-only`), pushes `main`, and returns to
   `develop`. It prints `drilled: <the last commit>`.

`JAVA_HOME` defaults to `/usr/lib/jvm/java-21-openjdk-amd64` inside the script when it is not set.

---

## 3. Project rules

Some rules are enforced by tests, so a plain `./mvnw verify` fails when they are broken. Others are
conventions that reviews hold to. Both kinds are listed here.

| Rule | Enforced by |
|---|---|
| Every source file carries the copyright header | `LicenseHeaderTest`, `tools/license_headers.py` (drill, CI) |
| No non-UI source file over 1500 lines | `SourceFileSizeTest`; `console/tests/test_assets_policy.py` for console Python |
| One-way module dependencies, a Spring-free SPI, controllers only in `server`, no field injection, no `Serializable` | `ArchitectureRulesTest` |
| Front end vendored: no CDN, no external URL, no inline script, handler or style | `console/tests/test_assets_policy.py`, the console's Content Security Policy |
| Generated files match their generators | `--check` in the drill |
| Sutras shown in docs and pack guides are valid | `DocumentedSutrasTest` |
| Every pack Sutra loads without problems | `PackSutrasTest` |
| Warm views stay fast | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99` |
| Object-oriented, concurrent, configuration-driven code; virtual threads; no SQL in code; schema files per database; git author only | Review (this section) |

### 3.1 The copyright header

Every file whose suffix is `.java`, `.py`, `.js`, `.css`, `.html`, `.yaml`, `.yml`, `.xml`, `.md`, `.properties` or
`.sh` must contain `Copyright (c) 2026 Ashutosh Sinha` within its first 2000 characters. Build output, `.git`,
`.venv`, `vendor`, `recorded`, `__pycache__`, `.mvn`, `.idea`, `.pytest_cache` and `docs/requirements` are skipped,
as are `mvnw`, `mvnw.cmd` and `LICENSE`. JSON files cannot hold comments and are exempt. SQL files carry the
header in `--` comments by convention.

Check, then insert what is missing:

```bash
python3 tools/license_headers.py          # lists "missing <file>" and exits 1 if any file lacks it
python3 tools/license_headers.py --fix    # inserts the header and prints "fixed <file>"
```

The header text is `config/license-header.txt`, written in the comment style of each suffix: `/* … */` for Java,
JavaScript and CSS; `#` for Python, YAML, properties and shell (after a `#!` line if there is one); `<!-- … -->` for
XML and Markdown (after an `<?xml` line); `{# … #}` for Jinja templates (`.html`). A Markdown document therefore
starts like this one.

### 3.2 File size

`SourceFileSizeTest` fails when any `.java`, `.py`, `.yaml`, `.yml` or `.xml` file outside `console/web/` has more
than 1500 lines (`MAX_LINES = 1500`). UI templates, styles and scripts under `console/web/` are exempt. When a class
approaches the limit, split it by responsibility (the banking generator, for example, is a dozen focused modules
under `tools/packgen/banking/`).

### Error Prone and Spotless

- **Error Prone** checks every compile. A finding at error level stops the build with the check's name, for
  example `[ReturnValueIgnored] Return value of 'apply' must be used`. Fix the code. If the finding is intended,
  add `@SuppressWarnings("ReturnValueIgnored")` on the smallest element, with a comment saying why. Warnings
  (missing `@Override`, Javadoc summaries) do not fail the build but are worth fixing when you touch the file.
- **Spotless** runs at `verify`. On a failure it prints the diff; `./mvnw spotless:apply` fixes unused imports,
  trailing whitespace, missing final newlines and tabs.

### 3.3 Architecture rules

`drishti-it/src/test/java/com/ash/drishti/it/ArchitectureRulesTest.java` checks the compiled main code (tests are
excluded) with ArchUnit:

| Rule | Meaning |
|---|---|
| `apiIsSpringFree` | Nothing in `com.ash.drishti.api..` may use `org.springframework..`. A plugin must not need Spring. |
| `apiDependsOnNothingInternal` | `api` may not use `common`, `rachana`, `engine` or `server`. |
| `lowerLayersDoNotSeeEngine` | `common`, `rachana`, `inference`, `graph` and `identity` may not use `engine` or `server`. |
| `engineDoesNotSeeServer` | `engine` may not use `server`. |
| `controllersOnlyInServer` | `@RestController` classes live only in `com.ash.drishti.server..`. |
| `noFieldInjection` | No field is annotated `@Autowired`. Use constructor injection. |
| `noSerializable` | No class implements `java.io.Serializable`, except enums, throwables and JPA `@Embeddable` keys (JPA requires composite keys to be serialisable). |

Tests may use `@Autowired` fields (`@Autowired MockMvc mvc;`), because test classes are not analysed.

### 3.4 Object-oriented, concurrent and configuration-driven

- **Beans per module.** Each module contributes its beans from its own `@Configuration` class (`EngineConfiguration`,
  `RachanaConfiguration`, `IdentityConfiguration`, `SecurityConfiguration`, …). `DrishtiApplication` imports them.
  Domain classes take their collaborators in the constructor and are plain Java, so they unit-test without Spring
  (ADR-001).
- **Configuration, not constants.** Every limit, timeout, path and switch is a property under `drishti.*`, bound to
  an immutable `@ConfigurationProperties` record whose compact constructor supplies the defaults
  (`LiveProperties`, `SearchProperties`, `GovernanceProperties`). Industry vocabulary belongs in packs, never in
  core code (ADR-010). The product name and legal notices are configuration too (`drishti.branding.*` on the
  server, `ui.*` in the console).
- **Virtual threads.** `spring.threads.virtual.enabled: true` runs every request on a virtual thread. Blocking I/O
  (a source read, a link fan-out) is fine and cheap. `EngineConfiguration` provides `drishtiVirtualExecutor`
  (a thread per task) for fetches and SSE writers, `drishtiBindPool` (a `ForkJoinPool`, `drishti.engine.bind-parallelism`,
  0 = one per core) for CPU-bound panel binding, and `drishtiFrameScheduler` (two platform threads) for live frames.
- **Do not hold a monitor across I/O.** Guard I/O with a `ReentrantLock`, not `synchronized`, so a blocked virtual
  thread releases its carrier. The code says so where it matters, for example in `TopicHub`
  (`a ReentrantLock: the upstream subscribe may do I/O, and synchronized would pin`) and `FileAuditLog`.
- **Thread safety is the default expectation.** `SourcePlugin` implementations are called concurrently from many
  virtual threads. Shared state is either immutable and swapped whole (`HitIndex` keeps an immutable list in an
  `AtomicReference`; `PackStateStore` keeps an immutable snapshot map that readers use lock-free), or guarded by a
  lock for writers only. Each class says in its Javadoc whether it is thread-safe. Concurrency has its own tests
  (`HitIndexConcurrencyTest`, `UserServiceConcurrencyTest`, `TopicHubTest`, `LiveStreamSlotsTest`).
- **One exception type with a stable code.** Code that crosses a module boundary throws `DrishtiException(ErrorCode, message)`.
  `ApiExceptionHandler` turns it into RFC 7807 `problem+json` with `code: "DRS-nnnn"`. Codes are grouped by first digit
  (1 sources and data, 2 Sutra, 3 inference, 4 engine and graph, 5 API, 6 identity) and are never reused.
- **Imperfect data never fails a view.** A panel whose data does not fit is returned empty ("No data available"),
  never an exception (`Binder.bind`, `Emptiness`, `ImperfectDataTest`).
- **The Rachana-EL is closed.** Functions must be pure, total (never throw on odd input) and cheap; adding one is an
  amendment to ADR-003.

### 3.5 Data access: no SQL in code

The identity database (`drishti-identity`) is reached only through Spring Data JPA:

- Repositories are interfaces nested in `db.IdentityRepositories` and use **derived queries only**
  (`findBySubjectOrActorOrderByIdDesc`), never `@Query` strings or JDBC.
- The schema is two files, `drishti-identity/src/main/resources/db/schema-sqlite.sql` and `db/schema-postgres.sql`.
  `IdentityDatabase` applies the one for the configured URL at every start. Every statement is idempotent
  (`CREATE TABLE IF NOT EXISTS`), so the file *is* the schema: there are **no migrations**.
- Hibernate never changes the schema. On PostgreSQL it **validates** the entities against it
  (`hibernate.hbm2ddl.auto: validate`), so a mismatch stops start-up. SQLite types columns by value, so the check
  cannot apply there; the identity contract tests round-trip every column on both databases instead.

### 3.6 The front end is vendored

The console uses Bootstrap, Bootstrap Icons, ECharts (and ECharts GL) and CodeMirror, copied under
`console/web/static/vendor/`. There is no npm, no build step and no CDN (ADR-006). The Content Security Policy in
`console/core/app.py` is:

```text
default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self';
connect-src 'self'; frame-src 'self'; frame-ancestors 'self'
```

So templates may not contain inline `<script>` blocks, `on…=` handlers or `style=` attributes, and nothing may load
from another origin. `console/tests/test_assets_policy.py` checks all of this. Behaviour goes in a file under
`web/static/js/`, wired by `data-*` attributes; widths and colours come from classes and `data-*` values that a
script applies (bars and gauges use `data-w`, see `view.js`).

### 3.7 Git

- Work happens on `develop`; `main` only moves by fast-forward through the drill.
- Commits are authored as the repository owner (`ajsinha`). Do not add co-author or tool-attribution trailers.
- Commit messages say what changed for a user or an operator, in plain sentences (see `git log`).

---

## 4. How a request travels through the code

This section follows `TRD T-10001 <GO>` from the keyboard to the screen, then the live updates that follow.

### 4.1 From the command line to a view

| Step | Where | What happens |
|---|---|---|
| 1 | `console/web/templates/terminal/_topbar.html`, `web/static/js/command.js` | The command line is a plain form (`action="/go"`). `command.js` adds the dropdown: it calls the console's `GET /api/suggest`, which calls the server's `GET /api/v1/command/suggest` (`CommandController.suggest` → `SuggestionService`), filtered by `Entitlements.filter`. |
| 2 | `console/routes/terminal_routes.py` `go()` | Text that looks like a search (`<MN> where …`, `order by`, `limit`) is redirected to `/s`. Otherwise `BackendClient.command()` posts `{"text": "TRD T-10001"}` to `POST /api/v1/command`. |
| 3 | `console/core/backend.py` `BackendClient._send` | Every call carries the caller (`X-Drishti-User`, and `Authorization: Bearer …` when sign-in is on) and the business date (`X-Drishti-As-Of`, from `core/asof.py`, which `AuthGate` in `core/app.py` sets from the `drishti_asof` cookie). An unreachable server becomes `BackendError(503, "DRS-5003", …)`. |
| 4 | `server.security.TokenFilter` | For every `/api/v1/**` request: with security off, the caller is `Principal.anonymous(X-Drishti-User)` with every role; with it on, the bearer token is verified (`TokenVerifier`) or the answer is `401 DRS-5010`. The principal is a request attribute (`Principal.ATTRIBUTE`). |
| 5 | `server.api.AsOfResolver` | Gives any controller parameter of type `AsOf` the request's business date: `X-Drishti-As-Of`, else `?asOf=`, else the current business date (`BusinessDates.parse`). |
| 6 | `server.api.CommandController.command` | `CommandParser.parse` turns the text into an `EntityRef` (`trade/T-10001`). `Entitlements.requireOpen` checks the caller's roles and active packs (`403 DRS-5002`). If the entity exists (`SourceRouter.fetchAll`), the answer is its ref; otherwise the text becomes a pick list (`SearchQuery.pick` + `StructuredSearch.run`), and exactly one match opens directly. An unreadable command is `400 DRS-4001`. |
| 7 | `terminal_routes.go()` | Redirects (303) to `/v/trade/T-10001`, or to `/s?q=…` for a pick list. |
| 8 | `terminal_routes.view()` | `BackendClient.view()` calls `GET /api/v1/views/trade/T-10001`. |
| 9 | `server.api.ViewController.view` | `requireOpen` again, then `ViewPipeline.view(ref, asOf)` (timed as the `drishti.view` metric), then `Entitlements.restrict` (disables links the caller may not follow), then `RecentEntities.touch` (feeds "recent" in the dropdown). |
| 10 | `engine.ViewPipeline` | The pipeline (below) returns a `ViewModel`. |
| 11 | `terminal_routes.view()` | Splits the panels into `main` and `right` by `area`, renders `terminal/view.html`. Each panel is drawn by the `panel(p)` macro in `web/templates/_macros/panels.html`; `web/static/js/view.js` adds charts (vendored ECharts), tabs, F-keys and the raw-JSON drawer. |

### 4.2 Inside `ViewPipeline`

`ViewPipeline` (`drishti-engine`) documents itself as "command → fetch → classify → fingerprint → layout → link →
bind". In code:

1. **Fetch.** `SourceRouter.fetch(ref, asOf)` picks the plugins that serve the kind (`drishti.sources.routes`,
   named connectors, `default-route`), tries dated sources first for a picked date, and gives up after
   `drishti.sources.fetch-timeout` (2s). No source holding it is `404 DRS-1001`.
2. **Match a Sutra.** `SutraMatcher.match(kind, data)` asks `SutraRegistry` for the highest-priority Sutra of the
   kind whose `match.where` expression holds for this document. None means inference alone.
3. **Fingerprint the shape.** `ShapeFingerprinter` hashes the document's structure (not its values). It is cached per
   (entity, generation, business date).
4. **Lay out.** `LayoutMerger.merge(sutra, data, kind)` merges the Sutra with inferred panels into an
   `EffectiveLayout`. Layouts are cached in Caffeine per (Sutra *object identity*, kind, fingerprint), so the cost of
   inference is paid once per shape, and a reloaded Sutra can never be served an older layout. A registry change
   clears the cache.
5. **Link.** `ReferenceCatalog.discover` finds fields that reference other entities (from the packs'
   `graph.fields` and `graph.id-patterns`), and line charts may name a `source` entity. All are fetched in parallel
   by `SourceRouter.fetchAll` within `drishti.graph.link-budget` (40ms); late ones show as pending.
6. **Bind.** Each panel is bound by `Binder.bind(panel, BindContext)` in parallel on `drishtiBindPool`. Expressions
   are compiled once by `ElCompiler` (Rachana-EL) and values formatted by `Formats` and toned by `Tones`. The strip,
   title and F-keys are bound the same way.
7. **Return** a `ViewModel(ref, mnemonic, title, strip, panels, keys, provenance, timings)`. `provenance` carries the
   layout label (`Sutra irs-fixfloat v1 + inference`), the fingerprint, source, generation, `live` and business
   date; `timings` has `fetch`, `layout`, `links`, `bind` and `total` in milliseconds.

### 4.3 Live updates

| Step | Where | What happens |
|---|---|---|
| 1 | `terminal/view.html` | `live.js` is loaded only when `vm.provenance.live` is true. A picked business date is a static snapshot: no stream. |
| 2 | `web/static/js/live.js`, `web/static/js/channel.js` | `live.js` subscribes `view:trade/T-10001` on `window.DrishtiChannel`. `channel.js` keeps **one** `EventSource` per browser tab, to the console's `GET /api/channel?s=…`, and adds or removes subscriptions with `POST /api/channel/{cid}` instead of reconnecting. Workspace panes (iframes) share their parent's channel. Browsers allow six HTTP/1.1 connections per site; a stream per view and per bell used to exhaust them and freeze the page. |
| 3 | `console/routes/api_routes.py` `channel()` | For each subscription it opens an upstream stream (`BackendClient.stream` → `GET /api/v1/views/{kind}/{id}/stream`; `alerts` and `monitor:<name>` go to their own server streams), in **detached** tasks, and multiplexes everything into one SSE response as `{"ch": "<subscription>", "d": …}`. Frames are rewritten by `_view_event`: each patched panel is rendered to HTML with the same `panels.html` macro as first paint (charts stay data). A watchdog ends a channel nobody has read for five seconds; a comment every ~15s keeps proxies from closing it. |
| 4 | `server.api.StreamController.stream` | Takes a slot from `LiveStreamSlots` (cap `drishti.live.max-streams`), builds the initial view, and creates a `ViewStream`. A writer on a virtual thread sends a `view` event, then a `frame` event per frame, or a heartbeat comment (`drishti.live.heartbeat`, 15s). Each client has its own latest-wins `FrameMailbox`, so a slow client never slows others. |
| 5 | `engine.live.TopicHub` | One topic per live entity, shared by every view of it, holding a single source subscription (`SourceRouter.subscribe` → the plugin's `subscribe`). Ticks land in a latest-wins slot and are delivered at most once per frame (`drishti.live.frame`, 50ms). The subscription closes with the last listener. |
| 6 | `engine.live.ViewStream`, `PatchDiffer` | On a tick, the view is rebuilt (`ViewPipeline.build`) and `PatchDiffer.diff(before, after)` produces `Patch`es; they travel as a `Frame`. |
| 7 | `live.js` | Applies the patches in place: strip cells get new text and tone, panels are swapped for the server-rendered HTML, charts move to new data, and changed values flash. |

[LIVE.md](LIVE.md) has the details: frames, coalescing, reconnects and the latency metrics.

---

## 5. Recipes

Each recipe lists every file you touch. New files need the copyright header
(`python3 tools/license_headers.py --fix` inserts it); the snippets below leave it out to save space.

### 5.1 Add a REST endpoint

**Goal:** `GET /api/v1/mnemonics` lists the mnemonics the caller may use; `GET /api/v1/mnemonics/{code}` gives one,
or `404 DRS-4005`.

**1. Add the error code** to `drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java`, in its group
(4 = engine and graph), with the next free number. Never reuse a code.

```java
    BAD_SEARCH("DRS-4004", 400),
    MNEMONIC_NOT_FOUND("DRS-4005", 404),
```

**2. Write the controller** in `drishti-server/src/main/java/com/ash/drishti/server/api/MnemonicController.java`.
Controllers live in `server.api` (an ArchUnit rule), take their collaborators in the constructor, read the caller
from the request attribute, and check entitlements before doing anything:

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
  `mayOpen`/`filter`/`redact`/`restrict` to trim answers. They throw `DrishtiException(FORBIDDEN, …)`, which
  becomes `403 DRS-5002`.
- Add a parameter of type `AsOf` when the answer depends on the business date; `AsOfResolver` fills it.
- Throw `DrishtiException`; never build error responses by hand. `ApiExceptionHandler` writes the
  `problem+json` with the code, and logs 5xx errors.
- Admin actions that change something are audited: `users.recordAudit(p.user(), "cache-purged", name, detail)`
  in `CacheController` is the pattern.

**3. Test it with MockMvc** in `drishti-server/src/test/java/com/ash/drishti/server/MnemonicApiTest.java`. With
security off (the default), pass the user in `X-Drishti-User`. To test refusals, turn security on and mint a
token with `TokenVerifier.mint(user, roles, ttlSeconds)`, as `CacheAdminTest` does:

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
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class MnemonicApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void listsResolvesAndRefuses() throws Exception {
        String risk = "Bearer " + tokens.mint("rita", List.of("risk"), 300);
        mvc.perform(get("/api/v1/mnemonics").header("Authorization", risk)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='NSET')].kind").value(hasItem("netting-set")));
        mvc.perform(get("/api/v1/mnemonics/nset").header("Authorization", risk))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("NSET"));
        mvc.perform(get("/api/v1/mnemonics/NOPE").header("Authorization", risk))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4005"));

        String trader = "Bearer " + tokens.mint("tom", List.of("trader"), 300);   // the finance pack's trader role cannot open netting sets
        mvc.perform(get("/api/v1/mnemonics/NSET").header("Authorization", trader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/mnemonics")).andExpect(status().isUnauthorized());   // no token
    }
}
```

**4. Document it.** Add the endpoint to [API_GUIDE.md](API_GUIDE.md) and the new code to its error-code table, and
to the code table in [TROUBLESHOOTING.md](TROUBLESHOOTING.md). The OpenAPI document at `/api/docs` picks the
endpoint up by itself.

**5. Call it from the console** (if a page needs it): add a method to `BackendClient` in `console/core/backend.py`,
following its pattern (`return await self._get("/mnemonics", ident)`), and a matching method to `FakeBackend`
in `console/tests/conftest.py`.

### 5.2 Add a source plugin

**Goal:** a plugin `ndjson` that serves `<root>/<kind>.ndjson`, one JSON document per line, each with an `id`
field. Read [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) for the full SPI; this recipe shows the shape of a new module.

**1. Create the module** `plugins/drishti-plugin-ndjson/pom.xml`. Depend on `drishti-api` only; the other
internal modules are for tests:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.ash.drishti</groupId>
    <artifactId>drishti-parent</artifactId>
    <version>1.10.2</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>drishti-plugin-ndjson</artifactId>
  <name>drishti-plugin-ndjson</name>
  <description>Newline-delimited JSON files, one per kind</description>
  <dependencies>
    <dependency><groupId>com.ash.drishti</groupId><artifactId>drishti-api</artifactId></dependency>
    <dependency><groupId>com.ash.drishti</groupId><artifactId>drishti-common</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.ash.drishti</groupId><artifactId>drishti-testkit</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

Then register it in three places:

| File | Add |
|---|---|
| `pom.xml` (parent) | `<module>plugins/drishti-plugin-ndjson</module>` before `drishti-server`, and a `<dependency>` with `<version>${project.version}</version>` in `<dependencyManagement>` |
| `drishti-server/pom.xml` | `<dependency><groupId>com.ash.drishti</groupId><artifactId>drishti-plugin-ndjson</artifactId></dependency>` |
| `plugins/drishti-plugin-ndjson/src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin` | one line: `com.ash.drishti.plugin.ndjson.NdjsonSourcePlugin` |

A plugin can instead ship as a separate jar in `drishti.sources.plugin-dir` (`DRISHTI_PLUGIN_DIR`); each jar there
gets its own class loader (`PluginDiscovery`).

**2. Implement `SourcePlugin`** in `src/main/java/com/ash/drishti/plugin/ndjson/NdjsonSourcePlugin.java` (and a
`package-info.java` with a one-line Javadoc, like the other plugins):

```java
package com.ash.drishti.plugin.ndjson;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Serves {@code <root>/<kind>.ndjson}: one JSON document per line, identified by its {@code id} field. The files are
 * read into memory and re-read every {@code reload-seconds}; the generation is the file's modification time.
 * Thread-safe: readers see one complete snapshot, swapped whole after each reload.
 *
 * <p>Settings: {@code root} (required), {@code source-name} (default {@code ndjson}), {@code reload-seconds} (default 60).
 */
public final class NdjsonSourcePlugin implements SourcePlugin {

    private final HitIndex index = new HitIndex();
    private volatile Map<EntityRef, EntityDocument> documents = Map.of();
    private SourceContext context;
    private Path root;
    private String sourceName;

    @Override
    public PluginManifest manifest() {
        // any kind; not live, no reverse lookup, answers search, undated
        return new PluginManifest("ndjson", "1.0", Set.of(), new SourceCapabilities(false, false, true));
    }

    @Override
    public void start(SourceContext ctx) {
        String dir = ctx.setting("root", "");
        if (dir.isBlank()) {
            // installed but not configured: the server leaves it idle instead of reporting a failure
            throw new PluginNotConfigured("no root directory (drishti.sources.plugins.ndjson.settings.root)");
        }
        this.context = ctx;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.sourceName = ctx.setting("source-name", "ndjson");
        long every = Long.parseLong(ctx.setting("reload-seconds", "60"));
        reload();
        ctx.scheduler().scheduleWithFixedDelay(this::reload, every, every, TimeUnit.SECONDS);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return Optional.ofNullable(documents.get(ref));
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public String health() {
        return Files.isDirectory(root) ? "UP" : "DOWN: no directory " + root;
    }

    void reload() {
        Map<EntityRef, EntityDocument> fresh = new HashMap<>();
        List<EntityHit> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".ndjson")).toList()) {
                String name = f.getFileName().toString();
                String kind = name.substring(0, name.length() - ".ndjson".length());
                long generation = Files.getLastModifiedTime(f).toMillis();
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    DataNode data = context.parseJson(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
                    String id = data.get("id").asText();
                    if (id.isEmpty()) {
                        continue;                                   // a line without an id cannot be addressed
                    }
                    EntityRef ref = EntityRef.of(kind, id);
                    fresh.put(ref, new EntityDocument(ref, data, new Provenance(sourceName, generation, Instant.now(), false)));
                    hits.add(new EntityHit(ref, id, kind + " · " + sourceName));
                }
            }
        } catch (IOException e) {
            return;                                                 // keep the last good snapshot; health() says why
        }
        documents = Map.copyOf(fresh);
        index.replaceAll(hits);
    }
}
```

What to take from this:

- **Manifest.** `PluginManifest(name, version, kinds, capabilities)`; an empty `kinds` set means "any kind".
  `SourceCapabilities(live, reverseLookup, search, dated)` says which optional methods you implement:
  `subscribe` for live, `reverse` for reverse lookup, `search` for the dropdown, and the `AsOf` overloads of
  `fetch`/`reverse`/`search` for dated data (stamp `Provenance.businessDate` on what you return).
- **Settings** arrive through `SourceContext.settings()` from `drishti.sources.plugins.<name>.settings.*`, or from
  a named connector's `settings` (then `source-name` defaults to the connector's name).
- **`PluginNotConfigured`.** A plugin on the class path starts unless it is disabled
  (`drishti.sources.plugins.<name>.enabled: false`). Throw `PluginNotConfigured` from `start` when the settings it
  needs are missing; `SourceRegistry` then logs `installed but not configured … it stays idle` instead of a
  failure.
- **Search must be fast**: an in-memory index, never a remote scan. `HitIndex` ranks identifier prefix, then
  identifier substring, then title/subtitle; `replaceAll` swaps the whole index at once.
- **Use the engine's services**: `context.parseJson` instead of bundling a JSON library, and `context.scheduler()`
  for periodic work (it runs on virtual threads, so a blocking reload does not delay others).
- **Implement `cacheStats()`/`purgeCaches()`** if you cache, so *Admin → Caches* can show and clear it.

**3. Test it.** A unit test with a temporary directory, as `FileSourcePluginTest` does. `DatedSourceContract.context(settings)`
from `drishti-testkit` builds a `SourceContext` for you:

```java
package com.ash.drishti.plugin.ndjson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NdjsonSourcePluginTest {

    @TempDir
    Path root;

    @Test
    void readsOneDocumentPerLineAndSearchesIds() throws Exception {
        Files.writeString(root.resolve("trade.ndjson"), "{\"id\":\"T-1\",\"mtm\":5}\n\n{\"id\":\"T-2\",\"mtm\":-3}\n{\"mtm\":9}\n");
        NdjsonSourcePlugin p = new NdjsonSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "source-name", "eod-file")));
        var doc = p.fetch(EntityRef.of("trade", "T-1")).orElseThrow();
        assertThat(doc.data().get("mtm").asDouble()).isEqualTo(5);
        assertThat(doc.provenance().source()).isEqualTo("eod-file");
        assertThat(p.fetch(EntityRef.of("trade", "T-9"))).isEmpty();
        assertThat(p.search("trade", "t-", 10)).hasSize(2);
        assertThat(p.health()).isEqualTo("UP");
    }

    @Test
    void withoutARootItStaysIdle() {
        assertThatThrownBy(() -> new NdjsonSourcePlugin().start(DatedSourceContract.context(Map.of())))
                .isInstanceOf(PluginNotConfigured.class);
    }
}
```

When your plugin stores data **per business date**, extend `DatedSourceContract` instead: load its `ROWS` into
your store, return the started plugin from `plugin()`, and its tests (each date reads its own data, snapshot and
effective kinds, dated reverse lookup and search) run against your store unchanged. `DeltaSourcePluginTest`,
`PostgresEntityTableTest` and `AerospikeSourcePluginTest` do this. A **message-queue** plugin extends
`MessageSourceContract` (messages become live entities that survive a restart) and `BrokerOutage` (starts before
the broker and recovers after it is killed), as the ActiveMQ and RabbitMQ tests do.

**4. Configure it** in `drishti-server/src/main/resources/application.yaml` under `drishti.sources.plugins`, off
until configured, like the other optional plugins:

```yaml
      ndjson:                       # newline-delimited JSON files, one per kind
        enabled: ${DRISHTI_NDJSON_ENABLED:false}
        settings:
          root: ${DRISHTI_NDJSON_ROOT:./data/ndjson}
```

Route kinds to it with `drishti.sources.routes` (`trade: ndjson`) or run it several times as named connectors
(`drishti.sources.connectors.<name>: {plugin: ndjson, settings: {...}, kinds: [...]}`). Document the settings in
[PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) and [CONFIGURATION.md](CONFIGURATION.md), and add the module to the plugin table
in this guide and in `README.md`.

### 5.3 Add a Rachana-EL function

**Goal:** `round(x, digits?)` rounds half up: `round(2.345, 2)` is `2.35`.

Adding a function changes the grammar: record it as an amendment to
[ADR-003](adr/003-sutra-is-yaml-with-a-closed-expression-language.md). Functions must stay pure, total (odd input
gives `null`, never an exception) and cheap.

**1. Register it** in `drishti-rachana/src/main/java/com/ash/drishti/rachana/el/Functions.java`. `ARITY` holds
`{min, max}` arguments (the parser checks it and says `unknown function '…'; known: …` for a name not in it), and
`FUNCTIONS` holds the implementation:

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

`Values.number` turns any value (a number, numeric text, a boolean) into a `double` (`NaN` when it cannot), and
`Values.normalise` makes whole results `Long`, so `round(7.5)` prints `8`.

**2. Test it** in `drishti-rachana/src/test/java/com/ash/drishti/rachana/el/ElTest.java`, with its `eval` helper
over the test document (`notional` is 50,000,000):

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

**3. Document it** in the functions row of [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) and in the in-app
`console/web/guides/sutra-guide.md`.

### 5.4 Add a panel kind

**Goal:** a `metric` panel: one large, formatted, toned figure (`value`, with optional `label`, `fmt`, `tone`). A
panel kind spans the grammar, the engine and the console:

| File | Change |
|---|---|
| `drishti-rachana/.../model/PanelKind.java` | `METRIC(Set.of("value"), Set.of("label", "fmt", "tone"))`: required and accepted options. The parser rejects unknown kinds (`DRS-2021`), unknown options (`DRS-2023`) and missing required ones (`DRS-2022`). |
| `drishti-rachana/.../SutraExpressions.java` | `Map.entry(PanelKind.METRIC, Set.of("value"))` in `EL_OPTIONS`: which options hold expressions, so they are compiled and checked when the Sutra loads. |
| `drishti-rachana/src/main/resources/sutra.schema.json` | `"metric"` in the panel `kind` enum (for editors). |
| `drishti-engine/.../bind/Binder.java` | A `case METRIC -> metric(p, c);` in the `switch` in `bind` (the compiler insists: the switch over `PanelKind` must be exhaustive). |
| `drishti-engine/.../view/PanelData.java` and `Emptiness.java` | Only if the kind needs a new record. A new record must also be handled in `Emptiness.of`, whose `switch` over the sealed interface the compiler checks. This recipe reuses `PanelData.Fields`. |
| `console/web/templates/_macros/panels.html` | A branch in the `panel(p)` macro. |
| `console/web/static/css/terminal.css` | Its styles (no `style=` attributes: the CSP forbids them). |
| `console/core/export.py` and the `data-export-panel` list in `panels.html` | Only if its data should download as CSV. |
| `console/web/static/js/md-editor.js` | A snippet for Studio's toolbar. |
| `console/tests/test_terminal.py` | `"metric"` in `KINDS`, so it is rendered with broken data. |
| `console/web/guides/panel-kinds.md`, `RACHANA_REFERENCE.md`, `console/config/help.yaml` | Documentation (and the count: "thirteen panel kinds" is written in several places, including `PanelKind`'s Javadoc). |

**The binding** reuses `Binder.cell` (formatting, tones and links) and wraps the cell in `PanelData.Fields`, so
`Emptiness` already knows when it is empty (a dash or no text):

```java
                case SURFACE -> surface(p, c);
                case METRIC -> metric(p, c);
            };
```

```java
    private PanelData metric(Panel p, BindContext c) {
        String expr = p.option("value").orElseThrow();
        Object v;
        try {
            v = eval(expr, c.eval());
        } catch (RuntimeException e) {
            v = null;                                   // a field the document lacks shows as a dash, never an error
        }
        return new PanelData.Fields(List.of(cell(p.option("label").orElse(null), v, p.option("fmt").orElse(null),
                p.option("tone").orElse(null), true, pathOf(expr))));
    }
```

**The macro** in `panels.html`, next to the other kinds (the `tone` and `cell_text` macros already exist there):

```jinja
  {% elif p.kind == 'metric' %}{% set m = (p.data.fields or [{}])[0] %}<div class="metric"><span class="metric-v mono{{ tone(m) }}">{{ cell_text(m) }}</span>{% if m.label %}<span class="metric-l">{{ m.label }}</span>{% endif %}</div>
```

**A Sutra uses it** like any other kind:

```yaml
panels:
  - { id: mtm, kind: metric, title: MTM, value: $.mtm, fmt: signed0, tone: sign, label: USD }
```

**Test the binding** through the real pipeline with a Sutra and a document supplied by the test.
`ViewPipeline.preview(Optional<Sutra>, EntityDocument)` builds a view without any source:

```java
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance"})
class MetricPanelTest {

    @Autowired ViewPipeline pipeline;
    @Autowired JsonCodec codec;

    static final Sutra SUTRA = new SutraParser().parse("""
            sutra: metric-demo
            version: 1
            match: { kind: trade }
            title: { pill: Trade, id: $.tradeId }
            panels:
              - { id: mtm, kind: metric, title: MTM, value: $.mtm, fmt: signed0, tone: sign, label: USD }
            """, "metric-demo.yaml", "test");

    ViewModel.PanelView panel(String json) {
        EntityDocument doc = new EntityDocument(EntityRef.of("trade", "T-1"), codec.read(json),
                new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(Optional.of(SUTRA), doc).panels().get(0);
    }

    @Test
    void showsOneFormattedTonedFigure() {
        var p = panel("{\"tradeId\":\"T-1\",\"mtm\":-1250}");
        assertThat(p.kind()).isEqualTo("metric");
        var cell = ((PanelData.Fields) p.data()).fields().get(0);
        assertThat(cell.text()).isEqualTo("−1,250");
        assertThat(cell.tone()).isEqualTo("neg");
        assertThat(p.empty()).isFalse();
    }

    @Test
    void aMissingFigureIsAnEmptyPanelNotAnError() {
        var p = panel("{\"tradeId\":\"T-1\"}");
        assertThat(p.empty()).isTrue();
        assertThat(p.error()).isNull();
    }
}
```

`ImperfectDataTest` then applies every pack Sutra to mangled documents, and `test_terminal.py`'s
`test_every_panel_kind_renders_imperfect_data_without_failing` renders the macro with broken data.

### 5.5 Add a pack

A pack is a folder `packs/<name>/` with a `pack.yaml`. A new industry is content and configuration: no Java
(ADR-010). [PACKS.md](PACKS.md) documents every key; this recipe is the checklist.

**Hand-written or generated?** Look at the first lines of a pack's files. The banking packs (`banking-core`,
`market-data`, `trading`, `market-risk`, `counterparty-risk`) and `liquidity-risk`, `climate-risk`,
`operational-risk`, `retail-banking`, `genomics`, `politics-society` and `economics` are **generated**: their
`pack.yaml` starts with a line such as
`# Generated by tools/packgen/banking/make_packs.py from the taxonomy. Edit the generator, not this file.`
`finance` and `logistics` are hand-written (with sample generators in their own `tools/` folders).

**Never hand-edit a generated file.** Change the generator and run it. The drill runs every generator with
`--check` and stops when a file on disk differs from what the generator would write:

```bash
python3 tools/packgen/banking/make_packs.py          # writes the five banking manifests
python3 tools/packgen/banking/make_sutras.py         # their Sutras
python3 tools/packgen/banking/make_docs.py           # their guides and help.yaml
python3 tools/packgen/banking/make_data.py           # their samples
python3 tools/packgen/climate/make.py                # one generated pack: manifest, Sutras, samples, guide
python3 tools/packgen/climate/make.py --check        # what the drill runs: "climate-risk: N files up to date"
```

`--check` fails with `<pack> pack out of date; run <generator>. stale=[…] extra=[…]`.

**A new generated pack** describes itself in Python with a `PackSpec` and calls the common builder
(`tools/packgen/common/packbuild.py`), which checks the data (every id field matches its document, every link
resolves, every path a Sutra's header reads is present) before it writes `pack.yaml`, one Markdown Sutra per kind,
the samples and `catalog.json`, a guide and `config/help.yaml`, and, with `--lake <dir>`, the Delta Lake history.
The in-app tutorial `console/web/guides/build-a-pack.md` walks through one.

**A hand-written pack** has this layout (`packs/logistics/` is a complete, small example):

```text
packs/energy/
├── pack.yaml
├── sutras/            wind-farm.v1.sutra.md, …   one Markdown Sutra per layout
├── samples/           catalog.json, wind-farm/WF-HORNSEA-1.json, …   served by the demo plugin
├── config/            formats.yaml, semantics.yaml, help.yaml, workspaces.yaml
└── guides/            energy.md   the pack's page in the help centre
```

A `pack.yaml` with the keys you will use most:

```yaml
# Energy · renewable generation pack for Drishti.
pack: energy                       # must equal the folder name
version: 1.0.0
title: Energy · renewables
description: Wind and solar farms, turbines and power purchase agreements.
extends: [banking-core]            # parents load first; this pack may override them (ADR-015)
kinds: [wind-farm, turbine, ppa]   # a kind belongs to exactly one pack
mnemonics:
  WF:  { kind: wind-farm, label: Wind farm }
  WTG: { kind: turbine, label: Turbine }
  PPA: { kind: ppa, label: Power purchase agreement }
graph:
  id-patterns:                     # a bare id's shape tells its kind
    - { pattern: "^WF-", kind: wind-farm }
    - { pattern: "^WTG-", kind: turbine }
  fields:                          # a field with this name links to this kind
    windFarm: { kind: wind-farm, label: Wind farm }
    offtaker: { kind: counterparty, label: Offtaker }
  badges:
    wind-farm: "fmt($.capacityFactor, 'pct0') + ' CF'"
columns:                           # key fields shown in pick lists and searches
  wind-farm: [name, country, capacityMw, capacityFactor, status]
roles:
  energy-analyst: { kinds: [wind-farm, turbine, ppa, counterparty], raw: true }
connectors:                        # named connectors this pack's data comes from
  energy-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [wind-farm, turbine, ppa]
    settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: energy }
routes:
  wind-farm: energy-store
console:
  examples:
    - ["WF WF-HORNSEA-1", "Wind farm · output, turbines, PPAs"]
  help: config/help.yaml
```

A Sutra file in `sutras/` is Markdown with exactly one `sutra` block (ADR-011):

````markdown
# Wind farm (`wind-farm` v1)

One wind farm: capacity, output and its turbines.

```sutra
sutra: wind-farm
version: 1
match: { kind: wind-farm, priority: 10 }
title: { pill: "Energy · Wind farm", id: $.farmId, with: $.name }
strip:
  - { label: Capacity (MW), bind: $.capacityMw, fmt: amount0 }
  - { label: Capacity factor, bind: $.capacityFactor, fmt: pct0, emphasis: true }
  - { label: Status, bind: $.status, tone: status }
panels:
  - id: turbines
    kind: table
    title: Turbines
    key: F2
    rows: $.turbines
    columns:
      - { label: Turbine, bind: "@.turbineId" }
      - { label: Output (MW), bind: "@.outputMw", fmt: amount0 }
  - { id: refs, kind: links, title: Linked entities, area: right }
# … more panels as you need them
```
````

Each sample document may carry a `_meta` block (`source`, `generation`, `live`, and `walk` for the demo plugin's
random-walk ticks), which becomes the view's provenance; `samples/catalog.json` lists `{kind, id, title, subtitle}`
for the dropdown.

**Load it** with `DRISHTI_PACKS=energy` (parents come with it), and check:

```bash
curl -s localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
curl -s localhost:18480/api/v1/sutras/problems
curl -s localhost:18480/api/v1/admin/health | python3 -m json.tool | head -20
```

You should see `energy` among the packs, `{}` for problems, and `"packsWithProblems":0`. Two packs that define the
same mnemonic, role or connector differently without one extending the other stop the server at start-up: that is
why `finance` and the banking packs (both define `TRD`) cannot be loaded together.

**Tests that cover packs:** `PackLoaderTest` (manifests become properties; clashes are errors), `PackSutrasTest`
(every pack Sutra loads with no problems), `DocumentedSutrasTest` (the `sutra` blocks in pack guides parse),
`ImperfectDataTest`, and for generated packs `DomainPacksTest`, which opens every example command in
`console.examples` and expects a Sutra layout with every panel filled. Add your pack's name to the list in
`DomainPacksTest` when it should be held to that.

### 5.6 Add a table to the identity database

**Goal:** a `drishti_notice` table: site-wide notices that administrators switch on and off.

**1. The entity**, `drishti-identity/src/main/java/com/ash/drishti/identity/db/NoticeEntity.java`, in the style of
`PackStateEntity` (public fields, explicit column names):

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

**3. Both schema files.** Add the table to **both** `db/schema-sqlite.sql` and `db/schema-postgres.sql`, idempotent,
with matching names. Follow the existing type mapping: SQLite `TEXT`, `INTEGER` for booleans, `TIMESTAMP`;
PostgreSQL `VARCHAR(n)`/`TEXT`, `BOOLEAN`, `TIMESTAMP WITH TIME ZONE`.

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

There are no migrations. A **new table** is created on the next start of an existing installation. A **new column on
an existing table** is not added by `CREATE TABLE IF NOT EXISTS`; it needs its own idempotent statement in each file
(and a release note), so prefer new tables or nullable columns, and say in the release notes what an upgrade does.
Hibernate validates the PostgreSQL schema at start-up, so a column the entity has and the file lacks stops the server.

**4. The store**, `identity/NoticeStore.java`, in the style of `PackStateStore`: an immutable snapshot for readers,
a lock for writers, every change in a transaction and audited:

```java
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

**6. The contract test.** Add a test to `drishti-identity/src/test/java/com/ash/drishti/identity/IdentityStoreContract.java`.
It runs twice: `SqliteIdentityStoreTest` (always) and `PostgresIdentityStoreTest` (PostgreSQL 18 in Docker; skipped
without Docker). The contract's `schemaIsIdempotentSoAStartAgainstAnExistingDatabaseChangesNothing` already checks
that a second start against the same database works.

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

**Goal:** `drishti.notices.max-length` (default 500), settable as `DRISHTI_NOTICE_MAX_LENGTH`, used by
`NoticeStore` from the previous recipe.

**1. A record** with its defaults in the compact constructor and each key documented as a `@param`:

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

Boxed types (`Integer`, `Boolean`, `Duration`) let the constructor tell "not set" from a value. If a record has a
second constructor, mark the canonical one `@ConstructorBinding`, as `SearchProperties` does.

**2. Register it** on the module's configuration class and inject it where it is used:

```java
@EnableConfigurationProperties({IdentityProperties.class, NoticeProperties.class})
```

To add a key to an **existing** record instead, add a component to it. Every `new XxxProperties(...)` call in tests
then needs the extra argument (for example `ImperfectDataTest` constructs `RachanaProperties` directly).

**3. Put it in `application.yaml`** with an environment override and a comment saying what it does:

```yaml
drishti:
  notices:
    max-length: ${DRISHTI_NOTICE_MAX_LENGTH:500}   # longest notice text admins may set
```

The default in the YAML and in the record must agree: the record's default applies when the key is absent
(`SearchProperties` keys are not in the bundled file at all).

**4. Document it** in [CONFIGURATION.md](CONFIGURATION.md), in the section for its prefix: key, default, meaning,
and the environment variable.

**5. Test it** where the behaviour is, with the property set in the test:
`@SpringBootTest(properties = "drishti.notices.max-length=20")`, or in the identity contract by adding
`props.put("drishti.notices.max-length", "20")` in `open()`.

**In the console** the pattern is the same YAML: add the key to `console/config/application.yaml` as
`${DRISHTI_SOMETHING:default}` and read it with `settings.get("section.key", default)`. Every console key can also be
set as `DRISHTI_CONSOLE__SECTION__KEY` without a named variable.

### 5.8 Add a console page

**Goal:** `/sources`, a page listing the connectors the server runs, their kinds and health.

**1. The route**, `console/routes/source_routes.py`. Pages call the server through `request.app.state.backend` with
`ident(request)`, turn a `BackendError` into a message, and render with `render`, which adds the signed-in user,
packs, business date and settings to the template context:

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

**2. Register it** in `create_app` in `console/core/app.py`:

```python
    from routes import (admin_routes, api_routes, asof_routes, auth_routes, export_routes, help_routes, home_routes, monitor_routes,
                        source_routes, studio_routes, terminal_routes, workspace_routes)
    # …
    app.include_router(source_routes.router)
```

and add `"/sources"` to the `PROTECTED` tuple in the same file. **A path that is not in `PROTECTED` is public** when
sign-in is on. To list it in the top bar, add an entry to the menus in `web/templates/terminal/_topbar.html`.

**3. The template**, `console/web/templates/sources.html`, extends `base.html` like the other terminal pages (the
admin and workspace templates are good models):

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

Jinja autoescapes, so values from the server are safe to print. `ASSET_V` is the asset version plus a fingerprint of
`static/js` and `static/css`: always add `?v={{ ASSET_V }}` to your own script and style URLs.

**4. The script**, `console/web/static/js/sources.js`. No inline script: the page wires behaviour by `data-*`
attributes, and the script is an IIFE in strict mode like the others:

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

**5. The test.** The `client` fixture in `console/tests/conftest.py` builds the real app with a `FakeBackend` in place
of the server. Add the method your page calls to `FakeBackend`:

```python
    async def sources(self, ident=None):
        return {"sources": [{"name": "demo", "version": "1.0", "kinds": [], "live": True, "search": True,
                             "reverseLookup": True, "health": "UP"},
                            {"name": "credit-store", "version": "1.0", "kinds": ["netting-set"], "live": False,
                             "search": True, "reverseLookup": True, "health": "UP"}],
                "failures": {}}
```

and a test file, `console/tests/test_sources.py`:

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
console/.venv/bin/python -m pytest -q console/tests
console/.venv/bin/python -m pytest -q console/tests/test_sources.py -k connectors
```

`test_assets_policy.py` will fail if the template gains an inline script, handler or style, or an external URL.

---

## 6. Testing strategy

| Layer | Where | How | Examples |
|---|---|---|---|
| Unit | every module, `src/test/java` | JUnit 5 and AssertJ, plain constructors, no Spring | `DataNodeTest`, `ShapeFingerprinterTest`, `SearchQueryTest`, `DocumentDiffTest`, `FormatsTest`, `SutraParserTest`, `IdColumnTest` |
| Property-based | `drishti-rachana` | jqwik `@Property` | `ElTest` (random arithmetic and paths) |
| Concurrency | api, engine, identity, server | many threads on one object, then invariants | `HitIndexConcurrencyTest`, `TopicHubTest`, `UserServiceConcurrencyTest`, `LiveStreamSlotsTest` |
| Contract | `drishti-testkit` and subclasses | one abstract test class, one subclass per implementation | `DatedSourceContract` (Delta, Delta on S3, PostgreSQL table mode, Aerospike), `MessageSourceContract` and `BrokerOutage` (ActiveMQ, RabbitMQ), `IdentityStoreContract` (SQLite, PostgreSQL) |
| Spring and HTTP | `drishti-server` | `@SpringBootTest` + `@AutoConfigureMockMvc`, properties set per test class | `ApiTest`, `SecurityTest`, `CacheAdminTest`, `IdentityApiTest`, `WorkspaceApiTest`, `StructuredSearchTest`, `LiveTest` |
| Whole pipeline | `drishti-server` | `ViewPipeline` from the context, real packs | `ViewPipelineTest` (the four mockups, value by value), `BankingPacksTest`, `DomainPacksTest`, `ImperfectDataTest`, `BusinessDateTest` |
| Real services | plugins, identity | Testcontainers, skipped without Docker ([§2.3](#23-tests-that-need-docker)) | `PostgresIdentityStoreTest`, `AerospikeSourcePluginTest`, `RabbitMqOutageTest` |
| Repository rules | `drishti-it` | ArchUnit and file walks | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest` |
| Documentation | rachana, server | the docs are parsed as code | `DocumentedSutrasTest` (every `sutra` block in `docs/`, `console/web/guides/` and pack guides), `RachanaReferenceExampleTest` (the annotated example in `RACHANA_REFERENCE.md` previews as described), `PackSutrasTest` |
| Performance gate | `drishti-server` | timed in the test | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99`: 300 warm-up views, then 2000 timed; p99 must be under 50ms and the layout cache hit rate above 0.99 |
| Micro-benchmarks | `drishti-benchmarks` | JMH, run by hand | `HotPathBenchmark` ([PERFORMANCE.md](PERFORMANCE.md) has the commands) |
| Console | `console/tests` | pytest with `FakeBackend`; fixtures in `tests/fixtures/` are ViewModels captured from the real server | `test_terminal.py` (every panel kind with broken data), `test_assets_policy.py` (no CDN, no inline code, Python size), `test_contrast.py` (theme contrast), `test_help.py` (every catalogued guide renders) |
| Python tools | `tools/` | `unittest` | `tools/samplegen/test_samplegen.py`, `tools/lake/test_maintain.py` |
| Generated content | `tools/packgen`, pack tools | `--check` | run by the drill |
| In the browser | your machine | by hand | There is no browser automation in the repository. After a UI change, open the page in the running console in at least one dark and one light theme, use the keys (F-keys, `/`, `Alt+←`, table keys), watch a live view tick, and check the browser console for CSP violations. |

Habits that keep the suite fast and reliable:

- Turn the demo plugin's ticking off in Spring tests that do not test live updates:
  `"drishti.sources.plugins.demo.settings.ticking=false"`.
- Name the packs a test needs (`"drishti.packs.enabled=finance"`) rather than relying on the default.
- Server tests use their own identity database (`drishti-server/src/test/resources/config/application.properties`
  sets `jdbc:sqlite:target/test-identity/drishti.db`), so they never touch `./data`.
- Prefer one assertion chain that reads like the behaviour (`assertThat(p99).as("warm p99 ms").isLessThan(50)`).
- Name tests as sentences: `aMissingFigureIsAnEmptyPanelNotAnError`.

---

## 7. Debugging

### 7.1 Where to look

| Question | Ask |
|---|---|
| Is the server up? | `curl -s localhost:18480/actuator/health` → `{"status":"UP",…}` (`/actuator/health/liveness` and `/readiness` for probes) |
| Is the console up, and can it reach the server? | `curl -s localhost:17480/healthz` (the process) and `curl -s localhost:17480/readyz` (`503` while the server is unreachable) |
| Which packs, sources and problems? | `curl -s localhost:18480/api/v1/admin/health \| python3 -m json.tool`: version, uptime, heap, every source with status, health, kinds, live/dated/search and read counts, and pack problems. *Admin → Health* shows the same. |
| What does the server return for a view? | `curl -s localhost:18480/api/v1/views/trade/T-10001 \| python3 -m json.tool`. Add `-H 'X-Drishti-As-Of: 2026-09-29'` for a past date and `-H 'X-Drishti-User: ash'` to act as a user (security off). With security on, add `-H "Authorization: Bearer <token>"`. |
| What did the source send? | `F9` in the view, or `curl -s localhost:18480/api/v1/entities/trade/T-10001/raw` (redacted for roles without `raw`) |
| Which Sutra, how long? | The view JSON's `provenance.layout` (`Sutra irs-fixfloat v1 + inference`, or `inference only`) and `timings` (`fetch`, `layout`, `links`, `bind`, `total` in ms) |
| What would inference do on its own? | `curl -s localhost:18480/api/v1/studio/inferred/trade/T-10001` returns the inferred Sutra as Markdown |
| Is a Sutra broken? | `curl -s localhost:18480/api/v1/sutras/problems` (`{}` when none); see [runbooks/sutra-broken.md](runbooks/sutra-broken.md) |
| Which sources run? | `curl -s localhost:18480/api/v1/sources` (and `failures`) |
| What changed between dates? | `curl -s localhost:18480/api/v1/history/trade/T-10001/diff` |
| How are live streams doing? | `curl -s localhost:18480/api/v1/health/live` → `{"streams":…,"topics":…,"frames":…,"p50Ms":…,"p99Ms":…}` |
| Metrics | `/actuator/metrics/drishti.view`, `/actuator/prometheus` (`drishti_view_seconds`, `drishti_live_*`) |
| Every endpoint | `/api/docs` (OpenAPI JSON) and `/api/docs/ui` (Swagger UI). With security on, `/api/docs` needs a token and `/actuator` (except health) an admin token or `DRISHTI_METRICS_TOKEN`. |

### 7.2 Logs

Both programs log to standard output. The server logs each plugin it starts (`source plugin started: …`), plugins left
idle (`… installed but not configured …`), failures with stack traces, the identity database it opened
(`identity database ready: …`), and every 5xx answer (`request failed: …`). Raise the level for Drishti's packages
with Spring's usual environment form:

```bash
LOGGING_LEVEL_COM_ASH_DRISHTI=DEBUG java -jar drishti-server/target/drishti-server-1.10.2-exec.jar
```

The console runs Uvicorn with access logs off (`access_log=False` in `run_drishti_web.py`).

### 7.3 Debugging a live view

1. Does the view say **Live** in the top bar? A picked business date is static: no stream is opened.
2. `provenance.live` in the view JSON must be `true`; only live sources (`SourceCapabilities.live`) tick.
3. Watch the server stream directly; you should see an `event:view` and then `event:frame` lines:

   ```bash
   curl -sN localhost:18480/api/v1/views/trade/T-10001/stream | head -c 600
   ```

4. In the browser's network tab there should be exactly one `/api/channel?s=…` request per tab, staying open.
5. If ticks arrive but the page does not change, check the browser console for errors in `live.js`.

---

## 8. Common pitfalls

1. **Starlette cancel scopes in streaming responses.** In `console/routes/api_routes.py`, upstream work for
   `/api/channel` runs in tasks created with a **fresh** `contextvars.Context` (`detached()`), carrying only the
   business date and "known at". A task started from the request's context inherits Starlette's (anyio's) cancel
   scope, which, once the request has ended, cancels every `await` in it for good: clean-up never completed, the
   console's connections to the server stayed open, the pool ran out, and the UI froze. End streams by **closing the
   upstream response**, never by cancelling a task that is reading it. Follow the same pattern in any new streaming
   route.
2. **Stale scripts after a change.** `ASSET_V` (in `console/core/app.py`) is `ASSET_VERSION` plus a hash of the names,
   sizes and modification times of `web/static/js/*` and `web/static/css/*`, computed **once at start-up**. Restart
   the console after editing scripts or styles, or browsers keep the cached file. Files you add elsewhere (images,
   vendor upgrades) are not fingerprinted; change their name or bump `ASSET_VERSION`.
3. **Editing generated files.** Generated `pack.yaml`, Sutras, samples, guides and `help.yaml` are overwritten by the
   next generator run, and the drill's `--check` rejects a hand edit. The generated pack manifests even copy their
   header from lines 2–14 of `docs/RACHANA_REFERENCE.md` (`make_packs.py`), so editing that document's header makes
   them stale too.
4. **`sutra` blocks in documentation are compiled.** `DocumentedSutrasTest` parses every fenced block marked
   `sutra` in `docs/`, `console/web/guides/` and pack guides. Use `yaml` fences for fragments, or put `…` in a block
   that is deliberately abridged (such blocks are skipped).
5. **Environment variables leak into tests.** See [§2.2](#22-maven-commands): unset `DRISHTI_*` before running Maven.
6. **A page that is public by accident.** New console paths must be added to `PROTECTED` in `console/core/app.py`.
   `/t` and `/s` are matched exactly (they are prefixes of public paths such as `/static`); the rest by prefix.
7. **Inline code under the CSP.** `onclick=`, `<script>…</script>` and `style="…"` are blocked by the browser and
   rejected by `test_assets_policy.py`. Use a script file and `data-*` attributes.
8. **A plugin that starts by surprise.** Plugins are enabled by default when they are on the class path. Disable
   them in `application.yaml` until configured, or throw `PluginNotConfigured` from `start`.
9. **`synchronized` around I/O** pins a virtual thread to its carrier. Use `ReentrantLock` (see [§3.4](#34-object-oriented-concurrent-and-configuration-driven)).
10. **Adding a record component** to a `@ConfigurationProperties` record breaks every direct constructor call in
    tests; a record with two constructors needs `@ConstructorBinding` on the canonical one.
11. **SQLite hides schema mismatches.** Hibernate validates only on PostgreSQL. Run the identity contract on PostgreSQL
    (Docker) after changing an entity or a schema file.
12. **Packs that clash.** Two unrelated packs defining the same mnemonic, role, route or connector stop the server.
    `finance` and the banking packs both define `TRD`; never enable them together.
13. **Layouts that do not change.** The layout cache is keyed by the Sutra object, kind and data shape; a hot reload
    clears it. If you changed inference code or semantic hints instead, restart, or purge the engine cache from
    *Admin → Caches*.
14. **A test that reads the repository** (`DocumentedSutrasTest`, `ImperfectDataTest`, the `drishti-it` rules) resolves
    paths relative to its module (`../docs`, `../packs`). Run Maven from the repository root.

---

## 9. Releasing

Releases are numbered `MAJOR.MINOR.PATCH` and tagged `vX.Y.Z` on `main` (`git tag` lists `v1.0.0` … `v1.10.2`).
The release commit for 1.9.0 (`Release 1.9.0`) shows every file a bump touches.

1. **Start clean on `develop`** with everything for the release merged and drilled.
2. **Bump the version** everywhere it is written. Find the old version first:

   ```bash
   git grep -n -F '1.10.2' -- '*pom.xml' console/core/app.py deploy README.md docs
   ```

   | File | What changes |
   |---|---|
   | `pom.xml` and every module's `pom.xml` (including `plugins/*/pom.xml`) | the project `<version>` and each module's `<parent><version>` |
   | `console/core/app.py` | `ASSET_VERSION`, so browsers fetch the new scripts |
   | `deploy/compose.yaml`, `deploy/server.Dockerfile`, `deploy/console.Dockerfile` | image tags `drishti-server:<version>`, `drishti-console:<version>` |
   | `README.md`, `docs/*.md` | the jar name `drishti-server-<version>-exec.jar` and versions in examples |

   Leave historical entries (old changelog sections) alone.
3. **Write the release text.** Add a section at the top of `CHANGELOG.md`
   (`## X.Y.Z — <headline> (<date>)`, then what changed, grouped, with the settings and endpoints involved) and of
   `RELEASE_NOTES.md` (`# Drishti X.Y.Z — release notes`, a short, user-facing summary; the previous release's heading
   becomes `# Previous release: Drishti …`). Add upgrade notes when the identity schema, configuration keys or
   on-disk formats change.
4. **Verify locally.** `./mvnw -q package -DskipTests`, start the new jar, and run through [QUICKSTART.md](QUICKSTART.md):
   `curl -s localhost:18480/api/v1/about` should report the new `version`.
5. **Commit** as `Release X.Y.Z` on `develop`.
6. **Drill:** `tools/drill.sh`. Nothing reaches `main` unless the whole build, every test, the console tests, the
   header check and the generator checks pass.
7. **Tag `main`** with an annotated tag and push it:

   ```bash
   git tag -a vX.Y.Z -m "Drishti X.Y.Z: <headline>" main
   git push origin vX.Y.Z
   ```

8. **Images (optional):** `docker build -f deploy/server.Dockerfile -t drishti-server:X.Y.Z .` and
   `docker build -f deploy/console.Dockerfile -t drishti-console:X.Y.Z .` from the repository root, after building
   the jar. [OPERATIONS.md](OPERATIONS.md) covers deployment and upgrades.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
