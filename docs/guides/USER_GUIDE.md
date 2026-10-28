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
# User guide

This guide explains every part of the Drishti console, one feature at a time. Each section says what
the feature is for, then walks through an example: the exact text to type and what you should see.

If Drishti is not running yet, start with [GETTING_STARTED.md](GETTING_STARTED.md) (ten minutes) or
[GETTING_STARTED.md](GETTING_STARTED.md) (every step explained).

The examples use the seven default packs (`core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`, `world-economy`, `news-and-markets`)
on the demo lakes of the quick start (`drishti.py lake demo`, business date 2026-10-16; [GETTING_STARTED.md](GETTING_STARTED.md)), so ids such as
`MX-20000001`, `1000001` and `NS-SUMMIT-NY` exist and the counts are those of 15,000 trades from ENDUR, CALYPSO and MUREX. If your administrator loaded other packs, the
**Examples** list on the terminal page shows ids that work for you, and every pack's guide (*Help → Help
centre → Domain packs*) has a *Finding things* section with the same commands for its own kinds.

**Contents**

| Section | What you learn |
|---|---|
| [The pages at a glance](#the-pages-at-a-glance) | Every page and its address |
| [The top bar](#the-top-bar) | The menus and the round tools on the right |
| [The command line](#the-command-line) | Opening an entity, suggestions, what an error means |
| [Pick lists](#pick-lists-when-a-command-names-several-entities) | `TRD MX-200000`, `CPTY north`, `TRD PRODUCT_TYPE=FX_QUANTO`, `TRD` |
| [Tables: paging and the keyboard](#tables-sorting-filtering-paging-and-the-keyboard) | The pager, ▲ ▼, and the keys that walk a table |
| [Controls on a screen](#controls-on-a-screen-choose-what-a-panel-shows) | Dropdowns, date ranges and search that a screen offers: pick a leg or a period and only the panels that depend on it move; the choice is in the address |
| [A deep trade](#a-deep-trade-legs-cashflows-and-fixings) | A tab per leg; each leg's terms and cashflows, and a floating cashflow that opens to its fixings; live, and on a phone |
| [Filter a table like Excel](#filter-a-table-like-excel) | A funnel in every column heading: tick values or filter by a condition, in the address, with `Alt+Shift+X` to clear |
| [Reading a view](#reading-a-view) | Title, strip, panels, links, provenance |
| [About this page](#about-this-page) | `?`: what the page is, what each number means, where the data came from, why it looks like this |
| [Layout mode](#layout-mode-arrange-a-view-your-way) | `Alt+L`: drag, resize and hide panels for yourself; promote your layout to the Sutra |
| [Screens that respond](#screens-that-respond-clicks-refresh-editing-and-your-own-page) | A click that opens a panel, refresh and freshness, Pause all, editing a record (drafts), a screen you arrange, *Your page* |
| [Zoom a panel](#zoom-a-panel) | `Z`: one panel fills the view area; restore with `Esc`; shareable as `#zoom=<panel>` |
| [The Pivot tab](#the-pivot-tab-slice-a-table-your-way) | Rows, columns, values and filters over a table or a search's results, by drag or by key; totals, drill-down, chart, export |
| [Keyboard](#keyboard) | Every key in one table |
| [Live updates](#live-updates), [Business dates](#business-dates-live-or-a-day-in-the-past), [Compare](#compare-what-changed) | Ticking values, past dates, what changed |
| [Search by value](#search-by-value) | `where` searches, sorting and limits |
| [Calc: Python on a view](#altc--calc-python-on-a-view) | `Alt+C`: Python in your browser on what you are looking at |
| [Export, print and share](#export-print-and-share) | CSV, JSON Lines, JSON, PDF and links |
| [Share a view with a note](#share-a-view-with-a-note), [Your inbox](#your-inbox) | Send a view to colleagues; what they sent you |
| [Monitors](#monitors), [Alerts](#alerts), [Workspaces](#workspaces) | Watching many entities at once |
| [Your settings](#your-settings), [Themes](#themes), [Domain packs](#domain-packs-choosing-what-you-see) | Making the console yours |
| [The workbench](#the-workbench-changing-how-a-screen-looks) | Changing how a screen looks |
| [Administration](#administration) | Users, roles, packs, audit, health, caches |

## The pages at a glance

| Page | Address | What it is for |
|---|---|---|
| Landing (Home) | `/` | The start page, with a button to open the terminal; signed in, the fact of the day and quick cards ([Home: the fact of the day](#home-the-fact-of-the-day)) |
| Terminal | `/t` | The command line, your pinned entities and example commands |
| View | `/v/<kind>/<id>` | One entity, laid out in full (for example `/v/trade/MX-20000001`) |
| Pick list and search results | `/s?q=…` | Entities a command names (`TRD MX-200000`) or a `where` search finds |
| Forms | `/forms`, `/form/<pack>` | Query by form: the forms home, and a pack's search form ([Search with a form](#search-with-a-form-form-pack)) |
| Pack overview | `/p/<pack>` | What a pack holds (type its code alone: `MKT <GO>`) |
| Compare | `/compare/<kind>/<id>` | What changed in an entity between two business dates |
| Impact | `/impact/<kind>/<id>` | What depends on an entity (F8) |
| Monitors | `/m` | Live watchlists |
| Alerts | `/alerts` | Your alerts on numbers, your alert rules and recent alerts (also **Reports**, *My alerts*) |
| Workspaces | `/w` | Several live views on one screen |
| Inbox | `/inbox` | Views and comments sent to you ([Your inbox](#your-inbox)) |
| Reports | `/reports` | Saved searches delivered as CSV on a schedule, and your subscriptions ([Scheduled reports](#scheduled-reports), [Export, and subscriptions](SCHEDULED_REPORTS.md#export-and-subscriptions)) |
| BI | `/bi`, `/bi/catalogue`, `/bi/w/me`, `/bi/r/<id>`, `/bi/models`, `/bi/datasets`, `/bi/designer`, `/bi/explore`, `/bi/transform` | Rūpaka business intelligence, for people with the `bi` power: reports (a board is a report page; `/bi/b/<id>` opens it), BI Studio's designers, Analyse and the Transform editor ([BI_GUIDE.md](BI_GUIDE.md)) |
| Python console | `/pyconsole` | Your own Python in the browser on a masked extract of a BI model; promote it to the server (BI rail, *Python console*; [ANALYSE.md](ANALYSE.md#16-the-browser-python-console)) |
| Build | `/build`, `/build/new`, `/build/pack/new`, `/build/reviews` | Design layouts (Sutras) in the workbench, make a pack from schemas, approve proposed Sutras |
| My account | `/account` | Your profile, settings and password |
| Servers | `/servers` | The Drishti servers this console may talk to ([Working with several servers](#working-with-several-servers)) |
| Admin | `/admin/users`, `/admin/roles`, `/admin/audit`, `/admin/packs`, `/admin/connectors`, `/admin/artifacts`, `/admin/loads`, `/admin/lakes`, `/admin/collab`, `/admin/embedding`, `/admin/net`, `/admin/health`, `/admin/caches` (and `/admin/ai`, `/admin/bi`, `/admin/tokens`, `/admin/access` where those features are on) | Users, roles, audit log, packs, connectors, installed artifacts, data loads, Delta lakes, collaboration moderation, embedding, Drishti\*net, health and caches (admins only) |
| Help | `/help` | Tutorials, guides and every reference |
| About | `/about` | The product page: features, measured performance, competitive analysis, technology, licence (system details for admins: `/admin/health`) |

## Home: the fact of the day

Signed in, the landing page (Home) puts **Did you know** beside the headline *See what your data hasn't told you yet.*: one
surprising fact about your data on the business date you are on, worked out from the data itself. The tile shows:

- the domain and the date (**Fact of the day · Counterparty credit**, 2026-10-27);
- one sentence, its key figure highlighted, and a small picture (bars, or a filled bar for a share of a whole);
- **Why does that happen?**: opens a short explanation, with **Read more in Knowledge** (the Knowledge page section that explains it);
- **Open …**: the command that shows it (`EXP NS-NORTHPLAINS-NY`, `VAR NODE MGMT~BOOK-COMM-1`, `FUND PONAX`), and **WHAT …**, the
  term's glossary entry;
- **Another fact**: the next fact that holds today (it wraps round).

Every figure is computed from the server's answers as you, so you see only facts about data you may see, and only for packs you have
switched on. Nothing is written into a fact: a fact is shown only on a date its condition holds (a book over its VaR limit inside a
desk within its own; collateral that more than doubles a netting set's expected exposure; a counterparty whose peak PFE is over five
times its trades' VaR; the largest US funds owning a tenth or more of the company they hold most of; two funds of different managers
tracking one index and 80% or more the same; a fund whose holdings weigh over 120% of its net assets; a desk that adds VaR on paper but
whose removal would raise the bank's). The first fact changes with the business date; everyone on the same date sees the same one first.
On the demo data of 2026-10-27 the facts read:

| Domain | The fact | Opens |
|---|---|---|
| Market risk | BOOK-COMM-1 is at **259%** of its VaR limit, while its desk, Commodities, is within its own | `VAR NODE MGMT~BOOK-COMM-1` |
| Market risk | Take the **Commodities** desk out and the bank's VaR would rise by 10.7m, though the desk adds 3.0m to it on paper | `VAR DESK DESK-COMM` |
| Counterparty credit | Collateral made **NS-NORTHPLAINS-NY** more risky: EE at 2M is 129.7m with it and 55.6m without (+133%) | `EXP NS-NORTHPLAINS-NY` |
| Counterparty credit | Northern Plains Energy Canada's trades have a VaR of 37.4m but a peak PFE of **508.1m** (13.6× as large) | `EXP CP-NORTHPLAINS` |
| Funds and companies | 87 of the largest US funds hold Nvidia and own **21.19%** of its shares | `HELD NVDA` |
| Funds and companies | VOO and IVV, run by two different managers, are **99.3%** the same portfolio | `FUND VOO` |
| Funds and companies | PIMCO Income Fund (PONAX) holds investments weighing **150.01%** of its net assets | `FUND PONAX` |

The tile draws at once as a placeholder and fills when the facts are ready (the first visit of a business date computes them, a few
seconds at most on the demo data; later visits that day read them back). When no fact holds, or the server cannot be reached, the tile
is left out and the headline stands alone. Under the headline, **quick cards** open *Market risk* (`VAR BANK`), *Exposure* (`EXP BANK`),
*Funds and ETFs* (`FUND SPY`) and *Data map* (`DATAMAP`); a card whose pack is off is not shown. An administrator turns the tile off
with `ui.facts.enabled: false` (`DRISHTI_HOME_FACTS=false`) and sets the cards under `ui.home_cards` in the console's
`application.yaml`; a pack author adds facts as the [pack developer guide](PACK_DEVELOPER_GUIDE.md#facts-for-home) describes.

## The top bar

Every page has the same top bar, on one row: the terminal, the views, BI, Build, Admin, Help and the landing page when you are signed in, and the public pages (signed out) in the same height, logo and
wordmark (set in Cinzel; the slogan is in the footer, which also carries Help, About, the competitive analysis and the release notes).

The **Drishti name and logo** at its left end always lead to the landing page, also when you are signed in. The
landing page then shows **← Back to …** (the view, Build, Admin or Help page you came from; `Alt+B`), so one click
takes you back where you were; *Open the terminal* goes to the terminal instead.

```text
◉ Drishti  > TRD MX-20000001, CPTY north …  [2026-09-30]   Views ▾  BI ▾  Build ▾  Admin ▾  Help ▾   [bell] [box 12] [palette] (Live) (A) Ash ▾
```

**Beside the brand** (click it to go back to `/`) are the [command line](#the-command-line), which takes the
free space, and the [business date](#business-dates-live-or-a-day-in-the-past) box.
Below 1100 pixels wide they move to a line of their own.

**Then the menus**: **Views**, **Build** and **Help** for everyone, **BI** where you hold the `bi` power, **Admin** only for administrators. Below 1280 pixels they show as icons only. Each opens a wide panel
(a *mega menu*) in which every entry has an icon and one line saying what it does. The menu holding the page
you are on is highlighted.

| Menu | Column | Entries |
|---|---|---|
| **Views** | Look up | *Terminal* (`/t`), *Search & pick lists* (`/s`), *Forms* (`/forms`: the [forms home](QUERY_BY_FORM.md#the-forms-home), query by form), *Functions* (`/func`, also `FUNC <GO>`: every command you can use, what it does and an example; administrators switch commands on or off there, [COMMANDS.md](../admin/COMMANDS.md)), *Data map* (`/datamap`, also `DATAMAP <GO>`: every kind of data, how it joins and how much there is, [The data map](#the-data-map)), *Reports* (`/reports`: every scheduled report and subscription, with a box to find one, and links to your alerts and the BI reports) |
| | Watch | *Workspaces* (`/w`), *Monitors* (`/m`), *Alerts* (`/alerts`) |
| **BI** (people with the `bi` power) | Rupaka: business intelligence | *BI home* (`/bi`), *My workspace* (`/bi/w/me`), *Catalogue* (`/bi/catalogue`), then *Recent*: the three items you opened last. Administrators with `bi-admin` also see *BI settings* ([BI_GUIDE.md](BI_GUIDE.md)) |
| **Build** | Create | *New screen* (`/build/new`), *New pack* (`/build/pack/new`: schema files and/or JSON Lines in, a pack to download or deploy out; [Schema to pack](SCHEMA_TO_PACK.md)), *My designs* (`/build`), *Examples* |
| | Govern | *Reviews* (`/build/reviews`, with the number waiting) |
| | Learn | *Knowledge* (`/what`, also `WHAT <GO>`: [Knowledge](#knowledge-what-the-words-mean)), *Tutorials*, *Schema to pack*, *Screen designer guide*, *Sutra developer guide*, *Panel kinds*, *Rachana reference*, *Pack developer guide* |
| **Admin** (admins only) | People & access | *Users*, *Roles*, *Audit log* |
| | Operations | *Packs*, *Connectors*, *Artifacts*, *Commands* (opens Functions with the switches), *Data loads*, *Lakes*, *Collaboration*, *Embedding*, *Net* (Drishti\*net: [NET.md](../admin/NET.md)), *Health*, *Caches* |
| **Help** | Learn | *Help centre* (`/help`, also `F1`), *Knowledge* (`WHAT <GO>`), *Install and run*, *About* |

**BI and the `BI` keyword (alias `RUPAKA`).** Where your roles hold the `bi` power, a **BI** menu sits between **Views** and **Forms**, and the reserved command
word `BI` goes there from the command line, in any case: `BI <GO>` the BI home, `BI WORKSPACE <GO>` your personal workspace, `BI CATALOGUE <GO>` the
catalogue, `BI SETTINGS <GO>` the BI settings (administrators with `bi-admin`), `BI <report-or-board> <GO>` a published item by id or title (a **report opens drawn**, with its slicers, drill-down and drill-through; type-ahead completes after `BI `; `RUPAKA` still works as an alias). Without the power the menu is not there and `BI <GO>`
says what is missing. No pack can define a mnemonic named `BI`, `RUPAKA`, `FORM`, `FUNC`, `WHAT` or `DATAMAP`. The BI page, its keys (`/`, `g h`, `g c`, `g w`, `?`) and what it can and cannot do yet:
[BI_GUIDE.md](BI_GUIDE.md). **Using a report:** click a bar and the page filters to it, the buttons in a visual's header drill down, drill through to another page, show the rows behind it or open a trade in the terminal, and a slicer filters
what it can reach ([BI_GUIDE.md](BI_GUIDE.md) section 14); **building one:** *Designer* in the BI rail, drag fields onto the canvas in BI Studio, with its Explorer, tabs and Problems and Query dock ([BI_GUIDE.md](BI_GUIDE.md) sections 15 and 26, [tutorial 10](../tutorials/10-first-bi-report.md)); the YAML reference is [REPORTS.md](REPORTS.md). The BI rail also opens *Analyse*, *Transforms* (Python data prep step by step) and the *Python console*.

**Since 2.6.0, in a report:** a **parameter slicer** may switch *which* field or measure a visual draws (a field parameter: the chart breaks down
by desk, then by portfolio; it shows MTM, then notional), and the choice is yours alone, as any slicer's ([REPORTS.md section 21](REPORTS.md#21-field-parameters-in-a-well-260)).
A report may also have **statement pages**: paper, page by page, with a header and footer, page numbers and a period, downloaded as PDF or
Excel made as you (your masks and row rules), and sent by a subscription as one statement per recipient; the reference is
[STATEMENTS.md](STATEMENTS.md), the walk-through [tutorial 20](../tutorials/20-statements-and-field-parameters.md).

**On the right:** round tools, from left to right.

| Tool | Looks like | What it does |
|---|---|---|
| Live | the round button right of the theme switcher | Your choice and this page's stream in one: green while live (glowing while ticks arrive, an amber ring while it reconnects, steady on a page without a stream), red with pause bars while paused or on a picked date. Hover to read both, for example *Live updates on — click to pause · Live, built 1.92 ms*. A click switches ([Business dates](#business-dates-live-or-a-day-in-the-past)) |
| Alerts | a bell | Opens `/alerts`. A number on the bell counts alerts you have not seen ([Alerts](#alerts)), and BI runs that finished, failed or were started again (`BI RUNS <GO>` lists them: [BI_RUNS_GUIDE.md](BI_RUNS_GUIDE.md)) |
| Packs | a box with a number | Appears when you may use more than one pack. The number is how many you have switched on; hover to read their names ([Domain packs](#domain-packs-choosing-what-you-see)) |
| Theme | a palette | Ten themes ([Themes](#themes)) |
| You | a round avatar with your initial and name | A menu: your name, desk and roles, a clock in your time zone (`NY` by default), **My account**, **Settings**, and **Sign out** when sign-in is on (when it is off, the menu says *Sign-in is off on this server* and links to how to turn it on) |

Worked example: find the audit log without typing an address.

1. Click **Admin** in the top bar. A panel opens with two columns, *People & access* and *Operations* (each with a line saying what the entry does).
2. Click **Audit log** (*Every sign-in and change, by whom*). The audit page opens and **Admin** stays
   highlighted.
3. Press `Esc`, or click anywhere outside, to close a menu without choosing.

If you do not see **Admin**, your account has no admin role; ask an administrator.

## The command line

The command line is the box beside the brand in the top bar. Press `/` anywhere to jump to it.

### The form of a command

```text
<MNEMONIC> <ID> <GO>
```

- The **mnemonic** names a kind of entity: `TRD` is a trade, `NSET` a netting set, `ECON` an economy.
- The **id** names one entity of that kind: `MX-20000001`.
- `<GO>` means "press Enter".

Examples:

```text
TRD MX-20000001      an interest rate swap (MUREX)
TRD 1000001          a commodity swap (ENDUR)
TRD CLY-3000025      an FX option (CALYPSO)
TRD CLY-3000157      a government bond (CALYPSO)
NSET NS-SUMMIT-NY    a netting set
CRV CRV-USD-OIS      the USD SOFR discount curve
CPTY CP-NORTHBRIDGE  a counterparty
ECON ECON-USA        an economy (world-economy)
```

**A bare id** works when its shape tells Drishti the kind. Type `MX-20000001` and press Enter: it opens
the same trade as `TRD MX-20000001`.

**Case** never matters: `trd mx-20000001` opens `MX-20000001`.

**One match opens; several give a pick list.** If what you typed names exactly one entity, it opens. If it
names several (`TRD MX-200000`, the start of an id), you get a table to pick from. See
[Pick lists](#pick-lists-when-a-command-names-several-entities).

**A range after the id shows the entity over time.** `TRD 1000015 30` opens the trade's
[History](#history-an-entity-over-time): one row per business day it has data, the last 30 such days.

```text
TRD 1000015 30            the last 30 business days on which the trade has data
EQ EQ-CSCA 3M             the last 3 months (also 30D, 2W, 1Y): every business day in the span that has data
FX FX-AUDUSD YTD          since 1 January (also MTD, QTD)
CRV CRV-USD-SOFR FROM 2026-09-01 TO 2026-09-30
TRD 1000015 30 DIFF       only what changed, day by day
```

The range is always the last words, never the first word after the mnemonic: `TRD 30` still opens trade `30`, and an id that really
contains the words (`BOND UST 2030`) opens as before. A search (`TRD desk=rates 30`, `TRD MX-2* 30`) stays a search. A prefix that names
several trades (`TRD MX-2000 30`) gives the pick list, and each of its rows opens that trade's history. Aliases keep the range:
`MYBOOK 30` is `BOOK BOOK-RATES-1 30`.

Mnemonics come from the packs that are switched on. Every command, with what it does and an example, is in
[COMMAND_CATALOG.md](COMMAND_CATALOG.md); the packs themselves are in [PACKS.md](PACKS.md#the-packs-that-ship). The most used ones, from the seven default packs:

| Mnemonic | Kind | Example |
|---|---|---|
| `TRD` | trade | `TRD MX-20000001` |
| `CPTY` | counterparty | `CPTY CP-NORTHBRIDGE` |
| `BOOK` | book | `BOOK BOOK-RATES-1` |
| `NSET` | netting set | `NSET NS-SUMMIT-NY` |
| `LIM` | credit limit | `LIM LIM-SUMMIT` |
| `CRV` | interest rate curve | `CRV CRV-USD-OIS` |
| `FXV` | FX volatility surface | `FXV FXV-EURUSD` |
| `VAR` | VaR / expected shortfall | `VAR VAR-RATES` |
| `TVAL` | trade valuation (MTM, P&L, DV01) | `TVAL TVAL-MX-20000001` |
| `ECON` | economy | `ECON ECON-USA` |

**VaR in another currency (2.9.4).** The VaR explorer (`VAR BANK`, `VAR DESK DESK-RATES`) has a **Reporting currency** select at the
right end of its status line: the configured currencies (USD, EUR, CAD, JPY, GBP by default) and **More…** for any other currency with
scenario FX on the date. Every figure of the page follows it (the tiles say `VaR 99% · 1 day · EUR`), the choice stays while you work in
that browser tab, and it goes into the page's link (`&ccy=EUR`), so a link you send opens in the same currency. Type it too: any VAR
command ends with `IN <currency>`, `VAR DESK DESK-RATES IN EUR <GO>`. The tab **In other currencies** (loaded when you open it) shows the
scope's VaR and ES in each configured currency side by side, with each one's VaR in the page's currency at today's rate; click a row to
move the page to it. A currency's VaR is never the USD VaR at a rate: the currency move on each scenario day is part of its risk
([VAR_GUIDE.md section 15.11](VAR_GUIDE.md#1511-reporting-currencies-built-ahead-per-trade-currency-totals-and-the-picker-as-built-294)).

**Construct your basket.** On the VaR page, **◫ Construct your basket** (or `Alt+B`) opens the basket tray across its top: the VaR
of any mix of portfolios, trades and counterparties; type for hints, **+ / −** each part, the selection's VaR live, a pre-deal what-if,
**Save basket and open VaR**. It is on the VaR page only (2.9.4: no longer in the top bar); what it holds stays in your browser, so it is
there when you come back ([VAR_GUIDE.md section 3.5](VAR_GUIDE.md#35-construct-your-basket)).

**One year or two; 99% or 95% (2.9.4).** The page's labels say the confidence the figures are at (*VaR 95% · 1 day* when the
server is set to 95%), and *How this number was built* says the scenario window (*newest 250 of 509 scenarios*);
`drishti.py var show BANK --window 250 --confidence 0.95` compares on the command line, never official.

**The top of the tree (2.9.4).** The VaR tree's top node shows the hierarchy data's name (*Drishti Bank* in the demo); an administrator
can set any name with `drishti.var.root-name` (or one per hierarchy with `drishti.var.root-names`), and the page, the command line and new
captures follow it ([CONFIGURATION.md](../admin/CONFIGURATION.md)).

**Any form, any grain (2.9.4).** *How this number was built* says, per source system, whether its figures came as scenario PVs or P&L
vectors, per trade or per position. A position its system sends whole answers `POS:<id>`; its trades have no VaR of their own on that
date (the trade page says so and links the position).

**Intraday (2.9.4).** When pricing systems re-send trades during the day (amended, new or cancelled), the VaR page applies them as they
land and shows an **intraday** badge in its status line (*intraday · 5 trades · 2026-09-30 11:00*): its figures are the morning run with
those trades updated; point at the badge for the batches. The official end-of-day records stay the morning run's. `drishti.py var
intraday` lists what each date holds; the updates may come as files or from a Kafka topic (the `pv-updates` connector, off by default:
an administrator's **Apply now** on its Admin page applies the waiting window at once) ([VAR_GUIDE.md section 8.4](VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)).

**Trade currency.** Every trade page's strip says **Trade currency**: the currency the trade's PVs come in (`EUR · as priced`) when the
VaR engine holds it as scenario PVs, else the trade's own currency (`USD · as booked`). Its **Risk** group adds where it comes from and
the trade's **Base PV** in it. (The strip holds eight figures: the source system moved from the strip to the deal ticket, F2 DES.) The
VaR explorer's contributions and worst scenarios, a position's trades and the trade tables of the counterparty, portfolio, legal
entity and index pages list it too.

### Reserved words

Six words are keywords of the console itself, not mnemonics of a pack, and no pack may define a mnemonic with
their name. They work in any case, and each has type-ahead.

| Type | You get | More |
|---|---|---|
| `WHAT <GO>` | Knowledge, the home of the [Knowledge pages](#knowledge-what-the-words-mean): what the words of your data mean and how the data connects | `WHAT PFE <GO>`, `WHAT netting set <GO>`, `WHAT value at risk <GO>` open the term's glossary entry (its long name or a synonym works too, any case); other words (`WHAT collateral posted <GO>`) search the Knowledge pages. The type-ahead lists the terms as you type |
| `FUNC <GO>` | Functions, `/func`: every command you can use, by pack, with what it does and an example ([COMMANDS.md](../admin/COMMANDS.md)) | `FUNC exposure <GO>` opens it filtered (every word must appear in a command's code, title, description or its pack); `FUNC trading` lists one pack's commands. Click a command to open it with its example, a pack to open its search form |
| `DATAMAP <GO>` | the [data map](#the-data-map), `/datamap`: every kind of data your packs hold, how much of it there is, and how the kinds join | `DATAMAP trade <GO>` opens it on one kind (its name, mnemonic or label: `DATAMAP CPTY`), `DATAMAP organisation` or `DATAMAP counterparty-risk` on one domain; other words fill its Find box |
| `FORM <GO>` | the forms home, `/forms` | `FORM trading <GO>` opens the trading pack's search form, `FORM trading trade <GO>` its form for trades ([Search with a form](#search-with-a-form-form-pack)) |
| `BI <GO>` | the BI home, `/bi` (only with the `bi` power; otherwise the terminal says what is missing) | `BI WORKSPACE`, `BI CATALOGUE`, `BI SETTINGS`, `BI <report-or-board>` ([BI_GUIDE.md](BI_GUIDE.md)) |
| `RUPAKA <GO>` | the same as `BI`, the older name | everything that works after `BI` works after `RUPAKA` |

A pack's code typed alone (`MKT <GO>`) opens that pack's overview ([A pack at a glance](#a-pack-at-a-glance)). Anything else
that is not a command but reads as words (`live trades over 5m`) is offered as a question
([Asking in plain words](#asking-in-plain-words)).

### Knowledge: what the words mean

*See what your data hasn't told you yet.* The **Knowledge** section at the top of the help centre has one page per domain:
[Market risk](../knowledge/market-risk.md), [Counterparty credit risk](../knowledge/counterparty-credit-risk.md),
[Trades and products](../knowledge/trades-and-products.md), [Entities and ownership](../knowledge/entities-and-ownership.md),
[Market data](../knowledge/market-data.md), [Data and contracts](../knowledge/data-and-contracts.md),
[Economies](../knowledge/economies.md) and [Companies, funds and ETFs](../knowledge/companies-funds-and-etfs.md). Each says what the domain is in plain words, gives a glossary with the command that shows each
term, draws how the kinds connect (with their id conventions), works an example through on the demo data, lists ready-made questions to
ask your data, and clears up the common confusions (VaR against ES, why PFE does not add up, why posted collateral raises exposure).
The [Knowledge home](../knowledge/README.md) lists every term A to Z.

Reach it three ways: `WHAT <GO>` (or `WHAT <term> <GO>` for one entry), **Build → Learn → Knowledge** or **Help → Knowledge**, and
the **What does this mean?** link on a data page: the VaR explorer, `VRES` and `DESK` open Market risk; `CPTY`, `NSET`, `CSA` and the
exposure explorer open Counterparty credit risk. F1 and **?** still open the page's help as before.

### The data map

**Views → Data map** (`/datamap`, or `DATAMAP <GO>`) shows every kind of data your packs hold, how much of it there is on the business
date, and how the kinds join. Nothing on it is drawn by hand; it is inferred each time from what is loaded:

- **Domains and kinds** come from the packs switched on for you: each pack's kinds, titled by their data contracts
  (`config/packs/<pack>/contracts/<kind>.contract.yaml`). A kind whose contract says `class: computed` (VaR and ES, EE and PFE, the
  what-ifs) is drawn **dashed**: it is computed on demand and never stored, so it has no count.
- **Joins** are what the contracts declare: their `links:`, their fields' `link:` and the fields a pack's `graph.fields` names (the
  trade's `COUNTERPARTY` → counterparty, `PORTFOLIO` → book, `TRADER_ID` → trader; the P&L vector's and the exposure paths' `TRADE_ID`
  → trade). Each join is **checked against the data**: the server reads the field over the kind's rows (as columns where the lake keeps
  them so, else from the documents; at most 20,000 rows a kind, `drishti.datamap.max-rows`) and counts how many of the ids name an
  entity that exists, so a join reads *15,000 of 15,000 ids match (100%)*. A join under 95% is a **finding**, listed above the map and
  drawn in the warning colour: the contract promises a link the data does not keep.
- **Counts** are read from the lake when you open the page (the server keeps them for the business date for 15 minutes,
  `drishti.datamap.ttl`). The first visit of a day may say *Counting and checking the data*: the map fills in as the server goes.
- **Positions** come from an automatic layout: each domain a box, its kinds in a grid, the most joined first.

Two views share one selection, the domain chips and the **Find** box (a kind, a mnemonic or a field: `LEI`, `PORTFOLIO`); the view
and the selection are in the address, so a link opens the same picture.

- **Map** (the default): click a kind to light its joins and dim the rest. The side panel shows its join fields with their coverage,
  the kinds that join to it, the commands that open it (its example from Functions, its search form) and its *Data contract*, its pack's
  guide and its *WHAT* entry. Ctrl-click (Cmd-click) a kind to open it directly; Esc clears the selection. On a phone the map scrolls
  inside its box.
- **Cards**: one card per domain with its largest counts. Open one for a table of its kinds with their counts, joins (→ to, ← from,
  with the field) and the command that opens each, **Comes from** (the pack README's sources) and **Crosses into** (the other domains
  its joins reach, and through which fields).

![DATAMAP trade: the Map view with the trade selected, its joins lit and the side panel showing their coverage](img/datamap/01-map-trade.jpg)

![The Cards view with the Trades domain open: its kinds, counts and joins, where the data comes from and the domains it crosses into](img/datamap/02-cards.jpg)

Domains follow `drishti-console/config/datamap.yaml`, which is presentation only: their names, their order and which packs they hold
(and, rarely, a kind to draw dashed that its contract does not mark computed). A pack the file does not name appears as a domain of its
own under its pack title, with its kinds and joins, with no configuration at all.

### Suggestions as you type

The command line suggests as you type, in a dropdown, like the Bloomberg terminal. The dropdown holds up to
**25 suggestions** and scrolls when there are more than fit.

| You have typed | You see |
|---|---|
| nothing (click in the box) | the entities you opened recently, marked `recent ·` |
| `T` | mnemonics that start with T (`TFLOW` Trade flow, `TRD` Trade, `TRDR` Trader, `TRIAL` Clinical trial), then recent entities |
| `TRD ` (with a space) | trades: `MX-20000001`, `MX-20000002`, … |
| `TRD MX-2000010` | trades whose id starts that way: `MX-20000100`, `MX-20000101`, … |
| `NSET SUMMIT` | netting sets whose id or title contains it: `NS-SUMMIT-LDN`, `NS-SUMMIT-NY` |
| `XYZ Q` | `No matches for "XYZ Q"` |
| `B`, `BI` (with the `bi` power) | the `BI` keyword, then `BI ` lists *BI home*, *My workspace*, *Catalogue* and the published reports whose id or title match |
| `FORM `, then `FORM tra` | the packs you may open that have a form, then (after a space) their kinds: `FORM trading trade` |

Each suggestion shows the mnemonic, the id (the letters you typed are highlighted) and a subtitle, for
example:

```text
TRD   MX-20000002   Trade · MUREX · IRS_FIXFLOAT · CP-HALCYON · EUR 110.0m
```

If your role sees some fields masked ([Masked fields](#masked-fields)), the subtitle is the entity's view title as you
see it (`Rates · Interest rate swap (fixed/float) · •••` when the counterparty is masked), and typing a masked value
(`Meridian`) finds nothing through it.

| Key in the command line | Does |
|---|---|
| `↓` / `↑` | Highlight the next / previous suggestion (the list scrolls with you) |
| `Tab` | Copy the highlighted suggestion into the box (the first one if none is highlighted). For an entity the box then reads `CPTY CP-NORTHBRIDGE <GO>`; for a mnemonic it reads `TRD ` and the dropdown refreshes |
| `Enter` | With a suggestion highlighted: open it straight away. With none highlighted: run what you typed as a command (it opens one entity or a pick list) |
| `Esc` | Close the dropdown |

Worked example:

1. Press `/` and type `CPTY north`.
2. The dropdown shows one line: `CPTY  CP-NORTHBRIDGE  Counterparty · Northbridge Capital LLP`. Press `↓` to
   highlight it.
3. Press `Tab`: the box now reads `CPTY CP-NORTHBRIDGE <GO>`.
4. Press `Enter`: the counterparty opens.

Without steps 2 and 3, pressing `Enter` on `CPTY north` gives the same result: the command names one
counterparty, so it opens at once.

The number of suggestions is a server setting (`drishti.commands.suggest-limit`, environment
`DRISHTI_SUGGEST_LIMIT`, default 25). A program can ask for a different number, up to 50:
`curl -s 'http://localhost:18480/api/v1/command/suggest?q=TRD%20T-1&limit=50'`.

### Earlier commands and your own aliases

- **History.** Press `↑` in the empty command line to bring back your last command, again for the one before (up to
  50); `↓` goes forward. The server keeps your history, so it follows you to another browser. Only commands that
  could be read are kept.
- **Aliases.** On **My account → Aliases**, give a command a short word: `MYBOOK` for `BOOK BOOK-RATES-1`, or `REVS`
  for `TRD PRODUCT_TYPE=FX_QUANTO`. Typing `MYBOOK <GO>` then does the same as the command; anything you add after
  the word is kept (`MYBOOK F3`). A mnemonic or a pack code cannot be an alias. The same through the API:
  `GET` / `PUT /api/v1/command/aliases` and `GET /api/v1/command/history`.
- **A range that cannot be read.** After an id, what reads as a range but is none answers with its code and what to type, on the
  terminal page: `TRD 1000001 0` gives *DRS-4022 a history of 0 days is empty: ask for 1 or more (TRD 1000001 30)*; `-5`, `1.5M`,
  `3X`, `30.`, `1e3`, `FROM yesterday`, `FROM 2026-02-30`, `FROM 2026-10-01 TO` (*TO needs a date*), `TO 2026-10-01` (*TO follows
  FROM*), `DIFF DIFF`, a bare `DIFF`, `DIFF 30` (*DIFF follows a range: TRD 1000001 30 DIFF*) and two ranges (`30 30`) are `DRS-4022`
  too, and a number of days past the limit (`99999`) is `DRS-4021`. The same after a bare id (`1000015 -5`, `1000015 since yesterday`).
  Full-width digits are read as digits (`３0` is `30`). It never opens today's view as if the range was not typed, nor a search that
  drops the id. A lone word after the mnemonic stays an id, never a range (`TRD 0` looks up the id `0`).

### When a command does not work

| You see | Meaning | What to do |
|---|---|---|
| `DRS-4001 cannot read command 'XYZ MX-20000001'; type <MNEMONIC> <ID> <GO> with a mnemonic such as AGR, BOOK, CPTY, …` on the terminal page (the mnemonics are those of the packs loaded) | The first word is not a mnemonic, and the text is not an id any pack recognises | Type the first letter and pick from the suggestions; check the pack is switched on ([Domain packs](#domain-packs-choosing-what-you-see)) |
| A page headed *Pick a trade* that says `0 of 0 trades match` and *Nothing matches.* | The mnemonic is fine, but no trade's id starts with what you typed (`TRD MX-29999999`) and no title contains it | Type less of the id (`TRD MX-2`) and pick from the list |
| `DRS-1001 no source holds trade/MX-29999999` | You opened an address such as `/v/trade/MX-29999999` directly, and no source has that id | Check the id; use the command line to find it |
| `DRS-4004 'XYZ' is neither a mnemonic nor a kind; type it alone to see suggestions` | A pick list or search started with an unknown word | Type the first letter and pick a mnemonic |
| `DRS-4004 no trade has a field 'producttyp'; did you mean PRODUCT_TYPE, PRODUCT_GROUP?` | A pick list or search names a field the kind does not have (case does not matter, spelling does) | Use the name it suggests |
| `DRS-4004 limit must be a whole number from 1 to 1000, not '0'` | `limit` outside its range | A number from 1 to 1000 |
| `DRS-5002 <you> may not open trade-valuation entities` | Your roles, or the packs you have switched on, do not include that kind | Switch the pack on in the box menu; otherwise ask an administrator |
| A page that says **FRTB is not active on this server**, *Off since 2026-10-24* | An administrator turned the command off for everyone (on Functions, `FUNC`, [COMMANDS.md](../admin/COMMANDS.md)). It is not active anywhere: typed, by its page, in search and type-ahead (it is not suggested), in exports and AI; a link to it on another page is struck through and says *not active*, and a panel that reads it says *not active (turned off by an administrator)* | Ask an administrator if you need it; an open page follows when it is turned on again |

### A pack at a glance

Type a pack's code alone, for example `MKT <GO>`, to see what the market-data pack holds: each kind with its mnemonic
(`CRV`, `FXV`, `IRV`, …), how many there are, an example you can open, and the fields its pick list shows. Click a
mnemonic to list that kind. Below the kinds, **What the pack holds** gives the pack README's summary, its data sources (with
any internal-only flag) and, folded, its *Data structures*: each kind's id rule, fields with an example and a description, and
nested structures by path, for the kinds you may open only. Every pack has a code (`CORE`, `MKT`, `TRDS`, `CCR`, `MRSK`, `WRLD`,
`NWMK`, … see [PACKS.md](PACKS.md#pack-codes)); the pack's name (`market-data`) works too.

## Pick lists: when a command names several entities

On a Bloomberg terminal, a command that matches several securities shows a list to pick from. Drishti does
the same. After you press Enter:

- if your command names **exactly one** entity, that entity opens;
- otherwise you get a **pick list** at `/s?q=…`, headed *Pick a trade* (or *Pick a book*, …), with one row
  per match and the kind's **key fields** beside each id.

### The forms

| You type | You get | Real result on the demo lakes |
|---|---|---|
| `TRD MX-20000001` | That trade opens (an exact id) | the swap `MX-20000001` |
| `TRD MX-200000` | Trades whose id **starts with** `MX-200000`, or whose title **contains** it | `99 of 15000 trades match`: `MX-20000001` … `MX-20000099` |
| `CPTY north` | Counterparties whose id starts with, or title contains, `north` | `2 of 2 counterparties match` (`CP-NORTHBRIDGE`, `CP-NORTHPLAINS`); `CPTY northp` is one match, so `CP-NORTHPLAINS` opens at once |
| `TRD MX-2*0` | `*` stands for any text, anywhere in the id | `44 of 15000 trades match`: `MX-20000010`, `MX-20000020`, `MX-20000030`, … |
| `TRD PRODUCT_TYPE=FX_QUANTO` | Trades whose field has that value | `102 of 15000 trades match`: `CLY-3000079`, `CLY-3000080`, … |
| `TRD QUANTITY > 10m and CURRENCY = usd` | Trades matching a condition (no `where` needed) | `3646 of 15000 trades match; the first 100 are shown` |
| `TRD MX-2* CURRENCY=usd order by QUANTITY desc` | An id pattern and a condition together, sorted | `220 of 15000 trades match`, largest quantity first (`MX-20000066`) |
| `TRD` | Every trade, of the three source systems | `15000 of 15000 trades match; the first 100 are shown` |

Rules:

- **Case never matters**, for ids, names, field names and values: `trd t-100`, `CPTY NORTH`, `PRODUCT_TYPE=fx_quanto`
  and `trd product_type=FX_QUANTO` all work. Text compared with `=` or `!=` ignores case in every search.
- **A field the kind does not have is an error, not an empty list:** `TRD where nosuchfield > 1` says
  `DRS-4004 no trade has a field 'nosuchfield'; …`, and a near miss names the field you meant
  (`TRD prodct_type=FX_QUANTO`: `…; did you mean PRODUCT_TYPE?`).
- **A word without `*`** matches ids that **start** with it and titles that **contain** it. **A word with
  `*`** is a pattern on the id only (`*SUMMIT*` matches `NS-SUMMIT-NY`).
- **A bare word after a comparison is a value:** `PRODUCT_TYPE = FX_QUANTO` needs no quotes. Quote values that
  contain spaces: `STATUS = 'Early warning'`.
- **Everything from [Search by value](#search-by-value) works here** (`and`, `or`, `not`, `contains`, `250k`,
  `order by`, `limit`). `not TRADE_STATUS = matured` means *not (TRADE_STATUS = matured)*.
- **How many rows** you see is your *Search results* setting (100 unless you change it in
  [Your settings](#your-settings)), or `limit N` at the end of the command. The table then pages through them
  25 at a time ([Tables](#tables-sorting-filtering-paging-and-the-keyboard)).
- **Pick lists follow the date** in the top bar, like every other read.

### The columns

The first column is the id (a link). Then come:

1. the fields your command uses (`CURRENCY` and `QUANTITY` in `TRD MX-2* CURRENCY=usd order by QUANTITY desc`);
2. the kind's **key fields**, which the pack declares under `columns:` in its `pack.yaml`
   ([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#columns-the-key-fields-of-a-pick-list)). In the shipped packs:

   | Kind | Key fields shown |
   |---|---|
   | trade | Source system, Product type, Trade status, Trade date, Buy/sell code, Currency, Quantity, Maturity date, Portfolio, Counterparty |
   | counterparty | Name, Rating, Sector, Country of risk, UEN, Net MTM |
   | book | Name, Desk name, Trade count, MTM (USD), DV01 |

3. for a kind whose pack declares no key fields, the first plain fields of its documents, up to six columns
   in all, so a pick list always says more than the id.

A *Title* column appears only when titles say more than the id.

### Worked example: find the FX quanto trades

1. Press `/`, type `TRD PRODUCT_TYPE=FX_QUANTO` and press Enter.
2. You should see *Pick a trade*, the line `102 of 15000 trades match`, the time it took, and a table:

   ```text
   TRD          Source system  Product type  Trade status  Trade date  Buy/sell  Currency  Quantity     Maturity date  Portfolio  Counterparty
   CLY-3000079  CALYPSO        FX_QUANTO     AMENDED       2026-03-27  B         GBP       245,000,000  2027-12-04     BOOK-FX-2  CP-VERDANT
   CLY-3000080  CALYPSO        FX_QUANTO     VALIDATED     2024-06-10  S         USD        15,000,000  2027-02-22     BOOK-FX-3  CP-HARBORPT
   CLY-3000081  CALYPSO        FX_QUANTO     VALIDATED     2026-04-01  B         USD       237,000,000  2029-08-07     BOOK-FX-2  CP-VERDANT
   …
   ```

3. Press `↓` twice: the second row is selected. Press `Enter`: `CLY-3000080` opens.
4. Press `Alt+←` to come back to the list, and change the box at the top of the page to
   `TRD PRODUCT_TYPE=FX_QUANTO order by QUANTITY desc`. Press **Search**: the largest quantity is now first (`CLY-4000384`, 1,212,505,000).
5. **CSV** downloads the list; **Watch as a monitor** turns its first 50 rows into a live watchlist
   ([Monitors](#monitors)).

## Tables: sorting, filtering, paging and the keyboard

Every table in the console (pick lists, search results, the tables inside views, admin lists) sorts and filters,
has a pager in its heading, and can be walked with the keyboard.

```text
| Largest trades     [Filter rows   ] ⧩  « ‹ 1–10 of 10 · page 1 of 1 › »  ▲ ▼ [25 rows ▾]  F2 ⤓ ? |  <- the heading
| Trade ↕   Product ↕   Notional ▼   Maturity ↕   MTM ↕                                          |
| …                                                                                               |
```

Every control is in the table's heading, never under the table: in a panel, its heading bar; for a pick list,
a search or an admin list, the strip just above the table. The page buttons are always there, greyed when
there is nowhere to go.

- **Sort**: click a column heading (or focus it and press Enter). Once for ascending (▲), again for descending
  (▼), a third time for the original order. Numbers, amounts (`1.5m`, `−30,205,543`), percentages and dates sort
  as values; text alphabetically.
- **Filter rows**: type in the box in the table's heading (a table panel's heading bar, or the strip above a pick
  list or admin list); a row stays when any cell contains the text (any case). Every table has it (table and
  ladder panels too); a Sutra may turn it off for a panel (`search: false`).
- **Filter by column**: `⧩` shows a box under each heading. Text matches within that column; in a number column
  `>1m`, `<0`, `>=250k` or `=5` compare values. Example: on `TRD MX-2`, type `>200m` under *Notional* to keep the 18
  trades above 200 million.
- A pick list or search holds the rows the server sent (100 by default); sorting and filtering work on those. For
  all of a kind, sort or filter on the server instead: `TRD where QUANTITY > 200m order by QUANTITY desc` (4369 of 15000 trades).
- For Excel-style filtering (tick values, conditions, dates, top 10) use the funnel in each heading: [Filter a table like Excel](#filter-a-table-like-excel).
- Live tables keep their sort and filters when they update.

| Control | Does |
|---|---|
| `«` `‹` `›` `»` | First, previous, next and last page. Buttons that lead nowhere are greyed |
| `1–25 of 99 · page 1 of 4` | Which rows you are looking at |
| `7 of 35 rows (filtered)` | With a filter on: the rows that match, of all the table holds |
| `▲` `▼` | Select the previous / next row, turning the page when needed |
| *rows per page* | 25, 50, 100 or 250. Your choice is **remembered in this browser** and applies to every table, at once |

**The keyboard.** Click a row, or press `Tab` until the table has the focus, then:

| Key | Does |
|---|---|
| `↓` / `↑` | Select the next / previous row; at the end of a page the next page opens |
| `PgDn` / `PgUp` | Move the selection one page down / up |
| `Home` / `End` | Select the first / last row (of all pages) |
| `Enter` | Open the selected row's link (its id) |
| `K` | Chart the table ([Chart any table](#chart-any-table)) |

Worked example:

1. Type `TRD MX-200000` and press Enter. The pager reads `1–25 of 99 · page 1 of 4`.
2. Click the first row (`MX-20000001`), then press `End`. The last page opens with `MX-20000099` selected.
3. Press `PgUp`: the selection moves 25 rows up, to `MX-20000074`, on page 3.
4. Choose *50 rows*. The pager reads `51–99 of 99 · page 2 of 2`, still showing your selection.
5. Press `Enter`: `MX-20000074` opens. Open any other list: it shows 50 rows per page too.

Tables that update live (a monitor, a ticking table inside a view) keep their page and selection when
their rows change.

## Filter a table like Excel

Every column heading of every table, matrix (a table with rows that open) and pivot has a **funnel** button, and its menu
works as Excel's AutoFilter does: tick the values you want, or filter by a condition, in as many columns as you like.
Nothing is written anywhere: it only decides which rows you see. The *Filter rows* box above still searches every cell, and
both apply together.

![The filter menu of the Type column: sort, a condition box, a search box and the values with how many rows have each](img/colfilter/01-menu.jpg)

Point at a heading (on a phone the funnel is always shown, and 44 pixels wide) and press the funnel, or focus it with `Tab`
and press `Enter` or `Space`. The menu has, from the top:

- **Sort**: *A→Z* and *Z→A* for text, *smallest→largest* for numbers (`1.5m`, `−30,205,543`, `12%` sort as values),
  *oldest→newest* for dates. Clicking the heading itself still cycles the sort.
- **A condition**, offered by what the column holds (the type is read from its cells: a column that is at least 80% numbers is
  a number column, likewise dates written `2026-10-14`; anything else is text):

  | Type | Conditions |
  |---|---|
  | Numbers | equals, does not equal, greater than, greater than or equal to, less than, less than or equal to, between, **top N** and **bottom N** (N is a box, 10 to begin with), above average, below average. A value may be written `1m`, `250k`, `12%` |
  | Dates | today, yesterday, this week (Monday to Sunday), last week, this month, last month, last 7 days, last 30 days, between, before, after. They count from today in your browser's calendar |
  | Text | equals, does not equal, contains, does not contain, begins with, ends with (any case) |

- **A search box over the values**: it lists the column's distinct values with **how many rows** have each one. Typing narrows
  the list and, as in Excel, leaves just those values ticked. *(Select all)* ticks or clears the values in view. *(Blanks)* is
  the rows whose cell is empty.
- **OK**, **Cancel** (or `Esc`) and **Clear** (removes this column's filter). A column uses either its ticked values or its
  condition, not both: choosing a condition sets the ticks aside, ticking a value clears the condition.

![A condition chosen: Amount greater than 1m](img/colfilter/02-condition.jpg)

The list of a column shows only the values that the **other** columns' filters leave, so it answers "what is left?". A list
longer than 500 values shows the first 500 and says so; search to reach the rest.

![The same table filtered on two columns (Type and Date): a filled funnel on each, "33 of 42 rows (filtered)" and Clear filters](img/colfilter/03-filtered.jpg)

**What you see when a filter is on**

- the column's funnel is **filled and amber**; its tooltip says what it does (*Filtered: Greater than 1m*, *Filtered: 2 values:
  Equity, FX*, *Between 2026-01-01 and 2026-03-31*, *Top 10*);
- the pager reads `33 of 42 rows (filtered)` (the total is the rows the table holds, before any filter) and a **Clear filters**
  button appears next to the *Filter rows* box. A screen reader is told the same, politely, whenever the count changes;
- filters across columns **combine** (a row must pass all of them); paging, the sort and the zoomed table's *Fit* page size work on
  the rows that remain;
- **live tables keep their filters.** When a live update replaces the table, the filters are applied to the new rows again: a row
  that stops matching leaves, one that starts matching appears. A menu you have open stays open and applies to the new table.

**Clear everything.** *Clear filters* (in the heading), or `Alt`+`Shift`+`X` while a table has the focus, clears the column
filters, the *Filter rows* box and the per-column text boxes of that panel. (`Alt`+`Shift`+`X` with the focus outside any panel clears
every table on the page.)

**Share and reload.** Column filters are part of the address: `?f.<panel id>.<column heading>=<filter>`, for example
`/v/trade/MX-20000001?f.schedule.Type=in:Fixed&f.schedule.Amount=gt:1m`. Reload, send the link, or open the snapshot and the same rows
are shown. The filter is written compactly:

| In the address | Means |
|---|---|
| `in:Fixed,Float` | the ticked values (`,` and `~` inside a value are written `%2C` and `%7E`); `~b` in the list is *(Blanks)*, `~m` is *(masked)* |
| `eq:5` `ne:5` `gt:1m` `ge:` `lt:` `le:` | number or text comparisons |
| `bt:100k~1m` | between (two values joined by `~`) |
| `top:10` `bot:10` `avga` `avgb` | top / bottom N, above / below average |
| `today` `yest` `thisweek` `lastweek` `thismonth` `lastmonth` `last7` `last30` `bf:2026-10-01` `af:2026-10-01` | dates |
| `ct:x` `nc:x` `bw:x` `ew:x` | contains, does not contain, begins with, ends with |

A filter that names a column or panel that is not there is ignored. Inside `<drishti-view>` the filters are kept in the page's
memory only (the host's address is not touched). **Remembering** a user's filters between visits is **off**: nothing is stored unless
the page sets `data-remember-filters="on"` on `<body>` or this browser has `localStorage['drishti.rememberFilters'] = 'on'`; then the
filters of a table return when the address has none.

**Masked values.** If a column hides values from you (shown as `•••`), the menu never lists a masked value: all of them are one
entry, **(masked)**, with a count, and you can tick it to see those rows (which still show `•••`). A masked cell never satisfies a
condition (not even *does not equal*) and sorts after the rest. Row rules (which rows exist for you) apply before any of this.

**Matrices (rows that open).** A table whose rows have children filters its **rows of every level**: a row stays when it matches,
and so do its parents, shown open, so you can see where a match sits; a parent that does not match itself stays only because
something under it does. The count reads `2 of 7 rows (filtered)` (matches only). While a filter is on the ▸ ▾ buttons are held
open; clear it to open and close rows again. Sorting is not offered in a matrix, to keep the order of the hierarchy.

**Pivots.** A pivot panel by one field is a table: its heading cells (the field, each column) filter its rows. A pivot by several
fields, drawn as nested groups, and the **Pivot tab** over a table keep their own tools: the tab's **Filters** zone filters the
*source* fields before the rows are grouped (see [The Pivot tab](#the-pivot-tab-slice-a-table-your-way)), which is the Excel
pivot's report filter; the table's column filters do not reach into the tab.

**Large tables.** Every table today holds the rows the server sent (100 for a pick list), and a filter works on those; a
last line "*n* more rows" says when there are more. Nothing is paged by the server, so there is no server-side filter yet. When a
table is paged by the server the same filters will go to it as the address's `f.` values, and the page raises the
`drishti:column-filter` event for it to do so; until then, for all of a kind, filter in the query:
`TRD where QUANTITY > 200m`.

**On a phone.**

![The menu on a phone: finger-sized buttons, the list scrolls inside the menu](img/colfilter/04-phone.jpg)

The funnel is always shown and 44 pixels wide; the menu fits the screen (its list scrolls inside it) and every row, button and box
in it is at least 44 pixels high.

| Key | Does |
|---|---|
| `Enter` / `Space` on a heading's funnel | Open its menu; the focus moves into it |
| `Tab` / `Shift`+`Tab` | Move within the menu (it does not leave until closed) |
| `↓` `↑` `Home` `End` in the list | Move between *(Select all)* and the values (from the search box, `↓` enters the list); `Space` ticks |
| letters in the list | Jump to the next value that begins with what you type |
| `Enter` | OK |
| `Esc` | Cancel and close the menu, and nothing more: a zoomed panel or a drawer stays; a second `Esc` leaves them |
| `Alt`+`Shift`+`X` | Clear all filters of the panel that has the focus |

## Chart any table

Every table can be drawn as a chart: the tables inside a view, search results and pick lists, admin lists, the days of a
history page, and a BI report's table and matrix visuals. Press the **chart button** in the table's heading (the bar-chart
icon beside the page size), or focus the table and press `K` (`Shift`+`K` on a history page, where `k` is *previous day*;
`K` on a focused BI table, while `K` elsewhere on a report still toggles kiosk). A wide dialog opens with the chart
**already drawn**; it is full screen on a phone. Close it with `Esc` or ✕: the focus goes back to the button.

![A line chart of a swap's cash flows by month, two series on two axes, in the Terminal theme: the chart, its summary, what was charted, and the settings beside it](img/table-chart/line-by-month-terminal.png)

**What it draws first.** X is the first date column (a time axis), else the first text column (categories), else a number;
Y is the first numeric column that is not an id, summed (averaged for percentages, prices and rates); with no number at all
it counts rows. A date X is a line, a text X a bar chart, two numbers a scatter. With more than 30 categories it starts at the
top 20 and *Others*.

**The settings** sit beside the chart (under it on a phone, in a *Settings* section that folds away); every change redraws at
once, there is no Apply.

| Setting | Choices |
|---|---|
| Type | Line, Bar, Area, Pie (or donut), Scatter, Combo (bars and lines together); `1` to `6` pick them when the focus is not in a box. A type the columns cannot make is greyed with the reason (*A scatter needs two numeric columns*) |
| X | Any column; a date column also takes **Dates** (automatic, by day, week, month, quarter, year; automatic keeps days up to 500 points) and **Gaps** (connect across missing days, or break where a business day has no data) |
| Y series | Up to six: the column (or *Rows (count)*), the aggregation for a repeated X (sum, average, count, min, max; a scatter can plot the points themselves), the left or right axis (two axes for different units), and in a combo bar, line or area |
| Split by | A text or date column makes one series per value: at most 12 (the 11 largest and *Others*) |
| Sort | By X or by value, either way |
| Top N | Keep the N largest categories, with or without *Others* (a pie has at most 12 slices). Not on a time axis: it would cut dates out of a line |
| Stacked, 100%, Donut, Labels | Stack bars (and 100% of each stack), a donut instead of a pie, values on the marks |

![Bars of amounts by desk, stacked by currency, in the light theme](img/table-chart/stacked-bars-light.png)

**What it charts.** The rows the table holds, after its filters (the filter box and every column filter), on the page's
business date; paging is ignored, so a table on page 2 charts all its filtered rows. The line under the chart says what was
charted: *Showing 30 of 500 rows*, *after the table's filters*, anything left out. Search results, history and BI tables
chart the **raw values** the server sent; other tables read the cells as displayed, and the line says *figures as displayed
(rounded)* (`1.5m` is charted as 1,500,000). A table whose panel also has a Pivot tab charts its raw rows.

**Masked values are never charted.** A column hidden for your role is listed greyed in every picker, *(hidden for your
role)*, and cannot be X, a series or a split; the status line names it. In a column only partly masked, the masked cells
are left out of every sum, average and count, and the line says how many. Exports carry only what is drawn.

**Chart all N.** When the table shows fewer rows than exist, **Chart all N rows** (or `A`) charts every row: a search whose
pack offers its Pivot tab is aggregated on the server over every match of the business date (on the pack's pivot fields,
a date by day); a view's table with a Pivot tab reads all its rows (up to the server's limit, said if reached). Otherwise the
button is absent or greyed with the reason. **Chart the shown rows** goes back.

![A combo of bars and a line on two axes, by quarter, in the light theme](img/table-chart/combo-two-axes-light.png)

**Edges.** A table with no rows (or whose filters leave none) opens with *No rows to chart* and the settings still usable; a
single row draws one bar, point or a full pie, and the summary says *one row*; a pie refuses negative values and offers a bar
chart or the absolute values; a table with only identifiers or only hidden columns says *Nothing to chart* and why.

**Read it without the picture.** The sentence under the chart is the chart's summary (and what a screen reader reads):
*Line chart of Amount (sum) by Pay date (month), 12 months, from 2027-01 to 2027-12, highest 2027-02 4.3m, lowest 2027-01
−290.1k, trend down; 40 rows.* **View as table** (`T`) shows the chart's data as a table. Focus the chart and use `←` `→` to
step through the points (each one is read out), `↑` `↓` to change series, `Home` / `End` for the first and last.

**Export.** **PNG** and **SVG** save the picture in the theme's colours (no round trip to the server); **CSV**, **Excel** and **JSONL**
(JSON Lines, a row a line) save the chart's data as drawn: X, then one column per series, *Others* as a row. `E` moves to the export buttons.
**Defaults** puts back the chart the table opens with. Your settings are kept per table until you close the browser tab
(saving them for next time is planned).

![A donut of amounts by currency in the Terminal theme](img/table-chart/donut-terminal.png)

**Themes.** Every colour is the theme's: all ten themes, the three Terminal hues and the light theme included; changing
the theme redraws an open chart. Two series never share a colour, and past eight series lines get dashes and bars patterns.

**On a phone** the dialog fills the screen: the chart on top, its summary, then *Settings*; buttons are finger-sized and the
page never scrolls sideways.

![The chart on a phone in the Terminal Blue theme](img/table-chart/phone-terminal-blue.png)

| Key | Does |
|---|---|
| `K` | On a focused table: open its chart (`Shift`+`K` on a history page) |
| `1`…`6` | Line, bar, area, pie, scatter, combo |
| `←` `→`, `Home` `End` | On the chart: the previous, next, first, last point, read out |
| `↑` `↓` | On the chart: the previous, next series |
| `T` | View as table, and back |
| `A` | Chart all N rows, and back (when offered) |
| `E` | Go to the export buttons |
| `?` | This section |
| `Esc` | Close; the focus returns to the chart button |

## Controls on a screen: choose what a panel shows

Some screens offer **controls**: a dropdown, a list of boxes to tick, a pair of dates, a search box. They are the Sutra's own (its author put
them where they help), and they are different from the funnels of [Filter a table like Excel](#filter-a-table-like-excel): a funnel filters
what a table already holds; a control changes what the *panel shows*, and it can change a title, a total, a chart and the rows together. Two
screens that ship with them: an interest-rate swap (`TRD MX-20000001 <GO>`: its **Events** by leg and by date range) and a netting set
(`NS-MERIDIAN-RE-NY`: its **Member trades** by asset class, product and how many to show).

![The events of a swap with the controls Legs (open: a filter box, Select all, Clear, a box per leg) and Between (two dates and a presets list) in the panel's header](img/controls/01-header.jpg)

**Using them.** A control sits under the strip, in a panel's header or in a bar at the side of the screen, with its label. Change one and
**only the panels that read it** are rebuilt, in place (you keep your place, your zoom and your other filters); the rest of the screen does not
move, and a line for screen readers says what was updated. On the swap, the date presets (*Next 90 days*, *This month*, *Year to date* ...)
fill both dates for you; either date may be left empty (*from the start*, *to the end*).

| Control | How |
|---|---|
| a **dropdown** (also *cascading*: the second one's choices follow the first) | Pick a value; *(all)* clears it |
| a **list of boxes** (multi-select) | `Enter` or `Space` on its button opens the list; type to filter it, tick boxes, or *Select all* / *Clear*; `Esc` closes it. Nothing ticked means everything |
| a **switch** or **buttons** | Click, or `Space`; with buttons the arrow keys move and choose |
| **dates**, a **number range** | Type or pick; the panel follows when you leave the box (numbers: when you pause) |
| a **slider** | Drag the round handle (or press on the track), or focus it and use `←` `→` for one step, `PageUp` / `PageDown` for ten, `Home` / `End` for the ends; a slider with two handles sets a range, and the handles cannot cross. The number beside it says where it is; on a phone the handles are 44 pixels |
| **Show 10 / 25 / 50** (top-n) | Pick how many rows |
| **Find** (search) | Type; the panel follows when you pause |

![The same swap with leg 1 only and the next 90 days: the panel's title says the dates it shows, and the address carries both choices](img/controls/02-filtered.jpg)

**The address remembers.** What you set is written to the address: `.../v/trade/MX-20000001?ctl.legs=1&ctl.when=2026-10-27..2027-01-24`
(a relative choice such as *Next 90 days* is written as the dates it meant). Send the link, bookmark it or press **Share**: whoever opens it sees
the same panels with the same controls. A control you have not touched is not in the address, so it starts at the author's default. A control
the author marked *remember* (Legs on the swap) also comes back as you last left it **in this browser** the next time you open the screen
with no address values; another browser, or another person, starts from the defaults.

**Click a row or a bar to choose.** On the netting set, *Net MTM by asset class* is a chart you can click: a click (or `Enter` on the focused bar)
sets **Asset class** and the *Member trades* table follows; the chosen bar is marked, and a second click on it clears the choice. The same
works on rows of a table where the author set it up. Everything a click does, the control does too.

![The netting set with Rates chosen by clicking its bar: the member trades are Rates only, with their title, and the chosen bar is marked](img/controls/03-cross-filter.jpg)

**Live screens keep your choice.** When the screen ticks, every update is built with your controls, so a filtered table stays filtered.

**On a phone.** Controls stack one to a row, each at least 44 pixels high, and the page does not scroll sideways; the list of boxes opens under its button.

![The swap's events on a phone: Legs open, Between with two date boxes, the table below](img/controls/04-phone.jpg)

**What you may see.** A control offers only values you may see: for a field your role does not show (`•••`), a dropdown lists nothing, and a
search over it never finds anything. Controls only choose; they never change data.

**Sending it.** *Share with a note* keeps your controls and your [column filters](#filter-a-table-like-excel) with the share: the people you
send it to open the same view with the same choices, and a picture of the share (if you attach one) shows the filtered rows. The dialog says
*with your controls and filters* beside the date when there are any.

**Not yet.** The Pivot tab and CSV download of a panel read **every** row, not the ones the controls leave, and a scheduled export does not
carry the choices (there is no scheduled delivery yet). Authors add controls in the workbench's
[Controls tab](SCREEN_DESIGNER.md#the-controls-tab); the Sutra's side of it is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#controls).

## A deep trade: legs, cashflows and fixings

Real trades are not flat. A swap has **legs**, each leg has **cashflows**, and each floating cashflow has **fixings**: the
observations of the rate or price that set it. The trading pack's swaps, basis and cross-currency swaps, caps and floors, swaptions and
commodity swaps are all held that way (`LEGS` → `CASH_FLOWS` → `RESETS`, with `EVENTS` beside them), and their screens show it. Open
`TRD 1000001`, an ENDUR commodity swap (`ComSwap`, sub-type `ENGY-SWAP`): a fixed price leg against a floating aluminium index leg (`IDX-ALU-3M`),
36 monthly cash flows each.

![A commodity swap: the legs side by side, each with its terms, its notional profile and its cash flows; the index leg also charts its fixings](img/deep/01-commodity.jpg)

- **Fixed against floating** sums the trade up: fixed price `2,332.9131`, floating index `IDX-ALU-3M`, averaging *Monthly*, price unit `MT`, 36 periods.
- **Legs (F3), side by side.** `Leg 1 · Fixed price FIXED` and `Leg 2 · Floating price FLOAT · IDX-ALU-3M`: one column per leg on a wide screen, tabs
  on a narrow one (a cap has one leg, some swaps more). Each leg shows its **Terms** (type, pay or receive, economic type, index, notional
  `USD 167,969,743`, price unit, settlement, discount index, daily volume, flows), its **Notional profile** (a step line), its **Fixings** and its
  **Cash flows and resets**.
- **Cash-flow schedule, every leg (F4)**: one row per cash flow of every leg (72 here): flow id, start, end, payment, fixing date, notional,
  rate or price, and how many resets it has.
- **Floating cash flows open to their resets.** A row with a ▸ has resets (`5000037`, the floating leg's first flow, has one): press it (or Enter or
  Space on it) and the resets show under the cash flow, indented, with their dates and values. A fixed flow has no ▸. The box above the table
  filters rows and keeps the cash flows whose resets match.
- **Events by leg (F5)** and **Netted payments by date (F6)**: the trade's events linked to its legs, and the payments netted per date (paid −,
  received +: `+195,278` on 2025-07-22, `−211,665` on 2028-03-07).
- **Export.** The download link of a panel's heading saves every cell with its tab, panel and row, as CSV.

A MUREX interest-rate swap, `TRD MX-20000001`, reads the same way with two legs, `Leg 1 · Receive FIXED` and `Leg 2 · Pay FLOAT · AONIA`; the
floating leg's cash flows open to their resets:

![A swap: the two legs side by side; the fixed leg's cash flows, and the floating leg's AONIA fixings](img/deep/02-swap.jpg)

On a phone the legs become tabs that scroll along one line, the terms stack, and the table scrolls sideways inside its own box; the ▸ buttons are
44 px targets.

![The same commodity swap on a phone: the index leg's cash flows with the first one opened to its resets](img/deep/03-phone.jpg)

The same layout is what you get from **inference** for any document that holds lists inside a short list of records, even with no Sutra written
for it ([Inference](../architecture/INFERENCE.md#the-rules)). To build such a screen see
[Panels inside a tab](RACHANA_REFERENCE.md#panels-inside-a-tab) and
[Modelling deep documents](PACK_MODELLING_GUIDE.md#modelling-deep-documents-legs-cashflows-and-fixings).

## Reading a view

Open `TRD MX-20000001`. A view has five parts.

### 1 · The title line

```text
[Rates · Interest rate swap (fixed/float)]  MX-20000001  with CP-MERIDIAN-RE   Alert
Pin  Share  JSON  Export  Subscribe  Print  Viewed by  About  Discussion  Compare
```

- The **pill** says what the entity is.
- The **id**, and the main party (**with …**), the trade's `COUNTERPARTY`. The party is a link: click it to open the counterparty.
- The buttons on the right are explained in [Export, print and share](#export-print-and-share),
  [Alerts](#alerts) and [Pinning](#your-settings). **Compare** appears there too when a past date is picked.

### 2 · The strip

The row of key figures under the title. For `MX-20000001` on 2026-10-16:

| Label | Value |
|---|---|
| Source | `MUREX` (the source system the trade comes from) |
| Side | `Sell` |
| **Notional** | `AUD 242,000,000` (highlighted: the figure the desk watches most) |
| Quantity | `242,000,000` |
| Trade date | `2025-07-25` |
| Maturity | `2032-06-25` |
| Status | `AMENDED v2` (the trade status and version) |
| Portfolio | `BOOK-RATES-3` (a link) |

A trade carries no MTM: its valuation does. `TVAL TVAL-MX-20000001` shows `MTM +1,876,068`, `P&L 1D +126,999`, `DV01 −155,245`
for the same trade.

Positive figures carry a `+` and the positive colour; negative ones a true minus sign (`−`) and the
negative colour.

### 3 · The panels

The main detail is on the left; context (charts, linked entities) is on the right. Each panel has a
header with:

- its **title** and, if it has one, its **function key** (`DES · F2` means press `F2` to jump here);
- an **inferred** tag when Drishti laid the panel out by itself (hover over it to read why);
- a **↓** button to download the panel: its menu offers **CSV** or **JSON Lines**;
- a **?** that explains this kind of panel.

`MX-20000001` shows *Deal ticket* (F2), *Legs* (F3, the legs side by side), *Cash-flow schedule, every leg* (F4), *Events by leg* (F5),
*Events*, *Netted payments by date* (F6), *Payments by month*, *How this view was built* and *Linked entities*; F7 opens the counterparty's
trades, F8 the impact and F9 the raw JSON.

A page over many trades (`CTRD`, `PTRD`, `LTRD`, `ITRD`: a counterparty's, a portfolio's, a legal entity's, an index's trades) lists at
most 1,000 of them; the trade count, the notional, the payments in the next 7, 30 and 90 days and every other total cover all of
its trades, so a portfolio of 20,000 trades opens in well under a second. To work on every trade, search them (`TRD PORTFOLIO=…`) or
open the pack's BI reports.

Panel kinds you will meet:

| Kind | Looks like | Example |
|---|---|---|
| `kv` | label and value pairs | *Deal ticket* in `TRD MX-20000001` |
| `table` | rows and columns, totals for money | member trades in `NSET NS-SUMMIT-NY` |
| `tabs` | one tab (or column) per element of a list | *Legs* in `TRD MX-20000001` |
| `ladder` | dated rows, the next one highlighted | a ladder of dated flows (the panels catalogue) |
| `line` / `area` | a chart over tenors or dates | the curve in `CRV CRV-USD-OIS` |
| `hbar` | horizontal bars | *Netted payments by date* in `TRD MX-20000001` |
| `surface` | a grid as a heatmap, or a 3D surface you can rotate | `FXV FXV-EURUSD` (*Heatmap* / *3D* buttons) |
| `status`, `gauge`, `markdown` | a status list, a dial, formatted text | various packs |
| `links` | linked entities with badges | *Linked entities* everywhere |
| `provenance` | how the view was built | *How this view was built* |

The [panel kinds catalogue](PANELS.md) shows each one in detail.

**No data available.** A panel whose data is missing, or not in the expected shape, says *No data
available*. The rest of the view still renders. Real feeds are often incomplete; this is normal.

### 4 · Links

Every identifier in a view is a door. Click `BOOK-RATES-3` in the strip and the book opens. The
*Linked entities* panel lists everything the entity points to, each with a **badge** read from the
target (for example the rating `A` on the counterparty `CP-MERIDIAN-RE` of `TRD MX-20000001`). A badge that says *pending* means the target did not
answer in time; open it to see it.

**Ids in tables are links too.** A table column that shows an identifier (a column bound to a field whose
name ends in `Id`, `Ref` or `_id` in any case, such as `TRADE_ID`, `COUNTERPARTY_ID` or `BOOK_REF`) links each value to its
entity, as long as a pack recognises the id. Example: open `BOOK BOOK-RATES-1`; its *Largest trades* table
(`F2`) starts with the *Trade* column (`MX-30003150`, `MX-30004482`, …), and clicking `MX-30003150` opens that trade. With the table selected, `↓` to a row and
`Enter` does the same ([Tables](#tables-sorting-filtering-paging-and-the-keyboard)).

**Breadcrumbs** above the title (`← MX-20000001 / NS-MERIDIAN-RE-NY`) show the path you followed in this
browser tab. Click one to go back to it, or press `Alt+←`.

### 5 · How this view was built

The *How this view was built* panel answers "where did this come from?":

| Line | Example | Meaning |
|---|---|---|
| Layout | `Sutra irs-fixfloat v1 + inference` | A Sutra (a written layout) drew the screen; inference filled gaps. `inference only` means no Sutra matched |
| Fingerprint | `b7df…1372` | The shape of the data, used to cache the layout |
| Source | `murex-rates`, generation `1674` | Which system the document came from, and its version counter |
| Node | `drishti-b · forwarded from drishti-a` | Administrators only, when several servers work as one (Drishti*net): the server that built the view and how: `local`, `built here, data from <server>`, `forwarded from <server>`, or `scatter-gather from <servers>` (and any that were missing) for a page that groups records of several servers. Nobody else sees it |

The footer repeats the source and generation, and the business date when one is picked
(`trade-endur · gen 7 · as of 2026-09-29`).

## About this page

*What am I looking at, can I trust it, and why does it look like this?* Press `?` on any view (or `F1`, or the **About**
button beside the title). A drawer opens on the right; the page stays usable beside it. It is written for you: what it
says is worked out from the page **you** have, so a field hidden from your role is never explained with its value.

![The About drawer on a stress result view](img/about/01-var-drawer.jpg)

Each layer is a section you can fold; the first ones are open, and Drishti remembers which you folded (in this
browser only).

| Layer | What it tells you | Example |
|---|---|---|
| **What you are looking at** | One sentence about this entity, written by the domain pack and filled with its own numbers; the screen's description; notes on single panels; which pack wrote it | "STR-GFC2008-RATES: under \"Global financial crisis 2008\" the portfolio changes in value by −73.6m USD against a limit of −67.5m." |
| **Where the data came from** | The source, its health (`up`, `degraded`, `down`), the generation, when it was fetched and updated, whether it is fresh or stale, a snapshot or a live feed, and how many linked entities were fetched | "Source stress-engine · health up · fresh · a snapshot" |
| **Why the page looks like this** | Which Sutra (layout) was chosen and why; panels that were added by inference; panels with no data and the reason (missing, null, empty list, masked); fields hidden for your role; panels you may not open | "Layout: Sutra stress-result v1, the one that applies to this kind, priority 10." · "1 field hidden for your role: Limit (in strip)." |
| **Where next** | The keys on this page (F7 and F8), and one link per kind of panel on it, to the guide that explains how to read it | "The histogram panel: what it shows and how to read it" |

A layer with nothing to say is not shown. If you see **What each number means** (a layer that explains the figures one
by one: term, unit and sign), your pack has a glossary; it comes with the next stage of the feature, so older servers
do not show it.

### Ask about this page (when your administrator has turned it on)

At the end of the drawer, a box **Ask about this page** answers a question in your own words ("is this number a loss?", "why is the
curve panel empty?"). It is not on unless your administrator switched it on for your pack; if you do not see it, it is off. The
answer comes from a language model that is given only this page's explanation (labels, definitions, the pack's guide), not the
document's values unless the administrator chose that, and never a field hidden from your role. It can be wrong: the layers above
are the reference. If it cannot be reached the box says "Ask is unavailable"; everything else in the drawer works. `Alt+?` jumps to the box.

### Ask the data, explain a number, describe a report (BI, when your administrator has turned AI on)

In BI ([BI_AI.md](BI_AI.md)), a report page shows a box **Ask the data**: type "notional by desk" and you get a table, a chart that suits it and the query that was asked, answered from
what *you* may see (the same rows and masks as the report). The light bulb in a visual's header **explains** its number: where it comes from and what it is made of. In the designer,
**Describe a report** drafts a report from your words; you read it, use it and save it, or not. These are off unless your administrator switched them on; if you do not see the box and the bulb, they are off.

### The keys

| Key | Does |
|---|---|
| `?` | Open or close the drawer (anywhere on a view except in a text field) |
| `F1` | Open the drawer; `F1` again, inside it, or *The full guide to this screen*, opens this guide |
| `Esc` | Close the drawer; focus goes back to where you were |
| `Tab` | Move through the drawer. On a phone the drawer is a sheet that holds the focus until you close it |

On a phone the drawer is a bottom sheet (swipe down, or tap **×**, to close it):

![The About sheet on a phone](img/about/03-var-phone.jpg)

All the layers open, on the same view:

![Every layer of the drawer open](img/about/02-var-layers.jpg)

### Someone else sees less

Ask the same page as a user whose role hides a field and the drawer hides it too. In this picture the administrator
chose to mask `limit` for the *viewer* role (a **demo**: no standard setup masks it). The sentence reads "against a limit of
•••" and the glossary marks Limit *hidden for your role*:

![The drawer for a viewer: the limit is masked](img/about/05-viewer-masked.jpg)

### Another domain

The words come from the pack, not from the program, so every domain explains itself in its own terms. An economy of the
world-economy pack (`ECON ECON-USA`), in that pack's words:

![The drawer on an economy, with the world-economy pack's text](img/about/04-economy.jpg)

### Good to know

- The drawer is fetched the first time you open it, never with the page, so it does not slow a view down.
- When a live update brings a newer generation of the data, an open drawer asks again (at most once in two seconds).
- No text? A pack without an `about` entry for the kind still shows the other layers; the pack's author can add one
  ([how](PACK_MODELLING_GUIDE.md#about-text-and-glossary); how it works inside is
  [HOW_IT_FITS.md](../architecture/HOW_IT_FITS.md#39-about-this-page-end-to-end)).
- Hover hints on field labels and a help popover on each panel's `?` come with the glossary stage; today the panel `?`
  still opens that panel kind's page in this guide.
- If the drawer says "could not be loaded", try again; the page itself is not affected. Error numbers are in
  [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Layout mode: arrange a view your way

A Sutra decides where each panel of a view goes, for everyone. **Layout mode** lets you arrange the panels for
yourself: put the panels you read first at the top, give a table more width and a chart less, move a panel between the
main and the side column, or hide one you never read. Your arrangement is a **personal layout**: it is kept on the server
for you, it never changes the Sutra, and nobody else sees it.

A personal layout belongs to a **Sutra**, not to one entity: arrange `TRD MX-20000001` and every trade that the same Sutra
lays out (`irs-fixfloat` here: every interest rate swap, fixed against floating) opens your way. The footer says so: its **Layout** key shows a dot (`Alt+L Layout •`)
when you are looking at your own layout.

### Turning it on

Press `Alt+L` on a view (`Option+L` on a Mac), or click **Alt+L Layout** in the footer. A bar appears under the top bar
(*Layout mode · Drag a panel by its heading …*), every panel gets a dashed outline, a size badge at its bottom right
(`12/12`, `8/12 · 8 rows`), a **Hide** button in its heading, and two handles: one on its right edge (width) and one on
its bottom edge (height).

Each column of the view, the main one and the side one, is a **grid of 12 columns**. A panel is 1 to 12 columns wide
(12, the whole column, is the default) and either as tall as its content (the default) or 1 to 24 **rows** of 2.5 rem
(40 pixels at the usual text size); a panel with a set height scrolls inside.

### With the mouse, a pen or a finger

| Do | To |
|---|---|
| Drag a panel by its heading | Move it: a dashed box shows where it will land, in this column or the other one. Near the top or bottom of the window the page scrolls |
| Drag its right edge | Make it wider or narrower, a column at a time (not under 3 of 12: `layouts.min_span`); panels narrower than the column sit side by side |
| Drag its bottom edge | Give it a height in rows; double-click the edge for its natural height again |
| **Hide** / **Show** in its heading | Hide it from your view (in layout mode it stays, greyed, so you can show it again). The last visible panel cannot be hidden |

### With the keyboard

`Tab` moves between panels (each is outlined when it has the focus). On the focused panel:

| Key | Does |
|---|---|
| `↑` `↓` | Move it one place earlier or later in its column (in a row of narrow panels, earlier is to the left) |
| `←` `→` | Move it to the main column or to the side column |
| `Shift`+`←` `→` (or `-` `+`) | One column narrower or wider |
| `Shift`+`↑` `↓` | One row shorter or taller |
| `A` | Back to its natural height |
| `H` (or `Delete`) | Hide it, or show it again |
| `Ctrl`+`Enter` | Save (`Cmd`+`Enter` on a Mac) |
| `Esc` | Cancel: leave layout mode and put everything back as it was |

Every change is announced to screen readers (*Cashflows · Leg 1: 1 of 5 in the main column*, *Linked entities: 6 of
12 columns wide, 7 rows tall*).

### Save, cancel, reset

- **Save** (`Ctrl+Enter`) keeps the layout and leaves layout mode. Open another trade of the same Sutra, or reload the
  page: it is drawn your way.
- **Cancel** (`Esc`) leaves layout mode and undoes everything since you turned it on.
- **Reset to <Sutra>** arranges the panels as the Sutra has them; **Save** then forgets your layout.

### Worked example

On the demo lakes, open `TRD 1000001` (an ENDUR commodity swap, Sutra `endur-comswap`):

1. Press `Alt+L`.
2. Drag *Cash-flow schedule, every leg* by its heading above *Legs*, and let go: it is first in the main column.
3. Drag its right edge to about two thirds of the column: the badge reads `8/12`. Drag its bottom edge down until the
   badge reads `8/12 · 8 rows`.
4. Drag *Payments by month* from the side column into the main column, under the schedule, and narrow it to `4/12`.
5. Press `Tab` until *Linked entities* has the focus, press `←` (it moves to the main column), then `Shift`+`←` six
   times (`6/12`): it now sits beside *Payments by month*.
6. Focus *How this view was built* and press `H`.
7. Press `Ctrl+Enter`. Reload: the view keeps your layout, and the function keys (**F2** the ticket ... **F6** the charts) still work. The key
   of a panel you hid is greyed (*Hidden in your layout*) until you show the panel again.

### Who may, and what follows your layout

- Layout mode needs a role with the **layout** power. Every role has it unless an administrator says otherwise; the
  built-in `viewer` role does not ([Admin → Roles](#admin--roles-what-a-role-may-do)). Without it the key is greyed with
  the reason. A view laid out by inference alone (*Layout: inference only*) has no Sutra to arrange, so the key is
  greyed there too.
- **Printing** follows your layout: your order and widths, hidden panels left out, every panel as tall as its content.
- **Workspace panes** show views with your layout too (layout mode itself is offered on the full view).
- **Live updates** keep your sizes. CSV and JSON exports, Calc and the API are unaffected: they read the data, not the
  arrangement.
- **When the Sutra changes**, your layout follows: panels the Sutra no longer has are dropped from it, and panels it
  gained appear at the end of their column, as the Sutra places them.
- You keep at most 50 layouts (one per Sutra).

### Promote to Sutra: offering your layout to everyone

If your role may author Sutras, the bar has **Promote to Sutra…**. It saves your layout and opens a panel that shows:

- the changes in words (*'refs' moves to the main column*, *'cashflows' is 8 of 12 columns wide*, *the main column
  reads cashflows, refs, dv01, leg2, legs, built*);
- the diff between the latest version of the Sutra and the **next version** your layout makes: the panels in your
  order, `area: right` where you put them in the side column, and the panel options `span` and `height`
  ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#keys-every-panel-takes)) for your sizes. The rest of the Sutra (its
  comments, quoting and every other key) is kept as written;
- a tick box, **Remove the panels I hid from the Sutra**. Unticked, hidden panels stay in the Sutra for everyone else.

Write a note for the reviewer and press **Submit for review**. As with a save from the workbench, the new version is a
**proposal**: an approver opens it in *Build → Reviews* (the review shows the diff against the version before), and it
goes live only when approved. A panel your layout moved is listed in words, for example *moved: DV01 by tenor (USD)
from position 3 to 1 (side → main)*, and the line diff below it holds only the real edits (a width, a height, a
removed panel); the full line diff, where a move reads as a deleted block and an added one, is behind **Full line
diff**. The promote drawer shows the same before you submit ([Reviews](#reviews-approving-a-sutra)). With review switched off on the server the button
reads **Publish** and the new version is live at once.

## Screens that respond: clicks, refresh, editing and your own page

Newer screens (every pack screen is built from the same parts) can do more than show a document. What you meet:

- **A click opens more.** On a table with clickable rows (the book desk, `/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk`) a click,
  or Enter or Space on a focused row, marks the row and opens what the screen says: the trade's cash flows under the row, its
  counterparty and credit limit beside it. A panel waiting for a click says so (*Pick a trade to see its counterparty and credit
  limit*). A new click replaces what opened. A panel that would follow a value masked for you says **Hidden for you** and is not
  looked up. If the server that drew the screen goes away, the next click says *Your selections were reset ... Pick again.*: click
  the row again.
- **Refresh and freshness.** A panel that refreshes says how fresh it is: *updated 12 s ago*, ticking; *stale* in amber when its
  source is behind; *trade-endur isn't answering; showing data from 10:42* when a refresh failed (it keeps what it showed). Its header
  has **pause/resume** and **every …** (only slower than the screen's own interval); **Pause all** above the panels pauses the page.
  Your choices are kept for you, per screen. A browser tab you are not looking at fetches nothing.
- **Editing a record.** Where a screen lets you, clicking a row opens a form beside the table, or a pencil on a column lets you edit
  in the cell, and **New …** opens a guided drawer. Fields you may not change say why; a wrong value is explained under its field.
  **Save draft** keeps a **draft** that only you see (*You have 2 drafts on this page* offers them again). The page asks before you
  leave with changes not saved. The reference is [SUTRA_FORMS_GUIDE.md](SUTRA_FORMS_GUIDE.md).
- **Submitting a change (2.8.0 write-back).** Where editing is on, you hold the edit power and the form says where it goes (the book
  desk's trades do), **Submit…** opens a sheet: what you saw, what is there now and your change; where it goes (a correction kept beside
  the booking system's value, or an amendment sent to it); who approves (never you); what it recomputes; and the reason. After **Confirm**
  the sheet follows it: waiting for an approver, applied (everyone viewing the trade sees the new value at once, *How this view was built*
  says *Edited here*), or conflicted if the trade moved since you opened it (your draft is kept). Approvers decide in **Review → Changes**
  (Build → Govern → Changes). Someone else's change to the record you are viewing shows *Changed by … just now*.
  The reference is [SUTRA_FORMS_GUIDE.md section 4a](SUTRA_FORMS_GUIDE.md#4a-submitting-a-change-280-write-back); a walk-through is
  [tutorial 24](../tutorials/24-correct-a-trade-and-approve-it.md).
- **Screens laid out on a grid.** A screen with named areas may have a split you drag (its position kept for you), areas shown as
  tabs and panels that fold. Where the screen allows it, you may drag panels to another place and resize them, for yourself;
  **Reset layout** goes back. Such a screen does not offer layout mode (`Alt+L`): the grid replaces it.
- **Your page.** With the Sutra designer on, **Customize** in a page's header changes the page for you alone (move, hide, columns,
  what a click opens, refresh, letting people edit); the header then says *Your page*, whose link opens the pack's page.
  [SCREEN_DESIGNER.md section 31](SCREEN_DESIGNER.md#31-the-sutra-designer-customize-describe-outline-and-grid) and
  [tutorial 22](../tutorials/22-build-a-screen-with-the-designer.md).

## Zoom a panel

A chart you want to read closely, a table with many columns, a pivot you are slicing: **zoom** expands one panel to fill
the view area, and **restore** puts it back. Nothing is saved to your layout and the Sutra is untouched.

![A panel's heading with its zoom button, next to the help (?) button](img/zoom/01-button.jpg)

Every panel's heading has a **zoom button** (the arrows icon, *Expand Hypothetical P&L, last 60 days*) next to the
help (?) button. Click it, or press `Z` while the panel has the focus (use `F2` to `F6` to focus a panel, or `Tab`).

![The chart panel zoomed: it fills the screen between the top bar and the function keys](img/zoom/02-chart.jpg)

While a panel is zoomed:

- it fills the view area **below the top bar and above the function keys**; the page behind it does not scroll;
- the focus is in the panel, and the other panels are inert (neither focusable nor read by a screen reader) until you
  restore; only one panel is zoomed at a time;
- it stays **live**: ticking values, charts and tables keep updating, and a chart redraws at the larger size;
- a table **fits the height**: the pager's page size is set to the number of rows that fill the panel (the size box reads
  *Fit (37)*), so there is no scrollbar and no blank space under the rows, and the page keeps the row that was at its top.
  It is recomputed when the window is resized, when you switch a tab inside the panel and when live updates add or remove
  rows. Pick a size yourself and it is kept until you restore (choose *Fit* to go back); the fit is never saved, and
  restoring puts back your own page size and the page with the same top row. A pivot, ladder, surface or graph use the
  height as usual;
- the button now reads *Restore <title>* and is pressed; pressing it again, `Z`, or `Esc` restores the panel and puts
  the focus back on its button.

![A table zoomed: all its columns and as many rows as fit](img/zoom/03-table.jpg)

| Key | Does |
|---|---|
| `Z` | On a focused panel (not in a text field): zoom it. While zoomed: restore |
| `Shift`+`Z` | Zoom the panel and ask the browser for **true full screen**, for a wall display (nothing happens where the browser has no full screen) |
| `Esc` | Restore. An open drawer (About, raw JSON), popover or menu closes first; a second `Esc` restores |

**Share or reload a zoomed panel.** The address gains `#zoom=<panel id>` (for example `/v/var/VAR-EQD#zoom=pnl`):
reload the page, or send the link, and the panel opens zoomed. An id that is not on the page is ignored.

**Layout mode** (`Alt+L`) and zoom exclude each other: turning layout mode on restores a zoomed panel first, and the
zoom buttons are hidden while you arrange the view.

**On a phone** the zoomed panel fills the screen under the top bar, and the button is a finger-sized 44 pixel target.

![A zoomed chart on a phone](img/zoom/04-phone.jpg)

**Print** prints the zoomed panel alone.

**In another application.** Inside `<drishti-view>` the panel fills the element's own box, not the host page; the keys
work only while the focus is inside the element ([Drishti Elements, keyboard](../architecture/ELEMENTS.md#58-keyboard)).

## The Pivot tab: slice a table your way

Some tables can be turned into an **Excel-style pivot**: drag *Product* down the side, *Maturity bucket* across the
top, *Notional* into the cells, and read the totals; click any number to see the trades behind it. Such a table has a
**Table | Pivot** switch above it. Search results and pick lists of some kinds have one too.

The switch is there **only where it was asked for**: a Sutra author adds `pivot:` to a table or ladder
([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#pivot-a-pivot-tab-on-a-table-or-ladder)), or a pack adds it to a kind's
search results ([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#pivot-a-pivot-tab-on-search-results)), and chooses which fields you may use and
how it opens. Every other table stays a table. In the seven packs: a netting set's member trades (notional by product and maturity bucket, counterparty-risk), a desk's trades
(core), collateral balances, a counterparty's and a legal entity's trades (notional by product type and source system, trading), and trade
searches (the trading pack's `pivot:` on `trade`: source system, portfolio, currency, product group and type, side, status, counterparty).

![The Pivot tab on a netting set's member trades: currency and product down the side, asset class and maturity bucket across, notional summed](/static/img/guide/pivot-netting-set.png)

### Opening it

Click **Pivot** above the table (or `Tab` to the switch and press `→`; `←` goes back to **Table**). The first time,
the console reads **every row** of the panel, not only the rows the table shows: a netting set table showing its first
four trades pivots all of them. The pivot opens as the Sutra (or pack) arranged it, or as you saved it last.

Under the switch: the **field list** and four **zones**, a toolbar, a status line (*42 rows · source records · 1 ms*)
and the grid.

### Fields and zones, with the mouse

| Zone | What a field there does |
|---|---|
| **Rows** | Its values go down the side, one group per value; a second field nests inside the first |
| **Columns** | Its values go across the top; a second field nests inside the first |
| **Values** | The numbers in the cells. Each has two choices: **sum**, **count**, **avg**, **min**, **max** or **distinct** (how many different values), and **value**, **% of row**, **% of column** or **% of total** |
| **Filters** | A chip reading *Currency: all*: click it for a pick list of the values (search box, **All**, **None**, tick what to keep) or, for numbers and dates, a **from** and **to**; **Apply** |

Drag a field from the list into a zone (the zone lights up where it will land), drag a chip to another zone or to
another place in its zone, or back to the field list to remove it. A field may be in Rows or Columns, not both, and a
numeric field can be in Values more than once (sum of MTM and count of MTM). At most 4 fields in Rows, 4 in Columns
and 6 values. Fields marked `#` are numbers.

### Fields and zones, with the keyboard

| Key | On | Does |
|---|---|---|
| `←` `→` (`Home`, `End`) | the Table / Pivot switch | Switch between the table and the pivot |
| `Tab`, then the arrows | the fields | Move between chips |
| `R` `C` `V` `F` | a chip | Put the field in Rows, Columns, Values or Filters |
| `Delete` (or `Backspace`) | a chip in a zone | Take it out of the zone |
| `Alt`+`↑` `↓` (or `←` `→`) | a chip in a zone | Move it earlier or later in its zone (outer or inner level) |
| `Enter` or `Space` | a chip in the field list | Add it: a number to Values, anything else to Rows |
| `Enter` | a value's chip | Go to its aggregation and show-as choices (`Esc` comes back) |
| `Enter` | a filter's chip | Open its pick list (`Esc` closes it) |
| arrows, `Home` `End`, `PgUp` `PgDn` | the grid | Move from cell to cell |
| `Enter` or `Space` | a cell | Show the rows under it |
| `Enter` or `Space` | a value's heading | Sort the rows by it: ascending, descending, then back to key order |
| `Esc` | the rows under a cell, the promote panel | Close it |

Every change is announced to screen readers (*Currency moved to Rows*, *Rows sorted by Sum of Notional, largest
first*).

### The grid

- **Totals.** Each group has a subtotal row (*Total GBP*) and column; the last row and column are the grand totals. Untick
  **Totals** to hide them. The totals cover every row, including groups beyond the grid's limits.
- **Groups open and close.** ▾ beside a group closes it to its total (▸ opens it); **Collapse all** and **Expand all**.
- **Sort.** Click a value's heading: within each group the rows are sorted by it, largest first or smallest first.
- **Group order.** Groups come in natural order: numbers by value, and `0-1Y`, `1-2Y`, `2-5Y`, `5-10Y`, `10Y+`,
  `BOOK-9` before `BOOK-10`; a missing value is *(blank)*, last. A number used as a group (a notional in Rows, say) is
  written plainly, as `42408726.01`, never `4.240872601E7`, and to at most 15 significant digits, the same in a panel's
  pivot and a search's.
- **Heat.** Tick **Heat** to shade each cell by its value: one colour for a range of positive numbers, two (gains and
  losses in the theme's colours) when the values have both signs. The number is always written; the shades keep the
  text readable in every theme.
- **Shares.** With **% of row**, **% of column** or **% of total**, a cell is its share of its row's total, its column's
  total or the grand total (the grand total itself reads 100%).

### Rows under a cell, the chart, export, print

- **Drill down.** Click any number (totals too), or press `Enter` on it: a dialog lists the rows behind it (*Rows under
  GBP · Equity barrier option*) with every field, ids as links that open the entity. `Esc` closes it.
- **Chart.** Choose **Bar chart**, **Line chart** or **Heatmap** in the toolbar (and which value, when there are
  several): a chart of the grid as it is now, in the theme's colours, redrawn when you change anything.
  To chart the table itself, with more types and two axes, use its chart button or `K` ([Chart any table](#chart-any-table)).
- **CSV**, **Excel** and **JSONL** (JSON Lines, a row a line keyed as the CSV's header) download the grid as shown: one column per row field, then one per column group and value, the
  subtotal and total rows labelled, numbers as numbers. The Excel file has a bold, frozen header row.
- **Print** prints the pivot and its chart alone, in the print palette.

### Save, reset, and what follows you

**Save** keeps your arrangement (rows, columns, values, filters, heat, chart) on the server, for you: it belongs to the
**Sutra and the panel**, so every netting set that Sutra lays out opens its Pivot tab your way, on any browser. A
search's pivot is kept per kind (every trade search). **Reset** goes back to the arrangement the Sutra or pack chose
and forgets yours. Nobody else's screen changes.

Keeping a pivot needs no special power: you may arrange the pivot of anything you may open, as you may sort a table.
When the Sutra changes, an arrangement that names a field it no longer offers is set aside, and the tab opens as the
Sutra says.

### Promote to Sutra

If your role may author Sutras (and saving is switched on), the toolbar has **Promote to Sutra…**. It saves your
arrangement and opens a panel with the changes in words (*the pivot of 'trades' opens with rows currency, product (was
product)*) and the diff of the **next version** of the Sutra: only that panel's `pivot:` changes, written as you
arranged it; its fields and everything else in the file stay as written. Add a note and **Submit for review**: an
approver reviews it as any save from the workbench ([Reviews](#reviews-approving-a-sutra)), and once approved everyone's Pivot tab
opens that way (those who saved their own keep theirs). With review off the button reads **Publish**.

### Search results: computed on the server

On a search or pick list whose kind offers a Pivot tab (`TVAL where MTM > 0`: 7579 of the 15000 trade valuations), the pivot is not limited to the 100
results the page lists: the server aggregates **every entity the search matches** on the business date and sends only
the cells. It reads the day's **columns**, the fields a large store keeps beside each document (a bank's trade table
keeps `PORTFOLIO`, `DESK`, `CURRENCY`, `MTM` and a dozen more), so a whole book pivots without a document being read.

- A field no source keeps as a column is marked **doc**. Using one, or searching a kind whose store keeps no columns
  (the sample data), the server says so instead of guessing: *no source of trade keeps its fields as columns here. Ask
  for a document read …*. **Read documents instead** computes from the documents themselves, at most 20,000 of them;
  when there were more, the status line says *partial: the first 20000 documents*.
- **Masked fields stay masked.** A field your role may not see (as in a search) groups under `•••` and is never added
  up, and so does a field under a masked one (`KYC.PD_1Y` when a counterparty's `KYC` is masked); the status line says
  *hidden from your role: Book*.
- The rows under a cell come from the server a page at a time (**Previous**, **Next**).

![The Pivot tab on a trade search: a measure by portfolio and currency over the matching entities, read from the columns](/static/img/guide/pivot-search.png)

### Worked example

With the counterparty-risk pack, open `NSET NS-ALDERSHOT-LDN`:

1. On *Member trades*, click **Pivot**. You see notional by product down the side and maturity buckets across
   (`0-1Y` … `10Y+`), with totals: how much of the netting set's notional matures when.
2. Drag **Currency** into Rows, before *Product*: each currency is a group, with a *Total* row.
3. Drag **Asset class** into Columns: each asset class spans its maturity buckets, with its own total column.
4. Set the value's second choice to **% of total**: each cell is its share of the netting set's notional.
5. Drag **MTM (USD)** into Values: two numbers per cell.
6. Click *Currency: all* in Filters, **None**, tick `USD`, **Apply**.
7. Click the largest number: the trades behind it, with links. `Esc`.
8. Choose **Bar chart**; then **Excel** to download the grid.
9. **Save**, and open `NSET NS-ALDERSHOT-NY`: its Pivot tab opens the same way.

The same keyboard-only: `Tab` to the switch, `→`; `Tab` to *Currency* in the field list, `R`; to *Asset class*, `C`;
to *MTM (USD)*, `V`; to a cell, `Enter`.

### Limits

A panel's pivot reads at most 50,000 rows (*the first 50000 of N rows* when cut short); a pivot shows at most 2,000
innermost row groups and 200 column groups (further ones are still in the totals, and the status line says *more row
keys than shown*); a search's drill-down shows 50 rows a page. An administrator sets these (`drishti.pivot`,
[CONFIGURATION.md](../admin/CONFIGURATION.md#drishtipivot--the-pivot-tab)) and can switch every Pivot tab off.

## Keyboard

| Key | Does |
|---|---|
| `/` | Focus the command line, from anywhere |
| `↑` `↓` | In the command line: move through the suggestions. In a selected table: move the selected row |
| `Tab` | Complete the highlighted suggestion |
| `Enter` | Open the highlighted suggestion, or run the command (`<GO>`). In a table: open the selected row |
| `PgUp` `PgDn` | In a selected table: move the selection a page up or down |
| `Home` `End` | In a selected table: select the first or last row |
| `Esc` | Close the suggestions, a menu (a column's filter menu included), the raw JSON drawer, About this page or Calc; then restore a [zoomed panel](#zoom-a-panel) |
| `Z` | On a focused panel: [zoom](#zoom-a-panel) it, or restore it; `Shift`+`Z` also asks for full screen |
| `Alt`+`Shift`+`X` | Clear all [column filters](#filter-a-table-like-excel) (and the filter box) of the panel that has the focus |
| `K` | On a focused table: [chart it](#chart-any-table); `Shift`+`K` on a history page, where `k` is the previous day |
| `F1` | Help for the page you are on. On a view it opens [About this page](#about-this-page); `F1` again goes to the guide |
| `?` | On a view (focus not in a text field): open or close [About this page](#about-this-page) |
| `F2`–`F6` | Jump to the panel that shows that key in its header |
| `Alt`+`Shift`+`1`…`9`, `0` | The same as `F1`…`F9`, `F10` on a view. On Windows a laptop's F-keys are media keys unless `Fn` is held (or Fn Lock is on, often `Fn`+`Esc`), and Edge keeps `F9` for its Immersive Reader: these always work |
| `F7` | Open the main linked entity (for a trade: its netting set) |
| `F8` | Impact: what depends on this entity |
| `F9` | Raw JSON of the entity, with its source and generation |
| `Alt+←` | Back |
| `Alt+C` | Calc: Python on this view (where a pack offers it; `Option+C` on a Mac) |
| `Alt+L` | Layout mode: arrange this view's panels for yourself (`Option+L` on a Mac); in layout mode, [its keys](#with-the-keyboard) |
| `Alt+S` | Share this view (or panel) with people with a note; `Ctrl+Enter` sends, `Esc` closes |
| `Alt+N` | Discussion: the second tab of the side drawer ([Discussion](#discussion)) |
| `Alt+I` | Your inbox: what people sent you |
| `R` `C` `V` `F`, `Delete`, `Alt+↑` `↓` | On a field of a [Pivot tab](#the-pivot-tab-slice-a-table-your-way): move it to Rows, Columns, Values or Filters, remove it, reorder it ([all its keys](#fields-and-zones-with-the-keyboard)) |
| `Ctrl+Enter` | In Calc: run the code (`Cmd+Enter` on a Mac) |
| `Alt+1`…`Alt+4` | In a workspace: move to pane 1 to 4, also while the focus is inside a pane |
| `Alt+0` | In a workspace: from a pane back to the workspace's bar |

The keys a view offers are also buttons along the bottom of the screen, so you can click them.

### F9 · Raw JSON

Press `F9` in `TRD MX-20000001`. A drawer opens on the right with the full document, starting
`{"SOURCE_SYSTEM": "MUREX", "SOURCE_UPDATE_TIME": "2026-10-07T18:30:00Z", "TRADE_DATE": "2025-07-25", …, "TRADE_ID": "MX-20000001", …}`, and its
source (`trade-murex`) and generation, as the same foldable tree as the Raw document viewer (see [A number over time](#a-number-over-time));
**Search · copy · download** at its top opens that viewer. Press `Esc` to close it. If your role does not have raw access, fields such as `TRADER_ID` read `•••`, here
and everywhere else
(see [Masked fields](#masked-fields)).

### Masked fields

Your administrator can mask fields (`drishti.security.redact`; by default `TRADER_ID`, `TRADER_FIRST_NAME` and `TRADER_LAST_NAME`) for roles
without raw access. A masked field reads `•••` everywhere you could see or work out its value: the raw JSON, search
results and their CSV, compare and history, Calc, pivots (grouped under `•••`, never added up), and views: the header
strip, the title, tables and their totals, the Pivot tab's rows, charts (masked points are left out), anything a
layout computes from the field, the live updates, monitors and the panel CSV export. Impact (`F8`) does not list what
is tied to the entity only through a masked field, and shows a masked measure and its total as `•••`. The
type-ahead neither shows nor matches a masked value, and an alert on a masked field never fires.

You cannot find a masked value by trying: a condition on a masked field is never true, sorting by it does not sort,
and typing it finds nothing. Fields under a masked one are masked too (with a counterparty's `KYC` masked,
`KYC.PD_1Y` reads `•••`).

### Alt+C · Calc: Python on a view

Press `Alt+C` on `VAR VAR-RATES`. A panel slides in from the right, like F9's, with a Python editor. The first time
in a tab, Python starts (*Python ready*, about a second). Pick **VaR and expected shortfall from the scenario P&L**
in **Snippets ▾** and press `Ctrl+Enter`: a table shows the VaR recomputed from the 500 scenario P&Ls
(**94,397,741**, the fifth-worst of the 500, the same as the strip's *VaR 99% 1D* `94.4m`), the expected shortfall, and a histogram. `view` is the screen (`view.doc` its
document, `view.tables` its tables as pandas DataFrames); `drishti.search("TRD where …")`, `drishti.get(kind, id)`,
`show()` and `chart()` are ready.

The code runs in your browser, never on a server, and reads only what you could open by clicking. The key is offered
where a pack switches Calc on (trading, counterparty-risk and market-risk do, through roles with `calc: true`: `trader`, `credit-risk`,
`market-risk`) and needs a role with the `calc` power; without
one it is shown greyed, with the reason. Everything about it, from the `drishti` module to the limits, is in
[PYTHON_CALC.md](PYTHON_CALC.md).

### F8 · Impact

Impact answers "what depends on this, and how much is at stake?". Open `TRD MX-20000001` and press `F8`.
You should see *Impact of MX-20000001*:

- **Depends on it directly:** *Desk · 1*: `DESK-RATES`; *Netting set · 1*: `NS-MERIDIAN-RE-NY` with its net MTM (`+99,704,064`);
  *Trade valuation · 1*: `TVAL-MX-20000001` with its MTM (`+1,876,068`).
- **Rolls up into:** *Credit limit · 1*: `LIM-MERIDIAN-RE` via `CREDIT_LIMIT`, with the limit amount (`7.4bn`).

Each group has a total. Click any id to open it. With [masked fields](#masked-fields), Impact lists only what you
could find yourself, and a masked measure and its total read `•••`. See the
[Impact guide](#f8--impact).

## Live updates

Views of live entities tick: figures change in place and flash briefly. With the demo source on
(`DRISHTI_DEMO_ENABLED=true`; the demo lakes are daily snapshots and do not tick), open `TVAL TVAL-MX-20000001` and watch
**MTM** for a few seconds.

- The round **Live** button glows while the view streams (an amber ring while it reconnects). Hover over it to read
  how long the view took to build (`Live, built 1.92 ms`).
- All views, panes, the bell and monitors in **all the tabs of one browser** share **one** connection, so you can
  keep as many live tabs and workspaces open as you like (one per screen, say): new pages still load at once.
  A view open in several tabs is followed once. One browser follows at most 32 live things at a time; a view over
  that says *Static*, and why.
- The dot turns **amber** when the connection dropped or cannot be had (*Reconnecting…*): the page reconnects and
  repaints by itself, and everything else on the page keeps working meanwhile.
- It also turns amber when the tab was in the background for 10 seconds and gave its live updates back
  (*Paused while hidden*). Show the tab and it resumes. A tab left from before you signed in again (or picked
  another server) stays amber: reload it.
- It is **grey** on a view of a picked past date, which never ticks: it is a snapshot.

Screen readers hear the full state, including the server's rolling p99 (`Live, p99 2 ms`). To see the same
figures yourself, ask the server: `curl -s http://localhost:18480/api/v1/health/live`
([PERFORMANCE.md](../admin/PERFORMANCE.md#4-live-latency)).

To turn off the flash, untick *Flash changed values* in [your settings](#your-settings).

## Business dates: live or a day in the past

The **Business date** box in the top bar says which day you are looking at.

- **Live** (the default) is today's business date on the New York calendar (`USNY`), streaming.
  Weekends and holidays count as the business day before.
- **A picked date** shows that day's end-of-day data as a static snapshot. The box turns amber.

The round **Live** button beside the box is a toggle, the same on a laptop and a phone:

| The button | Means | A click (or Enter or Space) |
|---|---|---|
| green, with a filled dot | live updates are on | pauses: the page becomes a static snapshot of the current business date |
| red, with two pause bars | paused, or a picked date | resumes live on today's business date |

You can still pick another date while paused. The choice is kept as you move between pages. A screen reader hears
*Live updates on — click to pause* or *Live updates paused — click to resume*, which is also its tooltip.

Step by step, with pictures (a past date, the same layout with that day's figures, a Saturday, back to Live): [tutorial 2](../tutorials/02-look-up-anything.md), step 5. It needs the sample history ([GETTING_STARTED.md](GETTING_STARTED.md), Step 5).

What the banners mean:

| Banner | Meaning |
|---|---|
| *No data held for 2026-09-29: no dated store has … for that date. Shown instead: the current data of …* | No dated store holds the entity for the day you picked (for example, the history lake was not built, or does not reach back that far), so a source that keeps no dates answered. Its current data is shown as a still snapshot: it does not update and the top bar does not say *Live* |
| *Latest data on or before 2026-09-29 is from 2026-09-25* | Nothing was written for the day you picked; you see the last earlier day |
| *Pick a business date from 2021-09-30 to today.* | The date you typed is not a real date, is in the future, or is older than the server keeps. It was not taken: you stay on the date you had |
| *The business date you had picked cannot be used, so this is live. …* | A date remembered from earlier is no longer one the server accepts (for example after its history window moved on). The console has forgotten it and shows live |

### Known at: time travel

With a date picked, the clock field beside it (**Known at**) asks *as known when?*. Enter a time
(New York time) to see that business date as the system knew it at that moment: corrections made later
are left out. The **×** clears it and returns to the latest knowledge.

Use it to answer "what did we report at 6 pm, before the restatement?". Sources that keep versions
(Delta Lake, Iceberg) honour it. A dated store that keeps no versions (files, a database, Redis …) says so
instead (`DRS-1007 … keeps no earlier versions`) rather than showing today's data as if it were what was known then;
a search names it and says *partial*. Undated sources have only one version and show it.

## Compare: what changed

Compare lists every field that differs in one entity between two business dates.

Click **Compare** (it appears in the title line when you have picked a date): *What changed in MX-20000001* compares the picked date with the business day before. Each row shows the field's label and path, the value on each date and the
change for numbers; choose any two dates at the top to compare further apart, or give the earlier side a *known at* time to see what was restated on the same date; filter to *changed*, *added* or *removed*; **CSV** downloads
the comparison. Walk-through: [tutorial 2](../tutorials/02-look-up-anything.md), step 6.

Example rows for the valuation `TVAL-MX-20000001`, 2026-10-15 against 2026-10-16:

| Field | Path | Before | After | Change |
|---|---|---|---|---|
| MTM (USD) | `MTM` | 1,911,897 | 1,876,068 | −35,829 |
| DV01 | `DV01` | −155,791 | −155,245 | +546 |
| Valuation date | `VALUATION_DATE` | 2026-10-15 | 2026-10-16 | |

An amended trade compares the same way: `MX-20000022` between 2026-10-07 and 2026-10-16 shows *Trade status* `VALIDATED` → `AMENDED`,
*Trade action* `NEW` → `AMEND`, *Trade version* `1` → `2`, a new *Transaction ID* and *Source update time*.

Lists are matched by their identifiers or natural keys (a tenor such as `3M`, a date, a code), so an
inserted cash flow shows as one addition, not as every later cash flow changed: a path reads `LEGS[1].CASH_FLOWS[5000037]` (the
flow's id) rather than `LEGS[1].CASH_FLOWS[0]`.

The same comparison is available to programs:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$DRISHTI_SERVER/api/v1/history/trade-valuation/TVAL-MX-20000001/diff?from=2026-10-15&to=2026-10-16"
```

It also takes `fromKnownAt` and `toKnownAt`. See [API_GUIDE.md](API_GUIDE.md).

## History: an entity over time

Type a range after the id (`TRD 1000015 30 <GO>`, see [The command line](#the-form-of-a-command)) and the screen becomes that entity
over time. The address is `/v/trade/1000015/history?days=30` (`?range=3M`, `?from=2026-09-01&to=2026-09-30`, `&diff=1`); the short
form `/v/trade/1000015?days=30` opens the same page. After an id, a space in the command line offers the ranges: `30`, `3M`, `YTD`,
`30 DIFF`.

**The header** names the entity, the range (*30 business days*), the first and last dates, how many days have data, how many changed,
how many were restated, and the days in the range with no data (hover *no data on 1 day* for the dates). *known: latest* means each day
is shown as it is known now. When a calendar range (`3M`, `FROM … TO …`) runs past the newest day the sources hold, the header says
*not loaded yet after 2026-10-16* rather than counting those days as missing.

**Trades and other documents** show one row per business day, newest first, with the kind's top-level fields as columns and each
list (legs, events) as a count with `▸`. A cell that differs from the day before is tinted and in bold, with `▲`/`▼` for numbers;
hover it for the value it had (*changed from 72K*). What stayed the same is dimmed, so the moves stand out. `⟲2` beside a date means the
store kept two versions of that day: the row is the latest. Masked fields read as the mask on every row, exactly as in the view.

**Equities, FX, curves and other market kinds** add the headline numbers of the chart's field (*Last*, *Chg*, *Chg %*, *High* and *Low*
with their dates, *Range*, *Avg*) and a chart in the theme's colours above the same table. The chart's crosshair follows the selected
row. The range buttons (`1M` `3M` `6M` `YTD` `1Y`) ask again; the address follows, so *Back* works and a link reproduces it.

**Diff** (`D`, or `DIFF` at the end of the command) turns the table into a change log: one block per day on which something changed,
each change on its own line (`Quantity 72,000 → 75,000`), with *Compare* (the full [compare page](#compare-what-changed) for that day
and the one before) and *Open the day*.

**Keys** (shown with `?`; ignored while you type in a field):

| Key | Does | Key | Does |
|---|---|---|---|
| `↑` `↓`, `j` `k` | previous, next day | `Home` `End`, `PgUp` `PgDn` | first, last, a page |
| `←` `→` | previous, next column (read aloud with its header) | `Enter`, double click | open the day's view, as it was that day |
| `C` | changes only: hide the days on which nothing moved (*7 of 30 days*) | `D` | diff, on and off |
| `R` | type a new range (`30`, `3M`, `YTD`, `FROM 2026-09-01`) | `1` `3` `6` `Y` `T` | 1M, 3M, 6M, 1Y, YTD |
| `E` | export: CSV, Excel, JSON Lines, JSON | `Alt+S` | copy a link to these rows |
| `Shift`+`K` | chart these days: any column by date ([Chart any table](#chart-any-table)) | | |
| `?` | the keys | `Esc` | back to today's view |

**Open the day.** `Enter` opens `/v/trade/1000015?asOf=2026-10-15&gen=59&via=history`: the normal view as of that day, with the pinned
banner (*as it was, from its history*, or *changed since the history read that day* when the data has moved on) and **Back to the
history**, which returns to the same range (the view's back link does too).

**Market kinds.** The table's numbers read in the chart field's format (`price2`: `21.66`), as the chart and the headline do. On a row from
the embedded series (a day before the stored documents), the parts' columns read `—` (*not read on this day*), not `0`. The chart's hover
label stays inside the chart and follows the selected row when the window is resized or zoomed; the crosshair's date is in the label and the
`⌖` line, not on the axis.

**Export** (`E`) downloads the table as shown, newest first: CSV (numbers as numbers, the formula guard, UTF-8 with BOM), Excel, JSON
Lines (a day a line, keyed as the CSV's header), or the server's JSON as served (its rows oldest first). Excel's dates are date cells (`yyyy-mm-dd`), so they sort and filter as dates. With
*changes only* on, only the changed days, in all three. Files are named `1000015-history-2026-09-07-2026-10-19.csv`.
Every export reads through the server, so it carries your masks.

**Link** (`Alt+S`) copies a link that pins the last date (`…/history?days=30&to=2026-10-19`), so a colleague sees the same rows a week
later; with *changes only* on it keeps `&only=1`. A link without `to` moves with the business date. The link is also shown, selected,
in a field under the toolbar, with *Copied* beside it: on a console opened by its plain-http LAN address the browser offers no clipboard
to the page, so Drishti copies from that field instead (and says *Select the link and copy it* if the browser refuses even that).

**When there is nothing to show** the page says why and offers today's view: an unknown entity (`DRS-1001`), a kind that keeps no daily
history here (`DRS-1012`), no data in the range (`DRS-1013`), a range larger than allowed (`DRS-4021`, with a link to the last 30 days)
or one that cannot hold (`FROM` after `TO`).

## How fresh is it

The footer of every view names the source and says how long ago it last received new data: *trade-murex ·
updated 42 min ago · gen 517 · as of 2026-10-16*. The age keeps counting while the page is open. When a source has received nothing for
longer than it should (its `stale-after`, set by your administrator), the view shows an amber banner at the top:
*trade-murex is behind: its last new data was 25 min ago, and it expects some within 15m. What you see may be out
of date.* Sources read on demand (a database) cannot tell, and show no age.

## A number over time

Click a number in a view (a strip value, a field, a cell of a table) and a window shows that field over the last 30
business days: a line chart, and the values day by day with the date the data is for and the connector it came from.
Choose 10, 30, 90 or 250 days at the top. Links still open what they name; a number that is a link opens the link.
The first line says where the value comes from: the path in the document, the connector, the business date the data
is for and the source's generation, with a link to the raw document. **Raw document** opens the document on the same page, in
a viewer: a tree you fold and unfold, with the field you clicked highlighted and scrolled to (`$.SLOPE_2S_10S` for the 2s10s of
`CRV-USD-OIS`). Search filters keys and values; **Copy JSON** copies the whole document and **Copy node** the selected one;
**Download** saves `<kind>-<id>-<date>.json` (`ir-curve-CRV-USD-OIS-2026-10-19.json`). Long lists show their first 50 items and a
*Show all*. Masked values read `•••`, as everywhere. `Esc` closes it and puts you back on the link; on a phone it fills the screen.
`Ctrl`+click (or a middle click) still opens the plain JSON in a new tab, and the `/api/raw/...` address keeps working.

Worked example: open `TVAL TVAL-MX-20000001`, click the MTM in the strip. You see *MTM (USD) · TVAL-MX-20000001 over time*, a line
over 30 business days, and below it the values, newest first, each with *Data for* and *Source* (`risk-store`, the demo lake of the
market-risk pack; `+1,876,068` on 2026-10-16, `+1,908,960` on 2026-10-07).
A *Data for* date in amber is earlier than the day it is listed under: the source carried its last value forward
(no data that day). An entity no dated source holds shows today's value with a note, since no history is kept.

**A pick list or search on two dates.** On the search page, set **Compare with** to an earlier business date and
press Search: each row shows today's value of every number with its change in brackets (`125  (+25)`), and entities
only one of the dates holds are marked *added* or *removed*.

The same through the API: `GET /api/v1/history/{kind}/{id}/series?path=$.MTM&days=30` and
`GET /api/v1/search/compare?q=…&from=2026-10-07&to=2026-10-16`.

## Asking in plain words

Type what you want in the command line, in words: `amended trades over 5m in BOOK-RATES-3, biggest first`, and press
Enter. When the words are not a command, Drishti shows the search they make and how it read each part; nothing runs
until you press **Run it**. The search page has the same box, **In words**.

```
amended trades over 5m in BOOK-RATES-3, biggest first
→ TRD where TRADE_STATUS = 'AMENDED' and QUANTITY > 5000000 and PORTFOLIO = 'BOOK-RATES-3' order by QUANTITY desc
   "trades" → Trade (TRD) · "amended" → trade status is AMENDED · "over 5m" → quantity > 5000000
   "BOOK-RATES-3" → portfolio is BOOK-RATES-3 · "biggest first" → sorted by quantity, largest first
```

It understands:

| You write | It means |
|---|---|
| a kind: `trades`, `counterparties`, `desk pnl`, a mnemonic | what to list |
| `over`, `above`, `more than`, `under`, `below`, `at least`, `at most` and a number (`5m`, `2.5 bn`, `10k`, `1,000,000`) | a comparison, on the field you name or the kind's main number (a trade's quantity, a valuation's MTM, an amount…) |
| `between 10m and 50m` | both bounds |
| `negative mtm`, `positive pnl` | below or above zero (`trade valuations with negative mtm` → `TVAL where MTM < 0`) |
| a value the data has: `amended`, `USD`, `BOOK-RATES-3` (optionally after `in`, `for`, `with`) | that field equals it |
| `maturing before 2028`, `traded after 2025`, `maturing in 2027`, `before 2026-12-31` | dates |
| `biggest`, `top 10`, `smallest 5 … by quantity`, `newest`, `sorted by` | the order, and how many |

**One entity over time, in words.** A kind, an id and a time phrase make a [history](#history-an-entity-over-time) command
instead of a search. When every word was read, it opens at once; otherwise the page shows *Read as …* with an **Open** button.

```text
trade 1000015 last month                    TRD 1000015 FROM 2026-09-01 TO 2026-09-30
equity EQ-CSCA last 3 months                EQ EQ-CSCA 3M
trade 1000015 last 30 days                  TRD 1000015 30
equity EQ-CSCA this year                    EQ EQ-CSCA YTD
trade 1000015 since 2026-10-01              TRD 1000015 FROM 2026-10-01
trade 1000015 since 2026-10-01 until 2026-10-10   TRD 1000015 FROM 2026-10-01 TO 2026-10-10
what changed on trade 1000015 this week     TRD 1000015 FROM 2026-10-19 DIFF
fx audusd since 2026-10-01                  FX FX-AUDUSD FROM 2026-10-01
1000015 30                                  TRD 1000015 30
BOOK-RATES-3 last week                      BOOK BOOK-RATES-3 FROM 2026-10-12 TO 2026-10-16
fx audusd                                   FX FX-AUDUSD (today's view)
```

Time phrases: `last` or `past N days|weeks|months|years`, `last week|month|year` (the whole previous one), `this week|month|quarter|year`,
`ytd`, `since <date>`, `from <date> to <date>` (or `since … until …`; two dates are put in order), `between <date> and <date>`. `changed` or `changes` anywhere adds `DIFF`. The id is the word
that names one entity of the kind (its id in any case, or the end of its id after a dash: `audusd` is `FX-AUDUSD`), even when it is also
a value of a field (`EQ-CSCA` is the equity's ticker); else the one word with a digit or a dash that is not a value of one of the kind's
fields, so `trades in BOOK-RATES-3 since 2026-01-01` stays a search (`BOOK-RATES-3` is no trade). Without a kind word, an id that names one
entity followed by a range (`1000015 30`, `1000015 3M DIFF`) or a time phrase (`BOOK-RATES-3 last week`) is that entity's history.
A kind and one id with no time phrase open the entity (`fx audusd`). A search is of one business date, so a time phrase in a search
(`trades in BOOK-RATES-3 last week`) is listed as *left out*, with how to ask for one entity over time instead (`BOOK-RATES-3 last week`),
and the search does not run on its own; `since <date>` in a search is a condition on the date, as before.

It is deterministic: no AI service, only the server's own vocabulary (its kinds, and each kind's fields and the
values its documents have). Words it did not understand are listed as *Not understood, left out*, never guessed.
Change the words, or edit the search itself.

## Search by value

Add `where` after a mnemonic to find entities by what they contain.

```text
TVAL where MTM > 1m order by MTM desc limit 50
```

You should see a page headed *Search by value*, a summary line such as `3321 of 15000 trade valuations match; the first 50
are shown`, the time it took, and a table: one row per valuation, with a column for each field the query uses
(here **MTM (USD)**), then the kind's key fields. Click an id to
open it, or walk the table with the keyboard ([Tables](#tables-sorting-filtering-paging-and-the-keyboard)).

`where` is optional: `TVAL MTM > 1m` and `TRD PRODUCT_TYPE=FX_QUANTO` work as well, and come back as a
[pick list](#pick-lists-when-a-command-names-several-entities). The rest of this section applies to both.

### The parts of a search

```text
<MNEMONIC> [where <condition>] [order by <field> [desc]] [limit <N>]
```

| Part | Rules | Example |
|---|---|---|
| Mnemonic | Any mnemonic, or a kind name | `TRD`, `NSET`, `trade-valuation` |
| Field | As named in the document, in any case; dots for nested fields, brackets for list items. A name no entity of the kind has is an error with the closest names | `MTM`, `mtm`, `KYC.PD_1Y`, `LEGS[0].NOTIONAL` |
| Compare | `=`  `!=`  `<`  `<=`  `>`  `>=`; text compared with `=` and `!=` ignores case | `PRODUCT_GROUP = 'Rates'` (also matches `rates`) |
| Text | `contains` and `startswith`, case-insensitive; `contains` also looks inside lists | `COUNTERPARTY contains 'MERIDIAN'` |
| Combine | `and`, `or`, `not`, brackets. `not` applies to the comparison after it | `MTM > 1m and not TRADE_STATUS = 'MATURED'` |
| Amounts | `k` thousand, `m` million, `bn` billion | `250k`, `1.5m`, `2bn` |
| Text values | in single quotes; a single word may go without | `'Early warning'`, `Rates` |
| Sort | `order by <field>`, add `desc` for largest first. Equal values come in id order and entities without the field last, so a `limit` keeps the same rows every time | `order by MTM desc` |
| Size | `limit N`, a whole number from 1 to 1000; anything else (`limit 0`, `limit 5000`) is an error that gives the range | `limit 20` |

Without `limit`, a search shows the number of results set in *My account → Settings* (100 unless you
change it).

### Examples to try

| Search | Matches on the demo lakes (business date 2026-10-16) |
|---|---|
| `TRD where QUANTITY >= 250m and PRODUCT_GROUP = 'Rates' order by QUANTITY desc limit 20` | 783 of 15000 trades (the first 20 shown) |
| `TRD where COUNTERPARTY contains 'MERIDIAN'` | 1291 of 15000 trades |
| `TVAL where MTM < -1m order by MTM` | 3154 of 15000 trade valuations, most negative first |
| `TRD where PRODUCT_GROUP = rates` | 1832 of 15000 trades (case ignored, no quotes needed) |
| `NSET where UTILISATION > 0.5 order by UTILISATION desc` | 48 of 76 netting sets |
| `LIM where STATUS = 'Early warning'` | 2 of 24 credit limits |
| `ECON where GDP_USD_BN > 1000 order by GDP_USD_BN desc` | 21 of 213 economies |

A search whose condition matches exactly one entity still shows its one-row table; only a command typed
without `where` opens a single match directly.

### Good to know

- A search follows the business date in the top bar.
- It sees only what your role may see; a condition on a masked field never matches and sorting by one does not
  sort ([Masked fields](#masked-fields)).
- If the scan limit was reached, the page says *results may be incomplete*. If a source failed, did not answer
  in time, or could not list the kind, a banner says *Incomplete: 1 source could not be read* and names each source
  and why (for example a lake date in a format the server does not read): entities it holds are missing, so
  "0 matches" does not mean none exist.
- A mistake gives an error such as `DRS-4004 cannot read the condition: …` with the position. A condition that stops
  short (`TVAL where MTM >`) says `the condition ends early: something is missing after '>'`.
- **CSV** downloads the results. **Watch as a monitor** saves the first 50 results as a live monitor
  (see [Monitors](#monitors)).
- Where the kind's pack offers it, a **Table | Pivot** switch turns the results into a pivot over **every** match of
  the day, computed on the server ([The Pivot tab](#search-results-computed-on-the-server)).

## Search with a form (`FORM <pack>`)

Type `FORM <pack>` (or open **Forms** in the Views menu) to fill in boxes instead of writing a search: the form writes the search, shows it and runs it. A field with more than 300 values (the counterparties of a large book) shows a box to find them as you type instead of one tick box each. The reference is [QUERY_BY_FORM.md](QUERY_BY_FORM.md); the walk-through is [tutorial 3](../tutorials/03-query-by-form.md).

## Export, print and share

The buttons in a view's title line, and the **↓** in each panel header.

| Action | Where | What you get |
|---|---|---|
| Panel as CSV or JSON Lines | **↓** in a panel header, then **CSV** or **JSON Lines** | Tables and ladders as they are; key/value panels as field and value; charts as one column per series; surfaces as a grid; bars as label and value |
| Search as CSV or JSON Lines | **CSV**, **JSONL** on the results page (and on a form's results) | One row per result |
| Pivot as CSV, Excel or JSON Lines | **CSV**, **Excel**, **JSONL** in a [Pivot tab](#the-pivot-tab-slice-a-table-your-way) | The grid as shown: row fields, one column per column group and value, totals labelled; Excel with a bold frozen header |
| Comparison as CSV or JSON Lines | **CSV** on the Compare page; the same address ending `.jsonl` | One row per change |
| View as PDF, PNG, Excel, CSV or JSON Lines | **Export** in the title line | The view as you see it, with the controls and filters you set |

**JSON Lines** (`.jsonl`, [DATA_FORMATS.md](DATA_FORMATS.md)) is one JSON object a line, keyed by the CSV's column names, numbers as
numbers and no spreadsheet escaping: the form for scripts and other systems. It sits beside every CSV download; CSV, Excel, PDF and
PNG all stay.
| Document as JSON | **JSON** in the title line | The whole document, as your role may see it |
| Print or PDF | **Print** | A clean light page without the top bar and buttons; choose *Save as PDF* in the print dialog |
| Share | **Share** (`Alt+S`), or a panel's **send** icon | A [dialog](#share-a-view-with-a-note): send the view, or one panel, to people with a note; **Copy link** is in it |

Worked example (CSV): open `TRD MX-20000001` and click **↓** on *Cashflows*. You get a file whose first
lines are:

```text
Pay date,Leg,Type,Rate,Amount,PV
2025-09-29,2,Float,0.035377,-1403091,0
2025-12-31,2,Float,0.036186,-2310878,0
```

Numbers arrive as plain numbers (`-1403091`, `0.0425` for `4.25%`), so a spreadsheet can add them up.
File names carry the business date when one is picked. Each download also has a direct address, for
example `/export/trade/MX-20000001/schedule.csv` (`schedule` is the panel's id) or `/export/trade/MX-20000001.json`.

**Share** links:

- in Live, the link is the view's address, such as `http://localhost:17480/v/trade/MX-20000001`;
- with a date picked, the link carries it (`/v/trade/MX-20000001?asOf=2026-09-29&knownAt=…&gen=…`): the date, the
  *known at* moment if you set one, and the generation you saw. A colleague sees what you see, within their own
  permissions. If they are signed out, the sign-in page keeps the whole link and brings them back to it. The date applies
  to that page only: a *Pinned* banner says "You are seeing 29 Sep as known at …, as it was shared" (or that the source
  keeps no earlier versions so they see the latest for the date, or that the data has changed since, with *What changed*),
  and **Go live** drops it. Their own saved date, and their other tabs, are not changed.

### Share a view with a note

**Share** (or `Alt+S` on a view) opens a dialog. **Copy link** is the first thing in it, so the old one-click use is one
click plus `Enter`; the rest sends the view to people inside Drishti.

1. **What**: the whole view, or one panel (use the **send** icon in a panel's header to start with that panel chosen).
2. **As of**: shown, never typed. It is the date and *known at* moment you are looking at, so the people you send it to open
   exactly that; tick *send a live link instead* to send the live view. On a live page the link is live.
3. **To**: type two letters of a name or a role (`rav`, `risk`) and pick from the list with the arrow keys and `Enter`
   (or click). You can only find people who use at least one pack you use; a role stands for everyone in it (its size is
   shown). `Backspace` in the empty field removes the last name.
4. **Note**: plain text. Do not type the value of a hidden field: the server replaces it with `•••` for everyone who may
   not see it (you read your own text as written). If you can see hidden values yourself, you are warned when the note copies one; if you
   cannot, you are told nothing either way. A `{$.path}` quote is filled for each reader with the value they may see, in the app and in
   email.
5. **Also start a discussion on the view** (a tick under the note): the note also becomes the first comment of a thread on the view, written by
   you, so the conversation continues in the [Discussion](#discussion) tab. Whether it is ticked when the dialog opens is your administrator's
   setting (`drishti.collab.share.post-to-thread`); you can change it for each share.
6. **Include a picture** (a tick, shown only when your administrator allows pictures for this kind of view): the share carries a snapshot of the
   view, or of the panel you chose, with a watermark (who sent it, to how many people, when, the data's date and generation, and the link). Tick it
   and the dialog shows a **preview** for the people you chose. The picture shows only what *every* recipient may see: if any of them sees a hidden
   field as `•••`, so does the picture for all, and a panel any of them may not open is left out. Charts without a simple drawing appear as a labelled
   box that says to open the view. Recipients see it on the shared view's banner and, if email is on, as an attachment; it may show figures, only those every recipient may see, and it can never later show more than it did when you sent it.
7. **Send** (`Ctrl+Enter`). The dialog says who it reached ("Sent to 2") and who it did not and why ("Not sent to dkim:
   cannot open trade views"). `Esc` closes the dialog and puts the focus back where it was. On a phone the dialog is a full
   screen sheet.

![The Share dialog on MX-20000001: the view as of 2026-09-30, Vera Lim chosen and a note that names the trader](img/collab/01-share-dialog.jpg)

![After Send: the warning that the note holds the value of a hidden field, "Sent to 1 person", and Copy link still in the dialog](img/collab/02-share-sent.jpg)

What the recipient gets: a notice on the **bell** within a second (a number on the bell, and a toast) and a row in
their [inbox](#your-inbox). Opening it goes to the view **as you sent it**: your date and *known at* moment, with a
banner "Shared by Ann Author", your note, and the usual *Pinned* banner. A shared panel is scrolled to and outlined. They
see the view with *their own* rights: a field hidden from their role stays `•••`, and the note reads `•••` where you typed
a hidden value.

**Reply.** Under the note, the sender and each person the share reached have a **Reply** box (`Ctrl+Enter` sends). Replies are listed under the
note, oldest first, and the other side gets a notice. A compliance officer who opens the share can read the replies but not write one.

**Times.** Every time the collaboration pages show (inbox rows, the share banner and its replies, the *known at* time in the Pinned banner,
comment times, the administration page) is in the same zone as the top bar's *known at* picker, with its name after it: `2026-09-30 10:02 New York`.

![Vera's view of the share: "Shared by Asha Rao", the note with the trader's name replaced by three dots, and the Pinned banner](img/collab/05-shared-view.jpg)

![The same shared view on a phone](img/collab/06-shared-phone.jpg)

A person whose role does not open that kind of view (or who was sent it and then lost access) sees a plain page, "A shared
view you cannot open", with who sent it and when, and nothing about what it holds; their inbox keeps the notice. If the
reason is that the pack is switched off for them, the page offers **Switch on** *pack*. A link that was never sent to the
reader looks the same as a link that does not exist.

### Your inbox

The **bell** in the top bar counts what is new for you (shares sent to you, and alerts as they fire); click it, or press
`Alt+I`, for the inbox page. Each row says when, what kind (Share), who, and the start of their note, as you may read it
now. Click a row to open it (opening marks it read), **Mark read** on a row, or **Mark all read**. Filter by *All*,
*Shares*, *Mentions* or *Replies* and *Unread only*; **Older** pages back. Rules that fired are on the
[Alerts](#alerts) page.

![Vera's top bar: the bell shows 1 new notice](img/collab/03-recipient-bell.jpg)

![Vera's inbox: one unread Share row, "Asha Rao shared trade MX-20000001", with the start of the note as she may read it](img/collab/04-inbox.jpg)

## Monitors

A monitor is a **live watchlist**: one row per entity, each row showing that entity's key figures (its
strip) as they tick. Rows can be of different kinds.

*Views → Monitors* in the top bar (`/m`) lists *Yours* and the *Starters* the packs offer. Opening a starter
saves a copy to your account. (None of the shipped packs offers a starter, so start from a search; the retired example packs
`finance` and `logistics` under `examples/packs/` offer *Credit watch* and *Fleet watch*.)

Step by step (a search, **Watch as a monitor**, adding an entity, removing a row), with pictures: [tutorial 3](../tutorials/03-query-by-form.md) (save a search and a monitor).

The monitor is saved on the server under your account, so it follows you to any browser. All rows share
one live connection.

## Scheduled reports

A report is a search that runs on its own, on a schedule, and delivers its result as a CSV or JSON Lines file (**Format**); **Reports** in the Views menu lists them with your subscriptions. Export and subscriptions are in the same place. The reference is [SCHEDULED_REPORTS.md](SCHEDULED_REPORTS.md); the walk-through is [tutorial 9](../tutorials/09-reports.md).

## Alerts

An alert rule watches **one entity** and tells you when a condition becomes true. (To be told when a **number** in a BI report, a dataset or a view crosses a limit, moves too far or does not arrive, see [Alerts on numbers](#alerts-on-numbers) at the end of this section; both are on the same **Alerts** page.)

- The **server** checks every rule on every change of its entity, even when nobody has Drishti open.
- A rule alerts **once** when its condition becomes true, and re-arms when it turns false again.
- A rule that is already true when you save it alerts straight away.
- A rule sees the entity as you may see it: a condition on a [masked field](#masked-fields) never becomes true, and a
  message shows a masked value as `•••`.
- New alerts show as a **toast** and a count on the **bell** among the round tools of the top bar, on every
  page. Click the bell to see them all, and to allow browser notifications. *Views → Alerts* opens the same page.
  At most three toasts show at once; a burst adds one more toast, *+N more on the bell*, which opens the inbox. Only notices
  written after the page opened toast: older ones wait in the inbox and on the bell's count.

Worked example:

1. Open `NSET NS-SUMMIT-NY` and click **Alert** in the title line. The *Alerts* page opens with
   **Kind** `netting-set` and **Id** `NS-SUMMIT-NY` filled in.
2. Fill in **Name** `Summit utilisation`, **When** `$.UTILISATION > 0.5`, **Severity** `warn`, and
   **Message** `${$.NETTING_SET_ID}: ${fmt($.UTILISATION, 'pct0')} of limit`.
3. Click **Save rule**. The utilisation is already about 58 %, so you should at once see a toast, the
   bell showing `1`, and under *Recent alerts* a line `warn NS-SUMMIT-NY NS-SUMMIT-NY: 58% of limit`.
4. *Your rules* lists the rule; **Delete** removes it.

### Writing conditions

A condition is a Rachana-EL expression over the entity's document. `$` is the document; `$.field`
reads a field.

```text
$.UTILISATION > 0.8                            a netting set near its limit
$.MTM < -450000                                a trade valuation's MTM below −450k
$.NET_MTM < -1500000 && $.UTILISATION > 0.6    both at once
$.STATUS == 'Early warning'                    a credit limit's status
```

In a rule (unlike a search) write `==` for equality, `&&` for and, `||` for or, and full numbers
(`1500000`, not `1.5m`). The message is a template: `${…}` holds an expression, and `fmt(value, 'pct0')`,
`fmt(value, 'signed0')` or `fmt(value, 'compact')` format numbers. The full language is in
[RACHANA_REFERENCE.md](RACHANA_REFERENCE.md).

A pack may suggest rules for its kinds (`alerts:` in its `pack.yaml`; none of the shipped packs does, the retired example pack
`finance` suggests *PFE near limit* and *MTM below −450k*); they appear on the Alerts page and fill the form when clicked.

> **Tip:** a condition that cannot be evaluated (a misspelled field, say) is skipped, not alerted.
> Check a field's exact name with `F9` first.

Rules are kept with your account, and so are the alerts they fire: the newest 1,000 per user
(`drishti.alerts.keep`) are kept in the identity database, so they are still there after a server restart. More in the
[Monitors and alerts guide](#monitors).


### Alerts on numbers

Click **Alert me** (the bell in the corner of a visual of a BI report) or click a number on a view and choose **Alert me**: the form opens with that number filled in. Say when to tell you: when it goes **above** or **below** a limit,
**between** two numbers or **outside** them; when it **changes by** a percentage or an amount (against the previous look, or the previous business day); or when today's data **has not arrived by** a time. Say when to look (on a schedule,
after a data load, or when it changes), and how to tell you: your **inbox** and the bell, **email** to the address on your account, the **chat bridges** your administrator set up. *Check now* shows what it reads for you right now.

**It is your number.** The alert reads the number as you: with your row rules and your masks. If a number is masked for you the alert never judges it and never shows it. Once it fires it stays quiet until the number is back
inside the limit (by the *hysteresis* you set, if any) and does not fire again within the *cooldown* (30 minutes by default). Your alerts are on the **Alerts** page (also **Reports**, *My alerts*): *Look now*, *Pause*, *Resume*,
*Edit and history* (what every look did, and what was sent where) and *Delete*. What fired is in the **Alerts** tab of your inbox. If a board's author has made a tile notify, you are told when it turns red, on your own number.
Reference: [DISTRIBUTION.md section 4](DISTRIBUTION.md#4-alerts-on-numbers); walk-through: [tutorial 11](../tutorials/11-bi-that-runs-itself.md).

## Workspaces

A workspace puts up to four live views on one screen. A pane can **follow** another: a link you click
in the followed pane opens in the following pane.

*Views → Workspaces* in the top bar (`/w`) lists yours and the starters. Starters come from packs that
define them (none of the shipped packs does; the retired example pack `finance` defines *Credit desk*, *Rates*, *Cross-asset*) and from
`drishti-console/config/workspaces.yaml`.

With any packs, start a blank workspace: type a name under **New workspace** on `/w` and press **Create** (or open
`/w/<name>` for a name you have not used and click **Create it**). It opens with two empty panes (the `blank` entry of
`drishti-console/config/workspaces.yaml`); type a command in each pane's box, then **Save**. When your packs bring no
starters, *Starters* says so.

Worked example (on the demo lakes, from a blank workspace):

1. Open `/w`, type `Credit desk` under **New workspace** and press **Create**. In the first pane type `NSET NS-NORTHPLAINS-NY` (the
   netting set), in the second `TRD 1000001`, and set the second pane to follow the first.
2. In the netting-set pane, click another member trade. Pane 2 follows pane 1, so it opens that trade.
3. Change **Layout** to *Two by two*, click **Pane** to add a fourth pane, and type `TRD MX-20000001` (a MUREX swap) in its
   **Entity for this pane** box.
4. Set its **follows** to `—` so it stays put.
5. Click **Save as…**, name it `My credit desk`, and click OK. It now appears under *Yours*.

| Control | Does |
|---|---|
| Layout | *Two columns*, *Three columns*, *Two by two*, *One large, two stacked* |
| Entity for this pane | A command or id (`TRD MX-20000001`, `NS-SUMMIT-NY`), then Enter |
| follows | Which pane this one follows, or `—` |
| Pane / × | Add a pane (up to four) / remove one |
| Drag a suggestion onto a pane | Type in the command line (`TRD MX-2`, or nothing for your recent ones), drag a suggestion onto a pane: the pane shows it. Click it without dragging to open it full screen as usual |
| Drag a pane's number onto another pane | The other pane shows the same view |
| Drag a divider | Share the width (or height) between two neighbouring columns (or rows); focus it with `Tab` and use the arrow keys (`Home`, or a double-click: equal) |
| Save / Save as… / Delete | Keep it under its name, sizes included / a new name / remove it |
| `Alt+1`…`Alt+4` | Move to pane 1 to 4, from anywhere: the workspace's bar or inside a pane (an empty pane: its box) |
| `Alt+0` | Back from a pane to the workspace's bar |

Workspaces are saved to your account (up to 50 each).

**If no starters are listed** (the shipped packs ship none: *Starters* then says so), start a blank workspace as above,
or an administrator can add one for everybody
to `drishti-console/config/workspaces.yaml`; it appears after the console restarts:

```yaml
templates:
  Counterparty credit:
    description: A netting set, the trade you pick in it, and the discount curve.
    layout: "1+2"
    panes:
      - { ref: { kind: netting-set, id: NS-SUMMIT-NY }, title: Netting set }
      - { ref: { kind: trade, id: MX-20000001 }, follows: 0, title: Selected trade }
      - { ref: { kind: ir-curve, id: CRV-USD-OIS }, title: Discount curve }
```

`follows: 0` means "follow the first pane". See the [Workspaces guide](#workspaces).

### Sharing a workspace

Open one of your saved workspaces and press **Share…**. Tick **Everyone**, or name roles (`risk, trader`) and people
(`ravi, tess`), and press **Share**. They find it on their Workspaces page under **Shared with you**, marked
*shared by you · read-only*. They see it as you keep it: when you change and save it, they see the change. Panes on
kinds a reader may not open stay hidden from that reader. A reader who wants to change it presses **Save a copy…**,
which makes it their own. **Stop sharing** (or deleting the workspace) takes it away from everyone.

## Discussion

Anyone who may open an entity can start a discussion on it: a comment thread on the whole view, on one panel or on one
field, for the next reader or for a colleague. Press **Discussion** in the view's header (or **Alt+N**). It opens in the
same side drawer as **About this page**, as a second tab: **?** and **F1** still open About, **Alt+N** opens
Discussion, the arrow keys move between the two tabs, and **Esc** closes the drawer and puts the focus back where it was.
On a phone the drawer is a sheet from the bottom edge.

- **Write.** The box at the bottom starts a new thread: choose what it is about (the view, a panel or a field), write,
  and press **Post** or **Ctrl+Enter**. Each thread has its own reply box. The panel's header carries a small comment
  icon beside its **?** with the number of comments; pressing it opens the drawer on that panel. A field with a
  discussion shows a small amber dot; in the history window of a number, **Discuss** writes about that field.
- **Mention people.** Type **@** and a few letters: the list offers colleagues and roles (`@risk` tells everyone with
  that role who may open the view). Arrow keys move, **Enter** picks. People you mention get a notice on their bell, and
  an email when your administrator has set one up (each person can switch the email off on the *Account* page, under
  *Email me when*; the bell always shows it). After you post, the drawer says how many people were told and, by name,
  who was not (for example a colleague whose role may not open this kind of view).
- **Quote a value.** Type **{** (or press **Quote a value**) and pick a field: the comment holds `{$.MTM}` and every reader
  sees the number *as they may see it*: a reader who may not see the field sees ••• instead. A number you type out by
  hand is checked too: if it copies a value that is hidden from some readers, those readers see ••• (you read your own text as written). If you can see hidden values yourself you get a warning; if you cannot, you are not told, so nobody can test guesses of a hidden value. Email and chat posts about the comment carry the quoted values as each recipient may see them, unless your administrator set the pack to link-only.
- **What the comment saw.** Every comment shows the data it was written against ("gen 1702 · 29 Sep"). When the page now
  shows other data, **Open as it was** opens the view at that date and time, as the writer saw it.
- **Edit, retract.** You can edit your comment for a short while after posting (15 minutes unless your administrator
  changed it); "(edited)" opens the earlier versions. After that you can only **Retract** it (press twice to confirm): it
  then reads "Retracted by the author". Nothing is deleted.
- **Resolve, follow, mute.** Anyone in the thread can **Resolve** and **Reopen** it. You follow threads you write in; **Follow**
  and **Mute** control whether you are told of replies. A reply on a thread you follow shows on your bell and a line is
  announced in the drawer ("1 new comment on VaR contribution").
- **Quoted values.** A quote (`{` then pick a field) shows the value as the page shows that field: `1,875,863`, not the stored `1875863`; a reader
  who may not see the field sees `•••`. If the page now shows newer data than the comment saw, the field's format is applied to the value the comment saw.
- **Moderation.** Administrators see **Hide** (with a reason; readers see "Hidden by a moderator: <reason>") and **Lock**
  (no new comments). Hidden comments are kept in the record. The whole set of moderation and compliance tools is on one page, **Admin > Collaboration**
  (see [USER_MANAGEMENT](../admin/USER_MANAGEMENT.md)).

![Asha's Discussion tab: a comment that mentions @vera, with the trader's id typed and the trader quoted, and the quoted quantity](img/collab/07-discussion-author.jpg)

The same comment for a reader without access to the field: the typed id and the quoted field both read ••• and the quoted quantity stays,
because the reader may see it. *Open as it was* is offered because this page now shows other data.

![Vera's Discussion tab: the same comment with ••• where the trader's name was](img/collab/08-discussion-viewer.jpg)

![The Discussion sheet on a phone](img/collab/09-discussion-phone.jpg)

Notes written before discussions existed appear as one-comment threads. Every comment, edit, retraction and hide is in
the audit log. Discussions are kept in the server's identity database, so everyone on that server sees the same ones;
another server has its own.

## Working with several servers

Your organisation may run more than one Drishti server: an open one most people use, and others for a restricted desk
or a pre-production copy. When the console knows of several, the top bar shows the current one as a coloured dot and
a name. Click it to switch, or choose **All servers…** to see each one's state, version, how you sign in to it, and
whether you are signed in there.

Each server has its own users and data, so you sign in to each separately. You can be signed in to several at once and
switch without signing in again. **Sign out** signs you out of the current server only, and ends that session on the
server: the browser's cookie, or a copy of it, no longer signs anyone in. (Opening `/logout` by a link only asks; the
**Sign out** button does it; you land on the home page with a "Signed out" note.) With sign-in on, the home page shows a **Sign in** button when nobody is signed in, and your name with a menu (Open the terminal, My account, Sign out) when you are. A link someone shares opens on
the server it was made on (it carries `?srv=…`). A server not shown in the list can still be reached with the link
`/connect/<its id>` if you were given one.

Servers that act as **one cluster** (Drishti\*net) are different: you sign in once and the console moves between them by itself; see
[Several servers (Drishti\*net)](#several-servers-drishtinet).

## Your settings

*My account* (`/account`) shows your profile and roles, your settings, and a form to change your password.
To get there, click your avatar at the right of the top bar and choose **My account**, or **Settings** to
jump straight to the settings (`/account#settings`). Settings are kept on the server, so they follow you to
any browser.

| Setting | Choices | Example |
|---|---|---|
| Theme | *This browser's choice* or one of the ten themes | `Wallstreet` |
| After signing in, open | Any console address | `/t`, `/w/Rates`, `/m/High utilisation`, `/v/trade/MX-20000001` |
| Clock time zone | Default (NY) or a listed zone. Also the zone of every time in comments, shares, the inbox and revision history (labelled with the zone's city, e.g. `Kolkata`); a picture of a share is drawn on the server, in the business-date zone | `Europe/London` |
| Density | *Comfortable* or *Compact* (more on screen) | `Compact` |
| Flash changed values | On or off | off for a calmer screen |
| Search results | 10 to 1000 | `50` |

Click **Save settings**.

**Pinning.** **Pin** in a view's title line adds the entity to *Pinned* on the terminal home (`/t`), up
to 20. The button then reads **Pinned**; press it again to unpin.

**Changing your password.** Type the current password and the new one twice (at least 10 characters,
with letters and digits), then click **Change password**. When an administrator asked you to choose your own password
(at your first sign-in, or after they reset it), *My account* shows only this form, and every other page takes you
back to it until the password is changed; then the console opens the terminal home.

**When your access changes.** Your roles and whether your account is enabled are read from the server as you work,
not only when you sign in. If an administrator disables your account, every page sends you to the sign-in page within
a few seconds (`auth.recheck_seconds`, 10 by default); if they change your roles, what you may open changes as quickly.
An administrator resetting your password signs you out everywhere. Users, roles and password rules are covered
in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

## Themes

The palette among the round tools of the top bar offers ten themes (three added in 2.6.0: the Terminal look in one dominant hue):

| Theme | Name in settings | Look |
|---|---|---|
| Terminal (default) | `terminal` | dark navy, amber accents |
| Parchment | `light` | light, warm paper |
| Blue | `blue` | light page; Sajha's blue: a navy-to-blue top bar with white text |
| Wall Street | `wallstreet` | dark, the Bloomberg colours: black, amber, yellow |
| Green | `green` | light page; Sajha's green: a deep-green top bar with white text |
| Crimson | `crimson` | light page; a crimson-deep to crimson to indigo top bar with white text, deep-crimson headings |
| Crimson dark | `crimson-dark` | the same crimson on a dark page |
| Green Terminal | `terminal-green` | dark: phosphor green with light lime on deep forest |
| Crimson Terminal | `terminal-crimson` | dark: crimson with blush on wine-black; text in the accent is dusty rose, buttons a deep crimson with white labels |
| Blue Terminal | `terminal-blue` | dark: azure with periwinkle on midnight |

**Choosing one.** Pick by the room and the screen, not by taste alone: a dark theme (Terminal, Wall Street, Crimson dark or one of the
three Terminal hues) for a trading floor, a wall screen or long sessions; a light one (Parchment, Blue, Green, Crimson) for a bright office,
a projector or anything you print or capture for a document. Wall Street is for people who read Bloomberg all day. A desk that wants its
screens told apart at a glance can give each its own Terminal hue. Your choice follows you (below); an administrator sets the default for
everyone with `ui.default_theme` ([CONFIGURATION.md](../admin/CONFIGURATION.md)), and *My account → Settings → Theme* or
`PATCH /api/v1/me/settings` (`"theme": "terminal-blue"`, [API_GUIDE.md](API_GUIDE.md)) set it too.

Each theme's colours shade the page, the top bar, view and panel headers and accent buttons; panel
bodies, tables and figures stay on solid colours so they read clearly. If your system asks for more
contrast or less transparency, the solid look is used. Signed in, your choice is saved to your account;
otherwise the browser remembers it.

**The contrast rule.** Every theme meets WCAG AA contrast, checked by a test on every theme (`drishti-console/tests/test_contrast.py`), not
by eye: all text, including faint notes, the footer, links and placeholders, reads at 4.5:1 or more on every background it is drawn on, and
accents and chart fills at 3:1. Buttons and pressed controls fill with `--d-fill` (and `--d-fill-strong` on hover), which is the accent
unless a theme gives buttons a colour of its own, and carry their label in `--d-on-fill` at 4.5:1 on both: Crimson Terminal, for one, fills
its buttons with a deep crimson under white labels rather than its bright text accent. The same test fails any stylesheet rule that fills a
button from the accent instead (the accent buttons' gradient runs from `--d-fill` to `--d-fill-strong`). A page that embeds Drishti's elements and sets the
`--d-*` values itself ([ELEMENTS.md](ELEMENTS.md)) should keep the same rule.

These are the console's themes. A BI report has a theme of its own (the shipped *Corporate* and *Print*, or one made in the
theme designer), chosen by its author in BI Studio: [REPORTS.md section 5.2](REPORTS.md#52-themes).

## Domain packs: choosing what you see

A pack brings one domain: its mnemonics, layouts and data. If you may use more than one pack, the round
**box** tool in the top bar shows how many you have switched on, for example `7`. Hover over it to read
their names (*Packs shown: core, market-data, …*).

Worked example: see only trading and counterparty risk.

1. Click the box. A menu opens headed *Show these packs*, with one line per pack: a tick box, the pack's
   title (*Core*, *Market data*, *Trading*, …) and one line on what it covers.
2. Click **none** (top right of the menu): every box is cleared.
3. Tick *Trading* and *Counterparty credit risk*.
4. Click **Apply**. The page reloads and the box shows `2`.
5. Type `E`. The suggestions no longer offer `ECON` (world economy), whose pack is now off.
6. To undo, open the box, click **all**, then **Apply**.

What changes when a pack is off for you:

- its mnemonics leave the suggestions, and its examples leave `/t`, the help centre and the search page;
- opening one of its entities is refused with `DRS-5002 … may not open … entities`;
- a pack's parents stay usable through it: with *Counterparty credit risk* on, a netting set still links to
  its trades and counterparties (see [PACKS.md](PACKS.md#who-sees-which-pack)).

**Apply** needs at least one ticked pack; with none ticked it does nothing. The choice is saved to your
account. You can choose only among the packs an administrator assigned to you, and only among packs that
are switched on for everyone ([Admin → Packs](#admin--packs-switching-a-pack-off-for-everyone)).

## The workbench: changing how a screen looks

A **Sutra** is a layout: one YAML file (`<name>.v<N>.sutra.yaml`) that starts with `rachana: 1`, the version of
the layout language, followed by the layout. It may say what it is for in `description:` and carry longer notes for
reviewers in `notes:`; neither changes the screen. The **workbench** (*Build → New screen*, then the design's page) is
where you change one; it replaces the old Sutra Studio page, whose addresses (`/studio`, `/studio?sutra=…`) still work and
send you to the equivalent workbench screen. The [screen designer guide](SCREEN_DESIGNER.md) goes through it with a
picture of every step; this is the short version.

Designing is open to every signed-in user: the workbench, its preview against your files or a stored entity, **File → Open**
and the examples need no special role. Only **saving and proposing** need the *author* role (and saving switched on for the
server, `DRISHTI_STUDIO_SAVE=true`); without it **Save** is dimmed and says "Designing is open to everyone; saving needs the author right". Previewing
a *stored* entity, and any panel that reads another entity with `source:`, still follow the kinds your roles open: a panel whose source you may not open shows
"no access to <kind>" instead of its data.

A design is yours and is kept by the server (**Build → My designs**). To start one:

- **Build → New screen**: bring JSON files, a schema or stored entities, and let auto-design draft a first screen;
- **Build → Examples**: each example opens as your own copy;
- an existing Sutra: *New screen → an existing Sutra* (the design then edits a copy, with that Sutra as its base).

The workbench has:

- the **Data** pane: the design's samples (add files, or stored entities), the shape found in them, and the palette of panel kinds;
- the **screen** in the middle, as **Design** (the real preview you drag panels and fields onto), **YAML** (the editor, which completes as you type: keys, panel kinds and the options each kind takes, formats, tones, entity kinds and expression functions, all from the server's schema of the language, `GET /api/v1/rachana/schema`) and **Summary** (the layout read back as a page);
- the **Inspector**, **Problems** (by line, while you type), **Tests** (every sample against the design) and **Versions** (the design against its base or an earlier version, with restore);
- **File** (open a `.yaml` or `.json` from your computer, or an example), **Save** or **Submit for review**, **Commands** (Ctrl+K), and Undo and Redo.

The keys Studio had are still there: `Ctrl+S` saves (or says why not), `Ctrl+Enter` previews. `Ctrl+K` opens a command palette.
Plain `/studio` opens a scratch design on the showcase example; use **Build → New screen** for your own data.

Worked example: add a one-day P&L figure to the swap's strip.

1. Choose *irs-fixfloat@1* under **Build → New screen → an existing Sutra** (or open `/studio?sutra=irs-fixfloat@1&kind=trade&id=MX-20000001`) and add
   `MX-20000001` as a stored entity). The YAML tab shows the Sutra; the preview shows `MX-20000001`. The entities you had kept as that Sutra's test entities in
   Studio are already among the samples.
2. Near the top, under `rachana: 1` and `sutra: irs-fixfloat`, change `version: 1` to `version: 2`.
3. Under `strip:`, after the `MTM (USD)` line, add the line shown below this list
   (same indentation as the other strip lines).
4. Press `Ctrl+Enter`. The preview's strip now has **P&L 1D (USD)**.
5. Make a mistake on purpose, such as `bind: $.pnl1d +`: the Problems tab names the line and the error. Undo it (Ctrl+Z).
6. Open **Versions** and compare with the base: you see the two lines you changed. Type a note in *What changed? (for the reviewer)*, such as `Adds 1-day P&L to the strip`, and click **Submit for review** (or **Save** when review is off).
   You should see `Submitted for review as …. An approver makes it live.`

The line to add in step 3:

```yaml
  - { label: P&L 1D (USD), bind: $.pnl1d, fmt: signed0, tone: sign }
```

Bump the version whenever you change a Sutra that is already live. The saved file goes into the site
Sutra folder (`./sutras/…/irs-fixfloat.v2.sutra.yaml`), and views use the highest version.

**Auto-design** (instead of Studio's *Start from inference*) drafts a complete Sutra from the design's samples, with a reason for every choice; it is a new
revision, and Undo brings the old text back.

The [screen designer guide](SCREEN_DESIGNER.md) (every pane, with pictures) and the
[Sutra developer guide](SUTRA_DEVELOPER_GUIDE.md) go much further.

### Reviews: approving a Sutra

With review on (the default), a save from the workbench is a **proposal**. Nothing changes for anyone until an
approver approves it.

1. Open **Build → Govern → Reviews** (`/build/reviews`; the menu shows how many wait). *Pending* proposals are listed with their Sutra,
   note, author and time.
2. Click one. You see *Changes against the live Sutra* (or *New version: the whole Sutra*) as a diff.
3. Add a **Comment** and click **Approve and publish**: the Sutra goes live, and the next view uses it.
   Or give a **Reason** and click **Reject**. The author can **Withdraw** their own.

Rules: approvers are users with the `approver` or `admin` role; with sign-in on, nobody approves their
own proposal (four eyes); a proposal whose live Sutra changed since it was made cannot be approved
(reject it and propose again); every step is in the audit log.

## Administration

Admins see the **Admin** menu in the top bar. The admin pages also share a row of tabs, so you can move
between them without the menu.

| Page | Address | What you do there |
|---|---|---|
| Users | `/admin/users` | Create users; set roles and the packs each user may use; enable, disable, reset passwords |
| Roles | `/admin/roles` | Define roles: the kinds each opens, and its powers |
| Packs | `/admin/packs` | Switch a domain pack off or on for everyone; deploy an archive; see which connectors a pack reads through (**Data source**, read only) |
| Artifacts | `/admin/artifacts` | Everything installed in one list (packs, connector files, shipped BI files): see what depends on each, **disable** it, **uninstall** it to an archive and **restore** it, install a BI file ([ARTIFACTS.md](../admin/ARTIFACTS.md)) |
| Connectors | `/admin/connectors` | Where data comes from: one connector per YAML file in `config/connectors/`; create, edit, test, switch off and see which packs use each ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)) |
| Tokens | `/admin/tokens` | Every personal API token (never its secret): its owner, its scopes (*read only* or what it may write), when it was last used (in the console's time zone, named), and revoking any of them ([CLIENTS.md](CLIENTS.md)) |
| Access | `/admin/access` | Who read which entity, filtered by user, action, kind, id and dates ([USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md)) |
| AI | `/admin/ai` | The AI layer: off by default; providers, models, MCP servers and a test call ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md)) |
| Data loads | `/admin/loads` | What your ETL announced, what is due and what is late ([DATA_LOADS.md](DATA_LOADS.md)) |
| Collaboration | `/admin/collab` | Moderation of shares and threads, legal holds, export, the mail outbox and chat bridges ([COLLABORATION.md](../architecture/COLLABORATION.md)) |
| Embedding | `/admin/embedding` | Host applications that show Drishti views in their own pages ([ELEMENTS.md](../architecture/ELEMENTS.md)) |
| BI | `/admin/bi` | Powers per role and who creates workspaces (needs `bi-admin`; [BI_GUIDE.md](BI_GUIDE.md)) |
| Audit log | `/admin/audit` | Sign-ins, failures, lockouts, user, role and pack changes, Sutra proposals and approvals, cache purges |
| Health | `/admin/health` | Every connector and pack: up or down, reads, timings. Refreshes every 5 seconds |
| Caches | `/admin/caches` | What the engine and each connector hold; **Purge** one or **Purge all** (safe at any time) |

### Admin → Artifacts: install and uninstall safely

*Admin → Artifacts* lists every pack, connector file and shipped BI file, with its status, origin, version and problems, and a filter (name, kind, status,
origin; **/** focuses it). *Uninstall…* (here, and on the Packs, Connectors and BI pages) first asks what depends on the artifact. With nothing depending on it,
it **moves** the folder or file to `data/uninstalled/<time>-<kind>-<name>/`; with dependants it is refused and **Disable instead** (keep it, switch it off) is the
default; taking the dependants along needs the name typed. The **Uninstalled** tab restores an archive (refused if the name exists again, unless you replace it) or
deletes it for good (name typed); archives older than 30 days are removed. *Install* uploads a BI file with a dry run first. Every action is audited, and no API
token can do any of it. Full guide: [ARTIFACTS.md](../admin/ARTIFACTS.md).

### Admin → Roles: what a role may do

A role says which **kinds** of entity its holders may open, and which **powers** they have. The table lists
every role with its kinds (or *every kind*), its powers, how many users hold it, and who changed it last.

| Power | Lets its holders |
|---|---|
| raw JSON | see every field: nothing is masked for them, in the raw JSON (`F9`, **JSON**) or anywhere else ([Masked fields](#masked-fields)) |
| author Sutras | save and propose Sutras from the workbench (designing and previewing there is open to everyone) |
| approve Sutras | approve proposed Sutras in Reviews |
| administer | use every admin page |
| Calc | use Calc (`Alt+C`): Python in the browser on what the role opens ([PYTHON_CALC.md](PYTHON_CALC.md#9-roles-who-may-use-calc)) |
| BI (`bi`, `bi-author`, `bi-steward`, `bi-admin`) | four powers for Rūpaka, granted on roles ([BI_GUIDE.md](BI_GUIDE.md#3-who-may-do-what-the-four-powers), [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md#bi-powers-rupaka)); the Roles form above lists only the first six powers |
| layouts | use [layout mode](#layout-mode-arrange-a-view-your-way) (`Alt+L`) and keep personal layouts. On for every role unless unticked; the built-in `viewer` role does not have it |

There is no power for the [Pivot tab](#the-pivot-tab-slice-a-table-your-way): anyone who may open a kind may arrange and save its pivots, as they
may sort a table; promoting one to a Sutra needs **author Sutras** (and goes through review).

Roles marked **built-in** come from the server's configuration and from packs (`viewer`, `author`,
`approver`, `admin`, and pack roles such as `trader`, `credit-risk` or `economist`). They are shown read-only.

Worked example: a role for credit analysts.

1. Open *Admin → Roles* and click **New role**.
2. **Name** `credit-analyst` (lower case, digits and hyphens); **Description** `Reads counterparties and
   credit exposure`.
3. Under *Add every kind of a pack*, click **Counterparty credit risk**: its kinds (`netting-set`,
   `credit-limit`, `cva`, …) fill the *Kinds it opens* box, one per line. Add `counterparty`
   on a line of its own.
4. Leave every power unticked, and click **Save**. The role appears in the table with `0` users.
5. Open *Admin → Users*, edit a user, tick `credit-analyst` and save. At that user's next click, netting sets
   open for them; a trade does not, and links to trades show disabled with the reason.

Changes apply at the holder's next request and are written to the audit log. **Delete** is greyed while
anyone holds the role (*Held by 1 user(s): take it away from them first*); the server answers `DRS-6009`
if asked anyway. Roles only matter when sign-in is on. More in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

### Admin → Packs: switching a pack off for everyone

*Admin → Packs* lists every pack in the server's packs folder, one row each:

| Column | Example |
|---|---|
| Pack | *Trading* `trading 1.0.0` |
| What it brings | its description |
| Builds on | `core, market-data`, and under it *needed by market-risk, counterparty-risk, …* |
| Kinds | `1 (TRD)`: the number of kinds and the first four mnemonics |
| Connectors | `trade-endur, trading-stream` |
| Status | **on**, **off**, or **not loaded**; under it, the problem if the pack's files could not be used (the last good version keeps running) |
| Changed | when and by whom it was last switched |

A **Sample** badge beside the title marks a demonstration pack. **Load** and **Unload** take effect at once, with no restart; a pack folder you add,
edit or delete on the server is picked up the same way (see [PACKS.md](PACKS.md#live-reload-the-packs-folder-is-watched)). Above the table, the
*Sample packs* section says who sees samples and offers **Hide samples from business users**: only authors and administrators then see sample
packs, everyone else has them filtered out as if switched off. *Hide samples from everyone* unloads them; *Show samples to everyone* brings them back.
The choice is saved, applied at once and recorded in the audit log (`pack-samples`).

Switching a pack **off** takes effect for every user at their next click: its kinds cannot be opened, its
mnemonics and suggestions disappear, and it leaves every pack menu. Switching it **on** brings it all back.
The choice is kept in the database, so it survives restarts, and each switch is in the audit log
(`pack-disabled`, `pack-enabled`).

Two safeguards keep packs consistent:

- **A pack another switched-on pack builds on cannot be switched off.** Its **Switch off** button is greyed
  with the reason (*Needed by market-risk, counterparty-risk: switch those off first*).
- **Switching a pack on switches on what it builds on.** If you switched off `counterparty-risk`, then
  `trading`, switching `counterparty-risk` on again switches `trading` (and anything it needs) on as well.

Worked example: take operational risk away for everyone.

1. Open *Admin → Packs*. The *News and markets* row says **on**; nothing builds on it.
2. Click **Switch off**. The status turns to **off**, and *Changed* shows the time and your name.
3. Type `TOPIC` in the command line: no suggestions come. In the box menu, the pack is gone.
4. Open *Admin → Audit log*: the newest line is `pack-disabled news-and-markets` by you.
5. Click **Switch on** to bring it back.

**Not loaded** packs are folders on disk that the server did not load at start-up. Press **Load**: the server
first checks the pack together with the loaded ones (a clash, such as the retired example pack `finance` beside `trading`, both defining `trade`, is refused
with the reason and nothing changes), then records it and puts it to use at once: no restart, nobody is signed out, and
open views carry on. The page confirms and refreshes. A pack loaded this way shows **Unload**, which takes it back the
same way (not while another loaded pack builds on it). See
[PACKS.md](PACKS.md#loading-a-pack-while-the-server-runs).

#### One pack: its own page

In the *Pack* column every title is a link (Tab to it, Enter opens it) to `/admin/packs/<name>`; *Builds on*, *needed by* and *Connectors* link to the
packs and connectors they name. The page gathers what the list can only hint at:

![Admin → Packs → one pack: status, kinds with their mnemonics, Sutras, connectors, loads and history](../connectors/img/connector_files/12-pack-detail.jpg)

- **Summary:** title, version, code, description, the **Sample** badge, who changed it and when, and the status: **on**, **off**, **not loaded**, or **refused**
  (a pack whose files could not be used) with the reason and, when the server is trying again, *failed — next retry in Ns (attempt k)* and **Retry now**.
  **Builds on** and **Needed by** are links to the other packs' pages. **Switch on/off** and **Load/Unload** are the list's buttons, with the same safeguards.
- **Kinds and mnemonics:** each kind with its mnemonic, a link that **opens a record of the kind** (where the pack has one), a link to `FORM <pack>`
  for that kind, and its form fields (declared in the pack's `form:` section, or *inferred*).
- **Sutras:** the pack's Sutras with their versions, each with **Open in Build** (the Build workbench).
- **Connectors it names:** state and kinds for each, a link to its page, and, for a name nothing defines, **missing** with a **Create** button that opens the editor
  pre-filled from the pack's suggestion.
- **Query by form and About:** how many kinds declare a form, how many carry About text and how many glossary terms.
- **Data loads:** today's expectations and their state, the last load, and a link to the full [data loads page](DATA_LOADS.md).
- **Deploy history:** the pack's deployments and rollbacks with **Roll back to this** for each kept version.

#### One connector: its own page

In *Admin → Connectors* the connector name is a link to `/admin/connectors/<name>` (the *Used by* column links to the packs):

![Admin → Connectors → one connector: summary, TLS certificates and their expiry, packs, settings, the file and its history](../connectors/img/connector_files/11-connector-detail.jpg)

It shows the plugin, where the connector is defined (file, pack or the deprecated `application.yaml`), whether it is on, its **status** and the plugin's own health,
the time of its last update, and, when it failed, *next retry in Ns (attempt k)* with **Retry now**. **TLS** says whether it is on, which protocols, whether the
host name is checked and, for every certificate the settings name, **when it expires** (a warning under 30 days, *expired* in red). Below: the kinds it serves, the
packs and kinds that use it (links), the **settings in effect** with credentials masked as `***`, the file as read-only text and the kept earlier versions.
**Edit** opens the same editor as the list, **Test connection** opens it on the Test tab and runs the test, **Compare or restore** opens the History tab,
**Disable/Enable** and **Reset to pack default** (or **Delete**, for a file no pack suggests) work as on the list, with the same questions when packs use the connector.
Both pages are for administrators, work in both themes and on a phone, where every link and button is at least 44 px:

![The connector page on a phone](../connectors/img/connector_files/13-connector-detail-phone.jpg) ![The pack page on a phone](../connectors/img/connector_files/14-pack-detail-phone.jpg)

### Admin → Connectors → Index: reload type-ahead for a past day

Worked example: a trade was corrected in last Friday's data and type-ahead for that date still misses it.

1. Open *Admin → Connectors* and click the connector (for example `trading-lake`). Under **Index** every kind is listed by
   business day with where it is held (memory or disk), its ids, size and when it was built, and the line above says when the
   automatic check last ran, runs next and last found a change.
2. Click **Reload** on Friday's row (or **Reload kind** for the kind, or **Reload all**). The row changes to
   *rebuilding* with a progress message, then to *fresh* with a new build time. Type-ahead for that date keeps answering from
   the old day until the new one is ready.
3. If a reload of the connector is already running, the message says so; wait for it. If a day cannot be read, its row
   shows *failed* and the reason, and the old day stays in use.

*Admin → Health* has **Reload index** on each connector that keeps several days. Reloads are for administrators only and are
written to the audit log. See [OPERATIONS.md section 12.2](../admin/OPERATIONS.md#122-sizing-the-multi-day-type-ahead-index).

### Admin → Health: is everything up?

Worked example: check that every data source is up.

1. Open *Admin → Health* (`/admin/health`). At the top is the overall state, **OK**, **DEGRADED** or
   **DOWN**, and a summary. Below it, each connector (for example `trade-endur`, `market-store`, `demo`)
   shows its state, reads, errors and p50/p99 read times. A Delta Lake connector whose folder is missing
   shows `DOWN: no Delta tables under …`; one with a table or date it cannot read shows **DEGRADED** and names it
   (`DEGRADED: cannot read trade 2026-09-30: …`).
2. The page refreshes itself every 5 seconds. The same figures are at `GET /api/v1/admin/health` for
   external monitoring:

   ```bash
   curl -s localhost:18480/api/v1/admin/health | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d["summary"])'
   ```

   You should see:

   ```text
   OK {'packs': 12, 'sources': 18, 'sourcesDown': 0, 'failedToStart': 0, 'packsWithProblems': 0}
   ```

3. A shorter list of sources, with their kinds and health, needs no admin rights:

   ```bash
   curl -s localhost:18480/api/v1/sources
   ```

A connector whose plugin is installed but has no settings (a Kafka connector without `topics`, a feed without
`feed`) stays **idle**: it is not started and is not counted as failed. Anything else that is not **UP** is
explained in [runbooks/source-down.md](../admin/runbooks/source-down.md). A connector a pack names but that has no file shows as *not configured* (its kinds answer `DRS-1011`);
an administrator creates it in *Admin → Connectors*, and a connector file with a mistake is listed on Health as a bad file while the last good configuration keeps running.

Managing users and roles is explained in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

## Help

- *Help → Help centre* in the top bar, or `F1`, opens the help centre (`/help`): tutorials, guides, every reference
  document and each pack's guides, with search (`/help/search?q=`). On a page, `F1` opens the guide for that page
  (`/help/context/<page>`: Build opens the screen designer guide, BI the BI guide, Admin the users and roles guide, Workspaces, Monitors, Alerts and the inbox their own guides); on a view, `F1` opens [About this page](#about-this-page).
- The **?** in each panel header explains that panel kind.
- **About** (`/about`) is the product page (features, measured performance, competitive analysis, licence); loaded Sutras and source health are in *Admin → Health* (`/admin/health`).

## On a phone

Drishti works in Safari on iPhone and Chrome on Android, with no app to install. To put it on your home
screen, use *Share → Add to Home Screen* (iPhone) or *⋮ → Add to Home screen* (Android). It then opens
full-screen at the terminal.

- **Function keys** become a swipeable row of buttons at the bottom of every view: tap **F9 Raw JSON**,
  **F7 Netting set**, **← Back**.
- **Command line:** type a command and tap a suggestion.
- **Layout:** panels stack in one column, the strip shows two figures per row, and wide tables scroll
  sideways inside their panel. Workspace panes stack too. No page scrolls sideways on a screen 360 px wide or
  wider.
- **Top bar:** the menus and tools keep their icons on the first line (below 480 px the brand shows its mark
  only); the command line and the business date share the second, and **Live** is the same round green or red toggle as on a laptop.
- **Help:** screenshots fit the screen; tap one to open it full size.
- **Live values** keep ticking.
- The **workbench** is left out on phones; everything else, including help and administration, works.

## Several servers (Drishti*net)

When your administrator runs several Drishti servers as one cluster, nothing changes for you: you sign in once, and every page and live view looks
and works the same whichever server holds the data. If the server your console uses stops, the console moves to another by itself; the page you are
on reloads its data, a live view reconnects, and you do not sign in again. A save made at the very moment a server stops says it did not save: save
again. When the server that holds what you asked for is down, the page says so (`DRS-5052 ... cannot be reached now`) instead of showing nothing.
A search covers every server's data; when one of them did not answer, the results say they are partial and name the server they are missing.
A page that groups many records (a counterparty's trades, a book's P&L) counts the records of every server; if one did not answer, the page
says it is partial and names the server.
Your saved searches, layouts, pivots and workspaces follow you to whichever server you are on. Alerts and scheduled reports stay on the server they
were made on (they act, and would act once per server).
