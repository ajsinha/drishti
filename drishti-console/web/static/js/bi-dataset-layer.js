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
/* The dataset wizard, version 2 (docs/architecture/BI_DESIGN.md sections 3.1 and 8.1; docs/guides/DATASETS.md), part 1: the state, the dataset document it
   writes (rachana: 2 with extends or source, columns, where, grain with an explicit de-duplication, derive, group, time, refresh, security), the step bar
   and navigation, the server's Check beside each step, and saving. The steps themselves are bi-dataset-steps.js, on the same object (window.drishtiLw).
   The draft is kept in this tab's session (refresh and Back keep it). Same-origin JSON only; everything is built with DOM calls and textContent. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  const B = window.drishtiBi;
  if (!canvas || !B || !B.layer || !canvas.querySelector('[data-lw-root]')) { return; }
  const h = B.h, q = (s) => B.q(s, canvas);
  const root = q('[data-lw-root]');
  const KEEP = 'drishti.bi.layer.wizard.v2';
  const STEPS = { extends: ['root', 'columns', 'filter', 'grain', 'derive', 'group', 'preview', 'save'], connector: ['root', 'columns', 'filter', 'grain', 'derive', 'group', 'preview', 'save'],
    python: ['root', 'columns', 'filter', 'grain', 'derive', 'group', 'preview', 'save'], model: ['root', 'columns', 'filter', 'grain', 'derive', 'group', 'preview', 'save'], sql: ['root', 'preview', 'save'] };
  const LABELS = { root: 'Source', columns: 'Columns', filter: 'Rows', grain: 'One row is', derive: 'Worked-out columns', group: 'Group', preview: 'Preview', save: 'Save' };
  const PATHS = { root: ['source', 'extends'], columns: ['columns', 'time', 'refresh'], filter: ['where'], grain: ['grain'], derive: ['derive'], group: ['group'], preview: [], save: [] };
  const NUMERIC = ['integer', 'decimal'];
  const blank = () => ({ step: 'root', seen: ['root'], src: '', extends: '', connector: '', query: '', python: '', model: '', picks: [], sql: '', sqlModel: '', sqlColumns: [],
    base: null, keepCols: null, columns: [], keys: [], says: '', where: '', dedup: '', dedupBy: '', derive: [], group: { on: false, by: [], cols: {}, count: '', says: '' },
    time: 'snapshot', refresh: 'on-load', title: '', name: '', masks: [], savedId: '' });
  const L = (window.drishtiLw = { B, h, q, canvas, W: blank(), draw: {}, leave: {}, NUMERIC, TYPES: ['string', 'integer', 'decimal', 'boolean', 'date', 'timestamp'],
    ROLES: ['dimension', 'key', 'attribute', 'date', 'amount', 'quantity', 'ratio', 'text'] });
  let dirty = false;
  L.say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  L.keep = () => { dirty = true; try { window.sessionStorage.setItem(KEEP, JSON.stringify(L.W)); } catch (e) { /* not kept */ } };
  L.sel = (opts, v, attrs, words) => h('select', Object.assign({ class: 'studio-select' }, attrs || {}), opts.map((o) => h('option', { value: o, text: (words && words[o]) || o || '—', selected: o === v })));
  L.steps = () => STEPS[L.W.src] || STEPS.extends;

  /** The columns the table has after the Columns step: the kept base columns (a narrowed pack dataset) or the ones defined (any other source). */
  L.cols = () => {
    const W = L.W;
    if (W.src === 'extends') { return ((W.base && W.base.columns) || []).filter((c) => !W.keepCols || W.keepCols.indexOf(c.name) >= 0 || (W.base.grain.keys || []).indexOf(c.name) >= 0); }
    if (W.src === 'sql') { return W.sqlColumns; }
    return W.columns.filter((c) => c.name);
  };
  /** And with the worked-out columns (for the grain and the grouping). */
  L.allCols = () => L.cols().map((c) => ({ name: c.name, type: c.type, role: c.role, meaning: c.meaning })).concat(L.W.derive.filter((d) => d.name).map((d) => ({ name: d.name, type: d.type, role: NUMERIC.indexOf(d.type) >= 0 ? 'amount' : 'dimension', meaning: d.meaning, derived: true })));

  /** The dataset document, in the spec's keys (BI_DESIGN.md 3.1). */
  L.doc = function () {
    const W = L.W;
    const d = { rachana: 2, dataset: W.name || 'new-dataset', version: '1.0.0', title: W.title || W.name || 'New dataset' };
    if (W.src === 'extends') { d.extends = W.extends + '@^1'; } else if (W.src === 'connector') { d.source = { connector: W.connector, query: W.query, params: { asOf: 'as-of' } }; } else if (W.src === 'python') { d.source = { python: W.python, inputs: {} }; } else if (W.src === 'model') {
      d.source = { model: W.model, query: { select: { fields: W.picks.filter((p) => p.kind === 'field').map((p) => p.name), measures: W.picks.filter((p) => p.kind === 'measure').map((p) => p.name) } } };
    } else if (W.src === 'sql') { d.source = { sql: W.sql, model: W.sqlModel }; }
    if (W.src === 'extends') {
      const all = ((W.base && W.base.columns) || []).map((c) => c.name);
      const kept = L.cols().map((c) => c.name);
      if (W.keepCols && kept.length < all.length) { d.columns = kept; }                       // a subset: the base's keys always stay
    } else if (W.src !== 'sql') {
      const cols = {};
      W.columns.filter((c) => c.name).forEach((c) => {
        const x = { type: c.type || 'string' };
        if (c.role) { x.role = c.role; }
        if (c.meaning) { x.meaning = c.meaning; }
        if (c.references) { x.references = c.references; }
        if (c.currency) { x.unit = { currency: c.currency }; }
        if (c.additive === 'never') { if (NUMERIC.indexOf(c.type) >= 0 || ['amount', 'quantity', 'ratio'].indexOf(c.role) >= 0) { x.additive = false; } } else if (c.additive === 'last') { x.additive = { 'over-time': 'last' }; } else if (['amount', 'quantity'].indexOf(c.role) >= 0) { x.additive = true; x.aggregate = 'sum'; }
        cols[c.name] = x;
      });
      if (Object.keys(cols).length) { d.columns = cols; }
    }
    if (W.src !== 'sql' && W.where.trim()) { d.where = W.where.trim(); }
    if (W.src !== 'sql') {
      const baseKeys = (W.base && W.base.grain && W.base.grain.keys) || [];
      if (W.dedup && W.keys.length && W.dedupBy) {
        d.grain = { 'one-row-per': W.keys.length === 1 ? W.keys[0] : W.keys.slice(), keep: W.dedup + ' by ' + W.dedupBy };
        if (W.says) { d.grain.says = W.says; }
      } else if (W.src !== 'extends' || W.says || W.keys.join() !== baseKeys.join()) {
        d.grain = { says: W.says || '', keys: W.keys.slice() };
      }
    }
    const der = {};
    W.derive.filter((x) => x.name && (x.kind === 'python' ? x.python : x.expr)).forEach((x) => {
      const o = x.kind === 'python' ? { python: x.python, inputs: (x.inputs || []).slice() } : { expr: x.expr };
      o.type = x.type || 'decimal';
      if (x.meaning) { o.meaning = x.meaning; }
      der[x.name] = o;
    });
    if (Object.keys(der).length) { d.derive = der; }
    if (W.group.on && W.group.by.length) {
      const cols = {};
      Object.keys(W.group.cols).forEach((c) => { if (W.group.cols[c] && W.group.by.indexOf(c) < 0) { cols[c] = W.group.cols[c]; } });
      if (W.group.count) { cols[W.group.count] = 'count'; }
      d.group = { by: W.group.by.slice(), columns: cols, grain: { says: W.group.says || 'One row per ' + W.group.by.join(' and '), keys: W.group.by.slice() } };
    }
    if (W.src !== 'extends' && W.src !== 'sql') { d.time = { mode: W.time }; if (W.refresh) { d.refresh = W.refresh === 'on-load' ? { 'on-load': true } : W.refresh; } }
    if (W.masks.length) { d.security = { masked: W.masks.slice() }; }
    return d;
  };

  /** The server's Check of the document, its problems listed in `list` (only those of the step's keys, unless `all`). */
  L.problems = function (list, check, step) {
    const box = B.clear(list);
    const keys = PATHS[step] || [];
    const mine = (check.problems || []).filter((p) => !keys.length || keys.some((k) => p.path === k || String(p.path || '').indexOf(k + '.') === 0 || String(p.path || '').indexOf(k + '[') === 0));
    mine.forEach((p) => box.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path || '(dataset)' }), ': ' + p.message,
      p.fix ? h('span', { class: 'text-muted-d', text: ' ' + p.fix }) : null)));
    if (!mine.length) { box.appendChild(h('li', { class: 'bi-ok', text: keys.length ? 'Nothing to fix here.' : 'Checks clean.' })); }
  };
  L.check = function (step) {
    return B.layer.post('dataset-yaml', { doc: L.doc() }).then((r) => {
      const list = q('[data-problems-' + step + ']');
      if (list) { L.problems(list, r.check, step); }
      q('[data-yaml]').textContent = r.yaml;
      L.lastCheck = r;
      return r;
    });
  };

  // ---- the step bar and navigation -----------------------------------------------------------------------------------
  function bar() {
    const ol = B.clear(q('[data-lw-steps]'));
    L.steps().forEach((s, i) => {
      const seen = L.W.seen.indexOf(s) >= 0;
      const b = h('button', { type: 'button', class: 'bi-lw-stepbtn', 'data-step-mark': s, disabled: !seen, 'aria-current': s === L.W.step ? 'step' : false, text: (i + 1) + ' · ' + LABELS[s] });
      b.addEventListener('click', () => go(s));
      ol.appendChild(h('li', { 'aria-current': s === L.W.step ? 'step' : false }, b));
    });
    const i = L.steps().indexOf(L.W.step);
    q('[data-back]').hidden = i <= 0;
    q('[data-next]').hidden = i >= L.steps().length - 1;
  }
  function show(step) {
    L.W.step = step;
    if (L.W.seen.indexOf(step) < 0) { L.W.seen.push(step); }
    B.qa('[data-step]', canvas).forEach((s) => { s.hidden = s.getAttribute('data-step') !== step; });
    bar();
    return Promise.resolve(L.draw[step] ? L.draw[step]() : null).then(() => { L.keep(); if (step !== 'root' && step !== 'preview') { L.check(step).catch(() => {}); } });
  }
  L.show = show;
  function go(to) {
    const cur = L.W.step, steps = L.steps();
    if (steps.indexOf(to) > steps.indexOf(cur)) {
      const why = L.leave[cur] ? L.leave[cur]() : '';
      if (why) { B.near(q('[data-next]'), why, true); return Promise.resolve(); }
    }
    return show(to).catch((e) => L.say(e.message, true));
  }
  L.go = go;
  q('[data-next]').addEventListener('click', () => { const s = L.steps(); go(s[s.indexOf(L.W.step) + 1]); });
  q('[data-back]').addEventListener('click', () => { const s = L.steps(); go(s[Math.max(0, s.indexOf(L.W.step) - 1)]); });

  // ---- saving --------------------------------------------------------------------------------------------------------
  /** Saves the draft (creates the item the first time); resolves with the item. */
  L.saveDraft = function () {
    const W = L.W;
    if (!W.name) { W.name = B.slug(W.title || '') || 'new-dataset'; }
    return B.layer.post('dataset-yaml', { doc: L.doc() }).then((r) => {
      const put = W.savedId ? B.api('PUT', '/items/' + B.id2path(W.savedId), { body: r.yaml })
        : B.api('POST', '/workspaces/' + encodeURIComponent(q('[data-ws]').value || root.getAttribute('data-workspace') || 'me') + '/items', { kind: 'dataset', title: W.title || W.name, description: '', body: r.yaml });
      return put.then((it) => { W.savedId = it.id; L.keep(); return Object.assign(it, { check: r.check }); });
    });
  };
  function save(publish) {
    const btn = publish ? q('[data-save-publish]') : q('[data-save]');
    if (!L.W.title || !L.W.name) { B.near(btn, 'Give it a title and a name.', true); return; }
    B.busy(btn, () => L.check('save').then((r) => {
      if (publish && !r.check.valid) { throw new Error('Check has errors (listed above): fix them first, or Save draft to keep the work.'); }
      return L.saveDraft();
    }).then((it) => (publish ? B.api('POST', '/items/' + B.id2path(it.id) + '/publish', { note: 'Published from the dataset wizard' }) : it))).then(() => {
      dirty = false;
      try { window.sessionStorage.removeItem(KEEP); } catch (e) { /* nothing kept */ }
      B.near(btn, publish ? 'Published.' : 'Saved as a draft.', false);
      const after = B.clear(q('[data-after]'));
      after.hidden = false;
      const rep = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-after-report': '', text: 'Start a report on it' });
      rep.addEventListener('click', () => B.askWorkspace(rep, 'Start the report', 'Start the report in').then((ws) => B.layer.post('report', { dataset: L.W.name, title: L.W.title + ' report', workspace: ws })).then((x) => { window.location.href = x.href; }).catch((e) => { if (!e.cancelled) { B.near(rep, e.message, true); } }));
      const mod = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-after-model': '', text: 'Use it in a new model' });
      mod.addEventListener('click', () => B.askWorkspace(mod, 'Make the model', 'Make the model in').then((ws) => B.layer.post('new', { title: L.W.title + ' model', workspace: ws, datasets: [L.W.savedId] })).then((x) => { window.location.href = x.href; }).catch((e) => { if (!e.cancelled) { B.near(mod, e.message, true); } }));
      after.append('Next: ', h('a', { class: 'btn-pill btn-ghost', href: '/bi/ds/' + B.id2path(L.W.savedId), 'data-after-open': '', text: 'Open the dataset' }), ' ', rep, ' ', mod);
    }).catch((e) => B.near(btn, e.message, true));
  }
  q('[data-save]').addEventListener('click', () => save(false));
  q('[data-save-publish]').addEventListener('click', () => save(true));
  window.addEventListener('beforeunload', (e) => { if (dirty && L.W.src) { e.preventDefault(); e.returnValue = ''; } });

  // ---- start (after bi-dataset-steps.js has registered the steps) ----------------------------------------------------
  L.start = function () {
    let kept = null;
    try { kept = JSON.parse(window.sessionStorage.getItem(KEEP) || 'null'); } catch (e) { kept = null; }
    const ext = new URLSearchParams(window.location.search).get('extends');
    if (kept && kept.src && !ext) { L.W = Object.assign(blank(), kept); }
    if (ext) { L.W.src = 'extends'; L.W.extends = ext; }
    B.fillWorkspaces(q('[data-ws]'), root.getAttribute('data-workspace') || undefined).catch(() => {});
    return L.bindSources().then(() => show(L.steps().indexOf(L.W.step) >= 0 ? L.W.step : 'root')).then(() => {
      dirty = false;
      root.setAttribute('data-ready', 'yes');
      if (kept && kept.src && !ext) { L.say('Your unfinished dataset is back where you left it.'); }
    }).catch((e) => { L.say(e.message, true); root.setAttribute('data-ready', 'error'); });
  };
})();
