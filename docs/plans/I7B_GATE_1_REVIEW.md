<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7b gate 1 review: foundations and split

Review of milestone G1 of [I7B_PRODUCTS_PLAN](I7B_PRODUCTS_PLAN.md) against [I7B_GATE_CHECKLIST](I7B_GATE_CHECKLIST.md).
A point-in-time inspection, not a drill. Run on 2026-11-21 on JDK 21, Node 22, Python 3.12, in fresh clones from `origin`
(nothing was read from a builder's working tree, nothing was installed into the local Maven repository).

| Repository | Branch | Commit reviewed |
|---|---|---|
| `drishti-rachana` | `g1/integration` | `7c33edd` |
| `drishti-elements` | `g1/integration` | `e995a85` |
| `drishti` | `main` | `9ce7c3170` (read-only) |
| `drishti` | `wip/i7b-evidence`, `wip/i7b-products` | evidence tooling and this plan |

## Verdict

**PASS WITH FINDINGS. G1 is not closed yet.** The split is real and the three runtimes agree byte for byte, the regime's guards
are green and Drishti is untouched. Three things keep the gate open:

1. **F1 (blocker):** the customer-corpus proof (CG3, "478 Sutras byte-identical") is not run by Rachana's own compiler; the test is skipped.
2. **Datasets and the `grid`, `tree`, `chart` kinds are in progress for G1** (owner decision of 2026-11-21: completed in G1, not
   deferred to G2). ViewModel contract 1.1 in Rachana and Views step 5 in Elements are being built on `g1/integration` after the
   commits reviewed here. The gate needs the short re-check of [section 7](#7-re-check-when-the-datasets-work-lands) when they land.
3. The major findings F2 to F7 are fixed before G2 starts.

**Re-check of 2026-11-21 ([section 8](#8-re-check-2026-11-21)): FAIL on one blocker.** F1 to F7 and F9 to F12 are fixed and the
datasets work passes, but the runtime JavaScript bundle is broken at `52e112f` (R1).

## Owner summary

1. Conformance: JVM, JavaScript (Node) and Python on V8 give **the same 2,123 result lines, byte for byte**; 0 verified failing.
2. The JavaScript build is reproducible: my clean build gave the SHA-256s the CHANGELOG records.
3. Against Drishti i7a's compiler (reference bridge, run read-only on the i7a jars): 2,051 lines identical, 67 known differences, 0 unexplained outside the binder.
4. Guards and regime tests green: 861 unit tests, CG1, CG2, CG4, CG5, CG6, licence and line cap, dependency allow-list.
5. **Blocker F1:** the corpus test of 478 Sutras is skipped (`coreReady = false`); Core has never been proven on the corpus by a committed test.
6. Security: the regex budget is per match, not per call (1.7 s for one `replaceAll` on 8 KB); a form field's `pattern` still runs on `java.util.regex`.
7. Elements: 327 Node tests, 149 pytest (browser tests included, none skipped), build, API and licence checks green. 3.0.0 has breaking changes for Drishti's console; no migration list exists for them.
8. Drishti `main` holds only the start-up fix since the baseline work (two commits, `a816aa7c0` and `9ce7c3170`); no i7b branch changes a production file.
9. Docs are behind the code in places: ROADMAP, ARCHITECTURE and OVERVIEW still say WebAssembly for Python and "CG2, CG5 not started"; decision W-1 is not in DECISIONS.
10. Ten owner decisions are listed in section 5; the largest are the bundle size (361 KB against 150 to 200 KB) and the evaluation speed (14 times).

## 1. Checklist

| # | Result | Command and evidence |
|---|---|---|
| C1 | **pass** | `./mvnw clean package -DskipTests`, then `rachana_conformance.py --jvm`: `2123 cases: pass 1937, fail 59, error 0, unsupported 127 (unverified failing 59, verified failing 0)`. The 59 are the unverified 1.0 suites (`el/numbers-exact` 26, `el/reserved` 20, `el/text-code-points` 9, others 4): see F8. The pass rule names `coverage-gaps.txt`, which is a different list: see F12. |
| C2 | **pass** | `tools/js/build_js.py`: runtime 1,387,747 bytes (Brotli 360,929, SHA-256 `d0ced04f…`), tools 1,478,167 bytes (Brotli 380,533, SHA-256 `b012b411…`): **the hashes of the CHANGELOG**. `--node`: same counts as C1. Runtime bundle: 43 `edit` cases unsupported, as designed (editing is in the tools bundle). |
| C3 | **pass** | `python[v8]` in a scratch virtual environment; `pytest python/tests/test_engine.py`: 3 passed; `--runtime python-v8`: same counts as C1. Needs `jsonschema` and `pyyaml` too (the `conformance` extra): F12. |
| C4 | **pass** | `cmp results/jvm.jsonl results/node.jsonl` and `results/python-v8.jsonl`: byte-identical. |
| C5 | **fail** | `./mvnw -o verify`: `CustomerCorpusTest` 6 run, **1 skipped**: `theShippedSutrasCompileToTheRecordedDocuments` ("no runtime: Core cannot compile yet"). The manifest tests pass (1,042 files). F1. |
| C6 | deferred to G4 | The products are not wired into Drishti, so a capture would compare i7a with itself. The committed `testing/evidence/proof-i7a-against-itself.json` reads `"status": "no difference"`, 234 identical. A capture starts a server and loads data: not cheap, not informative before G4. |
| C7 | deferred to G4 | With C6. |
| C8 | **pass** | `build.py --check` 0, `elements_api.py --check` 0, `license_headers.py` 0, `node --test`: 327 pass, 0 skipped; `uv run --with playwright==1.64.0 … pytest -q tests -n 8`: **149 passed**, 0 skipped. |
| C9 | **partly** | `coverage.py`: 207 of 207 Core functions in a case; `reserved_names.py --check`: current. `spec_files.py` and `compare_viewmodel_schema.py` do not run as the checklist writes them (they need the reference's class path and a dump) and compare with a reference that A9 moved away from: F12. |
| S1 | **pass with a note** | `FrozenCasesTest` green (4). `git diff v0.9.0 -- conformance/1`: 2 files added, **2 edited** (`el/determinism.yaml`: 9 expectations for A5 and A9; `sutra/examples/sutra.yaml`: `select` to `dropdown`). `FROZEN.sha384` says `state: pre-release` and does not list the 2 new files. Acceptable before 1.0; see F8. |
| S2 | **pass with a note** | `levels.py check`: `level 1.0: identical (2109 identical, 3 newly answered, 11 cases added since)`; `LevelReplayTest` green. `git log -- compat/levels`: level 1.0 rewritten three times (A5, A9, A15), 59 lines. Section 3. |
| S3 | **pass** | `ContractAppendOnlyTest` green (3). |
| S4 | **pass with a note** | `ApiSurfaceTest` green. Since the first baseline (`349f001`): 1,463 lines added, **5 removed**. Section 2. No tag carries `api-surface.txt`, so nothing released was broken. |
| S5 | **pass** | `ReservedNamesTest` green (4); `promotions: []`, `switches: []`. |
| S6 | **pass with a note** | Drishti `main` = `9ce7c3170`. Since the i7a tag: 56 commits of 2026-11-18 (the owner's CSV loaders) and 2 of 2026-11-21 (`a816aa7c0`, `9ce7c3170`: the start-up fix). `wip/i7b-evidence` adds only `testing/evidence`, `tools/evidence` and `tools/test_evidence.py`; `wip/i7b` and `wip/i7b-products` change docs only. Decision O7. |
| S7 | **pass with a note** | Rachana: `main` `d90176e`, tag `v0.9.0`. Elements: `main` `39ca755`, tags `v2.4.0`, `v3.0.0-rc.1`. All dated 2026-11-19 10:18 to 10:35, before the G1 rule was written (16:29 the same day); nothing since. Decision O7. |
| S8 | **note** | API reference current. Elements 3.0.0 **is** a breaking release (section 2). Decision O6. |
| Q1 | **partly** | A1 to A15 done as SPLIT_PLAN lists; not done: the corpus on Core (F1), per-instance block library (F4), datasets and three kinds (in progress). |
| Q2 | **pass with findings** | Guards green (`NamesNoHostTest`, `DependencyAllowListTest`, `CoreDeterminismGuardTest`). Second ways found: F3 (two regex engines), F4 (static block library). Elements: `LiveClient`, `TransactionSink`, `pipeToGrid` removed in favour of drishti-events: one way. |
| Q3 | **pass** | `LicenceHeaderAndLineCapTest` green; Elements `license_headers.py` 0. Files above 2,500 lines in Elements are built bundles, the generated `docs/API.md` and specification data. |
| Q4 | **fail on two budgets** | P10: runtime 361 KB (budget 150 KB, accepted "about 200 KB"), tools 381 KB (budget 350 KB). T4: median 14 times the hand-written interpreter. Elements and Views budget tests pass (in C8). Other budgets taken from `P1_RESULTS.md`, not measured again. Decisions O1, O2. |
| Q5 | **pass** | The diffs since the last tags name no secret; the new words are the address guard of drishti-events (`SECRET_PARAMS`) and its `auth` callbacks. |
| Q6 | **pass** | Masking cases inside the conformance run (0 verified failing); Elements masking inside C8. |
| Q7 | **pass with findings** | `SafeRegex` is a linear-time simulation with caps; F2 and F3. |
| Q8 | **pass** | No runtime URL outside XML namespaces in `elements/` and `css/`; drishti-views writes no `innerHTML`; markdown links pass `safeHref` (http, https, mailto, relative). |
| Q9 | **pass** | `test_vendor_licences.py` inside C8; `DependencyAllowListTest` green. The patched TeaVM file keeps its Apache-2.0 header and states the change. |
| D1 | **partly** | No broken relative link in either repository (checked 0 and 0). Stale pages: F6. |
| D2 | **pass** | Rachana: `v0.10.0 ready`, `v0.11.0 ready`; Elements: `3.0.0`, `3.1.0`. `liveSource` and `timeoutNanos` removals: F5. |
| D3 | **pass with a note** | Every worktree of both repositories is clean and not ahead. Worktrees and remote branches of merged work are still there: F11. |
| D4 | **partly** | 3 of the 5 removed API members are in `DRISHTI_MIGRATION_TODO`; Elements 3.0.0's breaking changes have no list for Drishti: F5. |
| D5 | **partly** | The conformance tool reads `/home/ashutosh/IdeaProjects/drishti/config/packs/core/core/config/formats.yaml` by default; the reference results of the split proof are not kept: F7. |
| D6 | **partly** | PY1 to PY8, D5, D9, D15 are in DECISIONS. W-1 is not (F6). The plan still lists datasets as waiting (now in progress). |

## 2. API changes, for the owner

**Rachana (`java/api-surface.txt` since its first baseline).** Added: 1,463 lines, mostly whole packages that became public with
the binder and the aggregates: `view` (ViewModel records), `agg`, `bind`, `wire`, `profile`, `control`, `parse`, and `RachanaOptions`.
Removed:

| Removed | Replaced by | In the migration list |
|---|---|---|
| `RachanaSchema.build(Collection, Collection)` | the per-instance schema | yes |
| `Calendars.use(Resolver)` | calendars per instance (`Rachana.with`) | yes |
| `Functions.register(String, List<Provided>)` | a profile's function table | yes |
| `Functions.liveSource(String)` | none named | **no** |
| `SafeRegex.Limits.timeoutNanos()` | `maxSteps` (D5-6) | only `maxSteps` is named |

The added surface is large. Everything in it is frozen by CG5 from the first tag that carries the file. Before `v0.10.0` is tagged
the owner should look at whether all of `view`, `bind`, `wire` and `agg` are meant as public API or should be narrowed (O6).

**Elements 3.0.0 and 3.1.0 against `v2.4.0`.** Breaking, from the CHANGELOG: `LiveClient`, `LIVE_STATES`, `live()`, `parseSse`,
`Backoff`, `Heartbeat`, `FeedMonitor`, `feedBadge`, `TransactionSink` and `pipeToGrid` leave drishti-data (drishti-events is the
one way); `SELECTION_EVENT` and `FILTER_CHANNEL` are removed with no alias (channels are `ui.filter.<channel>`); a filter bar's
`channel` is now a channel name. New: drishti-events and drishti-views. Drishti's console uses the removed names, so G4 has work
that no list yet holds (F5).

## 3. Known differences and the level 1.0 re-recordings

`tools/split/known-differences.txt` holds **68 entries**, not about 31 (the figure G1 reported): A1 2, A5 17, A14 3, A9 37, A7b 6,
A15 3. I ran the reference bridge on the i7a jars and `tools/split/compare_results.py`: 2,051 identical, 72 different, 67 of them
listed. The other 5 are the `sutra/binder/panels` cases, which need the engine's class path (`RefBridgeSutra`); I did not build
Drishti to run them, so **the binder's 5 cases against the reference are not confirmed by this review**. One listed entry
(`edit/ops#refused-option…`) came out identical in this run, as its reason says it may.

| Group | Entries | Justified | Could a Drishti screen change at level 1.0? |
|---|---|---|---|
| A1 codes | 2 | yes | **Yes, in error text.** A refusal that quotes another refusal carries `RCH-4010` inside its words. Drishti maps codes at its API edge; a code inside a message is not mapped unless the mapping also rewrites text. Check at G4. |
| A5 maths (fdlibm) | 17 | yes; equals `StrictMath`, the only way to the same bits on three runtimes | **Yes, in the last digit.** Any expression over `exp`, `ln`, powers, distributions or the finance functions can move by one unit in the last place. Visible only when shown unrounded, at a rounding tie, or in an equality or threshold test. The evidence capture of G4 decides; the headline VaR and ES are computed by the server, not by expressions. |
| A15 maths | 3 | yes (same cause; A5 had missed the method references) | as A5. The reason text is wrong for `el/determinism#d-m01-a`: it says the tolerance passes both, but the case has no tolerance and **fails** (expects `2.718281828459045`, gets `…0455`); d-m04-c and d-m05-a were updated, this one was not. F9. |
| A9 aggregates computed | 23 | yes for Core alone | **Possibly.** In Drishti these names were answered by the pivot engine's provider; after A11 Core answers them first (D9-2). D9-3 already says geometric mean, skewness and kurtosis may move in the last place. Check at G4. |
| A9 refusal lists | 14 | yes | Only the words of an "unknown function" refusal (the list of known names). In Drishti the list already held the engine's aggregates, so likely no change; the order may differ. |
| A14 Core Sutras | 3 | yes (the reference cannot parse a Sutra without `match`) | No. |
| A7b `and`, `or`, `not` | 6 | yes (PY1) | No valid Sutra changes: the reference refused these words. A Sutra that was refused for them is now accepted. |

**The re-recordings (D5-1, D9-4, D15-4).** Level 1.0 was written again three times, 59 result lines and one line of
`functions.yaml`. This is acceptable **only because 1.0 is not released**: no customer holds a document stamped by it. Two
conditions for the owner (O5):

- The recorded level still holds **59 failing results**: the 1.0 decisions on reserved tokens, exact decimals and code points are
  specified and have cases, but are not built. Level 1.0 therefore must be written once more when they are built. It should be
  frozen for good, with `FROZEN.sha384` out of `pre-release`, in the same commit as the `v1.0.0` tag, and never after.
- Until then each further rewrite needs the owner's line in DECISIONS, as these three have.

## 4. Findings

Severity: **blocker** (G1 cannot close), **major** (fix before G2 starts), **minor** (fix when next in the file).

**F1 (blocker). The customer corpus is not proven on Core.** `CorpusRuntime.choose` has `boolean coreReady = false` with the
comment "flips when rachana-core exposes the compile entry (plan A13)". A13 and A14 are done; the flag never flipped. In the
default build the test is skipped; with `-Drachana.reference.classpath` it would compare Drishti's compiler with its own recording.
So "all 478 Sutras compile byte-identical" rests on work outside the repository (the CHANGELOG speaks of "331 corpus Sutras built
both ways"). I ran the corpus program on Core's jars without a profile: all 478 differ, as expected, because the Drishti profile
(`match`, `kind` sources) is not applied; so a Core runtime for this test needs the Drishti profile of the conformance suite or
better. Fix: wire `CoreRuntime`, make it the default, and make the absence of a runtime a failure, not a skip.
`recordedBindsReplayThroughARecordedHost` is empty too (no `binds/` recorded, VERSION.json: "binds: later (v0.10.0)").

**F2 (major, security). The regex step budget is per match, not per call.** `SafeRegex.replaceAll` (and every loop over matches)
starts a fresh budget for each match. Measured on 8,192 characters of `a`: `a(.*b)?` 610 ms, `(a|aa)*c|a` 1,673 ms,
`a(a|b)*c|a` 1,477 ms, for one call, with tiny patterns; the bound is the input length times the budget (8,192 × 20 million
steps). One such expression in a table column over 10,000 rows is hours of CPU. Fix: one budget for the whole call.

**F3 (major, security and one way per job). A form field's `pattern` runs on `java.util.regex`.** `model/Form.java` lines 345 and
731 compile the Sutra's pattern with `Pattern.compile` and match the user's text with backtracking, outside `SafeRegex` and its
caps. With generated Sutras the pattern is not trusted. It is also a second regex engine for the same job, and on the JavaScript
build it runs on TeaVM's emulation. Moving it to `SafeRegex` changes the accepted dialect (no look-around, no back-references):
decision O10.

**F4 (major). The block library is process-wide.** `parse/SutraBlocks` keeps `LIBRARY`, `PROBLEMS`, `WARNINGS`, `CLASHES`,
`CONTRIBUTIONS` and the UI kit folder in static fields, set by `SutraBlocks.load(dirs)` and `kit(dir)`. SPLIT_PLAN marks A6
"registries per instance, no static registration" as done. Two Rachana instances in one JVM (two tenants, two profiles, Studio
beside a host) share and overwrite one library. It is the only static mutable state left in Core.

**F5 (major). The host's migration lists are incomplete.** `Functions.liveSource` and `timeoutNanos` are removed and not in
`DRISHTI_MIGRATION_TODO`. Elements 3.0.0's breaking changes (section 2) have no migration list at all; G4 migrates Drishti's
console onto them.

**F6 (major, docs).** `docs/ROADMAP.md` says "Not started: CG2 level replay, CG5 API surface" (both built) and places the
WebAssembly build for Python in R2; `README.md`, `docs/ARCHITECTURE.md` and `docs/OVERVIEW.md` still give WebAssembly as the
Python route, while the code and `COMPILER_AND_RUNTIMES` 5.3a use V8. Decision **W-1 is not in `DECISIONS.md`** (only in
`WASM_RESULTS.md`), and D17 there still recommends WebAssembly. `el/FunctionProvider`'s Javadoc describes `ServiceLoader` and
names the host's pivot engine. `CorpusRuntime`'s comment is stale (F1).

**F7 (major, nothing local-only).** (a) `tools/conformance/rachana_conformance.py` has the default
`DRISHTI_FORMATS = "/home/ashutosh/IdeaProjects/drishti/config/packs/core/core/config/formats.yaml"`: every `--jvm` and `--node`
result depends on a file of another repository's working tree on one machine. The Drishti profile's format file belongs in this
repository (the corpus has a `profile/` folder with only a README). (b) The reference results that `compare_results.py` needs are
git-ignored and kept nowhere; the proof of each split step can only be repeated by someone who has Drishti's jars built. Commit the
reference results of the last step (or record their SHA-384 and how to make them).

**F8 (minor, becomes major at 1.0). 59 cases of level 1.0 fail and are recorded as failing.** See section 3. Before `v1.0.0`:
build them or move the cases to the level that brings them; unverified failing should be 0 at the tag.

**F9 (minor).** `el/determinism#d-m01-a` fails with a wrong reason in the known-differences file (section 3). Give the probe
Core's value as D15-4 says was done.

**F10 (minor, security).** `python/src/drishti_rachana/engine.py` creates `MiniRacer()` with no heap limit and calls with no
timeout, and loads the bundle without checking the SHA-256 that D15-7 records. Core's own budgets bound an expression, so this is
defence in depth: set `max_memory` and a call timeout, and check the bundle hash on load.

**F11 (minor).** Versions disagree with "v0.11.0 ready": `java/pom.xml` `0.10.0-SNAPSHOT`, `js/packages/rachana/package.json`
`0.9.0-snapshot`, `python/pyproject.toml` `0.0.1.dev0`. `.gitignore` lacks `__pycache__/`. Six worktrees and about 30 remote
branches of merged work remain in `drishti-rachana`, four worktrees in `drishti-elements` (checklist D3 asks for their removal;
the owner's rule is to remove merged worktrees at once).

**F12 (minor, the checklist itself).** C1's pass rule names `conformance/coverage-gaps.txt`, which lists uncovered language
entries, not expected failures: there is no committed list of the expected unverified failures, so "does not grow" cannot be
checked (add one, or drop the rule when F8 is done). C3 needs the `conformance` extra. C9's `spec_files.py` and
`compare_viewmodel_schema.py` need arguments and a reference that no longer matches Core after A9; name their replacements
(the freshness tests in the build).

**F13 (minor, proof depth).** The binder, the largest moved part, is compared with the reference by 5 `sutra/binder/panels`
cases and 3 Core examples; the rest of its assurance is the moved unit tests and, later, G4's evidence. With F1 fixed the corpus
should also record ViewModels (`binds/`) so that the binder is replayed on the 478 Sutras before G4.

What I checked and found sound: the patched TeaVM file differs from TeaVM 0.16.0's published source only in the header and the
two removed `case ADD:` / `case MULTIPLY:` labels; Core has no reflection (`java.lang.reflect`, `Class.forName`, `MethodHandle`:
none; one `ServiceLoader` mention, in a comment); `SafeRegex` itself is linear per match and caps pattern, program and input;
Core's last two libraries (Jackson, SnakeYAML) are gone and only AssertJ (tests) is exempt; drishti-views builds DOM nodes only.

## 5. Owner decisions needed

| # | Decision | Notes |
|---|---|---|
| O1 | **Bundle size (T2, P10).** Runtime 361 KB Brotli against 150 KB budget and "about 200 KB" accepted; tools 381 KB against 350 KB. | Accept about 360 KB and restate the budget, or fund the removal work (D15-5: the in-memory file system is about 7%; the parser, checker and view builder are the rest). |
| O2 | **Evaluation speed (T4).** Median 14 times the hand-written interpreter (8 to 100 times). | Fallback 3 (a hand-written JavaScript evaluator for Core: a second implementation, against "one way per job", held equal by conformance) or move the threshold. My view: move the threshold for G2 and measure on real screens first. |
| O3 | **Datasets and `grid`, `tree`, `chart`.** | Decided 2026-11-21: completed in G1 as ViewModel contract 1.1 and Views step 5. **In progress**; re-check in section 7. Record it in DECISIONS and the plan. |
| O4 | **Map panels need a geo helper from the host** (`geo` option of drishti-views). | Ship the helper in Elements (one way, works for every host) or leave it to each host. |
| O5 | **Level 1.0 re-recordings D5-1, D9-4, D15-4** and the freeze rule of section 3. | Confirm, and confirm that level 1.0 freezes at the `v1.0.0` tag. |
| O6 | **API.** The 5 removals; the 1,463 added lines becoming frozen API at the next tag; Elements 3.0.0's breaking changes. | Confirm, or ask for the surface to be narrowed before `v0.10.0`. |
| O7 | **Pushes before the rule.** Rachana `main` `d90176e` and `v0.9.0`, Elements `v3.0.0-rc.1` (all 2026-11-19 morning); Drishti `main` `a816aa7c0` beside the approved `9ce7c3170`. | Confirm these are the approved state the later gates compare with. |
| O8 | **PY1 to PY8 as built** (A7b: `and`, `or`, `not` in the parser now; the rest later). | DECISIONS marks it "for the owner's review". |
| O9 | **Merges and tags after G1:** `g1/integration` to `main`, `v0.10.0`, `v0.11.0`, Elements `3.0.0`, `3.1.0`. | Only after F1 to F7 and the re-check. |
| O10 | **Form `pattern` on `SafeRegex` (F3).** | It narrows the accepted patterns; the 478 corpus Sutras show whether any shipped pattern needs more. |

## 6. Before G2 starts

1. F1: the corpus on Core, default, failing when it cannot run; then C5 run again.
2. F2, F3 (after O10), F4.
3. F5: both migration lists complete (Rachana API, Elements 3.0.0).
4. F6: ROADMAP, ARCHITECTURE, OVERVIEW, README, DECISIONS (W-1, the datasets decision) current.
5. F7: the Drishti profile's format file in the repository; the reference results kept.
6. F9 to F12 with the same commits where the files are touched; F8 and F13 planned inside G2 before `v1.0.0`.
7. The re-check of section 7.

## 7. Re-check when the datasets work lands

Short, on the new heads of both `g1/integration` branches:

- **Contract append-only:** `ContractAppendOnlyTest` green; `spec/1/released/*` unchanged; contract 1.1 only adds.
- **Stamp unchanged:** a document that uses no dataset still carries stamp 1.0 and the same bytes: C1 to C4 again, the three
  result files identical to each other, and no line of this review's `results/jvm.jsonl` changed for an existing case.
- **Corpus byte-identical:** C5 with F1 fixed, not skipped.
- **Level replay:** `levels.py check` identical for 1.0; no new rewrite of `compat/levels/1.0` without a decision.
- **API surface:** additions only.
- **Elements gates:** C8 whole; the pinned schema (`drishti_viewmodel.pin.json`) names Rachana's new commit; `grid`, `tree`,
  `chart` and datasets have vectors, browser tests, budget lines and their part of `USING.md` and `VIEWS_DESIGN.md`.
- **Docs:** both CHANGELOGs, `spec/1/VIEWMODEL.md`, DECISIONS.

## 8. Re-check (2026-11-21)

Re-check of section 7 and of the findings, in fresh clones from `origin` (JDK 21, Node 22, Python 3.12; `./mvnw clean verify`
only, nothing installed; Drishti read-only). Heads: `drishti-rachana` `g1/integration` `52e112f`, `drishti-elements`
`g1/integration` `db1dc55`.

**Verdict: FAIL, on one blocker (R1). Everything else passes.** The fix is expected to be small; the re-run is R1's two commands.

**R1 (blocker). The runtime bundle is broken at `52e112f`.** `rachana_conformance.py --node --bundle
js/packages/rachana/dist/rachana.runtime.js`: Node stops on `TypeError: Cannot read properties of null` (1,826 errors, 1,463
verified failing). With a runner that catches the exception per case: **10 errors and 27 verified failing** against the tools
bundle (`el/dates` 4 and `el/logic` 5 null reads, e.g. `round(2.675, 2)`, `shift_date`; `problem/coverage-valid#panel-refresh-mapping`
`b.ni is not a function`; 27 cases where a named format is not applied: `amount2` of 1234.5 gives `1234.5`). Both parents are
clean on the same command: `7332c54` (gate fixes) and `3796973` (contract 1.1) give 0 errors, 0 verified failing; so the merge
commit itself brought it. Reproduced on two clean builds (runtime SHA-256 `4df91f42…`). The tools bundle, the JVM and Python on V8
are not affected, which is why the merge's own check ("JVM and JavaScript agree") did not see it: no test or gate runs the
runtime bundle. Fix the bundle, and add the runtime-bundle run to the build (the result equal to the tools bundle's apart from the
43 `edit` cases), so that it cannot regress unseen.

| Check | Result | Evidence |
|---|---|---|
| F1 corpus on Core | **fixed** | `CustomerCorpusTest` 6 run, 0 skipped; Core with the `drishti` profile is the default; 478 compared (asserted against `VERSION.json`), byte-identical; the recordings are unchanged since `7c33edd`. Proven to fail: one byte changed in a recorded document gives `1 differences`; `-Drachana.compat.runtime=bridge` with no class path is an error, not a skip. |
| F2 regex budget | **fixed** | The three 8 KB cases of the review stop at one budget per call: `RCH-4011` after 316, 354 and 359 ms (cold JVM); `SafeRegexBudgetTest`. |
| F3 form pattern | **fixed** | No `Pattern.compile` of a Sutra's text remains in Core (only fixed patterns of Core's own); `RegexText` deleted; O10, G1-1 in DECISIONS. |
| F4 block library | **fixed** | `SutraBlocks` has no mutable static; `BlockLibrary` per instance through the profile; no mutable static collection in Core. 25 API members removed for it, all in `DRISHTI_MIGRATION_TODO` 8.3i. |
| F5 migration lists | **fixed** | `liveSource`, `timeoutNanos` in the list; Elements `docs/MIGRATING_TO_3.md` names every removed member of section 2. |
| F6 docs | **fixed, one leftover** | README, ARCHITECTURE, OVERVIEW, ROADMAP text say V8 (W-1 in DECISIONS). ROADMAP's diagram still reads `R2 WASM, server, editor` (line 33). |
| F7 local-only | **fixed** | The tool's default is `tools/conformance/profile/drishti-formats.yaml`; no `/home/` path in the repository; `tools/split/make_reference.py` rebuilds the reference results (not run here). |
| F9 `d-m01-a` | **fixed** | The probe expects `2.7182818284590455`; the level result is `pass`. |
| F10 V8 engine | **fixed** | Heap limit, call timeout, SHA-256 check before the bundle runs; `test_engine.py` green. |
| F11 versions | **partly** | `0.11.0` in pom, `package.json`, `pyproject`; `__pycache__` ignored. Still 7 worktrees and 35 remote branches in `drishti-rachana`, 5 and 7 in `drishti-elements` (all clean, none ahead). |
| F12 checklist | **fixed** | Checklist commit `8756ecfd6` (C1, C3, C9 run as written). |
| Contract append-only | **pass** | `spec/1/released/viewmodel-1.0.schema.json` is byte-identical to the schema reviewed at `7c33edd`; against it the current schema removes nothing, makes nothing newly required, adds 13 definitions (`Dataset`, `DataField`, `ArrowRef`, `PagesRef`, `ServerRef`, `Grid`, `GridColumn`, `SortKey`, `GridPivot`, `Measure`, `Tree`, `DataChart`, `GeoPoint`); `ContractAppendOnlyTest` 3 green. |
| Stamp | **pass** | Every existing `sutra` case still stamps `1.0` at both levels; the datasets cases stamp `1.1` (one without a dataset stays `1.0`); no answered case differs between a run at level 1.0 and one at 1.1. |
| Conformance (tools bundle) | **pass** | 2,130 cases. Level 1.0: pass 1,939, unverified failing 58, **verified failing 0**. Level 1.1: pass 1,945, unverified failing 104 (the 46 more are the unbuilt 1.1 suites, 43 of them `el/messages-level-1-1`), **verified failing 0**. JVM = Node byte-identical at both levels; Python on V8 = JVM at 1.1. |
| Conformance (runtime bundle) | **fail** | R1. |
| Level replay | **pass** | `levels.py check`: `level 1.0: identical (2109 identical, 3 newly answered, 18 cases added since)`; `LevelReplayTest` green. Two lines of `compat/levels/1.0` re-recorded (G1-2, accepted). No `compat/levels/1.1` yet: record it when 1.1 is released. |
| API surface | **pass** | Since `7c33edd`: the gate fixes remove 25 lines (all F4) and add 31; contract 1.1 and the merge add 174 and **remove 0**. `ApiSurfaceTest` green. |
| Unit tests, guards | **pass** | `./mvnw clean verify`: 912 tests, 0 failures, 0 skipped. |
| Elements gates | **pass** | `build.py --check`, `elements_api.py --check`, `license_headers.py`: 0. Node 341 pass, 0 skipped. pytest with `DRISHTI_RACHANA` at `52e112f`: **160 passed, 0 skipped** (datasets 5, maps 3 browser tests). The pin names `52e112f`, contract 1.1, and its SHA-256 equals Rachana's schema. |
| Docs | **pass with notes** | Rachana CHANGELOG, `VIEWMODEL.md`, `SPEC.md`; Elements CHANGELOG 3.1.0, USING, CONTRACTS, VIEWS_DESIGN, MIGRATING_TO_3. Notes: R3. |
| Nothing on `main`, no tags, nothing local-only | **pass** | Rachana `main` `d90176e`, tag `v0.9.0`; Elements `main` `39ca755`, tags `v2.4.0`, `v3.0.0-rc.1`; Drishti `main` `9ce7c3170`: all as at the review. Every worktree clean and on `origin`. |

Remaining, not blocking:

- **R2. Bundle sizes are over the budgets restated today (O1).** Runtime 366,439 bytes Brotli (budget 365 KB), tools 387,663
  (385 KB); contract 1.1 added about 6 KB to each. No test holds the budget. Trim, or the owner restates again; either way put the
  budget in a check.
- **R3. Docs.** DECISIONS has no line for O3 (datasets and the three kinds in G1 as contract 1.1 and level 1.1) or O4 (the geo
  helper ships in Elements); ROADMAP and README do not mention level 1.1; ROADMAP's diagram (F6); the CHANGELOG still records the
  bundle hashes of `28d01bf` only (rule P1-3 applies at the release).
- **R4. `python/tests/test_protocol.py::test_schema_roundtrip_keeps_order_and_drops_absent` fails** (a column's `to_json` writes
  `fmt, unit, scale`, the test expects `scale, unit, fmt`). Not from this work (the file is unchanged since the package skeleton)
  and outside C3's smoke test, but it is a red test on the branch: 15 passed, 1 failed.
- **F11**: the merged worktrees and branches. **F8, F13** stay planned for G2 as before.

To close G1: fix R1, then run `build_js.py` and the conformance tool on both bundles (the runtime bundle with `--bundle`), with
C4's comparison. No other part of this re-check needs repeating if the fix touches nothing else.

## 9. How this review was run

Fresh clones of both product repositories from `origin` in a scratch folder (removed afterwards); JDK 21 at
`/usr/lib/jvm/java-21-openjdk-amd64`; Maven `package` and `verify` only; the reference bridge on the existing i7a jars of the
Drishti checkout, read-only; no Drishti server started; nothing fixed by the reviewer (no fix commits).
