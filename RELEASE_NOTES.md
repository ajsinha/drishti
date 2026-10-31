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
# Drishti 2.9.6 — release notes

*Released 2026-10-29.* **Drishti Risk with one setting; VaR sharded across servers; a parallel Java loader; servers that run for
days.**

- **Editions: Drishti Risk** ([EDITIONS_GUIDE](docs/admin/EDITIONS.md), [EDITIONS](docs/admin/EDITIONS.md#13-the-design-as-built)): one setting,
  `drishti.edition: risk` (or `DRISHTI_EDITION=risk`), turns an instance into **Drishti Risk**, VaR and exposure first: its packs
  (others refused, `DRS-1017`), its commands (others hidden from the command line, FUNC and type-ahead, `DRS-4023`; the engines still
  read every kind), its menus, help, Knowledge, Admin pages, BI datasets and models, and its name. Three landings: **Risk desk** (the
  default), **Command first** and **Risk map**, each person's choice remembered. On a full instance, the **lens** (`drishti.lenses:
  risk`, off by default) gives each person *Open Drishti Risk* and *Leave Drishti Risk*, with no restart; a lens hides, it does not
  secure. Without an edition Drishti is exactly as before. Works across a cluster; Admin → Net warns when members differ.
- **VaR sharded across Drishti\*net** ([VAR_SHARDING](docs/architecture/VAR_SHARDING.md)): off by default; with the market-risk pack's
  `sharding:` block each date is cut into legal-entity and desk ranges, owned by two members each (rendezvous hashing, no consensus
  protocol); every member keeps every node's aggregate, so bank and desk totals answer even with a shard's owners down; other scopes sum
  one partial per shard, never a partial VaR (`DRS-5215`); handover and drain for scale-in. Equal to one server to the cent.
- **The parallel lake loader** ([PARALLEL_LOADER](docs/architecture/PARALLEL_LOADER.md), [TOOLS 4.13](docs/guides/TOOLS.md)): a
  config-driven, multi-threaded Java loader of JSON Lines from a folder or S3 into Delta or Iceberg, memory bounded, one commit per
  date. `lake build` keeps the Python path as the default; `--engine java` asks for the loader. Both follow one duplicate-record rule:
  the latest `COMPUTED_AT` wins (else the last read), the others counted, never a failure.
- **Servers that run for days:** lake, hot-file, compute and content-store reads no longer exhaust direct memory (a server had stopped
  answering trade pages after about 13 hours); Kafka fetches capped; every launcher gains the long-run JVM flags (a cached-buffer cap,
  a heap dump on out-of-memory, a rotated GC log, native memory tracking); Admin → Health shows memory and files (direct, mapped, heap,
  threads, open and deleted files).
- **Fixes:** an intraday batch can no longer bring back a date a reload dropped (published only over the date it was built on);
  reporting currencies always carry the current intraday layer; batches listed in a stable order; cluster changes queued for suspected
  members; the browser tests ignore a checkout's own connector and cluster files.
- **Documents:** the ViewModel contract and its JSON Schema ([VIEWMODEL](docs/architecture/VIEWMODEL.md),
  `GET /api/v1/viewmodel/schema`); how a screen is made, generic not bespoke (ARCHITECTURE section 7a); a worked example of every data
  type, field by field ([data samples](docs/contracts/samples/README.md)), with every input contract audited against all its rows and a
  drift test; the computed-attributes analysis of the market-risk and counterparty-risk packs, with its decisions
  ([COMPUTED_ATTRIBUTES](docs/architecture/COMPUTED_ATTRIBUTES.md)); the documentation consolidated (178 to 152 active documents,
  obsolete designs deleted); the README rewritten as a product page.
- **Upgrade:** rebuild the server image or jar so the new launch flags apply; nothing else to do. The lens and sharding are off until
  you turn them on.
- **Known open:** the soak audit's long-run items, the scale benchmark at 200K positions, the remaining computed-attribute work,
  stressed and 10-day VaR, the client-side renderer and the deck clean-up are planned for 2.9.7. One browser test, `test_live_tabs_browser.py` (many live tabs and a live workspace, the shared-worker
  leader case), fails on the release machine since a reboot, and fails the same way on the code before this release's changes; it is
  under investigation for 2.9.7. Every other check of the release drill passed: Java 21, Java 25, the tools and the console suite.

# Drishti 2.9.5 — release notes

*Released 2026-10-28.* **Iceberg as a full alternative to Delta; VaR hierarchies of your own, such as
limit letters; connector files that upgrade themselves safely.**

- **Hierarchies by columns** ([VAR_GUIDE 15.14–15.15](docs/guides/VAR_GUIDE.md)): besides MGMT and LEGAL, any hierarchy built from the
  trades' own columns, configured in the market-risk pack (`hierarchies:`) or `drishti.var.hierarchies`. The demo's **limit letters**
  (`LIMIT_LETTER > DESK`) with their limits (`var-limit`); a letter may hold part of a book (the positions split, and said); `VAR SCOPE
  LL:LL-0011`, the VaR page's hierarchy select, official VaR and exposure records for every hierarchy. Data without the column skips
  that hierarchy alone, with a warning (`DRS-5211`, Admin → Health, *not available* in the select); the rest answers as before.
- **Iceberg, as a full alternative to Delta** ([ICEBERG_CONNECTOR 18–19](docs/connectors/ICEBERG_CONNECTOR.md),
  [tutorial 29](docs/tutorials/29-var-on-iceberg.md)): VaR, intraday (files and Kafka) and exposure read from Iceberg, equal to Delta
  to the cent (VAR BANK 112,041,646 on 2026-09-30 on both); `lake build --format iceberg`, `build_demo_lakes.py --store risk=iceberg`;
  Admin → Lakes and `lake maintain` for Iceberg (the Format column, snapshot expiry, orphan files, the 7-year floor); official results
  published as Delta or Iceberg, to a folder or to S3, with BI's readers following; `deploy/aws/iceberg/` scripts. Delta stays the
  default.
- **Your own lake** ([EXTERNAL_LAKES](docs/guides/EXTERNAL_LAKES.md)): Databricks Delta tables or Iceberg tables from Spark, Databricks,
  Trino or Snowflake, path-based or through a REST catalog (Unity Catalog, Polaris, Glue), by configuration only.
- **Connector upgrades made airtight** ([CONNECTOR_FILES "Upgrades"](docs/connectors/CONNECTOR_FILES.md)): a file nobody edited follows
  the pack's new template; an edited one gains every missing key and keeps its values; each conflict has **Use the pack's value**; the
  file before is kept beside it (`.before-upgrade-….bak`); an upgrade test in the drill.
- **Also:** P&L-vector ids are `PNL-<trade>-<set>` (`PV-` ids still read); one download button per panel with a CSV / JSON Lines menu;
  subscriptions in `jsonl` documented; the demo lake measured at 85 tables, about 3.4 GB.
- **Upgrade:** rebuild the demo lakes (`build_demo_lakes.py`: `LIMIT_LETTER` and `var-limit`); connector files follow by themselves. A
  `var-results.yaml` written by `lake demo` stays on Delta when the official results move to Iceberg: **Reset to pack default**.
- **Known open:** the AWS Glue and S3 Tables live runs (written, not run: they need an AWS account); the S3 format guard is tested on
  S3Mock only; shellcheck was not run on the new scripts; tables copied into Iceberg by `build_demo_lakes.py` are read only in Admin →
  Lakes; about 271 pictures in other guides still show the old top bar.
- **Next, 2.9.6:** editions (`var-engine` first), VaR sharded across servers, the 2.3M-position benchmark, Delta ↔ Iceberg conversion,
  stressed and 10-day VaR, in-place Delta schema evolution, plain tables and Delta through Unity Catalog.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.9.4 — release notes

*2026-10-27.* **VaR from the pricing systems' own data, in any currency, updated through the day; JSON Lines as the one data
standard.**

- **Any input contract** ([VAR_GUIDE 15.9–15.13](docs/guides/VAR_GUIDE.md)): scenario PVs or path P&L, by trade or by position,
  in any currency, from files or Kafka. Every record says what it is (`VALUE_TYPE`, `GRAIN`, its id, `CURRENCY`, `SOURCE_SYSTEM`);
  a per-system pin (`drishti.var.inputs`) is an optional cross-check; a source system may switch form or grain from one day to the
  next. Scenario PVs are converted to P&L with scenario FX (supplied, or derived from the FX history; crosses via USD).
- **VaR in any reporting currency:** USD, EUR, CAD, JPY and GBP built ahead in the background; any other on first ask; a reporting
  currency picker and an *In other currencies* tab on the VaR page; each trade's currency on its page.
- **Intraday:** changed trades or positions only, from small JSONL files or Kafka (JSONL messages, micro-batched every 5 minutes,
  early flush, Apply now), applied as new − old up the tree; the result equals a full reload to the cent. Official numbers come only
  from full runs.
- **Settings:** the scenario window (`all` or the newest N days, e.g. 250), the confidence (99% by default, 95% by setting), the VaR
  tree's top name, the demo's bank and legal-entity names (`config/demo/names.yaml`).
- **A stand-in pricing system** (`tools/pvgen`): a day's morning run, intraday files, a Kafka publisher and a checker that recomputes
  VaR independently; P&L-vector scripts kept.
- **JSON Lines is the data standard** ([DATA_FORMATS](docs/guides/DATA_FORMATS.md)): every generator writes JSONL, loaders load it
  into Delta; CSV input is deprecated (`drishti.py convert`); every export and download also offers JSONL.
- **Also:** the Basket button left the top bar (Construct your basket stays on the VaR page); connector files get new columns by
  themselves on upgrade (a backup kept); 351 documentation pictures retaken and the fixes the screenshot run found (New pack across
  console workers, installed packs' guides, the About drawer, the Legs list); the decks in `docs/publications` rewritten.
- **Upgrade:** rebuild the demo lakes (`build_demo_lakes.py`); P&L files without `GRAIN`/`VALUE_TYPE`/`CURRENCY` are refused, so add
  them (`drishti.py var convert --set …`).
- **Known open:** about 271 pictures in other guides still show the old top bar; VaR sharding across servers is planned.
- **Next, 2.9.5:** Iceberg as a full alternative to Delta (Delta stays the default); VaR hierarchies configured by columns, such as
  limit letters; all VaR feeds regenerated.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.9.3 — release notes

*2026-10-27.* **A map of all your data that draws itself; a surprising fact from your own data on Home; index membership that is
finally right.**

- **DATAMAP** (Views → Data map, or `DATAMAP [domain|kind]`; [USER_GUIDE "The data map"](docs/guides/USER_GUIDE.md)): every kind of
  data on the server, how it joins and how much there is, as a **Map** (domains as clusters, joins as lines) or as **Cards** (one per
  domain, with each kind's joins and commands). Nothing is hand-drawn: kinds come from the enabled packs, joins from the data
  contracts, each **checked against the data** (how many ids match), counts from the lake on the business date; a new pack appears
  with no setup. It found 11 joins that do not hold (e.g. a jurisdiction code where an id is expected), listed in the CHANGELOG.
- **The fact of the day on Home** ([USER_GUIDE "Home: the fact of the day"](docs/guides/USER_GUIDE.md)): one surprising, true
  sentence from your data, with a small picture, "Why does that happen?", the command that shows it and "Another fact". Each pack
  declares its facts ([PACK_DEVELOPER_GUIDE "Facts for Home"](docs/guides/PACK_DEVELOPER_GUIDE.md)); they are computed as you, on
  your business date, and only those that hold today are shown. Seven ship: a book over its VaR limit while its desk is within, a
  desk that lowers VaR, posted collateral raising exposure, VaR against peak PFE, the most-held company, two managers holding one
  portfolio, a fund's weights above its net assets. Quick cards below: VAR BANK, EXP BANK, FUND SPY, DATAMAP.
- **Knowledge: Companies, funds and ETFs** ([docs/knowledge](docs/knowledge/companies-funds-and-etfs.md)): share classes, NAV,
  look-through, expense ratios, active share and overlap on the demo data; 23 new `WHAT` terms (162 in all); "What does this mean?"
  on USEQ, FUND, HELD and TRACK.
- **Index membership from the tracking ETFs** ([External data](docs/guides/PUBLIC_DATA.md)): the S&P 500, Nasdaq-100 and Dow members
  are now what SPY, QQQ and DIA report holding to the SEC, not Wikidata's out-of-date lists (which lacked Exxon Mobil, BlackRock,
  NVIDIA in the Nasdaq-100 and about 100 more). **The companies pack grows from 503 to 586.** The *vs index* tab weighs the Dow by
  price and caps the Nasdaq-100 as their methods do: DIA's active share 1.8% (was 57.6%), QQQ's 11.2% (was 26.75%).
- **One firm-wide VaR limit:** the legal-entity tree's root now carries the bank's 150m limit, as the management tree does (it read
  210m); every VaR is unchanged.
- **Planned:** [test data from seeds](docs/architecture/IMPLEMENTATION_PLAN.md#plan-test-data-from-seeds-approved-for-296-not-built), a design for review (nothing built).
- **Known open:** the about 600 older screenshots not touched by this release's changes move to 2.9.4 (the pictures of what changed
  here are retaken); limits still read the stored credit limits (LIM parked); the data map's 11 findings; 55 companies have no usable
  share count, so index-fund active shares are not 0.
- **Next, 2.9.4:** VaR from scenario PVs in any reporting currency.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.9.2 — release notes

*2026-10-26.* **US funds and ETFs with their holdings; Knowledge pages and `WHAT` that explain the data; construct your own basket and see its VaR; a pre-deal what-if for VaR and exposure; risk one click from every trade.**

- **Funds and ETFs** ([FUNDS_GUIDE.md](docs/guides/FUNDS_GUIDE.md), tutorial 28): the 200 largest US mutual funds and ETFs from SEC
  N-PORT, N-CEN and the prospectus summaries, with GLEIF relationships; `FUND <ticker>` (a fact sheet, its look-through holdings and
  how it compares with its index), `HELD`, `TRACK`, `FUNDS`; USEQ's *Held by funds* and *Fund ownership*, IDX's funds tracking it;
  a refresh script for Linux and Windows; 5.6 MB of gzipped data, offline. Sectors are SIC divisions (GICS is proprietary) and
  index weights are computed from SEC filings; the page says so.
- **Knowledge** ([docs/knowledge](docs/knowledge/README.md)): market risk, counterparty credit risk, trades and products, entities and
  ownership, market data, data and contracts, economies; a 139-term glossary; each page with its plain idea, how the records
  connect, a worked example on the demo data and the questions to ask it. `WHAT <term>` explains a term; Help opens on Knowledge;
  "What does this mean?" beside the VaR and exposure explorers and the risk pages.
- **Construct your basket** ([VAR_GUIDE.md section 3.5](docs/guides/VAR_GUIDE.md)): pick portfolios, baskets, desks, books, legal
  entities, counterparties, positions, trades or a rule as you type, add or take away each, and see the mix's VaR, ES and
  diversification live; save it as a basket that opens in the full explorer. A strip in the VaR explorer, a popup elsewhere
  (◫ Basket, Alt+B). It replaces "+ New scope".
- **The pre-deal what-if:** a hypothetical trade (a copy of a booked one, scaled; −1 its unwind) in the basket shows VaR before and
  after; the exposure explorer has it on the counterparty as well as the netting set. Exact methods run on the compute workers.
- **Trade pages' Risk links:** P&L vector, VaR of this trade, exposure of this trade, or why not. Ids that match nothing and trades
  that are not live are said in words instead of a VaR of 0.
- **Data quality** (found while writing the Knowledge pages): economy debt is the latest actual year, not a 2031 projection; netting
  sets and exposure runs hold live trades only (a cancelled trade had driven half of one set's exposure); valuations follow the
  trade's version of the day; equity beta shows its decimals; FX spot dates move with the business date; legal entities no longer
  carry stored VaR. **Exposure figures change** (e.g. NS-ALDERSHOT-LDN peak PFE 85.1m on the data's date); VaR does not.
- **About and Competitive** rewritten for what Drishti now is.
- **Known open:** limits still read the stored credit limits (LIM parked); the bank's VaR limit differs between the management and
  legal-entity hierarchies (150m and about 210m) in the data (one 150m limit from 2.9.3); about 600 older screenshots are refreshed in 2.9.3.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.9.1 — release notes

*2026-10-26.* **Every command in one directory (`FUNC`); groupings and analytics computed, not stored; one External data page; every pack's guide carries its data contracts and where its data comes from.**

- **Functions, `FUNC`** ([COMMANDS.md](docs/admin/USER_MANAGEMENT.md#functions-func-every-command-and-turning-one-on-or-off)): Views → *Functions* lists every command you can use, by pack, with
  what it does and an example; `FUNC <words>` filters it. A pack's name opens its search form; a command opens with its example.
  Administrators see the same page with the commands that are off and the Everyone switches, review, history and the cluster's
  versions; `/admin/commands` opens it. The server still decides who may switch.
- **Groupings and analytics computed instead of stored** ([GROUPINGS_ANALYTICS_GUIDE.md](docs/guides/GROUPINGS_ANALYTICS_GUIDE.md),
  tutorial 27): LEIG (no member cap), LEIC, SIC, ECG (summed or weighted over member economies, as the World Bank does), CORR (a new
  correlation engine, window and estimator picked on the page), the USEQ ratios, the yearly change of a financial fact, IDX totals,
  equity beta (a new engine, `EQB`) and the price-history analytics (internal-only, off as shipped). Their stored versions are retired;
  old ids open the computed pages. **The numbers change** where the stored ones were set by hand (correlations ~0.4 stored, ~0.1
  computed; equity betas near zero on the demo's independent price series); the reconciliation is in the guide.
- **External data** ([the page](docs/guides/PUBLIC_DATA.md)): every external source with its URL, licence and use; IMF, Yahoo and
  Google News data marked internal only; each refresh script with real `status` and dry-run examples on Linux and Windows.
- **Every pack's guide** now ends with its **data contracts** (every kind, every column) and its **data retrieval** (per kind: external
  with its script, synthetic with its generator, or computed with its engine), generated from the contracts, so they cannot drift.
- **Fixed:** a control that feeds a computed panel (CORR's estimator and window) now recomputes it at once; the exposure answer page
  no longer shows an empty netting-sets table for a single netting set.
- **Exposure:** the benchmark (`ExposureTierBenchmark`, measured at 10,000 and 100,000 trades, extrapolated to 1M) and the explorer's
  browser test.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.9.0 — release notes

*2026-10-26.* **Exposure is computed for any scope, down to a trade; the stored exposure profile is retired; VaR and exposure reach BI; each measure has one guide and one tutorial.**

- **Exposure (EXP), computed like VaR** ([EXPOSURE_GUIDE.md](docs/guides/EXPOSURE_GUIDE.md), tutorial 26):
  - EE, ENE, effective EE, PFE 95/99, EPE, EEPE, peak PFE and EAD (information) for any scope: netting set, counterparty, group (GLEIF
    parents), legal entity, desk, book, a single trade, the bank, joined with `+ - &`; every trade's MtM on every path netted per
    netting set, each CSA's collateral applied path by path (thresholds, MTA, IA, rounding, margin period of risk);
  - **the exposure explorer** (`EXP <scope>`): tree, breadcrumb and Back, headline tiles, the profile with the path fan, the counterparty
    board, netting-set contributions, the Collateral, Trades and Pre-deal tabs, and a trade alone, in its set and its netting benefit;
  - **pre-deal what-if**: exact by the Python engine on the run's own paths (`XWIF`, compute on), or a conservative add-on marked proxy;
  - official end-of-day results published to `risk-results/exposure-official` and `-cpty`, captures and compare, CSV, the load signal
    reloads the date, tiered retention (full trade paths 20 days and month-ends for a year; statistics 7 years), float32 paths read;
  - `drishti.py exposure`; DRS-5440..5446; the demo lake generates paths for 22 of the 24 counterparties (73 netting sets, 9,000 trades).
  - **Retired:** the stored `exposure-profile` kind and the netting set's and counterparty's stored peaks. `EXP-…` ids open the computed
    netting set. **The numbers change:** the stored peaks summed to 92.6bn, computed 7.6bn on the same sets (the generator scaled them
    with gross notional and ignored the CSAs); the 76-set reconciliation is in the guide.
- **VaR and exposure in BI:** the published official results are read-only kinds (`VOFF`, `XOFC`); a new Market risk VaR model and
  report; the Counterparty risk, Trading risk, Desk wall and P&L vs exposure content shows computed exposure (the live model's two exposure
  cards became Collateral and Max utilisation).
- **Documentation:** VaR and exposure have exactly two documents each, a complete guide (use, data contract, engine) and a tutorial, and
  two Help pages; tutorials 10, 12, 14, 18, 25 and 26 have new screenshots; 26 tutorials.
- **Landing page:** the headline "See what your data hasn't told you yet." with the tagline under it, both from configuration.
- **Test runs no longer rewrite the committed screenshots** (`DRISHTI_SHOTS` to retake them on purpose).
- **Known open:** the exposure benchmark and the explorer's browser test (2.9.1); limits read the stored credit limits (LIM parked), which
  were sized on the old profiles, so utilisation reads low; CP-SUMMIT and CP-NORTHPLAINS have no paths in the demo (10,000-trade limit).
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.8.0 — release notes

*2026-10-26.* **Every command has a data contract; VaR is computed for any scope, never stored; commands can be switched off; forms save.**
The scope of 2.8.0 was narrowed by the product owner to fundamentals and VaR; the other computed measures are parked with their
designs kept ([IMPLEMENTATION_PLAN.md](docs/architecture/IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)).

- **Data contracts** ([docs/contracts](docs/contracts/README.md)): the shared rules (keys, types, dates, signs, vectors, delivery,
  validation, entitlements, versioning, retirement); a machine-readable contract for every command
  (`config/packs/<pack>/contracts/<kind>.contract.yaml`, `drishti.py contract`), generated pages, and the **command catalog**
  ([COMMAND_CATALOG.md](docs/guides/COMMAND_CATALOG.md)): every command with what it does and an example. The detailed VaR contract is
  [VAR_GUIDE.md Part II](docs/guides/VAR_GUIDE.md#14-the-contract-at-a-glance); the parked measures' contracts (FRTB, SIMM, SA-CCR, EXP, CVA, stress, P&L explain …) are kept as designs.
- **VaR, complete** ([VAR_GUIDE.md](docs/guides/VAR_GUIDE.md), tutorial 25):
  - the stored `var` and `var-node` kinds are **retired**: `VAR-*` ids and links open the explorer; positions, portfolios and desks
    carry no stored VaR figure, their pages show the engine's, computed as the reader; a guard test keeps it so;
  - **every live trade** valued (9,572 trades), **positions derived by Drishti** from config rules (securities by book and security,
    listed by book and contract, OTC one per trade), 28 portfolios; new scope words `LE:` and `CPTY:`;
  - **official end-of-day captures** of every node, published to `risk-results/var-official` for other systems; the **data-load signal
    reloads** the engine; vectors kept **7 years**; bad vectors, duplicate trades and mixed runs refused with DRS-5201..5204;
  - **memory tiering**: a memory-mapped float32 hot file per date (sums in float64), history reopened in milliseconds; benchmark
    measured at 100k trades and extrapolated to 1M × 50 dates;
  - `VRES` (VaR as an ordinary page) and `VWIF` (what-if on the Python workers: before and after, incremental, stressed window);
    `drishti.py var`.
  - **The numbers change:** with every live trade the bank's VaR on the test data is about 112m (the 1,673-trade sample gave 9.9m); the
    old stored VAR-COMM (446.9m) was not a historical-simulation figure.
- **Admin → Commands** ([COMMANDS.md](docs/admin/USER_MANAGEMENT.md#functions-func-every-command-and-turning-one-on-or-off)): switch any command off for everyone; it then answers "not active"
  everywhere (page, API, command, CLI, search, exports, BI, AI; `403 DRS-1016`), stored in the Drishti database and gossiped to every
  server; ready for role-based access.
- **Write-back** ([WRITE_BACK.md](docs/architecture/WRITE_BACK.md), [EDITS.md](docs/guides/WRITE_BACK_GUIDE.md#for-administrators-write-back-is-off-as-shipped), tutorial 24, off by default):
  forms save through an edit log in the Drishti database, with four-eyes approval, a cluster mode, JDBC writes through an outbox, and
  reactive recomputes and live updates after each change.
- **Branding from configuration:** the product name, owner, copyright and notices come from configuration only; guard tests.
- **Fixed:** opening the console no longer replays old notices as a flurry of toasts; VaR explorer and official capture wait for the
  lake's connectors at start.
- **Known open:** the VRES page shows the 99% confidence as "1"; sharding one date across Drishti*net members, pricing a what-if trade,
  the S3 edit log and inline cell editing are not built; the drill's browser tests rewrite documentation screenshots.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.7.0 — release notes

*2026-10-24.* **Sutra Next: screens that respond, built from one UI kit, designed in plain words; two public-data packs.**
Everything since 2.6.7, including the patches pushed to main without a tag.

- **Sutra Next** ([SUTRA_NEXT.md](docs/architecture/SUTRA_NEXT.md), [SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md)):
  - **The Drishti UI kit** (`config/ui-kit/`): reusable fragments (`row-picker@1`, `field-list@1`, `cashflow-ladder@1`...), pack
    fragments (`trading/trade-table@1`), theme tokens and brand; every fragment and every Sutra carries a `doc:` docstring. Every pack
    screen now uses fragments, each proven to draw the same as before.
  - **Events and children:** a click on a row opens linked records under the row, beside, below or in tabs, from any enabled pack
    (`emits`, `@panel.event`, `@parent`, per-panel `source:`); masked ids stay hidden ("Hidden for you").
  - **Refresh and freshness:** `refresh:` per panel or screen, with pause, resume and interval per person and Pause all; every panel says
    "updated N s ago", marks stale data and names a source that is not answering.
  - **Layout:** named areas on a 12-column grid, splits, tabs, sections, a sticky header, phone order; `user-can-arrange` per reader.
  - **Forms** ([SUTRA_FORMS_GUIDE.md](docs/guides/WRITE_BACK_GUIDE.md)): edit beside a table, in its cells, or New in a guided drawer;
    money, date-time and linked-record fields (live server search), rules, computed defaults, masks and roles; **drafts only** until
    2.8.0's write-back.
  - **One server per screen** on a cluster; "your selections were reset" in words when a server goes away.
- **The Sutra designer** (`ui.designer`, off by default; [SCREEN_DESIGNER.md section 31](docs/guides/SCREEN_DESIGNER.md)): four modes
  over one Sutra — **Customize** the live page, **Describe** a screen in one line, **Outline**, and **Grid** with formulas
  (`B2 = field-list@1(...; source: {... id: @A2.open.ref})`, two levels of click-opened cells); *my version of this page* per person.
  Tutorial 22 builds a screen with it.
- **New packs (opt-in, `DRISHTI_PACKS`), on the standard data pattern (gzipped snapshots in `data/testdata`, built into lakes, served by a Delta connector each):** **us-equities** (479 US companies from SEC EDGAR, Nasdaq, GLEIF and Wikidata: financials,
  filings, index memberships; `USEQ`, `IDX`, `SIC`, `EDGAR`; prices live and internal only) and **gleif** (9,512 legal entities in 540
  ownership groups; `LEI`, `LEIG`, `LEIC`), linked to each other by LEI.
- **Public data, one script per pack** ([PUBLIC_DATA.md](docs/guides/PUBLIC_DATA.md)): `tools/publicdata/<pack>.py` (world-economy,
  news-and-markets, official market data, us-equities, gleif) with `.sh` and Windows wrappers; widen the scope into the data folder;
  internal-only sources refused in the repository.
- **READMEs as a standard:** every pack and every connector has a `README.md` with a front matter and its data structures, shown on
  the Packs pages (`MKT <GO>`, Admin) and served for AI (`/api/v1/packs/{pack}/readme`); a test enforces it.
- **Documentation:** a rot check over every document (links, paths, commands, settings, error codes, command words, kinds); stale
  docs fixed; long guides split; obsolete designs moved to `docs/archive/`.
- **Known open:** a gleif entity's Country link (`US-DE`) points at world-economy's jurisdictions, which do not hold it (a graph field cannot yet be limited to one pack); server-side refresh push to many viewers; GLEIF groups capped at 50 entities in the committed pack (the script
  widens a group); Wikidata's index memberships are stale in places.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console.

# Drishti 2.6.7 — release notes

*2026-10-21.* **BI runs: every heavy question becomes a visible, shareable run; chart any table; Delta and Iceberg side by side.**
2.6.7 also carries 2.6.6, the QA release that was never tagged on its own (see below).

- **BI runs** ([BI_RUNS.md](docs/architecture/BI_RUNS.md), [BI_RUNS_GUIDE.md](docs/guides/BI_GUIDE.md#28-background-runs-long-bi-questions)):
  - **Planned before it runs:** a planner sizes every question from metadata only (Delta's row counts, Iceberg's manifests, built
    layers; tested to read no data). A cheap question answers at once; a heavy one becomes a run; a very heavy one asks to confirm; an
    impossible one is refused before anything runs. Report visuals, MQ, SQL and statements all take `Prefer: respond-async`; without it
    nothing changes.
  - **The Runs tab** in each workspace: live list, filters, a drawer per run (open, cancel, rerun, export CSV/Excel, share, schedule,
    promote to a dataset snapshot or a report); run cards on reports and boards; a bell notice when a run ends; `biruns.py` and
    `bi mq|sql --async` on the command line; Admin → BI → Runs.
  - **Fewer duplicate runs:** an identical question joins the run already going; a finished answer is reused while the data is
    unchanged, also after a restart; repeated heavy questions produce **rollup suggestions** an admin accepts into the model's draft.
  - **Masks hold:** a run computes as its runner; a shared run is *the same*, *re-masked* (groups on a masked field collapse) or
    *recomputed as the recipient*, decided at every read. Tested in both directions.
  - **Across the cluster:** a heavy run executes on the node that owns the model; the list is merged from every node; a dead owner's
    unfinished runs are adopted by the next owner.
- **Chart any table** ([TABLE_CHARTS.md](docs/architecture/TABLE_CHARTS.md)): a Chart button (and K; Shift+K on history) on view
  tables, searches, pick lists, admin lists, history and BI table visuals; six types, Top N, stacking, two axes, PNG/SVG/CSV/Excel;
  masked columns are never plotted; every theme and a phone.
- **Delta and Iceberg side by side:** `build_demo_lakes.py --store endur=delta --store calypso=iceberg` (or `all=iceberg`); the right
  connector is switched per system; the same pages, histories, searches and BI answers either way (equality test). Windows QA guide 3.3.
- **Drishti*net:** heartbeats on a port of their own (`gossip-port`), so a busy server is never taken for dead; HTTP/2 between servers
  (one connection per peer; p99 117 ms against 370 ms on HTTP/1.1), falling back to HTTP/1.1 for an older peer.
- **2.6.6, the QA release:** a six-hour adversarial QA, four rounds and four soaks fixed about 100 findings, among them five P1s:
  masked values leaking across the history cache through a peer, a signal storm stalling the server, stale pages across console
  workers, a moved Iceberg table reading as healthy (a Java 25 difference), a rejoining node answering *not found*. The history
  cache is sized in real heap and defaults to 256 MB (`drishti.history.cache-mb`).
- **Known open:** SOAK4-01 (the busiest soak node's live heap rose 165 MB/h over 73 minutes; not the caches; not yet diagnosed);
  Analyse does not use runs yet; runs' Parquet/Arrow results and the second run engine; a real-crash test of run adoption.
- **Patched after the tag** (CHANGELOG, Unreleased): a fact table whose connector file lacked its kind showed no rows (now said, with
  the fix); as of a date each table reads its latest date on or before it, and the answer and the report say each table's date when
  they differ; a report's `as-of` setting (`per-table`, `aligned`, a date, a relative name); a report's visuals no longer fail
  `DRS-9025` when they queue as runs.
- **Tested:** the drill passed licence headers, Java 21, Java 25, the tools and the console; failures on the way were fixed (an
  undeclared Iceberg setting, two token scopes, a test race, and an HTTP/2 bug: a 413 carried `Connection: close`, which Java 25's
  client refuses). QA reports in `docs/qa/2026-10-20/` and `docs/qa/2026-10-21/`.

# Drishti 2.6.5 — release notes

*2026-10-20.* **History queries: type a range after an id and see the entity over time.** ([HISTORY_QUERIES.md](docs/architecture/HISTORY_QUERIES.md),
tutorial 21, [USER_GUIDE](docs/guides/USER_GUIDE.md#history-an-entity-over-time).)

- **The command:** `TRD 1000015 30` (the last 30 business days with data), `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD`,
  `FROM 2026-09-01 TO 2026-09-30`, and `DIFF` at the end for only what changed. Aliases keep their range; the type-ahead offers
  `30 — last 30 days` after an id; plain words such as `trade 1000015 last month` make the same command.
- **Generic:** any kind with dated data has a history, with no code per kind: a trade, a counterparty, a curve, an economy. A pack's
  `history:` block only shapes the view (columns, chart fields, headline numbers, limits), and `sutra lint` checks it.
- **The page:** one row per business day, the cells that changed from the day before highlighted with ▲/▼, restated days marked,
  *Changes only* (`C`), a change log (`D`), Enter opens the full page as it stood that day, CSV / Excel / JSON export, a link that
  pins the view. Market data (equities, indices, FX, bonds, curves) gets a chart with last, change, high, low, range and average;
  days before the store's first day come from the price history the document carries. Every theme; masks hold on every day's row.
- **Fast:** 30 days of one trade among 10,000 on Delta in 13 ms cold, under 1 ms warm, 0.3 ms from the cache. Fast paths for
  Delta, Redis, PostgreSQL, DuckDB, MongoDB, Aerospike, Cassandra and Iceberg; every other connector works through the default.
- **Across a cluster:** a node reads an entity's days from the peer that holds them in one call, masked on both nodes. *Fixed after
  the release (QA1-01, QA1-02):* the history cache follows the peers' data versions, so a load on the peer shows on the next request;
  a peer that is down is named (`DRS-5052`), one that does not answer in time is `DRS-1014`, never "no data", and neither is cached.
- **Also:** `drishti.py history KIND ID` and the Python client's `history()`; `GET /api/v1/history/{kind}/{id}`; tutorial 21;
  tutorial 20 completed; the Windows QA guide's history section.
- **Not in 2.6.5:** history as a BI data source (2.7.0); the iceberg-external fast path (the default path serves it).
- **Tested:** the drill passed Java 21, Java 25 and the tools; the console stage's failures were test drift (a stale element sheet,
  the tutorial count word, TOOLS.md missing `history` and `bi statement`), fixed and re-run green, and browser tests that passed
  on their re-run under load (`test_bi_python_browser`, `test_controls_browser`, carried to the adversarial QA).

# Drishti 2.6.0 — release notes

*2026-10-20.* **Paper statements and field parameters in BI, Cassandra and Iceberg without Hadoop, three new themes, and the
known bugs fixed.** (Wave D of [BI_DESIGN.md](docs/architecture/BI_DESIGN.md); the full list is in the [changelog](CHANGELOG.md).)

- **Paginated reports and statements:** a report page with `layout: statement` is paper: page-exact PDF (written by the
  server) and Excel set to print, headers and footers with tokens, page breaks, repeated column titles, groups kept
  together, and bursting through subscriptions, one statement per recipient, masked as them. The statement designer
  (Format → Layout → statement, the Paper tab) previews page by page ([STATEMENTS.md](docs/guides/REPORTS.md), tutorial 20).
- **Field parameters in a report's wells:** a reader's slicer switches which field or measure a visual draws.
- **Apache Cassandra connector** (`cassandra`): Cassandra 4 and 5, ScyllaDB, Astra; a day appears whole or not at all
  ([CASSANDRA_CONNECTOR.md](docs/connectors/CASSANDRA_CONNECTOR.md)).
- **drishti-iceberg:** Drishti's own Apache Iceberg engine with no Hadoop, so Iceberg runs on Windows; it is the default
  engine (`engine: library` brings back the old one), and faster: a connector starts in 637 ms (1.8 s before), a trade on a
  past day reads in 94 ms (353 ms). **`iceberg-external`** reads Iceberg tables Drishti did not write (Spark, Flink, Trino,
  Snowflake, Glue) ([DRISHTI_ICEBERG.md](docs/architecture/DRISHTI_ICEBERG.md), [ICEBERG_EXTERNAL_CONNECTOR.md](docs/connectors/ICEBERG_EXTERNAL_CONNECTOR.md)).
- **Three themes:** Green Terminal, Crimson Terminal and Blue Terminal (ten in all); every text colour passes the contrast
  rule on every ground, labels on button fills too.
- **Known bugs fixed:** a Drishti*net cluster never ends with no enabled administrator; members advertise only a real
  address; a BI query waits for a connector that is restarting; the report designer never saves a draft without its last
  change; Show the rows and Drill through use the selection at the click; embedded reports and Analyse wait as long as the
  server runs a query; flaky browser tests made steady under load.
- **Designs:** history queries (2.6.5, [HISTORY_QUERIES.md](docs/architecture/HISTORY_QUERIES.md)), Drishti on AWS
  ([AWS_DEPLOYMENT.md](docs/architecture/AWS_DEPLOYMENT.md)) and super scale, 500 GB a day for seven years
  ([SUPER_SCALE.md](docs/architecture/SUPER_SCALE.md)).
- **Roadmap:** 2.6.5 history queries → 2.7.0 write-back → 2.8.0 AI and the Sajha MCP connector; send and push and
  integration (PG-wire, Excel feed, embed tokens) after 3.0.0.
- **Known:** two browser tests still flaky under heavy load (`test_showcase_clean_browser[map]`, the embedded Pivot click
  in Firefox and WebKit), carried to 2.6.5. 2.6.0 was released on targeted tests; the full drill runs with 2.6.5.

# Drishti 2.5.0 — release notes

*2026-10-19.* **Drishti*net: several servers act as one Drishti, and the console carries on when one stops.** (Wave N of
[BI_DESIGN.md](docs/architecture/BI_DESIGN.md); operator's guide [NET.md](docs/admin/NET.md), tutorial 19.)

- **One cluster, off by default:** servers find each other by gossip over HTTP from one seed, keep the member list in
  `<data dir>/net/peers.json`, and publish what they serve (packs with versions, kinds, the tables they read). Configured
  like connectors, in its own folder `config/net/` (node, peers, gossip, routing, access), from the UI, the CLI
  (`drishti.py net`) or YAML.
- **The UI cannot tell which server answered:** a page for data held on another node is built here with the data fetched
  (masked on both nodes) or forwarded to a node that renders it; the newest version of a pack is served; a pack can be
  offered to peers only. Searches and grouped pages are gathered from every node a role may reach, replicas asked once,
  each entity counted once; a node that is down is named in a partial answer, never a wrong one. Live views and BI
  reports work across nodes; saved searches, layouts, pivots and sign-in sessions follow a user.
- **Security:** a cluster key (rotated with no downtime), short-lived on-behalf-of tokens, nodes per role, audit.
- **The console with several servers:** give it one, it learns the rest every minute and keeps the list; if its server
  stops it moves to another within seconds and the open page, live stream and sign-in carry on.
- **Admin → Net:** members with a hash ring of the cluster, the capability map, routing with *Explain*, access, security,
  the configuration files.
- **Measured on real processes:** three servers, 20 users for 10 minutes with servers killed and restarted: 0 wrong answers
  of 9,362 checked, the console moved in 0.27 s, nobody signed out ([NET.md section 13](docs/admin/NET.md)).
- **A ready-made local cluster:** `drishti-stack/` (start, stop, status; secrets stay local and are never committed).
- **No JVM fills `/tmp`:** every Java process Drishti and its build start gets its own temporary folder at launch; the
  server warns in Admin → Health when it was started without one.
- **News without the internet connector:** a switched-off news, quote or public-data connector's kinds are served from
  the committed snapshot ("off (served from the snapshot)").
- **Documents, help and the deck reviewed** for 2.3–2.5 (names, commands checked against the CLI, release order); the
  design-and-evidence deck brought up to date (48 slides).

Upgrading from 2.4.0: nothing to do for a single server (Drishti*net is off without `config/net/`); start the server with
`-Djava.io.tmpdir=<data dir>/tmp/server` (README); see [CHANGELOG.md](CHANGELOG.md).

# Previous release: Drishti 2.4.0 — release notes

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
- **Docs verified for this release:** [docs/qa/2.3.0-docs-verification.md](docs/qa/QA_HISTORY.md#5-2026-10-17-the-230-documentation-verification) lists
  every guide, tutorial and help page with what was checked and how to re-check it.

- **Offline demos:** `DRISHTI_OFFLINE=true` (set by the *Drishti server (demo lakes)* run configuration) starts no
  internet connector; quotes and news come from a committed snapshot (Yahoo Finance and Google News, captured
  2026-10-18) that `lake demo` builds into the lake. World economy data was already offline.
- **Ids are text everywhere:** a numeric `TRADE_ID` in a file is read as its exact text; ids may hold letters, dashes and
  underscores; nothing expects a prefix. Older lakes with numeric id columns still read.
- **[How to QA in Windows Environment using Custom Data](docs/qa/windows-custom-data.md):** an offline
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
- **Streaming BI (Rūpaka phase 5)** (docs/guides/REPORTS.md, REPORTS.md): live dataset tables kept in memory and updated by
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
