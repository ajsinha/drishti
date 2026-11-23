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
# Drishti 2.9.11.i7f: release notes

*A point release on i7e: `setup` can no longer write into another folder, and nothing is left for you to rename or delete by hand.*
Everything else is as in i7e.

## What changes for administrators

- **An old settings file is moved, never followed.** When `~/drishti-envs/dev.ini` (Windows `%USERPROFILE%\drishti-envs\dev.ini`) names
  another copy's `drishti.home` (or names none and a folder outside this checkout), `setup` moves it into a dated folder of the settings
  folder, `drishti-envs\replaced-YYYY-MM-DD-HHMM`, writes a fresh one for this checkout and says so in one line. The same for a JVM
  arguments file (`jvm-demo.args`, `jvm-qa.args`, `jvm-samples.args`) that differs from what setup writes now. Nothing is lost, nothing is
  overwritten, and identical files are left alone. A `dev.ini` that names this checkout is kept as it is, with your own lines and a data
  folder you chose elsewhere (setup says so and makes nothing there unless you pass `--data-dir` again; it adds only `drishti.python`,
  once). `setup --check` says what it would move and moves nothing; `--settings <old dev.ini> setup` works too. A file another program
  holds open is a `[FAIL]` line naming it, never written over. The i7e rename-it-yourself step is gone.
- **`launchers --machine` works the same way**: a JVM arguments file that differs is moved into a dated folder first, and an old
  `dev.ini` is named in a WARNING.
- **Setup makes no folder outside this checkout,** the settings folder and a `--data-dir` you pass (it is recorded in the new `dev.ini`).
  The JVM arguments files only name their folders; setup makes them under the data folder it resolved.
- **The settings file beats the environment** in the Python tools and the console, as it already did in the server: explicit option, then the settings
  file, then the environment, then this checkout, for every folder. A `DRISHTI_HOME`, `DRISHTI_DATA_DIR`, `DRISHTI_MODULES_HOME`,
  `DRISHTI_LIB_PATH` or `DRISHTI_TMP_DIR` left by an old `setx` that names another folder is shown by setup as a WARNING (*ignored: your
  settings file wins; you may delete it in Environment Variables*) and never used.
- **Warn, never refuse, at run time.** The server, the console and the tools WARN when a settings file names another folder's
  `drishti.home` (the warning says to run setup) and still run: the server and the console on that folder, the tools on their own
  folder (they never drive another install; `--home` naming one is still refused). The same for a `DRISHTI_HOME` naming another
  folder. A `DRISHTI_TMP_DIR` outside the checkout that does not exist is a WARNING and the default temp folder is used.
- The deployment guide no longer tells you to `setx DRISHTI_HOME` or `DRISHTI_DATA_DIR`: the folders live in the settings file.

## What changes for developers

- The Module SDK parent pom takes `drishti.home` only from `-Ddrishti.home=` (no `${env.DRISHTI_HOME}` default); a guard test fails when
  any `pom.xml` reads `${env.DRISHTI_*}` or `${user.home}`.
- `javacmd.launch_roots` is the one Python resolver, in the Java order; `settingsfile.apply_roots` lets the file win;
  `launchers.machine_files` renders the JVM arguments files without writing or making anything.

## Known limits

Carried from i7d (below): where Program Files is not writable, pip falls back to the user's own folder; setup, PyCharm and the
console see it, but the server's pivot Python workers start Python isolated (`-I`) and do not. Use a Python the user can write to, or have
IT run step 4 as administrator.

# Drishti 2.9.11.i7e: release notes

*A point release on i7d: the IDE launchers are grouped by language, the loader asks for its file, and setup checks an old settings file.*
Everything else is as in i7d.

## What changes for administrators

- **`setup` checks an existing `dev.ini`.** When its `drishti.home` names another folder than this checkout, `setup --check` shows
  `[FAIL] settings file` and `setup` a `[WARN]`, both saying: your settings file `…\dev.ini` points at `<other folder>`, not this one: rename
  it (`ren …\dev.ini dev-old.ini`) and run setup again. The file is never overwritten.
- **The server, the console and every tool print a WARNING** at start when the settings file names a `drishti.home` other than the
  folder they are installed in (an old `dev.ini` from an earlier copy); they still start, using that folder, and the warning says how to
  replace the file.
- **pytest is no longer a runtime requirement** of the console (it stays in `requirements-test.txt`): setup and the offline wheel packs
  install one library less.

## What changes for developers

- **Launchers are grouped by folder.** One `run-configs/` folder is read by both IDEs: every Maven and JAR Application launcher is in the
  folder **Drishti-java** (IntelliJ), every Python one in **Drishti-python** (PyCharm). Each IDE shows the other's folder as not runnable
  (a red cross): that is expected. `tools/launch/launchers.py` and `launchers.yaml` generate this; their tests and docstrings are current.
- **Drishti: run loader (file into the lake)** asks for the file when it runs (`$Prompt$`); it no longer names a file of one person.
- **VS Code workspace**: `${userHome}` for the home folder, `py -3.13` on Windows (per-OS commands), no Linux-only interpreter or JDK path
  (the JDK is `JAVA_HOME`); the loader task asks for the file.
- The guides' examples carry no personal or old-layout paths (the platform layout, `IdeaProjects/drishti-platform/drishti`).
- IDE_GUIDE, CLI_REFERENCE, DEVELOPER_GUIDE and OPERATIONS_GUIDE describe the one `run-configs/` folder.

## Known limits

Carried from i7d (below): where Program Files is not writable, pip falls back to the user's own folder; setup, PyCharm and the
console see it, but the server's pivot Python workers start Python isolated (`-I`) and do not. Use a Python the user can write to, or have
IT run step 4 as administrator.

# Drishti 2.9.11.i7d: release notes

*A point release on i7c: setup no longer needs the package index.* Everything else is as in i7c.

## What changes for administrators

- **Setup installs only what is missing** and never runs pip when every library is already installed (the Lake wheel included, at its pin).
- **When pip fails, setup says why in one sentence** (the index rejected the login, 401/403, naming its host; the index could not be reached
  or its certificate is not trusted; the index lacks `name==ver`), then prints the missing libraries as one copy-ready `py -m pip install ...`
  line. pip's own output is always shown.
- **Setup installs and `--check` verifies both** `drishti-console/requirements.txt` and `config/compute/locks/python.lock` (the compute pool's environment: numpy, pandas, pyarrow, scipy, ...), the Lake wheel first; a missing pin names its file, and two files that disagree on a pin stop setup in words naming both.
- **`--wheels <folder or wheel-pack .zip>`** for `setup`, `setup venv`, `setup libs` and `setup --system` (and `DRISHTI_WHEELS`): installs only
  from that folder (`pip --no-index --find-links`), the Lake wheel first; a wheel the folder lacks is named and nothing is installed.

## What changes for developers

- PyCharm launcher **Drishti: setup (system Python, offline wheels)** (`--wheels $USER_HOME$/drishti-wheels`); VS Code workspace regenerated.
- Spotless uses `lineEndings` UNIX: `spotless:check` accepts the repository's LF files on Windows without git.

## Known limits

Carried from i7c (below), and one more: where Program Files is not writable, pip falls back to the user's own folder; setup, PyCharm and the
console see it, but the server's pivot Python workers start Python isolated (`-I`) and do not. Use a Python the user can write to, or have
IT run step 4 as administrator.

# Drishti 2.9.11.i7c: release notes

*A point release on i7b: the Python set-up is split, and the server's Python work finds the interpreter from one setting.* Everything else is
as in i7b.

## What changes for administrators

- **`tools/drishti.py setup` is split in parts**: `setup drishti` (settings folder, `dev.ini`, data folder, JVM files, Java, jar and module
  checks), `setup venv` (`drishti-console/.venv`: the pinned Lake wheel first, then `requirements.txt`), `setup libs` (the same libraries
  into the current Python, no venv) and `setup --system` (drishti plus libs, for machines that forbid a `.venv`). `setup` alone is drishti
  then venv; `--skip-venv` is `setup drishti`.
- **`--check` verifies the libraries** name by name (version specs and the Lake wheel's pin) and exits 1 naming what is missing.
- **`drishti.python` is written once** into `dev.ini` by setup (never over an existing value). The server uses it for the feeds' Run now, the
  compute pool and the pivot Python functions when their own setting is blank. Precedence (`PythonSetting`): `DRISHTI_PYTHON`, then the work's
  own setting, then `drishti.python`.
  `drishti.py start console` (and `start all`) runs the console on it too, so a machine with no `.venv` starts the console with no
  environment variable to set (`--python` or `--reinstall` still ask for the `.venv`).
- **pip on Windows uses the Windows certificate store** (`--use-feature=truststore` before pip 24.2), so machines behind a TLS-inspecting proxy install.

## What changes for developers

- Two PyCharm launchers, **Drishti: setup** and **Drishti: setup (system Python)**.
- IDE_GUIDE 2.3 (no `.venv` allowed: `setup --system`, the libraries into the system Python, System Interpreter) and 9.5 (from the release
  zip: the platform's IDE_GUIDE.md and the clean-install launcher, then this guide's launchers).

## Known limits

Carried from i7b unchanged (below).

# Drishti 2.9.11.i7b: release notes

**Drishti is one of five.** The language, the lake, the screens' libraries and the shared utilities are products of their own, in their own
repositories under github.com/drishti-platform; Drishti uses them as jars and bundles. The versions that go together are in the index
repository's COMPATIBILITY.md.

## What changes for administrators

- **Loading, reading and upkeep go through Drishti Lake**: one loader (Java and Python) with the guards `future-days`, `zone`,
  `max-drop-share` and `force-drop`, one archive rule, `lake maintain` for retention, compaction, checkpoints and vacuum (no delta-rs), the read
  API; vectors are stored as numbers in columns with a `{"$vector": NAME}` reference; the lake files' roots are under `${lake.data.dir}`.
- **Get and build the code**: the platform's source bundle, or `clone_all.py` and `build_all.py` of the index repository; the products install
  into `~/.m2` from their tags in the order tools, Rachana, Lake, Drishti.
- **Operator commands are Python only** (`tools/drishti.py`); no shell script and no `.exe` on Windows.
- **IDE launchers** follow one standard across the platform (`Drishti: build all`, `Drishti: run server (demo)`, one `run-configs/` folder); VS Code has
  a guide and a workspace file.

## What changes for developers

- Rachana (one language level, `rachana: 1`), Drishti Elements, Drishti Lake and Drishti Tools are consumed from tags; Drishti no longer carries its own
  copies of the Sutra compiler, the live-table engine, the renderer, the Delta and Iceberg protocol code, the aggregate kernel or the `.xlsx` writers.
- The developer quickstart, the deployment and QA guide, and the IDE guide (now with VS Code) are rewritten for this layout.

## Known limits

- **BI wizard:** a model the wizard saves is not yet offered in the report designer's dataset picker right after it is saved. Reports draw as before.
- **Workbench:** a table's `limit` is edited in a text box (it takes a number or a control reference), and a refused edit in the YAML tab does not yet show its problem line.
- **Admin pages:** the Connectors and Bindings pages differ slightly from their approved pictures; nothing they show is wrong.
- **Iceberg lakes:** building an Iceberg lake with the Python tools failed once on a heavily loaded machine; Delta lakes are not affected.
- **Elements:** a row click that sets a control and remembered control values are not drawn yet in embedded screens.

# Drishti 2.9.11.i7a — release notes

*Released 2026-11-17.* **Modules end to end: a module is a file.** Each module ships as a verified `.dmod` package in
`<home>/modules`, beside one server jar; installing, upgrading or removing a module is copying a file and restarting. The version
shown everywhere is `2.9.11.i7a`; the release tag is `v2.9.11.i_7a`.

## What changes for administrators

- **Build:** `python tools/drishti.py build all` builds the server jar and every module package; `build jar` alone says which modules
  are missing or stale. A server or cluster refuses to start on a package older than its module's source, naming the module and the
  command that fixes it.
- **The old deployment system is gone**: the module store, plans and four-eyes approvals, staging, hot swap, tombstones and the
  Admin deploy screen. **Admin → Modules** now shows every module and, in a cluster, a nodes × modules matrix of the content each node
  booted, with a warning when nodes disagree and the commands to install or upgrade.
- **`python tools/drishti.py module verify`** is the one verifier (in the server jar); `module pack` is the Python packer.
- **Kernel libraries always win**: a module's own copy of a library the kernel ships is ignored and named in a warning (verify, log,
  Health).
- **Cluster stack:** nodes switch off connectors whose tables their lake does not hold, and build their lakes to the business today.
- **An SDK folder** (`python tools/drishti.py build sdk`) lets a partner build, test, pack and verify a module without Drishti's source.

## What changes for readers

- **The genomics showcase works on an installed server** (the module's server code now ships), and its screens say what failed
  instead of waiting forever. Stacks can offer the **Research** lens (*Open Drishti Research*).
- **World economy reads work on an installed server.** IMF-derived figures stay internal: they are never in the shipped package.
- **FORM rules are checked by the server as people type**; the browser holds no rule code.
- Fixes: ids containing `/` in the command line tools; a first save of a BI board under load; a rare stale read right after a
  write-back correction was applied.

## Known limits

- One Spring context for kernel and modules was dropped by design: each module keeps its own child context.

## Upgrading an install of i6

1. `git pull` (or unpack the release); then `python tools/drishti.py build all` (not `build jar`).
2. Restart the servers, then the consoles. A cluster rebuilds each node's synthetic lake on its first start (about 13 minutes per lake).
3. Leftover folders of the old module store under `<data>/modules` are reported at start and can be deleted.


## The previous release: 2.9.11.i6

*Released 2026-11-17.* **The console draws what the server decides (the thin console), Drishti Elements 2.4.0 with one version for
every library, and the genomics showcase: a linked cohort board and a protein-engineering workbench over two seeded data packs.** The
version shown everywhere (About, the console footer, the image tags, the jar's name) is `2.9.11.i6`, read from `.mvn/maven.config`;
the release tag is `v2.9.11.i_6`.

### What changes for administrators

- **The console reads no pack, module, lens or distribution file.** It asks the server (`GET /api/v1/console/bootstrap`,
  `GET /api/v1/me/console`). **Upgrade the servers before the consoles**; `tools/rolling.py` does so. Console settings removed:
  `packs.enabled`, `distributions_dir`, `module_manifests_dir`, `lens.dir`, `builder.max_*`, `under_construction.file`, `ai.*`, `mcp.*`,
  `retry.*` (each now the server's). `trading.people` moves to the server as `drishti.trading.people`.
- **AI is hidden and off** in this release (`drishti.ai.enabled`, off as shipped; Admin → Health says *AI: off*).
- **The tools read the Java resolver's answer** (`resolve --json` in the server jar): every Python tool and generator needs a built jar
  (`python tools/drishti.py build jar`). Site overrides of module files (`drishti.overrides.dir`) now reach these tools too.
- **A fixed business "today"** for a server whose data ends on a known date (`drishti.business-date.today`, environment
  `DRISHTI_BUSINESS_TODAY`; blank as shipped, so the clock's today).
- **Admin → Lakes on a console away from the server's machine** says so and lists the server's read-only lakes instead of offering
  lake scripts.
- **The demo lake has 103 tables** (89 in i5); the two showcase packs are opt-in.
- **`start cluster <name>`** refuses a name that is no node or console, listing the names there are.

### What changes for readers

- **The genomics showcase** (`GWAS RESPONSE`, `PROTEIN HLXK7`, `DESIGNS HLXK7` in Drishti Genomics or Drishti All): the association plot,
  the cohort matrix, the map of samples and the variant lollipop linked by one selection; the mutational scan, the structure in 3D and
  the design campaign. Load its data with `python tools/drishti.py lake demo --only genomics-showcase,protein-engineering`. The record
  words of the two packs are `ASSOC`, `PROT`, `DESIGN`, `SGENE`, `SSAMPLE`, `SVARIANT`, `VCALL`, `CNV`, `PATHWAY`, `RESIDUE`, `DMS`,
  `CONTACT`, `DMUT`, `DROUND`.
- **Drishti Elements 2.4.0**: new chart kinds (radar, violin, ridgeline, curve, density, segmented scatter, matrix, lollipop, 3D trace),
  the dock layout, the command palette, master-detail grid rows, what-if inputs and the query builder.

### Known limits

- A FORM's rules are still evaluated twice, in the browser (`drishti_form-expr.js`, as people type) and on the server (`FormExpr`); one
  home is planned for i7.

### Upgrading an install of i5

1. `git pull` (or unpack the release), then `git clean -fdX modules/` as for i5.
2. `python tools/drishti.py build jar`, then restart the servers, then the consoles.
3. Remove the console settings listed above from your console settings file; set the server's `drishti.trading.people` if you used
   `trading.people`.

