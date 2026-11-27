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
# Drishti Risk: the risk engine as a product

**Status: design, not built (the engine API, kit and seam are built: see ENGINES_IN_DRISHTI.md). Written 2026-11-21. Nothing built, nothing run, no decision taken beyond the owner's of this
date.** Read from git only: Drishti `main` at `9ce7c3170`; the Lake design and the i7b products plan (branch wip/i7b-products); the Rachana library, live-table and Data Protocol designs on
`origin/g2/integration`. Line counts are `wc -l` over `src/main` and `src/test` on that commit; groups marked "about" were sorted
by imports (Spring, host packages), not file by file: step K0 reviews the list file by file. Timings are quoted from
`docs/admin/BENCHMARK.md`; nothing was measured for this document.

**The owner's decisions (2026-11-21).** Release **i9** builds the product and puts Drishti on it, one drill at the end. One
product: a shared risk kernel and engine modules (market risk first, then counterparty credit, liquidity, climate; non-financial
risk last, after its own design check). The engine never prices. Every risk number is computed in Java. In-process library
first; a separate engine process only if measurements or a clear need justify it.

**Amended 2026-11-22 (the owner's acceptance test for engines, [ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) section 0).** Drishti Risk reaches a Drishti
server as **one engine JAR copied into the server's lib folder**, never as a compile-time dependency. Section 0a says what that
changes here; text it supersedes is marked DELETED where it stood (git keeps it). By the owner's later decision the product is
built in the combined i8 (milestones R1 and R2), not in a release of its own; the step lists of sections 9 to 11 stand.

## Contents

0. [The answer in one page](#0-the-answer-in-one-page) · 0a. [Delivery as one engine JAR](#0a-delivery-one-engine-jar-in-the-servers-lib-folder) ·
1. [Positioning](#1-positioning) ·
2. [Kernel and engine modules](#2-the-kernel-and-the-engine-modules) · 3. [Contracts](#3-input-and-output-contracts) ·
4. [API and SPI](#4-public-api-and-module-spi) · 5. [What moves, what stays](#5-what-moves-what-stays) ·
6. [Process model](#6-the-process-model) · 7. [Proof of accuracy](#7-proof-of-accuracy) ·
8. [Repository and coordinates](#8-repository-layout-and-coordinates) · 9. [The move](#9-the-move-step-by-step) ·
10. [Drishti's migration](#10-drishtis-migration) · 11. [Estimate](#11-estimate) · 12. [Roadmap](#12-the-roadmap-after-i9) ·
13. [Risks](#13-risks) · 14. [Decisions](#14-decisions-rk-1-to-rk-20)

## 0. The answer in one page

**The engine is already two halves, and the split follows that line.** `modules/banking` holds 61,353 main lines in two Maven
modules:

| | Lines main / test | Names the host? | Fate |
|---|---|---|---|
| `banking-compute` (the arithmetic: `VarBook`, `VarMath`, `ParametricVar`, `ExposureMath`, FRTB, SIMM, scope grammar, hierarchies) | 15,341 / 7,429 | 0 Spring files; 20 host types, all from `compute.vector`, `compute.linalg`, `api.compute`, `DataNode` | **moves whole** |
| `banking-service` (sessions, loaders, tiers, captures, official record, explain, playgrounds, reports, screens, controllers) | 46,012 / 21,696 | 36 Spring files (5,765 lines: controllers, properties, configuration); 132 imports of `api.error`, 63 of `kernel.api.security` | **about 31,500 move, about 14,500 stay as adapters** |
| `compute.vector`, `compute.linalg` in `drishti-compute`; the playground workbook framework in `drishti-server` | 4,024 + 989 / 1,485 + its tests | used by banking only (39 + 2 files), the server's native-library start-up and benchmarks | **moves** (the risk kernel) |

**About 52,000 main and 24,000 test lines of Java leave Drishti; about 14,500 stay in the banking module as adapters, and about
1,500 lines of host adapter are new.** The banking module's Java falls from 61,353 to about 16,000 lines (section 5.4).

**Three knots.**

1. **Inputs arrive as the host's documents.** `VarLoader` (1,424) and `ExposureLoader` (590) read `DataNode` and
   `EntityDocument` through `SourceRouter`. The product must take rows of a standard contract through its own port. The intake is
   where a number can move, so this is the deep step (K5) and the one the goldens watch hardest.
2. **The caller's identity is inside the engine.** `Principal` is passed into `VarService`, `VarViews`, `VarExplain`,
   `VarCaptures`, `ExposureService`. The product takes a **visibility** (a scope the host has already decided the caller may see)
   and an actor name for the audit line; access rules stay in Drishti.
3. **The kernel is not yet a thing.** VaR and exposure each have their own book, hot file, tier, captures and official record
   (`VarBook`/`ExposureBook`, `VarHotFile`/`ExposureHotFile`, `VarTier`/`ExposureTier`). i9 names as kernel only what is
   **already shared** (vectors, BLAS, scopes, hierarchies, limits, method choices, workbook, explain). Folding the two books into
   one is not i9 work (RK-6): it would be a rewrite inside a release whose only promise is "the same numbers".

**Process model: in-process library, and no engine process in i9.** The numbers do not ask for one: a node's headline costs 3 to
8 ms and the store answers a 50,000-position basket in 2.9 ms; a network hop would be a visible share of that. The costs that
are large (a date's first open, 25 to 27 s at 1,000,000 positions, two thirds of it Parquet decode; the garbage collector at that
size) are not removed by a second process on the same machine. Scale-out already exists as shards across the cluster. i9 reserves
the seam only (section 6): requests and answers are plain values, partial aggregates are public, and one measurement (K11) is
recorded so the question is next asked with figures.

**Accuracy.** A reference recorder runs Drishti's own jars at the freeze commit and writes every answer with doubles as raw
bits; at every step the moved code must give the same bits, and the workbooks the same bytes. The product's gate is a
conformance kit (`risk-tck`); Drishti's gate is its goldens unchanged (`VarGoldenMatrixTest`, `SystemBasketGoldenTest`,
`HierarchyGolden`, `PackagedVarGoldenTest`) and the stack's figures (VaR 167,372,692, ES 210,131,972).

**Size: about 92 bd (about 105 with 15% contingency), one drill.** Section 11.

## 0a. Delivery: one engine JAR in the server's lib folder

The reasoning, the class loading, the compatibility rule and the decisions (EN-29 to EN-44, accepted by the owner on 2026-11-22) are in
[ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) sections 0 and 9; the normative text is [ENGINE_SPEC](https://github.com/drishti-platform/drishti-engine/blob/develop/spec/1/SPEC.md) section 17. For this product:

| | What it is |
|---|---|
| The deliverable to a host | **`drishti-risk-<version>.jar`**: `risk-kernel`, `risk-market`, `risk-ccr`, the two thin engine modules (`risk-engine-market`, `risk-engine-ccr`) and the private libraries (Arrow, the OpenBLAS binding with its Java fallback, a YAML reader) merged into one file |
| What it registers | two engines for `ServiceLoader`: `market-risk` and `ccr`, each with its descriptor; they share the JAR's one risk kernel |
| What it compiles against | `engine-api` (JDK only), and as provided family libraries `tools-xlsx` (workbooks), `lake-contracts` (the contract files' model) and Rachana core (explanation formulas). No Drishti artifact, in any scope |
| How it is built and proven | in the `drishti-risk` repository, with no Drishti application: unit tests on the kit's harness, `risk-tck` (the engine kit plus Risk's cases and the recorder comparison), the kit's delivery group on the merged JAR |
| How Drishti gets it | a distribution lists the JAR's version and SHA-384; packaging copies the file into `<home>/lib/engines`; `config/engines/market-risk.yaml` and `ccr.yaml` bind it |
| How it is upgraded | the file is replaced and the node restarted (a cluster, node by node). No Drishti build |
| What Drishti compiles against | nothing of `com.ash.risk`. The banking module's remaining code uses the engine API's values and output names ([ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) 9.3) |
| The other build outputs | the product's own Maven modules, `risk-cli` and the facade of 4.1, for a second customer who embeds the library. They are not what a Drishti server loads |

What this changes below: 4.1 (how a host finds the engines), 4.3 (the ports), 5.2 to 5.4 (what stays in Drishti), 7 (how the
goldens call), 8 (the pin), 9.1 (Drishti's guards), 10 (D1, D3, D4, D8) and RK-3, RK-7, RK-16, RK-19. The estimate's change is
in [ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) section 14 (lane E15, and D1 and D3 mostly gone).

## 1. Positioning

Drishti Risk is **an aggregation-and-attribution engine over supplied valuations: scenario P&L, scenario PVs, sensitivities and
exposure paths in under a contract; VaR, ES, exposure, limits, their explanations and their formula workbooks out, for any scope,
the same number from the API, the command line and the workbook.** It prices nothing, stores no trades, has no screens of its
own and needs no Drishti.

| | What it is | Relation |
|---|---|---|
| A bank's pricing systems (Endur, Calypso, Murex, in-house) | value trades, produce scenario PVs and sensitivities | **suppliers**: their output is our input contract |
| QLRisk (the owner's QuantLib library) | prices and revalues | an optional supplier behind the `SuppliedVectors` port (what-if, stress); never inside the product, never needed |
| Vendor risk engines (full-revaluation platforms) | price and aggregate in one system | we do the second half only, on anyone's valuations, with every number explained and reproducible in Excel |
| Drishti | the real-time risk and analytics layer: lakes, access, cluster, screens | **customer #1**: serves the inputs from Drishti Lake, shows the outputs through Rachana |
| Drishti Lake | the loading and table layer | the normal source of inputs, through an optional adapter module; files, a database or an API replace it |

**What a second customer gets:** one jar set and a command; a day of inputs as files in, tables and workbooks out; a Java API
for embedding; the kit that proves a build, a new input source or a new engine module gives the certified numbers.

## 2. The kernel and the engine modules

### 2.1 The kernel (`risk-kernel`): what every engine module shares

| Part | Content | From today's |
|---|---|---|
| Vectors | scenario-vector stores on heap and memory-mapped, ordinal sets, deterministic parallel sums, grouped and sharded vectors | `compute.vector` (2,715) |
| Linear algebra | one interface, pure Java always present, OpenBLAS when it loads, guards, canary, circuit breaker; never a failed start | `compute.linalg` (1,309), `server.nativelib.OpenBlasEngine` |
| Scopes | the scope grammar (a position, a book, a node, a basket, any set, "the bank minus a desk"), resolution to ordinals | `ScopeExpr`, `ScopeResolver`, `IdIndex`, `Columns`, `Cells` (about 1,470) |
| Hierarchies | any number of hierarchies from promoted columns only; `(Blank)` nodes; path ids; limit letters | `HierarchySpec`, `Hierarchies`, `MembershipLines`, `PositionRule(s)` (about 1,120) |
| Limits | one rule for use, `within`, `near`, `breach`, for every measure | `RiskLimits` and the limit reads of VaR and exposure |
| Method choices | every method decision a named switch with its alternative computed beside it | `MethodChoices` |
| Explain | a figure's derivation as data: inputs, steps, formula, the scope's own numbers | `VarExplainMath`, the model half of `VarExplain`, `kernel.api.explain` shapes |
| Workbook | the playground workbook framework: templates as text OOXML parts, values-only data sheets, formula sheets | `server.playground` (989), `PlaygroundSheets` |
| Records | captures, the official record (freeze, sign-off state), result rows | the model half of `VarCaptures`, `VarOfficial`, `ExposureOfficial` |
| Partitions | split a date by a shard unit, partial aggregates, assembly | `ShardAssembly`, `ShardSkeleton`, `ShardedVectors`, the pure half of `VarShards` |
| Ports | inputs, supplied vectors, work directory, document store, result sink, audit, clock (section 4.3) | new, cut from the knots of section 0 |

Kernel rules: JDK plus Arrow (vectors and tables) and the OpenBLAS binding; no Spring, no Jackson in a public signature, no host
type; per-instance state only (today `VarBook.positionCacheBytes` and the native-library holder are static: K6 removes both).

### 2.2 Engine modules

| Module | i9 content | Built today | After i9 |
|---|---|---|---|
| **`risk-market`** | historical VaR and ES (rank rules, component, incremental, marginal), parametric VaR (PVAR), backtesting, stressed windows, 10-day, currencies and scenario FX, intraday overlay, what-if on supplied vectors, system baskets, period statistics, regulatory reports, sensitivities and the MR cube, FRTB-SA as it stands | yes: about 32,000 of the moving lines | i12: FRTB completed, stress, trade-grain VaR (the parked list) |
| **`risk-ccr`** | exposure from supplied paths (EE, EPE, PFE, netting, collateral), pre-deal check, what-if paths, official exposure, SIMM, SA-CCR and FVA playgrounds as they stand | yes: about 12,000 (the kernel is the other 8,000) | i13: the CCR engine (XRUN, XPATH), CVA |
| **`risk-liquidity`** | **design only in i9** (K15): measures, input contracts, playground mock-ups | no | built in its own release (RK-17) |
| **`risk-climate`** | **design only in i9** (K15) | no (one example data pack) | built in its own release |
| **non-financial risk** | a design check only: does a loss-event and scenario engine fit the kernel at all | no | decided after the check |

A module is a jar that implements the SPI of section 4.2 and passes the kit. Nothing in the kernel names a module; a deployment
lists the modules it loads.

## 3. Input and output contracts

The product owns the **definitions** of what it reads and writes, in the contract schema of Drishti Lake (`lake-contracts`, a
file format here, not a run-time dependency of the kernel). Kind names are today's, so no data file changes.

| Group | Contracts (kinds) | Used by |
|---|---|---|
| Positions | `position`; trade attributes and promoted hierarchy columns on the vector kinds | all |
| Scenario vectors | `pnl-vector`, `scenario-pv`, `scenario-set`, `fx-scenario` | VaR, ES, PVAR, stress windows |
| Sensitivities | `position-sensitivity`, `frtb-sensitivity`, `risk-factor` | MR cube, FRTB-SA, SIMM |
| Actual P&L | `desk-pnl`, `pnl-history` | backtesting, regulatory reports |
| Stress | `stress-scenario`, `stress-scenario-pnl` | stress (i12 completes it) |
| Exposure | `exposure-path`, `simulation-run`, `netting-set`, `csa`, `agreement`, `collateral-balance`, `trade-valuation`, `nset-mtm`, `cpty-mtm` | exposure, pre-deal |
| Limits | `var-limit` (hierarchy, node id, limit, currency), `credit-limit` with tenor buckets | limits, limit letters |
| Hierarchies | declared in the engine's options as levels over promoted columns; system baskets as documents | scopes |
| **Outputs** | `var-result`, `var-official`, `exposure-result`, `exposure-official-cpty`, `stress-result`; each answer as a named table | hosts, lakes, reports |

Rules:

- **Missing input is an answer, not an error and never a computed substitute:** "not found: `scenario-pv` for 2026-01-13;
  missing columns: `DESK`". No fallback valuation, ever (`not-a-valuation-engine`).
- **One home per fact.** The roughly 30 engine contracts move to the product (`risk-contracts`); Drishti's packs keep their
  host keys (`mnemonic`, `pack`, `binding-hint`) as a contract profile over them, as the Lake design does (its section 5). The
  other banking contracts (trades, margin calls, capital inputs that no engine reads) stay in the packs.
- **Outputs are plain:** every answer is a table (columns, types, rows) available as Java records, Arrow batches and JSONL; every
  figure has its explanation and its workbook (`.xlsx` with live formulas over a values-only data sheet). The product writes
  results only through the `ResultSink` port.
- **Screens are optional and not the product's.** The product publishes its answer tables under the column names that Rachana's
  `finance` library declares in its published contracts (`scenario-pnl` and the rest, LIBRARIES.md 5.2); the fragments
  (`finance/var-tiles`, `finance/var-histogram`, `finance/limit-gauge`) stay in that library. The kit compares the two contract
  lists as files, so neither product depends on the other. A host serves the tables over the Rachana Data Protocol; live
  results (intraday VaR) are a live table in the host, fed by the engine's change events.

## 4. Public API and module SPI

### 4.1 The facade (`com.ash.risk`)

```java
RiskRuntime rt = RiskRuntime.builder()
        .modules(MarketRisk.module(), CounterpartyRisk.module())      // explicit: no ServiceLoader, no static registry
        .inputs(LakeInputs.of(lake))                                   // or FileInputs.of(dir), JdbcInputs, a host's own
        .workDir(abs).documents(store).results(sink).audit(audit)      // ports; all optional but workDir
        .options(RiskOptions.read(Path.of("/abs/risk.yaml")))          // or built in code by the host
        .build();

RiskDay day = rt.open(LocalDate.of(2026, 1, 13));                      // loads once; any number of readers
Answer a  = day.measure("var").scope(Scope.parse("DESK:RATES - BOOK:X1"))
               .visibility(v).lens(Lens.of("MGMT", "USD")).method("rank-rule", "ceil")
               .ask(Ask.HEADLINE, Ask.CONTRIBUTIONS, Ask.PATH);        // tables, never a vector per part unless asked
Explanation e = a.explain("var");          Workbook w = a.workbook();   // every figure, by rule
Partial p = day.partial("var", shardUnit); Answer whole = rt.assemble(partials);
```

This facade, with its explicit module list, is for an embedder and for `risk-cli`. A host (Drishti) does not call it: it finds
the two `com.ash.engine.Engine` classes of the engine JAR by `ServiceLoader` and drives them through the engine API (0a).

Rules of the surface: packages `com.ash.risk`, `.spi`, `.contract`, `.table`, `.explain`, `.workbook` are public, the rest
`internal` and closed by the module boundary from the first tag. Requests and answers are immutable values that serialise
(Arrow for tables and vectors, JSON for the rest): this is the process seam of section 6. Errors are `RiskException` with
`RSK-` codes, mapped once by the host (Drishti: to `DRS-`). Paths are absolute. The engine reads options and never writes a
configuration file. No `Principal`, no Spring, no host exception.

### 4.2 The module SPI (`com.ash.risk.spi`)

| SPI | Shape | The kit checks |
|---|---|---|
| **`RiskModule`** | id, version, the measures it offers, the contracts it reads and writes, its option schema | every contract and option is declared and documented |
| **`Measure`** | `load(day, inputs)` to an immutable book; `partial(scope or shard)` returning additive aggregates; `figures(aggregate, method)` applying the non-linear rule; `contributions`, `history` | additivity: the parts' aggregates sum to the whole's, bit for bit; determinism over two runs and over thread counts |
| **`Explainer`** | the figure's derivation as data | the explanation's own steps recompute the figure exactly |
| **`Playground`** | the template, the data sheet, the formula sheets; each method switch a drop-down cell | the workbook's stored values equal the engine's; an outside recalculation agrees (section 7) |
| **`LimitRule`** | which limit kind binds which figure at which node | one status rule (`within`, `near`, `breach`) |
| **`ReportDefinition`** | a regulatory or management report as cells over measures | each cell traces to a measure and inputs |

"A metric ships with its playground" is therefore not a habit but the SPI: a `Measure` without `Explainer` and `Playground`
does not pass the kit and cannot be released.

### 4.3 The ports (implemented by the host or by the product's own adapters)

| Port | Purpose | Product adapters | Drishti implements |
|---|---|---|---|
| **`RiskInputs`** | rows of a contract for a date, as column batches; pruned by promoted columns. It **is** the engine API's `Inputs` | the engine repository's `engine-inputs-files` and `engine-inputs-lake` (JDBC later, on a need) | the generic engine host, over its lakes, with row rules and masks applied |
| **`SuppliedVectors`** | a vector for a hypothetical or amended trade, from whoever prices; a named supplier port of the engine API | none (answers "not supplied") | a pricing supplier when one exists (QLRisk through a worker) |
| **`WorkDir`**, **`AuditSink`**, **`Clock`** | hot files and spill in an explicit absolute folder; who asked for what; business today. They are the engine API's `EngineContext` | local folder, log, system | its data folder, `AuditLog`, its business calendar |
| `DocumentStore` | **DELETED** (2026-11-22): documents the engine reads (system baskets, report definitions) are inputs under contracts; captures, sign-offs and the official record are stored outputs with the host's workflow over them. The list is settled file by file at K0 | | |
| `ResultSink` | **DELETED** (2026-11-22): the engine returns tables; the host stores the outputs its configuration declares | | |
| `PartitionTransport` | **DELETED** (2026-11-22): a `Partial` is a value; carrying it between shard owners is the host's | | |

## 5. What moves, what stays

### 5.1 Moves (measured on `9ce7c3170`)

| Today in Drishti | Lines main | Goes to |
|---|---|---|
| `drishti-compute`: `compute.vector`, `compute.linalg` | 2,715 + 1,309 | `risk-kernel` |
| `drishti-server`: `server.playground`; `OpenBlasEngine` | 989 + about 200 | `risk-kernel` (workbook; linear algebra) |
| `banking-compute/var`: scopes, hierarchies, columns, position rules, shard assembly | about 2,600 | `risk-kernel` |
| `banking-compute/var`: `VarBook`, `VarHotFile`, `VarIntraday`, `VarMath`, `ParametricVar`, `VarCalc`, `CurrencyTotals`, `ScenarioFx`, `Backtest`, `StressWindows`, what-if maths | about 5,200 | `risk-market` |
| `banking-compute/frtb`, `/analytics`, `MethodChoices` | 2,686 + 439 + 105 | `risk-market` (`MethodChoices`: kernel) |
| `banking-compute/ccr`, `/simm` | 2,495 + 1,812 | `risk-ccr` |
| `banking-service/var`: service, loader, intake, tier, matrix, selection, system baskets, official, captures, reports, backtesting, parametric, stress window, what-if, period statistics, matured items, the answers (`VarViews`, `VarFigures`), the pure half of sharding | about 16,500 | `risk-market` |
| `banking-service/var`: explain and playgrounds (`VarExplain*`, `VarPlayground*`, `VarBasketSheet`) | about 4,700 | `risk-market` over the kernel's workbook |
| `banking-service/exposure` less its adapters | about 5,500 | `risk-ccr` |
| `banking-service/frtb`, `/simm` less controllers and properties | about 2,600 + 2,200 | `risk-market`, `risk-ccr` (with their playground books, 1,314 and 891) |
| playground templates and engine defaults (`resources/playground`, `resources/engines`), 42 files | | with their modules |
| `tools/pvgen` (synthetic scenario PVs, Python, 1,335) | | the samples folder in the product: the kit's sample day |
| tests of all the above | about 24,000 | with their code, or into the kit |
| **Total** | **about 52,000 main, 24,000 test** | |

### 5.2 Stays in Drishti

| What | Lines | Why |
|---|---|---|
| The 36 Spring files: 14 controllers, 12 properties classes, configurations and wiring | 5,765 | HTTP, `config/engines/*.yaml` binding, wiring; properties build `RiskOptions` |
| Access (`VarAccess`, `ExposureAccess`), pivot (`VarPivotProvider`, `VarPivotLens`, `VarPivotCodes`, `MrCubeExtras`), Flight (`VarFlightForm`), intraday stream (`VarPvStream`, `VarPvStreamPlugin`), `VarReadyRule`, saved views and basket edits on the content store, sign-off and approvals, number explainers and why-panels on the kernel's explain API, shard rows and peers, providers | about 8,200 | each is Drishti's access rules, cluster, content store, BI or transport |
| Official sinks (`official/*`), `BankingVocabulary`, `BankingOfficialResults`, `RiskOperations` | about 600 | lake writes and vocabulary |
| The three packs (`market-risk`, `counterparty-risk`, `trading`): 595 files, 162 Sutra files, samples, pack Python, the contract profiles | | domain packs |
| `tools/mrloader`, `tools/packgen/var`, the `var`, `exp`, `frtb`, `simm` commands of `tools/drishti.py` | | loading and operator commands |
| Cluster (Drishti*net, shard ownership), engine switches, admission, capacity, the kernel and `.dmod` assembly, editions | | the host |

Amended 2026-11-22: none of what stays may name a Risk type. Properties classes become the engines' `options` (old keys as
aliases); a controller whose route is a plain ask becomes an alias of the generic endpoint; the rest ask through the generic
engine host by output name ([ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) 9.3). How many of the 5,765 and 8,200 lines remain is measured at R2.

### 5.3 Adapters left, and new

**DELETED** (2026-11-22): `drishti-risk-host`. No Risk adapter module is written in Drishti. Its jobs are done by the generic
engine host and the two engine files; the visibility rules stay in the banking module as named host rules; an engine off is
the engine switch, not a module left unloaded.

### 5.4 Drishti's reduction

| | Before | After |
|---|---|---|
| Banking module, Java main | 61,353 | at most about 14,500 kept, none of it naming a Risk type; falling as plain-ask controllers, properties classes and screens go (measured at R2) |
| `drishti-compute` | `vector` and `linalg` leave: 4,024 | `reactive`, `worker`, `env`, `arrow`, `shard` remain |
| `drishti-server` | `playground` and the OpenBLAS engine leave: about 1,200 | `NativeLibraries` keeps the Admin switch and status over the product's interface |
| Tests | about 24,000 leave | the goldens stay and run against the pin |
| Docs | the method sections of `VAR_GUIDE` (7,137), `EXPOSURE_GUIDE` (2,280), `FRTB_SA_GUIDE`, `SIMM_GUIDE`, `REGULATORY_VAR_REPORTING` move to the product | Drishti keeps commands, screens, configuration, and links |

History: 216 of Drishti's 5,551 commits touch the banking paths (more through the earlier `drishti-compute/.../var` location,
which the filter follows).

## 6. The process model

### 6.1 What the measurements say (BENCHMARK.md 4.5, 4.9, 5.4, 5.5)

| Fact | Figure | Reading for a separate process |
|---|---|---|
| A node's headline, one user | 3 to 5 ms p50, 8 to 10 ms p99, the same at 200,000 and 1,000,000 positions | a loopback RPC adds a visible share to the commonest request |
| The store alone: a 50,000-position basket; the bank minus a desk | 2.9 ms; 1.6 ms | answers are a few hundred numbers; the vectors are already off-heap in a mapped hot file |
| A date's first open | 3.5 to 4.5 s at 200,000; 25 to 27 s at 1,000,000; 65 to 74% is the lake read | input-bound; a second process on the same machine reads the same Parquet |
| Garbage collection at 1,000,000 positions | about 6 CPU-minutes in a 7-minute loaded run | **the one argument for isolation**: the engine's heap pressure is the host's pause. The mapped tier and the run-wise part totals already cut it; not re-profiled since |
| Scale-out | VaR is sharded across cluster nodes today (`ShardedMeasure`, partial aggregates, assembly) | more capacity is more nodes, with a transport that exists |
| Native code | OpenBLAS behind guards, self-test, fallback; the engine never prices, so no QuantLib in process | crash isolation is not a present need |

### 6.2 Options

| Option | For | Against |
|---|---|---|
| **A. In-process library** | fastest call; one deployment; goldens trivially comparable; what the code is today | shares the host's heap and collector |
| B. Engine process, Arrow Flight | isolates heap and restarts; serves non-Java hosts; Drishti has `drishti-flight` and already streams VaR P&L over it | a second thing to deploy, secure, monitor and keep version-matched; entitlements must cross the wire as visibility; latency on small answers |
| C. Engine process, shared memory | zero-copy for large vectors | a private protocol to design and prove; and the large data is **already** a memory-mapped file a second process could map read-only |

### 6.3 Recommendation

**A in i9, with the seam reserved and nothing more.**

1. The API of section 4.1 is the seam: requests and answers are serialisable values, no callbacks into the host on a hot path,
   inputs pulled through a port. A remote engine is later one more implementation of `RiskRuntime`, not a redesign.
2. `Partial` and `assemble` are public and transport-free. What Drishti*net does between shard owners today is what a remote
   engine would do tomorrow.
3. The standalone customer gets **`risk-cli`** (a day's inputs in, tables and workbooks out): a separate process by nature,
   with no service, no port and no protocol. It is also how the kit runs.
4. **One measurement, K11 (1.5 bd):** the same four requests (headline, basket, contributions, path) at 200,000 and 1,000,000
   positions, in process and through loopback Flight, plus host pause times with the engine loaded; written into
   `BENCHMARK.md`. No server is shipped from it.
5. **Build B only when one of these is observed:** (a) with the engine loaded at production size, the host's other requests
   lose more than 20% at p99, or a collector pause exceeds the agreed limit, after heap tuning; (b) a date at the target size
   (2.3M positions) does not fit one JVM beside the host on the agreed machine; (c) a customer's host is not a JVM; (d) the
   engine must restart or upgrade without the host. If built: **Flight for remote, the mapped hot file for co-located** reads;
   no shared-memory protocol of our own (option C is rejected, RK-10).

The decision is looked at again with K11's figures at the i12 gate, when trade-grain VaR and stress raise the load.

## 7. Proof of accuracy

**Rule: the move changes no number. A double is compared by its raw bits, a workbook by its bytes.** No behaviour change, fix
or optimisation rides with the move; anything found is recorded and scheduled after `1.0.0`.

| Layer | What | When |
|---|---|---|
| **Reference recorder** (K2) | a runner over Drishti's own jars at the freeze commit writes, for the golden data sets and the sample day, every answer of every measure: headlines, components, incremental and marginal, paths, histories, backtests, parametric figures, stress windows, limits, exposure profiles, pre-deal, SIMM, FRTB, each explanation, each workbook. Doubles as `Double.doubleToRawLongBits` hex | once; stable over two runs and over 1, 4 and 24 threads before it is trusted |
| **Step equality** | `compare_results` after every step K3 to K10: equal file for file; `known-differences.txt` must be empty for any number | every step |
| **Tiers and engines** | heap and mapped tiers give the same bits at node level (as today); pure-Java linear algebra exact; OpenBLAS within relative 1e-12 of Java (today's parity rule) and recorded as its own result set | K6, K9 |
| **Drishti's goldens, untouched** | `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest` pass without an edit to an expected value. Their calling code asks through the generic engine host, with the Risk JAR copied into a temporary lib folder (no Risk class on Drishti's class path); the stack shows VaR 167,372,692 and ES 210,131,972; the demo's FY2026 period figures (period end 112,041,646) unchanged | D8, before the drill |
| **Independent check** | the playground workbook is the outside oracle: its formulas recompute the figure from the data sheet in a spreadsheet engine the product did not write; plus a small reference script for the rank-based measures on kit cases (test code only; no product number comes from it) | K9; every release |
| **Speed is part of "the same"** | `VarTierBenchmark`, `ExposureTierBenchmark` and the 200,000-position run within 5% of the pre-move figures | K14, D8 |

**The product's gate, `risk-tck`** (a test-jar and a command; no tag unless green):

| Part | Content |
|---|---|
| Cases as data | `tck/cases/<module>/<measure>/*.yaml`: input rows under the contracts (JSONL), the request, the expected tables with raw-bit doubles, or the expected refusal by `RSK-` code or "not found" text. Harvested from the goldens and the about 24,000 moved test lines |
| The matrix | case × tier (heap, mapped) × linear algebra (Java, native) × inputs adapter (files, lake, JDBC) × partitions (1, 3); equal across the row |
| SPI kit | for any module: additivity, determinism, explanation recomputes, workbook equals engine, every option and contract documented |
| Gates | coverage (every measure × every method switch; every `RSK-` code; every contract column read); additive-only (the last minor's cases pass unchanged); a missing oracle fails, never skips |
| For a third party | `risk tck run --inputs <adapter jar>` or `--module <jar>` writes the same results file; equality with the reference is the certificate |

## 8. Repository layout and coordinates

```
drishti-risk/                        private, proprietary; main + integration branches as drishti-rachana and drishti-lake
  java/  risk-api  risk-kernel  risk-contracts  risk-market  risk-ccr
         risk-engine-market  risk-engine-ccr  risk-engine-jar      (the merged drishti-risk-<version>.jar a host receives)
         risk-files  risk-jdbc  risk-lake  risk-cli  risk-tck  risk-bom
  contracts/                         the engine contracts (YAML), generated pages
  tck/cases/  tck/results/           cases as data; reference results per release
  tools/split/  tools/release/  tools/samples/
  docs/  README.md  CHANGELOG.md  LICENSE  java/SPLIT_EXEMPTIONS.md
```

| | Recommended | Note |
|---|---|---|
| Name, repository | **Drishti Risk**, `drishti-risk` | the 2.9.6 "Drishti Risk" edition becomes the Drishti distribution that bundles the product (RK-2) |
| groupId, artifactIds | **`com.ash.risk`**; `risk-kernel`, `risk-market`, … | beside `com.ash.rachana` and `com.ash.lake` |
| Java package | `com.ash.risk` (+ `.spi`, `.contract`, `.table`, `.explain`, `.workbook`, `.market`, `.ccr`, `.internal..`) | renamed in one commit |
| Java level | 21 | Drishti's production level |
| Version | `0.9.0` at the move, `0.10.x` while untangling, **`1.0.0`** when the kit and section 7 hold | semantic versioning on the public packages |
| Dependency allow-list | `risk-api`: JDK. `risk-kernel`: Arrow, the OpenBLAS binding, a YAML reader. Engine modules: the kernel only. `risk-lake`: `com.ash.lake:lake-api`. No Spring, no host, no Jackson in a public signature | a guard |
| The pin | **DELETED** (2026-11-22): the `drishti.risk.version` property and `risk-bom` import in Drishti. Drishti has no Maven dependency on Risk: a distribution lists the engine JAR's version and SHA-384, packaging copies the file into `lib/engines`, and under trust `pinned` the engine files carry the same hash. Released from the tag only, never a branch or a snapshot | 0a |

## 9. The move, step by step

Method as Rachana and Lake: **move first, untangle in the new repository; Drishti is read-only until its migration.** Every
step ends green on its check and can be paused after it. Grades: F Fable, O Opus, S Sonnet, H Haiku.

| # | Step | Check | Grade | bd |
|---|---|---|---|---|
| K0 | Decisions RK-1 to RK-20; the path allow-list reviewed file by file (the "about" groups of section 5 become exact); the freeze commit named | the list; a scan for private data and files over 1 MB (no real Endur value) | F | 1 |
| K1 | Repository bootstrap: reactor, headers, licence, the guards in report mode, release script skeleton | an empty build; guards run | S | 2 |
| K2 | **Reference recorder and first cases** (section 7) | results stable over two runs and three thread counts | O 3, S 3 | 6 |
| K3 | **Move with history**: `git filter-repo` on a fresh clone at the freeze commit, the allow-list only (it follows the earlier `drishti-compute/.../var` paths); merged with `--allow-unrelated-histories`, no force push; package rename in one commit; host types parked in `com.ash.risk.hostcompat`, each listed in `SPLIT_EXEMPTIONS.md` with the step that removes it; tag `v0.9.0` | moved unit tests green; results equal the recorder's | S, O reviews | 2 |
| K4 | **Own errors, options, visibility**: `RiskException` and `RSK-` codes (132 import sites); `RiskOptions` in place of the 12 properties classes' reach; `Principal` out (63 sites) for visibility and actor | results equal | S 4, O 2 | 6 |
| K5 | **Input ports**: the loaders and intakes off `DataNode`, `EntityDocument`, `SourceRouter` onto `RiskInputs` column batches; `risk-files`; the "not found" answers | results equal **bit for bit through the intake**; a date's first open not slower | **O 7**, S 3 | 10 |
| K6 | **Kernel cut and runtime**: `risk-kernel` against `risk-market` and `risk-ccr`; `RiskModule` and `Measure` SPI over what exists; `RiskRuntime` per instance, the two statics removed; two runtimes in one JVM proven apart | results equal; `TwoRuntimesTest`; the few-core start-up test | **O** | 8 |
| K7 | **Outputs**: answers as tables (Arrow, JSONL), `ResultSink`, `DocumentStore`; the workbook framework in the kernel; the table names checked against the `finance` library's contract list | workbooks byte-identical; results equal | O 3, S 3 | 6 |
| K8 | **Partitions public**: `Partial`, `assemble`, the shard unit per measure, transport-free | 1 and 3 partitions equal; equal to Drishti's sharded answers in the recorder | O | 3 |
| K9 | **Kit complete**: the matrix, the SPI kit, coverage and additive gates, the outside recalculation, the third-party runner | green on the whole matrix | O 4, S 5 | 9 |
| K10 | **`risk-cli`** and the sample day (`pvgen` moved); `risk-jdbc`, `risk-lake` | the sample day through the command equals the recorder | S | 4 |
| K11 | **Process-model measurement** (section 6.3, item 4) | figures in the benchmark document | O | 1.5 |
| K12 | Public API review against three consumers (Drishti's adapter sketch, the command, a 50-line sample project); `module-info`; API dump frozen | the sample builds from `~/.m2` only | F 1, S 1.5 | 2.5 |
| K13 | Docs: README, one guide per engine module (the method sections moved, host words removed), `SPI.md`, `TCK.md`, `CONTRACTS.md`, `METHODS.md`, CHANGELOG | link and stale checks | S 4, H 2 | 6 |
| K14 | Release tooling; `v1.0.0` candidate into `~/.m2`; a Drishti side branch on the pin with the goldens and the tier benchmarks run | section 7's Drishti rows on the side branch | S | 2 |
| K15 | **Designs**: liquidity and climate modules (measures, contracts, playground mock-ups, 2 to 3 each for the owner to pick); the non-financial-risk check | the owner's review | F | 4 |
| | **In the product's repository** | | | **73** |

### 9.1 Guards

| In `drishti-risk` | Fails when |
|---|---|
| Names no host | a file contains `com.ash.drishti`, `DRS-`, a `drishti.` setting, or a host word (pack, mnemonic, Sutra); "Drishti Risk" and "Drishti Lake" allowed by exact phrase |
| Never prices | a class named or documented as a pricer, a curve, a model calibration; a dependency on a pricing library |
| Java computes | a number in a product path produced outside Java (the kit's reference script lives under `tck/` only) |
| Public surface | a public type outside the exported packages; a Spring, Jackson or Arrow-internal type in a public signature; the API dump changed without a recorded approval |
| No statics | mutable static state in the kernel or a module |
| Metric complete | a `Measure` without `Explainer` and `Playground`, or without kit cases for each method switch |
| Kit gates; exemptions only shrink; file cap; header | as Lake's section 7.1 |

| In Drishti (from the migration) | Fails when |
|---|---|
| No Risk type in Drishti | a source imports `com.ash.risk`, or a pom depends on a Risk artifact (replaces "public API only", 2026-11-22) |
| No second copy | a class named like a moved one; a VaR, ES or exposure formula outside the product (extends today's "server computes risk" checks to "the product computes risk") |
| The JAR is one fact | the distribution's listed version and SHA-384, the file packaging copied, and (under trust `pinned`) the engine files' hash disagree |
| The host only adapts | the kit fails when run inside Drishti against the engines as configured |

## 10. Drishti's migration

One sitting on the i9 integration branch, other builders off the banking module; `main` moves only after the drill.

| # | Step | Check | Grade | bd |
|---|---|---|---|---|
| D1 | The JAR: the distribution lists `drishti-risk-<version>.jar` with its SHA-384; packaging copies it into `lib/engines`; `config/engines/market-risk.yaml`, `ccr.yaml`. DELETED: the `drishti.risk.version` property and `risk-bom` | the drop-in test: the server jar as built runs the engines from the copied file | S | 0.5 |
| D2 | Delete: `banking-compute`, the moved classes of `banking-service`, `compute.vector`, `compute.linalg`, `server.playground`, the moved tests, `tools/pvgen` | the build fails only where an import must change | S | 1 |
| D3 | **DELETED** (2026-11-22): the scripted import rewrite to `com.ash.risk..`. Nothing in Drishti imports Risk; what stays is rewritten onto engine API values in D4 | | | 0 |
| D4 | **What stays, on the engine API only** ([ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) 9.3, lane E15): access rules as visibility rules; plain-ask routes as aliases of the generic endpoints; pivot, Flight form, intraday stream, sign-off and explain panels through the generic host by output name; engine switches are the engine files. DELETED: `drishti-risk-host`, one `RiskRuntime` per server | module tests; the banking browser files; start on few cores | **O** | 6.5 (E15) |
| D5 | Commands: `var`, `exp`, `frtb`, `simm` of `tools/drishti.py`; callers of `pvgen` | the affected Python tests | S | 1.5 |
| D6 | Guards of 9.1 on, failing; the two reactor guards of [ENGINE_INTEGRATION_DESIGN](ENGINE_INTEGRATION_DESIGN.md) 0.11 | the guards | S | 1 |
| D7 | Docs: the five guides cut to Drishti's part with links; contract profiles; `CONFIGURATION.md` regenerated; README counts; CHANGELOG; the deck | docs checks | S 2, H 1.5 | 3.5 |
| D8 | **Proof**: section 7's Drishti rows through the dropped JAR; the kit inside Drishti; the cluster's sharded answers equal the single server's; Risk `1.0.0` replaced by a `1.0.1` build with the server jar's hash unchanged | all exact; any difference stops the migration | O | 2.5 |
| D9 | **The one drill**, then the tag | green drill | lead | 1 |
| | **In Drishti** | | | **about 17.5** |

## 11. Estimate

| Lane | Steps | bd | Model |
|---|---|---|---|
| Design and gates | K0, K12 review, K15, the pre-migration inspection | 7 | Fable |
| Deep: recorder, visibility, input ports, kernel cut, outputs, partitions, kit design, measurement, adapters, proof | K2, K4 (part), K5 to K9, K11, D4, D8 | 40 | Opus |
| Routine: bootstrap, move, errors and options, files and JDBC adapters, kit cases, command, docs, release, pin, delete, imports, commands, guards | K1, K3, K4, K5, K7, K9, K10, K12 to K14, D1 to D3, D5 to D7 | 41 | Sonnet |
| Repetitive: headers, link fixes, doc moves | parts of K13, D7 | 3.5 | Haiku |
| Drill | D9 | 1 | lead |
| **Total** | | **about 92** | |
| With 15% contingency | | **about 105** | |

Calendar: the chain K3 → K4 → K5 → K6 → K7 → K8 is serial (35 bd; a serial chain moves 4 to 5 bd a day, I7B_PRODUCTS_PLAN
4.2); the kit, command, docs and design lanes run beside it, never more than three builders. About **12 to 14 working days**
for the product, then about 4 for Drishti's migration and the drill. Preconditions: i7b tagged (the split pattern proven, Drishti
Lake `1.0.0` on the pin) and i8 tagged.

## 12. The roadmap after i9

| Release | Content | Change |
|---|---|---|
| i7b | Rachana, Elements, Drishti Lake; G1 to G4; one drill | as planned |
| i8 | role Homes, screens from fragments, the live-tables switch, carry-overs | as planned |
| **i9** | **Drishti Risk 1.0: kernel, market risk, counterparty credit as built; Drishti on the pin; designs for liquidity and climate** | this document |
| i10 | GenAI | was i9 |
| i11 | RBAC and controls | was i10 |
| i12 | market-risk expansion **inside the product**: FRTB completed, stress, trade-grain VaR, TVAL narrowing; the process-model checkpoint with K11's figures | was i11 |
| i13 | CCR expansion inside the product: the CCR engine (XRUN, XPATH), CVA, collateral | was i12 |
| i14 | `risk-liquidity` (from K15's design, after the owner's pick) | new |
| i15 | `risk-climate` | new |
| i16 | non-financial risk, only if K15's check says the kernel fits | new, conditional |

From i12 on, a risk release is a product release with its kit, then a pin move in Drishti with screens: two small steps, not
one large one.

## 13. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| A number moves in the intake rewrite (K5) | the product's whole claim | raw-bit equality at the step; Opus only; the old loader kept in the recorder as the reference until `1.0.0` |
| Summation order changes with the kernel cut or thread count | last-bit differences | the recorder is first proven stable over 1, 4 and 24 threads; `ParallelSums` moves unchanged; determinism in the SPI kit |
| The kernel is cut too wide (a shared book for all measures) | a rewrite hidden in a move | RK-6: only what is shared today; the fold waits for i12 and i13, when a third measure shows the true shape |
| The API is shaped by Drishti's screens | a second customer cannot use it | K12's three consumers; the command and the sample project use nothing but tables |
| Visibility loses a rule that `Principal` carried | a caller sees more than allowed | D4's tests run the existing access tests unchanged; a request without visibility is refused, never "all" |
| Speed regresses through the ports | slower first open, slower headline | column batches, no per-row objects; the 5% benchmark gate at K5, K14, D8 |
| Market-risk expansion (i12) reshapes the same code soon after | double work | the expansion happens inside the product behind the SPI, which is the point of going first; nothing parked is touched in i9 |
| Contracts in two homes | drift between the product's definition and a pack's | one file per contract in the product; packs hold a profile only; a guard compares |
| Two repositories slow a one-line fix | a release and a pin move per fix | scripted release in minutes, as Rachana and Lake |
| Private data or demo values in the moved history | a leak into a product repository | allow-list reviewed at K0; scan of the filtered clone before the first push |
| Liquidity and climate designs wait on the owner | i14, i15 slip | designs and mock-ups delivered inside i9 (K15), decisions asked then |
| Token cost | the owner's budget | Fable for 7 bd only; grade set on every builder; no critique runs |

## 14. Decisions (RK-1 to RK-20)

| # | Decision | Recommendation |
|---|---|---|
| RK-1 | Timing | **i9, after i7b and i8 are tagged**; one drill at the end |
| RK-2 | Name and coordinates | **Drishti Risk**; repository `drishti-risk`; groupId and package `com.ash.risk`; error codes `RSK-`. The 2.9.6 `risk` edition keeps its file and means "the Drishti distribution with the product's modules on" |
| RK-3 | One product | **One repository, one version, one kit**: `risk-kernel` plus engine modules. To a host they are one engine JAR registering `market-risk` and `ccr` (0a); a site turns each on or off in its engine file |
| RK-4 | Order of work | **Move first, untangle in the new repository** (`hostcompat`, exemptions that only shrink); Drishti read-only until D1 |
| RK-5 | Where the line runs | **`banking-compute` whole and the Spring-free core of `banking-service` move; controllers, properties, access, pivot, Flight, streams, sign-off, sinks stay** as adapters on the public API |
| RK-6 | Kernel scope in i9 | **Only what is shared today** (vectors, linear algebra, scopes, hierarchies, limits, method choices, explain, workbook, records, partitions, ports). The VaR and exposure books stay in their modules; folding them is i12/i13 work |
| RK-7 | Vectors and BLAS | **Move into the kernel** (only the engine uses them). Drishti's `openblas` switch becomes the engines' option `linear-algebra` (old key as alias) and the Health line comes from the engines' status words. Later native maths (LAPACK and the rest) joins the kernel when a measure needs it, not before |
| RK-8 | Inputs | **`RiskInputs` port, column batches, contract rows**; adapters for files, JDBC and Drishti Lake in the product; Drishti implements it over `SourceRouter` so masks still apply |
| RK-9 | Identity | **No `Principal` in the product**: the host passes a visibility and an actor name; a request without visibility is refused |
| RK-10 | Process model | **In-process library in i9; no engine process, no protocol.** Seam = serialisable requests and answers plus public partials. K11 measures; build an engine process only on the triggers of 6.3; then Flight for remote and the mapped hot file for co-located; no shared-memory protocol of our own |
| RK-11 | Standalone running | **`risk-cli` in 1.0** (a day of files in, tables and workbooks out); no HTTP service in 1.0 |
| RK-12 | Pricing | **Never.** `SuppliedVectors` is a port; the product's own implementation answers "not supplied"; QLRisk or any pricer is a host's supplier, outside the repository; a guard keeps pricers out |
| RK-13 | Contracts | **The engine's roughly 30 input and output contracts move to the product** in the Lake contract schema; packs keep a profile; other banking contracts stay. Exact list at K0 |
| RK-14 | Screens | **None in the product.** Answer tables carry the columns the Rachana `finance` library declares; fragments stay in that library; the two lists are compared as files |
| RK-15 | Playground rule | **In the SPI**: no `Measure` without `Explainer` and `Playground`; the kit refuses it |
| RK-16 | The workbook writer | The playground framework moves to the kernel and writes through drishti-tools `tools-xlsx` (TL-2), a provided family library of the engine JAR. DELETED: `XlsxWriter` as a public kernel type and the `com.ash.common:xlsx` alternative |
| RK-17 | Liquidity, climate, non-financial risk | **Designs and mock-ups in i9 (K15); built in i14 and i15; non-financial risk only after the check** |
| RK-18 | Accuracy gate | **Raw-bit equality to the recorder at every step; Drishti's goldens unedited; the kit is the release gate; nothing but the move in the move** |
| RK-19 | Delivery | **One engine JAR copied into `<home>/lib/engines`, released from the tag only; no Maven dependency in Drishti** (0a). DELETED: jars in `~/.m2` pinned by one property and `risk-bom` |
| RK-20 | Freeze | **Freeze `modules/banking/java`, `compute.vector`, `compute.linalg`, `server.playground` on Drishti from the freeze commit to D9**; owner-directed fixes in that window are made in the product and reach Drishti with the pin |

## Owner decision (2026-11-22): liquidity and climate inside Drishti Risk, isolated

Liquidity and climate are part of the Drishti Risk product, each isolated exactly like market risk and CCR: its own Maven modules in
the one `drishti-risk` Maven project (`risk-liquidity` and `risk-engine-liquidity`; `risk-climate` and `risk-engine-climate`), its own
package (`com.ash.risk.liquidity`, `com.ash.risk.climate`), its own engine JAR with its own product id (separately licensable). Each
depends only on `risk-api` and `risk-kernel`, never on another line of business; a guard enforces it. They are built in their releases
(liquidity i13, climate i14); the modules may be created empty earlier.
