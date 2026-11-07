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
/* The pivot model. One view and one answer, the server's (PIVOT_ENGINE.md section 4; PivotView and PivotAnswer in drishti-bicompute):
   the browser builds the same view a server pivot request sends (rows, columns, values, member filters, open nodes, sort, top N,
   subtotals above or below), and pivotLocal answers it for rows the page holds in the same shape the server answers it: the row and
   column entries (grand, node, header, total, others) and one cell per pair, each with its figures by measure label and its members.
   So a grid draws either answer the same way, and a non-additive measure (VaR, ES, a ratio) in a local pivot is left for the server:
   its cells are null with a note and listed in `pending`; serverRequest() asks for exactly those and mergeAnswer() puts the server's
   figures in, by coordinates, never a sum made in the browser.

   Value filters (keep the members of a level whose figure passes, "Desk where Notional > 1m") are the browser's: locally the figures
   are recomputed without the members left out; on a server answer the rows are hidden and the totals say they include them. */
import { aggFunc, parseMeasure, isServerMeasure } from './drishti_aggregate.js';
import { passes } from '../../drishti-core/src/drishti_index.js';

/** The unit separator joining a path's values in a key. */
export const SEP = '\u001f';
/** A blank member's text, as the server writes it. */
export const BLANK = '(blank)';

const PLAIN = typeof Intl !== 'undefined' ? new Intl.NumberFormat('en-US', { useGrouping: false, maximumSignificantDigits: 15 }) : null;
/**
 * A value as a member: text; null, missing and empty text are "(blank)"; whole numbers without ".0" (the server's and the console's).
 * @param {*} v
 * @returns {string}
 */
export function memberText(v) {
  if (v === null || v === undefined || v === '') { return BLANK; }
  if (typeof v === 'number') { return !isFinite(v) ? BLANK : v === 0 ? '0' : PLAIN ? PLAIN.format(v) : String(Number(v.toPrecision(15))); }
  if (typeof v === 'boolean') { return v ? 'true' : 'false'; }
  if (v instanceof Date) { return v.toISOString().slice(0, 10); }
  return String(v);
}

/**
 * @typedef {Object} PivotValue
 * @property {string} measure the function's text: sum(NOTIONAL), count(), var(99, 1d)
 * @property {string} [as] its label (default: the text)
 */

/**
 * @typedef {Object} PivotView
 * @property {string} [source] the server's source (a local pivot does not need it)
 * @property {string[]} rows row dimensions, outermost first
 * @property {string[]} columns column dimensions
 * @property {PivotValue[]} values
 * @property {Object<string, {in: string[], notIn: string[]}>} [filters] member filters
 * @property {Array<{axis: string, path: Array<Array<string>>, by: ?string}>} [expanded] the open nodes
 * @property {{rows: number, columns: number}} [expandTo] every node open down to this level (1: level one only)
 * @property {Object<string, {by: string, desc: boolean}>} [sort] per dimension: by "label" or a measure's label
 * @property {Object<string, {n: number, by: string}>} [top] per dimension: its top n, the rest in Others
 * @property {('below'|'above')} [subtotals='below']
 * @property {Array<{dim: string, by: string, op: string, value: *, valueTo: *}>} [valueFilters] keep a level's members whose figure passes
 * @property {Object<string, string>} [options] the server provider's options
 */

/**
 * A value's label (its as, else the measure's text).
 * @param {PivotValue} v
 * @returns {string}
 */
export function valueLabel(v) { return v.as || v.measure; }

/**
 * A view with every default filled in, checked: rows and columns do not repeat a dimension, at least one value.
 * @param {PivotView} view
 * @returns {PivotView}
 */
export function normalizeView(view) {
  const v = Object.assign({ rows: [], columns: [], values: [], filters: {}, expanded: [], expandTo: { rows: 1, columns: 1 }, sort: {}, top: {},
    subtotals: 'below', valueFilters: [] }, view);
  const seen = {};
  v.rows.concat(v.columns).forEach((d) => { if (seen[d]) { throw new Error(d + ' is on the rows or columns twice'); } seen[d] = 1; });
  if (!v.values.length) { throw new Error('a pivot needs at least one value: {measure: "sum(NOTIONAL)"}'); }
  v.subtotals = v.subtotals === 'above' ? 'above' : 'below';
  v.expandTo = { rows: Math.max(1, (v.expandTo && v.expandTo.rows) || 1), columns: Math.max(1, (v.expandTo && v.expandTo.columns) || 1) };
  return v;
}

/**
 * The body of a server pivot request (POST /api/pivot/query on the console): {view} in the server's PivotView shape. Only the server's
 * fields go (valueFilters are the browser's).
 * @param {PivotView} view
 * @param {{only: string[]}} [o] only: the labels of the values to ask (the non-additive ones a local pivot left)
 * @returns {{view: Object}}
 */
export function serverRequest(view, o) {
  const v = normalizeView(view);
  const values = o && o.only ? v.values.filter((x) => o.only.indexOf(valueLabel(x)) >= 0) : v.values;
  const out = { rows: v.rows, columns: v.columns, values: values.map((x) => Object.assign({ measure: x.measure }, x.as ? { as: x.as } : {}, x.showAs ? { showAs: x.showAs } : {})),
    filters: v.filters, expanded: v.expanded, expandTo: v.expandTo, sort: v.sort, top: v.top, subtotals: v.subtotals };
  ['source', 'date', 'asOf', 'options', 'currency', 'slicers'].forEach((k) => { if (v[k] !== undefined) { out[k] = v[k]; } });
  return { view: out };
}

/**
 * Translates a grid filter model into the server's member filters ({in, notIn} per dimension); a filter the server cannot take
 * (text contains, number ranges) is listed in `unsupported`, in words, never dropped silently.
 * @param {Object<string, Object>} model column id to a grid filter
 * @returns {{filters: Object<string, {in: string[], notIn: string[]}>, unsupported: string[]}}
 */
export function memberFilters(model) {
  const filters = {}, unsupported = [];
  Object.keys(model || {}).forEach((k) => {
    const f = model[k];
    if (!f) { return; }
    if (f.type === 'set' && f.values) { filters[k] = { in: f.values.map(memberText), notIn: [] }; }
    else if (f.type === 'set' && f.excluded) { filters[k] = { in: [], notIn: f.excluded.map(memberText) }; }
    else if ((f.type === 'text' || !f.type) && f.op === 'equals') { filters[k] = { in: [String(f.value)], notIn: [] }; }
    else if ((f.type === 'text' || !f.type) && f.op === 'notEquals') { filters[k] = { in: [], notIn: [String(f.value)] }; }
    else { unsupported.push(k + ': the server pivot filters by members only (in or not in)'); }
  });
  return { filters: filters, unsupported: unsupported };
}

function keyOf(path) { return path.join(SEP); }

/** An axis's tree: nodes by key with children in order, from the members seen. */
function Axis(dims) {
  this.dims = dims;
  this.nodes = new Map();
  this.root = { key: '', path: [], depth: 0, children: [], childKeys: new Set() };
  this.nodes.set('', this.root);
}
Axis.prototype.touch = function (values, depth) {
  let parent = this.root, key = '';
  for (let i = 0; i < depth; i++) {
    key = i === 0 ? values[0] : key + SEP + values[i];
    let n = this.nodes.get(key);
    if (!n) {
      n = { key: key, path: values.slice(0, i + 1), depth: i + 1, children: [], childKeys: new Set(), parent: parent };
      this.nodes.set(key, n);
    }
    if (!parent.childKeys.has(key)) { parent.childKeys.add(key); parent.children.push(n); }
    parent = n;
  }
};

/**
 * The open state of a pivot's nodes, rows and columns: which members are expanded (by path), what level every node is opened to, and
 * the change of either; toView() puts it in a view. A collapsed column group shows only its total.
 */
export class PivotState {
  /** @param {{expanded: Array, expandTo: Object}} [view] the state a view holds */
  constructor(view) {
    this.expandTo = { rows: 1, columns: 1 };
    this.open = { rows: new Map(), columns: new Map() };
    this.closed = { rows: new Set(), columns: new Set() };
    if (view) {
      if (view.expandTo) { this.expandTo = { rows: view.expandTo.rows || 1, columns: view.expandTo.columns || 1 }; }
      (view.expanded || []).forEach((e) => this.open[e.axis === 'columns' ? 'columns' : 'rows'].set(keyOf(e.path.map((p) => p[1])), e));
    }
  }

  /**
   * Whether a node is open.
   * @param {('rows'|'columns')} axis
   * @param {string[]} path the node's member values from the top
   * @returns {boolean}
   */
  isOpen(axis, path) {
    const k = keyOf(path);
    if (this.closed[axis].has(k)) { return false; }
    return path.length < this.expandTo[axis] || this.open[axis].has(k);
  }

  /**
   * Opens or closes a node.
   * @param {('rows'|'columns')} axis
   * @param {Array<Array<string>>} pairs the node's path as (dimension, value) pairs
   * @param {boolean} [open] default: the other state
   * @param {string} [by] expand by this dimension (expand by..., per member)
   * @returns {boolean} the new state
   */
  toggle(axis, pairs, open, by) {
    const path = pairs.map((p) => p[1]), k = keyOf(path);
    const now = open === undefined ? !this.isOpen(axis, path) : open;
    if (now) {
      this.closed[axis].delete(k);
      if (path.length >= this.expandTo[axis] || by) { this.open[axis].set(k, by ? { axis: axis, path: pairs, by: by } : { axis: axis, path: pairs }); }
    } else {
      this.open[axis].delete(k);
      if (path.length < this.expandTo[axis]) { this.closed[axis].add(k); }
    }
    return now;
  }

  /**
   * Opens every node of an axis down to a level (1: only level one shown), forgetting single opens and closes on that axis.
   * @param {('rows'|'columns')} axis
   * @param {number} level
   * @returns {void}
   */
  expandToLevel(axis, level) {
    this.expandTo[axis] = Math.max(1, level | 0);
    this.open[axis].clear();
    this.closed[axis].clear();
  }

  /**
   * The state in a view's terms (expanded, expandTo). A node closed below expandTo has no server form: the view opens to level one and
   * names the open nodes it knows (from an answer's entries).
   * @param {{rows: Array, columns: Array}} [known] the entries of the last answer, to name the nodes expandTo opened
   * @returns {{expanded: Array, expandTo: {rows: number, columns: number}}}
   */
  toView(known) {
    const expanded = [], expandTo = { rows: this.expandTo.rows, columns: this.expandTo.columns };
    ['rows', 'columns'].forEach((axis) => {
      if (this.closed[axis].size && known && known[axis]) {
        expandTo[axis] = 1;
        known[axis].forEach((e) => {
          const path = (e.path || []).map((p) => p[1]);
          if (path.length && path.length < this.expandTo[axis] && !this.closed[axis].has(keyOf(path)) && e.kind !== 'others') { expanded.push({ axis: axis, path: e.path }); }
        });
      }
      this.open[axis].forEach((e) => expanded.push(e));
    });
    return { expanded: expanded, expandTo: expandTo };
  }
}

function sortChildren(nodes, dim, view, figure) {
  const s = (view.sort || {})[dim];
  const byLabel = !s || !s.by || s.by === 'label';
  const coll = typeof Intl !== 'undefined' ? new Intl.Collator(undefined, { numeric: true }) : null;
  nodes.sort((a, b) => {
    let c;
    if (byLabel) {
      const x = a.path[a.path.length - 1], y = b.path[b.path.length - 1];
      c = x === BLANK ? (y === BLANK ? 0 : 1) : y === BLANK ? -1 : coll ? coll.compare(x, y) : x < y ? -1 : x > y ? 1 : 0;
    } else {
      const x = figure(a, s.by), y = figure(b, s.by);
      c = x === null ? (y === null ? 0 : 1) : y === null ? -1 : x - y;
    }
    return s && s.desc ? -c : c;
  });
}

/**
 * A pivot of rows the page holds, answered in the server's shape.
 * @param {Object[]} records the rows
 * @param {PivotView} view
 * @param {Object} [o]
 * @param {function(Object, string): *} [o.value] a record's value of a field (default: record[field])
 * @param {function(string): ?Object} [o.column] a field's column, for its additive flag
 * @param {PivotState} [o.state] the open state (default: the view's)
 * @returns {Object} {measures, rowDimensions, columnDimensions, rows, columns, cells, pending, computedBy: 'browser', notes}
 */
export function pivotLocal(records, view, o) {
  o = o || {};
  const v = normalizeView(view);
  const val = o.value || ((r, f) => r[f]);
  const state = o.state || new PivotState(v);
  const measures = v.values.map((x, i) => {
    const p = parseMeasure(x.measure), server = isServerMeasure(x.measure, o.column);
    return { index: i, id: x.measure, label: valueLabel(x), func: server ? null : aggFunc(p.func), field: p ? p.field : null, server: server,
      kind: server ? 'server' : 'mergeable', exact: true };
  });
  const local = measures.filter((m) => !m.server);
  const R = v.rows.length, C = v.columns.length;
  const filters = Object.keys(v.filters || {}).map((d) => [d, v.filters[d]]);
  const keep = (r) => filters.every(([d, f]) => {
    const t = memberText(val(r, d));
    return (!f.in || !f.in.length || f.in.indexOf(t) >= 0) && (!f.notIn || f.notIn.indexOf(t) < 0);
  });

  function run(excluded) {
    const rowsAxis = new Axis(v.rows), colsAxis = new Axis(v.columns);
    const cells = new Map();
    const rv = new Array(R), cv = new Array(C);
    const addTo = (rk, ck, rec) => {
      const k = rk + '|' + ck;
      let c = cells.get(k);
      if (!c) { c = { members: 0, states: local.map((m) => m.func.init()) }; cells.set(k, c); }
      c.members++;
      for (let i = 0; i < local.length; i++) { const m = local[i]; c.states[i] = m.func.add(c.states[i], m.field ? val(rec, m.field) : 1); }
    };
    const rk = new Array(R + 1), ck = new Array(C + 1);
    for (let n = 0; n < records.length; n++) {
      const rec = records[n];
      if (filters.length && !keep(rec)) { continue; }
      for (let i = 0; i < R; i++) { rv[i] = memberText(val(rec, v.rows[i])); }
      for (let i = 0; i < C; i++) { cv[i] = memberText(val(rec, v.columns[i])); }
      if (excluded && excluded(rv)) { continue; }
      rowsAxis.touch(rv, R);
      colsAxis.touch(cv, C);
      rk[0] = ''; for (let i = 0; i < R; i++) { rk[i + 1] = i === 0 ? rv[0] : rk[i] + SEP + rv[i]; }
      ck[0] = ''; for (let i = 0; i < C; i++) { ck[i + 1] = i === 0 ? cv[0] : ck[i] + SEP + cv[i]; }
      for (let i = 0; i <= R; i++) { for (let j = 0; j <= C; j++) { addTo(rk[i], ck[j], rec); } }
    }
    return { rowsAxis: rowsAxis, colsAxis: colsAxis, cells: cells };
  }

  const figureOf = (cells, rowKey, colKey, label) => {
    const li = local.findIndex((m) => m.label === label);
    if (li < 0) { return null; }
    const c = cells.get(rowKey + '|' + colKey);
    return c ? local[li].func.result(c.states[li]) : null;
  };

  let comp = run(null);
  const notes = [];
  if (v.valueFilters.length) {
    const drop = [];
    v.valueFilters.forEach((f) => {
      const level = v.rows.indexOf(f.dim);
      if (level < 0) { throw new Error('a value filter on ' + f.dim + ', which is not a row dimension'); }
      if (local.findIndex((m) => m.label === f.by) < 0) { notes.push('the value filter on ' + f.dim + ' by ' + f.by + ' needs the server (a non-additive measure)'); return; }
      comp.rowsAxis.nodes.forEach((n) => {
        if (n.depth === level + 1 && !passes(figureOf(comp.cells, n.key, '', f.by), { type: 'number', op: f.op, value: f.value, valueTo: f.valueTo })) { drop.push(n.path); }
      });
    });
    if (drop.length) {
      const set = new Set(drop.map(keyOf));
      comp = run((rv) => { for (let i = 1; i <= rv.length; i++) { if (set.has(keyOf(rv.slice(0, i)))) { return true; } } return false; });
    }
  }
  const { rowsAxis, colsAxis, cells } = comp;

  // top N per level: the rest merged into one Others member, its figures from its own members' states (exact for every browser function)
  const others = new Map();
  const applyTop = (axis, dims, axisName) => {
    axis.nodes.forEach((n) => {
      if (n.depth >= dims.length) { return; }
      const dim = dims[n.depth], t = (v.top || {})[dim];
      sortChildren(n.children, dim, v, (x, by) => (axisName === 'rows' ? figureOf(cells, x.key, '', by) : figureOf(cells, '', x.key, by)));
      if (!t || !t.n || n.children.length <= t.n) { return; }
      const by = t.by || (local[0] && local[0].label);
      const ranked = n.children.slice().sort((a, b) => {
        const x = axisName === 'rows' ? figureOf(cells, a.key, '', by) : figureOf(cells, '', a.key, by);
        const y = axisName === 'rows' ? figureOf(cells, b.key, '', by) : figureOf(cells, '', b.key, by);
        return (y === null ? -Infinity : y) - (x === null ? -Infinity : x);
      });
      const kept = new Set(ranked.slice(0, t.n)), rest = ranked.slice(t.n);
      const okey = (n.key ? n.key + SEP : '') + '\u0000Others';
      const o2 = { key: okey, path: n.path.concat(['\u0000Others']), depth: n.depth + 1, children: [], others: rest.length, parent: n, levelMembers: n.children.length };
      n.children = n.children.filter((c) => kept.has(c)).concat([o2]);
      others.set(okey, rest);
      axis.nodes.set(okey, o2);
      // the Others cells: the merge of its members' cells, against every key of the other axis
      const otherKeys = new Set();
      cells.forEach((c, k) => {
        const [a, b] = k.split('|');
        rest.forEach((m) => { if ((axisName === 'rows' ? a : b) === m.key) { otherKeys.add(axisName === 'rows' ? b : a); } });
      });
      otherKeys.forEach((ok) => {
        let acc = null;
        rest.forEach((m) => {
          const c = cells.get(axisName === 'rows' ? m.key + '|' + ok : ok + '|' + m.key);
          if (!c) { return; }
          acc = acc ? { members: acc.members + c.members, states: acc.states.map((s, i) => local[i].func.merge(s, c.states[i])) } : { members: c.members, states: c.states.slice() };
        });
        if (acc) { cells.set(axisName === 'rows' ? okey + '|' + ok : ok + '|' + okey, acc); }
      });
    });
  };
  applyTop(rowsAxis, v.rows, 'rows');
  applyTop(colsAxis, v.columns, 'columns');

  const pairs = (dims, path) => path.map((x, i) => [dims[i], x.charAt(0) === '\u0000' ? null : x]);
  const coords = (dims, path) => { const c = {}; path.forEach((x, i) => { c[dims[i]] = x.charAt(0) === '\u0000' ? '*others*' : x; }); return c; };
  const entry = (axis, dims, n, kind, axisName) => {
    const isOthers = n.others !== undefined;
    const value = n.path.length ? n.path[n.path.length - 1] : null;
    const label = !n.path.length ? 'Grand total' : isOthers ? 'Others (' + n.others + ' of ' + n.levelMembers + ')' : kind === 'total' ? value + ' total' : value;
    return { key: n.key, kind: kind, depth: n.depth, label: label, dim: n.path.length ? dims[n.path.length - 1] : null, value: isOthers ? null : value,
      path: pairs(dims, n.path), coords: coords(dims, n.path), expandable: !isOthers && n.path.length > 0 && n.depth < dims.length,
      expanded: !isOthers && n.path.length > 0 && n.depth < dims.length && state.isOpen(axisName, n.path), others: isOthers ? n.others : undefined };
  };
  const rows = [], cols = [];
  const above = v.subtotals === 'above';
  const emitRows = (n) => {
    if (!n.path.length) {
      if (above || !n.children.length) { rows.push(entry(rowsAxis, v.rows, n, 'grand', 'rows')); }
      n.children.forEach(emitRows);
      if (!above && n.children.length) { rows.push(entry(rowsAxis, v.rows, n, 'grand', 'rows')); }
      return;
    }
    const kind = n.others !== undefined ? 'others' : 'node';
    const open = n.others === undefined && n.children.length && state.isOpen('rows', n.path);
    if (open) {
      if (above) { rows.push(entry(rowsAxis, v.rows, n, kind, 'rows')); n.children.forEach(emitRows); }
      else { rows.push(entry(rowsAxis, v.rows, n, 'header', 'rows')); n.children.forEach(emitRows); rows.push(entry(rowsAxis, v.rows, n, 'total', 'rows')); }
    } else { rows.push(entry(rowsAxis, v.rows, n, kind, 'rows')); }
  };
  const emitCols = (n) => {
    if (!n.path.length) { n.children.forEach(emitCols); cols.push(entry(colsAxis, v.columns, n, 'grand', 'columns')); return; }
    const open = n.others === undefined && n.children.length && state.isOpen('columns', n.path);
    if (open) { n.children.forEach(emitCols); cols.push(entry(colsAxis, v.columns, n, 'total', 'columns')); }
    else { cols.push(entry(colsAxis, v.columns, n, n.others !== undefined ? 'others' : 'node', 'columns')); }
  };
  emitRows(rowsAxis.root);
  emitCols(colsAxis.root);

  const out = {}, pending = [];
  rows.forEach((r) => {
    if (r.kind === 'header') { return; }
    cols.forEach((c) => {
      const cell = cells.get(r.key + '|' + c.key);
      if (!cell) { return; }
      const values = {}, cellNotes = {};
      measures.forEach((m) => {
        if (m.server) {
          values[m.label] = null;
          cellNotes[m.label] = 'asked of the server (a non-additive measure is never added up in the browser)';
          pending.push({ row: r.key, column: c.key, coords: Object.assign({}, r.coords, c.coords), measure: m.label });
        } else {
          const li = local.indexOf(m), x = m.func.result(cell.states[li]);
          values[m.label] = typeof x === 'number' && !isFinite(x) ? null : x;
        }
      });
      const e = { values: values, members: cell.members, coords: Object.assign({}, r.coords, c.coords) };
      if (Object.keys(cellNotes).length) { e.notes = cellNotes; }
      out[r.key + '|' + c.key] = e;
    });
  });
  const answer = { measures: measures.map((m) => ({ index: m.index, id: m.id, label: m.label, kind: m.kind, plainSum: !!(m.func && m.func.name === 'sum'), exact: true, server: m.server })),
    rowDimensions: v.rows, columnDimensions: v.columns, rows: rows, columns: cols, cells: out, pending: pending, computedBy: 'browser', notes: notes };
  // what an update needs to change the figures by difference (not part of the answer's shape: not enumerable)
  Object.defineProperty(answer, 'incremental', { enumerable: false, value: {
    cells: cells, local: local, measures: measures, keep: filters.length ? keep : null, val: val, view: v,
    invertible: !v.valueFilters.length && !Object.keys(v.top || {}).length && local.every((m) => ['sum', 'avg', 'count'].indexOf(m.func.name) >= 0)
  } });
  return answer;
}

/**
 * Changes a local pivot's figures for rows that changed (pivotLocal's answer, in place): each changed row's old values out of every
 * cell it is in, its new ones in, by difference: exact for sums, counts and averages. The other cases (a row moved to another member, a
 * min or max, top N, value filters) answer false: compute the pivot again. Non-additive measures are untouched: ask the server again.
 * @param {Object} answer pivotLocal's answer
 * @param {Array<{old: Object, value: Object}>} updates each changed row before and after
 * @returns {{applied: boolean, cells: string[]}} applied false: compute again; cells: the keys of the cells whose figures changed
 */
export function pivotApplyUpdates(answer, updates) {
  const inc = answer && answer.incremental;
  if (!inc || !inc.invertible) { return { applied: false, cells: [] }; }
  const v = inc.view, R = v.rows.length, C = v.columns.length, keysOf = (rec, dims) => {
    const out = [''];
    let k = '';
    for (let i = 0; i < dims.length; i++) { k = i === 0 ? memberText(inc.val(rec, dims[0])) : k + SEP + memberText(inc.val(rec, dims[i])); out.push(k); }
    return out;
  };
  const plan = [];
  for (const u of updates) {
    const inOld = !inc.keep || inc.keep(u.old), inNew = !inc.keep || inc.keep(u.value);
    if (inOld !== inNew) { return { applied: false, cells: [] }; }
    if (!inOld) { continue; }
    const ro = keysOf(u.old, v.rows), rn = keysOf(u.value, v.rows), co = keysOf(u.old, v.columns), cn = keysOf(u.value, v.columns);
    if (ro[R] !== rn[R] || co[C] !== cn[C]) { return { applied: false, cells: [] }; }
    plan.push({ rk: rn, ck: cn, u: u });
  }
  const touched = new Set();
  plan.forEach((p) => {
    for (let i = 0; i <= R; i++) {
      for (let j = 0; j <= C; j++) {
        const key = p.rk[i] + '|' + p.ck[j], c = inc.cells.get(key);
        if (!c) { continue; }
        inc.local.forEach((m, li) => {
          const o = m.field ? inc.val(p.u.old, m.field) : 1, n = m.field ? inc.val(p.u.value, m.field) : 1;
          if (m.func.name === 'count') { const has = (x) => x !== undefined && x !== null && x !== ''; c.states[li] += (has(n) ? 1 : 0) - (has(o) ? 1 : 0); return; }
          const st = c.states[li], on = typeof o === 'number' && isFinite(o), nn = typeof n === 'number' && isFinite(n);
          st.s += (nn ? n : 0) - (on ? o : 0);
          st.n += (nn ? 1 : 0) - (on ? 1 : 0);
        });
        touched.add(key);
      }
    }
  });
  touched.forEach((key) => {
    const e = answer.cells[key], c = inc.cells.get(key);
    if (!e) { return; }
    inc.local.forEach((m, li) => { e.values[m.label] = m.func.result(c.states[li]); });
  });
  return { applied: true, cells: Array.from(touched) };
}

function coordsId(c) { return Object.keys(c || {}).sort().map((k) => k + '=' + c[k]).join(SEP); }

/**
 * Puts a server answer's figures into a local answer's pending cells, matched by coordinates; the cells the server did not answer keep
 * their note. Returns how many figures came in.
 * @param {Object} local pivotLocal's answer (changed in place)
 * @param {Object} server the server's answer to serverRequest(view, {only: the pending labels})
 * @returns {number}
 */
export function mergeAnswer(local, server) {
  const by = new Map();
  Object.keys(server.cells || {}).forEach((k) => { const c = server.cells[k]; by.set(coordsId(c.coords), c); });
  let n = 0;
  local.pending = local.pending.filter((p) => {
    const s = by.get(coordsId(p.coords)), cell = local.cells[p.row + '|' + p.column];
    if (!s || !s.values || !(p.measure in s.values)) { return true; }
    cell.values[p.measure] = s.values[p.measure];
    if (cell.notes) {
      if (s.notes && s.notes[p.measure]) { cell.notes[p.measure] = s.notes[p.measure]; } else { delete cell.notes[p.measure]; }
      if (!Object.keys(cell.notes).length) { delete cell.notes; }
    }
    n++;
    return false;
  });
  return n;
}

/**
 * Hides the rows of a server answer whose members fail a value filter (with their children); the totals still include them and the
 * answer's notes say so.
 * @param {Object} answer a server answer (changed in place)
 * @param {Array<{dim: string, by: string, op: string, value: *, valueTo: *}>} valueFilters
 * @returns {number} how many rows were hidden
 */
export function applyValueFilters(answer, valueFilters) {
  if (!answer || !valueFilters || !valueFilters.length) { return 0; }
  const grand = answer.columns.find((c) => c.kind === 'grand');
  const hidden = [];
  answer.rows.forEach((r) => {
    if (r.kind !== 'node' && r.kind !== 'header') { return; }
    valueFilters.forEach((f) => {
      if (r.dim !== f.dim) { return; }
      const cell = answer.cells[r.key + '|' + (grand ? grand.key : '')];
      const x = cell && cell.values ? cell.values[f.by] : null;
      if (!passes(x, { type: 'number', op: f.op, value: f.value, valueTo: f.valueTo })) { hidden.push(r.path); }
    });
  });
  const under = (p) => hidden.some((h) => h.length <= p.length && h.every((x, i) => p[i] && p[i][0] === x[0] && p[i][1] === x[1]));
  const before = answer.rows.length;
  answer.rows = answer.rows.filter((r) => !(r.path && r.path.length && under(r.path)));
  if (before > answer.rows.length) { (answer.notes = answer.notes || []).push('value filters hide ' + (before - answer.rows.length) + ' rows; the totals include them'); }
  return before - answer.rows.length;
}

/**
 * The grid's columns of a pivot answer: the row heading column, then one column per (column entry, value): a column member's figures,
 * a collapsed group's total, the grand total; each with its heading path (the members from the top, then the value's label).
 * @param {Object} answer
 * @param {{hideTotals: boolean}} [o] hideTotals: leave out the subtotal and grand total columns
 * @returns {Array<{id: string, colKey: string, measure: string, headerPath: string[], kind: string, depth: number, server: boolean}>}
 */
export function resultColumns(answer, o) {
  const out = [];
  const server = {};
  (answer.measures || []).forEach((m) => { server[m.label] = !!m.server || m.kind === 'holistic' || m.kind === 'server'; });
  answer.columns.forEach((c) => {
    if (o && o.hideTotals && (c.kind === 'total' || c.kind === 'grand') && answer.columns.length > 1) { return; }
    const path = (c.path || []).map((p) => (p[1] === null ? c.label : p[1]));
    if (c.kind === 'total') { path[path.length - 1] = path[path.length - 1] + ' total'; }
    if (c.kind === 'grand') { path.push('Grand total'); }
    (answer.measures || []).forEach((m) => {
      out.push({ id: c.key + '::' + m.label, colKey: c.key, measure: m.label, headerPath: path.concat([m.label]), kind: c.kind, depth: c.depth || 0, server: server[m.label] });
    });
  });
  return out;
}

/**
 * A cell's figure in an answer.
 * @param {Object} answer
 * @param {string} rowKey
 * @param {string} colKey
 * @param {string} measure the value's label
 * @returns {{value: *, note: ?string, members: number}}
 */
export function cellValue(answer, rowKey, colKey, measure) {
  const c = answer.cells[rowKey + '|' + colKey];
  if (!c) { return { value: null, note: null, members: 0 }; }
  return { value: c.values ? c.values[measure] : null, note: c.notes ? c.notes[measure] || null : null, members: c.members || 0 };
}

/**
 * A server answer's figure as the pivot shows it, by the answer's own measure fields (it formats, never computes): a text answer
 * (string_agg, the mode of names) as its text; percentages for a pct show-as; whole numbers for a rank, an ntile bucket and a measure
 * whose unit is count; bn and m for large figures; two decimals otherwise. A grid takes it as its pivotFormat (answerFormat).
 * @param {*} v
 * @param {{id: string, unit: ?string, showAs: ?string}} [m] the answer's measure
 * @returns {string}
 */
export function measureText(v, m) {
  if (typeof v === 'string') { return v; }
  if (v === null || v === undefined || typeof v !== 'number' || !isFinite(v)) { return ''; }
  const showAs = m && m.showAs ? String(m.showAs) : '';
  if (showAs.indexOf('pct') === 0) { return (v * 100).toFixed(2) + '%'; }
  const whole = showAs === 'rank' || showAs.indexOf('ntile:') === 0
    || (m && (m.unit === 'count' || ((m.unit === null || m.unit === undefined) && /^count|^trades/.test(m.id || ''))));   // no unit: an answer of before units
  if (whole) { return Math.round(v).toLocaleString('en-US'); }
  const a = Math.abs(v);
  if (a >= 1e9) { return (v / 1e9).toFixed(2) + ' bn'; }
  if (a >= 1e6) { return (v / 1e6).toFixed(2) + ' m'; }
  return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * The format of an answer's measure for GridOptions.pivotFormat: measureText bound to the measure.
 * @param {{id: string, unit: ?string, showAs: ?string}} m
 * @returns {function(*): string}
 */
export function answerFormat(m) { return (v) => measureText(v, m); }
