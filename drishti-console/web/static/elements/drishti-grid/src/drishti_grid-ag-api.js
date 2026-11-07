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
/* The AG Grid-style adapter's handle: the grid API calls an application written for AG Grid makes (setGridOption, applyTransaction,
   getSelectedRows, forEachNode, exportDataAsCsv, setFilterModel, applyColumnState, expandAll, refreshServerSide ...), each forwarded to
   drishti-grid's own API (the filters, columns, wells and pivot calls in drishti_grid-ag-columns.js), and the AG-named events raised from
   drishti-grid's typed events (drishti_grid-ag-events.js). With an element
   the handle draws a Grid; without one (Node, tests) it holds the GridModel alone. AgGridApi.grid and .model are drishti-grid's own. */
import { GridModel } from './drishti_grid.js';
import { Grid } from './drishti_grid-view.js';
import { XLSX_TYPE } from './drishti_export.js';
import { unregisterAggFunc } from './drishti_aggregate.js';
import { translateAgOptions, AgReport } from './drishti_grid-ag.js';
import { agDatasource } from './drishti_grid-ag-server.js';
import { AgEvents } from './drishti_grid-ag-events.js';
import { AgColumnsApi } from './drishti_grid-ag-columns.js';

const RUNTIME = ['rowData', 'pivotMode', 'quickFilterText', 'columnDefs', 'groupDefaultExpanded', 'pivotRowTotals', 'pivotColumnGroupTotals', 'grandTotalRow',
  'groupTotalRow', 'sideBar', 'serverSideDatasource', 'domLayout', 'context'];

/**
 * The handle an application calls as it called AG Grid's api: the common calls, the events, and reportUnsupported().
 */
export class AgGridApi extends AgColumnsApi {
  /**
   * @param {?HTMLElement} el the element to draw in (null: the model alone)
   * @param {Object} gridOptions the AG Grid-shaped options
   * @param {{out: {warn: function(string): void, info: function(string): void}}} [o] out: where options not covered are said (default: the console)
   */
  constructor(el, gridOptions, o) {
    super();
    this.go = Object.assign({}, gridOptions);
    this.report = new AgReport(o && o.out);
    this.destroyed = false;
    this.t = translateAgOptions(this.go, { report: this.report, api: this });
    /** @type {?Grid} drishti-grid's view (null without an element) */
    this.grid = el ? new Grid(el, Object.assign({}, this.t.options, { pivot: undefined })) : null;
    /** @type {GridModel} drishti-grid's model */
    this.model = this.grid ? this.grid.model : new GridModel(this.t.options);
    this.headless = { grandColumn: this.t.options.grandColumn, groupTotals: this.t.options.columnGroupTotals };
    if (!this.grid) { this.model.pivotExtra = Object.assign({}, this.t.options.pivotOptions); }
    this.events = new AgEvents(this);
    this.model.wells.setState(this.t.wells);
    this.model.pivotState.expandToLevel('rows', this.t.expand.rows);
    this.model.pivotState.expandToLevel('columns', this.t.expand.columns);
    if (this.t.sort.length) { this.model.setSortModel(this.t.sort); }
    if (this.t.quickFilter) { this.model.setQuickFilter(this.t.quickFilter); }
    if (this.t.rowGroups.length) { this.model.setRowGroups(this.t.rowGroups); }
    /** @type {Promise<void>} settles when the first pivot (if any) is computed */
    this.ready = this.t.pivotMode ? this.setPivotMode(true) : Promise.resolve();
    if (this.grid && Object.keys(this.t.flex).length) { this.sizeColumnsToFit(); }
    if (this.grid && this.t.autoHeight) { this.events.fitHeight(); }
    this.events.start();
  }

  // ---- options ---------------------------------------------------------------------------------------------------------------------------
  /**
   * An option as the application last set it.
   * @param {string} key
   * @returns {*}
   */
  getGridOption(key) { return this.go[key]; }

  /**
   * Changes one option at run time (rowData, pivotMode, quickFilterText, columnDefs, groupDefaultExpanded, the totals, sideBar,
   * serverSideDatasource, domLayout, context and the onXxx events); any other is reported.
   * @param {string} key
   * @param {*} value
   * @returns {void}
   */
  setGridOption(key, value) {
    const m = this.model;
    this.go[key] = value;
    if (/^on[A-Z]/.test(key)) { this.events.setHandler(key.charAt(2).toLowerCase() + key.slice(3), value); return; }
    if (RUNTIME.indexOf(key) < 0) { this.report.add(key + ' (at run time)', 'unsupported', 'give it when the grid is made (createGridFromAgOptions)'); return; }
    switch (key) {
      case 'rowData': if (this.grid) { this.grid.setRows(value || []); } else { m.rowModel.setRows(value || []); if (m.pivotMode) { m.runPivot(); } } this.events.fire('rowDataUpdated', {}); break;
      case 'pivotMode': this.setPivotMode(!!value); break;
      case 'quickFilterText': m.setQuickFilter(value || ''); break;
      case 'columnDefs': this.setColumnDefs(value); break;
      case 'groupDefaultExpanded': this.expandTo(value === -1 ? 99 : (value || 0) + 1); break;
      case 'pivotRowTotals': this.setView('grandColumn', !!value); break;
      case 'pivotColumnGroupTotals': this.setView('groupTotals', !!value); break;
      case 'grandTotalRow': m.setPivotOptions({ grandTotalRow: value === 'top' || value === 'pinnedTop' ? 'top' : value ? 'bottom' : 'none' }); break;
      case 'groupTotalRow': m.setPivotOptions({ subtotals: value === 'bottom' ? 'below' : 'above' }); break;
      case 'sideBar': if (this.grid) { this.grid.setLayout(value ? 'panel' : 'wells'); } else { m.setLayout(value ? 'panel' : 'wells'); } break;
      case 'serverSideDatasource': if (m.serverSide) { m.rowModel.datasource = agDatasource(value, this.serverContext()); m.rowModel.purge(); } break;
      case 'domLayout': if (value === 'autoHeight') { this.events.fitHeight(); } break;
      default: break;
    }
  }

  /**
   * Changes several options at once.
   * @param {Object} options
   * @returns {void}
   */
  updateGridOptions(options) { Object.keys(options || {}).forEach((k) => this.setGridOption(k, options[k])); }

  /**
   * The older name of setGridOption('rowData', rows).
   * @param {Object[]} rows
   * @returns {void}
   */
  setRowData(rows) { this.setGridOption('rowData', rows); }

  /** @private the context a server-side datasource is called with */
  serverContext() {
    return { column: (id) => this.model.columns.get(id) || this.model.columns.all().find((c) => c.field === id) || null, aggOf: (id) => this.t.aggNames[id], api: this,
      context: this.go.context, separator: this.go.serverSidePivotResultFieldSeparator || '_' };
  }

  /** @private a view switch (headless: kept) */
  setView(k, v) { if (this.grid) { this.grid.ui[k] = v; this.grid._disp = null; this.grid.refresh(); } else { this.headless[k] = v; } }

  /** @private new column definitions: translated again, the wells and grouping after them */
  setColumnDefs(defs) {
    this.t.registered.forEach(unregisterAggFunc);
    this.t = translateAgOptions(Object.assign({}, this.go, { columnDefs: defs }), { report: this.report, api: this });
    this.model.columns.setColumns(this.t.options.columns);
    this.model.wells.setState(this.t.wells);
    if (!this.model.pivotMode) { this.model.setRowGroups(this.t.rowGroups); }
    if (this.grid) { this.grid.refresh(); }
  }

  // ---- rows --------------------------------------------------------------------------------------------------------------------------------
  /** @private a row's id as the grid keeps it */
  idOf(row) { const rm = this.model.rowModel; return rm.getRowId(row, rm.rows && !this.go.getRowId ? rm.rows.indexOf(row) : -1, []); }

  /** @private an AG transaction as drishti-grid's (removes by id) */
  tx(t) {
    if (t && t.addIndex !== undefined) { this.report.add('applyTransaction addIndex', 'partial', 'added rows go at the end of the data; the sort places them'); }
    return { add: (t && t.add) || [], update: (t && t.update) || [], remove: ((t && t.remove) || []).map((r) => (typeof r === 'string' ? r : this.idOf(r))) };
  }

  /** @private an AG transaction's result: the nodes added, updated and removed */
  txResult(t) { const n = (r) => this.events.nodeOf(r, this.idOf(r)); return { add: ((t && t.add) || []).map(n), update: ((t && t.update) || []).map(n), remove: ((t && t.remove) || []).map((r) => (typeof r === 'string' ? { id: r, data: null } : n(r))) }; }

  /**
   * Adds, updates and removes rows by id (client-side rows).
   * @param {{add: Object[], update: Object[], remove: Object[]}} t
   * @returns {{add: Object[], update: Object[], remove: Object[]}} the row nodes
   */
  applyTransaction(t) { this.model.applyTransaction(this.tx(t)); return this.txResult(t); }

  /**
   * Queues a transaction with the others of the next few milliseconds (one refresh for a live feed); the callback hears its nodes.
   * @param {{add: Object[], update: Object[], remove: Object[]}} t
   * @param {function(Object): void} [callback]
   * @returns {Promise<Object>}
   */
  applyTransactionAsync(t, callback) { return this.model.applyTransactionAsync(this.tx(t)).then(() => { const r = this.txResult(t); if (callback) { callback(r); } return r; }); }

  /**
   * Applies the queued transactions now.
   * @returns {void}
   */
  flushAsyncTransactions() { this.model.flushTransactions(); }

  /**
   * The selected rows' data.
   * @returns {Object[]}
   */
  getSelectedRows() { return this.getSelectedNodes().map((n) => n.data); }

  /**
   * The selected row nodes.
   * @returns {Object[]}
   */
  getSelectedNodes() { const rm = this.model.rowModel; return this.model.selection.selectedIds().map((id) => this.events.nodeOf(rm.getRowById ? rm.getRowById(id) : null, id)); }

  /**
   * Selects every row that passes the filters.
   * @returns {void}
   */
  selectAll() { this.model.selectAll(); }

  /**
   * Clears the row selection.
   * @returns {void}
   */
  deselectAll() { this.model.selection.selectAll(); }

  /**
   * Calls back with every row node of the data (client-side rows; the loaded rows on the server side).
   * @param {function(Object, number): void} fn
   * @returns {void}
   */
  forEachNode(fn) { this.events.leaves(false).forEach(fn); }

  /**
   * Every row node that passes the filters, in the data's order.
   * @param {function(Object, number): void} fn
   * @returns {void}
   */
  forEachNodeAfterFilter(fn) { this.events.leaves(true).forEach(fn); }

  /**
   * Every row node that passes the filters, in the sort's order.
   * @param {function(Object, number): void} fn
   * @returns {void}
   */
  forEachNodeAfterFilterAndSort(fn) { this.events.leaves(true).forEach(fn); }

  /**
   * Every leaf row node (no groups).
   * @param {function(Object, number): void} fn
   * @returns {void}
   */
  forEachLeafNode(fn) { this.events.leaves(false).forEach(fn); }

  /**
   * How many rows are shown (groups and pivot rows included).
   * @returns {number}
   */
  getDisplayedRowCount() { return this.model.rowCount(); }

  /**
   * The row node at a display index.
   * @param {number} i
   * @returns {?Object}
   */
  getDisplayedRowAtIndex(i) { return this.events.nodeAt(i); }

  /**
   * A row node by its id.
   * @param {string} id
   * @returns {?Object}
   */
  getRowNode(id) { const rm = this.model.rowModel, row = rm.getRowById ? rm.getRowById(String(id)) : null; return row ? this.events.nodeOf(row, String(id)) : null; }

  // ---- export -----------------------------------------------------------------------------------------------------------------------------
  /**
   * The grid as shown as CSV text.
   * @param {{columnSeparator: string}} [p]
   * @returns {string}
   */
  getDataAsCsv(p) { return this.model.exportCsv(p && p.columnSeparator ? { separator: p.columnSeparator } : undefined); }

  /**
   * The grid as shown as an Excel workbook's bytes.
   * @param {{sheetName: string}} [p]
   * @returns {Uint8Array}
   */
  getDataAsExcel(p) { return this.model.exportXlsx({ sheetName: (p && p.sheetName) || 'Grid' }); }

  /**
   * Hands the person a CSV file of the grid as shown (drishti.download, else a link clicked).
   * @param {{fileName: string, columnSeparator: string}} [p]
   * @returns {void}
   */
  exportDataAsCsv(p) { this.events.download((p && p.fileName) || 'export.csv', this.getDataAsCsv(p), 'text/csv'); }

  /**
   * Hands the person an Excel workbook of the grid as shown (values and outline).
   * @param {{fileName: string, sheetName: string}} [p]
   * @returns {void}
   */
  exportDataAsExcel(p) { this.events.download((p && p.fileName) || 'export.xlsx', this.getDataAsExcel(p), XLSX_TYPE); }

  // ---- drawing, focus, editing ------------------------------------------------------------------------------------------------------------
  /**
   * Draws the cells again.
   * @returns {void}
   */
  refreshCells() { if (this.grid) { this.grid.refresh(); } }

  /**
   * Draws the rows again.
   * @returns {void}
   */
  redrawRows() { this.refreshCells(); }

  /**
   * Scrolls a display row into view.
   * @param {number} i
   * @returns {void}
   */
  ensureIndexVisible(i) { this.model.viewport.scrollToRow(i); if (this.grid) { this.grid.body.schedule(); } }

  /**
   * Focuses a cell.
   * @param {number} rowIndex
   * @param {string} colKey
   * @returns {void}
   */
  setFocusedCell(rowIndex, colKey) {
    const c = Math.max(0, this.model.shownColumns().findIndex((x) => x.id === colKey));
    if (this.grid) { this.grid.focusCell(rowIndex, c); } else { this.model.cellClicked(rowIndex, c, {}); }
  }

  /**
   * The focused cell, or null.
   * @returns {?{rowIndex: number, column: Object}}
   */
  getFocusedCell() { const f = this.model.navigator.focus, col = f ? this.model.shownColumns()[f.col] : null; return f && col ? { rowIndex: f.row, column: { colId: col.id, getColId: () => col.id }, rowPinned: null } : null; }

  /**
   * The cell ranges selected.
   * @returns {Array<Object>}
   */
  getCellRanges() { const cols = this.model.shownColumns(); return this.model.selection.ranges.map((r) => ({ startRow: { rowIndex: r.r1 }, endRow: { rowIndex: r.r2 }, columns: cols.slice(r.c1, r.c2 + 1).map((c) => ({ colId: c.id })) })); }

  /**
   * Clears the cell ranges.
   * @returns {void}
   */
  clearCellSelection() { this.model.selection.clearRanges(); }

  /**
   * Starts editing a cell (with an element).
   * @param {{rowIndex: number, colKey: string}} p
   * @returns {void}
   */
  startEditingCell(p) { if (this.grid) { this.grid.startEditing(p.rowIndex, this.model.shownColumns().findIndex((c) => c.id === p.colKey)); } }

  /**
   * Stops editing (cancel: the value is not kept).
   * @param {boolean} [cancel]
   * @returns {void}
   */
  stopEditing(cancel) { if (this.grid) { this.grid.stopEditing(cancel); } }

  /**
   * Undoes the last edit.
   * @returns {void}
   */
  undoCellEditing() { this.model.editing.undo().catch(() => {}); }

  /**
   * Redoes the last edit undone.
   * @returns {void}
   */
  redoCellEditing() { this.model.editing.redo().catch(() => {}); }

  // ---- events, the report, the end ---------------------------------------------------------------------------------------------------------
  /**
   * Listens to an AG-named event (AG_EVENTS).
   * @param {string} type
   * @param {function(Object): void} fn
   * @returns {void}
   */
  addEventListener(type, fn) { this.events.add(type, fn); }

  /**
   * Stops listening.
   * @param {string} type
   * @param {function(Object): void} fn
   * @returns {void}
   */
  removeEventListener(type, fn) { this.events.remove(type, fn); }

  /**
   * Every option the adapter did not take in full: its name, its state (partial, unsupported, no-op, unknown) and drishti-grid's way.
   * @returns {import('./drishti_grid-ag.js').AgReportItem[]}
   */
  reportUnsupported() { return this.report.items.map((x) => Object.assign({}, x)); }

  /**
   * Takes the grid away (its element emptied, its aggregations forgotten).
   * @returns {void}
   */
  destroy() {
    if (this.destroyed) { return; }
    this.events.fire('gridPreDestroyed', {});
    this.events.stop();
    this.t.registered.forEach(unregisterAggFunc);
    if (this.grid) { this.grid.destroy(); }
    this.destroyed = true;
  }

  /**
   * Whether destroy() was called.
   * @returns {boolean}
   */
  isDestroyed() { return this.destroyed; }
}

/**
 * A drishti-grid from AG Grid Enterprise-shaped grid options: draws it in the element (or, without one, holds the model alone) and
 * answers the handle an application calls as it called AG Grid's api.
 * @param {?HTMLElement} el
 * @param {Object} gridOptions
 * @param {{out: {warn: function(string): void, info: function(string): void}}} [o]
 * @returns {AgGridApi}
 * @example const api = createGridFromAgOptions(document.getElementById('grid'), { columnDefs, rowData, pivotMode: true, sideBar: true });
 */
export function createGridFromAgOptions(el, gridOptions, o) { return new AgGridApi(el, gridOptions, o); }
