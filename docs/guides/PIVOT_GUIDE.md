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
# Pivot guide

The pivot engine turns any table of any pack, or the VaR engine's trades, into a pivot: these measures, broken down by these fields on
rows and columns, under these filters, on this business date. Every figure is computed by the server; every total is made from its own
members, never from the figures on the screen. The design behind it is [PIVOT_ENGINE.md](../architecture/PIVOT_ENGINE.md); the hands-on
walk-through is [tutorial 33](../tutorials/33-the-pivot-engine.md).

## Contents

- [1. Open it](#1-open-it)
- [2. The screen](#2-the-screen)
- [3. Sources and fields](#3-sources-and-fields)
- [4. Measures and how each total is made](#4-measures-and-how-each-total-is-made)
- [5. Nested rows, opened lazily](#5-nested-rows-opened-lazily)
- [6. Top N, Others and sorting](#6-top-n-others-and-sorting)
- [7. Provenance, rows and cell references](#7-provenance-rows-and-cell-references)
- [8. The scan estimate, budgets and busy protection](#8-the-scan-estimate-budgets-and-busy-protection)
- [9. Charts of a selection](#9-charts-of-a-selection)
- [10. Export](#10-export)
- [11. Saved and shared views, compare](#11-saved-and-shared-views-compare)
- [12. Who sees what](#12-who-sees-what)
- [13. The view as JSON](#13-the-view-as-json)
- [14. The API](#14-the-api)
- [15. The command line](#15-the-command-line)
- [16. Settings](#16-settings)
- [17. Refusals and their codes](#17-refusals-and-their-codes)
- [18. How it is built](#18-how-it-is-built)

## 1. Open it

| From | How |
|---|---|
| The command line | `PIVOT <GO>` (choose a source), `PIVOT trade <GO>` (a table), `PIVOT trade 2026-10-30 <GO>` (a table on a date), `PIVOT var <GO>` (VaR), `PIVOT @asha/desk-risk <GO>` (a saved view) |
| The menu | **Views → Pivot** |
| A table | **Pivot** in the Data view's facts of the table (`DATA trade <GO>`, then Pivot) |
| A page's table | **Pivot** under a table or ladder whose Sutra says `pivot:` (the source `panel/<kind>/<panel>/<id>`: the panel's own rows, the Sutra's arrangement as the opening view) |
| Search results | **Pivot** above the results of a kind whose pack says `pivot:` (the kind, the pack's arrangement as the opening view) |

The engine is a compute engine with its own file, `config/engines/pivot.yaml` ([COMPUTE_ENGINES.md](../architecture/COMPUTE_ENGINES.md)):
switch it with `drishti.engines.pivot.enabled` (on as shipped; `DRISHTI_PIVOT_ENGINE_ENABLED`), from Admin → Engines or
`drishti.py admin engines`, live. Off, `/api/v1/pivot` answers `404 DRS-1020` in words, and `PIVOT`, Views → Pivot, its Help pages and
`drishti.py pivot` disappear. The old name `drishti.pivot.enabled` still works (it is also the Pivot tab's switch). The MR cube needs it.

## 2. The screen

The screen is mock-up A of the design ([section 25](../architecture/PIVOT_ENGINE.md#25-mock-ups-the-screens-to-build)): Excel's PivotTable
as people know it, with what Excel lacks in the places Excel's equivalents would be.

| Part | What it does |
|---|---|
| Toolbar | Source, Date, Compare (another date, cell by cell), Subtotals (below or above), Chart (`G`), Export (`X`), Save view, Share |
| Scan-estimate bar | what the view reads before it runs ("This pivot reads ≈2,000 rows (≈94 KB, 3 of 8 columns), ≈under a second."); a large one asks first, with **Run** |
| The grid | rows in display order: a member with **+** (closed) or a header with **−** (open), its children indented, then its total (subtotals below; above puts the total on the member's own line); Others; the grand total. Columns × measures; a holistic measure's header carries **H**, an approximate figure **≈** |
| Fields | every field you may use, with its type (string, date, number), and the source's own measures (VaR, ES); search with `/` |
| Slicers | a strip above the grid: each slicer field's members as buttons (read from the engine, as you may see them); a button keeps or drops its member (a filter of the view), **All** clears it. Add one by dragging a field to **Slicers** or pressing `S` on it |
| Conditional formats | on a value's chip: **colour scale** or **data bars**, drawn from the figures shown over the members' cells (never changing a figure), kept with the view |
| Four zones | Filters, Columns, Rows, Values. Drag a field onto a zone, or select it and press `R`, `C`, `V` or `F`; double-click puts a number into Values and a text into Rows. A value's chip has a function picker (sum, count, avg, min, max, median, count_distinct, var_samp, stddev_samp) |
| Provenance popover | click a cell: its figure, exact or ≈, the function and how its totals are made, the members counted, the condition (or VaR scope) that selects them, the data version, the filters, what was computed and what came from the cache, its reference; **Rows** lists the members, **Ask the VaR engine** opens the cell's scope in VAR |
| Chart | a chart of the selected cells (or the top level), linked to the grid: a click on a bar opens that member |
| Export | what (the pivot, the rows of the selected cell, the definition) and how (Excel, CSV, Arrow) |

Keyboard: arrows move between cells; `→` on a row's first cell opens it, `←` closes it; `Shift+→` opens every level below; `1` to `9`
opens the whole row axis to that level; `E` expands the selected row by another field; `Enter` lists a cell's rows; `G` charts; `X`
exports. The help line under the grid shows them.

## 3. Sources and fields

| Source | What it is | Its fields | Its measures |
|---|---|---|---|
| A table (`trade`, `position`, any stored kind) | the Data view's table, read for you through its connector on the date | its columns, typed from the data contract (`string`, `date`, `number`) | the built-in functions over any field |
| `var` | the VaR engine's book of the date | the positions' text columns (DESK, CURRENCY, PRODUCT_GROUP …) | `var()`, `es()`, `pv()`, `trades()` |
| `panel/<kind>/<panel>/<id>` | a table's or ladder's rows on its page: the same document, Sutra and business date | the fields of its Sutra's `pivot:` (masked ones left out) | the built-in functions |

**Rows held in memory.** Two components pivot rows they already hold, and both ask the engine rather than add up a figure: a Sutra's
`pivot` panel (its document's rows, grouped by `by` and `across` with the panel's `agg`; ChartBinder hands them to the engine's in-memory
provider) and *Chart any table* on the shown rows (the page sends only the chart's fields to `POST /api/v1/pivot/inline`, at most 100,000
rows). The in-memory provider makes every state with the same built-in functions as the tabular provider, so the figures are equal.

A date field groups as `TRADE_DATE:day`, `:week`, `:month`, `:quarter` or `:year`; a number as `NOTIONAL_USD:width=1000000` (buckets of a
width, labelled by their lower edge). A missing value is the member `(blank)`.

How a table is read, chosen per table:

| Route | When | How |
|---|---|---|
| **Direct lake** | the kind's one source is a Delta or Iceberg table on a local disk, a snapshot table (one partition holds the date), without deletion vectors or delete files, and every field you may use is a column it keeps | DuckDB reads the date's Parquet files itself: only the columns a statement uses (column pruning), the filters repeated on the raw columns so row groups and files are skipped by their statistics; masked columns are never named, so never read; no staging cap |
| **Staged** | any other table (a document store, an object store, masks that also change values inside other fields, memory-only data) | read once per date, data version and mask signature through the Data view's own read and kept in the engine's in-memory DuckDB (`drishti.pivot.stage-keep` tables); over `drishti.pivot.stage-max-rows` rows refused in words, never cut short |

Memory-only data is never written: the executor has no spill folder unless `drishti.pivot.duckdb.temp-directory` names one. The estimate
of a lake table comes from its metadata (the files' bytes, the manifests' or add-actions' row counts); nothing is read.

### Currencies and units

A field has a **unit**: an `amount` of the data contract is **money** in the currency of the row's currency field (`CURRENCY`, `CCY`,
`*_CCY`); a `rate` or a name ending `_PCT` is never summed (a sum of it is refused, with `avg` or `wavg` offered). A money total over
several currencies is **never added raw**: such a cell shows no figure and says *mixed currencies (EUR to USD)*; a cell in one currency
shows its figure and its currency. To add them, convert in the view:

```json
"currency": {"report": "USD", "rates": {"EUR": 1.08, "GBP": 1.27, "JPY": 0.0066}, "source": "ECB close 2026-10-30"}
```

Every money figure is then in USD (each row converted at its currency's rate before any aggregation; a row in a currency without a rate is
left out), the measures' unit says USD, and the provenance names the rates and their source. Counts stay counts.

## 4. Measures and how each total is made

**A cell or a total is made from its members, never from other cells' figures.** What differs by kind is how:

| Function | Kind | A total is | In Excel |
|---|---|---|---|
| `sum(x)` | mergeable | the sum of its own rows (compensated) | `SUM` of its parts |
| `count()`, `count(x)` | mergeable | the rows (or values) of its own rows | `SUM` of its parts |
| `avg(x)` | mergeable | Σx / n of its own rows, never an average of averages | value |
| `min(x)`, `max(x)` | mergeable | the extreme of its own rows | value |
| `wavg(x, w)` | mergeable | Σwx / Σw of its own rows | value |
| `var_samp(x)`, `var_pop(x)`, `stddev_samp(x)`, `stddev_pop(x)` | mergeable | Welford's state of its own rows | value |
| `median(x)`, `percentile(x, q)` | holistic, exact | the quantile of its own values (linear interpolation, as DuckDB's `quantile_cont`) | value |
| `count_distinct(x)` | holistic, exact | the distinct values of its own rows | value |
| `var()`, `es()` (source `var`) | holistic | the VaR engine's answer for the cell's own scope, from its trades' summed scenario vector | value |
| `pv()`, `trades()` (source `var`) | holistic (adds up) | the engine's answer for the cell's scope | value |

So the desks' VaRs add up to more than the bank's: the grand total is the bank's own VaR, and the gap (Σ children − total) is shown beside
every open node's total as **Σ−total**, the diversification. The sketch functions (≈ distinct counts and percentiles, exact on request) come
with phase P3; custom functions (DEL, SQL macros, Python, Java) with phase P2.

Show values as: a value may carry `showAs` (`pct-grand`, `pct-row`, `pct-column`, `pct-parent-row`, `rank`); it is computed from the
finished figures, so a total is never broken.

## 5. Nested rows, opened lazily

Rows are levels. Only level one is computed at first; opening a member computes **its children only**: one statement for that node's
children and their columns, every other cell read from the partial-state cache. A ten-level hierarchy over a big table opens as fast as one
level. The answer says what it did: "18 cells · 6 sets (4 from the cache) · 1 queries".

- **Expand by…** (`E`): open any member by a field other than the next row field, per member: EMEA by desk, APAC by currency, in one grid.
  The totals stay right: each node is its own cell key.
- **Open to level N** (`1`–`9`): every member down to level N.
- The open nodes are part of the view (its `expanded` list), so a saved view and a link reopen them.

## 6. Top N, Others and sorting

A level may show its top N members by a measure and one **Others** member: "Others (3 of 5)". Others' figures are computed from **its own
members** (the rows of the members not shown), for every measure: a median of Others is the median of those rows, never total minus the
top N. Others is not opened further (its rows can be exported).

Each level sorts on its own: by label in the field's type order (numbers by value, dates by time, text alphabetically, `(blank)` last),
or by a measure, ascending or descending. A sort never moves a child out of its parent.

## 7. Provenance, rows and cell references

Every answer has a provenance id (`pv:<date>:<hash>`); every cell's provenance (members, condition or scope, the measures' kinds, the data
version, filters, statements run, cache hits, time) is kept for the last `drishti.pivot.provenance-kept` answers.

**Rows** lists a cell's member rows, paged, as you may see them (the VaR source's rows are its trades: open the cell's scope in VAR).

A **cell reference** names a cell of a saved view by its coordinates, not its position: `pivot://asha/desk-risk@4/Notional[DESK=RATES-NY,
CCY=USD]`. It survives re-sorting, new columns and a new date; a coordinate that is gone says "not in the view", never 0. Copy it from the
popover; resolve it with `GET /api/v1/pivot/cells?ref=...`.

## 8. The scan estimate, budgets and busy protection

Before a view runs, the screen shows what it will read. Over `drishti.pivot.estimate.confirm-above-seconds` (60) or `confirm-above-rows`
(500 million), it asks first. A table not yet read for you has an unknown size until its first pivot.

- **Cell budget**: a view of more visible cells than `drishti.pivot.max-cells` (20,000, totals included; `max-holistic-cells`, 5,000,
  with a holistic measure) is refused with the count and how to narrow it (`422 DRS-5702`), never cut short.
- **Busy protection**: a view of more than `drishti.pivot.busy-cells` cells asks the admission gate before it opens deeper levels; a
  refusal is `503 DRS-9507` with `Retry-After`.

## 9. Charts of a selection

Select cells (click, Shift+click or Shift+arrows) and press `G`. With nothing selected the chart shows the top level. A measure that adds up
(sum, count) is drawn as bars that sum to the total. **A measure that does not add up is never stacked**: VaR by desk is drawn as the desks'
bars beside the bank's, with the diversification as its own bar, and the chart says so in words. A bar's click opens that member in the
grid. ECharts is vendored; nothing comes from a CDN.

## 10. Export

**Export** (`X`) asks what and how:

| What | Contents |
|---|---|
| The pivot as shown | Excel: one sheet with the rows as shown, numbers as numbers, Excel's own row **outline levels** (grouping with + and −, summaries below or above as on screen); a **`SUM` formula only where a measure's total is the plain sum of its parts** (sum, count), every other figure the engine's value; a **Provenance** sheet (who, when, the provenance id, the data, the filters, every measure's function, kind, exactness and how its totals are made). CSV: one row per cell and measure (`ROW, COLUMN, <dimensions>, MEASURE, VALUE, EXACT, MEMBERS, KIND`) or the grid as shown. Arrow: the cells as a columnar stream |
| The underlying rows | the selected cell's member rows as CSV, as you may see them (up to `drishti.pivot.export.rows-max`) |
| The definition | the view as YAML, no figures |

An xlsx of more rows than `drishti.pivot.export.xlsx-max-rows` is refused with CSV or Arrow offered. Every export is audited (who, the view,
what, the format, the size), never its values.

## 11. Saved and shared views, compare

**Save view** keeps the definition (never the numbers) under your name, each save a new version; **Share** adds people or roles
(`role:risk`). A reader opens a shared view with their own entitlements and masks. `PIVOT @owner/name <GO>` opens one.

On a page's table, an author may **promote** the view to the Sutra (`POST /api/v1/pivot/promote` `{"sutra", "view", "note", "preview"}`):
the Sutra's next version with the panel's `pivot:` arrangement, through review when review is on; a view the arrangement cannot say (a
grouped date, a median, a filter that leaves values out) is refused in words and stays a saved view
([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#pivot-pivot-on-a-table-or-ladder)).

**Compare** picks another date: each cell shows its change (▲/▼) and new or gone cells are marked; the API answers both sides and the
differences.

## 12. Who sees what

- A table is read through the Data view's own read for you: a table you may not open is not offered; a **masked column is not read at
  all**, so it cannot be a dimension, a measure's input or a filter (`403 DRS-5705`).
- The VaR source applies the VaR engine's own entitlements: trades you may not see are left out of every cell; a role that sees the bank only
  in aggregate is offered no dimension (hierarchy levels come with the MR cube).
- `drishti.pivot.aggregate-only.min-members-per-cell` (1 as shipped) hides the figures of a cell with fewer members.
- Every view run, rows read and export is audited without values.

## 13. The view as JSON

```json
{ "source": "trade", "date": "2026-10-30",
  "rows": ["LEGAL_ENTITY", "DESK"], "columns": ["TRADE_DATE:month"],
  "values": [{"measure": "sum(NOTIONAL_USD)", "as": "Notional"}, {"measure": "median(PNL_1D)", "as": "Median P&L"}],
  "filters": {"PRODUCT_GROUP": {"in": ["RATES", "FX"]}, "TRADE_STATUS": {"notIn": ["MATURED"]}},
  "expanded": [{"axis": "rows", "path": [["LEGAL_ENTITY", "LE-UK"]], "by": "CURRENCY"}],
  "expandTo": {"rows": 1}, "sort": {"DESK": {"by": "Notional", "desc": true}},
  "top": {"DESK": {"n": 10, "by": "Notional"}}, "subtotals": "below" }
```

The answer has `rows` and `columns` in display order (each with `key`, `kind` node, header, total, others or grand, `depth`, `label`,
`path`, `expandable`, `expanded`, `by`, and `diversification` on a holistic total), `cells` keyed `rowKey|colKey` (each with `values`,
`members`, `coords`, `notes` where a figure is missing, `scope` on the VaR source), `measures` (id, label, kind, plainSum, exact),
`computed` (sets, fromCache, queries, ms), `provenance` and `provenanceShared`.

## 14. The API

| Call | Does |
|---|---|
| `GET /api/v1/pivot/sources` | the sources you may pivot |
| `GET /api/v1/pivot/catalogue?source=&date=` | a source's fields, measures, functions, dates, masked count |
| `POST /api/v1/pivot/query` | a view (or `{"view": "owner/name"}`) → its answer |
| `POST /api/v1/pivot/estimate` | the scan estimate; nothing read |
| `POST /api/v1/pivot/expand` | `{view, axis, path, by}`: the view with that node open; only its children computed |
| `POST /api/v1/pivot/compare` | `{view, otherDate}`: both answers and the differences per cell |
| `POST /api/v1/pivot/rows` | `{view, coords, offset, limit}`: a cell's member rows |
| `POST /api/v1/pivot/inline` | `{fields: [{name, type}], rows: [[…]], view}`: rows the caller already holds (at most 100,000; field names of letters, digits and `_`; type `string`, `number` or `date`), aggregated by the engine; the view's `source` is ignored |
| `GET /api/v1/pivot/cells?ref=` | a cell by reference |
| `GET /api/v1/pivot/provenance/{id}?cell=rowKey|colKey` | an answer's or a cell's provenance |
| `GET, POST /api/v1/pivot/views`, `GET /views/{owner}/{name}`, `DELETE /views/{name}` | saved views |
| `POST /api/v1/pivot/promote` | a page table's view as its Sutra's `pivot:` arrangement (an author; `preview: true` shows the next version) |
| `POST /api/v1/pivot/export` | `{view, what: pivot|rows|definition, format: xlsx|csv|arrow, shape: cells|grid, delimiter, bom, coords}`, streamed |

Details and the error codes: [API_GUIDE.md, Pivot engine](API_GUIDE.md#pivot-engine-apiv1pivot).

## 15. The command line

```
drishti.py pivot sources
drishti.py pivot fields trade
drishti.py pivot run --source trade --rows productType,counterparty --values "count()" --values "sum(notional)" --open productType=IRS
drishti.py pivot run --source var --rows DESK --cols CURRENCY --values "var()" --values "es()"
drishti.py pivot run --source trade --rows DESK --top DESK=5 --csv out.csv
drishti.py pivot estimate --view asha/desk-risk
drishti.py pivot export --view asha/desk-risk --format xlsx -o desk-risk.xlsx
drishti.py pivot save desk-risk --source var --rows DESK --values "var()" --share role:risk
drishti.py pivot views
```

More in [TOOLS.md](TOOLS.md#516-pivot).

## 16. Settings

All under `drishti.pivot` in `config/engines/pivot.yaml` (the switch `drishti.engines.pivot.enabled` there too), listed in [CONFIGURATION.md](../admin/CONFIGURATION.md): `enabled`, `max-cells`,
`max-holistic-cells`, `busy-cells`, `cache-mb` (the partial-state cache `pivot.states`, in the one memory budget), `provenance-kept`,
`local-shards`, `stage-max-rows`, `stage-keep`, `duckdb.connections`, `duckdb.threads`, `duckdb.memory-limit-mb`, `duckdb.temp-directory`,
`estimate.confirm-above-seconds`, `estimate.confirm-above-rows`, `aggregate-only.min-members-per-cell`, `export.xlsx-max-rows`,
`export.rows-max`; and `drishti.admission.retry-after.pivot-view`.

## 17. Refusals and their codes

| Code | Status | When |
|---|---|---|
| DRS-5701 | 400 | a view it cannot answer: an unknown field or function, a field twice, a bad grouping, sort or show-as |
| DRS-5702 | 422 | over the cell budget (with the count), a table over `stage-max-rows`, an xlsx over `xlsx-max-rows` |
| DRS-1020 | 404 | the engine is switched off here (DRS-5703 when an edition has no PIVOT) |
| DRS-5704 | 404 | a saved view, provenance id or source that is not there (or not shared with you) |
| DRS-5705 | 403 | a masked field asked as a dimension, input or filter |
| DRS-9507 | 503 | busy protection: a large view did not start; `Retry-After` |

## 18. How it is built

The engine is the Maven module `drishti-bicompute` (plain Java, no Spring): the pivot model (views, cell keys, nested axes), the provider
SPI (`PivotProvider`: catalogue, estimate, evaluate a plan of declarative cell sets into partial states, drill), the function SPI
(`AggFunction`: start, add a columnar batch, merge, finish; mergeable, sketch or holistic; each with its SQL state form), the built-ins,
the `GROUP BY GROUPING SETS` compiler and the DuckDB tabular provider, the in-memory provider (`RowsProvider`: rows held in memory, the
same states by the functions' own `add`), partial states merged in a fixed order (`OrderedMerge`), the
partial-state cache, the split-and-merge harness and the exports. The server wires it: the Data view's tables as sources, the VaR provider,
REST, busy protection, saved views and audit. The console only draws: `pivot-engine.js` asks the engine and lays its answer out,
`pivot-grid.js` is the one grid (the Pivot screen and the nested `pivot` panel), and nothing in the browser adds up a figure.
