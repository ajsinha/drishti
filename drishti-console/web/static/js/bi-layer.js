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

  /**
   * The things a consumer can read, grouped for a picker: Models (published first, each with its version and state so a draft and a published copy of one
   * name are told apart), Your datasets, then the packs' datasets, one group per pack (a kind with no columns is left out: there is nothing to read in it).
   * Every entry is {value, label, ref, kind}: `ref` is what a consumer binds (`name@major` for a model, `dataset:<name>` for a dataset), `value` is `mq:`+ref
   * for a model and `dataset:`+name for a dataset (the form Analyse's extract takes). `opts.legacy` adds the version 1 datasets as a last group.
   */
  B.layer.groups = function (opts) {
    opts = opts || {};
    return B.layer.get('datasets').then((d) => {
      const groups = [];
      const stateOf = (m) => (m.status === 'published' ? 'published' : m.status || 'draft');
      const models = (d.models || []).filter((m) => m.kind !== 'compat').slice().sort((a, b) => (a.status === 'published' ? 0 : 1) - (b.status === 'published' ? 0 : 1) || String(a.title).localeCompare(String(b.title)));
      if (models.length) {
        groups.push({ label: 'Models', items: models.map((m) => {
          const ref = m.query || (m.name + '@1');
          return { value: 'mq:' + ref, ref, kind: 'model', label: (m.title || m.name) + ' (model ' + (m.version || ref.split('@')[1] || '1') + ', ' + stateOf(m) + ')', title: m.title || m.name };
        }) });
      }
      if ((d.items || []).length) {
        groups.push({ label: 'Your datasets', items: d.items.map((x) => ({ value: 'dataset:' + x.name, ref: 'dataset:' + x.name, kind: 'dataset', label: x.title || x.name, title: x.title || x.name })) });
      }
      const byPack = {};
      (d.packs || []).filter((x) => x.columns == null || x.columns > 0).forEach((x) => { (byPack[x.pack] = byPack[x.pack] || []).push(x); });
      Object.keys(byPack).sort().forEach((pk) => groups.push({ label: 'Pack ' + pk, items: byPack[pk].map((x) => ({ value: 'dataset:' + x.name, ref: 'dataset:' + x.name, kind: 'dataset', label: x.title || x.name, title: x.title || x.name })) }));
      return opts.legacy ? opts.legacy().then((list) => {
        if (list && list.length) { groups.push({ label: 'Other datasets (version 1)', items: list.map((x) => ({ value: x.id, ref: x.id, kind: 'legacy', label: x.title || x.id, title: x.title || x.id })) }); }
        return groups;
      }, () => groups) : groups;
    });
  };
  /** Fills a select with `groups` (optgroups) after a placeholder option. */
  B.layer.fillSelect = function (sel, groups, placeholder) {
    B.clear(sel);
    sel.appendChild(B.h('option', { value: '', text: placeholder || '(choose)' }));
    groups.forEach((g) => sel.appendChild(B.h('optgroup', { label: g.label }, g.items.map((i) => B.h('option', { value: i.value, text: i.label })))));
  };

  /** Who uses a model: {known, consumers[{kind, id, title, href}]} from the server's consumers list. A report references a model, it never owns it. */
  B.layer.consumers = (ref) => B.layer.get('consumers?ref=' + encodeURIComponent(ref)).catch(() => ({ known: false, consumers: [] }));
  const KIND_WORD = { report: 'Reports', board: 'Boards', alert: 'Alerts', subscription: 'Subscriptions', notebook: 'Notebooks' };
  /** Draws the "Used by" list into `host`, grouped by kind; says so when the server cannot list the consumers yet. Returns the answer. */
  B.layer.drawUsedBy = (host, ref) => B.layer.consumers(ref).then((r) => {
    B.clear(host);
    if (!r.known) { host.appendChild(B.h('p', { class: 'text-muted-d bi-small-note', 'data-used-by-unknown': '', text: 'The list of what uses this model is not available from the server yet.' })); return r; }
    if (!r.consumers.length) { host.appendChild(B.h('p', { class: 'text-muted-d', 'data-used-by-none': '', text: 'Nothing uses this model yet. Start a report or a board on it: they will share it, and a change to the model reaches them all.' })); return r; }
    Object.keys(KIND_WORD).forEach((k) => {
      const list = r.consumers.filter((c) => c.kind === k);
      if (!list.length) { return; }
      host.appendChild(B.h('h4', { text: KIND_WORD[k] + ' (' + list.length + ')' }));
      host.appendChild(B.h('ul', { class: 'bi-layer-list', 'data-used-by': k }, list.map((c) => B.h('li', {}, B.h('a', { href: c.href, text: c.title })))));
    });
    return r;
  });

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
