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

# Drishti Elements: the console's JavaScript as eight reusable libraries

**Status: design, not built** (release 2.9.11.e, product owner 2026-11-07; mock-ups g1 to g4 below, all decided). The internal design:
what each library is, what it takes over from today's code, the grid's pivot, the AG Grid-style adapter, the chart spec and the maps,
packaging and licensing, the documentation of every library, the gallery, the guards, the order in which Drishti's screens move, the
risks and the product owner's decisions. The renderer, the embed API and Elements 2.x (`<drishti-view>`) are designed in
[CLIENT_RENDERER.md](CLIENT_RENDERER.md) and are not repeated here.

## Contents

1. [Why: one implementation of each thing](#1-why-one-implementation-of-each-thing)
2. [The eight libraries](#2-the-eight-libraries)
3. [The rules every library keeps](#3-the-rules-every-library-keeps)
4. [Each library: purpose, API sketch, what it takes over](#4-each-library-purpose-api-sketch-what-it-takes-over)
5. [drishti-grid and its pivot](#5-drishti-grid-and-its-pivot)
6. [The AG Grid-style adapter](#6-the-ag-grid-style-adapter)
7. [drishti-chart: one spec, the kinds, the maps](#7-drishti-chart-one-spec-the-kinds-the-maps)
8. [Packaging, versions and licensing](#8-packaging-versions-and-licensing)
9. [The documentation of every library](#9-the-documentation-of-every-library)
10. [The gallery](#10-the-gallery)
11. [The guards](#11-the-guards)
12. [Moving Drishti's screens onto the libraries](#12-moving-drishtis-screens-onto-the-libraries)
13. [Risks](#13-risks)
14. [Decisions of the product owner (2026-11-07)](#14-decisions-of-the-product-owner-2026-11-07)
15. [Build checklist](#15-build-checklist)

## 1. Why: one implementation of each thing

The console's browser code grew screen by screen. Measured on develop (2026-11-07):

| What | Today |
|---|---|
| Console scripts (`drishti-console/web/static/js/`, with `js/build/`) | 239 files, 58,864 lines |
| The renderer (`static/render/1.2.0/`, without its playground) | 19 files, 3,184 lines |
| Grid implementations | about 10: `drishti_tables.js` with `drishti_colfilter.js`, `drishti_tree-rows.js`, `drishti_drill.js`, `drishti_pivot-grid.js` (with the field zones of `drishti_pivot.js`), `drishti_bi-grids.js` with `drishti_bi-matrix.js`, `drishti_designer-grid.js`, the basket's sheet (`drishti_var-basket-sheet.js`), the playground's windowed grid (`drishti_var-playground.js`), the raw viewer's tree, the renderer's `m/tables.js` |
| Scripts that draw charts themselves | 22: 16 console scripts call ECharts or Vega directly (`drishti_view.js`, `drishti_charts.js`, `drishti_bi-visuals.js`, `drishti_bi-vega.js`, `drishti_table-chart.js`, `drishti_pivot.js`, `drishti_geomap.js` and nine more), the renderer's `m/charts.js` and `m/draw.js`, and four draw SVG by hand (`drishti_var-charts.js`, `drishti_exposure-charts.js`, `drishti_var-backtesting.js`, `drishti_stress-window.js`) |
| Number formatters | at least six (`drishti_bi-lib.js`, `drishti_pack-ui.js`, `drishti_var-charts.js`, `drishti_exposure-charts.js`, the renderer's `m/format.js`, the pivot grid) |

Each copy has its own keyboard, its own accessibility, its own bugs and its own look. **Drishti Elements ends that**: every screen is
built from eight libraries, the old code is deleted as each screen moves (no dual machinery), and a guard stops a second copy from
growing back (section 11). The same libraries are usable outside Drishti: the product owner's other applications use AG Grid Enterprise
with heavy pivoting, and libraries 1 to 5 must serve them without a Drishti server.

## 2. The eight libraries

| # | Library | What it is | Server | Takes over today (measured: files, lines) | Expected size |
|---|---|---|---|---|---|
| 1 | **drishti-core** | formats, theme tokens, API client, events, accessibility helpers, resize, keymap, dialogs and menus | not needed | 18 files (12 whole, 6 in part), about 2,160 lines | 1,300 deduplicated; about 1,900 with the new parts |
| 2 | **drishti-inputs** | pickers (date and range, multi-select, people), forms and their rules, the filter bar, the code editor, the formula input | not needed | 15 files (11 whole, 4 in part), about 2,830 lines | 1,800; about 3,300 |
| 3 | **drishti-grid** | the grid with the pivot UI, tree data, ranges, editing, export, the AG Grid-style adapter | not needed (server-side rows optional) | 16 files (9 whole, 7 in part), about 3,760 lines | 1,700; about 7,700 (the most new code) |
| 4 | **drishti-chart** | one engine-neutral chart spec over ECharts, the general kinds, the maps | not needed | 23 files (7 whole, 16 in part), about 3,980 lines | 2,000; about 3,500 |
| 5 | **drishti-data** | data sources (local, REST, Drishti's pivot and rows), Arrow tables, live updates over one connection | not needed (talks to any server) | 9 files (5 whole, 4 in part), about 1,570 lines | 1,100; about 1,700 |
| 6 | **drishti-insight** | Explain, search, approvals, workbook export | a Drishti server | 9 files (6 whole, 3 in part), about 1,380 lines | 1,000; about 1,300 |
| 7 | **drishti-renderer** | Drishti screens from a (streamed) ViewModel, `<drishti-view>`, the layout grid | a Drishti server | 21 files (14 whole, 7 in part), about 4,250 lines | 3,600; about 3,000 once it draws with libraries 1 to 5 |
| 8 | **drishti-risk** | the banking module's chart kinds and panels (P&L distribution, backtest, exposure profile, VaR panels) | a Drishti server with the banking module; **licensed** | 8 files (2 whole, 6 in part), about 1,060 lines | 600; about 1,100 |
| | **All eight** | | | **116 files touched, about 21,000 lines folded** (17,800 from the console's scripts, 3,200 from the renderer) | **about 13,100 deduplicated; about 23,500 with the commercial feature set** |

How the numbers were taken: every `drishti_*.js` and renderer module was read and assigned to a library, wholly or in part (a share
estimated from its sections); "expected" applies a deduplication factor per library (the grid's ten copies fold hardest) and then adds
the new capability sections 5 to 7 call for. **After the move** the console keeps about 190 scripts of screen logic (49 scripts move
whole, 58,864 lines become about 38,000) and every table, chart, picker and format comes from a library. The inventory is regenerated
by the build (`tools/elements/inventory.py`, section 15) so these figures are checked, not trusted.

**Dependencies.** Each library depends only on **drishti-core** (and on its vendored engine: ECharts for drishti-chart, CodeMirror for
the code editor, Arrow JS for drishti-data). drishti-insight and drishti-renderer may use libraries 1 to 5; drishti-risk uses
drishti-chart and drishti-grid. Nothing depends on drishti-risk, and no library depends on the console.

```
drishti-core ◄── inputs, grid, chart, data            (1 to 5: no server needed)
     ▲               ▲      ▲      ▲
     └── insight, renderer ─┴──────┘                   (6, 7: a Drishti server)
                     ▲
                 drishti-risk                          (8: the banking module, licensed)
```

**Domain libraries.** Each later domain adds its own library, licensed with its module and built like drishti-risk: drishti-genomics,
drishti-public-sector, drishti-aml. A domain library may use libraries 1 to 7 and never another domain's.

## 3. The rules every library keeps

- **No framework required.** Each component is a **web component** (`<drishti-grid>`, `<drishti-chart>`, …) and a **plain JavaScript
  class** with the same options (`new Grid(el, options)`); React, Angular and Vue use them as they use any custom element (the samples of
  [CLIENT_RENDERER.md section 19](CLIENT_RENDERER.md#19-drishti-elements-developer-guide) extend to every library).
- **Plain ES modules, no build step**, readable as served; one entry per library plus its stylesheet, a `.d.ts` and an integrity record
  ([the renderer's shape](CLIENT_RENDERER.md#3-the-librarys-shape) and [versioning](CLIENT_RENDERER.md#13-the-elements-versioning-and-distribution) apply to all eight).
- **Vendored, never a CDN**; every file of ours carries the `drishti_` prefix inside the console and the library name outside it.
- **Files under 1,500 lines**; object-oriented, configuration-driven, no globals beyond one namespace (`window.Drishti.<Library>` for
  hosts without modules).
- **The theme is CSS custom properties** from drishti-core's tokens; light and dark, three **densities** (comfortable, compact,
  terminal: the product owner's decision on g1, 2026-11-07) shared by every component.
- **Accessibility is part of the component**: roles, names, keyboard, focus, announcements, contrast (WCAG 2.2 AA); axe runs on every
  gallery page.
- **The browser computes no figure Drishti owns.** Inside the console every total comes from the server
  (`test_console_does_not_aggregate.py`); the grid's client-side aggregation exists for standalone use and is off in the console (5.3).
- **One chrome**: the same toolbar, menus, status bar, Explain entry and export menu on the grid, the chart and the map (g1 to g3).

## 4. Each library: purpose, API sketch, what it takes over

### 4.1 drishti-core

Formats (numbers, money, percentages, dates, durations, "3 min ago"; the server's rounding), theme tokens and the theme switch, the API
client (fetch with the person's token, DRS problems with their fix, cancellation, retries), a typed event bus, accessibility helpers
(live region, focus trap, roving tab index), the one resize component, the keymap (one registry, conflicts refused, Help lists it), dialogs,
menus, toasts and popovers.

```js
import { format, theme, api, keys, dialog } from 'drishti-core';
format(-30205543.2, { style: 'compact' });          // "−30.21m"
theme.set({ mode: 'dark', density: 'terminal' });
keys.register('grid', { 'E': explainCell, 'Ctrl+C': copyRange });
const ok = await dialog.confirm({ title: 'Delete the basket?', danger: true });
```

Takes over: `drishti_app.js`, `drishti_theme-init.js`, `drishti_resize.js`, `drishti_settled.js`, `drishti_pack-ui.js`,
`build/drishti_drag.js`, `build/drishti_menus.js`, the renderer's `m/dom.js`, `m/format.js`, `m/net.js`, `m/signin.js`, `m/assets.js`;
the formats and JSON calls of `drishti_bi-lib.js`, and the dialogs of `drishti_busy.js`, `drishti_scope-delete.js`,
`drishti_artifact-uninstall.js`, `drishti_server-watch.js`, `build/drishti_grid-keys.js`.

### 4.2 drishti-inputs

`<drishti-multiselect>`, `<drishti-date>` (date, range, relative ranges, business dates), `<drishti-people>`, `<drishti-form>` (fields
from a JSON schema, rules evaluated as people type, drafts), `<drishti-filter-bar>`, `<drishti-code>` (CodeMirror with YAML, Python,
SQL and Rachana modes, completion from a schema), `<drishti-formula>` (Rachana with completion and the red part of a formula that does
not read).

Takes over: `drishti_multiselect.js`, `drishti_people.js`, `drishti_controls.js`, `drishti_form-expr.js`, `drishti_cm-yaml.js`,
`drishti_studio-yaml.js`, `drishti_studio-assist.js`, `drishti_editor-formula.js`, `drishti_bi-expr-editor.js`, `build/drishti_expr.js`,
`build/drishti_forms.js`; the inputs of `drishti_form.js`, `drishti_bi-format-pane.js`, `drishti_subscribe-when.js`,
`drishti_discussion-compose.js`.

### 4.3 drishti-grid

Section 5. Takes over: `drishti_tables.js`, `drishti_colfilter.js`, `drishti_tree-rows.js`, `drishti_drill.js`, `drishti_pivot-grid.js`,
`drishti_bi-grids.js`, `drishti_bi-matrix.js`, the renderer's `m/tables.js` and `m/cells.js`; the grids inside `drishti_pivot.js`,
`drishti_designer-grid.js`, `drishti_var-basket-sheet.js`, `drishti_var-playground.js`, `drishti_raw-viewer.js`,
`drishti_editor-table.js` and the renderer's `m/behave.js`.

### 4.4 drishti-chart

Section 7. Takes over: `drishti_charts.js`, `drishti_geomap.js`, `drishti_bi-vega.js`, `drishti_bi-multiples.js`,
`drishti_table-chart-engine.js`, the renderer's `m/charts.js` and `m/draw.js`; the chart code of `drishti_bi-visuals.js`,
`drishti_bi-visuals-format.js`, `drishti_table-chart.js`, `drishti_table-chart-sources.js`, `drishti_view.js`, `drishti_history.js`,
`drishti_history-page.js`, `drishti_bi-python.js`, `drishti_bi-ai.js`, `drishti_calc.js`, `drishti_zoom.js`, the BI designer's canvas
and panes, `drishti_bi-extras.js`, `drishti_bi-notebook-cells.js`.

### 4.5 drishti-data

```js
import { source, arrow, live } from 'drishti-data';
const s = source.rest('/api/v1/rows/positions');      // or source.local(rows), source.drishtiPivot(server, view)
const t = await arrow.fetch('/api/v1/rows/positions'); // column-oriented, Arrow where the server can, JSON otherwise
live.subscribe('vm:pivot/42', patch => grid.apply(patch));   // one connection per browser
```

A **data source** answers windowed, grouped, pivoted, sorted and filtered requests; the grid's server-side row model and the chart ask
it the same way. Takes over: `drishti_channel.js`, `drishti_live-hub.js`, `drishti_live.js`, `drishti_table.js` (Arrow),
`drishti_pivot-engine.js`; the freshness of `drishti_refresh.js`, the live part of `drishti_bi-live.js`, the file streaming of
`drishti_pack-read.js` and `drishti_pack-profile.js`.

### 4.6 drishti-insight

`<drishti-explain>` (the one Explain: a cell, a mark, a figure, a change between two dates), `<drishti-search>` (the one search),
`<drishti-approvals>` (the queue and a review), `workbook.export()` (the server's workbook with formulas and a Provenance sheet). It
packages the components the one-Explain, one-search and one-Approvals work finished ([SHARED_SCREENS.md](SHARED_SCREENS.md)); it adds
no second implementation. Takes over: `drishti_about.js`, `drishti_about-hints.js`, `drishti_about-ask.js`, `drishti_command.js`,
`drishti_bi-export.js`, the renderer's `m/about.js`; the Why card of `drishti_pivot-functions.js`, the review panel of
`drishti_bi-govern.js`, the dialog of `drishti_share.js`.

### 4.7 drishti-renderer

The renderer of [CLIENT_RENDERER.md](CLIENT_RENDERER.md) becomes library 7 (renderer 2.0.0): it draws panels with drishti-grid and
drishti-chart instead of its own `m/tables.js` and `m/charts.js`, and `behaviours: 'console'` loads library components instead of console
scripts. Elements 2.x (`<drishti-view>`, `<drishti-panel>`, `<drishti-report>`) move with it unchanged; the name **Drishti Elements**
now means the family of eight. Takes over: the renderer's entry and `m/` modules, `drishti_screen-grid.js`, `drishti_layout.js`,
`drishti_layout-rules.js`, `drishti_sutra-wire.js`, `drishti_compute.js`, `drishti_bi-frame.js`; the screen parts of `drishti_view.js`,
`drishti_zoom.js`, `drishti_record-forms.js`, `drishti_phone.js`, `drishti_bi-report.js` (the one page renderer), `drishti_bi-phone.js`.

### 4.8 drishti-risk

Chart kinds `risk.pnl-distribution`, `risk.backtest` (with the traffic light), `risk.exposure-profile`, `risk.var-change` (the
waterfall of what moved), `risk.stress-window`; panels for the VaR and exposure explorers; grid presets (the MR cube's columns). Served
only with the banking module and only to people whose role or lens uses it. Takes over: `drishti_var-charts.js`,
`drishti_exposure-charts.js`; the charts of `drishti_var-backtesting.js` and `drishti_stress-window.js`; the drawing parts of
`drishti_scenario-matrix.js`, `drishti_frtb-sa.js`, `drishti_simm.js`, `drishti_mr-lens.js`.

## 5. drishti-grid and its pivot

### 5.1 Three layouts of one grid (g1, product owner 2026-11-07)

[g1](mockups/elements/g1-pivot-grid.html): the product owner liked all three options, "they show different capabilities", so
**drishti-grid ships all three as layouts of one component**, chosen per screen or application with `layout`. They share the grid,
the pivot model, the menus, the status bar and Explain; only the arrangement around the grid differs.

| `layout` | Mock-up | Arrangement | Best at | Default for |
|---|---|---|---|---|
| `'wells'` | A, ribbon wells | the field list (with search) and the four wells (Filters, Rows, Columns, Values) in one strip above the grid; Explain in a drawer on the right that follows the selection | the most room for the grid; quick re-pivoting by drag; Explain read beside many cells | every screen that is not VaR (BI, Pivot, tables) |
| `'panel'` | B, tool panel | a side panel with the grouped, searchable field list (checkboxes) and the wells in a 2×2 block, pivot mode, subtotal and grand-total switches, deferred update; Explain as a card at the cell | building and changing pivots all day; long field lists; people and applications coming from AG Grid's or Excel's pivot panel | the AG Grid adapter (`sideBar` maps to it) |
| `'terminal'` | C, terminal | a command line, one lane of compact wells, a dense grid, Explain in a split pane below with its steps, the worst scenarios and the trend side by side, function keys at the foot | the keyboard (F2 explain, F3 and F4 expand and collapse, F7 chart, F8 export); a figure, its scenarios and its trend at once; traders | **VaR screens** (the VaR explorer, the MR cube, the scenario matrix) |

**Density** (comfortable, compact, terminal) is a separate setting from drishti-core, shared by every component; the terminal layout
does not force the terminal density, and any layout takes any density.

### 5.2 Features (professional, not a clone)

| Area | What drishti-grid does |
|---|---|
| Scale | virtual rows and columns (millions of rows, thousands of columns), row height fixed per density; server-side rows in windows with prefetch and cancellation |
| Columns | pinning left and right, reorder by drag, resize, autosize, hide and show, column groups, header menus (sort, filter, hide, pin, autosize, aggregation, Explain) |
| Rows | sorting (multi-column), Excel-style column filters and a quick filter, value filters on measures, row selection with checkboxes, tree data, grouping |
| Pivot | section 5.3 |
| Cells | cell ranges (drag, Shift+click, Shift+arrows), the status bar's count, sum, average, min and max of a range (and a warning when a range mixes measures or sums a non-additive one), copy and paste as text, editing with validation, conditional formatting (colour scales, data bars, rules), Explain on any cell |
| Menus | a context menu (Explain, Why did it move, copy, copy with headers, export, chart the range, drill to the rows, group by, pin, comment) and the column menu; both extendable by the host |
| Export | CSV and Excel from the browser for standalone use (an Excel writer in the library, values and outline); inside Drishti the server's export (values, outline, formulas where right, Provenance sheet) |
| Keyboard and readers | arrows, Home and End, Page keys, Enter to expand or edit, E or F2 to explain, Ctrl+C, Shift+F10; `role="grid"` or `treegrid`, `aria-rowcount` and `aria-colcount` for virtual rows, `aria-level`, `aria-expanded`, `aria-sort`, announcements of sort and filter changes |
| Look | Drishti's own: the theme tokens, three densities, light and dark, the shared chrome; three layouts (5.1); no copied styling |

### 5.3 The pivot

**The model.** A pivot is a view: `rows` (dimensions, in order), `columns` (dimensions, in order), `values` (a measure with its
aggregation), `filters` (members of a dimension, and value filters such as "VaR 99% > 1m" or "top 5 by VaR"), `totals` (grand total
and subtotals, before or after), `expanded` (which row and column groups are open). It is the pivot engine's view
([PIVOT_ENGINE.md section 4](PIVOT_ENGINE.md#4-the-pivot-model)); the grid adds nothing to its meaning.

**What the grid draws.** Row groups with + and − (lazy: a group's children are asked when it opens), **pivot result columns** with
collapsible column groups (a collapsed group shows its own total), totals and subtotals in the server's order, a grand total row and
column (pinnable), the measure's name in the leaf header, ≈ beside an approximate figure and ◆ on a non-additive measure.

**Where totals come from.** Two row models:

- **Server-side** (Drishti, and any server that answers the request below): the grid sends the view and the window it needs; the
  server answers rows in display order with every subtotal and total already computed. This is the only model the console uses.
  **Non-additive measures (VaR, ES, ratios, distinct counts, percentiles) are computed by Drishti's server for each total**, so a
  total is never the sum of its rows; the hover note and Explain say so (g1).
- **Client-side** (standalone pages with their rows in memory): the grid aggregates with built-in functions (sum, count, average, min,
  max, first, last, distinct count) and functions the host registers. A measure marked `aggregate: 'server'` shows no client total
  unless the host gives a function that computes it from the group's rows (for example a percentile of summed vectors); otherwise the
  cell says that it needs the server. In the console the client-side model is refused by policy (section 11).

**The request** (server-side, one shape for rows, groups and pivots):

```json
{ "view": { "rows": ["desk", "book"], "columns": ["region", "currency"], "values": [{"measure": "mtm", "agg": "sum"}, {"measure": "var", "agg": "var99_1d"}],
            "filters": {"trader": {"not": ["L. Duval"]}}, "valueFilters": [{"measure": "var", "op": ">", "value": 400000, "level": "leaf"}],
            "totals": {"rows": "after", "columns": "after", "grand": true} },
  "groupKeys": ["Rates"], "window": {"start": 0, "end": 200}, "sort": [{"by": "var", "column": {"region": "EMEA"}, "dir": "desc"}],
  "expandedColumns": ["Americas", "EMEA"] }
```

The answer is the pivot engine's answer ([PIVOT_ENGINE.md 4.4](PIVOT_ENGINE.md#44-the-answer)): rows in display order, the
pivot result column keys, cells with values, texts and tones, totals, approximation marks, the row count. drishti-data's
`source.drishtiPivot()` speaks it; a host's own server may answer the same JSON, or the AG-style request (section 6).

**Large data.** Windows of rows, lazily opened groups, column windows when the pivot is wide, a progressive answer (≈ first, exact
when ready) from the engine, cancellation when the view changes; the browser holds only what is in view and a margin.

## 6. The AG Grid-style adapter

**Purpose.** An application written for AG Grid Enterprise's pivoting moves to drishti-grid by keeping its configuration:
`Drishti.agGrid(el, gridOptions)` takes the **option shapes** of AG Grid's row grouping, pivoting and server-side row model and builds a
drishti-grid. **Clean room: written from AG Grid's public documentation only, never its source code, styling, class names or look.**
The result looks and behaves like drishti-grid. Unknown options are reported once in the console (named, with the nearest Drishti
option); the adapter never fails silently.

| AG Grid Enterprise option (public API shape) | drishti-grid |
|---|---|
| `pivotMode: true` | `pivot` on |
| `columnDefs[].rowGroup`, `rowGroupIndex`, `enableRowGroup` | `pivot.rows` (order), field allowed in Rows |
| `columnDefs[].pivot`, `pivotIndex`, `enablePivot` | `pivot.columns` (order), field allowed in Columns |
| `columnDefs[].aggFunc` (`sum`, `min`, `max`, `count`, `avg`, `first`, `last`, a registered name), `enableValue`, `allowedAggFuncs`, `defaultAggFunc` | `pivot.values[].agg`, the aggregation picker's list; `aggFunc: 'drishti:server'` marks a non-additive measure |
| `aggFuncs` (custom functions) | `aggregations.register(name, fn)` (client-side model) |
| `groupDisplayType` (`singleColumn`, `multipleColumns`, `groupRows`), `autoGroupColumnDef` | `groupColumn: 'single' \| 'multiple' \| 'rows'`, the group column's definition |
| `groupDefaultExpanded`, `pivotDefaultExpanded` | `expanded: { rows: n, columns: n }` |
| `grandTotalRow`, `groupTotalRow` | `totals.grand`, `totals.rows` (`before` or `after`) |
| `pivotRowTotals`, `pivotColumnGroupTotals` | `totals.columns`, collapsible column groups |
| `suppressAggFuncInHeader`, `removePivotHeaderRowWhenSingleValueColumn` | `header.showAggregation`, `header.singleValueRow` |
| `processPivotResultColDef`, `processPivotResultColGroupDef` | `pivot.columnDef(fn)`, `pivot.columnGroupDef(fn)` hooks |
| `sideBar` (columns and filters tool panels), `rowGroupPanelShow`, `pivotPanelShow` | `layout: 'panel'` (g1 B); without a `sideBar`, the host's chosen layout |
| `rowModelType: 'serverSide'`, `serverSideDatasource.getRows(params)` | a drishti-data source wrapping the host's function |
| `params.request`: `startRow`, `endRow`, `rowGroupCols`, `valueCols`, `pivotCols`, `pivotMode`, `groupKeys`, `filterModel`, `sortModel` | translated to and from the request of 5.3 (window, view, groupKeys, filters, sort) |
| `params.success({ rowData, rowCount, pivotResultFields })`, `params.fail()` | the answer: rows, row count, pivot result column keys |
| `serverSidePivotResultFieldSeparator` | the key separator of pivot result columns |
| `treeData`, `getDataPath`, `isServerSideGroup`, `getServerSideGroupKey` | `tree: { path }`, lazy tree groups |
| `columnDefs[]`: `field`, `headerName`, `pinned`, `hide`, `width`, `sort`, `valueFormatter`, `cellClassRules`, `filter` | the column definition: field, header, pin, hidden, width, sort, format, rules, filter |
| `cellSelection` (cell ranges), `statusBar`, `getContextMenuItems`, `getMainMenuItems` | ranges and the status bar, the context and column menus (extendable) |
| API: `setGridOption('pivotMode', …)`, `applyColumnState`, `setRowGroupColumns`, `setPivotColumns`, `setValueColumns`, `getPivotResultColumns`, `expandAll`, `collapseAll`, `exportDataAsCsv`, `exportDataAsExcel`, `refreshServerSide` | the same calls on the adapter's handle, forwarded to the grid's API |

**Not covered in version 1** (named in the console when used): integrated charts (use drishti-chart on a range), master-detail rows,
sparkline cell renderers, row dragging between grids, AG Grid's theming API and CSS classes (Drishti's theme applies), the
viewport row model. The migration guide (section 9) lists each with its Drishti way.

## 7. drishti-chart: one spec, the kinds, the maps

### 7.1 The spec

**ECharts stays the drawing engine**; drishti-chart is one **engine-neutral spec** above it, so a Sutra panel, a BI visual, the AI and
`new Chart(el, spec)` all say the same thing and nothing in a spec names ECharts ([g2](mockups/elements/g2-charts.html), option B shows
the spec beside the chart).

```json
{ "kind": "line", "title": "Daily P&L", "data": { "source": "var/backtest?scope=BANK&days=250" },
  "x": { "field": "date", "type": "time" }, "y": { "format": "money" },
  "series": [{ "field": "hypothetical" }, { "field": "var99", "negate": true, "style": "dashed", "tone": "bad" }],
  "marks": [{ "when": "hypothetical < -var99", "as": "point", "tone": "bad" }],
  "legend": "bottom", "zoom": { "window": [150, 250] }, "onClick": "explain", "export": ["png", "svg", "csv"], "a11y": { "table": true } }
```

Every chart gets, without asking: the theme and density, drishti-core's formats on axes and tooltips, a crosshair tooltip, legend
toggles, click → Explain (with drishti-insight), export (PNG at 2×, SVG, CSV of the data, the spec), a **data-table alternative** that
screen readers read and a button shows, and the same card chrome and status line as the grid.

### 7.2 The kinds

| General (drishti-chart) | Risk (drishti-risk, licensed) |
|---|---|
| line, area, bar (grouped, stacked, horizontal), scatter, bubble, heatmap (sequential and diverging), waterfall, candlestick, histogram, box plot, treemap, sankey, graph, gauge, sparkline, map | `risk.pnl-distribution` (scenario P&L with VaR and ES lines, the tail marked), `risk.backtest` (P&L against −VaR with exceptions and the traffic light: green 0 to 4, amber 5 to 9, red 10 and more, with the plus factor), `risk.exposure-profile` (EE, PFE, EEPE, the limit), `risk.var-change` (the waterfall of what moved), `risk.stress-window` |

A kind is a module that turns the spec into ECharts options; a domain library registers its kinds (`chart.kinds.register`) and they
appear in the gallery and in Sutras like the general ones.

**Vega-Lite.** Today a BI visual of `type: vega-lite` and a Python visual's Vega-Lite output draw with Vega (845 KB, and an interpreter
to avoid `unsafe-eval`). Two engines is dual machinery, so Vega is retired (decision O1, section 14).

### 7.3 Maps

`kind: "map"`: the world, then **a country's first-level regions**: US states (and DC), **Canadian provinces and territories**, and the
first-level regions of every country that has a file, from the vendored Natural Earth data already in
`drishti-console/web/static/vendor/echarts/maps/` (`world-countries.json` at 1:110m, `world-points.json` for countries too small for that
scale, `regions/<ISO3>.json` for 252 countries at 1:10m simplified; public domain). A country's regions **load when first opened** and
stay cached for the session; nothing comes from a map service. [g3](mockups/elements/g3-maps.html) shows the world → USA → Canada drill
with quantile and gradient legends, the ranking, the inset and the table.

```js
new Chart(el, { kind: 'map', level: 'world', value: { field: 'ead', format: 'money' }, scale: { type: 'quantile', classes: 5 },
                drill: 'regions', onClick: 'explain' });
```

Codes are ISO 3166 (alpha-3 for countries, 3166-2 for regions, as the files hold them); a value keyed by a name is matched by
drishti-chart's place lookup (today `drishti_geomap.js`'s `find`). Projections: Equal Earth for the world, Albers for the USA (with
Alaska and Hawaii insets) and Canada, a conic fitted to the country otherwise.

## 8. Packaging, versions and licensing

**Where the files live.** Each library is a folder per version, `drishti-console/web/static/elements/<library>/<version>/` (entry,
modules, stylesheet, `.d.ts`, `integrity.json`), served as `/static/elements/…` to the console and `/embed/v2/elements/…` to other
origins with the rules of [CLIENT_RENDERER.md section 13](CLIENT_RENDERER.md#13-the-elements-versioning-and-distribution) (immutable
released folders, SRI, a partial version redirecting to the exact one). drishti-risk's folder lives in the banking module's plugin and
is served from `/static/banking/elements/risk/<version>/` behind the entitlement check.

**Versions.** Semantic versions **per library**; a released folder never changes; a breaking change only in a new major, announced one
release ahead. A **release train** file (`elements.json`) names the versions that go together for a Drishti release, so a host pins
one train or single libraries. Elements 2.x and the renderer's 1.x keep working one release beside renderer 2.0.0.

**Outside Drishti.** Libraries 1 to 5 are copied into a host application's own vendored folder (never a CDN); an npm package per
library (`@drishti/core`, `@drishti/grid`, …) is documented, not published (as the renderer's package).

**Licensing.** Libraries 1 to 7 ship with the product. **drishti-risk is licensed with the banking module** and every later domain
library with its module: an unlicensed module's library is not served, its kinds are not registered, and its gallery pages are hidden.

**What the domain-module platform must deliver first** (the module split, designed on `origin/wip/e-boundaries` in
`docs/architecture/DOMAIN_MODULES.md`):

1. the console plugin loader with a plugin's own `static/` folder served only to the people whose role or lens uses it;
2. an **import map** per page (vendored, generated from the release train and the enabled modules) so pages and plugins import
   `drishti-grid` by name rather than by path;
3. the integrity record and the immutable-version check of the renderer generalised to any folder under `static/elements/` and a
   plugin's `elements/`;
4. the architecture guard extended to JavaScript: core code never imports a domain library, domain libraries never import each other;
5. the module licence check reachable from the console, so a library's folder is served or refused by it.

### 8.1 As built: the foundation, drishti-core and the grid's engine

- **Files.** `static/elements/drishti_elements.json` is the release train (each library's version, entry, requirements, style sheets).
  A library is ES modules under `<library>/src/` (they run as served: `import` them) and one bundled file, `<library>/drishti_<name>.js`,
  for hosts without modules and the console's classic pages: a classic script that registers `Drishti.elements.<name>` and is a CommonJS
  module under Node. The bundle is written by `tools/elements/build.py` (Python, offline, no npm; `--check` refuses a stale bundle).
  The per-version folders of this section come with the first released version.
- **Loading.** drishti-core carries the loader: `Drishti.elements.load('drishti-grid')` adds what a library requires, its style sheets
  and its bundle (`?v=<version>`), once each. `base.html` loads drishti-core on every page.
- **One home for each fact.** The named formats are generated from the server's `formats.yaml` and the themes from `drishti_tokens.css`
  into drishti-core; numbers are rounded half up on the decimal value with the true minus, as `FormatSpec` does.
- **Moved into drishti-core:** the resize handles (`drishti_resize.js`), the panel keys (`build/drishti_grid-keys.js`; `Drishti.resize`
  and `Drishti.GridKeys` keep their names), the alerts' and the workbench's toasts, the table editors' dialog, and the number helpers
  of twelve screens. `drishti_charts.js`, `drishti_view.js` and `drishti_pivot-engine.js` keep their own until drishti-renderer: the
  frozen renderer loads them into host pages that have no drishti-core.
- **The grid's engine speaks the server's pivot.** The view and the answer are the pivot engine's own (`PivotView`, `PivotAnswer`:
  `{measure, as}` values, `{in, notIn}` member filters, `expanded` paths, the row kinds grand, node, header, total, others); 5.3's
  request maps onto it, and value filters are applied by the grid. A column with `additive: false` (or `aggregate: 'server'`) is never
  summed in the browser: its cells wait for the server, which is asked for exactly those. `clientAggregation: false` is the console's
  policy. The layout (`wells`, `panel`, `terminal`) and the density are state the engine keeps and gives back; it draws nothing.
- **Events and live data** (`drishti_grid-live.js`, `drishti_live.js`). Typed events (`GRID_EVENTS`) on the bus and, on the web
  component's host, as DOM events `drishti:<kebab-name>`; transactions by row id, batched (`applyTransactionAsync`, a row's last update
  wins) and applied in place: a row whose sort or filter values changed is merged back where it belongs, ties keep their order; each
  changed cell's direction for its flash; a feed message is `{type: transaction | dataChanged | rows}` over SSE or a WebSocket; a
  server's data-changed notice invalidates blocks, a level, the blocks holding some rows, or everything. Group and local pivot totals
  change by difference (sums, counts, averages; the others from their members); non-additive ones are asked of the server again
  (`serverGroupTotals`, `serverPivot`), debounced.
- **Ready for the renderer.** Every module is a plain ES module with no console global: the renderer imports
  `drishti-core/src/drishti_index.js` and `drishti-grid/src/drishti_index.js` directly (a page touches `window` only through
  `install()`, which the bundle runs). One difference to settle when the renderer moves: its `m/format.js` rounds as Python does
  (half to even, for parity with the console's Python-drawn panels), drishti-core as the server's `FormatSpec` (half up); one rule wins.
- **The visual layer (drishti-grid 0.2.0).** `new Grid(element, options)` (`drishti_grid-view.js`) and `<drishti-grid>`
  (`drishti_grid-element.js`: options as a property, `layout` and `density` as attributes, events as `drishti:<name>` DOM events,
  `el.grid` the plain API; no shadow root, so the page's tokens and style sheet reach it) draw the engine's model. The modules, each
  under 530 lines: `drishti_grid-body.js` (one scroller with role grid or treegrid; heading rows stuck to its top with a pivot's member
  groups spanning their columns and collapsible; the rows the viewport gives, each one element with its pinned cells stuck left and
  right inside it, so a reader reads a row as one; the middle columns windowed), `drishti_grid-shell.js` (the three layouts' chrome:
  toolbar, ribbon, side panel, command line, function keys, status bar, the Explain drawer, card or split pane),
  `drishti_grid-wells-ui.js` (the field list and the wells: drag and drop, the chip menus, member filters, value filters, top N, the
  panel's switches and deferred update), `drishti_grid-actions.js` (the keys, the column, context and toolbar menus, copy, export,
  chart the range, the rows behind a cell), `drishti_grid-explain.js` (what the Explain hook answers, drawn by drishti-core in the layout's place),
  `drishti_grid-base.js` (the columns as drawn: panes, widths, a phone's subset), `drishti_grid-dom.js` (the menu,
  the hover note) and `drishti_grid-ui.js` (the decisions without a page, tested in Node: heading spans, conditional formats, the
  phone's columns, menu items, the command line, value filter conditions, the status figures, the flash). The look is
  `drishti_grid.css`, every colour a drishti-core token; the densities set the row heights the engine uses (36, 28, 22 px).
  **Nothing inline**: elements are made with the DOM and styled through the CSSOM, so a page with a strict CSP draws the grid.
  **Totals stay the server's**: a non-additive total is marked ◆ with a hover note, a range that holds one gets no sum (a warning
  instead), and its aggregation in the menus is the server's, disabled with the reason. **Live**: after a transaction, a figure drawn
  again that changed flashes up or down (the CSS animation is dropped under `prefers-reduced-motion`; a still edge marks the change);
  pending server figures and loading blocks show placeholders. **Phone** (under 640 px): the wells behind a Fields button, Explain as a
  sheet, the first column pinned; a pivot shows its row headings and one value column, the grand total of the measure picked in the
  toolbar's switcher. The **fixture harness** `static/elements/drishti-grid/drishti_harness.html` (synthetic trades, a stand-in server
  for diversified VaR and ES and for server-side rows, an Explain hook, a live tick; `?layout=…&density=…&theme=light&mode=pivot|grid|server&rows=N`,
  `?matrix=1` for all nine) opens with no server, from the file system too; the browser test of the component runs against it.
- **Reference and tests.** [ELEMENTS_API.md](../guides/ELEMENTS_API.md) is generated from the typed JSDoc by
  `tools/docs/elements_api.py` (`--check` refuses a public symbol without types); the unit tests run in Node (`tests/elements/`,
  `node --test`) from `tests/test_elements.py`, a quick file check of the gate; the engine at a million rows is in
  [BENCHMARK.md](../admin/BENCHMARK.md). The drawn grid is tested once, in one browser file against the fixture harness
  (`tests/test_elements_grid_browser.py`: every layout and density in both themes, drag between wells, every menu item, button and
  function key, ranges and the status bar, the keys and roles, the flash, server blocks, 100,000 virtual rows, a phone); screens that
  move onto it (section 12) test only their wiring.

### 8.2 As built: drishti-chart, its maps and drishti-risk

- **The spec** (`drishti_chart-spec.js`): `kind`, `title`, `subtitle`, `data` (rows inline as objects, `{values}`, a server's answer
  `{columns, rows}`, or `{source}` read with the page's fetch), `id` (the field that names a row: selection and live updates follow it),
  encodings `x`, `y`, `category`, `value`, `series` (a list of `{field, name, tone, style, negate, area, stack, axis}` or `{by: field}`, one
  series per member), `size`, `colour`; formats by name through drishti-core (`money`, `compact`, `percent`, `amount0`, `pct2` …);
  axes (`type` category, time, value or log; `title`, `order`, `domain`, `min`, `max`); `legend` (bottom, right, top, none);
  `annotations` (lines, bands, points and texts on either axis); `marks` (points where a condition holds: `"hypothetical < -var99"`);
  `colourWhen` (a mark's tone by a condition); `stack` (true or `percent`), `orient`, `sort`, `labels`, `zoom` (`{window: [150, 250]}`),
  `onClick` (explain, select, none), `export`, `live` (`{throttleMs, keep}`). Conditions are read by a small parser (comparisons,
  arithmetic, `&&`, `||`, `datum.x` as Vega-Lite writes it), never by `eval`. `checkSpec` names every problem with its path;
  `normalizeSpec` fills the defaults; `specText` is the spec as the studio shows it. A kind is registered with `registerKind(name,
  {build, label, family, cartesian})`; nothing in a spec names ECharts.
- **The kinds**: line, area, bar (grouped, stacked, stacked to 100%, horizontal, sorted), scatter and bubble (value or category axes,
  symbols), histogram (equal bins on a value axis, the tail beyond a value in its tone), waterfall (totals first and last or by a
  field), box plot (whiskers at 1.5 IQR, outliers), heatmap (sequential, or diverging around a middle), candlestick, pie and donut (small
  slices folded into Other), gauge (bands in tones), treemap (nested by levels), sankey (links summed), `layer` (several kinds over the
  same axes, categories the union of every layer's) and `map`. Each builds the ECharts option, the data table, the legend and one
  line a screen reader reads, without a page (`drishti_chart-kinds.js`, `drishti_chart-kinds2.js`); every data item carries the indexes
  and ids of the rows it was made of.
- **The card** (`new Chart(el, spec, options)`, `<drishti-chart>`; `drishti_chart-view.js`, `drishti_chart-parts.js`): the title, the
  kind, a status badge (the backtest's zone), the tools ▦ ⤓ ? ⛶ and the zoom's reset, the legend as buttons that hide a series, the
  status line (the source, the rows, the time to draw). The theme follows the page (a change of `data-theme` redraws), the three
  densities are the grid's. Tooltips are elements built with the DOM (a strict CSP holds), with a crosshair on axes. **Click any mark:**
  its rows are selected (ctrl or shift adds) and outlined, and the Explain hook (the grid's: a context of kind, series, field, value,
  category, rows and ids) answers in a side sheet drawn by drishti-core's `renderExplain`, as the grid draws it. Zoom: the wheel, a drag
  across the chart, and the overview window under it. The keyboard: arrows walk the first series' marks with an announcement, Enter
  or E explains, T opens the table, Escape closes. Export: PNG at 2×, SVG drawn off screen by the engine's server-side renderer, CSV of
  the data (drishti-core's writer), the spec. **Live**: `apply({add, update, remove})` by row id, `append`, `replace`, drawn once per
  throttle window; `live.keep` caps the rows kept. **Studio** (`mode: 'studio'`, `drishti_chart-studio.js`): the registered kinds in a
  rail grouped by family (general, maps, then each domain library's), the chart with its table under it, the spec beside it with options
  (legend, labels, stack, bars, log x, zoom, density) that change the chart and the spec together.
- **Linked selection** (`drishti_chart-link.js`): charts with `link: {channel}` and grids linked by `linkGrid(grid, {channel})` exchange
  row ids on drishti-core's bus (`drishti:selection`); a selection that came from the bus is never sent back. drishti-chart does not
  import drishti-grid: a grid is linked through its model's typed events and selection.
- **Maps** (`drishti_chart-geo.js`, `drishti_chart-map.js`, `drishti_map-view.js`). The shapes are projected before the engine sees them
  (an identity projection draws them): Equal Earth for the world (Antarctica left out; the countries too small for 1:110m as points),
  Albers for the United States (the lower 48 and DC; Alaska and Hawaii in their own Albers, scaled into framed insets under the
  south-west) and for Canada (Statistics Canada's parallels; ten provinces and three territories), and for every other country a conic
  fitted to its core extent (the rings near its area-weighted centre, so France fits its mainland and its overseas regions stay
  reachable by panning), or a plate carrée scaled at the middle latitude where a conic cannot fit. A country's `regions/<ISO3>.json` is
  read the first time it is opened and kept for the session; a failed read is asked again next time. Places match by ISO alpha-3,
  alpha-2, a region's ISO 3166-2 code with or without its country, or the English name; rows that match nothing are counted on the
  status line. The choropleth: quantile classes (each with its range and count, and the no-data count) or a continuous ramp; no data
  hatched; a legend class clicked keeps it bright; bubbles at places or at longitude and latitude; a metric picker; the wheel and drag,
  and + − ⟲. Double-click a country (or "Drill into regions ›") for its regions, the breadcrumb back. A page gives a level's rows with
  `placeData(iso3)`, or one set of rows holds every level. Modes (g3): `ranking` (the default: the legend, the top ten with bars, the
  selected place with its rank and share, Explain and the drill) and `table` (a floating legend, a world inset once drilled, the selected
  place in a floating card, a sortable table under the map).
- **Vega-Lite translated** (O1; `fromVegaLite(vl, {rows})` gives `{spec, unsupported, refused}`). Drishti's Vega-Lite visuals today are the
  report visual of `python-desk-risk` (a bar with a sort, a colour condition and a tooltip) and the Python sample `pnl-vega` (two layers:
  dots and median ticks over a nominal axis); both translate whole. Coverage:

  | Vega-Lite | Translated to |
  |---|---|
  | marks bar, line, area, point, circle, square, tick, rect, arc, boxplot | bar, line, area, scatter (or bubble with size), heatmap, pie or donut (innerRadius), boxplot |
  | rule at a constant (`datum`), or a few rows' values | annotation lines |
  | `layer` with data of its own or inherited | the `layer` kind |
  | encodings x, y, color, fill, stroke, size, theta; tooltip and opacity | axes, `series: {by}`, `colour`, bubble size, the pie's value; Drishti's tooltip |
  | types quantitative, nominal, ordinal, temporal | value, category (sorted as Vega-Lite sorts), time |
  | `aggregate` sum, mean, average, count, min, max, median; `bin` with maxbins | rows aggregated in the translation; the histogram kind |
  | `sort` -y, y, ascending, descending, an array; `stack` null, false, normalize | `sort`, the category order, `stack` (percent) |
  | `scale` log, zero, domain; `axis` title and format (d3: `,.0f`, `.2%`, `~s`) | log axes, min and max, titles, drishti-core's named formats |
  | color condition `{test, value}`, a fixed colour | `colourWhen` and tones (a red is bad, a green good, an orange accent, a grey muted) |
  | transforms filter (an expression or a field predicate) and calculate | applied to the rows in the translation |
  | `title` (text and subtitle); width, height, autosize, config, $schema | title and subtitle; the card sizes and themes the chart |

  Facets, repeat and concat, params and selections, other transforms, text marks, projections and geoshape are listed in `unsupported`
  (the visual is named and redrawn once); a `url` or a `loader` anywhere is refused, as before. The vendored Vega stays until the BI
  visuals move (section 12, step 5), then it is deleted.
- **drishti-risk** (`static/elements/drishti-risk/`, the banking module's; the manifest marks it `module: banking`; it moves into the
  plugin's folder with the module platform of section 8): `risk.pnl-distribution` (the histogram of scenario P&L, the VaR and ES lines,
  the tail beyond VaR in the bad tone), `risk.backtest` (P&L against minus the day-before VaR, exceptions marked, the Basel traffic light
  with its plus factor on the card: green 0 to 4, amber 5 to 9, red 10 and more over 250 days), `risk.exposure-profile` (EE and PFE on a
  log tenor axis, EEPE over the first year, the limit, a breach on the card) and `risk.var-change` (a waterfall where an increase is bad).
  VaR, ES and EEPE are the server's (in the spec or the rows); only a page without a server computes them from the rows given, and the
  card says "computed here".
- **drishti-core** gained what the grid and the chart share: the element maker (`h`, `placeAt`), Explain's drawing (`renderExplain`,
  `sparkline`, under each component's class prefix), the CSV writer and `download`. The loader loads a library's vendored engine (the
  manifest's `scripts`: ECharts for drishti-chart) once, and not when the page has it.
- **Tests.** Node (`tests/elements/chart.test.mjs`, `maps.test.mjs`): the spec and its checks, the condition language, the formats and
  theme, every kind's option, table and legend, the risk figures, the translator on the two shipped specs and the subset, linked
  selection, the bundles, the projections, the loader and every one of the 252 region files. The drawn chart is tested once, in
  `tests/test_elements_chart_browser.py` against the fixture harness `static/elements/drishti-chart/drishti_harness.html` (synthetic
  data, no server; `?view=board|studio|map|link`, `&mapMode=table`, `&level=USA`, `&density=…`, `&theme=light`).

## 9. The documentation of every library

The owner asked for documentation as rich as a commercial product's. Within the lean-docs rule (one home per fact; references are
generated, never written twice) every library has:

| Document | One per | Where | Written or generated |
|---|---|---|---|
| **Guide** (concepts: what it is, how its parts fit, decisions such as server totals) | library | `docs/guides/elements/<library>.md`, a Help page | written |
| **API reference** (every option, event, method, type and CSS custom property) | library | Help → Developer → Elements, and the gallery's API tab | **generated** from typed JSDoc by `tools/elements/api_ref.py`; `--check` fails the build when a public member has no description or a type is missing |
| **Tutorial** (one task end to end: a pivot over your own data, a chart with Explain, a world map with drill) | library | `docs/tutorials/`, numbered with the others | written, with screenshots from the gallery |
| **Live gallery** (runnable examples) | component | `/elements/gallery/<library>/<component>` | the examples are files the tests also run (section 10) |
| **Migration guide from AG Grid Enterprise (pivot)** | drishti-grid | `docs/guides/elements/grid-from-ag-grid.md` | written; its option table is **generated** from the adapter's mapping table (the code's table is the one home of section 6's table) |
| **Theming guide** | the family | `docs/guides/elements/theming.md` | written; the list of tokens is generated from drishti-core's token file |
| **Accessibility guide** | the family | `docs/guides/elements/accessibility.md` | written; each component's keyboard table is generated from its keymap registration |
| **Changelog** | library | `static/elements/<library>/CHANGELOG.md`, shown on the gallery's Changelog tab | written per release; the product `CHANGELOG.md` links it rather than repeating it |
| **Versioning policy** | the family | one section of the theming guide's sibling, `docs/guides/elements/versions.md` | written once |

Help lists one entry per library guide and per tutorial, grouped under Developer; the generated references are built at build time and
never committed (as the Javadoc and pdoc references). The documentation tests gain: every public member documented (`--check`), every
gallery example runs, every guide links its API reference and tutorial, no option table written by hand.

## 10. The gallery

[g4](mockups/elements/g4-gallery.html): option A, the three-pane playground (the list of libraries and components with their version and
a badge: no server needed, needs a Drishti server, banking module licence; the live component; code tabs for the web component, plain
JavaScript, a Sutra and, for the grid, the AG Grid adapter; options generated from the typed JSDoc; an event log), with option B's tabs
(Examples, API, Accessibility, Changelog) as the published page. Served by the console at `/elements/gallery`; libraries 1 to 5 run with
no server (static files and sample data); 6 to 8 against the console's own server. **Each gallery example is a test**: the browser suite
opens every example in both themes and three densities, runs axe, and checks the code snippet produces the same component (the snippet
is the example's source, not a copy).

## 11. The guards

| Guard | What it fails |
|---|---|
| **No second implementation** (`test_elements_no_duplicates.py`) | outside `static/elements/`: a call to `echarts.init`, `setOption`, `vegaEmbed`; a `<table>` built with sorting, filtering or paging code; a number formatter (`toFixed` with a unit suffix, `Intl.NumberFormat` outside drishti-core); a drag handle; a hand-made dialog. An allow list holds exactly the files not yet moved and **only shrinks** |
| **Dependencies** | a library importing anything but drishti-core, its vendored engine and (for 6 to 8) libraries 1 to 5; a domain library importing another domain's |
| **No server for 1 to 5** | a reference to `/api/`, `/embed/` or the console in libraries 1 to 5 (their Drishti sources live in drishti-data behind an explicit `server` option) |
| **The browser computes nothing in the console** | a console page creating a grid with the client-side aggregating model (extends `test_console_does_not_aggregate.py`) |
| **Immutable versions** | a changed file in a released version folder without a new version and integrity record |
| **Assets** | an outside URL (`test_assets_policy.py`), a missing `drishti_` prefix inside the console (`test_asset_names.py`), a file over 1,500 lines |
| **Licence** | drishti-risk served, or its kinds registered, without the banking module's licence |
| **Docs** | `api_ref.py --check`, a gallery example that does not run, a hand-written option table |
| **Size budget** | a library's gzip size over its budget (set from the first release, raised only on purpose) |

## 12. Moving Drishti's screens onto the libraries

Each step is one branch with its gate; the screen moves, its old code is deleted in the same branch, the allow list of 11 shrinks, and
the screen's tests and screenshots are unchanged or deliberately updated.

1. **drishti-core**: formats, theme, API client, keymap, dialogs, resize; every script switches its formatter and its fetch.
2. **drishti-data**: one live connection, Arrow, the pivot source.
3. **drishti-grid, plain tables**: every server-rendered table (`drishti_tables.js` and `drishti_colfilter.js`), then tree rows and
   drills, then the renderer's tables.
4. **drishti-grid, the pivot**: the Pivot screen and the MR cube, the nested pivot panel, BI's matrix and tables, the basket's sheet, the
   playground's grid, the designer's grid.
5. **drishti-chart**: the renderer's charts, `drishti_view.js` and `drishti_charts.js`, BI's visuals, Chart any table, history charts,
   then the maps (`drishti_geomap.js`); Vega-Lite visuals translated to the spec and Vega deleted (O1).
6. **drishti-inputs**: controls, forms, pickers, the code and formula editors (Build, Studio, the editors, BI).
7. **drishti-insight**: About and Explain, search, approvals, workbook export.
8. **drishti-renderer 2.0.0**: drawn with libraries 1 to 5; `behaviours: 'console'` from libraries.
9. **drishti-risk**: the VaR explorer's and exposure explorer's charts, backtesting, the stress window, the scenario matrix, FRTB-SA,
   SIMM and the MR lens; moved into the banking plugin.
10. **The allow list empty**; the guard strict; the inventory regenerated into section 2.

## 13. Risks

| Risk | Mitigation |
|---|---|
| The grid is the largest new code and the most visible | built first behind the gallery with the Pivot screen as its first user; parity tests against the current pivot's answers and screenshots |
| Performance at millions of rows | virtual rows and columns from the first commit; a gallery page with 1,000,000 rows and a frame-time budget in the browser suite |
| An adapter that looks like a clone | clean-room rule (public documentation only), Drishti's look, our own names in code; reviewed before release |
| Moving every screen is long | ordered by reuse (tables first), one branch per step, the allow list as the progress bar; no screen keeps old code past its step |
| Two charting engines (Vega and ECharts) | Vega retired in step 5 (O1); a visual outside the translatable subset is listed and redrawn |
| Licensing leaks (drishti-risk reachable without a licence) | served by the plugin behind the entitlement and licence check; a guard |
| Outside users pin old versions | immutable folders, a release train, a deprecation window of one release |

## 14. Decisions of the product owner (2026-11-07)

| # | Question | Decided |
|---|---|---|
| O1 | **Vega-Lite's future** | **Retire Vega**: Vega-Lite visuals are translated to the drishti-chart spec (the common subset: marks, encodings, facets) and Python visuals return a drishti-chart spec; visuals outside the subset are listed and redrawn once; Vega, its 845 KB and the expression interpreter are deleted |
| O2 | **The grid on a phone** | grids scroll across with the first column pinned; a pivot collapses to one value column with a measure switcher; cards (one per row, the first column as its title) only where a Sutra asks for them |
| O3 | Where libraries 1 to 5 may be used | inside the product owner's applications now; any customer application with the licensing work |
| O4 | The standalone Excel export | a small writer in drishti-grid (values, outline, formats); no new dependency |
| O5 | The AG adapter's scope in version 1 | pivot, row grouping, the server-side row model and tree data (section 6) |
| O6 | Mock-ups g2, g3, g4 | **both options of each are built as modes of one component, A the default**: the chart card `mode: 'card'` (g2 A) or `'studio'` (g2 B, the kind rail and the spec beside the chart); the map `mode: 'ranking'` (g3 A) or `'table'` (g3 B, the floating legend, the inset and the table below); the gallery `mode: 'playground'` (g4 A) or `'docs'` (g4 B, the Examples, API, Accessibility and Changelog tabs) |
| O7 | Names | "Drishti Elements" is the family; Elements 2.x is part of drishti-renderer |

g1 was decided separately: the grid's three layouts (section 5.1).

## 15. Build checklist

- [ ] The domain-module platform's five deliverables (section 8) in place
- [ ] `static/elements/` layout, integrity records, release train, import map
- [ ] drishti-core: formats with the server's rounding, tokens and densities, API client, events, keymap, dialogs, menus, resize; its gallery pages
- [ ] drishti-data: sources (local, REST, Drishti pivot and rows), Arrow, live
- [ ] drishti-grid: virtual rows and columns, columns and menus, ranges and the status bar, filters, tree, editing, export, conditional formats, accessibility
- [x] drishti-grid pivot: the three layouts `wells`, `panel` and `terminal` (VaR screens default to `terminal`, others to `wells`), aggregation pickers, value filters, collapsible pivot result columns, totals, server-side model with the request of 5.3, non-additive totals from the server
- [ ] The AG Grid-style adapter with the table of section 6 as code, and its unknown-option report
- [x] drishti-chart: the spec, the general kinds, the data-table alternative, export, Explain; the chart card's modes `card` (default) and `studio` (g2 A and B); maps with per-country loading and the modes `ranking` (default) and `table` (g3 A and B); drishti-risk's four kinds (8.2)
- [ ] Vega retired (O1): Vega-Lite visuals translated to the spec (the translator is built, 8.2), Python visuals return a spec, the vendored Vega deleted
- [x] The phone behaviour of the grid and the pivot (O2); the standalone Excel writer (O4)
- [ ] drishti-inputs, drishti-insight, drishti-renderer 2.0.0, drishti-risk (in the banking plugin, licensed)
- [ ] The gallery in both modes, `playground` (default) and `docs` (g4 A and B), every example a test in both themes and three densities, axe clean
- [ ] Documentation per library (section 9): guide, generated API reference with `--check`, tutorial, gallery, the AG Grid migration guide, theming and accessibility guides, changelogs, versioning policy; Help entries
- [ ] The guards of section 11, the allow list
- [ ] Every screen moved (section 12) and the old code deleted; the allow list empty
- [ ] `tools/elements/inventory.py` regenerates section 2's figures; README and CHANGELOG updated; two drills
