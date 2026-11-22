/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The aggregation functions the browser computes, the server's names. The rule is the server's: a cell or
   a total is made from its members, never from other cells' figures. Each function keeps a small state that merges exactly (a sum; a
   sum and a count; the extreme; the set of values), so a total is the merge of its members' states. A measure the browser does not
   compute (VaR, ES, a ratio, a custom function) or a column marked non-additive is never added up here: it is asked of the server, for
   every cell and every total (see isServerMeasure). */

/**
 * @typedef {Object} AggFunc
 * @property {string} name
 * @property {string} label
 * @property {function(): *} init
 * @property {function(*, *): *} add the state with one more value (returns the state)
 * @property {function(*, *): *} merge two states as one
 * @property {function(*): *} result the figure
 * @property {boolean} numeric whether the values are numbers
 */

function num(v) { return typeof v === 'number' ? (isFinite(v) ? v : null) : v === null || v === undefined || v === '' ? null : (isFinite(+v) ? +v : null); }

/** Kahan-compensated sums, as the server's sum. */
const sum = {
  name: 'sum', label: 'Sum', numeric: true,
  init: () => ({ s: 0, c: 0, n: 0 }),
  add: (st, v) => { const x = num(v); if (x === null) { return st; } const y = x - st.c, t = st.s + y; st.c = (t - st.s) - y; st.s = t; st.n++; return st; },
  merge: (a, b) => { const y = b.s - a.c - b.c, t = a.s + y; return { s: t, c: (t - a.s) - y, n: a.n + b.n }; },
  result: (st) => (st.n ? st.s : null)
};

/** The functions by name. */
export const AGG_FUNCS = Object.freeze({
  sum: sum,
  count: { name: 'count', label: 'Count', numeric: false, init: () => 0, add: (st, v) => (v === undefined || v === null || v === '' ? st : st + 1), merge: (a, b) => a + b, result: (st) => st },
  min: { name: 'min', label: 'Min', numeric: true, init: () => null, add: (st, v) => { const x = num(v); return x === null ? st : st === null || x < st ? x : st; },
    merge: (a, b) => (a === null ? b : b === null ? a : Math.min(a, b)), result: (st) => st },
  max: { name: 'max', label: 'Max', numeric: true, init: () => null, add: (st, v) => { const x = num(v); return x === null ? st : st === null || x > st ? x : st; },
    merge: (a, b) => (a === null ? b : b === null ? a : Math.max(a, b)), result: (st) => st },
  avg: { name: 'avg', label: 'Average', numeric: true, init: () => sum.init(), add: sum.add, merge: sum.merge, result: (st) => (st.n ? st.s / st.n : null) },
  first: { name: 'first', label: 'First', numeric: false, init: () => ({ has: false, v: null }), add: (st, v) => { if (!st.has) { st.has = true; st.v = v; } return st; },
    merge: (a, b) => (a.has ? a : b), result: (st) => st.v },
  last: { name: 'last', label: 'Last', numeric: false, init: () => ({ has: false, v: null }), add: (st, v) => { st.has = true; st.v = v; return st; },
    merge: (a, b) => (b.has ? b : a), result: (st) => st.v },
  count_distinct: { name: 'count_distinct', label: 'Distinct count', numeric: false, init: () => new Set(),
    add: (st, v) => { if (v !== null && v !== undefined && v !== '') { st.add(typeof v === 'number' ? v : String(v)); } return st; },
    merge: (a, b) => { const s = new Set(a); b.forEach((x) => s.add(x)); return s; }, result: (st) => st.size }
});

const CUSTOM = new Map();

/**
 * A function by its name (the server's: sum, count, count_distinct, avg ...), or a registered one; null when the browser does not compute it.
 * @param {string} name
 * @returns {?AggFunc}
 */
export function aggFunc(name) { return AGG_FUNCS[name] || CUSTOM.get(name) || null; }

/**
 * Registers a host's own aggregation for client-side rows (the AG Grid-style adapter's aggFuncs): it is given every member's value of a
 * cell or total and answers the figure; the state is the list of values, so a total is still made from its own members, never from
 * other cells' figures. A built-in name cannot be replaced. The name must be a plain word (it goes into a measure's text, name(FIELD)).
 * @param {string} name
 * @param {function(Array<*>): *} fn the figure of the members' values
 * @param {{label: string, numeric: boolean}} [o]
 * @returns {AggFunc}
 */
export function registerAggFunc(name, fn, o) {
  if (!/^[A-Za-z_]\w*$/.test(String(name)) || AGG_FUNCS[name]) { throw new Error('an aggregation needs a new plain name (letters, digits, _): ' + name); }
  if (typeof fn !== 'function') { throw new Error('aggregation ' + name + ': give a function of the values'); }
  const f = { name: name, label: (o && o.label) || name, numeric: !!(o && o.numeric), init: () => [], add: (st, v) => { st.push(v); return st; },
    merge: (a, b) => a.concat(b), result: (st) => fn(st.slice()), custom: true };
  CUSTOM.set(name, f);
  return f;
}

/**
 * Forgets a registered aggregation (a grid that registered it is gone).
 * @param {string} name
 * @returns {boolean} whether it was registered
 */
export function unregisterAggFunc(name) { return CUSTOM.delete(name); }

/**
 * Whether a value is the Data Protocol's mask ({"$masked": true}): a value the reader may not see.
 * @param {*} v
 * @returns {boolean}
 */
export function isMasked(v) { return v !== null && typeof v === 'object' && v.$masked === true; }

/**
 * The aggregate of some values by a function (a one-off: a selection's figure, a group's, a chart's category). The mask is never summed:
 * a masked member makes every numeric aggregation (sum, min, max, avg) masked, and a distinct count counts all masks as one value.
 * @param {Iterable<*>} values
 * @param {string} func
 * @returns {*}
 */
export function aggregate(values, func) {
  const f = aggFunc(func);
  if (!f) { throw new Error('the browser does not compute ' + func + ': ask the server'); }
  let st = f.init(), masked = false;
  for (const v of values) {
    if (isMasked(v)) { masked = true; st = f.add(st, f.name === 'count_distinct' ? '\u2022' : v); } else { st = f.add(st, v); }
  }
  return masked && f.numeric ? { $masked: true } : f.result(st);
}
