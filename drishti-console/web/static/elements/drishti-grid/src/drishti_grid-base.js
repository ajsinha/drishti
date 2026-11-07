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
/* The parts of drishti-grid's view its layouts call (drishti_grid-view.js's Grid extends this): the columns as drawn (pinned panes, a
   phone's subset), the order and width of a column, the wells' changes (deferred in the panel layout), the pivot's options, the value
   filters and the phone's measure switcher. */
import { WellsModel } from './drishti_wells.js';
import { formatValue } from './drishti_columns.js';
import { phoneColumns, pivotMeasures, figureRange } from './drishti_grid-ui.js';

/**
 * What a layout asks of its grid: the columns as drawn and the changes a person makes in the chrome.
 */
export class GridBase {
  // ---- what is shown ---------------------------------------------------------------------------------------------------------------------
  /**
   * The columns as drawn: each shown column (its index in the model's shown columns, its width, its pane), split into the pinned left,
   * the middle (with each one's left edge) and the pinned right; a phone's subset (O2).
   * @returns {{all: Object[], left: Object[], center: Object[], right: Object[], offs: Float64Array, leftW: number, centerW: number, rightW: number, byIndex: Object, leftOffset: Object, rightOffset: Object}}
   */
  displayColumns() {
    if (this._disp) { return this._disp; }
    const m = this.model, piv = m.pivotMode, ui = this.ui;
    let list = m.shownColumns().map((col, i) => ({ col: col, i: i, width: (piv ? ui.widths[col.id] : null) || col.width || 120,
      pin: piv ? (col.rowHeader ? 'left' : col.kind === 'grand' && ui.pinTotals ? 'right' : null) : col.pinned || null }));
    if (piv) {
      const others = list.some((d) => !d.col.rowHeader && d.col.kind !== 'grand');
      list = list.filter((d) => !ui.hidden.has(d.col.id) && (ui.grandColumn || !others || d.col.kind !== 'grand'));
    }
    if (this.phone) {
      list = phoneColumns(list, piv, ui.phoneMeasure);
      const room = Math.max(160, (this.host.clientWidth || 360) * 0.5);
      list = list.map((d) => (d.pin === 'left' ? Object.assign({}, d, { width: Math.min(d.width, room) }) : d));
    }
    const out = { all: [], left: [], center: [], right: [], byIndex: {}, leftOffset: {}, rightOffset: {} };
    list.forEach((d) => { d.pane = d.pin === 'left' ? 'left' : d.pin === 'right' ? 'right' : 'center'; out[d.pane].push(d); out.byIndex[d.i] = d; });
    out.all = out.left.concat(out.center, out.right);
    let x = 0;
    out.left.forEach((d) => { out.leftOffset[d.i] = x; x += d.width; });
    out.leftW = x;
    x = 0;
    for (let k = out.right.length - 1; k >= 0; k--) { out.rightOffset[out.right[k].i] = x; x += out.right[k].width; }
    out.rightW = x;
    out.offs = new Float64Array(out.center.length + 1);
    out.center.forEach((d, k) => { out.offs[k + 1] = out.offs[k] + d.width; });
    out.centerW = out.offs[out.center.length];
    this._disp = out;
    return out;
  }

  /**
   * Why nothing is drawn, in words (null when there are rows).
   * @returns {?string}
   */
  emptyText() {
    const m = this.model;
    if (m.pivotMode && !m.wells.state.values.length) { return 'Drop a measure on Values to see figures.'; }
    if (m.pivotMode && !m.answer) { return 'Computing the pivot…'; }
    return m.rowCount() ? null : 'No rows to show.';
  }

  /**
   * Whether rows are grouped (grid mode): the first column then holds the tree.
   * @returns {boolean}
   */
  grouped() { const r = this.model.rowModel; return !!((r.groupBy && r.groupBy.length) || (r.rowGroupCols && r.rowGroupCols.length) || r.treeData); }

  /**
   * Whether the rows carry checkboxes.
   * @returns {boolean}
   */
  checkboxes() { return this.o.checkboxes !== false && this.model.selection.mode !== 'none'; }

  /**
   * A shown column's sort as drawn: asc, desc or null (a pivot column: its measure's order of the rows).
   * @param {{col: Object}} d
   * @returns {?string}
   */
  sortOf(d) {
    const m = this.model, col = d.col;
    if (!m.pivotMode) { const s = m.sortModel.find((x) => x.colId === col.id); return s ? s.sort : null; }
    const sort = m.pivotExtra.sort || {}, dims = m.answer ? m.answer.rowDimensions : [];
    const s = dims.length ? sort[dims[0]] : null;
    if (!s) { return null; }
    if (col.rowHeader) { return s.by === 'label' ? (s.desc ? 'desc' : 'asc') : null; }
    return s.by === col.measure && col.kind === 'grand' ? (s.desc ? 'desc' : 'asc') : null;
  }

  /**
   * A pivot column's source field (the value's column id), for its conditional format.
   * @param {Object} col
   * @returns {?string}
   */
  sourceOf(col) {
    if (!this.model.pivotMode || !col.measure) { return col.id; }
    const labels = this.model.wells.toView().values.map((x) => x.as), i = labels.indexOf(col.measure);
    return i >= 0 ? this.model.wells.state.values[i].colId : null;
  }

  /**
   * A column's heading by id.
   * @param {string} id
   * @returns {string}
   */
  headerOf(id) { const c = this.model.columns.get(id); return c ? c.header : id; }

  /**
   * A figure of a measure (a pivot value's label) as its column shows it.
   * @param {string} label
   * @param {*} v
   * @returns {string}
   */
  fmtMeasure(label, v) {
    const col = this.model.shownColumns().find((c) => c.measure === label) || { type: 'number', format: this.model.measureFormat(label) };
    return formatValue(v, col);
  }

  /**
   * The middle and the top quarter of a measure's figures at one level of the pivot's rows (for a value filter's presets).
   * @param {string} dim the row field
   * @param {string} by the measure's label
   * @returns {?{median: number, p75: number}}
   */
  levelFigures(dim, by) {
    const a = this.model.answer;
    if (!a) { return null; }
    const grand = a.columns.find((c) => c.kind === 'grand');
    const xs = a.rows.filter((r) => r.dim === dim).map((r) => { const c = a.cells[r.key + '|' + (grand ? grand.key : '')]; return c && c.values ? c.values[by] : null; })
      .filter((v) => typeof v === 'number').sort((p, q) => p - q);
    if (!xs.length || !figureRange(xs)) { return null; }
    const q = (t) => xs[Math.min(xs.length - 1, Math.floor(t * xs.length))];
    return { median: Math.round(q(0.5)), p75: Math.round(q(0.75)) };
  }

  /**
   * A field's members for its filter: the host's (members option) or the rows in the page.
   * @param {string} colId
   * @returns {Array<{text: string, count: number}>}
   */
  membersOf(colId) {
    if (this.o.members) {
      const r = this.o.members(colId);
      if (Array.isArray(r)) { return r; }
      const cache = this.membersCache = this.membersCache || {};
      if (r && r.then && !cache[colId]) { r.then((list) => { cache[colId] = list || []; if (this.menu.isOpen()) { this.menu.fill(); } }); }
      return cache[colId] || [];
    }
    return this.model.rowModel.members ? this.model.rowModel.members(colId, 500) : [];
  }

  // ---- changes a layout asks for --------------------------------------------------------------------------------------------------------
  /**
   * The wells being edited: the live ones, or the draft while updates are deferred (panel layout).
   * @returns {WellsModel}
   */
  activeWells() { return this.draft || this.model.wells; }

  /**
   * The wells changed: the pivot is on when there is a value; the chrome is drawn again; the change is said.
   * @param {?string} what the change in words
   * @returns {void}
   */
  wellsChanged(what) {
    if (this.draft) { this.draftDirty = true; }
    else if (!this.model.pivotMode && this.model.wells.state.values.length) { this.model.setPivotMode(true); }
    this.ex = null;
    this.renderChrome();
    if (what) { this.say(what); }
  }

  /**
   * Applies the deferred wells (the panel's Update).
   * @returns {void}
   */
  applyDraft() {
    if (!this.draft) { return; }
    this.model.wells.setState(this.draft.getState());
    this.draftDirty = false;
    if (!this.model.pivotMode) { this.model.setPivotMode(true); }
    this.renderChrome();
    this.say('Pivot updated');
  }

  /**
   * A switch of the panel layout: pivot mode, the grand total column, subtotals above, deferred update.
   * @param {string} id pivot, grand, above or defer
   * @param {boolean} on
   * @returns {void}
   */
  panelSwitch(id, on) {
    const m = this.model;
    if (id === 'pivot') { this.setPivotMode(on); return; }
    if (id === 'grand') { this.ui.grandColumn = on; this._disp = null; this.refresh(); return; }
    if (id === 'above') { this.setPivotOptions({ subtotals: on ? 'above' : 'below' }, 'Subtotals ' + (on ? 'above' : 'below')); return; }
    if (id === 'defer') {
      if (on) { this.draft = new WellsModel(m.columns, m.wells.getState()); this.draftDirty = false; } else { const d = this.draftDirty; if (d) { this.applyDraft(); } this.draft = null; }
      this.renderChrome();
    }
  }

  /**
   * Sets more of the pivot's view (sort, top N, subtotals, value filters) and computes it again.
   * @param {Object} extra
   * @param {string} [what] the change in words
   * @returns {Promise<void>}
   */
  setPivotOptions(extra, what) { if (what) { this.say(what); } return this.model.setPivotOptions(extra); }

  /**
   * Replaces the pivot's value filters.
   * @param {Array<{dim: string, by: string, op: string, value: number}>} list
   * @returns {Promise<void>}
   */
  setValueFilters(list) { return this.setPivotOptions({ valueFilters: list }, list.length ? 'Value filter: ' + list.length : 'Value filters cleared'); }

  /**
   * Sorts by a shown column (a heading's click): grid mode none, ascending, descending (Shift adds); a pivot orders its rows by the
   * measure (largest first, then smallest, then by name).
   * @param {number} c the shown column
   * @param {boolean} [multi]
   * @returns {void}
   */
  sortColumn(c, multi) {
    const m = this.model, col = m.shownColumns()[c];
    if (!col || col.sortable === false) { return; }
    if (!m.pivotMode) { m.toggleSort(col.id, multi); return; }
    const dims = m.answer ? m.answer.rowDimensions : [], cur = this.sortOf({ col: col });
    const sort = {};
    if (col.rowHeader) { dims.forEach((d) => { sort[d] = { by: 'label', desc: cur === 'asc' }; }); }
    else if (cur !== 'asc') { dims.forEach((d) => { sort[d] = { by: col.measure, desc: cur !== 'desc' }; }); }
    this.setPivotOptions({ sort: sort }, Object.keys(sort).length ? 'Rows ordered by ' + (col.rowHeader ? 'name' : col.measure) : 'Order cleared');
  }

  /**
   * Sets a shown column's width (grid mode: the model's column; a pivot column: kept by the view).
   * @param {{col: Object, i: number}} d
   * @param {number} w px
   * @returns {void}
   */
  setColumnWidth(d, w) {
    if (this.model.pivotMode) { this.ui.widths[d.col.id] = Math.round(w); this._disp = null; this.body.schedule(); }
    else { this.model.columns.setWidth(d.col.id, Math.round(w)); }
  }

  /**
   * Sizes a column to its widest text among the heading and the first rows (all columns when c is -1).
   * @param {number} c the shown column, or -1
   * @returns {void}
   */
  autosize(c) {
    const m = this.model, disp = this.displayColumns();
    const ctx = this.doc.createElement('canvas').getContext('2d');
    const style = this.doc.defaultView.getComputedStyle(this.body.scroller);
    if (ctx) { ctx.font = style.fontSize + ' ' + style.fontFamily; }
    const measure = (t) => (ctx ? ctx.measureText(String(t)).width : String(t).length * 7);
    const n = Math.min(m.rowCount(), 300);
    (c < 0 ? disp.all : [disp.byIndex[c]]).forEach((d) => {
      if (!d) { return; }
      let w = measure(d.col.header) + 44;
      for (let r = 0; r < n; r++) { w = Math.max(w, measure(m.cellText(r, d.i)) + (d === disp.all[0] ? 70 : 24)); }
      this.setColumnWidth(d, Math.min(480, Math.ceil(w)));
    });
    this._disp = null;
    this.body.schedule();
    this.say(c < 0 ? 'Every column sized to its content' : 'Column sized to its content');
  }

  /**
   * The phone's measure switcher: the one value column shows this measure.
   * @param {string} label
   * @returns {void}
   */
  switchMeasure(label) {
    this.ui.phoneMeasure = label;
    this._disp = null;
    this.emitView('measureSwitched', { measure: label });
    this.refresh();
  }

  /**
   * The measures a phone may switch between.
   * @returns {string[]}
   */
  measures() { return pivotMeasures(this.model.shownColumns()); }

}
