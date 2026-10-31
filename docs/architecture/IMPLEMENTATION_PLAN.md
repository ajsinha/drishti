<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti — Implementation Plan

*Revision 1.3 · 2026-10-25 · Author: Ashutosh Sinha · Companion to [ARCHITECTURE.md](ARCHITECTURE.md)*

**Built 2026-10-24: the us-equities pack** (`config/packs/us-equities`, [guide](../../config/packs/us-equities/guides/us-equities.md); off by default, enabled through `DRISHTI_PACKS`; as built it covers the members of the three indexes, about 500 companies, to keep the repository small: every listed company is a later step; links company to LEI to a core legal entity where the LEI matches). Planned by the product owner on 2026-10-22: Every NYSE and Nasdaq listed company, Dow 30, Nasdaq-100 and S&P 500 membership, from openly licensed sources first: SEC EDGAR (company facts, filings, SIC codes, ticker to CIK), the Nasdaq Trader symbol lists, GLEIF LEI (CC0) and Wikidata (CC0). Kinds: listed-company (enriched), filing, financial-fact (with history), index-membership, price-history; screens from the Sutra fragment library; links company to LEI to parent to counterparty. Index weights and levels are proprietary, and free price feeds forbid redistribution: internal use only (like the IMF data) until a licensed feed is chosen.

**North star: Sutra Next, one grammar for every screen (product owner, 2026-10-22).** The board/chip paradigm is dropped: Sutra itself grows from an entity browser into the UI library, in ONE grammar, built on today's panels and shared blocks (`use:`/`with:`): a versioned, parameterised fragment library whose every fragment and Sutra carries a `doc:` docstring a component browser and an AI agent read; per-panel sources over several packs; events (`emits`, `@panel.event.path`, `@parent`); nested child panels (expand-row, detail-right/below, tabs; lazy; depth 3); refresh control and freshness; forms with validation; actions that write through connectors' `writes:` (2.8.0 write-back); dynamic screens saved to the workspace; an optional layout grid; and an editor built from the product owner's mock-ups. Design and phases: [SUTRA_NEXT.md](SUTRA_NEXT.md); phase 1 (the library, now the Drishti UI kit in `config/ui-kit` loaded whatever packs are enabled, with assets and brand; docstrings and lint, composite fragments, the ir-curve and economics screens migrated and proved identical, `GET /api/v1/ui-kit`) is built for 2.7.0 (unreleased); since then events and per-panel sources, nested children, refresh and freshness, forms with drafts, the `layout:` grid, every pack screen on fragments and the Sutra designer (Customize, Describe, Outline, Grid; `ui.designer`) are built too, unreleased. The UI Board (2026-10-21) stays parked on branch `ui-board-preview`; it is not in any release.

**Where things stand.** Every wave in this plan is done. The current release is **2.9.6** (2026-10-29: editions with Drishti Risk, its three landings and the lens; VaR sharded across servers; the parallel Java loader; long-run memory fixes; [its table](#296-released-2026-10-29)); next **2.9.7** ([its table](#297-future-product-owner-2026-10-28-in-progress)); before it **2.9.5** (2026-10-28: Iceberg as a full alternative to Delta; VaR hierarchies configured by columns; connector files that upgrade themselves safely); before it **2.9.4** (2026-10-27: VaR from scenario PVs or P&L, by trade or position, in any reporting currency, intraday from files and Kafka; JSON Lines as the data standard); before it **2.9.3** (2026-10-27: `DATAMAP`, the fact of the day on Home, the Companies, funds and ETFs Knowledge page, index membership from the tracking ETFs); before 2.9.3 **2.9.2** (2026-10-26: funds and ETFs, Knowledge and `WHAT`, Construct your basket and the pre-deal what-if); before it **2.9.1** (2026-10-26: `FUNC`, groupings and analytics computed, External data and complete pack guides); before it **2.9.0** (2026-10-26: exposure (EXP) computed for any scope like VaR, with its guide and tutorial 26, and VaR and exposure in BI; [the computed-measures roadmap](#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)); before it **2.8.0** (2026-10-26: the data contract of every command, VaR computed for any scope and never stored, Admin → Commands, write-back, branding from configuration; [the computed-measures roadmap](#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)); before it **2.7.0** (Sutra Next, [SUTRA_NEXT.md](SUTRA_NEXT.md): the UI kit, events and children across packs, refresh and freshness, layout, forms with drafts and the four-mode Sutra designer; the us-equities and gleif packs, the per-pack public-data scripts and the README standard); before it **2.6.7** (BI runs, [BI_RUNS.md](BI_RUNS.md); chart any table, [TABLE_CHARTS.md](TABLE_CHARTS.md); mixed Delta and Iceberg storage, [DEMO_DATA.md](../connectors/DEMO_DATA.md#mixed-delta-and-iceberg-storage); gossip on its own port and HTTP/2 between nodes; with 2.6.6, the QA release); next are **2.8.0** (Sutra Next write-back: forms and actions saved through connectors, [WRITE_BACK.md](WRITE_BACK.md); its item 0, the one Drishti store, is begun as the content store, CONTENT_STORE.md; the UI Board stays parked on branch `ui-board-preview`, in no release) with the data contracts of every command and VaR complete; everything after it (the other computed measures, 2.9.0 to 2.11.0, and AI with GenUI, 2.12.0) is parked since 2026-10-25 ([the computed-measures roadmap](#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25))
([CHANGELOG.md](../../CHANGELOG.md), [RELEASE_NOTES.md](../../RELEASE_NOTES.md)). The table below maps each release to
the waves it shipped; [What shipped after the waves](#what-shipped-after-the-waves-1100) and [Since 1.10](#since-110) list the work done since
the last numbered wave, and [Known gaps](#known-gaps) lists what is still open. The wave sections themselves are
kept as they were planned, with notes where the delivery differed.

| Release | Waves |
|---|---|
| 1.0.0 | W1–W10 |
| 1.1.0 | W11 user management |
| 1.2.0 | W12 help centre and About |
| 1.3.0 | W12b competitive landscape and mobile |
| 1.4.0 | W13 workspaces |
| 1.5.0 | W13b domain packs |
| 1.6.0 | W14 monitors and alerts |
| 1.7.0 | W15 F8 impact |
| 1.8.0 | W13c packs per user |
| 1.9.0 | W22 business dates and Delta Lake; R1–R2 risk taxonomy and Sutras; Markdown Sutras (replaced in 1.11: Sutras are YAML only, ADR-017) |
| 1.10.0 | B0, R3–R5, P2–P8 (all domain packs); W16–W21; and the work [below](#what-shipped-after-the-waves-1100) |
| 1.11.0 | Sutras are YAML only (ADR-017) |
| 1.12.0 | derived kinds, notes, scheduled reports, plain words, the signed pack registry (ADR-018) |
| 1.13.0 | a million trades a day on eight stores, Calc, pivots, twenty panel kinds |
| 1.14.0 | the Build workbench |
| 1.15.0 | the metric tile, five developer guides |
| 1.16.0 | Java 21 for production, About this page, share and discussion (ADR-020) |
| 1.17.0 | the `drishti.py` command line, `pack make`, Admin deploy, API token write scopes |
| 1.18.0 | data loads: the batch-landed signal, expectations, late data |
| 1.19.0 | connectors as files, TLS and proxy, query by form, Drishti Elements, retry and no-restart reload, AI and MCP layers, Rūpaka phase 1 |
| 1.20.0 | Rūpaka phases 2 and 3 (datasets, reports, designer), deep trades, real public data |
| 1.21.0 | Rūpaka phases 4 and 5 (governance, live tables, Perspective, boards), outbound HTTP proxy, news and market sample packs, Forms and Reports in the top bar, the Live toggle, a documentation review against the code |
| 1.22.0 | Rūpaka phase 7, distribution: export (PDF, PNG, Excel, CSV), scheduled subscriptions rendered per recipient, alerts on measures and board notifications, `<drishti-report>` and Elements steps 11 to 14, per-report phone layouts; one shared file watcher per process |
| 2.0.0 | the waves after 1.22.0 in one release, see [What 2.0.0 contains](#what-200-contains) |
| 2.1.0 | the BI long tail in four waves, see [What 2.1.0 contains](#what-210-contains) |
| 2.2.0 | BI wave A: datasets and data models rooted in packs, the Python-like expression language (DEL), read-only Drishti SQL, one query plan; see [What 2.2.0 to 2.5.0 contain](#what-220-to-250-contain) |
| 2.3.0 | BI wave B: filter context, time and live push, the servers compute every number; production-shaped UPPER_SNAKE trades from ENDUR, CALYPSO and MUREX; seven packs; committed test data and `lake demo`; offline mode |
| 2.4.0 | BI wave C: BI Studio, report schema v2, boards as a report layout, Python data prep (the Transform editor), the browser Python console; query by form at scale; the CATO calendar; the console's channel relay |
| 2.5.0 | wave N: Drishti\*net, several servers as one; `-Djava.io.tmpdir` on every JVM; a stand-in for switched-off internet connectors; `drishti-stack/` |
| 2.6.0 | wave D: no known bug; statements (paginated reports, bursting, PDF and Excel, the statement designer); field parameters in report wells; the Apache Cassandra connector; drishti-iceberg (Iceberg without Hadoop) and `iceberg-external`; members advertise only a real address; ten themes |
| 2.6.5 | history queries: any entity over time (`TRD <id> 30`, `EQ <id> 3M`), on every connector and across Drishti\*net ([HISTORY_QUERIES.md](HISTORY_QUERIES.md)); sixty business days of committed test data |
| 2.6.7 | BI runs: every BI query through one bounded asynchronous pipeline, the Runs tab ([BI_RUNS.md](BI_RUNS.md)); Drishti\*net gossip off the busy path (its own small HTTP client pool and platform thread on the sender, reserved handling of `/api/v1/net/gossip` and probes on the receiver; accepted when a heavy BI burst on one node gives no false *suspect* anywhere) and HTTP/2 for peer data forwarding (`server.http2.enabled`; tests across views, history, search and live relay, and a soak comparison) ([DRISHTI_NET.md](DRISHTI_NET.md#the-transport-between-nodes-as-built-267)); chart any table: a Chart icon and K on every table, one shared `table-chart.js` over the pivot engine and ECharts, chart all N on the server, save, export, promote to a `chart` panel, open in BI ([TABLE_CHARTS.md](TABLE_CHARTS.md)); mixed storage: `build_demo_lakes.py --store <system>=delta|iceberg` (and `all=`), each source system's trades in Delta or Iceberg, the connector file switched with it, proven by an equality test against all-Delta ([DEMO_DATA.md](../connectors/DEMO_DATA.md#mixed-delta-and-iceberg-storage)) |
| 2.7.0 | Sutra Next phases 1 to 5 (the UI kit, events and children, refresh, layout, forms with drafts, the Sutra designer); the us-equities and gleif packs; public-data scripts; the README standard |
| 2.8.0 | the data contract of every command and the command catalog; VaR computed for any scope from every live trade (stored var and var-node retired, positions derived, memory tiering, official captures published, what-if); Admin → Commands; write-back W1–W7; branding from configuration (the other computed measures parked) |
| 2.9.0 | exposure computed for any scope (the stored exposure-profile retired; the explorer; the exact pre-deal what-if; official results; the wider demo run); VaR and exposure in BI; VaR and exposure in two documents each; the landing headline |
| 2.9.1 | Functions (`FUNC`) for everyone; groupings and analytics computed (LEIG, LEIC, SIC, ECG, CORR, USEQ ratios, FACT change, IDX totals, equity beta, PXH analytics); the External data page and every pack's data contracts and data retrieval; the exposure benchmark and explorer test |
| 2.9.2 | Funds and ETFs (SEC N-PORT, N-CEN, risk/return summaries, GLEIF); Knowledge pages and `WHAT`; Construct your basket and the pre-deal what-if for VaR and exposure; trade pages' Risk links; data-quality fixes; About and Competitive rewritten |
| 2.9.3 | `DATAMAP` (Map and Cards, inferred from the packs and contracts, every join checked against the data); the fact of the day on Home (facts declared per pack, computed as the user); the Companies, funds and ETFs Knowledge page; index membership from the tracking ETFs' N-PORT (companies 503 to 586); one firm-wide VaR limit; the test-data-from-seeds plan |
| 2.9.4 | VaR from scenario PVs or P&L, by trade or position, in any reporting currency (five built ahead, the currency picker, trade currency); intraday from files and Kafka micro-batches; the scenario window and confidence as settings; the stand-in pricing system; JSON Lines as the data standard and on every export; 351 pictures retaken; the decks rewritten |
| 2.9.5 | Iceberg as a full alternative to Delta (VaR, intraday and exposure read from Iceberg; official results as Delta or Iceberg, local or S3; Delta = Iceberg to the cent); VaR hierarchies by columns (limit letters, `var-limit`, a missing column skips only its tree); `PNL-` vector ids; airtight connector upgrades | released 2026-10-28 |

## What 2.0.0 contains

The three waves agreed on 2026-10-11 (BI gaps, Python and Analyse, AI-driven BI) shipped together with the work around them, and the documentation was consolidated.

| Area | What is in 2.0.0 | Read |
|---|---|---|
| BI essentials | scheduled refresh of datasets; year-to-date, month-to-date, prior period and running measures; map (world, country, region drill), funnel, histogram, box plot and bubble; true totals for measures that do not add up; dropdown slicers | [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [tutorial 10](../tutorials/10-first-bi-report.md) |
| Several datasets | composed datasets (`from:`, each source asked as the reader, row rules and masks before the join), derived datasets, a Python Transform, a report over several datasets, the Model view for them | [DATASETS.md](../guides/DATASETS.md), [tutorial 10](../tutorials/10-first-bi-report.md) step 11 |
| Python and Analyse | Python measures and visuals (sandboxed server runner, browser target); Analyse: slice, SQL, a Python notebook, promote, over one or several masked extracts | [PYTHON_BI.md](../guides/PYTHON_BI.md), [ANALYSE.md](../guides/ANALYSE.md), [tutorial 14](../tutorials/14-python-and-analyse.md) |
| AI in BI | a report from words, ask the data, explain this number, under the AI policy; seventeen vendor types of model provider with TLS and a Test connection button | [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number), [AI_PROVIDERS.md](AI_PROVIDERS.md), [tutorial 15](../tutorials/15-ai-in-drishti.md) |
| Maps | the `map` panel kind (the 22nd): a country highlighted, regions, a click that opens the entity | [PANELS.md](../guides/PANELS.md#map) |
| Live data | coherent walks: generated live data that moves together (trades, netting sets, counterparties), live tables and reports that follow them with pause and resume | [REPORTS.md section 23](../guides/REPORTS.md#23-boards-report-pages-of-live-tiles), [tutorial 12](../tutorials/12-live-bi-and-boards.md) |
| Console | one top bar on every page (signed in or out); Forms and Reports inside Views; the Live button merged with the stream status; Sajha's crimson, blue and green themes, the Cinzel wordmark and the footer slogan | [USER_GUIDE.md](../guides/USER_GUIDE.md#the-top-bar) |
| Connections | shared, sized database connection pools (`drishti.db.pool.*`) with their metrics in Admin → Health | [CONFIGURATION.md](../admin/CONFIGURATION.md), [OPERATIONS.md](../admin/OPERATIONS.md) |
| Documentation | seventeen tutorials with real screenshots; the help centre cut from 90 pages to about forty in six sections; tutorials teach, guides are reference; every old help address redirects | [docs/README.md](../README.md), [tutorials](../tutorials/README.md) |

## What 2.1.0 contains

Waves A to D close what 2.0.0 listed as "not built" in BI. The one consolidated list of what is still not built is [RUPAKA.md section 20.15](RUPAKA.md#2015-as-built-at-210-and-what-is-still-not-built).

| Wave | What is in 2.1.0 | Read |
|---|---|---|
| A, the long tail | window functions; several unrelated fact tables in one query; incremental live measures; refresh state that survives a restart and Refresh now; the relative-date slicer; tablet layouts; expected-value tests that stop a publish; the entity-view visual; the Region economics sample | [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md) |
| B, governance and boards | grain-correct measures; rename detection and type or meaning impact; import rollback; BI inside pack archives; lineage past the connector; live-table history; tile alert hysteresis; the board designer; one temp folder per process | [BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md), [REPORTS.md section 23](../guides/REPORTS.md#23-boards-report-pages-of-live-tiles) |
| C, Python and Analyse | Vega-Lite visuals; Python on boards and in alerts; tests shipped with a Python item; Calc's `drishti_bi` and Graphic Walker; DuckDB-Wasm in Analyse; promote to a report visual; Perspective charts and the settings panel; measures that mix grains | [PYTHON_BI.md](../guides/PYTHON_BI.md), [PYTHON_CALC.md](../guides/PYTHON_CALC.md), [ANALYSE.md](../guides/ANALYSE.md) |
| D, AI, distribution, embedding | narrative summaries; the designer copilot and the dataset helper; charts in Excel; alert retries; webhook and text channels; `<drishti-about>` and `<drishti-search>`; React, Vue and Angular samples | [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number), [DISTRIBUTION.md](../guides/DISTRIBUTION.md), [ELEMENTS.md](../guides/ELEMENTS.md) |
| Type-ahead index | several business days with date keys; disk segments; automatic and manual reload; `DRISHTI_INDEX_DIR` | [ARCHITECTURE.md](ARCHITECTURE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Documentation | the BI docs read as one set; tutorials 10 to 17 re-followed with new pictures | [docs/README.md](../README.md) |

## What 2.2.0 to 2.5.0 contain

The BI waves of [BI_DESIGN.md section 12](BI_DESIGN.md#122-the-three-waves), then Drishti\*net. Each line's detail is in the [changelog](../../CHANGELOG.md).

| Release | What is in it | Read |
|---|---|---|
| 2.2.0 (wave A) | datasets and data models rooted in packs; DEL v2 with a converter; Drishti SQL; one logical plan for the model query, SQL and Python; field properties, perspectives and the consumer envelope; stored items converted at start | [DATASETS.md](../guides/DATASETS.md), [EXPRESSIONS.md](../guides/EXPRESSIONS.md), [SQL.md](../guides/SQL.md) |
| 2.3.0 (wave B) | `calc`, `share_of` and filter context; calculation groups, field and numeric parameters; balances and time on the model's calendar; standing queries pushed from the server; a tier planner, with Python on the console; production-shaped trades (one UPPER_SNAKE schema from three source systems) and seven packs; committed test data, `lake demo`, an offline market snapshot; lake maintenance | [BI_GUIDE.md section 25](../guides/BI_GUIDE.md#25-live-answers-and-standing-queries-wave-b-230), [data/testdata/README.md](../../data/testdata/README.md), [LAKE_MAINTENANCE.md](../admin/LAKE_MAINTENANCE.md) |
| 2.4.0 (wave C) | BI Studio (Explorer, tabs, Problems and Query dock, the generated Format pane); report schema v2 (conditional formatting, themes, interactions, sync groups, tooltip pages, small multiples, the matrix); boards as a report layout; the Transform editor with a snippets library and profiles; the browser Python console; query by form kept per data version (form search p50 5.8 s to 0.21 s on 35.7 million trades); the CATO calendar; Elements 1.4.2 | [BI_GUIDE.md section 26](../guides/BI_GUIDE.md#26-bi-studio-authoring-as-one-product-wave-c-240), [REPORTS.md section 23](../guides/REPORTS.md#23-boards-report-pages-of-live-tiles), [PYTHON_BI.md section 17](../guides/PYTHON_BI.md#17-data-prep-the-transform-editor-240), [PERFORMANCE.md](../admin/PERFORMANCE.md) |
| 2.5.0 (wave N) | Drishti\*net: membership by gossip, capability digests, the cluster key and its rotation, forwarding and the newest pack served, scatter-gather search and grouped pages, live relay and BI routing, user state across nodes, the console's failover, Admin → Net, `config/net/`, `drishti.py net`; every JVM started with `-Djava.io.tmpdir` | [NET.md](../admin/NET.md), [DRISHTI_NET.md](DRISHTI_NET.md), [tutorial 19](../tutorials/19-a-cluster-of-three.md) |

## Working agreement

- All work happens on **`develop`**. Each wave closes with a green build, updated docs,
  and a merge to **`main`** ("drill it": push `develop`, merge into `main`, push `main`).
- Sole author: Ashutosh Sinha. Commit messages explain *why*; no trailers.
- Every file carries the copyright/proprietary header; build fails otherwise.
- No source file over 1500 lines (UX templates excepted); build fails otherwise.
- Each wave updates `docs/` (guides, ADRs, CHANGELOG) as it goes, not afterwards.

## Wave map

The first ten waves (plus W11, user management, after 1.0.0); all are done. Each held a small set of closely related items and ended with a demo-able exit gate. Later waves are in [Waves 12–21](#waves-1221--capability-roadmap-after-110) and the [domain-pack roadmap](#domain-pack-roadmap-agreed-2026-09-30).

| Wave | Theme | Exit gate |
|---|---|---|
| W1 | Build foundation | `mvnw verify` green with licence-header, file-size and architecture gates; CI runs |
| W2 | Console shell & landing | Console serves the landing page with the hero animation, 5 themes and the public nav |
| W3 | Data model & sources | `demo` and `file` plugins serve the 4 reference entities with provenance and fingerprints |
| W4 | Sutra grammar | Sutra YAML parses, validates against the schema with line-accurate errors, and hot-reloads |
| W5 | Rachana-EL & reference Sutras | Expressions compile; formats resolve; the 4 reference Sutras produce golden layouts |
| W6 | Inference engine | An entity with **no** Sutra renders a sensible layout; "Sutra + inference" merges |
| W7 | Engine pipeline & entity graph | `ViewPipeline` builds full ViewModels in-process with links; warm p99 < 50 ms (JMH) |
| W8 | REST API & console views | The 4 mockups are reproduced in the browser end to end, with command line, F-keys, breadcrumbs and F9 |
| W9 | Live updates | Curves and MTM tick over SSE; top bar shows measured p99; 10k-subscriber soak passes |
| W11 | User management | Admins manage users, roles and passwords; the audit log; seeded `drishti-dev-admin`; `v1.1.0` |
| W10 | Studio, security, ops & release | Sutra Studio, entitlements, Docker, runbooks, `v1.0.0` tagged on `main` |

---

## W1 — Build foundation
- Maven reactor on `spring-boot-starter-parent` (it aligns versions, so there is no BOM module), `mvnw`, Java enforcer (21 then; 25 since 1.13), `.editorconfig`.
- `config/license-header.txt` and `tools/license_headers.py --fix`; Spotless and Error Prone deferred (ADR-007).
- Empty modules with `package-info.java`: `api, common, sutra, inference, graph, engine, server, testkit, it, benchmarks`.
- `drishti-it`: `LicenseHeaderTest`, `SourceFileSizeTest` (1500), `ArchitectureRulesTest` (one-way module deps, Spring-free `drishti-api`, no `Serializable`, no field injection).
- GitHub Actions `fast.yml`; ADRs 001 to 007 in `docs/architecture/adr/`; `CHANGELOG.md`.

## W2 — Console shell & landing
- `drishti-console/run_drishti_web.py`, `drishti-console/config/application.yaml`, properties configurator (YAML → local → env → CLI).
- `web/templates/{base,landing}.html`, `_nav_public.html`, `_theme_menu.html`, `_footer.html` (copyright), `_macros/ui.html`.
- `static/css/{tokens,theme}.css`: `terminal` (default), `light`, `wallstreet` (Bloomberg colour scheme), `blue`, `green`, `crimson` and `crimson-dark` (the Maya palette).
- `static/js/{app,theme,landing}.js`: the JSON → `{◉}` → panels hero canvas, reduced-motion, replay.
- Brand assets in `static/img/`; vendored Bootstrap and Bootstrap Icons.
- Tests: file sizes, licence headers, no inline script, colour contrast.

## W3 — Data model & sources
- `drishti-api`: `EntityRef`, `DataNode`, `Provenance`, `SourcePlugin`, `SourceCapabilities`, `Subscription`, `ReverseLookup`, `PluginManifest`.
- `drishti-common`: config loader, `ErrorCode`/`DrishtiException` (`DRS-1nnn`), `JsonCodec`, `ShapeFingerprinter` (xxHash64).
- `drishti-engine`: `PluginDiscovery` (isolated class loaders), `SourceRouter`, virtual-thread fetch executor.
- `SourcePlugin.search` in the SPI (type-ahead); demo and file plugins index their catalogue in memory.
- `plugins/drishti-plugin-demo`: the 4 reference entities plus curves, CSAs, agreements, counterparties and 14 netting-set trades.
- `plugins/drishti-plugin-file`: JSON/CSV/Parquet directory source; generation from a sequence number.
- Tests: fingerprint stability, plugin isolation, routing by config.

## W4 — Sutra grammar
- `drishti-rachana` model records: `Sutra`, `Match`, `Title`, `Strip`, `Panel`, `KeyMap`.
- YAML parser with source positions; `sutra.schema.json` (since 1.11 the schema is generated from the grammar and served at `GET /api/v1/rachana/schema`); validator emitting `DRS-2nnn` with line/column.
- `SutraRegistry`: versions, lookup by `name@version`, `WatchService` hot reload.
- Docs: `docs/guides/RACHANA_REFERENCE.md` (keys, panel kinds, versioning).
- Tests: parse round-trip, schema negatives, reload races.

## W5 — Rachana-EL & reference Sutras
- Rachana-EL: lexer → parser → AST → compiled closures (paths, `[?x]` filters, ternary, `link()`, `size()`, arithmetic, concat); EBNF in the reference doc.
- `Formats` (bundled `formats.yaml`, site override file) and `Tones`; `SutraMatcher` (the Sutra `match` block is the classifier).
- `examples/packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml`, `examples/packs/finance/sutras/fx/fx-swap.v2.sutra.yaml`, `examples/packs/finance/sutras/commodities/listed-future.v1.sutra.yaml`, `examples/packs/finance/sutras/credit/netting-set.v1.sutra.yaml` (Markdown `.sutra.md` from W5 to 1.10, YAML since 1.11).
- Tests: jqwik property tests for EL; golden `Layout` JSON per reference Sutra.

## W6 — Inference engine
- `drishti-inference`: `ShapeTree`, `SemanticHints` (`drishti-inference/src/main/resources/inference/semantics.yaml`), the rules in ARCHITECTURE §6.
- `CandidateScorer`, `AreaPacker` (strip ≤ 8, right column ≤ 4), `LayoutMerger` (Sutra wins; `infer` holes filled).
- Explanations (rule + score) recorded per inferred panel.
- Tests: an unknown product renders; each reference entity without its Sutra stays usable (golden).
- Docs: `docs/architecture/INFERENCE.md`.

## W7 — Engine pipeline & entity graph
- `CommandParser` (mnemonics from each pack's `pack.yaml`); `SuggestionService` + per-plugin `HitIndex` for type-ahead (mnemonics, recents, parallel `SourcePlugin.search` under a 30 ms budget).
- `ViewPipeline` (ARCHITECTURE §4), `LayoutResolver` with Caffeine caches, parallel `Binder`, `ViewModel` records and a streaming serializer.
- `drishti-graph`: `ReferenceCatalog` (the packs' `graph:`), `LinkResolver` with a deadline and pending placeholders, badges, reverse lookups.
- `drishti-benchmarks`: JMH for fingerprint, EL, bind and the full pipeline.
- Tests: golden ViewModels for the 4 entities; a slow link degrades to pending.

## W8 — REST API & console views
- `drishti-server` (the single Spring Boot application, `DrishtiApplication`, wiring each module's `@Configuration`): virtual threads; `Command` (incl. `/command/suggest`), `View`, `Entity`, `Sutra` and `Source` controllers; `ApiExceptionHandler` (problem+json); springdoc; actuator + Prometheus.
- Console: `core/api_client.py` (pooled httpx), `routes/{terminal,views,help}.py`, `templates/terminal/{home,view}.html`.
- `_macros/panels.html` (one macro per panel kind), `_command_bar.html`, `_fkeys.html`, `_breadcrumb.html`, `_provenance.html`.
- `static/js/command.js`: Bloomberg-style suggestion dropdown (debounced, abortable, ↑/↓/Tab/Enter/Esc, ARIA combobox); `static/js/{keys,view}.js`, `static/js/panels/<kind>.js`, vendored ECharts.
- Tests: MockMvc, pytest routes, headless Chrome screenshots against the mockups.
- Docs: `API_GUIDE.md`, `CONFIGURATION.md`, `USER_GUIDE.md` (the keyboard reference is part of it).

## W9 — Live updates
- `Subscription` in `demo` (random-walk ticker) and a `kafka` plugin.
- `TopicHub` (single writer per ref, JCTools MPSC), 50 ms coalescing, `ViewMaintainer` with a path→panel dependency index, `PatchDiffer`, HdrHistogram.
- `StreamController` SSE with `Last-Event-ID` resume and bounded per-client queues.
- Console `live.js`: patch by `data-path`, flash on change, reconnect banner, live p99 in the top bar.
- Tests: patch minimality, slow-consumer drop-to-latest, 10k-subscriber soak (`-Pperf`).
- Docs: `LIVE.md`, `PERFORMANCE.md` with measured numbers.

## W10 — Studio, security, ops & release
- Sutra Studio (`/studio`): vendored CodeMirror, schema completion, live preview, "promote inferred panel".
- Security: console sign-in (users file, PBKDF2, signed cookies), HS256 tokens to the server, per-role kinds (`drishti.security.roles`), links disabled with the reason, F9 redaction, CSP. *(OIDC was deferred; it shipped in W20.)*
- Plugins `rest` and `jdbc`. *(The `aero` plugin was deferred; it shipped in R5 as `aerospike`.)*
- Ops: Dockerfiles, `deploy/docker/compose.yaml`, Grafana dashboard, `OPERATIONS.md`, `runbooks/`, `TROUBLESHOOTING.md`.
- Release: `RELEASE_NOTES.md`, tag `v1.0.0`, merge to `main`.

---

## W11 — User management (added after 1.0.0)
- `drishti-identity`: `User`, `UserStore` / `FileUserStore` (atomic, mode 600), `PasswordHasher` (PBKDF2), `UserService` (lockout, policy, last-admin guard), `AuditLog`.
- Seed `drishti-dev-admin` / `drishti-dev-admin123` (admin) when the store is empty; warn until it is changed.
- Forced password change is off unless configured.
- Server: `/api/v1/auth/{login,me,password}`, `/api/v1/admin/{users,audit,roles,status}`; an `admin` role.
- Console: sign-in through the server, `/account`, `/admin/users`, `/admin/audit`. The users file is retired (ADR-009).
- *Later (1.10.0):* the file stores were replaced by a JPA database (SQLite by default, PostgreSQL by URL), with
  1.9 files imported once; administrators define roles in Admin → Roles.

## Waves 12–21 — capability roadmap (after 1.1.0)

| Wave | Theme | Exit gate |
|---|---|---|
| W12 ✅ | Help centre and About | `/help` guides and tutorials with search; per-panel help and F1; `/about` with version, Sutras, sources, licence |
| W12b ✅ | Competitive landscape and mobile | `/about/competitive`; every page fits 390 px; add to home screen |
| W13 ✅ | Workspaces | several views on one screen with linked selection, saved per user |
| W13b ✅ | Domain packs | a neutral core; finance and logistics packs; `DRISHTI_PACKS` |
| W14 ✅ | Monitors and alerts | watchlists with live columns; threshold alerts with notifications |
| W15 ✅ | F8 Impact | what depends on an entity (reverse graph), navigable |
| W22 ✅ | Business dates and Delta Lake | `AsOf` in the SPI; USNY business calendar; top-bar date (Live streams, a picked date is static); Delta Lake connector (Delta Kernel, partitions by business date, time travel); named connectors; dated file folders; JDBC `:asOf`; imperfect documents render as "No data available" (ADR-012) |
| W16 ✅ | History | `GET /history/{kind}/{id}/diff` (two business dates and/or "known at" instants; objects by field, arrays by identifier or natural key, numeric deltas, redacted per role, labels from the taxonomy); console **Compare** page and a **known at** control beside a picked date; Delta time travel before the first commit finds nothing, after the last reads the latest |
| W17 ✅ | Structured search | `GET /search?q=` with a friendly syntax over Rachana-EL (`and/or/not`, `=`, `contains`, `startswith`, `1.5m`), scan of the kind's entities on virtual threads within `drishti.search.max-scan` and `budget`, conditions on redacted documents, sort and limit; `where` on the command line opens the results page, and **Watch as a monitor** makes the results a live table |
| W18 ✅ | Export and share | CSV of every table-like panel (tables, ladders, key/value, tabs, charts, bars, surfaces, links, gauges; shown numbers exported as numbers, UTF-8 with BOM), of search results and of comparisons; the document as JSON; a print stylesheet (light, no controls) for print and PDF; **Share** links that carry the business date and the exact known-at instant. Everything reads through the server, so entitlements and redaction apply |
| W19 ✅ | Sutra governance | Studio saves become proposals (validated, with the base text); approvers (`approve` role flag or admin) see the diff and approve (published, hot-reloaded) or reject with a reason; authors withdraw; four eyes; stale approvals refused; JSON store under `drishti.governance.dir`; audited; per-Sutra history (ADR-013) |
| W20 ✅ | Single sign-on | OIDC code flow with PKCE, state and nonce in the console; `POST /auth/oidc` verifies the ID token on the server (JWKS discovery and rate-limited refresh, RSA/PSS/ECDSA via the JDK, never `none`/HMAC; issuer, audience, azp, exp, nbf, iat, nonce); group-to-role map; provisioning on first sign-in; local disables and the last admin protected; audited (ADR-014). The Aerospike plugin was delivered in R5 |
| W21 ✅ | Personal settings | `GET/PATCH /me/settings` (validated: theme, landing page, clock zone, density, flash, pinned entities, search size); the console applies them on every page (theme before first paint, body classes, clock), saves theme changes from the menu, opens the landing page after sign-in, pins from a view's header |

### Domain-pack roadmap (agreed 2026-09-30)

| Wave | Pack | Scope |
|---|---|---|
| W13c ✅ | Per-user packs | admins assign packs to users; users choose active packs; kinds owned by one pack; enforced server-side (1.8.0) |
| R1 ✅ | **risk**: taxonomy | 125 products in ten asset classes, 21 market-data kinds, 24 risk and reference kinds, one source of truth (`tools/packgen/banking/taxonomy.py`) with uniqueness checks |
| R2 ✅ | risk: Sutras | `make_sutras.py` writes 170 Markdown Sutras (125 products, 45 kinds) that double as the data dictionary (YAML Sutras with `description` and `notes` since 1.11, ADR-017); `trade_shape.py` is the trade-document contract; every pack's Sutras load with zero problems (`PackSutrasTest`); drill fails if generated files drift |
| B0 ✅ | banking packs split | `requires:`, `connectors:` and `routes:` in pack.yaml; data domains and packs many-to-many; five packs (core, market-data, trading, market-risk, counterparty-risk) generated from the taxonomy (`tools/packgen/banking/`) |
| R3 ✅ | risk: data | `make_data.py`: thousands of consistent JSON documents (counterparties → netting sets → trades → market data; exposure, CVA, SA-CCR, SIMM, VaR, stress, FRTB, P&L) and a consistency checker |
| R4 ✅ | risk: connector and docs | Delta Lake connector (W22) and named connector instances; pack manifests; `make_docs.py` guides |
| R5 ✅ | risk: databases and feeds | the same data in Aerospike and PostgreSQL with the same tests; public data feeds as separately switchable connectors, off by default |
| P2 ✅ | liquidity risk | LCR, NSFR, maturity ladders, HQLA holdings, funding sources, liquidity stress, intraday liquidity |
| P3 ✅ | climate risk | climate profiles, PCAF financed emissions, NGFS scenarios, climate stress, physical-risk assets, green asset ratio |
| P4 ✅ | operational and non-financial risk | loss events, RCSA, key risk indicators, issues and actions, scenarios, third parties, cyber incidents, SMA capital |
| P5 ✅ | retail banking | customers, deposit accounts, mortgages, card accounts, personal loans, branches, collections cases, IFRS 9 portfolio segments |
| P6 ✅ | genomics and biology | genes, variants, proteins, pathways, samples, sequencing runs, expression studies, clinical trials |
| P7 ✅ | politics and society | jurisdictions, parties, candidates, elections, polls, bills, regions, social indicators |
| P8 ✅ | economics | economies, indicators, central-bank decisions, forecasts, trade flows, labour, fiscal, price baskets |

Waves 16–21 were done after the risk pack, as planned, and shipped in 1.10.0.

Every wave also fixes documentation rot: the README status table, "what works", this plan, the changelog, and any guide the wave touches.

## What shipped after the waves (1.10.0)

Work done after the last numbered wave, grouped by theme. Each item is in [CHANGELOG.md](../../CHANGELOG.md) under
1.10.0 with details.

| Theme | Delivered | Where to read |
|---|---|---|
| Pack inheritance | `extends:` with C3 order, the more specific pack wins, overrides reported; risk packs extend market data and trading (ADR-015) | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#inheritance) |
| Connectors | ActiveMQ and RabbitMQ on a shared messaging base with a persistent state store; S3; Delta Lake on S3 through `LakeStore`; scheduled lake maintenance (`tools/lake/maintain.py`); every connector reconnects by itself | [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) |
| Operations | Admin → Health (connectors, packs, live, server; `GET /api/v1/admin/health`); guarded `/actuator` and `/api/docs` when security is on (`DRISHTI_METRICS_TOKEN`); console `/readyz`; product name and legal notices from configuration | [OPERATIONS.md](../admin/OPERATIONS.md) |
| Identity | Users, roles, saved documents and audit in a JPA database (SQLite default, PostgreSQL), one schema file per database, no migrations; Admin → Roles | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Packs for everyone | Admin → Packs switches packs off and on for everyone (`drishti_pack_state`, audited); packs on disk but not loaded are listed | [PACKS.md](../guides/PACKS.md#switching-packs-off-and-on-admin--packs) |
| Terminal | Pick lists (`TRD MX-200000`, `CPTY north`, `TRD MX-2*0`, `TRD productType=Revolver`, `TRD`; one match opens; case-insensitive; key columns from `columns:`); every table pages and walks with the keyboard; id columns link; 25 suggestions | [USER_GUIDE.md](../guides/USER_GUIDE.md#pick-lists-when-a-command-names-several-entities) |
| Console chrome | A two-row top bar after MAYA's: Views, Build, Admin and Help mega menus; round tools (live, alerts, packs, theme, user menu); gradient themes; one live channel per browser, shared by its tabs (the freeze fixes) | [USER_GUIDE.md](../guides/USER_GUIDE.md#the-top-bar) |
| Documentation | Every guide rewritten example-first; a 10-minute quickstart, a developer guide, a Rachana tutorial and a connector guide | [README.md](../README.md) |

## Since 1.10

Releases 1.11 to 1.21; each is in [CHANGELOG.md](../../CHANGELOG.md) with details and tests.

| Theme | Delivered | Where to read |
|---|---|---|
| Sutras are YAML only (1.11) | One file per Sutra, `<name>.v<N>.sutra.yaml`, starting with `rachana: 1` (missing or unknown: `DRS-2009`); `description`, `notes` and a per-panel `description` for prose; Markdown Sutras no longer read (`DRS-2004`, converted by `tools/rachana/md_to_yaml.py`) | [adr/017](adr/017-sutras-are-yaml.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) |
| Build gates | Error Prone in every compile and Spotless at `verify` (ADR-007 amended) | [DEVELOPER_GUIDE.md](../guides/DEVELOPER_GUIDE.md) |
| Packs while running | Load and unload a pack without a restart (pack overlay, rollback); since 1.19 the packs folder is watched and a new or changed pack is checked and swapped in live; sample packs | [PACKS.md](../guides/PACKS.md) |
| API tokens and clients | Personal API tokens (read-only, then write scopes `design:write`, `design:approve`, `packs:admin`, `loads:write`, `bi:*`, `ai:*`), searches as CSV, a Python client, Excel through Power Query | [API_GUIDE.md (clients)](../guides/API_GUIDE.md#clients-scripts-notebooks-and-excel), [API_GUIDE.md](../guides/API_GUIDE.md#personal-api-tokens) |
| History and freshness | A field over business days, searches compared between two dates, freshness and `stale-after` per connector | [USER_GUIDE.md](../guides/USER_GUIDE.md) |
| One console, many servers | `servers:` in the console; a session per server; picker, `/connect/<id>`, `?srv=` links; public `/public/about` | [adr/016](adr/016-one-console-many-servers.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Derived kinds, notes, shared workspaces, reports (1.12) | Kinds computed from others (`derived`), notes on entities and fields, workspaces shared read-only, scheduled CSV reports, who-viewed-what, a deterministic phrase parser | [PACKS.md](../guides/PACKS.md), [USER_GUIDE.md](../guides/USER_GUIDE.md) |
| Signed pack registry (1.12) | Ed25519-signed, versioned packs installed and rolled back from Admin → Packs | [adr/018](adr/018-signed-pack-registry.md), [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md) |
| Scale and stores (1.13) | A million trades a day measured on eight stores, Calc (Python in the browser), the Pivot tab, twenty panel kinds, JDK 25 | [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md), [PYTHON_CALC.md](../guides/PYTHON_CALC.md), [PANELS.md](../guides/PANELS.md) |
| Build workbench (1.14) | Designs kept on the server, operations with undo, samples, check matrix, propose with evidence | [BUILD_WORKBENCH.md](BUILD_WORKBENCH.md), [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) |
| Java 21, About this page, collaboration (1.16) | Java 21 bytecode and runtime; About this page and the optional *Ask*; share with a note, the directory, the inbox, comment threads, email outbox, retention, legal holds, bridges and snapshots | [COLLABORATION.md](COLLABORATION.md), [adr/020](adr/020-collaboration-records-live-in-the-identity-database.md) |
| The command line (1.17) | `drishti.py` (sutra, pack, data, server, doctor, view, design, docs); `pack make` from JSON Lines to a deployable folder; Admin deploy, history and roll back; offline `pack bundle` | [CLI_GUIDE.md](../guides/CLI_GUIDE.md), [OPERATIONALISING.md](../guides/OPERATIONALISING.md) |
| Data loads (1.18) | `POST /packs/{pack}/loads`, expectations and late data, Admin → Data loads | [DATA_LOADS.md](../guides/DATA_LOADS.md) |
| Connectors as files (1.19) | One YAML file per connector in `config/connectors/`, live reload, Admin → Connectors (list, form, YAML, test, history), the connector API and CLI, pack templates, a detail page per connector and per pack | [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) |
| TLS and proxy (1.19, 1.20) | One set of `tls.*` settings for every connector that can use TLS (Kafka with every security option, ActiveMQ over `ssl://`, RabbitMQ over `amqps://` and the rest, as the [connector matrix](../connectors/TLS.md#connector-matrix) lists); an outbound HTTP proxy for connectors, AI providers and MCP servers | [TLS.md](../connectors/TLS.md), [TLS.md (proxy)](../connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| Retry and no restart (1.19) | Anything that failed to load or lost its connection is tried again with backoff (`drishti.retry`), Retry now in Admin; no setting a pack contributes needs a restart | [OPERATIONS.md](../admin/OPERATIONS.md) |
| Query by form, controls, regex search (1.19) | `FORM <pack>`, the pack `form:` section, multi-select dropdowns, `controls:` on a screen (and the slider, view state in sent shares), safe regular expressions, Excel-style column filters, the zoomed panel | [USER_GUIDE.md](../guides/USER_GUIDE.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) |
| Drishti Elements (1.19) | `<drishti-view>`: live views in another web application's page, no iframe; host applications registered by an administrator (Admin → Embedding) | [ELEMENTS.md](ELEMENTS.md) |
| AI and MCP (1.19) | Pluggable LLM providers and an MCP client, off by default, behind one policy (masking, limits, audit) | [AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md) |
| Schema to pack (1.19) | Sutras and packs from JSON Schema; Build → New pack | [SCHEMA_TO_PACK.md](../guides/SCHEMA_TO_PACK.md) |
| Rūpaka, business intelligence (1.19 to 2.1.0) | Phase 1 shell, workspaces and catalogue (1.19); phase 2 datasets and the query engine, phase 3 reports, the designer and the Model view (1.20); phase 4 publishing and governance, phase 5 live tables, Perspective and boards (1.21); distribution (1.22); the Python, Analyse, AI and multi-dataset waves (2.0); the long tail of waves A to D (2.1) | [RUPAKA.md](RUPAKA.md), [BI_GUIDE.md](../guides/BI_GUIDE.md), [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [REPORTS.md section 23](../guides/REPORTS.md#23-boards-report-pages-of-live-tiles), [BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md) |
| Deep trades and real data (1.20) | Tabs hold panels (a trade, its legs, their cashflows and fixings on one screen); the `world-economy` packs carry real public data (`tools/realdata`; the IMF data is for internal demos only) | [PANELS.md](../guides/PANELS.md), [PUBLIC_DATA.md (world-economy)](../guides/PUBLIC_DATA.md#the-world-economy-data-in-detail) |
| Connectors that compute (1.21) | `news` and `yfinance` source plugins whose documents are computed when asked for; the `news-and-markets` sample packs | [NEWS.md](../connectors/NEWS.md), [YFINANCE.md](../connectors/YFINANCE.md) |
| Forms and Reports in the top bar (1.21) | Two top-bar entries beside BI, with a forms home; since 2.0.0 they are entries of Views | [USER_GUIDE.md](../guides/USER_GUIDE.md) |

## 2.8.0: write-back, reactive connectors and VaR (planned)

Scope agreed by the product owner on 2026-10-24: **write-back**, **reactive connectors** and a **generic VaR engine**. Packs stay as they
are; connectors may be static, live, on-demand or reactive; a screen combines as many connectors as it needs, on one as-of. Waves V0 to V3
of the VaR engine are built (as built: [VAR_GUIDE.md](../guides/VAR_GUIDE.md), Part III); the rest is planned. The designs:

| Design | What it decides |
|---|---|
| [WRITE_BACK.md](WRITE_BACK.md) (section 18 added for 2.8.0) | forms save through connectors; an applied change publishes an event that triggers reactive connectors; updated records and results pushed to viewers |
| [REACTIVE_CONNECTORS.md](REACTIVE_CONNECTORS.md) | the four connector modes; typed parameters on a panel's source; triggers (write-back, live feed, new load, schedule); point-in-time stamps, ordering, idempotence; per-user isolation; coalescing and incremental recompute; one as-of per screen; per-panel failure |
| [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) | out-of-process Python and Java compute workers; the connector's requirements (pinned Python wheels, Java libraries with hashes, plugins) in locked environments, approved by an administrator; the pricer interface with QLRisk as default (design only until its API is stable) and QuantLib's own Java binding in an isolated worker (Linux first; skipped on Windows); reconciles [QUANT_SERVICE.md](QUANT_SERVICE.md) |
| [VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) | VaR, ES, contributions, history and backtesting for any scope (a position, a book, a desk, the bank, any basket of trades) from precomputed 509-day P&L vectors; what-if priced just in time; full drill-down with tables and charts; entitlements and audit; targets for a large bank |
| [VAR_GUIDE.md sections 15, 25 to 29](../guides/VAR_GUIDE.md#29-the-pl-vector-in-the-lake) | positions derived from trades; portfolios by hierarchy, rule and basket, resolved point in time; the vector layout in Delta (one row per position, one array column), the rolling window as daily snapshots, restatements, precision, cubes, the in-memory vector store, sharding and sizing |

**Waves, in order** (each with its docs and CHANGELOG entries in the same push):

| Wave | What | Design |
|---|---|---|
| 0 | the one Drishti database (unchanged from the table above) | [DATABASE.md](DATABASE.md) |
| W1–W5 | write-back as designed (lanes, edit log, approval, cluster, outbox and connector writes, forms and phone) (**built** 2026-10-25, unreleased: [WRITE_BACK.md section 19](WRITE_BACK.md#19-as-built-280-2026-10-25); the S3 edit log, the lake fold of overlays and the per-column `edit:` are not built) | [WRITE_BACK.md](WRITE_BACK.md) section 14 |
| C1–C3 | compute workers: the supervisor and Python pools (Linux and Windows), Arrow transport, locked environments, approval (**built**, unreleased: the `drishti-compute` module, fan-out over N workers and nodes, Admin → Compute, [COMPUTE.md](../admin/COMPUTE.md)) | [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) section 16 |
| R1–R4 | reactive connectors: the plugin and its interface, typed source parameters, cache and single flight, the worker bridge with progress, triggers with coalescing and ordering, per-user isolation (**built**, unreleased: the `reactive` plugin in `drishti-compute`, the SPI in `drishti-api`, panel states in the console, the compute-demo sample pack, [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md), tutorial 23; per-event impacts wait for W6/V5) | [REACTIVE_CONNECTORS.md](REACTIVE_CONNECTORS.md) sections 18 and 21 |
| V0–V3 (built) | VaR data (positions, vectors, hierarchies, vector columns in the lake tools and the Delta plugin), the engine and the VaR explorer, history and backtesting, baskets, captures, entitlements and audit; the portfolio and position pages | [VAR_GUIDE.md section 30](../guides/VAR_GUIDE.md#30-the-design-record) (phased delivery), [section 29](../guides/VAR_GUIDE.md#29-the-pl-vector-in-the-lake) |
| W6–W7 | forms save through connectors; applied changes trigger recomputes; viewers updated (**built** 2026-10-25, unreleased) | [WRITE_BACK.md](WRITE_BACK.md) section 18 |
| C4–C6, V4 | the pricer interface and stand-in; what-if; **QLRisk only if its API is declared stable, otherwise in 2.8.x**; the QuantLib Java worker with its parity test (Linux) | [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) section 9, [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294) |
| R5–R6, V5–V6 | one as-of per screen; the cluster (key owners, sharded vector store); intraday VaR from write-backs; the targets measured on a real server | [REACTIVE_CONNECTORS.md](REACTIVE_CONNECTORS.md), [VAR_GUIDE.md section 27](../guides/VAR_GUIDE.md#27-sizes-performance-and-the-benchmark) |

Open questions for the product owner are listed at the end of each design.

## The computed-measures roadmap: 2.8.0 to 2.12.0 (product owner, 2026-10-25)

**Scope narrowed (product owner, 2026-10-25, later the same day): "I worry we are expanding the scope too much. Just fundamentals and
VaR is good for now."** What is active:
- **Fundamentals:** the data contract of every command ([docs/contracts](../contracts/README.md#16-contracts-of-every-command): one
  contract per kind, the `contract` tool, the generated pages and the command catalog), kept current by tests.
- **VaR, complete** (the 2.8.0 VaR row below; [VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance)): the stored `var` and `var-node` kinds retired, and the VaR
  figures on positions, portfolios and desks computed; positions derived on the server; the full trade set and portfolios; memory
  tiering; the load signal reloading the engine; vectors kept 7 years; the published `var-official` results; the VaR guide. **Part 1
  built** (2026-10-25): everything but memory tiering, the 1 M-trade benchmark, the Python engine and the VaR guide. **Part 2 built**
  (2026-10-26): memory tiering (memory-mapped float32 hot files, lazy history dates), the benchmark (measured at 100,000 × 5,
  extrapolated to 1 M × 50), the computed kinds `var-result` and `var-whatif` with the Python VaR engine, `drishti.py var`, the VaR guide
  and tutorial 25. Not built: sharding across Drishti*net (no mechanism yet: VAR_GUIDE.md section 28), what-if pricing (QLRisk).

**2.9.0: EXP brought forward (product owner, 2026-10-26).** Exposure profiles computed for any scope, exactly as VaR was in 2.8.0
([EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md)), with the stored `exposure-profile` retired in the same release. **Part 1 built** (2026-10-26): the
inputs `simulation-run` and `exposure-path` with their test data (`tools/packgen/ccr`), the Java engine (netting and CSA collateral
per path with the margin period of risk, EE, ENE, EEE, PFE, EPE, EEPE, contributions, scopes `NSET:`, `CPTY:`, `GROUP:`, `LE:`,
`DESK:`, `BOOK:`, `TRD:`, `BANK`, `WHERE:`), the memory-mapped hot file per date (`DRSHEXP1`), `/api/v1/exposure`, the computed kind
`exposure-result` (`EXP <scope>`), the `addon` what-if, captures, the official results `exposure-official` and `-cpty`, retention
settings, the netting-set and counterparty pages' computed figures, and the retirement (the reconciliation of the 76 stored sets in
EXPOSURE_GUIDE.md section 22.2). **Part 2 built** (2026-10-26): the exposure explorer (`/exposure`, `EXP <scope> <GO>`: breadcrumb,
tree, tiles, the profile chart and path fan, the tabs by level, the pre-deal what-if, captures and compare), the Python what-if engine
(`exposure-whatif`, `XWIF`, the exact `paths` method, compute pool `exposure`), `drishti.py exposure` (`tools/expcli.py`), float32
paths in the Delta reader, `drishti.ccr.exposure.kinds.limit` (the stored credit limits read as stored), and the documentation in two
documents by the product owner's rule (one guide and one tutorial per measure): [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) (the
contract EXP.md folded in as its Part II, version 1.2; the old page deleted) and
[tutorial 26](../tutorials/26-exposure-from-the-bank-to-a-trade.md). **Not built in 2.9.0:** the exposure benchmark at a large bank's
size, sharding across Drishti\*net, a restricted list (EXPOSURE_GUIDE.md section 28). The other counterparty measures (CVA, SA-CCR, LIM,
GRP, WWR) stay parked and stored, unchanged; the stored credit limits were sized on the retired profiles, so their use reads very low
against the computed PFE until LIM is brought back.

**2.9.0: VaR in BI (2026-10-26).** The published official VaR results are a read-only kind `var-official` (`VOFF`) of the market-risk
pack on the Delta connector `var-results` over `risk-results/var-official`, with the BI samples (datasets `market-risk-var-official`
and `market-risk-var-latest`, model and report `market-risk-var`: VaR 99% and ES 97.5% by node, never summed) (VAR_GUIDE.md
section 19).

**Built (2.9.2): the Funds and ETFs pack (product owner, 2026-10-26).** The `funds` pack: the 200 largest US mutual funds and ETFs by
net assets from SEC public data (the newest Form N-PORT data set, four quarters of Form N-CEN and of the prospectus risk/return
summaries, the SEC and Nasdaq Trader ticker files, GLEIF's fund relationships). Kinds `fund` (FUND: the fact sheet, with the tabs
Look-through holdings and the fund against its index, as on the approved mock-ups), `fund-holder` (HELD: the funds holding a us-equities
company, also USEQ's Held by funds tab) and `index-funds` (TRACK: the funds tracking an index, also on IDX). Index weights are computed
from SEC data (shares outstanding times the price the filings imply); no prices. A 5.6 MB gzipped snapshot in parts
(`data/testdata/funds`), the refresh script `tools/publicdata/funds.py` (downloads kept in the data folder), contracts, the guide
[FUNDS_GUIDE.md](../guides/FUNDS_GUIDE.md) and tutorial 28. Not built from the candidate: N-MFP (money-market funds) and 13F holdings.

**After 2.9.0: synthetic test data from its generators (to plan).** A plan for regenerating the synthetic test data from the
deterministic generators (`tools/packgen`, `tools/testdata/make_testdata.py`) instead of committing it; public data stays committed. The plan is shown
to the product owner before anything is built.

Everything else in this section is **PARKED**: the 2.9.0, 2.10.0 and 2.11.0 measures (but EXP, above), the groupings and analytics, the DESK/BOOK/TRDR/CPTY
figures other than VaR, and 2.12.0 (AI and GenUI). Their stored kinds stay active and unchanged, and their contracts are kept as designs
in docs/contracts. The other 2.8.0 items stay in 2.8.0 (confirmed by the product owner, 2026-10-25): housekeeping (branding from configuration), Admin → Commands and write-back W1–W7. The
release numbers below are the plan of record for when a parked item is brought back.

**The principle.** Drishti keeps *fundamental* data as it is: masters, trades, terms, market data, public data, and the trade-level
inputs that upstream engines produce. Everything that can be derived from them is **computed when asked**, for any scope, never stored
as a summary. The first case was VaR: the stored `VAR-COMM` record read 446.9m while the engine computed 1.97m for the same
commodities scope. A stored copy of a computed figure drifts, so none is kept.

**The boundary.** Trade-level values stay inputs from the upstream engines: a trade's MtM, its sensitivities, its P&L vectors
and its exposure paths. Drishti computes everything above the trade:
- netting, positions and aggregation over any scope;
- regulatory and risk formulas;
- what-ifs, pre-deal checks, captures and history.

Pricing trades inside Drishti comes later, through QLRisk once its API is stable ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md),
[QUANT_SERVICE.md](QUANT_SERVICE.md)).

**One architecture for every measure:**
- **One common risk Delta lake** holds every input of every measure:
  - trades, derived positions, portfolios and hierarchies;
  - P&L vectors, sensitivities, scenario P&L and exposure paths;
  - CSAs and netting sets, scenarios, market data;
  - later cash flows, the banking book and the loan book.

  Ids are shared across all of it (trade, position, counterparty by LEI, netting set, legal entity), and every pack reads the same lake.
- **The measure framework**, generalised from the VaR engine:
  - per-trade inputs, with additive totals kept per hierarchy node;
  - the measure's non-additive rule applied at query time (a percentile, a square root of correlated sums, caps, ratios);
  - one scope grammar, one memory tiering (memory-mapped date files, lazy history);
  - baskets, captures, official end-of-day captures, entitlements and audit.
- **A Python engine per measure** on the compute worker pools (C1–C3: fork or warm pools, fan-out across workers and nodes,
  approved locked environments). It runs the formulas, the heavy and the what-if work.
- **A reactive connector per measure** (`plugin: reactive`, R1–R4). Every answer is an ordinary kind: it opens by command, link,
  API, CLI or AI, and recomputes on a load, a write-back or a schedule. Repeated asks are merged, users are isolated, and what-if
  sandboxes are supported.
- **Scopes:** `BANK`, `DESK`, `BOOK`, `POS`, `TRD`, `PORT`, `BASKET`, `CAPTURE`, `WHERE`, joined with `+ - &`. New: `CPTY:` (counterparty),
  `GROUP:` (connected group through GLEIF parent links), `NSET:` (netting set) and `LE:` (legal entity).

**The retirement rule.** Every item that moves to compute retires its old version **in the same release**; the old and new versions
never coexist. For each item:
1. The old command, the old ids and every link open the computed page.
2. The stored kind is removed, with its samples, test data, Sutra and connector routes (for CTRD, PTRD, LTRD and ITRD, their entries in the
   trading pack's `derived` connector).
3. A guard test fails the build if a retired kind returns, or if a stored record carries a field a compute engine produces.
4. The computed numbers are reconciled against the old stored ones before the old version goes. The release notes explain each difference.
5. The docs and the CHANGELOG say what was retired, what replaced it and how old links behave.

**How each release is run:**
- Mock-ups come first for every new explorer or page, and the product owner picks.
- Each measure's docs are as elaborate as VaR's: a guide, worked examples, help and tutorials.
- At most two builders work at once:
  - **Track A** takes the main sequence;
  - **Track B** takes housekeeping and the groupings and analytics.
- Each release ends with one drill, a cluster restart, the release and the tag.

### 2.8.0: the foundation, with VaR fully computed

| Item | What |
|---|---|
| Housekeeping | (The "TLS certificate expires 2026-11-14" item is closed: the local cluster uses no TLS; the warning came from TLS tests that mint short-lived certificates on every run.) Branding from configuration only: the product name, owner, copyright and notices come from `ui.*` and `drishti.branding.*`, about 26 literal names in templates and scripts are removed, `ui.owner` is added to the console, and a guard test is added **Branding built 2026-10-25** (guard tests in the console and drishti-it). |
| The common risk lake | One layout and shared ids; today's market-risk and counterparty-risk inputs move into it |
| The measure framework | The VaR engine generalised: per-node additive totals, query-time rules, captures, baskets, official end-of-day captures, memory tiering |
| Scopes | `CPTY:`, `GROUP:`, `NSET:`, `LE:` |
| Engine template | A Python engine plus reactive connector template that each measure fills in |
| Retirement mechanism | Redirects from old commands and ids, removal of stored kinds, the guard test |
| Published official results | Every measure's official end-of-day captures also written to a read-only results area of the lake (`risk-results/<measure>-official`, one row per hierarchy node per date, versioned on restatement, kept 7 years) for reporting and regulatory systems; engines never read it (guard test) ([contracts README section 11](../contracts/README.md#11-what-drishti-answers-computed-kinds-the-api-captures); product owner, 2026-10-25) |
| VaR complete | The old `var` and `var-node` kinds (VAR, VNODE) are retired. The full trade set (about 12,000 live trades), about 25–30 portfolios (one per desk and legal entity, plus rule and basket portfolios). Positions derived on the server by config-driven rules (securities by book and security id, OTC by book and trade; signed quantity; live statuses only). Memory tiering: a float32 hot copy, a memory-mapped file per date, lazy history, official end-of-day captures, and a benchmark at 1M trades and 50 dates (**built**; sharding planned, not built). The computed kinds and the Python engine (**built**). The elaborate VaR guide and help (**built**: [VAR_GUIDE.md](../guides/VAR_GUIDE.md); [VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives), [VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#29-the-pl-vector-in-the-lake), [VAR_GUIDE.md section 3](../guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen)) |
| Admin → Commands | Each command switched on or off by an administrator (the matrix with a side panel and a "what it does" column). A command that is off is blocked everywhere (command, URL, API, CLI, exports, AI, search, links, child panels, BI) with a "not active" answer and its own DRS code. The switches are stored in the Drishti database (both schema files, audited) and spread to every member by gossip; ready for role-based access per command **Built 2026-10-25** ([USER_MANAGEMENT.md (Functions)](../admin/USER_MANAGEMENT.md#functions-func-every-command-and-turning-one-on-or-off); DRS-1016). |
| Write-back | W1–W7 as in [2.8.0: write-back, reactive connectors and VaR](#280-write-back-reactive-connectors-and-var-planned) **Built 2026-10-25** ([WRITE_BACK.md section 19](WRITE_BACK.md#19-as-built-280-2026-10-25), [WRITE_BACK_GUIDE.md, for administrators](../guides/WRITE_BACK_GUIDE.md#for-administrators-write-back-is-off-as-shipped); DRS-9301 to 9306). |

### 2.9.0: trading and market risk

**Parked, not built.** 2.9.0 shipped exposure (EXP, brought forward) and no measure of this table; 2.9.1 to 2.9.5 did not
build them either. They stay designs (their contracts in docs/contracts, status *proposed*) until the product owner brings them back;
the release number is the plan of record for then.

| Order | Command (retired kind) | Computed instead |
|---|---|---|
| 1 | CTRD, PTRD, LTRD, ITRD (`counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades`) | The counterparty, portfolio, legal-entity and index pages over the scope engine: every trade, paged, with no 1,000-member cap, for any business date, plus the group's exposure and market-risk figures |
| 2 | TVAL (`trade-valuation`) | Valuation totals: trade MtM summed and broken down over any scope. NSET, margin calls, P&L explain and SA-CCR all build on it |
| 3 | STR (`stress-result`) | Stress P&L for any scope, from per-trade scenario P&L (full revaluation upstream), and from sensitivities × shocks for scenarios defined on the spot. SCN (scenario definitions) stays fundamental |
| 4 | FRTB (`frtb-sensitivity`) | The FRTB standardised (sensitivities-based) charge: delta, vega and curvature, with three correlation scenarios, for any scope. Adds per-trade sensitivity tables to the lake |
| 5 | SIMM (`simm`) | ISDA SIMM initial margin by netting set, from the same sensitivities in CRIF form with ISDA's weights and correlations (versioned yearly). Includes incremental margin before dealing and reconciliation by risk class and bucket |
| 6 | PNL (`pnl-explain`) | P&L explain from sensitivities × market moves and day-over-day MtM, with the unexplained remainder |

### 2.10.0: counterparty risk

| Order | Command | Kept (fundamental) | Computed instead |
|---|---|---|---|
| 1 | NSET (`netting-set`) | Agreement, CSA and trade membership | Net MtM, collateral, EE and PFE peaks, utilisation, CVA and the exposure profile (today stored copies) |
| 2 | SACCR (`sa-ccr`) | Nothing: retired | Replacement cost, add-ons per hedging set, multiplier and EAD by netting set, counterparty or legal entity; incremental EAD before dealing |
| 3 | COLL (`collateral-balance`) | Holdings: asset, quantity, held or posted | Market value and value after haircut, from market data and the CSA's haircuts |
| 4 | MC (`margin-call`) | Actual calls and their events (workflow) | The expected call (MtM − collateral − threshold, then minimum transfer and rounding), shown beside the actual call |
| 5 | EXP (`exposure-profile`) | Nothing: retired | EE, EPE, effective EPE and PFE at any tenor after netting and collateral, from per-trade MtM per path and tenor. The heaviest data, on the VaR memory tiering. **Brought forward to 2.9.0: parts 1 and 2 built 2026-10-26** (above) |
| 6 | LIM (`credit-limit`) | Limit amount, measure, tenor buckets, approval | Used, utilisation and status from EXP or SA-CCR at any date; headroom before dealing |
| 7 | GRP (`counterparty-group`) | Members and the approved limit | Group utilisation over the GLEIF parent links |
| 8 | CVA (`cva`) | Nothing: retired | CVA and XVA from EXP's exposure profiles and credit curves |
| 9 | Wrong-way risk | (new) | Exposure that rises as the counterparty weakens, from EXP plus credit factors |

### Groupings and analytics (Track B, shipped in 2.9.0 or 2.10.0 as each is ready)

| Command | Computed instead | Status |
|---|---|---|
| LEIG (`lei-group`) | The ownership group from GLEIF relationships, with no 50-member cap | **Built 2026-10-26 (2.9.2)**: the derived connector `gleif-groupings` from `lei-entity`; tree and levels from `DIRECT_PARENT` |
| LEIC (`lei-country`) | Entity counts, lapsed and late renewals per country | **Built 2026-10-26 (2.9.2)**: `gleif-groupings`; every entity listed (no 400-row cap) |
| SIC (`sic-sector`) | Companies, revenue and net income over the sector's companies | **Built 2026-10-26 (2.9.2)**: `us-equities-groupings` from `us-company`; totals of the USD reporters |
| ECG (`country-group`) | GDP, population, growth and inflation summed or weighted over member economies | **Built 2026-10-26 (2.9.2)**: `macro-groupings` from `economy`; growth GDP-weighted, inflation the median |
| CORR (`correlation-matrix`) | Correlations from price history for any factor set, window and estimator | **Built 2026-10-26 (2.9.2)**: the set keeps its factors; `correlation-result` (CORRC) by the reactive Java `CorrelationEngine`, Pearson or Ledoit-Wolf over a window |
| USEQ ratios | Margins and growth from the financial facts | **Built 2026-10-26 (2.9.2)**: worked out by the pages from `REVENUE`, `NET_INCOME`, `FINANCIALS[]` |
| PXH analytics | Returns, volatility and beta from price history (internal use only, like the prices) | **Built 2026-10-26 (2.9.2)**: by the `yfinance` plugin as the history is read; off as shipped |
| FACT changes, IDX totals | A fact's change on the year; an index's members' revenue, net income and divisions | **Built 2026-10-26 (2.9.2)**: worked out by the pages from the values and the member rows |
| EQ beta (`equity` `BETA`) | An equity's beta against its index from both price histories | **Built 2026-10-26 (2.9.2)**: the computed kind `equity-beta` (`EQB`) by the reactive Java `BetaEngine`; the equity page's Beta panel |

The reference is [GROUPINGS_ANALYTICS_GUIDE.md](../guides/GROUPINGS_ANALYTICS_GUIDE.md) (Part II the contracts and the reconciliation
with the retired stored pages), the hands-on companion [tutorial 27](../tutorials/27-groupings-and-analytics.md).

### 2.11.0: balance sheet and regulatory

| Order | Measure | New data needed |
|---|---|---|
| 1 | Concentration and large exposures: top N by counterparty or connected group, % of Tier 1, checked against the 10%, 20% and 25% limits. The OSFI report goes here once its template is supplied (the nearest public returns are OSFI930 Large Exposures, the 2A schedule and EB/ET) | Tier 1 capital; banking-book exposures beside SA-CCR |
| 2 | Liquidity: LCR (HQLA levels and caps, outflow and inflow rates, the 75% inflow cap), NSFR (ASF and RSF factors), OSFI NCCF, cash-flow gaps, intraday liquidity | Contractual cash flows per contract, generated from the trades plus a synthetic deposit and funding book; counterparty type, deposit stability, encumbrance; the factor tables from OSFI LAR and Basel as pack config. The retired example pack `examples/packs/liquidity-risk` (`tools/packgen/liquidity/make.py`) is a starting point for the data |
| 3 | IRRBB: ΔEVE under the six standard shocks, ΔNII | A banking book with repricing cash flows and behavioural assumptions (prepayment, non-maturity deposits) |
| 4 | Credit risk: IFRS 9 / CECL expected credit loss, stage migration, credit VaR | A loan book with PD term structures, LGD, EAD and stage |

Climate risk is out of scope (product owner, 2026-10-25). Operational, model, conduct and reputational risk are not compute measures:
they are served by ordinary packs (records, workflows, write-back).

**Kept as fundamental data (not moved):**
- **Core masters:** counterparty, issuer, legal entity, book, desk, trader, calendar, CCP, clearing account.
- **Counterparty terms:** agreements and CSAs.
- **GLEIF:** entities, relationships and mappings.
- **Market data:** curves, surfaces, cubes, fixings, bonds, equities, indices, correlation inputs.
- **Engine inputs:** P&L vectors (PNLV), scenario sets (SSET), stress scenario definitions (SCN), portfolio definitions (PORT), the hierarchy (HIER), trades (TRD).
- **Public data:** SEC filings and facts, IMF macro and forecasts, regional figures, elections, news.

### 2.12.0: AI and generated UI (parked until 2.11.0 is done)

Parked by the product owner on 2026-10-25 and brought back after the computed measures. The scope is unchanged from the plan it replaces:
- AI, with the Sajha MCP connector and AI management ([AI_MANAGEMENT.md](AI_MANAGEMENT.md)). Drishti's AI layer follows Sajha's
  LLM provider and model design ([AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md)).
- GenUI ([GENUI.md](GENUI.md)): pages an AI client asks for are dynamic Sutras it composes from the UI kit's fragment docstrings.
- Every computed measure is an ordinary kind, so the AI reads VaR, FRTB, SA-CCR, liquidity and the rest through the same tools, with
  the same entitlements, masks and audit.

### After banking and finance: other industries

Once the banking and finance measures are done, the same pattern of fundamental data in one lake, connectors, and computed measures
served by Python engines and reactive connectors is carried to other industries. Each gets its own packs, connectors and compute
engines. Which industries, and in what order, is for the product owner to decide then.

## 2.9.4 (planned): VaR from scenario PVs, in any reporting currency (product owner, 2026-10-26)

**Status (2026-10-27): built on a local branch for the product owner's review, not pushed or merged** (VAR_GUIDE.md sections 15.9 and
15.10, contract 1.5): scenario PVs (`scenario-pv`) beside P&L vectors, FX supplied (`fx-scenario`) or derived (`fx-spot`), crosses through
USD, `DRS-5206` and `DRS-5207`, `ccy` on every answer and `--ccy` on the command line, books per currency on first ask, official records
per `drishti.var.official.currencies`; the demo's Endur trades as scenario PVs. Open: the console's currency picker (mock-ups), the
screenshots of tutorial 25 and VAR_GUIDE on the new figures.

Moved from 2.9.3 to its own release, 2.9.4 (product owner, 2026-10-27). Decided after 2.9.1: the upstream engine gives **scenario PVs** (each live trade revalued under each historical scenario) and the
**base PV**, and FX rates are available; Drishti computes the P&L vectors itself and reports VaR and ES **in a chosen currency**.
The P&L-vector input of 2.8.0 stays supported (a bank sends either).

| Part | What |
|---|---|
| Input | per trade and scenario: `PV` in the trade's currency (or one stated currency), the base PV of the same run, the scenario set's order; FX: today's rates and each scenario's (or derived from the FX history of the scenario dates) |
| P&L at load | `P&L(s) = PV(s) × FX(s) − PV(base) × FX(base)` into the reporting currency; same valuation date for both PVs; same scenario order for every trade; a scenario that failed to price flagged, never zero-filled (a new DRS code) |
| Reporting currency | VaR, ES, components, captures, official results and the basket tray in USD, EUR, CAD or any currency with FX; the currency move is part of the risk, so the same trades give different VaR per currency |
| Engine | vectors per reporting currency built at load (USD always; others on first ask, cached per date), on the existing memory tiering |
| Docs and screens | mock-ups for the currency picker first; VAR_GUIDE Part II (the contract) for PV input; tutorial 25; the Market risk Knowledge page ("From scenario PV to the P&L vector"); test data regenerated as scenario PVs and FX |

Decided by the product owner (2026-10-27): (1) PVs in each trade's own currency (one stated currency for a file is the same thing);
(2) scenario FX derived from the FX history of the scenario dates, the batch's supplied FX used when it sends it; (3) cross rates via USD;
(4) 1-day moves, as today.

### 2.9.4 also: JSON Lines is the data standard (product owner, 2026-10-27; built)

The decisions, final: **generators write JSONL only** (one JSON object a line, UTF-8; generating and loading are separate steps, as
`make_trades.py` already did); **readers keep CSV, JSON and JSONL, CSV deprecated** (logged and named in Admin → Health; `drishti.py
convert`); **every export and download gains a JSONL option** (CSV, Excel, PDF and PNG stay); **Sutra test cases are one
`tests/<sutra>/cases.jsonl`** (the per-file folders read with a warning during 2.9.4); **official results also as JSONL** beside their
Delta tables, the content store's row lists `.jsonl`; `lake build`'s staging rows, `seals.log` and `catalog.json` become JSONL; a
guard test in the drill. The page: [DATA_FORMATS.md](../guides/DATA_FORMATS.md).

| Part | Built |
|---|---|
| Generators | `make_data.py --lake`, `samplegen/lake.py`, `packbuild --lake`: dated lake rows, then `samplegen/load_rows.py` (the same lake, byte for byte); `bulk_trades.py`: JSONL then `lake build`; `comswap_synth`/`note_synth` JSONL (`--array` for the export shape); retired, finance and logistics samples `<kind>.jsonl`; `realdata` dataset `*.jsonl`; the feed `fixing.jsonl`; loaders no longer rewrite samples, tests or feeds |
| Readers | the file connector's CSV deprecated (one warning a file, named in Health); `drishti.py convert` |
| Exports | server (search, SQL, BI runs, `/export/jsonl`, statements, subscriptions, scheduled reports, exposure and VaR captures, the cluster allow-list), console routes and `to_jsonl`, every UI menu, the CLI; the compliance export's `.ndjson` renamed `.jsonl` |
| Sutra tests | `cases.jsonl` (197 folders converted; every writer; `SutraCli`, `ExpectFile`, the pack fragment export) |
| Official results | `risk-results/jsonl/<table>/<date>/` beside the Delta tables (`official.jsonl`, on by default); `-membership-v<n>.jsonl`, `-trades-v<n>.jsonl` |
| Smaller | `samples/catalog.jsonl`, staged lake rows as objects, `seals.jsonl` (the older forms still read) |
| Guard | `tools/test_generators_write_jsonl.py`, `tools/test_exports_offer_jsonl.py` in the drill |

Removing CSV input is for a later release (not yet planned); the per-file Sutra test folders stop being read after 2.9.4.

## 2.9.5 (released 2026-10-28): Iceberg as a full production alternative to Delta; hierarchies by columns (product owner, 2026-10-27)

**Status (2026-10-28): built on develop, before its drill** ([CHANGELOG.md](../../CHANGELOG.md) `2.9.5 (unreleased)`). What was built:

| Part | Built |
|---|---|
| Iceberg plugin parity | `vectors()`, `documents()`, `tableIdentity()`, `inventory()`, `holdsNone()`, `refreshVersions()`; vector columns (`list<double>`); id maps and documents cached on disk across restarts. VaR, intraday (file batches and Kafka windows as Iceberg appends) and exposure read from Iceberg equal to Delta to the cent (`VarFormatEqualityTest`, `ExposureFormatEqualityTest`); the demo's 2026-09-30 gives VAR BANK 112,041,646 on both |
| Lake build | `lake build --format iceberg` (local, `s3://` or a REST catalog; `overwrite-dates` only); `build_demo_lakes.py --store risk=iceberg` (and `risk=delta` back) switching `risk-store.yaml` and `exposure-store.yaml` |
| Official results anywhere | `drishti.var.official.format/storage`, `drishti.ccr.exposure.official.format/storage`: Delta (default) or Iceberg, a folder or `s3://` (Delta commits as exclusive creates); append-only, 7 years; a table keeps its format; BI's `var-results`/`exposure-results` follow the format (`OfficialReaders`) |
| Admin and maintenance | Admin -> Lakes and `lake maintain` for Iceberg (retention under the 7-year floor, relayout, snapshot expiry, orphan files at 72 h at least, dry runs, ownership, schedules); the tables list's Format column |
| AWS scripts | `deploy/aws/iceberg/`: load-day, backfill, maintain, lifecycle, alarms, report, IAM, conditional-write and Glue checks (`--help`/`--dry-run` tested) |
| Hierarchies by columns | `HierarchySpec` (the pack's `hierarchies:` block, `drishti.var.hierarchies` over it); the limit letters (`LL`, `LIMIT_LETTER > DESK`) with `var-limit`; a letter may hold part of a book (positions split, `DRS-5212`); a source without a level column skips that hierarchy alone with a `DRS-5211` warning (Admin -> Health, the picker's *not available*), not a refusal; `DRS-5213`, `DRS-5214`; `LL:` in every scope; official VaR and exposure records for every hierarchy; P&L-vector ids `PNL-<trade>-<set>` (`PV-` still read) |
| Connector upgrades | airtight: an unedited file follows the pack (the template stamp), an edited one gains every missing key, **Use the pack's value** per conflict, `.before-upgrade-….bak`; `ConnectorUpgradeTest` in the drill |
| Docs | the guides above, [EXTERNAL_LAKES.md](../guides/EXTERNAL_LAKES.md) (Databricks Delta or Iceberg), tutorial 25 step 14 and [tutorial 29](../tutorials/29-var-on-iceberg.md) (the risk lake and the official results on Iceberg) |

**Deferred:** the AWS Glue and S3 Tables **live runs** (`deploy/aws/iceberg/check-glue.sh`: written, never run; they need an AWS account,
the product owner's); the format guard of an `s3://` official root has no automated test (S3Mock cannot run in the server's tests) and
was **not run against real S3**; **shellcheck was not run** on the new scripts (not installed on the build machine). The four gaps the
docs review found were fixed in 2.9.5 (CHANGELOG, *Iceberg*): build records for the copied tables, `lake demo` leaving the official
results' readers to the server, a template's own kinds never obsolete, and all-null columns kept in the copy.

The plan as agreed (2026-10-27):

Production may run on Delta Lake or Iceberg. Above the storage plugins Drishti is already identical for both (packs, data contracts,
connector names, engines, BI, UI, Drishti*net); the review behind the AWS Iceberg deck (since merged into `docs/publications/Drishti-on-AWS.pptx`) found what the Iceberg
side still lacks. 2.9.5 closes it, so every capability the Delta side has is built for Iceberg too. Started only after 2.9.4 is on main.

| Group | Part | What |
|---|---|---|
| 1. Production VaR on Iceberg | Iceberg plugin parity | `SourcePlugin.vectors()` (VaR scenario PVs and P&L vectors, exposure paths), `documents()`, `tableIdentity()`, `inventory()`, `refreshVersions()` on the Iceberg plugin, so VaR and exposure load from Iceberg exactly as from Delta; the same tests run on both formats |
| | Official results anywhere | official VaR and exposure results published to Iceberg as well as Delta, and to `s3://` roots in either format (today: a local or EFS Delta root; an `s3://` root fails with `publishError`); JSONL beside them as now |
| 2. Running on AWS | AWS scripts | Iceberg versions of the `deploy/aws/` scripts: load, backfill, maintain, lifecycle, alarms, report (dry-run tested, as the Delta ones) |
| | Catalogs | the AWS Glue catalog run for real (the SigV4 REST client exists, never run against Glue), Glue IAM and endpoint documented; S3 Tables through its Iceberg REST endpoint if it works with the same client |
| | Admin and maintenance | Iceberg in Admin -> Lakes: the maintenance page, schedules, the ownership rule, the 7-year risk retention floor, orphan-file removal |
| 3. Speed and convenience | Disk caches | Iceberg id maps and document caches across restarts, as Delta has |
| | Lake build | `lake build --format iceberg` (and `build_demo_lakes.py`), JSONL in as everywhere |

| 4. Hierarchies | Any number of VaR hierarchies | a third hierarchy beside MGMT and LEGAL, e.g. limit letters (a tree of nodes with VaR limits, rolled up like desk; a bank may have about 700): the `risk-hierarchy` contract's HIERARCHY and LEVEL open, the VaR page's selector listing the hierarchies the date holds, a scope word for a limit letter (`LL:<id>`), official VaR and exposure records for every hierarchy (product owner, 2026-10-27). Assumes a letter groups whole books; a letter holding part of a book would need position-level membership |
| | Hierarchies defined by columns (configurable drill-down) | a VaR hierarchy is configured as an ordered list of columns that every input row carries (scenario-PV, P&L-vector or trade record in the lake), e.g. `LEGAL_ENTITY > DESK > TT_BOOK`, `LIMIT_LETTER > DESK`, `REGION > PRODUCT_TYPE`: the nodes are derived from the data, so any column present on every row can be a level and the drill-down path is configuration; limits attach to node paths from a limits table; a hierarchy whose level column is on each row also handles a limit letter holding part of a book (membership per row, not per book). Validated at load: a configured column missing from a source's rows is refused with a DRS code naming the source and column (product owner, 2026-10-27) |

Deferred (decided later): converting tables between Delta and Iceberg; S3 Tables if the REST route does not work.
Docs: ICEBERG_CONNECTOR, DRISHTI_ICEBERG, VAR_GUIDE (section 29.3), EXPOSURE_GUIDE, LAKE_MAINTENANCE, AWS_DEPLOYMENT, the Windows QA guide
where it changes, and both AWS decks (`Drishti-AWS-Iceberg.pptx` labels move from proposed to built).

## 2.9.6 (released 2026-10-29)

Build, test and close 2.9.5 (drill, main, tag) first; only the editions design doc may be drafted meanwhile. Two builders at most.

| # | Part | What |
|---|---|---|
| 0 | The parallel lake loader | **built (2026-10-28)**: a config-driven, multi-threaded Java loader of JSON Lines (folder or S3) into Delta or Iceberg, memory bounded by `memory.budget-mb`, one commit per business date, shard-aligned files and a load manifest for item 2; `lake build --engine java` ([PARALLEL_LOADER.md](PARALLEL_LOADER.md)). Measured at 10K positions; the 2.3M load is estimated at 1 to 5 minutes, to be measured with item 3 |
| 1 | Editions | **moved to 2.9.7** (product owner, 2026-10-28): built there with the Drishti Risk edition (VaR and exposure first) instead of a VaR-only edition now and a redo later |
| 2 | Scale-out | **VaR sharding built (2026-10-28)**, measure-agnostic machinery ([VAR_SHARDING.md](VAR_SHARDING.md)); several servers as one cluster; VaR sharded (a map-reduce of vectors); consoles without sticky sessions. Target (product owner): many small ECS Fargate tasks each owning a shard of books, auto-sharding and rebalancing, all reading the same Delta or Iceberg lake on S3; any task coordinates and sums the partial vectors; a hot file per shard on its own EBS; replicas auto-scale, Fargate Spot for read replicas. Cost options compared in the BMO deck: A (ECS on EC2 capacity), B (Fargate), C (sharded Fargate) |
| 3 | The scale benchmark | no server for 2.3M (product owner, 2026-10-28): measured locally at **200K positions and 1,500 portfolios**, single server and a 3-node sharded cluster, Python and Java loaders; 2.3M extrapolated and labelled so |
| 4 | Screenshots | the about 271 pictures still showing the old top bar |
| 5 | Delta <-> Iceberg conversion | table conversion between the formats (deferred from 2.9.5) |
| 6 | Stressed and 10-day VaR, ES backtests | |
| 7 | The data map's 11 findings | joins that do not hold (2.9.3) |
| 8 | Test data from seeds | approved; the plan is [its section below](#plan-test-data-from-seeds-approved-for-296-not-built) |
| 9 | Glue and S3 Tables | the code completed and tested on simulators where they exist, with a runbook; the live runs once the product owner's AWS credentials arrive |
| 10 | In-place Delta schema evolution | add a nullable promoted column to an existing Delta table without rewriting it (old files read as null), with an optional backfill from the `doc` column; tested on both formats and in the upgrade drill. Both VaR decks carry a Delta-vs-Iceberg slide and a *when the data's structure changes* section |
| 11 | Plain tables and Unity Catalog | reading plain tables (no `doc` column), driven by per-table configuration; Delta through Unity Catalog (`catalog.schema.table`, credential vending, UC permissions) as an opt-in per connector ([EXTERNAL_LAKES.md](../guides/EXTERNAL_LAKES.md)) |
| 12 | Documentation consolidation | **done (2026-10-28)**: the audit's 20 contradictions fixed from the code; one home per topic (178 to 152 active documents, help 64 to 59 pages); obsolete designs deleted, not archived (git keeps them) |

Item 9's live runs need the product owner's AWS credentials.

## 2.9.7 (future, product owner 2026-10-28; in progress)

Editions and Drishti Risk were brought into 2.9.6 and released there.

| # | Part | What |
|---|---|---|
| 2 | **A client-side renderer (first item, product owner 2026-10-30; both phases in 2.9.7)** | One renderer, in the browser ([CLIENT_RENDERER.md](CLIENT_RENDERER.md), [ADR-022](adr/022-one-renderer-in-the-browser.md), superseding ADR-021's rendering decision): a library that paints Drishti screens from the server's ViewModel JSON so other applications show them while the backend stays in one place. **Phase 1 built (2026-10-30, on a branch for 2.9.7):** Drishti Render 0.1.0 at `static/render/0.1.0/` (plain ES modules, no build, vendored ECharts, no CDN; 49 KB gzip of script, 27 KB of sheet; integrity record and types): all 22 panel kinds and their states, charts, tables, trees, drills, tabs, the layout grid with phone stacking, live patches, a streamed (GenUI) ViewModel, in-place navigation with a back stack and a cancelable `drishti:navigate`, `render(el, {kind, id})` or `<drishti-screen>`; the playground `/render/demo`; a parity suite that draws every pack Sutra, the captured views and the VIEWMODEL.md edge cases both ways (macros and library) in every theme at 390 and 1280 px. **Phase 2 built (2026-10-30):** Drishti Render 1.0.0; the console's entity page (and a workspace pane) drawn by it behind `ui.render` (`server` by default this release, page by page), every console behaviour unchanged on the drawn view, live through its `subscribe()` (`vm:` subscriptions, panels as JSON), About from JSON; the library's `behaviours: 'console'` (funnels, Chart any table, the Pivot grid, the nested pivot, drills in place); Elements 2.0 and the embed API v2 beside v1; the server's `"contract": "1.0"`; the three console fixes phase 1 found; parity on the console's own page in both modes (CLIENT_RENDERER.md section 16). **Next (the release after):** the macros retired, then the Studio and Build previews and the exports moved |
| 2a | Renderer follow-ups | the client-mode stall on a shared item opened from an inbox link (before `ui.render: client` can be the default); the demo defaulting to an entity the server holds; the lens named in a `DRS-4023` hint; **the optional sign-in window `auth: 'popup'`** for hosts without a backend ([CLIENT_RENDERER.md 10.1](CLIENT_RENDERER.md#101-signing-in-on-another-site-the-token-exchange-and-an-optional-sign-in-window-design-product-owner-2026-10-30)); not built |
| 2b | **10-day VaR (moved from 2.9.8, product owner 2026-10-30)** | two methods from the 1-day scenario PVs or P&L the sources already send, no new feed: **√10** (1-day VaR × √10) and **the 10-day sum** (each 10-day scenario the sum of 10 consecutive 1-day P&Ls of the historical days; 509 scenarios give 500 overlapping windows, 250 give 241); VaR, ES, components, contributions and the P&L path for either; **chosen on screen** on the VaR page (a horizon picker: 1-day, 10-day √10, 10-day sum, beside the currency picker, kept in the address and per person), in commands (`VAR BANK 10D`, `VAR BANK 10D SUM`) and in BI; the default 10-day method a setting; every answer and official record names its horizon and method; a slot for full 10-day revaluation (a second vector set, `HORIZON: 10D`) designed, not built. **The confidence chosen on screen too** (product owner, 2026-10-30): a Confidence picker beside Horizon and Currency, **99% the default**, from a configured list (`drishti.var.confidences`, default 95%, 97.5%, 99%, 99.5%; ES keeps its own, 97.5% by default, selectable the same way); the rank follows (99% of 509: the 6th worst; 95%: the 26th); limit use shown only when the chosen horizon and confidence are the limit's own, else named as not comparable; official records stay at the configured confidence and horizon, other choices computed on demand (a different rank of the same sorted vector: no extra data); commands `VAR BANK 95` and `VAR BANK 10D SUM 97.5`. **Stressed VaR left out for now**. **Documented in the same change** (product owner): VAR_GUIDE (a section on horizons and the two 10-day methods: how each is computed, when each is right, its limits for options, the overlapping windows, worked figures on the demo data, the full-revaluation slot), USER_GUIDE (the horizon picker), tutorial 25 (a 10-day step with both methods, CLI and UI, pictures), a Knowledge page entry, the help entries, API_GUIDE and CLI_GUIDE (`10D`, `SUM`, `SQRT`), CONFIGURATION (the default method), the data contracts of the official record (`HORIZON`, `METHOD`), BI_GUIDE (the fields), the decks' VaR roadmap, CHANGELOG |
| 2c | **Stressed VaR (in 2.9.7, Drill B; product owner 2026-10-31)** | VaR and ES on a **stress scenario set** per date (a 12-month stress window, about 250 days, e.g. 2008-07 to 2009-06): the same contract and feed as the normal vectors, a second `SCENARIO_SET_ID` (`HS-STRESS-…`) flagged as the stress set, today's positions revalued under the stress days' moves by the source's pricing system (proxies for instruments that did not exist then are the source's); 1-day and 10-day (√10 and the sum, as 2b), at any confidence; a **Normal / Stressed** choice beside the Horizon and Confidence pickers, commands `VAR BANK SVAR`, `SVAR 10D [SUM|SQRT]`, BI fields; official records with `SVAR`; the **12-week average** for the period statistics; a date without a stress set says so, never zero; bit-equal across shards; a **synthetic demo stress set** from the test-data generators (wider, fat-tailed 2008-like moves, labelled synthetic). Not included: choosing the stress window over a long history (later, separate). Documented as 2b (VAR_GUIDE, USER_GUIDE, tutorial 25, Knowledge, help, API and CLI guides, CONFIGURATION, contracts, BI_GUIDE, decks) |
| 2d | **One comprehensive VaR document (Drill B, product owner 2026-10-31)** | VAR_GUIDE becomes the single complete document on VaR: **methodology** (historical simulation, scenario sets and windows, P&L from scenario PVs, ranks and confidence, ES, components, contributions, diversification, 1-day and 10-day by √10 and the sum, **stressed VaR**, the **stress window selection tool** (as designed, built in 2.9.8), backtesting (2.9.8), period statistics); **the logic and the calculation, worked step by step** with formulas and demo figures; **Drishti's implementation** to the smallest detail (inputs and contracts, intake and refusals, positions, hierarchies and limits, the hot file and tiers, sharding and bit-equality, intraday, currencies, official records, caches, settings, API, CLI, UI, provenance); **the VaR-related regulatory reporting** (Canada and the US, what each report needs and what Drishti produces). REGULATORY_VAR_REPORTING keeps the analysis of reports beyond VaR and links in; VAR_SHARDING's operational detail folded in where it is VaR's |
| 2e | **Every document reviewed and consolidated (Drill B, product owner 2026-10-31)** | target approved: one document per subject per audience, about 89 hand-written architecture, guide and admin documents into about 30 (VaR, exposure, cluster deployment, operations, resources and scale, BI guide and design and language reference, Sutra guide and reference, client renderer, data guide and data architecture, packs, editions, AI, write-back, user guide, plan); generated references, tutorials, connector READMEs and Knowledge kept; the old QA round reports removed after a summary; content moved never dropped; a list of proposed deletions put to the product owner before anything is removed; all ~350 documents under docs/ read: duplicates merged (one document per subject), stale statements corrected against the code, obsolete documents deleted (git keeps history), links and the help map updated, docs/README.md as the one index; the one configuration document (C1–C3) is part of it; tests (docs rot, help links, knowledge, contracts) green |
| 2f | **Screenshots (Drill B, the long-pending item)** | every picture in the guides, tutorials, Knowledge and decks retaken from scratch stacks with the repo's shot scripts (about 271 with the old top bar, plus the new screens: editions, the renderer, Admin → Caches, Health → Disk, the VaR pickers, stressed VaR, the scenario matrix); each picture checked by eye; the image tests green |
| 2g | **Java and Python API references, available in the console (Drill B, product owner 2026-10-31)** | **Javadoc** for the public Java surfaces (`drishti-api`: the plugin and connector interfaces; `drishti-compute`'s extension points: `ShardedMeasure` and the shard machinery; anything a plugin or pack developer touches), with doclint in the drill (a broken link or an undocumented public member fails); **pdoc** for the public Python surfaces (the compute-worker SDK, BI Python Transforms, the `drishti.py` CLI and its client library, the pack and test-data generator helpers), with a check that every public function and class has a docstring and type hints; both **generated at build time, never committed** (no generated HTML in git), versioned per release, self-contained (no CDN), **packaged with the console** and served at **Help → Developer** beside the REST API (OpenAPI) and the renderer's API (`.d.ts`, ELEMENTS); also attached to each GitHub release as a zip for readers without a running console; the developer guides link straight to the classes and functions |
| 0 | **Arrow where it pays (first deliverable, product owner 2026-10-30)** | Where Arrow is used today: Java to the Python compute workers (Arrow IPC files, memory-mapped), BI answers (Arrow or JSON), the console's Python (pyarrow). Where it is not, and whether it should be: **the VaR hot files** stay our own format (float32 position rows in hierarchy order, memory-mapped, float64 node totals): for summing vectors it is better than Arrow, about 70 GB/s on 24 threads, near the machine's memory limit; **the lake** stays Parquet (Delta or Iceberg), Arrow's partner on disk; **shard partials** between servers stay raw vectors of doubles (Arrow would add framing, no gain). **Built (2026-10-30, on a branch for 2.9.7), measured first:** `Accept: application/vnd.apache.arrow.stream` answers a BI visual's answer and a VaR contribution table as Arrow (the JSON answer cell for cell: the table the record batch, repeated text as dictionaries, the rest in the schema's metadata; server `ArrowAnswers`, console `core/arrow_answer.py`), read in the browser by `drishtiFetchTable` with Arrow JS 21.1.0 vendored. **The browser uses it where it won:** the VaR contribution tables by position and by trade (1,173 and 1,281 rows: 692 and 632 KB of JSON to 243 and 244 KB, gzip 15 to 21% smaller, fetched in 59 to 61 ms against 81 to 91 ms, painted in the same time). **Stayed JSON, measured:** the 509-point P&L path (no smaller, gzip larger), a node's few children, the BI grid (5,000 and 50,000 trades: 36% smaller raw, 8 to 14% gzip, slower to parse, painted no sooner); search is HTML drawn on the server (1,000 rows: a 680 KB page whose rows are 124 KB as JSON, 74 KB as Arrow: drawing it in the browser is left to row 2). Entity pages stay JSON. [DATA_FORMATS.md section 6](../guides/DATA_FORMATS.md#6-arrow-to-the-browser-where-it-pays) |
| 1 | Soak-proofing | the long-run audit's items: background loops that restart themselves, connector tasks cancelled on restart, a restart policy for connectors that are down, one cache memory budget, mapped id maps released, shard books and access views released, write-back caching, the console's stream pool, stream timeouts, dead subscriptions, log rotation |
| 3 | Scale benchmark | 200K positions and 1,500 portfolios locally: single server against a three-node sharded cluster, Python against Java loader; written up, and in both VaR decks |
| 4 | Computed attributes | **built (2026-10-30, drilled, on develop and main)**: the quick wins and fixes of [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md) (netting-set and counterparty MtM, the Sutra checks, stress per scenario, the test-data fixes, the exposure engine on its fundamental sources) |
| 5 | Carried from 2.9.6 | stressed and 10-day VaR with ES backtests; plain tables; in-place Delta schema evolution; Delta and Iceberg conversion; Unity Catalog; AWS Glue and S3 Tables; the data map findings; test data from seeds; the deck clean-up; about 271 screenshots |
| 6 | Drishti World edition | **built (2026-10-29, on a branch for 2.9.7)**: `config/editions/world.yaml` (**Drishti World**): the world-economy, news-and-markets, us-equities, gleif and funds packs (allow-only), their 32 commands, Knowledge pages, the world-economy BI datasets, help and branding; its three landings (World desk, Explorer, World map) from the approved mock-ups, filled from the packs' own answers; the landing mechanism generalised (each edition names its landings, template and titles); two lenses side by side (`drishti.lenses: risk,world`, "Switch to" between them); the stack runs `risk,world`. IMF and Yahoo data shown inside the bank (product owner, 2026-10-29); an external showing still needs a decision. [EDITIONS.md](EDITIONS.md) section 12 |
| 7 | Drishti Market edition | **built (2026-10-29, on a branch for 2.9.7)**: `config/editions/market.yaml` (**Drishti Market**): the market-data pack and the core it extends (allow-only), its 22 commands (`CRV` … `EQB`), no trades and no VaR, the Market data Knowledge page, help and branding; BI kept to `market-data-*` names (the pack ships no dataset yet); its three landings (Market desk, Explorer, Quality) from the approved mock-ups, figures worked out from the pack's own answers by `core/market_landing.py` (changes only where both days hold real data, biggest moves in σ of their own history); the Quality checks the data supports (stale with `quality.stale-days`, gaps, discount factors and negative forwards, vol surfaces); three lenses (`drishti.lenses: risk,world,market`), which the stack runs. [EDITIONS.md](EDITIONS.md) section 13 |
| 8 | Phone display, configured (design) | which panels go to a phone, configured not coded: four tiers (must, glance, more, desk) resolved over five layers (kind default, fragment, Sutra, site/pack/edition with caps, the person's pins); the server binds only must and glance panels for a phone (`X-Drishti-Device`), more loads on tap, desk opens on desktop; design in [MOBILE_DISPLAY.md](MOBILE_DISPLAY.md), decisions in its section 11; not built |
| 9 | Drishti Trading edition | **built (2026-10-30, on a branch for 2.9.7)**: `config/editions/trading.yaml` (**Drishti Trading**, for traders and desk heads): the five packs of Drishti Risk (allow-only), 33 commands (blotter, books and positions, risk on my book, counterparties, market data) with `VAR` and `EXP`, no regulatory, stress, collateral operations or macro economics, Build and Studio off, the trading and VaR BI datasets; "my" scoping by configuration (`trading.people`), then a matching trader record, then a View as choice kept in the person's settings (`tradingView`), never widening access; its three landings (Trader desk, Desk head, Explorer) from the approved mock-ups, figures from the servers' own answers by `core/trading_landing.py` (P&L from valuations on both dates, the engines' VaR, limits and official records), and the new-trade check from one VaR what-if and one pre-deal check; four lenses by default (`risk,world,market,trading`). [EDITIONS.md](EDITIONS.md) section 14 |

### 2.9.7: the order of the remaining work, and the memory and CPU diet (product owner, 2026-10-30)

**Order:**
1. The client-side renderer, both phases.
2. The benchmark (200K positions and 1,500 portfolios, the concurrency test) **with profiling**: JFR recordings, heap histograms, GC and
   allocation profiles, as the baseline, on **Java 21 and Java 25** both.
3. **The memory and CPU diet** (below), each change measured against that baseline.
4. The benchmark again, as the "after" figures for the decks.
5. Soak-proofing.
6. The small fixes.
7. The release (version bump, notes, full drill, tag).

**Java 21 and Java 25 stay supported** for now: the build targets 21, every drill tests both, and no change may need a 25-only API.
Where 25 helps for free (compact object headers, virtual threads without pinning), it is switched on when the JVM is 25, as the Docker
image already does.

**The diet, in order of likely payoff (each to be confirmed by the profile first):** the design of every item below, with today's code
and settings, configuration keys, tests and measurements, is [RESOURCES_AND_SCALE.md](RESOURCES_AND_SCALE.md) (sections 5 and 6; its
build order in section 11, its decisions in section 12).

| # | Where | Change | Expected |
|---|---|---|---|
| D1 | The heap skeleton on every shard (ids, columns, node totals of all positions, about 2 GB a date at 2.3M; explained in [VAR_SHARDING.md 5.1](VAR_SHARDING.md#51-the-skeleton-in-plain-words)) | memory-map the ids and columns like the hot file, or shard the skeleton with remote parts fetched on demand | heap per shard owner from about 36 GB toward 12–16 GB at 2.3M; the capacity slides follow · not built (RESOURCES_AND_SCALE 14.23 says exactly what remains) |
| D2 | Ids and repeated text held as separate strings | dictionary encoding: one copy per distinct value, small integers in rows | 20–40% of the non-vector heap · **built (2.9.7)**: the id maps (14.22); the ids as strings remain |
| D3 | Caches with separate limits | one shared memory budget, least used evicted first (soak audit) | steady memory over weeks · **built (2.9.7)** (14.20) |
| D4 | Mapped id maps, shard books, access views outliving their date | released with the date (soak audit) | no slow growth · **built (2.9.7)** (14.2-14.6, 14.23) |
| D5 | Compact object headers only in Docker | on wherever the JVM is 25 (the stack, the docs) | 10–20% less heap, free · **built (2.9.7)** (14.18) |
| D6 | Pages drawn on the console | the client-side renderer (first item) | console capacity 2 to 3 times · the renderer (items 1 and 2) |
| D7 | JSON in and out | the browser fetches ViewModels directly after the renderer; Arrow for large tables (built for contributions) | less CPU on server and console · after the renderer |
| D8 | Cold loads (Parquet decode, JSON parse) | profile, then parallel decode per row group, skip unneeded columns | faster first open and overnight load · left for the benchmark (14.23) |
| D9 | Logging volume | quieter defaults, summaries every N batches | less CPU and disk · **built in part (2.9.7)** (14.8, 14.21) |
| D10 | Virtual threads pinned by `synchronized` on Java 21 | the hot ones to `ReentrantLock` (helps 21; 25 is already unpinned) | throughput under many users on 21 · **built (2.9.7)** (14.9) |

**Caches, disk and loads: visible and bounded** (product owner, 2026-10-30; done with soak-proofing; designed in
[RESOURCES_AND_SCALE.md](RESOURCES_AND_SCALE.md) sections 3, 4, 7 and 8, which adds O9, a version watch for the engines' open dates):

| # | Where | Change |
|---|---|---|
| O1 | **Admin → Caches shows the details** | today a name, a kind and raw stats; then, per cache: where it lives (heap, off-heap, disk folder), size now against its limit (MB/GB and entries), TTL and age of the oldest entry, hit rate, the dates it holds (hot files: each date, open or on disk, its size, its inputs' data versions), last purge and by whom; totals for memory and for disk · **built (2.9.7)** (14.12) |
| O2 | **Purge a date** | Admin → Caches "Purge a date" (one cache or all), `POST /admin/caches/{name}/purge?date=D`, `drishti.py server caches purge <name> --date D` · **built (2.9.7)** (14.12) |
| O3 | **Reload one date** | `--date D` on `drishti.py var reload` and `exposure reload`; exposure gains the per-date API VaR has · **built (2.9.7)** (14.13) |
| O4 | **Announce a load from the UI** | Admin → Packs → Data loads → "Announce a load" (kind, date, status, rows, batch), the same as `data landed` and `POST /packs/{pack}/loads` · **built (2.9.7)** (14.14) |
| O5 | **Hot files bounded by size** | `max-disk-gb` for VaR and exposure hot files (least recently used first, beside `disk-dates`), and a free-space guard: below the threshold no new date is built and the answer says why; the disk never fills · **built (2.9.7)** (14.11) |
| O6 | **Old id-map versions** | each date keeps its current and previous table version's id map; older ones deleted · **built (2.9.7)** (14.4) |
| O7 | **Admin → Health → Disk** | every folder under the data folder with its size, limit and free space; an alert when one nears its cap or the disk nears full · **built (2.9.7)** (14.15) |
| O8 | **A soak test** | many dates and table versions in a row: memory and disk stay flat · **built, short form (2.9.7)** (14.17) |

**One configuration document, complete by construction, also in the UI** (product owner, 2026-10-30):

| # | Part | What |
|---|---|---|
| C1 | **One document** | `docs/admin/CONFIGURATION.md` becomes the one and only place for every setting: how configuration works (precedence, placeholders, environment variables, recipes) followed by **every key of every kind**, the same row shape everywhere: key, type, default, environment variable, applies to, meaning, restart needed or live, since. Areas: the server (`application.yaml`), the console (`application.yaml`, `menus.yaml`, `help.yaml`, `workspaces.yaml`, `datamap.yaml`, …), every connector plugin (`plugin-settings.yaml`), packs (`pack.yaml`), editions (`config/editions/*.yaml`, `features.yaml`), the content store, BI runs, write-back, Sutras' configuration keys (with the full grammar staying in RACHANA_REFERENCE, linked), the environment variable index. The scattered settings tables elsewhere become links to it |
| C2 | **Generated, never stale** | a generator in tools (proposed name settings_catalogue.py) builds the reference sections from the sources (the YAML files with their comments, `plugin-settings.yaml`, schemas for the files that have none yet); the explanatory sections stay hand-written; `--check` in the drill fails when a key has no meaning or default, or the document is out of date |
| C3 | **In the UI** | Help → **Configuration**: the same document, searchable (by key, env var or words), each key with its meaning and default; for administrators, the **value in effect** on this server beside the default and where it came from (file, environment variable, override), secrets never shown; linked from Admin → Health and from every Admin page's settings |

VaR's sum (about 70 GB/s, at the memory limit), node answers (under 1 ms), the hot-file format, Parquet and the shard partials stay as
they are. Any vectorised sum keeps a fixed order, so VaR stays equal to the bit across shards.

## 2.9.8 (regulatory VaR reporting, phase 1; product owner 2026-10-30; not started)

Reports produced automatically with human sign-off: the measures, the backtesting engine and a report-definition framework, proved on one pilot pack. The analysis, its sources and the gaps G1 to G15 are in [REGULATORY_VAR_REPORTING.md](REGULATORY_VAR_REPORTING.md); the framework's design is its [section 8.1](REGULATORY_VAR_REPORTING.md#81-the-report-definition-framework-298). What was planned for 2.9.8 moved to 2.9.9 (product owner, 2026-10-30).

| # | Part | What |
|---|---|---|
| 1 | 10-day VaR (G1) | **moved to 2.9.7 (row 2b)**: √10 and the 10-day sum; full 10-day revaluation stays a slot |
| 2 | Stressed VaR (G2), **moved to 2.9.7 (row 2c, product owner 2026-10-31)** | a configured 12-month stress window (250 scenarios), 1-day and 10-day, weekly at least; the capacity model already sizes 1,518 values a position |
| 3 | Actual and hypothetical P&L (G3) | a data contract and connector for the bank's daily P&L per desk: actual (from finance, without fees, commissions, reserves, net interest income, intraday) and front-office hypothetical; Drishti's own risk-theoretical P&L kept and named as such |
| 4 | Backtesting engine (G4) | VaR 99% (and 97.5%) against actual and hypothetical P&L, bank and desk; exceptions over 250 days, the traffic-light zone and the capital multiplier (US 3.00 to 4.00); computed on demand, never stored; the backtesting chart |
| 5 | Period statistics (G5) | high, low, average and period-end over a quarter or a year; 60-day averages; 12-week stressed-VaR averages; over the stored official runs |
| 6 | Report-definition framework | one YAML per report (lines and cells mapped to computed measures and scopes), a generator (filled template, an extract, a review report in BI), checks before review (cross-report consistency, sums to totals, movements explained, missing data), the period frozen with every figure traced to its official run and data versions, a maker-checker sign-off record; submission stays with the bank's reporting team |
| 7 | Pilot pack | the annual report's VaR table (1-day 99% by risk type with diversification; year-end, average, high, low) and the backtesting chart: public, well defined, they prove the chain official run → measures → report → checks → sign-off → file |
| 8 | **Stress window selection tool (product owner 2026-10-31)** | which 12-month stress period is worst for today's portfolio: node-level long-history P&L series (for example 2007 to today, from the source or a monthly batch) → rolling 250-day VaR across every window → the top candidates, by bank and desk, against the current window; an alert when another window overtakes it by a threshold; the analysis frozen with its inputs and the window approved through the 2.9.8 sign-off framework, then used by stressed VaR; a position-level full run later, monthly, if needed; documented in VAR_GUIDE |

## 2.9.9 (moved from 2.9.8 on 2026-10-30; candidates, not started)

| # | Part | What |
|---|---|---|
| 1 | Market data impact | the Quality landing's Impact card: who uses each flagged point (VaR scenarios, the valuations of which trades) and the VaR at stake if it is wrong, computed, never invented; needs the market data linked to the VaR and valuation inputs that use it |
| 2 | More quality checks | jumps over n σ against each item's own history as a check (the desk already ranks moves this way); two sources disagreeing (needs a second source per kind); calendar and smile checks on the commodity, cap and swaption grids |
| 3 | **Regulatory reports (scoped here, product owner 2026-10-30)** | the report packs on 2.9.8's framework, each definition mirroring the regulator's **current published template** cell for cell (taken from the regulator or the bank's regulatory-reporting team before it is built): **Canada** BCAR schedule 90.010 Market Risk (the sections that apply), Pillar 3 MR1 and MR2 (MR2's backtesting exceptions against 99% VaR), the annual report's VaR table (from the 2.9.8 pilot); **US** FFIEC 102 (VaR, stressed VaR when built, averages, backtesting exceptions), the §217.212 disclosure tables, the Volcker metrics (VaR per desk, limits and usage) in their electronic format; **internal**: a board and risk-committee pack (VaR, limits, breaches, backtesting) as PDF in a configurable house style (logo, layout, sign-off block). Outputs xlsx, csv and PDF; XBRL or a regulator's electronic format per report later; VaR by risk type (G6); phases 3a (FRTB standardised) and 3b (FRTB internal models) after that ([REGULATORY_VAR_REPORTING.md section 8](REGULATORY_VAR_REPORTING.md#8-a-proposed-roadmap)) |

## Known gaps

What is open today. None blocks normal use; each is a candidate for a future wave.

| Gap | Today | Where it shows |
|---|---|---|
| Several console processes | A tab's live channel lives in one console process: the workers of one console pass a channel's calls to the worker that holds it (2.4.0), but several consoles behind a load balancer need sticky sessions | [LIVE.md](LIVE.md) |
| Rūpaka, not built | The Python Source interface, a pool of pre-forked Python children, escalation of alerts, a Data tab and Notes in Analyse, a large-screen layout; the full list is in RUPAKA.md section 20.15 | [RUPAKA.md](RUPAKA.md) |
| Server-side pricing | Not built: the QuantLib service is a proposal | [QUANT_SERVICE.md](QUANT_SERVICE.md) |
| A million trades a day on a real server | The numbers in the scale document are measured on one workstation at the sizes its safety rule allows and extrapolated beyond | [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md) |

## Roadmap

**Agreed after 2.1.0 (2026-10-12):** the Rūpaka model architecture and waves W1–W16 for 2.2.0 to 3.0 — see
[BI_DESIGN.md section 14](BI_DESIGN.md#14-the-model-architecture-decisions-d1-to-d6); the gap check is [BI_DESIGN.md section 11](BI_DESIGN.md#11-gap-check-against-power-bi) (the 2.1 study behind it, BI_PARITY_PLAN.md, was deleted: in git at tag v2.9.5; its item ids are [BI_DESIGN.md section 11.1](BI_DESIGN.md#111-the-21-studys-item-ids)).

**Re-ordered again on 2026-10-25 (supersedes the release numbers below):** 2.8.0 is the measure foundation with VaR fully computed, Admin → Commands and write-back; 2.9.0 trading and market risk; 2.10.0 counterparty risk; 2.11.0 balance sheet and regulatory; 2.12.0 AI and GenUI (parked until then); then other industries. See [the computed-measures roadmap](#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25).

**Next releases (re-ordered 2026-10-18, twice on 2026-10-19, on 2026-10-21 and on 2026-10-22: 2.7.0 Sutra Next phase 1+ ([SUTRA_NEXT.md](SUTRA_NEXT.md); the UI Board stays parked on branch `ui-board-preview`), 2.8.0 write-back, 2.9.0 AI with GENUI.md folded in):** 2.5.0, Drishti*net, 2.6.0 and 2.6.5 are released (2026-10-19 and 2026-10-20); 2.6.0 was wave D (it starts with no known bug; paginated reports and statements, field parameters in report wells, the Cassandra connector, drishti-iceberg (Iceberg without Hadoop); on 2026-10-20 send and push and integration (PG-wire, Excel feed, embed tokens, SDK events) moved after 3.0.0 and the Sajha MCP connector to the AI release, now 2.9.0); 2.6.5 was history queries (`TRD 1000015 30 <GO>`: an entity over time, [HISTORY_QUERIES.md](HISTORY_QUERIES.md), added between 2.6.0 and 2.7.0 on 2026-10-20); 2.6.6 is the QA-clean release (QA round 3 fixed); 2.6.7 is BI runs ([BI_RUNS.md](BI_RUNS.md), decided 2026-10-20: every BI query through one bounded asynchronous pipeline, the Runs tab, super scale as the architecture at every size), with gossip off the busy path and HTTP/2 for peer data forwarding ([DRISHTI_NET.md](DRISHTI_NET.md#the-transport-between-nodes-as-built-267)) chart any table ([TABLE_CHARTS.md](TABLE_CHARTS.md)) and mixed Delta and Iceberg storage per source system ([DEMO_DATA.md](../connectors/DEMO_DATA.md#mixed-delta-and-iceberg-storage)), all decided 2026-10-20; 2.6.7 is released (2026-10-21). Next: 2.7.0 is Sutra Next phase 1+ (built, unreleased); 2.8.0 is wave W, write-back ([WRITE_BACK.md](WRITE_BACK.md), item 0 the one Drishti database); 2.9.0 is wave I, AI, with the Sajha MCP connector, GenUI ([GENUI.md](GENUI.md)) and AI management ([AI_MANAGEMENT.md](AI_MANAGEMENT.md)); the real-life QA and wave E (QA findings and production readiness) follow; then 3.0.

Everything planned up to 2.4.0 is built (see the changelog, [What 2.0.0 contains](#what-200-contains), [What 2.1.0 contains](#what-210-contains) and [What 2.2.0 to 2.5.0 contain](#what-220-to-250-contain)); 2.5.0 (Drishti\*net) is released (2026-10-19); 2.6.0 (the known-bug pass, statements and field parameters, the Cassandra connector, drishti-iceberg with `iceberg-external`) and 2.6.5 (history queries, [HISTORY_QUERIES.md](HISTORY_QUERIES.md)) are released (2026-10-20); 2.6.6 (QA clean) and 2.6.7 (BI runs, [BI_RUNS.md](BI_RUNS.md); gossip off the busy path, HTTP/2 between nodes, chart any table, [TABLE_CHARTS.md](TABLE_CHARTS.md), and mixed Delta and Iceberg storage) are released (2026-10-21). Next: 2.7.0 Sutra Next phase 1+ → 2.8.0 write-back (item 0 the one Drishti database, [DATABASE.md](DATABASE.md)) → 2.9.0 AI and the Sajha MCP connector → the real-life QA → 3.0.0.

**2.8.0 (write-back; was 2.7.0 until the re-order of 2026-10-21; 2.7.0 is Sutra Next phase 1+ since 2026-10-22), in order (product owner, 2026-10-20):**

| Item | What | Design |
|---|---|---|
| 0 | **The one Drishti database:** every Drishti-owned relational table (identity today; the edit log, lanes, overlays and owned data next) in one database; SQLite by default, PostgreSQL long term; no migrations: two complete schema files with a parity test, a schema version stamp, a planned `drishti.py db copy` to switch or upgrade; its own file `config/db/database.yaml` (neither was built yet) | [DATABASE.md](DATABASE.md) |
| W1–W5 | Write-back, with **the edit log on that database**: `drishti_edit_lane` and `drishti_edit_log`, acknowledged after commit, ordered by seq, fenced by epoch; SQLite on one node (W1), PostgreSQL or S3 across a cluster, or one edit primary (W3); source commands through the outbox and connector `writes:` (W4); off by default | [WRITE_BACK.md](WRITE_BACK.md) sections 5, 7, 11, 12, 14 |
| C, R, V, W6–W7 | Compute workers, reactive connectors, the VaR engine, and write-back's triggers (added 2026-10-24) | [2.8.0: write-back, reactive connectors and VaR](#280-write-back-reactive-connectors-and-var-planned) |

The long tail of BI gaps that the product owner trimmed on 2026-10-11 (window functions, several fact tables in one query, relative-date slicers, per-device layouts, rename detection, BI in pack archives, import rollback, incremental aggregates, live-table history, a board designer) was built after all, in 2.1.0. The platform loose ends (live bridges and plugin jars, proxy gaps, AI streaming and stdio MCP) remain candidates, not plans.

Later candidates: server-side pricing (the QuantLib service), Windows proven on a real machine, a real deployment (single
sign-on, HTTPS, the images together), scale on a real server, load with many users, several console processes without
sticky sessions, delete handling in the Redis connector.

## Risks

| Risk | Mitigation |
|---|---|
| Inference produces noisy layouts | Scored rules with density limits; golden tests; Studio "promote" loop |
| EL grows into a scripting language | Closed grammar, no loops/assignment, reviewed via ADR |
| Live fan-out cost at scale | Coalescing, dependency index, bounded queues, perf gates in W6 |
| Mockup fidelity drifts | Screenshot comparison tests from W5 onwards |

## Plan: test data from seeds (approved for 2.9.6, not built)

**Status: proposed, design not built; approved for 2.9.6 by the product owner (plan of 2026-10-27, tracked in this plan, row 8 of 2.9.7 above).** Goal: stop committing the synthetic part of `data/testdata` and regenerate
it, identically, on any machine (the offline Windows laptop included: Python only, no `.exe`, no internet), without git LFS. Public
snapshots (funds, us-equities, gleif, news-and-markets) stay committed.

### 1. Today

- `tools/testdata/make_testdata.py` writes everything in one process: the universe (3 × 5,000 trades) → 60 days of history →
  `make_var_data` (reads the trades it just wrote) → `make_ccr_data` (reads the netting sets) → manifest `testdata.yaml`.
- Committed: **137.6 MB** in 2,860 files. Synthetic **127.7 MB** (trades 41.6, market-risk 50.3, counterparty-risk 23.8, core 8.8,
  market-data 3.0); public **9.55 MB** (stays). Git history holds about 353 MB of test-data blobs.
- Generation time (measured in memory): about **3–4 min** here, **5–12 min** on a Windows laptop with Defender; `lake demo` itself
  takes 15–45 min.
- Determinism: seeds are text-hashed and the business date is fixed in code (safe). **Not** safe today: gzip bytes (zlib vs zlib-ng),
  `GzParts` splits on *compressed* size (file names can differ by platform), a few float `sum()` calls, last-bit differences of the
  platform math library (`exp`, `log`, `gauss`), and side files (pack samples, `reference.json`) rewritten when generating in place.

### 2. What stays and what is generated

| Path | |
|---|---|
| `README.md`, `testdata.yaml`, **new** `testdata.lock` (~250 KB), **new** `seeds.yaml` (~2 KB) | committed |
| `news-and-markets/`, `us-equities/`, `gleif/`, `funds/` | committed as now |
| `trades/`, `core/`, `market-data/`, `counterparty-risk/`, `market-risk/` | **generated in place**, git-ignored, same names and layout |
| `data/testdata-scale/` | generated, never committed |

Same paths, so `lake demo`, `build_demo_lakes`, the benches, `contracts.py`, the Java tests and the CLI examples need no change.

### 3. Seeds and lock

- `seeds.yaml`: profiles `demo` (5,000 per system, 60 days, 2026-09-30, locked), `small` (tests), `scale`; split files by
  **uncompressed** bytes; the compression level is not part of the hash. The seeds stay in the code; the file names the parameters.
- `testdata.lock`: one tab-separated line per generated file: sha256 of the **decompressed** content, a canonical hash with floats
  rounded to 9 significant digits (reported as *equivalent*, not a failure, when only the last bits differ), line count, bytes,
  path; plus a fingerprint of the generator sources, so CI catches "code changed, lock not regenerated" in a second.

### 4. One command

```
Linux:    drishti-console/.venv/bin/python tools/drishti.py testdata generate      (planned, not built)
Windows:  drishti-console\.venv\Scripts\python.exe tools\drishti.py testdata generate   (planned, not built)
```
Standard library + PyYAML only. Quick check when up to date (< 1 s); otherwise generate into `data/build/testdata-gen/`, verify
against the lock, then move each folder in (a half-written folder never replaces a good one). `--check`, `--check-lock`, `--force`,
`--profile demo|small|scale`, `--out`, `--explain`, `--accept-unverified`. Refuses Python < 3.12; checks free disk (~1.3 GB peak).

- `build_demo_lakes.py` gains step **1/4 the test data** (skipped with `--files-only`; `--no-generate` to forbid it).
- `lake demo` generates by itself when the folders are missing ("about 5 minutes, once").
- **Your own data is unchanged**: `--endur-dir`, `--source-dir`, `--with-real` work as now; generation never reads real files.

### 5. Scale (100k positions, 1,000 portfolios)

About 190k trades give 100k positions (0.53 positions per trade today), so `--positions 100000` sizes the run. 1,000 portfolios:
the 28 named ones plus generated rule, basket and per-book portfolios. 5 days of history by default (~1 min of trade states; 60 days
would be ~15 min). VaR vectors ~160 MB gz. Exposure stays capped at 9,500 trades. Written to `data/testdata-scale/`, not locked.

### 6. Tests, drill, CI

- New `test_lock.py` (lock complete, public folders tracked and synthetic ones not, sources fingerprint current); determinism test
  extended to every kind, two hash seeds and a second Python; `git status` clean after a user's generation.
- Java `ExposureParityTest` and `VarTestDataPrecisionTest` skip with a message naming the command, and fail in CI/drill
  (`-Ddrishti.requireTestdata=true`).
- Drill generates first (cached), then runs the generators' `--check` as now.
- CI: `fast.yml` generates (cached on the lock's hash); **a Windows job on Python 3.13 and 3.14, no cache, must be green before the
  folders stop being tracked**: the proof for the offline Windows machine.

### 7. Migration (one commit each)

1. Platform-independent output: split by raw bytes, `fsum` audit, side files off by default; prove the content unchanged.
2. Lock code. 3. Regenerate once, commit lock + manifest (data still tracked). 4. The command, `lake demo` and `build_demo_lakes`
   wiring, run configuration. 5. Windows CI green. 6. Stop tracking the five folders (`git rm --cached` + `.gitignore`); the first
   `lake demo` after a pull regenerates them; release note and QA guide say so. 7. Docs (README, QA on Windows, getting started,
   developer guide).
8. *Separate decision, not recommended now:* rewriting history to reclaim ~350 MB (changes every commit id; needs force pushes).

### 8. Risks

Last-bit floats on Windows (canonical hash, Windows CI, round `gauss` draws at the source if needed) · zlib differences (hash
decompressed content) · Python drift (≥ 3.12, CI on two versions) · a user's run rewriting committed files (side files off, test) ·
forgotten lock (fingerprint check) · first run 5–12 min offline (once, with progress) · history stays large (separate decision) ·
`git pull` removes the folders (auto-generate, release note) · scale memory (5 days, measure, stream if needed).

### 9. Estimate

About **6 days** for the core (sections 2–4, 6, 7), **8 days** with scale, +1 if Windows floats need fixing at the source.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
