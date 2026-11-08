<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti is a platform for looking at, analysing and explaining data, with its industries added as **modules**. The core knows no industry:
it brings connectors and lakes, screens written as short YAML **Sutras** in one grammar (**Rachana**), the pivot engine, business
intelligence, a command line and a REST and Arrow Flight API. The banking module adds a bank's risk; capital markets and the economy
(with the public sector) add listed companies, funds, economies, governments and news.

**For a bank, Drishti is explainable and controlled risk aggregation over the engines it already has.** The pricing and simulation
systems deliver trade-grain inputs (scenario P&L vectors, exposure paths, sensitivities); Drishti computes **VaR, ES, counterparty
exposure, the pre-deal check, FRTB-SA and SIMM on demand for any scope**, from the whole bank down to an ad-hoc set of trades, instead of
storing finished numbers. Every figure can be explained (where it came from, how it was calculated, why it moved) and every figure is
controlled (roles, masks, four eyes, an audit trail, and nothing unbuilt shown as built).

The motto is **lean & streamlined**: one implementation of each thing, one grammar for every expression, one set of JavaScript libraries
for every screen.

```
VAR BANK <GO>                                  →  VaR and ES of the bank, down the hierarchy to a trade, contributions that add up
EXP CPTY:CP-ALDERSHOT <GO>                     →  exposure from the simulator's paths: EE, PFE, EPE, EEPE
PREDEAL CP-ALDERSHOT LIKE 1000001 250M <GO>    →  the pre-deal check: approve, refer or decline, against the limit and its buckets
FRSA BANK <GO>                                 →  FRTB-SA capital: SBM, DRC and RRAO, by class and bucket
SIMM <GO>                                      →  initial margin per netting set, to collect and to post
WDI WDI-CAN <GO>                               →  a country's development indicators, with the world map by GDP per head
TRD MX-20000001 <GO>                           →  a trade: terms, legs, cashflows, links to what it touches
```

## Contents

[Modules, lenses and functions](#modules-lenses-and-functions) · [Risk, computed and explained](#risk-computed-and-explained) ·
[Analyse](#analyse) · [Screens and Drishti Elements](#screens-and-drishti-elements) · [Data in: connectors, lakes and feeds](#data-in-connectors-lakes-and-feeds) ·
[Data out](#data-out) · [Operate](#operate) · [Run it](#run-it) · [Measured performance](#measured-performance) ·
[Documentation](#documentation) · [Project facts](#project-facts) · [By the numbers](#by-the-numbers)

## Modules, lenses and functions

Access is decided in one place, the **access registry**: licence → module → lens → function → permission
([ACCESS_MODEL.md](docs/architecture/ACCESS_MODEL.md), [DOMAIN_MODULES.md](docs/architecture/DOMAIN_MODULES.md)).

| Module | What it brings |
|---|---|
| **core** | everything without an industry: connectors, lakes, Sutras, search, the Pivot, BI, tables and models, approvals, alerts, Explain, Admin; GLEIF's legal entities as reference data |
| **banking** | VaR and ES (historical and parametric, backtests, the market-risk cube), counterparty exposure and the pre-deal check, sensitivities, FRTB-SA, SIMM, the official results; the trading, market-data, market-risk and counterparty-risk packs; the drishti-risk library |
| **capital markets** | US equities (SEC EDGAR filings and facts, indices), funds and ETFs, and the market data's functions (curves, FX, volatilities, equities, bonds) |
| **economy**, with the public sector | economies, forecasts, governments, elections and news; World Bank development indicators, central banks (FRED, Bank of Canada), Statistics Canada, the OECD, official press releases and GDELT world events ([PUBLIC_SECTOR_GUIDE.md](docs/guides/PUBLIC_SECTOR_GUIDE.md), [tutorial 46](docs/tutorials/46-public-sector-data.md)) |

- **A module** is on when it is installed, licensed and enabled (`config/modules/<module>.yaml`, or `/api/v1/admin/modules` with a
  reason, audited). A module that is off
  loads no bean, engine, job, pack or API, and its pages, menus, commands, Help pages and Build items are gone from every door of the
  console ([OPERATIONS.md section 22](docs/admin/OPERATIONS.md#22-modules-on-or-off)).
- **Packs name their domain** and live in its folder: `config/packs/<domain>/<pack>` (core, banking, capitalmarkets, economy).
- **A function** is a command word (`VAR`, `TRD`, `WDI` …), the finest unit of access: each belongs to one module, and an administrator
  can switch any one off.
- **A lens** is what a person works in: its modules, functions, Home cards and chips. Shipped lenses: Risk, Trading, Market, CRO, the
  market-risk and counterparty-risk cubes (banking); World and Public sector (economy); CIO and Oversight (core). A lens file is
  `config/lenses/<module>/<lens>.yaml` ([LENSES.md](docs/admin/LENSES.md)). **Editions are now lenses**: the former Risk, Trading, Market
  and World editions are these lenses, and `drishti.edition` still runs a whole instance as one lens.

## Risk, computed and explained

The banking module's engines run on the server in Java, for any scope; the console only draws their answers.

- **VaR and ES for any scope**: the bank, a desk, a book, a counterparty, a portfolio, a basket, or an ad-hoc scope written as an
  expression (`BANK - DESK:CREDIT`). Drill from the bank to a trade with contributions that add up, hierarchies from rows or from the
  trades' own columns, limits per node with one limit rule (within, near, breach), any reporting currency, the 509- or 250-scenario
  window, intraday updates, the official end-of-day record, history and backtests ([VAR_GUIDE.md](docs/guides/VAR_GUIDE.md),
  [tutorial 25](docs/tutorials/25-var-from-the-bank-to-a-trade.md)). Regulatory reporting to the cell:
  [REGULATORY_VAR_REPORTING.md](docs/guides/REGULATORY_VAR_REPORTING.md).
- **Parametric VaR as a cross-check** (`PVAR`, or the explorer's Parametric toggle): the normal fitted to the scope's own scenario P&L,
  beside historical VaR; never the official figure ([VAR_GUIDE.md section 3.12](docs/guides/VAR_GUIDE.md#312-parametric-var-beside-historical-pvar),
  [tutorial 43](docs/tutorials/43-parametric-var-beside-historical.md)).
- **The market-risk cube** (`MCUBE`): VaR by desk and risk class as a Pivot preset, each cell equal to the VaR engine's answer for its
  scope ([MR_CUBE_GUIDE.md](docs/guides/MR_CUBE_GUIDE.md), [tutorial 38](docs/tutorials/38-the-mr-cube-on-the-pivot-engine.md)).
- **Counterparty exposure** (EE, PFE, EPE, EEPE) from the simulator's paths, with netting and collateral on each path
  ([EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [tutorial 26](docs/tutorials/26-exposure-from-the-bank-to-a-trade.md)).
- **The pre-deal check** (`PREDEAL`, Counterparties → Pre-deal check, `POST /api/v1/risk/exposure/predeal`): a new trade copied from a
  booked one or described, added to its netting set path by path, held to the counterparty's credit limit and each tenor bucket, and
  answered **approve**, **refer** or **decline** with the reasons, the headroom and the exposure before and after; every check audited
  ([EXPOSURE_GUIDE.md section 18.9](docs/guides/EXPOSURE_GUIDE.md#189-the-pre-deal-check)).
- **FRTB-SA** (sensitivities-based method, default risk charge, residual risk add-on) for any VaR scope, its parameters as configuration
  ([FRTB_SA_GUIDE.md](docs/guides/FRTB_SA_GUIDE.md), [tutorial 30](docs/tutorials/30-frtb-sa-from-the-cases-to-a-desk.md)).
- **SIMM** initial margin per netting set and over any scope. ISDA's calibrated numbers are licensed, so the parameter file ships empty
  and the bank installs its own ([SIMM_GUIDE.md](docs/guides/SIMM_GUIDE.md#4-the-licensed-parameter-file),
  [tutorial 32](docs/tutorials/32-simm-from-the-examples-to-a-netting-set.md)).

**Explained.** Every computed number has one **Explain**: hover for a preview, click (or **E**) for the formula, the inputs and lineage,
why it moved between two dates (in steps that add up) and an Excel workbook with live formulas. VaR, FRTB-SA and SIMM each have a
**playground** (`PLAY VAR`, `PLAY FRTB`, `PLAY SIMM`) that works a small case step by step.

**Controlled.** Roles per kind, row rules and field masks apply to every screen, pivot, BI query and Flight read. Corrections, screen
designs, settings and report sign-offs go through **one Approvals queue** with four eyes; access is logged and audited. **Nothing unbuilt
looks built**: anything that depends on unbuilt work is registered in `config/under-construction.yaml` and shown with an *Under
construction* chip or hidden ([UNDER_CONSTRUCTION.md](docs/admin/UNDER_CONSTRUCTION.md)); today that covers, among others, the stored
SA-CCR and xVA figures and SA-CCR's incremental EAD in the pre-deal check.

## Analyse

- **A keyboard-driven terminal**: type-ahead over every source, linked views of any entity, impact (F8), the raw document (F9), any past
  business day, history and compare, live views that tick, one keymap (`?` lists the keys of the screen) and one search (Alt+K) over
  entities, commands, screens and Help ([USER_GUIDE.md](docs/guides/USER_GUIDE.md), [COMMAND_CATALOG.md](docs/guides/COMMAND_CATALOG.md),
  [tutorial 2](docs/tutorials/02-look-up-anything.md)).
- **Search** by value (`TRD where QUANTITY > 200m order by QUANTITY desc`), in plain words parsed without any AI service, or with a form;
  monitors and alerts on one alert engine ([tutorial 3](docs/tutorials/03-query-by-form.md)).
- **The Pivot** (`PIVOT`): any kind, search, table or measure as a pivot, every total computed from its own members, saved views, live
  pivots, Excel export with formulas, and **Pivot → Report** when a view should become a report
  ([PIVOT_GUIDE.md](docs/guides/PIVOT_GUIDE.md), [tutorial 33](docs/tutorials/33-the-pivot-engine.md)).
- **Business intelligence on the table layer**: tables and models written in Rachana (Build › Tables & models, with the YAML live
  beside the form), reports and boards, row rules across facts, governance and distribution, and the **Notebook** with one Python
  runtime on the server's sandboxed pool ([TABLES_GUIDE.md](docs/guides/TABLES_GUIDE.md), [REPORTS_GUIDE.md](docs/guides/REPORTS_GUIDE.md),
  [tutorial 44](docs/tutorials/44-virtual-tables-and-models.md), [tutorial 10](docs/tutorials/10-first-bi-report.md)).
- **One grammar.** Rachana paths and expressions are used in Sutras, search, the Pivot, BI and custom functions; the **function
  catalogue** lists every function with its rules and its Excel, SAS and MATLAB counterparts ([FUNCTIONS.md](docs/guides/FUNCTIONS.md),
  [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md)).

## Screens and Drishti Elements

- **Sutras**: a product family's screen is a short, versioned YAML file; where there is none, the inference engine lays the screen out
  from the shape of the data. One pipeline draws every entity of every kind ([BUILD_GUIDE.md](docs/guides/BUILD_GUIDE.md#11-what-a-sutra-is-and-when-one-applies)).
- **Domain packs** bring an industry's kinds, functions, Sutras, links, roles and connectors in YAML only; a pack can be made from your
  own JSON Schema or JSON Lines ([BUILD_GUIDE.md](docs/guides/BUILD_GUIDE.md), [BUILD_GUIDE.md](docs/guides/BUILD_GUIDE.md#8-a-pack-from-a-json-schema-build--new-pack),
  [tutorial 5](docs/tutorials/05-build-a-pack.md)).
- **The Build home** (`/build`) is the one place for what people build: connectors, lakes, tables and models, Sutras, packs, reports and
  boards, pivot views, functions, VaR baskets, each with what it uses and what uses it, and the drafts waiting for review
  ([tutorial 6](docs/tutorials/06-design-a-screen.md)).
- **Drishti Elements**: the console's JavaScript and CSS as eleven libraries, each with its own version under one release train:
  drishti-core (formats, theme, API client, dialogs, the filter model), drishti-inputs (date and range pickers on the bank's calendars,
  Select, multi-select, combobox, forms from a schema, the code editor), drishti-grid (with pivot, cell editing, write-back and an AG
  Grid-style adapter), drishti-chart (with maps from the world to every country's regions), drishti-data (data sources, live and Arrow
  clients), drishti-tree, drishti-graph, drishti-insight (Explain, search, Approvals), drishti-designer, drishti-renderer and
  drishti-risk (the banking module's, licensed with it) ([ELEMENTS.md](docs/architecture/ELEMENTS.md), [ELEMENTS_API.md](docs/guides/ELEMENTS_API.md)).
  **Every screen of the console is drawn with them**, and guard tests fail the build when a page draws its own select, form or
  designer. The gallery (`/elements`) shows every component live.
- **The designer kit** (drishti-designer): the layout canvas, palette, inspector, outline, the YAML pane kept in step with the model,
  and the designer shell (Visual / YAML / Both, versions, validate, preview, history, impact, propose for review). Every designer and
  editor in Drishti (the Sutra designer, the BI report designer, the theme designer, the table and model editors) is built from it.
- **Distribution, never from a CDN**: the libraries ship as two files for one page (`drishti.py elements pack`), a release archive with
  SRI hashes, npm tarballs for your own registry, a WebJar for your own Maven repository (`drishti.py elements dist`), or from the Drishti
  server you run. The same sources make the same bytes ([EMBED_GUIDE.md](docs/guides/EMBED_GUIDE.md#13-drishti-elements-libraries-in-your-own-app)).

## Data in: connectors, lakes and feeds

- **Connectors**: Delta Lake and Apache Iceberg (Drishti's own engines, no Hadoop, local disk or S3), JDBC databases, DuckDB, MongoDB,
  Redis, Aerospike, Cassandra, Kafka, ActiveMQ, RabbitMQ, files, S3, REST and public feeds
  ([CONNECTORS.md](docs/connectors/CONNECTORS.md), [tutorial 4](docs/tutorials/04-connect-your-data.md)). The plugins,
  `plugins/drishti-plugin-*/`: `demo`, `file`, `rest`, `jdbc`, `delta`, `iceberg`, `iceberg-external`, `duckdb`, `mongodb`, `redis`, `aerospike`, `cassandra`, `kafka`, `activemq`, `rabbitmq`, `s3`, `feeds`, `news`, `yfinance`.
- **Lakes, each in its own folder**, each described by one lake file (`config/lakes/<lake>.yaml`: where it is, every table's partitions,
  columns, load mode and retention):

  | Lake | Holds |
  |---|---|
  | `demo` | the demo data, built from the committed test data (`drishti.py lake demo`); demo and real data never mix |
  | `risklake` | a bank's own risk data (shipped empty) |
  | `capitalmarket` | prices, curves, volatilities, US equities and funds |
  | `worldlake` | the World Bank, the IMF, the OECD, central banks' and national statistics' series |
  | `newslake` | official press releases, GDELT world events, market headlines |
  | `referencelake` | core reference data: GLEIF's legal entities |
  | `regulators` | the SEC, the Federal Reserve, OSFI and other central banks (shipped empty) |

  The banking module adds the official VaR and exposure records (`config/lakes/banking/`) ([DATA_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md),
  [tutorial 45](docs/tutorials/45-load-your-own-data-into-a-new-lake.md)).
- **The load rules**: JSON Lines is the data standard. A plain file is one table and one business date (`<name>.<YYYY-MM-DD>.jsonl.gz`);
  a `.ready` file says the file is complete and names its lake; a tar archive carries several tables and dates and loads all or nothing.
  Every row carries its load id, so `drishti.py lake undo <load-id>` removes exactly one load. The **data inbox** loads what your ETL
  drops, and the parallel Java loader handles large loads ([tutorial 36](docs/tutorials/36-load-files-with-the-data-inbox.md)).
- **drishti-feeds** pulls external data into the lakes every day: one feed file per source (`config/feeds/<feed>.yaml`: datasets, target
  lake, schedule, licence), incremental pulls, a polite client, a run ledger, three load routes (the Java loader, the Python loader, the
  inbox), `drishti.py feed list|run|status|schedule`, and Admin → Data loads → Feeds with an alert when a run fails. The data goes under
  `data/datafeeds/`. Feeds: World Bank WDI, central banks, Statistics Canada, OECD, official news, GDELT, GLEIF, US equities, funds,
  market data and, for internal use only, the IMF forecasts, Yahoo prices and market headlines ([EXTERNAL_DATA.md](docs/guides/EXTERNAL_DATA.md)).
- **In-memory lakes** (`mem://`) for data that never touches disk, such as news ([IN_MEMORY_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md#13-the-in-memory-lake)).
- **Data contracts**: one per mnemonic, stating what the upstream engines deliver and what Drishti answers ([docs/contracts/](docs/contracts/README.md)).

## Data out

- **Arrow Flight and Flight SQL** (off as shipped): data, BI models, pivot views and VaR scenario P&L vectors to Python, Java, JDBC and ODBC
  clients, over TLS, read only, with the reader's entitlements and masks ([FLIGHT_GUIDE.md](docs/guides/FLIGHT_GUIDE.md),
  [tutorial 35](docs/tutorials/35-data-out-with-arrow-flight.md)). Single-node reads are tested; the three-node end to end is under
  construction. Client examples: [examples/clients/](examples/clients/README.md).
- **REST API** under `/api/v1` with API tokens; the banking engines under `/api/v1/risk/…` ([API_REFERENCE.md](docs/guides/API_REFERENCE.md)).
- **Embedding**: live views, panels and reports inside other web applications ([EMBED_GUIDE.md](docs/guides/EMBED_GUIDE.md),
  [tutorial 16](docs/tutorials/16-embed-drishti.md)).

## Operate

- **Security**: passwords or OpenID Connect, roles per kind, field masks, API tokens, an access log and an audit trail; **off by default**
  for a developer machine and turned on for any shared one ([USER_MANAGEMENT.md](docs/admin/USER_MANAGEMENT.md), [tutorial 17](docs/tutorials/17-run-it-in-production.md)).
- **Admin → Configuration**: every setting in three tiers (everyday, admin, expert), with its value in effect and where it came from; a
  change is a proposal a second administrator approves. Every setting is listed in [CONFIGURATION.md](docs/admin/CONFIGURATION.md).
- **Modules and engines on or off** per node ([OPERATIONS.md section 22](docs/admin/OPERATIONS.md#22-modules-on-or-off); Admin → Engines,
  [section 21](docs/admin/OPERATIONS.md#21-compute-engines-on-or-off)).
- **Clustering** (Drishti\*net, off by default): servers join by gossip, any member answers any request, VaR shards across members
  ([OPERATIONS.md section 18](docs/admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one), [tutorial 19](docs/tutorials/19-a-cluster-of-three.md)).
- **Linear algebra**: OpenBLAS is bundled for Linux and Windows x86-64, **off as shipped**; turn it on per node after measuring
  ([OPERATIONS.md section 2.1](docs/admin/OPERATIONS.md#21-server-requirements-native-libraries-blas)).
- **Deployment** by hand, with systemd, Docker Compose ([deploy/docker/compose.yaml](deploy/docker/compose.yaml)), AWS ECS, and on
  Windows with Java and Python only ([OPERATIONS.md](docs/admin/OPERATIONS.md)). Prometheus metrics; an **AI layer** and an **MCP
  client**, both off by default, with masking and audit of every call ([AI.md](docs/admin/AI.md)).

## Run it

[GETTING_STARTED.md](docs/guides/GETTING_STARTED.md) installs and starts Drishti from a clean machine, and
[tutorial 1](docs/tutorials/01-first-15-minutes.md) walks through your first quarter of an hour. The short version, from the repository
root, with a 64-bit Java 21 (JDK) and [uv](https://docs.astral.sh/uv/) for the console's Python (Maven ships with the repository):

```bash
# 1. Build the runnable server (the core with the domain modules this build ships)
./mvnw -q package -DskipTests -pl drishti-app -am

# 2. The console's Python environment (it also runs tools/drishti.py)
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt

# 3. The demo lake, from the committed test data in data/testdata
drishti-console/.venv/bin/python tools/drishti.py lake demo

# 4. The server on :18480 (native libraries unpack in its own folder, not /tmp)
mkdir -p data/tmp/server
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar

# 5. In a second terminal: the console on http://localhost:17480
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

Then open http://localhost:17480/t and try `TRD MX-20000001`, `VAR BANK`, `EXP BANK`, `PREDEAL` and `PIVOT`. Security is off in this
start, which is meant for one developer machine; the tutorials run with sign-in
([GETTING_STARTED.md, Step 8](docs/guides/GETTING_STARTED.md#step-8--sign-in-and-change-the-admin-password)).

- **The command line** is `tools/drishti.py`, run with the console's Python: lakes and loads (`lake demo|build|check|undo`), feeds
  (`feed run|status|schedule`), Elements (`elements pack|dist`), approvals, VaR, exposure and more ([CLI_GUIDE.md](docs/guides/CLI_GUIDE.md)).
- **The stack**: a ready-made local three-server Drishti\*net cluster with its consoles, from [drishti-stack/](drishti-stack/README.md).
- **Other ways**: [Windows](docs/guides/GETTING_STARTED.md#drishti-on-windows) (`tools/windows/start-server.ps1`; Java and Python only),
  [Docker](docs/guides/GETTING_STARTED.md#running-everything-in-docker-optional) and the
  [IDEs](docs/guides/GETTING_STARTED.md#running-from-intellij-idea-and-pycharm).
- **Lenses, not editions**: to run an instance as one lens set `drishti.edition` (for example `risk`); offer several with `drishti.lenses`.
  A site's own edition file moves to `config/lenses/<module>/` ([LENSES.md section 14](docs/admin/LENSES.md#14-formerly-editions-upgrading)).

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

The map of every document is [docs/README.md](docs/README.md); the console's Help centre holds the same guides, and shows each person
only those of the modules they may use.

| You want to… | Start with |
|---|---|
| use Drishti | [USER_GUIDE.md](docs/guides/USER_GUIDE.md), [VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), [EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [PIVOT_GUIDE.md](docs/guides/PIVOT_GUIDE.md), [REPORTS_GUIDE.md](docs/guides/REPORTS_GUIDE.md) |
| work with economies and the public sector | [PUBLIC_SECTOR_GUIDE.md](docs/guides/PUBLIC_SECTOR_GUIDE.md), [FUNDS_GUIDE.md](docs/guides/FUNDS_GUIDE.md), [EXTERNAL_DATA.md](docs/guides/EXTERNAL_DATA.md) |
| add data or an industry | [DATA_GUIDE.md](docs/guides/DATA_GUIDE.md), [DATA_LAKE_GUIDE.md](docs/guides/DATA_LAKE_GUIDE.md), [BUILD_GUIDE.md](docs/guides/BUILD_GUIDE.md), [TABLES_GUIDE.md](docs/guides/TABLES_GUIDE.md) |
| build screens or use the libraries | [BUILD_GUIDE.md](docs/guides/BUILD_GUIDE.md#11-what-a-sutra-is-and-when-one-applies), [EMBED_GUIDE.md](docs/guides/EMBED_GUIDE.md#13-drishti-elements-libraries-in-your-own-app), [ELEMENTS_API.md](docs/guides/ELEMENTS_API.md) |
| run and administer it | [GETTING_STARTED.md](docs/guides/GETTING_STARTED.md), [OPERATIONS.md](docs/admin/OPERATIONS.md), [LENSES.md](docs/admin/LENSES.md), [CONFIGURATION.md](docs/admin/CONFIGURATION.md) |
| develop on it | [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md), [DOMAIN_MODULES.md](docs/architecture/DOMAIN_MODULES.md), [API_REFERENCE.md](docs/guides/API_REFERENCE.md), [CLI_GUIDE.md](docs/guides/CLI_GUIDE.md) |

**Learn by doing**: [the tutorials](docs/tutorials/README.md) run on the shipped demo data and show the UI, the command line and the YAML
at every step. **Where it's going**: [IMPLEMENTATION_PLAN.md](docs/architecture/IMPLEMENTATION_PLAN.md#the-roadmap-from-2911c-product-owner-2026-11-04-supersedes-every-later-release-table-in-this-file).
What changed and when: [RELEASE_NOTES.md](RELEASE_NOTES.md) and [CHANGELOG.md](CHANGELOG.md).

## Project facts

- **Server**: Java 21 (64-bit), Spring Boot on virtual threads, Maven modules that depend in one direction (the core imports no domain,
  no domain another; a test fails the build otherwise); identity and user content in SQLite by default or PostgreSQL, one complete schema
  file per database and no migrations.
- **Console**: Python, FastAPI and Jinja2, with each domain module's part in `drishti-console/plugins/<module>/`; every front-end asset is
  vendored or one of the Elements libraries (no CDN) under a strict content security policy; it works on phones.
- **Contributing**: work on `develop`; `./mvnw -q verify` and the console tests
  (`drishti-console/.venv/bin/python -m pytest -q drishti-console/tests`) must pass; no source file over 1,500 lines; every file carries
  the copyright header (`python3 tools/license_headers.py --fix`); every change updates the documents it affects and the changelog in
  the same push.

## By the numbers

<!-- code-volume:begin -->

*Measured 2026-11-08 from the files the repository tracks (`python3 tools/docs/code_volume.py`; sample data and vendored libraries not counted).*

| Language | Lines | Files |
|---|---|---|
| Java, product | 431k | 2,159 |
| Java, tests | 163k | 1,031 |
| Python, product (console, tools, loaders, feeds) | 142k | 726 |
| Python, tests | 105k | 597 |
| JavaScript, product (console and Elements) | 125k | 486 |
| JavaScript, tests | 4k | 22 |
| CSS | 19k | 114 |
| HTML (templates and pages) | 34k | 328 |
| YAML: configuration, Sutras, data contracts | 111k | 1,411 |
| Markdown: guides, tutorials, design | 272k | 489 |

About **750k lines of product code** and **273k lines of tests** (36% of the product code), with **272k lines of documentation**.

| What | Count |
|---|---|
| Maven modules | 52 (19 of them connector plugins) |
| Domain modules | 4: core, banking, economy, capitalmarkets |
| Lenses | 10: risk, trading, market, cro, mr-cube, ccr-cube, world, publicsector, cio, oversight |
| Functions a module declares | 106 (47 banking, 32 capitalmarkets, 27 economy); every other command word is the core's |
| Console routes | 644 |
| Server REST handlers | 751 |
| Drishti Elements | 11 libraries, 186 ES modules |
| Domain packs | 17: banking 4, capitalmarkets 2, core 3, economy 8 |
| Sutras in the shipped packs | 248 |
| Connector plugins | 19 |
| Feeds | 13 |
| Lake files | 9 |
| Test files | 1,511: Java 948, console 247, browser 177, tools 117, JavaScript 22 |
| Documents under docs/ | 360 (35 guides, 41 tutorials) |

<!-- code-volume:end -->

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
