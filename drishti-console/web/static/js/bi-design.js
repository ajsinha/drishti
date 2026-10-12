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
/* Rupaka phase 3 (RUPAKA.md section 9): the report designer's core - the draft being edited (the report model the server parses and writes), its undo
   history, the page tabs, the YAML tab (the same draft as text; an edit in either appears in the other), Check, and Save. The canvas, the Fields pane and the
   Visualizations and Filters panes are in bi-design-canvas.js and bi-design-panes.js and talk to this through window.drishtiBi.design (events: 'model',
   'select', 'page', 'preview'). The draft is a report item of a workspace you may edit; a shipped report opens read-only and can be saved as a copy.
   The designer also holds the Designer start page (your report drafts, and a new report on a dataset). Same-origin JSON only. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'designer') { return; }
  const B = window.drishtiBi;
  const h = B.h, q = (s, sc) => B.q(s, sc || canvas);
  const D = (B.design = { modules: [] });

  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);

  /** A distinct default title for a new report: the kind and the minute, so reports made without a title can be told apart. */
  function defaultTitle() {
    const d = new Date(), z = (n) => (n < 10 ? '0' : '') + n;
    return 'Report ' + d.getFullYear() + '-' + z(d.getMonth() + 1) + '-' + z(d.getDate()) + ' ' + z(d.getHours()) + ':' + z(d.getMinutes());
  }

  // ---- the start page ------------------------------------------------------------------------------------------------
  if (q('[data-design-start]')) {
    const list = q('[data-drafts]');
    Promise.all([B.api('GET', '/workspaces/me/items'), B.api('GET', '/datasets')]).then(([items, ds]) => {
      items.filter((i) => i.kind === 'report').forEach((i) => {
        list.appendChild(h('li', { 'data-id': i.id }, h('a', { href: '/bi/design/' + B.id2path(i.id), text: i.title }), ' ', h('span', { class: 'text-muted-d', text: i.status })));
      });
      q('[data-no-drafts]').hidden = list.children.length > 0;
      const sel = q('[data-new-dataset]');
      ds.datasets.filter((d) => d.valid).forEach((d) => sel.appendChild(h('option', { value: d.id, text: d.title })));
      q('[data-no-datasets]').hidden = sel.options.length > 1;
      const want = new URLSearchParams(window.location.search).get('dataset');             // from a dataset page: New report on this dataset
      if (want) { const o = Array.prototype.find.call(sel.options, (x) => x.value === want || x.value === 'file:dataset:' + want); if (o) { sel.value = o.value; } }
    }).catch((e) => say(e.message, true));
    q('[data-new]').addEventListener('submit', (e) => {
      e.preventDefault();
      const ref = q('[data-new-dataset]').value, title = q('[data-new-title]').value.trim() || defaultTitle();
      if (!ref) { say('Choose a dataset.', true); return; }
      const name = B.slug(title) || 'my-report';
      const body = 'rachana: 1\nreport: ' + name + '\ntitle: ' + JSON.stringify(title) + '\ndataset: ' + ref + '\npages:\n  - id: page1\n    title: Page 1\n    visuals: []\n';
      B.api('POST', '/workspaces/me/items', { kind: 'report', title, description: '', body }).then((it) => { window.location.href = '/bi/design/' + B.id2path(it.id); }).catch((err) => say(err.message, true));
    });
    return;
  }

  const itemId = canvas.getAttribute('data-item');
  const root = q('[data-design-root]');
  if (!root) { return; }
  const shell = canvas.closest('.bi-shell');
  if (shell) { shell.classList.add('bi-shell-design'); }          // the designer uses the whole width: the BI rail steps aside
  D.itemId = itemId;
  D.say = say;
  D.q = q;
  D.model = null;
  D.item = null;
  D.dataset = null;
  D.idx = null;
  D.catalogue = null;
  D.pageId = null;
  D.selected = null;
  D.auto = new Set();            // visuals made by dropping a field: they pick a better type as more fields arrive
  D.dirty = false;
  D.readOnly = false;
  D.problems = [];
  const undo = [], redo = [];
  const listeners = {};
  D.on = (ev, fn) => { (listeners[ev] = listeners[ev] || []).push(fn); };
  D.emit = (ev, arg) => { (listeners[ev] || []).forEach((fn) => fn(arg)); };

  // ---- the draft and its history -------------------------------------------------------------------------------------
  D.page = () => D.model.pages.find((p) => p.id === D.pageId) || D.model.pages[0];
  D.visual = (id) => D.page().visuals.find((v) => v.id === id) || null;
  D.sel = () => (D.selected ? D.visual(D.selected) : null);

  /** Changes the draft: `fn` edits D.model; the change is one step of the undo history, and everything redraws. */
  D.change = function (label, fn) {
    if (D.readOnly) { say('This is shipped content: save a copy to change it.', true); return false; }
    const before = JSON.stringify(D.model);
    fn(D.model);
    if (before === JSON.stringify(D.model)) { return false; }
    undo.push(before);
    if (undo.length > 100) { undo.shift(); }
    redo.length = 0;
    D.dirty = true;
    afterChange(label);
    return true;
  };
  function afterChange(label) {
    if (!D.model.pages.some((p) => p.id === D.pageId)) { D.pageId = D.model.pages[0].id; }
    if (D.selected && !D.visual(D.selected)) { D.selected = null; }
    D.emit('model', label);
    updateButtons();
    scheduleYaml();
  }
  /** A visual with nothing in its wells comes back from the server without a `wells` key: give it one, so every pane and the canvas can read it. */
  D.normalize = function (model) {
    ((model && model.pages) || []).forEach((p) => (p.visuals || []).forEach((v) => { if (!v.wells) { v.wells = {}; } }));
    return model;
  };
  D.undo = function () {
    if (!undo.length) { return; }
    redo.push(JSON.stringify(D.model));
    D.model = JSON.parse(undo.pop());
    D.dirty = true;
    afterChange('undo');
    say('Undone.');
  };
  D.redo = function () {
    if (!redo.length) { return; }
    undo.push(JSON.stringify(D.model));
    D.model = JSON.parse(redo.pop());
    D.dirty = true;
    afterChange('redo');
    say('Redone.');
  };
  D.select = function (id) { D.selected = id; D.emit('select', id); };
  D.setPage = function (id) { D.pageId = id; D.selected = null; D.emit('page', id); D.emit('select', null); renderTabs(); };

  D.newVisualId = function (type) {
    const used = new Set(D.page().visuals.map((v) => v.id));
    let n = 1;
    while (used.has(type + '-' + n)) { n++; }
    return type + '-' + n;
  };

  /** Moves others down so nothing overlaps `moved` (and, in turn, each other). */
  D.settle = function (page, moved) {
    const hit = (a, b) => a.at.x < b.at.x + b.at.w && b.at.x < a.at.x + a.at.w && a.at.y < b.at.y + b.at.h && b.at.y < a.at.y + a.at.h;
    const queue = [moved];
    let guard = 0;
    while (queue.length && guard++ < 500) {
      const a = queue.shift();
      page.visuals.filter((b) => b !== a && hit(a, b)).sort((p, r) => p.at.y - r.at.y).forEach((b) => { b.at.y = a.at.y + a.at.h; queue.push(b); });
    }
  };

  D.firstFreeY = (page) => page.visuals.reduce((m, v) => Math.max(m, v.at.y + v.at.h), 0);

  /** The size a new visual of a type starts at. */
  D.defaultSize = (type) => ({ card: [3, 2], kpi: [3, 2], gauge: [3, 3], slicer: [3, 5], text: [4, 2], image: [3, 3], button: [3, 1], 'multirow-card': [4, 3], table: [6, 5], matrix: [6, 5] }[type] || [6, 5]);

  D.addVisual = function (type, x, y, wells, label) {
    let made = null;
    D.change(label || 'Add a ' + type, (m) => {
      const page = m.pages.find((p) => p.id === D.pageId);
      const [w, hh] = D.defaultSize(type);
      const at = { x: Math.max(0, Math.min(12 - w, x == null ? 0 : x)), y: y == null ? D.firstFreeY(page) : Math.max(0, y), w, h: hh };
      made = { id: D.newVisualId(type), type, at, wells: wells || {} };
      if (type === 'text') { made.text = 'Text'; }
      if (type === 'button') { made.text = 'Go'; made.action = {}; }
      page.visuals.push(made);
      D.settle(page, made);
    });
    if (made) { D.select(made.id); }
    return made;
  };

  // ---- wiring: the modules --------------------------------------------------------------------------------------------
  function updateButtons() {
    q('[data-undo]').disabled = !undo.length;
    q('[data-redo]').disabled = !redo.length;
    q('[data-save]').disabled = D.readOnly;
    q('[data-state]').textContent = D.readOnly ? 'Read-only (shipped)' : D.dirty ? 'Unsaved changes' : 'Saved';
  }

  // ---- page tabs -----------------------------------------------------------------------------------------------------
  function renderTabs() {
    const nav = B.clear(q('[data-ptabs]'));
    D.model.pages.forEach((p, i) => {
      const b = h('button', { type: 'button', class: 'bi-tab', role: 'tab', 'data-page': p.id, 'aria-selected': p.id === D.pageId ? 'true' : 'false', text: p.title });
      b.addEventListener('click', () => D.setPage(p.id));
      nav.appendChild(b);
    });
    const add = h('button', { type: 'button', class: 'bi-tab bi-tab-add', 'aria-label': 'Add a page', title: 'Add a page', 'data-add-page': '' }, B.icon('plus-lg'));
    add.addEventListener('click', () => {
      D.change('Add a page', (m) => {
        let n = m.pages.length + 1;
        while (m.pages.some((p) => p.id === 'page' + n)) { n++; }
        m.pages.push({ id: 'page' + n, title: 'Page ' + n, visuals: [] });
        D.pageId = 'page' + n;
      });
      D.selected = null;
      D.emit('page', D.pageId);
      renderTabs();
    });
    nav.appendChild(add);
  }
  D.on('model', renderTabs);

  // ---- the YAML tab --------------------------------------------------------------------------------------------------
  let editor = null, ignoreChange = false, yamlTimer = null, textTimer = null, yamlStale = false;
  const ta = q('[data-yaml]');

  function setYamlText(text) {
    ignoreChange = true;
    if (editor) { editor.setValue(text); } else { ta.value = text; }
    ignoreChange = false;
  }
  D.yamlText = () => (editor ? editor.getValue() : ta.value);

  function scheduleYaml() {
    yamlStale = true;
    clearTimeout(yamlTimer);
    yamlTimer = setTimeout(refreshYaml, 250);
  }
  /** Writes the draft as YAML into the tab (the server writes it, so what you see is what is saved). Returns a promise of the text. */
  function refreshYaml() {
    return B.api('POST', '/reports/format', { report: D.model }).then((r) => {
      if (yamlStale) { setYamlText(r.yaml); yamlStale = false; D.lastYaml = r.yaml; showProblems([]); }
      return r.yaml;
    }).catch((e) => { say(e.message, true); return D.yamlText(); });
  }
  D.flushYaml = () => (yamlStale ? (clearTimeout(yamlTimer), refreshYaml()) : Promise.resolve(D.yamlText()));

  /** Puts YAML in the tab as if typed (the Describe a report draft): the draft model follows it once it checks. */
  D.loadYaml = (text) => { setYamlText(text); onYamlEdit(); showTab('yaml'); };

  function onYamlEdit() {
    if (ignoreChange) { return; }
    clearTimeout(textTimer);
    textTimer = setTimeout(() => {
      const text = D.yamlText();
      B.api('POST', '/reports/validate', { yaml: text }).then((r) => {
        showProblems(r.problems || []);
        if (r.report && (r.problems || []).every((p) => p.level !== 'error' || p.code === 'DRS-9034')) {
          if (!B.same(r.report, D.model)) {
            const before = JSON.stringify(D.model);
            undo.push(before);
            redo.length = 0;
            D.model = D.normalize(r.report);
            D.dirty = true;
            yamlStale = false;
            D.lastYaml = text;
            if (!D.model.pages.some((p) => p.id === D.pageId)) { D.pageId = D.model.pages[0].id; }
            if (D.selected && !D.visual(D.selected)) { D.selected = null; }
            D.emit('model', 'yaml');
            updateButtons();
            renderTabs();
          }
        }
      }).catch((e) => showProblems([{ level: 'error', code: e.code || '', path: '', message: e.detail || e.message }]));
    }, 450);
  }

  function showProblems(list, into) {
    const box = into || q('[data-yaml-problems]');
    B.clear(box);
    list.forEach((p) => {
      const m = /^pages\[(\d+)\]\.visuals\[(\d+)\]/.exec(p.path || '');
      const vis = m && D.model && D.model.pages[+m[1]] && D.model.pages[+m[1]].visuals[+m[2]];
      const who = vis ? 'Visual ' + (vis.title ? '"' + vis.title + '" ' : '') + '(' + vis.id + ', number ' + (+m[2] + 1) + ' on page ' + D.model.pages[+m[1]].title + '): ' : '';
      const li = h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: (p.code || p.level) + ' ' }), p.path ? h('code', { text: p.path }) : null, ': ' + who + p.message);
      if (m && D.model && D.model.pages[+m[1]] && D.model.pages[+m[1]].visuals[+m[2]]) {
        li.tabIndex = 0;
        li.classList.add('bi-problem-link');
        const go = () => { D.setPage(D.model.pages[+m[1]].id); D.select(D.model.pages[+m[1]].visuals[+m[2]].id); showTab('canvas'); };
        li.addEventListener('click', go);
        li.addEventListener('keydown', (e) => { if (e.key === 'Enter') { go(); } });
      }
      box.appendChild(li);
    });
  }

  /** What the cursor is in, for the suggestions strip: type names after `type:`, fields and measures inside wells, keys elsewhere. */
  const KEYS = ['report', 'title', 'description', 'dataset', 'theme', 'dates', 'filters', 'bookmarks', 'layouts', 'pages', 'id', 'at', 'wells', 'sort', 'limit', 'hierarchy', 'drillthrough', 'format', 'text', 'alt', 'interacts', 'action', 'visuals'];
  function suggest() {
    const strip = B.clear(q('[data-suggest]'));
    if (!editor || !D.idx) { return; }
    const cur = editor.getCursor(), line = editor.getLine(cur.line).slice(0, cur.ch);
    const m = /([A-Za-z0-9_.' -]*)$/.exec(line);
    const word = m ? m[1].replace(/^[ '-]+/, '') : '';
    let pool = [];
    if (/type:\s*[\w-]*$/.test(line)) { pool = Object.keys(D.catalogue.visuals); } else if (/op:\s*[\w-]*$/.test(line)) { pool = ['in', 'not-in', 'eq', 'ne', 'lt', 'le', 'gt', 'ge', 'between', 'contains', 'starts-with', 'is-null', 'not-null', 'top']; }
    else if (/mode:\s*[\w-]*$/.test(line)) { pool = D.catalogue.slicerModes; } else if (/hierarchy:\s*[\w ]*$/.test(line)) { pool = Object.keys(D.idx.hierarchies); }
    else if (/(page|navigate):\s*[\w-]*$/.test(line)) { pool = D.model.pages.map((p) => p.id); }
    else if (/(axis|legend|values|columns|rows|secondary|x|y|size|details|target|field|by)\s*:\s*[[{]?[^\]}]*$/.test(line) || /^\s*-\s/.test(line)) { pool = Object.keys(D.idx.columns).concat(Object.keys(D.idx.measures)); }
    else { pool = KEYS; }
    const w = word.toLowerCase();
    pool.filter((x) => !w || x.toLowerCase().indexOf(w) >= 0).slice(0, 14).forEach((x) => {
      const b = h('button', { type: 'button', class: 'bi-suggest', text: x });
      b.addEventListener('mousedown', (e) => e.preventDefault());
      b.addEventListener('click', () => {
        const from = { line: cur.line, ch: cur.ch - word.length };
        editor.replaceRange(/\s/.test(x) || /[,:[\]{}#]/.test(x) ? JSON.stringify(x) : x, from, cur);
        editor.focus();
      });
      strip.appendChild(b);
    });
  }

  function showTab(name) {
    ['canvas', 'phone', 'tablet', 'yaml'].forEach((t) => {
      q('[data-view="' + t + '"]').hidden = t !== name;
      q('[data-tab="' + t + '"]').setAttribute('aria-selected', t === name ? 'true' : 'false');
    });
    if (name === 'yaml') { D.flushYaml().then(() => { if (editor) { editor.refresh(); editor.focus(); } else { ta.focus(); } suggest(); }); } else if (name === 'phone' || name === 'tablet') { D.emit(name, 'show'); } else { D.emit('model', 'show'); }
  }

  // ---- Check, Save ---------------------------------------------------------------------------------------------------
  /** Runs the report's expected values (tests:) as the chosen role and lists each as passed, failed or could not run. Hidden when the report declares none. */
  function runTests(yaml) {
    const tests = (D.model.tests || []);
    const head = q('[data-tests-head]'), run = q('[data-tests-run]'), sum = q('[data-tests-sum]'), list = B.clear(q('[data-tests-list]'));
    head.hidden = run.hidden = sum.hidden = !tests.length;
    if (!tests.length || !itemId) { return; }
    const sel = q('[data-tests-role]');
    if (!sel.options.length) {
      B.api('GET', '/me').then((me) => {
        sel.appendChild(h('option', { value: '', text: 'yourself' }));
        (me.roles || []).forEach((r) => sel.appendChild(h('option', { value: r, text: r })));
      }).catch(() => {});
    }
    q('[data-tests-again]').onclick = () => D.flushYaml().then(runTests);
    sum.textContent = 'Running ' + tests.length + ' test' + (tests.length === 1 ? '' : 's') + '...';
    B.api('POST', '/items/' + B.id2path(itemId) + '/tests', { yaml, role: sel.value || undefined }).then((r) => {
      sum.textContent = r.passed + ' passed, ' + r.failed + ' failed' + (r.errors ? ', ' + r.errors + ' could not run' : '') + (r.role ? ' (as ' + r.role + ')' : '') + '.';
      r.results.forEach((x) => list.appendChild(h('li', { class: 'bi-problem bi-problem-' + (x.status === 'pass' ? 'info' : x.status === 'fail' ? 'error' : 'warning'), 'data-test': x.status },
        h('strong', { text: x.status.toUpperCase() + ' ' }), x.name + ': ' + (x.status === 'pass' ? x.expect + ' (' + x.actual + ')' : x.message))));
    }).catch((e) => { sum.textContent = e.message; });
  }

  function check() {
    D.flushYaml().then((yaml) => { runTests(yaml); return B.api('POST', '/reports/validate', { yaml, quality: true }); }).then((r) => {
      const dlg = q('[data-check-box]');
      dlg.hidden = false;
      const list = q('[data-check-list]');
      showProblems(r.problems || [], list);
      q('[data-check-sum]').textContent = (r.problems || []).length ? (r.problems || []).filter((p) => p.level === 'error').length + ' errors, ' + (r.problems || []).filter((p) => p.level !== 'error').length + ' warnings.' : 'Nothing to report: the draft is valid and passes the basic accessibility checks.';
      // performance hint: any visual slower than a second in the last preview
      const slow = [...(D.timings || new Map()).entries()].filter(([, ms]) => ms > 1000);
      slow.forEach(([id, ms]) => list.appendChild(h('li', { class: 'bi-problem bi-problem-warning', 'data-code': 'PERF' }, h('strong', { text: 'PERF ' }), id + ' took ' + Math.round(ms) + ' ms: a visual over one second is worth a rollup or a filter.')));
      q('[data-check-sum]').focus();
    }).catch((e) => say(e.message, true));
  }

  function save() {
    if (D.readOnly) { return; }
    D.flushYaml().then((yaml) => B.api('PUT', '/items/' + B.id2path(itemId), { body: yaml })).then((it) => {
      D.dirty = false;
      D.item = it;
      updateButtons();
      say('Saved. It is a draft until you publish it from the item page.');
    }).catch((e) => say(e.message, true));
  }

  function saveCopy() {
    D.flushYaml().then((yaml) => B.api('POST', '/workspaces/me/items', { kind: 'report', title: (D.model.title || 'Report') + ' (copy)', description: D.model.description || '', body: yaml })).then((it) => {
      D.dirty = false;
      window.location.href = '/bi/design/' + B.id2path(it.id);
    }).catch((e) => say(e.message, true));
  }

  // ---- start ---------------------------------------------------------------------------------------------------------
  function bindToolbar() {
    q('[data-undo]').addEventListener('click', D.undo);
    q('[data-redo]').addEventListener('click', D.redo);
    q('[data-check]').addEventListener('click', check);
    q('[data-check-close]').addEventListener('click', () => { q('[data-check-box]').hidden = true; });
    q('[data-save]').addEventListener('click', save);
    q('[data-save-copy]').addEventListener('click', saveCopy);
    q('[data-tab="canvas"]').addEventListener('click', () => showTab('canvas'));
    q('[data-tab="phone"]').addEventListener('click', () => showTab('phone'));
    q('[data-tab="tablet"]').addEventListener('click', () => showTab('tablet'));
    q('[data-tab="yaml"]').addEventListener('click', () => showTab('yaml'));
    q('[data-preview]').addEventListener('change', (e) => { D.preview = e.target.checked; D.emit('preview', D.preview); });
    D.preview = true;
    document.addEventListener('keydown', (e) => {
      const k = e.key.toLowerCase();
      if ((e.ctrlKey || e.metaKey) && !e.target.closest('input,textarea,select,.CodeMirror')) {
        if (k === 'z' && !e.shiftKey) { e.preventDefault(); D.undo(); } else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); D.redo(); } else if (k === 's') { e.preventDefault(); save(); }
      }
    });
    window.addEventListener('beforeunload', (e) => { if (D.dirty) { e.preventDefault(); e.returnValue = ''; } });
    if (window.CodeMirror) {
      editor = window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, lineWrapping: true, indentUnit: 2, tabSize: 2, viewportMargin: Infinity, extraKeys: { Tab: (cm) => cm.replaceSelection('  ') } });
      editor.on('change', onYamlEdit);
      editor.on('cursorActivity', suggest);
    } else { ta.addEventListener('input', onYamlEdit); }
  }

  /** A report draft that names no dataset has nothing to draw: ask for one (the datasets the person may read) and write `dataset:` into the draft. */
  function ensureDataset(item) {
    if (/^dataset:\s*\S/m.test(item.body || '')) { return Promise.resolve(); }
    return B.api('GET', '/datasets').then((r) => new Promise((resolve) => {
      const sel = h('select', { class: 'studio-select', 'data-pick-dataset': '', 'aria-label': 'Dataset for this report' }, h('option', { value: '', text: 'Choose a dataset' }));
      (r.datasets || []).filter((d) => d.valid).forEach((d) => sel.appendChild(h('option', { value: d.id.replace(/^file:dataset:/, ''), text: d.title })));
      const go = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-pick-dataset-go': '', text: 'Use this dataset' });
      const box = h('section', { class: 'bi-form', 'data-choose-dataset': '', role: 'group', 'aria-label': 'Choose a dataset' },
        h('h2', { text: 'Choose a dataset' }), h('p', { class: 'text-muted-d', text: 'This report does not name a dataset yet, so there is nothing to draw. Choose the one it reads; you can change it later in the YAML tab.' }), sel, go);
      canvas.insertBefore(box, canvas.firstChild);
      go.addEventListener('click', () => {
        if (!sel.value) { say('Choose a dataset.', true); return; }
        const line = 'dataset: ' + sel.value + '\n';
        item.body = /^pages:/m.test(item.body || '') ? item.body.replace(/^pages:/m, line + 'pages:') : (item.body || '') + (item.body && !/\n$/.test(item.body) ? '\n' : '') + line;
        D.pickedDataset = true;
        box.remove();
        resolve();
      });
    }));
  }

  Promise.all([B.api('GET', '/items/' + B.id2path(itemId)), B.api('GET', '/reports/catalogue')]).then(([item, cat]) => {
    D.item = item;
    D.catalogue = cat;
    if (item.kind !== 'report') { throw new Error('This item is not a report.'); }
    D.readOnly = !!item.shipped || !(item.can && item.can.edit);
    q('[data-title]').textContent = item.title;
    q('[data-item-link]').href = '/bi/i/' + B.id2path(itemId);
    q('[data-view-link]').href = '/bi/r/' + B.id2path(itemId);
    q('[data-save-copy]').hidden = !D.readOnly;
    if (D.readOnly) { q('[data-readonly]').hidden = false; }
    bindToolbar();
    return ensureDataset(item).then(() => B.api('POST', '/reports/validate', { yaml: item.body }));
  }).then((r) => {
    if (!r.report) { showProblems(r.problems || []); q('[data-yaml-problems]').hidden = false; showTab('yaml'); setYamlText(D.item.body); say('The YAML does not parse; fix it in the YAML tab.', true); return null; }
    D.model = D.normalize(r.report);
    D.pageId = D.model.pages[0].id;
    D.lastYaml = D.item.body;
    return B.api('GET', '/reports/dataset?ref=' + encodeURIComponent(D.model.dataset)).then((ds) => {
      D.dataset = ds;
      D.idx = B.index(ds);
      D.tokens = B.tokens();
      D.palette = B.palette((D.model.theme || {}).palette, D.tokens);
      D.modules.forEach((fn) => fn(D));
      setYamlText(D.item.body);
      renderTabs();
      updateButtons();
      D.emit('model', 'load');
      D.emit('select', null);
      const bad = (r.problems || []).filter((p) => p.level === 'error');
      if (bad.length) { say(bad.length + ' problem' + (bad.length === 1 ? '' : 's') + ' in the draft; see Check.', true); }
      if (D.pickedDataset) { D.dirty = true; updateButtons(); }
      root.setAttribute('data-ready', 'yes');
    });
  }).catch((e) => { say(e.message, true); });
})();
