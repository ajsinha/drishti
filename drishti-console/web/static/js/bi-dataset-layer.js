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
/* The new-dataset wizard of the model layer (docs/architecture/BI_MODEL_SPEC.md section 9.2): the source (a narrowed pack dataset, a connector query, a
   Python Transform, a model's answer), the table (columns with type, role, meaning, references, currency and additivity; grain keys as chips; time and
   refresh), then save. The draft is kept in this tab's session (refresh and Back keep it); the console writes the rachana: 2 YAML and the server checks it
   at every step, its problems shown beside the step. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  const B = window.drishtiBi;
  if (!canvas || !B || !B.layer || !canvas.querySelector('[data-lw-root]')) { return; }
  const h = B.h, q = (s) => B.q(s, canvas);
  const root = q('[data-lw-root]');
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  const KEEP = 'drishti.bi.layer.wizard';
  const TYPES = ['string', 'integer', 'decimal', 'boolean', 'date', 'timestamp'];
  const ROLES = ['dimension', 'key', 'attribute', 'date', 'amount', 'quantity', 'ratio', 'text'];
  let W = { step: 1, src: '', extends: '', connector: '', query: '', python: '', model: '', picks: [], columns: [], keys: [], says: '', where: '', time: 'snapshot', refresh: 'on-load', title: '', name: '', masks: [] };
  let base = null, saved = null, dirty = false;
  const keep = () => { dirty = true; try { window.sessionStorage.setItem(KEEP, JSON.stringify(W)); } catch (e) { /* not kept */ } };
  const sel = (opts, v, attrs, words) => h('select', Object.assign({ class: 'studio-select' }, attrs || {}), opts.map((o) => h('option', { value: o, text: (words && words[o]) || o || '—', selected: o === v })));
  const ADDS = { yes: 'yes', last: 'not over time (last value)', never: 'no' };
  const NUMERIC = ['integer', 'decimal'];
  const addsDefault = (c) => (NUMERIC.indexOf(c.type) >= 0 && c.role !== 'key' ? 'yes' : 'never');          // text, dates and flags are never added up

  function show(step) {
    W.step = step;
    B.qa('[data-step]', canvas).forEach((s) => { s.hidden = +s.getAttribute('data-step') !== step; });
    B.qa('[data-step-mark]', canvas).forEach((m) => { if (+m.getAttribute('data-step-mark') === step) { m.setAttribute('aria-current', 'step'); } else { m.removeAttribute('aria-current'); } });
    if (step === 2) { drawColumns(); }
    if (step === 3) { drawSave(); }
    keep();
  }

  /** The dataset document the wizard holds, as the spec's keys. */
  function doc() {
    const d = { rachana: 2, dataset: W.name || 'new-dataset', version: '1.0.0', title: W.title || W.name || 'New dataset' };
    if (W.src === 'extends') { d.extends = W.extends + '@^1'; if (W.where) { d.where = W.where; } } else if (W.src === 'connector') { d.source = { connector: W.connector, query: W.query, params: { asOf: 'as-of' } }; } else if (W.src === 'python') { d.source = { python: W.python, inputs: {} }; } else if (W.src === 'model') {
      const fields = W.picks.filter((p) => p.kind === 'field').map((p) => p.name), measures = W.picks.filter((p) => p.kind === 'measure').map((p) => p.name);
      d.source = { model: W.model, query: { select: { fields, measures } } };
    }
    if (W.src !== 'extends' || W.keys.length) { d.grain = { says: W.says || '', keys: W.keys.slice() }; }
    if (W.src !== 'extends') { d.time = { mode: W.time }; if (W.refresh) { d.refresh = W.refresh === 'on-load' ? { 'on-load': true } : W.refresh; } }
    const cols = {};
    W.columns.filter((c) => c.name && (!c.base || c.changed)).forEach((c) => {
      const x = { type: c.type || 'string' };
      if (c.role) { x.role = c.role; }
      if (c.meaning) { x.meaning = c.meaning; }
      if (c.references) { x.references = c.references; }
      if (c.currency) { x.unit = { currency: c.currency }; }
      if (c.additive === 'never') { if (NUMERIC.indexOf(c.type) >= 0 || ['amount', 'quantity', 'ratio'].indexOf(c.role) >= 0) { x.additive = false; } } else if (c.additive === 'last') { x.additive = { 'over-time': 'last' }; } else if (['amount', 'quantity'].indexOf(c.role) >= 0) { x.additive = true; x.aggregate = 'sum'; }
      if (c.expr) { x.expr = c.expr; }
      cols[c.name] = x;
    });
    if (Object.keys(cols).length) { d.columns = cols; }
    if (W.masks.length) { d.security = { masked: W.masks.slice() }; }
    return d;
  }
  function problems(list, check) {
    const box = B.clear(list);
    (check.problems || []).forEach((p) => box.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
    if (!(check.problems || []).length) { box.appendChild(h('li', { class: 'bi-ok', text: 'Checks clean.' })); }
  }
  function checkNow(list) { return B.layer.post('dataset-yaml', { doc: doc() }).then((r) => { problems(list, r.check); q('[data-yaml]').textContent = r.yaml; return r; }); }

  // ---- step 1 --------------------------------------------------------------------------------------------------------
  function bindSources() {
    B.qa('[data-src]', canvas).forEach((r) => {
      r.checked = r.value === W.src;
      r.addEventListener('change', () => { W.src = r.value; keep(); });
    });
    const ext = q('[data-src-extends]'), py = q('[data-src-python]'), md = q('[data-src-model]');
    q('[data-src-connector]').value = W.connector;
    q('[data-src-query]').value = W.query;
    q('[data-src-connector]').addEventListener('input', (e) => { W.connector = e.target.value.trim(); W.src = 'connector'; q('[data-src="connector"]').checked = true; keep(); });
    q('[data-src-query]').addEventListener('input', (e) => { W.query = e.target.value; W.src = 'connector'; q('[data-src="connector"]').checked = true; keep(); });
    ext.addEventListener('change', () => { W.extends = ext.value; W.src = 'extends'; q('[data-src="extends"]').checked = true; W.columns = []; keep(); });
    py.addEventListener('change', () => { W.python = py.value; W.src = 'python'; q('[data-src="python"]').checked = true; keep(); });
    md.addEventListener('change', () => { W.model = md.value; W.src = 'model'; W.picks = []; q('[data-src="model"]').checked = true; keep(); modelFields(); });
    return Promise.all([B.layer.get('datasets').then((d) => {
      (d.packs || []).forEach((p) => ext.appendChild(h('option', { value: p.name, text: p.title + ' (' + p.name + ')', selected: p.name === W.extends })));
      md.appendChild(h('option', { value: '', text: 'Choose a model' }));
      (d.models || []).forEach((m) => md.appendChild(h('option', { value: m.kind === 'model' ? m.name + '@' + String(m.version || '1').split('.')[0] : m.query || m.name, text: m.title, selected: false })));
      if (!W.extends && d.packs && d.packs.length) { W.extends = d.packs[0].name; }
      if (W.model) { md.value = W.model; modelFields(); }
    }), B.api('GET', '/catalogue?kind=python&limit=100').then((c) => {
      py.appendChild(h('option', { value: '', text: 'Choose a Python item' }));
      (c.items || c.results || []).forEach((x) => py.appendChild(h('option', { value: x.id.replace(/^file:python:/, ''), text: x.title, selected: x.id.replace(/^file:python:/, '') === W.python })));
    }).catch(() => {})]);
  }
  function modelFields() {
    const box = B.clear(q('[data-src-model-fields]'));
    if (!W.model) { return; }
    B.layer.get('fields?ref=' + encodeURIComponent(W.model)).then((f) => {
      const pick = (name, kind, type) => {
        const c = h('input', { type: 'checkbox', 'data-src-pick': name, checked: W.picks.some((p) => p.name === name) });
        c.addEventListener('change', () => { W.picks = W.picks.filter((p) => p.name !== name).concat(c.checked ? [{ name, kind, type }] : []); W.columns = []; keep(); });
        return h('label', { class: 'bi-checkrow' }, c, ' ' + name);
      };
      f.tables.forEach((t) => t.columns.forEach((c) => box.appendChild(pick(t.name + '.' + c.name, 'field', c.type))));
      f.measures.forEach((m) => box.appendChild(pick(m.name, 'measure', 'decimal')));
    }).catch((e) => box.appendChild(h('span', { class: 'bi-near bi-bad', text: e.message })));
  }

  // ---- step 2 --------------------------------------------------------------------------------------------------------
  function seedColumns() {
    if (W.columns.length) { return Promise.resolve(); }
    if (W.src === 'extends') {
      return B.layer.get('dataset?name=' + encodeURIComponent(W.extends)).then((d) => {
        base = d.info;
        W.columns = d.info.columns.map((c) => ({ name: c.name, type: c.type, role: c.role, meaning: c.meaning, references: c.references, currency: c.currency, additive: c.additive === false ? 'never' : c.additive && c.additive['over-time'] ? 'last' : 'yes', expr: '', base: true }));
        if (!W.title) { W.title = d.info.title + ' (narrowed)'; }
        if (!W.keys.length && d.info.grain && d.info.grain.keys) { W.keys = d.info.grain.keys.slice(); if (!W.says && d.info.grain.says) { W.says = d.info.grain.says; } }
      });
    }
    if (W.src === 'model') {
      W.columns = W.picks.map((p) => { const n = p.name.split('.').pop().replace(/[^A-Za-z0-9_]/g, '_'); return { name: n, type: p.type || 'string', role: p.kind === 'measure' ? 'amount' : 'dimension', additive: p.kind === 'measure' ? 'yes' : 'never' }; });
      W.keys = W.picks.filter((p) => p.kind === 'field').map((p) => p.name.split('.').pop().replace(/[^A-Za-z0-9_]/g, '_'));
      return Promise.resolve();
    }
    W.columns = [{ name: '', type: 'string', role: 'dimension', additive: 'never' }];
    return Promise.resolve();
  }
  function drawColumns() {
    seedColumns().then(() => {
      q('[data-cols-note]').textContent = W.src === 'extends' ? 'The base\'s columns are kept as they are (greyed); change a meaning or a format, or add a calculated column. A narrowing dataset cannot remove a key.'
        : W.src === 'connector' ? 'Name each column the query returns, with its type and role. Check runs the server\'s checks (the query is read on its connector when the server can).'
          : 'The columns the source answers. Pick the role of each: dimensions group and filter, amounts are added up.';
      q('[data-where-row]').hidden = W.src !== 'extends';
      q('[data-where]').value = W.where;
      q('[data-grain-says]').value = W.says;
      q('[data-time]').value = W.time;
      q('[data-refresh]').value = W.refresh;
      const tb = B.clear(q('[data-cols]'));
      W.columns.forEach((c, i) => {
        const ro = c.base && !c.changed && W.src === 'extends';
        const inp = (key, attrs) => { const x = h('input', Object.assign({ class: 'studio-in', value: c[key] || '', 'aria-label': key + ' of column ' + (i + 1), ['data-col-' + key]: i }, attrs || {})); x.addEventListener('change', () => { c[key] = x.value.trim(); if (c.base) { c.changed = true; } keep(); drawKeys(); }); return x; };
        const pick = (key, opts) => { const x = sel(opts, c[key] || '', { 'aria-label': key + ' of column ' + (i + 1), ['data-col-' + key]: i }, key === 'additive' ? ADDS : null); x.addEventListener('change', () => { c[key] = x.value; if (key === 'type' && !c.base && !c.addsTouched) { c.additive = addsDefault(c); drawColumns(); } if (key === 'additive') { c.addsTouched = true; } if (c.base) { c.changed = true; } keep(); }); return x; };
        const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove column ' + (c.name || i + 1), disabled: !!c.base }, B.icon('x-lg'));
        rm.addEventListener('click', () => { W.columns.splice(i, 1); keep(); drawColumns(); });
        const cell = (label, node) => h('td', { 'data-label': label }, node);
        tb.appendChild(h('tr', { class: ro ? 'bi-lw-base text-muted-d' : '', 'data-col-row': c.name || String(i), title: ro ? 'A column of the base dataset: kept as it is' : null }, cell('Column', inp('name', { disabled: !!c.base })), cell('Type', pick('type', TYPES)), cell('Role', pick('role', ROLES)),
          cell('Meaning', inp('meaning')), cell('References', inp('references', { placeholder: 'desk' })), cell('Currency', inp('currency', { placeholder: 'currency or USD' })),
          cell('Adds up', pick('additive', ['yes', 'last', 'never'])), cell('Formula', inp('expr', { class: 'studio-in mono', placeholder: c.base ? '' : 'mtm / 1000', disabled: !!c.base })), h('td', { 'data-label': '' }, rm)));
      });
      drawKeys();
    }).catch((e) => say(e.message, true));
  }
  function drawKeys() {
    const box = B.clear(q('[data-keys]'));
    W.keys = W.keys.filter((k) => W.columns.some((c) => c.name === k) || (W.src === 'extends' && k === 'id'));
    W.columns.filter((c) => c.name).forEach((c) => {
      const on = W.keys.indexOf(c.name) >= 0;
      const b = h('button', { type: 'button', class: 'bi-chip', 'aria-pressed': on ? 'true' : 'false', 'data-key': c.name }, on ? B.icon('check2') : B.icon('plus'), ' ' + c.name);
      b.addEventListener('click', () => { W.keys = on ? W.keys.filter((k) => k !== c.name) : W.keys.concat([c.name]); keep(); drawKeys(); });
      box.appendChild(b);
    });
  }
  q('[data-col-add]').addEventListener('click', () => { W.columns.push({ name: '', type: 'decimal', role: 'amount', additive: 'yes' }); keep(); drawColumns(); });
  q('[data-where]').addEventListener('change', (e) => { W.where = e.target.value.trim(); keep(); });
  q('[data-grain-says]').addEventListener('change', (e) => { W.says = e.target.value.trim(); keep(); });
  q('[data-time]').addEventListener('change', (e) => { W.time = e.target.value; keep(); });
  q('[data-refresh]').addEventListener('change', (e) => { W.refresh = e.target.value; keep(); });

  // ---- step 3 --------------------------------------------------------------------------------------------------------
  function drawSave() {
    q('[data-title]').value = W.title;
    q('[data-name]').value = W.name || B.slug(W.title || '') || '';
    W.name = q('[data-name]').value;
    const box = B.clear(q('[data-masks]'));
    W.columns.filter((c) => c.name).forEach((c) => {
      const x = h('input', { type: 'checkbox', checked: W.masks.indexOf(c.name) >= 0, 'data-mask': c.name });
      x.addEventListener('change', () => { W.masks = W.masks.filter((m) => m !== c.name).concat(x.checked ? [c.name] : []); keep(); checkNow(q('[data-problems-3]')).catch(() => {}); });
      box.appendChild(h('label', { class: 'bi-checkrow' }, x, ' ' + c.name));
    });
    checkNow(q('[data-problems-3]')).catch((e) => say(e.message, true));
  }
  q('[data-title]').addEventListener('change', (e) => { W.title = e.target.value.trim(); if (!q('[data-name]').value) { q('[data-name]').value = B.slug(W.title); W.name = q('[data-name]').value; } keep(); checkNow(q('[data-problems-3]')).catch(() => {}); });
  q('[data-name]').addEventListener('change', (e) => { W.name = e.target.value.trim(); keep(); checkNow(q('[data-problems-3]')).catch(() => {}); });

  function save(publish) {
    const btn = publish ? q('[data-save-publish]') : q('[data-save]');
    if (!W.title || !W.name) { B.near(btn, 'Give it a title and a name.', true); return; }
    B.busy(btn, () => checkNow(q('[data-problems-3]')).then((r) => {
      if (!r.check.valid) { throw new Error('Check has errors (listed above): fix them first, or Save draft to keep the work.'); }
      return r;
    }).catch((e) => { if (publish) { throw e; } return B.layer.post('dataset-yaml', { doc: doc() }); }).then((r) => {
      const put = saved ? B.api('PUT', '/items/' + B.id2path(saved.id), { body: r.yaml }) : B.api('POST', '/workspaces/' + encodeURIComponent(q('[data-ws]').value || 'me') + '/items', { kind: 'dataset', title: W.title, description: '', body: r.yaml });
      return put.then((it) => { saved = it; return publish ? B.api('POST', '/items/' + B.id2path(it.id) + '/publish', { note: 'Published from the new-dataset wizard' }) : it; });
    })).then(() => {
      dirty = false;
      try { window.sessionStorage.removeItem(KEEP); } catch (e) { /* nothing kept */ }
      B.near(btn, publish ? 'Published.' : 'Saved as a draft.', false);
      const after = B.clear(q('[data-after]'));
      after.hidden = false;
      const rep = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-after-report': '', text: 'Start a report on it' });
      rep.addEventListener('click', () => B.askWorkspace(rep, 'Start the report', 'Start the report in').then((ws) => B.layer.post('report', { dataset: W.name, title: W.title + ' report', workspace: ws })).then((x) => { window.location.href = x.href; }).catch((e) => { if (!e.cancelled) { B.near(rep, e.message, true); } }));
      const mod = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-after-model': '', text: 'Use it in a new model' });
      mod.addEventListener('click', () => B.askWorkspace(mod, 'Make the model', 'Make the model in').then((ws) => B.layer.post('new', { title: W.title + ' model', workspace: ws, datasets: [saved.id] })).then((x) => { window.location.href = x.href; }).catch((e) => { if (!e.cancelled) { B.near(mod, e.message, true); } }));
      after.append('Next: ', h('a', { class: 'btn-pill btn-ghost', href: '/bi/ds/' + B.id2path(saved.id), text: 'Open the dataset' }), ' ', rep, ' ', mod);
    }).catch((e) => B.near(btn, e.message, true));
  }
  q('[data-save]').addEventListener('click', () => save(false));
  q('[data-save-publish]').addEventListener('click', () => save(true));

  // ---- navigation and start ------------------------------------------------------------------------------------------
  B.qa('[data-next]', canvas).forEach((b) => b.addEventListener('click', () => {
    const to = +b.getAttribute('data-next');
    if (to === 2) {
      const why = !W.src ? 'Choose where the table comes from.' : W.src === 'connector' && (!W.connector || !W.query.trim()) ? 'Name the connector and write its query.' : W.src === 'python' && !W.python ? 'Choose the Python item (or Write one).' : W.src === 'model' && !W.picks.length ? 'Choose the model and tick at least one field or measure.' : '';
      if (why) { B.near(b, why, true); return; }
    }
    if (to === 3) {
      if (W.columns.some((c) => !c.name)) { B.near(b, 'Name every column (or remove the empty one).', true); return; }
      return checkNow(q('[data-problems-2]')).then(() => show(3)).catch((e) => B.near(b, e.message, true));
    }
    show(to);
  }));
  B.qa('[data-back]', canvas).forEach((b) => b.addEventListener('click', () => show(+b.getAttribute('data-back'))));
  window.addEventListener('beforeunload', (e) => { if (dirty && W.src) { e.preventDefault(); e.returnValue = ''; } });

  let kept = null;
  try { kept = JSON.parse(window.sessionStorage.getItem(KEEP) || 'null'); } catch (e) { kept = null; }
  const ext = new URLSearchParams(window.location.search).get('extends');
  if (kept && kept.src && !ext) { W = Object.assign(W, kept); }
  if (ext) { W.src = 'extends'; W.extends = ext; W.columns = []; }
  B.fillWorkspaces(q('[data-ws]'), root.getAttribute('data-workspace') || undefined).catch(() => {});
  bindSources().then(() => {
    if (ext) { q('[data-src-extends]').value = ext; }
    show(W.step || 1);
    dirty = false;
    root.setAttribute('data-ready', 'yes');
    if (kept && kept.src && !ext) { say('Your unfinished dataset is back where you left it.'); }
  }).catch((e) => say(e.message, true));
  void base;
})();
