<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

**For a bank, Drishti is explainable and controlled risk aggregation over the engines it already has.** Your pricing and simulation
systems deliver trade-grain inputs (scenario P&L vectors, exposure paths, sensitivities); Drishti computes **VaR, ES, counterparty
exposure, FRTB-SA and SIMM on demand for any scope**, from the whole bank down to an ad-hoc set of trades, instead of storing finished
numbers. Every figure can be explained: where it came from, how it was calculated and why it moved. Every figure is also controlled:
roles and masks decide who sees what, changes go through four eyes, access is logged and audited, and anything not built yet is clearly
marked as such.

Beneath the risk sits a general platform: any data through connectors and domain packs, screens written as short YAML **Sutras** in the
**Rachana** grammar, a pivot engine, business intelligence, data out through Arrow Flight and Flight SQL, and lakes in Delta Lake,
Apache Iceberg or memory. The motto is **lean & streamlined**: one implementation of each thing, one grammar for every expression,
nothing on screen that is not built.

```
VAR BANK <GO>                           →  VaR and ES of the bank, down the hierarchy to a trade, contributions that add up
PVAR DESK DESK-RATES <GO>               →  the same explorer with parametric VaR beside historical, as a cross-check
EXP CPTY:CP-ALDERSHOT <GO>              →  exposure from the simulator's paths: EE, PFE, EPE, EEPE
FRSA BANK <GO>                          →  FRTB-SA capital: SBM, DRC and RRAO, by class and bucket
SIMM <GO>                               →  initial margin per netting set, to collect and to post
MCUBE <GO>                              →  the market-risk cube: VaR by desk and risk class against limits
TRD MX-20000001 <GO>                    →  a trade: terms, legs, cashflows, links to what it touches
```

## Contents

[Risk, computed and explained](#risk-computed-and-explained) · [The platform](#the-platform) · [Try it](#try-it) ·
[Measured performance](#measured-performance) · [Documentation](#documentation) · [Project facts](#project-facts) · [By the numbers](#by-the-numbers)

## Risk, computed and explained

- **VaR and ES for any scope**: the bank, a desk, a book, a counterparty, a portfolio, a basket, or an ad-hoc scope written as an
  expression (`BANK - DESK:CREDIT`). Drill from the bank to a trade with contributions that add up, hierarchies from rows or configured
  from the trades' own columns, limits per node, any reporting currency, intraday updates on top of the morning run, the official
  end-of-day record, captures, history and backtests ([VAR_GUIDE.md](docs/guides/VAR_GUIDE.md),
  [tutorial 25](docs/tutorials/25-var-from-the-bank-to-a-trade.md)).
- **Parametric VaR as a cross-check**: the explorer's **Parametric** toggle (or `PVAR`) fits the normal distribution to the scope's own
  scenario P&L and shows parametric VaR, ES and the fat-tail gap beside historical VaR. It is never the official figure: limits,
  backtesting and official records stay historical ([VAR_GUIDE.md section 3.12](docs/guides/VAR_GUIDE.md#312-parametric-var-beside-historical-pvar),
  [tutorial 43](docs/tutorials/43-parametric-var-beside-historical.md)).
- **Counterparty exposure** (EE, PFE, EPE, EEPE) for any scope, from the simulator's paths, with netting, collateral on each path and a
  pre-deal what-if ([EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [tutorial 26](docs/tutorials/26-exposure-from-the-bank-to-a-trade.md)).
- **FRTB-SA** (sensitivities-based method, default risk charge, residual risk add-on) for any VaR scope, with its parameters as
  configuration ([FRTB_SA_GUIDE.md](docs/guides/FRTB_SA_GUIDE.md), [tutorial 30](docs/tutorials/30-frtb-sa-from-the-cases-to-a-desk.md)).
- **SIMM** initial margin per netting set and as an analytic over any scope. ISDA's calibrated numbers are licensed, so the parameter
  file ships empty and the bank installs its version ([SIMM_GUIDE.md](docs/guides/SIMM_GUIDE.md#4-the-licensed-parameter-file),
  [tutorial 32](docs/tutorials/32-simm-from-the-examples-to-a-netting-set.md)).
- **The MR cube** (`MCUBE`): VaR by desk and risk class on the pivot engine, each cell equal to the VaR engine's answer for its scope,
  with tail and attribution, DV01 buckets and what-if ([MR_CUBE_GUIDE.md](docs/guides/MR_CUBE_GUIDE.md),
  [tutorial 38](docs/tutorials/38-the-mr-cube-on-the-pivot-engine.md)).

**Explained.** The **?** on a figure opens the server's own lines: the method, the inputs and the formula with the scope's numbers.
**Explain the move** breaks a change between two dates into exact steps that add up. A cell's **provenance** shows its scope, members,
data and method. VaR, FRTB-SA and SIMM each have a **playground** (`PLAY VAR`, `PLAY FRTB`, `PLAY SIMM`) that works a small case step by step and exports an Excel workbook with live
formulas. The pivot's **Why** and **What-if** explain any difference ([PIVOT_GUIDE.md section 20](docs/guides/PIVOT_GUIDE.md#20-why-did-it-move-explain-the-difference),
[tutorial 37](docs/tutorials/37-custom-functions-and-explain.md)).

**Controlled.** All risk is computed on the server in Java; the console only draws it. Roles per kind, row rules and field masks apply to
every screen, pivot, BI query and Flight read. Corrections and screen designs go through four eyes, access is logged and audited, and each compute engine is
switched on or off per node in **Admin → Engines** ([OPERATIONS.md section 21](docs/admin/OPERATIONS.md#21-compute-engines-on-or-off)).
**Nothing unbuilt looks built**: a figure, panel or menu item that depends on unbuilt work is registered in
`config/under-construction.yaml` and is either shown with an *Under construction* chip or hidden
([UNDER_CONSTRUCTION.md](docs/admin/UNDER_CONSTRUCTION.md)). Today that covers the stored SA-CCR and xVA figures, credit limit usage and
the pre-deal limit decision, among others.

## The platform

### Look up, search and analyse

- **A keyboard-driven terminal**: type-ahead over every source, linked views of any entity, impact (F8), the raw document (F9), any past
  business day, history and compare, live views that tick ([USER_GUIDE.md](docs/guides/USER_GUIDE.md),
  [COMMAND_CATALOG.md](docs/guides/COMMAND_CATALOG.md), [tutorial 2](docs/tutorials/02-look-up-anything.md)).
- **Search** by value (`TRD where QUANTITY > 200m order by QUANTITY desc`), in plain words parsed without any AI service, or with a
  form (`FORM <pack>`); monitors, alerts and workspaces ([tutorial 3](docs/tutorials/03-query-by-form.md)).
- **The pivot engine** (`PIVOT`): any kind, search or measure as a pivot, every total computed from its own members, saved and shared
  views, live pivots, Excel export with formulas ([PIVOT_GUIDE.md](docs/guides/PIVOT_GUIDE.md), [tutorial 33](docs/tutorials/33-the-pivot-engine.md)).
- **Business intelligence** (Rūpaka) on the pivot engine: models, reports, live boards, governance and distribution, with row rules and
  masks per reader ([BI_GUIDE.md](docs/guides/BI_GUIDE.md), [tutorial 10](docs/tutorials/10-first-bi-report.md)).
- **One grammar.** Rachana paths and expressions are used everywhere: in Sutras, search, the pivot, BI and custom functions. The
  **function catalogue** lists every function with the rules each keeps (nulls, rounding, dates on the bank's calendars) and its Excel,
  SAS and MATLAB counterparts ([FUNCTIONS.md](docs/guides/FUNCTIONS.md), [FUNCTION_CATALOGUE.md](docs/architecture/FUNCTION_CATALOGUE.md)).

### Screens without code

- **Sutras**: a product family's screen is a short, versioned YAML file. Where there is no Sutra, the inference engine lays the screen
  out from the shape of the data. One generic pipeline draws every entity of every kind
  ([SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md), [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md)).
- **Domain packs** bring an industry: kinds, mnemonics, Sutras, links, roles and connectors, in YAML only. The core knows no industry.
  Packs load and unload from Admin → Packs, and a pack can be made from your own JSON Schema or JSON Lines
  ([PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md), [SCHEMA_TO_PACK_GUIDE.md](docs/guides/SCHEMA_TO_PACK_GUIDE.md),
  [tutorial 5](docs/tutorials/05-build-a-pack.md)).
- **The Build workbench and the Sutra designer**: drag panels onto a live screen; a save is a proposal an approver reviews
  ([tutorial 6, part B](docs/tutorials/06-design-a-screen.md#part-b--on-a-live-page-the-sutra-designer)).

### Data in

- **Connectors**, one YAML file each in `config/connectors/`: Delta Lake and Apache Iceberg (Drishti's own engines, no Hadoop, local disk
  or S3), JDBC databases, DuckDB, MongoDB, Redis, Aerospike, Cassandra, Kafka, ActiveMQ, RabbitMQ, files, S3, REST and public feeds
  ([CONNECTORS.md](docs/connectors/CONNECTORS.md), [tutorial 4](docs/tutorials/04-connect-your-data.md)).
- **One file per lake**: `config/lakes/<lake>.yaml` says where a lake is (a folder, `s3://` or `mem://`) and describes every table in it
  (partitions after `business_date`, promoted columns, vectors, load mode, retention). Loaders, the data inbox and connectors all refer to
  it; `drishti.py lake check` compares it with the tables on disk ([DATA_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md),
  [tutorial 34](docs/tutorials/34-one-file-per-lake.md)).
- **The load rules**: JSON Lines is the data standard. A plain file is one table and one business date, named in the file
  (`<name>.<YYYY-MM-DD>.jsonl.gz`); a `.ready` control file says the file is complete and may override the name; a tar archive carries
  several tables and dates and loads all or nothing. Loads **append** by default, and every row carries its load id, so
  `drishti.py lake undo <load-id>` removes exactly one load. Nothing is guessed: a file that breaks a rule is refused with the reason
  ([DATA_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo), [tutorial 7](docs/tutorials/07-load-data-daily.md)).
- **The data inbox** loads the files your ETL drops, by routing rules, with Admin → Data loads to watch, retry and undo
  ([tutorial 36](docs/tutorials/36-load-files-with-the-data-inbox.md)). For large loads, the parallel Java loader is multi-threaded and
  memory-bounded ([PARALLEL_LOADER.md](docs/architecture/PARALLEL_LOADER.md)).
- **In-memory lakes** (`mem://`) for feeds that never touch disk, such as news ([IN_MEMORY_LAKE_GUIDE.md](docs/guides/IN_MEMORY_LAKE_GUIDE.md),
  [tutorial 31](docs/tutorials/31-news-in-memory-only.md)).
- **Data contracts**: one per mnemonic, stating what the upstream engines deliver and what Drishti answers
  ([docs/contracts/](docs/contracts/README.md)); `DATAMAP` shows what the packs hold today.

### Data out

- **Arrow Flight and Flight SQL** (engine `flight`, off as shipped): Drishti's data, BI models, pivot views and VaR scenario P&L vectors
  delivered to Python, Java, JDBC and ODBC clients as Arrow, over TLS, read only, with API tokens scoped `data:read`. The reader's
  entitlements, masks, limits and audit apply exactly as on the screens. Flight SQL answers SQL over the BI models
  ([FLIGHT_GUIDE.md](docs/guides/FLIGHT_GUIDE.md), [FLIGHT_REFERENCE.md](docs/guides/FLIGHT_REFERENCE.md),
  [tutorial 35](docs/tutorials/35-data-out-with-arrow-flight.md)). Reads on a single node are tested; the three-node end to end is
  under construction.
- **Client examples** ([examples/clients/](examples/clients/README.md)): Python notebooks (pandas, polars, DuckDB, a VaR P&L plot, a live
  stream with resume), a Java program, JDBC through Flight SQL, and Excel through Python.
- **REST API and embedding**: everything under `/api/v1` with API tokens ([API_GUIDE.md](docs/guides/API_GUIDE.md)); live views, panels
  and reports inside other web applications as Web Components ([CLIENT_RENDERER.md](docs/architecture/CLIENT_RENDERER.md#19-drishti-elements-developer-guide),
  [tutorial 16](docs/tutorials/16-embed-drishti.md)).

### Operate

- **Security**: sign-in with passwords or OpenID Connect, roles per kind, field masks, API tokens, an access log and an audit trail. It
  is **off by default** for local development and is turned on for any shared machine ([USER_MANAGEMENT.md](docs/admin/USER_MANAGEMENT.md),
  [tutorial 17](docs/tutorials/17-run-it-in-production.md)).
- **Clustering** (Drishti\*net, off by default): servers join by gossip, any member answers any request, VaR shards across members
  ([OPERATIONS.md section 18](docs/admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one), [tutorial 19](docs/tutorials/19-a-cluster-of-three.md),
  [drishti-stack/](drishti-stack/README.md)).
- **Linear algebra**: OpenBLAS is bundled for Linux and Windows x86-64 but **off as shipped**. A 250,000-position run gave the same VaR to
  the dollar with it on and off, with no clear end-to-end gain. Turn it on per node in Admin → Engines after measuring on your own
  hardware ([OPERATIONS.md section 2.1](docs/admin/OPERATIONS.md#21-server-requirements-native-libraries-blas)).
- **Admin and Health**: packs, connectors, lakes, engines, roles and functions from the Admin pages; Prometheus metrics; deployment by
  hand, with systemd, with Docker Compose ([deploy/docker/compose.yaml](deploy/docker/compose.yaml)), on AWS ECS, and on Windows with
  Java and Python only ([OPERATIONS.md](docs/admin/OPERATIONS.md), [GETTING_STARTED.md › Windows](docs/guides/GETTING_STARTED.md#drishti-on-windows)).
- An **AI layer** and an **MCP client**, both off by default, with masking and audit of every call ([AI.md](docs/admin/AI.md)).

## How it works

![How a command becomes a screen: sources feed the connector plugins; the server's view pipeline turns a command into a ViewModel in
JSON, which the console, Drishti Elements and the API render; beneath it, the risk engines, BI and the domain packs.](docs/img/readme/drishti-flow.svg)

Every view is built by the same pipeline: the command is parsed, the document is fetched from the connector that holds it for the
chosen date, the kind's Sutra is matched, the layout is taken from a cache keyed by Sutra version and data shape, links are fetched in
parallel, and the panels are bound. The server sends the bound screen as one JSON **ViewModel**, and live updates are patches to it.
A Sutra is interpreted, never compiled, and there is no screen per product
([ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md), [VIEWMODEL.md](docs/architecture/VIEWMODEL.md)).

The server is one Spring Boot application on virtual threads, built from Maven modules that depend in one direction (the plugin SPI
has no Spring). The console is a separate Python web application that computes nothing itself.

The connector plugins, `plugins/drishti-plugin-*/`: `demo`, `file`, `rest`, `jdbc`, `delta`, `iceberg`, `iceberg-external`, `duckdb`, `mongodb`, `redis`, `aerospike`, `cassandra`, `kafka`, `activemq`, `rabbitmq`, `s3`, `feeds`, `news`, `yfinance`.

## Try it

[GETTING_STARTED.md](docs/guides/GETTING_STARTED.md) installs and starts Drishti from a clean machine, and
[tutorial 1](docs/tutorials/01-first-15-minutes.md) walks through your first quarter of an hour. The short version, from the repository
root:

- **Prerequisites**: a 64-bit Java 21 (JDK) and Python as stated in
  [GETTING_STARTED.md, Step 1](docs/guides/GETTING_STARTED.md#step-1--install-the-prerequisites); [uv](https://docs.astral.sh/uv/)
  to set up the console's Python. Maven ships with the repository (`./mvnw`).

```bash
# 1. Build the server
./mvnw -q package -DskipTests -pl drishti-server -am

# 2. The console's Python environment (it also runs tools/drishti.py)
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt

# 3. The demo lakes, from the committed test data in data/testdata
drishti-console/.venv/bin/python tools/drishti.py lake demo

# 4. The server on :18480 (native libraries unpack in its own folder, not /tmp)
mkdir -p data/tmp/server
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar

# 5. In a second terminal: the console on http://localhost:17480
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

Then open http://localhost:17480/t and try `TRD MX-20000001`, `VAR BANK`, `EXP BANK` and `PIVOT`. Security is off in this start, which
is meant for one developer machine. To run with sign-in, which is how the tutorials run, follow
[GETTING_STARTED.md, Step 6](docs/guides/GETTING_STARTED.md#step-6--start-the-server) and
[Step 8](docs/guides/GETTING_STARTED.md#step-8--sign-in-and-change-the-admin-password).

Other ways to run it: [Windows](docs/guides/GETTING_STARTED.md#drishti-on-windows) (`tools/windows/start-server.ps1`; Java and Python
only), [Docker](docs/guides/GETTING_STARTED.md#running-everything-in-docker-optional), the
[IDEs](docs/guides/GETTING_STARTED.md#running-from-intellij-idea-and-pycharm), and a three-server cluster from
[drishti-stack/](drishti-stack/README.md).

## Measured performance

All figures were measured on one developer workstation. The method, the commands to run each benchmark again and the limits of each
figure are in [BENCHMARK.md](docs/admin/BENCHMARK.md).

| Run | Result | Where |
|---|---|---|
| VaR, 200,000 positions, one server (`-Xmx8g`) | first open of a date 4.5 s; 50 users 270 requests/s, 0 errors; heap with two dates open 881 MB | [§4.2](docs/admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster), [§4.3](docs/admin/BENCHMARK.md#43-users-50-100-and-200) |
| VaR, 200,000 positions, three-node cluster | the bank's VaR bit-equal to one server | [§4.2](docs/admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster) |
| VaR, 1,000,000 positions, one server (`-Xmx6g`) | first open of a date 20.9 s; 50 users 183 requests/s, 0 errors; heap with two dates open 2.2 GB | [§4.2](docs/admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster), [§4.3](docs/admin/BENCHMARK.md#43-users-50-100-and-200) |
| Many VaR scopes at once, 1,000,000 positions | 100 overlapping sets 3.1x faster summed together on OpenBLAS; 100 desks 3.3x on Java; results identical or within 4.5e-16 | [§17](docs/admin/BENCHMARK.md#17-many-var-scopes-at-once-blas) |
| 1,000,000 trades a day on Delta | search over every trade 134 ms (2,017 matches); open a trade 48 ms cached | [§8.1](docs/admin/BENCHMARK.md#81-a-million-trades-a-day-on-delta) |
| 35.7 million trades over 30 dates, 50 users | server 179 requests/s, 0 failures; a day of 400,000 trades restated in 2 min 54 s while 30 users read | [§8.3](docs/admin/BENCHMARK.md#83-thirty-days-and-a-day-restated-while-serving) |
| The parallel Java loader, 10,000 positions × 510 scenarios | 0.19 to 0.29 s warm, 34,000 to 52,000 rows/s | [§7.2](docs/admin/BENCHMARK.md#72-the-java-parallel-loader-per-stage) |

## Documentation

The map of every document, by what you want to do, is [docs/README.md](docs/README.md); the console's Help centre holds the same
guides. Start from the door that fits:

| You want to… | Start with |
|---|---|
| use Drishti | [Use Drishti](docs/README.md#use-drishti): [USER_GUIDE.md](docs/guides/USER_GUIDE.md), [VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), [EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [BI_GUIDE.md](docs/guides/BI_GUIDE.md) |
| change how screens look | [Change how screens look](docs/README.md#change-how-screens-look): [SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md), [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md) |
| add an industry or data | [Add an industry or data](docs/README.md#add-an-industry-or-data): [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md), [DATA_GUIDE.md](docs/guides/DATA_GUIDE.md), [DATA_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md) |
| run and administer it | [Run and administer it](docs/README.md#run-and-administer-it): [GETTING_STARTED.md](docs/guides/GETTING_STARTED.md), [OPERATIONS.md](docs/admin/OPERATIONS.md), [CONFIGURATION.md](docs/admin/CONFIGURATION.md) |
| develop on it | [Develop on it](docs/README.md#develop-on-it): [PACK_DEVELOPER_GUIDE.md, Part VIII](docs/guides/PACK_DEVELOPER_GUIDE.md#part-viii-changing-drishtis-code-the-developer-guide), [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md), [API_GUIDE.md](docs/guides/API_GUIDE.md) |

**Learn by doing**: [the tutorials](docs/tutorials/README.md) run on the shipped demo data and show the UI, the command line and the YAML
at every step, with real screenshots. Every setting is in [CONFIGURATION.md](docs/admin/CONFIGURATION.md); the command line is
[CLI_GUIDE.md](docs/guides/CLI_GUIDE.md).

**Where it's going**: [IMPLEMENTATION_PLAN.md, The roadmap from 2.9.11.c](docs/architecture/IMPLEMENTATION_PLAN.md#the-roadmap-from-2911c-product-owner-2026-11-04-supersedes-every-later-release-table-in-this-file).
What changed and when: [RELEASE_NOTES.md](RELEASE_NOTES.md) and [CHANGELOG.md](CHANGELOG.md).

## Project facts

- **Server**: Java 21 (64-bit), Spring Boot on virtual threads, REST under `/api/v1` with OpenAPI; identity and user content in SQLite
  by default or PostgreSQL, one complete schema file per database and no migrations.
- **Console**: Python, FastAPI and Jinja2; every front-end asset is vendored (no CDN) under a strict content security policy; it works
  on phones.
- **Command line**: `tools/drishti.py`, run with the console's Python.
- **Contributing**: work on `develop`; `./mvnw -q verify` and the console tests
  (`drishti-console/.venv/bin/python -m pytest -q drishti-console/tests`) must pass; no source file over 1,500 lines; every file carries
  the copyright header (`python3 tools/license_headers.py --fix`); every change updates the documents it affects and the changelog in
  the same push.

## By the numbers

<!-- code-volume:begin -->

*Measured 2026-11-07 from the files the repository tracks (`tools/docs/code_volume.py`; sample data and vendored libraries not counted).*

| Kind | Lines | Files |
|---|---|---|
| Java, product | 429k | 2,151 |
| Java, tests | 163k | 1,025 |
| Python, product (console, tools, loaders) | 136k | 700 |
| Python, tests | 101k | 573 |
| JavaScript, HTML and CSS (console) | 162k | 881 |
| YAML: configuration, Sutras, data contracts | 105k | 1,349 |
| Markdown: guides, tutorials, design | 260k | 441 |

About **728k lines of product code** and **263k lines of tests** (36% of the product code), with **260k lines of documentation**; 53 Maven modules, 11 domain packs and 40 tutorials.

<!-- code-volume:end -->

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
