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
/* Rupaka phase 3 (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 8): the report viewer at /bi/r/<id>[/<page>]. It asks the server for the report once (/bi/api/reports/<id>: the model, the
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

  const state = { slicers: {}, selections: [], drills: {}, carry: [], dates: null, mode: 'filter', rows: {}, all: {}, asTable: {}, time: { mode: 'now' } };
  const MAX_FOLLOW = 12;                    // standing queries one page holds (the server allows drishti.bi.mq.max-subscriptions-per-user for a person)
  let pbar = null, picker = null;           // wave B: the parameter slicers and Show as (bi-consume.js), the time picker
  let rep = null, idx = null, pageId = null, palette = null, tokens = null;
  let theme = null, families = {}, board = null, tips = null;        // 2.4.0: the report's theme, the format family of each type, its board pages, its tooltip pages
  const themed = (spec) => B.theme.spec(spec, theme, families);
  const frames = new Map();                 // visual id -> {frame, spec, token, res, mq, follow, chip}
  const rolling = {};                       // visual id -> the points a rolling chart has collected since the page opened
  let watch = null, indicator = null, refresher = null, seenGeneration = 0, wasOff = false;
  const live = q('[data-live]');
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);

  // ---- state ---------------------------------------------------------------------------------------------------------
  const pageOf = (id) => rep.report.pages.find((p) => p.id === id);
  const visualOf = (id) => pageOf(pageId).visuals.find((v) => v.id === id);

  /** The index of the dataset a visual reads: its own when it names one (the server sends the models of the report's other datasets), else the report's. */
  const idxs = {};
  function idxOf(spec) {
    if (spec && spec.dataset && rep && rep.datasets && rep.datasets[spec.dataset]) { return idxs[spec.dataset] || (idxs[spec.dataset] = B.index(rep.datasets[spec.dataset])); }
    return idx;
  }
  function levels(spec) { const ix = idxOf(spec); return spec.hierarchy && ix.hierarchies[spec.hierarchy] ? ix.hierarchies[spec.hierarchy] : null; }
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
    if (state.all && state.all[spec.id]) { st.limit = 5000; }                       // "Show all" of a visual that said it was cut
    if (pbar && pbar.active()) { const pv = pbar.params(); if (Object.keys(pv).length) { st.params = pv; } }   // a field parameter's choice: the server draws the field chosen (2.6.0)
    return st;
  }

  // ---- drawing -------------------------------------------------------------------------------------------------------
  function ctxFor(spec) {
    const sel = state.selections.find((s) => s.visual === spec.id);
    return {
      index: idxOf(spec), palette, t: tokens, interactive: true, selected: sel || null, rolling, entity: true, selections: state.selections, theme,
      onMark: (mark) => selectMark(spec, mark), navigate: (a) => navigate(a),
      onHover: spec.tooltip && tips ? (mark, at) => tips.hover(spec, mark, at) : null, onHoverEnd: spec.tooltip && tips ? () => tips.end() : null,
      slicer: spec.type === 'slicer' ? { value: state.slicers[spec.id] == null ? null : state.slicers[spec.id], onChange: (v) => { setSlicer(spec, v); refreshAll(spec.id); } } : null
    };
  }

  /** A slicer's choice; the slicers of its sync group (on any page) take the same choice (Power BI's sync slicers, 2.4.0). */
  function setSlicer(spec, v) {
    state.slicers[spec.id] = v;
    const group = (spec.format || {}).syncGroup;
    if (!group) { return; }
    rep.report.pages.forEach((p) => p.visuals.forEach((x) => { if (x.id !== spec.id && x.type === 'slicer' && (x.format || {}).syncGroup === group) { state.slicers[x.id] = v; } }));
  }

  function buttons(spec) {
    const out = [];
    const mk = (cls, icon, label, fn, extra) => {
      const b = h('button', Object.assign({ type: 'button', class: 'bi-vis-btn ' + cls, 'aria-label': label, title: label }, extra || {}), B.icon(icon));
      b.addEventListener('click', fn);
      return b;
    };
    const group = VGROUP[spec.type];
    if (group === 'static' || group === 'entity' || group === 'board' || spec.type === 'slicer') { return out; }
    if (group === 'python') { return out; }                                        // a Python result is drawn from rows Python was handed: no alert, drill or marks
    if (B.ai && group !== 'python') { out.push(B.ai.explainButton(spec, mk)); }                                  // Explain this number: hidden unless the server's AI layer is on
    const known = frames.get(spec.id);
    if (!known || known.mq !== null) { out.push(mk('bi-plan', 'diagram-3', 'How this was answered: tiers, layers, times', () => { const e = frames.get(spec.id); if (e) { B.consume.explain(e.frame, askedMq(e)); } }, { 'data-act': 'explain-plan' })); }          // a visual with no model query (a 2.1 dataset's) has no plan to explain
    out.push(alertButton(spec, mk));                                               // Alert me: every visual that shows numbers, live ones too (a live source can alert on change)
    if (group === 'perspective' || group === 'live') { return out; }
    const sel = state.selections.find((s) => s.visual === spec.id);
    const current = () => state.selections.find((s) => s.visual === spec.id);       // at the click: a mark chosen since these buttons were drawn counts
    const path = state.drills[spec.id] || [];
    const lv = levels(spec);
    if (lv) {
      out.push(mk('bi-drill-up', 'arrow-up-circle', 'Drill up', () => { drill(spec, 'up'); }, { 'data-act': 'drill-up', disabled: !path.length }));
      out.push(mk('bi-drill-down', 'arrow-down-circle', 'Drill down into the selected mark', () => { drill(spec, 'down'); },
        { 'data-act': 'drill-down', disabled: !sel || startLevel(spec) + path.length + 1 >= lv.length }));
    }
    const targets = throughTargets(spec, sel);
    targets.forEach((t) => out.push(mk('bi-through', 'box-arrow-up-right', 'Drill through to ' + t.title, () => { drillThrough(t, current() || sel); }, { 'data-act': 'drill-through', 'data-page': t.id })));
    const key = terminalTarget(spec, sel);
    if (key) { out.push(mk('bi-toterm', 'terminal', 'Open ' + key.value + ' in the terminal', () => { window.location.href = '/v/' + encodeURIComponent(key.kind) + '/' + encodeURIComponent(String(key.value)); }, { 'data-act': 'open-terminal' })); }
    out.push(mk('bi-rows', 'list-ul', state.rows[spec.id] ? 'Back to the visual' : 'Show the rows behind this visual', () => toggleRows(spec, current()), { 'data-act': 'rows', 'aria-pressed': state.rows[spec.id] ? 'true' : 'false' }));
    out.push(mk('bi-astable', 'table', state.asTable[spec.id] ? 'Show as a chart' : 'Show as a table', () => { state.asTable[spec.id] = !state.asTable[spec.id]; redraw(spec.id); }, { 'data-act': 'as-table', 'aria-pressed': state.asTable[spec.id] ? 'true' : 'false' }));
    if (chartable(spec)) { out.push(mk('bi-chart', 'bar-chart-line', 'Chart this table (K)', (ev) => chartOf(spec, ev.currentTarget), { 'data-act': 'chart', 'aria-keyshortcuts': 'K' })); }
    if (sel) { out.push(mk('bi-clear', 'x-circle', 'Clear the selection', () => { clearSelection(spec.id); }, { 'data-act': 'clear' })); }
    return out;
  }
  /** Chart any table (RACHANA_REFERENCE.md): a table or matrix visual, or any visual shown as a table or as its rows, charts its last answer. */
  function chartable(spec) { return spec.type === 'table' || spec.type === 'matrix' || !!state.asTable[spec.id] || !!state.rows[spec.id]; }
  function chartOf(spec, opener) {
    const T = (window.drishtiModules || {}).tables, e = frames.get(spec.id);
    if (!T || !e || !e.res || !e.res.columns) { return; }
    T.openChart((S) => S.bi(e.res, { title: spec.title || spec.id, identity: 'bi:' + reportId + '/' + pageId + '/' + spec.id }), opener || e.frame.el);
  }
  /** Opens the alert form for this visual with the report, page, visual and the filters on the screen filled in. */
  function alertButton(spec, mk) {
    return mk('bi-alert', 'bell', 'Alert me about this visual', (ev) => {
      const p = new URLSearchParams({ source: 'visual', report: reportId, page: pageId, visual: spec.id, title: (rep.title || reportId) + ': ' + (spec.title || spec.id) });
      const keep = stateFor(spec);
      delete keep.dates;                                                         // an alert follows the data's own date, not the day pinned on the screen
      delete keep.rows;
      const st = JSON.stringify(keep);
      const long = st.length > 15000;
      if (!long) { p.set('state', st); }
      const btn = ev && ev.currentTarget;
      if (long && btn) { B.near(btn, 'The filters on the screen were too long to carry, so the alert starts without them.', false); }
      window.setTimeout(() => window.location.assign('/alerts/new?' + p.toString()), long ? 1800 : 0);
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
    const kind = B.terminalKind(idxOf(spec).columns[sel.field]);            // a key of an entity's table, or a column that references a kind
    return kind ? { kind, value: sel.values[0] } : null;
  }

  function buildFrame(spec) {
    const f = B.frame(themed(spec), { buttons: buttons(spec), onRetry: () => load(spec), onShowAll: () => { state.all[spec.id] = true; load(spec); } });
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
    if (VGROUP[spec.type] === 'board') { return board ? board.load(entry, pageId, spec, quiet) : Promise.resolve(); }          // a tile (R7): asked of the report it shows
    if (VGROUP[spec.type] === 'static' || VGROUP[spec.type] === 'entity') { entry.frame.result(null, (host) => B.visuals.draw(host, themed(spec), ctxFor(spec), null)); return Promise.resolve(); }
    if (VGROUP[spec.type] === 'python') { return loadPython(spec, entry, token, quiet); }
    if (!quiet) { entry.frame.busy(); }
    return ask(spec, entry, token, quiet).then((res) => {
      if (!res || token !== entry.token) { return null; }
      entry.mq = res.mq || null;
      // parameter values and Show as items ride on the model query (MQ v2): the report's own answer gave the query, the server answers it with them
      const asked = askedMq(entry);
      const named = (cols) => (res.period ? cols.map((c) => (c.name && c.name.endsWith('.' + res.period + '_start') ? Object.assign({}, c, { name: c.name.slice(0, -('.' + res.period + '_start').length) }) : c)) : cols);   // a series asked by period keeps its axis's name
      const formats = (cols) => { const f = {}; (cols || []).forEach((c) => { if (c.kind === 'measure' && c.format) { f[c.name] = c.format; } }); return f; };   // a calculation item's format (a Share reads as a percent)
      if (asked !== entry.mq && entry.mq && !DISTRIBUTIONS[spec.type] && JSON.stringify(asked) !== JSON.stringify(entry.mq)) { return B.enqueue(() => B.mq.ask(asked)).then((a) => [Object.assign({}, res, { columns: named(a.columns), rows: a.rows, rowCount: a.rowCount, totals: undefined, highlight: null, formats: formats(a.columns), meta: Object.assign({}, res.meta, a.meta) })]); }
      return [res];
    }).then((got) => {
      if (!got || token !== entry.token) { return; }
      const res = got[0];
      if (state.all[spec.id]) { res.allShown = true; }
      entry.res = res;
      asOfNote(res);
      sawLive(res);
      B.live.markStale(entry.frame, false);
      draw(spec.id, quiet);
      if (!quiet) { follow(spec, entry); }
    }, (e) => {
      if (token !== entry.token) { return; }
      if (quiet && entry.res) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); } else { entry.frame.error(e); }
    });
  }

  /**
   * A visual's question (BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 6): asked with the inline wait, so a slow one becomes a run card and frees its place in the queue at once; a live
   * redraw asks as before (live views stay streams). The first draw of a page is not an interaction; a later ask (a slicer, a cross-filter, a drill) is, and an
   * interaction that would need a background run is refused at once with the way forward. A ?run=<id> in the address draws its visual from the stored result.
   */
  function ask(spec, entry, token, quiet) {
    const path = '/reports/' + B.id2path(reportId) + '/query';
    const body = { page: pageId, visual: spec.id, state: stateFor(spec) };
    const R = B.runs;
    if (!R || !B.ask || quiet) { return B.enqueue(() => B.api('POST', path, body)); }
    const interaction = !!entry.asked;
    entry.asked = true;
    return R.fromUrl(spec.id, q('[data-run-banner]')).then((stored) => stored || R.settings().then((st) => {
      const again = (wait, extra) => B.enqueue(() => B.ask(path, Object.assign({}, body, { interaction, supersedes: reportId + '/' + pageId + '/' + spec.id }, extra || {}), wait));
      return again(R.seconds(st.inlineWait)).then((got) => R.settle(got, entry.frame, { title: spec.title, reask: again, alive: () => token === entry.token,
        narrow: () => { const d = q('[data-date]'); if (d) { d.focus(); } } }));
    }));
  }

  /** The date the data is as of, beside the Business date control: what the engine read (the newest date of the answer), which may be older than the day in the top bar. */
  // ---- wave B: standing queries, the state chip, parameters, time (bi-consume.js) ------------------------------------
  const DISTRIBUTIONS = { histogram: 1, box: 1 };
  /** The model query a visual is answered by now: the report's answer's own query with the parameter values and Show as items on it. */
  function askedMq(entry) { return entry && entry.mq ? B.consume.withParams(entry.mq, pbar ? pbar.params() : {}, pbar ? pbar.calc() : {}) : null; }
  /** A pushed answer redraws the visual in place when its rows are the whole picture; one with totals, highlights or bins is asked again (quietly). */
  function pushed(spec, entry, d) {
    if (!frames.get(spec.id) || frames.get(spec.id) !== entry) { return; }
    if (watch && watch.paused()) { entry.pending = true; return; }
    canvas.setAttribute('data-pushed', String((Number(canvas.getAttribute('data-pushed')) || 0) + 1));
    canvas.setAttribute('data-live-round', String((Number(canvas.getAttribute('data-live-round')) || 0) + 1));          // a pushed redraw is a round of the live page too
    const whole = d.rows && entry.res && !entry.res.totals && !entry.res.highlight && !DISTRIBUTIONS[spec.type] && !state.rows[spec.id] && entry.res.columns && (!d.rows.length || d.rows[0].length === entry.res.columns.length);
    if (!whole) { load(spec, { quiet: true }); return; }
    entry.res = Object.assign({}, entry.res, { rows: d.rows, rowCount: d.rows.length });
    entry.frame.el.setAttribute('data-updated', new Date().toISOString());
    entry.frame.el.setAttribute('data-cursor', d.cursor || '');
    draw(spec.id, true);
  }
  /** Follows the visual's query when the time can be followed (now, a window, today); says so in its chip either way. */
  function follow(spec, entry) {
    const chip = entry.chip || (entry.chip = B.consume.chip(entry.frame));
    const mq = askedMq(entry);
    const key = mq ? JSON.stringify(mq) : '';
    if (entry.follow && entry.followKey === key) { return; }
    if (entry.follow) { entry.follow.close(); entry.follow = null; }
    entry.followKey = key;
    if (!mq) { chip.el.remove(); entry.chip = null; return; }
    const at = B.consume.pinned(mq) || state.time;                                  // a report's as-of rule may pin a past date (2.6.7 patch)
    if (!B.consume.followable(at)) { chip.set('asof', 'A past day never changes: it was asked once.', at); return; }
    const following = Array.from(frames.values()).filter((e) => e.follow).length;
    if (following >= MAX_FOLLOW) { chip.set('once', 'This page follows ' + MAX_FOLLOW + ' visuals already; this one was asked once.'); return; }
    chip.set('connecting');
    entry.follow = B.consume.follow(mq, {
      throttle: Math.max(250, Number(canvas.getAttribute('data-live-min')) || 250) + 'ms', deliver: 'rows', staleSeconds: Number(canvas.getAttribute('data-live-stale')) || 30,
      onAnswer: (d) => pushed(spec, entry, d),
      onState: (s, why, info) => { chip.set(state.time.mode === 'window' && (s === 'live' || s === 'quiet') ? 'window' : s, why, Object.assign({}, info, state.time)); B.live.markStale(entry.frame, s === 'stale' || s === 'failed', why); }
    });
  }
  function closeFollows() { frames.forEach((e) => { if (e.follow) { e.follow.close(); e.follow = null; } }); }
  window.addEventListener('pagehide', closeFollows);

  function asOfNote(res) {
    const note = q('[data-asof-note]');
    if (!note || !res || !res.meta) { return; }
    const top = document.querySelector('form[data-asof] input[type="date"]');
    const asked = state.dates ? '' : (state.time && state.time.mode === 'asof' && state.time.asOf) || (top && top.value) || '';
    const line = B.visuals.asOfLine(state.dates ? { dates: res.meta.dates } : res.meta, asked);   // a window of the report's own dates: the last day read
    if (line) { note.textContent = line; note.setAttribute('data-asof-aligned', String(!(res.meta.dataAsOf && res.meta.dataAsOf.aligned === false))); }
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
      if (quiet && entry.frame.body.querySelector('.bi-py-chart,.bi-py-img,.bi-vega')) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); return; }
      entry.frame.error(e);
      if (e.traceback) { entry.frame.body.appendChild(B.h('pre', { class: 'bi-py-trace', 'data-py-trace': '', text: e.traceback })); }
    });
  }

  function sawLive(res) { if (res && res.live && res.live.generation > seenGeneration) { seenGeneration = res.live.generation; } }

  function draw(id, live) {
    const entry = frames.get(id);
    if (!entry || !entry.res) { return; }
    const spec = entry.spec;
    const ds = themed(drawSpec(spec));
    const res = entry.res;
    entry.frame.result(res, (host) => {
      const c = ctxFor(ds);
      c.update = !!live;
      if (res.formats) { c.formats = res.formats; }
      if (state.rows[id] || state.asTable[id]) { return B.visuals.dataTable(host, ds, c, res, true); }
      return B.visuals.draw(host, ds, c, res);
    }, live ? { keep: true } : undefined);
    refreshButtons(spec);
    if (entry.chip && !entry.chip.el.isConnected) { entry.frame.foot.appendChild(entry.chip.el); }          // a redraw writes the foot again: the state chip stays
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

  // ---- live: the page follows the report's live tables (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 12) -----------------------------------------------
  /** A tick: every visual of the page that draws data is asked again, quietly, three at a time; a burst of ticks is one round (bi-live.js coalesce). */
  function refreshLive() {
    if (watch && watch.paused()) { return Promise.resolve(); }      // a round asked just before Pause (waiting out the coalescing) does not redraw a paused page
    const specs = pageOf(pageId).visuals.filter((v) => ['static', 'entity', 'slicer'].indexOf(VGROUP[v.type]) < 0);
    canvas.setAttribute('data-live-busy', '');                         // a round under way (its answers may land just after a Pause)
    return Promise.all(specs.map((v) => load(v, { quiet: true }))).then(() => { canvas.setAttribute('data-live-round', String((Number(canvas.getAttribute('data-live-round')) || 0) + 1)); })
      .finally(() => canvas.removeAttribute('data-live-busy'));
  }

  function startLive() {
    const ids = (rep.dataset && (rep.dataset.tables || []).some((t) => t.mode === 'live')) ? [reportId] : [];
    if (board) { board.liveIds().forEach((id) => { if (ids.indexOf(id) < 0) { ids.push(id); } }); }          // the reports a board page's tiles show
    if (!ids.length) { return; }
    const min = Number(canvas.getAttribute('data-live-min')) || 250;
    refresher = B.live.coalesce(refreshLive, min);
    watch = B.live.watch(ids, {
      staleSeconds: Number(canvas.getAttribute('data-live-stale')) || 30,
      onHello: (id, d) => { if (wasOff || Number(d.generation) > seenGeneration) { refresher.request(); } wasOff = false; },
      onTick: (id, d) => { if ((d && d.resumed) || !Array.from(frames.values()).some((e) => e.follow)) { refresher.request(); } },          // pushed answers redraw the followed visuals; Resume catches up
      onState: (info) => { if (info.state === 'disconnected' || info.state === 'hidden') { wasOff = true; } if (info.state === 'paused' && refresher) { refresher.cancel(); } if (indicator) { indicator.draw(); } },
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
    else if ((e.key === 'k' || e.key === 'K') && chartable(spec)) { e.preventDefault(); chartOf(spec, e.currentTarget); }          // before the board's kiosk K, which then stands aside
    else if (e.key === 'd' && levels(spec)) { drill(spec, 'down'); } else if (e.key === 'u' && levels(spec)) { drill(spec, 'up'); }
  }

  // ---- the page, the tabs, the bar -----------------------------------------------------------------------------------
  function visiblePages() { return rep.report.pages.filter((p) => (!p.drillthrough && !p.tooltip) || p.id === pageId); }          // drill-through and tooltip pages are opened from a visual

  function drawTabs() {
    const nav = B.clear(q('[data-pages]'));
    visiblePages().forEach((p) => {
      const a = h('a', { href: '/bi/r/' + B.id2path(reportId) + '/' + encodeURIComponent(p.id), class: 'bi-tab', role: 'tab', 'data-page': p.id, 'aria-selected': p.id === pageId ? 'true' : 'false', text: p.title });
      if (p.id === pageId) { a.setAttribute('aria-current', 'page'); }
      a.addEventListener('click', (e) => { if (e.ctrlKey || e.metaKey || e.shiftKey) { return; } e.preventDefault(); go(p.id, []); });
      nav.appendChild(a);
    });
  }

  /** The page's layout for this screen (a phone, 640 px and narrower, or a tablet, 1024 px and narrower) when the report declares one (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.6): {device, lay}; else null. */
  function deviceLayout() {
    if (!window.matchMedia) { return null; }
    const mm = (px) => window.matchMedia('(max-width: ' + px + 'px)').matches;
    const device = mm(B.phone.MAX) ? 'phone' : mm(B.phone.TABLET_MAX) ? 'tablet' : null;
    const lay = device ? B.phone.layoutOf(rep.report, pageId, device) : null;
    return lay ? { device, lay } : null;
  }

  function drawGrid() {
    frames.forEach((e) => { e.frame.dispose(); if (e.follow) { e.follow.close(); } });
    frames.clear();
    const grid = B.clear(q('[data-grid]'));
    const page = pageOf(pageId);
    const dl = deviceLayout(), lay = dl && dl.lay;                      // on a phone or tablet, the report's own arrangement (order, hidden, width, heights) when it has one
    const ordered = B.phone.arrange(page, lay);                         // else the keyboard reading order is the picture's
    grid.classList.toggle('bi-board-grid', page.layout === 'board');
    canvas.setAttribute('data-layout', page.layout || 'grid');
    canvas.setAttribute('data-page-id', page.id);
    grid.setAttribute('data-phone', dl && dl.device === 'phone' ? 'layout' : 'auto');
    grid.setAttribute('data-device', dl ? dl.device : 'desktop');
    ordered.forEach(({ spec, rows }) => {
      const frame = buildFrame(spec);
      if (dl && dl.device === 'tablet') {
        frame.el.style.gridColumn = B.phone.tabletColumn(spec, lay);
        frame.el.style.gridRow = B.phone.tabletRow(spec, rows);
      } else if (lay && rows) { frame.el.style.height = B.phone.height(rows) + 'px'; }
      frames.set(spec.id, { frame, spec, token: 0, res: null });
      grid.appendChild(frame.el);
    });
    ordered.forEach(({ spec }) => load(spec));
    q('[data-page-title]').textContent = page.title;
    document.title = rep.title + ' · ' + page.title + ' · BI';
    syncChips();
  }

  function go(page, carry, push) {
    if (tips) { tips.close(); }
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
      box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'slicer' }, B.icon('sliders'), ' ' + (sp ? sp.title || B.label(sp.wells.field[0]) : k) + ': ' + (Array.isArray(v) ? v.join(', ') : v.relative ? B.visuals.relativeLabel(v.relative) : (v.from == null ? '' : v.from) + ' to ' + (v.to == null ? '' : v.to))));
    });
    if (pbar && pbar.active()) {
      const p = pbar.params(), c = pbar.calc();
      Object.keys(p).forEach((k) => box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'param' }, B.icon('sliders2'), ' ' + k + ': ' + p[k])));
      Object.keys(c).forEach((k) => box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'calc' }, B.icon('layers'), ' ' + k + ': ' + c[k])));
    }
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
    state.time = { mode: 'now' };
    if (picker) { picker.reset(); }
    if (pbar) { pbar.reset(); }
    q('[data-date]').value = '';
    { const t = document.querySelector('form[data-asof] input[type="date"]'); if (t && t.value) { q('[data-date]').value = t.value; } }
    Object.keys(state.drills).forEach((k) => { delete state.drills[k]; });
    refreshAll(null);
    drawGrid();
  }

  function bar() {
    const date = q('[data-date]');
    const topDate = document.querySelector('form[data-asof] input[type="date"]');
    if (topDate && topDate.value && !date.value) { date.value = topDate.value; date.setAttribute('data-from-topbar', ''); }          // the control shows the day the top bar has; it asks for nothing until it is changed
    date.addEventListener('change', () => date.removeAttribute('data-from-topbar'));
    picker = B.consume.timePicker(q('[data-time]'), (t) => { state.time = t; state.dates = B.consume.datesOf(t); syncChips(); refreshAll(null); });
    const mode = q('[data-mode]');
    mode.addEventListener('change', () => { state.mode = mode.value; if (state.selections.length) { refreshAll(null); } });
    q('[data-reset]').addEventListener('click', reset);
    q('[data-back]').addEventListener('click', () => { window.history.back(); });
    const bm = q('[data-bookmarks]');
    (rep.report.bookmarks || []).forEach((b) => bm.appendChild(h('option', { value: b.name, text: b.name })));
    q('[data-bookmarks-wrap]').hidden = !(rep.report.bookmarks || []).length;
    bm.addEventListener('change', () => { if (bm.value) { applyBookmark(bm.value); bm.value = ''; } });
    window.addEventListener('popstate', () => { const m = window.location.pathname.split('/bi/r/' + B.id2path(reportId))[1]; const p = m ? decodeURIComponent(m.replace(/^\//, '')) : rep.report.pages[0].id; if (pageOf(p)) { go(p, readCarry(), false); } });
    if (window.matchMedia) {                                              // crossing the phone or tablet width swaps between the report's device layout and the grid
      [B.phone.MAX, B.phone.TABLET_MAX].forEach((px) => window.matchMedia('(max-width: ' + px + 'px)').addEventListener('change', () => {
        if (B.phone.layoutOf(rep.report, pageId, 'phone') || B.phone.layoutOf(rep.report, pageId, 'tablet')) { drawGrid(); }
      }));
    }
    if (window.ResizeObserver) { new ResizeObserver(() => frames.forEach((e) => e.frame.resize())).observe(q('[data-grid]')); }
    q('[data-design]').href = '/bi/design/' + B.id2path(reportId);
  }

  /** The model's parameters and calculation groups as slicers above the page (a report on a model; a 2.1 dataset has none). */
  function paramBar(r) {
    const host = q('[data-param-bar]');
    const ref = (r.report && r.report.model) || (r.dataset && r.dataset.model && r.dataset.model.ref) || '';
    if (!host || !ref) { return; }
    B.api('GET', '/models/' + encodeURIComponent(ref) + '/describe').then((d) => {
      pbar = B.consume.paramBar(host, d, () => { syncChips(); refreshAll(null); });
      canvas.setAttribute('data-params', pbar.active() ? 'set' : (d.parameters || []).length || (d.calcGroups || []).length ? 'ready' : 'none');
    }).catch(() => { host.hidden = true; });
  }

  function readCarry() {
    try { const c = new URLSearchParams(window.location.search).get('carry'); return c ? JSON.parse(c) : []; } catch (e) { return []; }
  }

  // ---- start ---------------------------------------------------------------------------------------------------------
  Promise.all([B.api('GET', '/reports/' + B.id2path(reportId)), B.api('GET', '/reports/catalogue')]).then(async ([r, cat]) => {
    rep = r;
    setGroups(cat);
    q('[data-title]').textContent = r.title;
    q('[data-desc]').textContent = r.description || '';
    const badges = q('[data-badges]');
    if (r.sample) { badges.appendChild(h('span', { class: 'bi-badge' }, B.icon('mortarboard'), ' sample')); }
    if (r.shipped) { badges.appendChild(h('span', { class: 'bi-badge' }, B.icon('lock'), ' shipped, read-only')); }
    const errors = (r.problems || []).filter((p) => p.level === 'error');
    const tilesOnly = !!(r.report && r.report.pages.some((p) => p.layout === 'board') && !r.report.model && !r.report.dataset);      // a wall of tiles (R7) reads no data of its own
    if (errors.length || !r.report) {
      const box = q('[data-problems]');
      box.hidden = false;
      const ul = q('ul', box);
      errors.forEach((p) => ul.appendChild(h('li', {}, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
      if (!r.report || (!r.dataset && !tilesOnly)) { q('[data-report]').hidden = true; return; }
    }
    B.publishedBanner(reportId, document.getElementById('biCanvas'));
    B.api('POST', '/items/' + B.id2path(reportId) + '/open', {}).catch(() => {});             // a view is counted, and the report goes under Recent
    idx = B.index(r.dataset || { measures: [], tables: [], hierarchies: {} });
    tokens = B.tokens();
    theme = r.theme || { palette: (r.report.theme || {}).palette || 'default' };
    families = cat.families || {};
    palette = B.theme.palette(theme, tokens);
    B.theme.apply(q('[data-report]'), theme);
    if (!(r.mayEdit)) { q('[data-design]').hidden = true; }
    bar();
    const parts = window.location.pathname.split('/bi/r/' + B.id2path(reportId))[1];
    const wanted = parts ? decodeURIComponent(parts.replace(/^\//, '')) : '';
    const first = rep.report.pages.find((p) => !p.drillthrough && !p.tooltip) || rep.report.pages[0];
    const carry = readCarry();
    // a tooltip page is asked under the choices of the page it is drawn over: that page's slicers and the report-wide ones, not another page's
    const tipState = (spec) => { const st = stateFor(spec), here = pageOf(pageId), own = {}; Object.keys(st.slicers || {}).forEach((k) => { const sl = rep.report.pages.map((p) => p.visuals.find((x) => x.id === k && x.type === 'slicer')).find(Boolean); if (here.visuals.some((x) => x.id === k) || (sl && (sl.format || {}).scope === 'report')) { own[k] = st.slicers[k]; } }); return Object.assign(st, { slicers: own }); };
    tips = B.tooltips ? B.tooltips({ reportId, page: pageOf, stateFor: tipState, ctxFor: (v) => ctxFor(v) }) : null;
    const boardPages = () => rep.report.pages.filter((p) => p.layout === 'board' && !p.drillthrough && !p.tooltip).map((p) => p.id);
    board = boardPages().length && B.reportBoard ? B.reportBoard({ canvas, rep, tokens, time: () => state.time, pages: boardPages, current: () => pageId, go: (id) => go(id, []),
      watchPaused: () => !!(watch && watch.paused()), togglePause: () => { if (watch) { if (watch.paused()) { watch.resume(); } else { watch.pause(); } if (indicator) { indicator.draw(); } } } }) : null;
    await (board ? board.init() : Promise.resolve());
    if (board) { board.bindKiosk(); }
    go(wanted && pageOf(wanted) ? wanted : first.id, carry, false);
    window.history.replaceState({ page: pageId, carry }, '', window.location.pathname + window.location.search);
    startLive();
    paramBar(r);
    // what the export and subscribe controls (bi-export.js) need: the report, the page being shown, the state of the screen and the visuals that hold data
    B.reportView = { id: reportId, title: r.title, page: () => pageId, state: () => stateFor({ id: '' }), visuals: () => pageOf(pageId).visuals.filter((v) => ['static', 'entity', 'slicer', 'board'].indexOf(VGROUP[v.type]) < 0) };
    document.dispatchEvent(new CustomEvent('drishti:report-ready'));
    q('[data-ready]').setAttribute('data-ready', 'yes');
  }).catch((e) => { say(e.message, true); });
})();
