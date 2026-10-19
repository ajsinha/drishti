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
# Drishti 2.4.0 — release notes

*2026-10-19.* **BI Studio: authoring and analysis as one product, Python data prep step by step, and query by form at
scale.** (Wave C of [BI_DESIGN.md](docs/architecture/BI_DESIGN.md).)

- **BI Studio:** the report, model and theme designers share one shell: an Explorer of your items, open items as tabs,
  and a dock with **Problems** (the server's check of the draft, linked to each visual) and **Query** (the visual's model
  query and SQL).
- **Report schema v2:** a typed `format:` per visual family, checked by the server, drives a generated **Format pane**;
  **conditional formatting** (rules, colour scales, icons, colour per value), **themes** (Corporate and Print shipped, a
  theme designer), an **interactions matrix** (filter, highlight, none), slicer **sync groups**, **tooltip pages**,
  **small multiples** and the **matrix at every level**.
- **Boards are a report layout:** tiles live on board pages of a report, beside its own visuals; stored boards are
  converted at start (audited), and board files are still read until converted.
- **Python data prep:** **BI → Transforms** builds a Transform without YAML: inputs read straight from a pack kind
  through its connector (as the reader, masked), steps from a library of 21 tested snippets (as-of joins of prices and
  FX, pivots, tenor buckets…), a preview and a profile after every step; warm workers.
- **Browser Python console** (`/pyconsole`): your own Python on a masked extract, in the browser; **Promote** runs the
  same code on the server and keeps it as a notebook draft.
- **Query by form at scale:** a kind's form is kept per data version and per mask view: on 35.7 million trades with 50
  users, form search p50 went from 5.8 s to 0.21 s. A search on a date with no data says so and offers the newest date.
- **Toronto business calendar `CATO`.** A restated day reloaded while serving: 0 failed requests.
- **Live pages with several console workers:** a live channel's calls reach the worker that holds its stream, and a
  console stop closes open streams. Elements 1.4.2; the elements demo shows live valuations.
- **Fixes:** Analyse's filter picker lists its values again; a BI table that names its connector reads only that
  connector; the model designer no longer makes a dataset's own id a dimension; board tile alert settings are kept;
  44 px tap targets on phone.

**Not in this release:** field parameters in report wells (2.6.0). A full browser rehearsal and the tutorial 14 and 16
pictures still to retake are carried to the end of 2.5.0; the release drill's tests pass.

Upgrading from 2.3.0: stored boards become reports at the first start (`/bi/b/<id>` keeps working); see
[CHANGELOG.md](CHANGELOG.md).

# Previous release: Drishti 2.3.0 — release notes

*2026-10-17.* **BI that calculates like Power BI and stays live, production-shaped trades from three source systems, and a
demo you can run from a fresh clone in minutes.** (Wave B of [BI_DESIGN.md](docs/architecture/BI_DESIGN.md).)

- **Calculations with filter context:** `calc`, `share_of`, `parent_of` and `avg_by` change the filter a measure sees;
  calculation groups, field and numeric parameters (slicers), and % of total built in the UI.
- **Time and balances:** opening, closing and average balances, period-to-date and same period last year on the model's
  calendar, currency conversion; daily snapshots over a window take the last date instead of summing.
- **Live everywhere:** standing queries pushed from the server when data changes (no polling), a time picker (now, as of,
  window) on every report, and a chip that says when a visual is listening, and why not when it is refused.
- **Servers compute, the browser only draws:** a tier planner (source, Java server, console backend) with Python nodes,
  explained per visual in *How this was answered*; work classes, budgets and metrics for scale.
- **Production-shaped trades:** one UPPER_SNAKE schema (47 attributes, `LEGAL_ENTITY`, `SOURCE_SYSTEM`) for ENDUR, CALYPSO
  and MUREX, one Delta table and connector each, searched and analysed as one kind; deal pages with legs, cash flows,
  resets, events and charts; one Sutra per source system and product type built from shared blocks, with a coverage check;
  Compare across business dates including nested changes.
- **Seven packs** (core, market-data, trading, counterparty-risk, market-risk, world-economy, news-and-markets), UPPER_SNAKE
  attribute names everywhere, and samples as one JSON Lines file per kind (12,195 files became 69).
- **A demo from a fresh clone in minutes:** committed synthetic test data (`data/testdata`, 7 business days) and
  `drishti.py lake demo` (or the *Build demo lakes* run configuration) builds 64 Delta tables in under a minute.
- **Lakes:** Admin → Lakes lists every table; **lake maintenance** (retention, compaction, checkpoint, vacuum) runs now or
  on a schedule from the page or the CLI, only on tables Drishti built; a rebuilt lake is never served from old figures.
- **Working in the IDE:** run configurations for IntelliJ IDEA and PyCharm, the console reconnects to the server by
  itself, no hidden files, everything Drishti writes under the data folder.
- **A page explains itself:** the Sutra and connector named in *How this view was built* and *About this page* open in
  the designer and on the connector's page, at one click.
- **Docs verified for this release:** [docs/qa/2.3.0-docs-verification.md](docs/qa/2.3.0-docs-verification.md) lists
  every guide, tutorial and help page with what was checked and how to re-check it.

- **Offline demos:** `DRISHTI_OFFLINE=true` (set by the *Drishti server (demo lakes)* run configuration) starts no
  internet connector; quotes and news come from a committed snapshot (Yahoo Finance and Google News, captured
  2026-10-18) that `lake demo` builds into the lake. World economy data was already offline.
- **Ids are text everywhere:** a numeric `TRADE_ID` in a file is read as its exact text; ids may hold letters, dashes and
  underscores; nothing expects a prefix. Older lakes with numeric id columns still read.
- **[How to QA in Windows Environment using Custom Data](docs/guides/QA_ON_WINDOWS_WITH_CUSTOM_DATA.md):** an offline
  Windows demo with your own ENDUR files replacing the synthetic ENDUR trades, with screenshots; a *Load ENDUR file into
  the lake* run configuration; every Python run configuration uses the project interpreter (works on Windows).
- **Security fix:** field masks match whatever the case or naming style (a camelCase mask masks the UPPER_SNAKE field);
  a mask that matches nothing is reported (DRS-6011).

**Known test drift in this release:** the Java build and every Java, tool and documentation test pass. Part of the
console's browser suite still uses the pre-2.3 field names and ids in its own fixtures (BI report fixtures, embed
examples); those tests, not the product, are being updated in 2.3.1.

Upgrading from 2.2.0: trades and other kinds use UPPER_SNAKE attribute names; Sutras, BI items and forms written against
the old camelCase names need the new names (the shipped packs and examples are converted). See [CHANGELOG.md](CHANGELOG.md).

# Previous release: Drishti 2.2.0 — release notes

*2026-10-17.* **The BI foundation rebuilt: datasets and data models rooted in packs, a Python-like expression language,
read-only SQL, one query plan for everything.** (Wave A of [BI_DESIGN.md](docs/architecture/BI_DESIGN.md).)

- **Datasets and data models.** Every pack kind is a dataset automatically; a dataset can narrow, filter, de-duplicate
  explicitly, derive columns (expression or Python) and group, and always traces back to its pack. A **data model** is
  datasets, relationships, measures, hierarchies, security and a versioned contract — a small database rooted in packs.
  Reports, boards, alerts, subscriptions, exports, embeds, Analyse and AI all **reference** one model; many share one.
- **The model query language** (`mq: 1`): the one way the UI, the API, AI and the CLI ask a model; every refusal comes
  with its fix. Answers are identical whichever way you ask, per reader, masked.
- **DEL, the expression language**, now reads like Python (`sum(trades.mtm, where=trades.status == 'Live')`,
  `measure('MTM')`, `a if c else b`): 59 functions, a converter from the old syntax, and
  [EXPRESSIONS.md](docs/guides/EXPRESSIONS.md) with 59 tested finance and risk recipes; the editor completes, checks and
  fixes as you type.
- **Drishti SQL**, read-only, over models (`MEASURE('MTM')`): SQL views as datasets, SQL tests, SQL cells in Analyse,
  `POST /bi/sql` — compiled into the same secured plan.
- **One logical plan** with security applied per dataset before any join, fan-out refusal, a currency guard, rollups that
  respect row rules, and a result cache shared by readers with identical security.
- **Designers:** the dataset wizard (eight steps), the model designer (suggested relationships from packs, field
  properties, perspectives, hidden fields per role, row-rule and test editors, contract and semver, Used by and impact,
  Preview as a role, server autosave with a merge view), reports from a model or straight from a dataset, Python starters,
  and several datasets per report through a model.
- **Security:** the safeguard (a dataset open to a restricted role needs a rule or an audited note), hidden fields that
  answer "not available to your role", a warning when a hidden measure is still reachable, report-only readers limited to
  what their report asks.
- **Governance:** every publish carries a new version; breaking changes need a major; readers pinned `@1` keep the latest
  1.x; the publish gate runs model and dataset tests; lineage and impact from report to pack kind.
- **Deep trading JSON, generically:** Build → New pack reads JSON arrays and JSONL of any depth (ids in any case, blanks as
  missing, UPPER_SNAKE names as words, anonymised samples by default) and makes exactly one Sutra per product type; deal pages
  drill level by level (legs, cash flows, resets, events) with a breadcrumb and the keyboard; counterparty, portfolio and
  index pages; a BI model with a dataset per level; **Load data** streams a whole file into a pack's File connector and
  checks every row. A synthetic multi-product generator (`tools/packgen/comswap_synth.py --mix`) for samples and tests.
- **Lakes from big JSON files:** `drishti.py lake build|scan|status|maintain` streams a JSON array or JSONL file (any
  size, memory capped by the batch) into a Delta lake partitioned by business date and any classifier columns
  (`--partition-by PRODUCT_TYPE`), with Drishti's own Java Delta writer or the Python `deltalake`; scheduler-friendly
  (exit codes, a lock, a run summary); the same from **Admin → Data loads → Lakes**, which binds the lake to a pack.
- **[TOOLS.md](docs/guides/TOOLS.md):** one guide to every command-line and UI tool, with examples and scheduling.
- **The console reconnects by itself** when the server starts late or restarts (a banner, then the page recovers).
- **Also:** `external_lib/` (jars on the server's class path, Python modules in the console, `DRISHTI_EXTERNAL_LIB`); a
  deployed pack's Sutra opens in the designer; tutorials 10–18 rewritten for models with UI, CLI and YAML in every step.

**Upgrading from 2.1.0:** on first start the server converts stored BI items to the new expression syntax and stored
datasets to datasets + models (audited; the text before is kept and restorable; anything it cannot convert is listed in
Admin → BI settings and on the item's page — `drishti.py bi migrate --dry-run` shows what would change). The old dataset
query API answers `410` with the model query to use. The server jar now starts through Spring Boot's properties launcher
(`java -jar` is unchanged). The two live sample reports stay on their 2.1 datasets until wave B (live models).

# Older release: Drishti 2.1.0 — release notes

*2026-10-12.* **The BI long tail closed, a multi-day type-ahead index, and safe install and uninstall in Admin.**

- **Measures and reports:** window functions (`lag`, `lead`, ranks, moving and running figures), several unrelated fact
  tables in one query, every measure aggregated at its own table's grain (no fan-out, also in mixed-grain expressions),
  incremental live measures, dataset refresh state and history that survive a restart with **Refresh now**, the
  relative-date slicer, tablet layouts, expected-value tests in Check, the entity-view visual and a region economics sample.
- **Governance, live data and boards:** renames recognised as renames, BI items in pack archives, import rollback, impact
  of type and meaning changes, lineage past the connector, history of a live table, tile hysteresis and the **board
  designer**.
- **Python, Analyse and Perspective:** Vega-Lite visuals, Python on boards and in alerts, tests shipped with a Python item,
  Calc's `drishti_bi` client with Graphic Walker, Slice and SQL in the browser with DuckDB-Wasm, promote a notebook cell to
  a report visual, Perspective charts and its settings panel.
- **AI and distribution** (AI off by default, through the AI policy): narrative summaries in subscriptions written from
  each recipient's own figures, the designer copilot (suggestions and plain-words Check explanations), the dataset helper;
  native charts in Excel exports, alert delivery retries, **webhook** (signed, allow-listed hosts) and **SMS** (any HTTP
  gateway, never figures) channels.
- **Embedding:** `<drishti-about>` and `<drishti-search>` (token scope `embed:search`, read-only, masked, rate-limited),
  Elements 1.4.0, and React, Vue and Angular sample host pages.
- **Multi-day type-ahead index:** per connector `index.days`; every lookup by a concrete business date; closed days in
  compressed files under `drishti.index.dir` (`./data/index`) with a disk cap and their space freed at once; an automatic
  check every 10 minutes (`index.reload-every`) and **Reload** per day, kind or connector in Admin.
- **Admin → Artifacts:** every installed pack, connector file and shipped BI file in one list; what depends on it before
  anything changes; **Disable**, or **Uninstall** (refused while anything depends on it) into an archive with **Restore**;
  install from the same page; all audited.
- **Temp folders:** the server (`drishti.tmp-dir`) and the console (`DRISHTI_CONSOLE_TMP_DIR`) each keep one folder per
  process, cleaned at exit and at the next start.
- **Documentation:** the BI guides read as one set with no stale "not built" markers; tutorials 10–17 re-followed with
  new pictures where screens changed.

**Upgrading from 2.0.0:** nothing to migrate by hand; what is new is created at start. The type-ahead index keeps today's behaviour (`index.days: 1`) until you raise it; plan heap and disk with
docs/admin/OPERATIONS.md §12.2. Webhook hosts must be allow-listed by an admin before use. The Angular sample page needs a
looser policy on its own path (it compiles in the browser). Pending alert retries are kept in memory, so a restart drops
them.

# Older release: Drishti 2.0.0 — release notes

*2026-10-12.* **BI complete, maps, coherent live data, seventeen hands-on tutorials and one consistent look.**

- **BI essentials:** scheduled dataset refresh; time intelligence (year-, quarter- and month-to-date, prior period, same
  period last year, running totals, business-day aware); map, funnel, histogram, box and bubble visuals; true totals for
  measures that do not add up.
- **Several datasets:** **composed datasets** in YAML (`from:` and `join:` inner, left or full; each source masked and
  row-ruled for the reader before the join; key, fan-out and cycle checks), **Python Transforms** published as derived
  datasets, reports whose visuals each name a dataset with slicers and clicks that carry across (`links:`), and Analyse
  notebooks over several extracts. The sample *Desk P&L vs counterparty exposure* is built both ways.
- **Python in BI and Analyse:** Python measures, visuals and Transforms in a sandboxed runner (no network, CPU, memory and
  time limits, an allow-list of packages, masked input only), the same items in the browser through Pyodide; the Analyse
  workbench (Slice, SQL, Python cells, notebooks, promote a cell) and a quant-engine slot with a stand-in.
- **AI-driven BI**, off by default: describe a report, ask the data (run as the reader), explain this number. The AI layer
  follows Sajha's design in Python and Java (`LLMProvider`, `LLMModel`, the tool base) with **17 vendor types** (the
  OpenAI-compatible family with ten presets, Azure OpenAI, Anthropic, Gemini, Mistral, Cohere, Ollama, AWS Bedrock, a mock),
  each with the connectors' TLS settings and the HTTP proxy, and **Test connection** in Admin → AI.
- **Maps:** a `map` Sutra panel (a jurisdiction's page highlights its country; a click opens another), region shapes for
  251 countries, and BI maps that drill from world to country to region.
- **Coherent live demo data:** a tick moves a curve's points and forwards with its headline rate, a trade's legs with its
  MTM, a netting set's exposure profile, an LCR's parts; every live sample is checked to stay consistent.
- **Connection pools everywhere** (JDBC and PostgreSQL, the identity database, MongoDB, Redis, Aerospike): 20 by default,
  leak warnings with the borrower, borrows only for a unit of work (a build rule), in-use shown in Admin and Health.
- **One look:** one top bar on every page (Forms and Reports inside Views; the Live button carries the page's stream);
  Sajha's Crimson, Dark, Blue and Green themes with their navigation gradients; the Cinzel wordmark; a footer with the
  slogan; dropdown slicers that float over the page; a tidy function-key bar.
- **Documentation:** **17 hands-on tutorials** (docs/tutorials: CLI and UI steps with real output and screenshots), a help
  centre of 40 pages in six sections (every old address redirects), and a pass of every guide against the code.
- **Reliability:** one shared file watcher per process; the drill removes its temporary files.

**Upgrading from 1.22.0:** new tables for revisions of composed datasets and Transform caches are created at start. The
console needs `pandas`, `numpy`, `pyarrow` and `boto3` (`requirements.txt`). Pool sizes default to 20: check the
database's `max_connections` against pools × instances (docs/admin/OPERATIONS.md). Help addresses changed; the old ones
redirect. The BI menu no longer lists report names, and Forms and Reports moved into Views. Pyodide (browser Python) is
installed by `tools/fetch-pyodide.sh`, as for Calc.

# Older release: Drishti 1.22.0 — release notes

*2026-10-11.* **Distribution: export, subscriptions, alerts and embedded reports.**

- **Export** (docs/guides/DISTRIBUTION.md): a BI report, page or visual, or a Sutra view, as PDF (paged, with the title,
  date and filters), PNG, Excel (a typed sheet per visual) or CSV, made for the person asking: their masks and row rules.
- **Subscriptions:** a report, page, visual or view by email on a schedule (daily, business days, weekly, monthly,
  last business day, hourly, cron, or after the day's data lands), as the email itself, a PDF or an Excel file, to users
  and roles. **Each recipient gets their own render** with their own masks; someone who may not open it is skipped, with
  the reason in the history. Pause, run now, history, retries. Outside email addresses are refused.
- **Alerts on measures:** a threshold, a change, or data that did not arrive, on a BI visual, a dataset measure or a
  Sutra field; checked on a schedule, after a load, or live; judged for each person on their own numbers (a masked number
  is never judged or sent); hysteresis and cooldown; to the inbox, email or chat. Board tiles can notify their audience.
- **`<drishti-report>`:** embed a published report, page or visual in another web application, always masked, under the
  host's CSP; Admin → Embedding says which hosts may embed which reports. Elements are versioned, with a package folder.
- **Phone layouts per report** (`layouts.phone`), arranged in the designer's Phone tab.
- **Reliability:** one shared file watcher per process (it was six), which also ends random test failures from the
  operating system's watcher limit; the drill removes the temporary files its tests make.

**Upgrading from 1.21.0:** subscriptions and measure alerts add tables, created at start; existing scheduled reports
appear in the same list and keep working. Outside email addresses on old subscriptions are skipped at send. Hosts that
embed reports need the `embed:report` scope and `report:<id>` (or `report:*`) in their allowed kinds.

# Older release: Drishti 1.21.0 — release notes

*2026-10-11.* **BI you can govern and watch live; connectors behind a proxy; Forms and Reports in the top bar; documentation checked against the code.**

- **Publishing and governance (Rūpaka phase 4)** (docs/guides/BI_GOVERNANCE.md): every save is a version with history, a
  side-by-side diff and restore; review with the diff, Check, impact and a data preview (four eyes); certified and promoted
  badges, certification revoked when a dataset change breaks what it certified; lineage and "what breaks if"; a publish
  that breaks published reports is refused unless a BI admin forces it; BI bundles between environments (dry run first,
  all or nothing, boards bring their reports and datasets); every step audited.
- **Streaming BI (Rūpaka phase 5)** (docs/guides/BOARDS.md, REPORTS.md): live dataset tables kept in memory and updated by
  key, live visuals redrawn in place (pause, resume, stale and disconnected states), Perspective grid and pivot under a
  narrow policy on the pages that hold them, boards with kiosk mode. Samples: Live trading desk, Live exposure, Desk wall.
- **Outbound HTTP proxy** (docs/connectors/PROXY.md) for REST, feeds, news, Yahoo Finance, S3, Delta and Iceberg on S3,
  the schema registry, and the AI and MCP clients: per connector, a site default, or the environment; credentials only
  as `${ENV}` and masked everywhere; Admin shows the effective proxy and tests through it.
- **Connectors that are code:** `news` (Google News RSS) and `yfinance` (Yahoo Finance), off by default, with the sample
  packs **news-watch** and **market-watch**. Yahoo's endpoint is unofficial: samples and internal demos only.
- **The top bar:** **Forms** (with a `/forms` home; `FORM <GO>` opens it) and **Reports** menus beside BI; **Live** is one
  round toggle on every screen (green live, red paused); no overlap from 1024 to 1920 px; every menu fits a phone.
- **Query by form:** a field with more than three values is a checkbox dropdown with a filter box.
- **Admin pages for every connector and pack.**
- **Documentation reviewed against the code:** about 150 fixes across the user, BI, admin, API, connector and developer
  guides and the in-product help; new tests keep the API guide, error codes and the connector settings catalogue in step
  with the code.

**Upgrading from 1.20.0:**
- The identity database gains `drishti_bi_revision` and `drishti_bi_event`, created at start; BI history starts at the
  upgrade.
- The Python AI and MCP clients no longer read `HTTP_PROXY` by themselves; set `DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT=true`
  to keep that.
- The news-watch pack's code is `NEWSW` (`NEWS` is its headlines command); its roles are `news-analyst` and
  `market-analyst` (market-watch).
- The connector settings catalogue no longer marks settings with code defaults as required (file `root`, kafka
  `bootstrap-servers` and others); files that relied on the old warnings are unaffected.
- **Give the server a heap limit** (`-Xmx`, for example 3g for a workstation): without one Java may grow to a quarter of
  the machine's memory before collecting. A 2-hour check showed no leak (about 400 MB live after a collection).

# Older release: Drishti 1.20.0 — release notes

*2026-10-10.* **BI you can use: datasets, a query engine, reports and a designer; deep trades; real public data.**

- **Datasets and the query engine (Rūpaka phase 2)** (docs/guides/DATASETS.md): a dataset is YAML over pack kinds, across
  packs, with relationships, measures, hierarchies, row rules and masks. Queries run on an embedded DuckDB over columns
  and rollup layers, rebuilt when a day's data lands, with caching and limits (`DRS-9010` to `9013`, `9020` to `9027`).
  Row rules fail closed; masks apply before aggregation. Three sample datasets over the trades.
- **Reports, the designer and the Model view (phase 3)** (docs/guides/REPORTS.md): the viewer at `/bi/r/<id>`
  (`BI <report> <GO>`) with 21 visual types, cross-filter and highlight, drill-down, drill-through, show the rows and
  open in the terminal; the designer at `/bi/design/<draft>` with Fields, Visualizations, Format and Filters panes, a
  12-column canvas, a two-way YAML tab, Check, undo and redo; the Model view at `/bi/m/<dataset>`. Three sample reports:
  Trades overview, Counterparty risk, Cashflow ladder. Learn it step by step in docs/guides/BI_TUTORIAL.md.
- **Deep trades:** swaps, basis and cross-currency swaps, caps, swaptions and commodity swaps carry legs, cashflows and
  fixings, three or four levels deep; a `tabs` panel holds whole panels per tab, so a trade shows a tab per leg with its
  cashflows opening to their fixings.
- **Real public data** in the `economics` and `politics-society` packs (World Bank, UNDP, CIA World Factbook, IMF) from
  the repeatable `tools/realdata/` pipeline. The IMF forecasts are for internal demonstrations only.
- **Shared views keep their filters:** a sent share carries the sender's control values and column filters, and the
  recipient (and the emailed picture) sees the same view, masked for the recipient.
- **The `slider` control:** one value or a range, keyboard and touch.
- **Admin pages for every connector and pack**, linked from the lists.
- **Fixed:** Build → New pack no longer loses what you type when a background re-plan arrives.

**Upgrading from 1.19.0:** the identity database gains a nullable `view_state` column on shares, added automatically
(SQLite and PostgreSQL); old shares read as having none. The trading pack's deep samples renamed some cashflow fields
(`accrualStart` → `periodStart`, `accrualEnd` → `periodEnd`, `yearFraction` → `accrualFactor`, `df` → `discountFactor`,
`businessDay` → `businessDayConvention`): your own Sutras over the shipped sample trades may need the new names. BI
datasets and reports are new folders under `config/bi/` and load live. Not built yet (marked in the docs): scheduled
refresh, time intelligence and window functions, live visuals, several visual types, reports over several datasets.

# Older release: Drishti 1.19.0 — release notes

*2026-10-10.* **Connectors as files with TLS everywhere, query by form, Drishti Elements, no restarts, and the start of BI.**

- **Connectors are files** (docs/connectors/CONNECTOR_FILES.md): one `config/connectors/<name>.yaml` per connector, named
  by packs; **Admin → Connectors** creates, tests, edits and disables them; changes apply live. Inline pack connectors are
  templates, written to files once at first start; old per-pack data-source overrides migrate automatically.
- **TLS/SSL for every connector** (docs/connectors/TLS.md): PEM, PKCS12 and JKS, mutual TLS, expiry warnings; Kafka
  (Apache and Confluent: SASL, Schema Registry with JSON Schema and Avro), ActiveMQ, RabbitMQ, Redis, MongoDB,
  PostgreSQL, REST, S3, Delta, Iceberg, Aerospike.
- **Query by form:** `FORM <pack> <GO>` — typed criteria (safe regex, amounts, business-date ranges, multi-select
  dropdowns), results in a grid with drill to the record, save as search, monitor or report; the form folds after a search
  (Alt+F). Every pack has a `form:`; the Build workbench has a Form tab.
- **Interactive Sutras:** `controls:` (dropdowns, date ranges, cascading, top-N …) read as `ctl.*`, cross-filtering by
  click, and **Excel-style column filters** on every table.
- **Panel zoom** (`Z`, the ⤢ button), with zoomed tables fitting the space.
- **Drishti Elements:** `<drishti-view>` puts live views in other web applications without iframes (token exchange,
  always masked, Admin → Embedding).
- **Build → New pack:** a pack from JSON Schemas and/or a folder of JSON Lines, previewed and deployed in the browser.
- **Nothing needs a restart:** packs, connectors, BI files and AI/MCP files load, reload and unload live; anything that
  failed retries with backoff (Admin and Health show the next attempt).
- **BI (Rūpaka), first steps:** the **BI** menu and `BI <GO>`, workspaces, catalogue, publishing with review, BI powers;
  datasets and reports come next (docs/architecture/RUPAKA.md).
- **AI and MCP layers, off by default:** pluggable LLM providers (OpenAI via Spring AI, Anthropic, a mock) and an MCP
  client, on the server and the console, under one policy (masks, audit, limits).
- Also: sign-in and sign-out links on public pages; the console listens on 0.0.0.0; finger-sized controls on touch
  screens; named holidays in the calendars; sample packs can be hidden from business users.

**Upgrade notes:**
- **Folders:** packs moved to `config/packs/` (a missing folder falls back to `./packs` with a warning); connectors live in
  `config/connectors/`; BI files in `config/bi/`; runtime data under `DRISHTI_DATA_DIR` (default `./data`). All paths are
  configurable (CONFIGURATION.md).
- **Connectors:** on first start, connector files are generated from pack templates and old overrides are migrated (a
  backup is kept). A literal secret in a connector file is refused: use `${ENV}`.
- **Roles:** the default roles `viewer`, `author` and `approver` now carry BI powers; roles defined by packs get none until
  granted.
- **Console:** new Python dependency `cryptography` (run `uv pip install -r drishti-console/requirements.txt`);
  DuckDB-Wasm for the BI proof of concept is fetched by `tools/fetch-duckdb-wasm.sh`, not committed.
- **New problem codes:** DRS-1011, DRS-2034..2039, DRS-2060, DRS-4010..4019, DRS-5031..5033, DRS-8001..8006, DRS-9001..9006
  (API_GUIDE.md).

# Older release: Drishti 1.18.0 — release notes

*2026-10-06.* **Data loads: tell Drishti new data has landed, and it acts on it.**

- **The "batch landed" signal** (docs/guides/DATA_LOADS.md). Your ETL — Airflow, a cron script, DishtaYantra — tells
  Drishti that a kind's data for a business date is ready: `POST /api/v1/packs/{pack}/loads` or
  `drishti.py data landed --pack P --kind K --date D --rows N`. Drishti then refreshes what it reads, **verifies** the
  date is there, **evaluates the alert rules** on that date, optionally runs a smoke check, and **sends notices** (bell,
  inbox, email) to the roles you choose, e.g. "EOD trades for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts
  fired". Re-announcing is safe (idempotent per pack, kind, date and batch); a failure is recorded too.
- **Expectations and late data:** per pack, which kinds are due each business day and by when (time, zone, business-day
  calendar). A late or missing load is flagged once, Health shows `data late: …`, and the flag clears when it lands.
- **Admin → Packs → Data loads:** the history of every load with each step's result, today's expectations (on time,
  late, missing), filters and settings. **CLI:** `data landed` (exit 1 when a ready load is not verified) and
  `data loads [--expectations]`.
- **A least-privilege token scope, `loads:write`,** for ETL jobs: they may announce loads only for kinds their user's
  roles reach.
- Drishti does not ingest data itself: `data ingest`, `--watch` and `pack make` stay for staging, small setups and
  proofs of concept; production ingestion stays with your ETL.

**Upgrade notes:** none required. New: `drishti.loads.*` settings, the `loads:` section in `pack.yaml`, the `loads:write`
token scope, problem codes DRS-5011 and DRS-5012.

# Older release: Drishti 1.17.1 — release notes

*2026-10-06.* **A way home and back, hot reload that never stops, and a drill in nine minutes.**

- **The name and logo lead home:** clicking them in the top bar opens the landing page from any page, also when you are
  signed in; the landing page then offers **← Back to …** (Alt+B) to the view or page you came from. Only same-site paths
  are accepted, so the logo cannot be turned into a redirect.
- **Sutra hot reload never stops silently:** when the operating system has no file watches left (Linux *User limit of
  inotify watches reached*, common on developer machines with several IDEs), the server polls the Sutra files instead
  and keeps picking up edits; Admin → Health shows `hotReload: POLLING`. New settings `drishti.rachana.watch: auto|poll`
  (`DRISHTI_SUTRA_WATCH`) and `poll-interval` (2 s).
- **The drill takes about 9 minutes instead of about 22, testing as much:** Java tests in parallel forks, console tests
  in parallel workers, the Java 25 check beside the console tests, and a `--docs` mode (about a minute) chosen by itself
  for documentation-only changes; `--no-push` for timing runs. Browser tests reload a page once when Chromium cancelled
  its requests because Docker changed the network (`ERR_NETWORK_CHANGED`), the cause of the rare workbench failures.
- Tests no longer depend on the order they run in, and no longer leave files in a `data` folder of the server module.

**Upgrade notes:** none required. Developers: `uv pip install -r drishti-console/requirements-test.txt` once (adds
`pytest-xdist`).

# Older release: Drishti 1.17.0 — release notes

*2026-10-05.* **From a folder of JSON Lines to a deployed pack, by command line or by the admin page.**

- **`drishti.py`, one command line for every build activity** (docs/guides/CLI_GUIDE.md, with real output for every
  command and a PyCharm section):
  - `pack make`: a folder or file of JSON Lines, a kind, a match column and a name give ONE folder to deploy: the pack
    (Sutras per group, tests, About skeleton), the data as a Delta lake or files partitioned by business date, a signed-
    ready bundle, a README.txt written for that pack (deploy, verify, lift data from Delta, add days, roll back), a
    server overlay and run scripts. Key and date fields are detected; the reasons are printed.
  - `sutra gen`, `data profile` (suggests key, date and match columns), `data ingest` (Delta or files, idempotent by
    date, `--watch` for drop folders), `pack regenerate` (a three-way merge that keeps your edits), `pack diff`
    (breaking changes flagged), `pack check`, `catalogue`, `i18n`, `bundle`, `verify`, `deploy`, `rollback`,
    `server smoke`, `doctor`, `view get|explain`, design commands over the API, and shell completion.
- **Admin → Packs → Deploy archive:** upload a bundle; the server verifies it (checksums, paths, server version,
  Sutra tests, optional signature), previews what changes with breaking changes highlighted, deploys with the previous
  version kept and an automatic rollback, and keeps a history with Roll back. A **Data source** panel per pack edits,
  tests (dates and row counts) and resets where the data comes from; changes live in an admin override file, so a
  redeploy never loses them. Archives carry the pack only, never data.
- **Operationalising by copying** (docs/guides/OPERATIONALISING.md): promote the same checksummed bundle from dev to
  test to prod; Docker and Kubernetes recipes.
- **Personal API tokens can write** when created with a scope (`design:write`, `design:approve`, `packs:admin`), always
  within the user's own roles, expiring, audited; existing tokens stay read-only.
- **The console module is `drishti-console/`** (was `console/`).
- Fixed: pack checks from the command line see the pack's own About text; server tests no longer leave a `data` folder.

**Upgrade notes:**
- Recreate the console's virtual environment once: `rm -rf drishti-console/.venv`, then QUICKSTART step 3; point IDE run
  configurations at `drishti-console/`.
- New settings: `drishti.packs.deploy.*`, `drishti.security.token-scopes`, `token-write-max-days`, console
  `packs.deploy_max_mb`. See CONFIGURATION.md.
