<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti — Implementation Plan

*Revision 1.2 · 2026-10-11 · Author: Ashutosh Sinha · Companion to [ARCHITECTURE.md](ARCHITECTURE.md)*

**Where things stand.** Every wave in this plan is done. The current release is **1.20.0**
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
- `config/packs/finance/sutras/rates/irs-vanilla.v3.sutra.md`, `config/packs/finance/sutras/fx/fx-swap.v2.sutra.md`, `config/packs/finance/sutras/commodities/listed-future.v1.sutra.md`, `config/packs/finance/sutras/credit/netting-set.v1.sutra.md` (all `.sutra.yaml` since 1.11).
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
| B0 ✅ | banking packs split | `requires:`, `connectors:` and `routes:` in pack.yaml; data domains and packs many-to-many; five packs (banking-core, market-data, trading, market-risk, counterparty-risk) generated from the taxonomy (`tools/packgen/banking/`) |
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
| Scale and stores (1.13) | A million trades a day measured on eight stores, Calc (Python in the browser), the Pivot tab, twenty panel kinds, JDK 25 | [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md), [PYTHON_CALC.md](../guides/PYTHON_CALC.md), [PANEL_KINDS.md](../guides/PANEL_KINDS.md) |
| Build workbench (1.14) | Designs kept on the server, operations with undo, samples, check matrix, propose with evidence | [BUILD_WORKBENCH.md](BUILD_WORKBENCH.md), [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) |
| Java 21, About this page, collaboration (1.16) | Java 21 bytecode and runtime; About this page and the optional *Ask*; share with a note, the directory, the inbox, comment threads, email outbox, retention, legal holds, bridges and snapshots | [COLLABORATION.md](COLLABORATION.md), [adr/020](adr/020-collaboration-records-live-in-the-identity-database.md) |
| The command line (1.17) | `drishti.py` (sutra, pack, data, server, doctor, view, design, docs); `pack make` from JSON Lines to a deployable folder; Admin deploy, history and roll back; offline `pack bundle` | [CLI_GUIDE.md](../guides/CLI_GUIDE.md), [OPERATIONALISING.md](../guides/OPERATIONALISING.md) |
| Data loads (1.18) | `POST /packs/{pack}/loads`, expectations and late data, Admin → Data loads | [DATA_LOADS.md](../guides/DATA_LOADS.md) |
| Connectors as files (1.19) | One YAML file per connector in `config/connectors/`, live reload, Admin → Connectors (list, form, YAML, test, history), the connector API and CLI, pack templates, a detail page per connector and per pack | [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) |
| TLS and proxy (1.19, 1.20) | One set of `tls.*` settings for every connector that can use TLS (Kafka with every security option, ActiveMQ over `ssl://`, RabbitMQ over `amqps://` and the rest, as the [connector matrix](../connectors/TLS.md#connector-matrix) lists); an outbound HTTP proxy for connectors, AI providers and MCP servers | [TLS.md](../connectors/TLS.md), [PROXY.md](../connectors/PROXY.md) |
| Retry and no restart (1.19) | Anything that failed to load or lost its connection is tried again with backoff (`drishti.retry`), Retry now in Admin; no setting a pack contributes needs a restart | [OPERATIONS.md](../admin/OPERATIONS.md) |
| Query by form, controls, regex search (1.19) | `FORM <pack>`, the pack `form:` section, multi-select dropdowns, `controls:` on a screen (and the slider, view state in sent shares), safe regular expressions, Excel-style column filters, the zoomed panel | [USER_GUIDE.md](../guides/USER_GUIDE.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) |
| Drishti Elements (1.19) | `<drishti-view>`: live views in another web application's page, no iframe; host applications registered by an administrator (Admin → Embedding) | [ELEMENTS.md](ELEMENTS.md) |
| AI and MCP (1.19) | Pluggable LLM providers and an MCP client, off by default, behind one policy (masking, limits, audit) | [AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md) |
| Schema to pack (1.19) | Sutras and packs from JSON Schema; Build → New pack | [SCHEMA_TO_PACK.md](../guides/SCHEMA_TO_PACK.md) |
| Rūpaka, business intelligence (1.19 to unreleased) | Phase 1 shell, workspaces and catalogue (1.19); phase 2 datasets and the query engine, phase 3 reports, the designer and the Model view (1.20); phase 4 publishing and governance, phase 5 live tables, Perspective and boards (unreleased) | [RUPAKA.md](RUPAKA.md), [BI_GUIDE.md](../guides/BI_GUIDE.md), [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [BOARDS.md](../guides/BOARDS.md), [BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md) |
| Deep trades and real data (1.20) | Tabs hold panels (a trade, its legs, their cashflows and fixings on one screen); the `economics` and `politics-society` packs carry real public data (`tools/realdata`; the IMF data is for internal demos only) | [PANEL_KINDS.md](../guides/PANEL_KINDS.md), [REAL_DATA.md](../guides/REAL_DATA.md) |
| Connectors that compute (unreleased) | `news` and `yfinance` source plugins whose documents are computed when asked for; the `news-watch` and `market-watch` sample packs | [NEWS.md](../connectors/NEWS.md), [YFINANCE.md](../connectors/YFINANCE.md) |
| Forms and Reports in the top bar (unreleased) | Two top-bar entries beside BI, with a forms home | [USER_GUIDE.md](../guides/USER_GUIDE.md) |

## Known gaps

What is open today. None blocks normal use; each is a candidate for a future wave.

| Gap | Today | Where it shows |
|---|---|---|
| Several console processes | A tab's live channel lives in one console process, so a load balancer needs sticky sessions | [LIVE.md](LIVE.md) |
| Rūpaka, not built | Notifications and subscriptions (phase 7), Python measures (phase 6), time intelligence and window functions, a report over several datasets, per-device layouts; each is marked "not built" where it would appear | [RUPAKA.md](RUPAKA.md) |
| Server-side pricing | Not built: the QuantLib service is a proposal | [QUANT_SERVICE.md](QUANT_SERVICE.md) |
| A million trades a day on a real server | The numbers in the scale document are measured on one workstation at the sizes its safety rule allows and extrapolated beyond | [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md) |

## Roadmap

Everything planned up to 1.21.0 is built (see the changelog). Agreed with the product owner on 2026-10-11, in order,
each wave closing with a drill and a release:

| Wave | Release | What it delivers | Design |
|---|---|---|---|
| 2 | 1.22.0 | **Distribution (Rūpaka phase 7):** export (PDF, image, Excel/CSV); scheduled subscriptions rendered per recipient with masks, carrying control values and filters; alerts on measures and board notifications; `<drishti-report>` with Elements steps 11 to 15; per-report phone layouts | [RUPAKA.md](RUPAKA.md) |
| 3 | 1.23.0 | **BI gaps:** scheduled refresh, year-to-date and month-to-date, window functions, several fact tables in one query; map, funnel, histogram, box, bubble and entity visuals; relative-date slicers, true totals, reports over several datasets, per-device layouts; rename detection, BI in pack archives, import rollback, incremental aggregates, live-table history, a board designer | [RUPAKA.md](RUPAKA.md) section 20 |
| 4 | 1.24.0 | **Python (Rūpaka phase 6):** Transform, Measure and Visual interfaces; the sandboxed server runner and the browser target (Pyodide, on-demand compute on masked data the reader already has); the Calc client and PygWalker | [RUPAKA.md](RUPAKA.md) |
| 5 | 2.0.0 | **Analyse (Rūpaka phase 9):** the analyst workbench (DuckDB-Wasm Slice and SQL, Pyodide notebooks) over masked extracts; QLRisk behind a quant-engine interface once its API is stable | [RUPAKA.md](RUPAKA.md), [QUANT_SERVICE.md](QUANT_SERVICE.md) |
| 6 | between waves | **Platform loose ends:** chat bridges and plugin jars reload live; proxy gaps (HTTPS proxies, SOCKS, PAC, the server's own HTTP calls); AI streaming and stdio MCP | [AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md) |
| 7 | 2.1.0 | **AI-driven BI (stage 2):** a report from words, ask the data, explain this number, under the AI policy (off by default, masked, audited) | [RUPAKA.md](RUPAKA.md) section 20.1 |

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
