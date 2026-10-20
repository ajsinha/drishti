<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti — Implementation Plan

*Revision 1.2 · 2026-10-11 · Author: Ashutosh Sinha · Companion to [ARCHITECTURE.md](ARCHITECTURE.md)*

**Where things stand.** Every wave in this plan is done. The current release is **2.0.0**; **2.1.0** (the BI long tail, waves A to D) is built and about to ship
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
| 2.5.0 (unreleased) | wave N: Drishti\*net, several servers as one; `-Djava.io.tmpdir` on every JVM |

## What 2.0.0 contains

The three waves agreed on 2026-10-11 (BI gaps, Python and Analyse, AI-driven BI) shipped together with the work around them, and the documentation was consolidated.

| Area | What is in 2.0.0 | Read |
|---|---|---|
| BI essentials | scheduled refresh of datasets; year-to-date, month-to-date, prior period and running measures; map (world, country, region drill), funnel, histogram, box plot and bubble; true totals for measures that do not add up; dropdown slicers | [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [tutorial 10](../tutorials/10-first-bi-report.md) |
| Several datasets | composed datasets (`from:`, each source asked as the reader, row rules and masks before the join), derived datasets, a Python Transform, a report over several datasets, the Model view for them | [DATASETS.md](../guides/DATASETS.md), [tutorial 10](../tutorials/10-first-bi-report.md) step 11 |
| Python and Analyse | Python measures and visuals (sandboxed server runner, browser target); Analyse: slice, SQL, a Python notebook, promote, over one or several masked extracts | [PYTHON_BI.md](../guides/PYTHON_BI.md), [ANALYSE.md](../guides/ANALYSE.md), [tutorial 14](../tutorials/14-python-and-analyse.md) |
| AI in BI | a report from words, ask the data, explain this number, under the AI policy; seventeen vendor types of model provider with TLS and a Test connection button | [BI_AI.md](../guides/BI_AI.md), [AI_PROVIDERS.md](AI_PROVIDERS.md), [tutorial 15](../tutorials/15-ai-in-drishti.md) |
| Maps | the `map` panel kind (the 22nd): a country highlighted, regions, a click that opens the entity | [PANELS.md](../guides/PANELS.md#map) |
| Live data | coherent walks: generated live data that moves together (trades, netting sets, counterparties), live tables and reports that follow them with pause and resume | [BOARDS.md](../guides/BOARDS.md), [tutorial 12](../tutorials/12-live-bi-and-boards.md) |
| Console | one top bar on every page (signed in or out); Forms and Reports inside Views; the Live button merged with the stream status; Sajha's crimson, blue and green themes, the Cinzel wordmark and the footer slogan | [USER_GUIDE.md](../guides/USER_GUIDE.md#the-top-bar) |
| Connections | shared, sized database connection pools (`drishti.db.pool.*`) with their metrics in Admin → Health | [CONFIGURATION.md](../admin/CONFIGURATION.md), [OPERATIONS.md](../admin/OPERATIONS.md) |
| Documentation | seventeen tutorials with real screenshots; the help centre cut from 90 pages to about forty in six sections; tutorials teach, guides are reference; every old help address redirects | [docs/README.md](../README.md), [tutorials](../tutorials/README.md) |

## What 2.1.0 contains

Waves A to D close what 2.0.0 listed as "not built" in BI. The one consolidated list of what is still not built is [RUPAKA.md section 20.15](RUPAKA.md#2015-as-built-at-210-and-what-is-still-not-built).

| Wave | What is in 2.1.0 | Read |
|---|---|---|
| A, the long tail | window functions; several unrelated fact tables in one query; incremental live measures; refresh state that survives a restart and Refresh now; the relative-date slicer; tablet layouts; expected-value tests that stop a publish; the entity-view visual; the Region economics sample | [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md) |
| B, governance and boards | grain-correct measures; rename detection and type or meaning impact; import rollback; BI inside pack archives; lineage past the connector; live-table history; tile alert hysteresis; the board designer; one temp folder per process | [BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md), [BOARDS.md](../guides/BOARDS.md) |
| C, Python and Analyse | Vega-Lite visuals; Python on boards and in alerts; tests shipped with a Python item; Calc's `drishti_bi` and Graphic Walker; DuckDB-Wasm in Analyse; promote to a report visual; Perspective charts and the settings panel; measures that mix grains | [PYTHON_BI.md](../guides/PYTHON_BI.md), [PYTHON_CALC.md](../guides/PYTHON_CALC.md), [ANALYSE.md](../guides/ANALYSE.md) |
| D, AI, distribution, embedding | narrative summaries; the designer copilot and the dataset helper; charts in Excel; alert retries; webhook and text channels; `<drishti-about>` and `<drishti-search>`; React, Vue and Angular samples | [BI_AI.md](../guides/BI_AI.md), [DISTRIBUTION.md](../guides/DISTRIBUTION.md), [ELEMENTS.md](../guides/ELEMENTS.md) |
| Type-ahead index | several business days with date keys; disk segments; automatic and manual reload; `DRISHTI_INDEX_DIR` | [ARCHITECTURE.md](ARCHITECTURE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Documentation | the BI docs read as one set; tutorials 10 to 17 re-followed with new pictures | [docs/README.md](../README.md) |

## What 2.2.0 to 2.5.0 contain

The BI waves of [BI_DESIGN.md section 12](BI_DESIGN.md#122-the-three-waves), then Drishti\*net. Each line's detail is in the [changelog](../../CHANGELOG.md).

| Release | What is in it | Read |
|---|---|---|
| 2.2.0 (wave A) | datasets and data models rooted in packs; DEL v2 with a converter; Drishti SQL; one logical plan for the model query, SQL and Python; field properties, perspectives and the consumer envelope; stored items converted at start | [DATASETS.md](../guides/DATASETS.md), [EXPRESSIONS.md](../guides/EXPRESSIONS.md), [SQL.md](../guides/SQL.md) |
| 2.3.0 (wave B) | `calc`, `share_of` and filter context; calculation groups, field and numeric parameters; balances and time on the model's calendar; standing queries pushed from the server; a tier planner, with Python on the console; production-shaped trades (one UPPER_SNAKE schema from three source systems) and seven packs; committed test data, `lake demo`, an offline market snapshot; lake maintenance | [BI_GUIDE.md section 25](../guides/BI_GUIDE.md#25-live-answers-and-standing-queries-wave-b-230), [data/testdata/README.md](../../data/testdata/README.md), [LAKE_MAINTENANCE.md](../admin/LAKE_MAINTENANCE.md) |
| 2.4.0 (wave C) | BI Studio (Explorer, tabs, Problems and Query dock, the generated Format pane); report schema v2 (conditional formatting, themes, interactions, sync groups, tooltip pages, small multiples, the matrix); boards as a report layout; the Transform editor with a snippets library and profiles; the browser Python console; query by form kept per data version (form search p50 5.8 s to 0.21 s on 35.7 million trades); the CATO calendar; Elements 1.4.2 | [BI_GUIDE.md section 26](../guides/BI_GUIDE.md#26-bi-studio-authoring-as-one-product-wave-c-240), [BOARDS.md](../guides/BOARDS.md), [PYTHON_BI.md section 17](../guides/PYTHON_BI.md#17-data-prep-the-transform-editor-240), [PERFORMANCE.md](../admin/PERFORMANCE.md) |
| 2.5.0 (wave N, unreleased) | Drishti\*net: membership by gossip, capability digests, the cluster key and its rotation, forwarding and the newest pack served, scatter-gather search and grouped pages, live relay and BI routing, user state across nodes, the console's failover, Admin → Net, `config/net/`, `drishti.py net`; every JVM started with `-Djava.io.tmpdir` | [NET.md](../admin/NET.md), [DRISHTI_NET.md](DRISHTI_NET.md), [tutorial 19](../tutorials/19-a-cluster-of-three.md) |

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
- GitHub Actions `fast.yml`; `docs/architecture/adr/001..007`; `CHANGELOG.md`.

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
- `examples/packs/finance/sutras/rates/irs-vanilla.v3.sutra.md`, `examples/packs/finance/sutras/fx/fx-swap.v2.sutra.md`, `examples/packs/finance/sutras/commodities/listed-future.v1.sutra.md`, `examples/packs/finance/sutras/credit/netting-set.v1.sutra.md` (all `.sutra.yaml` since 1.11).
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
- Ops: Dockerfiles, `deploy/compose.yaml`, Grafana dashboard, `OPERATIONS.md`, `runbooks/`, `TROUBLESHOOTING.md`.
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

Releases 1.11 to 1.20 and what is unreleased; each is in [CHANGELOG.md](../../CHANGELOG.md) with details and tests.

| Theme | Delivered | Where to read |
|---|---|---|
| Sutras are YAML only (1.11) | One file per Sutra, `<name>.v<N>.sutra.yaml`, starting with `rachana: 1` (missing or unknown: `DRS-2009`); `description`, `notes` and a per-panel `description` for prose; Markdown Sutras no longer read (`DRS-2004`, converted by `tools/rachana/md_to_yaml.py`) | [adr/017](adr/017-sutras-are-yaml.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) |
| Build gates | Error Prone in every compile and Spotless at `verify` (ADR-007 amended) | [DEVELOPER_GUIDE.md](../guides/DEVELOPER_GUIDE.md) |
| Packs while running | Load and unload a pack without a restart (pack overlay, rollback); since 1.19 the packs folder is watched and a new or changed pack is checked and swapped in live; sample packs | [PACKS.md](../guides/PACKS.md) |
| API tokens and clients | Personal API tokens (read-only, then write scopes `design:write`, `design:approve`, `packs:admin`, `loads:write`, `bi:*`, `ai:*`), searches as CSV, a Python client, Excel through Power Query | [CLIENTS.md](../guides/CLIENTS.md), [API_GUIDE.md](../guides/API_GUIDE.md#personal-api-tokens) |
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
| Rūpaka, business intelligence (1.19 to 2.1.0) | Phase 1 shell, workspaces and catalogue (1.19); phase 2 datasets and the query engine, phase 3 reports, the designer and the Model view (1.20); phase 4 publishing and governance, phase 5 live tables, Perspective and boards (1.21); distribution (1.22); the Python, Analyse, AI and multi-dataset waves (2.0); the long tail of waves A to D (2.1) | [RUPAKA.md](RUPAKA.md), [BI_GUIDE.md](../guides/BI_GUIDE.md), [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [BOARDS.md](../guides/BOARDS.md), [BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md) |
| Deep trades and real data (1.20) | Tabs hold panels (a trade, its legs, their cashflows and fixings on one screen); the `world-economy` packs carry real public data (`tools/realdata`; the IMF data is for internal demos only) | [PANELS.md](../guides/PANELS.md), [REAL_DATA.md](../guides/REAL_DATA.md) |
| Connectors that compute (unreleased) | `news` and `yfinance` source plugins whose documents are computed when asked for; the `news-and-markets` sample packs | [NEWS.md](../connectors/NEWS.md), [YFINANCE.md](../connectors/YFINANCE.md) |
| Forms and Reports in the top bar (unreleased) | Two top-bar entries beside BI, with a forms home | [USER_GUIDE.md](../guides/USER_GUIDE.md) |

## Known gaps

What is open today. None blocks normal use; each is a candidate for a future wave.

| Gap | Today | Where it shows |
|---|---|---|
| Several console processes | A tab's live channel lives in one console process: the workers of one console pass a channel's calls to the worker that holds it (2.4.0), but several consoles behind a load balancer need sticky sessions | [LIVE.md](LIVE.md) |
| Rūpaka, not built | The Python Source interface, a pool of pre-forked Python children, field parameters in report wells (2.6.0), escalation of alerts, a Data tab and Notes in Analyse, a large-screen layout; the full list is in RUPAKA.md section 20.15 | [RUPAKA.md](RUPAKA.md) |
| Server-side pricing | Not built: the QuantLib service is a proposal | [QUANT_SERVICE.md](QUANT_SERVICE.md) |
| A million trades a day on a real server | The numbers in the scale document are measured on one workstation at the sizes its safety rule allows and extrapolated beyond | [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md) |

## Roadmap

**Agreed after 2.1.0 (2026-10-12):** the Rūpaka model architecture and waves W1–W16 for 2.2.0 to 3.0 — see
[BI_MODEL_ARCHITECTURE.md](BI_MODEL_ARCHITECTURE.md); the gap analysis behind it is [BI_PARITY_PLAN.md](BI_PARITY_PLAN.md).

**Next releases (re-ordered 2026-10-18 and twice on 2026-10-19):** 2.5.0, Drishti*net, is released; 2.6.0 is wave D, sharing and integration (it starts with no known bug; paginated reports, send and push, feeds, embed tokens, the Sajha MCP connector, field parameters in report wells); 2.7.0 is wave W, write-back ([WRITE_BACK.md](WRITE_BACK.md)); 2.8.0 is wave I, AI, with GenUI ([GENUI.md](GENUI.md)) and AI management ([AI_MANAGEMENT.md](AI_MANAGEMENT.md)); the real-life QA follows 2.8.0; 2.9.0 is wave E (QA findings and production readiness); then 3.0.

Everything planned up to 2.4.0 is built (see the changelog, [What 2.0.0 contains](#what-200-contains), [What 2.1.0 contains](#what-210-contains) and [What 2.2.0 to 2.5.0 contain](#what-220-to-250-contain)); 2.5.0 (Drishti\*net) is built and in its release testing. After it: 2.6.0, 2.7.0, the real-life QA, 2.8.0, then 3.0.

The long tail of BI gaps that the product owner trimmed on 2026-10-11 (window functions, several fact tables in one query, relative-date slicers, per-device layouts, rename detection, BI in pack archives, import rollback, incremental aggregates, live-table history, a board designer) was built after all, in 2.1.0. The platform loose ends (live bridges and plugin jars, proxy gaps, AI streaming and stdio MCP) remain candidates, not plans.

Later candidates: server-side pricing (the QuantLib service), Windows proven on a real machine, a real deployment (single
sign-on, HTTPS, the images together), scale on a real server, load with many users, several console processes without
sticky sessions, Iceberg without Hadoop, delete handling in the Redis connector.

## Risks

| Risk | Mitigation |
|---|---|
| Inference produces noisy layouts | Scored rules with density limits; golden tests; Studio "promote" loop |
| EL grows into a scripting language | Closed grammar, no loops/assignment, reviewed via ADR |
| Live fan-out cost at scale | Coalescing, dependency index, bounded queues, perf gates in W6 |
| Mockup fidelity drifts | Screenshot comparison tests from W5 onwards |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
