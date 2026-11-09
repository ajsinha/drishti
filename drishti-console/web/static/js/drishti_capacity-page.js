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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Admin -> Capacity's page (templates/admin/capacity.html; mock-ups docs/architecture/mockups/capacity/ B and A): today against the
   plan (headroom bars, what breaks first in words, this server beside the plan, growth ×1.5/×2/×3) and, behind "Edit the plan", the
   planner (inputs grouped, every result with its Why?, the settings in three forms, the JVM arguments, the command line, the warnings).
   drishti_capacity.js evaluates; this draws. The plan's inputs are kept in this browser (localStorage) until Reset to defaults. */
(function () {
  'use strict';
  var page = document.querySelector('[data-capacity]');
  if (!page || !window.Drishti.capacity) { return; }
  var C = window.Drishti.capacity;
  var KEY = (window.Drishti.key ? window.Drishti.key('drishti.capacity.inputs') : 'drishti.capacity.inputs');
  var st = { plan: null, live: null, inputs: {}, factor: 1.5, grow: {}, ev: null, timer: null };
  var $ = function (sel) { return page.querySelector(sel); };
  var phone = window.matchMedia ? window.matchMedia('(max-width: 639.98px)') : { matches: false };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
  }

  var F = window.Drishti.elements.core;                                         // numbers by drishti-core
  function at1(p) { return Math.round(p * 10) / 10; }                       // a position in per cent of a track (geometry, not a figure)
  function fmt(v, unit) {
    if (v == null || v === '') { return '—'; }
    if (typeof v === 'boolean') { return v ? 'yes' : 'no'; }
    if (typeof v === 'string') { return v; }
    if (unit === 'share' || unit === 'factor') { return F.formatNumber(v, { maxDecimals: 4, minus: '-' }); }
    var a = Math.abs(v);
    return F.formatNumber(v, { maxDecimals: a >= 100 ? 0 : a >= 10 ? 1 : 2, minus: '-' });
  }

  /** Widths, positions and colours given as data-w, data-l, data-bg and data-sw: set through the CSSOM (the console's CSP refuses style attributes). */
  function styled(el) {
    el.querySelectorAll('[data-w],[data-l],[data-bg],[data-sw]').forEach(function (x) {
      if (x.hasAttribute('data-w')) { x.style.width = x.getAttribute('data-w') + '%'; }
      if (x.hasAttribute('data-l')) { x.style.left = x.getAttribute('data-l') + '%'; }
      if (x.hasAttribute('data-bg')) { x.style.background = x.getAttribute('data-bg'); }
      if (x.hasAttribute('data-sw')) { x.style.setProperty('--sw', x.getAttribute('data-sw')); }
    });
    return el;
  }

  function gb(mb) { return mb == null ? '—' : fmt(mb / 1024) + ' GB'; }

  function pill(state) {
    var t = { room: ['g', 'room'], tight: ['a', 'tight'], 'short': ['r', 'short'], none: ['n', 'not measured'], ok: ['g', 'ok'], watch: ['a', 'watch'], act: ['r', 'act'] }[state];
    return '<span class="cap-pill ' + t[0] + '">' + t[1] + '</span>';
  }

  /** A Why? with the reason, the expression and the value of every name in it (a coefficient with its source). */
  function why(text, expression) {
    var names = [], seen = {}, toks = expression ? C.tokens(expression) : [];
    toks.forEach(function (t, i) {
      var fn = toks[i + 1] && toks[i + 1][0] === 'op' && toks[i + 1][1] === '(';
      if (t[0] === 'name' && !fn && !seen[t[1]]) { seen[t[1]] = 1; names.push(t[1]); }
    });
    var coeff = {};
    st.plan.coefficients.forEach(function (c) { coeff[c.name] = c; });
    var rows = names.slice(0, 14).map(function (n) {
      var v;
      try { v = st.ev.value(n); } catch (e) { v = '?'; }
      return '<li><code>' + esc(n) + '</code> ' + esc(typeof v === 'string' ? (v.length > 40 ? v.slice(0, 40) + '…' : v) : fmt(v)) +
        (coeff[n] ? ' <span class="cap-muted">(' + esc(coeff[n].status) + ': ' + esc(coeff[n].source) + ')</span>' : '') + '</li>';
    }).join('');
    return '<span class="cap-why" tabindex="0" role="button" aria-label="Why?">Why?<span class="cap-tip" role="tooltip">' + esc(text) +
      (expression ? '<br><code>= ' + esc(expression) + '</code>' : '') + (rows ? '<ul>' + rows + '</ul>' : '') + '</span></span>';
  }

  function typed(name, raw) {
    var inp = st.plan.inputs.filter(function (i) { return i.name === name; })[0];
    if (!inp) { return undefined; }
    if (inp.kind === 'text') { return String(raw); }
    var n = typeof raw === 'number' ? raw : parseFloat(String(raw).replace(/,/g, ''));
    return isFinite(n) ? n : undefined;
  }

  function overrides() {
    var o = {};
    Object.keys(st.inputs).forEach(function (k) { var v = typed(k, st.inputs[k]); if (v !== undefined) { o[k] = v; } });
    return o;
  }

  function value(n) { try { return st.ev.value(n); } catch (e) { return null; } }

  // ------------------------------------------------------------------------------------------- B: today against the plan
  function renderToday() {
    var rows = C.headroom(st.plan, st.ev, st.live);
    $('[data-cap-kpis]').innerHTML = rows.map(function (r) {
      var big, small;
      if (r.state === 'none') { big = 'not measured'; small = 'needs ' + amount(r.need, r.unit); }
      else if (r.id === 'disk' && r.daysLeft != null && r.state !== 'short') { big = r.daysLeft > 730 ? 'room' : '~' + Math.max(1, Math.round(r.daysLeft / 30.4)) + ' months'; small = fmt(r.has - (r.now || 0)) + ' GB free · ' + fmt(r.perDayGB) + ' GB a day'; }
      else if (r.state === 'short') { big = 'short ' + amount(r.need - r.has, r.unit); small = 'needs ' + amount(r.need, r.unit) + ' · has ' + amount(r.has, r.unit); }
      else { big = r.state; small = (r.now != null ? amount(r.now, r.unit) + ' now · ' : '') + 'needs ' + amount(r.need, r.unit) + ' of ' + amount(r.has, r.unit); }
      return '<div class="cap-kpi ' + r.state + '" data-cap-kpi="' + r.id + '"><div class="l">' + esc(r.label) + '</div><div class="v">' + esc(big) +
        '</div><div class="s">' + esc(small) + '</div></div>';
    }).join('');
    var whyOf = {
      heap: ['The plan needs ' + fmt(value('XmxRawMB')) + ' MB of heap: the steady heap after a collection (' + fmt(value('HeapSteady')) + ' MB, all ' +
        value('DatesAtPeak') + ' dates open and today\'s intraday layer) ÷ ' + value('LiveShare') + ' for the collector, plus ' + fmt(value('HeapLoadTransient')) +
        ' MB of loads in progress. This server has -Xmx of ' + fmt((st.live && st.live.server && st.live.server.heapMaxMb)) + ' MB; heap after the last collection is ' +
        fmt(st.live && st.live.server && st.live.server.heapAfterGcMb) + ' MB now. Short of heap, the safety net closes the oldest dates first (they reopen from their hot files), then collections lengthen.', 'HeapSteady/LiveShare+HeapLoadTransient'],
      ram: ['Resident memory planned: the heap (' + value('XmxGB') + ' GB, fully committed over a long run) + native ' + fmt(value('NativeMB')) + ' MB + the open dates\' page cache ' +
        fmt(value('PageCacheMB')) + ' MB, plus ' + value('OsReserveGB') + ' GB for the OS and ' + value('ConsoleGB') + ' GB for the console, against the machine\'s memory. The tick is the RSS now (VmRSS).', 'RssPlannedMB+(OsReserveGB+ConsoleGB)*1024'],
      disk: ['The plan keeps ' + fmt(value('DiskUsedGB')) + ' GB in every folder together, against the data folder\'s disk. The lakes grow about ' + fmt(rows[2].perDayGB) +
        ' GB a business day (the VaR lake, the intraday batches, the trades); the free space above the guard lasts ' + (rows[2].daysLeft == null ? '—' : fmt(rows[2].daysLeft) + ' business days') + '.', 'DiskUsedGB'],
      cores: ['This server\'s cores (' + ((st.live && st.live.server && st.live.server.cpus) || '—') + ' hardware threads, 2 a core) less ' + value('CoresReserve') + ' kept for loads and the collector, × ' +
        fmt(value('MixRatePerCore')) + ' requests a second a core at the plan\'s VaR share, × ' + value('CpuTarget') + ' busy at the peak, against the plan\'s ' + fmt(value('PeakRps')) +
        ' requests a second. The tick is today\'s busiest minute.', 'PeakRps/MixRatePerCore/CpuTarget']
    };
    $('[data-cap-bars]').innerHTML = rows.map(function (r) {
      var fill = r.ratio == null ? 0 : Math.min(1, r.ratio) * 100, tick = r.now != null && r.has ? Math.min(1, r.now / r.has) * 100 : null;
      return '<div class="cap-hb" data-cap-bar="' + r.id + '"><div><b>' + esc(r.label) + '</b> ' + why(whyOf[r.id][0], whyOf[r.id][1]) + '</div>' +
        '<div class="cap-track" role="img" aria-label="' + esc(r.label + ': needs ' + amount(r.need, r.unit) + ' of ' + amount(r.has, r.unit)) + '"><i class="' + r.state + '" data-w="' + at1(fill) + '"></i>' +
        (tick != null ? '<b data-l="' + at1(tick) + '" title="now"></b>' : '') + '</div>' +
        '<div class="cap-n">' + esc(amount(r.need, r.unit)) + ' needed · ' + esc(amount(r.has, r.unit)) + ' here' + (r.now != null ? ' <span class="cap-muted">/ ' + esc(amount(r.now, r.unit)) + ' now</span>' : '') + ' ' + pill(r.state) + '</div></div>';
    }).join('');
    styled($('[data-cap-bars]'));
    renderFirst(rows);
    $('[data-cap-warnings-b]').innerHTML = warnings(true);
    renderLiveTable();
    renderGrowth();
  }

  function amount(v, unit) {
    if (v == null) { return '—'; }
    if (unit === 'MB') { return v >= 1024 ? fmt(v / 1024) + ' GB' : fmt(v) + ' MB'; }
    if (unit === 'GB') { return fmt(v) + ' GB'; }
    return fmt(v) + ' req/s';
  }

  var SYMPTOM = {
    heap: 'First symptom: the safety net closes the oldest dates (they reopen from their hot files in a fraction of a second); past it, long collections and slow answers.',
    ram: 'First symptom: the open dates\' hot files no longer stay in the page cache, so questions on them read the disk first (slower, not failing).',
    disk: 'First symptom: the free-space guard refuses to build a date (DRS-5218); the dates already open keep answering.',
    cores: 'First symptom: requests queue (they wait rather than fail): answers slow at the peak.'
  };
  var WHEN = { heap: 'at the plan\'s peak (all dates open and the day\'s intraday layer)', ram: 'with every planned date open', disk: 'at the plan\'s year of data', cores: 'at the plan\'s peak requests' };

  function renderFirst(rows) {
    var known = rows.filter(function (r) { return r.ratio != null; });
    var el = $('[data-cap-first]');
    if (!known.length) { el.innerHTML = '<div class="cap-box">No live figures yet: the plan alone is shown. ' + (st.liveError ? esc(st.liveError) : '') + '</div>'; return; }
    known.sort(function (a, b) { return order(b) - order(a); });
    var f = known[0], html = '';
    if (f.state === 'short') {
      html += '<div class="cap-box r" data-cap-breaks="' + f.id + '"><b>What breaks first: ' + esc(f.label.toLowerCase()) + ', ' + esc(WHEN[f.id]) + '.</b> ' +
        esc('The plan needs ' + amount(f.need, f.unit) + '; this server has ' + amount(f.has, f.unit) + '.') + (f.id === 'disk' && f.daysLeft != null ? esc(' At ' + fmt(f.perDayGB) + ' GB a day the room above the guard lasts about ' + fmt(f.daysLeft) + ' business days.') : '') +
        ' ' + esc(SYMPTOM[f.id]) + ' <b>Fix</b>: <code>' + esc(f.fix) + '</code>.</div>';
    } else {
      html += '<div class="cap-box ' + (f.state === 'tight' ? 'a' : 'g') + '" data-cap-breaks="' + f.id + '"><b>Nothing breaks at the plan. Nearest: ' + esc(f.label.toLowerCase()) + '</b> (' +
        esc(amount(f.need, f.unit) + ' of ' + amount(f.has, f.unit)) + (f.id === 'disk' && f.daysLeft != null ? esc(', ' + months(f.daysLeft) + ' of growth left') : '') + '). If it grows: <code>' + esc(f.fix) + '</code>.</div>';
    }
    known.slice(1).forEach(function (r, i) {
      if (r.state === 'short' || r.state === 'tight') {
        html += '<div class="cap-box ' + (r.state === 'short' ? 'r' : 'a') + '"><b>' + (i === 0 ? 'Second' : 'Then') + ': ' + esc(r.label.toLowerCase()) + '</b> (' +
          esc(amount(r.need, r.unit) + ' of ' + amount(r.has, r.unit)) + (r.id === 'disk' && r.daysLeft != null ? esc(', ' + months(r.daysLeft) + ' at today\'s growth') : '') + '). Fix: <code>' + esc(r.fix) + '</code>.</div>';
      }
    });
    var fine = known.filter(function (r) { return r.state === 'room' && r !== f; }).map(function (r) { return r.label; });
    if (fine.length) { html += '<div class="cap-box g"><b>Fine:</b> ' + esc(fine.join(', ')) + ' ' + (fine.length > 1 ? 'have' : 'has') + ' room at the plan.</div>'; }
    el.innerHTML = html;
  }

  /** Business days as months in words (21 business days a month). */
  function months(days) {
    var m = Math.round(days / 21);
    return m < 1 ? 'under a month' : 'about ' + m + (m === 1 ? ' month' : ' months');
  }

  function order(r) {
    var x = r.ratio;
    if (r.id === 'disk' && r.daysLeft != null) { x = Math.max(x, r.daysLeft < 30 ? 1.5 : r.daysLeft < 365 ? 0.9 : 0); }
    return x;
  }

  function liveRow(label, live, plan, state, from) {
    return '<tr><td class="l">' + esc(label) + '</td><td>' + esc(live) + '</td><td class="cap-muted">' + esc(plan) + '</td><td>' + pill(state) + '</td><td class="l cap-muted cap-src">' + esc(from) + '</td></tr>';
  }

  function loadState(live, plan) { return live == null ? 'none' : live > plan ? 'act' : live > 0.9 * plan ? 'watch' : 'ok'; }
  function haveState(live, plan) { return live == null ? 'none' : live >= plan ? 'ok' : 'act'; }

  function renderLiveTable() {
    var L = st.live || {}, s = L.server || {}, v = L.engine || {}, d = L.disk || {}, l = L.load || {};
    var h = '<thead><tr><th class="l">Figure</th><th>Live</th><th>Plan</th><th></th><th class="l">From</th></tr></thead><tbody>';
    h += liveRow('Positions (newest date)', v.positions == null ? '—' : fmt(v.positions), fmt(value('Positions')), loadState(v.positions, value('Positions')), 'the VaR engine: the newest date loaded');
    h += liveRow('Dates open / hot-dates', v.openDates == null ? '—' : v.openDates + ' / ' + v.hotDates, fmt(value('DatesInMemory')), loadState(v.openDates, value('DatesInMemory')), 'the VaR engine\'s open dates');
    h += liveRow('Open dates\' heap / safety net', v.openHeapMb == null ? '—' : fmt(v.openHeapMb) + ' / ' + fmt(v.openHeapLimitMb) + ' MB', fmt(value('SetOpenHeapMB')) + ' MB', haveState(v.openHeapLimitMb, value('SetOpenHeapMB')), 'drishti.var.open-heap-mb (' + (v.openHeapSetting || '—') + ')');
    h += liveRow('Heap after the last collection', s.heapAfterGcMb == null ? '—' : fmt(s.heapAfterGcMb) + ' MB', fmt(value('HeapSteady')) + ' MB', loadState(s.heapAfterGcMb, value('HeapSteady')), 'jvm.gc.live.data.size');
    h += liveRow('Heap max (-Xmx)', s.heapMaxMb == null ? '—' : fmt(s.heapMaxMb) + ' MB', fmt(value('XmxGB') * 1024) + ' MB', haveState(s.heapMaxMb, value('XmxRawMB')), 'the JVM');
    h += liveRow('Resident memory (RSS)', s.rssMb == null ? '—' : gb(s.rssMb), gb(value('RssPlannedMB')), loadState(s.rssMb, value('RssPlannedMB')), 'VmRSS (Linux only)');
    h += liveRow('Machine memory / cores', (s.ramMb == null ? '—' : gb(s.ramMb)) + ' / ' + (s.cpus || '—'), value('RamRecommendedGB') + ' GB / ' + value('CoresRecommended'), haveState(s.ramMb, value('RamRecommendedGB') * 1024), 'the operating system; cores as hardware threads');
    h += liveRow('Disk free (data folder)', d.freeBytes == null ? '—' : fmt(d.freeBytes / 1073741824) + ' GB of ' + fmt(d.totalBytes / 1073741824), value('DiskRecommendedGB') + ' GB disk', haveState(d.totalBytes == null ? null : d.totalBytes / 1073741824, value('DiskRecommendedGB')), 'the disk budget (guard ' + (d.guardBytes == null ? '—' : fmt(d.guardBytes / 1073741824) + ' GB') + ')');
    h += liveRow('People now / today\'s most', l.usersActive == null ? '—' : l.usersActive + ' / ' + l.usersPeakToday, fmt(value('Users')), loadState(l.usersPeakToday, value('Users')), 'signed-in users who asked something in the last 5 minutes');
    h += liveRow('Requests a second, today\'s busiest minute', l.requestsPerSecondPeakToday == null ? '—' : fmt(l.requestsPerSecondPeakToday), fmt(value('PeakRps')), loadState(l.requestsPerSecondPeakToday, value('PeakRps')), 'this server\'s API requests (now ' + (l.requestsPerSecond == null ? '—' : fmt(l.requestsPerSecond)) + ')');
    $('[data-cap-live-table]').innerHTML = h + '</tbody>';
  }

  function renderGrowth() {
    var base = overrides(), g = st.grow;
    if (g.HotDates) { base.HotDates = g.HotDates; }
    if (g.Users) { base.Users = g.Users; }
    if (g.intraday != null) {
      var trades = base.Trades != null ? base.Trades : C.defaultOf(st.plan, 'Trades'), batches = base.IntradayBatches != null ? base.IntradayBatches : C.defaultOf(st.plan, 'IntradayBatches');
      base.UpdatesPerBatch = batches > 0 ? Math.round(trades * g.intraday / 100 / batches) : 0;
    }
    var s = (st.live && st.live.server) || {}, d = (st.live && st.live.disk) || {};
    var rows = C.growth(st.plan, base, [1, 1.5, 2, 3]);
    var h = '<thead><tr><th class="l">Trades (positions)</th><th>-Xmx</th><th>Collector</th><th>RAM</th><th>Disk</th><th>Cores</th><th class="l">Against this server</th></tr></thead><tbody>';
    rows.forEach(function (r) {
      var e = r.ev, x = e.value('XmxGB'), ram = e.value('RamRecommendedGB'), disk = e.value('DiskRecommendedGB'), cores = e.value('CoresRecommended'), gaps = [];
      if (s.heapMaxMb && x * 1024 > s.heapMaxMb + 1) { gaps.push('heap +' + Math.ceil(x - s.heapMaxMb / 1024) + ' GB'); }
      if (s.ramMb && ram * 1024 > s.ramMb * 1.02) { gaps.push('RAM +' + Math.ceil(ram - s.ramMb / 1024) + ' GB'); }
      if (d.totalBytes && disk > d.totalBytes / 1073741824) { gaps.push('disk +' + Math.ceil(disk - d.totalBytes / 1073741824) + ' GB'); }
      if (s.cpus && cores > s.cpus / 2) { gaps.push('cores +' + Math.ceil(cores - s.cpus / 2)); }
      var against = !st.live ? pill('none') : gaps.length ? '<span class="cap-pill ' + (gaps.length > 1 ? 'r' : 'a') + '">' + esc(gaps.join('; ')) + '</span>' : pill('room');
      h += '<tr' + (r.factor === st.factor ? ' class="hl"' : '') + ' data-cap-growth-row="' + r.factor + '"><td class="l">×' + r.factor + ' · ' + fmt(r.trades) + '</td><td>' + x + 'g</td><td>' +
        esc(e.value('GcChoice')) + '</td><td>' + ram + ' GB</td><td>' + fmt(disk) + ' GB</td><td>' + cores + '</td><td class="l">' + against + '</td></tr>';
    });
    $('[data-cap-growth]').innerHTML = h + '</tbody>';
  }

  // ------------------------------------------------------------------------------------------- A: the planner
  var STACK = [['HeapDates', 'Dates in memory'], ['HeapIntraday', 'Today\'s intraday layer'], ['HeapBiCaches', 'Caches at their budgets'],
    ['HeapTradeRows', 'Trade rows beyond the measured'], ['HeapBase', 'The server at start'], ['HeapUsers', 'Requests in flight'],
    ['HeapExposure', 'Exposure'], ['FooterCacheMB+HeapSearch+HeapFirstOpen', 'Footer cache, search, first open']];

  function renderPlan() {
    var v = value;
    $('[data-cap-answers]').innerHTML = [['Heap', '-Xmx' + v('XmxGB') + 'g', v('GcChoice') + ' · open-heap-mb ' + v('SetOpenHeapMB')], ['RAM', v('RamRecommendedGB') + ' GB', 'smallest workable ' + v('RamMinimumGB') + ' GB'],
      ['Disk', fmt(v('DiskRecommendedGB')) + ' GB', fmt(v('DiskLocalRecommendedGB')) + ' GB local if the lakes live elsewhere'], ['Cores', String(v('CoresRecommended')), fmt(v('PeakRps')) + ' requests a second at 70% busy']]
      .map(function (k) { return '<div class="cap-kpi"><div class="l">' + esc(k[0]) + '</div><div class="v" data-cap-answer="' + esc(k[0].toLowerCase()) + '">' + esc(k[1]) + '</div><div class="s">' + esc(k[2]) + '</div></div>'; }).join('');
    var total = v('HeapSteady'), parts = STACK.map(function (p) {
      var x = p[0].split('+').reduce(function (a, n) { return a + (v(n) || 0); }, 0);
      return [p[1], x];
    });
    $('[data-cap-stack]').setAttribute('aria-label', 'Heap after a collection ' + fmt(total) + ' MB by part');
    $('[data-cap-stack]').innerHTML = parts.map(function (p, i) { return p[1] > 0 ? '<i data-w="' + Math.round(p[1] / total * 10000) / 100 + '" data-bg="var(--cap-c' + (i + 1) + ')" title="' + esc(p[0] + ': ' + fmt(p[1]) + ' MB') + '"></i>' : ''; }).join('');
    $('[data-cap-legend]').innerHTML = parts.map(function (p, i) { return p[1] > 0 ? '<span data-sw="var(--cap-c' + (i + 1) + ')">' + esc(p[0]) + ' <b>' + fmt(p[1]) + ' MB</b> (' + Math.round(p[1] / total * 100) + '%)</span>' : ''; }).join('') +
      '<span class="cap-tot">After a collection <b>' + fmt(total) + ' MB</b> ÷ ' + v('LiveShare') + ' + loads ' + fmt(v('HeapLoadTransient')) + ' MB = ' + fmt(v('XmxRawMB')) + ' MB → <b>-Xmx' + v('XmxGB') + 'g</b></span>';
    styled($('[data-cap-stack]'));
    styled($('[data-cap-legend]'));
    $('[data-cap-warnings]').innerHTML = warnings(false);
    $('[data-cap-bands]').innerHTML = st.plan.results.map(function (b, i) {
      var rows = b.rows.map(function (r) {
        var x = v(r.name), big = ['XmxGB', 'RamRecommendedGB', 'DiskRecommendedGB', 'CoresRecommended', 'SetOpenHeapMB'].indexOf(r.name) >= 0;
        return '<tr' + (big ? ' class="tot"' : '') + ' data-cap-result="' + esc(r.name) + '"><td class="l">' + esc(r.label) + '</td><td>' + esc(fmt(x, r.unit)) + '</td><td class="l cap-muted">' + esc(r.unit === 'text' ? '' : r.unit) + '</td><td>' + why(r.why, r.expression) + '</td></tr>';
      }).join('');
      return '<details class="cap-card cap-fold" data-tier="' + (i < 3 ? 'glance' : 'more') + '" open><summary><h3>' + esc(b.band) + '</h3></summary><div class="cap-scroll"><table class="cap-tbl">' + rows + '</table></div></details>';
    }).join('');
    var on = function (c) { return !c || !!st.ev.eval(c); };
    $('[data-cap-settings]').innerHTML = '<thead><tr><th class="l">Exact key</th><th>Value</th><th class="l">Environment variable</th><th class="l">Server argument</th><th></th></tr></thead><tbody>' +
      st.plan.settings.map(function (s) {
        var x = v(s.name), shown = on(s.condition), val = shown ? C.text(x) : st.plan.settingOff;
        return '<tr data-cap-setting="' + esc(s.key) + '"><td class="k">' + esc(s.key) + '</td><td>' + esc(val) + '</td><td class="l cap-mono">' + esc(shown ? s.env + '=' + C.text(x) : '—') + '</td><td class="l cap-mono">' +
          esc(shown ? '--' + s.key + '=' + C.text(x) : '—') + '</td><td>' + why(s.why + (s.envSource === 'docs' ? ' The environment variable is CONFIGURATION.md\'s.' : ' No environment variable is documented: Spring\'s relaxed binding of the key.'), s.expression) + '</td></tr>';
      }).join('') + '</tbody>';
    $('[data-cap-yaml-where]').textContent = 'The YAML block for ' + st.plan.settingsFile + ':';
    $('[data-cap-yaml]').textContent = v('YamlBlock');
    $('[data-cap-jvm]').innerHTML = '<thead><tr><th class="l">Argument</th><th class="l">Value</th><th class="l">When</th><th></th></tr></thead><tbody>' + st.plan.jvm.map(function (j) {
      var x = v(j.name);
      return '<tr data-cap-jvm-row="' + esc(j.name) + '"><td class="l">' + esc(j.flag) + '</td><td class="k">' + esc(x === '' ? '(not set)' : x) + '</td><td class="l cap-muted">' + esc(j.condition) + '</td><td>' + why(j.why + ' [' + j.source + ']', j.expression) + '</td></tr>';
    }).join('') + '</tbody>';
    $('[data-cap-cmd]').textContent = v('JvmCommand');
  }

  function warnings(onlyNotOk) {
    var out = st.plan.warnings.map(function (w) { return [w, value(w.name)]; }).filter(function (p) { return p[1] && p[1] !== 'OK'; });
    if (!out.length) { return onlyNotOk ? '' : '<div class="cap-box g" data-cap-warning="none"><b>Warnings:</b> none at this plan.</div>'; }
    return out.map(function (p) { return '<div class="cap-box a" data-cap-warning="' + esc(p[0].name) + '"><b>' + esc(p[0].label) + ':</b> ' + esc(p[1]) + '</div>'; }).join('');
  }

  // the plan's inputs: one drishti-inputs Form from the planner's own list (a fieldset per group; a list of choices a select; a number read
  // loosely and shown grouped, its problem in words); each field's block carries data-cap-field (the name), marked "ch" when changed
  var paths = {};
  function inputSchema() {
    var props = {}, groups = {};
    st.plan.inputs.forEach(function (i) {
      var g = groups[i.group] || (groups[i.group] = 'g' + Object.keys(groups).length);
      if (!props[g]) { props[g] = { type: 'object', title: i.group, properties: {}, hook: { 'class': 'cap-grp' } }; }
      var fs = { title: i.label + (i.unit && i.unit !== 'text' ? ' (' + i.unit + ')' : ''), description: i.hint, hook: { 'data-cap-in': i.name } };
      if (i.choices) { Object.assign(fs, { type: 'string', widget: 'select', allowEmpty: false, 'enum': i.choices.map(String) }); }
      else if (i.kind === 'text') { fs.type = 'string'; } else { fs.type = 'number'; }
      props[g].properties[i.name] = fs;
      paths[i.name] = g + '.' + i.name;
    });
    return { type: 'object', properties: props };
  }
  function inputValue() {
    var v = {};
    st.plan.inputs.forEach(function (i) {
      var cur = st.inputs[i.name] != null ? st.inputs[i.name] : i['default'], p = paths[i.name].split('.');
      (v[p[0]] = v[p[0]] || {})[p[1]] = i.choices ? String(cur) : cur;
    });
    return v;
  }
  function buildInputs() {
    var host = $('[data-cap-inputs]');
    if (!st.form) {
      st.form = new window.Drishti.elements.inputs.Form(host, { schema: inputSchema(), value: inputValue(), live: true, label: 'Inputs', pathAttr: 'data-cap-path' });
      st.form.on('change', onInput);
    } else { st.form.setValue(inputValue()); }
    markChanged();
  }

  function markChanged() {
    st.plan.inputs.forEach(function (i) {
      var f = page.querySelector('[data-cap-path="' + paths[i.name] + '"]');
      if (f) { f.setAttribute('data-cap-field', i.name); f.classList.toggle('ch', st.inputs[i.name] != null && String(typed(i.name, st.inputs[i.name])) !== String(i['default'])); }
    });
  }

  function onInput(e) {
    var name = String(e.path).split('.').pop(), inp;
    inp = st.plan.inputs.filter(function (i) { return i.name === name; })[0];
    if (!inp) { return; }
    var v = typed(name, e.value);
    if (v === undefined) { return; }                                   // the field says what does not read
    if (String(v) === String(inp['default'])) { delete st.inputs[name]; } else { st.inputs[name] = v; }
    save();
    markChanged();
    recompute();
  }

  function save() { try { localStorage.setItem(KEY, JSON.stringify(st.inputs)); } catch (e) { /* private window */ } }

  // ------------------------------------------------------------------------------------------- the frame
  function recompute() {
    try {
      st.ev = C.evaluate(st.plan, overrides());
      st.ev.value('JvmCommand');
    } catch (e) {
      $('[data-cap-error]').hidden = false;
      $('[data-cap-error]').textContent = 'The plan cannot be computed: ' + e.message;
      return;
    }
    $('[data-cap-error]').hidden = true;
    renderToday();
    renderPlan();
    var meta = $('[data-cap-meta]');
    var at = st.live && st.live.measuredAt ? new Date(st.live.measuredAt).toLocaleString() : null;
    meta.textContent = (at ? 'Measured ' + at + ' · refreshes every 60 s' : (st.liveError || 'Not measured yet')) + ' · plan: ' + (Object.keys(st.inputs).length ? Object.keys(st.inputs).length + ' input(s) changed' : 'the defaults (the production shape)');
  }

  function getJson(url) {
    return fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) { if (!r.ok) { throw new Error((b && (b.detail || b.message)) || ('HTTP ' + r.status)); } return b; });
    });
  }

  function loadLive() {
    return getJson('/admin/api/capacity/live').then(function (l) { st.live = l; st.liveError = null; }, function (e) { st.liveError = 'Live figures unavailable: ' + e.message; })
      .then(function () { if (st.plan) { recompute(); } });
  }

  function setView(plan) {
    page.querySelector('[data-cap-view="today"]').hidden = plan;
    page.querySelector('[data-cap-view="plan"]').hidden = !plan;
    $('[data-cap-edit]').setAttribute('aria-pressed', plan ? 'true' : 'false');
    $('[data-cap-edit]').innerHTML = plan ? '<i class="bi bi-speedometer2" aria-hidden="true"></i> Today against the plan' : '<i class="bi bi-sliders" aria-hidden="true"></i> Edit the plan';
    $('[data-cap-title]').textContent = plan ? 'The plan: a single-node planner' : 'Today against the plan';
  }

  function download(mode) {
    var q = ['inputs=' + encodeURIComponent(mode)], o = overrides();
    Object.keys(o).forEach(function (k) { q.push(encodeURIComponent(k) + '=' + encodeURIComponent(o[k])); });
    var a = document.createElement('a');
    a.href = '/admin/api/capacity/workbook.xlsx?' + q.join('&');
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  function copy(text, btn) {
    var done = function () { var old = btn.innerHTML; btn.textContent = 'Copied'; setTimeout(function () { btn.innerHTML = old; }, 1500); };
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text).then(done, function () {}); return; }
    var t = document.createElement('textarea');
    t.value = text;
    document.body.appendChild(t);
    t.select();
    try { document.execCommand('copy'); done(); } catch (e) { /* nothing */ }
    t.remove();
  }

  function wire() {
    $('[data-cap-edit]').addEventListener('click', function () { setView(page.querySelector('[data-cap-view="plan"]').hidden); });
    $('[data-cap-refresh]').addEventListener('click', loadLive);
    $('[data-cap-reset]').addEventListener('click', function () { st.inputs = {}; save(); buildInputs(); recompute(); });
    $('[data-cap-use-live]').addEventListener('click', function () {
      var li = (st.live && st.live.inputs) || {};
      Object.keys(li).forEach(function (k) { if (li[k] != null && typed(k, li[k]) !== undefined) { st.inputs[k] = typed(k, li[k]); } });
      save();
      buildInputs();
      recompute();
    });
    var dlg = $('[data-cap-dialog]');
    $('[data-cap-download]').addEventListener('click', function () {
      var n = Object.keys(st.inputs).length;
      $('[data-cap-dlg-plan]').textContent = n ? 'The inputs as set here (' + n + ' changed from the defaults).' : 'The inputs as set here: the defaults, the production shape.';
      dlg.returnValue = '';
      if (dlg.showModal) { dlg.showModal(); } else { download('plan'); }
    });
    dlg.addEventListener('close', function () {
      if (dlg.returnValue === 'go') { download((dlg.querySelector('input[name=inputs]:checked') || {}).value || 'plan'); }
    });
    $('[data-cap-factors]').addEventListener('click', function (e) {
      var b = e.target.closest('[data-factor]');
      if (!b) { return; }
      st.factor = parseFloat(b.getAttribute('data-factor'));
      page.querySelectorAll('[data-factor]').forEach(function (x) { x.setAttribute('aria-checked', x === b ? 'true' : 'false'); });
      renderGrowth();
    });
    growth().on('change', function (e) { st.grow[e.path] = Number(e.value); renderGrowth(); });
    page.addEventListener('click', function (e) {
      var w = e.target.closest('.cap-why');
      page.querySelectorAll('.cap-why.open').forEach(function (x) { if (x !== w) { x.classList.remove('open'); } });
      if (w) { w.classList.toggle('open'); }
      var c = e.target.closest('[data-cap-copy]');
      if (c) { copy(c.getAttribute('data-cap-copy') === 'yaml' ? $('[data-cap-yaml]').textContent : $('[data-cap-cmd]').textContent, c); }
    });
    page.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { page.querySelectorAll('.cap-why.open').forEach(function (x) { x.classList.remove('open'); }); }
      if ((e.key === 'Enter' || e.key === ' ') && e.target.classList && e.target.classList.contains('cap-why')) { e.preventDefault(); e.target.classList.toggle('open'); }
    });
    if (page.getAttribute('data-help')) {                               // F1: the guide's Capacity section, not the Admin guide's top
      window.Drishti.keys.register({ id: 'capacity.help', keys: ['F1', 'Alt+Shift+1'], scope: 'screen', area: 'capacity', group: 'Help', typing: true,
        description: 'Help: the Capacity section of the guide', run: function () { window.location.href = page.getAttribute('data-help'); } });
    }
    if (phone.matches) { page.querySelectorAll('details[data-tier="more"]').forEach(function (d) { d.removeAttribute('open'); }); }
    document.addEventListener('visibilitychange', function () { if (!document.hidden) { loadLive(); } });
    st.timer = setInterval(function () { if (!document.hidden) { loadLive(); } }, 60000);
  }

  function growth() { return window.Drishti.elements.inputs.formOf(page.querySelector('[data-cap-growth-fields]')); }   // the sliders (core/admin_forms.py)

  function seedGrowth() {
    var t = value('Trades'), u = value('IntradayUpdatesDay');
    growth().setValue({ intraday: t > 0 ? Math.round(u / t * 100) : 0, HotDates: value('HotDates'), Users: value('Users') });
  }

  try { st.inputs = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { st.inputs = {}; }
  getJson('/admin/api/capacity').then(function (plan) {
    st.plan = plan;
    Object.keys(st.inputs).forEach(function (k) { if (typed(k, st.inputs[k]) === undefined) { delete st.inputs[k]; } });
    buildInputs();
    wire();
    recompute();
    seedGrowth();
    if (/[#&]plan\b/.test(location.hash)) { setView(true); }
    return loadLive();
  }).catch(function (e) {
    $('[data-cap-error]').hidden = false;
    $('[data-cap-error]').textContent = 'The planner could not be read: ' + e.message;
  });
})();
