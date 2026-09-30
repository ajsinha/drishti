<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti — Implementation Plan

*Revision 1.0 · 2026-09-30 · Author: Ashutosh Sinha · Companion to [ARCHITECTURE.md](ARCHITECTURE.md)*

## Working agreement

- All work happens on **`develop`**. Each wave closes with a green build, updated docs,
  and a merge to **`main`** ("drill it": push `develop`, merge into `main`, push `main`).
- Sole author: Ashutosh Sinha. Commit messages explain *why*; no trailers.
- Every file carries the copyright/proprietary header; build fails otherwise.
- No source file over 1500 lines (UX templates excepted); build fails otherwise.
- Each wave updates `docs/` (guides, ADRs, CHANGELOG) as it goes, not afterwards.

## Wave map

Ten waves (plus W11, user management, after 1.0.0). Each holds a small set of closely related items and ends with a demo-able exit gate.

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
- Maven reactor on `spring-boot-starter-parent` (it aligns versions, so there is no BOM module), `mvnw`, Java 21 enforcer, `.editorconfig`.
- `config/license-header.txt` and `tools/license_headers.py --fix`; Spotless and Error Prone deferred (ADR-007).
- Empty modules with `package-info.java`: `api, common, sutra, inference, graph, engine, server, testkit, it, benchmarks`.
- `drishti-it`: `LicenseHeaderTest`, `SourceFileSizeTest` (1500), `ArchitectureRulesTest` (one-way module deps, Spring-free `drishti-api`, no `Serializable`, no field injection).
- GitHub Actions `fast.yml`; `docs/adr/001..007`; `CHANGELOG.md`.

## W2 — Console shell & landing
- `console/run_drishti_web.py`, `console/config/application.yaml`, properties configurator (YAML → local → env → CLI).
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
- YAML parser with source positions; `sutra.schema.json`; validator emitting `DRS-2nnn` with line/column.
- `SutraRegistry`: versions, lookup by `name@version`, `WatchService` hot reload.
- Docs: `docs/RACHANA_REFERENCE.md` (keys, panel kinds, versioning).
- Tests: parse round-trip, schema negatives, reload races.

## W5 — Rachana-EL & reference Sutras
- Rachana-EL: lexer → parser → AST → compiled closures (paths, `[?x]` filters, ternary, `link()`, `size()`, arithmetic, concat); EBNF in the reference doc.
- `Formats` (bundled `formats.yaml`, site override file) and `Tones`; `SutraMatcher` (the Sutra `match` block is the classifier).
- `packs/finance/sutras/rates/irs-vanilla.v3.sutra.md`, `packs/finance/sutras/fx/fx-swap.v2.sutra.md`, `packs/finance/sutras/commodities/listed-future.v1.sutra.md`, `packs/finance/sutras/credit/netting-set.v1.sutra.md`.
- Tests: jqwik property tests for EL; golden `Layout` JSON per reference Sutra.

## W6 — Inference engine
- `drishti-inference`: `ShapeTree`, `SemanticHints` (`config/inference/semantics.yaml`), the rules in ARCHITECTURE §6.
- `CandidateScorer`, `AreaPacker` (strip ≤ 8, right column ≤ 4), `LayoutMerger` (Sutra wins; `infer` holes filled).
- Explanations (rule + score) recorded per inferred panel.
- Tests: an unknown product renders; each reference entity without its Sutra stays usable (golden).
- Docs: `docs/INFERENCE.md`.

## W7 — Engine pipeline & entity graph
- `CommandParser` (`config/mnemonics.yaml`); `SuggestionService` + per-plugin `HitIndex` for type-ahead (mnemonics, recents, parallel `SourcePlugin.search` under a 30 ms budget).
- `ViewPipeline` (ARCHITECTURE §4), `LayoutResolver` with Caffeine caches, parallel `Binder`, `ViewModel` records and a streaming serializer.
- `drishti-graph`: `ReferenceCatalog` (`config/references.yaml`), `LinkResolver` with a deadline and pending placeholders, badges, reverse lookups.
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
- Security: console sign-in (users file, PBKDF2, signed cookies), HS256 tokens to the server, per-role kinds (`drishti.security.roles`), links disabled with the reason, F9 redaction, CSP. *(OIDC deferred.)*
- Plugins `rest` and `jdbc`. *(The `aero` plugin is deferred.)*
- Ops: Dockerfiles, `deploy/compose.yaml`, Grafana dashboard, `OPERATIONS.md`, `runbooks/`, `TROUBLESHOOTING.md`.
- Release: `RELEASE_NOTES.md`, tag `v1.0.0`, merge to `main`.

---

## W11 — User management (added after 1.0.0)
- `drishti-identity`: `User`, `UserStore` / `FileUserStore` (atomic, mode 600), `PasswordHasher` (PBKDF2), `UserService` (lockout, policy, last-admin guard), `AuditLog`.
- Seed `drishti-dev-admin` / `drishti-dev-admin123` (admin) when the store is empty; warn until it is changed.
- Forced password change is off unless configured.
- Server: `/api/v1/auth/{login,me,password}`, `/api/v1/admin/{users,audit,roles,status}`; an `admin` role.
- Console: sign-in through the server, `/account`, `/admin/users`, `/admin/audit`. The users file is retired (ADR-009).

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
| W20 ✅ | Single sign-on | OIDC code flow with PKCE, state and nonce in the console; `POST /auth/oidc` verifies the ID token on the server (JWKS discovery and rate-limited refresh, RSA/PSS/ECDSA via the JDK, never `none`/HMAC; issuer, audience, azp, exp, nbf, iat, nonce); group-to-role map; provisioning on first sign-in; local disables and the last admin protected; audited (ADR-014). The Aerospike plugin was delivered in R4 |
| W21 | Personal settings | command history, aliases, per-user theme and default workspace |

### Domain-pack roadmap (agreed 2026-09-30)

| Wave | Pack | Scope |
|---|---|---|
| W13c | Per-user packs | admins assign packs to users; users choose active packs; kinds owned by one pack; enforced server-side |
| R1 ✅ | **risk**: taxonomy | 125 products in ten asset classes, 21 market-data kinds, 24 risk and reference kinds, one source of truth (`tools/packgen/banking/taxonomy.py`) with uniqueness checks |
| R2 ✅ | risk: Sutras | `make_sutras.py` writes 170 Markdown Sutras (125 products, 45 kinds) that double as the data dictionary; `trade_shape.py` is the trade-document contract; every pack's Sutras load with zero problems (`PackSutrasTest`); drill fails if generated files drift |
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

Waves 16–21 resume after the risk pack.

Every wave also fixes documentation rot: the README status table, "what works", this plan, the changelog, and any guide the wave touches.

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
