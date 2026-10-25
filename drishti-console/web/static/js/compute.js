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
/* Computed panels on a view page (REACTIVE_CONNECTORS.md 6.2 and 12.4). A panel whose source a reactive connector computes carries its
   request id (data-compute-req). The page never waits for it: such a panel is drawn at once, computing (with the last good result dimmed,
   if there is one), ready, stale, failed or refused. This listens to each request on the browser's one live channel (compute:<id>, its
   owner's only): progress moves the panel's bar; done, failed or updated (a trigger recomputed what it shows) fetch the panels of that
   request again through refresh.js, which swaps them in place; state marks them stale in amber. Requests of panels no longer on the page
   are given back. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  if (!view) { return; }
  var subs = {};                                     // request id -> unsubscribe

  function sections(req) {
    return Array.prototype.filter.call(view.querySelectorAll('section.pnl[data-compute-req]'), function (s) { return s.getAttribute('data-compute-req') === req; });
  }
  function width(bar) {
    var w = +bar.getAttribute('data-w') || 0;
    if (w > 0) { bar.style.width = Math.min(100, w) + '%'; }
  }
  function progress(req, d) {
    if (!d || typeof d.done !== 'number' || !d.of) { return; }
    var pct = Math.max(0, Math.min(100, Math.round(d.done * 100 / d.of)));
    sections(req).forEach(function (s) {
      var bar = s.querySelector('[data-compute-bar]'), t = s.querySelector('[data-compute-pct]');
      if (bar) { bar.setAttribute('data-w', String(pct)); width(bar); }
      if (t) { t.textContent = pct + '%'; }
    });
  }
  function stale(req, d) {
    sections(req).forEach(function (s) {
      s.setAttribute('data-compute', 'stale');
      var line = s.querySelector('[data-compute-strip] .cmp-t');
      if (line && d && d.message) { line.textContent = 'Stale · ' + d.message; }
    });
  }
  function again(req) {
    var ids = sections(req).map(function (s) { return s.getAttribute('data-panel'); });
    if (ids.length && window.drishtiRefresh && window.drishtiRefresh.fetch) { window.drishtiRefresh.fetch(ids); }
  }
  function watch(s) {
    var req = s.getAttribute('data-compute-req');
    var bar = s.querySelector('[data-compute-bar]');
    if (bar) { width(bar); }
    if (!req || subs[req] || !window.DrishtiChannel) { return; }
    subs[req] = window.DrishtiChannel.subscribe('compute:' + req, {
      progress: function (d) { progress(req, d); },
      state: function (d) { stale(req, d); },
      done: function () { again(req); },
      failed: function () { again(req); },
      updated: function () { again(req); },
      gone: function () { delete subs[req]; }
    });
  }
  function prune() {
    Object.keys(subs).forEach(function (req) {
      if (!sections(req).length) { try { subs[req](); } catch (e) { /* already given back */ } delete subs[req]; }
    });
  }
  function scan(root) {
    (root || view).querySelectorAll('section.pnl[data-compute-req]').forEach(watch);
    prune();
  }
  scan();
  if (window.MutationObserver) {                     // a panel swapped in (its result landed, a control, a click) brings a new request id
    var pending = false;
    new MutationObserver(function () {
      if (pending) { return; }
      pending = true;
      setTimeout(function () { pending = false; scan(); }, 50);
    }).observe(view, { childList: true, subtree: true });
  }
  window.drishtiCompute = { watching: function () { return Object.keys(subs); } };
})();
