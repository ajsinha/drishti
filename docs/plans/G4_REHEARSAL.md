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
| Duplicates gone | Drishti's `RowFeed` (the product's, in the runtime's business zone, for every store loader); the hadoop engine's leftovers: the `engine`, `hadoop.*` and `lz4-via-native` settings, `DRISHTI_DELTA_ENGINE`, the launcher's `--engine`, the Windows workflow line, the delta-s3 example's row, the hadoop jars of `plugin-delta`. |
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
| R11 | Docs not rewritten: `CONFIGURATION.md` (regenerate), `DATA_LAKE_GUIDE`, `DATA_ARCHITECTURE`, `CLI_REFERENCE`, `OPERATIONS_GUIDE`, `DEVELOPER_GUIDE`, the plugin READMEs (the hadoop engine's places, old module and class names, `DRISHTI_LAKE_CLASSPATH`); `tools/packmake.py` and the bench scripts still name `DRISHTI_DELTA_ENGINE`; the capacity workbook's setting names | D7 | Drishti | M |
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
| R11 | closed | Every hadoop-engine mention and old module name in the guides, plugin READMEs, examples, deploy scripts and tools; the settings catalogue (`engine`, `hadoop.*`, `lz4-*` gone from the connectors; `LAKE_CLASSPATH` one variable for the tools); `CONFIGURATION.md` regenerated and checked; `DRISHTI_DELTA_ENGINE`, `DRISHTI_ICEBERG_CLASSPATH`, `DRISHTI_LAKE_CLASSPATH` gone. Left as they were: history in `IMPLEMENTATION_PLAN`, `BENCHMARK` results, the generated bench result files; Iceberg's `catalog: hadoop` is the path-based catalog's name and stays |
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
| Lake items found | open | (1) the date guard (`--future-days`, `--zone`) and the retention guard (`--max-drop-share`, `--force-drop`) the old Iceberg loader had are not in the one loader; `data load --store iceberg` takes neither (Delta never had them there). (2) JSON-array input in the loader (above). (3) the archive rule of `tools/lake/archives.py` (451 lines, with the `rows` layout `load_rows.py` reads) and `drishti_lake.archives` (the feed layout only) are two copies of one rule: to be one when Lake's gets the `rows` layout |
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
| T8 | Four tests failed | **all four fixed at their cause** (`g4/fixes-1`): `TestPathsGuardTest` named `LanguageLevelGuardTest`, which built its root from `Path.of("..", "..")`; it now uses `drishti.repo.root`. `SimmServiceTest` and `SimmPlaygroundWorkbookTest`: no SIMM bug; `SimmTestData` built `FrtbProperties.defaults()`, which resolves banking's folder from the module boot (none in a unit test); it now names the parameters folder as `FrtbTestData` does (SIMM code and goldens unchanged). `PivotFormulaMigrationTest`: Rachana's reader now accepts `and`, `or`, `not` and `where=`, so those saved formulas need no migration; the five parity-set rows that expected the old printer's text lose their `rachana:` expectation (the text itself is expected); the other 14 migrate to the same text as before and `PivotFormulaParityTest` (the answers) is green. P10's open question is settled for the test by Core's reading; the migrator itself goes with the DEL reader in 2.9.14 (Q24; `I7B_DUPLICATES.md` is not in this branch) |

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
| S4 | `LakeRelocateOnStart`, `tools/lake/relocate.py`, `lake relocate`, `drishti.lakes.relocate-on-start` | 330 |
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
| S6 (Lake) | `LakesCli migrate-config`, `LakeFiles.migrate`, `adoptDemo`, `demoRoot` and their helpers, `ConnectorLake.legacy` and its `LakeCheck` note; `api-surface.txt` and CHANGELOG; Drishti's three tests of the same code; `tools/ci/check.py` **green** (build, delta-rs interop 3+3+2 with none skipped, Python tests, parity kit twice, coverage gates, equal to the reference). drishti-lake `main` `94dd281` | 497 |

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


## Pass 3: renderer (2026-11-21), on `g4/renderer` merged into `wip/g4-rehearsal-rx`

G4 item 10 (I7B_DUPLICATES U1, B6; DD-7: Jinja stays). Branch `g4/renderer` from `wip/g4-rehearsal-rx` `fd1b4a847`. Drishti Elements
**pinned at 3.2.0**: the release tree of the `v3.2.0` tag (`787f97d`, built by `tools/elements/cli.py dist` in a fresh clone), its SHA-384
manifest unchanged; `drishti.elements.version` 3.2.0, every module's range `>=3.2 <4`, every pinned path, Drishti's two libraries rebuilt.
The data tables (`world-regions.yaml`, `formats.yaml`) are byte-identical to the 3.1.0 tree, so the server jar's behaviour is unchanged.

### What was done

| Item | Result |
|---|---|
| The entry | `static/render/3.2.0/drishti-render.js` keeps the API (`render`, `patch`, `subscribe`, the `Handle`, `<drishti-screen>`, `window.DrishtiRender`) and `drishti-elements.js` the embed elements 2.x (`<drishti-view>`, `<drishti-panel>`): same options, events, attributes and contract. |
| Every panel | drawn by `drishti-views` `paint()` (`shadow: false` inside the host's shadow root): the title line, strip, freshness line, keys, every kind; drishti-chart, drishti-graph, drishti-data, drishti-inputs and the geo part handed to it from the same module graph. |
| Host part (Drishti's) | `render/3.2.0/m/`, 10 modules, 1,598 lines: where the ViewModel comes from (`/api/view`, embed v2, the token, the sign-in window), navigation in place with its own Back bar (links are real `/v/…` addresses; the painter's cancelable `link` event opens them), About this page, live frames handed to the painter, the stream over drishti-events. |
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
3.2.1-dev). Elements' `tools/run_tests.py`: every pass green, nothing skipped.

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

## Pass 3: level 1.2 (2026-11-21), on `g4/level-12` from `wip/g4-rehearsal-rx` (`d06a8d6e2`)

Owner direction: the latest language level everywhere, no tech debt. Built against Rachana `develop` `73c76065` (with `rachana upgrade`,
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
| The one setting | `drishti.rachana.language-level` defaults to 1.2 (`DrishtiRachana.LEVEL`, the newest the runtime implements) |
| Guard | `LanguageLevelGuardTest`: the holder runs the newest level; no other main source builds an instance or picks a level; **every shipped Sutra, fragment and block declares the server's level**; a Sutra of an older level is read at its own level and offered the upgrade |
| Older user Sutras | Never silent: `LanguageLevels` (declared level, `older`, one line of advice, `upgrade` with the server's function table). `drishti.py sutra lint` prints a warning per older file; **`drishti.py sutra upgrade <paths> [--write]`** (Java `SutraCli upgrade`) lists every change and flag and writes only with `--write`; the registry logs the advice for a site Sutra; the designer: the design and every operation answer carry `language {level, server, older}`, the Problems tab shows a `level` item, a click runs `POST /api/v1/designs/{id}/upgrade` (one step of the log, undo takes it back) and says each place to review. Docs: RACHANA_REFERENCE (Language level), CLI_REFERENCE (`sutra upgrade`), BUILD_GUIDE |
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
| Console and tools Python | designer, ship, schema kit, governance, Studio, pack deploy, packdiff, GLEIF, US equities, pack layout and guides: pass. Failing on the base as well: `test_studio_folded::test_the_studio_help_guide_moved_to_the_designer_guide` (Help's old addresses removed by the shims pass) and the genomics showcase `--check` (`help.yaml` `category`) |
