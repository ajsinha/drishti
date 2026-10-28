<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti (दृष्टि, "sight") is a live, keyboard-driven terminal for any kind of record. Type an identifier and it renders the entity
behind it, a swap, a counterparty, a netting set, a fund, as a dense, linked, ticking view, from whichever system holds it. Nobody
codes a screen per product: a product family gets a **Sutra**, a short versioned YAML file in the **Rachana** (रचना, *composition*)
screen grammar, and an **inference engine** lays out whatever no Sutra covers. **Domain packs** bring an industry (kinds, mnemonics,
Sutras, vocabulary, links, roles, connectors); the core knows no industry. Business intelligence (**Rūpaka**) runs on the same data,
and risk measures such as **VaR** and **counterparty exposure** are computed on demand, for any scope, from trade-level inputs.

```
TRD MX-20000001 <GO>                          →  the trade: terms, legs, cashflows, curve, DV01 ladder, links
NSET NS-SUMMIT-NY <GO>                        →  the netting set: member trades, CSA, collateral
VAR BANK <GO>                                 →  VaR and ES of the bank, down the hierarchy to a trade
EXP CPTY:CP-ALDERSHOT <GO>                    →  exposure computed from the paths: EE, PFE, EPE, EEPE
TRD where QUANTITY > 200m order by QUANTITY desc   →  every trade over 200m, largest first
amended trades over 5m in BOOK-RATES-3, biggest first   →  plain words: Drishti shows the search it makes
```

**Current release: 2.9.5** (Iceberg as a full alternative to Delta, Delta still the default; VaR hierarchies of your own from the trades' columns, such as limit letters; connector files that upgrade themselves safely) ([release notes](RELEASE_NOTES.md) · [changelog](CHANGELOG.md) ·
[what shipped when](docs/architecture/IMPLEMENTATION_PLAN.md)).
**2.9.6 is in development** on `develop`, not released: a parallel Java lake loader and VaR sharded across Drishti\*net
([Unreleased in the changelog](CHANGELOG.md#unreleased-296)).

**Learn it by doing:** [twenty-nine tutorials](docs/tutorials/README.md), from your first 15 minutes to VaR on Iceberg, each with
real screenshots; the [guides](docs/README.md) are the reference.

## What you can do

**Look things up**
- A command line with type-ahead and pick lists, as on a Bloomberg terminal; views with a header, panels (tables, curves, ladders,
  surfaces, maps), linked entities and the raw document (F9); every table sorts, filters like Excel and walks with the keyboard
  ([USER_GUIDE.md](docs/guides/USER_GUIDE.md), [tutorial 2](docs/tutorials/02-look-up-anything.md)).
- **Search** by value (`TRD where …`) or in plain words, parsed deterministically with no AI service; **query by form**
  (`FORM <pack> <GO>`; [QUERY_BY_FORM.md](docs/guides/QUERY_BY_FORM.md)); the **impact** (F8) of an entity; a pivot of any table.

**Over time and live**
- Any past business day, **compare** two dates, the **history** of an entity or a field (`TRD 1000279 60`), freshness flags
  ([USER_GUIDE.md › Business dates](docs/guides/USER_GUIDE.md#business-dates-live-or-a-day-in-the-past),
  [tutorial 21](docs/tutorials/21-an-entity-over-time.md)).
- Views that tick over server-sent events, **monitors**, **alerts** and **workspaces**
  ([USER_GUIDE.md › Monitors](docs/guides/USER_GUIDE.md#monitors)); share a view with a note, discuss a number, export, scheduled
  reports, API tokens, a Python client and Excel ([DISTRIBUTION.md](docs/guides/DISTRIBUTION.md), [API_GUIDE.md](docs/guides/API_GUIDE.md)).

**Business intelligence (Rūpaka)**
- Data models over pack datasets with row rules and masks, reports and **BI Studio**, live boards, statements, subscriptions,
  governance (versions, review, certification, lineage), Python and AI in BI ([BI_GUIDE.md](docs/guides/BI_GUIDE.md),
  [tutorials 10 to 15, 18 and 20](docs/tutorials/README.md)).

**Risk, computed on demand**
- **VaR and ES** for any scope (the bank, a desk, a counterparty, an ad-hoc set of trades), from scenario PVs or P&L by trade or
  position, in any reporting currency, updated intraday from files or Kafka; hierarchies of your own (limit letters); capture and
  backtest ([VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), [tutorial 25](docs/tutorials/25-var-from-the-bank-to-a-trade.md)).
- **Counterparty exposure** (EE, PFE, EPE, EEPE) for any scope, with netting, collateral and a pre-deal what-if
  ([EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), [tutorial 26](docs/tutorials/26-exposure-from-the-bank-to-a-trade.md));
  groupings and analytics ([GROUPINGS_ANALYTICS_GUIDE.md](docs/guides/GROUPINGS_ANALYTICS_GUIDE.md)).

**Data in**
- Connectors, each **one YAML file** changed live in Admin → Connectors: Delta Lake and Apache Iceberg (local or S3, no Hadoop;
  your own Databricks or Iceberg tables too), PostgreSQL and other JDBC databases, DuckDB, MongoDB, Redis, Aerospike, Cassandra,
  Kafka, ActiveMQ, RabbitMQ, S3, REST, files and public feeds; TLS and an outbound proxy
  ([CONNECTORS.md](docs/connectors/CONNECTORS.md), [EXTERNAL_LAKES.md](docs/guides/EXTERNAL_LAKES.md),
  [tutorial 4](docs/tutorials/04-connect-your-data.md)).
- **JSON Lines** is the data standard: `drishti.py lake build` loads it into Delta or Iceberg, and one call tells Drishti a load has
  landed ([DATA_LOADS.md](docs/guides/DATA_LOADS.md), [TOOLS.md](docs/guides/TOOLS.md)).

**Build**
- **Domain packs**: eleven ship in `config/packs/`, seven on by default (`core`, `market-data`, `trading`, `counterparty-risk`,
  `market-risk`, `world-economy`, `news-and-markets`); `us-equities`, `gleif`, `funds` (public data) and the sample `compute-demo`
  are opt-in, and retired examples live in `examples/packs/` ([PACKS.md](docs/guides/PACKS.md),
  [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md), [SCHEMA_TO_PACK.md](docs/guides/SCHEMA_TO_PACK.md)).
- **Sutras** in YAML with a schema, the Build workbench and the Sutra designer, reviewed with four eyes
  ([SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md), [SCREEN_DESIGNER.md](docs/guides/SCREEN_DESIGNER.md),
  [tutorial 22](docs/tutorials/22-build-a-screen-with-the-designer.md)); Calc, Python on any view (`Alt+C`); views and reports
  embedded in other web applications ([ELEMENTS.md](docs/guides/ELEMENTS.md)).

**Operate**
- **Drishti\*net** (off by default): several servers as one cluster by gossip, any of them answering any request
  ([NET.md](docs/admin/NET.md), [tutorial 19](docs/tutorials/19-a-cluster-of-three.md), [drishti-stack/](drishti-stack/README.md)).
- Sign-in with passwords or OpenID Connect, roles per kind, masking, an access log, commands switched off for everyone
  ([USER_MANAGEMENT.md](docs/admin/USER_MANAGEMENT.md)); health, Prometheus, Docker and runbooks
  ([OPERATIONS.md](docs/admin/OPERATIONS.md)).
- An **AI layer** and an **MCP client**, both off by default ([AI_PROVIDERS.md](docs/architecture/AI_PROVIDERS.md),
  [MCP_CLIENT.md](docs/architecture/MCP_CLIENT.md)).

## Try it

New to Drishti? **[GETTING_STARTED.md](docs/guides/GETTING_STARTED.md)** installs and starts it from a clean machine (Linux, Windows,
Docker, the IDEs), and **[tutorial 1](docs/tutorials/01-first-15-minutes.md)** takes you through your first quarter of an hour. The
short version, from the repository root (Java 21 or newer, production runs 21; Python 3.13,
[the minimum and why](docs/guides/GETTING_STARTED.md#step-1--install-the-prerequisites); [uv](https://docs.astral.sh/uv/)):

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
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.5-exec.jar

# 5. In a second terminal: the console on http://localhost:17480
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

Open http://localhost:17480/t, type `TRD MX-20000001` and press Enter. Then try `VAR BANK`, `CPTY CP-NORTHBRIDGE`, a past date in the
top bar, or `amended trades over 5m, biggest first`. The server loads the seven default packs; `DRISHTI_PACKS` chooses others
([PACKS.md](docs/guides/PACKS.md)). Sign-in is off for local development; to turn it on, see
[GETTING_STARTED.md, Step 8](docs/guides/GETTING_STARTED.md#step-8--sign-in-and-change-the-admin-password).

Checks: `curl -s localhost:18480/actuator/health` and `curl -s localhost:17480/healthz` answer `{"status":"UP"…}`.

## Performance at scale

Measured on one developer workstation; the method and every figure are in the linked documents.

| Data | Measure | Result |
|---|---|---|
| 35.7 million trades (ENDUR, CALYPSO, MUREX over 30 dates), 8 GB heap | 50 simultaneous users against the server | 179 requests/s, 0 failures; a trade p50 / p95 0.16 / 0.73 s; heap 3.7 GB ([PERFORMANCE.md](docs/admin/PERFORMANCE.md#millions-of-trades-a-day-and-many-users-2026-10-18)) |
| the same | 50 users through the console | 182 pages/s, 0 failures; form search p50 0.21 s |
| the same | a day of 400,000 trades restated while 30 users read | load 2 min 54 s; 202 requests/s, 0 failures |
| 8.2 million trades over 7 dates, 8 GB heap | 50 users: server / console | 146 / 69 requests/s, 0 failures; heap 1.8 GB ([SCALE_STRATEGY.md](docs/architecture/SCALE_STRATEGY.md)) |
| 1 million trades a day | search over every trade, 2,017 matches | 134 ms ([PERFORMANCE.md](docs/admin/PERFORMANCE.md#a-book-of-a-million-trades-a-day)) |
| 1 million trades in memory (VaR) | a node's VaR and ES / an ad-hoc scope of 50,000 positions / the whole bank summed from its trades | under 1 ms / 52 ms / 58 ms ([VAR_GUIDE.md section 27](docs/guides/VAR_GUIDE.md#27-sizes-performance-and-the-benchmark)) |
| warm cache | view built on the server / over HTTP, p99; live tick to frame, p99 | 0.6 ms / 3.0 ms; 11.2 ms ([PERFORMANCE.md](docs/admin/PERFORMANCE.md#targets-and-measured-numbers)) |

The stores compared at several sizes: [SCALE_BENCHMARK.md](docs/admin/SCALE_BENCHMARK.md).

## How it is built

A Java 21 server (one Spring Boot 3.5 application on virtual threads, REST under `/api/v1`) and a Python web console (FastAPI,
Jinja2, every asset vendored). The server is a set of Maven modules that depend in one direction: the plugin SPI (`drishti-api`, no
Spring), the Rachana grammar and inference, the engine, identity (SQLite or PostgreSQL), packs, the lake engines
(`drishti-deltalake`, `drishti-iceberg`, `drishti-lakeio`, no Hadoop), compute (Python worker pools, VaR), Drishti\*net, the AI
layer, and one plugin per connector under `plugins/`. The full picture: [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md).

The connector plugins, `plugins/drishti-plugin-*/`: `demo`, `file`, `rest`, `jdbc`, `delta`, `aerospike`, `cassandra`, `redis`, `mongodb`, `iceberg`, `iceberg-external`, `duckdb`, `feeds`, `news`, `yfinance`, `kafka`, `activemq`, `rabbitmq`, `s3`.

## Documentation

- **[Install and run](docs/guides/GETTING_STARTED.md)**: Linux, Windows, Docker and the IDEs.
- **[Tutorials](docs/tutorials/README.md)**: twenty-nine hands-on walk-throughs, from your first 15 minutes to VaR on Iceberg.
- **[User guide](docs/guides/USER_GUIDE.md)**: the console, screen by screen.
- **[Documentation map](docs/README.md)**: every document, by what you want to do, and the help centre section by section.
- **[Developer guide](docs/guides/DEVELOPER_GUIDE.md)**: changing the code, the tests and the drill.

## Contributing rules

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

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
