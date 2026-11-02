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
# Tutorial 33 · The pivot engine: trades by product, then VaR by desk

A pivot is "these measures, broken down by these fields, under these filters, on this date". In this tutorial you pivot the demo trades,
open a row lazily and another by a different field, check a top N's Others by hand, read a cell's provenance and rows, pivot VaR by desk and
see that the bank's VaR is not the sum of the desks', chart a selection, and save, share and export a view to Excel. Each step shows the
UI, the command line and the view's JSON. The guide is [PIVOT_GUIDE.md](../guides/PIVOT_GUIDE.md).

## Time to complete

About 20 minutes.

## Before you start

- The server and console running with the demo data ([tutorial 1](01-first-15-minutes.md)); `DATA trade <GO>` lists the trades.
- For step 5, the VaR engine on with the demo risk lake (`VAR BANK <GO>` answers).
- A token for the command line (`drishti.py login`, [TOOLS.md](../guides/TOOLS.md)).

## What you will learn

- how a view is built from the field list and its four zones, and what the server computes;
- why only open rows are computed, and how "expand by" breaks one member down by another field;
- why Others and every total are made from their own members;
- where a cell's figures come from (provenance, rows, a reference);
- why a non-additive total (VaR) is never a sum, and how the chart and the Excel export respect it.

## Step 1 · A first pivot of the trades

**UI.** Type `PIVOT trade <GO>`. The screen opens with the first text field on Rows and `count()` in Values. Double-click
`productType` if it is not on Rows, and drag a number field (the demo's notional) onto Values: it arrives as `sum(...)`. The estimate bar
says what the view reads; the grid shows one row per product type and the grand total.

**CLI.**

```
drishti.py pivot fields trade
drishti.py pivot run --source trade --rows productType --values "count()" --values "sum(notional)"
```

**JSON** (what the screen sent to `POST /api/v1/pivot/query`):

```json
{"source": "trade", "rows": ["productType"], "values": [{"measure": "count()"}, {"measure": "sum(notional)"}]}
```

Check: the grand total's `count()` equals the Data view's row count (`DATA trade`, "Rows").

## Step 2 · Open a row, then expand another by a different field

**UI.** Add `counterparty` to Rows (after productType). Nothing below level one is computed yet: the grid shows product types with **+**.
Press **+** on IRS (or select its first cell and press `→`): IRS becomes a header with its counterparties and an "IRS total" below them.
The meta line says how many sets were computed and how many came from the cache: only IRS's children were asked. Now select FXSWAP's
row, press `E` and type a date field (or any other field): FXSWAP opens by that field while IRS stays by counterparty.

**CLI.**

```
drishti.py pivot run --source trade --rows productType,counterparty --values "count()" --open productType=IRS
```

**JSON.**

```json
{"source": "trade", "rows": ["productType", "counterparty"], "values": [{"measure": "count()"}],
 "expanded": [{"axis": "rows", "path": [["productType", "IRS"]]}, {"axis": "rows", "path": [["productType", "FXSWAP"]], "by": "tradeDate"}]}
```

## Step 3 · The top N and its Others

**UI.** In the JSON of step 1, or with the CLI below, keep the top 2 product types by count. The grid shows the two and "Others (k of n)".
Click Others' count cell: its provenance counts its members. Check by hand: Others' count is the grand total less the two shown (for a
count that adds up); for `median(...)`, Others is the median of Others' own rows, which no subtraction gives.

**CLI.**

```
drishti.py pivot run --source trade --rows productType --values "count()" --values "median(notional)" --top productType=2
```

**JSON.** `"top": {"productType": {"n": 2, "by": "count()"}}`

## Step 4 · A cell's provenance and its rows

**UI.** Click the IRS total cell. The popover shows the figure, exact, the function and how its totals are made ("each cell and total from
its own rows' state"), the members counted, the condition (`productType = 'IRS'`), the data version and what was computed. Press **Rows**:
the IRS trades, as you may see them. Press `Enter` on any cell for the same.

**CLI.** `drishti.py pivot export --source trade --rows productType --values "count()" --what rows --cell productType=IRS --format csv -o irs.csv`

**JSON** (`POST /api/v1/pivot/rows`): `{"view": {...}, "coords": {"productType": "IRS"}, "limit": 50}`

## Step 5 · VaR by desk: the total is not a sum

**UI.** Choose the source **VaR** (or type `PIVOT var <GO>`). The view opens with `var()` and `es()`. Put DESK on Rows and CURRENCY on
Columns. Each cell's popover shows its **scope** (`(WHERE:DESK == 'DESK-X') & (WHERE:CURRENCY == 'USD')`) and **Ask the VaR engine** opens
it in VAR: the figure is the same to the cent. The grand total is the bank's own VaR; the desks' VaRs add up to more, and the grand total
row shows the gap as **Σ−total** (the diversification).

**CLI.**

```
drishti.py pivot run --source var --rows DESK --cols CURRENCY --values "var()" --values "es()" --values "trades()"
drishti.py var show BANK
```

**JSON.** `{"source": "var", "rows": ["DESK"], "columns": ["CURRENCY"], "values": [{"measure": "var()", "as": "VaR"}]}`

## Step 6 · Chart the selection

**UI.** With nothing selected, press `G`: the desks' VaR as bars **beside** the bank's, with the diversification as its own bar, and a line
saying why it is not stacked. Go back to the trades pivot of step 1 and press `G`: a count adds up, so its bars sum to the total. Click a
bar to open that member in the grid.

## Step 7 · Save, share and export to Excel

**UI.** Press **Share**, name the view `desk-var` and share it with `role:risk`. The title becomes `you/desk-var @1`; a cell's popover now
shows its reference (`pivot://you/desk-var@1/VaR[DESK=DESK-X]`). Press `X`, keep "The pivot as shown" and press **Excel**. Open the file:
the rows are grouped with Excel's own + and −; in a trades pivot, a `count()` or `sum()` total is a `SUM` formula of its parts, while VaR
and any median are values; the Provenance sheet lists who exported, the data and how every measure's totals are made.

**CLI.**

```
drishti.py pivot save desk-var --source var --rows DESK --values "var()" --share role:risk
drishti.py pivot export --view you/desk-var --format xlsx -o desk-var.xlsx
drishti.py pivot views
```

**JSON** (`POST /api/v1/pivot/views`): `{"name": "desk-var", "view": {...}, "sharedWith": ["role:risk"]}`

## What you learned

Every figure in a pivot is the server's, made from its cell's own members: a mergeable total from its own rows' state, a holistic one
(VaR, a median) from its own members, Others from its own rows. Open rows are computed on demand and the rest come from the cache. A chart
and an Excel file never add up what does not add up.
