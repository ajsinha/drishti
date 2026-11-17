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
# Pre-drill review: release i7a

**Status: proposed** (review findings for the fixers; deleted with the i7a plan documents at the release).

Reviewed 2026-11-17 17:50, branch wip/i7a at 052b364aa, diff against tag v2.9.11.i_6 (1012 files). Read only: nothing was built or run.
The drill reviewed: tools/drill.sh with lanes browser, other, tools-a, tools-b, java21, java25, anywhere and keep-going.

## What is already proven, and what is not

- The gate passed in this worktree at 17:35 to 17:39 on this commit (data/drill gate logs): licence headers, generated documents,
  docs and help tests (328), quick file checks (140), the Java rule tests, Spotless, Javadoc doclint, and the gate's module packaging
  (12 packages in modules/).
- **No lane has ever run through the drill on i7a.** Every earlier run folder under data/drill/runs holds gate logs only. So three
  things are unproven: the drill's final data-folder check, the java21 copy's data-folder check, and a full verify of
  testing/drishti-modules-it (76 classes, about 60 moved there) on the merged tree. Five branches merged between 15:35 and 17:34 with
  only the gate after them.

Confidence words below: **confirmed** = the mechanism was read end to end in the code; **likely** = read, one link not verified;
**speculative** = a risk seen, not traced.

## Findings, by likelihood × cost

### 1. Every pytest lane: the drill's data-folder check fails at the very end (confirmed)

- **Lane:** browser, other, tools-a, tools-b, anywhere; reported by data_unchanged after every lane has run.
- **Where:** drishti-console/tests/conftest.py:63-75 and tools/conftest.py:22-28; tools/javacmd.py:319-320; tools/resolved.py:336-353;
  ResolveCli.java:130-148 (withOverrides); LaunchRoots.java:254-262; ModuleBoot.java:409-411.
- **What fails:** "the tests wrote these files under data/: cache/modules/<id>/<version>-<hash>/…" (and possibly
  tmp/resolve/overrides/modules), although every test passed.
- **Why:** both conftests give the run its own data folder (the I6 fix, commit 4ecd33813) only inside the block
  "if DRISHTI_RESOLVED_CACHE is not set". The drill exports DRISHTI_RESOLVED_CACHE before anything starts, so in the drill, and only
  there, the data folder is never set. javacmd then gives every Java command the repository's data folder, and a resolver call without
  a root (resolved.current()) boots the modules and unpacks all 12 packages into data/cache/modules. Callers of current() inside the
  lanes: tools/lake/build.py:94,186,326 (every lake build: test_admin_lakes.py:199, the tools/lake tests, a lake-cache miss, since the
  data-dir argument of lake demo does not reach the resolver), tools/lake/stores.py:77, tools/lake/demo.py:814,
  tools/launch/clusterlakes.py:147, drishti-console/tests/pack_answers.py:37. The gate did not show it: its tools ask with a root
  (source mode, no unpack) and a gate-only run stops before the check. Builders ran pytest outside the drill, where the block is taken.
- **Fix (drill.sh untouched):** in both conftests take the data folder out of that block into one of its own: when DRISHTI_DATA_DIR
  is not set, make a temporary folder (prefix drishti-data-), set the variable, remove it at exit. One folder per pytest process (a
  server's start sweeps the cache of its data folder). Belt and braces in tools/resolved.py _ask: when DRISHTI_RESOLVED_CACHE is set
  and DRISHTI_DATA_DIR is not, pass the data folder as <cache dir>/data. Prove it: run one file that uses with_packs and
  tools/lake/test_build.py with DRISHTI_RESOLVED_CACHE exported as the drill does, then look for data/cache.
- **Grade:** Sonnet.

### 2. tools-a: the Java API comment check fails on the moved error classes (confirmed)

- **Lane:** tools-a (tools_lane_a runs api_docs.py check-java after the test files; the gate runs only check-python).
- **Where:** kernel/drishti-api/src/main/java/com/ash/drishti/api/error/ErrorCode.java (constants from line 23; code() :687,
  httpStatus() :691) and DrishtiException.java (:24, :29, :34); kernel/drishti-api/pom.xml:39; tools/api_docs.py:102-117.
- **Why:** I11 D2 moved both classes from drishti-common (not documented) into drishti-api, whose documented scope is
  com.ash.drishti.api and every package under it. The new package has no package-info.java (6 of the 7 packages have one), about 40
  of the 303 constants have no comment, nor have code(), httpStatus(), the two constructors and errorCode().
- **Fix:** add package-info.java (licence header, one paragraph) and a one-line Javadoc on each uncommented member, the meaning taken
  from the error code table of API_REFERENCE. Check with the api-docs build of drishti-api, then api_docs.py check-java.
- **Grade:** Sonnet.

### 3. tools-a: the SDK test runs Maven clean in the working tree during the drill (confirmed)

- **Lane:** tools-a.
- **Where:** tools/test_sdk_builds_genomics.py:52-58; tools/launch/sdkbuild.py:218.
- **What fails:** (a) check-java, later in the same lane: "drishti-api: no Javadoc at …", because the test's build sdk runs
  clean package without the api-docs profile over drishti-api, drishti-common, drishti-kernel-api and what they need, and deletes the
  Javadoc the gate built; this holds even after finding 2 is fixed. (b) The file itself is at risk of its 900 s limit: two SDK builds
  plus two genomics verify builds beside six other lanes. It also makes and removes sdk/ at the repository root while other lanes
  walk the tree.
- **Fix:** give build sdk a no-Maven switch (as build modules has: assemble from the jars and poms the gate's build left in target/)
  and an out folder; the test uses both with a folder under tmp_path when the gate's jars are there. No Maven in the working tree,
  nothing at the repository root.
- **Grade:** Sonnet.

### 4. java21: testing/drishti-modules-it has never run whole on the merged tree (likely to surface failures)

- **Lane:** java21.
- **Where:** testing/drishti-modules-it (pom.xml:167, reuseForks false; 76 classes on real packages, a JVM and a server start each).
- **Why:** the gate's test list runs none of these classes; target/surefire-reports is empty; I8c2, I16a, I8b, I14 and I8c1 merged
  after the last proof of any of them. Not looked at by this review: ApiSurfaceTest's surface file, ModuleBridgeTest and
  ModuleChildContextGuardTest. ConsoleAnswersRecordTest compares the recorded console answers (bootstrap, admin pages), unchanged
  since i6, with what the server says now.
- **Fix (process, before the drill, machine free):** one offline verify of testing/drishti-modules-it with what it needs; fix what
  fails; then kernel/drishti-server and the banking service if time allows. This is the pre-merge gate the drill lessons ask for.
- **Grade:** Opus for what fails.

### 5. java21: PackagedVarGoldenTest's lake build writes under the copy's data folder (likely)

- **Lane:** java21 (the copy's data and config check, reported after the whole verify).
- **Where:** testing/drishti-modules-it/src/test/java/com/ash/drishti/it/PackagedServer.java:84-121 (buildLake, runLake).
- **Why:** it runs lake demo of tools/drishti.py in the copy with no DRISHTI_DATA_DIR in the environment; the data-dir argument
  moves feeds and staging only. The lake build asks resolved.current() (finding 1), which boots the copy's modules/ packages into
  the copy's data/cache/modules. PythonPathEqualityTest sets the variable and is safe. Not verified: that this lake demo path reaches
  build.py:94, 186 or 326 (packs_root, site_set, _pack_dir); it is hard to see how a load avoids all three.
- **Fix:** in runLake put DRISHTI_DATA_DIR (the lake folder's data) and DRISHTI_TMP_DIR in the ProcessBuilder's environment, as
  PythonPathEqualityTest does. The resolved.py part of fix 1 covers it too when the drill's cache variable is inherited.
- **Grade:** Sonnet.

### 6. browser: the Admin → Modules snapshot will not match its goldens (likely)

- **Lane:** browser (test_snapshots_admin_browser.py; fails again on the re-run).
- **Where:** drishti-console/tests/snapshots/admin-modules/ (4 goldens, approved at 16:14); web/templates/admin/modules.html:33,42,72,87;
  drishti-console/tests/snapshot_pages.py:79 (no mask, default tolerance 0.2 %).
- **Why:** the page is drawn on a real server and prints the node's fingerprint and a content hash per module. Module content changed
  after the goldens (I14 at 17:03: banking, trading, market-data; I8c1 at 17:34: economy), and a module with Java is stamped with the
  kernel's version and build time (tools/modulepack.py:243-268), so its hash differs on every build. A golden can never be stable here.
- **Fix:** mask the fingerprint tile's value, the matrix cells and the content-hash values (data-snapshot-mask in the template, or
  a mask list for admin-modules and admin-cluster-modules in snapshot_pages.py); re-approve the goldens once.
- **Grade:** Sonnet.

### 7. Release prep: the version is still 2.9.11.i6 (confirmed fact)

- **Where:** .mvn/maven.config; lib/ holds the i6 jar; CHANGELOG says 2.9.11.i7a.
- **Why it matters:** the bump moves the jar's name, the packages' kernel stamps, the footer in every visual golden (i6 re-approved
  them for the same reason), the Admin → Modules header, kernel/drishti-server/src/test/resources/capacity/test-vectors.json:40,336,634
  and the capacity template sheets. AdminModulesRecordTest and test_admin_modules.py use fixed example words and do not move.
- **Fix:** bump first (poms, image tags, asset and console version, instruction lines only), re-approve the goldens in the same
  commit, run the gate, then drill. Never bump between the gate and the lanes, and never drill i6-named artefacts as the release.
- **Grade:** Sonnet.

### 8. browser: the FORM tests flake under load, now that the rules are asked of the server (likely flake)

- **Lane:** browser (test_record_forms_browser.py; the goldens of pages with FORM panels, trades and form).
- **Where:** drishti-console/routes/drafts_routes.py:82 (CHECK_WAIT = 3.0); web/static/js/drishti_record-forms.js:66-80, 476, 818.
- **Why:** the console's proxy gives up after 3 s (504); the page then marks the server away and does not ask again until the next
  keystroke. Under the full drill one cold check over 3 s means the message, hint or computed default never arrives and the test's
  10 s wait runs out. Before i7a these rules ran in the browser at once.
- **Fix:** CHECK_WAIT to about 10 s; the checker asks once more after about 1 s when its newest request failed.
- **Grade:** Sonnet.

### 9. other: test_modules_beside_jar.py starts four real servers in the wrong lane (likely flake)

- **Lane:** other (the planner sees no browser or port word in it; tools/drillkit/drill_plan.py:131-149).
- **Where:** drishti-console/tests/test_modules_beside_jar.py:55-66.
- **Why:** start server with the launcher's default heap, at lower priority, outside the browser lane's memory budget, beside both
  Java lanes: its 400 s wait can run out. It also starts with the repository as its home (cwd is the repository, DRISHTI variables
  stripped): if a first start still writes anything under config/ (speculative), config_unchanged fails the drill.
- **Fix:** put it in the browser lane (the file name's _browser suffix, or the planner's rule), a 768m heap, and a scratch home as
  wb_live makes.
- **Grade:** Sonnet.

### 10. java21: longer and heavier than i6 (likely, not a failure by itself)

- About 76 cold JVM and server starts that used to share cached contexts, plus PackagedVarGoldenTest's lake build and a server of
  1.5 GB: expect 15 to 25 minutes more and about 1.5 GB more at peak beside the browser lane.
- **Fix:** none in code. With less than 40 GB free at the start, run with three test forks through DRILL_MVN_ARGS. Stop the owner's
  stack and list orphan test JVMs first (the standing rule).

### 11. java21, java25: the new cluster tests under load (flake, low to medium)

- OperationsClusterTest (:131 30 s, :137 15 s, :208 20 s) and ModuleDigestClusterTest (:58 30 s, :110 and :154 20 s) wait as
  PlacementClusterTest does; surefire's one re-run covers a single miss. Ports are in order: every cluster test takes
  DRISHTI_NET_IT_PORTS under one file lock, and no fixed 18783 or 18753 is left in the tests.
- **Fix if one misses:** the 15 s placement wait to 30 s; no sleeps. **Grade:** Sonnet.

### 12. tools-a: two conformance cases skip in silence (confirmed, no failure)

- tools/test_content_modules.py:86 looks for required packages under packaging/dist/target/classes/drishti/modules, the old place;
  capital-markets and economy (both need market-data) reach pytest.skip. **Fix:** read modules/ (the gate's), else
  packaging/dist/target/dmod. **Grade:** Sonnet.

### 13. java21: one moved test still names a relative modules folder (speculative, low)

- testing/drishti-modules-it/…/server/console/ConsoleAnswersServerTest.java:44 keeps drishti.modules.home=../../modules; the guard's
  pattern (OnPackagesOnlyTest) misses it. In the copy that folder holds the gate's packages, stamped for another build than the
  copy's. **Fix:** delete the property; widen the guard. **Grade:** Sonnet.

### 14. Freeze rule: an edit to a module's content sources mid-drill stops every later pytest session (speculative)

- The session check (R2, both conftests) exits 3 when a module folder or a content source of a module changed since the gate packed
  it: modules/**, config/lenses/**, and the guides copied into modules (VAR_GUIDE, EXPOSURE_GUIDE, FRTB_SA_GUIDE, SIMM_GUIDE,
  WORLD_DATA_GUIDE, bi/GROUPINGS_AND_ANALYTICS). Nothing in the drill writes them. **Fix:** none in code; no edits, docs included,
  in the drilled worktree while the drill runs.

### 15. Stale words that no check fails on (confirmed; accuracy, in the release's docs pass)

- tools/settings_schemas.yaml:478-479 (DRISHTI_KERNEL_JAR twice, default the deleted packaging/drishti-kernel-app) and
  CONFIGURATION.md:6618; README.md:70 (the thin jar); HOW_IT_FITS.md:306,339,358 with docs/how-it-fits/files-map.svg and the PDF
  (installed/active.yaml, "installs at the first start"); DEVELOPER_GUIDE.md:214 (active.yaml);
  docs/architecture/kernel-and-modules/examples (the deleted Maven plugin, active.yaml); the trading and market-data service poms'
  descriptions (KernelCalls); ModuleManifests.java:37 (a link to the deleted ModulePackages);
  ConfigReadersGuardTest.java:47,51 (ModuleDirs). **Grade:** Sonnet.

## Checked and in order

- drill.sh for i7a: the gate builds the packages after the jar and before the ready mark; the copies get lib/ and modules/ from the
  gate; clean and packaging/dist write only under target/; the anywhere test copies the gate's packages beside the unpacked release
  and its scratch is removed; the stale state moved aside now includes modules and cache.
- The stale refusal: content hashes, not times; build output and module jars left out; the gate's packages are fresh for the lanes;
  the Java copies pack their own target/modules-home for their own jar and never read lib/ or modules/.
- Reactor order (drishti-app, dist, drishti-it, drishti-modules-it); every entry of tools/drill-java25.txt exists; Python for the
  Java lanes is the system one the gate's packers used.
- In-JVM tests' data folders are under target/; no test of the diff reads the wall clock against the lake (business today pinned).
- Test servers of the browser lane: scratch home and data, the lib/ jar or the class-data archive, modules from modules/; the
  class-data archive still valid; no console code or test names the deleted deploy page, install endpoints, module_removed.html,
  the form-expression script or the thin jar; admin-deploy is out of the sitemap and the snapshot list; no collection error seen.
- The Admin → Modules page's fields are all in the recorded answer; the recording is newer than the classes that make it.
- Package sizes are small (8 MB together): a test server's unpack adds seconds, not minutes.

## Verdict

**Not safe to drill as it is.** Findings 1, 2 and 3 fail the drill for certain or nearly so (1 only after 40 minutes, when every
lane has run); 5 and 6 are likely to; 4 is an unknown the drill should not be the first to measure.

**Safe to drill once these are done, in this order:** fix 1, 2, 3, 5, 6 (all Sonnet, one fixer, about two hours); 8 and 9 in the same
round to save a re-run; run testing/drishti-modules-it whole once and fix what it shows (4); decide the version bump (7) and
re-approve the goldens with it; then the gate, the tools lanes alone (they prove 1, 2, 3 in about 20 minutes), one browser file per
family with collect-only over all, a look for data/cache, and then the one full drill with the owner's stack stopped.
