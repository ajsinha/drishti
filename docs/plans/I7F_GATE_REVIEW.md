<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i7f release gate review: setup never writes into another folder

Release gate of Drishti 2.9.11.i7f: branch `release/i7f`, commit `9d7e21437`, against `origin/main` (tag `v2.9.11.i_7e`): 26 files,
+539 / −159. Background and root cause: [DRISHTI_ORIG_ANALYSIS](DRISHTI_ORIG_ANALYSIS.md). Run on 2026-11-23, 16:50 to 17:15. No
sub-agent; every claim below was read in the code or run. No product code was changed.

**How it was checked.** The diff and the code it left were read in full (`tools/launch/envsetup.py`, `tools/launch/launchers.py`,
`tools/settingsfile.py`, `tools/javacmd.py`, `tools/drishti.py`, the console's `core/config.py` and `run_drishti_web.py`). The real
command line was run in a scratch copy of the checkout (`git archive` of `tools`, `config`, `lib`, the console's `core`) with a
scratch home folder, an old `dev.ini` and old `DRISHTI_*` variables naming a folder `orig` that did not exist; after every run the
scratch tree was listed for anything new. Tests run in the worktree: `tools/test_setup_collisions.py`, `test_setup_split.py`,
`test_settingsfile.py`, `test_launch.py` (114 passed); `test_launchers.py`, `test_run_configs.py`, `test_build_modules.py`,
`test_deploy_aws.py`, the console's `test_settings.py` (53 passed, 1 skipped); `launchers --check` (clean); the console's docs gate
tests (section 5). **Not run:** the Java build (one string changed in `LaunchRoots.java`), the drill, anything on Windows (Windows
path rules were checked through `ntpath`, as the lane's tests do).

## Verdict: TAG-AFTER-FIXES

The owner's case is fixed for `setup`: with an old `dev.ini` and all five old variables set, `setup --system --check`, `setup drishti`
and `setup` made nothing in the old folder, moved `dev.ini` and the differing `jvm-*.args` into one `replaced-YYYY-MM-DD-HHMM` folder
and wrote fresh ones; a second run the same minute got `-2`, `-3`, `-4`. No path was found where setup overwrites or deletes a user
file. Four things still stand between this and the owner's words ("no manual workaround", "nothing is lost and no confusing
situation arises"), all small:

| # | Fix before the tag (one line each) | Section |
|---|---|---|
| F1 | **Console: the settings file must beat the environment.** `drishti-console/core/config.py` `_shared` (lines 159 to 162): take `drishti.home` and `drishti.data.dir` from the file first, the environment second, as `settingsfile.apply_roots` now does; one test in `drishti-console/tests/test_settings.py`. | 1.1 |
| F2 | **Keep this checkout's `dev.ini` when its data folder is elsewhere.** `envsetup.ini_collision`: when `drishti.home` names this checkout and no `--data-dir` is passed, a `drishti.data.dir` / `drishti.modules.home` / `drishti.lib.path` outside it is not a collision; setup keeps the file, says the folder is outside the checkout, and makes nothing there unless `--data-dir` is passed in this run (line 323: `folder == data` only with an explicit `--data-dir`; the same for the data layout at lines 264 to 268). Tests: the `D:/x` line of `test_windows_shaped_paths`, one new test of "setup --data-dir, then plain setup". Owner to confirm the rule. | 3 |
| F3 | **`launchers --machine` like setup.** `launchers.machine`: a `jvm-*.args` that is there and differs is moved through the same dated-folder helper (move `dated_folder` beside `machine_files`), identical files untouched; print `settingsfile.other_home` for the `dev.ini` it reads. Until then drop "(or launchers --machine)" from setup's todo line (`envsetup.py:314`). | 4 |
| F4 | **`--settings <old dev.ini> setup` must work.** `tools/drishti.py` `main`: for `setup`, do not `apply_roots` and do not hand the four root keys to `javacmd.set_settings` (setup decides the roots itself). One test. | 1.2 |
| F5 | **Docs lines** of section 6 (twelve lines in Drishti, one generated table, two PDFs; three places in the platform index). | 6 |

Should fix, not blocking: S1 to S6 in sections 1, 2 and 5.

## 1. Can anything still be made outside the checkout and the settings folder?

Run with an old `dev.ini` (`drishti.home`, `drishti.data.dir`, `drishti.modules.home`, `drishti.lib.path` under `orig`) and
`DRISHTI_HOME`, `DRISHTI_DATA_DIR`, `DRISHTI_MODULES_HOME`, `DRISHTI_LIB_PATH`, `DRISHTI_TMP_DIR` naming `orig`:

| Command | Made outside? | Notes |
|---|---|---|
| `setup --system --check` | no | writes nothing at all; reports the move and the five `[WARN]` lines |
| `setup drishti`, `setup` | no | `dev.ini` moved, fresh one written, layout made under the checkout's `data`, `data/qa/data` (the QA profile) and the settings folder only |
| `setup libs`, `setup venv` (old `dev.ini`) | no | the old file is left alone (the Drishti part did not run); `drishti.python` would be added to it (settings folder only) |
| `launchers --machine` (old `dev.ini`, no `--settings`) | **yes, through `DRISHTI_TMP_DIR`** | `machine` itself makes nothing now; `drishti.py` `use_tmp_dir` made `orig/tmp` before the command ran (S1). The files written name `orig/data` (F3) |
| `--settings <old dev.ini> launchers --machine`, `status`, `start server` | no | WARNING, then refused by `find_home` (exit 2); see S2 |
| `--settings <old dev.ini> setup` | no | refused, with misleading lines (F4) |
| the console launcher, correct `dev.ini`, old `DRISHTI_HOME` / `DRISHTI_DATA_DIR` | **yes (F1)** | see 1.1 |

Path rules (`settingsfile.inside`, text only, `ntpath` on Windows): case, `/` against `\`, a trailing separator, `..`, `~` and
`${drishti.home}` compare correctly; another drive, a relative value and an unreadable file count as "another folder". A short (8.3)
name, a junction or a mapped drive for the same folder compares as different: the file is then moved and a fresh one written, which
loses nothing. Every doubtful case fails toward "move", never toward "use". Good.

### 1.1 F1: the console still lets the environment win

`drishti-console/core/config.py:159-162` puts the file's `drishti.home` and `drishti.data.dir` into the environment only "where
unset", and reads `DRISHTI_HOME` before the file. Checked by calling `_shared` with a correct settings file and both variables naming
`orig`: both stayed `orig`. The PyCharm launcher **Drishti: run console** starts `run_drishti_web.py --settings dev.ini` directly, so
on a machine with the old guide's `setx DRISHTI_HOME` / `setx DRISHTI_DATA_DIR` (the owner's case exactly) the console makes
`<old folder>\data\tmp\console` and its `console` data folder there, with no warning (`warn_other_home` reads only the file). The
release notes say the settings file beats the environment "in the Python tools"; for the console it does not. (`drishti.py start
console` is fine: `apply_roots` has already put the file's values into the environment the child inherits.) The console's test stacks
set `DRISHTI_DATA_DIR` without a settings file, so the change does not touch them.

### 1.2 F4: `--settings <old dev.ini> setup`

The run-time WARNING says "run python tools/drishti.py setup". Every other command in the guides carries `--settings
~/drishti-envs/dev.ini`; a user who keeps it gets: three `[WARN]` lines about `DRISHTI_*` variables they never set (`apply_roots`
put the old file's values into the environment), then `drishti: drishti.home in the settings file is <old>, but these tools come
from <new> ... or unset drishti.home`, exit 2. Nothing is written, but it is the manual workaround the release removes. The IDE's
three setup launchers pass no settings file (`settings: false`), so only the command line is affected.

### Should fix

- **S1.** `tools/drishti.py` `use_tmp_dir` makes a `DRISHTI_TMP_DIR` that does not exist for every command but setup, and the console
  (`core/tmpdir.py:103`) does the same. One line: use it only when the folder exists, else a WARNING and the default temp folder.
- **S2.** The tools do not "only WARN" for a foreign `drishti.home`: they print the WARNING and `find_home` then refuses most commands
  (`status`, `start server`, `launchers`; `module list` runs). That is unchanged from i7e and the safer behaviour; IDE_GUIDE 2.1 already
  says "refused". Either the owner accepts refusal for the tools (then fix the RELEASE_NOTES line, section 6) or `find_home` is relaxed.
  In both cases the refusal should end "run python tools/drishti.py setup" for the settings-file case, not "unset drishti.home".
- **S3.** A stale `DRISHTI_DATA_DIR` with no `--settings` is still used silently by `start server`, `lake demo` and the rest
  (environment before checkout, as documented). Setup reports it; a run-time WARNING line from `settingsfile.foreign_vars` would close it.

## 2. Is anything ever overwritten or deleted?

- `move_aside` is `shutil.move` into one new folder per run; `dated_folder` never reuses a name (four folders in one minute in the
  scratch run). `dev.ini` is written only after the move; a `jvm-*.args` only when it is not there. `--check` wrote nothing.
- A `dev.ini` naming this checkout (also as `~/...`, with a trailing `/`, `${drishti.home}/data`) with own lines and `server.port` was
  kept byte for byte. A `jvm-<name>.args` setup does not write (`jvm-dev.args`) is left alone.
- **The one case where own lines leave the live file is F2**: the file is moved whole, so the user's port, Java home and
  `drishti.python` lines go to the dated folder with it. Nothing is lost; it is the confusing situation.
- **S4.** `pylibs.ensure_setting` adds `drishti.python=` to a kept `dev.ini` in place (one line, nothing removed, unchanged from i7d).
  The guides now say setup "never overwrites or edits a file": say "adds only `drishti.python`, once".
- **S5.** Not in words: a `dev.ini` or args file held open by another program on Windows (`PermissionError` from `shutil.move`) and two
  setups started in the same second (`dated_folder`: `exists()` then `mkdir`) end in a traceback. Nothing is lost in either; catch
  `OSError` in `move_aside` and say which file to close.

## 3. The judgement call: this checkout's `dev.ini` with a data folder outside it

Today: `setup --data-dir D:\data` writes `drishti.data.dir=D:/data`; the next plain `setup` (the IDE launcher **Drishti: setup**,
which the guide says to run "after a pull that changed requirements.txt") reports `drishti.data.dir=D:/data` as a collision, moves the
whole file with the user's own lines, writes a fresh one naming `<checkout>/data`, moves the args files and makes an empty layout
there. Reproduced in the scratch run. The server then starts on an empty data folder. Nothing is lost, but this is precisely "a
confusing situation", and IDE_GUIDE 2.1 (lines 139 to 141) tells the user to change `drishti.data.dir` in `dev.ini` by hand and "run
setup again".

**Recommendation: keep it.** The mark of the incident was `drishti.home` naming another folder. A file whose `drishti.home` is this
checkout is this checkout's file, and a data folder outside the install is the documented layout (install read only, data elsewhere).
Rule (F2):

1. `drishti.home` names another folder, or the file names no `drishti.home` and a root outside the checkout: move, as now.
2. `drishti.home` names this checkout: keep the file, whatever its other roots; print them with "(outside this checkout, from your
   dev.ini: kept)".
3. Setup still makes nothing outside the checkout unless `--data-dir` is passed in this run: for a kept outside data folder a missing
   layout is a `[todo]` naming `setup --data-dir <that folder>`. So the promise "nothing is made outside the checkout, the settings
   folder and an explicit `--data-dir`" holds word for word, and a hand-edited file (new home, old data folder) cannot recreate the
   incident.
4. `--data-dir` different from the recorded one: move and write fresh, as now (tested, right).

## 4. `launchers --machine` writes without moving: consistent?

No. It overwrites a differing `jvm-*.args` in place (setup moves it), and with an old `dev.ini` it writes files naming the old
folder's data with no WARNING when run without `--settings` (the scratch run: `data folder .../orig/data` in `jvm-demo.args`). The
files are generated ("never edit it"), so the loss is small, but setup's own `--check` todo offers it as the equal of setup, and the
next setup then moves what it wrote. Fix F3. That it makes no folders any more is right and tested.

## 5. Test impact

- **Changed behaviour with a test:** the move and the fresh file; nothing made in the old folder; args moved only when different;
  second move the same minute; `--check` writes nothing; five variables warned and ignored; the resolver order; `--data-dir` recorded
  and kept on repeat; Windows-shaped paths; `DRISHTI_TMP_DIR` for setup; the pom guard; `apply_roots` file-first.
- **Weakened or removed:** none removed. `test_launch.py` no longer covers a `dev.ini` holding only a comment (still kept by the
  code); `test_setup_split.py` and `test_settingsfile.py` were turned to the new behaviour, correctly.
- **Changed behaviour without a test:** the console order (F1, wrong today); `--settings <old> setup` (F4); `drishti.lib.path`
  outside in a real setup run; the restriction of the made folders at `envsetup.py:323`; `launchers --machine` on an existing
  differing file (F3); the `REPLACED` path of setup; the new texts of the three run-time warnings (only "setup" is asserted, in
  `test_settingsfile.py:113`).
- **Old messages:** no test, script or drill suite reads `dev-old.ini`, `points at ... not this one`, `rename` or `[WARN] settings
  file` any more (grep over the tree). `kept as it is` is still printed and still asserted (`test_launch.py`).
- **Order change and the drill:** `apply_roots` now overwrites `DRISHTI_HOME` / `DRISHTI_DATA_DIR` / `DRISHTI_MODULES_HOME` /
  `DRISHTI_LIB_PATH` from the files. Only `config/profiles/qa.ini` names a root (`drishti.data.dir`); a harness that exports
  `DRISHTI_DATA_DIR` **and** runs `drishti.py --profile qa` now gets `<home>/data/qa/data`. None was found (the console and bench
  stacks set the variable with no profile), but it is the one thing to watch in the drill's QA lane.
- **Docs gate:** `launchers --check` clean. The console's docs gate tests (`test_docs_gates`, `test_docs_rot`, `test_docs_settings`,
  `test_docs_placeholders`, `test_settings_catalogue`, `test_tools_guide`, `test_release_from_other_folder`, `test_no_relative_paths`,
  `test_script_paths_guard`, `test_branding_guard`) were started at 17:00; result: 120 passed, 5 failed, all five in `test_settings_catalogue.py` and none from i7f: four need the sibling `drishti-tools` checkout,
  which a lane worktree does not have beside it (they pass in the platform layout), and
  `test_every_environment_variable_is_indexed` names `DRISHTI_WHEELS`, read by `envsetup.py` since i7e and still without a row in
  `tools/settings_schemas.yaml` (already so on `origin/main`): add the row with F5 (**S6**). After F5 run
  `test_settings_catalogue` / `test_docs_settings` again: `tools/settings_schemas.yaml:475` feeds the generated CONFIGURATION.md.
- **SDK:** `drishti-module-parent` no longer reads `DRISHTI_HOME`. `tools/test_sdk_builds_genomics.py` passes `-Ddrishti.home=`
  (fine); a module developer following DEVELOPER_GUIDE ("set `DRISHTI_HOME`") now fails: docs line in section 6.

## 6. Document impact (F5)

Current and right: RELEASE_NOTES and CHANGELOG (i7f), IDE_GUIDE 2.1 / 2.3 / recipe text, DEPLOYMENT 1.5 and 1.6, CLI_REFERENCE
`setup drishti` row, README version, QUICKSTART release name, DEPLOYMENT_AND_QA_GUIDE.pdf (holds `replaced-YYYY`, no `setx
DRISHTI_HOME`). No Help page names `dev.ini` or setup. The only `setx` left are `DRISHTI_JAVA_HOME` and `MAVEN_OPTS` (right).

Lines to change in Drishti:

| File and line | Now | Change to |
|---|---|---|
| `docs/DEPLOYMENT_AND_QA_GUIDE.md:379-380` | "The environment variables: `DRISHTI_HOME`, `DRISHTI_DATA_DIR` and `DRISHTI_JAVA_HOME` at least, set permanently as in 1.5" | contradicts 1.5: the folders come from the settings file; only the JDK variable (a service: its unit) |
| `docs/DEPLOYMENT_AND_QA_GUIDE.md:558` | "never overwrites one that is there" | kept when it names this checkout, else moved to `replaced-<date>` |
| `docs/DEPLOYMENT_AND_QA_GUIDE.md:768` | `--home`: "`DRISHTI_HOME`, `drishti.home` of the settings file, ..." | settings file before `DRISHTI_HOME` |
| `docs/guides/CLI_REFERENCE.md:2706` | "`--home`, else `DRISHTI_HOME`, else `drishti.home` of the settings file" | the new order; add the modules folder's order |
| `README.md:394` | "`--home`, else `DRISHTI_HOME`, else the folder the tools are in" | add the settings file, before `DRISHTI_HOME` |
| `tools/settings_schemas.yaml:475`, then regenerate `docs/admin/CONFIGURATION.md` (row at 6558) | "--home, else DRISHTI_HOME, else drishti.home of --settings" | the new order |
| `docs/QUICKSTART_DEVELOPERS.md:120` | "(it never overwrites one)" | as DEPLOYMENT 558; regenerate `QUICKSTART_DEVELOPERS.pdf` |
| `docs/guides/DEVELOPER_GUIDE.md:240` | "(set `DRISHTI_HOME` to your Drishti folder; no Python)" | `mvn verify -Ddrishti.home=<Drishti folder>` |
| `docs/guides/DEVELOPER_GUIDE.md:271` | "(`-Ddrishti.home=<Drishti folder>`, or the environment variable `DRISHTI_HOME`)" | drop the environment variable |
| `docs/guides/IDE_GUIDE.md:139-141` | "after you change `drishti.data.dir`, run ... `launchers --machine` (or `setup` again)" | true only after F2 and F3; reword with them |
| `docs/guides/IDE_GUIDE.md:150` and the CLI_REFERENCE `setup drishti` row | "setup never overwrites or edits a file" | "... (it adds only `drishti.python`, once)" (S4) |
| `RELEASE_NOTES.md` (i7f, "At run time the server, the console and the tools still only WARN") | the tools WARN and then refuse most commands | say so, or relax (S2); after F1 to F3 add their lines; the lead sentence "names another folder (its `drishti.home`, `drishti.data.dir` or `drishti.modules.home`)" follows F2 |

Also: `tools/packmake.py:146, 165, 313` (the generated `run-server` scripts say "set DRISHTI_HOME") is run-time guidance for a packed
folder with no settings file: leave. After the Markdown changes regenerate DEPLOYMENT_AND_QA_GUIDE.pdf and QUICKSTART_DEVELOPERS.pdf.

Platform index (`drishti-platform` repository):

| File and line | Now | Change to |
|---|---|---|
| `IDE_GUIDE.md:61` | "It never overwrites an existing `dev.ini`." | "A `dev.ini` that names this folder is kept as it is; one from an older copy, and a JVM arguments file that differs, is moved into `%USERPROFILE%\drishti-envs\replaced-<date>` and a fresh one written: nothing to rename or delete." |
| `START_HERE_WINDOWS.md:55` | "a list of lines starting with `[  ok]`" | add: "`[made]` for what it creates; `[move] old settings ... moved to ...\drishti-envs\replaced-<date>` when an older copy's settings were there; `[WARN]` for an old `DRISHTI_...` variable: nothing to do for either"; regenerate `START_HERE_WINDOWS.pdf` |
| `README.md:11, 108-110, 133, 139`, `COMPATIBILITY.md` (new row), `platform.yaml` (new `i7f` entry) | i7e | i7f, at release preparation (`v2.9.11.i_7f`; product tags unchanged) |

Nothing else in the two index guides names the rename flow, `setx`, or `DRISHTI_HOME` / `DRISHTI_DATA_DIR`.
