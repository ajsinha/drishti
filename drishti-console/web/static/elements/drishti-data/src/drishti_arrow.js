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
/* The Arrow client: a table from a server as Arrow IPC (the vendored Apache Arrow JS reads it; loaded the first time, never from a
   CDN), read without copying where it can: a column of 64-bit floats with no nulls is the Arrow buffer itself, a column of repeated
   text (a dictionary) is decoded once per distinct word, and the grid's rows are views (each holds its row number and reads the columns
   when asked), so a million rows cost a million small objects, not a million copies. Types follow the console's rule (DATA_GUIDE):
   64-bit integers become numbers while they are safe and exact text past 2^53, decimals exact text, dates ISO, timestamps ISO with Z
   when zoned. A JSON answer comes back as the same frame, so a page never cares which it got.

   Where the bytes come from: Flight over HTTP when the server offers it (a POST of the ticket to its do-get address, an Arrow stream
   back; ArrowClient asks the server's capabilities once), else Arrow over HTTP (Accept: application/vnd.apache.arrow.stream), else JSON.
   The Drishti server answers Arrow over HTTP today; its Flight service is gRPC (docs/architecture/DATA_ARCHITECTURE.md), which a browser cannot speak. */
import { staticUrl, loadScript } from '../../drishti-core/src/drishti_index.js';

/** The media type of an Arrow IPC stream. */
export const ARROW_MEDIA = 'application/vnd.apache.arrow.stream';
/** The vendored Arrow JS, relative to the static root. */
export const ARROW_VENDOR = 'vendor/apache-arrow/21.1.0/Arrow.es2015.min.js';
const SAFE = typeof BigInt === 'function' ? BigInt(Number.MAX_SAFE_INTEGER) : null;

/**
 * Arrow JS: the one given, the page's, or the vendored one loaded now.
 * @param {Object} [given]
 * @returns {Promise<Object>}
 */
export function arrowLib(given) {
  const root = typeof window !== 'undefined' ? window : globalThis;
  if (given) { return Promise.resolve(given); }
  if (root.Arrow && root.Arrow.tableFromIPC) { return Promise.resolve(root.Arrow); }
  return loadScript(staticUrl(ARROW_VENDOR)).then(() => { if (!root.Arrow) { throw new Error('Arrow JS did not load'); } return root.Arrow; });
}

function pad(n, w) { return String(n).padStart(w || 2, '0'); }
function isoDay(ms) { const d = new Date(ms); return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()); }
function isoTime(ms, zoned) {
  const d = new Date(ms), frac = d.getUTCMilliseconds() ? '.' + pad(d.getUTCMilliseconds(), 3) : '';
  return isoDay(ms) + 'T' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds()) + frac + (zoned ? 'Z' : '');
}

/**
 * One Arrow vector as a column: data (the Arrow buffer where it can be: a Float64Array with no nulls), get(i) (the JSON value), and
 * its type for a grid (number, text, date, boolean).
 * @param {Object} vec an Arrow Vector
 * @param {Object} field its Field
 * @param {Object} A Arrow JS
 * @returns {{data: (Array|Float64Array), get: function(number): *, type: string, copied: boolean}}
 */
export function readColumn(vec, field, A) {
  const T = A.Type, id = field.type.typeId, n = vec.length, nulls = vec.nullCount > 0;
  const valid = (i) => !nulls || vec.isValid(i);
  if (id === T.Float || (id === T.Int && field.type.bitWidth <= 32)) {
    const raw = vec.data.length === 1 ? vec.data[0].values : vec.toArray();
    if (!nulls && raw instanceof Float64Array) { return { data: raw, get: (i) => raw[i], type: 'number', copied: vec.data.length !== 1 }; }
    if (!nulls) { return { data: raw, get: (i) => raw[i], type: 'number', copied: vec.data.length !== 1 }; }
    return { data: raw, get: (i) => (valid(i) ? Number(raw[i]) : null), type: 'number', copied: vec.data.length !== 1 };
  }
  if (id === T.Int) {
    const raw = vec.toArray();
    let safe = true;
    for (let i = 0; i < n && safe; i++) { if (valid(i) && (raw[i] > SAFE || raw[i] < -SAFE)) { safe = false; } }
    if (safe) { const data = Float64Array.from({ length: n }, (_, i) => (valid(i) ? Number(raw[i]) : NaN)); return { data: data, get: (i) => (valid(i) ? data[i] : null), type: 'number', copied: true }; }
    const data = Array.from({ length: n }, (_, i) => (!valid(i) ? null : raw[i] > SAFE || raw[i] < -SAFE ? raw[i].toString() : Number(raw[i])));
    return { data: data, get: (i) => data[i], type: 'text', copied: true };
  }
  if (id === T.Dictionary) {
    const data = new Array(n);
    let k = 0, type = 'text';
    vec.data.forEach((chunk) => {
      const dict = readColumn(chunk.dictionary, { type: field.type.dictionary }, A), words = dict.data, keys = chunk.values;
      type = dict.type;
      for (let i = 0; i < chunk.length; i++, k++) { data[k] = chunk.getValid(i) ? words[keys[i]] : null; }
    });
    return { data: data, get: (i) => data[i], type: type, copied: false };
  }
  if (id === T.Utf8 && vec.data.length) {
    const data = new Array(n), dec = new TextDecoder();
    let k = 0;
    vec.data.forEach((chunk) => {
      const offs = chunk.valueOffsets, bytes = chunk.values, all = dec.decode(bytes.subarray(offs[0], offs[chunk.length]));
      const ascii = all.length === offs[chunk.length] - offs[0];
      for (let i = 0; i < chunk.length; i++, k++) {
        if (!chunk.getValid(i)) { data[k] = null; continue; }
        data[k] = ascii ? all.substring(offs[i] - offs[0], offs[i + 1] - offs[0]) : dec.decode(bytes.subarray(offs[i], offs[i + 1]));
      }
    });
    return { data: data, get: (i) => data[i], type: 'text', copied: true };
  }
  let conv = null, type = 'text';
  if (id === T.Decimal) { conv = (v) => decimalText(v, field.type.scale, A); }
  else if (id === T.Date) { conv = (v) => isoDay(Number(v)); type = 'date'; }
  else if (id === T.Timestamp) { conv = (v) => isoTime(Number(v), !!field.type.timezone); }
  else if (id === T.Bool) { conv = (v) => !!v; type = 'boolean'; }
  else if (id !== T.Utf8 && id !== T.LargeUtf8) { conv = (v) => (typeof v === 'bigint' ? Number(v) : v); }
  const data = new Array(n);
  for (let i = 0; i < n; i++) { const v = valid(i) ? vec.get(i) : null; data[i] = v === null || v === undefined ? null : conv ? conv(v) : v; }
  return { data: data, get: (i) => data[i], type: type, copied: true };
}

function decimalText(raw, scale, A) {
  let s = A.util.bigNumToString(raw);
  if (!scale) { return s; }
  const neg = s[0] === '-';
  if (neg) { s = s.slice(1); }
  s = s.padStart(scale + 1, '0');
  return (neg ? '-' : '') + s.slice(0, s.length - scale) + '.' + s.slice(s.length - scale);
}

/**
 * A table, column by column, from Arrow or from JSON: the same API either way.
 */
export class DataFrame {
  /**
   * @param {{format: string, names: string[], length: number, columns: Object<string, {data: *, get: function(number): *, type: string}>, meta: Object, bytes: number}} p
   */
  constructor(p) {
    Object.assign(this, { format: p.format, names: p.names, length: p.length, cols: p.columns, meta: p.meta || {}, bytes: p.bytes || 0 });
    this._rows = null;
  }

  /**
   * A column's data (a typed array for numbers where it can be).
   * @param {string} name
   * @returns {*}
   */
  column(name) { return this.cols[name] ? this.cols[name].data : undefined; }

  /**
   * One cell, as JSON would have it.
   * @param {string} name
   * @param {number} i
   * @returns {*}
   */
  value(name, i) { return this.cols[name].get(i); }

  /**
   * The columns' types for a grid.
   * @returns {Array<{name: string, type: string}>}
   */
  fields() { return this.names.map((n) => ({ name: n, type: this.cols[n].type })); }

  /**
   * The rows as views: each holds its row number and reads the columns when a property is asked (row.MTM); toObject() makes a plain copy.
   * @returns {Object[]}
   */
  rows() {
    if (this._rows) { return this._rows; }
    const cols = this.cols, names = this.names;
    function Row(i) { this.$i = i; }
    names.forEach((n) => Object.defineProperty(Row.prototype, n, { get() { return cols[n].get(this.$i); }, set(v) { Object.defineProperty(this, n, { value: v, writable: true, enumerable: true, configurable: true }); }, enumerable: true, configurable: true }));
    Row.prototype.toObject = function () { const o = {}; names.forEach((n) => { o[n] = this[n]; }); return o; };
    Row.prototype.toJSON = Row.prototype.toObject;
    const out = new Array(this.length);
    for (let i = 0; i < this.length; i++) { out[i] = new Row(i); }
    this._rows = out;
    return out;
  }

  /**
   * The rows as plain objects (a copy).
   * @returns {Object[]}
   */
  objects() { return this.rows().map((r) => r.toObject()); }

  /**
   * What a drishti-grid needs: column definitions from the types and the row views.
   * @param {{id: string, formats: Object<string, string>, headers: Object<string, string>}} [o] id: the column that identifies a row
   * @returns {{columns: Object[], rows: Object[], getRowId: ?function(Object): string}}
   */
  toGrid(o) {
    o = o || {};
    const columns = this.names.map((n) => ({ field: n, header: (o.headers && o.headers[n]) || n, type: this.cols[n].type === 'boolean' ? 'text' : this.cols[n].type, format: o.formats && o.formats[n] }));
    return { columns: columns, rows: this.rows(), getRowId: o.id ? (r) => String(r[o.id]) : null };
  }
}

/**
 * A frame from Arrow IPC bytes (the metadata drishti.answer, when the server wrote one, is its meta).
 * @param {(ArrayBuffer|Uint8Array)} buf
 * @param {Object} A Arrow JS
 * @returns {DataFrame}
 */
export function fromArrow(buf, A) {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  return fromArrowTable(A.tableFromIPC(bytes), A, bytes.byteLength);
}

/**
 * A frame from an Arrow Table.
 * @param {Object} tb
 * @param {Object} A
 * @param {number} [bytes]
 * @returns {DataFrame}
 */
export function fromArrowTable(tb, A, bytes) {
  const meta = tb.schema.metadata, cols = {};
  tb.schema.fields.forEach((f, i) => { cols[f.name] = readColumn(tb.getChildAt(i), f, A); });
  let answer = {};
  try { answer = JSON.parse((meta && meta.get('drishti.answer')) || '{}'); } catch (e) { answer = {}; }
  return new DataFrame({ format: 'arrow', names: tb.schema.fields.map((f) => f.name), length: tb.numRows, columns: cols, meta: answer, bytes: bytes || 0 });
}

/**
 * A frame from JSON rows: records ([{a: 1}]) or a {columns, rows} answer.
 * @param {(Object[]|{columns: Array, rows: Array})} json
 * @param {number} [bytes]
 * @returns {DataFrame}
 */
export function fromJson(json, bytes) {
  let names, get;
  const records = Array.isArray(json) ? json : null;
  if (records) { const seen = new Map(); records.forEach((r) => Object.keys(r).forEach((k) => seen.set(k, 1))); names = Array.from(seen.keys()); get = (n, i) => (records[i][n] === undefined ? null : records[i][n]); }
  else { names = (json.columns || []).map((c) => (c && typeof c === 'object' ? c.name : String(c))); get = (n, i) => json.rows[i][names.indexOf(n)]; }
  const length = records ? records.length : (json.rows || []).length, cols = {};
  names.forEach((n) => {
    const vals = Array.from({ length: length }, (_, i) => get(n, i));
    const numeric = vals.length && vals.every((v) => v === null || typeof v === 'number');
    const data = numeric ? Float64Array.from(vals, (v) => (v === null ? NaN : v)) : vals;
    const type = numeric ? 'number' : vals.every((v) => v === null || /^\d{4}-\d{2}-\d{2}$/.test(String(v))) && vals.some((v) => v !== null) ? 'date' : vals.every((v) => v === null || typeof v === 'boolean') && vals.some((v) => v !== null) ? 'boolean' : 'text';
    cols[n] = { data: data, get: (i) => (vals[i] === undefined ? null : vals[i]), type: type };
  });
  const meta = records ? {} : Object.assign({}, json, { columns: undefined, rows: undefined });
  return new DataFrame({ format: 'json', names: names, length: length, columns: cols, meta: meta, bytes: bytes || 0 });
}

/**
 * Reads a fetch Response as a frame: Arrow when it is Arrow (batch by batch to onBatch when asked), JSON otherwise; a problem
 * (status 400 and up) rejects with the server's words (.status, .code, .detail).
 * @param {Response} r
 * @param {{Arrow: Object, onBatch: function(number, number): void}} [o] onBatch(rowsSoFar, batches)
 * @returns {Promise<DataFrame>}
 */
export function readResponse(r, o) {
  o = o || {};
  const kind = (r.headers.get('content-type') || '').toLowerCase();
  if (r.ok && kind.indexOf(ARROW_MEDIA) >= 0) {
    return arrowLib(o.Arrow).then((A) => {
      if (o.onBatch && r.body && A.RecordBatchReader) {
        return A.RecordBatchReader.from(r.body).then((reader) => reader.open ? reader.open() : reader).then(async (reader) => {
          const batches = [];
          let rows = 0;
          for await (const b of reader) { batches.push(b); rows += b.numRows; o.onBatch(rows, batches.length); }
          return fromArrowTable(batches.length ? new A.Table(batches) : new A.Table(reader.schema), A);
        });
      }
      return r.arrayBuffer().then((buf) => fromArrow(buf, A));
    });
  }
  return r.text().then((text) => {
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (e) { data = {}; }
    if (!r.ok) {
      const e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText || 'HTTP ' + r.status));
      Object.assign(e, { status: r.status, code: data.code, detail: data.detail || r.statusText, fix: data.fix, body: data });
      throw e;
    }
    return fromJson(Array.isArray(data) ? data : data.rows && !data.columns ? data.rows : data, text.length);
  });
}

/**
 * Fetches a table: Arrow over HTTP where the server can, JSON where it cannot.
 * @param {string} url
 * @param {{method: string, body: Object, headers: Object, fetch: Function, Arrow: Object, signal: AbortSignal, onBatch: function(number, number): void}} [o]
 * @returns {Promise<DataFrame>}
 */
export function fetchFrame(url, o) {
  o = o || {};
  const f = o.fetch || globalThis.fetch.bind(globalThis);
  const init = { method: o.method || 'GET', credentials: 'same-origin', signal: o.signal, headers: Object.assign({ Accept: ARROW_MEDIA + ', application/json;q=0.9' }, o.headers) };
  if (o.body !== undefined && init.method !== 'GET') { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(o.body); }
  return f(url, init).then((r) => readResponse(r, o));
}

/**
 * A server's data, read the best way it offers: Flight over HTTP (a ticket POSTed to its do-get address) when its capabilities say so,
 * Arrow over HTTP otherwise.
 */
export class ArrowClient {
  /**
   * @param {{capabilitiesUrl: string, flight: (boolean|{doGet: string}), fetch: Function, Arrow: Object, headers: Object}} [o]
   *   capabilitiesUrl: where the server says what it offers ({flightHttp: {doGet: url}}); flight: false never asks, an object names it
   */
  constructor(o) {
    this.o = Object.assign({ capabilitiesUrl: '/api/v1/data/capabilities' }, o);
    this.caps = null;
  }

  /**
   * What the server offers: {flightHttp: ?{doGet: string}} (asked once; a server that does not answer offers Arrow over HTTP only).
   * @returns {Promise<{flightHttp: ?{doGet: string}}>}
   */
  capabilities() {
    if (this.caps) { return this.caps; }
    if (this.o.flight === false) { this.caps = Promise.resolve({ flightHttp: null }); return this.caps; }
    if (this.o.flight && this.o.flight.doGet) { this.caps = Promise.resolve({ flightHttp: { doGet: this.o.flight.doGet } }); return this.caps; }
    const f = this.o.fetch || globalThis.fetch.bind(globalThis);
    this.caps = f(this.o.capabilitiesUrl, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : {}), () => ({}))
      .then((c) => ({ flightHttp: c && c.flightHttp && c.flightHttp.doGet ? { doGet: c.flightHttp.doGet } : null }), () => ({ flightHttp: null }));
    return this.caps;
  }

  /**
   * Reads a request: {ticket} through Flight over HTTP when offered, else {url} (and body) as Arrow over HTTP.
   * @param {{ticket: Object, url: string, method: string, body: Object, onBatch: function(number, number): void, signal: AbortSignal}} req
   * @returns {Promise<DataFrame & {via: string}>}
   */
  read(req) {
    return this.capabilities().then((c) => {
      const base = { fetch: this.o.fetch, Arrow: this.o.Arrow, headers: this.o.headers, onBatch: req.onBatch, signal: req.signal };
      if (req.ticket && c.flightHttp) {
        return fetchFrame(c.flightHttp.doGet, Object.assign(base, { method: 'POST', body: { ticket: req.ticket }, headers: Object.assign({ Accept: ARROW_MEDIA }, this.o.headers) })).then((fr) => Object.assign(fr, { via: 'flight-http' }));
      }
      if (!req.url) { throw new Error('this server offers no Flight over HTTP: give the request\'s address (url) to read it as Arrow over HTTP'); }
      return fetchFrame(req.url, Object.assign(base, { method: req.method || (req.body ? 'POST' : 'GET'), body: req.body })).then((fr) => Object.assign(fr, { via: fr.format === 'arrow' ? 'arrow-http' : 'json' }));
    });
  }
}
