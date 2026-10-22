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
/* Concept A, "Edit the live page" (UI_BOARD_GUIDE.md "The designer"): the designer's default view. The canvas is the page itself, rendered with
   live data as the person (the render API, the same stream as the viewer), blocks in reading order at their widths. Hover or select a block:
   an outline and three pills (Swap chip, Its data, Follows…, ui-board-pick.js); the side panel says what the selected block shows, what feeds
   it, what it follows and its fields, in words. "+ Add a block here" zones between the rows and after the last; drag a block by its head to
   reorder it, its right edge to make it half or full width; a phone preview. The engine's tools (pins and wires, the mapping sheet, board
   parameters, YAML / JSON, the board check) wait in the Advanced drawer, closed by default (?advanced=1 opens it); while it is open the pin view
   takes the paint. Views: drishtiUiBoard.view.get(), .set(name), .register(name, {show(host), hide()}) and the ub:view event on the designer. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, h = U.h, clear = U.clear, W = U.words, D = U.designer, C = U.canvas;
  if (!D || !C) { return; }
  const root = D.root, $ = (s) => root.querySelector(s);
  const pageEl = $('[data-ub-page]'), side = $('[data-ub-side]'), adv = $('[data-ub-advanced]'), statusEl = $('[data-ub-page-status]');
  const nameIn = $('[data-ub-name]'), titleIn = $('[data-ub-title]');
  const P = { blocks: {}, bodies: {} };
  let current = null, hoverTimer = 0;

  // ---- views: Page (this file) and the ones that plug in (Wiring) ------------------------------------------------------------------------
  const views = { page: { show() {}, hide() {} } };
  let view = 'page';
  U.view = {
    get: () => view,
    /** Adds a view: its tab in the top bar shows, and set(name) hands it the slot [data-ub-view-slot=name] (made when missing). */
    register(name, impl) {
      views[name] = impl;
      let tab = root.querySelector('[data-ub-view="' + name + '"]');
      if (!tab) {
        tab = h('button', { type: 'button', role: 'tab', class: 'ub-seg', 'data-ub-view': name, 'aria-selected': 'false', text: name.charAt(0).toUpperCase() + name.slice(1) });
        $('[data-ub-views]').appendChild(tab); tab.addEventListener('click', () => U.view.set(name));
      }
      tab.hidden = false;
      if (!root.querySelector('[data-ub-view-slot="' + name + '"]')) { $('[data-ub-pageview]').after(h('div', { class: 'ub-view-slot', 'data-ub-view-slot': name, 'data-mode-only': 'board', hidden: true })); }
    },
    set(name) {
      if (!views[name] && name !== 'page') { return false; }
      if (name === view) { return true; }
      const was = view;
      if (views[was] && views[was].hide) { views[was].hide(); }
      view = name;
      root.setAttribute('data-view', name);
      root.querySelectorAll('[data-ub-view]').forEach((b) => b.setAttribute('aria-selected', String(b.getAttribute('data-ub-view') === name)));
      $('[data-ub-pageview]').hidden = name !== 'page';
      root.querySelectorAll('[data-ub-view-slot]').forEach((s) => { s.hidden = s.getAttribute('data-ub-view-slot') !== name; });
      if (name !== 'page' && views[name].show) { views[name].show(root.querySelector('[data-ub-view-slot="' + name + '"]'), D); }
      root.dispatchEvent(new CustomEvent('ub:view', { detail: { view: name, was } }));
      D.liveRender();
      return true;
    }
  };
  root.querySelectorAll('[data-ub-view]').forEach((b) => b.addEventListener('click', () => U.view.set(b.getAttribute('data-ub-view'))));

  // ---- the Advanced drawer: the pin view takes the paint while it is open ------------------------------------------------------------------
  const pinView = () => adv.open || D.mode === 'chip' || !!D.composite || root.hasAttribute('data-composite');
  if (/[?&]advanced=1\b/.test(location.search) || D.mode === 'chip') { adv.open = true; }
  adv.addEventListener('toggle', () => { if (adv.open) { C.draw(); requestAnimationFrame(C.wires); } D.liveRender(); });
  root.addEventListener('ub:mode', (e) => { if (e.detail === 'chip') { adv.open = true; } });
  new MutationObserver(() => { if (root.hasAttribute('data-composite')) { adv.open = true; } }).observe(root, { attributes: true, attributeFilter: ['data-composite'] });
  $('[data-ub-adv-open]').addEventListener('click', () => { closeMenu(); adv.open = true; adv.scrollIntoView({ block: 'start' }); });

  // ---- the paint: the page view renders the board itself; the pin view keeps the designer's own render -------------------------------------
  const pinRender = D.liveRender;
  /** The board as rendered: blocks with errors of their own leave the render (each says why), so one unfinished block never blanks the page. */
  function renderDoc() {
    const doc = D.docForServer(), bad = new Set(), errs = D.problems.filter((p) => p.level !== 'warning');
    errs.forEach((p) => { const m = /^chips\.([^.[\]]+)/.exec(p.path || ''); if (m) { bad.add(m[1]); } });
    errs.forEach((p) => { const m = /^traces\[(\d+)\]/.exec(p.path || ''); if (m && doc.traces[+m[1]]) { doc.traces[+m[1]].__bad = true; } });
    bad.forEach((n) => { delete doc.chips[n]; });
    doc.traces = (doc.traces || []).filter((t) => !t.__bad && !bad.has(t.from.split('.')[0]) && !bad.has(t.to.split('.')[0]));
    const g = (doc.layout || {}).desktop;
    if (g && g.areas) { g.areas = g.areas.filter((a) => !bad.has(a.chip)); }
    const ph = (doc.layout || {}).phone;
    if (ph) { ph.order = (ph.order || []).filter((n) => !bad.has(n)); ph.collapsed = (ph.collapsed || []).filter((n) => !bad.has(n)); }
    const boardWide = errs.filter((p) => !/^(chips\.|traces\[)/.test(p.path || ''));
    return { doc, bad, boardWide };
  }
  D.liveRender = function () {
    if (current) { current.close(); current = null; }
    if (pinView()) { return pinRender(); }
    if (view !== 'page') { return; }
    const r = renderDoc();
    paintEmpty(r.bad);
    if (r.boardWide.length) { statusEl.textContent = 'The page waits: ' + r.boardWide[0].message; return; }
    if (!Object.keys(r.doc.chips).length || D.missingParams().length) { statusEl.textContent = ''; return; }
    current = U.render(pageEl, { board: r.doc }, { device: D.device, status: statusEl, params: D.params, bodyOf: (inst) => P.blocks[inst] || null });
  };
  window.addEventListener('pagehide', () => { if (current) { current.close(); } });

  // ---- the blocks --------------------------------------------------------------------------------------------------------------------------
  function pill(kind, text, inst) {
    const b = h('button', { type: 'button', class: 'ub-pill' + (kind === 'swap' ? ' ub-pill-solid' : ''), 'data-ub-pill': kind, text });
    b.addEventListener('click', (e) => { e.stopPropagation(); D.select(inst); U.pick.open(kind, inst, b); });
    return b;
  }
  /** What a block says while it cannot show data: one sentence, one action. */
  function emptyOf(inst, bad) {
    const i = D.doc.chips[inst] || {}, fedByTrace = (D.doc.traces || []).some((t) => t.to.split('.')[0] === inst && !/^board\./.test(t.from));
    const act = (text, kind, attrs) => { const b = h('button', Object.assign({ type: 'button', class: 'ub-btn ub-btn-accent', text }, attrs || {})); b.addEventListener('click', (e) => { e.stopPropagation(); D.select(inst); kind(b); }); return b; };
    if (!i.supply && !fedByTrace) { return h('div', { class: 'ub-empty' }, h('p', { text: 'No data yet.' }), act('Choose its data', (b) => U.pick.open('data', inst, b), { 'data-ub-empty-act': 'data' })); }
    const miss = D.missingParams();
    if (miss.length) {
      const p = miss[0], inp = h('input', { class: 'studio-in mono', 'data-ub-param-quick': p, placeholder: p, 'aria-label': 'Which ' + p + ' should the page show?' });
      inp.addEventListener('click', (e) => e.stopPropagation());
      inp.addEventListener('change', () => { if (inp.value.trim()) { D.params[p] = inp.value.trim(); D.autoLive(); draw(); D.liveRender(); } });
      return h('div', { class: 'ub-empty' }, h('p', { text: 'Which ' + p.replace(/-/g, ' ') + ' should the page show?' }), inp);
    }
    if (bad) {
      const pr = D.problemsOf('chips.' + inst).find((x) => x.level !== 'warning');
      return h('div', { class: 'ub-empty' }, h('p', { text: 'It needs ' + (/map/.test((pr || {}).path || '') ? 'its fields chosen' : 'a fix') + ' before it shows data.' }),
        act(/map/.test((pr || {}).path || '') ? 'Choose fields' : 'Show me', (b) => (/map/.test((pr || {}).path || '') ? U.pick.open('fields', inst, b) : openAdvancedOn(inst)), { 'data-ub-empty-act': 'fix' }));
    }
    return h('p', { class: 'ub-loading text-muted-d', text: 'Loading live data…' });
  }
  function paintEmpty(bad) {
    Object.keys(P.blocks).forEach((inst) => {
      const b = P.blocks[inst], needs = bad.has(inst) || !(D.doc.chips[inst] || {}).supply && !(D.doc.traces || []).some((t) => t.to.split('.')[0] === inst) || D.missingParams().length;
      if (needs || !b.__body.hasChildNodes() || b.__body.querySelector(':scope > .ub-empty')) { clear(b.__body).appendChild(emptyOf(inst, bad.has(inst))); }
    });
  }
  function block(inst) {
    const i = D.doc.chips[inst], full = W.isFull(D, inst);
    const title = h('h3', { class: 'ub-block-title', text: W.titleOf(D, inst) });
    const state = h('span', { class: 'ub-state', role: 'status' });
    const grip = h('span', { class: 'ub-block-grip', title: 'Drag to move', 'aria-hidden': 'true' }, h('i', { class: 'bi bi-grip-vertical' }));
    const body = P.bodies[inst] || h('div', { class: 'ub-body ub-block-body' });
    P.bodies[inst] = body;
    const edge = h('span', { class: 'ub-block-edge', title: 'Drag to make it half or full width', 'aria-hidden': 'true' });
    const el = h('article', { class: 'ub-block' + (D.selected === inst ? ' ub-on' : ''), tabindex: '0', 'data-block': inst, 'data-instance': inst, 'data-full': String(full), 'aria-label': W.titleOf(D, inst) + ' block. Enter selects it.',
      style: 'grid-column: span ' + spanOf(inst) },
    h('div', { class: 'ub-pills', role: 'group', 'aria-label': 'Change ' + W.titleOf(D, inst) }, pill('swap', 'Swap chip', inst), pill('data', 'Its data', inst), pill('follows', 'Follows…', inst)),
    h('header', { class: 'ub-block-head' }, grip, title, state), body, edge);
    el.__body = body; el.__state = state;
    el.addEventListener('click', () => D.select(inst));
    el.addEventListener('keydown', (e) => { if (e.target === el && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); D.select(inst); } });
    el.querySelector('.ub-block-head').addEventListener('pointerdown', (e) => dragMove(e, inst, el));
    edge.addEventListener('pointerdown', (e) => dragEdge(e, inst, el));
    if (i && (D.problemsOf('chips.' + inst).some((p) => p.level !== 'warning'))) { el.classList.add('ub-bad'); }
    return el;
  }
  function spanOf(inst) {
    if (D.device === 'phone') { return 12; }
    const g = (D.doc.layout || {}).desktop || {}, a = (g.areas || []).find((x) => x.chip === inst), cols = g.columns || 12;
    return a ? Math.max(1, Math.round(a.span[0] * 12 / cols)) : 6;
  }
  function addZone(index, big) {
    const z = h('button', { type: 'button', class: 'ub-add' + (big ? ' ub-add-big' : ''), 'data-ub-add': String(index), 'aria-label': 'Add a block here' }, h('span', { text: '+ Add a block here' }));
    z.addEventListener('click', () => U.pick.open('add', null, z, { index }));
    return z;
  }
  /** The page in its reading order, cut into rows of twelve columns; an add zone between the rows and after the last. */
  function draw() {
    if (D.mode === 'chip') { return; }
    clear(pageEl);
    P.blocks = {};
    pageEl.classList.toggle('ub-page-phone', D.device === 'phone');
    const order = D.device === 'phone' ? phoneOrder() : W.order(D);
    if (!order.length) {
      pageEl.appendChild(h('div', { class: 'ub-page-empty' }, h('h2', { text: 'An empty page' }), h('p', { class: 'text-muted-d', text: 'Add the first block: a table, a card, a chart.' }), addZone(0, true)));
      paintSide();
      return;
    }
    let row = null, used = 12, at = 0;
    order.forEach((inst) => {
      const s = spanOf(inst);
      if (used + s > 12) {
        if (row) { pageEl.appendChild(addZone(at)); }
        row = h('div', { class: 'ub-row-grid' }); pageEl.appendChild(row); used = 0;
      }
      const el = block(inst);
      P.blocks[inst] = el;
      row.appendChild(el);
      used += s; at += 1;
    });
    pageEl.appendChild(addZone(order.length, true));
    paintEmpty(renderDoc().bad);
    paintSide();
  }
  function phoneOrder() {
    const ph = ((D.doc.layout || {}).phone || {}).order || [], all = W.order(D);
    return ph.filter((n) => all.indexOf(n) >= 0).concat(all.filter((n) => ph.indexOf(n) < 0));
  }
  P.draw = draw;

  // ---- reorder and resize -------------------------------------------------------------------------------------------------------------------
  function move(inst, to) {
    const list = W.order(D).filter((x) => x !== inst);
    list.splice(Math.max(0, Math.min(to, list.length)), 0, inst);
    W.reflow(D, list);
    D.changed('chips');
    D.say('Moved ' + W.titleOf(D, inst) + ' to place ' + (list.indexOf(inst) + 1) + ' of ' + list.length + '.');
  }
  P.move = move;
  function dragMove(e, inst, el) {
    if (e.button !== 0 || e.target.closest('button, input, select, a')) { return; }
    const x0 = e.clientX, y0 = e.clientY;
    let on = false, target = null;
    const mv = (ev) => {
      if (!on && Math.hypot(ev.clientX - x0, ev.clientY - y0) < 6) { return; }
      on = true; el.classList.add('ub-dragging');
      const over = document.elementFromPoint(ev.clientX, ev.clientY), b = over && over.closest('.ub-block');
      pageEl.querySelectorAll('.ub-drop-before, .ub-drop-after').forEach((x) => x.classList.remove('ub-drop-before', 'ub-drop-after'));
      target = null;
      if (b && b !== el) {
        const r = b.getBoundingClientRect(), after = ev.clientX > r.left + r.width / 2;
        b.classList.add(after ? 'ub-drop-after' : 'ub-drop-before');
        target = { inst: b.getAttribute('data-block'), after };
      }
    };
    const up = () => {
      window.removeEventListener('pointermove', mv); window.removeEventListener('pointerup', up);
      el.classList.remove('ub-dragging');
      pageEl.querySelectorAll('.ub-drop-before, .ub-drop-after').forEach((x) => x.classList.remove('ub-drop-before', 'ub-drop-after'));
      if (on && target) { const list = W.order(D).filter((x) => x !== inst); move(inst, list.indexOf(target.inst) + (target.after ? 1 : 0)); }
    };
    window.addEventListener('pointermove', mv); window.addEventListener('pointerup', up);
  }
  function dragEdge(e, inst, el) {
    e.preventDefault(); e.stopPropagation();
    const r = el.parentNode.getBoundingClientRect();
    const up = (ev) => {
      window.removeEventListener('pointerup', up);
      const full = ev.clientX > r.left + r.width * 0.75;
      if (full !== W.isFull(D, inst)) { W.setWidth(D, inst, full); D.changed('chips'); D.say(W.titleOf(D, inst) + (full ? ' spans the full width.' : ' takes half the width.')); }
    };
    window.addEventListener('pointerup', up);
  }

  // ---- the side panel ------------------------------------------------------------------------------------------------------------------------
  const sect = (label, ...kids) => h('section', { class: 'ub-side-s' }, h('h4', { class: 'ub-side-l', text: label }), ...kids);
  function link(text, on, attrs) { const a = h('button', Object.assign({ type: 'button', class: 'ub-link', text }, attrs || {})); a.addEventListener('click', on); return a; }
  function paintSide() {
    clear(side);
    const inst = D.selected, i = inst && D.doc.chips[inst];
    if (!i) {
      side.appendChild(h('p', { class: 'ub-side-l', text: 'THIS PAGE' }));
      side.appendChild(h('h3', { class: 'ub-side-t', text: titleIn.value || 'Untitled board' }));
      const n = Object.keys(D.doc.chips || {}).length;
      side.appendChild(h('p', { class: 'ub-side-p', text: n ? n + ' block' + (n === 1 ? '' : 's') + ', live as you. Click a block to change it.' : 'Add a block to start.' }));
      const errs = D.problems.filter((p) => p.level !== 'warning').length;
      if (errs) { side.appendChild(h('p', { class: 'ub-side-p ub-bad-text', text: errs + ' thing' + (errs === 1 ? '' : 's') + ' to fix before it can be saved.' }), link('Show the details', () => openAdvancedOn(null))); }
      return;
    }
    const chip = W.chipOf(D, inst), fed = W.fedBy(D, inst), fol = W.follows(D, inst);
    side.appendChild(h('p', { class: 'ub-side-l', text: 'SELECTED' }));
    const t = h('input', { class: 'ub-side-t ub-side-title', value: W.titleOf(D, inst), 'aria-label': 'Its title', 'data-ub-side-title': true });
    t.addEventListener('change', () => { if (t.value.trim()) { i.title = t.value.trim(); } else { delete i.title; } D.changed('chips'); });
    side.appendChild(t);
    const purpose = ((chip.manual || {}).purpose || chip.title || i.use).replace(/\.$/, '');
    side.appendChild(h('p', { class: 'ub-side-p', 'data-ub-side-what': true }, purpose + '. ', h('span', { class: 'ub-side-k', text: 'Fed by: ' }), fed || 'nothing yet', '.'));
    side.appendChild(sect('FOLLOWS', h('p', { class: 'ub-side-p', 'data-ub-side-follows': true, text: fol ? fol.charAt(0).toUpperCase() + fol.slice(1) + '.' : 'Nothing. It shows ' + (fed ? fed : 'its own data') + '.' }),
      link('Change what it follows', (e) => U.pick.open('follows', inst, e.currentTarget))));
    const shown = W.shown(D, inst);
    const fieldsP = h('p', { class: 'ub-side-p', 'data-ub-side-fields': true, text: shown.length ? shown.map((f) => W.field(f)).join(' · ') : (i.supply ? 'None chosen yet.' : 'The fields of what it follows.') });
    side.appendChild(sect('SHOWN FIELDS', fieldsP, i.supply ? link('Change fields', (e) => U.pick.open('fields', inst, e.currentTarget), { 'data-ub-change-fields': true }) : null));
    if (i.supply) { D.attributes(i.supply).then((attrs) => { if (attrs && shown.length && D.selected === inst) { fieldsP.textContent = shown.map((f) => W.field(f, attrs)).join(' · '); } }); }
    const full = W.isFull(D, inst);
    const half = h('button', { type: 'button', class: 'ub-seg', 'aria-pressed': String(!full), 'data-ub-width': 'half', text: 'Half' }), whole = h('button', { type: 'button', class: 'ub-seg', 'aria-pressed': String(full), 'data-ub-width': 'full', text: 'Full' });
    [[half, false], [whole, true]].forEach(([b, f]) => b.addEventListener('click', () => { W.setWidth(D, inst, f); D.changed('chips'); }));
    const at = W.order(D).indexOf(inst), n = W.order(D).length;
    const up = h('button', { type: 'button', class: 'ub-icon', 'aria-label': 'Move earlier', 'data-ub-move': 'up', disabled: at <= 0 }, h('i', { class: 'bi bi-arrow-up', 'aria-hidden': 'true' }));
    const dn = h('button', { type: 'button', class: 'ub-icon', 'aria-label': 'Move later', 'data-ub-move': 'down', disabled: at >= n - 1 }, h('i', { class: 'bi bi-arrow-down', 'aria-hidden': 'true' }));
    up.addEventListener('click', () => move(inst, at - 1)); dn.addEventListener('click', () => move(inst, at + 1));
    side.appendChild(sect('ON THE PAGE', h('div', { class: 'ub-row' }, h('span', { class: 'ub-segs', role: 'group', 'aria-label': 'Width' }, half, whole), up, dn)));
    const probs = D.problemsOf('chips.' + inst);
    if (probs.length) { side.appendChild(h('p', { class: 'ub-side-p ' + (probs.some((p) => p.level !== 'warning') ? 'ub-bad-text' : 'text-muted-d'), text: probs[0].message })); }
    const rm = link('Remove this block', () => { D.removeInstance(inst); }, { 'data-ub-remove': true });
    rm.classList.add('ub-link-bad');
    side.appendChild(h('div', { class: 'ub-side-foot' }, rm, link('Open in Advanced', () => openAdvancedOn(inst))));
  }
  function openAdvancedOn(inst) { adv.open = true; if (inst) { D.selected = null; D.select(inst); } adv.scrollIntoView({ block: 'start' }); }

  // ---- keeping the page in step with the document ------------------------------------------------------------------------------------------
  const drawPins = C.draw;
  C.draw = function () { drawPins(); draw(); };
  const changed = D.changed;
  D.changed = function (what) { changed(what); if (what === 'layout' || what === 'map') { draw(); } };
  root.addEventListener('ub:select', () => {
    Object.keys(P.blocks).forEach((n) => P.blocks[n].classList.toggle('ub-on', n === D.selected));
    paintSide();
  });
  root.addEventListener('ub:checked', () => {
    Object.keys(P.blocks).forEach((n) => P.blocks[n].classList.toggle('ub-bad', D.problemsOf('chips.' + n).some((p) => p.level !== 'warning')));
    paintSide();
  });
  pageEl.addEventListener('pointerover', (e) => {
    const b = e.target.closest && e.target.closest('.ub-block');
    clearTimeout(hoverTimer);
    pageEl.querySelectorAll('.ub-hover').forEach((x) => { if (x !== b) { x.classList.remove('ub-hover'); } });
    if (b) { b.classList.add('ub-hover'); }
  });
  pageEl.addEventListener('pointerleave', () => { hoverTimer = setTimeout(() => pageEl.querySelectorAll('.ub-hover').forEach((x) => x.classList.remove('ub-hover')), 150); });

  // ---- the top bar ---------------------------------------------------------------------------------------------------------------------------
  const more = $('[data-ub-more]'), menu = $('[data-ub-menu]');
  function closeMenu() { menu.hidden = true; more.setAttribute('aria-expanded', 'false'); }
  more.addEventListener('click', (e) => { e.stopPropagation(); menu.hidden = !menu.hidden; more.setAttribute('aria-expanded', String(!menu.hidden)); if (!menu.hidden) { const f = menu.querySelector('button, select, a'); if (f) { f.focus(); } } });
  menu.addEventListener('click', (e) => { if (e.target.closest('button, a')) { closeMenu(); } });
  document.addEventListener('pointerdown', (e) => { if (!menu.hidden && !menu.contains(e.target) && e.target !== more && !more.contains(e.target)) { closeMenu(); } });
  menu.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeMenu(); more.focus(); } });
  // the title names the board too, until it is saved (a new board's name follows its title)
  let autoName = !nameIn.value.trim();
  titleIn.addEventListener('input', () => {
    if (!autoName || nameIn.readOnly) { return; }
    nameIn.value = titleIn.value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
    nameIn.dispatchEvent(new Event('input')); nameIn.dispatchEvent(new Event('change'));
  });
  nameIn.addEventListener('input', (e) => { if (e.isTrusted) { autoName = false; } });
  titleIn.addEventListener('change', () => { D.doc.title = titleIn.value.trim(); paintSide(); });
  const asDlg = $('[data-ub-as-dlg]');
  $('[data-ub-preview-as]').addEventListener('click', () => { $('[data-ub-as-me]').textContent = root.getAttribute('data-user') || 'you'; asDlg.showModal(); });
  $('[data-ub-as-share]').addEventListener('click', () => { asDlg.close(); $('[data-ub-share]').click(); });
  const phoneBtn = $('[data-ub-device]');
  phoneBtn.addEventListener('click', () => { pageEl.classList.toggle('ub-page-phone', D.device === 'phone'); });

  draw();
})();
