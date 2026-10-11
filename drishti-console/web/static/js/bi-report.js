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
/* Rupaka phase 3 (RUPAKA.md section 8): the report viewer at /bi/r/<id>[/<page>]. It asks the server for the report once (/bi/api/reports/<id>: the model, the
   problems and the dataset's fields), lays each page out on its 12-column grid, and asks for each visual's data one query at a time (at most three at once:
   the server allows three per person; a refusal for busy is retried), under the state the person has built: slicers, the marks selected on other visuals,
   the drill-down path of each visual, the values carried by a drill-through and the business date. Interactions: clicking (or Enter on) a mark selects it,
   which filters or highlights the other visuals; the buttons in a visual's header then drill down, drill through to another page, show the rows behind it, or
   open the entity in the terminal. Same-origin JSON only; the server applies the reader's row rules and masks. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'report') { return; }
  const B = window.drishtiBi;
  const h = B.h, q = (s, sc) => B.q(s, sc || canvas);
  const reportId = canvas.getAttribute('data-item');

  const state = { slicers: {}, selections: [], drills: {}, carry: [], dates: null, mode: 'filter', rows: {}, asTable: {} };
  let rep = null, idx = null, pageId = null, palette = null, tokens = null;
  const frames = new Map();                 // visual id -> {frame, spec, token}
  const live = q('[data-live]');
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);

  // ---- state ---------------------------------------------------------------------------------------------------------
  const pageOf = (id) => rep.report.pages.find((p) => p.id === id);
  const visualOf = (id) => pageOf(pageId).visuals.find((v) => v.id === id);

  function levels(spec) { return spec.hierarchy && idx.hierarchies[spec.hierarchy] ? idx.hierarchies[spec.hierarchy] : null; }
  function firstWell(spec) { return spec.wells.axis ? 'axis' : spec.wells.rows ? 'rows' : null; }
  function startLevel(spec) { const lv = levels(spec); const w = firstWell(spec); return lv && w ? Math.max(0, lv.indexOf(spec.wells[w][0])) : 0; }

  /** The visual as it is drawn now: the first field of its axis moved down the hierarchy by the values drilled into. */
  function drawSpec(spec) {
    const path = state.drills[spec.id] || [];
    const lv = levels(spec), w = firstWell(spec);
    if (!path.length || !lv || !w) { return spec; }
    const c = B.clone(spec);
    c.wells[w][0] = lv[Math.min(lv.length - 1, startLevel(spec) + path.length)];
    return c;
  }

  function stateFor(spec) {
    const st = { dates: state.dates || undefined, carry: state.carry };
    const sl = {};
    Object.keys(state.slicers).forEach((k) => { if (state.slicers[k] != null) { sl[k] = state.slicers[k]; } });
    st.slicers = sl;
    st.selections = state.selections.map((s) => ({ visual: s.visual, field: s.field, values: s.values, highlight: state.mode === 'highlight' }));
    st.drill = state.drills[spec.id] || [];
    if (state.rows[spec.id]) { st.rows = state.rows[spec.id] === true ? true : state.rows[spec.id]; }
    return st;
  }

  // ---- drawing -------------------------------------------------------------------------------------------------------
  function ctxFor(spec) {
    const sel = state.selections.find((s) => s.visual === spec.id);
    return {
      index: idx, palette, t: tokens, interactive: true, selected: sel || null,
      onMark: (mark) => selectMark(spec, mark), navigate: (a) => navigate(a),
      slicer: spec.type === 'slicer' ? { value: state.slicers[spec.id] == null ? null : state.slicers[spec.id], onChange: (v) => { state.slicers[spec.id] = v; refreshAll(spec.id); } } : null
    };
  }

  function buttons(spec) {
    const out = [];
    const mk = (cls, icon, label, fn, extra) => {
      const b = h('button', Object.assign({ type: 'button', class: 'bi-vis-btn ' + cls, 'aria-label': label, title: label }, extra || {}), B.icon(icon));
      b.addEventListener('click', fn);
      return b;
    };
    const group = VGROUP[spec.type];
    if (group === 'static' || spec.type === 'slicer') { return out; }
    const sel = state.selections.find((s) => s.visual === spec.id);
    const path = state.drills[spec.id] || [];
    const lv = levels(spec);
    if (lv) {
      out.push(mk('bi-drill-up', 'arrow-up-circle', 'Drill up', () => { drill(spec, 'up'); }, { 'data-act': 'drill-up', disabled: !path.length }));
      out.push(mk('bi-drill-down', 'arrow-down-circle', 'Drill down into the selected mark', () => { drill(spec, 'down'); },
        { 'data-act': 'drill-down', disabled: !sel || startLevel(spec) + path.length + 1 >= lv.length }));
    }
    const targets = throughTargets(spec, sel);
    targets.forEach((t) => out.push(mk('bi-through', 'box-arrow-up-right', 'Drill through to ' + t.title, () => { drillThrough(t, sel); }, { 'data-act': 'drill-through', 'data-page': t.id })));
    const key = terminalTarget(spec, sel);
    if (key) { out.push(mk('bi-toterm', 'terminal', 'Open ' + key.value + ' in the terminal', () => { window.location.href = '/v/' + encodeURIComponent(key.kind) + '/' + encodeURIComponent(String(key.value)); }, { 'data-act': 'open-terminal' })); }
    out.push(mk('bi-rows', 'list-ul', state.rows[spec.id] ? 'Back to the visual' : 'Show the rows behind this visual', () => toggleRows(spec, sel), { 'data-act': 'rows', 'aria-pressed': state.rows[spec.id] ? 'true' : 'false' }));
    out.push(mk('bi-astable', 'table', state.asTable[spec.id] ? 'Show as a chart' : 'Show as a table', () => { state.asTable[spec.id] = !state.asTable[spec.id]; redraw(spec.id); }, { 'data-act': 'as-table', 'aria-pressed': state.asTable[spec.id] ? 'true' : 'false' }));
    if (sel) { out.push(mk('bi-clear', 'x-circle', 'Clear the selection', () => { clearSelection(spec.id); }, { 'data-act': 'clear' })); }
    return out;
  }
  const VGROUP = {};
  function setGroups(cat) { Object.keys(cat.visuals).forEach((t) => { VGROUP[t] = cat.visuals[t].group; }); }

  /** The pages that take the selected mark's field as a drill-through filter (a visual's own `drillthrough:` names the one it points at). */
  function throughTargets(spec, sel) {
    if (!sel) { return []; }
    return rep.report.pages.filter((p) => p.id !== pageId && p.drillthrough && p.drillthrough.fields.indexOf(sel.field) >= 0)
      .map((p) => ({ id: p.id, title: p.title, field: sel.field }));
  }

  function terminalTarget(spec, sel) {
    if (!sel || sel.values.length !== 1) { return null; }
    const col = idx.columns[sel.field];
    return col && col.kind && (col.key || col.name === 'id') ? { kind: col.kind, value: sel.values[0] } : null;
  }

  function buildFrame(spec) {
    const f = B.frame(spec, { buttons: buttons(spec), onRetry: () => load(spec) });
    f.el.style.gridColumn = (spec.at.x + 1) + ' / span ' + spec.at.w;
    f.el.style.gridRow = (spec.at.y + 1) + ' / span ' + spec.at.h;
    f.el.tabIndex = 0;
    f.el.addEventListener('keydown', (e) => visualKey(spec, e));
    return f;
  }

  function load(spec) {
    const entry = frames.get(spec.id);
    if (!entry) { return; }
    const token = ++entry.token;
    if (VGROUP[spec.type] === 'static') { entry.frame.result(null, (host) => B.visuals.draw(host, spec, ctxFor(spec), null)); return; }
    entry.frame.busy();
    B.enqueue(() => B.api('POST', '/reports/' + B.id2path(reportId) + '/query', { page: pageId, visual: spec.id, state: stateFor(spec) })).then((res) => {
      if (token !== entry.token) { return; }
      entry.res = res;
      draw(spec.id);
    }, (e) => { if (token === entry.token) { entry.frame.error(e); } });
  }

  function draw(id) {
    const entry = frames.get(id);
    if (!entry || !entry.res) { return; }
    const spec = entry.spec;
    const ds = drawSpec(spec);
    const res = entry.res;
    entry.frame.result(res, (host) => {
      const c = ctxFor(ds);
      if (state.rows[id] || state.asTable[id]) { return B.visuals.dataTable(host, ds, c, res, true); }
      return B.visuals.draw(host, ds, c, res);
    });
    refreshButtons(spec);
    const chartOrCard = entry.frame.body.querySelector('.bi-chart');
    if (chartOrCard) { chartOrCard.setAttribute('data-ready', '1'); }
  }
  const redraw = (id) => draw(id);

  function refreshButtons(spec) {
    const entry = frames.get(spec.id);
    B.clear(entry.frame.tools);
    buttons(spec).forEach((b) => entry.frame.tools.appendChild(b));
    entry.frame.title.textContent = (spec.title || '') + ((state.drills[spec.id] || []).length ? ' › ' + state.drills[spec.id].join(' › ') : '') + (state.rows[spec.id] ? ' (rows)' : '');
  }

  function refreshAll(exceptId) {
    pageOf(pageId).visuals.forEach((v) => { if (v.id !== exceptId) { load(v); } });
    syncChips();
  }

  // ---- interactions --------------------------------------------------------------------------------------------------
  function selectMark(spec, mark) {
    const multi = !!(window.event && (window.event.ctrlKey || window.event.metaKey || window.event.shiftKey));
    const cur = state.selections.find((s) => s.visual === spec.id);
    const field = mark.field;
    if (cur && cur.values.some((v) => String(v) === String(mark.value))) {
      cur.values = cur.values.filter((v) => String(v) !== String(mark.value));
      if (!cur.values.length) { state.selections = state.selections.filter((s) => s !== cur); }
    } else if (cur && multi) {
      cur.values.push(mark.value);
    } else {
      state.selections = state.selections.filter((s) => s.visual !== spec.id);
      state.selections.push({ visual: spec.id, field, values: [mark.value], label: mark.label });
    }
    live.textContent = (state.selections.find((s) => s.visual === spec.id) ? 'Selected ' + mark.label : 'Cleared ' + mark.label) + '.';
    pageOf(pageId).visuals.forEach((v) => { if (v.id !== spec.id) { load(v); } else { refreshButtons(v); draw(v.id); } });
    syncChips();
  }

  function clearSelection(id) {
    state.selections = state.selections.filter((s) => s.visual !== id);
    refreshAll(null);
  }

  function drill(spec, dir) {
    const path = (state.drills[spec.id] || []).slice();
    const sel = state.selections.find((s) => s.visual === spec.id);
    if (dir === 'up') { path.pop(); } else if (sel) { path.push(sel.values[0]); state.selections = state.selections.filter((s) => s.visual !== spec.id); }
    state.drills[spec.id] = path;
    pageOf(pageId).visuals.forEach((v) => load(v));
    syncChips();
  }

  function toggleRows(spec, sel) {
    if (state.rows[spec.id]) { delete state.rows[spec.id]; } else { state.rows[spec.id] = sel ? { field: sel.field, values: sel.values } : true; }
    load(spec);
  }

  function drillThrough(t, sel) {
    const carry = state.carry.concat([{ field: t.field, values: sel.values }]);
    go(t.id, carry);
  }

  function navigate(a) {
    if (a.navigate) { go(String(a.navigate), []); } else if (a.bookmark) { applyBookmark(String(a.bookmark)); }
  }

  function visualKey(spec, e) {
    if (e.target.closest('input,select,textarea') || e.ctrlKey || e.metaKey || e.altKey) { return; }
    if (e.key === 'Escape') { clearSelection(spec.id); } else if (e.key === 't' || e.key === 'T') { state.asTable[spec.id] = !state.asTable[spec.id]; redraw(spec.id); }
    else if (e.key === 'r' || e.key === 'R') { toggleRows(spec, state.selections.find((s) => s.visual === spec.id)); }
    else if (e.key === 'd' && levels(spec)) { drill(spec, 'down'); } else if (e.key === 'u' && levels(spec)) { drill(spec, 'up'); }
  }

  // ---- the page, the tabs, the bar -----------------------------------------------------------------------------------
  function visiblePages() { return rep.report.pages.filter((p) => !p.drillthrough || p.id === pageId); }

  function drawTabs() {
    const nav = B.clear(q('[data-pages]'));
    visiblePages().forEach((p) => {
      const a = h('a', { href: '/bi/r/' + B.id2path(reportId) + '/' + encodeURIComponent(p.id), class: 'bi-tab', role: 'tab', 'data-page': p.id, 'aria-selected': p.id === pageId ? 'true' : 'false', text: p.title });
      if (p.id === pageId) { a.setAttribute('aria-current', 'page'); }
      a.addEventListener('click', (e) => { if (e.ctrlKey || e.metaKey || e.shiftKey) { return; } e.preventDefault(); go(p.id, []); });
      nav.appendChild(a);
    });
  }

  function drawGrid() {
    frames.forEach((e) => e.frame.dispose());
    frames.clear();
    const grid = B.clear(q('[data-grid]'));
    const page = pageOf(pageId);
    const ordered = page.visuals.slice().sort((a, b) => a.at.y - b.at.y || a.at.x - b.at.x);       // the keyboard reading order is the picture's
    ordered.forEach((spec) => {
      const frame = buildFrame(spec);
      frames.set(spec.id, { frame, spec, token: 0, res: null });
      grid.appendChild(frame.el);
    });
    ordered.forEach((spec) => load(spec));
    q('[data-page-title]').textContent = page.title;
    document.title = rep.title + ' · ' + page.title + ' · BI';
    syncChips();
  }

  function go(page, carry, push) {
    pageId = page;
    state.carry = carry || [];
    state.selections = [];
    state.rows = {};
    state.asTable = {};
    Object.keys(state.drills).forEach((k) => { delete state.drills[k]; });
    const url = '/bi/r/' + B.id2path(reportId) + '/' + encodeURIComponent(page) + (state.carry.length ? '?carry=' + encodeURIComponent(JSON.stringify(state.carry)) : '');
    if (push !== false) { window.history.pushState({ page, carry: state.carry }, '', url); }
    drawTabs();
    drawGrid();
    q('[data-back]').hidden = !state.carry.length;
  }

  function syncChips() {
    const box = B.clear(q('[data-chips]'));
    state.carry.forEach((c) => box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'carry' }, B.icon('funnel'), ' ' + c.field + ': ' + c.values.join(', '))));
    state.selections.forEach((s) => box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'selection' }, B.icon('cursor'), ' ' + s.field + ': ' + s.values.join(', '))));
    Object.keys(state.slicers).forEach((k) => {
      const v = state.slicers[k];
      if (v == null) { return; }
      const sp = rep.report.pages.map((p) => p.visuals.find((x) => x.id === k)).find(Boolean);
      box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'slicer' }, B.icon('sliders'), ' ' + (sp ? sp.title || B.label(sp.wells.field[0]) : k) + ': ' + (Array.isArray(v) ? v.join(', ') : (v.from == null ? '' : v.from) + ' to ' + (v.to == null ? '' : v.to))));
    });
    q('[data-reset]').hidden = !box.children.length && !state.dates;
  }

  function applyBookmark(name) {
    const b = (rep.report.bookmarks || []).find((x) => x.name === name);
    if (!b) { return; }
    state.slicers = {};
    Object.keys(b.slicers || {}).forEach((k) => { state.slicers[k] = b.slicers[k]; });
    go(b.page, (b.filters || []).map((f) => ({ field: f.field, op: f.op, values: f.values || [] })));
    say('Bookmark "' + name + '" applied.');
  }

  function reset() {
    state.slicers = {};
    state.selections = [];
    state.rows = {};
    state.asTable = {};
    state.dates = null;
    q('[data-date]').value = '';
    Object.keys(state.drills).forEach((k) => { delete state.drills[k]; });
    refreshAll(null);
    drawGrid();
  }

  function bar() {
    const date = q('[data-date]');
    date.addEventListener('change', () => { state.dates = date.value ? { from: date.value, to: date.value } : null; refreshAll(null); });
    const mode = q('[data-mode]');
    mode.addEventListener('change', () => { state.mode = mode.value; if (state.selections.length) { refreshAll(null); } });
    q('[data-reset]').addEventListener('click', reset);
    q('[data-back]').addEventListener('click', () => { window.history.back(); });
    const bm = q('[data-bookmarks]');
    (rep.report.bookmarks || []).forEach((b) => bm.appendChild(h('option', { value: b.name, text: b.name })));
    q('[data-bookmarks-wrap]').hidden = !(rep.report.bookmarks || []).length;
    bm.addEventListener('change', () => { if (bm.value) { applyBookmark(bm.value); bm.value = ''; } });
    window.addEventListener('popstate', () => { const m = window.location.pathname.split('/bi/r/' + B.id2path(reportId))[1]; const p = m ? decodeURIComponent(m.replace(/^\//, '')) : rep.report.pages[0].id; if (pageOf(p)) { go(p, readCarry(), false); } });
    if (window.ResizeObserver) { new ResizeObserver(() => frames.forEach((e) => e.frame.resize())).observe(q('[data-grid]')); }
    q('[data-design]').href = '/bi/design/' + B.id2path(reportId);
  }

  function readCarry() {
    try { const c = new URLSearchParams(window.location.search).get('carry'); return c ? JSON.parse(c) : []; } catch (e) { return []; }
  }

  // ---- start ---------------------------------------------------------------------------------------------------------
  Promise.all([B.api('GET', '/reports/' + B.id2path(reportId)), B.api('GET', '/reports/catalogue')]).then(([r, cat]) => {
    rep = r;
    setGroups(cat);
    q('[data-title]').textContent = r.title;
    q('[data-desc]').textContent = r.description || '';
    const badges = q('[data-badges]');
    if (r.sample) { badges.appendChild(h('span', { class: 'bi-badge' }, B.icon('mortarboard'), ' sample')); }
    if (r.shipped) { badges.appendChild(h('span', { class: 'bi-badge' }, B.icon('lock'), ' shipped, read-only')); }
    const errors = (r.problems || []).filter((p) => p.level === 'error');
    if (errors.length || !r.report) {
      const box = q('[data-problems]');
      box.hidden = false;
      const ul = q('ul', box);
      errors.forEach((p) => ul.appendChild(h('li', {}, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
      if (!r.report || !r.dataset) { q('[data-report]').hidden = true; return; }
    }
    idx = B.index(r.dataset);
    tokens = B.tokens();
    palette = B.palette((r.report.theme || {}).palette, tokens);
    if (!(r.mayEdit)) { q('[data-design]').hidden = true; }
    bar();
    const parts = window.location.pathname.split('/bi/r/' + B.id2path(reportId))[1];
    const wanted = parts ? decodeURIComponent(parts.replace(/^\//, '')) : '';
    const first = rep.report.pages.find((p) => !p.drillthrough) || rep.report.pages[0];
    const carry = readCarry();
    go(wanted && pageOf(wanted) ? wanted : first.id, carry, false);
    window.history.replaceState({ page: pageId, carry }, '', window.location.pathname + window.location.search);
    q('[data-ready]').setAttribute('data-ready', 'yes');
  }).catch((e) => { say(e.message, true); });
})();
