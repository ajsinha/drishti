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
/* Boards as a report layout (R7, 2.4.0; docs/guides/BOARDS.md) in the report viewer: the tiles of a page with `layout: board` and the board's kiosk. A tile shows
   a visual of another report (`from:`): it is asked of that report, as the person looking (its model, their rows and masks), followed live like any visual,
   and coloured by its `alert:` (said in words under it, never by colour alone). Ordinary visuals on a board page are the report's own and take its slicers,
   cross-filter and drill. Kiosk fills the screen, hides the menus and turns the board pages by themselves (`board: { rotate }` of the report, else the
   console's default); Esc leaves it. The 2.3 board viewer (/bi/b/<id>) sends people here. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;

  /**
   * @param o { canvas, rep (the report's answer: report, tiles), tokens, time() (the viewer's time), pages() (the ids of the board pages in order),
   *            current() (the page shown), go(pageId), watchPaused() }
   */
  B.reportBoard = function (o) {
    const sources = new Map();            // report item id -> {rep, idx, palette}
    const rolling = {};
    let kiosk = false, rotateTimer = 0;
    const info = (pageId, spec) => (o.rep.tiles || {})[pageId + '/' + spec.id] || {};

    /** Asks each report a tile names for its model, once. */
    function init() {
      const ids = [];
      Object.keys(o.rep.tiles || {}).forEach((k) => { const r = o.rep.tiles[k].report; if (r && ids.indexOf(r) < 0) { ids.push(r); } });
      return Promise.all(ids.map((rid) => B.api('GET', '/reports/' + B.id2path(rid)).then((rep) => {
        if (rep.report && rep.dataset) { sources.set(rid, { rep, idx: B.index(rep.dataset), palette: B.theme.palette(rep.theme, o.tokens) }); }
      }, () => {})));
    }

    /** The source visual a tile shows, placed where the tile is and called what the tile says. */
    function specOf(pageId, tile) {
      const i = info(pageId, tile);
      const s = sources.get(i.report);
      if (!s) { return null; }
      const rp = s.rep.report.pages.find((p) => p.id === i.page);
      const v = rp && rp.visuals.find((x) => x.id === tile.from.visual);
      return v ? Object.assign(B.clone(v), { id: tile.id, at: tile.at, title: tile.title || v.title, __source: v.id }) : null;
    }

    function ctxFor(i, key) {
      const s = sources.get(i.report);
      return { index: s.idx, palette: s.palette, t: o.tokens, interactive: false, selected: null, rolling, rollingKey: key, update: false, theme: s.rep.theme };
    }

    /** Colours the tile by its alert and says it in words in the line under it. */
    function alertTile(entry, tile) {
      const level = tile.alert && tile.alert.warn || tile.alert && tile.alert.crit ? B.live.level(tile.alert, entry.res, entry.level || '') : '';
      entry.level = level;
      entry.frame.el.classList.toggle('bi-tile-warn', level === 'warn');
      entry.frame.el.classList.toggle('bi-tile-crit', level === 'crit');
      entry.frame.el.setAttribute('data-alert', level);
      B.qa('.bi-tile-badge', entry.frame.foot).forEach((n) => n.remove());
      if (level) { entry.frame.foot.appendChild(h('span', { class: 'bi-tile-badge bi-tile-badge-' + level, text: level === 'crit' ? ' · critical' : ' · warning' })); }
    }

    function missing(entry, why) {
      entry.frame.result(null, (host) => {
        B.clear(host).appendChild(h('div', { class: 'bi-vis-note', role: 'status', 'data-tile-state': 'missing' }, h('strong', { text: 'Not available' }), h('p', { text: why })));
        return { dispose() {}, resize() {} };
      });
    }

    /** Asks the tile's report for its visual and draws it; a quiet load is a live redraw in place. */
    function load(entry, pageId, tile, quiet) {
      const i = info(pageId, tile);
      const spec = entry.tileSpec || (entry.tileSpec = specOf(pageId, tile));
      entry.frame.el.setAttribute('data-tile', tile.id);
      if (!spec) { missing(entry, i.report ? 'The report no longer has this visual.' : 'You cannot open the report this tile shows, or it does not exist.'); return Promise.resolve(); }
      const token = ++entry.token;
      const key = pageId + '/' + tile.id;
      if (['text', 'image', 'button'].indexOf(spec.type) >= 0) { entry.frame.result(null, (host) => B.visuals.draw(host, spec, ctxFor(i, key), null)); return Promise.resolve(); }
      const ask = Object.assign({}, spec, { id: spec.__source });
      if (spec.type === 'python' && B.python) {
        if (!quiet) { entry.frame.busy(); }
        return B.python.run(ask, { report: i.report, page: i.page, state: {} }).then((res) => {
          if (token !== entry.token) { return; }
          entry.frame.result(null, (host) => B.python.draw(host, spec, ctxFor(i, key), res), quiet ? { keep: true } : undefined);
          B.python.footer(entry.frame.foot, res);
          entry.frame.el.setAttribute('data-updated', new Date().toISOString());
        }, (e) => { if (token === entry.token) { entry.frame.error(e); } });
      }
      if (!quiet) { entry.frame.busy(); }
      const dates = B.consume.datesOf(o.time());
      return B.enqueue(() => B.api('POST', '/reports/' + B.id2path(i.report) + '/query', { page: i.page, visual: spec.__source, state: dates ? { dates } : {} })).then((res) => {
        if (token !== entry.token) { return; }
        entry.res = res;
        entry.mq = res.mq || null;
        if (!quiet) { follow(entry, pageId, tile); }
        B.live.markStale(entry.frame, false);
        const c = ctxFor(i, key);
        c.update = !!quiet;
        entry.frame.result(res, (host) => B.visuals.draw(host, spec, c, res), quiet ? { keep: true } : undefined);
        alertTile(entry, tile);
        if (entry.chip && !entry.chip.el.isConnected) { entry.frame.foot.appendChild(entry.chip.el); }
        entry.frame.el.setAttribute('data-updated', new Date().toISOString());
      }, (e) => {
        if (token !== entry.token) { return; }
        if (quiet && entry.res) { B.live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); } else { entry.frame.error(e); }
      });
    }

    /** A tile follows its query's pushed answers (a standing query): its rows redraw in place and its alert colour is judged again. */
    function follow(entry, pageId, tile) {
      const chip = entry.chip || (entry.chip = B.consume.chip(entry.frame));
      const k = entry.mq ? JSON.stringify(entry.mq) : '';
      if (entry.follow && entry.followKey === k) { return; }
      if (entry.follow) { entry.follow.close(); entry.follow = null; }
      entry.followKey = k;
      if (!entry.mq) { chip.el.remove(); entry.chip = null; return; }
      if (!B.consume.followable(o.time())) { chip.set('asof', 'A past day never changes: it was asked once.', o.time()); return; }
      chip.set('connecting');
      entry.follow = B.consume.follow(entry.mq, {
        throttle: Math.max(250, Number(o.canvas.getAttribute('data-live-min')) || 250) + 'ms', deliver: 'rows', staleSeconds: Number(o.canvas.getAttribute('data-live-stale')) || 30,
        onAnswer: (d) => {
          if (o.watchPaused()) { return; }
          o.canvas.setAttribute('data-pushed', String((Number(o.canvas.getAttribute('data-pushed')) || 0) + 1));
          if (!d.rows || !entry.res || entry.res.totals || (d.rows.length && entry.res.columns && d.rows[0].length !== entry.res.columns.length)) { load(entry, pageId, tile, true); return; }
          entry.res = Object.assign({}, entry.res, { rows: d.rows, rowCount: d.rows.length });
          const c = ctxFor(info(pageId, tile), pageId + '/' + tile.id);
          c.update = true;
          entry.frame.result(entry.res, (host) => B.visuals.draw(host, entry.tileSpec, c, entry.res), { keep: true });
          alertTile(entry, tile);
          if (entry.chip && !entry.chip.el.isConnected) { entry.frame.foot.appendChild(entry.chip.el); }
          entry.frame.el.setAttribute('data-updated', new Date().toISOString());
        },
        onState: (s, why, inf) => { chip.set(s, why, Object.assign({}, inf, o.time())); B.live.markStale(entry.frame, s === 'stale' || s === 'failed', why); }
      });
    }

    /** The reports whose live streams the board's tiles follow. */
    function liveIds() {
      const ids = [];
      Object.keys(o.rep.tiles || {}).forEach((k) => { const t = o.rep.tiles[k]; if (t.live && t.report && ids.indexOf(t.report) < 0) { ids.push(t.report); } });
      return ids;
    }

    // ---- kiosk ---------------------------------------------------------------------------------------------------------------
    function rotateSeconds() {
      const r = (o.rep.report.board || {}).rotate;
      return r ? parseInt(r, 10) : Number(o.canvas.getAttribute('data-rotate')) || 30;
    }
    function rotation() {
      clearInterval(rotateTimer);
      rotateTimer = 0;
      const note = B.q('[data-rotate-note]', o.canvas);
      const pages = o.pages();
      if (!note) { return; }
      if (!kiosk || pages.length < 2) { note.hidden = true; return; }
      note.hidden = false;
      note.textContent = 'Next page every ' + rotateSeconds() + ' s';
      rotateTimer = setInterval(() => {
        if (document.hidden || o.watchPaused()) { return; }
        const i = pages.indexOf(o.current());
        o.go(pages[(i + 1) % pages.length]);
      }, rotateSeconds() * 1000);
    }
    function setKiosk(on, fromKey) {
      kiosk = on;
      document.body.classList.toggle('bi-kiosk', on);
      const btn = B.q('[data-kiosk]', o.canvas);
      if (btn) {
        btn.setAttribute('aria-pressed', on ? 'true' : 'false');
        B.q('span', btn).textContent = on ? 'Leave kiosk' : 'Kiosk';
        B.q('i', btn).className = 'bi bi-' + (on ? 'fullscreen-exit' : 'fullscreen');
      }
      if (on && !fromKey && document.documentElement.requestFullscreen) { document.documentElement.requestFullscreen().catch(() => {}); }
      if (!on && document.fullscreenElement && document.exitFullscreen) { document.exitFullscreen().catch(() => {}); }
      rotation();
      window.dispatchEvent(new Event('resize'));
    }
    function bindKiosk() {
      const btn = B.q('[data-kiosk]', o.canvas);
      if (!btn) { return; }
      btn.hidden = !o.pages().length;
      btn.addEventListener('click', () => setKiosk(!kiosk));
      document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && kiosk) { setKiosk(false, true); } });
      document.addEventListener('keydown', (e) => {
        if (e.target.closest('input,select,textarea') || e.ctrlKey || e.metaKey || e.altKey) { return; }
        const pages = o.pages(), i = pages.indexOf(o.current());
        if (e.key === 'Escape' && kiosk) { setKiosk(false, true); } else if ((e.key === 'k' || e.key === 'K') && pages.length) { setKiosk(!kiosk); }
        else if (kiosk && (e.key === 'ArrowRight' || e.key === 'ArrowLeft') && pages.length > 1 && i >= 0) { o.go(pages[(i + (e.key === 'ArrowRight' ? 1 : pages.length - 1)) % pages.length]); }
        else if (kiosk && (e.key === 'p' || e.key === 'P') && o.togglePause) { o.togglePause(); }
      });
      if (new URLSearchParams(window.location.search).get('kiosk') === '1' && o.pages().length) { setKiosk(true, true); }
    }

    return { init, load, liveIds, bindKiosk, isKiosk: () => kiosk, close(entry) { if (entry.follow) { entry.follow.close(); entry.follow = null; } } };
  };
})();
