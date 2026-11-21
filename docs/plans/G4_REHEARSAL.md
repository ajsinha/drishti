
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
