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
/* The trader home's host (home/trader_home_landing.html; SHARED_SCREENS.md section 4): loads drishti-grid and drishti-risk (with drishti-core)
   and starts its TraderHome with three hooks: load (GET /api/home/trading/home?since=, the servers' answers as the person), live (the browser's
   one live channel, subscription trading:home: the server's ticks, which carry no data) and openBook (a book position by position: the VaR
   explorer of the book). After the first answer it keeps the time of this visit (POST /api/home/trading/visit) for "since my last visit".
   The page computes nothing: every figure is the server's, every number carries the one Explain (data-explain). */
(function () {
  'use strict';
  var root = document.querySelector('[data-trader-home]');
  if (!root) { return; }
  var visited = false;

  function answer(r) {
    return r.json().catch(function () { return {}; }).then(function (b) {
      if (!r.ok) { var e = new Error((b && (b.detail || b.reason)) || ('HTTP ' + r.status)); e.code = b && b.code; throw e; }
      return b;
    });
  }
  function load(since, o) {
    return fetch('/api/home/trading/home?since=' + encodeURIComponent(since) + (o && o.fresh ? '&fresh=1' : ''), { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
      .then(answer);
  }
  function visit() {
    if (visited) { return; }
    visited = true;
    fetch('/api/home/trading/visit', { method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, credentials: 'same-origin', body: '{}' })
      .catch(function () { /* the next visit then counts from the morning */ });
  }
  function live(h) {
    var C = window.Drishti.Channel;
    if (!C) { h.state('off'); return function () {}; }
    h.state('connecting');
    return C.subscribe('trading:home', {
      hello: function () { h.state('live'); },
      status: function () { h.state('live'); },
      tick: function (d) { h.tick(d); },
      gone: function () { h.state('off'); },
      error: function () { h.state('reconnecting'); }
    });
  }
  function openBook(book) { window.location.href = '/var?scope=' + encodeURIComponent('BOOK:' + book); }

  var E = window.Drishti.elements;
  if (!E || !E.load) {
    root.textContent = 'The trader home needs Drishti Elements (drishti-core), which this page did not load.';
    return;
  }
  E.load(['drishti-grid', 'drishti-risk']).then(function (libs) {
    var home = new libs[1].TraderHome(root, { Grid: libs[0].Grid, load: load, live: live, openBook: openBook, density: 'terminal', onPainted: visit });
    window.Drishti.traderHome = home;                          // for the page's tests and the console's keys
    return home.start();
  }, function (e) {
    root.textContent = 'The trader home could not be drawn: ' + (e && e.message ? e.message : e);
  });
})();
