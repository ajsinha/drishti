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
# Running Drishti from IntelliJ IDEA and PyCharm

Drishti has two programs, and each has an IDE that suits it:

| Program | Language | IDE | Starts | Listens on |
|---|---|---|---|---|
| **Server** (`drishti-server`) | Java 21 or newer (production runs 21; 25 also supported), Spring Boot 3.5 | IntelliJ IDEA (Community or Ultimate) | `com.ash.drishti.server.DrishtiApplication` | `http://127.0.0.1:18480` |
| **Console** (the UX, `drishti-console/`) | Python 3.11 or newer, FastAPI | PyCharm (Community or Professional) | `drishti-console/run_drishti_web.py` | `http://127.0.0.1:17480` |

Start the server first, then the console: the console calls the server for everything it shows. You can use one IDE for
both (IntelliJ IDEA Ultimate with the Python plugin runs the console too); the settings below are the same.

For the command-line way to build and run, see [GETTING_STARTED.md](GETTING_STARTED.md). For every server and console setting, see
[CONFIGURATION.md](../admin/CONFIGURATION.md).

## 1. Before you open the IDE

| You need | Version | Check |
|---|---|---|
| A JDK (not only a JRE) | **21 or newer**, any vendor: OpenJDK, Oracle, Temurin, Corretto…; Java 21 is the production target | `java -version` and `javac -version` |
| Python | 3.11 or newer | `python3 --version` |
| The console's virtual environment | `drishti-console/.venv` | `ls drishti-console/.venv/bin/python` |
| Docker (only for some tests) | any recent | `docker --version` |

Create the console's virtual environment once, from the repository root (PyCharm can also create it, see §4.2):

```bash
uv venv drishti-console/.venv && uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt -r drishti-console/requirements-test.txt
# without uv:
python3 -m venv drishti-console/.venv && drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt -r drishti-console/requirements-test.txt
```

**Which Java version.** Drishti compiles to Java 21 bytecode, so the same build runs on Java 21, 25 and anything newer.
Java 21 is the production target and the recommended runtime; Java 25 is also tested. On Java 25 only, `-XX:+UseCompactObjectHeaders` saves about 10% heap; Java 21 has no such
flag and refuses to start when it is given, so leave it out there. The build stops on anything older than 21 with
`Drishti needs Java 21 or newer … Maven is running on Java N from <folder>`, naming the Java it actually found.

**After pulling the move to Java 21 bytecode** (from a checkout built when Drishti targeted Java 25 only): rebuild once,
**Build → Rebuild Project** in IntelliJ, or `./mvnw clean` from a terminal. Otherwise old Java 25 class files in
`target/` give `class file version 69.0` errors on Java 21 ([TROUBLESHOOTING.md](TROUBLESHOOTING.md#tests-on-java-21-fail-with-has-been-compiled-by-a-more-recent-version-of-the-java-runtime-class-file-version-690)).

## 2. IntelliJ IDEA: open and build the server

Use an IntelliJ IDEA release that knows your JDK (Java 25 needs IDEA 2025.2 or newer).

1. **File → Open**, choose the repository's root `pom.xml`, then **Open as Project**. IDEA imports every Maven module
   (the server, the engine, the 19 plugins, the testkit, …). Wait for the import and indexing to finish.
2. **File → Project Structure → Project**:
   - **SDK**: your JDK 21 or newer. If it is not listed: **Add SDK → Add JDK…** and choose its folder, for example
     `/usr/lib/jvm/java-21-openjdk-amd64` (Ubuntu OpenJDK), `/usr/lib/jvm/jdk-21-oracle-x64` (Oracle JDK on Linux),
     `C:\Program Files\Java\jdk-21` (Oracle JDK on Windows) or `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`
     (macOS).
   - **Language level**: *SDK default*, or 21 or higher.
3. **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE**: *Use Project JDK*. Maven run from the
   IDE uses this setting, not your shell's `JAVA_HOME`; when it points at an older Java the build stops with the message
   above.
4. **Build → Build Project** (Ctrl+F9). The first build downloads dependencies and takes a few minutes.

To build the runnable jar as well (what `java -jar` and the Docker image use), open the **Maven** tool window and run
**drishti → Lifecycle → package** (skip tests with the *Toggle 'Skip Tests' Mode* button). The jar is
`drishti-server/target/drishti-server-<version>-exec.jar`.

## 3. IntelliJ IDEA: run and debug the server

### 3.1 The run configurations (already in the repository)

Two projects, so the two IDEs never share one `.idea` folder (opened on the same folder they overwrite each other's settings and
IntelliJ loses the Maven modules: "module not specified"): **IntelliJ opens the repository root**, **PyCharm opens `drishti-console/`**
(section 4.1). The Java run configurations are in `run-configs/` (IntelliJ's), the Python ones in `drishti-console/run-configs/`
(PyCharm's). IntelliJ shows its own in the drop-down at the top right as soon as the project is open (folder **Drishti**); there is
nothing to type. Every one uses `$PROJECT_DIR$` (the repository root), the project's JDK and no absolute path of yours.

| Run configuration | What it does | Ports |
|---|---|---|
| **Drishti server** | `DrishtiApplication` of module `drishti-server`, working directory = the repository root, the seven packs (`DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets`), sign-in on with the documented development administrator (`drishti-dev-admin` / `drishti-dev-admin123`), `DRISHTI_EXTERNAL_LIB=$PROJECT_DIR$/external_lib`, `-Xmx2g`. Data in `./data`. | 18480 |
| **Drishti server (QA profile)** | The same, with **its own data folder `data/qa/data`** (git-ignored) so QA never touches `./data` or another stack. | **18580** |
| **Drishti server (debug-friendly)** | The QA profile with sign-in **off**, DEBUG logging for `com.ash.drishti` and `-Xmx1g`: for stepping through code without logging in (pair it with a console whose sign-in is off too). | 18580 |
| **Drishti build server jar** | Maven: `package -DskipTests -pl drishti-server -am`; produces `drishti-server/target/drishti-server-<version>-exec.jar`. | |
| **Build demo lakes** (PyCharm) | `drishti.py lake demo`: the seven packs' Delta lakes under `data/delta`, sixty business days, from the committed test data ([data/testdata/README.md](../../data/testdata/README.md)); about 15 minutes and 2.2 GB; the sixty days end on today's business date. Then start **Drishti server (demo lakes)**. | |
| **Drishti server (demo lakes)** | **Drishti server** with the samples off (`DRISHTI_DEMO_ENABLED=false`), so every page reads the demo lakes, and `-Xmx4g` (15,000 trades a day over sixty days and the BI tables of them). | 18480 |
| **QA pack make (from JSON)**, **QA lake build (big JSON to Delta)**, **QA server smoke**, **QA doctor** (PyCharm) | The command-line tools of [CLI_GUIDE.md](CLI_GUIDE.md) as Python run configurations in `drishti-console/run-configs/`. Edit **Parameters** to point at your file (see section 6). | against 18580 |

The QA server and the QA console are started one in each IDE (**Drishti server (QA profile)** in IntelliJ, **Drishti console (QA
profile)** in PyCharm); there is no compound configuration across the two.

The configurations are plain Application configurations (not "Spring Boot"), so they behave the same in IDEA Community and Ultimate.
To change one for yourself without touching the committed file, **Copy Configuration** (Ctrl+D) and keep the copy (it is stored in
`.idea/`, which git ignores). Every `DRISHTI_*` variable is in [CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault).

If you build a configuration by hand instead: **Run -> Edit Configurations -> + -> Application**, main class
`com.ash.drishti.server.DrishtiApplication`, *Use classpath of module* `drishti-server`, JRE 21 or newer, **Working directory = the
repository root** (the default, the `drishti-server` folder, is wrong: the server reads `./config/packs`, `./data` and `./sutras`
relative to it), and environment variables as in the table above. Without `DRISHTI_PACKS` the same seven packs load (the default of
`drishti.packs.enabled` in `application.yaml`). The
development token secret in the committed files is the documented development value; never use it outside your own machine.

### 3.2 Run it

Click **Run** (Shift+F10). The console log ends with `Started DrishtiApplication in … seconds`. Check it:

```bash
curl -s localhost:18480/actuator/health      # {"status":"UP",...}
curl -s localhost:18480/actuator/info        # the version
```

The REST API and its OpenAPI page are at `http://127.0.0.1:18480/api/docs`.

### 3.3 Debug it

Click **Debug** (Shift+F9) instead of Run, and set breakpoints as usual. Useful places to stop:

| To see | Breakpoint in |
|---|---|
| a view being built (data → Sutra → panels) | `com.ash.drishti.engine.ViewPipeline` |
| which Sutra was chosen for an entity | `com.ash.drishti.rachana.SutraRegistry` |
| a connector fetching data | the plugin's `SourcePlugin.fetch(…)`, e.g. `FileSourcePlugin` |
| a REST call arriving | the controllers under `com.ash.drishti.server` |

[HOW_IT_FITS.md](../architecture/HOW_IT_FITS.md) follows one request through all of these.

### 3.4 Changing code while it runs

- **Java method bodies:** in a debug session, **Run → Debugging Actions → Reload Changed Classes** (or build with
  Ctrl+F9; IDEA offers to reload). Changes to signatures, fields or new classes need a restart.
- **Sutras** (`*.sutra.yaml`): the server reloads them on its own when the file changes; no restart.
- **Packs:** the packs folder is watched: edit, add or delete a pack folder and the server reloads it; switch a pack on or off in **Admin → Packs**; no restart ([PACKS.md](PACKS.md)).
- **`application.yaml` and environment variables:** restart the server.

### 3.5 Several servers at once

Give the second one other ports and data: `DRISHTI_PORT=18481` in its environment, and its own working directory if
it must not share `./data`. Point a console at it with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481`.

## 4. PyCharm: run and debug the console

### 4.1 Open the project

**File -> Open** **`drishti-console/`** (not the repository root: that is IntelliJ's project, and the two IDEs must not share one `.idea`
folder). Its run configurations are in `drishti-console/run-configs/`; each runs from the repository root (`$PROJECT_DIR$/..`), so
`tools/`, `config/` and `data/` mean what they mean everywhere else. Then, once:

1. the interpreter of section 4.2;
2. **the tools as a source folder** (they sit next to `drishti-console/`): **Settings → Project: drishti-console → Project Structure →
   + Add Content Root** → the repository's `tools/` → select it → **Sources** → OK. The Project panel then shows `drishti-console` and
   `tools` side by side (for reading and navigating; the run configurations work without it);
3. right-click `tests/` → **Mark Directory as → Test Sources Root**.

If PyCharm was opened on the repository root before, close both IDEs, delete the root's `.idea/` (IDE settings only, git-ignored) and
reopen: IntelliJ on the root, PyCharm on `drishti-console/`.

### 4.2 The interpreter

**Settings → Project → Python Interpreter → Add Interpreter → Add Local Interpreter**:

- **Existing**: pick `drishti-console/.venv/bin/python` (Windows: `drishti-console\.venv\Scripts\python.exe`), created in §1; or
- **Virtualenv → New**: location `drishti-console/.venv`, base interpreter Python 3.11 or newer; then in PyCharm's terminal
  `pip install -r drishti-console/requirements.txt -r drishti-console/requirements-test.txt`.

### 4.3 The run configurations (already in the repository)

PyCharm reads `drishti-console/run-configs/` when you open `drishti-console/` as the project (section 4.1):

| Run configuration | Parameters | Talks to |
|---|---|---|
| **Drishti console** | `--server.port=17480 --backend.url=http://127.0.0.1:18480`, environment `DRISHTI_AUTH_ENABLED=true`, `DRISHTI_TOKEN_SECRET` and `DRISHTI_SESSION_SECRET` (the development values, matching the server's), `DRISHTI_SECURE_COOKIE=false` (plain http on your machine) | the default server |
| **Drishti console (QA profile)** | `--server.port=17580 --backend.url=http://127.0.0.1:18580`, environment `DRISHTI_LAKE_ROOTS=$PROJECT_DIR$/../data/qa/delta` (Admin -> Data loads -> Lakes may write there) and the same sign-in settings | the QA server |

Both run `drishti-console/run_drishti_web.py` from the repository root with the **project interpreter** (*Project Default*). Every
committed Python run configuration stores no interpreter path (`SDK_HOME` empty, `IS_MODULE_SDK` false; `tools/test_run_configs.py`
guards it), so once the project interpreter is `drishti-console/.venv` (section 4.2) they all work as they are, on Linux, macOS and
Windows (`drishti-console\.venv\Scripts\python.exe`); nothing to edit.
The console reads `drishti-console/config/application.yaml`, then `drishti-console/config/application.local.yaml` if it exists, then
`DRISHTI_CONSOLE__...` environment variables, then `--key=value` parameters; later wins ([GETTING_STARTED.md](GETTING_STARTED.md)).

### 4.4 Run it

Click **Run**. The log shows `Uvicorn running on http://0.0.0.0:17480` (it listens on every interface). Open http://localhost:17480, or http://<this machine's IP>:17480 from another machine or a phone. If the console says `DRS-5003 backend unreachable`, the
server is not running or `DRISHTI_BACKEND_URL` points at the wrong port.

### 4.5 Debug it and change code

**Debug** works as usual; good first breakpoints are the route functions in `drishti-console/core/app.py` and the page routes
it includes. The console serves pages from four worker processes by default (`server.workers`); for debugging, add
`DRISHTI_CONSOLE_WORKERS=1` to the run configuration's environment so every request reaches the one process the debugger is in.
Temporary files go under `data/tmp/console` (the server's under `data/tmp/server`), never the system temp folder.

- **Templates** (`drishti-console/web/templates/**`) and **static files** (`drishti-console/web/static/**`: CSS, JavaScript): reload
  the browser page; no restart.
- **Python code** (`drishti-console/core/**`): restart the run configuration (Ctrl+F5).

### 4.6 The command-line tools from PyCharm

The pack and Sutra tools (`tools/drishti.py`: `pack make` (JSON Lines to one deployable folder), `pack new`, `pack check`, `sutra lint`, `data ingest`, the REST commands) run from PyCharm
the same way, and the Java `sutra` tool runs from IntelliJ IDEA: run configurations, the interpreter with PyYAML (and `deltalake`,
`pyarrow`), `DRISHTI_TOKEN` and the working directory are in
[CLI_GUIDE.md](CLI_GUIDE.md#14-running-the-tools-from-pycharm-and-the-java-sutra-from-intellij), which also covers debugging them and sharing run configurations through `drishti-console/run-configs/` (committed: **drishti pack make**, **drishti sutra gen**, **drishti pack regenerate** and **drishti data profile** on the test data, **drishti view get**, **drishti doctor** and **drishti server smoke** against the default server).

## 5. Running the tests from the IDEs

### 5.1 Java tests in IntelliJ

Run a test class or method from its gutter icon, a module's tests from **right-click → Run 'All Tests'**, or everything
from the Maven tool window (**drishti → Lifecycle → verify**: builds, runs every test and every rule, as the drill does).

- **Clear the `DRISHTI_*` variables** in test run configurations (or in the JUnit template: **Run → Edit Configurations →
  Edit configuration templates → JUnit**). The server tests set their own packs (the retired `finance` pack from `examples/packs` as a
  fixture) and fail when `DRISHTI_PACKS`
  and its siblings are set ([DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#22-maven-commands)).
- Tests that need a broker or database (PostgreSQL, Kafka, RabbitMQ, S3 and others) start it in Docker with
  Testcontainers. Without Docker they are skipped (`disabledWithoutDocker`), not failed.
- The test JDK is the project SDK. The drill runs the suite on Java 21 and again on Java 25; to do the same in the IDE,
  switch the SDK in **Project Structure** and run the tests again.

### 5.2 Python tests in PyCharm

**Settings → Tools → Python Integrated Tools → Default test runner: pytest**, then right-click `drishti-console/tests` → **Run
'pytest in tests'**. The browser tests (`*_browser.py`) need Playwright's Chromium once:

```bash
drishti-console/.venv/bin/python -m playwright install chromium
```

Without it they are skipped. Some console tests start the built server jar on a free port; build it first (§2) or
they are skipped.

## 6. The QA profile, end to end (IntelliJ + PyCharm only)

This is the walk a tester follows without a terminal. It uses ports 18580 (server) and 17580 (console) and the folder `data/qa/`
(git-ignored), so it never disturbs the default stack on 18480 / 17480 or the repository's `data/`.

1. **Build the jar.** IntelliJ: run **Drishti build server jar** (a few minutes the first time). `pack make` and `lake build` use it.
2. **Start the QA server.** IntelliJ: run (or debug) **Drishti server (QA profile)**. The log ends with `Started DrishtiApplication`.
   Open http://127.0.0.1:18580/actuator/health: `{"status":"UP"...}`.
3. **Start the QA console.** PyCharm: run **Drishti console (QA profile)** and open http://localhost:17580. Sign in as
   `drishti-dev-admin` / `drishti-dev-admin123` (development defaults, [CONFIGURATION.md](../admin/CONFIGURATION.md)).
4. **Make an API token for the tools** (smoke, doctor, the lake build's `--signal`): console -> *My account -> API tokens*, create one, and
   save its text as `data/qa/token.txt` (create the folder; the file is git-ignored with the folder).
5. **Check the stack.** Run **QA server smoke** and **QA doctor**: smoke ends `smoke: OK`; doctor shows no red lines.
6. **Make the pack from your JSON (optional if you build it in the browser, step 8).** Open **QA pack make (from JSON)**, set
   *Parameters*: the first argument is your sample file (JSON, JSON Lines or `.gz`; the placeholder is
   `$USER_HOME$/Downloads/YOUR_SAMPLE.jsonl`), `--kind` the entity kind, `--match` the field that tells the kinds apart (the placeholder
   is `PRODUCT_TYPE`), `--name` the pack's name. To try it without a file of your own, use the committed test data:
   `data/testdata/trades/endur/snapshot-2026-09-22.jsonl.gz`. It writes `data/qa/packs/<name>/` (the pack is in `data/qa/packs/<name>/pack/<name>`).
7. **Build the lake from the big JSON.** Open **QA lake build (big JSON to Delta)** and edit *Parameters*:

   | Parameter | Meaning | Placeholder |
   |---|---|---|
   | `--from` | your big `.json` array, `.jsonl` or `.gz` (or a folder of them) | `$USER_HOME$/Downloads/YOUR_BIG_FILE.json` |
   | `--kind`, `--key`, `--date` | entity kind, its id field, its business-date field | `trade`, `TRADE_ID`, `TRADE_DATE` (use your file's business-date field) |
   | `--partition-by` | the column(s) the Delta table is split by | `PRODUCT_TYPE` |
   | `--lake` | where the Delta table goes | `data/qa/delta` |
   | `--pack` | the pack folder from step 6 (the folder that holds `pack.yaml`) | `data/qa/packs/qa-pack/pack/qa-pack` |

   Add `--signal http://localhost:18580 --token-file $PROJECT_DIR$/data/qa/token.txt` to tell the server the data landed. Run it; the
   last line says `lake-build ok exit=0 ... rows=N`. It needs `pyyaml`, `pyarrow` and `deltalake` in the interpreter (they are in
   `drishti-console/requirements.txt`); a re-run of the same date replaces that date. Keep local runs to the size the machine takes
   (10,000 trades is plenty for QA).
8. **Build -> New pack in the UI.** In the console: *Build -> New pack*, upload the same JSON, accept or edit the proposed Sutras, and save.
9. **Deploy.** *Admin -> Packs -> Deploy* (the pack archive made in step 6 is `data/qa/packs/<name>/bundle/<name>-<version>.tar.gz`, or use
   the one the wizard offers). Deploying writes under `data/qa/data/packs/`, never the repository's `config/packs`.
10. **Load data.** *Admin -> Data loads* (or the lake build's `--signal`): the pack's loads show the table written in step 7. If a lake
    folder is refused, it is not under `DRISHTI_LAKE_ROOTS` of the QA console (`data/qa/delta`).
11. **Open a deal.** *Explore*, choose the pack and kind, open a row: the deal's page opens with its Sutra view.

Start again from scratch by stopping both processes and deleting the `data/qa/` folder.


## 7. Common problems

| You see | Do this |
|---|---|
| `Drishti needs Java 21 or newer … Maven is running on Java 17 from …` | The IDE's Maven runs on an older Java: set **Maven → Runner → JRE** to *Use Project JDK* and the project SDK to 21 or newer (§2). |
| `class file version 69.0 … only recognizes class file versions up to 65` | Old Java 25 class files: **Build → Rebuild Project** once (§1). |
| `Unrecognized VM option 'UseCompactObjectHeaders'` | The run configuration's JRE is Java 21 (or older than 25): remove the flag from **VM options**. |
| The server starts but every view is empty, or packs are missing | The **Working directory** is `drishti-server/`, not the repository root (§3.1). |
| `Port 18480 was already in use` | Another server is running (from a terminal or another run configuration). Stop it, or use `DRISHTI_PORT=18481` (§3.5). |
| Tests pass in the terminal but fail in IDEA | `DRISHTI_*` variables are set in the test configuration (§5.1). |
| PyCharm: `ModuleNotFoundError: No module named 'fastapi'` | The run configuration uses another interpreter: choose `drishti-console/.venv` (§4.2). |
| `DRS-5003 backend unreachable` in the console | Start the server first; check the `--backend.url` parameter of the console's run configuration (§4.4). |
| Smoke or doctor says `401 DRS-5010 missing bearer token` | Sign-in is on: make an API token (§6 step 4) and save it as `data/qa/token.txt`. |
| `lake build: ... pack.yaml: no such file` | `--pack` must name the folder that holds `pack.yaml`: `data/qa/packs/<name>/pack/<name>`. |
| `deltalake is not installed` | The run configuration's interpreter lacks it: install `drishti-console/requirements.txt` into the `.venv` (§1). |
| `pack make` says no `drishti-server-*-exec.jar` | Run **Drishti build server jar** first (§6 step 1). |
| A run configuration is missing or shows `<no module>` | Open the repository root (a `pom.xml` project) and let the Maven import finish; the Python configurations need the Python plugin / PyCharm. |
| The QA server will not start: port in use | The QA port 18580 is taken: stop the other process, or edit `DRISHTI_PORT` and the console's `--backend.url`. |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
