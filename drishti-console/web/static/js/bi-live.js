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
/* Rupaka phase 5 (RUPAKA.md section 12): what a report or a board needs to follow live tables. The server's report stream (GET /api/v1/bi/reports/<id>/live)
   says only that the tables changed (a "tick") and how they are doing (a "status"); it carries no data. A tick makes the page ask its visuals again, as the
   person looking, so their row rules and masks apply to every update. The stream comes over the console's one channel per browser (channel.js), so a
   page with live visuals costs the browser no connection of its own.

   Three things are here:
     B.live.watch(ids, handlers)   follows the streams of some reports and keeps one state: live, stale, disconnected, paused or hidden, and the time of the last update;
     B.live.coalesce(job, minMs)   runs a redraw at most every minMs and never two at once: a burst of ticks becomes one redraw, a slow server is not queued behind;
     B.live.indicator(el, watch)   the words, the dot and the time a person reads ("Live", "Last update 12:03:04"), and the Pause / Resume button. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const L = (B.live = B.live || {});

  const clockTime = (d) => d.toTimeString().slice(0, 8);

  /**
   * Follows the live streams of the given reports.
   * @param ids report item ids (their datasets' live tables are followed; the console's channel shares one connection for all)
   * @param o { staleSeconds, onTick(report id, d), onHello(report id, d), onState(state) }
   * @returns {state(), info(), pause(), resume(), paused(), close()}; `info()` is {state, reason, lastUpdate: Date|null, generation}
   */
  L.watch = function (ids, o) {
    const stale = (o.staleSeconds || 30) * 1000;
    const unique = ids.filter((x, i) => ids.indexOf(x) === i);
    const info = { state: 'connecting', reason: '', lastUpdate: null, generation: 0 };
    let paused = false, pending = false, heard = 0, closed = false;
    const offs = [];

    function signal() { if (!closed && o.onSignal) { o.onSignal(info); } }

    function set(state, reason) {
      if (closed || (info.state === state && info.reason === (reason || ''))) { return; }
      info.state = state;
      info.reason = reason || '';
      if (o.onState) { o.onState(info); }
    }

    /** What a server state means to the reader. */
    function fromServer(d) {
      if (d.lastUpdate) { info.lastUpdate = new Date(d.lastUpdate); }
      info.generation = Math.max(info.generation, Number(d.generation) || 0);
      if (d.state === 'failed') { set('failed', failing(d)); } else if (d.state === 'stale') { set('stale', 'the source has pushed nothing for a while'); } else if (d.state === 'closed') { set('disconnected', 'the table was closed'); } else { set(paused ? 'paused' : 'live'); }
    }
    function failing(d) {
      const t = (d.tables || []).find((x) => x.error);
      return t ? t.error : 'the live feed is failing';
    }

    unique.forEach((id) => {
      offs.push(window.DrishtiChannel.subscribe('bilive:' + id, {
        hello(d) { heard = Date.now(); fromServer(d); if (o.onHello) { o.onHello(id, d); } signal(); },
        tick(d) { heard = Date.now(); fromServer(d); if (paused) { pending = true; } else if (o.onTick) { o.onTick(id, d); } signal(); },
        status(d) { heard = Date.now(); fromServer(d); signal(); },
        gone(d) { set('disconnected', (d && d.detail) || 'the stream ended'); },
        error() { set('disconnected', 'the connection to the console dropped; trying again'); },
        paused() { set('hidden', 'this tab was in the background'); }
      }));
    });

    // the server says something at least every `drishti.bi.live.status` (5 s): a page that has heard nothing for a long time says so
    const timer = setInterval(() => {
      if (closed || !heard || info.state === 'disconnected' || info.state === 'hidden' || info.state === 'failed') { return; }
      if (Date.now() - heard > stale) { set('stale', 'nothing heard from the server for ' + Math.round((Date.now() - heard) / 1000) + ' s'); }
    }, 1000);

    return {
      info: () => info,
      state: () => info.state,
      paused: () => paused,
      pause() { paused = true; if (info.state === 'live') { set('paused'); } },
      resume() {
        paused = false;
        if (info.state === 'paused') { set('live'); }
        if (pending && o.onTick) { pending = false; o.onTick(unique[0], { resumed: true }); }
      },
      close() { closed = true; clearInterval(timer); offs.forEach((off) => off()); }
    };
  };

  /**
   * A redraw that never runs twice at once nor oftener than minMs. `request()` asks for one: if one is running or too recent, a single further run is
   * remembered however many requests come. The job returns a promise; its failure does not stop the next run.
   */
  L.coalesce = function (job, minMs) {
    let busy = false, dirty = false, timer = 0, lastStart = 0, runs = 0;
    function run() {
      if (busy) { return; }
      const wait = lastStart + minMs - Date.now();
      if (wait > 0) { if (!timer) { timer = setTimeout(() => { timer = 0; if (dirty) { run(); } }, wait); } return; }
      dirty = false;
      busy = true;
      lastStart = Date.now();
      runs++;
      Promise.resolve().then(job).catch(() => {}).then(() => { busy = false; if (dirty) { run(); } });
    }
    return { request() { dirty = true; run(); }, busy: () => busy, runs: () => runs, cancel() { dirty = false; if (timer) { clearTimeout(timer); timer = 0; } } };
  };

  const WORDS = {
    connecting: 'Connecting', live: 'Live', stale: 'Stale', failed: 'Feed failing', disconnected: 'Disconnected', paused: 'Paused', hidden: 'Paused (tab in background)'
  };

  /** Draws the indicator into `box` ([data-live-state], [data-live-text], [data-live-time], [data-live-pause] inside it) and keeps it current. */
  L.indicator = function (box, getWatch) {
    const state = B.q('[data-live-state]', box), text = B.q('[data-live-text]', box), time = B.q('[data-live-time]', box), pause = B.q('[data-live-pause]', box);
    function draw() {
      const w = getWatch();
      const info = w ? w.info() : { state: 'connecting', reason: '', lastUpdate: null };
      state.setAttribute('data-live-state', info.state);
      state.className = 'bi-live-state bi-live-' + info.state;
      text.textContent = WORDS[info.state] || info.state;
      state.title = info.reason || '';
      time.textContent = info.lastUpdate ? 'Last update ' + clockTime(info.lastUpdate) : '';
      time.setAttribute('data-time', info.lastUpdate ? info.lastUpdate.toISOString() : '');
      if (pause) {
        const p = !!(w && w.paused());
        pause.setAttribute('aria-pressed', p ? 'true' : 'false');
        B.q('span', pause).textContent = p ? 'Resume' : 'Pause';
        B.q('i', pause).className = 'bi bi-' + (p ? 'play-fill' : 'pause-fill');
      }
    }
    if (pause) { pause.addEventListener('click', () => { const w = getWatch(); if (!w) { return; } if (w.paused()) { w.resume(); } else { w.pause(); } draw(); }); }
    box.hidden = false;
    draw();
    return { draw };
  };

  /** A tile's or visual's own words for "this picture is old": the last good answer stays, marked, with the reason. */
  L.markStale = function (frame, on, why) {
    frame.el.classList.toggle('bi-vis-stale', !!on);
    if (on) { frame.foot.setAttribute('data-stale', why || 'the last update failed'); } else { frame.foot.removeAttribute('data-stale'); }
  };

  /**
   * The value a tile's alert is judged by, and the level it reaches: 'crit', 'warn' or ''. A card or KPI by its first row; any other visual by the worst of its rows.
   * With a hysteresis (how far back inside a threshold the value must be before the tile drops a level) and the level the tile had before, a value hovering at the
   * edge keeps the level it had: the level drops only as far as the thresholds loosened by the hysteresis allow. The same rule as TileLevel.java.
   */
  const RANK = { '': 0, warn: 1, crit: 2 };
  const NAME = ['', 'warn', 'crit'];
  L.level = function (alert, res, previous) {
    if (!alert || !res || !res.rows || !res.columns) { return ''; }
    const cols = res.columns.map((c) => c.name);
    let ci = alert.on ? cols.indexOf(alert.on) : -1;
    if (ci < 0) { ci = res.columns.findIndex((c) => c.type === 'decimal' || c.type === 'integer'); }
    if (ci < 0) { return ''; }
    const test = (r, v, hy) => ({ lt: v < r.value + hy, le: v <= r.value + hy, gt: v > r.value - hy, ge: v >= r.value - hy, eq: v === r.value, ne: v !== r.value }[r.op]);
    const rank = (hy) => {
      let level = 0;
      for (const row of res.rows) {
        const v = row[ci];
        if (typeof v !== 'number') { continue; }
        if (alert.crit && test(alert.crit, v, hy)) { return 2; }
        if (alert.warn && test(alert.warn, v, hy)) { level = 1; }
      }
      return level;
    };
    const strict = rank(0);
    const hy = Number(alert.hysteresis) || 0;
    if (!hy || !previous) { return NAME[strict]; }
    return NAME[Math.max(strict, Math.min(RANK[previous] || 0, rank(hy)))];
  };
})();
