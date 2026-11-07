/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* The AG Grid-style adapter's map (ELEMENTS.md section 6): the one home of what the adapter takes and what it becomes. AG_MAPPING is the
   table the gallery, ELEMENTS.md and the migration guide show (tools/docs/elements_api.py reads it; the guide's table is generated from
   it); the option lists below are what the adapter does at run time with each name (a test keeps the two in step); the filter models are
   translated both ways. Clean room: written from AG Grid's public documentation of option and model shapes only; no AG Grid code, types,
   styling or class names. Drishti never uses AG Grid: an application written for its option shapes is given a drishti-grid. */

function row(ag, state, drishti) { return Object.freeze({ ag: ag, state: state, drishti: drishti }); }

/** The adapter's mapping, one row per group of AG Grid Enterprise options (public API shapes): its state (mapped, partial, unsupported) and its drishti-grid form. */
export const AG_MAPPING = Object.freeze([
  row('`columnDefs`: `field`, `colId`, `headerName`, `headerTooltip`, `children` (column groups)', 'mapped', 'the column definition: `field`, `id`, `header`, `description`; a group\'s heading is `group` (nested groups joined with ›)'),
  row('`defaultColDef`, `columnTypes`, colDef `type` (`numericColumn`, your own types), `cellDataType`', 'mapped', 'merged under each column; `numericColumn` or a number in the first row makes `type: \'number\'`'),
  row('`width`, `minWidth`, `maxWidth`, `resizable`, `hide`, `pinned`, `sortable`, `sort`, `sortIndex`', 'mapped', '`width`, `minWidth`, `maxWidth`, `resizable`, `hidden`, `pinned`, `sortable`, the sort model'),
  row('`flex`', 'partial', 'a width shared from the element\'s width when the grid is drawn (and by `sizeColumnsToFit`); it does not follow later resizes'),
  row('`filter` (`true`, the text, number, date, set and multi filters), `filterModel` shapes', 'mapped', '`filterable` and the shared filter model (text, number, date, set, blank); a filter with two conditions keeps the first and says so'),
  row('`valueGetter`, `valueFormatter`, `comparator` (functions)', 'mapped', '`valueGetter(row)`, `format(value, row)`, `comparator(a, b)`; string expressions are refused with a warning'),
  row('`cellClass`, `cellClassRules`, `cellStyle` (functions or values)', 'partial', '`cellClass` and `cellStyle` of the column: class names of letters and digits, colour, background, font, alignment only'),
  row('`editable`', 'mapped', '`editable` (a function becomes `true`, with a warning); drishti-grid\'s editors by type'),
  row('`pivotMode: true`', 'mapped', 'the grid starts as a pivot (`pivot` wells)'),
  row('colDef `rowGroup`, `rowGroupIndex`, `enableRowGroup`', 'mapped', '`pivot.rows` (order) or grouped rows when not a pivot; the field allowed in Rows'),
  row('colDef `pivot`, `pivotIndex`, `enablePivot`', 'mapped', '`pivot.columns` (order); the field allowed in Columns'),
  row('colDef `aggFunc` (`sum`, `min`, `max`, `count`, `avg`, `first`, `last`, a function, a name of `aggFuncs`), `enableValue`, `defaultAggFunc`', 'mapped', '`pivot.values[].aggFunc`; a function or an `aggFuncs` name is registered (`registerAggFunc`); `\'drishti:server\'` (or `\'drishti:server:var(99, 1d)\'`) marks a non-additive measure asked of the server'),
  row('`allowedAggFuncs`', 'partial', 'drishti-grid\'s aggregation picker offers its own functions (`VALUE_FUNCS`)'),
  row('`aggFuncs` (custom functions)', 'mapped', '`registerAggFunc(name, fn)`: the function is given the members\' values of each cell and total'),
  row('`groupDisplayType: \'singleColumn\'`, `autoGroupColumnDef` (`headerName`, `width`, `minWidth`, `pinned`)', 'mapped', 'a group column the adapter adds (`system`), headed by `headerName`'),
  row('`groupDisplayType: \'multipleColumns\'` or `\'groupRows\'`', 'partial', 'drawn as one group column'),
  row('`groupDefaultExpanded`, `pivotDefaultExpanded`', 'mapped', '`groupDefaultExpanded`; the pivot\'s open levels (`expandToLevel`)'),
  row('`grandTotalRow` (`top`, `bottom`; `pinnedTop`, `pinnedBottom` drawn unpinned), `groupIncludeTotalFooter`', 'mapped', 'the pivot\'s `grandTotalRow` (`top`, `bottom`, `none`)'),
  row('`groupTotalRow`, `groupIncludeFooter`', 'mapped', 'the pivot\'s subtotals: `bottom` below the members, else on the group row (`subtotals: \'above\'`)'),
  row('`pivotRowTotals`, `pivotColumnGroupTotals`', 'mapped', '`grandColumn`, `columnGroupTotals` (drawn after the members; `before` is drawn after, with a warning)'),
  row('`suppressAggFuncInHeader`', 'mapped', 'the value\'s label is its column\'s heading alone'),
  row('`sideBar` (columns and filters tool panels), `rowGroupPanelShow`, `pivotPanelShow`', 'mapped', '`layout: \'panel\'` (g1 B); the panels without a `sideBar` are the `wells` layout\'s ribbon'),
  row('`rowModelType: \'serverSide\'`, `serverSideDatasource.getRows(params)`, `cacheBlockSize`', 'mapped', 'drishti-grid\'s server-side row model (`ServerRowModel`) over the host\'s function; `blockSize`'),
  row('`params.request`: `startRow`, `endRow`, `rowGroupCols`, `valueCols`, `pivotCols`, `pivotMode`, `groupKeys`, `filterModel`, `sortModel`', 'mapped', 'translated from the block request (columns as `{id, displayName, field, aggFunc}`, the filter model in AG\'s shape)'),
  row('`params.success({ rowData, rowCount, pivotResultFields })`, `params.fail()`, `successCallback`, `failCallback`', 'mapped', 'the block answer; in pivot mode the rows and `pivotResultFields` become the pivot\'s answer (rows, result columns, cells)'),
  row('`serverSidePivotResultFieldSeparator`', 'mapped', 'the separator of a pivot result field\'s keys (default `_`)'),
  row('`refreshServerSide({ route, purge })`, `purge`', 'mapped', '`ServerRowModel.refresh({keys, purge})`; a server pivot is asked again'),
  row('`rowModelType: \'infinite\'` with `datasource.getRows`', 'partial', 'the server-side row model without groups'),
  row('`treeData`, `getDataPath`', 'mapped', '`treeData`, `getDataPath` (client-side rows)'),
  row('`isServerSideGroup`, `getServerSideGroupKey` (server-side tree data)', 'unsupported', 'tree data on client-side rows; server-side tree data is not built yet'),
  row('`rowData`, `getRowId`, `rowSelection` (`singleRow`, `multiRow`, `single`, `multiple`, `checkboxes`), `quickFilterText`', 'mapped', '`rows`, `getRowId`, `rowSelection` and `checkboxes`, the quick filter'),
  row('`rowHeight`', 'partial', 'the nearest density: up to 24 px terminal, up to 31 px compact, else comfortable'),
  row('`domLayout: \'autoHeight\'`', 'partial', 'the element\'s height follows the row count when drawn; give large data a fixed height'),
  row('`animateRows`, `enableCellChangeFlash`, `cellSelection`, `enableRangeSelection`, `statusBar`, `suppressMenuHide`', 'no-op', 'nothing to set: drishti-grid flashes live changes, has cell ranges and its status bar always'),
  row('Grid API: `setGridOption`, `updateGridOptions`, `getGridOption`, `applyTransaction(Async)`, `getSelectedRows`, `forEachNode`, `getDisplayedRowAtIndex`', 'mapped', 'the same calls on the adapter\'s handle, forwarded to the grid\'s API'),
  row('Grid API: `exportDataAsCsv`, `exportDataAsExcel`, `getDataAsCsv`, `setFilterModel`, `getFilterModel`, `applyColumnState`, `getColumnState`', 'mapped', 'CSV and the standalone Excel writer, the filter model, the column state'),
  row('Grid API: `sizeColumnsToFit`, `autoSizeAllColumns`, `expandAll`, `collapseAll`, `refreshCells`, `setRowGroupColumns`, `setPivotColumns`, `setValueColumns`, `getPivotResultColumns`', 'mapped', 'the grid\'s widths, autosize, open levels, redraw and wells'),
  row('Events: `onGridReady`, `onFirstDataRendered`, `onCellClicked`, `onSelectionChanged`, `onSortChanged`, `onFilterChanged`, `onColumnPivotChanged` and the rest of AG_EVENTS', 'mapped', 'drishti-grid\'s typed events (GRID_EVENTS), with AG-shaped parameters'),
  row('`processPivotResultColDef`, `processPivotResultColGroupDef`', 'unsupported', 'the pivot\'s columns are drawn by drishti-grid; format a measure with its column\'s `valueFormatter`'),
  row('`getContextMenuItems`, `getMainMenuItems`, `cellRenderer`, `cellEditor`, `headerComponent`, overlays', 'unsupported', 'drishti-grid\'s own menus, cells and editors (by type)'),
  row('`getRowStyle`, `getRowClass`, `rowClassRules`, `headerHeight`, `localeText`, `tooltipField`, `suppressRowClickSelection`', 'unsupported', 'the column\'s `cellStyle` and `cellClass`, the density, drishti-grid\'s words, `headerTooltip`, `rowSelection.enableClickSelection`'),
  row('`pagination`, `paginationPageSize`', 'unsupported', 'virtual scrolling: every row is reachable without pages'),
  row('`enableCharts` (integrated charts), `masterDetail`, `rowDragManaged`, `theme`, `rowModelType: \'viewport\'`', 'unsupported', 'drishti-chart on a range; Drishti\'s theme tokens; the rest is not covered in version 1')
]);

/** What version 1 leaves out, in words (ELEMENTS.md section 6, the migration guide). */
export const AG_NOT_COVERED = 'integrated charts (use drishti-chart on a range), master-detail rows, sparkline cell renderers, row dragging between grids, ' +
  'AG Grid\'s theming API and CSS classes (Drishti\'s theme applies), the viewport row model, pagination, custom cell renderers, editors and ' +
  'header components, server-side tree data.';

/** Grid options the adapter takes (translated or carried): any other name is reported. */
export const AG_GRID_OPTIONS = Object.freeze(['columnDefs', 'defaultColDef', 'columnTypes', 'rowData', 'getRowId', 'rowSelection', 'animateRows', 'domLayout',
  'pivotMode', 'groupDefaultExpanded', 'pivotDefaultExpanded', 'groupTotalRow', 'groupIncludeFooter', 'grandTotalRow', 'groupIncludeTotalFooter',
  'pivotRowTotals', 'pivotColumnGroupTotals', 'autoGroupColumnDef', 'groupDisplayType', 'suppressAggFuncInHeader', 'aggFuncs', 'sideBar',
  'rowGroupPanelShow', 'pivotPanelShow', 'rowModelType', 'serverSideDatasource', 'datasource', 'cacheBlockSize', 'serverSidePivotResultFieldSeparator',
  'treeData', 'getDataPath', 'quickFilterText', 'context', 'rowHeight', 'enableCellChangeFlash', 'cellSelection', 'enableRangeSelection', 'statusBar',
  'suppressMenuHide', 'drishti']);

/** Column definition keys the adapter takes. */
export const AG_COLUMN_KEYS = Object.freeze(['field', 'colId', 'headerName', 'headerTooltip', 'children', 'groupId', 'type', 'cellDataType', 'width',
  'minWidth', 'maxWidth', 'flex', 'resizable', 'hide', 'pinned', 'sortable', 'sort', 'sortIndex', 'filter', 'valueGetter', 'valueFormatter',
  'comparator', 'cellClass', 'cellClassRules', 'cellStyle', 'editable', 'rowGroup', 'rowGroupIndex', 'enableRowGroup', 'pivot', 'pivotIndex',
  'enablePivot', 'aggFunc', 'enableValue', 'defaultAggFunc', 'allowedAggFuncs', 'initialSort', 'initialHide', 'initialWidth', 'initialPinned',
  'initialRowGroup', 'initialPivot', 'initialAggFunc', 'headerClass', 'suppressHeaderMenuButton', 'menuTabs', 'suppressMovable', 'lockPosition',
  'lockPinned', 'lockVisible', 'filterParams']);

/** Options the adapter does not cover, each with its drishti-grid way (a warning names both). */
export const AG_UNSUPPORTED = Object.freeze({
  processPivotResultColDef: 'the pivot\'s columns are drishti-grid\'s; format a measure with its column\'s valueFormatter',
  processPivotResultColGroupDef: 'the pivot\'s column groups are drishti-grid\'s (collapsible by +/−)',
  getContextMenuItems: 'drishti-grid\'s context menu (Explain, copy, export, chart, drill, group, pin)',
  getMainMenuItems: 'drishti-grid\'s column menu',
  pagination: 'virtual scrolling: every row is reachable without pages',
  paginationPageSize: 'virtual scrolling: every row is reachable without pages',
  enableCharts: 'drishti-chart on a range (the context menu\'s Chart the range)',
  masterDetail: 'not covered in version 1: a second grid beside the first, or drillThrough',
  detailCellRendererParams: 'not covered in version 1',
  rowDragManaged: 'not covered in version 1',
  theme: 'Drishti\'s theme tokens (drishti_tokens.css) and densities',
  isServerSideGroup: 'tree data on client-side rows (treeData, getDataPath); server-side tree data is not built yet',
  getServerSideGroupKey: 'tree data on client-side rows (treeData, getDataPath); server-side tree data is not built yet',
  loadingOverlayComponent: 'drishti-grid\'s loading placeholders',
  noRowsOverlayComponent: 'drishti-grid\'s empty text',
  localeText: 'drishti-grid\'s words',
  headerHeight: 'the density sets every height',
  getRowStyle: 'the column\'s cellStyle',
  getRowClass: 'the column\'s cellClass',
  rowClassRules: 'the column\'s cellClass',
  suppressRowClickSelection: 'rowSelection.enableClickSelection',
  allowedAggFuncs: 'drishti-grid\'s aggregation picker (VALUE_FUNCS)',
  cellRenderer: 'drishti-grid draws its cells (format with valueFormatter, colour with cellClass or conditional formats)',
  cellRendererParams: 'drishti-grid draws its cells',
  cellEditor: 'drishti-grid\'s editors by type (text, number, date, boolean)',
  cellEditorParams: 'drishti-grid\'s editors by type',
  headerComponent: 'drishti-grid\'s headings',
  tooltipField: 'the column\'s description (headerTooltip)',
  lockPosition: 'columns move by drag; there is no lock',
  lockPinned: 'pinned stays as set; there is no lock',
  lockVisible: 'columns hide from the menu; there is no lock',
  filterParams: 'drishti-grid\'s filter by the column\'s type'
});

/** Options that need nothing in drishti-grid (said once, as information). */
export const AG_NO_OP = Object.freeze(['animateRows', 'enableCellChangeFlash', 'cellSelection', 'enableRangeSelection', 'statusBar', 'suppressMenuHide',
  'headerClass', 'suppressHeaderMenuButton', 'menuTabs', 'suppressMovable', 'groupId']);

/** The AG Grid events the adapter raises (onXxx options and addEventListener), each from drishti-grid's typed events. */
export const AG_EVENTS = Object.freeze(['gridReady', 'firstDataRendered', 'cellClicked', 'cellDoubleClicked', 'rowClicked', 'rowDoubleClicked',
  'cellValueChanged', 'cellFocused', 'selectionChanged', 'cellSelectionChanged', 'rangeSelectionChanged', 'sortChanged', 'filterChanged',
  'modelUpdated', 'rowDataUpdated', 'columnPivotChanged', 'columnRowGroupChanged', 'columnValueChanged', 'columnPivotModeChanged', 'rowGroupOpened',
  'columnMoved', 'columnResized', 'columnPinned', 'columnVisible', 'cellEditingStarted', 'cellEditingStopped', 'gridPreDestroyed']);

const NUM_OPS = { equals: 'equals', notEqual: 'notEquals', lessThan: 'lt', lessThanOrEqual: 'lte', greaterThan: 'gt', greaterThanOrEqual: 'gte', inRange: 'between' };
const TEXT_OPS = { contains: 'contains', notContains: 'notContains', equals: 'equals', notEqual: 'notEquals', startsWith: 'startsWith', endsWith: 'endsWith' };
const flip = (o) => { const r = {}; Object.keys(o).forEach((k) => { r[o[k]] = k; }); return r; };
const NUM_BACK = flip(NUM_OPS), TEXT_BACK = flip(TEXT_OPS);
const day = (v) => (v === null || v === undefined ? v : String(v).slice(0, 10));

/**
 * One column's AG Grid-shaped filter as drishti-grid's ColumnFilter (text, number, date, set; blank). A filter with two conditions keeps
 * the first; what cannot be kept is said through warn.
 * @param {Object} f the AG-shaped filter ({filterType, type, filter, filterTo, dateFrom, dateTo, values, operator, conditions})
 * @param {string} [colType] the column's type, when the filter does not say its kind
 * @param {function(string): void} [warn]
 * @returns {?Object} the drishti filter, or null when there is none
 */
export function agFilterToDrishti(f, colType, warn) {
  if (!f) { return null; }
  const say = warn || (() => {});
  if (f.filterType === 'multi') { const one = (f.filterModels || []).find(Boolean); return one ? agFilterToDrishti(one, colType, say) : null; }
  if (f.conditions || f.condition1) {
    const list = f.conditions || [f.condition1, f.condition2].filter(Boolean);
    if (list.length > 1) { say('a filter with ' + list.length + ' conditions (' + (f.operator || 'AND') + ') keeps its first: drishti-grid has one condition a column (or between)'); }
    return agFilterToDrishti(Object.assign({ filterType: f.filterType }, list[0]), colType, say);
  }
  const kind = f.filterType || (colType === 'number' ? 'number' : colType === 'date' ? 'date' : f.values ? 'set' : 'text');
  if (f.type === 'blank' || f.type === 'notBlank') { return { type: 'blank', op: f.type }; }
  if (kind === 'set') { return { type: 'set', values: (f.values || []).slice() }; }
  if (kind === 'number' || kind === 'date') {
    const op = NUM_OPS[f.type || 'equals'];
    if (!op) { say('the ' + kind + ' filter type ' + f.type + ' is not drishti-grid\'s (equals, notEqual, lessThan(OrEqual), greaterThan(OrEqual), inRange)'); return null; }
    const a = kind === 'date' ? day(f.dateFrom) : f.filter, b = kind === 'date' ? day(f.dateTo) : f.filterTo;
    return op === 'between' ? { type: kind, op: op, value: a, valueTo: b } : { type: kind, op: op, value: a };
  }
  const op = TEXT_OPS[f.type || 'contains'];
  if (!op) { say('the text filter type ' + f.type + ' is not drishti-grid\'s (contains, notContains, equals, notEqual, startsWith, endsWith)'); return null; }
  return { type: 'text', op: op, value: f.filter };
}

/**
 * One drishti ColumnFilter in AG Grid's shape (what getFilterModel and a server-side request carry).
 * @param {Object} f
 * @returns {?Object}
 */
export function drishtiFilterToAg(f) {
  if (!f) { return null; }
  if (f.type === 'blank') { return { filterType: 'text', type: f.op === 'notBlank' ? 'notBlank' : 'blank' }; }
  if (f.type === 'set') { return f.values ? { filterType: 'set', values: f.values.slice() } : { filterType: 'set', values: null, excluded: (f.excluded || []).slice() }; }
  if (f.type === 'number') { return Object.assign({ filterType: 'number', type: NUM_BACK[f.op || 'equals'], filter: f.value }, f.op === 'between' ? { filterTo: f.valueTo } : {}); }
  if (f.type === 'date') { return Object.assign({ filterType: 'date', type: NUM_BACK[f.op || 'equals'], dateFrom: f.value, dateTo: f.op === 'between' ? f.valueTo : null }); }
  return { filterType: 'text', type: TEXT_BACK[f.op || 'contains'], filter: f.value };
}

/**
 * A whole filter model translated: AG Grid's shape to drishti-grid's (toDrishti true) or back.
 * @param {Object<string, Object>} model column id to a filter
 * @param {boolean} toDrishti
 * @param {function(string): ?string} [typeOf] a column's type by id
 * @param {function(string): void} [warn]
 * @returns {Object<string, Object>}
 */
export function translateFilterModel(model, toDrishti, typeOf, warn) {
  const out = {};
  Object.keys(model || {}).forEach((k) => {
    const say = warn ? (w) => warn('filter on ' + k + ': ' + w) : null;
    const f = toDrishti ? agFilterToDrishti(model[k], typeOf ? typeOf(k) : null, say) : drishtiFilterToAg(model[k]);
    if (f) { out[k] = f; }
  });
  return out;
}
