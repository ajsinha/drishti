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

# Documentation plan for 2.9.11.h (internal, for the product owner's approval)

**Status, 2026-11-08 (2.9.11.h, branch wip/h-docs):** approved and largely done: 217 → 172 Markdown files under `docs/`; tutorials 22
(+ index), knowledge 4, the combined guides of §2 written except BANK_DATA_GUIDE (R6) and WORLD_DATA_GUIDE (W1, FUNDS_GUIDE and
EXTERNAL_DATA stand in); the gates of §5 that can hold today fail (`drishti-console/tests/test_docs_gates.py`, register
`config/docs-features.yaml`), page budgets and the contract count warn. Still open: guides over their budgets (USER, REPORTS, BUILD,
DEVELOPER, OPERATIONS, CLUSTER, DATA, EMBED), contracts 51 → 28, a docs/reference folder (references stay in guides/ and admin/),
FUNCTION_CATALOGUE (a Java test reads it), VIEWMODEL in architecture/ (the frozen renderers name its path), the connector type READMEs
(served by the API), the screenshots after the g tag. This file is internal and is deleted
when 2.9.11.h is released (it supersedes TUTORIAL_INVENTORY.md).

**The rules it applies** (owner, 2026-10-26 to 2026-11-06): fewer documents, no repeated content; each fact has one home (a field → its
data contract, a setting → CONFIGURATION.md, a function → the function catalogue, an endpoint → the API reference, a measure's maths → its
guide); one guide + one tutorial per major feature and nothing more for users; data contracts are the one home for data, each kind with
its fields and one JSON example generated from `data/testdata`; no bugs or version history in customer docs; obsolete docs are deleted;
Help shows only user docs, grouped by domain and lens; every tutorial step shows the UI, the command line and the YAML.

**Counted on develop, 2026-11-07:** 337 Markdown files under `docs/` (guides 32 + 15 example notes + 4 Flight client pages, tutorials 39 +
index, architecture 40 + 22 ADRs + 2 indexes, contracts 118, connectors 29, admin 12, lenses 10, knowledge 9, qa 2, strategy 1, the index),
about 166k lines; plus 25 pack docs under `config/packs/` (11 READMEs, 14 pack guides, 13k lines) and 79 Help entries.

## 1. The major features

What a bank user and an operator actually do. Each row is one feature: one guide, one tutorial.

| # | Feature | Who | What they do |
|---|---|---|---|
| F1 | **Find anything** | everyone | sign in, the home, search, commands, lenses, an entity and its history, business dates, compare, share a view, discuss a number, alerts |
| F2 | **Pivot, cubes and Explain** | analysts, risk | pivot any data, the cube presets (MR cube, later CCR), custom functions, why did it move, what-if |
| F3 | **Reports and dashboards** | analysts | Pivot → Report: design, models, live boards, schedules, publishing and governance |
| F4 | **Notebook and Python** | quants, analysts | the Notebook, Python cells and measures on the server's one Python runtime |
| F5 | **AI assistant** | everyone, admin | Ask, its tools and MCP, model providers |
| F6 | **Corrections and approvals** | ops, risk | correct a record in a form, the one Approvals queue, the audit trail |
| R1 | **Value at risk** | risk | historical, parametric, stressed and 10-day VaR, explorer, playground, backtest, limits |
| R2 | **Counterparty exposure** | credit risk | EPE/PFE from the bank to a trade, netting sets, limit use, pre-deal |
| R3 | **FRTB-SA** | capital | sensitivities to SBM, desks, the cases, the playground |
| R4 | **SIMM** | margin | initial margin per netting set, the examples, the playground |
| R5 | **Groupings and analytics** | risk | concentrations, groupings, analytics over trading hierarchies |
| R6 | **The bank's data** | data teams | what a bank sends each day (trades, market data, reference), which contracts, the daily cycle |
| W1 | **World data** | research, macro | economies, companies, funds and ETFs, entities (LEI), news; the public-data refresh scripts |
| B1 | **Connect** | builders, ops | connectors, the data-load signal, formats, external data, Export data |
| B1b | **Data lake & data load** | builders, ops, data teams | lakes (Delta, Iceberg, in-memory), the lake file, the data inbox, `.ready`, load rules, `lake build`, the parallel loader, sizing, reading an existing lakehouse (product owner, 2026-11-07: "there should be a dedicated document on Drishti data lake & data load with real examples") |
| B2 | **Tables and models** | builders | virtual tables, the table and model editors, Python-driven tables |
| B3 | **Packs and screens** | builders | a pack from a schema, Sutras, the designer, forms, the phone display |
| B4 | **Data out** | developers | Arrow Flight and Flight SQL, exports, pandas/JDBC/ODBC |
| B5 | **Embed** | developers | Drishti Elements and the client renderer in any web page |
| B6 | **Extend** | developers | write a connector, a plugin, a module; the API |
| O1 | **Install and operate** | operators | install, configure, run, deploy, promote, monitor, runbooks, troubleshoot |
| O2 | **Clusters and scale** | operators | several servers as one, AWS, sizing, sharding, tuning |
| O3 | **People, access and editions** | administrators | users, sign-on, roles, editions, lenses, engine and mnemonic switches, (h) module licences |

23 features → 23 guides + 1 kept reference guide (Regulatory VaR reporting, owner rule), 23 tutorials (B1 split into B1 Connect and B1b Data lake & data load, product owner, 2026-11-07).

## 2. One guide and one tutorial per feature

Targets are hand-written lines. Tutorials keep UI + command line + YAML at every step; their budget comes from removing overlap between the
tutorials merged, never from thinning a step. Help placement: **section** / **domain** (core, risk, world) / **lens** (all, or the lenses
that see it). Contract pages are those of §4.

| # | Guide (feeds) | Lines | Tutorial (feeds) | Lines | Contracts linked | Help |
|---|---|---|---|---|---|---|
| F1 | `USER_GUIDE.md` (USER_GUIDE, console sharing guide, COLLABORATION user parts, lens intros) | 1,200 | `01-first-15-minutes` (01, 02, 03, 08, 21) | 600 | — | Start / core / all |
| F2 | `PIVOT_GUIDE.md` (PIVOT_GUIDE, MR_CUBE_GUIDE, PIVOT_ENGINE use, FUNCTION_CATALOGUE how-to) | 1,200 | `pivot-cubes-and-explain` (33, 37, 38) | 550 | VAR, PNL | Analyse / core / all (cube preset: risk) |
| F3 | `REPORTS_GUIDE.md` (BI_GUIDE rewritten, BI_DESIGN user parts) | 1,800 | `reports-and-dashboards` (09, 10, 11, 12, 13, 18, 20) | 800 | — | Analyse / core / all |
| F4 | `NOTEBOOK_GUIDE.md` (BI_GUIDE Python parts, PYTHON_COMPUTE) | 700 | `notebook-and-python` (14) | 450 | — | Analyse / core / all |
| F5 | `AI_GUIDE.md` (admin/AI) | 700 | `ai-assistant` (15) | 400 | — | Analyse / core / all |
| F6 | `APPROVALS_GUIDE.md` (WRITE_BACK, COLLABORATION approvals) | 600 | `correct-and-approve` (24) | 300 | trading, core | Analyse / core / all |
| R1 | `VAR_GUIDE.md` (VAR_GUIDE, PARAMETRIC_VAR, VAR_PLAYGROUND, VAR_SHARDING use) | 2,500 | `var-from-the-bank-to-a-trade` (25, 43) | 900 | VAR, PNL, STR, market-risk | Risk / risk / risk, trading, CRO |
| R1′ | `REGULATORY_VAR_REPORTING.md` (kept: one regulatory document, owner rule) | 850 | — | — | VAR | Risk / risk / risk, CRO |
| R2 | `EXPOSURE_GUIDE.md` | 1,400 | `exposure-from-the-bank-to-a-trade` (26) | 550 | EXP, NSET, LIM, counterparty-risk | Risk / risk / risk, CRO |
| R3 | `FRTB_SA_GUIDE.md` (+ SENSITIVITIES_AND_FRTB_SA) | 600 | `frtb-sa` (30) | 300 | FRTB, TVAL | Risk / risk / risk |
| R4 | `SIMM_GUIDE.md` | 600 | `simm` (32) | 300 | SIMM, NSET | Risk / risk / risk |
| R5 | `GROUPINGS_ANALYTICS_GUIDE.md` | 800 | `groupings-and-analytics` (27) | 500 | TRADING_GROUPINGS, GRP | Risk / risk / risk, trading |
| R6 | `BANK_DATA_GUIDE.md` (pack guides core, banking-data-model, trading, trading-products, market-data, market-data-catalogue, market-risk, counterparty-risk; knowledge intros) | 1,200 | `a-day-of-bank-data` (07) | 400 | core, trading, market-data, market-risk, counterparty-risk | Data / risk / risk, trading, market |
| W1 | `WORLD_DATA_GUIDE.md` (FUNDS_GUIDE, EXTERNAL_DATA, pack guides world-economy, us-equities, funds, gleif, news-and-markets) | 1,500 | `world-data` (28 widened) | 500 | world-economy, us-equities, funds, gleif, news-and-markets | Data / world / world |
| B1 | `DATA_GUIDE.md` (DATA_GUIDE, CONNECTORS, CONNECTOR_FILES, TLS, DEMO_DATA, connector types) | 1,000 | `connect-your-data` (04) | 300 | the kind being loaded | Data / core / all |
| B1b | `DATA_LAKE_GUIDE.md` (written 2.9.11.d: LAKE_GUIDE, DATA_LOAD_RULES and DATALOAD_INBOX folded in, DATA_GUIDE's lakehouse, archive, feed and inbox sections; later IN_MEMORY_LAKE_GUIDE, LAKE_CONFIG use) | 1,100 | `load-your-own-data-into-a-new-lake` (45; 34, 36, 31, 29's lake part) | 500 | VAR, the kinds being loaded | Data / core / all |
| B2 | `TABLES_GUIDE.md` (+ VIRTUAL_TABLES, EDITORS 1–4) | 800 | `tables-and-models` (44) | 400 | — | Build / core / all |
| B3 | `BUILD_GUIDE.md` (PACK_DEVELOPER I–VII, SUTRA_DEVELOPER, SCHEMA_TO_PACK, WRITE_BACK form design, MOBILE_DISPLAY) | 2,000 | `build-a-pack-and-a-screen` (05, 06, 23) | 700 | — | Build / core / all |
| B4 | `FLIGHT_GUIDE.md` (+ export) | 500 | `data-out` (35) | 300 | — | Data / core / all |
| B5 | `EMBED_GUIDE.md` (CLIENT_RENDERER user parts) | 800 | `embed-drishti` (16) | 300 | — | Build / core / all |
| B6 | `DEVELOPER_GUIDE.md` (CONNECTOR_DEVELOPER_GUIDE, PACK_DEVELOPER VIII, API_GUIDE how-to) | 1,200 | `write-a-connector` (new, after e) | 400 | — | Build / core / admin, developer |
| O1 | `OPERATIONS_GUIDE.md` (GETTING_STARTED, admin/OPERATIONS, OPERATIONALISING, TROUBLESHOOTING, 4 runbooks, TOOLS scheduling) | 2,500 | `run-it-in-production` (17) | 400 | — | Operate / core / admin |
| O2 | `CLUSTER_GUIDE.md` (CLUSTER_DEPLOYMENT, RESOURCES_AND_SCALE sizing, PERFORMANCE tuning, VAR_SHARDING ops, deploy/aws) | 1,500 | `a-cluster-of-three` (19) | 400 | — | Operate / core / admin |
| O3 | `ADMIN_GUIDE.md` (USER_MANAGEMENT, EDITIONS, lenses README, COMPUTE_ENGINES switches) | 1,200 | `set-up-a-desk` (new, after h) | 350 | — | Operate / core / admin |

Totals: guides about 27,600 lines (today about 110k across guides, admin, connectors and pack docs); tutorials about 10,500.
Tutorials are renamed `NN-<slug>.md` in track order (pictures are re-shot anyway; Help's `moved:` keeps old addresses).

**References** (one home each, mostly generated; Help → Reference, core, all): `CONFIGURATION.md`, `API_REFERENCE.md`, `CLI_REFERENCE.md`,
`FUNCTIONS.md`, `RACHANA_REFERENCE.md`, `VIEWMODEL.md`, `FLIGHT_REFERENCE.md`, `BENCHMARK.md`, `UNDER_CONSTRUCTION.md`, the data contracts, the
Knowledge primers and the release notes. Help listed entries: 79 → about 60 (each guide and its tutorial on one row).

## 3. Every existing document

`docs/` paths. "→ X" means merge into X (live content moves, then `git rm`). New folder `docs/reference/` (planned) holds the references.

### Guides (32 + 19)

| Document | Action | Why |
|---|---|---|
| guides/API_GUIDE | → generated `reference/API_REFERENCE` + DEVELOPER_GUIDE | endpoints have one home, generated by `tools/api_docs.py` |
| guides/BI_GUIDE | → REPORTS_GUIDE (rewrite) + NOTEBOOK_GUIDE | obsolete after d (virtual tables, Pivot → Report) |
| guides/BI_LANGUAGE_REFERENCE | delete (SQL part → RACHANA_REFERENCE) | DEL folded into Rachana in d |
| guides/CLI_GUIDE | → generated `reference/CLI_REFERENCE` | options come from the parser |
| guides/COMMAND_CATALOG | → `reference/CLI_REFERENCE` | already generated; one reference for the command line |
| guides/DATA_GUIDE | keep, rewrite (B1) | |
| guides/examples/* (15) | keep as example notes | the workbench's Build → New → Examples reads them; not counted as guides |
| guides/EXPOSURE_GUIDE | keep, rewrite (R2) | data section → link to contracts |
| guides/EXTERNAL_DATA | → WORLD_DATA_GUIDE | one external-data section beside the packs it feeds |
| guides/FLIGHT_GUIDE | keep (B4) | |
| guides/flight/JAVA, JDBC, ODBC, PYTHON | → `reference/FLIGHT_REFERENCE` | four short client pages, one reference |
| guides/FLIGHT_REFERENCE | move to reference | |
| guides/FRTB_SA_GUIDE | keep (R3) | |
| guides/FUNCTIONS | move to reference (generated) | the function catalogue |
| guides/FUNDS_GUIDE | → WORLD_DATA_GUIDE | funds are one of the world packs |
| guides/GETTING_STARTED | → OPERATIONS_GUIDE part 1 | install is the operator's first task; F1 links to it |
| guides/GROUPINGS_ANALYTICS_GUIDE | keep (R5) | |
| guides/IN_MEMORY_LAKE_GUIDE | → DATA_LAKE_GUIDE | a lake option, not a feature of its own |
| guides/LAKE_GUIDE | → DATA_LAKE_GUIDE (done, 2.9.11.d) | |
| guides/DATA_LAKE_GUIDE | keep (B1b) | the one home for lakes and loading |
| guides/MR_CUBE_GUIDE | → PIVOT_GUIDE | the cube is a Pivot preset since d |
| guides/OPERATIONALISING | → OPERATIONS_GUIDE | deploy, promote, roll back |
| guides/PACK_DEVELOPER_GUIDE | → BUILD_GUIDE (I–VII) + DEVELOPER_GUIDE (VIII) | 6k lines, two audiences |
| guides/PIVOT_GUIDE | keep (F2) | |
| guides/RACHANA_REFERENCE | move to reference; keys generated from the schema, budget 4k (from 9k) | one grammar reference |
| guides/REGULATORY_VAR_REPORTING | keep (R1′) | owner rule: one regulatory document |
| guides/SCHEMA_TO_PACK_GUIDE | → BUILD_GUIDE | |
| guides/SIMM_GUIDE | keep (R4) | |
| guides/SUTRA_DEVELOPER_GUIDE | → BUILD_GUIDE | |
| guides/TABLES_GUIDE | keep (B2) | |
| guides/TOOLS | → CLI_REFERENCE (commands) + OPERATIONS_GUIDE (scheduling) | |
| guides/TROUBLESHOOTING | → OPERATIONS_GUIDE (error codes generated) | |
| guides/USER_GUIDE | keep, rewrite (F1) | |
| guides/VAR_GUIDE | keep, rewrite (R1) to 2.5k | contract and data sections → contracts |
| guides/WRITE_BACK | → APPROVALS_GUIDE + BUILD_GUIDE (forms) | |

### Tutorials (39 + index)

| Tutorials | Action |
|---|---|
| 01, 02, 03, 08, 21 | → `01-first-15-minutes` |
| 04, 34, 36, 31 | → `connect-and-load` |
| 05, 06, 23 | → `build-a-pack-and-a-screen` |
| 07 | keep, retarget as `a-day-of-bank-data` |
| 09, 10, 11, 12, 13, 18, 20 | → `reports-and-dashboards` |
| 14, 15, 16, 17, 19, 24, 26, 27, 30, 32, 35, 44 | keep, rewrite (renamed per §2) |
| 25, 43 | → `var-from-the-bank-to-a-trade` |
| 28 | keep, widen to `world-data` |
| 29 | VaR part → 25 (lake switch as one step), lake part → `connect-and-load` |
| 33, 37, 38 | → `pivot-cubes-and-explain` |
| README | generate from help.yaml (tracks) |
| new | `write-a-connector` (after e), `set-up-a-desk` (after h) |

### Admin (12)

| Document | Action | Why |
|---|---|---|
| admin/AI | → guides/AI_GUIDE | user and admin halves in one guide |
| admin/BENCHMARK | move to reference | one benchmark document |
| admin/CONFIGURATION | move to reference; hand part trimmed to the concepts | settings are generated already |
| admin/EDITIONS, admin/USER_MANAGEMENT | → ADMIN_GUIDE | |
| admin/OPERATIONS | → guides/OPERATIONS_GUIDE | |
| admin/PERFORMANCE | tuning → CLUSTER_GUIDE, figures → BENCHMARK | a fact has one home |
| admin/runbooks/* (4) | → OPERATIONS_GUIDE "Runbooks" | |
| admin/UNDER_CONSTRUCTION | move to reference (generated) | |

### Connectors (29)

| Document | Action | Why |
|---|---|---|
| ACTIVEMQ, AEROSPIKE, CASSANDRA, DELTA, DEMO, DUCKDB, FEEDS, FILE, ICEBERG, ICEBERG_EXTERNAL, KAFKA, MONGODB, RABBITMQ, REDIS, REST, S3 (`*_CONNECTOR`), NEWS, YFINANCE | → `plugins/<plugin>/README.md` | the README is already the enforced home per connector; the docs page repeats it at 4× the length |
| POSTGRES_CONNECTOR, JDBC_QUERIES | → `plugins/drishti-plugin-jdbc/README.md` | Postgres is a JDBC source |
| CONNECTORS, CONNECTOR_FILES, TLS, DEMO_DATA, REACTIVE_CONNECTOR, types/derived, types/net, types/reactive | → DATA_GUIDE (connector table generated from the READMEs' front matter) | |
| CONNECTOR_DEVELOPER_GUIDE | → DEVELOPER_GUIDE | |

### Architecture (40 + 22 ADRs + 2 indexes): internal, never in Help

| Document | Action | Why |
|---|---|---|
| ARCHITECTURE, DATA_ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_HARNESS | keep | the internal core |
| adr/* (22 + README) | keep; delete any ADR superseded (check 011 vs 017) | decisions record |
| mockups/capacity/README | keep | beside its mock-ups |
| COUNTERPARTY_ENGINES, IFRS9_ECL, FINANCIAL_CRIME | keep | designs not built (j–l, later) |
| CONTROLS_SCREENS, EDITORS (5–6) | keep until h, then fold and delete | h builds them; EDITORS 1–4 → TABLES_GUIDE |
| HIGH_PERFORMANCE_NATIVE | keep until i | |
| DRISHTI_NATIVE | fold into ARCHITECTURE after e, delete | built in e |
| SHARED_SCREENS | fold into USER/BUILD guides after f, delete | built in f |
| ARROW_FLIGHT, IN_MEMORY_LAKE, LAKE_CONFIG, PARALLEL_LOADER | → DATA_LAKE_GUIDE / FLIGHT_GUIDE (use) + ARCHITECTURE (how), delete | built |
| DATALOAD_INBOX, DATA_LOAD_RULES | done (2.9.11.d): use → DATA_LAKE_GUIDE, internal notes → DATA_ARCHITECTURE section 7, deleted | built |
| BI_DESIGN, BI_FOUNDATION_2911E, VIRTUAL_TABLES, ONE_GRAMMAR | → REPORTS/TABLES guides + ARCHITECTURE, delete | built in d |
| PIVOT_ENGINE, PYTHON_COMPUTE, COMPUTE_ENGINES | → PIVOT/NOTEBOOK/ADMIN guides + ARCHITECTURE, delete | built |
| PARAMETRIC_VAR, VAR_PLAYGROUND, VAR_SHARDING | → VAR_GUIDE / CLUSTER_GUIDE, delete | built |
| SENSITIVITIES_AND_FRTB_SA | → FRTB_SA_GUIDE + contracts, delete | built |
| CLIENT_RENDERER | → EMBED_GUIDE + VIEWMODEL, delete | 3.7k lines, built |
| CLUSTER_DEPLOYMENT, RESOURCES_AND_SCALE | → CLUSTER_GUIDE + BENCHMARK, delete | |
| COLLABORATION | → USER_GUIDE, APPROVALS_GUIDE, ARCHITECTURE, delete | |
| COMPUTED_ATTRIBUTES | applied parts → contracts; open proposals → IMPLEMENTATION_PLAN; delete | half applied, half proposal |
| MOBILE_DISPLAY | → BUILD_GUIDE (phone tiers), delete | built in 2.9.10 |
| FUNCTION_CATALOGUE | → reference/FUNCTIONS (generated), delete | |
| VIEWMODEL | move to reference | the embed developer's contract |
| IMPLEMENTATION_HISTORY | delete | CHANGELOG is the history |
| PRODUCT_CRITIQUE_BRIEF | delete | critique dropped (owner, 2026-11-06) |
| TUTORIAL_INVENTORY | delete | superseded by this plan |
| DOCS_PLAN (this) | delete at the g release | |

### The rest

| Document | Action | Why |
|---|---|---|
| lenses/* (10) | → one internal `architecture/LENSES.md` (status, gaps, proposals; mock-ups stay); "what a lens is" → USER/ADMIN guides | product planning, not user docs |
| knowledge/* (9) | → 4 primers: glossary (README), risk (market-risk, counterparty-credit-risk, trades-and-products), data (market-data, entities-and-ownership, data-and-contracts), world (companies-funds-and-etfs, economies) | glossary stays generated by `tools/knowledge.py` |
| qa/QA_HISTORY | delete | history |
| qa/windows-custom-data | → TEST_HARNESS | |
| strategy/COMPETITIVE_ANALYSIS | move to architecture (internal) | |
| docs/README | generate from help.yaml | |
| contracts/* (118) | §4 | |
| config/packs/*/guides (14) | → BANK_DATA_GUIDE / WORLD_DATA_GUIDE; the pack-guide mechanism stays for other packs | |
| config/packs/*/README (11) | keep, shortened; data structures → link to the contract page | README standard |

### Counts

| Kind | Before | After |
|---|---|---|
| Feature guides | 32 | 23 (22 + regulatory) |
| Guide sub-pages (examples, Flight clients) | 19 | 15 (example notes) |
| Tutorials (+ index) | 39 + 1 | 22 + 1 (generated) |
| Reference (`docs/reference/` (planned)) | 0 | 9 |
| Admin | 12 | 0 |
| Connectors | 29 | 0 (19 plugin READMEs) |
| Contracts | 118 | 28 |
| Knowledge | 9 | 4 |
| Architecture, ADRs, lenses, qa, strategy | 77 | 35 |
| Index | 1 | 1 (generated) |
| **Total under docs/** | **337** | **about 138** |
| Pack docs | 25 | 11 |
| Help entries | 79 | about 60 |

## 4. The contracts plan: 118 → 28 pages

- **11 pack pages** (`contracts/packs/<pack>.md`, one per pack): every kind, derived kinds included, with its fields table (from
  `modules/<module>/packs/<pack>/contracts/<kind>.contract.yaml`) and **one JSON example** from `data/testdata`, formatted, long arrays shortened
  `/* … N more … */`. The 90 `contracts/samples/**` pages fold in; `contracts/packs/README` folds into the index.
- **16 computed pages** (the 16 sections of `contracts/README`): VAR and EXP move here from VAR_GUIDE and EXPOSURE_GUIDE (contracts are the one
  home for data), FRTB_DATA_STRUCTURES folds into FRTB; TVAL, STR, PNL, TRADING_GROUPINGS, SIMM, NSET, SACCR, COLL, MC, LIM, GRP, CVA, WWR stay.
  Each gets a generated JSON example of its inputs (testdata) and of its answer (the engine's golden test output). Pages for engines not
  built (SACCR, COLL, MC, CVA, WWR, STR, computed LIM) are marked *Draft* through the under-construction registry.
- **1 index** (`contracts/README`): the rules, every kind A–Z with pack, command word and "needed for".
- **Generation:** `tools/contracts.py` writes the pages; the example code of `tools/docs/guide_data.py` becomes a shared module it calls.
  `tools/testdata/sample_docs.py` is deleted (no dual machinery). The eight guides' generated data sections become short tables
  (kind, needed?, for what, link to the contract); `guide_data.py --check` keeps them current.
- **Review, every contract:** fields equal the YAML and the test data (gate); keys, grain, dates, units, nullability and currency stated; every
  `review:` item resolved or listed on the page; no history; API paths taken from the API reference after e. Four review slices: core +
  trading + market-data; market-risk + counterparty-risk; the five world packs + compute-demo; the 16 computed pages.

## 5. Generated vs hand-written, and the gates

**Generated (never edited by hand):** CONFIGURATION settings (`settings_catalogue.py`), API_REFERENCE (`api_docs.py`), CLI_REFERENCE
(`command_catalog.py` + a parser dump), FUNCTIONS, Rachana key tables (from the schema), contracts and their examples (`contracts.py`),
guides' data tables (`guide_data.py`), Knowledge glossaries (`knowledge.py`), UNDER_CONSTRUCTION, the docs and tutorial indexes (from
help.yaml), the connector table (from plugin READMEs), the README's numbers (`code_volume.py`), screenshots (`tools/docs/screenshots.py`).

**Hand-written:** the 23 guides, the 22 tutorials, the Knowledge primers' prose, the concepts parts of CONFIGURATION and RACHANA_REFERENCE,
everything internal.

**Gates to add** (in the docs gate beside the existing help, links, rot, placeholders, settings, error-code and image tests):

| Gate | Fails when |
|---|---|
| Feature register | `config/docs-features.yaml` (planned) (feature, guide, tutorial, domain, lenses, budget) and the files disagree; a feature without exactly one guide and one tutorial (or `tutorial: after <release>`) |
| Count caps | guides > 24, tutorials > 23, reference > 10, contracts > 30, knowledge > 5, Help entries > 65 |
| Page budgets | a guide or tutorial over its budget by more than 10% |
| One home for data | a field table or JSON record of a contract kind outside `docs/contracts/` (tutorial outputs excepted) |
| One home for settings, endpoints, functions | a `drishti.*` default, an endpoint table or a function table outside its reference |
| No repeated prose | the same 40-word run in two user documents |
| Stale generated sections | any generator's `--check` |
| Customer text | version history ("since 2.x", "(2.9.x)"), defect words, links to internal docs from Help pages |
| Help is user docs only | a Help entry pointing under `docs/architecture/`; an entry without domain and lens |
| Executable tutorials | a tutorial's command-line block fails against the demo lakes (run once per release before drill 1) |

Gates land first in warn mode and switch to fail at the end of g.

## 6. Order of work

e (module split) moves the API paths, packs and Help into modules; f (Build home, search, Explain, Approvals, sitemap, trader home)
changes most screens. So:

**Now, alongside e and f (light builders, screen-independent):**
1. The contracts (§4): generator, the samples folded, the 28 pages, the review. API paths last.
2. The generators and the gates (§5) in warn mode; the feature register.
3. Deletions of history and superseded internal docs (IMPLEMENTATION_HISTORY, PRODUCT_CRITIQUE_BRIEF, QA_HISTORY, TUTORIAL_INVENTORY at
   the end, BI_FOUNDATION_2911E and BI_LANGUAGE_REFERENCE once d's fold is checked); built designs' "how it works" into ARCHITECTURE.
4. Knowledge 9 → 4; lenses 10 → 1.
5. Method and maths sections of R1–R5 and REGULATORY (no screens).
6. Connector pages into plugin READMEs (re-pathed by e's plugins folder, content unchanged).

**After e:** Help placement by domain and module; API_REFERENCE regenerated; R6 and W1 (packs grouped by domain); B6 DEVELOPER_GUIDE and its
tutorial (module and plugin shape); O1/O2 install and cluster parts (thin jar, plugins folder, storage profile).

**After f (g proper):** every UI step and screenshot; F1 (search, sitemap, trader home), F2 Explain, F6 Approvals, B2/B3 (Build home),
O3 (Admin configuration editor). Order: Start + Build → Analyse → Risk → Data → Operate; then one screenshot run (one heavy job); gates to
fail; root README; decks; the drills.

**After h:** O3's tutorial `set-up-a-desk` (RBAC, module licences) and the role doors.

## 7. Open questions for the owner

1. **Layout:** a new `docs/reference/` (planned), and lenses, qa and strategy moved under `docs/architecture/` (internal)? Or keep the folders and
   only cut files?
2. **Pack guides:** the 14 shipped pack guides fold into BANK_DATA_GUIDE and WORLD_DATA_GUIDE, packs keep only their README; agreed?
3. **Where risk docs live after e:** in `docs/` tagged by domain (recommended; the licence hides them) or inside the banking module?
4. **Draft contracts** for engines not built (SA-CCR, collateral, margin calls, CVA, WWR, stress, limit use): visible as *Draft* (banks can
   start delivering) or internal until built?
5. **Regulatory VaR reporting** as a third VaR document (your earlier rule): keep?
6. **MR cube** folded into the Pivot guide (the cube is a Pivot preset), or kept as its own risk guide?
7. **Developer and admin tutorials:** new `write-a-connector` (after e) and `set-up-a-desk` (after h), or guides only?
8. **External data:** your rule asks for one listed Help page "External data"; under this plan it is a section of WORLD_DATA_GUIDE with
   its own Help entry. Fine?
9. **Connector pages** (19 × ~700 lines) into plugin READMEs (~300 each): agreed, or a generated connector reference from the READMEs?
10. **Knowledge** 9 → 4 primers, and **example notes** (15) kept as workbench content, not counted as guides: agreed?
11. **Tutorial renumbering** in track order (pictures are re-shot anyway): agreed?
