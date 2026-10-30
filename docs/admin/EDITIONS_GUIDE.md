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
# Editions guide: run Drishti as Drishti Risk, Drishti World or Drishti Market

Yes: by editing configuration you run a Drishti instance as **Drishti Risk**, **Drishti World** or **Drishti Market**. One setting,
`drishti.edition: risk` (or `world`, or `market`), on the server (every server of a cluster), then a restart. The console learns it from the server. This guide shows how, for a single server, the 3-node stack,
Docker and AWS ECS; how to switch back; what the edition includes and hides; how to adjust it without forking; how to make your own; and
how to troubleshoot. The design is [EDITIONS.md](../architecture/EDITIONS.md).

## Contents

1. [Run an instance as Drishti Risk](#1-run-an-instance-as-drishti-risk)
2. [Switch back to the full Drishti](#2-switch-back-to-the-full-drishti)
3. [What the risk edition includes and hides](#3-what-the-risk-edition-includes-and-hides)
4. [Choose the Home landing](#4-choose-the-home-landing)
5. [The lens: Drishti Risk (or Drishti World) for one person on a full instance](#5-the-lens-drishti-risk-or-drishti-world-for-one-person-on-a-full-instance)
6. [Customise it without forking, and white-label it](#6-customise-it-without-forking-and-white-label-it)
7. [Make your own edition](#7-make-your-own-edition)
8. [In a cluster](#8-in-a-cluster)
9. [Troubleshooting](#9-troubleshooting)
10. [Drishti World](#10-drishti-world)
11. [Drishti Market](#11-drishti-market)

## 1. Run an instance as Drishti Risk

The setting is `drishti.edition` (environment variable `DRISHTI_EDITION`). It names `config/editions/risk.yaml` in the folder
`drishti.editions.dir` (`DRISHTI_EDITIONS_DIR`, default `./config/editions` relative to the server's working folder). Set it **on the
server only**: the console asks the server (`/public/about`, the same answer as `/api/v1/about`'s `edition`) and follows. A change needs a
restart of the server; the console follows within its check interval (`backend.check_seconds`, 5 s) or at its next start.

**A single server.** Either in the server's `application.local.yaml` (in its working folder, git-ignored):

```yaml
drishti:
  edition: risk
```

or in the environment, from the repository root:

```bash
DRISHTI_EDITION=risk java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar
```

Leave `DRISHTI_PACKS` unset: the edition's packs load. If `DRISHTI_PACKS` is set it still wins (site configuration beats the edition), but
with `allow-only` the packs outside the edition are not loaded, and the log says which.

**The 3-node stack (`drishti-stack/`).** Each node's settings are in `drishti-stack/stack.yaml`. Add the variable to the `env:` of every node,
then restart the stack:

```yaml
nodes:
  - name: drishti-main
    env: { DRISHTI_COMPUTE_ENABLED: "true", DRISHTI_EDITION: risk }
  - name: drishti-b
    env: { DRISHTI_EDITION: risk }
  - name: drishti-c
    env: { DRISHTI_EDITION: risk }
```

```bash
drishti-stack/stop.sh && drishti-stack/start.sh
```

drishti-main's `packs:` names ten packs: with Drishti Risk the five of the edition load and the log names the others it skipped. The
console section needs nothing: it learns the edition from drishti-main.

**Docker Compose.** `deploy/docker/compose.yaml` passes `DRISHTI_EDITION` to the server:

```bash
DRISHTI_EDITION=risk docker compose -f deploy/docker/compose.yaml up -d
```

The images carry `config/editions/` (server and console).

**AWS ECS.** Set `DRISHTI_EDITION=risk` in your environment file (the one `deploy/aws/env.example` documents), then register the task
definitions again and roll the services (`deploy/aws/ecs/register.sh --nodes`, as in [AWS_DEPLOYMENT.md](../architecture/AWS_DEPLOYMENT.md)).
Every node's task definition gets the same value.

**What you should see** after the restart (sign in to the console):

- the browser title, the top bar and About say **Drishti Risk**, with Drishti's eye;
- Views: Terminal, Search, Functions, Data map, **VaR**, **Exposure**, Reports, Workspaces, Monitors, Alerts; no Build menu, no Forms;
- the command line suggests VaR and exposure commands (`VAR BANK`, `VRES DESK:RATES`, `EXP CPTY:CP-ALDERSHOT`); `FUNC` lists only the
  edition's commands; `FORM` says it is not part of Drishti Risk;
- Home is the **Risk desk** landing (section 4);
- Help shows Knowledge, Start, Use, Data and Operate, without the BI and Build categories;
- Admin shows users, roles, packs, commands, tokens, access, connectors, artifacts, data loads, lakes, audit, net, health, caches and BI
  settings; `/admin/ai` answers 404;
- BI's catalogue offers the VaR and exposure datasets, models and reports only.

Check it from the command line: `curl -s http://localhost:18480/public/about` answers `"edition": {"name": "risk", "title": "Drishti Risk", ...}`.

## 2. Switch back to the full Drishti

Blank the setting (remove `drishti.edition` from `application.local.yaml`, unset `DRISHTI_EDITION`, or set it empty) and restart the
server. `/public/about` then answers `"edition": null` and everything is exactly as without editions: the default packs
(`config/editions/default-packs.yaml`), every command, menu, help page, Admin page and BI dataset.

Packs an administrator loaded from Admin → Packs before the switch are in `data/packs/added.yaml`; with an allow-only edition the ones
outside it are skipped, and they load again when you switch back.

## 3. What the risk edition includes and hides

| Area | Included | Hidden |
|---|---|---|
| **Packs** | core, market-data, trading, counterparty-risk, market-risk (allow-only) | world-economy, news-and-markets, us-equities, gleif, funds, the example packs |
| **Commands** | `VAR` and the VaR page; `VRES`, `VWIF`, `VOFF`, `VLIM`, `HIER`, `PORT`, `POS`, `PNLV`, `SPV`, `FXS`, `SSET`; `EXP` and the exposure page; `XRUN`, `XPATH`, `XWIF`, `XOFC`, `AGR`, `CSA`, `NSET`, `LIM`, `COLL`, `MC`; the derived totals `NSMTM`, `CPMTM`; `CPTY`, `GRP`, `LE`, `BOOK`, `DESK`, `TRDR`, `CCP`; `TRD`, `CTRD`, `PTRD`, `LTRD`; `FX`; the words `BI`, `FUNC`, `WHAT`, `DATAMAP` | `FRTB`, stress (`STR`, `SCN`, `SSPNL`), `PNL`, `TVAL`, `CVA`, `SACCR`, `SIMM`, the curves and vol surfaces, `ISS`, `CAL`, `CLR`; `FORM`, `RUPAKA` |
| **Menus** | Views (above), BI, Admin, Help (Help centre, Knowledge, Install and run, About); footer: Help, About, Release notes | Build, Forms, the competitive analysis link |
| **Features** | BI (the edition's datasets), VaR, exposure, layouts, workspaces, monitors, alerts, reports, lakes, the data map | Calc, the Python console and Python visuals, the Sutra designer, Studio, Build, Forms, AI, embedding |
| **Admin pages** | users, roles, packs, commands, tokens, access, connectors, artifacts, loads, lakes, audit, net, health, caches, BI | AI, collaboration, embedding, edits, compute |
| **BI datasets** | `market-risk-var-latest`, `market-risk-var-official`, `counterparty-exposure-counterparties`, `counterparty-exposure-netting-sets`, `counterparty-exposure-official`, `counterparty-exposure-official-counterparties`, `counterparty-exposure-official-netting-sets`, `live-exposure`, `live-exposure-netting` (models `market-risk-var`, `counterparty-exposure`, `live-exposure`, and the reports on them) | every other dataset, model, report and board |

A hidden command is hidden from people only. The engines still read every kind they need (the VaR engine reads P&L vectors, positions,
hierarchies, scenario PVs and FX; the exposure engine its paths and agreements), and the API is the same.

## 4. Choose the Home landing

Drishti Risk has three Home pages, each filled from the engines' own answers:

| Landing | Id | What leads |
|---|---|---|
| **Risk desk** (the default) | `desk` | tiles: VaR and ES of the bank, the backtest, exposure EPE and peak PFE, limits in breach, counterparties; VaR by desk with limit use; exposure by counterparty against its limit; quick cards |
| **Command first** | `command` | a big command line, the edition's command chips, a status strip, your VaR baskets, your monitors |
| **Risk map** | `map` | the hierarchy with VaR and limit use per node and a switch between hierarchies; a limit-use heat map; Today: intraday batches, official records, breaches |

The links at the top of Home switch between them; **your choice is remembered** in your settings (it follows you to every server and
browser). "The classic Home" opens the product's Home. The edition file sets which landings are offered and the default:

```yaml
home:
  landing: desk                    # the default; today: the product's Home
  landings: [desk, command, map]
  landing-by-role: {}              # e.g. { risk-manager: map, trader: command }
```

`landing-by-role` is read and, when you configure it, wins over a person's remembered choice. It is the hook for role-based access later:
how roles are managed for it is not decided yet.

## 5. The lens: Drishti Risk (or Drishti World) for one person on a full instance

On an instance **without** an edition you can offer Drishti Risk as a *lens*: a link that focuses one person's view, with no restart and
every pack still loaded. Turn it on in the server's configuration and restart the server:

```yaml
drishti:
  lenses: risk                     # DRISHTI_LENSES=risk; blank (the default): no lens link
  # lenses: risk,world             # both: "Open Drishti Risk" and "Open Drishti World"
  # lenses: risk,world,market      # three: "Open Drishti Risk", "Open Drishti World" and "Open Drishti Market"
```

Each person then sees **Open Drishti Risk** in the Views menu and on Home. Opening it gives them, on every page and every server of the
cluster, the edition's menus, commands (command line, FUNC, type-ahead), help, Home landings, Admin pages, BI datasets and branding, with
**Leave Drishti Risk** to come back.

**Two lenses.** With `lenses: risk,world` each person sees **Open Drishti Risk** and **Open Drishti World** (Views and Home). Inside one,
the menu shows **Leave Drishti Risk** first, then **Switch to Drishti World**: switching needs no leaving first. **Three lenses** work the
same way: with `lenses: risk,world,market`, inside Drishti Market the menu shows **Leave Drishti Market**, then **Switch to Drishti Risk**
and **Switch to Drishti World**. The 3-node stack runs `DRISHTI_LENSES: risk,world,market` (`drishti-stack/stack.yaml`, `settings:`);
after changing it, restart the stack
(`drishti-stack/stop.sh && drishti-stack/start.sh`). It is kept in their settings, so a refresh, another browser or another server keeps it. Other people
are unaffected. An instance that runs an edition shows no lens link.

**A lens hides; it does not secure.** What a person may read is still decided by their roles, entitlements and masks; a lens only
changes what they are shown. Who sees the link (and which roles start in the lens) is a hook for role-based access later (`lens.roles` in
the console's configuration, read, not wired). To turn lenses off, blank `drishti.lenses` and restart: people inside one see the full
product at their next page.

## 6. Customise it without forking, and white-label it

The site's configuration wins over the edition file, key by key. On the server the file's keys are under `drishti.edition-spec`; in the
console under `edition`. Examples, in the server's `application.local.yaml`:

```yaml
drishti:
  edition: risk
  edition-spec:
    commands:
      also-allow: [FRTB]           # one more command, without restating the list
    packs:
      allow-only: false            # let Admin -> Packs load other packs too
    bi:
      datasets: [market-risk-var-latest, market-risk-var-official, trades-basic-trade]   # a list replaces the edition's
```

and in the console's `drishti-console/config/application.local.yaml` (menus, Home and help are drawn by the console):

```yaml
edition:
  menus:
    views: [terminal, search, functions, var, exposure, workspaces]   # show only these in Views
  home:
    landing: map
```

Any feature preset can be overridden by its own setting too (for example `DRISHTI_CALC_ENABLED=true` turns Calc back on).

### White-labelling checklist

Drishti Risk keeps Drishti's name and eye by default. A licensee who wants its own name sets, on both sides:

| What | Server | Console |
|---|---|---|
| Product name | `drishti.branding.product` (`DRISHTI_PRODUCT`) or the edition's `branding.product` | `ui.product` (the console takes the server's when its own is not set) |
| Tagline | `drishti.branding.tagline` | `ui.tagline` |
| Owner, copyright, notice | `drishti.branding.owner`, `copyright`, `notice` | `ui.owner`, `ui.copyright`, `ui.notice` |
| Logo (instead of the eye) | | `ui.logo`: an image under `/static/` (a site file in `web/static/`) or a `data:` URL |
| Browser icon, home-screen icon | | `ui.favicon`, `ui.apple_touch_icon`; the web manifest (`/manifest.webmanifest`) follows them and the product name |
| Colours | | a theme (`ui.default_theme`) or the UI kit's `brand.yaml` (`drishti.ui-kit.site-dir`) |
| CLI name | | `drishti.py` keeps its name; wrap it in a script of yours if needed |
| Mail | `drishti.collab.email.*` sender and subject prefix | |

Check with `drishti-console/tests/test_branding_guard.py`: it renders the console with another product name and fails on any visible
"Drishti" left.

## 7. Make your own edition

Copy the shipped file and edit it:

```bash
cp config/editions/risk.yaml config/editions/my-desk.yaml
```

| Key | What it does |
|---|---|
| `edition`, `title`, `description` | the name (the file's) and the product title shown in About |
| `packs.enabled`, `packs.allow-only` | the packs it loads; whether others are refused |
| `commands.allow`, `commands.also-allow` | the commands people may type and see (empty: all) |
| `commands.words` | `BI`, `RUPAKA`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `VAR`, `EXP`: `on` or `off` |
| `features` | presets of existing switches (`config/editions/features.yaml` says which settings each one sets); `off` presets false, anything else true |
| `bi.datasets`, `bi.models` | the BI datasets and models offered (a dataset name may end in `*`); reports and boards follow |
| `menus.<menu>` | the items shown per menu (`views`, `bi`, `build`, `admin`, `help`, `footer`), by the ids in `drishti-console/config/menus.yaml` |
| `menus.placeholder` | the command line's placeholder |
| `home.landing`, `home.landings`, `home.template`, `home.titles`, `home.landing-by-role`, `home.sections`, `home.cards`, `home.chips`, `home.hierarchies`, `home.topics` | Home (sections 4 and 10); `template` is a `home/<name>_landing.html` page (none: Drishti Risk's); `sections` switches off `stats`, `grammar`, `pipeline`, `capabilities`, `views`, `close` |
| `help.categories`, `help.knowledge`, `help.unlisted-slugs` | the help centre's categories, the packs whose Knowledge pages show, `refuse` for unlisted documents |
| `admin.pages` | the Admin pages shown (the others answer 404) |
| `branding.product`, `branding.tagline` | the product name and tagline |
| `server:`, `console:` | raw settings of either side, as presets |

Then `drishti.edition: my-desk` and restart. **Adding a measure later** (liquidity, climate) is more lines in the same file: its pack in
`packs.enabled`, its commands in `commands.allow`, its datasets in `bi.datasets`, its page in `menus.views`, its Knowledge pages in
`help.knowledge`.

## 8. In a cluster

Every member must run the same edition: set `DRISHTI_EDITION` on every node (the stack, ECS and Docker sections above do). Each member
publishes its edition in its capability digest; if they differ, **Admin → Net** shows the warning "the nodes run different editions" with
the nodes of each, and each member shows and refuses what its own edition says until you fix it. Check each node:

```bash
for p in 18480 18791 18792; do curl -s http://localhost:$p/public/about | python3 -c 'import json,sys; print(json.load(sys.stdin).get("edition"))'; done
```

VaR sharding, the exposure engine and every other cluster service work the same under an edition.

## 9. Troubleshooting

| What you see | Why | Fix |
|---|---|---|
| The server does not start: `drishti.edition='x': no edition file …` | the name does not match a file in `drishti.editions.dir` | check the name, or set `DRISHTI_EDITIONS_DIR` to the folder of the edition files |
| A command answers `DRS-4023` "is not part of the edition", or the type-ahead does not offer it | the edition hides it (`commands.allow`) | add it with `drishti.edition-spec.commands.also-allow` (section 6), or use `FUNC` to see what is offered |
| `FORM` (or another word) says "is not part of Drishti Risk" | the edition switches the word off (`commands.words`) | `edition.commands.words.FORM: on` in the console and `drishti.edition-spec.commands.words.form: on` on the server |
| An Admin page or `/forms` answers 404 | the edition hides it (`admin.pages`, a feature off) | list the page in `admin.pages`, or switch the feature on |
| Loading a pack in Admin → Packs, Deploy archive or the registry answers `DRS-1017` | the edition's packs are allow-only | add the pack to `packs.enabled`, or set `drishti.edition-spec.packs.allow-only: false` |
| A pack named in `DRISHTI_PACKS` did not load | allow-only drops it (the log says "edition risk is allow-only: not loading …") | as above |
| The console still shows the full product | it has not reached the server yet, or the server has no edition | check `/public/about`; the console follows within `backend.check_seconds` |
| A BI report is missing | its dataset or model is not in `bi.datasets` / `bi.models` | add it there |

## 10. Drishti World

**Drishti World** (`world`, `config/editions/world.yaml`) is the public-data packs in one product: economies and regions (world-economy),
news and quotes (news-and-markets), US companies and filings (us-equities), legal entities (gleif), funds and ETFs (funds). Run it as
section 1 says with `world` in place of `risk`:

```bash
DRISHTI_EDITION=world java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar
```

or offer it as a lens beside Drishti Risk (`drishti.lenses: risk,world`, section 5). The public-data lakes must be built
(`lake demo` builds them; [PUBLIC_DATA.md](../guides/PUBLIC_DATA.md) says how each source is refreshed). The IMF figures and Yahoo prices
in these packs are shown inside the bank; showing Drishti World outside it needs the product owner's decision first.

**What you should see:**

- the title, top bar and About say **Drishti World**;
- Views: Terminal, Search, Forms, Functions, Data map, Reports, Workspaces, Monitors, Alerts; no VaR, Exposure or Build;
- the command line suggests `ECON CAN`, `NEWS rates`, `USEQ AAPL`, `LEIC US`, `FUND SPY`; `FUNC` lists the packs' 32 commands; `VAR` says
  it is not part of Drishti World;
- Help shows Knowledge (Economies; Companies, funds and ETFs; Entities and ownership), Start, Use, BI, Data and Operate;
- BI offers the world-economy datasets (`world-economics-economies`, `region-economics-regions`) and their reports.

**Its landings** (the links at the top of Home switch; your choice is remembered, as in section 4):

| Landing | Id | What leads |
|---|---|---|
| **World desk** (the default) | `desk` | World GDP growth, quotes up and down in the market snapshot, the biggest mover, new SEC filings, funds and ETFs; economies that moved (growth against the year before); today's news by topic; quick cards for ECON, USEQ, LEIG and FUND |
| **Explorer** | `explorer` | a big command line, chips (`home.chips`), the counts of economies, companies, legal entities, funds, filings and topics; your recent commands, Knowledge, Data map, Learn |
| **World map** | `map` | the world by region, income group, G7 or G20, by growth, inflation or debt to GDP; US sectors by revenue; the largest funds and the index each tracks; what changed |

Every figure is the packs' own answer. What the mock-ups showed and the packs do not serve (an S&P 500 level, USD/CAD, elections in the next
90 days, G7/G20/emerging-market groups as such, day-on-day changes) is replaced by the nearest real figure or left out:
[EDITIONS.md](../architecture/EDITIONS.md) section 12 lists each.

**Choosing which topics the desk follows, the chips and the cards** is configuration, in the file or the console's `edition:` block:

```yaml
edition:
  home:
    topics: [TOPIC-RATES, TOPIC-FED, TOPIC-OIL]            # the desk's news topics (TOPIC ids)
    chips: [ECON USA, USEQ MSFT, FUND QQQ, DATAMAP]          # the Explorer's chips
    landing: map                                            # the default landing
```

Each edition names its landings, the page that draws them and their titles (`home.landings`, `home.template`, `home.titles`): a file of
your own that names no template gets Drishti Risk's page.

## 11. Drishti Market

**Drishti Market** (`market`, `config/editions/market.yaml`) is the bank's own market data in one product: curves, repo, fixings, FX
spot, forwards and vols, swaption and cap vols, credit curves, bonds, equities, indexes, dividends, inflation, commodities and correlations
(the market-data pack, with the core pack it extends). No trades and no VaR. Run it as section 1 says with `market` in place of `risk`:

```bash
DRISHTI_EDITION=market java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar
```

or offer it as a lens beside the others (`drishti.lenses: risk,world,market`, section 5). The market-data lake must be built (`lake demo`
builds it).

**What you should see:**

- the title, top bar and About say **Drishti Market**;
- Views: Terminal, Search, Forms, Functions, Data map, Reports, Workspaces, Monitors, Alerts; no VaR, Exposure or Build;
- the command line suggests `CRV CRV-USD-OIS`, `FXV FXV-EURUSD`, `IRV IRV-USD`; `FUNC` lists the pack's 22 commands; `VAR` says it is not
  part of Drishti Market, and so do `TRD`, `CPTY` and `ECON`;
- Help shows Knowledge (Market data), Start, Use, BI, Data and Operate;
- BI offers no VaR, exposure or world item: the market-data pack ships no BI dataset yet, so only its themes until one named
  `market-data-...` is added.

**Its landings** (the links at the top of Home switch; your choice is remembered, as in section 4):

| Landing | Id | What leads |
|---|---|---|
| **Market desk** (the default) | `desk` | USD OIS 10Y, USD 2s10s, EUR/USD 1M ATM vol, the CDS 5Y median, the data exceptions; the USD curves (today against a week ago when the history has it); the biggest moves in σ of their own history; quick cards for CRV, FXV, IRV and FIX |
| **Explorer** | `explorer` | a big command line, chips (`home.chips`), counts by asset class, the commands grouped by asset class with their counts; your recent commands, Knowledge, Data map, Learn |
| **Quality** | `quality` | the checks: stale, gaps (missing tenors), discount factors and forwards, vol surfaces; critical first, then warnings, each opening its command; the clean share; the impact on VaR and valuations comes in a later release |

Every figure is the pack's own answer. A day-over-day change appears only when both days hold real data; without history the tiles show
no change, the curve chart shows OIS against projection, and the biggest moves say so instead of ranking anything.

**The stale threshold** is configuration: data older than the business date by more than this many business days is stale.

```yaml
edition:
  quality:
    stale-days: 3                  # default 2 (config/editions/market.yaml)
  home:
    chips: [CRV CRV-USD-OIS, FXV FXV-USDJPY, CDS CDS-SOLARIS, DATAMAP]
```

[EDITIONS.md](../architecture/EDITIONS.md) section 13 lists every check and what is not built yet.
