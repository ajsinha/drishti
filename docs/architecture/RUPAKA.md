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

# Drishti Rūpaka: business intelligence in Drishti, live and governed

Status: design, proposed (2026-10-10). Nothing in it is built. Owner: a new module family (`drishti-bi-*` on the
server, `drishti-console/bi/` in the console), the existing source layer, identity, collaboration and Elements.
Companion notes: [LIVE_BI.md](LIVE_BI.md) (streaming result producers and boards) and the Project Rūpaka SRS that this
design adopts and adapts.

**The goal.** Drishti becomes a business-intelligence platform as nimble and as capable as commercial BI products, with
**Power BI** as the benchmark, while keeping what Drishti already does that they do not: live data at
terminal speed, one grammar for every screen, masks and audit on every value that leaves the server, and a click from
any number down to the record behind it. BI gets its own place in the product, **the BI page**, separate from the
streaming terminal, reached from the top bar or by typing `RUPAKA <GO>`.

**The decisions in one paragraph.** The server stays the authority on data: queries run on the server (DuckDB over the
lake and the connectors), results leave as **masked Apache Arrow**, and the browser slices what the user may see without
asking again. Reports, datasets and workspaces are **YAML documents in the Rachana grammar**, edited by a Power BI-style
designer that writes the same YAML (two views of one draft, as the Build workbench already does). Streaming visuals use
**FINOS Perspective**; charts use the **ECharts** the console already vendors; free exploration uses **Graphic Walker**
(the component behind PygWalker). **Python** is a first-class, interface-driven extension (transforms, measures, visuals)
that runs in a sandboxed server runner or in the browser (Pyodide), behind one interface. **Generative AI** comes later (stage 2 for BI, stage 3 for the whole UI, section 20.1), through one provider interface,
**off by default**; everything works without it. Publishing goes through workspaces,
review and a catalogue with endorsement, and access is the same roles, packs, masks and row rules as the rest of Drishti.

Contents

1. [Positioning against Power BI](#1-positioning-against-power-bi)
2. [Principles](#2-principles)
3. [Concepts](#3-concepts)
4. [Entering BI: the top bar, `RUPAKA <GO>` and URLs](#4-entering-bi-the-top-bar-rupaka-go-and-urls)
5. [The BI page](#5-the-bi-page)
6. [Datasets: the semantic model](#6-datasets-the-semantic-model)
7. [The query engine](#7-the-query-engine)
8. [Reports and visuals](#8-reports-and-visuals)
9. [The designer](#9-the-designer)
10. [Workspaces, publishing and the catalogue](#10-workspaces-publishing-and-the-catalogue)
11. [Access, security and governance](#11-access-security-and-governance)
12. [Streaming BI](#12-streaming-bi)
13. [Python in BI: interfaces, not scripts](#13-python-in-bi-interfaces-not-scripts)
14. [Generative AI hooks (off by default)](#14-generative-ai-hooks-off-by-default)
15. [Distribution: export, subscriptions, alerts, embedding, mobile](#15-distribution-export-subscriptions-alerts-embedding-mobile)
16. [Administration and operations](#16-administration-and-operations)
17. [Architecture](#17-architecture)
18. [Performance targets](#18-performance-targets)
19. [Non-goals for the first release](#19-non-goals-for-the-first-release)
20. [Build plan](#20-build-plan)
21. [Open decisions](#21-open-decisions)

## 1. Positioning against Power BI

Power BI is the reference because it is what our users already know: workspaces, semantic models, reports with pages
and visuals, a designer with fields, visualisations, format and filter panes, publishing to apps, row-level security,
refresh schedules, subscriptions, alerts, Q&A and an AI assistant. Rūpaka must feel familiar to a Power BI author in
the first ten minutes, and must win clearly where Drishti is strong.

| Area | Power BI (what users expect) | Rūpaka (what we build) | Verdict |
|---|---|---|---|
| Authoring | Desktop designer, fields / visuals / format / filters panes, pages | The same four panes in the browser, plus a YAML tab that is the same draft; no desktop install | **Match**, browser-only is a plus |
| Semantic model | Tables, relationships, measures (DAX), hierarchies, formats | Datasets in YAML: tables, relationships, measures and calculated columns in a closed expression language (Rachana-EL, extended for aggregation), hierarchies, formats, descriptions | **Match** for common models; no DAX compatibility |
| Data modes | Import (refresh), DirectQuery, streaming datasets (limited) | **Live** (every change, at terminal speed), **cached** (server DuckDB, refreshed by schedule or by a data-load signal), **direct** (pushed down to the source) | **Beat** on live |
| Streaming visuals | Limited tiles, a few seconds' refresh | Perspective grids and pivots, ECharts, hundreds of updates a second per view | **Beat** |
| Drill to detail | Drill-through to another report page | Drill-through to another page **and** to the Drishti terminal view of the record (the trade, the netting set), with its links, history and provenance | **Beat** |
| Security | Row-level security, object-level security, sensitivity labels | Row rules per dataset, field masks (already in Drishti, applied in every channel), classification tags, kinds per role | **Match**; masks reach emails, chat bridges and snapshots too |
| Governance | Workspaces, apps, endorsement, lineage, deployment pipelines | Workspaces, catalogue entries, endorsement, lineage to connectors, versions with four-eyes publish, environments by pack bundles | **Match** |
| Collaboration | Comments, subscriptions, Teams sharing | Drishti shares, threads, @mentions, email digests, chat bridges, legal holds, exports | **Beat** on compliance |
| AI | Q&A, Copilot (cloud, licensed) | Provider-neutral hooks (any OpenAI-compatible or Anthropic endpoint, or on-premises), off by default, masks applied to prompts | **Match** in kind; **beat** on control |
| Python / R | Python and R visuals and scripts (desktop runtime, gateway) | Typed Python interfaces (transforms, measures, visuals) running sandboxed on the server or in the browser | **Beat** on safety and reuse |
| Embedding | Embedded analytics (licensed, iframe-based) | `<drishti-report>` Web Component, no iframe, token exchange, always masked | **Match**, simpler |
| Deployment | Cloud service, gateway for on-premises data | Runs entirely on your servers, next to your data; Java 21 and a Python console | **Different**: on-premises first |
| Paginated reports | Report Builder | Print layouts of a report page and PDF export | **Partial** (section 19) |
| Excel integration | Analyze in Excel | CSV export; XLSX export in a later phase | **Behind** at first |

**Where we must not lose.** Familiar authoring, the visuals people use every day (bar, line, area, combo, scatter,
table, matrix, card, KPI, gauge, slicer, map), filters at visual, page and report level, cross-filtering, drill-down,
bookmarks, export, subscriptions, and a good catalogue. Everything in this list is in the first two phases of section 20.

## 2. Principles

1. **The server decides what leaves.** Every query runs with the user's identity on the server; row rules and masks are
   applied before a row is sent. The browser analyses only what that user may see. No raw lake access from browsers.
2. **One grammar.** Datasets, reports, boards and workspaces are Rachana documents: versioned, validated by schema, tested,
   reviewed, diffed and shipped from the BI folder (`config/bi`, below) like Sutras. Nothing is code that can run arbitrary instructions.
3. **Live is the default, not a special mode.** Any visual over a live source updates as the source changes, at the frame
   rate the viewer can read.
4. **Every number has a way down.** From a total to the rows behind it, and from a row to its Drishti terminal view.
5. **Nimble.** The BI page opens in under a second; heavy engines (Perspective, DuckDB-Wasm, Graphic Walker, Pyodide) load
   only when a page uses them. Everything is vendored; nothing comes from a CDN.
6. **AI-optional.** Every capability works without a language model; AI features are hooks that an administrator turns on.
7. **Familiar, then better.** A Power BI author recognises every pane; a Drishti user recognises every key.

## 3. Concepts

| Concept | What it is | Power BI's nearest |
|---|---|---|
| **Workspace** | A container for datasets, reports and boards, with members and roles. Each user has a **personal workspace** (drafts, private); teams have **shared workspaces** | Workspace (and My workspace) |
| **Dataset** | A semantic model: tables from connectors (by their logical name), relationships, measures, calculated columns, hierarchies, formats, descriptions, row rules; a storage mode per table | Semantic model |
| **Measure** | A named aggregation in the closed expression language (`sum($.notional)`, `ratio(sum($.pnl), sum($.notional))`), or a Python measure (section 13) | Measure (DAX) |
| **Report** | Pages of visuals over one or more datasets, with filters at three levels, bookmarks and drill-through targets | Report |
| **Visual** | One chart, table, matrix, card, slicer or Perspective grid, bound to fields and measures | Visual |
| **Board** | A live, sorted list or grid of many result rows (LIVE_BI section 7.2), usable as a page or a visual | Streaming dashboard tile |
| **Catalogue entry** | A published report or board with an audience, a description, an owner, tags and an endorsement | App / Endorsed content |
| **Endorsement** | `promoted` (an owner says it is good) or `certified` (a data steward says it is authoritative) | Promoted / Certified |
| **Refresh** | For cached tables: a schedule, a data-load signal (`POST /api/v1/packs/{pack}/loads`), or both | Scheduled refresh |

## 4. Entering BI: the top bar, `RUPAKA <GO>` and URLs

- **Top bar.** A new item **BI** (icon `bar-chart-line`) between **Views** and **Build**, with a menu: *BI home*, *My
  workspace*, *Catalogue*, *New report*, *Datasets*, and the user's three most recent reports. Shown to anyone with the
  `bi` power (section 11); admins see *BI settings* in Admin.
- **Command line.** `RUPAKA <GO>` opens the BI home page. `RUPAKA <report-id> <GO>` opens a report (type-ahead offers
  report titles and ids the user may open, as it offers entity ids today). `RUPAKA WORKSPACE <GO>` opens my workspace and
  `RUPAKA CATALOGUE <GO>` the catalogue (type-ahead completes both after `RUPAKA `). `RUPAKA` is a reserved keyword: a pack
  cannot define a mnemonic with that name (pack checks refuse it), and case does not matter (`rupaka <GO>` works).
- **URLs.** `/bi` (home), `/bi/w/<workspace>` , `/bi/r/<report>[/<page>]`, `/bi/d/<dataset>`, `/bi/catalogue`,
  `/bi/design/<draft>`; filters and bookmarks in the query string (`?f=region:EMEA`) so a link reproduces what was seen.
- **A separate shell.** The BI page shares the top bar, sign-in, themes, inbox and help with the terminal, but has its
  own layout: a left navigation rail, a wide canvas and its own keys. The terminal's F-key footer is not shown. From any
  BI visual, *Open in terminal* goes to the record's view; from a terminal view, *Analyse in BI* opens a report over the
  kind with the current filters.

## 5. The BI page

```
┌ top bar: DRISHTI · command line · date · Live · Views  BI  Build  Admin  Help · bell · user ───────────────────────┐
│ ┌ rail ───────┐ ┌ canvas ──────────────────────────────────────────────────────────────────────────────────────┐ │
│ │ Home        │ │ Home: Recent · Favourites · Certified for you · Shared with me · What changed                 │ │
│ │ Catalogue   │ │  ┌ card ────────────┐ ┌ card ────────────┐ ┌ card ────────────┐                             │ │
│ │ My workspace│ │  │ Desk P&L (live)  │ │ Counterparty     │ │ Liquidity daily  │   each: owner, endorsement,  │ │
│ │ Workspaces ▸│ │  │ ✔ certified      │ │ exposure         │ │ refreshed 06:12  │   freshness, open / favourite │ │
│ │ Datasets    │ │  └──────────────────┘ └──────────────────┘ └──────────────────┘                             │ │
│ │ Designer    │ │                                                                                              │ │
│ │ Explore     │ │                                                                                              │ │
│ └─────────────┘ └──────────────────────────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Section | Shows | Who |
|---|---|---|
| **Home** | Recent, favourites, certified reports the user may open, items shared with them, what changed since their last visit | everyone with `bi` |
| **Catalogue** | Every published report and board the user may open; search (title, description, tags, fields used), filters (domain or pack, workspace, owner, endorsement, live or cached, freshness), sort by use or by date | everyone with `bi` |
| **My workspace** | Personal drafts, datasets and reports; publish from here to a shared workspace | authors (`bi-author`) |
| **Workspaces** | Shared workspaces the user belongs to, their content and members | members |
| **Datasets** | Datasets the user may build on, with lineage (connectors, sources, kinds), refresh state, row rules summary | authors |
| **Designer** | The report designer (section 9) | authors |
| **Explore** | Free exploration of a dataset with Graphic Walker; *Save as visual* to a draft | authors and analysts |

Keys on the BI page: `/` search the catalogue, `g h` home, `g c` catalogue, `g w` my workspace, `n` new report, `?` the
keys. All of it works at phone width (section 15).

## 6. Datasets: the semantic model

A dataset is a YAML document (`<drishti.bi.dir>/datasets/<id>.dataset.yaml`, by default `config/bi/datasets/`, or in a workspace store):

```yaml
rachana: 1
dataset: desk-risk
version: 3
title: Desk risk and P&L
description: Trades, desks and counterparties with live MTM and daily P&L.
tables:
  trades:
    source: { connector: trading-lake, kind: trade }      # a connector by its logical name (CONNECTOR_FILES.md)
    mode: cached                                          # live | cached | direct
    refresh: { schedule: "0 */15 * * *", on-load: true }  # cron in the business zone, and on a data-load signal
    columns:
      tradeId:   { type: string, key: true }
      desk:      { type: string }
      cpty:      { path: $.counterparty.id, type: string }
      notional:  { type: decimal, format: amount0 }
      mtm:       { type: decimal, format: signed0 }
      tradeDate: { type: date }
  desks:
    source: { connector: desk-totals, kind: desk-pnl }
    mode: live
relationships:
  - { from: trades.desk, to: desks.desk, cardinality: many-to-one }
measures:
  Notional:      { expr: "sum(trades.notional)", format: amount0 }
  MTM:           { expr: "sum(trades.mtm)", format: signed0, description: "Mark-to-market in USD, signed" }
  MTM per 1m:    { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000", format: signed2 }
  Trades:        { expr: "count(trades)" }
  P&L 1d (py):   { python: risk.measures.pnl_attribution, inputs: [trades.mtm, trades.desk] }   # section 13
hierarchies:
  Date: [tradeDate.year, tradeDate.quarter, tradeDate.month, tradeDate]
security:
  rows:                                                   # row rules: evaluated on the server for each reader
    - { role: desk-head, where: "trades.desk in user.desks" }
    - { role: risk, where: "true" }
  masked: [cpty]                                          # shown as ••• without `raw`, as everywhere in Drishti
```

- **Expression language.** Rachana-EL grows aggregation and window functions (`sum`, `avg`, `min`, `max`, `count`,
  `distinct`, `ratio`, `percentile`, `running`, `lag`, `rank`, time intelligence `ytd`, `mtd`, `same_period_last_year`)
  with an explicit filter context. It stays closed and side-effect-free; it compiles to SQL for DuckDB (section 7).
  No DAX compatibility: a short mapping table for Power BI authors is in the user guide.
- **Storage modes.** `live`: the table is the current state of a live kind (TopicHub, LIVE_BI producers); `cached`: copied
  into the server's DuckDB on refresh; `direct`: every query is pushed to the source (JDBC, DuckDB, Delta). A dataset may mix
  them; the designer shows each visual's freshness.
- **Lineage** is derived: dataset → tables → connectors → plugins → sources, and reports → datasets, shown in the catalogue.

### 6.1 Where the data comes from: the same connectors

Rūpaka reads through the **same connectors as the rest of Drishti, by their logical names**
(`config/connectors/<name>.yaml`, CONNECTOR_FILES.md): one place for credentials, TLS, health, the Admin → Connectors page
and the audit of what reads where. There is no BI-only path to the data. What BI adds is two optional connector
capabilities for reading whole tables instead of one record:

| Access | Used by | Connector interface |
|---|---|---|
| One record (`fetch`, `subscribe`) | terminal views, live updates, drill to record | exists |
| Columns of a kind for a date (`columns`) | searches, derived kinds | exists (Delta's promoted columns) |
| **Bulk scan**: chosen columns, filters and a date range, as Arrow record batches | the columns layer (7.1), `cached` tables | new, optional: `Optional<ArrowScan> scan(ScanRequest)`; Delta, Parquet, DuckDB and JDBC implement it natively, Kafka returns its current state |
| **Pushdown**: the compiled SQL runs in the source | `direct` tables over SQL stores | new, optional: `Optional<SqlTarget> sqlTarget()`, an attachable target for the engine |

A connector with neither falls back to record-by-record reads, and the designer warns that a large table will be slow.
What Rūpaka writes (the columns and rollup layers) goes to a BI store that is itself a configured connector (`bi-store`,
by default a local Parquet/Delta folder; S3 by configuration), so it is managed like every other source.

### 6.2 The data model: from pack entities to a relationship diagram

As in Power BI, reports sit on a **data model** (the dataset), and the data model sits on connectors:
connectors (by logical name) → data model (tables, relationships, measures, hierarchies, row rules) → reports. Reports
never bind to connectors directly.

- **Packs expose their entities.** Every kind of every pack the author may access is offered as a table, with the
  fields its Sutras, shape and about text describe. A pack's link graph (trade → counterparty, trade → netting set) is
  read as ready-made relationships, so a model started from pack kinds is already connected.
- **One model, many packs.** A data model may combine kinds from any packs (trades from `trading`, counterparties from
  `counterparty-risk`, exposures from `market-risk`); cross-pack links become cross-pack relationships.
- **Inference fills the gaps.** For kinds without declared links, the dataset helper proposes relationships from the data:
  a column whose values match another kind's key, with the match rate and the cardinality it found. Proposals are
  accepted one by one; nothing changes on its own. (This is profiling, not AI: stage 1.)
- **The Model view** in the designer is the relationship diagram: tables as boxes with their columns, measures and
  hierarchies; drag a column onto another to relate them; each line shows cardinality (one-to-one, one-to-many,
  many-to-one, many-to-many) and filter direction (single or both); a relationship can be inactive and used by a measure
  explicitly. The diagram and the YAML (section 6) are two views of the same draft; the layout is saved with it.
- **BI only.** Model relationships drive joins and cross-filtering in BI. The terminal keeps navigating by pack links;
  editing a model never changes a pack.
- **Access per pack.** A reader sees a model's tables only from the packs assigned to them (and not from packs switched
  off, or sample packs hidden from them); a table they may not see returns no rows and the visual says so. Row rules and
  masks apply per kind. Lineage lists every pack and connector behind a model, so switching a pack off shows the reports
  it affects first.

## Where BI files live

BI content has one home, the **BI folder**: `drishti.bi.dir` (environment variable `DRISHTI_BI_DIR`), `./config/bi` by default,
relative to the server's working directory. It sits beside the other two things a site owns, `config/connectors/` (the
connectors) and `config/packs/` (the packs); runtime output goes under `data/` (`drishti.data.dir`).

```
config/bi/
  README.md
  datasets/   <id>.dataset.yaml   section 6
  reports/    <id>.report.yaml    section 8
  python/                         section 13: typed implementations, their manifests and tests
```

- **Never inside a pack.** A pack describes data (kinds, Sutras, links, vocabulary). A dataset may combine kinds from several
  packs and a report several datasets, so BI content is deployed, versioned and switched on by itself; a pack does not mention
  BI, and switching a pack off never deletes a report (the visuals over its kinds say they have no rows).
- **Datasets name connectors** by logical name ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)), as packs do.
- **Workspace drafts and published versions** live in the identity database (section 21, decision 1); the folder holds the
  reviewed content shipped with a site.
- The folders may be empty or absent. Phase 0 reads the folder only to list the dataset files it finds (the `bench` answer's
  `biDir` and `biDatasets`; see [RUPAKA_POC.md](RUPAKA_POC.md)).

## 7. The query engine

- **Server-side DuckDB** (already a Drishti plugin) is the analytical engine: cached tables are DuckDB tables (on disk,
  per dataset, refreshed atomically); direct tables are attached (`ATTACH` for PostgreSQL and Delta, scanners for Parquet
  and Iceberg) or fetched through the connector when no attachment exists; live tables are an in-memory Arrow table kept
  current from the live path.
- **Compilation.** A visual's query (fields, measures, filters, the user's row rules) compiles to one SQL statement with
  parameters, never string concatenation of user input. Row rules are added as predicates the user cannot remove.
- **Delivery.** Results leave as **Apache Arrow IPC** (compact, typed, read without parsing by Perspective and
  DuckDB-Wasm) with masks applied column by column; small results may also be JSON for simple visuals.
- **Caching.** Results are cached per (dataset version, query, row-rule set, data generation); a data-load signal or a
  refresh invalidates exactly what it changed.
- **Limits.** Per-query timeout, row and byte caps, and a concurrency limit per user and per server, all in settings;
  over a limit the visual says so with a `DRS-9xxx` code.
- **In the browser,** DuckDB-Wasm is optional: it lets a user slice an extract they already received (pivot, filter,
  re-aggregate) with no round trip. It never connects to a store.

### 7.1 Data at scale: JSON in Delta and Kafka

Most of the data Drishti serves is **JSON documents** in Delta Lake (partitioned by `business_date`) and on Kafka topics.
DuckDB is a fast single-node, column-oriented engine that spills to disk; it handles very large tables when it reads
**columns** and prunes partitions and row groups, but a query that has to parse a JSON document column costs in proportion
to the bytes of JSON, every time. The architecture therefore answers each query from the smallest layer that can:

| Layer | Content | Written by | Used for |
|---|---|---|---|
| **Raw** | JSON documents in Delta, as today | the site's ETL | drill to the full record; rare ad-hoc fields |
| **Columns** | per kind, the fields that datasets declare, typed (Parquet/Delta), partitioned by business date | **Drishti**, incrementally, when a data-load signal (DATA_LOADS.md) says a date landed: JSON is parsed once per load, not per query | almost every BI query |
| **Rollups** | per dataset, pre-aggregations for the common group-bys | Drishti, after the columns of that date | dashboards answered in milliseconds (aggregate-aware routing, as Power BI's aggregations) |
| **Hot** | the current state of live kinds (Kafka state mode, LIVE_BI producers), bounded by the dataset's window | the live path, in memory | live tables and visuals |
| **Continuous** | heavy streaming aggregates | a producer (Pravaha, Flink), per LIVE_BI | live totals over large streams |

The query planner rewrites a visual's query to the first layer that can answer it (rollup, then columns, then raw), and
joins the hot state with today's columns for live tables. Drishti's own searches already work this way: the Delta
connector's promoted columns are why a search over a million trades a day answers in about 134 ms (PERFORMANCE.md).

**Engine interface.** The compiler emits SQL against an `AnalyticEngine` interface. **DuckDB** (embedded, no extra
process) is the default. A dataset too large for one node sets `engine: <name>` and its `direct` tables are pushed down to
a distributed engine the site already runs (Trino or Starburst, Spark or Databricks SQL, ClickHouse, StarRocks) through
a connector, with Drishti still adding row rules and applying masks to the results. Reports and the designer do not change.

**Sizing rule of thumb** (to be replaced by measurements, LOAD_AND_MEMORY.md): one DuckDB server for datasets whose
*columns* layer fits a few terabytes and whose heavy queries run at modest concurrency; a distributed engine beyond that.
The phase 0 POC measures the JSON, columns and rollup paths side by side (RUPAKA_POC.md).

## 8. Reports and visuals

A report is `<drishti.bi.dir>/reports/<id>.report.yaml` (by default `config/bi/reports/`): pages, each a 12-column grid of visuals; filters at report, page and visual
level; bookmarks; drill-through targets; a theme.

| Visual | Engine | Notes |
|---|---|---|
| Bar, column, line, area, combo, scatter, bubble, pie / donut, waterfall, funnel, treemap, heatmap, histogram, box | ECharts (vendored) | cross-filter on click; drill-down along hierarchies |
| Card, multi-row card, KPI (value, target, trend), gauge | Drishti panels (`metric`, `gauge`) | conditional formats |
| Table, matrix (with subtotals, expand/collapse) | Drishti `table` / `pivot`, or Perspective for large or live | export, conditional formats, data bars |
| Live grid, live pivot, live chart | **FINOS Perspective** (WebAssembly, Apache-2.0) | hundreds of updates a second; group, split, sort, filter in the visual |
| Slicer (list, dropdown, range, relative date, hierarchy) | Drishti | sync across pages |
| Map (points, regions) | ECharts geo with vendored map data | no external tiles by default |
| Text, image, shape, button (navigate, bookmark, drill-through) | Drishti | |
| Python visual | the Python runner (section 13) | returns a declarative chart, never HTML |
| Entity view | a Drishti Sutra view of one record, in a report | the terminal inside BI |

Interactions: cross-filter and cross-highlight between visuals on a page; drill-down and up along hierarchies; drill-through
to another page with filters carried; *Show as table*; *Show the rows* (a table of the underlying rows, masked); *Open in
terminal* on any row whose key is a Drishti kind.

### 8.1 Interactive Sutras and slicers: controls, view state and filters

The terminal's Sutras and Rūpaka's reports share **one model of interaction**, so it is built once and works in both.

**Controls.** A Sutra or a report page declares `controls:`; each is placed in the strip, a panel header or a side bar.

| Kind | Use | Options from |
|---|---|---|
| `dropdown`, `multi-select` | a leg, a currency, desks | a fixed list; the record's own data (`$.legs[*].id`); a dataset column (distinct values the reader may see); a lookup kind |
| cascading | region → country → book: options depend on another control | an expression over the parent control's value |
| `toggle`, `segmented` | Pay / Receive / Both | a fixed list |
| `date`, `date-range` | a business date; paid between; relative ranges (`next-90d`, `this-month`, `ytd`) | the calendar of the pack |
| `number-range`, `slider`, `top-n` | notional between; the top 10 | bounds from data or fixed |
| `search` | contains, across chosen columns | the columns named |

A control has an `id`, a `label`, `options`, a `default`, `remember: user | none` and `required`. Its value is read in the
closed expression language as `ctl.<id>`. Illustrative (the syntax is settled in phase 0a):

```yaml
controls:
  - { id: leg, kind: dropdown, label: Leg, options: "$.legs[*].id", default: first }
  - { id: when, kind: date-range, label: Paid between, default: next-90d }
panels:
  - id: cashflows
    kind: table
    rows: "$.legs[?(@.id == ctl.leg)].cashflows"
    where: "@.payDate within ctl.when"
    title: "Cashflows · leg {ctl.leg}"
```

**Behaviour.**
- Changing a control re-renders only the panels whose expressions read it; the rest do not move.
- **Live data stays live:** each new frame is filtered through the current control values before it reaches the screen.
- **View state is in the URL** (`?ctl.leg=L2&ctl.when=2026-10-01..2026-12-31`), so a link, a share, a snapshot and a
  subscription reproduce exactly what was seen; `remember: user` restores the last choice for that Sutra or report.
- **Cross-filtering:** a panel may declare `on-select: { filter: [cashflows], by: leg }`, so clicking a row or a chart bar
  sets a control; in Rūpaka every visual cross-filters its page by default, as Power BI's visuals do.
- **Where filtering runs:** in the browser for panels already loaded in full; on the server (the query or the source) for
  large tables and datasets, with the same expression, so the results are the same either way.

**Excel-style column filters on every table** (no Sutra needed). Each column header of a table, matrix or pivot has a
filter menu, as in Excel's AutoFilter: a searchable checklist of distinct values with counts, *(Select all)* and
*(Blanks)*; number conditions (equals, not equal, greater, less, between, top 10 / bottom 10, above or below average);
date conditions (today, this week, this month, last 7 days, between, before, after); text conditions (equals, contains,
begins with, ends with, does not contain); sort ascending and descending. Filters combine across columns; a filtered
column shows a funnel; one key clears them all. Column filters are part of the view state (URL, shares) and become a
report's visual-level filters in Rūpaka. They extend today's single *Filter rows* box, which stays as the quick search.

**As built (delivery 1).** The column filters are in the terminal (`static/js/colfilter.js`, called by `tables.js` and `tree-rows.js`;
`colfilter.css`; also inside `<drishti-view>`). Decisions taken: the checklist lists only the values the other columns' filters leave,
with counts; a column uses its ticked values or one condition; a masked value (`•••`) is never listed, all masked cells are one
*(masked)* entry and never satisfy a condition; types are detected from the cells (80% numbers or ISO dates); in a matrix a row
matches on its own cells and its ancestors stay open; the URL form is `?f.<panel>.<column heading>=in:a,b | gt:1m | bt:1~9 | top:10 |
today ...` (`~b` Blanks, `~m` masked) and is written with `history.replaceState`; remembering per user is off (a `<body
data-remember-filters="on">` or a browser flag turns it on); `Alt+Shift+X` clears a panel's filters. Not yet built: a server-paged
table (none exists today: every table holds the rows the server sent), so the filter runs in the browser and every change is
announced as the table event `drishti:column-filter` ({panel, filters in the URL form}) for a server-side pager to take; a column
filter on the nested-group pivot and in the Pivot tab (its own Filters zone filters source fields before grouping, the Excel report
filter); `controls:` and `ctl.*` (delivery 2). Rūpaka's visual-level filters will reuse `matches()` and the URL form.

**Safety and access.** Controls only select or filter; they never write. Options and filters are expressions in the closed
language, never code. Masks apply: a dropdown or a filter checklist never lists a masked value to a reader without `raw`,
and row rules decide which values exist at all. Every control works from the keyboard (the checklist with arrows, space
and type-ahead), at 44 px on touch screens, and is announced to screen readers.

**Order of delivery.** (1) Excel-style column filters on every table, matrix and pivot in the terminal (useful at once, no
grammar change); (2) `controls:` and `ctl.*` in Sutras, with URL view state; (3) cross-filtering; (4) the same model as
Rūpaka slicers and the visual, page and report filters of the designer.

## 9. The designer

Power BI authors find the same panes in the same places:

```
┌ pages: [Overview] [By desk] [+]                                     Undo Redo · Preview · YAML · Save · Publish ┐
│ ┌ canvas (12-col grid, snap, align, distribute) ──────────────────────┐ ┌ Filters ┐ ┌ Visualisations ┐ ┌ Fields ─┐ │
│ │  ┌ KPI ┐ ┌ KPI ┐ ┌ KPI ┐                                           │ │ report  │ │ ▦ ▤ ◔ ▥ ... │ │ ▸ trades│ │
│ │  └─────┘ └─────┘ └─────┘                                           │ │ page    │ │ Build: axis, │ │   desk  │ │
│ │  ┌ bar: MTM by desk ──────┐ ┌ live grid ─────────────────┐         │ │ visual  │ │ values, legend│ │   mtm  │ │
│ │  └────────────────────────┘ └────────────────────────────┘         │ │         │ │ Format: …    │ │ ▸ desks │ │
│ └────────────────────────────────────────────────────────────────────┘ └─────────┘ └──────────────┘ └─────────┘ │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Fields pane:** the dataset's tables, columns, measures and hierarchies with search; drag onto the canvas creates the
  best visual for the field (number → card, date + number → line, category + number → bar), as Power BI does.
- **Visualisations pane:** the catalogue of section 8; *Build* (field wells: axis, values, legend, tooltips, drill-through)
  and *Format* (titles, colours from the theme, labels, axes, conditional formats).
- **Filters pane:** visual, page and report filters; basic, advanced and top-N.
- **YAML tab:** the same draft as text with schema completion (as the Build workbench's YAML tab); edits in either view
  appear in the other at once.
- **Data while designing:** every visual renders real data (a sample for large tables, labelled), with the query time shown.
- **Quality:** a *Check* button runs the report's tests (expected values on sample data), accessibility checks (contrast,
  alt text, keyboard order) and performance hints (a visual over 1 s).
- **Explore → visual:** an exploration in Graphic Walker can be saved as a visual in the draft.
- **Keys and accessibility:** every action has a key; the canvas is operable without a mouse (as layout mode is today).

### 9.1 The analyst's workbench: Power BI and Jupyter in one place

Authors build reports; analysts also want to **slice, look at the raw data and compute**. The **Analyse** view of any
dataset, report visual or terminal search result gives them, in one tabbed workspace and without leaving the browser:

| Tab | What | Engine |
|---|---|---|
| **Slice** | drag fields to rows, columns, values and filters; pivot, sort, filter, top-N, totals; every change instant | DuckDB-Wasm on the extract already received (no server round trip); Perspective for live data |
| **Data** | the rows as a fast grid (millions virtualised), column profiles (type, nulls, distinct, min/max, histogram), search; a row opens its **JSON document** and its Drishti terminal view | Perspective grid; profiles computed locally |
| **SQL** | SQL cells over the loaded tables, results as tables or charts | DuckDB-Wasm |
| **Python** | notebook cells (Python in the browser) over the same tables as `pyarrow` / pandas / polars where available, with plots; `pygwalker.walk(table)` for drag-and-drop exploration | Pyodide in a Web Worker (the runtime Calc bundles), loaded on first use |
| **Explore** | Graphic Walker drag-and-drop over the tables | Graphic Walker |
| **Notes** | Markdown cells between the others | the console |

- **One data model across tabs.** The extract is held once as Arrow in the browser; the Slice, Data, SQL, Python and
  Explore tabs all see the same tables, and a result from any tab can become a new table for the others (`df` in Python
  appears as a table in SQL).
- **Local and governed.** Everything runs on the data the server sent for this user: row rules and masks were applied
  before it arrived, so local analysis cannot reveal more than the user may see. Extract size is capped per dataset
  (`bi.extract.max-rows`, `max-mb`); a larger question goes back to the server as a query.
- **From exploration to production.** A Slice becomes a visual in a report draft; a SQL cell becomes a dataset measure or
  table; a Python cell becomes a **Transform, Measure or Visual** draft (section 13) with its inputs captured as a test
  fixture, so notebook work turns into reviewed, tested, shared code instead of a lost notebook.
- **Saved as a notebook.** The whole workspace (cells, layout, the query that made the extract) saves to the user's
  workspace and can be shared, discussed (Drishti threads) and re-run on today's data.
- **Nimble.** The Slice and Data tabs open in under a second on a 1 million-row extract; Pyodide (large) and Graphic
  Walker load only when their tab is first opened, with progress shown; keys for everything (`Shift+Enter` runs a cell,
  `Alt+1`…`Alt+6` switch tabs).

## 10. Workspaces, publishing and the catalogue

- **Workspace roles:** `viewer` (open published content), `contributor` (create and edit drafts), `member` (publish),
  `admin` (members and settings). A workspace may require **review before publish** (four eyes: the Build workbench's
  review flow, reused).
- **Lifecycle:** draft (personal or shared) → proposed → published version N. Viewers always see the published version;
  authors keep editing the draft. Versions are kept, diffed and restorable.
- **Catalogue entry:** title, description, owner, workspace, tags, audience (roles, users, packs), endorsement, the datasets
  and connectors behind it, freshness, usage (views in the last 30 days). Audience decides who sees it in the catalogue;
  dataset row rules and masks still decide what each person sees inside it.
- **Endorsement:** owners mark `promoted`; data stewards (a `bi-steward` power) mark `certified`. Certified content is
  pinned first in search and on Home.
- **Environments:** a workspace's content exports as a pack bundle (the existing pack deploy with preview and rollback), so
  development, test and production are pack deployments, not copies by hand.
- **Lineage and impact:** changing a dataset shows every report and visual that uses each changed field before saving.

## 11. Access, security and governance

| Control | How |
|---|---|
| Who may use BI | new powers on roles: `bi` (open BI and published content), `bi-author` (designer, personal workspace), `bi-steward` (certify, manage datasets), `bi-admin` (BI settings) |
| Which content | workspace membership plus the catalogue entry's audience |
| Which rows | the dataset's row rules, evaluated on the server with the user's identity (roles, desks, packs) |
| Which fields | Drishti's field masks (`drishti.security.redact`), applied to every result, export, subscription email, chat bridge, snapshot and AI prompt |
| Which kinds | the role's kinds, as today: a dataset table over a kind a user may not open returns no rows for them |
| Aggregates that leak | LIVE_BI section 10: `masked-from` and a minimum group size for masked inputs |
| Export | per catalogue entry: allowed formats and a row cap; every export audited |
| Classification | tags on datasets and reports (`internal`, `confidential`, `restricted`), shown on every page and export |
| Audit | who opened, filtered, exported, published, certified; the access log gains BI reads |
| Embedding | `<drishti-report>` through the existing token exchange; always masked |

## 12. Streaming BI

- A `live` table is the current state of a live kind: rows arrive through the existing TopicHub (or the LIVE_BI result
  producers) and are applied to an in-memory Arrow table on the server; visuals over it subscribe to its changes.
- Visual updates are **coalesced per visual** to a frame rate the eye can follow (default 4 per second for charts, up to
  30 for Perspective grids), with backpressure: a slow browser gets the latest state, never a growing queue.
- Aggregations over live tables are maintained **incrementally** for the simple, common cases (sum, count, min, max by
  group) and recomputed on a short timer for the rest; heavy continuous aggregation belongs in a producer (Pravaha, Flink),
  per LIVE_BI.
- Time windows (`last 15 minutes`, `today`) are part of the dataset or the visual, and bound the memory a live table holds.
- Perspective receives Arrow updates and does its own pivoting in the browser, on the masked rows the user may see.

## 13. Python in BI: interfaces, not scripts

Python is supported the way a careful platform supports plug-ins: **typed interfaces**, packaged and versioned code,
tests, and a sandbox. Authors never paste scripts into a visual.

### 13.1 The interfaces

```python
# drishti_bi (a small, dependency-free package shipped with Drishti)
from typing import Protocol
import pyarrow as pa

class Transform(Protocol):
    """A table in, a table out: cleaning, enrichment, reshaping. Declared output schema; deterministic."""
    output_schema: pa.Schema
    def apply(self, table: pa.Table, params: dict) -> pa.Table: ...

class Measure(Protocol):
    """A vectorised aggregation over grouped inputs: one value per group."""
    output_type: pa.DataType
    def compute(self, groups: pa.Table, inputs: list[str], params: dict) -> pa.Array: ...

class Visual(Protocol):
    """Data in, a declarative chart out (an ECharts option dict or a Drishti panel spec): never HTML or script."""
    def render(self, table: pa.Table, params: dict, theme: dict) -> dict: ...

class Source(Protocol):
    """Optional: rows from a Python-only source (an API, a model's output), as Arrow batches."""
    schema: pa.Schema
    def batches(self, params: dict): ...          # an iterator of pa.RecordBatch
```

- Implementations live in the BI folder's `python/` package (`config/bi/python/`; or a workspace's, for drafts), registered in YAML by dotted name
  (`risk.measures.pnl_attribution`), with a `manifest` of parameters, required packages and the execution targets it supports.
- Each implementation ships **tests** (inputs and expected outputs as small Arrow files) that the BI check runs.

### 13.2 Where it runs: one interface, two targets

| Target | When | Isolation |
|---|---|---|
| **Server runner** (`drishti-pyrunner`: worker subprocesses the console starts on demand and stops when idle; nothing extra to deploy) | cached and direct datasets, scheduled refresh, anything shared by many viewers | each call in a worker process with CPU time, memory and wall-clock limits; no network unless the manifest declares hosts and an admin allows them; read-only file system except a scratch folder; an allow-list of packages; inputs and outputs are Arrow only |
| **Browser** (Pyodide, the runtime Calc already bundles) | exploration and personal analysis on data the user already received | the browser sandbox, in a Web Worker; loaded on demand |

The dataset or visual says `python: <name>` and, optionally, `run: server | browser | auto`; the engine picks the target
(`auto`: server for shared content, browser for personal exploration) and calls the same interface. Results are masked
again on the way out of the server runner, so Python cannot reveal a masked field.

### 13.3 Packages

The server runner uses a fixed, vendored wheel set per release (pyarrow, numpy, pandas, polars where available,
scipy, statsmodels, scikit-learn; listed and licence-checked); a workspace cannot install packages. The browser target uses
the packages Pyodide provides; a manifest that needs a package missing there runs on the server only.

### 13.4 PygWalker and notebooks

Calc (the in-browser Python) gains a `drishti_bi` client: `ds = bi.dataset("desk-risk"); t = ds.query(...)` returns the
masked Arrow table the user may see, and `pygwalker.walk(t)` opens PygWalker on it for those who prefer Python. A
notebook exploration can be saved as a Transform or Visual draft with its tests.

## 14. Generative AI hooks (off by default)

This section is **stage 2** (section 20.1): nothing in it is built in stage 1, BI without AI.

One provider interface (`AiProvider`: chat with tool calls, embeddings optional) with implementations for
OpenAI-compatible endpoints, Anthropic, and on-premises models; configured by an administrator, **off** unless turned on.
Every feature below has a non-AI equivalent that works out of the box.

| Hook | What it does | Guardrails | Without AI |
|---|---|---|---|
| **Report from words** | "Live MTM by desk with a trend and the top counterparties" → a draft report (YAML) | the draft is validated by the report schema and the expression language, opens in the designer as a draft, is never published without a person | the designer's field-to-visual suggestions |
| **Ask the data** (Q&A) | a question → a query over a dataset → a visual | only the dataset's model is sent (names, types, descriptions, statistics), not rows; the query runs with the user's row rules | the plain-words parser Drishti already has, extended to datasets |
| **Explain this number** | why a value moved: contributions by dimension, then a narrative | contributions are computed by Drishti; the model only words them; masked fields never sent | the contribution table alone |
| **Narrative summary** | a paragraph for a page (for subscriptions) | numbers inserted by Drishti from computed results, not generated | none |
| **Designer copilot** | suggest visuals, formats, measures | suggestions are diffs to the draft, accepted one by one | rule-based suggestions |
| **Dataset helper** | propose relationships, descriptions, measures from the schema and statistics | proposals only | profiling (`dataprofile` exists) |

All prompts and answers are audited (with masks applied), rate-limited and costed per user and per workspace;
an administrator can restrict which workspaces and roles may use each hook.

## 15. Distribution: export, subscriptions, alerts, embedding, mobile

- **Export:** a page as PDF or PNG; a visual's data as CSV (later XLSX); within the entry's export policy and row cap.
- **Subscriptions:** a report page by email on a schedule or after a data load, rendered for each recipient with their
  masks and row rules (the outbox and digest machinery exists), with an optional narrative summary.
- **Alerts:** a condition on a measure (`MTM < -15m`, a KPI off target) evaluated on refresh or live; to the bell, inbox,
  email and chat bridges (the alert engine exists).
- **Embedding:** `<drishti-report report="…" page="…">` and `<drishti-visual>` through Drishti Elements (token exchange,
  origin-bound, always masked), with the same container-query responsiveness.
- **Mobile:** every report has an automatic phone layout (visuals stacked by importance) and an optional hand-made one in
  the designer; touch targets of 44 px; the BI page passes the phone sweep.

## 16. Administration and operations

- **Admin → BI settings:** powers per role, workspace creation policy, export defaults, AI hooks on or off per feature,
  Python runner limits and package set, query limits, cache sizes, refresh concurrency.
- **Health:** refresh state per dataset (last success, duration, rows, next run, failures with codes), query latency
  percentiles, runner pool, cache hit rates.
- **Usage:** views per report and user, slow visuals, unused content (candidates to archive).
- **Backups:** workspaces, datasets and reports are documents in the identity database and on disk; included in the existing
  backup and export procedures.
- **Load and memory:** the strategy of [LOAD_AND_MEMORY.md](../admin/LOAD_AND_MEMORY.md) gains a BI persona (report
  viewers) and the runner's memory budget.

## 17. Architecture

**No new server processes unless they are needed.** The BI modules run inside the existing Drishti server (Java) and the
console (Python); DuckDB is embedded in the server. The only extra processes are the Python runner's short-lived worker
subprocesses, started by the console when a Python transform, measure or visual runs and stopped when idle
(`bi.python.workers`, default 2, `bi.python.idle-seconds`, default 300). When measured load calls for it (section 18,
LOAD_AND_MEMORY), two scale-out options exist, both off by default: the runner as its own service on another host
(`bi.python.runner-url`), and more Drishti servers behind a load balancer, as PERFORMANCE.md "Scaling out" describes.

```
 browser (BI page)                                   console (FastAPI)                     server (Java 21)
 ─────────────────                                   ─────────────────                     ────────────────
 designer, catalogue, home     ── HTML over the wire ──  /bi routes, templates   ── REST ──  drishti-bi-core: datasets,
 visuals: ECharts, panels        Arrow (masked)           auth, CSRF, CORS                   reports, workspaces, catalogue,
 Perspective (on demand)       ◄─ live channel (SSE) ──   channel hub              ◄─ SSE ─   publish, endorsement, audit
 DuckDB-Wasm (on demand)                                  pyrunner pool  ◄── Arrow ──►      drishti-bi-query: compile,
 Graphic Walker (on demand)                               (sandboxed Python)                 DuckDB, caches, row rules,
 Pyodide / Calc (on demand)                                                                  masks, Arrow out
                                                                                             drishti-bi-live: live tables,
                                                                                             incremental aggregates
                                                                                             sources: connectors by name,
                                                                                             TopicHub, LIVE_BI producers
                                                                                             drishti-bi-ai: provider SPI (off)
```

New server modules depend one way (bi-core → bi-query → engine and identity), no controller outside `drishti-server`,
files under 1,500 lines, settings for every limit; the console adds `bi/` routes and templates, vendored assets loaded
per page; the Python runner is a separate process family with its own health.

## 18. Performance targets

| Measure | Target |
|---|---|
| BI home page, first paint | < 1 s; no heavy engine loaded |
| Cached visual over 10 M rows (server DuckDB), p95 | < 500 ms; cache hit < 50 ms |
| Report page of 8 visuals, all cached, p95 | < 1.5 s to complete |
| Live visual, source change to screen, p99 | < 250 ms (charts, coalesced); < 100 ms (Perspective grid) |
| Perspective grid sustained updates | 1,000 rows a second per visual without dropped frames below 45 fps |
| Python runner call overhead | < 50 ms for a small table; server-side limits enforced |
| Concurrent BI viewers per server | measured with the LOAD_AND_MEMORY capacity method; target 500 viewers on the reference server |

## 19. Non-goals for the first release

DAX compatibility; a desktop authoring tool; paginated (pixel-perfect) report authoring beyond print layouts; Excel
pivot connectivity (an XMLA-like endpoint); R scripts; custom JavaScript visuals (only the vetted catalogue and Python
visuals returning declarative specs); cloud-hosted multi-tenant service; write-back actions (planned, gated: the Rūpaka SRS
section 3.4 pattern, server-side, four-eyes, off by default).

## 20. Build plan

| Phase | What | Size | Done when |
|---|---|---|---|
| 0 | **Proof of concept**: Perspective live grid fed by Drishti's live path; masked Arrow out of a server DuckDB query; DuckDB-Wasm slicing it; CSP and size measured | M | a page shows a live pivot at target rates and a cached query under target, with masks proven |
| 0a | **Interactive Sutras** (section 8.1): Excel-style column filters on every table, then `controls:` and `ctl.*` with URL view state, then cross-filtering | M | a desk filters a trade's cashflows by leg and date from a dropdown, and any table by column filters, by mouse, keyboard and touch |
| 1 | **Shell and catalogue**: BI top bar item, `RUPAKA <GO>`, `/bi` home, catalogue, workspaces (personal and shared), powers, audit | M | a published report appears for its audience only |
| 2 | **Datasets and query engine**: dataset YAML, cached and direct modes, the columns and rollup layers (section 7.1), the engine interface, expression language aggregation, compile to DuckDB, row rules, masks, Arrow delivery, caching, refresh by schedule and data load | L | the reference dataset answers every visual of the demo report within targets for three roles with different rows |
| 3 | **Reports and the designer**: report YAML, the visual catalogue, filters, cross-filter, drill-down and through, the four panes, YAML tab, preview with data, check | L | a Power BI author rebuilds a supplied reference report without help in under an hour (usability test) |
| 4 | **Publishing and governance**: review before publish, versions, endorsement, lineage, impact of dataset changes, pack bundles for environments | M | certified content and lineage shown; deployment by bundle with rollback |
| 5 | **Streaming BI**: live tables, incremental aggregates, Perspective visuals, coalescing and backpressure, boards as pages | M | live targets met under the LOAD_AND_MEMORY capacity run |
| 6 | **Python**: the interfaces, the server runner and sandbox, browser target, manifests and tests, Calc client and PygWalker | M | a Transform, a Measure and a Visual ship in a pack with tests and run on both targets |
| 7 | **Distribution**: export, subscriptions, alerts on measures, `<drishti-report>`, phone layouts | M | a subscription email renders per recipient with masks |
| 9 | **Analyse**: the analyst workbench (Slice, Data, SQL, Python, Explore, Notes) over masked extracts, save as notebook, promote cells to visuals, measures and Python interfaces | L | an analyst slices a 1 M-row extract, runs a Python cell on it and turns it into a tested Transform, all in the browser |

Every phase ships with tests (unit, console, browser at phone width), documentation with real captures, and its numbers
measured, as every Drishti feature does. Phases 0 to 3 are the minimum lovable BI; 4 to 9 make it a Power BI alternative.

### 20.1 Three stages

The work is done in three stages, in this order; each is complete and useful on its own.

| Stage | Scope | Phases | AI |
|---|---|---|---|
| **1. BI** | Everything in this document except section 14, starting with interactive Sutras (8.1): datasets, the query engine and its layers, reports, the designer, workspaces and catalogue, streaming BI, Python interfaces, distribution, the analyst workbench | 0, 0a, 1 to 7 and 9 above | **none**: no feature depends on a language model, and none is built |
| **2. Generative AI driven BI** | Section 14: the provider interface (off by default) and the BI hooks: report from words, ask the data, explain this number, narrative summaries, designer copilot, dataset helper | A1 provider interface, guardrails, audit and cost limits (M); A2 report from words and designer copilot (M); A3 ask the data and explain this number (M); A4 narratives and dataset helper (S) | optional, administrator-enabled; every hook has a non-AI equivalent from stage 1 |
| **3. Generative AI driven UI** | The terminal and BI screens themselves composed from words: a Sutra or a workspace generated from a request and the data's shape, dynamic forms (the Rūpaka SRS's GenUI), conversational navigation, layouts that adapt to the task; all validated by the same schemas and published through the same review | designed after stage 2, from what stage 2 teaches | optional, as stage 2 |

**Stage 1 keeps the seams, not the features.** It defines no AI code, but its designs leave the places stage 2 plugs into:
every report, dataset and Sutra is a schema-validated document (so a generated one is checked like a written one); every
change goes through drafts and review (so nothing generated is published without a person); queries carry the user's row
rules and masks (so a prompt can only ever see what that user may see); and the designer edits a draft as a list of
changes (so suggestions can arrive as diffs).

## 21. Open decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| 1 | Where BI documents live | identity database; files in the BI folder; both | **Both**: the BI folder `config/bi` for shipped content (reviewed, versioned in git, never inside a pack), the database for workspace drafts and published versions |
| 2 | The measure language | extend Rachana-EL; adopt a DAX subset | **Extend Rachana-EL**: one closed language, already validated and documented; a DAX mapping table for authors |
| 3 | Streaming grid engine | Perspective; Drishti's own table panel only | **Perspective** for live and large grids (Apache-2.0, WebAssembly), Drishti panels for the rest |
| 4 | Exploration component | Graphic Walker; PygWalker in Pyodide | **Graphic Walker** directly; PygWalker inside Calc for Python users |
| 5 | Python runner host | inside the console process; worker subprocesses of the console; a separate service | **Worker subprocesses of the console, started on demand**: no extra deployment, and a runaway transform never stalls the web process; a separate service only when load measurements call for it |
| 6 | Command keyword for BI | `BI`; `RUP`; `RUPAKA` | **`RUPAKA`** (reserved; the product owner's choice), with `RUPAKA WORKSPACE` and `RUPAKA CATALOGUE` |
| 7 | Product name in the UI | "BI"; "Rūpaka" | **"BI"** in the top bar, "Drishti Rūpaka" in documentation and marketing |
