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

# Moving an AG Grid Enterprise pivot app to drishti-grid

An application written for AG Grid Enterprise's pivoting, row grouping, server-side row model or tree data moves to drishti-grid by
changing the call that makes its grid. drishti-grid's **AG Grid-style adapter** takes the application's grid options in AG Grid's option
shapes, gives it a drishti-grid, and answers the grid API calls and events the application already makes. The grid the person sees is
drishti-grid: its own look, layouts, densities, keyboard, Explain and non-additive totals.

**Drishti never uses AG Grid.** The adapter is a translation layer inside drishti-grid, written from AG Grid's public documentation of
its option and API shapes only: no AG Grid code, package, type files, CSS, class names or look. Your application stops loading AG Grid.

Contents: [1. Step by step](#1-step-by-step) · [2. Before and after](#2-before-and-after) · [3. What maps](#3-what-maps) ·
[4. What differs](#4-what-differs) · [5. The server-side datasource](#5-the-server-side-datasource) ·
[6. Options not covered](#6-options-not-covered) · [7. Checking a migration](#7-checking-a-migration)

The adapter's API (`createGridFromAgOptions`, `AgGridApi`, the filter translation, the server helpers) is in the generated
[ELEMENTS_API.md](ELEMENTS_API.md#drishti-grid); its design is [ELEMENTS.md section 6](../architecture/ELEMENTS.md#6-the-ag-grid-style-adapter).

## 1. Step by step

1. **Load drishti-grid instead of AG Grid.** Remove the AG Grid package, its script and its style sheets. Add Drishti's theme tokens,
   the grid's style sheet and the two bundles (no npm, no CDN: the files are served from Drishti's `static/elements/` or copied with
   your application):

   ```html
   <link rel="stylesheet" href="/static/css/drishti_tokens.css">
   <link rel="stylesheet" href="/static/elements/drishti-inputs/drishti_inputs.css">
   <link rel="stylesheet" href="/static/elements/drishti-grid/drishti_grid.css">
   <script src="/static/elements/drishti-core/drishti_core.js"></script>
   <script src="/static/elements/drishti-inputs/drishti_inputs.js"></script>   <!-- the grid's choices are drishti-inputs' Select -->
   <script src="/static/elements/drishti-grid/drishti_grid.js"></script>
   ```

   or, as ES modules: `import { createGridFromAgOptions } from '/static/elements/drishti-grid/src/drishti_index.js';`

2. **Change the one call that makes the grid.** Keep `gridOptions` as it is:

   ```js
   // before: const api = agGrid.createGrid(document.getElementById('grid'), gridOptions);
   const api = Drishti.elements.grid.createGridFromAgOptions(document.getElementById('grid'), gridOptions);
   ```

   `api` answers the calls your code makes (`api.setGridOption`, `api.applyTransaction`, `api.exportDataAsExcel` ...). drishti-grid's own
   objects are `api.grid` (the drawn grid) and `api.model` (its engine), for anything beyond AG Grid's shapes.

3. **Read the report.** Every option the adapter does not take in full is named once in the browser console with drishti-grid's way,
   and `api.reportUnsupported()` lists them (`{option, state, words}`; state `partial`, `unsupported`, `no-op` or `unknown`). Work
   through the list; nothing is ignored silently.

4. **Give the element a height.** drishti-grid scrolls its rows virtually inside its element (a million rows cost nothing), so the
   element needs a height, as AG Grid's did. `domLayout: 'autoHeight'` sizes it from the row count when the grid is drawn.

5. **Mark non-additive measures.** A measure whose total is not the sum of its rows (VaR, expected shortfall, a ratio, a distinct
   count) takes `aggFunc: 'drishti:server'` (or `'drishti:server:var(99, 1d)'` to name the server's measure). Its totals are never
   added up in the browser: with client-side rows give `drishti: { serverPivot }`, with the server-side row model your datasource
   answers them.

6. **Choose the look** with the `drishti` option, the one key the adapter adds: `drishti: { layout: 'panel' | 'wells' | 'terminal',
   density: 'comfortable' | 'compact' | 'terminal', title, explain, serverPivot, download, conditionalFormats }`. Any drishti-grid option
   may go there (ELEMENTS_API.md, `GridOptions` and `GridViewOptions`). A `sideBar` gives the `panel` layout, the nearest to AG Grid's
   tool panel.

## 2. Before and after

**A client-side pivot.** The options do not change; only the call does.

```js
const gridOptions = {
  columnDefs: [
    { field: 'desk', rowGroup: true, enableRowGroup: true },
    { field: 'book', rowGroup: true, rowGroupIndex: 1, enableRowGroup: true },
    { field: 'region', pivot: true, enablePivot: true },
    { field: 'mtm', headerName: 'MTM', aggFunc: 'sum', enableValue: true, valueFormatter: (p) => fmt(p.value) },
    { field: 'var', headerName: 'VaR 99% 1d', aggFunc: 'drishti:server', enableValue: true },
  ],
  rowData: trades,
  getRowId: (p) => p.data.id,
  pivotMode: true,
  groupDefaultExpanded: 1,
  grandTotalRow: 'bottom',
  pivotRowTotals: 'after',
  sideBar: true,
  onCellClicked: (e) => show(e.data, e.column.colId),
  drishti: { serverPivot: (body, signal, rows) => fetch('/api/pivot/query', { method: 'POST', body: JSON.stringify(body), signal }).then((r) => r.json()) },
};
// before: agGrid.createGrid(el, gridOptions)
const api = Drishti.elements.grid.createGridFromAgOptions(el, gridOptions);
```

**The server-side row model.** The datasource is the one your server already answers.

```js
const gridOptions = {
  rowModelType: 'serverSide',
  serverSideDatasource: {
    getRows: (params) => fetch('/trades/rows', { method: 'POST', body: JSON.stringify(params.request) })
      .then((r) => r.json())
      .then((a) => params.success({ rowData: a.rows, rowCount: a.lastRow, pivotResultFields: a.pivotFields }))
      .catch(() => params.fail()),
  },
  cacheBlockSize: 200,
  columnDefs: [{ field: 'desk', rowGroup: true }, { field: 'region', pivot: true }, { field: 'mtm', aggFunc: 'sum' }],
  pivotMode: true,
};
const api = Drishti.elements.grid.createGridFromAgOptions(el, gridOptions);   // was agGrid.createGrid(el, gridOptions)
api.refreshServerSide({ purge: true });                                          // as before
```

**Tree data.**

```js
const api = Drishti.elements.grid.createGridFromAgOptions(el, {
  treeData: true,
  getDataPath: (data) => data.orgPath,
  autoGroupColumnDef: { headerName: 'Organisation' },
  groupDefaultExpanded: -1,
  columnDefs: [{ field: 'headcount', aggFunc: 'sum' }],
  rowData: people,
});
```

## 3. What maps

The table is generated from the adapter's own mapping (`AG_MAPPING` in `drishti_grid-ag-map.js`), so it is what the code does. States:
**mapped** (same meaning), **partial** (mapped, with a difference the console names), **unsupported** (named in the console, with
drishti-grid's way), **no-op** (nothing to set: drishti-grid does it always).

<!-- ag-mapping: generated by tools/docs/elements_api.py from drishti_grid-ag-map.js; do not edit -->

| AG Grid Enterprise option (public API shape) | State | drishti-grid |
|---|---|---|
| `columnDefs`: `field`, `colId`, `headerName`, `headerTooltip`, `children` (column groups) | mapped | the column definition: `field`, `id`, `header`, `description`; a group's heading is `group` (nested groups joined with ›) |
| `defaultColDef`, `columnTypes`, colDef `type` (`numericColumn`, your own types), `cellDataType` | mapped | merged under each column; `numericColumn` or a number in the first row makes `type: 'number'` |
| `width`, `minWidth`, `maxWidth`, `resizable`, `hide`, `pinned`, `sortable`, `sort`, `sortIndex` | mapped | `width`, `minWidth`, `maxWidth`, `resizable`, `hidden`, `pinned`, `sortable`, the sort model |
| `flex` | partial | a width shared from the element's width when the grid is drawn (and by `sizeColumnsToFit`); it does not follow later resizes |
| `filter` (`true`, the text, number, date, set and multi filters), `filterModel` shapes | mapped | `filterable` and the shared filter model (text, number, date, set, blank); a filter with two conditions keeps the first and says so |
| `valueGetter`, `valueFormatter`, `comparator` (functions) | mapped | `valueGetter(row)`, `format(value, row)`, `comparator(a, b)`; string expressions are refused with a warning |
| `cellClass`, `cellClassRules`, `cellStyle` (functions or values) | partial | `cellClass` and `cellStyle` of the column: class names of letters and digits, colour, background, font, alignment only |
| `editable` | mapped | `editable` (a function becomes `true`, with a warning); drishti-grid's editors by type |
| `pivotMode: true` | mapped | the grid starts as a pivot (`pivot` wells) |
| colDef `rowGroup`, `rowGroupIndex`, `enableRowGroup` | mapped | `pivot.rows` (order) or grouped rows when not a pivot; the field allowed in Rows |
| colDef `pivot`, `pivotIndex`, `enablePivot` | mapped | `pivot.columns` (order); the field allowed in Columns |
| colDef `aggFunc` (`sum`, `min`, `max`, `count`, `avg`, `first`, `last`, a function, a name of `aggFuncs`), `enableValue`, `defaultAggFunc` | mapped | `pivot.values[].aggFunc`; a function or an `aggFuncs` name is registered (`registerAggFunc`); `'drishti:server'` (or `'drishti:server:var(99, 1d)'`) marks a non-additive measure asked of the server |
| `allowedAggFuncs` | partial | drishti-grid's aggregation picker offers its own functions (`VALUE_FUNCS`) |
| `aggFuncs` (custom functions) | mapped | `registerAggFunc(name, fn)`: the function is given the members' values of each cell and total |
| `groupDisplayType: 'singleColumn'`, `autoGroupColumnDef` (`headerName`, `width`, `minWidth`, `pinned`) | mapped | a group column the adapter adds (`system`), headed by `headerName` |
| `groupDisplayType: 'multipleColumns'` or `'groupRows'` | partial | drawn as one group column |
| `groupDefaultExpanded`, `pivotDefaultExpanded` | mapped | `groupDefaultExpanded`; the pivot's open levels (`expandToLevel`) |
| `grandTotalRow` (`top`, `bottom`; `pinnedTop`, `pinnedBottom` drawn unpinned), `groupIncludeTotalFooter` | mapped | the pivot's `grandTotalRow` (`top`, `bottom`, `none`) |
| `groupTotalRow`, `groupIncludeFooter` | mapped | the pivot's subtotals: `bottom` below the members, else on the group row (`subtotals: 'above'`) |
| `pivotRowTotals`, `pivotColumnGroupTotals` | mapped | `grandColumn`, `columnGroupTotals` (drawn after the members; `before` is drawn after, with a warning) |
| `suppressAggFuncInHeader` | mapped | the value's label is its column's heading alone |
| `sideBar` (columns and filters tool panels), `rowGroupPanelShow`, `pivotPanelShow` | mapped | `layout: 'panel'` (g1 B); the panels without a `sideBar` are the `wells` layout's ribbon |
| `rowModelType: 'serverSide'`, `serverSideDatasource.getRows(params)`, `cacheBlockSize` | mapped | drishti-grid's server-side row model (`ServerRowModel`) over the host's function; `blockSize` |
| `params.request`: `startRow`, `endRow`, `rowGroupCols`, `valueCols`, `pivotCols`, `pivotMode`, `groupKeys`, `filterModel`, `sortModel` | mapped | translated from the block request (columns as `{id, displayName, field, aggFunc}`, the filter model in AG's shape) |
| `params.success({ rowData, rowCount, pivotResultFields })`, `params.fail()`, `successCallback`, `failCallback` | mapped | the block answer; in pivot mode the rows and `pivotResultFields` become the pivot's answer (rows, result columns, cells) |
| `serverSidePivotResultFieldSeparator` | mapped | the separator of a pivot result field's keys (default `_`) |
| `refreshServerSide({ route, purge })`, `purge` | mapped | `ServerRowModel.refresh({keys, purge})`; a server pivot is asked again |
| `rowModelType: 'infinite'` with `datasource.getRows` | partial | the server-side row model without groups |
| `treeData`, `getDataPath` | mapped | `treeData`, `getDataPath` (client-side rows) |
| `treeData` with `rowModelType: 'serverSide'`, `isServerSideGroup`, `getServerSideGroupKey` (server-side tree data) | mapped | tree data on the server-side row model: each node's level asked when it opens, `groupKeys` its path |
| `rowData`, `getRowId`, `rowSelection` (`singleRow`, `multiRow`, `single`, `multiple`, `checkboxes`), `quickFilterText` | mapped | `rows`, `getRowId`, `rowSelection` and `checkboxes`, the quick filter |
| `rowHeight` | partial | the nearest density: up to 24 px terminal, up to 31 px compact, else comfortable |
| `domLayout: 'autoHeight'` | partial | the element's height follows the row count when drawn; give large data a fixed height |
| `animateRows`, `enableCellChangeFlash`, `cellSelection`, `enableRangeSelection`, `statusBar`, `suppressMenuHide` | no-op | nothing to set: drishti-grid flashes live changes, has cell ranges and its status bar always |
| Grid API: `setGridOption`, `updateGridOptions`, `getGridOption`, `applyTransaction(Async)`, `getSelectedRows`, `forEachNode`, `getDisplayedRowAtIndex` | mapped | the same calls on the adapter's handle, forwarded to the grid's API |
| Grid API: `exportDataAsCsv`, `exportDataAsExcel`, `getDataAsCsv`, `setFilterModel`, `getFilterModel`, `applyColumnState`, `getColumnState` | mapped | CSV and the standalone Excel writer, the filter model, the column state |
| Grid API: `sizeColumnsToFit`, `autoSizeAllColumns`, `expandAll`, `collapseAll`, `refreshCells`, `setRowGroupColumns`, `setPivotColumns`, `setValueColumns`, `getPivotResultColumns` | mapped | the grid's widths, autosize, open levels, redraw and wells |
| Events: `onGridReady`, `onFirstDataRendered`, `onCellClicked`, `onSelectionChanged`, `onSortChanged`, `onFilterChanged`, `onColumnPivotChanged` and the rest of AG_EVENTS | mapped | drishti-grid's typed events (GRID_EVENTS), with AG-shaped parameters |
| `processPivotResultColDef`, `processPivotResultColGroupDef` | unsupported | the pivot's columns are drawn by drishti-grid; format a measure with its column's `valueFormatter` |
| `getContextMenuItems`, `getMainMenuItems`, `cellRenderer`, `cellEditor`, `headerComponent`, overlays | unsupported | drishti-grid's own menus, cells and editors (by type) |
| `getRowStyle`, `getRowClass`, `rowClassRules`, `headerHeight`, `localeText`, `tooltipField`, `suppressRowClickSelection` | unsupported | the column's `cellStyle` and `cellClass`, the density, drishti-grid's words, `headerTooltip`, `rowSelection.enableClickSelection` |
| `pagination`, `paginationPageSize` | unsupported | virtual scrolling: every row is reachable without pages |
| `enableCharts` (integrated charts), `masterDetail`, `rowDragManaged`, `theme`, `rowModelType: 'viewport'` | unsupported | drishti-chart on a range; Drishti's theme tokens; the rest is not covered in version 1 |

Not covered in version 1: integrated charts (use drishti-chart on a range), master-detail rows, sparkline cell renderers, row dragging between grids, AG Grid's theming API and CSS classes (Drishti's theme applies), the viewport row model, pagination, custom cell renderers, editors and header components.

<!-- /ag-mapping -->

## 4. What differs

The adapter keeps AG Grid's meaning where it can. These are the differences a person or a test will notice:

| Area | AG Grid | drishti-grid through the adapter |
|---|---|---|
| Look | AG Grid's themes | Drishti's theme tokens, three layouts (`wells` by default, `panel` with a `sideBar`, `terminal`) and three densities; light and dark |
| The wells | the row group and pivot panels appear when asked (`rowGroupPanelShow`) | the `wells` layout always shows the four wells above the grid; `drishti: { layout: 'panel' }` puts them in a side panel |
| Non-additive totals | a custom aggregation sums or recomputes in the browser | `aggFunc: 'drishti:server'`: every total is asked of the server and marked ◆; a sum of a range of such cells is refused in the status bar |
| Custom aggregations | `aggFuncs` and `aggFunc` functions get `params.values` | the same, registered for the grid (`registerAggFunc`); every cell and total is made from its own members' values, never from other totals |
| Group order | the data's order unless sorted | the same for grouped rows; a pivot's members are in natural order (T2 before T10) until sorted |
| Pivot totals | no row or column group totals unless asked | the same: `pivotRowTotals` shows the grand total column, `pivotColumnGroupTotals` each open column group's total, both after the members |
| Group footers | `groupTotalRow` adds a footer row | in pivot mode `groupTotalRow: 'bottom'` gives a total row below the members; otherwise the group row carries the figures |
| Headings of values | `sum(MTM)` | `MTM` for a sum, `MTM (average)` for the others; `suppressAggFuncInHeader` keeps the column's heading alone |
| Column flex | follows the grid's width | shares the width once when drawn and on `sizeColumnsToFit()` |
| Row height | any pixel height | the nearest density (22, 28 or 36 px) |
| Filters | a filter component per column | drishti-grid's filter by the column's type (text, number, date, set, blank), one condition a column (or between); the model is translated both ways |
| Row nodes | the full RowNode | `{id, data, rowIndex, group, key, level, expanded, isSelected(), setSelected()}` |
| Events | AG Grid's event objects | the AG names with `type`, `api`, `context` and the fields applications read (`data`, `node`, `value`, `column`, `colDef`, `rowIndex`, `oldValue`, `newValue`, `expanded`) |
| Export | CSV and Excel with styles | CSV, and an Excel workbook with values, number formats and outline levels (`exportDataAsExcel`); inside Drishti the server's export adds formulas and provenance |

## 5. The server-side datasource

Your `serverSideDatasource.getRows(params)` is called as before. `params.request` carries `startRow`, `endRow`, `rowGroupCols`,
`valueCols` (each `{id, displayName, field, aggFunc}`, the `aggFunc` as your column definition named it, `'drishti:server'`
included), `pivotCols`, `pivotMode`, `groupKeys`, `filterModel` (AG Grid's shape: `{filterType: 'set', values}`, `{filterType:
'number', type: 'greaterThan', filter}` ...) and `sortModel`; `params.success({rowData, rowCount, pivotResultFields})` and
`params.fail()` answer it (the older `successCallback(rows, lastRow)` and `failCallback()` too). `params.api` is the adapter's handle and
`params.context` your `context`. A group level's rows carry the group column's value and their figures; `childCount` is shown beside
the member.

Rows come in blocks of `cacheBlockSize` (default 100) as they scroll into view; a sort or filter change aborts what is in flight. Open
groups ask their own level (`groupKeys`). `api.refreshServerSide({route, purge})` asks again (one level and those under it, or all).

**Pivot mode.** drishti-grid draws a pivot from one answer per open level, so in pivot mode the adapter asks your datasource for each
level whole (`startRow` 0, `endRow` 100,000): the top level, then each open member's level (`groupKeys`), as drishti-grid's pivot
opens them. Each answer's rows carry the pivot result fields; `pivotResultFields` names them, keys joined by
`serverSidePivotResultFieldSeparator` (default `_`) and the value column's id last (`EMEA_EUR_mtm`). The grand total row
(`grandTotalRow`) is one more request with no `rowGroupCols`; the row totals (`pivotRowTotals`) one more per level with no `pivotCols`.
Every figure, every total included, is your server's: nothing is added up in the browser, so a non-additive measure is right at every
level.

## 6. Options not covered

Every option below is named in the console when an application gives it, with drishti-grid's way, and listed by
`api.reportUnsupported()`. The table of section 3 lists them by state; the ways in short:

- **Integrated charts** (`enableCharts`): select a range and use the context menu's Chart the range (drishti-chart).
- **Master-detail** (`masterDetail`): a second grid beside the first, or `drishti: { drillThrough }` for the rows behind a cell.
- **Cell renderers, editors, header components, overlays**: drishti-grid draws its cells (format with `valueFormatter`, colour with
  `cellClass`, `cellStyle` or conditional formats) and edits by type.
- **Pagination**: virtual scrolling reaches every row without pages.
- **Theming** (`theme`, AG Grid's CSS classes): Drishti's theme tokens and densities.
- **The viewport row model**: the server-side row model.
- **Server-side tree data** (`isServerSideGroup`, `getServerSideGroupKey`): tree data on client-side rows.
- **`processPivotResultColDef`**: format a measure with its column's `valueFormatter`.
- **Context and column menus of your own** (`getContextMenuItems`, `getMainMenuItems`): drishti-grid's menus.

An option the adapter does not know at all is reported as `unknown`. A string expression in `valueGetter`, `valueFormatter` or
`cellClassRules` is refused (give a function).

## 7. Checking a migration

- Open the page with the browser console showing; read every adapter line, then `api.reportUnsupported()`.
- Compare a pivot's figures with the old grid's on the same data: sums, counts, averages, mins, maxes and custom aggregations are
  computed the same way from the same members; a non-additive measure now comes from the server at every total.
- Your event handlers run with the same names; check the fields they read against section 4.
- The gallery (Build → Learn → Elements, `/elements`) shows any grid's options in AG Grid's shapes on its AG Grid adapter tab, and the
  adapter's demo draws a pivot made from them.
