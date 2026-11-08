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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* BI Studio, the one shell of the BI designers (docs/architecture/BI_DESIGN.md section 8.1), on drishti-designer's DesignerShell (ELEMENTS.md
   8.8, 12.3): the header (where you are, the name, the status, the designer's actions), the left rail's Explorer (the items of the workspaces you
   may edit: reports, boards, models, datasets, themes, Python items, with a search and + New; it folds away), the open items above the designer
   (kept for the session: a per-person convenience, never the work itself, which the server saves), the designer in the middle, and the dock
   under it with Problems (what the server's Check says about the draft) and Query (the selected visual's model query and SQL). Its layout (studio,
   side by side, terminal: the kit's three, with the terminal's DESIGN> line and function keys) is remembered for the person in this browser.
   A designer page opts in with `data-studio` (and `data-studio-kind`) on its root, its own content inside, and tells the shell what it holds
   (`window.drishtiBi.studio.attach`). */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const root = document.querySelector('[data-studio]');
  const K = window.Drishti.elements && window.Drishti.elements.designer;
  if (!B || !root || !K) { return; }
  const bi = root.closest('.bi-shell');
  if (bi) { bi.classList.add('bi-shell-design'); }                         // the Studio uses the whole width: the BI rail steps aside
  const S = (B.studio = B.studio || {});
  const key = (n) => (window.Drishti.key ? window.Drishti.key(n) : n);
  const TABS = key('drishti.bi.studio.tabs'), RAIL = key('drishti.bi.studio.rail'), DOCK = key('drishti.bi.studio.dock'), LAYOUT = key('drishti.designer.layout');
  const KINDS = [['report', 'Reports', 'file-earmark-bar-graph'], ['board', 'Boards', 'easel'], ['model', 'Models', 'boxes'], ['dataset', 'Datasets', 'hdd-stack'],
    ['theme', 'Themes', 'palette2'], ['python', 'Python', 'code-slash']];
  const read = (store, k, d) => { try { const v = window[store].getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } };
  const write = (store, k, v) => { try { window[store].setItem(k, JSON.stringify(v)); } catch (e) { /* not kept: a private window */ } };

  /** The designer's actions in the shell's header, each with the data-* hook the designer's script (and the tests) find it by. */
  const ACTIONS = {
    report: [
      { id: 'aiDraft', label: 'Describe a report', title: 'The AI drafts a report from your words', enabled: true, hidden: true, attrs: { 'data-ai-draft': '' } },
      { id: 'aiSuggest', label: 'Suggest visuals', title: 'The AI suggests visuals for this report', enabled: true, hidden: true, attrs: { 'data-ai-suggest': '' } },
      { id: 'undo', label: 'Undo', title: 'Undo (Ctrl+Z)', attrs: { 'data-undo': '' } },
      { id: 'redo', label: 'Redo', title: 'Redo (Ctrl+Y)', attrs: { 'data-redo': '' } },
      { id: 'preview', label: 'Preview with data', title: 'Draw the visuals with data, or as outlines only', toggle: true, pressed: true, enabled: true, attrs: { 'data-preview': '' } },
      { id: 'validate', label: 'Check', title: 'Check the draft on the server', enabled: true, attrs: { 'data-check': '' } },
      { id: 'viewAs', label: 'View which version', enabled: true, hidden: true, attrs: { 'data-view-as': '' },
        choices: [{ value: 'draft', label: 'View: my draft' }, { value: 'published', label: 'View: as readers see it (published)', disabled: true }] },
      { id: 'view', label: 'View', title: 'Open the report page: authors see the saved draft', href: '/bi', attrs: { 'data-view-link': '' } },
      { id: 'item', label: 'Item page', title: 'The item page', href: '/bi', attrs: { 'data-item-link': '' } },
      { id: 'propose', label: 'Publish', title: 'Save, then publish (or propose for review where the workspace requires it)', enabled: true, attrs: { 'data-publish': '' } },
      { id: 'saveCopy', label: 'Save a copy', enabled: true, hidden: true, attrs: { 'data-save-copy': '' } },
      { id: 'save', label: 'Save', title: 'Save the draft (Ctrl+S)', primary: true, enabled: true, attrs: { 'data-save': '' } }],
    theme: [
      { id: 'propose', label: 'Publish', title: 'Publish the theme (or propose it for review where the workspace requires it)', enabled: true, attrs: { 'data-publish': '' } },
      { id: 'save', label: 'Save', title: 'Save the theme', primary: true, enabled: true, attrs: { 'data-save': '' } }]
  };
  const kind = root.getAttribute('data-studio-kind') || 'report';
  const body = Array.prototype.slice.call(root.children);                   // the designer's own content, moved into the shell's editor pane
  const narrow = window.matchMedia && window.matchMedia('(max-width: 900px)').matches;
  const shell = (S.shell = new K.DesignerShell(root, {
    crumbs: [{ label: 'BI', href: '/bi' }, { label: 'Studio', href: '/bi/designer' }, (KINDS.find((k) => k[0] === kind) || [])[1] || 'Reports'],
    title: kind === 'theme' ? 'Theme' : 'Report', label: 'BI Studio', search: 'Find an item', modes: kind === 'theme',      // a theme's YAML is the shell's panel (Visual, YAML, Both); a report's is its designer's tab
    preview: false, docs: true, collapsible: true,
    dock: [{ id: 'problems', label: 'Problems' }, { id: 'query', label: 'Query' }], actions: ACTIONS[kind] || ACTIONS.report,
    newItems: [{ id: 'report', label: 'Report' }, { id: 'board', label: 'Board' }, { id: 'theme', label: 'Theme' }, { id: 'dataset', label: 'Dataset' }, { id: 'model', label: 'Model' }],
    layout: read('localStorage', LAYOUT, 'studio')
  }));
  root.classList.add('bi-studio');
  body.forEach((n) => shell.visual.appendChild(n));

  /** Where an item opens in the Studio. */
  S.href = function (i) {
    if (i.kind === 'theme') { return '/bi/design/theme/' + B.id2path(i.id); }
    if (i.kind === 'model' || i.kind === 'table') { return '/build/tables?kind=' + i.kind + '&item=' + B.id2path(i.id); }
    if (i.kind === 'dataset') { return '/bi/d/' + B.id2path(i.id); }
    if (i.kind === 'report') { return '/bi/design/' + B.id2path(i.id); }
    return '/bi/i/' + B.id2path(i.id);
  };
  const kindOf = (i) => (i.kind === 'report' && i.board ? 'board' : i.kind);

  // ---- the Explorer: the shell's objects, a link each ------------------------------------------------------------------------------
  let items = [];
  function drawExplorer() {
    shell.setObjects(KINDS.map(([k, title]) => ({ id: k, label: title, items: items.filter((i) => kindOf(i) === k).map((i) => ({
      id: i.id, label: i.title, detail: i.workspaceName || '', search: i.workspaceName || '', href: S.href(i), current: i.id === S.current,
      badge: i.status !== 'published' ? i.status : '', title: i.title + ' · ' + (i.workspaceName || '') })) })));
  }
  function loadItems() {
    return B.writable().then((wss) => Promise.all(wss.map((w) => B.api('GET', '/workspaces/' + encodeURIComponent(w.personal ? 'me' : w.id) + '/items').then((list) => list.map((i) => Object.assign({ workspaceName: w.name }, i)), () => []))))
      .then((groups) => { items = [].concat.apply([], groups); drawExplorer(); }).catch(() => drawExplorer());
  }
  shell.setRail(read('localStorage', RAIL, !narrow));
  shell.on('rail', (r) => write('localStorage', RAIL, r.open));
  shell.on('layout', (l) => { if (l.user) { write('localStorage', LAYOUT, l.layout); } });

  /** + New: a report (the start page, which asks for its model), a board, a theme, a dataset or a model. */
  const NEW = {
    report: () => { window.location.href = '/bi/designer'; },
    board: () => B.api('POST', '/workspaces/me/items', { kind: 'board', title: 'Board ' + new Date().toISOString().slice(0, 16).replace('T', ' '), description: '' })
      .then((it) => { window.location.href = '/bi/design/' + B.id2path(it.id); }),
    theme: () => { window.location.href = '/bi/design/theme/new'; },
    dataset: () => { window.location.href = '/build/tables?new=table'; },
    model: () => { window.location.href = '/bi/models'; }
  };
  shell.on('new', (n) => { if (NEW[n.id]) { Promise.resolve(NEW[n.id]()).catch((e) => shell.say(e.message, true)); } });

  // ---- the open items above the designer ------------------------------------------------------------------------------------------
  function drawDocs() { shell.setDocs(read('sessionStorage', TABS, []).map((t) => ({ id: t.id, label: t.title, href: t.href, current: t.id === S.current }))); }
  shell.on('close', (c) => {
    const rest = read('sessionStorage', TABS, []).filter((o) => o.id !== c.id);
    write('sessionStorage', TABS, rest);
    if (c.id === S.current && rest.length) { window.location.href = rest[rest.length - 1].href; } else { drawDocs(); }
  });

  /** A designer says which item it holds: it joins the open items and is marked in the Explorer. */
  S.attach = function (item) {
    S.current = item.id;
    const list = read('sessionStorage', TABS, []).filter((o) => o.id !== item.id);
    list.push({ id: item.id, title: item.title, href: window.location.pathname });
    write('sessionStorage', TABS, list.slice(-8));
    drawDocs();
    drawExplorer();
  };

  /** The status beside the name, in the designer's words (Saved, Unsaved changes, Read-only). */
  S.state = function (text, status) { shell.setStatus({ text: text, status: status || 'draft' }); };

  // ---- the terminal's words and keys: the designer's own names for the header's actions ---------------------------------------------
  const ALIAS = { check: 'validate', publish: 'propose', propose: 'propose' };
  shell.on('command', (c) => {
    const w = c.text.toLowerCase().split(/\s+/), b = shell.buttons[ALIAS[w[0]] || ''];
    if (b && w.length === 1) { if (!b.disabled) { b.click(); } else { shell.say(b.title, true); } return; }
    shell.say('Not a command here: ' + c.text + ' (check, save, publish, undo, redo, preview, layout studio).', true);
  });
  shell.on('mode', (m) => { const t = root.querySelector('[data-tab="' + (m.mode === 'yaml' ? 'yaml' : 'canvas') + '"]'); if (t) { t.click(); } });     // F4: the designer's YAML tab

  // ---- the dock: Problems and Query ---------------------------------------------------------------------------------------------
  shell.showDock(read('localStorage', DOCK, '') || null);
  shell.on('dock', (d) => write('localStorage', DOCK, d.id || ''));
  /** The Problems of the draft: the list the designer's Check makes, with the count on the tab. */
  S.problems = function (list, draw) {
    const pane = shell.dockPane('problems');
    if (!pane) { return; }
    B.clear(pane);
    const errors = list.filter((p) => p.level === 'error').length, warnings = list.length - errors;
    shell.setDockCount('problems', list.length, errors > 0);
    if (!list.length) { pane.appendChild(B.h('p', { class: 'text-muted-d', 'data-dock-clean': '', text: 'No problems: the draft is valid.' })); return; }
    pane.appendChild(B.h('p', { class: 'text-muted-d', text: errors + ' error' + (errors === 1 ? '' : 's') + ', ' + warnings + ' warning' + (warnings === 1 ? '' : 's') + '.' }));
    const ul = B.h('ul', { class: 'bi-warn bi-dock-list' });
    pane.appendChild(ul);
    draw(list, ul);
  };
  /** The Query pane's host, for the designer's query panel. */
  S.queryHost = () => shell.dockPane('query');

  loadItems();
  drawDocs();
})();
