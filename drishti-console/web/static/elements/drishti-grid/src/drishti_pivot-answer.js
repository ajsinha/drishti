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
/* The pivot's answer after it is made (drishti_pivot.js makes it): figures changed by difference for rows that changed, the server's
   non-additive figures merged in by coordinates, value filters, the result columns, a cell's value and a measure's text. */
import { passes, columnScale, formatScaled } from '../../drishti-core/src/drishti_index.js';
import { SEP, memberText } from './drishti_pivot.js';

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
    const path = c.path && c.path.length ? c.path.map((p) => (p[1] === null ? c.label : p[1])) : c.kind === 'grand' ? [] : [c.label];   // an answer without paths: its label
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
    || (m && (m.unit === 'count' || ((m.unit === null || m.unit === undefined) && /^count/.test(m.id || ''))));   // no unit: a count by its function
  if (whole) { return Math.round(v).toLocaleString('en-US'); }
  if (m && m.scale) { return formatScaled(v, m.scale); }        // the column's one unit (answerScales): every figure of the measure in it
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

/**
 * Each measure's one unit over an answer (one number format per column: "74.63 m" beside "0.84 m"): drishti-core's columnScale of every
 * cell's figure of the measure. A count, a percentage, a rank and a text answer keep their own forms (no scale).
 * @param {Object} answer the server's answer ({measures, cells})
 * @returns {Object<string, {unit: string, size: number}>} measure label to its scale
 */
export function answerScales(answer) {
  const out = {};
  const cells = (answer && answer.cells) || {};
  ((answer && answer.measures) || []).forEach((m) => {
    if (m.showAs || m.unit === 'count' || m.unit === 'pct' || m.unit === 'rate' || ((m.unit === null || m.unit === undefined) && /^count/.test(m.id || ''))) { return; }
    const vals = [];
    Object.keys(cells).forEach((k) => { const c = cells[k]; if (c && c.values) { vals.push(c.values[m.label]); } });
    out[m.label] = columnScale(vals);
  });
  return out;
}
