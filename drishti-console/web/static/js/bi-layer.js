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
/* The model layer's shared helpers (docs/architecture/BI_MODEL_SPEC.md section 5): B.layer.post/get for the console's /bi/model-api routes, and B.mq, which
   turns a report visual (its wells, its own, its page's and its report's filters, the reader's slicer and cross-filter choices) into ONE model query and asks
   the server's /mq as the signed-in person. The answer has the shape a visual draws (columns by name, rows, meta), so a visual on a model draws exactly as
   one on a dataset. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});

  function call(method, path, body) {
    const opt = { method, headers: { Accept: 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    return fetch(path, opt).then((r) => r.json().catch(() => ({})).then((d) => {
      if (!r.ok) {
        const e = new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText));
        e.code = d.code; e.status = r.status; e.detail = d.detail || r.statusText; e.path = d.path; e.fix = d.fix;
        throw e;
      }
      return d;
    }));
  }
  B.layer = {
    get: (path) => call('GET', '/bi/model-api/' + path),
    post: (path, body) => call('POST', '/bi/model-api/' + path, body),
    /** The model a report body names (`model: trading-risk@1` or `model: dataset:pack.trading.trade`), or ''. */
    modelOf: (body) => { const m = /^model:\s*(\S+)\s*$/m.exec(body || ''); return m ? m[1].replace(/^["']|["']$/g, '') : ''; },
    /** A short label for a model reference: "Implicit model of Trade" is the server's title; this is for links. */
    href: (ref) => (ref.indexOf('dataset:') === 0 ? '/bi/ds/' + encodeURIComponent(ref.slice(8)) : '')
  };

  // ---- one visual as a model query ----------------------------------------------------------------------------------
  const OPS = { eq: 'eq', ne: 'ne', in: 'in', 'not-in': 'not-in', lt: 'lt', le: 'le', gt: 'gt', ge: 'ge', between: 'between', contains: 'contains', 'starts-with': 'starts-with', 'is-null': 'is-null', 'not-null': 'not-null', relative: 'relative', top: 'top' };

  function filterOf(f, isMeasure) {
    const op = OPS[f.op || 'eq'] || 'eq';
    if (isMeasure(f.field)) {
      const hv = { measure: f.field, op: op === 'in' ? 'in' : op };
      if (f.values) { hv.values = f.values; } else { hv.value = f.value; }
      return { having: hv };
    }
    const w = { field: f.field, op };
    if (f.values) { w.values = f.values; }
    if (f.value !== undefined) { w.value = f.value; }
    if (op === 'top') { w.n = f.n; w.by = f.by; w.dir = f.dir || 'desc'; }
    if (op === 'relative') { w.window = f.window || f.value; }
    return { where: w };
  }

  /**
   * The model query of one visual: fields are its wells' fields, measures its wells' measures (order kept, as the visual reads its columns by name), filters
   * from the report, the page, the visual and the reader's choices (slicers and other visuals' selections), its sort and limit.
   * @param idx the model's index (B.index of the Fields shape): what is a measure
   * @param extra {filters: [...]} more filters (slicers, selections), already as {field, op, value(s)}
   */
  B.mq = function (ref, report, page, v, idx, extra) {
    const isMeasure = (n) => !!(idx && idx.measures && idx.measures[n]);
    const fields = [], measures = [];
    Object.keys(v.wells || {}).forEach((w) => (v.wells[w] || []).forEach((n) => {
      const list = isMeasure(n) ? measures : fields;
      if (list.indexOf(n) < 0) { list.push(n); }
    }));
    const q = { mq: 1, model: ref, select: { fields, measures } };
    const where = [], having = [];
    [].concat((report && report.filters) || [], (page && page.filters) || [], v.filters || [], (extra && extra.filters) || []).forEach((f) => {
      if (!f || !f.field) { return; }
      const x = filterOf(f, isMeasure);
      if (x.having) { having.push(x.having); } else { where.push(x.where); }
    });
    if (where.length) { q.where = where; }
    if (having.length) { q.having = having; }
    if (v.sort && v.sort.by) { q.sort = [{ by: v.sort.by, desc: !!v.sort.desc }]; }
    if (v.limit) { q.limit = v.limit; }
    if (report && report.dates && report.dates.asOf) { q.time = { 'as-of': report.dates.asOf }; }
    if (!fields.length && !measures.length) { return null; }
    return q;
  };

  /** Asks the server one model query; the answer as a visual reads it. A refusal carries its code, the path that caused it and the fix. */
  B.mq.ask = function (q) {
    return B.api('POST', '/mq', q).then((a) => ({
      columns: a.columns, rows: a.rows, rowCount: a.rowCount != null ? a.rowCount : (a.rows || []).length, totals: a.totals,
      meta: Object.assign({ millis: 0, masked: [] }, a.meta || {}, { model: a.model, mq: a.meta && a.meta.query })
    }));
  };

  /** The distinct values of one field of a model (a filter's picker), as the person sees them. */
  B.mq.values = function (ref, field, limit) {
    return B.api('POST', '/mq', { mq: 1, model: ref, select: { fields: [field], measures: [] }, limit: limit || 200 });
  };

  /** The YAML of a query, for the visual's Query panel (keys in the spec's order; values JSON-quoted when needed). */
  B.mq.yaml = function (q) {
    const scalar = (v) => (typeof v === 'string' && /^[A-Za-z0-9_.@:\- ]+$/.test(v) && !/^\s|\s$/.test(v) && !/^(true|false|null|yes|no)$/i.test(v) ? v : JSON.stringify(v));
    const flow = (v) => (Array.isArray(v) ? '[' + v.map(flow).join(', ') + ']' : v && typeof v === 'object' ? '{ ' + Object.keys(v).map((k) => scalar(k) + ': ' + flow(v[k])).join(', ') + ' }' : scalar(v));
    return Object.keys(q).map((k) => {
      const v = q[k];
      if (Array.isArray(v) && v.length && typeof v[0] === 'object') { return k + ':\n' + v.map((x) => '  - ' + flow(x)).join('\n'); }
      if (v && typeof v === 'object' && !Array.isArray(v)) { return k + ':\n' + Object.keys(v).map((kk) => '  ' + scalar(kk) + ': ' + flow(v[kk])).join('\n'); }
      return k + ': ' + flow(v);
    }).join('\n') + '\n';
  };
})();
