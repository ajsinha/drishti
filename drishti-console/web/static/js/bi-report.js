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
  const frames = new Map();                 // visual id -> {frame, spec, token, psp}
  const rolling = {};                       // visual id -> the points a rolling chart has collected since the page opened
  let watch = null, indicator = null, refresher = null, seenGeneration = 0, wasOff = false;
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
      index: idx, palette, t: tokens, interactive: true, selected: sel || null, rolling,
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
    if (group === 'python') { return out; }                                        // a Python result is drawn from rows Python was handed: no alert, drill or marks
    out.push(alertButton(spec, mk));                                               // Alert me: every visual that shows numbers, live ones too (a live source can alert on change)
    if (group === 'perspective' || group === 'live') { return out; }
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
  /** Opens the alert form for this visual with the report, page, visual and the filters on the screen filled in. */
  function alertButton(spec, mk) {
    return mk('bi-alert', 'bell', 'Alert me about this visual', () => {
      const p = new URLSearchParams({ source: 'visual', report: reportId, page: pageId, visual: spec.id, title: (rep.title || reportId) + ': ' + (spec.title || spec.id) });
      const keep = stateFor(spec);
      delete keep.dates;                                                         // an alert follows the data's own date, not the day pinned on the screen
      delete keep.rows;
      const st = JSON.stringify(keep);
      if (st.length <= 15000) { p.set('state', st); }
      window.location.assign('/alerts/new?' + p.toString());
    }, { 'data-act': 'alert-me' });
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

  /** Asks for a visual's data and draws it. `opts.quiet` is a live redraw: the picture stays, is drawn over in place, and a failure only marks it as old. */
  function load(spec, opts) {
    const quiet = !!(opts && opts.quiet);
    const entry = frames.get(spec.id);
    if (!entry) { return Promise.resolve(); }
    const token = ++entry.token;
    if (VGROUP[spec.type] === 'static') { entry.frame.result(null, (host) => B.visuals.draw(host, spec, ctxFor(spec), null)); return Promise.resolve(); }
    if (VGROUP[spec.type] === 'perspective') { return loadPerspective(spec, entry, token, quiet); }
    if (VGROUP[spec.type] === 'python') { return loadPython(spec, entry, token, quiet); }
    if (!quiet) { entry.frame.busy(); }
    return B.enqueue(() => B.api('POST', '/reports/' + B.id2path(reportId) + '/query', { page: pageId, visual: spec.id, state: stateFor(spec) })).then((res) => {
      if (token !== entry.token) { return; }
      entry.res = res;
      sawLive(res);
      B.live.markStale(entry.frame, false);
      draw(spec.id, quiet);
    }, (e) => {
      if (token !== entry.token) { return; }
      if (quiet && entry.res) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); } else { entry.frame.error(e); }
    });
  }

  /** A python visual (bi-python.js): the Python item runs on the rows this person may see, on the server or in the browser, and what it returns is drawn. */
  function loadPython(spec, entry, token, quiet) {
    if (!B.python) { entry.frame.error({ code: 'DRS-9074', detail: 'The Python visual script did not load.' }); return Promise.resolve(); }
    if (!quiet) { entry.frame.busy(); }
    return B.python.run(spec, { report: reportId, page: pageId, state: stateFor(spec) }).then((res) => {
      if (token !== entry.token) { return; }
      entry.frame.result(null, (host) => B.python.draw(host, spec, ctxFor(spec), res), quiet ? { keep: true } : undefined);
      B.python.footer(entry.frame.foot, res);
      B.live.markStale(entry.frame, false);
    }, (e) => {
      if (token !== entry.token) { return; }
      if (quiet && entry.frame.body.querySelector('.bi-py-chart,.bi-py-img')) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); return; }
      entry.frame.error(e);
      if (e.traceback) { entry.frame.body.appendChild(B.h('pre', { class: 'bi-py-trace', 'data-py-trace': '', text: e.traceback })); }
    });
  }

  function sawLive(res) { if (res && res.live && res.live.generation > seenGeneration) { seenGeneration = res.live.generation; } }

  /** A Perspective visual: the first read as Arrow, then only what changed (bi-perspective.js); the controller lives with the frame until the page changes. */
  function loadPerspective(spec, entry, token, quiet) {
    if (!entry.psp) {
      const ask = (format, extra) => {
        const body = { page: pageId, visual: spec.id, state: Object.assign(stateFor(spec), extra || {}) };
        return B.enqueue(() => (format === 'arrow' ? B.arrow('/reports/' + B.id2path(reportId) + '/query?format=arrow', body) : B.api('POST', '/reports/' + B.id2path(reportId) + '/query', body)))
          .then((res) => { sawLive(res); return res; });
      };
      entry.psp = B.perspective.start(spec, entry.frame, ask, ctxFor(spec));
      entry.frame.busy();
      return entry.psp.load().catch((e) => { if (token === entry.token) { entry.frame.error(e); } });
    }
    if (quiet) { return entry.psp.refresh().then(() => B.live.markStale(entry.frame, false), (e) => B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message)); }
    return entry.psp.reload().catch((e) => entry.frame.error(e));
  }

  function draw(id, live) {
    const entry = frames.get(id);
    if (!entry || !entry.res) { return; }
    const spec = entry.spec;
    const ds = drawSpec(spec);
    const res = entry.res;
    entry.frame.result(res, (host) => {
      const c = ctxFor(ds);
      c.update = !!live;
      if (state.rows[id] || state.asTable[id]) { return B.visuals.dataTable(host, ds, c, res, true); }
      return B.visuals.draw(host, ds, c, res);
    }, live ? { keep: true } : undefined);
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

  // ---- live: the page follows the report's live tables (RUPAKA.md section 12) -----------------------------------------------
  /** A tick: every visual of the page that draws data is asked again, quietly, three at a time; a burst of ticks is one round (bi-live.js coalesce). */
  function refreshLive() {
    const specs = pageOf(pageId).visuals.filter((v) => ['static', 'slicer'].indexOf(VGROUP[v.type]) < 0);
    return Promise.all(specs.map((v) => load(v, { quiet: true }))).then(() => { canvas.setAttribute('data-live-round', String((Number(canvas.getAttribute('data-live-round')) || 0) + 1)); });
  }

  function startLive() {
    if (!(rep.dataset.tables || []).some((t) => t.mode === 'live')) { return; }
    const min = Number(canvas.getAttribute('data-live-min')) || 250;
    refresher = B.live.coalesce(refreshLive, min);
    watch = B.live.watch([reportId], {
      staleSeconds: Number(canvas.getAttribute('data-live-stale')) || 30,
      onHello: (id, d) => { if (wasOff || Number(d.generation) > seenGeneration) { refresher.request(); } wasOff = false; },
      onTick: () => { refresher.request(); },
      onState: (info) => { if (info.state === 'disconnected' || info.state === 'hidden') { wasOff = true; } if (indicator) { indicator.draw(); } },
      onSignal: () => { if (indicator) { indicator.draw(); } }
    });
    indicator = B.live.indicator(q('[data-live-bar]'), () => watch);
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

  /** The page's phone layout when the screen is a phone and the report declares one (RUPAKA.md section 20.6), else null: the automatic single column. */
  function phoneLayout() {
    return window.matchMedia && window.matchMedia('(max-width: ' + B.phone.MAX + 'px)').matches ? B.phone.layoutOf(rep.report, pageId) : null;
  }

  function drawGrid() {
    frames.forEach((e) => { e.frame.dispose(); if (e.psp) { e.psp.dispose(); } });
    frames.clear();
    const grid = B.clear(q('[data-grid]'));
    const page = pageOf(pageId);
    const lay = phoneLayout();                                          // on a phone, the report's own arrangement (order, hidden, heights) when it has one
    const ordered = B.phone.arrange(page, lay);                         // else the keyboard reading order is the picture's
    grid.setAttribute('data-phone', lay ? 'layout' : 'auto');
    ordered.forEach(({ spec, rows }) => {
      const frame = buildFrame(spec);
      if (lay && rows) { frame.el.style.height = B.phone.height(rows) + 'px'; }
      frames.set(spec.id, { frame, spec, token: 0, res: null });
      grid.appendChild(frame.el);
    });
    ordered.forEach(({ spec }) => load(spec));
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
    document.dispatchEvent(new CustomEvent('drishti:report-page'));
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
    if (window.matchMedia) {                                              // crossing the phone width swaps between the report's phone layout and the grid
      window.matchMedia('(max-width: ' + B.phone.MAX + 'px)').addEventListener('change', () => { if (B.phone.layoutOf(rep.report, pageId)) { drawGrid(); } });
    }
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
    B.api('POST', '/items/' + B.id2path(reportId) + '/open', {}).catch(() => {});             // a view is counted, and the report goes under Recent
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
    startLive();
    // what the export and subscribe controls (bi-export.js) need: the report, the page being shown, the state of the screen and the visuals that hold data
    B.reportView = { id: reportId, title: r.title, page: () => pageId, state: () => stateFor({ id: '' }), visuals: () => pageOf(pageId).visuals.filter((v) => ['static', 'slicer'].indexOf(VGROUP[v.type]) < 0) };
    document.dispatchEvent(new CustomEvent('drishti:report-ready'));
    q('[data-ready]').setAttribute('data-ready', 'yes');
  }).catch((e) => { say(e.message, true); });
})();
