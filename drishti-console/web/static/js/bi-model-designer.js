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
/* The model designer, its core (docs/architecture/BI_MODEL_SPEC.md section 9.3): the model is one JSON document (the YAML's keys); every action is an edit
   of it that can be undone; after each edit the console writes the YAML and the server checks it (debounced), and the canvas, the suggestions and the tabs
   redraw from the answer. Unsaved work is kept in this browser (a refresh or Back offers it again), leaving asks first, a stale save offers Reload, a copy
   or Overwrite. The Measures, Security and Contract tabs are bi-model-tabs.js, on the same object (window.drishtiModel). */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  const B = window.drishtiBi;
  if (!canvas || !B || !B.layer || !canvas.querySelector('[data-md-root]')) { return; }
  const h = B.h, q = (s, sc) => B.q(s, sc || canvas);
  const root = q('[data-md-root]');
  const itemId = canvas.getAttribute('data-item');
  const shell = canvas.closest('.bi-shell');
  if (shell) { shell.classList.add('bi-shell-design'); }
  const KEEP = 'drishti.bi.model.draft.' + itemId;

  const M = (window.drishtiModel = { doc: null, infos: {}, check: null, suggestions: [], yaml: '', item: null, etag: null, readOnly: false, dirty: false, selected: null, tabs: [], listeners: {} });
  M.on = (ev, fn) => { (M.listeners[ev] = M.listeners[ev] || []).push(fn); };
  M.emit = (ev, x) => (M.listeners[ev] || []).forEach((fn) => { try { fn(x); } catch (e) { window.console.error(e); } });
  M.say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  M.h = h;
  M.q = q;

  // ---- edits, undo, keeping the draft --------------------------------------------------------------------------------
  const undo = [], redo = [];
  function buttons() {
    q('[data-undo]').disabled = !undo.length || M.readOnly;
    q('[data-redo]').disabled = !redo.length || M.readOnly;
    const it = M.item || {};
    q('[data-state]').textContent = M.readOnly ? 'Read-only' : M.conflict ? 'Not saved: saved elsewhere' : M.dirty ? (M.saving ? 'Saving the draft…' : 'Unsaved changes') : it.status === 'published' ? 'Published'
      : it.status === 'proposed' ? 'Proposed' : it.publishedVersion ? 'Draft changes (v' + it.publishedVersion + ' published)' : 'Saved draft';
    publishState();
  }
  /** Publish (or Propose) shows whenever the person could publish this model once it is saved: a published model with a newer saved or unsaved version included. In a
   *  private workspace it is disabled with the reason and a Move button beside it (never a dead end). */
  function publishState() {
    const it = M.item, btn = q('[data-publish]'), mv = q('[data-move]');
    if (!it) { return; }
    const c = it.can || {};
    const publishedAgain = it.status === 'published' && c.edit && !it.personal;
    btn.hidden = M.readOnly || !(c.publish || c.propose || (it.personal && c.edit) || publishedAgain);
    q('[data-publish-label]').textContent = it.workspaceReviewRequired ? 'Propose' : 'Publish';
    const stuck = !!it.personal && !M.readOnly;
    btn.disabled = stuck;
    btn.title = stuck ? 'This model is in your private workspace, which cannot publish. Move it to a shared workspace first.' : '';
    mv.hidden = !(stuck && c.move);
  }
  function moveModel() {
    const btn = q('[data-move]');
    return B.askWorkspace(btn, 'Move here', 'Move the model to').then((to) => {
      if (to === 'me') { B.near(btn, 'Pick a shared workspace: your private workspace cannot publish.', true); return null; }
      return B.busy(btn, () => B.api('POST', '/items/' + B.id2path(itemId) + '/move', { workspace: to }).then((n) => {
        M.item = n; M.etag = n.etag; buttons();
        B.near(q('[data-publish]').hidden ? btn : q('[data-publish]'), 'Moved to ' + (n.workspaceName || to) + '. Publish is available there.', false);
      }));
    }).catch((e) => { if (!e.cancelled) { B.near(btn, e.message, true); } });
  }
  function keep() {
    try { window.localStorage.setItem(KEEP, JSON.stringify({ etag: M.etag, doc: M.doc, at: Date.now() })); } catch (e) { /* the browser keeps nothing; Save still works */ }
  }
  function forget() { try { window.localStorage.removeItem(KEEP); } catch (e) { /* nothing kept */ } }

  /** Applies `fn(doc)` as one undoable edit, then checks the result. */
  M.edit = function (fn, quiet) {
    if (M.readOnly) { M.say('This model is read-only here: Save a copy to change it.', true); return; }
    M.acted = true;
    undo.push(B.clone(M.doc));
    if (undo.length > 100) { undo.shift(); }
    redo.length = 0;
    fn(M.doc);
    M.dirty = true;
    M.rev = (M.rev || 0) + 1;
    keep();
    buttons();
    if (!quiet) { M.redraw(); }
    M.compose();
    M.autosave();
  };
  function step(from, to) {
    if (!from.length || M.readOnly) { return; }
    to.push(B.clone(M.doc));
    M.doc = from.pop();
    M.dirty = true;
    M.rev = (M.rev || 0) + 1;
    keep();
    buttons();
    M.redraw();
    M.compose();
    M.autosave();
  }

  // ---- compose: the YAML, the server's Check, the suggestions --------------------------------------------------------
  let timer = null, seq = 0;
  M.compose = function (now) {
    window.clearTimeout(timer);
    return new Promise((resolve) => {
      timer = window.setTimeout(() => {
        const mine = ++seq;
        B.layer.post('compose', { doc: M.doc, base: M.base || '' }).then((r) => {
          if (mine !== seq) { resolve(); return; }
          M.yaml = r.yaml;
          M.infos = r.datasets || {};
          M.check = r.check;
          M.suggestions = r.suggestions || [];
          const nEx = ((r.check && r.check.exposures) || []).length, bare = !Object.keys(M.doc.datasets || {}).length;
          const badge = q('[data-valid]');
          // the header agrees with the Security tab: a model that checks clean but has a role exposed is not ready, said in words and in the warning colour (not the error one)
          // the badge agrees with the problems pinned on the canvas: valid with warnings says how many
          const nWarn = ((r.check && r.check.problems) || []).filter((p) => p.level === 'warning').length;
          badge.textContent = bare ? 'Add a dataset to begin' : !r.check.valid ? (M.acted ? 'Has errors' : 'Not ready yet') : nEx ? 'Valid, ' + nEx + ' to fix before publishing' : nWarn ? 'Valid, ' + nWarn + ' warning' + (nWarn === 1 ? '' : 's') : 'Valid';
          badge.classList.toggle('bi-bad', !bare && !r.check.valid && !!M.acted);
          badge.classList.toggle('bi-warnbadge', !bare && (nEx > 0 || nWarn > 0 || (!r.check.valid && !M.acted)));
          M.emit('checked');
          M.redraw();
          resolve();
        }).catch((e) => { M.say(e.message, true); resolve(); });
      }, now ? 0 : 280);
    });
  };
  M.problems = (prefix) => ((M.check && M.check.problems) || []).filter((p) => !prefix || p.path === prefix || p.path.indexOf(prefix + '.') === 0 || p.path.indexOf(prefix + '[') === 0);

  // ---- the datasets list ---------------------------------------------------------------------------------------------
  let catalogue = [];
  const MIME = 'text/x-drishti-dataset';
  function drawList() {
    const box = B.clear(q('[data-ds-list]'));
    const t = (q('[data-ds-search]').value || '').trim().toLowerCase();
    const used = new Set(Object.values(M.doc.datasets || {}).map((d) => String((d && d.dataset) || d).split('@')[0]));
    catalogue.filter((d) => !t || (d.name + ' ' + d.title).toLowerCase().indexOf(t) >= 0).forEach((d) => {
      const add = h('button', { type: 'button', class: 'bi-chipbtn', 'data-add-dataset': d.name, 'aria-label': 'Add ' + d.title + ' to the model', title: 'Add to the model', disabled: M.readOnly }, B.icon(used.has(d.name) ? 'plus-square' : 'plus-lg'));
      add.addEventListener('click', () => addDataset(d.ref, add));
      const li = h('li', { class: 'bi-md-dsitem' + (used.has(d.name) ? ' bi-md-used' : ''), draggable: 'true', 'data-ds': d.name },
        h('span', { class: 'bi-md-dsname' }, h('strong', { text: d.title }), h('small', { class: 'mono text-muted-d', text: d.name })), add);
      li.addEventListener('dragstart', (e) => { e.dataTransfer.setData(MIME, d.ref); e.dataTransfer.effectAllowed = 'copy'; });
      box.appendChild(li);
    });
  }
  q('[data-ds-search]').addEventListener('input', drawList);

  /** Adds a dataset: alias from its name, role inferred (a dataset another one references is a dimension; one with amounts a fact). */
  function addDataset(ref, anchor, at) {
    return B.layer.get('dataset?name=' + encodeURIComponent(ref)).then((d) => {
      const i = d.info;
      const kinds = Object.values(M.infos).map((x) => x);
      const referenced = kinds.some((x) => (x.source || {}).kind !== i.source.kind && x.columns.some((c) => c.references && c.references === i.source.kind));   // its own id column is no other dataset's reference
      let alias = i.alias, n = 2;
      while ((M.doc.datasets || {})[alias]) { alias = i.alias + '_' + n++; }
      const role = referenced ? 'dimension' : i.role;
      M.edit((doc) => {
        doc.datasets = doc.datasets || {};
        doc.datasets[alias] = { dataset: i.name + '@^' + String(i.version || '1').split('.')[0], role };
        const lay = (doc.layout = doc.layout || {});
        lay.positions = lay.positions || {};
        lay.positions[alias] = at || freeSpot(lay.positions, alias, role);
      });
      M.selected = { kind: 'dataset', alias };
      B.near(anchor || q('[data-ds-list]'), 'Added ' + i.title + ' as ' + alias + ' (' + role + '). Suggested relationships appear under the canvas.', false);
    }).catch((e) => B.near(anchor || q('[data-ds-list]'), e.message, true));
  }
  function autoPlace(i, role) { return [24 + (i % 3) * 270, 24 + Math.floor(i / 3) * 260 + (role === 'dimension' ? 0 : 20)]; }
  const BOX_W = 260, BOX_H = 250;
  const overlaps = (a, b) => Math.abs(a[0] - b[0]) < BOX_W && Math.abs(a[1] - b[1]) < BOX_H;
  /** The first place on the grid (three across, facts and dimensions alike) that no other box covers. */
  function freeSpot(positions, alias, role) {
    const others = Object.keys(positions || {}).filter((a) => a !== alias).map((a) => positions[a]).filter(Array.isArray);
    for (let i = 0; i < 300; i++) { const p = autoPlace(i, role); if (!others.some((o) => overlaps(o, p))) { return p; } }
    return autoPlace(others.length, role);
  }
  /** Auto-layout: every box that sits on another (an old draft, a drop on a crowded canvas) moves to a free place; the boxes not touched stay. */
  function untangle(positions) {
    const seen = {};
    let moved = 0;
    Object.keys(M.doc.datasets || {}).forEach((a) => {
      const p = positions[a];
      if (!Array.isArray(p) || Object.keys(seen).some((b) => overlaps(seen[b], p))) { positions[a] = freeSpot(seen, a, (M.doc.datasets[a] || {}).role); moved++; }
      seen[a] = positions[a];
    });
    return moved;
  }
  /** Tidy: every box placed again on the grid, the facts first and then the dimensions, none over another. */
  M.untangle = () => M.edit((doc) => {
    doc.layout = doc.layout || {};
    const sets = doc.datasets || {}, order = Object.keys(sets).sort((a, b) => ((sets[a] || {}).role === 'dimension') - ((sets[b] || {}).role === 'dimension'));
    doc.layout.positions = {};
    order.forEach((a, i) => { doc.layout.positions[a] = autoPlace(i, (sets[a] || {}).role); });
  });

  // ---- the canvas ----------------------------------------------------------------------------------------------------
  const NS = 'http://www.w3.org/2000/svg';
  const inner = q('[data-diagram-inner]');
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'bi-lines');
  inner.appendChild(svg);
  const boxes = new Map();
  const posOf = (alias) => { const p = (((M.doc.layout || {}).positions || {})[alias]); return Array.isArray(p) ? p : [24, 24]; };
  const aliasOf = (field) => String(field || '').split('.')[0];

  function drawCanvas() {
    boxes.forEach((b) => b.remove());
    boxes.clear();
    const sets = M.doc.datasets || {};
    q('[data-canvas-empty]').hidden = Object.keys(sets).length > 0;
    let maxX = 0, maxY = 0;
    Object.keys(sets).forEach((alias) => {
      const use = sets[alias] || {};
      const info = M.infos[alias];
      const [x, y] = posOf(alias);
      maxX = Math.max(maxX, x + 260); maxY = Math.max(maxY, y + 230);
      const probs = M.problems('datasets.' + alias);
      const role = h('select', { class: 'studio-select bi-md-role', 'aria-label': 'Role of ' + alias, 'data-role-of': alias, disabled: M.readOnly },
        ['fact', 'dimension'].map((r) => h('option', { value: r, text: r, selected: (use.role || 'fact') === r })));
      role.addEventListener('change', () => M.edit((doc) => { doc.datasets[alias].role = role.value; }));
      const cols = info ? info.columns : [];
      const list = h('ul', { class: 'bi-md-cols' }, cols.slice(0, 9).map((c) => h('li', { 'data-col': alias + '.' + c.name, title: [c.role, c.meaning, c.references ? 'references ' + c.references : ''].filter(Boolean).join(' · ') },
        B.icon(c.role === 'amount' || c.role === 'quantity' ? 'calculator' : c.references ? 'link-45deg' : c.type === 'date' ? 'calendar3' : 'fonts'), ' ' + c.name)));
      if (cols.length > 9) { list.appendChild(h('li', { class: 'text-muted-d', text: '… ' + (cols.length - 9) + ' more' })); }
      const box = h('div', { class: 'bi-tbox bi-md-box bi-md-' + (use.role || 'fact') + (probs.some((p) => p.level === 'error') ? ' bi-md-bad' : ''), tabindex: '0', role: 'group', 'data-box': alias,
        'aria-label': alias + ', ' + (use.role || 'fact') + (info ? ', ' + info.title : '') },
        h('header', { class: 'bi-md-boxhead', 'data-handle': alias }, h('strong', { text: alias }), role),
        h('p', { class: 'bi-md-boxsub text-muted-d', text: info ? info.title + ' · ' + (info.grain.says || '') : String(use.dataset || '') }),
        probs.length ? (() => { const p0 = probs.find((p) => p.level === 'error') || probs[0]; return h('p', { class: 'bi-md-pin' + (p0.level === 'error' ? '' : ' bi-md-pin-warn'), 'data-pin': alias, 'data-pin-level': p0.level || 'error' }, B.icon(p0.level === 'error' ? 'exclamation-triangle' : 'info-circle'), ' ' + p0.code + ' ' + p0.message.slice(0, 120) + (probs.length > 1 ? ' (+' + (probs.length - 1) + ' more: Check lists them)' : '')); })() : null,
        list);
      box.style.left = x + 'px';
      box.style.top = y + 'px';
      if (M.selected && M.selected.kind === 'dataset' && M.selected.alias === alias) { box.classList.add('bi-md-sel'); }
      box.addEventListener('click', (e) => { if (e.target.tagName !== 'SELECT') { M.select({ kind: 'dataset', alias }); } });
      box.addEventListener('keydown', (e) => keyMove(e, alias));
      drag(box, box.querySelector('[data-handle]'), alias);
      inner.appendChild(box);
      boxes.set(alias, box);
    });
    inner.style.width = Math.max(maxX + 40, 600) + 'px';
    inner.style.height = Math.max(maxY + 40, 420) + 'px';
    lines();
  }

  function keyMove(e, alias) {
    if (e.target !== e.currentTarget) { return; }
    const d = { ArrowLeft: [-20, 0], ArrowRight: [20, 0], ArrowUp: [0, -20], ArrowDown: [0, 20] }[e.key];
    if (e.key === 'Enter') { e.preventDefault(); M.select({ kind: 'dataset', alias }); return; }
    if (!d || M.readOnly) { return; }
    e.preventDefault();
    const [x, y] = posOf(alias);
    M.edit((doc) => { doc.layout.positions[alias] = [Math.max(0, x + d[0]), Math.max(0, y + d[1])]; });
    window.setTimeout(() => { const b = boxes.get(alias); if (b) { b.focus(); } }, 0);
  }

  function drag(box, handle, alias) {
    handle.addEventListener('pointerdown', (e) => {
      if (M.readOnly || e.target.tagName === 'SELECT' || e.button !== 0) { return; }
      e.preventDefault();
      const [x0, y0] = posOf(alias);
      const sx = e.clientX, sy = e.clientY;
      let moved = false;
      const move = (ev) => { moved = true; box.style.left = Math.max(0, x0 + ev.clientX - sx) + 'px'; box.style.top = Math.max(0, y0 + ev.clientY - sy) + 'px'; lines(); };
      const up = (ev) => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        if (moved) { M.edit((doc) => { doc.layout = doc.layout || {}; doc.layout.positions = doc.layout.positions || {}; doc.layout.positions[alias] = [Math.max(0, x0 + ev.clientX - sx), Math.max(0, y0 + ev.clientY - sy)]; }, false); }
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    });
  }
  const diagram = q('[data-diagram]');
  diagram.addEventListener('dragover', (e) => { if ([...e.dataTransfer.types].indexOf(MIME) >= 0) { e.preventDefault(); diagram.classList.add('bi-drop'); } });
  diagram.addEventListener('dragleave', () => diagram.classList.remove('bi-drop'));
  diagram.addEventListener('drop', (e) => {
    diagram.classList.remove('bi-drop');
    const ref = e.dataTransfer.getData(MIME);
    if (!ref) { return; }
    e.preventDefault();
    const r = inner.getBoundingClientRect();
    addDataset(ref, null, [Math.max(0, Math.round(e.clientX - r.left - 110)), Math.max(0, Math.round(e.clientY - r.top - 20))]);
  });

  function lines() {
    while (svg.firstChild) { svg.removeChild(svg.firstChild); }
    svg.setAttribute('width', inner.style.width || '600');
    svg.setAttribute('height', inner.style.height || '420');
    (M.doc.relationships || []).forEach((r, i) => {
      const a = boxes.get(aliasOf(r.from)), b = boxes.get(aliasOf(r.to));
      if (!a || !b) { return; }
      const ax = a.offsetLeft + a.offsetWidth / 2, ay = a.offsetTop + a.offsetHeight / 2, bx = b.offsetLeft + b.offsetWidth / 2, by = b.offsetTop + b.offsetHeight / 2;
      const line = document.createElementNS(NS, 'line');
      [['x1', ax], ['y1', ay], ['x2', bx], ['y2', by]].forEach(([k, v]) => line.setAttribute(k, v));
      const sel = M.selected && M.selected.kind === 'rel' && M.selected.index === i;
      line.setAttribute('class', (r.active === false ? 'bi-line-off' : '') + (sel ? ' bi-line-sel' : ''));
      svg.appendChild(line);
      const hit = document.createElementNS(NS, 'line');
      [['x1', ax], ['y1', ay], ['x2', bx], ['y2', by]].forEach(([k, v]) => hit.setAttribute(k, v));
      hit.setAttribute('class', 'bi-line-hit');
      hit.addEventListener('click', () => M.select({ kind: 'rel', index: i }));
      svg.appendChild(hit);
      const t = document.createElementNS(NS, 'text');
      t.setAttribute('x', (ax + bx) / 2);
      t.setAttribute('y', (ay + by) / 2 - 4);
      t.textContent = ({ 'many-to-one': '* → 1', 'one-to-one': '1 → 1', 'one-to-many': '1 → *' }[r.cardinality || 'many-to-one'] || r.cardinality) + (r.active === false ? ' (inactive)' : '');
      svg.appendChild(t);
    });
  }

  // ---- relationships: the list, the suggestions, by hand -------------------------------------------------------------
  function relText(r) { return r.from + ' → ' + r.to; }
  function drawRels() {
    const box = B.clear(q('[data-rels]'));
    (M.doc.relationships || []).forEach((r, i) => {
      const probs = M.problems('relationships[' + i + ']');
      const open = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-rel-open': i, text: 'Edit' });
      open.addEventListener('click', () => M.select({ kind: 'rel', index: i }));
      box.appendChild(h('li', { class: 'bi-rel' + (M.selected && M.selected.kind === 'rel' && M.selected.index === i ? ' bi-rel-sel' : ''), 'data-rel': relText(r) },
        h('code', { text: relText(r) }), ' ', h('span', { class: 'bi-badge', text: r.cardinality || 'many-to-one' }), r.active === false ? h('span', { class: 'bi-badge', text: 'inactive' }) : null,
        r.suggested ? h('span', { class: 'bi-badge', title: 'Accepted from a suggestion', text: 'from the pack' }) : null, ' ', open,
        probs.length ? h('p', { class: 'bi-near' + (M.acted ? ' bi-bad' : ' bi-hint'), text: probs[0].code + ' ' + probs[0].message }) : null));
    });
    if (!(M.doc.relationships || []).length) { box.appendChild(h('li', { class: 'text-muted-d', text: 'None yet: accept a suggestion above, or relate two columns by hand.' })); }
    const sbox = B.clear(q('[data-suggestions]'));
    M.suggestions.forEach((s) => {
      const add = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-accept': s.from + '->' + s.to, text: s.active ? 'Add' : 'Add as inactive', disabled: M.readOnly });
      const no = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-dismiss': s.from + '->' + s.to, text: 'Dismiss', disabled: M.readOnly });
      add.addEventListener('click', () => M.edit((doc) => {
        doc.relationships = doc.relationships || [];
        const r = { from: s.from, to: s.to };
        if (!s.active) { r.active = false; }
        r.suggested = { from: s.basis };
        doc.relationships.push(r);
      }));
      no.addEventListener('click', () => M.edit((doc) => { doc.layout = doc.layout || {}; doc.layout.dismissed = (doc.layout.dismissed || []).concat([s.from + '->' + s.to]); }));
      sbox.appendChild(h('li', { class: 'bi-rel bi-md-suggest', 'data-suggestion': s.from + '->' + s.to }, h('code', { text: s.from + ' → ' + s.to }), ' ',
        h('small', { class: 'text-muted-d', text: s.why + (s.reason ? '; ' + s.reason : '') }), ' ', add, ' ', no));
    });
    q('[data-no-suggestions]').hidden = M.suggestions.length > 0;
    // by hand: every column of every dataset
    const fields = [];
    Object.keys(M.infos).forEach((a) => (M.infos[a].columns || []).concat([{ name: 'id' }]).forEach((c) => fields.push(a + '.' + c.name)));
    [q('[data-nr-from]'), q('[data-nr-to]')].forEach((sel) => { const was = sel.value; B.clear(sel); fields.forEach((f) => sel.appendChild(h('option', { value: f, text: f, selected: f === was }))); });
  }
  q('[data-nr-add]').addEventListener('click', () => {
    const from = q('[data-nr-from]').value, to = q('[data-nr-to]').value, btn = q('[data-nr-add]');
    if (!from || !to || aliasOf(from) === aliasOf(to)) { B.near(btn, 'Choose columns of two different datasets.', true); return; }
    M.edit((doc) => { doc.relationships = (doc.relationships || []).concat([{ from, to }]); });
    B.near(btn, 'Added; Check runs now and says if the one side is not unique.', false);
  });

  // ---- the inspector -------------------------------------------------------------------------------------------------
  M.select = function (sel) { M.selected = sel; drawInspector(); drawCanvas(); drawRels(); };
  function frow(label, input, note) { return h('label', { class: 'bi-frow' }, h('span', { text: label }), input, note ? h('small', { class: 'text-muted-d', text: note }) : null); }
  function drawInspector() {
    const box = B.clear(q('[data-inspect]'));
    const s = M.selected;
    if (s && s.kind === 'rel') {
      const r = (M.doc.relationships || [])[s.index];
      if (!r) { box.appendChild(h('p', { class: 'text-muted-d', text: 'Choose a dataset box or a relationship.' })); return; }
      const card = h('select', { class: 'studio-select', 'data-insp-card': '', disabled: M.readOnly }, ['many-to-one', 'one-to-one', 'one-to-many'].map((c) => h('option', { value: c, text: c, selected: (r.cardinality || 'many-to-one') === c })));
      card.addEventListener('change', () => M.edit((doc) => { doc.relationships[s.index].cardinality = card.value; }));
      const dir = h('select', { class: 'studio-select', 'data-insp-dir': '', disabled: M.readOnly }, h('option', { value: 'single', text: 'one way (the one side filters the many)' }), h('option', { value: 'both', text: 'both ways (arrives in wave W2)', disabled: true }));
      const active = h('input', { type: 'checkbox', 'data-insp-active': '', checked: r.active !== false, disabled: M.readOnly });
      active.addEventListener('change', () => M.edit((doc) => { if (active.checked) { delete doc.relationships[s.index].active; } else { doc.relationships[s.index].active = false; } }));
      const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-insp-remove': '', text: 'Remove the relationship', disabled: M.readOnly });
      rm.addEventListener('click', () => { M.edit((doc) => { doc.relationships.splice(s.index, 1); }); M.select(null); });
      box.append(h('h3', { text: 'Relationship' }), h('p', {}, h('code', { text: relText(r) })),
        h('p', { class: 'text-muted-d bi-small-note', text: r.suggested ? 'Accepted from a ' + (r.suggested.from || 'pack') + ' suggestion: the column holds ids of the other dataset\'s kind.' : 'Made by hand.' }),
        frow('Cardinality', card), frow('Filters', dir, 'Both ways arrives with the star in wave W2.'), h('label', { class: 'bi-checkrow' }, active, ' Active (an inactive relationship is used only by a measure that names it)'), rm);
      return;
    }
    if (s && s.kind === 'dataset') {
      const use = (M.doc.datasets || {})[s.alias];
      const info = M.infos[s.alias];
      if (!use) { box.appendChild(h('p', { class: 'text-muted-d', text: 'Choose a dataset box or a relationship.' })); return; }
      const alias = h('input', { class: 'studio-in', value: s.alias, 'data-insp-alias': '', disabled: M.readOnly, 'aria-label': 'Alias' });
      alias.addEventListener('change', () => rename(s.alias, alias.value.trim(), alias));
      const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-insp-remove': '', text: 'Remove from the model', disabled: M.readOnly });
      rm.addEventListener('click', () => {
        const a = s.alias;
        M.edit((doc) => {
          delete doc.datasets[a];
          doc.relationships = (doc.relationships || []).filter((r) => aliasOf(r.from) !== a && aliasOf(r.to) !== a);
          if (doc.layout && doc.layout.positions) { delete doc.layout.positions[a]; }
        });
        M.select(null);
      });
      box.append(h('h3', { text: 'Dataset' }), frow('Alias (what fields are called)', alias, 'Letters, digits and _.'),
        h('p', {}, h('code', { text: String(use.dataset || use) }), ' ', info ? h('a', { href: '/bi/ds/' + encodeURIComponent(info.item || info.name), text: 'Open the dataset' }) : null),
        info ? h('dl', { class: 'bi-layer-facts' }, h('dt', { text: 'One row is' }), h('dd', { text: info.grain.says || '' }), h('dt', { text: 'Keys' }), h('dd', { text: (info.grain.keys || []).join(', ') }),
          h('dt', { text: 'Time' }), h('dd', { text: info.time.mode })) : null,
        info ? h('ul', { class: 'bi-md-cols bi-md-cols-all' }, info.columns.map((c) => h('li', {}, h('code', { text: c.name }), ' ', h('small', { class: 'text-muted-d', text: [c.type, c.role, c.meaning, c.references ? '→ ' + c.references : ''].filter(Boolean).join(' · ') })))) : null, rm);
      return;
    }
    box.appendChild(h('p', { class: 'text-muted-d', text: 'Choose a dataset box or a relationship.' }));
  }
  function rename(from, to, anchor) {
    if (to === from) { return; }
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(to) || (M.doc.datasets || {})[to]) { B.near(anchor, 'An alias is letters, digits and _, not already used.', true); return; }
    const swap = (f) => (aliasOf(f) === from ? to + f.slice(from.length) : f);
    const swapText = (t) => String(t).replace(new RegExp('\\b' + from + '\\.', 'g'), to + '.');
    M.edit((doc) => {
      const next = {};
      Object.keys(doc.datasets).forEach((k) => { next[k === from ? to : k] = doc.datasets[k]; });
      doc.datasets = next;
      (doc.relationships || []).forEach((r) => { r.from = swap(r.from); r.to = swap(r.to); });
      Object.values(doc.measures || {}).forEach((m) => { if (m && m.expr) { m.expr = swapText(m.expr); } if (m && m.currency) { m.currency = swap(m.currency); } });
      const sec = doc.security || {};
      if (sec.rows && sec.rows[from]) { sec.rows[to] = sec.rows[from].map((r) => Object.assign({}, r, { where: swapText(r.where) })); delete sec.rows[from]; }
      if (sec['may-see-all'] && sec['may-see-all'][from]) { sec['may-see-all'][to] = sec['may-see-all'][from]; delete sec['may-see-all'][from]; }
      if (doc.layout && doc.layout.positions && doc.layout.positions[from]) { doc.layout.positions[to] = doc.layout.positions[from]; delete doc.layout.positions[from]; }
    });
    M.selected = { kind: 'dataset', alias: to };
  }

  // ---- tabs, check, save, publish ------------------------------------------------------------------------------------
  let editor = null, ignoreYaml = false, yamlTimer = null;
  const ta = q('[data-yaml]');
  function showTab(name) {
    B.commitFocus(canvas);
    B.qa('[data-tab]', canvas).forEach((b) => b.setAttribute('aria-selected', b.getAttribute('data-tab') === name ? 'true' : 'false'));
    B.qa('[data-pane]', canvas).forEach((p) => { p.hidden = p.getAttribute('data-pane') !== name; });
    q('[data-inspector]').hidden = name !== 'canvas';
    q('.bi-md-side').hidden = ['security', 'measures', 'contract', 'fields', 'calc'].indexOf(name) >= 0;          // the datasets list is for building the canvas
    q('.bi-md-main').classList.toggle('bi-md-wide', ['security', 'measures', 'contract', 'fields', 'calc'].indexOf(name) >= 0);
    M.tab = name;
    if (name === 'yaml') { setYaml(M.yaml); if (editor) { editor.refresh(); } }
    if (name === 'canvas') { lines(); }
    M.emit('tab', name);
  }
  M.showTab = showTab;
  B.qa('[data-tab]', canvas).forEach((b) => b.addEventListener('click', () => showTab(b.getAttribute('data-tab'))));
  function setYaml(t) { ignoreYaml = true; if (editor) { editor.setValue(t || ''); } else { ta.value = t || ''; } ignoreYaml = false; }
  function onYaml() {
    if (ignoreYaml) { return; }
    window.clearTimeout(yamlTimer);
    yamlTimer = window.setTimeout(() => {
      const text = editor ? editor.getValue() : ta.value;
      B.layer.post('parse', { yaml: text }).then((r) => { q('[data-yaml-problem]').hidden = true; M.base = text; M.edit((doc) => { Object.keys(doc).forEach((k) => delete doc[k]); Object.assign(doc, r.doc); }, false); })
        .catch((e) => { const p = q('[data-yaml-problem]'); p.hidden = false; p.textContent = e.message + ' The canvas keeps the last version that parsed.'; });
    }, 600);
  }

  function check() {
    const box = q('[data-check-box]');
    B.commitFocus();
    return M.compose(true).then(() => {
      box.hidden = false;
      const list = B.clear(q('[data-problems]'));
      const ps = M.problems();
      ps.forEach((p) => list.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path || '(model)' }), ': ' + p.message)));
      if (!ps.length) { list.appendChild(h('li', { class: 'bi-ok', text: 'No problem: the model can be published.' })); }
      box.scrollIntoView({ block: 'nearest' });
    });
  }

  function save() {
    const btn = q('[data-save]');
    if (M.readOnly) { return Promise.resolve(); }
    B.commitFocus();                                             // the field being typed in is part of what is saved (it commits on blur, Ctrl+S comes first)
    window.clearTimeout(autoTimer);
    const rev = M.rev;
    return B.busy(btn, () => autoChain.then(() => M.compose(true)).then(() => B.saveItem(itemId, { body: M.yaml }, M.etag, btn, saveCopy))).then((it) => {
      M.item = Object.assign({}, M.item, it); M.etag = it.etag; M.conflict = false; M.savedDoc = B.clone(M.doc);
      if (M.rev === rev) { M.dirty = false; forget(); }
      buttons();
      const mv = q('[data-merge]');
      if (mv) { mv.remove(); }
      // a save on a published model makes a draft beside the published version: Publish and the state are worked out again from the item as it now is
      refreshItem();
      const wasLive = it.publishedVersion && it.status !== 'published';
      B.near(btn, 'Saved as a draft' + (wasLive ? ' (version ' + it.publishedVersion + ' stays published until you publish this one)' : '') + '. ' + (M.check && M.check.valid ? 'It checks clean: Publish when ready.' : 'Check lists what to fix before it can be published.'), false);
    }).catch((e) => { if (!e.cancelled) { B.near(btn, e.message, true); } });
  }
  // ---- server autosave (docs/architecture/BI_DESIGN.md section 8.1): each change is saved to the draft 2 s after the last one; the browser's journal
  //      (keep()) stays as the backup; a save someone else made since (409, DRS-9007) opens the merge view instead of overwriting ----------------------
  let autoTimer = null, autoChain = Promise.resolve();
  const AUTOSAVE_MS = Number(root.getAttribute('data-autosave-ms') || 2000);
  M.autosave = function () {
    if (M.readOnly || M.conflict || !M.item || !(AUTOSAVE_MS > 0)) { return; }
    window.clearTimeout(autoTimer);
    autoTimer = window.setTimeout(autoSaveNow, AUTOSAVE_MS);
  };
  function autoSaveNow() {
    if (M.readOnly || M.conflict || !M.dirty) { return autoChain; }
    const rev = M.rev;
    M.saving = true;
    buttons();
    autoChain = autoChain.then(() => M.compose(true)).then(() => B.api('PUT', '/items/' + B.id2path(itemId), { body: M.yaml, baseEtag: M.etag, message: 'Autosaved by the model designer' })).then((it) => {
      M.item = Object.assign({}, M.item, it); M.etag = it.etag; M.savedDoc = B.clone(M.doc);
      if (M.rev === rev) { M.dirty = false; forget(); }
      M.saving = false;
      buttons();
      q('[data-autosaved]').textContent = 'Draft saved at ' + new Date().toTimeString().slice(0, 8);
    }).catch((e) => {
      M.saving = false;
      if (e.code === 'DRS-9007') { M.conflict = true; buttons(); return mergeView(); }
      buttons();
      q('[data-autosaved]').textContent = 'Not saved automatically (' + e.message + '); your changes are kept in this browser. Save tries again.';
      return null;
    });
    return autoChain;
  }
  /** Saves now when there are unsaved changes (Preview as a role, Try it): resolves once the draft on the server is this one. */
  M.saveNow = () => { window.clearTimeout(autoTimer); return M.dirty ? autoSaveNow() : Promise.resolve(); };

  const SECTIONS = [['title', 'Title'], ['description', 'Description'], ['version', 'Version'], ['datasets', 'Datasets'], ['relationships', 'Relationships'], ['measures', 'Measures'],
    ['fields', 'Field properties'], ['perspectives', 'Perspectives'], ['security', 'Security'], ['contract', 'Contract'], ['tests', 'Tests'], ['lifecycle', 'Lifecycle'], ['layout', 'Canvas layout']];
  /** The merge view: theirs (the saved draft now) beside mine, part by part. A part only one side changed takes that side; a part both changed asks (mine first). */
  function mergeView() {
    const old = q('[data-merge]');
    if (old) { old.remove(); }
    return B.api('GET', '/items/' + B.id2path(itemId)).then((fresh) => B.layer.post('parse', { yaml: fresh.body || '' }).then((r) => {
      const theirs = r.doc, base = M.savedDoc || {}, mine = M.doc;
      const keys = SECTIONS.map((x) => x[0]).concat(Object.keys(Object.assign({}, theirs, mine)).filter((k) => !SECTIONS.some((x) => x[0] === k)));
      const rows = [], pick = {};
      keys.forEach((k) => {
        const t = JSON.stringify(theirs[k]), m = JSON.stringify(mine[k]), b = JSON.stringify(base[k]);
        if (t === m) { return; }
        const iChanged = m !== b, theyChanged = t !== b;
        pick[k] = !iChanged && theyChanged ? 'theirs' : 'mine';
        const label = (SECTIONS.find((x) => x[0] === k) || [k, k])[1];
        const radio = (v, words) => {
          const x = h('input', { type: 'radio', name: 'merge-' + k, value: v, checked: pick[k] === v, 'data-merge-pick': k + ':' + v });
          x.addEventListener('change', () => { pick[k] = v; });
          return h('label', { class: 'bi-checkrow' }, x, ' ' + words);
        };
        const show = (v) => h('pre', { class: 'bi-merge-val mono', text: v === undefined ? '(none)' : JSON.stringify(v, null, 1).slice(0, 600) });
        rows.push(h('tr', { 'data-merge-row': k, class: iChanged && theyChanged ? 'bi-merge-both' : '' },
          h('th', { scope: 'row' }, label, h('br'), h('small', { class: 'text-muted-d', text: iChanged && theyChanged ? 'both changed' : iChanged ? 'you changed' : 'they changed' })),
          h('td', {}, radio('theirs', 'theirs'), show(theirs[k])), h('td', {}, radio('mine', 'mine'), show(mine[k]))));
      });
      const go = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-merge-save': '', text: 'Merge and save' });
      const reload = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-merge-reload': '', text: 'Take theirs (reload)' });
      const over = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-merge-overwrite': '', text: 'Keep mine (overwrite)' });
      const box = h('section', { class: 'bi-sec bi-merge', 'data-merge': '', role: 'alert', 'aria-labelledby': 'mdMerge' },
        h('h2', { id: 'mdMerge', text: 'Saved elsewhere since you opened it' }),
        h('p', { text: (fresh.updatedBy || fresh.owner || 'Someone') + ' saved this model since' + (fresh.updatedAt ? ' (' + String(fresh.updatedAt).replace('T', ' ').slice(0, 16) + ')' : '') + '. Autosave has stopped so nothing of theirs is overwritten. Choose, part by part, what the draft keeps; your changes stay in this browser until you do.' }),
        rows.length ? h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl bi-merge-table', 'data-plain': '' }, h('caption', { class: 'visually-hidden', text: 'Theirs and mine, part by part' }),
          h('thead', {}, h('tr', {}, ['Part', 'Theirs', 'Mine'].map((x) => h('th', { scope: 'col', text: x })))), h('tbody', {}, rows))) : h('p', { text: 'The two drafts are the same now: Merge and save keeps it.' }),
        h('p', { class: 'bi-md-row' }, go, ' ', reload, ' ', over));
      q('[data-msg]').after(box);
      box.scrollIntoView({ block: 'nearest' });
      const finish = (doc) => {
        undo.push(B.clone(M.doc));
        M.doc = doc; M.etag = fresh.etag; M.base = fresh.body || ''; M.savedDoc = B.clone(theirs); M.conflict = false; M.dirty = true; M.rev = (M.rev || 0) + 1;
        box.remove(); keep(); buttons(); M.redraw();
        return autoSaveNow();
      };
      go.addEventListener('click', () => {
        const doc = B.clone(theirs);
        Object.keys(pick).forEach((k) => { if (pick[k] === 'mine') { if (mine[k] === undefined) { delete doc[k]; } else { doc[k] = B.clone(mine[k]); } } });
        finish(doc);
      });
      reload.addEventListener('click', () => { M.dirty = false; forget(); window.location.reload(); });
      over.addEventListener('click', () => finish(B.clone(mine)));
    })).catch((e) => M.say('Saved elsewhere since you opened it, and the saved version could not be read: ' + e.message, true));
  }

  /** The item read again after a save, a publish or a move, so Publish (or Propose), Move and the state follow what the server now says. */
  function refreshItem() {
    return B.api('GET', '/items/' + B.id2path(itemId)).then((fresh) => {
      if (M.dirty) { return; }
      M.item = fresh; M.etag = fresh.etag || M.etag; buttons();
    }).catch(() => { /* the save's own answer already drew the buttons */ });
  }
  M.refreshItem = refreshItem;
  function saveCopy() {
    const btn = q('[data-save-copy]').hidden ? q('[data-save]') : q('[data-save-copy]');
    return B.askWorkspace(btn, 'Save the copy', 'Save the copy into').then((to) => M.compose(true).then(() => B.api('POST', '/workspaces/' + encodeURIComponent(to) + '/items',
      { kind: 'model', title: ((M.item && M.item.title) || 'Model') + ' (copy)', description: (M.item && M.item.description) || '', body: M.yaml }))).then((it) => { M.dirty = false; forget(); window.location.href = '/bi/md/' + B.id2path(it.id); })
      .catch((e) => { if (!e.cancelled) { B.near(btn, e.message, true); } });
  }
  function publish() {
    const btn = q('[data-publish]');
    B.commitFocus();
    if (M.item && M.item.personal) { B.near(btn, 'This model is in your private workspace, which cannot publish. Use Move beside Publish to choose a shared workspace.', true); return Promise.resolve(); }
    const review = M.item && M.item.workspaceReviewRequired;
    const go = () => B.api('POST', '/items/' + B.id2path(itemId) + '/' + (review ? 'propose' : 'publish'), { note: 'From the model designer' }).then((it) => {
      M.item = it; M.etag = it.etag; M.lastRefusal = null; buttons();
      const rf = q('[data-publish-refusal]');
      if (rf) { rf.remove(); }
      B.near(btn, review ? 'Proposed for review: it is frozen until approved or withdrawn.' : 'Published. Reports can use ' + M.doc.model + '@' + String(M.doc.version || '1').split('.')[0] + '.', false);
    });
    const after = (p) => p.catch((e) => { refusal(e); });
    if (M.dirty) { return B.confirm(btn, 'Save the changes first, then ' + (review ? 'propose' : 'publish') + '?', 'Save and continue').then((yes) => (yes ? B.busy(btn, () => after(save().then(() => (M.dirty ? null : go())))) : null)); }
    return B.busy(btn, () => after(go()));
  }
  /** A refused publish, said where it stays (not a passing note): the code, the server's words, its problems, and the tab that fixes it. DRS-9126 (the
   *  contract needs a new version) opens Contract, which proposes the version; DRS-9130 (the safeguard) opens Security. */
  function refusal(e) {
    const old = q('[data-publish-refusal]');
    if (old) { old.remove(); }
    const tab = e.code === 'DRS-9126' || /DRS-9126/.test(e.message || '') ? 'contract' : e.code === 'DRS-9130' || /DRS-9130/.test(e.message || '') ? 'security' : '';
    const go = tab ? h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-refusal-open': tab, text: tab === 'contract' ? 'Open the Contract tab' : 'Open the Security tab' }) : null;
    const close = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Close', 'data-refusal-close': '' }, B.icon('x-lg'));
    const box = h('div', { class: 'bi-warn bi-md-refusal', role: 'alert', 'data-publish-refusal': e.code || '' }, h('strong', { text: 'Not published. ' }), e.message,
      (e.problems || []).length ? h('ul', {}, e.problems.slice(0, 8).map((p) => h('li', {}, h('code', { text: (p.code || '') + ' ' + (p.path || '') }), ' ' + (p.message || p.detail || '')))) : null,
      tab === 'contract' ? h('p', { class: 'bi-small-note', text: 'A change that removes or changes what consumers use needs the next major version: the Contract tab names the changes and proposes the version (Use version …), then Publish again.' }) : null,
      go, ' ', close);
    if (go) { go.addEventListener('click', () => showTab(tab)); }
    close.addEventListener('click', () => box.remove());
    q('[data-msg]').after(box);
    M.lastRefusal = e;
    if (tab) { showTab(tab); }
  }
  function startBoard() {
    const btn = q('[data-board]');
    const ref = M.doc.model + '@' + String(M.doc.version || '1').split('.')[0];
    const go = () => B.askWorkspace(btn, 'Start the board', 'Start the board in').then((ws) => {
      const name = B.slug((M.doc.title || M.doc.model) + ' board') || 'board';
      const body = 'rachana: 1\nreport: ' + name + '\ntitle: ' + JSON.stringify((M.doc.title || M.doc.model) + ' board') + '\nmodel: ' + ref + '\npages:\n  - id: page1\n    title: Page 1\n    layout: board\n    visuals: []\n';
      return B.api('POST', '/workspaces/' + encodeURIComponent(ws) + '/items', { kind: 'report', title: (M.doc.title || M.doc.model) + ' board', description: '', body });      // a board is a report with board pages (2.4.0)
    }).then((it) => { window.location.href = '/bi/design/' + B.id2path(it.id); }).catch((e) => { if (!e.cancelled) { B.near(btn, e.message, true); } });
    if (M.dirty) { return B.confirm(btn, 'A board reads the saved model. Save first?', 'Save and start').then((yes) => (yes ? save().then(() => (M.dirty ? null : go())) : null)); }
    return go();
  }
  function usedBy() {
    if (!M.doc) { return; }
    const ref = M.doc.model + '@' + String(M.doc.version || '1').split('.')[0];
    B.layer.drawUsedBy(q('[data-used-by-host]'), ref).then((r) => { q('[data-used-count]').textContent = r.known && r.consumers.length ? String(r.consumers.length) : ''; M.consumers = r; });
  }
  function startReport() {
    const btn = q('[data-report]');
    const ref = M.doc.model + '@' + String(M.doc.version || '1').split('.')[0];
    const go = () => B.askWorkspace(btn, 'Start the report', 'Start the report in').then((ws) => B.layer.post('report', { model: ref, title: (M.doc.title || M.doc.model) + ' report', workspace: ws }))
      .then((r) => { window.location.href = r.href; }).catch((e) => { if (!e.cancelled) { B.near(btn, e.message, true); } });
    if (M.dirty) { return B.confirm(btn, 'A report reads the saved model. Save first?', 'Save and start').then((yes) => (yes ? save().then(() => (M.dirty ? null : go())) : null)); }
    return go();
  }

  M.redraw = function () {
    if (!M.doc) { return; }
    q('[data-title]').textContent = M.doc.title || M.doc.model || 'Model';
    q('[data-version]').textContent = 'v' + (M.doc.version || '1.0.0');
    drawList();
    drawCanvas();
    drawRels();
    drawInspector();
    M.emit('redraw');
  };

  // ---- start ---------------------------------------------------------------------------------------------------------
  function restoreOffer(body) {
    let kept = null;
    try { kept = JSON.parse(window.localStorage.getItem(KEEP) || 'null'); } catch (e) { kept = null; }
    if (!kept || !kept.doc || M.readOnly) { return; }
    const box = q('[data-restore]');
    const yes = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-restore-yes': '', text: 'Restore my changes' });
    const no = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-restore-no': '', text: 'Discard them' });
    B.clear(box).append(h('span', { text: 'You have unsaved changes to this model from ' + new Date(kept.at).toLocaleString() + (kept.etag !== M.etag ? ' (someone has saved it since: restoring and saving will ask before overwriting)' : '') + '. ' }), yes, ' ', no);
    box.hidden = false;
    yes.addEventListener('click', () => { undo.push(B.clone(M.doc)); M.doc = kept.doc; M.etag = kept.etag || M.etag; M.dirty = true; M.rev = (M.rev || 0) + 1; box.hidden = true; buttons(); M.redraw(); M.compose(true); M.autosave(); });
    no.addEventListener('click', () => { forget(); box.hidden = true; });
    void body;
  }

  /** BI runs' rollup suggestions for this model (BI_RUNS.md section 8.3a), as a small notice linking to Admin → BI → Runs. Only a bi-admin can read them (and
   *  accept them), so for anyone else the notice simply never shows. */
  function rollupNotice(model) {
    if (!model) { return; }
    fetch('/bi/api/admin/runs', { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then((res) => (res.ok ? res.json() : null)).then((d) => {
      const open = ((d && d.suggestions) || []).filter((s) => s.model === model && (s.status || 'suggested') === 'suggested');
      if (!open.length) { return; }
      const runs = open.reduce((n, s) => n + (Number(s.runs) || 0), 0);
      const p = h('p', { class: 'bi-warn bi-md-suggest', role: 'note', 'data-md-suggest': String(open.length) },
        (open.length === 1 ? 'A heavy question on this model was' : open.length + ' heavy questions on this model were') + ' asked ' + runs + ' times this week: a rollup would answer '
        + (open.length === 1 ? 'it' : 'them') + ' at once. ', h('a', { href: '/admin/bi/runs?model=' + encodeURIComponent(model) + '#suggestions', text: 'Review the suggested rollups' }));
      q('[data-msg]').after(p);
    }).catch(() => {});
  }

  B.api('GET', '/items/' + B.id2path(itemId)).then((it) => {
    if (it.kind !== 'model') { throw new Error('This item is not a model (it is a ' + it.kind + ').'); }
    M.item = it;
    M.etag = it.etag;
    M.readOnly = !!it.shipped || it.status === 'proposed' || !(it.can && it.can.edit);
    q('[data-save]').disabled = M.readOnly;
    q('[data-save-copy]').hidden = !M.readOnly;
    publishState();
    if (M.readOnly) { const ro = q('[data-readonly]'); ro.hidden = false; ro.textContent = it.status === 'proposed' ? 'Proposed for review: frozen until approved or withdrawn (its item page). You can still look and save a copy.' : 'You can look at this model but not change it here; Save a copy makes a draft of your own.'; }
    M.base = it.body || '';                                      // the text the designer patches: its comments and order survive every edit (BI_DESIGN.md 8.2)
    return B.layer.post('parse', { yaml: it.body || '' });
  }).then((r) => {
    M.doc = r.doc;
    M.savedDoc = B.clone(r.doc);
    rollupNotice(M.doc.model || (M.item && M.item.name));
    M.doc.datasets = M.doc.datasets || {};
    const lay = (M.doc.layout = M.doc.layout || {});
    lay.positions = lay.positions || {};
    untangle(lay.positions);                                   // no box drawn over another at open (a layout the YAML never gave, or one that overlaps)
    if (window.CodeMirror) {
      editor = window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, lineWrapping: true, indentUnit: 2, tabSize: 2, readOnly: M.readOnly, viewportMargin: Infinity });
      editor.on('change', onYaml);
      editor.getInputField().setAttribute('aria-label', 'Model YAML (Ctrl+S saves; Escape then Tab leaves the editor)');
      editor.setOption('extraKeys', { Esc: () => { const t = q('[data-tab="yaml"]'); if (t) { t.focus(); } } });
    } else { ta.addEventListener('input', onYaml); ta.readOnly = M.readOnly; }
    q('[data-undo]').addEventListener('click', () => step(undo, redo));
    q('[data-redo]').addEventListener('click', () => step(redo, undo));
    q('[data-check]').addEventListener('click', check);
    q('[data-tidy]').addEventListener('click', () => { M.untangle(); B.near(q('[data-tidy]'), 'No box sits on another now (Undo puts them back).', false); });
    q('[data-check-close]').addEventListener('click', () => { q('[data-check-box]').hidden = true; });
    q('[data-save]').addEventListener('click', save);
    q('[data-save-copy]').addEventListener('click', saveCopy);
    q('[data-publish]').addEventListener('click', publish);
    q('[data-move]').addEventListener('click', moveModel);
    q('[data-report]').addEventListener('click', startReport);
    q('[data-board]').addEventListener('click', startBoard);
    document.addEventListener('keydown', (e) => {
      const k = e.key.toLowerCase();
      if ((e.ctrlKey || e.metaKey) && k === 's') { e.preventDefault(); save(); return; }
      if ((e.ctrlKey || e.metaKey) && !e.target.closest('input,textarea,select,.CodeMirror')) {
        if (k === 'z' && !e.shiftKey) { e.preventDefault(); step(undo, redo); } else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); step(redo, undo); }
      }
    });
    window.addEventListener('beforeunload', (e) => { if (M.dirty) { e.preventDefault(); e.returnValue = ''; } });
    window.addEventListener('resize', lines);
    buttons();
    restoreOffer();
    M.emit('load');
    return Promise.all([B.layer.get('datasets').then((d) => {
      catalogue = (d.packs || []).map((x) => Object.assign({ ref: x.name }, x)).concat((d.items || []).map((x) => Object.assign({ ref: x.id }, x)));
    }), M.compose(true)]);
  }).then(() => {
    M.redraw();
    usedBy();
    const want = new URLSearchParams(window.location.search);
    if (want.get('measure') && want.get('expr') && !M.readOnly && !(M.doc.measures || {})[want.get('measure')]) {          // a report's own measure proposed as a change to the model
      M.edit((doc) => { doc.measures = doc.measures || {}; doc.measures[want.get('measure')] = { expr: want.get('expr') }; }, true);
      showTab('measures');
      M.say('Proposed from a report: ' + want.get('measure') + ' is added to this draft. Check it, Save, and publish the model so every report can use it.');
    }
    if (B.studio && B.studio.attach && M.item) { B.studio.attach(M.item); }          // BI Studio (2.4.0): the model joins the open items
    root.setAttribute('data-ready', 'yes');
  }).catch((e) => { M.say(e.message, true); root.setAttribute('data-ready', 'error'); });
})();
