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
# Tutorial 1 · Your first 15 minutes

Install Drishti, start it, sign in, open a trade, search, try the search form and look at business intelligence. At the end you will also have a
token that lets the command line talk to your server, which tutorials 4, 5, 6 and 7 use.

## Time to complete

About 15 minutes, plus the first build (a few minutes, once).

## Before you start

You need a Linux, macOS or Windows machine with Java 21 or newer, Python 3.11 or newer, [uv](https://docs.astral.sh/uv/) and git. Nothing else: no database, no
Docker. On Windows follow [WINDOWS.md](../guides/WINDOWS.md) for the same steps in PowerShell.

In these tutorials `<GO>` means "press Enter", commands are run from the repository root (the folder called `drishti`), and the server and the console are the two
programs from [GETTING_STARTED.md](../guides/GETTING_STARTED.md): the **server** reads the data (port 18480), the **console** is the web pages you open (port 17480).

## What you will learn

- how to build and start Drishti with sign-in on (the way every tutorial here runs);
- how to sign in, and what the home page and the top bar offer;
- how to open an entity by typing a command (`TRD MX-20000001`), and what a view is made of;
- how to find entities by what they contain, and by filling in a form;
- where business intelligence (BI) lives;
- how to make a token so the command line can use your server.

## Step 1 · Install, build and check the machine

```bash
git clone https://github.com/ajsinha/drishti.git
cd drishti
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64     # your Java 21 or newer
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q package -DskipTests
uv venv drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
```

Then build the sample history (ten business days to 30 September 2026), so that past dates and **Compare** work in tutorial 2:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta
```

Now ask the machine whether it is ready. `doctor` prints green, yellow and red lines, each red or yellow with a fix:

```text
$ python3 tools/drishti.py doctor --server http://localhost:18940
GREEN  java                    /usr/lib/jvm/java-21-openjdk-amd64/bin/java: Java 21.0 (Ubuntu)
GREEN  server jar              ~/drishti/drishti-server/target/drishti-server-1.22.0-exec.jar (version 1.22.0)
GREEN  python                  /usr/bin/python3: Python 3.14.4
GREEN  python pyyaml           installed
YELLOW python deltalake        not installed (needed for data ingest --store delta)
                               fix: pip install deltalake   or run through uv: uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...
YELLOW python pyarrow          not installed (needed for data ingest --store delta)
                               fix: pip install pyarrow   or run through uv: uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...
GREEN  repo config/packs/      ~/drishti/config/packs
GREEN  console venv            ~/drishti/drishti-console/.venv
GREEN  configured directories  none of DRISHTI_DELTA_ROOT, DRISHTI_FILES_ROOT, DRISHTI_PACKS_INSTALLED is set
GREEN  port 18940              in use by Drishti: status UP
GREEN  port 17940              in use by Drishti: answers 404 on /actuator/health, as the Drishti console does
YELLOW server                  http://localhost:18940: version 1.22.0, java 21.0.12.1, status DEGRADED
GREEN  token                   accepted; this token 'oF6pcM68sQUO' acts as drishti-dev-admin: scopes loads:write, design:write, packs:admin, bi:write, expires 2026-11-11T02:14:19.018Z
doctor: 0 red, 3 yellow
```

You should see no red line (the exit is 0). Yellow lines about `deltalake` and `pyarrow` only matter for `data ingest`, which you run through `uv run --with ...`. The two
`port` lines say what is on the ports: with nothing started they say *free*; in the output above the server and the console were already running.

> The ports in the output are 18940 and 17940 because the author's stack ran beside another one. Yours are 18480 and 17480, the defaults.

## Step 2 · Start the server and the console with sign-in on

Open two terminals in the repository root. The server, in the first (the packs are the nine the tutorials use):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
export DRISHTI_STUDIO_SAVE=true
export DRISHTI_SECURITY_ENABLED=true
export DRISHTI_TOKEN_SECRET=change-me-to-a-long-random-string-0123456789
java -jar drishti-server/target/drishti-server-1.22.0-exec.jar
```

The console, in the second (the same token secret, so the two trust each other):

```bash
export DRISHTI_TOKEN_SECRET=change-me-to-a-long-random-string-0123456789
export DRISHTI_AUTH_ENABLED=true
export DRISHTI_SESSION_SECRET=another-long-random-string-0123456789abcd
export DRISHTI_SECURE_COOKIE=false        # plain http on your own machine only
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

You should see `Started DrishtiApplication` in the server's terminal and `Uvicorn running on http://0.0.0.0:17480` in the console's. Check the server from a third terminal:

```bash
curl -s localhost:18480/actuator/health
```

You should see `{"status":"UP","groups":["liveness","readiness"]}`.

(`DRISHTI_STUDIO_SAVE=true` lets the Build pages of tutorials 5 and 6 save work. Sign-in is optional for tutorials 1 to 3 and 5 to 7 but tutorials 8 and 9 need two people, so
the tutorials all run with it on.)

## Step 3 · Sign in

Open <http://localhost:17480>. You are sent to the sign-in page. On the very first start the server makes a development administrator for you: the user
`drishti-dev-admin`, password `drishti-dev-admin123`. Type them in.

![The sign-in page with the user name and password filled in](img/t01/01-sign-in.jpg)

You should see the page above, then, after **Sign in**, the terminal's home page with your name at the top right. (The page tells you to change the development password: do that
under **My account** before you ever put real data in; tutorials here leave it so that the screenshots match.)

## Step 4 · Find your way around the home page

![The home page: the command line, the date box, the menus and a list of commands to try](img/t01/02-home.jpg)

You should see, from left to right in the top bar: the **command line** (the rounded box, `> TRD MX-20000001, CPTY ...` is its hint), the **business date** box, the menu icons
(Views, BI, Forms, Reports, Build, Admin, Help), the bell for your inbox, the packs icon with a count, the theme button, the green round **Live** button and your name.
Below it is a list of commands you can click.

The command line is the heart of Drishti. A command is a **mnemonic** (a short code for a kind of thing: `TRD` is a trade) and an **identifier**:

```text
TRD MX-20000001 <GO>
```

Press `/` anywhere to jump to the command line. Type `TRD MX-200000` and stop: do not press Enter yet.

![The command line suggesting trades as you type](img/t01/03-typeahead.jpg)

You should see a dropdown of trades whose id starts with what you typed, each with its mnemonic. Use the arrow keys to move, `Tab` to copy a suggestion into the box and `Enter` to open it.

## Step 5 · Open a trade

Clear the box, type `TRD MX-20000001` and press Enter.

![The view of an interest rate swap: title, strip of key figures, panels and function keys](img/t01/04-trade.jpg)

You should see the **view** of an interest rate swap: the title line (`MX-20000001 with Meridian Reinsurance Ltd`), the **strip** of key figures under it (notional, direction, dates,
MTM, DV01), then panels: the terms, the legs (with a tab for each), the curve the swap is priced from, and DV01 by bucket. The MTM tints and changes a little as new data arrives:
the view is live. The function keys at the bottom (`F2 Terms`, `F3 Cashflows`, ...) jump to a panel; click a blue name (the counterparty, the book) to open that entity; click
a number to see it over time (tutorial 2).

On a phone the same view is one column. This is the same trade at 390 px:

![The same trade on a phone: one column](img/t01/05-trade-phone.jpg)

You should see the title, the strip as a grid and the panels stacked, with the function keys as a scrolling row at the bottom.

The command line has a twin, `view get`, which prints the view as text. Before using it, make the token of Step 8; for now just look at what it prints:

```text
$ python3 tools/drishti.py view get trade/MX-20000001
Rates · Interest rate swap (fixed/float)  MX-20000001  with Meridian Reinsurance Ltd   [TRD]

Key figures
  figure      value
  ----------  ---------------
  Notional    AUD 242,000,000
  Direction   Receive fixed
  Trade date  2025-07-25
  Maturity    2032-06-25
  MTM (USD)   +1,892,961
  DV01 (USD)  −155,245
  Status      Live
  Book        BOOK-RATES-3

Terms  (kv, terms, F2)
  field          value
  -------------  ------------------------
  Fixed rate     4.0829%
  Pay frequency  Annual
  Day count      ACT/365F
  Business day   Following
  Effective      2025-07-28
  Counterparty   Meridian Reinsurance Ltd

Legs  (tabs, legs)
  {"layout": "tabs", "tabs": [{"title": "Leg 1 · Receive fixed 4.0829%", "fields": [], "panels": [{"id": "leg-terms", "kind": "kv", "title": "Leg terms", "area": "main", "inferred": false, "data": {"fields": [{"label": "Pay/receive", "text": "Receive"}, {"label": "Rate type", "text": "Fixed"}, {"label

Cashflows  (ladder, schedule, F3)
  Pay date    Leg  Type      Rate      Amount          PV
  ----------  ---  -----  -------  ----------  ----------
  2025-09-29  2    Float  3.5377%  −1,403,091           0
  2025-12-31  2    Float  3.6186%  −2,310,878           0
  2026-03-27  2    Float  3.6102%  −2,087,097           0
...
```

## Step 6 · Search by what is inside

Press `/` and type:

```text
TRD where mtm > 1m order by mtm desc limit 50
```

Press Enter.

![Search by value: the search, the count, and a table of trades with MTM above one million](img/t01/06-search.jpg)

You should see a page headed **Search by value** with the search in a box, a line such as `161 of 750 trades match; the first 50 are shown` and how long it took, then a table with one row
per trade, biggest MTM first. (Your count may differ: the sample data is generated.) Click a trade id to open it, sort by a heading, or type in **Filter rows**. **CSV** downloads the table;
**Watch as a monitor** keeps the first 50 rows as a live list. Amounts take `k`, `m` and `bn`; `and`, `or`, `not`, `contains` and `startswith` work too.

## Step 7 · Fill in a form instead

If you would rather click than write, open **Forms** (the third icon in the top bar), then **All forms**.

![The forms home: every pack that has a search form, with its kinds](img/t01/07-forms-home.jpg)

You should see one card for each pack that offers a form. Pick **Trading**, kind **Trade**:

![The trading pack's form for trades: boxes for the trade id, counterparty, product, amounts and dates](img/t01/08-form-trading.jpg)

You should see a form with a box, a list or a set of tick boxes for each field people search by, grouped as Identity, Product, Where, Money and Dates. Tutorial 3 uses it.

## Step 8 · Look at business intelligence

Click the **BI** icon (or type `BI` and press Enter).

![The BI home: a menu on the left (Home, Catalogue, My workspace and more) and the sections Recent, Favourites, Certified for you and Shared with me](img/t01/09-bi-home.jpg)

You should see the BI home: a menu on the left (Home, Catalogue, My workspace, Datasets, Insights and so on) and, in the middle, the sections *Recent*, *Favourites*, *Certified for you* and *Shared with me*, which fill as you use BI. Business intelligence is Drishti's second front: reports and boards built on datasets, with their own guide ([BI_GUIDE.md](../guides/BI_GUIDE.md)) and tutorial
([BI_TUTORIAL.md](../guides/BI_TUTORIAL.md)). Later tutorials come back to it for reports and alerts.

## Step 9 · Make a token for the command line

The command line (`python3 tools/drishti.py ...`) talks to your server over REST, and a server with sign-in on wants a token. Open **My account** (click your name), scroll to **API tokens**,
name a token `tutorials`, set **Days** to 30 and tick the four write scopes the tutorials use: `design:write`, `loads:write`, `packs:admin` and `bi:write`. (A token with a write scope must
expire, and it can never do more than you can.)

![The API tokens form: a name, days, and the scopes ticked](img/t01/10-api-tokens.jpg)

Press **Create token**.

![The secret, shown once, with a Copy button](img/t01/11-api-token-secret.jpg)

You should see the secret (it starts `drk_`). It is shown **once**: copy it now and keep it in a file only you can read, then point the command line at it:

```bash
mkdir -p ~/.drishti && cat > ~/.drishti/tutorial.token     # paste the secret, press Enter, then Ctrl+D
chmod 600 ~/.drishti/tutorial.token
export DRISHTI_SERVER=http://localhost:18480
export DRISHTI_TOKEN=$(cat ~/.drishti/tutorial.token)
```

Without a token a signed-in server says no, in plain words:

```text
$ python3 tools/drishti.py server health        # no token
drishti: the server said 401 DRS-5010: DRS-5010 missing bearer token (no or bad token: set DRISHTI_TOKEN or --token-file)
[exit 1]
```

With it, the server answers. `server health` lists every source (a connector to a store) and says whether it is up:

```text
$ python3 tools/drishti.py server health
status DEGRADED  version 1.22.0  java 21.0.12.1  uptime 40s
packs 12  sources 20 (degraded 0, down 8)  packs with problems 7  failed to start 0
  civic-store                 DOWN      DOWN: cannot reach ~/drishti/data/delta/civic (engine: native)
  climate-store               DOWN      DOWN: cannot reach ~/drishti/data/delta/climate (engine: native)
  finance-lake                DOWN      DOWN: cannot reach ~/drishti/data/delta/finance (engine: native)
  genomics-store              DOWN      DOWN: cannot reach ~/drishti/data/delta/genomics (engine: native)
  liquidity-store             DOWN      DOWN: cannot reach ~/drishti/data/delta/liquidity (engine: native)
...
```

You should see `status DEGRADED` on a machine that built only the banking history: the sources for genomics, climate and the other packs say `DOWN` because no lake was built for them.
That is expected here and does not affect the tutorials. Tutorial 4 shows how a connector reports its state.

## Next steps

- [Tutorial 2 · Look up anything](02-look-up-anything.md): mnemonics, type-ahead, links, a past date, Compare.
- [Tutorial 3 · Query by form](03-query-by-form.md): the form in depth, and keeping a search.
- The [User guide](../guides/USER_GUIDE.md) describes every feature of the console; [CLI_GUIDE.md](../guides/CLI_GUIDE.md) every command.

## If something goes wrong

| You see | Do this |
|---|---|
| The build says `Drishti needs Java 21 or newer` | `JAVA_HOME` is older than 21; point it at 21 or newer and run the build again |
| The sign-in page says the password is wrong | the development password is `drishti-dev-admin123` until you change it; five wrong tries lock the account for 15 minutes |
| The page loads but views say the server is unreachable | the server is not running, or the two programs have different `DRISHTI_TOKEN_SECRET` values |
| `address already in use` | something else holds 18480 or 17480; set `DRISHTI_PORT` (server) or `DRISHTI_CONSOLE_PORT` (console), and tell the console where the server is with `DRISHTI_BACKEND_URL` |
