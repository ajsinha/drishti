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
/* Rupaka phase 5 (RUPAKA.md section 12, docs/guides/BOARDS.md): the board viewer at /bi/b/<id>. A board is a wall of tiles; each tile is a visual of a report. The
   page asks the server for the board once (/bi/api/boards/<id>: the model, the problems and the report each tile resolves to), asks each tile's report for the
   report once (its model and dataset), and each tile's report for the tile's data, as the person looking (their row rules and masks). Tiles over live tables follow
   the reports' streams: a tick draws the page's tiles again, in place, three queries at a time. A tile whose number crosses a threshold changes colour (and says so
   in words). Kiosk fills the screen, hides the menus and turns the pages by itself; Esc leaves it. Same-origin JSON only. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'board') { return; }
  const B = window.drishtiBi;
  const h = B.h, q = (s, sc) => B.q(s, sc || canvas);
  const boardId = canvas.getAttribute('data-item');
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);

  let board = null, model = null, pageIdx = 0, tokens = null, kiosk = false;
  const reports = new Map();            // report item id -> {rep, idx, palette}
  const tiles = new Map();              // tile key (page/tile) -> {frame, tile, info, spec, token, res, psp}
  const rolling = {};                   // tile key -> a rolling chart's points
  let watch = null, indicator = null, refresher = null, seenGeneration = 0, wasOff = false, rotateTimer = 0;

  const page = () => board.pages[pageIdx];
  const keyOf = (t) => page().id + '/' + t.id;

  // ---- tiles ---------------------------------------------------------------------------------------------------------------
  function specOf(tile, info) {
    const r = reports.get(info.report);
    if (!r) { return null; }
    const rp = r.rep.report.pages.find((p) => p.id === info.page);
    const v = rp && rp.visuals.find((x) => x.id === tile.visual);
    return v ? Object.assign(B.clone(v), { at: tile.at, title: tile.title || v.title }) : null;
  }

  function ctxFor(entry) {
    const r = reports.get(entry.info.report);
    return { index: r.idx, palette: r.palette, t: tokens, interactive: false, selected: null, rolling, rollingKey: entry.key, update: false };
  }

  function place(entry) {
    entry.frame.el.style.gridColumn = (entry.tile.at.x + 1) + ' / span ' + entry.tile.at.w;
    entry.frame.el.style.gridRow = (entry.tile.at.y + 1) + ' / span ' + entry.tile.at.h;
    entry.frame.el.tabIndex = 0;
    entry.frame.el.setAttribute('data-tile', entry.tile.id);
  }

  /** Colours the tile by its alert rule and says it in words in the line under it (not by colour alone). */
  function alertTile(entry) {
    const level = entry.tile.alert ? B.live.level(entry.tile.alert, entry.res, entry.level || '') : '';
    entry.level = level;                                 // remembered, so a value hovering at a threshold does not flip the colour when the alert has a hysteresis
    entry.frame.el.classList.toggle('bi-tile-warn', level === 'warn');
    entry.frame.el.classList.toggle('bi-tile-crit', level === 'crit');
    entry.frame.el.setAttribute('data-alert', level);
    B.qa('.bi-tile-badge', entry.frame.foot).forEach((n) => n.remove());
    if (level) { entry.frame.foot.appendChild(h('span', { class: 'bi-tile-badge bi-tile-badge-' + level, text: level === 'crit' ? ' · critical' : ' · warning' })); }
  }

  function load(entry, quiet) {
    if (!entry.spec) { return Promise.resolve(); }
    const spec = entry.spec;
    const token = ++entry.token;
    const rid = entry.info.report;
    const qpath = '/reports/' + B.id2path(rid) + '/query';
    if (['text', 'image', 'button'].indexOf(spec.type) >= 0) { entry.frame.result(null, (host) => B.visuals.draw(host, spec, ctxFor(entry), null)); return Promise.resolve(); }
    if (['perspective-grid', 'perspective-pivot', 'perspective-chart'].indexOf(spec.type) >= 0) { return loadPerspective(entry, token, quiet, qpath); }
    if (spec.type === 'python') { return loadPython(entry, token, quiet); }
    if (!quiet) { entry.frame.busy(); }
    return B.enqueue(() => B.api('POST', qpath, { page: entry.info.page, visual: spec.id, state: {} })).then((res) => {
      if (token !== entry.token) { return; }
      entry.res = res;
      if (res.live && res.live.generation > seenGeneration) { seenGeneration = res.live.generation; }
      B.live.markStale(entry.frame, false);
      const c = ctxFor(entry);
      c.update = !!quiet;
      entry.frame.result(res, (host) => B.visuals.draw(host, spec, c, res), quiet ? { keep: true } : undefined);
      alertTile(entry);
      entry.frame.el.setAttribute('data-updated', new Date().toISOString());
    }, (e) => {
      if (token !== entry.token) { return; }
      if (quiet && entry.res) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); } else { entry.frame.error(e); }
    });
  }

  /** A tile that is a Python visual (bi-python.js): the item runs for the person looking, on the rows they may see, on the server or in the browser; no threshold alert (watch a Python measure with an alert instead). */
  function loadPython(entry, token, quiet) {
    const spec = entry.spec;
    if (!B.python) { entry.frame.error({ code: 'DRS-9074', detail: 'The Python visual script did not load.' }); return Promise.resolve(); }
    if (!quiet) { entry.frame.busy(); }
    return B.python.run(spec, { report: entry.info.report, page: entry.info.page, state: {} }).then((res) => {
      if (token !== entry.token) { return; }
      entry.frame.result(null, (host) => B.python.draw(host, spec, ctxFor(entry), res), quiet ? { keep: true } : undefined);
      B.python.footer(entry.frame.foot, res);
      B.live.markStale(entry.frame, false);
      entry.frame.el.setAttribute('data-updated', new Date().toISOString());
    }, (e) => {
      if (token !== entry.token) { return; }
      if (quiet && entry.frame.body.querySelector('.bi-py-chart,.bi-py-img,.bi-vega')) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); return; }
      entry.frame.error(e);
      if (e.traceback) { entry.frame.body.appendChild(h('pre', { class: 'bi-py-trace', 'data-py-trace': '', text: e.traceback })); }
    });
  }

  function loadPerspective(entry, token, quiet, qpath) {
    const spec = entry.spec;
    if (!entry.psp) {
      const ask = (format, extra) => {
        const body = { page: entry.info.page, visual: spec.id, state: Object.assign({}, extra || {}) };
        return B.enqueue(() => (format === 'arrow' ? B.arrow(qpath + '?format=arrow', body) : B.api('POST', qpath, body)));
      };
      entry.psp = B.perspective.start(spec, entry.frame, ask, ctxFor(entry));
      entry.frame.busy();
      return entry.psp.load().catch((e) => entry.frame.error(e));
    }
    return (quiet ? entry.psp.refresh() : entry.psp.reload()).then(() => B.live.markStale(entry.frame, false), (e) => B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message));
  }

  function missing(entry, why) {
    entry.frame.result(null, (host) => {
      B.clear(host).appendChild(h('div', { class: 'bi-vis-note', role: 'status', 'data-tile-state': 'missing' }, h('strong', { text: 'Not available' }), h('p', { text: why })));
      return { dispose() {}, resize() {} };
    });
  }

  function drawPage() {
    tiles.forEach((e) => { e.frame.dispose(); if (e.psp) { e.psp.dispose(); } });
    tiles.clear();
    const grid = B.clear(q('[data-grid]'));
    const p = page();
    p.tiles.slice().sort((a, b) => a.at.y - b.at.y || a.at.x - b.at.x).forEach((tile) => {
      const info = (model.tiles || {})[p.id + '/' + tile.id] || {};
      const spec = info.report ? specOf(tile, info) : null;
      const frame = B.frame(spec || { id: tile.id, type: 'text', title: tile.title || tile.visual }, { buttons: [] });
      const entry = { frame, tile, info, spec, key: p.id + '/' + tile.id, token: 0, res: null, psp: null };
      tiles.set(entry.key, entry);
      place(entry);
      grid.appendChild(frame.el);
    });
    tiles.forEach((entry) => {
      if (!entry.spec) { missing(entry, entry.info.report ? 'The report no longer has this visual.' : 'You cannot open the report this tile shows, or it does not exist.'); return; }
      load(entry, false);
    });
    q('[data-page-title]').textContent = p.title;
    document.title = board.title + ' · ' + p.title + ' · BI';
    drawTabs();
    canvas.setAttribute('data-page-id', p.id);
  }

  function refreshLive() {
    const jobs = [];
    tiles.forEach((e) => { if (e.spec && e.info.live) { jobs.push(load(e, true)); } });
    return Promise.all(jobs).then(() => { canvas.setAttribute('data-live-round', String((Number(canvas.getAttribute('data-live-round')) || 0) + 1)); });
  }

  // ---- pages, tabs, kiosk ------------------------------------------------------------------------------------------------------
  function drawTabs() {
    const nav = B.clear(q('[data-pages]'));
    board.pages.forEach((p, i) => {
      const a = h('a', { href: '#' + p.id, class: 'bi-tab', role: 'tab', 'data-page': p.id, 'aria-selected': i === pageIdx ? 'true' : 'false', text: p.title });
      a.addEventListener('click', (e) => { e.preventDefault(); show(i); });
      nav.appendChild(a);
    });
  }

  function show(i) {
    pageIdx = (i + board.pages.length) % board.pages.length;
    drawPage();
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#' + page().id);
  }

  function rotateSeconds() { return board.rotate ? parseInt(board.rotate, 10) : Number(canvas.getAttribute('data-rotate')) || 30; }

  function rotation() {
    clearInterval(rotateTimer);
    rotateTimer = 0;
    const note = q('[data-rotate-note]');
    if (!kiosk || board.pages.length < 2) { note.hidden = true; return; }
    note.hidden = false;
    note.textContent = 'Next page every ' + rotateSeconds() + ' s';
    rotateTimer = setInterval(() => { if (!document.hidden && !(watch && watch.paused())) { show(pageIdx + 1); } }, rotateSeconds() * 1000);
  }

  function setKiosk(on, fromKey) {
    kiosk = on;
    document.body.classList.toggle('bi-kiosk', on);
    const btn = q('[data-kiosk]');
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    B.q('span', btn).textContent = on ? 'Leave kiosk' : 'Kiosk';
    B.q('i', btn).className = 'bi bi-' + (on ? 'fullscreen-exit' : 'fullscreen');
    if (on && !fromKey && document.documentElement.requestFullscreen) { document.documentElement.requestFullscreen().catch(() => {}); }
    if (!on && document.fullscreenElement && document.exitFullscreen) { document.exitFullscreen().catch(() => {}); }
    rotation();
    tiles.forEach((e) => e.frame.resize());
  }

  function bar() {
    q('[data-kiosk]').addEventListener('click', () => setKiosk(!kiosk));
    q('[data-prev]').addEventListener('click', () => show(pageIdx - 1));
    q('[data-next]').addEventListener('click', () => show(pageIdx + 1));
    document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && kiosk) { setKiosk(false, true); } });
    document.addEventListener('keydown', (e) => {
      if (e.target.closest('input,select,textarea') || e.ctrlKey || e.metaKey || e.altKey) { return; }
      if (e.key === 'Escape' && kiosk) { setKiosk(false, true); } else if (e.key === 'k' || e.key === 'K') { setKiosk(!kiosk); } else if (e.key === 'ArrowRight') { show(pageIdx + 1); } else if (e.key === 'ArrowLeft') { show(pageIdx - 1); } else if ((e.key === 'p' || e.key === 'P') && watch) { if (watch.paused()) { watch.resume(); } else { watch.pause(); } indicator.draw(); }
    });
    if (window.ResizeObserver) { new ResizeObserver(() => tiles.forEach((e) => e.frame.resize())).observe(q('[data-grid]')); }
  }

  function startLive() {
    const ids = [];
    Object.keys(model.tiles || {}).forEach((k) => { const t = model.tiles[k]; if (t.live && t.report && ids.indexOf(t.report) < 0) { ids.push(t.report); } });
    if (!ids.length) { return; }
    refresher = B.live.coalesce(refreshLive, Number(canvas.getAttribute('data-live-min')) || 250);
    watch = B.live.watch(ids, {
      staleSeconds: Number(canvas.getAttribute('data-live-stale')) || 30,
      onHello: (id, d) => { if (wasOff || Number(d.generation) > seenGeneration) { refresher.request(); } wasOff = false; },
      onTick: () => { refresher.request(); },
      onState: (info) => { if (info.state === 'disconnected' || info.state === 'hidden') { wasOff = true; } if (indicator) { indicator.draw(); } },
      onSignal: () => { if (indicator) { indicator.draw(); } }
    });
    indicator = B.live.indicator(q('[data-live-bar]'), () => watch);
  }

  // ---- start ---------------------------------------------------------------------------------------------------------------------
  B.api('GET', '/boards/' + B.id2path(boardId)).then(async (m) => {
    model = m;
    q('[data-title]').textContent = m.title;
    const design = q('[data-design]');
    if (design) { design.hidden = !(m.mayEdit && !m.shipped); design.href = '/bi/design/board/' + B.id2path(boardId); }
    q('[data-desc]').textContent = m.description || '';
    const badges = q('[data-badges]');
    if (m.sample) { badges.appendChild(h('span', { class: 'bi-badge' }, B.icon('mortarboard'), ' sample')); }
    if (m.shipped) { badges.appendChild(h('span', { class: 'bi-badge' }, B.icon('lock'), ' shipped, read-only')); }
    const errors = (m.problems || []).filter((p) => p.level === 'error');
    if (errors.length) {
      const box = q('[data-problems]');
      box.hidden = false;
      const ul = q('ul', box);
      errors.forEach((p) => ul.appendChild(h('li', {}, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
    }
    if (!m.board) { q('[data-board]').hidden = true; return; }
    board = m.board;
    tokens = B.tokens();
    B.publishedBanner(boardId, document.getElementById('biCanvas'));
    B.api('POST', '/items/' + B.id2path(boardId) + '/open', {}).catch(() => {});
    const ids = [];
    Object.keys(m.tiles || {}).forEach((k) => { const r = m.tiles[k].report; if (r && ids.indexOf(r) < 0) { ids.push(r); } });
    await Promise.all(ids.map((rid) => B.api('GET', '/reports/' + B.id2path(rid)).then((rep) => {
      if (rep.report && rep.dataset) { reports.set(rid, { rep, idx: B.index(rep.dataset), palette: B.palette((rep.report.theme || {}).palette, tokens) }); }
    }, () => {})));
    bar();
    const wanted = window.location.hash.replace('#', '');
    pageIdx = Math.max(0, board.pages.findIndex((p) => p.id === wanted));
    drawPage();
    startLive();
    const params = new URLSearchParams(window.location.search);
    if (params.get('kiosk') === '1') { setKiosk(true, true); }
    q('[data-ready]').setAttribute('data-ready', 'yes');
  }).catch((e) => { say(e.message, true); });
})();
