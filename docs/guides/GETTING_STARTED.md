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
# Getting started

This guide takes you from nothing to a working Drishti on your own computer. You need no prior
knowledge of Drishti. Each step gives the exact text to type and what you should see.

You will:

1. install three tools;
2. download and build Drishti;
3. start the **server** (the part that reads data) and the **console** (the web pages you use);
4. open your first views, search, pick a past date, and set an alert;
5. optionally turn on sign-in and change the development admin's password.

The whole thing takes about 20 minutes, most of it waiting for the first build.

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
`winutils.exe`): follow [WINDOWS.md](WINDOWS.md), which covers the same steps in PowerShell.

| Tool | Version | Why | Check with |
|---|---|---|---|
| OpenJDK | **25** exactly | builds and runs the server | `java -version` |
| Python | 3.11 or newer | runs the console | `python3 --version` |
| uv | any recent | creates the console's Python environment and builds sample history | `uv --version` |
| git | any | downloads the code | `git --version` |
| Docker | optional | only to run everything in containers, or the PostgreSQL/Aerospike/Kafka extras | `docker --version` |

On Ubuntu or Debian:

```bash
sudo apt install openjdk-25-jdk python3 python3-venv git curl
curl -LsSf https://astral.sh/uv/install.sh | sh      # installs uv into ~/.local/bin
```

Drishti builds and runs on Java 25 (the current long-term release) and refuses any other. Point `JAVA_HOME` at
it in every terminal you use for Drishti. On Ubuntu the path is:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

You should see a line that starts with `openjdk version "21.`.

> **Tip:** add the two `export` lines to your `~/.bashrc` so new terminals get them too.

You do **not** need to install Maven. The repository has its own copy (`./mvnw`).

## Step 2 · Download the code

```bash
git clone https://github.com/ajsinha/drishti.git
cd drishti
```

Every command in the rest of this guide is run **from this `drishti` folder** (the repository root).
The server finds the packs (`./packs`) and its data (`./data`) relative to where you start it.

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

You should see `drishti-server/target/drishti-server-1.13.0-exec.jar`.

> **Note:** `./mvnw -q verify` builds **and** runs every test (several minutes more). Use it when you
> change code; for a first try, `package -DskipTests` is enough.

If the build stops with `Drishti builds and runs on OpenJDK 25.`, your `JAVA_HOME` is not Java 25. Go
back to Step 1.

## Step 4 · Set up the console

Create a private Python environment for the console and install its libraries into it:

```bash
uv venv console/.venv
uv pip install --python console/.venv/bin/python -r console/requirements.txt
```

You should see uv report the packages it installed (FastAPI, Uvicorn, Jinja2 and a few more).

Without uv, plain Python works too:

```bash
python3 -m venv console/.venv
console/.venv/bin/pip install -r console/requirements.txt
```

## Step 5 · (Recommended) Build the sample history

The packs come with sample documents for **today** (the server serves them live, so they tick). To
look at **past business dates** and compare dates, you also need a small history: a local Delta Lake
under `data/delta/`. It is not in git because it is generated. Build it once:

```bash
# banking packs: banking-core, market-data, trading, market-risk, counterparty-risk
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta

# the other generated packs (run the ones you want)
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/liquidity/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/climate/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/oprisk/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/retail/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/genomics/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/politics/make.py --lake data/delta
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/economics/make.py --lake data/delta
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
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
export DRISHTI_STUDIO_SAVE=true
java -jar drishti-server/target/drishti-server-1.13.0-exec.jar
```

What the settings do:

| Setting | Meaning |
|---|---|
| `DRISHTI_PACKS` | The packs to load, separated by commas. A pack's parents load with it: `market-risk` brings `banking-core`, `market-data` and `trading`. Without this setting only the small `finance` pack loads. |
| `DRISHTI_STUDIO_SAVE=true` | Lets Sutra Studio save layouts. Leave it out if you will not edit layouts. |

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

You should see the twelve names, starting with `banking-core`, `market-data`, `trading`.

> **The smallest start.** `java -jar drishti-server/target/drishti-server-1.13.0-exec.jar` with no
> settings loads only the `finance` pack: the four original mockups (`TRD IRS-48213`, `TRD FXS-20931`,
> `TRD CFT-77120`, `NSET NS-NORTH-01`). Everything in this guide works the same way with those ids.
> Do not mix `finance` with the banking packs: both define `TRD` and `CRV`.

## Step 7 · Start the console

Open another terminal in the `drishti` folder:

```bash
console/.venv/bin/python console/run_drishti_web.py
```

You should see:

```text
INFO:     Uvicorn running on http://127.0.0.1:17480 (Press CTRL+C to quit)
```

Now open **http://localhost:17480** in your browser. You see the landing page with an animated eye.
Click **Open the terminal**, or go straight to **http://localhost:17480/t**.

> **Signed in already?** Out of the box, sign-in is **off** so you can explore at once: you act as the
> development user *Ash* with every role (the top bar shows `Rates desk · Ash`). Step 13 turns sign-in on.

## Step 8 · Your first view

On the terminal page (`/t`), the command line is at the top. Below it is a list of **Examples**, one
or more per pack.

1. Click in the command line (or press `/`).
2. Type `TRD MX-20000001` and press **Enter**.

You should see a full view of an interest rate swap:

- the **title line**: `Rates · Interest rate swap (fixed/float)`, the id `MX-20000001`, and `with Meridian Reinsurance Ltd`;
- the **strip** of key figures: Notional `AUD 242,000,000`, Direction `Receive fixed`, Trade date, Maturity,
  **MTM (USD)** (highlighted), DV01, Status `Live`, Book `BOOK-RATES-3`;
- **panels**: *Terms* (F2), *Legs* (one tab per leg), *Cashflows* (F3), *How this view was built*, a curve
  chart (F4), *DV01 by bucket*, *Daily P&L*, and *Linked entities*;
- the **function keys** along the bottom: `F2 Terms`, `F3 Cashflows`, `F4 Interest rate`, `F7 Netting set`,
  `F8 Impact`, `F9 Raw JSON`, `Alt+← Back`.

Watch the **MTM** figure for a few seconds: it changes and flashes. That is a live update. The top bar
shows a green **Live** dot.

Try these next:

| Type | You should see |
|---|---|
| `F9` | The raw JSON document on the right, with its source (`murex-rates`) and generation |
| `F7` | The netting set `NS-MERIDIAN-RE-NY` this trade belongs to |
| `Alt+←` | Back to the trade |
| `F8` | *Impact of MX-20000001*: the netting set that depends on it, and the credit limit it rolls into |
| click `BOOK-RATES-3` in the strip | The book: its top trades, MTM and DV01 |

## Step 9 · Let the suggestions help you

You rarely need to remember an identifier. Type slowly and watch the dropdown:

1. Clear the command line and type `T`. You should see mnemonics such as `TRD` (Trade) and `TRDR` (Trader).
2. Type `TRD MX-2000010`. You should see trades whose ids start that way: `MX-20000100`, `MX-20000101`, …
3. Press `↓` to highlight one, `Tab` to complete it, and `Enter` to open it.

A bare identifier works too when its shape tells Drishti the kind: type `MX-20000001` and press Enter.

More to try, one from each kind of pack:

```text
CRV CRV-USD-OIS         SOFR discount curve: zero rates, pillars, forwards
NSET NS-SUMMIT-NY       netting set: exposure profile, CSA, member trades
LCR LCR-NY              liquidity coverage ratio
CUST CUST-100231        retail customer
GENE GENE-TP53          the TP53 gene
ECON ECON-US            the US economy
```

If you mistype a mnemonic, the terminal says `DRS-4001 cannot read command …`. If the id does not
exist, the view says `DRS-1001 no source holds trade/…`.

## Step 10 · Search by value

Put `where` after a mnemonic to find things by what they contain. Type:

```text
TRD where mtm > 1m order by mtm desc limit 20
```

You should see a results page: a line such as `163 of 750 trades match; the first 20 are shown`, and a
table of trades with an **MTM (USD)** column, largest first. Click any id to open it.

More examples:

```text
TRD where notional >= 250m and assetClass = 'Rates' order by mtm desc limit 20
TRD where counterparty.name contains 'Meridian'
NSET where utilisation > 0.5 order by utilisation desc
CUST where totalDeposits > 100k order by totalDeposits desc
```

The full rules are in the [user guide](USER_GUIDE.md#search-by-value).

## Step 11 · Look at a past business date

The **Business date** box in the top bar says which day you are looking at. **Live** (green) means
today, streaming.

1. Open `TRD MX-20000001`.
2. Click the date box and pick **29 September 2026** (any weekday in the last two weeks of September 2026
   works with the sample history from Step 5).
3. The box turns amber, the **Live** dot goes out, and the footer says `as of 2026-09-29`. The MTM is that
   day's value and no longer ticks.
4. Click **Compare** in the title line. You should see *What changed in MX-20000001*: every field that differs
   from the business day before, such as **MTM (USD)** and the **DV01** of each tenor, with the change.
5. Click **Live** in the top bar to return to today.

If you skipped Step 5, the view instead says *No data held for* the date (it shows the current data, which does not
update), and there is no Compare.

## Step 12 · Set an alert and watch a list

**An alert** tells you when a figure crosses a line.

1. Open `NSET NS-SUMMIT-NY`. Its *utilisation* is about 58 % of its limit.
2. Click **Alert** in the title line. The *Alerts* page opens with **Kind** `netting-set` and **Id**
   `NS-SUMMIT-NY` already filled in.
3. Fill in **Name** `Summit utilisation`, **When** `$.utilisation > 0.5`, **Severity** `warn`, and
   **Message** `${$.nettingSetId}: ${fmt($.utilisation, 'pct0')} of limit`.
4. Click **Save rule**.

Because the condition is already true, the alert fires at once: a toast appears, the bell in the top
bar shows `1`, and *Recent alerts* lists `NS-SUMMIT-NY: 58% of limit`.

**A monitor** is a live watchlist. The quickest way to make one is from a search:

1. Type `NSET where utilisation > 0.7 order by utilisation desc` and press Enter.
2. Above the results, change the name to `High utilisation` and click **Watch as a monitor**.
3. You should see the monitor page: one row per netting set, each with its key figures.
4. Type `NSET NS-SUMMIT-NY` in **Add an entity** to add another row; `×` removes one.

The monitor is saved to your account. **Monitors** in the top bar (`/m`) lists it.

## Step 13 · (Optional) Turn on sign-in and change the admin password

With sign-in off, anyone who can reach the console acts as the development user. To try real
sign-in on your machine, stop both programs (`Ctrl+C` in each terminal) and restart them with
security on. Server and console must share the same **token secret**.

```bash
# in both terminals, the same value (at least 32 characters)
export DRISHTI_TOKEN_SECRET=change-me-to-a-long-random-string-0123456789
```

Server terminal:

```bash
export DRISHTI_SECURITY_ENABLED=true
java -jar drishti-server/target/drishti-server-1.13.0-exec.jar
```

Console terminal:

```bash
export DRISHTI_AUTH_ENABLED=true
export DRISHTI_SESSION_SECRET=another-long-random-string-0123456789abcd
export DRISHTI_SECURE_COOKIE=false        # only for plain http on your own machine
console/.venv/bin/python console/run_drishti_web.py
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
*Audit log*, *Health* and *Caches*. Users, roles and packs per user are explained in
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

> **Production:** set `DRISHTI_SEED_ADMIN=false` so no development admin is created, and use long random
> secrets. See [OPERATIONS.md](../admin/OPERATIONS.md).

## Step 14 · Switch packs, themes and settings

- **Packs.** The box icon in the top bar shows how many packs you see (for example `12 packs`). Click it,
  untick the ones you do not need, and click **Apply**. The examples, suggestions and help follow your
  choice. You can only choose among packs the server loaded and an admin assigned to you.
- **Themes.** The palette icon offers seven themes: Terminal, Parchment, Blue, Wall Street, Green, Crimson
  and Crimson dark.
- **Settings.** *My account → Settings* (`/account`) keeps your theme, the page to open after signing in
  (for example `/v/trade/MX-20000001`), the clock's time zone, density, whether changes flash, and how many
  search results to show.

## Step 15 · Find help

- **Help** in the top bar (or `F1` on any page) opens the help centre at `/help`, with tutorials, every
  reference document and a search box. `F1` opens the help for the page you are on.
- The **?** in each panel's header explains that kind of panel.
- **About** (`/about`, linked in the footer) shows the version, the loaded Sutras and the health of each source.
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
`drishti-dev-admin123` and change the password (Step 13). `docker compose -f deploy/compose.yaml down`
stops it. Production details are in [OPERATIONS.md](../admin/OPERATIONS.md).

## If something goes wrong

| You see | Do this |
|---|---|
| `Drishti builds and runs on OpenJDK 25.` | `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` and build again |
| `Port 18480 was already in use` | another server is running; stop it, or start this one with `DRISHTI_PORT=18481` and the console with `DRISHTI_BACKEND_URL=http://127.0.0.1:18481` |
| The console says the backend is unreachable (`DRS-5003`) | start the server first; check `curl -s localhost:18480/actuator/health` |
| A command gives `DRS-4001` | the mnemonic is unknown: check its pack is loaded (`/api/v1/packs`) and chosen in the pack switcher |
| A view says `DRS-1001 no source holds …` | the id does not exist; type part of it and pick from the suggestions |
| A picked date shows today's data | build the sample history (Step 5) and restart the server |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Where to go next

| If you want to… | Read |
|---|---|
| Learn every feature of the console, with examples | [USER_GUIDE.md](USER_GUIDE.md) |
| Know which packs exist and what commands they offer | [PACKS.md](PACKS.md) |
| Change how a screen looks | the in-app *Sutra guide* and *Sutra Studio* tutorial, and [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) |
| Add users and roles | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Connect your own data | [PLUGIN_GUIDE.md](../connectors/PLUGIN_GUIDE.md) and [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| See every document | [the documentation map](../README.md) |
