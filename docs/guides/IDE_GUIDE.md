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
# IntelliJ IDEA and PyCharm: build and run Drishti

For developers and testers who build and run Drishti from the two IDEs: the server and the modules from IntelliJ IDEA, the console and
the command-line tools from PyCharm. The repository carries its run configurations as files, so nothing is installed and no `.exe` is
added. Every configuration uses only `$PROJECT_DIR$` and `$USER_HOME$` paths, so the same files work on Linux and Windows.

**In one minute.** Run `python tools/drishti.py setup` once. Open the root `pom.xml` in IntelliJ and `drishti-console/` in PyCharm.
Run, in this order: **Drishti build all**, **Build demo lakes**, **Drishti server (demo lakes)**, **Drishti console**. Open
`http://localhost:17480` and sign in as `drishti-dev-admin` / `drishti-dev-admin123`.

## 1. What you need

| You need | How |
|---|---|
| **A JDK 21** | Linux `/usr/lib/jvm/java-21-openjdk-amd64`, Windows `C:\jdk-21`, macOS `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`. Production runs on 21. |
| **Python 3.11 or later** | `python3` (Windows `py`) to run `tools/drishti.py`; the console runs in its own environment (next row). |
| **`drishti-console/.venv`** | made by `python tools/drishti.py setup` (it installs `drishti-console/requirements.txt`). |
| **Your settings file `~/drishti-envs/dev.ini`** | made by the same command from `config/settings.examples/dev.ini` with this machine's paths filled in (Windows `C:\Users\you\drishti-envs\dev.ini`); a file already there is never overwritten. |

**Every run configuration passes `dev.ini`** (a Java one as `-Ddrishti.settings.file=$USER_HOME$/drishti-envs/dev.ini`, a Python one as
`--settings $USER_HOME$/drishti-envs/dev.ini`), so it must exist before the first run. The file names this clone in `drishti.home`; a
`DRISHTI_HOME` or `drishti.home` that names another clone is refused. The settings file, what each key does and the other ways to make it:
[DEPLOYMENT_AND_QA_GUIDE.md › The settings file](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup).

1. From the clone: `python3 tools/drishti.py setup --check` (Windows `py tools\drishti.py setup --check`) lists what is missing and changes nothing.
2. `python3 tools/drishti.py setup` makes it. You should see `[made] settings file …/drishti-envs/dev.ini` and `[made] console .venv …`.
   The `[todo]` lines for the modules and the server jar are done by section 3.

## 2. Open the projects

Two IDEs, two projects, **never one folder for both**: opened on the same folder they overwrite each other's `.idea`, and IntelliJ then
loses the Maven modules (*module not specified*). If that happened, close both, delete the root's `.idea/` (IDE settings only) and reopen.
One IntelliJ IDEA Ultimate with the Python plugin can run both kinds of configuration.

### 2.1 IntelliJ IDEA: the root

1. **File → Open**, choose the root `pom.xml`, **Open as Project**. Wait for the Maven import and the indexing; the Maven tool window lists `drishti-app`.
2. **File → Project Structure → Project → SDK**: **Add SDK → JDK…**, choose the JDK 21 folder; **Language level** *SDK default*.
3. **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE**: *Use Project JDK*. Maven in the IDE ignores your shell's `JAVA_HOME`.
4. The drop-down at the top right now lists the configurations of both folders under the folder **Drishti** (section 5).
5. For the Python launchers (`Drishti build all`, `Drishti console (All)` and the like, which live in `run-configs/`) IntelliJ needs the Python plugin and an interpreter.
   **Install the plugin first**: **File → Settings → Plugins → Marketplace**, search **Python** (IntelliJ IDEA Ultimate) or **Python Community
   Edition** (IntelliJ IDEA Community), **Install**, restart IntelliJ. A plugin is a plain download inside IntelliJ, no `.exe`. Without it
   those launchers sit under **Unknown** with a red cross and say *Unknown run configuration type PythonConfigurationType*. Where plugins
   are blocked, build from IntelliJ's own terminal instead: `py tools\drishti.py build all` (Linux `python3 tools/drishti.py build all`) does
   exactly what the launcher does. If `drishti-console\.venv` does not exist yet, `py tools\drishti.py setup` makes it. Then the interpreter:
   **Project Structure → SDKs → Add → Python SDK → Existing**, `drishti-console/.venv/bin/python` (Windows `drishti-console\.venv\Scripts\python.exe`).
   The configurations store no interpreter path of their own; they use the project's.

### 2.2 PyCharm: `drishti-console/`

1. **File → Open**, choose the folder `drishti-console/`.
2. **Settings → Project → Python Interpreter → Add Interpreter → Add Local Interpreter → Existing**:
   Linux `…/drishti/drishti-console/.venv/bin/python`, Windows `C:\Users\you\IdeaProjects\drishti\drishti-console\.venv\Scripts\python.exe`.
3. **Project Structure → + Add Content Root**: the repository's `tools/`, marked **Sources** (for reading and completion; the run configurations work without it).
4. Right-click `tests/` → **Mark Directory as → Test Sources Root**.
5. The command-line tools in `tools/pvf/` use Polars. The console's environment may not carry it: either
   `drishti-console/.venv/bin/pip install -r drishti-console/requirements-polars.txt`, or give those scripts another interpreter (Python 3.11 or later with Polars,
   NumPy and PyYAML) in their own run configuration. The details are in `tools/pvf/README.md`.

## 3. Build

Open the **Drishti build all** configuration (IntelliJ, `run-configs/`) and press **Run** (Shift+F10).

| Order | Run configuration | What it runs | Use it when |
|---|---|---|---|
| 1 | **Drishti build all** | `tools/drishti.py --settings … build all`: one Maven run that builds the server jar into `lib/` (`lib/drishti-server-<version>-exec.jar`) and the twelve module packages into `modules/` (`<id>-<version>.dmod` and `modules/first-party.yaml`) | the first build on a clone, and after every pull: **this is the one to click** |
| | **Drishti build server jar** (IntelliJ) and **Build server jar (into lib)** (PyCharm) | `… build jar`: Maven `clean package -DskipTests -pl :drishti-app -am`, then the jar into `lib/` | you changed only the kernel and the modules are current. The two are the same command. |
| | **Drishti build modules** | `… build modules`: only the module packages | you changed only module code. It is also the before-launch step of **Drishti server (debug-friendly)**. |

**You should see** Maven's progress, then the jar named in `lib/` and the packages in `modules/`. The jar holds no module, so **`build jar`
alone leaves `modules/` empty or stale and the server will not start** (section 8). The first build downloads Maven dependencies and
takes minutes; later ones are faster. `build all` always runs `clean`, so `lib/` never holds a stale jar.

From a terminal the same is `python3 tools/drishti.py build all` (Windows `py tools\drishti.py build all`; `--offline` builds without the network).

**For a module of your own** (a project with `module.yaml` at its top, under `<Drishti folder>/modules/`) there are two ways to see an edit:
rebuild its package with `python3 tools/drishti.py build modules <module-id>` and restart, or run **Drishti server (dev switch)** (section 6.3),
which loads the module folders as they are, unverified. A third party builds against the SDK folder alone: `python3 tools/drishti.py build sdk`
([DEVELOPER_GUIDE.md › The module SDK](DEVELOPER_GUIDE.md#16-the-module-sdk)).

## 4. Run: demo lakes, server, console

The order matters: the lakes are written by the Java loader of the jar, the server reads the lakes, the console talks to the server.

### 4.1 Build the demo lakes

PyCharm (or IntelliJ with the Python plugin): **Build demo lakes**. It runs `tools/drishti.py --settings … lake demo --engine java`, the demo
lake of [section 6 of the Deployment guide](../DEPLOYMENT_AND_QA_GUIDE.md#6-build-the-demo-lake): sixty business days of production-shaped data ending today's business date.
Stop the server first. **You should see** `Demo lake ready: …` (this takes several minutes and writes under your data folder).

### 4.2 Start the server

IntelliJ: **Drishti server (demo lakes)**. It is `DrishtiApplication` on the class path of module `drishti-app`, port **18480**,
`-Ddrishti.home=$PROJECT_DIR$`, `-Xmx4g`, offline, sign-in on, the ten demo packs, the development administrator, `DRISHTI_DEMO_ENABLED=false`
(it reads the lakes of 4.1 and generates nothing). **You should see** in the Run window `Started DrishtiApplication in … seconds` and a `resolved paths:`
block that names `dev.ini` as the origin of the data folder. Check: `curl -s localhost:18480/actuator/health` answers `UP`.

If you have not built the lakes, use **Drishti server** instead: the same on 18480 with the packs' small samples (demo on), `-Xmx2g`.

### 4.3 Start the console

PyCharm: **Drishti console**. It runs `drishti-console/run_drishti_web.py --settings … --server.port=17480 --backend.url=http://127.0.0.1:18480` with sign-in on and the
development secrets. **You should see** `Uvicorn running on http://0.0.0.0:17480`.

### 4.4 Sign in

Open `http://localhost:17480` and sign in as **`drishti-dev-admin`** / **`drishti-dev-admin123`** (the administrator the server seeds in these
configurations; development only). Then **Admin → Health** lists the server and each module as *loaded*. More on signing in, and a token for the command line:
[DEPLOYMENT_AND_QA_GUIDE.md › Sign in](../DEPLOYMENT_AND_QA_GUIDE.md#93-sign-in-and-a-token-for-the-command-line).

### 4.5 A distribution: Risk, Finance or All

A white-labelled instance is a server and a console started with the same distribution
([DEPLOYMENT_AND_QA_GUIDE.md › A white-labelled instance](../DEPLOYMENT_AND_QA_GUIDE.md#77-starting-a-white-labelled-instance)).
Start **Drishti server (All)** (every module; a Risk or Finance server would limit both consoles to its own), then **Drishti console (Risk)**
(port 17480, own temp folder `data/tmp/console-risk`) and **Drishti console (Finance)** (port 17481). Open the second by another host name
(`http://127.0.0.1:17481`) or another browser profile, because browsers share sign-in cookies across the ports of one host.
**Drishti console (All)** is the console for the **All** distribution on 17480. Do not run **Drishti console (All)** and **(Risk)** together: both use 17480.

## 5. Every launcher

All run from the repository root. Server configurations are IntelliJ **Application** configurations (`com.ash.drishti.server.DrishtiApplication`,
class path of module `drishti-app`, **Shorten command line: @argfile**); the rest are Python configurations of the project interpreter.
`dev.ini` is passed by all of them. **Ports**: 18480 default server, 17480 default console, 18580 and 17580 the QA pair, 17481 the Finance console.

### 5.1 Servers (IntelliJ)

| Name | What it runs | Port, data folder | Use it when |
|---|---|---|---|
| **Drishti server** | the seven core packs with demo data generated on, sign-in on, `-Xmx2g` | 18480, `drishti.data.dir` of `dev.ini` | a quick look without building lakes |
| **Drishti server (demo lakes)** | the ten packs, demo off, offline, system baskets seeded, `-Xmx4g` | 18480, `dev.ini` | the normal run after **Build demo lakes** |
| **Drishti server (All)** | every module; `DRISHTI_DISTRIBUTION=all`; the seven core packs, `-Xmx2g` | 18480, `dev.ini` | with **Drishti console (All)** or the two distribution consoles |
| **Drishti server (Risk)** | `DRISHTI_DISTRIBUTION=risk` | 18480, `dev.ini` | with **Drishti console (Risk)** |
| **Drishti server (Finance)** | `DRISHTI_DISTRIBUTION=finance` | 18480, `dev.ini` | with **Drishti console (Finance)** |
| **Drishti server (QA profile)** | the seven packs, sign-in on, `-Xmx2g`, its own folder given as `-D` (`-Ddrishti.data.dir=$PROJECT_DIR$/data/qa/data -Dserver.port=18580`; a `-D` beats the settings file) | 18580, `data/qa/data` | QA or a second server beside the first; never touches your data |
| **Drishti server (debug-friendly)** | the QA profile with sign-in **off**, DEBUG logging for `com.ash.drishti`, `-Xmx1g`; **before launch: Drishti build modules** | 18580, `data/qa/data` | stepping through code (section 6) |
| **Drishti server (dev switch)** | the QA profile with sign-in off, DEBUG, and `DRISHTI_MODULES_DEV=true`: module folders load as they are, nothing built | 18580, `data/qa/data` | editing a module and restarting without packing it (section 6.3) |

### 5.2 Consoles (PyCharm, and the distribution ones in IntelliJ)

| IDE | Name | What it runs | Port, backend | Use it when |
|---|---|---|---|---|
| PyCharm | **Drishti console** | `run_drishti_web.py`, sign-in on, development secrets | 17480, server 18480 | the normal console |
| PyCharm | **Drishti console (QA profile)** | the same with `DRISHTI_LAKE_ROOTS=$PROJECT_DIR$/../data/qa/delta` | 17580, server 18580 | with **Drishti server (QA profile)** |
| IntelliJ | **Drishti console (All)** | `--distribution=all` | 17480, server 18480 | with **Drishti server (All)** |
| IntelliJ | **Drishti console (Risk)** | `--distribution=risk`, temp folder `data/tmp/console-risk` | 17480, server 18480 | Drishti Risk |
| IntelliJ | **Drishti console (Finance)** | `--distribution=finance`, temp folder `data/tmp/console-finance` | 17481, server 18480 | Drishti Finance, beside the Risk console |

### 5.3 Build and data tools

| IDE | Name | What it runs | Use it when |
|---|---|---|---|
| IntelliJ | **Drishti build all** | `drishti.py --settings … build all` | after a clone or a pull |
| IntelliJ | **Drishti build server jar** | `… build jar` | the same command as the next row |
| PyCharm | **Build server jar (into lib)** | `… build jar` | the kernel only |
| IntelliJ | **Drishti build modules** | `… build modules` | module code only |
| PyCharm | **Build demo lakes** | `… lake demo --engine java` | after a build, server stopped |
| PyCharm | **Load ENDUR file into the lake** | `… lake build --from $USER_HOME$/drishti-private/endur/trade_endur.2026-10-16.jsonl --pack $PROJECT_DIR$/../modules/banking/packs/trading --connector trade-endur --key TRADE_ID --mode overwrite-dates --lake demo` | you hold a real ENDUR file (never committed); edit the file name |

### 5.4 Command-line tools (PyCharm)

The first group runs against the default server on 18480, the QA group against 18580 with a token file `data/qa/token.txt`
(**My account → API tokens**, save the text there). Edit *Parameters* for your own files.

| Name | What it runs |
|---|---|
| **drishti doctor** | `doctor --server http://localhost:18480`: checks the server and the environment, a red line names the problem |
| **drishti server smoke** | `server smoke --server http://localhost:18480 --ids 2 --dates 2` |
| **drishti view get** | `view get trade/1000001 --server http://localhost:18480` |
| **drishti data profile** | `data profile data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz` |
| **drishti pack make** | `pack make <file> --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force` |
| **drishti pack regenerate** | `pack regenerate data/build/my-bank/pack/my-bank <file> --dry-run` (run **drishti pack make** first) |
| **drishti sutra gen** | `sutra gen <file> --kind trade --match PRODUCT_TYPE --out data/build/sutras` |
| **QA doctor** | `doctor --server http://localhost:18580 --token-file data/qa/token.txt` |
| **QA server smoke** | `server smoke --server http://localhost:18580 --ids 2 --dates 2 --token-file data/qa/token.txt`; ends `smoke: OK` |
| **QA pack make (from JSON)** | `pack make $USER_HOME$/Downloads/YOUR_SAMPLE.jsonl --kind trade --match PRODUCT_TYPE --name qa-pack --out data/qa/packs/qa-pack --force`; replace `YOUR_SAMPLE.jsonl` |
| **QA lake build (big JSON to Delta)** | `lake build --from $USER_HOME$/Downloads/trade.YYYY-MM-DD.json --kind trade --key TRADE_ID --mode overwrite-dates --partition-by PRODUCT_TYPE --lake data/qa/delta --pack data/qa/packs/qa-pack/pack/qa-pack`; replace the date. Keep it to 10,000 trades on a laptop. |

The QA end to end, step by step: [6.5](#65-the-qa-profile-end-to-end-no-terminal).
Every command's options: [CLI_REFERENCE.md](CLI_REFERENCE.md).

**Your own configuration.** Never edit a shipped file for yourself: **Copy Configuration** (Ctrl+D) and change the copy. The copy lives in the git-ignored `.idea/`.
By hand: **Run → Edit Configurations → + → Application**, main class `com.ash.drishti.server.DrishtiApplication`, class path of module **`drishti-app`**
(`drishti-server` alone carries no domain module, so every banking function would answer *not installed*), **Shorten command line** *@argfile*, JRE 21,
VM options `-Ddrishti.home=$PROJECT_DIR$ -Ddrishti.settings.file=$USER_HOME$/drishti-envs/dev.ini`, working directory `$PROJECT_DIR$`.

## 6. Debug, develop a module, run tests

### 6.1 Debug the server

1. Run **Drishti build all** (or at least **Drishti build modules**).
2. Choose **Drishti server (debug-friendly)** and press **Debug** (Shift+F9). Its before-launch step builds the modules; it listens on **18580**, sign-in is off, and the
   log is at DEBUG.
3. The console to pair with it: copy **Drishti console (QA profile)** (Ctrl+D) and set `DRISHTI_AUTH_ENABLED` to `false` in the copy; the console (17580) then needs no sign-in.
4. Good breakpoints: `com.ash.drishti.engine.ViewPipeline` (a view being built), `com.ash.drishti.rachana.SutraRegistry` (which Sutra was chosen),
   a plugin's `SourcePlugin.fetch(…)`, and the controllers under `com.ash.drishti.server`.
5. While it runs: **Run → Debugging Actions → Reload Changed Classes** reloads method bodies (a changed signature, field or class needs a restart);
   Sutras, packs and connector files reload by themselves; `application.yaml` and the environment need a restart.

### 6.2 Debug the console

In PyCharm press **Debug** on **Drishti console**. For a debugger to see each request add the environment variable `DRISHTI_CONSOLE_WORKERS=1` to the configuration
(otherwise a request reaches a worker process the debugger is not in). Breakpoints: the routes in `drishti-console/core/app.py`. Templates and static files
reload with the browser page; Python code needs a restart (Ctrl+F5).

### 6.3 Work on a module

Run **Drishti server (dev switch)** (port 18580). `DRISHTI_MODULES_DEV=true` loads the module folders under `modules/` as they are; the log says
*modules from the boot: …* and Health marks the module *development (unverified)*. Nothing is built. After a code change rebuild with **Drishti build modules**
and restart. The switch is refused in a cluster and under the `prod` or `production` profile, and never belongs on a shared server. The terminal equivalent is
`python3 tools/drishti.py start server --modules-dev`.

### 6.4 Run the tests from the IDE

- **Java (IntelliJ):** a class or method from its gutter icon, a module with **Run 'All Tests'**, everything with **drishti → Lifecycle → verify** in the Maven tool window.
  **Clear the `DRISHTI_*` variables in test configurations** (and in the JUnit template): server tests set their own packs and fail when `DRISHTI_PACKS` and its siblings are set.
  Tests that need a broker or a database start it in Docker (Testcontainers) and are skipped without Docker.
- **Python (PyCharm):** **Settings → Tools → Python Integrated Tools → Default test runner: pytest**, then **Run 'pytest in tests'** on `drishti-console/tests`.
  Browser tests (`*_browser.py`) need `drishti-console/.venv/bin/python -m playwright install chromium` once; tests that start the server jar need it built.
  Both are skipped otherwise.
- Keep local data small: ten thousand trades at most.

### 6.5 The QA profile, end to end (no terminal)

Ports 18580 and 17580 and the git-ignored folder `data/qa/`, so it never disturbs the default stack or `data/`. Start again from scratch by
stopping both and deleting `data/qa/`.

1. IntelliJ: **Drishti build server jar** (`pack make` and `lake build` use the jar).
2. IntelliJ: **Drishti server (QA profile)**; http://127.0.0.1:18580/actuator/health says `{"status":"UP"...}`.
3. PyCharm: **Drishti console (QA profile)**; open http://localhost:17580 and sign in as `drishti-dev-admin` / `drishti-dev-admin123`.
4. *My account → API tokens*: make one and save its text as `data/qa/token.txt`.
5. **QA server smoke** ends `smoke: OK`; **QA doctor** shows no red line.
6. Optional: **QA pack make (from JSON)**, *Parameters*: your sample file (JSON, JSON Lines or `.gz`; or
   `data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz`), `--kind`, `--match` (the field that tells kinds apart, e.g.
   `PRODUCT_TYPE`), `--name`. It writes `data/qa/packs/<name>/` (the pack in `pack/<name>`).
7. **QA lake build (big JSON to Delta)**: `--from` your file named by its business date (or add `--as-of YYYY-MM-DD`), `--kind`/`--key`
   (`trade`, `TRADE_ID`), `--mode overwrite-dates` (a re-run replaces the date), `--partition-by PRODUCT_TYPE`, `--lake data/qa/delta`,
   `--pack data/qa/packs/<name>/pack/<name>` (the folder holding `pack.yaml`), and `--signal http://localhost:18580 --token-file
   $PROJECT_DIR$/data/qa/token.txt`. The last line says `lake-build ok exit=0 ... rows=N`. Keep it to 10,000 trades locally.
8. In the console, **Create → New pack**: upload the same JSON, accept or edit the proposed Sutras, save.
9. **Admin → Packs → Deploy** the archive of step 6 (`data/qa/packs/<name>/bundle/<name>-<version>.tar.gz`) or the wizard's; it writes under
   `data/qa/data/packs/`, never `config/packs`.
10. **Admin → Data loads**: the pack's loads show the table of step 7 (a refused lake folder is outside the QA console's
    `DRISHTI_LAKE_ROOTS`).
11. **Explore**, choose the pack and kind, open a row: the deal's page opens with its Sutra view.

## 7. When a stack is already running

A three-node stack (started from `drishti-stack/` or by `drishti.py start cluster`) holds **18480** (the main node), **18791** and **18792** (its other nodes) and
the console on **17480**. A run configuration on the same port fails with *Port 18480 was already in use* or the console's *address already in use*. Choose one:

1. **Stop the stack:** `python3 tools/drishti.py stop cluster` (Windows `py tools\drishti.py stop cluster`); `python3 tools/drishti.py status` shows nothing running. Then run the default configurations.
2. **Or use the other ports:** **Drishti server (QA profile)** or **(debug-friendly)** on 18580 with **Drishti console (QA profile)** on 17580. They have their own data folder `data/qa/data`,
   so they never touch the stack's.
3. A second server of your own: copy a configuration, add `-Dserver.port=18481` and `-Ddrishti.data.dir=…` (its own data folder, never shared), and start a console with
   `--backend.url=http://127.0.0.1:18481` on another `--server.port`.

Two servers must never share a data folder.

## 8. Troubleshooting

| What you see | Cause and fix |
|---|---|
| `drishti.settings.file names …/dev.ini, which is not a file` (a server) or the same from the console | the settings file is missing: `python3 tools/drishti.py setup`. |
| `the modules beside the jar are not current:` followed by a list, and the server stops | `modules/` is missing a package or holds one older than its module folder or built for another jar, typically after `build jar` alone or a pull. Run **Drishti build all**. Or use **Drishti server (dev switch)**. |
| Health says *no modules*, and `VAR` or a banking function answers *not installed* | the class path was not module `drishti-app`, or `modules/` is empty. Run **Drishti build all**, and start from a shipped configuration or copy one. |
| Maven fails with *invalid target release* or *release version 21 not supported* | the wrong JDK: set the Project SDK (section 2.1 step 2) and **Maven → Runner → JRE** to *Use Project JDK*; the shell's `JAVA_HOME` does not count in the IDE. |
| A Python configuration says *Python interpreter is not configured* | choose `drishti-console/.venv` as the project interpreter (section 2). |
| `lake demo` says the jar is missing | run **Drishti build all** first; `lake demo --engine java` reads the jar in `lib/`. |
| Started with the **▶ in the margin of `DrishtiApplication.java`** and nothing works | never use it: it sets none of the settings above (no `dev.ini`, no packs, no sign-in, no home). Use a shipped configuration. |
| A change to a configuration does not stay | a shipped `.run.xml` is overwritten by a pull. Copy the configuration (Ctrl+D); the copy lives in `.idea/`, which git ignores. Never copy a configuration into the repository. |
| *module not specified*, or the Maven modules vanished | both IDEs were opened on the same folder (section 2). Close both, delete the root's `.idea/`, reopen. |
| After a pull the build behaves oddly | **Build → Rebuild Project** once, after **Drishti build all**. |
| A console runs but the page cannot reach the server | the console's `--backend.url` and the server's port differ (18480 against 18580). A QA console needs the QA server. |
| *Error running 'Drishti build all': Unknown run configuration type PythonConfigurationType*; the Python launchers are under **Unknown** with a red cross; *This configuration cannot be edited* | the Python plugin is not installed: **Settings → Plugins → Marketplace → Python Community Edition** (Community) or **Python** (Ultimate), restart, then the interpreter `drishti-console\.venv\Scripts\python.exe` (section 2.1 step 5). Plugins blocked: `py tools\drishti.py build all` in IntelliJ's terminal |
| A run configuration is missing or shows `<no module>` | the project is not the root `pom.xml`, or the Python plugin is missing: open the root and let the Maven import finish; Python configurations need PyCharm |
| Tests pass in a terminal but fail in IntelliJ | `DRISHTI_*` set in the test configuration: clear them in the JUnit template |

More: [OPERATIONS_GUIDE.md › Troubleshooting](OPERATIONS_GUIDE.md#21-troubleshooting). The terminal equivalent of every step above:
[DEPLOYMENT_AND_QA_GUIDE.md › Start the server and the console](../DEPLOYMENT_AND_QA_GUIDE.md#7-start-the-server-and-the-console).

## 9. Run and debug the command-line tools

The tools (`tools/drishti.py pack make`, `sutra gen`, `data ingest`, `pack check` and the rest; every command is in [CLI_REFERENCE.md](CLI_REFERENCE.md)) are ordinary Python
scripts, so PyCharm runs and debugs them like any other, and the Java `sutra` tool runs from IntelliJ IDEA. The shipped configurations of section 5.4 cover the common ones;
this section makes your own.

### 9.1 A run configuration for one task

**Run → Edit Configurations → + → Python**. The fields are the same for every task; only **Name** and **Parameters** change.

| Field | Value |
|---|---|
| **Script path** (not *Module name*; switch the toggle at the left of the field) | `<repo>/tools/drishti.py` |
| **Parameters** | `--settings $USER_HOME$/drishti-envs/dev.ini` followed by the command, for example `pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force` |
| **Python interpreter** | the `drishti-console/.venv` interpreter (section 2) |
| **Working directory** | the repository root: relative paths in *Parameters* resolve from here |
| **Environment variables** | `PYTHONUNBUFFERED=1`; `JAVA_HOME` may be left out (the tool then looks for a JDK 21 and for the newest `lib/*-exec.jar` of the repository); for server commands `DRISHTI_SERVER=http://localhost:18480` and `DRISHTI_TOKEN` (a personal API token) |

More *Parameters* to start from: `data profile <file>`, `pack regenerate data/build/my-bank/pack/my-bank <file> --dry-run`, `sutra gen <file> --kind trade --match PRODUCT_TYPE --out data/build/sutras`,
`data ingest --from your-new-days/ --pack data/build/my-bank/pack/my-bank --lake data/build/my-bank/data/delta --dry-run`, `pack check data/build/my-bank/pack/my-bank --strict --junit data/build/reports`,
`server packs load my-bank --server http://localhost:18480`, `sutra lint config/packs/my-bank`, `server health`.

Press **Run** (Shift+F10). The output is in the Run window; a non-zero exit code shows as a failed run (1 = a check failed, 2 = usage;
[CLI_REFERENCE.md › Exit codes](CLI_REFERENCE.md#exit-codes)). If it says `no drishti-server-*-exec.jar`, run **Drishti build all** once.

- **Templates.** **Run → Edit Configurations → Edit configuration templates… → Python** sets the defaults of every *new* Python configuration: put the interpreter, the working
  directory (`$PROJECT_DIR$/..` in PyCharm, the repository root) and the environment variables there once. Duplicate a configuration with Ctrl+D for a variant.
- **Secrets.** `DRISHTI_TOKEN` goes in the configuration's own *Environment variables* (kept in `.idea/workspace.xml`, which is not committed), or use `--token-file ~/.drishti-token`
  in *Parameters*. Never put a token in a shared configuration.

### 9.2 Debug a tool

Open `tools/drishti.py` (or `tools/packmake.py`, `tools/sutragen.py`, `tools/ingest_jsonl.py`, `tools/packgen/pack_from_jsonl.py`, `tools/packbundle.py`), click the gutter
to set a breakpoint, and press **Debug** (Shift+F9). Good first breakpoints: `detect_key`, `detect_date` and `make` in `tools/packmake.py` (`pack make`: why a field was chosen,
and the whole flow); `generate` in `tools/sutragen.py` (`sutra gen`: the groups, one per `--match` value); `ingest` in `tools/ingest_jsonl.py` (`data ingest`: the dates and
rows it will write). **Variables** shows the parsed options (`a`) and **Evaluate expression** (Alt+F8) works on them. To stop on an error tick **Run → View Breakpoints →
Python Exception Breakpoints → Any exception**. The Java `sutra` command that `pack check` and `pack make` start is a separate process: Python breakpoints do not stop it (9.4).

### 9.3 Share a configuration with the team

A configuration lives in `.idea/workspace.xml` (personal) unless you tick **Store as project file** in **Edit Configurations**: PyCharm, opened on `drishti-console/`,
then writes `drishti-console/run-configs/<name>.run.xml`, which can be committed. Keep a shared file portable: `$PROJECT_DIR$` is `drishti-console/`, so `$PROJECT_DIR$/..` is the
repository root on every machine; an empty `SDK_HOME` (*Project Default* interpreter) leaves nobody's interpreter path in the file; use `$USER_HOME$` for anything personal and put no
token and no home-directory path in it (`tools/test_run_configs.py` guards the shipped files). Reload with **File → Reload All from Disk** if the project was open already.

### 9.4 The Java `sutra` tool from IntelliJ IDEA

`sutra lint|test|preview|shape|design` is the server's own jar started with `sutra` as its first argument, so IntelliJ can run it and debug the engine while it lints.
**Run → Edit Configurations → + → Application**: main class `com.ash.drishti.server.DrishtiApplication`, *Use classpath of module* `drishti-server`, JRE 21, **Program arguments**
`sutra lint config/packs/my-bank --strict`, **Working directory** the repository root (paths in the arguments are relative to it here, unlike in `drishti.py`, which makes them absolute).
Or **JAR Application** with the exec jar as *Path to JAR*. It prints what the command line prints and ends with the exit code; breakpoints in `drishti-rachana` or
`kernel/drishti-server/.../cli/SutraCli.java` stop as usual.

### 9.5 The tools' own tests

**Run → Edit Configurations → + → Python tests → Unittest**: *Target* `Script path`, the file `tools/test_packmake.py` (or `test_drishti_cli.py`, `test_sutragen.py`,
`test_ingest_jsonl.py`, `test_pack_bundle.py`), working directory the repository root. The tests that run `sutra lint` are skipped until **Drishti build all** has put the jar in `lib/`.
