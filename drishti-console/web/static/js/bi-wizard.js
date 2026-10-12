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
/* The dataset wizard (DATASETS.md, "Build a dataset in the browser"), part 1: the state, the step bar, the source step (packs, a connector, combined datasets, a
   Python Transform) and the tables-and-columns step. The dataset is one JSON document (`W.doc`) that the steps edit in place; a key the wizard has no control for
   stays in it and is written out again, which is how "Edit in the wizard" keeps what it does not understand. Part 2 (bi-wizard-steps.js) adds the rest. Same-origin
   JSON only; everything is built with DOM calls and textContent. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const root = document.querySelector('[data-wizard]');
  if (!root) { return; }
  const h = B.h, q = (s) => B.q(s, root);
  const W = (window.drishtiWizard = {
    B, h, q, root,
    doc: null, mode: 'tables', offer: [], proposals: [], step: 0, steps: [], editId: root.getAttribute('data-edit') || '', editItem: null, rows: {}, yamlText: null,
    types: ['string', 'integer', 'decimal', 'boolean', 'date', 'timestamp'], renderers: {}
  });
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  W.say = say;
  W.slug = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64);
  W.ident = (s) => String(s || '').replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '') || 'col';

  const LABELS = { source: 'Source', columns: 'Tables and columns', relationships: 'Relationships', measures: 'Measures', hierarchies: 'Hierarchies', refresh: 'Refresh and security', review: 'Review and save' };
  const STEPS = { tables: ['source', 'columns', 'relationships', 'measures', 'hierarchies', 'refresh', 'review'], composed: ['source', 'refresh', 'review'], derived: ['source', 'measures', 'refresh', 'review'] };

  // ---- the step bar and the buttons ----------------------------------------------------------------------------------
  function bar() {
    const ol = B.clear(q('[data-wiz-steps]'));
    W.steps.forEach((s, i) => ol.appendChild(h('li', { 'data-step': s, class: i < W.step ? 'wiz-done' : '', 'aria-current': i === W.step ? 'step' : false, text: (i + 1) + '. ' + LABELS[s] })));
    q('[data-wiz-back]').hidden = W.step === 0 || (W.editId && W.step === 1 && W.steps[0] === 'source' && W.skipSource);
    const last = W.step === W.steps.length - 1;
    q('[data-wiz-next]').hidden = last;
  }
  W.show = function (n) {
    W.step = Math.max(0, Math.min(W.steps.length - 1, n));
    say('');
    bar();
    const body = B.clear(q('[data-wiz-body]'));
    body.setAttribute('data-step', W.steps[W.step]);
    W.renderers[W.steps[W.step]](body);
    window.scrollTo(0, 0);
  };
  W.setMode = function (mode) { W.mode = mode; W.steps = STEPS[mode].slice(); };
  q('[data-wiz-back]').addEventListener('click', () => W.show(W.step - 1));
  q('[data-wiz-next]').addEventListener('click', () => {
    const leave = W.leave[W.steps[W.step]];
    Promise.resolve(leave ? leave() : true).then((ok) => { if (ok !== false) { W.show(W.step + 1); } }).catch((e) => say(e.message, true));
  });
  W.leave = {};

  // ---- fields of the document, for every step that picks one --------------------------------------------------------------
  W.tables = () => Object.keys((W.doc && W.doc.tables) || {});
  W.colsOf = (t) => Object.keys((W.doc.tables[t] || {}).columns || {});
  W.typeOf = (t, c) => { const v = ((W.doc.tables[t] || {}).columns || {})[c]; return typeof v === 'string' ? v : (v && v.type) || 'string'; };
  /** Every field a measure or a relationship may name, as table.column (plus id and the date parts), with its type. */
  W.fields = function () {
    const out = [];
    if (W.mode === 'derived') {
      Object.keys(W.doc.columns || {}).forEach((c) => out.push({ name: 'result.' + c, table: 'result', type: typeof W.doc.columns[c] === 'string' ? W.doc.columns[c] : (W.doc.columns[c] || {}).type || 'string' }));
      return out;
    }
    W.tables().forEach((t) => {
      out.push({ name: t + '.id', table: t, type: 'string' });
      W.colsOf(t).forEach((c) => {
        const type = W.typeOf(t, c);
        out.push({ name: t + '.' + c, table: t, type });
        if (type === 'date' || type === 'timestamp') { ['year', 'quarter', 'month'].forEach((p) => out.push({ name: t + '.' + c + '.' + p, table: t, type: 'integer', part: true })); }
      });
    });
    return out;
  };

  /** A suggestion list under a text box: the field names that match the word being typed; a click puts the name in. Returns the wrapper. */
  W.suggest = function (input, source) {
    const wrap = h('div', { class: 'wiz-suggest' });
    let list = null;
    const close = () => { if (list) { list.remove(); list = null; } };
    input.addEventListener('input', () => {
      close();
      const pos = input.selectionStart == null ? input.value.length : input.selectionStart;
      const m = /[\w.[\]]*$/.exec(input.value.slice(0, pos)), word = m ? m[0] : '';
      if (word.length < 1) { return; }
      const hit = source().filter((s) => s.toLowerCase().indexOf(word.toLowerCase()) >= 0 && s !== word).slice(0, 12);
      if (!hit.length) { return; }
      list = h('ul', { role: 'listbox', 'aria-label': 'Suggestions', 'data-suggestions': '' });
      hit.forEach((s) => {
        const b = h('button', { type: 'button', role: 'option', text: s });
        b.addEventListener('mousedown', (e) => e.preventDefault());
        b.addEventListener('click', () => {
          input.value = input.value.slice(0, pos - word.length) + s + input.value.slice(pos);
          close(); input.focus(); input.dispatchEvent(new Event('change', { bubbles: true }));
        });
        list.appendChild(h('li', {}, b));
      });
      wrap.appendChild(list);
    });
    input.addEventListener('keydown', (e) => { if (e.key === 'Escape') { close(); } });
    input.addEventListener('blur', () => setTimeout(close, 150));
    wrap.appendChild(input);
    return wrap;
  };

  // ---- step: source --------------------------------------------------------------------------------------------------------
  const field = (label, input) => h('label', {}, label, input);
  function connectors() {
    const seen = {};
    W.offer.forEach((t) => { if (t.connector) { (seen[t.connector] = seen[t.connector] || []).push(t); } });
    return seen;
  }
  W.renderers.source = function (body) {
    if (W.editId && W.doc) {
      body.appendChild(h('p', { text: 'This dataset is open for editing. Its tables are ' + (W.tables().join(', ') || 'none') + '. Use the next steps to change columns, relationships, measures, hierarchies and the refresh.' }));
      return;
    }
    const meta = h('div', { class: 'bi-form' },
      field('Name (letters, digits, dash)', h('input', { class: 'studio-in', 'data-wiz-name': '', maxlength: '64', value: W.name || '', autocomplete: 'off', placeholder: 'trades-by-desk' })),
      field('Title', h('input', { class: 'studio-in', 'data-wiz-title-in': '', maxlength: '200', value: W.title || '', autocomplete: 'off', placeholder: 'Trades by desk' })),
      field('Description', h('input', { class: 'studio-in', 'data-wiz-desc': '', maxlength: '2000', value: W.description || '', autocomplete: 'off' })));
    const modes = h('div', { class: 'wiz-modes', role: 'radiogroup', 'aria-label': 'Where the data comes from' });
    [['packs', 'The packs\' kinds', 'Tables from the kinds of the packs you may open.'], ['connector', 'A connector', 'Choose a connector, then its kinds (tables).'],
      ['composed', 'Combine datasets', 'Ask two datasets and join the answers (a composed dataset).'], ['derived', 'A Python Transform', 'A Python item makes the table from datasets (a derived dataset).']].forEach(([v, t, d]) => {
      const r = h('input', { type: 'radio', name: 'wizmode', value: v, 'data-wiz-mode': v });
      r.checked = (W.source || 'packs') === v;
      r.addEventListener('change', () => { W.source = v; sourceBody(); });
      modes.appendChild(h('label', { class: 'wiz-mode' }, r, h('span', {}, h('b', { text: t }), h('small', { text: d }))));
    });
    body.appendChild(modes);
    body.appendChild(meta);
    body.appendChild(h('div', { 'data-wiz-source-body': '' }));
    sourceBody();
  };
  function sourceBody() {
    const box = B.clear(q('[data-wiz-source-body]'));
    const src = W.source || 'packs';
    W.setMode(src === 'composed' ? 'composed' : src === 'derived' ? 'derived' : 'tables');
    bar();
    if (src === 'composed' || src === 'derived') { W.renderBuilt(box, src); return; }
    let shown = W.offer;
    if (src === 'connector') {
      const cs = connectors(), sel = h('select', { class: 'studio-select', 'data-wiz-connector': '', 'aria-label': 'Connector' }, h('option', { value: '', text: 'Choose a connector' }),
        Object.keys(cs).map((c) => h('option', { value: c, text: c + ' (' + cs[c].length + ' kind' + (cs[c].length === 1 ? '' : 's') + ')' })));
      sel.value = W.connector || '';
      sel.addEventListener('change', () => { W.connector = sel.value; sourceBody(); });
      box.appendChild(h('div', { class: 'bi-form' }, field('Connector', sel)));
      shown = W.connector ? cs[W.connector] || [] : [];
      if (!Object.keys(cs).length) { box.appendChild(h('p', { class: 'bi-warn', text: 'No connector serves a kind you may open.' })); }
    }
    const set = h('fieldset', { class: 'bi-kinds', 'data-kinds': '' }, h('legend', { text: src === 'connector' ? 'Tables (kinds) of this connector' : 'Kinds you may open' }));
    W.picked = W.picked || {};
    shown.forEach((t) => {
      const cb = h('input', { type: 'checkbox', value: t.kind, 'data-kind': t.kind });
      cb.checked = !!W.picked[t.kind];
      cb.addEventListener('change', () => { W.picked[t.kind] = cb.checked; });
      set.appendChild(h('label', { class: 'bi-checkrow' }, cb, ' ' + t.kind + ' ', h('small', { class: 'text-muted-d', text: t.pack + ' · ' + t.columns.length + ' columns' + (t.data ? '' : ' · no data now') })));
    });
    box.appendChild(set);
  }
  W.leave.source = function () {
    if (W.editId) { return true; }
    W.name = W.slug(q('[data-wiz-name]').value); W.title = q('[data-wiz-title-in]').value.trim(); W.description = q('[data-wiz-desc]').value.trim();
    if (!W.name) { say('Give the dataset a name: letters, digits and dashes.', true); return false; }
    if (W.mode !== 'tables') { return W.leaveBuilt(); }
    const kinds = Object.keys(W.picked || {}).filter((k) => W.picked[k] && W.offer.some((t) => t.kind === k));
    if (!kinds.length) { say('Choose at least one kind.', true); return false; }
    say('Reading the packs and the data...');
    return B.api('POST', '/model/start', { kinds, name: W.name }).then((d) => {
      W.proposals = d.proposals || []; W.warnings = d.warnings || [];
      return post('/bi/wizard/parse', { yaml: d.yaml });
    }).then((r) => {
      W.doc = r.doc; W.doc.dataset = W.name; W.doc.title = W.title || W.doc.title; if (W.description) { W.doc.description = W.description; }
      W.rows = {}; say(''); return true;
    });
  };

  // ---- step: tables and columns ---------------------------------------------------------------------------------------------------
  /** The editable rows of one table: every column of the document, then the columns the kind offers that the document does not have (unticked). */
  function rowsOf(t) {
    if (W.rows[t]) { return W.rows[t]; }
    const tab = W.doc.tables[t], cols = tab.columns || {}, rows = [];
    Object.keys(cols).forEach((k) => {
      const v = typeof cols[k] === 'string' ? { type: cols[k] } : Object.assign({}, cols[k]);
      const row = { on: true, key: k, orig: k, path: v.path || (v.expr ? '' : k), type: v.type || 'string', isKey: !!v.key, desc: v.description || '', extra: {} };
      Object.keys(v).forEach((x) => { if (['path', 'type', 'key', 'description'].indexOf(x) < 0) { row.extra[x] = v[x]; } });
      rows.push(row);
    });
    const kind = (tab.source || {}).kind, off = W.offer.find((o) => o.kind === kind && (!(tab.source || {}).connector || o.connector === tab.source.connector)) || W.offer.find((o) => o.kind === kind);
    ((off && off.columns) || []).forEach((c) => {
      if (!rows.some((r) => (r.path || r.key) === c.path)) { rows.push({ on: false, key: W.ident(c.name), orig: null, path: c.path, type: c.type, isKey: false, desc: '', extra: {} }); }
    });
    W.rows[t] = rows;
    return rows;
  }
  /** Writes the ticked rows back as the table's columns, and follows a renamed column through relationships, measures and hierarchies. */
  W.commitColumns = function () {
    W.tables().forEach((t) => {
      if (!W.rows[t]) { return; }
      const cols = {};
      W.rows[t].forEach((r) => {
        if (!r.on) { return; }
        if (r.orig && r.orig !== r.key) { rename(t, r.orig, r.key); r.orig = r.key; }
        const o = Object.assign({}, r.extra);
        const plain = r.path === r.key && !r.isKey && !r.desc && !Object.keys(o).length;
        if (plain) { cols[r.key] = r.type; return; }
        const c = {};
        if (r.path && r.path !== r.key) { c.path = r.path; }
        c.type = r.type;
        if (r.isKey) { c.key = true; }
        if (r.desc) { c.description = r.desc; }
        Object.assign(c, o);
        cols[r.key] = c;
      });
      W.doc.tables[t].columns = cols;
    });
  };
  function rename(t, from, to) {
    const re = new RegExp('\\b' + t + '\\.' + from + '\\b', 'g');
    (W.doc.relationships || []).forEach((r) => { r.from = r.from.replace(re, t + '.' + to); r.to = r.to.replace(re, t + '.' + to); });
    Object.keys(W.doc.measures || {}).forEach((m) => {
      const v = W.doc.measures[m];
      if (typeof v === 'string') { W.doc.measures[m] = v.replace(re, t + '.' + to); } else if (v && v.expr) { v.expr = v.expr.replace(re, t + '.' + to); }
    });
    Object.keys(W.doc.hierarchies || {}).forEach((k) => { W.doc.hierarchies[k] = W.doc.hierarchies[k].map((l) => l.replace(re, t + '.' + to)); });
  }
  W.renderers.columns = function (body) {
    body.appendChild(h('p', { class: 'text-muted-d', text: 'Tick the columns the dataset keeps. The name is what reports and formulas call the column; the source path is where it comes from. Add a description so others know what it means.' }));
    W.tables().forEach((t) => {
      const rows = rowsOf(t), src = W.doc.tables[t].source || {};
      const tb = h('tbody');
      rows.forEach((r, i) => {
        const on = h('input', { type: 'checkbox', 'aria-label': 'Keep ' + r.path, 'data-col-on': t + '.' + r.path });
        on.checked = r.on; on.addEventListener('change', () => { r.on = on.checked; });
        const name = h('input', { class: 'studio-in', value: r.key, 'aria-label': 'Name of ' + r.path, maxlength: '64', 'data-col-name': t + '.' + r.path, autocomplete: 'off' });
        name.addEventListener('change', () => { r.key = W.ident(name.value); name.value = r.key; });
        const type = h('select', { class: 'studio-select', 'aria-label': 'Type of ' + r.path }, W.types.map((x) => h('option', { value: x, text: x })));
        type.value = r.type; type.addEventListener('change', () => { r.type = type.value; });
        const key = h('input', { type: 'checkbox', 'aria-label': r.path + ' is a key' }); key.checked = r.isKey; key.addEventListener('change', () => { r.isKey = key.checked; });
        const desc = h('input', { class: 'studio-in', value: r.desc, 'aria-label': 'Description of ' + r.path, maxlength: '300', autocomplete: 'off', 'data-col-desc': t + '.' + r.path });
        desc.addEventListener('change', () => { r.desc = desc.value.trim(); });
        tb.appendChild(h('tr', { 'data-col': t + '.' + r.path }, h('td', {}, on), h('td', { class: 'mono', text: r.extra.expr ? 'calculated: ' + r.extra.expr : r.path }), h('td', {}, name), h('td', {}, type), h('td', {}, key), h('td', {}, desc)));
      });
      body.appendChild(h('fieldset', { class: 'wiz-table', 'data-wiz-table': t }, h('legend', { text: t + ' · ' + (src.kind || '') + (src.connector ? ' · connector ' + src.connector : '') }),
        h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl', 'data-plain': true }, h('caption', { class: 'visually-hidden', text: 'Columns of ' + t }),
          h('thead', {}, h('tr', {}, ['Keep', 'Source', 'Name', 'Type', 'Key', 'Description'].map((x) => h('th', { scope: 'col', text: x })))), tb))));
    });
    if (!W.tables().length) { body.appendChild(h('p', { class: 'bi-empty', text: 'This dataset has no tables of its own.' })); }
  };
  W.leave.columns = function () {
    W.commitColumns();
    const empty = W.tables().filter((t) => !Object.keys(W.doc.tables[t].columns || {}).length);
    if (empty.length) { say('Keep at least one column of ' + empty.join(', ') + ', or go back and drop the kind.', true); return false; }
    return true;
  };

  function post(path, body) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then((r) => r.json().catch(() => ({})).then((d) => { if (!r.ok) { throw new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText)); } return d; }));
  }
  W.post = post;

  // ---- start -----------------------------------------------------------------------------------------------------------------------
  W.start = function () {
    B.api('GET', '/me').then((me) => {
      if ((me.powers || []).indexOf('bi-author') < 0) {
        B.clear(q('[data-wiz-body]')).appendChild(h('p', { class: 'bi-warn', text: 'Building a dataset needs the bi-author power. Ask an administrator for it.' }));
        q('[data-wiz-next]').hidden = true; q('[data-wiz-back]').hidden = true;
        return false;
      }
      return B.api('GET', '/model/kinds').then((d) => { W.offer = d.tables || []; return true; });
    }).then((ok) => {
      if (!ok) { return null; }
      W.setMode('tables');
      if (!W.editId) { W.show(0); return null; }
      q('[data-wiz-title]').textContent = 'Edit the dataset in the wizard';
      return B.api('GET', '/items/' + B.id2path(W.editId)).then((it) => {
        W.editItem = it;
        return post('/bi/wizard/parse', { yaml: it.body || '' });
      }).then((r) => {
        W.doc = r.doc; W.setMode(r.mode); W.skipSource = true; W.rows = {};
        W.show(1);
      });
    }).catch((e) => say(e.message, true));
  };
})();
