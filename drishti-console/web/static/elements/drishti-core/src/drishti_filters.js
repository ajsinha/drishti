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
/* The sort model and the filter model, the one contract every library shares: drishti-grid sorts and filters by it, drishti-inputs'
   filter bar writes it, drishti-data's sources answer it, a chart filters its rows by it, and FILTER_CHANNEL on a bus carries it between
   them. Plain data, kept with a view and sent to a server as they are. Sorting: by several columns, blanks
   last in either direction, numbers as numbers, text by the locale's collation with numbers inside it in order (T2 before T10), dates as
   ISO text. Filters, per column: text (contains, equals, starts, ends, not), number and date (equals, less, greater, between), set
   (members kept, or members left out: the member filter), blank or not; plus a quick filter across every shown column. */

/**
 * @typedef {Object} SortItem
 * @property {string} colId
 * @property {('asc'|'desc')} sort
 */

/**
 * @typedef {Object} ColumnFilter
 * @property {('text'|'number'|'date'|'set'|'blank'|'list')} type
 * @property {string} [op] list: has, anyOf, allOf (a list attribute, as Rachana's has / any_of / all_of); text: contains, notContains, equals, notEquals, startsWith, endsWith; number and date: equals, notEquals, lt,
 *   lte, gt, gte, between; blank: blank, notBlank
 * @property {*} [value]
 * @property {*} [valueTo] the upper end of between (inclusive)
 * @property {Array<*>} [values] set: the members kept; list: the values asked for (op has: one; anyOf, allOf: several)
 * @property {Array<*>} [excluded] set: the members left out
 */

import { listValues } from './drishti_chips.js';

const collator = typeof Intl !== 'undefined' ? new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' }) : null;

/**
 * Whether a value is blank (null, undefined, '' or NaN).
 * @param {*} v
 * @returns {boolean}
 */
export function isBlank(v) { return v === null || v === undefined || v === '' || (typeof v === 'number' && isNaN(v)); }

/**
 * The natural order of two values (blanks are not handled here: see compareRows).
 * @param {*} a
 * @param {*} b
 * @returns {number}
 */
export function compareValues(a, b) {
  if (typeof a === 'number' && typeof b === 'number') { return a - b; }
  if (typeof a === 'boolean' && typeof b === 'boolean') { return (a ? 1 : 0) - (b ? 1 : 0); }
  if (a instanceof Date && b instanceof Date) { return a.getTime() - b.getTime(); }
  const sa = String(a), sb = String(b);
  return collator ? collator.compare(sa, sb) : sa < sb ? -1 : sa > sb ? 1 : 0;
}

/**
 * A comparator of rows by a sort model: each item in turn, blanks last whatever the direction, the column's own comparator when it has one.
 * @param {SortItem[]} model
 * @param {function(string): Object} column the column of an id
 * @param {function(Object, Object): *} value a row's value in a column
 * @returns {?function(Object, Object): number} null when the model is empty
 */
export function compareRows(model, column, value) {
  if (!model || !model.length) { return null; }
  const items = model.map((s) => ({ col: column(s.colId), dir: s.sort === 'desc' ? -1 : 1 }));
  return (ra, rb) => {
    for (const it of items) {
      const a = value(ra, it.col), b = value(rb, it.col);
      const ba = isBlank(a), bb = isBlank(b);
      if (ba || bb) { if (ba && bb) { continue; } return ba ? 1 : -1; }
      const c = it.col.comparator ? it.col.comparator(a, b) : compareValues(a, b);
      if (c) { return c * it.dir; }
    }
    return 0;
  };
}

/**
 * The next sort of a column when its header is clicked: none, ascending, descending, none; with multi (Shift) the column is added to the
 * model, else it replaces it.
 * @param {SortItem[]} model
 * @param {string} colId
 * @param {boolean} [multi]
 * @returns {SortItem[]}
 */
export function cycleSort(model, colId, multi) {
  const cur = (model || []).find((s) => s.colId === colId);
  const next = !cur ? 'asc' : cur.sort === 'asc' ? 'desc' : null;
  const rest = multi ? (model || []).filter((s) => s.colId !== colId) : [];
  if (!next) { return rest; }
  if (multi && cur) { return (model || []).map((s) => (s.colId === colId ? { colId: colId, sort: next } : s)); }
  return rest.concat([{ colId: colId, sort: next }]);
}

function asNumber(v) { return typeof v === 'number' ? v : v === null || v === undefined || v === '' ? NaN : Number(v); }
function asDateText(v) { if (v instanceof Date) { return v.toISOString().slice(0, 10); } return v === null || v === undefined ? '' : String(v).slice(0, 10); }
function fold(s) { return String(s).toLowerCase(); }

/**
 * Whether a value passes a column's filter.
 * @param {*} v
 * @param {ColumnFilter} f
 * @returns {boolean}
 */
export function passes(v, f) {
  if (!f) { return true; }
  const op = f.op;
  switch (f.type) {
    case 'blank': return op === 'notBlank' ? !isBlank(v) : isBlank(v);
    case 'set': {
      const key = isBlank(v) ? null : typeof v === 'number' ? v : String(v);
      if (f.values) { return f.values.some((x) => (x === null ? key === null : key !== null && String(x) === String(key))); }
      if (f.excluded) { return !f.excluded.some((x) => (x === null ? key === null : key !== null && String(x) === String(key))); }
      return true;
    }
    case 'list': {
      const want = (f.values || (f.value === undefined || f.value === null || f.value === '' ? [] : [f.value])).map(String);
      if (!want.length) { return true; }                       // nothing chosen holds everything
      const have = new Set(listValues(v));
      if (op === 'allOf') { return want.every((x) => have.has(x)); }
      if (op === 'anyOf') { return want.some((x) => have.has(x)); }
      if (op === 'has' || !op) { return have.has(want[0]); }
      throw new Error('no list filter ' + op);
    }
    case 'number':
    case 'date': {
      if (op === 'blank') { return isBlank(v); }
      if (op === 'notBlank') { return !isBlank(v); }
      if (isBlank(v)) { return op === 'notEquals'; }
      const conv = f.type === 'number' ? asNumber : asDateText;
      const x = conv(v), a = conv(f.value), b = conv(f.valueTo);
      switch (op || 'equals') {
        case 'equals': return x === a;
        case 'notEquals': return x !== a;
        case 'lt': return x < a;
        case 'lte': return x <= a;
        case 'gt': return x > a;
        case 'gte': return x >= a;
        case 'between': return x >= a && x <= b;
        default: throw new Error('no number or date filter ' + op);
      }
    }
    default: {
      if (op === 'blank') { return isBlank(v); }
      if (op === 'notBlank') { return !isBlank(v); }
      const t = isBlank(v) ? '' : fold(v), q = fold(f.value === undefined || f.value === null ? '' : f.value);
      switch (op || 'contains') {
        case 'contains': return t.indexOf(q) >= 0;
        case 'notContains': return t.indexOf(q) < 0;
        case 'equals': return t === q;
        case 'notEquals': return t !== q;
        case 'startsWith': return t.indexOf(q) === 0;
        case 'endsWith': return t.length >= q.length && t.slice(t.length - q.length) === q;
        default: throw new Error('no text filter ' + op);
      }
    }
  }
}

/**
 * A test of rows by a filter model and a quick filter, or null when nothing filters.
 * @param {Object<string, ColumnFilter>} model column id to its filter
 * @param {string} quick words every one of which must appear in one of the columns
 * @param {Object[]} columns the columns the quick filter looks in (and the model's)
 * @param {function(Object, Object): *} value
 * @returns {?function(Object): boolean}
 */
export function rowFilter(model, quick, columns, value) {
  const byId = {};
  columns.forEach((c) => { byId[c.id] = c; });
  const parts = Object.keys(model || {}).filter((k) => model[k]).map((k) => {
    if (!byId[k]) { throw new Error('a filter on ' + k + ', which is not a column'); }
    return [byId[k], model[k]];
  });
  const words = String(quick || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!parts.length && !words.length) { return null; }
  return (row) => {
    for (const [c, f] of parts) { if (!passes(value(row, c), f)) { return false; } }
    if (words.length) {
      const hay = columns.map((c) => { const v = value(row, c); return isBlank(v) ? '' : String(v).toLowerCase(); }).join('\u0001');
      for (const w of words) { if (hay.indexOf(w) < 0) { return false; } }
    }
    return true;
  };
}

/**
 * A filter model in words, for the line above a grid ("Desk in RATES, FX; Notional > 1000000").
 * @param {Object<string, ColumnFilter>} model
 * @param {function(string): string} [header] a column's heading
 * @returns {string}
 */
export function filterWords(model, header) {
  const h = header || ((id) => id);
  const sym = { equals: '=', notEquals: '≠', lt: '<', lte: '≤', gt: '>', gte: '≥' };
  return Object.keys(model || {}).filter((k) => model[k]).map((k) => {
    const f = model[k];
    if (f.type === 'set') { return f.values ? h(k) + ' in ' + f.values.map((x) => (x === null ? '(blank)' : x)).join(', ') : h(k) + ' not ' + (f.excluded || []).join(', '); }
    if (f.type === 'list') { const vs = f.values || (f.value === undefined || f.value === null ? [] : [f.value]); return h(k) + (f.op === 'allOf' ? ' has all of ' : f.op === 'anyOf' ? ' has any of ' : ' contains ') + vs.join(', '); }
    if (f.type === 'blank' || f.op === 'blank' || f.op === 'notBlank') { return h(k) + (f.op === 'notBlank' ? ' is not blank' : ' is blank'); }
    if (f.op === 'between') { return h(k) + ' ' + f.value + ' to ' + f.valueTo; }
    if (sym[f.op]) { return h(k) + ' ' + sym[f.op] + ' ' + f.value; }
    return h(k) + ' ' + (f.op || 'contains').replace(/[A-Z]/g, (c) => ' ' + c.toLowerCase()) + ' "' + f.value + '"';
  }).join('; ');
}

/** The bus event a filter bar emits and grids and charts listen to; its payload is {model, quickFilter, source}. */
export const FILTER_CHANNEL = 'filters:changed';

/**
 * The rows that pass a filter model (and a quick filter), each filter's column read as the row's property of that name unless a value
 * function says otherwise: what a chart, an in-memory source or a page without a grid uses.
 * @param {Object[]} rows
 * @param {Object<string, ColumnFilter>} model
 * @param {{quickFilter: string, columns: Array<{id: string, field: string}>, value: function(Object, Object): *}} [o] columns: where the
 *   quick filter looks (default: the model's columns); value: a row's value in a column (default: row[column.field or column.id])
 * @returns {Object[]}
 */
export function filterRows(rows, model, o) {
  o = o || {};
  const columns = (o.columns || []).slice();
  Object.keys(model || {}).forEach((id) => { if (!columns.some((c) => c.id === id)) { columns.push({ id: id }); } });
  const test = rowFilter(model, o.quickFilter || '', columns, o.value || ((row, c) => row[c.field || c.id]));
  return test ? rows.filter(test) : rows.slice();
}

const TEXT_MULT = { k: 1e3, m: 1e6, mm: 1e6, b: 1e9, bn: 1e9, tn: 1e12 };

/**
 * A drawn cell's sort key, read as the console's tables read it: an amount (separators, signs with the true minus or a dash, k, m, mm,
 * b, bn, tn, a trailing % or bp) is a number; an ISO date is its time; anything else is its lower-cased text. The document table sorts
 * by it, as the console's server tables do.
 * @param {string} text the cell's text as shown
 * @returns {{n: number, s: string}} n for a number or a date, else s
 */
export function textSortKey(text) {
  const t = String(text).replace(/[−–]/g, '-').replace(/,/g, '').replace(/\s/g, '');
  const m = t.match(/^([+-]?)(\d+(?:\.\d+)?)(k|m|mm|bn|b|tn)?(%|bp)?$/i);
  if (m) { const n = parseFloat(m[2]) * (m[3] ? TEXT_MULT[m[3].toLowerCase()] : 1); return { n: m[1] === '-' ? -n : n }; }
  if (/^\d{4}-\d{2}-\d{2}/.test(t) && !isNaN(Date.parse(t.slice(0, 10)))) { return { n: Date.parse(t.slice(0, 10)) }; }
  return { s: String(text).toLowerCase() };
}

/**
 * The order of two sort keys (textSortKey): numbers and dates before text, numbers by value, text by code point.
 * @param {{n: number, s: string}} a
 * @param {{n: number, s: string}} b
 * @returns {number}
 */
export function compareTextKeys(a, b) {
  if (a.n != null && b.n != null) { return a.n - b.n; }
  if (a.n != null) { return -1; }
  if (b.n != null) { return 1; }
  return a.s < b.s ? -1 : a.s > b.s ? 1 : 0;
}
