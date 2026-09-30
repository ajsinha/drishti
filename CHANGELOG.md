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
# Changelog

## Unreleased
- **Risk pack, R2:** `packs/risk/tools/make_sutras.py` writes 170 Markdown Sutras from the taxonomy: one per product (terms, legs, schedule, market-data chart, risk by bucket, P&L, links) and one per market-data, risk and reference kind. Each explains its fields, market data and risk measures, so the Sutras are also the data dictionary. `trade_shape.py` fixes the trade-document contract shared with the data generator. New `PackSutrasTest` loads every pack's Sutras with zero problems, and `tools/drill.sh` refuses to merge if generated pack files are out of date.
- **Sutras are Markdown documents** (ADR-011). The standard file is `<name>.v<N>.sutra.md`: prose for people and AI assistants, with the layout in one fenced `sutra` block. Problems keep the Markdown file's line numbers, and a missing, duplicated or unclosed block is `DRS-2004`. Plain `*.yaml` Sutras still load. The finance and logistics Sutras are converted, and `tools/sutra_to_md.py` converts others.
- **Studio is a Markdown editor:** a toolbar with shortcuts, Markdown highlighting with Rachana inside the block, **Insert…** snippets (a `sutra` block, a strip field, all twelve panel kinds), a **Jump to…** outline, soft wrap and a rendered **Document** tab (rendered without raw HTML and with only safe links). Inference and Save produce Markdown Sutras.
- **Themes:** *crimson* and *crimson dark*, Maya's Harvard crimson and indigo palette, bring the total to seven themes. Contrast is checked like the others.
- Fix: a `gauge` panel whose value or maximum is not a number no longer breaks the page.
- **Studio, sample JSON:** a *Sample JSON* tab shows the entity's document (**Load entity JSON**). You can paste any JSON object and tick **Preview against this JSON** to render it with the unsaved Sutra, with no source needed. **Start from inference** works on pasted JSON. Server: `POST /api/v1/studio/preview` accepts `document`; `POST /api/v1/studio/inferred` infers from a document.
- **Risk pack, R1:** the taxonomy, a single machine-readable source (`packs/risk/tools/`) of 125 products in ten asset classes, 21 market-data kinds and 24 risk and reference kinds. Uniqueness of codes, kinds, mnemonics, ID prefixes and reference fields is checked (`python3 packs/risk/tools/taxonomy.py`).

## 1.8.0 — Wave 13c: packs per user (2026-09-30)
- **Admins assign packs to users** (user dialog → *Packs*). Users with several packs choose which to see from a **pack switcher** in the top bar, and the choice is saved to their account (`GET/PUT /api/v1/me/packs`).
- Each pack declares the `kinds` it owns, and a kind belongs to exactly one installed pack.
- **Enforcement is on the server:** kinds of packs that are not active for the user cannot be opened, and their mnemonics, suggestions, examples, starters, guides and alert suggestions disappear with them.
- `drishti.packs.default-for-users` sets what new users get.
- The plan records the pack roadmap: risk (R1–R5), then liquidity, climate, operational and non-financial risk, retail banking, genomics and biology, politics and society, and economics.

## 1.7.0 — Wave 15: F8 Impact (2026-09-30)
- **F8 Impact** (`/impact/{kind}/{id}`, `GET /api/v1/impact/{kind}/{id}`) answers "what depends on this?":
  - level 1: every entity that references it (reverse lookups across all known kinds, in parallel);
  - level 2: what those roll into, following fields each pack configures (finance: `nettingSet`, `creditLimit`; logistics: `vessel`, `destination`);
  - each group shows its count and the summed measure at stake (trade MTM, netting-set net MTM, shipment declared value);
  - kinds the caller may not open are counted, never shown.
  A SOFR move reaches its trades and their netting sets in about 1–6 ms.
- The demo source's reverse lookup also matches arrays of identifiers (for example `discountCurves`).
- Docs: an *F8 · Impact* guide in help; user guide, API guide, architecture and packs guide updated; README no longer lists F8 as unbuilt.

## 1.6.0 — Wave 14: monitors and alerts (2026-09-30)
- **Monitors** (`/m`): live watchlists of up to 50 entities of any kinds, each row showing its own strip. One multiplexed SSE stream per page (`/api/v1/me/monitors/{name}/stream`, a `row` event per changed entity). Starters come from packs (finance: *Credit watch*; logistics: *Fleet watch*). Saved per user.
- **Alerts** (`/alerts`):
  - per-user rules in Rachana-EL (`$.utilisation > 0.8`), compiled on save;
  - evaluated by the server on every tick of the watched entity, with no browser needed;
  - edge-triggered (fire on false → true, re-arm on false), so a rule already true when saved fires at once;
  - severities `info`/`warn`/`critical` and message templates.
  Packs suggest rules per kind. A bell with a count and toasts appear on every signed-in page, with optional browser notifications, and each view has an **Alert** shortcut.
- The most recent 200 alerts per user are kept in memory (a restart clears the history; rules persist).
- Docs: a *Monitors and alerts* guide in help; API guide, architecture, live, user guide and packs guide updated.

## 1.5.0 — Wave 13b: domain packs (2026-09-30)
- **Domain packs.** The core is now industry-neutral, and everything domain-specific lives in `packs/<name>/`:
  - Sutras, mnemonics, identifier patterns, reference fields, link badges, roles;
  - semantic hints and formats;
  - starter workspaces, help guides and sample data.
  Enable packs with `DRISHTI_PACKS=finance,logistics`. They load in order at the lowest precedence, so site configuration wins, and a clash between packs stops start-up with both names.
- New module `drishti-packs` (an `EnvironmentPostProcessor`; no core module depends on it). The server gains `GET /api/v1/packs`; About lists the active packs.
- **Finance pack:** everything that was finance-specific moved out of the core: the four Sutras, 13 mnemonics, reference patterns and fields, badges, `trader`/`risk` roles, finance semantic hints (MTM, DV01, notional, pips…), finance formats (`pct4`, `rate5`, `pips1`, `df4`, `bp1`), the mockup samples, starters and tutorials.
- **Logistics pack** (new), which proves the core is neutral:
  - shipments, reefer containers, vessels and ports;
  - a shipment Sutra (route, milestones, reefer temperature from the linked container);
  - logistics vocabulary (weights, temperatures, delays, knots, TEU) and formats (`temp1`, `hours0`, `knots1`);
  - an `ops` role, live samples, and a *Shipment tracker* workspace.
- The demo source reads pack sample directories and can tick any pack's samples through `_meta.walk`; no code is needed.
- Core semantic hints and formats are now neutral; the console's placeholders and examples are pack-driven.
- Docs: `PACKS.md`, ADR-010; architecture §8a; configuration, plugin guide, user guide, Rachana reference and deploy files updated.

## 1.4.0 — Wave 13: workspaces (2026-09-30)
- **Workspaces** (`/w`): 2–4 live views on one screen, in layouts of two or three columns, two by two, or one large with two stacked.
  - Each pane is a same-origin embedded view with its own live stream and keys.
  - A pane can **follow** another: a link clicked in the followed pane opens there (postMessage, origin-checked). `Alt+1…4` moves between panes.
  - Starters (*Credit desk*, *Rates*, *Cross-asset*) are config (`console/config/workspaces.yaml`).
- **Saved per user** on the server: `GET/PUT/DELETE /api/v1/me/workspaces[/{name}]`, validated (known layout, 1–4 panes, entitled entities, no follow cycles). They are stored in a new per-user `PreferenceStore` (atomic JSON, 64 KB per document, 50 per namespace), which is removed with the user.
- Views have an embed mode (`?embed=1`: no chrome, no breadcrumbs). The CSP allows same-origin frames only (`frame-ancestors 'self'`, `X-Frame-Options: SAMEORIGIN`).
- Docs: a Workspaces guide in help; updated user guide, API guide and architecture.

## 1.3.0 — Wave 12b: competitive landscape and mobile (2026-09-30)
- **Competitive landscape** (`/about/competitive`), in Maya's form. It compares categories, not vendors (market data terminals, trading and risk platform screens, low-code tools, BI), with a Yes/Partial/No matrix over twelve capabilities and, for each, the problem and how Drishti does it. It says plainly where Drishti is weaker (no market data content or analytics, no ecosystem or support). The rows are config (`console/config/competitive.yaml`).
- **Mobile.** Every page works on iPhone (~390 px) and Android (~412 px): stacked layouts; a swipeable F-key bar of tap targets; 16 px inputs (no iOS zoom on focus); safe-area insets; tables that scroll inside their panel; Studio hidden on phones. A web-app manifest and touch icon allow *Add to Home Screen*, which opens at the terminal. Checked with CDP mobile emulation: no page scrolls sideways at 390 px.

## 1.2.0 — Wave 12: help centre and About (2026-09-30)
- **Help centre** (`/help`), in Maya's help language:
  - a searchable catalogue of 25 guides in six categories;
  - three tutorials (your first view, your first Sutra, Sutra Studio) and a panel-kind guide;
  - captioned, copyable examples; tip/warning/note boxes; a table of contents; full-text search.
- The help centre renders the **repository's own docs** (Rachana reference, inference, API, configuration, operations, runbooks, ADRs, plan, release notes, changelog, notices), and links between them open in-app. There is one source of truth, so the help cannot rot separately from the docs.
- **Contextual help:** `F1` opens help for the current screen; every panel header has a **?** linking to its kind.
- **About** (`/about`): server and console versions, build time, Java, uptime, security mode, loaded Sutras, source health, licence and notices. The server gains `GET /api/v1/about` with Spring Boot build info.
- The console image ships the docs. A sample feed file (`data/feeds/fixing/SOFR-HISTORY.csv`) means the `file` source starts UP (`FIX SOFR-HISTORY <GO>`).
- **Doc rot fixed:** stale "arrives in Wave N" promises removed from the API guide, plugin guide, Rachana reference and configuration.

## 1.1.0 — Wave 11: user management (2026-09-30)
- New module `drishti-identity`:
  - users with profiles, roles and enabled flags;
  - PBKDF2-HMAC-SHA256 passwords with a policy; lockout after 5 failures;
  - atomic, owner-only JSON storage; an append-only audit log;
  - a guard so there is always an enabled admin.
- A development admin **`drishti-dev-admin` / `drishti-dev-admin123`** (role `admin`) is seeded on an empty store. The console warns until its password is changed; `DRISHTI_SEED_ADMIN=false` turns seeding off.
- Forced password changes are **off unless configured** (`force-password-change-on-create`, `force-password-change-on-reset`).
- Server: `/api/v1/auth/login` (console service token only), `/auth/me`, `/auth/password`, and `/api/v1/admin/**` (users CRUD, enable/disable, reset, audit, roles, status); an `admin` role.
- Console: sign-in through the server; `/account` (profile, change password); `/admin/users` (search, create, edit, enable/disable, reset, delete); `/admin/audit`. The users file and hashing tool are retired (ADR-009).
- Docs: `USER_MANAGEMENT.md`, ADR-009; updated operations, runbook, configuration.

## 1.0.0 — Wave 10: Studio, security, operations, release (2026-09-30)
- **Sutra Studio** (`/studio`):
  - a Rachana editor (vendored CodeMirror with a Drishti YAML mode);
  - Ctrl+Enter live preview of unsaved Sutras against any entity (about 45 ms), with problems listed by line;
  - "start from inference" (`SutraWriter` turns an inferred layout into an editable Sutra);
  - saving for authors when `drishti.rachana.studio-save` is on.
- **Security:**
  - console sign-in (PBKDF2 users file, signed expiring cookies, safe `next` redirects);
  - HS256 tokens to the server (algorithm pinned, constant-time signature, expiry);
  - per-role kinds, with denied links shown disabled with the reason;
  - suggestion filtering and raw JSON redaction.
- **Plugins:** `rest` (HTTP/JSON with headers and a generation from ETag) and `jdbc` (per-kind query, pooled connections, JSON column support).
- **Operations:** live Micrometer gauges; Grafana dashboard; Dockerfiles and compose; `OPERATIONS.md`, runbooks, `TROUBLESHOOTING.md`; `RELEASE_NOTES.md`.
- **Version:** 1.0.0.

## Wave 9: live updates
- The `demo` source ticks live entities with consistent random walks while someone watches (MTM, DV01, curves, spot, exposure; futures keep MTM, settlement and VM consistent).
- Engine:
  - `TopicHub`: one source subscription per entity, latest-wins, leading-edge 50 ms frames, single writer.
  - `ViewStream`: rebuild with the cached layout, then diff; rebuilds never overlap.
  - `PatchDiffer`; `LiveMetrics` (HdrHistogram rolling p50/p99).
- Server:
  - SSE `/api/v1/views/{kind}/{id}/stream` (a `view` event, then `frame` events), with a per-client latest-wins `FrameMailbox` that merges frames for slow clients, and heartbeats.
  - `/api/v1/health/live`.
- Console: `/api/stream` relay that renders changed panels with the same Jinja macros; `live.js` patches cells, panels and charts in place, flashes changes and shows `Live, p99 N ms`.
- Measured: p50 2.4 ms, p99 11 ms from tick to frame; 10,000 listeners on one topic all see the latest tick. Verified in real Chrome over the DevTools protocol.
- Docs: `LIVE.md`; `PERFORMANCE.md` updated.

## Wave 8: REST API and console entity views
- Server REST API under `/api/v1`: command, suggest, views, raw entities, sources and Sutras. RFC 7807 errors with `DRS` codes, springdoc at `/api/docs`, and a Micrometer `drishti.view` timer.
- Console terminal:
  - `/t` home;
  - `/go` dispatch;
  - `/v/{kind}/{id}` entity views that reproduce the four mockups: title line, strip, tabs and side-by-side legs, tables with totals, ladders, ECharts curves and exposure areas, bars, linked entities with badges, provenance.
- Bloomberg-style type-ahead dropdown: debounced, stale requests aborted, ARIA combobox. ↑/↓ move, Tab completes, Enter opens, Esc closes, `/` focuses.
- F-keys from the layout. F9 opens the raw JSON drawer, Alt+← goes back, and breadcrumbs follow the views opened in the browser tab.
- The CSP now also forbids inline `style` attributes (bar widths come from `data-w`), enforced by a test.
- Docs: `API_GUIDE.md`, `USER_GUIDE.md` (with the keyboard), `CONFIGURATION.md`.

## naming: Rachana and Sutra (ADR-008)
- **Rachana** (रचना) is the declarative screen grammar. **Sutra** is one layout written in it.
- The module `drishti-sutra` is now `drishti-rachana` (packages `com.ash.drishti.rachana`). The configuration prefix is `drishti.rachana.*`, the expression language is **Rachana-EL**, and the reference is `RACHANA_REFERENCE.md`.
- Sutra files, `Sutra`, `SutraRegistry` and the view label `Sutra irs-vanilla v3 + inference` keep their names.

## Wave 7: view pipeline, entity graph, type-ahead
- `drishti-graph`: config-driven `ReferenceCatalog` (identifier patterns and reference fields) and `BadgeRenderer` (`EE 4.1m`, `threshold 0`, `live`).
- `drishti-engine`:
  - `ViewPipeline` (fetch → match → fingerprint → cached layout → parallel link fetch within 40 ms → parallel panel binding);
  - `Binder` for all twelve panel kinds, with one error per panel rather than per view;
  - the `ViewModel` JSON contract, with pre-formatted, toned cells and document paths for live patches.
- Commands: config-driven `Mnemonics`, `CommandParser` (`TRD IRS-48213 <GO>`, a bare id, any case), `SuggestionService` (mnemonics, per-user recents, parallel plugin search within 30 ms) and `RecentEntities`.
- Golden ViewModel tests for all four mockups: strip, legs, cashflows and totals, curve with mark, link badges, ladder highlight, "10 more trades", exposure limit.
- Latency gate: warm p99 under 50 ms.
- `drishti-benchmarks` (JMH). Docs: `PERFORMANCE.md` with measured numbers.

## Wave 6: inference engine
- `drishti-inference`: config-driven `Semantics` (roles from field names and value classes, memoised), `ColumnInference`, six rules (legs → tabs, term structure → line/area, distribution → hbar, time series → ladder, arrays → table, nested objects → kv), and a packer with density limits.
- `LayoutMerger`: Sutra ⊕ inference, where the Sutra always wins. It produces an `EffectiveLayout` with the provenance label (`Sutra irs-vanilla v3 + inference` / `inference only`) and a per-panel explanation.
- Tests: an unknown product (equity option) renders; each reference entity stays usable without its Sutra; the gaps in the listed-future Sutra are filled.
- README rewritten to show the real state of the project: a status table per wave, what works, how to run it. It is now updated with every wave.
- Docs: `INFERENCE.md`.

## Wave 5: Rachana-EL, formats & matching
- Rachana-EL: lexer, recursive-descent parser (the EBNF is in `RACHANA_REFERENCE.md`), and immutable closure trees with cached compilation. Paths, filters, ternary, arithmetic, twelve pure functions, `link(...)`, and `${...}` templates.
- Every expression reports the document paths it reads, ready for dependency-driven live updates.
- Every expression in a Sutra is compiled at load; errors are reported against the file (`DRS-2101`).
- `Formats` (bundled `formats.yaml` plus a site override; true minus sign, grouping, percent, compact `4.1m`, dates) and `Tones`.
- `SutraMatcher`: highest-priority Sutra whose `where` holds; no match means inference only.
- Golden test: the four reference Sutras reproduce the mockups' header strips exactly. jqwik property tests check the arithmetic and comparisons.

## Wave 4: Sutra grammar
- `drishti-rachana`: immutable model (`Sutra`, `Match`, `Title`, `StripItem`, `Panel`, `Column`, twelve `PanelKind`s with per-kind required and optional options).
- A position-aware YAML reader and a validator that reports **every** problem with its line and column (`DRS-20xx`).
- `SutraRegistry`: `name@version` lookup, per-kind matching by priority, lock-free snapshot reads, `WatchService` hot reload with debounce, last good version kept on error, change listeners.
- `sutra.schema.json` for editors. The four reference Sutras for the mockups.
- Docs: `RACHANA_REFERENCE.md`.

## Wave 3: data model & sources
- `drishti-api`: `DataNode` (immutable tree; navigation never throws), `EntityRef`, `EntityDocument`, `Provenance`, and the `SourcePlugin` SPI with `search` (for the type-ahead) and `reverse`. `HitIndex` provides in-memory search.
- `drishti-common`: `DRS-nnnn` error codes, a streaming `JsonCodec`, and `ShapeFingerprinter` (canonical shape, FNV-1a 64; values and array lengths do not change it).
- `drishti-engine`: `PluginDiscovery` (class path plus isolated plugin jars), `SourceRegistry` (parallel start on virtual threads, failures isolated), and `SourceRouter` (config-driven routes, deadlines, partial `fetchAll`, parallel `search` under a time budget).
- Plugins: `demo` (36 reference entities) and `file` (JSON/CSV feed directory).
- The build is pinned to OpenJDK 21 by the enforcer.
- Docs: `PLUGIN_GUIDE.md`; ARCHITECTURE §7a (command suggestions).

## Wave 2: console shell & landing
- FastAPI + Jinja2 console (`console/`) with a layered config (YAML → local → env → CLI), a strict CSP and security headers.
- Five themes, tokens only in `tokens.css`: terminal (default), parchment, **wallstreet** (Bloomberg Terminal colour scheme), blue, green.
- Landing page: a canvas hero (JSON → `{◉}` → assembled, ticking panels) with replay, reduced motion and pause-when-hidden; stats strip; Sutra example; animated pipeline; capabilities; the four reference views.
- Every front-end asset is vendored (Bootstrap, Bootstrap Icons, ECharts). Tests fail on any external URL, inline script or inline handler.
- WCAG contrast is computed for every theme in tests.

## Wave 1: build foundation
- Maven reactor on `spring-boot-starter-parent` 3.5.16, Java 21, wrapper included.
- Modules: api, common, sutra, inference, graph, engine, two plugins (demo, file), server, testkit, it.
- The `DrishtiApplication` Spring Boot skeleton runs on virtual threads, with actuator and Prometheus.
- Repository gates: licence headers, 1500-line limit, architecture rules (ArchUnit).
- `tools/license_headers.py` to check and insert headers; GitHub Actions `fast.yml`.
- ADR-001 to ADR-007.
