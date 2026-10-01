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

If Drishti is not running yet, start with [QUICKSTART.md](QUICKSTART.md) (ten minutes) or
[GETTING_STARTED.md](GETTING_STARTED.md) (every step explained).

The examples use the banking packs (`trading`, `counterparty-risk` and the others) with their sample
data, so ids such as `MX-20000001` and `NS-SUMMIT-NY` exist. If your administrator loaded other packs, the
**Examples** list on the terminal page shows ids that work for you, and every pack's guide (*Help → Help
centre → Domain packs*) has a *Finding things* section with the same commands for its own kinds.

**Contents**

| Section | What you learn |
|---|---|
| [The pages at a glance](#the-pages-at-a-glance) | Every page and its address |
| [The top bar](#the-top-bar) | The menus and the round tools on the right |
| [The command line](#the-command-line) | Opening an entity, suggestions, what an error means |
| [Pick lists](#pick-lists-when-a-command-names-several-entities) | `TRD MX-200000`, `CPTY north`, `TRD productType=Revolver`, `TRD` |
| [Tables: paging and the keyboard](#tables-sorting-filtering-paging-and-the-keyboard) | The pager, ▲ ▼, and the keys that walk a table |
| [Reading a view](#reading-a-view) | Title, strip, panels, links, provenance |
| [Keyboard](#keyboard) | Every key in one table |
| [Live updates](#live-updates), [Business dates](#business-dates-live-or-a-day-in-the-past), [Compare](#compare-what-changed) | Ticking values, past dates, what changed |
| [Search by value](#search-by-value) | `where` searches, sorting and limits |
| [Export, print and share](#export-print-and-share) | CSV, JSON, PDF and links |
| [Monitors](#monitors), [Alerts](#alerts), [Workspaces](#workspaces) | Watching many entities at once |
| [Your settings](#your-settings), [Themes](#themes), [Domain packs](#domain-packs-choosing-what-you-see) | Making the console yours |
| [Sutra Studio](#sutra-studio-changing-how-a-screen-looks) | Changing how a screen looks |
| [Administration](#administration) | Users, roles, packs, audit, health, caches |

## The pages at a glance

| Page | Address | What it is for |
|---|---|---|
| Landing | `/` | The start page, with a button to open the terminal |
| Terminal | `/t` | The command line, your pinned entities and example commands |
| View | `/v/<kind>/<id>` | One entity, laid out in full (for example `/v/trade/MX-20000001`) |
| Pick list and search results | `/s?q=…` | Entities a command names (`TRD MX-200000`) or a `where` search finds |
| Compare | `/compare/<kind>/<id>` | What changed in an entity between two business dates |
| Impact | `/impact/<kind>/<id>` | What depends on an entity (F8) |
| Monitors | `/m` | Live watchlists |
| Alerts | `/alerts` | Your alert rules and recent alerts |
| Workspaces | `/w` | Several live views on one screen |
| Studio | `/studio`, `/studio/reviews` | Edit and preview layouts (Sutras); approve proposed ones |
| My account | `/account` | Your profile, settings and password |
| Admin | `/admin/users`, `/admin/roles`, `/admin/packs`, `/admin/audit`, `/admin/health`, `/admin/caches` | Users, roles, packs, audit log, health and caches (admins only) |
| Help | `/help` | Tutorials, guides and every reference |
| About | `/about` | Version, licence, loaded Sutras and the health of each source |

## The top bar

Every page after the landing page has the same top bar, on one row.

```text
◉ Drishti  > TRD MX-20000001, CPTY north …  [2026-09-30] ● Live   Views ▾  Build ▾  Admin ▾  Help ▾   ● [bell] [box 12] [palette] (A) Ash ▾
```

**Beside the brand** (click it to go back to `/`) are the [command line](#the-command-line), which takes the
free space, and the [business date](#business-dates-live-or-a-day-in-the-past) box with its **Live** button.
Below 1100 pixels wide they move to a line of their own.

**Then four menus.** Below 1280 pixels they show as icons only. Each opens a wide panel
(a *mega menu*) in which every entry has an icon and one line saying what it does. The menu holding the page
you are on is highlighted.

| Menu | Column | Entries |
|---|---|---|
| **Views** | Look up | *Terminal* (`/t`), *Search & pick lists* (`/s`) |
| | Watch | *Workspaces* (`/w`), *Monitors* (`/m`), *Alerts* (`/alerts`) |
| **Build** | Layouts | *Sutra Studio* (`/studio`), *Reviews* (`/studio/reviews`) |
| | Learn | *Sutra guide*, *Build a pack* |
| **Admin** (admins only) | People & access | *Users*, *Roles*, *Audit log* |
| | Operations | *Packs*, *Health*, *Caches* |
| **Help** | Learn | *Help centre* (`/help`, also `F1`), *Install and run*, *About* |

**On the right:** round tools, from left to right.

| Tool | Looks like | What it does |
|---|---|---|
| Live | a dot, only on a view | Glows while the view streams; amber while it reconnects or while the tab is paused; grey for a static view (a past date). Hover over it to read `Live, built 1.92 ms`: how long the server took to build the view |
| Alerts | a bell | Opens `/alerts`. A number on the bell counts alerts you have not seen ([Alerts](#alerts)) |
| Packs | a box with a number | Appears when you may use more than one pack. The number is how many you have switched on; hover to read their names ([Domain packs](#domain-packs-choosing-what-you-see)) |
| Theme | a palette | Seven themes ([Themes](#themes)) |
| You | a round avatar with your initial and name | A menu: your name, desk and roles, a clock in your time zone (`NY` by default), **My account**, **Settings**, and **Sign out** when sign-in is on |

Worked example: find the audit log without typing an address.

1. Click **Admin** in the top bar. A panel opens with two columns, *People & access* and *Operations*.
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

- The **mnemonic** names a kind of entity: `TRD` is a trade, `NSET` a netting set, `GENE` a gene.
- The **id** names one entity of that kind: `MX-20000001`.
- `<GO>` means "press Enter".

Examples:

```text
TRD MX-20000001          an interest rate swap
TRD WSS-1500025          an FX option
NSET NS-SUMMIT-NY    a netting set
CRV CRV-USD-OIS      the USD SOFR discount curve
CPTY CP-NORTHBRIDGE  a counterparty
CUST CUST-100231     a retail customer
VRNT VRNT-BRAF-V600E a genetic variant
```

**A bare id** works when its shape tells Drishti the kind. Type `MX-20000001` and press Enter: it opens
the same trade as `TRD MX-20000001`.

**Case** never matters: `trd t-10001` opens `MX-20000001`.

**One match opens; several give a pick list.** If what you typed names exactly one entity, it opens. If it
names several (`TRD MX-200000`, the start of an id), you get a table to pick from. See
[Pick lists](#pick-lists-when-a-command-names-several-entities).

Mnemonics come from the packs that are switched on. The full list, pack by pack, is in
[PACKS.md](PACKS.md#the-packs-that-ship). The most used banking ones:

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
| `LCR` | liquidity coverage ratio | `LCR LCR-NY` |

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

Each suggestion shows the mnemonic, the id (the letters you typed are highlighted) and a subtitle, for
example:

```text
TRD   MX-20000001   Trade · Interest rate swap (fixed/float) · Meridian Reinsurance Ltd · AUD 242m
```

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
  for `TRD productType=Revolver`. Typing `MYBOOK <GO>` then does the same as the command; anything you add after
  the word is kept (`MYBOOK F3`). A mnemonic or a pack code cannot be an alias. The same through the API:
  `GET` / `PUT /api/v1/command/aliases` and `GET /api/v1/command/history`.

### When a command does not work

| You see | Meaning | What to do |
|---|---|---|
| `DRS-4001 cannot read command 'XYZ MX-20000001'; try <MNEMONIC> <ID> <GO>, for example TRD IRS-48213 <GO>` on the terminal page | The first word is not a mnemonic, and the text is not an id any pack recognises | Type the first letter and pick from the suggestions; check the pack is switched on ([Domain packs](#domain-packs-choosing-what-you-see)) |
| A page headed *Pick a trade* that says `0 of 0 trades match` and *Nothing matches.* | The mnemonic is fine, but no trade's id starts with what you typed (`TRD MX-29999999`) and no title contains it | Type less of the id (`TRD MX-2`) and pick from the list |
| `DRS-1001 no source holds trade/MX-29999999` | You opened an address such as `/v/trade/MX-29999999` directly, and no source has that id | Check the id; use the command line to find it |
| `DRS-4004 'XYZ' is neither a mnemonic nor a kind; type it alone to see suggestions` | A pick list or search started with an unknown word | Type the first letter and pick a mnemonic |
| `DRS-5002 <you> may not open lcr entities` | Your roles, or the packs you have switched on, do not include that kind | Switch the pack on in the box menu; otherwise ask an administrator |

### A pack at a glance

Type a pack's code alone, for example `MKT <GO>`, to see what the market-data pack holds: each kind with its mnemonic
(`CRV`, `FXV`, `IRV`, …), how many there are, an example you can open, and the fields its pick list shows. Click a
mnemonic to list that kind. Every pack has a code (`BNK`, `MKT`, `TRDS`, `CCR`, `LIQ`, `RTL`, `GENO`, … see
[PACKS.md](PACKS.md#pack-codes)); the pack's name (`market-data`) works too.

## Pick lists: when a command names several entities

On a Bloomberg terminal, a command that matches several securities shows a list to pick from. Drishti does
the same. After you press Enter:

- if your command names **exactly one** entity, that entity opens;
- otherwise you get a **pick list** at `/s?q=…`, headed *Pick a trade* (or *Pick a book*, …), with one row
  per match and the kind's **key fields** beside each id.

### The forms

| You type | You get | Real result on the banking samples |
|---|---|---|
| `TRD MX-20000001` | That trade opens (an exact id) | the swap `MX-20000001` |
| `TRD MX-200000` | Trades whose id **starts with** `MX-200000`, or whose title **contains** it | `99 of 99 trades match`: `MX-20000001` … `MX-20000099` |
| `CPTY north` | Counterparties whose id starts with, or title contains, `north` | one match, so `CP-NORTHBRIDGE` opens at once |
| `TRD MX-2*0` | `*` stands for any text, anywhere in the id | `19 of 19 trades match`: `MX-20000010`, `MX-20000020`, `MX-20000030`, … |
| `TRD productType=Revolver` | Trades whose field has that value | `6 of 750 trades match`: `CLY-3000055` … `CLY-3000060` |
| `TRD notional > 10m and currency = usd` | Trades matching a condition (no `where` needed) | `317 of 750 trades match; the first 100 are shown` |
| `TRD MX-2* currency=usd order by mtm desc` | An id pattern and a condition together, sorted | `53 of 750 trades match`, largest MTM first (`MX-20000003`) |
| `TRD` | Every trade | `750 of 750 trades match; the first 100 are shown` |

Rules:

- **Case never matters**, for ids, names and values: `trd t-100`, `CPTY NORTH` and `productType=revolver`
  all work. Text compared with `=` or `!=` ignores case in every search.
- **A word without `*`** matches ids that **start** with it and titles that **contain** it. **A word with
  `*`** is a pattern on the id only (`*SUMMIT*` matches `NS-SUMMIT-NY`).
- **A bare word after a comparison is a value:** `productType = Revolver` needs no quotes. Quote values that
  contain spaces: `status = 'Early warning'`.
- **Everything from [Search by value](#search-by-value) works here** (`and`, `or`, `not`, `contains`, `250k`,
  `order by`, `limit`). `not status = matured` means *not (status = matured)*.
- **How many rows** you see is your *Search results* setting (100 unless you change it in
  [Your settings](#your-settings)), or `limit N` at the end of the command. The table then pages through them
  25 at a time ([Tables](#tables-sorting-filtering-paging-and-the-keyboard)).
- **Pick lists follow the date** in the top bar, like every other read.

### The columns

The first column is the id (a link). Then come:

1. the fields your command uses (`currency` and `mtm` in `TRD MX-2* currency=usd order by mtm desc`);
2. the kind's **key fields**, which the pack declares under `columns:` in its `pack.yaml`
   ([PACKS.md](PACKS.md#columns-the-key-fields-of-a-pick-list)). For the banking packs:

   | Kind | Key fields shown |
   |---|---|
   | trade | Product type, Direction, Currency, Notional, MTM (USD), Maturity date, Book |
   | counterparty | Name, Rating, Sector, Country, Net MTM |
   | book | Name, Desk name, Trade count, MTM (USD), DV01 |

3. for a kind whose pack declares no key fields, the first plain fields of its documents, up to six columns
   in all, so a pick list always says more than the id.

A *Title* column appears only when titles say more than the id.

### Worked example: find the revolving credit facilities

1. Press `/`, type `TRD productType=Revolver` and press Enter.
2. You should see *Pick a trade*, the line `6 of 750 trades match`, the time it took, and a table:

   ```text
   TRD      Product type  Direction  Currency  Notional      MTM (USD)   Maturity date  Book
   CLY-3000055  REVOLVER      Long       CAD       202,000,000   3,459,176   2032-04-16     BOOK-CREDIT-3
   CLY-3000056  REVOLVER      Short      GBP       127,000,000   1,256,638   2035-09-28     BOOK-CREDIT-2
   CLY-3000057  REVOLVER      Long       AUD       156,000,000   1,498,057   2036-10-03     BOOK-CREDIT-1
   …
   ```

3. Press `↓` twice: the second row is selected. Press `Enter`: `CLY-3000056` opens.
4. Press `Alt+←` to come back to the list, and change the box at the top of the page to
   `TRD productType=Revolver order by mtm desc`. Press **Search**: the largest MTM is now first.
5. **CSV** downloads the list; **Watch as a monitor** turns its first 50 rows into a live watchlist
   ([Monitors](#monitors)).

## Tables: sorting, filtering, paging and the keyboard

Every table in the console (pick lists, search results, the tables inside views, admin lists) sorts and filters,
has a pager in its heading, and can be walked with the keyboard.

```text
| Largest trades     [Filter rows   ] ⧩  « ‹ 1–25 of 49 · page 1 of 2 › »  ▲ ▼ [25 rows ▾]  F2 ⤓ ? |  <- the heading
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
  all of a kind, sort or filter on the server instead: `TRD where notional > 200m order by notional desc`.
- Live tables keep their sort and filters when they update.

| Control | Does |
|---|---|
| `«` `‹` `›` `»` | First, previous, next and last page. Buttons that lead nowhere are greyed |
| `1–25 of 99 · page 1 of 4` | Which rows you are looking at |
| `▲` `▼` | Select the previous / next row, turning the page when needed |
| *rows per page* | 25, 50, 100 or 250. Your choice is **remembered in this browser** and applies to every table, at once |

**The keyboard.** Click a row, or press `Tab` until the table has the focus, then:

| Key | Does |
|---|---|
| `↓` / `↑` | Select the next / previous row; at the end of a page the next page opens |
| `PgDn` / `PgUp` | Move the selection one page down / up |
| `Home` / `End` | Select the first / last row (of all pages) |
| `Enter` | Open the selected row's link (its id) |

Worked example:

1. Type `TRD MX-200000` and press Enter. The pager reads `1–25 of 99 · page 1 of 4`.
2. Click the first row (`MX-20000001`), then press `End`. The last page opens with `MX-20000099` selected.
3. Press `PgUp`: the selection moves 25 rows up, to `MX-20000074`, on page 3.
4. Choose *50 rows*. The pager reads `51–99 of 99 · page 2 of 2`, still showing your selection.
5. Press `Enter`: `MX-20000074` opens. Open any other list: it shows 50 rows per page too.

Tables that update live (a monitor, a ticking table inside a view) keep their page and selection when
their rows change.

## Reading a view

Open `TRD MX-20000001`. A view has five parts.

### 1 · The title line

```text
[Rates · Interest rate swap (fixed/float)]  MX-20000001  with Meridian Reinsurance Ltd   Alert  Pin  Share  JSON  Print
```

- The **pill** says what the entity is.
- The **id**, and the main party (**with …**). The party is a link: click it to open the counterparty.
- The buttons on the right are explained in [Export, print and share](#export-print-and-share),
  [Alerts](#alerts) and [Pinning](#your-settings). **Compare** appears there too when a past date is picked.

### 2 · The strip

The row of key figures under the title. For `MX-20000001`:

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

`MX-20000001` shows *Terms*, *Legs* (one tab per leg), *Cashflows*, *How this view was built*, a curve
chart, *DV01 by bucket*, *Daily P&L, last 20 days* and *Linked entities*.

Panel kinds you will meet:

| Kind | Looks like | Example |
|---|---|---|
| `kv` | label and value pairs | *Terms* in `TRD MX-20000001` |
| `table` | rows and columns, totals for money | member trades in `NSET NS-SUMMIT-NY` |
| `tabs` | one tab per element of a list | *Legs* in `TRD MX-20000001` |
| `ladder` | dated rows, the next one highlighted | *Cashflows* in `TRD MX-20000001` |
| `line` / `area` | a chart over tenors or dates | the curve in `CRV CRV-USD-OIS` |
| `hbar` | horizontal bars | *DV01 by bucket* |
| `surface` | a grid as a heatmap, or a 3D surface you can rotate | `FXV FXV-EURUSD` (*Heatmap* / *3D* buttons) |
| `status`, `gauge`, `markdown` | a status list, a dial, formatted text | various packs |
| `links` | linked entities with badges | *Linked entities* everywhere |
| `provenance` | how the view was built | *How this view was built* |

The [panel kinds guide](../../console/web/guides/panel-kinds.md) shows each one in detail.

**No data available.** A panel whose data is missing, or not in the expected shape, says *No data
available*. The rest of the view still renders. Real feeds are often incomplete; this is normal.

### 4 · Links

Every identifier in a view is a door. Click `BOOK-RATES-3` in the strip and the book opens. The
*Linked entities* panel lists everything the entity points to, each with a **badge** read from the
target (for example `EE 32.4m` on a netting set). A badge that says *pending* means the target did not
answer in time; open it to see it.

**Ids in tables are links too.** A table column that shows an identifier (a column bound to a field whose
name ends in `Id`, `Ref` or `_id`, such as `tradeId`, `counterpartyId` or `bookRef`) links each value to its
entity, as long as a pack recognises the id. Example: open `BOOK BOOK-RATES-1`; its *Largest trades* table
(`F2`) starts with the *Trade* column (`MX-20000043`, `MX-20000011`, …), and clicking `MX-20000043` opens that trade. With the table selected, `↓` to a row and
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

The footer repeats the source and generation, and the business date when one is picked
(`trading-store · gen 7 · as of 2026-09-29`).

## Keyboard

| Key | Does |
|---|---|
| `/` | Focus the command line, from anywhere |
| `↑` `↓` | In the command line: move through the suggestions. In a selected table: move the selected row |
| `Tab` | Complete the highlighted suggestion |
| `Enter` | Open the highlighted suggestion, or run the command (`<GO>`). In a table: open the selected row |
| `PgUp` `PgDn` | In a selected table: move the selection a page up or down |
| `Home` `End` | In a selected table: select the first or last row |
| `Esc` | Close the suggestions, a menu, or the raw JSON drawer |
| `F1` | Help for the page you are on |
| `F2`–`F6` | Jump to the panel that shows that key in its header |
| `F7` | Open the main linked entity (for a trade: its netting set) |
| `F8` | Impact: what depends on this entity |
| `F9` | Raw JSON of the entity, with its source and generation |
| `Alt+←` | Back |
| `Alt+1`…`Alt+4` | In a workspace: move to pane 1 to 4 |

The keys a view offers are also buttons along the bottom of the screen, so you can click them.

### F9 · Raw JSON

Press `F9` in `TRD MX-20000001`. A drawer opens on the right with the full document, starting
`{"tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", …}`, and its source and generation. Press `Esc`
to close it. If your role does not have raw access, fields such as `trader` are masked.

### F8 · Impact

Impact answers "what depends on this, and how much is at stake?". Open `TRD MX-20000001` and press `F8`.
You should see *Impact of MX-20000001*:

- **Depends on it directly:** *Netting set · 1*: `NS-MERIDIAN-RE-NY` with its net MTM.
- **Rolls up into:** *Credit limit · 1*: `LIM-MERIDIAN-RE` via `creditLimit`, with the limit amount.

Each group has a total. Click any id to open it. See the [Impact guide](../../console/web/guides/impact.md).

## Live updates

Views of live entities tick: figures change in place and flash briefly. Open `TRD MX-20000001` and watch
**MTM (USD)** for a few seconds.

- The **live dot** among the round tools of the top bar glows while the view streams. Hover over it to read
  how long the view took to build (`Live, built 1.92 ms`).
- All views, panes, the bell and monitors in one browser tab share **one** connection.
- The dot turns **amber** when the connection dropped (*Reconnecting…*): the page reconnects and repaints by
  itself.
- It also turns amber when the tab was in the background for 10 seconds and gave its connection back
  (*Paused while hidden*). Show the tab and it resumes.
- It is **grey** on a view of a picked past date, which never ticks: it is a snapshot.

Screen readers hear the full state, including the server's rolling p99 (`Live, p99 2 ms`). To see the same
figures yourself, ask the server: `curl -s http://localhost:18480/api/v1/health/live`
([PERFORMANCE.md](../admin/PERFORMANCE.md#4-live-latency)).

To turn off the flash, untick *Flash changed values* in [your settings](#your-settings).

## Business dates: live or a day in the past

The **Business date** box in the top bar says which day you are looking at.

- **Live** (green, the default) is today's business date on the New York calendar (`USNY`), streaming.
  Weekends and holidays count as the business day before.
- **A picked date** shows that day's end-of-day data as a static snapshot. The box turns amber.

Worked example (needs the sample history; see [GETTING_STARTED.md](GETTING_STARTED.md), Step 5):

1. Open `TRD MX-20000001`.
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

1. Pick `2026-09-30` in the date box and open `TRD MX-20000001`.
2. Click **Compare**. You should see *What changed in MX-20000001*, comparing `2026-09-30` with the business
   day before, `2026-09-29`.
3. Each row shows the field's label and path, the value on each date, and the change for numbers
   (see the example below).
4. Choose any two dates at the top to compare further apart, or give the earlier side a *known at* time
   to see what was restated on the same date.
5. Filter to *changed*, *added* or *removed*. **CSV** downloads the comparison.

Example rows for `MX-20000001`, 2026-09-29 against 2026-09-30:

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
curl -s 'http://localhost:18480/api/v1/history/trade/MX-20000001/diff?from=2026-09-29&to=2026-09-30'
```

It also takes `fromKnownAt` and `toKnownAt`. See [API_GUIDE.md](API_GUIDE.md).

## How fresh is it

The footer of every view names the source and says how long ago it last received new data: *trading-stream ·
updated 2 min ago · gen 41*. The age keeps counting while the page is open. When a source has received nothing for
longer than it should (its `stale-after`, set by your administrator), the view shows an amber banner at the top:
*trading-stream is behind: its last new data was 25 min ago, and it expects some within 15m. What you see may be out
of date.* Sources read on demand (a database) cannot tell, and show no age.

## A number over time

Click a number in a view (a strip value, a field, a cell of a table) and a window shows that field over the last 30
business days: a line chart, and the values day by day with the date the data is for and the connector it came from.
Choose 10, 30, 90 or 250 days at the top. Links still open what they name; a number that is a link opens the link.
The first line says where the value comes from: the path in the document, the connector, the business date the data
is for and the source's generation, with a link to the raw document.

Worked example: open `TRD MX-20000001`, click the MTM in the strip. You see *MTM (USD) · MX-20000001 over time*, a line
over 30 business days, and below it the values, newest first, each with *Data for* and *Source* (`trading-store`).
A *Data for* date in amber is earlier than the day it is listed under: the source carried its last value forward
(no data that day). An entity no dated source holds shows today's value with a note, since no history is kept.

**A pick list or search on two dates.** On the search page, set **Compare with** to an earlier business date and
press Search: each row shows today's value of every number with its change in brackets (`125  (+25)`), and entities
only one of the dates holds are marked *added* or *removed*.

The same through the API: `GET /api/v1/history/{kind}/{id}/series?path=$.mtm&days=30` and
`GET /api/v1/search/compare?q=…&from=2026-09-25&to=2026-09-30`.

## Asking in plain words

Type what you want in the command line, in words: `live trades over 5m in BOOK-RATES-3, biggest first`, and press
Enter. When the words are not a command, Drishti shows the search they make and how it read each part; nothing runs
until you press **Run it**. The search page has the same box, **In words**.

```
live trades over 5m in BOOK-RATES-3, biggest first
→ TRD where status = 'Live' and mtm > 5000000 and book = 'BOOK-RATES-3' order by mtm desc
   "trades" → Trade (TRD) · "live" → status is Live · "over 5m" → mtm > 5000000
   "in BOOK-RATES-3" → book is BOOK-RATES-3 · "biggest first" → sorted by mtm, largest first
```

It understands:

| You write | It means |
|---|---|
| a kind: `trades`, `counterparties`, `desk pnl`, a mnemonic | what to list |
| `over`, `above`, `more than`, `under`, `below`, `at least`, `at most` and a number (`5m`, `2.5 bn`, `10k`, `1,000,000`) | a comparison, on the field you name or the kind's main number (MTM, notional, amount…) |
| `between 10m and 50m` | both bounds |
| `negative mtm`, `positive pnl` | below or above zero |
| a value the data has: `live`, `USD`, `BOOK-RATES-3` (optionally after `in`, `for`, `with`) | that field equals it |
| `maturing before 2028`, `traded after 2025`, `maturing in 2027`, `before 2026-12-31` | dates |
| `biggest`, `top 10`, `smallest 5 … by notional`, `newest`, `sorted by` | the order, and how many |

It is deterministic: no AI service, only the server's own vocabulary (its kinds, and each kind's fields and the
values its documents have). Words it did not understand are listed as *Not understood, left out*, never guessed.
Change the words, or edit the search itself.

## Search by value

Add `where` after a mnemonic to find entities by what they contain.

```text
TRD where mtm > 1m order by mtm desc limit 50
```

You should see a page headed *Search by value*, a summary line such as `163 of 750 trades match; the first 50
are shown`, the time it took, and a table: one row per trade, with a column for each field the query uses
(here **MTM (USD)**), then the kind's key fields (*Product type*, *Direction*, *Currency*, …). Click an id to
open it, or walk the table with the keyboard ([Tables](#tables-sorting-filtering-paging-and-the-keyboard)).

`where` is optional: `TRD mtm > 1m` and `TRD productType=Revolver` work as well, and come back as a
[pick list](#pick-lists-when-a-command-names-several-entities). The rest of this section applies to both.

### The parts of a search

```text
<MNEMONIC> [where <condition>] [order by <field> [desc]] [limit <N>]
```

| Part | Rules | Example |
|---|---|---|
| Mnemonic | Any mnemonic, or a kind name | `TRD`, `NSET`, `customer` |
| Field | As named in the document; dots for nested fields, brackets for list items | `mtm`, `counterparty.name`, `legs[0].rate` |
| Compare | `=`  `!=`  `<`  `<=`  `>`  `>=`; text compared with `=` and `!=` ignores case | `assetClass = 'Rates'` (also matches `rates`) |
| Text | `contains` and `startswith`, case-insensitive; `contains` also looks inside lists | `counterparty.name contains 'Meridian'` |
| Combine | `and`, `or`, `not`, brackets. `not` applies to the comparison after it | `mtm > 1m and not status = 'Matured'` |
| Amounts | `k` thousand, `m` million, `bn` billion | `250k`, `1.5m`, `2bn` |
| Text values | in single quotes; a single word may go without | `'Early warning'`, `Rates` |
| Sort | `order by <field>`, add `desc` for largest first | `order by mtm desc` |
| Size | `limit N`, from 1 to 1000 | `limit 20` |

Without `limit`, a search shows the number of results set in *My account → Settings* (100 unless you
change it).

### Examples to try

| Search | Matches on the banking samples |
|---|---|
| `TRD where notional >= 250m and assetClass = 'Rates' order by mtm desc limit 20` | 4 of 750 trades |
| `TRD where counterparty.name contains 'Meridian'` | 76 of 750 trades |
| `TRD where mtm < -1m order by mtm` | 171 of 750 trades, most negative first |
| `TRD where assetClass = rates` | 156 of 750 trades (case ignored, no quotes needed) |
| `NSET where utilisation > 0.5 order by utilisation desc` | 46 of 66 netting sets |
| `LIM where status = 'Early warning'` | 1 of 18 credit limits |
| `CUST where totalDeposits > 100k order by totalDeposits desc` | 10 of 32 customers |
| `VRNT where significance contains 'pathogenic'` | 6 of 8 variants |

A search whose condition matches exactly one entity still shows its one-row table; only a command typed
without `where` opens a single match directly.

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
- with a date picked, the link opens that date (`/asof?d=2026-09-29&next=…`), and with a *known at*
  time, that exact moment. A colleague sees what you see, within their own permissions.

## Monitors

A monitor is a **live watchlist**: one row per entity, each row showing that entity's key figures (its
strip) as they tick. Rows can be of different kinds.

*Views → Monitors* in the top bar (`/m`) lists *Yours* and the *Starters* the packs offer. Opening a starter
saves a copy to your account. (The `finance` pack offers *Credit watch* and `logistics` *Fleet watch*;
the banking packs offer none, so start from a search.)

Worked example: build a monitor from a search.

1. Type `NSET where utilisation > 0.7 order by utilisation desc` and press Enter.
2. In the box beside **Watch as a monitor**, replace `NSET search` with `High utilisation`.
3. Click **Watch as a monitor**. The monitor page opens: rows such as `NS-CASCADIA-TKY`,
   `NS-NORTHBRIDGE-NY`, `NS-SUMMIT-LDN`, each with its strip: Trades, Net MTM, Collateral, EE peak,
   PFE 95 peak, Limit, Utilisation and CVA.
4. In **Add an entity**, type `TRD MX-20000001` and press Enter. The trade joins the list.
5. Click **×** on a row to remove it. **Delete monitor** removes the whole monitor.

The monitor is saved on the server under your account, so it follows you to any browser. All rows share
one live connection.

## Scheduled reports

A report is a search that runs on its own, on a schedule, and delivers its result as a CSV file. On the search page,
after a search, press **Schedule…** (or open **Views → Reports**). Give it a name, check the search, and say when:

| When | Runs |
|---|---|
| `business-days 18:30` | at 18:30 on weekdays that are not holidays in the server's calendar |
| `weekdays 07:00` | at 07:00 Monday to Friday |
| `daily 06:00` | every day at 06:00 |
| `hourly` | on the hour |
| `cron 0 0 8,12,16 * * MON-FRI` | a cron expression: second, minute, hour, day, month, weekday |

Times are in the business-date zone (New York by default). **Data for** chooses the current business day or the
one before (for a morning report on yesterday's close). **Deliver to** is the server's reports folder, or a
webhook (a URL your administrator allows; the CSV is posted with `X-Drishti-Report` and `X-Drishti-Business-Date`
headers). Email needs SMTP settings on the server and is not offered until then.

A report runs **as you**: it sees what your roles let you see when it runs, with the same redaction. If your
account is disabled or deleted, it stops. The list shows each report's next run and its last run (rows, where the
file went, or the error); **Run now** runs one at once. Worked example: name `Big losers`, search
`TRD where mtm < -10000000`, when `business-days 18:30`, deliver to the folder. At 18:30 on each business day a file
such as `data/reports/ash/Big_losers/Big_losers-2026-10-01-1830.csv` appears, starting
`kind,id,title,MTM (USD),…`.

## Alerts

An alert rule watches **one entity** and tells you when a condition becomes true.

- The **server** checks every rule on every change of its entity, even when nobody has Drishti open.
- A rule alerts **once** when its condition becomes true, and re-arms when it turns false again.
- A rule that is already true when you save it alerts straight away.
- New alerts show as a **toast** and a count on the **bell** among the round tools of the top bar, on every
  page. Click the bell to see them all, and to allow browser notifications. *Views → Alerts* opens the same page.

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

Rules are kept with your account, and so are the alerts they fire: the newest 1,000 per user
(`drishti.alerts.keep`) are kept in the identity database, so they are still there after a server restart. More in the
[Monitors and alerts guide](../../console/web/guides/monitors-and-alerts.md).

## Workspaces

A workspace puts up to four live views on one screen. A pane can **follow** another: a link you click
in the followed pane opens in the following pane.

*Views → Workspaces* in the top bar (`/w`) lists yours and the starters. Starters come from packs that
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
| Entity for this pane | A command or id (`TRD MX-20000001`, `NS-SUMMIT-NY`), then Enter |
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
      - { ref: { kind: trade, id: MX-20000001 }, follows: 0, title: Selected trade }
      - { ref: { kind: ir-curve, id: CRV-USD-OIS }, title: Discount curve }
```

`follows: 0` means "follow the first pane". See the [Workspaces guide](../../console/web/guides/workspaces.md).

### Sharing a workspace

Open one of your saved workspaces and press **Share…**. Tick **Everyone**, or name roles (`risk, trader`) and people
(`ravi, tess`), and press **Share**. They find it on their Workspaces page under **Shared with you**, marked
*shared by you · read-only*. They see it as you keep it: when you change and save it, they see the change. Panes on
kinds a reader may not open stay hidden from that reader. A reader who wants to change it presses **Save a copy…**,
which makes it their own. **Stop sharing** (or deleting the workspace) takes it away from everyone.

## Notes

Anyone who may open an entity can leave a note on it, for the next reader: *Restated on 28 Sep after the SOFR
fixing correction*, *Novation pending legal sign-off*. Press **Notes** in the view's header. The drawer lists the
notes, oldest first, with their author and time; **About** chooses the whole entity or one of its fields. A field
with a note shows a small amber dot. From the history window of a number (click the number), **Add a note** writes
about that field.

Only a note's author can edit it. Its author or an administrator can delete it. Every note, edit and deletion is
in the audit log (Admin → Audit). Notes are kept in the server's identity database, so everyone on that server sees
the same notes; another server has its own.

## Working with several servers

Your organisation may run more than one Drishti server: an open one most people use, and others for a restricted desk
or a pre-production copy. When the console knows of several, the top bar shows the current one as a coloured dot and
a name. Click it to switch, or choose **All servers…** to see each one's state, version, how you sign in to it, and
whether you are signed in there.

Each server has its own users and data, so you sign in to each separately. You can be signed in to several at once and
switch without signing in again. **Sign out** signs you out of the current server only. A link someone shares opens on
the server it was made on (it carries `?srv=…`). A server not shown in the list can still be reached with the link
`/connect/<its id>` if you were given one.

## Your settings

*My account* (`/account`) shows your profile and roles, your settings, and a form to change your password.
To get there, click your avatar at the right of the top bar and choose **My account**, or **Settings** to
jump straight to the settings (`/account#settings`). Settings are kept on the server, so they follow you to
any browser.

| Setting | Choices | Example |
|---|---|---|
| Theme | *This browser's choice* or one of the seven themes | `Wallstreet` |
| After signing in, open | Any console address | `/t`, `/w/Rates`, `/m/High utilisation`, `/v/trade/MX-20000001` |
| Clock time zone | Default (NY) or a listed zone | `Europe/London` |
| Density | *Comfortable* or *Compact* (more on screen) | `Compact` |
| Flash changed values | On or off | off for a calmer screen |
| Search results | 10 to 1000 | `50` |

Click **Save settings**.

**Pinning.** **Pin** in a view's title line adds the entity to *Pinned* on the terminal home (`/t`), up
to 20. The button then reads **Pinned**; press it again to unpin.

**Changing your password.** Type the current password and the new one twice (at least 10 characters,
with letters and digits), then click **Change password**. Users, roles and password rules are covered
in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

## Themes

The palette among the round tools of the top bar offers seven themes:

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

A pack brings one domain: its mnemonics, layouts and data. If you may use more than one pack, the round
**box** tool in the top bar shows how many you have switched on, for example `12`. Hover over it to read
their names (*Packs shown: banking-core, market-data, …*).

Worked example: see only trading and counterparty risk.

1. Click the box. A menu opens headed *Show these packs*, with one line per pack: a tick box, the pack's
   title (*Banking core*, *Market data*, *Trading*, …) and one line on what it covers.
2. Click **none** (top right of the menu): every box is cleared.
3. Tick *Trading* and *Counterparty credit risk*.
4. Click **Apply**. The page reloads and the box shows `2`.
5. Type `L`. The suggestions no longer offer `LCR` (liquidity) or `LOSS` (operational risk).
6. To undo, open the box, click **all**, then **Apply**.

What changes when a pack is off for you:

- its mnemonics leave the suggestions, and its examples leave `/t`, the help centre and the search page;
- opening one of its entities is refused with `DRS-5002 … may not open … entities`;
- a pack's parents stay usable through it: with *Counterparty credit risk* on, a netting set still links to
  its trades and counterparties (see [PACKS.md](PACKS.md#who-sees-which-pack)).

**Apply** needs at least one ticked pack; with none ticked it does nothing. The choice is saved to your
account. You can choose only among the packs an administrator assigned to you, and only among packs that
are switched on for everyone ([Admin → Packs](#admin--packs-switching-a-pack-off-for-everyone)).

## Sutra Studio: changing how a screen looks

A **Sutra** is a layout: one YAML file (`<name>.v<N>.sutra.yaml`) that starts with `rachana: 1`, the version of
the layout language, followed by the layout. It may say what it is for in `description:` and carry longer notes for
reviewers in `notes:`; neither changes the screen. **Studio** (`/studio`, *Build → Sutra Studio* in the top bar) is the editor. You need the *author* role; saving must be
switched on for the server (`DRISHTI_STUDIO_SAVE=true`).

The Studio page has:

- a **Sutra** picker (every loaded Sutra, `irs-fixfloat v1 · trade`, or *New Sutra…*);
- **Kind** and **Id** of the entity to preview against;
- **Preview** (or `Ctrl+Enter`), **Start from inference**, and **Save** (or **Submit for review**);
- a **YAML editor** that completes as you type: keys, panel kinds and the options each kind takes, formats, tones,
  entity kinds and expression functions, all taken from the server's schema of the language
  (`GET /api/v1/rachana/schema`);
- **live checking**: problems are listed by line number while you type, before you preview or save;
- a **Summary** of the layout (what it matches, the strip, the panels and their keys), read from the YAML;
- the **Preview**, and **Sample JSON** (paste your own document and preview against it);
- **test entities** kept for each Sutra, so you can preview against several entities in turn.

Worked example: add a one-day P&L figure to the swap's strip.

1. Open `/studio?sutra=irs-fixfloat@1&kind=trade&id=MX-20000001`. The editor shows the Sutra; the preview
   shows `MX-20000001`.
2. Near the top, under `rachana: 1` and `sutra: irs-fixfloat`, change `version: 1` to `version: 2`.
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
Sutra folder (`./sutras/…/irs-fixfloat.v2.sutra.yaml`), and views use the highest version.

**Start from inference** turns what Drishti infers for the entity into an editable Sutra: a quick
start for a kind that has no Sutra.

The [Sutra Studio tutorial](../../console/web/guides/sutra-studio.md) and the
[Sutra guide](../../console/web/guides/sutra-guide.md) go much further.

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

Admins see the **Admin** menu in the top bar. The admin pages also share a row of tabs, so you can move
between them without the menu.

| Page | Address | What you do there |
|---|---|---|
| Users | `/admin/users` | Create users; set roles and the packs each user may use; enable, disable, reset passwords |
| Roles | `/admin/roles` | Define roles: the kinds each opens, and its powers |
| Packs | `/admin/packs` | Switch a domain pack off or on for everyone |
| Audit log | `/admin/audit` | Sign-ins, failures, lockouts, user, role and pack changes, Sutra proposals and approvals, cache purges |
| Health | `/admin/health` | Every connector and pack: up or down, reads, timings. Refreshes every 5 seconds |
| Caches | `/admin/caches` | What the engine and each connector hold; **Purge** one or **Purge all** (safe at any time) |

### Admin → Roles: what a role may do

A role says which **kinds** of entity its holders may open, and which **powers** they have. The table lists
every role with its kinds (or *every kind*), its powers, how many users hold it, and who changed it last.

| Power | Lets its holders |
|---|---|
| raw JSON | see the raw document (`F9`, **JSON**) without masked fields |
| author Sutras | use Sutra Studio |
| approve Sutras | approve proposed Sutras in Reviews |
| administer | use every admin page |

Roles marked **built-in** come from the server's configuration and from packs (`viewer`, `author`,
`approver`, `admin`, and pack roles such as `trader`, `credit-risk` or `retail`). They are shown read-only.

Worked example: a role for credit analysts.

1. Open *Admin → Roles* and click **New role**.
2. **Name** `credit-analyst` (lower case, digits and hyphens); **Description** `Reads counterparties and
   credit exposure`.
3. Under *Add every kind of a pack*, click **Counterparty credit risk**: its kinds (`netting-set`,
   `credit-limit`, `exposure-profile`, …) fill the *Kinds it opens* box, one per line. Add `counterparty`
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
| Builds on | `banking-core, market-data`, and under it *needed by market-risk, counterparty-risk, …* |
| Kinds | `1 (TRD)`: the number of kinds and the first four mnemonics |
| Connectors | `trading-store, trading-stream` |
| Status | **on**, **off**, or **not loaded** |
| Changed | when and by whom it was last switched |

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

1. Open *Admin → Packs*. The *Operational and non-financial risk* row says **on**; *Builds on* reads
   `banking-core`; nothing needs it.
2. Click **Switch off**. The status turns to **off**, and *Changed* shows the time and your name.
3. Type `LOSS` in the command line: no suggestions come. In the box menu, the pack is gone.
4. Open *Admin → Audit log*: the newest line is `pack-disabled operational-risk` by you.
5. Click **Switch on** to bring it back.

**Not loaded** packs are folders on disk that the server did not load at start-up. Press **Load**: the server
first checks the pack together with the loaded ones (a clash, such as `finance` with the banking packs, is refused
with the reason and nothing changes), then records it and restarts in its own process. The page says *Restarting
the server… 4 s* and refreshes when it is back; signed-in users stay signed in and live views reconnect. A pack
loaded this way shows **Unload**, which takes it back the same way. See
[PACKS.md](PACKS.md#loading-a-pack-while-the-server-runs).

### Admin → Health: is everything up?

Worked example: check that every data source is up.

1. Open *Admin → Health* (`/admin/health`). At the top is the overall state, **OK**, **DEGRADED** or
   **DOWN**, and a summary. Below it, each connector (for example `trading-store`, `market-store`, `demo`)
   shows its state, reads, errors and p50/p99 read times. A Delta Lake connector whose folder is missing
   shows `DOWN: no Delta tables under …`.
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
explained in [runbooks/source-down.md](../admin/runbooks/source-down.md).

Managing users and roles is explained in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

## Help

- *Help → Help centre* in the top bar, or `F1`, opens the help centre (`/help`): tutorials, guides, every reference
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
