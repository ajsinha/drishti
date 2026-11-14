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
/* Records everywhere: every table of records of the console is drishti-grid's LiveSetGrid (live and static mode), mounted by this one file. A page marks
   a host with data-records and one of three things: data-live-set (the id of a result set of the server's live engine: the rows are read from
   /api/live/<id>/rows and tick when the set is live; data-offset and data-limit name the page of a larger set), data-rows (a result without a record key:
   a JSON array of plain rows, shown in the grid's static mode: no merge, no edit, the panel shows the row's JSON) or nothing (a script calls
   Drishti.records.mount(host, {rows, columns, title}) with rows it has read). data-spec holds the set's declaration (or, static, {title, columns}). The
   grid's own look, its sort, filter, grouping, record panel and Pivot, are the same everywhere; nothing of a table is drawn by a page. */
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
(function () {
  'use strict';
  var CSS = ['/static/elements/drishti-grid/drishti_grid.css', '/static/elements/drishti-inputs/drishti_inputs.css', '/static/css/drishti_records.css'];
  var tag = document.querySelector('script[src*="drishti_records.js"]'), version = ((tag && tag.getAttribute('src')) || '').split('?')[1] || '';
  var NONE = { subscribe: function () { return function () {}; } };

  function styles() {
    CSS.forEach(function (href) {
      var name = href.split('/').pop();
      if (document.querySelector('link[href*="' + name + '"]')) { return; }
      var l = document.createElement('link');
      l.rel = 'stylesheet'; l.href = href + (version ? '?' + version : '');
      document.head.appendChild(l);
    });
  }
  /** Chart these rows: the held rows as the columns draw them, handed to the one chart dialog (Chart any table, drishti_table-chart.js). */
  function chart(x) {
    var tables = (window.drishtiModules || {}).tables;
    if (!tables) { return; }
    var cols = (x.columns || []).filter(function (c) { return c.field; });
    var res = { columns: cols.map(function (c) { return { name: c.label || c.field, label: c.label || c.field, type: c.type === 'number' || c.type === 'amount' ? 'number' : 'text', key: c.type === 'id' }; }),
      rows: x.rows.map(function (r) { return cols.map(function (c) { var v = r[c.field]; return Array.isArray(v) ? v.join(', ') : v; }); }) };
    tables.openChart(function (S) { return S.bi(res, { title: x.title || 'Results' }); }, document.activeElement);
  }
  function json(text, fallback) { try { return text ? JSON.parse(text) : fallback; } catch (e) { return fallback; } }

  /** Draws one table of records into host. o: {set, live, spec, rows, columns, title, offset, limit, layout}. Answers the LiveSetGrid (or null). */
  function mount(host, o) {
    var grid = window.Drishti.elements && window.Drishti.elements.grid;
    if (!grid || !grid.LiveSetGrid) { host.textContent = 'The grid did not load: reload the page.'; return null; }
    styles();
    host.classList.add('rc-host');
    var opts = { spec: o.spec || { title: o.title, columns: o.columns }, offset: o.offset || 0, snapshotRows: o.limit || undefined, layout: o.layout || 'panel', onChart: chart };
    if (o.set) {
      opts.api = grid.httpLiveApi({ id: o.set });
      opts.channel = o.live && window.Drishti.Channel ? { subscribe: function (h) { return window.Drishti.Channel.subscribe('live:' + o.set, h); } } : NONE;
    } else {
      opts.rows = o.rows || [];
    }
    var live = new grid.LiveSetGrid(host, opts);
    host.setAttribute('data-records-mode', o.set ? 'engine' : 'static');
    live.start().then(function () { host.setAttribute('data-records-ready', 'true'); },
      function (e) { host.textContent = (e && e.message) || 'The rows could not be read.'; });
    host.drishtiRecords = live;
    return live;
  }

  function auto(root) {
    (root || document).querySelectorAll('[data-records]:not([data-records-mode])').forEach(function (host) {
      var set = host.getAttribute('data-live-set') || '';
      mount(host, { set: set, live: host.getAttribute('data-live') === 'on', spec: json(host.getAttribute('data-spec'), null), rows: set ? null : json(host.getAttribute('data-rows'), []),
        offset: +host.getAttribute('data-offset') || 0, limit: +host.getAttribute('data-limit') || 0, title: host.getAttribute('data-title') || '' });
    });
  }
  window.Drishti.records = { mount: mount, auto: auto };
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', function () { auto(); }); } else { auto(); }
})();
