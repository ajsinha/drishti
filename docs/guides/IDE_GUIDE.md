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
| **Server** (`drishti-server`) | Java 21 or newer (25 recommended), Spring Boot 3.5 | IntelliJ IDEA (Community or Ultimate) | `com.ash.drishti.server.DrishtiApplication` | `http://127.0.0.1:18480` |
| **Console** (the UX, `console/`) | Python 3.11 or newer, FastAPI | PyCharm (Community or Professional) | `console/run_drishti_web.py` | `http://127.0.0.1:17480` |

Start the server first, then the console: the console calls the server for everything it shows. You can use one IDE for
both (IntelliJ IDEA Ultimate with the Python plugin runs the console too); the settings below are the same.

For the command-line way to build and run, see [QUICKSTART.md](QUICKSTART.md). For every server and console setting, see
[CONFIGURATION.md](../admin/CONFIGURATION.md).

## 1. Before you open the IDE

| You need | Version | Check |
|---|---|---|
| A JDK (not only a JRE) | **21 or newer**, any vendor: OpenJDK, Oracle, Temurin, Corretto…; 25 recommended | `java -version` and `javac -version` |
| Python | 3.11 or newer | `python3 --version` |
| The console's virtual environment | `console/.venv` | `ls console/.venv/bin/python` |
| Docker (only for some tests) | any recent | `docker --version` |

Create the console's virtual environment once, from the repository root (PyCharm can also create it, see §4.2):

```bash
uv venv console/.venv && uv pip install --python console/.venv/bin/python -r console/requirements.txt -r console/requirements-test.txt
# without uv:
python3 -m venv console/.venv && console/.venv/bin/pip install -r console/requirements.txt -r console/requirements-test.txt
```

**Which Java version.** Drishti compiles to Java 21 bytecode, so the same build runs on Java 21, 25 and anything newer.
Java 25 is recommended: with `-XX:+UseCompactObjectHeaders` the server uses about 10% less heap. Java 21 has no such
flag and refuses to start when it is given, so leave it out there. The build stops on anything older than 21 with
`Drishti needs Java 21 or newer … Maven is running on Java N from <folder>`, naming the Java it actually found.

**After pulling the move to Java 21 bytecode** (from a checkout built when Drishti targeted Java 25 only): rebuild once,
**Build → Rebuild Project** in IntelliJ, or `./mvnw clean` from a terminal. Otherwise old Java 25 class files in
`target/` give `class file version 69.0` errors on Java 21 ([TROUBLESHOOTING.md](TROUBLESHOOTING.md#tests-on-java-21-fail-with-has-been-compiled-by-a-more-recent-version-of-the-java-runtime-class-file-version-690)).

## 2. IntelliJ IDEA: open and build the server

Use an IntelliJ IDEA release that knows your JDK (Java 25 needs IDEA 2025.2 or newer).

1. **File → Open**, choose the repository's root `pom.xml`, then **Open as Project**. IDEA imports every Maven module
   (the server, the engine, the 15 plugins, the testkit, …). Wait for the import and indexing to finish.
2. **File → Project Structure → Project**:
   - **SDK**: your JDK 21 or newer. If it is not listed: **Add SDK → Add JDK…** and choose its folder, for example
     `/usr/lib/jvm/java-25-openjdk-amd64` (Ubuntu OpenJDK), `/usr/lib/jvm/jdk-25-oracle-x64` (Oracle JDK on Linux),
     `C:\Program Files\Java\jdk-25` (Oracle JDK on Windows) or `/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home`
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

### 3.1 The run configuration

**Run → Edit Configurations → + → Spring Boot** (Ultimate) or **+ → Application** (Community):

| Field | Value | Why |
|---|---|---|
| Name | `Drishti server` | |
| Main class | `com.ash.drishti.server.DrishtiApplication` | the Spring Boot application |
| Module / *Use classpath of module* | `drishti-server` | it depends on every plugin, so all of them are on the classpath |
| JRE | your JDK 21 or newer | |
| **Working directory** | **the repository root** (the default is the `drishti-server` folder: change it) | the server reads `./packs`, `./data`, `./sutras` and `./application.local.yaml` relative to it |
| VM options | Java 25 or newer: `-XX:+UseCompactObjectHeaders`; Java 21: leave empty. Optionally `-Xmx2g` | the flag saves heap and exists only from Java 25 |
| Environment variables | e.g. `DRISHTI_PACKS=market-risk,counterparty-risk,retail-banking;DRISHTI_STUDIO_SAVE=true` (separate with `;`) | which packs load, and whether the Build workbench may save Sutras; every `DRISHTI_*` variable is in [CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault) |
| Program arguments | optional, e.g. `--spring.config.additional-location=file:/home/you/drishti-site.yaml` | a settings file of your own on top of the defaults ([QUICKSTART.md](QUICKSTART.md#supplying-a-different-application-config)) |

The packs the quickstart loads, to paste into **Environment variables**:

```text
DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
```

Without `DRISHTI_PACKS` the server loads only the `finance` pack.

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
- **Packs:** switch a loaded pack on or off in **Admin → Packs** in the console, no restart ([PACKS.md](PACKS.md)).
- **`application.yaml` and environment variables:** restart the server.

### 3.5 Several servers at once

Give the second one other ports and data: `DRISHTI_PORT=18481` in its environment, and its own working directory if
it must not share `./data`. Point a console at it with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481`.

## 4. PyCharm: run and debug the console

### 4.1 Open the project

**File → Open** the repository root (or only `console/`; the root lets you read the docs and packs beside the code).

### 4.2 The interpreter

**Settings → Project → Python Interpreter → Add Interpreter → Add Local Interpreter**:

- **Existing**: pick `console/.venv/bin/python` (Windows: `console\.venv\Scripts\python.exe`), created in §1; or
- **Virtualenv → New**: location `console/.venv`, base interpreter Python 3.11 or newer; then in PyCharm's terminal
  `pip install -r console/requirements.txt -r console/requirements-test.txt`.

### 4.3 The run configuration

**Run → Edit Configurations → + → Python**:

| Field | Value |
|---|---|
| Name | `Drishti console` |
| Script path | `console/run_drishti_web.py` |
| Parameters | optional `--key=value` settings, e.g. `--server.port=17481 --backend.url=http://127.0.0.1:18481` |
| Python interpreter | the `console/.venv` one |
| Working directory | the repository root (the script finds `console/config` from its own location, so any folder works; the root keeps relative paths in your own settings predictable) |
| Environment variables | `DRISHTI_BACKEND_URL=http://127.0.0.1:18480;DRISHTI_CONSOLE_PORT=17480` (these are the defaults; set them to point elsewhere) |

The console reads `console/config/application.yaml`, then `console/config/application.local.yaml` if it exists, then
`DRISHTI_CONSOLE__…` environment variables, then `--key=value` parameters; later wins
([QUICKSTART.md](QUICKSTART.md#the-console)).

### 4.4 Run it

Click **Run**. The log shows `Uvicorn running on http://127.0.0.1:17480`. Open that address. If the console says `DRS-5003 backend unreachable`, the
server is not running or `DRISHTI_BACKEND_URL` points at the wrong port.

### 4.5 Debug it and change code

**Debug** works as usual; good first breakpoints are the route functions in `console/core/app.py` and the page routes
it includes.

- **Templates** (`console/web/templates/**`) and **static files** (`console/web/static/**`: CSS, JavaScript): reload
  the browser page; no restart.
- **Python code** (`console/core/**`): restart the run configuration (Ctrl+F5).

## 5. Running the tests from the IDEs

### 5.1 Java tests in IntelliJ

Run a test class or method from its gutter icon, a module's tests from **right-click → Run 'All Tests'**, or everything
from the Maven tool window (**drishti → Lifecycle → verify**: builds, runs every test and every rule, as the drill does).

- **Clear the `DRISHTI_*` variables** in test run configurations (or in the JUnit template: **Run → Edit Configurations →
  Edit configuration templates → JUnit**). Tests expect the defaults (the `finance` pack) and fail when `DRISHTI_PACKS`
  and its siblings are set ([DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#22-maven-commands)).
- Tests that need a broker or database (PostgreSQL, Kafka, RabbitMQ, S3 and others) start it in Docker with
  Testcontainers. Without Docker they are skipped (`disabledWithoutDocker`), not failed.
- The test JDK is the project SDK. The drill runs the suite on Java 25 and again on Java 21; to do the same in the IDE,
  switch the SDK in **Project Structure** and run the tests again.

### 5.2 Python tests in PyCharm

**Settings → Tools → Python Integrated Tools → Default test runner: pytest**, then right-click `console/tests` → **Run
'pytest in tests'**. The browser tests (`*_browser.py`) need Playwright's Chromium once:

```bash
console/.venv/bin/python -m playwright install chromium
```

Without it they are skipped. Some console tests start the built server jar on a free port; build it first (§2) or
they are skipped.

## 6. Common problems

| You see | Do this |
|---|---|
| `Drishti needs Java 21 or newer … Maven is running on Java 17 from …` | The IDE's Maven runs on an older Java: set **Maven → Runner → JRE** to *Use Project JDK* and the project SDK to 21 or newer (§2). |
| `class file version 69.0 … only recognizes class file versions up to 65` | Old Java 25 class files: **Build → Rebuild Project** once (§1). |
| `Unrecognized VM option 'UseCompactObjectHeaders'` | The run configuration's JRE is Java 21 (or older than 25): remove the flag from **VM options**. |
| The server starts but every view is empty, or packs are missing | The **Working directory** is `drishti-server/`, not the repository root (§3.1). |
| `Port 18480 was already in use` | Another server is running (from a terminal or another run configuration). Stop it, or use `DRISHTI_PORT=18481` (§3.5). |
| Tests pass in the terminal but fail in IDEA | `DRISHTI_*` variables are set in the test configuration (§5.1). |
| PyCharm: `ModuleNotFoundError: No module named 'fastapi'` | The run configuration uses another interpreter: choose `console/.venv` (§4.2). |
| `DRS-5003 backend unreachable` in the console | Start the server first; check `DRISHTI_BACKEND_URL` (§4.4). |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
