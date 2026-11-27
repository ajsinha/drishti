<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti. Proprietary: see LICENSE. -->
# R2 cut plan: Drishti on Drishti Risk

Status: proposed (not built), revised after Fable's review (section 6, GO WITH CHANGES): the eleven must-changes of 6.2 are in sections 1 to 5 (marked MC-n), and the owner's zero-copy requirement is MC-12 (section 3a). Steps 0 to 4 proceed; steps 5 and 6 stay NO-GO until the coverage table has no empty cell and steps 1 to 4a are green. Branch `i8/develop`. Design: DRISHTI_RISK_DESIGN.md (sections 0a,
5, 7, 9, 10) and ENGINE_INTEGRATION_DESIGN.md (sections 0, 9, 12). Gate: A8, "Risk from one dropped JAR, through configuration, with the
goldens bit-identical".

## 1. The fixed figures (never edited)

| Proof | Where | Figures |
|---|---|---|
| Stack headline, asserted **by name** (MC-2) | packaged server over the committed sample lake, 2026-09-30, 509-day window, USD | BANK VaR 167,372,692, ES 210,131,972; DESK:RATES VaR 74,633,680, ES 79,246,576 |
| `PackagedVarGoldenTest` | `testing/drishti-modules-it` | 12 cases (3 dates x 2 windows x 2 currencies), > 1,500 scopes, members exact, VaR/ES, limit and use |
| `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden`, `HierarchyEquivalenceTest` | today `modules/banking/java/*/src/test`; their classes move to drishti-risk, so at step 4 they are **rewritten in `drishti-modules-it` over the host, expected files copied unedited** (MC-11) | expected files unedited |
| Recorder (RK-18) | drishti-risk `tck/recorded/**` and `tck/data/**`, written by `RiskRecorder` run against Drishti at the freeze commit `3813b8735` (step 1, MC-3) | every double as its raw bits; workbooks byte for byte |

No expected value, tolerance or golden file is changed by any step. **Equality is raw bits** (MC-2): a scope whose VaR or ES differs in
any bit is named and stops the plan; there is no "within one" count. The CCR reference, recorded today within a float32 tolerance, is
re-recorded as raw bits by the recorder.

## 2. What moves, what Drishti keeps

Moves (copied with history into `drishti-platform/drishti-risk` at R1; Drishti's copies are deleted only at step 6):

| Drishti today | Risk module |
|---|---|
| `compute.vector`, `compute.linalg` (drishti-compute), `server.playground`, `OpenBlasEngine` | `risk-kernel` (Drishti keeps `NativeLibraries` and `openblas.yaml` over `Engine.notes()`) |
| `banking-compute/var`, `/frtb`, `MethodChoices`; `banking-service/var` engine half | `risk-market` + `risk-engine-market` (`market-risk`) |
| `banking-compute/analytics` (correlation, beta) | an output of `market-risk`, or its own engine JAR (design section 12), before step 6 (MC-6) |
| `banking-compute/ccr`, `/simm`; `banking-service/exposure`, `/simm` engine half | `risk-ccr` + `risk-engine-ccr` (`ccr`) |

Drishti keeps the 68 `banking-service` classes of section 6.3; **each one** is rewired to engine API values, or deleted with its reason,
in the step-5 commit that makes it compile without the engine half (MC-5). Nothing that stays may keep `VarBook` or `VarService` alive
(MC-1).

## 3. Engine-host wiring

1. **One JAR and one product id per line** (the owner's isolation decision, MC-8): `drishti-risk-market-<v>.jar` (product
   `drishti-risk-market`, engine `market-risk`) and `drishti-risk-ccr-<v>.jar` (product `drishti-risk-ccr`, engine `ccr`). No merged JAR.
   The descriptors, the drishti-risk README, `FileLicence`'s example and the drop-in licence file use these two spellings. The empty
   liquidity and climate modules produce no JAR and register nothing. A test loads both JARs together and states what
   `linear-algebra: native` does in each loader (a JVM binds a native library in one loader): either both native, or the second falls
   back to Java in words, and the speed step runs that way. **Decided (owner, 2026-11-22, EN-75):** OpenBLAS and javacpp are a shared platform library the server provides
   parent-first; the per-line JARs carry neither (provided, unshaded, the version in `platform:`, a mismatch refused in words), so both
   engines bind the one native copy. Built before step 4a, which measures load and memory with it.
2. Drishti has no Maven dependency on either JAR. Packaging copies them (versions and SHA-384 listed in the distribution) into
   `<home>/lib/engines`. A test copies them into a `@TempDir` lib folder; the banking test harness (`BankingTestHarness`,
   `BankingTestServer` and the fixtures, about 80 API tests) does the same and writes the engine files, expected values unedited.
3. `config/engines/market-risk.yaml` and `ccr.yaml` bind inputs to the packs' kinds and carry `options` (hierarchies and position rules
   from the market-risk pack; today's `var.yaml`, `exposure.yaml`, `frtb-sa.yaml`, `simm.yaml`, `sensitivities.yaml`, `mr-cube.yaml` keys
   as aliases; `linear-algebra` from `openblas.yaml`).
4. **Packs** (MC-6): the reactive blocks of `market-risk/pack.yaml` (`var-engine`), `counterparty-risk/pack.yaml` (`exposure-engine`) and
   `modules/market-data/packs/market-data/pack.yaml` (`correlation-engine`: `correlation-result`, `equity-beta`) become `uses.engines`,
   generated by `tools/packgen/banking/make_packs.py`.
5. The banking module asks through `EngineHost` by output name, with the caller's `Visibility` from `VarAccess` and `ExposureAccess`.
   Routes are `/api/v1/risk/var/*` and `/api/v1/risk/exposure/*` (F8).

## 3a. Zero-copy P&L vectors across the engine boundary (owner requirement, MC-12: a must before the engine path is built)

Normative now: ENGINE_INTEGRATION_DESIGN section 17 (EN-56 to EN-65, accepted) and drishti-engine SPEC section 18. The view types are
`Lease`, `RowSet`, `Columns`, `FloatVectors`, `DoubleVectors`, `TableWriter` (they replace the array `Batch` and the `VectorSink`); the
working name `ScenarioVectors` below is `FloatVectors`/`DoubleVectors`.

The engine JAR runs in the server's JVM, so the vectors are never copied at the seam or in the engine's book.

1. **A read-only view, owned by the host.** The engine API gains a vector view (working name `ScenarioVectors`: `rows()`, `width()`,
   `value(row, j)`, `sum(rows, into)`, a `DoubleBuffer`/`ByteBuffer` slice per row, read-only) over Drishti's existing stores: the heap
   tier's `double[]` chunks and the mapped tier's hot file (`MappedByteBuffer` pieces through `FileMaps`). Java 21 only: no FFM preview.
   The host keeps the mapping's lifetime and the close guard (`MappedMatrix`'s reader count): a view used after its date is evicted
   answers "unloaded, ask again", never a crash. The engine reads in place; `MarketDay` and `MarketBooks` keep row indices into the view,
   never `double[]` copies (F3: today `Arrays.copyOf` per row, then `clone()` in the book).
2. **The file layout, checked first.** What the in-tree store writes today (read on `i8/develop`): the mapped tier's hot file
   (`VarHotFile`) holds the **position rows as float32**, row-major, little-endian (`FileMaps` orders every mapping `LITTLE_ENDIAN`), and
   the node aggregates as float64; the heap tier holds float64 `double[]` chunks; the load's spill (`RowSpill`) is float64 little-endian.
   A node's VaR and ES come from its float64 aggregate, so they are the lake's bits; position-level sums read float32 rows into float64
   accumulators. Row-major fixed-width little-endian values are what an Arrow `FixedSizeList<float64>` (or `<float32>`) buffer is, minus
   the IPC framing. Decision recorded here, made at step 4a with figures: keep the hot file's bytes (bits unchanged) and add an Arrow IPC
   file header and footer written with the official Arrow Java library (`arrow-vector`, already a dependency) so an out-of-process engine
   (Python, remote, Flight) maps the same body zero-copy; no change of value width in R2, because float32 to float64 would change
   position-level bits and double the mapped bytes.
3. **Visibility is a selection, not a book.** A caller's visibility becomes a position index list (an `OrdinalSet`) applied over the
   one shared set of vectors and aggregates; no book is built per visibility signature. `access.isolation: shared` with the kit's
   filter-equivalence and interleaving laws (EN-47).
4. **Proof.** Step 3 compares raw bits with the view in place (heap and mapped tier). Step 4a proves the engine path at 200,000 and
   1,000,000 positions (`-Xmx6g`) no slower (p50 within 5 %, p99 within 10 %) and no bigger (peak memory within 5 %, bytes per ask within
   10 %) than in-tree, figures in BENCHMARK.md.
5. **Order.** The view is added to `engine-api` (0.x, additive) and implemented by the host's `Inputs` and the kit's file inputs before
   the market-risk intake is rewritten on it; the side by side of step 3 runs on the rewritten intake.

## 4. Steps

Each step is small commits, each green on its targeted tests; tests and documents change **in the same commit** as the code they describe
(MC-10: the rows of 6.4 and 6.5 are listed per step).

| Step | What | Proven by | Tests and documents in the same commits | Rollback (MC-9) |
|---|---|---|---|---|
| 0 | i8/develop builds; `./mvnw clean verify` passes | full verify | | revert |
| 1 | drishti-risk builds (no `wip: unbuilt` left); **`RiskRecorder` run first** against Drishti built at `3813b8735`; `tck/data` and the raw-bit cases for market risk and CCR committed (MC-3); unit tests, `MarketKitIT`, `CcrKitIT`, `LineOfBusinessIsolationTest` green; the two per-line JARs (MC-8) | `./mvnw clean verify` in drishti-risk; recorder cases pass to the bit | drishti-risk README (status, product ids), CHANGELOG | revert in drishti-risk; Drishti untouched |
| 2 | `config/engines/market-risk.yaml`, `ccr.yaml`; the server starts with both JARs in `lib/engines` and both engines `running`; the two-loader native test | drop-in test with the Risk JARs; Admin -> Engines | ENGINES_IN_DRISHTI (Risk as the worked example), CONFIGURATION.md regenerated | delete the two files and the JARs |
| 3 | **Side by side** in one JVM (`RiskSideBySideTest`, `drishti-modules-it`): every golden scope asked in-tree and through the engine, raw bits; then the widened corpus (MC-2): scope expressions and ad-hoc sets of 1, 50, 50,000 positions; parametric equal and EWMA; stressed and 10-day (both methods); components; what-if; backtest; period statistics (112,041,646); reports; FRTB; SIMM; exposure summary, profile, pre-deal; each under three visibilities (all, one desk, one legal entity); heap and mapped tier; 1, 4 and 24 threads; Java and OpenBLAS (OpenBLAS at the 1e-12 rule, recorded apart); workbooks byte for byte; the four stack figures by name. The three post-freeze classes (F6: `VarHomeMeasures`, `VarHomeBacktest`, `ExposureHomeMeasures`) are covered here | zero differing scopes | the test and its row in TEST_HARNESS | test-only: revert |
| 4 | The goldens over the host: `PackagedVarGoldenTest` asks the engine; `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden`, `HierarchyEquivalenceTest` rewritten in `drishti-modules-it`, expected files copied unedited (MC-11) | all pass | TEST_HARNESS; the goldens' new home named in VAR_GUIDE's proof section | test-only: revert |
| 4a | **Speed** (MC-4, EN-49): `engine tck bench` and `tools/bench/var_bench.py`, in-tree against engine, at 200,000 and 1,000,000 positions, one heavy job at a time: first open cold and warm; p50 within 5 %; p99 at 16 callers and bytes per ask within 10 %; peak memory within 5 %; 1,000,000 at `-Xmx6g`; `VarTierBenchmark`, `ExposureTierBenchmark`. Needs, in drishti-risk first: vectors taken without a copy (the sink's array handed over once, or the engine's own mapped file as today's hot file) and one shared book with visibility as a selection (`access.isolation: shared`, kit laws filter-equivalence and interleaving) instead of a book per visibility (F3) | figures within the bounds | BENCHMARK.md (one section, the figures) | none needed (a measurement) |
| 4b | **Coverage** (MC-1), **on the computation contract** (ENGINE_INTEGRATION_DESIGN section 18, EN-66 to EN-74; SPEC section 19), never as bespoke outputs: the host resolves every scope to a `RowSet`; the engine offers `aggregate(view, rows, groups)` and `merge`, and measures as functions of an aggregate; path, tree, matrix, compare, what-if, sharding and intraday are uses of the two; captures and history are host sinks; the MR cube is the `query` engine; edge cases are declared functions (`@EngineFunction`, EN-66). Bits per EN-71: merge equals single pass when each unit is summed whole by one party; a split unit chains the open block's accumulator or is summed again from rows, never aggregate plus a delta; step 3 proves an ad-hoc set on a sharded date equals the single-server bits. every route of the 15 controllers and every kept class of 6.3 has an engine output or capability in drishti-risk, or "dropped, owner agreed". Today's gaps (F4) are R2 work: scope-expression and ad-hoc/personal basket asks, P&L path, matured items, history, currencies, hints, tree, matrix, compare, captures, contributions by attribute, MR cube headline/detail/history, exposure distribution/contributions/tree/what-if, SIMM `against`, playground estimate and recipes, correlation and beta, `Partitioned` (shards) and `Incremental` (intraday) | the coverage table (appendix A, filled as outputs land) has no empty cell; each new output has kit cases and is in step 3's corpus | the descriptors' docs; drishti-risk CHANGELOG | revert in drishti-risk |
| 5 | **Switch, every kept class** (MC-5): FRTB, SIMM, MR cube, sensitivities, regulatory, stress window, system baskets, playgrounds; pivot provider and lens, Flight form, PV stream, ready rule, caches, capacity figures, role homes, explainers, why panels, trade links, approvals, shard classes. One switch per route family (`in-tree` or `engine`), so a family goes back without a revert | step 3's test compares the routes against the recorder; a route-shape test per route against the engine (the console's fakes keep their JSON); the console's VaR browser tests | 6.4 "stay, rewired" and "engine lists and counts"; 6.5 guides, API and CLI references, help | flip the family's switch back |
| 5a | **Risk off** (MC-7): with `lib/engines` empty and banking on, the server starts; every risk route answers one sentence naming the missing engine (not 404, not 500); menus, mnemonics, role-home tiles, pivot and BI measures, Flight forms, alerts, ready rules, caches and capacity lines of risk are absent; no stored copy served as live. The same with one engine absent, and with a licence that does not grant the product (`ENG-1014`) | `test_engine_off_everywhere.py` extended; the drop-in lane | ADMIN_GUIDE (risk off) | revert |
| 6 | **Deletion** (only when 4b has no empty cell and 5, 5a are green): tag `pre-r2-cut` first; one commit per package group in dependency order, each green on its targeted tests: `banking-compute` engine packages, the engine half of `banking-service`, `compute.vector`, `compute.linalg`, `server.playground`, `OpenBlasEngine`, `VarResultEngine`, `ExposureResultEngine`, the old engine files (keys kept as aliases), `module.yaml compute.engines`, the reactive blocks; the 85 moved tests | full verify; a guard over the **reactor modules** (F9: `engines/changes` is outside the reactor): no source imports `com.ash.risk`, no class implements `com.ash.engine.Engine`, no pom names an engine artifact | 6.4 "deleted at step 6" and the guards' allow-lists; 6.5 documents | the tag `pre-r2-cut` |
| 7 | Upgrade proof, **before the drill**: Risk 1.0.0 replaced by 1.0.1 in `lib/engines`, the server jar's hash unchanged, the goldens pass | the drop-in lane | DEPLOYMENT_AND_QA_GUIDE (upgrade) | put the old JAR back |

## 4c. Switching a route (the template; lanes D and E follow it)

The seam is owned by lane A and does not change under the other lanes (ask lane A through the lead): the kernel's
`com.ash.drishti.kernel.api.services.Engines` (`Kernel.engines()`, or the bean injected), `KernelEngines` in the server, `engine-api`'s
view types. The first route switched end to end is the VaR headline (`GET /api/v1/risk/var/scope`, one node); copy it.

1. **One family switch.** A module setting per route family, `drishti.module.<module>.<family>.path`: `in-tree` (the default until the
   family is green) or `engine`, declared in the module's `module.yaml` `settings:` and in its engine file (`engines/var.yaml`:
   `var.route`). A request may name its own path (`path=engine` or `path=in-tree`), so both answers come from one server.
2. **The engine path class.** One class per family beside the controller (`banking/var/VarEnginePath`): it asks
   `Engines.ask(principal, engine, outputs, params, snapshot)` by output name and reads plain values back (tables of rows by column). It
   names no Risk type (step 6's guard). It returns the route's own JSON shape: the same keys, the in-tree rounding (whole units by
   `VarViews.round`), plus the engine's exact figures (`varExact`, `esExact`) for the bit check. What the engine path does not answer yet
   is refused in one sentence naming `path=in-tree`, never answered half.
3. **The controller.** `if (enginePath.engine(path)) return enginePath.<route>(...)` at the top of the handler; the in-tree code below
   is untouched until step 6. In-process callers (`RiskOperations`) pass `null` for `path` (the family switch decides).
4. **The side by side.** In `testing/drishti-modules-it`: `SideBySide` (`both(server, what, route, fields...)` asks both paths and
   compares the named fields; `bits(what, reference, engine)` compares the engine's exact figure with a raw-bit reference: the golden's
   doubles, which `VarGoldenMatrixTest` holds exactly, or the recorder's). `RiskSideBySideTest` is the pattern: one packaged server
   with the Risk JARs dropped into `lib/engines` (`SideBySide.dropJars`, `-Ddrishti.risk.jars=<folder>`), its own lake, every golden
   scope of every case; differences and raw-bit differences must both be empty. Run:
   `./mvnw -o verify -pl testing/drishti-modules-it -Dtest=RiskSideBySideTest -Ddrishti.risk.jars=<folder> -Ddrishti.python=<python with drishti_lake>`.
5. **Switch.** When the family's side by side is green, its default becomes `engine` in the module's engine file, in the same
   commit as the guide's line, and the coverage row below is ticked with the commit.

### Appendix A: coverage (a row per route and kept class; filled as routes switch)

| Route or class | Engine output | Side by side | Switched |
|---|---|---|---|
| `GET /api/v1/risk/var/scope` (one node, offered lens) | `market-risk` `node-var` | `RiskSideBySideTest.theVarHeadlineOfEveryGoldenNodeIsTheSameOnBothPaths`: 1,572 node answers (3 dates x 509/250 x USD/EUR), trades, VaR, ES and limit equal, **every VaR and ES equal to the in-tree raw bits (3,144 of 3,144)**; limit use equal on USD/509. Open: limit use in another currency or window (the in-tree route divides by another VaR than the one asked; align in the engine or the route before the switch) | path available (`path=engine`); default stays `in-tree` until the whole headline (lens, diversification, backtest, provenance) is covered |
| Role Home measures `VarHomeMeasures` (VAR, ES, LIM_USE, PNL_DAY, BREACHES, desk VaR and limit rows), `VarHomeBacktest` | `market-risk` `node-var`, `backtest-days` | engine path behind `var.consumers.route` (`VarEnginePath.nodes/node/history`); `HomesSideBySideTest` (two servers, one lake) skips: the module's `MeasureSource` and `HomeTable` beans are not handed to the kernel's `HomeTables` (`ModuleBridge`), so the Homes' VaR panels are empty in a packaged server on both paths | path available; default `in-tree` until the bridge is wired and the test compares rows |
| `GET /api/v1/risk/var/scope/path` (P&L path: series, histogram, worst 25, ranked table with the part that lost most) | `market-risk` `scope-path`, `scope-scenarios`; the parts `scope-var` per child node | `RiskSideBySideTest.thePnlPathOfEveryGoldenNodeIsTheSameOnBothPaths`: 246 node paths (3 dates x USD/EUR, 509 ranked rows each); **USD: every field equal**; VaR and ES equal to the in-tree raw bits (492 of 492); EUR: 67 paths differ only in a part's P&L by a few units (the in-tree route takes a child part from the book converted trade by trade, the engine and the in-tree headline from the per-currency totals: the open EUR decision). Refused on the engine path in words: a leaf node (parts are positions; 540 asked), expressions and lists (parts are terms or positions), a lens, matured items, the parametric curve (`parametric=false` answers) | path available (`path=engine`); default `in-tree` |
| `POST /api/v1/risk/var/what-if` | `market-risk` `what-if` (node aggregate; the items' vectors from `scope-var` or supplied) | `RiskSideBySideTest.theWhatIfOfEveryGoldenNodeIsTheSameOnBothPaths`: 393 node what-ifs (3 dates, USD; an unwind of a position, half a desk, a priced vector): before, after, change, every item and the limit block (use before and after as doubles) **all equal**. Refused on the engine path: a scope that is not a node, a currency other than USD | path available; default `in-tree` |
| Every scope kind on `GET /scope` (expressions, system baskets, ad-hoc trade and position lists, counterparty) | `market-risk` `scope-index`, `scope-positions`, `scope-position-values` and `scope-limits` (both in the reporting currency), `scope-nodes`, `scope-var` | `RiskSideBySideTest.everyScopeKindIsTheSameOnBothPaths`: 300 answers. **Heap tier (`-Dsbs.tier=heap`): USD every field and raw bit equal.** Default mapped tier: 35 USD answers differ in the last units (row-level sums: the hot file keeps position rows as float32, the engine sums float64: MC-12's float32 view is what closes it). EUR: system basket limits and amount rules now in the reporting currency (104 differences to 8); what is left is the EUR canonical (the in-tree route sums a row set's EUR P&L from the converted book, the engine from the per-currency totals) | path available; default `in-tree` until the float32 view (lane A) and the EUR decision |
| `GET /api/v1/risk/var/tree` (every node of a hierarchy, the explorer's left pane) | `market-risk` `node-var`; the walk, names and system baskets Drishti's (`EngineScopeBook`) | `RiskSideBySideTest.theTreeOfEveryHierarchyIsTheSameOnBothPaths`: 18 trees (3 dates x USD/EUR x 3 hierarchies), 786 node rows: **USD every field equal** (order, name, level, parent, depth, VaR, ES, limit, limit use and status, trades, expandable, the (Blank) note), **VaR and ES of every golden node equal to the golden raw bits (1,572 of 1,572)**; EUR: every field equal on the heap tier (convert first, 6.6 (2)); mapped tier last units (float32 rows, MC-12). Parametric cells from `node-pvar` (6.6 (4)). Refused on the engine path: a lens, matured items, `within` (a basket's own tree) | path available; default `in-tree` |
| `GET /api/v1/risk/var/scope/contributions` (a node's child nodes) and the headline's parts block (`children`, `childKind`, `sumStandalone`, `diversification`) | `market-risk` `scope-contributions` (new: parts as row sets, each summed as `scope-var`, measured by the calculator's `contributions`) | `RiskSideBySideTest.theContributionsOfEveryGoldenNodeAreTheSameOnBothPaths`: 246 nodes with child nodes x (contributions + headline parts) = 492 answers: **USD every row and sum equal, VaR and ES equal to the golden raw bits (252 of 252 USD)**; EUR: every row equal on the heap tier (convert first, 6.6 (2)); mapped tier last units (MC-12). Refused on the engine path: nodes whose parts are positions (540), `by` position/trade/desk/book/attribute, a scenario column, the matrix, the parametric columns | path available; default `in-tree` |
| `GET /scope` through a horizon or the stress set (`horizon=10d`, `method=sum`, `set=stressed`) | `market-risk` `node-var-horizon` | `RiskSideBySideTest.theHorizonsAndTheStressSetAreTheSameOnBothPaths`: 1,441 node answers (10-day by the square root of 10 and by sums, stressed 1-day and 10-day, dates with a stress set): **every field equal** (VaR, ES, scenarios, trades, limit, limit comparable, limit use), **USD VaR and ES equal to the in-tree raw bits**; EUR 10-day equal on the heap tier (6.6 (2)); mapped tier last units (MC-12). Refused: another confidence, a scope that is not a node | path available; default `in-tree` |
| `POST /api/v1/risk/var/what-if` on any scope (expressions, system baskets, ad-hoc lists, a counterparty) | `market-risk` `what-if` with `rows` (new: the before is the row set summed as `scope-var` sums it) | `RiskSideBySideTest.theWhatIfOnEveryScopeKindIsTheSameOnBothPaths`: 39 what-ifs, 3 dates, USD: **heap tier every field equal** (before, after, change, items, a system basket's limit block); 6 refused on both paths (single trades of position-grain data); the mapped tier counted, not asserted (float32 rows, MC-12) | path available; default `in-tree` |
| The reporting currency setting `drishti.module.banking.var.reporting-currency` (USD as shipped) | (the host passes it as `currency`) | `RiskSideBySideCadTest` (one server at CAD): a request naming no currency is answered in CAD on both paths, **to the bit as `ccy=CAD` (1,572 of 1,572)**; trades and limits equal between the paths; CAD figures: heap tier every field and raw bit asserted equal (convert first, 6.6 (2)); mapped tier last units (MC-12) | setting available; default USD |
| Every currency other than USD on every route: the book converted first (decision 6.6 (2)) | `market-risk` `scope-var` and the row-set outputs on the converted book; `basket-var` limit converted | `RiskSideBySideTest` with `-Dsbs.tier=heap`: tree, contributions, path, horizons, scope kinds, what-ifs, headline **EUR 0 differences, 0 raw-bit differences**; `RiskSideBySideCadTest` heap: CAD every field asserted. Re-recorded: 9 EUR answers of `PackagedVarGoldenTest` (mapped tier), the EUR `basket-var` limits of `MarketRecordedIT` (336 cells); USD goldens unchanged | rule in both paths; default `in-tree`, USD |
| The parametric block of a node's headline and the tree's parametric cells (`parametric` absent or true) | `market-risk` `node-pvar` (with `SKEW`, `EXCESS_KURTOSIS`, `Z_ES`, `ES_FACTOR`, `K`, `SCENARIO_SET`, `LAST_DAY`) | `RiskSideBySideTest.theParametricFiguresOfEveryGoldenNodeAreWithinRelative1e12OnBothPaths`: 2,358 blocks, **32,226 numbers within relative 1e-12 (all)**, words equal (decision 6.6 (4)). Refused on the engine path: `parametric=true` through a horizon or the stress set; path curve, contributions' columns and matrix stay `parametric=false` | path available; default `in-tree` |
| `GET /api/v1/risk/var/scope/history`, the replay (a scope without official records: today's members over the newest `history-days` scenario days against today's VaR, its backtest) | `market-risk` `scope-backtest` (new: the row set summed as `scope-var` sums it, `Backtest.of` with the scaled Basel light, 6.6 (3)), `scope-scenarios` | `RiskSideBySideTest.theReplayedHistoryOfEveryGoldenNodeAndScopeKindIsTheSameOnBothPaths` (heap tier): 604 replay answers (3 dates x USD/EUR, every golden node without two official records, 8 scope kinds, the ad-hoc lists), every row, zone and word equal (the counts with the official mode below). Refused on the engine path: a lens | path available; default `in-tree` |
| `GET /api/v1/risk/var/scope/history`, the official mode (a node with two or more official records the caller sees whole: its records' VaR, ES and hypothetical P&L, each day tested against the record before) | `market-risk` `official-history` and `official-backtest` (new, over `var-history`: the published records, the latest result version of each date at the engine's confidence, the newest `days`, `Backtest.of` as in-tree); the host reads an input the engine declares `per: []` on every date its kind holds (`LakeInputs`, `KindData.dates`: the engine selects by date) | Same test, the side by side capturing 2026-09-28, -29 and -30 officially at start (`POST /var/official`, oldest first; `PackagedServer` points the published records' reader, `LAKE_VAR_OFFICIAL_ROOT`, at the publisher's root): **864 answers, 260 of them official (649 official rows: USD nodes on 09-29 and 09-30), 0 in-tree only; 462,723 numbers, all within relative 1e-12 (VaR, ES, P&L whole units equal; only the likelihood ratios and p-values may differ in the last bits), every row, flag, zone and word equal**. Refused on the engine path in words: official records in a currency other than USD (only USD is published); the engine reading fewer records than the store holds (publish off, or a session opened before a capture: no `data-version` trigger on `market-risk` yet) | path available; default `in-tree` |
| `GET /api/v1/risk/var/statistics` (a node's period statistics of the official records: period end, average, high, low with each record's date, version and capture id, the weekdays without a record, the capital formula's VaR and stressed VaR averages) | `market-risk` `official-statistics` (new, over `var-history` with `CAPTURE_ID`, and `desk-pnl` for holidays: the averages summed in the in-tree order, so the figures are its bits) on the newest official date; a scope word resolved by the engine's index | `RiskSideBySideTest.theStatisticsOfEveryGoldenNodeAreTheSameOnBothPaths`: 2,144 answers (131 golden nodes and BANK, DESK:RATES, LE:LE-NY x VaR, ES, stressed VaR and ES x the default fiscal year, FY2026Q4, a two-day range, FY2025), 1,340 with figures, **15,946 numbers: every field equal, every number to the bit (0 differences)**. Refused on the engine path in words: no official record yet; a period holding a synthetic demo history (never published); the engine reading other records than the store (publish off, a session opened before a capture); other fiscal-year, average-day or stress-week settings in the engine's options | path available; default `in-tree` |
| The P&L path's parametric block and normal curve (`GET /scope/path`, `parametric` absent or true, a node with child nodes) | `market-risk` `node-pvar`; the block and the curve over the histogram's raw edges shaped by Drishti's `VarParametric` as in-tree (decision 6.6 (4)) | `RiskSideBySideTest.theParametricFiguresOfEveryGoldenNodeAreWithinRelative1e12OnBothPaths` (heap tier): 246 path blocks with their curves (3 dates x USD/EUR, every golden node with child nodes) beside the 2,358 headline and tree blocks: **107,256 numbers, all within relative 1e-12, every word equal**. Refused on the engine path: the parametric block of a scope that is not a node (one trade: `parametric=false` or in-tree) | path available; default `in-tree` |
| the rest of 6.3 | | | |

## 5. Risks

- Inputs arrive through the engine API's `Inputs` (columns) instead of `DataNode` documents, through new intake code (F2): step 3 compares
  raw bits scope by scope before any route changes.
- Speed and memory (F3): a heap copy per visibility cannot meet step 4a; the engine changes come before the speed step, not after it.
- Two loaders and one native library (F7): proven at step 2, measured at step 4a.
- Scope: the outputs of step 4b are most of the R2 work; the deletion waits for them. No route stays in-tree past step 6 (MC-1).

## 6. Fable review (2026-11-22)

Read only: nothing was built or run. Read against `DRISHTI_RISK_DESIGN.md` and `ENGINE_INTEGRATION_DESIGN.md` (on `wip/i7b-products`; they
are not on `i8/develop`), drishti-risk `develop` `c7c623e`, drishti-engine `develop` `a3196c0`, Drishti `i8/develop` `d8a6b8e4d`.

**Verdict: GO WITH CHANGES.** Steps 0 to 4 are additive and may start. Steps 5 and 6 (the switch and the deletion) are NO-GO until the
must-change list is in the plan and green. The order (nothing deleted before the side by side) is right; the plan is too thin on what the
engine cannot answer yet, on speed, and on everything outside the two route families.

### 6.1 Facts the plan does not state

| # | Fact | Where |
|---|---|---|
| F1 | Every drishti-risk commit is `wip: unbuilt`. `RiskRecorder.java` has never run: `tck/recorded` holds harvested goldens only (whole units, with Drishti's bits for node and basket VaR and ES); there is no `tck/data`. The CCR reference is compared within a float32 tolerance, "never as bits". Parametric, stressed, 10-day, backtest, period statistics, what-if and reports have hand cases only | drishti-risk `tck/`, the split recorder folder |
| F2 | The engines are a new assembly over the calc classes (`VarBook`, `VarMath`, `ParallelSums`). They do not use `VarService`, `VarLoader`, `VarIntake`, `VarTier`, `VarHotFile`, `VarSelection`, the shard classes or `VarIntraday`. The intake is new code, and K5 is not done (`DataNode` and about 30 other host types still parked in `hostcompat`) | `risk-engine-market/.../MarketDay.java`, `MarketBooks.java`; `java/SPLIT_EXEMPTIONS.md` |
| F3 | Vectors are copied at the seam and again in the book: `Arrays.copyOf(values, length)` per row into a heap map (`MarketDay` 213, `CcrDay` 120), then `new double[]`/`clone()` in `MarketBooks` (79, 109). One book is built per (set, window, currency, **visibility signature**). No mapped tier. BENCHMARK 4.8 runs 1,000,000 positions in a 6 GB heap; one heap copy of those vectors is about 4 GB | as F2 |
| F4 | Outputs are by hierarchy node and system basket. There is no output for a scope expression (`BANK - DESK:RATES`), a personal or ad-hoc basket (the 50,000-position basket of BENCHMARK 4.3), the P&L path, matured items, history, currencies, hints, tree, matrix, compare, captures, contributions by attribute, the MR cube headline/detail/history, exposure distribution/contributions/tree/what-if, SIMM `against`, FRTB/SIMM/VaR playground estimate and recipes, correlation and beta. Capabilities are `[explain, workbook]` only: no `Partitioned` (cluster shards, `VarShardController`), no `Incremental` (intraday) | `market-risk.engine.yaml`, `ccr.engine.yaml` |
| F5 | 68 of the 161 `banking-service` main classes stay, and about 60 of them compile against the half that is deleted (`VarService`, `VarBook`, `VarViews`, `VarDate`, `OrdinalSet`, `ExposureService`, ...). The plan has no step for them (design D4, lane E15) | list in 6.3 |
| F6 | Three classes were added after the freeze commit `3813b8735` and exist in neither the plan nor drishti-risk: `VarHomeMeasures`, `VarHomeBacktest`, `ExposureHomeMeasures` (role homes) | `banking-service/.../var`, `/exposure` |
| F7 | drishti-risk builds one JAR per line (`drishti-risk-market-<v>.jar`, `drishti-risk-ccr-<v>.jar`, products `drishti-risk-market`, `drishti-risk-ccr`; CHANGELOG "owner, 2026-11-22"). The plan's step 1 merges them again. README and `FileLicence`'s example say product `drishti-risk`. With two JARs the kernel and its OpenBLAS binding (bytedeco) load in two class loaders; a JVM loads one native library in one loader only (not tested here) | drishti-risk `java/risk-engine-*/pom.xml`, `README.md`; `kernel/drishti-engine-host/.../FileLicence.java` |
| F8 | The exposure routes are `/api/v1/risk/exposure/*`, not `/api/v1/ccr/exposure/*` (step 5) | `ExposureController` |
| F9 | `engines/changes/.../ChangesEngine.java` implements `com.ash.engine.Engine` inside the Drishti repository (outside the reactor): step 6's guard must scan reactor modules, or it fails on it | `engines/changes` |

### 6.2 Must change (before step 5; shortest list)

1. **Coverage table, and no partial deletion.** Add to the plan one row per route of the 15 controllers and per kept class of 6.3: the
   engine output or capability that answers it, or "dropped, owner agreed". Replace the Risks line "any route not covered stays in-tree":
   a route that stays keeps `VarBook` and `VarService` alive, which is two engines in one release. Step 6 starts only when the table has
   no empty cell. Today's gaps are F4; each needs an output in drishti-risk first (scope and ad-hoc basket asks, path, tree, matrix,
   compare, captures, cube, exposure tree/what-if, correlation, beta, `Partitioned`, `Incremental`).
2. **Bits, not "within one".** Step 3 is exact raw bits for every scope of every case, in one JVM; a scope that differs is named and
   stops the plan (delete "the within-one count"). Widen the corpus beyond the goldens: scope expressions and ad-hoc sets (1, 50, 50,000
   positions), parametric (equal and EWMA), stressed and 10-day (both methods), components, what-if, backtest, period statistics
   (112,041,646), reports, FRTB, SIMM, exposure summary/profile/pre-deal; each under at least three visibilities (all, one desk, one
   legal entity), heap and mapped tier, 1, 4 and 24 threads, Java and OpenBLAS (OpenBLAS at today's 1e-12 rule, recorded apart).
   Workbooks byte for byte. BANK 167,372,692 / 210,131,972 and DESK:RATES 74,633,680 / 79,246,576 asserted by name.
3. **Run the recorder first.** Step 1 includes: build Drishti at the freeze commit, run `RiskRecorder`, commit `tck/data` and the
   raw-bit cases for market risk and CCR, and drop the word "unbuilt". The plan's section 1 row "every double as its raw bits" is true
   only after that. The three post-freeze classes (F6) are covered by step 3, not by the recorder.
4. **A speed step before deletion (EN-49).** New step between 4 and 5: `engine tck bench` and `var_bench.py` on the side-by-side
   branch, in-tree against engine, at 200,000 and 1,000,000 positions (one heavy job at a time): first open cold and warm, p50 within 5%,
   p99 at 16 callers and bytes per ask within 10%, peak memory within 5%, 1,000,000 at `-Xmx6g`; `VarTierBenchmark`,
   `ExposureTierBenchmark`; figures into `BENCHMARK.md`. It cannot pass with F3: the engine must take vectors without a copy (the sink's
   array handed over once, or its own mapped file as today's hot file) and must not build a book per visibility (one shared book with
   the visibility applied as a selection, `access.isolation: shared` with the kit's laws, EN-47).
5. **Step 5 covers every kept class, not two route families.** FRTB, SIMM, MR cube, sensitivities, regulatory, stress window,
   system baskets, playgrounds; the pivot provider and lens, Flight form, PV stream, ready rule, caches, capacity figures, role homes,
   explainers, why panels, trade links, approvals, shard classes (6.3). Each is rewired, or deleted with the reason, in the commit that
   makes it compile without the engine half.
6. **Packs and the other module.** `market-risk/pack.yaml` (`var-engine`), `counterparty-risk/pack.yaml` (`exposure-engine`) and
   **`modules/market-data/packs/market-data/pack.yaml` (`correlation-engine`: `correlation-result`, `equity-beta`)** are reactive
   blocks on the deleted classes; they become `uses.engines`, generated by `tools/packgen/banking/make_packs.py`. Correlation and beta
   need an output or their own JAR (design section 12) before step 6.
7. **Risk off is a step with a test.** With `lib/engines` empty and the banking module on: the server starts; every risk route answers
   one sentence naming the missing engine (not 404, not 500); menus, mnemonics, role-home tiles, pivot and BI measures, Flight forms,
   alerts, ready rules, caches and capacity lines of risk are absent; no figure is served from a stored copy as if live.
   Extend `test_engine_off_everywhere.py` and the drop-in lane; the same with one of the two engines absent, and with a licence that
   does not grant the product (ENG-1014).
8. **JAR and licence, decided once.** Follow the owner's line isolation: one JAR and one product id per line, as the repository builds
   today; remove "step 1 adds the merged-JAR module" (or record the owner's word for merging). Then: one product-id spelling in the
   descriptors, README, `FileLicence` and the drop-in licence file; a test that both JARs loaded together give `linear-algebra: native`
   in both (F7) or the plan says which falls back and the speed step is run that way; `LineOfBusinessIsolationTest` green; the empty
   liquidity and climate modules produce no JAR and register nothing.
9. **Rollback written per step.** Steps 0 to 4: revert the commit, nothing in production code changed. Step 5: one switch per route
   family (in-tree or engine) so a family is put back without a revert, removed in step 6. Step 6: one commit per package group in
   dependency order, each green on its targeted tests, tagged before the first (`pre-r2-cut`); after it the only way back is the tag.
   Step 7 before the drill, not after.
10. **Tests and documents in the same commit as the code they describe**: sections 6.4 and 6.5 become rows of the step table.
11. Small: F8 (route name); F9 (guard scope); the design documents merged to `i8/develop`; the goldens' new home named (their
    classes move to drishti-risk, so `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden`, `HierarchyEquivalenceTest`
    in Drishti are rewritten in `drishti-modules-it` over the host with the expected files copied unedited).

### 6.3 Callers the plan does not name

Under `modules/banking/java/banking-service/src/main/java/com/ash/drishti/banking/` unless a path is given.

| Group | Files |
|---|---|
| Role homes (post-freeze) | `var/VarHomeMeasures`, `var/VarHomeBacktest`, `exposure/ExposureHomeMeasures`; `kernel/drishti-server/.../server/home/` reads them |
| Figures on screens | `var/VarFigures`, `exposure/ExposureFigures` (moved, but registered as `ViewAugmenter`), `var/VarTradeLinks`, `exposure/ExposureTradeLinks`, `var/VarWhyPanels`, `var/VarNumberExplainer`, `exposure/ExposureNumberExplainer`, `exposure/ExposurePreDealExplainer` |
| Admin and sizing | `var/VarCaches`, `exposure/ExposureCaches` (`CacheGroup`), `var/VarCapacityFigures` (`CapacityFigures`), `kernel/.../capacity/planner.yaml`, `tools/sizing_planner.py` |
| Cluster | `var/VarMeasure` (`ShardedMeasure`), `var/VarShards`, `var/VarShardRows`, `var/VarShardController`, `var/VarNodeParts` |
| Kinds | `var/VarPositionsProvider`, `var/VarSystemBasketsProvider` (`KindProvider`), `var/VarMatrixLake`, `var/VarMatrixController` |
| Jobs and rules | `var/VarReadyRule`, `var/VarPvStream`, `var/VarPvStreamPlugin`, `var/StressWindowApprovals`, `var/VarReportApprovals`, `var/VarReportSignoff`, `config/alerts/platform-alerts.yaml` |
| Pivot and BI | `var/VarPivotProvider`, `var/VarPivotLens`, `var/VarPivotCodes`, `var/MrCubeExtras`, `var/MrCubeController` |
| Saved content | `var/VarSaved`, `var/VarBasketEdit`, `var/VarBasketDelete`, `var/VarSystemBasketEdit`, `var/VarBasketController` |
| Wiring | `VarConfiguration`, `ExposureConfiguration`, `FrtbConfiguration`, `SimmConfiguration`, `MrCubeConfiguration`, `VarRegulatoryConfiguration`, `BankingAutoConfiguration`; `META-INF/drishti/engines.yaml`, `moved-classes.properties`, `resources/engines/*.yaml`; `modules/banking/module.yaml` (`compute.engines`, `java.jars`), `modules/banking/pom.xml`, `modules/banking/java/kernel-internals.txt` |
| Kernel | `kernel/drishti-server/.../nativelib/NativeLibraries.java` and `resources/engines/openblas.yaml` (stay, over `Engine.notes()`), `server/playground/{ExportSizing,PlaygroundTemplate,PlaygroundWorkbook,SeriesLayout}.java`, `application.yaml`, `kernel/drishti-api/.../resolution/ModuleLayout.java`, `config/engines/{var,exposure,frtb-sa,simm,sensitivities,mr-cube,openblas}.yaml`, `config/lakes/banking/var-official.yaml` |
| Other modules | `modules/market-data/packs/market-data/pack.yaml` and its `correlation-result`, `equity-beta` contracts; `modules/trading/.../TradingStandIn.java` (test) |
| Console | `drishti-console/plugins/banking/risk_api.py`, `routes/{var,var_regulatory,exposure,frtb,simm,mcube,play,smat}_routes.py`, `static/js/drishti_exposure-explorer.js`, `core/backend.py`, `core/facts.py`, `web/templates/admin/_native_banner.html`, `_health_body.html`, `modules/banking/console/plugin.yaml` |
| Tools | `tools/{varcli,expcli,frtbcli,simmcli,pivotcli}.py`, `tools/bench/{var_bench,users}.py`, `tools/ccr/{whatif_api_parity,record_whatif_parity}.py`, `tools/lake/{demo,maintain}.py`, `tools/packgen/banking/*`, `tools/packgen/{var,ccr}/*`, `tools/pvgen/*` (moves), `tools/drillkit/{container_smoke,explain_hammer,lake_cache}.py`, `tools/docs/shots/{_risk,t25,t29}.py`, `tools/module_layout.py`, `tools/domain_strings.py`, `tools/xlsx_before_after/capture_patch.py` |
| Benchmarks | `testing/drishti-benchmarks/.../ManyScopeBenchmark.java`; `VarTierBenchmark`, `VarScaleBenchmark`, `ExposureTierBenchmark`, `ColumnHierarchyBenchmark` (banking tests; BENCHMARK.md 2.5 runs them by name) |

### 6.4 Test impact

| Tests | Count | Fate | Breaks on |
|---|---|---|---|
| Banking tests whose class is in drishti-risk (`banking-compute` all; `banking-service` unit tests of moved classes, the four goldens, `ExposureParityTest`, the benchmarks) | 85 of 166 | **deleted in Drishti at step 6** (they live in drishti-risk); the four goldens and the two parity files rewritten over the host (must-change 11) | package `com.ash.drishti.banking.*`; resources `var/golden-matrix.json.gz`, `var/hierarchy-golden-2026-09-30.json`, `var/system-baskets-golden-2026-09-30.json`, `var/whatif-parity.json`, `ccr/reference.json`, `ccr/whatif-parity.json`, `simm/simm-toy.yaml` |
| Banking API tests on the harness (`VarApiTest`, `VarHierarchyApiTest`, `VarCurrencyApiTest`, `VarIntradayApiTest`, `VarParametricApiTest`, `VarStressApiTest`, `VarStressWindowApiTest`, `VarRegulatoryApiTest`, `VarPlaygroundApiTest`, `VarExplainApiTest`, `VarBasketSheetApiTest`, `VarSystemBasketApiTest`, `VarMatrixApiTest`, `VarPivotApiTest`, `VarMrCube*Test`, `VarShard*Test`, `VarPvStream*Test`, `VarReadyRuleTest`, `VarOfficialBi*Test`, `Var*RiskEditionTest`, `Exposure*ApiTest`, `ExposureOfficial*Test`, `FrtbApiTest`, `SimmApiTest`, `VarWhyScenarioCardTest`, `VarResourcesSoakTest`, ...; fixtures `BankingTestHarness`, `BankingTestServer`, `VarLakeFixture`, `VarPvFixture`, `VarHierarchyFixture`, `ExposureLakeFixture`, `VarIntradayLake`) | about 80 | **stay, rewired**: the harness copies the Risk JAR(s) into a `@TempDir` lib folder and writes the engine files. Expected values unedited. The largest test item, with no row in the plan | no JAR on the class path; `VarSiteEngineFileTest`, `VarLensSettingsTest`, `VarShardingSettingsTest` (`var.yaml` keys); `VarComputeFirstGuardTest` (scans moved sources); `VarInstalledModuleRestartTest`, `EditionShardClusterTest` (module jar names: check `drishti-compute-banking.jar`) |
| Engine lists and counts | | **stay, expected lists change** | `BankingEngineCatalogueTest` (`containsExactly("var","exposure","sensitivities","frtb-sa","simm","mr-cube")`); banking `server/engines/{EngineApiTest,EngineFilesTest,EngineRegistryTest}`, EngineClusterTest, `CacheAdminTest`, `HealthApiTest`, `capacity/CapacityControllerTest`, `PlatformMetricsApiTest`, `BankingModuleOffTest`/`OnTest`, `BankingBindingsTest`, `TokenScopeGuardTest` (`token-scopes.yaml` routes); kernel EngineCatalogueTest, `KernelWithoutModulesTest`, `modules/ModuleGateFilterTest`, `nativelib/NativeLibrariesTest`, `playground/LibreOfficeRecalc`; `kernel/drishti-api` `ModuleLayoutTest`, `ModuleOverridesTest`; `kernel/drishti-packs` `ModuleSettingsSiteSetsTest`; `kernel/drishti-registry` `AccessRegistryTest` |
| Architecture guards and allow-lists | | **stay, lists edited in the deleting commit** | `testing/drishti-it`: `ArchitectureRulesTest`, `OneGrammarRulesTest`, `ServerJarHoldsNoModuleTest`, `src/test/resources/flat-harness-tests.txt`, `count-only-caches.txt`; `testing/drishti-modules-it`: `ModuleArchitectureRulesTest`, `PackagedPackLifecycleTest`, `PackagedServer`, `PackagedVarGoldenTest`, `ModuleSettingsTest`, `ModuleEngineFilesTest`; `modules/banking/java/kernel-internals.txt`; the new guards of step 6 |
| Console pytest and browser | about 60 files | **stay**; route tests on `engine_fakes.py`/`engine_standin.py` are unchanged only if the aliases keep the JSON shapes (prove it: one shape test per route against the engine); live stacks (`var_stack.py`, `trade_grain_stack.py`, `conftest.py`) must put the JAR(s) in the stack's `lib/engines` | `test_var_*` (16), `test_pvar_*`, `test_exposure_*`, `test_predeal_browser`, `test_frtb*`, `test_simm`, `test_mcube`, `test_smat*`, `test_play*`, `test_stress_window_*`, `test_blas_switch_browser`, `test_native_banner`, `test_native_select_guard`, `test_capacity_*`, `test_engine_off_everywhere`, `test_engine_pages_*`, `test_engines_browser`, `test_engines_per_app`, `test_engine_help_pages`, `test_engine_figures`, `test_admin_engines_budget`, `test_arrow_bench_var_browser`, `test_role_home_browser`, `test_home_*`, `test_release_from_other_folder` (the release must carry the JARs), `test_settings_catalogue`, `test_no_relative_paths`; answers `tests/answers/bootstrap-{all,finance,genomics,risk}.json` (engine ids and routes); `elements/risk-screens.test.mjs` |
| Visual snapshots | 40 PNG | **stay**; pixel-equal, a changed snapshot is a changed number or message | `tests/snapshots/{var-bank,var-desk,var-basket,exposure,frtb,simm,home,home-risk,home-market,home-markets,home-trading}/` |
| Tools tests | | **stay, rewired**; `test_pvgen.py` moves | `tools/test_{varcli,expcli,frtbcli,simmcli,enginescli,dataloadcli,contracts,domain_strings,module_layout,exports_offer_jsonl,generators_write_jsonl}.py`, `tools/packgen/var/test_stress_data.py`, `tools/lake/test_maintain_{rows,admin}.py`, `tools/pvgen/test_pvgen.py` |
| Drill | | **edited before the drill, not during** | `tools/drillkit/affected-map.txt` lines 18 to 23 (paths `banking-compute`, `banking-service`), `test_affected.py`, `test_drill_lanes.py`, `container_smoke.json`/`.py` (the VaR golden through the API), `dropin.json`/`dropin.py` (add the Risk JARs and the goldens, design 0.11), `tools/drill-budgets.txt`, `drill-known.txt`, `drill-java25.txt`; `GATE_CONSOLE` in `tools/drill.sh` keeps its files (they are the doc gates of 6.5) |
| Recorder | | drishti-risk; must-change 3 | `record.py` names `modules/banking/java/banking-service`, `banking-compute`, `testing/drishti-testkit` at `3813b8735`: it runs against that commit, never against the cut tree |

### 6.5 Document impact (each updated in the commit that changes what it describes)

| Documents | Change |
|---|---|
| `docs/guides/VAR_GUIDE.md`, `EXPOSURE_GUIDE.md`, `FRTB_SA_GUIDE.md`, `SIMM_GUIDE.md`, `REGULATORY_VAR_REPORTING.md` | method sections move to drishti-risk; Drishti keeps screens, commands, configuration and a link (design 5.4, D7) |
| `docs/admin/CONFIGURATION.md` (generated), `tools/settings_schemas.yaml`, `tools/settings_catalogue.py`, `kernel/.../application.yaml` | the keys of `var.yaml`, `exposure.yaml`, `frtb-sa.yaml`, `simm.yaml`, `sensitivities.yaml`, `mr-cube.yaml`, `openblas.yaml` become `market-risk.yaml` and `ccr.yaml` options, old keys listed as aliases; regenerate |
| `docs/guides/CLI_REFERENCE.md`, `API_REFERENCE.md`, `tools/api_docs.yaml` | the `var`, `exp`, `frtb`, `simm` commands; routes now aliases; `/api/v1/engines/{id}/...` |
| `docs/guides/ADMIN_GUIDE.md`, `OPERATIONS_GUIDE.md`, `CLUSTER_GUIDE.md`, `BUILD_GUIDE.md`, `DEVELOPER_GUIDE.md`, `IDE_GUIDE.md`, `DATA_GUIDE.md`, `DATA_LAKE_GUIDE.md`, `FLIGHT_GUIDE.md`, `PVF_LOADING.md`, `USER_GUIDE.md`, `bi/{PIVOT,PIVOT_SCALE_AND_CUBES,GROUPINGS_AND_ANALYTICS}.md`, `docs/DEPLOYMENT_AND_QA_GUIDE.md` (and its PDF) | engine files, `lib/engines`, the licence line, the install order with drishti-risk, module paths |
| the architecture documents (ARCHITECTURE, DATA_ARCHITECTURE, KERNEL_AND_MODULES, LENSES, LIFECYCLE, TEST_HARNESS and the plans that name the engines), `kernel-and-modules/examples/banking/{module.yaml,console/plugin.yaml,project-mapping.txt}`, `capital-parameters/simm.yaml`, `HOW_IT_FITS.md`, `compute/drishti-compute/README.md`, `modules/banking/docs/README.md`, root `README.md` (counts from the file system) | class and module names, line counts, what the banking module holds |
| `docs/admin/BENCHMARK.md` | 2.4, 2.5 commands (`VarTierBenchmark`, `VarScaleBenchmark`, `ExposureTierBenchmark` leave Drishti); the speed step's figures; `tools/bench/results/*.jsonl` kept as the reference |
| Help | `drishti-console/plugins/banking/help.yaml`, `modules/banking/docs/help.yaml`, the help.yaml of the market-risk, counterparty-risk and trading packs, `modules/market-data/packs/market-data/config/help.yaml`, `drishti-console/config/help.yaml`, `config/docs-features.yaml`, `tools/docs/guide_data.yaml`, `docs/knowledge/{risk,data}.md`, `glossary.yaml` |
| Packs and contracts | `modules/banking/packs/{market-risk,counterparty-risk}/guides/*.md`, `README.md`, `contracts/{scenario-pv,pnl-vector,fx-scenario,var-limit,var-result,exposure-path,exposure-result}.contract.yaml`; `modules/market-data/.../{correlation-result,equity-beta}.contract.yaml`, `guides/market-data.md`; `docs/contracts/**` (generated: `tools/contracts.py docs`), `tools/packgen/banking/{make_docs.py,kept/market-risk.yaml,readme/*.intro.md}` |
| Tutorials and screenshots | `docs/tutorials/16-*.md`, `17-*.md` and the CLI lines of every VaR and exposure tutorial; `tools/docs/shots/{_risk,t25,t29}.py`; screenshots retaken only where a message or menu changed |
| Other READMEs | `plugins/drishti-plugin-kafka/README.md`, `plugins/drishti-plugin-iceberg/README.md` (name banking classes); drishti-risk `README.md` (its status line still says K1, "the code moves at K3") and `CHANGELOG.md` |
| Gates that fail until the above is done | `drishti-console/tests/test_docs_{api_endpoints,error_codes,gates,help_map,pdfs,placeholders,plugins,ready_example,rot,settings}.py`, `test_help.py`, `test_help_links.py`, `test_help_configuration.py`, `test_help_developer.py`, `test_tools_guide.py`, `test_engine_help_pages.py`, `test_settings_catalogue.py`; `tools/settings_catalogue.py --check`, `tools/contracts.py check` and `docs --check`, `tools/testdata/sample_docs.py --check`, `tools/api_docs.py check-python` and `check-java`, every `make_*.py --check`, `tools/test_readmes.py`, `tools/docs/help_map.py`. Run `tools/drill.sh --gate` after steps 2, 5 and 6 |

### 6.6 Owner decisions (2026-11-27, through the lead)

1. **Reporting currency is a setting**: `drishti.module.banking.var.reporting-currency` (`DRISHTI_VAR_REPORTING_CURRENCY`; `module.yaml`
   settings, so Admin -> Modules -> banking -> Settings lists it), **USD as shipped**; an installation chooses its own (the owner's: CAD).
   A request naming no `ccy` is answered in it on both paths; stressed VaR stays USD (the stress set's own currency). Built in lane G;
   goldens and acceptance figures unchanged (USD).
2. **Convert first, then compute** (limit use divides the converted-book VaR; a system basket's limit is converted; sums are the
   converted book). **Built on both paths (owner OK 2026-11-27, lane G part 3)**: one rule, in `VarService.scoped` (the per-currency
   totals' `inCurrencyVector` is gone: every currency sums the date's book converted to it, trade by trade) and in the engine's
   `MarketScopes.summed` (`scope-var` and every row-set output read the converted book; `basket-var` converts the limit as `scope-limits`
   does). Re-recorded, exactly the cases it moves, USD untouched (167,372,692 / 210,131,972 and every USD case as recorded):
   - `PackagedVarGoldenTest` (the server on packages, mapped tier): 9 EUR answers, 250 window, two whole units each (golden before ->
     server after): 2026-09-28 `NODE:LEGAL~LE-NY` ES 187,143,625 -> 187,143,627; `NODE:LEGAL~LE-NY/DESK-CREDIT` ES 258,116,069 ->
     258,116,071; `NODE:LL~LL-0005` VaR 166,163,956 -> 166,163,954; `NODE:MGMT~DESK-CREDIT` ES 258,116,069 -> 258,116,071;
     `PORT:PF-CREDIT-DESK` ES 258,116,069 -> 258,116,071; `PORT:PF-LE-NY` ES 187,143,625 -> 187,143,627; 2026-09-29 `NODE:LL~LL-0005` VaR
     165,676,375 -> 165,676,373; 2026-09-30 `NODE:LEGAL~LE-NY` ES 185,400,215 -> 185,400,217; `PORT:PF-LE-NY` ES 185,400,215 ->
     185,400,217 (`RE_RECORDED` in the test). The golden file itself is unchanged: it is the flat test's converted book on heap rows
     (`VarGoldenMatrixTest` green), and the engine's figures are its bits; the server's mapped tier converts its float32 rows (MC-12).
   - The engine's `basket-var` (`tck/recorded/market-risk`, `MarketRecordedIT` green): the six EUR cases (3 dates x 509/250), LIMIT and
     LIMIT_USE of the 28 baskets only (336 cells), e.g. 2026-09-28/509 `PF-ASHA-UNWIND` limit 1,800,000.00 (USD, 0x413b774000000000) ->
     1,552,969.19 (EUR, 0x4137b24930d95e42), use 0.7721 -> 0.8950; every other recorded bit unchanged.
   Side by side on the heap tier: every route in EUR and CAD equal to the bit (headline, tree, contributions, path, horizons, scope
   kinds, what-ifs). On the default mapped tier the in-tree book is converted from float32 rows: EUR/CAD answers a few units apart,
   counted and printed (the float32 view, MC-12, closes it). The intraday tests' incremental-against-full-reload comparison in another
   currency (`VarIntradayApiTest`, its Risk-edition subclass, `VarPvStreamTest`) now reads the converted book: its rows are a few
   millionths apart (5.7e-6 at most: float32 USD rows on a full reload, the batch's own figures incrementally) and the fixture's CAD
   figures sit on half units, so the API's whole units may tie either way: those tests compare the exact vectors to the cent (every
   node) and the API's whole units within one unit in EUR/CAD; USD stays exact.
3. **Backtest zone under 250 days = the scaled Basel light** (`Backtest.light`: green to round(4 x days/250), amber to round(9 x
   days/250)), as the engine: built in-tree (`RegulatoryProperties.Backtesting.zone(exceptions, days)`, `VarBacktesting`); 250 days or
   more, the configured counts. `VarRegulatoryApiTest` (25 days) now expects the scaled light.
4. **Parametric VaR within relative 1e-12** (owner OK 2026-11-27): the engine's `node-pvar` is accepted within relative 1e-12 of in-tree
   (the normal quantile's exp and log may differ in the last bit); historical VaR and ES stay bit-exact. Built behind `var.route`: the
   headline's parametric block of a node (offered lens) and the tree's parametric cells from `node-pvar` (which now also carries
   `SKEW`, `EXCESS_KURTOSIS`, `Z_ES`, `ES_FACTOR`, `K`, `SCENARIO_SET`, `LAST_DAY`), shaped by Drishti's `VarParametric` as in-tree.
   `RiskSideBySideTest.theParametricFiguresOfEveryGoldenNodeAreWithinRelative1e12OnBothPaths` (heap tier): 2,358 blocks (1,572 headline
   blocks + 786 tree cells), **32,226 numbers, all 32,226 within relative 1e-12**, every word equal. The P&L path's block and normal
   curve of a node followed (lane G part 4: 246 blocks; 107,256 numbers in all, all within). Still in-tree only: contributions'
   parametric columns and the matrix, a parametric block through a horizon or the stress set, a block of a scope
   that is not a node.

### Status, lane G (2026-11-27 09:3x, branch `r2/var-routes`; drishti-risk `r2/var-routes`)

- **Green at this stop** (default mapped tier unless said): `PackagedVarGoldenTest` (USD 167,372,692 / 210,131,972, every USD case as
  recorded); VaR headline 1,572 answers, **3,918 of 3,918** golden raw bits; `MarketRecordedIT`; `RiskSideBySideTest` (now 11 tests: the
  three dates captured officially at start) and `RiskSideBySideCadTest`; the history and parametric tests also on the heap tier; the
  engine host's tests (`HostPartsTest`: an input declared `per: []` holds every date its kind holds).
- **Added in part 4 (behind `var.route` / `path=engine`, defaults unchanged; Appendix A rows)**: (1) `GET /scope/history` of a node with
  official records, from the new `official-history` and `official-backtest` over `var-history`: 260 official answers, 649 rows, every
  field equal; the host reads an input the engine declares `per: []` (not per date) on every date its kind holds (`LakeInputs`,
  `KindData.dates`; documented in ENGINES_IN_DRISHTI.md), and `PackagedServer` points the published records' reader at the publisher's
  root (`LAKE_VAR_OFFICIAL_ROOT`). (2) `GET /statistics` from the new `official-statistics`: 2,144 answers, 15,946 numbers, every field
  and bit equal. (4, part) the P&L path's parametric block and normal curve of a node: 246 blocks within relative 1e-12.
- **Open**: (a) the default mapped tier: EUR/CAD a few units apart between the paths (MC-12's float32 view closes it); (b) `MarketKitIT`
  still fails on drishti-risk for reasons outside lane G (kit checks; its coverage gate now also names the new outputs, with the many it
  named before); (c) an engine session opened before an official capture keeps the records it read until it closes (`market-risk` has
  no `data-version` trigger; the published reader refreshes every 10 s): the engine path then refuses in words (records differ from the
  store); the side by side waits 11 s after its captures; (d) **`GET /backtesting` is blocked on the published contract**: the in-tree
  answer counts exceptions at every configured confidence (0.99 and 0.975) from each record's `varAt`, which `risk-results/var-official`
  does not publish (one row per node at the official confidence); the engine's `backtest`/`backtest-days` read the published records,
  so the 97.5% counts would be missing. It needs a decision: publish the other confidences (a row per confidence, or a `VAR_AT` column:
  the contract `var-official` moves) or answer them in-tree.
- **Remaining VaR routes** (in-tree only, refused on the engine path in words), next in order: `GET /backtesting` (see (d)); the
  compare/captures (Drishti's stored captures; their figures come from the headline, contributions and path, and a capture stores
  in-tree fields the engine path does not give yet: `provenance`, `dataVersion`, `label`, `kind`); contributions' parametric columns
  and the matrix; a parametric block through a horizon or the stress set or of a scope that is not a node; leaf-node and expression
  paths and contributions; other confidences; matured items; `within` trees; the Homes' engine tables (lane E).
