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
# Developer quickstart

From a clean machine to your first change, built, checked and committed, in about an hour (most of it the first build and the demo
lake). This page is the shortest path; each step links the guide that holds the details, so nothing here is the only place a fact lives:
[DEVELOPER_GUIDE.md](guides/DEVELOPER_GUIDE.md) (the code, the rules, recipes), [BUILD_GUIDE.md](guides/BUILD_GUIDE.md) (packs and
screens), [CLI_REFERENCE.md](guides/CLI_REFERENCE.md) (every command), [CONFIGURATION.md](admin/CONFIGURATION.md) (every setting). To
install and verify a release rather than develop it, use [DEPLOYMENT_AND_QA_GUIDE.md](DEPLOYMENT_AND_QA_GUIDE.md). A PDF copy of this page
is [QUICKSTART_DEVELOPERS.pdf](QUICKSTART_DEVELOPERS.pdf).

## Contents

1. [Get the code and set up the machine](#1-get-the-code-and-set-up-the-machine)
2. [Build](#2-build)
3. [Run the server and the console on the demo lake](#3-run-the-server-and-the-console-on-the-demo-lake)
4. [The repository map](#4-the-repository-map)
5. [The fast checks](#5-the-fast-checks)
6. [The rules a change must follow](#6-the-rules-a-change-must-follow)
7. [Your first change, end to end](#7-your-first-change-end-to-end)
8. [Where to go next](#8-where-to-go-next)

## 1. Get the code and set up the machine

Drishti is one of five repositories of the **Drishti platform** (github.com/drishti-platform). The four products Drishti is built on are
their own repositories with their own versions and gates; Drishti uses them as jars and as JavaScript bundles. Which version of each goes
with which Drishti is stated in one place, the index repository's
[COMPATIBILITY.md](https://github.com/drishti-platform/drishti-platform/blob/main/COMPATIBILITY.md); this page never repeats a version.

| Repository | What it is | Language | Build output |
|---|---|---|---|
| [drishti-tools](https://github.com/drishti-platform/drishti-tools) | the shared Java utilities (settings and placeholders, TLS and the outbound proxy, single flight, the load guard, the one `.xlsx` writer) | Java 21 | jars in `~/.m2` |
| [drishti-rachana](https://github.com/drishti-platform/drishti-rachana) | the Rachana language (Sutras, the expression language and the compiler, the live tables, the data protocol and its SDKs) | Java 21, Python, JavaScript | jars in `~/.m2` |
| [drishti-lake](https://github.com/drishti-platform/drishti-lake) | Drishti Lake: the Delta, Iceberg and files stores, the loader, the in-memory lake, the data inbox | Java 21, Python | jars in `~/.m2` |
| [drishti-elements](https://github.com/drishti-platform/drishti-elements) | Drishti Elements, the JavaScript libraries every screen is made of | JavaScript | the release bundles (a WebJar) |
| [drishti](https://github.com/drishti-platform/drishti) | the server, the console, the modules and the demo data | Java 21, Python | `lib/` and `modules/` |

The index repository,
[drishti-platform/drishti-platform](https://github.com/drishti-platform/drishti-platform), holds `clone_all.py`, `build_all.py`,
`make_bundle.py` and COMPATIBILITY.md.

### 1.1 The machine

You need a JDK 21 (production runs 21), Python 3.13 for the console and the tools, git, and Node 20 or newer for the Elements unit
tests. Maven is not needed: every repository carries its own (`./mvnw`, `mvnw.cmd`). The one statement of the versions is
[OPERATIONS_GUIDE.md › Prerequisites](guides/OPERATIONS_GUIDE.md#21-prerequisites).

| | Linux (Ubuntu, Debian) | macOS | Windows (PowerShell) |
|---|---|---|---|
| JDK 21 | `sudo apt install openjdk-21-jdk` | `brew install openjdk@21` | the Temurin 21 MSI or ZIP (a ZIP needs no installer) |
| Python 3.13 (the one Python Drishti runs with: the system's, a dedicated one, or a virtual environment you made yourself) | a dedicated `python3.13` (the OS's own is externally managed on Debian/Ubuntu: section 3.1) | `brew install python@3.13` | python.org's installer or its embeddable ZIP |
| uv (only to build the Lake wheel when you work from a git clone, section 2; the source zip and the wheel pack carry it) | the installer script from astral.sh (`install.sh`) | `brew install uv` | optional |
| Node (Elements tests) | `sudo apt install nodejs` | `brew install node` | optional: the Node tests skip without it |
| `JAVA_HOME` | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` | `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` | `$env:JAVA_HOME = 'C:\jdk-21'` |

Only Java and Python are needed: no `.exe` is added on Windows, and every operator command is `tools/drishti.py`. Behind a company
network that re-signs HTTPS, Maven needs Java to trust the Windows store ([IDE_GUIDE.md › PKIX](guides/IDE_GUIDE.md#14-maven-behind-a-company-network-pkix-path-building-failed)).

### 1.2 The code: a bundle, or `clone_all.py`

The platform lives in one folder, every repository a sibling of the index repository (`drishti-platform`, which holds `clone_all.py`,
`build_all.py` and `platform.yaml`, the tags that go together):

```
~/IdeaProjects/drishti-platform/                           (Windows: C:\Users\<you>\IdeaProjects\drishti-platform\)
    drishti-platform/  drishti/  drishti-rachana/  drishti-elements/  drishti-lake/  drishti-tools/
```

**A. From the source bundle** (no git needed): download the bundle ZIP from the index repository's release and unpack it into
`~/IdeaProjects/drishti-platform/`. It holds every repository at the tags that go together, with `build_all.py`; the pinned Lake wheel is already in it, in `drishti/lib/wheels/` (only a git clone builds it, section 2).

**B. With git:** clone the index repository into the folder that will hold them all, and let `clone_all.py` clone the rest at the release's
tags (it puts the repositories in the folder *above* the index repository, so the index repository goes one level down):

```bash
mkdir -p ~/IdeaProjects/drishti-platform && cd ~/IdeaProjects/drishti-platform
git clone https://github.com/drishti-platform/drishti-platform.git
cd drishti-platform
python3 clone_all.py --release <release>         # Windows: py -3.13 clone_all.py --release <release>; --parent <folder> puts them elsewhere
```

`--release` is required (a release name from `platform.yaml`; `--develop` clones every repository at `develop` instead). `make_bundle.py` (the
same index repository) makes the ZIP of A from the clones. To work on a branch rather than the tags, clone the repository you change by hand
into the same folder: `git clone https://github.com/drishti-platform/drishti.git`.

### 1.3 The console's environment

From the `drishti` folder (every command below runs from it):

```bash
cd ~/IdeaProjects/drishti-platform/drishti
# Source zip or wheel pack: lib/wheels already holds the pinned Lake wheel, nothing to build. From a git clone only: build it (section 2)
#   uv build --wheel ../drishti-lake/python --out-dir lib/wheels
python3 tools/lake/lakewheel.py                                        # exit 0 and (ok): lib/wheels holds exactly the pinned wheel
# first YOU install the libraries with the two pip commands and run `drishti.py setup` (Drishti runs no pip): see the line below the block
python3 -m playwright install chromium        # the browser tests' Chromium, once
python3 tools/drishti.py doctor
```

The Lake wheel comes first because `requirements.txt` installs `drishti-lake==<version>` from `lib/wheels`, and no package index has it; `../drishti-lake`
must be at the tag `lib/lake-wheel.pin` names (`clone_all.py --release <release>` leaves it there). On every system Drishti never makes a virtual
environment: the Python you run it with holds the libraries, which you install with the two pip commands and then check with `drishti.py setup`
(per system: [DEPLOYMENT_AND_QA_GUIDE.md 1.6](DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup)); a developer adds
`-r drishti-console/requirements-test.txt` (`requirements.txt` plus pytest, pytest-xdist and Playwright) into the same Python. Playwright's browsers go into the
user's profile, no administrator rights needed.

**You should see** `doctor` end with `doctor: all clear` (the Java, the Python and its libraries, the ports 18480 and 17480 free).

**Your settings file**, once: `python tools/drishti.py setup` makes `~/drishti-envs/dev.ini` for you (one that names this checkout is kept; one from another copy is moved into `~/drishti-envs/replaced-<date>` and a fresh one written), and `tools/drishti.py` takes it with `--settings` ([DEPLOYMENT_AND_QA_GUIDE.md › The settings file](DEPLOYMENT_AND_QA_GUIDE.md#51-the-settings-file)); the IDE launchers need it too ([IDE_GUIDE.md](guides/IDE_GUIDE.md#21-the-settings-file-drishti-envsdevini)).
`requirements-test.txt` is `requirements.txt` plus pytest, pytest-xdist and Playwright: a developer needs both. The IDE setup: [IDE_GUIDE.md](guides/IDE_GUIDE.md) (IntelliJ and PyCharm), [IDE_GUIDE.md section 10](guides/IDE_GUIDE.md#10-visual-studio-code) (VS Code).

## 2. Build

The products install into `~/.m2` at the versions the root `pom.xml` pins (`drishti.tools.version`, `drishti.rachana.version`,
`drishti.lake.version`), in this order: **tools, Rachana, Lake, then Drishti** (each builds on the one before). The index repository does the Maven part:

```bash
cd ~/IdeaProjects/drishti-platform/drishti-platform
python3 build_all.py --release <release> [--skip-tests] [--repo-local]       # Windows: py -3.13 build_all.py --release <release>
```

`build_all.py` checks that each repository is at the release's tag (it stops and says so if not: `clone_all.py --release <release>` puts them there), then runs
`mvnw -B clean install` in `drishti-tools/java`, `drishti-rachana/java` and `drishti-lake/java` in that order (their tests included unless `--skip-tests`),
and then `mvnw -B clean verify` in `drishti`: the whole Drishti suite, not `build all`. It repeats what is already built (nothing is skipped), builds neither the
Elements bundles (Drishti vendors them, [ELEMENTS_IN_DRISHTI.md](architecture/THIN_CONSOLE_DESIGN.md)) nor the Lake wheel, and does not start Drishti.
`--repo-local` installs into `<parent>/.m2-drishti` instead of `~/.m2`; `--dry-run` prints the commands. For the Drishti jar and modules alone, with the products already
installed, use `python tools/drishti.py build all` below. By hand, per repository, the same order (each repository's README, section *Build and run in the IDE*,
names its launchers):

```bash
cd ~/IdeaProjects/drishti-platform/drishti-tools/java && git checkout <tag> && ./mvnw -q install -DskipTests
cd ../../drishti-rachana/java                 && git checkout <tag> && ./mvnw -q install -DskipTests
cd ../../drishti-lake/java                    && git checkout <tag> && ./mvnw -q install -DskipTests
cd ../../drishti                              && python tools/drishti.py build all
```

`<tag>` is the tag in COMPATIBILITY.md for your Drishti; install the version `pom.xml` pins, and check `~/.m2` holds no other build of it. Windows:
`mvnw.cmd`.

**The Lake wheel.** The console and the tools use Lake's Python path (`drishti_lake`), a pure-Python wheel. `lib/lake-wheel.pin` names the one
wheel Drishti uses: the Lake tag, its exact version and its SHA-256 (the build is reproducible). Build it from that tag into `lib/wheels` before the console's
requirements are installed (section 1.3; `uv` is needed):

```bash
git clone --branch <tag in lib/lake-wheel.pin> https://github.com/drishti-platform/drishti-lake.git ../drishti-lake-tag     # or use ../drishti-lake when it is at that tag
uv build --wheel ../drishti-lake-tag/python --out-dir lib/wheels
python tools/lake/lakewheel.py             # prints the pin and whether lib/wheels holds exactly that wheel (exit 1 if not)
```

The tools take only the pinned wheel by name and checksum, never the newest file of the folder; a wheel of another version or checksum is refused
with the reason.

**Drishti itself:**

```bash
python tools/drishti.py build all        # Windows: py -3.13 tools\drishti.py build all
ls lib/*-exec.jar modules/*.dmod
```

**You should see** Maven's progress, then `lib/drishti-server-<version>-exec.jar` and one `modules/<id>-<version>.dmod` per module with `modules/first-party.yaml`: `build all` runs one `mvnw clean package` for the server and the modules, and the Maven build itself installs the exec jar into `lib/`, where every start finds it, and the packages into `modules/` (the same command with no Python: `./mvnw clean package -DskipTests -pl :drishti-app,:drishti-modules -am`, or IntelliJ's **Drishti: build all**). The jar holds no module; `build jar` alone builds only the jar, `build modules` only the packages; a start refuses packages older than their module folder and names the one to rebuild; `--offline` builds without the network. The first build downloads
its libraries (several minutes); later ones take about a minute. Every Maven command and
what it builds: [DEVELOPER_GUIDE.md › Maven commands](guides/DEVELOPER_GUIDE.md#22-maven-commands).

## 3. Run the server and the console on the demo lake

### 3.1 Without an IDE: every command, from the unzipped platform folder

The whole path on the command line, nothing but Java 21 and Python 3.13 (the wheel packs are built for it; `setup` checks 3.11 as the floor). Windows commands are for `cmd` from the
unzipped platform folder (`C:\Users\you\drishti-platform`, holding `build_all.py` and `drishti\`); Linux and macOS are the same with
`python3` and `/`, where `python3` is a dedicated Python 3.13 for Drishti (for example `/opt/python3.13/bin/python3.13`): the operating
system's own `python3` of Debian or Ubuntu is *externally managed* (PEP 668) and setup refuses it in one line. Unzip the offline wheel
pack (built for Python 3.13) into `%USERPROFILE%\drishti-wheels` (`~/drishti-wheels`) first and install it yourself, once, with the two pip commands ([DEPLOYMENT_AND_QA_GUIDE.md 1.6](DEPLOYMENT_AND_QA_GUIDE.md#16-the-settings-file-and-drishtipy-setup)); Drishti never installs Python packages, `setup` only checks them.

| Step | Windows (`cmd`) | Linux and macOS |
|---|---|---|
| 1. Build everything (the products, then Drishti's jar and modules; no tests) | `py -3.13 build_all.py --bundle . --skip-tests` | `python3 build_all.py --bundle . --skip-tests` |
| ... or Drishti alone (the products already in `~/.m2`) | `cd drishti` then `mvnw.cmd -B clean package -DskipTests -Dspotless.check.skip=true` | `cd drishti && ./mvnw -B clean package -DskipTests -Dspotless.check.skip=true` |
| 2. Prepare the machine (settings file, data folder, checks the libraries of THIS Python) | `cd drishti` then `py -3.13 tools\drishti.py setup` | `cd drishti && python3 tools/drishti.py setup` |
| 3. The demo lake (about 15 minutes, 3.4 GB) | `py -3.13 tools\drishti.py lake demo --engine java` | `python3 tools/drishti.py lake demo --engine java` |
| 4. Start the server and the console | `py -3.13 tools\drishti.py start all --demo` | `python3 tools/drishti.py start all --demo` |
| ... see them, stop them | `py -3.13 tools\drishti.py status`, `py -3.13 tools\drishti.py stop all` | `python3 tools/drishti.py status`, `python3 tools/drishti.py stop all` |
| 5. Use it | open `http://localhost:17480`, sign in as `drishti-dev-admin` / `drishti-dev-admin123` (the demo profile also made `drishti-dev-risk` / `drishti-dev-risk123`, `drishti-dev-genomics` / `drishti-dev-genomics123` and `drishti-dev-market` / `drishti-dev-market123`, each seeing only its packs), type `VAR BANK` and press Enter | the same |
| ... or prove it on the command line | `py -3.13 tools\drishti.py doctor --acceptance` | `python3 tools/drishti.py doctor --acceptance` |

**You should see** in step 2 `[  ok] libraries`, `drishti.python=<this Python>`, "Drishti is ready." and, as the last next step,
`start all --demo`: while a `[FAIL]` remains the next steps name the fix instead (a missing library: the pip command to run). Step
4 ends with `console: up on http://<address>:17480` and runs the console on the Python setup wrote (`drishti.python`); it never makes
an environment or installs anything. In step 5 `VAR BANK` shows the bank's 99% one-day VaR **167,372,692** and expected shortfall
**210,131,972**. No command needs `--settings`: every `tools/drishti.py` command takes `~/drishti-envs/dev.ini` by itself when that file
names this folder as `drishti.home` (setup writes it so); pass `--settings <file>` for another.

### 3.2 The same, step by step

Build the demo lake once (about ninety tables, sixty business days ending today, about 15 minutes and 3.4 GB under `data/delta/demo/`;
with the jar built, Drishti's Java loader writes it), then start both programs with `tools/drishti.py`:

```bash
python tools/drishti.py lake demo --engine java                 # ~/drishti-envs/dev.ini is taken by itself (it names this folder)
python tools/drishti.py start all --demo                         # the server on :18480, then the console on :17480, in the background
python tools/drishti.py status                                               # both running and UP, with the addresses to open
python tools/drishti.py stop all                                             # the console, then the server (by their pid files)
```

On Windows (`cmd` or PowerShell; Java and Python only) the same commands run as `py -3.13 tools\drishti.py ...` (the settings file
`%USERPROFILE%\drishti-envs\dev.ini`, taken by itself).

**You should see** `server: up on http://<this machine's address>:18480` and `console: up on http://<this machine's address>:17480`, with a
warning that the demo's **development** secrets are in use (fine on your machine, never on a shared one). Open the console's address,
sign in as `drishti-dev-admin` (password `drishti-dev-admin123`; the three demo users are in [the production standard, section 1.3](admin/PRODUCTION_STANDARD.md#13-the-demo-profile), and every one of these accounts shows a red warning in Admin until its password is changed, never in production), type `TRD 1000001` and press Enter: an ENDUR commodity swap with its
legs and cash flows. `VAR BANK` answers the bank's value at risk.

To watch a program's output, start each in the foreground in its own terminal instead (`Ctrl+C` stops it):
`python tools/drishti.py start server --demo`, then `python tools/drishti.py start console --demo` (no `--background`).
Logs are in `data/logs/` (`server.log`, `console.log`), process ids in `data/run/`. Every option of the commands (ports, heap, an
env-file for your own secrets, a cluster of several servers with `--node`, the console's `--server`), where the files go, the exit codes
and what to do when a command stops with an error: [DEPLOYMENT_AND_QA_GUIDE.md › section 7](DEPLOYMENT_AND_QA_GUIDE.md#7-start-the-server-and-the-console)
and [CLI_REFERENCE.md](guides/CLI_REFERENCE.md#setup-build-jar-start-stop-and-status-install-build-run-and-stop-drishti).
A cluster of three servers and a console on this machine, from one file: `python tools/drishti.py start cluster`, `python tools/drishti.py status --cluster deploy/stack/stack.yaml`, `python tools/drishti.py stop cluster`
([deploy/stack/README.md](guides/CLUSTER_GUIDE.md#deploystack-a-ready-made-local-drishtinet-cluster)). From the IDEs: [IDE_GUIDE.md](guides/IDE_GUIDE.md). Why each
setting: [OPERATIONS_GUIDE.md › Start the server](guides/OPERATIONS_GUIDE.md#25-start-the-server) and
[› Start the console](guides/OPERATIONS_GUIDE.md#26-start-the-console).

Everything the two write at run time is under `data/` (git-ignored except `data/testdata/`); deleting `data/delta`, `data/bi-store` and
`data/cache` while both are stopped starts you fresh.

### Starting a white-labelled instance

The same build starts as **Drishti Risk**, **Drishti Finance**, **Drishti Genomics** or **Drishti All** with one argument
(`config/distributions/drishti.<name>.yaml`; [DISTRIBUTIONS.md](architecture/KERNEL_AND_MODULES.md)). Without it you run Drishti All, every
module: what the drill, the page walk and the tutorials use.

| Distribution | Start (Windows: `py -3.13 tools\drishti.py ...`) | You should see |
|---|---|---|
| Drishti Risk | `python tools/drishti.py start all --demo --distribution risk` | **Drishti Risk**, navy dark; Risk, Counterparties and Markets menus; `VAR BANK` works, `ECON CAN` answers *not in Drishti Risk*; Help titled *Drishti Risk Help* |
| Drishti Finance | `python tools/drishti.py start all --demo --distribution finance` | **Drishti Finance**, light; Economies, Markets and Companies menus, no Risk menu; `CRV` works, `VAR BANK` answers *not in Drishti Finance*; Help titled *Drishti Finance Help* with no VaR guide |
| Drishti Genomics | `python tools/drishti.py start all --demo --distribution genomics` | **Drishti Genomics**, green; the kernel's menus only; Admin → Health: module `genomics` *not installed* (the sample ships later) |
| Drishti All | `python tools/drishti.py start all --demo` | **Drishti**, exactly as above |

`python tools/drishti.py status` shows each process's distribution; Admin → White label shows them all side by side. The
setting behind the argument is `DRISHTI_DISTRIBUTION` (both programs read it).

**In the IDEs:** [IDE_GUIDE.md › A distribution](guides/IDE_GUIDE.md#34-a-distribution-risk-or-finance). Every step, Linux, macOS and Windows:
[DEPLOYMENT_AND_QA_GUIDE.md › 7.7](DEPLOYMENT_AND_QA_GUIDE.md#77-starting-a-white-labelled-instance).

## 4. The repository map

Two programs: the **server** (Java 21, Spring Boot, a Maven reactor) computes every number and lays out every screen; the **console**
(Python, FastAPI and Jinja2) renders what the server sends, and the browser draws it. Domains are **modules**: the core plus banking,
capitalmarkets, economy, market-data, trading and one module per trading system (`source-endur`, `source-calypso`, …). The modules sit in subject folders (`kernel/`, `compute/`, `lake/`, `net/`, `plugins/`, `modules/`, `packaging/`, `testing/`, `drishti-sdk/`); the full map, module by module: [DEVELOPER_GUIDE.md › The repository](guides/DEVELOPER_GUIDE.md#1-the-repository).

| Where | What |
|---|---|
| `drishti-api`, `drishti-common` | the source SPI (Spring-free) and the shared types, errors and file-date rules |
| `drishti-engine`, `drishti-rachana`, `drishti-inference` | the view pipeline, Drishti's side of Rachana (its binder and hooks; the language, the compiler and the live tables are the Rachana product's jars), layout inference, links |
| `drishti-registry` | modules, lenses, functions and their switches: what is on at this site |
| `drishti-banking`, `drishti-compute-banking` | the banking module: VaR and ES, exposure, FRTB-SA, SIMM, sensitivities, the MR cube; its compute kernels |
| `lake-io`, `lake-delta`, `lake-iceberg`, `lake-load` | the lakes: the lake core (their files and file access), Delta, Iceberg, and loading (the parallel loader, the data inbox, the in-memory lake) |
| `drishti-identity`, `drishti-net`, `drishti-compute`, `drishti-bicompute`, `drishti-tables`, `drishti-flight`, `drishti-ai` | users and tokens; Drishti\*net (clusters); compute workers; the pivot and BI engine; tables and models; Arrow Flight; the AI layer |
| `drishti-server` | the Spring Boot application: controllers, the core's configuration, the exec jar (`lib/*-exec.jar`) |
| `drishti-app` | the assembly the release ships: the server with the banking module and the plugins (`-pl :drishti-app -am` builds the jar only; `build all` adds the modules) |
| `plugins/drishti-connectors`, `plugins/drishti-plugin-*` | the connectors: the built-in light ones (file, rest, feed, news, demo, yfinance) in one module, and a plugin each for delta, iceberg, jdbc, kafka, s3 and more ([DATA_GUIDE.md](guides/DATA_GUIDE.md)) |
| `drishti-elements`, `drishti-console/web/static/elements/` | Drishti Elements, the JavaScript libraries every screen is made of (core, inputs, grid, chart, data, insight, renderer, risk, graph, tree, designer), and their WebJar ([ELEMENTS_API.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/API.md)) |
| `drishti-console/` | `run_drishti_web.py`, `core/` (one module per feature), `routes/`, `web/templates/`, `plugins/<module>/` (a module's console pages, help and static files: `banking`, `economy`), `config/` (`application.yaml`, `help.yaml`), `tests/` |
| `modules/<module>/packs/<pack>/` (banking's in `modules/banking/packs/`), `config/packs/core/` | the packs (content, not code): the modules' and the kernel's `core` ([BUILD_GUIDE.md](guides/BUILD_GUIDE.md)); a source module's connector templates in `modules/source-<system>/connectors/` |
| `config/` (the rest) | `modules/`, `lenses/<module>/`, `lakes/`, `engines/`, `tables/`, `bi/`, `feeds/`: the site's switches and files ([CONFIGURATION.md](admin/CONFIGURATION.md)) |
| `bin/` | a README only: every command is `tools/drishti.py` (a node's drain: `drishti.py node drain`, [CLUSTER_GUIDE.md](guides/CLUSTER_GUIDE.md)) |
| `tools/` | `drishti.py` (the command line: `setup`, `build jar`, `start`, `stop`, `status` and the rest), `lake/`, `docs/` (screenshots, the PDFs), `drill.sh` (the release drill) |
| `run-configs/`, `drishti-console/run-configs/`, `drishti.code-workspace` | the IntelliJ launchers (project `drishti`), the PyCharm launchers (project `drishti-console`) and the VS Code workspace ([IDE_GUIDE.md](guides/IDE_GUIDE.md), [IDE_GUIDE.md section 10](guides/IDE_GUIDE.md#10-visual-studio-code)) |
| `docs/` | `guides/`, `tutorials/`, `admin/`, `architecture/`, `contracts/`; the index is [docs/README.md](README.md) |
| `drishti-it`, `drishti-benchmarks` | the architecture and repository rule tests; the JMH benchmarks |

## 5. The fast checks

Run these before every commit; each takes seconds to a few minutes. The whole test strategy is
[DEVELOPER_GUIDE.md › Testing strategy](guides/DEVELOPER_GUIDE.md#6-testing-strategy).

| Check | Command | What it covers |
|---|---|---|
| The quick file checks | `python3 -m pytest -q -m static drishti-console/tests` | every test that reads files and starts nothing: docs and help, asset names, branding, CSS tokens, guards, licences |
| The Elements unit tests | `node --test drishti-console/tests/elements/` (or `pytest drishti-console/tests/test_elements_node.py`) | the libraries in Node, no browser |
| One browser file | `python3 -m pytest -q drishti-console/tests/test_elements_gallery_browser.py` | Chromium on a real console; name the file of the screen you changed |
| One Java module | `./mvnw -q verify -pl :drishti-registry -am` | that module's tests and the rules it must keep |
| Several targeted tests together | `./mvnw -q verify -pl :drishti-registry,:drishti-engine -am -Dtest='A*Test,B*Test' -Dsurefire.failIfNoSpecifiedTests=false` | build once, name every test you need in one Maven run, never one Maven run per test |
| What your branch touched | `tools/drill.sh --affected` (`--base REF`, default `origin/develop`; `--dry-run` lists the selection) | only the Maven modules and test files the diff can reach; shared infrastructure selects more; never a release proof |
| The gate | `tools/drill.sh --gate` | everything the release drill checks first (headers, generated files, docs, static tests, the rule tests and one build); a few minutes, on any branch, no push |

**You should see** pytest end with `passed` and no `failed`, and `drill.sh --gate` end with its summary and exit 0. Read the exit code
before trimming a long output: `… ; echo "exit=$?"`. The gate is the one check the release drill runs first; run it before you hand a
branch over. Never run two heavy jobs (a gate, a drill, a full verify) at once on one machine.

## 6. The rules a change must follow

Most rules are tests, so the fast checks above catch them; the rest is review. The full list with the test of each:
[DEVELOPER_GUIDE.md › Project rules](guides/DEVELOPER_GUIDE.md#3-project-rules).

| Rule | Held by |
|---|---|
| **No bespoke UI**: every screen, designer and editor is built from Drishti Elements; a missing piece is added to a library, never written for one page | `test_elements_guards.py`, `test_designer_kit_guard.py`, `test_admin_forms_guard.py`, `test_native_select_guard.py` |
| **CSS tokens**: colours, spacing and type come from the tokens, never literals | `test_css_tokens.py` |
| **Domain boundaries**: the core never imports a module; banking code lives in the banking module and its console plugin | `test_module_boundaries.py`, `ArchitectureRulesTest` |
| **The servers compute, the browser draws**: every risk and BI number is computed in Java on the server | review; [ARCHITECTURE.md](architecture/ARCHITECTURE.md) |
| **Branding from configuration**: no product name or owner in code or templates (`ui.product`, `drishti.branding.*`) | `test_branding_guard.py` |
| **Our files are named `drishti_<name>`** (JS, CSS, images we write); vendored libraries untouched under `static/vendor` with `VENDOR.md`; nothing from a CDN | `test_assets_policy.py` and the asset-name tests |
| **The kernel names no domain**: banking's words reach the core through `DomainVocabulary` and the console's plugin hooks ([KERNEL_AND_MODULES.md](architecture/KERNEL_AND_MODULES.md)) | `tools/domain_strings.py` (allow-list `tools/domain_strings_allowed.yaml`, kept empty), `KernelWithoutModulesTest`, `ApiSurfaceTest` |
| **No copy and paste**: duplicated lines per language never rise above `tools/cpd/baseline.txt` (only lowered) | `tools/duplicates.py` in the gate |
| **The copyright header** on every source file; no source file over 2,000 lines | `LicenseHeaderTest`, `tools/license_headers.py`, `SourceFileSizeTest` |
| **Docs with every change**: the guide, tutorial, Help entry and CHANGELOG that a change affects are updated in the same commit; one home per fact (link, do not copy); no bugs or version history in customer documents | `test_docs_rot.py`, `test_docs_gates.py`, `test_help*.py`, `test_guide_images.py` |
| **Settings**: a new setting is declared where the generator finds it and appears in CONFIGURATION.md | the generated-document checks of the gate |
| **Commits**: authored as the repository owner, plain sentences that say what changed for a user or an operator, no tool or co-author trailers | review ([DEVELOPER_GUIDE.md › Git](guides/DEVELOPER_GUIDE.md#38-git)) |

## 7. Your first change, end to end

Every change takes the same steps, however small; the example is a change to how a grid behaves on one screen, which touches a library,
a test, a guide and a screenshot together.

1. **Branch** from `develop`: `git switch -c my-first-change origin/develop`.
2. **Find the one home.** Search before writing: `git grep -n "the thing you change"` across `drishti-console/`, `config/` and `docs/`.
   A screen is made of Elements: change the library (`drishti-console/web/static/elements/<library>/`) or the page's template, never a
   one-off widget ([ELEMENTS_API.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/API.md)). A pack change is YAML in its module's pack folder (`modules/<module>/packs/<pack>/`; `config/packs/core/` for the kernel's) ([BUILD_GUIDE.md](guides/BUILD_GUIDE.md)).
   A Java change follows the recipes of [DEVELOPER_GUIDE.md › Recipes](guides/DEVELOPER_GUIDE.md#5-recipes) (a REST endpoint, a
   configuration property, a console page, a source plugin).
3. **Write the test first** beside its neighbours: a `drishti-console/tests/test_<area>.py` for the console (a `*_browser.py` when it needs
   Chromium), a JUnit test in the module for Java, a `tests/elements/<lib>.test.mjs` for a library.
4. **Make the change**, then run the server and the console (section 3) and look at it: the page, at desktop width and at phone width
   (390 px, the browser's device toolbar).
5. **Update the docs in the same commit**: the guide section that describes the behaviour, the tutorial step if a step changes, the
   Help entry (`drishti-console/config/help.yaml`) if a page is added or moves, the screenshots of the step
   (`python3 tools/docs/screenshots.py --guide <guide>`), and one line under the open release in `CHANGELOG.md`.
   A setting goes into CONFIGURATION.md through its generator, never by hand.
6. **Run the fast checks** (section 5): `-m static`, the test files you touched, the Elements tests for a library change, then
   `tools/drill.sh --gate`.
7. **Commit** as the owner, one plain sentence of what changed for whom:
   `git -c user.name=ajsinha -c user.email=ajsinha@gmail.com commit -am "Pivot: the totals row stays visible while the grid scrolls"`.
8. **Push the branch** and hand it over; `main` moves only through the release drill ([DEVELOPER_GUIDE.md › The drill](guides/DEVELOPER_GUIDE.md#26-the-drill-toolsdrillsh)).

**You should see** the gate pass on your branch, the docs checks green (no broken link, no missing picture, Help listing the page) and
your change in the running console.

## 8. Where to go next

| You want to | Read |
|---|---|
| understand how a request travels through the code | [DEVELOPER_GUIDE.md › How a request travels](guides/DEVELOPER_GUIDE.md#4-how-a-request-travels-through-the-code) |
| add a pack, a kind, a Sutra or a screen | [BUILD_GUIDE.md](guides/BUILD_GUIDE.md) and [tutorial 19](tutorials/19-build-a-pack-and-a-screen.md) |
| add or change a connector | [DEVELOPER_GUIDE.md › Connectors](guides/DEVELOPER_GUIDE.md#10-connectors-choosing-combining-and-writing-one) |
| use or extend the Elements libraries | [ELEMENTS_API.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/API.md) |
| every command of `drishti.py` | [CLI_REFERENCE.md](guides/CLI_REFERENCE.md) |
| every setting | [CONFIGURATION.md](admin/CONFIGURATION.md) |
| install, deploy and verify a release on Windows, Linux or macOS | [DEPLOYMENT_AND_QA_GUIDE.md](DEPLOYMENT_AND_QA_GUIDE.md) |
| the browser test harness | [TEST_HARNESS.md](architecture/TEST_HARNESS.md) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
