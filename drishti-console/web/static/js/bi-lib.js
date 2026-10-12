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

  /**
   * "View as readers see it": a page opened with ?as=published asks the server for the published version of every report and board it draws, and (the data
   * still being drawn as the person looking) masks it for them. The parameter is added to those calls and to nothing else.
   */
  B.asPublished = /[?&]as=published(&|$)/.test(window.location.search);
  B.viewPath = (path) => (B.asPublished && /^\/(reports|boards)\//.test(path) ? path + (path.indexOf('?') < 0 ? '?' : '&') + 'as=published' : path);

  /** A call to the server: rejects with an Error carrying .code (DRS-nnnn), .status and .detail. */
  B.api = function (method, path, body) {
    path = B.viewPath(path);
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

  /** On a page opened with ?as=published: says which version is shown, or that nothing is published, with a way back to the draft. */
  B.publishedBanner = function (id, canvas) {
    if (!B.asPublished || !canvas) { return; }
    const back = B.h('a', { href: window.location.pathname, text: 'Back to my draft' });
    const box = B.h('p', { class: 'bi-warn', role: 'status', 'data-published-banner': '' }, 'Loading the published version... ');
    canvas.insertBefore(box, canvas.firstChild);
    B.api('GET', '/items/' + B.id2path(id) + '/published').then((p) => {
      B.clear(box).appendChild(document.createTextNode('Showing the published version ' + p.version + (p.publishedAt ? ' (' + String(p.publishedAt).replace('T', ' ').slice(0, 16) + ')' : '') + ', the way readers see it'
        + (p.draftDiffers ? '; your draft has changes that are not shown' : '') + '. Data is masked for you, as it would be for them. '));
      box.appendChild(back);
    }).catch((e) => { B.clear(box).appendChild(document.createTextNode(e.detail || e.message)); box.appendChild(document.createTextNode(' ')); box.appendChild(back); });
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
    const shows = (x, d) => Math.round(Math.abs(x) * Math.pow(10, d)) > 0;                        // a value that rounds to zero is zero: never "-0.00"
    const sign = (x, d) => (!shows(x, d) ? '' : x < 0 ? MINUS : '+');
    const minus = (x, d) => (x < 0 && shows(x, d) ? MINUS : '');
    switch (name) {
      case 'amount0': return minus(v, 0) + GROUP.format(Math.abs(v));
      case 'signed0': return sign(v, 0) + GROUP.format(Math.abs(v));
      case 'signed2': return sign(v, 2) + GROUP2.format(Math.abs(v));
      case 'pct': return (Math.abs(v) <= 1 ? minus(v * 100, 2) + FREE.format(Math.abs(v * 100)) : minus(v, 2) + FREE.format(Math.abs(v))) + '%';
      case 'compact': return B.compact(v);
      default: return minus(v, 2) + (Number.isInteger(v) ? GROUP.format(Math.abs(v)) : FREE.format(Math.abs(v)));
    }
  };

  /** 1.2k, 3.4m, 5bn: for axes and tight places. */
  B.compact = function (v) {
    if (typeof v !== 'number' || !isFinite(v)) { return ''; }
    const a = Math.abs(v), s = v < 0 && Math.round(a * 100) > 0 ? MINUS : '';
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

  /** The theme's tokens as the page (or the element given, for an embedded report) currently has them (they change with the theme). */
  B.tokens = function (el) {
    const s = getComputedStyle(el || document.documentElement), t = {};
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

  /** The workspaces the signed-in person may add items to (contributor and above, not the shipped folder), personal first. Asked once per page. */
  let writableP = null;
  B.writable = function () {
    if (!writableP) {
      writableP = B.api('GET', '/workspaces').then((list) => list.filter((w) => !w.shipped && ['contributor', 'member', 'admin'].indexOf(w.role) >= 0)
        .sort((a, b) => (b.personal ? 1 : 0) - (a.personal ? 1 : 0)));
    }
    return writableP;
  };
  /** Fills a <select> with those workspaces (value `me` for the personal one, the id otherwise); `want` picks one when it is among them. */
  B.fillWorkspaces = function (select, want) {
    return B.writable().then((list) => {
      list.forEach((w) => select.appendChild(B.h('option', { value: w.personal ? 'me' : w.id, text: w.name + (w.personal ? ' (private)' : '') })));
      if (want && list.some((w) => w.id === want)) { select.value = want; }
      return list;
    });
  };
  /**
   * Asks a yes/no question in place beside `anchor` (the page's own styling, no browser dialog): the text, a button for the action and Cancel. Resolves true for the
   * action and false for Cancel or Escape; Cancel has the focus, so Enter does not do the harm by accident.
   */
  B.confirm = function (anchor, text, yesText) {
    const old = document.querySelector('[data-ask-confirm]');
    if (old) { old.remove(); }
    return new Promise((resolve) => {
      const go = B.h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-confirm-go': '', text: yesText || 'Go ahead' });
      const no = B.h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-confirm-cancel': '', text: 'Cancel' });
      const box = B.h('span', { class: 'bi-ask', 'data-ask-confirm': '', role: 'alertdialog', 'aria-label': text }, B.h('span', { class: 'bi-ask-text', text }), go, no);
      const done = (v) => { box.remove(); resolve(v); };
      box.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); done(false); } });
      go.addEventListener('click', () => done(true));
      no.addEventListener('click', () => done(false));
      if (anchor) { anchor.after(box); } else { document.body.appendChild(box); }
      no.focus();
    });
  };
  /** Asks which workspace, in place beside `anchor`: a select, an OK button and Cancel. Resolves with the workspace to post to; rejects with {cancelled: true}. */
  B.askWorkspace = function (anchor, okText, label) {
    const old = document.querySelector('[data-ask-workspace]');
    if (old) { old.remove(); }
    return B.writable().then((list) => new Promise((resolve, reject) => {
      const sel = B.h('select', { class: 'studio-select', 'aria-label': label || 'Into this workspace', 'data-ask-target': '' },
        list.map((w) => B.h('option', { value: w.personal ? 'me' : w.id, text: w.name + (w.personal ? ' (private)' : '') })));
      const go = B.h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-ask-go': '', text: okText || 'Copy here' });
      const no = B.h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ask-cancel': '', text: 'Cancel' });
      const box = B.h('span', { class: 'bi-ask', 'data-ask-workspace': '', role: 'group', 'aria-label': label || 'Choose a workspace' }, sel, go, no);
      anchor.after(box);
      sel.focus();
      go.addEventListener('click', () => { box.remove(); resolve(sel.value); });
      no.addEventListener('click', () => { box.remove(); reject(Object.assign(new Error('cancelled'), { cancelled: true })); });
    }));
  };


  /** Announces `text` in a live region right beside `anchor` (created once, after the control) and brings it into view: an error or a success is next to what caused it. */
  B.near = function (anchor, text, bad) {
    if (!anchor) { return null; }
    let box = anchor.parentNode.querySelector('[data-near="' + (anchor.getAttribute('data-near-for') || '') + '"]');
    if (!box && !text) { return null; }
    if (!box) {
      const key = 'n' + Math.random().toString(36).slice(2, 8);
      anchor.setAttribute('data-near-for', key);
      box = B.h('span', { class: 'bi-near', 'data-near': key, role: bad ? 'alert' : 'status', 'aria-live': bad ? 'assertive' : 'polite' });
      anchor.after(box);
    }
    box.setAttribute('role', bad ? 'alert' : 'status');
    box.textContent = text || '';
    box.classList.toggle('bi-bad', !!bad);
    if (text && box.scrollIntoView) { box.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
    return box;
  };

  /** Runs `fn()` (returns a promise) with `btn` disabled and marked busy until it settles, so a second click cannot send a second request. */
  B.busy = function (btn, fn) {
    if (btn.disabled && btn.getAttribute('aria-busy') === 'true') { return Promise.resolve(); }
    const was = btn.disabled;
    btn.disabled = true;
    btn.setAttribute('aria-busy', 'true');
    const done = () => { btn.disabled = was; btn.removeAttribute('aria-busy'); };
    let p;
    try { p = Promise.resolve(fn()); } catch (e) { done(); throw e; }
    return p.then((v) => { done(); return v; }, (e) => { done(); throw e; });
  };

  /**
   * Saves an item from the saved state the editor opened (`etag`, sent as baseEtag). When someone saved since (409 DRS-9007) it offers Reload, Save as a copy and
   * Overwrite beside `anchor`; Overwrite sends the same save with no base. Resolves with the saved item; a Reload or a copy leaves the page, a refusal rejects.
   */
  B.saveItem = function (id, payload, etag, anchor, onCopy) {
    const old = document.querySelector('[data-stale-choice]');
    if (old) { old.remove(); }
    const put = (withBase) => B.api('PUT', '/items/' + B.id2path(id), Object.assign({}, payload, withBase && etag ? { baseEtag: etag } : {}));
    return put(true).catch((e) => {
      if (e.code !== 'DRS-9007' || !anchor) { throw e; }
      return new Promise((resolve, reject) => {
        const reload = B.h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-stale-reload': '', text: 'Reload theirs' });
        const copy = onCopy ? B.h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-stale-copy': '', text: 'Save mine as a copy' }) : null;
        const over = B.h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-stale-overwrite': '', text: 'Overwrite with mine' });
        const box = B.h('span', { class: 'bi-ask bi-stale', 'data-stale-choice': '', role: 'alert' },
          B.h('strong', { text: 'Saved elsewhere since you opened it. ' }), e.detail + ' ', reload, copy, over);
        anchor.after(box);
        box.scrollIntoView({ block: 'nearest' });
        reload.addEventListener('click', () => { window.location.reload(); });
        if (copy) { copy.addEventListener('click', () => { box.remove(); Promise.resolve(onCopy()).then(() => reject(Object.assign(new Error('copied'), { cancelled: true })), reject); }); }
        over.addEventListener('click', () => { box.remove(); put(false).then(resolve, reject); });
      });
    });
  };

  /** The body of a copy of a shipped file: without the licence header and the sample's own explanation (the comments before the first key), and no longer marked as a sample. */
  B.copyBody = function (body) {
    const lines = String(body || '').split('\n');
    let i = 0;
    while (i < lines.length && (/^\s*#/.test(lines[i]) || !lines[i].trim())) { i++; }
    return lines.slice(i).filter((l) => !/^sample:\s*true\b/.test(l)).join('\n').replace(/^tags:\s*\[(.*)\]/m, (m, t) => 'tags: [' + t.split(',').map((x) => x.trim()).filter((x) => x && x !== 'sample' && x !== 'tutorial').join(', ') + ']');
  };

  /** Why a dataset of GET /datasets cannot be used for a new report yet, or '' when it can: shown beside the greyed entry instead of hiding it. */
  B.datasetWhy = function (d) {
    if (!d.valid) { return 'has errors: open it' + (d.errors ? ' (' + d.errors + ')' : ''); }
    if (d.status && d.status !== 'published') { return d.personal ? 'draft in a private workspace: publish it first' : 'a draft: publish it first'; }
    return '';
  };
  /** Fills the Main dataset <select> and the Also add <select multiple> from GET /datasets; datasets that cannot be used are greyed with the reason. */
  B.fillDatasetPickers = function (main, also, want) {
    const groups = B.layer && B.layer.groups ? B.layer.groups().catch(() => []) : Promise.resolve([]);       // the models and the packs' datasets, grouped (a report reads one model)
    return Promise.all([B.api('GET', '/datasets?drafts=1'), groups]).then(([d, grouped]) => {
      grouped.forEach((g) => main.appendChild(B.h('optgroup', { label: g.label }, g.items.map((i) => B.h('option', { value: i.value, text: i.label })))));
      const legacy = B.h('optgroup', { label: grouped.length ? 'Other datasets (version 1)' : 'Datasets' });
      main.appendChild(legacy);
      const same = {};
      (d.datasets || []).forEach((x) => { same[x.title] = (same[x.title] || 0) + 1; });
      (d.datasets || []).forEach((x) => {
        const why = B.datasetWhy(x), state = same[x.title] > 1 && x.status ? ', ' + x.status : '', text = x.title + (state || why ? ' (' + [why, state.slice(2)].filter(Boolean).join(', ') + ')' : '');
        legacy.appendChild(B.h('option', { value: x.id, text, disabled: !!why }));
        if (also) { also.appendChild(B.h('option', { value: x.id, text, disabled: !!why })); }
      });
      if (also && grouped.length) { const hide = () => { const one = /^(mq|dataset):/.test(main.value); also.disabled = one; if (one) { Array.prototype.forEach.call(also.options, (o) => { o.selected = false; }); } }; main.addEventListener('change', hide); }
      if (want) { const o = Array.prototype.find.call(main.options, (x) => !x.disabled && (x.value === want || x.value === 'file:dataset:' + want)); if (o) { main.value = o.value; } }
      return d.datasets || [];
    });
  };

  /** The line a new report draft starts with, for what the picker chose: a version 1 dataset (`dataset:`), a model or a pack dataset (`model:`; a report reads one model). */
  B.sourceLine = function (value) {
    if (value.indexOf('mq:') === 0) { return 'model: ' + value.slice(3); }
    if (value.indexOf('dataset:') === 0) { return 'model: ' + value; }
    return 'dataset: ' + value;
  };

  /** "Also add" never offers the main dataset: the option of the chosen main one is hidden and unselected, and follows the choice. */
  B.excludeMain = function (main, also) {
    const apply = () => Array.prototype.forEach.call(also.options, (o) => { const same = !!main.value && o.value === main.value; o.hidden = same; if (same) { o.selected = false; } });
    main.addEventListener('change', apply);
    return apply;
  };

  B.ROW_PX = 56;                 // the height of one grid row of a report, in pixels (also in bi-report.css)
  B.GAP_PX = 10;
})();
