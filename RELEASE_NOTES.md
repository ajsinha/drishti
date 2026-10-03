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
# Drishti 1.14.0 — release notes

*2026-10-03.* **The Build workbench: from JSON files to a working, reviewed screen in minutes; two rounds of adversarial QA closed.**

- **Build workbench** (Build → New screen). One page to design, test and ship screens:
  - **Bring data:** JSON or JSONL files, a whole folder, a JSON Schema (samples are generated), entities from a store,
    an example, or an existing Sutra. Drishti infers a schema with a role for every field (id, link, measure,
    dimension, series, tree, OHLC, …) and **auto-designs** a first screen, each panel with its reason and alternatives.
  - **Visual canvas:** the real view, with **+ Add panel** on every tab, a palette of all 20 kinds, drag to move,
    edges to resize, drop a field for suggested panels or to bind it, remove with the trash button (with Undo), an
    inspector with an editor for every option, YAML side by side, Notes, and a keyboard path for everything.
  - **Tested as you type** against every sample (panel × sample grid), **preview with any JSON file**, versions and
    diffs, a command palette (Ctrl+K), undo and redo.
  - **Ship:** propose with evidence (check grid, sample names, notes, diff); approval publishes the next version.
    Export a pack fragment, import a pack folder or zip, read-only share links, and file binding to a draft folder for
    IDE editing in development.
  - **Designs are kept on the server** per user (file or database store), with quotas and expiry; examples open as
    your own copy. Designing is open to every signed-in user; saving, proposing and approving keep their rights.
  - Guide: [SCREEN_DESIGNER.md](docs/guides/SCREEN_DESIGNER.md) (with screenshots); design:
    [BUILD_WORKBENCH.md](docs/architecture/BUILD_WORKBENCH.md).
- **Headless `sutra` CLI** in the server jar: `lint | test | shape | design | preview`, JUnit XML and HTML snapshots for
  CI ([SUTRA_CLI.md](docs/guides/SUTRA_CLI.md)).
- **Panels:** `source:` on every data panel (read a linked entity), **expandable row groups** (pivot `by: [a, b, c]` with
  subtotals; tree tables with `children`), ten [examples](docs/guides/examples/README.md) covering all 20 kinds.
- **How it fits together:** [HOW_IT_FITS.md](docs/architecture/HOW_IT_FITS.md) follows a trade and a genomics variant
  through packs, connectors, Sutras and the UI.
- **Quality:** every finding of the [2026-10-01](docs/qa/2026-10-01/README.md) and
  [2026-10-03](docs/qa/2026-10-03/README.md) adversarial QA rounds is fixed or addressed (sessions that follow the
  user, masks everywhere, one live connection per browser, atomic loads, safe retention, stricter grammar, phone width
  and contrast, and more). Browser tests run in the build (Playwright).

**Upgrade notes**

- **Sign in once** after upgrading: console sessions are now kept on the server; upgrade the server and console
  together. Servers behind one load balancer share one identity database.
- **Identity schema:** new tables for console sessions and designs are created at start (SQLite and PostgreSQL).
- **Redis loads replace each day by default;** partial or intraday loads pass `--merge`.
- **Stores** for one kind are consulted in configuration order (after `routes`).
- **Old Studio links** (`/studio?example=…` and the like) show a confirmation page before a design is created.
- **`deploy/compose.yaml`** requires `DRISHTI_ADMIN_USER` and `DRISHTI_ADMIN_PASSWORD` and publishes the server on
  127.0.0.1 only.
- New settings are listed in [CONFIGURATION.md](docs/admin/CONFIGURATION.md) (`drishti.builder.*`, `drishti.panels.*`,
  `drishti.security.token-read-posts`, console `auth.*`, `live.*`, `ui.*`, `builder.*`).

# Previous release: Drishti 1.13.0 — release notes

*2026-10-01.* **A million trades a day, eight stores, Calc, pivots, twenty panel kinds and JDK 25.**

- **A million trades a day, for years, in eight stores.** Delta Lake, Apache Iceberg, PostgreSQL, DuckDB, MongoDB,
  Redis, Aerospike and JSON-lines files share one design.
  - **Layout:** a pack promotes the fields searches use, and each store keeps them beside the documents.
  - **Speed:** type-ahead comes from memory; a single read touches one row group, row or record; searches, pick lists, desk P&L and impact read a day's columns, exact over the whole book.
  - **Combining stores:** a store that does not hold a date passes the question to the next one, for example recent days in Redis and years in Delta Lake.
  - Each store has its own design document under `docs/connectors/`.
  - Measured curves from 10,000 to 50,000 trades a day are in `docs/admin/SCALE_BENCHMARK.md`, and `tools/bench/scale.sh` reruns them.
- **Delta Lake without Hadoop.** A native engine (`drishti-deltalake`) is now the default: no Hadoop file system and no `winutils.exe`, and as fast or faster. Windows scripts and a guide are in `docs/guides/WINDOWS.md`.
- **Calc: Python in the browser** (Alt+C). Pyodide with numpy, pandas, scipy, statsmodels and matplotlib, on the screen's data and the pack's.
  - It ships 85 trader and quant snippets and a tested `drishti.quant` library.
  - Only users with the `calc` power can use it.
- **Twenty panel kinds.**
  - **New kinds:** waterfall, histogram, scatter, candlestick, graph, timeline and pivot.
  - **Pivot tab:** an Excel-style tab on tables and search results, wherever a Sutra or pack turns it on.
  - **Layout mode** (Alt+L): drag and resize panels into personal layouts, and promote them to the Sutra through review.
  - **Workspaces:** drag views into panes.
- **Several queries per kind** in the JDBC connector's query mode: parts, type-ahead, columns and reverse lookups.
- **Durable message state.** ActiveMQ and RabbitMQ acknowledge a message only after it is kept (synced by default), within a disk budget per connector.
- **Deletes reach open views.** A Kafka tombstone or a queue's delete marks the view deleted and removes the entity from type-ahead.
- **JDK 25 only**, run with `-XX:+UseCompactObjectHeaders`. Measured against JDK 21: 10–20% more requests a second and 10% less live heap.
- **Documents by audience:** `docs/guides`, `docs/connectors`, `docs/admin` and `docs/architecture`.

### Upgrading from 1.12.0

- **Java:**
  - Drishti builds and runs on **JDK 25 only**; the jars no longer run on 21.
  - Start the server with `-XX:+UseCompactObjectHeaders`.
  - The Docker image is `eclipse-temurin:25-jre`.
- **Delta Lake engine:** `native` is the default.
  - Lakes on `abfs://`, `gs://` or HDFS need `engine: hadoop` (or `DRISHTI_DELTA_ENGINE=hadoop`). The connector refuses to start with a message saying so.
  - Parquet compressed with LZ4, Brotli or LZO also needs `engine: hadoop`.
- **PostgreSQL table mode:**
  - It reads a table partitioned by month with promoted columns. Reload with `tools/load-postgres.sh`; it recreates each domain's table.
  - Tables of the earlier form are still read, but their searches read documents.
  - `make_data.py --postgres` is removed.
- **ActiveMQ and RabbitMQ state store:**
  - `state.durability` defaults to `sync`, which is slower but loses nothing; `wal` and `none` are faster.
  - The store keeps the latest value per entity.
  - Past `state.max-gb`, `state.when-full: evict-oldest` (the default) removes the entities written longest ago and logs it; `warn` keeps everything.
  - ActiveMQ redelivers a message the store could not keep without limit; set `max-redeliveries` for a dead-letter queue.
- **MongoDB and Iceberg plugins** stay idle unless configured: `uri` for MongoDB, `root` or `uri` for Iceberg.
- **Data is no longer in git.**
  - `data/banking.jsonl` and `data/feeds/` are gone.
  - Run `python3 tools/packgen/banking/make_data.py` once; it also writes the sample feed file. Use `tools/load-<store>.sh` for the stores.
- **Identity database:** new role powers `calc` and `layout`, and new preference namespaces (`calc-snippets`, `layouts`, `pivots`). These are created at start, with no migration.
- **Moved documents:** bookmarks to `docs/*.md` now point to `docs/guides/`, `docs/connectors/`, `docs/admin/` or `docs/architecture/`.
- **New problem codes:** `DRS-2029` (a restricted option value), `DRS-2030` (`span`/`height`) and `DRS-2031` (`pivot`).

# Previous release: Drishti 1.12.0 — release notes

*2026-10-01.* **Derived kinds, notes, reports, plain words and a signed pack registry.**
- **One console, many servers (ADR-016).** Configure a list of servers in the console. People pick one and sign in to it. Each server has its own session, and links carry their server.
- **Derived kinds.** A pack can declare a kind computed from another, such as a book's or desk's P&L from its trades.
  - It has history wherever its members do.
  - The finance pack ships `BPNL` and the trading pack ships `DPNL`.
- **Notes and shared workspaces.**
  - **Notes:** anyone who may open an entity can leave notes on it or on its fields. They are audited, and only the author edits them.
  - **Shared workspaces:** workspaces can be shared read-only with everyone, with roles or with people.
- **Scheduled reports.** A search can run as you on a schedule (`business-days 18:30` and others) and deliver CSV to a folder or to an allowed webhook.
- **Who looked at what.** Every answered read is recorded and shown in Admin → Access, kept for 90 days.
- **Ask in plain words.** Type `live trades over 5m in BOOK-RATES-3, biggest first` to see the search it makes before it runs. It is deterministic, with no AI service.
- **Signed pack registry (ADR-018).** Ed25519-signed, versioned packs can be installed, upgraded and rolled back from Admin → Packs.
- **Expression language change:** text that is not a number now orders as text, so ISO dates compare (`$.maturityDate < '2028-01-01'`).
- **Fix:** CSV numbers are written in full.

---

# Drishti 1.11.0 — release notes

*2026-10-01.* **Tokens, history and freshness.**
- **Personal API tokens:** read-only, revocable, kept only as a hash and audited. You make them under *My account*, and administrators manage them in Admin → Tokens.
- **Clients:** a standard-library Python client (`clients/python/drishti_client.py`), searches as CSV, and Excel through Power Query (see `docs/guides/CLIENTS.md`).
- **Field history:** click a number in a view to see it over the last 10, 30, 90 or 250 business days, as a chart and a table. The window also shows where the value comes from: its path, connector, business date and generation.
- **Search compare:** the search page's **Compare with** shows each number's change between two dates and marks entities added or removed.
- **Freshness:** a view's footer says when its source last received new data. A connector's `stale-after` setting marks it behind, with a banner on views and a **Last update** column in Admin → Health, and the overall status turns `DEGRADED`.
- **Kafka health:** it now goes `DOWN` when the broker is lost and back to `UP` when it returns.
- **Guides:** every Developer Guide recipe and Connector Guide walkthrough was rerun against a real build and real containers, and corrected where they differed.

---

# Previous release: Drishti 1.10.2 — release notes

*2026-09-30.* **Themes and the top bar.**
- **Gradients:** every theme now shows its gradient. Parchment, Crimson, Crimson dark and Wall Street were too faint, so each theme now sets its own strength.
- **Links:** links in tables are underlined, so ids read as links in every theme.
- **Top bar:** it is one row again. The command line, business date and Live sit beside the brand, then the menus (which open leftwards and stay on screen) and the tools.
- **Menus:** the entry for the page you are on is readable: bright text, a neutral highlight and an accent bar.

---

# Drishti 1.10.1 — release notes

*2026-09-30.* **Kafka ticks mode works.**
- **What changed:** a Kafka connector with `mode: ticks` keeps no state and answers no reads. It now drives the live updates of views that a lake or database answers. Such a view shows as live and repaints on every message for its entity, while reads still come from the store.
- **API:** plugins gain `SourcePlugin.pushes(ref)` (default false) for this case.
- **Upgrading:** nothing else changes from 1.10.0.

---

# Previous release: Drishti 1.10.0 — release notes

*2026-09-30.* **Fourteen domain packs, users in a database, and a terminal that behaves like one.**

- **Domain packs.**
  - **Banking:** five packs generated from one taxonomy, with 125 products, 45 kinds and 170 Sutras: banking core, market data, trading, market risk and counterparty credit risk.
  - **Other domains:** liquidity, climate, operational risk, retail banking, genomics, economics, and politics and society.
  - **Inheritance:** packs inherit from parent packs (`extends:`, ADR-015).
  - **Switching:** admins switch packs off and on for everyone in Admin → Packs.
- **Users, roles and saved work in a database.**
  - **Databases:** SQLite by default, PostgreSQL by URL, through JPA. There is one schema file per database and no migrations.
  - **Roles:** admins define roles in Admin → Roles.
  - **Upgrading:** 1.9 users, audit and saved documents are imported on first start.
- **Pick lists.** One match opens the entity; several give a table with the kind's key fields. Case never matters.
  - `TRD T-100` lists trades whose id starts with T-100.
  - `CPTY north` matches titles too.
  - `TRD productType=Revolver` lists trades by field value.
- **Tables:** every table pages and walks with the keyboard, and id columns link to their entities.
- **Top bar:** redesigned on two rows with mega menus.
- **Connectors.**
  - **Message queues:** Kafka, ActiveMQ and RabbitMQ, keeping state in RocksDB.
  - **Stores:** PostgreSQL table mode, Aerospike, S3, and Delta Lake on S3 with scheduled lake maintenance.
  - **Public data:** NY Fed, ECB, US Treasury and FRED feeds.
  - **Reconnecting:** every connector reconnects by itself, and Admin → Health shows each one's state.
- **Waves 16–21:**
  - history and diffs between dates;
  - structured search;
  - export and share links;
  - Sutra governance (four eyes, ADR-013);
  - single sign-on with OpenID Connect (ADR-014);
  - personal settings.
- **Reliability.**
  - **Freeze fix:** the UI no longer freezes; each tab uses one live connection.
  - **Concurrency:** hardened throughout.
  - **Security:** with security on, the operational endpoints are guarded (`DRISHTI_METRICS_TOKEN` for Prometheus).
  - **Readiness:** the console has `/readyz`.
- **Configuration and documentation.**
  - **Branding:** the product name and legal notices come from configuration.
  - **Documentation:** every guide is rewritten with worked examples, and there are a new quickstart and a developer guide.

Upgrade notes:
- **Identity database:** the server creates `data/identity/drishti.db` and imports `users.json`, `audit.jsonl` and `preferences/`, renaming them `*.imported`. For PostgreSQL, set `DRISHTI_IDENTITY_DB_URL`, `DRISHTI_IDENTITY_DB_USER` and `DRISHTI_IDENTITY_DB_PASSWORD` first.
- **Monitoring:** with security on, give Prometheus `DRISHTI_METRICS_TOKEN`.
- **Plugins:** they keep working; one with no settings may now throw `PluginNotConfigured` to stay idle.

---

# Previous release: Drishti 1.9.0 — release notes

*2026-09-30.* **Business dates and history.** Drishti now answers "what did this look like on a given day?"

- **Live or a picked date.** The top bar has a date box. **Live** (the default) is the current business date on
  the New York calendar, and views stream. A **picked date** is a static snapshot of that day's end-of-day
  data, even when it is today: the view, its links, F8 impact and suggestions all follow the date. Weekends and
  holidays roll back to the previous business day.
- **Delta Lake.** History is read from Delta Lake by a new server connector (Delta Kernel, no Spark): one table
  per kind under `data/delta/<domain>/`, partitioned by business date, with time travel for data "as known at"
  an instant. The console never touches the lake. **Named connectors** let each domain have its own.
- **Imperfect data never breaks a screen.** A panel whose data is missing or has the wrong shape says "No data
  available".
- **Sutras are Markdown documents** (`*.sutra.md`): prose that explains the layout, around one `sutra` block.
  Studio is now a Markdown editor with snippets, an outline and a rendered Document tab.
- **Realistic sample data.** Every finance trade is fully booked (identifiers with check digits, execution,
  lifecycle, regulatory, settlement, valuation, full cashflow schedules that reprice to the MTM), built on the
  new `tools/samplegen` toolkit.
- **Risk pack groundwork:** a taxonomy of 125 products and 45 data kinds, and 170 generated Sutras.
- **Themes:** *crimson* and *crimson dark*, from Maya.

Upgrade notes: plugins keep working unchanged (the dated SPI methods have defaults). Sutra `*.yaml` files still
load. `tools/sutra_to_md.py` converts them. To see history locally, build the sample lake:
`uv run --with deltalake --with pyarrow python tools/samplegen/lake.py --samples packs/finance/samples --root data/delta --domain finance`.

---

# Drishti 1.8.0 — release notes

*2026-09-30.* **Packs per user.** Admins assign domain packs to each user. Users with several packs
choose which to see, and the server enforces it.

---

# Drishti 1.7.0 — release notes

*2026-09-30.* **F8 Impact** is built. It is the known limit listed since 1.0.0, and it now shows what
depends on an entity, what that rolls into, and the amount at stake.

---

# Drishti 1.6.0 — release notes

*2026-09-30.* This release adds **monitors and alerts**:
- live watchlists of mixed entities, over one connection per page;
- alert rules that the server checks on every tick and that fire once when they become true, with a
  bell and toasts;
- packs that suggest rules and starter monitors.

---

# Drishti 1.5.0 — release notes

*2026-09-30.* **Domain packs.** Drishti's core is now industry-neutral. Finance (the mockups) and
logistics (shipments, containers, vessels, ports) ship as packs, enabled with `DRISHTI_PACKS`. A new
industry needs configuration and content only: Sutras, vocabulary, links, roles, samples and guides.
See `docs/guides/PACKS.md`.

---

# Drishti 1.4.0 — release notes

*2026-09-30.* This release adds **workspaces**:
- several live views on one screen, where a pane can follow another's selection (pick a trade in the
  netting set, and the next pane opens it);
- three starters;
- saved per user on the server.

---

# Drishti 1.3.0 — release notes

*2026-09-30.* This release adds:
- **Mobile.** Drishti works in Safari on iPhone and Chrome on Android, and can be added to the home
  screen. Views stack, and F-keys become a swipeable row of buttons.
- **Competitive landscape** (`/about/competitive`). It compares categories rather than vendors, and is
  candid about where Drishti is weaker.

---

# Drishti 1.2.0 — release notes

*2026-09-30.* This release adds the **in-app help centre and About page**:
- tutorials, guides and every reference document, with search;
- `F1` for help on the current screen, and a **?** on every panel;
- `/about` showing the version, what is loaded, source health and the legal notices.

The help renders the repository's own docs, so the two cannot drift apart.

---

# Drishti 1.1.0 — release notes

*2026-09-30.* This release adds **user management**:
- admins create, edit, enable or disable, reset and delete users, and read the audit log;
- everyone can change their own password;
- PBKDF2 hashing, a password policy, lockout, and a guard that always keeps an enabled admin.

A development admin, `drishti-dev-admin` / `drishti-dev-admin123`, is created on an empty store.
Change its password, or set `DRISHTI_SEED_ADMIN=false`. Nobody is forced to change a password unless
that is configured. See `docs/admin/USER_MANAGEMENT.md`.

---

# Drishti 1.0.0 — release notes

*2026-09-30 · Ashutosh Sinha*

**Any data. Any domain. One grammar.** Drishti 1.0.0 is the first complete release: a live,
keyboard-driven terminal that renders any entity from any source through one declarative screen
grammar, **Rachana**. Each product family gets a versioned **Sutra** instead of a coded screen, and
inference fills whatever a Sutra leaves out.

## What is in 1.0.0

- **The four reference views.** The interest rate swap, FX swap, commodity future and netting-set
  mockups are reproduced end to end. Golden tests pin their values.
- **Rachana and Sutra.** A YAML grammar with twelve panel kinds and a compiled, side-effect-free
  expression language (Rachana-EL). Every problem is reported with line and column, the registry
  hot-reloads, and a broken edit keeps the last good version.
- **Inference.** Scored, explainable rules lay out any document with no Sutra (tabs, tables, curves,
  exposure areas, bars, ladders, key/value, links), and fill a Sutra's gaps.
- **The terminal.** Commands (`TRD IRS-48213 <GO>`), Bloomberg-style type-ahead, F-keys, F9 raw JSON,
  Alt+← back, breadcrumbs, and five themes including *Wall Street*.
- **Live.** SSE with leading-edge 50 ms frames and minimal patches. Slow clients get merged frames.
  Measured tick → frame p99 is about 11 ms, and 10,000 listeners share one topic.
- **Sources.** A Spring-free plugin SPI with `demo`, `file` (JSON/CSV), `rest` (HTTP/JSON) and
  `jdbc`, each with config-driven routing, deadlines and in-memory search.
- **Sutra Studio.** A highlighted editor, live previews in tens of milliseconds, problems by line,
  "start from inference", and saving for authors where enabled.
- **Security.**
  - Console sign-in (PBKDF2, signed session cookies) and HS256 tokens to the server (algorithm pinned, constant-time signature check).
  - Per-role entity kinds; denied links stay visible but disabled with the reason.
  - Raw JSON redaction; a strict CSP with no inline script or style; every asset vendored.
- **Operations.** Actuator and Prometheus metrics (view and live), a Grafana dashboard, Dockerfiles
  and compose, runbooks.

## Measured

Warm view p99 < 50 ms (a build gate); cold IRS view about 14 ms; Rachana-EL evaluation 10–50 ns;
cold inference about 13 µs; live p99 about 11 ms. See `docs/admin/PERFORMANCE.md`.

## Known limits (stated plainly)

- **Sign-in** is config-backed (users file). OIDC/SSO is not built; the token boundary is where it
  will plug in.
- **The `aero` live-cache plugin** is not built. The `rest` and `jdbc` plugins cover generic services
  and databases.
- **F8 Impact** is reserved but not implemented.
- **Docker images** are defined (`deploy/`), but were not built in the release environment because
  it had no Docker daemon access. Build them with the commands in `docs/admin/OPERATIONS.md`.
- **Studio saving** writes files locally. There is no review workflow yet; Sutras are expected to go
  through version control.

## Upgrading

This is the first release. Configuration keys are documented in `docs/admin/CONFIGURATION.md`.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
