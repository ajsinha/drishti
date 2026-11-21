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
/* Datasets (contract 1.1, VIEWS_DESIGN step 5): the rows a view's grid, tree and chart panels read, by name, shared. A dataset is ONE
   of: values (rows as lists in the order of its fields: what drishti-rachana writes), rows (objects), name (rows, Arrow bytes or a
   drishti-data source the page hands the painter: options.data[name]), arrow ({url} or {base64}), pages (REST pages read a window at a
   time as the reader scrolls), server (a server-side row model) or live (contract 1.2: a live table of the Data Protocol, read a window
   at a time through options.tables and followed by key: the live kind's alone). Inline rows are held here and turned into objects once; the forms by
   address become drishti-data sources (loaded only then), so a grid of a million server rows holds only the rows in view. Every
   address passes the data allow-list: the page's origin, the view's own origin and options.data.allow. A rows patch (contract 1.1)
   changes a dataset by key (reset, remove, update, add) and reaches every panel that reads it. Values are raw: the panels format them
   with the field's fmt (drishti-core's named formats); {"$masked": true} prints as the mask. Derived reads (a chart's groups and
   aggregations, its top categories) are computed here, over the rows, with no expression language. */
import { hasFormat, formatNamed, formatNumber, MASK } from '../../drishti-core/src/drishti_index.js';

/** The aggregations a measure may name. */
export const AGGS = Object.freeze(['sum', 'count', 'min', 'max', 'avg', 'first', 'last', 'distinct']);
/** The forms a dataset's rows come in. */
export const DATASET_FORMS = Object.freeze(['values', 'rows', 'name', 'arrow', 'pages', 'server', 'live']);
/** How many rows a chart or a tree reads from a source by address at most. */
export const READ_LIMIT = 100000;

const SEP = '\u001f';
const FORBIDDEN = new Set(['__proto__', 'constructor', 'prototype']);

/**
 * Whether a value is the Data Protocol's mask ({"$masked": true}).
 * @param {*} v
 * @returns {boolean}
 */
export function isMasked(v) { return v !== null && typeof v === 'object' && v.$masked === true; }

/**
 * A raw value as text, by a format name (drishti-core's named formats) and the field's type; the mask as the mask, nothing as ''.
 * @param {*} v
 * @param {?string} fmt
 * @param {string} [type]
 * @returns {string}
 */
export function valueText(v, fmt, type) {
  if (isMasked(v)) { return MASK; }
  if (v === null || v === undefined || v === '') { return ''; }
  if (fmt && hasFormat(fmt)) { return formatNamed(fmt, v); }
  if (typeof v === 'number') { return formatNumber(v, { decimals: 0, maxDecimals: Number.isInteger(v) ? 0 : 2 }); }
  if (typeof v === 'boolean') { return v ? 'yes' : 'no'; }
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

/**
 * A row's key: its key fields' values as text, joined by U+001F (VIEWS_DESIGN 5.3); the row's place when the dataset has no key.
 * @param {Object} row
 * @param {string[]} key
 * @param {number} [index]
 * @returns {string}
 */
export function rowKey(row, key, index) {
  if (!key || !key.length) { return row && row.$i !== undefined ? String(row.$i) : String(index); }
  return key.map((k) => (row[k] === null || row[k] === undefined ? '' : String(row[k]))).join(SEP);
}

/**
 * The fields of rows given without any: every own key of the first 50 rows, in order, typed from their values.
 * @param {Object[]} rows
 * @returns {Array<{name: string, type: string}>}
 */
export function inferFields(rows) {
  const names = [];
  (rows || []).slice(0, 50).forEach((r) => { if (r && typeof r === 'object') { Object.keys(r).forEach((k) => { if (k !== '$i' && !FORBIDDEN.has(k) && names.indexOf(k) < 0) { names.push(k); } }); } });
  return names.map((n) => ({ name: n, type: typeOf((rows || []).map((r) => r && r[n])) }));
}

function typeOf(values) {
  let t = null;
  for (const v of values) {
    if (v === null || v === undefined || isMasked(v)) { continue; }
    const vt = typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) ? 'date'
      : typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v) ? 'datetime' : 'string';
    if (t === null) { t = vt; } else if (t !== vt) { return 'string'; }
  }
  return t || 'string';
}

/**
 * Combines numbers by an aggregation; the mask and nothing are left out (a masked member masks a sum: the figure is not shown).
 * @param {Array<*>} values
 * @param {string} agg
 * @returns {*}
 */
export function aggregate(values, agg) {
  if (agg === 'count') { return values.filter((v) => v !== null && v !== undefined).length; }
  if (agg === 'distinct') { return new Set(values.filter((v) => v !== null && v !== undefined).map((v) => (isMasked(v) ? MASK : String(v)))).size; }
  if (agg === 'first') { return values.length ? values[0] : null; }
  if (agg === 'last') { return values.length ? values[values.length - 1] : null; }
  if (values.some(isMasked)) { return { $masked: true }; }
  const nums = values.filter((v) => typeof v === 'number' && isFinite(v));
  if (!nums.length) { return null; }
  if (agg === 'min') { return Math.min.apply(null, nums); }
  if (agg === 'max') { return Math.max.apply(null, nums); }
  const sum = nums.reduce((a, b) => a + b, 0);
  return agg === 'avg' ? sum / nums.length : sum;
}

/**
 * A derived read (VIEWS_DESIGN 4.5): the rows grouped by x (and by), each measure combined by its aggregation; without any aggregation
 * the rows as they are. limit keeps the categories with the largest first figure (the others are left out).
 * @param {Object[]} rows
 * @param {{x: ?string, by: ?string, y: Array<{field: string, agg: ?string}>, limit: number}} q
 * @returns {Object[]}
 */
export function derive(rows, q) {
  const y = q.y || [];
  let out = rows;
  if (y.some((m) => m && m.agg)) {
    const groups = new Map();
    rows.forEach((r) => {
      const k = (q.x ? String(r[q.x]) : '') + SEP + (q.by ? String(r[q.by]) : '');
      let g = groups.get(k);
      if (!g) { g = { row: {}, members: [] }; if (q.x) { g.row[q.x] = r[q.x]; } if (q.by) { g.row[q.by] = r[q.by]; } groups.set(k, g); }
      g.members.push(r);
    });
    out = Array.from(groups.values()).map((g) => {
      y.forEach((m) => { g.row[m.field] = aggregate(g.members.map((r) => r[m.field]), m.agg || 'sum'); });
      return g.row;
    });
  }
  if (q.limit > 0 && q.x && y.length) {
    const first = y[0].field, size = new Map();
    out.forEach((r) => { const k = String(r[q.x]); size.set(k, (size.get(k) || 0) + Math.abs(typeof r[first] === 'number' ? r[first] : 0)); });
    const keep = new Set(Array.from(size.entries()).sort((a, b) => b[1] - a[1]).slice(0, q.limit).map((e) => e[0]));
    out = out.filter((r) => keep.has(String(r[q.x])));
  }
  return out;
}

/**
 * Whether an address may be read: http(s) (or the page's own scheme) at the page's origin, the view's origin or an origin the host
 * allowed (options.data.allow: origins, or 'any-https').
 * @param {string} url
 * @param {{page: string, view: ?string, allow: string[]}} where
 * @returns {?string} the address resolved, or null when it is refused
 */
export function allowedAddress(url, where) {
  if (typeof url !== 'string' || !url || /[\u0000-\u001f]/.test(url)) { return null; }
  let u;
  try { u = new URL(url, where.view || where.page); } catch (e) { return null; }
  const page = new URL(where.page);
  if (!/^https?:$/.test(u.protocol) && u.protocol !== page.protocol) { return null; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:' && u.protocol !== page.protocol) { return null; }
  const origins = [page.origin];
  if (where.view) { try { origins.push(new URL(where.view, where.page).origin); } catch (e) { /* not an address */ } }
  const allow = where.allow || [];
  if (u.protocol === 'file:' && page.protocol === 'file:') { return u.href; }
  if (origins.indexOf(u.origin) >= 0 || allow.indexOf(u.origin) >= 0 || (allow.indexOf('any-https') >= 0 && u.protocol === 'https:')) { return u.href; }
  return null;
}

/** One dataset of a view: its fields, its rows (inline) or its source (by address or handed by the page), and the panels that read it. */
export class Dataset {
  /**
   * @param {string} name
   * @param {Object} def the specification's Dataset
   * @param {Object} env {library(name), data: options.data, where: {page, view, allow}, fetch}
   */
  constructor(name, def, env) {
    this.name = name;
    this.def = def || {};
    this.env = env;
    this.key = Array.isArray(this.def.key) ? this.def.key.map(String) : [];
    this.listeners = new Set();
    this.form = DATASET_FORMS.find((f) => this.def[f] !== undefined && this.def[f] !== null) || 'values';
    this.held = null;                                   // inline rows as objects, made once
    this.fields = Array.isArray(this.def.fields) && this.def.fields.length ? this.def.fields.map((f) => Object.assign({}, f)) : null;
    if (this.form === 'values' || this.form === 'rows') { this.hold(); }
  }

  /** @private inline rows as objects, each with its place ($i) */
  hold() {
    const d = this.def;
    let rows;
    if (this.form === 'values') {
      const names = (this.fields || []).map((f) => f.name);
      rows = (Array.isArray(d.values) ? d.values : []).map((v, i) => { const r = { $i: i }; names.forEach((n, k) => { if (!FORBIDDEN.has(n)) { r[n] = Array.isArray(v) ? v[k] : undefined; } }); return r; });
    } else {
      rows = (Array.isArray(d.rows) ? d.rows : []).map((x, i) => { const r = { $i: i }; if (x && typeof x === 'object') { Object.keys(x).forEach((k) => { if (!FORBIDDEN.has(k)) { r[k] = x[k]; } }); } return r; });
    }
    if (!this.fields) { this.fields = inferFields(rows); }
    this.held = rows;
    this.index = new Map(rows.map((r, i) => [rowKey(r, this.key, i), r]));
    this.next = rows.length;
  }

  /** Whether the rows are read a window at a time (pages, a server). */
  get windowed() { return this.form === 'pages' || this.form === 'server' || this.form === 'live' || (this.form === 'arrow' && !!(this.def.arrow && this.def.arrow.paged)); }

  /** How many rows the producer left out. */
  get more() { return Number(this.def.more) || 0; }

  /**
   * A field by name.
   * @param {string} name
   * @returns {?Object}
   */
  field(name) { return (this.fields || []).find((f) => f.name === name) || null; }

  /**
   * Every row held or read (a source by address is read up to READ_LIMIT rows, a page at a time).
   * @returns {Promise<Object[]>}
   */
  rows() {
    if (this.held) { return Promise.resolve(this.held); }
    return this.source().then((src) => {
      if (Array.isArray(src)) { return src; }
      return this.env.library('drishti-data').then((D) => D.readAll(src, {}, { limit: READ_LIMIT })).then((a) => {
        if (!this.fields) { this.fields = inferFields(a.rows); }
        return a.rows;
      });
    });
  }

  /**
   * The rows as a source: an array (rows held) or a drishti-data DataSource (by address, or one the page handed).
   * @returns {Promise<(Object[]|Object)>}
   */
  source() {
    if (this.held) { return Promise.resolve(this.held); }
    if (this.sourceP) { return this.sourceP; }
    const d = this.def, env = this.env;
    const addr = (u) => { const a = allowedAddress(u, env.where); if (!a) { throw new Error('the address ' + u + ' is not an allowed data origin (options.data.allow)'); } return a; };
    this.sourceP = Promise.resolve().then(() => {
      if (this.form === 'name') {
        const given = env.data && Object.prototype.hasOwnProperty.call(env.data, d.name) ? env.data[d.name] : undefined;
        if (given === undefined) { throw new Error('the page handed no data named ' + d.name + ' (options.data.' + d.name + ')'); }
        if (Array.isArray(given)) { this.def = Object.assign({}, d, { rows: given }); this.form = 'rows'; this.hold(); return this.held; }
        if (given && typeof given.getRows === 'function') { return given; }
        return env.library('drishti-data').then((D) => D.arrowSource(D.fromArrow(given)));
      }
      if (this.form === 'arrow') {
        const a = d.arrow || {};
        if (a.base64) {
          return env.library('drishti-data').then((D) => D.arrowSource(D.fromArrow(base64Bytes(a.base64))));
        }
        const url = addr(a.url);
        return env.library('drishti-data').then((D) => D.arrowSource(url, { paged: !!a.paged, fetch: env.fetch }));
      }
      if (this.form === 'pages') {
        const p = d.pages || {}, url = addr(p.url);
        return env.library('drishti-data').then((D) => D.restSource(url, { method: 'GET', fetch: env.fetch,
          map: (b) => ({ rows: b.rows || b.items || [], rowCount: b.total !== undefined ? b.total : (b.rowCount !== undefined ? b.rowCount : p.total || undefined) }) }));
      }
      if (this.form === 'server') {
        const url = addr((d.server || {}).url);
        return env.library('drishti-data').then((D) => D.serverSideSource(url, { fetch: env.fetch }));
      }
      return [];
    });
    return this.sourceP;
  }

  /**
   * Listens to the dataset's changes: fn({reset, add, update, remove}) with whole rows (update) and keys (remove).
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }

  /**
   * A rows patch (contract 1.1): reset first, then remove, update (merged by key; an unknown key added), add (appended; an existing key
   * updated). Only rows the dataset holds can change: a windowed source is told by its own server.
   * @param {{reset: Object[], remove: string[], update: Object[], add: Object[]}} p
   * @returns {boolean} whether the dataset holds rows that a patch can change
   */
  apply(p) {
    if (!this.held) { return false; }
    const change = { reset: false, add: [], update: [], remove: [] };
    const clean = (x) => { const r = {}; if (x && typeof x === 'object') { Object.keys(x).forEach((k) => { if (!FORBIDDEN.has(k) && k !== '$i') { r[k] = x[k]; } }); } return r; };
    if (Array.isArray(p.reset)) {
      this.held = p.reset.map((x, i) => Object.assign(clean(x), { $i: i }));
      this.index = new Map(this.held.map((r, i) => [rowKey(r, this.key, i), r]));
      this.next = this.held.length;
      change.reset = true;
    }
    (Array.isArray(p.remove) ? p.remove : []).forEach((k) => {
      const r = this.index.get(String(k));
      if (r) { this.index.delete(String(k)); const at = this.held.indexOf(r); if (at >= 0) { this.held.splice(at, 1); } change.remove.push(rowKey(r, this.key)); }
    });
    const put = (x) => {
      const c = clean(x), k = rowKey(c, this.key, this.next);
      const r = this.key.length ? this.index.get(k) : null;
      if (r) { Object.assign(r, c); change.update.push(r); return; }
      const row = Object.assign(c, { $i: this.next++ });
      this.held.push(row);
      this.index.set(rowKey(row, this.key, row.$i), row);
      change.add.push(row);
    };
    (Array.isArray(p.update) ? p.update : []).forEach(put);
    (Array.isArray(p.add) ? p.add : []).forEach(put);
    this.listeners.forEach((fn) => { try { fn(change); } catch (e) { /* one panel never stops the others */ } });
    return true;
  }
}

function base64Bytes(b64) {
  const s = typeof atob === 'function' ? atob(b64) : Buffer.from(b64, 'base64').toString('binary');
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) { out[i] = s.charCodeAt(i); }
  return out;
}

/**
 * The datasets of a view, by name (made when the view is drawn; nothing is read until a panel asks).
 * @param {Object} vm the ViewModel
 * @param {Object} env see Dataset
 * @returns {Map<string, Dataset>}
 */
export function datasetsOf(vm, env) {
  const out = new Map();
  const d = vm && vm.datasets && typeof vm.datasets === 'object' && !Array.isArray(vm.datasets) ? vm.datasets : {};
  Object.keys(d).forEach((name) => { if (!FORBIDDEN.has(name)) { out.set(name, new Dataset(name, d[name], env)); } });
  return out;
}
