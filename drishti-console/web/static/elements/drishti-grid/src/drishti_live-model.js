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
/* The grid's live mode, the model (tested in Node): a live result set's rows as the server sends them ({id, version, seq, doc}), held by
   key and merged by version (an older or equal version never replaces a newer one: a replayed or reordered event is harmless), turned into
   the grid's rows and columns, the page window the server is told, the changes a record panel offers as an edit, and the Pivot's layout for
   the same kind, filters and grouping. The server's rules (rights, masks, versions, four eyes) are never decided here; this only draws them.
   The connection is not here either: any object with subscribe(handlers) is a channel (the console's live channel, a test's manual one),
   and httpLiveApi is the one way a page reaches the server's /live endpoints. */
import { MASK, hasFormat } from '../../drishti-core/src/drishti_index.js';

/** The grid row's key and version properties (the record's own fields keep their names). */
export const ROW_KEY = '__id';
/** The grid row's version property. */
export const ROW_VERSION = '__v';

/** The most rows the page window names at once (the server's page is 5,000); a set larger than the grid holds is windowed. */
export const WINDOW_ROWS = 400;

const TYPES = { id: 'text', number: 'number', amount: 'number', status: 'text', date: 'date', datetime: 'text', list: 'text', text: 'text' };

/**
 * A server row as the grid's row: the record's fields, its key and its version.
 * @param {{id: string, version: number, doc: Object}} r
 * @returns {Object}
 */
export function gridRow(r) {
  const row = Object.assign({}, r.doc || {});
  row[ROW_KEY] = String(r.id);
  row[ROW_VERSION] = r.version === undefined || r.version === null ? -1 : r.version;
  return row;
}

/**
 * The declaration's columns as the grid's: the key pinned, a status drawn by its value, a list joined, the subtotal columns summed in groups.
 * @param {Object} spec the declaration as GET /live describes it
 * @returns {Object[]}
 */
export function gridColumns(spec) {
  const sums = new Set(spec.subtotals || []);
  return (spec.columns || []).map((c) => {
    const type = TYPES[c.type] || 'text';
    const col = { id: c.field, field: c.field, header: c.label || c.field, type: type, width: c.width || (type === 'number' ? 110 : 130) };
    if (c.format) {                                   // a named format the server knows; number4, price6 ...: that many decimals
      const n = /^[a-z]+(\d)$/.exec(String(c.format));
      if (hasFormat(c.format)) { col.format = c.format; } else if (n) { col.format = { decimals: +n[1] }; }
    }
    if (c.type === 'id') { col.pinned = 'left'; }
    if (c.type === 'list') { col.valueGetter = (row) => (Array.isArray(row[c.field]) ? row[c.field].join(', ') : row[c.field]); }
    if (c.type === 'status') { col.cellClass = (v) => 'dg-live-st dg-live-st-' + String(v || '').toLowerCase().replace(/[^a-z0-9]/g, ''); }
    if (type === 'number' && sums.has(c.field)) { col.aggFunc = 'sum'; }
    if (type === 'number') { col.enableValue = true; }
    return col;
  });
}

/**
 * The rows a set holds, by key and version.
 */
export class LiveRows {
  /** An empty set of rows. */
  constructor() { this.byId = new Map(); this.total = 0; this.seq = 0; }

  /** @returns {number} how many rows are held */
  get size() { return this.byId.size; }

  /**
   * Holds exactly these rows (a snapshot or a resync).
   * @param {Object[]} rows server rows
   * @param {number} [total] the set's size
   * @param {number} [seq]
   * @returns {Object[]} the grid rows
   */
  load(rows, total, seq) {
    this.byId.clear();
    (rows || []).forEach((r) => { const g = gridRow(r); this.byId.set(g[ROW_KEY], g); });
    this.total = total === undefined ? this.byId.size : total;
    this.seq = seq || 0;
    return Array.from(this.byId.values());
  }

  /**
   * Merges one rows event: an upsert replaces a row only when its version is newer (a row not held is added), a remove drops it.
   * @param {{upserts: Object[], removes: string[], total: number, seq: number}} ev
   * @param {boolean} [paged] the viewer holds only a window of the set: rows it does not hold are not added
   * @returns {{add: Object[], update: Object[], remove: string[], stale: number}} the grid's transaction
   */
  merge(ev, paged) {
    const add = [], update = [], remove = [];
    let stale = 0;
    (ev.upserts || []).forEach((r) => {
      const g = gridRow(r), held = this.byId.get(g[ROW_KEY]);
      if (held && g[ROW_VERSION] >= 0 && held[ROW_VERSION] >= g[ROW_VERSION]) { stale++; return; }
      if (!held && paged) { return; }
      this.byId.set(g[ROW_KEY], g);
      (held ? update : add).push(g);
    });
    (ev.removes || []).forEach((id) => { if (this.byId.delete(String(id))) { remove.push(String(id)); } });
    if (ev.total !== undefined) { this.total = ev.total; }
    if (ev.seq !== undefined) { this.seq = ev.seq; }
    return { add: add, update: update, remove: remove, stale: stale };
  }
}

/**
 * The ids a stream's window names: the rows in view and a margin, in the order shown; null (the viewer holds every row) when the set fits.
 * @param {string[]} shown the ids in display order
 * @param {number} first the first visible index
 * @param {number} last the last visible index
 * @param {boolean} paged whether the viewer holds less than the set
 * @returns {?string[]}
 */
export function windowIds(shown, first, last, paged) {
  if (!paged) { return null; }
  const from = Math.max(0, first - WINDOW_ROWS / 2), to = Math.min(shown.length - 1, last + WINDOW_ROWS / 2);
  return shown.slice(from, to + 1);
}

/**
 * What a record panel's edit changes: the fields whose value differs from the record, as the edit API names them.
 * @param {Object} record the record's document
 * @param {Object} values the form's values, by declaration field id
 * @param {Object[]} fields the declaration's editable fields ({id, bind})
 * @returns {Array<{field: string, from: *, to: *}>}
 */
export function editItems(record, values, fields) {
  const out = [];
  (fields || []).forEach((f) => {
    if (!(f.id in values)) { return; }
    const from = record[f.bind], to = values[f.id];
    if (to === undefined || (to === '' && (from === null || from === undefined)) || String(from) === String(to)) { return; }
    out.push({ field: f.bind, from: from === undefined ? null : from, to: to });
  });
  return out;
}

/**
 * A form schema (drishti-inputs) from the declaration's editable fields: a number, a book code with its pattern, an enum, a reason.
 * A field the server did not send (read-only for this person: the server leaves "fields" out) is not drawn.
 * @param {Object} spec
 * @param {Object} record the record's document
 * @returns {{schema: Object, value: Object}}
 */
export function editSchema(spec, record) {
  const props = {}, value = {}, required = [];
  (spec.fields || []).forEach((f) => {
    const number = f.type === 'number' || f.type === 'integer';
    const p = { title: f.label || f.id, type: number ? 'number' : 'string' };
    if (f.options && f.options.length) { p.enum = f.options.map((o) => o.value); p.enumLabels = f.options.map((o) => o.label || o.value); p.widget = 'select'; p.allowEmpty = false; }
    if (f.min !== null && f.min !== undefined) { p.minimum = f.min; }
    if (f.max !== null && f.max !== undefined) { p.maximum = f.max; }
    if (f.pattern) { p.pattern = f.pattern; }
    if (f.readonly || f.bind === spec.key) { p.readOnly = true; }               // the key is never edited
    p.hook = 'live-field-' + f.id;
    props[f.id] = p;
    if (f.required) { required.push(f.id); }
    const v = record[f.bind];
    if (v !== undefined && v !== null) { value[f.id] = v; }
  });
  props.reason = { type: 'string', title: 'Reason', widget: 'textarea', hook: 'live-reason', description: spec.reason === 'required' ? 'Required: it goes in the audit.' : 'Optional: it goes in the audit.' };
  if (spec.reason === 'required') { required.push('reason'); }
  return { schema: { type: 'object', properties: props, required: required, order: Object.keys(props) }, value: value };
}

/**
 * The Pivot's view for the same set: its kind as the source, the filters as members, the groups as rows and the numeric columns summed.
 * @param {Object} spec
 * @param {{groupBy?: string[], filters?: Object<string, string[]>, values?: string[]}} [o]
 * @returns {{source: string, view: Object}}
 */
export function pivotLayout(spec, o) {
  const opt = o || {}, filters = {};
  Object.keys(opt.filters || {}).forEach((f) => { if ((opt.filters[f] || []).length) { filters[f] = { in: opt.filters[f].slice() }; } });
  const numeric = (spec.columns || []).filter((c) => (c.type === 'number' || c.type === 'amount') && c.field !== spec.version).map((c) => c.field);
  const values = (opt.values && opt.values.length ? opt.values : numeric).map((f) => ({ measure: 'sum(' + f + ')', as: f }));
  const groups = (opt.groupBy ? opt.groupBy : spec.groupBy || []).slice();
  return { source: spec.kind, view: { rows: groups, columns: [], values: values.length ? values : [{ measure: 'count()', as: 'Rows' }], filters: filters } };
}

/**
 * The address of the Pivot for a layout: the kind, the layout and live refresh when the kind is live.
 * @param {{source: string, view: Object}} layout
 * @param {boolean} live
 * @returns {string}
 */
export function pivotHref(layout, live) {
  return '/pivot?source=' + encodeURIComponent(layout.source) + '&lay=' + encodeURIComponent(JSON.stringify(layout.view)) + (live ? '&live=1' : '');
}

/**
 * A link of the declaration for a row: {FIELD} replaced by the row's value, encoded.
 * @param {{label: string, href: string}} link
 * @param {Object} row
 * @returns {{label: string, href: string}}
 */
export function linkFor(link, row) {
  return { label: link.label, href: String(link.href).replace(/\{([A-Z0-9_]+)\}/g, (m, f) => encodeURIComponent(row && row[f] !== undefined && row[f] !== null ? row[f] : '')) };
}

/**
 * The paths of a record whose value is the mask (shown as masked, never copied).
 * @param {*} doc
 * @param {string} [base]
 * @returns {string[]}
 */
export function maskedPaths(doc, base) {
  const out = [];
  const walk = (v, p) => {
    if (v === MASK) { out.push(p); } else if (Array.isArray(v)) { v.forEach((x, i) => walk(x, p + '[' + i + ']')); } else if (v && typeof v === 'object') { Object.keys(v).forEach((k) => walk(v[k], p ? p + '.' + k : k)); }
  };
  walk(doc, base || '');
  return out;
}

/**
 * What differs between the record as the panel opened it and now: the fields whose value changed.
 * @param {Object} then
 * @param {Object} now
 * @returns {string[]}
 */
export function changedFields(then, now) {
  const keys = new Set(Object.keys(then || {}).concat(Object.keys(now || {})));
  return Array.from(keys).filter((k) => JSON.stringify((then || {})[k]) !== JSON.stringify((now || {})[k]));
}

/**
 * The one way a page reaches a live set on the server (the console's /api/live): methods answer promises of JSON and reject with an Error
 * that carries the server's code, status and words.
 * @param {{base?: string, id: string, fetch?: Function}} o base default "/api/live"
 * @returns {{rows: Function, row: Function, edit: Function, window: Function, history: Function, mine: Function}}
 */
export function httpLiveApi(o) {
  const base = (o.base || '/api/live').replace(/\/$/, ''), id = encodeURIComponent(o.id);
  const f = o.fetch || ((typeof fetch === 'function') ? fetch.bind(globalThis) : null);
  const call = (method, path, body) => f(path, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
    body: body ? JSON.stringify(body) : undefined }).then((r) => r.json().catch(() => ({})).then((j) => {
    if (!r.ok) { const e = new Error(j.detail || ('HTTP ' + r.status)); e.code = j.code; e.status = r.status; throw e; }
    return j;
  }));
  const q = (p) => Object.keys(p).filter((k) => p[k] !== undefined && p[k] !== null && p[k] !== '').map((k) => k + '=' + encodeURIComponent(p[k])).join('&');
  return {
    rows: (p) => call('GET', base + '/' + id + '/rows?' + q(p || {})),
    row: (entity) => call('GET', base + '/' + id + '/rows/' + encodeURIComponent(entity)),
    edit: (body) => call('POST', base + '/' + id + '/edit', body),
    window: (stream, body) => call('POST', base + '/' + id + '/stream/' + encodeURIComponent(stream) + '/window', body),
    history: (kind, entity) => call('GET', '/api/changes?' + q({ kind: kind, entity: entity, limit: 50 })),
    mine: (kind) => call('GET', '/api/changes?' + q({ mine: 'true', kind: kind, limit: 100 }))
  };
}
