<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7b gate 2 review: Rachana 1.0 and first products

Review of milestone G2 of [I7B_PRODUCTS_PLAN](I7B_PRODUCTS_PLAN.md) against [I7B_GATE_CHECKLIST](I7B_GATE_CHECKLIST.md), in the
format of [I7B_GATE_1_REVIEW](I7B_GATE_1_REVIEW.md). A point-in-time inspection, not a drill. Run on 2026-11-21 on JDK 21
(`/usr/lib/jvm/java-21-openjdk-amd64`), Node 22 and Python 3 (`uv`), in fresh clones from `origin`. Judged from the code and from
my own runs; no builder summary was used as evidence. Nothing was installed into the local Maven repository; no product code was
changed.

| Repository | Branch | Commit reviewed |
|---|---|---|
| `drishti-rachana` | `g2/integration` | `86e8d27` (full run), then `2391142` (re-run: it landed during the review and holds the fix `ae9f3de`) |
| `drishti-elements` | `g2/integration` | `d1db672` |
| `drishti` | `main` | `9ce7c3170` (read-only: untouched) |

Scope as the owner cut it: the AI kit and MCP moved to the GenAI release, desktop hosts parked, docs minimal until the docs wave,
bigger bundles acceptable (budgets are guards and are restated). Those are not findings here.

## Verdict

**PASS WITH CONDITIONS. No blocker is open at `2391142`.** The language work is real and well pinned: three runtimes give the
same bytes at levels 1.0, 1.1 and 1.2, level 1.0 replays, the frozen-case changes are honest, the server's security design is
sound. One blocker existed at `86e8d27` (run-to-run different ViewModel bytes) and is fixed at `2391142`; I verified the fix.

The conditions are about **what `v1.0.0` would freeze**. Rachana must not be tagged `v1.0.0` until the must-fix list of
section 3 is done: the build does not run most of what G2 built, the binder proof (F13) is mostly a proof about empty panels,
49 frozen cases are run by nobody, and the API surface is too wide and partly a test adapter.

## Owner summary

1. **Build:** `./mvnw clean verify` green on a fresh clone (1,080 Java tests at `86e8d27`, 1,081 at `2391142`; 33 Node tests; 0 skipped; the reactor builds both bundles).
2. **Conformance:** 2,563 cases. Level 1.0: 1,958 pass, 0 fail. Level 1.1: 2,220 pass, 0 fail. Level 1.2: 2,395 pass, **2 fail** (unverified, stale expectations). JVM = Node = Python-V8 byte for byte at each level (at `2391142`).
3. **Fixed during the review:** a form's `options`, `steps` and `rules` were written from `Map.of`, so two JVM runs gave different bytes and the corpus test failed 1 run in 6. Fixed in `ae9f3de`; 8 corpus runs and a repeated 1.2 run are now identical.
4. **Frozen cases:** every changed or withdrawn case was **failing or unanswered in the level 1.0 recording**; no passing case was edited, moved or removed. One real weakening: problem `path` dropped (the SPEC still promises it).
5. **F13 is not enough for `v1.0.0`:** 445 of 478 identical, but 91% of the tables and 96% of the line charts in the recorded views are **empty**; in 293 of 463 views every data panel is empty. The recorder must record source rows.
6. **The build does not test G2:** conformance at 1.1 and 1.2, the protocol cases, the Python SDK and every browser test run only by hand. Three Python tests are red today and nothing noticed.
7. **Security:** SQL push-down, masking, CSRF, sessions and CSP are sound. Three gaps: no `Host` check in `auth: none` (DNS rebinding), the painter's `requires` is fail-open, a hand-written filter evaluator in the static host.
8. **API:** 4,199 lines, 417 public classes, 247 public records; the baseline covers 2 of 7 modules; the JavaScript facade is the conformance adapter. A freeze list is in section 4.
9. **Elements:** 378 Node tests and 172 browser/static tests green (1 skipped with a stated reason); build, API and licence checks green.
10. `main` and tags are untouched in all three repositories. Nine owner decisions are in section 5.

## 1. Checklist

| # | Result | Command and evidence |
|---|---|---|
| C1 | **pass at 1.0 and 1.1; 2 unverified failing at 1.2** | `rachana_conformance.py --jvm`: `2563 cases: pass 1958, fail 0, unsupported 605`. The command's default is **level 1.0** (`rachana_conformance.py:425`), so it does not look at G2's work; I ran `--language 1.1` (`pass 2220, fail 0`) and `--language 1.2` (`pass 2395, fail 2`): `live/declaration#blotter-reads-and-gives-its-schema` and `#read-only-table-with-a-compound-key` (F5). |
| C2 | **pass** | `tools/js/build_js.py`: runtime 2,049,897 bytes (Brotli 501,454, SHA-256 `fc0cac97…`), tools 2,174,191 bytes (Brotli 527,232, SHA-256 `6ed159b9…`); its own run at 1.0, 1.1, 1.2: 0 verified failing, runtime = tools on every case the runtime answers. `--node`: same counts as C1 at each level. |
| C3 | **pass** | `--runtime python-v8 --language 1.2`: `pass 2395, fail 2`, same two cases. |
| C4 | **fail at `86e8d27`, pass at `2391142`** | `86e8d27`: 1.0 and 1.1 byte-identical JVM/Node; **1.2 differed in one line, and JVM differed from JVM** (`fire/forms#submit-runs-only-after-the-rules-pass`: `{"label","value"}` against `{"value","label"}`). `2391142`: two JVM runs, Node and JVM identical at 1.2 (F1). |
| C5 | **flaky at `86e8d27`, pass at `2391142`** | `CustomerCorpusTest`: 1 failure in 6 runs at `86e8d27` (`recordedBindsReplayThroughCoresBinderByteForByte`); 8 of 8 green at `2391142`. 478 Sutras compile byte-identical; binds: 454 compared, 24 listed (F2). |
| C6, C7 | deferred to G4 | As at G1: the products are not wired into Drishti. |
| C8 | **pass** | `build.py --check` 0, `elements_api.py --check` 0, `license_headers.py` 0, `node --test`: 378 pass, 0 skipped; `pytest -q tests -n 8`: **172 passed, 1 skipped** (`test_views_spec.py:50`, no sibling checkout; with `DRISHTI_RACHANA` set it passes: the pin equals `spec/1/viewmodel.schema.json`, sha256 `698f996e…`). The known flake did not show in one run. |
| C9 | **pass** | `coverage.py`: 208 of 208 Core functions in a case; `reserved_names.py --check` current; `ReservedNamesTest`, `ContractAppendOnlyTest`, `LevelReplayTest`, `ApiSurfaceTest`, `FrozenCasesTest` green. |
| S1 | **pass (pre-release)** | Section 2.1. `FROZEN.sha384` says `state: pre-release`, so CG1 enforces nothing yet. |
| S2 | **pass with a note** | `levels.py check 1.0`: `identical (2048 identical, 5 newly answered, 59 listed as moved or withdrawn, 465 cases added since)`. Two of the five "newly answered" are in fact deleted cases (F9). Only `compat/levels/1.0` exists: 1.1 and 1.2 are written at the release (plan: "levels 1.0 to 1.2 replay"), so their replay is not applicable yet. |
| S3 | **pass** | `ContractAppendOnlyTest` green; `spec/1/released/` holds only `viewmodel-1.0.schema.json`: contracts 1.1 and 1.2 must be frozen there at the tag. |
| S4 | **pass with a note** | `git diff v0.11.0 -- java/api-surface.txt`: 900 lines added, 0 removed. Section 4. |
| S5 | **pass** | `promotions: []`, `switches: []`. |
| S6 | **pass** | Drishti `main` = `9ce7c3170`, as at G1. |
| S7 | **pass** | `git ls-remote`: Rachana `main` `7fa6e38` (`v0.11.0`), Elements `main` `f51b7f3` (`v3.1.0`); tag lists as at G1. |
| S8 | **pass** | `elements_api.py --check` 0; `git diff origin/main -- docs`: 9 reference lines reworded, no export removed; additions are SplitPane, Tabs, NavList, ColumnMap, the form field type `columnmap` and painter options. |
| Q1 | **partly** | Built and tested: PY1 to PY8, exact decimals, let and functions, state, effects, forms, typed fragments, libraries core and finance (145 examples, LG1 to LG5 and LG8 pass, gallery 31 + 57 pages), L-21, live tables read side, Data Protocol on JVM and Python, `<rachana-view>`, CLI, Studio, generated language files. Placeholders only, as cut: `js/packages/rachana-electron`, `js/packages/rachana-mcp` (10 lines each). |
| Q2 | **fail (three duplicates)** | F6 (a second filter evaluator), F12 (two `rachana` commands, nine copies of a default level). Guards green. |
| Q3 | **pass** | `LicenceHeaderAndLineCapTest` green; largest source `TypedFragment.java` 1,737 lines. |
| Q4 | **not guarded** | Bundles grew 39% since G1 (Brotli 361 KB to 501 KB runtime). Accepted by the owner's rule, but no budget guard exists for the Rachana bundles (F13). Elements budgets green. |
| Q5 | **pass** | No secret in the diff. `examples/server-demo/users.yaml:8` holds a PBKDF2 hash of a demo password: fine as a sample. No AI attribution in any commit (161 Rachana, 15 Elements, all `ajsinha`). |
| Q6 | **pass** | Masked columns refuse filter, sort and group (`RowQuery.java:166, 253, 258`; `LiveTables.java:404`); Arrow carries a mask column. |
| Q7 | **pass** | `SafeRegexBudgetTest`, `ElLimitsTest` green; no file or network call from an expression. |
| Q8 | **pass with a note** | Server CSP (`RachanaServer.java:53`): `script-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`; `style-src` allows `'unsafe-inline'`. Painter: no `innerHTML`, links through `safeHref`; the chart-click browser test asserts zero CSP violations. |
| Q9 | **pass** | `DependencyAllowListTest` green; vendored Arrow JS and js-yaml carry their licences. |
| D1 | not judged | Docs are minimal until the docs wave (owner). Stale lines noted in F14. |
| D2 | **pass** | `CHANGELOG.md` has an entry per lane. |
| D3 | **pass** | Both integration branches are on `origin`; nothing local was used. |
| D4 | **partly** | `DRISHTI_MIGRATION_TODO.md:296` still says the libraries come from the private companion repository (L-12 is superseded). |

## 2. The questions asked of this gate

### 2.1 `FROZEN.sha384`: legitimate corrections or silent weakening?

`git diff v0.11.0 -- conformance/1/FROZEN.sha384`: 55 lines added, 11 removed (10 files changed, 1 file withdrawn). I read every
changed file against its G1 version and checked each against the level 1.0 recording (`compat/levels/1.0/results.jsonl`).

**The decisive fact: of the 59 cases listed in `moved-cases.yaml`, 58 were recorded `fail` and 1 `unsupported` at level 1.0. No
case that passed at G1 was edited, moved or withdrawn; all 1,924 passes of the recording are still identical.**

| File | Change | Judgement |
|---|---|---|
| `el/distributions.yaml`, `el/text.yaml` | one case each `verified: false` to `true` | stronger |
| `el/messages-level-1-1.yaml` | suite verified; two `**` message cases deleted | legitimate (PY6 makes `**` the power); but see F9 |
| `el/numbers-exact.yaml` | 26 cases `since: "1.1"`, suite verified | legitimate: they failed at 1.0 (the reference's doubles, N5) |
| `el/text-code-points.yaml` | 9 cases `since: "1.1"`, suite verified | legitimate, same reason |
| `el/reserved.yaml` | 11 cases to 1.1 (4 with new words: `=>`, `..`, `}`, `*/`), 9 withdrawn (tokens defined at 1.1) | legitimate; the 1.0 refusals are pinned in the reference's words by `el/syntax-at-1-0` |
| `plan/examples/plan.yaml` | `select` to `dropdown` | correction of a case that never ran |
| `problem/wiring.yaml` | `emits-on-tick` gets `until: "1.1"`, a 1.2 twin added | legitimate (the list of built triggers grew) |
| `problem/examples/problem.yaml` | `-l11` case verified; `profile-key-without-the-profile` to 1.1; **`path` removed from three expectations** | **a weakening against SPEC 6.1** (F7) |
| `protocol/examples/bridge.yaml` | `bridge-rows-request-answered-once` withdrawn | legitimate: it sorted a table that does not declare `sort`; `protocol/host#bridge-rows-of-a-sortable-table` replaces it |
| `edit/examples/edit.yaml` | file withdrawn | legitimate: two unverified placeholders; `edit/ops` pins the real operations |

Notes on the mechanism: (a) eight new case files are not listed in `FROZEN.sha384` (`fire/change`, `plan/live`,
`problem/control-change`, `problem/live`, `problem/series`, `sutra/binder/live`, `series`, `treemap-bullet`): harmless before the
release, `freeze.py` must list them at the tag; (b) `FrozenCasesTest.everyCaseFileOfTheSuiteIsListedAtRelease` asserts nothing
(`FrozenCasesTest.java:100`: `assertNotNull(unlisted)`).

### 2.2 `moved-cases.yaml` and `known-differences.txt`

- `moved-cases.yaml`: 59 entries (47 moved to 1.1, 12 withdrawn), each with a reason, each one failing or unanswered in the
  recording. `levels.py` refuses a wrong entry (`LevelReplayTest.theReplaySkipsOnlyListedMovedCases`). **Justified.** One hole: F9.
- `known-differences.txt`: 70 conformance entries (fdlibm last-place differences, Core's aggregates, PY1, the `match` key, the
  code rename, SafeRegex): each names its step and reason, and the case tolerances pass both sides. **Justified.**
- The bind entries: 33 at `86e8d27`, **24 at `2391142`**. The nine "the reference wrote these from `Map.of`; Core writes them in
  declaration order" were **wrong about Core** at `86e8d27` (Core used `Map.of` too: `FormViews.java:80, 126, 133`), which is why
  the test flipped. `ae9f3de` fixed Core and compares those objects in sorted key order. The remaining 24 (15 could not bind,
  7 map lookups failed, 2 pivots) are honest about the recorder, and are the subject of 2.3.

### 2.3 F13: is 445 of 478 enough before `v1.0.0`?

**No. The recorder must improve first.** The count is true but says less than it seems:

- The recorder binds one synthetic record per Sutra with **no source router and no table provider**
  (`CoreBinds.java:88`: `PivotEngine.NONE, TableProvider.NONE`; "No source, link or mask is read: the reference recorded none").
- In the 463 recorded views: **1,312 of 1,447 tables, 829 of 863 line charts, 591 of 622 bar charts, 260 of 260 timelines and
  400 of 400 links panels are empty**. In **293 of 463 views every data panel is empty**. What is proven identical is titles,
  strips, key-value panels, controls, layout, tabs and empty states; row binding, formats in cells, sorting, limits, totals and
  chart series are proven on about 9% of the tables.
- 22 Sutras have no reference view at all (15 threw, 7 failed a lookup), all because the router is null.

Why it matters now: Drishti migrates at **level 1.0** (`DRISHTI_MIGRATION_TODO` section 9), and O5 freezes level 1.0 at the
`v1.0.0` tag. A binder difference found only by the G4 evidence would then need a level switch or a broken freeze.

Required before the tag: the recorder gives the reference a **recording source router and table provider** with synthetic rows
(the host calls already replay through `RecordedHost`), the corpus is re-exported, and the bar is: 0 Sutras the recorder cannot
bind, every panel kind non-empty in at least one bind, under 10% of data panels empty, the pivot compared through a recorded
`PivotEngine`. The alternative is decision O2.

### 2.4 The 1.0 API review

Section 4 holds the freeze list. The short form: the Java baseline is honest but too wide and covers only `rachana-api` and
`rachana-core`; the JavaScript facade is the conformance adapter with global state; three host-facing shapes are not ready
(`SutraProblem`, the profile's panel kinds, the default level).

### 2.5 Determinism, security, one way per job, skips, flaky tests

Findings F1, F3, F4, F6, F8, F10, F11, F12. No silent skip in the reactor: `node_tests.mjs` fails on a skipped Node test, and
`LevelReplayTest` ran its replay. No test depends on a sibling checkout's branch: Elements' pin test skips with a stated reason,
and Rachana vendors the Elements pack. The cross-repository fixture `tests/support/fire_cases.json` has no hash pin (F15).

### 2.6 Built but not tested by the build

Finding F3: this is the largest gap of the milestone.

## 3. Findings

Severity: **blocker** (the gate cannot close), **must-fix-before-1.0** (before the `v1.0.0` tag), **later**.

| # | Severity | Finding | Evidence |
|---|---|---|---|
| F1 | blocker, **fixed in `ae9f3de`** | Core wrote a form's `steps`, `options` and `rules` (and a problem's `at`, a grouped query's columns) from `Map.of`: the ViewModel's bytes changed from one JVM run to the next. CG3 failed 1 run in 6; C4 failed at level 1.2. | `86e8d27`: `rachana-core/.../wire/FormViews.java:80, 126, 133`; `results/jvm-1.2.jsonl` against a second run, line 1944. Verified fixed at `2391142`: two 1.2 runs identical, 8 corpus runs green. The new guard (`ScreenWireOrderTest`) covers `wire`, `view`, `bind` only: `rachana-data` and `rachana-live` still build JSON from `Map.of` (`StoreOverlay.java:190`, `LiveReferenceServer.java:237`), and `CoreDeterminismGuardTest` does not look for it. Widen the guard. |
| F2 | must-fix-before-1.0 | The F13 binder proof is mostly empty panels; 22 Sutras have no reference view. | Section 2.3. |
| F3 | must-fix-before-1.0 | **The build (and CI: `./mvnw -B verify`) does not run what G2 built.** (a) `RunnerTest` is a stub, so the conformance suite runs in the reactor only at level 1.0 through `levels.py check`: no 1.1 or 1.2 case. (b) `protocol_check.py` (three hosts) is not run. (c) The Python SDK's 7 test files are not run: **3 are red today**. (d) `<rachana-view>`, `screen.js`, the static host and Studio's page are tested only by Playwright scripts nobody calls. (e) JVM = Node = Python comparison is by hand. | (a) `rachana-conformance/src/test/.../RunnerTest.java:12` (`assertEquals("results", Runner.RESULTS_DIR)`); (c) `pytest python/tests`: `3 failed, 90 passed, 59 skipped`: `test_conformance.py` fails with `unknown kind 'fire'` (`python/src/drishti_rachana/conformance/cases.py:12`: the Python copy of the case reader was not given the new kinds); G1's re-check also found a red Python test; (d) `js/packages/rachana/runners/{view,state,form}_browser.py`, `rachana-cli/test/paint_check.py`, `rachana-studio/test/studio_check.py`; `js/packages/rachana/test` holds only the two `data_client` tests. Add to the reactor: conformance at every built level on JVM and Node with `cmp`, the protocol cases, `pytest python/tests`, and one browser lane. |
| F4 | must-fix-before-1.0 | **49 frozen cases are answered by no runtime at any level**: kinds `check` (26, checked mode, SPEC 4.14) and `grammar` (23). At least one is already wrong: `grammar/reserved#pipe-refused` expects `$.a \|> f($.b)` refused with no `until`, while 1.1 accepts the pipe. | `results/jvm-1.2.jsonl`: unsupported `check` 26, `grammar` 23; `conformance/1/grammar/reserved.yaml:10`; both files are in `FROZEN.sha384`. Build the two adapters (checked mode exists: `CheckedModeTest`, 40 tests) or withdraw the files before the freeze. |
| F5 | must-fix-before-1.0 | Two cases fail at level 1.2 on every runtime: the expected schema lacks `arrow` in `can`, added by `e960cfd`. 33 suite files are still `verified: false` (all of `live/*`, `protocol/*` and `patch/*`, most of `plan/*`). The pass rule of C1 is 0 unverified failing before `v1.0.0`. | `conformance/1/live/declaration.yaml:9`; `--language 1.2`: `fail 2`. |
| F6 | must-fix-before-1.0 | **A second evaluator, untested.** The static-files host evaluates the Data Protocol filter tree in hand-written JavaScript, with other rules than Core's: numbers as doubles, numeric text coerced, `==` against empty false, UTF-16 ordering, `contains` case-blind. Every `rachana build` site and Studio use it; no protocol case runs against it. Against O2 (no second evaluator) and one way per job. | `js/packages/rachana/src/static_files_host.js:50-66` (`evalNode`), `:70` (`sortKey`); no test imports `StaticHost`. Run the filter through the bundle (Core's `RowQuery` rules), or run `conformance/1/protocol` against it with a known-differences list. |
| F7 | must-fix-before-1.0 | SPEC 6.1 says a problem **always** carries `path`, a full `range` (end, offsets) and `doc`. The runtime writes `code`, `severity`, `message` and `range.start` only; the cases were changed to match the runtime. `SutraProblem` has no severity and no path, and is a record about to be frozen. | `spec/1/SPEC.md:928-958`; `problem/examples/problem.yaml` (the `-l11` case: "the paths of SPEC 6.1 are not reported by this runtime"); `SutraProblem.java:16`. Decision O4. |
| F8 | must-fix-before-1.0 | Security, server: no `Host` check. In `auth: none` (loopback only) a page on another site can read every table through DNS rebinding: `GET` checks nothing and a `POST` passes `sameOrigin` because `Origin` equals the attacker's `Host`. Studio's Node server already has the check. | `RachanaServer.java:357` (`NONE` is the anonymous caller), `:480-498`; compare `rachana-studio/server/studio_server.js:120`. Answer only the bind address, `localhost` and `server.origins` hosts. Smaller: `/static/` resolves without `toRealPath` (a symbolic link inside `static/` can leave it); `access.default` is `signed-in`, so "fail-closed" means "closed to the unsigned": say so. |
| F9 | must-fix-before-1.0 | `levels.py` counts a recorded `unsupported` case that has been **deleted** as "newly answered": a deletion passes unlisted. Two today: `el/messages-level-1-1#expr-error-double-star-is-not-rachana-l11` and `#math-bad-quantity-2-l11`. | `tools/regime/levels.py:117` (`now.get(c) != x`, with `c` absent from `now`). An absent case must be a listed withdrawal. |
| F10 | must-fix-before-1.0 | Security, painter: `requires` is **fail-open**. An action button that names a permission is enabled when the host gives no `allowed` hook, and for any answer but `false`. `<rachana-view>` passes `undefined` when the page has no hook. | `elements/drishti-views/src/drishti_views-paint.js:135`; `js/packages/rachana/src/rachana_view.js:98`. A button with `requires` is disabled unless the host answers `true`. The host still decides in `run`; neither Rachana nor Elements executes a host action (`drishti_views-state.js:194-204` hands `notify` and `host` back). |
| F11 | must-fix-before-1.0 | CG5 does not enforce what it says after the tag: `-Drachana.api.surface.write=true` rewrites the baseline and the test passes, removals included; only review sees it. And it covers 2 of 7 modules. | `ApiSurfaceTest.java:70`; `MODULES = rachana-api, rachana-core`. At the tag: a released baseline (`api-surface-1.0.txt`) that the write flag cannot change; add `rachana-data`, `rachana-live` (`live.spi`), `rachana-tools`, `rachana-server`. |
| F12 | must-fix-before-1.0 | One way per job. (a) The default language level is written in nine places and is stale: `1.1` in the Node CLI, `<rachana-view>`, `screen.js`, the Python CLI, engine and adapters ("the newest built"); `1.2` in Studio and `LibCheck`; `1.0` in Java. (b) Two programs named `rachana` with different commands (Node: new, lint, build, preview, studio, lib, serve; Python: lint, conformance). (c) The Elements pack folder `3.2.0-dev+d1db672` is written in eight files; a stale pack `3.2.0-dev+475232e` (4.2 MB) is still vendored and nothing reads it. | `rachana-cli/src/lint.js:11`, `rachana_view.js:79`, `screen.js:40`, `python/.../engine.py:25`, `cli.py:27`, `Rachana.java:159`; `python/pyproject.toml` (`[project.scripts]`); `js/vendor/drishti-elements/`. |
| F13 | must-fix-before-1.0 | Release hygiene. Rachana vendors an **untagged development build** of Elements (`rachana.elements.pack` = `3.2.0-dev+d1db672`) while Core's data files come from Elements **2.4.0** (`java/pom.xml:38`); Elements' manifest still says `3.1.0`. `Facade.VERSION` is a hand-written `"0.11.0"`. No budget guard on the Rachana bundles (39% growth since G1 went unremarked). | `rachana-server/pom.xml:22`; `elements/drishti_elements.json`; `rachana-js/.../Facade.java:24`; `tools/js/build_js.py` prints sizes and checks none. Tag Elements 3.2.0 first, vendor that, one pin; restate the budgets at measured + 5% and fail above them. |
| F14 | later | Docs (not judged, owner cut): `DRISHTI_MIGRATION_TODO.md:296` (L-12 superseded); the placeholders `rachana-electron` and `rachana-mcp` should say "parked" or leave the tree until built. |
| F15 | later | `tests/support/fire_cases.json` in Elements is generated from Rachana's `fire` cases by hand and has no pin: the painter can drift from the cases unseen. Pin it like the schema (`drishti_viewmodel.pin.json`). |
| F16 | later | Tests leak temporary folders: 32 `rachana-studio-*`, `rachana-cli-*` and `rachana-compat-*` folders in `/tmp` from today's lanes. The Java tests use `Files.createTempDirectory` with a manual delete; use `@TempDir`, and remove in a `finally` in the Node tests. |
| F17 | later | The server speaks HTTP only; in `auth: users` on a non-loopback address a password travels in clear unless a TLS proxy is in front. Refuse or warn at start when `cookie-secure` is off on a non-loopback bind. Sign-in throttling is per remote address, so one address behind a proxy locks everyone. |
| F18 | later | The known Elements flake (`test_the_libraries_unit_tests_pass_in_node` under `-n 8`) did not show in my run; it stays open until fixed or run alone. |

**Incident, not a finding:** my scratch folder was emptied once from outside while a build ran (the session's scratch is shared
by the lanes). I read every delete in the tests and tools: all act on `Files.createTempDirectory` or `mkdtemp` folders, none
on a path above them. I cloned again and repeated the run at `2391142`. Lanes must not clean the shared scratch.

## 4. The API freeze list

Three consumers were read: Drishti (level 1.0, a `drishti` profile, binder hooks, the Data Protocol host and live tables: the
G4 rehearsal's needs), `rachana-server` with Studio, and the libraries.

**Freeze at `v1.0.0` (stable):**

| Surface | Why it is ready |
|---|---|
| `com.ash.rachana.api.Node` | small, 22 members, used by every host |
| `Rachana` (`core`, `with`, `language`, `libraries`, `parser`, `compiler`, `functions`, `formats`, `profiles`), `RachanaOptions` builders (`with…`), `RachanaCode`, `RachanaException`, `SutraException` | the entry point; exercised by the server, the CLI, the corpus and the libraries |
| `el`: `ElCompiler`, `Expr`, `Template`, `EvalContext`, `ElFunction`, `FunctionTable`, `ElLimits`, `SafeRegex.Limits` | 2,395 cases stand on it |
| `format.Formats` | used by every runtime |
| `lib`: `LibrarySet`, `LibraryResolver`, `Library`, `Library.Trust` | LG1 to LG8 and two libraries use it |
| The wire contracts: ViewModel schemas 1.0, 1.1, 1.2; the Data Protocol documents (`spec/1/data-protocol/*.json`); the case format | these, not the Java records, are what Elements, Python and JavaScript consume |

**Fix first, then freeze:**

| Surface | What must change |
|---|---|
| `SutraProblem` | add severity and path, or amend SPEC 6.1 (F7); do not freeze the three-argument record |
| The default language level | one rule in every entry point (F12). Recommended: no default outside Java's documented `1.0`; the JavaScript and Python entry points require it or take the bundle's newest |
| `range` at 1.1 (EL1-1) | two meanings chosen by the first argument's type are frozen for ever at the tag: rename the aggregate before it (decision O5) |
| CG5 itself | a released baseline; five more modules (F11) |
| `rachana-data` and `rachana-live.spi` (`TableProvider`, `ProtocolHost`, `Authoriser`, `ReaderView`) | Drishti implements these at G4 and they are in no baseline; `ReaderView` is a seven-component record |

**Mark EXPERIMENTAL at `v1.0.0` (may change in a minor, said in the README and the baseline header):**

| Surface | Why |
|---|---|
| `view`, `bind`, `wire`, `control`, `agg`, the `parse` additions (already so by O6) | 247 public records; `ViewModel.PanelView` alone has **nine** public constructors of positional strings, one per field added. Hosts should read the ViewModel as JSON (`ViewJson`), which is the frozen contract |
| `model.*` (49 classes: `Sutra`, `Panel`, `Form`, `Effects`, `PanelKind`…) | grows with every language level; `PanelKind` is a closed enum that already holds a host's kinds (`LINKS`, `PROVENANCE`), so a profile cannot add a kind of its own |
| `profile.*`, above all `profile.Entities` | by its own comment a temporary home for the host's additions "until the host's profile module takes them over" (`Entities.java:25`); Drishti's G4 rehearsal should prove the profile API before it is frozen |
| `design.ops`, `SutraLayoutEditor`, `SutraPivotEditor`, `SutraStore`, `SutraText`, `SutraWiring`, `SutraWriter` | editor tooling used only by Studio and the corpus writer |
| `table.LiveTableSpec`, `parse.TypedFragment`, `el.check.*` | new in G2 (40, 28 and 12+ members), one consumer each |
| The JavaScript facade: `evaluate`, `evaluateError`, `format`, `check`, `view`, `library`, `patch`, `fire`, `plan`, `edit`, `live`, `hello`, `answer` | they are the conformance adapter: the request is a case's fields (`since` is the level, `given` the inputs: `screen.js:40`), and `init` sets one global instance (`Facade.java:49`), so two views with different formats or profiles on one page share it. `library` is exported by the runtime bundle and not by the tools bundle |
| `screen(op, request)` | the one function `<rachana-view>` needs; a global table of open screens (`Screens.java:35`); freeze after it takes an instance, not before |
| `<rachana-view>` attributes and events, `DataClient`, `StaticHost` | built in G2, no browser test in the build (F3), a fail-open hook (F10) |
| The Python SDK (`drishti_rachana.*`) | version `0.11.0.dev0`, tests not in the build, three red |
| `rachana-server` configuration and `rachana` command lines | first release; freeze the config keys one release later |

## 5. Owner decisions needed

| # | Decision | Recommendation |
|---|---|---|
| O1 | When is Rachana tagged `v1.0.0`? | **After** F2 to F13, not at the close of G2. Close G2 now on `2391142`; tag a `v0.12.0` if a tag is wanted. The tag freezes level 1.0, 1.1, 1.2 and the API for ever |
| O2 | F13: improve the recorder, or accept the G4 evidence as the binder's proof? | **Improve the recorder** (recording router and table provider, about 2 to 3 builder-days). Otherwise hold `v1.0.0` until the G4 evidence is green, since Drishti runs at level 1.0 |
| O3 | Put conformance at every level, the protocol cases, the Python tests and a browser lane into `./mvnw verify`? | **Yes.** It adds about 3 minutes. Three red tests and one byte difference went unseen without it |
| O4 | SPEC 6.1: build `path`, `range.end` and `doc` on problems, or cut them from the 1.0 SPEC? | **Build `path` and severity** (tools need them, and the record freezes); cut `doc` and `fix` to "from a later minor" |
| O5 | EL1-1: `range` with two meanings at 1.1 | **Rename the aggregate now** (`spread`), before the level is frozen; a rename after the tag needs a migration rule and a level switch |
| O6 | EL1-2: `-2 ^ 2` is `-4` (Python), Excel gives `4` | **Refuse `-x ^ y` without parentheses, with a fix**: a silent sign difference is the wrong default for a risk product; `**` keeps Python's rule |
| O7 | The `check` and `grammar` case kinds (49 cases nobody runs) | **Build the two adapters** before the freeze; withdraw only what is wrong |
| O8 | The painter's `requires` with no host hook: enabled or disabled? | **Disabled** (fail-closed); a page that wants every button passes `allowed: () => true` |
| O9 | The API marking of section 4 | **Accept**: freeze the small entry surface and the wire contracts, mark the records EXPERIMENTAL, baseline all seven modules |

## 6. What was fixed during the review

| Finding | Commit | Verified |
|---|---|---|
| F1 | `ae9f3de` (in `2391142`), by the builders | reactor green (1,081 Java tests, 33 Node, 0 skipped); `--language 1.2` twice: byte-identical; JVM = Node at 1.2; `CustomerCorpusTest` 8 of 8 |

Nothing else was changed. The must-fix items are for the lanes before the `v1.0.0` tag; the gate needs a short re-check then:
the reactor with the added lanes, C1 at every level with 0 failing, the corpus with the new recorder, and the API baseline.

## 9. Re-check

Run on 2026-11-21, 12:37 to 13:00, on JDK 21, in fresh clones of my own. Judged from the code and my own runs. No product code
was changed.

| Repository | Branch | Commit |
|---|---|---|
| `drishti-rachana` | `g2/integration` | `774538f` (full run and reading), then `9db1aa2` (full run again: it landed during the re-check) |
| `drishti-elements` | `g2/integration` | `b466b87` (suites); `6972989` and `b466b87` (the two packs Rachana vendored) |

### 9.1 Verdict

**NOT YET. Close, and nothing found is deep.** Every blocker and almost every must-fix of section 3 is truly closed in the code,
not papered over, and level 1.0 is byte for byte what it was. Five small items stand between this and golden; the list of 9.4 is
exact. The `v1.0.0` tag has its own, longer list (9.5).

### 9.2 What I ran

| Check | Result |
|---|---|
| `./mvnw clean verify`, fresh clone, `774538f` | green in 4 min 12 s: 1,095 Java tests, 39 Node tests, 0 skipped; four lanes green; temp gate green |
| The same at `9db1aa2` | green in 3 min 31 s: 1,098 Java tests, 39 Node tests, 0 skipped; four lanes green; temp gate green |
| Conformance lane | 2,593 cases (`9db1aa2`: 2,599). Level 1.0 pass 2,007; 1.1 pass 2,277; 1.2 pass 2,468 (2,474). 0 failing, verified or not. JVM = Node byte for byte at each level |
| By hand, beyond the lane | Python on V8 = JVM byte for byte at 1.0, 1.1 and 1.2; a second JVM run at 1.2 identical |
| What is still `unsupported` at 1.2 | 125: the 113 protocol cases (the protocol lane runs them), 7 grammar and 5 problem cases with `until` (earlier levels answer them). `check` and `grammar` are answered by all three runtimes |
| Protocol lane | reference 54 pass, Python host 54 pass (the 59 others are live-table cases neither serves), live server 113 of 113; 0 fail |
| Python SDK lane | 93 passed, 0 skipped. The 59 skips of the first review were live-table cases; they are now left out by name and run by the live server |
| Chromium lane | `<rachana-view>`, forms, state, a built site, Studio: green |
| `-DskipTests` | honoured: 29 s, no test, no Node test, no lane |
| `tools/js/build_js.py` | green: runtime 2,088,209 bytes (Brotli 510,602), tools 2,215,331 (Brotli 535,820); runtime = tools on every case at each level |
| `tools/libraries/build.py --gallery` | green: 145 examples, LG1 to LG5 and LG8 pass, gallery 31 + 57 pages |
| `levels.py check 1.0` | identical at both commits: 1,999 identical, 52 newly answered, 61 listed moved or withdrawn |
| Elements: build, API, licence checks; Node tests | all 0; 379 pass, 0 skipped |
| Elements `pytest -n 8` (179 tests, Chromium) | **1 failed, 178 passed**, at machine load 28 (9.3, F18) |
| Temp folders | no new `rachana-*` folder in `/tmp` after either run; both clones clean in `git status` after every build |

**Level 1.0, 1.1 and 1.2 against the commit I reviewed (`2391142`, rebuilt and run again):**

- **1.0:** 2,514 of 2,563 common cases answer the same bytes. The other 49 are the `check` and `grammar` cases, unanswered then and
  passing now. Nothing else moved.
- **1.1:** as 1.0, plus 13 problem cases that now carry `path` and the full `range` (decision O4). One start column moved
  (a duplicate id now points at the `id`, column 11, not the panel, column 5).
- **1.2:** as 1.1, plus the two `arrow` cases fixed, two messages that now list `pivot` and `surface`, one more start column.

All of the 1.1 and 1.2 changes are intended, and allowed because those levels are not recorded yet. They show why
`compat/levels/1.1` and `1.2` must be written at the tag.

**A document without a level.** There are two defaults, both deliberate. `Rachana.core()` (the Java library) starts at 1.0, as
SPEC 7.2 rule 6 says, so a host that names nothing is never moved. The tools (Node command, `<rachana-view>`, server, Studio,
Python command) read `Grammar.DEFAULT_LEVEL`, the newest built level, now 1.2. It is written once; `DefaultLevelGuardTest`
refuses a level literal in any entry point. Two gaps: SPEC 7.2 does not mention the tools' default, and `rachana new` writes no
level into a new project, so a tool upgrade re-levels that project silently.

### 9.3 Status of each finding

| # | Status | Evidence |
|---|---|---|
| F1 | **closed**; guard not widened | Two JVM runs at 1.2 identical; three runtimes identical. `rachana-data` and `rachana-live` still build multi-key objects from `Map.of` (`StoreOverlay.java:190`, `LiveReferenceServer.java:237, 276`, `RowQuery.java:278`, `ProtocolHost.java:209`) and no guard looks there. Protocol answers are not byte-compared, so this is later |
| F2 | **closed** | The recorder runs the i7a reference's own binder over a recording router and the host's pivot engine. I counted the views myself: 478 of 478 bind, 0 errors, 4,470 data panels, **7 empty (0.16%)**, every kind filled, 0 views with every data panel empty. `known-differences.txt` lists no bind. Notes: 3 synthetic rows per table, so row limits and "more" are thinly covered; the tool assembles title and keys itself |
| F3 | **closed** | Section 9.2. `verify_lanes.py` fails on any failing case, any skip, any byte difference. `LevelReplayTest` asserts its prerequisites, where it used to skip |
| F4 | **closed** | 49 of 49 answered on JVM, Node and Python. The seven refusals that 1.1 accepts carry `until: "1.0"` and a 1.1 twin each |
| F5 | **closed for the two cases; verification open** | 0 failing at any level. 501 cases in 35 files are still `verified: false` (9.6) |
| F6 | **closed** | `static_files_host.js` holds no evaluator: it calls the bundle's `query` (Core's `RowQuery`). `static_host.test.mjs` is in the Node tests and asserts that |
| F7 | **closed but for 22 problems** | SPEC 6.1 now defers `doc`, `fix` and `expected`. `SutraProblem` has severity, path and range. Of 148 problems answered at 1.1 and 1.2, 126 carry `path` and the full range. **22 do not**: problems inside a fragment file (`RCH-2048` 11, `RCH-2121` 8, `RCH-2117` 2, `RCH-2023` 1). SPEC 6.1 says such a problem carries that file's name in `file`; they carry none, so line 9 of a fragment reads as line 9 of a 7-line Sutra (`problem/fragments-calls#fragment-reads-only-its-parameters`) |
| F8 | **closed** | Read: `hostAllowed` runs first, in every auth mode (`RachanaServer.java:155, 494`): bind address, `localhost`, `127.0.0.1`, `[::1]` with the server's port, or a `server.origins` authority. Test: `aHostThatIsNotThisServersIsRefusedInEveryAuthMode` (rebound name, wrong port, no port, `auth: none`). Static files: `realInside` compares real paths (`:342`); **it has no test** (no symbolic link is made in any test) |
| F9 | **closed** | `levels.py` fails on a deleted recorded case that is not listed; test added; the two deletions are listed as withdrawn |
| F10 | **closed** | `drishti_views-paint.js:137`: enabled only when the host's `allowed` is a function and answers exactly `true`; a throw is `false`. Browser test covers none, `false`, `undefined`, `'yes'`, throws, `true`. The fix is in the vendored pack |
| F11 | **closed** | Seven modules; `[stable]` 280 lines, `[experimental]` 4,444; a write refuses an unlogged removal; a released baseline is never rewritten. Since `2391142`: 0 lines removed, 526 added, `api-removals.txt` empty and rightly so. `9db1aa2` adds two experimental lines |
| F12 | **(a) closed, (c) closed, (b) open** | (a) above. (c) one vendored pack, the stale one is gone, `ElementsPinGuardTest` refuses a second version. (b) **two programs are still named `rachana`**: `python/pyproject.toml:27` and `rachana-cli/package.json:10`, with different commands |
| F13 | **pin closed; three parts open** | One pin: `drishti.elements.version` feeds Core's tables and the pack folder. I rebuilt the pack from Elements at `6972989` and at `b466b87`: **byte for byte the vendored one**, both times. Open: no size guard on the Rachana bundles (Brotli 501 KB at the review, 511 KB now, nothing checks); the pack named `3.2.0-dev` says `"elements": "3.1.0"` inside; the pack is an untagged build |
| F14 | open (later, docs) | `DRISHTI_MIGRATION_TODO.md` 9.2 still names the private companion repository |
| F15 | **closed, and it caught a real drift** | Fixture and schema are pinned by Rachana commit and hash and read with `git show`, never from the files of the branch checked out. Confirmed: my sibling clone had `774538f` out while its remote ref was `9db1aa2`, and the tests followed the ref. **At Rachana `9db1aa2` the fixture is stale**: `fire/fragment-marks` and `fire/pivot-cells` were added, and `test_the_fire_cases_fixture_is_the_cases_at_its_commit_and_they_have_not_moved_since` fails at Elements `b466b87` |
| F16 | **closed** | Every test's temp folder is `java/target/tmp`; the build fails if anything is left |
| F17 | **closed** | `auth: users` on a non-loopback bind is refused unless `server.behind-tls-proxy: true`; test present. `cookie-secure` is advised in the message, not required. Sign-in throttling per address is unchanged |
| F18 | **not closed as a class** | The Node budgets now measure CPU time over synchronous code, and the validator takes the least of 15 runs: sound, and no wall clock is left in `tests/elements`. But in my one `-n 8` run `test_ten_thousand_a_second_into_a_grid_once_a_frame` failed (13,600 ticks sent in 3 s, 15,000 wanted); alone it passed 3 of 3. It is a wall-clock rate in a browser. The lead saw the Node suite fail too at load 40 |

**Is CPU time enough for F18?** No. It removes the time a test waits for a core. It does not remove three things: a loaded
machine runs each instruction slower (shared cores, cache, clock speed), so CPU time itself rises; the suite has tests on real
timers (`setTimeout` in seven files, the browser rate test); and `node --test` starts a process per file inside one of eight
pytest workers. Budget tests must be kept away from parallel load: mark them, run everything else with `-n 8`, then run the
marked ones alone and serially. A budget is a release measurement, not a unit test.

**`FROZEN.sha384` since the review** (still `pre-release`): 10 files changed, 15 added. Each is justified. `el/check` and
`el/examples/check` gain offsets (stronger); one loses `expected`, which SPEC 6.1 now defers. `grammar/reserved` gains `until`
and seven twins. `live/declaration` gains `arrow`. `problem/examples/problem` regains `path`. Two message cases list `pivot` and
`surface`. All 124 files are listed and every hash matches. At `9db1aa2` three new files are not listed
(`fire/fragment-marks`, `fire/pivot-cells`, `problem/fragment-marks`). `everyCaseFileOfTheSuiteIsListedAtRelease` still asserts
nothing.

**New since the review, not regressions:** `compat/promotions.yaml` promotes four formats at 1.2, and `ReservedNamesTest`
compares them with Drishti's definitions. The aggregate `range` is renamed `span` (O5). `-x ^ y` does not compile (O6).

### 9.4 What remains before GOLDEN

About half a builder-day in all.

1. **Rachana, F7:** the 22 fragment-file problems carry `file`.
2. **Elements, F15:** regenerate `tests/support/fire_cases.json` against Rachana `9db1aa2`. From here on the two repositories
   move together or Elements is red.
3. **Elements, F18:** separate the budget tests from the parallel run. Also pin Playwright (`playwright>=1.45` today): with a
   version whose Chromium is not installed, 105 browser tests **skip and the run is green**. A gate run must fail on a skip, as
   Rachana's does.
4. **Rachana, F12 (b) and F13:** one program named `rachana`; a size guard on the two bundles at measured + 5%; the pack's
   manifest says the version its folder says.
5. **Freeze the base.** `g2/integration` still takes G3 merges: three landed during this re-check. Name one commit as the G2
   base (a branch that no lane writes to), run `freeze.py` on it so every case file is listed, and let G3 start from there.

### 9.5 What remains before the `v1.0.0` tag (not needed for golden)

- The verification of 9.6.
- Tag Elements 3.2.0 and vendor that; real versions in `pom.xml`, `Facade.VERSION`, the packages and `pyproject.toml`.
- Record `compat/levels/1.1` and `1.2`; freeze contracts 1.1 and 1.2; `FROZEN.sha384` to `released`, with a listing test that asserts.
- SPEC wording: 4.7 and 4.13 say text orders by code point and section 9 item 4 says "settled", but level 1.0 orders by UTF-16
  code unit (`el/determinism#d-u02-a`); say so, as 4.16 does for numbers. 7.2 names the tools' default level. FORMAT 3 says a
  later runtime "does not run" an `until` case and is silent on the fact that every case is answered at the level of its own
  `since`. `LIVE_TABLES.md:197` says `RCH-2058` where SPEC and the case say `RCH-2060`.
- `rachana new` writes the project's level.
- A test for the static symbolic-link check; the `Map.of` guard over `rachana-data` and `rachana-live`; F14.

### 9.6 The verification sample

**501 of 2,599 cases are `verified: false`**: `expr` 273, `protocol` 113, `grammar` 30, `check` 26, `plan` 14, `sutra` 12,
`live` 9, `problem` 8, `format` 7, `patch` 6, `expr-error` 3. I drew 40 at random (seed 20261121), in proportion to kind with at
least two of each kind, and read each expected answer against the section its `source` names.

**Result: 40 of 40 expected answers are correct. 0 wrong.**

| Kind | Checked | Against |
|---|---|---|
| `expr`, `expr-error` (15) | 15 correct | SPEC 4.5 to 4.8, 4.10 rule 7, 4.12, 4.13, 4.16, 5.4, 9 item 8 |
| `protocol` (9) | 9 correct | PROTOCOL 5.3, 6.1, 10.1, 12, 16 |
| `check`, `grammar` (4) | 4 correct | SPEC 4.11, 4.14, 4.15 |
| `format`, `sutra`, `problem` (6) | 6 correct | SPEC 2.4, 2.6, 4.9, 6.2, 7.2 |
| `live`, `patch`, `plan` (6) | 6 correct | SPEC 6.2, PROTOCOL 7 and 8.1, VIEWMODEL frames, FORMAT 5.9 and 5.10 |

Three cautions.

- Four of the 15 expression cases are right **only as level 1.0 behaviour** (doubles for `2 / 3` and `-5.5 % 2`, `RCH-4010` for a
  refused pattern, UTF-16 order). The SPEC states the first three; the fourth is the wording gap of 9.5.
- A message's exact words are not specified anywhere (codes are). Where a case pins words, I checked the code and the sense.
- 0 wrong in 40 means the true error rate is below about 7% with 95% confidence. That is up to about 35 wrong cases in 501. The
  sample supports flipping; it does not replace evidence per case.

**Recommended procedure for the other 461.** Flip by evidence, in two classes, before the freeze (a flip changes the file's
bytes).

1. **Class A, about 280 cases: the reference recorded the answer** (`el/determinism` and the other level 1.0 `expr`,
   `expr-error`, `format` cases). Evidence is mechanical: the Drishti reference at `v2.9.11.i_7a`, through `RefBridge`, gives
   the expected bytes, and Rachana's three runtimes give them too. A script writes the list; a case in `known-differences.txt`
   is left out and read by a person (about ten). No reading is needed for the rest.
2. **Class B, about 220 cases: written by reading, no reference exists** (`protocol`, `live`, `patch`, `plan`, `check`,
   `grammar`, the 1.1 and 1.2 cases). Evidence: all runtimes pass with the same bytes (the build proves it), and **a reviewer who
   did not write the case** reads each one against the section its `source` names. One independent review agent, about a day,
   and not the lane that built the feature.
3. **Record** per file in `conformance/1/VERIFIED.md`: file, cases, class, the section read or the reference tag, reviewer,
   date, the Rachana commit. One commit per file, `FROZEN.sha384` rewritten with it.
4. **The owner** reads the Class B log, spot-checks one case in ten (22 cases), and approves. FORMAT 3 says a person flips it.
5. A case that cannot be shown right is fixed or withdrawn, never flipped. After the flip the rule of C1 is 0 failing and 0
   unverified at the tag.

## 10. Final confirmation

Run on 2026-11-21, 15:01 to 15:20, on JDK 21, in fresh clones of my own (deleted afterwards). Judged from the code and my own
runs. No product code was changed. G3 work is in the merged tree; it is judged here only for whether it blocks the G2 freeze.

| Repository | Branch | Commit run |
|---|---|---|
| `drishti-rachana` | `g2/integration` | `3cf6fc49` (the head; still the head when I finished) |
| `drishti-elements` | `g2/integration` | `4b7f406` (the head; still the head when I finished) |

### 10.1 Verdict

**GOLDEN.** Every item of 9.4 is closed in the code, every run is green with nothing skipped, and no case is unverified.
Nothing in the merged G3 work blocks the freeze. One defect was confirmed in the upgrade tool (10.5); it belongs to G3 and must
be fixed before G4 runs the upgrade, not before the G2 freeze.

### 10.2 What I ran

| Check | Result |
|---|---|
| `./mvnw clean verify` (in `java/`), fresh clone | green in 4 min 47 s: 1,136 Java tests, 44 Node tests, 0 skipped; four lanes green; temp gate green; clone clean afterwards |
| Conformance lane | 2,685 cases in 133 files. Pass at 1.0: 2,028; at 1.1: 2,308; at 1.2: 2,522. JVM = Node at each level. 0 failing |
| Protocol lane | reference 57 pass, Python host 57 pass (the 90 others are live-table cases neither serves), live server 147 of 147; 0 fail |
| Python SDK lane | 98 passed, 0 skipped |
| Chromium lane | view, forms, state, live edit, paint check, Studio: green (Playwright 1.64.0 pinned, browser installed by the lane) |
| `tools/js/build_js.py` | green: runtime Brotli 514,869 (guard 537,812), tools 539,799 (guard 563,898); runtime = tools on every case at each level |
| `tools/libraries/build.py --gallery` | green: LG1 to LG5 and LG8 pass, both libraries packed and verified, gallery 33 + 57 pages |
| Elements `tools/run_tests.py` | green, nothing skipped: Node 390 + 35 budget tests alone; pytest 188 with `-n 8`, then 4 budget tests alone |
| Elements pack | rebuilt from Elements `a40ca7e` with the documented command: byte for byte the pack Rachana vendors |

One note on procedure: the Elements run started at machine load 33 (my own gallery build had just ended; I should have waited
for 16). It was green all the same, which is the F18 failure case of 9.3 passing under the load that broke it.

### 10.3 The items of 9.4 and 9.5

| Item | Status | Evidence |
|---|---|---|
| 9.4 (1) F7, `file` on fragment problems | **closed** | 24 expected problems inside a fragment file carry `file` and `path` (`problem/fragments-calls#fragment-reads-only-its-parameters` names `peek.v1.fragment.yaml`, line 9); the lane passes them on every runtime |
| 9.4 (2) F15, fire fixture | **closed** | the fixture names its Rachana commit (`f7f4cd8`) and is compared with that commit only; `conformance/1/fire` is unchanged from there to `3cf6fc49` |
| 9.4 (3) F18 and the Playwright pin | **closed** | budget tests run alone after the parallel pass (Node and pytest); `playwright==1.64.0`; a skipped test fails `run_tests.py` |
| 9.4 (4) F12 (b), F13 | **closed** | one program named `rachana` (the Node command; `pyproject.toml` has no console script); `bundle-size-guard.json` at measured + 5%, read by the build and the lane; the pack's manifest says `3.2.0-dev+a40ca7e`, as its folder does |
| 9.4 (5) the base | **closed by 10.4** | `FROZEN.sha384` lists all 133 case files (state `pre-release`) |
| 9.5 verification | **closed** | 0 of 2,685 cases unverified (counted from the files); `VERIFIED.md` logs each; sample below |
| 9.5 SPEC 4.7, 4.13 (UTF-16 at 1.0), 7.2 (the tools' default), `rachana new` | **closed** | SPEC lines 544, 660 and the paragraph "The level of a Sutra and of a host" |
| 9.5 the rest | **open, for the tag** | list in 10.6 |

**The sample of the newer cases.** Of the 40 cases the builders verified last (12 `el/numbers-exact`, 16 code-fix and decision
cases, 12 `el/aggregates-host-values`), I drew 30 at random (seed 20261121) and read each against the section it names, with the
arithmetic recomputed: SPEC 4.14 rules 1, 2, 6; 4.16 rules 3, 4, 7, 8; 5.1; 2.4; PROTOCOL 3.1, 6.3, 16. **30 of 30 correct.**
Checked by hand among them: `5 / 3` to 34 digits half to even; the sums `3.35`, `4.85` and the total average `1.8`; a sum of
nothing `0` and `"0"`; the two error offsets (30 to 33, 16 to 19). With the 40 of 9.6, that is 70 of 70.

### 10.4 The commits to freeze as the G2 base

| Repository | Commit | Why this one |
|---|---|---|
| `drishti-rachana` | **`3cf6fc49`** | the commit every run above was made on. G2 was complete at `417f6af9`; the only change since is the libraries' `rachana:` key (92 files, all below `libraries/`) |
| `drishti-elements` | **`4b7f406`** | the commit the suite ran on. The libraries themselves are unchanged since `a40ca7e`, the pack Rachana vendors |

Put a branch or tag that no lane writes to on each (`g2/base`); G3 goes on from `g2/integration`.

### 10.5 The upgrade tool and a fragment's `rachana:` key

**It is a defect, and G4 needs it fixed before it runs the upgrade. It does not block the G2 freeze** (the tool is G3 work).

What the code does (`Upgrade.java:105`): a Sutra's level is read from its key; a fragment's level is the `--from` option
(default 1.0), its own key is never read and never written. I ran it on a Sutra and a fragment that calls `range(list, field)`:

1. Run 1: the fragment's text becomes `span(...)` (level 1.2 text); its key still says `rachana: 1`.
2. Run 2, same command: the fragment is **refused** ("unknown function 'span'" at 1.0), exit 1. The tool is not idempotent for
   a fragment it rewrote.
3. Run 3, after a level-1.1 sequence `sum(range(1, 4))` was written into that fragment: the tool **rewrites it to
   `sum(span(1, 4))` and exits 0**. That changes the meaning of correct 1.2 text, silently.

For Drishti's 42 corpus fragments: all say `rachana: 1`, none needs a rewrite today (6 carry review or info flags), so the first
G4 run works, and `UpgradeCorpusTest` proves compile and bind identical. But afterwards the 478 Sutras say 1.2 and the 42
fragments say 1, the 90 library fragments say 1.2 (set by hand), nothing records which fragments were upgraded, every later run
repeats the flags, and case 3 above waits for the first fragment that uses `range`. The tree also says two things: the rule
text says "a fragment declares none", and the libraries commit says "libraries declare level 1.2"; the compiler accepts the key
in a fragment and ignores it; SPEC 2.1 rule 4 speaks of Sutras only.

Recommended fix (one way, small): a fragment's `rachana:` key is the level its text is written for. SPEC 2.1 rule 4 says so;
the tool reads the level from the key for both kinds, writes the new level into both, and leaves a file already at the target
alone; `--from` goes. The compiler should then refuse a use across levels that would change meaning (a fragment above its
Sutra's level is `RCH-2008`; a fragment below it is expanded and read at the Sutra's level today, with other arithmetic).
Add the three runs above as tests.

### 10.6 Before the Elements 3.2.0 tag and the `v1.0.0` tag

Before **Elements 3.2.0**:

- **Sync the ViewModel schema.** Elements' copy is pinned to Rachana `5d1b116`; Rachana's schema has since gained
  `LiveRef.edit`, which Elements' own grid edit UI reads. The pin test checks only that the copy equals its pinned commit, so it
  is green on a stale copy. Sync to the G2 base (`gen.py --sync --commit 3cf6fc49`).
- **Move the two test pins to the G2 base** and run again: the fire fixture (`f7f4cd8`; cases identical, so only the pin
  moves) and the live host (`7b1f5e4`, older than the code fixes CF-1 to CF-5 in `rachana-live`).
- `elements/drishti_elements.json` says `3.1.0`; the changelog's `Unreleased` becomes 3.2.0. Then Rachana vendors the tagged
  pack (label `3.2.0`).

Before **`v1.0.0`** (unchanged from 9.5, still open): real versions (`0.11.0-SNAPSHOT`, `0.11.0.dev0`); record
`compat/levels/1.1` and `1.2` and release contracts 1.1 and 1.2; `FROZEN.sha384` to `released` and
`everyCaseFileOfTheSuiteIsListedAtRelease` made to assert (it still ends in `assertNotNull`); FORMAT 3's sentence on `until`;
`LIVE_TABLES.md:202` (`RCH-2058` where SPEC says `RCH-2060`); a test with a symbolic link for the static files; the `Map.of`
guard over `rachana-data` and `rachana-live`; F14; the fix of 10.5.
