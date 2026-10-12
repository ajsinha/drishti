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
/* The board designer (docs/guides/BOARDS.md, "Designing a board"): a grid of tiles taken from reports, pages, the kiosk rotation, a threshold alert (with hysteresis
   and notification) for each tile, and the board's YAML as a second view of the same draft. The server parses and writes the board (POST /boards/validate and
   /boards/format), so what the YAML tab shows is what is saved; Save stores a draft, and publishing goes through the board's item page (review, impact, audit).
   Every action also has a button or a field, so nothing needs the mouse. Same-origin JSON only. Also the "Boards" part of the Designer start page. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'designer') { return; }
  const B = window.drishtiBi;
  const h = B.h, q = (s, sc) => B.q(s, sc || canvas), qa = (s, sc) => B.qa(s, sc || canvas);
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);

  // ---- the Designer start page: board drafts and a new board ---------------------------------------------------------------------------
  const start = q('[data-board-start]');
  if (start) {
    const list = q('[data-board-drafts]');
    B.api('GET', '/workspaces/me/items').then((items) => {
      items.filter((i) => i.kind === 'board').forEach((i) => list.appendChild(h('li', { 'data-id': i.id }, h('a', { href: '/bi/design/board/' + B.id2path(i.id), text: i.title }), ' ', h('span', { class: 'text-muted-d', text: i.status }))));
      q('[data-no-board-drafts]').hidden = list.children.length > 0;
    }).catch((e) => say(e.message, true));
    q('[data-new-board]').addEventListener('submit', (e) => {
      e.preventDefault();
      const title = q('[data-new-board-title]').value.trim() || 'My board';
      const body = 'rachana: 1\nboard: ' + (B.slug(title) || 'my-board') + '\ntitle: ' + JSON.stringify(title) + '\npages:\n  - id: page1\n    title: Page 1\n    tiles: []\n';
      B.api('POST', '/workspaces/me/items', { kind: 'board', title, description: '', body }).then((it) => { window.location.href = '/bi/design/board/' + B.id2path(it.id); }).catch((err) => say(err.message, true));
    });
    return;
  }

  const root = q('[data-board-design-root]');
  if (!root) { return; }
  const shell = canvas.closest('.bi-shell');
  if (shell) { shell.classList.add('bi-shell-design'); }
  const itemId = canvas.getAttribute('data-item');

  // ---- the draft and its history -------------------------------------------------------------------------------------------------------
  const S = { model: null, pageId: null, selected: null, dirty: false, readOnly: false, resolved: {}, problems: [], yaml: '' };
  const undo = [], redo = [];
  const reportCache = new Map();                       // report item id -> its pages and visuals
  const ROW = B.ROW_PX + B.GAP_PX;

  const page = () => S.model.pages.find((p) => p.id === S.pageId) || S.model.pages[0];
  const tile = (id) => page().tiles.find((t) => t.id === id) || null;
  const info = (t) => S.resolved[page().id + '/' + t.id] || {};

  function change(label, fn) {
    if (S.readOnly) { say('This is shipped content: save a copy to change it.', true); return false; }
    const before = JSON.stringify(S.model);
    fn(S.model);
    if (before === JSON.stringify(S.model)) { return false; }
    undo.push(before);
    if (undo.length > 100) { undo.shift(); }
    redo.length = 0;
    S.dirty = true;
    changed();
    return true;
  }
  function changed() {
    if (!S.model.pages.some((p) => p.id === S.pageId)) { S.pageId = S.model.pages[0].id; }
    if (S.selected && !tile(S.selected)) { S.selected = null; }
    render();
    scheduleCheck();
  }
  function stepHistory(from, to, word) {
    if (!from.length) { return; }
    to.push(JSON.stringify(S.model));
    S.model = JSON.parse(from.pop());
    S.dirty = true;
    changed();
    say(word + '.');
  }

  /** Moves others down so nothing overlaps `moved` (and, in turn, each other). */
  function settle(p, moved) {
    const hit = (a, b) => a.at.x < b.at.x + b.at.w && b.at.x < a.at.x + a.at.w && a.at.y < b.at.y + b.at.h && b.at.y < a.at.y + a.at.h;
    const queue = [moved];
    let guard = 0;
    while (queue.length && guard++ < 500) {
      const a = queue.shift();
      p.tiles.filter((b) => b !== a && hit(a, b)).sort((x, y) => x.at.y - y.at.y).forEach((b) => { b.at.y = a.at.y + a.at.h; queue.push(b); });
    }
  }
  const freeY = (p) => p.tiles.reduce((m, t) => Math.max(m, t.at.y + t.at.h), 0);
  const newId = (base, used) => { let n = 1; while (used.has(base + '-' + n)) { n++; } return base + '-' + n; };

  // ---- the server: write the YAML, check the board -------------------------------------------------------------------------------------
  let checkTimer = 0, textTimer = 0, ignoreText = false, editor = null;
  const ta = q('[data-yaml]');
  const yamlText = () => (editor ? editor.getValue() : ta.value);
  function setYaml(text) { ignoreText = true; if (editor) { editor.setValue(text); } else { ta.value = text; } ignoreText = false; S.yaml = text; }

  function scheduleCheck() {
    clearTimeout(checkTimer);
    checkTimer = setTimeout(refresh, 300);
    updateButtons();
  }
  /** The model as YAML (the server writes it), then that YAML checked: the problems, and what each tile resolves to. */
  function refresh() {
    return B.api('POST', '/boards/format', { board: S.model }).then((r) => {
      setYaml(r.yaml);
      return B.api('POST', '/boards/validate', { yaml: r.yaml });
    }).then((r) => {
      S.resolved = r.tiles || {};
      S.problems = r.problems || [];
      render();
      showProblems(q('[data-yaml-problems]'), S.problems);
      return S.yaml;
    }).catch((e) => { say(e.message, true); return S.yaml; });
  }
  const flush = () => { clearTimeout(checkTimer); return refresh(); };

  function onYamlEdit() {
    if (ignoreText) { return; }
    clearTimeout(textTimer);
    textTimer = setTimeout(() => {
      const text = yamlText();
      B.api('POST', '/boards/validate', { yaml: text }).then((r) => {
        showProblems(q('[data-yaml-problems]'), r.problems || []);
        if (r.board && (r.problems || []).every((p) => p.level !== 'error' || /tiles\[|report|visual/.test(p.path || ''))) {
          if (!B.same(r.board, S.model)) {
            undo.push(JSON.stringify(S.model));
            redo.length = 0;
            S.model = r.board;
            S.dirty = true;
            S.yaml = text;
            S.resolved = r.tiles || {};
            S.problems = r.problems || [];
            if (!S.model.pages.some((p) => p.id === S.pageId)) { S.pageId = S.model.pages[0].id; }
            render();
            updateButtons();
          }
        }
      }).catch((e) => say(e.message, true));
    }, 400);
  }

  function showProblems(list, problems) {
    B.clear(list);
    problems.forEach((p) => list.appendChild(h('li', { class: 'bi-problem bi-problem-' + (p.level === 'error' ? 'error' : 'warning') },
      h('strong', { text: (p.level || 'error').toUpperCase() + ' ' }), (p.path ? p.path + ': ' : '') + p.message)));
    list.hidden = !problems.length;
  }

  // ---- drawing -------------------------------------------------------------------------------------------------------------------------
  const frames = new Map();
  const grid = q('[data-board-grid]');
  const TYPE_ICON = { card: 'hash', kpi: 'speedometer2', 'multirow-card': 'list-ol', table: 'table', matrix: 'grid-3x3', bar: 'bar-chart', column: 'bar-chart-fill', line: 'graph-up', area: 'graph-up',
    pie: 'pie-chart', donut: 'pie-chart', gauge: 'speedometer', map: 'globe2', 'live-chart': 'activity', 'perspective-grid': 'table', 'perspective-pivot': 'table' };

  function place(el, at) {
    el.style.gridColumn = (at.x + 1) + ' / span ' + at.w;
    el.style.gridRow = (at.y + 1) + ' / span ' + at.h;
  }
  function alertWords(a) {
    if (!a) { return ''; }
    const rule = (r) => r ? (r.op + ' ' + r.value) : '';
    return [a.on ? 'on ' + a.on : '', a.warn ? 'amber ' + rule(a.warn) : '', a.crit ? 'red ' + rule(a.crit) : '', a.hysteresis ? 'hysteresis ' + a.hysteresis : '', a.notify ? 'tells people' : ''].filter(Boolean).join(' · ');
  }

  function makeTile(t) {
    const del = h('button', { type: 'button', class: 'bi-vis-btn', 'aria-label': 'Remove this tile', title: 'Remove', 'data-act': 'delete' }, B.icon('trash'));
    const head = h('div', { class: 'bi-btile-head bi-dhandle' }, h('strong', { class: 'bi-btile-title', 'data-tile-title': '' }), del);
    const body = h('div', { class: 'bi-btile-body' });
    const rs = h('div', { class: 'bi-resize', 'aria-hidden': 'true', 'data-resize': '' });
    const el = h('div', { class: 'bi-btile', tabindex: '0', 'data-tile': t.id }, head, body, rs);
    del.addEventListener('click', (e) => { e.stopPropagation(); removeTile(t.id); });
    el.addEventListener('click', () => select(t.id));
    el.addEventListener('focus', () => select(t.id));
    el.addEventListener('keydown', (e) => keys(t.id, e));
    drag(el, head, t.id, 'move');
    drag(el, rs, t.id, 'resize');
    return { el, head, body };
  }

  function drawTile(t, f) {
    const i = info(t);
    const missing = !i.report;
    f.el.classList.toggle('bi-btile-bad', missing && Object.keys(S.resolved).length > 0);
    f.el.classList.toggle('bi-selected', S.selected === t.id);
    f.el.querySelector('[data-tile-title]').textContent = t.title || t.visual;
    B.clear(f.body).appendChild(h('div', { class: 'bi-btile-sub' }, B.icon(TYPE_ICON[i.type] || 'bar-chart'), ' ', i.reportTitle ? i.reportTitle + ' · ' + t.visual + (i.type ? ' (' + i.type + ')' : '') : (t.report + ' · ' + t.visual)));
    if (missing && Object.keys(S.resolved).length > 0) { f.body.appendChild(h('div', { class: 'bi-btile-warn', role: 'status' }, B.icon('exclamation-triangle'), ' This tile names a report or visual you cannot open.')); }
    if (t.alert) { f.body.appendChild(h('div', { class: 'bi-btile-alert' }, B.icon('bell'), ' ' + alertWords(t.alert))); }
    f.el.setAttribute('aria-label', (t.title || t.visual) + ', column ' + (t.at.x + 1) + ' row ' + (t.at.y + 1) + ', ' + t.at.w + ' wide, ' + t.at.h + ' high. Arrow keys move it, Shift with arrows resizes it, Delete removes it.');
    place(f.el, t.at);
  }

  function render() {
    if (!S.model) { return; }
    const p = page();
    // header and settings
    q('[data-title]').textContent = S.model.title || 'Board';
    const state = q('[data-state]');
    state.textContent = S.readOnly ? 'Shipped, read-only' : S.dirty ? 'Unsaved changes' : 'Saved';
    if (document.activeElement !== q('[data-b-title]')) { q('[data-b-title]').value = S.model.title || ''; }
    if (document.activeElement !== q('[data-b-desc]')) { q('[data-b-desc]').value = S.model.description || ''; }
    if (document.activeElement !== q('[data-b-rotate]')) { q('[data-b-rotate]').value = S.model.rotate ? parseInt(S.model.rotate, 10) || '' : ''; }
    // page tabs
    const tabs = B.clear(q('[data-ptabs]'));
    S.model.pages.forEach((pg) => tabs.appendChild(h('button', { type: 'button', class: 'bi-tab', role: 'tab', 'aria-selected': pg.id === p.id ? 'true' : 'false', 'data-page': pg.id, text: pg.title || pg.id })));
    tabs.appendChild(h('button', { type: 'button', class: 'bi-tab bi-tab-add', 'aria-label': 'Add a page', title: 'Add a page', 'data-page-add': '' }, B.icon('plus')));
    if (document.activeElement !== q('[data-p-title]')) { q('[data-p-title]').value = p.title || ''; }
    q('[data-p-delete]').disabled = S.model.pages.length < 2 || S.readOnly;
    q('[data-p-left]').disabled = S.model.pages.indexOf(p) < 1;
    q('[data-p-right]').disabled = S.model.pages.indexOf(p) >= S.model.pages.length - 1;
    // tiles
    const ids = new Set(p.tiles.map((t) => t.id));
    frames.forEach((f, id) => { if (!ids.has(id) || f.page !== p.id) { f.el.remove(); frames.delete(id); } });
    p.tiles.slice().sort((a, b) => a.at.y - b.at.y || a.at.x - b.at.x).forEach((t) => {
      let f = frames.get(t.id);
      if (!f) { f = Object.assign(makeTile(t), { page: p.id }); frames.set(t.id, f); grid.appendChild(f.el); }
      drawTile(t, f);
    });
    q('[data-board-empty]').hidden = p.tiles.length > 0;
    renderTileForm();
    q('[data-problem-count]').textContent = S.problems.filter((x) => x.level === 'error').length || '';
    updateButtons();
  }

  function updateButtons() {
    q('[data-undo]').disabled = !undo.length;
    q('[data-redo]').disabled = !redo.length;
    q('[data-save-copy]').hidden = !S.readOnly;
    q('[data-save]').disabled = S.readOnly;
  }

  // ---- selecting, moving, resizing -----------------------------------------------------------------------------------------------------
  function select(id) {
    if (S.selected === id) { return; }
    S.selected = id;
    frames.forEach((f, k) => f.el.classList.toggle('bi-selected', k === id));
    renderTileForm();
  }
  function removeTile(id) {
    change('Remove a tile', (m) => { const p = m.pages.find((x) => x.id === S.pageId); p.tiles = p.tiles.filter((t) => t.id !== id); });
    S.selected = null;
    render();
    say('Tile removed. Undo brings it back.');
  }
  function move(id, dx, dy, dw, dh) {
    change('Move a tile', (m) => {
      const p = m.pages.find((x) => x.id === S.pageId);
      const t = p.tiles.find((x) => x.id === id);
      t.at.w = Math.max(1, Math.min(12, t.at.w + dw));
      t.at.h = Math.max(1, Math.min(40, t.at.h + dh));
      t.at.x = Math.max(0, Math.min(12 - t.at.w, t.at.x + dx));
      t.at.y = Math.max(0, t.at.y + dy);
      settle(p, t);
    });
  }
  function keys(id, e) {
    if (e.target !== e.currentTarget) { return; }
    const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
    if (step) {
      e.preventDefault();
      if (e.shiftKey) { move(id, 0, 0, step[0], step[1]); } else { move(id, step[0], step[1], 0, 0); }
      const t = tile(id);
      say(e.shiftKey ? 'Resized to ' + t.at.w + ' by ' + t.at.h + '.' : 'Moved to column ' + (t.at.x + 1) + ', row ' + (t.at.y + 1) + '.');
      const f = frames.get(id);
      if (f) { f.el.focus(); }
    } else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); removeTile(id); }
  }
  function drag(el, handle, id, mode) {
    handle.addEventListener('pointerdown', (e) => {
      if (e.button !== 0 || e.target.closest('button')) { return; }
      const t = tile(id);
      if (!t || S.readOnly) { return; }
      e.preventDefault();
      select(id);
      const w = grid.clientWidth, col = (w - 11 * B.GAP_PX) / 12, stepX = col + B.GAP_PX;
      const from = { x: e.clientX, y: e.clientY, at: Object.assign({}, t.at) };
      let at = Object.assign({}, t.at);
      handle.setPointerCapture(e.pointerId);
      el.classList.add('bi-dragging');
      const onMove = (ev) => {
        const cx = Math.round((ev.clientX - from.x) / stepX), cy = Math.round((ev.clientY - from.y) / ROW);
        at = mode === 'move' ? { x: Math.max(0, Math.min(12 - from.at.w, from.at.x + cx)), y: Math.max(0, from.at.y + cy), w: from.at.w, h: from.at.h }
          : { x: from.at.x, y: from.at.y, w: Math.max(1, Math.min(12 - from.at.x, from.at.w + cx)), h: Math.max(1, Math.min(40, from.at.h + cy)) };
        place(el, at);
      };
      const onUp = () => {
        handle.removeEventListener('pointermove', onMove);
        handle.removeEventListener('pointerup', onUp);
        handle.removeEventListener('pointercancel', onUp);
        el.classList.remove('bi-dragging');
        if (!B.same(at, from.at)) {
          change(mode === 'move' ? 'Move a tile' : 'Resize a tile', (m) => { const p = m.pages.find((x) => x.id === S.pageId); const live = p.tiles.find((x) => x.id === id); live.at = at; settle(p, live); });
        } else { place(el, from.at); }
      };
      handle.addEventListener('pointermove', onMove);
      handle.addEventListener('pointerup', onUp);
      handle.addEventListener('pointercancel', onUp);
    });
  }

  // ---- the selected tile: title, place, alert ------------------------------------------------------------------------------------------
  const OPS = ['lt', 'le', 'gt', 'ge', 'eq', 'ne'];
  const OP_WORDS = { lt: 'is below', le: 'is at most', gt: 'is above', ge: 'is at least', eq: 'equals', ne: 'is not' };
  function renderTileForm() {
    const box = B.clear(q('[data-tile-form]'));
    const t = S.selected ? tile(S.selected) : null;
    q('[data-tile-none]').hidden = !!t;
    if (!t) { return; }
    const a = t.alert || {};
    const num = (name, label, value, min, max) => h('label', {}, label, h('input', { class: 'studio-in', type: 'number', name, value: value == null ? '' : value, min, max, step: 1, inputmode: 'numeric' }));
    const opSel = (name, label, rule) => h('label', {}, label, h('select', { class: 'studio-select', name }, h('option', { value: '', text: 'none' }), OPS.map((o) => h('option', { value: o, text: OP_WORDS[o], selected: rule && rule.op === o }))));
    const ruleValue = (name, label, rule) => h('label', {}, label, h('input', { class: 'studio-in', type: 'number', name, value: rule ? rule.value : '', step: 'any', inputmode: 'decimal' }));
    const form = h('form', { autocomplete: 'off', 'data-tile-edit': '' },
      h('p', { class: 'text-muted-d', text: (info(t).reportTitle || t.report) + ' · ' + t.visual }),
      h('label', {}, 'Title', h('input', { class: 'studio-in', name: 'title', value: t.title || '', maxlength: 120, placeholder: 'The visual\'s own title' })),
      h('fieldset', { class: 'bi-fs' }, h('legend', { text: 'Place on the grid (12 columns)' }),
        num('x', 'Column', t.at.x + 1, 1, 12), num('y', 'Row', t.at.y + 1, 1, 400), num('w', 'Wide', t.at.w, 1, 12), num('h', 'High', t.at.h, 1, 40)),
      h('fieldset', { class: 'bi-fs' }, h('legend', { text: 'Alert: colour the tile when its number crosses a threshold' }),
        h('label', {}, 'Number it watches', h('input', { class: 'studio-in', name: 'on', value: a.on || '', placeholder: 'a measure of the visual (blank: its first number)' })),
        opSel('warn_op', 'Amber when the number', a.warn), ruleValue('warn_value', 'Amber threshold', a.warn),
        opSel('crit_op', 'Red when the number', a.crit), ruleValue('crit_value', 'Red threshold', a.crit),
        h('label', {}, 'Hysteresis', h('input', { class: 'studio-in', type: 'number', name: 'hysteresis', min: 0, step: 'any', value: a.hysteresis || '', inputmode: 'decimal' })),
        h('p', { class: 'text-muted-d', text: 'How far back inside a threshold the number must be before the tile drops a level, so a number hovering at the edge does not flip the colour. Blank: none.' }),
        h('label', { class: 'bi-checkrow' }, h('input', { type: 'checkbox', name: 'notify', checked: !!a.notify }), ' Tell the people who read the board when the tile turns'),
        h('label', {}, 'Tell them when it is', h('select', { class: 'studio-select', name: 'notify_level' }, h('option', { value: 'crit', text: 'red', selected: !a.notify || a.notify.level !== 'warn' }), h('option', { value: 'warn', text: 'amber or red', selected: !!a.notify && a.notify.level === 'warn' })))),
      h('p', { class: 'bi-dactions' }, h('button', { type: 'submit', class: 'btn-pill btn-accent', text: 'Apply to the tile' }), h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-alert-clear': '', text: 'Remove the alert' })));
    form.addEventListener('submit', (e) => { e.preventDefault(); applyTileForm(t.id, new FormData(form)); });
    form.querySelector('[data-alert-clear]').addEventListener('click', () => { change('Remove the alert', (m) => { delete m.pages.find((x) => x.id === S.pageId).tiles.find((x) => x.id === t.id).alert; }); say('Alert removed.'); });
    box.appendChild(form);
  }
  function rule(op, value) {
    if (!op || value === '' || value == null || !isFinite(Number(value))) { return null; }
    return { op, value: Number(value) };
  }
  function applyTileForm(id, fd) {
    const num = (k, lo, hi) => Math.max(lo, Math.min(hi, parseInt(fd.get(k), 10) || lo));
    const warn = rule(fd.get('warn_op'), fd.get('warn_value')), crit = rule(fd.get('crit_op'), fd.get('crit_value'));
    const hy = parseFloat(fd.get('hysteresis'));
    if (!warn && !crit && (fd.get('on') || fd.get('notify'))) { say('An alert needs an amber or a red threshold: choose when and a number.', true); return; }
    change('Edit a tile', (m) => {
      const p = m.pages.find((x) => x.id === S.pageId);
      const t = p.tiles.find((x) => x.id === id);
      const title = String(fd.get('title') || '').trim();
      if (title) { t.title = title; } else { delete t.title; }
      const w = num('w', 1, 12);
      t.at = { x: Math.min(num('x', 1, 12) - 1, 12 - w), y: num('y', 1, 400) - 1, w, h: num('h', 1, 40) };
      if (warn || crit) {
        const al = {};
        if (String(fd.get('on') || '').trim()) { al.on = String(fd.get('on')).trim(); }
        if (warn) { al.warn = warn; }
        if (crit) { al.crit = crit; }
        if (isFinite(hy) && hy > 0) { al.hysteresis = hy; }
        if (fd.get('notify')) { al.notify = Object.assign({}, t.alert && t.alert.notify, { level: fd.get('notify_level') === 'warn' ? 'warn' : 'crit', channels: (t.alert && t.alert.notify && t.alert.notify.channels) || ['inbox'] }); }
        t.alert = al;
      } else { delete t.alert; }
      settle(p, t);
    });
    say('Tile updated.');
  }

  // ---- adding tiles ------------------------------------------------------------------------------------------------------------------
  const repSel = q('[data-add-report]'), visSel = q('[data-add-visual]');
  const SIZE = { card: [3, 3], kpi: [3, 3], 'multirow-card': [4, 4], slicer: [3, 3], 'perspective-grid': [12, 8], 'perspective-pivot': [12, 8] };
  function loadReport(id) {
    B.clear(visSel);
    if (!id) { return; }
    const done = (r) => {
      (r.pages || []).forEach((pg) => (pg.visuals || []).filter((v) => v.type !== 'slicer').forEach((v) => visSel.appendChild(h('option', { value: pg.id + '/' + v.id, 'data-type': v.type, text: (pg.title || pg.id) + ' · ' + (v.title || v.id) + ' (' + v.type + ')' }))));
      q('[data-add-btn]').disabled = !visSel.options.length;
    };
    if (reportCache.has(id)) { done(reportCache.get(id)); return; }
    B.api('GET', '/reports/' + B.id2path(id)).then((r) => { const rep = r.report || {}; reportCache.set(id, rep); done(rep); }).catch((e) => say(e.message, true));
  }
  repSel.addEventListener('change', () => loadReport(repSel.value));
  q('[data-add-form]').addEventListener('submit', (e) => {
    e.preventDefault();
    if (!repSel.value || !visSel.value) { say('Choose a report and one of its visuals.', true); return; }
    const [pg, vis] = visSel.value.split('/');
    const type = visSel.selectedOptions[0].getAttribute('data-type');
    const [w, hh] = SIZE[type] || [6, 5];
    let made = null;
    change('Add a tile', (m) => {
      const p = m.pages.find((x) => x.id === S.pageId);
      made = { id: newId(B.slug(vis) || 'tile', new Set(p.tiles.map((t) => t.id))), report: repSel.value, visual: vis, at: { x: 0, y: freeY(p), w, h: hh } };
      const onPages = (reportCache.get(repSel.value).pages || []).filter((x) => (x.visuals || []).some((v) => v.id === vis)).length;
      if (onPages > 1) { made.page = pg; }                      // the same visual id on two pages of the report: say which
      p.tiles.push(made);
    });
    if (made) { S.selected = made.id; render(); const f = frames.get(made.id); if (f) { f.el.focus(); } say('Tile added at the bottom of the page.'); }
  });

  // ---- board and page settings, tabs -------------------------------------------------------------------------------------------------
  q('[data-b-title]').addEventListener('change', (e) => change('Title', (m) => { m.title = e.target.value.trim() || m.board; }));
  q('[data-b-desc]').addEventListener('change', (e) => change('Description', (m) => { if (e.target.value.trim()) { m.description = e.target.value.trim(); } else { delete m.description; } }));
  q('[data-b-rotate]').addEventListener('change', (e) => {
    const n = parseInt(e.target.value, 10);
    if (e.target.value && (!(n >= 5) || n > 3600)) { say('Turn the pages every 5 to 3600 seconds, or leave it blank for no turning.', true); return; }
    change('Page turning', (m) => { if (n) { m.rotate = n + 's'; } else { delete m.rotate; } });
  });
  q('[data-p-title]').addEventListener('change', (e) => change('Page title', (m) => { m.pages.find((x) => x.id === S.pageId).title = e.target.value.trim() || S.pageId; }));
  q('[data-ptabs]').addEventListener('click', (e) => {
    const t = e.target.closest('button');
    if (!t) { return; }
    if (t.hasAttribute('data-page-add')) {
      if (S.model.pages.length >= 20) { say('A board holds up to 20 pages.', true); return; }
      let id;
      change('Add a page', (m) => { id = newId('page', new Set(m.pages.map((x) => x.id))); m.pages.push({ id, title: 'Page ' + (m.pages.length + 1), tiles: [] }); });
      if (id) { S.pageId = id; S.selected = null; render(); }
    } else if (t.hasAttribute('data-page')) { S.pageId = t.getAttribute('data-page'); S.selected = null; frames.forEach((f) => f.el.remove()); frames.clear(); render(); }
  });
  q('[data-p-delete]').addEventListener('click', () => {
    const was = S.pageId;
    change('Delete a page', (m) => { m.pages = m.pages.filter((x) => x.id !== was); });
    say('Page deleted. Undo brings it back.');
  });
  [['[data-p-left]', -1], ['[data-p-right]', 1]].forEach(([sel, d]) => q(sel).addEventListener('click', () => change('Move a page', (m) => {
    const i = m.pages.findIndex((x) => x.id === S.pageId), j = i + d;
    if (j >= 0 && j < m.pages.length) { const [pg] = m.pages.splice(i, 1); m.pages.splice(j, 0, pg); }
  })));

  const showTab = (name) => {
    qa('[data-tab]').forEach((b) => b.setAttribute('aria-selected', b.getAttribute('data-tab') === name ? 'true' : 'false'));
    qa('[data-view]').forEach((v) => { v.hidden = v.getAttribute('data-view') !== name; });
    if (name === 'yaml') { flush().then(() => { if (editor) { editor.refresh(); editor.focus(); } else { ta.focus(); } }); }
  };
  qa('[data-tab]').forEach((b) => b.addEventListener('click', () => showTab(b.getAttribute('data-tab'))));

  // ---- toolbar -----------------------------------------------------------------------------------------------------------------------
  q('[data-undo]').addEventListener('click', () => stepHistory(undo, redo, 'Undone'));
  q('[data-redo]').addEventListener('click', () => stepHistory(redo, undo, 'Redone'));
  q('[data-check]').addEventListener('click', () => flush().then(() => {
    const box = q('[data-check-box]');
    box.hidden = false;
    showProblems(q('[data-check-list]'), S.problems);
    const errors = S.problems.filter((p) => p.level === 'error').length;
    q('[data-check-sum]').textContent = S.problems.length ? errors + ' error' + (errors === 1 ? '' : 's') + ', ' + (S.problems.length - errors) + ' warning' + (S.problems.length - errors === 1 ? '' : 's') + '.' : 'No problems: every tile names a report and visual you can open.';
    q('[data-check-sum]').focus();
  }));
  q('[data-check-close]').addEventListener('click', () => { q('[data-check-box]').hidden = true; });
  function save() {
    if (S.readOnly) { return; }
    flush().then((yaml) => B.api('PUT', '/items/' + B.id2path(itemId), { body: yaml })).then(() => {
      S.dirty = false;
      render();
      say('Saved as a draft at ' + new Date().toTimeString().slice(0, 8) + '. Publish it from its item page when it is ready.');
    }).catch((e) => say(e.message, true));
  }
  q('[data-save]').addEventListener('click', save);
  q('[data-save-copy]').addEventListener('click', () => flush().then((yaml) => B.api('POST', '/workspaces/me/items', { kind: 'board', title: (S.model.title || 'Board') + ' (copy)', description: S.model.description || '', body: yaml }))
    .then((it) => { window.location.href = '/bi/design/board/' + B.id2path(it.id); }).catch((e) => say(e.message, true)));
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); save(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !(e.target.closest && e.target.closest('textarea, .CodeMirror'))) { e.preventDefault(); stepHistory(undo, redo, 'Undone'); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y' && !(e.target.closest && e.target.closest('textarea, .CodeMirror'))) { e.preventDefault(); stepHistory(redo, undo, 'Redone'); }
  });
  window.addEventListener('beforeunload', (e) => { if (S.dirty) { e.preventDefault(); e.returnValue = ''; } });

  // ---- start ---------------------------------------------------------------------------------------------------------------------------
  if (window.CodeMirror && window.CodeMirror.modes && window.CodeMirror.modes['rachana-yaml']) {
    editor = window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, lineWrapping: true, indentUnit: 2, tabSize: 2, viewportMargin: Infinity });
    editor.on('change', onYamlEdit);
  } else { ta.addEventListener('input', onYamlEdit); }

  Promise.all([B.api('GET', '/items/' + B.id2path(itemId)), B.api('GET', '/catalogue?kind=report&limit=500')]).then(([item, cat]) => {
    if (item.kind !== 'board') { say('This is not a board.', true); return null; }
    S.readOnly = !(item.can && item.can.edit);
    q('[data-view-link]').href = '/bi/b/' + B.id2path(itemId);
    q('[data-item-link]').href = '/bi/i/' + B.id2path(itemId);
    (cat.items || []).forEach((i) => repSel.appendChild(h('option', { value: i.id, text: i.title })));
    q('[data-readonly]').hidden = !S.readOnly;
    return B.api('POST', '/boards/validate', { yaml: item.body });
  }).then((r) => {
    if (!r) { return; }
    S.problems = r.problems || [];
    S.resolved = r.tiles || {};
    if (!r.board) { showProblems(q('[data-yaml-problems]'), S.problems); showTab('yaml'); setYaml(''); say('The YAML does not parse; fix it in the YAML tab.', true); return; }
    S.model = r.board;
    S.pageId = S.model.pages[0].id;
    root.setAttribute('data-ready', '1');
    render();
    refresh();
    say(S.readOnly ? 'Shipped board: look, try changes and check them; Save a copy makes a draft of your own.' : 'Ready.');
  }).catch((e) => say(e.message, true));
})();
