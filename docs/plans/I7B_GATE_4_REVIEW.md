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
