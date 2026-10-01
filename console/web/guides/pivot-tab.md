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
# The Pivot tab · slice a table

Some tables have a **Table | Pivot** switch above them. **Pivot** turns the table's rows into an Excel-style pivot:
drag a field down the side, another across the top, a number into the cells, and read the totals; click any number for
the rows behind it. The switch is there only where a Sutra (`pivot:` on a table or ladder) or a pack (`pivot:` on a
kind's search results) asked for it, with the fields it chose; every other table stays a table.

## Tutorial: a netting set's notional by maturity

With the counterparty-risk pack enabled, open `NSET NS-ALDERSHOT-LDN`.

1. On *Member trades*, click **Pivot**. Notional by product down the side, maturity buckets (`0-1Y` … `10Y+`) across,
   with totals: over **all** the netting set's trades, not only the rows the table shows.
2. Drag **Currency** into **Rows**, before *Product*: each currency is a group with its *Total* row.
3. Drag **Asset class** into **Columns**: each asset class spans its buckets, with a total column.
4. On the value, choose **% of total**: each cell is its share of the netting set's notional.
5. Click *Currency: all* in **Filters**, **None**, tick `USD`, **Apply**.
6. Click a number: the trades behind it, ids as links. `Esc` closes.
7. Choose **Bar chart**; **Excel** downloads the grid; **Save** keeps the arrangement for every netting set.

![The Pivot tab on a netting set's member trades: currency and product down the side, asset class and maturity bucket across](/static/img/guide/pivot-netting-set.png)

## The keys

| Key | On | Does |
|---|---|---|
| `←` `→` | the Table / Pivot switch | Switch |
| `R` `C` `V` `F` | a field | To Rows, Columns, Values or Filters |
| `Delete` | a field in a zone | Out of the zone |
| `Alt`+`↑` `↓` | a field in a zone | Earlier or later (outer or inner) |
| `Enter` | a field in the list | Add it (a number to Values, anything else to Rows) |
| `Enter` | a value / a filter | Its choices / its pick list (`Esc` back) |
| arrows, `Enter` | the grid | Move; show the rows under the cell |
| `Enter` | a value's heading | Sort by it: ascending, descending, off |

Every change is read out by screen readers.

## What it does

- **Values**: sum, count, average, min, max or distinct count, each shown as the value or as a share of its row, its
  column or the total.
- **Grid**: subtotals and grand totals (untick **Totals** to hide them), groups that close to their total (▾, **Collapse
  all**), sort by any value, **Heat** shading, groups in natural order (`2-5Y` before `10Y+`).
- **Chart** (bar, line, heatmap), **CSV**, **Excel**, **Print**.
- **Save** keeps your arrangement per Sutra and panel (per kind for searches); **Reset** goes back to the default.
  Nobody else's screen changes. No special power is needed.
- **Authors** see **Promote to Sutra…**: your arrangement as the panel's `pivot:` in the Sutra's next version, shown as
  a diff, submitted for review like a Studio save.

## On search results

`TRD where mtm > 0` (where the pack offers it): the server pivots **every** matching trade of the day, from the fields
its store keeps as columns, and sends only the cells. A field marked **doc** is not a column: the server says so, and
**Read documents instead** reads up to 20,000 documents (marked *partial* if there were more). Fields your role may not
see stay masked (`•••`), never added up.

![The Pivot tab on a trade search: MTM by book and currency over every matching trade](/static/img/guide/pivot-search.png)

More in the [user guide](using-the-terminal) (*The Pivot tab*), and the `pivot` option in the
[Rachana reference](rachana-reference).
