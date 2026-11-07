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
/* The client-side row model: every row is in the page. Filtering and sorting make an index (a typed array of row numbers, so a million
   rows cost four megabytes, not a million objects); row groups make a tree over it whose group rows carry their figures, each from its
   own members by the aggregation of its column (a non-additive column's group figure is left to the server: null, and listed); the
   display is one typed array of codes (a row number, or a group). Rows have ids (getRowId), so selection and transactions follow a row
   wherever sorting moves it. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { getValue } from './drishti_columns.js';
import { compareRows, compareValues, rowFilter, isBlank } from '../../drishti-core/src/drishti_index.js';
import { aggFunc } from './drishti_aggregate.js';
import { memberText, SEP } from './drishti_pivot.js';

/**
 * A row as the grid sees it at a display index.
 * @typedef {Object} DisplayRow
 * @property {('leaf'|'group')} kind
 * @property {string} id the row's id (a group's: "group:" and its path)
 * @property {number} depth 0 for a row without groups; a group's level from 0; a leaf under groups: the number of groups
 * @property {?Object} row the data (leaves)
 * @property {number} [index] the row's number in the data
 * @property {string} [field] a group's column
 * @property {*} [value] a group's member
 * @property {string[]} [path] a group's members from the top
 * @property {number} [count] a group's rows
 * @property {boolean} [expanded]
 * @property {Object<string, *>} [aggregates] a group's figures by column id
 * @property {string[]} [pendingServer] a group's non-additive columns, left for the server
 */

/**
 * The client-side row model.
 */
export class ClientRowModel {
  /**
   * @param {{columns: import('./drishti_columns.js').ColumnModel, rows: Object[], getRowId: function(Object, number): string, clientAggregation: boolean}} o
   *   clientAggregation false: no group figure is computed here, every one is left for the server (the console's policy)
   */
  constructor(o) {
    this.columns = o.columns;
    this.clientAggregation = o.clientAggregation !== false;
    this.getRowId = o.getRowId || ((r, i) => (r && r.id !== undefined && r.id !== null ? String(r.id) : String(i)));
    /** @type {EventBus} rowsChanged {reason} */
    this.events = new EventBus();
    this.sortModel = [];
    this.filterModel = {};
    this.quickFilter = '';
    this.groupBy = [];
    this.expanded = new Set();
    this.expandLevel = 0;
    this.setRows(o.rows || []);
  }

  /**
   * Replaces every row.
   * @param {Object[]} rows
   * @returns {void}
   */
  setRows(rows) {
    this.rows = rows;
    this.reindexIds();
    this.refresh('rows');
  }

  /** @private */
  reindexIds() {
    this.idIndex = new Map();
    for (let i = 0; i < this.rows.length; i++) { this.idIndex.set(this.getRowId(this.rows[i], i), i); }
  }

  /**
   * A row's changed cells: column id to the old and new value and the way a number moved (for a cell's flash).
   * @param {Object} before
   * @param {Object} after
   * @returns {Object<string, {old: *, value: *, dir: ?string}>}
   */
  changesOf(before, after) {
    const out = {};
    this.columns.all().forEach((c) => {
      const a = this.value(before, c), b = this.value(after, c);
      if (a === b || (a !== a && b !== b)) { return; }
      out[c.id] = { old: a, value: b, dir: typeof a === 'number' && typeof b === 'number' ? (b > a ? 'up' : b < a ? 'down' : null) : null };
    });
    return out;
  }

  /**
   * Adds, updates (by id) and removes (by id) rows. Updates alone are applied in place: a row whose sort or filter values changed is
   * taken out of the order and merged back where it now belongs (ties keep the rows' order, so the order is stable), and the figures
   * of its groups follow by difference (sums and counts) or from their members (the others); adds and removes refresh the whole view.
   * @param {{add: Object[], update: Object[], remove: Array<(Object|string)>}} t
   * @returns {{added: number, updated: number, removed: number, changes: Array<{id: string, cells: Object}>, staleGroups: string[][]}}
   */
  applyTransaction(t) {
    let added = 0, updated = 0, removed = 0;
    const changes = [], touched = [];
    (t.update || []).forEach((r) => {
      const id = this.getRowId(r, -1), i = this.idIndex.get(id);
      if (i === undefined) { return; }
      const before = this.rows[i];
      this.rows[i] = r;
      updated++;
      const cells = this.changesOf(before, r);
      if (Object.keys(cells).length) { changes.push({ id: id, cells: cells }); touched.push({ i: i, before: before, cells: cells }); }
    });
    if (t.remove && t.remove.length) {
      const gone = new Set(t.remove.map((r) => (typeof r === 'string' ? r : this.getRowId(r, -1))));
      const before = this.rows.length;
      this.rows = this.rows.filter((r, i) => !gone.has(this.getRowId(r, i)));
      removed = before - this.rows.length;
    }
    (t.add || []).forEach((r) => { this.rows.push(r); added++; });
    let staleGroups = [];
    if (added || removed) { this.reindexIds(); this.refresh('transaction'); staleGroups = this.groupNodes ? this.groupNodes.map((g) => g.path) : []; }
    else if (touched.length) { staleGroups = this.updateInPlace(touched); this.events.emit('rowsChanged', { reason: 'transaction' }); }
    return { added: added, updated: updated, removed: removed, changes: changes, staleGroups: staleGroups };
  }

  /** @private updates in place; returns the paths of the groups whose non-additive figures the server must give again */
  updateInPlace(touched) {
    const changed = new Set();
    touched.forEach((x) => Object.keys(x.cells).forEach((k) => changed.add(k)));
    if (this.groupBy.some((g) => changed.has(g))) { this.refresh('transaction'); return this.groupNodes ? this.groupNodes.map((g) => g.path) : []; }
    const sort = (this.sortModel || []).filter((s) => this.columns.get(s.colId));
    const filterCols = Object.keys(this.filterModel || {}).filter((k) => this.filterModel[k]);
    const reposition = !!this.quickFilter || sort.some((s) => changed.has(s.colId)) || filterCols.some((k) => changed.has(k));
    if (reposition) {
      const cols = this.columns.all(), rows = this.rows;
      const test = rowFilter(this.filterModel, this.quickFilter, this.quickFilter ? this.columns.visible() : cols, (r, c) => this.value(r, c));
      const cmp0 = sort.length ? compareRows(sort, (id) => this.columns.get(id), (r, c) => this.value(r, c)) : null;
      const cmp = (a, b) => (cmp0 ? cmp0(rows[a], rows[b]) : 0) || a - b;
      const moved = new Set(touched.map((x) => x.i));
      const back = Array.from(moved).filter((i) => !test || test(rows[i])).sort(cmp);
      const v = this.view, out = new Uint32Array(v.length - this.countIn(v, moved) + back.length);
      let k = 0, j = 0;
      for (let i = 0; i < v.length; i++) {
        const x = v[i];
        if (moved.has(x)) { continue; }
        while (j < back.length && cmp(back[j], x) < 0) { out[k++] = back[j++]; }
        out[k++] = x;
      }
      while (j < back.length) { out[k++] = back[j++]; }
      this.view = out;
      if (this.groupBy.length) { this.buildGroups(); this.flatten(); return this.groupNodes.filter((g) => g.serverCols.length).map((g) => g.path); }
      this.flatten();
      return [];
    }
    return this.groupBy.length ? this.regroupFigures(touched) : [];
  }

  /** @private */
  countIn(v, set) { let n = 0; for (let i = 0; i < v.length; i++) { if (set.has(v[i])) { n++; } } return n; }

  /** @private a changed row's groups: sums and counts by difference, the other functions again from the group's members */
  regroupFigures(touched) {
    const stale = new Map();
    const inView = this.view.length === this.rows.length ? null : new Set(this.view);
    touched.forEach((x) => {
      if (inView && !inView.has(x.i)) { return; }
      const row = this.rows[x.i];
      let key = '';
      for (let d = 0; d < this.groupBy.length; d++) {
        key = (d ? key + SEP : '') + memberText(this.value(row, this.columns.get(this.groupBy[d])));
        const g = this.groupByKey.get(key);
        if (!g) { continue; }
        Object.keys(x.cells).forEach((colId) => {
          const c = this.columns.get(colId);
          if (!c || !(colId in g.aggregates)) { return; }
          if (g.serverCols.indexOf(colId) >= 0) { stale.set(g.path.join(SEP), g.path); return; }
          const f = aggFunc(c.aggFunc), st = g.states[colId];
          if (f.name === 'sum' || f.name === 'avg') {
            const o = x.cells[colId].old, n = x.cells[colId].value;
            const on = typeof o === 'number' && isFinite(o), nn = typeof n === 'number' && isFinite(n);
            st.s += (nn ? n : 0) - (on ? o : 0);
            st.n += (nn ? 1 : 0) - (on ? 1 : 0);
          } else if (f.name === 'count') {
            const has = (v) => v !== undefined && v !== null && v !== '';
            g.states[colId] = st + (has(x.cells[colId].value) ? 1 : 0) - (has(x.cells[colId].old) ? 1 : 0);
          } else {
            g.states[colId] = this.groupState(g, c, f);
          }
          g.aggregates[colId] = f.result(g.states[colId]);
        });
      }
    });
    return Array.from(stale.values());
  }

  /** @private a group's state of a column, from its members */
  groupState(g, c, f) {
    const leaves = [];
    const walk = (n) => { n.rows.forEach((x) => leaves.push(x)); n.children.forEach(walk); };
    walk(g);
    let st = f.init();
    for (let j = 0; j < leaves.length; j++) { st = f.add(st, this.value(this.rows[leaves[j]], c)); }
    return st;
  }

  /**
   * Puts the server's figures of a group in (its non-additive columns: VaR, ES): they are no longer waiting.
   * @param {string[]} path the group's members from the top
   * @param {Object<string, *>} values column id to the server's figure
   * @returns {boolean} whether the group exists
   */
  setGroupServerValues(path, values) {
    const g = this.groupByKey && this.groupByKey.get(path.join(SEP));
    if (!g) { return false; }
    Object.keys(values).forEach((k) => { g.aggregates[k] = values[k]; g.pendingServer = g.pendingServer.filter((x) => x !== k); });
    return true;
  }

  /**
   * The groups and the columns whose figures the server gives (non-additive, or every one when clientAggregation is off).
   * @returns {Array<{path: string[], columns: string[]}>}
   */
  serverGroupColumns() { return (this.groupNodes || []).filter((g) => g.serverCols.length).map((g) => ({ path: g.path, columns: g.serverCols.slice() })); }

  /** Sorts by a sort model. @param {Array<{colId: string, sort: string}>} m @returns {void} */
  setSortModel(m) { this.sortModel = (m || []).slice(); this.refresh('sort'); }
  /** Filters by a filter model (column id to its filter). @param {Object<string, Object>} m @returns {void} */
  setFilterModel(m) { this.filterModel = Object.assign({}, m); this.refresh('filter'); }
  /** Filters by words across the shown columns. @param {string} q @returns {void} */
  setQuickFilter(q) { this.quickFilter = q || ''; this.refresh('filter'); }

  /**
   * Groups rows by columns (outermost first); [] for none.
   * @param {string[]} colIds
   * @returns {void}
   */
  setGroupBy(colIds) { this.groupBy = (colIds || []).slice(); this.expanded.clear(); this.refresh('group'); }

  /**
   * Opens or closes a group.
   * @param {string[]} path its members from the top
   * @param {boolean} [open] default: the other state
   * @returns {void}
   */
  setExpanded(path, open) {
    const k = path.join(SEP), now = open === undefined ? !this.isExpanded(path) : open;
    if (now === (path.length <= this.expandLevel)) { this.expanded.delete(k); } else { this.expanded.add(k); }
    this.flatten();
    this.events.emit('rowsChanged', { reason: 'expand' });
  }

  /**
   * Whether a group is open.
   * @param {string[]} path
   * @returns {boolean}
   */
  isExpanded(path) { const byLevel = path.length <= this.expandLevel; return this.expanded.has(path.join(SEP)) ? !byLevel : byLevel; }

  /**
   * Opens every group down to a level (0: all closed; Infinity: all open).
   * @param {number} level
   * @returns {void}
   */
  expandToLevel(level) { this.expandLevel = level; this.expanded.clear(); this.flatten(); this.events.emit('rowsChanged', { reason: 'expand' }); }

  /** @private */
  value(row, col) { return getValue(row, col); }

  /**
   * Filters, sorts, groups and flattens again.
   * @param {string} [reason]
   * @returns {void}
   */
  refresh(reason) {
    const cols = this.columns.all();
    const test = rowFilter(this.filterModel, this.quickFilter, this.quickFilter ? this.columns.visible() : cols, (r, c) => this.value(r, c));
    const n = this.rows.length;
    let idx = new Uint32Array(n), k = 0;
    for (let i = 0; i < n; i++) { if (!test || test(this.rows[i])) { idx[k++] = i; } }
    if (k < n) { idx = idx.slice(0, k); }
    const sort = (this.sortModel || []).filter((s) => this.columns.get(s.colId));
    if (sort.length) { idx = this.sorted(idx, sort); }
    this.view = idx;
    this.buildGroups();
    this.flatten();
    this.events.emit('rowsChanged', { reason: reason || 'refresh' });
  }

  /** @private one typed key per row and sort column: numbers as they are, anything else as its rank among the column's distinct values
   *  (ranked once by the general order, so a million rows compare numbers, not text); NaN is a blank */
  sortKeys(idx, col) {
    const rows = this.rows, keys = new Float64Array(rows.length);
    if (col.type === 'number') {
      for (let i = 0; i < idx.length; i++) { const v = this.value(rows[idx[i]], col); keys[idx[i]] = isBlank(v) || typeof v === 'object' ? NaN : +v; }
      return keys;
    }
    const distinct = new Map();
    for (let i = 0; i < idx.length; i++) { const v = this.value(rows[idx[i]], col); if (!isBlank(v) && !distinct.has(v)) { distinct.set(v, 0); } }
    const order = Array.from(distinct.keys()).sort(compareValues);
    for (let r = 0, k = 0; r < order.length; r++) { if (r > 0 && compareValues(order[r - 1], order[r]) !== 0) { k++; } distinct.set(order[r], k); }
    for (let i = 0; i < idx.length; i++) { const v = this.value(rows[idx[i]], col); keys[idx[i]] = isBlank(v) ? NaN : distinct.get(v); }
    return keys;
  }

  /** @private sorts row numbers by typed keys (blanks last either way, ties in the rows' order); a column's own comparator: the general way */
  sorted(idx, sort) {
    const rows = this.rows, cols = sort.map((s) => this.columns.get(s.colId));
    if (cols.some((c) => c.comparator)) {
      const cmp = compareRows(sort, (id) => this.columns.get(id), (r, c) => this.value(r, c));
      return idx.sort((a, b) => cmp(rows[a], rows[b]) || a - b);
    }
    const keys = cols.map((c) => this.sortKeys(idx, c)), dirs = sort.map((s) => (s.sort === 'desc' ? -1 : 1)), n = keys.length;
    return idx.sort((a, b) => {
      for (let j = 0; j < n; j++) {
        const x = keys[j][a], y = keys[j][b];
        if (x !== x || y !== y) { if (x !== x && y !== y) { continue; } return x !== x ? 1 : -1; }
        if (x !== y) { return (x - y) * dirs[j]; }
      }
      return a - b;
    });
  }

  /** @private */
  buildGroups() {
    this.groupNodes = [];
    this.groupRoot = null;
    this.groupByKey = new Map();
    if (!this.groupBy.length) { return; }
    const cols = this.groupBy.map((id) => { const c = this.columns.get(id); if (!c) { throw new Error('no column ' + id + ' to group by'); } return c; });
    const valueCols = this.columns.all().filter((c) => c.aggFunc && c.type === 'number' && this.groupBy.indexOf(c.id) < 0);
    const root = { path: [], children: [], byKey: new Map(), rows: [] };
    const nodes = this.groupNodes;
    for (let i = 0; i < this.view.length; i++) {
      const r = this.rows[this.view[i]];
      let parent = root;
      for (let d = 0; d < cols.length; d++) {
        const v = this.value(r, cols[d]), t = memberText(v);
        let g = parent.byKey.get(t);
        if (!g) {
          g = { id: nodes.length, path: parent.path.concat([t]), depth: d, field: cols[d].id, value: isBlank(v) ? null : v, children: [], byKey: new Map(), rows: [], count: 0 };
          nodes.push(g);
          this.groupByKey.set(g.path.join(SEP), g);
          parent.byKey.set(t, g);
          parent.children.push(g);
        }
        g.count++;
        parent = g;
      }
      parent.rows.push(this.view[i]);
    }
    // figures: each group's from its own rows, by its column's aggregation; a non-additive column is the server's
    const leavesOf = (g, out) => { g.rows.forEach((x) => out.push(x)); g.children.forEach((c) => leavesOf(c, out)); return out; };
    nodes.forEach((g) => {
      const leaves = leavesOf(g, []);
      g.aggregates = {};
      g.states = {};
      g.serverCols = [];
      valueCols.forEach((c) => {
        const f = aggFunc(c.aggFunc);
        if (c.additive === false || !f || !this.clientAggregation) { g.aggregates[c.id] = null; g.serverCols.push(c.id); return; }
        let st = f.init();
        for (let j = 0; j < leaves.length; j++) { st = f.add(st, this.value(this.rows[leaves[j]], c)); }
        g.states[c.id] = st;
        g.aggregates[c.id] = f.result(st);
      });
      g.pendingServer = g.serverCols.slice();
    });
    this.groupRoot = root;
  }

  /** @private the display codes: a row number (>= 0) or a group (-(id + 1)) */
  flatten() {
    if (!this.groupRoot) { this.display = null; return; }
    const out = [];
    const walk = (g) => {
      g.children.forEach((c) => {
        out.push(-(c.id + 1));
        if (this.isExpanded(c.path)) { walk(c); }
      });
      if (g.path.length && this.isExpanded(g.path)) { g.rows.forEach((x) => out.push(x)); }
    };
    walk(this.groupRoot);
    this.display = Int32Array.from(out);
  }

  /**
   * How many rows are displayed (groups and the rows of open groups).
   * @returns {number}
   */
  rowCount() { return this.display ? this.display.length : this.view.length; }

  /**
   * How many data rows pass the filters.
   * @returns {number}
   */
  filteredCount() { return this.view.length; }

  /**
   * The row at a display index.
   * @param {number} i
   * @returns {?DisplayRow}
   */
  rowAt(i) {
    if (i < 0 || i >= this.rowCount()) { return null; }
    const code = this.display ? this.display[i] : this.view[i];
    if (code < 0) {
      const g = this.groupNodes[-code - 1];
      return { kind: 'group', id: 'group:' + g.path.join(SEP), depth: g.depth, row: null, field: g.field, value: g.value, path: g.path, count: g.count,
        expanded: this.isExpanded(g.path), aggregates: g.aggregates, pendingServer: g.pendingServer };
    }
    return { kind: 'leaf', id: this.getRowId(this.rows[code], code), depth: this.groupBy.length, row: this.rows[code], index: code };
  }

  /**
   * The display index of a row id (-1 when it is filtered out or in a closed group).
   * @param {string} id
   * @returns {number}
   */
  indexOfId(id) {
    const n = this.idIndex.get(id);
    if (n === undefined) { return -1; }
    const a = this.display || this.view;
    for (let i = 0; i < a.length; i++) { if (a[i] === n) { return i; } }
    return -1;
  }

  /**
   * A row's data by its id.
   * @param {string} id
   * @returns {?Object}
   */
  getRowById(id) { const n = this.idIndex.get(id); return n === undefined ? null : this.rows[n]; }

  /**
   * The rows passing the filters, in order (for export and selection of all).
   * @returns {Object[]}
   */
  filteredRows() { const out = new Array(this.view.length); for (let i = 0; i < this.view.length; i++) { out[i] = this.rows[this.view[i]]; } return out; }

  /**
   * The distinct members of a column among all rows (for a member filter's list), sorted, blanks last, with counts.
   * @param {string} colId
   * @param {number} [limit=10000]
   * @returns {Array<{value: *, text: string, count: number}>}
   */
  members(colId, limit) {
    const c = this.columns.get(colId), m = new Map();
    for (let i = 0; i < this.rows.length; i++) {
      const v = this.value(this.rows[i], c), t = memberText(v), e = m.get(t);
      if (e) { e.count++; } else { m.set(t, { value: isBlank(v) ? null : v, text: t, count: 1 }); }
    }
    const coll = typeof Intl !== 'undefined' ? new Intl.Collator(undefined, { numeric: true }) : null;
    return Array.from(m.values()).sort((a, b) => (a.value === null ? 1 : b.value === null ? -1 : typeof a.value === 'number' && typeof b.value === 'number' ? a.value - b.value : coll.compare(a.text, b.text)))
      .slice(0, limit || 10000);
  }
}
