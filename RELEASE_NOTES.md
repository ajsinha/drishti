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
# Drishti 2.0.0 — release notes

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

# Previous release: Drishti 1.22.0 — release notes

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
