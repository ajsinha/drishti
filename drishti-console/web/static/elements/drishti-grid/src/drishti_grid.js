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
import { PivotState, pivotLocal, serverRequest, mergeAnswer, applyValueFilters, resultColumns, cellValue } from './drishti_pivot.js';
import { toCsv, toXlsx } from './drishti_export.js';
import { cycleSort } from './drishti_sort-filter.js';

/** The layouts a grid is drawn in (the visual shell; the model is the same). */
export const LAYOUTS = Object.freeze(['wells', 'panel', 'terminal']);
/** The densities, with their row heights in px. */
export const DENSITIES = Object.freeze({ comfortable: 36, compact: 28, terminal: 22 });

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
 */

/**
 * The grid model.
 */
export class GridModel {
  /** @param {GridOptions} o */
  constructor(o) {
    if (!o || !o.columns) { throw new Error('a grid needs columns'); }
    this.o = o;
    /** @type {EventBus} changed {what}: rows, columns, selection, focus, pivot, state; pivotError {error} */
    this.events = new EventBus();
    this.columns = new ColumnModel(o.columns);
    this.layout = LAYOUTS.indexOf(o.layout) >= 0 ? o.layout : 'wells';
    this.density = DENSITIES[o.density] ? o.density : 'compact';
    this.rowModel = o.datasource ? new ServerRowModel({ datasource: o.datasource, blockSize: o.blockSize, getRowId: o.getRowId })
      : new ClientRowModel({ columns: this.columns, rows: o.rows || [], getRowId: o.getRowId, clientAggregation: o.clientAggregation });
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
    this.rowModel.events.on('rowsChanged', (p) => { this.viewport.setRowCount(this.rowCount()); this.events.emit('changed', { what: 'rows', reason: p.reason }); });
    this.columns.events.on('changed', () => this.events.emit('changed', { what: 'columns' }));
    this.selection.events.on('changed', (p) => this.events.emit('changed', { what: 'selection', which: p.what }));
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
    if (row.kind === 'group') {
      if (c === 0) { return row.value; }
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
  setSortModel(model) { this.sortModel = (model || []).slice(); this.rowModel.setSortModel(this.sortModel); }

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
  setFilterModel(model) { this.filterModel = Object.assign({}, model); this.rowModel.setFilterModel(this.filterModel); if (this.pivotMode) { this.runPivot(); } }

  /**
   * Filters by words across the shown columns.
   * @param {string} q
   * @returns {void}
   */
  setQuickFilter(q) { this.rowModel.setQuickFilter(q); }

  /**
   * Groups rows (grid mode).
   * @param {string[]} colIds
   * @returns {void}
   */
  setRowGroups(colIds) {
    if (this.serverSide) { this.rowModel.setGrouping({ rowGroupCols: colIds, valueCols: this.columns.all().filter((c) => c.enableValue).map((c) => ({ colId: c.id, aggFunc: c.aggFunc })) }); }
    else { this.rowModel.setGroupBy(colIds); }
  }

  // ---- the pivot -----------------------------------------------------------------------------------------------------------------------
  /**
   * Turns pivot mode on or off; on, the wells' view is computed (or asked).
   * @param {boolean} on
   * @returns {Promise<void>}
   */
  setPivotMode(on) {
    this.pivotMode = !!on;
    this._pivotCols = null;
    this.events.emit('changed', { what: 'pivot' });
    return on ? this.runPivot() : Promise.resolve();
  }

  /**
   * Sets the view's other parts (sort per level, top N, subtotals above or below, value filters, source, provider options).
   * @param {Object} extra
   * @returns {Promise<void>}
   */
  setPivotOptions(extra) { this.pivotExtra = Object.assign({}, this.pivotExtra, extra); return this.pivotMode ? this.runPivot() : Promise.resolve(); }

  /**
   * The pivot view now: the wells, the grid's member filters, the open state and the options.
   * @returns {Object}
   */
  pivotView() {
    const v = this.wells.toView(this.pivotExtra);
    Object.keys(this.filterModel).forEach((k) => {
      const f = this.filterModel[k], c = this.columns.get(k), field = c ? c.field : k;
      if (f && f.type === 'set' && f.values) { v.filters[field] = { in: f.values.map(String), notIn: [] }; }
      else if (f && f.type === 'set' && f.excluded) { v.filters[field] = { in: [], notIn: f.excluded.map(String) }; }
    });
    return Object.assign(v, this.pivotState.toView(this.answer));
  }

  /**
   * Computes the pivot: in the browser for client-side rows (the non-additive measures then asked of the server, when serverPivot is
   * given), else asked of the server. A value filter is applied either way.
   * @returns {Promise<void>}
   */
  runPivot() {
    const view = this.pivotView();
    this._pivotCols = null;
    if (!view.values.length) { this.answer = null; this.viewport.setRowCount(0); this.events.emit('changed', { what: 'pivot' }); return Promise.resolve(); }
    if (this.pivotAbort) { this.pivotAbort.abort(); }
    const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    this.pivotAbort = ctl;
    const done = (a) => {
      if (ctl && ctl.signal.aborted) { return; }
      this.answer = a;
      this._pivotCols = null;
      this.viewport.setRowCount(this.rowCount());
      this.events.emit('changed', { what: 'pivot' });
    };
    const fail = (e) => { if (!(ctl && ctl.signal.aborted)) { this.events.emit('pivotError', { error: e }); } };
    if (this.serverSide || this.o.clientAggregation === false) {
      if (!this.o.serverPivot) { return Promise.reject(new Error(this.serverSide ? 'a pivot of server-side rows needs serverPivot' : 'clientAggregation is off: a pivot needs serverPivot')); }
      return this.o.serverPivot(serverRequest(view), ctl && ctl.signal, this.serverSide ? null : this.rowModel.filteredRows()).then((a) => { applyValueFilters(a, view.valueFilters); done(a); }, fail);
    }
    const byField = {};
    this.columns.all().forEach((c) => { byField[c.field] = c; });
    const rows = this.rowModel.filteredRows();
    const a = pivotLocal(rows, view, { state: this.pivotState, column: (f) => byField[f] || null, value: (r, f) => (byField[f] ? getValue(r, byField[f]) : r[f]) });
    done(a);
    if (a.pending.length && this.o.serverPivot) {
      const only = Array.from(new Set(a.pending.map((p) => p.measure)));
      return this.o.serverPivot(serverRequest(view, { only: only }), ctl && ctl.signal).then((s) => {
        if (this.answer !== a) { return; }
        mergeAnswer(a, s);
        this.events.emit('changed', { what: 'pivot' });
      }, fail);
    }
    return Promise.resolve();
  }

  /**
   * Opens or closes a pivot row or column group, and computes again.
   * @param {('rows'|'columns')} axis
   * @param {Array<Array<string>>} path the node's (dimension, value) pairs
   * @param {boolean} [open]
   * @returns {Promise<void>}
   */
  togglePivotNode(axis, path, open) { this.pivotState.toggle(axis, path, open); return this.runPivot(); }

  /**
   * Opens every pivot node of an axis to a level.
   * @param {('rows'|'columns')} axis
   * @param {number} level
   * @returns {Promise<void>}
   */
  expandPivotTo(axis, level) { this.pivotState.expandToLevel(axis, level); return this.runPivot(); }

  /**
   * Opens or closes the group at a display row (a row group or a pivot row).
   * @param {number} r
   * @param {boolean} [open]
   * @returns {Promise<void>}
   */
  toggleRow(r, open) {
    const x = this.rowAt(r);
    if (!x) { return Promise.resolve(); }
    if (x.kind === 'pivot' && x.entry.expandable) { return this.togglePivotNode('rows', x.entry.path, open); }
    if (x.kind === 'group') { this.rowModel.setExpanded(x.path || x.keys, open); }
    return Promise.resolve();
  }

  // ---- selection, focus, keys ----------------------------------------------------------------------------------------------------------
  /**
   * The id of a display row, or null for a group, a pivot row or a placeholder.
   * @param {number} r
   * @returns {?string}
   */
  idAt(r) { const x = this.rowAt(r); return x && x.kind === 'leaf' ? x.id : null; }

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
    return ctx ? Promise.resolve(this.o.explain(ctx)) : Promise.reject(new Error('no cell at ' + r + ', ' + c));
  }

  // ---- export --------------------------------------------------------------------------------------------------------------------------
  /**
   * The grid as shown, as a table to export: shown columns; in grid mode every filtered row (or the groups and their rows), in pivot
   * mode the pivot's rows, with outline levels.
   * @returns {import('./drishti_export.js').ExportTable}
   */
  toTable() {
    const cols = this.shownColumns();
    const rows = [], levels = [], bold = [];
    const n = this.serverSide ? Math.min(this.rowCount(), 1048575) : this.rowCount();
    for (let i = 0; i < n; i++) {
      const x = this.rowAt(i);
      if (!x || x.kind === 'loading') { continue; }
      rows.push(cols.map((c, j) => {
        const v = this.cellValue(i, j);
        return x.kind === 'pivot' && c.rowHeader ? ' '.repeat(Math.max(0, (x.entry.depth || 1) - 1)) + v : v;
      }));
      levels.push(x.kind === 'pivot' ? Math.max(0, (x.entry.depth || 0) - 1) : x.depth || 0);
      bold.push(x.kind === 'group' || (x.kind === 'pivot' && (x.entry.kind === 'total' || x.entry.kind === 'grand' || x.entry.kind === 'header')));
    }
    return { columns: cols.map((c) => ({ header: c.header, type: c.type, format: c.format, width: c.width })), rows: rows, levels: levels, bold: bold };
  }

  /**
   * CSV text of the grid as shown.
   * @param {{formatted: boolean}} [o]
   * @returns {string}
   */
  exportCsv(o) { return toCsv(this.toTable(), o); }

  /**
   * An Excel workbook of the grid as shown.
   * @param {{sheetName: string}} [o]
   * @returns {Uint8Array}
   */
  exportXlsx(o) { return toXlsx(this.toTable(), o); }

  // ---- state -----------------------------------------------------------------------------------------------------------------------------
  /**
   * Sets the density (the row height follows).
   * @param {('comfortable'|'compact'|'terminal')} d
   * @returns {void}
   */
  setDensity(d) {
    if (!DENSITIES[d]) { throw new Error('no density ' + d + ' (' + Object.keys(DENSITIES).join(', ') + ')'); }
    this.density = d;
    this.viewport.rowHeight = DENSITIES[d];
    this.events.emit('changed', { what: 'state' });
  }

  /**
   * Sets the layout's name (the shell draws it; nothing in the model changes).
   * @param {('wells'|'panel'|'terminal')} l
   * @returns {void}
   */
  setLayout(l) {
    if (LAYOUTS.indexOf(l) < 0) { throw new Error('no layout ' + l + ' (' + LAYOUTS.join(', ') + ')'); }
    this.layout = l;
    this.events.emit('changed', { what: 'state' });
  }

  /**
   * Everything a person set, to keep with a view or a link: columns, sort, filters, wells, pivot mode and open state, layout, density.
   * @returns {Object}
   */
  getState() {
    return { columns: this.columns.getState(), sort: this.sortModel.slice(), filters: JSON.parse(JSON.stringify(this.filterModel)), wells: this.wells.getState(),
      pivot: this.pivotMode, pivotView: this.pivotMode ? this.pivotView() : null, pivotOptions: Object.assign({}, this.pivotExtra), layout: this.layout, density: this.density };
  }

  /**
   * Gives back a kept state.
   * @param {Object} s
   * @returns {Promise<void>}
   */
  setState(s) {
    if (s.columns) { this.columns.setState(s.columns); }
    if (s.layout) { this.setLayout(s.layout); }
    if (s.density) { this.setDensity(s.density); }
    if (s.sort) { this.setSortModel(s.sort); }
    if (s.filters) { this.filterModel = Object.assign({}, s.filters); this.rowModel.setFilterModel(this.filterModel); }
    if (s.wells) { this.wells.state = JSON.parse(JSON.stringify(s.wells)); }
    if (s.pivotOptions) { this.pivotExtra = Object.assign({}, s.pivotOptions); }
    if (s.pivotView) { this.pivotState = new PivotState(s.pivotView); }
    return this.setPivotMode(!!s.pivot);
  }
}

/**
 * A new grid model.
 * @param {GridOptions} o
 * @returns {GridModel}
 * @example const g = createGridModel({columns: [{field: 'DESK'}, {field: 'PNL', type: 'number', format: 'amount0'}], rows: data});
 */
export function createGridModel(o) { return new GridModel(o); }
