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
/*
 * The page's "settled" signal (docs/architecture/TEST_HARNESS.md, "The page-settled signal"). Inert: nothing runs until something asks,
 * apart from a request count kept under automation only (below).
 *
 *   Drishti.settled(options) -> Promise of {waited, quiet}
 *
 * resolves once the page is ready to be read:
 *   - the document loaded and its fonts in;
 *   - every panel chart and map drawn (an ECharts instance on every .chart and .xchart that is not .pnl-empty, in the page and in any
 *     open shadow root) and, unless options.animations is false, its animation finished;
 *   - nothing marked aria-busy in the view or a render host (a control's query, a panel being fetched or swapped in);
 *   - the Discussion's badges placed when the Discussion is on;
 *   - under automation, no fetch of the page's own still unanswered;
 *   - then no change to the page outside the charts for options.quiet milliseconds (default 50), and two frames.
 * It rejects after options.timeout milliseconds (default 30000) naming what is still pending, so a test that waits names the cause.
 * A browser test waits on this instead of a fixed pause; a person's page never calls it.
 */
(function () {
  'use strict';
  // Under automation only (navigator.webdriver: Playwright, never a person's browser) the page's fetches are counted from the moment
  // this file runs, so "settled" also means no request of the page's own still unanswered (a badge count, a panel's data). The count
  // lives on window.Drishti so that the copy a test puts on every page first and the console's own copy share it.
  var D = window.Drishti;
  if (navigator.webdriver && window.fetch && !window.fetch.__drishtiCounted) {
    var plain = window.fetch;
    D.__inflight = 0;
    var counted = function () {
      D.__inflight += 1;
      var p;
      try { p = plain.apply(this, arguments); } catch (e) { D.__inflight -= 1; throw e; }
      return p.then(function (r) { D.__inflight -= 1; return r; }, function (e) { D.__inflight -= 1; throw e; });
    };
    counted.__drishtiCounted = true;
    window.fetch = counted;
  }
  var CHARTS = '.chart:not(.pnl-empty), .xchart:not(.pnl-empty)';
  var BUSY = '.view[aria-busy="true"], .view [aria-busy="true"], [data-render-host][aria-busy="true"]';

  function scopes() {   // the page and every open shadow root in it (a render host, an embedded element)
    var out = [document];
    document.querySelectorAll('*').forEach(function (h) { if (h.shadowRoot) { out.push(h.shadowRoot); } });
    return out;
  }

  /** What is still pending, as words; an empty list when the page is ready to be read. */
  function pending(animations) {
    var why = [];
    if (document.readyState !== 'complete') { why.push('document ' + document.readyState); }
    var undrawn = 0, moving = 0;
    scopes().forEach(function (s) {
      s.querySelectorAll(CHARTS).forEach(function (el) {
        var c = window.echarts && window.echarts.getInstanceByDom(el);
        if (!c) { undrawn += 1; } else if (animations !== false && !c.isDisposed() && !c.getZr().animation.isFinished()) { moving += 1; }
      });
      if (s !== document && s.querySelector('[aria-busy="true"]')) { why.push('render host busy'); }
    });
    if (undrawn) { why.push(undrawn + ' chart(s) not drawn'); }
    if (moving) { why.push(moving + ' chart(s) still animating'); }
    if (document.querySelector(BUSY)) { why.push('aria-busy'); }
    if (D.__inflight > 0) { why.push(D.__inflight + ' request(s) unanswered'); }
    var open = document.querySelector('[data-discussion-open]');
    if (window.Drishti.discussion && open && !open.hidden && !document.querySelector('[data-view][data-embed]')) {
      var codes = document.querySelectorAll('.pnl[data-panel] .pnl-code'), missing = 0;
      codes.forEach(function (c) { if (!c.querySelector('.disc-badge')) { missing += 1; } });
      if (missing) { why.push(missing + ' discussion badge(s) not placed'); }
    }
    return why;
  }

  function frames(n) {
    return new Promise(function (r) { (function step(k) { if (k <= 0) { r(); } else { requestAnimationFrame(function () { step(k - 1); }); } })(n); });
  }

  function settled(options) {
    var o = options || {}, quiet = o.quiet == null ? 50 : o.quiet, timeout = o.timeout == null ? 30000 : o.timeout;
    var began = Date.now(), last = Date.now();
    // a change inside a chart's box is the chart drawing or animating: its own state is asked of ECharts above, not timed here
    var mo = new MutationObserver(function (list) {
      for (var i = 0; i < list.length; i += 1) {
        var t = list[i].target, el = t.nodeType === 1 ? t : t.parentElement;
        if (!(el && el.closest && el.closest('.chart, .xchart'))) { last = Date.now(); return; }
      }
    });
    scopes().forEach(function (s) { mo.observe(s === document ? document.documentElement : s, { subtree: true, childList: true, attributes: true, characterData: true }); });
    var fonts = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
    return fonts.then(function () {
      return new Promise(function (resolve, reject) {
        (function check() {
          var why = pending(o.animations), now = Date.now();
          if (!why.length && now - last >= quiet) {
            mo.disconnect();
            frames(2).then(function () { resolve({ waited: Date.now() - began, quiet: quiet }); });
            return;
          }
          if (now - began > timeout) {
            mo.disconnect();
            reject(new Error('the page did not settle in ' + timeout + ' ms: ' + (why.length ? why.join(', ') : 'it kept changing')));
            return;
          }
          setTimeout(check, why.length ? 16 : Math.max(8, quiet - (now - last)));
        })();
      });
    });
  }

  window.Drishti.settled = settled;
  window.Drishti.settledPending = pending;
})();
