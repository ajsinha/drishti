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
/* Rupaka phase 3: what the report viewer, the designer and the Model view share (RUPAKA.md sections 6.2, 8 and 9): DOM building with text only (never markup
   strings), the same-origin JSON calls to /bi/api (passed on to the server under the signed-in user's identity), number formats, the chart palettes and the
   few helpers about report models. Exposes window.drishtiBi. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});

  /** h('div', {class: 'x', text: 'y', 'data-k': 1}, child, [children], 'text') */
  B.h = function (tag, attrs, ...kids) {
    const el = document.createElement(tag);
    Object.keys(attrs || {}).forEach((k) => {
      const v = attrs[k];
      if (k === 'text') { el.textContent = v; } else if (k === 'class') { el.className = v; } else if (v === true) { el.setAttribute(k, ''); } else if (v !== false && v != null) { el.setAttribute(k, v); }
    });
    (function add(list) {
      list.forEach((c) => {
        if (c == null || c === false) { return; }
        if (Array.isArray(c)) { add(c); } else { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
      });
    })(kids);
    return el;
  };
  B.q = (sel, scope) => (scope || document).querySelector(sel);
  B.qa = (sel, scope) => Array.prototype.slice.call((scope || document).querySelectorAll(sel));
  B.clear = (el) => { while (el.firstChild) { el.removeChild(el.firstChild); } return el; };
  B.icon = (name) => B.h('i', { class: 'bi bi-' + name, 'aria-hidden': 'true' });
  B.id2path = (id) => encodeURIComponent(id).replace(/%3A/g, ':');
  B.clone = (o) => JSON.parse(JSON.stringify(o));
  B.same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

  /** A call to the server: rejects with an Error carrying .code (DRS-nnnn), .status and .detail. */
  B.api = function (method, path, body) {
    const opt = { method, headers: { Accept: 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    return fetch('/bi/api' + path, opt).then((r) => r.json().catch(() => ({})).then((data) => {
      if (!r.ok) {
        const e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText));
        e.code = data.code; e.status = r.status; e.detail = data.detail || r.statusText;
        throw e;
      }
      return data;
    }));
  };

  /** Announces a message in a status element (class bi-bad when it is a problem). */
  B.say = function (el, text, bad) {
    if (!el) { return; }
    el.textContent = text || '';
    el.classList.toggle('bi-bad', !!bad);
  };

  // ---- a queue: the server lets one person run three queries at once (drishti.bi.query.max-concurrent-per-user), so a page asks three at a time ---------------
  const queue = [];
  let running = 0;
  B.MAX_PARALLEL = 3;
  function pump() {
    while (running < B.MAX_PARALLEL && queue.length) {
      const t = queue.shift();
      running++;
      t.job().then(t.resolve, (e) => {
        if (e.status === 429 && t.tries++ < 8) { setTimeout(() => { queue.unshift(t); pump(); }, 250 * t.tries); } else { t.reject(e); }       // busy: try again shortly
      }).finally(() => { running--; pump(); });
    }
  }
  /** Runs `job()` (a function returning a promise) when a slot is free; a refusal for busy (429) is retried. */
  B.enqueue = function (job) { return new Promise((resolve, reject) => { queue.push({ job, resolve, reject, tries: 0 }); pump(); }); };

  // ---- numbers -----------------------------------------------------------------------------------------------------

  const GROUP = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });
  const GROUP2 = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const FREE = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
  const MINUS = '−';

  /** Formats a value as the dataset's format name says (amount0, signed0, signed2, pct, compact), else as a plain number. null is the mask or a gap. */
  B.fmt = function (v, name, masked) {
    if (v == null) { return masked ? '•••' : '–'; }
    if (typeof v !== 'number') { return String(v); }
    if (!isFinite(v)) { return String(v); }
    const sign = (x) => (x < 0 ? MINUS : '+');
    switch (name) {
      case 'amount0': return (v < 0 ? MINUS : '') + GROUP.format(Math.abs(v));
      case 'signed0': return sign(v) + GROUP.format(Math.abs(v));
      case 'signed2': return sign(v) + GROUP2.format(Math.abs(v));
      case 'pct': return (Math.abs(v) <= 1 ? FREE.format(v * 100) : FREE.format(v)) + '%';
      case 'compact': return B.compact(v);
      default: return (v < 0 ? MINUS : '') + (Number.isInteger(v) ? GROUP.format(Math.abs(v)) : FREE.format(Math.abs(v)));
    }
  };

  /** 1.2k, 3.4m, 5bn: for axes and tight places. */
  B.compact = function (v) {
    if (typeof v !== 'number' || !isFinite(v)) { return ''; }
    const a = Math.abs(v), s = v < 0 ? MINUS : '';
    const t = (x, d) => String(Number(x.toFixed(d)));
    if (a >= 1e12) { return s + t(a / 1e12, 2) + 'tn'; }
    if (a >= 1e9) { return s + t(a / 1e9, 2) + 'bn'; }
    if (a >= 1e6) { return s + t(a / 1e6, 2) + 'm'; }
    if (a >= 1e4) { return s + t(a / 1e3, 1) + 'k'; }
    return s + (Math.round(a * 100) / 100);
  };

  // ---- colours -----------------------------------------------------------------------------------------------------

  const OKABE = ['#0072b2', '#e69f00', '#009e73', '#cc79a7', '#56b4e9', '#d55e00', '#f0e442', '#666666'];
  B.PALETTES = ['default', 'colour-blind', 'mono'];

  /** The theme's tokens as the page currently has them (they change with the theme). */
  B.tokens = function () {
    const s = getComputedStyle(document.documentElement), t = {};
    ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'ok', 'warn', 'bad', 'surface', 'surface-2', 'bg', 'bg-2'].forEach((k) => { t[k] = s.getPropertyValue('--d-' + k).trim(); });
    t.mono = s.getPropertyValue('--d-font-mono').trim();
    return t;
  };

  /** The series colours of a report's palette (default: the console's own tones; colour-blind: Okabe-Ito; mono: one hue in steps). */
  B.palette = function (name, t) {
    t = t || B.tokens();
    if (name === 'colour-blind') { return OKABE; }
    if (name === 'mono') { return [t.accent, t.muted, t.ink, t.faint || t.border, t.link, t.border]; }
    return [t.link, t.accent, t.ok, t.warn, t.bad, t.muted, t.neg, t.pos];
  };

  // ---- the model of a report -----------------------------------------------------------------------------------------

  /** The dataset's measures by name, and its columns by dotted name (table.column) with their types. */
  B.index = function (dataset) {
    const measures = {}, columns = {}, tables = {};
    (dataset.measures || []).forEach((m) => { measures[m.name] = m; });
    (dataset.tables || []).forEach((t) => {
      tables[t.name] = t;
      t.columns.forEach((c) => { columns[t.name + '.' + c.name] = Object.assign({ table: t.name, kind: t.kind }, c); });
    });
    return { measures, columns, tables, hierarchies: dataset.hierarchies || {} };
  };

  /** The column a field name points at; a date part (table.column.month) answers the column with part set. */
  B.column = function (idx, field) {
    if (idx.columns[field]) { return idx.columns[field]; }
    const bits = field.split('.');
    if (bits.length === 3 && idx.columns[bits[0] + '.' + bits[1]]) { return Object.assign({}, idx.columns[bits[0] + '.' + bits[1]], { part: bits[2], type: 'integer' }); }
    return null;
  };

  /** A friendly label for a field or measure: the column name (with its date part) without the table. */
  B.label = function (name) {
    const bits = String(name).split('.');
    return bits.length > 1 ? bits.slice(1).join(' ') : name;
  };

  /** Escapes nothing: text goes in with textContent. A stable id fragment for data-* attributes and test selectors. */
  B.slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

  B.ROW_PX = 56;                 // the height of one grid row of a report, in pixels (also in bi-report.css)
  B.GAP_PX = 10;
})();
