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
/* BI runs in the browser (docs/architecture/BI_RUNS.md sections 6, 7, 11 and 14.3): the words for a run's state, estimate and failure; the one event stream
   of the person's runs (/bi/api/runs/events); the run card a visual shows while its question runs in the background, with the confirm and "needs a background
   run" cards; and drawing a visual from a stored result (?run=<id>). The Runs tab is bi-runs-tab.js. Every right comes from the run's `can`, computed by the
   server: this file never decides who may do what. Exposes drishtiBi.runs. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const R = (B.runs = B.runs || {});
  const h = B.h;

  // ---- words ------------------------------------------------------------------------------------------------------------------------------------------
  R.STATES = {
    planned: { label: 'Planned', icon: 'circle', glyph: '·' },
    'awaiting-confirm': { label: 'Waiting for you', icon: 'question-circle', glyph: '?' },
    queued: { label: 'Queued', icon: 'clock', glyph: '◷' },
    running: { label: 'Running', icon: 'arrow-repeat', glyph: '◔' },
    storing: { label: 'Storing', icon: 'save', glyph: '◕' },
    done: { label: 'Done', icon: 'check-circle', glyph: '✓' },
    reused: { label: 'Reused', icon: 'recycle', glyph: '↺' },
    failed: { label: 'Failed', icon: 'x-circle', glyph: '✕' },
    cancelled: { label: 'Cancelled', icon: 'slash-circle', glyph: '⊘' },
    expired: { label: 'Expired', icon: 'hourglass-bottom', glyph: '⌛' }
  };
  R.ACTIVE = ['planned', 'awaiting-confirm', 'queued', 'running', 'storing'];
  R.active = (s) => R.ACTIVE.indexOf(s) >= 0;
  R.finished = (s) => s === 'done' || s === 'reused';
  R.stateOf = (s) => R.STATES[s] || { label: String(s || 'unknown'), icon: 'circle', glyph: '·' };

  /** What each code means in plain words (section 16); a run's own `reason` follows it. Never the code alone. */
  R.CODES = {
    'DRS-9080': 'This run does not exist, or it is not shared with you.',
    'DRS-9081': 'You already have the most runs allowed queued or running. Wait for one to finish, or cancel one.',
    'DRS-9082': 'This question is large enough to ask you first.',
    'DRS-9083': 'This run has no result yet.',
    'DRS-9084': 'The result was removed by retention or the storage budget. Re-run it on the same data or the newest data.',
    'DRS-9085': 'The server stopped while this run waited or ran, and it could not start again.',
    'DRS-9086': 'Too large for this server: aggregate it, narrow the dates, add a rollup, or ask an administrator for an engine.',
    'DRS-9087': 'The server that owns this run is not answering. Try later, or re-run it elsewhere.',
    'DRS-9088': 'The result store is full. An administrator can free space.',
    'DRS-9089': 'This selection needs a background run.',
    'DRS-9004': 'That snapshot name is taken by another person\'s promotion: choose another name.',
    'DRS-9108': 'This dataset may not read that run snapshot: a snapshot is read only by the dataset it was promoted to. Promote the run again to make your own.',
    'DRS-9021': 'You no longer have access to what this run reads.',
    'DRS-9024': 'It took longer than its time limit.',
    'DRS-9025': 'The server was busy: try again in a moment.',
    'DRS-9026': 'A source failed while it was read.',
    'DRS-9028': 'It ran out of its memory budget: narrow the dates or add a rollup.',
    'DRS-9029': 'Too large to answer at once: ask for it as a run.'
  };
  /** The way forward for a failure, said after the run's own reason ("the run ran out of its memory budget (4 GB): narrow the dates or add a rollup"). */
  R.FIX = {
    'DRS-9024': 'narrow it, or ask an administrator for a longer limit', 'DRS-9028': 'narrow the dates or add a rollup', 'DRS-9081': 'wait for one to finish, or cancel one',
    'DRS-9084': 're-run it on the same data or the newest data', 'DRS-9085': 're-run it; if it happens again, narrow it', 'DRS-9086': 'aggregate it, narrow the dates or add a rollup',
    'DRS-9087': 'try later, or re-run it elsewhere', 'DRS-9088': 'an administrator can free space', 'DRS-9004': 'choose another name', 'DRS-9108': 'promote the run again to make your own'
  };
  /** A problem in words, with its code after it in brackets (for support), never the code alone: the run's own reason and the fix, else the code's words. */
  R.words = function (code, reason) {
    let text;
    if (reason) {
      let r = String(reason).replace(/[.\s]+$/, '');
      const f = R.FIX[code] || '';
      let fix = f && r.toLowerCase().indexOf(f.toLowerCase()) < 0 ? ': ' + f : '';
      // BIR-13: a reason that already ends with the start of the fix ("...could not be started again: re-run it") is finished by it, never said twice
      if (fix) {
        const lr = r.toLowerCase(), lf = f.toLowerCase();
        const cuts = [];
        for (let i = 0; i < lf.length; i++) { if (lf[i] === ';' || lf[i] === ',') { cuts.push(i); } }
        const head = cuts.map((i) => lf.slice(0, i).trim()).filter((x) => x && lr.endsWith(x)).sort((a, b) => b.length - a.length)[0];
        if (head) { r = r.slice(0, r.length - head.length) + f; fix = ''; }
      }
      text = r.charAt(0).toUpperCase() + r.slice(1) + fix + '.';
    } else {
      text = R.CODES[code] || 'Something went wrong.';
    }
    return code ? text + ' (' + code + ')' : text;
  };

  /**
   * How long a run's result is kept, as the server says it (BIR-12): its date when it has one; "kept (promoted)" or "kept (pinned)" only when the run says so;
   * a finished result without a date follows the retention rule (the run's own retentionDays, else the settings'); a run with no result says nothing.
   */
  R.keptUntil = function (run, days, when) {
    const r = run || {}, n = r.retentionDays || days || 30;
    if (r.expiresAt) { return when ? when(r.expiresAt) : String(r.expiresAt).slice(0, 10); }
    if (R.active(r.state) || r.state === 'awaiting-confirm') { return 'it finishes, then ' + n + ' days after it was last opened'; }
    if (r.state === 'expired') { return 'removed'; }
    if (!R.finished(r.state)) { return ''; }
    if (r.promotedTo) { return 'kept (promoted)'; }
    if (r.pinned) { return 'kept (pinned)'; }
    return n + ' days after it was last opened';
  };

  /** 41 s, 4 min 12 s, 1 h 3 min. */
  R.duration = function (sec) {
    if (sec == null || !isFinite(sec)) { return '–'; }
    sec = Math.max(0, Math.round(sec));
    if (sec < 60) { return sec + ' s'; }
    if (sec < 3600) { return Math.floor(sec / 60) + ' min' + (sec % 60 ? ' ' + (sec % 60) + ' s' : ''); }
    return Math.floor(sec / 3600) + ' h' + (Math.round((sec % 3600) / 60) ? ' ' + Math.round((sec % 3600) / 60) + ' min' : '');
  };
  /** An estimate's range in words: "3 to 6 min", "30 to 90 s", "1 to 2 h". */
  R.range = function (est) {
    const s = est && est.seconds;
    if (!s || s.low == null || s.high == null) { return ''; }
    const lo = Number(s.low), hi = Number(s.high);
    const unit = hi >= 5400 ? [3600, 'h'] : hi > 90 ? [60, 'min'] : [1, 's'];
    const r = (x) => Math.max(1, Math.round(x / unit[0]));
    return r(lo) === r(hi) ? 'about ' + r(hi) + ' ' + unit[1] : r(lo) + ' to ' + r(hi) + ' ' + unit[1];
  };
  /** A short range for the table: 3–6 m, 30–90 s. */
  R.shortRange = (est) => R.range(est).replace(/^about /, '').replace(' to ', '–').replace(' min', ' m');
  /** 4,320; 1.8 million; 1.84 billion. */
  R.count = function (n) {
    if (n == null || !isFinite(n)) { return '–'; }
    const a = Math.abs(n);
    if (a >= 1e9) { return (Math.round(n / 1e7) / 100) + ' billion'; }
    if (a >= 1e7) { return (Math.round(n / 1e5) / 10) + ' million'; }
    return Number(n).toLocaleString('en-US');
  };
  R.bytes = function (n) {
    if (n == null || !isFinite(n)) { return ''; }
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0, v = Number(n);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i ? Math.round(v * 10) / 10 : v) + ' ' + u[i];
  };
  /** A name from an id when the server gave no title (BIR-10): file:report:counterparty-risk is "Counterparty risk". */
  R.nameOfId = function (id) {
    const last = String(id || '').split(':').pop().replace(/[-_]+/g, ' ').trim();
    return last ? last.charAt(0).toUpperCase() + last.slice(1) : '';
  };
  /** The report a run came from, by name: its source title, else the name its id gives. */
  R.reportName = (s) => (s && (s.title || R.nameOfId(s.id))) || '';
  /**
   * A run's title as people read it (BIR-10): the server's title, unless it is only the source's id (a report visual's run titled
   * file:report:counterparty-risk), then the visual and the report by name.
   */
  R.titleOf = function (run) {
    const r = run || {}, s = r.source || {};
    if (r.title && r.title !== s.id) { return r.title; }
    if (s.type === 'report' || s.type === 'board') {
      return [s.visual ? R.nameOfId(s.visual) : '', R.reportName(s)].filter(Boolean).join(' · ') || r.title || r.id || '';
    }
    return r.title || s.model || R.nameOfId(s.id) || r.id || '';
  };
  /** The data a run read, in words (BIR-09): the server's words, else its tables and versions, else nothing (the caller draws a dash). */
  R.dataWords = function (run) {
    const d = (run && run.data) || {};
    if (d.words) { return d.words; }
    const tables = Array.isArray(d.tables) ? d.tables : [];
    return tables.map((t) => [t.table, t.versions].filter(Boolean).join(' ')).filter(Boolean).join(' · ');
  };
  R.BASIS = { layers: 'built layers', 'delta-stats': 'Delta statistics', 'iceberg-manifests': 'Iceberg manifests', 'store-stats': 'the store\'s statistics', history: 'past runs', unknown: 'no size known' };
  R.basis = (b) => R.BASIS[b] || String(b || '');
  R.TIERS = { interactive: 'interactive', 'known-heavy': 'known heavy (answered from products)', 'ad-hoc-heavy': 'ad-hoc heavy' };
  /** "MTM by desk and month" and the line under it: "Desk P&L · page Overview" or "mq · trading". */
  R.sourceLine = function (run) {
    const s = run.source || {};
    if (s.type === 'report' || s.type === 'board') { return [R.reportName(s), s.page ? 'page ' + s.page : ''].filter(Boolean).join(' · '); }
    return [s.type, s.model || s.title || s.id].filter(Boolean).join(' · ');
  };
  R.asOf = function (run) {
    const a = run.asOf || {};
    if (a.from && a.to && a.from !== a.to) { return a.from + ' to ' + a.to + (a.dates ? ' (' + a.dates + ' business dates)' : ''); }
    return a.to || a.from || (typeof run.asOf === 'string' ? run.asOf : '');
  };
  R.asOfShort = function (run) {
    const a = run.asOf || {};
    if (a.from && a.to && a.from !== a.to) { return a.from.slice(5) + '…' + a.to.slice(5); }
    return a.to || a.from || '';
  };
  /** How long it ran (or has run so far). */
  R.took = function (run) {
    const act = run.actual || {};
    if (act.seconds != null) { return act.seconds; }
    if (!run.startedAt) { return null; }
    const end = run.finishedAt ? Date.parse(run.finishedAt) : Date.now();
    return (end - Date.parse(run.startedAt)) / 1000;
  };
  /** The state in one line: "Running · building 12 of 41 dates · 29 %", "Done · 4 min 12 s · 4,320 rows", "Failed: <words>". */
  R.line = function (run) {
    const st = R.stateOf(run.state);
    if (run.state === 'failed') { return 'Failed: ' + R.words(run.code, run.reason); }
    if (run.state === 'cancelled') { return run.reason ? 'Cancelled: ' + run.reason : 'Cancelled'; }
    if (run.state === 'expired') { return 'Expired: ' + R.words('DRS-9084', run.reason); }
    const bits = [st.label];
    if (R.active(run.state)) {
      if (run.phase) { bits.push(run.phase); }
      if (run.progress != null) { bits.push(Math.round(run.progress * 100) + ' %'); }
    } else {
      const t = R.took(run);
      if (t != null) { bits.push(R.duration(t)); }
      const rows = (run.actual || {}).rowsOut;
      if (rows != null) { bits.push(R.count(rows) + (rows === 1 ? ' row' : ' rows')); }
    }
    return bits.join(' · ');
  };

  // ---- sharing, schedules and export (section 9.3 as built in 18.2b, 14.1) ------------------------------------------------------------------------------
  /** What a recipient gets, as the server decided it: the words, and the sentence that explains them. */
  R.MODES = {
    same: ['sees the same', 'the same rights as you: they read your stored result'],
    're-masked': ['re-masked', 'the same rows with their own extra masks applied: a masked column shows the mask, never a value'],
    recomputed: ['recomputed as them', 'their rights differ: the question runs again as them, and they are told when it is ready']
  };
  R.mode = (m) => (R.MODES[m] || [String(m || 'not shared'), ''])[0];
  R.modeWhy = (m) => (R.MODES[m] || ['', ''])[1];
  /** The schedule grammar (SubscriptionSchedule's, without after-load) from the friendly presets, and back. */
  R.DAYS = [['mon', 'Monday'], ['tue', 'Tuesday'], ['wed', 'Wednesday'], ['thu', 'Thursday'], ['fri', 'Friday'], ['sat', 'Saturday'], ['sun', 'Sunday']];
  R.PRESETS = [['daily', 'Daily at'], ['weekdays', 'Weekdays at'], ['business-days', 'Business days at'], ['weekly', 'Weekly on'], ['monthly', 'Monthly, the last day at'],
    ['monthly-business', 'Monthly, the last business day at'], ['custom', 'Written out']];
  R.scheduleText = function (p) {
    const t = /^\d\d:\d\d$/.test(p.time || '') ? p.time : '07:00';
    if (p.kind === 'custom') { return String(p.text || '').trim(); }
    if (p.kind === 'weekly') { return 'weekly ' + (p.day || 'mon') + ' ' + t; }
    if (p.kind === 'monthly' || p.kind === 'monthly-business') { return 'monthly last ' + t + (p.kind === 'monthly-business' ? ' business' : ''); }
    return p.kind + ' ' + t;
  };
  R.parseSchedule = function (text) {
    const s = String(text || '').trim();
    let m = /^(daily|weekdays|business-days) (\d\d:\d\d)$/.exec(s);
    if (m) { return { kind: m[1], time: m[2] }; }
    m = /^weekly (mon|tue|wed|thu|fri|sat|sun) (\d\d:\d\d)$/.exec(s);
    if (m) { return { kind: 'weekly', day: m[1], time: m[2] }; }
    m = /^monthly last (\d\d:\d\d)( business)?$/.exec(s);
    if (m) { return { kind: m[2] ? 'monthly-business' : 'monthly', time: m[1] }; }
    return { kind: s ? 'custom' : 'business-days', time: '07:30', text: s };
  };
  /** "business-days 07:30 on the newest data · next 2026-10-22 07:30" (or "paused"). */
  R.scheduleWords = function (s) {
    if (!s) { return ''; }
    if (typeof s === 'string') { return s; }
    const when = (t) => String(t || '').replace('T', ' ').replace(/:00Z$|Z$/, '').slice(0, 16);
    return s.schedule + ' on ' + (s.data === 'same' ? 'the same dates' : 'the newest data') + (s.enabled === false ? ' · paused' : s.nextAt ? ' · next ' + when(s.nextAt) + ' UTC' : '')
      + (s.lastRun ? ' · last run ' + s.lastRun : '');
  };
  /** A finished run's result as a file (CSV or Excel), made by the server from the stored result as the caller's own version: masked cells stay masked. */
  R.exportRun = function (run, fmt, tell) {
    tell('Preparing the ' + (fmt === 'xlsx' ? 'Excel' : 'CSV') + ' file…', false);
    return fetch('/bi/api/runs/' + encodeURIComponent(run.id) + '/export', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: '*/*' }, body: JSON.stringify({ format: fmt }) })
      .then((res) => {
        if (!res.ok) { return res.json().catch(() => ({})).then((d) => { throw Object.assign(new Error(d.detail || res.statusText), { code: d.code, detail: d.detail || res.statusText }); }); }
        const cd = res.headers.get('Content-Disposition') || '', m = /filename="?([^";]+)"?/.exec(cd);
        return res.blob().then((blob) => {
          const name = m ? m[1] : ((B.slug && B.slug(R.titleOf(run))) || run.id) + '.' + fmt;
          const a = h('a', { href: URL.createObjectURL(blob), download: name, hidden: true });
          document.body.appendChild(a); a.click(); a.remove();
          window.setTimeout(() => URL.revokeObjectURL(a.href), 4000);
          tell('Downloaded ' + name + '. Masked cells stay masked.', false);
        });
      }).catch((e) => tell(R.words(e.code, e.detail), true));
  };
  /** The two export buttons (CSV, Excel) of a finished run the caller may export. */
  R.exportButtons = function (run, tell) {
    if (!(run.can || {}).export || !R.finished(run.state)) { return null; }
    const b = (fmt, label) => {
      const x = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-run-export': fmt, text: label });
      x.addEventListener('click', () => { x.disabled = true; R.exportRun(run, fmt, (t, bad) => tell(t, bad, x)).then(() => { x.disabled = false; }); });
      return x;
    };
    return h('span', { class: 'bi-run-export', role: 'group', 'aria-label': 'Export the result' }, b('csv', 'Export CSV'), b('xlsx', 'Export Excel'));
  };

  // ---- settings and the event stream ------------------------------------------------------------------------------------------------------------------
  let settingsP = null;
  /** GET /runs/settings once per page (the inline wait, the retention); a server without runs answers the defaults of section 15. */
  R.settings = function () {
    if (!settingsP) { settingsP = B.api('GET', '/runs/settings').catch(() => ({})).then((s) => Object.assign({ inlineWait: 'PT2S', maxWait: 'PT10S', retentionDays: 30 }, s || {})); }
    return settingsP;
  };
  /** PT2S, PT1.5S, PT1M, 2s or a number of seconds, as seconds. */
  R.seconds = function (d) {
    if (typeof d === 'number') { return d; }
    const m = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/i.exec(String(d || ''));
    if (m) { return (Number(m[1] || 0) * 3600) + (Number(m[2] || 0) * 60) + Number(m[3] || 0); }
    const n = parseFloat(d);
    return isFinite(n) ? n : 2;
  };

  // the person's runs arrive on the browser's one live channel (channel.js, subscription `biruns`: the server's GET /api/v1/bi/runs/events), never a stream
  // of this page's own: a browser allows six connections per site and every live feature shares one
  const subs = new Set();
  let off = null;
  R.streaming = false;
  function deliver(d) { subs.forEach((fn) => { try { fn(d); } catch (e) { /* one listener's fault never stops the others */ } }); }
  function open() {
    if (off || !window.DrishtiChannel) { return; }
    off = window.DrishtiChannel.subscribe('biruns', {
      hello(d) { R.streaming = true; ((d && d.runs) || []).forEach(deliver); deliver({ hello: true }); },
      run(d) { R.streaming = true; if (d) { deliver(d); } },
      status() { R.streaming = true; },
      gone() { R.streaming = false; deliver({ gone: true }); },
      error() { R.streaming = false; },
      paused() { R.streaming = false; }
    });
  }
  /** Listens to the person's runs: fn({id, state, progress, phase, rowsOut, code}) for every change. Returns the function that stops listening. */
  R.listen = function (fn) {
    subs.add(fn);
    open();
    return () => { subs.delete(fn); if (!subs.size && off) { off(); off = null; } };
  };

  /**
   * Follows one run until it ends: onChange(run) on every change (the event merged into the run), then the whole run asked again when it ends. Without the event
   * stream (an older server, a proxy that cuts it) the run is asked every few seconds instead. Returns stop().
   */
  R.follow = function (run, onChange) {
    let live = Object.assign({}, run), stopped = false, timer = null;
    const ended = () => !R.active(live.state);
    const refetch = () => B.api('GET', '/runs/' + encodeURIComponent(live.id)).then((full) => { if (!stopped) { live = full; onChange(live); } return full; });
    const poll = () => {
      if (stopped || ended()) { return; }
      timer = window.setTimeout(() => { if (!R.streaming) { refetch().catch(() => {}).then(poll); } else { poll(); } }, 3000);
    };
    const off = R.listen((d) => {
      if (stopped || !d) { return; }
      if (d.hello) { refetch().catch(() => {}); return; }              // (re)connected: a change made while the stream was away is not sent again
      if (d.id !== live.id) { return; }
      const was = live.state;
      live = Object.assign({}, live, d, { actual: Object.assign({}, live.actual, d.rowsOut != null ? { rowsOut: d.rowsOut } : {}) });
      onChange(live);
      if (R.active(was) && ended()) { refetch().catch(() => {}); }
    });
    // asked once now: a run that ended between its 202 and this listener (a quick run on a real server) sends no event any more, and `hello` lists only
    // unfinished runs, so without this the card would say Queued for ever (BIR-01)
    if (R.streaming) { refetch().catch(() => {}); }
    poll();
    return () => { stopped = true; off(); if (timer) { window.clearTimeout(timer); } };
  };

  // ---- the run card (section 6.2) ---------------------------------------------------------------------------------------------------------------------
  R.runsHref = (id) => '/bi/w/me?tab=runs' + (id ? '&run=' + encodeURIComponent(id) : '');

  /** A progress bar with its number, or the word "running" when progress is not measured (never a guessed bar). */
  function bar(run) {
    if (run.progress == null) { return h('p', { class: 'bi-run-noprog', text: run.phase ? run.phase : 'Running: progress is not measured for this one.' }); }
    const pct = Math.max(0, Math.min(100, Math.round(run.progress * 100)));
    const fill = h('span', { class: 'bi-run-fill' });
    fill.style.width = pct + '%';                                    // the CSSOM, never a style attribute (the strict policy refuses those)
    return h('div', { class: 'bi-run-progress' },
      h('span', { class: 'bi-run-phase', text: run.phase || '' }),
      h('span', { class: 'bi-run-bar', role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(pct), 'aria-label': 'Progress of the run' }, fill),
      h('span', { class: 'bi-run-pct', text: pct + ' %' }));
  }
  function btn(text, attrs, fn, accent) {
    const b = h('button', Object.assign({ type: 'button', class: 'btn-pill ' + (accent ? 'btn-accent' : 'btn-ghost'), text }, attrs || {}));
    if (fn) { b.addEventListener('click', fn); }
    return b;
  }

  /**
   * Draws a run in `host` (a visual's body) and follows it. o.onAnswer(answer, run) when its result is there; the card says a failure in words with Re-run.
   * Returns {el, stop}.
   */
  R.card = function (host, run, o) {
    o = o || {};
    const el = h('div', { class: 'bi-run-card', 'data-run-card': run.id, 'data-run-state': run.state, role: 'status', 'aria-live': 'polite' });
    B.clear(host).appendChild(el);
    let stop = () => {};
    const draw = (r) => {
      el.setAttribute('data-run-state', r.state);
      const st = R.stateOf(r.state), est = r.estimate || {};
      B.clear(el);
      const head = R.active(r.state) ? (r.state === 'queued' ? 'Queued' : 'Running') + (R.range(est) ? ': ' + R.range(est) : '') : st.label;
      el.appendChild(h('p', { class: 'bi-run-head' }, h('span', { class: 'bi-run-glyph bi-run-' + r.state, 'aria-hidden': 'true', text: st.glyph }), h('strong', { 'data-run-head': '', text: head })));
      if (est.why) { el.appendChild(h('p', { class: 'bi-run-why', text: est.why })); }
      if (R.active(r.state)) {
        el.appendChild(bar(r));
        el.appendChild(h('p', { class: 'bi-run-note', text: 'We\'ll put it on the bell when it is ready. You can leave this page.' }));
      } else if (r.state === 'failed' || r.state === 'cancelled' || r.state === 'expired') {
        el.appendChild(h('p', { class: 'bi-run-bad', 'data-run-why': '', text: R.line(r) }));
      } else {
        el.appendChild(h('p', { class: 'bi-run-note', text: 'Done. Drawing the result…' }));
      }
      const acts = h('p', { class: 'bi-run-acts' });
      const can = r.can || {};
      if (R.active(r.state) && can.cancel !== false) {
        acts.appendChild(btn('Cancel', { 'data-run-cancel': '' }, (e) => B.busy(e.currentTarget, () => B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/cancel').then((c) => draw(Object.assign({}, r, c || {}, { state: (c && c.state) || 'cancelled', reason: (c && c.reason) || 'cancelled by you' })))
          .catch((x) => B.near(e.currentTarget, R.words(x.code, x.detail), true)))));
      }
      if (!R.active(r.state) && !R.finished(r.state) && can.rerun && o.onRerun) { acts.appendChild(btn('Run again', { 'data-run-again': '' }, () => o.onRerun(r), true)); }
      const said = h('span', { class: 'bi-run-note', role: 'status' });
      const ex = R.exportButtons(r, (t, bad) => { said.textContent = t; said.classList.toggle('bi-run-bad', !!bad); });
      if (ex) { acts.appendChild(ex); }
      acts.appendChild(h('a', { class: 'btn-pill btn-ghost', href: R.runsHref(r.id), 'data-run-open-runs': '', text: 'Open in Runs' }));
      acts.appendChild(said);
      acts.appendChild(h('a', { class: 'bi-run-help', href: '/help/run-card', 'data-run-help': '', text: 'What is this?' }));
      el.appendChild(acts);
    };
    draw(run);
    const settle = (r) => {
      if (!R.finished(r.state)) { return; }
      stop();
      B.api('GET', '/runs/' + encodeURIComponent(r.id) + '/result?format=json').then((answer) => { if (o.onAnswer) { o.onAnswer(answer, r); } })
        .catch((x) => { el.appendChild(h('p', { class: 'bi-run-bad', text: R.words(x.code, x.detail) })); });
    };
    if (R.active(run.state)) { stop = R.follow(run, (r) => { draw(r); settle(r); }); } else { settle(run); }
    return { el, stop: () => stop() };
  };

  /** The estimate in a sentence: "About 1.8 billion rows over 182 business days (Delta statistics). Expected 3 to 6 min on this server." */
  R.estimateWords = function (est) {
    est = est || {};
    if (est.basis === 'unknown') { return 'We cannot estimate this yet: it reads tables whose size we do not know. It may take a long time.'; }
    const parts = [];
    if (est.readRows != null) { parts.push('About ' + R.count(est.readRows) + ' rows' + (est.dates ? ' over ' + est.dates + ' business days' : '') + (est.basis ? ' (' + R.basis(est.basis) + ')' : '') + '.'); }
    if (R.range(est)) { parts.push('Expected ' + R.range(est) + ' on this server.'); }
    return parts.join(' ');
  };

  /**
   * A confirm card (409 DRS-9082) or a "needs a background run" card (409 DRS-9089) in `host`. o.run() starts the background run and resolves with {run};
   * o.narrow() helps narrow the question (focuses the date). Resolves when the person chooses; the card stays on Not now.
   */
  R.ask = function (host, problem, o) {
    o = o || {};
    const est = problem.estimate || {};
    const confirm = problem.code === 'DRS-9082';
    const el = h('div', { class: 'bi-run-card bi-run-ask', role: 'alertdialog', 'aria-label': confirm ? 'Confirm a background run' : 'This selection needs a background run', 'data-run-ask': problem.code });
    B.clear(host).appendChild(el);
    el.appendChild(h('p', { class: 'bi-run-head' }, h('span', { class: 'bi-run-glyph', 'aria-hidden': 'true', text: '?' }),
      h('strong', { text: confirm ? 'Confirm a background run' : 'This selection needs a background run' + (R.range(est) ? ' (' + R.range(est) + ')' : '') })));
    if (o.title) { el.appendChild(h('p', { class: 'bi-run-title', text: o.title })); }
    el.appendChild(h('p', { 'data-run-estimate': '', text: R.estimateWords(est) }));
    if (est.why) { el.appendChild(h('p', { class: 'bi-run-why', text: est.why })); }
    if (est.covered === false && confirm) { el.appendChild(h('p', { class: 'bi-run-note', text: 'A rollup at this grain in the model makes this instant next time.' })); }
    const msg = h('p', { class: 'bi-run-note', role: 'status' });
    const go = btn('Run in background', { 'data-run-go': '' }, (e) => B.busy(e.currentTarget, () => Promise.resolve(o.run()).catch((x) => { msg.textContent = R.words(x.code, x.detail); msg.classList.add('bi-run-bad'); })), true);
    const narrow = o.narrow ? btn('Narrow the dates', { 'data-run-narrow': '' }, () => { msg.textContent = 'Choose fewer dates above, and the visual asks again.'; o.narrow(); }) : null;
    const not = btn('Not now', { 'data-run-not': '' }, () => { msg.textContent = 'Not run. Nothing was read.'; go.focus(); });
    el.appendChild(h('p', { class: 'bi-run-acts' }, go, narrow, not, h('a', { class: 'bi-run-help', href: confirm ? '/help/run-confirm' : '/help/run-needed', text: 'Why?' })));
    el.appendChild(msg);
    el.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); not.click(); } });
    return el;
  };

  /**
   * What a visual does with the outcome of B.ask (bi-lib.js): resolves with the answer to draw, now or when its run finishes; with null when the frame shows a
   * card instead (a failed run, Not now). `o`: {title, reask(waitSeconds, extra) → Promise of an outcome, narrow(), alive() → false once the visual asked again}.
   */
  R.settle = function (got, frame, o) {
    o = o || {};
    if (!got) { return Promise.resolve(null); }
    if (got.answer !== undefined) { return Promise.resolve(got.answer); }
    return new Promise((resolve) => {
      const alive = () => !o.alive || o.alive();
      const show = (run) => {
        if (!alive()) { return resolve(null); }
        frame.el.classList.remove('bi-vis-busy');
        frame.el.removeAttribute('aria-busy');
        frame.el.setAttribute('data-run', run.id);
        R.card(frame.body, run, { onAnswer: (answer) => { frame.el.removeAttribute('data-run'); resolve(alive() ? answer : null); },
          onRerun: (r) => B.ask('/runs/' + encodeURIComponent(r.id) + '/rerun', { data: 'latest' }, 0).then(next) });
      };
      const next = (g) => {
        if (!g) { return resolve(null); }
        if (g.answer !== undefined) { return resolve(g.answer); }
        if (g.run) { return show(g.run); }
        const problem = g.confirm || g.needed;
        frame.el.classList.remove('bi-vis-busy');
        frame.el.removeAttribute('aria-busy');
        R.ask(frame.body, problem, {
          title: o.title, narrow: o.narrow,
          run: () => {
            const runId = problem.runId || (problem.run && problem.run.id);
            if (g.confirm && runId) { return B.api('POST', '/runs/' + encodeURIComponent(runId) + '/confirm', { confirmToken: problem.confirmToken }).then((r) => next({ run: r.run || r })); }
            // the person saw the estimate on this card: Run in background is their confirmation (a 9089's question is submitted as a visible run)
            return o.reask(0, g.confirm ? { confirm: true, confirmToken: problem.confirmToken } : { interaction: false, confirm: true }).then(next);
          }
        });
      };
      next(got);
    });
  };

  // ---- ?run=<id>: a visual drawn from a stored result ---------------------------------------------------------------------------------------------------
  const pinned = new URLSearchParams(window.location.search).get('run');
  let pinnedP = null;
  /** The answer of the run named in the address when it is this visual's, else null (the visual asks as usual). Used once per visual. */
  const usedPinned = new Set();
  R.fromUrl = function (visualId, banner) {
    if (!pinned || usedPinned.has(visualId)) { return Promise.resolve(null); }
    if (!pinnedP) { pinnedP = B.api('GET', '/runs/' + encodeURIComponent(pinned)).catch((e) => ({ error: e })); }
    return pinnedP.then((run) => {
      if (run.error) {
        if (banner) { say(banner, 'The run in this address could not be opened: ' + R.words(run.error.code, run.error.detail)); }
        return null;
      }
      if (!run.source || run.source.visual !== visualId) { return null; }
      usedPinned.add(visualId);                                        // once: a later filter on this visual asks again
      if (!R.finished(run.state)) {
        if (banner) { say(banner, 'The run ' + R.titleOf(run) + ' has no result to draw: ' + R.line(run)); }
        return null;
      }
      return B.api('GET', '/runs/' + encodeURIComponent(run.id) + '/result?format=json').then((answer) => {
        if (banner) { say(banner, 'Drawn from the run of ' + (run.submittedAt || '').replace('T', ' ').slice(0, 16) + ' (' + (R.asOf(run) || 'as of its dates') + '). Change a filter to ask again.', run); }
        return answer;
      }, (e) => { if (banner) { say(banner, R.words(e.code, e.detail)); } return null; });
    });
  };
  function say(box, text, run) {
    box.hidden = false;
    B.clear(box).appendChild(document.createTextNode(text + ' '));
    box.appendChild(h('a', { href: R.runsHref(pinned), text: 'Open in Runs' }));
    const said = h('span', { class: 'bi-run-note', role: 'status' });
    const ex = run ? R.exportButtons(run, (t, bad) => { said.textContent = ' ' + t; said.classList.toggle('bi-run-bad', !!bad); }) : null;
    if (ex) { box.appendChild(document.createTextNode(' ')); box.appendChild(ex); box.appendChild(said); }
  }
})();
