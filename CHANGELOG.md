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
- **Pack inheritance (ADR-015).** `extends: [parent, …]` (and the older `requires:`) makes a pack inherit everything its parents bring.
  - **Precedence:** C3 linearisation. The child wins over its parents, the rightmost parent over those to its left, and a shared ancestor counts once.
  - **What can be overridden:** mnemonics, link fields, badges, roles, routes, connectors, Sutras (same `name@version`), labels and formats. Overrides apply globally and are logged and listed under Admin → Health.
  - **Still strict:** kinds are never overridden, and unrelated packs still may not clash.
  - **Packs:** `market-risk` and `counterparty-risk` now extend `[market-data, trading]`, and the generators write `extends:`.
- **Admin → Health.** Every connector's status (with the reason while it reconnects), reads, errors, p50/p99 latency, last error and caches. Every pack's Sutras, Sutra problems and connectors. Live streaming and server figures. An overall OK / DEGRADED / DOWN. Refreshes every 5 s, and `GET /api/v1/admin/health` serves the same for monitoring. Read statistics are recorded lock-free on the router's read path.
- **Every connector reconnects by itself.**
  - **JDBC:** pools lazy slots, so it starts with its database down, reconnects broken connections, and lists table kinds in the background until the database answers. Health reports the last error.
  - **Kafka:** a supervisor recreates the consumer after a fatal error or a broker that was down at start, with backoff from 1 s to 30 s, and resumes from the last offset applied instead of replaying.
  - **Aerospike:** the client keeps tending the cluster in the background.
  - **Others:** feeds, REST, files and Delta connect afresh on every call.
  - **Tests:** reconnect tests kill and restart a real PostgreSQL in Docker, and bring a Kafka broker up after the connector.
- **Rachana reference: a complete annotated example.** A swap Sutra with a comment on every line covers matching, templates, the strip, thirteen panels (kv, tabs, table with total and "more", ladders with filters and highlights, status, markdown, provenance, lines from the document and from a linked curve, bars, a gauge, links) and function keys. It comes with the document fragment it reads and a table of what each part does. `RachanaReferenceExampleTest` previews the block exactly as written against `T-10001`.
- **Docker verified.** The PostgreSQL and Aerospike contract tests pass against real containers.
  - **Aerospike 8.1:** needs 15,000 file descriptors (set in the test and in compose), and its image is pinned rather than `latest`.
  - **Aerospike connector:** now starts even when the cluster is not ready yet, and reconnects in the background.
  - **Compose:** host ports are configurable (`DRISHTI_PG_PORT`, `DRISHTI_KAFKA_PORT`, `DRISHTI_AEROSPIKE_PORT`).
- **W21 Personal settings.** Theme, landing page after sign-in, clock time zone, compact density, change-flash on or off, default search size, and up to 20 pinned entities on the terminal home.
  - **Storage:** kept on the server (`/api/v1/me/settings`, every value validated), so they follow the user to any browser.
  - **Console:** applies them on every page (the theme before first paint), saves theme changes from the theme menu, and offers **Pin** in every view.
- **W20 Single sign-on (OpenID Connect, ADR-014).**
  - **Console:** runs the authorization code flow with PKCE, state and nonce, and offers "Sign in with single sign-on" on the login page.
  - **Server:** verifies the ID token itself: signature against the provider's keys (JDK cryptography only; RSA, RSA-PSS or ECDSA; never `none` or an HMAC), issuer, audience, authorised party, expiry, not-before, issued-at and nonce.
  - **Users:** provider groups map to Drishti roles, and users are created on first sign-in. A local disable always wins, and the provider can't demote the last admin; every step is audited.
  - **Fix:** the console now sends users who aren't signed in to the login page for search, compare and export too.
- **W19 Sutra governance (ADR-013).** With review on (the default), a Studio save is a proposal: validated, with the author's note and the live text it was written against.
  - **Review:** approvers (new role flag `approve`, or admins) read the diff under **Studio → Reviews**, then approve (published and hot-reloaded) or reject with a reason; authors may withdraw.
  - **Safeguards:** four eyes, so nobody approves their own proposal. Approval is refused if the live Sutra changed after the proposal (`DRS-2006`), and two approvers cannot both decide one proposal.
  - **Record:** proposals are stored as JSON, and every step is audited.
  - **API:** `/api/v1/sutras/proposals…` and `/api/v1/sutras/{name}/history`.
  - **Settings:** `DRISHTI_SUTRA_REVIEW=false` restores direct saving.
- **W18 Export and share.**
  - **CSV:** any table-like panel downloads as CSV (shown numbers become plain numbers, UTF-8 with BOM), as do search results and comparisons.
  - **JSON:** the document downloads as JSON, redacted for the role.
  - **Print:** a print stylesheet gives a clean light page, or a PDF, in every theme.
  - **Share:** copies a link that reopens the view with the same business date and exact known-at instant (`/asof?ki=`).
  - **Safety:** exports read through the server like the screen, so entitlements and redaction apply.
- **W17 Structured search.** Type `TRD where notional >= 250m and assetClass = 'Rates' order by mtm desc limit 20` on the command line.
  - **Syntax:** friendly, over Rachana-EL: `and`, `or`, `not`, `=`, `contains`, `startswith`, and amounts like `1.5m`; nested fields by path.
  - **Server** (`GET /api/v1/search`): reads the kind's entities concurrently within `drishti.search.max-scan` (20,000) and `drishti.search.budget` (3 s). It evaluates the condition on each document as the caller may see it (a masked field never matches), then sorts and limits. It follows the business date and known-at.
  - **Console:** a results page whose columns are the query's fields, labelled from the taxonomy; **Watch as a monitor** turns the results into a live watchlist.
  - **Rachana-EL:** gains `contains()` and `startsWith()`; new error code `DRS-4004`.
- **W16 History.**
  - **Server:** `GET /api/v1/history/{kind}/{id}/diff` compares an entity between two business dates, or two "known at" instants, field by field. Arrays are matched by identifier or natural key (tenor, date, code, …), numbers get deltas, each side is redacted for the caller, and labels come from the taxonomy.
  - **Console:** a **Compare** page (from any dated view; filter changed, added or removed), and a **known at** clock beside a picked date for time travel (read in the business zone, sent as `X-Drishti-Known-At`, cleared with Live).
  - **Delta:** time travel before a table's first commit finds nothing, and after its last commit reads the latest (both were errors).
  - **Console fix:** a known-at choice could cache the wrong business date for the top bar.
  - **Top bar:** it no longer scrolls sideways when a date is picked on narrower screens.
- **Docs and tutorials.** New *Tutorial 5 · Build a domain pack* (the common pack builder, step by step). README, the user guide (mnemonics of every generated pack, from the manifests), PACKS.md, LIVE.md and the architecture's concurrency section brought up to date; OPERATIONS explains how the disk cache reclaims space.
- **Concurrency hardening, audited module by module.** Fixed:
  - **Disk cache:** RocksDB generations are reference counted. A nightly clear, a purge or a close never frees native memory under a running read (it could crash the JVM after a 30 s grace period); later calls miss instead of failing.
  - **Sign-in and administration:** changes are applied to the current record under per-user and admin locks. Parallel wrong guesses can no longer dodge lockout, a sign-in finishing late can no longer undo an admin's disable or password reset, and two admins can no longer disable each other at once.
  - **Live topics:** subscribers arriving together could leave a topic unconnected, so a live view never ticked. Topics now connect exactly once.
  - **Kafka:** the cold-miss reader uses a `ReentrantLock` with a timeout (was `synchronized`, pinning carrier threads for up to 5 s); a fetch racing a newer record or a tombstone no longer caches the stale document; listener entries go with their last listener (also in the demo source); close is safe against a late read.
  - **Delta:** partitions and logs load through async caches on virtual threads (no map lock held during Parquet reads).
  - **Hot paths:** a failed live rebuild backs off (0.5 s to 30 s) instead of spinning; alert-rule saves are serialised so none is lost; the audit log, preferences and Sutra registry use `ReentrantLock` instead of `synchronized`; a preference delete can no longer race a write.
  - **Snapshots:** the search index and the Sutra registry publish complete immutable snapshots (searches never see an empty index mid-rebuild), and hot reload survives an unexpected error.
  - **Stream limits:** the stream cap is taken atomically and shared by monitor streams, which now release their subscriptions on failure.
  - **Scheduler and shutdown:** the plugins' scheduler runs on virtual-thread workers; shutdown interrupts stream writers instead of waiting out a heartbeat.
  - **Tests:** race tests reproduce each hazard and fail on the old code.
- **Politics and society pack.** Two fictional polities, so nothing describes real parties or people, with real mechanics: seats by D'Hondt with a 5% threshold or by first past the post, fortnightly polls with 95% margins of error, bills through their readings with votes, regional results and social indicators.
- **Economics pack.** Seven real economies with illustrative figures: growth paths and output by sector, macro indicators with release calendars and surprises, central-bank decisions and guidance, forecasts with weighted scenarios, bilateral trade flows, labour markets, fiscal positions and consumer-price contributions.
- **Genomics and biology pack**, the first with no banking ties. Genes with GRCh38 locations, expression by tissue and known variants; variants (BRAF V600E, KRAS G12D, EGFR L858R, CFTR F508del, …) with HGVS names, dbSNP ids, significance and population frequencies; UniProt proteins with domains; pathways; tumour samples with variant calls; sequencing runs with quality by cycle; differential-expression studies; targeted-therapy trials. Reference facts are public; patient-level data is synthetic.
- **Retail-banking pack.** Customers with every product they hold, current and savings accounts with transactions (live balances), mortgages with amortisation, property and payments, credit cards with spend by category and statements, personal loans, branches with product mix and NPS, collections cases for delinquent cards and loans, and IFRS 9 portfolio segments with ECL by stage.
- **Fix:** a generated pack may no longer reuse a link field that already means another kind. Climate stress linked its NGFS scenario through `scenario`, which market-risk maps to stress scenarios; it is now `climateScenario`. The domain-packs test checks that no advertised example has a missing link.
- **Operational and non-financial risk pack.** Loss events (Basel event types, recoveries, timelines, the failed control), risk and control self-assessments, key risk indicators with amber and red thresholds (live), audit and regulatory issues with actions, operational-risk scenarios, third-party (vendor) risk, cyber incidents and SMA operational-risk capital per legal entity, on the banking-core desks and legal entities.
- **Liquidity-risk and climate-risk packs.** Liquidity: LCR and NSFR per legal entity, maturity ladders, HQLA holdings (the market-data bonds), funding sources (the banking counterparties), liquidity-stress survival and intraday liquidity. Climate: counterparty climate profiles, PCAF financed emissions per book, NGFS scenarios, climate stress per desk, physical-risk assets and the EU-taxonomy green asset ratio. Both are generated from the banking data, so entities line up, and are built by a new common builder (`tools/packgen/common/packbuild.py`) that writes the manifest, Sutras, samples, guide and lake after checking ids, links and Sutra paths. Kinds with no links no longer get an empty links panel.
- **Admins purge caches.** Admin → Caches lists what the engine and each connector hold (entries, memory, disk, hit counts) with a Purge button per cache and Purge all; `GET /api/v1/admin/caches`, `POST /api/v1/admin/caches/{name|all}/purge`. Every purge is audited. Plugins report and drop their caches through two new SPI defaults, `cacheStats()` and `purgeCaches()`.
- **Disk cache for live data.** A new `drishti-diskcache` module: a RocksDB store on local disk, bounded by size (FIFO: the oldest data goes first), without a write-ahead log, cleared every night at a configured time and zone by swapping in a fresh store (readers never wait). Each connector has its own store and directory, so connectors do not contend. The Kafka connector writes every message to it and reads memory, then disk, then Kafka. The trading stream uses it by default (`./data/cache/trading-stream`, 10 GB, cleared at 02:00 New York).
- **Bounded memory.** The Kafka connector keeps only where each entity's latest message is, plus a cache of recently read documents limited by `cache-mb`; a miss reads the one message back by offset, and messages for unwatched entities are not parsed. `mode: ticks` keeps nothing and only drives the ticks of open views. The Delta partition cache is limited by size (`cache-mb`), and the Aerospike reference index can be turned off (`reverse-index: false`). OPERATIONS.md lists every cache and its limit.
- **Kafka live source.** A `kafka` connector reads topics of entity documents (the latest message per entity is the entity, rebuilt from the start of the topic) and pushes every new message to open views, so they tick from a real stream. Envelope or whole-document messages, tombstones, no consumer-group commits. The trading pack declares `trading-stream` (off until `DRISHTI_STREAM_TRADING=true`); `tools/samplegen/stream.py` replays and ticks the trades; `deploy/compose.data.yaml` adds a Kafka broker. Tested in-process with an embedded KRaft broker, including end to end through the server. For Live, a real stream now wins over the demo samples.
- **PostgreSQL and Aerospike (R5).** The JDBC connector gains a table mode: every kind of a data domain in one PostgreSQL table `(kind, id, business_date, doc jsonb)`, dated (snapshot or effective), with search and reverse lookups by SQL/JSON path. A new Aerospike connector keeps a record per entity with a bin per business date. `make_data.py --postgres` and `tools/load-aerospike.sh` load the banking data; `deploy/compose.data.yaml` runs PostgreSQL 18 and Aerospike CE; the `postgres` and `aerospike` profiles switch the banking stores. The banking connectors are now `<domain>-store`.
- **One contract for every dated store.** `DatedSourceContract` (testkit) runs the same tests against Delta Lake, PostgreSQL and Aerospike (the database runs use Docker via Testcontainers and are skipped without it; the PostgreSQL SQL was also verified on PostgreSQL 18).
- **Public data feeds.** A `feed` connector with five feeds: NY Fed SOFR, ECB €STR, ECB euro reference FX rates (with USD crosses), US Treasury par yields, and FRED series. The market-data pack declares each as its own connector, off by default, with its own switch. Feed entities carry the feed in their id, keep history for picked dates, and report failures in health. Tests use recorded public responses.
- **Banking pack documentation (R4).** `tools/packgen/banking/make_docs.py` writes eight guides from the taxonomy and the data: an overview per pack (requirements, data domains and connectors, every kind with its mnemonic, identifiers, fields and links, example commands, roles, impact), the 125-product catalogue, the market-data catalogue and the cross-pack data model. Help gains a **Domain packs** category. The `trader` role can open the market and reference data its trades link to. Drill fails if the guides are out of date.
- **The Sutra guide** (Help → Layouts, Rachana and Sutras): a complete guide to writing Sutras, from a first Sutra to anatomy, labels and taxonomies, paths and expressions, formats and tones, all thirteen panel kinds with screenshots from the banking packs, matching, inference, business dates, imperfect data, Studio and a checklist. A test checks every screenshot it shows is served.
- **Banking data (R3).** `tools/packgen/banking/make_data.py` generates 1,791 consistent documents across the five banking packs: 750 trades in 125 products (rate swaps with real calculation periods that reprice to their MTM), 220 market-data objects, 66 netting sets with exposure, CVA, SA-CCR and collateral, limits, SIMM, VaR, stress, FRTB and P&L explain, and the reference data. Checks: references resolve, netting sets reconcile, every field a Sutra reads is present. `--lake data/delta` writes it as Delta tables by data domain with ten business days of history. The packs gain example commands.
- **`surface` panel kind** (the thirteenth): a grid over two axes drawn as a heatmap, with a **3D** toggle (drag to rotate). The volatility kinds use it. ECharts is now the full build, and ECharts GL is vendored with its one `new Function` replaced, so the strict CSP still holds.
- **Labels are optional.** A strip item or column without `label` is named after the field it reads. Resolution order: the Sutra, then the pack taxonomy (`labels:`), then the global taxonomy, then the field name in words with the `acronyms:` vocabulary (UTI, DV01, MTM…).
- **Tutorial 4 · Nested documents**: paths of any depth, tables over nested arrays, tabs per element, and a whole Sutra over a real trade. Every complete Sutra in the docs is now parsed by a test.
- Live views prefer live sources; a picked date prefers dated ones. The finance pack declares its own lake connector.
- **Banking packs.** The risk pack is split into five packs generated from one taxonomy: `banking-core`, `market-data`, `trading` (125 products), `market-risk` and `counterparty-risk`. Enabling a risk pack brings the packs it requires.
- **Pack dependencies, connectors and routes.** `pack.yaml` gains `requires:` (loaded dependencies first; cycles and missing packs refused), `connectors:` (one per data domain; several packs may declare the same connector identically) and `routes:` (which connector answers each kind). Delta Lake is organised by data domain (`data/delta/<domain>/<kind>/`): packs and domains are many-to-many. Users can open the kinds of the packs their active packs require.

## 1.9.0 — Wave 22, risk pack R1–R2, Markdown Sutras, realistic samples (2026-09-30)
- **Business dates (Wave 22, ADR-012).** A date box in the top bar: **Live** (the default) is the current business date on the New York calendar and streams; a **picked date** is a static snapshot, even when it is today. Weekends and holidays roll back to the previous business day. The console only stores the choice and sends `X-Drishti-As-Of`; the server resolves it (`GET /api/v1/business-date`, `DRS-4003`), and the date reaches every read: views, links, F8 impact, suggestions, raw JSON, monitors and Studio. Views state the date their data is for, and say so when a source keeps no history.
- **Delta Lake connector** (`delta`, Delta Kernel, no Spark). Tables live at `<root>/<domain>/<kind>/`, root configurable (default `./data/delta`), partitioned by `business_date`. Snapshot and effective (last change) tables, time travel with `knownAt`, date-aware reverse lookups and search. `tools/samplegen/lake.py` writes a pack's samples as a lake with ten business days of history plus a restatement.
- **Named connectors** (`drishti.sources.connectors.<name>: {plugin, kinds, settings}`) run a plugin several times, for example one lake per domain. For a picked date, dated sources are tried first.
- The file plugin reads dated folders (`<root>/<yyyy-MM-dd>/<kind>/`); the JDBC plugin binds `:id` and `:asOf`.
- **Imperfect documents never break a view.** A panel whose data is missing or has the wrong shape renders "No data available". Header fields and titles fall back quietly. Non-numbers are sent as `null`, never as invalid JSON `NaN`, and one bad chart cannot stop the others. Tested with every Sutra against empty, mistyped, reshaped and half-missing documents.
- Fix: the *crimson* theme now uses Bootstrap's light mode.
- **Realistic finance samples.** Every trade is now fully booked: an execution block (venue and MIC, timestamp, trader, sales), a lifecycle and audit history, confirmation, clearing, regulatory reporting (UTI, UPI, regimes), settlement instructions (BICs, SSIs), valuation (model, curves, leg PVs) and 20 days of P&L. Swaps carry every calculation period with fixing dates, year fractions, fixings or projected forwards, DFs and PVs, and the thin swaps' fixed rates are solved so their cashflows reprice to their MTM. Counterparties have LEIs, ratings, KYC and regulatory classification. Curves publish pillars, and SOFR has 20 days of fixings. Agreements, CSAs, limits, books and contract specs are complete. The swap with no Sutra is now an FpML-style document from another booking system. Generated by `packs/finance/tools/make_fixtures.py` on the new `tools/samplegen` toolkit (identifiers with check digits, calendars, schedules, day counts, curves, legs), which has its own tests.
- Inference: when rows have more fields than a table shows, it keeps the first column and the columns whose semantic role weighs most, instead of the first nine.
- Long values in field lists wrap instead of overflowing.
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
