<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7b gate 4 review, part 1: Drishti migrated onto the products (what is merged and stable)

Code and design gate of milestone G4 of [I7B_PRODUCTS_PLAN](I7B_PRODUCTS_PLAN.md), in the format of
[I7B_GATE_3_REVIEW](I7B_GATE_3_REVIEW.md). **Part 1 of two**: what is merged on `wip/g4-rehearsal-rx` and stable. Part 2 (short, after
the last lanes land) covers the late pieces and the full runs; its checklist is section 4. Run on 2026-11-21, 22:47 to 23:00, in a
**fresh clone from `origin`** under `~/.cache/drishti-lanes/fable-g4/` (deleted afterwards). No sub-agent was used; every claim below
was opened and read by me. No product code was changed; nothing was installed anywhere.

| Repository | Branch | Commit reviewed |
|---|---|---|
| `drishti` | `wip/g4-rehearsal-rx` | `208b491f1` (against `main` `9ce7c3170`: 3,430 files, +56,043 / −141,676) |
| `drishti` | `g4/finish` | `e8388d12f`, read only to see whether a finding is already fixed there (it is still landing) |
| `drishti-lake`, `drishti-rachana`, `drishti-tools` | tags `v0.9.0`, `v0.12.0`, `v1.1.0` | read only: what the tags hold, the public surface |

**What this review is and is not.** It is a reading of the diffs and of the code they left, with greps over the whole tree. **Nothing
was built or run**: the machine's load was 14 at the start and 35 to 39 from 22:53 on (the limit given was 24), so no Maven module and
no test was started. Every finding is therefore *by reading*; where a run would settle it, section 4 says which. The rehearsal log
(`docs/plans/G4_REHEARSAL.md`, Passes 1 to 3) was used as a map of what was done, never as evidence that it works.

Not reviewed (part 2): Rachana 1.0.0 and the re-pin (`g4/finish`), BI/table/formula expressions at 1.2 (`g4/level-12`), Lake 0.9.1
(`drishti-lake` `wip/g4-live-fixes-lake`), `g4/test-fixes`, `g4/triage` beyond what is merged at `208b491f1`.

## Verdict (provisional)

**NOT YET GOLDEN. Two blockers (F1, F2), eleven must-fix, six nice.** The migration itself reads sound: the lake modules, the binder,
the renderer, the LiveSet engine and four xlsx writers are really gone, the source plugins read through Lake's public read API only,
the live tables' authoriser, write guard and audit are careful, the console keeps a strict CSP, and no VaR or ES code changed (only
imports and the workbook adapters). What is not golden is around the edges of the switch:

- **F1 (blocker):** a compiler taken at start never sees a module's functions started later, and keeps a stopped module's functions:
  a feature `main` has is lost, and the one test that held it was changed to look elsewhere.
- **F2 (blocker):** the Lake version Drishti is being pinned to (`v0.9.0`) does not hold the fix of the second G4 incident
  (a VaR refusal answered as `502 cannot be read`); only Lake `main` does.
- The must-fix group is mostly "the job is still done twice" and "the shim is still there": three ways to fill a Delta table, two more
  xlsx writers in Python, about fifteen compatibility leftovers, nine planned tools rows untouched, banking writing through Lake's
  internal packages.

Part 2 can turn this to a pass without new design work: F1 is one indirection and one real test, F2 is the pin.

## Owner summary

1. **Lake adoption reads right where it matters.** The Delta, Iceberg and Iceberg-external plugins import `com.ash.lake.read`,
   `spi` and nothing of the table layer; `LakeReads`, `DrishtiLake`, `DrishtiLakeCatalog` and the two mains are 475 lines in all. The
   vector form `{"$vector": "PNL"}` is the same on `main` and here; the incident was the fixtures' new path through the one loader, and
   its fix (`bcff31d`) **is** in Lake `v0.9.0`. The second incident's fix (`d94023b`) is **not** (F2).
2. **VaR and ES.** No engine source changed: the banking diff is imports (`com.ash.lake.*`, `com.ash.rachana.*`), the removed API
   aliases, and the playground and Explain workbooks on the shared writer. The golden data files are untouched. But the headline
   figures (VaR 167,372,692, ES 210,131,972 on 2026-11-06) are asserted in exactly one place, `tools/drillkit/container_smoke.json`,
   which only a drill runs, over a demo lake that the **new** loader now builds by default; and the drill's lake cache no longer
   notices a change of loader (F10). Until part 2 runs it on an empty cache with the pinned tags, the headline is unproven for G4.
3. **Live tables.** Kind access answers "unknown" so existence does not leak; audience = roles, embedding app, masks, administrator,
   plus the row rule and the person when a rule exists; a write is refused unless the request's person is the change's maker; the
   console opens every upstream stream as the signed-in person. Three defects at the edges: F3 (an evicted search table is read as a
   kind of its own name), F4 (a table that spans cluster members reads them as its first opener for ever), F5 (a repeated change id
   is answered without looking at what was sent).
4. **Shims: parts 1 to 4 removed a great deal, and a list is still there** (F8), some of it decided for G4 in I7B_DUPLICATES
   (DD-9: the frozen renderer 1.2.0 and embed v1; DD-14: aliases and old names).
5. **Tools: "one xlsx writer" is true for Java only** (F9); T3 to T11 and U11 of the duplicates plan are not done and the log does
   not say so.
6. **Docs part 2** is largely accurate against the code; the misses are listed in F12 (deleted tools named, sample output of a
   default that changed, two "older builds" bug stories, a design chapter naming classes that left).
7. **Security:** nothing found that lets one person see another's rows. CSP unchanged and strict (`core/app.py:90`); no inline
   handler in any template; no `innerHTML` in the files the BI and renderer lanes touched.

## 1. What I checked

| Area | How | Result |
|---|---|---|
| Lake host, plugins | read `lake/drishti-lake-host` whole; the Delta plugin's diff; every `com.ash.lake.*` import in main code against `java/api-surface.txt` of `v0.9.0` | plugins clean; banking, the store loaders and the host import five non-public packages (F6) |
| Vectors | every `$vector` in the tree; `main` against the branch | same stored form on both; the fix is in the tag |
| Incident fixes in the pins | `git merge-base --is-ancestor` of `bcff31d`, `d94023b`, `eafbd4e`, `94dd281` (Lake), `cc577818`, `7b16e0a0` (Rachana) | all in the tags but **`d94023b`** (F2) |
| Start-up on few cores (`main` `a816aa7c0`, `9ce7c3170`) | the branch against both commits | the platform-thread start and `StartOnFewCoresTest` are kept; the provider preload left with `StorageProviders` (Lake's runtime is built once, before the connectors) |
| ComputeEngine | the interface, the registry's constructor, every implementer | one type; F14 |
| Live tables | `server/live` (11 files, 2,191 lines): controller, authoriser, sink, `FormResults`, `LiveSources`, the rights; `EditService`'s diff; Rachana `LiveTables` at `v0.12.0` for how a resolver is used; the console's `live_routes.py`, `channel.py` | F3, F4, F5, F15, F18, F19 |
| Module functions | `DrishtiRachana`, `RachanaConfiguration`, every holder of an `ElCompiler`, Rachana's `Rachana.compiler()`, `ModuleHostServerTest` on `main` and here | F1 |
| Shims | one grep of main code for release-bounded words, each hit opened | F8 |
| Tools | pom modules used; I7B_DUPLICATES T1 to T11, U11 against the tree | F9 |
| Lake build engines | `tools/lake/build.py`, `writers.py`, commit `efcd4ad86` | F7 |
| Goldens | where the figures are asserted; the diff of golden files and tests; `lake_cache.py` | F10; section 4 |
| Docs | QUICKSTART_DEVELOPERS, DEPLOYMENT_AND_QA_GUIDE, IDE_GUIDE, README, DATA_LAKE_GUIDE, OPERATIONS_GUIDE, CLI_REFERENCE: a grep for 40 names the code no longer has, then the hits read | F11, F12 |
| Console security | CSP string, inline handlers, `innerHTML` in the touched files, who a channel's upstream runs as | clean |

## 2. Findings

Paths are in `drishti` at `208b491f1` unless a repository is named. "Also on `g4/finish`" means I checked that branch still has it.

### Blockers

**F1. BLOCKER. A compiler taken at start is fixed to that moment's functions: a module started later is not seen, a module stopped
stays callable.**
- `compute/drishti-rachana/.../DrishtiRachana.java:152-164` (`addFunctions` builds a new immutable Rachana and drops the old one),
  `RachanaConfiguration.java:31-36` (the `ElCompiler` bean is `DrishtiRachana.compiler()` **once**),
  `kernel/drishti-engine/.../EngineConfiguration.java:153-185` (`StructuredSearch`, `BadgeRenderer`, **`Binder`**, **`ViewPipeline`**
  take that bean), and the same bean in `HistoryService`, `ExplainService`, `ImpactService`, `RecordReads`, `FieldValueReader`,
  `DesignAboutService`; `kernel/drishti-engine/.../source/RowPredicate.java:49` (`new ElCompiler(2_000)`: the row rules' compiler is
  Core's alone). Rachana `v0.12.0` `Rachana.java:35,50,143`: the compiler is a final field with "its own function table".
- Why: on `main` the function table was global, so any compiler saw a started module's functions
  (`ModuleHostServerTest` there: `new ElCompiler(16).compile("greeter_greet('Ravi')")`). Here every screen the server binds goes
  through a compiler made before the module started. A module switched on in Admin gives functions no Sutra can call until a restart;
  a module switched off keeps answering through the old instance (and its class loader stays referenced): "off = gone everywhere"
  no longer holds. The log names it (P2, "Drishti, unchanged") and nothing later closes it; it is still open on `g4/finish`.
- The test was weakened, not the code fixed: `ModuleHostServerTest.java:186` now evaluates with `DrishtiRachana.compiler()`, which
  is the one place that always works. No test asks the server (a preview, a view) for a module function after a start and after a stop.
- Fix: one indirection, in one place. The beans take the holder, not a compiler (`Supplier<ElCompiler>`, or a `Binder`/`ViewPipeline`
  rebuilt by the swap as `Formats` already is through `Formats.live`); `RowPredicate` and `DocPath` compile through the same holder.
  Or Rachana gives a compiler view that follows its instance (the log's own suggestion). Then the test: start a module, bind a Sutra
  that calls its function through `/api/v1/studio/preview`, stop it, the same call is refused.
- By reading only. If part 2's run shows the server path does see a later module's function, this finding is withdrawn; I do not
  expect it to.

**F2. BLOCKER. The pinned Lake does not hold the fix of the G4 incident "a caller's refusal is read as an unreadable lake".**
- `drishti-lake`: `d94023b` ("A caller's refusal is its own") is on `main`, **after** tag `v0.9.0`; `g4/finish` `d5e23a02c` re-pins
  Drishti to the `v0.9.0` tag (`pom.xml:110` here is still `0.9.0-SNAPSHOT`, which hid it: the triage built the snapshot from Lake
  `94dd281` plus the two fixes).
- Why: without it `Problems.read` turns the VaR engine's own refusal inside a read (a duplicate trade, `DRS-5202`, 422) into
  `LK-1006` / `DRS-1003` **502** and records a read problem for the date, so the connector's health says DEGRADED for data that is
  fine (`VarApiTest.aDateThatIsNotOneRun…`, Pass 3 triage, row 2). A wrong status on the VaR API and a false health alarm.
- Also: `tools/lake/maintain.py` calls `lake maintain --relayout`, which Lake `0.9.0` refuses (the log says so); `78a8422` is on no
  branch of `origin` in the owner's Lake clone.
- Fix: Drishti pins the Lake release that contains `d94023b` and the maintain work (0.9.1), jars **and** wheel (F11). Part 2 checks
  the ancestry, not the version string.

### Must fix

**F3. MUST-FIX. A search's table that left `LiveSources` is read as a kind called `q-<hash>`; declarations are never released.**
- `kernel/drishti-server/.../live/DrishtiLiveTables.java:97-106` (the resolver: a name not in `LiveSources` "is a kind's own":
  `new KindSnapshot(name, …)`), `LiveSources.java:33,51-57` (at most 2,000, the oldest leaves), `FormResults.java:130-137`
  (put again only by a new `/rdp/searches`). Rachana `v0.12.0` `LiveTables.java:281,311`: a table is opened on first use and
  **closed when idle**, so the resolver is asked again later.
- Why: a grid holds `q-<hash>` and pages through `/rdp/tables/q-<hash>/query`, which does not pass `FormResults.open`. After 2,000
  other distinct searches (columns × day × kept condition × audience: days of a busy site) and one idle close, the next page reads the
  kind `q-<hash>`: an empty page or a 503, and the as-of date, the "whole" flag and the members are lost with the source. Meanwhile
  the spec stays in Rachana's map and in `SPECS` for ever (LS6 was ruled "unchanged"; it is a slow leak and a wrong answer).
- Fix: a `q-` name without a source is `404 rdp.table.unknown` (the grid then asks `/rdp/searches` again), never a kind; Rachana gets
  `undeclare` and `LiveSources` calls it on eviction, so the cap bounds both.

**F4. MUST-FIX. A FORM table that spans Drishti\*net members reads the members as its first opener, for as long as it lives.**
- `FormResults.java:127-136` (the source keeps `p` in `peerRows(…, p, …)` and `subscribeKind(f.kind(), p, l)`),
  `kernel/drishti-server/.../net/PeerSources.java:111-118`.
- Why: the audience shares the table between people of equal rights (right), but every later reader's snapshot and stream on the
  other members is asked **as the first person**: the members' audit names the wrong reader, and when that person's account is
  disabled or their roles change the shared table fails or goes stale for everyone else still entitled. "Caller to audit" was this
  lane's ruling for writes; reads across nodes miss it.
- Fix: the spanning table's audience always carries the person (as it already does when a row rule exists), or the members are asked
  with a node identity carrying the audience's rights and each reader is audited where they read.

**F5. MUST-FIX. A repeated change id is answered from the first change without comparing what was sent.**
- `kernel/drishti-server/.../edit/EditService.java:151-163`, `known` at `603`.
- Why: id `X` applied a change of one column; the same person sends `X` again with another column or another row (a client bug, a
  retry buffer mixed up). The answer is the first change's state ("applied"), the audit says "answered applied again", and the second
  edit is never made. The id is the client's since this lane, so the server must hold the pairing.
- Fix: a repeat must match the first on kind, entity and items; otherwise `409` with "the change id was used for another change".

**F6. MUST-FIX. Drishti's writers and loaders import Lake packages outside its public surface.**
- `modules/banking/.../var/VarPvStream.java` and `official/DeltaSink.java` (`com.ash.lake.delta.write.LakeWriter`, `LakeManifest`),
  `official/IcebergSink.java`, `LakeAppends.java` (`com.ash.lake.iceberg.table.IcebergAppender`), `official/OfficialSink.java`
  (`com.ash.lake.io.S3Storage`, `S3Settings`, `LocalPaths`); `lake/drishti-lake-host/.../DrishtiLake.java` and seven store loaders
  (`com.ash.lake.common.RowFeed`); `LakeReads.java` (`com.ash.lake.io.PageDecodeException`, `UnsupportedCodec`);
  `testing/drishti-benchmarks/.../HistoryBench.java`. Lake's `java/api-surface.txt` lists only `com.ash.lake`, `config`, `contract`,
  `inbox`, `load`, `memory`, `memory.store`, `read`, `spi`.
- Why: a Lake release can change these with no surface diff and Drishti's scenario-PV stream and official sinks (VaR inputs) break
  or, worse, change. `ArchitectureRulesTest.sourcePluginsReadThroughThePublicReadApi` guards three plugins only. It is also a second
  and third way to write a lake table from Java beside the one loader.
- Fix: Lake puts what a host writes with behind `spi.TableWriter` / `load` (or lists these types in the surface on purpose);
  Drishti's rule becomes "no main class imports a Lake package that is not in the surface".

**F7. MUST-FIX. `lake build` has three ways to fill a Delta table.**
- `tools/lake/build.py:966` (`--engine auto|drishti|deltalake|java`), `686-703`, `tools/lake/writers.py:126-138`; `auto` falls from
  the parallel loader to "Drishti's Java writer" (Python stages Parquet, `LakeWriter` commits) to delta-rs.
- Why: the owner's rule is one Java path (the default) and one Python path. The middle engine is a third, with its own staging code
  (`stage.py` 524, `writers.py` 373) and its own bugs to fix; and `auto` moves between engines by what happens to be installed.
- Fix: remove the `drishti` engine (the loader is its successor); `auto` is the loader, else delta-rs with the reason on the first
  line; CLI_REFERENCE 7.6 follows.

**F8. MUST-FIX. Compatibility code left after shims parts 1 to 4.** Each was opened; all are also on `g4/finish`.

| # | Where | What |
|---|---|---|
| a | `drishti-console/web/static/render/1.2.0/`, `drishti-console/web/embed/` (14 files), `core/render_dist.py:40-42` (`RETIRED`, `FROZEN`), `core/app.py:706-713`, `routes/embed_v2_routes.py:55,156-157,174-175` | the frozen renderer 1.2.0, embed v1, the 2.0.0 and Elements 2.3.0 redirects "for one release": I7B_DUPLICATES U3, U4, decision DD-9 "delete in G4" |
| b | `kernel/drishti-server/.../engines/EngineSwitches.java:82-90`, `modules/banking/.../META-INF/drishti/engines.yaml:26,55,112,141` | the old switch names (`drishti.var.enabled`…) "still work" with `DRS-1024` (S5, DD-14) |
| c | `modules/banking/module.yaml:472` and 18 `was:` keys, `ModuleSettingsController.java:68-96` | old server keys of module settings "read for one release", among them `drishti.var.scenario-window`, `drishti.var.confidence`, `drishti.var.es-confidence`: an old key in a site file still sets a VaR parameter |
| d | `kernel/drishti-module-runtime/.../DmodManifest.java:104,210`, `Claims.java:43` | `api.aliases` still parsed and claimed; nothing serves an alias since `ApiAliasFilter` left |
| e | `compute/drishti-tables/.../def/TableDef.java:29`, `catalog/Catalogue.java:234-258` | a table's `was:` name "resolves for one release" |
| f | `modules/banking/.../var/VarExplainController.java:46-116`, `MrCubeController.java:47,69`, `drishti-console/plugins/banking/risk_api.py:17` | deprecated explain paths "answered for this release" |
| g | `drishti-console/core/edition.py:442-450`, `kernel/drishti-server/.../edition/EditionConfiguration.java:178` | `edition` "read for one release" and the list of keys no longer read |
| h | `tools/serverconnectors.py:86-87` | prints the connectors API's `deprecated` list, a field part 4 removed: dead |
| i | `drishti-console/web/static/js/drishti_bi-lib.js:403-414`, `drishti_bi-layer.js:57-79`, `drishti_bi-notebook.js:77` | "Other datasets (version 1)" pickers, after old-form datasets became an error (`DRS-9196`) |
| j | `tools/packgen/common/sutracases.py:17` | an old case layout "still read during 2.9.4 with a deprecation warning" |
| k | `tools/settings_schemas.yaml:517` and its 17 `by: profile` rows, `docs/admin/CONFIGURATION.md:6662-6664`, `deploy/docker/compose.data.yaml:22` | the store profiles part 3 deleted are still described ("deprecated … and still work": they do not exist) |
| l | the log's own "not done, named": `PackStateStore` import, `LoadStore`'s first-read import, the `connector-migrated` override migration, `FileUserStore` / `FileAuditLog` (test only), Lake `PersistedNames` | named, not scheduled |

Fix: remove a to k in G4 as decided (an unknown old name is refused at start by name); for l, a line each in the log saying which
release removes it, or remove now.

**F9. MUST-FIX. Tools adoption stops at T1 and T2, and "one xlsx writer" holds for Java only.**
- Not done and not named as open in the log (I7B_DUPLICATES puts them in G4 Tier 1): T3 hashes (`MessageDigest.getInstance` in 67 main
  files, no user of `tools-codec`), T4 canonical JSON, T5 CSV (`dist/CsvWriter.java`, `plugin/file/CsvReader.java`; no user of
  `tools-csv`), T6 `common/TempFiles.java` and `JvmTempDir.java` beside Lake's and the tools', T8 `common/BusinessCalendar.java` with
  the `HostCalendars` adapter beside Rachana's, T9 sixteen files building their own `HttpClient` (the five I opened name neither the shared TLS nor the
  proxy settings), T10 guards, T11 zips. The pom uses `tools-net`, `-settings`, `-concurrent`, `-time`, `-xlsx`, `-testkit` only.
- Two xlsx writers remain in Python: `drishti-console/core/export.py:232-292` (`to_xlsx`, used by `routes/export_routes.py:99` and
  `routes/history_routes.py:155`) and `tools/sizing_planner.py:506-845`, the latter with its own formula evaluator beside
  `drishti_capacity.js` (U11). The 60-workbook proof does not cover them, nor do the three bugs the shared writer fixed.
- Fix: the owner either keeps these rows in G4 (then they are done before the drill) or moves them by name to a later release in
  I7B_DUPLICATES and the log; the two Python writers go to the server's one writer (the console streams it, as
  `core/workbook_stream.py` already does for the playgrounds).

**F10. MUST-FIX. The drill's lake cache no longer changes when the loader does.**
- `tools/drillkit/lake_cache.py:43-44`: the hashed sources lost `lake/drishti-lakeload/src/main` and `lake/drishti-deltalake/src/main`
  and gained only `lake/drishti-lake-host/src/main` (glue). `pom.xml` (`drishti.lake.version`) and the wheel are not in the key, and
  `DRISHTI_LAKE_CACHE` is "one absolute folder for every checkout and drill".
- Why: after a Lake re-pin the cached demo lake written by the previous loader is reused, and the drill's golden check proves the
  old loader. With a `-SNAPSHOT` pin not even the pom changes.
- Fix: the key includes the pinned Lake version and the digest of the loader jar and the wheel in use; part 2 runs on an empty cache.

**F11. MUST-FIX. The Lake wheel: a development version, no documented way to get it, and "the newest file" at run time.**
- `drishti-console/requirements.txt:18-19` (`--find-links ../lib/wheels`, `drishti-lake==0.9.0.dev0`, the same on `g4/finish`
  where the jars are the `0.9.0` tag); `lib/` holds no `wheels` folder and no tool of this repository makes one;
  `tools/lake/lakewheel.py:38-39` takes the newest `drishti_lake-*.whl` by modification time, whatever its version.
  QUICKSTART_DEVELOPERS, DEPLOYMENT_AND_QA_GUIDE and IDE_GUIDE do not contain the word "wheel".
- Why: a fresh clone following the quickstart cannot install the console's requirements; and a machine with two wheels runs the
  Python load rules of one Lake against the jars of another without a word.
- Fix: the wheel's version is the pinned Lake version, read from one place; `lakewheel` takes that version or refuses by name;
  the quickstart and the deployment guide say where the wheel comes from (the index repository's `build_all.py`, the release bundle).

**F12. MUST-FIX. Documents that do not say what the code does.**

| Document | Line | What |
|---|---|---|
| `docs/guides/CLI_REFERENCE.md` | 4130 | lists `tools/lake/relocate.py` (`lake relocate`) and `tools/lake/filedate.py`: both deleted |
| `docs/guides/CLI_REFERENCE.md` | 1764, 1773, 1792, 1804, 1829, 1921 | sample output of default runs says `engine: drishti`; the default is the parallel loader since `efcd4ad86` (1883-1891 say so) |
| `docs/guides/IDE_GUIDE.md`, `docs/guides/OPERATIONS_GUIDE.md` | 435, 589; 3196 | "Older builds only … Fixed in the product … on an older build add two lines": a bug's history in a guide (and it names `StorageProviders`, which left) |
| `docs/architecture/DATA_ARCHITECTURE.md` | 3359, 3479-3484 | `com.ash.drishti.lakeio.Storage`, `StorageProviders` and the services file of a module that is now Lake's: describe Drishti's side, link Lake's design |
| `docs/QUICKSTART_DEVELOPERS.md` | 119, 138 | "from their release tags only … a snapshot hides a mismatch", while `pom.xml:103-110` at this head pins three snapshots (true only once `g4/finish` lands: part 2) |
| `README.md`, `docs/QUICKSTART_DEVELOPERS.md` | 62-66; 45-52 | every product link is `github.com/drishti-platform/…`; `origin` is `github.com/ajsinha/drishti` (the organisation move is due before the drill: part 2 opens each link) |
| `deploy/docker/compose.data.yaml` | 22 | see F8 k |

DATA_LAKE_GUIDE and DEPLOYMENT_AND_QA_GUIDE: no stale name found by the grep; their commands were not run (section 4).

**F13. MUST-FIX. The headline goldens have no proof in G4 yet.** `tools/drillkit/container_smoke.json:5-6` is the only assertion of
167,372,692 and 210,131,972; the Java goldens (`VarGoldenMatrixTest`, `PackagedVarGoldenTest`) use the small committed set and passed
in the lanes. The demo lake of the smoke is now written by Lake's loader by default. The lake pass compared four tables of the two
loaders' lakes (318,180 rows equal), which is good evidence but not the figure. Fix: section 4, item 3; nothing to change in code.

### Nice

**F14. NICE. `ComputeEngine` is a union with every method optional.** `kernel/drishti-api/.../compute/ComputeEngine.java:44-140`:
`id()` defaults to `manifest().name()` and `manifest()` defaults to throwing, so a switched engine that forgets `id()` fails at
registration with an unrelated message; `stop()` and `close()` are two releases; `EngineRegistry.java:96-104` takes every bean of the
type and invents a catalogue entry, so a computing engine ever exposed as a bean becomes a switch in Admin. The javadoc says EN-25
moves the computation face again: fine as a named interim, but then say so in I7B_DUPLICATES (the clash is gone, the fold is not final).

**F15. NICE. The change sink reports any failure as a refusal with the exception's text.**
`live/DrishtiChangeSink.java:147-148`: a database or outbox failure inside `submit` comes back `REFUSED` with `e.getMessage()`.
A refusal tells the grid not to retry; an internal message reaches the browser. Use `FAILED` and the made-safe words of `ReadFailures`.

**F16. NICE. `MappedIdMap` twice.** `plugins/drishti-plugin-delta/.../MappedIdMap.java` and
`plugins/drishti-plugin-iceberg/.../MappedIdMap.java` differ in one comment line (84 and 85 lines). One class in `drishti-lake-host`.

**F17. NICE. Small leftovers.** `DrishtiLakeCli.java:39` usage says `load|check`, the switch also takes `lakes`; `DrishtiLakeLoad`
is a second main for `DrishtiLakeCli load`; `testing/drishti-testroots` now holds only `RecordedAnswers` (the name is wrong);
`tools/xlsx_before_after/` (`capture_patch.py`, `capture_unpatch.py`, 544 lines) is one-off proof tooling in the product tree.

**F18. NICE. An open change stream outlives the rights it was opened with.** `live/LiveTablesController.java:158` (`SseEmitter(0L)`),
the principal captured at subscribe. A person whose role is withdrawn keeps receiving until the connection drops. Probably the same on
`main`'s LiveSet (not compared); a periodic re-check of `view(principal, table)` by the engine's upkeep would close it.

**F19. NICE.** `live/LiveTablesController.java:225` decodes the path with `URLDecoder` (form rules: `+` becomes a space). No table is
named with `+` today; `UriUtils.decode` is the path's decoder.

## 3. What reads right (so part 2 need not repeat it)

- **No table-layer type in a source plugin.** Delta 1,554 lines, Iceberg 1,213, Iceberg-external 554: `LakeReader`,
  `TableReader`, `TableSnapshot`, `IdMap`, `VectorRequest` only. 7,260 lines left the three plugins.
- **One runtime.** `DrishtiLake.runtime()` is the installed one, else one built from the process's settings and rebuilt when they
  change; `DRISHTI_FOLDERS` maps the four folder settings and nothing else.
- **The authoriser fails closed** at every step I traced: no principal, no table, may not open (all "unknown"), a filter on a masked
  field (no table at all), a write without the request's mark, another person's change id, an id the write-back did not keep.
- **The audit of a live write** carries the caller, the change id and the columns, and the write-back's own record carries the same id.
- **The console never shares an upstream stream between people** (`core/channel.py`: a `ChannelSession` per identity, owner = user,
  application and server).
- **The start-up fix of `main`** is intact where it matters (connectors start on platform threads; the test is kept).
- **The ComputeEngine fold changed no id and no catalogue.**
- **Lake's refusals are mapped once at the API edge** (`LK-1001…1005` to Drishti's codes) and Rachana's through `DrishtiRachana.drs`.

## 4. Part 2 checklist

Part 2 verifies exactly this, on the final head, in fresh clones of all five repositories, on a machine below load 24, with an empty
lake cache (`DRISHTI_LAKE_CACHE` unset or a new folder) and a scratch Maven repository.

1. **Cross-stack versions, one table.** For Drishti's final head: `pom.xml` `drishti.tools.version`, `drishti.rachana.version`,
   `drishti.lake.version`, `drishti.elements.version`; the vendored Elements folder name, its manifest version and SHA-384 record;
   the render folder; every module's Elements range; `drishti-console/requirements.txt`'s wheel; the SDK `repo/`; COMPATIBILITY in the
   index repository. Each equals a **tag** that exists on `origin`, no `-SNAPSHOT`, no `.dev0`, and the Rachana jar's bundled Elements
   formats are the same Elements version. Ancestry, not names: Lake's pin contains `bcff31d`, `d94023b` and the maintain work;
   Rachana's contains `cc577818`; the product gates' blockers (G3 F1, F2, F5) are in the pinned tags.
2. **Every suite, fresh clone, once:** Tools, Rachana, Lake, Elements each by its own check; then Drishti `mvnw clean verify` (the
   whole reactor, `drishti-it` and `drishti-modules-it` included, `PackagedVarGoldenTest` with the wheel), the Python `tools` and
   console suites, the Node tests, the browser suites on Chromium (WebKit where it runs). Each failure classified against `main`;
   the list of "open" rows of the triage pass must be empty or each row signed by the owner. No test skipped that ran on `main`
   (compare the skip counts with `main`'s).
3. **The goldens:** `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest`
   bit-identical; then the 60-day demo lake built by `lake demo` with the default engine from the pinned jars, the server started on
   it, and `VAR BANK` on 2026-11-06 = **167,372,692**, ES = **210,131,972** (`VAR DESK RATES` 74,633,680 / 79,246,576), by the API
   and by the container smoke. The same on an Iceberg lake (`VarFormatEqualityTest`, `ExposureFormatEqualityTest`, `VarShardClusterTest`).
4. **F1 by a run:** a module started after the server is up; a Sutra calling its function previews through the API; the module
   stopped; the call is refused; no class of the module is reachable (the module-off tests' own check).
5. **F2 by a run:** `VarApiTest` whole, and the connector's health after the duplicate-trade refusal (UP, no read problem).
6. **The late lanes, read as this part read the early ones:** Rachana 1.0.0 (one level: `LanguageLevels`, the upgrade endpoint and
   `sutra upgrade` gone with nothing left behind; no `rachana: 1` or `1.2` handling that is now dead; the docs of the level);
   BI, table and formula expressions on the one level (`CheckedMode.parse`, `NodeLowering`, `FormulaLowering`: the 220 tests that
   failed in the first try, `PivotFormulaParityTest`, `SutraAggregateParityTest`; the `cell` catalogue row); Lake 0.9.1 maintain
   (data safety: a compaction or vacuum can never drop the newest copy of a row; `LakeMaintainTest.compactsAndVacuumsAlike` on all
   three stores; the retention rule the same on Delta and Iceberg or the difference written down; `tools/lake/test_maintain*.py`);
   the test-fixes and triage commits (each "fixed" row: was the cause fixed or the test bent; F1 is the pattern to look for).
7. **Each must-fix of this part closed or moved by the owner in writing** (F3 to F13), re-read at its lines.
8. **Shims, final sweep:** the grep of F8 on the final head returns only the kept messages named in the log; `render/1.2.0` and
   `web/embed` are gone or DD-9 is changed; `drishti_geomap.js` gone with them.
9. **Docs:** every command block of QUICKSTART_DEVELOPERS and DEPLOYMENT_AND_QA_GUIDE run once in the fresh clone as written (Linux);
   `test_docs_rot`, `test_help_links`, `settings_catalogue --check`, the PDFs regenerated from the final text; F12's lines; every
   `github.com/drishti-platform/…` link opens; CHANGELOG and RELEASE_NOTES hold the history the guides no longer do.
10. **Guards hold what this gate found:** a rule for F6 (Lake surface), a test for F1 (module functions through the server), a test
    for F3 (an evicted search table), the cache key of F10.
11. **Nothing left on disk or in git:** no temporary folder after the suites, the clones clean, `main` and every tag as the owner
    left them, the lane branches merged or deleted by name.

## 5. Owner decisions asked

1. F9: are T3 to T11 and U11 in G4 (before the drill) or moved, by name, to a later release?
2. F8 a: DD-9 says the frozen renderer 1.2.0 and embed v1 are deleted in G4. Still so?
3. F7: which Delta fill path leaves, the `drishti` engine (my recommendation) or delta-rs?
4. F4: a spanning table per person (simple, less sharing) or a node identity with per-reader audit?
5. F8 c: old module-setting keys (`was:`) refused at start by name, as DD-14 says, including the VaR ones?

## 6. State left behind

The clone under `~/.cache/drishti-lanes/fable-g4/` is deleted. No repository other than this plan branch was written; no Maven
repository, no `~/.m2` entry, no stack and no lake was touched. `main` and the tags were not touched.

---

# i7b gate 4 review, part 2 (final): the late pieces and the full runs

Run on 2026-11-22, 03:15 to 04:45, in **fresh clones from `origin`** of all five repositories under `~/.cache/drishti-lanes/fable-g4p2/`
(deleted afterwards), a scratch Maven repository there (never `~/.m2`), a fresh Python 3.13 environment with the pinned Lake wheel, an empty
lake cache, every heavy command through `heavy.sh`. No sub-agent was used; every claim below was opened or run by me. No product code was
changed; nothing was pushed but this file.

| Repository | Candidate | Commit reviewed and run | Ancestry checked |
|---|---|---|---|
| `drishti` | `wip/g4-rehearsal-rx` | `10753f4dbba3905d146c6d777348ee9602058f93` (= `origin`'s head; the 32 `g4/*` lane branches are all ancestors of it) | pins: Tools `1.1.0`, Rachana `1.0.0`, Lake `0.9.1`, Elements `3.3.1`; no `-SNAPSHOT`, no `.dev0` |
| `drishti-rachana` | 1.0.0 = `develop` | `66b3c9c7bf7c92c278da4e1ec6420ad021c83820` | holds `cc577818`, `7b16e0a0`; 8 commits after `v0.12.0` |
| `drishti-lake` | 0.9.1 = `develop` | `336524bf898c8cf3c66a2ee41c9dd8b5593ec7dc` | holds `bcff31d`, `d94023b`, `eafbd4e`, `94dd281`, `78a8422` (part 1's F2); 13 commits after `v0.9.0` |
| `drishti-elements` | 3.3.1 = `develop` | `20661f098a10d664d592dd8384442157436aba36` | 2 commits after `v3.3.0`; no file of the tree vendored in Drishti (`static/vendor/drishti-elements/3.3.1/`) differs from it |
| `drishti-tools` | tag `v1.1.0` | `4d3567a` | the tag |

The Lake wheel built here from `336524b` has the SHA-256 of `lib/lake-wheel.pin` (`81241d9c…f60a16`): the build is reproducible and
`lakewheel.py` says ok.

## Verdict

**NOT YET GOLDEN.** Much of part 1 is closed well: F1, F2, F3, F5, F6, F10, F11 and F13 are fixed at their cause with tests that hold
them, the four VaR and ES goldens pass on data files that are byte for byte `main`'s, and the headline is proven end to end: on a demo lake
built here by the pinned loader, the server answers **VaR 167,372,692 and ES 210,131,972** for the bank on 2026-11-06 (desk rates
74,633,680 / 79,246,576). But the final head was never run whole, and running it whole, with four probes, found six blockers. Four are
about a number being wrong with no word:

- **F20 (Lake 0.9.1):** compaction can still change what readers see. When the load committed last has an earlier run id than an
  older load, Delta and Iceberg readers answer the **superseded** copies after `lake maintain --compact`. Shown by a run.
- **F21 (Lake 0.9.1):** on Delta, a promoted column that first arrives in a later load of an existing table is **dropped silently**
  (Iceberg adds it). It is why `VarInputsApiTest` is red: a matured position's `MATURITY_DATE` is lost and it enters VaR (736 for 636).
- **F22 (Drishti):** a retired VaR setting in the site's own `config/engines/var.yaml` (where `main` keeps `scenario-window` and
  `confidence`) is neither read nor refused: the server starts and VaR runs on the defaults. Shown by a run.
- **F25 (Rachana 1.0.0):** measure functions are still parsed by the level 1.0 parser, without an end check: in a BI model with
  `functions:`, a measure `A if C else B` becomes `A`. One conformance case is still `verified: false`.

and two are about the proof:

- **F23 (Drishti):** the head is red: 121 of 5,302 Java tests fail (117 in `drishti-server`: the test fixtures still write the `was:` key
  that F8 e removed) and 3 console tests. The last four merges were proved by targeted runs only.
- **F24 (Drishti):** `./mvnw clean verify` in a fresh clone stops at module 27 of 52: a Delta plugin test needs the exec jar that the
  reactor builds 20 modules later.

None needs new design. F22 to F25 are small; F20 and F21 each need one decision in Lake.

## 1. What was run

| Suite | Command | Result |
|---|---|---|
| Tools `v1.1.0` | `./mvnw clean install` (scratch repository) | **green**: 126 tests, 0 failed, 1 skipped (`LibreOfficeOpensTest`: no LibreOffice here) |
| Rachana `66b3c9c7` | `./mvnw clean install` (the verify with its lanes) | **green**: 1,119 Java tests, 0 failed, 0 skipped; Node 43; Python 98; conformance JVM = Node at 1.2: 2,589 cases, 2,426 pass, 163 unsupported (the 148 protocol cases, which the protocol lane runs, and 15 others), 0 failing; protocol lane: reference 57 of 148, Python 57 of 148, **live 148 of 148**; browser lane green |
| Rachana, the unverified case | `protocol_check.py --live --only 'protocol/write#*'` | 21 of 21; `a-reused-change-id-with-other-content-is-refused` **passes** (409 `rdp.write.conflict`, `reused: true`, the audit `write-submitted`, `write-reused`, `write-sent`) |
| Lake `336524b` | `./mvnw clean install -DskipTests`, then `tools/ci/check.py` | **green**: 447 Java tests, 0 failed, 1 skipped (`CodecsTest`); delta-rs interop 3 + 3 + 2, none skipped; Python 148 passed; parity kit **642 results twice, stable**; coverage and additive gates; equal to the reference. (The kit failed in my first try only because it runs Maven offline and a new Maven repository lacks the dependency plugin: F38.) |
| Elements `20661f0` | `tools/run_tests.py` | **green, nothing skipped**: Node 407 + 37 budget; pytest 200 + 4 budget |
| Drishti `10753f4db`, as asked | `./mvnw clean verify` in the fresh clone | **RED, stops at module 27 of 52**: `DeltaAfterMaintenanceTest` (F24) |
| Drishti, with the jar built first | the Maven command of `drishti.py build all`, then `./mvnw clean verify -fae` | **RED**: 46 modules run, `drishti-server` fails (118 of 4,503 tests), five modules skipped behind it |
| Drishti, the complete run | `./mvnw clean verify -Dmaven.test.failure.ignore=true` (52 modules, 42:43 min) | **5,302 tests, 121 failed (44 failures, 77 errors) in 25 classes, 10 skipped**: `drishti-server` 117 in 22 classes, `banking-service` 3, `drishti-engine` 1 (F23). Green inside it: `VarGoldenMatrixTest` (with the headline), `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` (6), `PackagedVarGoldenTest` (2), `VarApiTest` (15), `VarFormatEqualityTest`, `ExposureFormatEqualityTest`, `VarShardClusterTest` (8), `ArchitectureRulesTest` (36), `ToolsAdoptionGuardTest` (4), `ModuleHostServerTest` (4), `ModuleSettingsTest` (5), `FormApiTest` (10), `KafkaTradeEditTest`, `SourceShapeTest` (12), `OfficialSinkTest` (5), `IcebergMovedConnectorFileTest`, `StartOnFewCoresTest` |
| Drishti Python tools | `pytest tools -n 4 --dist loadfile --ignore=tools/browser` | **green**: 1,445 passed, 22 skipped, 148 subtests (1,467 collected) |
| Drishti console | `pytest drishti-console/tests -n 4 --dist loadfile -m "not browser"` (the Node tests are inside it) | **RED**: 3,044 passed, **3 failed**, 94 skipped (F23) |
| Browser smoke, Chromium | page walk, page widths, top-bar widths, render parity, live grid, embed elements, 4 workers | 427 passed, **1 failed** (`test_live_grid_browser`: the tray read before it updated; 15 of 15 alone: F33) |
| Browser smoke, WebKit | page walk and page widths, 4 workers | 116 passed, **7 failed**, one cause (F33); page widths alone: 115 of 115 |
| Demo lake and the headline | `lake demo --engine java --as-of 2026-11-06` (102 tables, 3.7 GB, 4 minutes), `start server --demo`, `GET /api/v1/risk/var/scope` | **BANK 167,372,692 / 210,131,972; DESK-RATES 74,633,680 / 79,246,576** (250 scenarios, 99% and 97.5%): equal to `container_smoke.json` |

Two failures did not repeat and are counted as flaky, not as red: `ReactiveApiTest.anEventReachesTheTriggersOnce` (failed in the second
run, passed in the complete one) and `AbandonedReadsTest` (the reverse; 33 reads against at most 27). `VarInputsApiTest` was run again
alone on a quiet machine: the same two failures, so it is not load (F21).

## 2. The checks asked for

| | Check | Result |
|---|---|---|
| a | `ModuleSettingsTest.theModulesKeyIsTheOnlyOneRead` sets an old key and expects it ignored | **Right as a unit**: `ModuleSettingsSchema.resolve` never reads an old key; the refusal is the post-processor's, held by `aSiteThatSetsAnOldNameStopsTheStartAndNamesTheNewKey` and `ModuleSettingsSiteSetsTest`. **By a run:** `start server --server-arg=--drishti.var.scenario-window=400` exits 1 with "the server cannot start: settings names that were retired are still set; rename each one: `drishti.var.scenario-window` (set in commandLineArgs) is retired: use `drishti.module.banking.var.window`". **The same key in `config/engines/var.yaml` starts the server: F22.** |
| b | `LakeFileWritesTest` and `${lake.data.dir:./data}` | **Still there**: `lake/drishti-lake-host/src/test/java/com/ash/drishti/lakehost/LakeFileWritesTest.java:84` (`root: ${lake.data.dir:./data}/delta/risklake`). Only the prefix was changed. F31. |
| c | The connector `where` push-down "restored" | **It does not push down, here or on `main`.** `RowPredicate.pushdown()` has one reader in main code, `SourceShape.java:524`, which prints it for Health. A shaped connector answers `scan` with nothing (`ConnectorInstance.java:277`), reads every row of the kind and then tests it (`ShapedColumns.java:67-84`). What level 1.2 broke and the lane restored is the **list** (and the system a `where` fixes); `SourceShapeTest` holds that. F27. |
| d | The four goldens bit-identical and the headline asserted | **Yes.** The six golden data files have no differing line against `main`; the four tests pass on them in the complete run. `VarGoldenMatrixTest.headlineFigures` (`:113-119`) asserts 167,372,692 and 210,131,972 in three hierarchies on the committed set, and the API run gives the same on the demo lake. |
| e | F1 by a run | **Yes**, in the complete run: `ModuleHostServerTest.aModuleStoppedOrStartedAfterStartIsSeenByTheServersScreensAtOnce` previews a Sutra through `/api/v1/studio/preview`, unloads the module (leak check clean), gets 422 "the function 'greeter_greet' is off: its module 'greeter' is not running on this server", loads it again and previews again. The test is the real path, not bent. |
| f | Conflict markers, temp folders | No conflict marker in any tracked file (the guard `test_no_conflict_markers` passed). After the Java runs the clone is clean (`git status` empty; only `data/tmp/maven`, Maven's own). **After the Python, console and browser suites 19 folders stay in `/tmp`** (`tmp*` 15, `drishti-data-*` 2, `drishti-console-tests-*` 2; 2.3 GB with pytest's own): F34. I removed them. |

## 3. Part 1's findings, re-read at their lines

| # | State | Evidence at `10753f4db` |
|---|---|---|
| F1 blocker | **CLOSED** | `compute/drishti-rachana/.../LiveCompiler.java`: the one holder (`get()` is the current instance's compiler; `follow` drops what was built on an older one). No `ElCompiler` bean; `RowPredicate.java:50` and `DocPath` compile through it; the only `new ElCompiler(` left in main code are two benchmarks. Test: check e. |
| F2 blocker | **CLOSED** | the pin is Lake 0.9.1 = `336524b`, which holds `d94023b` and the maintain work; jars and wheel from one commit (`lib/lake-wheel.pin`); `VarApiTest` 15 of 15 in the complete run. |
| F3 | **CLOSED** | `live/DrishtiLiveTables.java:98-109` (a `q-` name without a source is `expired`, never a kind); `release` calls Rachana's `LiveTables.undeclare`; `FormApiTest.aSearchsTableThatLeftTheCapAnswersExpiredAndRunningTheSearchAgainOpensIt` passes. |
| F4 | **PARKED** by the owner | named in `G4_REHEARSAL.md:894` ("parked to the combined i8 by name"). It is named **only there**: no i8 list on the plan branch holds it (F39). |
| F5 | **CLOSED** | `EditService.submit` compares kind, record and items (`409 DRS-9303`, audited `edit-id-reused`); on the Data Protocol `ChangeSink.priorDigest` (`409 rdp.write.conflict`, `reused: true`); `KafkaTradeEditTest` passes. |
| F6 | **CLOSED** | `ArchitectureRulesTest.drishtiImportsOnlyLakesPublicPackages` (`:188-193`) has no exception list; a non-public Lake package is named by two **test** classes only (`DeltaDeletionVectorTest`, `ExternalFixtures`). Rows go through the loader (`lakehost.LakeRowsLoad`). |
| F7 | **CLOSED as I recommended; OPEN as an owner question** | `tools/lake/build.py:964`: `--engine auto|deltalake|java`. The owner had said to keep four engines: section 6. |
| F8 | **a to k CLOSED in main code; not in the tests; the sweep is not empty** | a: `render/1.2.0`, `web/embed/`, `drishti_geomap.js` are gone; b: `RetiredNames`; c: `was:` is `retired:` and refused (but F22); d to k as the log says. Left behind: the fixtures of e and the test of f (F23); l is not done; nine more leftovers (F28). The decision was "all leftovers removed". |
| F9 | **CLOSED (T3 to T10); T11 deferred by the owner** | guards `noMessageDigestOutsideTools`, `httpClientsStartInDrishtiTools`, `zipWritingOnlyWhereItIsToday`, `ToolsAdoptionGuardTest` (4) pass. The two Python workbook writers stay by the owner's word; `noJavaCodeRunsAPythonWorkbookWriter` holds that no server path runs them. |
| F10 | **CLOSED** | `tools/drillkit/lake_cache.py:69` (`loader_key`: the pinned version, the wheel pin and digest, the `lake-*` jars' CRCs); its test passes. |
| F11 | **CLOSED** | `lib/lake-wheel.pin` is the one place; `requirements.txt` says `drishti-lake==0.9.1`; the quickstart's three commands worked here as written. |
| F12 | **PARTLY** | the listed lines are fixed. Still open: every `github.com/drishti-platform/<product>` link (89 places) is dead: only the index repository exists in the organisation, the products are still `ajsinha/*`. More in F30. |
| F13 | **CLOSED** | the assertion is in the normal suite; the API run proves the demo lake. |
| F14 to F19 nice | **OPEN**, unchanged | `ComputeEngine` union; `DrishtiChangeSink.java:155` (`REFUSED` with the exception's text); `MappedIdMap` twice; `DrishtiLakeCli` usage, `DrishtiLakeLoad`, `drishti-testroots`, `tools/xlsx_before_after/` (4 files); `SseEmitter(0L)`; `URLDecoder` at `LiveTablesController.java:230`. None gates the tag. |

## 4. New findings

Paths are in `drishti` at `10753f4db` unless a repository is named.

### Blockers

**F20. BLOCKER (Lake 0.9.1). Compaction changes what readers see when commit order and run-id order disagree.**
- `drishti-lake` `java/lake-load/.../DeltaUpkeep.java:297-338` (`compacted`: the newest copies in one group, the older in a second) and
  `:139` with `LakeUndo.java:482-531` (`rewriteGroups`: **one commit** for both files); the reader's order is
  `lake-delta/.../DeltaTable.java:349-360` (`newestLast`: the commit that added the file, **then** the time in its largest `_load_id`).
  Iceberg behaves the same. A run id is the loader's start time, in **local** time with no offset (`LakeLoad.java:92-101`).
- Why: before compaction the commit decides, so the load committed last wins whatever its run id says. After compaction both files
  come from one commit, the tie falls to the run-id time, and the file of "older copies" wins when its loads started later.
- The run (each step its own JVM, the candidate's jars): three appends of one business date: `c` (`P-3`); `a` (`P-1`, `P-2` = 10, 20;
  `--run-id 20261121T131510.000-1-1`); `b` (`P-1`, `P-2` = 11, 21; `--run-id 20261121T131500.000-2-1`, committed last). Readers see
  **11, 21** before `lake maintain --compact` and **10, 20** after it, on **Delta and Iceberg**; the files store keeps 11, 21. No row is
  lost; the answer changes. With two files only, compaction has nothing to do and the fault hides; every kit case lets the loader
  make its ids in order, so `maintain/safety-compact` cannot see it.
- When it happens with nobody naming a run id: two loads of one date that overlap (the one that started first commits last); the inbox,
  which fixes one run id for every table of an archive and loads them one by one; the hour repeated when clocks go back; two loader hosts
  whose clocks differ. For Drishti: P&L vectors or positions sent again during the day, then the scheduled maintenance: VaR on the
  older vectors, no error.
- Fix (Lake's choice): two commits a date, the older copies first; or an order mark on the rewritten files that `newestLast` reads
  before the run id. Then a kit case with named run ids (this recipe) on every store and storage.
- **The design asked about** (the newest copies in one file, the older in one more; a second run changes nothing; an undo still finds
  the older copy; `dataChange: false`; an append-only table allowed) reads right and is what Delta's `OPTIMIZE` permits; my second probe
  (an id twice inside one load) changed nothing on any store. Besides F20, two remarks: the newest file ignores `--target-file-mb` and
  `file-rows` (`Integer.MAX_VALUE` at `:139`), so on a big date compaction and relayout can undo each other's work; and a whole date is
  held in memory while it is rewritten.

**F21. BLOCKER (Lake 0.9.1). On Delta, a promoted column that first arrives in a later load is dropped silently; Iceberg adds it.**
- The run (the candidate's jars): load 1 of a date with `promote: [DESK, BASE_PV]`; load 2 of the same date (replace) with
  `promote: [DESK, BASE_PV, MATURITY_DATE]` and a row carrying `MATURITY_DATE: 2026-01-15`. Both loads exit 0 with nothing rejected. On
  **Delta** the table's promoted columns are still `DESK, BASE_PV` and the value reads null; on **Iceberg** the column is there with its value.
- Why: it breaks the storage-parity rule, and it loses data with no word. `LOADER.md` says nothing of a new column and no kit case
  holds it. For VaR the optional columns `MATURITY_DATE`, `TRADE_STATUS`, `SCENARIO_SET_ID` (`VarIntake.java:53`) are read as columns:
  a feed that starts sending one after the table exists is ignored.
- It is what reddens Drishti: `VarInputsApiTest.maturedRecordsAreCountedByGrain…` adds a matured position and trade to an existing
  day through the fixtures, which since `9eeb453ed` write through the loader (`testkit.ManifestLoad`, `LakeRowsLoad`); the bank's VaR
  is then **736, not 636** ("matured records never enter the figure"), and the next test fails on the same state. Twice, the second time alone.
- Fix: one rule on every store, written in LOADER.md with a kit case: add the column (as Iceberg does and as Drishti's writer did), or
  refuse the load naming it. Never accept and drop.

**F22. BLOCKER (Drishti). A retired setting in a site's engine file is neither read nor refused.**
- `kernel/drishti-packs/.../ModuleSettingsEnvironmentPostProcessor.java:98-115` (`siteSets` skips every property source named
  `engines/<name>.yaml`: the site's `config/engines/*.yaml` as well as the jar's copy); `:55-61` (a manifest that cannot be read:
  a warning and no check at all).
- Why: on `main` the place of `scenario-window`, `confidence`, `es-confidence`, `threads`, `hot-dates` **is** the site's
  `config/engines/var.yaml`, under `drishti.var` (`main`'s file, `:26-47`). A site that upgrades with its edited file gets the
  defaults with no word: another VaR.
- The run: `scenario-window: 400` and `confidence: 0.95` added under `drishti.var` in `config/engines/var.yaml`; the server starts;
  `/api/v1/admin/modules/banking/settings` says window 250, confidence 0.99.
- Fix: skip only the **shipped** copies, as `EngineSwitches.shipped` (`:150-153`) already does for the switch names; a manifest that
  cannot be read stops the start. Test: a real start (`SpringApplication`) with a site engine file, not the post-processor called by hand.

**F23. BLOCKER (Drishti). The head is red; the last merges were never run whole.**

| Suite | Failing | Cause | From |
|---|---|---|---|
| `drishti-server`, 22 classes, 117 tests (`bi.mq.*` 14 classes, `LanguagesApiTest`, `FieldParamsApiTest`, `ModelAcceptanceTest`, `TransformInputsApiTest`, `BiAiTest`, `NetLiveBiTest`, `RunClusterTest`, `StatementApiTest`, `StatementRowsTest`) | "DRS-9600 unknown key 'was'", then "no dataset … is published" | F8 e took `was:` out of `TableDef` and the 22 shipped table files and touched **no test file**: `kernel/drishti-server/src/test/resources/bi/folders/*/bi/*.table.yaml` (ten files) and `MqEngineTest`, `DerivationTest` still write it | `75cac24dc` |
| `VarExplainApiTest.theOldExplainPathsAnswerForOneReleaseWithTheirOldBody` | 404 | F8 f removed the paths; their test stayed | `61a429d46` |
| `VarInputsApiTest` (2) | VaR 736 for 636 | F21 | `9eeb453ed` (fixtures on the loader) |
| console `test_settings_catalogue`, `test_docs_rot` (settings) | `CONFIGURATION.md` names `drishti.embed.search.max-results` and `drishti.embed.limits.searches-per-user-per-minute`, which nothing reads | the catalogue was not regenerated after the embed leftovers left | `0979bd248` |
| console `test_java_launch_guard` | `tools/lake/test_delta_interop.py:322`: `parametrize("engine", ["java", "deltalake"])` read as a java command built by hand | the guard's pattern, or the line | `9eeb453ed` |

The log's own words say why: "F8 a, c, e verification: tables (17) … banking (62) … **Not run here:** `ModuleSettingsTest` …", and the
two Lake re-pins carry no proof row. `ModuleSettingsTest` and `ModuleSettingsControllerTest` do pass in my complete run. Fix the five
rows; then the rule of the drill lessons: a full `clean verify` and both Python suites on the merged head before a gate is asked.

**F24. BLOCKER of the proof (Drishti). A fresh clone cannot pass `./mvnw clean verify`.**
- `plugins/drishti-plugin-delta/src/test/.../DeltaAfterMaintenanceTest.java:61-66` runs `tools/lake/maintain.py`, which since the Lake
  move runs "in the server's exec jar: none in `<home>/lib`". On `main` the tool was delta-rs alone.
- Why it matters twice: the fresh build is red; and on a developer's or a lane's tree the test silently runs whatever jar an **earlier**
  build left in `lib/`, so it proves the old loader (part 1's F10 again, in a test).
- Fix: the test belongs after the assembly (`drishti-modules-it`, whose tests already wait for the packaged jar), or the tool takes the
  reactor's class path (`LAKE_CLASSPATH`). Then one fresh-clone `clean verify` in the pre-drill check.

**F25. BLOCKER (Rachana 1.0.0). The retired level 1.0 is still the default of four public entry points; one drops text silently.**
- `drishti-rachana` `java/rachana-core/.../el/Parser.java:127-137` (the measure-function parse: the level 1.0 lexer, **no end-of-input
  check**), used by `MeasureFunctions.java:38,87`; `FormRules.java:127` and `CheckedMode.java:40,80` (`parse(text)`, and `require`, which
  checks at 1.2 and returns a tree parsed at 1.0); `Parser.java:165-167`. `ElSource` (122 lines, in `api-surface.txt`) is the reader of
  the removed `rachana upgrade` and has no caller. `Rachana.java:102-103,180` still says "1.0 unless raised".
- The run (the candidate's `rachana-core-1.0.0.jar`), `MeasureFunctions.expand` with `net(x) = x - 1`:
  `net(NOTIONAL) if A else 0` gives `NOTIONAL - 1`; `NOTIONAL if A else net(B)` gives `NOTIONAL`; `net(NOTIONAL) total rubbish )(` gives
  `NOTIONAL - 1`; `net(NOTIONAL ?? 0)`, `net(NOTIONAL) ** 2` and `net(1_000)` are refused though each compiles at 1.2.
- Why: Drishti expands **every** measure of a model that declares `functions:` (`kernel/drishti-server/.../bi/layer/ModelDefParser.java:117-125`;
  documented in `docs/guides/bi/TABLES_AND_MODELS.md:492`). The missing end check is on `main` too, but there the level had no trailing
  `if … else`; at the one level 1.2 that is the conditional, so a correct measure is cut to its first operand and a report shows a
  wrong number. No shipped model declares `functions:`; a site's may. The BI lowerings themselves pass `Grammar.LEVEL`
  (`NodeLowering.java:61`, `Expressions.java:87`, `FormulaLowering.java:107`, `Trees.java:40`, `LayerTables.java:237`): this path was missed.
- Fix: the macro parse reads at the instance's level with the end check; the one-argument `parse` and `require` read `Grammar.LEVEL`;
  `ElSource` and the 1.0 branches leave with the level, or the docs say why each stays. Cases for the inputs above; in Drishti a
  `BiApiTest` case of a model with a function and a conditional measure.
- **The unverified case** `protocol/write#a-reused-change-id-with-other-content-is-refused`: read against PROTOCOL 16 and 10.1 and
  run (section 1). Its expectation is what the specification says and what the live runtime answers. **It may be flipped** to
  `verified: true`, with its line in `conformance/1/VERIFIED.md`. Until that commit exists 1 of 2,589 cases is unverified and the G2
  rule (zero) is not met at `66b3c9c7`.

### Must fix

**F26. MUST-FIX. The old-name refusal is a stack trace.** The run of check a prints `java.lang.IllegalStateException` and its frames
around the two useful lines (`DrishtiApplication.main:105`). An operator's first sight of the rule should be the two lines and exit 1.

**F27. MUST-FIX. A connector's `where` is said to be pushed down and is not.** As check c. `docs/guides/DATA_LAKE_GUIDE.md:1457` ("pushed
down to the store (partition and file pruning where possible)"), `RowPredicate.java:41-43`, `SourceShape.java:41` and Health's `pushdown`
line say so; no store receives a filter from it. Not a regression and never a wrong row (every row is tested), but a table shared by
several trading systems is read whole for each system's connector. Give the list to the store's read (Lake's read API prunes: READ_API
2.7) with a test that counts the files opened, or take the claim out of the guide, the javadoc and Health.

**F28. MUST-FIX. Compatibility leftovers the F8 sweep still finds** (the decision: all removed; an old name stops the start).

| # | Where | What |
|---|---|---|
| m | `kernel/drishti-module-runtime/.../ModuleRuntime.java:248` | "console plugin format 1 is read for one release" |
| n | `kernel/drishti-server/.../pivot/PivotFunctionsController.java:218-226`, `application.yaml:400`, `PivotCellExplainer.java:38` | `POST /api/v1/pivot/explain`, "DEPRECATED, kept for one release" (F8 f removed the VaR and MR ones) |
| o | `kernel/drishti-server/.../cli/SutraCli.java:767`, `SutraCases.java:28` | one `.json` file a case, "deprecated (2.9.4)", still read (F8 j removed the Python twin) |
| p | `engines/memory-lake.yaml:25`, `EngineSwitches.java:111-125` | memory stores in "the old place, read for one more release with a warning" |
| q | `kernel/drishti-packs/.../PackPaths.java:84-98` | the old `./packs` folder: "This fallback ends in the next release" |
| r | `kernel/drishti-packs/.../edition/EditionEnvironmentPostProcessor.java:144-166`, `EditionConfiguration.java:136,178` | the server only **lists** `drishti.edition` and `drishti.branding.*` on Health; the console refuses them (F8 g): two answers to one retired name |
| s | `kernel/drishti-server/.../pivot/PivotScaleController.java:76,89`, `PivotInline.java:43`, `engines/pivot.yaml:21`, `ErrorCode.java:68` (`DRS-1024`), `SimmService.java:198`, `FrtbService.java:150` | readers and words of retired switch names: `PivotScaleController` reads **only** `drishti.pivot.enabled`, a name that now stops the start, so its own check can never say "off"; `pivot.yaml` says the old name "still works"; `DRS-1024` and the two `switchedOn()` are dead |
| t | `kernel/drishti-common/.../PackSamples.java:213` | `catalog.json` "of earlier releases: still read" |
| u | `VarIntake.java:369-373` (`REPORTING_CCY`, "a deprecated alias"), `AiProperties.java:24` (Ask's deprecated `endpoint`), `SourcesProperties.java:30` (connectors in the server's configuration, "deprecated") | three more aliases; the owner may keep them, by name |
| l | part 1's row | `PackStateStore`, `LoadStore`'s first-read import, the override migration, `FileUserStore`, `FileAuditLog`: "named for the release after Gate 4" in the log; the decision said G4 |

**F29. MUST-FIX (owner's word). 76 console tests are skipped as "parked to i6: writes to a lake (owner 2026-11-15)"**
(`test_expression_recipes` 72, `test_admin_lakes` 2, `test_admin_lake_maintain` 1, `test_python_runtime_answers` 1). The same on `main`,
so no test was lost in G4 (no `@Disabled` at the head, as `main`; 180 Python skip marks against `main`'s 187), but i6 has shipped and the
park names no later release: run them again or park them by name.

**F30. MUST-FIX. Documents that do not say what the code does** (with the docs wave, before the drill).

| Document | Line | What |
|---|---|---|
| README, QUICKSTART_DEVELOPERS and 87 other places | | `github.com/drishti-platform/<product>` links: only `drishti-platform/drishti-platform` exists; the quickstart itself clones `ajsinha/drishti-lake` at `:147` |
| `drishti-platform` `COMPATIBILITY.md` (the index repository) | the `i7b` row | Lake `0.9.x (v0.9.0)`, Elements `3.3.0 (v3.3.0)`: the pins are 0.9.1 and 3.3.1 |
| `docs/admin/CONFIGURATION.md` | 3395, 3400 | two embed settings nothing reads (F23) |
| `docs/architecture/adr/021-embedding-is-html-over-the-wire.md` | 29-41 | describes embed v1 (`/embed/v1/…`), deleted in this gate |
| `docs/architecture/DATA_ARCHITECTURE.md` | 3255 | names `tools/lake/filedate.py` (deleted) |
| `docs/QUICKSTART_DEVELOPERS.md` | section 4 | the map names `drishti-banking`, `drishti-compute-banking`, `lake-io`, `lake-delta`, `lake-iceberg`, `lake-load`, `drishti-elements` as modules of this repository |
| `docs/admin/BENCHMARK.md` | 187, 793 | `--engine drishti`, an engine that no longer exists (say in one line which engine the recorded run used) |
| `docs/guides/OPERATIONS_GUIDE.md` | 2535 | "older builds found once in `_drishti-lake/runs/*.json`" |
| DATA_GUIDE 1157, VAR_GUIDE 2008, DEVELOPER_GUIDE 2165, 2178, 2745, `plugins/drishti-plugin-kafka/README.md:548` | | examples with a relative default (`${DRISHTI_DELTA_ROOT:./data/delta/demo}`), against the explicit-paths rule |
| `pom.xml` | 109 | the comment names the candidate `7649778`; the pin is `336524b` |

**F31. MUST-FIX (small). `LakeFileWritesTest.java:84` keeps `${lake.data.dir:./data}`**, a relative default; `test_no_relative_paths`
does not read Java test sources, so nothing holds it. The same rule at `IdentityProperties.java:66` (`jdbc:sqlite:./data/identity/drishti.db`
as the default; ADMIN_GUIDE 494 documents it).

**F32. MUST-FIX. Tests decided by a clock.** `VarInputsApiTest.rewrite` (`:127-137`) waits `Thread.sleep(1500)` for "the connector's
refresh"; `AbandonedReadsTest` (33 reads against 27 under load) and `ReactiveApiTest.anEventReachesTheTriggersOnce` (the repeated
event not seen as a repeat, once in two runs) each failed once and passed once here. The second is an exactly-once promise: find out
whether the test or the product lost the first event.

**F33. MUST-FIX. The browser smoke is not stable beside other work.** Chromium: `test_live_grid_browser.test_a_row_opens_the_record_panel…`
reads "My changes (1)" right after the status text, before the tray updates (`test_live_grid_browser.py:149`; 15 of 15 alone). WebKit with four
workers: 7 of 123 fail on one cause, `/approvals/api/count` "due to access control checks": WebKit logs a fetch that a navigation
cancelled, and the page walk counts the line as a fault (`drishti_app.js:164` does catch it). Abort the count on `pagehide` or teach
the walk that one line; wait on the tray's own signal.

**F34. MUST-FIX. The Python, console and browser suites leave temp folders.** 19 folders in `/tmp` after one run of each (`tmp*`,
`drishti-data-*`, `drishti-console-tests-*`; 2.3 GB with `pytest-of-<user>`), and `data/dumps`, `data/qa`, `data/run`, `data/logs` in
the checkout. `drill.sh` cleans after itself; a plain `pytest` (what a developer and every lane runs) does not. Fixtures on `tmp_path`,
or one session finaliser.

### Nice

**F35. NICE.** `RowPredicate.test` answers false for any evaluation failure (`:130-141`): right for a row rule (fails closed), but for a
connector's `where` a bad expression hides every row with no word in Health. Count the failures.

**F36. NICE (Rachana).** The level's history still reads through Core's javadoc (`ElCompiler.java:29-33`, `CheckedMode.java:44,55`:
"at a language level (`1.0`, `1.1`)"). With F25, say what each remaining mention is for.

**F37. NICE.** The index repository `drishti-platform/drishti-platform` is **public** and holds the build scripts and the compatibility
table of a proprietary product. Probably meant; say so once in its README, or make it private.

**F38. NICE (Lake, Elements).** `tools/ci/check.py` and `tools/tck/tck.py` run Maven with `-o`, and `dependency:build-classpath` is not
fetched by the build itself: with a new Maven repository the kit fails with "No plugin found for prefix 'dependency'". Name the plugin's
version in the pom or drop `-o` for that call. `check.py` and Elements' `run_tests.py` leave `/tmp/pytest-of-<user>/pytest-N`; give
them a `--basetemp` under the build folder, as Rachana's lane does.

**F39. NICE (plan).** F4 is parked "by name" only in the rehearsal log. Put the line in the combined i8's list on the plan branch, with
part 1's two options.

## 5. Part 1's checklist

| # | Item | State |
|---|---|---|
| 1 | Cross-stack versions | the pins equal the candidates and each other (the table at the top); the tags `v1.0.0`, `v0.9.1`, `v3.3.1` do not exist yet (by design: they follow this gate); COMPATIBILITY is stale (F30) |
| 2 | Every suite, fresh clone, once | section 1: the four products green; Drishti red (F23, F24) |
| 3 | The goldens and the headline | check d and the API run: **proven** |
| 4 | F1 by a run | check e: **proven** |
| 5 | F2 by a run | `VarApiTest` 15 of 15; the pin holds `d94023b` |
| 6 | The late lanes read | Rachana 1.0.0: F25; BI at 1.2: the lowerings are on the one level, model functions are not (F25); Lake 0.9.1: F20, F21; the rest reads right (`TableProperties`: one mapping for the two product properties, append-only refuses what removes rows, the retention is vacuum's floor; keep-days counted on the calendar on every store; the public surface for hosts); test fixes and triage: no assertion found bent to pass, `IcebergMovedConnectorFileTest` holds the state and the relocate pair again |
| 7 | Each must-fix closed or moved in writing | section 3: F4 moved (log only), F7 an owner question, F8 not empty, F12 the links |
| 8 | Shims, final sweep | F28 |
| 9 | Docs | the quickstart's wheel and build steps, the demo lake and the server start ran here as written; `test_docs_rot` and `settings_catalogue` red on `CONFIGURATION.md` (F23); the other docs tests pass; F30 |
| 10 | Guards hold what the gate found | the F6 rule, the F1 test, the F3 test, the F10 key: all present and green. Missing: a real start for F22, a fresh-clone build for F24, F31 |
| 11 | Nothing left on disk or in git | check f and F34; `main` and every tag untouched; the 32 lane branches are merged and still on `origin` (delete by name at the release) |

## 6. Owner decisions: done, and one open question

| Decision | Done? |
|---|---|
| F9 T3 to T11 in G4; T11 waits for tools 1.2.0 (`Zips`) | yes; the four zip writers are listed in `zipWritingOnlyWhereItIsToday` |
| F8 all leftovers removed; old names stop the start naming the new key | a to k in main code yes; **F22 (engine files), F23 (the tests of e and f), F28 (m to u, l)** no |
| Renderer 1.2.0 and embed v1 deleted | yes (one renderer folder `static/render/3.3.1/`, one embed module `routes/embed_v2_routes.py`) |
| F4 parked to the combined i8 by name | in the log only (F39) |
| The Python xlsx writers stay; no server path calls them | yes, with a guard |

**Open question, recorded and not decided here:** `lake build` and `lake demo` have lost the Python `drishti` engine: `--engine` is now
`auto`, `java`, `deltalake` (`tools/lake/build.py:964`; the log's "Lake 0.9.1 final candidate" row). **The owner had said to keep four
engines.** Bringing it back means a writer in Python that commits through Lake's Java writer, which Lake 0.9.1 no longer offers a host
(`LakeCli` left the public surface). The owner decides: keep the removal (it matches "one Java way, one Python way"), or ask Lake for a
public entry and restore it.

## 7. What to tag

**Nothing from this gate but Elements.**

| Repository | Tag | Commit | State |
|---|---|---|---|
| `drishti-elements` | `v3.3.1` | **`20661f098a10d664d592dd8384442157436aba36`** | **golden: can be tagged now** (its suite green with nothing skipped, the release commit, the tree Drishti vendors) |
| `drishti-rachana` | `v1.0.0` | `66b3c9c7` + F25 + the case flipped with its `VERIFIED.md` line | not yet |
| `drishti-lake` | `v0.9.1` | `336524b` + F20 and F21, each with its kit case (the note on the `v0.9.0` release, "fixed in 0.9.1", is true only then) | not yet |
| `drishti` | the G4-golden head | `10753f4db` + F22, F23, F24 + the re-pin of the two products (poms, wheel pin) + F28 as the owner rules | not yet |

The re-check of this part is short: the fixes read; the probes of F20, F21 and F25 and the start of F22 run again; one fresh-clone
`./mvnw clean verify`, the two Python suites and the smoke on the new head; the suites of Rachana and Lake.

## 8. State left behind

The folder `~/.cache/drishti-lanes/fable-g4p2/` (five clones and a copy, the scratch Maven repository, the Python environment, a copy of
the install with the demo lake, the logs) is deleted, and so are the `/tmp` folders my runs left. No repository other than this plan
branch was written; `~/.m2`, the owner's stack, `/home/ashutosh/IdeaProjects/drishti`, `main` and every tag were not touched. The probe
servers ran on port 28480 and were stopped; nothing of mine is running.

---

# Part 2b and pre-drill review

Read on 2026-11-22, 06:23 to 06:50. **Nothing heavy was run** (owner rule: no duplicated testing): no Maven, no pytest, no browser. I read
the diffs, the code at its lines and the logs of `~/.cache/drishti-lanes/g4-int/`, ran `tools/drillkit/drill_readiness.py`, `git`, `grep`,
one `git merge-tree` (no tree touched) and one 12-line hash check of the vendored Elements manifest. The products were read in fresh
clones from `github.com/drishti-platform/<repo>` in my scratch folder (deleted). No sub-agent. Nothing was written but this file.

| Repository | Read at | Since part 2 |
|---|---|---|
| `drishti` | `wip/g4-rehearsal-rx` `4abfa33f3fb2936a72a8f56c314d62202700cbff` | 15 commits, 115 files (`10753f4db..4abfa33f3`) |
| `drishti-rachana` | `develop` `bfb6799b62246ef5486be12345d179f21d14d248` | F25 (`7bf00794`, merged `55723dcf`), then the organisation URL in two CLIs (`bfb6799b`) |
| `drishti-lake` | `develop` `2d22533c0580f9c25712f3b4630b6e2454b83bc6` | F20, F21 and three related faults (5 commits after `336524b`) |
| `drishti-elements` | tag `v3.3.1` = `20661f098a10d664d592dd8384442157436aba36` | tagged; `develop` has one later commit (URLs), not in the tag |
| `drishti-tools` | tag `v1.1.0` = commit `4d3567a` (the tag object is `b6fc22c`, the name RESULTS.md prints) | none |

## Verdict

**Products: GOLDEN. Tag Rachana and Lake now. Drishti at `4abfa33f3`: NOT YET, and the DRILL IS NO-GO on that commit.** Every blocker
of part 2 (F20 to F25) is closed at its cause; nothing new needs design. But the head cannot be the commit the drill runs, for reasons
that are certain by reading, not by opinion: it still builds version **2.9.11.i7a**; one test of it is red (shown by a hash); it cannot
fast-forward `develop`; and the drill tooling has never met the Lake wheel, so the Java lanes would skip or fail the tests that prove
the lake. All of it is one small pre-drill commit and four edits of the drill scripts: section 4.

| Tag | Commit | Why this one |
|---|---|---|
| `drishti-rachana` **`v1.0.0`** | **`bfb6799b62246ef5486be12345d179f21d14d248`** | `55723dcf` (what Drishti's integration ran) plus two text lines, the SARIF `informationUri` of the Node and Python `lint` (no Java file, no case, no test reads it). `55723dcf` is as good; `bfb6799b` ships the right address |
| `drishti-lake` **`v0.9.1`** | **`2d22533c0580f9c25712f3b4630b6e2454b83bc6`** | exactly this: `lib/lake-wheel.pin` names it and the wheel's SHA-256 (`23d2bd3f…e869`) is of its tree. No commit after it before the tag, or the pin moves |
| `drishti-elements` `v3.3.1` | `20661f098a10d664d592dd8384442157436aba36` | already tagged, at the commit part 2 called golden. Right |
| `drishti-tools` `v1.1.0` | `4d3567a` | unchanged |
| `drishti`, the head the drill runs | **`4abfa33f3` + the pre-drill commit of section 4** (no hash yet) | see D1 to D6 |

One condition on the two tags, because no log of their own suites at these commits is on disk (the lanes' folders are gone): the drill's
`prepare` builds each product from its tag with its tests (`clean install`), and for Lake runs `tools/ci/check.py` once (the kit is where
F20 and F21 are held; Maven alone does not run it). That is the single run of them, not a second one. A red product suite un-tags nothing
by itself but stops the drill.

## 1. Part 2's findings, at their lines

| # | State | Evidence |
|---|---|---|
| F20 blocker (Lake) | **CLOSED, with one gap left (L1 below)** | `drishti-lake` `java/lake-io/.../rows/LoadOrder.java:99-113` (the commit, then a file of older copies first, then the run's time); `lake-delta/.../table/DeltaTable.java:350-365` (`newestLast` reads the tag `lake.copies: older`, `:367-382`); `lake-delta/.../write/TableUpkeep.java` (`ranked`, `generations`: every rewrite ranks in the readers' own order); `LakeWriter.olderCopies`; Iceberg by `-older-` in the file name and always by sequence number; undo skips rewrite commits. Kit cases `maintain/compact-commit-order` (my part 2 recipe, four loads, compaction twice, undo, compaction again) and `retention/ttl-keeps-commit-order`. The three related faults (undo writing one file, undo taking a compaction for a replace, Iceberg undo keeping one copy by file name) are real finds of the lane |
| F21 blocker (Lake) | **CLOSED** | `LakeWriter.withColumns` (one metadata commit, nullable, at the end; a column-mapped or in-commit-timestamp table is refused naming the column), used by the loader (`DeltaTarget.java:157`), the manifest writer and the in-memory lake; `FilesWriter.java:92-104`; the Python path (`python/drishti_lake/delta.py:112-117`, delta-rs add-columns); `LOADER.md` "New columns"; kit case `load/new-promoted-column` |
| F22 blocker | **CLOSED** | `kernel/drishti-packs/.../module/RetiredNames.java:79-82` (`shipped`: only class-path resources and a module's shipped copy; a site's `config/engines/*.yaml` is checked), one rule for the start, the engine switches and a module loaded later; a manifest that cannot be read stops the start. Held by a real start: `ModuleSettingsTest.aRetiredNameInTheSitesEngineFileStopsARealStart`, and the new keys by a figure: `VarSiteEngineFileTest` |
| F23 blocker | **CLOSED by reading; proved only by the drill** | no `was:` under `kernel/drishti-server/src/test/resources/bi`, none in `MqEngineTest`, `DerivationTest`; `VarExplainApiTest` asks for 404; the two embed settings are out of `CONFIGURATION.md`. The head was again **not run whole** (scope cut by the owner): the drill is the proof, and D2 below is one red test already |
| F24 blocker | **CLOSED in the tree; open in the drill (D4)** | `testing/drishti-modules-it/.../it/DeltaAfterMaintenanceTest.java` (after the assembly, `LAKE_CLASSPATH` from the test class path, `@TempDir`). But `:44-48` **skips** when `lib/wheels` has no wheel, and the drill's Java lanes run in a copy that has none |
| F25 blocker (Rachana) | **CLOSED** | `java/rachana-core/.../el/Parser.java:160` (the one parse; every entry comes here), `:1126` (a token after a complete expression is `RCH-2101`); `MeasureFunctions.java:44`, `:180`; `CheckedMode.java:42-55`, `FormRules.java:127`; `ElSource` removed and logged in `api-removals.txt`; the 1.0 lexer and parser branches gone; 15 cases `el/measure-functions` (my four inputs among them). The reused-id case is `verified: true` with its `VERIFIED.md` row: no case below `conformance/1/` is unverified |
| F26 | **OPEN** | `DrishtiApplication.java:104-108` rethrows; no failure analyzer: the refusal is still a stack trace around its two lines. Not gating |
| F27 | **CLOSED** | `lake/drishti-lake-host/.../WherePushdown.java`, `SourcePlugin.columns(.., where)`, `TextEqualities`; `WherePushdownTest` counts the files read (1 of 3) and compares the answer with the whole read; PostgreSQL and DuckDB by `WHERE`; a real fault found by the proof (DuckDB bound the values in the wrong order) |
| F28 | **m, n, p, q, r, s, t, u (REPORTING_CCY) CLOSED; o and l parked** | o (`SutraCli.java:767`) and l are parked "by name" in `G4_REHEARSAL.md` only: **not in `I8_CARRY_IN.md`** (F39 again). `AiProperties.java:24` (Ask's deprecated endpoint) stays unnamed |
| F29 | **OPEN** | the "parked to i6" skips are still in `drishti-console/tests` (`var_stack.PARKED_I6`); no owner word, no carry-in row. The drill's rule is zero skips but those in `tools/drill-known.txt`: decide before the drill |
| F30 | **OPEN but the links** | the organisation exists and every product resolves (row 1 closed). Still there: the index repository's `platform.yaml` and `COMPATIBILITY.md` (`lake: v0.9.0`, `elements: v3.3.0`); ADR 021 (embed v1, 3 places); `DATA_ARCHITECTURE.md` (`filedate.py`); QUICKSTART section 4 (`lake-io`, `lake-delta`, `lake-iceberg`, `lake-load`, `drishti-banking`, `drishti-elements` as modules here); `BENCHMARK.md` (`--engine drishti`, twice); `OPERATIONS_GUIDE.md` ("older builds"); the six relative defaults; `pom.xml:109` (names `7649778`, the pin is `2d22533`). The docs wave the owner put before this drill has not been done: D6 |
| F31 | **half** | `LakeFileWritesTest.java:84` is `${lake.data.dir:/srv/drishti}` (right: a lake file may not name a host setting). `IdentityProperties.java:66` still defaults to `jdbc:sqlite:./data/identity/drishti.db` |
| F32 | **OPEN** | `AbandonedReadsTest`: the bound is now `before + 4 + cores` (still far from a thread a read; not bent). `VarInputsApiTest.java:135` keeps `Thread.sleep(1500)`; `ReactiveApiTest` was not looked at; and the new end-to-end test adds `time.sleep(3)` (`tools/test_lake_tools_e2e.py:444`) |
| F33 | **CLOSED by reading** | `drishti_app.js:169` (the count aborted on `pagehide`); the tray's own text awaited. The proof is the drill and the WebKit step |
| F34 | **CLOSED** | `tools/tmpguard.py` (one folder a session under `tempfile.gettempdir()`, so under the drill's `TMPDIR`), in both conftests. `drill.sh` does not set `DRISHTI_TMP_STRICT=1`: nice |
| F35 to F38 | **OPEN** (nice) | unchanged; of F36 the javadoc of `Facade.java:102` still lists `"1.0"` |
| F39 | **CLOSED for F4** | `I8_CARRY_IN.md` row 1. Add o, l, F29 and L1 there |

**The check asked for, `VarInputsApiTest`:** the change (`26fca5943`) is two fixture lines, `VECTOR_ID` and `SCENARIO_PV_ID` of the matured
copies; **no assertion moved**. It also corrects me: the two red tests of part 2 were not F21 but the fixture writing two records under one
id, which F20's rule (one id, one record) now shows. F21 itself was real (the probe). **But no log shows the 10 of 10**: `targeted.log`
(05:49) holds 8 of 10, `targeted2.log` (05:55) ran only `ModuleHostServerTest` and `PackagedVarGoldenTest`. The same for the end-to-end
module: the last log, `e2e6.log` (06:21), ends **1 failed, 27 passed** (the inbox test: `data get` answered 502 "no such folder" after the
table's only date was deleted), the head was committed a minute later, and the "1 passed after" has no log. Both are claims until the
drill; I count them as watch items, not as red.

## 2. The three judgements asked for

**L1. The gap the Lake lane left. MUST-FIX in Lake 0.9.2; does not block the `v0.9.1` tag; must be written down first.**
- What the code does: `DeltaTable.java:356` asks the log for the commits only when a date's files come from two runs
  (`LoadOrder.needsCommits`), and `addedAt` (`:402-441`) gives a file whose commit the log no longer holds version 0. Then the order is the
  run id's time, the load id, the file's modification time, the path.
- When that is wrong: (a) two appends of a date whose commit order and run-id order disagree (F20's own condition), not rewritten since,
  **after the log has dropped their commits** (Delta's default is 30 days, at a checkpoint): the answer changes on that day, with no
  word and no maintenance run. And compaction does not settle it: a date of exactly two files is left as it is
  (`DeltaUpkeep.java:120-122`, "already as few files"), so its files never get the older-copies mark; nor is a date whose files are over
  `--target-file-mb` (`:96-103`). (b) two commits of one date under **one** run id (`--run-id` used again): the commits are never read;
  the modification time decides, then the path, which is random.
- Why not a blocker: it is narrower than F20 (it needs F20's condition and a month), it is no regression (0.9.0 and `main` ordered by
  run id always), nothing can reach it before 0.9.2, and the tag commit may not move (the pin). Why not nice: it is the same class,
  a number that changes with no word, and `LOADER.md:112-114` says "a run id is a name, never an order" without the exception.
- Before the tag is announced: the limit in the `v0.9.1` release text (and the correction of the `v0.9.0` note) and a row in
  `I8_CARRY_IN.md`. Fix in 0.9.2, first Lake item of i8: the order written where it survives the log (the mark set when a date gets its
  second commit, or maintenance settling every date of two commits before the log may drop them: an add of the same path with a tag is
  a legal Delta commit, no file rewritten), the reader **refusing to guess** (a read problem naming the date) when two unmarked files
  share an id and neither commit is known, `needsCommits` no longer trusting equal run ids, and a kit case with the log cut.
  Until then a site that appends keeps `lake.retention-hours` long.

**L2. Retention parity. MUST-FIX before the drill (small), or parked by the owner by name. It is a Drishti fault, and Lake keeps two rules.**
- `tools/lake/maintain.py:110,121-122` runs `LakeMaintain --keep-days` (the calendar rule, `LakeMaintain.java:278-292`: `--as-of` and
  the N business days before it; one behaviour on Delta, Iceberg and files, kit case `maintain/keep-days-and-drop-guard`).
  `tools/lake/maintain_iceberg.py:36,143-144` runs the older `IcebergMaintenance --keep-days`, which is
  `guard.keepFromNewest(days, keep)` (`IcebergMaintenance.java:418-419`): the newest N dates the table holds.
- So `keep-business-days: 250` leaves 251 dates on Delta and 250 on Iceberg, and after a week without loads they differ by more. A
  date is deleted on one store that the other keeps. It is as on `main`, so no regression, but the owner's rule (11-14) is one
  behaviour with a parity test, and here the new test **asserts the difference** (`tools/test_lake_tools_e2e.py:258`:
  `2 if fmt == "delta" else 1`).
- Fix: `maintain_iceberg.py` sends retention through `LakeMaintain --format iceberg` (Lake 0.9.1 has it; no Lake change, no new tag);
  the test asks 2 on both; `test_maintain_iceberg.py:111,174` follow. Lake 0.9.2: `IcebergMaintenance`'s own keep-days leaves (one way
  per job): carry-in row.

**L3. Elements `v3.3.1` already tagged at `20661f0`: right**, it is the commit part 2 called golden and the tree Drishti vendors. One
consequence, D2: the copy in Drishti must be that tree, byte for byte, and at the head it is not.

**Engines:** `--engine auto|deltalake|java` (`tools/lake/demo.py:919`, `build.py`) is as the owner settled. `BENCHMARK.md` still names
`--engine drishti` (F30).

## 3. New findings

### Blockers of the drill (each certain by reading)

**D1. The head builds 2.9.11.i7a.** `.mvn/maven.config:1` is `-Drevision=2.9.11.i7a` (the integration log: "Building drishti-modules-it
2.9.11.i7a") while `README.md:15` and `RELEASE_NOTES.md:16` say i7b. The plan's step 0 is "version bumped". The drill proves the jar it
ships: the version first.

**D2. One test is red at the head: the URL sweep edited the vendored Elements copy.** `3628e3744` changed
`drishti-console/web/static/vendor/drishti-elements/3.3.1/README.md`; its SHA-384 no longer equals `drishti-elements.json` (checked: 599
files, 1 mismatch, `README.md`), and `test_elements_vendored.py:44-50` (`test_nobody_edits_the_copy`) fails on exactly that. Restore the
file from `41134d494`; the sweep leaves vendored trees alone. (Rachana's own sweep touched no vendored file.)

**D3. `develop` cannot fast-forward to the head.** `origin/develop` holds `12cd36f08` (its own URL sweep) that the head lacks, and the
merge conflicts in 10 files (`BENCHMARK.md`, `ELEMENTS_IN_DRISHTI.md`, `CLI_REFERENCE.md`, `DEVELOPER_GUIDE.md`, `EMBED_GUIDE.md`,
`LIVE_RESULTS_GUIDE.md`, `NOTEBOOK_AND_PYTHON.md`, `PIVOT.md`, `VENDOR.md`, `ELEMENTS_SPLIT_RUNBOOK.md`; `IMPLEMENTATION_PLAN.md`
deleted here, changed there). Released as it is, `main` would be a merge nobody drilled. Merge `origin/develop` into the head now (the
head's side; then D2's file again), so that `develop` and `main` move to the drilled commit itself.

**D4. The drill has never met the Lake wheel.** Nothing in `tools/drill.sh`, `i7b-drill.sh` or the readiness check knows `lib/wheels`.
- `i7b-drill.sh` step 4 runs `pip install -r drishti-console/requirements.txt` before any wheel exists: `drishti-lake==0.9.1` is on no
  index (`requirements.txt:19-20`), so `prepare` dies at "venv".
- The Java lanes run in `git archive HEAD` copies (`drill.sh:441-449`) that get the exec jar and the modules, **not `lib/wheels`** (git
  ignores it). There `DeltaAfterMaintenanceTest` skips (F24's own test, and a skip the pass criteria forbid), and every Java test that
  starts a Python lake tool with the machine's `python3` (`PackagedServer.java:90`; `tools/lake/build.py:68` imports `lakewheel`) has no
  `drishti_lake`: the packaged goldens of the java21 lane. The integration lane itself needed `LAKE_PYTHON` for `PackagedVarGoldenTest`
  (`targeted2.log:1`).
- Fix: `prepare` builds the wheel from the Lake **tag** clone into `<clone>/lib/wheels` (`uv build --wheel`), runs
  `tools/lake/lakewheel.py` (exit 1 unless it is the pinned SHA: this is also the proof that the tag gives the pinned bytes), then the
  venv; `copy_verify` copies `lib/wheels` beside the jar (one line, in the pre-drill commit, before the freeze); readiness checks it.

**D5. The drill script and its plan are of 2026-11-21.** `i7b-drill.sh`: `RACHANA_TAG` v0.12.0 and `LAKE_TAG` v0.9.0 as defaults,
`SRC=https://github.com/ajsinha`; `drill_readiness.py:37-38,144` the same; `I7B_DRILL_PLAN.md` says Elements 2.4.0. Readiness asks
Playwright 1.64.x, `requirements-test.txt:18` says `>=1.45`, and a fresh venv installs what is newest (the G4 lane's has 1.65.2): the
second readiness of `prepare` then dies, or the browsers and the snapshots are of a version nobody chose. Pin one version in the file
and ask for that one. And `prepare` step 5 runs the upstream modules' tests (`verify -pl testing/drishti-modules-it -am`) and both tools
lanes, which the drill then runs again: against the owner's rule of today. `prepare` = build all, the gate; then the smoke; then the
lanes, once.

### Must fix

**D6. The documents the drill follows do not match the code** (the docs wave, before the drill, in the pre-drill commit):
- `QUICKSTART_DEVELOPERS.md:100-103` installs the console's requirements in section 1.3; the wheel they need is built in section 2
  (`:141-150`). The first command of a new developer fails. The wheel first.
- `:86-90` and `:122-124`: `python3 clone_all.py` and `python3 build_all.py` with no argument exit 2 ("give --release <name>"). And
  `clone_all.py` clones into the folder **above** the index repository: cloned into `~/IdeaProjects` as written, the repositories land
  in `~/IdeaProjects/drishti`, not in the `~/IdeaProjects/drishti-platform/` of the picture (`:76-79`). **On this machine that is the
  owner's checkout: nobody runs `clone_all.py` here without `--parent <scratch>`.**
- `:127`, `:144`: `build_all.py` "skips what is already there", "builds the Elements bundles", builds the wheel. It does none
  (`build_all.py`: one `mvnw clean install` a product, then **`mvnw clean verify` of all of Drishti**, the whole suite, not
  `drishti.py build all`). Make the script do what the page says (index repository only) or the page say what the script does.
- `drishti-stack/` is in the layout (QUICKSTART `:78`, IDE_GUIDE `:604,706`) and is no repository of the organisation.
- `DEPLOYMENT_AND_QA_GUIDE.md:377`: "the release bundle carries" the wheel. `make_bundle.py` archives the tags; `lib/wheels` is ignored
  by git: the bundle has no wheel. Carry it, or say build it.
- F30's rows above.
The commands of `tools/drishti.py` named in the three guides all exist (43 groups), the organisation URLs are right, the install order
(tools, Rachana, Lake, Drishti; `java/mvnw` in each product) is right.

**D7. After the tags, before the bundle (index repository):** `platform.yaml` and `COMPATIBILITY.md` to `lake: v0.9.1`,
`elements: v3.3.1`; `build_all.py` as D6; then `make_bundle.py --release i7b` (it needs `v2.9.11.i_7b`, so after the release).

### Nice

**D8.** `drill.sh` could set `DRISHTI_TMP_STRICT=1` (the guard was built for it). **D9.** Three stale folders in
`~/IdeaProjects/drishti-drill-copies` (readiness WARN): delete before the start. **D10.** `lib/lake-wheel.pin` says the ref "moves to
the tag" once tagged: leave the commit for this release (the same bytes; a change after the drill is a change nobody drilled) and move it
in i8.

## 4. DRILL: NO-GO on `4abfa33f3`. GO on the commit that holds this list

**One pre-drill commit on `wip/g4-rehearsal-rx`** (docs and small code; each verified by its own files, not a suite):
1. D1: the version, `.mvn/maven.config`, and what `test_one_version.py` holds.
2. D2: the vendored `README.md` back to the tag's bytes (the manifest check says 0 mismatches).
3. D3: `origin/develop` merged in (`git merge-base --is-ancestor origin/develop HEAD` and the same for `origin/main` both true).
4. D4: `copy_verify` copies `lib/wheels`.
5. L2: Iceberg retention through `LakeMaintain` (or the owner's park by name, and the test says why it differs).
6. D5: Playwright pinned to one version.
7. D6: the documents. F29: the owner's word on the parked skips (run, or named in `tools/drill-known.txt`).
8. `I8_CARRY_IN.md` (plan branch): L1, L2's Lake half, F28 o and l, F29.

**Drill scripts (plan branch and `~/drishti-bench-scratch`, not the drilled commit):** tags `v1.0.0` and `v0.9.1`, the organisation URL,
the wheel step and `lakewheel.py`, Lake's `tools/ci/check.py` once, `prepare` without the repeated lanes (D5), the readiness defaults and
its Playwright and wheel checks; the plan's stale names; D9.

**Then, in this order:** tag Rachana `v1.0.0` at `bfb6799b` and Lake `v0.9.1` at `2d22533` (with L1 in the release text); readiness
must say READY but for the venv (today: 29 PASS, 1 WARN, 4 FAIL: the venv of a fresh clone, Playwright 1.63.0 against 1.64.x, and the
two tags that do not exist yet; the machine itself is ready: 385 GB, 50 GB free memory, load 0.2, every port free, WebKit starts);
fresh clone at the pre-drill commit; `prepare`; the smoke (VaR 167,372,692, ES 210,131,972); the lanes once; the WebKit step
(`DRISHTI_BROWSER=webkit`, read by `browser_engine.py`; `test_page_walk_browser.py`, `test_layouts.py`, the four
`test_snapshots_*_browser.py` exist). The freeze starts at the pre-drill commit. I need to read only that commit's diff to turn this
into GO; nothing here asks for a second review of the rest.

**What to watch in the drill, because no log holds it:** `VarInputsApiTest` (10), `tools/test_lake_tools_e2e.py` (18, the inbox test
with its `sleep(3)`), `test_elements_vendored.py`, `DeltaAfterMaintenanceTest` (run, not skipped), the whole console suite and
`drishti-server` (last run whole at `10753f4db`: 121 and 3 red, fixed since by reading only).

## 5. State left behind

Nothing running, nothing built. My five scratch clones are deleted. `/home/ashutosh/IdeaProjects/drishti`, the owner's stack, `~/.m2`,
`main`, every tag and every repository but this plan branch were not written.

---

# Pre-drill: final (2026-11-22 07:10)

Read: the diff `4abfa33f3..00101ff1e` of `wip/g4-rehearsal-rx` (36 files of code and pages, then one commit of three PDFs), the five logs in
`~/.cache/drishti-lanes/g4-pd/`, `~/drishti-bench-scratch/i7b-drill.sh`, `tools/drill.sh`, the tags on GitHub. Nothing was built or run.

## 1. The must-do list of part 2b

| Item | State | Evidence |
|---|---|---|
| D1 version | done | `.mvn/maven.config`: `-Drevision=2.9.11.i7b`; README names the release, not a number |
| D2 vendored README | done | the tag's bytes are back (`941b73dd5`); `vendored.log`: 1 passed |
| D3 one line of history | done | `origin/develop` and `origin/main` (`9ce7c3170`) are both ancestors of `00101ff1e`: `main` fast-forwards |
| D4 the wheel in the drill | done, one hole (S1) | `drill.sh:448` copies `lib/wheels` into each Java copy; `i7b-drill.sh` step 3 builds the wheel from the `v0.9.1` clone, step 4 installs it before the requirements |
| L2 one retention rule | done, one test left behind (**B1**) | `maintain_iceberg.maintain` goes through `maintain.maintain(fmt="iceberg")` (`LakeMaintain --format iceberg`); the e2e test asks 2 dates on both stores; `e2e.log` 17 passed, 1 skipped (duckdb) |
| D5 Playwright, script | done | `playwright~=1.64.0`; the script: `v1.0.0`, `v0.9.1`, `v1.1.0`, the organisation URL, Lake's `check.py --skip-build` once (the option exists at the tag), `prepare` ends at `build all` |
| D6 the documents | done but M1 | QUICKSTART (wheel first, `--release i7b`, the folder above), DEPLOYMENT step 1 and 6, IDE guide; the three PDFs regenerated (`00101ff1e`) |
| F29 | **the owner's word is half kept (O1)** | the marker is off four files (`test_admin_lake_maintain`, `test_admin_lakes`, `test_python_runtime_answers`, `test_expression_recipes`) |
| Carry-in | done | F28 o and l, the Lake order limit, Glue/REST maintenance in `I8_CARRY_IN.md` |
| Tags | right | `v1.0.0` = `bfb6799b`, `v0.9.1` = `2d22533c` (the pin's `ref=v0.9.1` is the same bytes), `v3.3.1` = `20661f09`, `v1.1.0` = `4d3567a2` |
| The two watch items | now on disk | `var-inputs.log`: `VarInputsApiTest` 10 of 10, the sleep replaced by a wait on both tables' versions; the inbox test waits on the real condition |

## 2. New, in the diff

**B1. BLOCKER: one tools test is red by reading.** `tools/lake/test_maintain_rows.py:64` and `:67`
(`test_iceberg_gets_the_options_and_a_table_without_a_block_none`) call `maintain_iceberg.command(...)`, which `cb06daab2`/`14cb73a07`
deleted (the module now has no `command`): `AttributeError` in the tools lane. `lake-unit.log` (11 passed) ran `test_maintain_iceberg.py`
and `test_undo.py`, not this file. Fix: the test builds the command with `maintain.lake_command(..., fmt="iceberg")` as
`test_maintain_iceberg.py:86` does (`--ttl`, `--on`, `--max-rows` are in `lake_command` for every format); run that one file.

**S1. Drill script (not in git): the wheel proof does not stop the drill.** Step 3, `( cd "$ROOT" && python3 tools/lake/lakewheel.py ) | tee -a ... || die`:
the pipe's status is `tee`'s and the script has no `pipefail`, so a wheel that is not the pinned one goes on (pip then installs it: the
requirements hold only the version; readiness marks it WARN). Add `set -o pipefail` at the top, or drop the `tee`.

**O1. Owner: about 30 browser tests still skip.** `PARKED_I6` (`pytest.mark.skip`) is still on `test_admin_lakes_browser` (1),
`test_admin_lake_maintain_browser` (2), `test_public_sector_browser` (6), `test_bi_multi_dataset_browser` (6),
`test_bi_reports_cross_browser` (8), `test_var_explorer_browser` (6), `test_var_currency_browser` (1), and TEST_HARNESS still lists them as
parked. I could not count the 76 without collecting (the recipes are parametrised), so: either the 76 were only the four files above and
these stay parked by the owner's word (one row in `I8_CARRY_IN.md`, since the plan's rule is zero skips), or they were meant too and the
marker comes off. One sentence from the owner; not a code finding.

**M1. Must fix, pages only (same commit as B1, no test reads them):** after L2 these still describe the retired path:
`OPERATIONS_GUIDE.md:2662-2663` ("run by `IcebergMaintenance`", "the table's newest N business dates") and its Glue example below
(a REST-catalog lake is now refused); `CONFIGURATION.md:818` (`iceberg-settings` "passed to maintenance as `--set`");
`deploy/aws/iceberg/maintain.sh` usage ("ICEBERG_CATALOG=glue maintains the tables of the Glue databases");
`DATA_GUIDE.md:3224`, `DATA_LAKE_GUIDE.md:326`, `plugins/drishti-plugin-iceberg/README.md:321,382`.
`IDE_GUIDE.md` section 10: "Save Workspace As into `platform/` itself" lost half its name. Regenerate the PDFs of the guides touched.

**Not verified (no test in the drill reaches it):** Iceberg maintenance of an S3 lake now gives the store's settings as `AWS_*`
environment names only (before: `--set s3.endpoint=...`). Whether `LakeMaintain --format iceberg` reads them I did not check; say it in
the Glue carry-in row or prove it in i8. Nice: `test_admin_lake_maintain.py:39` and `drishti_admin-lake-maintain.js:156` (`t.orphans`),
`maintain.py:405` still name what is gone.

Everything else in the diff reads right: `orphan-hours` refused in the file, the lake entry and the form, naming `vacuum-hours`; undo
through `LakeUndo` for every store; the `bothTablesChanged` wait; `copy_verify`.

## 3. DRILL: NO-GO on `00101ff1e`. GO on its child that holds B1

Shortest list:
1. **B1**: `test_maintain_rows.py` uses `maintain.lake_command(fmt="iceberg")`; that file green (one pytest file, seconds).
2. **S1**: `set -o pipefail` in `i7b-drill.sh` (before the freeze).
3. **M1** in the same commit (pages, PDFs).
4. **O1**: the owner's sentence on the ~30 browser tests still parked (run, or a carry-in row).

The drill then runs that commit by its hash (`i7b-drill.sh <hash> prepare`, never the branch name), products from `v1.1.0`, `v1.0.0`,
`v0.9.1`, the wheel from the `v0.9.1` clone. If the commit touches only `tools/lake/test_maintain_rows.py`, pages and PDFs, it needs no
further review from me: it is GO as it stands. Readiness today (06:48): 31 PASS, 2 WARN (two `g4pd` worktrees, three stale drill copies:
remove both), 2 FAIL that `prepare` step 4 cures (the venv, Playwright 1.64.x).

Nothing running, nothing built; only this file was written.

---

# Patch gate 1.0.1 / 3.3.2 (2026-11-22 14:45)

Read only: the two diffs, the code they touch, the committed schema run through `jsonschema` (a few seconds); no Maven, no Node. The lanes'
test logs were not found on this machine (`~/.cache/drishti-lanes` holds none for these branches), so the test counts below are the lanes'
own report, not re-read by me.

## 1. Rachana 1.0.1 (`fix/1.0.1` `94775ac7` against `v1.0.0` `bfb6799b`): NOT YET, one small fix

**What is right.** One commit. The only main code changed is `RachanaSchema.java` (reached only from `Rachana.schema()`); every other
source line is the version text (`Cases`, `Facade`, `__init__`, the poms, the packages). No parser, binder, compiler or API line moved, no
conformance case changed: no language change. The schema is now complete: `schema_cases.py` run here on the commit's tree says
`351 Sutras checked, 0 do not validate`. The top-level keys come from `Grammar.LEVEL_KEYS`, a panel list item is "a panel with an id, or a
use" (a `use` closed with `additionalProperties: false`), an unknown top-level key, kind, trigger, state type, pivot mode, emit trigger, a
panel without id or kind, a data entry with two forms and an action without `do` are all refused (18 hand-made bad Sutras tried).
The guard's exclusions are justified: of 360 candidates 6 are left out as Drishti-profile constructs (the schema is Core's) and 3 are in
`LENIENT`, each with its reason (the compiler ignores the value). `spec_files.py` keeps the file equal to the generator.

**P1. Must fix: the schema became looser than the compiler in two places 1.0.0 had right.** All 285 failing conformance cases were run
through both schemas: 89 that 1.0.0 refused now validate. 86 of them are right to validate (1.0.0 refused them only for the missing keys;
their problems are semantic, no schema can see them). Three are real:

| Case (`conformance/1/problem/strict-sutra.yaml`) | Compiler | 1.0.0 schema | 1.0.1 schema |
|---|---|---|---|
| `table-rows-5` (`rows: 5`) | RCH-2029 | refused | **accepted** |
| `table-limit-negative` (`limit: -5`; `0` too) | RCH-2029 | refused | **accepted** |
| `table-limit-many` (`limit: many`) | RCH-2029 | refused | **accepted** |

Cause, both in `RachanaSchema.panelSchema`: (a) every expression option now takes `string | number | boolean`, but
`PanelOptions.LIST_EXPRESSIONS` (`rows`, `each`, `nodes`, `edges`, `source`, `children`) and `where` are text only in the compiler (`each: 5`
on tabs validates too); (b) `forKind(TABLE, "limit")` lost `minimum: 1` and takes any string, while `PanelOptions.problem` takes a whole
number of 1 or more, or text that holds `ctl.`.
Fix (a few lines, then `spec_files.py --write`): keep `text(EL)` for `LIST_EXPRESSIONS`; the table `limit` is
`anyOf: [{integer, minimum 1}, {string, pattern "ctl\\."}]`. That refuses all three again.

**P2. Must fix with P1: the guard looks one way only.** It proves "compiles, so validates"; nothing proves "the schema still refuses".
Add to `schema_cases.py` a short `MUST_REFUSE` list (the three cases above at least) that fails when one validates. Without it the next
completeness fix can loosen the schema again unseen.

**Notes, not blocking.** `LENIENT` "only shrinks" is a comment; nothing enforces it (a count asserted in the script would). The Java guard is
skipped on a machine without python3, PyYAML and jsonschema (it says so): the drill machine must have them, or the guard does not run where
it counts. Already loose in 1.0.0 and unchanged: an unknown panel option validates (`properties` without `additionalProperties`).

**Verdict: NOT YET.** Tag `v1.0.1` on the child commit that holds P1 and P2, with `schema_cases.py` and `spec_files.py` green (seconds; the
lane's verify once). If that commit touches only `RachanaSchema.java`, the regenerated `spec/1/sutra.schema.json`, `schema_cases.py` and
the CHANGELOG, it needs no second gate from me.

## 2. Elements 3.3.2 (`fix/3.3.2` `9d704c4` against `v3.3.1` `20661f0`): TAG

Two commits, drishti-views only (the other bundles differ by the version line). Read: `drishti_views-controls.js` (new, 320 lines), the
paint, element, cells and sheet diffs, the budget test.

- Drawn with drishti-inputs' own components (Select, MultiSelect, Switch, ButtonGroup, DatePicker, DateRangePicker, WhatIf; search and
  numbers are the library's field): no bespoke widget. Holders are in the first paint with a kept height, so nothing moves.
- No inline handler or style is written (`h()` with attributes and classes; colours and spacing are tokens); the browser test runs under
  a strict CSP and asserts no violation.
- A pick: `takeControl` (value kept, `control-change` emitted), the change effects, then `bind` with every `ctl.<id>` the reader set and
  `req.panels` (null for the whole view); only the reading panels are drawn again, `aria-busy` while waiting, a polite status region
  after, focus kept. A failed bind clears `aria-busy`, says so and emits `view.bind`. Typing waits 250 ms; timers and components are
  disposed with the view.
- Budget 255,000 to 276,000 for 263,045: the file's own rule (size plus about 5%), with the reason written beside it.

**Restoration judged: `selects` and `remember` WERE embed v1 behaviour.** The script embed v1 ran inside its shadow root
(`drishti-console/web/static/js/drishti_controls.js`, 508 lines, last changed `7bfb675c6`) does both: `data-selects` (lines 52, 417 to 441:
a click or Enter on a row or bar sets the control, again clears it) and `remember` (lines 316, 477 to 481: `localStorage`, with
`options.scope` documented for the embed). Both are live in the language and the contract (`on-select`, a control's `remember`;
`ViewModel.selects`, `ControlView.remember`), and Drishti ships `on-select` in `counterparty-risk/netting-set.v1`. The painter in 3.3.2
reads neither. So this is an incomplete restoration.

**It does not block 3.3.2.** The patch is additive (3.3.1 drew no control at all), what the drill found is cured, and both gaps degrade
quietly and safely: a row click that used to filter does nothing, a choice is not kept between visits; no number is wrong, nothing throws.
Neither was ever tested through the embed (`test_embed_controls_browser.py` has four tests, none of them; only the console page's
`test_controls_browser.py` covers them). It must follow, and be written down so it is not lost:

1. **3.3.3 (before the i7b release is called embed-complete, or one row in `I8_CARRY_IN.md` by the owner's word):** `selects` (row and bar
   click and Enter set the control through `handle.change`, again clears, the selected row marked) and `remember` (through the existing
   `options.remember {get, set}` hook, never the painter's own `localStorage`), each with a node and a browser test.
2. With the tag: one line in the 3.3.2 CHANGELOG entry that `selects` and `remember` are not read yet. Today the entry reads as complete.

**Note for the Drishti side.** Drawing is on by default: a host that draws its own controls and upgrades to 3.3.2 gets them twice unless it
passes `controls: false` (documented in VIEWS.md). The console page still runs `drishti_controls.js`; if any page hands a ViewModel with
controls to `paint()` and also runs that script, it needs the option.

**Verdict: TAG `v3.3.2`** on `9d704c4` (or its child with the CHANGELOG line). Follow-up 3.3.3 as above.

Nothing running, nothing built; only this file was written.

---

# Release gate 1.1.0 (2026-11-22 19:24)

Rachana `release/1.1.0` `538e2e06` against `v1.0.1` (173 files, +6897 −127). No Maven, no Node build. Run here, each a few seconds: the
checksums; the wheel in a fresh Python 3.11 environment with the two customer guides' samples as written (Flask server and a headless
Chromium); the plugin zip unpacked and driven the way SAJHA's loader does (`sajha/core/plugins.py`, read only); the committed schema
through `jsonschema` against the wheel's compiler. The lane's test counts (verify, conformance, 18 kit tests) are its own report.

## Verdict: NOT YET. Four fixes, one of them code (a few lines of `DocSchema`), three text

**M1. The schema refuses Sutras the compiler accepts: not additive, and the class of fault 1.0.1 was released to cure.** The Sutra's
`doc` was `type: object` in 1.0.1; it is now `$defs/doc` with `required: [summary]`, `additionalProperties: false` and
`summary maxLength 120`. Tried on the 1.1.0 wheel:

| Sutra `doc:` | 1.1.0 compile | 1.0.1 schema | 1.1.0 schema |
|---|---|---|---|
| none; the earlier keys (`summary, params, emits, examples, since`); a panel `doc:` line | ok, no problem | valid | valid |
| `{ description: ... }` (no summary) | ok, no problem | valid | **refused** |
| `{ summary: ..., purpose: x }` (a key of the author's) | ok, no problem | valid | **refused** |
| a summary over 120 characters | ok, no problem | valid | **refused** |

An editor on the 1.1.0 schema marks a clean 1.0.1 Sutra as wrong. Fix: thinness belongs to `RCH-2093`, not to the schema of a Sutra.
The Sutra's and a panel's `doc` take a loose form (the properties described, nothing required, other keys allowed, no length limit);
the strict `$defs/doc` stays for the typed fragment file, where the compiler already refuses. Add the three rows to
`schema_cases.py` as "compiles, so validates", regenerate `spec/1/sutra.schema.json` and the kit's `schemas/doc.schema.json`, rebuild
the assets and their SHA256SUMS. One verify by the lane; no second gate if the commit holds only this and M2 to M4.

**M2. `docs/RACHANA_IN_SPA.md` does not work as written** (the Flask guide does: see below).
- The browser zip unpacks into `drishti-rachana-1.1.0-browser/`, so "unpack into `public/rachana/`" gives 404 for all three addresses
  of step 1 (`/rachana/vendor/...css`, `...js`, `/rachana/src/rachana_view.js`). With the folder's contents moved up one level the
  sample paints (`rachana-view:ready`, "Sales overview", 215,000). Say "move the contents of the folder", or zip without the folder.
- Step 1 imports the element in an inline `<script type="module">`; step 3's own policy (`script-src 'self'`) refuses it (seen in
  Chromium: "Executing inline script violates..."). Use `<script type="module" src="/rachana/src/rachana_view.js">` and an external
  file for the page's own code, as the Flask guide does.
- The npm package holds `dist/rachana.runtime.js` and no Drishti Elements; the guide says to "keep the two vendored Elements files and
  `rachana.runtime.js` as static assets" without saying where they are. Name `node_modules/drishti-rachana/dist/` and say Elements
  comes from the browser zip (or carry `vendor/` in the package). Its `package.json` says `"license": "SEE LICENSE IN ../../../LICENSE"`:
  the file is beside it.

**M3. Say who can open a board.** `boards.py` is its own HTTP server with no sign-in: whoever holds `/b/<id>` (128 random bits,
`no-referrer`, `no-store`) or `/b/<id>/board.json` reads the rows that were fetched with the publisher's rights. That is acceptable for
"lite" on `127.0.0.1`, but `GENUI_KIT.md` 6 says "behind SAJHA's proxy and sign-in", which nothing in the kit does, and section 4's
"Data the user may not see" stops at the fetch. Add one Safety row and one INSTALL/README paragraph: a board link is the access; keep
`RACHANA_GENUI_HOST` on loopback or put the port behind a proxy that signs readers in; the `view` audit event has no reader identity
(the HTTP thread has no SAJHA caller). Also say that tokens and boards live in one process: more than one SAJHA worker needs
`RACHANA_GENUI_SECRET` set and still does not share boards.

**M4. Stale text.**
- `CHANGELOG.md`: the `v1.1.0` entry sits below `v1.0.1` (newest first); it says "Written without a Maven build" (the build is green);
  it leaves out the two customer guides and `Rachana.close()`; it must say that library gate LG3 now fails a library whose fragments
  lack the new doc fields (intended, but it is the one thing a library author meets on upgrade).
- `GENUI_KIT.md` 7: items 1 (Maven build "next") and 3 (browser check of `spa/`, Chromium part) are done; section 4 "to verify in
  the browsers (open item)" likewise.
- `README.md` and `libraries/README.md`: finance "50 fragments"; the folder holds 57 (33 + 57 = the 90 documented). Already stale at
  1.0.1; the README is rewritten from the file system in every release.
- Both guides and INSTALL say Python 3.11 or later; the wheel says `>=3.10` (see deviation 3). One statement.

## 1. Additive against 1.0.1

- API surface: 17 lines added, all `[experimental]` (`DocSchema`, `DocBlockLint`, its `Kind`); none removed or changed.
- Compiler: three small changes, all accepting more: a panel takes `doc`; a parameter's, slot's and event's `doc` may be a block whose
  `summary` is its line (`TypedFragment.docLine`); nothing else in parse, bind or compile moved. No conformance case changed.
- **What `RCH-2093` is.** A separate lint (`DocBlockLint.check`), never raised by a compile: a Sutra with no `doc` compiles with no
  problem at all on 1.1.0 (tried). The caller chooses the severity; the default is a warning. It is an error in one place, the library
  gate LG3 (`LibraryGates`), and the kit's `check_sutra` lists it without failing the check (`ok: true`, token given). So old Sutras
  are untouched; old **libraries** fail the gate until documented (M4).
- Python: `Rachana.close()` and an `atexit` hook added; nothing removed.
- The schema is the one place that is not additive: M1.

## 2. Safety of the kit (read, and driven from the unpacked zip)

| Claim | Found |
|---|---|
| Rows never pass through the model | holds: `publish_ui` returns counts only (the tool result searched for the rows: absent); tools take shapes; inline rows capped at 200 (the agent can still retype what it was shown elsewhere: bounded, not prevented) |
| Only allow-listed data tools, as the user | holds: `RGK-162` for a tool off `RACHANA_GENUI_DATA_TOOLS` (empty by default); inside SAJHA the call goes through `inner_calls.entered` + `inner_calls.check` + `composition.execute_step` (all exist in SAJHA 8.15.1 with these signatures; policy applied per step) |
| Origins allow-list | holds: `RGK-165`; the origin is compared whole (`scheme://netloc`, so `allowed@evil` fails); the same list is the page's `connect-src` |
| Unchecked or altered Sutra | holds: `RGK-170` after one added comment; HMAC-SHA256 of the text's hash, constant-time compare; checked again against the fetched columns |
| CSP | holds: `default-src 'none'; script-src 'self'`, no inline script in `spa/`, every asset from the wheel; `nosniff`, `no-referrer`, `no-store` |
| Paths | hold: fixed table for `spa/`, `assets.find` refuses `..` and resolves inside its roots (four traversal attempts: 404) |
| Audit | search, get, check, publish, view; counts and sources, never rows; hooks cannot break a call. The view event has no reader identity (M3) |
| Who may read a board | **the link is the access**; not stated, and the design says otherwise (M3) |

## 3. Test impact

- Added: `DocBlockLintTest`, `LibraryDocBlocksTest` (the 90 fragments), the kit's `tests/` (doc blocks, layout, schemas, tools; 18) and
  `browser_check.py`. Changed: the version asserted in `test_engine.py` and `Cases.VERSION`, `Facade.VERSION`. Nothing stale found in tests.
- Missing: no schema case for a Sutra `doc` (why M1 passed the 1.0.1 guard: none of the 351 Sutras has a thin Sutra-level doc).
- `rachana_genui/doclint.py` is a Python copy of the Java rule (the file says so, and that it goes when the bundle exports the rule).
  Two homes for one rule: accept for this release because a guard runs both over the libraries; first item of the next one.
- Drishti: not changed by this release. `origin/develop` pins `drishti.rachana.version` 1.0.1 exactly, so nothing moves until it
  chooses to. Carry-in for that day: its libraries meet LG3, and its Sutras need doc blocks only where it turns the lint on.

## 4. Document impact

- Links: every relative link of README, CHANGELOG, the two guides, the three design documents, the kit's README, guide and TOOLS
  resolves in the repository. In the **zip** four do not (`README.md`, `docs/GENUI_GUIDE.md` twice, `docs/TOOLS.md` point into the
  checkout's `docs/design` and `spec/1`): reword or drop them when the zip is rebuilt.
- No release number in `GENUI_KIT.md` or `GENUI_ARCHITECTURE.md`. Versions read 1.1.0 in the poms, the packages, the wheel, the facade.
- `RACHANA_IN_FLASK.md` works as written: install, `check` ok, `lint` "0 failing", every address 200, `/sales` and `/rachana/page/sales`
  paint (title, 215,000, both regions) with no CSP violation. Two blemishes: the console carries one painter warning (`the view: has
  no "ref"`), and the tile shows "revenue" (the id) where the Sutra says `label: Revenue`; neither is the guide's fault, both worth a look.
- `RACHANA_IN_SPA.md`: M2. `INSTALL.md` is complete and correct for SAJHA's loader (`plugin.json`, `tools/*.py` with one concrete tool
  class each, `requirements.txt`, `min_sajha_version` 8.0.0 against 8.15.1): following it, the four tools load, search, check and
  publish from the zip alone. It exists only inside the zip (no source or build script for the zip in the repository: add one), and
  it should say the plugin is unsigned (SAJHA loads it with a notice unless `plugins.allow_unsigned` is false).

## 5. The four deviations

| Deviation | Judgement |
|---|---|
| Python package `drishti_rachana`, not `rachana` | **Accept.** It is the 1.0.x name; a rename now would be the break. Guides and plugin use it consistently |
| Browser zip is ESM only (no UMD, no `.d.ts`) | **Accept.** The JavaScript facade is experimental at 1.x and both guides say so; say "ES modules only, no type declarations yet" in the SPA guide |
| `requires-python >=3.10` | **Accept**, unchanged from 1.0.1; but only 3.11 and 3.13 were installed and run. Either say "3.10 allowed, 3.11 and 3.13 tested" or raise the floor to 3.11 in the next minor, not in this one |
| Plugin zip carries `libraries/` | **Accept.** The wheel has no libraries and the kit is useless without them; the copy is identical to the repository's (compared), and INSTALL sets `RACHANA_LIBS` to it. Better later: the kit finds `libraries/` beside itself so the variable is optional |

SHA256SUMS: all four assets OK. State left behind: nothing running; the scratch environment removed; only this file written.

---

# Release gate 2.9.11.i7c

*2026-11-23 11:56 EST. `release/i7c` at `bfbe8c6f2` against `v2.9.11.i_7b` (5 commits, 33 files). Read only: the code, the diff and small
Python checks; no Maven build (the lane's proofs stand: package, the three Java tests, tools 79 + 99, docs gate 136, the 28480/27480 smoke).*

## Verdict: NOT YET

The design is right and the split is clean: one `drishti.python`, written once, read by `start console`, the feeds and the compute pool; the
`.venv` path is unchanged. Three defects stand between this and "no human intervention" on a machine with only the company Python, and one
line of the notes is stale. All four are small.

## Must fix (the whole list)

| # | What | Where | Evidence |
|---|---|---|---|
| M1 | **No `drishti.py` command runs on a Python without PyYAML, `setup --system` and `setup libs` included.** The command that installs the libraries needs one of them first. A clean company Python stops with `drishti: yaml is not installed; run uv run --with pyyaml ...` and installs nothing; the PyCharm launcher **Drishti: setup (system Python)** stops the same way | `tools/contracts.py:40` (top-level `import yaml`, loaded by `build_parser` for every command); then inside setup `tools/module_layout.py:23` (from `tools/launch/envsetup.py:144`) and `tools/launch/launchers.py:63` (from `envsetup.py:173`); the message `tools/drishti.py:1183` | reproduced: `python3 -S -B tools/drishti.py setup libs --check` and `... setup --check --system` both exit 2 with that message; `envsetup.setup` called directly with `--system` prints five lines and raises at line 144. The same hole was in i7b's `setup`; i7c is the release that promises this path |
| M2 | **The pivot Python functions do not read `drishti.python`**, though CHANGELOG, RELEASE_NOTES and IDE_GUIDE 2.2 say they do. Blank `drishti.pivot.functions.python.interpreter` still starts `python` by name on Windows (the Store stub) | `kernel/drishti-server/.../pivot/functions/PivotFunctionsConfiguration.java:60` passes `p.interpreter()` unresolved; `ProcessPythonClient.java:87-88` falls to `python` / `python3`; `docs/admin/CONFIGURATION.md:4478` (truthfully) says "blank: python3, python on Windows" | grep of every `new ProcessBuilder` in main code: feeds and compute (workers, environments) go through `PythonSetting`; this one does not. Off as shipped, so the demo is not hit |
| M3 | **The two new VS Code tasks fail on a fresh machine**: they pass `--settings ~/drishti-envs/dev.ini` to `setup`, the file setup is there to make. The PyCharm rows got `settings: false`; the VS Code writer ignores it | `tools/launch/launchers.py:179`; `drishti.code-workspace:164,166,176,178` | `drishti.py --settings /nonexistent/dev.ini setup --check --system`: "drishti.settings.file names ..., which is not a file" |
| M4 | RELEASE_NOTES i7c, "What changes for developers": IDE_GUIDE 2.3 is described as "`setup --skip-venv`, the libraries into the system Python". The guide now says `setup --system` | `RELEASE_NOTES.md` (i7c section, second developer bullet) | read |

The fixes, shortest form:

- **M1**: setup must run on the standard library alone. Dispatch `setup` before the other command groups load (or make `contracts.py`'s
  import lazy); with `libs` / `--system` install the libraries **first**, then do the two PyYAML steps (module leftovers, JVM files). After
  the install the running process may not see the new packages: a `C:\Program Files` Python is not writable, pip falls back to a user
  install, and a user site folder made during the run is not on `sys.path`; so add it (`site.addsitedir(site.getusersitepackages())`,
  `importlib.invalidate_caches()`) or run the second half in a fresh process. One test: `setup libs --check` under `python -S` reports the
  libraries and exits 1, never 2. Change the `uv run` hint at `drishti.py:1183` to name `setup libs` / `setup --system`.
- **M2**: `PythonSetting.resolve(p.interpreter(), env::getProperty, System::getenv)` in `PivotFunctionsConfiguration`, the property's
  comment, CONFIGURATION.md regenerated, one targeted test. (Or take the claim out of the three documents; wiring is the better half
  because the owner's rule is "no `python` by name".)
- **M3**: `launchers.py:179` honours `settings: false`; regenerate the workspace; `test_launchers` asserts it for the workspace as it does
  for the run configurations.
- **M4**: one line.

Proof after the fixes: the changed tools tests, `PythonSettingTest` plus the pivot one, the docs gate; no drill, no second smoke.

## 1. The Windows path with no `.venv`, step by step

| Step | Creates `.venv`? | Needs `DRISHTI_PYTHON`? | `python` by name? | Network |
|---|---|---|---|---|
| `py tools\drishti.py setup --system` | no (`envsetup.py:93`: `do_venv` is off with `--system`) | no | no (`sys.executable`, which under `py` is the real `python.exe`) | pip only, the Windows store (`pylibs.py:152-160`: default from pip 24.2, the flag from 23.1, a note below that). **Stops at M1 where PyYAML is absent** |
| the Lake wheel | - | - | - | none: `lib/wheels`, installed before `requirements.txt` (`pylibs.py:163-175`); the source zip carries it. From a bare clone it is not there (not in git) and setup says so, naming `lib/lake-wheel.pin` |
| `drishti.python` in `dev.ini` | - | - | - | written as an absolute forward-slash path, once, over the commented example line or before the first section (`pylibs.py:178-197`); a path with a space (`C:/Program Files/Python313/python.exe`) writes and reads back whole (checked on a copy of the example file) |
| `setup --check --system` | no | no | no | none; each requirement by name and version, the wheel against its pin |
| PyCharm **Drishti: setup (system Python)** | no | no | no (project interpreter, no path stored) | as the first row; M1 applies |
| PyCharm **Drishti console** | no (runs `run_drishti_web.py` itself) | no | no | none |
| IntelliJ **Drishti: run server (demo)** | no | no | no for feeds and compute (they read `drishti.python` from the `dev.ini` the launcher names); **yes for pivot functions when switched on (M2)** | none |
| `start server` / `start console` / `start all` **with `--settings dev.ini`** | no (`consolestart.py:63-71`) | no | no (`procs.py:453-459` falls to this interpreter) | none |

Still able to make a `.venv` or call Python by name, each by design or minor, none a blocker:

- `start console` / `start all` **without `--settings`**: the settings file is never discovered, so `drishti.python` is not seen and
  `consolestart.py:71` → `:45` runs `-m venv`. Setup's "Next steps" print the command with `--settings`; the guides should say that on a
  no-venv machine it is not optional. The server started without it falls to `python`: `DataFeeds.java:313`, `ComputeSettings.java:73`.
- `start console --python X` or `--reinstall`: asks for the `.venv` (documented). Plain `setup` and `setup venv`: make it (their job).
- Stale words that still send the reader to a `.venv`: `tools/launch/launchcli.py:165` (cluster without PyYAML: "setup ... makes
  drishti-console/.venv"), `tools/doctor.py:99-101` (warns "console venv" missing, fix `python3 -m venv`; **Drishti: check environment**
  shows it on a no-venv machine), `tools/build_demo_lakes.py:304-305`, the docstring `tools/launch/consolestart.py:16`.
- VS Code on Windows runs `python tools/drishti.py ...` by name in every task (`drishti.code-workspace`, older than this release), and
  `python.defaultInterpreterPath` is `python3` (`launchers.py:192`): on a `py`-only machine the VS Code tasks need `py`. For the i8 list.
- A user install (no administrator rights) is invisible to the pivot workers, which start Python with `-I`
  (`ProcessPythonClient.java:90`). Only with M2's feature on; note it in the guide when M2 is wired.

## 2. Precedence (`DRISHTI_PYTHON` > the work's own setting > `drishti.python`)

| Starts Python | Through `PythonSetting`? |
|---|---|
| Feeds, Run now (`DataFeedsConfiguration.java:41`) | yes; last resort the console's `.venv`, then `python3` / `python` |
| Compute pool: workers and environment builds (`ComputeConfiguration.java:62` → `ComputeSettings.interpreter()`, used at `ComputeService.java:251`, `Environments.java:144`) | yes. `compute.python` is itself `${DRISHTI_PYTHON:}`, so the order holds |
| Pivot Python functions | **no (M2)** |
| Console (`consolestart.py:63-71`) | `DRISHTI_PYTHON`, then `drishti.python`, then the `.venv` |
| Tools' helpers (`procs.py:453`, `clusterctl.py:150`) | `DRISHTI_PYTHON`, the `.venv` if there, else the running Python: right without `drishti.python` |

No other main-code `ProcessBuilder` starts Python (the fifth is the test kit's MinIO).

## 3. The `.venv` path (Linux, the owner's stack)

No regression found. `setup` alone is still drishti then venv; `--skip-venv` still works; an existing `dev.ini` and `.venv` are kept; the
launchers other than the two new ones are byte-for-byte the same; `start all` on a stack whose `dev.ini` has no `drishti.python` takes the
old branch. Three intended changes to know before the stack is upgraded:

- `setup` now **verifies** an existing `.venv` and fails (exit 1, "setup venv --reinstall") if a requirement or the Lake pin is not met.
  i7b printed "ok" for any `.venv` that existed.
- The next `setup` writes `drishti.python=<the .venv's python>` into `dev.ini`. From then on the compute pool, when on, runs on the
  `.venv`'s Python where it used `python3`; and `start console` no longer remakes a deleted `.venv` by itself (it says "no Python at ...";
  `--reinstall` or `setup venv` remakes it).
- Without uv the `.venv` gets the wheel then the requirements in two pip calls (one before); a missing wheel is now said plainly, where
  pip failed on `drishti-lake==0.9.1` before.

## 4. Test impact

- `test_launchers` relaxed assertion: **justified**. `--settings` refuses a file that is not there (shown under M3), and setup is what makes
  `dev.ini`; the test now asserts the opposite for `setup` rows (`--settings` absent) and the old rule for every other row. It should
  grow the workspace half (M3).
- New: `tools/test_setup_split.py` (17 tests), `PythonSettingTest`. Every one of them runs on a Python that has PyYAML, which is why M1
  got through: add the `-S` test.
- No pivot test for the shared setting (M2). Nothing stale names i7b in tests or code; version 2.9.11.i7c, tag `v2.9.11.i_7c`
  (`tools/version.py --tag`).
- Counts as the lane gave them (tools 79 + 99, docs gate 136); not rerun.

## 5. Document impact

- IDE_GUIDE 2.1-2.3 and 9.5, DEPLOYMENT_AND_QA_GUIDE 1.2a/1.6, CLI_REFERENCE setup, CONFIGURATION.md (`drishti.python`, `compute.python`,
  `drishti.feeds.python`, `DRISHTI_PYTHON`) agree with each other and with the code, current design only, **except**: the pivot claim
  (M2: IDE_GUIDE 2.2, CHANGELOG, RELEASE_NOTES against CONFIGURATION.md:4478) and the notes' `--skip-venv` line (M4).
- Missing sentence, wherever `setup --system` is introduced (IDE_GUIDE 2.2/2.3, DEPLOYMENT 1.2a): what the Python must already have.
  After M1 the answer is "nothing"; say so.
- Small: IDE_GUIDE 4.3 shows "`dev.ini` only" for the two setup launchers, which name no settings file (`launchers.py` `guide_section`);
  QUICKSTART_DEVELOPERS 1.3 still gives only the hand-made `.venv` on Windows (one line to `setup --system`). The PDFs were regenerated in
  `bfbe8c6f2`; regenerate again after M2/M4 touch their sources.
- **The platform IDE guide** (`drishti-platform/IDE_GUIDE.md`, "No virtual environment: the system Python") is wrong for i7c as it
  stands: five steps with `setup --skip-venv`, two pip lines and `DRISHTI_PYTHON`. After i7c it should be:
  1. `py tools\drishti.py setup --system` (or PyCharm **Drishti: setup (system Python)**): the settings file, the JVM files, the Lake
     wheel then the requirements into that Python (pip on the Windows certificate store), and `drishti.python` in `dev.ini`.
  2. PyCharm: **System Interpreter**, the same `python.exe`.
  3. Nothing to set for the server: it reads `drishti.python`; `DRISHTI_PYTHON` only to override.
  4. Check: `py tools\drishti.py setup --check --system` lists nothing to do.
  Step 1 of the normal path there is unchanged. The index also needs the i7c row (`platform.yaml`, COMPATIBILITY.md: tools 1.1.0,
  Rachana 1.0.1, Lake 0.9.1, Elements 3.3.2, `v2.9.11.i_7c`): Drishti's guides already say `clone_all.py --release i7c`.

State left behind: nothing running, nothing built, no file changed but this one; the scratch copies removed.

---

# Release gate 2.9.11.i7d

*2026-11-23 14:50 EST. Drishti `release/i7d` at `6d8893875` against `v2.9.11.i_7c` (2 commits, 22 files); the index repository at
`5d80b2a` (pushed) plus the local `7888ae1` (START_HERE_WINDOWS, the IDE guide's offline step, the pending i7d row). Read only: the code,
the diffs and small Python checks; no Maven build, no pip run. The lane's proofs stand (tools 104, docs gate 136, package and
spotless:check, the Linux end to end with a dead index: BANK VaR 167,372,692 / ES 210,131,972). Nothing here was run on Windows.*

## Verdict: NOT YET

The mechanism is right: only what is missing is installed, pip is never called when all is in order, the offline path asks no index, the
lock and the requirements are checked together, and every launcher, path, asset name, sign-in and figure START_HERE_WINDOWS names exists
as written. What stands between this and "a non-technical person, one page, no workarounds" is one wrong sentence in the offline path
and a handful of lines of the page. All small; none needs a rebuild of the design.

## Must fix (the whole list)

| # | What | Where | Evidence |
|---|---|---|---|
| M1 | **The offline path blames the package index.** With `--wheels`, when pip finds no wheel it can use, setup says "your package index lacks numpy==2.5.3: use an index that has it, or install offline with --wheels <folder>". The reader is already offline. Two ordinary Windows mistakes end there: (a) `py` starts the newest Python on the PC (3.14 or 3.15), and the pack holds cp313 wheels only; (b) the pack is unzipped one folder deep (Explorer's "Extract All" makes a folder named after the zip): the missing-wheel check looks in sub-folders (`wheel_names` uses `rglob`) and passes, pip's `--find-links` does not look in sub-folders and fails. | `tools/launch/pylibs.py` `install`, `explain_pip_failure`, `wheel_names` | `explain_pip_failure("ERROR: No matching distribution found for numpy==2.4.0")` returns the index sentence (run here). Fix: give pip the folder that holds the `.whl` files; before pip, compare the interpreter with the pack's `cpNN` tags and say "this wheel pack is for Python 3.13, this Python is 3.14: run `py -3.13 ...`"; an offline sentence that never mentions an index. Two tests (nested folder, wrong Python). |
| M2 | **START_HERE step 4 and 5 name a Python that may not be the one used.** Step 4 runs `py` (the newest installed Python); step 5 tells the reader to choose `C:\Program Files\Python313\python.exe`. The python.org installer's default is `C:\Users\<you>\AppData\Local\Programs\Python\Python313`; the Program Files path exists only for an all-users install. | `START_HERE_WINDOWS.md` steps 4, 5 (index, unpushed) | Step 4: `py -3.13 tools\drishti.py setup ...`. Step 5: "choose the `python.exe` shown on the `python` line of step 4" (setup prints it; it is also what `drishti.python` holds). |
| M3 | **What a first-time reader meets and the page does not say.** (1) "Trust Project" in IntelliJ and in PyCharm. (2) The launchers appear in the list only when indexing ends (the bar at the bottom). (3) PyCharm offers to create a virtual environment on opening the folder: decline it. (4) Opening the second project asks "This Window / New Window": New Window. (5) Windows Firewall asks about Java and Python on the first start: Allow. (6) Step 3 downloads Maven and the Java libraries, so it needs the company network; "nothing is fetched from the internet" is true of the Python libraries only. (7) Java must be findable: `JAVA_HOME` set or `java` on the PATH (ask IT), else step 4 prints `[FAIL] java` and step 5 cannot build the lakes. | `START_HERE_WINDOWS.md` (+ the PDF) | The launchers are `*.run.xml` files outside `.run/`, found by the index; `locate_java` reads only the settings, `DRISHTI_JAVA_HOME`, `JAVA_HOME` and the PATH; the platform pom's product builds start `mvnw.cmd`, which needs a Java it can find too. |
| M4 | **Say where the libraries go when Python is in Program Files.** A user without administrator rights cannot write there; pip then installs into the user's own folder (`%APPDATA%\Python\Python313\site-packages`) by itself. Setup's second run, `--check`, PyCharm, the console and the demo lake all see it. The server's pivot Python function workers do not: they start Python with `-I`, which leaves the user folder out. | `ProcessPythonClient.java:90`; RELEASE_NOTES "Known limits"; START_HERE "What you need first" | One line in the known limits, and in START_HERE ask for a Python the user can write to (the installer's default "Install Now", for this user) or for IT to run step 4 once as administrator. No code change in this release. |

M1 is Drishti code (tools only: the tools tests are the proof, no Maven). M2 to M4 are the unpushed index commit, one line of
RELEASE_NOTES, and the PDF again. No further gate is needed once these are in: the lead confirms the four rows and tags.

## 1. START_HERE_WINDOWS against the real release

- Correct as written: the two asset names (`make_bundle.py`, `make_wheels.py`: `drishti-platform-<release>-wheels-windows-x64-py313.zip`);
  both zips are flat, so the folders the page names are what the reader sees (the wheels zip also holds `requirements.txt`, `python.lock`,
  `SHA256SUMS`); `run-configs\platform-clean-install.run.xml` is in the source zip beside the pom; **Drishti: build demo lakes**,
  **Drishti: run server (demo)**, **Drishti: run console** are the launchers' names; the demo lakes come before the server; `Demo lake ready`,
  `Started DrishtiApplication`, the sign-in and `VAR BANK` are real; the figures are the lane's.
- `platform-clean-install` with only the downloads: tests skipped (the launcher and `-DskipTests=${skipTests}`), the Windows certificate
  store in IntelliJ's Maven (the launcher's VM option) and in the four product builds (the Windows profile), `-Dpython3=py`,
  `-Dspotless.check.skip=true`, the project JDK. Nothing to set by hand. It still reaches a Maven repository (M3.6).
- Language: plain, each step says what to see. Two words to soften: "run configurations" (say "the list at the top right"), "wheels"
  is fine as the zip's name only.
- The server launcher is a JAR Application on `lib\drishti-server-exec.jar` with `~/drishti-envs/jvm-demo.args`, which step 4 writes:
  the order of the steps is right. The args file and the launchers quote their paths: a user name with a space works.

## 2. The Windows code paths of setup

- **pip and the certificate store**: off with `--wheels` (no index is asked), on otherwise (`--use-feature=truststore` before pip 24.2).
  Right.
- **Paths with spaces**: every process is started from a list; `drishti.python` is written with forward slashes and read back whole;
  the copy-ready pip line uses `py`. Right.
- **`--wheels` with a .zip**: unzipped to a temporary folder, removed afterwards; the second run of setup (after the bootstrap) unzips it
  again, which is correct, only slower. A bad zip and a missing path are said in words.
- **Program Files not writable**: pip falls back to the user folder on its own; `--user` is not needed. The second run, the checks and
  PyCharm are fresh processes and see it. The one reader that does not is M4. pip then also warns that the `Scripts` folder is not on the
  PATH, once per script: add `--no-warn-script-location` to the three pip lines (small, not a must).
- **Which Python**: M1 and M2.
- Small: `explain_pip_failure` names the host only from a URL; pip's `HTTPSConnectionPool(host='...')` line gives "the configured package
  index". The sentence is still right.

## 3. Test impact

- `tools/test_setup_split.py` covers: nothing to install means no pip, the three sentences, the pip line, the folder and the zip, the
  missing wheel named, the lock and the requirements disagreeing, `--find-links` stripped, the environment variable, `--check` ignoring
  `--wheels`, a standard-library-only Python with `--wheels`.
- Missing: the two cases of M1. Nothing else in the diff lacks a test; the Java side is untouched (the capacity test vectors are the
  version only).
- No test runs on Windows; the owner's Windows QA is the first real proof of the Windows pack (the lock is installed in pip's hash mode:
  the pack's wheels must be the files the lock lists, which `make_wheels.py` resolving them together gives).

## 4. Document impact

- IDE_GUIDE 2.2 and 4.3, DEPLOYMENT_AND_QA_GUIDE 1.2a, CLI_REFERENCE, RELEASE_NOTES and CHANGELOG agree with each other and with the
  code. QUICKSTART and DEPLOYMENT name i7d. No client name in either diff or in START_HERE.
- The platform IDE guide: "Python 3.10 or later" stands beside a pack that is for Python 3.13 only (say so in the offline step; Drishti
  itself asks for 3.11); its step 3.3 says **Drishti console**, the launcher is **Drishti: run console**. The index README line 140 still
  gives `i7c` in the pack's name. The PDF of START_HERE prints `docs/START_HERE_WINDOWS.md` in its header; the file is at the top of the zip.
- After M1 the sentence in IDE_GUIDE 2.2 / DEPLOYMENT 1.2a ("a wheel the folder lacks is named") gains "and a pack made for another
  Python is said so".

State left behind: nothing running, nothing built, no file changed but this one.
