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
/* Arrow to the browser (2.9.7, "Arrow where it pays", docs/guides/DATA_FORMATS.md section 6). One helper reads a table whichever format
   came back: drishtiFetchTable(url, opts) asks with Accept: application/vnd.apache.arrow.stream when Arrow JS is on the page (the vendored
   /static/vendor/apache-arrow/<version>/Arrow.es2015.min.js, loaded only by the pages that draw big tables), and with Accept: application/json
   otherwise; the console and the server answer Arrow where they can and JSON where they cannot. The result is column-oriented:

     t.format              'arrow' or 'json'
     t.names, t.length     the table's columns and rows
     t.column(name)        the column: a Float64Array for numbers (NaN where a cell is null), an Array for text (null for a null)
     t.value(name, i)      one cell exactly as the JSON answer has it (null, a number, a string)
     t.isNull(name, i)
     t.answer()            the whole answer, equal to the JSON one cell for cell (the table put back where it was)
     t.bytes               the bytes read (for the measurements)

   Types: 64-bit integers (BigInt in Arrow JS) become Numbers when every value of the column is within 2^53, and the values past it become
   strings (JSON would have rounded them); decimals become exact strings; dates 'YYYY-MM-DD'; timestamps ISO text, with Z when the column has
   a time zone (the instant) and without when it has none (a wall-clock time). The answer around the table travels in the schema's metadata
   ('drishti.answer', 'drishti.table'), written by the server's ArrowAnswers or the console's core/arrow_answer.py. */
(function () {
  'use strict';
  const MEDIA = 'application/vnd.apache.arrow.stream';
  const SAFE = BigInt(Number.MAX_SAFE_INTEGER);

  function arrow() { return window.Arrow && typeof window.Arrow.tableFromIPC === 'function' ? window.Arrow : null; }

  function pad(n, w) { return String(n).padStart(w || 2, '0'); }
  function isoDay(ms) { const d = new Date(ms); return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()); }
  function isoTime(ms, zoned) {
    const d = new Date(ms);
    const frac = d.getUTCMilliseconds() ? '.' + pad(d.getUTCMilliseconds(), 3) : '';
    return isoDay(ms) + 'T' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds()) + frac + (zoned ? 'Z' : '');
  }
  function decimalText(raw, scale, A) {
    let s = A.util.bigNumToString(raw);
    if (!scale) { return s; }
    const neg = s[0] === '-';
    if (neg) { s = s.slice(1); }
    s = s.padStart(scale + 1, '0');
    return (neg ? '-' : '') + s.slice(0, s.length - scale) + '.' + s.slice(s.length - scale);
  }

  /** One Arrow vector as a column: { data (typed array or Array), get(i) (the JSON value), nulls }. */
  function readColumn(vec, field, A) {
    const T = A.Type, id = field.type.typeId, n = vec.length, nulls = vec.nullCount > 0;
    const valid = (i) => !nulls || vec.isValid(i);
    if (id === T.Float || (id === T.Int && field.type.bitWidth <= 32)) {
      const raw = vec.toArray();
      const data = raw instanceof Float64Array && !nulls ? raw : Float64Array.from({ length: n }, (_, i) => (valid(i) ? Number(raw[i]) : NaN));
      return { data, nulls, get: (i) => (valid(i) ? data[i] : null) };
    }
    if (id === T.Int) {                                                   // 64-bit: Number where safe, text where not
      const raw = vec.toArray();
      let safe = true;
      for (let i = 0; i < n && safe; i++) { if (valid(i) && (raw[i] > SAFE || raw[i] < -SAFE)) { safe = false; } }
      if (safe) {
        const data = Float64Array.from({ length: n }, (_, i) => (valid(i) ? Number(raw[i]) : NaN));
        return { data, nulls, get: (i) => (valid(i) ? data[i] : null) };
      }
      const data = Array.from({ length: n }, (_, i) => (!valid(i) ? null : raw[i] > SAFE || raw[i] < -SAFE ? raw[i].toString() : Number(raw[i])));
      return { data, nulls, get: (i) => data[i] };
    }
    if (id === T.Dictionary) {                                            // repeated text: each distinct value decoded once, the cells share it
      const data = new Array(n);
      let k = 0;
      vec.data.forEach((chunk) => {
        const words = readColumn(chunk.dictionary, { type: field.type.dictionary }, A).data;
        const keys = chunk.values;
        for (let i = 0; i < chunk.length; i++, k++) { data[k] = chunk.getValid(i) ? words[keys[i]] : null; }
      });
      return { data, nulls, get: (i) => data[i] };
    }
    if ((id === T.Utf8) && vec.data.length) {                             // plain text: one decode of each chunk's bytes, cut at the offsets
      const data = new Array(n);
      let k = 0;
      const dec = new TextDecoder();
      vec.data.forEach((chunk) => {
        const offs = chunk.valueOffsets, bytes = chunk.values;
        const all = dec.decode(bytes.subarray(offs[0], offs[chunk.length]));
        const ascii = all.length === offs[chunk.length] - offs[0];        // one byte a character: the offsets cut the text itself
        for (let i = 0; i < chunk.length; i++, k++) {
          if (!chunk.getValid(i)) { data[k] = null; continue; }
          data[k] = ascii ? all.substring(offs[i] - offs[0], offs[i + 1] - offs[0]) : dec.decode(bytes.subarray(offs[i], offs[i + 1]));
        }
      });
      return { data, nulls, get: (i) => data[i] };
    }
    let conv = null;
    if (id === T.Decimal) { conv = (v) => decimalText(v, field.type.scale, A); }
    else if (id === T.Date) { conv = (v) => isoDay(Number(v)); }
    else if (id === T.Timestamp) { conv = (v) => isoTime(Number(v), !!field.type.timezone); }
    else if (id === T.Bool) { conv = (v) => !!v; }
    else if (id !== T.Utf8 && id !== T.LargeUtf8) { conv = (v) => (typeof v === 'bigint' ? Number(v) : v); }
    const data = new Array(n);
    for (let i = 0; i < n; i++) { const v = valid(i) ? vec.get(i) : null; data[i] = v == null ? null : conv ? conv(v) : v; }
    return { data, nulls, get: (i) => data[i] };
  }

  function make(format, names, length, cols, rest, spec, bytes) {
    const t = {
      format, names, length, bytes,
      column: (name) => (cols[name] ? cols[name].data : undefined),
      value: (name, i) => cols[name].get(i),
      isNull: (name, i) => cols[name].get(i) === null,
      /** The table as rows (arrays in the order of `names`), as the JSON answer's `rows` are. */
      /** Row i as an array in the order of `names`. */
      row(i) { return names.map((n) => cols[n].get(i)); },
      rows() {
        const out = new Array(length), cs = names.map((n) => cols[n]);
        for (let i = 0; i < length; i++) { const r = new Array(cs.length); for (let c = 0; c < cs.length; c++) { r[c] = cs[c].get(i); } out[i] = r; }
        return out;
      },
      /** The whole answer, equal to the JSON one (the table put back where it came from). */
      answer() {
        const a = Object.assign({}, rest);
        if (spec.shape === 'rows') { a[spec.at] = t.rows(); }
        else if (spec.shape === 'records') {
          const absent = new Set(spec.absent || []);
          a[spec.at] = Array.from({ length }, (_, i) => {
            const o = {};
            names.forEach((n) => { const v = cols[n].get(i); if (!(v === null && absent.has(n))) { o[n] = v; } });
            return o;
          });
        } else { names.forEach((n) => { a[n] = Array.from({ length }, (_, i) => cols[n].get(i)); }); }
        return a;
      },
      /** The answer with its rows read only when something asks for them (a BI answer: the grid reads the columns, a chart the rows). */
      lazyAnswer() {
        if (spec.shape !== 'rows') { return t.answer(); }
        const a = Object.assign({}, rest);
        let rows = null;
        Object.defineProperty(a, spec.at, { enumerable: true, configurable: true, get: () => rows || (rows = t.rows()), set: (v) => { rows = v; } });
        Object.defineProperty(a, '__table', { value: t, enumerable: false });
        return a;
      }
    };
    return t;
  }

  /** A table from an Arrow stream (bytes) written by ArrowAnswers or core/arrow_answer.py. */
  function fromArrow(buf) {
    const A = arrow();
    if (!A) { throw new Error('Arrow JS is not loaded on this page'); }
    const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
    const tb = A.tableFromIPC(bytes);
    const meta = tb.schema.metadata;
    const rest = JSON.parse(meta.get('drishti.answer') || '{}');
    const spec = JSON.parse(meta.get('drishti.table') || '{"shape":"rows","at":"rows"}');
    const names = tb.schema.fields.map((f) => f.name);
    const cols = {};
    tb.schema.fields.forEach((f, i) => { cols[f.name] = readColumn(tb.getChildAt(i), f, A); });
    return make('arrow', names, tb.numRows, cols, rest, spec, bytes.byteLength);
  }

  /** A table from a JSON answer; `shape` and `at` say where its table is ('rows' of `columns`, 'records' or 'arrays' of keys). */
  function fromJson(answer, shape, at, bytes) {
    shape = shape || 'rows';
    at = at || 'rows';
    const rest = Object.assign({}, answer);
    const cols = {};
    let names = [], length = 0;
    const columnOf = (values) => {
      const numeric = values.every((v) => v === null || typeof v === 'number');
      const data = numeric ? Float64Array.from(values, (v) => (v === null ? NaN : v)) : values.map((v) => (v === undefined ? null : v));
      return { data, nulls: values.some((v) => v === null), get: (i) => (values[i] === undefined ? null : values[i]) };
    };
    let spec;
    if (shape === 'rows') {
      const rows = answer[at] || [];
      delete rest[at];
      names = (answer.columns || []).map((c) => (c && typeof c === 'object' ? c.name : String(c)));
      length = rows.length;
      names.forEach((n, c) => { cols[n] = columnOf(rows.map((r) => r[c])); });
      spec = { shape, at };
    } else if (shape === 'records') {
      const rows = answer[at] || [];
      delete rest[at];
      const seen = new Map(), absent = [];
      rows.forEach((r) => Object.keys(r).forEach((k) => seen.set(k, 1)));
      names = Array.from(seen.keys());
      length = rows.length;
      names.forEach((n) => { if (rows.some((r) => !(n in r))) { absent.push(n); } cols[n] = columnOf(rows.map((r) => (n in r ? r[n] : null))); });
      spec = { shape, at, absent };
    } else {
      names = at.split(',');
      names.forEach((n) => { const v = answer[n] || []; delete rest[n]; length = v.length; cols[n] = columnOf(v); });
      spec = { shape, keys: names };
    }
    return make('json', names, length, cols, rest, spec, bytes || 0);
  }

  /** The Accept header a call for a table sends: Arrow first when this page can read it. */
  function accept() { return arrow() && !jsonOnly() ? MEDIA + ', application/json;q=0.9' : 'application/json'; }
  /** ?tables=json in the page's address asks JSON even where Arrow is loaded: to compare the two, or to rule Arrow out of a problem. */
  function jsonOnly() { return /[?&]tables=json(&|$)/.test(window.location.search); }

  /** Reads a fetch Response into a table, whichever format it is; a problem (status 400 and up) rejects with .code, .status, .detail. */
  function fromResponse(r, shape, at) {
    const kind = (r.headers.get('content-type') || '').toLowerCase();
    if (r.ok && kind.indexOf(MEDIA) >= 0 && arrow()) { return r.arrayBuffer().then(fromArrow); }
    return r.text().then((text) => {
      let data = {};
      try { data = text ? JSON.parse(text) : {}; } catch (e) { data = {}; }
      if (!r.ok) {
        const e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText || ('HTTP ' + r.status)));
        e.code = data.code; e.status = r.status; e.detail = data.detail || r.statusText;
        if (data.fix) { e.fix = data.fix; }
        if (Array.isArray(data.problems)) { e.problems = data.problems; }
        e.body = data;
        throw e;
      }
      return fromJson(data, shape, at, text.length);
    });
  }

  /**
   * Fetches a table: `opts` = { method, body (an object: sent as JSON), headers, shape, at }. Resolves with the column-oriented table above,
   * read from Arrow when the answer is Arrow and from JSON otherwise.
   */
  function drishtiFetchTable(url, opts) {
    const o = opts || {};
    const init = { method: o.method || 'GET', credentials: 'same-origin', headers: Object.assign({ Accept: accept() }, o.headers || {}) };
    if (o.body !== undefined && init.method !== 'GET') { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(o.body); }
    return fetch(url, init).then((r) => fromResponse(r, o.shape, o.at));
  }

  window.drishtiTable = { MEDIA, available: () => !!arrow() && !jsonOnly(), accept, fromArrow, fromJson, fromResponse };
  window.drishtiFetchTable = drishtiFetchTable;
}());
