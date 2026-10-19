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
/* BI Studio, the one shell of the designers (docs/architecture/BI_DESIGN.md section 8.1, 2.4.0): on the left the Explorer, the items of the workspaces you
   may edit (reports, boards, models, datasets, themes, Python items) with a search and New; on top the tabs of the items you have open in this browser tab
   (kept for the session: a per-person convenience, never the work itself, which the server saves); on the right the designer's own properties; at the
   bottom the dock, with Problems (what the server's Check says about the draft, each with a link to the visual) and Query (the selected visual's model
   query and SQL). A designer page opts in with `data-studio` on its root and tells the shell what it holds (`window.drishtiBi.studio.attach`). */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const root = document.querySelector('[data-studio]');
  if (!B || !root) { return; }
  const shell = root.closest('.bi-shell');
  if (shell) { shell.classList.add('bi-shell-design'); }                     // the Studio uses the whole width: the BI rail steps aside
  const S = (B.studio = B.studio || {});
  const KEY = 'drishti.bi.studio.tabs';
  const RAIL = 'drishti.bi.studio.rail';
  const KINDS = [['report', 'Reports', 'file-earmark-bar-graph'], ['board', 'Boards', 'easel'], ['model', 'Models', 'boxes'], ['dataset', 'Datasets', 'hdd-stack'],
    ['theme', 'Themes', 'palette2'], ['python', 'Python', 'code-slash']];
  const read = (k, d) => { try { const v = window.sessionStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } };
  const write = (k, v) => { try { window.sessionStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* not kept: a private window */ } };

  /** Where an item opens in the Studio. */
  S.href = function (i) {
    if (i.kind === 'theme') { return '/bi/design/theme/' + B.id2path(i.id); }
    if (i.kind === 'model') { return '/bi/md/' + B.id2path(i.id); }
    if (i.kind === 'dataset') { return '/bi/d/' + B.id2path(i.id); }
    if (i.kind === 'report') { return '/bi/design/' + B.id2path(i.id); }
    return '/bi/i/' + B.id2path(i.id);
  };
  const kindOf = (i) => (i.kind === 'report' && i.board ? 'board' : i.kind);

  // ---- the Explorer ---------------------------------------------------------------------------------------------------------------
  const rail = B.q('[data-studio-rail]', root);
  const tree = B.q('[data-studio-tree]', root);
  const search = B.q('[data-studio-search]', root);
  let items = [];
  function drawTree() {
    if (!tree) { return; }
    B.clear(tree);
    const t = (search && search.value || '').trim().toLowerCase();
    KINDS.forEach(([kind, title, icon]) => {
      const list = items.filter((i) => kindOf(i) === kind && (!t || (i.title + ' ' + (i.workspaceName || '')).toLowerCase().indexOf(t) >= 0));
      if (!list.length) { return; }
      const ul = h('ul', { class: 'bi-studio-list' });
      list.forEach((i) => ul.appendChild(h('li', {}, h('a', { href: S.href(i), class: 'bi-studio-item', 'data-studio-item': i.id, 'aria-current': i.id === S.current ? 'page' : null, title: i.title + ' · ' + (i.workspaceName || '') },
        B.icon(icon), h('span', { text: ' ' + i.title }), i.status !== 'published' ? h('small', { class: 'bi-badge', text: i.status }) : null))));
      tree.appendChild(h('details', { class: 'bi-studio-group', open: true, 'data-studio-group': kind }, h('summary', {}, title, h('small', { class: 'text-muted-d', text: ' ' + list.length })), ul));
    });
    if (!tree.children.length) { tree.appendChild(h('p', { class: 'text-muted-d', text: t ? 'Nothing matches.' : 'No items yet: make one with New.' })); }
  }
  function loadItems() {
    return B.writable().then((wss) => Promise.all(wss.map((w) => B.api('GET', '/workspaces/' + encodeURIComponent(w.personal ? 'me' : w.id) + '/items').then((list) => list.map((i) => Object.assign({ workspaceName: w.name }, i)), () => []))))
      .then((groups) => { items = [].concat.apply([], groups); drawTree(); }).catch(() => drawTree());
  }
  if (search) { search.addEventListener('input', drawTree); }
  const collapse = B.q('[data-studio-collapse]', root);
  const setRail = (open) => {
    root.classList.toggle('bi-studio-railless', !open);
    if (collapse) { collapse.setAttribute('aria-expanded', open ? 'true' : 'false'); collapse.setAttribute('aria-label', open ? 'Hide the Explorer' : 'Show the Explorer'); }
    write(RAIL, open);
  };
  if (collapse) { collapse.addEventListener('click', () => setRail(root.classList.contains('bi-studio-railless'))); }
  setRail(read(RAIL, window.matchMedia && window.matchMedia('(max-width: 900px)').matches ? false : true));

  /** New: a report (the start page, which asks for its model), a board, a theme, a dataset or a model. */
  const NEW = {
    report: () => { window.location.href = '/bi/designer'; },
    board: () => B.api('POST', '/workspaces/me/items', { kind: 'board', title: 'Board ' + new Date().toISOString().slice(0, 16).replace('T', ' '), description: '' })
      .then((it) => { window.location.href = '/bi/design/' + B.id2path(it.id); }),
    theme: () => { window.location.href = '/bi/design/theme/new'; },
    dataset: () => { window.location.href = '/bi/datasets/new'; },
    model: () => { window.location.href = '/bi/models'; }
  };
  const pick = B.q('[data-studio-new]', root);
  if (pick) {
    pick.addEventListener('change', () => {
      const k = pick.value;
      pick.value = '';
      if (NEW[k]) { Promise.resolve(NEW[k]()).catch((e) => B.near(pick, e.message, true)); }
    });
  }

  // ---- the tabs of the open items -------------------------------------------------------------------------------------------------
  const tabsBox = B.q('[data-studio-tabs]', root);
  function drawTabs() {
    if (!tabsBox) { return; }
    B.clear(tabsBox);
    read(KEY, []).forEach((t) => {
      const a = h('a', { href: t.href, class: 'bi-tab bi-studio-tab', role: 'tab', 'data-studio-tab': t.id, 'aria-selected': t.id === S.current ? 'true' : 'false' }, B.icon(t.icon || 'file-earmark'), h('span', { text: ' ' + t.title }));
      const x = h('button', { type: 'button', class: 'bi-chipbtn bi-studio-close', 'aria-label': 'Close ' + t.title, 'data-studio-close': t.id }, B.icon('x-lg'));
      x.addEventListener('click', (e) => {
        e.preventDefault();
        const rest = read(KEY, []).filter((o) => o.id !== t.id);
        write(KEY, rest);
        if (t.id === S.current && rest.length) { window.location.href = rest[rest.length - 1].href; } else { drawTabs(); }
      });
      tabsBox.appendChild(h('span', { class: 'bi-studio-tabwrap' }, a, x));
    });
  }

  /** A designer says which item it holds: it joins the tabs and is marked in the Explorer. */
  S.attach = function (item) {
    S.current = item.id;
    const kind = kindOf(item);
    const icon = (KINDS.find((k) => k[0] === kind) || [])[2];
    const list = read(KEY, []).filter((o) => o.id !== item.id);
    list.push({ id: item.id, title: item.title, href: window.location.pathname, icon });
    write(KEY, list.slice(-8));
    drawTabs();
    drawTree();
  };

  // ---- the dock: Problems and Query ---------------------------------------------------------------------------------------------
  const dock = B.q('[data-studio-dock]', root);
  if (dock) {
    const tabs = B.qa('[data-dock-tab]', dock);
    const show = (name) => {
      tabs.forEach((b) => b.setAttribute('aria-selected', b.getAttribute('data-dock-tab') === name ? 'true' : 'false'));
      B.qa('[data-dock-pane]', dock).forEach((p) => { p.hidden = p.getAttribute('data-dock-pane') !== name; });
      dock.classList.remove('bi-dock-closed');
      write('drishti.bi.studio.dock', name);
    };
    tabs.forEach((b) => b.addEventListener('click', () => (b.getAttribute('aria-selected') === 'true' && !dock.classList.contains('bi-dock-closed') ? dock.classList.add('bi-dock-closed') : show(b.getAttribute('data-dock-tab')))));
    const want = read('drishti.bi.studio.dock', '');
    if (want) { show(want); } else { dock.classList.add('bi-dock-closed'); }
  }
  /** The Problems of the draft: the list the designer's Check makes, with the count on the tab. */
  S.problems = function (list, draw) {
    const pane = dock && B.q('[data-dock-pane="problems"]', dock);
    if (!pane) { return; }
    const box = B.clear(pane);
    const errors = list.filter((p) => p.level === 'error').length, warnings = list.length - errors;
    const count = B.q('[data-dock-count="problems"]', dock);
    if (count) { count.textContent = list.length ? String(list.length) : ''; count.classList.toggle('bi-bad', errors > 0); }
    if (!list.length) { box.appendChild(h('p', { class: 'text-muted-d', 'data-dock-clean': '', text: 'No problems: the draft is valid.' })); return; }
    box.appendChild(h('p', { class: 'text-muted-d', text: errors + ' error' + (errors === 1 ? '' : 's') + ', ' + warnings + ' warning' + (warnings === 1 ? '' : 's') + '.' }));
    const ul = h('ul', { class: 'bi-warn bi-dock-list' });
    box.appendChild(ul);
    draw(list, ul);
  };
  /** The Query pane's host, for the designer's query panel. */
  S.queryHost = () => dock && B.q('[data-dock-pane="query"]', dock);

  loadItems();
  drawTabs();
})();
