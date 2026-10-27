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

The reference for getting Drishti onto a machine: install the tools, build the server, build the **demo lakes** from the
committed test data, start the server and the console, sign in, make a personal API token for the command line, and the other
ways to build and run (an IDE with no terminal, a system Maven, Docker, a different configuration file, Windows). It does not
teach the product: the first quarter of an hour in it is [tutorial 1](../tutorials/01-first-15-minutes.md), and the
[tutorials](../tutorials/README.md) take you through the rest. From a clean clone the whole install takes about 15 minutes on a
laptop, most of it the first build.

You will:

1. install the tools;
2. download and build Drishti;
3. build the demo lakes (sixty business days of production-shaped data, one command);
4. start the **server** (the part that reads data) and the **console** (the web pages you use);
5. sign in, and make an API token for scripts and the command line.

The short form of steps 2 to 5 is [data/testdata/README.md](../../data/testdata/README.md) ("Quick start: five steps"); this guide
is the same path with every option and what you should see at each step.

## What Drishti is, in one minute

Drishti is a data viewer that you drive from the keyboard, like a Bloomberg terminal. You type a short
**command** and press Enter, and a full screen of figures, tables and charts appears:

```text
TRD 1000001             a trade booked in ENDUR: a commodity swap, its legs side by side, cash flows and resets
TRD MX-20000001         a trade booked in MUREX: an interest rate swap
CTRD CP-NORTHPLAINS     every live trade with one counterparty, payments due, maturity ladder
NSET NS-SUMMIT-NY       a netting set: the trades with one counterparty under one agreement, exposure, CVA
CPTY CP-NORTHBRIDGE     a counterparty: UEN, country of risk, legal entity, netting sets
```

The first word is a **mnemonic** (a short code for a kind of thing: `TRD` is a trade). The second is the **identifier** of
one thing of that kind. In the docs we write `<GO>` after a command to mean "press Enter".

There are two programs:

| Program | What it does | Address |
|---|---|---|
| **Server** (Java) | Reads data from the sources (Delta lakes, databases, files, message streams), lays out each screen, answers BI queries, pushes live updates | `http://localhost:18480` |
| **Console** (Python) | The web pages you see in your browser; it computes nothing itself, the server does | `http://localhost:17480` |

You always open the **console** in your browser. The console talks to the server for you.

Industries are added as **packs**. Seven ship with Drishti and load by default:

| Pack | What it knows | Builds on |
|---|---|---|
| `core` | counterparties (UEN, country of risk), groups, issuers, CCPs, legal entities, books, desks, traders, calendars | nothing |
| `market-data` | curves, vol surfaces and cubes, FX, fixings, indices, equities, commodities, bonds | `core` |
| `trading` (`TRD`) | trades from three source systems (ENDUR, CALYPSO, MUREX), one view of them | `core`, `market-data` |
| `counterparty-risk` | agreements, CSAs, netting sets, collateral, margin calls, limits, exposure, CVA, SA-CCR, SIMM | `trading` |
| `market-risk` | trade valuations, sensitivities, VaR, stress, P&L explain | `market-data`, `trading` |
| `world-economy` | economies, macro indicators, forecasts, elections, parties, regions (public data) | nothing |
| `news-and-markets` | news topics, listed companies, quotes (fetched on demand) | nothing |

Every attribute of every kind is written in UPPER_SNAKE (`TRADE_ID`, `PRODUCT_TYPE`, `COUNTERPARTY`, `LEGS` → `CASH_FLOWS` →
`RESETS`), the way the production source systems name them. Retired example packs (finance, genomics, logistics, retail
banking, climate, operational and liquidity risk) live in `examples/packs/` and load only when you ask for them
([PACKS.md](PACKS.md)).

## Two ways to run it: the IDE or a terminal

| | You need | Read |
|---|---|---|
| **IntelliJ IDEA and PyCharm, no terminal** | the two IDEs, IntelliJ on the repository root and PyCharm on `drishti-console/` (never both on one folder); the ready-made run configurations in `run-configs/` (Java) and `drishti-console/run-configs/` (Python) (**Build demo lakes**, **Drishti build server jar**, **Drishti server (demo lakes)**, **Drishti console**, a QA profile, the CLI tools) | [IDE_GUIDE.md](IDE_GUIDE.md); this guide's steps 1 and 2, then run the configurations in the order of steps 3 to 7 |
| **A terminal** | the commands below | this guide |

Both give the same result: the run configurations hold exactly the settings the terminal commands below spell out.

## Step 1 · Install the prerequisites

You need a Linux or macOS machine with the tools below. On Windows, Drishti runs natively (no WSL, no Hadoop
`winutils.exe`): follow [Drishti on Windows](#drishti-on-windows), which covers the same steps in PowerShell.

| Tool | Version | Why | Check with |
|---|---|---|---|
| OpenJDK | **21** or newer (production runs 21) | builds and runs the server | `java -version` |
| Python | 3.11 or newer (3.13 recommended) | runs the console and the command-line tools | `python3 --version` |
| uv | any recent (**recommended**) | makes the console's Python environment; works where `python3 -m venv` cannot | `uv --version` |
| git | any | downloads the code | `git --version` |
| Docker | optional | only to run everything in containers, or the PostgreSQL/Kafka/… extras | `docker --version` |

On Ubuntu or Debian:

```bash
sudo apt install openjdk-21-jdk git curl             # any JDK 21 or newer works
curl -LsSf https://astral.sh/uv/install.sh | sh      # installs uv into ~/.local/bin
```

Drishti builds and runs on Java 21 or newer and refuses an older one; Java 21 is the production target, Java 25 is also tested.
Point `JAVA_HOME` at a JDK 21 or newer in every terminal you use for Drishti. On Ubuntu the path is:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

You should see a line that starts with `openjdk version "21.` (or a newer version if you installed one).

> **Tip:** add the two `export` lines to your `~/.bashrc` so new terminals get them too.

You do **not** need to install Maven. The repository has its own copy (`./mvnw`, Maven 3.9.12).

## Step 2 · Download the code

```bash
git clone https://github.com/ajsinha/drishti.git
cd drishti
```

Every command in the rest of this guide is run **from this `drishti` folder** (the repository root). The server finds the packs
(`./config/packs`) and its data folder (`./data`, or `DRISHTI_DATA_DIR`) relative to where you start it. The clone already holds
the test data the demo lakes are built from: `data/testdata/` (about 23 MB of `.jsonl.gz`, the one folder under `data/` that is
committed).

## Step 3 · Build the server

```bash
./mvnw -q package -DskipTests -pl drishti-server -am
```

The first build downloads its libraries and takes several minutes; later builds take about a minute (measured: 1 min 12 s on a
24-core laptop with the libraries cached). `-q` keeps the output quiet, so a successful build prints nothing. `-pl drishti-server
-am` builds the server and the modules it needs; `./mvnw -q package -DskipTests` builds every module (the benchmarks and the
integration tests too), which is slower and gives the same server. When it finishes, check that the server program was built:

```bash
ls drishti-server/target/*-exec.jar
```

You should see `drishti-server/target/drishti-server-2.9.2-exec.jar`. In the IDE this step is the run configuration
**Drishti build server jar**.

> **Note:** `./mvnw -q verify` builds **and** runs every test (much longer). Use it when you change code; for a first try,
> `package -DskipTests` is enough.

If the build stops with `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …`, your
`JAVA_HOME` is older than Java 21. Go back to Step 1.

## Step 4 · Set up the console's Python

Create a private Python environment for the console, the same environment the command-line tools (`tools/drishti.py`) and the
lake builder use, and install its libraries into it. With **uv** (recommended):

```bash
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
```

uv fetches Python 3.13 if the machine does not have it, then installs FastAPI, Uvicorn, PyYAML, pandas, pyarrow, deltalake and the
rest. Check it:

```bash
drishti-console/.venv/bin/python -c "import deltalake, pyarrow, yaml, fastapi; print('ok', deltalake.__version__)"
```

You should see `ok 1.6.6` (or a newer deltalake).

**Without uv**, plain Python works where its `venv` module is complete:

```bash
python3 -m venv drishti-console/.venv
drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt
```

> **`ensurepip is not available`.** On Debian and Ubuntu the system Python ships without `ensurepip` until you install the
> matching `python3.X-venv` package, and `python3 -m venv` stops with *The virtual environment was not created successfully because
> ensurepip is not available … apt install python3.14-venv* (seen on Ubuntu with Python 3.14.4). Either install that package
> (`sudo apt install python3.14-venv`, matching your `python3 --version`) or use uv as above, which needs neither.

If you will use **Calc** (Python in the browser on a view), install its runtime once with `tools/fetch-pyodide.sh` (about a 340 MB
download, cached; on Windows run it in Git Bash or WSL). Without it a Calc panel stays dead; see
[PYTHON_CALC.md](PYTHON_CALC.md) section 12 for the offline way. BI never computes in your browser: every number on a BI page comes
from the server.

## Step 5 · Build the demo lakes

The demo lakes are what the seven packs read: one Delta table per dataset, sixty business days each, built from the committed
test data in one command:

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

In the IDE this is the run configuration **Build demo lakes**. What you should see (the first lines, then the summary; the folder
is your clone's):

```text
  trading/trade_endur: … rows over 60 days
  trading/trade_calypso: … rows over 60 days
  trading/trade_murex: … rows over 60 days
  legal/agreement: … rows over 60 days
  market/bond: … rows over 60 days
  reference/book: … rows over 60 days
  …
  news/listed-company: … rows over 60 days
  news/news-topic: … rows over 60 days

Demo lake ready: …/drishti/data/delta: 84 tables, 60 business days 2026-07-08 to 2026-09-30, built in 943 s
Feeds: …/drishti/data/feeds/fixing.jsonl (the file connector's sample feed)
Server: no server at http://localhost:18480: left …/drishti/data/bi-store/lake-rebuilt.json, so a server clears its stored BI columns and rollups at its next start
Connector files:
  civic-store: generated by the server at its first start (reads …/drishti/data/delta)
  …
  trade-calypso: generated by the server at its first start (reads …/drishti/data/delta)
  trade-endur: generated by the server at its first start (reads …/drishti/data/delta)
  trade-murex: generated by the server at its first start (reads …/drishti/data/delta)
```

The summary line is from a sixty-day run of 2.6.5 that kept the test data's own dates; by default the days are moved to end on today's
business date and the line adds *(the test data's 2026-07-08 to 2026-09-30, moved to end on your date)*. The per-table row counts were
captured on the seven-day lakes of 2.5.0 and are left out here (each line reads `N rows over 60 days`, the sum over the days: up to
5,000 trades a day per source system). Measured for sixty days: **943 s (about 15 minutes), about 2.2 GB on disk** (the 807 MB peak
memory was measured on the seven-day lakes). What it did:

- **Sixty business days, ending on today's business date.** The test data's own days (2026-07-08 to 2026-09-30) are
  moved to end on today's business date in New York (on a weekend or holiday, the business day before), with the documents' own dates of the day (`AS_OF`,
  `VALUATION_DATE`, `RUN_DATE`, `SOURCE_UPDATE_TIME`) moved with them, so every page opens on data. `--as-of data` keeps the test
  data's dates; `--as-of 2026-10-30` ends on any date you choose.
- **One table per dataset under `data/delta/<domain>/<kind>/`**, partitioned by business date. The trades go to one table per
  source system, `data/delta/trading/trade_endur`, `trade_calypso` and `trade_murex`, partitioned by business date and
  `PRODUCT_TYPE`; the three connectors `trade-endur`, `trade-calypso` and `trade-murex` read them and serve one kind, `trade`,
  together.
- **Told the server.** A running server gets the data-load signal for every table and date it wrote (`--server URL`, default
  `DRISHTI_SERVER` then `http://localhost:18480`; with sign-in on, a token with the `loads:write` scope in `--token-file` or
  `DRISHTI_TOKEN`). With no server running it leaves `data/bi-store/lake-rebuilt.json`, and the next server start clears the BI
  store's stored columns and rollups so no old figure is served. Running `lake demo` again later is safe either way: a running
  server also notices each rebuilt Delta date by itself. `--no-signal` skips both.
- **Checked the connector files.** The packs' Delta connectors read `${DRISHTI_DELTA_ROOT:<data>/delta}`; with the default lake
  nothing changes and the server writes the files at its first start ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)).

Other options: `--lake DIR` builds elsewhere (the connector files are then pointed there, their earlier text kept under
`data/connectors/history/`), `--only trades/,core/counterparty` builds some datasets, `--with-real FILE` adds a local real extract to
your local lake only (never to `data/testdata`). The whole list: `drishti.py lake demo --help` and [TOOLS.md](TOOLS.md).

> **Skip this step** and the server still starts: the packs' sample documents (served live by the demo source) answer for today,
> and a view on a picked past date says "*No data held for <date>*".

## Step 6 · Start the server

There are two ways to start it. Pick the first to explore on your own, the second for the demo lakes as the run configurations
set them up (sign-in on, every page read from the lakes).

**A. On the demo lakes, with sign-in** (the run configuration **Drishti server (demo lakes)**):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets
export DRISHTI_STUDIO_SAVE=true
export DRISHTI_SECURITY_ENABLED=true
export DRISHTI_TOKEN_SECRET=drishti-dev-only-token-secret-change-me-0123456789
export DRISHTI_SEED_ADMIN=true DRISHTI_SEED_USERNAME=drishti-dev-admin DRISHTI_SEED_PASSWORD=drishti-dev-admin123
export DRISHTI_EXTERNAL_LIB=$PWD/external_lib
export DRISHTI_DEMO_ENABLED=false
mkdir -p data/tmp/server
java -Djava.io.tmpdir=data/tmp/server -Xmx4g -jar drishti-server/target/drishti-server-2.9.2-exec.jar
```

**B. The smallest start** (sign-in off, the packs' samples ticking live):

```bash
java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.2-exec.jar
```

What the settings do:

| Setting | Meaning |
|---|---|
| `-Djava.io.tmpdir=data/tmp/server` (and the `mkdir` before it) | Java's temp folder for this server: DuckDB, RocksDB and CatBoost unpack their native libraries there (DuckDB 60 MB a start), not in `/tmp`, and the server removes stale copies from it at start. Only the command line moves it (since JDK 17); without it the server warns in its log and on Admin, Health. `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=…` works too. [CONFIGURATION.md](../admin/CONFIGURATION.md#pass--djavaiotmpdir-to-java) |
| `DRISHTI_PACKS` | The packs to load, comma-separated. A pack's parents load with it: `market-risk` brings `core`, `market-data` and `trading`. Without it the same seven load (they are the default). |
| `DRISHTI_STUDIO_SAVE=true` | Lets the Build workbench save Sutras (the view layouts). Leave it out if you will not edit Sutras. |
| `DRISHTI_SECURITY_ENABLED=true` | Sign-in on: every call needs a token. Off by default. |
| `DRISHTI_TOKEN_SECRET` | The secret the server and the console share to sign tokens (at least 32 characters). The value above is the run configurations' development secret: never use it on a shared machine. |
| `DRISHTI_SEED_ADMIN`, `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` | On the first start with no users, create this administrator (the values above are the defaults too). |
| `DRISHTI_EXTERNAL_LIB` | The folder of extra jars and Python modules (`external_lib/java`, `external_lib/python`); default `external_lib` in the repository. |
| `DRISHTI_DEMO_ENABLED=false` | The demo source (the packs' samples, ticking) off, so every page reads the lakes. With it on (the default) a sample answers before the lake for an id both hold; tutorial 12's live tiles need it on. |

Leave this terminal open: the server runs in it and writes its log there. After a few seconds you should see:

```text
… The lake was rebuilt while the server was stopped ({ … "by": "drishti.py lake demo" …): the BI store's columns and rollups were cleared and are built again from it
… Started DrishtiApplication in 7.995 seconds (process running for 8.604)
```

(The first line only once after a `lake demo` with no server running.) Check it from a second terminal; the health address is
open even with sign-in on:

```bash
curl -s localhost:18480/actuator/health
```

You should see `{"status":"UP","groups":["liveness","readiness"]}`. With sign-in off (start B) you can list the packs at once:

```bash
curl -s localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
```

`['core', 'market-data', 'trading', 'counterparty-risk', 'market-risk', 'world-economy', 'news-and-markets']`. With sign-in on the
same call answers `401 DRS-5010 missing bearer token` until you send a token (Step 9).

At its first start the server writes one **connector file** per connector the packs suggest into `config/connectors/`
(`trade-endur.yaml`, `market-store.yaml`, …) and a generated marker for each under `data/connectors/generated/`; edit the YAML to
point a connector elsewhere ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)).

## Step 7 · Start the console

Open another terminal in the `drishti` folder. With the server of start A (sign-in on), the console needs the same token secret
and sign-in on (the run configuration **Drishti console**):

```bash
export DRISHTI_TOKEN_SECRET=drishti-dev-only-token-secret-change-me-0123456789
export DRISHTI_AUTH_ENABLED=true
export DRISHTI_SESSION_SECRET=drishti-dev-only-session-secret-change-me-0123456789
export DRISHTI_SECURE_COOKIE=false        # only for plain http on your own machine
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py --server.port=17480 --backend.url=http://127.0.0.1:18480
```

With the server of start B (sign-in off), none of the `export` lines is needed:

```bash
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

You should see:

```text
INFO:     Uvicorn running on http://0.0.0.0:17480 (Press CTRL+C to quit)
```

followed by a line for each of the console's four worker processes (`server.workers`, `DRISHTI_CONSOLE_WORKERS`; `1` runs a single
process).

> **The two must match.** A console started without `DRISHTI_TOKEN_SECRET` against a server with sign-in on cannot read anything:
> its log says `business date unavailable: DRS-5010 missing bearer token` and pages stay empty. Start both with sign-in, or both
> without.

Now open **http://localhost:17480** in your browser. You see the landing page with an animated eye. Click **Open the terminal**,
or go straight to **http://localhost:17480/t**.

## Step 8 · Sign in, and change the admin password

With sign-in on you are sent to **Sign in**. On the first start with no users the server created the development administrator:

| User | Password |
|---|---|
| `drishti-dev-admin` | `drishti-dev-admin123` |

1. Sign in with those.
2. Click your name in the top bar (or go to **http://localhost:17480/account**).
3. Under **Change password**, type the current password, then a new one twice (at least 10 characters, with letters and digits),
   and click **Change password**.

Until you do, the admin's *Users* page warns that the development password is still in use. Five wrong passwords in a row lock an
account for 15 minutes.

With sign-in off (start B) there is no sign-in: you act as the development user *Ash* with every role, and the top bar's user menu
says *Sign-in is off on this server*.

**Admin** in the top bar opens the administration pages, one tab each: *Users* (create users, give roles and packs), *Roles*,
*Packs*, *Tokens*, *Access*, *Connectors*, *Artifacts*, *AI*, *Data loads*, *Collaboration*, *Embedding*, *BI*, *Audit log*,
*Health* and *Caches* (*Lakes*, `/admin/lakes`, opens from *Connectors* and *Data loads*). Users, roles and
packs per user are explained in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

> **Production:** set `DRISHTI_SEED_ADMIN=false` (or your own `DRISHTI_SEED_USERNAME`/`DRISHTI_SEED_PASSWORD`, as the Docker
> setup does), and long random secrets (`openssl rand -hex 32`). See [OPERATIONS.md](../admin/OPERATIONS.md).

## Step 9 · Your first views

Click in the command line (or press `/`), type `TRD 1000001` and press Enter: an ENDUR commodity swap, the deal ticket, its legs
side by side, the cash flows opening to their resets, the payment ladder. The demo pages, on the console's port:

| Page | What it shows |
|---|---|
| `/v/trade/1000001` (`TRD 1000001`) | an ENDUR trade: the ticket, legs side by side, cash flows and resets, the payment ladder |
| `/v/trade/MX-20000001` (`TRD MX-20000001`) | a MUREX fixed/float interest rate swap |
| `/v/trade/CLY-3000157` | a CALYPSO government bond |
| `/v/counterparty-trades/CP-NORTHPLAINS` (`CTRD CP-NORTHPLAINS`) | a counterparty's live trades, payments due in 7/30/90 days, notional by product, maturity ladder |
| `/v/netting-set/NS-NORTHPLAINS-NY` | its netting set: every member trade, exposure, CVA, collateral |
| `/v/counterparty/CP-VAULTGUARD` (`CPTY CP-VAULTGUARD`) | a counterparty: UEN, country of risk, legal entity, netting sets |
| `/bi` | the shipped BI reports and models over the sixty business days |

**Compare across days**: on a trade page pick the first of the sixty dates `lake demo` printed, then **Compare** with the last; an
amended trade (its `TRADE_VERSION` moved) shows its changed rates, notionals, status or fees.

From there:

| You want to | Go to |
|---|---|
| Open a trade, search, try the form, look at BI and make a token, in a guided quarter of an hour | [Tutorial 1 · Your first 15 minutes](../tutorials/01-first-15-minutes.md) |
| Use mnemonics, type-ahead, links, a past business date and Compare | [Tutorial 2 · Look up anything](../tutorials/02-look-up-anything.md) |
| Search by value (`TRD where QUANTITY > 100m order by QUANTITY desc`: 6,062 of the 15,000 trades on the demo lakes), by form, save a search and a monitor | [Tutorial 3 · Query by form](../tutorials/03-query-by-form.md) and [USER_GUIDE.md](USER_GUIDE.md#search-by-value) |
| Set an alert and watch a list | [USER_GUIDE.md](USER_GUIDE.md#monitors) |

If a command gives `DRS-4001` the mnemonic is unknown (is its pack loaded?); if a view says `DRS-1001 no source holds …` the id does
not exist: type part of it and pick from the suggestions.

## Step 10 · A personal API token for the command line

Scripts, notebooks, Excel and `tools/drishti.py` call the server with a **personal API token**. Make one:

- **In the UI:** your name in the top bar → **My account** (`/account`) → **API tokens**: a name, how many days, and the scopes it may
  write with (none: it only reads; `loads:write` lets it announce data loads, `bi:write` edit BI items, `packs:admin` manage packs and
  connectors, …), then **Create**. The secret (`drk_<id>_<secret>`) is shown **once**: copy it into a file only you can read.
- **Over the API** (with a session token, as the console does): `POST /api/v1/me/tokens` with
  `{"name": "laptop", "days": 30, "scopes": []}` answers `201` with the token's record and its `secret`
  ([API_GUIDE.md](API_GUIDE.md#personal-api-tokens)).

Then give it to the command line in a file (never on the command line itself) and check it:

```bash
umask 077; printf '%s' 'drk_…' > ~/drishti-token        # the secret you copied
drishti-console/.venv/bin/python tools/drishti.py doctor --server http://localhost:18480 --token-file ~/drishti-token
```

The last lines of what you should see:

```text
GREEN  server                  http://localhost:18480: version 2.9.2, java 21.0.12.1, status UP
GREEN  token                   accepted; this token 'cqFV714maGDy' acts as drishti-dev-admin: scopes read, expires 2026-10-18T21:33:38.684Z
doctor: 0 red, 0 yellow
```

`DRISHTI_TOKEN` (the secret itself) and `DRISHTI_SERVER` (the base URL) work instead of the two options. A view in the terminal, as
the token's user sees it (masks apply):

```bash
drishti-console/.venv/bin/python tools/drishti.py view get trade/MX-20000001 --server http://localhost:18480 --token-file ~/drishti-token
```

```text
Rates · Interest rate swap (fixed/float)  MX-20000001  with CP-MERIDIAN-RE   [TRD]

Key figures
  figure      value
  ----------  ---------------
  Source      MUREX
  Side        Sell
  Notional    AUD 242,000,000
  …
```

A read-only token refuses any change (`403 DRS-5002 API tokens only read`); a token with a write scope must expire (at most 90 days
here, `writeMaxDays`). Revoke a token on the same page when you no longer use it. Every command: [CLI_GUIDE.md](CLI_GUIDE.md).

## Step 11 · Switch packs, themes and settings

- **Packs.** The box icon in the top bar shows how many packs you see (`7 packs`). Click it, untick the ones you do not need, and
  click **Apply**. The examples, suggestions and help follow your choice. You can only choose among packs the server loaded and an
  admin assigned to you.
- **Themes.** The palette icon offers ten themes: Terminal, Parchment, Blue, Wall Street, Green, Crimson, Crimson dark, and the three
  Terminal hues Green Terminal, Crimson Terminal and Blue Terminal ([USER_GUIDE.md › Themes](USER_GUIDE.md#themes): how to choose).
- **Settings.** *My account → Settings* (`/account`) keeps your theme, the page to open after signing in (for example
  `/v/trade/1000001`), the clock's time zone, density, whether changes flash, and how many search results to show.

## Step 12 · Find help

- **Help** in the top bar (or `F1` on any page) opens the help centre at `/help`, with the tutorials, every reference document and
  a search box. `F1` opens the help for the page you are on.
- The **?** in each panel's header explains that kind of panel.
- **About** (`/about`, linked in the footer) is the product page: what Drishti is, its features, measured performance, the technology
  and the licence. Versions and source health for admins are in *Admin → Health* (`/admin/health`).
- Each pack has its own guide under *Help → Domain packs*, with example commands.

## Where Drishti keeps things

Nothing Drishti writes is hidden (no dot-folders of its own), and everything it writes at run time is under **one data folder**,
`./data` by default (`DRISHTI_DATA_DIR` moves it):

| Folder | What is in it |
|---|---|
| `data/testdata/` | the committed test data the demo lakes are built from (the only committed folder) |
| `data/delta/` | the Delta lakes (`DRISHTI_DELTA_ROOT` moves them); `data/delta/_drishti-lake/` holds a lake's build log and lock |
| `data/bi-store/` | the BI store: typed Parquet columns and rollups read from the lakes (`DRISHTI_BI_STORE`); `lake-rebuilt.json` appears here when `lake demo` could not tell a server |
| `data/connectors/` | `generated/` (which connector files the server wrote from a pack's template) and `history/` (earlier text of every connector file changed by Admin, the API, the CLI or `lake demo`) |
| `data/identity/` | users, roles, tokens (`drishti.db`, SQLite), the audit log |
| `data/packs/` | the packs overlay `added.yaml` (packs loaded or switched off at run time) and installed pack archives |
| `data/feeds/` | the file connector's feeds (the SOFR history `lake demo` writes) |
| `data/loads/`, `data/governance/`, `data/collab/`, `data/reports/` | data-load history, BI governance, collaboration files, report output |
| `data/cache/` | the Delta connectors' disk caches, one folder per connector: `idmaps/` (id maps, memory-mapped) and `docs/` (documents read before, RocksDB); the Kafka disk caches |
| `data/derived/` | grouped pages kept on disk (`trade-pages`: counterparty, portfolio, legal entity and index trades), one folder per connector |
| `data/tmp/` | temporary files: `tmp/server/drishti-<pid>` and `tmp/console/drishti-console-<pid>`, removed when the process stops; `tmp/server` is also the JVM's `-Djava.io.tmpdir` (native libraries unpack there, not in `/tmp`), and `tmp/maven` is Maven's (`./mvnw`) |
| `data/net/` | Drishti\*net, when it is on: `peers.json` (the members it knew, to rejoin after a restart), `history/` (earlier text of the files in `config/net/`); the console keeps the servers it learned in its own data folder's `net/servers.json` |
| `data/qa/`, `data/build/`, `data/drill/` | the QA profile's own data, tool output (`lake demo` staging), the drill's logs |

The repository itself holds code and configuration: `config/` (packs, connector files, BI files), `run-configs/` and `drishti-console/run-configs/` (the IntelliJ and PyCharm run
configurations), `docs/`, `tools/`, `external_lib/`.

## Stopping and starting again

- Stop: press `Ctrl+C` in the server terminal and in the console terminal (or the red square in the IDE).
- Start again: repeat Step 6 and Step 7 (with the same `export` lines). No rebuild is needed unless the code changed. Your monitors,
  alerts, workspaces and settings are kept by the server under `./data`.
- New demo data: run Step 5 again, with the server running or not (it is told, or clears its BI store at its next start).
- Three servers as one cluster (Drishti\*net) with a console, on this machine: `drishti-stack/start.sh` ([drishti-stack/README.md](../../drishti-stack/README.md)).
  The nodes advertise this machine's real address (`DRISHTI_STACK_HOST`, found by `start.sh`), never `127.0.0.1`: members of a cluster are
  called by other machines, so a loopback or wildcard address is refused ([NET.md section 2](../admin/NET.md#2-a-cluster-of-three)). The
  console listens on every interface: from another machine open `http://<this machine's address>:17480`.

## Running everything in Docker (optional)

If you prefer containers, build the server first (Step 3), then:

```bash
export DRISHTI_TOKEN_SECRET=$(openssl rand -hex 32) DRISHTI_SESSION_SECRET=$(openssl rand -hex 32)
export DRISHTI_ADMIN_USER=admin DRISHTI_ADMIN_PASSWORD='choose-a-long-one-123'   # the first administrator; required
export DRISHTI_PACKS=market-risk,counterparty-risk   # or leave it out for the seven packs
docker compose -f deploy/docker/compose.yaml up -d --build
```

Sign-in is **on** in this setup and there is **no** development admin: sign in at http://localhost:17480 as the
`DRISHTI_ADMIN_USER` you chose. The containers serve the packs' samples (no lake is mounted; `deploy/docker/compose.data.yaml` adds data
stores). `docker compose -f deploy/docker/compose.yaml down` stops it. Production details are in [OPERATIONS.md](../admin/OPERATIONS.md).

## Build and run without the wrapper, or from an IDE

Everything above uses `./mvnw`. This section is for a machine that has its own Maven, and for working from IntelliJ IDEA and
PyCharm. Commands were run on Ubuntu with Maven 3.9.12 and OpenJDK 21 (any Java 21 or newer works).

### With Maven installed on the system

The poms enforce only the Java version (`[21,)`, the rule that prints `Drishti needs Java 21 or newer (any vendor; production runs
Java 21). Maven is running on Java …`); they set no minimum Maven version. The wrapper pins **Maven 3.9.12**
(`.mvn/wrapper/maven-wrapper.properties`), which is what the project is built and tested with; use 3.9.x. The enforcer rule checks
the JVM Maven itself runs on, so set `JAVA_HOME` to Java 21 or newer (as in step 1) before `mvn`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -v                                  # "Apache Maven 3.9.…" and "Java version: 21.…"

mvn -q package -DskipTests              # build (add -o to work offline once ~/.m2 is filled)
mvn -q verify                           # build, every test and every rule
mvn -q -pl drishti-server -am package -DskipTests    # the server and what it needs
ls drishti-server/target/*-exec.jar     # the application: drishti-server/target/drishti-server-2.9.2-exec.jar
```

Run the server from the repository root (it finds `./config/packs` and `./data` relative to where you start it). Java-25-only
note: `-XX:+UseCompactObjectHeaders` saves about 10% of the heap there, and Java 21 refuses to start with it, so leave it out on 21.

### IntelliJ IDEA and PyCharm

Running the server from IntelliJ IDEA and the console from PyCharm (the run configurations, JDK and interpreter setup, debugging,
reloading changes, tests in the IDE, common problems) has its own guide: **[IDE_GUIDE.md](IDE_GUIDE.md)**. The repository ships
ready-made run configurations: IntelliJ (opened on the repository root) reads `run-configs/`, PyCharm (opened on `drishti-console/`)
reads `drishti-console/run-configs/`: **Build
demo lakes**, **Drishti build server jar**, **Drishti server**, **Drishti server (demo lakes)**, **Drishti server (debug-friendly)**,
**Drishti console**, a QA profile on its own ports and data folder (**Drishti server (QA profile)**, **Drishti console (QA profile)**), and the tools (`drishti doctor`, `drishti pack make`, `drishti sutra gen`,
`drishti view get`, the QA lake build, …), so a tester needs only the two IDEs and no terminal.

Where next from a terminal: **[CLI_GUIDE.md](CLI_GUIDE.md)** is the one command line, `tools/drishti.py`, for turning your JSON
Lines into a pack, checking packs in CI, loading data and a pack into a running server, lakes, and the Screen Designer's propose and
approve steps.

## Supplying a different application config

### The server

Spring Boot reads `application.yaml` from inside the jar, then `./application.local.yaml` (the bundled file imports it with
`optional:`), and you can add more. Drishti's own `spring.config.import` entries are exactly two: that local file and the packs
overlay `<data>/packs/added.yaml` (`DRISHTI_PACKS_OVERLAY` moves it; the server writes it, do not edit it).

| You want | Do | Command |
|---|---|---|
| a site file read **in addition**, on top of the bundled defaults | `--spring.config.additional-location` | `java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.2-exec.jar --spring.config.additional-location=file:/etc/drishti/site.yaml` |
| the same, from the environment | `SPRING_CONFIG_ADDITIONAL_LOCATION` | `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/drishti/site.yaml java -jar …` |
| several files, later ones win | a comma list | `--spring.config.additional-location=file:/etc/drishti/site.yaml,file:/etc/drishti/secrets.yaml` |
| a folder of files | end it with `/` | `--spring.config.additional-location=file:/etc/drishti/conf.d/` |
| a profile's file | `--spring.profiles.active` (or `SPRING_PROFILES_ACTIVE`) | `java -jar … --spring.profiles.active=files` reads the jar's `application-files.yaml` |
| a profile's file of your own | name it `application-<profile>.yaml` beside your site file and add the location | `--spring.profiles.active=prod --spring.config.additional-location=file:/etc/drishti/` reads `/etc/drishti/application-prod.yaml` too |
| **replace** the defaults (rare) | `--spring.config.location`, and keep the bundled file in the list | `--spring.config.location=classpath:/application.yaml,file:/etc/drishti/site.yaml` |

**Do not** write `--spring.config.location=file:/etc/drishti/site.yaml` alone: it replaces the bundled `application.yaml`, so the
server starts with none of Drishti's defaults (tried: it came up on Spring's port 8080, not 18480). Prefer `additional-location`. A
key set in a file you add overrides the bundled one; environment variables and `--key=value` arguments override both
([CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault)). Do not set the same key in both `application.local.yaml`
and an additional file. The profiles the jar ships are `files`, `postgres`, `duckdb`, `mongodb`, `redis`, `aerospike` and
`iceberg`.

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

Connectors are not configured here: each is a YAML file of its own in `config/connectors/`
([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)).

### The console

The console has **no flag or variable that points at another config file**: it reads `config/application.yaml` beside
`run_drishti_web.py` (`drishti-console/config/`) and `drishti-console/config/application.local.yaml` if present
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

Later wins: the YAML, the local file, the environment, the arguments. To keep several configurations, keep several local files and
copy the one you want to `drishti-console/config/application.local.yaml`, or set the environment per shell.

### What the `${DRISHTI_PACKS:core,…}` in the files means

A value written `${NAME:default}` is "the variable `NAME` if it is set, else `default`". The meaning, the order that wins, and a
table of every such placeholder in both `application.yaml` files are in
[CONFIGURATION.md](../admin/CONFIGURATION.md#placeholders-namedefault).

## Drishti on Windows

Drishti runs on Windows 10 and 11 (and Windows Server 2019 or later) without WSL, Docker or Hadoop. This section covers the same
ground in PowerShell: installing Java (21 or newer, production runs 21) and Python, building the server or copying a built one,
building the demo lakes, starting the server and the console, what does not work on Windows, and what to do when something goes
wrong.

Why it works without Hadoop: the Delta Lake connector reads through its **native engine** (module `drishti-deltalake`), which uses
Java's own file access for local disk and the AWS SDK for S3. Hadoop's local file system, and with it `winutils.exe`, is never used.
See [DELTA_CONNECTOR.md › Engines](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop). The Iceberg connectors do the same
through **drishti-iceberg**, Drishti's own Iceberg engine ([ICEBERG_CONNECTOR.md › Engines](../connectors/ICEBERG_CONNECTOR.md#17-engines-native-and-library)),
the default engine.

> **Status.** The native engine is tested on Linux on every build, including its Windows path rules (drive letters, backslashes, UNC
> shares) as unit tests. The `windows` GitHub Actions job builds, tests and starts the server on `windows-latest`. Report what you
> find on your machine.

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

If PowerShell refuses to run the scripts (*running scripts is disabled on this system*), allow local scripts for your user once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

### 2. Get the server: build it, or copy it

**Build it** from a clone (the repository brings its own Maven, `mvnw.cmd`):

```powershell
git clone https://github.com/ajsinha/drishti.git
cd drishti
$env:JAVA_HOME = $env:DRISHTI_JAVA_HOME
.\mvnw.cmd -q package -DskipTests -pl drishti-server -am
dir drishti-server\target\*-exec.jar
```

You should see `drishti-server-2.9.2-exec.jar`. To also run the tests of the Delta engine and connector:
`.\mvnw.cmd -q verify -pl drishti-deltalake,plugins/drishti-plugin-delta -am` (tests that need Docker skip themselves); of the
Iceberg engine and connectors: `.\mvnw.cmd -q verify -pl drishti-iceberg,plugins/drishti-plugin-iceberg,plugins/drishti-plugin-iceberg-external -am`.

**Or copy** a server built elsewhere (on Linux, or by CI): Java bytecode is the same on every system. Make a folder, for example
`C:\drishti`, holding:

| Copy | From | Why |
|---|---|---|
| `drishti-server-2.9.2-exec.jar` | `drishti-server\target\` | the server (all its libraries are inside) |
| `config\packs\` | the repository root | the domain packs (Sutras, mnemonics, samples). Also `config\connectors\` (connector files) and `config\bi\` (BI datasets and reports) if you use them |
| `drishti-console\` | the repository root | only if you will run the console on this machine |
| `tools\` and `data\testdata\` | the repository | the scripts and `drishti.py`, and the test data if you will build the demo lakes here |
| `data\` | an existing installation, if any | the lakes (`data\delta`), users (`data\identity`), feeds, reports; start without it and it is created |
| `application.local.yaml` | your own, if any | site settings; read from the folder the server starts in |

Without a clone, put the scripts in `C:\drishti\tools\windows\`: they work from the folder two levels above themselves, and look
for the jar in `drishti-server\target\` and then in that folder.

### 3. Build the demo lakes

Make the console's Python first (step 5 below makes it on its first run, or by hand as there), then:

```powershell
drishti-console\.venv\Scripts\python.exe tools\drishti.py lake demo
```

It writes the same 84 tables under `data\delta` as on Linux (sixty business days ending today's business date). The older
`.\tools\windows\load-delta.ps1` still writes the packs' samples as ten days of history (`tools/packgen/banking/make_data.py
--lake`), without the multi-source trade tables; use `lake demo`.

### 4. Start the server

```powershell
.\tools\windows\start-server.ps1 -Packs market-risk
```

The script checks that Java is 21 or newer, sets `DRISHTI_DELTA_ENGINE=native`, and starts the jar with
`-XX:+UseCompactObjectHeaders` only on Java 25 or newer (Java 21, the production target, refuses to start with it). It runs in the
window until `Ctrl+C`. Set `$env:DRISHTI_DEMO_ENABLED='false'` first to read every page from the lakes, and the sign-in variables
of Step 6 A (`$env:DRISHTI_SECURITY_ENABLED='true'` and the rest) to sign in. Its options:

| Option | Default | Meaning |
|---|---|---|
| `-JavaHome` | `DRISHTI_JAVA_HOME`, then `JAVA_HOME`, then `java` on the PATH | the JDK folder (21 or newer) |
| `-Packs` | `DRISHTI_PACKS`, else the server's default (the seven default packs) | the packs to load, e.g. `market-risk,counterparty-risk` |
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
```

You should see `UP`. *Admin → Health* lists each lake connector (`trade-endur`, `market-store`, …) as `UP (engine: native)`.

To run the jar by hand instead:

```powershell
$env:DRISHTI_PACKS = 'market-risk'
& "$env:DRISHTI_JAVA_HOME\bin\java.exe" -Xmx2g -jar drishti-server\target\drishti-server-2.9.2-exec.jar
```

The native Delta engine is the default, so nothing else is needed.

### 5. Start the console

In a second PowerShell window:

```powershell
.\tools\windows\start-console.ps1
```

The first run makes the console's Python environment and installs its libraries (with uv if present, else `python -m venv` and
pip). Open **http://localhost:17480/t**. Options: `-Port` (17480), `-Backend` (`http://127.0.0.1:18480`), `-Python` (the Python to
build the environment with), `-Reinstall`. With sign-in on the server, set the console's variables of Step 7 first.

To start the console by hand instead (the same steps the script takes):

```powershell
uv venv --python 3.13 drishti-console\.venv
uv pip install --python drishti-console\.venv\Scripts\python.exe -r drishti-console\requirements.txt
drishti-console\.venv\Scripts\python.exe drishti-console\run_drishti_web.py
```

(without uv: `python -m venv drishti-console\.venv`, then `drishti-console\.venv\Scripts\pip install -r drishti-console\requirements.txt`).

> The settings are the console's own: `DRISHTI_CONSOLE_PORT`, `DRISHTI_BACKEND_URL`.

From here steps 8 to 12 above are the same.

### 6. The Delta engine on Windows

Every Delta connector has an `engine` setting; `DRISHTI_DELTA_ENGINE` sets it for those that do not set their own.

| Value | On Windows |
|---|---|
| `native` (the default) | works: local folders (`C:\lakes\drishti`, `\\fileserver\lakes`, `file:/C:/lakes`) and S3 |
| `auto` | the same as `native` on Windows (and `hadoop` on Linux) |
| `hadoop` | needs Hadoop's `winutils.exe` and `HADOOP_HOME`; use it only for a lake in Azure (`abfs://`) or Google Cloud Storage (`gs://`), which the native engine does not read |

A lake in S3 reads the same on Windows, with the connector's `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`
([CONFIGURATION.md](../admin/CONFIGURATION.md#delta--delta-lake)). Paths with spaces (`C:\Users\Jane Doe\drishti\data\delta`) are
fine.

**Iceberg tables on Windows.** The Iceberg loader and connector run on the native engine (drishti-iceberg), so no `winutils.exe` and
no `HADOOP_HOME` either. Load the banking samples (and, with `-Trades`, a trade book) into `data\iceberg`, then start the server with
the Iceberg connector files:

```powershell
.\tools\windows\load-iceberg.ps1                                    # the sample documents, ten business days
.\tools\windows\load-iceberg.ps1 -Trades 10000 -Days 3              # and a book of 10,000 trades a day
.\tools\windows\start-server.ps1 -Iceberg                           # copies config\connectors.examples\iceberg\*.yaml in, engine native
```

The loader's sort spills under `data\tmp\iceberg` (`-SpillDir` to move it), never the system temp folder. On Linux the same is
`tools/load-iceberg.sh ./data/iceberg --engine native` ([TOOLS.md](TOOLS.md)). `engine: library` (Apache Iceberg Java over Hadoop)
still needs `winutils.exe` on Windows; it is only for tables in Azure, Google Cloud Storage or HDFS.

### 7. What does not work on Windows

| What | Why | Instead |
|---|---|---|
| `engine: hadoop` on a local lake | Hadoop's local file system needs `winutils.exe` | `engine: native` (the default) |
| Replacing a DuckDB file while the server reads it | Windows cannot rename a file over one that is open ([DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md)) | load into a new file and switch the connector's `path`, or stop the server first |
| The bash scripts in `tools\*.sh` (`load-postgres.sh`, `drill.sh`, …) | they need bash | `tools\windows\*.ps1` for the server and the console, `tools\drishti.py` for lakes and packs; for the others, the Python tools they call run as they are (`python tools\…`), or use Git Bash or WSL |
| `docker compose` deployments (`deploy\`) | Linux containers | Docker Desktop runs them as on Linux; the jar runs natively as above |

The Aerospike, Cassandra, PostgreSQL, Kafka, RabbitMQ, ActiveMQ, Redis, MongoDB, S3 and REST connectors are plain network clients and work on
Windows; their servers are yours to run (`tools\windows\load-cassandra.ps1` loads the demo data into Cassandra).

### 8. Troubleshooting

| You see | Cause | Do this |
|---|---|---|
| `Drishti runs on Java 21 or newer (production runs 21; 25 also supported); … says: … version "17…` | the script found an older Java | pass `-JavaHome` or set `DRISHTI_JAVA_HOME` to a JDK 21 or newer folder |
| `running scripts is disabled on this system` | PowerShell's execution policy | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| `HADOOP_HOME and hadoop.home.dir are unset`, `Could not locate executable winutils.exe` | a Delta connector on `engine: hadoop` | remove `engine: hadoop` and unset `DRISHTI_DELTA_ENGINE` (or set it to `native`) |
| Health: `DOWN: cannot reach C:\…\data\delta\trading (engine: native)` | no lake there | run `tools\drishti.py lake demo`, or start with `-LakeRoot` pointing at the lake |
| Health: `DOWN: no Delta tables under …` | the folder exists but has no `<kind>\_delta_log` | check the root and the connector's `domain` folder (`trading`, `market`, …) |
| `the native Delta engine reads local disk and S3 …, not abfs://…` | an Azure or GCS lake with the native engine | set the connector's `engine: hadoop` (and install `winutils.exe`) |
| `Port 18480 was already in use` | another server | `-Port 18481`, and the console with `-Backend http://127.0.0.1:18481` |
| The console says `DRS-5003 backend unreachable` | the server is not up yet, or on another port | wait for `Started DrishtiApplication`; check `-Backend` |
| `lake demo`: `No module named 'deltalake'` | the console's Python is not set up | make it as in step 5 (it installs deltalake, pyarrow and PyYAML) |
| Windows Defender slows the first start | it scans the jar's native libraries (RocksDB, DuckDB, SQLite, Snappy) as they unpack | let the first start finish; later starts are faster |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## If something goes wrong

| You see | Do this |
|---|---|
| `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …` | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and build again |
| `The virtual environment was not created successfully because ensurepip is not available` | use uv (Step 4), or `sudo apt install python3.X-venv` for your Python |
| `lake demo`: `No module named 'deltalake'` (or `yaml`) | run it with the console's Python, `drishti-console/.venv/bin/python`, set up in Step 4 |
| `Port 18480 was already in use` | another server is running; stop it, or start this one with `DRISHTI_PORT=18481` and the console with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481` (and `lake demo --server http://localhost:18481`) |
| The console says the backend is unreachable (`DRS-5003`) | start the server first; check `curl -s localhost:18480/actuator/health` |
| Pages are empty and the console's log says `DRS-5010 missing bearer token` | the server has sign-in on and the console does not: start the console with the variables of Step 7 |
| A command gives `DRS-4001` | the mnemonic is unknown: check its pack is loaded (`/api/v1/packs`) and chosen in the pack switcher |
| A view says `DRS-1001 no source holds …` | the id does not exist; type part of it and pick from the suggestions |
| A picked date shows today's data, or BI shows nothing for a date | build the demo lakes (Step 5); the server reads them without a restart |
| BI shows figures from an earlier lake | it cannot after 2.3.0 (the server re-reads a rebuilt Delta date); if it does, *Admin → Caches* → purge, and report it |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Where to go next

| If you want to… | Read |
|---|---|
| Learn by doing | [the tutorials](../tutorials/README.md) |
| Learn every feature of the console | [USER_GUIDE.md](USER_GUIDE.md) |
| Build reports, models and boards in BI Studio | [BI_GUIDE.md](BI_GUIDE.md), [tutorial 10](../tutorials/10-first-bi-report.md) |
| Run several servers as one cluster (Drishti\*net) | [NET.md](../admin/NET.md), [tutorial 19](../tutorials/19-a-cluster-of-three.md) |
| Know which packs exist and what commands they offer | [PACKS.md](PACKS.md) |
| Change how a screen looks | [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md), [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md) and [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) |
| Add users and roles | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Connect your own data | [CONNECTORS.md](../connectors/CONNECTORS.md) and [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) |
| Build a Delta lake from your own JSON export | [TOOLS.md](TOOLS.md) (`drishti.py lake build`) |
| Build Drishti from source, run the tests | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md), [IDE_GUIDE.md](IDE_GUIDE.md) |
| See every document | [the documentation map](../README.md) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*
