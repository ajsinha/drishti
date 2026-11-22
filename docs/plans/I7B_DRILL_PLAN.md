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
# i7b drill plan

**Status: draft for the lead and the Fable pre-drill review (2026-11-21).** Nothing here has been run. Prepared early so the drill
starts the hour the Fable review passes. Tooling: `tools/drillkit/drill_readiness.py` (this branch), `~/drishti-bench-scratch/i7b-readiness.sh`,
`~/drishti-bench-scratch/i7b-drill.sh` (draft). Deleted with the other i7b plan documents at the release.

The pipeline is the one that worked for i7a: **Fable pre-drill review -> fixes -> scripted pre-check -> smoke on the build-all install
-> one drill**. The lanes, timings and rules below come from the i7a run (`logs/i7a-drill.log`) and the memory notes on drill lessons.

## 1. Lanes

One command, `tools/drill.sh --lanes=browser,other,tools-a,tools-b,java21,java25,anywhere --keep-going --no-push`, after the gate. The
i7a wall-clock was about 72 minutes for the java21 lane, the longest.

| Lane | Runs | i7a time | Memory / CPU |
|---|---|---|---|
| gate (stage 1) | licence headers, generated documents current, docs + help tests, quick static file checks, Java rule tests (drishti-it), Spotless, Javadoc doclint, module packaging | 265 s | about 8 GB, all threads |
| java21 | `clean verify -P api-docs` in a copy of the tree on JDK 21 (every test but container tests; includes the packaged goldens: `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest`, the exposure goldens) | 4,336 s | about 20 GB, two test forks, lowered priority |
| java25 | whole tree compiled on JDK 25 + the tests in `tools/drill-java25.txt`; starts once 20 GB are free. Kept while the JDK 25 migration is a roadmap item (owner 11-02: production may stay on 21) | 2,143 s | about 10 GB |
| browser (console) | ~197 files on the scratch ports: page walk (every route, 404/console error/empty page, p95 load times), layout checks, ~30 visual snapshots dark+light, command and API sweeps, render parity, tutorials. **Chromium by default; WebKit pass in section 1.1** | about 2,630 s on 9 workers | memory-driven workers (`drill_plan.py workers --reserve-gb 24`), peak about 42 GB total, 79 Chromium processes |
| other (console) | ~217 files, in-process against a fake backend, guide images, JSON bodies, tutorials | 668 s | 8 workers, low priority |
| tools-a / tools-b | the Python tool tests, two lanes (uv, deltalake, pyarrow, moto) | 3,400 / 3,550 s | one process each |
| anywhere | the release unpacked into a scratch folder, started from another folder; VaR, explain, POS, TRD, FORMS across two restarts; all paths absolute | 1,165 s | one stack, 3 server starts |
| containers (optional) | both images booted, VaR golden through the API. Run it only when the diff since `v2.9.11.i_7a` touches Dockerfiles, the JDK, native code or a module with container tests (`container_smoke.py full-needed`); set `I7B_LANES` | about 10 min | Docker; hidden from java25 |
| packaged goldens | inside java21 (above) and by API in the smoke (section 6): VaR 1-day 99.5% **167,372,692**, ES **210,131,972**, bit-identical | | |
| docs checks | in the gate (settings catalogue, contracts pages, data samples, generated pack content, Python API reference) and the `--docs`-style tests in the other lane | | |

### 1.1 WebKit

`drill.sh` has no WebKit lane and may not change once the drill runs (freeze). WebKit therefore runs **after the drill, as a separate
step**, with `DRISHTI_BROWSER=webkit` on the page walk, layout rules and the snapshot groups
(`test_page_walk_browser.py`, `test_layouts.py`, `test_snapshots_*_browser.py`), 3 workers, never beside another lane. It needs the
system-library helper `tools/browser/webkit_env.py` (G4 follow-ups lane, branch `g4/follow-ups`; readiness warns until it is in the
tree). Pass: the same files green as on Chromium.

## 2. Machine and budget

- This machine: 24 threads, 61 GB, NVMe. The drill, when it runs, owns it: **no builder runs Maven, Chrome, or a stack** (lesson 3, 6, 8 of
  the 2.9.11 drills). The i7a drill had the browser lane at a 42 GB peak and 13.5 GB minimum available.
- Pre-start: more than 30 GB available (readiness), more than 150 GB free on the data disk, load average under 8, the owner's stack
  (17480, 18480, 18791, 18792) untouched. The drill's ports: scratch window 17750-32767 (12 workers x 1,250 ports), fixed 20783-20785
  and 21783-21785 for the java21 copies.
- If the machine nears the floor: `DRILL_BROWSER_WORKERS` 9 to 6, then `DRILL_OTHER_WORKERS` down; java25 stays after the browser lane;
  never three heavy JVM jobs at once; no need to ask the owner first (his rule of 2026-11-03).
- Temporary files: `DRISHTI_DRILL_TMP` on disk under the data folder, never `/tmp` (tmpfs, 30 GB shared with RAM); the drill removes them;
  after the run clean `~/IdeaProjects/drishti-drill-copies/*`, the drill's Maven repository (section 4), and the fresh clone.
- `deprioritise.sh <session id>` (nice 15, idle I/O for every non-browser lane) runs beside the drill, started by the script.

## 3. Readiness check (read-only)

`~/drishti-bench-scratch/i7b-readiness.sh [--product rachana=v1.0.0 ...]` runs `tools/drillkit/drill_readiness.py`. It checks disk,
memory and load, the owner's and the drill's ports, JDK 21 (and 25 when `tools/drill-java25.txt` exists), python3/uv/git/ss/curl, `mvnw`,
the console venv, Playwright 1.64.x and its browsers (chromium, headless shell, webkit, firefox, ffmpeg), the WebKit helper, running
drill processes, stray test forks, stale drill worktrees and copies, and that the four product tags exist on GitHub (tools v1.1.0,
Rachana v1.0.0, Lake v0.9.1, Elements at the version `pom.xml` pins). It exits 1 on any FAIL and prints a PASS/WARN/FAIL list. It is run
at the start of `prepare`, after the fresh venv exists, and once more just before the lanes.

**Result of the run on 2026-11-21 (this machine, G4 lanes busy):** 20 PASS, 4 WARN, 3 FAIL -> NOT READY. Expected at this stage.

```
PASS  disk free ................ 412 GB (need 150)          PASS  JDK 21, JDK 25, uv, python3, git, ss, curl, mvnw
PASS  memory available ......... 42 GB of 61 GB (need 30)   PASS  owner ports 17480/18480/18791/18792 in use, left alone
WARN  load average (1 min) ..... 27.2 on 24 threads          PASS  drill fixed ports 20783-5, 21783-5 free
WARN  scratch window listeners . [18932, 21716]              PASS  no drill.sh running; no stale drill worktrees
WARN  stray test forks ......... 5 (G4 lanes at work)        WARN  drill copies left: java21.*, java25.* (3 folders)
FAIL  console venv ............. missing in this worktree (a fresh clone builds its own)
FAIL  Playwright 1.64.x ........ found 1.63.0 in the main checkout's venv (browsers on disk: chromium 1243/1248, webkit 2359/2370)
WARN  WebKit helper ............ not in this tree yet (G4 follow-ups lane)
PASS  tag tools v1.1.0          PASS  tag elements v3.3.1 (the pinned version)
FAIL  tag rachana v1.0.0 ...... GitHub has v0.9.0 v0.10.0 v0.11.0        FAIL  tag lake v0.9.1 ...... GitHub has no tags
```

To clear before the drill: tag Rachana v1.0.0 and Lake v0.9.1 (after their Fable gates; the owner's rule of 11-21 allows the lead to
tag once the cross-stack version check passes); raise Playwright to 1.64.x in `drishti-console/requirements-test.txt` (or confirm the
owner's meaning, since the pinned file says `>=1.45`); merge the WebKit helper; let the G4 lanes finish (load, stray forks); delete the
three stale copy folders.

## 4. Fresh start and the products

1. **Fresh clone** of Drishti at the frozen commit (`git clone`, then `checkout <commit>`), never a reused worktree, in
   `~/IdeaProjects/drishti-i7b-drill`. A fresh clone has no console venv: the script makes one (`python3 -m venv`, requirements,
   `playwright install`), because the old trick of linking the main checkout's venv hides requirement drift.
2. **The drill's own Maven repository: `~/drishti-bench-scratch/i7b-m2`.** The owner's `~/.m2` is **never written during the drill**
   (the rule: released jars are installed into `~/.m2` only as a release step from a tag; builders never install snapshots). The script
   `rsync`s third-party artefacts from `~/.m2` (19 GB, read-only copy) with every `com/ash/{drishti,rachana,tools,lake}` left out, and
   exports `MAVEN_ARGS=-Dmaven.repo.local=...` so every `./mvnw` it starts, including those inside `drill.sh` and its java21/java25
   copies, uses it. (`com/ash/messaging`, pravaha, is third party to this release and is copied.) Removed after the drill.
3. **Product install order, each from a fresh `--depth 1` clone of its TAG, `cd java && ./mvnw clean install`:**
   `drishti-tools v1.1.0` -> `drishti-rachana v1.0.0` -> `drishti-lake v0.9.1` -> Drishti.
   A tag that does not exist stops the script at step 0. Elements is vendored (`drishti-console/web/static/vendor/drishti-elements/3.3.1`,
   pinned in `pom.xml`) and checked by tag only. Rachana's JS and Python packages and the conformance results (G4 checklist C1 to C9) are
   inputs of the gate review, not of the drill.
4. If, at the frozen commit, Drishti's `pom.xml` still builds the in-tree Rachana and Lake modules, the installed products are then
   unused by the build and the install is only a proof that the tags build; the lead decides at the pre-drill review which it is.

## 5. Freeze and the second-drill rule

- **Freeze:** from the pre-drill review until the verdict: no merge into the drilled commit, no change to `tools/drill.sh`, the drill
  scripts or `tools/drill-*.txt`, no builder running anything heavy (code-only), nothing mid-release added to the drill tooling. The
  script refuses to start the lanes if the clone's HEAD is not the named commit or the tree is dirty.
- **One drill** if the first is green (zero failures, see section 7). **A second full drill** if the first needed any fix: fixes made,
  then `drill.sh --full`-equivalent lanes again on the new commit; nothing ships "fixed but not re-tested" (owner 11-01). Fix drills
  (`--fix`) are allowed for diagnosis only and never replace the second full drill.
- Flaky tests: surefire reruns once and the console lanes rerun failed files once; a test that failed then passed is listed in
  `flakes.log` and reported, and is not a pass without a reason in `tools/drill-known.txt`.

## 6. Order of work

| Step | What | Who | Time |
|---|---|---|---|
| 0 | all milestone gates passed; version bumped; products tagged; docs + README + release notes written; freeze starts | lead | |
| 1 | **Fable pre-drill review** (`I7B_PREDRILL_REVIEW.md`: findings by likelihood x cost); fixes; each fix verified by its whole files plus the gate | Fable, builders | hours |
| 2 | `i7b-drill.sh <commit> prepare`: readiness, fresh clone, drill Maven repo, products from tags (Lake's own check once, the Lake wheel built from its tag and checked against the pin), the Lake wheel then the venv, build all; the gate, the modules' tests and the tools lanes are run once, by the drill (no duplicate runs) | script | about 60 min |
| 3 | **Smoke on the build-all install** (I7B_PLAN 8.8): three-node stack (`drishti-stack` pattern), LAN addresses never loopback; events across nodes, two tabs one connection, embedded view with a renewed token, `drishti.py server smoke`; **VaR 167,372,692 and ES 210,131,972 by API and on the VaR page**; stop the stack and remove its temp files; `touch logs/i7b/smoke.ok` | lead | 30 min |
| 4 | `i7b-drill.sh <commit> drill` with `run_in_background`; watch `drill.log` modification time and the `^drill` lines; anchored `pgrep -f "^bash tools/drill.sh"` only (never an unanchored pattern) | script | about 75 to 90 min |
| 5 | WebKit pass (1.1) | lead | about 20 min |
| 6 | verdict against section 7; second drill if any fix | lead | |

## 7. Pass criteria

- **Zero failures** in every lane and the gate; `drill.log` ends `drill: PASSED`-equivalent with exit 0 (i7a ended `FAILED`, exit 1, on a
  java21 flake plus the snapshot comparison: both must be resolved or explained first).
- **Zero skips**, except the documented environment ones (each with its stated reason in `tools/drill-known.txt`): native-binary tests
  on Windows (no `.exe`; Linux drill does not skip them), MinIO-only paths on Windows, Docker tests when the containers lane is off.
- **Goldens bit-identical:** the golden tests green, and the installed stack answers VaR 167,372,692 and ES 210,131,972 (EV4, EV12).
- Page walk: no 404, error, console error or empty page; load budget 10 s per page; p95 per page written to `docs/admin/BENCHMARK.md`.
- Visual snapshots: no changed page unexplained (the i7a run had three changed, with sort-order warnings from `comm`: fixed first).
- The data-folder and config-folder checks at the end of every lane: no test wrote under `data/` or `config/`.
- The WebKit pass green on the same files.

## 8. After a green drill

1. Owner OK (the drill is not a release by itself). Then: push `develop`, merge `main`, tag `v2.9.11.i_7b` (product tags were set
   earlier, after their gates and the cross-stack version check).
2. Delete the plan documents named in I7B_PLAN 8.6 and this file; the deck's chapters refreshed.
3. **Upgrade the owner's stack** (`drishti-stack`: three nodes, drishti-main on 18480/17480) from the tag, never from a build tree;
   restart node by node.
4. **VaR/ES check on the upgraded stack: VaR 1-day 99.5% 167,372,692 and ES 210,131,972**, by API and on the VaR page. Any difference is a
   stop and a rollback to the previous tag, reported to the owner.
5. Clean up: the drill clone, `i7b-m2`, `i7b-products`, `drishti-drill-copies/*`, `/tmp` files, merged worktrees.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
