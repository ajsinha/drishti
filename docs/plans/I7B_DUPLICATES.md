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
# i7b duplicates: the A to Z audit and the plan to remove them inside i7b

**Status: audit and plan for the lead and the owner, written 2026-11-21. Nothing built, nothing run, no decision taken.**

**The directive (owner, 2026-11-21).** "I want i7b to be golden and i8 to be cleaner and simpler. If we know redundant and
duplicated code then we should fix these now and not punt to the next release." Added by the lead the same day: no customer uses
any product yet, so compatibility shims, aliases, deprecated names and read-both paths go as well; a shim is kept only if the
owner's own data or running stack needs it. Language level 1.0 stays (the proof baseline of the corpus and the frozen regime).

**Read from git only.** Drishti `main` `9ce7c3170`, `origin/wip/g4-rehearsal` `daebebcf7`, `origin/wip/g4-rehearsal-rx`
`f954d27bf`; `drishti-rachana` `origin/g2/integration` `05089da`; `drishti-elements` `origin/g2/integration` `2bd0a4a` (every
`g3/*` branch of both is 0 commits ahead); `drishti-lake` `origin/main` `84cb76c`. Line counts are `wc -l` of main sources.
"Planned" means an accepted plan step already removes it; the step is named. Estimates are builder-days (bd); models follow the
token economy (O Opus, S Sonnet, H Haiku, F Fable). Documents referenced, never copied: [DRISHTI_TOOLS_DESIGN.md](DRISHTI_TOOLS_DESIGN.md)
(TL-1 to TL-14), [DRISHTI_LAKE_DESIGN.md](DRISHTI_LAKE_DESIGN.md) (LK-1 to LK-16, D1 to D9), [I7B_PRODUCTS_PLAN.md](I7B_PRODUCTS_PLAN.md)
(C0 to C7, EV1 to EV12), `G4_REHEARSAL.md` on the two rehearsal branches (R, P, E, L findings), and in `drishti-rachana`
`docs/design/LIVE_TABLES.md` (LT-1 to LT-19), `docs/plans/R0_R1_I8_PLAN.md` (B6 to B8), `docs/plans/DRISHTI_MIGRATION_TODO.md` (8.2 to 8.6).

## 0. The answer in one page

1. **The register holds 80 rows** (section 2): 67 duplicates and 13 rows of compatibility code. **About 88,000 lines are already
   removed by accepted G4 steps** (the compiler copy, the binder, the lake modules, the aggregate kernel, the tools copies, the
   xlsx writers, the SSE controllers). **About 25,000 further lines of code and about 10,000 lines of documents have no plan
   step today**; this document gives each one a step.
2. **The four largest findings that no plan held, or held wrongly:**
   - **Drishti draws a ViewModel in five ways, not two.** The console's own pages are drawn by **Jinja on the server**
     (`templates/_macros`, 728 lines, plus `drishti_live.js` and `drishti_charts.js`); the `drishti-renderer` library (2,782)
     draws only embeds and Explain; a frozen `render/1.2.0` (3,275 of JavaScript); the frozen embed v1 (1,903, its own SSE
     reader); and Elements' `drishti-views` painter (4,699). The plans (B6, EV5, EV6) assume "the renderer" draws the pages.
     It does not. Adopting the painter as planned leaves Jinja, so "one way to draw" would still be untrue after G4.
   - **The live switch-over planned for i8 would make G4 build throw-away code**: B7 mounts a second wire beside
     `/api/v1/live/*`, S4a makes LiveSet an `events.v1` topic provider, C3 rewrites the live pages, and i8 deletes all three.
   - **A wrong answer sits under the aggregate kernel** (rehearsal P4: Core's `sum` over a filtered list gives 0, the engine
     gives 78,104). It blocks step 8.3h and must be found **before Rachana tags in G3**, not at G4.
   - **About 3,500 lines of migration and alias code in Drishti and about 800 in Lake serve releases nobody runs.**
3. **Recommendation: do all of it inside i7b, in three tiers** (DD-1). Tier 1, the same-job duplicates and the shims: in, no
   conditions. Tier 2, the three large switch-overs (live tables, Jinja to the painter, the BI chart builders): in, each with a
   G3 entry gate and an agreed cut line, because each can fail without failing the rest. Tier 3, five listed exceptions that
   are twins by design, with their binding tests (section 5).
4. **Cost** (section 7). New work beyond the accepted plans: **about 27 bd in G3** (products and tools) and **about 85 bd in
   G4** (Drishti), of which 36 bd are the three Tier 2 switch-overs. G4 grows from about 122 bd (the plans' 106.5 plus what the
   rehearsal already found) to **about 207 bd**. At the rates I7B_PRODUCTS_PLAN 4.2 records for work in one repository, that is
   **about 6 to 11 more working days for G4** and 2 to 3 for G3. i8 drops from about 65 bd of priced work (plus the unpriced rest
   of this register) to about 34 bd with no switch-over in it: role Homes, K5 and new features only.
5. **The honest risk.** G4 has one drill. Doubling what that drill must prove raises the chance of a second drill from likely
   to near certain (the drill rule already requires a second whenever the first needed fixes). The answer is the order of
   section 7.3: goldens after every lane, the Tier 2 lanes last and separately revertible, and a named freeze before the drill.

## 1. Method

Five read-only sweeps, each on fresh exports: (a) the Java engine side against Rachana Core; (b) the browser and console side
against Elements and Rachana's JavaScript and Python; (c) the lake, Python tooling, guards, the tools extras and the documents;
(d) compatibility code in all four repositories; (e) duplicates inside Elements, Rachana and Lake (a 12-line window detector
over main sources plus same-name helpers). The load-bearing counts were re-measured for this document (the liveset package,
the aggregate functions, the renderer, the frozen distributions, the Jinja macros, the migration packages).

**Classes used below.** *Same job twice*: two implementations where one must survive. *Required twin*: two implementations bound
by a parity test and needed by a build constraint (kept, section 5). *Shim*: code that exists only for an older release.

## 2. The register

Columns: where and how large; which copy survives and where it lives; milestone; estimate and model; risk; proof.
**P** in the first column means an accepted step already covers it (the estimate is then that step's, not new work).

### 2.1 Shared utilities: Drishti, Lake, Rachana (home: `drishti-tools`)

| # | What, where, lines | Survives / home | When | bd, model | Risk | Proof |
|---|---|---|---|---|---|---|
| T1 **P** | TLS and proxy (Drishti `api/tls`, `api/proxy` 2,084; Lake `io/net` 1,902), `SettingSpec`, `Placeholders`, `SettingsFile` (345 / 342), `SingleFlight`, `LoadGuard` (275 / 272), `FakeProxy`, `TlsFixture` (420 / 420) | `tools-net`, `-settings`, `-concurrent`, `-time`, `-testkit` | tools now; Lake G3; Drishti G4 | 10.5 + 1.5 + 2 (TL-4 to TL-8) | Lake's public surface freezes at its tag | the moved tests; Lake's kit green twice |
| T2 **P** | Four xlsx writers in Drishti (1,580): `dist/XlsxWriter` + charts + formulas, `bicompute/export/Xlsx`, `playground/SheetStream` + `WorkbookStream`, `capacity/CapacityWorkbook` | `tools-xlsx` (one streaming writer) | G4 | 3, O | a workbook changes | the same workbooks before and after, read by POI cell by cell |
| T3 | **Hash helpers**: 48 hash-named methods, `MessageDigest.getInstance` in 72 main files; of 9 sampled, 5 are the identical job (UTF-8, SHA-256, hex) | `tools-codec` `Hashes` (JDK) | tools G3; Drishti G4 | 1 + 2, S | an identity or signature changes | fixtures of today's bytes written first; every stored identity equal |
| T4 | **Canonical JSON** (sorted keys) in 4 places: `RunSources.canonical`, `QuestionNormaliser`, `MqParser`, `reactive/Results` | `tools-codec` `CanonicalJson` | tools G3; Drishti G4 | 0.5 + 0.5, S | as T3 | byte fixtures |
| T5 | **CSV** in Drishti, 7 places: readers `CsvReader` 103, `Feeds.csv`, `FileSourcePlugin.csv`; writers `CsvWriter` 89, `PivotExport.csv` / `rowsCsv`, `SqlController.csv` | `tools-csv` (our 192 lines; Commons CSV as the test oracle, as POI for xlsx) | tools G3; Drishti G4 | 1 + 1, S | quoting differs in one export | RFC 4180 vectors; exports before and after |
| T6 | **`TempFiles`** Drishti 266 against Lake 131 (229 lines apart), `JvmTempDir`; 27 users in Drishti; rehearsal L4 kept a second copy | `tools-files` (JDK only); Lake's runtime instance wraps it | tools G3; Lake G3; Drishti G4 | 1 + 0.5 + 0.5, S | clean-up on Windows | both test sets merged; `@TempDir` tests |
| T7 | **`RowFeed`** in `drishti-common` 387 beside Lake's (rehearsal L5) | Lake's; `drishti-common` stops depending on `lake-io` | G4 | 0.5, S | layering | compile; `ArchitectureRulesTest` |
| T8 | **`BusinessCalendar`** Drishti `common` 218 against Rachana `el` 212 (32 lines apart); plus the rehearsal's `HostCalendars` adapter | Rachana Core's (it must stay dependency-free for the JavaScript build); Drishti's deleted | G4 | 0.5, S | a holiday rule differs | calendar tests of both, merged; VaR goldens |
| T9 | **HTTP clients**: `TlsHttp` 135 is the wrapper; 7 builders bypass it (`JavaLibraries`, `BridgeClient`, `PackRegistryClient`, `ReportService`, `OidcConfiguration`, `ReplenishSource`, `MinioServer`) and so ignore site TLS and proxy settings; `ai/JsonHttp` 190, `net/PeerHttp` 345 wrap again | `tools-net` `TlsHttp`; a guard: `HttpClient.newBuilder` only there | G4 | 1.5, S | a call that worked without the proxy now uses it | `FakeProxy` tests per caller |
| T10 | **Guards written three to four times**: licence header and line cap (Drishti 30 + 72, Lake 49, Rachana 42), API surface (106 / 161 / 55), names-no-host (Lake 61, Rachana 65), `Sources` (71 / 36), exemptions only shrink (Lake only) | `tools-guards` **published as a test jar** (TL design held it unpublished); Lake and Drishti use it | tools G3; Lake G3; Drishti G4 | 1.5 + 0.5 + 0.5, S | none | each guard fails on a planted case |
| T11 | Zip writing: 9 files use `ZipOutputStream`; 4 are the xlsx writers (gone with T2); `DmodZip` 165, `Dmod`, `PackFragment`, `ExportService`, `ModulePublicController` remain | `Dmod`/`DmodZip` own module archives (one job); the other three call `tools-codec` `Zips.write` | G4 | 0.5, S | low | archive bytes before and after |

### 2.2 Language and engine: Drishti against Rachana Core

| # | What, where, lines | Survives / home | When | bd, model | Risk | Proof |
|---|---|---|---|---|---|---|
| R1 **P** | The compiler copy: `compute/drishti-rachana` `el`, `format`, `model`, `parse`, `design`, 9 classes: 149 files, 26,046 lines | Rachana Core | G4 (8.2; done in the rehearsal) | 1, S | none new | EV1 goldens, CG3, CG8 |
| R2 **P** | The binder copy: `engine/{bind,control,view,wire}` 23 files, 5,308; `engine/live/Frame`, `Patch` 89 | Core's binder behind hooks (8.3e) | G4 | 5, O | P3 (foreign node types), P7 (live formats), P8 (7 new kinds) | EV1 exact; `ViewModelSchemaTest` |
| R3 **P** | **The aggregate kernel**: `bicompute/functions` 9 files, 3,424 (`Builtins` 810, `Moments` 483, `MoreAggregates` 556, `Holistics` 474, `Comoments` 217, `Final` 121, `ListAggregates` 217, `ListAnalytics` 475, `ListGrouping` 71), a file-for-file copy of Core's `agg` (22 to 98 lines apart). **All 44 names of `CoreAggregates` are defined twice** (`sum`, `avg`, `count`, `stddev`, `percentile`, `wavg` ...); the rehearsal start-up refused: "function sum is provided twice" | Core's `agg`; Drishti keeps 2,146 lines that are its own (`_once` family, `measure`, `cell`, the sketches, `EngineFunctions` as shells) | G4 (8.3h) | 3, O | **P4: a wrong answer, cause not found** | a state encoded by the old class decodes with the new; `SutraAggregateParityTest`, `DifferentialTest`, the VaR goldens exact |
| R4 | **P4 itself**: Core `ListAggregates.apply` returns 0 only when the first argument is not a list or every value reads as not a number; one of the two happens with Drishti's `DataNode`s | a fix in Core or in the node seam, with a conformance case | **G3, before Rachana's tag** | 1, O | if it is Core's fault, a tagged release carries it | the collateral-balance case added to conformance |
| R5 | **Twins no step deletes** (about 1,600 lines): `api/IdText` 50, `api/ListValues` 218, `api/PathWalker` (the algorithm of `Nodes.at`), `engine/source/SourceFailures` 44, `engine/search/form/RelativeDates` 99, `bi/report/RelativeDates` 167 (212 lines apart from Core's: a third dialect), `common/SafeRegex` 728 (8.5 says "delegate", names no deletion) | Core's; Drishti's deleted, not delegated | G4 | 2.5, S (O for `RelativeDates`) | the report's relative dates differ from Core's | EV1; report recordings; `HotPathBenchmark` within 3% |
| R6 | **Static core-only compilers** in `RowPredicate` 383 and `DocPath` 303 (rehearsal P2): a second, poorer function table beside the instance's | the one `Rachana` instance of the holder, swappable when a module loads | G4 | 1.5, O | a compiler taken early misses a module's functions | `Rachana*Test`, `SearchQueryTest`, `DerivedPageTest` |
| R7 **P** | ViewModel JSON written by Jackson against Core's `ViewJson`; two schemas (`drishti.local/schema/viewmodel.json` against `spec/1/viewmodel.schema.json`) | `ViewJson` and the specification's schema (8.3g, N3) | G4 | in 8.3 | byte differences in the API | EV1, EV3 |
| R8 **P** | Error codes: 71 `RCH-` codes against 301 `DRS-`; mapped at 3 of the places an API answers | one map at the response writer (P9) | G4 | 0.5, S | a leaked `RCH-` | `ErrorShapeTest` |
| R9 | **Printer text** (P10): `&&` and `where = ` against Core's `and` and `where=` | **Core's text**; expected texts regenerated; nobody holds saved formulas to protect | G4 | 0.25, H | none (no customer) | `PivotFormulaMigrationTest` deleted with S1 |
| R10 | Number formatting outside `Formats`: `new DecimalFormat` in `HistoryRows`, `Explanation`, `dist/DocFmt` | `Formats`; a guard: no `DecimalFormat` outside it | G4 | 0.5, S | a digit differs in an export | export recordings |
| R11 | YAML read through two APIs: SnakeYAML directly in 14 files, Jackson YAML in 87 | Jackson YAML | G4 | 1, H with S review | anchors, implicit types | each file's tests; pack load of every shipped pack |
| R12 | **Arrow writers by hand**: `bi/engine/ArrowIpcWriter` 216, `compute/reactive/ArrowRows` 205, beside `rachana-data` `ArrowRows` 409 that B7 brings in | `rachana-data`'s codec | G4, after B7 | 2, S | type mapping (decimals, dates) | the same batches read back by pyarrow before and after |
| R13 | `bi/engine/LiveTable` 636, `LiveTables` 330, `LiveAggregate` 393: a keyed merge into DuckDB for BI and MQ, in no plan, sharing its name with Rachana's `LiveTable` | **not decided** (section 5, item 6): a half-day reading in G3 | G3 read | 0.5, F | unknown | |

Not duplicates (checked): `DsqlParser` 840 (the SQL console's own language), `MqParser`, `QuestionNormaliser`, `PhraseParser`,
`SearchQuery`, `StructuredSearch` (no twin; the search grammar compiles to a filter tree in L1 step 4).

### 2.3 Live result sets: Drishti's LiveSet against Rachana's live tables

| # | What, where, lines | Survives / home | When | bd, model | Risk | Proof |
|---|---|---|---|---|---|---|
| L1 | Drishti `server/liveset` 8 files, **3,302** (`LiveSet` 779, `LiveSets` 767, `StorePages` 520, `LiveSetConfiguration` 434, `LiveSetSpec` 349, `LiveSetController` 248, `LivePages` 133, `LiveReader` 72), tests 1,552; console `live_results.py` 183, `live_routes.py` 125, `drishti_live-page.js`, `drishti_records.js`; `/api/v1/live/*` (7 routes); one declaration, `modules/trading/blotters/trade.blotter.yaml`. Against `rachana-live` 21 files, **4,172**, built on `g2/integration` (L1 to L7, L9, L10, half of L11). **The merge rule is identical** (`KeyedRows.versionOrder`) | Rachana's engine; Drishti keeps an adapter of about 800 lines (`SnapshotSource`, `LiveStream`, `Authoriser`, the change sink over `server/edit`, which stays: LT-8) | **G4, last lane** (DD-6); planned for i8 by LT-18 | 25 as LIVE_TABLES section 11, less about 5 not spent on S4a, C3 and B7's parallel wire: **+20 new**, O (steps 4, 7: S) | leaks between users (a release blocker); a row rule or mask not expressible as a filter tree (`rowTest`, `redact` exist for that); search-set ids change | the ported cases of LIVE_TABLES 9.1 green on Drishti's adapter; `KafkaBlotterTest` unchanged; `LiveIsolationTest` (two users); `FormApiTest`; `live/*` and `protocol/window`, `write` suites against the Drishti host; the 1.3 million position page |

### 2.4 Drawing a ViewModel, and the browser's transports

| # | What, where, lines | Survives / home | When | bd, model | Risk | Proof |
|---|---|---|---|---|---|---|
| U1 **P** | `drishti-renderer` (17 modules, 2,782; contract `[1]`, 23 kinds) against Elements `drishti-views` (21 modules, 4,699; contract 1.2, the same 22 server kinds plus 7 new; `<elements-view>` 88; hooks `links`, `fetch`, `host`, `live`, `header`, `panels`, `registerKind`) | the painter; Drishti keeps `drishti_adapt.js` and about 1,000 lines of host code (sign-in, embed token, About, drill) | G4 (B6 inside C3) | plan 15 for B6 + B7 + B8; B6 alone measures **11 to 17**: **+8 new**, O | GenUI skeleton ops have no painter form; per-panel CSV and JSON Lines export is not in the painter; shadow DOM against the console's CSS and CSP; `facets` (renderer only, in neither schema) | `test_render_parity_browser` run renderer against painter, ViewModel by ViewModel; the 75 renderer tests; EV10 |
| U2 | **Jinja draws the console's own pages**: `templates/_macros/*.html` 728 (`panels.html` 410), `drishti_live.js` 155 (patches into server HTML), `drishti_charts.js` 114, `core/half_up.py` 38. `terminal/view.html` line 68: "Jinja is the one page renderer; the client-side renderer draws embeds" | the painter through `<elements-view>`; the page sends the ViewModel, the head tools stay as the painter's `header` hook | **G4, after U1** (DD-7); in no plan | **+10**, O | first paint (server HTML against client paint: EV10 must hold); every page snapshot re-approved once; pages without script | page walk; snapshots listed with reasons (EV5 already expects this at C-III); H7 page-load baseline at or better, else the cut line |
| U3 | Frozen `static/render/1.2.0` (25 files, 3,275 of JavaScript, its own SSE code) and the retired `2.0.0` redirect; `render_dist.py` `RETIRED`, `FROZEN` | deleted; one renderer version is served | G4 | 0.75, S | none (no embedder exists) | `chart.test.mjs` compares against the painter's vectors instead of the 1.2.0 builders |
| U4 | Frozen **embed v1**: `web/embed/*.js` 14 files, 1,903 (HTML over the wire; `connection.js` 136 is a hand-written SSE client), `core/embed_stream.py` 126, `core/element_sheet.py` 268, `tools/elements_sheet.py` 65 | deleted; embed v2 on the painter is the one embed | G4 | 1.25, S | none (no embedder exists) | `test_embed_*` for v2; the embed guide rewritten |
| U5 **P** | Browser live transport: `drishti_live-hub.js` 337, `drishti_channel.js` 171, `drishti_server-watch.js` 142, `drishti_net.js` 162 on `LiveClient` (removed in Elements 3) | drishti-events `connect()`; done on the rehearsal branch | G4 (C2) | in C2's 34 | page tests not yet run | `test_live_hub.py` (14), `test_server_watch_browser.py` |
| U6 **P** | Ten SSE controllers in Java (3,002 lines) and the console hop (`channel.py` 274, `channel_relay.py` 330) | `events.v1` topics (C2: S1 to S8, C1 to C7) | G4 | in C2 | as planned | EV8 |
| U7 | Number formats in `drishti_bi-lib.js` (`B.fmt`, `B.compact`, own `Intl`) | drishti-core's formatter | G4 | 0.5, S | a digit in BI | format vectors |
| U8 | Column filter `drishti_colfilter.js` 561 (own `matches`, `test`) against `drishti-core/drishti_filters.js` 261 | core's | G4 | 2, S | a filter operator differs | filter vectors shared by both, then one |
| U9 | **BI chart builders** calling `echarts.init` directly: `drishti_table-chart-engine.js` 624, `drishti_table-chart.js` 604, `drishti_bi-visuals.js` 808, `drishti_bi-python.js` | drishti-chart (the standing rule: no bespoke UI) | **G4, Tier 2** (DD-8) | **+6**, O | BI visuals change look; options drishti-chart lacks | series parity as `chart.test.mjs`; BI snapshots |
| U10 | Expression evaluator in Python: `core/facts_expr.py` 409 | the server's evaluate (Rachana-EL); the console asks the server | G4 | 2, S | a round trip per fact | the facts' recorded values |
| U11 | The Excel-subset evaluator twice: `drishti_capacity.js` 321 and `tools/sizing_planner.py` 962 | one: the server computes the capacity sheet (T2's workbook holds the formulas), page and tool display | G4 | 2, S | sizing figures differ | the capacity workbook before and after |
| U12 **P** | Sutra editing in Python: `core/designer.py` 370 (C4 deletes it) | Studio; Rachana's `edit` ops | G4 (C4) | in C4's 5 | as planned | `test_no_sutra_editor.py` |
| U13 | `sutra_diff.py` 195, `sutra_summary.py` 70, `schemakit/decorate.py` 219 | Rachana `tools/yaml-diff` and the schema Core generates | G4, with C4 | 1, S | wording of a diff | recorded diffs |
| U14 | `drishti-insight` Explain (part of 1,631) beside drishti-core's `drishti_explain.js` 135; `elements-demo/server.py` 210 beside Rachana's Flask and FastAPI sample hosts | core's Explain widget with Drishti's content; the demo server deleted | G4 | 1.5, S | Explain's look | Explain browser tests |

`drishti-risk` (750 lines) is a domain library on core, grid and chart and wraps core's formatter: no duplicate.

### 2.5 Lake

| # | What, where, lines | Survives / home | When | bd, model | Risk | Proof |
|---|---|---|---|---|---|---|
| K1 **P** | The lake modules twice: `lake/drishti-{deltalake,iceberg,lakeio,lakeload}` 215 twin files, **42,919 lines**; plugin twins up to 38 files, 8,169; archive readers `Archives` 635, `TarStream` 250, `GzipMembers` 219 | Drishti Lake | G4 (D2, D3) | 2.5 | L1: the source plugins sit on package-private classes | D8: lakes rebuilt, counts and goldens exact, the TCK inside Drishti |
| K2 **P** | **The Delta `hadoop` engine**: `EngineKind` 82, `HadoopLake` 181, 7 tests 470, the choice in `LakeStore` (lines 29 to 31, 78 to 90), `NativeLake:83` | deleted (LK-9, confirmed by the owner); one way to read Delta | G4 | in D2 | none: Lake has no such engine, only tests proving its absence | Delta tests on the native engine |
| K3 | **What LK-9 leaves that nobody owns**: the `engine` setting and `DRISHTI_DELTA_ENGINE`, `lz4-via-native`, 12 mentions in `CONFIGURATION.md`, 9 guides, `windows.yml:29`, `config/connectors.examples/delta-s3/README.md`, and `hadoop-client-api`, `-runtime`, `hadoop-aws` in `plugin-delta/pom.xml` 44 to 51 (the Parquet class-signature jars in the lake poms stay) | deleted with K2; named in D7 | G4 | 0.5, H | a start script sets the variable | start-up refuses an unknown setting by name; docs checks |
| K4 **P** | `drishti-testroots` shim (172 test files), Drishti's own delta-kernel, Iceberg, Parquet version properties (L6, L7) | `lake-tck`, `lake-bom` | G4 (D1) | in D1 | the BOM must exist at Lake's tag | offline build |
| K5 | 400 fixture files left in the lake modules' and plugins' test resources (the rehearsal counted 322) | Lake's; deleted with their tests | G4 | 0.5, H | a Drishti adapter test still reads one | adapter tests |
| K6 **P** | A third way to fill Delta: `tools/lake/build.py` 1,013 with `stage`, `writers`, `javaload` (119 files, about 15,600 lines in `tools/lake`), beside Lake's Java loader and `drishti_lake` (1,858). LK-7 allows exactly two | `drishti_lake`; `tools/drishti.py lake` calls it (D5) | G4 | 2 (D5) | 48 Python files import `tools/lake` | affected Python tests; `mr_run` sample day |
| K7 | Inside Lake: storage-handle wrappers in three pairs (`NativeFileIO` / `FileAccess`, `Streams` / `ParquetInput`, `LocalWriteFileIO` / `StorageWriteFileIO`), about 70 lines; the three stores' snapshot and reader boilerplate, about 40; `Lz4RoutingEngine` / `NativeParquetHandler`; `Documents` / `IcebergVectors`; `Files.createTempFile` in `FilesLog` 143, 296 | `lake-io` helpers; a base in `lake-api` | **G3, before `v1.0.0`** | 3, S | Parquet footers, IO | the TCK's store-parity cases, green twice |

### 2.6 Inside Elements and inside Rachana

| # | What, where, lines | Survives / home | When | bd, model | Risk | Proof |
|---|---|---|---|---|---|---|
| E1 | HTML escape written 6 ways that differ (`drishti_plain-render.js:55`, `drishti_graph-export.js:22`, `drishti_export.js:73`, `drishti_geohelper.js:34`, `drishti_chart-panels.js:34`, `drishti_gallery-page.js:112`): one omits the double quote | one `escapeHtml` in drishti-core, the strictest | G3 | 0.5, S | **a correctness matter, not only tidiness** | export and tooltip tests with a quote in the data |
| E2 | `isObj` (5 copies), `clamp` (3), `pad` (4); clipboard write (2); `fetch` with fallback (6); timing helper (2) | drishti-core utilities | G3 | 1, H | none | each library's Node tests |
| E3 | Quick-search tokeniser in 8 files (core, grid ×2, tree ×2, inputs ×2, designer); the grid's honours quoted phrases, others do not | `drishti-core/drishti_filters.js` | G3 | 1.5, S | search matches change slightly | shared search vectors |
| E4 | **Aggregation three times**: `drishti_views-data.js:101` (masked-aware), `drishti-grid/drishti_aggregate.js:127` (the engine), `drishti_chart-vega.js:168` | the grid's engine, with the masked rule | G3 | 1.5, S | masked values counted | views derive tests, pivot tests |
| E5 | Number to text outside the core formatter: `drishti_schema.js:189`, `drishti_trace.js:160`, `drishti_columns.js:109` | `drishti_format.js` | G3 | 1.5, S | locale output | format vectors |
| E6 | What the painter must gain so U1 and U2 need no host copy: a per-panel export hook (CSV, JSON Lines); a decision on GenUI skeleton ops (`view.piece` is the painter's form) | drishti-views | G3 | 1.5, O | scope creep | parity suite |
| E7 | Hand-made buttons in about 8 gallery files | **not measured** whether each has a widget; a half-day check | G3 | 0.5 + fixes, S | | gallery tests |
| Q1 | Rachana: JSON string escaper 6 times beside `parse/Json.java:114` (`LibraryExports:186`, `ListValues:98`, `Cases:745`, `CorpusCompile:77`, `RefBridge:276`, `RefBridgeSutra:67`); `\b` and `\f` differ between copies | `Json.quote` | G3 | 0.5, S | byte-equal output is a conformance rule | CG3 byte compare |
| Q2 | Rachana: bridge and regime tools copy each other (`RefBridgeSutra` / `CorpusBind` 41 lines, and two smaller); `RuntimeMain` / `ToolsMain` 43; `BenchMain` / `Bench`; `form_browser.py` / `state_browser.py` | one helper each | G3 | 1.25, S | none | conformance run, bundle build |
| Q3 | Rachana `agg`: `Builtins`, `Holistics`, `Moments`, `MoreAggregates` repeat about 70 lines of state handling | one helper in `agg` | G3, with R4 | 1, O | numerics; the state bytes Drishti will decode in R3 | Core aggregate tests; state bytes unchanged |
| Q4 | Two programs named `rachana` (`pyproject.toml:27`, `rachana-cli/package.json:10`): gate 2 finding F12(b) | one | G3 | in the gate's fixes | | `test_one_program.py` |

Elements' detector headline is 62 duplicated lines (Lake 375, Rachana 1,166 of which about 350 are true duplicates): the
products are clean in the large; what they have is the same small helper written many times.

### 2.7 Python tooling

| # | What | Survives | When | bd | Note |
|---|---|---|---|---|---|
| Y1 | `tools/lake/filedate.py` 286 against `drishti_lake/filedate.py` 311 | Lake's (K6) | G4 | in D5 | the only file-level twin |
| Y2 | `core/render_dist.py` 152, `core/elements_pin.py` 70, `tools/packgen/elements_range.py` 25 beside Elements' `dist.py` 319, `pack.py` 114 | Elements' tools; `render_dist.py` goes with U1 and U3 | G4 | 0.5 | |
| Y3 | `tools/license_headers.py` 99 against Elements' 97 (12 lines apart); release scripts Lake 49, Rachana 94 | section 5, item 5 | | | |

### 2.8 Compatibility shims (no customer: remove; keep only for the owner's own data)

The owner's `drishti-stack` was read for names only: it sets three secrets and peers, and no old setting name. **Not inspected:**
the data folder's contents and the Windows machine. Rows marked **once** need one action on the owner's machines first (S0).

| # | What, where, lines | Decision | bd | One action first |
|---|---|---|---|---|
| S0 | **The last run**: on the i7a build, on both machines: run the tables and BI migrators, start once (renames `.uploads`, `.previous`, relocates old lakes), list any old setting name that Health reports | **do first**, then delete S1 to S6 | 0.5 | this is the action |
| S1 | BI and tables migration: `tables/migrate` 9 files 2,018; `server/bi/migrate` 7 files 570; `StoredTablesMigration` 160; `PivotFormulaMigration` 154; the `migrate` subcommand of `tablescli.py` and `TablesCli` | remove | 2, S | **once** |
| S2 | The old expression language: `bi/expr` 9 files 1,446 (`DelParser` 685, `DelToRachana` 127, legacy modes; the default is already `refuse`), `drishti.bi.expr.legacy`, `Signatures`' `legacy` field, `LegacyListAggregates` | remove (one grammar) | 1.5, S | none |
| S3 | `LegacyNames` 74 and its 10 calls (`.uploads`, `.previous`) | remove | 0.25 | **once** |
| S4 | `tools/lake/relocate.py` 89, `drishti.lakes.relocate-on-start`; Lake `LakeRelocate` 483 (it says "retired in the next release") | remove in both | 0.5 | **once** |
| S5 | `ApiAliasFilter` 109, `ApiAlias` 42, `api.legacy` in the manifest, `aliases:` in the examples; `EngineKeyAliases` 56 and `aliases:` in `engines/catalogue.yaml` | remove | 1 | **once** (old keys in `application.local.yaml`) |
| S6 | Connectors defined in `application.yaml` (`ConnectorManager` 54, 186 to 226, 788 to 790, 1680; `drishti.sources.plugin-dir`; `ConnectorLake.legacy()`); Lake `LakesCli migrate-config` and the connector migration in `LakeFiles` (about 150) | remove | 2, S | **once** |
| S7 | Legacy report format keys (`FormatSchemas` 120 to 246, `legacyConditional` in 5 yaml files); old thread notes (`LegacyImport` 115, `NoteImport` 74, the `/notes` facade; the two schema files edited in place); console redirects (`bi_routes.py` 147, 166; `help.yaml` `aliases`, `moved:`); `SharedLib.migrate` and the "deprecated settings" notes; `Convert.java:38`, `SutraCli:767`, `convertcmd.py:27` | remove | 2.5, S | none |
| S8 | Lake: `PersistedNames` 68 (reads `drishti.*` table properties after `lake.*`), the `${drishti.x}` cross-resolve in `Lakes.java:544` and `LakeFiles.java:312, 534` | remove; **every owner lake is rebuilt by D8** with the pinned loader, so no table carries the old names afterwards; lake files renamed once | 1, S | **once** (rebuild; Windows too) |
| S9 | Lake: the Hadoop-framed `LZ4` read path (`Lz4Pages`, `Lz4RoutingEngine`) | **keep**: it reads Delta tables written by other engines (the compliance rule), not our past | 0 | |
| S10 | Rachana: `conformance/1/el/coverage-old-spellings.yaml` and the `legacy:` signature fields (old function names, not level 1.0); `docs/plans/DRISHTI_MIGRATION_TODO.md` and the runner patch after G4 | remove; if an old spelling is part of frozen level 1.0 it stays and this row closes as "is the baseline" | 0.5, S | check against `compat/levels/1.0` first |
| S11 | Elements: `KIND_ALIASES` (chart kinds `column`, `bars` ...), aggregation `ALIASES` (`average`, `mean` ...) | remove; one name per thing | 0.5, S | the corpus must not use an alias (CG3 says) |
| S12 | One-off split tooling in Lake (`tools/split/*`), `MOVE_ALLOWLIST.md`, the 66 host-word exemptions | remove after G4; the exemption list reaches zero or each survivor has an end date | 0.5 | |

Kept and not shims: `compat/levels/1.0` and `compat/customers/drishti/2.9.11.i7a` (the frozen baseline; `LangFilesTest` and
`CustomerCorpusTest` require them), the key-name normaliser in `drishti_keys.js` (browser differences).

### 2.9 Documents

Text is almost never copied between repositories (0 to 1 shared lines per pair): the duplication is by topic, two documents
teaching the same thing.

| # | What | One home | When | bd |
|---|---|---|---|---|
| D1 | `docs/guides/RACHANA_REFERENCE.md` (9,102 lines) against Rachana's `spec/1/SPEC.md` and guide | Rachana's; Drishti keeps the profile (match, links, provenance, entity kinds) and links | G4 | 2, S |
| D2 | `docs/guides/FUNCTIONS.md` 381 and `docs/architecture/FUNCTION_CATALOGUE.md` 409 against Rachana's `functions.yaml` | one generated page from the instance's catalogue (it changes anyway: P11) | G4 | 1, S |
| D3 **P** | `docs/architecture/VIEWMODEL.md` 920 against `spec/1/VIEWMODEL.md` | the specification; Drishti keeps the profile's fields (A14, B6) | G4 | planned |
| D4 | `ELEMENTS_IN_DRISHTI.md` 359, `EMBED_GUIDE.md` 1,084 against Elements' `DESIGN`, `USING`, `CONTRACTS` | Elements'; Drishti keeps the integration half; embed v1 chapters go with U4 | G4 | 1, S |
| D5 **P** | `DATA_LAKE_GUIDE.md` 3,120, `DATA_ARCHITECTURE` section 3 against Lake's docs | Lake's (D7) | G4 | planned |
| D6 | Rachana `docs/design/SPEC.md` 252 and `DATA_PROTOCOL.md` 244 beside `spec/1/SPEC.md` 809 and `PROTOCOL.md` 306 | the specification; the design notes keep decisions only | G3 | 0.5, H |
| D7 | Console `help.yaml` (639 lines) against `docs/`: **not measured** | measure, then one home per fact | G4 | 0.5 + fixes |

## 3. The tools "extras": which remove an existing duplicate

DRISHTI_TOOLS_DESIGN section 6 parks 11.5 bd of extras in the combined i8, "each when a second consumer exists". Tested against
the code, the rule gives a different split: a duplicate that exists today already has its second consumer.

| Extra | Existing duplicate today? | Verdict |
|---|---|---|
| Hash helpers, canonical JSON (`tools-codec`) | **Yes**: T3, T4 | **now** (the two classes only) |
| `DeterministicZip`, `Ed25519` in `tools-codec` | No: Rachana's stay in Core and its tools (TL-9) | waits |
| CSV | **Yes**, inside Drishti: T5 | **now**, with our own 192 lines; TL-10 (Commons CSV) becomes "test oracle only" |
| `TempFiles` | **Yes**: T6 | **now** |
| Business calendars | **Yes**, but the home is Rachana Core, not tools: T8 | **now**, in G4; `tools-time` gets no calendar |
| Windows-ROOT trust | No code anywhere | new feature, waits |
| xlsx reader | No (tests use LibreOffice and openpyxl) | new feature, waits |
| Retry and rate limit | No second copy (`RetryPolicy` only in Drishti). `RateLimits` 72 against `AskRateLimiter` 61: **not verified** | waits; a 15-minute check in G4 |
| Rachana's server on tools (path anchoring 40 lines, `Ed25519` 86) | Yes, about 120 lines | stays an exception (section 5, item 4) |

So of the 11.5 bd: about 6 bd move into i7b (they are T3 to T6, T10, T11) and about 5.5 bd (Windows-ROOT, the reader, retry)
are new features and wait.

## 4. The questions asked

**Can the LiveSet switch-over move into G4, and should it?** It can: the engine is built in Rachana (4,172 lines, the read side
and the edit path), the merge rule is identical, and Drishti's one declaration and seven routes are a small surface. It should,
for a reason the 25 bd figure hides: if it waits, G4 builds a `server/rdp` mount beside `/api/v1/live/*` (the "second wire"
LT-17 exists to remove), an `events.v1` provider for LiveSet and a rewrite of the live pages, and i8 deletes all three. What
must be true at the G3 gate (the entry gate): `rachana-live` in a tagged Rachana; the grid's live-mode edit UI built (L8); the
query tier placing new live rows; the `live/*` and `protocol/window|write|approve` suites moved from "unverified" to verified;
the scale run done. If any is missing, the lane does not start and the plan is today's (LT-18). Recommended: yes (DD-6).

**Can `<elements-view>` replace Drishti's renderer in G4 without screen rewrites?** For what the renderer draws today, yes:
it has one call site inside the console (Explain), the embed v2 routes and the loader table; an adapter behind the same
`render` / `patch` / `subscribe` entry keeps every caller, and B6 already plans exactly this. The contracts are close: the same
22 server kinds, the same five patch operations (the painter adds `rows`), links and masks through hooks. The differences to
close: contract 1 against 1.2, the GenUI skeleton operations, per-panel export, `facets`. The surprise is elsewhere: the
console's own screens are not drawn by the renderer, so replacing it changes no screen, and the second way to draw (Jinja, U2)
stays unless it is taken on. That one **is** a change to every screen, with first paint as its risk. Recommended: U1 as
planned with the honest size; U2 in G4 behind a measured gate (DD-7).

**The `sum` double definition.** It is the whole kernel, not one name: 44 names, 3,424 lines, a file-for-file copy. Step 8.3h
removes it and is correctly placed in G4. Two things change: P4 is investigated now (R4), and the repeated state handling
inside Core's own `agg` is folded before the tag (Q3), so Drishti's state-bytes test is written once against final bytes.

**The Delta hadoop engine.** Retired in G4 as decided. The audit adds only the leftovers nobody owned (K3).

## 5. What is not removed in i7b, with the reason

| # | What | Lines | Why it stays | What binds the two |
|---|---|---|---|---|
| 1 | Rachana Core's own `Sha384`, `Rlib`, `ViewJson`, and `NumberText` twice (Core 94, the JavaScript build's 355) | about 800 | the JavaScript build has no `MessageDigest`, zip or the JVM's number printing (TL-9) | conformance on JVM and Node, byte-equal |
| 2 | Lake's Java loader and the Python path, `FileDates` / `filedate.py` | 1,858 Python | LK-7: exactly two, by the owner's rule | the TCK's `^python` rows, `python-differences.txt` (only shrinks), `TwoRuntimesTest`. **Gap to close in G3: no parity test was found for `config.py` (211 lines)**; 0.5 bd |
| 3 | The Python SDK's filter evaluator (`server.py` 161 to 220) | 60 | the SDK's host runs without a JVM | the protocol cases in `test_protocol_cases.py` |
| 4 | Rachana's server and tools not on `drishti-tools` | about 120 | a dependency-free server, days before its tag (TL-7); taking a dependency is a larger change than the 120 lines | revisit at the first Rachana minor after G4 |
| 5 | Scripts that cannot share a package: `license_headers.py` in Drishti and Elements (97), the release scripts (49, 94), Rachana's own four guard classes (about 200) | about 440 | no shared Python distribution exists between the repositories, and Rachana takes no test dependency; inventing one for 440 lines is more machinery, not less | each is under 100 lines and listed here so it cannot grow unseen |
| 6 | `bi/engine/LiveTable`, `LiveTables`, `LiveAggregate` | 1,359 | not known to be the same job: it materialises into DuckDB for SQL, which `KeyedRows` does not. **The one honest open item**: a half-day reading in G3 (R13) decides whether it folds onto the live engine in G4's L1 lane or is renamed and kept | |
| 7 | Drishti's write-back (`server/edit`, 6,218) beside Rachana's `Outbox` | | not two in one process: each host installs exactly one sink (LT-8) | |
| 8 | Level 1.0 files and the frozen corpus | | the proof baseline (owner) | `LangFilesTest`, `CustomerCorpusTest` |

Nothing else found is deferred.

## 6. Guards, so it stays closed

Added to the evidence pack (EV6 grows from "no compiler and no painter of its own" to "no second copy of anything in this register"):

| Guard | Rule | Where |
|---|---|---|
| No second copy | a class named like a tools, Lake or Rachana public class fails the build (the lists are generated from the pinned jars, not typed) | Drishti, Lake |
| One drawer | no panel markup outside the vendored Elements: `_macros/panels.html` absent, `echarts.init` only inside the vendored folder (extends `test_no_own_painter.py`) | Drishti console |
| One wire | no `SseEmitter` outside the events allow-list (EV8); no `/api/v1/live`; `HttpClient.newBuilder` only in `tools-net`; `MessageDigest.getInstance`, `ZipOutputStream`, `DecimalFormat` only in their one home | Drishti |
| No shim words | `@Deprecated`, `legacy`, `alias`, `migrat` in main sources fail unless listed with an end date; the list only shrinks | all four |
| The copy ratchet | `tools/duplicates.py` (the existing ratchet) lowered to the post-G4 figure; the same detector run in each product's CI with its headline number recorded | all four |
| Twins are bound | every row of section 5 names its binding test; a twin without one fails | all four |

## 7. Cost, and the effect on i7b's remaining time

### 7.1 New work (beyond the accepted plans and beyond what the rehearsal already found)

| Where | Items | bd |
|---|---|---|
| **G3: `drishti-tools`** | T3 to T6, T10, T11 (codec's two classes, csv, files, guards as a test jar) | 5.5 |
| **G3: Lake** | adopt files and guards (T6, T10) 1; K7 3; S4, S6, S8, S12 (Lake's half) 2.5; the `config.py` parity test 0.5 | 7 |
| **G3: Rachana** | R4 (P4) 1; Q1 to Q3 2.75; S10 0.5; D6 0.5; R13's reading 0.5 | 5.25 |
| **G3: Elements** | E1 to E7 8.5; S11 0.5 | 9 |
| | **G3 total** | **about 27** |
| **G4: Tier 1** (same job twice, shims, documents) | tools adoption T3 to T11 7.5; engine R5, R6, R9 to R12 7.75; UI U1's uplift 8, U3, U4, U7, U8, U10, U11, U13, U14, Y2 11.5; lake K3, K5 1; shims S0 to S7 and Drishti's half of S8 9.5; documents D1, D2, D4, D7 4.5; guards of section 6 1.5 | **about 51** (less 2 where a row is partly inside an accepted step: **49**) |
| **G4: Tier 2** (switch-overs, each gated) | L1 live tables 20; U2 Jinja to the painter 10; U9 BI charts 6 | **36** |
| | **G4 total** | **about 85** |

### 7.2 What it does to the milestones

| | Before this audit | After |
|---|---|---|
| G3 | about 413 bd + libraries; tools 10.5, Lake lane 64 | **+27 bd**, spread over four repositories and so parallel: **2 to 3 working days** with three builders |
| G4 | plan 87 + Lake D1 to D9 14.5 + tools 5 = 106.5; the rehearsal's own re-estimate adds about 16: **about 122** | **about 207 bd** (Tier 1 only: about 171) |
| G4 calendar | at 14 bd a day (the planning rate) about 9 days; at 8 a day about 15 | at 14 a day about 15 days; **at 8 a day about 26**: **+6 to +11 working days** (Tier 1 only: +4 to +6). G4 is one repository with a three-builder cap, so the lower rate is the one to plan on |
| Drills | one (PD1), a second if the first needs fixes | plan on **two** |
| i8 | about 28 bd (role Homes, K5, carry-overs) + the live switch-over 25 + tools extras 11.5 = about 65, + whatever U2, U9 and the shims would have become (never priced) | **about 34 bd**: role Homes, K5, carry-overs and the 5.5 bd of genuinely new tools features. No switch-over, no deletion work |

The figures carry the same uncertainty as their sources: the rehearsal found the Drishti side of two plans understated by
about 1.7. Tier 2's 36 bd is the part most exposed to it.

### 7.3 Order

1. **Now, in G3**: R4 (P4) first, because a wrong answer in a product about to tag outranks everything here; the tools modules;
   the in-product folds (E, Q, K7) and the product-side shims, all **before** the three `v1.0.0` tags, since each tag freezes a
   surface.
2. **At the G3 gate**: the entry facts for L1 (section 4) and for U2 (the painter's export hook, E6; the H7 page-load baseline).
3. **G4, first**: S0 on both machines, then the shims (S1 to S7): deleting code first makes every later lane smaller.
4. **G4, the planned chain** as the rehearsal orders it (Rachana half, Elements pin and events, Lake), with the Tier 1 rows
   riding the lane that touches the same files (T rows with D1 and D3; R rows with 8.3; U3, U4 with B6).
5. **G4, last, each on its own branch**: U2, then L1 (its console step draws through `<rachana-view>`, which U2 brings), then U9.
6. Goldens after every lane; a freeze; the drill.

### 7.4 Cut lines (agreed now, taken at a named moment, never during a drill)

| Cut | Taken when | What happens |
|---|---|---|
| DC1 | an L1 entry fact is missing at the G3 gate | L1 does not start; G4 keeps today's plan for live sets; the switch-over opens the next release (LT-18 as written) |
| DC2 | U2 misses the page-load baseline by the pre-drill freeze | U2's branch is not merged; Jinja stays for one release, listed as an open duplicate in the evidence report |
| DC3 | U9's BI snapshots are not approved by the freeze | as DC2 |
| DC4 | G4 has consumed 26 working days and Tier 1 is not green | the owner chooses: the remaining Tier 1 rows by size, largest first, or the date |

A cut is a listed, dated exception in `PRODUCTS_MIGRATION_EVIDENCE.md`, never silent.

## 8. Decisions DD-1 to DD-22

| # | Question | Recommendation |
|---|---|---|
| DD-1 | The principle | **Every row of section 2 is closed inside i7b**: Tier 1 without conditions, Tier 2 behind entry gates and the cut lines of 7.4, section 5's eight exceptions listed with their binding tests. Nothing else is deferred |
| DD-2 | The price | **Accept about 27 bd in G3 and about 85 bd in G4** (6 to 11 more working days for G4, two drills planned) for an i8 of about 34 bd with no deletion work |
| DD-3 | Tools extras | **Pull into `drishti-tools` now**: `Hashes`, `CanonicalJson`, `tools-csv` (our code, Commons CSV as oracle only, replacing TL-10), `tools-files`, `tools-guards` as a published test jar. **Leave for later**: Windows-ROOT, the xlsx reader, retry, `DeterministicZip`, `Ed25519` (new features or no second copy) |
| DD-4 | P4, the wrong `sum` | **Investigate now, in G3, before Rachana's tag** (1 bd, Opus); its case joins conformance; 8.3h does not start without it |
| DD-5 | The engine twins no step deleted (R5, R6, R10 to R12) | **Delete in G4, never "delegate"**: Core's `IdText`, `ListValues`, `Nodes.at`, `SourceFailures`, `RelativeDates`, `SafeRegex`, `BusinessCalendar`; compilers only from the holder; Jackson YAML only; `rachana-data`'s Arrow codec |
| DD-6 | The live switch-over | **Into G4 as its last lane**, replacing LT-18's "never a G4 dependency" with the entry gate of section 4 and cut DC1; B7's mount is built once, for `LiveTables`; no `events.v1` provider and no page rewrite is written for LiveSet |
| DD-7 | Jinja as a page renderer (U2) | **Retire it in G4 after B6**: every Sutra page is painted by `<elements-view>`, behind the page-load gate and cut DC2. Without this, EV6's claim is false whatever the guards say |
| DD-8 | BI charts drawn with ECharts directly (U9) | **Onto drishti-chart in G4**, cut DC3 |
| DD-9 | Frozen renderer 1.2.0 and embed v1 (U3, U4) | **Delete in G4**: no embedder exists; one renderer version, one embed |
| DD-10 | GenUI skeleton operations in the renderer | **Not ported**: GenUI inside Drishti is i9 and will use the painter's `view.piece`; Explain is checked first (0.25 bd) and, if it streams with them, moves to `view.piece` inside U1 |
| DD-11 | `facets`, the renderer-only kind in neither schema | **Delete** unless the check in U1 finds a server sender (none found in `PanelKind`); if one exists it becomes a `registerKind` in Drishti's host code |
| DD-12 | The printer's text (P10) | **Core's** (`and`, `where=`); expected texts regenerated |
| DD-13 | The old expression language and every migrator (S1, S2) | **Remove**, after S0's last run on the owner's two machines |
| DD-14 | Aliases and old names (S3 to S7, S11) | **Remove**; an unknown old name is refused at start-up by name, never silently ignored |
| DD-15 | Lake's read-both of `drishti.*` names (S8) | **Remove before Lake's `v1.0.0`**; the owner's lakes are rebuilt in D8 (Linux and Windows) rather than converted |
| DD-16 | Lake's Hadoop-framed LZ4 reader (S9) | **Keep**: interoperability with tables other engines wrote, not compatibility with our past |
| DD-17 | The hadoop engine's leftovers (K3) | **Into D7 by name**: the setting, the variable, the jars, 12 + 9 document places, the workflow line |
| DD-18 | In-product folds (E1 to E7, Q1 to Q3, K7) | **Before each product's `v1.0.0`**, E1 first (it is a correctness matter); each product's CI records its detector headline |
| DD-19 | Guards across repositories (T10) | **`tools-guards` for Lake and Drishti**; Rachana keeps its four classes and Elements its script as listed exceptions (section 5, items 4 and 5) |
| DD-20 | Documents | **One home per topic**: the product's repository teaches the product; Drishti's guides keep only the integration half and link (D1 to D7) |
| DD-21 | `bi/engine/LiveTable` (R13) | **Read in G3** (0.5 bd, Fable): fold in L1's lane if it is the same job, else rename so two things do not share a name |
| DD-22 | The guards of section 6 and EV6's wider claim | **Yes**; the register above becomes a generated table in the evidence report: one row per item, closed, cut (with date) or exception (with its binding test) |

## 9. What this audit did not measure

- The owner's data folder and the Windows machine (S0 exists for that reason).
- The browser suites on the rehearsal branches (not run there either), so U1's and U2's risks are read from code.
- Which tests pin each shim (counted only for the renderer: 75 Python tests in 9 files, 8 Node tests).
- `help.yaml` against `docs/` (D7); the gallery's hand-made controls (E7); `RateLimits` against `AskRateLimiter`;
  `config.py` against the Java configuration reader; Lake's three retention classes line by line; whether Rachana's Python CLI
  (147 lines) repeats `rachana-cli`.
- The plugin twins of K1 are matched by file name: 8,169 lines is an upper bound.
- "About 90 callers" of `tools/lake` (LAKE_DESIGN D5) was not reproduced: 48 Python files import it (37 outside tests), 66
  files name it.
