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

# Install and run

The reference for getting Drishti on a machine: install the tools, build the server, start the server and the console, turn on sign-in, other ways to build and run (a system Maven, an IDE, a different
configuration file), running in Docker, and everything that differs on Windows. It does not teach the product: the first quarter of an hour in it is [tutorial 1](../tutorials/01-first-15-minutes.md), and
the [tutorials](../tutorials/README.md) take you through the rest. The whole install takes about 20 minutes, most of it waiting for the first build.

You will:

1. install three tools;
2. download and build Drishti;
3. start the **server** (the part that reads data) and the **console** (the web pages you use);
4. optionally turn on sign-in and change the development admin's password.

## What Drishti is, in one minute

Drishti is a data viewer that you drive from the keyboard, like a Bloomberg terminal. You type a short
**command** and press Enter, and a full screen of figures, tables and charts appears:

```text
TRD MX-20000001        a trade: an interest rate swap
NSET NS-SUMMIT-NY  a netting set: all trades with one counterparty under one agreement
CUST CUST-100231   a retail bank customer
GENE GENE-TP53     a gene
```

The first word is a **mnemonic** (a short code for a kind of thing: `TRD` is trade). The second is the
**identifier** of one thing of that kind. In the docs we write `<GO>` after a command to mean "press Enter".

There are two programs:

| Program | What it does | Address |
|---|---|---|
| **Server** (Java) | Reads data from the sources, lays out each screen, sends live updates | `http://localhost:18480` |
| **Console** (Python) | The web pages you see in your browser | `http://localhost:17480` |

You always open the **console** in your browser. The console talks to the server for you.

Industries are added as **packs**: a banking pack knows about trades and curves, a genomics pack knows
about genes and variants. You choose which packs to load when you start the server.

## Step 1 · Install the prerequisites

You need a Linux or macOS machine with the tools below. On Windows, Drishti runs natively (no WSL, no Hadoop
`winutils.exe`): follow [GETTING_STARTED.md (Windows)](#drishti-on-windows), which covers the same steps in PowerShell.

| Tool | Version | Why | Check with |
|---|---|---|---|
| OpenJDK | **21** or newer (production runs 21) | builds and runs the server | `java -version` |
| Python | 3.11 or newer | runs the console | `python3 --version` |
| uv | any recent | creates the console's Python environment and builds sample history | `uv --version` |
| git | any | downloads the code | `git --version` |
| Docker | optional | only to run everything in containers, or the PostgreSQL/Aerospike/Kafka extras | `docker --version` |

On Ubuntu or Debian:

```bash
sudo apt install openjdk-21-jdk python3 python3-venv git curl   # any JDK 21 or newer works
curl -LsSf https://astral.sh/uv/install.sh | sh      # installs uv into ~/.local/bin
```

Drishti builds and runs on Java 21 or newer and refuses an older one; Java 21 is the production target and the recommended runtime; Java 25 is also tested and supported. Point `JAVA_HOME` at
a JDK 21 or newer (these examples use 21) in every terminal you use for Drishti. On Ubuntu the path is:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

You should see a line that starts with `openjdk version "21.` (or a newer version if you installed one).

> **Tip:** add the two `export` lines to your `~/.bashrc` so new terminals get them too.

You do **not** need to install Maven. The repository has its own copy (`./mvnw`).

## Step 2 · Download the code

```bash
git clone https://github.com/ajsinha/drishti.git
cd drishti
```

Every command in the rest of this guide is run **from this `drishti` folder** (the repository root).
The server finds the packs (`./config/packs`) and its data (`./data`) relative to where you start it.

## Step 3 · Build the server

```bash
./mvnw -q package -DskipTests
```

The first build downloads its libraries and takes several minutes; later builds are much faster.
`-q` keeps the output quiet, so a successful build prints little or nothing. When it finishes, check
that the server program was built:

```bash
ls drishti-server/target/*-exec.jar
```

You should see `drishti-server/target/drishti-server-2.2.0-exec.jar`.

> **Note:** `./mvnw -q verify` builds **and** runs every test (several minutes more). Use it when you
> change code; for a first try, `package -DskipTests` is enough.

If the build stops with `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …`, your `JAVA_HOME` is older than Java 21. Go
back to Step 1.

## Step 4 · Set up the console

Create a private Python environment for the console and install its libraries into it:

```bash
uv venv drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
```

You should see uv report the packages it installed (FastAPI, Uvicorn, Jinja2 and a few more).

If you will use **Calc** (Python in the browser), install its runtime once with `tools/fetch-pyodide.sh` (about a
340 MB download, cached; on Windows run it in Git Bash or WSL). Without it a Calc panel stays dead; see
[PYTHON_CALC.md](PYTHON_CALC.md) section 12 for the offline way.

Without uv, plain Python works too:

```bash
python3 -m venv drishti-console/.venv
drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt
```

## Step 5 · (Recommended) Build the sample history

The packs come with sample documents for **today** (the server serves them live, so they tick). To
look at **past business dates** and compare dates, you also need a small history: a local Delta Lake
under `data/delta/`. It is not in git because it is generated. Build it once:

```bash
# banking packs: core, market-data, trading, market-risk, counterparty-risk
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta

# the other generated packs (run the ones you want)
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/liquidity/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/climate/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/oprisk/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/retail/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/genomics/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/politics/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/world/make.py --lake data/delta
```

Each command prints what it wrote, for example `lake: … rows under data/delta (…)`. Afterwards
`ls data/delta` lists one folder per data domain (`reference`, `market`, `trading`, `risk`, `credit`,
`collateral`, `liquidity`, `climate`, `oprisk`, `retail`, `genomics`, `civic`, `macro`).

The sample history holds ten business days, ending on **30 September 2026**.

> **Skip this step** if you only want to look around. Live views work without it; a view on a picked
> date then says "*No data held for <date>*" and shows the current data, as a still snapshot.

## Step 6 · Start the server

Open a terminal in the `drishti` folder and run:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets
export DRISHTI_STUDIO_SAVE=true
java -jar drishti-server/target/drishti-server-2.2.0-exec.jar
```

What the settings do:

| Setting | Meaning |
|---|---|
| `DRISHTI_PACKS` | The packs to load, separated by commas. A pack's parents load with it: `market-risk` brings `core`, `market-data` and `trading`. Without this setting only the small `finance` pack loads. |
| `DRISHTI_STUDIO_SAVE=true` | Lets the Build workbench save Sutras (the view definitions it edits). Leave it out if you will not edit Sutras. |

Leave this terminal open: the server runs in it and writes its log there. After a few seconds you
should see a line like:

```text
Started DrishtiApplication in 6.1 seconds
```

Check it from a second terminal:

```bash
curl -s localhost:18480/actuator/health
```

You should see `{"status":"UP","groups":["liveness","readiness"]}`. To list the packs it loaded:

```bash
curl -s localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
```

You should see the twelve names, starting with `core`, `market-data`, `trading`.

> **The smallest start.** `java -jar drishti-server/target/drishti-server-2.2.0-exec.jar` with no
> settings loads only the `finance` pack: the four original mockups (`TRD IRS-48213`, `TRD FXS-20931`,
> `TRD CFT-77120`, `NSET NS-NORTH-01`). Everything in this guide works the same way with those ids.
> Do not mix `finance` with the banking packs: both define `TRD` and `CRV`.

## Step 7 · Start the console

Open another terminal in the `drishti` folder:

```bash
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

You should see:

```text
INFO:     Uvicorn running on http://0.0.0.0:17480 (Press CTRL+C to quit)
```

Now open **http://localhost:17480** in your browser. You see the landing page with an animated eye.
Click **Open the terminal**, or go straight to **http://localhost:17480/t**.

> **Signed in already?** Out of the box, sign-in is **off** so you can explore at once: you act as the
> development user *Ash* with every role (the top bar shows `Rates desk · Ash`). Step 9 turns sign-in on.

## Step 8 · Your first view

Open http://localhost:17480, click in the command line (or press `/`), type `TRD MX-20000001` and press Enter: a full view of a trade, with a live MTM figure that changes and flashes. From there:

| You want to | Go to |
|---|---|
| Open a trade, search, try the form, look at BI and make a token, in a guided quarter of an hour | [Tutorial 1 · Your first 15 minutes](../tutorials/01-first-15-minutes.md) |
| Use mnemonics, type-ahead, links, a past business date and Compare | [Tutorial 2 · Look up anything](../tutorials/02-look-up-anything.md) |
| Search by value (`TRD where mtm > 1m order by mtm desc`), by form, save a search and a monitor | [Tutorial 3 · Query by form](../tutorials/03-query-by-form.md) and [USER_GUIDE.md](USER_GUIDE.md#search-by-value) |
| Set an alert and watch a list | [USER_GUIDE.md](USER_GUIDE.md#monitors) |

If a command gives `DRS-4001` the mnemonic is unknown (is its pack loaded?); if a view says `DRS-1001 no source holds …` the id does not exist: type part of it and pick from the suggestions.

## Step 9 · (Optional) Turn on sign-in and change the admin password

With sign-in off, anyone who can reach the console acts as the development user. To try real
sign-in on your machine, stop both programs (`Ctrl+C` in each terminal) and restart them with
security on. Server and console must share the same **token secret**. Once it is on, the home page shows a **Sign in** button and, after signing in, your name with **Sign out**; with it off, the top bar's user menu says *Sign-in is off on this server*.

```bash
# in both terminals, the same value (at least 32 characters)
export DRISHTI_TOKEN_SECRET=change-me-to-a-long-random-string-0123456789
```

Server terminal:

```bash
export DRISHTI_SECURITY_ENABLED=true
java -jar drishti-server/target/drishti-server-2.2.0-exec.jar
```

Console terminal:

```bash
export DRISHTI_AUTH_ENABLED=true
export DRISHTI_SESSION_SECRET=another-long-random-string-0123456789abcd
export DRISHTI_SECURE_COOKIE=false        # only for plain http on your own machine
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

(`openssl rand -hex 32` prints a good random secret.)

Now open http://localhost:17480/t. You are sent to **Sign in**. On first start with no users, the
server creates a development administrator:

| User | Password |
|---|---|
| `drishti-dev-admin` | `drishti-dev-admin123` |

1. Sign in with those.
2. Click your name in the top bar (or go to **http://localhost:17480/account**).
3. Under **Change password**, type the current password, then a new one twice (at least 10 characters,
   with letters and digits), and click **Change password**.

Until you do, the admin's *Users* page warns that the development password is still in use. Five
wrong passwords in a row lock an account for 15 minutes.

**Admin** in the top bar opens the administration pages: *Users* (create users, give roles and packs),
*Roles*, *Audit log*, *Packs*, *Connectors*, *Data loads*, *Collaboration*, *Embedding*, *Health* and *Caches*. Users, roles and packs per user are explained in
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

> **Production:** set `DRISHTI_SEED_ADMIN=false` so no development admin is created, and use long random
> secrets. See [OPERATIONS.md](../admin/OPERATIONS.md).

## Step 10 · Switch packs, themes and settings

- **Packs.** The box icon in the top bar shows how many packs you see (for example `12 packs`). Click it,
  untick the ones you do not need, and click **Apply**. The examples, suggestions and help follow your
  choice. You can only choose among packs the server loaded and an admin assigned to you.
- **Themes.** The palette icon offers seven themes: Terminal, Parchment, Blue, Wall Street, Green, Crimson
  and Crimson dark.
- **Settings.** *My account → Settings* (`/account`) keeps your theme, the page to open after signing in
  (for example `/v/trade/MX-20000001`), the clock's time zone, density, whether changes flash, and how many
  search results to show.

## Step 11 · Find help

- **Help** in the top bar (or `F1` on any page) opens the help centre at `/help`, with the tutorials, every
  reference document and a search box. `F1` opens the help for the page you are on.
- The **?** in each panel's header explains that kind of panel.
- **About** (`/about`, linked in the footer) is the product page: what Drishti is, its features, measured performance, the competitive analysis, the technology and the licence. Versions and source health for admins are in *Admin → Health* (`/admin/health`).
- Each pack has its own guide under *Help → Domain packs*, with example commands.

## Stopping and starting again

- Stop: press `Ctrl+C` in the server terminal and in the console terminal.
- Start again: repeat Step 6 and Step 7 (with the same `export` lines). No rebuild is needed unless the
  code changed. Your monitors, alerts, workspaces and settings are kept by the server under `./data`.

## Running everything in Docker (optional)

If you prefer containers, build the server first (Step 3), then:

```bash
export DRISHTI_TOKEN_SECRET=$(openssl rand -hex 32) DRISHTI_SESSION_SECRET=$(openssl rand -hex 32)
export DRISHTI_PACKS=finance            # or a banking list as in Step 6
docker compose -f deploy/compose.yaml up -d --build
```

Sign-in is **on** in this setup: sign in at http://localhost:17480 as `drishti-dev-admin` /
`drishti-dev-admin123` and change the password (Step 9). `docker compose -f deploy/compose.yaml down`
stops it. Production details are in [OPERATIONS.md](../admin/OPERATIONS.md).

## Build and run without the wrapper, or from an IDE

Everything above uses `./mvnw`. This section is for a machine that has its own Maven, and for working from
IntelliJ IDEA and PyCharm. Commands were run on Ubuntu with Maven 3.9.12 and OpenJDK 21 (any Java 21 or newer works).

### With Maven installed on the system

The poms enforce only the Java version (`[21,)`, the rule that prints `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …`); they
set no minimum Maven version. The wrapper pins **Maven 3.9.12** (`.mvn/wrapper/maven-wrapper.properties`), which is what
the project is built and tested with; use 3.9.x. The enforcer rule checks the JVM Maven itself runs on, so set
`JAVA_HOME` to Java 21 or newer (as in step 1) before `mvn`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -v                                  # "Apache Maven 3.9.…" and "Java version: 25.…"

mvn -q package -DskipTests              # build (add -o to work offline once ~/.m2 is filled)
mvn -q verify                           # build, every test and every rule
mvn -q -pl drishti-server -am package -DskipTests    # one module and what it needs
ls drishti-server/target/*-exec.jar     # the application: drishti-server/target/drishti-server-2.2.0-exec.jar
```

Run the server from the repository root (it finds `./config/packs` and `./data` relative to where you start it). It runs on
Java 21 (the production target) or any newer JDK. Java-25-only note: `-XX:+UseCompactObjectHeaders` saves about 10% of the heap there, and Java 21 refuses to start with it, so leave it out on 21:

```bash
DRISHTI_PACKS=market-risk,counterparty-risk \
  java -jar drishti-server/target/drishti-server-2.2.0-exec.jar
```

Run the console in a Python virtual environment (Python 3.11 or newer). With uv, as in step 3; without it:

```bash
python3 -m venv drishti-console/.venv           # Debian and Ubuntu: sudo apt install python3-venv first
drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt          # add -r drishti-console/requirements-test.txt for pytest's extras
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py                # http://127.0.0.1:17480
drishti-console/.venv/bin/python -m pytest drishti-console/tests -q                # the console's tests
```

### IntelliJ IDEA and PyCharm

Running the server from IntelliJ IDEA and the console from PyCharm (run configurations, JDK and interpreter setup,
debugging, reloading changes, tests in the IDE, common problems) has its own guide: **[IDE_GUIDE.md](IDE_GUIDE.md)**. The repository ships ready-made run configurations in `run-configs/` (server, a QA profile on its own ports and data folder, console, build the jar, the lake build and pack tools), so a tester needs only the two IDEs and no terminal.

Where next from a terminal: **[CLI_GUIDE.md](CLI_GUIDE.md)** is the one command line, `tools/drishti.py`, for turning your JSON Lines
into a pack, checking packs in CI, loading data and a pack into a running server, and the Screen Designer's propose and approve steps.

## Supplying a different application config

### The server

Spring Boot reads `application.yaml` from inside the jar, then `./application.local.yaml` (the bundled file imports it
with `optional:`), and you can add more. Drishti's own `spring.config.import` entries are exactly two: that local file
and the packs overlay `./data/packs/added.yaml` (`DRISHTI_PACKS_OVERLAY` moves it; the server writes it, do not edit it).

| You want | Do | Command |
|---|---|---|
| a site file read **in addition**, on top of the bundled defaults | `--spring.config.additional-location` | `java -jar drishti-server/target/drishti-server-2.2.0-exec.jar --spring.config.additional-location=file:/etc/drishti/site.yaml` |
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
beside `run_drishti_web.py` (`drishti-console/config/`) and `drishti-console/config/application.local.yaml` if present
(`drishti-console/core/config.py`). Override it in one of three ways:

```bash
# 1. a local file (git-ignored): the same keys as application.yaml, only what you change
cat > drishti-console/config/application.local.yaml <<'YAML'
server:
  port: 17481
backend:
  url: http://127.0.0.1:18481
YAML
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py

# 2. environment variables: DRISHTI_CONSOLE__<KEY>__<SUBKEY>, or the named variables the YAML uses
DRISHTI_CONSOLE__BACKEND__URL=http://127.0.0.1:18481 DRISHTI_CONSOLE_PORT=17481 \
  drishti-console/.venv/bin/python drishti-console/run_drishti_web.py

# 3. arguments: --key.subkey=value
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py --server.port=17481 --backend.url=http://127.0.0.1:18481
```

Later wins: the YAML, the local file, the environment, the arguments. To keep several configurations, keep several local
files and copy the one you want to `drishti-console/config/application.local.yaml`, or set the environment per shell.

### What the `${DRISHTI_PACKS:finance}` in the files means

A value written `${NAME:default}` is "the variable `NAME` if it is set, else `default`". The meaning, the order that
wins, and a table of every such placeholder in both `application.yaml` files are in
[CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault).

## Drishti on Windows


Drishti runs on Windows 10 and 11 (and Windows Server 2019 or later) without WSL, Docker or Hadoop. This guide covers
the same ground as this guide in PowerShell: installing Java (21 or newer, production runs 21) and Python, building the server
or copying a built one, loading the demo lake, starting the server and the console, what does not work on Windows,
and what to do when something goes wrong.

Why it works without Hadoop: the Delta Lake connector reads through its **native engine** (module
`drishti-deltalake`), which uses Java's own file access for local disk and the AWS SDK for S3. Hadoop's local file
system, and with it `winutils.exe`, is never used. See [DELTA_CONNECTOR.md › Engines](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop).

> **Status.** The native engine is tested on Linux on every build, including its Windows path rules (drive letters,
> backslashes, UNC shares) as unit tests. The `windows` GitHub Actions job builds, tests and starts the server on
> `windows-latest`. Report what you find on your machine.


### 1. Install Java and Python

| Tool | Version | Install with | Check with |
|---|---|---|---|
| JDK | **21 or newer; 21 is the production target** (Temurin or any OpenJDK) | `winget install EclipseAdoptium.Temurin.21.JDK`, or the MSI from adoptium.net | `java -version` shows `openjdk version "21…` (or 25…) |
| Python | 3.11 or newer | `winget install Python.Python.3.13`, or python.org (tick *Add python.exe to PATH*) | `python --version` |
| uv | optional, recommended | `winget install astral-sh.uv` | `uv --version` |
| git | to build from source | `winget install Git.Git` | `git --version` |

The Java you run Drishti with does not have to be the default Java. The scripts take its folder as `-JavaHome`, or from
`DRISHTI_JAVA_HOME`:

```powershell
$env:DRISHTI_JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'   # your JDK folder
```

If PowerShell refuses to run the scripts (*running scripts is disabled on this system*), allow local scripts for your
user once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

### 2. Get the server: build it, or copy it

**Build it** from a clone (the repository brings its own Maven, `mvnw.cmd`):

```powershell
git clone https://github.com/ajsinha/drishti.git
cd drishti
$env:JAVA_HOME = $env:DRISHTI_JAVA_HOME
.\mvnw.cmd -q package -DskipTests
dir drishti-server\target\*-exec.jar
```

You should see `drishti-server-2.2.0-exec.jar`. To also run the tests of the Delta engine and connector:
`.\mvnw.cmd -q verify -pl drishti-deltalake,plugins/drishti-plugin-delta -am` (tests that need Docker skip themselves).

**Or copy** a server built elsewhere (on Linux, or by CI): Java bytecode is the same on every system. Make a folder,
for example `C:\drishti`, holding:

| Copy | From | Why |
|---|---|---|
| `drishti-server-2.2.0-exec.jar` | `drishti-server\target\` | the server (all its libraries are inside) |
| `config\packs\` | the repository root | the domain packs (Sutras, mnemonics, samples). Also `config\connectors\` (connector files) and `config\bi\` (BI datasets and reports) if you use them |
| `drishti-console\` | the repository root | only if you will run the console on this machine |
| `tools\windows\`, `tools\packgen\`, `tools\samplegen\` | the repository's `tools\` | the scripts, and the lake generator if you will build the demo lake here |
| `data\` | an existing installation, if any | the lake (`data\delta`), users (`data\identity`), feeds, reports; start without it and it is created |
| `application.local.yaml` | your own, if any | site settings; read from the folder the server starts in |

Without a clone, put the scripts in `C:\drishti\tools\windows\`: they work from the folder two levels above
themselves, and look for the jar in `drishti-server\target\` and then in that folder.

### 3. Load the demo lake

Live views work without it. Past business dates, **Compare** and searches over the history need the banking packs'
lake under `data\delta` (ten business days ending 30 September 2026):

```powershell
.\tools\windows\load-delta.ps1
```

With uv installed it fetches `deltalake`, `pyarrow` and `pyyaml` itself; without uv, install them first:
`python -m pip install deltalake pyarrow pyyaml`. A bigger trade book: `.\tools\windows\load-delta.ps1 -Trades 50000`
(about 20 MB a day per 10,000 trades). Another folder: `-Root D:\lakes\drishti`, and start the server with
`-LakeRoot D:\lakes\drishti`.

### 4. Start the server

```powershell
.\tools\windows\start-server.ps1 -Packs market-risk
```

The script checks that Java is 21 or newer, sets `DRISHTI_DELTA_ENGINE=native`, and starts the jar with
`-XX:+UseCompactObjectHeaders` only on Java 25 or newer (Java-25-only: 10% less heap; the script leaves the flag out on Java 21, the production target, which refuses to start with it). It runs in the window until `Ctrl+C`.
Its options:

| Option | Default | Meaning |
|---|---|---|
| `-JavaHome` | `DRISHTI_JAVA_HOME`, then `JAVA_HOME`, then `java` on the PATH | the JDK folder (21 or newer) |
| `-Packs` | `DRISHTI_PACKS`, else the server's default (`finance`) | the packs to load, e.g. `market-risk,counterparty-risk` |
| `-Port` | `DRISHTI_PORT`, else `18480` | the server's port |
| `-LakeRoot` | `DRISHTI_DELTA_ROOT`, else `.\data\delta` | the Delta Lake |
| `-Engine` | `DRISHTI_DELTA_ENGINE`, else `native` | `native`, `hadoop` (needs `winutils.exe`) or `auto` |
| `-Heap` | `2g` | the heap limit (8 GB per million entities a day for a large lake) |
| `-Jar` | the newest `drishti-server-*-exec.jar` | another jar |
| `-Background` | off | start without a window: log in `data\logs\server.log`, process id in `data\server.pid` |
| `-Stop` | | stop a server started with `-Background` |

Check it from another PowerShell window:

```powershell
Invoke-RestMethod http://localhost:18480/actuator/health
(Invoke-RestMethod http://localhost:18480/api/v1/admin/health).sources | Select-Object name, health
```

You should see `UP`, then each lake connector (`trading-store`, `market-store`, …) as `UP (engine: native)`.

To run the jar by hand instead:

```powershell
$env:DRISHTI_PACKS = 'market-risk'
& "$env:DRISHTI_JAVA_HOME\bin\java.exe" -Xmx2g -jar drishti-server\target\drishti-server-2.2.0-exec.jar
```

The native Delta engine is the default, so nothing else is needed.

### 5. Start the console

In a second PowerShell window:

```powershell
.\tools\windows\start-console.ps1
```

The first run makes the console's Python environment and installs its libraries (with uv if present, else
`python -m venv` and pip). Open **http://localhost:17480/t**. Options: `-Port` (17480), `-Backend`
(`http://127.0.0.1:18480`), `-Python` (the Python to build the environment with), `-Reinstall`.

To start the console by hand instead (the same steps the script takes):

```powershell
uv venv drishti-console\.venv
uv pip install --python drishti-console\.venv\Scripts\python.exe -r drishti-console\requirements.txt
drishti-console\.venv\Scripts\python.exe drishti-console\run_drishti_web.py
```

(without uv: `python -m venv drishti-console\.venv`, then `drishti-console\.venv\Scripts\pip install -r drishti-console\requirements.txt`).

> The settings are the console's own: `DRISHTI_CONSOLE_PORT`, `DRISHTI_BACKEND_URL`.

From here the []() steps 7 to 9 are the same.

### 6. The Delta engine on Windows

Every Delta connector has an `engine` setting; `DRISHTI_DELTA_ENGINE` sets it for those that do not set their own.

| Value | On Windows |
|---|---|
| `native` (the default) | works: local folders (`C:\lakes\drishti`, `\\fileserver\lakes`, `file:/C:/lakes`) and S3 |
| `auto` | the same as `native` on Windows (and `hadoop` on Linux) |
| `hadoop` | needs Hadoop's `winutils.exe` and `HADOOP_HOME`; use it only for a lake in Azure (`abfs://`) or Google Cloud Storage (`gs://`), which the native engine does not read |

A lake in S3 reads the same on Windows, with the connector's `s3.endpoint`, `s3.access-key`, `s3.secret-key`,
`s3.region` ([CONFIGURATION.md](../admin/CONFIGURATION.md#delta--delta-lake)). Paths with spaces
(`C:\Users\Jane Doe\drishti\data\delta`) are fine.

### 7. What does not work on Windows

| What | Why | Instead |
|---|---|---|
| The Iceberg connector (profile `iceberg`) | it reads through Hadoop's file systems, which need `winutils.exe` | keep the default Delta Lake; the Iceberg connector is idle unless the `iceberg` profile is set |
| `engine: hadoop` on a local lake | Hadoop's local file system needs `winutils.exe` | `engine: native` (the default) |
| Replacing a DuckDB file while the server reads it | Windows cannot rename a file over one that is open ([DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md)) | load into a new file and switch the connector's `path`, or stop the server first |
| The bash scripts in `tools\*.sh` (`load-delta.sh`, `load-postgres.sh`, `drill.sh`, …) | they need bash | `tools\windows\*.ps1` for the server, the console and the Delta lake; for the others, the Python tools they call run as they are (`python tools\…`), or use Git Bash or WSL |
| `docker compose` deployments (`deploy\`) | Linux containers | Docker Desktop runs them as on Linux; the jar runs natively as above |

The Aerospike, PostgreSQL, Kafka, RabbitMQ, ActiveMQ, Redis, MongoDB, S3 and REST connectors are plain network clients
and work on Windows; their servers are yours to run.

### 8. Troubleshooting

| You see | Cause | Do this |
|---|---|---|
| `Drishti runs on Java 21 or newer (production runs 21; 25 also supported); … says: … version "17…` | the script found an older Java | pass `-JavaHome` or set `DRISHTI_JAVA_HOME` to a JDK 21 or newer folder |
| `running scripts is disabled on this system` | PowerShell's execution policy | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| `HADOOP_HOME and hadoop.home.dir are unset`, `Could not locate executable winutils.exe` | a Delta connector on `engine: hadoop` | remove `engine: hadoop` and unset `DRISHTI_DELTA_ENGINE` (or set it to `native`) |
| Health: `DOWN: cannot reach C:\…\data\delta\trading (engine: native)` | no lake there | run `load-delta.ps1`, or start with `-LakeRoot` pointing at the lake |
| Health: `DOWN: no Delta tables under …` | the folder exists but has no `<kind>\_delta_log` | check the root and the pack's `domain` folder (`trading`, `market`, …) |
| `the native Delta engine reads local disk and S3 …, not abfs://…` | an Azure or GCS lake with the native engine | set the connector's `engine: hadoop` (and install `winutils.exe`) |
| `Port 18480 was already in use` | another server | `-Port 18481`, and the console with `-Backend http://127.0.0.1:18481` |
| The console says `DRS-5003 backend unreachable` | the server is not up yet, or on another port | wait for `Started DrishtiApplication`; check `-Backend` |
| `load-delta.ps1`: `No module named 'deltalake'` | no uv, and the libraries are not installed | `python -m pip install deltalake pyarrow pyyaml`, or install uv |
| Windows Defender slows the first start | it scans the jar's native libraries (RocksDB, DuckDB, SQLite, Snappy) as they unpack | let the first start finish; later starts are faster |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*


## If something goes wrong

| You see | Do this |
|---|---|
| `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …` | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and build again |
| `Port 18480 was already in use` | another server is running; stop it, or start this one with `DRISHTI_PORT=18481` and the console with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481` |
| The console says the backend is unreachable (`DRS-5003`) | start the server first; check `curl -s localhost:18480/actuator/health` |
| A command gives `DRS-4001` | the mnemonic is unknown: check its pack is loaded (`/api/v1/packs`) and chosen in the pack switcher |
| A view says `DRS-1001 no source holds …` | the id does not exist; type part of it and pick from the suggestions |
| A picked date shows today's data | build the sample history (Step 5) and restart the server |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Where to go next

| If you want to… | Read |
|---|---|
| Learn by doing | [the tutorials](../tutorials/README.md) |
| Learn every feature of the console | [USER_GUIDE.md](USER_GUIDE.md) |
| Know which packs exist and what commands they offer | [PACKS.md](PACKS.md) |
| Change how a screen looks | [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md), [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md) and [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) |
| Add users and roles | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Connect your own data | [CONNECTORS.md](../connectors/CONNECTORS.md) and [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) |
| Build Drishti from source, run the tests | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md), [IDE_GUIDE.md](IDE_GUIDE.md) |
| See every document | [the documentation map](../README.md) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
