<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7b gate checklist

Used by every Fable gate review (G1 to G4) of [I7B_PRODUCTS_PLAN](I7B_PRODUCTS_PLAN.md), section "Four milestones and their Fable gates".
The review writes `docs/plans/I7B_GATE_<n>_REVIEW.md`: for each item below **pass / fail / not applicable yet**, the command run, and
the evidence (output line or file). A gate is a point-in-time inspection, not a drill. Run on JDK 21 (production runtime). One heavy
job at a time; local data at most 10K trades. Remove temporary files afterwards.

Paths: `RACHANA` = the `drishti-rachana` checkout on `g1/integration`; `ELEMENTS` = the `drishti-elements` checkout on `g1/integration`;
`DRISHTI` = a Drishti checkout on `wip/i7b-evidence` used **read-only**.

## 1. Correctness

| # | Check | Command | Pass when |
|---|---|---|---|
| C1 | Conformance, JVM | `cd RACHANA/java && ./mvnw clean package -DskipTests && cd .. && mkdir -p results && python3 tools/conformance/rachana_conformance.py --jvm --out results/jvm.jsonl` (the Drishti profile's format names come from `tools/conformance/profile/drishti-formats.yaml`; `RACHANA_DRISHTI_FORMATS` names another file) | `verified failing 0`; unverified failing at most the recorded 59 of F8 (the unverified 1.0 suites: `el/numbers-exact`, `el/reserved`, `el/text-code-points`), and it reaches 0 before `v1.0.0`; `conformance/coverage-gaps.txt` is a different list (language entries no case covers) and is not the pass rule |
| C2 | Conformance, JavaScript | `cd RACHANA && python3 tools/js/build_js.py && python3 tools/conformance/rachana_conformance.py --node --out results/node.jsonl` (TeaVM build of java/rachana-js, then the `node` runtime of `conformance/RUNNER.md` over `js/packages/rachana/dist/rachana.tools.js`; `--bundle .../rachana.runtime.js` for the runtime bundle) | same counts as C1 |
| C3 | Conformance, Python (V8, decision W-1) | `cd RACHANA && python3 tools/js/build_js.py && pip install -e 'python[v8,conformance]' pytest && python3 tools/conformance/rachana_conformance.py --runtime python-v8 --out results/python-v8.jsonl --cmd "python3 -m drishti_rachana.engine --profiles core,drishti spec/1/formats.yaml tools/conformance/profile/drishti-formats.yaml"` (the JavaScript bundle on V8 through `mini-racer`, checked against `SHA256SUMS`; needs `pyyaml` and `jsonschema` from the `conformance` extra; smoke test `python3 -m pytest -q python/tests/test_engine.py`) | same counts as C1 |
| C4 | Runtimes agree | compare the `results/<runtime>.jsonl` files: `cmp` the JVM and JS (and Python) files | byte-identical, or each difference is in a written known-differences file with a reason |
| C5 | Customer corpus | `cd RACHANA/java && ./mvnw -o verify -pl rachana-compat -am` (`CustomerCorpusTest`) | green: all 478 Sutras of the corpus compile to the recorded problems, models and plans, byte-identical (the manifest lists no missing or extra file) |
| C6 | Drishti results unchanged | evidence tooling, **read-only** against DRISHTI with the candidate products where applicable: `python3 tools/evidence/capture.py --home DRISHTI --jar <jar> --data <scratch dir> --port 19183 --out <scratch>/new --label g<n>` then `python3 tools/evidence/compare.py testing/evidence/baseline-v2.9.11.i_7a <scratch>/new --out <scratch>/compare.json` (the baseline is `DRISHTI/testing/evidence/baseline-v2.9.11.i_7a.tar.gz`, unpacked into the scratch folder) | exact class: no difference; reviewed class: every difference has a line and a reason; pages within thresholds. Not applicable before products are wired in (then run the proof `proof-i7a-against-itself` only) |
| C7 | Headline figures | the `figures.json` of the capture (C6) | VaR **167,372,692** and ES **210,131,972**, exact |
| C8 | Elements | `cd ELEMENTS && python3 tools/elements/build.py --check && python3 tools/docs/elements_api.py --check && python3 tools/license_headers.py && node --test tests/elements/*.test.mjs && python3 -m pytest -q tests -n 8` | all green; no skipped browser test without a stated reason |
| C9 | Language definition and reserved names fresh | `cd RACHANA && python3 tools/conformance/coverage.py && python3 tools/regime/reserved_names.py --check && cd java && ./mvnw -o -q test -pl rachana-compat` (`ReservedNamesTest`, `ContractAppendOnlyTest`, `LevelReplayTest`, `ApiSurfaceTest`, `FrozenCasesTest`); `spec_files.py` and `compare_viewmodel_schema.py` are retired from the gate: they compared with the reference's tables, which A9 moved away from, and need a class path and a dump as arguments | exit 0, no stale file |

## 2. No side effects

| # | Check | Command | Pass when |
|---|---|---|---|
| S1 | CG1 frozen cases | `cd RACHANA/java && ./mvnw -o verify -pl rachana-compat -am` (`FrozenCasesTest`); `git diff <last tag> -- conformance/1` | no released case edited or removed; additions only |
| S2 | CG2 level replay | `python3 tools/regime/levels.py check` (also `LevelReplayTest`, needs the JVM runtime built) | every `compat/levels/<L>` replays byte for byte; no file under an existing level changed (`git log -- compat/levels`: add-only) |
| S3 | CG4 contract append-only | `ContractAppendOnlyTest` (same run) | green; `spec/1/released/*` unchanged |
| S4 | CG5 API surface | `ApiSurfaceTest` (same run); `git diff <last tag> -- java/api-surface.txt` | no removed or changed line; every added line reviewed and intended |
| S5 | CG6 reserved names | `ReservedNamesTest` (same run) | green; `compat/promotions.yaml` and `level-switches.yaml` changes carry the owner's approval |
| S6 | Drishti untouched | `git -C DRISHTI diff --stat origin/main...HEAD -- . ':!testing/evidence' ':!tools/evidence' ':!docs'` and `git -C <drishti main checkout> status`, `git log origin/main -1` | nothing on Drishti `main` since the baseline commit (i7a `d694c1382` plus owner-approved commits); before G4 no Drishti production file changed on any i7b branch |
| S7 | No pushes to `main`, no tags | `git ls-remote --heads --tags origin` in each repo; compare `main` and tags with the previous gate | `main` and the tag list identical to before the milestone (unless the owner approved) |
| S8 | Elements public API | `python3 tools/docs/elements_api.py --check` and the Elements API diff against the last tag | no breaking change without the owner |

## 3. Quality

| # | Check | Command | Pass when |
|---|---|---|---|
| Q1 | Design conformance | read the milestone's rows of the plan and `docs/design/*` against the code; list each plan row done / partly / not done | every row of the milestone done, or listed with a decision |
| Q2 | One way per job; no duplicates | `RACHANA/java ./mvnw -o verify -pl rachana-guards -am` (guards); search for a second implementation of anything the milestone folded | guards green; no duplicate machinery; no code in two repos |
| Q3 | File-size cap and headers | guards (`LicenceHeaderAndLineCapTest`); `python3 ELEMENTS/tools/license_headers.py` | no file above the cap (2,500 lines); every file has the licence header |
| Q4 | Performance budgets P1 to P11 | the benchmarks named in `docs/design/COMPILER_AND_RUNTIMES.md` section on budgets (`tools/proofs/`, Elements `tests/test_elements_budget.py`, Views budget file) | each within budget; a regression above 10% against the last gate fails; P10 byte sizes measured |
| Q5 | Security: tokens | search for tokens, keys and passwords in the diff: `git diff <last gate>..HEAD | grep -iE "secret|token|password|api[_-]?key"` and a secret scanner if installed | none committed; samples use placeholders |
| Q6 | Security: masking | the masking cases of `conformance/1` (masked values stay masked through ViewModel and frames) and the Elements masking tests | green; no masked value in any golden or evidence file |
| Q7 | Security: sandboxing | the regex caps (`SafeRegex`) and evaluation budgets tests; the hostile-input corpora where built; Studio and host SDK embedding tests where built | green; no network or file access from an expression |
| Q8 | Security: CSP | Elements and Studio pages load no CDN and no inline script that a strict CSP would block: `grep -rn "https\?://" ELEMENTS/elements ELEMENTS/css` (vendor excluded), the browser CSP test where built | no external URL at runtime; strict CSP passes |
| Q9 | Licences | `python3 -m pytest -q tests/test_vendor_licences.py` in ELEMENTS; `DependencyAllowListTest` in RACHANA | green; each library's licence listed |

## 4. Completeness

| # | Check | Command | Pass when |
|---|---|---|---|
| D1 | Customer docs current | for each feature of the milestone: its user guide, tutorial and reference exist and match the code (not API reference alone); `python3 ELEMENTS/tools/docs/elements_api.py --check`; the Rachana docs checks (`docs/` link and generated-file checks) | every shipped feature has a user guide; no broken link; no stale generated page; docs state no history or bugs |
| D2 | CHANGELOGs | `CHANGELOG.md` of each repo | an entry for every change of the milestone |
| D3 | Everything pushed | `git status -sb`, `git log origin/<branch>..<branch>` in every worktree of every repo | clean and not ahead; worktrees of merged branches removed |
| D4 | DRISHTI_MIGRATION_TODO current | read `docs/plans/DRISHTI_MIGRATION_TODO.md` against the API changes of the milestone (the API-surface diff of S4) | every API change the host must adapt to is listed |
| D5 | Nothing local-only | `git status --ignored` in each checkout; search `/tmp`, scratch and home folders for results the plan cites | every result cited by a doc is committed; no file under the plan depends on an untracked path |
| D6 | Plan and decisions | `docs/plans/I7B_PRODUCTS_PLAN.md` and `docs/design/DECISIONS.md` | owner decisions recorded; sizes re-stated if the rate changed |

## 5. The report

`docs/plans/I7B_GATE_<n>_REVIEW.md` holds: date and commits of every repo reviewed; a table of every item above with pass / fail / n.a.
and the evidence; **findings** (severity: blocker, fix before next milestone, note); what was fixed and the commit; and a five-line
owner summary. Findings are fixed before the next milestone begins; the report is committed and pushed with them.
