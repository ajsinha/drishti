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
/* The dataset wizard, part 2 (bi-wizard.js is part 1): combined and derived sources, relationships, measures with an expression builder, hierarchies, the refresh
   schedule with the row rules shown read-only, and the review that writes the YAML, checks it and saves a draft. Same-origin JSON only. */
(function () {
  'use strict';
  const W = window.drishtiWizard;
  if (!W) { return; }
  const { B, h, q } = W;
  const say = W.say;
  const field = (label, input) => h('label', {}, label, input);
  const sel = (options, value, label, attrs) => {
    const s = h('select', Object.assign({ class: 'studio-select', 'aria-label': label }, attrs || {}), options.map((o) => (typeof o === 'string' ? h('option', { value: o, text: o }) : h('option', { value: o[0], text: o[1] }))));
    s.value = value == null ? '' : value;
    return s;
  };
  const btn = (text, fn, cls) => { const b = h('button', { type: 'button', class: 'btn-pill ' + (cls || 'btn-ghost'), text }); b.addEventListener('click', fn); return b; };
  const strip = (r) => String(r || '').replace(/^file:dataset:/, '');

  // ---- combined (composed) and Python-Transform (derived) sources ---------------------------------------------------------------------
  let datasets = null;
  const listDatasets = () => (datasets ? Promise.resolve(datasets) : B.api('GET', '/datasets').then((r) => (datasets = (r.datasets || []).filter((d) => d.valid))));
  const modelFields = (m) => [].concat.apply([], (m.tables || []).map((t) => t.columns.map((c) => ({ name: t.name + '.' + c.name, type: c.type }))));

  W.renderBuilt = function (box, src) {
    listDatasets().then((list) => {
      const opts = [['', 'Choose a dataset']].concat(list.map((d) => [d.id, d.title]));
      if (src === 'composed') { composedUi(box, opts); } else { derivedUi(box, opts); }
    }).catch((e) => say(e.message, true));
  };

  W.cmp = { a: {}, b: {}, join: 'left' };
  function side(box, key, opts, title) {
    const s = W.cmp[key], holder = h('div', { 'data-cmp': key });
    const pick = sel(opts, s.id || '', title, { 'data-cmp-dataset': key });
    pick.addEventListener('change', () => {
      s.id = pick.value; s.model = null; s.field = ''; s.measures = [];
      if (!s.id) { drawSide(holder, s, key); return; }
      B.api('GET', '/datasets/' + B.id2path(s.id)).then((m) => { s.model = m; s.measures = (m.measures || []).slice(0, 1).map((x) => x.name); autoField(); drawSide(holder, s, key); drawOther(); }).catch((e) => say(e.message, true));
    });
    box.appendChild(h('fieldset', { class: 'wiz-table' }, h('legend', { text: title }), field('Dataset', pick), holder));
    s.holder = holder;
    drawSide(holder, s, key);
  }
  function autoField() {
    const a = W.cmp.a, b = W.cmp.b;
    if (!a.model || !b.model || (a.field && b.field)) { return; }
    const fb = modelFields(b.model);
    modelFields(a.model).some((fa) => fb.some((x) => { if (x.name.split('.')[1] === fa.name.split('.')[1] && x.type === fa.type) { a.field = fa.name; b.field = x.name; return true; } return false; }));
  }
  function drawOther() { ['a', 'b'].forEach((k) => W.cmp[k].holder && drawSide(W.cmp[k].holder, W.cmp[k], k)); }
  function drawSide(holder, s, key) {
    B.clear(holder);
    if (!s.model) { return; }
    const f = sel([['', 'Choose the field to join on']].concat(modelFields(s.model).map((x) => [x.name, x.name + ' (' + x.type + ')'])), s.field, 'Join field of ' + key, { 'data-cmp-field': key });
    f.addEventListener('change', () => { s.field = f.value; });
    holder.appendChild(field('Join on', f));
    const set = h('fieldset', { class: 'bi-kinds' }, h('legend', { text: 'Measures to bring along' }));
    (s.model.measures || []).forEach((m) => {
      const cb = h('input', { type: 'checkbox', 'data-cmp-measure': m.name }); cb.checked = s.measures.indexOf(m.name) >= 0;
      cb.addEventListener('change', () => { s.measures = s.measures.filter((x) => x !== m.name).concat(cb.checked ? [m.name] : []); });
      set.appendChild(h('label', { class: 'bi-checkrow' }, cb, ' ' + m.name));
    });
    holder.appendChild(set);
  }
  function composedUi(box, opts) {
    box.appendChild(h('p', { class: 'text-muted-d', text: 'Each dataset is asked as the reader (their row rules and masks apply), then the answers are joined on the fields you pick. Nothing is copied.' }));
    side(box, 'a', opts, 'First dataset');
    side(box, 'b', opts, 'Second dataset');
    const jt = sel(['left', 'inner'], W.cmp.join, 'Join type', { 'data-cmp-join': '' });
    jt.addEventListener('change', () => { W.cmp.join = jt.value; });
    box.appendChild(h('div', { class: 'bi-form' }, field('Keep (left: every row of the first dataset; inner: only rows in both)', jt)));
  }
  const alias = (id, n) => (W.slug(strip(id)).replace(/-/g, '_') || 'src') + '_' + n;
  W.leaveBuilt = function () {
    if (W.mode === 'derived') { return leaveDerived(); }
    const a = W.cmp.a, b = W.cmp.b;
    if (!a.id || !b.id || !a.field || !b.field) { say('Choose both datasets and the field to join each on.', true); return false; }
    const key = a.field.split('.').slice(1).join('_'), na = alias(a.id, 1), nb = alias(b.id, 2);
    const src = (s) => { const o = { dataset: strip(s.id), by: [s.field + ' as ' + key] }; if (s.measures.length) { o.measures = s.measures.slice(); } o.key = [key]; return o; };
    W.doc = { rachana: 1, dataset: W.name, version: 1, title: W.title || 'Composed: ' + a.model.title + ' and ' + b.model.title, description: W.description || '',
      from: { [na]: src(a), [nb]: src(b) }, join: [{ left: na, right: nb, on: [key], type: W.cmp.join }] };
    if (!W.doc.description) { delete W.doc.description; }
    return true;
  };

  W.der = { transform: '', inputs: [{ alias: 'input1', id: '', fields: [], measures: [] }], columns: [{ name: '', type: 'string' }] };
  function derivedUi(box, opts) {
    const t = h('input', { class: 'studio-in', 'data-der-transform': '', value: W.der.transform, maxlength: '120', autocomplete: 'off', placeholder: 'the Python item, by name or title' });
    t.addEventListener('change', () => { W.der.transform = t.value.trim(); });
    box.appendChild(h('div', { class: 'bi-form' }, field('Python Transform (interface: transform)', W.suggest(t, () => W.der.known || [])),
      h('p', { class: 'wiz-note', text: 'A Python item that takes the answers below as tables and returns one. Make one with New item, python, Transform.' })));
    B.api('GET', '/catalogue?kind=python&limit=50').then((c) => { W.der.known = ((c.items || c.results || [])).map((x) => x.title); }).catch(() => {});
    const inputs = h('div', { 'data-der-inputs': '' });
    const draw = () => {
      B.clear(inputs);
      W.der.inputs.forEach((inp, i) => {
        const al = h('input', { class: 'studio-in', value: inp.alias, 'aria-label': 'Name of input ' + (i + 1), maxlength: '40', autocomplete: 'off' });
        al.addEventListener('change', () => { inp.alias = W.ident(al.value); });
        const ds = sel(opts, inp.id, 'Dataset of input ' + (i + 1), { 'data-der-dataset': String(i) });
        const where = h('div');
        const fill = () => {
          B.clear(where);
          if (!inp.model) { return; }
          const fs = h('select', { class: 'studio-select', multiple: true, size: '6', 'aria-label': 'Fields of input ' + (i + 1) }, modelFields(inp.model).map((x) => h('option', { value: x.name, text: x.name })));
          Array.prototype.forEach.call(fs.options, (o) => { o.selected = inp.fields.indexOf(o.value) >= 0; });
          fs.addEventListener('change', () => { inp.fields = Array.prototype.filter.call(fs.options, (o) => o.selected).map((o) => o.value); });
          const ms = h('select', { class: 'studio-select', multiple: true, size: '4', 'aria-label': 'Measures of input ' + (i + 1) }, (inp.model.measures || []).map((x) => h('option', { value: x.name, text: x.name })));
          Array.prototype.forEach.call(ms.options, (o) => { o.selected = inp.measures.indexOf(o.value) >= 0; });
          ms.addEventListener('change', () => { inp.measures = Array.prototype.filter.call(ms.options, (o) => o.selected).map((o) => o.value); });
          where.appendChild(h('div', { class: 'wiz-row' }, field('Fields to group by', fs), field('Measures', ms)));
        };
        ds.addEventListener('change', () => {
          inp.id = ds.value; inp.model = null; inp.fields = []; inp.measures = [];
          if (inp.id) { B.api('GET', '/datasets/' + B.id2path(inp.id)).then((m) => { inp.model = m; fill(); }).catch((e) => say(e.message, true)); } else { fill(); }
        });
        fill();
        const rm = btn('Remove', () => { W.der.inputs.splice(i, 1); draw(); });
        inputs.appendChild(h('fieldset', { class: 'wiz-table' }, h('legend', { text: 'Input ' + (i + 1) }), h('div', { class: 'wiz-row' }, field('Name', al), field('Dataset', ds), W.der.inputs.length > 1 ? rm : null), where));
      });
    };
    draw();
    box.appendChild(inputs);
    box.appendChild(btn('Add an input', () => { W.der.inputs.push({ alias: 'input' + (W.der.inputs.length + 1), id: '', fields: [], measures: [] }); draw(); }));
    const cols = h('div', { 'data-der-columns': '' });
    const drawCols = () => {
      B.clear(cols);
      W.der.columns.forEach((c, i) => {
        const n = h('input', { class: 'studio-in', value: c.name, 'aria-label': 'Returned column ' + (i + 1), maxlength: '64', autocomplete: 'off' });
        n.addEventListener('change', () => { c.name = W.ident(n.value); n.value = c.name; });
        const ty = sel(W.types, c.type, 'Type of returned column ' + (i + 1)); ty.addEventListener('change', () => { c.type = ty.value; });
        cols.appendChild(h('div', { class: 'wiz-row' }, field('Column', n), field('Type', ty), W.der.columns.length > 1 ? btn('Remove', () => { W.der.columns.splice(i, 1); drawCols(); }) : null));
      });
    };
    drawCols();
    box.appendChild(h('fieldset', { class: 'wiz-table' }, h('legend', { text: 'Columns the Transform returns' }), cols, btn('Add a column', () => { W.der.columns.push({ name: '', type: 'string' }); drawCols(); })));
  }
  function leaveDerived() {
    const d = W.der, used = d.inputs.filter((i) => i.id);
    const cols = d.columns.filter((c) => c.name);
    if (!d.transform || !used.length || !cols.length) { say('Name the Transform, give it at least one input dataset and list the columns it returns.', true); return false; }
    const inputs = {};
    used.forEach((i) => {
      const o = { dataset: strip(i.id) };
      if (i.fields.length) { o.by = i.fields.map((f) => f + ' as ' + f.split('.').slice(1).join('_')); }
      if (i.measures.length) { o.measures = i.measures.slice(); }
      inputs[i.alias] = o;
    });
    const columns = {};
    cols.forEach((c) => { columns[c.name] = c.type; });
    W.doc = { rachana: 1, dataset: W.name, version: 1, title: W.title || W.name, description: W.description || '', derived: { transform: d.transform, inputs }, columns, measures: { Rows: { expr: 'count(result)' } } };
    if (!W.doc.description) { delete W.doc.description; }
    return true;
  }

  // ---- relationships -----------------------------------------------------------------------------------------------------------------------
  const CARD = ['many-to-one', 'one-to-many', 'one-to-one', 'many-to-many'];
  const relText = (r) => r.from + ' -> ' + r.to;
  function suggestions() {
    const have = (W.doc.relationships || []).map(relText), out = [], fs = W.fields().filter((f) => !f.part);
    (W.proposals || []).forEach((p) => {
      const r = { from: p.fromTable + '.' + p.fromColumn, to: p.toTable + '.' + p.toColumn, cardinality: p.cardinality || 'many-to-one', why: 'values match: ' + Math.round((p.matchRate || 0) * 100) + '% of ' + p.sampled + ' sampled' };
      if (have.indexOf(relText(r)) < 0 && fs.some((f) => f.name === r.from) && fs.some((f) => f.name === r.to)) { out.push(r); }
    });
    W.tables().forEach((a) => W.tables().forEach((b) => {
      if (a >= b) { return; }
      W.colsOf(a).forEach((c) => {
        if (W.colsOf(b).indexOf(c) < 0 || W.typeOf(a, c) !== W.typeOf(b, c)) { return; }
        const r = { from: a + '.' + c, to: b + '.' + c, cardinality: 'many-to-one', why: 'same name and type' };
        if (have.indexOf(relText(r)) < 0 && have.indexOf(r.to + ' -> ' + r.from) < 0 && !out.some((x) => relText(x) === relText(r))) { out.push(r); }
      });
    }));
    return out;
  }
  W.renderers.relationships = function (body) {
    const rels = (W.doc.relationships = W.doc.relationships || []);
    const names = W.fields().filter((f) => !f.part).map((f) => f.name);
    body.appendChild(h('p', { class: 'text-muted-d', text: 'A relationship says how two tables join: "from" is the many side, "to" the one side. Reports and measures follow them, so a measure of one table can be sliced by a field of another.' }));
    const list = h('div', { 'data-rels': '' });
    const draw = () => {
      B.clear(list);
      rels.forEach((r, i) => {
        const f = sel(names, r.from, 'From (many side)', { 'data-rel-from': String(i) }), t = sel(names, r.to, 'To (one side)', { 'data-rel-to': String(i) }), c = sel(CARD, r.cardinality || 'many-to-one', 'Cardinality');
        f.addEventListener('change', () => { r.from = f.value; }); t.addEventListener('change', () => { r.to = t.value; }); c.addEventListener('change', () => { r.cardinality = c.value; });
        list.appendChild(h('div', { class: 'wiz-row', 'data-rel': String(i) }, field('From', f), field('To', t), field('Cardinality', c), btn('Remove', () => { rels.splice(i, 1); if (!rels.length) { delete W.doc.relationships; } draw(); sug(); })));
      });
      if (!rels.length) { list.appendChild(h('p', { class: 'bi-empty', text: 'No relationships yet.' })); }
    };
    const box = h('div', { 'data-rel-suggestions': '' });
    const sug = () => {
      B.clear(box);
      const s = suggestions();
      if (!s.length) { return; }
      box.appendChild(h('h3', { text: 'Suggested' }));
      s.forEach((r) => box.appendChild(h('div', { class: 'wiz-row', 'data-suggest': relText(r) }, h('code', { text: r.from + ' = ' + r.to }), h('small', { class: 'text-muted-d', text: r.why }),
        btn('Add', () => { (W.doc.relationships = W.doc.relationships || []).push({ from: r.from, to: r.to, cardinality: r.cardinality }); W.show(W.step); }))));
    };
    body.appendChild(list); body.appendChild(box);
    body.appendChild(btn('Add a relationship', () => {
      if (names.length < 2) { say('There are not two fields to relate yet.', true); return; }
      (W.doc.relationships = W.doc.relationships || []).push({ from: names[0], to: names[1], cardinality: 'many-to-one' }); W.show(W.step);
    }));
    draw(); sug();
  };

  W.leave.relationships = function () { if (W.doc.relationships && !W.doc.relationships.length) { delete W.doc.relationships; } return true; };

  // ---- measures --------------------------------------------------------------------------------------------------------------------------------
  const AGG = ['sum', 'avg', 'min', 'max', 'count', 'distinct'];
  const FORMATS = ['', 'amount0', 'amount2', 'signed0', 'signed2', 'percent1', 'integer'];
  const AGG_RE = /^(sum|avg|min|max|count|distinct)\(\s*([A-Za-z_][\w.]*)\s*\)$/;
  function measureRows() {
    if (W.mrows) { return W.mrows; }
    const ms = W.doc.measures || {}, rows = [];
    Object.keys(ms).forEach((name) => {
      const v = typeof ms[name] === 'string' ? { expr: ms[name] } : Object.assign({}, ms[name]);
      const row = { name, expr: v.expr || '', fmt: v.format || '', desc: v.description || '', extra: {}, locked: !v.expr };
      Object.keys(v).forEach((k) => { if (['expr', 'format', 'description'].indexOf(k) < 0) { row.extra[k] = v[k]; } });
      const m = AGG_RE.exec(row.expr);
      row.mode = m ? 'agg' : 'formula'; row.agg = m ? m[1] : 'sum'; row.field = m ? m[2] : '';
      rows.push(row);
    });
    return (W.mrows = rows);
  }
  const exprOf = (r) => (r.mode === 'agg' ? (r.field ? r.agg + '(' + r.field + ')' : '') : r.expr);
  W.commitMeasures = function () {
    if (!W.mrows) { return; }
    const out = {};
    W.mrows.forEach((r) => {
      if (!r.name) { return; }
      if (r.locked) { out[r.name] = Object.assign({}, r.extra); return; }
      r.expr = exprOf(r);
      const o = { expr: r.expr };
      if (r.fmt) { o.format = r.fmt; }
      if (r.desc) { o.description = r.desc; }
      Object.assign(o, r.extra);
      out[r.name] = Object.keys(o).length === 1 ? o.expr : o;
    });
    W.doc.measures = out;
    if (!Object.keys(out).length) { delete W.doc.measures; }
  };
  const numeric = (t) => t === 'integer' || t === 'decimal';
  W.renderers.measures = function (body) {
    const rows = measureRows();
    body.appendChild(h('p', { class: 'text-muted-d', text: 'A measure is a number computed over rows, like a total or a count. Pick an aggregation and a column, or write a formula: start typing a table or column name and choose from the list. Check asks the server whether the formula is valid.' }));
    const list = h('div', { 'data-measures': '' });
    const fieldNames = () => W.fields().filter((f) => !f.part).map((f) => f.name).concat(W.tables().map((t) => t)).concat(rows.filter((r) => r.name).map((r) => '[' + r.name + ']'));
    const draw = () => {
      B.clear(list);
      rows.forEach((r, i) => {
        if (r.locked) {
          list.appendChild(h('fieldset', { class: 'wiz-table', 'data-measure-row': r.name }, h('legend', { text: r.name }), h('p', { class: 'wiz-note', text: 'This measure is not an expression (for example a Python measure), so the wizard keeps it as it is. Edit it as YAML on the last step.' })));
          return;
        }
        const nm = h('input', { class: 'studio-in', value: r.name, 'data-m-name': String(i), 'aria-label': 'Measure name', maxlength: '80', autocomplete: 'off' });
        nm.addEventListener('change', () => { r.name = nm.value.trim(); });
        const mode = sel([['agg', 'Aggregate a column'], ['formula', 'Formula']], r.mode, 'How the measure is built', { 'data-m-mode': String(i) });
        mode.addEventListener('change', () => { if (mode.value === 'formula' && r.mode === 'agg') { r.expr = exprOf(r); } r.mode = mode.value; draw(); });
        const how = h('div', { class: 'wiz-row' });
        if (r.mode === 'agg') {
          const ag = sel(AGG, r.agg, 'Aggregation', { 'data-m-agg': String(i) });
          const fs = W.fields().filter((f) => !f.part && (r.agg === 'count' || r.agg === 'distinct' || numeric(f.type) || (r.agg !== 'sum' && r.agg !== 'avg')));
          const opts = [['', 'Choose a column']].concat(fs.map((f) => [f.name, f.name + ' (' + f.type + ')'])).concat(r.agg === 'count' ? W.tables().map((t) => [t, t + ' (rows)']) : []);
          const fl = sel(opts, r.field, 'Column', { 'data-m-field': String(i) });
          ag.addEventListener('change', () => { r.agg = ag.value; draw(); }); fl.addEventListener('change', () => { r.field = fl.value; });
          how.appendChild(field('Aggregation', ag)); how.appendChild(field('Column', fl));
        } else {
          const ex = h('input', { class: 'studio-in mono', value: r.expr, 'data-m-expr': String(i), 'aria-label': 'Formula', autocomplete: 'off', spellcheck: 'false', placeholder: 'ratio(sum(trade.mtm), sum(trade.notional))' });
          ex.addEventListener('change', () => { r.expr = ex.value; });
          if (B.exprEditor) {                                     // the shared ExpressionEditor: fields, measure('…'), functions, the server's check as you type
            const holder = field('Formula', ex);
            how.appendChild(holder);
            B.exprEditor(ex, { scope: 'aggregate', label: 'Formula', fields: () => W.fields().filter((f) => !f.part).map((f) => ({ name: f.name, type: f.type })),
              measures: () => rows.filter((x) => x.name && x !== r).map((x) => ({ name: x.name, description: exprOf(x) })), onCommit: (v) => { r.expr = v; } });
          } else {
            how.appendChild(field('Formula', W.suggest(ex, fieldNames)));
          }
        }
        const fm = h('input', { class: 'studio-in', value: r.fmt, list: 'wizFormats', 'aria-label': 'Format', autocomplete: 'off', 'data-m-format': String(i) });
        fm.addEventListener('change', () => { r.fmt = fm.value.trim(); });
        const ds = h('input', { class: 'studio-in', value: r.desc, 'aria-label': 'Description', maxlength: '300', autocomplete: 'off', 'data-m-desc': String(i) });
        ds.addEventListener('change', () => { r.desc = ds.value.trim(); });
        const out = h('p', { class: 'wiz-note', 'data-m-check': String(i), role: 'status' });
        list.appendChild(h('fieldset', { class: 'wiz-table', 'data-measure-row': r.name }, h('legend', { text: r.name || 'New measure' }),
          h('div', { class: 'wiz-row' }, field('Name', nm), field('Built as', mode)), how, h('div', { class: 'wiz-row' }, field('Format', fm), field('Description', ds)),
          h('div', { class: 'wiz-row' }, btn('Check', () => check(r, out)), btn('Remove', () => { rows.splice(i, 1); draw(); })), out));
      });
      if (!rows.length) { list.appendChild(h('p', { class: 'bi-empty', text: 'No measures yet.' })); }
    };
    body.appendChild(list);
    body.appendChild(h('datalist', { id: 'wizFormats' }, FORMATS.filter(Boolean).map((f) => h('option', { value: f }))));
    body.appendChild(btn('Add a measure', () => { rows.push({ name: 'Measure ' + (rows.length + 1), expr: '', fmt: '', desc: '', extra: {}, locked: false, mode: 'agg', agg: 'sum', field: '' }); draw(); }));
    draw();
  };
  /** The server's Check on the whole draft; the problems of this measure (or the first problems) are shown under it. */
  function check(r, out) {
    W.commitMeasures(); W.commitColumns();
    out.textContent = 'Checking...';
    W.post('/bi/wizard/yaml', { doc: W.doc }).then((y) => B.api('POST', '/datasets/validate', { yaml: y.yaml })).then((v) => {
      const mine = (v.problems || []).filter((p) => (p.path || '').indexOf('measures.' + r.name) === 0);
      const shown = mine.length ? mine : (v.problems || []).filter((p) => p.level === 'error').slice(0, 2);
      out.textContent = shown.length ? shown.map((p) => p.code + ' ' + p.path + ': ' + p.message).join(' | ') : 'This measure checks out.';
      out.classList.toggle('bi-bad', shown.length > 0);
    }).catch((e) => { out.textContent = e.message; out.classList.add('bi-bad'); });
  }
  W.leave.measures = function () {
    W.commitMeasures();
    const rows = W.mrows || [], names = rows.map((r) => r.name);
    if (rows.some((r) => !r.locked && r.name && !exprOf(r))) { say('A measure has no column or formula yet.', true); return false; }
    if (names.some((n, i) => n && names.indexOf(n) !== i)) { say('Two measures have the same name.', true); return false; }
    return true;
  };

  // ---- hierarchies ---------------------------------------------------------------------------------------------------------------------------------
  W.renderers.hierarchies = function (body) {
    const hs = (W.doc.hierarchies = W.doc.hierarchies || {});
    const names = W.fields().filter((f) => f.name.split('.')[1] !== 'id').map((f) => f.name);
    body.appendChild(h('p', { class: 'text-muted-d', text: 'Optional. A hierarchy is an ordered list of fields, coarse to fine (for example Desk, then Book), that a visual drills down. Skip this step if you do not need one.' }));
    const list = h('div', { 'data-hierarchies': '' });
    const draw = () => {
      B.clear(list);
      Object.keys(hs).forEach((k) => {
        const levels = hs[k];
        const box = h('fieldset', { class: 'wiz-table', 'data-hierarchy': k }, h('legend', { text: k }));
        levels.forEach((l, i) => {
          const s = sel([['', 'Choose a field']].concat(names), l, 'Level ' + (i + 1) + ' of ' + k); s.addEventListener('change', () => { levels[i] = s.value; });
          box.appendChild(h('div', { class: 'wiz-row' }, field('Level ' + (i + 1), s), btn('Remove', () => { levels.splice(i, 1); draw(); })));
        });
        box.appendChild(h('div', { class: 'wiz-row' }, btn('Add a level', () => { levels.push(''); draw(); }), btn('Remove the hierarchy', () => { delete hs[k]; draw(); })));
        list.appendChild(box);
      });
    };
    const nm = h('input', { class: 'studio-in', 'aria-label': 'Name of a new hierarchy', maxlength: '60', placeholder: 'Desk and book', autocomplete: 'off', 'data-h-name': '' });
    body.appendChild(list);
    body.appendChild(h('div', { class: 'wiz-row' }, field('New hierarchy', nm), btn('Add the hierarchy', () => { const n = nm.value.trim(); if (n && !hs[n]) { hs[n] = ['', '']; nm.value = ''; draw(); } })));
    draw();
  };
  W.leave.hierarchies = function () {
    const hs = W.doc.hierarchies || {};
    Object.keys(hs).forEach((k) => { hs[k] = hs[k].filter(Boolean); if (hs[k].length < 2) { delete hs[k]; } });
    if (!Object.keys(hs).length) { delete W.doc.hierarchies; }
    return true;
  };

  // ---- refresh and security ----------------------------------------------------------------------------------------------------------------------------
  const KINDS = [['none', 'No schedule: refresh on a data load or by hand'], ['daily', 'Every day at'], ['weekdays', 'Weekdays at'], ['business-days', 'Business days at (the pack\'s calendar)'], ['weekly', 'Every week on'],
    ['hourly', 'Every hour'], ['after-load', 'After a data load finishes'], ['custom', 'A schedule I write (cron, or words such as daily 07:30)']];
  function parseSchedule(s) {
    s = String(s || '').trim();
    let m;
    if (!s) { return { kind: 'none', time: '06:00', day: 'mon', text: '' }; }
    if ((m = /^(daily|weekdays|business-days)(?: at)? (\d{1,2}:\d{2})$/.exec(s))) { return { kind: m[1], time: m[2].padStart(5, '0'), day: 'mon', text: s }; }
    if ((m = /^weekly (mon|tue|wed|thu|fri|sat|sun) (\d{1,2}:\d{2})$/.exec(s))) { return { kind: 'weekly', day: m[1], time: m[2].padStart(5, '0'), text: s }; }
    if (s === 'hourly' || s === 'after-load') { return { kind: s, time: '06:00', day: 'mon', text: s }; }
    return { kind: 'custom', time: '06:00', day: 'mon', text: s };
  }
  const scheduleOf = (r) => (r.kind === 'none' ? '' : r.kind === 'custom' ? r.text.trim() : r.kind === 'weekly' ? 'weekly ' + r.day + ' ' + r.time : r.kind === 'hourly' || r.kind === 'after-load' ? r.kind : r.kind + ' ' + r.time);
  W.renderers.refresh = function (body) {
    const cur = W.doc.refresh, sched = typeof cur === 'object' && cur ? cur.schedule : cur;
    const st = W.sched || (W.sched = parseSchedule(sched));
    body.appendChild(h('p', { class: 'text-muted-d', text: 'When the dataset rebuilds its stored layers by itself. Without a schedule it rebuilds when a data load for one of its kinds arrives, or when an author presses Refresh now. Times are in the business-date zone.' }));
    const kind = sel(KINDS, st.kind, 'When to refresh', { 'data-refresh-kind': '' }), time = h('input', { type: 'time', class: 'studio-in', value: st.time, 'aria-label': 'Time', 'data-refresh-time': '' });
    const day = sel(['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], st.day, 'Day', { 'data-refresh-day': '' });
    const txt = h('input', { class: 'studio-in mono', value: st.text, 'aria-label': 'Schedule text', autocomplete: 'off', 'data-refresh-text': '', placeholder: '0 */15 * * *' });
    const preview = h('p', { class: 'wiz-note', 'data-refresh-preview': '' });
    const sync = () => {
      st.kind = kind.value; st.time = time.value || '06:00'; st.day = day.value; st.text = txt.value;
      time.hidden = ['daily', 'weekdays', 'business-days', 'weekly'].indexOf(st.kind) < 0; day.hidden = st.kind !== 'weekly'; txt.hidden = st.kind !== 'custom';
      const text = scheduleOf(st);
      preview.textContent = text ? 'Written as: refresh: ' + text : 'Nothing is written: no schedule.';
    };
    [kind, time, day, txt].forEach((e) => { e.addEventListener('change', sync); e.addEventListener('input', sync); });
    body.appendChild(h('div', { class: 'bi-form' }, h('div', { class: 'wiz-row' }, field('Refresh', kind), field('Time', time), field('Day', day), field('Schedule', txt)), preview));
    sync();
    const sec = W.doc.security || {}, rules = sec.rows || [], masked = sec.masked || [];
    const secBox = h('div', { class: 'bi-form', 'data-security': '' }, h('h3', { text: 'Security' }));
    if (rules.length) {
      secBox.appendChild(h('p', { class: 'wiz-note', text: 'Row rules are enforced on the server for every reader; a reader with no rule for their roles sees no rows. They are shown here, not editable in the wizard.' }));
      secBox.appendChild(h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl', 'data-plain': true, 'data-rules': '' }, h('caption', { class: 'visually-hidden', text: 'Row rules' }),
        h('thead', {}, h('tr', {}, [h('th', { scope: 'col', text: 'Role' }), h('th', { scope: 'col', text: 'Rows the role may read' })])), h('tbody', {}, rules.map((r) => h('tr', {}, h('th', { scope: 'row', text: r.role }), h('td', { class: 'mono', text: r.where })))))));
    } else {
      secBox.appendChild(h('p', { class: 'wiz-note', text: 'No row rules: every reader who may open the kinds sees every row. A dataset is secured by the roles that can open its kinds; to limit rows by role, write rules in the security section (Edit as YAML on the last step).' }));
    }
    if (masked.length) { secBox.appendChild(h('p', { class: 'wiz-note', text: 'Masked columns (shown as ••• to readers without the raw power): ' + masked.join(', ') })); }
    body.appendChild(secBox);
  };
  W.leave.refresh = function () {
    const text = scheduleOf(W.sched || parseSchedule(''));
    if (typeof W.doc.refresh === 'object' && W.doc.refresh) { if (text) { W.doc.refresh.schedule = text; } else { delete W.doc.refresh.schedule; } } else if (text) { W.doc.refresh = text; } else { delete W.doc.refresh; }
    return true;
  };

  // ---- review and save ---------------------------------------------------------------------------------------------------------------------------------------
  let yamlOf = '';
  const currentYaml = (area) => (area && !area.hidden ? area.value : yamlOf);
  W.renderers.review = function (body) {
    W.commitColumns(); W.commitMeasures();
    const pre = h('pre', { class: 'bi-body mono wiz-yaml', 'data-wiz-yaml': '', tabindex: '0' }), area = h('textarea', { class: 'studio-in mono', rows: '18', spellcheck: 'false', 'data-wiz-yaml-edit': '', hidden: true, 'aria-label': 'The dataset YAML' });
    const problems = h('ul', { class: 'bi-problems', 'data-wiz-problems': '' }), saved = h('div', { 'data-wiz-saved': '' });
    const wsSel = h('select', { class: 'studio-select', 'data-wiz-workspace': '', 'aria-label': 'Save into this workspace' });
    const privateNote = h('p', { class: 'wiz-note', 'data-wiz-private-note': '', hidden: true, text: 'This is your private workspace: only you can see the draft, and it cannot be published from here. Choose a shared workspace to publish it, or move it later from its item page.' });
    body.appendChild(h('h2', { text: 'Review' }));
    body.appendChild(h('p', { class: 'text-muted-d', text: 'This is the dataset the wizard wrote. It is saved as a draft: nothing is published until you propose or publish it from its item page. Comments in an original file are not kept when a dataset is round-tripped through the wizard.' }));
    body.appendChild(pre); body.appendChild(area); body.appendChild(problems);
    /** The wizard step that holds what a problem path names. */
    const stepOf = (path) => { const k = String(path || '').split(/[.[]/)[0]; return { tables: 'columns', columns: 'columns', relationships: 'relationships', measures: 'measures', hierarchies: 'hierarchies', refresh: 'refresh', security: 'refresh', rowSecurity: 'refresh' }[k] || null; };
    W.lastProblems = [];
    const run = () => {
      B.clear(problems);
      return B.api('POST', '/datasets/validate', { yaml: currentYaml(area) }).then((v) => {
        W.lastProblems = (v.problems || []).filter((p) => p.level === 'error');
        (v.problems || []).forEach((p) => {
          const li = h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path || '' }), ': ' + p.message);
          const st = stepOf(p.path), at = W.steps.indexOf(st);
          if (p.level === 'error' && at >= 0) {
            const jump = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-jump-error': st, text: 'Fix it in step ' + (at + 1) + ', ' + st });
            jump.addEventListener('click', () => W.show(at));
            li.appendChild(document.createTextNode(' '));
            li.appendChild(jump);
          }
          problems.appendChild(li);
        });
        problems.appendChild(h('li', { class: v.valid ? 'wiz-ok' : 'bi-bad', 'data-wiz-valid': v.valid ? 'yes' : 'no', text: v.valid ? 'Valid.' : 'The dataset has errors: fix them in the earlier steps (the links above), or edit the YAML.' }));
        return v;
      }).catch((e) => { say(e.message, true); return null; });
    };
    const edit = btn('Edit as YAML', () => {
      area.hidden = !area.hidden; pre.hidden = !area.hidden; edit.textContent = area.hidden ? 'Edit as YAML' : 'Use the wizard\'s text';
      if (!area.hidden) { area.value = yamlOf; area.focus(); } else { W.yamlText = null; }
    });
    area.addEventListener('input', () => { W.yamlText = area.value; });
    area.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); go.click(); } });      // Ctrl+S saves the draft, not the page
    area.addEventListener('change', run);
    const title = W.doc.title || W.doc.dataset || 'Dataset';
    B.api('GET', '/workspaces').then((list) => {
      const ok = list.filter((w) => !w.shipped && ['contributor', 'member', 'admin'].indexOf(w.role) >= 0);
      ok.forEach((w) => wsSel.appendChild(h('option', { value: w.personal ? 'me' : w.id, text: w.name + (w.personal ? ' (private)' : '') })));
      const want = W.root.getAttribute('data-workspace-id') || '';
      const mine = W.editItem && ok.find((w) => w.id === W.editItem.workspace);
      let last = '';
      try { last = window.localStorage.getItem('drishti.bi.lastWorkspace') || ''; } catch (e) { last = ''; }
      const canPublish = ok.filter((w) => !w.personal && ['member', 'admin'].indexOf(w.role) >= 0);
      const pick = last && canPublish.find((w) => w.id === last) || canPublish[canPublish.length - 1];
      wsSel.value = mine ? (mine.personal ? 'me' : mine.id) : want && ok.some((w) => w.id === want || (want === 'me' && w.personal)) ? want : pick ? pick.id : 'me';
      const note = () => { privateNote.hidden = wsSel.value !== 'me'; };
      wsSel.addEventListener('change', () => { note(); try { window.localStorage.setItem('drishti.bi.lastWorkspace', wsSel.value); } catch (e) { /* not kept */ } });
      note();
    }).catch(() => {});
    W.post('/bi/wizard/yaml', { doc: W.doc }).then((y) => { yamlOf = y.yaml; pre.textContent = y.yaml; run(); }).catch((e) => say(e.message, true));
    const go = btn(W.editItem && !W.editItem.shipped ? 'Save the changes as the draft' : 'Save as a draft', () => save(go, area, wsSel, saved, title), 'btn-accent');
    go.setAttribute('data-wiz-save', '');
    body.appendChild(h('div', { class: 'bi-form' }, W.editItem && !W.editItem.shipped ? h('p', { class: 'wiz-note', text: 'Saving writes a new saved state of ' + W.editItem.title + '; the published version is not touched.' }) : [field('Save into', wsSel), privateNote],
      h('div', { class: 'wiz-row' }, edit, btn('Check again', run), go)));
    body.appendChild(saved);
  };
  function save(go, area, wsSel, saved, title, confirmed) {
    if (B.commitFocus) { B.commitFocus(); }
    const text = currentYaml(area);
    if (!confirmed && W.lastProblems && W.lastProblems.length) {
      const steps = Array.from(new Set(W.lastProblems.map((p) => W.steps[W.steps.findIndex((s) => s === ({ tables: 'columns', relationships: 'relationships', measures: 'measures', hierarchies: 'hierarchies', refresh: 'refresh' })[String(p.path || '').split(/[.[]/)[0]])]).filter(Boolean)));
      const sure = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-wiz-save-anyway': '', text: 'Save the draft with errors anyway' });
      sure.addEventListener('click', () => save(go, area, wsSel, saved, title, true));
      B.clear(saved).appendChild(h('div', { class: 'bi-warn', role: 'alert', 'data-wiz-save-warn': '' }, h('p', { text: W.lastProblems.length + ' error' + (W.lastProblems.length === 1 ? '' : 's') + ' in this dataset' + (steps.length ? ' (steps: ' + steps.join(', ') + ')' : '') + '. It can be saved as a draft, but it cannot be proposed or published until they are fixed. Use the links in the list above to fix each one.' }), sure));
      saved.scrollIntoView({ block: 'nearest' });
      return;
    }
    go.disabled = true;
    const done = (it) => {
      go.disabled = false; W.savedId = it.id; W.editEtag = it.etag; W.clean = true; W.discardDraft();
      try { window.localStorage.setItem('drishti.bi.lastWorkspace', it.personal ? 'me' : it.workspace); } catch (e) { /* not kept */ }
      const reportLink = () => h('a', { class: 'btn-pill btn-accent', href: '/bi/designer?dataset=' + encodeURIComponent(it.id), 'data-wiz-new-report': '', text: 'New report on this dataset' });
      const box = h('div', { class: 'wiz-after', 'data-wiz-after': '' }, h('p', { class: 'wiz-ok', text: 'Saved as a draft: ' + (it.title || title) + '.' }),
        h('a', { class: 'btn-pill btn-accent', href: '/bi/m/' + B.id2path(it.id), 'data-wiz-open-model': '', text: 'Open the Model view' }));
      const rest = [h('a', { class: 'btn-pill btn-ghost', href: '/bi/d/' + B.id2path(it.id), text: 'Open it (the dataset page)' }), h('a', { class: 'btn-pill btn-ghost', href: '/bi/i/' + B.id2path(it.id), text: 'Open the item (propose, share)' })];
      if (it.publishedVersion) { box.appendChild(reportLink()); }
      else if (it.can && it.can.publish) {
        const pub = btn('Publish it, then start a report', () => {
          pub.disabled = true;
          B.api('POST', '/items/' + B.id2path(it.id) + '/publish', { note: 'Published from the dataset wizard' }).then(() => { pub.replaceWith(reportLink()); say('Published. A report can read it now.'); }).catch((e) => { pub.disabled = false; say(e.message, true); });
        }, 'btn-accent');
        pub.setAttribute('data-wiz-publish', '');
        box.appendChild(pub);
        box.appendChild(h('p', { class: 'wiz-note', text: 'A report reads a published dataset, so publish first. You can keep it as a draft and publish later from its item page.' }));
      } else {
        box.appendChild(h('p', { class: 'wiz-note', text: it.personal ? 'A report reads a published dataset, and a draft in your private workspace cannot be published: use Move to workspace on its item page, then publish it there.' : 'A report reads a published dataset. This one is a draft: propose it for review from its item page, and start the report once it is published.' }));
      }
      rest.forEach((a) => box.appendChild(a));
      B.clear(saved).appendChild(box);
      say('Saved as a draft.');
    };
    const fail = (e) => { go.disabled = false; if (!e.cancelled) { say(e.message, true); B.near(go, e.message, true); } };
    if (W.savedId || (W.editItem && !W.editItem.shipped)) {
      const copy = () => B.askWorkspace(go, 'Save mine as a copy', 'Save a copy into').then((to) => B.api('POST', '/workspaces/' + encodeURIComponent(to) + '/items', { kind: 'dataset', title: (W.doc.title || title) + ' (copy)', description: W.doc.description || '', body: text.replace(/^dataset:\s*(\S+)/m, (m, n) => 'dataset: ' + n + '-copy') })).then((it) => { W.clean = true; window.location.href = '/bi/i/' + B.id2path(it.id); });
      B.saveItem(W.savedId || W.editItem.id, { body: text, message: 'Edited in the dataset wizard' }, W.savedId ? W.editEtag : W.editItem.etag, go, copy).then(done).catch(fail);
      return;
    }
    let body = text;
    if (W.editItem && W.editItem.shipped) { body = text.replace(/^dataset:\s*(\S+)/m, (m, n) => 'dataset: ' + n + '-copy'); }
    B.api('POST', '/workspaces/' + encodeURIComponent(wsSel.value || 'me') + '/items', { kind: 'dataset', title: (W.doc.title || title) + (W.editItem && W.editItem.shipped ? ' (copy)' : ''), description: W.doc.description || '', body }).then(done).catch(fail);
  }

  W.start();
})();
