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
    const list = q('[data-drafts]'), wsSel = q('[data-new-workspace]');
    B.api('GET', '/me').then((me) => {                                  // the power is explained before anyone fills a form that cannot be saved
      if ((me.powers || []).indexOf('bi-author') < 0) {
        const note = h('p', { class: 'bi-warn', role: 'alert', 'data-need-author': '' }, h('strong', { text: 'You need the bi-author power to design. ' }), 'Your roles give you ' + ((me.powers || []).join(', ') || 'no BI power') + '. Ask an administrator to add bi-author to one of your roles (Admin, BI settings). You can still open published reports from the ', h('a', { href: '/bi/catalogue', text: 'catalogue' }), '.');
        q('[data-design-start]').before(note);
        B.qa('form button[type=submit], form input, form select', q('[data-design-start]')).forEach((el) => { el.disabled = true; });
        B.qa('[data-sample-copy]', q('[data-design-start]')).forEach((el) => { el.disabled = true; });
      }
    }).catch(() => {});
    const want = new URLSearchParams(window.location.search).get('dataset');             // from a dataset page: New report on this dataset
    B.fillWorkspaces(wsSel).then((wss) => Promise.all(wss.map((w) => B.api('GET', '/workspaces/' + encodeURIComponent(w.id) + '/items').then((items) => items.filter((i) => i.kind === 'report').map((i) => ({ i, w }))))))
      .then((groups) => {
        [].concat.apply([], groups).forEach(({ i, w }) => {
          list.appendChild(h('li', { 'data-id': i.id }, h('a', { href: '/bi/design/' + B.id2path(i.id), text: i.title }), ' ', h('span', { class: 'text-muted-d', text: i.status + ' · ' + w.name })));
        });
        q('[data-no-drafts]').hidden = list.children.length > 0;
      }).catch((e) => say(e.message, true));
    B.fillDatasetPickers(q('[data-new-dataset]'), q('[data-new-also]'), want).then((ds) => {
      B.excludeMain(q('[data-new-dataset]'), q('[data-new-also]'))();
      q('[data-no-datasets]').hidden = ds.some((d) => !B.datasetWhy(d));
    }).catch((e) => say(e.message, true));
    B.api('GET', '/workspaces').then((all) => {
      const shipped = all.find((w) => w.shipped);
      return shipped ? B.api('GET', '/workspaces/' + encodeURIComponent(shipped.id) + '/items') : [];
    }).then((items) => {
      const box = q('[data-samples]');
      items.filter((i) => i.kind === 'report' || i.kind === 'board').forEach((i) => {
        const board = i.kind === 'board', open = board ? '/bi/design/board/' : '/bi/design/';
        const copy = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-sample-copy': i.id, 'aria-label': 'Copy and design ' + i.title, text: 'Copy and design' });
        copy.addEventListener('click', () => B.askWorkspace(copy, 'Copy and open', 'Copy ' + i.title + ' into').then((to) => B.api('GET', '/items/' + B.id2path(i.id)).then((full) =>
          B.api('POST', '/workspaces/' + encodeURIComponent(to) + '/items', { kind: i.kind, title: i.title, description: i.description || '', body: B.copyBody(full.body) })))
          .then((it) => { window.location.href = open + B.id2path(it.id); }).catch((e) => { if (!e.cancelled) { say(e.message, true); } }));
        box.appendChild(h('li', { 'data-sample': i.id }, h('a', { href: (board ? '/bi/b/' : '/bi/r/') + B.id2path(i.id), text: i.title }), ' ', h('span', { class: 'text-muted-d', text: (board ? 'Board. ' : '') + (i.description || '') }), ' ', copy));
      });
      q('[data-no-samples]').hidden = box.children.length > 0;
    }).catch(() => { q('[data-no-samples]').hidden = false; });
    q('[data-new]').addEventListener('submit', (e) => {
      e.preventDefault();
      const ref = q('[data-new-dataset]').value, title = q('[data-new-title]').value.trim() || defaultTitle();
      if (!ref) { say('Choose a dataset.', true); return; }
      const name = B.slug(title) || 'my-report';
      const body = 'rachana: 1\nreport: ' + name + '\ntitle: ' + JSON.stringify(title) + '\ndataset: ' + ref + '\npages:\n  - id: page1\n    title: Page 1\n    visuals: []\n';
      const go = q('[data-new] [type=submit]');
      const also = Array.prototype.filter.call(q('[data-new-also]').selectedOptions, (o) => o.value !== ref).map((o) => o.value.replace(/^file:dataset:/, ''));
      B.busy(go, () => B.api('POST', '/workspaces/' + encodeURIComponent(wsSel.value || 'me') + '/items', { kind: 'report', title, description: '', body }).then((it) => { window.location.href = '/bi/design/' + B.id2path(it.id) + (also.length ? '?also=' + encodeURIComponent(also.join(',')) : ''); }).catch((err) => B.near(go, err.message, true)));
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
    if (D.readOnly) { say('This is read-only: save a copy to change it, or withdraw the proposal.', true); return false; }
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

  /**
   * Gives a visual a new id and keeps everything that named the old one pointing at it: the bookmarks' slicer selections and the phone and tablet layouts of its page
   * (order, hidden, wide and row spans), and the report's expected values (`tests:`). Call it inside a change so Undo brings the old id and its references back.
   */
  D.renameVisual = function (v, next) {
    const old = v.id, m = D.model, pageId = D.pageId;
    if (old === next) { return; }
    v.id = next;
    (m.bookmarks || []).filter((b) => b.page === pageId && b.slicers && Object.prototype.hasOwnProperty.call(b.slicers, old)).forEach((b) => { b.slicers[next] = b.slicers[old]; delete b.slicers[old]; });
    Object.keys(m.layouts || {}).forEach((device) => {
      const lay = (m.layouts[device] || {})[pageId];
      if (!lay) { return; }
      ['order', 'hide', 'wide'].forEach((k) => { if (Array.isArray(lay[k])) { lay[k] = lay[k].map((x) => (x === old ? next : x)); } });
      if (lay.span && Object.prototype.hasOwnProperty.call(lay.span, old)) { lay.span[next] = lay.span[old]; delete lay.span[old]; }
    });
    (m.tests || []).forEach((t) => { if (t && t.visual === old && (!t.page || t.page === pageId)) { t.visual = next; } });
    if (D.auto && D.auto.has(old)) { D.auto.delete(old); D.auto.add(next); }
    if (D.selected === old) { D.selected = next; }
  };
  /** The id a visual of `was` type gets when it becomes `type`: a made-up id (`card-1`, `slicer-2`, `visual-3`) follows the type; an id its author chose is kept. */
  D.followType = function (v, type) {
    if (/^[a-z][a-z0-9]*(-[a-z0-9]+)*-\d+$/.test(v.id) && v.id.replace(/-\d+$/, '') !== type) { D.renameVisual(v, D.newVisualId(type)); }
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
    q('[data-state]').textContent = D.readOnly ? (D.item && D.item.status === 'proposed' ? 'Read-only (proposed)' : 'Read-only') : D.dirty ? 'Unsaved changes' : 'Saved';
  }

  // ---- page tabs -----------------------------------------------------------------------------------------------------
  function renderTabs() {
    const nav = B.clear(q('[data-ptabs]'));
    D.model.pages.forEach((p, i) => {
      const b = h('button', { type: 'button', class: 'bi-tab', role: 'tab', 'data-page': p.id, 'aria-selected': p.id === D.pageId ? 'true' : 'false', text: p.title });
      b.addEventListener('click', () => D.setPage(p.id));
      b.title = 'Double-click to rename';
      b.addEventListener('dblclick', () => {
        if (D.readOnly) { return; }
        const inp = h('input', { type: 'text', class: 'studio-in bi-tab-rename', value: p.title, 'aria-label': 'Page title', 'data-rename-page': p.id });
        b.replaceWith(inp);
        inp.focus();
        inp.select();
        let done = false;
        const commit = (keep) => {
          if (done) { return; }
          done = true;
          const t = inp.value.trim();
          if (keep && t && t !== p.title) { D.change('Rename the page', (m) => { const pg = m.pages.find((x) => x.id === p.id); if (pg) { pg.title = t; } }); } else { renderTabs(); }
        };
        inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') { commit(true); } else if (e.key === 'Escape') { commit(false); } });
        inp.addEventListener('blur', () => commit(true));
      });
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

  /** The YAML parsed: the draft follows it (one undo step). */
  function onYamlApply(r, text) {
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
    } else { D.lastYaml = text; }
    return Promise.resolve();
  }

  function onYamlEdit() {
    if (ignoreChange) { return; }
    clearTimeout(textTimer);
    textTimer = setTimeout(() => {
      const text = D.yamlText();
      B.api('POST', '/reports/validate', { yaml: text }).then((r) => {
        showProblems(r.problems || []);
        if (r.report && (r.problems || []).every((p) => p.level !== 'error' || p.code === 'DRS-9034')) { onYamlApply(r, text); }
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

  let tabNow = 'canvas';
  /** Leaving the YAML tab with YAML that does not parse would silently drop the edits: stop, name the error and offer to discard the YAML edits. */
  function leaveYaml(name) {
    clearTimeout(textTimer);
    const text = D.yamlText();
    if (text === D.lastYaml) { return Promise.resolve(true); }
    return B.api('POST', '/reports/validate', { yaml: text }).then((r) => {
      const errors = (r.problems || []).filter((p) => p.level === 'error' && p.code !== 'DRS-9034');
      if (r.report && !errors.length) { return onYamlApply(r, text).then(() => true); }
      showProblems(r.problems || []);
      const box = q('[data-yaml-problems]');
      const discard = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-yaml-discard': '', text: 'Discard my YAML edits and go to ' + name });
      discard.addEventListener('click', () => { yamlStale = true; D.lastYaml = null; refreshYaml().then(() => showTab(name, true)); });
      box.appendChild(h('li', { class: 'bi-problem bi-problem-error', 'data-yaml-blocked': '' }, 'The YAML has ' + errors.length + ' error' + (errors.length === 1 ? '' : 's') + ', so the ' + name + ' cannot show it. Fix them here, or ', discard, '.'));
      say('The YAML has errors: fix them, or discard the YAML edits to leave this tab.', true);
      return false;
    }).catch((e) => { say(e.message, true); return false; });
  }

  function showTab(name, forced) {
    if (tabNow === 'yaml' && name !== 'yaml' && !forced && editor !== undefined) {
      leaveYaml(name).then((ok) => { if (ok) { showTab(name, true); } });
      return;
    }
    tabNow = name;
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
      const n = (r.problems || []).filter((p) => p.level === 'error').length;
      B.near(q('[data-check]'), n ? n + ' error' + (n === 1 ? '' : 's') + ': see the list.' : 'Checked: no errors.', n > 0);
    }).catch((e) => { say(e.message, true); B.near(q('[data-check]'), e.message, true); });
  }

  function save() {
    if (D.readOnly) { return Promise.resolve(); }
    const btn = q('[data-save]');
    return B.busy(btn, () => D.flushYaml().then((yaml) => B.saveItem(itemId, { body: yaml }, D.item && D.item.etag, btn, () => saveCopy(true))).then((it) => {
      D.dirty = false;
      D.item = it;
      updateButtons();
      const pub = it.workspaceReviewRequired;
      const word = 'Saved. It is a draft until you ' + (pub ? 'propose it for review' : 'publish it') + ' (the Publish button).';
      say('');                                                    // one message, beside the button
      B.near(btn, word, false);
    })).catch((e) => { if (!e.cancelled) { say(''); B.near(btn, e.message, true); } });
  }

  function saveCopy(fromStale) {
    return B.askWorkspace(q('[data-save-copy]').hidden ? q('[data-save]') : q('[data-save-copy]'), 'Save the copy', 'Save the copy into').then((to) => D.flushYaml().then((yaml) => B.api('POST', '/workspaces/' + encodeURIComponent(to) + '/items', { kind: 'report', title: (D.model.title || 'Report') + ' (copy)', description: D.model.description || '', body: yaml }))).then((it) => {
      D.dirty = false;
      window.location.href = '/bi/design/' + B.id2path(it.id);
    }).catch((e) => { if (!e.cancelled) { say(e.message, true); } if (fromStale) { throw e; } });
  }

  /** Publish...: saves first (asking when there are unsaved changes), then proposes for review where the workspace requires it, else publishes. Draft errors are named, and a bi-admin may force. */
  function publishNow() {
    const btn = q('[data-publish]');
    if (D.item && D.item.personal && !D.item.shipped) { B.near(btn, 'This report is in your private workspace, which cannot publish. Move it to a shared workspace (Move on its item page), then publish it there.', true); return Promise.resolve(); }
    const go = () => {
      const review = D.item && D.item.workspaceReviewRequired;
      const verb = review ? 'propose' : 'publish';
      return B.api('POST', '/items/' + B.id2path(itemId) + '/' + verb, {}).then((it) => {
        D.item = it;
        say(review ? 'Proposed for review. Open the item page to follow it; this draft is now frozen until it is approved or withdrawn.' : 'Published.');
        window.setTimeout(() => { window.location.href = '/bi/i/' + B.id2path(itemId); }, 900);
      }).catch((e) => { B.near(btn, e.message, true); });
    };
    if (D.dirty) {
      return B.confirm(btn, 'There are unsaved changes. Save them first, then ' + ((D.item && D.item.workspaceReviewRequired) ? 'propose' : 'publish') + '?', 'Save and continue')
        .then((yes) => (yes ? B.busy(btn, () => save().then(() => (D.dirty ? null : go()))) : null));
    }
    return B.busy(btn, go);
  }

  // ---- start ---------------------------------------------------------------------------------------------------------
  function bindPanes() {
    const main = q('[data-dmain]'), hide = q('[data-hide-filters]');
    const shown = () => !main.classList.contains('bi-nofilters');
    const set = (on) => { main.classList.toggle('bi-nofilters', !on); hide.textContent = on ? 'Hide' : 'Show'; };
    let was = false;
    try { was = window.localStorage.getItem('drishti.bi.designer.hideFilters') === '1'; } catch (e) { was = false; }
    set(!was);
    hide.addEventListener('click', () => {
      set(!shown());
      try { window.localStorage.setItem('drishti.bi.designer.hideFilters', shown() ? '0' : '1'); } catch (e) { /* not kept */ }
    });
    B.qa('[data-mpane-tab]').forEach((b) => b.addEventListener('click', () => {
      main.setAttribute('data-mpane', b.getAttribute('data-mpane-tab'));
      B.qa('[data-mpane-tab]').forEach((x) => x.setAttribute('aria-selected', x === b ? 'true' : 'false'));
    }));
  }

  function bindToolbar() {
    bindPanes();
    q('[data-undo]').addEventListener('click', D.undo);
    q('[data-redo]').addEventListener('click', D.redo);
    q('[data-check]').addEventListener('click', check);
    q('[data-check-close]').addEventListener('click', () => { q('[data-check-box]').hidden = true; });
    q('[data-save]').addEventListener('click', save);
    q('[data-save-copy]').addEventListener('click', () => saveCopy(false));
    q('[data-publish]').addEventListener('click', publishNow);
    q('[data-tab="canvas"]').addEventListener('click', () => showTab('canvas'));
    q('[data-tab="phone"]').addEventListener('click', () => showTab('phone'));
    q('[data-tab="tablet"]').addEventListener('click', () => showTab('tablet'));
    q('[data-tab="yaml"]').addEventListener('click', () => showTab('yaml'));
    q('[data-preview]').addEventListener('change', (e) => { D.preview = e.target.checked; D.emit('preview', D.preview); });
    D.preview = true;
    document.addEventListener('keydown', (e) => {
      const k = e.key.toLowerCase();
      if ((e.ctrlKey || e.metaKey) && k === 's') { e.preventDefault(); save(); return; }           // saves from anywhere, the YAML editor too, and never opens the browser's own Save dialog
      if ((e.ctrlKey || e.metaKey) && !e.target.closest('input,textarea,select,.CodeMirror')) {
        if (k === 'z' && !e.shiftKey) { e.preventDefault(); D.undo(); } else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); D.redo(); }
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
    const frozen = item.status === 'proposed';
    D.readOnly = !!item.shipped || frozen || !(item.can && item.can.edit);
    q('[data-title]').textContent = item.title;
    q('[data-item-link]').href = '/bi/i/' + B.id2path(itemId);
    const viewAs = q('[data-view-as]');
    const viewHref = () => '/bi/r/' + B.id2path(itemId) + (viewAs.value === 'published' ? '?as=published' : '');
    q('[data-view-link]').href = viewHref();
    if (item.publishedVersion > 0 || item.shipped) { viewAs.querySelector('option[value=published]').disabled = false; }
    viewAs.hidden = false;
    viewAs.addEventListener('change', () => { q('[data-view-link]').href = viewHref(); });
    q('[data-save-copy]').hidden = !D.readOnly;
    q('[data-dmain]').classList.toggle('bi-ro', D.readOnly);                       // the palette, the fields and the visuals' delete look switched off, as they are
    q('[data-publish-label]').textContent = item.workspaceReviewRequired ? 'Propose' : 'Publish';
    q('[data-publish]').hidden = D.readOnly || !(item.can && (item.can.publish || item.can.propose || (item.personal && item.can.edit)));       // a private workspace shows Publish too: pressing it explains what to do
    const ro = q('[data-readonly]');
    if (D.readOnly) {
      ro.hidden = false;
      B.clear(ro);
      if (item.shipped) {
        ro.appendChild(B.h('span', {}, 'This is shipped, read-only content. You can look, try changes and check them; ', B.h('strong', { text: 'Save a copy' }), ' makes a draft of your own.'));
      } else if (frozen) {
        const w = B.h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-withdraw': '', text: 'Withdraw to edit' });
        w.hidden = !(item.can && item.can.withdraw);
        w.addEventListener('click', () => B.busy(w, () => B.api('POST', '/items/' + B.id2path(itemId) + '/withdraw', {}).then(() => window.location.reload()).catch((e) => B.near(w, e.message, true))));
        ro.appendChild(B.h('span', {}, 'Proposed for review by ' + (item.proposedBy || 'someone') + ': it is frozen. Withdraw the proposal to edit it (or save a copy). ', w));
      } else {
        ro.appendChild(B.h('span', { text: 'You can look at this report but not change it: you need to be a contributor in its workspace with the bi-author power. You can still save a copy.' }));
      }
    }
    q('[data-published-note]').hidden = D.readOnly || item.status !== 'published';
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
  }).catch((e) => {
    if (e.status === 404 || e.status === 403) {                      // not there, or not yours to open: a page that says so, not a half-drawn designer
      B.clear(root).appendChild(h('section', { class: 'bi-sec', 'data-design-denied': '' }, h('h1', { text: e.status === 404 ? 'This report is not available' : 'You cannot open this report' }),
        h('p', { text: e.status === 404 ? 'There is no report here, or it is in a workspace you cannot see.' : 'You do not have the BI power or the workspace role this needs.' }),
        h('p', {}, h('a', { class: 'btn-pill btn-accent', href: '/bi/designer', text: 'Back to the designer' }), ' ', h('a', { class: 'btn-pill btn-ghost', href: '/bi/catalogue', text: 'Open the catalogue' }))));
      return;
    }
    say(e.message, true);
  });
})();
