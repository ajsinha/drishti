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
# IDE launcher audit (2026-11-23)

Audit only: no launcher, script or config was changed. Trigger: the owner (Windows, IntelliJ IDEA Community) opened
`drishti/pom.xml`; all 11 Python launchers of Drishti show a red cross, and "Drishti" appears as three folders.

**What was read.** Every `*.run.xml` and every VS Code `tasks`/`launch` entry at the release tags (Drishti `v2.9.11.i_7d`, Rachana `v1.0.1`,
Lake `v0.9.1`, Tools `v1.1.0`, Elements `v3.3.2`, index repository `main` 2f48761 = i7d), extracted with `git archive` into a scratch
folder (nothing was checked out in the owner's folders). Every `drishti.py` command line of the launchers was parsed with the real
parser of i_7d (`--help` and `parse_known_args`); every path was looked up in the tag. `START_HERE_WINDOWS.md` was followed step by step.
The Shell Script plugin's classes of the local IntelliJ 2026.3 install were disassembled, and the owner's own `pravaha/.run` Shell Script
launcher was read, to confirm the Option B XML.

**Not done.** No Windows machine and no IntelliJ Community here: what the Windows IntelliJ shows is derived from the files and from the
plugin code, not seen (marked *derived* below). Nothing was started.

Note: the owner's running checkout `drishti/` is at `v2.9.11.i_7b`, which has 14 launchers; the three `setup` launchers exist from i7d
(the owner's Windows PC has i7d).

## 1. Summary

| | Count |
|---|---|
| Launcher files at the tags | **68** (Drishti 17, Rachana 20, Lake 9, Tools 5, Elements 10, index repository 5 Python + 2 Maven `parent-run-configs`, the two Maven ones also copied beside the platform pom: 70 files on disk) |
| by type | Maven 24, Python (PythonConfigurationType) 34, JAR Application 3, Application 7 |
| OK in IntelliJ Community, no plugin | **34** (the 24 Maven + 3 JAR Application + 7 Application; the 7 Application need the Maven import of `java/pom.xml`, the 3 JAR launchers need `Drishti: build all` and `setup` first) |
| Broken in IntelliJ Community (red cross: unknown type) | **34** (every Python launcher) |
| Broken in PyCharm Community (the mirror image: Maven, JAR Application, Application are not PyCharm types) | **34** |
| Broken everywhere (any IDE, fresh machine following START_HERE) | **5**: the 4 platform Python launchers that name `--release i7b` (stale), and `Drishti: run loader (file into the lake)` (names a file only the owner has) |
| VS Code entries (59 tasks + 41 launches over 6 workspaces) broken | Drishti workspace: **9 of 11 launches on every OS** (8 with `~`, 1 with `$USER_HOME$`), **2 of 17 tasks on every OS** (`$USER_HOME$`) and **8 more on Windows** (`~`); platform workspace: 4 tasks + 4 launches stale (`i7b`) |

**Root cause of the red crosses.** IntelliJ and PyCharm read the same `run-configs/*.run.xml` files and show every type they do not
know as an error. The guides (IDE_GUIDE.md sections 1, 2.3, recipe D) and the docstring of `tools/test_run_configs.py` still describe an
older design where "IntelliJ reads the Java ones, PyCharm the Python ones" from two projects. Since the "one `run-configs/` folder"
standard there is one folder and one project, so each IDE sees the other's launchers. Nothing filters them. `launchers.yaml` line 14 still
says PyCharm launchers go to `drishti-console/run-configs/`; the generator writes them to `run-configs/`.

**Root cause of the three folders.** IntelliJ keeps run-configuration folders per configuration type (the Edit Configurations tree shows
Maven > Drishti, JAR Application > Drishti, Python > Drishti; the drop-down lists each type's group). All 17 files carry
`folderName="Drishti"`, and there are three types, so three folders of the same name. (Derived from the platform's per-type folder model
and the owner's screenshot; consistent with it.)

## 2. IDE capability matrix

| Type (XML `type=`) | IntelliJ CE, no plugin | IntelliJ CE + "Python Community Edition" plugin | PyCharm CE | VS Code |
|---|---|---|---|---|
| `MavenRunConfiguration` | yes (bundled Maven) | yes | **no** (red) | n/a (tasks) |
| `JarApplication` | yes | yes | **no** | n/a |
| `Application` | yes, module must exist (Maven import) | yes | **no** | n/a |
| `PythonConfigurationType` | **no (red)** | yes, but needs a Python SDK and the module in `<module name=...>` | yes with an interpreter | n/a (`debugpy` launches) |
| `ShConfigurationType` (Option B1) | yes, if the bundled "Shell Script" plugin is on (see 5.1) | yes | yes, if the plugin is on | n/a |

Python launchers: `<module name="drishti" />` (the 11 Drishti ones) exists in PyCharm opened on the folder `drishti`, but IntelliJ names the
Maven root module `drishti-parent` (artifactId), so even with the Python plugin the module is not found (derived). The Rachana, Lake,
Elements and platform Python launchers name no module, so they do not have this problem.

## 3. Tables per repository

Columns: IJ = IntelliJ CE without plugins; PC = PyCharm CE; VSC = the VS Code twin. Status: OK, broken-in-IntelliJ, broken-in-PyCharm,
broken-everywhere. Folder is the `folderName` (all are the product name except where stated). Every file uses `$PROJECT_DIR$` as the
folder that was opened.

### 3.1 Drishti (`drishti/run-configs/`, 17; open `drishti/` or `drishti/pom.xml` as the project; `$PROJECT_DIR$` = `drishti/`)

Shared facts: Python launchers use `SDK_HOME` empty and `IS_MODULE_SDK=false` (the project interpreter), working dir `$PROJECT_DIR$`, env
`PYTHONUNBUFFERED=1` (consoles also two dev-only `DRISHTI_*_SECRET` values). Every `--settings "$USER_HOME$/drishti-envs/dev.ini"` file
is made by **`setup`** (`setup drishti`: dev.ini from `config/settings.examples/dev.ini`, and `jvm-demo|qa|samples.args`), which is step 4
of START_HERE_WINDOWS. Verified flags at i_7d: `--settings`/`--profile` are popped by `main()` before the command, `doctor`, `lake demo
--engine java`, `lake build --from --kind --mode overwrite-dates --lake`, `setup [--system] [--wheels FOLDER]`, console `--distribution`,
`--server.port` all exist and parse; no flag is stale.

| Launcher | Type / folder | Runs | Files it names (exist at tag?) | IJ | PC | Why / fix |
|---|---|---|---|---|---|---|
| Drishti: build all | Maven / Drishti | `clean package -DskipTests -pl :drishti-app,:drishti-modules -am` | `pom.xml` yes | OK | broken-in-PyCharm | none needed |
| Drishti: test | Maven / Drishti | `clean verify` | yes | OK | broken-in-PyCharm | none |
| Drishti: build modules | Maven / Drishti | `... -pl :drishti-modules -am -Pmodules-only` | yes (profile exists) | OK | broken-in-PyCharm | none |
| Drishti: run server (demo) | JarApplication / Drishti | jar `$PROJECT_DIR$/lib/drishti-server-exec.jar`, `@$USER_HOME$/drishti-envs/jvm-demo.args`, `-Ddrishti.settings.file=dev.ini,config/profiles/demo.ini` | the jar and `lib/` are git-ignored: made by **build all** (hard link or copy of `drishti-server-<ver>-exec.jar`); `jvm-demo.args` and `dev.ini` made by **setup**; `config/profiles/demo.ini` yes | OK after build+setup, else runs and fails ("Unable to access jarfile") | broken-in-PyCharm | JRE = project SDK (Java 21 must be set in each new window; START_HERE 6.1 says so) |
| Drishti: run server (built-in samples) | JarApplication / Drishti | same, `jvm-samples.args`, `samples.ini` | same | same | same | same |
| Drishti: run server (QA, port 18580) | JarApplication / Drishti | same, `jvm-qa.args`, `qa.ini` | same | same | same | same |
| Drishti: build demo lakes | Python / Drishti | `tools/drishti.py --settings dev.ini lake demo --engine java` | `tools/drishti.py` yes; dev.ini by setup; needs the jar from build all | **broken-in-IntelliJ** (unknown type) | OK with an interpreter whose libraries were set up | fix: section 6 |
| Drishti: check environment | Python / Drishti | `... --profile demo doctor` | same | broken-in-IntelliJ | OK (needs a server on 18480 for the server part; says so) | fix: section 6 |
| Drishti: check environment (QA) | Python / Drishti | `... --profile qa doctor` | same | broken-in-IntelliJ | OK | fix: section 6 |
| Drishti: run console | Python / Drishti | `drishti-console/run_drishti_web.py --settings dev.ini --profile demo` | script yes | broken-in-IntelliJ | OK | fix: section 6 |
| Drishti: run console (QA, port 17580) | Python / Drishti | `... --profile qa` | same | broken-in-IntelliJ | OK | fix: section 6 |
| Drishti: run console (Risk) | Python / Drishti | `... --profile demo --distribution=risk` | same | broken-in-IntelliJ | OK; same port 17480 as the demo console by design | fix: section 6 |
| Drishti: run console (Finance) | Python / Drishti | `... --distribution=finance --server.port=17481` | same | broken-in-IntelliJ | OK | fix: section 6 |
| Drishti: run loader (file into the lake) | Python / Drishti | `lake build --from "$USER_HOME$/drishti-private/endur/trade_endur.2026-10-16.jsonl" --kind trade --mode overwrite-dates --lake demo` | **file does not exist** on any machine but the owner's (a template; the file is never committed) | broken-in-IntelliJ | **broken-everywhere on a fresh machine** (`no such file`) | make it a template that asks for the file (Maven property `-Dfile=`), or name an example file that ships |
| Drishti: setup | Python / Drishti | `setup` | `tools/drishti.py` | broken-in-IntelliJ | OK (makes `drishti-console/.venv` unless told otherwise) | fix: section 6 |
| Drishti: setup (system Python) | Python / Drishti | `setup --system` | same | broken-in-IntelliJ | OK; runs with the project interpreter (chicken and egg: PyCharm needs an interpreter before the launcher that prepares it; START_HERE solves it by a cmd.exe line in step 4) | fix: section 6 |
| Drishti: setup (system Python, offline wheels) | Python / Drishti | `setup --system --wheels "$USER_HOME$/drishti-wheels"` | the folder is made by unzipping the wheels pack (START_HERE 2.2) | broken-in-IntelliJ | OK when the pack is there; refuses a pack for another Python in one sentence | fix: section 6 |

Other Drishti observations: (a) the three Python `setup` launchers cannot choose the Python (the project interpreter is used), but the
wheel pack is `py313` and START_HERE says `py -3.13`; (b) there are no launchers for `start server`, `start console --background`, `stop`,
cluster or `launchers --check`; (c) dev-only token and session secrets are committed in the console and server launchers (placeholders,
named "dev-only"); (d) the generated header says "never edit this file, edit the table" (`tools/launch/launchers.yaml`): every fix below
is a change to the table and to `tools/launch/launchers.py`, then `drishti.py launchers`.

VS Code (`drishti.code-workspace`, 17 tasks + 11 launches):

| Entries | Status | Why | Fix |
|---|---|---|---|
| tasks and launches Drishti: build all / test / build modules | OK (`mvnw`, `mvnw.cmd`) | | |
| tasks run server (demo, samples, QA) | OK; `python3`/`python` + `start server --profile ...` (a different path from the IntelliJ JAR launchers, same jar) | on Windows `python` may be the Microsoft Store stub; START_HERE uses `py` | use `py` on Windows |
| tasks and launches: setup, setup (system Python) | OK | | |
| task and launch **setup (offline wheels)** and **run loader** (4 entries) | **broken on every OS** | the IntelliJ macro `$USER_HOME$` is written literally; VS Code does not know it (its macro is `${userHome}`); in a shell it becomes an empty variable | write `${userHome}` |
| 8 **launch** entries with `--settings ~/drishti-envs/dev.ini` (4 consoles, build demo lakes, loader, 2 check environment) | **broken on every OS** | `debugpy` passes arguments without a shell, so `~` stays literal and `settings_ini.check()` refuses it ("must be a full absolute path") | `${userHome}/drishti-envs/dev.ini` |
| 8 **tasks** with the same `~` | OK on Linux/macOS (the shell expands it); **broken on Windows** (cmd and PowerShell do not expand `~` for a program argument) | same | `${userHome}` |
| workspace settings | `python.defaultInterpreterPath: python3` and `java.configuration.runtimes` path `/usr/lib/jvm/java-21-openjdk-amd64` are Linux-only (same in the Rachana, Lake, Tools, Elements, platform workspaces) | `python3` does not exist on Windows | remove the paths (let the extensions detect) or add `${command:...}`-free per-OS settings |

### 3.2 Rachana (`drishti-rachana/run-configs/`, 20; project = repository root, no root `pom.xml`: the Maven project is `java/pom.xml`)

Maven launchers set `workingDirPath` to `$PROJECT_DIR$/java` (or `/js`) and use the IDE's Maven; they work without any import. The five
`Application` launchers name a module (`rachana-server`, `rachana-cli`, `rachana-data`) that exists only after the Maven import of
`java/pom.xml` (Maven tool window, **+**, choose `java/pom.xml`); until then they show *module not found*. Main classes and flags were
checked in the tag (`Main` of server and cli, `ReferenceServer --port --base --static`, `serve|check --config`, `hash-password`, `lib ...`).

| Launcher(s) | Type | IJ | PC | Notes / fix |
|---|---|---|---|---|
| Rachana: build all, test, install release (tag only), build JS bundles | Maven | OK | broken-in-PyCharm | `install release` writes `~/.m2`: by design, release tag only |
| Rachana: bundle JS runtime, test JS (Node), run Studio (static-sales), run Studio (server-demo), run dev server (static-sales) | Maven `exec:exec@runtime-bundle|node-tests|studio|studio-server-demo|dev` in `js/pom.xml` (all ids exist) | OK | broken-in-PyCharm | needs Node on PATH; Windows profile sets `python` (not `py`): see 5.3 |
| Rachana: run server (demo), check server (demo), run server hash-password, run reference server, run CLI (lib) | Application | OK after the Maven import | broken-in-PyCharm | `rachana-server.yaml` and `examples/server-demo` exist |
| Rachana: check examples, check protocol (Python host), lint examples, test Python, run live-orders (Flask), run live-orders (FastAPI) | Python | **broken-in-IntelliJ** | OK with an interpreter that has the libraries | `lint` and `test Python` run as modules (`MODULE_MODE`), `PYTHONPATH=$PROJECT_DIR$/python/src`; FastAPI/Flask need `fastapi`, `uvicorn`, `flask` installed (examples extras); `pytest` needed |

Folders: "Rachana" three times (Maven, Application, Python). VS Code (15 tasks, 11 launches): OK on both OSes (no macro, no `~`);
`python3`/`python`; `Rachana: run live-orders (FastAPI)` launch uses module `uvicorn`.

### 3.3 Lake (`drishti-lake/run-configs/`, 9; project = repository root, Maven project `java/pom.xml`)

| Launcher | Type | IJ | PC | Notes / fix |
|---|---|---|---|---|
| Lake: build all, test, install release (tag only) | Maven (`$PROJECT_DIR$/java`) | OK | broken-in-PyCharm | |
| Lake: run loader (CLI) | Application `lake-load` / `LakeLoad --help` | OK after the Maven import | broken-in-PyCharm | prints help only: a pointer, not a job |
| Lake: run check (CLI) | Application `LakeCheck`, no arguments | OK after the import | broken-in-PyCharm | |
| Lake: test Python | Python module `pytest -q python/tests`, `PYTHONPATH=python` | broken-in-IntelliJ | OK | needs pytest |
| Lake: test TCK (this repository) | Python `tools/tck/tck.py --out java/target/tck-local.jsonl` (`--out` required: ok) | broken-in-IntelliJ | OK | needs a built `java/target` |
| Lake: check all (build, TCK twice, reference) | Python `tools/ci/check.py` | broken-in-IntelliJ | OK | long |
| Lake: maintain release plan (dry run) | Python `tools/release/release.py --version 0.0.0` | broken-in-IntelliJ | OK | skeleton, changes nothing (verified in the script); refuses a dirty tree or a branch other than main, so at a tag it prints "not on main" and exits 1 (a red run that is not an error) |

VS Code (7 tasks, 6 launches): OK.

### 3.4 Tools (`drishti-tools/java/run-configs/`, 5; project = `drishti-tools/java/`, `$PROJECT_DIR$` = `java/`)

| Launcher | Type | IJ | PC | Notes / fix |
|---|---|---|---|---|
| Tools build all, test, API surface rewrite, install release, xlsx with LibreOffice | Maven | OK | broken-in-PyCharm | folder `Tools` |

Deviations from the standard: the files live in `java/run-configs/`, not the repository root, and the names lack the colon
(`Tools build all`, not `Tools: build all`). The VS Code workspace (5 tasks, `cwd` = `java`) is OK; "install release (ONLY on a release
tag)" is named differently from the IntelliJ one. Fix: rename and move (or document why Tools is different).

### 3.5 Elements (`drishti-elements/run-configs/`, 10; project = repository root, root `pom.xml` = a launcher pom)

| Launcher | Type | IJ | PC | Notes / fix |
|---|---|---|---|---|
| Elements: run gallery server, test Node | Maven `exec:exec@gallery|node-tests` (both ids exist; `python` on Windows, `python3` elsewhere) | OK | broken-in-PyCharm | needs Python/Node on PATH |
| Elements: build all, build dist, check API docs, check bundles, check licence headers | Python scripts under `tools/` (all exist) with `--check` where stated | broken-in-IntelliJ | OK | |
| Elements: test, test static, test one file | Python module `pytest` (`-n 8` needs pytest-xdist; the tests need Playwright browsers) | broken-in-IntelliJ | OK | |

Elements is the repository that already uses the Option B pattern (Maven `exec:exec` with a `python` property and a Windows profile) for
two launchers and Python configurations for eight: a mixed state. VS Code (10 tasks, 8 launches): OK.

### 3.6 Index repository (`drishti-platform/run-configs/` 5 Python; `parent-run-configs/` 2 Maven, placed beside the pom by `clone_all.py`)

| Launcher | Type | IJ | PC | Why / fix |
|---|---|---|---|---|
| platform-clean-install | Maven `validate` with the runner property `goals=clean install`, `skipTests`, VM option `-Djavax.net.ssl.trustStoreType=Windows-ROOT` (no folder, no "Platform:" name) | OK (Windows) | broken-in-PyCharm | works through the pom's `${goals}`; do not run the Windows one on Linux (the VM option breaks TLS there) |
| platform-clean-install (Linux, macOS) | Maven, same without the option | OK | broken-in-PyCharm | the existing precedent for a per-OS pair |
| Platform: clone all, build all, build all (skip tests), make bundle | Python, each with `--release i7b` | broken-in-IntelliJ | **broken-everywhere (stale)** | the current release is `i7d` (`platform.yaml`); `build_all.py --release i7b` refuses with "drishti is at 'v2.9.11.i_7d' but the release needs 'v2.9.11.i_7b'" on an i7d clone (and "?" in a source zip, which has no git); `make_bundle.py` would build an i7b bundle. The same stale `i7b` is in the VS Code tasks (4 tasks + 4 launches) and the guides |
| Platform: test Python | Python module `pytest -q tests` | broken-in-IntelliJ | OK | |

Opening the outer folder (START_HERE step 3, the platform `pom.xml`) makes `$PROJECT_DIR$` the outer folder: the five `Platform:`
launchers need `build_all.py` etc. directly under it, which the source zip does not contain (they are in the index repository
`drishti-platform/drishti-platform/`, a subfolder), so they only work with the index repository opened as its own project. The two
`platform-clean-install` copies in the host folder `run-configs/` are identical to `parent-run-configs/`.

## 4. The grouping fix (folder names)

IntelliJ groups per type, so one name on three types gives three folders. Choose one:

1. **Preferred, with Option B2 (section 5.2):** after the Python launchers become Maven launchers there are two types left. Use
   `folderName="Drishti"` on every Maven launcher (build, setup, tools, consoles: one list) and `folderName="Drishti servers"` on the three
   JAR Applications. Two folders with two different, meaningful names.
2. Without Option B: keep three types but name the folders by job: Maven `Drishti build`, JAR Application `Drishti servers`, Python
   `Drishti console and tools`. The three look different instead of identical.
3. Or drop `folderName` entirely: the `Drishti: ` prefix of every name already groups them alphabetically inside each type.

Apply the same in Rachana (Maven / Application / Python = 3 folders), Lake (3), Elements (2), platform (Python folder `Platform`, the
Maven pair has none). Generator change: `launchers.py` writes `folderName` from one constant; make it per kind.

## 5. Option B: launchers IntelliJ can run with no Python plugin

### 5.1 B1: the Shell Script configuration (`ShConfigurationType`)

* **Type id** is `ShConfigurationType` (not `ShellConfigurationType`), factory "Shell Script". Confirmed from the plugin's classes
  (`com.intellij.sh.run.ShConfigurationType`) and from the owner's own `pravaha/.run/Pravaha console (shell).run.xml`.
* **Availability.** The plugin `com.jetbrains.sh` ("Shell Script") is bundled in the IntelliJ IDEA distribution (it is in the local
  2026.3 product-info `bundledPlugins`; its source is in the open intellij-community repository, `plugins/sh`) and is a free JetBrains plugin
  otherwise. I could not open a Community install to confirm the bundled flag for 2024/2025 Community: check **Settings, Plugins,
  Installed, "Shell Script"** once on the owner's PC. It also needs the bundled Terminal plugin.
* **XML shape** (a script-text configuration; `EXECUTE_SCRIPT_FILE=false` means "script text", not a file):

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Drishti: setup (system Python)" type="ShConfigurationType" folderName="Drishti">
    <option name="SCRIPT_TEXT" value="py -3.13 tools\drishti.py setup --system" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="false" />
    <option name="INTERPRETER_PATH" value="" />
    <option name="INTERPRETER_OPTIONS" value="" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="false" />
    <envs />
    <method v="2" />
  </configuration>
</component>
```

* **cmd or PowerShell on Windows.** With `INDEPENDENT_INTERPRETER_PATH=false` (and an empty `INTERPRETER_PATH`) the configuration takes the
  default shell of the IDE (`ShDefaultShellPathProvider`, implemented by the Terminal plugin: the shell set in **Settings, Tools, Terminal,
  Shell path**; on current Windows IntelliJ that is PowerShell when found, else cmd). With `EXECUTE_IN_TERMINAL=true` the text is typed into
  a Terminal tab of that shell. With `EXECUTE_IN_TERMINAL=false` the plugin starts `<interpreter> -c "<script text>"` (the literal `-c` is in
  `ShRunConfigurationProfileState`): fine for bash and PowerShell, **wrong for cmd.exe** (needs `/c`). A committed absolute
  `INTERPRETER_PATH` such as `C:\Windows\...\powershell.exe` is checked when the configuration is validated ("interpreter not found"), so it
  would show a red cross on Linux and macOS: do not commit one.
* **Macros.** The plugin has no macro code; shared project run files are expanded by the platform's path-macro manager when loaded, so
  `$PROJECT_DIR$` and `$USER_HOME$` in `SCRIPT_TEXT` are probably expanded like in the Python `PARAMETERS` (derived, test once).
* **Per-OS naming.** The commands differ by OS (`py -3.13 tools\drishti.py` against `python3 tools/drishti.py`; home folder `$USER_HOME$`
  against `~`), so B1 needs one file per OS: follow the existing precedent `platform-clean-install` and
  `platform-clean-install (Linux, macOS)`: `Drishti: run console` (Windows) and `Drishti: run console (Linux, macOS)`. That doubles the 11
  Python launchers to 22 files, one set of which is always the wrong one for the OS in front of you, and nothing marks it red.
* **Single cross-platform text: not possible** in one shell-text file: `||` is not PowerShell 5.1 syntax, `if (...) {...}` is not bash, and a
  `py ... || python3 ...` form would run the script twice when it fails.
* **Stop behaviour.** With `EXECUTE_IN_TERMINAL=false` the process runs under a pseudo terminal and the Stop button ends the shell and its
  child: the right choice for the console (uvicorn). (Derived from `PtyCommandLine` in the class, test once.)

### 5.2 B2: Maven `exec:exec` launchers through a launch pom (recommended)

The platform already does this twice: the platform `pom.xml` (`<platform.python>` set to `-Dpython3=py` by an OS profile, exec plugin
3.6.3), and Elements and Rachana `js/pom.xml` (a `python` property with a `windows-python` profile). Same idea for Drishti:

* One standalone pom, **not a module of `drishti-parent`**: `tools/launch/pom.xml` (artifact `drishti-launchers`). Every launcher is a
  Maven run configuration with `workingDirPath=$PROJECT_DIR$/tools/launch`, goal `exec:exec@<id>`.
* One property chooses Python for every OS: `python` is `python3`, the Windows profile sets `py`, `python.flags` is empty and on Windows
  `-3.13` (the wheel pack is `py313`; a user overrides with `-Dpython.flags=` or `-Dpython=C:/.../python.exe`).
* `dev.ini` is `${user.home}/drishti-envs/dev.ini` in the pom: no `$USER_HOME$` and no `~` anywhere.
* Executions carry no `<phase>`, so they never run in a build.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ash.drishti</groupId><artifactId>drishti-launchers</artifactId><version>0</version><packaging>pom</packaging>
  <properties>
    <home>${project.basedir}/../..</home>
    <python>python3</python><python.flags></python.flags>
    <dev.ini>${user.home}/drishti-envs/dev.ini</dev.ini>
  </properties>
  <profiles>
    <profile><id>windows-python</id>
      <activation><os><family>windows</family></os></activation>
      <properties><python>py</python><python.flags>-3.13</python.flags></properties>
    </profile>
  </profiles>
  <build><plugins><plugin>
    <groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.6.3</version>
    <configuration><executable>${python}</executable><workingDirectory>${home}</workingDirectory></configuration>
    <executions>
      <execution><id>setup-system</id><goals><goal>exec</goal></goals><configuration>
        <commandlineArgs>${python.flags} tools/drishti.py setup --system</commandlineArgs></configuration></execution>
      <execution><id>doctor</id><goals><goal>exec</goal></goals><configuration>
        <commandlineArgs>${python.flags} tools/drishti.py --settings "${dev.ini}" --profile demo doctor</commandlineArgs></configuration></execution>
      <execution><id>console</id><goals><goal>exec</goal></goals><configuration>
        <environmentVariables>
          <PYTHONUNBUFFERED>1</PYTHONUNBUFFERED>
          <!-- the two dev-only secrets, as in the console launchers today -->
        </environmentVariables>
        <commandlineArgs>${python.flags} drishti-console/run_drishti_web.py --settings "${dev.ini}" --profile demo</commandlineArgs>
      </configuration></execution>
      <!-- one execution per launcher row of tools/launch/launchers.yaml (kind console|tool), generated -->
    </executions>
  </plugin></plugins></build>
</project>
```

The run configuration (same shape as the existing Maven launchers; only the goal, `pomFileName` and the working folder differ):

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Drishti: run console" type="MavenRunConfiguration" factoryName="Maven" folderName="Drishti">
    <MavenSettings>
      <option name="myGeneralSettings" />
      <option name="myRunnerSettings" />
      <option name="myRunnerParameters">
        <MavenRunnerParameters>
          <option name="profiles"><set /></option>
          <option name="goals"><list><option value="-q" /><option value="exec:exec@console" /></list></option>
          <option name="pomFileName" value="pom.xml" />
          <option name="profilesMap"><map /></option>
          <option name="resolveToWorkspace" value="false" />
          <option name="workingDirPath" value="$PROJECT_DIR$/tools/launch" />
        </MavenRunnerParameters>
      </option>
    </MavenSettings>
    <method v="2" />
  </configuration>
</component>
```

Why B2 over B1: one type that every IntelliJ edition knows (Maven is bundled, no plugin to check); **one file per launcher for all
three OSes**, the OS difference lives in one profile; no interpreter path to go red on another OS; the run list shrinks to two types
(Maven, JAR Application), so section 4 gives two folders; the generator keeps its table (a new `kind: exec`); the pattern is the one the
platform pom, Elements and Rachana already use; and PyCharm is no longer needed on the Windows PC at all (START_HERE steps 5 and 6.2
disappear, as do the interpreter choice and the "decline the virtual environment" note). Costs: a Maven start adds a few seconds, output is
Maven's (`-q` hides its own lines); **Stop** on a long-running console ends Maven and may leave the Python child alive (known exec-plugin
behaviour; not tested here: on the owner's PC press Stop once and check that port 17480 is free). If it does, the console launcher
becomes `start console --background` plus a `stop console` launcher (both exist in `drishti.py`), or the console alone is B1 with
`EXECUTE_IN_TERMINAL=false`.

### 5.3 Verdict

**Adopt B2 for all Python launchers of Drishti (11) and, later, of Rachana, Lake, Elements and the platform; keep B1 only as the
fallback for the console if Stop turns out not to kill it.** Do not mix both. Keep the VS Code `debugpy` launches (for debugging the
console); PyCharm users lose nothing but the generated Python launcher files, which stop existing. While there,
make the Windows profile the same everywhere: Elements and Rachana use `python` on Windows, the platform pom uses `py` because
`python` is often the Microsoft Store stub.

## 6. Ordered fix list

1. **Decide B2** (this note) and add `tools/launch/pom.xml` and a generator kind `exec` (`launchers.py`, `launchers.yaml`): the
   11 Drishti Python launchers become Maven launchers; `test_run_configs.py` and the `launchers --check` gate change with them. Result:
   zero red crosses in IntelliJ CE, one launcher set for all OSes.
2. **Folder names** (section 4): Maven `Drishti`, JAR Application `Drishti servers`; same rule in the other four repositories.
3. **VS Code Drishti workspace:** `$USER_HOME$` to `${userHome}` (4 entries), `~` to `${userHome}` (8 launches, 8 tasks), `py` on
   Windows, drop the Linux-only `python.defaultInterpreterPath` and JDK path from all six workspaces.
4. **Platform launchers and tasks:** remove the hard-coded `--release i7b` (default to the newest `released` row of `platform.yaml`, or
   read it from the file); the same in the platform VS Code workspace, `IDE_GUIDE.md` and `START_HERE_WINDOWS.md` wherever `i7b` is typed.
5. **Docs and guards:** correct `IDE_GUIDE.md` (sections 1, 2.3, recipe D: each IDE shows the other's types red) and the docstring of
   `tools/test_run_configs.py` and the header of `launchers.yaml` (two-project design); START_HERE_WINDOWS drops the PyCharm steps after
   fix 1.
6. **`Drishti: run loader (file into the lake)`:** make it a launcher that takes the file (`-Dfile=...` in B2), not a path that only exists
   on the owner's PC.
7. **Rachana and Lake `Application` launchers:** document (or generate a root aggregator `pom.xml`) that `java/pom.xml` must be added in
   the Maven tool window, otherwise *module not found*; Tools: move to the root and use the `Tools: verb` names.
8. **Lake `maintain release plan`:** pass nothing that makes a clean run exit 1 at a tag (or say so in its description).
9. **JAR launchers:** none needed, but `build all` must be the documented first click (the jar and `lib/drishti-server-exec.jar` are
   not in git).
