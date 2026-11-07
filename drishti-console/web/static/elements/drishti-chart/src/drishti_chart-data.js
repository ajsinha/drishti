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
/* A chart's data, without a page: the rows a spec names (inline objects, Vega-style values, or a server's answer of columns and rows),
   a field's values, the members of a field in order, the bins of a histogram, the five figures of a box, the quantile classes of a map,
   and the one small expression language a spec's conditions use ("hypothetical < -var99", Vega-Lite's "datum.MTM < 0"): read by a
   parser, never by eval, so a page with a strict content security policy runs it. */

/**
 * The rows of a spec's data as objects.
 * @param {*} data an array of objects, {rows: [...]} (objects, or arrays with columns), {values: [...]} or a server answer
 *   {columns: [names or {name}], rows: [[...]]}
 * @returns {Object[]}
 */
export function rowsOf(data) {
  if (!data) { return []; }
  if (Array.isArray(data)) { return data.slice(); }
  const rows = data.rows || data.values || [];
  if (data.columns && rows.length && Array.isArray(rows[0])) {
    const names = data.columns.map((c) => (typeof c === 'string' ? c : c.name || c.field || c.header));
    return rows.map((r) => { const o = {}; names.forEach((n, i) => { o[n] = r[i]; }); return o; });
  }
  return rows.slice();
}

/**
 * The name of the field an encoding names: "DESK", {field: "DESK"}, or null.
 * @param {*} enc
 * @returns {?string}
 */
export function fieldOf(enc) {
  if (!enc) { return null; }
  if (typeof enc === 'string') { return enc; }
  return enc.field || null;
}

/**
 * A number, or null when the value is not one.
 * @param {*} v
 * @returns {?number}
 */
export function num(v) {
  if (v === null || v === undefined || v === '' || typeof v === 'boolean') { return null; }
  const n = typeof v === 'number' ? v : Number(v);
  return isFinite(n) ? n : null;
}

/**
 * The members of a field in the order they first appear (or the order given; members not in it follow).
 * @param {Object[]} rows
 * @param {string} field
 * @param {string[]} [order]
 * @returns {string[]}
 */
export function members(rows, field, order) {
  const seen = new Set(), out = [];
  rows.forEach((r) => { const v = r[field] === null || r[field] === undefined ? '' : String(r[field]); if (!seen.has(v)) { seen.add(v); out.push(v); } });
  if (!order) { return out; }
  const first = order.map(String).filter((m) => seen.has(m));
  return first.concat(out.filter((m) => first.indexOf(m) < 0));
}

/**
 * The sum of a field over rows grouped by keys (a wide table from long rows): {key: sum}.
 * @param {Object[]} rows
 * @param {function(Object): string} key
 * @param {string} field
 * @returns {Map<string, number>}
 */
export function sumBy(rows, key, field) {
  const m = new Map();
  rows.forEach((r) => { const v = num(r[field]); if (v === null) { return; } const k = key(r); m.set(k, (m.get(k) || 0) + v); });
  return m;
}

/**
 * The bins of a histogram: equal widths over the values' range (or the extent given), each with its count.
 * @param {number[]} values
 * @param {{bins: number, extent: number[]}} [o] bins default 20 (Sturges' rule when "auto")
 * @returns {Array<{x0: number, x1: number, count: number}>}
 */
export function bin(values, o) {
  o = o || {};
  const xs = values.map(num).filter((v) => v !== null);
  if (!xs.length) { return []; }
  let lo = o.extent ? o.extent[0] : Math.min.apply(null, xs), hi = o.extent ? o.extent[1] : Math.max.apply(null, xs);
  if (hi === lo) { lo -= 0.5; hi += 0.5; }
  const n = o.bins === 'auto' || !o.bins ? (o.bins === 'auto' ? Math.ceil(Math.log2(xs.length) + 1) : 20) : Math.max(1, Math.round(o.bins));
  const w = (hi - lo) / n, out = [];
  for (let i = 0; i < n; i++) { out.push({ x0: lo + i * w, x1: i === n - 1 ? hi : lo + (i + 1) * w, count: 0 }); }
  xs.forEach((v) => { if (v < lo || v > hi) { return; } const i = Math.min(n - 1, Math.floor((v - lo) / w)); out[i].count++; });
  return out;
}

/**
 * A quantile of sorted numbers (linear between the two nearest, as R's type 7).
 * @param {number[]} sorted ascending
 * @param {number} p 0 to 1
 * @returns {?number}
 */
export function quantile(sorted, p) {
  if (!sorted.length) { return null; }
  const h = (sorted.length - 1) * p, lo = Math.floor(h), hi = Math.ceil(h);
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (h - lo);
}

/**
 * The five figures of a box (whiskers at the furthest values within 1.5 IQR) and the values beyond them.
 * @param {number[]} values
 * @returns {?{box: number[], outliers: number[], n: number}} box: [low whisker, Q1, median, Q3, high whisker]
 */
export function boxStats(values) {
  const xs = values.map(num).filter((v) => v !== null).sort((a, b) => a - b);
  if (!xs.length) { return null; }
  const q1 = quantile(xs, 0.25), q2 = quantile(xs, 0.5), q3 = quantile(xs, 0.75), iqr = q3 - q1;
  const inside = xs.filter((v) => v >= q1 - 1.5 * iqr && v <= q3 + 1.5 * iqr);
  return { box: [inside[0], q1, q2, q3, inside[inside.length - 1]], outliers: xs.filter((v) => v < q1 - 1.5 * iqr || v > q3 + 1.5 * iqr), n: xs.length };
}

/**
 * The class breaks of a quantile scale: classes - 1 breaks, each the value at its quantile of the values with a figure.
 * @param {Array<?number>} values null: no data
 * @param {number} [classes=5]
 * @returns {{breaks: number[], min: ?number, max: ?number}}
 */
export function quantileBreaks(values, classes) {
  const xs = values.map(num).filter((v) => v !== null).sort((a, b) => a - b), k = classes || 5, breaks = [];
  for (let i = 1; i < k; i++) { breaks.push(xs.length ? xs[Math.min(xs.length - 1, Math.floor(xs.length * i / k))] : 0); }
  return { breaks: breaks, min: xs.length ? xs[0] : null, max: xs.length ? xs[xs.length - 1] : null };
}

/**
 * The class of a value under quantile breaks: 0 to breaks.length, or -1 for no data.
 * @param {*} v
 * @param {number[]} breaks
 * @returns {number}
 */
export function classOf(v, breaks) {
  const n = num(v);
  if (n === null) { return -1; }
  let c = 0;
  while (c < breaks.length && n >= breaks[c]) { c++; }
  return c;
}

// ---- the expression language of conditions ------------------------------------------------------------------------------------------
const TOKEN = /\s*(?:(\d+(?:\.\d*)?(?:e[+-]?\d+)?|\.\d+(?:e[+-]?\d+)?)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")|(===|!==|==|!=|<=|>=|&&|\|\||[<>!()+\-*/[\].])|([A-Za-z_$][\w$]*))/y;

function tokens(text) {
  const out = [];
  TOKEN.lastIndex = 0;
  let at = 0;
  while (at < text.length) {
    if (/^\s*$/.test(text.slice(at))) { break; }
    TOKEN.lastIndex = at;
    const m = TOKEN.exec(text);
    if (!m) { throw new Error('cannot read the condition "' + text + '" at ' + (at + 1)); }
    if (m[1] !== undefined) { out.push({ t: 'num', v: Number(m[1]) }); } else if (m[2] !== undefined) { out.push({ t: 'str', v: m[2].slice(1, -1).replace(/\\(.)/g, '$1') }); } else if (m[3] !== undefined) { out.push({ t: 'op', v: m[3] }); } else { out.push({ t: 'id', v: m[4] }); }
    at = TOKEN.lastIndex;
  }
  return out;
}

/**
 * Reads a condition into a function of a row: comparisons (< <= > >= == != === !==) of numbers, strings and fields (a bare name, or
 * datum.name and datum['name'] as Vega-Lite writes them), arithmetic (+ - * /, unary minus), !, && and ||, and brackets. Nothing else
 * (no calls, no assignments): an unreadable condition is an error with its place.
 * @param {string} text for example "hypothetical < -var99" or "datum.MTM < 0"
 * @returns {function(Object): *}
 */
export function compileCondition(text) {
  const ts = tokens(String(text));
  let i = 0;
  const peek = () => ts[i], take = () => ts[i++];
  const isOp = (v) => ts[i] && ts[i].t === 'op' && ts[i].v === v;
  const need = (v) => { if (!isOp(v)) { throw new Error('the condition "' + text + '" needs ' + v); } i++; };
  function primary() {
    const t = take();
    if (!t) { throw new Error('the condition "' + text + '" ends too soon'); }
    if (t.t === 'num' || t.t === 'str') { return () => t.v; }
    if (t.t === 'op' && t.v === '(') { const e = or(); need(')'); return e; }
    if (t.t === 'op' && t.v === '-') { const e = primary(); return (r) => -e(r); }
    if (t.t === 'op' && t.v === '!') { const e = primary(); return (r) => !e(r); }
    if (t.t === 'id') {
      if (t.v === 'true' || t.v === 'false') { return () => t.v === 'true'; }
      if (t.v === 'null') { return () => null; }
      if (t.v === 'datum') {
        if (isOp('.')) { i++; const f = take(); if (!f || f.t !== 'id') { throw new Error('datum. needs a field in "' + text + '"'); } return (r) => r[f.v]; }
        if (isOp('[')) { i++; const f = take(); need(']'); if (!f || f.t !== 'str') { throw new Error('datum[...] needs a quoted field in "' + text + '"'); } return (r) => r[f.v]; }
        return (r) => r;
      }
      return (r) => r[t.v];
    }
    throw new Error('the condition "' + text + '" cannot use ' + t.v);
  }
  function binary(next, ops) {
    return function () {
      let left = next();
      while (peek() && peek().t === 'op' && ops[peek().v]) { const f = ops[take().v], l = left, r2 = next(); left = (r) => f(l(r), r2(r)); }
      return left;
    };
  }
  const n = (x) => (typeof x === 'string' && x !== '' && isFinite(Number(x)) ? Number(x) : x);
  const mul = binary(primary, { '*': (a, b) => a * b, '/': (a, b) => a / b });
  const add = binary(mul, { '+': (a, b) => a + b, '-': (a, b) => a - b });
  const cmp = binary(add, { '<': (a, b) => n(a) < n(b), '<=': (a, b) => n(a) <= n(b), '>': (a, b) => n(a) > n(b), '>=': (a, b) => n(a) >= n(b),
    '==': (a, b) => a == b, '!=': (a, b) => a != b, '===': (a, b) => a === b, '!==': (a, b) => a !== b }); // eslint-disable-line eqeqeq
  const and = binary(cmp, { '&&': (a, b) => a && b });
  const or = binary(and, { '||': (a, b) => a || b });
  const e = or();
  if (i < ts.length) { throw new Error('the condition "' + text + '" has more after ' + (ts[i - 1] ? ts[i - 1].v : 'its start')); }
  return e;
}

/**
 * The fields a condition reads (for the data table and the spec's checks).
 * @param {string} text
 * @returns {string[]}
 */
export function conditionFields(text) {
  const ts = tokens(String(text)), out = [];
  ts.forEach((t, k) => {
    if (t.t !== 'id' || ['datum', 'true', 'false', 'null'].indexOf(t.v) >= 0) { return; }
    const prev = ts[k - 1];
    if (prev && prev.t === 'op' && prev.v === '.' && !(ts[k - 2] && ts[k - 2].v === 'datum')) { return; }
    if (out.indexOf(t.v) < 0) { out.push(t.v); }
  });
  ts.forEach((t, k) => { if (t.t === 'str' && ts[k - 1] && ts[k - 1].v === '[' && ts[k - 2] && ts[k - 2].v === 'datum' && out.indexOf(t.v) < 0) { out.push(t.v); } });
  return out;
}
