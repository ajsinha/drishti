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
/* The dataset wizard, version 2, part 2 (bi-dataset-layer.js holds the state, the document and the navigation): the steps. Source (a pack dataset, an SQL
   view over a model, a connector query, a Python Transform, a model's answer), Columns (keep some of the base's, or define the table's), Rows (a filter in
   the ExpressionEditor, row scope), One row is (the keys and which duplicate wins), Worked-out columns (a formula or a Python column item), Group (a coarser
   grain, each column's aggregation), Preview (the server's answer as you) and Save (title, name, workspace, masks, the YAML). */
(function () {
  'use strict';
  const L = window.drishtiLw;
  if (!L) { return; }
  const B = L.B, h = L.h, q = L.q;
  const ADDS = { yes: 'yes', last: 'not over time (last value)', never: 'no' };
  const AGGS = [['', 'leave it out'], ['sum', 'sum'], ['avg', 'average'], ['min', 'minimum'], ['max', 'maximum'], ['count', 'count'], ['distinct', 'distinct count']];
  const addsDefault = (c) => (L.NUMERIC.indexOf(c.type) >= 0 && c.role !== 'key' ? 'yes' : 'never');
  let models = [], pythons = [];

  // ---- source --------------------------------------------------------------------------------------------------------
  L.bindSources = function () {
    const W = () => L.W;
    B.qa('[data-src]', L.canvas).forEach((r) => {
      r.checked = r.value === W().src;
      r.addEventListener('change', () => { setSrc(r.value); });
    });
    const setSrc = (v) => { if (W().src !== v) { W().src = v; W().seen = ['root']; } const r = q('[data-src="' + v + '"]'); if (r) { r.checked = true; } L.keep(); L.show('root'); };
    const ext = q('[data-src-extends]'), py = q('[data-src-python]'), md = q('[data-src-model]'), sm = q('[data-src-sql-model]'), sq = q('[data-src-sql]');
    q('[data-src-connector]').value = W().connector;
    q('[data-src-query]').value = W().query;
    sq.value = W().sql;
    q('[data-src-connector]').addEventListener('input', (e) => { W().connector = e.target.value.trim(); if (W().src !== 'connector') { setSrc('connector'); } L.keep(); });
    q('[data-src-query]').addEventListener('input', (e) => { W().query = e.target.value; if (W().src !== 'connector') { setSrc('connector'); } L.keep(); });
    ext.addEventListener('change', () => { W().extends = ext.value; W().base = null; W().keepCols = null; W().keys = []; setSrc('extends'); });
    py.addEventListener('change', () => { W().python = py.value; setSrc('python'); });
    md.addEventListener('change', () => { W().model = md.value; W().picks = []; W().columns = []; setSrc('model'); modelFields(); });
    sm.addEventListener('change', () => { W().sqlModel = sm.value; setSrc('sql'); });
    sq.addEventListener('input', () => { W().sql = sq.value; W().sqlColumns = []; if (W().src !== 'sql') { setSrc('sql'); } L.keep(); });
    q('[data-sql-check]').addEventListener('click', () => checkSql(q('[data-sql-check]')));
    return Promise.all([B.layer.get('datasets').then((d) => {
      (d.packs || []).forEach((p) => ext.appendChild(h('option', { value: p.name, text: p.title + ' (' + p.name + ')', selected: p.name === W().extends })));
      models = (d.models || []).map((m) => ({ ref: m.kind === 'model' ? m.name + '@' + String(m.version || '1').split('.')[0] : m.query || m.name, title: m.title }));
      [md, sm].forEach((s) => { s.appendChild(h('option', { value: '', text: 'Choose a model' })); models.forEach((m) => s.appendChild(h('option', { value: m.ref, text: m.title + ' (' + m.ref + ')' }))); });
      (d.packs || []).forEach((p) => sm.appendChild(h('option', { value: 'dataset:' + p.name, text: p.title + ' (the dataset\'s implicit model)' })));
      if (!W().extends && d.packs && d.packs.length) { W().extends = d.packs[0].name; }
      if (W().model) { md.value = W().model; modelFields(); }
      if (W().sqlModel) { sm.value = W().sqlModel; }
    }), B.api('GET', '/catalogue?kind=python&limit=100').then((c) => {
      pythons = (c.items || c.results || []).map((x) => ({ name: x.id.replace(/^file:python:/, ''), title: x.title }));
      py.appendChild(h('option', { value: '', text: 'Choose a Python item' }));
      pythons.forEach((x) => py.appendChild(h('option', { value: x.name, text: x.title, selected: x.name === W().python })));
    }).catch(() => {})]);
  };
  function modelFields() {
    const W = L.W;
    const box = B.clear(q('[data-src-model-fields]'));
    if (!W.model) { return; }
    B.layer.get('fields?ref=' + encodeURIComponent(W.model)).then((f) => {
      const pick = (name, kind, type) => {
        const c = h('input', { type: 'checkbox', 'data-src-pick': name, checked: W.picks.some((p) => p.name === name) });
        c.addEventListener('change', () => { W.picks = W.picks.filter((p) => p.name !== name).concat(c.checked ? [{ name, kind, type }] : []); W.columns = []; L.keep(); });
        return h('label', { class: 'bi-checkrow' }, c, ' ' + name);
      };
      f.tables.forEach((t) => t.columns.forEach((c) => box.appendChild(pick(t.name + '.' + c.name, 'field', c.type))));
      f.measures.forEach((m) => box.appendChild(pick(m.name, 'measure', 'decimal')));
    }).catch((e) => box.appendChild(h('span', { class: 'bi-near bi-bad', text: e.message })));
  }
  /** The SQL view's statement checked by the server (/bi/api/sql/validate): its columns, or what is wrong with the fix. */
  function checkSql(anchor) {
    const W = L.W, out = B.clear(q('[data-sql-out]'));
    if (!W.sqlModel || !W.sql.trim()) { B.near(anchor, 'Choose the model and write the SQL.', true); return Promise.resolve(false); }
    return B.busy(anchor, () => B.api('POST', '/sql/validate', { sql: W.sql, model: W.sqlModel }).then((r) => {
      W.sqlColumns = (r.columns || []).map((c) => ({ name: c.name || c, type: c.type || (c.kind === 'measure' ? 'decimal' : 'string') }));
      L.keep();
      if (r.valid === false || (r.problems || []).some((p) => (p.level || 'error') === 'error')) {
        (r.problems || []).forEach((p) => out.appendChild(h('span', { class: 'bi-bad', 'data-sql-problem': p.code || '', text: (p.code ? p.code + ' ' : '') + p.message + (p.fix ? ' ' + p.fix : '') + ' ' })));
        return false;
      }
      out.appendChild(h('span', { 'data-sql-ok': '', text: 'The SQL checks: ' + (W.sqlColumns.length ? W.sqlColumns.map((c) => c.name + ' (' + c.type + ')').join(', ') : 'it reads the model') + '.' }));
      return true;
    }).catch((e) => { out.appendChild(h('span', { class: 'bi-bad', 'data-sql-problem': e.code || '', text: e.status === 404 ? 'This server does not run Drishti SQL yet.' : e.message })); return false; }));
  }
  L.leave.root = () => {
    const W = L.W;
    return !W.src ? 'Choose where the table comes from.' : W.src === 'connector' && (!W.connector || !W.query.trim()) ? 'Name the connector and write its query.'
      : W.src === 'python' && !W.python ? 'Choose the Python item (or Write one).' : W.src === 'model' && !W.picks.length ? 'Choose the model and tick at least one field or measure.'
        : W.src === 'sql' && (!W.sqlModel || !W.sql.trim()) ? 'Choose the model and write the SQL (Check the SQL says what it answers).' : W.src === 'extends' && !W.extends ? 'Choose the pack dataset.' : '';
  };

  // ---- columns -------------------------------------------------------------------------------------------------------
  function seed() {
    const W = L.W;
    if (W.src === 'extends') {
      if (W.base && W.base.name === W.extends) { return Promise.resolve(); }
      return B.layer.get('dataset?name=' + encodeURIComponent(W.extends)).then((d) => {
        W.base = { name: W.extends, title: d.info.title, columns: d.info.columns.map((c) => ({ name: c.name, type: c.type, role: c.role, meaning: c.meaning, references: c.references })), grain: d.info.grain };
        W.keepCols = null;
        if (!W.keys.length) { W.keys = (d.info.grain.keys || []).slice(); }
        if (!W.says) { W.says = d.info.grain.says || ''; }
        if (!W.title) { W.title = d.info.title + ' (narrowed)'; }
        L.keep();
      });
    }
    if (W.src === 'model' && !W.columns.length) {
      W.columns = W.picks.map((p) => { const n = p.name.split('.').pop().replace(/[^A-Za-z0-9_]/g, '_'); return { name: n, type: p.type || 'string', role: p.kind === 'measure' ? 'amount' : 'dimension', additive: p.kind === 'measure' ? 'yes' : 'never' }; });
      W.keys = W.picks.filter((p) => p.kind === 'field').map((p) => p.name.split('.').pop().replace(/[^A-Za-z0-9_]/g, '_'));
    }
    if (!W.columns.length && W.src !== 'extends') { W.columns = [{ name: '', type: 'string', role: 'dimension', additive: 'never' }]; }
    return Promise.resolve();
  }
  L.seed = seed;
  L.draw.columns = function () {
    return seed().then(() => {
      const W = L.W, ext = W.src === 'extends';
      q('[data-keep-box]').hidden = !ext;
      q('[data-define-box]').hidden = ext;
      q('[data-cols-note]').textContent = ext ? 'Tick the columns this dataset keeps of ' + W.base.title + '. Its keys (' + (W.base.grain.keys || []).join(', ') + ') always stay: a row must still say what it is.'
        : W.src === 'connector' ? 'Name each column the query returns, with its type and role. Check runs the server\'s checks (the query is read on its connector when the server can).'
          : 'The columns the source answers. Pick the role of each: dimensions group and filter, amounts are added up.';
      if (ext) { drawKeep(); } else { drawDefine(); }
    });
  };
  function drawKeep() {
    const W = L.W, box = B.clear(q('[data-keep]'));
    const keys = W.base.grain.keys || [];
    W.base.columns.forEach((c) => {
      const isKey = keys.indexOf(c.name) >= 0;
      const x = h('input', { type: 'checkbox', 'data-keep-col': c.name, checked: isKey || !W.keepCols || W.keepCols.indexOf(c.name) >= 0, disabled: isKey });
      x.addEventListener('change', () => {
        const now = W.keepCols || W.base.columns.map((y) => y.name);
        W.keepCols = x.checked ? now.concat([c.name]).filter((v, i, a) => a.indexOf(v) === i) : now.filter((n) => n !== c.name);
        L.keep();
        L.check('columns').catch(() => {});
      });
      box.appendChild(h('label', { class: 'bi-checkrow', title: [c.type, c.role, c.meaning].filter(Boolean).join(' · ') }, x, ' ' + c.name, h('small', { class: 'text-muted-d', text: ' ' + [c.type, c.meaning || c.role].filter(Boolean).join(' · ') + (isKey ? ' · key' : '') })));
    });
  }
  q('[data-keep-all]').addEventListener('click', () => { L.W.keepCols = null; L.keep(); drawKeep(); });
  q('[data-keep-none]').addEventListener('click', () => { L.W.keepCols = (L.W.base.grain.keys || []).slice(); L.keep(); drawKeep(); });
  function drawDefine() {
    const W = L.W;
    q('[data-time]').value = W.time;
    q('[data-refresh]').value = W.refresh;
    const tb = B.clear(q('[data-cols]'));
    W.columns.forEach((c, i) => {
      const inp = (key, attrs) => { const x = h('input', Object.assign({ class: 'studio-in', value: c[key] || '', 'aria-label': key + ' of column ' + (i + 1), ['data-col-' + key]: i }, attrs || {})); x.addEventListener('change', () => { c[key] = x.value.trim(); L.keep(); }); return x; };
      const pick = (key, opts) => { const x = L.sel(opts, c[key] || '', { 'aria-label': key + ' of column ' + (i + 1), ['data-col-' + key]: i }, key === 'additive' ? ADDS : null); x.addEventListener('change', () => { c[key] = x.value; if (key === 'type' && !c.addsTouched) { c.additive = addsDefault(c); drawDefine(); } if (key === 'additive') { c.addsTouched = true; } L.keep(); }); return x; };
      const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove column ' + (c.name || i + 1) }, B.icon('x-lg'));
      rm.addEventListener('click', () => { W.columns.splice(i, 1); L.keep(); drawDefine(); });
      const cell = (label, node) => h('td', { 'data-label': label }, node);
      tb.appendChild(h('tr', { 'data-col-row': c.name || String(i) }, cell('Column', inp('name')), cell('Type', pick('type', L.TYPES)), cell('Role', pick('role', L.ROLES)),
        cell('Meaning', inp('meaning')), cell('References', inp('references', { placeholder: 'desk' })), cell('Currency', inp('currency', { placeholder: 'currency or USD' })),
        cell('Adds up', pick('additive', ['yes', 'last', 'never'])), h('td', { 'data-label': '' }, rm)));
    });
  }
  q('[data-col-add]').addEventListener('click', () => { L.W.columns.push({ name: '', type: 'decimal', role: 'amount', additive: 'yes' }); L.keep(); drawDefine(); });
  q('[data-time]').addEventListener('change', (e) => { L.W.time = e.target.value; L.keep(); });
  q('[data-refresh]').addEventListener('change', (e) => { L.W.refresh = e.target.value; L.keep(); });
  L.leave.columns = () => (L.W.src !== 'extends' && L.W.columns.some((c) => !c.name) ? 'Name every column (or remove the empty one).' : '');

  // ---- the rows (a filter) -------------------------------------------------------------------------------------------
  const colFields = () => L.cols().map((c) => ({ name: c.name, type: c.type, meaning: c.meaning || c.role }));
  // the server resolves names against the dataset's implicit model: the base (a narrowed pack dataset) or the saved draft; the row in context is its alias
  const aliasOf = (name) => String(name || '').split('@')[0].split('.').pop().replace(/[^A-Za-z0-9_]/g, '_');
  const target = () => { const n = L.W.src === 'extends' ? L.W.extends : L.W.savedId ? L.W.name : ''; return n ? { model: 'dataset:' + n, alias: aliasOf(n) } : {}; };
  let whereEd = null;
  L.draw.filter = function () {
    return seed().then(() => {
      const inp = q('[data-where]');
      inp.value = L.W.where;
      if (!whereEd) {
        whereEd = B.exprEditor(inp, { checkAtStart: true, scope: 'row', label: 'Keep the rows where', fields: colFields, measures: [], target, onCommit: (v) => { L.W.where = v; L.keep(); L.check('filter').catch(() => {}); } });
        inp.addEventListener('input', () => { L.W.where = inp.value; L.keep(); });
      } else { whereEd.refresh(); }
    });
  };

  // ---- one row is (the grain and duplicates) -------------------------------------------------------------------------
  L.draw.grain = function () {
    return seed().then(() => {
      const W = L.W, cols = L.cols();
      q('[data-grain-says]').value = W.says;
      const box = B.clear(q('[data-keys]'));
      W.keys = W.keys.filter((k) => cols.some((c) => c.name === k) || k === 'id');
      cols.forEach((c) => {
        const on = W.keys.indexOf(c.name) >= 0;
        const b = h('button', { type: 'button', class: 'bi-chip', 'aria-pressed': on ? 'true' : 'false', 'data-key': c.name }, on ? B.icon('check2') : B.icon('plus'), ' ' + c.name);
        b.addEventListener('click', () => { W.keys = on ? W.keys.filter((k) => k !== c.name) : W.keys.concat([c.name]); L.keep(); L.draw.grain(); L.check('grain').catch(() => {}); });
        box.appendChild(b);
      });
      const by = B.clear(q('[data-dedup-by]'));
      const dated = cols.filter((c) => c.type === 'date' || c.type === 'timestamp').concat(cols.filter((c) => c.type !== 'date' && c.type !== 'timestamp'));
      dated.forEach((c) => by.appendChild(h('option', { value: c.name, text: c.name + ' (' + (c.type || 'string') + ')', selected: c.name === W.dedupBy })));
      if (!W.dedupBy && dated.length) { W.dedupBy = dated[0].name; }
      B.qa('[data-dedup]', L.canvas).forEach((r) => { r.checked = r.value === W.dedup; });
    });
  };
  q('[data-grain-says]').addEventListener('change', (e) => { L.W.says = e.target.value.trim(); L.keep(); });
  B.qa('[data-dedup]', L.canvas).forEach((r) => r.addEventListener('change', () => { L.W.dedup = r.value; L.keep(); L.check('grain').catch(() => {}); }));
  q('[data-dedup-by]').addEventListener('change', (e) => { L.W.dedupBy = e.target.value; L.keep(); L.check('grain').catch(() => {}); });
  L.leave.grain = () => (L.W.dedup && !L.W.keys.length ? 'Say what one row is (the keys) before choosing which duplicate wins.' : '');

  // ---- worked-out columns --------------------------------------------------------------------------------------------
  L.draw.derive = function () {
    return seed().then(() => {
      const W = L.W, box = B.clear(q('[data-derive]'));
      if (!W.derive.length) { box.appendChild(h('p', { class: 'text-muted-d', 'data-derive-none': '', text: 'None yet: add a formula column or a Python column below, or go on.' })); }
      W.derive.forEach((d, i) => {
        const name = h('input', { class: 'studio-in mono', value: d.name, 'data-derive-name': i, 'aria-label': 'Name of worked-out column ' + (i + 1), placeholder: 'notionalUsd' });
        name.addEventListener('change', () => { d.name = name.value.trim().replace(/[^A-Za-z0-9_]/g, '_'); name.value = d.name; L.keep(); });
        const type = L.sel(L.TYPES, d.type || 'decimal', { 'data-derive-type': i, 'aria-label': 'Type of ' + (d.name || 'column ' + (i + 1)) });
        type.addEventListener('change', () => { d.type = type.value; L.keep(); });
        const meaning = h('input', { class: 'studio-in', value: d.meaning || '', 'data-derive-meaning': i, 'aria-label': 'Meaning of ' + (d.name || 'column ' + (i + 1)), placeholder: 'amount' });
        meaning.addEventListener('change', () => { d.meaning = meaning.value.trim(); L.keep(); });
        const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-derive-remove': i, text: 'Remove' });
        rm.addEventListener('click', () => { W.derive.splice(i, 1); L.keep(); L.draw.derive(); });
        let how;
        if (d.kind === 'python') {
          const item = h('select', { class: 'studio-select', 'data-derive-python': i, 'aria-label': 'Python column item' }, h('option', { value: '', text: 'Choose a Python column item' }), pythons.map((p) => h('option', { value: p.name, text: p.title, selected: p.name === d.python })));
          item.addEventListener('change', () => { d.python = item.value; L.keep(); });
          const ins = h('span', { class: 'bi-layer-picks', role: 'group', 'aria-label': 'Its input columns' }, L.cols().map((c) => {
            const x = h('input', { type: 'checkbox', 'data-derive-input': c.name, checked: (d.inputs || []).indexOf(c.name) >= 0 });
            x.addEventListener('change', () => { d.inputs = (d.inputs || []).filter((n) => n !== c.name).concat(x.checked ? [c.name] : []); L.keep(); });
            return h('label', { class: 'bi-checkrow' }, x, ' ' + c.name);
          }));
          how = h('div', {}, h('label', { class: 'bi-frow' }, h('span', { text: 'Python item' }), item), h('fieldset', {}, h('legend', { text: 'Its inputs (the columns it reads, masked as the reader)' }), ins));
        } else {
          const ex = h('input', { class: 'studio-in mono', value: d.expr || '', 'data-derive-expr': i, placeholder: "notional * fx(currency, 'USD')" });
          how = h('div', { class: 'bi-frow' }, h('span', { text: 'Formula (row by row)' }), ex);
          window.setTimeout(() => B.exprEditor(ex, { checkAtStart: true, scope: 'row', label: 'Formula of ' + (d.name || 'the column'), fields: colFields, measures: [], target, onCommit: (v) => { d.expr = v; L.keep(); L.check('derive').catch(() => {}); } }), 0);
          ex.addEventListener('input', () => { d.expr = ex.value; L.keep(); });
        }
        box.appendChild(h('fieldset', { class: 'bi-lw-derive', 'data-derive-row': d.name || String(i) }, h('legend', { text: (d.kind === 'python' ? 'Python column ' : 'Formula column ') + (d.name || i + 1) }),
          h('div', { class: 'bi-md-row' }, h('label', {}, 'Name ', name), h('label', {}, 'Type ', type), h('label', {}, 'Meaning ', meaning), rm), how));
      });
    });
  };
  B.qa('[data-derive-add]', L.canvas).forEach((b) => b.addEventListener('click', () => {
    const kind = b.getAttribute('data-derive-add');
    L.W.derive.push({ name: kind === 'python' ? 'pyColumn' + (L.W.derive.length + 1) : 'column' + (L.W.derive.length + 1), kind, expr: '', python: '', inputs: [], type: kind === 'python' ? 'string' : 'decimal', meaning: '' });
    L.keep();
    L.draw.derive();
  }));
  L.leave.derive = () => {
    const bad = L.W.derive.find((d) => !d.name || (d.kind === 'python' ? !d.python : !(d.expr || '').trim()));
    const dup = L.W.derive.find((d, i) => L.cols().some((c) => c.name === d.name) || L.W.derive.findIndex((x) => x.name === d.name) !== i);
    return bad ? 'Give ' + (bad.name || 'each worked-out column') + ' a name and ' + (bad.kind === 'python' ? 'its Python item.' : 'a formula.') : dup ? dup.name + ' is already a column: choose another name.' : '';
  };

  // ---- group ---------------------------------------------------------------------------------------------------------
  L.draw.group = function () {
    return seed().then(() => {
      const W = L.W, g = W.group, cols = L.allCols();
      q('[data-group-on]').checked = !!g.on;
      q('[data-group-box]').hidden = !g.on;
      q('[data-group-says]').value = g.says || '';
      const by = B.clear(q('[data-group-by]'));
      cols.filter((c) => L.NUMERIC.indexOf(c.type) < 0 || c.role === 'key' || c.role === 'dimension').forEach((c) => {
        const on = g.by.indexOf(c.name) >= 0;
        const b = h('button', { type: 'button', class: 'bi-chip', 'aria-pressed': on ? 'true' : 'false', 'data-group-key': c.name }, on ? B.icon('check2') : B.icon('plus'), ' ' + c.name);
        b.addEventListener('click', () => { g.by = on ? g.by.filter((k) => k !== c.name) : g.by.concat([c.name]); L.keep(); L.draw.group(); });
        by.appendChild(b);
      });
      const tb = B.clear(q('[data-group-cols]'));
      cols.filter((c) => g.by.indexOf(c.name) < 0).forEach((c) => {
        if (!(c.name in g.cols)) { g.cols[c.name] = L.NUMERIC.indexOf(c.type) >= 0 && c.role !== 'key' ? (c.role === 'ratio' ? 'avg' : 'sum') : ''; }
        const s = L.sel(AGGS.map((a) => a[0]), g.cols[c.name], { 'data-group-agg': c.name, 'aria-label': 'Per group: ' + c.name }, Object.fromEntries(AGGS));
        s.addEventListener('change', () => { g.cols[c.name] = s.value; L.keep(); L.check('group').catch(() => {}); });
        tb.appendChild(h('tr', {}, h('td', { 'data-label': 'Column' }, h('code', { text: c.name })), h('td', { 'data-label': 'Per group' }, s)));
      });
      const cnt = h('input', { class: 'studio-in mono', value: g.count || '', 'data-group-count': '', placeholder: 'trades', 'aria-label': 'Name of the row count (empty: none)' });
      cnt.addEventListener('change', () => { g.count = cnt.value.trim().replace(/[^A-Za-z0-9_]/g, '_'); L.keep(); });
      tb.appendChild(h('tr', {}, h('td', { 'data-label': 'Column' }, 'A count of the rows, as '), h('td', { 'data-label': 'Per group' }, cnt)));
    });
  };
  q('[data-group-on]').addEventListener('change', (e) => { L.W.group.on = e.target.checked; L.keep(); L.draw.group(); L.check('group').catch(() => {}); });
  q('[data-group-says]').addEventListener('change', (e) => { L.W.group.says = e.target.value.trim(); L.keep(); });
  L.leave.group = () => (L.W.group.on && !L.W.group.by.length ? 'Choose what one grouped row is (at least one column), or untick Group.' : '');

  // ---- preview -------------------------------------------------------------------------------------------------------
  function table(host, columns, rows) {
    B.clear(host).appendChild(h('table', { class: 'tbl bi-tbl', 'data-plain': '', 'data-preview-table': '' }, h('caption', { class: 'visually-hidden', text: 'The first rows' }),
      h('thead', {}, h('tr', {}, columns.map((c) => h('th', { scope: 'col', text: c.name || c })))),
      h('tbody', {}, rows.slice(0, 20).map((r) => h('tr', {}, r.map((v) => h('td', { text: v == null ? '—' : typeof v === 'number' ? B.fmt(v) : String(v) })))))));
  }
  function preview(btn) {
    const W = L.W, meta = q('[data-preview-meta]'), host = q('[data-preview]');
    meta.textContent = 'Asking the server…';
    if (W.src === 'sql') {
      return B.busy(btn, () => B.sql(W.sql, W.sqlModel, 20).then((a) => {
        table(host, a.columns || [], a.rows || []);
        meta.textContent = (a.rowCount != null ? a.rowCount : (a.rows || []).length) + ' rows, as you' + ((a.meta || {}).millis != null ? ', in ' + a.meta.millis + ' ms' : '') + '.';
      })).catch((e) => { meta.textContent = e.status === 404 ? 'This server does not run Drishti SQL yet.' : e.message; });
    }
    if (!W.title) { W.title = (W.base && W.base.title ? W.base.title + ' (narrowed)' : 'New dataset'); }
    if (!W.name) { W.name = B.slug(W.title); }
    return B.busy(btn, () => L.saveDraft().then((it) => {
      L.problems(q('[data-problems-preview]'), it.check, 'save');
      return B.layer.get('fields?ref=' + encodeURIComponent('dataset:' + W.name));
    }).then((f) => {
      const t = (f.tables || [])[0];
      const fields = t ? t.columns.map((c) => t.name + '.' + c.name) : [];
      return B.mq.ask({ mq: 1, model: 'dataset:' + W.name, select: { fields, measures: [] }, shape: 'rows', limit: 20 }).then((a) => {
        table(host, a.columns, a.rows);
        meta.textContent = 'The first ' + a.rows.length + ' rows of the draft ' + W.name + ', as you' + (a.meta.asOf ? ', as of ' + a.meta.asOf : '') + (a.meta.masked && a.meta.masked.length ? '; masked for you: ' + a.meta.masked.join(', ') : '') + '.';
      });
    })).catch((e) => { meta.textContent = e.message; });
  }
  q('[data-preview-go]').addEventListener('click', () => preview(q('[data-preview-go]')));
  L.draw.preview = () => { B.clear(q('[data-preview]')); q('[data-preview-meta]').textContent = ''; };

  // ---- save ----------------------------------------------------------------------------------------------------------
  L.draw.save = function () {
    const W = L.W;
    q('[data-title]').value = W.title;
    q('[data-name]').value = W.name || B.slug(W.title || '') || '';
    W.name = q('[data-name]').value;
    const box = B.clear(q('[data-masks]'));
    (W.group.on ? W.group.by.concat(Object.keys(W.group.cols).filter((c) => W.group.cols[c])) : L.allCols().map((c) => c.name)).forEach((n) => {
      const x = h('input', { type: 'checkbox', checked: W.masks.indexOf(n) >= 0, 'data-mask': n });
      x.addEventListener('change', () => { W.masks = W.masks.filter((m) => m !== n).concat(x.checked ? [n] : []); L.keep(); L.check('save').catch(() => {}); });
      box.appendChild(h('label', { class: 'bi-checkrow' }, x, ' ' + n));
    });
  };
  q('[data-title]').addEventListener('change', (e) => { L.W.title = e.target.value.trim(); if (!q('[data-name]').value || !L.W.nameTouched) { q('[data-name]').value = B.slug(L.W.title); L.W.name = q('[data-name]').value; } L.keep(); L.check('save').catch(() => {}); });
  q('[data-name]').addEventListener('change', (e) => { L.W.name = e.target.value.trim(); L.W.nameTouched = !!L.W.name; L.keep(); L.check('save').catch(() => {}); });

  L.start();
})();
