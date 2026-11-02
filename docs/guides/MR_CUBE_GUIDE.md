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
# MR cube guide: market risk as a pivot with the right totals

The **MR cube** puts the VaR engine's figures on any rows and columns: desks by risk class, books by currency, legal entities by product,
a desk's positions by counterparty. Every cell, every row and column total and the grand total is the **figure of its own set of trades**,
computed by the server from those trades' scenario P&L vectors. A total is never the sum of the cells above it: VaR does not add up, and the
cube shows the gap as **diversification**. Beside the pivot sit the cell's tail histogram, why it moved, where its number came from, the
attribution waterfall, the factor heat map, the DV01 ladder and a what-if side by side. The tutorial is
[tutorial 32](../tutorials/32-the-mr-cube.md); the lens design behind it is [MR_CUBE_LENS.md](../lenses/MR_CUBE_LENS.md).

## Contents

- [1. What the cube answers](#1-what-the-cube-answers)
- [2. The measures at a glance](#2-the-measures-at-a-glance)
- [3. The inputs and their data contract](#3-the-inputs-and-their-data-contract)
- [4. The pivot: dimensions, cells and totals](#4-the-pivot-dimensions-cells-and-totals)
- [5. The landing (layout A)](#5-the-landing-layout-a)
- [6. Tab B: tail and attribution; tab C: sensitivities and what-if](#6-tab-b-tail-and-attribution-tab-c-sensitivities-and-what-if)
- [7. Explain the move, time travel and provenance](#7-explain-the-move-time-travel-and-provenance)
- [8. Saved views, sharing and export](#8-saved-views-sharing-and-export)
- [9. Settings, the cell budget and switching the cube off](#9-settings-the-cell-budget-and-switching-the-cube-off)
- [10. API and command line](#10-api-and-command-line)
- [11. Refusals and error codes](#11-refusals-and-error-codes)
- [12. The shared cube core (for the counterparty cube)](#12-the-shared-cube-core-for-the-counterparty-cube)
- [13. What is not built yet](#13-what-is-not-built-yet)

## 1. What the cube answers

| Question | Where |
|---|---|
| Where is the bank's VaR, by desk × risk class, against limits? | the pivot (landing A): each cell its VaR, the desk column its limit bar, the total row re-aggregated |
| Why is the bank's VaR smaller than the desks' sum? | the **Σ cells − total** line and the **Σ desks − bank** tile: the diversification, in money |
| Which scenario days drive this cell's tail? | the right rail's histogram: the VaR scenario marked **V**, the expected-shortfall tail marked **E** |
| Why did a cell's VaR move since yesterday? | **Explain the move** (key `E`): the VaR engine's exact steps on the cell's scope, adding up to the change |
| Where did this number come from? | **Provenance** (key `P`, or hover): business date, data version, batches, scenario set and window, method, trades, shards |
| What does each desk contribute? | tab B: component VaR (adding up to the total exactly), the waterfall, the hedges as negative bars |
| Am I within my DV01 buckets? | tab C: the DV01 ladder by currency and tenor bucket, against two-sided bucket limits when they are configured |
| What does this unwind do to every desk? | tab C: the what-if side by side, before, after and the change for every row and the total |

Every figure is computed by the server in Java; the console only draws. The cube's VaR of a cell **equals** the VaR engine's answer for the
cell's scope (`GET /api/v1/var/scope?scope=<the cell's scope>`) to the cent; each answer carries the scope so you can check it.

## 2. The measures at a glance

| Measure | Class | What it is |
|---|---|---|
| `VAR` | vector, non-additive | the cell's own VaR: its trades' scenario P&L vectors summed (float64, the engine's fixed order), then the k-th worst loss |
| `ES` | vector, non-additive | the cell's expected shortfall from the same summed vector |
| `COMP_VAR` | additive | the cell's share of the view's **total** VaR (Euler): the cells of any row or column add up to their total's share, and all rows to the total VaR |
| `COMP_ES` | additive | the same for ES |
| `INCR_VAR` | vector, non-additive | the total's VaR less the total's VaR without the cell |
| `PV` | additive | the sum of the trades' present values |
| `TRADES` | additive | the trades in the cell |
| `SVAR` | vector, non-additive | stressed VaR: the cell's scope on the date's stress set (USD), computed scope by scope, for the first `stressed-max-cells` cells |
| `DV01` | additive | the sum of the cell's GIRR delta sensitivities (per basis point) from the position sensitivities |
| `CS01` | additive | the sum of the cell's credit-spread delta sensitivities |
| `SBM` | formula, non-additive | FRTB-SA sensitivities-based capital of the cell's own scope (the formula re-run on its members, [FRTB_SA_GUIDE.md](FRTB_SA_GUIDE.md)), for the first `sbm-max-cells` cells |

**The class decides how a total is made, and nothing else.** An additive measure's total is the sum of its members (and so of its cells); a
vector or formula measure's total is computed again from the union's own vector or inputs. `DV01`, `CS01` and `SBM` are offered only when the
FRTB-SA engine (which serves the sensitivities) is on; with it off they are not drawn and the ladder and heat map say so.

**The lens** of a view is the VaR engine's: 1-day or 10-day (√10 or the overlapping 10-day sum), 99% or 97.5%, the normal or the stressed
scenario set, the reporting currency ([VAR_GUIDE.md section 18.6](VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen)).
A limit use is shown only where the node's limit is set for the view's horizon and confidence.

## 3. The inputs and their data contract

The cube adds no input of its own: it reads the VaR engine's book of the business date and, for the sensitivity measures, the FRTB-SA
engine's position sensitivities.

| Input | Kind | What the cube reads from it |
|---|---|---|
| Scenario P&L vectors or scenario PVs | `pnl-vector`, `scenario-pv` (+ `fx-scenario`) | every trade's vector; its columns (`LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY`, `TAGS`, `SOURCE_SYSTEM`, …) as dimensions |
| Scenario set | `scenario-set` | the scenario days of the histogram and the explain |
| Hierarchies and VaR limits | `risk-hierarchy`, `var-limit` | the hierarchy levels as dimensions (`MGMT.DESK`, `LEGAL.LEGAL_ENTITY`, `LL.LIMIT_LETTER`), the limit bars |
| Position sensitivities and the factor dictionary | `position-sensitivity`, `risk-factor` | `DV01`, `CS01`, the ladder and the heat map ([FRTB_SA_GUIDE.md section 3](FRTB_SA_GUIDE.md#3-the-inputs-and-their-data-contract)) |
| Official captures and the open dates | the VaR engine | the time-travel slider's stops and the sparklines |

**Positions** are the VaR engine's own (VAR_GUIDE.md section 15.6). A dimension that is a column of the positions puts a **whole position** in
one value, exactly as a `WHERE:` rule selects it, so the cell's scope text selects the same trades.

**The risk class** (`RISK_CLASS`) is derived from each position's product group by configuration (`drishti.cube.mr.risk-classes`): a position's
primary class. A bank that delivers its vectors split by risk class (MR_CUBE_LENS.md section 7.4, `RISK_CLASS_VECTORS`) gives each position a
vector per class; until then a desk of one product group has VaR in one class column only.

## 4. The pivot: dimensions, cells and totals

A **view** is a scope (the VaR engine's grammar: `BANK`, `DESK:DESK-RATES`, `LE:LE-NY - BOOK:X`, `PORT:…`, `BASKET:…`), the dimensions on
rows and on columns (several nest with `>`), measures, filters, a business date and a lens. As text:

```
MCUBE BANK BY MGMT.DESK,RISK_CLASS MEASURE VAR,ES SORT desc
MCUBE DESK:DESK-RATES BY TT_BOOK>POSITION,CURRENCY MEASURE VAR,COMP_VAR,PV FILTER LEGAL_ENTITY=LE-NY|LE-LDN H 10D DATE 2026-09-30
```

`BY rows,columns`; `MEASURE` one or more (the first is the grid's, the sort's and the sparkline's); `FILTER DIM=a|b` keeps the members with
one of the values; `H 10D` (√10) or `H 10D SUM`; `C 97.5`; `CCY EUR`; `SET STRESSED`; `SORT desc|asc` by the first measure's row total;
`DATE yyyy-MM-dd`.

**Dimensions** (`GET /api/v1/cube/mr/describe`):

| Group | Examples | Values |
|---|---|---|
| hierarchy level | `MGMT.DIVISION`, `MGMT.DESK`, `MGMT.BOOK`, `LEGAL.LEGAL-ENTITY`, `LL.LIMIT_LETTER` | the level's nodes; a cell's scope names the node (`NODE:MGMT~DESK-RATES`) |
| attribute | `DESK`, `TT_BOOK`, `LEGAL_ENTITY`, `CURRENCY`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `COUNTERPARTY`, `TAGS`, any column of the trades | the column's values; a cell's scope is a `WHERE:` rule (or `CPTY:`) |
| derived | `RISK_CLASS` | the configured classes, in their order, then `OTHER` |
| member | `POSITION`, `TRADE` | each position, each trade: the last levels of a drill |

**A cell, a row total, a column total and the grand total are four kinds of the same thing**: a set of trades. A view of R × C cells is
R × C + R + C + 1 sets. The core asks the VaR engine for all of them **in one batch** (on a sharded date one call per shard owner for all of
them, section 4.11 of the lens design), sums each set's vectors in the engine's fixed order, then takes each measure's statistic once per set.
So:

- the grand total's VaR equals `VAR BANK` (or the view's scope) exactly;
- each row's total is the row's own VaR; each column's likewise;
- **Σ cells − total** under the grid, per column and for the rows' totals, is the diversification of that axis, in money;
- an empty cell (a desk with no trade of the class) is a dot, not a zero.

**The cell cache** keeps each set's summed vector by the date, its data version and intraday stamp, the viewer's rights and the scenario
set: a re-pivot or a second ask computes only the sets it has not seen (the answer says how many came from the cache). It is held in the one
memory budget (`engine.answers`, as `cube.cells`).

**Rights.** The cube reads as the person, as the VaR explorer does (VAR_GUIDE.md section 9). A role that sees part of a scope only in
aggregate may pivot it by **hierarchy levels** only: an attribute, a position or a trade dimension would name what it may not see one by one,
and is refused in words (`403`). A masked column is not offered as a dimension and is refused if asked.

## 5. The landing (layout A)

`MCUBE <GO>` (or **MR cube** in the VaR menu) opens the decided layout A: the pivot first, tabs B and C beside it (one view feeds the three).

| Panel | What it shows |
|---|---|
| Command line and keys | the view as text; **R C F** dimensions, **M** measures, **H** 1D/10D, **[ ]** dates, **E** explain, **P** provenance, **W** what-if, **S** save, **X** export |
| Headline strip | VaR of the scope (its rank of N), ES (mean of the worst), limit use with its bar, **Σ rows − total** (the diversification), stressed VaR, the data (batches, intraday, data version, trades) |
| Lens controls | chips **Rows**, **Columns**, **Filter** (a click, or R, C, F, picks a dimension); measure, horizon, confidence, scenario set and currency |
| Time-travel slider | the business dates with an official record or open on this server (an official one dotted); a click: *as of*; shift-click: *compare* (the explain's earlier date); `[` and `]` step |
| Pivot | each cell its figure (thousands separators, losses with a minus sign) and a sparkline of the open dates; the row total column (amber sparkline) and a **Limit** column (use %, the bar green under the warning level, amber from it, red over 100%); the total row *(re-aggregated)*; the **Σ cells − total** line |
| Right rail | the selected cell's tail histogram (V and E), explain the move, its provenance |

Click a cell to select it; double-click or `Enter` drills (desk → book → position → trade; the drill keeps the columns); `Backspace` and the
breadcrumb go back. Hovering a cell shows its figures, trades, scope and any reason a figure is not given.

## 6. Tab B: tail and attribution; tab C: sensitivities and what-if

**Tab B** (the same rows): **components** (component VaR of each row and its share, a negative component a hedge; they add up to the total
exactly), the **waterfall** of the components sorted by size with the total as its last bar, the **histogram** of the view's total, and the
**factor heat map** of GIRR delta (DV01) by curve currency × tenor (green long, red short, intensity by size, every cell printed).

**Tab C**: the **sensitivity ladder**: GIRR delta by currency and the buckets of a grid (`BANK-IR-TENOR`: 0-2Y, 2-5Y, 5-10Y, 10Y+; `FRTB-GIRR`:
the regulator's vertices), net per bucket, a centre-zero bar (long right, short left), and against the bucket's two-sided limit when one is
configured (`use = max(net / limit, −net / limit)`, **near** from 80%, **breach** over 100%). The **what-if side by side**: add a booked
trade copied and scaled (`× -1` unwinds it, or any scope such as `BOOK:BOOK-RATES-3` for a hedge shaped like a book); every row of the view
and the total, before, after and the change (the Java what-if on each row's scope, [VAR_GUIDE.md section 8.2](VAR_GUIDE.md#82-the-what-if-the-java-engine)),
a materially changed row outlined. **Explain the move** for the selected row.

A grid is configuration: each bucket `label: from-to` in years, starting where the last ended (a gap or an overlap is refused in words);
a tenor in no bucket is counted aside (`withoutTenor`).

## 7. Explain the move, time travel and provenance

**Explain the move** (`E`, `GET /api/v1/cube/mr/explain?scope=…&from=…&to=…`) is the VaR engine's explanation of the cell's scope between two
business dates ([VAR_GUIDE.md section 3.11](VAR_GUIDE.md#311-why-it-moved-the-var-change-explained)): the earlier date's VaR (official when
it was captured), then each exact step (restatement, hierarchy, trades out, window roll, market, trades in) with its effect, adding up to the
later date's VaR. *Compare* on the slider sets the earlier date; none: the business date before.

**Time travel.** The slider's stops are business dates (an official record, or open on this server) and, within an open date, its batches in
order (decision P3: dates and batches, not raw lake versions). Choosing a date that is not open opens it.

**Provenance** per cell (`P`, hover, `GET /api/v1/cube/provenance/{id}?cell=r|c`): every answer has a provenance id; the view's shared part
(business date, currency, data version and the input tables' versions, batches and intraday updates, scenario set and window, method, lens,
trades counted and hidden by the role, shards asked) and each cell's own (its trades, its scope, whether its vector came from the cache). The
ids of the last `provenance-kept` answers are kept.

## 8. Saved views, sharing and export

**Saved views** keep the scope, dimensions, measures, filters, lens and sort, never the numbers. A view is its owner's, or shared read-only
with people or roles (`role:risk`); a reader opens it **with their own entitlements**. Open one with `MCUBE @owner/name`, the *Saved views*
list, or `GET /api/v1/cube/views/{owner}/{name}`. Views are in the content store (`cube/views/<owner>/<name>.json`): every version kept, the
same on every server of a cluster.

**Export** (`X`, or the buttons):

| Format | What it holds |
|---|---|
| CSV | one row per cell, row total, column total and the grand total: kind, the coordinates, trades, scope, each measure |
| Excel | **About** (the view, the date, the provenance id and how to read the workbook); one sheet per measure: an **additive** measure's totals are live `SUM` formulas over its cells; a **non-additive** measure's cells and totals are the server's values, each re-aggregated from its own members, said so on the sheet, with the **Σ cells − total** line as a formula; **Provenance**: every cell's trades and scope, then the shared provenance |
| Arrow | the CSV's table as an Arrow stream (`Accept: application/vnd.apache.arrow.stream` on the query, or `format=arrow`) |
| Power Query, Tableau | `GET /api/v1/cube/views/{owner}/{name}/data?format=json|csv|arrow` with the token in the header: the saved view answered now, as the reader |

## 9. Settings, the cell budget and switching the cube off

| Setting | Default | What |
|---|---|---|
| `drishti.cube.max-cells` | 5000 | the most cells one view computes, its totals counted; a larger view is refused with its count (`DRS-5602`), never cut short |
| `drishti.cube.busy-cells` | 600 | a view of more cells asks busy protection first ([OPERATIONS.md section 12.5](../admin/OPERATIONS.md#125-busy-protection-refuse-politely-never-fall-over)): `503 DRS-9506` with `Retry-After` while the server is short of memory or threads |
| `drishti.cube.cache-mb` | 64 | the cell cache's own limit; the one memory budget may hold it to less |
| `drishti.cube.provenance-kept` | 500 | the answers whose provenance ids are kept |
| `drishti.cube.sparkline-max` | 600 | the most cells a view draws sparklines for (each date is the view computed again on that date) |
| `drishti.cube.mr.enabled` | `true` (`DRISHTI_MR_CUBE_ENABLED`) | the MR cube (engine `mr-cube`: needs `var`, uses `sensitivities` and `frtb-sa`) |
| `drishti.cube.mr.risk-classes` | IR, CR, FX, EQ, CM by product group | the derived `RISK_CLASS` dimension |
| `drishti.cube.mr.grids` | `BANK-IR-TENOR`, `FRTB-GIRR` | the ladders' bucket grids, in years |
| `drishti.cube.mr.ladder-limits` | none | two-sided DV01 limits per currency and bucket (`"USD 2-5Y": 1500000`) |
| `drishti.cube.mr.sbm-max-cells` | 60 | the cells whose SBM one view computes |
| `drishti.cube.mr.stressed-max-cells` | 100 | the cells whose stressed VaR one view computes |
| `drishti.admission.retry-after.cube-view` | 20 | the seconds a refused large view is told to wait |

**Switched off** (`drishti.cube.mr.enabled: false`), nothing of the MR cube is built: no source, no cache entries, no MR endpoints; every
`/api/v1/cube/mr/...` answers `404 DRS-5603` in words, and the page says so. The full engine-switch framework (Admin → Engines, the engine's own
file, the dependencies enforced) is [COMPUTE_ENGINES.md](../architecture/COMPUTE_ENGINES.md); the cube's key is the one it names.

Every setting is in [CONFIGURATION.md](../admin/CONFIGURATION.md).

## 10. API and command line

| Request | What |
|---|---|
| `GET /api/v1/cube` | the cubes running here and the core's settings |
| `GET /api/v1/cube/mr/describe?scope=&date=` | the measures and the dimensions a view may use |
| `POST /api/v1/cube/mr/query` | a view (`{scope, rows, columns, measures, filters, date, lens, sort, history}`, or `{text}`, or `{id}` of a saved view) → `rows`, `columns`, `cells` (each `{r, c, coords, members, scope, values, notes, cached, spark}`), `total`, `diversification`, `headline`, `provenance` |
| `GET /api/v1/cube/mr/query?text=MCUBE …` or `?view=owner/name` | the same for a typed or a saved view |
| `POST /api/v1/cube/mr/cell` · `GET /api/v1/cube/mr/cell?view=&row=&column=` | one cell: its figures, `detail` (histogram `edges` and `counts`, `var`, `es`, `k`, `esCount`, the VaR scenario and its date, the ES tail), and `drill` (its members by the next dimension) |
| `POST /api/v1/cube/mr/export?format=csv|xlsx|arrow|json` | the view exported |
| `GET /api/v1/cube/provenance/{id}?cell=r|c` | an answer's provenance, and one cell's |
| `GET /api/v1/cube/mr/versions` | the slider's stops |
| `GET /api/v1/cube/mr/explain?scope=&from=&to=&confidence=` | why a cell's VaR moved |
| `GET /api/v1/cube/mr/sensitivities?scope=&grid=` · `?by=QUALIFIER,TENOR&class=GIRR&measure=DELTA` | the ladder by a grid, or the heat map by two factor parts |
| `POST /api/v1/cube/mr/what-if` | `{view, items: [{copyOf, factor}]}`: every row and the total, before, after and incremental |
| `GET`/`POST /api/v1/cube/views` · `GET /api/v1/cube/views/{owner}/{name}` · `…/data` · `DELETE /api/v1/cube/views/{name}` | saved views |

The command line is `MCUBE <view> <GO>` in the console. Everything above is in [API_GUIDE.md](API_GUIDE.md#the-mr-cube).

## 11. Refusals and error codes

| Code | When |
|---|---|
| `DRS-5601` (400) | the view is not one the cube can answer: an unknown dimension or measure, a dimension twice, a bad filter, lens, sort or grid |
| `DRS-5602` (422) | the view has more cells (with its totals) than `drishti.cube.max-cells`: the count is given; nothing is cut short |
| `DRS-5603` (404) | the MR cube is switched off on this server |
| `DRS-5604` (404) | a saved view that is not there, or not shared with you |
| `DRS-9506` (503) | busy protection: a view of more than `busy-cells` cells did not start; `Retry-After` |
| `403` | a dimension that would name positions of a scope you see only in aggregate, or a masked column |
| `DRS-5215` (503) | a shard of the date has no live owner: the view is refused, never answered from the other shards |

A figure the cube cannot give in a cell (stressed VaR past `stressed-max-cells`, SBM of a cell no scope text names) is `null` with its
reason in the cell's `notes`, shown on hover; never a zero.

## 12. The shared cube core (for the counterparty cube)

The core (`com.ash.drishti.server.cube`) knows nothing of VaR. A cube is a **source** (`CubeSource<A>`, `A` the aggregate of a set of
members): its measures (`CubeMeasure`, each **additive**, **vector** or **formula**), its drill path and its earlier dates; opened for a person
and a view it is a **universe**: its members, how they split by a dimension (`partition`), each value's label, the scope text of a cell,
the aggregates of many sets in one batch, a cell's figures from its aggregate (and the total's, for components), a cell's detail, the shared
provenance and the headline. The core does the rest: the axes, the cells, the four kinds of totals, diversification, the budget, busy
protection, the cache, provenance ids, saved views and the exports. The MR cube's source is the VaR engine's book (`VarCubeSource`); the
counterparty cube will bring the exposure engine's (members the trades, the aggregate a set's path matrices netted and collateralised per
netting set before the sum).

## 13. What is not built yet

- **Per-class vectors** (the risk-class columns as each position's own split): the class is a position's primary class until the bank
  delivers `RISK_CLASS_VECTORS`.
- **The factor overlay** of explain the move (Σ sensitivity × the factor's move): the lake does not hold each factor's move by date yet; the
  exact steps add up on their own.
- **Within-date batches on the slider** as stops of their own (the slider moves between dates; a date's batches are listed on its stop).
- **Streaming patches** of visible cells on an intraday batch, **corrections** in the cube (MC11), the **MR Cube lens** file and the
  client renderer's `cube-view` kind, the **phone tiers** beyond the landing's narrow layout.
- **The cube's playground and its workbook of the VaR formulas** (the export's workbook has the totals and the diversification as formulas;
  the VaR statistic of a cell is the VaR Playground's, `PLAY VAR <scope>`).
