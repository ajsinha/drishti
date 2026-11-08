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
/* The grid model's pivot, export and kept state (GridModel, drishti_grid.js, extends this): the pivot computed in the browser for rows in
   the page or asked of the server, a server's "data changed" notice, the table to export (CSV, xlsx), the layout, the density, and the
   state a person keeps with a view. Every member works on the GridModel's own fields (columns, row model, wells, the answer). */
import { toCsv } from '../../drishti-core/src/drishti_index.js';
import { getValue } from './drishti_columns.js';
import { PivotState, pivotLocal, serverRequest } from './drishti_pivot.js';
import { pivotApplyUpdates, mergeAnswer, applyValueFilters } from './drishti_pivot-answer.js';
import { toXlsx } from './drishti_export.js';
import { ClientRowModel } from './drishti_client-rows.js';
import { ServerRowModel } from './drishti_server-rows.js';

/** The layouts a grid is drawn in (the visual shell; the model is the same). */
export const LAYOUTS = Object.freeze(['wells', 'panel', 'terminal']);
/** The densities, with their row heights in px. */
export const DENSITIES = Object.freeze({ comfortable: 36, compact: 28, terminal: 22 });

/**
 * Puts a pivot answer's grand total row where a view asks (its pivotOptions' grandTotalRow): 'top', 'bottom', or 'none' (left out);
 * anything else keeps the answer's own place (with the subtotals: below them at the bottom, above them at the top).
 * @param {?Object} answer a pivot answer (its rows changed in place)
 * @param {('top'|'bottom'|'none')} [where]
 * @returns {void}
 */
export function placeGrandRow(answer, where) {
  if (!answer || !answer.rows || ['top', 'bottom', 'none'].indexOf(where) < 0) { return; }
  const grand = answer.rows.filter((r) => r.kind === 'grand'), rest = answer.rows.filter((r) => r.kind !== 'grand');
  answer.rows = where === 'none' ? rest : where === 'top' ? grand.concat(rest) : rest.concat(grand);
}

/**
 * A grid's row model from its options: server-side rows when there is a datasource (with tree data a level at a time), else the rows in
 * the page (with their tree by getDataPath).
 * @param {Object} o the grid's options (GridOptions)
 * @param {import('./drishti_columns.js').ColumnModel} columns
 * @returns {(ClientRowModel|ServerRowModel)}
 */
export function rowModelFor(o, columns) {
  const tree = { treeData: o.treeData, groupDefaultExpanded: o.groupDefaultExpanded, getRowId: o.getRowId };
  return o.datasource ? new ServerRowModel(Object.assign({ datasource: o.datasource, blockSize: o.blockSize, isServerSideGroup: o.isServerSideGroup,
    getServerSideGroupKey: o.getServerSideGroupKey }, tree))
    : new ClientRowModel(Object.assign({ columns: columns, rows: o.rows || [], clientAggregation: o.clientAggregation, getDataPath: o.getDataPath }, tree));
}

/** The pivot, the export and the kept state of a grid model; GridModel adds the rest (rows, columns, selection, keys, editing). */
export class GridModelBase {
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
      placeGrandRow(a, this.pivotExtra.grandTotalRow);
      this.answer = a;
      this._pivotCols = null;
      this.viewport.setRowCount(this.rowCount());
      this.events.emit('changed', { what: 'pivot' });
      this.live.emit('pivotChanged', { view: view, rows: a ? a.rows.length : 0 });
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
      return this.o.serverPivot(serverRequest(view, { only: only }), ctl && ctl.signal, rows).then((s) => {
        if (this.answer !== a) { return; }
        mergeAnswer(a, s);
        this.events.emit('changed', { what: 'pivot' });
      }, fail);
    }
    return Promise.resolve();
  }

  /**
   * Shows a pivot answer the host fetched itself (a progressive answer's first, approximate one; a live patch; an answer a page holds),
   * in the server's PivotAnswer shape. A request in flight is left alone when `o.keepAsking` (a progressive answer's exact one follows).
   * With `o.patched` only figures moved: they flash, the open state and the columns stay.
   * @param {Object} answer
   * @param {{patched: boolean, keepAsking: boolean}} [o]
   * @returns {void}
   */
  showAnswer(answer, o) {
    o = o || {};
    if (this.pivotAbort && !o.keepAsking && !o.patched) { this.pivotAbort.abort(); this.pivotAbort = null; }
    this.pivotMode = true;
    this.answer = answer;
    this._pivotCols = null;
    this.viewport.setRowCount(this.rowCount());
    if (o.patched) { this.live.emit('serverTotalsUpdated', { pivotCells: Object.keys(answer.cells || {}).length }); }
    this.events.emit('changed', { what: 'pivot' });
    if (!o.patched) { this.live.emit('pivotChanged', { view: this.pivotView(), rows: answer ? answer.rows.length : 0 }); }
  }

  /**
   * Sets the pivot's open state from a view (its expanded nodes and expandTo), without computing: a saved view given back.
   * @param {{expanded: Array, expandTo: Object}} view
   * @returns {void}
   */
  setOpenState(view) { this.pivotState = new PivotState(view || {}); }

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
   * A server's "data changed" notice: server-side rows are asked again (all, a level, blocks, or the blocks holding some rows); a
   * server pivot is computed again.
   * @param {{purge: boolean, keys: string[], blocks: number[], rows: string[]}} notice
   * @returns {string} what was done
   */
  dataChanged(notice) {
    if (this.pivotMode && (this.serverSide || this.o.clientAggregation === false)) { this.runPivot(); return 'pivot'; }
    if (!this.serverSide) { return 'nothing'; }
    return this.rowModel.dataChanged(notice);
  }

  /**
   * Changes the local pivot's figures for changed rows by difference (false: compute it again).
   * @param {Array<{old: Object, value: Object}>} pairs
   * @returns {boolean}
   */
  applyPivotUpdates(pairs) {
    if (!this.answer) { return false; }
    return pivotApplyUpdates(this.answer, pairs).applied;
  }

  /**
   * Asks the server again for the pivot's non-additive measures (every cell), and puts the figures in.
   * @returns {Promise<number>} how many figures came in
   */
  refreshServerMeasures() {
    const a = this.answer;
    if (!a || !this.o.serverPivot) { return Promise.resolve(0); }
    const labels = a.measures.filter((m) => m.server).map((m) => m.label);
    if (!labels.length) { return Promise.resolve(0); }
    const view = this.pivotView();
    return this.o.serverPivot(serverRequest(view, { only: labels }), undefined, this.serverSide ? null : this.rowModel.filteredRows()).then((s) => {
      if (this.answer !== a) { return 0; }
      a.pending = [];
      Object.keys(a.cells).forEach((k) => { const [row, column] = k.split('|'); labels.forEach((l) => a.pending.push({ row: row, column: column, coords: a.cells[k].coords, measure: l })); });
      const n = mergeAnswer(a, s);
      this.events.emit('changed', { what: 'pivot' });
      return n;
    });
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

  /**
   * The pivot's row-header column wide enough for its labels (indented by depth), 220 px at least and 460 at most: full row labels
   * (POS-BOOK-RATES-3~CORP-BOND-ALLY-4.25-2031), a longer one ends with … and its full path in its tooltip.
   * @returns {number} px
   */
  headWidth() {
    let w = 220;
    ((this.answer && this.answer.rows) || []).forEach((r) => { w = Math.max(w, 34 + (r.depth || 0) * 16 + String(r.label || '').length * 7.2); });
    return Math.min(460, Math.round(w));
  }
}
