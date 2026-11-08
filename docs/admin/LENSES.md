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
# Lenses: Risk, Trading, Market, World and Public sector

A **lens** is a focused experience of Drishti: the packs, commands, menus, Home, Help, Admin pages, BI items and branding that one kind of
person needs, and nothing else. Drishti stays one product with one code base; a lens is configuration. It is used in two ways:

- **for a whole instance**: `drishti.edition: risk` on the servers, then a restart, and the instance *is* Drishti Risk (section 2);
- **for one person on a full instance**: `drishti.lenses` offers lenses, and each person opens one from Views or Home, with no restart and
  every pack still loaded (section 3).

This guide shows how, for a single server, the 3-node stack, Docker and AWS ECS; what each shipped lens (Risk, Trading, Market, World and
Public sector) shows; how to adjust a lens without forking it and how to make your own; clusters; troubleshooting; and the design
(section 15). The product view of each lens (who uses it, their questions, the gaps and the proposals) is in
[docs/lenses/](../lenses/README.md).

## Contents

1. [What a lens is](#1-what-a-lens-is)
2. [Run a whole instance as one lens](#2-run-a-whole-instance-as-one-lens)
3. [Offer lenses to people](#3-offer-lenses-to-people)
4. [Home: landings, cards and chips](#4-home-landings-cards-and-chips)
5. [Drishti Risk](#5-drishti-risk)
6. [Drishti Trading](#6-drishti-trading)
7. [Drishti Market](#7-drishti-market)
8. [Drishti World](#8-drishti-world)
9. [Public sector](#9-public-sector)
10. [Customise it without forking, and white-label it](#10-customise-it-without-forking-and-white-label-it)
11. [Make your own lens](#11-make-your-own-lens)
12. [In a cluster](#12-in-a-cluster)
13. [Troubleshooting](#13-troubleshooting)
14. [Formerly editions: upgrading](#14-formerly-editions-upgrading)
15. [The design](#15-the-design)

## 1. What a lens is

Every lens belongs to a **module** and bundles **functions** ([ACCESS_MODEL.md](../architecture/ACCESS_MODEL.md),
[DOMAIN_MODULES.md](../architecture/DOMAIN_MODULES.md) section 8):

```
licence  →  modules on  →  lenses offered  →  functions this person may use  →  rows and columns inside
```

- **The module** decides whether the lens exists here. A lens names the modules it needs and is offered only while they are all on
  (installed, licensed and enabled: Admin → Modules). A module that is off offers none of its lenses and none of its packs.
- **The functions** are the command words the lens bundles (`VAR`, `TRD`, `CRV`, `ECON` ...). A card, chip, menu item or command whose
  function is off for the person (its module off, an administrator's switch on Admin → Commands) is not shown.
- **The lens** carries the experience: its packs (by module), the commands people may type and see, the top menus and their items, Home
  (landings, cards, chips), Help (sections and Knowledge pages), the Admin pages, the BI datasets and models, the feature presets and the
  branding.

A lens is defined in two places, each with one job:

| Where | What it holds |
|---|---|
| **The registry** (`META-INF/drishti/lenses.yaml` in each module's jar: [the banking module's](../../drishti-banking/src/main/resources/META-INF/drishti/lenses.yaml), [the core server's](../../drishti-server/src/main/resources/META-INF/drishti/lenses.yaml), with the economy module's lenses) | the lens's id and title, the modules it needs, the functions it bundles, its Home template, its Home **cards** (`cards:`) and **chips** (`chips:`), and for a lens with no file its menus and Help pages; a site's `config/access/lenses.yaml` adds a lens or replaces one by its id |
| **The lens file** (`config/lenses/<module>/<lens>.yaml`) | packs by module, commands and words, features, menus, Home landings, Help, Admin pages, BI, the phone display and branding: everything needed to run an instance as the lens |

The shipped lenses:

| Lens | Id | Module | File | Modules it needs | Packs, by module |
|---|---|---|---|---|---|
| **Drishti Risk** | `risk` | banking | [`config/lenses/banking/risk.yaml`](../../config/lenses/banking/risk.yaml) | banking | core: core · banking: market-data, trading, counterparty-risk, market-risk |
| **Drishti Trading** | `trading` | banking | [`config/lenses/banking/trading.yaml`](../../config/lenses/banking/trading.yaml) | banking | core: core · banking: market-data, trading, market-risk, counterparty-risk |
| **Drishti Market** | `market` | banking | [`config/lenses/banking/market.yaml`](../../config/lenses/banking/market.yaml) | capitalmarkets, banking | core: core · banking: market-data |
| **Drishti World** | `world` | economy | [`config/lenses/economy/world.yaml`](../../config/lenses/economy/world.yaml) | economy (capitalmarkets for its company, filing and fund parts) | economy: world-economy, news-and-markets, official-news, gdelt-events · capitalmarkets: us-equities, funds · core: gleif |
| **Public sector** | `publicsector` | economy | none: the registry entry | economy | the public sector's six packs (section 9) |

The registry also lists the proposed lenses (CRO, MR cube, CCR cube, CIO, Oversight): their product documents are in
[docs/lenses/](../lenses/README.md); none has a lens file or a Home yet.

**One folder.** The lens files and the feature presets every lens uses ([`config/lenses/features.yaml`](../../config/lenses/features.yaml))
are under `config/lenses` (server `drishti.lens-dir`, `DRISHTI_LENS_DIR`, default `./config/lenses` relative to the server's working
folder; the console's `lens.dir`). A file sits in its module's folder (`config/lenses/<module>/<lens>.yaml`), or in the folder itself for a
lens of no module; a module's folder is read only while the module is on.

**Packs by module.** A lens's `packs.enabled` names its packs under their modules (`{core: [core], banking: [market-data, ...]}`); a module
that is off brings none of them. The full product's packs (no lens) are [`config/packs/default-packs.yaml`](../../config/packs/default-packs.yaml),
by module too.

**A lens hides; it does not secure.** What a person may read is decided by their roles, entitlements and masks; a lens only changes what
they are shown. A person who types a hidden command's API call directly gets what their roles allow.

## 2. Run a whole instance as one lens

The setting is `drishti.edition` (environment variable `DRISHTI_EDITION`): the name of a lens file in `drishti.lens-dir`. Set it **on the
server only**: the console asks the server (`/public/about`, the same answer as `/api/v1/about`'s `edition`) and follows. A change needs a
restart of the server; the console follows within its check interval (`backend.check_seconds`, 5 s) or at its next start. The examples
use `risk`; `trading`, `market` and `world` work the same way.

**A single server.** Either in the server's `application.local.yaml` (in its working folder, git-ignored):

```yaml
drishti:
  edition: risk
```

or in the environment, from the repository root:

```bash
DRISHTI_EDITION=risk java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar
```

Leave `DRISHTI_PACKS` unset: the lens's packs load. If `DRISHTI_PACKS` is set it still wins (site configuration beats the lens file), but
with `allow-only` the packs outside the lens are not loaded, and the log says which.

**The 3-node stack (`drishti-stack/`).** Each node's settings are in `drishti-stack/stack.yaml`. Add the variable to the `env:` of every
node, then restart the stack:

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

drishti-main's `packs:` names more packs than Drishti Risk opens: the lens's load and the log names the others it skipped. The console
section needs nothing: it learns the lens from drishti-main.

**Docker Compose.** `deploy/docker/compose.yaml` passes `DRISHTI_EDITION` to the server:

```bash
DRISHTI_EDITION=risk docker compose -f deploy/docker/compose.yaml up -d
```

The images carry `config/lenses/` (server and console).

**AWS ECS.** Set `DRISHTI_EDITION=risk` in your environment file (the one `deploy/aws/env.example` documents), then register the task
definitions again and roll the services (`deploy/aws/ecs/register.sh --nodes`, as in
[CLUSTER_DEPLOYMENT.md](../architecture/CLUSTER_DEPLOYMENT.md#appendix-b-drishti-on-aws-ecs-efs-and-s3)). Every node's task definition gets
the same value.

**Check it** from the command line: `curl -s http://localhost:18480/public/about` answers
`"edition": {"name": "risk", "title": "Drishti Risk", ...}`. Signed in, the browser title, the top bar and About say **Drishti Risk**; what
else you should see is in the lens's own section (5 to 8).

**The lens must be offered.** An instance lens whose modules are off (banking for Risk, Trading and Market; capitalmarkets too for Market;
economy for World) has nothing to show: switch the module on first (Admin → Modules, then a restart).

**Back to the full product.** Blank the setting (remove `drishti.edition` from `application.local.yaml`, unset `DRISHTI_EDITION`, or set it
empty) and restart the server. `/public/about` then answers `"edition": null` and everything is the full product: the default packs
(`config/packs/default-packs.yaml`), every command, menu, Help page, Admin page and BI dataset of the modules that are on. Packs an
administrator loaded from Admin → Packs are in `data/packs/added.yaml`; with an allow-only lens the ones outside it are skipped, and they
load again when you switch back.

## 3. Offer lenses to people

On an instance that runs no lens for itself, `drishti.lenses` (`DRISHTI_LENSES`) lists the lenses a person may open. Set it in the server's
configuration and restart the server:

```yaml
drishti:
  lenses: risk,world,market,trading    # the default; DRISHTI_LENSES= (empty): no lens link, the full product only
  # lenses: risk                       # one: "Open Drishti Risk"
  # lenses: risk,world                 # two: "Open Drishti Risk" and "Open Drishti World"
```

Each person then sees **Open Drishti Risk** (and the others listed) in the Views menu and on Home, each with its lens's description.
Opening one gives them, on every page and every server of the cluster, the lens's menus, commands (command line, `FUNC`, the type-ahead),
Help, Home landings, Admin pages, BI datasets and branding, with **Leave Drishti Risk** to come back. Inside one lens the menu shows
**Leave …** first, then **Switch to …** each of the others: switching needs no leaving first. The 3-node stack runs
`DRISHTI_LENSES: risk,world,market,trading` (`drishti-stack/stack.yaml`, `settings:`); after changing it, restart the stack.

- **Kept per person.** The lens is the person's `lens` setting (the server's user store), so a refresh, another browser or another server
  keeps it; the console tells the server on each call (`X-Drishti-Lens: risk`), so the command line, `FUNC`, the type-ahead and BI follow
  it too. Other people are unaffected. Every pack stays loaded.
- **Only lenses the registry offers.** A name in `drishti.lenses` whose registry lens is not offered (a module it needs is off) is dropped:
  no link, and `/lens/<name>` answers `DRS-4023`.
- **Not with an instance lens.** An instance that runs `drishti.edition` shows no lens link.
- **Build and Studio are off inside a lens.** A page names the Sutra that drew it ("How this view was built", About this page, a pack's
  page) **without** a link to the designer; in the full product the link opens it.
- **Who sees the link.** `lens.roles` in the console's configuration (who sees the link, which roles start in the lens) is read and is the
  hook for role-based access; grants and denials of lenses per role belong to the access model ([ACCESS_MODEL.md](../architecture/ACCESS_MODEL.md)).

To turn lenses off, set `DRISHTI_LENSES=` (empty) and restart: people inside one see the full product at their next page.

## 4. Home: landings, cards and chips

**Landings.** Each shipped lens has three Home pages, each filled from the engines' and packs' own answers. The links at the top of Home
switch between them, and **the choice is remembered** in the person's settings (`homeLanding`, one value, read back by the server's
`/api/v1/me/settings`), so it follows them to every server and browser. A landing the current lens does not offer gives that lens's
default; a landing id two lenses share (`desk`, `explorer`, `map`) follows the person from one to the other. "The classic Home"
(`?landing=today`) opens the product's Home with the lens's sections.

The lens file sets which landings are offered, the default, the page that draws them and their titles:

```yaml
home:
  landing: desk                    # the default
  landings: [desk, command, map]
  template: home/risk_landing.html # a home/<name>_landing.html page; a file that names none gets Drishti Risk's
  titles: { desk: Risk desk, command: Command first, map: Risk map }
  landing-by-role: {}              # e.g. { risk-manager: map, trader: command }
  sections: { stats: off, grammar: off, pipeline: off, capabilities: off, views: off }   # the product's Home sections
```

`landing-by-role: {role: landing}` is read and, when configured, wins over a person's remembered choice (Drishti Trading's own trading
role sits below the choice: section 6).

**Cards and chips** come from the lens's registry entry (`cards:`, each `{title, cmd, icon}`; `chips:`, command lines). A card or chip is
shown only while its function is on for the person, so a lens whose capital markets functions are off loses `USEQ AAPL` and `FUND SPY`
without a change to any file. A site replaces them in the console's `edition:` block (`home.cards`, `home.chips`: section 10).

The landings are not Sutras: a Sutra lays out one entity's view, and these pages span the engines with no entity. Each lens's landing is
one template under its module's console plugin (`home/<lens>_landing.html`, with its `drishti_<lens>-landing.js` and `.css`), drawn in
the terminal look with the theme's own variables in every theme, one column on a phone.

## 5. Drishti Risk

*The product view: [RISK_LENS.md](../lenses/RISK_LENS.md).*

**Drishti Risk** (`risk`, banking module) is a one-stop shop to manage risk: VaR and exposure first; liquidity, climate and more join as
more lines in the same file.

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | core, market-data, trading, counterparty-risk, market-risk (allow-only) | world-economy, news-and-markets, us-equities, gleif, funds, the public sector's packs, the example packs (refused: `DRS-1017`) |
| **Commands** | VaR (`VAR` and its page with the basket what-if; `VRES`, `VOFF`, `VLIM`, `HIER`, `PORT`, `POS`, `PNLV`, `SPV`, `FXS`, `SSET`); exposure (`EXP` and its page; `XRUN`, `XPATH`, `XOFC`, `AGR`, `CSA`, `NSET`, `LIM`, `COLL`, `MC`); the derived totals `NSMTM`, `CPMTM`; reference data `CPTY`, `GRP`, `LE`, `BOOK`, `DESK`, `TRDR`, `CCP`; trades `TRD`, `CTRD`, `PTRD`, `LTRD`; `FX`; the words `BI`, `FUNC`, `WHAT`, `DATAMAP`, `DATA` (Export data), `SMAT` (the scenario matrix), `PLAY` (the VaR Playground) | stress (`STR`, `SCN`, `SSPNL`), `PNL`, `TVAL`, `CVA`, `SACCR`, the curves and vol surfaces, `ISS`, `CAL`, `CLR`; the words `FORM`, `RUPAKA` |
| **Menus** | Views (Terminal, Search, Functions, Data map, Export data, Reports, Workspaces, Monitors, Alerts), Engines (VaR, exposure and the other engines running), BI, Admin, Help (Help centre, Knowledge, Install and run, About); footer: Help, About, Release notes | Build, Forms |
| **Features** | BI (the lens's datasets), VaR, exposure, the data map, Export data, layouts, workspaces, monitors, alerts, reports, lakes | Calc and Python visuals, the Sutra designer, Studio, Build, Forms, AI, embedding |
| **Help** | Get started, Risk measures, Data, Operate, Reference, Knowledge (market risk, counterparty credit risk, core, trading, market data), Internal for administrators | Analytics and BI, Build; other packs' Knowledge pages; documents not in the catalogue |
| **Admin** | users, roles, packs, commands, tokens, access, connectors, artifacts, loads, lakes, audit, net, engines, health, caches, capacity, BI, scopes (every person's VaR baskets), configuration | AI, collaboration, embedding, edits, compute (404) |
| **BI** | `banking.market_risk_var_latest`, `banking.market_risk_var_official`, the `banking.counterparty_exposure_*` datasets, `banking.live_exposure_netting`, `bi.trading_risk_*`; models `market-risk-var`, `counterparty-exposure`, `live-exposure`, `trading-risk`, and the reports on them | every other dataset, model, report and board |
| **Branding** | **Drishti Risk**, "VaR and exposure from your own pricing data", with Drishti's name and eye | nothing, by default |

A hidden command is hidden from people only. The engines still read every kind they need (the VaR engine reads P&L vectors, positions,
hierarchies, scenario PVs and FX; the exposure engine its paths and agreements), and the API is the same: VaR and exposure of any scope,
any currency, intraday, official records, the what-if, the API and the CLI work as on the full product.

The command line suggests `VAR BANK`, `VRES DESK:RATES`, `EXP CPTY:CP-ALDERSHOT`; the registry's chips add `VAR DESK RATES`, `EXP BANK`,
`HIER` and `VOFF`, and its cards open the bank's and the rates desk's VaR, VaR results by desk and a counterparty's exposure.

**Its landings:**

| Landing | Id | What leads |
|---|---|---|
| **Risk desk** (the default) | `desk` | tiles: VaR and ES of the bank, the backtest, exposure EPE and peak PFE, limits in breach, counterparties and netting sets; VaR by desk with limit use; exposure by counterparty against its credit limit; quick cards |
| **Command first** | `command` | a big command line, the lens's chips, a status strip, the person's VaR baskets, their monitors, links to learn |
| **Risk map** | `map` | the hierarchy with VaR and limit use per node and a switch between the hierarchies the engine holds (`home.hierarchies` names the one it opens on); a limit-use heat map; Today: intraday batches, official records, breaches |

Every figure is the engines' own answer through the console's `/api/var/...` and `/api/exposure/...` (`scope`, `scope/history`, `tree`,
`intraday/streams`, `official`, `baskets`). What the engines do not serve is left out or replaced: exposure (EPE) per node of a VaR
hierarchy (the map shows VaR and limit use only); credit-limit use per desk (the heat map shows each node's VaR limit use); the time of an
official capture (the feed names the date captured).

## 6. Drishti Trading

*The product view: [TRADING_LENS.md](../lenses/TRADING_LENS.md).*

**Drishti Trading** (`trading`, banking module) is for traders and desk heads: my books, my P&L today, my VaR and limits, today's trades,
my counterparties, and whether a new trade fits. It loads the same five packs as Drishti Risk and shows the blotter, books and positions,
risk on my book, counterparties and the market data a trader reads; regulatory, stress, collateral operations and macro economics are left
out.

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | core, market-data, trading, market-risk, counterparty-risk (allow-only) | the public-data packs, the sample and example packs (`DRS-1017`) |
| **Commands** | the blotter `TRD`, `CTRD`, `PTRD`, `LTRD`, `ITRD`; books and positions `BOOK`, `DESK`, `TRDR`, `POS`; risk on my book `VLIM`, `VRES`, `TVAL`, `PNLV`, `HIER`, `PORT`, `DPNL`, `PNL`, `SENS`; counterparties `CPTY`, `GRP`, `NSET`, `NSMTM`, `CPMTM`, `LIM`, `EXP`; market data `CRV`, `REPO`, `FIX`, `IRV`, `CPV`, `FX`, `FXF`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS`; the words `VAR`, `EXP`, `BI`, `FUNC`, `WHAT`, `DATA`, `SMAT`, `PLAY` | regulatory (`SACCR`, `CVA`), stress (`SCN`, `STR`, `SSPNL`), collateral operations (`COLL`, `MC`), the exposure engine's runs, paths and official records, VaR's scenario inputs, macro economics; the words `DATAMAP`, `FORM`, `RUPAKA` |
| **Menus** | Views (Terminal, Search, Functions, Export data, Reports, Workspaces, Monitors, Alerts); Engines; one **Markets** menu (rates, vols, FX, credit, equities and commodities: `menus.markets`); Blotter, Positions, Risk, Counterparties; Help. BI and Admin by command only (`BI <GO>`; `ADMIN <GO>` for administrators) | Forms, Data map, Build; BI and Admin in the bar |
| **Features** | VaR, exposure, BI (the lens's datasets), layouts, workspaces, monitors, alerts, reports, lakes | Calc, Python visuals, the designer, Studio, Build, forms, the data map, AI, embedding |
| **Help** | Knowledge (Trades and products, Market risk, Counterparty credit risk, Entities and ownership, Market data), with the other sections but Build | Build; the public-data packs' Knowledge pages |
| **BI** | `bi.trading_risk_*`, `bi.trading_cashflows_*`, `banking.trades_basic_trade`, `banking.live_trading_trade`, the VaR datasets; their models and reports | exposure, stress, world and market-data items |
| **Branding** | **Drishti Trading**, "Your books, your P&L and your limits, and whether the next trade fits" | |

`SMAT BOOK BOOK-RATES-1 20` opens the scenario matrix: the book's scenarios across its last 20 business dates; a trade's, a position's and
the VaR page's **Scenario matrix** link open it too ([VAR_GUIDE.md section 3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat)).

**Its landings** (green accent):

| Landing | Id | What leads |
|---|---|---|
| **Trader desk** (the default; traders) | `trader` | P&L today (MtM change since the business day before, over my books), my VaR (intraday when updated) and the highest limit use of my books, DV01 (when the valuations carry it), trades today (new, amended, cancelled); **Check a new trade**; today's blotter; top positions by VaR contribution; quick commands for my trader and book |
| **Desk head** (desk heads) | `desk` | desk P&L today, desk VaR and its limit use, breaches, books near their limit, the largest counterparty's share of the desk's positive MtM; limit use by book over the last 5 business dates (each cell prints its percentage); by trader (P&L, VaR, trades); P&L against VaR over 60 days (official history only); overnight movers |
| **Explorer** | `explorer` | a big command line, the chips, the commands grouped (blotter, books and positions, risk on my book, counterparties, market data), the person's recent Trading commands |

The figures are worked out by `plugins/banking/trading_landing.py` and served read-only by
`/api/home/trading/{me,valuations,blotter,risk,limits,pnl-var,traders}` and `POST /api/home/trading/check` (`404 DRS-4023` unless the
person's lens shows `TRD`; the check also needs the words `VAR` and `EXP`), cached two minutes per person, date and scope.

#### "My": who a person is, configured

The landings open on **the person's own** books or desk. Who that is, in order:

1. **Configuration.** `trading.people` in the lens file, with the console's own `trading.people` (`application.local.yaml`) added on top
   (a name in both: the console's). User names are compared ignoring case.

   ```yaml
   trading:
     people:
       a.shah: { trader: TRDR-ASHAH }                      # a trader: their trader record's books; opens on the Trader desk
       t.brennan: { desk: DESK-COMM, role: desk-head }      # a desk head: the desk's books; opens on Desk head
   ```

2. **A trader record** (`trading.match-records`, on): a `trader` whose id is `TRDR-<USER>` (case, dots and dashes ignored) or whose
   `NAME` is the person's display name, read with their own search.
3. **Their own choice**: a person with neither sees a small **View as** picker (the traders and desks their search returns); the choice is
   kept in their settings (`tradingView`: `trader:<id>` or `desk:<id>`, validated by the server's settings API).
4. An **administrator** always sees the picker, and their choice wins over a mapping; a mapped person who is not an administrator cannot
   repoint themselves (`/home/trading/view-as` refuses with `DRS-4023`).

**Which landing opens first** follows the trading role: `trading.landing-by-role: {desk-head: desk, trader: trader}` (a View as desk counts
as a desk head). It sits **below the person's own choice**, so a desk head who picks the Trader desk keeps it; `home.landing-by-role` still
wins over both when configured.

**It never widens access.** Every figure is read through the existing routes as the person, with their token, roles, masks, lens and date
box; scoping only chooses which books the landing asks about. A role without raw sees `TRADER_ID` masked, so the Desk head's figures by
trader say so instead of guessing.

**Other settings** (all in `trading:`): `near-limit` (0.8: from this share of a limit a book is "near"), `limit-days` (5),
`pnl-var-days` (60), `max-rows` (20,000 valuations a date before the figures say **partial**), `live-statuses` (`NEW`, `VALIDATED`,
`AMENDED`).

#### How the figures are made

- **P&L today**: the MtM change of every trade of my books valued on both the business date and the business day before (`TVAL`, read on
  both dates; the person's date box moves both), summed. The valuations' own `PNL_1D` (the pricing systems' explained P&L) is a different
  figure and is not used.
- **My VaR**: the VaR engine's figure of the union of my books (`BOOK:A + BOOK:B`), labelled **intraday** with the trades updated and the
  time when the engine applied intraday updates. **Limit use**: the highest of my books' VaR ÷ its limit, with the book and limit named.
- **Who adds up.** Every total (P&L today, MtM, DV01, by book, by trader, the largest counterparty's share) is the server's: the console
  sends the valuations it read to the pivot engine (`POST /api/v1/pivot/inline`) and shows its figures. With the pivot engine off the
  tiles show no figure and say why.
- **Trades today**: the trades of my books whose `SOURCE_UPDATE_TIME` is on the business date: **cancelled** (`TRADE_STATUS` CANCELLED),
  **new** (`TRADE_ACTION` NEW, or `TRADE_DATE` that day), else **amended**. When that search does not answer in time on a large lake
  (`DRS-1004`), the events come from the two days' valuations, with no time of day, and the tile says so.
- **The day**: when the lake's latest valuations are older than the business date, the figures are of that day against the business day
  before it, and the page says "the latest valuations are of …".
- **Limit use by book**: each cell VaR ÷ limit from that date's **official VaR record** (`/api/v1/var/official`); a date without a record
  shows "—" and is named under the grid.
- **By trader**: each trader's component VaR over every trade of the desk (`/scope/contributions?by=attribute:TRADER_ID`), adding up to
  the desk's VaR. With `TRADER_ID` masked the P&L and VaR are "—" and the card says why.
- **P&L against VaR**: the desk's official history (`/scope/history`, mode `official`), the last `pnl-var-days`, exceptions circled. A
  replayed history is scenario P&L, not a day's: then the card is left out with that note.
- **Largest counterparty**: its share of the desk's positive MtM over live trades. The exposure engine takes `DESK:<id>` as a `partial`
  scope ([EXPOSURE_GUIDE.md section 4](../guides/EXPOSURE_GUIDE.md#4-scopes)), but not a desk's **allocated** share of each
  counterparty's netted, collateralised exposure, so the tile ranks by positive MtM and says so.

#### The new-trade check

Pick a product (the products of your live trades), the side, a notional (`250m`, `1.5bn`), the counterparty and the book, then **Check**.
The engines price a **copy of a booked trade**: a live trade of the product with a notional in my books (the counterparty's own first,
then the chosen book's, then the largest notional), scaled to the notional (negative when the side differs). One answer from the two
engines, each asked once, as the person:

- **VaR**: `POST /api/v1/var/what-if {scope: BOOK:<book>, items: [{copyOf, factor}]}` (the VaR page's what-if) gives VaR before and after;
  the book's limit gives limit use before and after: **fits** below `near-limit`, **near** from it, **breaches** above 100%.
- **Counterparty**: `POST /api/v1/exposure/what-if` (the exposure page's pre-deal check, the copy dealt into the counterparty's largest
  netting set) gives peak PFE before and after against the counterparty's credit limit. When the exact `paths` method cannot run, the
  engine's conservative `addon` proxy answers, labelled **proxy** with the reason.

A verdict reads: *Fits my VaR · 6.12m → 6.30m · limit use 83% → 85%* and *Counterparty near its limit · peak PFE 401.5m → 409.2m · 97%
of limit*. Both halves are the server's engines and need no compute workers.

#### What the data does not give (left out or labelled, never invented)

The DV01 tile when no live trade holds a DV01; P&L today, by book and by trader, and the movers without valuations on the day before; the
P&L-against-VaR card without official history; by-trader P&L and VaR for a role that sees `TRADER_ID` masked; a book's limit use on a date
without an official record; a trade's time of day other than its `SOURCE_UPDATE_TIME`; the largest counterparty's share of **exposure**
(the share of positive MtM instead, named so). A scope larger than `trading.max-rows` is read in part and every figure says **partial**.

## 7. Drishti Market

*The product view: [MARKET_LENS.md](../lenses/MARKET_LENS.md).*

**Drishti Market** (`market`, banking module, needing capitalmarkets too) is the bank's own market data: curves, repo, fixings, FX spot,
forwards and vols, swaption and cap vols, credit curves, bonds, equities, indexes, dividends, inflation, commodities and correlations (the
market-data pack, with the core pack it extends). No trades and no VaR. The market-data lake must be built (`lake demo` builds it).

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | core, market-data (allow-only) | trading, counterparty-risk, market-risk, the public-data packs, the example packs (`DRS-1017`) |
| **Commands** | the pack's: `CRV`, `REPO`, `IRV`, `CPV`, `FIX`, `INF`, `INFC`; `FX`, `FXF`, `FXV`; `CDS`, `BND`; `EQ`, `EQX`, `DIV`, `EQV`, `CMD`, `CMDC`, `CMDV`; `CORR`, `CORRC`, `EQB`; the words `BI`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `DATA` | `VAR`, `EXP`, `SMAT`, `PLAY` and every risk and trade command; core's own (`CPTY`, `BOOK`, `CAL`, `ISS` …); `RUPAKA` |
| **Menus** | Views (Terminal, Search, Forms, Functions, Data map, Export data, Reports, Workspaces, Monitors, Alerts), Curves, Volatility, FX, Credit, BI, Admin, Help | Build, the risk engines |
| **Help** | Knowledge (Market data), with the other sections but Build | Build; the other packs' Knowledge pages |
| **BI** | `bi.market_data_*` datasets and the `market-data` model: the pack ships none yet, so BI offers its themes only | every VaR, exposure, trading and world item |
| **Branding** | **Drishti Market**, "Curves, surfaces and fixings: the bank's market data that feeds risk and valuations" | |

**Its landings** (violet accent). The figures are worked out in the console by `plugins/banking/market_landing.py` from the pack's own
answers, read as the person, cached two minutes per person, and served by `/api/home/market/desk`, `/api/home/market/moves` and
`/api/home/market/quality` (`404 DRS-4023` unless the person's lens shows `CRV`).

| Landing | Id | What leads |
|---|---|---|
| **Market desk** (the default) | `desk` | USD OIS 10Y, USD 2s10s, EUR/USD 1M ATM vol, the CDS 5Y median, the data exceptions; the USD curves (today against a week ago when the history has it); the biggest moves in σ of their own history; quick cards for CRV, FXV, IRV and FIX |
| **Explorer** | `explorer` | a big command line, the chips, counts by asset class, the commands grouped by asset class with their counts; the person's recent commands, Knowledge, Data map, Learn |
| **Quality** | `quality` | the checks below, critical first, then warnings, each opening its command; the clean share; a Checks run card |

- **Changes only from real data.** A day-over-day change appears only when the history holds real data for both days (a point whose data
  date is its date, not one carried from earlier); otherwise the tile says "no change: no history for the day before". Without that
  history the curve chart shows OIS against projection, and the biggest moves say so instead of ranking anything.
- **Biggest moves**: each curve's `TEN_Y`, credit curve's `SPREAD_5Y`, FX spot's `MID`, commodity's `FRONT` and index's `LEVEL` over 21
  business days; the latest move in standard deviations of the item's own daily moves when ten or more are held (a "jump" at 3σ or more);
  with less history, the largest moves against the day before. The CDS median and each move's σ are the server's pivot engine's.
- **The quality checks**, worst first:
  1. **Stale** (warning): a document older than the business date by more than `quality.stale-days` business days (weekends and the
     server's holidays skipped).
  2. **Gaps** (warning): a curve missing a tenor that every other curve of its type has (a type of three or more).
  3. **Discount factors and forwards**: a discount factor that does not fall with maturity, or is outside (0, 1] (critical); a negative
     forward rate (warning).
  4. **Vol surfaces** (critical): a vol at or below zero (risk reversals and butterflies may be negative), and ATM total variance falling
     with expiry on the FX and equity surfaces.

**Not built** (planned in IMPLEMENTATION_PLAN.md): an Impact card (who uses each flagged point, VaR at stake), jumps over n σ as a quality
check, and two sources disagreeing.

**The stale threshold** and the chips are configuration:

```yaml
edition:
  quality:
    stale-days: 3                  # default 2 (config/lenses/banking/market.yaml)
  home:
    chips: [CRV CRV-USD-OIS, FXV FXV-USDJPY, CDS CDS-SOLARIS, DATAMAP]
```

## 8. Drishti World

*The product view: [WORLD_LENS.md](../lenses/WORLD_LENS.md).*

**Drishti World** (`world`, economy module) is the public-data packs in one product: economies and regions (world-economy), news and quotes
(news-and-markets), the public sector's free news (official-news: `PRESS`, `PREL`; gdelt-events: `GDELT`), legal entities (gleif), and, while
the **capitalmarkets** module is on, US companies and filings (us-equities) and funds and ETFs (funds). The public-data lakes must be built
(`lake demo` builds them; [EXTERNAL_DATA.md](../guides/EXTERNAL_DATA.md) says how each source is refreshed). The IMF figures and Yahoo prices
in these packs are shown inside the bank; showing Drishti World outside it needs the product owner's decision first.

**World is economy with capital markets.** While capitalmarkets is off, its packs (us-equities, funds), its functions (`USEQ`, `IDX`,
`SIC`, `EDGAR`, `FILING`, `FACT`, `PXH`, `FUND`, `HELD`, `TRACK`), its cards and chips, and the World landing's company, filing and fund
parts are gone; the rest of World stands.

| Area | Shown | Hidden |
|---|---|---|
| **Packs** | world-economy, news-and-markets, official-news, gdelt-events, gleif, us-equities, funds (allow-only) | core, market-data, trading, counterparty-risk, market-risk, the example packs (`DRS-1017`) |
| **Commands** | the packs' own: `ECON`, `MACRO`, `FCST`, `LABR`, `FISC`, `ECG`, `REGE`, `JUR`, `PARTY`, `OFFC`, `ELEC`, `REGN`, `SOCI`; `TOPIC`, `NEWS`, `CO`, `QUOTE`; `USEQ`, `IDX`, `SIC`, `EDGAR`, `FILING`, `FACT`, `PXH`; `LEI`, `LEIG`, `LEIR`, `LEIC`, `LEIM`; `FUND`, `HELD`, `TRACK`; `PRESS`, `PREL`, `GDELT`; the words `BI`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `DATA` | `VAR`, `EXP`, `SMAT`, `PLAY` and every risk command; `RUPAKA` |
| **Menus** | Views (Terminal, Search, Forms, Functions, Data map, Export data, Reports, Workspaces, Monitors, Alerts), Economies, Markets (news and quotes), Companies, BI, Admin, Help | Build, the risk engines |
| **Help** | Knowledge (Economies; Companies, funds and ETFs; Entities and ownership; the news packs), with the other sections but Build | Build; the risk packs' Knowledge pages |
| **BI** | `bi.world_economics_economies`, `bi.region_economics_regions`; models `world-economics`, `region-economics`; their reports | every other dataset, model, report and board |
| **Branding** | **Drishti World**, "Economies, markets, companies and funds from public data" | |

**Its landings** (teal accent). One thin read-only route, `/api/home/search` (`routes/edition_routes.py`), runs the server's own search
grammar (`ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12`), refused with `404 DRS-4023` for a command the person's lens does
not show; the server filters again by the lens it is told. Each panel paints when its answer arrives; one refused or empty says so in place.

| Landing | Id | What leads |
|---|---|---|
| **World desk** (the default) | `desk` | World GDP growth (GDP-weighted), quotes up and down in the market snapshot, the biggest mover, new SEC filings, funds and ETFs; economies that moved (the twelve largest, growth against the year before); today's news by topic (`home.topics`); quick cards |
| **Explorer** | `explorer` | a big command line, the chips, the counts of economies, companies, legal entities, funds, filings and topics; the person's recent commands (World's only), Knowledge, Data map, Learn |
| **World map** | `map` | the world by region, income group, G7 or G20, by growth, inflation or debt to GDP (each group GDP-weighted from its economies); US sectors by revenue; the largest funds and the index each tracks; what changed |

**What the packs do not serve** (left out or replaced by the nearest real figure): an index level and USD/CAD (the snapshot holds equity
quotes, no index and no FX: the tiles show breadth and the biggest mover instead); elections in the next 90 days (free-text dates); G7, G20
and emerging-market country groups as such (shown as their member economies; the income groups stand for emerging markets); a debt
aggregate per group (computed, GDP-weighted, from the members' `DEBT_TO_GDP`); "since yesterday" changes (no change feed: What changed
shows the latest of each source); new GLEIF entities and parent changes (the counts on file instead).

**The desk's topics, the chips and the default landing** are configuration, in the file or the console's `edition:` block:

```yaml
edition:
  home:
    topics: [TOPIC-RATES, TOPIC-FED, TOPIC-OIL]            # the desk's news topics (TOPIC ids)
    chips: [ECON USA, USEQ MSFT, FUND QQQ, DATAMAP]          # the Explorer's chips
    landing: map                                            # the default landing
```

## 9. Public sector

*The guide: [PUBLIC_SECTOR_GUIDE.md](../guides/PUBLIC_SECTOR_GUIDE.md), section 2 to turn it on.*

**Public sector** (`publicsector`, economy module) is development indicators, central banks, national statistics, the OECD, official news
and world events: the packs world-development, central-banks, national-statistics, oecd, official-news and gdelt-events. It is defined by
its registry entry alone ([the core server's lenses](../../drishti-server/src/main/resources/META-INF/drishti/lenses.yaml)):

| Part | As shipped |
|---|---|
| **Modules** | economy |
| **Functions** | `WDI`, `WDIS`, `CBANK`, `CBSER`, `NSTAT`, `OECD`, `OECDS`, `PRESS`, `PREL`, `GDELT` |
| **Menus** | Views, BI, Admin, Help |
| **Cards** | a country's development (`WDI WDI-CAN`), one indicator's history, a central bank, a rate series, Statistics Canada, OECD members by inflation, official press releases, the world's news by tone (GDELT) |
| **Chips** | `WDI WDI-USA`, `CBANK CBANK-FED`, `OECD OECD-USA`, `PRESS PRESS-ECB` |
| **Help** | the Public sector guide and its tutorial, the six packs' guides, External data |

With the economy module off, all its packs and functions are gone and neither World nor Public sector is offered. It ships no lens file and
no landing template of its own, so it is not run as a whole instance's lens; to do that, or to give it landings, write a lens file for it
in `config/lenses/economy/` (section 11).

## 10. Customise it without forking, and white-label it

The site's configuration wins over the lens file, key by key. On the server the file's keys are under `drishti.edition-spec`; in the
console under `edition`. Examples, in the server's `application.local.yaml`:

```yaml
drishti:
  edition: risk
  edition-spec:
    commands:
      also-allow: [TVAL]           # one more command, without restating the list
    packs:
      allow-only: false            # let Admin -> Packs load other packs too
    bi:
      datasets: [banking.market_risk_var_latest, banking.market_risk_var_official, banking.trades_basic_trade]   # a list replaces the lens's
```

and in the console's `drishti-console/config/application.local.yaml` (menus, Home and Help are drawn by the console):

```yaml
edition:
  menus:
    views: [terminal, search, functions, workspaces]   # show only these in Views
  home:
    landing: map
    chips: [VAR BANK, EXP BANK]                       # replace the registry's chips
```

`drishti.edition-spec` overrides apply to the instance's lens only; a lens a person opens (`drishti.lenses`) is read from its file alone.
Any feature preset can be overridden by its own setting too (for example `DRISHTI_LABS_CALC=true` turns Calc, a Labs feature, on). A
site's `config/access/lenses.yaml` adds a lens to the registry or replaces a shipped one by its id (its modules, functions, cards and
chips).

**On a phone** ([MOBILE_DISPLAY.md](../architecture/MOBILE_DISPLAY.md), [USER_GUIDE.md, On a phone](../guides/USER_GUIDE.md#on-a-phone)).
A lens's `phone:` block sets and caps the phone tier of panels over every Sutra, after the site's `ui.phone`; `max:` is a cap no Sutra can
exceed. `pages:` does the same for the parts of the VaR and exposure pages, and `home.phone` for the lens's landing. Drishti Risk ships one
rule: a VaR contribution panel waits under *More on this page*.

```yaml
phone:
  rules:
    - { title: "*contribution*", tier: more }       # keys: panel, kind, title, sutra, entity (globs); tier or max
    - { kind: pivot, max: desk }
  pages:
    var: { hist: glance, whatif: desk }              # parts: tiles, method, path-dist, path-tail, path-series, path-table, hist,
                                                     # contrib, captures, ccy, tree, prov, whatif, basket
home:
  phone: { table: more }                             # parts: hero, tiles, quick, card-1, table, chart, map, feed
```

On a phone the lens's subject menus fold into one menu named after it (its title without the product's name: **Trading**); the Engines
menu is no subject menu and stays in the bar, its columns folding into a drawer.

### White-labelling checklist

A lens keeps Drishti's name and eye by default. A licensee who wants its own name sets, on both sides:

| What | Server | Console |
|---|---|---|
| Product name | `drishti.branding.product` (`DRISHTI_PRODUCT`) or the lens's `branding.product` | `ui.product` (the console takes the server's when its own is not set) |
| Tagline | `drishti.branding.tagline` | `ui.tagline` |
| Owner, copyright, notice | `drishti.branding.owner`, `copyright`, `notice` | `ui.owner`, `ui.copyright`, `ui.notice` |
| Logo (instead of the eye) | | `ui.logo`: an image under `/static/` (a site file in `web/static/`) or a `data:` URL |
| Browser icon, home-screen icon | | `ui.favicon`, `ui.apple_touch_icon`; the web manifest (`/manifest.webmanifest`) follows them and the product name |
| Colours | | a theme (`ui.default_theme`) or the UI kit's `brand.yaml` (`drishti.ui-kit.site-dir`) |
| CLI name | | `drishti.py` keeps its name; wrap it in a script of yours if needed |
| Mail | `drishti.collab.email.*` sender and subject prefix | |

Check with `drishti-console/tests/test_branding_guard.py`: it renders the console with another product name and fails on any visible
"Drishti" left.

## 11. Make your own lens

Copy a shipped file into the folder of the module it belongs to (or into `config/lenses/` itself for a lens of no module) and edit it:

```bash
cp config/lenses/banking/risk.yaml config/lenses/banking/my-desk.yaml
```

| Key | What it does |
|---|---|
| `edition`, `title`, `description` | the lens's name (the file's), the product title shown in About, the top bar and the lens menu, and its one line |
| `packs.enabled`, `packs.allow-only` | the packs it loads, by module (`{core: [...], banking: [...]}`; a plain list is read too); whether others are refused |
| `commands.allow`, `commands.also-allow` | the commands people may type and see (empty: all) |
| `commands.words` | `BI`, `RUPAKA`, `FORM`, `FUNC`, `WHAT`, `DATAMAP`, `DATA`, `VAR`, `EXP`, `SMAT`, `PLAY`: `on` or `off`. Off, the word answers `DRS-4023` and its pages and APIs answer 404 (`DATA`: `/data`, `/api/data/...`, the server's `/api/v1/data`; `PLAY`: `/play`, `/api/play/...` and the VaR page's **Playground** link; `SMAT`: `/smat`, `/api/smat/...` and every **Scenario matrix** link) |
| `features` | presets of existing switches (`config/lenses/features.yaml` says which settings each one sets); `off` presets false, anything else true. Every shipped lens sets `internal-only-data` off, so datasets marked internal only are listed but not downloaded |
| `bi.datasets`, `bi.models` | the BI datasets and models offered (a name may end in `*`); reports and boards follow |
| `menus.top` | the top menus by id, in order; a menu not named is not drawn (none: Views, Engines, BI, Build, Admin, Help). The Engines menu has a column per compute engine running in the cluster that the person's role opens. The subject menus (`subject: true`: `blotter`, `positions`, `risk`, `counterparties`; `curves`, `volatility`, `fx`, `credit`; `economies`, `markets`, `companies`) are drawn only where `menus.top` names them. A menu's `gather: [ids]` puts the columns of those menus first in its own: the one **Markets** menu gathers `curves`, `volatility`, `fx` and `credit`, each item picked by `menus.markets` |
| `menus.<menu>` | the items shown per menu (`views`, `bi`, `build`, `admin`, `help`, `footer`, `markets`, or a subject menu), by the ids in `drishti-console/config/sitemap.yaml` |
| `menus.placeholder` | the command line's placeholder |
| `home.landing`, `home.landings`, `home.template`, `home.titles`, `home.landing-by-role`, `home.sections`, `home.hierarchies`, `home.topics` | Home (section 4); `template` is a `home/<name>_landing.html` page (none: Drishti Risk's); `sections` switches off `stats`, `grammar`, `pipeline`, `capabilities`, `views`, `close` |
| `help.categories`, `help.knowledge`, `help.unlisted-slugs` | the Help centre's sections by id (`config/help.yaml` `sections:`; an old name such as `start` or `use` is read through its `aliases:`), the packs whose Knowledge pages show, `refuse` for documents not in the catalogue |
| `admin.pages` | the Admin pages shown (the others answer 404) |
| `phone` | the phone display (section 10) |
| `branding.product`, `branding.tagline` | the product name and tagline |
| `server:`, `console:` | raw settings of either side, as presets |

Then `drishti.edition: my-desk` (or add `my-desk` to `drishti.lenses`) and restart. A lens a person opens also needs a registry entry
(the modules it needs and its functions, cards and chips): add it to the site's `config/access/lenses.yaml` with the same id. **Adding a
measure later** (liquidity, climate) is more lines in the same file: its pack in `packs.enabled`, its commands in `commands.allow`, its
datasets in `bi.datasets`, its page in `menus.views`, its Knowledge pages in `help.knowledge`.

### Proposed lenses

The same mechanism serves the proposed lenses without new engine code; each will be a lens file with its landings when built. **Drishti
CRO** (`cro`: landings `appetite`, `brief` and `detail`, the command word `CRO`) is sketched in
[CRO_LENS.md section 10](../lenses/CRO_LENS.md#10-the-lens-design); it needs new packs for the risk appetite catalogue, liquidity, capital
and climate inputs, and the governance actions Oversight proposes. **Drishti CIO** (`cio`: landings `status`, `brief` and `detail`, the
command word `CIO`) is sketched in [CIO_LENS.md section 11](../lenses/CIO_LENS.md#11-the-lens-design); it needs one new `platform` pack of
captured records over the load, audit and access records Drishti keeps today. The **MR cube** and **CCR cube**
([MR_CUBE_LENS.md](../lenses/MR_CUBE_LENS.md), [CCR_CUBE_LENS.md](../lenses/CCR_CUBE_LENS.md)) and **Oversight**
([OVERSIGHT_LENS.md](../lenses/OVERSIGHT_LENS.md)) have registry entries and product documents. A **funds** lens (us-equities, gleif,
funds) or a **data platform** lens (BI and Build without the risk packs) is one more file.

## 12. In a cluster

**Three switches, each with its own job.** The **lens** (the instance's or a person's) decides which commands and pages people are offered,
the same on every node; a **command switch** (Admin → Commands) turns one function off for everyone in the cluster; an **engine switch**
(Admin → Engines, `config/engines/<engine>.yaml`, [OPERATIONS.md section 21](OPERATIONS.md#21-compute-engines-on-or-off)) decides whether a
compute engine (VaR, exposure, FRTB-SA, SIMM, ...) runs on one node. A word is shown when all three allow it: an engine running somewhere
in the cluster, the lens offering it, and its function on. A lens's `var: off` or `exposure: off` presets the engine's switch. Underneath
them all, a module that is off offers none of its lenses, functions or packs.

**Every member runs the same instance lens**: set `DRISHTI_EDITION` on every node (the stack, ECS and Docker examples of section 2 do);
each reads the same file (it ships in every image under `config/lenses/`). Each member publishes its lens in its capability digest (the
service `edition`); if they differ, **Admin → Net** shows the warning "the nodes run different editions" with the nodes of each, and each
member shows and refuses what its own lens says until you fix it. Check each node:

```bash
for p in 18480 18791 18792; do curl -s http://localhost:$p/public/about | python3 -c 'import json,sys; print(json.load(sys.stdin).get("edition"))'; done
```

A person's lens is kept in their settings (the server's user store), so every member and every console worker sees it, and the server is
told on each call (`X-Drishti-Lens`). VaR sharding, the exposure engine and every other cluster service work the same under a lens: a lens
hides commands from people, never a kind an engine reads.

## 13. Troubleshooting

| What you see | Why | Fix |
|---|---|---|
| The server does not start: `drishti.edition='x': no edition file …` | the name does not match a file in `drishti.lens-dir` or one of its module folders | check the name, or set `DRISHTI_LENS_DIR` to the folder of the lens files |
| No "Open …" link for a lens named in `drishti.lenses` | its registry lens is not offered (a module it needs is off), it has no file, or the instance runs a lens itself | switch the module on (Admin → Modules, restart); the server's log says "lens … is not offered" with the reason |
| A command answers `DRS-4023` "is not part of the edition", or the type-ahead does not offer it | the lens hides it (`commands.allow`) | add it with `drishti.edition-spec.commands.also-allow` (section 10), or use `FUNC` to see what is offered |
| A command answers `DRS-1026` or "not available to you" | its function is off (its module off, or Admin → Commands) | switch the module or the function on |
| `FORM` (or another word) says "is not part of Drishti Risk" | the lens switches the word off (`commands.words`) | `edition.commands.words.FORM: on` in the console and `drishti.edition-spec.commands.words.form: on` on the server |
| An Admin page or `/forms` answers 404 | the lens hides it (`admin.pages`, a feature off) | list the page in `admin.pages`, or switch the feature on |
| Loading a pack in Admin → Packs, Deploy archive or the registry answers `DRS-1017` | the lens's packs are allow-only | add the pack to `packs.enabled`, or set `drishti.edition-spec.packs.allow-only: false` |
| A pack named in `DRISHTI_PACKS` did not load | allow-only drops it (the log names it), or its module is off | as above, or switch the module on |
| A Home card or chip is missing | its function is off for the person | switch the function or its module on |
| World shows no companies, filings or funds | the capitalmarkets module is off | switch it on (Admin → Modules, restart) |
| The console still shows the full product | it has not reached the server yet, or the server runs no lens | check `/public/about`; the console follows within `backend.check_seconds` |
| A BI report is missing | its dataset or model is not in `bi.datasets` / `bi.models` | add it there |

## 14. Formerly editions: upgrading

Lenses were formerly called **editions**, with their files in `config/editions/<name>.yaml`; that folder is gone. To upgrade a site:

1. Move a site's own file to `config/lenses/<module>/<name>.yaml` (a lens of no module: `config/lenses/<name>.yaml`).
2. The folder setting is now `drishti.lens-dir` (`DRISHTI_LENS_DIR`; the console's `lens.dir`); rename an old `DRISHTI_EDITIONS_DIR`.
3. Optionally name the packs by module in `packs.enabled`; a plain list is still read.
4. For a lens people open with `drishti.lenses`, add a registry entry in `config/access/lenses.yaml` (section 11).

`drishti.edition`, `drishti.edition-spec.*`, the console's `edition:` block and `drishti.lenses` keep their names, and a lens file's first
key is still `edition:`.

## 15. The design

Drishti stays one product with one code base; a lens is a named bundle of settings. The site's configuration wins over the lens file key by
key, so a bank adjusts one item without forking. Switching is a configuration change (and, for the instance's lens, a restart), never a
code change.

| | The instance's lens | A person's lens |
|---|---|---|
| Set by | configuration and a restart (`drishti.edition`) | a link, per person ("Open Drishti Risk") |
| Scope | the whole instance | one person's session (kept in their settings) |
| Packs | only the lens's (`allow-only`) | all stay loaded |
| Controlled by | the administrator | the site's `drishti.lenses`, the registry (modules on); grants per role through the access model |
| Secures anything? | refuses packs; hides commands and pages | **no: it hides, it does not secure** |

### What a lens changes

| Area | How |
|---|---|
| **Packs** | one default list, `config/packs/default-packs.yaml` (copied into the `drishti-packs` jar at build, read by the console from disk); `drishti.packs.enabled` defaults to the word `default`: the lens's packs, else that list; `allow-only` refuses other packs at start, in Admin → Packs, Deploy archive, the registry and the folder watcher (`403 DRS-1017`) |
| **Commands** | a command allow-list, separate from kind access (`EditionCommands`): hidden from the command line, `FUNC`, the type-ahead, the packs' examples, the entity page and search (`404 DRS-4023`); the engines still read every kind. The reserved and console words switch on and off, with their pages |
| **Menus** | `drishti-console/config/sitemap.yaml`: every menu item and footer link with an id and `requires:` (`admin`, `bi`, `designer`, `edition`, `feature:<name>`, `word:<WORD>`, `admin-page:<id>`, `pack:<name>`, `cmd:<WORD>`, `route:<path>`, `module:<id>`); the lens lists what it shows per menu; with more than six menus the labels fold to icons on narrow screens |
| **Features** | `config/lenses/features.yaml` maps each feature a lens names to the flags of each side (console `features.build`, `forms`, `datamap`, `workspaces`, `monitors`, `alerts`, `lakes`, `reports`, `collaboration`, ...) |
| **Home** | `home.sections` switches the product's sections off; the landings (section 4); the registry's cards and chips, each shown while its function is on |
| **Help and Knowledge** | sections and Knowledge pages follow the lens (Knowledge pages tagged by pack in `help.yaml`), pack guides follow its packs, unlisted slugs refused (`help.unlisted-slugs: refuse`) |
| **Admin** | tabs, menu items and routes follow `admin.pages`; a hidden one answers 404 |
| **BI** | reduced to the lens's datasets and models (`EditionBi`, applied in `BiService`): a report or board follows its dataset or model, in the catalogue and by id |
| **Branding** | the console takes the server's product and tagline from its about answer (the console's own `ui.product` still wins); `ui.logo`, `ui.favicon`, `ui.apple_touch_icon`; a generated `/manifest.webmanifest`; `{product}` in Help titles and summaries |
| **Static files** | a module's scripts and styles are served to a person in a lens only when that lens uses the module (the World lens gets no banking file) |

### How each side reads it

- **Server.** `EditionEnvironmentPostProcessor` (`drishti-packs`, right after the configuration files are read, before the packs'
  post-processor) loads the file as one property source placed **just above the product's own `application.yaml` and below everything the
  site sets** (application.local.yaml, the packs overlay, environment variables, arguments). It holds the file's keys under
  `drishti.edition-spec.*` and the settings the lens presets: `drishti.packs.enabled`, `drishti.branding.product`/`tagline`, each feature's
  flags (through `features.yaml`), the raw `server:` block. A preset of a setting the product writes as `${VARIABLE:default}` keeps the
  variable, so `DRISHTI_PACKS` set still wins. `Editions.of(env)` gives the `Edition` record every server class asks; blank gives
  `Edition.NONE`, which answers "allowed" to every question. `Editions.file` finds `<lens>.yaml` in the folder or one of its module folders;
  a missing file or a name that is not a plain word stops the start with what to do. `Editions.lenses` reads each name of `drishti.lenses`
  from its file alone; `EditionContext` applies a person's lens per request (`X-Drishti-Lens`).
- **Registry.** `drishti-registry` reads every jar's `META-INF/drishti/lenses.yaml` and the site's `config/access/lenses.yaml`; a lens is
  offered while its modules are on, and `GET /api/v1/modules` tells each person the lenses open to them with their modules, functions,
  cards and chips, and the functions off.
- **Console.** The console learns the instance's lens, the lenses offered, and the product name and tagline from `/public/about`, at start
  and on every check of `core/backend_watch.py`; it reads the same file and puts its presets as a settings layer between `application.yaml`
  and the site's own; the site's `edition:` block overrides the file key by key. `core/edition.py` answers every "is this shown?" question
  (with `core/modules.py` for modules and functions); `core/edition_gate.py` answers 404 for a hidden page; `routes/edition_routes.py`
  draws the landings and opens, switches and leaves a person's lens. Each landing's code is its module's console plugin
  (`drishti-console/plugins/banking/`, `drishti-console/plugins/economy/`).

### Decisions (product owner)

1. Lenses are lenses of modules: a lens is offered only while its modules are on, and a card or chip only while its function is.
2. Branding: Drishti's name and eye kept; the product name is the lens's title; white-labelling through site configuration.
3. BI is reduced to the lens's datasets and models.
4. Commands are hidden, never renamed.
5. Fully clustered: one value on the servers; the console learns it from the about answer.
6. Each shipped lens has three Home landings, drawn from the engines' and packs' own answers; a figure the data cannot give is left out or
   labelled, never invented.
7. With no instance lens Drishti is the full product of the modules that are on.
8. A person's lens hides and does not secure; who may open a lens is the access model's.
9. The default lenses offered are `risk,world,market,trading`.
10. The IMF and Yahoo data in World are shown inside the bank; outside it needs a decision.

### Tests

| Test | What it proves |
|---|---|
| `EditionsTest` (drishti-packs) | the default list is the one file; the layer's place; presets; a site variable still wins; a missing file stops the start; lenses; packs by module |
| `EditionRiskServerTest`, `EditionWorldServerTest`, `EditionMarketServerTest`, `EditionTradingServerTest` | a server running each lens: About and branding, its packs, `DRS-1017` for another, `FUNC` and the type-ahead, `DRS-4023` typed and by page, the packs' examples, BI datasets and reports |
| `EditionNoneAndLensServerTest`, `EditionTwoLensesServerTest`, `SettingsTest` | no instance lens: the full product; a lens header focuses one request only; several lenses offered, set and read back through `/api/v1/me/settings`, switching, others unaffected; `tradingView` validated |
| `*RiskEditionTest` (seven classes), `EditionShardClusterTest` | the VaR and exposure suites again under `risk`; two Drishti*net members with `risk`: no Admin → Net warning, sharded VaR equal to one server's |
| `PublicSectorLensTest`, `EconomyModuleOffTest`, `BankingModuleOffTest`, `CapitalMarketsModuleOffTest` | lenses offered or not with their modules; functions, packs and cards gone with a module |
| `test_edition.py` (console) | the full product unchanged; every surface under each lens; the landings and the remembered choice; the role hooks; "my" scoping; the lens for one person and not another; no lens with an instance lens; logo, favicon, manifest |
| `test_market_landing.py`, `test_trading_landing.py` | Market's figures and quality checks; Trading's mapping, figures, scoping and the new-trade check |
| `test_edition_browser.py`, `test_edition_world_browser.py`, `test_edition_market_browser.py`, `test_edition_trading_browser.py`, `test_lens_menus_browser.py`, `test_public_sector_browser.py` | the landings and menus in a browser, at phone and desktop widths, in every theme |
| `test_modules_off.py` | a lens without a module gets none of its files, functions, cards or chips |
