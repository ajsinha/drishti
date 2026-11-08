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
/* Expandable rows (RACHANA_REFERENCE.md, children: on a table).
   1. A table or ladder whose Sutra says children: (table.tbl-tree) is drawn by drishti-tree (Drishti Elements' table tree,
      drishti_tree-panel.js): the tree grid on drishti-grid's tree data (+ and - open and close the rows under a row, any number of
      levels deep; the filter box and the grid's column filters keep the rows that match and their ancestors), or the org chart of the
      same rows (a tree that is one chain opens as it); every cell the server's text, tone and links. The server's table stays hidden
      for print and for pages without the library.
   2. A pivot by a list of fields (div.pv-tree-host): the nested rows are drawn by drishti-grid (Drishti Elements, the Pivot screen's
      grid: + and − on the groups, subtotals, the grand total) from the panel's own data, every figure the pivot engine's (ChartBinder
      computes them with drishti-bicompute) and shown as the server's text; one grid for pivot rows.
   Live updates replace panels: new ones are enhanced when they appear (data-tree-ready marks the ones already done); a tree keeps
   its mode, open rows and filter across them. */
(function () {
  'use strict';
  var me = document.currentScript;
  var ROOT = me && /\/js\/(?:drishti_)?tree-rows\.js/.test(me.src || '') ? me.src.replace(/js\/(?:drishti_)?tree-rows\.js.*$/, '') : '/static/';
  var VER = me && /[?&]v=([^&]+)/.exec(me.src || '') ? /[?&]v=([^&]+)/.exec(me.src)[1] : '';

  // ---- table rows with children: drishti-tree's table tree ---------------------------------------------------------
  var memory = {};                                         // each tree's mode, open rows and filter by table, so a live patch that replaces it keeps them
  var live = [];                                           // the trees drawn (those whose table left the page are taken down on the next scan)
  // a tree's filter is in the console's address (?tf.<panel id>=<words>), so a reload or a shared link shows the same rows (not inside
  // <drishti-view>: the host's address is not touched)
  var ADDRESS = {
    read: function (pid) { return new URLSearchParams(location.search).get('tf.' + pid); },
    write: function (pid, q) {
      var u = new URL(location.href), k = 'tf.' + pid;
      if ((u.searchParams.get(k) || '') === q) { return; }
      if (q) { u.searchParams.set(k, q); } else { u.searchParams.delete(k); }
      history.replaceState(history.state, '', u.pathname + u.search + u.hash);
    }
  };
  /** Draws the tree tables under node with drishti-tree (Drishti Elements: loaded on demand); without the library the server's table stays. */
  function treeTables(node, scope, root) {
    var E = window.Drishti && window.Drishti.elements;
    if (!E) { return; }
    var loader = E.load || (E.core && E.core.load);
    if (!E.tree && !loader) { return; }
    (E.tree ? Promise.resolve(E.tree) : loader('drishti-tree')).then(function (T) {
      live = T.treeTables(node, { memory: memory, scope: scope, live: live, address: root === document ? ADDRESS : null });
    }, function () { /* the server's table stays */ });
  }

  // ---- a pivot by several fields, drawn by drishti-grid ---------------------------------------------------------------
  /** The grid's sheet (elements/drishti-grid/drishti_grid.css), once per document or shadow root that shows a nested pivot. */
  function sheet(host) {
    if (/\/embed\/v1\/$/.test(ROOT)) { return; }        // <drishti-view>'s own sheet carries the grid's (tools/elements_sheet.py)
    var at = host.getRootNode ? host.getRootNode() : document, into = at === document || !at.host ? document.head : at;
    if (into.querySelector('link[data-grid-css]')) { return; }
    var l = document.createElement('link');
    l.rel = 'stylesheet'; l.href = ROOT + 'elements/drishti-grid/drishti_grid.css' + (VER ? '?v=' + VER : ''); l.setAttribute('data-grid-css', '');
    into.appendChild(l);
  }
  var US = '\u001f';
  /**
   * The panel's nested rows as a pivot answer the grid draws: each group a node (open or closed), its rows under it while open, the
   * columns of `across` and the total; every cell keeps the server's text and tone (formats, masks), its figure for the heat.
   */
  function answerOf(d, closed) {
    var cols = (d.columns || []).map(function (c) { return { kind: 'node', key: 'c' + US + c, label: c, path: [['across', c]], depth: 1 }; });
    if (d.totals) { cols.push({ kind: 'grand', key: '*', label: 'Total', path: [], depth: 0 }); }
    var label = labelOf(d), levels = d.levels || [];
    var rows = [], cells = {}, hidden = null;
    function cell(v, c) {                                  // the measure's figure (for the heat), the server's text and tone
      var o = { values: {}, texts: {}, tones: {} };
      o.values[label] = typeof v === 'number' ? v : null; o.texts[label] = c ? c.text : ''; o.tones[label] = c && c.tone ? 't-' + c.tone : '';
      return o;
    }
    (d.rows || []).forEach(function (r) {
      var path = r.path || [r.label], key = path.join(US);
      if (hidden !== null && key.indexOf(hidden + US) === 0) { return; }
      hidden = null;
      if (r.group && closed[key]) { hidden = key; }
      rows.push({ kind: 'node', key: key, label: r.label, depth: path.length, expandable: !!r.group, expanded: !!r.group && !closed[key],
        path: path.map(function (v, i) { return [levels[i] || 'level' + (i + 1), v]; }) });
      (r.cells || []).forEach(function (c, i) {
        if (!cols[i]) { return; }
        cells[key + '|' + cols[i].key] = cell((r['values'] || [])[i], c);
      });
      if (r.total) { cells[key + '|*'] = cell(null, r.total); }
    });
    if (d.totals) {
      rows.push({ kind: 'grand', key: '*', label: 'Total', depth: 0, path: [] });
      d.totals.forEach(function (c, i) {
        var k = i < (d.columns || []).length ? cols[i].key : '*';
        cells['*|' + k] = cell(null, c);
      });
    }
    return { rowDimensions: levels, columnDimensions: [], columns: cols, rows: rows, cells: cells,
      measures: [{ id: d.agg || 'value', label: label, kind: 'server', server: true }] };
  }
  function labelOf(d) { return { sum: 'Sum', count: 'Count', avg: 'Average', min: 'Min', max: 'Max' }[d.agg] || d.agg || 'Value'; }

  function pivotTree(host) {
    var E = window.Drishti.elements;
    if (!E || (!E.grid && !E.load)) { return; }           // no Drishti Elements on the page: the plain table stays
    if (!E.grid) { host.setAttribute('data-tree-ready', ''); E.load('drishti-grid').then(function () { draw(host); }, function () { /* the plain table stays */ }); return; }
    draw(host);
    host.setAttribute('data-tree-ready', '');
  }

  /** Draws the panel's pivot with drishti-grid: the panel's own figures (the engine's, computed by ChartBinder with drishti-bicompute),
      shown as the server's text; a + or − opens or closes a group in the page (nothing is asked again: the panel holds every level). */
  function draw(host) {
    var G = window.Drishti.elements.grid;
    var d = JSON.parse(host.getAttribute('data-pv-tree')), closed = {}, label = labelOf(d);
    (d.rows || []).forEach(function (r) {                  // the groups shown closed at first: those at or below `expand` levels
      if (r.group && (r.path || []).length >= d.expand) { closed[(r.path || []).join(US)] = true; }
    });
    sheet(host);
    var box = document.createElement('div');
    box.className = 'pv-tree-grid';
    host.textContent = '';
    host.appendChild(box);
    size(answerOf(d, closed).rows.length, 28);              // a height before the first draw: the grid draws the rows its height holds
    var grid = new G.Grid(box, {
      columns: [{ id: 'v', field: 'v', header: label, type: 'number', measure: d.agg || 'value', additive: false, enableValue: true }],
      layout: 'wells', density: 'compact', chrome: false, checkboxes: false, rowSelection: 'none', clientAggregation: false, pinTotals: false,
      label: (d.levels || []).join(' › ') || 'Nested rows',
      conditionalFormats: d.heat ? [{ measure: label, type: 'colourScale' }] : [],
      serverPivot: function () { return Promise.resolve(answerOf(d, closed)); }
    });
    // a + or −: the grid tells which group before it asks again, and the answer is this panel's own rows with that group open or closed
    box.addEventListener('drishti:row-expanded', function (e) { delete closed[pathKey(e.detail.path)]; });
    box.addEventListener('drishti:row-collapsed', function (e) { closed[pathKey(e.detail.path)] = true; });
    box.addEventListener('drishti:pivot-changed', function () { fit(); });
    function fit() { if (size(grid.model.rowCount(), grid.model.viewport.rowHeight)) { grid.refresh(); } }
    function size(n, rh) {                                 // as tall as its rows (a panel, not a page), at most 30 rows before it scrolls
      var px = (Math.min(n, 30) + 2) * rh + 6 + 'px';
      if (box.style.height === px) { return false; }
      box.style.height = px;
      return true;
    }
    grid.setPivot({ rows: [], columns: [], values: [{ colId: 'v' }], filters: [] });
  }
  function pathKey(path) { return (path || []).map(function (p) { return p[1]; }).join(US); }

  /** init(root, options): expandable rows under root (the document or a ShadowRoot), now and as panels are swapped in. */
  function init(root, options) {
    function scan(node) {
      if (!node.querySelectorAll) { return; }
      if (node.querySelector('table.tbl-tree:not([data-tree-ready])') || (node.matches && node.matches('table.tbl-tree:not([data-tree-ready])'))) {
        treeTables(node, (options && options.scope) || location.pathname, root);
      }
      var hosts = Array.prototype.slice.call(node.querySelectorAll('.pv-tree-host:not([data-tree-ready])'));
      if (node.matches && node.matches('.pv-tree-host:not([data-tree-ready])')) { hosts.push(node); }
      hosts.forEach(pivotTree);
    }
    scan(root);
    var mo = new MutationObserver(function (list) {
      list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { scan(n); } }); });
    });
    mo.observe(root === document ? document.body : root, { childList: true, subtree: true });
    return { scan: scan, dispose: function () { mo.disconnect(); } };
  }
  (window.drishtiModules = window.drishtiModules || {}).treeRows = { init: init };
  if (!(me && me.hasAttribute('data-manual'))) { init(document); }
})();
