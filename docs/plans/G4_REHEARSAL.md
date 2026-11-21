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
# G4 rehearsal: Drishti on the current product builds (2026-11-21)

Branch `wip/g4-rehearsal` (worktree `drishti-g4r`), cut from `origin/main` `9ce7c3170`. **Never merged without the G4 gate.** Purpose: move Drishti onto
the product builds early and list every breakage, so the products are fixed during G3 and the adapters are sized before G4. Nothing here was
installed into `~/.m2`: the product jars were built in fresh clones into a scratch Maven repository (`-Dmaven.repo.local`), both deleted afterwards.
No drill, no browser suite, no VaR/ES golden run (see section 6: the build does not reach the banking module).

Product builds used (JDK 21, `-DskipTests install`):

| Product | Ref | Version in the scratch repository |
|---|---|---|
| drishti-rachana | `origin/g2/integration` `f5745a4`, rebuilt at `2391142` for step 3 | `0.11.0-SNAPSHOT` (`rachana-api`, `-core`, `-tools`, `-data`, ...) |
| drishti-lake | `origin/main` `da3116b`, rebuilt at `be323e2` for step 3 | `0.9.0-SNAPSHOT` (`lake-api`, `-io`, `-config`, `-delta`, `-iceberg`, `-files`, `-memory`, `-load`, `-tck`) |
| drishti-elements | `origin/g2/integration` `d1db672`, `python3 tools/elements/cli.py dist` | `3.1.0` |

## 1. Steps reached

| Step | Commit | Result |
|---|---|---|
| 1. Rachana | `059ceb6b3` | Pin property `drishti.rachana.version`, four jars in `dependencyManagement` and in `compute/drishti-rachana`. A10 sources deleted (149 files: `el`, `format`, `model`, `parse`, `design`, 9 top-level classes, `signatures.yaml`, moved tests). Scripted import rewrite (`com.ash.drishti.rachana` to `com.ash.rachana`): 202 files. **Every module, main and test, compiles** (`test-compile` through `drishti-modules-it`, the packaging module excepted) with the adapters of section 2. The binder (A13) and the aggregate kernel (A9) were **not** deleted (Drishti's copies still compile after two one-line patches); their deletion is counted in section 2. `drishti-rachana` unit tests: 68 run, 36 fail or error (R11). |
| 2. Elements | `ec2f9cae7` | `3.1.0` tree vendored in place of `2.4.0`; `drishti.elements.version`, the `render/<version>` folder, 55 files naming the old version, Drishti's own libraries rebuilt with the vendored `build.py`, 13 `module.yaml` ranges. Version and vendoring guards pass (9 of 10; the tenth needs the server jar). Node tests: 52 of 55 pass (E3). |
| 3. Lake | `9de17833d` | `drishti.lake.version`, nine jars in `dependencyManagement`, 275 moved Java files deleted (the allow-list of `docs/MOVE_ALLOWLIST.md`, sections 1 and 3), imports rewritten, Delta `hadoop` engine retired (`EngineKind`, `HadoopLake`, 7 tests). **Does not build, by design**: Drishti `kernel`, `common`, `lakeio`, `engine`, `packs` compile after stubs; `plugin-delta` (93 errors) and `server` (42 first-pass errors in 20 files) do not; `banking`, `app`, `it` are not reached. |

## 2. Breakages: Rachana

Cause "Drishti" = the adapter or a call site in Drishti; "Rachana" = the product should change. Sizes: S under half a day, M 1 to 2 days, L 3 or more.

| # | Symptom | Cause | Who fixes | Size |
|---|---|---|---|---|
| R1 | `SutraBlocks.kit/load/clashes/find/library/fromKit/warnings/contribute/withdraw` do not exist | The block library moved from statics to an instance, `BlockLibrary` (A6). 9 files call it. Rehearsal stub: `DrishtiBlocks.LIBRARY` | Drishti: one `BlockLibrary` in the profile holder, handed to the parser and the registry | M (with R11) |
| R2 | `SutraMatcher`: `DataNode` cannot be converted to `Node`; `Map<String,DataNode>` is not `Map<String,Node>` (`ScreenWire`) | 8.5: `DataNode` must implement `com.ash.rachana.api.Node`. Done in the rehearsal (`kind()` over `type()`); `drishti-api` now depends on `rachana-api` | Drishti | S, done |
| R3 | `ElException(ErrorCode, String)` and `SutraException.errorCode().httpStatus()` fail | Core's code type is `RachanaCode` (no HTTP status). Patched at 2 sites; the real fix is the `RCH-` to `DRS-` map at the API edge with the status | Drishti | S |
| R4 | `BusinessCalendar` is not a `Calendars.Calendar` (`SqlLowering`, `ContextFunctions`) | Core's calendar hook. Adapter `HostCalendars` (4 methods) | Drishti | S, done |
| R5 | `BlockUses.of(Sutra)` gone; `Ops.toJson` returns `List<Map>` not Jackson nodes; `RachanaSchema.build(formats, kinds)` gone; `PositionalYamlReader.read` throws no `IOException` | Core API reshaping (additions and removals recorded in `api-surface.txt`) | Drishti, 6 call sites | S |
| R6 | `Functions.register(...)` removed (`RachanaFunctions`, module functions never reach Rachana; stubbed to a no-op) | 8.3c: a `Profile.named("drishti").functions(...)` and a holder that rebuilds the instance when a module starts or stops | Drishti | M |
| R7 | **`drishti-rachana` tests: 36 of 68 fail** (`PackSutrasTest` 19 of 19, `SutraRegistryTest` 7 of 7, `ReferenceSutrasTest`, `MatchTraceTest`, `UnderConstructionTest`, `DocumentedSutrasTest`): `unknown key 'match'`, `unknown panel kind 'provenance'` | `new SutraParser()` is Core alone; Drishti's grammar (`match`, `links`, `provenance`, entity kinds, UI-kit half of `SutraBlocks`) must come from `Rachana.with(options, Profile.core(), drishti)` and `rachana.parser()` (8.3d). Every `new SutraParser()`, `OpApplier`, `SutraLayoutEditor`, `SutraPivotEditor` in Drishti changes | Drishti | L |
| R8 | Binder: Drishti's copy of `engine/{bind,view,control,wire}` does not know Core's 7 new panel kinds (`BULLET CHART FORM GRID LIVE TREE TREEMAP`): switch not exhaustive; `EvalContext.Scope` takes `Node` maps | A13 not applied. The real step is 8.3e (hooks: links, provenance, masker, table provider, pivot engine; about 73 files of imports) | Drishti | L |
| R9 | Aggregate kernel: deleting A9 files leaves 65 errors in 5 files (`Functions` 35, `EngineFunctions` 15, `OncePerParent` 9, `Sketches` 4, `SqlLowering` 2) | 8.3h: thin `AggFunction` shells over `com.ash.rachana.agg` with state-bytes compatibility test | Drishti | M to L |
| R10 | Tests deleted whole that 8.6 says stay in part: `OpApplierTest`, `BlockOpsTest` (the `design` folder), the methods `referenceSutrasParse`, `aUseCarriesItsPanelsWiring`, `SutraLayoutEditorTest` (3) | The a10 regex takes the whole class; 8.6 keeps some methods. `SutraFragmentsTest` needed `TICKET` copied from the moved `SutraBlocksTest` | Drishti (re-extract) | S |
| R11 | Two Elements pins: the Rachana jar bundles `formats.yaml` and `world-regions.yaml` of Elements **2.4.0** (its `drishti.elements.version`), Drishti vendors **3.1.0**; byte-identical today. Drishti's `drishti-rachana` pom no longer adds the vendored `data/` folder as a resource; the "Pin is one fact" guard and `ELEMENTS_IN_DRISHTI.md` section 5 name that resource | Pin policy | Rachana (keep its table pin at or below Drishti's) and Drishti (guard, doc) | S |
| R12 | `rachana-guards` runs its Node tests under `-DskipTests`, so `install -DskipTests` fails; needed `-pl '!rachana-guards'` | `exec-maven-plugin` execution ignores `skipTests` | Rachana | S |
| R13 | The pin is `0.11.0-SNAPSHOT` of an untagged branch | Pin rule: tagged releases only | Rachana (tag) | process |

Not tried: 8.3b (`RachanaOptions` from `RachanaProperties`), 8.3f (regex `maxSteps` setting), 8.3g (`ViewJson` as the API writer), the goldens, the conformance run with the Drishti profile loaded, the guards (A12).

## 3. Breakages: Elements

| # | Symptom | Cause | Who fixes | Size |
|---|---|---|---|---|
| E1 | `drishti_live-hub.js` (`new Live.LiveClient`), Drishti's `drishti-renderer/src/drishti_net.js` (`LiveClient`), `drishti_channel.js`, `drishti_server-watch.js` (comments and the worker) | 3.0.0 removed `LiveClient`, `LIVE_STATES`, `live()`, `parseSse`, `Backoff`, `Heartbeat`, `TransactionSink`, `pipeToGrid`, `FeedMonitor`, `feedBadge` for `connect(url, { protocol: 'raw' })` of drishti-events (`docs/MIGRATING_TO_3.md`). Not migrated | Drishti | M to L (live hub and the page tests) |
| E2 | The pack now holds `drishti-events` and `drishti-views` (the ViewModel painter, forms, live tables); Drishti's own `drishti-renderer` (frozen `render/` distribution, GenUI, `drishti_net`) does the same job | Two ways to draw a ViewModel: against the "one way per job" rule. Needs a decision, not a patch | Owner decision, then Drishti | L |
| E3 | Node tests: `chart.test.mjs` (panel-chart kinds list now ends with `treemap`), `renderer.test.mjs` (SyntaxError: no `LiveClient` export), `states-palette.test.mjs` (`FeedMonitor is not a constructor`) | Stale expectations and E1 | Drishti | S |
| E4 | 13 `module.yaml` carry `elements: '>=2.4 <3'` and would refuse the pack | Range. Rewritten to `'>=3.1 <4'` in the rehearsal | Drishti | S, done |
| E5 | The renderer folder `static/render/2.4.0` and 55 files (the embed guide, tests, `render_dist.py`) carry the version | Pin move checklist; scripted | Drishti | S, done |
| E6 | Browser tests, gallery, budget and embed tests | Not run | | not measured |

Product fixes needed: none found for Elements itself; the pack built cleanly and the vendoring and manifest guards pass. A note for the Elements docs: `MIGRATING_TO_3.md` lists `LiveClient` users but not the "frozen renderer parity" suites a host may carry.

## 4. Breakages: Lake

| # | Symptom | Cause | Who fixes | Size |
|---|---|---|---|---|
| L1 | `DeltaSourcePlugin` (1,400 lines, 79 errors), `MappedIdMap`, `DeltaLakeFiles`: `DeltaTable`, `TableProblems`, `VectorBatches` "is not public"; `LakeStore.of(String)` is now `of(LakeRuntime, ...)`; `NativeEngine.purgeFooterCache(...)`; the same for the Iceberg adapters (`IcebergSourcePlugin`, `IcebergDiskCaches`, `IcebergExternalPlugin`; not compiled yet) | The adapters were written on the table layer's package-private classes. Design LK-4 says "the three `SourcePlugin`s on the public API": they must be rewritten on `TableStore`/`Stores.of(runtime, format)`/`ScanRequest`, and the product must give them what they read today (promoted-column filters, vectors, deletion-vector and column-mapping reads, doc cache staleness) | Lake: confirm the public read API covers the adapters' calls (a written list from this rehearsal: every `DeltaTable.*` and `TableProblems.*` call in `DeltaSourcePlugin`); Drishti: the rewrite | L (about 5 days for the two plugins) |
| L2 | `Lakes.current()`, `Lakes.dir(...)`, `ModuleConnectors.forKinds(kinds)`, `LakeFiles.packFiles/packTables/adoptDemo`, `LakeRelocate.relocate`, `LakeCheck`, `MemoryLake.store(...)`, `LakeStore.of(...)`: first argument `LakeRuntime`; `Lakes.useProperties` removed; `StorageProviders` removed | "One `LakeRuntime` everywhere" (SPLIT_STATUS L5). Rehearsal: a holder `DrishtiLake.runtime()` in `drishti-common` (built from the environment in `PackEnvironmentPostProcessor`, names supplier empty: named lakes not resolved) | Drishti: build the runtime once in the server wiring with the host `LakeCatalog`, `LakeOptions`, `mem://` provider, settings; pass it, delete the holder | M for the wiring, M for about 20 server files (first pass: `HealthController`, `ProcessResources`, `DuckDbEngine`, `RunService`, `CacheBoard`, `CachesConfiguration`, `ShareService`, `DataViewController`, `DataLoadConfiguration`, `AskService`, `FlightConfiguration`, `LakeRelocateOnStart`, `LakeStartCheck`, `MemoryLakeConfiguration`, `MemoryLakeController`, `DigestService`, `NetRouting`, `KindSources`, `ReportService`), `HistoryBench` |
| L3 | `MemoryLakeConfiguration`: `SourceRegistry` cannot be converted to `com.ash.lake.spi.RowSource` | The `RowSource` seam for `mem://` replenishment | Drishti: `RowSource` over `SourceRegistry` | S |
| L4 | `TempFiles` is now an instance (`TempFiles.of(runtime)`), `JvmTempDir` is gone; Drishti has 12 call sites (`DeltaSink`, `VarPvStream`, `VarTier`, `VarShardRows`, `ModuleCli`, ...) | Moved into `lake-io` with the runtime. Rehearsal kept Drishti's own `TempFiles`/`JvmTempDir` and their tests: **a second copy** | Drishti: the static facade delegates to the runtime's instance (or call sites take the runtime); then delete the copies | S to M |
| L5 | `drishti-common` keeps its own `RowFeed` beside `com.ash.lake.common.RowFeed`; `FileDates`, `RowRetention` moved | `drishti-common` now depends on `lake-io` (layering inversion: the kernel's common module depends on a product) | Drishti: decide where `RowFeed` lives; ideally the kernel depends on the lake, not `common` | S |
| L6 | `TestRoots` moved to `lake-tck`: 172 Drishti test files import `com.ash.lake.tck.TestRoots`; `drishti-testroots` (a deliberately parent-less module) is now an empty shim with a literal lake version in its pom | The parent's `testroots` profile gives every module the old module as a test dependency | Drishti: the profile gives `lake-tck` (test scope) instead; delete the shim | S |
| L7 | No `lake-bom`; the lake parent still pins `host-boot-bom` 3.5.16 and its own delta-kernel, Iceberg, Parquet versions (until L12). Drishti keeps its own `delta-kernel.version`, `iceberg.version`, `parquet`, `awssdk`, `hadoop` properties in the four lake poms | D1 (the BOM) is not available; versions in the two builds can drift and the class path now holds two sets | Lake: publish the BOM at L12. Drishti: delete its properties then | S, blocked on the product |
| L8 | The Delta `hadoop` engine is retired (LK-9): `EngineKind`, `HadoopLake` and 7 tests deleted. Not yet done: the `engine` setting and its words in `CONFIGURATION.md`, the guides, the Hadoop jars still declared in the lake and plugin poms | LK-9 | Drishti | S |
| L9 | Environment: the lake's names are `LAKE_*` (`LAKE_DATA_DIR`, `LAKE_LAKES_DIR`, `LAKE_CLASSPATH`, `LAKE_HEAP`, `LAKE_NAME`, `LAKE_GUIDE`, `LAKE_RISK_ROOT`, `LAKE_BUSINESS_ZONE`, `LAKE_ALLOW_INSECURE_TLS`, `LAKE_ARCHIVE_MAX_MB`); Drishti uses `DRISHTI_DATA_DIR` (149 files), `DRISHTI_DELTA_ROOT` (61), `DRISHTI_LAKES_DIR` (24), `DRISHTI_LAKE_CLASSPATH` (18), `DRISHTI_ICEBERG_ROOT` (21) and passes them to the loader child process | Rename (G4 item). Not started: the host passes its own names through the runtime's `.settings(lookup, names)` and `childProcessSettings` | Drishti (a map in the catalog) | M |
| L10 | The scripted rename used the L3 allow-list. Three classes it got wrong, found at compile: `MemoryBinding` stays in Drishti (fixed by hand), `LakeCheck` is in `com.ash.lake.load` not `config`, `LakeStartCheck` imports the wrong one | The allow-list's targets are the L3 places; later steps moved classes again | Drishti: regenerate the map from the product's class list (not the allow-list) before G4; the script is re-runnable | S |
| L11 | Not touched: Python (`tools/drishti.py lake ...`, about 90 callers, wheel), `ArchitectureRulesTest` (it names `StorageProviders` and `com.ash.drishti.plugin.delta`), the 322 fixture files left in Drishti's module test resources, 55 `server`/`banking` test files, `StartOnFewCoresTest`, `DatedSourceContract` over all connectors | Not reached | Drishti | in section 7 |

Product fixes needed from the Lake: L1 (public read API for the adapters, or the written confirmation that `TableStore` covers every call), L7 (BOM).

## 5. Drishti adapter work left (what the product cannot do for us)

1. **The profile and registries** (R1, R6, R7): `Profile.named("drishti")` with functions, formats, calendars, entities and the UI-kit half of blocks; one `Rachana` instance in a holder that rebuilds and swaps when a module starts or stops; every parser, editor and schema call through it.
2. **The binder hooks** (R8): links, provenance, masker, table provider, pivot engine, `BindContext.host`; delete `engine/{bind,view,control,wire}`.
3. **The aggregate shells** (R9) and the `RCH-` to `DRS-` mapping at the API edge (R3).
4. **The lake catalog and runtime** (L2, L3, L9): `LakeCatalog` (shipped lakes, pack files, connector templates, named writers, extensions, child-process settings), `LakeOptions`, the `mem://` provider, settings and names; one runtime passed everywhere.
5. **The source plugins on `TableStore`** (L1): Delta, Iceberg, Iceberg external, plus `MemoryLake`'s `RowSource`.
6. **Elements** (E1, E2): the live hub on drishti-events; the renderer decision.
7. **Python and docs**: the lake wheel, `tools/drishti.py lake`, `CONFIGURATION.md`, `DATA_LAKE_GUIDE`, `ELEMENTS_IN_DRISHTI.md`, the guards of both split plans.

## 6. What was not measured

The banking module (67 files reference the lake; 29 Drishti modules compile on the Rachana half), the VaR and ES golden tests (the banking module is behind the lake breakage; step 1 alone compiles but was not run against the goldens), conformance with the Drishti profile, all browser and page tests, the TCK inside Drishti, delta-rs reads of a rebuilt demo lake, performance.

## 7. Estimated G4 effort (builder days, one sitting, other builders off the modules)

| Part | Plan (SPLIT_PLAN, LAKE_DESIGN D1 to D9) | Estimate from this rehearsal |
|---|---|---|
| Rachana: pin, deletion, imports (done here) | M (2 to 3) | 1 (re-run the scripts) |
| Rachana: profile, registries, parser (R1, R6, R7) | inside 8.3c/d | 4 |
| Rachana: binder hooks (R8) and aggregate shells (R9) | inside 8.3e/h | 5 |
| Rachana: guards, goldens, conformance with the profile | S, 8.8 | 2 |
| Elements: pin, own libraries (done here) | | 0.5 |
| Elements: live hub on drishti-events, renderer decision (E1, E2) | not planned | 3 to 5 (more if the renderer is replaced by drishti-views) |
| Lake: runtime, catalog, server call sites (L2, L3, L4, L5, L6, L9) | D3 + D4 part, 5.5 | 7 |
| Lake: source plugins on the public API (L1) | in D4 | 5 |
| Lake: Python (D5), guards (D6), docs (D7) | 4.5 | 5 |
| Proof: VaR and ES goldens, TCK, delta-rs (D8), the shared drill (D9) | 3 | 4 |
| **Total** | **about 14.5 for the lake plus about 5 for the Rachana half** | **about 33 to 37** |

The two plans together understate the Drishti side by roughly a factor of 1.7, mostly in the source plugins (L1), the profile and binder work (R7, R8) and the Elements live hub (E1, E2), none of which the Lake design's table prices.

## 8. Order that minimises rework at G4

1. Product side during G3: L1 answered (public read API list), the Lake BOM, a Rachana tag (R13) and the table pin policy (R11).
2. Rachana half first (R1, R6, R7, R8, R9), because the lake adapters and the server call sites sit on top of the profile holder.
3. Elements pin and the live hub in parallel with the Rachana half (different files).
4. Lake adapters, then server call sites, then banking, then tests; the rewrite scripts of this rehearsal were scratch files and are not committed; they were small (a regex rename per product, a same-package import pass) and should be committed under `tools/split/` for G4, with the lake map regenerated from the product's classes (L10).
5. Goldens after the Rachana half and again after the lake half; one drill.
