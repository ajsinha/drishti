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
/* The AG Grid-style adapter's events and shapes: drishti-grid's typed events (GRID_EVENTS, the wells' changes) raised again under AG
   Grid's names with AG-shaped parameters (type, api, context, node, data, column, value ...), gridReady and firstDataRendered, row nodes
   and column objects as an application reads them, the column state both ways, and the widths (sizeColumnsToFit with flex, autoHeight). */
import { AG_EVENTS } from './drishti_grid-ag-map.js';
import { AG_GROUP_COLUMN } from './drishti_grid-ag.js';

/** drishti-grid's events and the AG events each raises. */
const FROM = {
  cellClicked: ['cellClicked', 'rowClicked'], cellDoubleClicked: ['cellDoubleClicked', 'rowDoubleClicked'], cellValueChanged: ['cellValueChanged'],
  focusChanged: ['cellFocused'], selectionChanged: ['selectionChanged'], rangeChanged: ['cellSelectionChanged', 'rangeSelectionChanged'],
  sortChanged: ['sortChanged'], filterChanged: ['filterChanged'], pivotChanged: ['modelUpdated'], rowDataUpdated: ['rowDataUpdated', 'modelUpdated'],
  blockLoaded: ['modelUpdated'], groupChanged: ['columnRowGroupChanged'], rowExpanded: ['rowGroupOpened'], rowCollapsed: ['rowGroupOpened'],
  columnMoved: ['columnMoved'], columnResized: ['columnResized'], columnPinned: ['columnPinned'], columnVisible: ['columnVisible'],
  cellEditStarted: ['cellEditingStarted'], cellEditStopped: ['cellEditingStopped']
};
const WELL_EVENT = { rows: 'columnRowGroupChanged', columns: 'columnPivotChanged', values: 'columnValueChanged' };

/**
 * The events, nodes, columns and widths of an AgGridApi.
 */
export class AgEvents {
  /** @param {Object} api the AgGridApi */
  constructor(api) {
    this.api = api;
    this.handlers = Object.assign({}, api.t.events);
    this.listeners = {};
    this.offs = [];
    this.rendered = false;
  }

  /**
   * Starts raising events: drishti-grid's own, the wells', pivot mode; then gridReady and, when the first rows are in, firstDataRendered.
   * @returns {void}
   */
  start() {
    const m = this.api.model;
    Object.keys(FROM).forEach((name) => this.offs.push(m.on(name, (d) => this.from(name, d || {}))));
    this.offs.push(m.wells.events.on('changed', (p) => (p.well === '*' ? Object.values(WELL_EVENT) : [WELL_EVENT[p.well]]).forEach((e) => e && this.fire(e, { source: 'api' }))));
    let mode = m.pivotMode;
    this.offs.push(m.on('changed', (p) => {
      if (p.what === 'pivot' && m.pivotMode !== mode) { mode = m.pivotMode; this.fire('columnPivotModeChanged', { pivotMode: mode }); }
      if ((p.what === 'rows' || p.what === 'pivot') && !this.rendered) { this.firstData(); }
    }));
    Promise.resolve().then(() => { if (!this.api.destroyed) { this.fire('gridReady', {}); this.firstData(); } });
  }

  /** @private firstDataRendered once, when rows are in (after a frame when drawn) */
  firstData() {
    const m = this.api.model;
    if (this.rendered || this.api.destroyed) { return; }
    const has = m.pivotMode ? !!m.answer : m.serverSide ? m.rowModel.levels.get('').blocks.size > 0 : m.rowModel.rows.length > 0;
    if (!has) { return; }
    this.rendered = true;
    const w = this.api.grid && this.api.grid.doc.defaultView;
    const go = () => { if (!this.api.destroyed) { this.fire('firstDataRendered', {}); } };
    if (w && w.requestAnimationFrame) { w.requestAnimationFrame(() => w.requestAnimationFrame(go)); } else { Promise.resolve().then(go); }
  }

  /**
   * Stops raising events.
   * @returns {void}
   */
  stop() { this.offs.forEach((off) => off()); this.offs = []; }

  /**
   * Sets an onXxx handler (setGridOption).
   * @param {string} name the event's name
   * @param {?function(Object): void} fn
   * @returns {void}
   */
  setHandler(name, fn) {
    if (AG_EVENTS.indexOf(name) < 0) { this.api.report.add('on' + name.charAt(0).toUpperCase() + name.slice(1), 'unsupported', 'one of AG_EVENTS, or drishti-grid\'s own on(name)'); return; }
    this.handlers[name] = fn;
  }

  /**
   * Adds a listener of an AG-named event.
   * @param {string} type
   * @param {function(Object): void} fn
   * @returns {void}
   */
  add(type, fn) {
    if (AG_EVENTS.indexOf(type) < 0) { this.api.report.add('addEventListener ' + type, 'unsupported', 'one of AG_EVENTS, or drishti-grid\'s own on(name)'); return; }
    (this.listeners[type] = this.listeners[type] || new Set()).add(fn);
  }

  /**
   * Removes a listener.
   * @param {string} type
   * @param {function(Object): void} fn
   * @returns {void}
   */
  remove(type, fn) { if (this.listeners[type]) { this.listeners[type].delete(fn); } }

  /**
   * Raises an AG-named event: its handler (onXxx) and its listeners, given {type, api, context} and the event's own parameters.
   * @param {string} type
   * @param {Object} params
   * @returns {void}
   */
  fire(type, params) {
    const e = Object.assign({ type: type, api: this.api, context: this.api.go.context }, params);
    const h = this.handlers[type];
    if (typeof h === 'function') { h(e); }
    if (this.listeners[type]) { this.listeners[type].forEach((fn) => fn(e)); }
  }

  /** @private one drishti-grid event as its AG events */
  from(name, d) {
    const m = this.api.model;
    let p = {};
    if (name === 'cellClicked' || name === 'cellDoubleClicked') {
      const col = m.shownColumns()[d.col];
      p = { rowIndex: d.row, value: d.value, data: d.data || null, node: this.nodeAt(d.row), column: col ? this.columnOf(col) : null, colDef: col ? this.columnOf(col).colDef : null };
    } else if (name === 'cellValueChanged') {
      const row = m.rowModel.getRowById ? m.rowModel.getRowById(d.rowId) : null, col = m.columns.get(d.colId);
      p = { data: row, node: this.nodeOf(row, d.rowId), oldValue: d.old, newValue: d.value, value: d.value, column: col ? this.columnOf(col) : null, colDef: col ? this.columnOf(col).colDef : null, source: d.source };
    } else if (name === 'focusChanged') {
      const col = m.shownColumns()[d.col];
      p = { rowIndex: d.row, column: col ? this.columnOf(col) : null, rowPinned: null };
    } else if (name === 'rowExpanded' || name === 'rowCollapsed') {
      p = { expanded: name === 'rowExpanded', node: { key: d.path && d.path.length ? (Array.isArray(d.path[d.path.length - 1]) ? d.path[d.path.length - 1][1] : d.path[d.path.length - 1]) : null, level: d.path ? d.path.length - 1 : 0, group: true, expanded: name === 'rowExpanded' } };
    } else if (/^column/.test(name)) {
      const col = m.columns.get(d.colId);
      p = { column: col ? this.columnOf(col) : null, columns: col ? [this.columnOf(col)] : [], finished: true, pinned: d.pinned, visible: d.visible, source: 'uiColumnMoved' };
    } else if (name === 'groupChanged') {
      const g = m.columns.get(AG_GROUP_COLUMN);
      if (g && !m.pivotMode && g.hidden === !!(d.rowGroups && d.rowGroups.length)) { m.columns.setVisible(AG_GROUP_COLUMN, !!(d.rowGroups && d.rowGroups.length)); }
      p = { columns: (d.rowGroups || []).map((id) => this.columnOf(m.columns.get(id))) };
    } else if (name === 'cellEditStarted' || name === 'cellEditStopped') {
      const col = m.shownColumns()[d.col];
      p = { rowIndex: d.row, column: col ? this.columnOf(col) : null, node: this.nodeAt(d.row), value: d.value, valueChanged: !!d.saved };
    } else { p = Object.assign({}, d); }
    FROM[name].forEach((type) => this.fire(type, p));
    if (name === 'rowDataUpdated' || name === 'blockLoaded' || name === 'pivotChanged') { this.firstData(); }
  }

  // ---- nodes and columns ----------------------------------------------------------------------------------------------------------------
  /**
   * A row node as an application reads it: id, data, group, key, level, expanded, rowIndex, setSelected and isSelected.
   * @param {?Object} data
   * @param {?string} id
   * @param {Object} [x] the display row (a group's key and level)
   * @param {number} [rowIndex]
   * @returns {Object}
   */
  nodeOf(data, id, x, rowIndex) {
    const sel = this.api.model.selection;
    const group = !!x && (x.kind === 'group' || (x.kind === 'pivot' && x.entry.kind !== 'grand' && x.entry.expandable));
    return {
      id: id, data: data, rowIndex: rowIndex === undefined ? null : rowIndex, group: group, level: x ? x.depth || 0 : 0,
      key: x ? (x.kind === 'pivot' ? x.entry.value : x.kind === 'group' ? x.value : null) : null, expanded: x ? !!(x.expanded || (x.entry && x.entry.expanded)) : false,
      isSelected: () => (id !== null && id !== undefined ? sel.isSelected(id) : false),
      setSelected: (on) => { if (id === null || id === undefined) { return; } const ids = new Set(sel.selectedIds()); if (on) { ids.add(id); } else { ids.delete(id); } sel.selectAll(Array.from(ids)); }
    };
  }

  /**
   * The row node at a display index (a leaf, a group, a pivot row).
   * @param {number} i
   * @returns {?Object}
   */
  nodeAt(i) {
    const m = this.api.model, x = m.rowAt(i);
    if (!x || x.kind === 'loading') { return null; }
    return this.nodeOf(x.kind === 'pivot' ? null : x.row || null, x.kind === 'pivot' ? x.id : x.kind === 'leaf' ? x.id : x.row && x.tree ? x.id : null, x, i);
  }

  /**
   * The leaf nodes: every row of the data, or (filtered) those that pass the filters in display order.
   * @param {boolean} filtered
   * @returns {Object[]}
   */
  leaves(filtered) {
    const rm = this.api.model.rowModel;
    if (this.api.model.serverSide) {
      const out = [];
      rm.levels.forEach((lv) => lv.blocks.forEach((b, bi) => b.rows.forEach((r, i) => out.push(this.nodeOf(r, rm.getRowId(r, bi * rm.blockSize + i, lv.keys))))));
      return out;
    }
    const at = filtered ? Array.from(rm.view) : rm.rows.map((r, i) => i);
    return at.map((i) => this.nodeOf(rm.rows[i], rm.getRowId(rm.rows[i], i)));
  }

  /**
   * A column as an application reads it: {colId, colDef, getColId, isVisible, getActualWidth, pinned}.
   * @param {?Object} c a drishti column
   * @returns {?Object}
   */
  columnOf(c) {
    if (!c) { return null; }
    const def = this.colDefOf(c.id) || { field: c.field, headerName: c.header };
    return { colId: c.id, colDef: def, getColId: () => c.id, isVisible: () => !c.hidden, getActualWidth: () => c.width, pinned: c.pinned || null };
  }

  /** @private the application's column definition of an id */
  colDefOf(id) {
    let found = null;
    const walk = (defs) => (defs || []).forEach((d) => { if (found || !d) { return; } if (d.children) { walk(d.children); } else if (String(d.colId || d.field) === id) { found = d; } });
    walk(this.api.go.columnDefs);
    return found;
  }

  /**
   * Each column's state.
   * @returns {Array<Object>}
   */
  columnState() {
    const m = this.api.model, w = m.wells.state;
    return m.columns.all().filter((c) => !c.system).map((c) => {
      const s = m.sortModel.findIndex((x) => x.colId === c.id), v = w.values.find((x) => x.colId === c.id);
      return { colId: c.id, width: c.width, hide: !!c.hidden, pinned: c.pinned || null, sort: s >= 0 ? m.sortModel[s].sort : null, sortIndex: s >= 0 ? s : null,
        rowGroup: w.rows.indexOf(c.id) >= 0, rowGroupIndex: w.rows.indexOf(c.id) >= 0 ? w.rows.indexOf(c.id) : null, pivot: w.columns.indexOf(c.id) >= 0,
        pivotIndex: w.columns.indexOf(c.id) >= 0 ? w.columns.indexOf(c.id) : null, aggFunc: v ? this.api.t.aggNames[c.id] || v.aggFunc : null, flex: this.api.t.flex[c.id] || null };
    });
  }

  /**
   * Gives back column states (see AgGridApi.applyColumnState).
   * @param {{state: Array<Object>, applyOrder: boolean, defaultState: Object}} p
   * @returns {boolean} whether every state named a column
   */
  applyColumnState(p) {
    const m = this.api.model, cols = m.columns, named = new Set();
    let all = true;
    const list = (p.state || []).slice();
    if (p.defaultState) { cols.all().filter((c) => !c.system && !list.some((s) => s.colId === c.id)).forEach((c) => list.push(Object.assign({ colId: c.id }, p.defaultState))); }
    const w = m.wells.getState(), sort = [];
    let wellsChanged = false;
    list.forEach((s) => {
      const c = cols.get(String(s.colId));
      if (!c) { all = false; return; }
      named.add(c.id);
      if (s.width !== undefined) { cols.setWidth(c.id, s.width); }
      if (s.hide !== undefined && !!s.hide !== !!c.hidden) { cols.setVisible(c.id, !s.hide); }
      if (s.pinned !== undefined) { cols.pin(c.id, s.pinned === 'right' ? 'right' : s.pinned ? 'left' : null); }
      if (s.sort !== undefined) { if (s.sort) { sort.push({ colId: c.id, sort: s.sort, at: s.sortIndex === undefined || s.sortIndex === null ? 1e6 : s.sortIndex }); } }
      const place = (well, on, at) => {
        if (on === undefined && at === undefined) { return; }
        const l = w[well].filter((x) => x !== c.id);
        if (on || (at !== undefined && at !== null)) { l.splice(at === undefined || at === null ? l.length : at, 0, c.id); }
        w[well] = l;
        wellsChanged = true;
      };
      place('rows', s.rowGroup, s.rowGroupIndex);
      place('columns', s.pivot, s.pivotIndex);
      if (s.aggFunc !== undefined) { w.values = w.values.filter((x) => x.colId !== c.id); if (s.aggFunc) { w.values.push({ colId: c.id, aggFunc: s.aggFunc }); } wellsChanged = true; }
    });
    if (list.some((s) => s.sort !== undefined)) {
      const keep = m.sortModel.filter((x) => !named.has(x.colId));
      m.setSortModel(keep.concat(sort.sort((a, b) => a.at - b.at).map((x) => ({ colId: x.colId, sort: x.sort }))));
    }
    if (wellsChanged) { m.wells.setState(w); if (!m.pivotMode) { m.setRowGroups(w.rows); } }
    if (p.applyOrder) { (p.state || []).forEach((s, i) => { if (cols.get(String(s.colId))) { cols.move(String(s.colId), i + (cols.get(AG_GROUP_COLUMN) ? 1 : 0)); } }); }
    return all;
  }

  // ---- widths, height, files --------------------------------------------------------------------------------------------------------------
  /**
   * Shares the element's width among the shown columns: by flex where given (a flex column takes its share of what the others leave),
   * else every column in proportion to its width.
   * @returns {void}
   */
  sizeToFit() {
    const g = this.api.grid, m = this.api.model;
    if (!g || m.pivotMode) { return; }
    const room = (g.body.scroller.clientWidth || g.host.clientWidth || 0) - 2;
    if (room <= 0) { return; }
    const shown = m.columns.visible(), flex = this.api.t.flex;
    const flexCols = shown.filter((c) => flex[c.id]), fixed = shown.filter((c) => !flex[c.id]);
    if (flexCols.length) {
      const left = Math.max(0, room - fixed.reduce((a, c) => a + c.width, 0)), total = flexCols.reduce((a, c) => a + flex[c.id], 0);
      flexCols.forEach((c) => m.columns.setWidth(c.id, Math.floor(left * flex[c.id] / total)));
    } else {
      const total = shown.reduce((a, c) => a + c.width, 0) || 1;
      shown.forEach((c) => m.columns.setWidth(c.id, Math.floor(room * c.width / total)));
    }
  }

  /**
   * domLayout autoHeight: the element's height follows the rows shown (heading and status bar included), now and as rows change.
   * @returns {void}
   */
  fitHeight() {
    const g = this.api.grid, m = this.api.model;
    if (!g || this.fitting) { return; }
    this.fitting = true;
    const fit = () => {
      const rows = Math.min(m.rowCount(), 2000), chrome = g.host.scrollHeight - g.body.scroller.clientHeight;
      g.host.style.height = Math.max(120, chrome + rows * m.viewport.rowHeight + 24) + 'px';
    };
    this.offs.push(m.on('changed', (p) => { if (p.what === 'rows' || p.what === 'pivot') { fit(); } }));
    fit();
  }

  /**
   * Hands the person a file: the host's drishti.download hook, else a link clicked (with an element).
   * @param {string} name
   * @param {(string|Uint8Array)} data
   * @param {string} type
   * @returns {void}
   */
  download(name, data, type) {
    const g = this.api.grid, hook = this.api.t.options.download;
    if (!g && !hook) { this.api.report.add('export without an element', 'partial', 'getDataAsCsv() or getDataAsExcel() answer the file\'s content'); return; }
    const win = g ? g.doc.defaultView : null, blob = win ? new win.Blob([data], { type: type }) : data;
    if (hook) { hook(name, blob); return; }
    const a = g.doc.createElement('a');
    a.href = win.URL.createObjectURL(blob);
    a.download = name;
    g.doc.body.appendChild(a);
    a.click();
    a.remove();
  }
}
