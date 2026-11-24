<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7g release gate review, part 1: the first run on Windows

Release gate of Drishti 2.9.11.i7g, part 1: branch `release/i7g`, commit `ba0e1ac66`, against `origin/main` (tag `v2.9.11.i_7f`):
317 files, +3768 / −1585. Run on 2026-11-23, 20:42 to 20:58, time-boxed. No sub-agent; read-only (`git show`, `git diff`); every claim
below was read in the code or run in a scratch copy. No product code was changed. Lane C (`i7g/accept`, not yet on origin at the time
of this review) is not reviewed here: section 4 is the checklist its merge must pass.

**What was gated.** Only the owner's six rules on the Windows-facing paths: (1) no virtual environment made or named; (2) launchers by
project, on the project interpreter; (3) `start` / `stop` / `status` / `run_drishti_web.py` make and install nothing and follow no old
root; (4) every failure is one `[FAIL] ... Fix: <command>` line, `py -3.13` on Windows, no traceback; (5) no package index, no proxy
for Drishti's own calls; (6) the documents say the same.

**Not run:** anything on Windows; the Java build; the drill; `drishti.py start|stop|status` themselves (not run so that nothing could
come near the owner's stack: those paths were read, the console's entry functions were called directly in a scratch copy).

## Verdict for part 1: FIX (five one-line fixes, then PASS)

Rules 1 and 5 pass. Rule 2 passes but for one folder name. Rules 3, 4 and 6 each have a small hole, all on the path a Windows user
takes first: the PyCharm launcher **Drishti: run console** (it runs `run_drishti_web.py` directly, so the tools' guards do not apply).

| # | Fix (one line each) | Rule | Where |
|---|---|---|---|
| F1 | **A traceback when `dev.ini` is not there yet.** The launcher (and the VS Code task) passes `--settings ~/drishti-envs/dev.ini`; before `setup` that file does not exist, `foreign_settings` calls `settings_ini.read_all`, and `SettingsError` leaves `main()` as a Python traceback (reproduced: `SettingsError: drishti.settings.file names .../drishti-envs/dev.ini, which is not a file`). Catch it in `foreign_settings` (or around `main()`): `raise SystemExit(f"[FAIL] the settings file: {e}. Fix: {setup_line()}")`. The two `raise SystemExit(f"--settings: {e}")` of `core/config.py` take the same shape. One test: the launcher's arguments with no file. | 4 | `drishti-console/run_drishti_web.py:106-115, 168-176`; `drishti-console/core/config.py:144, 162` |
| F2 | **The console started directly follows an old `DRISHTI_*` folder.** The trust rule lives only in `tools/drishti.py` `main()`. `run_drishti_web.py` has none: with no `dev.ini` (or another checkout's, which it rightly drops) an old `DRISHTI_DATA_DIR` is taken as the data folder (`core/app.py:65`, `core/tmpdir.py:49`, `run_drishti_web.py:85`), and an old `DRISHTI_TMP_DIR` is followed even with a good `dev.ini` (`core/tmpdir.py:42`); `tmpdir.start` then makes the folder there (`mkdir(parents=True)`, line 103) and `remove_stale` cleans in it. Reproduced in a scratch copy: bare run, `DRISHTI_DATA_DIR` and `DRISHTI_TMP_DIR` naming an old clone: the temp base resolved to the old clone, the variable kept. Fix: in `run_drishti_web.main()`, after the settings file is accepted or dropped and unless `drishti.py start` started it (it passes its own resolved roots), drop every variable of `settingsfile.ROOT_VARS` that names a folder outside `HERE.parent` and is not the folder the accepted settings file names, one `[WARN]` line each (the tools' rule, the same words). One test beside row 12 of `tools/test_first_run.py`. | 3 | `drishti-console/run_drishti_web.py:168-178` |
| F3 | **Failures that are not `[FAIL] ... Fix:`.** (a) `tools/drishti.py:1270-1275` prints `drishti: <text>` for `SettingsError`, `JavaError`, `HomeError` and `CliError`: a Windows machine with no JDK reads `drishti: no Java found (...)`: print `[FAIL] ` as `launchcli._run` does (line 126), and give `no Java found` / `Java too old` a `Fix:` (`javacmd.py:174, 178`). (b) These first-run failures carry the command inside the sentence, not after `Fix:`: no server jar and the wrong jar version (`javacmd.py:269, 275`), modules not current (`serverstart.py:170`); put them through `procs.fail(what, why, procs.command("build", "all"))`. (c) Port in use (`serverstart.py:245`, `consolestart.py:96`) and "already uses the data folder" (`serverstart.py:243`) say the fix in words: `Fix: <procs.command("stop", "server")>` (or `--port`). The option mistakes (`--node`, `--seed` ...) may stay in words. | 4 | as listed |
| F4 | **`Drishti: test` is in the wrong folder.** The owner's folders are Drishti-java 1 Build / 2 Run / 3 Test; the table's own comment (line 20), `launchers.GROUPS` and IDE_GUIDE line 24-25 say so, but the row says `group: "Drishti-java 1 Build"`, so no launcher is in **3 Test**. Change `tools/launch/launchers.yaml:41` to `"Drishti-java 3 Test"`, run `python tools/drishti.py launchers` (it rewrites `.idea/runConfigurations/Drishti test.xml` and the tables of IDE_GUIDE section 4.1). | 2 | `tools/launch/launchers.yaml:41` |
| F5 | **Four document lines.** `bin/README.md:26`: "`setup [--check]` makes the console's virtual environment ..." (say: checks the Python you run Drishti with, the settings and the data folder). `docs/guides/IDE_GUIDE.md:399` and `:498`: "a `.run.xml`" (there is none now: "a file of `.idea/runConfigurations/`"). `docs/guides/IDE_GUIDE.md:766`: the task `Drishti: test Python` is in no table and not in `drishti.code-workspace`: delete the row. `docs/QUICKSTART_DEVELOPERS.md:114`: "On Windows Drishti never makes a virtual environment" reads as if it did elsewhere: "On every system". `.gitignore:88` still speaks of `run-configs/` (a comment). | 6 | as listed |

F1 and F2 are the two that matter (a traceback and a folder made in an old clone, both on the first click in PyCharm); F3 to F5 are
wording and shape. None needs a design decision.

## 1. What passed

**Rule 1, no virtual environment.** `git grep` of the dot-venv name, `-m venv`, `uv venv`, `virtualenv`, `EnvBuilder`, `make_venv`
over the whole tree at `ba0e1ac66`: only ignore patterns are left (`.gitignore`, `.dockerignore`, the scanners' skip sets, each line
marked as an ignore pattern), plus two sentences of AI_GUIDE about SAJHA's own scratch copy. `--python` / `--reinstall` on `start` are
refused with the setup line (`consolestart.py:56`). The guard is `tools/test_no_venv.py`.

**Rule 2, launchers.** Tracked launcher files: `.idea/runConfigurations/` (6: Maven and JarApplication only) and
`drishti-console/.idea/runConfigurations/` (12: Python only); no `*.run.xml`, no `run-configs/` anywhere; `.gitignore` commits only
`runConfigurations` of each `.idea`. Every Python launcher has `SDK_HOME=""`, `IS_MODULE_SDK="false"`, module `drishti-console`: no
interpreter path, so PyCharm falls back to the interpreter of the project; folders Drishti-python 1 Setup / 2 Data / 3 Run / 4 Check.
To confirm on the owner's PyCharm (cannot be checked here): the Run dialog of **Drishti: run console** shows the project's
interpreter, and the module is named `drishti-console` (PyCharm names it after the folder).

**Rule 3, the tools' side.** `start server|console|all`: the roots come from `javacmd.launch_roots` after `drishti.py main()` has
dropped every `DRISHTI_*` folder variable that names a folder outside the checkout (said once) and has refused another checkout's
`~/drishti-envs/dev.ini`; `require_set_up` refuses a data folder with no `run/` before anything is made (`serverstart.py:188-192`,
called at `:214` and `consolestart.py:76`); the only `mkdir` calls after that are under the data folder; no pip, no install anywhere
in `serverstart.py`, `consolestart.py`, `launchcli.py`, `procs.py`. `status` and `stop` write nothing but the removal of a stale pid
file. `run_drishti_web.py`: no install, the library check is first (`check_libraries`, one `[FAIL] ... Fix:` line), another
checkout's `dev.ini` is warned about and dropped (bare run and `--settings`). Its two holes are F1 and F2.

One thing accepted, to know about: `use_tmp_dir()` runs before `main()`, so an old `DRISHTI_TMP_DIR` naming a folder outside the
checkout that still exists is used as this command's temp folder without a word (a site may set it on purpose; a missing one is
warned about and ignored, and never made). The line to say it once belongs with F2.

**Rule 4, the shape.** `procs.fail` is the one shape and `procs.command` / `run_drishti_web.setup_line` give `py -3.13 tools\drishti.py ...`
on Windows (the running version's digits), the tool's path in full when not in the install folder, quotes around a path with a space.
Not set up, the console's Python missing or lacking libraries, no demo lake, PyYAML missing for a cluster file, a missing library in
any command (the `ImportError` branch) all print it. Anything unexpected is one `[FAIL]` line with the traceback in a log file
(`drishti.py:1297-1310`). The rest is F1 and F3.

**Rule 5, no network.** Every call Drishti makes to itself goes through an opener with no proxy: `procs.no_proxy_opener` (health
checks of start, stop, status), `clusterctl.get_json`, `nodecmd`, `doctor.py:127, 234`, the command line's client for a loopback
`--server` (`drishti.py:296-297`), and the console's clients to the server (`trust_env=False` in `core/backend.py:125`,
`backend_watch.py:62`, `cluster.py:279, 308`). Start, stop, status and the console reach no package index. pip is in `setup` only
(`--isolated --no-index --find-links` from the pack; a package index only with `--index`): lane C removes it.
To know about, not a fix: the command line's client uses the system proxy for a `--server` that is not loopback (a LAN address);
the default is `http://localhost:18480`, so the default path is clean.

**Rule 6, the documents.** IDE_GUIDE, QUICKSTART_DEVELOPERS, DEPLOYMENT_AND_QA_GUIDE, CLI_REFERENCE and README say "the Python you
run Drishti with ... Drishti makes none", `py -3.13` on every Windows command, `.idea/runConfigurations/` per project, "When PyCharm
offers to create a virtual environment, Cancel". No `uv run`, no `Scripts\activate`, no `make_venv`, no `--reinstall`. The lines
left over are F5. The sentences that say setup installs from the wheel pack (IDE_GUIDE 9 places, QUICKSTART 3, DEPLOYMENT 14,
CLI_REFERENCE 13, README 2) are true at `ba0e1ac66` and become false with lane C: section 4, C8.

## 2. The 28 tools test failures: not from i7g

The three groups (`tools/lake`, `tools/drillkit`, `tools/test_config_readers_guard.py`; 256 tests) were run on clean copies
(`git archive`) of `origin/main` and of `ba0e1ac66` under `~/drishti-bench-scratch/fable-i7g-gate`, with the Python of the owner's
console (read only), one run at a time.

| Run | `origin/main` (i7f) | `ba0e1ac66` (i7g) |
|---|---|---|
| Clean copy, **no server jar in `lib/`** | 28 failed, 173 passed, 27 skipped | 28 failed, 173 passed, 27 skipped: **the same 28 tests** |
| The same copy **with the built jar** in `lib/` | stopped at 96 of 256 to keep to the time box: 0 failed until then (the tests that failed without the jar passed) | **0 failed**, 218 passed, 10 skipped |

- The 28 are all in `tools/lake` (`test_maintain.py` 9, `test_build.py` 4, `test_stores.py` 4, `test_demo.py` 3,
  `test_maintain_admin.py` 3, `test_names.py` 3, `test_feedroute.py` 2). The cause is the copy, not the code: 16 are
  `javacmd.JarError: no server jar in .../lib`, the others follow from it (`Delta tables are read by Delta Kernel ... build the
  server's exec jar`, a command that failed for the same reason). They fail identically on `origin/main`.
- With the jar of the i7g worktree (`drishti-server-2.9.11.i7g-exec.jar`) the i7g copy is green in all three groups. The `origin/main`
  copy used the same jar under the i7f name (main's Java sources differ from i7g in `DataFeeds.java` only): a stand-in, so that
  column is a comparison, not a release result.
- **No failure in `tools/drillkit` or `test_config_readers_guard.py` was seen in any run, on either commit.** If the lane saw failures
  there, they come from the state of its worktree (files not in git, or no jar), not from the commit.

**i7g introduced none of them.** A clean checkout of `ba0e1ac66` with the jar built passes the three groups. What the lane should
note: these suites need `build jar` first, and say so in their own failure text.

The scratch copies and the one temp folder the stopped run left were removed. Three `drishti-pytest-*` folders made in `/tmp`
between 20:42 and 20:44 (`y686vyr8`, 77 MB; `yk0b4vav`, `t6aybymh`, empty) could not be shown to be this review's and were left.

## 3. Notes

- `Drishti: setup (package index)` (`launchers.yaml:100-105`, `setup --index`) is a launcher whose only purpose is pip on a package
  index: it goes with lane C (C2).
- The development secrets are spelled in the committed launcher files (`drishti-dev-only-...-change-me`), by design
  (`tools/launch/devsecrets.py`, warned about at start); not a finding.

## 4. The checklist lane C's merge must pass

Lane C changes setup to check-only, takes pip out of the compute environments, adds wheel-pack discovery, finishes
`doctor --acceptance`, fixes `contracts.py` `_yaml`, and adds the matrix tests. Before `i7g/accept` is merged into `release/i7g`:

| # | Must hold | How to check |
|---|---|---|
| C1 | **Setup never runs pip.** No `pip` process is started by `setup`, `setup libs`, `setup --check` on any system; missing libraries are one `[FAIL] libraries: ... Fix: <the owner's pip command>` line, the command printed, never run, with `py -3.13 -m pip ...` on Windows. | `git grep -n -E '"-m", ?"pip"|-m pip|pip install' -- tools/launch tools/drishti.py compute drishti-console/core` shows only printed text; a test that fails if `subprocess` is called with pip |
| C2 | **No option or launcher that installs.** `--index`, `--wheels`, `--isolated` handling and the launcher `Drishti: setup (package index)` are gone, or only shape the printed command; `launchers --check` clean; `Drishti-python 1 Setup` holds `setup` and `check setup` only. | `python tools/drishti.py launchers --check`; `tools/test_run_configs.py`, `tools/test_launchers.py` |
| C3 | **Compute environments: no pip at all**, and still no virtual environment (`tools/test_no_venv.py` green, the server's Java side included). What an environment lacks is said, with the command, never installed. | `git grep -n -i pip -- compute/drishti-compute/src/main` (`env/Environments.java:158` today) |
| C4 | **Wheel-pack discovery makes and writes nothing**: it only finds the pack (`%USERPROFILE%\drishti-wheels`, Downloads, the platform folder, the zip itself) and puts its folder into the printed command; nothing is unzipped outside the data folder or the checkout. | read; a test with a pack one folder deep and a zip |
| C5 | **`doctor --acceptance`** makes nothing, installs nothing, calls only loopback through the no-proxy opener, and ends with one verdict line; each failed row is `[FAIL] ... Fix: <command>`. | read; run it in a scratch copy with no server |
| C6 | **F1 to F5 of this review are in** (or already in `release/i7g` before the merge), each with its test: the launcher's `--settings` with no file; a direct console run with old `DRISHTI_*` variables; `[FAIL]` for Java, jar, modules, port; `Drishti: test` in `Drishti-java 3 Test`; the four document lines. | the tests named in F1, F2; `launchers --check` |
| C7 | **The matrix tests cover the first click**: Windows-shaped (`ntpath`) rows for no `dev.ini`, another checkout's `dev.ini`, old `setx` variables, a Python with no libraries, no JDK, no jar, no demo lake, the port taken: each row asserts exactly one `[FAIL]` line with `Fix: py -3.13 ...`, no `Traceback`, and nothing made outside the checkout or data folder. | the new matrix test file |
| C8 | **The documents follow in the same merge**: every sentence that says setup installs (IDE_GUIDE, QUICKSTART_DEVELOPERS, DEPLOYMENT_AND_QA_GUIDE, CLI_REFERENCE sections of `setup`, README, `bin/README.md`, the launcher texts in `launchers.yaml`, `setup --help`) says setup checks and prints the command; CONFIGURATION.md regenerated; PDFs regenerated; no `--index` / `--wheels` left where the option is gone. | `git grep -n -i -E 'installs what is missing|setup installs|--index|--wheels' -- docs README.md bin tools/launch` |
| C9 | **`contracts.py` `_yaml`**: the `tools/feeds` tests that fail on `origin/main` too pass; the three groups of section 2 show no new failure against `ba0e1ac66`. | the same command as section 2 |
| C10 | **Guards still green**: `tools/test_no_venv.py`, `test_no_hidden_names.py`, `test_first_run.py`, `test_setup_split.py`, `test_setup_collisions.py`, `test_launch.py`, `test_drishti_cli.py`, `test_settingsfile.py`. | pytest, targeted |

Part 2 of this gate is a read of the lane C merge against C1 to C10, nothing more.

## 5. Part 2 checklist (2026-11-23 22:45 EST)

Read of `origin/release/i7g` at `4cabf93d9` against `ba0e1ac66` (8 commits, 61 files), by `git show` / `git diff` / `git grep` only:
nothing was built, started or run, so the rows that need a test run (C9, C10) are read, not proven.

### Verdict for part 2: FIX (two items, both small), then TAG

| # | Blocking item | The one-line fix |
|---|---|---|
| B1 | **Setup writes outside the checkout and the data folder.** `envsetup.setup` (line 161-163) passes a pack zip that discovery found (in `~/drishti-wheels`, the platform folder or Downloads, or named by `--wheels` / `DRISHTI_WHEELS`) to `pylibs.wheel_folder`, and `pylibs.unzip_pack` (line 358-385) unzips it into `~/drishti-wheels/<pack name>/` "without a question" (also `rmtree` of a part folder and of an incomplete target there). C4 says discovery makes and writes nothing; `tools/test_wheel_discovery.py` row 22 asserts the opposite. `setup --check`, `start console` and the bare console do not unzip. | In `wheel_folder`, a `.zip` is no longer unzipped: one `[FAIL] wheel pack: <zip> is still zipped. Fix: unzip it into ~/drishti-wheels, then run setup again` (and row 22 asserts that line and an unchanged home). Or the owner says in words that the unzip into `~/drishti-wheels` is wanted: then C4 is changed, not the code. |
| B2 | **Six texts still say setup installs** (C8), three of them on the first-run failure path: `tools/drishti.py:100` ("it installs the libraries from the offline wheel pack", the PyYAML-missing line); `tools/doctor.py:92` ("installs what is missing into this Python"); `tools/launch/launchcli.py:187` ("it installs the libraries ..."); `tools/build_demo_lakes.py:305` ("setup installs them into this one"); `tools/settings_schemas.yaml:477` (`DRISHTI_WHEELS`: "setup and setup libs install the missing libraries"), copied into `docs/admin/CONFIGURATION.md:6767`; `tools/elements-demo/README.md:38`. | Each: "setup checks them and prints the pip command; you run it". Regenerate CONFIGURATION.md. |

### C1 to C10

| # | Result | What was read |
|---|---|---|
| C1 | **yes** | No process with pip anywhere: the only `pip install` left in `tools/launch`, `tools/drishti.py`, `tools/doctor.py`, `compute/.../src/main`, `drishti-console` is printed text (`pylibs.py:500`, `run_drishti_web.py:63`, `Environments.java:229`). `pylibs` starts the chosen Python three times, each `-c` with a read-only probe (lines 164, 441, 468). `pylibs.install`, `pip_extra`, `explain_pip_failure` and the bootstrap re-exec are gone. The printed command is two pip runs from `drishti-console` (`-r requirements.txt`, then `-r ../config/compute/locks/python.lock`), offline `--no-index --find-links <pack>` first, the package index second (`pylibs.install_commands`). Guard: `tools/test_system_python_untouched.py::test_no_python_code_starts_pip`. |
| C2 | **yes** | `--index` and `--isolated` are gone from `tools/launch` and `tools/drishti.py`; `Drishti setup (package index).xml` is deleted and its 30 lines are out of `drishti.code-workspace`; `launchers.yaml` holds `Drishti: setup` and `Drishti: check setup` only in `Drishti-python 1 Setup`. `--wheels` stays and only names the pack in the printed command (but see B1 for a zip). `launchers --check` not run. |
| C3 | **yes** | `Environments.java`: no `pip --target`; a build checks the interpreter against the lock pins (`missing`), `verifyLibraries` checks again before a worker; what is missing is named with the command. The one `ProcessBuilder` (line 272) runs the interpreter's probe. `EnvironmentTest.noPipIsEverStartedByTheEnvironments`, `aMissingLibraryFailsTheCheckNamesThePipCommandAndInstallsNothing`, `ComputeWritesApiTest` (4cabf93d9). Left over, not blocking: `ComputeSettings.java:88` comment "a temporary folder for pip during a build", and `PIP_CONFIG_FILE` in the probe's environment. |
| C4 | **NO** | `find_pack` itself only reads. Setup then unzips a found zip into `~/drishti-wheels`: B1. |
| C5 | **yes (read)** | `doctor.acceptance`: the no-proxy opener (`ProxyHandler({})`), GET/POST to the console and server URL only, no file written, no process started; `render_acceptance` prints one PASS line or one `[FAIL] ... Fix:` per problem. 13 tests in `tools/test_doctor_acceptance.py` (the E2E one behind `DRISHTI_FIRSTRUN_E2E=1`). The console URL is the caller's: loopback by default, not enforced. |
| C6 | **yes** | `e0cd7cd39` is an ancestor of the release branch: F1/F3 `tools/drishti.py`, `javacmd.py`, `serverstart.py` with `test_first_run.py` (+26 lines; line 106 asserts one `[FAIL]`, no `Traceback`, no `drishti: `); F2 `run_drishti_web.py`, `core/settings_ini.py`, `core/tmpdir.py` with `test_bare_console_run.py`, `test_tmpdir.py`; F4 `launchers.yaml`, `Drishti test.xml` with `test_launchers.py`; F5 `bin/README.md`, IDE_GUIDE, QUICKSTART_DEVELOPERS. |
| C7 | **partly, not blocking** | `test_matrix_setup` (22), `_start` (10), `_console` (5), `_launchers` (3) with the three oracles of `matrix_oracles.py`. The output oracle asserts no traceback and that EVERY `[FAIL]` line carries `Fix:`; "exactly one `[FAIL]`" is asserted only in `test_first_run.py:106`. There are no Windows-shaped (`ntpath`) rows: Windows wording is covered only by `test_setup_split.py` (`py -3.13`) and `test_wheel_discovery.py` (`machine(windows=True)`). Two non-strict xfails stay: row 37 (Python 3.6 SyntaxError before the version check) and row 53 (console first, then server). Carry to the Windows QA pass. |
| C8 | **NO (small)** | The guides say it right ("you install, setup checks": README, QUICKSTART_DEVELOPERS, DEPLOYMENT_AND_QA_GUIDE, PRODUCTION_DEPLOYMENT, CLI_REFERENCE, OPERATIONS_GUIDE, IDE_GUIDE, ARCHITECTURE, tutorials 01 and 21; three PDFs regenerated; no `--index` left but `python_lock.py --index`, another tool). The tools' own messages and CONFIGURATION.md do not: B2. |
| C9 | **yes (read)** | `contracts.py`: the module loader is `_yaml_mod()`, the file reader keeps `_yaml(path)`; all five callers changed. `tools/feeds` tests not run here. |
| C10 | **not run** | Read-only gate. `test_no_venv.py`, `test_first_run.py`, `test_setup_split.py`, `test_setup_collisions.py`, `test_launch.py`, `test_launchers.py`, `test_run_configs.py` were changed with the code; green is the lane's word and the drill's proof. |

### Risk, VaR, ES, BI and lake code: untouched

Product files changed in the range (everything else is `tools/`, docs, launchers, `.gitignore`):
`compute/drishti-compute/.../ComputeService.java` (3 lines), `compute/drishti-compute/.../env/Environments.java`,
`drishti-console/core/config.py`, `core/settings_ini.py`, `core/tmpdir.py`, `drishti-console/run_drishti_web.py`; tests:
`EnvironmentTest.java`, `kernel/drishti-server/.../ComputeWritesApiTest.java`, `drishti-console/tests/test_bare_console_run.py`,
`test_tmpdir.py`. No file of the risk, VaR, ES, BI, pivot, lake or engine code is in the diff (the only path match is the document
`docs/guides/bi/NOTEBOOK_AND_PYTHON.md`).

---

## i7h checklist (2026-11-24 02:52 EST)

Read-only: `git diff` / `git show` only, nothing built or run. Drishti `origin/release/i7h` (`833ec3fc4`, 2 commits, 29 files, +260 −42)
against tag `v2.9.11.i_7g`; Drishti Tools `origin/release/1.1.1` (`d1305f2`, 16 files, +28 −13) against tag `v1.1.0`.

### Verdict: TAG (both)

No blocking item. Four notes, none blocking, at the end.

| # | Result | What was read |
|---|---|---|
| 1 | **yes** | `CapacityController`: the planner and the workbook are `Supplier`s made by `lazy(...)` (double-checked, `volatile`, the value kept; a failure is not kept, so the next call tries again). A `RuntimeException` of the build becomes `DrishtiException(MODULE_PART_UNAVAILABLE)` = `DRS-1032` / 503, "the capacity template is missing <part>"; both `get()` calls are inside `plan()` and the workbook endpoint, before the streaming body, so nothing is read at construction and the server starts. `/admin/capacity/live` reads neither. `CapacityWorkbook.builtIn` reads each part through `file(meta, part)`: `template.json` `"files": {"_rels/.rels": "_rels/root.rels.xml"}`, the part's name in the workbook stays `_rels/.rels` (rename is 100% similarity; `sizing_planner.py` writes the same map through `SOURCE_NAMES`; `.rels` is out of `test_no_hidden_names` ALLOWED). No other reader of the old resource name in the tree. `CapacityLazyTemplateTest` (3 tests) covers lazy, once-only and the zip entry names. |
| 2 | **yes** | `serverstart.require_settings(a)` is the first check of `start`: no `--data-dir` and no settings file of this process (`javacmd.settings()[0]`: `--settings`, else `~/drishti-envs/dev.ini` of this checkout) raises one `LaunchError` built by `procs.fail(...)` with `procs.setup_line()`. `start cluster` is not affected: every node and the console are started with `--data-dir` (`clusterctl.py` 419, 463). `test_matrix_start` row 04 asserts the one `[FAIL]`, no `--data-dir` in the Fix, nothing written. |
| 3 | **yes** | `build_demo_lakes.closing`: the Next line is `procs.command('start', 'all', '--demo')`; the hard-coded Linux and Windows spellings are gone. No file name is shared between `tools/launch` and `tools/lake`, so the added `sys.path` entry shadows nothing. Message text only, no lake logic. |
| 4 | **yes** | `drishti.py` hands `--server` (else `DRISHTI_SERVER`, first of a list) to `doctor.acceptance`. The development administrator is used only when one of three holds: the demo profile is named, `DRISHTI_SEED_PASSWORD` is given, or `server_says_demo` (GET `/public/about`, 200, JSON, `profile` equal to `demo`); any other answer (down, not JSON, no field, another value) is the old `[FAIL] demo profile` and no POST is sent (the new test asserts no POST). `PublicAboutController` adds `profile` only when `drishti.profile` is set, and the only file that sets it is `config/profiles/demo.ini`; a server without the demo profile has no field, so it never gets the sign-in by this path. The field carries one word: no path, user, password or secret. `CONFIGURATION.md` has the key (2,188 settings). |
| 5 | **yes** | Both `mvnw` (Drishti `data/tmp/maven`, Tools `build-tmp/maven`): a temporary folder whose path holds a space is replaced by `${TMPDIR:-/tmp}/drishti[-tools]-maven-<uid>` before `mkdir -p` and `MAVEN_OPTS`. Two lines each, same shape. |
| 6 | **yes** | Tools `SettingsFile.read`: a first line starting with U+FEFF loses that one character before `parse`. `SettingsFileTest.aLeadingUtf8ByteOrderMarkIsIgnored` writes the three bytes EF BB BF and reads the key. Everything else in Tools is the version (1.1.0 → 1.1.1) and the CHANGELOG. |
| 7 | **yes** | `envsetup._setup`: the two module reports (they run the jar) are inside `try`; `JavaError` / `JarError` / `LaunchError` puts the message in `problems` only when no problem is there yet (the Java line already printed stays the one line) and the steps go back to `[*problems, *todo]`, so no traceback and no module line without Java. |

### Risk, VaR, ES, BI and lake code: untouched

Product files changed: `PublicAboutController.java`, `CapacityController.java`, `CapacityWorkbook.java`, the capacity template (rename,
`template.json`, the jar name `i7g` → `i7h` in `sheet1.xml`, `sheet2.xml`, `test-vectors.json`, the sizing workbook), `config/profiles/demo.ini`,
`pom.xml` and `.mvn/maven.config` (versions; Tools 1.1.1). Everything else is `tools/` (launch, doctor, setup, messages, tests), `mvnw` and
documents. No file of the risk, VaR, ES, BI, pivot, lake or engine code is in either diff.

**TEST IMPACT**: targeted only: `CapacityLazyTemplateTest` + the capacity tests (vectors carry the new jar name), `tools/test_doctor_acceptance.py`, `test_matrix_start.py`, `test_no_hidden_names.py`, `test_wheel_discovery.py`, the setup matrix, Tools `SettingsFileTest`; no risk, BI or lake lane.

**DOCUMENT IMPACT**: already in the branch (CHANGELOG, RELEASE_NOTES, README version, `CONFIGURATION.md` regenerated, `CLI_REFERENCE.md` for `--server`, the demo rule and `start server`; Tools CHANGELOG); nothing further before the tag.

### Notes (not blocking; carry to I8_CARRY_IN)

| # | Note |
|---|---|
| N1 | `/public/about` needs no sign-in, so `"profile": "demo"` tells anyone who can reach a demo server that it runs the demo profile (whose development administrator is the documented product default). Nothing new is disclosed for a demo; never start a reachable non-demo server with `drishti.profile=demo` in its own settings file. |
| N2 | No server-side test asserts that a server started with the demo profile answers `"profile": "demo"` (the doctor test uses a stand-in server); the drill's acceptance step without `--profile demo` is the proof. |
| N3 | `doctor --acceptance --wait N` on a demo stack that is still starting, with no `--profile demo`: `server_says_demo` is asked once, before the wait, so it answers `[FAIL] demo profile` instead of waiting. The Fix line it prints works. |
| N4 | `mvnw` fallback: a `TMPDIR` that itself holds a space is not covered, and the folder name under a shared `/tmp` is predictable (`drishti-maven-<uid>`); single-user development machines only. |
