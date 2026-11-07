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
a desk's positions by counterparty. It is not a second grid: it is a **preset of the Pivot** ([PIVOT_GUIDE.md › Presets](PIVOT_GUIDE.md#28-presets-the-risk-cubes), `/pivot?preset=mr`), the pivot engine on its VaR source
(`var`), with the MR lens around it. Every cell, every subtotal and the grand total is the **figure of its own set of trades**, computed by
the server from those trades' scenario P&L vectors. A total is never the sum of the cells above it: VaR does not add up, and the pivot shows
the gap as **diversification**. Around the pivot sit the headline of the view's scope, the time-travel slider, sparklines, a cell's tail
histogram and why it moved, the components and their waterfall, the factor heat map, the DV01 ladder and a what-if side by side. The
tutorial is [tutorial 38](../tutorials/38-the-mr-cube-on-the-pivot-engine.md); the lens design behind it is
[LENSES.md section 6](../architecture/LENSES.md#6-the-mr-cube-a-market-risk-cube-lens-that-answers-what-fis-adaptivs-artiq-answers-and-shows-why); the engine's design is [ARCHITECTURE.md section 23](../architecture/ARCHITECTURE.md#23-the-pivot-engine).

## Contents

- [1. What the cube answers](#1-what-the-cube-answers)
- [2. The measures](#2-the-measures)
- [3. The inputs and their data contract](#3-the-inputs-and-their-data-contract)
- [4. The view: dimensions, options, cells and totals](#4-the-view-dimensions-options-cells-and-totals)
- [5. The landing (layout A)](#5-the-landing-layout-a)
- [6. Tab B: tail and attribution; tab C: sensitivities and what-if](#6-tab-b-tail-and-attribution-tab-c-sensitivities-and-what-if)
- [7. Explain the move, time travel and provenance](#7-explain-the-move-time-travel-and-provenance)
- [8. Saved views, sharing and export](#8-saved-views-sharing-and-export)
- [9. Settings and switching the cube off](#9-settings-and-switching-the-cube-off)
- [10. API and command line](#10-api-and-command-line)
- [11. Refusals and error codes](#11-refusals-and-error-codes)
- [12. How it is computed](#12-how-it-is-computed)
- [13. What is not built yet](#13-what-is-not-built-yet)
- [14. Cube data: every dataset the cube reads](#14-cube-data-every-dataset-the-cube-reads)

## 1. What the cube answers

| Question | Where |
|---|---|
| Where is the bank's VaR, by desk × risk class, against limits? | the pivot (landing A): each cell its VaR; add `limit_use()` for each whole desk's use of its limit |
| Why is the bank's VaR smaller than the desks' sum? | the **Σ desks − total** tile and the grand total's diversification (Σ children − total, in money) |
| Which scenario days drive this cell's tail? | the rail under the pivot: the cell's histogram, the VaR scenario marked **V**, the expected-shortfall tail marked **E** |
| Why did a cell's VaR move since yesterday? | **Explain the move**: the VaR engine's exact steps on the cell's scope, adding up to the change |
| Where did this number come from? | a cell's **provenance** (click it): its scope, its members, the date's data, the scenario set, the method |
| What does each desk contribute? | tab B: component VaR (adding up to the total exactly), the waterfall, the hedges as negative bars |
| Am I within my DV01 buckets? | tab C: the DV01 ladder by currency and tenor bucket, against two-sided bucket limits when they are configured |
| What does this unwind do to every desk? | tab C: the what-if side by side, before, after and the change for every row and the total |

Every figure is computed by the server in Java; the console only draws. The VaR of a cell **equals** the VaR engine's answer for the cell's
scope (`GET /api/v1/var/scope?scope=<the cell's scope>`) to the cent; every cell carries its scope so you can check it.

## 2. The measures

The VaR source's measures are its own functions (a view writes them `var()`, `comp_var()`): each **holistic**, so the pivot engine never
adds one cell's figure to another's; each cell and each total is computed from its own trades.

| Measure | Plain sum? | What it is |
|---|---|---|
| `var()` | no | the cell's own VaR: its trades' scenario P&L vectors summed (float64, the engine's fixed order), then the k-th worst loss through the view's lens |
| `es()` | no | the cell's expected shortfall from the same summed vector |
| `pv()` | yes | the sum of the trades' present values |
| `trades()` | yes | the trades in the cell |
| `comp_var()` | yes | the cell's share of the view's **total** VaR (Euler): the cells of any level add up to their parent's share, and all of them to the total VaR |
| `comp_es()` | yes | the same for ES |
| `incr_var()` | no | the view's total VaR less the total's VaR without the cell |
| `limit()` | no | the VaR limit of the node the cell is, when the cell is that node's whole membership as you see it (`var-limit`) |
| `limit_use()` | no | the cell's VaR over its node's limit, shown only when the limit is set for the view's horizon and confidence |
| `svar()` | no | stressed VaR: the cell's scope on the date's stress set (USD), computed scope by scope, for the first `stressed-max-cells` cells of a view |
| `dv01()` | yes | the cell's GIRR delta sensitivities per basis point, from the position sensitivities |
| `cs01()` | yes | the cell's credit-spread delta sensitivities |
| `sbm()` | no | FRTB-SA sensitivities-based capital of the cell's own scope ([FRTB_SA_GUIDE.md](FRTB_SA_GUIDE.md)), for the first `sbm-max-cells` cells |

**Always exact.** The cube's measures have one name each and no ≈ form: the pivot engine's sketches (≈ distinct counts and percentiles) and its progressive first answer from a sample never apply to them; a large view waits for its exact figures, or is refused by the budget with its count.

*Plain sum* says only how an Excel export writes a total: as a `SUM` formula of its cells where the figure adds up, as the server's value
where it does not. `dv01()`, `cs01()` and `sbm()` are offered only when the FRTB-SA engine runs here. With the MR cube off, the source offers
`var()`, `es()`, `pv()` and `trades()` only.

A figure the cube cannot give in a cell (stressed VaR past `stressed-max-cells`, SBM of a cell no scope text names, a limit of a cell that is
not a whole node) is empty, with its reason in the cell's notes; never a zero.

## 3. The inputs and their data contract

The cube adds no input of its own: it reads the VaR engine's book of the business date and, for the sensitivity measures, the FRTB-SA
engine's position sensitivities.

| Input | Kind | What the cube reads from it |
|---|---|---|
| Scenario P&L vectors or scenario PVs | `pnl-vector`, `scenario-pv` (+ `fx-scenario`) | every trade's vector; its columns (`LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY`, …) as dimensions |
| Scenario set | `scenario-set` | the scenario days of the histogram and the explain |
| Hierarchies and VaR limits | `risk-hierarchy`, `var-limit` | the hierarchy levels as dimensions (`MGMT.DESK`, `LEGAL.LEGAL-ENTITY`, `LL.LIMIT_LETTER`), `limit()` and `limit_use()` |
| Position sensitivities and the factor dictionary | `position-sensitivity`, `risk-factor` | `dv01()`, `cs01()`, the ladder and the heat map ([FRTB_SA_GUIDE.md section 3](FRTB_SA_GUIDE.md#3-the-inputs-and-their-data-contract)) |
| Official captures and the open dates | the VaR engine | the time-travel slider's stops and the sparklines |

**Positions** are the VaR engine's own ([VAR_GUIDE.md](VAR_GUIDE.md)). A dimension that is a column of the positions puts a **whole
position** in one value, exactly as a `WHERE:` rule selects it, so the cell's scope text selects the same trades.

**The risk class** (`RISK_CLASS`) is derived from each position's product group by configuration (`drishti.cube.mr.risk-classes`): a
position's primary class, the classes in their configured order, then `OTHER`. A bank that delivers its vectors split by risk class
(LENSES.md section 6.7.4) will give each position a vector per class; until then a desk of one product group has VaR in one class only.

## 4. The view: dimensions, options, cells and totals

A view of the cube is a pivot view ([PIVOT_GUIDE.md](PIVOT_GUIDE.md)) of source `var`: rows, columns, values, filters, the date, nodes opened
lazily, sort and top N, and the source's **options**:

| Option | Values | What |
|---|---|---|
| `scope` | the VaR engine's grammar: `BANK` (default), `DESK:RATES`, `NODE:MGMT~DESK-RATES`, `LE:LE-NY - BOOK:X`, `PORT:…`, `BASKET:…` | the trades the view starts from, as you see them |
| `horizon` | `1d` (default), `10d` | the VaR horizon |
| `method` | `sqrt10` (default for 10 days), `sum` | the 10-day method: the 1-day figure × √10, or the overlapping 10-day sums |
| `confidence`, `esConfidence` | `0.99`, `0.975` (or `99%`) | the VaR and ES confidences |
| `set` | `normal` (default), `stressed` | the scenario set (stressed: in USD) |
| `ccy` | `USD` (default), any reporting currency the engine offers | the reporting currency |

The lens is the VaR engine's ([VAR_GUIDE.md section 18.6](VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen)).
A name that is not an option is refused in words.

```json
{ "source": "var", "rows": ["MGMT.DESK", "MGMT.BOOK", "POSITION", "TRADE"], "columns": ["RISK_CLASS"],
  "values": [{"measure": "var()", "as": "VaR"}, {"measure": "comp_var()", "as": "Comp VaR"}],
  "filters": {"LEGAL_ENTITY": {"in": ["LE-NY"]}}, "options": {"scope": "BANK", "horizon": "10d"} }
```

**Dimensions** (`GET /api/v1/pivot/catalogue?source=var`):

| Type | Examples | Values and the cell's scope |
|---|---|---|
| hierarchy | `MGMT.DIVISION`, `MGMT.DESK`, `MGMT.BOOK`, `LEGAL.LEGAL-ENTITY`, `LL.LIMIT_LETTER` | the level's nodes by their code (`DESK-RATES`); the scope names the node, `NODE:MGMT~DESK-RATES` |
| string (a position column) | `DESK`, `TT_BOOK`, `LEGAL_ENTITY`, `CURRENCY`, `PRODUCT_GROUP`, `PRODUCT_TYPE` | the column's values; the scope is a `WHERE:` rule |
| string (a trade column) | `COUNTERPARTY`, `TRADE_STATUS`, any other column of the trades | the column's values; `COUNTERPARTY` is `CPTY:`, the others have no scope text |
| string (derived) | `RISK_CLASS` | the configured classes, then `OTHER`; the scope is `WHERE:PRODUCT_GROUP in (…)` |
| string (members) | `POSITION`, `TRADE` | each position (`POS:`), each trade (`TRD:`): the last levels of a drill |

The landing view (the catalogue's `defaultView`) puts the management hierarchy's desks on the rows, its lower levels then `POSITION` and
`TRADE` beneath them (each opened when you open a node), and `RISK_CLASS` on the columns.

**A cell, a subtotal and the grand total are the same thing**: a set of trades. The pivot engine plans the view level by level and asks the
VaR provider for every set of a level at once; the provider finds each cell's trades from per-trade dimension codes and sums **all** their
vectors **in one batch** (`VarBook.vectors`: on a sharded date one call per remote shard owner for all the cells), then takes each measure's
statistic once per cell. So:

- the grand total's VaR equals `VAR BANK` (or the view's scope) exactly;
- each subtotal is its node's own VaR, never a sum of the rows under it;
- the diversification of a node is **Σ children − node**, in money, beside it;
- an empty cell (a desk with no trade of the class) has no figure, not a zero.

**The partial-state cache** of the pivot engine keeps every cell's figures by the date's data version and intraday stamp, the viewer's rights,
the scope and the lens: a re-pivot or a second ask computes only the cells it has not seen (the answer says how many came from the cache).

**Rights.** The cube reads as the person, as the VaR explorer does. A role that sees part of a scope only in aggregate may pivot it by
**hierarchy levels** only: a column, the risk class, a position or a trade would name what it may not see one by one, and is refused
in words (`403`). A masked column is not offered and is refused if asked.

## 5. The landing (layout A)

`MCUBE <GO>` (or **MR cube** in the VaR menu, or **MR cube ›** in the VaR explorer, which opens the scope and date you are looking at) opens the
Pivot's preset `mr` (`/pivot?preset=mr`): the pivot screen on the VaR source with the MR lens, layout A, the pivot first, with tabs B and C beside it. `MCUBE <scope> BY <rows>,<columns> MEASURE <m>,… DATE <date> SORT desc <GO>` lays what you typed over the landing
view (`MCUBE DESK:RATES BY MGMT.BOOK,CURRENCY MEASURE VAR,COMP_VAR`); `MCUBE @owner/name <GO>` opens a saved view.

| Panel | What it shows |
|---|---|
| Headline strip | VaR of the scope (its rank of N), ES (mean of the worst), limit use with its bar (green under 80%, amber from it, red over 100%), **Σ rows − total** (the diversification), stressed VaR, the data (batches, intraday, data version, trades and positions) |
| Lens controls | the scope; the measure (VaR, ES, Comp VaR, DV01, SBM: one not running here is greyed); horizon 1D, 10D √10, 10D sum; confidence; scenario set; currency. Each changes the view's options and the engine answers again |
| Time-travel slider | the business dates with an official record, open on this server or held by the lake (official ones dotted); a click: *as of*; shift-click: *compare* (explain's earlier date); `[` and `]` step |
| Pivot | the pivot engine's grid: nested rows opened with + and −, *expand by…* per node, the field list and its zones, each cell a sparkline of the first measure over the open dates |
| Rail | the clicked cell's tail histogram (V and E), explain the move, the sparklines' note |

Click a cell to fill the rail and see its provenance; `→` opens a row (desk → book → position → trade), `E` expands a node by another field,
`Enter` lists a cell's trades. Keys of the lens: **H** 1D/10D, **[ ]** dates, **W** the what-if tray.

## 6. Tab B: tail and attribution; tab C: sensitivities and what-if

**Tab B** (the first row dimension): **components** (`comp_var()` of each member and its share of the total, a negative component a
hedge; they add up to the total exactly), the **waterfall** of the components sorted by size with the total as its last bar, the
**histogram** of the view's total, and the **factor heat map** of GIRR delta (DV01) by curve currency × tenor (▲ long in green, ▼ short in
red, intensity by size, every cell printed).

**Tab C**: the **sensitivity ladder**: GIRR delta by currency and the buckets of a grid (`BANK-IR-TENOR`: 0-2Y, 2-5Y, 5-10Y, 10Y+;
`FRTB-GIRR`: the regulator's vertices), net per bucket, a centre-zero bar (long right, short left), against the bucket's two-sided limit
when one is configured (`use = max(net / limit, −net / limit)`, **near** from 80%, **breach** over 100%). The **what-if side by side**: add
a booked trade copied and scaled (`× -1` unwinds it); every row of the view's first row dimension and the total, before, after and the change
(the Java what-if on each row's scope, [VAR_GUIDE.md section 8.2](VAR_GUIDE.md#82-the-what-if-the-java-engine)), a materially changed row
outlined. **Explain the move** of the last cell clicked, or of the view's scope.

A grid is configuration: each bucket `label: from-to` in years, starting where the last ended (a gap or an overlap is refused in words);
a tenor in no bucket is counted aside (`withoutTenor`).

## 7. Explain the move, time travel and provenance

**Explain the move** (`GET /api/v1/pivot/mr/explain?scope=…&from=…&to=…`) is the VaR engine's explanation of the cell's scope between two
business dates ([VAR_GUIDE.md section 3.11](VAR_GUIDE.md#311-why-it-moved-the-var-change-explained)): the earlier date's VaR (official when
it was captured), then each exact step with its effect, adding up to the later date's VaR. *Compare* on the slider sets the earlier date;
none: the business date before.

**Time travel.** The slider's stops are business dates and, within an open date, its batches in order (dates and batches, not raw lake
versions). Choosing a date that is not open opens it. The pivot screen's **Compare** shows the view on two dates cell by cell.

**Provenance** per cell (click it, `GET /api/v1/pivot/provenance/{id}?cell=<row>|<column>`): the cell's scope, its members, how its figure
is made; the view's shared part (the source's notes: route, scenarios, trades hidden by your role) and the work done (sets, cells from the
cache, the batch of vector sums). The headline's provenance (`POST /api/v1/pivot/mr/headline`) adds the date's data version and input
versions, batches and intraday updates, the scenario set and window, the method and lens, and the shards.

## 8. Saved views, sharing and export

The cube's views are pivot views: saved, shared read-only with people or roles and opened **with the reader's entitlements**
([PIVOT_GUIDE.md](PIVOT_GUIDE.md)); the options (scope and lens) are kept with the view. Export from the pivot screen's **Export**: Excel
(outline levels; `SUM` formulas only for `pv()`, `trades()`, `comp_var()`, `comp_es()`, `dv01()` and `cs01()`, the server's values for every
other measure; a Provenance sheet), CSV, Arrow, the definition as YAML, and a cell's trades.

## 9. Settings and switching the cube off

The cell budget, busy protection and the cache are the pivot engine's (`drishti.pivot.max-holistic-cells`, `busy-cells`, `cache-mb`,
[PIVOT_GUIDE.md](PIVOT_GUIDE.md)). The cube's own settings are in `config/engines/mr-cube.yaml`:

| Setting | Default | What |
|---|---|---|
| `drishti.engines.mr-cube.enabled` | `true` (`DRISHTI_MR_CUBE_ENABLED`) | the MR cube (engine `mr-cube`: needs `var` and `pivot`, uses `sensitivities` and `frtb-sa`) |
| `drishti.cube.mr.risk-classes` | IR, CR, FX, EQ, CM by product group | the derived `RISK_CLASS` dimension |
| `drishti.cube.mr.grids` | `BANK-IR-TENOR`, `FRTB-GIRR` | the ladders' bucket grids, in years |
| `drishti.cube.mr.ladder-limits` | none | two-sided DV01 limits per currency and bucket (`"USD 2-5Y": 1500000`) |
| `drishti.cube.mr.sbm-max-cells` | 60 | the cells whose SBM one evaluation computes |
| `drishti.cube.mr.stressed-max-cells` | 100 | the cells whose stressed VaR one evaluation computes |
| `drishti.cube.mr.sparkline-dates` | 20 | the open business dates a sparkline reads |
| `drishti.cube.mr.sparkline-max-cells` | 600 | the most cells of a view that get a sparkline |
| `drishti.cube.mr.what-if-rows` | 40 | the most rows the what-if side by side computes (the total always added) |

**Switched off** (Admin → Engines, or the file), `/api/v1/pivot/mr/...` answers `404 DRS-1020`, the `MCUBE` word, the menu entry and the
Help pages are gone, the VaR source of the pivot engine offers `var()`, `es()`, `pv()` and `trades()` by the positions' columns only (an MR
measure, a hierarchy level or an option is refused in words), and the cube's kept dimension codes are released. The engine-switch framework
is [ARCHITECTURE.md section 25](../architecture/ARCHITECTURE.md#25-compute-engines-on-or-off-each-with-its-own-configuration-file). Every setting is in [CONFIGURATION.md](../reference/CONFIGURATION.md).

## 10. API and command line

The grid is the pivot engine's API ([API_GUIDE.md, Pivot engine](API_GUIDE.md#pivot-engine-apiv1pivot)) with `"source": "var"`; the MR
lens adds:

| Request | What |
|---|---|
| `POST /api/v1/pivot/mr/headline` | `{view}`: the headline of the view's scope: `var`, `es`, `k`, `esCount`, `scenarios`, `lens`, `limit`, `limitUse`, `stressedVar` or `stressedNote`, `dataVersion`, `batches`, `intraday`, `trades`, `positions`, `provenance` |
| `POST /api/v1/pivot/mr/detail` | `{view, coords}`: one cell's tail: histogram `edges` and `counts`, `var`, `es`, `k`, `esCount`, the VaR scenario and its date, the ES tail, `trades`, `positions`, `scope` |
| `POST /api/v1/pivot/mr/history` | `{view}`: the sparklines: `dates`, `series` by cell key (`<row>|<column>`) of the first measure |
| `GET /api/v1/pivot/mr/versions` | the slider's stops |
| `GET /api/v1/pivot/mr/explain?scope=&from=&to=&confidence=` | why a cell's VaR moved |
| `GET /api/v1/pivot/mr/sensitivities?scope=&grid=&asOf=` · `?by=QUALIFIER,TENOR&class=GIRR&measure=DELTA` | the ladder by a grid, or the heat map by two factor parts |
| `POST /api/v1/pivot/mr/what-if` | `{view, items: [{copyOf, factor}]}`: each row and the total, before, after and incremental |

A `view` is the view itself or a saved view's id (`owner/name`). On the command line: `drishti.py pivot run --source var --rows MGMT.DESK
--cols RISK_CLASS --values "var()" --option horizon=10d`, and `drishti.py pivot mr headline|cell|versions|explain|ladder|heat|whatif`
([TOOLS.md](TOOLS.md)). In the console: `MCUBE <GO>`.

## 11. Refusals and error codes

| Code | When |
|---|---|
| `DRS-5701` (400) | a view the cube cannot answer: an unknown field, measure or option, an MR measure or option while the MR cube is off, a bad lens or grid |
| `DRS-5702` (422) | the view has more cells (with its totals) than the pivot engine's budget for holistic measures: the count is given; nothing is cut short |
| `DRS-5704` (404) | a saved view or a cell that is not there, or not shared with you |
| `DRS-5705` (403) | a masked column asked as a dimension |
| `DRS-1020` (404) | the MR cube (or the pivot or VaR engine) is not running on this server |
| `DRS-9507` (503) | busy protection: a large view did not start; `Retry-After` |
| `403` | a dimension that would name positions of a scope you see only in aggregate |
| `DRS-5215` (503) | a shard of the date has no live owner: the view is refused, never answered from the other shards |

## 12. How it is computed

The pivot engine (`drishti-bicompute`) owns the view: the levels, the cell sets, the budget, busy protection, the cache, provenance, saved
views and exports. The VaR provider (`VarPivotProvider` in the server) answers each set of cells: it opens the view's scope through its lens
as the person (`VarService`), finds each cell's trades from per-trade codes of each dimension (`VarPivotCodes`), sums every cell's vector in
one batch (`VarBook.vectors`, `ShardedVectors.sumSets`: the planner's node aggregates plus and minus the rows that differ, each remote
shard asked once for all the sets, partials added in shard order so each vector is the single set's to the bit), and finishes each figure
with the engine's own arithmetic (`VarCalc`, `VarMath`, `VarLens`). Components use the view's total vector (the filtered trades of the
view). The MR lens's endpoints (`MrCubeExtras`, `MrSensitivities`) read the same provider and engines. Nothing in the console computes a
figure.

## 13. What is not built yet

- **Per-class vectors** (the risk-class columns as each position's own split): the class is a position's primary class until the bank
  delivers per-class vectors.
- **The factor overlay** of explain the move (Σ sensitivity × the factor's move): the lake does not hold each factor's move by date yet; the
  exact steps add up on their own.
- **Within-date batches on the slider** as stops of their own (the slider moves between dates; a date's batches are listed on its stop).
- **Streaming patches** of visible cells on an intraday batch, **corrections** in the cube (MC11), the **phone tiers** beyond the pivot
  screen's narrow layout.
- **The cube's playground and its workbook of the VaR formulas** (the export has the totals that add up as formulas; the VaR statistic of a
  cell is the VaR Playground's, `PLAY VAR <scope>`).

## 14. Cube data: every dataset the cube reads

<!-- The block below is generated by tools/docs/guide_data.py from tools/docs/guide_data.yaml: edit the spec, not the block. -->
<!-- guide-data:begin mr-cube -->
Every dataset the feature reads: whether it is needed, what for, and its **data contract**, the one home of its fields and of one example record from the committed test data.

| Kind | Needed? | What it is for | Data contract |
|---|---|---|---|
| `pnl-vector` | **one of the two**, per trade | every trade's vector; its columns as dimensions | [`pnl-vector`](../contracts/packs/market-risk.md#pnlv-pl-vector-509-scenarios-pnl-vector) (PNLV, market-risk) |
| `scenario-pv` | **one of the two**, per trade | the same, as PVs that VaR turns into P&L | [`scenario-pv`](../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-trade-509-scenarios-its-currency-scenario-pv) (SPV, market-risk) |
| `scenario-set` | **yes** | the scenario days of the histogram and the explain | [`scenario-set`](../contracts/packs/market-risk.md#sset-scenario-set-scenario-set) (SSET, market-risk) |
| `risk-hierarchy` | **yes** | the hierarchy levels as dimensions | [`risk-hierarchy`](../contracts/packs/market-risk.md#hier-risk-hierarchy-node-risk-hierarchy) (HIER, market-risk) |
| `var-limit` | optional | `limit()` and `limit_use()` | [`var-limit`](../contracts/packs/market-risk.md#vlim-var-limit-of-a-hierarchy-node-var-limit) (VLIM, market-risk) |
| `position-sensitivity` | for the sensitivity measures | `dv01()`, `cs01()`, the ladder and the heat map | [`position-sensitivity`](../contracts/packs/market-risk.md#sens-position-sensitivities-position-sensitivity) (SENS, market-risk) |
| `risk-factor` | for the sensitivity measures | each factor's class, tenor and curve for the ladder | [`risk-factor`](../contracts/packs/market-risk.md#rfac-risk-factor-dictionary-risk-factor) (RFAC, market-risk) |

The cube adds no input of its own: these are the VaR engine's inputs and the FRTB-SA engine's sensitivities
([section 3](#3-the-inputs-and-their-data-contract)).
<!-- guide-data:end -->
