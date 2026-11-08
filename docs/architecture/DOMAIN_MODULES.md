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

> **What comes next is [KERNEL_AND_MODULES.md](KERNEL_AND_MODULES.md)** (2.9.11.i, design): the kernel, the signed module package, loading from
> module folders, kernel-controlled data binding and distributions. Section 6 step 9, section 7's D1 and D6 and section 8's *Phase 2, still to do*
> moved there (its section 12); this document stays the record of the split as built until that design's phase P2 completes.

**Status: phase 1 (the backend) built in 2.9.11.f: the Java modules split, the cycles J1 to J6 broken, the API under `/api/v1/risk`, the
packs naming their domain, the rules as tests. Phase 2's first half built: the console asks the server for modules and functions on every
door, static assets by module and lens, the console cycles C1 to C7 broken in place, the console on `/api/v1/risk` (section 8). The moves
(screens into the plugin folder, packs into domain folders, the banking configuration in its folders, editions folded into lenses)
are built (phase 2's second half, section 8); the thin jar is designed, not built.** The survey of sections 1 and 2 was taken on develop `b3f2af7c2` on 2026-11-07. The principle is the product owner's
([IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), the 2.9.11.e row and *Domains are modules*): the core knows no industry; each domain
is a set of modules owning its compute, services and API, console screens and assets, Help, tutorials and packs; what a person sees
follows role and lens; licences name modules. This document is the boundary list, the target layout, the dependency rules, the cycles
that exist today and the order of the move. The native libraries' module, which every domain shares, is [DRISHTI_NATIVE.md](ARCHITECTURE.md#25-drishti-native-native-maths-behind-abstractions-with-java-fallbacks).

## 1. The two sides

| Side | What belongs | How to tell |
|---|---|---|
| **Core** | everything generic: entities, packs and the pack loader, Sutras and Rachana, Pivot, BI, tables and models, lakes, loaders and the data inbox, connectors, search, Explain, approvals and changes, collaboration, security, the cluster, Flight, AI, the console shell, the generic compute (Python workers, the vector store, parallel and many-set sums, sharding, fan-out), `drishti-native` | it would serve a hospital, a utility or a ministry unchanged |
| **Banking** (the risk and banking domain) | VaR and ES (historical, parametric), the VaR playground content, backtesting, regulatory VaR reporting, the scenario matrix, stress windows, baskets; exposure and CCR (PFE, EEPE, what-if); FRTB-SA and sensitivities; ISDA SIMM; SA-CCR and CVA (planned); the MR and CCR cubes; the risk, trading, market and CRO lenses; the official risk results; equity analytics (beta, correlation); the trading, market-data, market-risk and counterparty-risk packs | it names a trade, a position, a risk factor, a desk or a regulation |
| **Economy** (no code of its own: a content module shipped with the core) | the world-economy and news-and-markets packs and the public sector's six (world-development, central-banks, national-statistics, oecd, official-news, gdelt-events), the World and Public sector lenses, the world landing, the IMF-based BI reports | public economic and public-sector data |
| **Capital markets** (no code of its own yet: a content module shipped with the core; the owner's choice, 2026-11-07) | the us-equities and funds packs; the market data's functions (curves, FX, vols, equities, bonds: `CRV` ... `EQV`); the market lens with banking | listed companies, funds, prices and market data |

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
| Editions (`config/lenses`) | 2 (default-packs, features) | 3 | 1 (world) |
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
| Architecture | `PARAMETRIC_VAR`, `SENSITIVITIES_AND_FRTB_SA`, `VAR_PLAYGROUND`, `DATA_ARCHITECTURE.md section 13` (its machinery is core: the document is split when it moves) |
| Help pages | the 19 listed under section `risk` and the banking pages of `analytics` (MR cube, funds) and `knowledge` (market risk, counterparty credit risk, trades and products, market data, companies, funds and ETFs) |
| Contracts | `CVA`, `FRTB`, `FRTB_DATA_STRUCTURES`, `PNL`, `SACCR`, `SIMM`, `TRADING_GROUPINGS`, `COLL`, `GRP`, `LIM`, `MC`, `NSET`, `STR`, `TVAL`, `WWR`, the banking packs' pages and samples |

`GROUPINGS_ANALYTICS_GUIDE` and tutorial 6 are mixed (generic groupings, equity beta and correlation): split when the analytics move.

### 2.4 Packs, configuration, engines and API

| Kind | Banking | Economy |
|---|---|---|
| Packs (`config/packs`) | `trading`, `market-data` (its commands are capital markets' functions; the pack stays banking's while the engines read it), `market-risk`, `counterparty-risk` | economy: `world-economy`, `news-and-markets`; capital markets: `us-equities`, `funds`; public sector: `world-development`, `central-banks`, `national-statistics`, `oecd`, `official-news`, `gdelt-events` (each filled daily by its feed); core reference data: `gleif` (section 7, D6) |
| Engines (`config/engines`) | `var`, `exposure`, `frtb-sa`, `simm`, `mr-cube`, `sensitivities` (core: `dataload`, `flight`, `memory-lake`, `openblas`, `pivot`) | |
| Editions | `risk`, `trading`, `market` | `world` |
| Lakes | `config/lakes/banking/var-official.yaml`, `ccr-official.yaml` | |
| BI content | reports `counterparty-risk`, `desk-wall`, `live-exposure`, `live-trading-desk`, `market-risk-var`, `pnl-vs-exposure`, `python-desk-risk`, `trades-overview`, `cashflow-ladder`; their models and tables under `config/tables/bi/`; notebooks and Python items on P&amp;L; `reports/annual-report-var.yaml`, `var-backtesting-chart.yaml`; fragments `pnl-distribution`, `risk-links`, `curve-overview`, `curve-table`, `cashflow-ladder` | reports `world-economics`, `region-economics` and their models |
| Server API | `/api/v1/var/**` (explorer, explain, matrix, playground, regulatory, shards, basket, stress-window), `/api/v1/exposure/**`, `/api/v1/frtb/**`, `/api/v1/sensitivities`, `/api/v1/risk-factors`, `/api/v1/simm/**`, `/api/v1/pivot/mr` | |
| Console API | `/var`, `/exposure`, `/frtb`, `/simm`, `/smat`, `/play`, `/api/var/**`, `/api/exposure/**`, `/api/frtb/**`, `/api/simm/**`, `/api/smat/**`, `/api/play/**`, `/api/home/trading/**`, the `MCUBE` word | |
| Tools | `varcli.py`, `expcli.py`, `frtbcli.py`, `simmcli.py` (and their tests), `tools/ccr/`, `tools/bench/var_bench.py` | the public-data refresh scripts of the economy packs |

To decide with the product owner: whether the **core** pack keeps its finance-flavoured entities (`book`, `ccp`, `clearing-account`,
`issuer`, `legal-entity`, `counterparty`, `counterparty-group`, `desk`, `trader`), or they move into a `banking-reference` pack and the
core pack keeps only `calendar` and the generic entities; and whether the **market** lens and edition are banking (trading desks) or
economy (markets and news).

## 3. The layout (as built in phase 1)

| Module | Holds | Depends on |
|---|---|---|
| `drishti-registry` | the access registry ([ACCESS_MODEL.md](ACCESS_MODEL.md)): `AccessRegistry`, `EmbeddedAccessRegistry`, `AccessStore`, `YamlAccessStore`; plain Java | Jackson only |
| `drishti-compute` | the generic compute of section 2.1 | core modules |
| `drishti-compute-banking` | `com.ash.drishti.banking.compute.{var,ccr,frtb,simm,analytics}` (was `compute.{var,ccr,frtb,simm,reactive.analytics}`), with their tests (the hot-file soak runs here alone); pure maths, no Spring | `drishti-compute` |
| `drishti-server` | the core server: every generic package, the SPIs the domains fill (section 5), `server.modules` (the registry in the server, `@ConditionalOnModule`, the API aliases, the module gate, the module switch over REST, `/api/v1/admin/modules`), and the economy content module's manifest and the core lenses (`META-INF/drishti/`) | core modules, `drishti-registry` |
| `drishti-banking` | `com.ash.drishti.banking.{var,exposure,frtb,simm,official}` (was `server.{var,exposure,frtb,simm,official}`), with their tests; `BankingAutoConfiguration` (`META-INF/spring/…AutoConfiguration.imports`, `@ConditionalOnModule("banking")`, a component scan of `com.ash.drishti.banking`); its manifest and lenses (`META-INF/drishti/`); the banking playground templates (`playground/{var,var-explain,exposure,frtb-sa,simm,saccr,fva}`); its connector plugins and official-results provider (`META-INF/services`); the API under **`/api/v1/risk/{var,exposure,frtb,simm,sensitivities,risk-factors,mr}`** | `drishti-server`, `drishti-compute-banking` |
| `drishti-app` | no code: the runnable jar (the core with the domain modules this build ships), written as before to `drishti-server/target/drishti-server-<version>-exec.jar`, so every launcher, script, Docker file and guide is unchanged; `loader.properties` (the external library folder) moved here | `drishti-server`, `drishti-banking` |
| `config/packs/*/pack.yaml` | each pack names its `domain:` (core, banking, economy); ids and folders unchanged | |
| `docs/tutorials`, `docs/guides` | stay where they are; Help groups them by module in phase 2 | |

The old API prefixes (`/api/v1/var`, `/exposure`, `/frtb`, `/simm`, `/sensitivities`, `/risk-factors`, `/pivot/mr`) are **answered as the new
ones for this release** (`ApiAlias` beans of the module, applied by the core's `ApiAliasFilter` before every other filter: the request is the
same request under the new path, answered with `Deprecation: true` and `Link: <new path>; rel="successor-version"`), then removed. A
rewrite rather than a `308` (D5): the console's HTTP client follows no redirect, and the console moves to the new paths with its plugin in
phase 2; a rewrite keeps every client, the method and the body unchanged.

## 4. The dependency rules

Rules 1 to 3 are enforced on the Java side by `ArchitectureRulesTest` (drishti-it: `coreNeverImportsADomain`, the two
`…DoesNotSeeAnotherDomain`, `domainComputeIsPlainJava`, `onlyTheRegistryReadsTheAccessModel`, `registryIsPlainJava`); the console import
guard and rule 4's string check come with phase 2:

1. **The core never imports a domain**: no class outside `com.ash.drishti.banking..` depends on one inside it; no console module outside
   `drishti-console/plugins/banking` imports one inside it; no core template includes a banking template.
2. **Domains never import each other**: `com.ash.drishti.banking..` and `com.ash.drishti.economy..` (and later `aml`) are disjoint.
3. **Domains reach the core only through its SPIs** (section 5) and public services; a domain may use anything public of the core.
4. **Strings count too**: the core names no banking word, engine id, source name or path in code (a static check over the core's
   sources for the banking words of section 2, with an allow list that starts empty).

## 5. The cycles today, and how each is broken

Core code that imported banking code (Java: imports and fully qualified names; console: Python imports and string knowledge). **J1 to J6
are broken (phase 1)**; C1 to C7 wait for the console plugin (phase 2):

| # | Where (core) | Uses (banking) | How to break it |
|---|---|---|---|
| J1 | `server.pivot.PivotConfiguration` | `VarPivotProvider`, `VarService`, `MrCubeExtras`, `MrCubeProperties`, `VarParametricProperties`, `FrtbService` | **built**: `server.pivot.PivotSourceProvider`; the pivot engine asks every one before the tabular provider; `MrCubeConfiguration` registers the VaR source with the MR lens |
| J2 | `server.caches.CachesConfiguration` | `VarCaches`, `ExposureCaches` | **built**: `server.caches.CacheGroup`, joined in `@Order` (VaR, then exposure, as before) |
| J3 | `server.capacity.CapacityController`, `CapacityLive` | `VarController` (its figures for "today against the plan") | **built**: `server.capacity.CapacityFigures` (its key, its figures and planner inputs, its rows); `VarCapacityFigures` |
| J4 | `server.api.HealthController` | `var.Hierarchies.skipped()` | **built**: `server.api.HealthFigures`; the banking module contributes `hierarchiesSkipped` |
| J5 | `packs.edition.Edition.WORDS` | the words `VAR`, `EXP` | **built**: `Edition.WORDS` holds the core's words; a module declares its functions in its manifest; an edition reads every word it names |
| J6 | `packs.OfficialReaders` | the lake forms `var-official`, `ccr-official` | **built**: `OfficialReaders.OfficialResults`, found with the `ServiceLoader` (it runs before any bean); `BankingOfficialResults` (the `config/lakes/*-official.yaml` files themselves move in phase 2, step 8) |
| C1 | `core/app.py` | includes nine banking routers by name | **built**: `drishti-console/plugins/<module>/plugin.yaml` lists the module's routers (in order), the files that register its hooks and its other code; `core/plugins.py` `include(app)` includes them where the banking routers were; a module's routes answer 404 while it is off |
| C2 | `routes/terminal_routes.py` | `redirect_for` and `retired_link` of eight banking route modules | **built**: `plugins.command(fn, needs=words)` and `plugins.retired(fn)`, filled by each banking route module; the terminal asks `plugins.redirect` and `plugins.retired_link` |
| C3 | `core/search.py` (was `routes/api_routes.py`) | `smat_command`, `play_command`, `var_suggestions` | **built**: `plugins.type_ahead(pattern, fn, needs)` and `plugins.hints(...)`, filled by the SMAT, PLAY and VAR routes |
| C4 | `routes/admin_routes.py` | `play_routes._stream` (the workbook streamer) | **built**: `core/workbook_stream.py` `stream(...)`, used by the VaR, FRTB-SA and SIMM playgrounds, why VaR moved and the capacity planner; `_stream` deleted |
| C5 | `routes/pivot_routes.py` | `mcube_routes.parse` (a preset's word) | **built**: `plugins.preset("mr", parse)` by the MR cube's routes; the Pivot page draws the MR lens's style, script and panes from the plugin's `screens.pivot` (`screen_parts()`), naming none |
| C6 | `routes/edition_routes.py` | `core/trading_landing`, `core/market_landing` | **built**: `plugins.landing_rule(fn)` (the trading role's landing, by `plugins/banking/routes/trading_routes.py`); the market landing's figures (`/api/home/market/{part}`) moved to the banking routes |
| C7 | `core/backend.py` | the VaR, FRTB, SIMM and exposure client methods | **built**: the core client's one `engine(method, path, ...)` call; the typed calls are the module's (`drishti-console/plugins/banking/risk_api.py`: `var`, `exposure`, `risk`, `mr`, `path`) on `/api/v1/risk`; `core/facts.py` reads an engine step's API root from the engine catalogue |

No banking code imports another domain (there is no other domain's code yet), and no core module imports banking code: in Java
`ArchitectureRulesTest`, in the console `tests/test_module_boundaries.py` (no core module imports a module's code, no core template
includes a module's template or names its static files, no core script or style names a module's file). Admin → Saved VaR baskets
(`/admin/scopes`) moved from the core's Admin routes to the VaR routes for the same rule. The generic packages that only *mention* VaR in their documentation (`compute.vector`, `compute.shard`, `server.shard`,
`server.playground`) import nothing of banking; their comments are reworded when the documentation is rewritten (2.9.11.g).

## 6. The move, step by step (after 2.9.11.d merges)

Each step is one branch with its gate; the VaR check stays exactly equal throughout; old paths keep working for one release.

Steps 2 (Java), 3 (Java), 4, 5 and 7 (the `domain:` key) are **built in phase 1**; 1's refreshed counts, 2 and 3 for the console, 6, 7's
folders, 8, 9 and 10 are phase 2 (section 8).

1. **Freeze and survey again**: rerun this survey on develop after d; refresh section 2's counts.
2. **Break the cycles in place** (section 5): the SPIs J1 to J6 and the console hooks C1 to C7, no file moved; all suites green.
3. **Add the rules** (section 4) with an allow list holding exactly what has not yet moved; the list only shrinks.
4. **`drishti-compute-banking`**: `git mv` the five compute packages into the new module under `com.ash.drishti.banking.compute`, their
   tests with them; `drishti-compute`'s description loses VaR. The VaR check and the compute suites.
5. **`drishti-banking`**: `git mv` the five server packages under `com.ash.drishti.banking`; the auto-configuration; the API moved to
   `/api/v1/risk/…` with `308` redirects from the old prefixes (one controller listing them, deleted in the next release); the CLIs,
   the console client and the examples switched to the new paths. The server and integration suites, the drill.
6. **The banking console plugin**: `git mv` the routes, templates, JavaScript, CSS, landings and their tests into
   the console's plugins/banking folder (planned); static assets served from the plugin's folder behind the entitlement check; Help's `risk`
   section and the engine catalogue entries moved into the plugin's files.
7. **Packs by domain**: `git mv` into `config/packs/core/core|banking|economy|capitalmarkets/`; the pack loader reads one level of domain folders and each
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
| D3 | Package root for domains | **as built**: `com.ash.drishti.banking` (`.compute` for its maths); `com.ash.drishti.economy` when it has code |
| D4 | Tutorials' location | stay in `docs/tutorials/` with their numbers; Help lists them by module (no broken link) |
| D5 | Old API paths | **as built**: answered as the new paths for one release (a rewrite with `Deprecation` and `Link`, section 3), then deleted |
| D6 | The us-equities, gleif and funds packs | **decided (owner, 2026-11-07)**: us-equities and funds are the **capitalmarkets** module's (later also the market data's prices, curves and vols); **gleif** is core reference data (legal entities every domain uses); economy keeps world-economy, news-and-markets and the public-sector data. The market-data pack stays banking's while the banking engines read it (it moves when banking can require capital markets); its commands are capital markets' functions already. The editions' pack lists are unchanged in phase 1 (the World edition still opens us-equities and funds: their functions follow capital markets): they change with the console's lens work (phase 2) |

## 8. Phase 1 as built, and what phase 2 must do

**Built (2.9.11.f, backend; the CLIs, tools and documents on the new paths too):** the modules of section 3; J1 to J6; the API under `/api/v1/risk` with the old prefixes answered; module
registration through the registry (`@ConditionalOnModule`, `404 DRS-1025` for an off module's API, its engines *not installed* with the
reason, its packs and connectors not loaded); functions in the manifests, the mnemonic switches decided by the registry, a function off
answering `404 DRS-1026`; packs naming their `domain:`; the rules of section 4 (Java) as `ArchitectureRulesTest`; the backend guarantee
(`BankingModuleOffTest`, `BankingModuleOnTest`); connector files written before the move keep naming the old engine classes for a release
(`META-INF/drishti/moved-classes.properties`, read by the reactive connectors' Java runner).

**Tests that moved with banking:** the banking packages' own; `ConnectorUpgradeTest` and `HierarchyUpgradeWithoutLettersTest` (they build
the VaR and exposure lakes; package `com.ash.drishti.server.connectors` kept, in drishti-banking's tests, with their fixtures); a
`TokenScopeGuardTest` beside the server's, over the banking API. Banking tests start the whole server through `BankingTestServer`
(`@SpringBootConfiguration` importing `DrishtiApplication`), with the server's test helpers from its test jar.

**Phase 2, first half, as built (2.9.11.f, the console; additive, no screen moved):**

- **The console asks the server** and nothing else. The public about answer lists the `modules` loaded at the server's start (polled with
  the edition and the engines, kept per console app: `core/modules.py` `State`); `GET /api/v1/modules`, as the person, answers the modules
  loaded and not (with why), the lenses open to them (with the modules and functions each needs) and `functions.off`: every function they
  may not use (its module off, an administrator's switch, from 2.9.11.i not granted), each with its module and the words to say (*VRES is
  not available to you: ask your administrator*). The console caches it per person (`ui.user_cache_seconds`, dropped on a change seen by
  any worker) and sets it per request; an older server that says nothing hides nothing.
- **Every door asks** `core/modules.py`: the routes (`core/edition_gate.py`: a module's pages and APIs 404), the words
  (`Edition.word_on`, `command_shown`, `typed_shown`: menus' `word:` and `cmd:` requirements, the Build home's, the landings' chips),
  menus, the Engines menu and the Admin strip (sitemap `domain:` tags on a menu, column or item, the innermost wins; `requires:
  module:<id>`), search and the type-ahead (`modules.allowed`), the command line (`/go`: typed anyway, the refusal in words), Help (a
  guide's module is its engine's, else its section's `domain:`; the section too), the Build home (a type's `domain`), keys (a module's
  keys are registered only by its own pages, which are refused) and the static files.
- **Each module's console part** is `drishti-console/plugins/<module>/plugin.yaml`: `routers` (included in order, C1), `registers` (files
  that fill the hooks on import), `code` (its other Python modules), `engines` (their words, pages, menus, Help and tiles from the engine
  catalogue), `words`, `paths`, `templates`, `assets` (globs under `web/static`) and `screens` (what it adds to a core screen while it is
  on: the Pivot's MR lens). banking and economy have one; capitalmarkets has none (its functions are its packs' commands, which the
  server stops offering).
- **Static assets by module and lens**: a module's file is served only while the module is on for the person and, when they work in a
  lens or an edition, only when that lens uses the module (the World lens gets no banking file; `core/app.py`
  `_module_asset_refused`: only a module's file costs the session check). The Elements manifest carries each library's `module`
  (drishti-risk: banking) and the loader refuses a library of a module the page does not list (`<meta name="drishti-modules">`, from the
  person's modules and lens; `configure({modules})`; `refused(name)`), and every library that requires it.
- **C1 to C7 broken in place** (section 5), and the console on `/api/v1/risk` (its client, the workbook streams, the MR cube's extras);
  the old aliases stay one release for other clients.
- **Tests**: `test_modules_off.py` (banking off: pages, APIs, menus, the Engines menu, the Admin strip, the command line, the type-ahead,
  search, Help, the Build home, the files and the page's module list; banking on draws them again; an older server hides nothing;
  capital markets off: its functions refused and not offered; single functions off (VRES, SMAT): their menu items, command, type-ahead and
  pages gone, VAR kept; a lens without the module gets none of its files), `test_module_boundaries.py` (static: the plugins name files
  that exist; the core imports no module's code; modules do not import each other; no core template includes a module template or names
  its files; no core script or style names a module file), the Elements loader's refusal (`tests/elements/core.test.mjs`), and on the
  server `BankingModuleOffTest` / `BankingModuleOnTest` (the console's answers: `/api/v1/modules` and `/public/about`).

**Phase 2, second half, as built so far (2.9.11.f):**

- **The console plugin's moves** (with `git mv`, so later merges follow): the banking routes (`drishti-console/plugins/banking/routes`: VaR, regulatory
  VaR, SMAT, FRTB-SA, SIMM, the playgrounds, exposure and the pre-deal check, the MR cube, the trading and market landings' figures), its
  landings' code and Build types (`trading_landing.py`, `market_landing.py`, `build_types.py`, `risk_api.py` in `drishti-console/plugins/banking`), its templates
  (`drishti-console/plugins/banking/templates`, the same names: the console searches each module's folder after `web/templates`) and its scripts and
  styles (`drishti-console/plugins/banking/static`, served at the same `/static/...` addresses after `web/static`, gated by module and lens); the World
  landing into `drishti-console/plugins/economy`. drishti-risk stays an Elements library under `web/static/elements` (its manifest names its module).
- **The engine catalogue by module**: the banking engines' entries are drishti-banking's `META-INF/drishti/engines.yaml` (read before the
  core's `engines/catalogue.yaml`; absent when the module is not installed), their jar files `engines/<id>.yaml` are its own (imported
  `optional:` by `application.yaml`), and the console's copies are `plugins/banking/engines.yaml` and `config/engines.yaml` (the core's),
  byte for byte (`EngineCatalogueTest`, `BankingEngineCatalogueTest`). A core engine's soft use of a module's engine (the pivot uses VaR)
  counts only where the module is installed.
- **Rule 4 (strings)**: `tools/domain_strings.py` counts the banking names (command words, engine ids, connectors, API and console paths,
  packages) in the core's code (Java outside the banking modules; the console's core, routes, scripts, styles and templates) against
  `tools/domain_strings_allowed.yaml`, which only shrinks (a quick file check, `tools/test_domain_strings.py`).

**Phase 2, second half, as built (2.9.11.f, the configuration):**

- **The banking token declarations** are the module's: `META-INF/drishti/token-scopes.yaml` in its jar (`module:`, `read-posts:`,
  `scopes:`, the shapes of `drishti.security.token-read-posts` and `token-scopes`), merged by the core's `ModuleTokenScopes` into the
  `TokenScopes` bean only while the module is on (Spring cannot split one list across files). `risk:write` is the banking module's; the
  core's `compute:write` keeps the sandboxes and cancellations. **Help's Risk measures section** is the plugin's
  (`drishti-console/plugins/banking/help.yaml`: `help-sections:` heads with `after:`, and `sections:` pages), merged by `core/modules.py` (`help_heads`,
  `help_pages`) into the sitemap's heads and `help.yaml`'s pages.
- **Packs in domain folders** (step 7): `config/packs/<domain>/<pack>`: core (core, gleif, compute-demo), banking (trading, market-data,
  market-risk, counterparty-risk), economy (world-economy, news-and-markets), capitalmarkets (us-equities, funds). **One rule** says where a
  pack is: a packs root holds packs directly or one level down in domain folders (a folder with no `pack.yaml` of its own); in Java
  `com.ash.drishti.api.PackFolders` (`roots`, `all`, `list`, `of`: plain Java in drishti-api, so the pack loader, the reloader and its
  watchers, Admin -> Packs, deploy and roll back, the Sutra CLI and the lake files' pack tables share it), in Python
  `drishti-console/core/pack_paths.py` (`roots`, `pack_dirs`, `pack_dir`, `manifests`: the console, the tools, the generators and the tests).
  A pack's `domain:` names its folder (`tools/test_pack_layout.py`). The default packs are `config/packs/default-packs.yaml`, by module.
- **Banking configuration** (step 8): a content folder holds a domain's files one level down, and a folder named after a module is read
  only while the module is on (`server.modules.DomainFolders`): `config/lakes/banking/{var,ccr}-official.yaml` (`Lakes.files` and
  `Lakes.file` read a lake file in a domain folder; the jar copies are drishti-banking's, listed in its `drishti/lakes/shipped.txt`, so
  `Lakes.SHIPPED` is the core's demo lake plus the modules' on the class path), the regulatory report definitions
  `config/reports/banking/`, the BI files `config/bi/{reports,python,notebooks}/banking/` (`BiFolder`) and the tables and models
  `config/tables/banking/` (`Catalogue.load(root, store, domainOn)`; the banking tables are `banking.<name>`). With banking off, BI lists
  none of its reports, tables or models. The economy samples stay in `config/tables/bi/` and `config/bi/reports/`.
- **Editions folded into lenses** (step 5 of the list before): `config/editions/` is gone. A lens's file is
  `config/lenses/<module>/<lens>.yaml` (risk, trading, market: banking; world and public sector: economy), the feature presets
  `config/lenses/features.yaml` (`drishti.lens-dir`, `DRISHTI_LENS_DIR`; the console's `lens.dir`); a lens names its packs by module
  (`packs.enabled: {economy: [...], capitalmarkets: [...]}`), so World is economy with capital markets: while capitalmarkets is off its
  packs, functions, cards and the World landing's company, filing and fund parts are gone (`module_on` in the templates). The registry's lens
  carries its Home cards and chips (`cards:`, `chips:` in its module's `META-INF/drishti/lenses.yaml`, answered per person by
  `/api/v1/modules`; each shown only while its function is on), and the lens file its landing, menus, Help, Admin pages, BI lists and
  branding; `drishti.edition` (a lens for the
  whole instance) and `drishti.lenses` are unchanged ([LENSES.md](../guides/ADMIN_GUIDE.md#12-what-a-lens-is), *Editions are lenses of modules*).

**Phase 2, still to do** (carried into [KERNEL_AND_MODULES.md](KERNEL_AND_MODULES.md) section 14, phases P1 and P2):

1. **The thin jar and the plugins folder** (step 9): `drishti-app` builds the core jar alone; the banking module (drishti-banking with
   drishti-compute-banking) is a module jar in a site's modules folder on the launcher's `loader.path`, and the registry's *installed*
   follows the jar. **Deferred out of this release** (a packaging change across every launcher: the scripts, Docker, ECS, the stack and
   the drill gain the modules folder in the same change, and the release ships both jars); until then a module is installed with the one
   jar and switched off with `config/modules/<module>.yaml`.
2. **Python worker code**: none of banking's is left in `drishti-compute/src/main/python/drishti_compute`; the tools' banking scripts
   (`varcli.py`, `expcli.py`, `frtbcli.py`, `simmcli.py`, `tools/ccr/`) move with the module.
3. **The drills**: one with the banking module absent (the core starts, serves BI, Pivot and the economy packs, and says *not
   installed* for a banking word).
