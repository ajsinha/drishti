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
/* UI Board P4, the Board Designer (UI_BOARD.md section 10): one board document, two editors. The canvas (ui-board-canvas.js) and the YAML / JSON
   tab edit the same object; a change in either redraws the other (the text view reads on pause, through the console's /ui/api-yaml calls, which
   keep the person's comments). Every change runs the board check (POST /ui/api/boards/check) and paints its problems red; with Live data on, the
   board is rendered as the person through the render API (POST /ui/api/render, debounced) and each box paints its chip. The mapping sheet of the
   selected instance: title, supply, supplier attribute -> pin column (only compatible attributes offered), controls; the board's parameters and
   its traces. Save is compare-and-set (409 DRS-5057: changed elsewhere, reload) and disabled while errors remain; revisions, share by link to
   named people, copy to a workspace. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, h = U.h, clear = U.clear;
  const root = document.querySelector('[data-ub-design]');
  if (!root) { return; }
  const $ = (s) => root.querySelector(s);
  const status = $('[data-ub-status]'), sheet = $('[data-ub-sheet]'), probList = $('[data-ub-problems]'), saveBtn = $('[data-ub-save]');
  const yamlBox = $('[data-ub-yaml]'), yamlNote = $('[data-ub-yaml-note]'), nameIn = $('[data-ub-name]'), titleIn = $('[data-ub-title]');
  const PIN_TYPES = { id: ['id'], dimension: ['dimension', 'id', 'text', 'date'], measure: ['measure'], date: ['date'], text: ['text', 'dimension', 'id', 'date', 'measure'], number: ['measure'] };

  const D = {
    root, live: false, device: 'desktop', etag: null, problems: [], chips: {}, selected: null, yaml: '',
    name: root.getAttribute('data-board') || '', where: {},
    doc: { rachana: 1, board: '', title: '', chips: {}, traces: [], layout: { desktop: { columns: 12, areas: [] }, phone: { order: [], collapsed: [] } } }
  };
  ['owner', 'workspace'].forEach((k) => { const v = root.getAttribute('data-' + k); if (v) { D.where[k] = v; } });

  D.say = (text, bad) => { status.textContent = text || ''; status.classList.toggle('bi-bad', !!bad); };
  D.params = {};                                   // the values the designer previews the board with (board parameter -> value); never saved
  /** One short line for what the board was opened from; the board-local chips (chip, nearest Chip Store chips, why) and any other notes
      fold into a Details disclosure under it, so the canvas stays near the top. */
  const notesBox = $('[data-ub-notes]');
  D.notes = (line, locals, notes) => {
    locals = locals || []; notes = notes || [];
    const cands = locals.filter((l) => l.chip !== 'local-header');
    D.say(line + (cands.length ? ' ' + cands.length + ' chip' + (cands.length > 1 ? 's are' : ' is') + ' board-local: Chip Store candidates.' : '')
      + (notes.length ? ' ' + notes.length + ' note' + (notes.length > 1 ? 's' : '') + '.' : ''));
    if (!notesBox) { return; }
    const body = notesBox.querySelector('[data-ub-notes-body]');
    clear(body);
    notesBox.hidden = !cands.length && !notes.length;
    notesBox.open = false;
    if (cands.length) {
      const tb = h('tbody');
      cands.forEach((l) => tb.appendChild(h('tr', {}, h('td', { class: 'mono', text: l.chip }), h('td', { class: 'mono', text: (l.near || []).join(', ') || '-' }), h('td', { text: l.why || '' }))));
      body.appendChild(h('table', { class: 'tbl ub-notes-tbl', 'data-no-chart': true }, h('thead', {}, h('tr', {}, h('th', { text: 'Chip' }), h('th', { text: 'Nearest Chip Store chips' }), h('th', { text: 'Why it stays local' }))), tb));
    }
    if (notes.length) { body.appendChild(h('ul', { class: 'ub-notes-list' }, notes.map((n) => h('li', { text: n })))); }
  };
  /** The required board parameters with no value to preview with (neither a default nor a value typed in the designer). */
  D.missingParams = () => Object.keys(D.doc.params || {}).filter((p) => (D.doc.params[p] || {}).required && D.params[p] == null && (D.doc.params[p] || {}).default == null);
  /** Live data on by default when the board can be fed: a chip has a supply and every required parameter has a value (the render API still
      checks the person may read it). Otherwise each box says what is missing (D.emptyState). */
  D.autoLive = () => {
    const fed = Object.keys(D.doc.chips || {}).some((n) => { const s = D.doc.chips[n].supply || {}; return s.connector || s.model || s.live; });
    D.live = fed && !D.missingParams().length;
    $('[data-ub-live]').checked = D.live;
  };
  /** What a box shows while live data is off: why, and the shortest way to data. */
  D.emptyState = (inst) => {
    const i = D.doc.chips[inst] || {}, s = i.supply || {}, fedByTrace = (D.doc.traces || []).some((t) => t.to.split('.')[0] === inst);
    if (!(s.connector || s.model || s.live) && !fedByTrace) { return h('p', { class: 'text-muted-d ub-hint ub-empty-state', text: 'Map a supplier to see data.' }); }
    const miss = D.missingParams();
    if (miss.length) {
      const p = miss[0], inp = h('input', { class: 'studio-in mono ub-param-quick', 'data-ub-param-quick': p, placeholder: p, 'aria-label': 'Board parameter ' + p });
      inp.addEventListener('change', () => { if (inp.value.trim()) { D.params[p] = inp.value.trim(); D.autoLive(); C.draw(); fillSheet(); D.liveRender(); } });
      return h('div', { class: 'ub-hint ub-empty-state' }, h('span', { class: 'text-muted-d' }, 'Set board parameter ', h('code', { text: p }), ' to see data '), inp);
    }
    return h('p', { class: 'text-muted-d ub-hint ub-empty-state', text: 'Turn on Live data to see data.' });
  };
  /** A same-origin call; rejects with the server's {code, detail, problems, status}. raw: send the body as it is (YAML). */
  D.api = (method, url, body, headers) => fetch(url, {
    method, credentials: 'same-origin', headers: Object.assign(body != null ? { 'Content-Type': 'application/json' } : {}, headers || {}),
    body: body == null ? undefined : JSON.stringify(body)
  }).then((r) => r.text().then((t) => {
    let b = null; try { b = t ? JSON.parse(t) : null; } catch (e) { b = { detail: t }; }
    if (!r.ok) { b = b || {}; b.status = r.status; b.code = b.code || 'HTTP-' + r.status; throw b; }
    if (b && typeof b === 'object') { Object.defineProperty(b, '__etag', { value: r.headers.get('ETag'), enumerable: false }); }
    return b;
  }));
  const where = () => { const q = new URLSearchParams(D.where).toString(); return q ? '?' + q : ''; };

  // ---- chips ------------------------------------------------------------------------------------------------------------------------------
  D.chipDoc = (ref) => D.chips[ref] && D.chips[ref].document;
  function fetchChip(ref) {
    if (D.chips[ref]) { return Promise.resolve(D.chips[ref]); }
    const m = /^([a-z0-9-]+)@(\d+)$/.exec(ref || '');
    if (!m) { return Promise.resolve(null); }
    return D.api('GET', '/ui/api/chips/' + m[1] + '/' + m[2]).then((c) => { D.chips[ref] = c; return c; }).catch(() => null);
  }
  D.fetchChip = fetchChip;
  const fetchAll = () => Promise.all(Object.keys(D.doc.chips || {}).map((n) => fetchChip(D.doc.chips[n].use)));

  // ---- editing the document ---------------------------------------------------------------------------------------------------------------
  function areas() { const l = D.doc.layout = D.doc.layout || {}; l.desktop = l.desktop || { columns: 12, areas: [] }; l.desktop.areas = l.desktop.areas || []; return l.desktop.areas; }
  D.placeNew = (inst, at) => {
    const list = areas(), bottom = list.reduce((m, a) => Math.max(m, a.at[1] + a.span[1]), 1);
    const a = { chip: inst, at: at || [1, bottom], span: [6, 4] };
    a.at[0] = Math.min(a.at[0], (D.doc.layout.desktop.columns || 12) - 5);
    list.push(a);
    return a;
  };
  D.addInstance = (ref, at) => {
    fetchChip(ref).then((c) => {
      const base = ref.split('@')[0].replace(/^panel-|^bi-/, '');
      let inst = base, n = 2;
      while (D.doc.chips[inst]) { inst = base + '-' + n++; }
      D.doc.chips[inst] = { use: ref };
      D.placeNew(inst, at);
      const ph = D.doc.layout.phone = D.doc.layout.phone || { order: [], collapsed: [] };
      ph.order = (ph.order || []).concat([inst]);
      D.changed('chips');
      D.select(inst);
      D.say('Added ' + inst + ' (' + ref + ')' + (c ? '' : ': its document could not be read'));
      const b = C.boxOf(inst); if (b) { b.focus(); }
    });
  };
  D.removeInstance = (inst) => {
    delete D.doc.chips[inst];
    D.doc.traces = (D.doc.traces || []).filter((t) => t.from.split('.')[0] !== inst && t.to.split('.')[0] !== inst);
    D.doc.layout.desktop.areas = areas().filter((a) => a.chip !== inst);
    const ph = D.doc.layout.phone || {};
    ph.order = (ph.order || []).filter((x) => x !== inst); ph.collapsed = (ph.collapsed || []).filter((x) => x !== inst);
    if (D.selected === inst) { D.selected = null; }
    D.changed('chips');
    D.say('Removed ' + inst);
  };
  function renameInstance(old, name) {
    if (!/^[a-z][a-z0-9-]*$/.test(name) || D.doc.chips[name]) { D.say('An instance name is lower-case letters, digits and dashes, and new on this board.', true); return false; }
    const chips = {};
    Object.keys(D.doc.chips).forEach((k) => { chips[k === old ? name : k] = D.doc.chips[k]; });
    D.doc.chips = chips;
    const re = (s) => (s.split('.')[0] === old ? name + s.slice(old.length) : s);
    (D.doc.traces || []).forEach((t) => { t.from = re(t.from); t.to = re(t.to); });
    areas().forEach((a) => { if (a.chip === old) { a.chip = name; } });
    const ph = D.doc.layout.phone || {};
    ph.order = (ph.order || []).map((x) => (x === old ? name : x)); ph.collapsed = (ph.collapsed || []).map((x) => (x === old ? name : x));
    D.selected = name;
    return true;
  }
  /** The data pin of an instance's chip and its first id column: the column a selection filters on (`filter-on: rows.trade`). */
  function idColumn(inst) {
    const c = D.chipDoc((D.doc.chips[inst] || {}).use) || {}, ins = (c.pins || {}).in || {};
    for (const p of Object.keys(ins)) {
      const shape = ins[p].shape || {};
      const cols = Object.keys(shape).filter((k) => (shape[k].type || '') === 'id');
      const pick = cols.find((k) => k !== 'id') || cols[0];
      if (pick && ins[p].role === 'data') { return p + '.' + pick; }
    }
    return null;
  }
  D.addTrace = (from, to, fromType, toType) => {
    const tr = D.doc.traces = D.doc.traces || [];
    if (tr.some((t) => t.from === from && t.to === to)) { D.say('That trace is drawn already.'); return; }
    const t = { from, to };
    if (toType === 'filter' && fromType !== 'filter') { const col = idColumn(to.split('.')[0]); if (col) { t.transform = { 'filter-on': col }; } }
    tr.push(t);
    D.changed('traces');
    D.say('Trace ' + from + ' → ' + to + (t.transform ? ' (filters on ' + t.transform['filter-on'] + ')' : ''));
  };
  D.removeTrace = (i) => { const t = D.doc.traces.splice(i, 1)[0]; D.changed('traces'); D.say('Removed the trace ' + t.from + ' → ' + t.to); };

  // ---- problems ---------------------------------------------------------------------------------------------------------------------------
  D.problemsOf = (prefix) => D.problems.filter((p) => (p.path || '') === prefix || (p.path || '').indexOf(prefix + '.') === 0);
  D.problemAt = (prefix) => D.problems.find((p) => (p.path || '').indexOf(prefix) === 0) || null;
  function showProblems() {
    clear(probList);
    const errors = D.problems.filter((p) => p.level !== 'warning');
    D.problems.forEach((p) => {
      const b = h('button', { type: 'button', class: 'ub-prob ub-prob-' + (p.level || 'error'), 'data-rule': p.rule, 'data-path': p.path || '' },
        h('strong', { text: p.rule }), ' ', h('span', { class: 'mono', text: p.path || 'board' }), ': ' + p.message + (p.fix ? ' · Fix: ' + p.fix : ''));
      b.addEventListener('click', () => C.focusPath(p.path));
      probList.appendChild(h('li', {}, b));
    });
    $('[data-ub-problem-count]').textContent = errors.length ? errors.length + ' error' + (errors.length === 1 ? '' : 's') : (D.problems.length ? 'warnings only' : 'no problems');
    saveBtn.disabled = errors.length > 0 || !nameIn.value.trim();
    saveBtn.title = errors.length ? 'Fix the errors first (the Problems list below)' : '';
    C.marks();
    root.dispatchEvent(new CustomEvent('ub:checked'));
  }
  let checkTimer = 0, checkSeq = 0;
  function check() {
    if (D.composite) { D.problems = D.composite.problems ? D.composite.problems() : []; showProblems(); return Promise.resolve(); }   // the chip's own check
    const seq = ++checkSeq;
    return D.api('POST', '/ui/api/boards/check' + (D.where.workspace ? '?workspace=' + encodeURIComponent(D.where.workspace) : ''), docForServer())
      .then((r) => { if (seq === checkSeq) { D.problems = (r && r.problems) || []; showProblems(); } })
      .catch((e) => { if (seq === checkSeq) { D.problems = e.problems || [{ rule: e.code, level: 'error', path: '', message: e.detail || 'the check failed' }]; showProblems(); } });
  }
  function docForServer() { const d = JSON.parse(JSON.stringify(D.doc)); d.board = nameIn.value.trim() || d.board || 'untitled'; if (titleIn.value.trim()) { d.title = titleIn.value.trim(); } return d; }

  // ---- live data --------------------------------------------------------------------------------------------------------------------------
  let liveTimer = 0, current = null;
  function liveRender() {
    if (current) { current.close(); current = null; }
    if (D.composite || !D.live || D.device === 'phone' || !Object.keys(D.doc.chips).length || D.problems.some((p) => p.level !== 'warning')) {
      if (D.live && D.problems.some((p) => p.level !== 'warning')) { D.say('Live data waits until the board check passes.'); }
      return;
    }
    current = U.render($('[data-ub-canvas]'), { board: docForServer() }, { device: 'desktop', status: $('[data-ub-live-status]') || status, params: D.params, bodyOf: (inst) => C.boxOf(inst),
      onEvent: (ev, d) => { if ((ev === 'chip' || ev === 'frame') && d && d.instance && (ev === 'frame' || d.state === 'data')) { C.flow(d.instance); } },
      onRefused: (b) => { if (b.problems) { D.problems = b.problems; showProblems(); } } });
  }

  D.liveRender = liveRender;            // the Page view (ui-board-page.js) takes the paint while the pin view is closed
  D.docForServer = () => docForServer();

  // ---- one document, two editors ---------------------------------------------------------------------------------------------------------
  let yamlTimer = 0, fromYaml = false;
  /** The focused control, as a selector that finds it again after a redraw (keyboard use keeps its place). */
  function focusKey() {
    const a = document.activeElement;
    if (!a || !root.contains(a)) { return null; }
    for (const k of ['data-map', 'data-control', 'data-ub-inst-title', 'data-ub-inst-name', 'data-ub-supply-kind', 'data-ub-supply-name', 'data-ub-supply-query', 'data-pin', 'data-instance']) {
      if (a.hasAttribute(k)) { const v = a.getAttribute(k); return '[' + k + (v ? '="' + CSS.escape(v) + '"' : '') + ']' + (k === 'data-pin' ? '.ub-' + a.getAttribute('data-dir') : ''); }
    }
    return null;
  }
  // Undo and Redo: a snapshot of the document after each change (the workbench's Ctrl+Z / Ctrl+Y)
  const undo = { list: [], at: -1 }, undoBtn = $('[data-ub-undo]'), redoBtn = $('[data-ub-redo]');
  function snap() {
    const t = JSON.stringify(D.doc);
    if (undo.list[undo.at] === t) { return; }
    undo.list = undo.list.slice(0, undo.at + 1).concat([t]).slice(-100);
    undo.at = undo.list.length - 1;
    undoButtons();
  }
  function undoButtons() { undoBtn.disabled = undo.at <= 0; redoBtn.disabled = undo.at >= undo.list.length - 1; }
  function step(d) {
    const at = undo.at + d;
    if (at < 0 || at >= undo.list.length) { return; }
    undo.at = at;
    D.doc = JSON.parse(undo.list[at]);
    titleIn.value = D.doc.title || '';
    undoButtons();
    restoring = true;
    fetchAll().then(() => { D.changed('chips'); restoring = false; D.say(d < 0 ? 'Undone.' : 'Redone.'); });
  }
  let restoring = false;
  undoBtn.addEventListener('click', () => step(-1));
  redoBtn.addEventListener('click', () => step(1));
  window.addEventListener('keydown', (e) => {
    if (D.mode !== 'board' || !(e.ctrlKey || e.metaKey) || /input|textarea/i.test(e.target.tagName)) { return; }
    if (e.key === 'z' && !e.shiftKey) { e.preventDefault(); step(-1); } else if (e.key === 'y' || (e.key === 'z' && e.shiftKey) || e.key === 'Z') { e.preventDefault(); step(1); }
  });
  /** Inside a composite chip (P4b): the canvas edits the chip's inner board; ui-chip-module.js enters and leaves. */
  D.edit = (doc) => { D.doc = doc; D.doc.chips = D.doc.chips || {}; D.doc.traces = D.doc.traces || []; D.doc.layout = D.doc.layout || {}; undo.list = []; undo.at = -1; return fetchAll().then(() => { D.changed('chips'); }); };
  D.changed = (what) => {
    root.setAttribute('data-dirty', 'true');
    if (!restoring) { snap(); }
    if (what === 'layout') { C.wires(); } else if (what !== 'map') {
      const keep = focusKey();
      C.draw(); fillSheet();
      const back = keep && root.querySelector(keep);
      if (back) { back.focus(); }
    }
    if (!fromYaml) { writeYaml(); }
    clearTimeout(checkTimer);
    checkTimer = setTimeout(() => check().then(() => { clearTimeout(liveTimer); liveTimer = setTimeout(() => D.liveRender(), 400); }), 300);
  };
  function writeYaml() {
    return D.api('POST', '/ui/api-yaml/dump', { document: docForServer(), yaml: D.yaml }).then((r) => {
      D.yaml = r.yaml;
      if (document.activeElement !== yamlBox && D.mode === 'board') { yamlBox.value = r.yaml; }
    }).catch(() => {});
  }
  yamlBox.addEventListener('input', () => {
    if (D.mode !== 'board') { return; }
    clearTimeout(yamlTimer);
    yamlTimer = setTimeout(() => {
      const text = yamlBox.value;
      D.api('POST', '/ui/api-yaml/load', { yaml: text }).then((r) => {
        yamlNote.textContent = 'In step with the canvas.'; yamlNote.classList.remove('bi-bad');
        D.yaml = text;
        const doc = r.document || {};
        doc.chips = doc.chips || {}; doc.traces = doc.traces || []; doc.layout = doc.layout || {};
        D.doc = doc;
        if (doc.board) { nameIn.value = doc.board; }
        titleIn.value = doc.title || '';
        fromYaml = true;
        fetchAll().then(() => { D.changed('chips'); fromYaml = false; });
      }).catch((e) => { yamlNote.textContent = (e.line ? 'Line ' + e.line + ', column ' + e.column + ': ' : '') + (e.detail || 'not YAML'); yamlNote.classList.add('bi-bad'); });
    }, 600);
  });

  // ---- the mapping sheet ------------------------------------------------------------------------------------------------------------------
  const attrCache = {};
  D.attributes = (supply) => attributes(supply);
  function attributes(supply) {
    const key = JSON.stringify(supply || {});
    if (!supply || !(supply.connector || supply.model || supply.live)) { return Promise.resolve(null); }
    if (!attrCache[key]) {
      const q = new URLSearchParams();
      ['connector', 'model', 'live'].forEach((k) => { if (supply[k]) { q.set(k, supply[k]); } });
      attrCache[key] = D.api('GET', '/ui/api-suppliers?' + q.toString()).then((r) => r.attributes || null).catch(() => null);
    }
    return attrCache[key];
  }
  function field(label, input, hint) { return h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l', text: label }), input, hint ? h('small', { class: 'text-muted-d', text: hint }) : null); }

  D.select = (inst) => {
    if (D.selected === inst) { return; }
    D.selected = inst;
    root.querySelectorAll('.ub-box').forEach((b) => b.classList.toggle('ub-selected', b.getAttribute('data-instance') === inst));
    fillSheet();
    root.dispatchEvent(new CustomEvent('ub:select', { detail: inst }));          // the palette's "Fits this data" follows the selection
  };
  D.openSheet = (inst) => { D.select(inst); const f = sheet.querySelector('input, select, textarea, button'); if (f) { f.focus(); } };

  function fillSheet() {
    clear(sheet);
    const inst = D.selected, i = inst && D.doc.chips[inst];
    if (i) {
      const chip = D.chipDoc(i.use) || {}, pins = chip.pins || {};
      sheet.appendChild(h('h3', { text: 'Mapping sheet: ' + inst }));
      sheet.appendChild(h('p', { class: 'text-muted-d', text: i.use + (chip.manual && chip.manual.purpose ? ' · ' + chip.manual.purpose : '') }));
      const nm = h('input', { class: 'studio-in', value: inst, 'data-ub-inst-name': true });
      nm.addEventListener('change', () => { if (renameInstance(inst, nm.value.trim())) { D.changed('chips'); } else { nm.value = inst; } });
      sheet.appendChild(field('Instance name', nm));
      const ti = h('input', { class: 'studio-in', value: i.title || '', placeholder: chip.title || '', 'data-ub-inst-title': true });
      ti.addEventListener('change', () => { if (ti.value.trim()) { i.title = ti.value.trim(); } else { delete i.title; } D.changed('chips'); });
      sheet.appendChild(field('Title', ti));
      // supply
      const sup = i.supply || {}, kind = ['connector', 'model', 'live'].find((k) => sup[k]) || '';
      const ks = h('select', { class: 'studio-select', 'data-ub-supply-kind': true }, ['', 'connector', 'model', 'live'].map((k) => h('option', { value: k, selected: k === kind, text: k ? { connector: 'Pack connector or search', model: 'BI model', live: 'Live topic' }[k] : 'None (fed by traces)' })));
      const sn = h('input', { class: 'studio-in mono', value: kind ? sup[kind] : '', placeholder: 'trading/trade-search', 'data-ub-supply-name': true });
      const sq = h('textarea', { class: 'studio-in mono ub-query', rows: '2', 'data-ub-supply-query': true, placeholder: '{"book": "%{board.params.book}"}' });
      sq.value = sup.query ? JSON.stringify(sup.query) : '';
      const setSupply = () => {
        const k = ks.value;
        if (k && !sn.value.trim()) { sn.focus(); return; }          // the kind first, then its name: nothing changes until both are there
        if (!k) { delete i.supply; } else {
          const s = {}; s[k] = sn.value.trim();
          if (sq.value.trim()) { try { s.query = JSON.parse(sq.value); } catch (e) { D.say('The query is JSON: ' + e.message, true); return; } }
          i.supply = s;
        }
        D.changed('chips');
      };
      [ks, sn, sq].forEach((x) => x.addEventListener('change', setSupply));
      if (D.composite) { [ks, sn, sq].forEach((x) => { x.disabled = true; }); }      // inside a composite chip: no connectors, data enters through its pins
      sheet.appendChild(h('fieldset', { class: 'ub-fs' }, h('legend', { text: D.composite ? 'Supply: none inside a chip (its data enters through the chip\'s pins)' : 'Supply (read as the viewer)' }), field('Supplier', ks), field('Name', sn), field('Query (JSON, optional)', sq)));
      // the map: supplier attribute -> pin column
      const fed = new Set((D.doc.traces || []).filter((t) => t.to.split('.')[0] === inst).map((t) => t.to.split('.').slice(1).join('.')));
      const mapFs = h('fieldset', { class: 'ub-fs', 'data-ub-map': true }, h('legend', { text: 'Map: supplier attribute → pin' }));
      sheet.appendChild(mapFs);
      attributes(i.supply).then((attrs) => {
        Object.keys(pins.in || {}).forEach((p) => {
          const spec = pins.in[p];
          if (spec.role !== 'data' || fed.has(p)) { return; }
          const shape = spec.shape || {};
          const keys = Object.keys(shape).length ? Object.keys(shape).map((c) => [p + '.' + c, shape[c]]) : [[p, spec]];
          keys.forEach(([key, cs]) => {
            const t = (typeof cs.type === 'string' ? cs.type : '') || 'text', many = !!cs.many, now = (i.map || {})[key];
            const nowList = now == null ? [] : (Array.isArray(now) ? now : [now]);
            let input;
            if (attrs) {
              const ok = attrs.filter((a) => (PIN_TYPES[t] || [t]).indexOf(a.type) >= 0);
              input = h('select', { class: 'studio-select', multiple: many, size: many ? String(Math.min(6, Math.max(2, ok.length))) : null, 'data-map': key },
                many ? null : h('option', { value: '', text: cs.required ? 'Choose (required)' : '(not mapped)' }),
                ok.map((a) => h('option', { value: a.name, selected: nowList.indexOf(a.name) >= 0, text: a.name + (a.label && a.label !== a.name ? ' · ' + a.label : '') + ' (' + a.type + ')' })));
            } else {
              input = h('input', { class: 'studio-in mono', value: nowList.join(', '), 'data-map': key, placeholder: many ? 'NOTIONAL, MTM_USD' : 'TRADE_ID' });
            }
            input.addEventListener('change', () => {
              const vals = input.tagName === 'SELECT' ? Array.from(input.selectedOptions).map((o) => o.value).filter(Boolean) : input.value.split(',').map((s) => s.trim()).filter(Boolean);
              i.map = i.map || {};
              if (!vals.length) { delete i.map[key]; } else { i.map[key] = many ? vals : vals[0]; }
              if (!Object.keys(i.map).length) { delete i.map; }
              D.changed('map');
            });
            const bad = D.problemsOf('chips.' + inst + '.map.' + key)[0];
            mapFs.appendChild(field(key + ' (' + t + (many ? ', many' : '') + (cs.required ? ', required' : '') + ')', input, bad ? bad.rule + ': ' + bad.message : (attrs ? '' : 'No attribute list for this supplier: type the names.')));
          });
        });
        if (mapFs.childNodes.length === 1) { mapFs.appendChild(h('p', { class: 'text-muted-d', text: 'Every data pin is fed by a trace.' })); }
      });
      // controls
      const ctl = Object.keys(pins.in || {}).filter((p) => pins.in[p].role === 'control' && !fed.has(p));
      if (ctl.length) {
        const fs = h('fieldset', { class: 'ub-fs' }, h('legend', { text: 'Controls (constants)' }));
        ctl.forEach((p) => {
          const inp = h('input', { class: 'studio-in', value: (i.controls || {})[p] == null ? '' : String(i.controls[p]), placeholder: pins.in[p].default == null ? '' : String(pins.in[p].default), 'data-control': p });
          inp.addEventListener('change', () => { i.controls = i.controls || {}; if (inp.value === '') { delete i.controls[p]; } else { i.controls[p] = /^\d+$/.test(inp.value) ? +inp.value : inp.value; } if (!Object.keys(i.controls).length) { delete i.controls; } D.changed('map'); });
          fs.appendChild(field(p + ' (' + ((pins.in[p].type) || '') + ')', inp));
        });
        sheet.appendChild(fs);
      }
      const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Remove ' + inst });
      rm.addEventListener('click', () => D.removeInstance(inst));
      sheet.appendChild(rm);
    } else {
      sheet.appendChild(h('p', { class: 'text-muted-d', text: 'Select a chip on the canvas to map its pins.' }));
    }
    // the board's parameters and traces
    const ps = D.doc.params || {};
    const pfs = h('fieldset', { class: 'ub-fs' }, h('legend', { text: 'Board parameters' }));
    Object.keys(ps).forEach((p) => {
      const spec = ps[p] || {};
      const x = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Remove the parameter ' + p, text: '×' });
      x.addEventListener('click', () => { delete ps[p]; delete D.params[p]; if (!Object.keys(ps).length) { delete D.doc.params; } D.changed('params'); });
      const val = h('input', { class: 'studio-in mono', 'data-ub-param-value': p, value: D.params[p] != null ? D.params[p] : '', placeholder: spec.default != null ? String(spec.default) : (spec.required ? 'required' : 'optional'),
        'aria-label': 'Preview value of ' + p });
      val.addEventListener('change', () => { if (val.value.trim()) { D.params[p] = val.value.trim(); } else { delete D.params[p]; } D.autoLive(); C.draw(); D.liveRender(); });
      const what = (spec.type || 'text') + (spec.of ? ' of ' + spec.of : '') + (spec.required ? ', required' : '') + (spec.default != null ? ', default ' + spec.default : '');
      pfs.appendChild(h('div', { class: 'ub-param', 'data-ub-param': p }, h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l' }, h('b', { class: 'mono', text: p }), ' · ' + what), h('span', { class: 'ub-row' }, val, x))));
    });
    const pn = h('input', { class: 'studio-in mono', placeholder: 'as-of', 'aria-label': 'New parameter name' }), pt = h('select', { class: 'studio-select', 'aria-label': 'Its type' }, ['date', 'id', 'text', 'number'].map((t) => h('option', { value: t, text: t })));
    const pd = h('input', { class: 'studio-in', placeholder: 'default', 'aria-label': 'Its default' }), pa = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Add parameter' });
    pa.addEventListener('click', () => { const n = pn.value.trim(); if (!/^[a-z][a-z0-9-]*$/.test(n)) { D.say('A parameter name is lower-case letters, digits and dashes.', true); return; } D.doc.params = D.doc.params || {}; D.doc.params[n] = { type: pt.value }; if (pd.value) { D.doc.params[n].default = pd.value; } D.changed('params'); });
    pfs.appendChild(h('div', { class: 'ub-row' }, pn, pt, pd, pa));
    sheet.appendChild(pfs);
    const tfs = h('fieldset', { class: 'ub-fs', 'data-ub-traces': true }, h('legend', { text: 'Traces' }));
    (D.doc.traces || []).forEach((t, k) => {
      const bad = D.problemAt('traces[' + k + ']');
      const x = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Remove the trace ' + t.from + ' to ' + t.to, text: '×' });
      x.addEventListener('click', () => D.removeTrace(k));
      tfs.appendChild(h('p', { class: 'mono' + (bad ? ' ub-bad-text' : ''), 'data-trace-row': String(k) }, t.from + ' → ' + t.to + (t.transform ? ' ' + JSON.stringify(t.transform) : '') + (bad ? ' · ' + bad.rule + ': ' + bad.message : '') + ' ', x));
    });
    if (!(D.doc.traces || []).length) { tfs.appendChild(h('p', { class: 'text-muted-d', text: 'Drag from an out pin (right edge) to an in pin (left edge).' })); }
    sheet.appendChild(tfs);
  }

  // ---- load, save, revisions, share, copy ---------------------------------------------------------------------------------------------------
  function load() {
    const fromDesign = root.getAttribute('data-design'), example = root.getAttribute('data-example');
    if (!D.name && example && D.mode === 'board') {        // a board example as your unsaved copy (/build/new#examples)
      return D.api('GET', '/build/board-api/example/' + encodeURIComponent(example)).then((r) => {
        D.doc = r.document; D.doc.chips = D.doc.chips || {}; D.doc.traces = D.doc.traces || []; D.doc.layout = D.doc.layout || {};
        nameIn.value = D.doc.board || ''; titleIn.value = D.doc.title || '';
        return fetchAll().then(() => { D.autoLive(); C.draw(); fillSheet(); writeYaml(); check().then(() => D.liveRender()); snap(); D.notes('Your copy of the example ' + (r.title || example) + ' (not saved yet).', [], r.notes); });
      }).catch((e) => D.say(e.code + ': ' + e.detail, true));
    }
    if (!D.name && fromDesign) {                  // a screen design opened as a board (the adapter until P5's converter)
      return D.api('GET', '/build/board-api/from-design/' + encodeURIComponent(fromDesign)).then((r) => {
        D.doc = r.document; nameIn.value = D.doc.board || ''; titleIn.value = D.doc.title || '';
        D.params = Object.assign({}, r.params || {});            // the id of the design's sample, so data shows at once
        return fetchAll().then(() => { D.autoLive(); C.draw(); fillSheet(); writeYaml(); check().then(() => D.liveRender()); snap(); D.notes('Opened as a board (not saved).', r.locals, r.notes); });
      }).catch((e) => D.say(e.code + ': ' + e.detail, true));
    }
    if (!D.name) { nameIn.value = ''; C.draw(); fillSheet(); writeYaml(); check(); snap(); return Promise.resolve(); }
    D.say('Loading ' + D.name + '…');
    return D.api('GET', '/ui/api/boards/' + encodeURIComponent(D.name) + where()).then((b) => {
      D.etag = b.__etag || b.etag || null;
      D.doc = b.document || D.doc;
      D.doc.chips = D.doc.chips || {}; D.doc.traces = D.doc.traces || []; D.doc.layout = D.doc.layout || {};
      D.yaml = b.yaml || '';
      yamlBox.value = D.yaml;
      nameIn.value = D.name; nameIn.readOnly = true;
      titleIn.value = D.doc.title || '';
      root.setAttribute('data-revision', String(D.doc.version || ''));
      return fetchAll().then(() => { D.autoLive(); C.draw(); fillSheet(); check().then(() => D.liveRender()); revisions(); undo.list = []; undo.at = -1; snap(); D.say('Opened ' + D.name + ', revision ' + (D.doc.version || 1)); root.removeAttribute('data-dirty'); });
    }).catch((e) => D.say(e.code + ': ' + e.detail, true));
  }

  function save() {
    const name = nameIn.value.trim();
    if (!/^[a-z0-9][a-z0-9-]*$/.test(name)) { D.say('A board name is lower-case letters, digits and dashes.', true); nameIn.focus(); return; }
    const headers = D.etag && D.name === name ? { 'If-Match': D.etag } : { 'If-None-Match': '*' };
    const q = D.where.workspace ? '?workspace=' + encodeURIComponent(D.where.workspace) : '';
    D.say('Saving…');
    D.api('PUT', '/ui/api/boards/' + encodeURIComponent(name) + q, docForServer(), headers).then((b) => {
      D.etag = b.__etag || b.etag || D.etag;
      const firstSave = D.name !== name;
      D.name = name; nameIn.readOnly = true;
      if (b.document) { D.doc.version = b.document.version; }
      root.removeAttribute('data-dirty');
      root.setAttribute('data-revision', String(D.doc.version || ''));
      $('[data-ub-conflict]').hidden = true;
      D.say('Saved ' + name + (D.doc.version ? ', revision ' + D.doc.version : '') + '.');
      if (firstSave) { history.replaceState(null, '', '/ui/design/' + encodeURIComponent(name) + where()); }
      $('[data-ub-open]').href = '/ui/b/' + encodeURIComponent(name) + where();
      $('[data-ub-open]').hidden = false;
      revisions();
    }).catch((e) => {
      if (e.status === 409) { $('[data-ub-conflict]').hidden = false; D.say(e.code + ': changed elsewhere. Reload to see the newer revision (your changes stay in the YAML tab to copy).', true); return; }
      if (e.problems) { D.problems = e.problems; showProblems(); }
      D.say(e.code + ': ' + e.detail, true);
    });
  }
  saveBtn.addEventListener('click', save);
  $('[data-ub-reload]').addEventListener('click', () => { $('[data-ub-conflict]').hidden = true; load(); });
  window.addEventListener('keydown', (e) => { if (D.mode === 'board' && (e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); if (!saveBtn.disabled) { save(); } } });

  function revisions() {
    const sel = $('[data-ub-revisions]');
    if (!D.name) { return; }
    D.api('GET', '/ui/api/boards/' + encodeURIComponent(D.name) + '/revisions' + where()).then((list) => {
      clear(sel);
      sel.appendChild(h('option', { value: '', text: 'Revisions…' }));
      (Array.isArray(list) ? list : (list && list.revisions) || []).forEach((r) => sel.appendChild(h('option', { value: String(r.revision || r.version || r.n), text: 'Revision ' + (r.revision || r.version || r.n) + (r.savedBy ? ' · ' + r.savedBy : '') + (r.savedAt ? ' · ' + r.savedAt : '') })));
    }).catch(() => {});
  }
  $('[data-ub-revisions]').addEventListener('change', (e) => {
    const n = e.target.value;
    if (!n) { return; }
    D.api('GET', '/ui/api/boards/' + encodeURIComponent(D.name) + '/revisions/' + encodeURIComponent(n) + where()).then((r) => {
      tab('yaml');
      yamlBox.value = r.yaml || '';
      yamlBox.dispatchEvent(new Event('input'));
      D.say('Revision ' + n + ' is in the editor: Save makes it the current one.');
    }).catch((x) => D.say(x.code + ': ' + x.detail, true));
  });

  const shareDlg = $('[data-ub-share-dlg]'), copyDlg = $('[data-ub-copy-dlg]');
  $('[data-ub-share]').addEventListener('click', () => { if (!D.name) { D.say('Save the board first.', true); return; } shareDlg.showModal(); });
  shareDlg.querySelector('form').addEventListener('submit', (e) => {
    e.preventDefault();
    const people = shareDlg.querySelector('input').value.split(',').map((s) => s.trim()).filter(Boolean);
    D.api('PUT', '/ui/api/boards/' + encodeURIComponent(D.name) + '/share', { people }).then(() => {
      shareDlg.close();
      const me = root.getAttribute('data-user');
      D.say(people.length ? 'Shared read-only with ' + people.join(', ') + ': the link is ' + location.origin + '/ui/b/' + D.name + '?owner=' + encodeURIComponent(me || '') : 'Private again.');
    }).catch((x) => D.say(x.code + ': ' + x.detail, true));
  });
  $('[data-ub-copy]').addEventListener('click', () => { if (!D.name) { D.say('Save the board first.', true); return; } copyDlg.showModal(); });
  copyDlg.querySelector('form').addEventListener('submit', (e) => {
    e.preventDefault();
    const ins = copyDlg.querySelectorAll('input'), body = { workspace: ins[0].value.trim() };
    if (ins[1].value.trim()) { body.name = ins[1].value.trim(); }
    D.api('POST', '/ui/api/boards/' + encodeURIComponent(D.name) + '/copy' + where(), body).then((r) => { copyDlg.close(); D.say('Copied to the workspace ' + body.workspace + ' as ' + ((r && r.board) || body.name || D.name) + '.'); })
      .catch((x) => D.say(x.code + ': ' + x.detail, true));
  });
  [shareDlg, copyDlg].forEach((d) => d.querySelector('[data-cancel]').addEventListener('click', () => d.close()));
  $('[data-ub-export]').addEventListener('click', () => {
    D.api('POST', '/build/board-api/to-sutra', { document: docForServer() }).then((r) => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([r.yaml], { type: 'application/yaml' }));
      a.download = (nameIn.value.trim() || 'board') + '.v1.sutra.yaml';
      document.body.appendChild(a); a.click(); a.remove();
      D.say('Exported ' + a.download + ((r.notes || []).length ? ' · ' + r.notes.join('; ') : ''));
    }).catch((e) => D.say(e.code + ': ' + e.detail, true));
  });

  // ---- toolbar ----------------------------------------------------------------------------------------------------------------------------
  function tab(which) {
    root.querySelectorAll('[data-ub-tab]').forEach((b) => b.setAttribute('aria-selected', String(b.getAttribute('data-ub-tab') === which)));
    $('[data-ub-pane="canvas"]').hidden = which !== 'canvas';
    $('[data-ub-pane="yaml"]').hidden = which !== 'yaml';
    if (which === 'canvas') { requestAnimationFrame(C.wires); } else if (D.mode === 'board') { writeYaml(); }
  }
  D.tab = tab;
  // ---- the two modes: Board and Chip, one shell (one palette area, one YAML tab; P4b's chip designer plugs into Chip mode) --------------
  const texts = { board: null, chip: '' };
  D.mode = root.getAttribute('data-mode') || 'board';
  function mode(m) {
    if (m === D.mode) { return; }
    texts[D.mode] = yamlBox.value;
    D.mode = m;
    root.setAttribute('data-mode', m);
    root.querySelectorAll('[data-ub-mode]').forEach((b) => b.setAttribute('aria-selected', String(b.getAttribute('data-ub-mode') === m)));
    $('[data-ub-heading]').textContent = (m === 'chip' ? 'Chip' : 'Board') + ' designer';
    if (m === 'board') { yamlBox.value = D.yaml; requestAnimationFrame(C.wires); } else { yamlBox.value = texts.chip || ''; }
    const ce = $('[data-ce-name]');
    history.replaceState(null, '', m === 'chip' ? '/build/chip/' + (ce && ce.value.trim() ? encodeURIComponent(ce.value.trim()) : 'new') : (D.name ? '/build/board/' + encodeURIComponent(D.name) + where() : '/build/board/new'));
    root.dispatchEvent(new CustomEvent('ub:mode', { detail: m }));
  }
  D.setMode = mode;
  root.querySelectorAll('[data-ub-mode]').forEach((b) => b.addEventListener('click', () => mode(b.getAttribute('data-ub-mode'))));
  /** A chip chosen in the palette (click, Enter): added to the board in Board mode, handed to Chip mode's onPick otherwise. */
  D.pick = (ref, at) => { if (D.mode === 'chip') { if (D.onChipPick) { D.onChipPick(ref); } } else { D.addInstance(ref, at); } };
  root.querySelectorAll('[data-ub-tab]').forEach((b) => b.addEventListener('click', () => tab(b.getAttribute('data-ub-tab'))));
  $('[data-ub-device]').addEventListener('click', (e) => {
    const b = e.currentTarget, phone = b.getAttribute('aria-pressed') !== 'true';
    b.setAttribute('aria-pressed', String(phone));
    D.device = phone ? 'phone' : 'desktop';
    C.draw();
    D.liveRender();
  });
  $('[data-ub-grid]').addEventListener('change', (e) => { D.grid = e.target.checked; C.draw(); });
  $('[data-ub-live]').addEventListener('change', (e) => { D.live = e.target.checked; C.draw(); D.liveRender(); });
  nameIn.addEventListener('input', () => { showProblems(); });
  nameIn.addEventListener('change', () => { D.doc.board = nameIn.value.trim(); writeYaml(); });
  titleIn.addEventListener('change', () => { D.doc.title = titleIn.value.trim(); writeYaml(); check(); });
  window.addEventListener('beforeunload', (e) => { if (root.getAttribute('data-dirty') === 'true') { e.preventDefault(); e.returnValue = ''; } });
  window.addEventListener('pagehide', () => { if (current) { current.close(); } });

  const C = U.Canvas(D);
  U.canvas = C;
  U.designer = D;
  C.loadPalette();
  load();
})();
