<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti (दृष्टि, "sight") is a live, keyboard-driven terminal for any kind of record. Type an identifier and it renders the entity
behind it, a swap, a counterparty, a netting set, a fund, as a dense, linked view that ticks while the source changes, from whichever
system holds it. Every number links to what it depends on, any past business day is one click away, and every table sorts, filters
and walks with the keyboard.

The idea behind it: **a screen is a paragraph, not a program.** Nobody codes a screen per product. A product family gets a **Sutra**, a
short, versioned YAML file in the **Rachana** (रचना, *composition*) screen grammar that says what the screen shows; where there is no
Sutra, or a Sutra leaves gaps, an **inference engine** reads the shape of the data and lays out the rest. One generic pipeline draws
every entity of every kind. **Domain packs** bring an industry (its kinds, mnemonics, Sutras, vocabulary, links, roles and connectors);
the core knows no industry.

On the same data, **business intelligence** (Rūpaka) gives analysts models, reports and live boards, and risk measures such as **VaR**
and **counterparty exposure** are computed on demand, for any scope from the whole bank to an ad-hoc set of trades, from trade-level
inputs, rather than stored as finished numbers.

```
TRD MX-20000001 <GO>                          →  the trade: terms, legs, cashflows, curve, DV01 ladder, links
NSET NS-SUMMIT-NY <GO>                        →  the netting set: member trades, CSA, collateral
VAR BANK <GO>                                 →  VaR and ES of the bank, down the hierarchy to a trade
EXP CPTY:CP-ALDERSHOT <GO>                    →  exposure computed from the paths: EE, PFE, EPE, EEPE
FORM trading <GO>                             →  a form for the trading pack's kinds that writes the search for you
TRD where QUANTITY > 200m order by QUANTITY desc   →  every trade over 200m, largest first
amended trades over 5m in BOOK-RATES-3, biggest first   →  plain words: Drishti shows the search it makes
```

**Learn it by doing:** [twenty-nine tutorials](docs/tutorials/README.md), from your first 15 minutes to VaR on Iceberg, each showing
the UI, the command line and the YAML at every step, with real screenshots. The [documentation map](docs/README.md) lists every guide.

## Contents

[Features](#features) · [How it works](#how-it-works) · [Try it](#try-it) · [Measured performance](#measured-performance) ·
[Documentation map](#documentation-map) · [Project facts](#project-facts)

## Features

### Look things up

- **A command line** with type-ahead, as on a Bloomberg terminal: mnemonics, recent entities and matches from every source as you type.
  One match opens; several give a **pick list** with each kind's key fields (`TRD MX-200000`, `CPTY north`).
- **Views** of any entity: a title line, a strip of key figures, panels of 22 kinds (tables, key-value, ladders, curves, surfaces,
  waterfalls, histograms, candlesticks, pivots, graphs, timelines, maps and more), function keys, and the raw document (F9)
  ([PANELS.md](docs/guides/PANELS.md)). Tables filter like Excel and chart in place.
- **Links** between entities, resolved through the entity graph, with badges; **impact** (F8) shows what depends on an entity, what it
  rolls into and the amount at stake.
- **The Pivot tab**: an Excel-style pivot of a table or of a search's every match, computed on the server, saved per user and promoted to
  the Sutra by an author.
- Guides: [USER_GUIDE.md](docs/guides/USER_GUIDE.md), [COMMAND_CATALOG.md](docs/guides/COMMAND_CATALOG.md). Tutorial:
  [2, Look up anything](docs/tutorials/02-look-up-anything.md).

### Search and forms

- **Search by value** (`TRD where QUANTITY >= 250m and PRODUCT_GROUP = 'Rates' order by QUANTITY desc limit 20`) over every source of a
  kind ([USER_GUIDE.md › Search by value](docs/guides/USER_GUIDE.md#search-by-value)).
- **Plain words** (`amended trades over 5m in BOOK-RATES-3, biggest first`): a deterministic parser shows the search it makes, part by
  part, before anything runs. No AI service is involved.
- **Query by form** (`FORM <pack> <GO>`, `/forms`): typed inputs per kind (amounts, dates, multi-select dropdowns, pasted lists of ids)
  that write the search; name it, watch it as a monitor, schedule it ([USER_GUIDE.md](docs/guides/USER_GUIDE.md#query-by-form),
  [tutorial 3](docs/tutorials/03-query-by-form.md)).
- **The data map** (`DATAMAP <GO>`): every kind of data the packs hold, how much there is today, and how the kinds join, each join
  checked against the data ([USER_GUIDE.md › The data map](docs/guides/USER_GUIDE.md#the-data-map)).

### Over time and live

- **Business dates**: Live, or any past business day; views, links, search and impact all follow the date. **Known at** shows a day as
  it was known at an earlier time ([USER_GUIDE.md › Business dates](docs/guides/USER_GUIDE.md#business-dates-live-or-a-day-in-the-past)).
- **History and compare**: a range after an id (`TRD 1000015 30`, `3M`, `YTD`) gives the entity's history with what moved tinted;
  compare an entity or a search between two dates, field by field; every view says how fresh its source is
  ([tutorial 21](docs/tutorials/21-an-entity-over-time.md), [HISTORY_QUERIES.md](docs/architecture/HISTORY_QUERIES.md)).
- **Live views** tick over server-sent events; changed values flash ([LIVE.md](docs/architecture/LIVE.md)).
- **Monitors** (live watchlists), **alerts** (rules checked on every change, with a bell and history) and **workspaces** (several live
  views on one screen, a pane following another's selection) ([USER_GUIDE.md › Monitors](docs/guides/USER_GUIDE.md#monitors),
  [Alerts](docs/guides/USER_GUIDE.md#alerts), [Workspaces](docs/guides/USER_GUIDE.md#workspaces)).

### Business intelligence (Rūpaka)

- **Datasets and models**: tables from pack kinds, relationships, measures, several fact tables in one model, row rules and masks per
  reader, on an embedded query engine ([BI_GUIDE.md](docs/guides/BI_GUIDE.md), [DATASETS.md](docs/guides/DATASETS.md),
  [tutorial 18](docs/tutorials/18-several-datasets-in-one-report.md)).
- **Reports in BI Studio**: a designer, slicers, cross-filtering, drill-through, maps from the world to a region, conditional formatting,
  themes, phone and tablet layouts, statements laid out on paper ([REPORTS.md](docs/guides/REPORTS.md),
  [tutorial 10](docs/tutorials/10-first-bi-report.md), [tutorial 20](docs/tutorials/20-statements-and-field-parameters.md)).
- **Live BI and boards**: reports that follow the ticks, live tables, boards of tiles in kiosk mode
  ([tutorial 12](docs/tutorials/12-live-bi-and-boards.md)); long questions as background runs.
- **Governance**: versions and diffs, review with four eyes, certification, lineage, bundles between environments
  ([BI_GOVERNANCE.md](docs/guides/BI_GOVERNANCE.md), [tutorial 13](docs/tutorials/13-govern-bi.md)).
- **Python and AI in BI**: Python measures, visuals and Transforms, **Analyse** (slice, Drishti SQL and Python on your masked extract);
  with AI switched on, a report from words, ask the data, explain a number ([PYTHON_BI.md](docs/guides/PYTHON_BI.md),
  [USER_GUIDE.md](docs/guides/USER_GUIDE.md#analyse-slice-sql-and-python), [tutorial 14](docs/tutorials/14-python-and-analyse.md),
  [tutorial 15](docs/tutorials/15-ai-in-drishti.md)).

### Risk, computed on demand

- **VaR and ES for any scope**: the bank, a desk, a book, a counterparty, a stored portfolio, a basket or an ad-hoc set written as a
  scope (`BANK - DESK:CREDIT`), drilled from the bank to a trade with contributions that add up
  ([VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), [tutorial 25](docs/tutorials/25-var-from-the-bank-to-a-trade.md)).
- **Inputs as your pricing systems deliver them**: P&L vectors or scenario PVs per trade or position, positions derived by the pack's
  rules; hierarchies from rows or **configured from the trades' columns** (such as limit letters) with VaR limits per node; any
  reporting currency ([VAR_GUIDE.md › Inputs](docs/guides/VAR_GUIDE.md#15-inputs-from-upstream-engines)).
- **Intraday**: updates from files (`drishti.py var intraday --load`) or a Kafka topic, applied on top of the morning run; the
  **official** end-of-day record; captures and compare; history and **backtests** (Kupiec, Christoffersen); a pre-deal **what-if**.
- **Counterparty exposure** (EE, PFE, EPE, EEPE) for any scope, from the simulator's paths, with netting, collateral on each path and a
  pre-deal what-if ([EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md),
  [tutorial 26](docs/tutorials/26-exposure-from-the-bank-to-a-trade.md)); groupings and analytics computed the same way
  ([GROUPINGS_ANALYTICS_GUIDE.md](docs/guides/GROUPINGS_ANALYTICS_GUIDE.md)).
- **Sharded across the cluster** (off by default): a date's trades cut into shards by legal entity and desk, each owned by members of
  Drishti\*net, every member still answering every scope, measured equal to one server to the cent; never a partial answer
  ([VAR_SHARDING.md](docs/architecture/VAR_SHARDING.md)).

### Data in

- **Connectors**, each one YAML file in `config/connectors/`, changed live in Admin → Connectors, with TLS and an outbound proxy. The
  plugin families: lakes (Delta Lake, Apache Iceberg, other teams' Iceberg tables), databases (PostgreSQL and other JDBC databases,
  DuckDB, MongoDB, Redis, Aerospike, Cassandra), messaging (Kafka, ActiveMQ, RabbitMQ), storage and services (files, Amazon S3, REST),
  and public feeds, news and quotes ([CONNECTORS.md](docs/connectors/CONNECTORS.md),
  [tutorial 4](docs/tutorials/04-connect-your-data.md)).
- **Delta Lake and Apache Iceberg**, both read and written by Drishti's own engines without Hadoop, on local disk (Windows included) or
  S3; Delta is the default, Iceberg a full alternative, and your own Databricks or Iceberg tables can be read too
  ([EXTERNAL_LAKES.md](docs/guides/EXTERNAL_LAKES.md), [DRISHTI_ICEBERG.md](docs/architecture/DRISHTI_ICEBERG.md),
  [tutorial 29](docs/tutorials/29-var-on-iceberg.md)).
- **JSON Lines is the data standard**: `drishti.py lake build` loads it into Delta or Iceberg (Python by default); for large loads,
  `--engine java` runs the **parallel Java loader**, multi-threaded, memory-bounded, one commit per business date
  ([DATA_FORMATS.md](docs/guides/DATA_FORMATS.md), [PARALLEL_LOADER.md](docs/architecture/PARALLEL_LOADER.md),
  [TOOLS.md](docs/guides/TOOLS.md)). One call tells Drishti a load has landed ([DATA_LOADS.md](docs/guides/DATA_LOADS.md),
  [tutorial 7](docs/tutorials/07-load-data-daily.md)).
- **Public-data packs**: world economy, US equities from SEC EDGAR, GLEIF legal entities, US funds and ETFs from SEC filings, each with
  its own refresh script in `tools/publicdata/` and its licence and internal-only rules stated
  ([PUBLIC_DATA.md](docs/guides/PUBLIC_DATA.md), [FUNDS_GUIDE.md](docs/guides/FUNDS_GUIDE.md),
  [tutorial 28](docs/tutorials/28-funds-and-etfs.md)).
- **Data contracts**: a contract per mnemonic states what the upstream engines write, where and when, and what Drishti answers
  ([docs/contracts/](docs/contracts/README.md)); `DATAMAP` shows the result.

### Build without code

- **Domain packs**: eleven ship in `config/packs/`, seven on by default (`core`, `market-data`, `trading`, `counterparty-risk`,
  `market-risk`, `world-economy`, `news-and-markets`); `us-equities`, `gleif`, `funds` and the sample `compute-demo` are opt-in. Packs
  inherit, load and unload from Admin → Packs, and install from a signed registry with rollback ([PACKS.md](docs/guides/PACKS.md),
  [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md)).
- **A pack from your data**: JSON Schema files or a folder of JSON Lines in, a pack out (`drishti.py pack make`, or Build → New pack)
  ([SCHEMA_TO_PACK.md](docs/guides/SCHEMA_TO_PACK.md), [tutorial 5](docs/tutorials/05-build-a-pack.md)).
- **Sutras** in YAML with a JSON Schema, fragments from a UI kit, panels that talk to each other, forms with drafts
  ([SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md), [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md)).
- **The Build workbench** (`/build`) and the **Sutra designer**: drag panels onto a live screen, or describe a screen in one line; a save
  is a proposal an approver reviews (four eyes) ([SCREEN_DESIGNER.md](docs/guides/SCREEN_DESIGNER.md),
  [tutorial 6](docs/tutorials/06-design-a-screen.md), [tutorial 22](docs/tutorials/22-build-a-screen-with-the-designer.md)).
- **Query-by-form design**: a pack's `form:` and `columns:` declare its forms; no form code ([USER_GUIDE.md](docs/guides/USER_GUIDE.md#query-by-form)).
- **The CLI** (`tools/drishti.py`): packs, Sutras, data, lakes, VaR, exposure and designs from a terminal or a pipeline
  ([CLI_GUIDE.md](docs/guides/CLI_GUIDE.md)); **Calc** (`Alt+C`) runs Python in the browser on the view you are looking at
  ([USER_GUIDE.md](docs/guides/USER_GUIDE.md#calc-python-on-any-view)).

### Collaboration and distribution

- **Share a view with a note**, an inbox, and discussion threads anchored to a number; everyone sees the view with their own permissions
  ([USER_GUIDE.md › Share a view](docs/guides/USER_GUIDE.md#share-a-view-with-a-note), [tutorial 8](docs/tutorials/08-share-and-collaborate.md)).
- **Scheduled reports**: a search run as you on a schedule, delivered as CSV to a folder or an approved webhook
  ([tutorial 9](docs/tutorials/09-reports.md)).
- **Subscriptions and alerts in BI**: a report as PDF, PNG, Excel or CSV by email, webhook or text, each reader on their own masks
  ([DISTRIBUTION.md](docs/guides/DISTRIBUTION.md), [tutorial 11](docs/tutorials/11-bi-that-runs-itself.md)).
- **Write-back with four eyes**: a correction proposed on a form, reviewed and confirmed, applied and shown to everyone
  ([WRITE_BACK.md](docs/guides/WRITE_BACK.md), [tutorial 24](docs/tutorials/24-correct-a-trade-and-approve-it.md)).
- **Export** any table as CSV, a document as JSON, print or PDF, and a link that reopens the view exactly as you see it.

### Embed Drishti elsewhere

- **Drishti Elements**: live views, single panels, published BI reports, *About this page* and search inside another web application
  as `<drishti-view>`, `<drishti-panel>`, `<drishti-report>`, `<drishti-about>` and `<drishti-search>` Web Components, without
  iframes, under a strict content security policy; React, Vue and Angular samples ([ELEMENTS.md](docs/guides/ELEMENTS.md),
  [tutorial 16](docs/tutorials/16-embed-drishti.md)).
- **The ViewModel contract**: every entity screen is one JSON document, with a JSON Schema served at `GET /api/v1/viewmodel/schema`, so
  scripts, golden tests and other renderers read exactly what the console draws ([VIEWMODEL.md](docs/architecture/VIEWMODEL.md)).
- **REST API, API tokens and clients**: everything under `/api/v1`, read-only revocable tokens, a Python client and Excel through
  Power Query ([API_GUIDE.md](docs/guides/API_GUIDE.md)).

### Operate

- **Clustering (Drishti\*net, off by default)**: several servers join by gossip; each keeps its own packs, connectors and data, and any
  of them answers any request with the caller's masks applied; the console fails over with no new sign-in. Members advertise real
  addresses only ([NET.md](docs/admin/NET.md), [tutorial 19](docs/tutorials/19-a-cluster-of-three.md),
  [drishti-stack/](drishti-stack/README.md) for three servers on one machine).
- **Security**: sign-in with passwords or OpenID Connect, roles per kind, field masks, API tokens, an access log of who looked at what,
  commands switched off for everyone, and an audit trail ([USER_MANAGEMENT.md](docs/admin/USER_MANAGEMENT.md)).
- **Admin and Health**: packs, connectors, lakes, roles and functions from the Admin pages; Health shows sources, freshness, pools and a
  **Memory and files** row (direct and mapped memory, heap, threads, open files); Prometheus metrics and a Grafana dashboard
  ([OPERATIONS.md › Admin → Health](docs/admin/OPERATIONS.md#132-admin--health)).
- **Deployment**: one machine by hand or with systemd, Docker Compose ([deploy/docker/compose.yaml](deploy/docker/compose.yaml)), AWS on ECS (Fargate or EC2)
  with S3 and EFS ([AWS_DEPLOYMENT.md](docs/architecture/AWS_DEPLOYMENT.md)), and Windows with Java and Python only
  ([GETTING_STARTED.md › Windows](docs/guides/GETTING_STARTED.md#drishti-on-windows)). Production steps:
  [tutorial 17](docs/tutorials/17-run-it-in-production.md).
- **Performance**: virtual threads, layouts cached by shape, memory-mapped hot files for VaR and exposure, tuning knobs and how to
  measure ([PERFORMANCE.md](docs/admin/PERFORMANCE.md), [Measured performance](#measured-performance) below).
- An **AI layer** and an **MCP client**, both off by default, with masking and audit of every call
  ([AI.md](docs/admin/AI.md#1-model-providers), [AI.md](docs/admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)).

## How it works

![How a command becomes a screen: sources such as trade systems, Delta and Iceberg lakes, databases, Kafka, files and S3 feed the
connector plugins; the server's view pipeline runs seven steps (command, fetch, match Sutra, shape, layout from Sutra and inference,
links, bind) into a ViewModel in JSON, which the web console, Drishti Elements in other applications and the REST API and CLI render.
Beneath: the VaR and exposure engines sharded across Drishti*net, BI (Rūpaka) on the same data, and the domain packs in
YAML.](docs/img/readme/drishti-flow.svg)

**The view pipeline.** Every view, live or not, is built in the same seven stages: the command is parsed into a kind and an id; the
source router fetches the document from the connector that holds it for the chosen date; the kind's Sutra is matched; the document's
shape is fingerprinted; the layout (Sutra plus inference) is taken from a cache keyed by Sutra version, kind and shape, or made once;
linked entities are fetched in parallel within a time budget; and every panel is bound in parallel. Each stage works on immutable inputs
and each view reports its own timings ([ARCHITECTURE.md › The view pipeline](docs/architecture/ARCHITECTURE.md#4-the-view-pipeline)).

**Generic rendering.** A Sutra is not compiled and no code is generated from it: the server interprets the YAML, and the console draws
every ViewModel with one page template and one macro per panel kind, with the same scripts on every page. There is no screen per
product, kind or mnemonic; a pack is YAML only. The few hand-built pages and why they exist are listed too
([ARCHITECTURE.md › How a screen is made](docs/architecture/ARCHITECTURE.md#7a-how-a-screen-is-made-generic-not-bespoke)).

**The ViewModel contract.** What the server sends is neither HTML nor the raw record but the screen, laid out and bound, as JSON:
title, strip, panels, links, keys, provenance and timings. Live updates are patches to it in frames over server-sent events. Its JSON
Schema is generated from the Java records and checked against real views ([VIEWMODEL.md](docs/architecture/VIEWMODEL.md)).

**The module layout.** One Spring Boot application on virtual threads, built from Maven modules that depend in one direction: the
plugin SPI (`drishti-api`, no Spring), the Rachana grammar and inference, the entity graph, the engine, identity, packs, the lake
engines (`drishti-deltalake`, `drishti-iceberg`, `drishti-lakeio`, `drishti-lakeload`), compute, Drishti\*net, the AI layer, and one
module per connector. The console is a separate Python web application
([ARCHITECTURE.md › Module layout](docs/architecture/ARCHITECTURE.md#13-module-layout-java)).

The connector plugins, `plugins/drishti-plugin-*/`: `demo`, `file`, `rest`, `jdbc`, `delta`, `aerospike`, `cassandra`, `redis`, `mongodb`, `iceberg`, `iceberg-external`, `duckdb`, `feeds`, `news`, `yfinance`, `kafka`, `activemq`, `rabbitmq`, `s3`.

Key design documents: [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) (the whole picture and its decisions, with the
[ADRs](docs/architecture/adr/README.md)), [VAR_SHARDING.md](docs/architecture/VAR_SHARDING.md),
[PARALLEL_LOADER.md](docs/architecture/PARALLEL_LOADER.md), [DRISHTI_NET.md](docs/architecture/DRISHTI_NET.md),
[ELEMENTS.md](docs/architecture/ELEMENTS.md), [CONTENT_STORE.md](docs/architecture/CONTENT_STORE.md) (user content every node can
reach), [INFERENCE.md](docs/architecture/INFERENCE.md), [RUPAKA.md](docs/architecture/RUPAKA.md).

## Try it

New to Drishti? **[GETTING_STARTED.md](docs/guides/GETTING_STARTED.md)** installs and starts it from a clean machine, and
**[tutorial 1](docs/tutorials/01-first-15-minutes.md)** takes you through your first quarter of an hour. The short version, from the
repository root (Java 21 or newer; Python 3.13, [the minimum and why](docs/guides/GETTING_STARTED.md#step-1--install-the-prerequisites);
[uv](https://docs.astral.sh/uv/)):

```bash
# 1. Build (Maven comes with the repository)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q package -DskipTests                 # or: ./mvnw -q verify  (also runs every test)

# 2. The console's Python environment (also runs tools/drishti.py)
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt

# 3. The demo lakes from the committed test data: sixty business days, 85 tables (about 15 minutes, 3.4 GB)
drishti-console/.venv/bin/python tools/drishti.py lake demo

# 4. The server on :18480 (native libraries unpack in its own folder, not /tmp)
mkdir -p data/tmp/server
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar

# 5. In a second terminal: the console on http://localhost:17480
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

Checks: `curl -s localhost:18480/actuator/health` and `curl -s localhost:17480/healthz` answer `{"status":"UP"…}`. The server loads the
seven default packs; `DRISHTI_PACKS` chooses others ([PACKS.md](docs/guides/PACKS.md)). Sign-in is off for local development; to turn it
on, see [GETTING_STARTED.md, Step 8](docs/guides/GETTING_STARTED.md#step-8--sign-in-and-change-the-admin-password).

**What to try first**, at http://localhost:17480/t:

- `TRD MX-20000001`, then F8 for its impact and F9 for the raw document;
- `CPTY CP-NORTHBRIDGE`, then follow a link to its agreement and its country's economy;
- a past date in the top bar, and `TRD 1000015 30` for a trade's last thirty business days;
- `amended trades over 5m, biggest first`, then `FORM trading`;
- `VAR BANK` and `EXP BANK`, then click down to a trade;
- `BI` for the catalogue of reports and boards.

Other ways to run it: [Windows](docs/guides/GETTING_STARTED.md#drishti-on-windows) (PowerShell; Java and Python only),
[Docker](docs/guides/GETTING_STARTED.md#running-everything-in-docker-optional), the
[IDEs](docs/guides/GETTING_STARTED.md#running-from-intellij-idea-and-pycharm), and a three-server cluster from
[drishti-stack/](drishti-stack/README.md).

## Measured performance

Measured on one developer workstation; the method and every figure are in the linked documents.

| Data | Measure | Result |
|---|---|---|
| 35.7 million trades (ENDUR, CALYPSO, MUREX over 30 dates), 8 GB heap | 50 simultaneous users against the server | 179 requests/s, 0 failures; a trade p50 / p95 0.16 / 0.73 s; heap 3.7 GB ([PERFORMANCE.md](docs/admin/PERFORMANCE.md#millions-of-trades-a-day-and-many-users-2026-10-18)) |
| the same | 50 users through the console | 182 pages/s, 0 failures; form search p50 0.21 s |
| the same | a day of 400,000 trades restated while 30 users read | load 2 min 54 s; 202 requests/s, 0 failures |
| 8.2 million trades over 7 dates, 8 GB heap | 50 users: server / console | 146 / 69 requests/s, 0 failures; heap 1.8 GB ([SCALE_STRATEGY.md](docs/architecture/SCALE_STRATEGY.md)) |
| 1 million trades a day | search over every trade, 2,017 matches | 134 ms ([PERFORMANCE.md](docs/admin/PERFORMANCE.md#a-book-of-a-million-trades-a-day)) |
| 1 million trades in memory (VaR) | a node's VaR and ES / an ad-hoc scope of 50,000 positions / the whole bank summed from its trades | under 1 ms / 52 ms / 58 ms ([VAR_GUIDE.md section 27](docs/guides/VAR_GUIDE.md#27-sizes-performance-and-the-benchmark)) |
| 10,000 positions × 510 scenarios (53.7 MB of JSON Lines), 24 cores | the parallel Java loader into Delta, warm | 0.19 to 0.29 s, 34,000 to 52,000 rows/s ([PARALLEL_LOADER.md](docs/architecture/PARALLEL_LOADER.md)) |
| three real servers, 20 users, one node killed and restarted | the console's failover; answers checked against one server | moved in 0.27 s, nobody signed out; 9,362 answers, 0 wrong ([NET.md section 13](docs/admin/NET.md#13-measured-the-soak-on-real-processes)) |
| warm cache | view built on the server / over HTTP, p99; live tick to frame, p99 | 0.6 ms / 3.0 ms; 11.2 ms ([PERFORMANCE.md](docs/admin/PERFORMANCE.md#targets-and-measured-numbers)) |

The stores compared at several sizes: [SCALE_BENCHMARK.md](docs/admin/SCALE_BENCHMARK.md).

## Documentation map

The full map, every document by what you want to do, is [docs/README.md](docs/README.md); the console's Help centre holds the same
guides. The key ones by audience:

| You are… | Guides | Tutorials |
|---|---|---|
| **a user** of the terminal | [USER_GUIDE.md](docs/guides/USER_GUIDE.md), [COMMAND_CATALOG.md](docs/guides/COMMAND_CATALOG.md), [USER_GUIDE.md](docs/guides/USER_GUIDE.md#query-by-form), [VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), [EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [FUNDS_GUIDE.md](docs/guides/FUNDS_GUIDE.md) | [1](docs/tutorials/01-first-15-minutes.md), [2](docs/tutorials/02-look-up-anything.md), [3](docs/tutorials/03-query-by-form.md), [8](docs/tutorials/08-share-and-collaborate.md), [9](docs/tutorials/09-reports.md), [21](docs/tutorials/21-an-entity-over-time.md), [25](docs/tutorials/25-var-from-the-bank-to-a-trade.md), [26](docs/tutorials/26-exposure-from-the-bank-to-a-trade.md), [27](docs/tutorials/27-groupings-and-analytics.md), [28](docs/tutorials/28-funds-and-etfs.md) |
| **an analyst or BI author** | [BI_GUIDE.md](docs/guides/BI_GUIDE.md), [DATASETS.md](docs/guides/DATASETS.md), [REPORTS.md](docs/guides/REPORTS.md), [PYTHON_BI.md](docs/guides/PYTHON_BI.md), [USER_GUIDE.md](docs/guides/USER_GUIDE.md#analyse-slice-sql-and-python), [SQL.md](docs/guides/SQL.md), [BI_GOVERNANCE.md](docs/guides/BI_GOVERNANCE.md), [DISTRIBUTION.md](docs/guides/DISTRIBUTION.md) | [10](docs/tutorials/10-first-bi-report.md), [11](docs/tutorials/11-bi-that-runs-itself.md), [12](docs/tutorials/12-live-bi-and-boards.md), [13](docs/tutorials/13-govern-bi.md), [14](docs/tutorials/14-python-and-analyse.md), [15](docs/tutorials/15-ai-in-drishti.md), [18](docs/tutorials/18-several-datasets-in-one-report.md), [20](docs/tutorials/20-statements-and-field-parameters.md) |
| **a pack or Sutra author** | [PACKS.md](docs/guides/PACKS.md), [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md), [SCHEMA_TO_PACK.md](docs/guides/SCHEMA_TO_PACK.md), [SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md), [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md), [PANELS.md](docs/guides/PANELS.md), [SCREEN_DESIGNER.md](docs/guides/SCREEN_DESIGNER.md), [CLI_GUIDE.md](docs/guides/CLI_GUIDE.md), [data contracts](docs/contracts/README.md) | [5](docs/tutorials/05-build-a-pack.md), [6](docs/tutorials/06-design-a-screen.md), [22](docs/tutorials/22-build-a-screen-with-the-designer.md), [23](docs/tutorials/23-computed-panels.md), [24](docs/tutorials/24-correct-a-trade-and-approve-it.md) |
| **an operator or administrator** | [GETTING_STARTED.md](docs/guides/GETTING_STARTED.md), [OPERATIONS.md](docs/admin/OPERATIONS.md), [CONFIGURATION.md](docs/admin/CONFIGURATION.md), [USER_MANAGEMENT.md](docs/admin/USER_MANAGEMENT.md), [NET.md](docs/admin/NET.md), [CONNECTORS.md](docs/connectors/CONNECTORS.md), [DATA_LOADS.md](docs/guides/DATA_LOADS.md), [LAKE_MAINTENANCE.md](docs/admin/LAKE_MAINTENANCE.md), [PUBLIC_DATA.md](docs/guides/PUBLIC_DATA.md), [PERFORMANCE.md](docs/admin/PERFORMANCE.md), [TROUBLESHOOTING.md](docs/guides/TROUBLESHOOTING.md), [runbooks](docs/admin/runbooks/) | [4](docs/tutorials/04-connect-your-data.md), [7](docs/tutorials/07-load-data-daily.md), [17](docs/tutorials/17-run-it-in-production.md), [19](docs/tutorials/19-a-cluster-of-three.md), [29](docs/tutorials/29-var-on-iceberg.md) |
| **a developer** | [DEVELOPER_GUIDE.md](docs/guides/DEVELOPER_GUIDE.md), [API_GUIDE.md](docs/guides/API_GUIDE.md), [ELEMENTS.md](docs/guides/ELEMENTS.md), [CONNECTOR_DEVELOPER_GUIDE.md](docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md), [TOOLS.md](docs/guides/TOOLS.md), [VIEWMODEL.md](docs/architecture/VIEWMODEL.md) | [16](docs/tutorials/16-embed-drishti.md), [23](docs/tutorials/23-computed-panels.md) |
| **an architect** | [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md), [ADRs](docs/architecture/adr/README.md), [SCALE_STRATEGY.md](docs/architecture/SCALE_STRATEGY.md), [VAR_SHARDING.md](docs/architecture/VAR_SHARDING.md), [PARALLEL_LOADER.md](docs/architecture/PARALLEL_LOADER.md), [DRISHTI_NET.md](docs/architecture/DRISHTI_NET.md), [DRISHTI_ICEBERG.md](docs/architecture/DRISHTI_ICEBERG.md), [CONTENT_STORE.md](docs/architecture/CONTENT_STORE.md), [AWS_DEPLOYMENT.md](docs/architecture/AWS_DEPLOYMENT.md) | [all twenty-nine](docs/tutorials/README.md) |

## Project facts

- **Server**: Java 21 or newer (production runs 21), one Spring Boot application on virtual threads, REST under `/api/v1` with OpenAPI;
  identity and user content in SQLite by default or PostgreSQL, one complete schema file per database and no migrations.
- **Console**: Python 3.13, FastAPI and Jinja2, every front-end asset vendored (no CDN), a strict content security policy; it works on
  phones.
- **Command line**: `tools/drishti.py`, run with the console's Python.
- **Licence**: proprietary; see [Legal](#legal), [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

### Contributing rules

- Work on `develop`. `tools/drill.sh` runs every check, pushes `develop` and merges it into `main`; releases are
  tagged on `main` after it.
- Every build runs Error Prone and Spotless; `./mvnw -q verify` must pass, as must the console tests
  (`drishti-console/.venv/bin/python -m pytest -q drishti-console/tests`).
- No source file over 1,500 lines (UX templates excepted), and every file carries the copyright header
  (`python3 tools/license_headers.py --fix`). Both are enforced by tests.
- Modules depend in one direction only, controllers live only in the server, and the plugin SPI is Spring-free
  (ArchUnit).
- Front-end assets are vendored; a test rejects any external URL. Product name and legal notices come from
  configuration, never from code.
- Every change updates the documents it affects and the [changelog](CHANGELOG.md) in the same push.

What changed and when: [release notes](RELEASE_NOTES.md) · [changelog](CHANGELOG.md) · [plan](docs/architecture/IMPLEMENTATION_PLAN.md).

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
