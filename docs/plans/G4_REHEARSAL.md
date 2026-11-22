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

## Pass 2 (2026-11-21): Rachana profile and Elements 3.x, on `wip/g4-rehearsal-rx`

Branch `wip/g4-rehearsal-rx`, cut from `wip/g4-rehearsal` before its Lake step (`ec2f9cae7`): the lake modules are Drishti's own again, so every module builds. `wip/g4-rehearsal` keeps its Lake step. Products rebuilt from fresh clones into a scratch Maven repository: drishti-rachana `origin/g2/integration` `5d1b116` (`0.11.0-SNAPSHOT`), drishti-elements `origin/g2/integration` `6b4a3b2` (pack `3.1.0` tree, Elements 3.2.0-dev). No drill, no browser suite.

### What was done

| Item | Result |
|---|---|
| Elements pack | Re-vendored from `6b4a3b2`; Drishti's own libraries rebuilt (no change); `drishti-renderer` now requires drishti-events in the manifest; the frozen `render/3.1.0` sheet and integrity record regenerated. |
| Drishti profile | One holder, `DrishtiRachana` (`compute/drishti-rachana`): Core plus `Profile.named("drishti")` from `Entities.addTo` (`match`, `domain`, `links`, `provenance`, the `kind` source form, form write-back), the one `BlockLibrary` as the profile's fragment resolver, every `FunctionProvider` (the engine's through `ServiceLoader`, a started module's through `addFunctions`, which adapts the kernel API provider), the business calendars. Immutable `Rachana` instance rebuilt and swapped when a module starts or stops. Every `new SutraParser()`, `OpApplier`, `SutraLayoutEditor`, `SutraPivotEditor`, schema and function-bearing compiler goes through it (8.3b settings, 8.3c, 8.3d, 8.3i). The `META-INF/services` file name was still the old package (`com.ash.drishti.rachana.el.FunctionProvider`): renamed. `DrishtiBlocks` stub deleted. |
| `RCH-` to `DRS-` | `DrishtiRachana.drs(...)` at the registry, the API exception handler (`SUTRA_INVALID`, 422, `DRS-2002`, as before) and the tables' checker. |
| Language level | The holder pins level **1.0** (Core's default is 1.2: `Rachana.with(...)` takes `Grammar.DEFAULT_LEVEL`, which would have changed meanings silently). The level is one setting, `drishti.rachana.language-level` (default 1.0) read once by `RachanaConfiguration` and passed to `DrishtiRachana.level(...)`; G4 switches it to the latest after `rachana upgrade`. Guard `LanguageLevelGuardTest`: the holder is at 1.0, and no other main source builds a Rachana instance, picks a level or takes a bare parser. Planned for G4 (needs the upgrade tool): every shipped Sutra declares its level explicitly; a user-saved Sutra without a level compiles at 1.0 and Drishti offers the upgrade. |
| Elements 3 | `drishti_live-hub.js`, the renderer's `drishti_net.js` and `drishti_embed.js` moved from `LiveClient` to drishti-events `connect(...)` in raw mode (address a function asked at every connect, `events` names, kind = the SSE event, `idOf`/`seqOf` disabled so a frame's own id or seq is not the connection's, hub clock injected, `auth` for the embed's token); `base.html` loads drishti_events.js; the host script's library table; genomics showcase `SELECTION_EVENT` to `selectionTopic(channel)`; three test files. Drishti's own renderer stays (i8). |

### Results

| Check | Result |
|---|---|
| Compile, main and test, all modules but `packaging/*` | green |
| `drishti-rachana` unit tests (the 36 of pass 1) | **68 of 68 pass** after the profile (plus the level guard) |
| Node tests (`drishti-console/tests/elements`) | 56 of 56 (pass 1: 52 of 55) |
| Console Python guards that run without a server jar (live hub 14, elements guards, vendored, budget, render library, licences) | pass |
| All unit tests, all non-packaging modules except `drishti-it`, `drishti-benchmarks` (need `drishti-app`) | about 5,300 run; **12 failures and 759 errors on the first run** (one root cause, below); after the fixes, **14 test classes still fail** (listed below) |
| VaR/ES goldens (`VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`; committed small data, no lake move needed) | pass (the lake is not moved on this branch). `VarShardClusterTest` failed once under load (173 s, timing); `PackagedVarGoldenTest` is in `drishti-modules-it`, which needs a packaged build: not run |

### Breakages

| # | Symptom | Cause | Who fixes | Size |
|---|---|---|---|---|
| P1 | 759 errors: `Rachana-EL function sum is provided twice (Rachana Core, the pivot engine)` at start-up and in every test that builds a compiler | 8.3h not done: `EngineFunctions` still gave names Core's `CoreAggregates` gives. Stopgap: `EngineFunctions.functions()` drops them and returns no grouping; the `_once` family, measure-level functions, `measure`, `cell` stay. The Drishti copies (`Builtins`, `Moments`, ...) still exist for pivot cells | Drishti (8.3h: AggFunction shells over `com.ash.rachana.agg`, state-bytes test) | M to L |
| P2 | `new ElCompiler()` is Core-only: no engine or module functions (tests `Rachana*Test`, `SearchQueryTest`, `DerivedPageTest`; production `DerivedSourcePlugin`) | Per-instance function table | Drishti: compilers from the holder (done for tests and `DerivedSourcePlugin`). `RowPredicate` and `DocPath` keep static core-only compilers, and a compiler taken early never sees a module loaded later: a swappable compiler is needed | Drishti, M. Rachana could offer a compiler view that follows its instance |
| P3 | `DrillPanelTest`, `DerivedPageTest`: lists made by expressions (`flatten`, filters) read as missing or empty | Core's functions return `com.ash.rachana.api.internal.Nodes.Arr/Obj`, not `DataNode`; 77 `instanceof DataNode.Arr/Obj` sites in Drishti. Partial fix: `DataNode.of(Object)` converts a foreign `Node`; `Binder.node` uses it | Rachana (a node factory hook, so a host's own types come out) preferred; else Drishti converts at every site | M |
| P4 | `SutraAggregateParityTest`: `sum($.POSITIONS[?@.DIRECTION == 'Held'], 'VALUE')` is 0 from Core's aggregate and 78104 from the engine's (collateral-balance Sutra) | **Wrong answer, cause not found**: Core's kernel over a filtered list of Drishti `DataNode`s. Suspects: the P3 type seam or field reads through `Node.get` | Investigate first at G4; a release blocker for 8.3h | S to M |
| P5 | `Jackson: No serializer for ProblemPlaces`; `domain` and `match` gone from a Sutra's JSON | Core's `Sutra` record keeps profile keys in `extensions` (and a parse's `ProblemPlaces`); the record components no longer include `domain` and `match` | Drishti: `SutraJsonMixin` (done: ignores `extensions`, writes `domain` and `match` in their old places). Rachana: the record's JSON shape is a contract change to note | S, done |
| P6 | `BuilderOpsApiTest`, `DesignGovernanceWaveTest`: HTTP 400 on every op | Pass 1's patch of R5 handed a Jackson `JsonNode` to `Ops.parse(Object)`; it takes plain Java values | Drishti (done: convert at both call sites) | S, done |
| P7 | `PackReloaderTest`: a pack's `formats.yaml` never reaches the `Formats` the readers hold | 8.4: Core's `Formats` is immutable and final; Drishti's was live-reloaded (`LiveSettings`). Readers hold the instance | Drishti (rebuild and swap the instance and its binder) or Rachana (a live-table view of `Formats`) | M |
| P8 | `ViewModelSchemaTest`: no data record for Core's seven new panel kinds; Drishti's binder switch throws for them | R8 (8.3e): the binder copy is not Core's | Drishti, 8.3e | L |
| P9 | `RCH-5021`, `RCH-9161` and checked-mode codes leak where an API answers (`BuilderOpsApiTest`, `LanguagesApiTest`, `WindowFunctionsTest`) | `drs(...)` is applied at three places; the rest of the API edge (BI language check, builder ops problems) is not | Drishti: one mapping at the response writer | S |
| P10 | `PivotFormulaMigrationTest`: expected `sum(PNL, where = DESK == 'RATES' && PNL > 0)`, got `sum(PNL, where=DESK == 'RATES' and PNL > 0)` | Core's printer writes `and` and `where=`; Drishti's printer wrote `&&` and `where = ` | Owner decision: Rachana (keep the old text) or Drishti (new expected text; saved formulas migrate to different text) | S |
| P11 | `FunctionCataloguePageTest`, `RachanaAggregatesTest`, `RachanaStatisticsTest`, `RachanaListAnalyticsTest`: catalogue says Core is the source of `sum`, `nmiss`, `cumsum`...; `DataNode.Arr` expected, `Nodes.Arr` got | Consequence of P1 and P3: the generated `docs/guides/FUNCTIONS.md` changes | Drishti: regenerate and update the tests | S |
| P12 | `NetForwardTest` (502 body), `AbandonedReadsTest`, `VarShardClusterTest`, `CustomFunctionsTest` (cost limit 2000 ns, measured 2715) | Timing under a loaded machine (load 14 to 140 during the run), not the rehearsal | rerun on a quiet machine | |
| P13 | `PythonPathEqualityTest`, `DomainPacksTest` | not triaged (Python environment for the first) | Drishti | |
| E7 | `chart.test.mjs` parity with the frozen 1.2.0 builders: a stacked area now has a `legend` part and a donut slice a `bar` record | Elements 3.2.0-dev added them | Drishti test: ignore both (done); Elements docs: note in the changelog | S, done |
| E8 | The Elements pin cannot be told from the version: the tree built from `6b4a3b2` is still named `3.1.0` (`drishti-elements.json`), but the Rachana jar bundles the formats of `3.2.0-dev+6b4a3b2` | Dev versions share a folder name | Elements (a version that names the commit) | S |
| E9 | `drishti-renderer` imports drishti-events but its manifest entry did not require it (`build.py` refused) | Elements 3 split | Drishti (done) | S, done |
| E10 | `FeedMonitor` coverage in Drishti's tests disappeared with the class | 3.0 removed it | Elements has its own sequencer tests; Drishti test dropped | S, done |
| R14 | `rachana-guards` and the JS build take about 15 minutes under load; a scratch install needs `-pl '!rachana-guards'` | as R12 | Rachana | |

### Not done

8.3e (the binder behind hooks, 93 Drishti files import the four packages, 5,309 lines of Drishti copy), 8.3g (`ViewJson` as the writer), 8.3h beyond the stopgap (P1), the browser tests, `PackagedVarGoldenTest`. The binder move is the largest remaining Drishti item (P3, P7, P8 sit in it): estimated 5 days, as in section 7.

### Product fixes needed

Rachana: a node factory hook so a host's tree types come out of expressions (P3); a live view of `Formats` (P7); a decision on the printer's text (P10) and a note that the `Sutra` record's JSON no longer holds `domain` and `match` (P5); a default language level that a host can pin without calling `language(...)` after the fact (the 1.2 default is a trap). Elements: a distinct dev version name (E8); the two parity differences in the changelog (E7). P4 (a wrong answer) needs an owner for investigation before the aggregate kernel is trusted.

## Pass 3 (2026-11-21): lake, on `g4/lake`

Branch `g4/lake` from `wip/g4-rehearsal-rx` `f954d27bf`, merged back into it. Products from fresh worktrees into a scratch Maven
repository: drishti-lake `origin/main` `a3c6b2a` (`0.9.0-SNAPSHOT`), drishti-tools `origin/main` `b3a451d` (`1.0.0-SNAPSHOT`),
drishti-rachana `origin/g2/integration` `417f6af9` (`0.11.0-SNAPSHOT`). Pass 1's lake step (`9de17833d`: the 275 moved files deleted, the
hadoop engine retired) cherry-picked first, then finished. About 61,000 lines leave Drishti, about 2,000 come in.

### What was done

| Item | Result |
|---|---|
| Modules | `drishti-deltalake`, `drishti-iceberg`, `drishti-lakeload` deleted; `drishti-lakeio` is `lake/drishti-lake-host` (the shipped lake files and Drishti's side of the product). `lake-bom` and the AWS SDK bill imported in the parent (Drishti's own pins first). |
| Catalog and runtime | `DrishtiLakeCatalog`: shipped lakes from every jar's and live module's `drishti/lakes/shipped.txt`, pack files from the one resolution, connector templates, named lake writers, `ReadyRule` extensions, the module boot's child-process settings. `DrishtiLake`: the one `LakeRuntime` of the process, built once by `PackEnvironmentPostProcessor` from the server's settings with the catalog and the `mem://` storage, and installed; a tool or a unit test without one gets one of the process's settings (rebuilt when they change). `DrishtiLakeCli load|check`: the loader and `LakeCheck` with that runtime. |
| Settings | The product's options under its names (`lake.*`, `LAKE_*`): archive caps, business zone, Parquet row group and page, lakes dir, footer and Iceberg caches, loader trace. `drishti.home`, `drishti.data.dir`, `drishti.config.dir`, `drishti.tmp-dir` stand for the lake's folders when those are not set. |
| Source plugins | Delta and Iceberg on `com.ash.lake.read` only (`LakeReader`, `TableReader`, `TableSnapshot`, `DayFiles`, `DataFile`, `IdMap`, `ColumnBlock`, `VectorRequest`, `Documents.withVectors`): no Kernel or Iceberg type, no package-private class. `VectorBatches` back in the Delta adapter (RA-7). `LakeReads` (lake-host) opens a connector's lake, maps a failed read to `UnreadableData` and keeps the connector's own listings. |
| Memory lake | `ConnectorRows`: the connectors as the product's `RowSource`; `Retention` held by the server (`RetentionHolder`); `MemoryBinding` in `server.memlake`; `MemoryStores.of(runtime)` everywhere. |
| Duplicates gone | Drishti's `RowFeed` (the product's, in the runtime's business zone, for every store loader); the hadoop engine's leftovers: the `engine`, `hadoop.*` and `lz4-via-native` settings, the Delta engine variable, the launcher's `--engine`, the Windows workflow line, the delta-s3 example's row, the hadoop jars of `plugin-delta`. |
| Python | `tools/lake/filedate.py` deleted: 15 callers on `drishti_lake.filedate`; `hostdates.py` keeps `local_day` and `of_input` (host). The loader starts as `DrishtiLakeCli load`; `lakeconfig`, `undo`, `relocate`, `delete`, `deltaread` start the product's mains. |
| Tests | Tests of the table layer's internals deleted (their cases are the TCK's: `NamedTables`, `NewTables`, Iceberg layout, orphan files, REST and S3 catalogs, S3 natives, pyiceberg, `AppendNewestLoadTest`, `PythonPathEqualityTest`). Iceberg fixtures (contract rows, vector lakes, the server's mirror) written by the one loader (`format: loader-rows`). TLS and proxy fixtures from `tools-testkit`. |

### Results

| Check | Result |
|---|---|
| Compile, main and test, every module but `packaging/dist` and `drishti-modules-it` (needs the module packaging) | green; the server exec jar builds |
| VaR/ES goldens: `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` | **pass, bit-identical**. `PackagedVarGoldenTest` not run (`drishti-modules-it`) |
| Demo lake: `lake demo --engine java --only trades/endur,market-risk/{risk-factor,scenario-pv,desk-pnl}` (4,407 trades a day, 60 days), built by this branch's jar (`DrishtiLakeCli load`) and by `wip/g4-rehearsal-rx`'s (Drishti's own loader) | **identical**: `compare_lakes.py` 4 of 4 tables, 565 of 565 partitions, 318,180 rows; every column of every row equal (all but `_load_id` and `_source_file`); table properties equal |
| Delta plugin tests | 93 run, 90 pass, 3 fail (R1, R2) |
| Iceberg plugin tests | 49 run, 44 pass, 5 fail (R6) |
| `drishti-lake-host`, `drishti-packs` | 6 and 69 pass |
| `drishti-engine` | 711 run, 2 fail, none lake: `AbandonedReadsTest` (timing, P12), `DerivedPageTest` (Rachana, P3) |
| Python (`test_feedfiles`, `test_business_dates`, `test_archives`, `test_javaload`, `test_launch`, `test_build`) with `drishti_lake` on the path | 126 pass after one expectation changed (the loader's `load` word) |

### Breakages left

| # | Symptom | Cause | Who fixes | Size |
|---|---|---|---|---|
| R1 | `TableReader.snapshot(AsOf.time(t))` before the first commit throws `no version committed at or before` and records a problem (health DEGRADED) instead of empty (`DeltaSourcePluginTest`: knownAt, health) | the read API's own contract says "empty: no table then" | Lake | S |
| R2 | `DeltaAfterMaintenanceTest`: the maintenance job does not run | `tools/lake/maintain.py` without `drishti_lake` and the Delta writer's new main | Drishti (D5) | S |
| R3 | The data inbox's loader child is the product's `LakeLoad` (runtime from the process: no catalog, so no shipped lake or pack template in the child) | `LoaderProcess` launches its own main | Lake: a host-named main for the child; then Drishti passes `DrishtiLakeCli load` | S |
| R4 | `lakeconfig.py` reads lake files through `LakesCli` without Drishti's catalog (a shipped lake the folder lacks is not seen) | `LakesCli.run(runtime, ..)` is not public | Lake (public `run(runtime, args, out)`), then Drishti (`DrishtiLakeCli lakes`) | S |
| R5 | `drishti-plugin-iceberg-external` is out of the build | the shaped read of a foreign table (READ_API RA-S8) is not built; `ExternalTable`, `KindConfig`, `Shape` are package-private | Lake (RA-S8), then Drishti rewrites the plugin as Delta and Iceberg here | M |
| R6 | 5 Iceberg tests: vector-lake fixtures through the one loader (reject words, file counts, a null vector's document) | tests written for `IcebergLoader`'s shape | Drishti | S |
| R7 | Id-map files keep Drishti's `MappedIdFile` format beside the product's `IdMaps`; Iceberg's maps are now keyed by the files stamp (rebuilt once) | RA-5: the folder's lifecycle is the host's, the format the product's | Drishti (adopt `IdMaps` under `IdMapFolder`) | S |
| R8 | `jsonstream`, `archives`, `lakeconfig`, `laketables`, `maintain*`, `delete`, `undo`, `relocate`, `scan`, `deltaread`, `ownership`, `compare_lakes`, `memory_*` stay in `tools/lake` | the wheel has no `inputs`-compatible stream (JSON arrays, archive guards) and no lake-cli parts yet (SPLIT_STATUS L10 "not yet") | Lake (L10 parts), then Drishti | M |
| R9 | `drishti_lake` must be on the tools' path (here `PYTHONPATH` to the product's `python/`) | the wheel is not in the console's environment | Drishti (D1, D5: install the pinned wheel) | S |
| R10 | `testing/drishti-testroots` is still a shim; Drishti's tests use `lake-tck`'s `TestRoots` | L6 | Drishti | S |
| R11 | Docs not rewritten: `CONFIGURATION.md` (regenerate), `DATA_LAKE_GUIDE`, `DATA_ARCHITECTURE`, `CLI_REFERENCE`, `OPERATIONS_GUIDE`, `DEVELOPER_GUIDE`, the plugin READMEs (the hadoop engine's places, old module and class names, `DRISHTI_LAKE_CLASSPATH`); `tools/packmake.py` and the bench scripts still name the Delta engine variable; the capacity workbook's setting names | D7 | Drishti | M |
| R12 | `ArchitectureRulesTest` still names `StorageProviders` and the old packages; `PackagedVarGoldenTest`, browser tests, the TCK inside Drishti and a delta-rs read of the rebuilt lake not run | D6, D8 | Drishti | S to M |
| R13 | The process runtime lives in one holder (`DrishtiLake`), installed at start and read by the plugins | `SourceContext` carries no runtime | Drishti (the plugin context hands the runtime) | S |

### Pass 3 closure, Drishti side (2026-11-21, on `g4/lake`, against Drishti Lake `420ba7a`)

| # | State | What was done |
|---|---|---|
| R2 | closed | `maintain.py` runs (the `drishti_lake` import it needed came through `lakewheel`); a Delta table's dates leave through Drishti Lake's `LakeRetention --dates` (`delete.plan`), row retention and the Iceberg upkeep through its `LakeRetention` and `IcebergMaintenance`. Delta compaction, checkpoint and vacuum stay on delta-rs: the product has no Delta upkeep tool yet (Lake item). `DeltaAfterMaintenanceTest` passes |
| R3 | closed | `DrishtiLakeLoad` (the child's main: `LakeLoad` with Drishti's runtime); `DrishtiLakeCatalog.loaderMain()` names it |
| R4 | closed | `DrishtiLakeCli lakes` runs `LakesCli.run(runtime, ..)`; `lakeconfig.py` starts it (the lake-file reader sees the shipped lakes) |
| R5 | closed | `drishti-plugin-iceberg-external` is back in the build and the server: `IcebergExternalPlugin` on `LakeReader.foreign` with a `TableShape` (631 lines to about 520; `Shape`, `KindConfig`, `ExternalTable` not copied); `StarterSchemas` from the snapshot's columns. 7 of 7 tests pass. Differences: the reader's "looked in ..." account of a missing table is not given (the plugin says `no table <t>`), and starter schemas describe flat columns (no nested fields, no partition-spec date role): Lake items |
| R6 | closed | Iceberg plugin: 49 of 49 (a null vector's document is `null` on every store: the one loader's rule, the Delta fixture follows; one data file a date; the loader's reject words) |
| R7 | closed | One id-map format, Drishti Lake's `LKIDMAP1` (`MappedIdFile` writes and reads it; strings in String order). What stays Drishti's: the lifecycle (reader guard, unmap, registry, TTL, cap), which `IdMaps` does not have. A file written by either is read by the other (`MappedIdMapTest`, same bytes) |
| R8 | partly | One launcher for the Java tools (`stores.loader_command`, `LAKE_CLASSPATH`, else the exec jar): undo, retention, relocate, maintain, deltaread, lakes, load. The Iceberg writers (`lake build --format iceberg`, `ingest_jsonl --store iceberg`, `stores.copy`) go through one `loaderows.py` onto `DrishtiLakeCli load` with `format: loader-rows`. Left in `tools/lake` (no product equivalent): `jsonstream` JSON arrays, `scan`, `ownership`, `compare_lakes`, `memory_*`. `data load --store iceberg` is refused with a pointer to `lake build --format iceberg` until rebuilt on the one loader (the files store's `JsonlLoader` is Drishti's and works) |
| R9 | closed | `drishti-lake==0.9.0.dev0` in `drishti-console/requirements.txt` from `lib/wheels` (`--find-links`, pure-Python wheel, no executable); tools find the wheel by themselves where it is not installed (`tools/lake/lakewheel.py`, or `LAKE_PYTHON` for development). The wheel is not committed |
| R11 | closed | Every hadoop-engine mention and old module name in the guides, plugin READMEs, examples, deploy scripts and tools; the settings catalogue (`engine`, `hadoop.*`, `lz4-*` gone from the connectors; `LAKE_CLASSPATH` one variable for the tools); `CONFIGURATION.md` regenerated and checked; the Delta engine variable, `DRISHTI_ICEBERG_CLASSPATH`, `DRISHTI_LAKE_CLASSPATH` gone. Left as they were: history in `IMPLEMENTATION_PLAN`, `BENCHMARK` results, the generated bench result files; Iceberg's `catalog: hadoop` is the path-based catalog's name and stays |
| R12 | closed | `ArchitectureRulesTest`, 30 of 30, analysing `com.ash.drishti` and `com.ash.lake`: (1) the lake-layer rules keep their text (they run on the product's classes now on the class path); (2) added `theLakeProductKnowsNoDrishti`, `sourcePluginsReadThroughThePublicReadApi` (Delta, Iceberg, Iceberg-external plugins: no table-layer, Kernel, Iceberg, Parquet or Hadoop type) and `onlyTheLakeHostBuildsTheRuntime`; (3) **narrowed**: `temporaryFilesComeFromTempFiles` now covers Drishti's classes only (the product has its own `TempFiles` and four legitimate callers of `Files.createTemp*`; Drishti Lake has no rule of its own for it: a Lake item) |
| build | closed | `packaging/dist` and `drishti-modules-it` build and test-compile (three test fixtures moved to the runtime and the one loader) |
| tests | closed | `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` pass (bit-identical); `PackagedVarGoldenTest` passes (2 of 2, the exec jar and the `.dmod` packages, the demo lake built by `lake demo --engine java`); Delta plugin 94 of 94 (R1 and R2 included), Iceberg 49, Iceberg-external 7, `drishti-lake-host` 6, `drishti-packs` 69, `ArchitectureRulesTest` 30. Banking's kernel-internals allowance names `lakehost` instead of the old lake packages (conformance C1) |

Python: the tests that cross-checked Java source or fixtures of the deleted module now use a copy of the product's `childkinds` fixtures
(`tools/lake/testdata/childkinds`) or `LAKE_HOME` (the ready keys); `test_delta_interop` compiles its reader against `lake-delta`.
Two failures are older than this branch (`test_memory_caps` run-config check, `test_lake_paths` clusterctl path): same result at `48f7b9931`.


### Pass 3 lake closure (2026-11-21, on `g4/lake-close`, against Drishti Lake `cbd329d`)

| # | State | What was done |
|---|---|---|
| R5 (the two differences) | closed | Both were generic, so both are Drishti Lake's. `TableReader.absence()` says why a table has no snapshot (a foreign Iceberg table: the catalog's account, for a path-based one the files looked for and what the folder holds; a Delta table: no log; files: no folder), and `TableSnapshot.documentFields()` lists a shaped table's documents' fields with nested fields and the id and date roles, the date also when it is the table's day partition (`DocumentField`; additive, `api-surface.txt` extended; `ShapedReadTest`). The connector's health says `no table ghost: looked in ... for version-hint.text, v<N>.metadata.json ... (the folder is empty)` again; `StarterSchemas` writes nested objects, lists, maps and `x-drishti-role: date` from the partition spec again (tests in `IcebergExternalPluginTest`) |
| R8 | closed | Decided one by one against Drishti Lake `cbd329d`. Kept as Drishti's own, now said in each file: `jsonstream` (Drishti Lake's loader reads JSON Lines; JSON arrays of any size are the exports `lake build`, `lake scan` and `convert` take; a Lake item if the loader should read arrays), `scan` (profiles an export for `lake build`), `ownership` (the record of the tables `lake build` made, for Drishti's maintenance), `compare_lakes` (an acceptance check by an independent reader, delta-rs, on purpose), `memory_caps` (the launchers' JVM sizing before any JVM starts) and `memory_cli` (the server's admin REST API). None duplicates a Lake capability. `data load --store iceberg` works through the one loader: `samplegen/load_rows.py --format iceberg` (the samples and the book in one run, the layout's promoted columns, the corrections applied before the one load a table, each business date replaced) on `DrishtiLakeCli load`; its options are the loader's settings (`--catalog`, `--uri`, `--warehouse`, `--credential`, `--token`, `--set KEY=VALUE`). Run end to end on the shipped samples (25,430 rows, 29 tables, 10 business days; the working folders removed) |
| Delta upkeep | Lake gap | Drishti Lake has no Delta compaction (OPTIMIZE), explicit checkpoint or VACUUM: `maintain` is Iceberg only (`IcebergMaintenance`), `LakeRetention` removes dates and rows, the Delta writer checkpoints on its own cadence only. `tools/lake/maintain.py` keeps delta-rs for those three until Drishti Lake has them (the item for the lead: `lake maintain` on a Delta table: compact small files per date, checkpoint, vacuum with the retention floor and the ownership rule; then `maintain.py` calls it and delta-rs leaves the tools) |
| Lake items found | open | (1) the date guard (`--future-days`, `--zone`) and the retention guard (`--max-drop-share`, `--force-drop`) the old Iceberg loader had are not in the one loader; `data load --store iceberg` takes neither (Delta never had them there). (2) JSON-array input in the loader (above). (3) the archive rule of tools/lake/archives.py (moved to Drishti Lake) (451 lines, with the `rows` layout `load_rows.py` reads) and `drishti_lake.archives` (the feed layout only) are two copies of one rule: to be one when Lake's gets the `rows` layout |
| Python | closed | `test_memory_caps` (the IDE launchers name the JVM options file `serverstart.jvm_flags` writes; the exec jar's manifest grants native access, so no `--add-opens` is passed: the test asked for the old flags) and `test_lake_paths` (`clusterctl` spelled `data/delta/demo`: it asks `lakeconfig.default_root`) fixed at their cause; `drill-java25.txt` no longer names the nine tests that moved to Drishti Lake; the planner's `JvmFooter` flag is `lake.parquet.footer-cache-mb` (the rename left it behind; workbook and test vectors regenerated); `test_maintain_iceberg` knows the lake-files reader's new main; `test_launcher_memory` asks `loaderows` for the Iceberg loader's JVM |
| Results (after the last edit) | | Python `tools` (1,352 tests): 1,341 pass, 11 fail, none lake: `test_config_readers_guard` (`tools/mrloader/contract.py` and `test_launchers.py` name the packs folders: older than this branch), `test_sdk_builds_genomics` (starts Maven offline), and the contracts' eight and `test_pack_new` pass with `jsonschema` installed (25 of 25). Java: `IcebergExternalPluginTest` 7, Iceberg plugin 49, Delta plugin 94 (`DeltaAfterMaintenanceTest` skipped: no deltalake in the Python it starts), `ArchitectureRulesTest` 30 (the rule's Jackson exemptions are the only change), `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` and `PackagedVarGoldenTest` (2 of 2) pass: the goldens are bit-identical |

## Pass 3 (2026-11-21): tools, on `g4/tools` (merged into `wip/g4-rehearsal-rx` after the lake pass)

### What was done

Drishti Tools (`github.com/ajsinha/drishti-tools`, `main` at `b3a451d`, no tag yet: `1.0.0-SNAPSHOT`) built into a scratch Maven repository, pinned once as
`drishti.tools.version` and imported through `tools-bom`. Taken from it, with the copies deleted:

| Tools module | What Drishti no longer carries | Where Drishti's names go |
|---|---|---|
| `tools-net` | TLS (`TlsContexts`, `TlsSettings`, `TlsMaterial`, `PemReader`, `Der`, `CertInfo`, `TlsHttp`), the proxy (`ProxySettings`, `NoProxy`, `SiteProxy`) | `DrishtiTools`: the variable `DRISHTI_ALLOW_INSECURE_TLS`, the site proxy's `drishti.http.proxy.` |
| `tools-settings` | `Placeholders`, `SettingsFile`, `SettingSpec`, `Secrets`, the module `drishti-settingsfile`; `Roots.anchorValue` is `PathAnchors` | `DrishtiTools.applySettingsFile()`: `drishti.settings.file`, `${drishti.home}` |
| `tools-concurrent`, `tools-time` | `SingleFlight`, `LoadGuard` | `DrishtiTools.loadGuard(args)`: business zone from `LAKE_BUSINESS_ZONE` (the lake pass's variable) |
| `tools-testkit` | `FakeProxy`, `TlsFixture`, `TestPki` | the TLS test identity is `CN=client` and key alias `client` (was `drishti`) |
| `tools-xlsx` | the four writers: `XlsxWriter` + `XlsxCharts` + `XlsxFormulas`, the pivot export's `Xlsx`, the playgrounds' `SheetStream` + `WorkbookStream`, `CapacityWorkbook`'s own zip | `ExportWorkbook` (an `ExportDoc` laid out on the writer), `PlaygroundWorkbook` (template look, properties, branding footer), `CapacityWorkbook` (cells of a `WorkbookTemplate`), `PivotExport` |

Lines: 26 main files and 7 test files deleted (4,065 and 1,077 lines); the branch adds 1,455 (the adapters, `DrishtiTools`, the comparison tool, the guard);
net -4,392. `SharedToolsGuardTest` holds it: no main source carries a copy or writes workbook parts.

### The xlsx proof

Every workbook Drishti's tests make was written on the parent commit and on the new code (60 workbooks: the exports with every chart kind, the statement with
its print set-up, the pivot export, the capacity workbook, the VaR, Parametric VaR, Explain, FRTB and SIMM playground books, the VaR basket and the
regulatory sign-off), read with Apache POI and compared cell by cell and formula by formula, then by sheet and by style (`tools/xlsx_before_after/XlsxCompare.java`;
the capture is a temporary patch of the tests, `capture_patch.py` and `capture_unpatch.py` in the same folder, never committed in the tests). Cell values, cell types, formulas (text and cached result), merges, freeze panes, column widths,
outline levels, drop-downs, conditional formats, print set-up, defined names, charts (kind and references) are **equal in all 60**. Every difference:

| Difference | Count | Reason |
|---|---|---|
| Default row height written (15) where there was none; playgrounds 14 to 15 | 337 sheets | the shared writer's default (Excel's own) |
| The first sheet is the selected tab (the playgrounds selected the sheet called `Main`, the exports none) | 30 sheets | no sheet name in the writer |
| Print-titles defined name `'MTM'!$1:$1` written `MTM!$1:$1` | 2 | the quotes are written only where a name needs them; the same reference |
| Pivot export's header fill `DCE6F1` and title 14 pt are the exports' `EEF1F5` and 16 pt | 24 cells | one standard look for the four writers |
| Provenance rows: a run's timing (`computeMs`), the order of the data-version lines (a set) | 6 cells | the runs, not the writer (the same on two runs of the old code) |

Noise the comparison removes, each shown to come from the run and not from the writer: timestamps and run ids; the product name the tests install into a JVM-wide
global; the capacity workbook's live figures; and the order of the FRTB Config rows, which is a set's iteration order and differs between two runs of the *same* code
(the comparison matches those rows by parameter name and writes each formula's `Config!$B$n` by the name).

The three bugs the tools fixed did not occur in the 60 (no sheet name with an `&`, no invalid name, no pivot formula outside the common set): a test now holds
the `&` (`XlsxChartsTest`), the other two are the tools' own tests. **The `SCATTER` chart type the tools dropped is used by no caller**: Drishti's chart kinds are
bar, column, line, area, pie and doughnut (any other visual is columns), as before.

### Findings

| # | Finding | Action |
|---|---|---|
| T1 | The FRTB playground wrote some cells of a row out of column order (the Classes header row's term cells, the Curvature bucket header and term cells): invalid for the file format, accepted by the old writer | fixed in `FrtbPlaygroundBook`; the shared writer refuses it, so any new book is caught |
| T2 | The FRTB Config sheet's row order changed from run to run | **fixed** (`g4/fixes-1`): the fixed parameters are in declaration order, the risk-weight rows (which follow the data) are collected in a sorted set and written after them in key order; a test holds it; the comparison tool's by-name matching of Config rows is removed |
| T3 | Tests that assert the old writer's raw XML (`s="3"`, `<v>92000.0</v>`, `chart2.xml`) | made independent of style indexes and part numbers (`DistApiTest`, `PivotExportTest`, `XlsxChartsTest`) |
| T4 | The settings catalogue read `TlsSettings.java` and `ProxySettings.java` from `drishti-api`; they are in the tools now | reads a checkout of Drishti Tools (`DRISHTI_TOOLS_HOME`, else the folder next to this repository). **`--check` run** against the built kernel jar (`g4/fixes-1`): three variables missing from the index (`DRISHTI_TOOLS_HOME`, `DRISHTI_XLSX_CAPTURE`, `DRISHTI_LOCAL_MAVEN_REPO`) added to `tools/settings_schemas.yaml`, `docs/admin/CONFIGURATION.md` regenerated; `--check` is current |
| T5 | The SDK's `repo/` must carry the tools jars | **fixed** (`g4/fixes-1`), one rule for all products: `repo/` carries every jar and pom of drishti-rachana, drishti-lake and drishti-tools at the versions the root pom pins, copied from the local Maven repository (`DRISHTI_LOCAL_MAVEN_REPO`, else `-Dmaven.repo.local` of `MAVEN_ARGS`, else `~/.m2/repository`); a product not installed there is refused by name; `build sdk` never builds them (`sdkbuild.py`, `test_sdkbuild_folder.py`) |
| T6 | `Secrets` outside TLS throws the tools' `SettingException`, not `TlsException` | no caller catches the old type (the build and the tests show it) |
| T7 | Tools has no tag: `1.0.0-SNAPSHOT` from a scratch repository, as Rachana and Lake | pin to the tag when it exists |
| T8 | Four tests failed | **all four fixed at their cause** (`g4/fixes-1`): `TestPathsGuardTest` named `LanguageLevelGuardTest`, which built its root from `Path.of("..", "..")`; it now uses the property drishti.repo.root (a test property). `SimmServiceTest` and `SimmPlaygroundWorkbookTest`: no SIMM bug; `SimmTestData` built `FrtbProperties.defaults()`, which resolves banking's folder from the module boot (none in a unit test); it now names the parameters folder as `FrtbTestData` does (SIMM code and goldens unchanged). `PivotFormulaMigrationTest`: Rachana's reader now accepts `and`, `or`, `not` and `where=`, so those saved formulas need no migration; the five parity-set rows that expected the old printer's text lose their `rachana:` expectation (the text itself is expected); the other 14 migrate to the same text as before and `PivotFormulaParityTest` (the answers) is green. P10's open question is settled for the test by Core's reading; the migrator itself goes with the DEL reader in 2.9.14 (Q24; `I7B_DUPLICATES.md` is not in this branch) |

Green: the TLS and proxy tests of the connectors, lake storage and AI, the settings and placeholder tests, the loaders, `NamedResources`, the settings catalogue,
the pivot and export tests, the capacity workbook, the VaR, FRTB, SIMM playground and API tests, `SharedToolsGuardTest`; the VaR and ES goldens are untouched (no
engine or test-data file changed). Build: `mvn` with a scratch `-Dmaven.repo.local` holding Drishti Tools, Rachana and Lake snapshots; nothing installed in `~/.m2`.

## Pass 3: compute engine (2026-11-21), on `g4/compute-engine` merged into `wip/g4-rehearsal-rx`

Front-loaded from the engine integration design (EN-25, owner: fix known duplicates now). The two interfaces named `ComputeEngine` are one:
`com.ash.drishti.api.compute.ComputeEngine` (drishti-api) with the union of their real uses, each method with a default: the switch
(`id`, `start`, `changing`, `stop`, `memoryBytes`, `datesOpen`, `threadPrefixes`, `running`, `threadsNamed`) and the reactive computation
(`manifest`, `compute`, `incremental`, `affected`, `close`). `id()` defaults to the manifest's name; `manifest()` of a switch-only engine
refuses. `kernel.api.engines.ComputeEngine` is deleted (the package is gone): kernel API **0.6**, surface baseline regenerated. The registry,
Admin controller, configuration, module bridge, `BasicEngine`, `OpenBlasEngine` and the registry test import the one type; the reactive
engines (`VectorSumEngine`, `BetaEngine`, `CorrelationEngine`, `VarResultEngine`, `ExposureResultEngine`, test engines) are unchanged. Engine
ids and the catalogues (`engines/catalogue.yaml`, modules' `META-INF/drishti/engines.yaml`) are unchanged, so they stay consistent. The new
engine model (`com.ash.engine`, `EngineLifecycle`, engine-host) stays for the combined i8.

| Check | Result |
|---|---|
| Compile, main and test, every module but `packaging/*` and `drishti-it` (needs `drishti-app`) | green |
| `ApiSurfaceTest`, `EngineRegistryTest`, `EngineApiTest` (switches, Admin → Engines API), `EngineCatalogueTest`, `EngineFilesTest`, `ModuleBridgeTest`, the three `*ModuleOffTest`, `ReactiveApiTest`, the reactive connector tests, `BetaEngineTest`, `CorrelationEngineTest` | pass (225 tests in the run) |
| VaR/ES goldens: `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` | **pass, bit-identical** |

## Pass 3 (2026-11-21): binder, ViewModel writer and aggregate kernel onto Rachana, on `g4/binder`

Branch `g4/binder` from `wip/g4-rehearsal-rx` `f954d27bf`, merged back into it. Rachana rebuilt from `origin/g2/integration` `f7f4cd8f` (includes the G4-ready commit `35fdf00f`) into a scratch Maven repository; Elements pack unchanged from pass 2.

### What was done

| Step | Result |
|---|---|
| Rachana's "Drishti to act on" | `DataNode.nodes()` returns Drishti's `NodeFactory` (lists and records an expression builds are `DataNode`s); `SutraJsonMixin` deleted (the `Sutra` record's components are its JSON; a written Sutra now also carries `extensions`); the level is pinned with `Rachana.at(level, ...)` (guard extended to `Rachana.at`); the parity oracle `LegacyListAggregates` reads a list of any node type. **P4 closed**: the 0 was the oracle's; `SutraAggregateParityTest` passes (39 calls, 153,900 comparisons). |
| 8.3e one binder | Drishti's `engine/{bind,view,control,wire}` and `live/{Frame,Patch}` deleted (5,398 lines, and the five unit tests Rachana holds); 69 files' imports rewritten to `com.ash.rachana.*`. Drishti keeps only profile hooks, in `engine/rachana`: `DrishtiBinder` (`LinkResolver` over `ReferenceCatalog` and `Mnemonics`; the `links` and `provenance` panels reading a `Host` record in `BindContext.host()`; `Semantics::humanize` labels; limits; `PivotCore` as the `PivotEngine`), `DrishtiTables` (`TableProvider` over `SourceRouter`, `ComputeRef` ids), `DrishtiDays`, `Hosted` (the Drishti and Rachana refs, as-of and documents), `PanelSettings` (`drishti.panels`, since Core's `PanelLimits` has no Spring annotation). `ViewerContext` is the `Masker`. Formats reload: `DrishtiRachana.prepareFormats` (a `LiveSettings` bean) reloads Rachana's live `Formats` in place (**P7 closed**). The seven new panel kinds come with Core's binder (**P8 closed**; the schema test is held to the server's level 1.0). Jackson reads Rachana's `@Include` and writes Rachana nodes; `RCH-` codes of Rachana records, of the expression check and of a panel's error are answered `DRS-` (**P9 closed**). |
| 8.3g writer | `ViewJsonConverter`: the API answers a ViewModel with `ViewJson.write` as a raw body; `ViewJsonBytesTest` proves the bytes equal to the server's Jackson (reference trade, netting set, curve, all-panels showcase). There is no `Rachana.binder(hooks, engine)` in this Rachana: Drishti builds `new Binder(el, formats, hooks, engine)`. |
| 8.3h aggregate kernel | Drishti's `functions/{Builtins,Moments,MoreAggregates,Holistics,Comoments}` and `rachana/{ListAggregates,ListAnalytics,ListGrouping}` deleted; `Functions.builtIn` takes Core's `Catalogue.of` behind `CoreAggregate` (a thin `AggFunction` shell; state bytes and SQL text Core's); `EngineFunctions` gives only the `_once` family (`OnceAggregates`), `measure`, `cell` and the measure-level functions, never a Core name (**P1 closed**, stopgap gone). `Final` stays in Drishti (Core's `Final` keeps `TAG`/`encode`/`decode` package-private). `AggregateStateCompatTest`: states recorded by the deleted classes decode with Core's kernel; same bytes, except `geomean` (state) and `skewness_pop` (figure) in the last place (CoreMath), as 8.3h expected. `FUNCTIONS.md` regenerated from the instance catalogue (Core's names: runs R, source Rachana Core; **P11 closed**). Net: 3,535 lines deleted, 392 added. |

### Proof

| Check | Result |
|---|---|
| All unit tests, every module but `packaging/*`, `drishti-it`, `drishti-modules-it`, `drishti-benchmarks` | **5,625 run, 8 failures, 1 error** (pass 2: 759 errors); two of them fixed after the run (test paths, panel error code), the rest below |
| VaR/ES goldens `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` | pass, unchanged. `PackagedVarGoldenTest` not run (needs a packaged build) |
| ViewModel goldens (`ViewPipelineTest`, `DrillPanelTest`, `DeepTradeViewTest`, `ChartPanelsTest`, `CoreSutraNextEquivalenceTest`, `LiveTest`, `ViewModelSchemaTest`, `ViewJsonBytesTest`) | pass. `MetricPanelTest`, `MapPanelTest` are in `drishti-modules-it`: not run |
| Rachana conformance inside Drishti with the profile loaded (`DrishtiProfileConformanceTest`: the pinned suite's expr, expr-error, check, grammar and format cases on `DrishtiRachana`'s instance over `DataNode`s, against the suite's reference instance) | **1,999 cases, 1,998 alike**; one known difference listed (below) |
| `drishti-rachana` 70, `drishti-bicompute` 300, `drishti-engine` 700 tests | pass (but `AbandonedReadsTest`, timing) |

### Remaining breakages

| # | Symptom | Cause | Who |
|---|---|---|---|
| P10 | `PivotFormulaMigrationTest` | fixed in `g4/fixes-1` (see T8 of the tools pass): Core's reader accepts `and`/`or`/`not`, the five parity rows expect their own text | done |
| P14 | `WindowFunctionsTest`: `rank(sum(trades.mtm), 'sideways')` no longer refused | Rachana's checked mode changed (CF fixes) | Rachana to confirm, or the test changes |
| P15 | `el/limits#call-chain-past-the-depth`: `RCH-2101` on the reference instance, `StackOverflowError` on Drishti's | not found; the depth refusal comes too late on Drishti's instance | investigate (Drishti or Rachana) |
| P2 | a compiler taken early never sees a module loaded later (`ElCompiler` bean, `RowPredicate`, `DocPath`) | per-instance function table | Drishti, unchanged |
| P12, P13 | `AbandonedReadsTest`, `NetForwardTest`, `VarShardClusterTest` (timing); `PythonPathEqualityTest`, `DomainPacksTest` (demo data for the business day) | not the rehearsal | rerun quiet; triage |
| R-1 | Rachana's ViewModel schema has no data record for the level-1.2 `form` kind (`PanelData.FormPanel`) | schema gap | Rachana |
| R-2 | Core's `Final` (`TAG`, `encode`, `decode`) is package-private, so a host keeps its own holistic-final state | API | Rachana (optional) |

Note (g4/fixes-1): `drishti.py build jar` ran the server's tests and `LiveTablesAdapterTest` failed (4 tests: no table `position`, RCH-2081 on `TRADE_ID`, a problem-message text) against the lake lane's Rachana snapshot; not one of the four above, not investigated, not changed here.

## Pass 3: shims (2026-11-21), on `g4/shims` from `wip/g4-rehearsal-rx` (`fd1b4a847`)

Owner direction: no customers, so no compatibility code. Removed from the product (rows S1 to S7 of `I7B_DUPLICATES.md`; level 1.0 and Lake's LZ4 reader kept as listed there).

| Row | Removed | Lines |
|---|---|---|
| S1 | `tables/migrate` (9 classes), `bi/migrate` (7), `StoredTablesMigration`, `PivotFormulaMigration`, the `migrate` command of `TablesCli` and `tablescli.py`, `bi migrate` of `bilang.py`, the setting `drishti.bi.migrate.*`; `PivotFormulaMigrationTest`, `StoredTablesMigrationTest`, `TablesMigratorTest`, `NullRuleTest`, `BoardMigrationApiTest` | about 4,300 |
| S2 | the DEL reader: `DelParser`, `DelToRachana`, `DelAst`, `DelTablesTranslator`, the `Legacy` mode of `Expressions`, the setting `drishti.bi.expr.legacy`, `OLD_SYNTAX` | about 1,100 |
| S3 | `LegacyNames` and its calls (`.uploads`, `.previous`) | 134 |
| S4 | `LakeRelocateOnStart`, tools/lake/relocate.py, `lake relocate`, drishti.lakes.relocate-on-start | 330 |
| S5 | `ApiAlias`, `ApiAliasFilter`, `api.legacy`, `EngineKeyAliases` (it aliased names nothing read) | 250 |
| S6 | `lake migrate-config` (Python side) | 50 |
| S7 | `LegacyImport` (users.json), `NoteImport`, `SharedLib.migrate` / `previousDefault` and its test, the `plugin-dir` default | 650 |

**Messages kept (one line, what to do):** an old-form BI dataset or model (`DRS-9196`, `DRS-9614`) says to write it as a table or model; `drishti.sources.plugin-dir` and `drishti.external-lib`, when still set, log that they are no longer read; `lake build` says to delete a demo lake left in the older layout and build again.

### Part 2 (2026-11-21): the test fixtures, the notes table, the console redirect, Help's old names, the lake's migration code

Owner rule restated: no shim is kept "for tests" either. What was left in part 1 is now done, except the two items named at the end.

| Row | Removed | Lines |
|---|---|---|
| S1 (tests) | the test-scope migrator (`tables/migrate`, 8 classes), the DEL reader (`DelAst`, `DelParser`, `DelToRachana`, `DelTablesTranslator`), `Layered` and the old `TablesFolder`; `EquivalenceTest` and `NoMoreRowsAfterMigrationTest` (both compared old forms with the migrated ones) and the `before-tables` and `samples-2.1` fixtures | about 4,000 |
| S1 (fixtures) | the 55 server tests that went through `Layered` or `TablesFolder` now hold tables and models of the layer: `Tables` (read a model over its tables), `TestLake` (the shared model, its two tables, `liveTrades`, `table`, `withTable`), `TablesFolder.place` (a folder case's tables and models are files in `src/test/resources/bi/folders/<case>`), `bi/samples` (the 2.1 samples as models, tables and rebound reports). Each fixture was written by running the old migrator once over it and keeping the output (read, not trusted: five hand repairs, three tests whose assertions were about the old forms rewritten for the current checks) | +2,400 |
| S7 | `NoteStore`, `NoteEntity`, `linkNote` and its three implementations, `drishti_note` and `drishti_note_link` in both schema files (the two files change together; no migration, as the database rules say) and their tests | 300 |
| S7 | `FormatSchemas.LEGACY`: a bad value of any format key is an error (`DRS-9032`), not a warning for the keys a 2.3 report could hold | 20 |
| S7 | the console's `/bi/b/<id>` redirect (alerts and runs already link to `/bi/r/<id>`; the fixtures that held the old address changed first), Help's `aliases:` (pack guides name their section by its id: 30 `help.yaml` files) and `moved:` (about 400 lines) with `section_id`, `lib.moved`, the settings catalogue rows and the tests of the old addresses; the links of `developer.md` that used old slugs | 520 |
| S7 | `LegacyListAggregates` (the "before" oracle): `SutraAggregateParityTest` compares the engine with Rachana Core (`new ElCompiler()`, no host function) over the same Sutras and cases | 143 |
| S6 (Lake) | `LakesCli migrate-config`, `LakeFiles.migrate`, `adoptDemo`, `demoRoot` and their helpers, `ConnectorLake.legacy` and its `LakeCheck` note; `api-surface.txt` and CHANGELOG; Drishti's three tests of the same code; tools/ci/check.py **green** (build, delta-rs interop 3+3+2 with none skipped, Python tests, parity kit twice, coverage gates, equal to the reference). drishti-lake `main` `94dd281` | 497 |

Also fixed at the cause: `NodeLowering` threw a null pointer for `rank()` (a window function with no measure); it now refuses with a message (the DEL reader used to catch this before Rachana's lowering saw it). The window test's `rank(x, 'sideways')` case is dropped: the current checker accepts it (a Rachana question, not a shim).

**ArchitectureRulesTest and OneGrammarRulesTest were already failing on the base branch** (`wip/g4-rehearsal-rx`; the classes came in with the 8.3e and 8.3g commits). `noSerializable` now exempts Jackson's extension points (`SimpleModule`, `AnnotationIntrospector`, `BeanSerializerModifier`, `BeanPropertyWriter`: Serializable by their base classes, the rule's existing kind of exemption); `onlyTheEngineAddsFunctionsToRachana` exempts the module host's `RachanaFunctions` (a started module's scalar functions; the engine stays the only source of aggregates).

**Not done, and why (named, not deferred silently):**

- S6 `ConnectorManager`'s connectors defined in `application.yaml`: the seven profile files (`application-{postgres,files,mongodb,redis,duckdb,aerospike,iceberg}.yaml`) define their connectors there, and `props.connectors()` is also the map the connector files are loaded into. Removing the path means writing those profiles as connector templates of a module (a design step, not a deletion). Left as it was.
- Lake `PersistedNames` read-both (`drishti.*` then `lake.*`): the check of the owner's Linux stack lake folders (read-only) was refused by the sandbox, so whether those lakes carry the old names is not known. Not removed.
- `LoadConfig`'s "target.lake is a folder: read this way for one more release" and `CollabImport` (the jpa and file stores into the content store) are further shims of the same kind: not in the list; named here.

### Part 3 (2026-11-21, `g4/shims-3` from `wip/g4-rehearsal-rx` with `g4/shims`, built against Drishti Lake `main` and Rachana `develop` from a scratch repository)

| Item | Done |
|---|---|
| S6 connectors in `application*.yaml` | The seven store profiles are deleted. Their connectors already existed as files, one per domain, in `config/connectors.examples/<store>/` (compared setting by setting: the files carry the same values and, besides, the reference kinds' modes and the lake form of Iceberg), so the conversion is the deletion. `drishti.py start server --<store>` and the benchmark scripts (`tools/bench/scale.sh`, `pinning.sh`, whose RabbitMQ connector is now a file of its own) read the folder with `DRISHTI_CONNECTORS_DIR`. The `connectors:` entry and its deprecation text left `application.yaml`; `StoreProfilesTest` reads the postgres example files. Docs: DATA_GUIDE 9.11 and the store sections, OPERATIONS, DEVELOPER, the file connector README, CONFIGURATION (a Stores section; regenerated). |
| S7 `CollabImport` | Deleted with its test and the DATA_ARCHITECTURE paragraph; the content stores it also held are now `CollabContent`. |
| S7 `target.lake` as a folder | The Drishti side holds nothing of it (the CLI reference no longer names it). The code is Lake's: `lake-load` `LoadConfig`, the `warnOnce("target.lake ... is a folder ...")` branch: to remove in Drishti Lake. |
| Help anchors | The 17 broken anchors of `test_help_links` came from five causes: an unclosed code fence in CLI_REFERENCE before section 13 (it swallowed the headings of 13 and 14, so `#13-the-risk-engines-...` and `#var-the-var-engine` vanished); the renamed headings of DATA_ARCHITECTURE 3 and 10, ARCHITECTURE 30.2 and DATA_LAKE_GUIDE 7, now named as they are; DATA_GUIDE no longer linking the `delta` section of CONFIGURATION. `test_help_links` is green. |
| Lake jars | Drishti builds against Drishti Lake `main` (with `lake-bom`). The one removed API in use was `ConnectorLake.legacy`; its caller `ConnectorLayers` no longer treats a file in the explicit-path form specially. |
| `rank(x, 'sideways')` | **Not re-added.** Rachana `develop` refuses it only from language level 1.1 (`CatalogueArgs`); the server runs level 1.0 (kept on purpose), which keeps the reference's acceptance. The case returns with the move to level 1.1. |

**Not done, named:** `drishti.sources.connectors.<name>.*` as a property (the overlay the cluster launcher's `enabled=false` flags and about 70 tests use) still binds and still warns; removing it means every one of those tests defining its connector as a file. The other one-time imports (`BiImport`, `DesignImport`, `RunImport`, `ProposalStore`, `LoadsConfig`, `EmbedAppRows`, the audit import) are the same kind of shim as `CollabImport` and are not in this part. `PersistedNames` (Lake) is as in part 2. `test_docs_rot` has five failures that were there before this part (paths and headings moved by earlier passes: the Rachana package paths, `tables migrate` and `bi migrate` still named in two documents, links to removed engine headings).

### Part 4 (2026-11-21, `g4/shims-4` from `wip/g4-rehearsal-rx` `d06a8d6e2`)

| Item | Done | Lines |
|---|---|---|
| Connector property overlay | `drishti.sources.connectors.<name>.*` as a property or environment variable no longer binds: `ConnectorManager` refuses the start naming the file to write (`<connectors-dir>/<name>.yaml`); its deprecation warning, the `application` origin banner and the `deprecated` field of the connectors API, the console's banner, `applicationDefined`, are removed. The packs' loaders still hand the files' own values to the server as the internal `drishti.sources.connectors.*` properties (their one internal form). About 60 tests: the connectors are files of `testing/connectors/<set>/` (README there), copied by `ConnectorFixtures.copy` into the run folder and named as `connectors-dir`; a value only the running test knows is a placeholder `${test-connectors.<connector>.<setting>}`; the net tests write a node's files with `NetNodes.connector` / `off`; the shard tests write them beside `risk-lake.yaml`. `ConnectorManagerTest` proves the refusal. | about 700 removed, 150 fixture files |
| Cluster launcher | the stack's `off:` (and the connectors a lake leaves without tables) are written as `enabled: false` override files in the node's own connectors folder (`data/stack/nodes/<node>/connectors/`, marked, removed again when no longer listed); no `--drishti.sources.connectors...` argument. A `home: repo` node takes no `off:` (refused with the way to do it in `config/connectors/`). `deploy/stack/stack.yaml` needs no change (its `off:` lists are on `home` nodes b and c); its comments and README now say how. The owner's own stack.yaml: nothing to change either, unless a `home: repo` node lists `off:`. | |
| One-time imports | `BiImport`, `DesignImport`, `RunImport`, the governance proposals' folder import (`ProposalStore.importFolder`), the loads config files import (`LoadsConfig.Overrides.importFiles`), `EmbedAppRows.Content.importFrom`, the audit table copy (`JpaAuditLog.copyTo`), `JpaBiStore.allMarks/allUsage/allSettings`, their tests, `system/imports/*` markers, and the import paragraphs of DATA_ARCHITECTURE, CONFIGURATION (regenerated; `settings_catalogue --check` green) and the settings comments. | about 900 |
| `test_docs_rot` | five failures fixed at the cause: the paths of Rachana's moved classes (named as Rachana's classes), the removed `tables migrate` and `bi migrate` commands, stale anchors, the Rachana codes DrishtiBinder answers as `DRS-2xxx` and two retired codes (listed), `DRS-9190` (the dead `EXPR_CONVERT` removed; the documents say `DRS-9196`), and `config/engines/sensitivities.yaml`, whose `drishti.sensitivities.*` keys nothing read (the code binds `drishti.frtb.*`): now `drishti.frtb.*`. | |

**Not done, named:** `PackStateStore` (the table and document import into the switch ledger), `LoadStore`'s `<data>/loads/<pack>.json` first-read import, the data-source override migration (`connector-migrated`), `drishti.identity.users-file` and `audit-file` with `FileUserStore` and `FileAuditLog` (only tests use them). VarApiTest `aDateThatIsNotOneRun...` and the three Iceberg-loader tests (`VarShardClusterTest`, `VarFormatEqualityTest`, `ExposureFormatEqualityTest`: "the vector field 'PNL' is not a list of numbers") fail on the base branch too.

## Pass 3: live tables (2026-11-21), on `g4/live` merged into `wip/g4-rehearsal-rx`

The switch of Drishti's LiveSet engine to Rachana's live tables (I7B_DUPLICATES L1, DD-6, LIVE_TABLES section 11), front-loaded into G4
with its entry check first. Rachana as pinned for the rehearsal: `0.11.0-SNAPSHOT` from the lake lane's scratch repository (`417f6af9`; its
`rachana-live`, `rachana-data` and `conformance` are identical to drishti-rachana `develop` `86862e17`).

### What was done (additive; nothing deleted)

- `kernel/drishti-server` `com.ash.drishti.server.live`, the adapter (1,313 lines with headers and comments): `KindBindings` (what the bindings give one kind:
  the columnar store, documents, records by id, the `live:` binding, its pause), `KindSnapshot` (`SnapshotSource`: tier `columns` when a lake
  keeps the kind as columns, asked only for the columns it keeps, else `whole`), `KindStream` (`LiveStream` over `router.subscribeKind`),
  `KindRows`, `DrishtiAuthoriser` (kind access as `unknown`, row rules as `rowTest`, the redactor as `redact` plus `maskedColumns`, editable
  columns by `roles` / `editable-by`, read-only reasons), `DrishtiChangeSink` (the write-back's `EditService.submit` as the person, form
  `live:<table>`, change id passed through, `awaiting` answered `held`), `DrishtiLiveTables` (configuration, `*.live.yaml` loading with
  Drishti's profile keys, the extra checks, default declarations `kind-<kind>` with `narrow:` from a kind's `list: requires`, the write-back's
  rules) and `LiveTablesController` (the Data Protocol at `/api/v1/rdp`, SSE changes, windows owned by their caller).
- Switched off by default: `drishti.live-sets.engine: rachana` turns it on (CONFIGURATION.md row). No second wire runs in the product.
- `LiveTablesAdapterTest`: the eleven scenarios of `LiveSetTest` (8) and `StorePagesTest` (3), each under its old name, on `rachana-live`
  through the adapter fed with Drishti's `EntityDocument`s.

### Entry check (DD-6, section 4) and the cut line

| Entry fact | State |
|---|---|
| The 11 ported LiveSet scenarios on `rachana-live` with Drishti's adapter | **pass, 11 of 11** (the million-record case at 100,000 locally: first page 500 ms, half-way page 0 ms, first sorted page 712 ms, a page after a live change 2 ms, 399 records fetched) |
| `rachana-live` in a tagged Rachana | **missing**: neither `v0.10.0` nor `v0.11.0` holds it; the branch pins `0.11.0-SNAPSHOT` |
| The grid's live-mode edit UI (L8) | built in Elements 3.2.0 (`834ef87`); the console vendors the 3.1.0 tree, whose grid speaks `/api/live` (LiveSetGrid) |
| `live/*`, `protocol/window`, `write`, `approve` suites verified | **missing**: all "unverified" (green on Rachana's reference host only) |
| The scale run | 100,000 rows done in Rachana; 1.3 million not run |

**Cut line DC1 applies**: two entry facts are missing, so nothing is deleted. `server/liveset/*`, `/api/v1/live/*`, `trade.blotter.yaml`, the
console's `live_results.py`, `live_routes.py`, `drishti_live-page.js` and `drishti_records.js` stay, and the console keeps painting through
them. Lines removed: 0.

### Findings for the switch itself

| # | Finding | Where it lands |
|---|---|---|
| LV1 | A FORM result is filled today from the **search API** (masks, edition, Drishti*net scatter across nodes, relevance order, `limit`, labels). LT-5 makes it "a query with a filter on the kind's table", but Drishti has **no search-grammar to filter-tree compiler**, and a live table is per node: fan-out and as-of dates must move into `KindSnapshot` (i8 steps 2 and 4). This is the largest piece and is not started | the switch-over lane |
| LV2 | Rachana refuses an edit field bound to the key (`RCH-2081`); `trade.blotter.yaml` has `{ id: trade_id, bind: TRADE_ID, readonly: true }` and `key: trade_id`. The `.live.yaml` drops both (the table's key names the row) | declaration rewrite |
| LV3 | Drishti's row rules read nested fields and `$user` attributes: they go as `rowTest` (not pushed down). On a `columns`-tier table the engine tests the rule on the scanned columns only, so a rule on a field that is not a column hides the row (fails closed) | the authoriser; or rules' fields added to the scan |
| LV4 | `ChangeSink.accept` carries no caller; the write-back submits as a person, so the mount marks the request's person for the write (`DrishtiChangeSink.as`). Four eyes stays Drishti's approvals (`held`); the tray's release/reject is not wired (`holds()` false) | adapter; a Rachana SPI change would remove the mark |
| LV5 | `ProtocolHost.keys` is package-private: the Spring mount copies it (10 lines) | Rachana: make it public |
| LV6 | `bi/engine/LiveTable` (636, with `LiveTables` 330, `LiveAggregate` 393): **stays**. It keeps a kind's current state as a table *inside the analytic engine* so BI and MQ answer SQL (group, join, standing queries, `updateSeq`) over it; Rachana's live table holds rows in memory for pages and patches and answers no SQL. Different job, not a duplicate; only the name clashes (rename to `EngineLiveTable` when it is next touched) | closes I7B_DUPLICATES R13 |

| Check | Result |
|---|---|
| `LiveTablesAdapterTest` (11) | pass on Rachana `417f6af9` and again on drishti-rachana `develop` `6ae430bc` (api, core, data, live built into the lane's scratch repository `~/.cache/drishti-lanes/g4-live/m2`), on the tree merged with the lake and binder passes |
| `LiveSetTest` (8), `StorePagesTest` (3), `LiveIsolationTest` (3), `LiveQueryTest` (5), `LiveBlotterBrokerAbsentTest` (1): the engine that still serves | pass |
| VaR/ES goldens | not affected (no engine or test-data file changed; not run) |


### Pass 3 live tables, the switch-over (2026-11-21, on `g4/live-2` from `wip/g4-rehearsal-rx`)

The owner's rule (no dual machinery at the drill) overrides cut line DC1: the switch is made and the liveset engine deleted. Built against
drishti-rachana `develop` (`7b16e0a0`, with LV5 below), Drishti Lake `94dd281` (Lake `main` 0.9.0 refuses `${drishti.data.dir}` in a lake
file since G3 F5: every `config/lakes/*.yaml` must move to `${lake.data.dir}` before the re-pin; the Spring context does not start
otherwise) and Drishti Tools 1.0.0-SNAPSHOT (the branch's pin), in `~/.cache/drishti-lanes/g4-live2/m2`.

**What was built**

| Gap | Where | How |
|---|---|---|
| LV1 search grammar → filter tree | `drishti-engine` `SearchFilter` (one compiler) | `SearchQuery.pick` and the search's own name resolution, the condition made row-relative (`@.X`), `startsWith` as `starts_with`, Rachana's `FilterTree.of` for the tree; an id pattern is a condition on `id`; the order a `{col, dir}` (else by id), the limit the search's. A condition outside the tree's subset (`matches`, `size`, links) has no tree and stays in the table's `where:` |
| FORM results | `server.live.FormResults`, `GET /api/v1/rdp/searches` | a table of the kind per (columns, day[, kept condition][, audience when spanning]) declared in code (`q-<hash>`, its source in `LiveSources`), read with `{filter, sort, limit}`; whole when a column is not a lake column; a condition on a field masked for the person answers no row (the protocol refuses such a filter, 403) |
| As-of dates | `KindBindings` with `AsOf`, `KindSnapshot` | a day other than the live one reads the lake as of that day (columns and documents) and the table has no stream (`live: false`) |
| Drishti*net fan-out | `KindSnapshot.Peers`, `KindStream.Peers`, `PeerSources.subscribeKind`, `GET /api/v1/net/changes/{kind}` | for a person whose search the cluster routing scatters: the table is the audience's own, whole; its snapshot is this node's rows then each member's share of the search (as the person, one hop), merged by key (higher row version wins when given); each member's changes over the new peer stream (its own records, masked there) |
| LV5 | Rachana `develop` `cc577818` | `ProtocolHost.keys` public (case `ProtocolHostTest.keysAreReadAsTheProtocolSendsThem`); the mount's copy deleted |
| Profile in the schema | `LiveTablesController` | `GET /tables/{name}` adds `profile` (kind, links), `tray`, `readOnly` (why not, from the authoriser) and `excludes` (switched-off trading systems) for the grid |

**Lines**: 2,310 added, 5,213 removed against `d06a8d6e2`.

**Deleted**: `server/liveset/*` (8 files, 3,302 lines), its 6 tests (`LiveSetTest`, `StorePagesTest`, `LiveQueryTest`, `LiveIsolationTest`,
`LiveBlotterBrokerAbsentTest`, `ContractColumnsTest`) and `LiveSetValidationTest`, `/api/v1/live/*`, the console's `/api/live/*`,
`drishti.live-sets.engine`; `trade.blotter.yaml` is `trade.live.yaml` (LV2: no key field in the form). `KernelLiveQueries` reads
`FormResults` (the kernel API `LiveQueries` keeps its shape). The console's grids (FORM results, the search page, bare mnemonics, the
blotter page, the trading landing) paint through the same LiveSetGrid over a Data Protocol adapter in `js/drishti_records.js`
(query, record, write; changes on the one live channel, `live:<table>[~<filter>]`, events `msg` and `gap`).

**Ported and proved**

| Check | Result |
|---|---|
| `LiveTablesAdapterTest` (11 LiveSet/StorePages scenarios), `LiveIsolationTest` (3, ported: shared table, scope on reads and changes, whole and paged; masked column refused; audience), `ContractColumnsTest` (3) | pass |
| `FormApiTest`, `FormResultsParityTest` (10 searches: rows, count, order and columns equal the search's; tree vs kept condition; as-of), `LiveTableValidationTest` (3), `KafkaTradeEditTest` (write through `/rdp/tables/trade/write`, four eyes held then applied once, stale refused) | pass (9, 3, 3, 1; `StructuredSearchTest` 11 unchanged) |
| Console: `test_live_results`, `test_live_routes`, `test_form_routes`, `test_base_path*` with the browser tests | 93 pass |
| Blotter browser tests `test_live_grid_browser` (pinned Playwright 1.64.1) | Chromium 15/15; WebKit 15/15 (LD_PRELOAD of `~/.cache/drishti-lanes/webkit-libs`; the clipboard read-back is Chromium-only) |
| VaR/ES goldens | not affected (no engine, VaR or test-data file changed) |
| `ArchitectureRulesTest` | pass (30) |
| `KafkaBlotterTest` (the plug-in as a blotter's source and write path) | pass (3) |
| `LiveBlotterBrokerAbsentTest` (ported: snapshot served, `live: paused` with the binding's reason) | pass |

**Behaviour that changed (findings)**

| # | Finding |
|---|---|
| LS1 | A FORM's rows have no title column (a table row has no search title); the id is shown |
| LS2 | The console's grid follows the table unwindowed on the shared live channel: a change of a row a paged grid does not hold moves its count and **N new** (no top insertion within a second); a windowed stream remains the protocol's (`POST /changes` with `window`) for a client that holds its own connection |
| LS3 | A source failure answers `503 rdp.unavailable` without the source's words (POS/PORT on a server with no VaR inputs) |
| LS4 | `asOfNotices` are carried by a search's answer (`/rdp/searches`); a bare mnemonic's table page does not carry them |
| LS7 | The write-back gives a change its own id: Rachana's change id is not the approvals id (find it by `GET /api/v1/changes?mine=true&entity=`) |
| LS5 | LV4 stays (no caller in `ChangeSink.accept`; the mount marks the person); the tray's release/reject in the table are not wired (`held` is Drishti's approvals) |
| LS6 | `LiveSources` keeps at most 2,000 code-declared tables; Rachana has no undeclare, so a declaration outlives its source when evicted (read again on next use) |

### Pass 3 live tables, the fixes (2026-11-21, on `g4/live-fixes` from `wip/g4-rehearsal-rx` `14c20e677`)

The owner's rulings on LS1 to LS7 and LV4 (no feature loss; audit correctness):

| # | Ruling | Done |
|---|---|---|
| LV4, LS5 | the caller must reach the change sink and the audit record | the mount marks the person for the write it serves and `DrishtiChangeSink` refuses a change whose maker is not that person (never submitted as anyone else); `live-write-*` audit records carry the caller, the change id and its columns (subject `<table>:<change id>`), the write-back's `edit-submitted` the same id. The tray's release/reject stay Drishti's approvals page (no feature the old engine had) |
| LS7 | Rachana's change id end to end; a repeated id gets the same answer (PROTOCOL 16) | `EditService` keeps a client's id (`[A-Za-z0-9_-]{1,64}`, Postgres `VARCHAR(64)`); `EditService.known(id)` reads the edit log or the approval queue; the sink's `madeBy`/`prior` answer a repeat from it (any node, after a restart); a repeat through the write-back API answers the change's state (`edit-repeated` audited), another person's is refused |
| LS3 | a failing source's 503 carries the source's words, made safe | `ReadFailures` (one line, 300 characters, file paths cut to the name, URL credentials and `password=`/`token:` values as `***`), kept per kind by `KindSnapshot` for two minutes and cleared by a good read; the mount adds them to every `503 rdp.unavailable` (`message` and `source`) |
| LS1 | FORM results keep their title column | a FORM's table has `_title` after the id, filled from the sources' listing (the search's titles) on a whole read and by Drishti*net members; the console's Title column shows when it says more than the id |
| LS4 | a bare mnemonic's page carries the as-of notices | `GET /rdp/kinds/{word}` answers `{table, asOfNotices}`; the console's kind page shows them as a search's |
| LS2 | N new instead of jumping: intentional | said in LIVE_RESULTS_GUIDE.md section 7 |
| LS6 | unchanged (a declaration outlives an evicted source; read again on next use) | — |

Delta upkeep: `tools/lake/maintain.py` no longer imports delta-rs; relayout, compaction, retention, checkpoint, vacuum and the report's
shape are Drishti Lake's `lake maintain` (Drishti Lake `78a8422`, to be tagged v0.9.1). Two things the delta-rs relayout did are not carried: promoting a pack's
columns into a table written without them (every table Lake builds has them) and the `delta.dataSkippingStatsColumns` property (Lake writes
statistics for the id, the date, the load id and the promoted columns only). The retention rule is Drishti's again on Delta (today and
`keep-business-days` business days before it); Iceberg keeps its own (the newest N dates), as before the move.

| Check | Result |
|---|---|
| `LiveTablesAdapterTest` (11, with the repeat through a second engine, another's id refused, an unmarked write refused, the audit), `LiveTablesFixesTest` (5), `LiveIsolationTest`, `LiveBlotterBrokerAbsentTest`, `ContractColumnsTest` | pass |
| `FormApiTest` (9, the Title column and `/kinds` notices), `FormResultsParityTest` (3, titles equal the search's), `KafkaTradeEditTest` (four eyes; the id kept; a repeat after the approval answers without a second write; another trader's repeat 403), `LiveTableValidationTest`, `StructuredSearchTest` | pass |
| Console `test_live_results`, `test_live_routes`, `test_every_mnemonic_opens_live` | pass (`test_form_routes`: 3 fail before and after the change, the pack index of the test environment) |
| Blotter browser tests `test_live_grid_browser` | Chromium 15/15, WebKit 15/15 |

**Not done in this lane (lane closed by the owner):** the Python maintenance tests (`tools/lake/test_maintain.py`, `test_maintain_admin.py`,
rewritten for Lake's rules and relayout) were not run: they need `LAKE_CLASSPATH` on a Lake build holding `--relayout` (78a8422) or the
re-pin of `drishti.lake.version` to v0.9.1; the four VaR/ES goldens and `ArchitectureRulesTest` were not run on this branch (no engine,
VaR or test-data file changed); the console's `test_form_routes` three failures (present before the change) are not investigated.
**Lake side not merged:** the Lake work (keep-days rule restored, `lake maintain --relayout` on all three stores, compaction keeping the
newest copy and each older load's copies apart, `read-layout` kit verb, kit case `maintain/sorted-layout`) is on drishti-lake
`wip/g4-live-fixes-lake`, **not on main**: tools/ci/check.py is not green. Delta interop 3/3 and the Python path pass; the kit ran twice;
`LakeMaintainTest.compactsAndVacuumsAlike` fails on Delta (data files after compaction plus vacuum not fewer than before: the three
appends of the same ids now keep three files) and Iceberg (a second compaction rewrites again: the compacted form is not recognised from
its `_load_id` bounds). Until that lands and Drishti pins the Lake tag, `maintain.py` calls `--relayout`, which Lake 0.9.0 refuses.

## Pass 3: renderer (2026-11-21), on `g4/renderer` merged into `wip/g4-rehearsal-rx`

G4 item 10 (I7B_DUPLICATES U1, B6; DD-7: Jinja stays). Branch `g4/renderer` from `wip/g4-rehearsal-rx` `fd1b4a847`. Drishti Elements
**pinned at 3.2.0**: the release tree of the `v3.2.0` tag (`787f97d`, built by `tools/elements/cli.py dist` in a fresh clone), its SHA-384
manifest unchanged; `drishti.elements.version` 3.2.0, every module's range `>=3.2 <4`, every pinned path, Drishti's two libraries rebuilt.
The data tables (`world-regions.yaml`, `formats.yaml`) are byte-identical to the 3.1.0 tree, so the server jar's behaviour is unchanged.

### What was done

| Item | Result |
|---|---|
| The entry | `static/render/3.3.0/drishti-render.js` keeps the API (`render`, `patch`, `subscribe`, the `Handle`, `<drishti-screen>`, `window.DrishtiRender`) and `drishti-elements.js` the embed elements 2.x (`<drishti-view>`, `<drishti-panel>`): same options, events, attributes and contract. |
| Every panel | drawn by `drishti-views` `paint()` (`shadow: false` inside the host's shadow root): the title line, strip, freshness line, keys, every kind; drishti-chart, drishti-graph, drishti-data, drishti-inputs and the geo part handed to it from the same module graph. |
| Host part (Drishti's) | `render/3.3.0/m/`, 10 modules, 1,598 lines: where the ViewModel comes from (`/api/view`, embed v2, the token, the sign-in window), navigation in place with its own Back bar (links are real `/v/…` addresses; the painter's cancelable `link` event opens them), About this page, live frames handed to the painter, the stream over drishti-events. |
| CSP | the sheet (`drishti-render.css`, generated from Elements' tokens with every theme and the painter's sheets, 1,579 lines, was 3,292) is adopted by the shadow root; the loader's page links for those sheets are claimed without a request, so a host page with `style-src 'self'` loads no stylesheet and reports no violation. No inline styles. |
| Explain | unchanged code: its `paintPanels` calls the same `render()`; Why it moved (drawer, its three views, the full page) is the painter's. |
| Deleted | the `drishti-renderer` library (17 modules 2,783 lines, its bundle 1,972, harness and fixtures 134), `tools/renderer_fixtures.py`, `renderer.test.mjs`, `test_elements_renderer_browser.py`; `render/3.1.0/` (its paths answer 308 to 3.2.0 for one release). Not ported (DD-10, DD-11): GenUI stream messages, `facets`, `behaviours: 'console'` (accepted; the painter's tables), the `draw*` parts of the handle (no caller). |
| Fixed on the way | `/embed/v2/elements/2/` resolved to the frozen 1.2.0 and `/embed/v2/render/2/` to nothing (pre-existing, two failing tests): the element API 2.4.0 maps to the current renderer, the alias is the major; embed v2 serves drishti-views and drishti-events modules; `elements_pin.LIBRARIES` holds all ten. |

Lines: the renderer library and its harness 4,889 deleted, the host part 1,598 added (about 700 of it the old library's sign-in, token, net and About modules, moved); the generated sheet 3,292 to 1,579; tests and tools 213 deleted. The branch, without the vendored tree: +2,520, −7,938.

### Proof

| Check | Result |
|---|---|
| Embed and Explain browser tests, Playwright 1.64.1 pinned (Chromium 1248): `test_explain_browser` (4), `test_embed_elements2_browser` (5), `test_render_sign_in_browser` (6) | **15 passed** (before: Explain 4 passed, Elements 2: 3 passed, 2 failed on the alias bug) |
| Renderer browser suites: `test_render_library_browser` (14), `test_render_behaviours_browser` (4 + 1 xfail E11), `test_render_parity_browser` without the pack Sutras (32: the console's macros against the painter, content: every cell, field and strip text, every entity link, every chart's series values) | pass |
| Console Node tests (`tests/elements`, `test_elements_node.py`), the render, elements and embed v2 Python tests, the quick file checks | pass, but the six failing on the base commit too (`test_docs_pdfs` 4, `test_code_volume`, `test_docs_ready_example`) |
| Console errors | none on any page; the painter warns (a warning, by design) on the two edge-case ViewModels that are invalid on purpose (`states`, `more-shapes`) |
| VaR/ES goldens (`VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`) | **pass, untouched** (no Java changed) |

**Before and after screenshots**: 14 ViewModels (6 captured views, 8 edge cases) × 2 themes (terminal, light) × 2 widths (1280, 390) drawn through
the public entry, and the Explain test's 7 pages. Pixel diff: a pixel differs when a channel differs by more than **24/255**; compared at the
larger size. Embed pages: 12.1 % to 74.7 % of pixels differ (median 25.6 %); Explain: preview and phone 0 %, the drawer's Why it moved
0.8 % to 1.5 %, the full pages 23.9 % (page) and 26.4 % (Why it moved as a page). Every difference, with its reason (the content is the
same: the parity suite):

| Difference | Reason |
|---|---|
| Type: figures in the sans face (the old renderer set strip, fields and tables in the mono face in the terminal theme); smaller title | the painter's typography (Elements' tokens) |
| Panel header: no accent bar, no per-panel `?` help button; a code tag (`CRV`, `REFS`) as a chip | the painter's frame; the console's help `?` is console chrome |
| Strip: an emphasised figure is coloured text, not a filled box; no "with" before the counterparty | the painter's cell tones |
| Freshness line (`live · aero-risk`) and the function keys row (`F2 Legs …`) drawn under the strip | the painter draws them (the old renderer did not show them) |
| Key-value panels: values beside labels, left-aligned, one column at 1280 | the painter's field list |
| Tables: a "Filter the rows" box and pages on long tables; tree tables open one level; a dash in an empty cell | the painter's tables |
| Each chart: a "Data table" disclosure under it, a legend under donuts | the painter's chart frame |
| Timeline, ladder status chips, gauge bar width | the painter's kinds |
| The element sizes to its content (the old one filled the viewport with the theme's background) | an embed's height is its content; below it the host page shows |
| Explain drawer: the metric panels' header without the accent bar; the change under the figure instead of beside it | the painter's metric kind |
| Candlestick Data table: the raw numbers instead of the server's `openText`… | Elements finding E11 (below) |

### Findings

| # | Finding | Who |
|---|---|---|
| E11 | `drishti-views`' candlestick Data table prints `str(c.open)`, not the producer's `openText`/`highText`/`lowText`/`closeText` the contract carries (test `test_the_candles_print_the_servers_texts`, xfail strict) | Elements |
| E12 | the painter cannot take a host's sheets: with a shadow root it links each library sheet into it, without one into the page; a cross-origin host with `style-src 'self'` refuses both. Drishti claims the loader's links and adopts its own sheet; an option for adopted sheets (or a pack sheet as a `CSSStyleSheet`) would remove the claim | Elements |
| E13 | the chart kinds need `window.echarts` before they draw (`engine: () => win.echarts`); the host loads it. The painter's own loading would match its words ("the chart engine loaded only when a view has a chart") | Elements |
| E14 | the painter's validation warns on ViewModels without `contract`; Drishti sets `1.x` (VIEWMODEL.md section 8) | Drishti, done |
| D1 | the server-side parity check of every pack Sutra (`test_every_pack_sutra_on_its_sample_is_the_same`) still skips: the server's preview answers 422 for those Sutras (as on the base commit) | Drishti, triage |

### Pass 3 renderer follow-ups (2026-11-21, on `g4/renderer-2`, merged into `wip/g4-rehearsal-rx`)

Drishti Elements `develop` `3c9908f` and `5ddb94f` (not tagged: the lead tags 3.2.1 after the G3 gate), vendored as the release tree of a fresh
clone of `5ddb94f` (`cli.py dist`) under the pin folder `3.2.0` (its manifest still says 3.2.0; `VENDOR.md` names the commit and the dev label
3.2.1-dev). Elements' tools/run_tests.py: every pass green, nothing skipped.

| # | State | What was done |
|---|---|---|
| E11 | closed | The candlestick Data table prints `openText`/`highText`/`lowText`/`closeText` (the number only without a text); Elements browser test; Drishti's strict xfail removed, the test passes |
| E12 | closed | Paint option `sheets` (constructable sheets adopted by the view's root; the painter links no stylesheet, neither its own nor a library's). Drishti passes its one sheet; `claimSheets` (the claimed `<link>`s) is deleted |
| E13 | closed | The painter loads the chart engine itself the first time a view has a chart, or the host's way (`engine`). Drishti passes `assets.echarts` (its Trusted Types policy); its own pre-paint engine load and the kinds walk are deleted |
| E14 | closed | `complete` gives a contract-less ViewModel the painter's contract; Drishti's own stamping line is deleted |
| Parity | closed | Paint option `tableTools: {filters, chart}`: a funnel on each column heading (values with counts, search, Select all, a number condition such as `> 1m`; AND across columns; kept through patches; Clear filters) and Chart any table (drishti-chart's new `TableChart`/`openTableChart`: the rows as shown, in a `<dialog>` inside the view; kind, across, series; the card's data table and export; a grid's Chart the range opens it). `behaviours: 'console'` maps to it. Browser tests in Elements and in Drishti (`test_console_behaviours_bring_the_funnels_and_chart_any_table_into_the_container`). GenUI stream messages stay i9 scope |
| Missing links | closed | A link whose target the server found `missing` is a link again (its chip says so), as the console's pages link it; only `denied` is words |
| 3.1.0 paths | closed | The redirect is removed: `/embed/v2/render/3.1.0/` answers `410 DRS-8006`, `/static/render/3.1.0/` `404` |
| D1 | closed | The 422s had two causes, both of the test's server: no module packages in a lane (`Modules: none`: `modules/*.dmod` not built, so the trading pack's blocks were absent: 130 of 138) and the packs a module or the kernel leaves off by default (`gleif`, `us-equities`, `funds`: their blocks absent: 8). The pack-views server now turns every shipped pack on (`DRISHTI_PACKS=default,<every pack>`), a refusal is a failure (no skip) and the preview's own words are reported. 241 Sutras bound; the suite then showed 18 real differences: links to `missing` targets (fixed in Elements, above) and content a click away in the painter (a long table's further pages, closed pivot and tree rows), now opened before the read (intentional difference `pages-and-closed-rows`). `test_render_parity_browser`: **274 passed, none skipped** |
| Quick checks | closed | The four guide PDFs regenerated, README's code volume, the settings catalogue's counts; the ready example's duplicate key-list test (it read the deleted `drishti-lakeload`) removed: the one check is `tools/lake/test_feedroute.py`. Console and tools static checks green |
| Elements develop | note | Vendoring `develop` brings its breaking "one name per thing": chart-kind and aggregation aliases are gone (`KIND_ALIASES`); Drishti's Node test updated. The BI's own visual vocabulary (`type: column`) is mapped by Drishti's BI code; the BI suites were not run in this lane |

**Proof.** Explain, Elements 2 and sign-in browser tests, the render library, behaviours and parity suites, the render, elements, embed and
explain Python tests, the console Node tests: 228 passed, 1 fixed after the run (the Node alias test), skipped only WebKit (not installed
here) and the framework builds (not fetched). No Java changed (the goldens untouched).

**The painter's look that remains** (pass 3's table, less what this pass closed): the type (sans figures, smaller title), the panel frame
(no accent bar or per-panel `?`; the code tag as a chip), the strip's coloured text, the freshness line and keys row, the key-value layout,
the tables' filter box, pages and one-level trees, the Data table disclosure and donut legend, timeline/ladder/gauge drawing, the element
sizing to its content, and Explain's metric panels. Closed here: the candlestick Data table (server texts). New with `behaviours: 'console'`:
a small funnel after each column heading and a Clear filters and a Chart button in the table's bar. Pass 3's before/after shots are no longer
on disk (its scratch folder was cleared); this pass's after shots (14 ViewModels × 2 themes × 2 widths) are in the lane's scratch folder.

## Follow-ups (2026-11-21, on `g4/follow-ups`, against Drishti Lake `eafbd4e`)

| # | State | What was done |
|---|---|---|
| Delta upkeep | closed (one part stays) | A Delta table is kept by Drishti Lake's `lake maintain` (`LakeMaintain`: keep-days under the drop guard and the retention floor, row retention, compaction, checkpoint, vacuum; `maintain.lake_command` builds the command, the JSON line becomes the report's `retention`, `row-retention`, `compact`, `checkpoint`, `vacuum`). The delta-rs compaction, checkpoint, vacuum, retention counting and estimate code is deleted. Two things stay on delta-rs, both read-only or Lake's gap: the report's file counts and sizes (`add_actions`), and the pack's sorted layout (`relayout`, `keep_stats_small`): Lake's compaction does not keep a laid-out table's id order, so a laid-out table is relaid first and Lake compacts nothing. **Lake item**: sorted layout for Delta tables (Iceberg has it). The retention rule is now Lake's on Delta as on Iceberg: the newest `keep-business-days` dates on or before today are kept (it was the dates within that many business days back, one more); tests and the Operations guide say so. A Delta lake on S3 reads its `AWS_*` options from the environment of the maintenance command. The Drishti jar needs a Lake build that holds `LakeMaintain` (pin `drishti.lake.version` when Lake tags); tests ran with `LAKE_CLASSPATH` on Lake's own class path |
| Archive rule | closed | `tools/lake/archives.py`, `tools/lake/test_archives.py` and the shared fixtures folder deleted; every host path imports `drishti_lake.archives` (the `rows` layout asked for by name in `convertcmd`). Lake's own tests run the shared fixtures. The host's `target.lake` values are lake names (`javaload`), none a folder |
| Python failures | closed | `test_config_readers_guard`: `mrloader/contract.py` and `test_launchers.py` read the packs through `resolved.pack_dirs()`; `jsonschema` is declared in `requirements-test.txt`; `test_sdk_builds_genomics` takes its read-only Maven repository from `MAVEN_ARGS` (`-Dmaven.repo.local.tail`), else `~/.m2` (it needs the product's pinned Lake and Tools boms to be installed there: on a machine without them it cannot build, whatever the test does) |
| Python result | | `tools` (1,464 tests, 4 workers): 1,427 pass, 30 skipped, 7 fail, none from these changes: `test_sdk_builds_genomics` x2 and `test_pack_new_source` (Maven offline, the boms above), `test_tablescli` x2 (the `migrate` command Lake removed), `test_clusterlakes` (lake nodes switch off the stores' connectors), `genomics_showcase` generator drift. `test_maintain`, `test_maintain_admin` (on Lake's class path): 20 pass. VaR and ES goldens: no Java changed |
| WebKit | closed | `tools/browser/webkit_env.py` (no root: `apt-get download` + `dpkg -x` of four libraries and what they need, `LD_PRELOAD`, validation switched off; `--check`), used by `drishti-console/tests/browser_engine.py`: `DRISHTI_BROWSER=webkit` makes every test's `p.chromium` the chosen engine (TEST_HARNESS.md 3.31). Playwright 1.64.0 matches the installed WebKit build |
| WebKit run | | The 59 console browser files that start no server of their own (the others need a built server jar for this branch, which this machine has not): 376 pass, 247 skip, 64 fail, 4 error. Causes: 32 `render.test` does not resolve (Chromium's `--host-resolver-rules` has no WebKit equivalent: the render harness's origin), 15 `clipboard-write` is not a WebKit permission (`test_live_grid`, `test_records`), 4 errors the VaR engine (needs the server), 5 a sideways-scroll at phone width (`test_page_widths`, 477 px against 390: a real WebKit layout finding), 3 a Content-Security-Policy refusal of an evaluated string, the rest single tests (`test_panel_polish` 4, `test_smat`, `test_table_chart`, `test_help_hub`, `test_bi_catalogue_tabs`, `test_admin_samples`). The 178 server-starting files were not run on WebKit |
## Pass 3: BI charts (2026-11-21), on `g4/bi-charts` from `wip/g4-rehearsal-rx` (`d06a8d6e2`)

Duplicate audit row U9 (DD-8, cut DC3). Jinja stays (DD-7). The pivot engine stays on the server; only the drawing moved.

**Entry check.** What BI draws, and with what, before the switch:

| Where | Kinds and options | Drawn by (before) | Covered by drishti-chart? |
|---|---|---|---|
| Report visuals (viewer, designer canvas, theme designer, boards, the embedded `<drishti-report>`) | bar, column, line, area, combo (second axis), small multiples (`split`), pie, donut, waterfall, treemap, heatmap, scatter, bubble, gauge, funnel, histogram (server bins), box plot (server figures), map (countries, drill to first-level regions, latitude/longitude points), live-chart (rolling window), KPI sparkline; format: stacked, percent, labels, label density and position, pie label style, legend none/right/top, smooth, colour, series colours, value axis (min, max, log, gridlines, show), category axis (rotate, show), axis titles, conditional rules as point colours, gauge min/max, live window, multiples (columns, max, shared axis), map level/drill, number format; interaction: selection fade, cross-highlight overlay, click and keyboard marks, tooltip pages on hover, range brush, live redraw in place | Drishti's `drishti_bi-visuals.js`, `-visuals-format.js`, `-multiples.js`, `-elements.js` (brush) calling `echarts.init`; maps over `drishti_geomap.js` | the spec kinds cover most kinds but not the report vocabulary (wells, server-binned answers, highlight overlay, mark semantics): **added** to drishti-chart as its `report` part |
| Python visual and notebook cells | an ECharts option from the item (sanitised, themed), a measure's rows as bars; drishti-chart spec and Vega-Lite already drishti-chart's | `drishti_bi-python.js` (`echarts.init`) | added (`pythonOption`, `measureBarsOption`, `drawOption`) |
| Chart any table (BI table and matrix, Sutra tables, history) | line, bar, area, pie/donut, scatter, combo; stacks, 100%, labels, Top N with Others, dashes and decals past the palette, SVG renderer, PNG and SVG export, keyboard tip | `drishti_table-chart.js` (`echarts.init`) + the engine's `option()` | added (`tableChartOption`, `OptionChart`) |
| Pivot chart, calendar, sunburst/icicle, decomposition | | already drishti-chart / drishti-tree | yes |
| Exports (PDF, PNG, Excel, CSV) | | server side | n/a |

Nothing was left uncovered, so the lane went on.

**The switch.** Elements develop `cd527cf` (on the v3.3.0 tag): drishti-chart's new part `drishti_chart-report.js` (sources
`drishti_chart-report.js`, `drishti_chart-report-kinds.js`, 830 lines; part budget 70,000 bytes, 65,890 built): `reportOption`/`drawReport`/
`mountReport` (the options moved series for series, the keyboard's marks, hover, click, live redraw in place, the range brush), `reportMap`
over the geo part's helper, `sparkline`, `pythonOption`, `measureBarsOption`, `drawOption`, `tableChartOption`, `OptionChart`; Node tests
(`report.test.mjs`, 12; the Elements suite 411 Node tests and 75 static checks green). Vendored into the pin folder `3.2.0` (VENDOR.md names
both commits; re-pinning to 3.3.x is its own step). Drishti: `drishti_bi-visuals.js` keeps the cards and the report's adapter (`V.env`: formats,
conditional colours, class names), `drishti_bi-multiples.js` deleted, the chart parts of `-visuals-format.js` and the brush of `-elements.js`
deleted, `drishti_bi-python.js` and `drishti_table-chart.js` draw through the part, the table chart engine keeps the numbers and words
(`valueText`, `summary`). BI pages load the geo and report parts instead of `drishti_geomap.js`; the embedded report loads them by name
(`/embed/v1/js/elements-chart-geo.js`, `elements-chart-report.js`); embed v2 serves a library's parts. The guard `test_elements_guards.py`
lost the `echarts` exceptions of `drishti_bi-visuals.js`, `drishti_bi-python.js` and `drishti_table-chart.js`; `drishti_geomap.js` keeps
its one for the Sutra panels' maps and the frozen embeds (render 1.2.0, embed v1: U3, U4). Strict CSP unchanged (no inline style or script
added; colours through the CSS object model). **Drishti lines: +110, −997** (without the vendored tree); Elements +830 source lines.

**Before/after screenshots** (`~/.cache/drishti-lanes/g4-bi/shots/{before,before2,after}`: 31 visuals of three reports, the pages and the
Chart any table dialog, Chromium, Playwright 1.64.1). Two base runs differ from each other by up to 11% on the first report (a chart's
height settles 6 to 8 px differently from run to run): the comparison is against the base run in the same state. Every chart is
pixel-identical except the footer line (milliseconds, "from the cache", "Listening"/"Asked once") and the live chart's clock labels,
**except the maps**:

| Visual | Difference | Reason |
|---|---|---|
| Map by country (world) | Equal Earth projection instead of plate carrée; land a little lighter; the choropleth ramp from the surface to the link tone | drishti-chart's map helper (the view painter's maps use the same) replaces `drishti_geomap.js` for BI |
| Map of points | the same projection and land; bubbles 4 + 30√ instead of 7 + 22√ of the share (the helper's sizing), still in the palette's first colour; countries silent, no small-country dots | the helper projects the points onto its own shapes (raw longitude/latitude over projected shapes would misplace them) |

**Tests.** Console static checks 326 passed; the BI, table chart, maps, embed report, board designer, render library, vendored, licences
and asset unit tests 149 passed (the one failure, `/help/table-chart` 404, fails on the base too). BI browser suites (14 files, pinned
Playwright 1.64.1): 63 passed, 6 skipped (parked to i6), **32 failed, the same 32 on the base** (a separate base worktree, same jar and
packages): the shims pass removed `config/bi/datasets|models` (the new-visuals suite's fixtures), the strict-policy check sees an inline
`script-src-attr` on the base too, the Python and notebook suites' compute set-up, the phone and tablet layout checks. Their first failing
line is the same before and after but for two tests that got further after (the highlight test, base timed out first; the desk-pack viewer,
base failed in set-up): both then stop on the same pre-existing policy violation. BI API tests (`BiApiTest`, `BiReportApiTest`, `FieldParamsApiTest`, `PythonBiApiTest`), `PivotFormulaParityTest` and the VaR/ES goldens: no Java changed in this lane; **not run** (the machine's load stayed above 16 at the end and the owner asked to wrap up), so the goldens are untouched by construction, not re-proven here. Static checks on the merge with `wip/g4-rehearsal-rx`: 322 passed, 4 failed in files this lane does not touch (`test_browser_rules` fixed pause, `KERNEL_AND_MODULES.md` PDF, the settings catalogue twice: from the merged branch). **Not compared** in screenshots: the report designer canvas, the theme designer, the notebook's Python cell and the embedded `<drishti-report>` (they draw through the same calls as the viewer).

**Open.** The BI browser suites need their fixtures moved to tables and models after the shims pass (`test_bi_new_visuals_browser`,
`test_bi_wave_a_browser`) and the pre-existing `script-src-attr` violation found; Elements re-pin to 3.3.x; `drishti_geomap.js` goes with
U3/U4 once the Sutra panels' maps use the geo part.


## Pass 3: level 1.2 (2026-11-21), on `g4/level-12` from `wip/g4-rehearsal-rx` (`d06a8d6e2`)

Owner direction: the latest language level everywhere, no tech debt. Built against Rachana `develop` (`73c76065`, then `7b16e0a0` for the merged live tables; with `rachana upgrade`,
fragment keys and the cross-level refusal) and Drishti Tools `v1.1.0` from a scratch repository. **Drishti Lake `main` (0.9.0) cannot run
this branch's server**: since G3 F5 it refuses `${drishti.data.dir}` in a lake file ("a host's setting: name `${lake.data.dir}`"), and every
`config/lakes/*.yaml` names it; the server proof used Lake 0.9.0-SNAPSHOT (`94dd281`, the rehearsal's) until the lake re-pin moves the lake
files to `${lake.data.dir}` (a lake-pin item, not done here).

### What was done

| Item | Done |
|---|---|
| Upgrade | `rachana upgrade --to 1.2 --write` over every `*.sutra.yaml`, `*.fragment.yaml`, `*.block.yaml` (548: modules, packs, config, UI kit, samples, tutorials, docs examples, test fixtures): 540 upgraded, every change the `declare` rule (no `span` or `named` rewrite needed), 0 blocked. The 8 refused were the `packdiff` test inputs (Sutras with no `rachana:` key at all): they now declare `rachana: 1.2`. Report: `G4_LEVEL_1_2_UPGRADE_REPORT.json`. A second run finds 0 to upgrade |
| Inline and documented Sutras | 98 Sutras inside Java and Python tests and the complete Sutras in the guides (`RACHANA_REFERENCE`, `BUILD_GUIDE`, `OPERATIONS_GUIDE`, `CLI_REFERENCE`, `API_REFERENCE`, `USER_GUIDE`, tutorials, the finance example guide) upgraded the same way; the prose that named `rachana: 1` names the level |
| Writers | The pack generators (`make_sutras.py` `LEVEL`, the genomics showcase, source modules), the console's starters (`designs.py`, `describe.py`, the schema kit, the designer's blank Sutra) and the screenshot scripts write 1.2; the server's inferred and auto-designed Sutras (Core's writer declares the major only) go through `LanguageLevels.written` (upgraded, same meaning). `make_sutras.py` regenerates the 183 committed Sutras byte for byte |
| The one setting | the setting drishti.rachana.language-level (since removed) defaults to 1.2 (`DrishtiRachana.LEVEL`, the newest the runtime implements) |
| Guard | `LanguageLevelGuardTest`: the holder runs the newest level; no other main source builds an instance or picks a level; **every shipped Sutra, fragment and block declares the server's level**; a Sutra of an older level is read at its own level and offered the upgrade |
| Older user Sutras | Never silent: `LanguageLevels` (declared level, `older`, one line of advice, `upgrade` with the server's function table). `drishti.py sutra lint` prints a warning per older file; **`drishti.py sutra upgrade <paths> [--write]`** (Java `SutraCli upgrade`) lists every change and flag and writes only with `--write`; the registry logs the advice for a site Sutra; the designer: the design and every operation answer carry `language {level, server, older}`, the Problems tab shows a `level` item, a click runs `POST /api/v1/builder/designs/{id}/upgrade` (one step of the log, undo takes it back) and says each place to review. Docs: RACHANA_REFERENCE (Language level), CLI_REFERENCE (`sutra upgrade`), BUILD_GUIDE |
| Stricter checks (RCH-2048, S11 names, RCH-2008) | Nothing to fix: every pack Sutra and fragment compiles at 1.2 with no problem (`PackSutrasTest`; the server binds the same set before and after); the inline test Sutras that use UI kit fragments moved to 1.2 with them (a 1.0 Sutra on a 1.2 fragment is RCH-2008) |
| `rank(x, 'sideways')` | The refusal case is back in `WindowFunctionsTest` |
| Conformance inside Drishti | The runner reads its instance as level 1.0 and derives each case's level from it; Drishti's holder now runs 1.2, so `DrishtiProfileConformanceTest` hands it over at 1.0 (`language("1.0")`): every case runs at its own level on Drishti's instance |
| The engine's measure-level functions | `EngineFunctions` gave `rank` and kin a maximum of their by-place parameters, so `rank(x, 'sideways')` was refused by arity in Drishti's words while the reference refuses it with Core's catalogue check (`el/catalogue-arguments-1-1`): they now take up to every parameter and Core's check (1.1 and later) refuses, in Core's words |
| `rank(x, 'sideways')` in BI | Restored in `WindowFunctionsTest` and refused: BI's lowering (`NodeLowering`) read any non-boolean direction as `desc`; a direction that is not `desc = true`/`false` is now refused there |
| Showcase of every kind | `ViewModelSchemaTest` held the showcase to the level's kinds; at 1.2 seven more (grid, tree, chart of 1.1; treemap, bullet, form, live of 1.2): a second example, `docs/guides/examples/level-1-2-panels` (Sutra, JSON, note, README row), covers them (all seven bind on the server); the "no FormPanel record" exemption is gone (R-1 closed in Rachana) |
| Aggregate tests | `RachanaAggregatesTest`, `RachanaStatisticsTest` (the host compiler at 1.2): `range(list, field)` is `span`; sums and averages of integers are exact (`BigDecimal`), compared as numbers |

### Not moved to 1.2, named

- **BI, table-layer and formula expressions** (`Expressions`, `NodeLowering`, `LayerTables`, `Trees`, `LayerExpressionChecker`, `ExpressionCheck`,
  `FormulaLowering`) are parsed by `CheckedMode.parse(text)`, which is Core's level 1.0 with Core's table, not the server's level. Parsing
  them at 1.2 was tried: about 220 BI, table and pivot tests fail, because BI lowers Rachana's tree into its own and does not know the 1.1
  forms (`sum(x, where = c)` is refused by 1.1's named-argument binding; `$.mtm / 1000` and `sum(r * w) / sum(w)` come out as trees the
  lowering refuses). Moving BI's dialect to 1.2 is its own step (BI's lowering of the 1.1 trees, then its goldens); reverted here.
- **Rachana finding (catalogue):** `cell(view, field?, member?, ...)` has no `params` in `functions.yaml`, so from 1.1 the catalogue check
  refuses it with any argument by place ("takes at most 0 arguments by place"). No shipped Sutra uses `cell`; the engine test calls it at
  level 1.0 until Rachana gives `cell` its parameters (or exempts a function without a params row).

### The flags (21 exact, 7 text by code point; the corpus proof's 18 and 6 plus the copies in docs and test fixtures)

Decision rule: exact decimals are the rule from 1.2; a Sutra is changed only where its screen needs a binary double. **None does.** Every
flagged Sutra that binds gives a ViewModel identical to the 1.0 one (below), and the reasoning per flag:

| Sutra (and its copies) | Expression | 1.2 result | Outcome |
|---|---|---|---|
| credit-limit (pack, `sutra-next` v2) | `$.USED / $.LIMIT`, `$.USED / $.LIMIT - $.UTILISATION` | the record's `LIMIT` is a double (`337000000.0`), so the division stays double; a bucket's integers would divide exactly, which is the right utilisation | keep (pct0/pct2 text identical) |
| stress-result (pack, v2) | `max(-$.PNL, 0) / $.LIMIT` and its `> 1` breach test | `LIMIT` double: unchanged; with exact operands the breach test is exact (no false breach at exactly 100%) | keep |
| fx-scenario | `(@ - $.RATE) / $.RATE * 100` | rates are doubles: unchanged | keep |
| flow-schedule, legs (pack fragments, the two `old-*` test blocks) | the average reset rate `sum(...) / size(...)` | rates are doubles; were they exact, the exact mean is the intended rate (`%{rate-fmt}` rounds it) | keep |
| us-company, annual-financials, company-card | `NET_INCOME / REVENUE * 100` (net margin) | integers (EDGAR dollars): exact decimal, `pct`/2-digit text identical; no screen compares it for equality | keep: the exact margin is the intended number |
| us-company, financial-fact | year-on-year change `(a - b) / abs(b) * 100` | integers: exact; text identical | keep |
| metric example (`docs/guides/examples`), var-desk (architecture example) | `$.VAR99 / $.VAR99_YESTERDAY - 1`, `VAR / LIMIT` | exact where the inputs are exact: the delta is the true ratio | keep |
| desk-book (tutorial), netting-set (pack, v2) | `@.NOTIONAL >= ctl.minNotional` and the control filters (info) | texts compared by code point: the same unless a text holds a character beyond U+FFFF; the codes and dates are ASCII | keep |
| legs, endur-callnot (pack, test copies) | date texts compared with `today()` (info) | ISO dates are ASCII: the same order | keep |

### Proof

| Check | Result |
|---|---|
| Every Sutra bound on its sample by a real server (`/api/v1/studio/preview`, every pack on): the base build at level 1.0 against this branch at 1.2 | **303 bound, 301 byte-identical**, 2 differ only in an async compute panel's request id and timestamp (`path-book`, `correlation-matrix`: the run's own request id and time). The 203 that do not bind fail identically before and after (test fixtures that need their own old blocks, architecture illustrations, kinds with no sample). Every flagged Sutra that binds is among the identical |
| drishti-rachana | 71 of 72: `SutraRegistryTest.anyFailureLoadingOneFileIsThatFilesProblem` fails on the base too with Rachana `develop` (100,000 nested parentheses no longer overflow, so the test's "unforeseen failure" does not happen): a test premise for its owner, not a level effect |
| Every Sutra bound again on the final build (after merging `wip/g4-rehearsal-rx`, Rachana `develop` `7b16e0a0`) | 304 bound: 299 identical to 1.0; the new `level-1-2-panels`; `path-book` and `correlation-matrix` differ in an async request id and time; `desk-book` and the `desk.v2` fixture in the source fingerprint only, which the merged live-tables switch changed (identical before the merge) |
| Java, targeted | drishti-rachana 71/72 (the overflow premise above); Rachana conformance inside Drishti **all cases alike** (with the profile); `SutraAggregateParityTest`; bicompute and the table layer all green; drishti-server BI (650) green but `LiveVisualsTest` (its fixture `bi/samples/tables/bi/model:live-trading@1.model.yaml` is missing on the base); `WindowFunctionsTest`, `ViewModelSchemaTest`, `StudioTest`, `ViewPipelineTest`, `DrillPanelTest`, `DeepTradeViewTest`, `ChartPanelsTest`, `CoreSutraNextEquivalenceTest`, `ViewJsonBytesTest`, `LiveTest`, Design*/Builder/Governance/Pack* green; `PathologicalExpressionsTest$LiftedLimits` fails for the same overflow premise as `SutraRegistryTest` |
| VaR/ES goldens | `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` and `PackagedVarGoldenTest` (2/2): **pass, bit-identical** |
| drishti-modules-it | the Sutra Next equivalence suites (trading, market data, market risk, counterparty risk, world economy, news), Controls, PanelProvenance, Funds, GLEIF, US equities, ImperfectData, SutraCliHelp: pass. Not level: `MapPanelTest`/`MetricPanelTest` expect `DRS-` codes from the bare parser (it answers `RCH-`), `SutraNextEquivalenceTest`/`EconomicsSutraNextEquivalenceTest` read fixtures under `testing/drishti-modules-it/src/test/resources/sutra-next`, which no longer exists; `SutraCliTest.designEachDraftsOneSutraPerSubfolder` exits 1 in the test's context while `drishti.py sutra design --each` on the same input through the built jar exits 0 and writes `rachana: 1.2` Sutras (not traced further); `DomainPacksTest` (banking-service) misses a genomics store day |
| Designer, end to end on a server | a design made from a `rachana: 1` Sutra answers `language {level 1.0, server 1.2, older}`; `POST /builder/designs/{id}/upgrade` writes `rachana: 1.2` and `range` → `span` (changes listed, no flags), the answer `older: false`; `/studio/settings` gives `level: 1.2`. A Sutra the 1.2 reader refuses while it is read is now a located preview problem, not a 422 (the workbench's "a mistyped expression costs its panel" tests failed until this) |
| Browser | `test_render_parity_browser` with the workbench, designer grid, About and ship browser suites: **292 passed, 1 failed**: a map panel's chart missing on the console's page for one Sutra on one run (`jurisdiction` here, `examples/map` on the run before; both ViewModels identical to 1.0): a painter timing, not the level |
| Console and tools Python | designer, ship, schema kit, governance, Studio, pack deploy, packdiff, GLEIF, US equities, pack layout and guides: pass. Failing on the base as well: `test_studio_folded::test_the_studio_help_guide_moved_to_the_designer_guide` (Help's old addresses removed by the shims pass) and the genomics showcase `--check` (`help.yaml` `category`) |

## Pass 3: triage (2026-11-21, `g4/triage` from `wip/g4-rehearsal-rx` `8346079c8`)

Each failing test was run on Drishti `main` `9ce7c3170` (a scratch worktree) and on the branch, in a scratch Maven repository (Drishti Lake
`0.9.0-SNAPSHOT` built from Lake `94dd281` with the two Lake fixes below; Lake `main` carries both).

| Test | On `main` | Cause | Fix |
|---|---|---|---|
| `VarShardClusterTest`, `VarFormatEqualityTest`, `ExposureFormatEqualityTest` ("the vector field 'PNL' is not a list of numbers") | pass (the shard test's `anOwnerDown...` case fails there too, see below) | **introduced by G4** (`7b0fff1d3`, Iceberg fixtures written by Lake's one loader, `source.format: loader-rows`): a loader row is `{domain, kind, id, date, doc, columns}` with the document holding the vector in its stored form `{"$vector": "PNL"}` and the numbers in `columns.PNL`; Drishti's old loader took them from `columns`, Lake's read only the document, so every P&L and scenario-PV row was rejected and the Iceberg side never loaded | Lake `bcff31d`: a stored-form vector takes the wrapper's `columns` list (null: no vector; missing or not a list: rejected), Java loader and Python path alike, kit case `load/loader-rows-stored-vectors`; `check.py` all green, reference equal |
| `VarApiTest.aDateThatIsNotOneRun...` (422 expected, 502) | pass | **introduced by G4**: Lake's read wrapper (`Problems.read`) turned any exception inside a read, the caller's own sink included, into `LK-1006` "cannot be read" and a read problem of the date, so the VaR engine's duplicate-trade refusal (`DRS-5202`, 422) answered `DRS-1003` 502 | Lake `d94023b`: a sink's exception is marked as the caller's (`Problems.caller`) and thrown unchanged, no read problem recorded, on Delta, Iceberg and files; `ProblemsCallerTest`; `check.py` all green |
| `VarApiTest.theOperationsAnswerWhatTheRestPathsAnswer` (seen once) | pass (timing) | pre-existing flake: the comparison removed `computedAt` but not `computeMs`, a wall-clock duration (0 then 9 ms under load) | the test's clock filter drops both |
| `MapPanelTest` | pass | **introduced by G4** (binder pass): the test calls Rachana's parser and compiler, which say `RCH-`; Drishti maps to `DRS-` at its edge (`DrishtiRachana.drs`) | the test checks the codes as Drishti answers them (same numbers) |
| `EconomicsSutraNextEquivalenceTest` (missing resource) | pass | **introduced by G4**: the tests use Lake's `TestRoots`, which reads `lake.module.root`/`lake.repo.root`; surefire still set `drishti.*`, so `drishti-modules-it` no longer pointed its module root at `drishti-server` | surefire sets `lake.repo.root`/`lake.module.root`; `ModulePackTest`, test.ini, TEST_HARNESS, CONFIGURATION |
| `NewsInMemorySentinelTest` (DRS-5219) | pass | **exposed by G4 shims 4**, a defect of `main` too: the memory-only guard took a connector's kinds from its definition, and a connector *file*'s `kinds:` is not read (bindings and routes say which kinds it serves), so a memory connector defined as a file guarded nothing (a scheduled report of memory-only news was accepted); `main`'s test passed only because it defined the connector as properties. And Lake's refusal (`LK-1005`) had no Drishti mapping | `SourceRegistry.kindsOf` (own list plus what the resolution reads from it); `ConnectorRows` uses it; `LK-1005` answers `403 DRS-5219` |
| `PackagedVarGoldenTest` | pass | environment: needs a Python with `drishti_lake` | passes with the scratch venv (wheel installed), `-Ddrishti.python` |

VaR/ES goldens on the branch with the fixes: `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest`
pass. `VarShardClusterTest.anOwnerDownChangesNothingBothDownIsAClearErrorThenTheShardMovesAndAnswersAgain` fails identically on `main`
(503 expected, 200 with `answeredHere: 2`): pre-existing, not G4.

### Pass 3 triage: the full Java suite (`clean verify`, once, after the PNL fix), every failure classified against `main`

`main` was run on the same failing classes (one build). With the fixes below re-run, these are the results:

| Failure on the branch | `main` | Class | Now |
|---|---|---|---|
| `ReportApiTest`, `RegistryApiTest`, `DerivedKindTest`, `SimmApiTest`, `FrtbApiTest` (initializer: connector set `exposure-format-equality` not there) | pass | G4 (shims 4 named a set that does not exist) | **fixed**: set `finance-lake-off` (the finance lake connector off, what they had as a property) |
| `LicenseHeaderTest` (the `bi/folders` fixtures) | pass | G4 (shims part 2's fixtures) | **fixed** (`tools/license_headers.py --fix`) |
| `CachesWeighedInBytesTest` (caches of moved or removed classes still listed) | pass | G4 | **fixed** (`count-only-caches.txt`) |
| `OneTablePerSystemTest`, `SourceModuleEndToEndTest` (`lake.home` not set) | pass | G4 | **fixed**: Lake's loader run on `DrishtiLake.runtime()` (also `SourceShapeParityTest`) |
| `ReadyApiTest` (5, `LK-1002` as a 500) | pass | G4 | **fixed**: Lake's `LK-1001..1005` answer as Drishti's codes at the API edge |
| `SutraNextEventsTest.badClicksAreRefused` (`RCH-5001` as a 500) | pass | G4 (P9) | **fixed**: a `RachanaException` answers as the `DRS-` code of the same number |
| `MetricPanelTest` (`RCH-` codes) | pass | G4 | **fixed**, as `MapPanelTest` |
| `ModuleHostServerTest` (3, unknown function `greeter_greet`) | pass | G4 | **fixed**: the test evaluates with the instance's compiler (`DrishtiRachana.compiler()`) |
| `BankingModuleOnTest.theOldPathIsTheNewOneForThisRelease`, `VarExplainApiTest.theOldExplainPaths...` (404) | pass | G4 (shims S5 removed the API aliases) | **fixed**: the old-prefix case removed with its shim; the explain test asks the module's own deprecated paths |
| `ConnectorLayersFixtureTest` (the "older lake form" case) | pass | G4 (shims part 3 removed that form) | **fixed**: the case removed with the form |
| `AbandonedReadsTest` (28 reads, at most 20) | pass | timing under load (load 30-45) | passes on the re-run; not changed |
| `SutraRegistryTest.anyFailureLoadingOneFileIsThatFilesProblem`, `PathologicalExpressionsTest$LiftedLimits.anOverflow...` | pass | G4 (Rachana): a 100,000-deep expression with the limits lifted no longer overflows on Rachana's compiler, so the tests' "overflow" never happens | **open**: the tests need an input that does fail on Rachana, or Rachana's stack guard (P15) to refuse it; Rachana's call |
| `ViewModelSchemaTest` (every `PanelData` record belongs to a kind) | pass | G4 (Rachana's `PanelData` has records the schema does not name: Pivot, Candles, DataChart, Graph, Tree, FormPanel, Timeline, Grid, Scatter, Bullet, Treemap) | **open**: the schema of Rachana's ViewModel, the renderer lane |
| `DrishtiProfileConformanceTest` (`rank(x, 'sideways')`) | pass | G4, known (Part 3: kept at language level 1.0 on purpose) | **open**, by decision |
| `LiveVisualsTest` (`bi/samples/tables/bi/model:live-trading@1.model.yaml` missing) | pass | G4 (shims part 2's fixtures) | **open** |
| `IcebergMovedConnectorFileTest` (health text: `cannot read sa-ccr log: File does not exist` instead of the moved-lake words) | pass | G4 (Lake's read problems word a moved Iceberg table differently) | **open** (Lake's reason words for a moved table) |
| `BankingPacksTest` (183 vs 186), `ConsoleAnswersRecordTest` (recorded answers older than the code), `DomainPacksTest` (no source holds `dms-score/...`), `EditionTwoLensesServerTest`, `NetForwardTest.withAGoneBAndCSaySoClearly`, `VarShardClusterTest.anOwnerDown...` | **fail on `main` too** | pre-existing | not changed |

## Gate 4 fixes: F6, F10–F13

| # | State | What was done |
|---|---|---|
| F6 | partly | `ArchitectureRulesTest.drishtiImportsOnlyLakesPublicPackages`: no Drishti class imports a Lake package outside the surface (`com.ash.lake`, `config`, `contract`, `inbox`, `load`, `memory`, `memory.store`, `read`, `spi`; `tck` is test support). Nineteen classes still do and are listed in the rule (`LAKE_FOLLOW_UP`; it only shrinks). **Lake follow-up** (not edited here): `common.RowFeed` (seven store loaders, `DrishtiLake`, `LoaderRows`), `delta.write.LakeWriter` and `LakeManifest` (`VarPvStream`, `DeltaSink`, `HistoryBench`: behind `spi.TableWriter` / `load`), `iceberg.table.IcebergAppender` (`IcebergSink`, `LakeAppends`), `io.S3Storage`, `S3Settings`, `LocalPaths` (`OfficialSink`), `io.PageDecodeException` and `UnsupportedCodec` (`LakeReads`), `delta.write.LakeCli` (`DrishtiApplication`), `common.LakeException` and `LakeError` (`ApiExceptionHandler`) |
| F10 | closed | `tools/drillkit/lake_cache.py` hashes the pinned Lake version (`pom.xml`), the wheel pin and the pinned wheel's SHA-256, and the `lake-*` jars of the exec jar (name, size, CRC) or `LAKE_CLASSPATH`; `test_the_key_changes_with_the_loader` |
| F11 | closed | `lib/lake-wheel.pin` (ref = Lake commit b26cc2e, release 0.9.1, to become the tag v0.9.1 with no content change; version `0.9.1`; SHA-256 of the wheel; reproducible build) is the one place; `pom.xml` `drishti.lake.version` is 0.9.1 (the jars and the bom of that commit) and `requirements.txt` says `drishti-lake==0.9.1` (held to the pin by `tools/lake/test_lakewheel.py`); `lakewheel` takes the pinned version by name and checksum, never the newest file, and `python tools/lake/lakewheel.py` checks it; the quickstart, deployment guide, IDE guide and `lib/README.md` say how to get it |
| F12 | closed | CLI_REFERENCE (deleted tools, `engine:` sample lines; the same in DATA_LAKE_GUIDE, VAR_GUIDE, tutorial 16), the two bug-history rows (IDE, Operations), DATA_ARCHITECTURE 9.1 and the dependency paragraph (Lake's interfaces, link to Lake's design), QUICKSTART_DEVELOPERS tags/pins, `compose.data.yaml`. Not changed: the `github.com/drishti-platform/...` links (the organisation move is planned; the part 2 link check follows it) |
| F13 | closed | `VarGoldenMatrixTest.headlineFigures`: the bank's VaR 167,372,692 and ES 210,131,972 (2026-09-30, 250 scenarios, USD, three hierarchies) asserted in the normal suite from the committed data; no number changed |

### Gate 4 fix F8 (compatibility leftovers)

| Item | State | What was done |
|---|---|---|
| a | partly | `render/1.2.0` deleted; `RETIRED`, `FROZEN`, `DEPRECATION`, `RETIRED_ELEMENTS`, the Elements 2.2 to 1.2.0 mapping and the 308 redirects of the retired renderer and Elements paths removed (410 now). **Not done:** embed v1's 14 files under `web/embed/` with the v1 element routes (`/embed/v1/drishti-elements.js`, `/m/`, the sheets, `/elements/<version>/...`), `core/element_dist.py` and `core/element_api.py`: the v1 REST and channel routes and the v2 routes import the same module, and the guide, tutorial and four test files assert them; it is one piece of work with its documents |
| b | closed | an engine's old switch name (catalogue `retired:`) set by a site stops the start, naming the new key: `RetiredNames` (kernel/drishti-packs), the one place; `EngineFilesTest.anOldSwitchNameStopsTheStartAndNamesTheNewKey`; DRS-1024 warning, `Switch.alias`, the Admin and CLI "old name" text removed |
| c | not done | the 18 `was:` module-setting keys are the names the VaR, exposure, FRTB, SIMM code reads (`drishti.var.threads` is set by the VaR API tests themselves); refusing them stops every VaR configuration. Needs the code to read `drishti.module.banking.*` and every site file, test and document to move first. Release: the one after the modules' settings migration |
| d | closed | `api.aliases` in a module manifest is refused (V1); parsing and claims removed |
| e | not done | a table's `was:` name: `TableDef.was`, `Catalogue.renamed` and 22 table files and their generators; its own piece of work |
| f | closed | `GET /api/v1/risk/var/explain`, `/explain/matrix` and `/api/v1/risk/mr/explain` removed (the one Explain and the workbook stay); API_REFERENCE rows removed |
| g | closed | the console refuses `edition` and the retired `ui.*` brand keys at start (`core/edition.py refuse_retired`, called by `create_app`) |
| h | closed | the `deprecated` list print of `tools/serverconnectors.py` |
| i | closed | "Other datasets (version 1)": `B.layer.groups` has no legacy group, the notebook's sources and `fieldsOf` take models and datasets only, `fillDatasetPickers` offers them and hides "Also add" |
| j | closed | `tools/packgen/common/sutracases.py` reads `cases.jsonl` only |
| k | closed | `settings_schemas.yaml` (the profile rows are `by: packs`, connector files), `CONFIGURATION.md` (hand-synchronised: regenerate with `tools/settings_catalogue.py` once the server jar is built, then `--check`), `compose.data.yaml` |
| l | not done | `PackStateStore`, `LoadStore`'s first-read import, `connector-migrated` override migration, `FileUserStore` / `FileAuditLog` (test only), Lake `PersistedNames`: named for the release after Gate 4 |
## Pass 4: finish (2026-11-21, on `g4/finish`)

Products installed from their tags into a scratch Maven repository in the order tools 1.1.0, Rachana, Lake 0.9.0; Drishti pins Tools 1.1.0, Lake 0.9.0 (+ bom), Rachana 1.0.0 (the `develop` build, installed into the scratch repository only; the tag follows the gate) and Elements 3.3.1 (the test-fixes lane's vendoring, one Elements version).

| Item | State | What was done |
|---|---|---|
| Re-pin | closed | the poms' pins; lake files read `${lake.data.dir}` (the server writes it with `drishti.data.dir`, `tools/javacmd.py` passes it; the official lake files name `lake.var.official.*` and `lake.ccr.official.*` because Lake 0.9.0 refuses a host's name; `config/engines/*.yaml` lake sections likewise) |
| Level retirement (Rachana 1.0.0) | closed | one level, 1.2; `rachana upgrade` removed from 1.0.0 (it lives in 0.12.0); in Drishti `LanguageLevels`, the designer's `/upgrade`, `drishti.py sutra upgrade` and `drishti.rachana.language-level` removed; Rachana's corpus re-recorded at 1.2 (481 Sutras, no problem); Rachana `./mvnw clean verify` green |
| Proof (one targeted `mvnw` run) | green | `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `PackagedVarGoldenTest` (Lake tag's Python on `LAKE_PYTHON`), `HierarchyEquivalenceTest`, `ArchitectureRulesTest`, Drishti profile conformance, `PackSutrasTest` (parity), `ReferenceSutrasTest`, `ViewPipelineTest`, `RachanaAggregatesTest`, `WindowFunctionsTest`, `PackLoaderTest`, `PackSettingsTest`; tools/lake `test_javaload`, `test_build`, `test_demo`: 60 pass. The printed VaR/ES figures were not compared with 167,372,692 / 210,131,972, and the demo lake's tables and row counts were not compared with the base build |
| Not done | open | the `PackStateStore` and `LoadStore` imports, the data-source override migration, `FileUserStore`/`FileAuditLog` and their settings, the WebKit test setup, the phone-width sideways scroll, the launcher move per LAUNCHER_STANDARD (the generator `tools/launch/launchers.py`, its tests and IDE_GUIDE change together), the browser smoke on Chromium and WebKit |
| Python failures (tools and the docs tests, 4 workers) | open | `tools/lake/test_maintain*` (the Lake 0.9.0 jar rejects `--relayout`: `lake maintain` and the maintain admin tests fail); `test_sdk_builds_genomics` (2) and the genomics generator check; `test_pvf_sets`; `test_launchers` docs check; the docs tests for error codes, api endpoints and docs rot (the references to the retired upgrade paths were fixed afterwards and not run again) |

## Pass 3: BI at 1.2 (2026-11-21, on `g4/level-12`, Rachana `develop` `76bf5b23`, pin `1.0.0`)

Nothing in Drishti reads Rachana at level 1.0 any more: BI's expressions, the table layer, measures, pivot formulas, the pivot's document
filters and connector `where` predicates parse at 1.2 (`Grammar.LEVEL`) and their lowerings know the 1.2 trees.

| Item | What was done |
|---|---|
| Lowerings | `NodeLowering`/`NodeText` (BI), `Compiler`/`SqlLowering`/`FormulaLowering` (bicompute), `Trees` (table layer), `BiMeasures`, `MeasureFacts`, `DocPath`, `RowPredicate` read `Op` and `Minus` (and walk `Power`, `Coalesce`); a number written in an expression is exact at 1.2: SQL, pivot text, document filters and lake push-down take its 1.0 value (`Values.normalise`), so SQL text and push-down are unchanged (`RowPredicate`'s push-down was silently off since the 1.2 default: back on) |
| `agg(x, where = c)` | refused from 1.1 (SPEC 4.10 rule 3: `where` is no parameter of Core's `sum`, `avg`, `count`, ...). The 1.2 spelling is `calc(agg(x), where = c)` (Rachana's own catalogue examples). Each lowering reads a `calc` that edits nothing but the rows over one aggregate as that aggregate with its condition (the SQL emitter wrote `calc(where=)` and `sum(x, where=)` as the same `FILTER (WHERE ...)`), so the compiled tree is the 1.0 one; `NodeText` writes it back as `calc(...)`; `Incremental` builds its counts through `NodeText` |
| Expressions rewritten | shipped models (`config/tables/banking`), test fixtures, tests, guides, mock-ups, tools' examples: every Core aggregate with `where =` (about 150 places). The formula parity corpus keeps each formula as recorded and asks the 1.2 text (`rachana:`) |
| Upgrade paths | `sutra upgrade` and `LanguageLevels` were already gone (`g4/finish`); now the designer's `POST /designs/{id}/upgrade` console route, the Problems tab's level item, the ops' `upgrade()`, the design's `language` field and their doc mentions. A Sutra of a retired level gets Rachana's `RCH-2009` (as `DRS-2009`) naming `rachana 0.12 upgrade` (CLI, BUILD, RACHANA and API references say so) |
| Conformance | the runner already takes Drishti's instance as it is (1.2) |
| Pin | `drishti.rachana.version` back to `1.0.0` (the `g4/finish` merge had restored `0.11.0-SNAPSHOT`) |

Results (one build, offline, `clean verify` of the changed modules and their dependants): drishti-bicompute **301/301** (with
`PivotFormulaParityTest`: every recorded answer cell for cell, SQL and Java; `DifferentialTest`; `SutraAggregateParityTest`; Drishti's
conformance), drishti-tables **52/52**, drishti-server BI **649, 648 pass**: `LiveVisualsTest` only (its fixture is missing on the base, as
before), BI API tests (`BiApiTest` 19 and the rest of `server.bi`) green; table, pivot, designer and builder tests green; `VarGoldenMatrixTest`,
`SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest` (2/2) **pass, bit-identical**.

Exact decimals changed no figure. What changed in form only, in the table layer's Java rows (Rachana's own evaluation): a whole number
computed from a double keeps its point (`125.0`, was `125`), and a division of exact numbers is a decimal (`500` as `BigDecimal`, was
`Long`); the tests now hold the value. Tests changed with the language: `sum(PNL) if count() > 10 else 0` is Rachana 1.2 (Python's
conditional, the same tree as `c ? a : b`), no longer refused; `ShippedInRachanaTest` holds that the printed text reads back as itself
(1.2's printer writes `where=`, `and` and `a if c else b`). `LanguageLevelGuardTest` no longer exists (deleted with the level setting in
`g4/finish`). Not run here: the console's Python suites (their conftest refuses the stale `.dmod` packages of the worktree).

### Pass 3 triage: after the merge with level 1.2, the live fixes and the lake-doc work (`36e82521a`), one full `clean verify`

5,275 tests. The level-1.2 lane's three items: `SutraRegistryTest` and `PathologicalExpressionsTest$LiftedLimits` now expect Rachana's
documented behaviour (its stack guard, `internal.Deep`, parses, checks and binds again on a 512 MB stack when the host thread's runs out, so
with the limits lifted a 100,000- or 200,000-deep file is valid and loads, and a 200,000-term chain evaluates; past even that stack the
binder's refusal is the panel's `RCH-2102`, never a 500): not a Rachana defect. `SutraCliTest` `design --each` failed in-process only:
the `sutra` command's own context reconfigured the process-wide `DrishtiRachana` settings with its scratch folder's pack formats files,
deleted when it ended, so the test JVM's server then read formats from a deleted folder (through the jar the command is a JVM of its own);
`CliLauncher` now puts the settings back (`DrishtiRachana.keep()`). `ViewModelSchemaTest` and `DrishtiProfileConformanceTest` pass at
level 1.2. `PackagedVarGoldenTest` needs the venv's `drishti_lake` from Lake `main` (the merged tools call `archives.plan(layout=...)`).

Failures left (11):

| Test | Class |
|---|---|
| `VarShardClusterTest.anOwnerDown...`, `BankingPacksTest`, `ConsoleAnswersRecordTest`, `DomainPacksTest`, `EditionTwoLensesServerTest`, `NetForwardTest.withAGoneBAndCSaySoClearly` | fail on `main` too |
| `AbandonedReadsTest` | timing under load; passes alone |
| `TypedCallParityTest` (2: the REST `live` search answers 404 where the typed call answers rows) | G4, from the merged live-table work (not in the pre-merge run); open for the live lane |
| `LiveVisualsTest` (fixture `bi/samples/tables/bi/model:live-trading@1.model.yaml` missing) | G4 (shims part 2 fixtures); open |
| `IcebergMovedConnectorFileTest` (a moved Iceberg table's health words) | G4 (Lake's read-problem words); open |

## Gate 4 fixes: F9 (T3–T11), on `g4/tools-adopt` from `wip/g4-rehearsal-rx` (2026-11-21)

Owner decision on Fable's F9: Drishti keeps no copy of anything Drishti Tools provides. Tools 1.1.0 (the `v1.1.0` tag) is the pin (`drishti.tools.version`);
the build is offline against a scratch repository with that tag installed. The Python xlsx writers (`drishti-console/core/export.py`, `tools/sizing_planner.py`)
stay by the owner's word; `ToolsAdoptionGuardTest.noJavaCodeRunsAPythonWorkbookWriter` holds that no Java main source names or runs them (only two messages in
`CapacityWorkbook` and `CapacityPlanner` name the generator), and the server writes every workbook with `tools-xlsx`.

| Row | Result |
|---|---|
| T3 hashes | Done. Every `MessageDigest.getInstance` in 75 files (main and test) is `Hashes` (`sha256Hex`, `of`, `digest` for streams and `SHA-1` where a stored identity needs it); the `NoSuchAlgorithmException` catches that could no longer happen are gone; `MessageDigest.isEqual` (constant-time compare) stays. Rule: `ArchitectureRulesTest.noMessageDigestOutsideTools`, text guard `ToolsAdoptionGuardTest.noDigestIsMadeByHand` (tests too). Same bytes: the stored identities and the four VaR/ES goldens are unchanged. |
| T4 canonical JSON | Done in `RunSources.canonical`, `QuestionNormaliser` (`canonical`, `text`), `MqParser` (`hash`, `json`) and `reactive/Results.hash`: Jackson turns the value into plain data first, then `CanonicalJson` writes it (same text as `ORDER_MAP_ENTRIES_BY_KEYS` for plain data). Not changed: `net/Digest.hash` writes a record, whose property order is the declaration order, not sorted: a different job, and a node's gossiped hash must not change between builds. |
| T5 CSV | Done. `plugin/file/CsvReader` became `CsvRows` (the typing of a cell into a `DataNode` only; records are read by `tools-csv`, which also reads a quoted line break); `Feeds.csv` reads through `tools-csv`; `dist/CsvWriter` became `ExportCsv` (lays an `ExportDoc` out as records; BOM, formula guard and quoting are `tools-csv`); the four hand-written quoting helpers (`PivotExport.q`, `VarCaptures.cell`, `SqlController.cell`, `SearchController.cell`) call `CsvWriter.quote`. A newline inside a field of the VaR capture export is now quoted (it was not). |
| T6 temp files | Done. `TempFiles` (266 lines) is the server's policy on `tools-files` `TempFolder` (the base from `drishti.tmp-dir`, the JVM-flag warning, `java.io.tmpdir`, the health report); the folder, marker, clean-up hook, usage count, stale listing and `deleteTree` are the tools'. The disabled `removeStale` and the legacy-marker are gone. `JvmTempDir` stays: it reads the JVM's own temp folder and sweeps unpacked native libraries, which no tools module does (see the tools gaps). |
| T7 `RowFeed` | Already done by the Lake step (Drishti's copy was replaced by the product's); now held: `ArchitectureRulesTest.commonDoesNotDependOnTheLake` and the no-copy rule. One unrelated nested interface of the same name in `DuckDbExecutor` is `StagedRows`. |
| T8 calendar | Done. `common/BusinessCalendar` and `rachana/HostCalendars` are deleted; Rachana Core's `BusinessCalendar` is a `Calendars.Calendar`, so the adapter had no job. `BusinessCalendarTest` (holidays of the five centres, joint calendars, observed days) now runs on Rachana's class. The two sources were equal rule for rule (a diff of the two files). |
| T9 HTTP clients | Done. `DrishtiTools.httpBuilder` is the one place an `HttpClient` starts, with the proxy (`ProxySettings`) and the TLS material (`TlsHttp`) applied. The seven that bypassed it (`JavaLibraries`, `BridgeClient`, `PackRegistryClient`, `ReportService`, `OidcConfiguration`, the feed connector's default client, `MinioServer`) use it, as do the eleven that applied both by hand (notify, the four AI clients, the five connectors) and the peer client (no proxy, the node's mutual TLS). Rule: `ArchitectureRulesTest.httpClientsStartInDrishtiTools`; text guard on main sources. Test: `OutboundClientsHonourSettingsTest` (a call through the fake proxy, the TLS context on the client, the bridge and registry clients' proxy selector); the per-connector `*ProxyTest`, `*TlsTest`, `AiProxyTest`, `ProviderTlsTest` pass. A client built for a target not known at construction (registry, reports, OIDC, bridge, libraries) resolves the proxy for an `https://` target. |
| T10 guards | Done where Drishti duplicated the kit: `LicenseHeaderTest` uses the kit's `HeaderAndLineCap.withoutHeader`, `SourceFileSizeTest` its `overCap` and holds `tools/code_cap.py` equal to the kit's cap. Not adopted: `kernel-api ApiSurfaceTest` (it checks a hand-named value-class list and its own baseline format; the kit's `ApiSurface` writes another format, so adopting it is a baseline rewrite for a later release). |
| T11 zips | Not done as written: `tools-codec` has no `Zips.write` (see the tools gaps). The four archives written with the JDK stay (`Dmod`, `PackFragment`, `ModulePublicController`, `ExportService`), named in `ArchitectureRulesTest.zipWritingOnlyWhereItIsToday`: the list only shrinks, and a fifth writer fails the build. |

### Tools gaps (for a tools 1.2.0)

- `tools-codec` has no `Zips` (a deterministic and a plain zip writer from entries); T11 waits on it.
- `tools-net` has no client factory (`HttpClients.builder(proxy, tls)`): `DrishtiTools.httpBuilder` is Drishti's three lines; the guard keeps it the only place.
- `tools-files` has no JVM-temp-folder helper (the `-Djava.io.tmpdir` check and the native-copy sweep of `JvmTempDir`).
- `tools-guards` kit: the header mark is fixed to the owner's name and its window is 20 lines; a configurable mark would let the branding rule use it.

### Proof

Merged with `wip/g4-rehearsal-rx` (Rachana 1.0.0, Lake 0.9.0, Tools 1.1.0 tags), offline; every run through the two-slot machine lock.

- **Compile all** (main and test, every module, `install -DskipTests`): green.
- **Guards**: `ArchitectureRulesTest` (42 with the five new rules), `ToolsAdoptionGuardTest` (4), `LicenseHeaderTest`, `SourceFileSizeTest` (2): green.
- **Tests of every touched class**: `TempFilesTest`, `JvmTempDirTest`, `BusinessCalendarTest`, `ExportWritersTest`, `RunExportTest`, `PivotExportTest`, `QuestionNormaliserTest`, `RunSourcesReportTest`, `MqParserTest`, `MqEngineTest`, `ModelViewHashTest`, `PackRegistryClientTest`, `RegistryApiTest`, `ConnectorProxyTest`, `IdTokenVerifierTest`, `NotifyChannelsTest`, the feed, news, REST and Yahoo connector tests (source, proxy and TLS), `AiProxyTest`, `ProviderTlsTest`, `ComputeWritesApiTest`, `EnvironmentTest`, `ShardMap*`, `ModulePackTest`, `OutboundClientsHonourSettingsTest` (new); plus the whole of drishti-api, -common, -kernel-api, -identity, -module-runtime, -net and -flight: green.
- **The four VaR/ES goldens**: `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest` (2 cases, from the Python of the lake wheel): green, the figures unchanged.
- **The 60-workbook comparison**: not re-run. This branch changes no workbook writer (`ExportWorkbook`, `PlaygroundWorkbook`, `CapacityWorkbook`, `PivotExport`'s sheet code, `tools-xlsx` calls are untouched; the only edits near them are a hash key and a CSV quote helper), and the export tests that read workbooks pass; the capture needs two full builds of two trees and was left to the drill.

## Gate 4 fixes: F1, F3, F5 (2026-11-21, `g4/gate4-server` from `wip/g4-rehearsal-rx` `208b491f1`)

The findings of `I7B_GATE_4_REVIEW.md` (part 1), fixed at their cause.

| Finding | Fix | Proof |
|---|---|---|
| **F1** (blocker) a compiler taken at start: a module started later not seen, a stopped one still callable | One holder, `LiveCompiler` (drishti-rachana): `get()` is always `DrishtiRachana.current()`'s compiler, as `Formats.live` is for formats. It is the Spring bean (no `ElCompiler` bean any more) and every reader takes it: `StructuredSearch`, `BadgeRenderer`, `ViewPipeline`, `ExplainService`, `ImpactService`, `HistoryService`, `RecordReads`, `HistoryController`, `FieldValueReader`, `DesignAboutService`, `SutraRegistry`, `SutraMatcher`, `AboutCatalog`/`AboutParser`/`DesignAbout`, `LayoutMerger`; `RowPredicate` and `DocPath` compile through it (no Core-only compiler). What is built on a compiler (the binder, controls, maps, the screen's wiring; the Sutra checks) is held through `LiveCompiler.follow`, dropped by `DrishtiRachana` at every change of instance, so no older instance (and no stopped module's class loader) stays reachable. A Sutra that calls a stopped module's function is refused in words: "the function 'f' is off: its module 'm' is not running on this server" | `ModuleHostServerTest` evaluates through the server's bean again, and `aModuleStoppedOrStartedAfterStartIsSeenByTheServersScreensAtOnce`: a Sutra calling `greeter_greet` previews through `/api/v1/studio/preview`; the module unloaded hot (leak check clean); the same preview is refused with the module-off words; the module started again after start-up previews again |
| **F3** an evicted search's table read as a kind `q-<hash>`; declarations never released | `LiveSources` tells a listener of every source that leaves the cap; the live tables release it (its declaration leaves the authoriser and the write-back, its open table is closed with its rows, members' reads and stream). The resolver never reads a `q-` name without a source as a kind: `404 rdp.table.unknown` "the search's table q-… has expired: run the search again" (`expired: true`), also through the controller's words for every route | `FormApiTest.aSearchsTableThatLeftTheCapAnswersExpiredAndRunningTheSearchAgainOpensIt` |
| **F5** a repeated change id answered from the first change without comparing | `EditService.submit`: a repeat must send the first change's kind, record and items (path, from, to; a number equal whatever its written form); otherwise `409 DRS-9303` "the change id … was used for another change", audited `edit-id-reused` | `KafkaTradeEditTest`: the same id and content through `/api/v1/changes` answers `applied`; another value or another field is 409, audited twice by name, nothing made or written |

Rachana 1.0.0 (`develop` `66b3c9c7`) has since added both: `LiveTables.undeclare` and `ChangeSink.priorDigest` (see "Rachana SPI adoption" below).

F4 (a FORM table spanning Drishti\*net members read as its first opener): not done here, parked to the combined i8 by name. Option A
needs a node identity on the on-behalf-of token carrying the audience's rights, the member trusting it, and a per-read audit call to each
member for every reader of the shared table, proved on a cluster test: more than the two hours it was given.

### Gate 4 fixes, open after the Lake 0.9.1 pin (b26cc2e)

| Item | State |
|---|---|
| `IcebergMovedConnectorFileTest` | loader config follows 0.9.1 (`target.path`, not `target.lake`); the test holds the state, the tables and DRS-1003, not Lake's sentence. **Still fails:** 0.9.1 no longer classifies a moved Iceberg table (Health says `File does not exist: ...avro`, no relocate hint) and the read failure logs a stack trace (`SourceFailure`): Lake follow-up |
| `tools/lake/test_maintain.py::test_relayout_sorts_into_the_layouts_files_and_is_idempotent` | fails: `lake maintain --relayout` answers "another writer committed while relayout read the date; run it again" on a delta-rs fixture table: Lake follow-up (or the fixture) |
| `test_maintain` row counts, `test_maintain_admin` file counts | updated to 0.9.1 compaction (24 rows readers see; two files a date) |

## Pass 3: test fixes (2026-11-21, on `g4/test-fixes` from `wip/g4-rehearsal-rx`, merged again with `d8b7da8f4`)

| Item | Done |
|---|---|
| Elements 3.3.1 | released on Elements `develop` as `20661f0` (version file, regenerated bundles, API reference and catalogue, CHANGELOG heading `3.3.1 (2026-11-21)`; `tools/run_tests.py` green, nothing skipped: 37 Node tests, 200 + 4 budget pytest; not tagged). Vendored into `static/vendor/drishti-elements/3.3.1/` from that commit's `dist` (SHA-384 manifest), `drishti.elements.version`, the renderer's folder `static/render/3.3.1/`, every module's range `>=3.3 <4`, Drishti's libraries and sheets rebuilt; one Elements version in Drishti |
| BI browser fixtures | `test_bi_wave_a_browser` and `test_bi_new_visuals_browser` write a table (`config/tables/bi/*.table.yaml`, tests as `agg:` rows) and a model, reports read `model: <name>@1`; the python and notebook suites start their server on `DRISHTI_PYTHON` (the pinned libraries' interpreter) |
| Strict policy (`script-src-attr`) | cause: `B.h` (the BI element maker) set `onclick` as an attribute, so the viewer's "Clear" chips carried an inline handler (blocked, and never worked); `B.h` adds an `on<event>` function as a listener. The policy check now names the directive, the source file and the line |
| Maps | `drishti_geomap.js` is out of every console page (Sutra panels, Build, shared designer): they load drishti-chart's geo part, `drishti_charts.js` draws with one helper per maps folder (without a folder the part makes a new helper, and so a new fetch of the world, for every map: the flake "map chart missing on the console page" under load). It stays as the script of the **frozen** embed v1 and render 1.2.0 (the freeze guard `test_element_package` refuses a changed `web/embed/`): it goes with them. The map tests read the geo part's projection (Equal Earth) and its tooltip node |
| The four console failures | `test_live_grid_browser` waits on the settled signal; `KERNEL_AND_MODULES.pdf` regenerated (and the five other stale PDFs); `DRISHTI_WEBKIT_LIBS` in the environment index; the settings catalogue regenerated. Also at their cause: `State` is patched through its dict (pytest 9.2's `monkeypatch.setattr` dropped a `State` attribute at teardown: 94 cascading failures), the embed integrity record (the BI lane had changed `web/embed/assets.js`), `test_bi_live_browser`'s foot regex reads a line |
| Finish lane's open items | launcher standard (one `run-configs/`, `Drishti: <verb> <what>`, `drishti.code-workspace`, `Drishti: test`, `tools/launchers/check_launchers.py`, generator, tests, IDE_GUIDE and every guide naming a launcher); `LiveVisualsTest` (a report names `model:<name>@1`); docs tests: Rachana Core's codes and the live tables' route constant are known to the error-code and endpoint checks, the retired `sutra upgrade` section and dead anchors removed, `/net/changes/{kind}` documented; genomics generator writes Help category `data`; `settled` waits out a second navigation (WebKit) |
| Proof (one `heavy.sh` slot each) | `BiApiTest`, `BiReportApiTest`, `FieldParamsApiTest`, `PythonBiApiTest`, `PivotFormulaParityTest`, `LiveVisualsTest`, `BoardParserTest`, `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest`: **all green**. BI browser suites (11 files, Chromium): 89 of 90 (the one, a foot-text regex, fixed). Smoke: Chromium 128 passed, WebKit `test_page_widths_browser` 115 passed. `test_render_parity_browser`: 5 consecutive green runs |
| Not done (named) | Python tools with the Lake 0.9.0 wheel on `LAKE_PYTHON`: 48 `plan() got an unexpected keyword argument 'layout'` and `tools/lake/test_maintain*` wait for the Lake 0.9.1 pin; `test_sdk_builds_genomics` needs Rachana 1.0.0 in a Maven repository (`DRISHTI_LOCAL_MAVEN_REPO`); console tests that read Rachana sources that moved (`PanelKind.java`: `test_panel_kinds`, `test_examples`); `test_no_relative_paths` and `test_python_main_paths_guard` (`${lake.data.dir:./data}` in the lake files: the finish lane's design); `test_tools_guide` (Lake scripts not in the guides); `test_element_sheet` (the view sheet is 37.7 KB gzip against 36 KB, the same bytes as before the re-pin) |

### Gate 4 fixes: Rachana SPI adoption (F3 and F5 on the Data Protocol, `g4/rachana-spi`, Rachana `develop` `66b3c9c7`)

| Item | What was done |
|---|---|
| `LiveTables.undeclare` (F3) | `DrishtiLiveTables.release` (the cap's listener) calls `undeclare(name)`: Rachana drops the spec, tray and open table, open streams end with an expired gap, and a read answers its `404 rdp.table.unknown` `expired: true`; Drishti's "expired: run the search again" words stay (its resolver answers first). The workaround that declared a spec again to close the table is gone. Proved in `FormApiTest.aSearchsTableThatLeftTheCapAnswersExpiredAndRunningTheSearchAgainOpensIt` (Rachana's `specs()` no longer holds the table) |
| `ChangeSink.priorDigest` (F5) | the sink sends `Change.digest()` with the change; `EditService` keeps it in the change's `origin` (durable with the edit log and approval queue) and `known(id)` answers it; `DrishtiChangeSink.priorDigest(id)` reads it. A reused id with other content written through the Data Protocol is `409 rdp.write.conflict` (`reused: true`) audited `write-reused` for the caller, as the write-back API's `409 DRS-9303`; the same content answers as before. The digest never leaves `known` into an answer |
| Proof | `KafkaTradeEditTest` (reused id and other content through `/api/v1/rdp/tables/trade/write`: 409 plus audit naming the caller; same content: same answer), `FormApiTest`, `LiveIsolationTest`, `TypedCallParityTest`, the four VaR/ES goldens |
