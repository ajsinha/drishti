
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

Note (g4/fixes-1): `drishti.py build jar` ran the server's tests and `LiveTablesAdapterTest` failed (4 tests: no table `position`, RCH-2081 on `TRADE_ID`, a problem-message text) against the lake lane's Rachana snapshot; not one of the four above, not investigated, not changed here.
