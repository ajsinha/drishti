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

# Domain modules: the boundary between the core and banking

**Status: design for 2.9.11.e (surveyed 2026-11-07 on develop `b3f2af7c2`); nothing has moved.** The principle is the product owner's
([IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), the 2.9.11.e row and *Domains are modules*): the core knows no industry; each domain
is a set of modules owning its compute, services and API, console screens and assets, Help, tutorials and packs; what a person sees
follows role and lens; licences name modules. This document is the boundary list, the target layout, the dependency rules, the cycles
that exist today and the order of the move. The native libraries' module, which every domain shares, is [DRISHTI_NATIVE.md](DRISHTI_NATIVE.md).

## 1. The two sides

| Side | What belongs | How to tell |
|---|---|---|
| **Core** | everything generic: entities, packs and the pack loader, Sutras and Rachana, Pivot, BI, tables and models, lakes, loaders and the data inbox, connectors, search, Explain, approvals and changes, collaboration, security, the cluster, Flight, AI, the console shell, the generic compute (Python workers, the vector store, parallel and many-set sums, sharding, fan-out), `drishti-native` | it would serve a hospital, a utility or a ministry unchanged |
| **Banking** (the risk and banking domain) | VaR and ES (historical, parametric), the VaR playground content, backtesting, regulatory VaR reporting, the scenario matrix, stress windows, baskets; exposure and CCR (PFE, EEPE, what-if); FRTB-SA and sensitivities; ISDA SIMM; SA-CCR and CVA (planned); the MR and CCR cubes; the risk, trading, market and CRO lenses; the official risk results; equity analytics (beta, correlation); the trading, market-data, market-risk, counterparty-risk, funds, us-equities and gleif packs | it names a trade, a position, a risk factor, a desk or a regulation |
| **Economy** (next domain; no code of its own today) | the world-economy and news-and-markets packs, the world lens and its landing, the IMF-based BI reports | public and economic data |

## 2. The boundary list

Counts are files (or entries) as surveyed; "core" is everything not listed under banking or economy.

| Kind | Core | Banking | Economy |
|---|---|---|---|
| Java packages (main) | 194 | 10 (185 files, 55,261 lines) | 0 |
| Console route modules | 61 | 9 | 0 |
| Console core modules | 89 | 2 | 0 |
| Templates | 152 | 15 | 1 |
| JavaScript (`drishti_*.js`) | 191 | 22 | 0 |
| CSS (`drishti_*.css`) | 72 | 14 | 0 |
| Guides (`docs/guides`) | 25 | 7 | 0 |
| Tutorials | 29 | 9 | 1 |
| Lenses (`docs/lenses`) | 2 (CIO, oversight) | 6 | 1 (world) |
| Help pages (`help.yaml`) | 56 | 19 | 0 (pack guides) |
| Packs | 2 (core, compute-demo) | 7 | 2 |
| Engine files (`config/engines`) | 5 | 6 | 0 |
| Editions (`config/editions`) | 2 (default-packs, features) | 3 | 1 (world) |
| Configuration files (outside packs, connectors, engines, editions) | 28 | 51 | 6 |
| Data contracts (`docs/contracts`) | 12 | 87 (4 of them the core pack's finance entities: question D1) | 19 |
| API prefixes (server) | about 100 | 6 | 0 |

### 2.1 Java

| Module today | Banking packages | Core packages |
|---|---|---|
| `drishti-compute` | `compute.var` (25), `compute.ccr` (7), `compute.frtb` (12), `compute.simm` (7), `compute.reactive.analytics` (2: beta, correlation) | `compute` (the supervisor), `approval`, `arrow`, `cluster`, `env`, `reactive` (the reactive-connector machinery), `reactive.samples`, `sched`, `shard`, `vector` (the vector store, `MultiSums`, `ParallelSums`, `OrdinalSet`: generic sums over rows of one width), `worker` |
| `drishti-server` | `server.var` (89), `server.exposure` (23), `server.frtb` (8), `server.simm` (8), `server.official` (4: the official end-of-day risk results sinks) | the other 73 packages, among them `bi.*`, `pivot`, `tables`, `api`, `security`, `net`, `edit`, `collab.*`, `engines`, `nativelib`, `caches`, `capacity`, `shard` (sharding for any measure), `playground` (the generic workbook streamer every metric's playground uses) |
| every other module | none | all (`drishti-api`, `-common`, `-rachana`, `-engine`, `-bicompute`, `-packs`, the lake modules, `-net`, `-flight`, `-ai`, `-native`, the plugins) |

`drishti-native`'s `nativ.linalg` (BLAS) is core: VaR is its first caller, not its owner.

### 2.2 Console (`drishti-console`)

| Kind | Banking |
|---|---|
| Routes | `var_routes`, `var_regulatory_routes`, `exposure_routes`, `frtb_routes`, `simm_routes`, `smat_routes`, `play_routes` (the VaR, FRTB and SIMM playgrounds), `mcube_routes`, `trading_routes` |
| Core modules | `trading_landing`, `market_landing` |
| Templates | `terminal/var.html`, `var_backtesting.html`, `var_reports.html`, `var_statistics.html`, `exposure.html`, `frtb.html`, `simm.html`, `smat.html`, `play.html`, `stress_window.html`, `_mr_lens.html`, `_mr_panes.html`; `home/risk_landing.html`, `trading_landing.html`, `market_landing.html` |
| JavaScript | `drishti_var-*` (backtesting, basket, basket-sheet, charts, explain, explorer, layout, parametric, playground, reports), `drishti_exposure-charts`, `drishti_exposure-explorer`, `drishti_frtb-sa`, `drishti_simm`, `drishti_scenario-matrix`, `drishti_smat-link`, `drishti_play-link`, `drishti_stress-window`, `drishti_mr-lens`, `drishti_risk-landing`, `drishti_trading-landing`, `drishti_market-landing` |
| CSS | `drishti_var`, `drishti_var-basket`, `-layout`, `-parametric`, `-playground`, `-regulatory`, `drishti_exposure`, `drishti_frtb-sa`, `drishti_scenario-matrix`, `drishti_stress-window`, `drishti_mr-lens`, `drishti_risk-landing`, `drishti_trading-landing`, `drishti_market-landing` |
| Configuration | `config/engines.yaml` entries `var`, `exposure`, `sensitivities`, `frtb-sa`, `simm`, `mr-cube`; `pivot-presets.yaml` preset `mr`; the risk words in `menus.yaml`; `help.yaml` section `risk` |
| Economy | `home/world_landing.html` |

Everything else (the shell, BI, Pivot, Build, designer, admin, Help, embed, phone, alerts, monitor, workspace, data, history) is core.

### 2.3 Documentation and Help

| Kind | Banking |
|---|---|
| Guides | `VAR_GUIDE`, `REGULATORY_VAR_REPORTING`, `EXPOSURE_GUIDE`, `FRTB_SA_GUIDE`, `SIMM_GUIDE`, `MR_CUBE_GUIDE`, `FUNDS_GUIDE` |
| Tutorials | 24 (correct a trade: a core feature on banking data; moves with banking until it has a neutral twin), 25, 26, 28, 29, 30, 32, 38, 43 |
| Lenses | `RISK_LENS`, `CRO_LENS`, `TRADING_LENS`, `MARKET_LENS`, `MR_CUBE_LENS`, `CCR_CUBE_LENS` |
| Architecture | `PARAMETRIC_VAR`, `SENSITIVITIES_AND_FRTB_SA`, `VAR_PLAYGROUND`, `VAR_SHARDING` (its machinery is core: the document is split when it moves) |
| Help pages | the 19 listed under section `risk` and the banking pages of `analytics` (MR cube, funds) and `knowledge` (market risk, counterparty credit risk, trades and products, market data, companies, funds and ETFs) |
| Contracts | `CVA`, `FRTB`, `FRTB_DATA_STRUCTURES`, `PNL`, `SACCR`, `SIMM`, `TRADING_GROUPINGS`, `COLL`, `GRP`, `LIM`, `MC`, `NSET`, `STR`, `TVAL`, `WWR`, the banking packs' pages and samples |

`GROUPINGS_ANALYTICS_GUIDE` and tutorial 27 are mixed (generic groupings, equity beta and correlation): split when the analytics move.

### 2.4 Packs, configuration, engines and API

| Kind | Banking | Economy |
|---|---|---|
| Packs (`config/packs`) | `trading`, `market-data`, `market-risk`, `counterparty-risk`, `funds`, `us-equities`, `gleif` | `world-economy`, `news-and-markets` |
| Engines (`config/engines`) | `var`, `exposure`, `frtb-sa`, `simm`, `mr-cube`, `sensitivities` (core: `dataload`, `flight`, `memory-lake`, `openblas`, `pivot`) | |
| Editions | `risk`, `trading`, `market` | `world` |
| Lakes | `lakes/var-official.yaml`, `lakes/ccr-official.yaml` | |
| BI content | reports `counterparty-risk`, `desk-wall`, `live-exposure`, `live-trading-desk`, `market-risk-var`, `pnl-vs-exposure`, `python-desk-risk`, `trades-overview`, `cashflow-ladder`; their models and tables under `config/tables/bi/`; notebooks and Python items on P&amp;L; `reports/annual-report-var.yaml`, `var-backtesting-chart.yaml`; fragments `pnl-distribution`, `risk-links`, `curve-overview`, `curve-table`, `cashflow-ladder` | reports `world-economics`, `region-economics` and their models |
| Server API | `/api/v1/var/**` (explorer, explain, matrix, playground, regulatory, shards, basket, stress-window), `/api/v1/exposure/**`, `/api/v1/frtb/**`, `/api/v1/sensitivities`, `/api/v1/risk-factors`, `/api/v1/simm/**`, `/api/v1/pivot/mr` | |
| Console API | `/var`, `/exposure`, `/frtb`, `/simm`, `/smat`, `/play`, `/api/var/**`, `/api/exposure/**`, `/api/frtb/**`, `/api/simm/**`, `/api/smat/**`, `/api/play/**`, `/api/home/trading/**`, the `MCUBE` word | |
| Tools | `varcli.py`, `expcli.py`, `frtbcli.py`, `simmcli.py` (and their tests), `tools/ccr/`, `tools/bench/var_bench.py` | the public-data refresh scripts of the economy packs |

To decide with the product owner: whether the **core** pack keeps its finance-flavoured entities (`book`, `ccp`, `clearing-account`,
`issuer`, `legal-entity`, `counterparty`, `counterparty-group`, `desk`, `trader`), or they move into a `banking-reference` pack and the
core pack keeps only `calendar` and the generic entities; and whether the **market** lens and edition are banking (trading desks) or
economy (markets and news).

## 3. The target layout

| Module | Holds | Depends on |
|---|---|---|
| `drishti-compute` | the generic compute of section 2.1 | core modules, `drishti-native` |
| `drishti-compute-banking` | `com.ash.drishti.banking.compute.{var,ccr,frtb,simm,analytics}` | `drishti-compute`, `drishti-native` |
| `drishti-banking` | the services and controllers of `server.{var,exposure,frtb,simm,official}` as `com.ash.drishti.banking.{var,exposure,frtb,simm,official}`, a Spring Boot auto-configuration (`META-INF/spring/…AutoConfiguration.imports`) so the server finds it on the classpath or in the plugins folder; the API under **`/api/v1/risk/{var,exposure,frtb,simm,sensitivities,risk-factors,mr}/…`**, the old paths answered with `308` to the new for one release, then deleted | `drishti-server` (its SPIs, section 5), `drishti-compute-banking` |
| the banking console plugin (`drishti-console/plugins/banking/`) | its routes, templates, `static/` (served under `/static/banking/` only to people whose role or lens uses them: by lens in 2.9.11.f, by role in 2.9.11.h), its `help.yaml` section, its `engines.yaml` entries, its words and pivot presets | the console core's plugin hooks (section 5) |
| `config/packs/{core,banking,economy}/` | the packs, grouped; each `pack.yaml` names its `domain:`; ids unchanged, so links and saved items keep working | |
| `docs/tutorials`, `docs/guides` | stay where they are; Help groups them by the module that owns them (one line per page in the module's `help.yaml`), so no link breaks | |

The server jar becomes thin: the core, with a plugins folder from which `drishti-banking` (and later `drishti-economy`, `drishti-aml`) is
loaded; module licensing (2.9.11.h) decides which load at all.

## 4. The dependency rules

Enforced by `ArchitectureRulesTest` (drishti-it) once the first move lands, and by a console import guard beside
`tests/test_java_launch_guard.py`:

1. **The core never imports a domain**: no class outside `com.ash.drishti.banking..` depends on one inside it; no console module outside
   `plugins/banking/` imports one inside it; no core template includes a banking template.
2. **Domains never import each other**: `com.ash.drishti.banking..` and `com.ash.drishti.economy..` (and later `aml`) are disjoint.
3. **Domains reach the core only through its SPIs** (section 5) and public services; a domain may use anything public of the core.
4. **Strings count too**: the core names no banking word, engine id, source name or path in code (a static check over the core's
   sources for the banking words of section 2, with an allow list that starts empty).

## 5. The cycles today, and how each is broken

Core code that imports banking code today (Java: imports and fully qualified names; console: Python imports and string knowledge):

| # | Where (core) | Uses (banking) | How to break it |
|---|---|---|---|
| J1 | `server.pivot.PivotConfiguration` | `VarPivotProvider`, `VarService`, `MrCubeExtras`, `MrCubeProperties`, `VarParametricProperties`, `FrtbService` | a `PivotSourceProvider` SPI in `server.pivot`; the `var` source and the MR cube register their provider from their own configuration |
| J2 | `server.caches.CachesConfiguration` | `VarCaches`, `ExposureCaches` | a `CacheGroup` SPI: each engine contributes its caches to Admin → Caches as a bean |
| J3 | `server.capacity.CapacityController`, `CapacityLive` | `VarController` (its figures for "today against the plan") | a `CapacityFigures` SPI; the VaR engine reports its positions and dates |
| J4 | `server.api.HealthController` | `var.Hierarchies.skipped()` | a `HealthContributor` SPI (the shape `nativelib` already has); VaR and exposure contribute their skipped hierarchies |
| J5 | `packs.edition.Edition.WORDS` | the words `VAR`, `EXP` | each module declares its words; the edition reads the loaded modules' words |
| J6 | `packs.OfficialReaders` | the lake forms `var-official`, `ccr-official` | the official readers are listed by the module that owns them (`lakes/*.yaml` moves with banking) |
| C1 | `core/app.py` | includes nine banking routers by name | a plugin loader: `plugins/*/plugin.py` declares its routers, templates folder, static folder, words and Help; `app.py` includes what is enabled |
| C2 | `routes/terminal_routes.py` | `redirect_for` and `retired_link` of eight banking route modules | a redirect registry the plugins fill |
| C3 | `routes/api_routes.py` | `smat_command`, `play_command`, `var_suggestions` | a command-word registry (word → handler, suggestions) the plugins fill |
| C4 | `routes/admin_routes.py` | `play_routes._stream` (the workbook streamer) | move `_stream` into a core module (`core/workbook_stream.py`); FRTB, SIMM and VaR playgrounds use it from there |
| C5 | `routes/pivot_routes.py` | `mcube_routes.parse` (a preset's word) | the preset's parser registered with the pivot presets by the plugin |
| C6 | `routes/edition_routes.py` | `core/trading_landing`, `core/market_landing` | landings registered by the plugin (lens → landing) |
| C7 | `core/backend.py` | the VaR, FRTB, SIMM and exposure client methods | a generic `engine(path)` call in the core client; the typed helpers move into the plugin |

No banking code imports another domain (there is no other domain's code yet), and no core module beyond those above imports banking
code. The generic packages that only *mention* VaR in their documentation (`compute.vector`, `compute.shard`, `server.shard`,
`server.playground`) import nothing of banking; their comments are reworded when the documentation is rewritten (2.9.11.g).

## 6. The move, step by step (after 2.9.11.d merges)

Each step is one branch with its gate; the VaR check stays exactly equal throughout; old paths keep working for one release.

1. **Freeze and survey again**: rerun this survey on develop after d; refresh section 2's counts.
2. **Break the cycles in place** (section 5): the SPIs J1 to J6 and the console hooks C1 to C7, no file moved; all suites green.
3. **Add the rules** (section 4) with an allow list holding exactly what has not yet moved; the list only shrinks.
4. **`drishti-compute-banking`**: `git mv` the five compute packages into the new module under `com.ash.drishti.banking.compute`, their
   tests with them; `drishti-compute`'s description loses VaR. The VaR check and the compute suites.
5. **`drishti-banking`**: `git mv` the five server packages under `com.ash.drishti.banking`; the auto-configuration; the API moved to
   `/api/v1/risk/…` with `308` redirects from the old prefixes (one controller listing them, deleted in the next release); the CLIs,
   the console client and the examples switched to the new paths. The server and integration suites, the drill.
6. **The banking console plugin**: `git mv` the routes, templates, JavaScript, CSS, landings and their tests into
   `drishti-console/plugins/banking/`; static assets served from the plugin's folder behind the entitlement check; Help's `risk`
   section and the engine catalogue entries moved into the plugin's files.
7. **Packs by domain**: `git mv` into `config/packs/core|banking|economy/`; the pack loader reads one level of domain folders and each
   pack's `domain:`; Admin → Packs, Help and the Build home group by it; editions unchanged (they name pack ids).
8. **Banking configuration**: the official lakes, the banking BI reports, models and tables (after d's tables settled), fragments and
   notebooks into the banking folders the loader reads.
9. **The thin jar and the plugins folder**: the server's runnable jar without `drishti-banking`; the release ships both, and a
   site's `plugins/` holds the modules it runs.
10. **Documentation and drills**: this document's status to built; the guides' module notes; the CHANGELOG; two drills, one with the
    banking module absent (the core must start, serve BI, Pivot and the economy packs, and say "not installed" for a banking word).

## 7. Open questions

| # | Question | Proposal |
|---|---|---|
| D1 | The core pack's finance entities (section 2.4) | a `banking-reference` pack in banking; the core pack keeps the calendar and generic entities |
| D2 | The market lens and edition | banking (trading desks) |
| D3 | Package root for domains | `com.ash.drishti.banking`, `com.ash.drishti.economy` (a rename in the move; the ArchUnit rules key on it) |
| D4 | Tutorials' location | stay in `docs/tutorials/` with their numbers; Help lists them by module (no broken link) |
| D5 | Old API paths | `308` for one release (keeps the method and body), then deleted |
