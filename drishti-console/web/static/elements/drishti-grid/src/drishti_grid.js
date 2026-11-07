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
/* The grid model: the one object a layout draws. Columns, a row model (client-side rows in the page, or server-side blocks), the pivot
   (wells, open state, the answer: computed in the browser for rows in the page, asked of the server for the rest, non-additive totals
   always the server's), selection, keyboard focus, the viewport, export, copy and the Explain hook. It knows nothing of the DOM or of
   the layout: wells, panel and terminal are shells over the same state (getState carries the layout's name only to give it back). */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { ColumnModel, getValue, formatValue } from './drishti_columns.js';
import { ClientRowModel } from './drishti_client-rows.js';
import { ServerRowModel } from './drishti_server-rows.js';
import { Viewport } from './drishti_viewport.js';
import { SelectionModel, rangeStats, toTsv, rangeCells } from './drishti_selection.js';
import { GridNavigator } from './drishti_keyboard.js';
import { WellsModel } from './drishti_wells.js';
import { PivotState, resultColumns, cellValue } from './drishti_pivot.js';
import { GridLive } from './drishti_grid-live.js';
import { GridEditing } from './drishti_grid-edit.js';
import { GridModelBase, LAYOUTS, DENSITIES } from './drishti_grid-model-base.js';
import { cycleSort, FILTER_CHANNEL } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} GridOptions
 * @property {import('./drishti_columns.js').ColumnDef[]} columns
 * @property {Object[]} [rows] client-side rows
 * @property {import('./drishti_server-rows.js').Datasource} [datasource] server-side rows (instead of rows)
 * @property {function(Object, number): string} [getRowId]
 * @property {('wells'|'panel'|'terminal')} [layout='wells']
 * @property {('comfortable'|'compact'|'terminal')} [density='compact']
 * @property {function({view: Object}, AbortSignal, Object[]): Promise<Object>} [serverPivot] asks a server pivot (POST /api/pivot/query's
 *   body; the third argument: the rows in the page, for an inline pivot); needed for a pivot of server-side rows, for the non-additive
 *   totals of a local pivot, and for every pivot when clientAggregation is false
 * @property {boolean} [clientAggregation=true] false: the browser computes no figure (the console's policy, ELEMENTS.md section 3): group
 *   figures and every pivot are the server's
 * @property {function(Object): Promise<*>} [explain] the Explain hook: given a cell's context, the explanation (a layout shows it)
 * @property {('single'|'multiple'|'none')} [rowSelection='multiple']
 * @property {number} [blockSize] server-side rows per request
 * @property {function({groupBy: string[], groups: Array<{path: string[], columns: string[]}>}): Promise<Array<{path: string[], values: Object}>>} [serverGroupTotals]
 *   the server's figures of row groups for their non-additive columns (VaR, ES), asked when grouping and again after updates (debounced)
 * @property {{asyncWaitMs: number, flashMs: number, serverDebounceMs: number}} [live] the live data's timings (drishti_grid-live.js)
 * @property {{writeBack: function(Object): Promise<Object>, fourEyes: boolean, undoLimit: number}} [editing] cell editing
 *   (drishti_grid-edit.js): the server's write-back hook (each edit transaction; it answers applied, refused or proposed), four eyes
 * @property {string} [locale] how typed and pasted numbers are read (de-DE: 1.250,5)
 * @property {boolean} [treeData=false] the rows make a tree by their paths (getDataPath): nodes open and close, a node no row names carries
 *   its figures as a group (the server's when clientAggregation is false); the first shown column holds the node's key
 * @property {function(Object): string[]} [getDataPath] a row's path from the top (tree data)
 * @property {number} [groupDefaultExpanded=0] groups and tree nodes open down to this level (-1: all)
 */

/**
 * The grid model.
 */
export class GridModel extends GridModelBase {
  /** @param {GridOptions} o */
  constructor(o) {
    super();
    if (!o || !o.columns) { throw new Error('a grid needs columns'); }
    this.o = o;
    /** @type {EventBus} changed {what}: rows, columns, selection, focus, pivot, state; pivotError {error} */
    this.events = new EventBus();
    this.columns = new ColumnModel(o.columns);
    this.layout = LAYOUTS.indexOf(o.layout) >= 0 ? o.layout : 'wells';
    this.density = DENSITIES[o.density] ? o.density : 'compact';
    this.rowModel = o.datasource ? new ServerRowModel({ datasource: o.datasource, blockSize: o.blockSize, getRowId: o.getRowId })
      : new ClientRowModel({ columns: this.columns, rows: o.rows || [], getRowId: o.getRowId, clientAggregation: o.clientAggregation, treeData: o.treeData,
        getDataPath: o.getDataPath, groupDefaultExpanded: o.groupDefaultExpanded });
    this.serverSide = !!o.datasource;
    this.viewport = new Viewport({ rowHeight: DENSITIES[this.density] });
    this.selection = new SelectionModel({ mode: o.rowSelection });
    this.wells = new WellsModel(this.columns);
    this.pivotState = new PivotState();
    this.pivotMode = false;
    this.pivotExtra = {};
    this.answer = null;
    this.sortModel = [];
    this.filterModel = {};
    this.navigator = new GridNavigator({
      rowCount: () => this.rowCount(), columnCount: () => this.shownColumns().length,
      pageSize: () => Math.max(1, Math.floor(this.viewport.height / this.viewport.rowHeight) - 1),
      isGroup: (r) => { const x = this.rowAt(r); return !!x && (x.kind === 'group' || (x.kind === 'pivot' && x.entry.expandable)); },
      isExpanded: (r) => { const x = this.rowAt(r); return !!x && (x.kind === 'group' ? x.expanded : x.kind === 'pivot' ? !!x.entry.expanded : false); },
      explain: !!o.explain
    });
    /** @type {GridLive} the typed events and the live data */
    this.live = new GridLive(this, o.live);
    /** @type {GridEditing} cell editing: transactions, undo and redo, paste, write-back and four eyes */
    this.editing = new GridEditing(this, Object.assign({ locale: o.locale }, o.editing));
    this.rowModel.events.on('rowsChanged', (p) => { this.viewport.setRowCount(this.rowCount()); this.events.emit('changed', { what: 'rows', reason: p.reason }); });
    this.rowModel.events.on('blockLoaded', (p) => this.live.emit('blockLoaded', p));
    this.rowModel.events.on('loadFailed', (p) => this.live.emit('blockFailed', p));
    const colEvent = { order: 'columnMoved', width: 'columnResized', pinned: 'columnPinned', visible: 'columnVisible' };
    this.columns.events.on('changed', (p) => {
      this.events.emit('changed', { what: 'columns' });
      if (colEvent[p.what]) { const c = this.columns.get(p.id); this.live.emit(colEvent[p.what], { colId: p.id, width: c && c.width, pinned: c && c.pinned, visible: c && !c.hidden }); }
    });
    this.selection.events.on('changed', (p) => {
      this.events.emit('changed', { what: 'selection', which: p.what });
      if (p.what === 'rows') { this.live.emit('selectionChanged', { ids: this.selection.selectedIds() }); } else { this.live.emit('rangeChanged', { ranges: this.selection.ranges.slice() }); }
    });
    this.wells.events.on('changed', () => { if (this.pivotMode) { this.runPivot(); } });
    this.viewport.setRowCount(this.rowCount());
  }

  // ---- rows and columns as shown -----------------------------------------------------------------------------------------------------
  /**
   * How many rows are shown (groups and pivot rows included).
   * @returns {number}
   */
  rowCount() {
    if (this.pivotMode) { return this.answer ? this.pivotRows().length : 0; }
    return this.rowModel.rowCount();
  }

  /** @private the pivot's shown rows: every entry (a header row too: it carries the group's label) */
  pivotRows() { return this.answer ? this.answer.rows : []; }

  /**
   * The row at a display index: a leaf or group of the row model, or a pivot entry ({kind: 'pivot', entry}).
   * @param {number} i
   * @returns {?Object}
   */
  rowAt(i) {
    if (this.pivotMode) { const e = this.pivotRows()[i]; return e ? { kind: 'pivot', id: 'pivot:' + e.kind + ':' + e.key, depth: Math.max(0, (e.depth || 0) - 1), entry: e } : null; }
    return this.rowModel.rowAt(i);
  }

  /**
   * The shown columns: in grid mode the visible columns (pinned first); in pivot mode the row heading column, then the pivot's result
   * columns (one per column member, total and value).
   * @returns {Object[]}
   */
  shownColumns() {
    if (!this.pivotMode) { return this.columns.visible(); }
    if (this._pivotCols) { return this._pivotCols; }
    const a = this.answer;
    const dims = a ? a.rowDimensions : [];
    const head = { id: '__rows', header: dims.map((d) => { const c = this.columns.get(d); return c ? c.header : d; }).join(' / ') || 'Total', type: 'text', pinned: 'left', width: 220, additive: true, rowHeader: true };
    const rc = a ? resultColumns(a) : [];
    this._pivotCols = [head].concat(rc.map((r) => ({ id: r.id, header: r.headerPath.join(' › '), headerPath: r.headerPath, type: 'number', width: 120,
      colKey: r.colKey, measure: r.measure, kind: r.kind, additive: !r.server, format: this.measureFormat(r.measure) })));
    return this._pivotCols;
  }

  /** @private a measure's format: its column's */
  measureFormat(label) {
    const labels = this.wells.toView().values.map((x) => x.as), i = labels.indexOf(label);
    const v = i >= 0 ? this.wells.state.values[i] : null;
    const c = v ? this.columns.get(v.colId) : null;
    return c && c.format ? c.format : 'amount0';
  }

  /**
   * A cell's raw value (a group's figure; a pivot cell's figure).
   * @param {number} r display row
   * @param {number} c shown column
   * @returns {*}
   */
  cellValue(r, c) {
    const row = this.rowAt(r), col = this.shownColumns()[c];
    if (!row || !col) { return undefined; }
    if (row.kind === 'pivot') {
      if (col.rowHeader) { return row.entry.label; }
      if (row.entry.kind === 'header') { return null; }
      return cellValue(this.answer, row.entry.key, col.colKey, col.measure).value;
    }
    if (row.tree && c === 0) { return row.treeKey; }
    if (row.kind === 'group') {
      if (c === 0) { return row.value; }
      if (row.tree && row.row) { return getValue(row.row, col); }
      const agg = row.aggregates || (row.row || {});
      return agg[col.id] !== undefined ? agg[col.id] : null;
    }
    if (row.kind === 'loading') { return undefined; }
    return getValue(row.row, col);
  }

  /**
   * A cell's text as shown (formatted; a group's first cell its member and count; a pending figure "…").
   * @param {number} r
   * @param {number} c
   * @returns {string}
   */
  cellText(r, c) {
    const row = this.rowAt(r), col = this.shownColumns()[c];
    if (!row || !col) { return ''; }
    if (row.kind === 'loading') { return c === 0 ? '…' : ''; }
    const v = this.cellValue(r, c);
    if (row.kind === 'group' && c === 0) { return (v === null || v === undefined ? '(blank)' : String(v)) + (row.count !== undefined ? ' (' + row.count + ')' : ''); }
    if (row.kind === 'group' && row.pendingServer && row.pendingServer.indexOf(col.id) >= 0) { return '…'; }
    if (row.kind === 'pivot') {
      if (col.rowHeader) { return String(v); }
      if (row.entry.kind === 'header') { return ''; }
      const cv = cellValue(this.answer, row.entry.key, col.colKey, col.measure);
      if (cv.value === null && cv.note) { return '…'; }
      return cv.value === null || cv.value === undefined ? '' : formatValue(cv.value, col);
    }
    return formatValue(v, col, row.row);
  }

  // ---- sorting, filtering, grouping ----------------------------------------------------------------------------------------------------
  /**
   * Sorts by a sort model.
   * @param {Array<{colId: string, sort: string}>} model
   * @returns {void}
   */
  setSortModel(model) { this.sortModel = (model || []).slice(); this.rowModel.setSortModel(this.sortModel); this.live.emit('sortChanged', { sortModel: this.sortModel.slice() }); }

  /**
   * A header click: none, ascending, descending (Shift: adds to the sort).
   * @param {string} colId
   * @param {boolean} [multi]
   * @returns {Array<{colId: string, sort: string}>} the new model
   */
  toggleSort(colId, multi) { const m = cycleSort(this.sortModel, colId, multi); this.setSortModel(m); return m; }

  /**
   * Filters by a filter model.
   * @param {Object<string, Object>} model
   * @returns {void}
   */
  setFilterModel(model) {
    this.filterModel = Object.assign({}, model);
    this.rowModel.setFilterModel(this.filterModel);
    this.live.emit('filterChanged', { filterModel: Object.assign({}, this.filterModel) });
    if (this.pivotMode) { this.runPivot(); }
  }

  /**
   * Filters by words across the shown columns.
   * @param {string} q
   * @returns {void}
   */
  setQuickFilter(q) { this.rowModel.setQuickFilter(q); this.live.emit('filterChanged', { quickFilter: q || '' }); }

  /**
   * Follows a filter bar (drishti-inputs) or any page part that emits the filter model on a bus under FILTER_CHANNEL: each payload's
   * filters on this grid's columns (by id or field) become its filter model, and its quick filter the grid's; filters on fields this
   * grid has not are left out.
   * @param {EventBus} bus drishti-core's bus (the page's shared bus, or the filter bar's)
   * @param {{channel: string, source: string}} [o] channel default FILTER_CHANNEL; source: follow only the bar of this id
   * @returns {function(): void} stops following
   */
  followFilters(bus, o) {
    o = o || {};
    return bus.on(o.channel || FILTER_CHANNEL, (p) => {
      if (!p || (o.source && p.source !== o.source)) { return; }
      const known = {};
      Object.keys(p.model || {}).forEach((k) => { const c = this.columns.get(k) || this.columns.all().find((x) => x.field === k); if (c) { known[c.id] = p.model[k]; } });
      this.rowModel.setQuickFilter(p.quickFilter || '');
      this.setFilterModel(known);
    });
  }

  /**
   * Groups rows (grid mode).
   * @param {string[]} colIds
   * @returns {void}
   */
  setRowGroups(colIds) {
    if (this.serverSide) { this.rowModel.setGrouping({ rowGroupCols: colIds, valueCols: this.columns.all().filter((c) => c.enableValue).map((c) => ({ colId: c.id, aggFunc: c.aggFunc })) }); }
    else {
      this.rowModel.setGroupBy(colIds);
      const want = this.rowModel.serverGroupColumns();
      if (want.length && this.o.serverGroupTotals) { want.forEach((x) => this.live.staleGroups.set(x.path.join('\u001f'), x.path)); this.live.askServer(); }
    }
    this.live.emit('groupChanged', { rowGroups: (colIds || []).slice() });
  }

  /**
   * Opens or closes the group at a display row (a row group or a pivot row).
   * @param {number} r
   * @param {boolean} [open]
   * @returns {Promise<void>}
   */
  toggleRow(r, open) {
    const x = this.rowAt(r);
    if (!x) { return Promise.resolve(); }
    if (x.kind === 'pivot' && x.entry.expandable) {
      const now = open === undefined ? !x.entry.expanded : open;
      this.live.emit(now ? 'rowExpanded' : 'rowCollapsed', { path: x.entry.path, pivot: true });
      return this.togglePivotNode('rows', x.entry.path, open);
    }
    if (x.kind === 'group') {
      const path = x.path || x.keys, now = open === undefined ? !x.expanded : open;
      this.rowModel.setExpanded(path, open);
      this.live.emit(now ? 'rowExpanded' : 'rowCollapsed', { path: path, pivot: false });
    }
    return Promise.resolve();
  }

  // ---- selection, focus, keys ----------------------------------------------------------------------------------------------------------
  /**
   * The id of a display row: a leaf's id, a pivot row's (pivot:<kind>:<key>, so the rows of a pivot may be selected too); null for a
   * group, a pivot's heading line or a placeholder.
   * @param {number} r
   * @returns {?string}
   */
  idAt(r) { const x = this.rowAt(r); return !x ? null : x.kind === 'leaf' ? x.id : x.kind === 'pivot' && x.entry.kind !== 'header' ? x.id : null; }

  /**
   * A key press on the grid: moves the focus, changes the selection and runs the action the layout needs no part in; returns what the
   * layout does (copy text to the clipboard, start an edit, show an Explain).
   * @param {KeyboardEvent} e
   * @returns {{handled: boolean, action: ?string, focus: ?Object, copy: ?string}}
   */
  key(e) {
    const before = this.navigator.focus ? Object.assign({}, this.navigator.focus) : null;
    const k = this.navigator.handleKey(e), f = this.navigator.focus;
    const out = { handled: k.handled, action: k.action, focus: f, copy: null };
    if (k.focus) {
      if (k.action === 'extend-range') {
        if (!this.selection.rangeAnchor && before) { this.selection.startRange(before.row, before.col); }
        this.selection.extendRange(f.row, f.col);
      } else { this.selection.startRange(f.row, f.col); }
      this.viewport.scrollToRow(f.row);
      this.events.emit('changed', { what: 'focus' });
      this.live.emit('focusChanged', { row: f.row, col: f.col });
    }
    if (!f) { return out; }
    switch (k.action) {
      case 'copy': out.copy = this.copy(); break;
      case 'select-all': this.selectAll(); break;
      case 'select-row': this.selection.clickRow(f.row, (i) => this.idAt(i), { ctrl: true }); break;
      case 'toggle-row': this.selection.clickRow(f.row, (i) => this.idAt(i), { ctrl: true }); break;
      case 'expand': this.toggleRow(f.row, true); break;
      case 'collapse': this.toggleRow(f.row, false); break;
      case 'clear': this.selection.clearRanges(); break;
      default: break;
    }
    return out;
  }

  /**
   * Selects every row that passes the filters (client-side), or clears.
   * @returns {void}
   */
  selectAll() {
    if (this.serverSide || this.pivotMode) { this.selection.startRange(0, 0); this.selection.extendRange(this.rowCount() - 1, this.shownColumns().length - 1); return; }
    const rm = this.rowModel, ids = [];
    for (let i = 0; i < rm.view.length; i++) { ids.push(rm.getRowId(rm.rows[rm.view[i]], rm.view[i])); }
    this.selection.selectAll(ids);
  }

  /**
   * The selected cells as TSV (the ranges, else the focused cell): numbers raw, so Excel reads them as numbers.
   * @param {{headers: boolean}} [o]
   * @returns {string}
   */
  copy(o) {
    let ranges = this.selection.ranges;
    if (!ranges.length && this.navigator.focus) { const f = this.navigator.focus; ranges = [{ r1: f.row, r2: f.row, c1: f.col, c2: f.col }]; }
    const cols = this.shownColumns();
    return toTsv(rangeCells(ranges, (r, c) => { const v = this.cellValue(r, c); return typeof v === 'number' ? String(v) : this.cellText(r, c); },
      o && o.headers ? { headers: (j) => cols[j].header } : null));
  }

  /**
   * The figures of the selected range for the status line (count, sum, average, min, max; a non-additive column warns instead of summing).
   * @returns {Object}
   */
  rangeStats() {
    const cols = this.shownColumns();
    return rangeStats(this.selection.ranges, (r, c) => this.cellValue(r, c), (j) => cols[j]);
  }

  // ---- Explain ---------------------------------------------------------------------------------------------------------------------------
  /**
   * A cell's context for Explain: the row (or a pivot cell's coordinates), the column or measure, the value, and whether the figure is
   * additive.
   * @param {number} r
   * @param {number} c
   * @returns {?Object}
   */
  cellContext(r, c) {
    const row = this.rowAt(r), col = this.shownColumns()[c];
    if (!row || !col) { return null; }
    const ctx = { row: r, col: c, columnId: col.id, header: col.header, value: this.cellValue(r, c), text: this.cellText(r, c), additive: col.additive !== false, kind: row.kind };
    if (row.kind === 'pivot') {
      const cell = this.answer.cells[row.entry.key + '|' + col.colKey];
      Object.assign(ctx, { measure: col.measure, coords: cell ? cell.coords : row.entry.coords, members: cell ? cell.members : 0, note: cell && cell.notes ? cell.notes[col.measure] || null : null, view: this.pivotView() });
    } else if (row.kind === 'group') { Object.assign(ctx, { path: row.path || row.keys, field: row.field }); }
    else { Object.assign(ctx, { rowId: row.id, data: row.row }); }
    return ctx;
  }

  /**
   * Explains a cell through the grid's Explain hook (rejects when the grid has none).
   * @param {number} r
   * @param {number} c
   * @returns {Promise<*>}
   */
  explain(r, c) {
    if (!this.o.explain) { return Promise.reject(new Error('this grid has no Explain hook')); }
    const ctx = this.cellContext(r, c);
    if (!ctx) { return Promise.reject(new Error('no cell at ' + r + ', ' + c)); }
    this.live.emit('explainOpened', ctx);
    return Promise.resolve(this.o.explain(ctx));
  }

  // ---- events and live data (drishti_grid-live.js) -----------------------------------------------------------------------------------
  /**
   * Listens to a typed event (GRID_EVENTS) or to changed.
   * @param {string} name
   * @param {function(Object, string): void} fn
   * @returns {function(): void} stops listening
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * The element the grid's events are dispatched on as DOM CustomEvents (drishti:cell-clicked ...): the web component's host.
   * @param {?EventTarget} el
   * @returns {void}
   */
  setEventTarget(el) { this.live.target = el; }

  /**
   * A click on a cell (a layout calls it): focus, the range, the row's selection, and cellClicked.
   * @param {number} r
   * @param {number} c
   * @param {{ctrl: boolean, shift: boolean}} [mods]
   * @returns {void}
   */
  cellClicked(r, c, mods) {
    const m = mods || {};
    this.navigator.setFocus(r, c);
    if (m.shift) { this.selection.extendRange(r, c); } else { this.selection.startRange(r, c, m); }
    this.selection.clickRow(r, (i) => this.idAt(i), m);
    this.live.emit('cellClicked', Object.assign(this.cellContext(r, c) || {}, { mods: m }));
  }

  /**
   * A double click on a cell: cellDoubleClicked (a layout starts an edit or opens a group).
   * @param {number} r
   * @param {number} c
   * @returns {void}
   */
  cellDoubleClicked(r, c) { this.live.emit('cellDoubleClicked', this.cellContext(r, c) || {}); }

  /**
   * An edit: a leaf's value of an editable column set, validated, as an edit transaction (rows updated by id, cellValueChanged and
   * editTransaction told, kept for undo); the write-back hook, when there is one, hears it.
   * @param {number} r
   * @param {number} c
   * @param {*} value
   * @returns {boolean} whether the value was set (false: the cell may not be edited, or the value is not valid)
   */
  setCellValue(r, c, value) {
    const p = this.editing.plan([{ r: r, c: c, value: value }]);
    if (p.problems.length) { return false; }
    this.editing.apply(p.edits, 'api').catch(() => {});
    return true;
  }

  /**
   * Adds, updates and removes rows by id now (rows in the page); rowDataUpdated tells what changed.
   * @param {{add: Object[], update: Object[], remove: string[]}} tx
   * @returns {{added: number, updated: number, removed: number, changes: Array}}
   */
  applyTransaction(tx) { return this.live.apply(tx); }

  /**
   * Queues a transaction with the others of the next few milliseconds (a live feed's thousands of updates a second, one refresh).
   * @param {{add: Object[], update: Object[], remove: string[]}} tx
   * @returns {Promise<Object>} the batch's result
   */
  applyTransactionAsync(tx) { return this.live.applyAsync(tx); }

  /**
   * Applies the queued transactions now.
   * @returns {?Object}
   */
  flushTransactions() { return this.live.flush(); }

  /**
   * A cell's flash after a live change: up, down or null.
   * @param {string} rowId
   * @param {string} colId
   * @returns {?string}
   */
  flashOf(rowId, colId) { return this.live.flashOf(rowId, colId); }

  /**
   * Connects a live feed (drishti_live.js: Server-Sent Events, a WebSocket, or drishti-data's live sources).
   * @param {{subscribe: function(function(Object): void): function(): void}} feed
   * @returns {function(): void} disconnects
   */
  connectLive(feed) { return this.live.connect(feed); }
}

/**
 * A new grid model.
 * @param {GridOptions} o
 * @returns {GridModel}
 * @example const g = createGridModel({columns: [{field: 'DESK'}, {field: 'PNL', type: 'number', format: 'amount0'}], rows: data});
 */
export function createGridModel(o) { return new GridModel(o); }
