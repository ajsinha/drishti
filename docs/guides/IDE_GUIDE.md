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

For developers and testers who build and run Drishti from the two IDEs. **IntelliJ IDEA builds Drishti and runs the server with Java
only**: Maven builds the server jar and the modules and installs them, and the server launchers are Java applications; IntelliJ needs no
Python and no Python plugin. **PyCharm, separately, runs the console and the command-line tools**, and prepares the data (the demo
lakes). The repository carries the run configurations of both as files (`run-configs/` for IntelliJ, `drishti-console/run-configs/` for
PyCharm), so nothing is installed and no `.exe` is added. Every configuration uses only `$PROJECT_DIR$` and `$USER_HOME$` paths, so the
same files work on Linux and Windows.

**In one minute.** IntelliJ: open the root `pom.xml`, run **Drishti build all**. Once: make `~/drishti-envs/dev.ini` (section 2.1) and,
in PyCharm (opened on `drishti-console/`), run **Build demo lakes**. Then IntelliJ: **Drishti server (demo lakes)**; PyCharm: **Drishti
console**. Open `http://localhost:17480` and sign in as `drishti-dev-admin` / `drishti-dev-admin123`.

The three parts follow that order: [1. Build](#1-build-intellij-idea-java-only), [2. Prepare the environment](#2-prepare-the-environment),
[3. Run](#3-run). Sections 4 to 7 (every launcher, a running stack, troubleshooting, the command-line tools) serve all three.

## 1. Build (IntelliJ IDEA, Java only)

### 1.1 What you need

| You need | How |
|---|---|
| **A JDK 21** | Linux `/usr/lib/jvm/java-21-openjdk-amd64`, Windows `C:\jdk-21`, macOS `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`. Production runs on 21. |
| **IntelliJ IDEA** (Community or Ultimate) | its bundled Maven builds everything; the repository's `mvnw` is the same Maven for a terminal. |

No Python, no Python plugin and no settings file are needed to build: the Maven launchers start no Drishti process. Two IDEs, two
projects, **never one folder for both**: opened on the same folder they overwrite each other's `.idea`, and IntelliJ then loses the Maven
modules (*module not specified*). If that happened, close both, delete the root's `.idea/` (IDE settings only) and reopen.

### 1.2 Open the root `pom.xml` in IntelliJ IDEA

1. **File → Open**, choose the root `pom.xml`, **Open as Project**. Wait for the Maven import and the indexing; the Maven tool window lists `drishti-app`.
2. **File → Project Structure → Project → SDK**: **Add SDK → JDK…**, choose the JDK 21 folder; **Language level** *SDK default*.
3. **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE**: *Use Project JDK*. Maven in the IDE ignores your shell's `JAVA_HOME`.
4. The drop-down at the top right lists the configurations of `run-configs/` under the folder **Drishti**: the three Maven builds and the
   server launchers (section 4). None of them is a Python configuration.

### 1.3 The Maven build launchers

Open the **Drishti build all** configuration and press **Run** (Shift+F10).

| Run configuration | What it runs (Maven, from the repository root) | Use it when |
|---|---|---|
| **Drishti build all** | `clean package -DskipTests -pl :drishti-app,:drishti-modules -am`: the server jar, installed into `lib/` (`lib/drishti-server-<version>-exec.jar`), and the twelve module packages, packed, checked and installed into `modules/` (`<id>-<version>.dmod` and `modules/first-party.yaml`), stamped for that jar | the first build on a clone, and after every pull: **this is the one to click** |
| **Drishti build server jar** | `clean package -DskipTests -pl :drishti-app -am`: the server jar into `lib/` | you changed only the kernel and the modules are current |
| **Drishti build modules** | `clean package -DskipTests -pl :drishti-modules -am -Pmodules-only`: only the module packages into `modules/`, stamped for the jar already in `lib/` | you changed only module code. It is also the before-launch step of **Drishti server (debug-friendly)**. |

**You should see** Maven's progress and `BUILD SUCCESS`; the build itself installs what it made: the exec jar into `lib/` (any other
server jar there is replaced; `lib/ext`, your own jars, is never touched) and each package into `modules/` through a `.part` file and one
rename, an older version of the same module removed, then `modules/first-party.yaml`. The packer and the conformance checks are the
server's own Java (`module pack`, `module verify`): no Python runs. The jar holds no module, so **`build server jar` alone leaves
`modules/` empty or stale and the server will not start** (section 6). The first build downloads Maven dependencies and takes minutes;
later ones are faster. Every build runs `clean`, so `lib/` never holds a stale jar.

**From a terminal** the same commands are `./mvnw clean package -DskipTests -pl :drishti-app,:drishti-modules -am` (Windows
`mvnw.cmd …`), and `python3 tools/drishti.py build all|jar|modules` runs exactly these Maven commands (`--offline` adds `-o`). To build
without installing anything, add `-Ddrishti.install.skip=true` (`drishti.py build … --no-copy`): the jar stays in
`kernel/drishti-server/target`, the packages in `packaging/dist/target/dmod`.

### 1.4 Maven behind a company network: `PKIX path building failed`

On a company network Maven often stops on its first download with

```
Failed to execute goal org.codehaus.mojo:flatten-maven-plugin:… Could not transfer artifact … from/to central
(https://repo.maven.apache.org/maven2): PKIX path building failed … unable to find valid certification path to requested target
```

The network re-signs HTTPS with the company's own root certificate, which Windows trusts but the JDK does not (the browser opens
`https://repo.maven.apache.org/maven2` fine; Java refuses it). Nothing is wrong with Drishti or with Maven Central.

**The fix on Windows (proven on a company laptop): let Java trust the Windows certificate store.** One VM option, no file change,
no administrator rights:

```
-Djavax.net.ssl.trustStoreType=Windows-ROOT
```

1. IntelliJ: **File → Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → VM Options**: paste the option.
2. The same screen family, **Maven → Importing → VM options for importer**: paste it too (the Maven import downloads as well).
3. **Apply**, then run **Drishti build all** again.
4. A terminal (`mvnw`, `py tools\drishti.py build all`): `setx MAVEN_OPTS "-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, then open a
   new terminal.
5. The server itself, when it reaches outside sources over HTTPS on that network (public data feeds, news): add the same option to the
   server run configuration's **VM options** (on a copy: **Copy Configuration**, so a pull never overwrites it).

Other ways, if your company prefers them:

- **The company's Maven mirror** (Artifactory, Nexus): put the `<mirror>` your team uses in `%USERPROFILE%\.m2\settings.xml`
  (Linux `~/.m2/settings.xml`). IntelliJ reads the same file (**Settings → Build Tools → Maven → User settings file**).
- **Add the company root certificate to the JDK** (needs write access to the JDK folder): export it from the browser's padlock or ask
  the IT team for the `.cer`, then `C:\jdk-21\bin\keytool -importcert -cacerts -alias company-root -file company-root.cer`
  (the store's password is `changeit`).

Then run **Drishti build all** again. Use **Drishti build all** (`clean package`), not Maven's **clean install**: nothing needs the
local repository, and `install` only adds time.

### 1.5 A module of your own

A project with `module.yaml` at its top, under `<Drishti folder>/modules/`, has two ways to show an edit: rebuild the packages with
**Drishti build modules** and restart, or run **Drishti server (dev switch)** (section 3.7), which loads the module folders as they are,
unverified. A third party builds against the SDK folder alone ([DEVELOPER_GUIDE.md › The module SDK](DEVELOPER_GUIDE.md#16-the-module-sdk)):
its module project packs and verifies with the install's server jar (`java -jar lib/drishti-server-<version>-exec.jar module pack|verify`).

## 2. Prepare the environment

What the launchers read before they run: the settings file (every launcher), the console's Python (PyCharm) and the data (the demo
lakes, your own files, the QA folder). Each step says the IDE its launcher lives in.

### 2.1 The settings file `~/drishti-envs/dev.ini`

**Every run configuration passes `dev.ini`** (a Java one as `-Ddrishti.settings.file=$USER_HOME$/drishti-envs/dev.ini`, a Python one as
`--settings $USER_HOME$/drishti-envs/dev.ini`), so it must exist before the first run (Windows `C:\Users\you\drishti-envs\dev.ini`). The
file names this clone in `drishti.home`; a `DRISHTI_HOME` or `drishti.home` that names another clone is refused.

1. From the clone: `python3 tools/drishti.py setup --check` (Windows `py tools\drishti.py setup --check`) lists what is missing and changes nothing.
2. `python3 tools/drishti.py setup` makes it from `config/settings.examples/dev.ini` with this machine's paths filled in, makes the data
   folder and `drishti-console/.venv`. You should see `[made] settings file …/drishti-envs/dev.ini` and `[made] console .venv …`; a file
   already there is never overwritten.
3. **With no Python on the machine** (a server-only machine): copy `config/settings.examples/dev.ini` to `~/drishti-envs/dev.ini` and
   replace `/path/to/drishti` with the clone's folder (forward slashes, also on Windows).

**The folders in `dev.ini`, explained.** The example file has `/path/to/drishti` as a placeholder; each line names a folder on
*your* machine. On a normal developer machine all three are your clone of the repository, and two folders inside it:

| Key | What it is | Normally | Windows example (clone in `C:\Users\you\IdeaProjects\drishti`) |
|---|---|---|---|
| `drishti.home` | **The install folder**: the folder Drishti runs from. It holds `lib/` (the server jar the build puts there), `config/`, `modules/` and `data/`. For a developer it is the clone itself; on a server it is the folder the release was unpacked into. The server refuses to start without it. | the clone | `drishti.home=C:/Users/you/IdeaProjects/drishti` |
| `drishti.data.dir` | **The data folder**: everything Drishti writes while it runs: the demo lakes (`delta/demo`), the user and audit databases, logs, the data inbox (`dataload/inbox`), caches. It is git-ignored. Point it elsewhere (a bigger disk, a folder per test set) and the clone stays clean. | `<home>/data` | `drishti.data.dir=C:/Users/you/IdeaProjects/drishti/data` |
| `drishti.java.home` | **The JDK the Python tools run** (**Build demo lakes**, `lake build --engine java`, `start`): the folder that holds `bin/java`, Java 21 or newer. It beats `DRISHTI_JAVA_HOME` and `JAVA_HOME`, so a machine whose `JAVA_HOME` is Java 8 needs nothing else. The server in IntelliJ does not read it: it runs on the project's JDK. `drishti.py setup` writes it when it finds a Java 21+; otherwise the line is commented in the file. | (commented) | `drishti.java.home=C:/jdk-21` |
| `drishti.modules.home` | **The modules folder**: where the module packages (`banking-….dmod`, `trading-….dmod` …) are. **Drishti build all** writes them there, and the server loads every package it finds there at start. | `<home>/modules` | `drishti.modules.home=C:/Users/you/IdeaProjects/drishti/modules` |

Rules: absolute paths only; forward slashes, also on Windows (or doubled backslashes, `C:\\Users\\…`); no quotes; no trailing
slash. So for a clone at `C:\Users\asinh09\IdeaProjects\drishti-orig` the lines are:

```ini
drishti.home=C:/Users/asinh09/IdeaProjects/drishti-orig
drishti.data.dir=C:/Users/asinh09/IdeaProjects/drishti-orig/data
drishti.modules.home=C:/Users/asinh09/IdeaProjects/drishti-orig/modules
drishti.java.home=C:/Users/asinh09/.jdks/temurin-21
```

Leave the rest of the file as it is: the other keys are optional and **commented out on purpose**, each with its default already in
force. For example `[lake.demo] root=…`: the demo lake is `<drishti.data.dir>/delta/demo` unless you uncomment it to put the lake
somewhere else (another disk, a shared folder); `[server] server.port=…` moves every launcher's port at once. Uncomment a section (its
`[name]` line and its keys) only when you want to change that default. A second clone on the same machine needs its own
settings file (for example `~/drishti-envs/dev-orig.ini`, named in that clone's run configurations), because `drishti.home` names one clone.

What each key does and the other ways to make it: [DEPLOYMENT_AND_QA_GUIDE.md › The settings file](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup).

### 2.2 Python for the console and the tools (PyCharm)

The console and `tools/drishti.py` need Python 3.11 or later with Drishti's packages. Either the console's own environment,
`drishti-console/.venv` (made by `python tools/drishti.py setup`, which installs `drishti-console/requirements.txt`), or a local Python:

Any Python 3.11 or later on the machine works as the interpreter: a python.org install, a company-packaged Python, conda, or the
one your team already uses. Drishti needs only its packages installed in it.

1. Find its path. Windows: `py -0p` lists every installed Python with its path (for example
   `C:\Users\you\AppData\Local\Programs\Python\Python313\python.exe`); Linux and macOS: `which python3`.
2. Install Drishti's packages into it, once, from the clone (a company package index goes in `%APPDATA%\pip\pip.ini`, Linux `~/.config/pip/pip.conf`):

   ```bash
   <that python> -m pip install -r drishti-console/requirements.txt
   <that python> -m pip install -r drishti-console/requirements-polars.txt    # only for tools/pvf
   ```

   No administrator rights: add `--user`.
3. Choose it in PyCharm (section 2.3, step 2). Every Python launcher then uses it: they store no interpreter path of their own.

The command-line tools in `tools/pvf/` use Polars; the console's environment may not carry it: either
`drishti-console/.venv/bin/pip install -r drishti-console/requirements-polars.txt`, or give those scripts another interpreter (Python 3.11
or later with Polars, NumPy and PyYAML) in their own run configuration. The details are in `tools/pvf/README.md`.

### 2.3 PyCharm: open `drishti-console/`

1. **File → Open**, choose the folder `drishti-console/`.
2. **Settings → Project → Python Interpreter → Add Interpreter → Add Local Interpreter → Existing** (or **System Interpreter** for a local
   Python): Linux `…/drishti/drishti-console/.venv/bin/python`, Windows `C:\Users\you\IdeaProjects\drishti\drishti-console\.venv\Scripts\python.exe`.
3. **Project Structure → + Add Content Root**: the repository's `tools/`, marked **Sources** (for reading and completion; the run configurations work without it).
4. Right-click `tests/` → **Mark Directory as → Test Sources Root**.
5. The drop-down lists the configurations of `drishti-console/run-configs/` under the folder **Drishti**: the consoles (the
   distributions' consoles included), the data preparation and the tools (section 4). PyCharm builds nothing: building is IntelliJ's
   (section 1) or the terminal's.

An IntelliJ IDEA Ultimate with the Python plugin may open `drishti-console/` as a second project in the same way; it is optional.

### 2.4 The demo lakes and your own data with the Java loader (PyCharm)

The lakes are built by Python commands that hand the writing to **Drishti's concurrent Java loader**, the one inside the server jar in
`lib/` (several datasets at once, each in a Java process of its own, bounded memory). Python plans; Java writes. There is one
implementation of each, so the launchers live in PyCharm, and IntelliJ only has to build the jar first.

**The demo lakes**

1. IntelliJ: **Drishti build all** (section 1.3), so `lib/` holds the jar the loader runs from.
2. Stop the server that reads the demo lake.
3. PyCharm: **Build demo lakes**. It runs `tools/drishti.py --settings … lake demo --engine java`; `--engine java` is what selects the
   Java loader. **You should see** `lake writer: java (asked for); N datasets at a time`, the tables as they are written, then
   `Demo lake ready: …`.

How many datasets at a time (`--jobs`, `--memory-mb`), which Java (`--java`), the expected output and the time it takes:
[DEPLOYMENT_AND_QA_GUIDE.md › Build the demo lake](../DEPLOYMENT_AND_QA_GUIDE.md#6-build-the-demo-lake). Change them on a copy of the
launcher (**Copy Configuration**, then *Parameters*), so a pull never overwrites your change.

**Your own files**

Any `lake build` launcher (**Load ENDUR file into the lake**, **QA lake build (big JSON to Delta)**) uses the Java loader when its
*Parameters* include **`--engine java`**; without it the Python writer is used. Use the Java loader for big files (it uses every core
within a fixed memory budget). Its options (`--loader-set memory.budget-mb=…`, the heap, S3):
[CLI_REFERENCE.md › The parallel lake loader](CLI_REFERENCE.md#713-the-parallel-lake-loader); a worked example with your own trade
files: [DEPLOYMENT_AND_QA_GUIDE.md › Put the data in place](../DEPLOYMENT_AND_QA_GUIDE.md#122-put-the-data-in-place).

### 2.5 Your own data and the QA folder (PyCharm)

- **Load ENDUR file into the lake**: a real ENDUR file (never committed) into the demo lake; edit the file name in *Parameters* (section 4.3).
- **QA pack make (from JSON)** and **QA lake build (big JSON to Delta)**: a pack and a lake from your own JSON under the git-ignored
  `data/qa/` (section 3.9). Keep local files to ten thousand trades.
- **The QA token**: the QA tools read `data/qa/token.txt`; make the token in the console (**My account → API tokens**) once the QA
  server and console run (section 3.9, step 4).

## 3. Run

The order matters: the lakes are written by the Java loader of the jar, the server reads the lakes, the console talks to the server.

### 3.1 Start the server (IntelliJ)

**Drishti server (demo lakes)**. It is `DrishtiApplication` on the class path of module `drishti-app`, port **18480**,
`-Ddrishti.home=$PROJECT_DIR$`, `-Xmx4g`, offline, sign-in on, the ten demo packs, the development administrator, `DRISHTI_DEMO_ENABLED=false`
(it reads the lakes of 2.4 and generates nothing). **You should see** in the Run window `Started DrishtiApplication in … seconds` and a `resolved paths:`
block that names `dev.ini` as the origin of the data folder. Check: `curl -s localhost:18480/actuator/health` answers `UP`.

If you have not built the lakes, use **Drishti server** instead: the same on 18480 with the packs' small samples (demo on), `-Xmx2g`.

### 3.2 Start the console (PyCharm)

**Drishti console**. It runs `drishti-console/run_drishti_web.py --settings … --server.port=17480 --backend.url=http://127.0.0.1:18480` with sign-in on and the
development secrets. **You should see** `Uvicorn running on http://0.0.0.0:17480`.

### 3.3 Sign in

Open `http://localhost:17480` and sign in as **`drishti-dev-admin`** / **`drishti-dev-admin123`** (the administrator the server seeds in these
configurations; development only). Then **Admin → Health** lists the server and each module as *loaded*. More on signing in, and a token for the command line:
[DEPLOYMENT_AND_QA_GUIDE.md › Sign in](../DEPLOYMENT_AND_QA_GUIDE.md#93-sign-in-and-a-token-for-the-command-line).

### 3.4 A distribution: Risk, Finance or All

A white-labelled instance is a server and a console started with the same distribution
([DEPLOYMENT_AND_QA_GUIDE.md › A white-labelled instance](../DEPLOYMENT_AND_QA_GUIDE.md#77-starting-a-white-labelled-instance)).
In IntelliJ start **Drishti server (All)** (every module; a Risk or Finance server would limit both consoles to its own), then in PyCharm
**Drishti console (Risk)** (port 17480, own temp folder `data/tmp/console-risk`) and **Drishti console (Finance)** (port 17481). Open the
second by another host name (`http://127.0.0.1:17481`) or another browser profile, because browsers share sign-in cookies across the ports
of one host. **Drishti console (All)** is the console for the **All** distribution on 17480. Do not run **Drishti console (All)** and
**(Risk)** together: both use 17480.

### 3.5 Debug the server (IntelliJ)

1. Run **Drishti build all** (or at least **Drishti build modules**).
2. Choose **Drishti server (debug-friendly)** and press **Debug** (Shift+F9). Its before-launch step is the Maven **Drishti build modules**; it listens on **18580**, sign-in is off, and the
   log is at DEBUG.
3. The console to pair with it (PyCharm): copy **Drishti console (QA profile)** (Ctrl+D) and set `DRISHTI_AUTH_ENABLED` to `false` in the copy; the console (17580) then needs no sign-in.
4. Good breakpoints: `com.ash.drishti.engine.ViewPipeline` (a view being built), `com.ash.drishti.rachana.SutraRegistry` (which Sutra was chosen),
   a plugin's `SourcePlugin.fetch(…)`, and the controllers under `com.ash.drishti.server`.
5. While it runs: **Run → Debugging Actions → Reload Changed Classes** reloads method bodies (a changed signature, field or class needs a restart);
   Sutras, packs and connector files reload by themselves; `application.yaml` and the environment need a restart.

### 3.6 Debug the console (PyCharm)

Press **Debug** on **Drishti console**. For a debugger to see each request add the environment variable `DRISHTI_CONSOLE_WORKERS=1` to the configuration
(otherwise a request reaches a worker process the debugger is not in). Breakpoints: the routes in `drishti-console/core/app.py`. Templates and static files
reload with the browser page; Python code needs a restart (Ctrl+F5).

### 3.7 Work on a module (IntelliJ)

Run **Drishti server (dev switch)** (port 18580). `DRISHTI_MODULES_DEV=true` loads the module folders under `modules/` as they are; the log says
*modules from the boot: …* and Health marks the module *development (unverified)*. Nothing is built. After a code change rebuild with **Drishti build modules**
and restart. The switch is refused in a cluster and under the `prod` or `production` profile, and never belongs on a shared server. The terminal equivalent is
`python3 tools/drishti.py start server --modules-dev`.

### 3.8 Run the tests from the IDEs

- **Java (IntelliJ):** a class or method from its gutter icon, a module with **Run 'All Tests'**, everything with **drishti → Lifecycle → verify** in the Maven tool window.
  **Clear the `DRISHTI_*` variables in test configurations** (and in the JUnit template): server tests set their own packs and fail when `DRISHTI_PACKS` and its siblings are set.
  Tests that need a broker or a database start it in Docker (Testcontainers) and are skipped without Docker.
- **Python (PyCharm):** **Settings → Tools → Python Integrated Tools → Default test runner: pytest**, then **Run 'pytest in tests'** on `drishti-console/tests`.
  Browser tests (`*_browser.py`) need `drishti-console/.venv/bin/python -m playwright install chromium` once; tests that start the server jar,
  or pack a module with it, need it built (section 1.3). Both are skipped otherwise.
- Keep local data small: ten thousand trades at most.

### 3.9 The QA profile, end to end (no terminal)

Ports 18580 and 17580 and the git-ignored folder `data/qa/`, so it never disturbs the default stack or `data/`. Start again from scratch by
stopping both and deleting `data/qa/`.

1. IntelliJ: **Drishti build all** (the server needs the packages; `pack make` and `lake build` use the jar).
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

## 4. Every launcher

All run from the repository root. Server configurations are IntelliJ **Application** configurations (`com.ash.drishti.server.DrishtiApplication`,
class path of module `drishti-app`, **Shorten command line: @argfile**); the builds are IntelliJ **Maven** configurations; everything in
PyCharm is a Python configuration of the project interpreter. `dev.ini` is passed by every launcher but the Maven builds. **Ports**: 18480
default server, 17480 default console, 18580 and 17580 the QA pair, 17481 the Finance console.

### 4.1 Servers (IntelliJ)

| Name | What it runs | Port, data folder | Use it when |
|---|---|---|---|
| **Drishti server** | the seven core packs with demo data generated on, sign-in on, `-Xmx2g` | 18480, `drishti.data.dir` of `dev.ini` | a quick look without building lakes |
| **Drishti server (demo lakes)** | the ten packs, demo off, offline, system baskets seeded, `-Xmx4g` | 18480, `dev.ini` | the normal run after **Build demo lakes** |
| **Drishti server (All)** | every module; `DRISHTI_DISTRIBUTION=all`; the seven core packs, `-Xmx2g` | 18480, `dev.ini` | with **Drishti console (All)** or the two distribution consoles |
| **Drishti server (Risk)** | `DRISHTI_DISTRIBUTION=risk` | 18480, `dev.ini` | with **Drishti console (Risk)** |
| **Drishti server (Finance)** | `DRISHTI_DISTRIBUTION=finance` | 18480, `dev.ini` | with **Drishti console (Finance)** |
| **Drishti server (QA profile)** | the seven packs, sign-in on, `-Xmx2g`, its own folder given as `-D` (`-Ddrishti.data.dir=$PROJECT_DIR$/data/qa/data -Dserver.port=18580`; a `-D` beats the settings file) | 18580, `data/qa/data` | QA or a second server beside the first; never touches your data |
| **Drishti server (debug-friendly)** | the QA profile with sign-in **off**, DEBUG logging for `com.ash.drishti`, `-Xmx1g`; **before launch: the Maven Drishti build modules** | 18580, `data/qa/data` | stepping through code (section 3.5) |
| **Drishti server (dev switch)** | the QA profile with sign-in off, DEBUG, and `DRISHTI_MODULES_DEV=true`: module folders load as they are, nothing built | 18580, `data/qa/data` | editing a module and restarting without packing it (section 3.7) |

### 4.2 Consoles (PyCharm)

| Name | What it runs | Port, backend | Use it when |
|---|---|---|---|
| **Drishti console** | `run_drishti_web.py`, sign-in on, development secrets | 17480, server 18480 | the normal console |
| **Drishti console (QA profile)** | the same with `DRISHTI_LAKE_ROOTS=$PROJECT_DIR$/../data/qa/delta` | 17580, server 18580 | with **Drishti server (QA profile)** |
| **Drishti console (All)** | `--distribution=all` | 17480, server 18480 | with **Drishti server (All)** |
| **Drishti console (Risk)** | `--distribution=risk`, temp folder `data/tmp/console-risk` | 17480, server 18480 | Drishti Risk |
| **Drishti console (Finance)** | `--distribution=finance`, temp folder `data/tmp/console-finance` | 17481, server 18480 | Drishti Finance, beside the Risk console |

### 4.3 Build (IntelliJ) and data tools (PyCharm)

| IDE | Name | What it runs | Use it when |
|---|---|---|---|
| IntelliJ | **Drishti build all** | Maven `clean package -DskipTests -pl :drishti-app,:drishti-modules -am` | after a clone or a pull |
| IntelliJ | **Drishti build server jar** | Maven `clean package -DskipTests -pl :drishti-app -am` | the kernel only |
| IntelliJ | **Drishti build modules** | Maven `clean package -DskipTests -pl :drishti-modules -am -Pmodules-only` | module code only |
| PyCharm | **Build demo lakes** | `drishti.py --settings … lake demo --engine java` | after a build, server stopped |
| PyCharm | **Load ENDUR file into the lake** | `… lake build --from $USER_HOME$/drishti-private/endur/trade_endur.2026-10-16.jsonl --pack $PROJECT_DIR$/../modules/banking/packs/trading --connector trade-endur --key TRADE_ID --mode overwrite-dates --lake demo` | you hold a real ENDUR file (never committed); edit the file name |

### 4.4 Command-line tools (PyCharm)

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

The QA end to end, step by step: [3.9](#39-the-qa-profile-end-to-end-no-terminal).
Every command's options: [CLI_REFERENCE.md](CLI_REFERENCE.md).

**Your own configuration.** Never edit a shipped file for yourself: **Copy Configuration** (Ctrl+D) and change the copy. The copy lives in the git-ignored `.idea/`.
By hand: **Run → Edit Configurations → + → Application**, main class `com.ash.drishti.server.DrishtiApplication`, class path of module **`drishti-app`**
(`drishti-server` alone carries no domain module, so every banking function would answer *not installed*), **Shorten command line** *@argfile*, JRE 21,
VM options `-Ddrishti.home=$PROJECT_DIR$ -Ddrishti.settings.file=$USER_HOME$/drishti-envs/dev.ini`, working directory `$PROJECT_DIR$`.

## 5. When a stack is already running

A three-node stack (started from `drishti-stack/` or by `drishti.py start cluster`) holds **18480** (the main node), **18791** and **18792** (its other nodes) and
the console on **17480**. A run configuration on the same port fails with *Port 18480 was already in use* or the console's *address already in use*. Choose one:

1. **Stop the stack:** `python3 tools/drishti.py stop cluster` (Windows `py tools\drishti.py stop cluster`); `python3 tools/drishti.py status` shows nothing running. Then run the default configurations.
2. **Or use the other ports:** **Drishti server (QA profile)** or **(debug-friendly)** on 18580 with **Drishti console (QA profile)** on 17580. They have their own data folder `data/qa/data`,
   so they never touch the stack's.
3. A second server of your own: copy a configuration, add `-Dserver.port=18481` and `-Ddrishti.data.dir=…` (its own data folder, never shared), and start a console with
   `--backend.url=http://127.0.0.1:18481` on another `--server.port`.

Two servers must never share a data folder.

## 6. Troubleshooting

| What you see | Cause and fix |
|---|---|
| `drishti.settings.file names …/dev.ini, which is not a file` (a server) or the same from the console | the settings file is missing: section 2.1. |
| `the modules beside the jar are not current:` followed by a list, and the server stops | `modules/` is missing a package or holds one older than its module folder or built for another jar, typically after **Drishti build server jar** alone or a pull. Run **Drishti build all**. Or use **Drishti server (dev switch)**. |
| Health says *no modules*, and `VAR` or a banking function answers *not installed* | the class path was not module `drishti-app`, or `modules/` is empty. Run **Drishti build all**, and start from a shipped configuration or copy one. |
| Maven fails with *invalid target release* or *release version 21 not supported* | the wrong JDK: set the Project SDK (section 1.2 step 2) and **Maven → Runner → JRE** to *Use Project JDK*; the shell's `JAVA_HOME` does not count in the IDE. |
| Maven: *Could not transfer artifact … from/to central … PKIX path building failed* | the company network re-signs HTTPS: on Windows add `-Djavax.net.ssl.trustStoreType=Windows-ROOT` to Maven's Runner and Importer VM options (section 1.4; or the mirror, or the root certificate in the JDK) |
| Maven: *module pack … refused:* followed by a reason | the packer refused a module folder (a jar `module.yaml` names is missing, a `content.yaml` that leaves out what a pack serves, a file outside the layout): the line names the module and the file; fix it and build again |
| A Python configuration says *Python interpreter is not configured* | PyCharm: choose the interpreter (section 2.3, step 2). |
| `lake demo` says the jar is missing | run **Drishti build all** first; `lake demo --engine java` reads the jar in `lib/`. |
| Started with the **▶ in the margin of `DrishtiApplication.java`** and nothing works | never use it: it sets none of the settings above (no `dev.ini`, no packs, no sign-in, no home). Use a shipped configuration. |
| A change to a configuration does not stay | a shipped `.run.xml` is overwritten by a pull. Copy the configuration (Ctrl+D); the copy lives in `.idea/`, which git ignores. Never copy a configuration into the repository. |
| *module not specified*, or the Maven modules vanished | both IDEs were opened on the same folder (section 1.1). Close both, delete the root's `.idea/`, reopen. |
| After a pull the build behaves oddly | **Build → Rebuild Project** once, after **Drishti build all**. |
| A console runs but the page cannot reach the server | the console's `--backend.url` and the server's port differ (18480 against 18580). A QA console needs the QA server. |
| A PyCharm launcher (**Build demo lakes**, a `lake build`) says Java is too old, or names Java 1.8 from `JAVA_HOME` | Add one line to your `dev.ini` (the file the launcher passes with `--settings`): `drishti.java.home=C:/jdk-21` (the JDK 21 folder; forward slashes). The tools find Java in this order: `--java FILE`, `--java-home DIR`, **`drishti.java.home`** of the settings file, `DRISHTI_JAVA_HOME`, `JAVA_HOME`, `java` on the PATH, so `JAVA_HOME` can stay Java 8 for other programs. (Alternatives: `setx DRISHTI_JAVA_HOME "C:\jdk-21"` and restart PyCharm, or **Edit Configurations → Environment variables** for one launcher.) The folder IntelliJ builds with is shown in IntelliJ's **Project Structure → SDKs** (a downloaded one is under `C:\Users\you\.jdks\`). |
| A run configuration is missing or shows `<no module>` | IntelliJ: the project is not the root `pom.xml`; open the root and let the Maven import finish. The consoles and the tools are PyCharm's (`drishti-console/`). |
| Tests pass in a terminal but fail in IntelliJ | `DRISHTI_*` set in the test configuration: clear them in the JUnit template |

More: [OPERATIONS_GUIDE.md › Troubleshooting](OPERATIONS_GUIDE.md#21-troubleshooting). The terminal equivalent of every step above:
[DEPLOYMENT_AND_QA_GUIDE.md › Start the server and the console](../DEPLOYMENT_AND_QA_GUIDE.md#7-start-the-server-and-the-console).

## 7. Run and debug the command-line tools

The tools (`tools/drishti.py pack make`, `sutra gen`, `data ingest`, `pack check` and the rest; every command is in [CLI_REFERENCE.md](CLI_REFERENCE.md)) are ordinary Python
scripts, so PyCharm runs and debugs them like any other, and the Java `sutra` tool runs from IntelliJ IDEA. The shipped configurations of section 4.4 cover the common ones;
this section makes your own.

### 7.1 A run configuration for one task

**Run → Edit Configurations → + → Python**. The fields are the same for every task; only **Name** and **Parameters** change.

| Field | Value |
|---|---|
| **Script path** (not *Module name*; switch the toggle at the left of the field) | `<repo>/tools/drishti.py` |
| **Parameters** | `--settings $USER_HOME$/drishti-envs/dev.ini` followed by the command, for example `pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force` |
| **Python interpreter** | the project interpreter (section 2.2) |
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

### 7.2 Debug a tool

Open `tools/drishti.py` (or `tools/packmake.py`, `tools/sutragen.py`, `tools/ingest_jsonl.py`, `tools/packgen/pack_from_jsonl.py`, `tools/packbundle.py`), click the gutter
to set a breakpoint, and press **Debug** (Shift+F9). Good first breakpoints: `detect_key`, `detect_date` and `make` in `tools/packmake.py` (`pack make`: why a field was chosen,
and the whole flow); `generate` in `tools/sutragen.py` (`sutra gen`: the groups, one per `--match` value); `ingest` in `tools/ingest_jsonl.py` (`data ingest`: the dates and
rows it will write). **Variables** shows the parsed options (`a`) and **Evaluate expression** (Alt+F8) works on them. To stop on an error tick **Run → View Breakpoints →
Python Exception Breakpoints → Any exception**. The Java `sutra` command that `pack check` and `pack make` start is a separate process: Python breakpoints do not stop it (7.4).

### 7.3 Share a configuration with the team

A configuration lives in `.idea/workspace.xml` (personal) unless you tick **Store as project file** in **Edit Configurations**: PyCharm, opened on `drishti-console/`,
then writes `drishti-console/run-configs/<name>.run.xml`, which can be committed. Keep a shared file portable: `$PROJECT_DIR$` is `drishti-console/`, so `$PROJECT_DIR$/..` is the
repository root on every machine; an empty `SDK_HOME` (*Project Default* interpreter) leaves nobody's interpreter path in the file; use `$USER_HOME$` for anything personal and put no
token and no home-directory path in it (`tools/test_run_configs.py` guards the shipped files). Reload with **File → Reload All from Disk** if the project was open already.

### 7.4 The Java `sutra` tool from IntelliJ IDEA

`sutra lint|test|preview|shape|design` is the server's own jar started with `sutra` as its first argument, so IntelliJ can run it and debug the engine while it lints.
**Run → Edit Configurations → + → Application**: main class `com.ash.drishti.server.DrishtiApplication`, *Use classpath of module* `drishti-server`, JRE 21, **Program arguments**
`sutra lint config/packs/my-bank --strict`, **Working directory** the repository root (paths in the arguments are relative to it here, unlike in `drishti.py`, which makes them absolute).
Or **JAR Application** with the exec jar as *Path to JAR*. It prints what the command line prints and ends with the exit code; breakpoints in `drishti-rachana` or
`kernel/drishti-server/.../cli/SutraCli.java` stop as usual.

### 7.5 The tools' own tests

**Run → Edit Configurations → + → Python tests → Unittest**: *Target* `Script path`, the file `tools/test_packmake.py` (or `test_drishti_cli.py`, `test_sutragen.py`,
`test_ingest_jsonl.py`, `test_pack_bundle.py`), working directory the repository root. The tests that run `sutra lint` are skipped until **Drishti build all** has put the jar in `lib/`.
