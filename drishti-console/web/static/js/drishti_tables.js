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
/* Every table sorts, filters, pages and can be walked with the keyboard: drishti-grid's console tables (Drishti Elements,
   consoleTables: sort by a heading, the filter box and the column funnels, pages and rows per page, ↑ ↓ PgUp PgDn Home End Enter, a
   zoomed panel fitted to its height, the state kept through a live repaint). This file is their host on a page: the column funnels
   (drishti_colfilter.js), the keymap, and Chart any table (RACHANA_REFERENCE.md: a chart button in the heading and K open the table as a
   chart, drishti_table-chart.js, loaded at the first use with its engine and ECharts, so a page with tables pays nothing until then;
   a table with data-no-chart has none). A table with data-plain is left as it is (a form laid out as a table). */
(function () {
  'use strict';
  var me = document.currentScript;
  var CF = (window.drishtiModules || {}).colFilter;     // column filters; loaded before this script
  var VER = me && /[?&]v=([^&]+)/.exec(me.src || '') ? /[?&]v=([^&]+)/.exec(me.src)[1] : '';
  // where the chart kit comes from: beside this script (the console's /static/, or /embed/v2/ for the client-side renderer on another site)
  var ROOT = me && /\/js\/tables\.js/.test(me.src || '') ? me.src.replace(/js\/(?:drishti_)?tables\.js.*$/, '') : '/static/';

  // ---- Chart any table: the dialog and its scripts, loaded at the first use ------------------------------------------------
  var kit = null;
  function script(src) {
    return new Promise(function (ok, fail) {
      var x = document.createElement('script');
      x.src = ROOT + src + (VER ? '?v=' + VER : '');
      x.onload = ok; x.onerror = function () { fail(new Error('could not load ' + src)); };
      document.head.appendChild(x);
    });
  }
  /** The chart kit {open, sources}: echarts, the pivot and chart engines, the adapters, the dialog and its stylesheet. */
  function chartKit() {
    if (kit) { return kit; }
    if (!document.querySelector('link[data-table-chart-css]')) {
      var l = document.createElement('link');
      l.rel = 'stylesheet'; l.href = ROOT + 'css/drishti_table-chart.css' + (VER ? '?v=' + VER : ''); l.setAttribute('data-table-chart-css', '');
      document.head.appendChild(l);
    }
    var steps = [];
    if (!window.echarts) { steps.push('vendor/echarts/echarts.min.js'); }
    if (!window.Drishti.pivotEngine && !((window.drishtiModules || {}).pivotEngine)) { steps.push('js/drishti_pivot-engine.js'); }
    if (!window.Drishti.tableChartEngine) { steps.push('js/drishti_table-chart-engine.js'); }
    if (!window.Drishti.tableChartSources) { steps.push('js/drishti_table-chart-sources.js'); }
    if (!window.Drishti.tableChart) { steps.push('js/drishti_table-chart.js'); }
    kit = steps.reduce(function (p, src) { return p.then(function () { return script(src); }); }, Promise.resolve()).then(function () {
      if (!window.Drishti.pivotEngine && window.drishtiModules && window.drishtiModules.pivotEngine) { window.Drishti.pivotEngine = window.drishtiModules.pivotEngine; }
      return { open: window.Drishti.tableChart.open, sources: window.Drishti.tableChartSources };
    });
    kit.catch(function () { kit = null; });
    return kit;
  }
  function fetchJson(url, init) {
    init = init || {};
    var o = { method: init.method || 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } };
    if (init.body) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(init.body); }
    return fetch(url, o).then(function (r) { return r.json().catch(function () { return {}; }); });
  }
  /** Opens a source (an adapter, or a function of the kit that makes one) as a chart; opener gets the focus back. */
  function openChart(make, opener) {
    return chartKit().then(function (k) { return k.open(typeof make === 'function' ? make(k.sources) : make, { opener: opener, fetchJson: fetchJson }); }, function (e) {
      window.alert('The chart could not open: ' + e.message);
    });
  }
  /** init(root, options): the console's tables under root (the document or a ShadowRoot), now and as panels are swapped in.
      options.scope names the page for remembered state (default: the path); options.chart offers Chart any table inside a shadow root
      too (the client-side renderer's behaviours: 'console'; the dialog opens over the host's page). Returns {scan, dispose}. */
  function init(root, options) {
    var G = window.Drishti.elements && window.Drishti.elements.grid;
    options = options || {};
    if (!G) { return { scan: function () {}, dispose: function () {} }; }
    return G.consoleTables(root, {
      scope: options.scope, chart: !!options.chart, columnFilters: CF, keys: root === document ? window.Drishti.keys : null,
      storageKey: window.Drishti.key ? window.Drishti.key('drishti.tableRows') : 'drishti.tableRows',
      openChart: function (t, view, opener) { return openChart(function (S) { return S.forTable(t, view, fetchJson); }, opener); }
    });
  }
  var reg = (window.drishtiModules = window.drishtiModules || {});
  reg.tables = { init: init, chartKit: chartKit, openChart: openChart, fetchJson: fetchJson };
  if (!(me && me.hasAttribute('data-manual'))) { init(document); }
})();
