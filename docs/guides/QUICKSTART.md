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
# Quickstart

Ten minutes from a fresh clone to your first live view, with only the commands you need. It skips the
explanations. For a slower walk through the same steps, read [GETTING_STARTED.md](GETTING_STARTED.md).

In these docs, `<GO>` means "press Enter".

## 1. Check the prerequisites

| Tool | Version | Check with | You should see |
|---|---|---|---|
| OpenJDK | **25** (the build refuses any other) | `java -version` | `openjdk version "25.…` |
| Python | 3.11 or newer | `python3 --version` | `Python 3.11` or later |
| uv | any recent | `uv --version` | `uv 0.…` |
| git | any | `git --version` | `git version …` |

On Windows, follow [WINDOWS.md](WINDOWS.md) instead: the same steps in PowerShell.

You do not need Maven: the repository has its own (`./mvnw`). Point `JAVA_HOME` at Java 25 in
**every** terminal you use (this is the Ubuntu path; adjust it for your system):

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## 2. Build

Run everything from the repository root.

```bash
git clone https://github.com/ajsinha/drishti.git
cd drishti
./mvnw -q package -DskipTests
ls drishti-server/target/*-exec.jar
```

You should see `drishti-server/target/drishti-server-1.14.1-exec.jar`. The first build downloads its
libraries and takes a few minutes.

If the build stops with `Drishti builds and runs on OpenJDK 25.`, `JAVA_HOME` is not Java 25.

## 3. Set up the console

```bash
uv venv console/.venv
uv pip install --python console/.venv/bin/python -r console/requirements.txt
```

You should see uv list what it installed (FastAPI, Uvicorn, Jinja2, httpx, PyYAML, Markdown, pytest).

**Calc (Python in the browser) needs its runtime once.** It is a download of about 340 MB (cached), 51 MB on disk:

```bash
tools/fetch-pyodide.sh
```

Without it a Calc panel stays dead. On Windows run the same command in Git Bash or WSL (the script needs bash and
python3); [PYTHON_CALC.md](PYTHON_CALC.md) section 12 has the offline way (`--tarball`). Skip this if you do not use Calc.

## 4. (Optional) Build the sample history

Live views work without this. Past business dates and **Compare** need a small Delta Lake under
`data/delta/` (ten business days ending 30 September 2026); the same command writes the sample feed file
`data/feeds/fixing/SOFR-HISTORY.csv`. Nothing under `data/` is in git: it is always generated.

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta
```

## 5. Start the server

Terminal 1, from the repository root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
java -jar drishti-server/target/drishti-server-1.14.1-exec.jar
```

| Variable | What it does | Default |
|---|---|---|
| `DRISHTI_PACKS` | The domain packs to load. A pack's parents load with it: `market-risk` brings `banking-core`, `market-data` and `trading`. | `finance` |
| `DRISHTI_PORT` | The server's port | `18480` |
| `DRISHTI_STUDIO_SAVE` | `true` lets the Build workbench save Sutras (the view definitions it edits) | `false` |

When the log says `Started DrishtiApplication`, check it from another terminal:

```bash
curl -s localhost:18480/actuator/health
curl -s localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
```

You should see `{"status":"UP","groups":["liveness","readiness"]}`, then twelve pack names starting with
`'banking-core', 'market-data', 'trading'`.

## 6. Start the console

Terminal 2, from the repository root:

```bash
console/.venv/bin/python console/run_drishti_web.py
```

You should see `Uvicorn running on http://127.0.0.1:17480`. The console finds the server at
`http://127.0.0.1:18480` (`DRISHTI_BACKEND_URL` changes that; `DRISHTI_CONSOLE_PORT` changes its own port).

Open **http://localhost:17480/t** in your browser. Sign-in is off out of the box, so you are the development
user *Ash* with every role.

## 7. Your first eight commands

Click the command line at the top (or press `/`), type each command and press Enter.

| # | Type | You should see |
|---|---|---|
| 1 | `TRD MX-20000001` | An interest rate swap: the title `Rates · Interest rate swap (fixed/float)`, `MX-20000001`, `with Meridian Reinsurance Ltd`; a strip with Notional `AUD 242,000,000` and a highlighted **MTM (USD)** that ticks and flashes; panels *Terms*, *Legs*, *Cashflows*, a curve chart, *DV01 by bucket*, *Daily P&L*, *Linked entities*. |
| 2 | `F9`, then `F7`, then `Alt+←` | `F9` opens the raw JSON (source `murex-rates`). `F7` jumps to the netting set `NS-MERIDIAN-RE-NY`. `Alt+←` comes back. The bottom bar lists every key the view has: `F2 Terms`, `F3 Cashflows`, `F4 Interest rate`, `F7 Netting set`, `F8 Impact`, `F9 Raw JSON`; a view's own panel keys, such as `F5` and `F6`, jump to their panels (they appear when the view defines them). `Alt+C` opens Calc (Python, where a pack offers it) and `Alt+L` layout mode, `F1` is help. |
| 3 | `F8` (on the trade) | *Impact of MX-20000001*: level 1 the netting set `NS-MERIDIAN-RE-NY`, level 2 the credit limit `LIM-MERIDIAN-RE`, each with the amount at stake. |
| 4 | `TRD MX-200000` | No trade has that exact id, so you get a **pick list**: `99 of 99 trades match`, with each trade's product type, direction, currency, notional, MTM, maturity and book. Use `↑` `↓` and Enter, or click an id. |
| 5 | `TRD productType=Revolver` | A pick list by field value: `6 of 750 trades match`. Case never matters, in field names too (`trd producttype=revolver` works too); a field trades do not have (`TRD nosuchfield=1`) says so and suggests the nearest names. |
| 6 | `CPTY north` | Exactly one counterparty's name contains "north", so it opens at once: `CP-NORTHBRIDGE`, Northbridge Capital LLP, with Rating `BB+` and its PFE peak. |
| 7 | `TRD where mtm > 1m order by mtm desc limit 20` | A search: `163 of 750 trades match; the first 20 are shown`, largest MTM first. |
| 8 | `NSET NS-SUMMIT-NY` | A netting set: Trades `108`, Utilisation `58%`, an exposure profile (`F2`) and the member trades (`F3`). |

While you type, the dropdown suggests mnemonics and ids: type `TRD MX-2000010` and you should see `MX-20000100`,
`MX-20000101`, … Press `↓` to pick one, `Tab` to complete it, Enter to open it.

## 8. Look at a past business date

This needs step 4.

1. Open `TRD MX-20000001`.
2. In the top bar, click the **Business date** box (it shows today's date and a green **Live**) and pick
   **29 September 2026**.
3. The box turns amber and **Live** goes out. The MTM is that day's value and no longer ticks.
4. Click **Compare** in the title line. You should see *What changed in MX-20000001* against the business day
   before, field by field.
5. Click **Live** to return to today.

Without the sample history, the view says *No data held for* the date and shows the current data as a still
snapshot, and there is no **Compare**.

## 9. Try a workspace (optional)

**Workspaces** in the top bar (`/w`) puts up to four live views on one screen, and a pane can follow
another. With the packs above there are no starter workspaces (*Starters* says so): type a name under
**New workspace**, press **Create**, then type `TRD MX-20000001` in the first pane's box and `CPTY CP-NORTHBRIDGE` in
the second, and press **Save**. `Alt+1` and `Alt+2` move between the panes, `Alt+0` back to the bar. The starters
come from the `finance` and `logistics` packs (restart the server with `DRISHTI_PACKS=finance` to try **Credit desk**).
The [workspaces guide](../../console/web/guides/workspaces.md) walks through both.

## Build and run without the wrapper, or from an IDE

Everything above uses `./mvnw`. This section is for a machine that has its own Maven, and for working from
IntelliJ IDEA and PyCharm. Commands were run on Ubuntu with Maven 3.9.12 and OpenJDK 25.

### With Maven installed on the system

The poms enforce only the Java version (`[25,26)`, the rule that prints `Drishti builds and runs on OpenJDK 25.`); they
set no minimum Maven version. The wrapper pins **Maven 3.9.12** (`.mvn/wrapper/maven-wrapper.properties`), which is what
the project is built and tested with; use 3.9.x. The enforcer rule checks the JVM Maven itself runs on, so set
`JAVA_HOME` to Java 25 (as in step 1) before `mvn`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -v                                  # "Apache Maven 3.9.…" and "Java version: 25.…"

mvn -q package -DskipTests              # build (add -o to work offline once ~/.m2 is filled)
mvn -q verify                           # build, every test and every rule
mvn -q -pl drishti-server -am package -DskipTests    # one module and what it needs
ls drishti-server/target/*-exec.jar     # the application: drishti-server/target/drishti-server-1.14.1-exec.jar
```

Run the server from the repository root (it finds `./packs` and `./data` relative to where you start it). It runs on
JDK 25 only; `-XX:+UseCompactObjectHeaders` saves about 10% of the heap:

```bash
DRISHTI_PACKS=market-risk,counterparty-risk \
  java -XX:+UseCompactObjectHeaders -jar drishti-server/target/drishti-server-1.14.1-exec.jar
```

Run the console in a Python virtual environment (Python 3.11 or newer). With uv, as in step 3; without it:

```bash
python3 -m venv console/.venv           # Debian and Ubuntu: sudo apt install python3-venv first
console/.venv/bin/pip install -r console/requirements.txt          # add -r console/requirements-test.txt for pytest's extras
console/.venv/bin/python console/run_drishti_web.py                # http://127.0.0.1:17480
console/.venv/bin/python -m pytest console/tests -q                # the console's tests
```

### IntelliJ IDEA

Use a release that supports JDK 25.

1. **File → Open**, choose the root `pom.xml`, **Open as Project**. IDEA imports every module.
2. **File → Project Structure → Project → SDK**: a JDK 25 (add it with *Add SDK → Add JDK*); language level 25.
   Maven's own JDK, in **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE**, must be 25 too.
3. **Run → Edit Configurations → + → Spring Boot** (or *Application* in the Community edition):

   | Field | Value |
   |---|---|
   | Main class | `com.ash.drishti.server.DrishtiApplication` |
   | Module / classpath of | `drishti-server` |
   | JRE | 25 |
   | Working directory | the repository root (the default is the `drishti-server` folder, so change it: `packs/` and `data/` are relative) |
   | VM options | `-XX:+UseCompactObjectHeaders` |
   | Environment variables | `DRISHTI_PACKS=market-risk,counterparty-risk;DRISHTI_STUDIO_SAVE=true` (separate with `;`; the other `DRISHTI_*` variables are listed in [CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault)) |
   | Program arguments | `--spring.config.additional-location=file:/home/you/drishti-site.yaml` (optional; see [below](#supplying-a-different-application-config)) |

4. Run it. The log ends with `Started DrishtiApplication`; `curl -s localhost:18480/actuator/health` answers `UP`.

Tests run from the gutter icons. Clear the `DRISHTI_*` variables in the test run configuration: tests expect the
defaults (the `finance` pack) and fail when `DRISHTI_PACKS` and its siblings are set (see [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#22-maven-commands)).

### PyCharm

1. **File → Open** the repository root (or only `console/`).
2. **Settings → Project → Python Interpreter → Add Interpreter → Add Local Interpreter → Existing**, and pick
   `console/.venv/bin/python` (Windows: `console\.venv\Scripts\python.exe`). Create the venv first with the commands above.
3. **Run → Edit Configurations → + → Python**:

   | Field | Value |
   |---|---|
   | Script | `console/run_drishti_web.py` |
   | Parameters | optional `--server.port=17481` (any `--key=value`, e.g. `--backend.url=http://127.0.0.1:18481`) |
   | Python interpreter | the `console/.venv` one |
   | Working directory | the repository root (the script finds `console/config` from its own location, so any folder works; the root keeps relative paths in your own settings predictable) |
   | Environment variables | `DRISHTI_BACKEND_URL=http://127.0.0.1:18480;DRISHTI_CONSOLE_PORT=17480` |

The console reads `console/config/application.yaml`, then `console/config/application.local.yaml` if it exists, then
`DRISHTI_CONSOLE__…` environment variables, then `--key=value` parameters. For pytest, make **Default test runner** pytest
(*Settings → Tools → Python Integrated Tools*) and run `console/tests`.

## Supplying a different application config

### The server

Spring Boot reads `application.yaml` from inside the jar, then `./application.local.yaml` (the bundled file imports it
with `optional:`), and you can add more. Drishti's own `spring.config.import` entries are exactly two: that local file
and the packs overlay `./data/packs/added.yaml` (`DRISHTI_PACKS_OVERLAY` moves it; the server writes it, do not edit it).

| You want | Do | Command |
|---|---|---|
| a site file read **in addition**, on top of the bundled defaults | `--spring.config.additional-location` | `java -jar drishti-server/target/drishti-server-1.14.1-exec.jar --spring.config.additional-location=file:/etc/drishti/site.yaml` |
| the same, from the environment | `SPRING_CONFIG_ADDITIONAL_LOCATION` | `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/drishti/site.yaml java -jar …` |
| several files, later ones win | a comma list | `--spring.config.additional-location=file:/etc/drishti/site.yaml,file:/etc/drishti/secrets.yaml` |
| a folder of files | end it with `/` | `--spring.config.additional-location=file:/etc/drishti/conf.d/` |
| a profile's file | `--spring.profiles.active` (or `SPRING_PROFILES_ACTIVE`) | `java -jar … --spring.profiles.active=files` reads the jar's `application-files.yaml` |
| a profile's file of your own | name it `application-<profile>.yaml` beside your site file and add the location | `--spring.profiles.active=prod --spring.config.additional-location=file:/etc/drishti/` reads `/etc/drishti/application-prod.yaml` too |
| **replace** the defaults (rare) | `--spring.config.location`, and keep the bundled file in the list | `--spring.config.location=classpath:/application.yaml,file:/etc/drishti/site.yaml` |

**Do not** write `--spring.config.location=file:/etc/drishti/site.yaml` alone: it replaces the bundled
`application.yaml`, so the server starts with none of Drishti's defaults (tried: it came up on Spring's port 8080, not
18480). Prefer `additional-location`. A key set in a file you add overrides the bundled one; environment variables and
`--key=value` arguments override both ([CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault)).
Do not set the same key in both `application.local.yaml` and an additional file. The profiles the jar ships are `files`, `postgres`,
`duckdb`, `mongodb`, `redis`, `aerospike` and `iceberg`.

A site file is ordinary YAML with the same keys as `application.yaml`; it needs only what you change:

```yaml
# /etc/drishti/site.yaml
server:
  port: 18481
drishti:
  packs:
    enabled: market-risk,counterparty-risk
  rachana:
    studio-save: true
```

### The console

The console has **no flag or variable that points at another config file**: it reads `config/application.yaml`
beside `run_drishti_web.py` (`console/config/`) and `console/config/application.local.yaml` if present
(`console/core/config.py`). Override it in one of three ways:

```bash
# 1. a local file (git-ignored): the same keys as application.yaml, only what you change
cat > console/config/application.local.yaml <<'YAML'
server:
  port: 17481
backend:
  url: http://127.0.0.1:18481
YAML
console/.venv/bin/python console/run_drishti_web.py

# 2. environment variables: DRISHTI_CONSOLE__<KEY>__<SUBKEY>, or the named variables the YAML uses
DRISHTI_CONSOLE__BACKEND__URL=http://127.0.0.1:18481 DRISHTI_CONSOLE_PORT=17481 \
  console/.venv/bin/python console/run_drishti_web.py

# 3. arguments: --key.subkey=value
console/.venv/bin/python console/run_drishti_web.py --server.port=17481 --backend.url=http://127.0.0.1:18481
```

Later wins: the YAML, the local file, the environment, the arguments. To keep several configurations, keep several local
files and copy the one you want to `console/config/application.local.yaml`, or set the environment per shell.

### What the `${DRISHTI_PACKS:finance}` in the files means

A value written `${NAME:default}` is "the variable `NAME` if it is set, else `default`". The meaning, the order that
wins, and a table of every such placeholder in both `application.yaml` files are in
[CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault).

## If something goes wrong

| You see | Do this |
|---|---|
| `Port 18480 was already in use` | Another server is running. Stop it, or start this one with `DRISHTI_PORT=18481` and the console with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481`. |
| `DRS-5003 backend unreachable` in the console | Start the server first; check `curl -s localhost:18480/actuator/health`. |
| `DRS-4001 cannot read command …` | The mnemonic is unknown. Is its pack loaded (`/api/v1/packs`)? |
| `DRS-5002 ash may not open gene entities` | The kind's pack is not among the packs you chose. Click the box icon in the top bar, tick the pack, and click **Apply**. |
| `DRS-1001 no source holds trade/…` | The id does not exist. Type part of it and pick from the suggestions. |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Where next

| If you want to… | Read |
|---|---|
| How connectors, packs and Sutras fit together, and how the screen is bound to the backend | [HOW_IT_FITS.md](../architecture/HOW_IT_FITS.md) |
| The same setup, step by step, with sign-in, alerts and monitors | [GETTING_STARTED.md](GETTING_STARTED.md) |
| Every console feature, with examples | [USER_GUIDE.md](USER_GUIDE.md) |
| The packs and the commands each one adds | [PACKS.md](PACKS.md) |
| Build a screen from your own JSON files, by dragging panels and fields, no YAML needed | [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md), or **Build → Screen designer guide** (F1 on any Build page) |
| Change how a screen looks (Sutras) | [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) and the in-app *Sutra guide* |
| See every panel kind working, and copy from ten small Sutras with their data | [examples/](examples/README.md), or **File** in the workbench, or **Help → Examples** |
| Build Drishti from source, run the tests, add an endpoint, plugin, pack or page | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) |
| Connect your own data | [PLUGIN_GUIDE.md](../connectors/PLUGIN_GUIDE.md) and [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Turn on sign-in and run it for others | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) and [OPERATIONS.md](../admin/OPERATIONS.md) |
| See every document | [the documentation map](../README.md) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
