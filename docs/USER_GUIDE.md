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

If Drishti is not running yet, start with [GETTING_STARTED.md](GETTING_STARTED.md).

The examples use the banking packs (`trading`, `counterparty-risk` and the others) with their sample
data, so ids such as `T-10001` and `NS-SUMMIT-NY` exist. If your administrator loaded other packs, the
**Examples** list on the terminal page shows ids that work for you.

## The pages at a glance

| Page | Address | What it is for |
|---|---|---|
| Landing | `/` | The start page, with a button to open the terminal |
| Terminal | `/t` | The command line, your pinned entities and example commands |
| View | `/v/<kind>/<id>` | One entity, laid out in full (for example `/v/trade/T-10001`) |
| Search results | `/s?q=…` | Entities that match a `where` search |
| Compare | `/compare/<kind>/<id>` | What changed in an entity between two business dates |
| Impact | `/impact/<kind>/<id>` | What depends on an entity (F8) |
| Monitors | `/m` | Live watchlists |
| Alerts | `/alerts` | Your alert rules and recent alerts |
| Workspaces | `/w` | Several live views on one screen |
| Studio | `/studio` | Edit and preview layouts (Sutras) |
| My account | `/account` | Your profile, settings and password |
| Admin | `/admin/users` | Users, audit log, health and caches (admins only) |
| Help | `/help` | Tutorials, guides and every reference |
| About | `/about` | Version, loaded Sutras and the health of each source |

## The command line

The command line is the box at the top of every terminal page. Press `/` anywhere to jump to it.

### The form of a command

```text
<MNEMONIC> <ID> <GO>
```

- The **mnemonic** names a kind of entity: `TRD` is a trade, `NSET` a netting set, `GENE` a gene.
- The **id** names one entity of that kind: `T-10001`.
- `<GO>` means "press Enter".

Examples:

```text
TRD T-10001          an interest rate swap
TRD T-10181          an FX option
NSET NS-SUMMIT-NY    a netting set
CRV CRV-USD-OIS      the USD SOFR discount curve
CPTY CP-NORTHBRIDGE  a counterparty
CUST CUST-100231     a retail customer
VRNT VRNT-BRAF-V600E a genetic variant
```

**A bare id** works when its shape tells Drishti the kind. Type `T-10001` and press Enter: it opens
the same trade as `TRD T-10001`.

**Case** does not matter for the mnemonic: `trd T-10001` works.

Mnemonics come from the packs that are switched on. The full list, pack by pack, is in
[PACKS.md](PACKS.md#the-packs-that-ship). The most used banking ones:

| Mnemonic | Kind | Example |
|---|---|---|
| `TRD` | trade | `TRD T-10001` |
| `CPTY` | counterparty | `CPTY CP-NORTHBRIDGE` |
| `BOOK` | book | `BOOK BOOK-RATES-1` |
| `NSET` | netting set | `NSET NS-SUMMIT-NY` |
| `LIM` | credit limit | `LIM LIM-SUMMIT` |
| `CRV` | interest rate curve | `CRV CRV-USD-OIS` |
| `FXV` | FX volatility surface | `FXV FXV-EURUSD` |
| `VAR` | VaR / expected shortfall | `VAR VAR-RATES` |
| `LCR` | liquidity coverage ratio | `LCR LCR-NY` |

### Suggestions as you type

The command line suggests as you type, in a dropdown, like the Bloomberg terminal.

| You have typed | You see |
|---|---|
| nothing (click in the box) | the entities you opened recently |
| `T` | mnemonics that start with T (`TRD` Trade, `TRDR` Trader) and matching entities |
| `TRD ` (with a space) | trades |
| `TRD T-101` | trades whose id starts that way: `T-10100`, `T-10101`, … |
| `NSET SUMMIT` | netting sets whose id or title contains it: `NS-SUMMIT-NY`, `NS-SUMMIT-LDN` |

Each suggestion shows the id and a subtitle (`Trader · A. Shah`). The matched letters are highlighted.

Worked example:

1. Press `/` and type `CPTY north`.
2. The dropdown shows `CP-NORTHBRIDGE`. Press `↓` to highlight it.
3. Press `Tab`: the box now reads `CPTY CP-NORTHBRIDGE`.
4. Press `Enter`: the counterparty opens.

### When a command does not work

| Message | Meaning | What to do |
|---|---|---|
| `DRS-4001 cannot read command 'XYZ T-1'; try <MNEMONIC> <ID> <GO>` | The mnemonic is unknown | Type the first letter and pick from the suggestions; check the pack is switched on |
| `DRS-1001 no source holds trade/T-99999` | No source has that id | Type part of the id and pick from the suggestions |
| `You do not have access` | Your role may not see that kind | Ask an administrator |

## Reading a view

Open `TRD T-10001`. A view has five parts.

### 1 · The title line

```text
[Rates · Interest rate swap (fixed/float)]  T-10001  with Meridian Reinsurance Ltd   Alert  Pin  Share  JSON  Print
```

- The **pill** says what the entity is.
- The **id**, and the main party (**with …**). The party is a link: click it to open the counterparty.
- The buttons on the right are explained in [Export, print and share](#export-print-and-share),
  [Alerts](#alerts) and [Pinning](#your-settings). **Compare** appears there too when a past date is picked.

### 2 · The strip

The row of key figures under the title. For `T-10001`:

| Label | Value (example) |
|---|---|
| Notional | `AUD 242,000,000` |
| Direction | `Receive fixed` |
| Trade date | `2025-07-25` |
| Maturity | `2032-06-25` |
| **MTM (USD)** | `+1,603,277` (highlighted: the figure the desk watches most) |
| DV01 (USD) | `−155,245` |
| Status | `Live` |
| Book | `BOOK-RATES-3` (a link) |

Positive figures carry a `+` and the positive colour; negative ones a true minus sign (`−`) and the
negative colour.

### 3 · The panels

The main detail is on the left; context (charts, linked entities) is on the right. Each panel has a
header with:

- its **title** and, if it has one, its **function key** (`TRM · F2` means press `F2` to jump here);
- an **inferred** tag when Drishti laid the panel out by itself (hover over it to read why);
- a **↓** icon to download the panel as CSV;
- a **?** that explains this kind of panel.

`T-10001` shows *Terms*, *Legs* (one tab per leg), *Cashflows*, *How this view was built*, a curve
chart, *DV01 by bucket*, *Daily P&L, last 20 days* and *Linked entities*.

Panel kinds you will meet:

| Kind | Looks like | Example |
|---|---|---|
| `kv` | label and value pairs | *Terms* in `TRD T-10001` |
| `table` | rows and columns, totals for money | member trades in `NSET NS-SUMMIT-NY` |
| `tabs` | one tab per element of a list | *Legs* in `TRD T-10001` |
| `ladder` | dated rows, the next one highlighted | *Cashflows* in `TRD T-10001` |
| `line` / `area` | a chart over tenors or dates | the curve in `CRV CRV-USD-OIS` |
| `hbar` | horizontal bars | *DV01 by bucket* |
| `surface` | a grid as a heatmap, or a 3D surface you can rotate | `FXV FXV-EURUSD` (*Heatmap* / *3D* buttons) |
| `status`, `gauge`, `markdown` | a status list, a dial, formatted text | various packs |
| `links` | linked entities with badges | *Linked entities* everywhere |
| `provenance` | how the view was built | *How this view was built* |

The [panel kinds guide](../console/web/guides/panel-kinds.md) shows each one in detail.

**No data available.** A panel whose data is missing, or not in the expected shape, says *No data
available*. The rest of the view still renders. Real feeds are often incomplete; this is normal.

### 4 · Links

Every identifier in a view is a door. Click `BOOK-RATES-3` in the strip and the book opens. The
*Linked entities* panel lists everything the entity points to, each with a **badge** read from the
target (for example `EE 32.4m` on a netting set). A badge that says *pending* means the target did not
answer in time; open it to see it.

**Breadcrumbs** above the title (`← T-10001 / NS-MERIDIAN-RE-NY`) show the path you followed in this
browser tab. Click one to go back to it, or press `Alt+←`.

### 5 · How this view was built

The *How this view was built* panel answers "where did this come from?":

| Line | Example | Meaning |
|---|---|---|
| Layout | `Sutra irs-fixfloat v1 + inference` | A Sutra (a written layout) drew the screen; inference filled gaps. `inference only` means no Sutra matched |
| Fingerprint | `b7df…1372` | The shape of the data, used to cache the layout |
| Source | `murex-rates`, generation `1674` | Which system the document came from, and its version counter |

The footer repeats the source and generation, and the business date when one is picked
(`trading-store · gen 7 · as of 2026-09-29`).

## Keyboard

| Key | Does |
|---|---|
| `/` | Focus the command line, from anywhere |
| `↑` `↓` | Move through the suggestions |
| `Tab` | Complete the highlighted suggestion |
| `Enter` | Open it (`<GO>`) |
| `Esc` | Close the suggestions, or the raw JSON drawer |
| `F1` | Help for the page you are on |
| `F2`–`F6` | Jump to the panel that shows that key in its header |
| `F7` | Open the main linked entity (for a trade: its netting set) |
| `F8` | Impact: what depends on this entity |
| `F9` | Raw JSON of the entity, with its source and generation |
| `Alt+←` | Back |
| `Alt+1`…`Alt+4` | In a workspace: move to pane 1 to 4 |

The keys a view offers are also buttons along the bottom of the screen, so you can click them.

### F9 · Raw JSON

Press `F9` in `TRD T-10001`. A drawer opens on the right with the full document, starting
`{"tradeId": "T-10001", "productType": "IRS_FIXFLOAT", …}`, and its source and generation. Press `Esc`
to close it. If your role does not have raw access, fields such as `trader` are masked.

### F8 · Impact

Impact answers "what depends on this, and how much is at stake?". Open `TRD T-10001` and press `F8`.
You should see *Impact of T-10001*:

- **Depends on it directly:** *Netting set · 1*: `NS-MERIDIAN-RE-NY` with its net MTM.
- **Rolls up into:** *Credit limit · 1*: `LIM-MERIDIAN-RE` via `creditLimit`, with the limit amount.

Each group has a total. Click any id to open it. See the [Impact guide](../console/web/guides/impact.md).

## Live updates

Views of live entities tick: figures change in place and flash briefly. Open `TRD T-10001` and watch
**MTM (USD)** for a few seconds.

- The top bar shows a green dot with **Live** and how long the view took to build (`Live, built 0.82 ms`).
- All views, panes, the bell and monitors in one browser tab share **one** connection.
- **Reconnecting…** means the connection dropped; the page reconnects and repaints by itself.
- **Paused while hidden** means the tab was in the background for a while and gave its connection back.
  Show the tab and it resumes.
- A view on a picked past date never ticks: it is a snapshot.

To turn off the flash, untick *Flash changed values* in [your settings](#your-settings).

## Business dates: live or a day in the past

The **Business date** box in the top bar says which day you are looking at.

- **Live** (green, the default) is today's business date on the New York calendar (`USNY`), streaming.
  Weekends and holidays count as the business day before.
- **A picked date** shows that day's end-of-day data as a static snapshot. The box turns amber.

Worked example (needs the sample history; see [GETTING_STARTED.md](GETTING_STARTED.md), Step 5):

1. Open `TRD T-10001`.
2. Click the date box and pick `2026-09-29`.
3. You should see the same layout with that day's figures. The footer reads `… as of 2026-09-29`, the
   MTM no longer ticks, and a **Compare** button appears in the title line.
4. Open `NSET NS-MERIDIAN-RE-NY` with `F7`: it opens on the same date. Links, impact, search and
   suggestions all follow the date you picked.
5. Pick Saturday `2026-09-26`. The box shows `↩ 2026-09-26` and the data is for Friday `2026-09-25`.
6. Click **Live** to return to today.

What the banners mean:

| Banner | Meaning |
|---|---|
| *… is not a dated source: this shows its current data, not 2026-09-29* | The source keeps no history (for example, the history lake was not built) |
| *Latest data on or before 2026-09-29 is from 2026-09-25* | Nothing was written for the day you picked; you see the last earlier day |

### Known at: time travel

With a date picked, the clock field beside it (**Known at**) asks *as known when?*. Enter a time
(New York time) to see that business date as the system knew it at that moment: corrections made later
are left out. The **×** clears it and returns to the latest knowledge.

Use it to answer "what did we report at 6 pm, before the restatement?". Sources that keep versions
(Delta Lake) honour it; others have only one version and show it.

## Compare: what changed

Compare lists every field that differs in one entity between two business dates.

Worked example:

1. Pick `2026-09-30` in the date box and open `TRD T-10001`.
2. Click **Compare**. You should see *What changed in T-10001*, comparing `2026-09-30` with the business
   day before, `2026-09-29`.
3. Each row shows the field's label and path, the value on each date, and the change for numbers
   (see the example below).
4. Choose any two dates at the top to compare further apart, or give the earlier side a *known at* time
   to see what was restated on the same date.
5. Filter to *changed*, *added* or *removed*. **CSV** downloads the comparison.

Example rows for `T-10001`, 2026-09-29 against 2026-09-30:

| Field | Path | Before | After | Change |
|---|---|---|---|---|
| MTM (USD) | `mtm` | 1,904,860 | 1,875,863 | −28,997 |
| DV01 | `risk.dv01` | −155,879 | −155,245 | +634 |
| DV01 | `sensitivities[3M].dv01` | −5,619 | −5,544 | +75 |

Lists are matched by their identifiers or natural keys (a tenor such as `3M`, a date, a code), so an
inserted cashflow shows as one addition, not as every later cashflow changed. That is why the path
above reads `sensitivities[3M]` rather than `sensitivities[0]`.

The same comparison is available to programs:

```bash
curl -s 'http://localhost:18480/api/v1/history/trade/T-10001/diff?from=2026-09-29&to=2026-09-30'
```

It also takes `fromKnownAt` and `toKnownAt`. See [API_GUIDE.md](API_GUIDE.md).

## Search by value

Add `where` after a mnemonic to find entities by what they contain.

```text
TRD where mtm > 1m order by mtm desc limit 50
```

You should see a results page with a summary line such as `163 of 750 trades match; the first 50 are
shown`, the time it took, and a table: one row per trade, with a column for each field the query uses
(here **MTM (USD)**). Click an id to open it.

### The parts of a search

```text
<MNEMONIC> [where <condition>] [order by <field> [desc]] [limit <N>]
```

| Part | Rules | Example |
|---|---|---|
| Mnemonic | Any mnemonic, or a kind name | `TRD`, `NSET`, `customer` |
| Field | As named in the document; dots for nested fields, brackets for list items | `mtm`, `counterparty.name`, `legs[0].rate` |
| Compare | `=`  `!=`  `<`  `<=`  `>`  `>=` | `assetClass = 'Rates'` |
| Text | `contains` and `startswith`, case-insensitive; `contains` also looks inside lists | `counterparty.name contains 'Meridian'` |
| Combine | `and`, `or`, `not`, brackets; put brackets after `not` | `mtm > 1m and not (status = 'Matured')` |
| Amounts | `k` thousand, `m` million, `bn` billion | `250k`, `1.5m`, `2bn` |
| Text values | in single quotes | `'Rates'` |
| Sort | `order by <field>`, add `desc` for largest first | `order by mtm desc` |
| Size | `limit N`, from 1 to 1000 | `limit 20` |

Without `limit`, a search shows the number of results set in *My account → Settings* (100 unless you
change it).

### Examples to try

```text
TRD where notional >= 250m and assetClass = 'Rates' order by mtm desc limit 20
TRD where counterparty.name contains 'Meridian'
TRD where mtm < -1m order by mtm
NSET where utilisation > 0.5 order by utilisation desc
LIM where status = 'Early warning'
CUST where totalDeposits > 100k order by totalDeposits desc
VRNT where significance contains 'pathogenic'
```

### Good to know

- A search follows the business date in the top bar.
- It sees only what your role may see; a masked field never matches.
- If a slow source did not answer in time, or the scan limit was reached, the page says *results may be
  incomplete*.
- A mistake gives an error such as `DRS-4004 cannot read the condition: …` with the position.
- **CSV** downloads the results. **Watch as a monitor** saves the first 50 results as a live monitor
  (see [Monitors](#monitors)).

## Export, print and share

The buttons in a view's title line, and the **↓** in each panel header.

| Action | Where | What you get |
|---|---|---|
| Panel as CSV | **↓** in a panel header | Tables and ladders as they are; key/value panels as field and value; charts as one column per series; surfaces as a grid; bars as label and value |
| Search as CSV | **CSV** on the results page | One row per result |
| Comparison as CSV | **CSV** on the Compare page | One row per change |
| Document as JSON | **JSON** in the title line | The whole document, as your role may see it |
| Print or PDF | **Print** | A clean light page without the top bar and buttons; choose *Save as PDF* in the print dialog |
| Share | **Share** | A link copied to the clipboard (the button briefly confirms) |

Worked example (CSV): open `TRD T-10001` and click **↓** on *Cashflows*. You get a file whose first
lines are:

```text
Pay date,Leg,Type,Rate,Amount,PV
2025-09-29,2,Float,0.035377,-1403091,0
2025-12-31,2,Float,0.036186,-2310878,0
```

Numbers arrive as plain numbers (`-1403091`, `0.0425` for `4.25%`), so a spreadsheet can add them up.
File names carry the business date when one is picked. Each download also has a direct address, for
example `/export/trade/T-10001/schedule.csv` (`schedule` is the panel's id) or `/export/trade/T-10001.json`.

**Share** links:

- in Live, the link is the view's address, such as `http://localhost:17480/v/trade/T-10001`;
- with a date picked, the link opens that date (`/asof?d=2026-09-29&next=…`), and with a *known at*
  time, that exact moment. A colleague sees what you see, within their own permissions.

## Monitors

A monitor is a **live watchlist**: one row per entity, each row showing that entity's key figures (its
strip) as they tick. Rows can be of different kinds.

**Monitors** in the top bar (`/m`) lists *Yours* and the *Starters* the packs offer. Opening a starter
saves a copy to your account. (The `finance` pack offers *Credit watch* and `logistics` *Fleet watch*;
the banking packs offer none, so start from a search.)

Worked example: build a monitor from a search.

1. Type `NSET where utilisation > 0.7 order by utilisation desc` and press Enter.
2. In the box beside **Watch as a monitor**, replace `NSET search` with `High utilisation`.
3. Click **Watch as a monitor**. The monitor page opens: rows such as `NS-CASCADIA-TKY`,
   `NS-NORTHBRIDGE-NY`, `NS-SUMMIT-LDN`, each with its strip: Trades, Net MTM, Collateral, EE peak,
   PFE 95 peak, Limit, Utilisation and CVA.
4. In **Add an entity**, type `TRD T-10001` and press Enter. The trade joins the list.
5. Click **×** on a row to remove it. **Delete monitor** removes the whole monitor.

The monitor is saved on the server under your account, so it follows you to any browser. All rows share
one live connection.

## Alerts

An alert rule watches **one entity** and tells you when a condition becomes true.

- The **server** checks every rule on every change of its entity, even when nobody has Drishti open.
- A rule alerts **once** when its condition becomes true, and re-arms when it turns false again.
- A rule that is already true when you save it alerts straight away.
- New alerts show as a **toast** and a count on the **bell** in the top bar, on every page. Click the
  bell to see them all, and to allow browser notifications.

Worked example:

1. Open `NSET NS-SUMMIT-NY` and click **Alert** in the title line. The *Alerts* page opens with
   **Kind** `netting-set` and **Id** `NS-SUMMIT-NY` filled in.
2. Fill in **Name** `Summit utilisation`, **When** `$.utilisation > 0.5`, **Severity** `warn`, and
   **Message** `${$.nettingSetId}: ${fmt($.utilisation, 'pct0')} of limit`.
3. Click **Save rule**. The utilisation is already about 58 %, so you should at once see a toast, the
   bell showing `1`, and under *Recent alerts* a line `warn NS-SUMMIT-NY NS-SUMMIT-NY: 58% of limit`.
4. *Your rules* lists the rule; **Delete** removes it.

### Writing conditions

A condition is a Rachana-EL expression over the entity's document. `$` is the document; `$.field`
reads a field.

```text
$.utilisation > 0.8                          a netting set near its limit
$.mtm < -450000                              a trade's MTM below −450k
$.netMtm < -1500000 && $.utilisation > 0.6   both at once
$.status == 'Early warning'                  a credit limit's status
```

In a rule (unlike a search) write `==` for equality, `&&` for and, `||` for or, and full numbers
(`1500000`, not `1.5m`). The message is a template: `${…}` holds an expression, and `fmt(value, 'pct0')`,
`fmt(value, 'signed0')` or `fmt(value, 'compact')` format numbers. The full language is in
[RACHANA_REFERENCE.md](RACHANA_REFERENCE.md).

Some packs suggest rules for their kinds (`finance`: *PFE near limit*, *MTM below −450k*); they appear
on the Alerts page and fill the form when clicked.

> **Tip:** a condition that cannot be evaluated (a misspelled field, say) is skipped, not alerted.
> Check a field's exact name with `F9` first.

Rules are kept with your account; the most recent 200 alerts per user are kept in the server's memory,
so a server restart clears the alert history but not the rules. More in the
[Monitors and alerts guide](../console/web/guides/monitors-and-alerts.md).

## Workspaces

A workspace puts up to four live views on one screen. A pane can **follow** another: a link you click
in the followed pane opens in the following pane.

**Workspaces** in the top bar (`/w`) lists yours and the starters. Starters come from packs that
define them (`finance`: *Credit desk*, *Rates*, *Cross-asset*; `logistics`: one more) and from
`console/config/workspaces.yaml`.

Worked example (with the `finance` pack):

1. Open `/w` and click **Credit desk**. You see three panes: the netting set `NS-NORTH-01` (large), the
   trade `IRS-48213` and the curve `USD-SOFR`.
2. In the netting-set pane, click another member trade. Pane 2 follows pane 1, so it opens that trade.
3. Change **Layout** to *Two by two*, click **Pane** to add a fourth pane, and type `TRD FXS-20931` in its
   **Entity for this pane** box.
4. Set its **follows** to `—` so it stays put.
5. Click **Save as…**, name it `My credit desk`, and click OK. It now appears under *Yours*.

| Control | Does |
|---|---|
| Layout | *Two columns*, *Three columns*, *Two by two*, *One large, two stacked* |
| Entity for this pane | A command or id (`TRD T-10001`, `NS-SUMMIT-NY`), then Enter |
| follows | Which pane this one follows, or `—` |
| Pane / × | Add a pane (up to four) / remove one |
| Save / Save as… / Delete | Keep it under its name / a new name / remove it |
| `Alt+1`…`Alt+4` | Move between panes |

Workspaces are saved to your account (up to 50 each).

**If no starters are listed** (the banking packs ship none), an administrator can add one for everybody
to `console/config/workspaces.yaml`; it appears after the console restarts:

```yaml
templates:
  Counterparty credit:
    description: A netting set, the trade you pick in it, and the discount curve.
    layout: "1+2"
    panes:
      - { ref: { kind: netting-set, id: NS-SUMMIT-NY }, title: Netting set }
      - { ref: { kind: trade, id: T-10001 }, follows: 0, title: Selected trade }
      - { ref: { kind: ir-curve, id: CRV-USD-OIS }, title: Discount curve }
```

`follows: 0` means "follow the first pane". See the [Workspaces guide](../console/web/guides/workspaces.md).

## Your settings

*My account* (`/account`, or click your name in the top bar) shows your profile and roles, your
settings, and a form to change your password. Settings are kept on the server, so they follow you to
any browser.

| Setting | Choices | Example |
|---|---|---|
| Theme | *This browser's choice* or one of the seven themes | `Wallstreet` |
| After signing in, open | Any console address | `/t`, `/w/Rates`, `/m/High utilisation`, `/v/trade/T-10001` |
| Clock time zone | Default (NY) or a listed zone | `Europe/London` |
| Density | *Comfortable* or *Compact* (more on screen) | `Compact` |
| Flash changed values | On or off | off for a calmer screen |
| Search results | 10 to 1000 | `50` |

Click **Save settings**.

**Pinning.** **Pin** in a view's title line adds the entity to *Pinned* on the terminal home (`/t`), up
to 20. The button then reads **Pinned**; press it again to unpin.

**Changing your password.** Type the current password and the new one twice (at least 10 characters,
with letters and digits), then click **Change password**. Users, roles and password rules are covered
in [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

## Themes

The palette icon in the top bar offers seven themes:

| Theme | Look |
|---|---|
| Terminal (default) | dark, amber accents |
| Parchment | light |
| Blue | dark, cyan to violet |
| Wall Street | the Bloomberg colours: black, amber, yellow |
| Green | dark, green accents |
| Crimson / Crimson dark | Harvard crimson with indigo |

Each theme's two colours shade the page, the top bar, view and panel headers and accent buttons; panel
bodies, tables and figures stay on solid colours so they read clearly. If your system asks for more
contrast or less transparency, the solid look is used. Signed in, your choice is saved to your account;
otherwise the browser remembers it.

## Domain packs: choosing what you see

If you may use more than one pack, the box icon in the top bar shows how many are on (`12 packs`).

1. Click it. You see a tick box per pack (*Banking core*, *Market data*, *Trading*, …).
2. Untick the ones you do not need, say everything except *Trading* and *Counterparty credit risk*.
3. Click **Apply**.

The examples on `/t`, the suggestions, the help and search now cover only those packs. Commands for a
pack you switched off stop working until you switch it back on. You can choose only among the packs an
administrator assigned to you; see [PACKS.md](PACKS.md).

## Sutra Studio: changing how a screen looks

A **Sutra** is a layout, written as a Markdown document with the layout itself in one fenced `sutra`
block. **Studio** (`/studio`, in the top bar) is the editor. You need the *author* role; saving must be
switched on for the server (`DRISHTI_STUDIO_SAVE=true`).

The Studio page has:

- a **Sutra** picker (every loaded Sutra, `irs-fixfloat v1 · trade`, or *New Sutra…*);
- **Kind** and **Id** of the entity to preview against;
- **Preview** (or `Ctrl+Enter`), **Start from inference**, and **Save** (or **Submit for review**);
- an editor with a toolbar, **Insert…** (a sutra block, a strip field, or any panel kind) and **Jump to…**;
- on the right, the **Preview**, the rendered **Document**, and **Sample JSON** (paste your own document and
  tick *Preview against this JSON*);
- a list of **problems** by line number when the Sutra has mistakes.

Worked example: add a one-day P&L figure to the swap's strip.

1. Open `/studio?sutra=irs-fixfloat@1&kind=trade&id=T-10001`. The editor shows the Sutra; the preview
   shows `T-10001`.
2. In the `sutra` block, change `version: 1` to `version: 2`.
3. Under `strip:`, after the `MTM (USD)` line, add the line shown below this list
   (same indentation as the other strip lines).
4. Press `Ctrl+Enter`. The preview's strip now has **P&L 1D (USD)**, and the status line says
   `Preview in … ms. Not saved.`
5. Make a mistake on purpose, such as `bind: $.pnl1d +`, and preview again: the problems list names the
   line and the error. Undo it.
6. Type a note in *What changed? (for the reviewer)*, such as `Adds 1-day P&L to the strip`, and click **Submit for review** (or **Save** when review is off).
   You should see `Submitted irs-fixfloat v2 for review as …. It goes live when an approver approves it (Reviews).`

The line to add in step 3:

```yaml
  - { label: P&L 1D (USD), bind: $.pnl1d, fmt: signed0, tone: sign }
```

Bump the version whenever you change a Sutra that is already live. The saved file goes into the site
Sutra folder (`./sutras/…/irs-fixfloat.v2.sutra.md`), and views use the highest version.

**Start from inference** turns what Drishti infers for the entity into an editable Sutra: a quick
start for a kind that has no Sutra.

The [Sutra Studio tutorial](../console/web/guides/sutra-studio.md) and the
[Sutra guide](../console/web/guides/sutra-guide.md) go much further.

### Reviews: approving a Sutra

With review on (the default), a Studio save is a **proposal**. Nothing changes for anyone until an
approver approves it.

1. Open **Reviews** in Studio (`/studio/reviews`). *Pending* proposals are listed with their Sutra,
   note, author and time.
2. Click one. You see *Changes against the live Sutra* (or *New version: the whole Sutra*) as a diff.
3. Add a **Comment** and click **Approve and publish**: the Sutra goes live, and the next view uses it.
   Or give a **Reason** and click **Reject**. The author can **Withdraw** their own.

Rules: approvers are users with the `approver` or `admin` role; with sign-in on, nobody approves their
own proposal (four eyes); a proposal whose live Sutra changed since it was made cannot be approved
(reject it and propose again); every step is in the audit log.

## Administration

Admins see **Admin** in the top bar. The admin pages are:

| Page | Address | What you do there |
|---|---|---|
| Users | `/admin/users` | Create users; set roles and the packs each user may use; enable, disable, reset passwords |
| Audit log | `/admin/audit` | Sign-ins, failures, lockouts, user changes, Sutra proposals and approvals, cache purges |
| Health | `/admin/health` | Every connector and pack: up or down, reads, timings. Refreshes every 5 seconds |
| Caches | `/admin/caches` | What the engine and each connector hold; **Purge** one or **Purge all** (safe at any time) |

Worked example: check that every data source is up.

1. Open `/admin/health`. Each connector (for example `trading-store`, `market-store`, `demo`) shows its
   state. A Delta Lake connector whose folder is missing shows `DOWN: no Delta tables under …`.
2. The same figures are at `GET /api/v1/admin/health` for external monitoring. A shorter list of
   sources, with their kinds and health, comes from the command below.

```bash
curl -s localhost:18480/api/v1/sources
```

Managing users and roles is explained in [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

## Help

- **Help** in the top bar, or `F1`, opens the help centre (`/help`): tutorials, guides, every reference
  document and each pack's guides, with search. On any page, `F1` opens the guide for that page.
- The **?** in each panel header explains that panel kind.
- **About** (`/about`) shows the version, the Sutras loaded and the health of each source.

## On a phone

Drishti works in Safari on iPhone and Chrome on Android, with no app to install. To put it on your home
screen, use *Share → Add to Home Screen* (iPhone) or *⋮ → Add to Home screen* (Android). It then opens
full-screen at the terminal.

- **Function keys** become a swipeable row of buttons at the bottom of every view: tap **F9 Raw JSON**,
  **F7 Netting set**, **← Back**.
- **Command line:** type a command and tap a suggestion.
- **Layout:** panels stack in one column, the strip shows two figures per row, and wide tables scroll
  sideways inside their panel. Workspace panes stack too.
- **Live values** keep ticking.
- **Studio** is left out on phones; everything else, including help and administration, works.
