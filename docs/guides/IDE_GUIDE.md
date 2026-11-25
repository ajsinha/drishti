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
# IntelliJ IDEA, PyCharm and VS Code: build and run Drishti

VS Code is section 10; the command line without an IDE, section 11. All three IDEs use the platform's one launcher standard: launchers are named `<Product>: <verb> [<what>]` (`Drishti: build all`, `Drishti: run server (demo)`), live in `run-configs/` of the project the IDE opens as ordinary `*.run.xml` files (no `.idea` in the source), and use only Java, Python and Maven (no `.exe`, no shell script). The other repositories of the platform (Rachana, Elements, Lake, Tools) follow the same standard, so what you learn here applies to each: section 9 opens them together.

For developers and testers who build and run Drishti from the two IDEs. **IntelliJ IDEA builds Drishti and runs the server with Java
only**: Maven builds the server jar and the modules and installs them, and the server launchers run that jar (IntelliJ **JAR
Application** configurations on `lib/`); IntelliJ needs no Python and no Python plugin. **PyCharm, separately, runs the console and the command-line tools**, and prepares the data (the demo
lakes). The repository carries the run configurations of both as files, so nothing is installed and no `.exe` is added. **Two IDEs, two
projects:** IntelliJ IDEA opens `drishti` (its `pom.xml`) and shows only the Java launchers, `run-configs/`, in the folders **IntelliJ-drishti-build**, **-run** and **-test**
(the Maven builds, the servers, the tests); PyCharm opens `drishti\drishti-console` and shows only the Python launchers, `drishti-console/run-configs/`,
in the folders **PyCharm-console-setup**, **-data**, **-run** and **-check** (setup, the demo lakes, the consoles, the checks; the install folder is `$PROJECT_DIR$/..`, the interpreter the
project's: the Python you choose, the one you prepared). Every configuration uses only `$PROJECT_DIR$` and `$USER_HOME$` paths, so
the same files work on Linux and Windows.

**Which IDE opens what.**

| IDE | Open this folder | Launcher folders it shows | What they are |
|---|---|---|---|
| IntelliJ IDEA | `drishti` (its `pom.xml`) | **IntelliJ-drishti-build**, **-run**, **-test** | Maven builds, the three server runs, the Maven test run (Java only, no Python plugin needed) |
| PyCharm | `drishti/drishti-console` | **PyCharm-console-setup**, **-data**, **-run**, **-check** | setup, the demo lakes and loader, the consoles, doctor (the Python you prepared) |
| PyCharm | `drishti-platform` (the platform's index repository) | **PyCharm-platform-build**, **PyCharm-platform-test** | `Platform clone all`, `Platform build all`, `Platform make bundle`, `Platform test Python`, `Platform build all (skip tests)` (Python scripts at the repository root) |

Open each folder as its own project; never both IDEs on one folder (*module not specified*, section 6). Where each launcher goes, what it
runs and its command line: section 4.

**One-time setup per IDE.**

- **IntelliJ IDEA:** *File → Project Structure → Project*: SDK = a **JDK 21**, language level 21; *Settings → Build Tools → Maven → Runner → JRE* = *Use Project JDK*.
  Open `pom.xml` as a project. Nothing else (section 1.2).
- **PyCharm:** open `drishti/drishti-console`. When PyCharm offers *Create a new virtual environment*, press **Cancel**: Drishti never makes an
  environment. *Settings → Project → Python Interpreter → Add Interpreter → Existing* and choose **the Python you prepared** (a dedicated
  Python 3.13 with the libraries installed, section 2.2) or your own virtual environment made from it. Then run **Drishti: setup** once.
- IntelliJ may list the PyCharm folders too; without the Python plugin they sit under **Unknown**: ignore them there and run them in PyCharm.

**In one minute.** From the release zip with nothing to edit: [9.5](#95-from-the-release-zip-build-and-run-with-no-hand-editing). From a clone: IntelliJ: open the root `pom.xml`, run **Drishti: build all**. Once: make `~/drishti-envs/dev.ini` (section 2.1) and,
in PyCharm (opened on `drishti-console/`), run **Drishti: build demo lakes**. Then IntelliJ: **Drishti: run server (demo)**; PyCharm: 
**Drishti: run console**. Open `http://localhost:17480` and sign in as `drishti-dev-admin` / `drishti-dev-admin123`. Starting from scratch, or something
does not work: [8. A fresh start, step by step](#8-a-fresh-start-step-by-step-and-how-to-check-each-step) checks every step.

The three parts follow that order: [1. Build](#1-build-intellij-idea-java-only), [2. Prepare the environment](#2-prepare-the-environment),
[3. Run](#3-run). Sections 4 to 7 (every launcher, a running stack, troubleshooting, the command-line tools) serve all three.

## 1. Build (IntelliJ IDEA, Java only)

### 1.1 What you need

| You need | How |
|---|---|
| **A JDK 21** | Linux `/usr/lib/jvm/java-21-openjdk-amd64`, Windows `C:\jdk-21`, macOS `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`. Production runs on 21. |
| **IntelliJ IDEA** (Community or Ultimate) | its bundled Maven builds everything; the repository's `mvnw` is the same Maven for a terminal. |

No Python, no Python plugin and no settings file are needed to build: the Maven launchers start no Drishti process. IntelliJ opens `drishti` (its `run-configs/`: the Java launchers); PyCharm opens `drishti\drishti-console` (section 2.3). If IntelliJ loses the Maven
modules (*module not specified*), close it, delete the root's `.idea/` (IDE settings only) and reopen.

### 1.2 Open the root `pom.xml` in IntelliJ IDEA

1. **File → Open**, choose the root `pom.xml`, **Open as Project**. Wait for the Maven import and the indexing; the Maven tool window lists `drishti-app`.
2. **File → Project Structure → Project → SDK**: **Add SDK → JDK…**, choose the JDK 21 folder; **Language level** *SDK default*.
3. **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE**: *Use Project JDK*. Maven in the IDE ignores your shell's `JAVA_HOME`.
4. The drop-down at the top right lists the configurations of `run-configs/`, in the folders **IntelliJ-drishti-build** (the Maven builds) and **-run** (the servers), and **-test** (section 4). The Python launchers are PyCharm's, in `drishti-console/run-configs/` (section 2.3); no Python plugin is needed.

### 1.3 The Maven build launchers

Open the **Drishti: build all** configuration and press **Run** (Shift+F10).

| Run configuration | What it runs (Maven, from the repository root) | Use it when |
|---|---|---|
| **Drishti: build all** | `clean package -DskipTests -pl :drishti-app,:drishti-modules -am`: the server jar, installed into `lib/` (`lib/drishti-server-<version>-exec.jar`, and the same file as `lib/drishti-server-exec.jar` for the server launchers), and the twelve module packages, packed, checked and installed into `modules/` (`<id>-<version>.dmod` and `modules/first-party.yaml`), stamped for that jar | the first build on a clone, and after every pull: **this is the one to click** |
| **Drishti: build modules** | `clean package -DskipTests -pl :drishti-modules -am -Pmodules-only`: only the module packages into `modules/`, stamped for the jar already in `lib/` | you changed only module code |

Stop the server first: the build replaces the jar and the packages it runs (Windows keeps them locked), and `drishti.py build` says so in
one line while a server of this clone runs. **You should see** Maven's progress and `BUILD SUCCESS`; the build itself installs what it made: the exec jar into `lib/` (any other
server jar there is replaced; `lib/ext`, your own jars, is never touched; `lib/drishti-server-exec.jar` is the same jar under a name that
does not change with the version: a hard link, or a copy on a file system without them, renewed by every build) and each package into `modules/` through a `.part` file and one
rename, an older version of the same module removed, then `modules/first-party.yaml`. The packer and the conformance checks are the
server's own Java (`module pack`, `module verify`): no Python runs. The jar holds no module, so the jar is always built with the
modules (**Drishti: build all**); a new jar with old modules is refused at start (section 6). The first build downloads Maven dependencies and takes minutes;
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
3. **Apply**, then run **Drishti: build all** again.
4. A terminal (`mvnw`, `py -3.13 tools\drishti.py build all`): `setx MAVEN_OPTS "-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, then open a
   new terminal.
5. The server itself, when it reaches outside sources over HTTPS on that network (public data feeds, news): add the same option to the
   server run configuration's **VM options** (on a copy: **Copy Configuration**, so a pull never overwrites it).

Other ways, if your company prefers them:

- **The company's Maven mirror** (Artifactory, Nexus): put the `<mirror>` your team uses in `%USERPROFILE%\.m2\settings.xml`
  (Linux `~/.m2/settings.xml`). IntelliJ reads the same file (**Settings → Build Tools → Maven → User settings file**).
- **Add the company root certificate to the JDK** (needs write access to the JDK folder): export it from the browser's padlock or ask
  the IT team for the `.cer`, then `C:\jdk-21\bin\keytool -importcert -cacerts -alias company-root -file company-root.cer`
  (the store's password is `changeit`).

Then run **Drishti: build all** again. Use **Drishti: build all** (`clean package`), not Maven's **clean install**: nothing needs the
local repository, and `install` only adds time.

### 1.5 A module of your own

A project with `module.yaml` at its top, under `<Drishti folder>/modules/`, has two ways to show an edit: rebuild the packages with
**Drishti: build modules** (or `python tools/drishti.py build modules <id>` for one) and restart the server (section 3.7). A third party builds against the SDK folder alone ([DEVELOPER_GUIDE.md › The module SDK](DEVELOPER_GUIDE.md#16-the-module-sdk)):
its module project packs and verifies with the install's server jar (`java -jar lib/drishti-server-<version>-exec.jar module pack|verify`).

## 2. Prepare the environment

What the launchers read before they run: the settings file (every launcher), the console's Python (PyCharm) and the data (the demo
lakes, your own files, the QA folder). Each step says the IDE its launcher lives in.

### 2.1 The settings file `~/drishti-envs/dev.ini`

**Every run configuration passes `dev.ini`** (a Java one as `-Ddrishti.settings.file=$USER_HOME$/drishti-envs/dev.ini,…`, a Python one as
`--settings $USER_HOME$/drishti-envs/dev.ini`), so it must exist before the first run (Windows `C:\Users\you\drishti-envs\dev.ini`). The
file names this clone in `drishti.home`; a `drishti.home` or `DRISHTI_HOME` that names another clone is a WARNING at start (the server,
the console and the tools still run, the tools on this clone), and `setup` moves such a `dev.ini` aside (below).

**A launcher names a profile after it** (`config/profiles/demo.ini`, `samples.ini` or `qa.ini`, committed): what makes the demo, the
built-in samples or the QA pair, the same file `drishti.py start server --demo` (or `drishti.py --profile qa start server`) reads, so the IDE and the terminal start
the same server. **A server launcher also names this machine's JVM options**, `~/drishti-envs/jvm-<profile>.args` (`@…` in its VM
options): the options `drishti.py start server` uses, with your data folder in them (the temp folder, heap dumps and the GC log follow
`drishti.data.dir`). `setup` writes them. To put the data folder elsewhere, run `python tools/drishti.py setup --data-dir <folder>`: it
records the folder in `dev.ini` (the old file moved into `drishti-envs/replaced-<date>`), makes its layout and rewrites the JVM options.
A later plain `setup` keeps that `dev.ini` (its `drishti.home` is this clone) and makes nothing outside the clone. After editing
`drishti.data.dir` by hand, `python tools/drishti.py --settings ~/drishti-envs/dev.ini launchers --machine` rewrites the JVM options
(a file that differs is moved into the same kind of dated folder first).

1. From the clone: `python3 tools/drishti.py setup --check` (Windows `py -3.13 tools\drishti.py setup --check`) lists what is missing and changes nothing.
2. `python3 tools/drishti.py setup` makes it from `config/settings.examples/dev.ini` with this machine's paths filled in, makes the data
   folder, writes the JVM arguments files, and only CHECKS the libraries of **the Python it runs with**: when something is missing it prints the exact pip command for you to run
   ([2.2](#22-python-for-the-console-and-the-tools-pycharm)), and when all is in order it prints "Drishti is ready." Drishti installs no Python
   package, and makes no virtual environment: the Python is yours. `--check` changes nothing.
   In PyCharm, no terminal is needed: **Drishti: setup** (section 4.3) runs it on the project interpreter. You should see `[made] settings file …/drishti-envs/dev.ini`,
   `[made] JVM options … jvm-demo.args, jvm-qa.args, jvm-samples.args`, `[made] libraries …` (or `[  ok]`) and `drishti.python=<that Python>`;
   while a `[FAIL]` or `[todo]` remains, the next steps name the fix first and never `start`. A `dev.ini` already there that names
   this checkout is kept as it is (your own lines too, and a data folder elsewhere), and setup never overwrites a file (it writes only
   `drishti.python`: when absent, or when it names a missing Python). **An old `dev.ini` of another folder** (its `drishti.home` names another copy) and a
   JVM arguments file that differs: setup **moves** them into a dated folder of the settings folder and writes fresh ones for this
   checkout, in one line: *`[move] old settings  dev.ini moved to …\drishti-envs\replaced-2026-11-23-1630  (it named drishti.home=…)`*.
   Nothing is lost and nothing is made in the other folder; there is nothing to rename or delete by hand. `--check` says what it would
   move and moves nothing. A `DRISHTI_HOME`, `DRISHTI_DATA_DIR` … variable from an older guide that names another folder is shown as a
   `[WARN]` (*ignored: your settings file wins; you may delete it in Environment Variables*) and never used.
3. **With no Python on the machine** (a server-only machine): copy `config/settings.examples/dev.ini` to `~/drishti-envs/dev.ini` and
   replace `/path/to/drishti` with the clone's folder (forward slashes, also on Windows).

**The folders in `dev.ini`, explained.** The example file has `/path/to/drishti` as a placeholder; each line names a folder on
*your* machine. On a normal developer machine all three are your clone of the repository, and two folders inside it:

| Key | What it is | Normally | Windows example (clone in `C:\Users\you\IdeaProjects\drishti-platform\drishti`) |
|---|---|---|---|
| `drishti.home` | **The install folder**: the folder Drishti runs from. It holds `lib/` (the server jar the build puts there), `config/`, `modules/` and `data/`. For a developer it is the clone itself; on a server it is the folder the release was unpacked into. The server refuses to start without it. | the clone | `drishti.home=C:/Users/you/IdeaProjects/drishti-platform/drishti` |
| `drishti.data.dir` | **The data folder**: everything Drishti writes while it runs: the demo lakes (`delta/demo`), the user and audit databases, logs, the data inbox (`dataload/inbox`), caches. It is git-ignored. Point it elsewhere (a bigger disk, a folder per test set) and the clone stays clean. | `<home>/data` | `drishti.data.dir=C:/Users/you/IdeaProjects/drishti-platform/drishti/data` |
| `drishti.java.home` | **The JDK the Python tools run** (**Drishti: build demo lakes**, `lake build --engine java`, `start`): the folder that holds `bin/java`, Java 21 or newer. It beats `DRISHTI_JAVA_HOME` and `JAVA_HOME`, so a machine whose `JAVA_HOME` is Java 8 needs nothing else. The server in IntelliJ does not read it: it runs on the project's JDK. `drishti.py setup` writes it when it finds a Java 21+; otherwise the line is commented in the file. | (commented) | `drishti.java.home=C:/jdk-21` |
| `drishti.modules.home` | **The modules folder**: where the module packages (`banking-….dmod`, `trading-….dmod` …) are. **Drishti: build all** writes them there, and the server loads every package it finds there at start. | `<home>/modules` | `drishti.modules.home=C:/Users/you/IdeaProjects/drishti-platform/drishti/modules` |

Rules: absolute paths only; forward slashes, also on Windows (or doubled backslashes, `C:\\Users\\…`); no quotes; no trailing
slash. So for a clone at `C:\Users\<you>\IdeaProjects\drishti-platform\drishti` the lines are:

```ini
drishti.home=C:/Users/<you>/IdeaProjects/drishti-platform/drishti
drishti.data.dir=C:/Users/<you>/IdeaProjects/drishti-platform/drishti/data
drishti.modules.home=C:/Users/<you>/IdeaProjects/drishti-platform/drishti/modules
drishti.java.home=C:/Users/<you>/.jdks/temurin-21
```

Leave the rest of the file as it is: the other keys are optional and **commented out on purpose**, each with its default already in
force. For example `[lake.demo] root=…`: the demo lake is `<drishti.data.dir>/delta/demo` unless you uncomment it to put the lake
somewhere else (another disk, a shared folder); `[server] server.port=…` moves the server's port, the console's server address and the
tools' `--server` default together. Uncomment a section (its
`[name]` line and its keys) only when you want to change that default. A second clone on the same machine needs its own
settings file (for example `~/drishti-envs/dev-second.ini`, named in that clone's run configurations), because `drishti.home` names one clone.

What each key does and the other ways to make it: [DEPLOYMENT_AND_QA_GUIDE.md › The settings file](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup).

### 2.2 Python for the console and the tools (PyCharm)

The console and `tools/drishti.py` need Python 3.13 with Drishti's packages. **One Python, yours**: the Python you run Drishti with
(a dedicated one, a virtual environment you made yourself, or the system's, not Debian/Ubuntu's own) must have the libraries; you install them from the offline wheel pack, and setup checks them. Drishti installs no package and makes no virtual environment.

Python 3.13 (the wheel packs are built for it) on the machine works as the interpreter: a python.org install, a company-packaged Python, conda, or the
one your team already uses. Drishti needs only its packages installed in it.

1. Find its path. Windows: `py -0p` lists every installed Python with its path (for example
   `C:\Users\you\AppData\Local\Programs\Python\Python313\python.exe`); Linux and macOS: `which python3`.
2. Drishti Lake's pinned Python wheel is in `lib/wheels` when you start from the platform source zip or the wheel pack; only a git clone builds it (see [QUICKSTART_DEVELOPERS.md section 1.3](../QUICKSTART_DEVELOPERS.md#13-the-consoles-environment); `python tools/lake/lakewheel.py` checks it). Then install the libraries yourself, once, with that Python. Unzip the offline wheel pack into `%USERPROFILE%\drishti-wheels` (`~/drishti-wheels`). Windows:
   the two pip commands from `drishti-console` ([DEPLOYMENT_AND_QA_GUIDE.md 1.6](../DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup); your own Python works too). Then, from the clone, run `<that python> tools/drishti.py setup`: it writes the settings, the JVM files and `drishti.python` and CHECKS the libraries ("Drishti is ready."); `setup --check` names every library that is missing or has the wrong version (exit 1) and changes nothing. `pip install -r drishti-console/requirements-polars.txt` is only for `tools/mrloader`.

   Drishti never runs pip: not `setup`, not `start`, not the server's compute environments. When a library is missing, setup prints that same pip command
   for the Python that ran it. `setup` needs nothing installed beforehand, not even PyYAML. Setup writes the Python it ran with as `drishti.python` in `dev.ini` (when absent, or when it names a missing Python;
   another Python you named is kept), so `start console`, the cluster, the feed schedules and the server's Python work (the data
   feeds' Run now, the compute pool, the pivot Python functions) run in the same Python; `DRISHTI_PYTHON` overrides it. On Windows pip uses
   the Windows certificate store, so it works behind a company proxy (a company package index goes in `%APPDATA%\pip\pip.ini`, Linux
   `~/.config/pip/pip.conf`, for a pip command without `--no-index`). No administrator rights: add `--user` to a plain `pip`, or use a Python of your own.

   The check covers `requirements.txt` and `config/compute/locks/python.lock` (the server's compute pool needs the lock's numpy, pandas, pyarrow, scipy ...; the wheel pack carries both): the server's
   compute environments run with `drishti.python` as it is, and a missing library is an error naming it and the pip command. In PyCharm: the launcher **Drishti: setup**.
3. Choose it in PyCharm (section 2.3, step 2). Every Python launcher then uses it: they store no interpreter path of their own.

The command-line tools in `tools/mrloader/` use Polars; the one Python may not carry it: either
`python3 -m pip install -r drishti-console/requirements-polars.txt` into it, or give those scripts another interpreter (Python 3.13
or later with Polars, NumPy and PyYAML) in their own run configuration. The details are in `tools/mrloader/README.md`.

### 2.3 PyCharm: open `drishti\drishti-console`

1. **File → Open**, choose the `drishti-console` folder of the clone (`C:\Users\you\IdeaProjects\drishti-platform\drishti\drishti-console`):
   its launchers are in `drishti-console/run-configs/` and reach the install folder as `$PROJECT_DIR$/..`.
2. **Settings → Project → Python Interpreter**: choose your Python, the one you prepared (**Add Interpreter → Add Local Interpreter →
   System Interpreter**, or an environment you made yourself: Windows `C:\Users\you\AppData\Local\Programs\Python\Python313\python.exe`,
   Linux `/opt/python3.13/bin/python3.13`). When PyCharm offers to create a virtual environment, **Cancel**: the
   launchers use whatever interpreter you choose here and name none of their own. Install the libraries into that interpreter with the two pip commands above, then run **Drishti: setup** (or `py -3.13 tools\drishti.py setup`), which makes the settings and checks the libraries. PyCharm's *Package requirement ... is not satisfied*
   banner means the same thing: run **Drishti: setup**. An old `dev.ini` or JVM arguments file from another copy is moved into a dated
   folder in `drishti-envs` (`replaced-<date>`) and a fresh one written (section 2.1): nothing to do by hand. The launchers use the project's interpreter; the server uses `drishti.python` of
   `dev.ini` (written by setup), else `py -3.13` (Windows) or `python3`, or the one `DRISHTI_PYTHON` names. `start console` and `start all`
   run the same Python and never install anything: one lacking a library is refused with the list and the `setup` line.
   Running `run_drishti_web.py` with no
   arguments (*Run current file*) is the same as **Drishti: run console**: `~/drishti-envs/dev.ini` with the demo profile and the development
   secrets of `drishti.py start all --demo`, each said in one line.
3. **Project Structure → + Add Content Root**: `../tools`, marked **Sources** (for reading and completion; the run configurations work without it).
4. Right-click `tests/` → **Mark Directory as → Test Sources Root**.
5. The drop-down lists the configurations of `drishti-console/run-configs/`, in the folders **PyCharm-console-setup**, **-data**, **-run**, **-check** (setup, check setup, the demo lakes, your own file, the consoles, the distributions' included, the acceptance check, the environment) (section 4). PyCharm builds nothing: building is IntelliJ's
   (section 1) or the terminal's.

### 2.4 The demo lakes and your own data with the Java loader (PyCharm)

The lakes are built by Python commands that hand the writing to **Drishti's concurrent Java loader**, the one inside the server jar in
`lib/` (several datasets at once, each in a Java process of its own, bounded memory). Python plans; Java writes. There is one
implementation of each, so the launchers live in PyCharm, and IntelliJ only has to build the jar first.

**The demo lakes**

1. IntelliJ: **Drishti: build all** (section 1.3), so `lib/` holds the jar the loader runs from.
2. Stop the server that reads the demo lake.
3. PyCharm: **Drishti: build demo lakes**. It runs `tools/drishti.py --settings … lake demo --engine java`; `--engine java` is what selects the
   Java loader. **You should see** `lake writer: java (asked for); N datasets at a time`, the tables as they are written, then
   `Demo lake ready: …`.

How many datasets at a time (`--jobs`, `--memory-mb`), which Java (`--java`), the expected output and the time it takes:
[DEPLOYMENT_AND_QA_GUIDE.md › Build the demo lake](../DEPLOYMENT_AND_QA_GUIDE.md#6-build-the-demo-lake). Change them on a copy of the
launcher (**Copy Configuration**, then *Parameters*), so a pull never overwrites your change.

**Your own files**

**Drishti: run loader (file into the lake)** asks for the file when it runs: PyCharm shows a box (its `$Prompt$` macro in *Parameters*,
`--from "$Prompt$"`); type the full path of your JSONL, `.gz`, `.zip` or `.tar.gz` file. No file is named in the launcher, so there is nothing
to edit. To use the Java loader for it, copy the launcher (Ctrl+D) and add **`--engine java`** to the copy's *Parameters* (it uses the Java loader when its
*Parameters* include **`--engine java`**; without it the Python writer is used). Use the Java loader for big files (it uses every core
within a fixed memory budget). Its options (`--loader-set memory.budget-mb=…`, the heap, S3):
[CLI_REFERENCE.md › The parallel lake loader](CLI_REFERENCE.md#713-the-parallel-lake-loader); a worked example with your own trade
files: [DEPLOYMENT_AND_QA_GUIDE.md › Put the data in place](../DEPLOYMENT_AND_QA_GUIDE.md#122-put-the-data-in-place).

### 2.5 Your own data and the QA folder (PyCharm)

- **Drishti: run loader (file into the lake)**: one file of yours (never committed) into the demo lake, by its kind; copy the launcher and name the file
  in *Parameters* (section 4).
- A pack or a lake of your own JSON for the QA server: the `pack make` and `lake build` commands of section 3.9, from a terminal or a
  copy of **Drishti: run loader (file into the lake)**. Keep local files to ten thousand trades.

## 3. Run

The order matters: the lakes are written by the Java loader of the jar, the server reads the lakes, the console talks to the server.

### 3.1 Start the server (IntelliJ)

**Drishti: run server (demo)**. It is a **JAR Application** on `lib/drishti-server-exec.jar`, the jar **Drishti: build all** made and the
modules in `modules/` are stamped for (section 4), port **18480**, with `dev.ini` and the demo profile `config/profiles/demo.ini`: offline,
sign-in on, the packs of every module that is on plus us-equities, gleif and funds, the lenses of the modules that are on (Risk, Trading,
Market, World, Research), the data inbox on, the demo source off (it reads the lakes of 2.4 and generates nothing), a 4 GB heap; the
development administrator. `python tools/drishti.py start server --demo` starts the same server. **You should see** in the Run window `Started DrishtiApplication in … seconds` and a `resolved paths:`
block that names `dev.ini` as the origin of the data folder. Check: `curl -s localhost:18480/actuator/health` answers `UP`. Nothing is
compiled before the launch: after a change to Java code run **Drishti: build all** (with the server stopped: Windows keeps a running jar
locked) and start again.

If you have not built the lakes, use **Drishti: run server (built-in samples)** instead: the samples profile on 18480, the packs' small samples (demo on), a 2 GB heap.

### 3.2 Start the console (PyCharm)

**Drishti: run console**. It runs `drishti-console/run_drishti_web.py --settings …/dev.ini --profile demo`: port 17480 and the server on 18480
(the defaults), sign-in on (the profile's `[console.auth]`), the development secrets. **You should see** `Uvicorn running on http://0.0.0.0:17480`.

### 3.3 Sign in

Open `http://localhost:17480` and sign in as **`drishti-dev-admin`** / **`drishti-dev-admin123`** (the administrator the server seeds in these
configurations; development only). Then **Admin → Health** lists the server and each module as *loaded*. More on signing in, and a token for the command line:
[DEPLOYMENT_AND_QA_GUIDE.md › Sign in](../DEPLOYMENT_AND_QA_GUIDE.md#93-sign-in-and-a-token-for-the-command-line).

### 3.4 A distribution: Risk or Finance

A white-labelled instance is a server and a console started with the same distribution
([DEPLOYMENT_AND_QA_GUIDE.md › A white-labelled instance](../DEPLOYMENT_AND_QA_GUIDE.md#77-starting-a-white-labelled-instance)).
In IntelliJ start **Drishti: run server (demo)** (every module: the All distribution is the default, and a Risk or Finance server would
limit both consoles to its own), then in PyCharm **Drishti: run console (Risk)** (port 17480, its own temp folder `<data>/tmp/console-risk`) and
**Drishti: run console (Finance)** (port 17481, `<data>/tmp/console-finance`). Open the second by another host name (`http://127.0.0.1:17481`)
or another browser profile, because browsers share sign-in cookies across the ports of one host. **Drishti: run console** is the console of
the All distribution on 17480: do not run it and **(Risk)** together. A server limited to one distribution is the settings line
`drishti.distribution=risk` in a settings file of your own named after the profile (section 2.1).

### 3.5 Debug the server (IntelliJ)

1. Run **Drishti: build all** after your last change to kernel code: the debugger runs the jar in `lib/`, so a breakpoint matches the source
   only when the jar was built from it.
2. Choose any server launcher, **Drishti: run server (demo)** for example, and press **Debug** (Shift+F9). Every server launcher debugs
   the same way: a JAR Application starts the jar with the debugger attached, and IntelliJ finds the source of each class
   in the project (**Edit Configurations → Search sources using module's classpath**: leave it at *&lt;whole project&gt;*, so the kernel and
   the modules' sources, `modules/*/java`, are both found). No *attach sources* is needed; a class from a library shows IntelliJ's
   decompiled view, or the library's sources after **Download Sources** in the Maven tool window.
3. The console to pair with it (PyCharm): **Drishti: run console** (or **Drishti: run console (QA, port 17580)** for the QA server); sign in as in 3.3.
4. Good breakpoints: `com.ash.drishti.engine.ViewPipeline` (a view being built), `com.ash.drishti.rachana.SutraRegistry` (which Sutra was chosen),
   a plugin's `SourcePlugin.fetch(…)`, and the controllers under `com.ash.drishti.server`.
5. While it runs: **Run → Debugging Actions → Reload Changed Classes** reloads method bodies (a changed signature, field or class needs a restart);
   Sutras, packs and connector files reload by themselves; `application.yaml` and the environment need a restart.

### 3.6 Debug the console (PyCharm)

Press **Debug** on **Drishti: run console**. For a debugger to see each request add the environment variable `DRISHTI_CONSOLE_WORKERS=1` to the configuration
(otherwise a request reaches a worker process the debugger is not in). Breakpoints: the routes in `drishti-console/core/app.py`. Templates and static files
reload with the browser page; Python code needs a restart (Ctrl+F5).

### 3.7 Work on a module (IntelliJ)

**A shipped module** (banking, trading, the source systems …): change its code, run **Drishti: build modules** (the packages, stamped for
the jar in `lib/`) and restart the server. From a terminal, `python tools/drishti.py build modules banking` builds one module.

**A module of your own** works the same way: put its project under `modules/`, run **Drishti: build modules** and restart. A module is
always loaded from its verified package, never from its folder.

### 3.8 Run the tests from the IDEs

- **Java (IntelliJ):** a class or method from its gutter icon, a module with **Run 'All Tests'**, everything with **drishti → Lifecycle → verify** in the Maven tool window.
  **Clear the `DRISHTI_*` variables in test configurations** (and in the JUnit template): server tests set their own packs and fail when `DRISHTI_PACKS` and its siblings are set.
  Tests that need a broker or a database start it in Docker (Testcontainers) and are skipped without Docker.
- **Python (PyCharm):** **Settings → Tools → Python Integrated Tools → Default test runner: pytest**, then **Run 'pytest in tests'** on `drishti-console/tests`.
  Browser tests (`*_browser.py`) need `python3 -m playwright install chromium` once; tests that start the server jar,
  or pack a module with it, need it built (section 1.3). Both are skipped otherwise.
- Keep local data small: ten thousand trades at most.

### 3.9 The QA profile, end to end

Ports 18580 and 17580 and the git-ignored folder `data/qa/`, so it never disturbs the default stack or `data/`. The profile is the
committed settings file `config/profiles/qa.ini`. Start again from scratch by stopping both and deleting `data/qa/`.

1. IntelliJ: **Drishti: build all** (the server needs the packages; `pack make` and `lake build` use the jar).
2. IntelliJ: **Drishti: run server (QA, port 18580)**; http://127.0.0.1:18580/actuator/health says `{"status":"UP"...}`.
3. PyCharm: **Drishti: run console (QA, port 17580)**; open http://localhost:17580 and sign in as `drishti-dev-admin` / `drishti-dev-admin123`.
4. PyCharm: **Drishti: check environment (QA)** shows no red line (without a token it says how to make one: *My account → API tokens*).
5. Optional, from a terminal at the repository root: a pack from your sample, then a lake of it:
   ```
   python tools/drishti.py --settings ~/drishti-envs/dev.ini --profile qa pack make YOUR_SAMPLE.jsonl --kind trade --match PRODUCT_TYPE --name qa-pack --out data/qa/packs/qa-pack
   python tools/drishti.py --settings ~/drishti-envs/dev.ini --profile qa lake build --from trade.2026-07-08.jsonl --kind trade --key TRADE_ID --mode overwrite-dates --lake demo --pack data/qa/packs/qa-pack/pack/qa-pack
   ```
   (`data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz` is a sample to try). Keep it to 10,000 trades locally.
6. In the console, **Create → New pack**: upload the same JSON, accept or edit the proposed Sutras, save.
7. **Admin → Packs → Deploy** the archive of step 5 (`data/qa/packs/<name>/bundle/<name>-<version>.tar.gz`) or the wizard's; it writes under
   `data/qa/data/packs/`, never `config/packs`.
8. **Admin → Data loads**: the pack's loads show the table of step 5.
9. **Explore**, choose the pack and kind, open a row: the deal's page opens with its Sutra view.

## 4. Every launcher

All run from the repository root. Server configurations are IntelliJ **JAR Application** configurations on
`$PROJECT_DIR$/lib/drishti-server-exec.jar`, the jar **Drishti: build all** installed (the same file as `lib/drishti-server-<version>-exec.jar`),
so the server is exactly the build the module packages in `modules/` are stamped for; nothing is compiled before they start. They never
run IntelliJ's compiled classes: those carry no build stamp, and the server would refuse banking (section 6.1). The builds are IntelliJ **Maven** configurations; everything in
PyCharm is a Python configuration of the project interpreter. `dev.ini` is passed by every launcher but the Maven builds, then the
launcher's profile (section 2.1); a server launcher's JVM options are `~/drishti-envs/jvm-<profile>.args`. The launchers are generated from
one table, `tools/launch/launchers.yaml`, by `python tools/drishti.py launchers` (the tables below too), and the drill's gate fails when
a committed launcher differs from it: change the table, never a file of `run-configs/`. **Ports**: 18480
default server, 17480 default console, 18580 and 17580 the QA pair, 17481 the Finance console.

**From the command line.** `py -3.13 tools\drishti.py launchers --list` (Linux and macOS `python3.13 tools/drishti.py`) prints every launcher, its group and the
command it runs; `launchers --run "Drishti: run server (demo)"` runs exactly what the launcher file holds (its folder, command and environment variables), so the IDE
and the command line cannot drift apart.

<!-- generated: launchers begin (python tools/drishti.py launchers) -->

### 4.1 IntelliJ IDEA: open `drishti` (`run-configs/`)

**IntelliJ-drishti-build**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: build all** | Maven `clean package -DskipTests -pl :drishti-app,:drishti-modules -am` | the server jar into `lib/` and every module's package with `first-party.yaml` into `modules/`, stamped for that jar | after a clone or a pull (the one to click) |
| **Drishti: build modules** | Maven `clean package -DskipTests -pl :drishti-modules -am -Pmodules-only` | the module packages only, stamped for the jar already in `lib/` | you changed only module code |

**IntelliJ-drishti-run**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: run server (demo)** | the jar, profile `demo.ini` | the demo lakes, offline, sign-in on, the data inbox on | the normal run, after **Drishti: build demo lakes** |
| **Drishti: run server (built-in samples)** | the jar, profile `samples.ini` | the packs' small built-in samples (generated, ticking), sign-in on | a first look before any lake is built |
| **Drishti: run server (QA, port 18580)** | the jar, profile `qa.ini` | the samples on its own port and data folder (`data/qa/data`), sign-in on | QA, or a second server beside the first |

**IntelliJ-drishti-test**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: test** | Maven `clean verify` | the whole Maven test run of the server, the engines and the modules (long, tens of minutes; each test class also has its own targeted run in the IDE) | before a release, or after a change that crosses modules |

### 4.2 PyCharm: open `drishti\drishti-console` (`drishti-console/run-configs/`)

**PyCharm-console-setup**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: setup** | `drishti.py setup`, `dev.ini` only | prepare this machine: `~/drishti-envs/dev.ini`, the data folder, the JVM options, then the libraries of the project interpreter (the Python that runs this launcher, written as `drishti.python`): CHECKED, never installed; what is missing is one [FAIL] with the pip command you may run yourself (it makes `dev.ini`, so it names none) | after a clone, after a pull that changed `requirements.txt`, or when start says a library is missing |
| **Drishti: check setup** | `drishti.py setup --check`, `dev.ini` only | what setup would do, changing nothing: the settings file, the data folder and the libraries of the project interpreter, name by name | before setup, or to see why a launcher refuses |

**PyCharm-console-data**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: build demo lakes** | `drishti.py lake demo --engine java`, `dev.ini` only | the demo lakes with the Java loader of the jar in `lib/` | after a build, with the server stopped |
| **Drishti: run loader (file into the lake)** | `drishti.py lake build`, `dev.ini` only | one file of yours into the demo lake, by its kind (it asks for the file when it runs) | you hold a file of your own (never committed; the launcher asks for its path) |

**PyCharm-console-run**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: run console** | `run_drishti_web.py`, profile `demo.ini` | the console on 17480 for the server on 18480, sign-in on | with either 18480 server |
| **Drishti: run console (QA, port 17580)** | `run_drishti_web.py`, profile `qa.ini` | the console on 17580 for the QA server on 18580 | with **Drishti: run server (QA, port 18580)** |
| **Drishti: run console (Risk)** | `run_drishti_web.py`, profile `demo.ini`, `--distribution=risk` | the Risk distribution's console on 17480 (temp folder `<data>/tmp/console-risk`) | Drishti Risk, with **Drishti: run server (demo)** |
| **Drishti: run console (Finance)** | `run_drishti_web.py`, profile `demo.ini`, `--distribution=finance --server.port=17481` | the Finance distribution's console on 17481 (temp folder `<data>/tmp/console-finance`) | Drishti Finance, beside the Risk console |

**PyCharm-console-check**

| Name | What it runs | What it is | Use it when |
|---|---|---|---|
| **Drishti: check acceptance** | `drishti.py doctor --acceptance`, profile `demo.ini` | the end-to-end check: signs in to the console on 17480 as the development administrator, asks VAR BANK through it and compares both figures with the release's | after **Drishti: run server (demo)** and **Drishti: run console** are up |
| **Drishti: check environment** | `drishti.py doctor`, profile `demo.ini` | this machine and the server on 18480 (without a token it says how to make one) | something does not start or answer |
| **Drishti: check environment (QA)** | `drishti.py doctor`, profile `qa.ini` | the same for the QA server on 18580 | with the QA pair |

<!-- generated: launchers end -->

### 4.3 The same launchers on the command line

`<py>` is `py -3.13` on Windows and `python3.13` elsewhere; `<dev>` is `~/drishti-envs/dev.ini`, `<envs>` is `~/drishti-envs`. `launchers --list`
on your machine prints the exact commands with your paths; `launchers --run "<name>"` runs one (a prefix that names one is enough;
`--input <value>` answers a launcher that asks, e.g. the file of **Drishti: run loader (file into the lake)**). This table is hand-written from `--list`: when a
launcher changes, change `tools/launch/launchers.yaml` and this table together.

| Group | Launcher | What it does | Equivalent command line |
|---|---|---|---|
| IntelliJ-drishti-build | Drishti: build all | server jar into `lib/`, module packages into `modules/` | `mvnw clean package -DskipTests -pl :drishti-app,:drishti-modules -am` |
| | Drishti: build modules | module packages only | `mvnw clean package -DskipTests -pl :drishti-modules -am -Pmodules-only` |
| IntelliJ-drishti-run | Drishti: run server (demo) | the server on 18480, demo profile | `java @<envs>/jvm-demo.args -Ddrishti.home=<home> -Ddrishti.settings.file=<dev>,<home>/config/profiles/demo.ini -jar lib/drishti-server-exec.jar` |
| | Drishti: run server (built-in samples) | the same with the samples profile | as above with `jvm-samples.args` and `samples.ini` |
| | Drishti: run server (QA, port 18580) | the QA server | as above with `jvm-qa.args` and `qa.ini` |
| IntelliJ-drishti-test | Drishti: test | the whole Maven test run | `mvnw clean verify` |
| PyCharm-console-setup | Drishti: setup | prepare the machine, check the libraries | `<py> tools/drishti.py setup` |
| | Drishti: check setup | the same, changing nothing | `<py> tools/drishti.py setup --check` |
| PyCharm-console-data | Drishti: build demo lakes | the demo lakes with the Java loader | `<py> tools/drishti.py --settings <dev> lake demo --engine java` |
| | Drishti: run loader (file into the lake) | one file of yours into the demo lake | `<py> tools/drishti.py --settings <dev> lake build --from <file> --kind trade --mode overwrite-dates --lake demo` |
| PyCharm-console-run | Drishti: run console | the console on 17480 | `<py> drishti-console/run_drishti_web.py --settings <dev> --profile demo` |
| | Drishti: run console (QA, port 17580) | the QA console | the same with `--profile qa` |
| | Drishti: run console (Risk) | the Risk distribution | the same with `--distribution=risk` |
| | Drishti: run console (Finance) | the Finance distribution on 17481 | the same with `--distribution=finance --server.port=17481` |
| PyCharm-console-check | Drishti: check environment | machine and server on 18480 | `<py> tools/drishti.py --settings <dev> --profile demo doctor` |
| | Drishti: check environment (QA) | the QA server | the same with `--profile qa` |
| | Drishti: check acceptance | demo stack only: VAR BANK through the console | `<py> tools/drishti.py --settings <dev> --profile demo doctor --acceptance` |

The server and console launchers also set `DRISHTI_TOKEN_SECRET` and `DRISHTI_SESSION_SECRET` in their environment; `launchers --run` sets
them as the launcher does.

### 4.4 From the command line

```text
<py> tools/drishti.py launchers --list                                     every launcher: group, name, exact command, folder, environment
<py> tools/drishti.py launchers --run "Drishti: run server (demo)"          runs what the launcher file holds; returns its exit code
<py> tools/drishti.py launchers --run "Drishti: run loader" --input /data/trades.jsonl     a launcher that asks for a value
<py> tools/drishti.py launchers --check                                     exit 1 when a committed launcher differs from launchers.yaml
```

An unknown or ambiguous name is one `[FAIL] launcher "<x>": <why>. Fix: <the --list command>`.

The QA end to end, step by step: [3.9](#39-the-qa-profile-end-to-end).
Every command's options: [CLI_REFERENCE.md](CLI_REFERENCE.md).

**Your own configuration.** Never edit a shipped file for yourself: **Copy Configuration** (Ctrl+D) and change the copy. The copy lives in the git-ignored `.idea/`.
By hand: **Run → Edit Configurations → + → JAR Application**, *Path to JAR* `$PROJECT_DIR$/lib/drishti-server-exec.jar` (or the versioned
`lib/drishti-server-<version>-exec.jar`), JRE 21, VM options copied from **Drishti: run server (demo)** (the arguments file, the home and
the settings files: `"@$USER_HOME$/drishti-envs/jvm-demo.args" "-Ddrishti.home=$PROJECT_DIR$" "-Ddrishti.settings.file=$USER_HOME$/drishti-envs/dev.ini,$PROJECT_DIR$/config/profiles/demo.ini"`),
working directory `$PROJECT_DIR$`, the environment variables copied too, and no *Build* before launch (section 6.1, recipe A). A setting
of your own goes in a settings file of yours named after the profile (`…,demo.ini,$USER_HOME$/drishti-envs/mine.ini`), never in the launcher.

## 5. When a stack is already running

A three-node stack (started by `drishti.py start cluster`) holds **18480** (the main node), **18791** and **18792** (its other nodes) and
the console on **17480**. A run configuration on the same port fails with *Port 18480 was already in use* or the console's *address already in use*. Choose one:

1. **Stop the stack:** `python3 tools/drishti.py stop cluster` (Windows `py -3.13 tools\drishti.py stop cluster`); `python3 tools/drishti.py status` shows nothing running. Then run the default configurations.
   `status` also lists a server or console of this clone started from an IDE, as `server (elsewhere)` with its port: stop it in the IDE.
2. **Or use the other ports:** **Drishti: run server (QA, port 18580)** with **Drishti: run console (QA, port 17580)**. They have their own data folder `data/qa/data`,
   so they never touch the stack's.
3. A second server of your own: copy a configuration, add `-Dserver.port=18481` and `-Ddrishti.data.dir=…` (its own data folder, never shared), and start a console with
   `--backend.url=http://127.0.0.1:18481` on another `--server.port`.

Two servers must never share a data folder.

## 6. Troubleshooting

| What you see | Cause and fix |
|---|---|
| `drishti.settings.file names …/dev.ini, which is not a file` (a server) or the same from the console, or `drishti.home … names another clone` | the settings file is missing or names another folder: recipe F (section 2.1). |
| `the modules beside the jar are not current:` followed by a list, and the server stops | `modules/` is missing a package or holds one older than its module folder or built for another jar, typically after a pull. Run **Drishti: build all**. |
| Admin → Modules shows banking *stale*: *built for kernel 2.9.11.…+2026-…Z, this is an unknown build: install the matching module* (the words of an older server; the current server says *…this is an unknown build (a server started from compiled classes, not from the server jar): start the server from the jar that built the modules…*), the kernel shows as *dev*, and every `VAR` call answers 404 | the server ran IntelliJ's compiled classes (an **Application** configuration, a copy made before this change, or the ▶ in the margin), which carry no build stamp, while banking is stamped for the exact jar **Drishti: build all** made. Start the server from that jar: **recipe A** of section 6.1. |
| Health says *no modules*, banking is missing from Admin → Modules, or `VAR` answers *not installed* | `modules/` is empty or holds no `banking-*.dmod`: run **Drishti: build all** (recipe B). |
| `GET /api/v1/risk/var/…` (or VAR BANK in the console) answers **404** | banking is not loaded: Admin → Modules says why (stale: recipe A; missing: recipe B). |
| A launcher is red, or missing from the list | you opened the wrong folder: IntelliJ opens `drishti` (`pom.xml`), PyCharm `drishti/drishti-console` (table at the top). Reopen; the launchers are read from that folder's `run-configs/`. |
| *no interpreter* in PyCharm | *Settings → Python Interpreter → Add → Existing*: the Python you prepared; Cancel PyCharm's new-environment offer. |
| *Port 18480 was already in use* / console *address already in use* | a stack runs: `python3.13 tools/drishti.py stop cluster`, or use the QA pair (section 5). |
| a `[FAIL] ... Fix: ...` line from setup or doctor | each line carries its fix; run it and run setup again (production machine: [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#31-what-setup-tells-you)). |
| Maven fails with *invalid target release* or *release version 21 not supported* | the wrong JDK: set the Project SDK (section 1.2 step 2) and **Maven → Runner → JRE** to *Use Project JDK*; the shell's `JAVA_HOME` does not count in the IDE. |
| Maven: *Could not transfer artifact … from/to central … PKIX path building failed* | the company network re-signs HTTPS: on Windows add `-Djavax.net.ssl.trustStoreType=Windows-ROOT` to Maven's Runner and Importer VM options (recipe C; section 1.4: or the mirror, or the root certificate in the JDK) |
| IntelliJ shows *Unknown configuration type* for a launcher, asks for the Python plugin, or a PyCharm launcher is missing in IntelliJ | the consoles and the Python tools are PyCharm's (`drishti-console/run-configs/`, the project `drishti\drishti-console`); IntelliJ does not run them: recipe D. |
| Maven: *module pack … refused:* followed by a reason | the packer refused a module folder (a jar `module.yaml` names is missing, a `content.yaml` that leaves out what a pack serves, a file outside the layout): the line names the module and the file; fix it and build again |
| A Python configuration says *Python interpreter is not configured* | PyCharm: choose the interpreter (section 2.3, step 2). |
| `lake demo` says the jar is missing | run **Drishti: build all** first; `lake demo --engine java` reads the jar in `lib/`. |
| Started with the **▶ in the margin of `DrishtiApplication.java`** and nothing works | never use it: it sets none of the settings above (no `dev.ini`, no packs, no sign-in, no home) and runs compiled classes, an unknown build that refuses banking. Use a shipped configuration. |
| A change to a configuration does not stay | a shipped file of `run-configs/` is overwritten by a pull. Copy the configuration (Ctrl+D); the copy lives in `.idea/workspace.xml`, which git ignores. Never copy a configuration into the repository. |
| *module not specified*, or the Maven modules vanished | both IDEs were opened on the same folder (section 1.1). Close both, delete the root's `.idea/`, reopen. |
| After a pull the build behaves oddly | **Build → Rebuild Project** once, after **Drishti: build all**. |
| A console runs but the page cannot reach the server | the console's `--backend.url` and the server's port differ (18480 against 18580). A QA console needs the QA server. |
| A PyCharm launcher (**Drishti: build demo lakes**, a `lake build`) says Java is too old, or names Java 1.8 from `JAVA_HOME` | Recipe E. Add one line to your `dev.ini` (the file the launcher passes with `--settings`): `drishti.java.home=C:/jdk-21` (the JDK 21 folder; forward slashes). The tools find Java in this order: `--java FILE`, `--java-home DIR`, **`drishti.java.home`** of the settings file, `DRISHTI_JAVA_HOME`, `JAVA_HOME`, `java` on the PATH, so `JAVA_HOME` can stay Java 8 for other programs. (Alternatives: `setx DRISHTI_JAVA_HOME "C:\jdk-21"` and restart PyCharm, or **Edit Configurations → Environment variables** for one launcher.) The folder IntelliJ builds with is shown in IntelliJ's **Project Structure → SDKs** (a downloaded one is under `C:\Users\you\.jdks\`). |
| A server launcher says *could not open …/drishti-envs/jvm-demo.args* | this machine's arguments files were never written (or `dev.ini` moved): `py -3.13 tools\drishti.py --settings C:\Users\you\drishti-envs\dev.ini launchers --machine` (or `py -3.13 tools\drishti.py setup`); recipe G. |
| A run configuration is missing or shows `<no module>` | IntelliJ: the project is not the root `pom.xml`; open the root and let the Maven import finish. The consoles and the tools are PyCharm's (`drishti-console/`). |
| Tests pass in a terminal but fail in IntelliJ | `DRISHTI_*` set in the test configuration: clear them in the JUnit template |

### 6.1 Step by step: the fixes

**Recipe A: banking is *stale*, "built for kernel …, this is an unknown build".** The kernel knows its own build from
`META-INF/build-info.properties`, which only Maven writes (the `build-info` goal of `kernel/drishti-server/pom.xml`) and only the jar is
sure to carry; banking, the one module allowed to reach kernel internals, is stamped at **Drishti: build all** with the exact build
(`<version>+<build time>`) of the jar built in the same run and loads on that build only. IntelliJ's own compile (**Build**, the *Make*
before an **Application** configuration) never runs that goal and clears `target/classes` whenever it rebuilds a module (as on the
first compile after Maven's `clean`), so a server started from compiled classes is an *unknown build*, and even when an old file
survives it names a build no package is stamped for: Admin → Modules shows the kernel as *dev* and banking *stale*, and every VaR API answers 404. The fix is to start the jar:

1. Stop the server.
2. IntelliJ: **Drishti: build all**, ending `BUILD SUCCESS` (`lib/` holds `drishti-server-<version>-exec.jar` and `drishti-server-exec.jar`;
   `modules/` the `.dmod` packages and `first-party.yaml`).
3. Start one of the shipped server launchers (**Drishti: run server (demo)**, **Drishti: run server (built-in samples)** …): each is a JAR Application on that jar.
   A copy you made of an older **Application** configuration keeps the old shape: delete it and copy a shipped one again.
4. **By hand**, if you need your own: **Run → Edit Configurations → + → JAR Application**; *Path to JAR*
   `C:\Users\you\IdeaProjects\drishti-platform\drishti\lib\drishti-server-exec.jar` (or `…\lib\drishti-server-<version>-exec.jar`); *VM options* copied
   from **Drishti: run server (demo)** (open it, select the whole field, copy); *Working directory* the clone (`C:\Users\you\IdeaProjects\drishti-platform\drishti`);
   *Environment variables* copied from the same launcher; *JRE* 21; under *Before launch* remove **Build** if it is there; **OK**, **Run**.
5. Check: the start-up log names the modules it loaded; **Admin → Modules** shows banking *ok* and the kernel with its build (the
   version, not *dev*); in the console `VAR BANK <GO>` computes.

The terminal does the same: `python3 tools/drishti.py start server` always runs the jar in `lib/`.

**Recipe B: banking (or every module) is missing.** `modules/` holds no packages. Run **Drishti: build all** (the jar holds no module), check that `modules/` lists twelve `.dmod` files and `first-party.yaml`, and restart the server.

**Recipe C: Maven stops with `PKIX path building failed` (Windows, company network).**

1. IntelliJ: **File → Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → VM Options**:
   `-Djavax.net.ssl.trustStoreType=Windows-ROOT`.
2. **Maven → Importing → VM options for importer**: the same option.
3. **Apply**; run **Drishti: build all** again. A terminal: `setx MAVEN_OPTS "-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, then a new
   terminal. Details and the other ways: section 1.4.

**Recipe D: *Unknown configuration type*, or IntelliJ asks for the Python plugin.** IntelliJ runs the Maven builds and the server JAR Applications of `run-configs/`;
every Python launcher (the folders **PyCharm-console-…**: the consoles, **Drishti: build demo lakes**, the tools) is PyCharm's, in
`drishti-console/run-configs/`.

1. In IntelliJ, delete any Python configuration you copied there (**Edit Configurations**, select, **−**); no plugin is needed.
2. Open `drishti\drishti-console` in PyCharm (section 2.3); choose the interpreter (section 2.3, step 2).
3. The PyCharm drop-down then lists the consoles and the tools under **Drishti**.

**Recipe E: a PyCharm launcher finds Java 1.8 (from `JAVA_HOME`).**

1. Find a JDK 21: IntelliJ's **Project Structure → SDKs** shows its folder (a downloaded one is under `C:\Users\you\.jdks\`).
2. Add to `C:\Users\you\drishti-envs\dev.ini`: `drishti.java.home=C:/Users/<you>/.jdks/temurin-21` (forward slashes).
3. Run the launcher again: it now names that JDK. (Or `setx DRISHTI_JAVA_HOME "C:\jdk-21"` and restart PyCharm.) `JAVA_HOME` can stay 1.8.

**Recipe F: `dev.ini` is missing, or names the wrong folders.**

1. `py -3.13 tools\drishti.py setup --check` lists what is missing; `py -3.13 tools\drishti.py setup` makes `C:\Users\you\drishti-envs\dev.ini`
   (one that names another folder is moved into `drishti-envs\replaced-<date>` and a fresh one written). Without Python: copy `config\settings.examples\dev.ini` there by hand.
2. Open it and check the three folders (section 2.1): `drishti.home` the clone, `drishti.data.dir` `<clone>/data`,
   `drishti.modules.home` `<clone>/modules`; forward slashes, no quotes, no trailing slash; and `drishti.java.home` (recipe E).
3. Start the server again: the `resolved paths:` block of its log names `dev.ini` as the origin of each folder.

**Recipe G: a server launcher says *could not open …/drishti-envs/jvm-demo.args*, or the temp folder or heap dumps are in the old data
folder.** The JVM arguments file of the launcher's profile is missing or was written for another data folder.

1. `py -3.13 tools\drishti.py --settings C:\Users\you\drishti-envs\dev.ini launchers --machine` (or `py -3.13 tools\drishti.py setup`): *You should
   see* `written …\jvm-demo.args`, `jvm-qa.args`, `jvm-samples.args`.
2. Open one: each line is one JVM option, its folders under your `drishti.data.dir` (the QA one under `data/qa/data`).
3. Start the launcher again.

Starting again from scratch, checking each step: [section 8](#8-a-fresh-start-step-by-step-and-how-to-check-each-step).
More: [OPERATIONS_GUIDE.md › Troubleshooting](OPERATIONS_GUIDE.md#21-troubleshooting). The terminal equivalent of every step above:
[DEPLOYMENT_AND_QA_GUIDE.md › Start the server and the console](../DEPLOYMENT_AND_QA_GUIDE.md#7-start-the-server-and-the-console).

## 7. Run and debug the command-line tools

The tools (`tools/drishti.py pack make`, `sutra gen`, `data ingest`, `pack check` and the rest; every command is in [CLI_REFERENCE.md](CLI_REFERENCE.md)) are ordinary Python
scripts, so PyCharm runs and debugs them like any other, and the Java `sutra` tool runs from IntelliJ IDEA. The shipped configurations of section 4.3 cover the common ones;
this section makes your own.

### 7.1 A run configuration for one task

**Run → Edit Configurations → + → Python**. The fields are the same for every task; only **Name** and **Parameters** change.

| Field | Value |
|---|---|
| **Script path** (not *Module name*; switch the toggle at the left of the field) | `<repo>/tools/drishti.py` |
| **Parameters** | `--settings $USER_HOME$/drishti-envs/dev.ini` followed by the command, for example `pack make data/testdata/trades/endur/trade_endur.2026-07-08.jsonl.gz --kind trade --match PRODUCT_TYPE --name my-bank --out data/build/my-bank --force` |
| **Python interpreter** | the project interpreter (section 2.2) |
| **Working directory** | the repository root: relative paths in *Parameters* resolve from here |
| **Environment variables** | `PYTHONUNBUFFERED=1`; `JAVA_HOME` may be left out (the tool then looks for a JDK 21 and for the one `lib/*-exec.jar` of the repository); for server commands `DRISHTI_TOKEN` (a personal API token); the server's address comes from `--profile` (`--profile qa`: 18580) or `--server` |

More *Parameters* to start from: `data profile <file>`, `pack regenerate data/build/my-bank/pack/my-bank <file> --dry-run`, `sutra gen <file> --kind trade --match PRODUCT_TYPE --out data/build/sutras`,
`data ingest --from your-new-days/ --pack data/build/my-bank/pack/my-bank --lake data/build/my-bank/data/delta --dry-run`, `pack check data/build/my-bank/pack/my-bank --strict --junit data/build/reports`,
`server packs load my-bank --server http://localhost:18480`, `sutra lint config/packs/my-bank`, `server health`.

Press **Run** (Shift+F10). The output is in the Run window; a non-zero exit code shows as a failed run (1 = a check failed, 2 = usage;
[CLI_REFERENCE.md › Exit codes](CLI_REFERENCE.md#exit-codes)). If it says `no drishti-server-*-exec.jar`, run **Drishti: build all** once.

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

A configuration lives in `.idea/workspace.xml` (personal) unless you tick **Store as project file** in **Edit Configurations**: PyCharm, opened on `drishti-console`,
then writes `.idea/runConfigurations/<name>.xml` there (git-ignored); move it to `run-configs/<name>.run.xml` to commit it (a name of the standard has no colon in its file name); add it as a row of
`tools/launch/launchers.yaml` to keep it. Keep a shared file portable: `$PROJECT_DIR$` is `drishti-console` (`$PROJECT_DIR$/..` the install folder) on every machine; an empty `SDK_HOME` (*Project Default* interpreter) leaves nobody's interpreter path in the file; use `$USER_HOME$` for anything personal and put no
token and no home-directory path in it (`tools/test_run_configs.py` guards the shipped files). Reload with **File → Reload All from Disk** if the project was open already.

### 7.4 The Java `sutra` tool from IntelliJ IDEA

`sutra lint|test|preview|shape|design` is the server's own jar started with `sutra` as its first argument, so IntelliJ can run it and debug the engine while it lints.
**Run → Edit Configurations → + → Application**: main class `com.ash.drishti.server.DrishtiApplication`, *Use classpath of module* `drishti-server`, JRE 21, **Program arguments**
`sutra lint config/packs/my-bank --strict`, **Working directory** the repository root (paths in the arguments are relative to it here, unlike in `drishti.py`, which makes them absolute).
Or **JAR Application** with `$PROJECT_DIR$/lib/drishti-server-exec.jar` as *Path to JAR*. It prints what the command line prints and ends with the exit code; breakpoints in `drishti-rachana` or
`kernel/drishti-server/.../cli/SutraCli.java` stop as usual.

### 7.5 The tools' own tests

**Run → Edit Configurations → + → Python tests → Unittest**: *Target* `Script path`, the file `tools/test_packmake.py` (or `test_drishti_cli.py`, `test_sutragen.py`,
`test_ingest_jsonl.py`, `test_pack_bundle.py`), working directory the repository root. The tests that run `sutra lint` are skipped until **Drishti: build all** has put the jar in `lib/`.

## 8. A fresh start, step by step (and how to check each step)

The one place to start from scratch on Windows (Linux and macOS: the same steps, `/` paths and `python3`), and to find which step went
wrong. Each step says what you should see; if you do not, the row of section 6 it names says why and what to do.

1. **Get the code.** `git pull` in the clone (for example `C:\Users\you\IdeaProjects\drishti-platform\drishti`). For a clean start also delete `lib\` and
   `modules\*.dmod` (and `modules\first-party.yaml`): the next build makes them again. Keep the module folders (`modules\banking\` …).
2. **IntelliJ: Drishti: build all** (section 1.3). *You should see* `BUILD SUCCESS`; `lib\` holds `drishti-server-<version>-exec.jar`
   and `drishti-server-exec.jar` (the same jar); `modules\` holds twelve `.dmod` packages and `first-party.yaml`. *If not:*
   `PKIX path building failed`: recipe C; *invalid target release*: the JDK (section 6); *module pack … refused*: the line names the file.
3. **Check `C:\Users\you\drishti-envs\`** (section 2.1): run `py -3.13 tools\drishti.py setup` once (again after a pull that changed the
   launchers). *You should see* `dev.ini` with `drishti.home` naming this clone, `drishti.data.dir` `<clone>/data`, `drishti.modules.home`
   `<clone>/modules`, and `drishti.java.home` a JDK 21 folder (forward slashes), and beside it `jvm-demo.args`, `jvm-samples.args` and
   `jvm-qa.args`, and `drishti.python` naming the Python you ran setup with; setup's `libraries` line is `[  ok]` (no virtual environment is made, and nothing is installed), and its next steps end with `start all --demo`. A `[FAIL] libraries`
   line: run the pip commands it prints (the wheel pack unzipped into `C:\Users\you\drishti-wheels`: the two pip commands of section 2.2, `requirements.txt` then `..\config\compute\locks\python.lock`, in `drishti-console`) and run setup again. Only the arguments files missing or naming an old data folder: `py -3.13 tools\drishti.py --settings
   C:\Users\you\drishti-envs\dev.ini launchers --machine` writes them again. *If not:* recipe F; for the Java line, recipe E; a server
   launcher that says *could not open …jvm-demo.args*: recipe G.
4. **PyCharm: Build demo lakes** (section 2.4), with no server running. *You should see* `lake writer: java (asked for)`, the tables,
   then `Demo lake ready: …`. *If not:* Java 1.8 named: recipe E; *the jar is missing*: step 2; a launcher missing or *Unknown
   configuration type*: recipe D.
5. **IntelliJ: Drishti: run server (demo)** (section 3.1). *You should see* `Started DrishtiApplication`, the `settings file …demo.ini`
   lines, and in the start-up log the modules line *Modules: banking, capitalmarkets, economy, …* with no *module refused* after it and no
   *lens … is not offered* ERROR. *If not:* *module refused: banking …
   this is an unknown build*: the server did not run the jar, recipe A; *the modules beside the jar are not current*: step 2 again;
   *dev.ini … not a file*: recipe F; *Port 18480 was already in use*: section 5.
6. **PyCharm: Drishti: run console** (section 3.2, in the project `drishti\drishti-console`). *You should see* `Uvicorn running on http://0.0.0.0:17480`. *If not:* *Python interpreter is not configured*: section
   2.3 step 2 (the Python you ran setup with); *this Python lacks ...* or PyCharm's *Package requirement ... is not satisfied*: **Drishti:
   setup** (step 3); *the server rejects this console's secret* (logged once): start the console with the same settings as the server
   (this launcher, or `drishti.py start all --demo`); *Unknown configuration type*: recipe D.
7. **Sign in and check.** `http://localhost:17480`, `drishti-dev-admin` / `drishti-dev-admin123`; **Admin → Modules**: *You should
   see* all twelve modules *ok* (thirteen rows with core), none *stale*, and the kernel with its version (not *dev*); Home offers **Open
   Drishti Research** beside Risk, Trading, Market and World; **Admin → Data loads** shows the inbox running. Then `VAR BANK <GO>`
   computes. *If not:* banking *stale*: recipe A; banking missing: recipe B; VaR (or `GET /api/v1/risk/var/…`) answers 404: banking
   is not loaded, Admin → Modules says why (recipe A or B).

## 9. The whole platform in one IDE

Drishti is built on four products that live in their own repositories ([QUICKSTART_DEVELOPERS.md section 1](../QUICKSTART_DEVELOPERS.md#1-get-the-code-and-set-up-the-machine)),
side by side under one folder:

```
~/IdeaProjects/drishti-platform/            (Windows C:\Users\<you>\IdeaProjects\drishti-platform\)
    drishti-platform/  drishti/  drishti-rachana/  drishti-elements/  drishti-lake/  drishti-tools/
```

### 9.1 One window per repository, the usual way

Open each repository root as its own project (**File, Open**): in IntelliJ link the repository's `java/pom.xml` in the Maven tool window (Drishti:
the root `pom.xml`); in PyCharm the Python part (`python/` in Rachana and Lake, `drishti-console/` and `tools/` in Drishti) is marked as sources.
Each project shows its own launchers in its own run-configuration folder (**Tools**, **Rachana**, **Lake**, **Elements**, **Drishti**), with the verbs
of the standard: `build all` and `test` everywhere, `install release (tag only)` where a repository publishes jars, `run ...` for programs.
The install order is **tools, Rachana, Lake, then Drishti**, from release tags only; run each `install release (tag only)` launcher on a checkout of the
tag, never on a branch ([QUICKSTART_DEVELOPERS.md section 2](../QUICKSTART_DEVELOPERS.md#2-build) does it in one command).

### 9.2 All repositories in one IntelliJ window

To search, navigate and refactor across repositories, open Drishti and attach the others as modules of the same window:

1. **File, Open** the `drishti/` folder, then **File, Project Structure, Modules, +, Import Module**: choose `drishti-tools/java/pom.xml`, then
   `drishti-rachana/java/pom.xml`, `drishti-lake/java/pom.xml`, each as a **Maven** project (or, in the Maven tool window, **+** and choose the pom).
   Elements, which has no Java, is attached with **Import Module, Create module from existing sources** on its `js/` folder.
2. Set the one project SDK to JDK 21 (section 1.2) and **Maven, Runner, JRE: Use Project JDK**.
3. A change in an attached repository is compiled in the IDE, but Drishti's build reads the product's jar from `~/.m2` (the installed tag), so to run
   Drishti against such a change build the product with its own `build all` launcher into a scratch repository
   (`-Dmaven.repo.local=<folder>`, the product's `BUILD_AND_IDE.md`), never install a branch into `~/.m2`.
4. Each attached repository keeps its own launchers: add its launchers by opening that repository in a second window (9.1) when you need to run
   them, or copy the launcher (Ctrl+D) into this project.

### 9.3 The same in PyCharm

PyCharm: open the `drishti/` root; add the other repositories' Python parts as content roots (**Settings, Project, Project Structure, Add Content
Root**: `drishti-rachana/python`, `drishti-lake/python`), mark each `src` or package folder as sources, and keep one interpreter, the Python you
run Drishti with (its libraries from `setup`), for Drishti; a product with its own requirements names its own in its guide.

### 9.4 Windows

Paths are the Windows form of the layout above; no `.exe` is needed for any of it. `JAVA_HOME` is `C:\jdk-21`, and behind a company network
the Maven runner needs `-Djavax.net.ssl.trustStoreType=Windows-ROOT` (section 1.4). Playwright's browsers for the browser tests install into the
user's profile with `py -3.13 -m playwright install chromium`.

### 9.5 From the release zip: build and run with no hand editing

The platform's own IDE guide, `IDE_GUIDE.md` at the top of the release zip (and of the index repository,
`https://github.com/drishti-platform/drishti-platform/blob/main/IDE_GUIDE.md`), takes a fresh machine from the zip to a running server and
console: one launcher, **`platform-clean-install`**, builds everything without tests (on Windows trusting the Windows certificate store), then
this guide's server and console launchers run Drishti (sections 2.1, 3.1, 2.4, 3.2).

## 10. Visual Studio Code

For developers and testers who work in VS Code. Every component of the Drishti platform (Drishti, Rachana, Lake, Tools, Elements) carries a
**workspace file** at its root, `<product>.code-workspace` (for Drishti, `drishti.code-workspace`), with the same build, test, check and run
actions as the IntelliJ and PyCharm launchers (sections 1 to 9): one **task** per Maven or Python launcher, one **launch** entry
(F5, debuggable) per Java application and per Python program, under the same names. The convention is the platform's launcher standard:
`<Product>: <verb> [<what>]`, with the verbs `build`, `test`, `check`, `install`, `run`, `lint`, `bundle`, `maintain`, `bench`. Nothing here
needs a hidden folder, a shell script or an `.exe`: Java, Python and Maven only, so the same files work on Linux, macOS and Windows.

**In one minute.** Install the extensions (section 10.1), open `drishti.code-workspace` (File, Open Workspace from File), set the JDK path
(section 10.2), then **Terminal, Run Task, `Drishti: build all`**. Once, make `~/drishti-envs/dev.ini` (`python tools/drishti.py setup`, [QUICKSTART_DEVELOPERS.md](../QUICKSTART_DEVELOPERS.md#13-the-consoles-environment)),
then run the tasks **Drishti: build demo lakes**, **Drishti: run server (demo)** and **Drishti: run console**; open `http://localhost:17480`.

### 10.1 What you need

| You need | How |
|---|---|
| **JDK 21** | Linux `/usr/lib/jvm/java-21-openjdk-amd64`, macOS `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`, Windows `C:\jdk-21` |
| **Python 3.13** with the libraries | your two pip commands from the wheel pack in `~/drishti-wheels` (section 2.2: `requirements.txt`, then `config/compute/locks/python.lock`, in `drishti-console`), then `tools/drishti.py setup` checks ([QUICKSTART_DEVELOPERS.md section 1.3](../QUICKSTART_DEVELOPERS.md#13-the-consoles-environment)) |
| **VS Code** with the extensions the workspace recommends | Extension Pack for Java (`vscjava.vscode-java-pack`), Python (`ms-python.python`, `ms-python.debugpy`, `ms-python.vscode-pylance`), YAML (`redhat.vscode-yaml`); VS Code offers them when the workspace opens |
| The platform's other repositories, built and installed | tools, Rachana and Lake from their tags into `~/.m2`, in that order (`build_all.py --release <name>` does it) ([QUICKSTART_DEVELOPERS.md section 2](../QUICKSTART_DEVELOPERS.md#2-build)); their versions: the index repository's [COMPATIBILITY.md](https://github.com/drishti-platform/drishti-platform/blob/main/COMPATIBILITY.md) |

### 10.2 Open a repository

1. **File, Open Workspace from File**, choose `drishti.code-workspace` at the root of the clone (a visible file).
2. The workspace names no machine path: its tasks use `${userHome}` for your home folder, and the Java extension finds the JDK 21 from
   `JAVA_HOME` (set it, or choose it in the Java extension's settings). The Python tasks run `python3` on Linux and macOS and `py -3.13` on
   Windows. **Drishti: run loader (file into the lake)** asks for the file's path in a box. A copy of the workspace file for yourself is fine;
   the shipped one is overwritten by a pull.
3. Choose the Python setup prepared: **Python: Select Interpreter** (the Python 3.13 setup prepared: yours).
4. Wait for the Java extension to import the Maven project (the first import downloads libraries). Behind a company network that re-signs
   HTTPS the import and the build stop with `PKIX path building failed`: put `-Djavax.net.ssl.trustStoreType=Windows-ROOT` in the environment
   variable `MAVEN_OPTS` (`setx MAVEN_OPTS "-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, then a new terminal) and in the Java extension's
   `java.import.maven.vmargs`; the full explanation is [section 1.4](#14-maven-behind-a-company-network-pkix-path-building-failed).

### 10.3 Build, test and run: the tasks

**Terminal, Run Task** (or Ctrl+Shift+B for the default build) lists every task of the workspace under the standard names. The same names exist
in IntelliJ and PyCharm, so a team mixes the three without translating.

| Task | What it does | Use it when |
|---|---|---|
| `Drishti: build all` | Maven `clean package -DskipTests -pl :drishti-app,:drishti-modules -am`: the server jar into `lib/` and every module package into `modules/` | after a clone and after every pull |
| `Drishti: build modules` | only the module packages, stamped for the jar in `lib/` | you changed only module code |
| `Drishti: test` | the Maven tests | before a hand-over |
| `Drishti: build demo lakes` | `drishti.py lake demo`, the demo lakes through Drishti Lake's loader | after a build, with the server stopped |
| `Drishti: run server (demo)` | the server on 18480 on the demo lakes | the normal run |
| `Drishti: run console` | the console on 17480 | with the server |
| `Drishti: check environment` | `drishti.py doctor` | something does not start or answer |

(The workspace file is the authority for the complete list; the names above are those of the standard.) Stop the server before a build: the
build replaces the jar and the packages it runs, and Windows keeps them locked.

### 10.4 Run and debug

**Run and Debug** (Ctrl+Shift+D) lists the launch entries, named like the tasks: pick **Drishti: run server (demo)** and press **F5** to debug
the server (breakpoints in the Java extension, the jar's classes from `lib/`), **Drishti: run console** to debug the console (the Python
debugger, `debugpy`). To start both without a debugger, run their tasks. Both read `~/drishti-envs/dev.ini` and the profile `demo.ini`
([section 2.1](#21-the-settings-file-drishti-envsdevini)); sign in as `drishti-dev-admin` / `drishti-dev-admin123`
(a QA machine's development secrets only).

### 10.5 Several repositories at once

To work across the platform (a change in Drishti that needs one in Lake), use one **multi-root workspace**: **File, Open Workspace from File**
on any one product's workspace, then **File, Add Folder to Workspace** for each of the others under `~/IdeaProjects/drishti-platform/`
(`drishti`, `drishti-rachana`, `drishti-elements`, `drishti-lake`, `drishti-tools`), then **File, Save Workspace As** into
`~/IdeaProjects/drishti-platform/` itself (beside the clones, outside every repository, so it is never committed; the index repository `drishti-platform` also ships a ready `drishti-platform.code-workspace`). The
tasks and launches of the workspaces you did not open are not merged into it; run them from their own workspace (a second VS Code window
costs nothing), and use the multi-root one for searching, reading and editing across repositories. A change in a product repository reaches Drishti only
through a tag installed into `~/.m2` ([QUICKSTART_DEVELOPERS.md section 2](../QUICKSTART_DEVELOPERS.md#2-build)); to try an unreleased change,
build the product with `-Dmaven.repo.local=<scratch folder>` as that product's `BUILD_AND_IDE.md` says, and never install a branch.

### 10.6 Python and JavaScript parts

The Python parts (`drishti-console`, `tools`) run from the console's environment; the workspace's Python tasks set `PYTHONPATH` where a package
needs it. Elements (JavaScript) and Rachana's JavaScript build and test through Maven (`exec-maven-plugin`), so VS Code needs no Node
extension for the tasks; Node 20 or newer is needed only for the Node unit tests.

### 10.7 When it does not work

| You see | Cause and fix |
|---|---|
| the Java extension shows `JavaSE-21` unresolved | the JDK path in the workspace's settings is wrong for this machine: edit it, then **Java: Clean Java Language Server Workspace** |
| `PKIX path building failed` | section 10.2, step 4 |
| a task says `lib/drishti-server-exec.jar` is missing | run `Drishti: build all` first |
| the console cannot import a package | the wrong interpreter: section 10.2, step 3 |
| the server refuses the modules at start (`stamped for another jar`) | a new jar with old modules: run `Drishti: build all`, never the jar alone |

A fresh start, step by step, and every check: [section 8](#8-a-fresh-start-step-by-step-and-how-to-check-each-step).

## 11. Without an IDE

Every launcher is a `tools/drishti.py` command, so the whole path runs from a terminal too (Windows `cmd`, from the `drishti` folder;
Linux and macOS: `python3` and `/`):

```bat
mvnw.cmd -B clean package -DskipTests -Dspotless.check.skip=true      &rem  Drishti: build all
py -3.13 tools\drishti.py setup                                       &rem  Drishti: setup (checks the libraries of this Python)
py -3.13 tools\drishti.py lake demo --engine java                     &rem  Drishti: build demo lakes
py -3.13 tools\drishti.py start all --demo                            &rem  Drishti: run server (demo) + Drishti: run console
py -3.13 tools\drishti.py status
py -3.13 tools\drishti.py stop all
```

No `--settings` is needed: the commands take `%USERPROFILE%\drishti-envs\dev.ini` by itself when it names this folder. The whole
platform from the unzipped release (`py -3.13 build_all.py --bundle . --skip-tests`), what each step shows and the VaR to expect:
[QUICKSTART_DEVELOPERS.md section 3.1](../QUICKSTART_DEVELOPERS.md#31-without-an-ide-every-command-from-the-unzipped-platform-folder).
