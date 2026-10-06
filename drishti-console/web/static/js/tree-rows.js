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
/* Expandable rows (PANEL_KINDS.md, Tree rows).
   1. A table or ladder whose Sutra says children: (table.tbl-tree): the ▸/▾ button of a row opens and closes the rows under
      it, any number of levels deep; the filter box keeps the ancestors of the rows that match.
   2. A pivot by a list of fields (div.pv-tree-host): the nested rows are drawn by the Pivot tab's grid (pivot-grid.js, the
      same ▸/▾ groups and subtotals) from the panel's own data, so there is one renderer for collapsible row groups.
   Both are plain buttons, so Tab, Enter and Space work; the state is aria-expanded. Live updates replace panels: new ones
   are enhanced when they appear (data-tree-ready marks the ones already done). */
(function () {
  'use strict';
  var me = document.currentScript, reg0 = window.drishtiModules || {};
  var E = reg0.pivotEngine || window.drishtiPivotEngine, G = reg0.pivotGrid || window.drishtiPivotGrid;

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }

  // ---- table rows with children -----------------------------------------------------------------------------------
  function treeTable(t) {
    var body = t.tBodies[0];
    if (!body) { return; }
    var rows = Array.prototype.slice.call(body.rows), q = '';
    rows.forEach(function (r) {
      r.__depth = +r.getAttribute('data-depth');
      var b = r.querySelector('.tr-tog');
      r.__open = !!b && b.getAttribute('aria-expanded') === 'true';
      r.__text = r.textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').toLowerCase();
    });
    function paint() {
      var hit = [];
      if (q) {                                             // a match, and every ancestor of a match (shown open)
        hit = rows.map(function (r) { return r.__text.indexOf(q) >= 0; });
        rows.forEach(function (r, i) {
          if (r.__text.indexOf(q) < 0) { return; }
          for (var j = i - 1, need = r.__depth - 1; j >= 0 && need >= 0; j--) {
            if (rows[j].__depth === need) { hit[j] = true; need--; }
          }
        });
      }
      var parentOpen = [true];                             // per depth: is the nearest row above at this depth open and shown?
      rows.forEach(function (r, i) {
        var d = r.__depth, visible = q ? hit[i] : (d === 0 || parentOpen[d - 1] === true);
        r.hidden = !visible;
        var open = q ? hasKept(i, hit) : r.__open;
        parentOpen[d] = visible && open;
        var b = r.querySelector('.tr-tog');
        if (b) {
          b.setAttribute('aria-expanded', open ? 'true' : 'false');
          b.setAttribute('aria-label', (open ? 'Collapse ' : 'Expand ') + b.getAttribute('aria-label').replace(/^(Collapse|Expand) /, ''));
          b.textContent = open ? '▾' : '▸';
        }
      });
      t.setAttribute('data-shown', String(rows.filter(function (r) { return !r.hidden; }).length));
    }
    function hasKept(i, hit) {                             // a row is shown open while a row below it (deeper) is kept
      var d = rows[i].__depth;
      return i + 1 < rows.length && rows[i + 1].__depth > d && hit[i + 1] === true;
    }
    body.addEventListener('click', function (e) {
      var b = e.target.closest && e.target.closest('.tr-tog');
      if (!b || q) { return; }
      var r = b.closest('tr');
      r.__open = !r.__open;
      paint();
      var again = r.querySelector('.tr-tog');
      if (again) { again.focus(); }
    });
    if (!t.hasAttribute('data-no-search')) {
      var wrap = t.closest('.tbl-wrap') || t.parentNode;
      var box = el('input', 'tbl-q tr-q');
      box.type = 'search'; box.placeholder = 'Filter rows'; box.setAttribute('aria-label', 'Filter rows');
      box.addEventListener('input', function () { q = box.value.trim().toLowerCase(); paint(); });
      wrap.parentNode.insertBefore(box, wrap);
    }
    paint();
  }

  // ---- a pivot by several fields, drawn by the Pivot tab's grid ---------------------------------------------------
  /** The panel's nested rows as the cube the grid draws: the cells hold the server's own text (formats, masks and all). */
  function cubeOf(d) {
    var cells = {}, leaves = [], cols = d.columns || [], levels = d.levels;
    (d.rows || []).forEach(function (r) {
      cells[E.cellKey(r.path, [])] = [r.total ? r.total.text : null];
      cols.forEach(function (c, i) { var x = r.cells[i] && r.cells[i].text; cells[E.cellKey(r.path, [c])] = [x === '' || x === undefined ? null : x]; });
      if (!r.group) { leaves.push(r.path); }
    });
    if (d.totals) {
      cols.forEach(function (c, i) { cells[E.cellKey([], [c])] = [d.totals[i] ? d.totals[i].text : null]; });
      cells[E.RS] = [d.totals[cols.length] ? d.totals[cols.length].text : null];
    }
    return { rows: levels, columns: d.across ? [d.across] : [''], values: [{ field: null, agg: d.agg, show: 'value', label: E.AGG_LABEL[d.agg] || d.agg }],
      rowKeys: leaves, columnKeys: cols.map(function (c) { return [c]; }), cells: cells };
  }

  function pivotTree(host) {
    if (!E || !G) { return; }
    var d = JSON.parse(host.getAttribute('data-pv-tree'));
    var cube = cubeOf(d), fields = [], st = { collapsedRows: {}, collapsedCols: {}, sort: null, totals: !!d.totals, heat: false };
    (d.rows || []).forEach(function (r) {                  // the groups shown closed at first: those at or below `expand` levels
      if (r.group && r.path.length >= d.expand) { st.collapsedRows[r.path.join(E.US)] = true; }
    });
    var out = null;
    function draw(focusKey) {
      out = G.render(host, cube, st, fields);
      host.querySelectorAll('td.pv-c').forEach(function (td) { td.removeAttribute('title'); td.tabIndex = -1; });
      var first = host.querySelector('td.pv-c');
      if (first) { first.tabIndex = 0; }
      if (focusKey != null) {
        var again = Array.prototype.filter.call(host.querySelectorAll('.pv-tog'), function (x) { return out.toggles[+x.getAttribute('data-t')].key === focusKey; })[0];
        if (again) { again.focus(); }
      }
    }
    host.addEventListener('click', function (e) {
      var b = e.target.closest('.pv-tog');
      if (b) {
        var key = out.toggles[+b.getAttribute('data-t')].key, map = st.collapsedRows;
        if (map[key]) { delete map[key]; } else { map[key] = true; }
        draw(key);
        return;
      }
      var th = e.target.closest('th.pv-sortable');
      if (th) { sortBy(th); }
    });
    host.addEventListener('keydown', function (e) {
      var th = e.target.closest && e.target.closest('th.pv-sortable');
      if (th && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); sortBy(th); }
    });
    function sortBy(th) {
      var s = out.lay.slots[+th.getAttribute('data-si')], c = s.path.join(E.US);
      if (st.sort && st.sort.c === c) { st.sort.dir = st.sort.dir === 1 ? -1 : st.sort.dir === -1 ? 0 : 1; } else { st.sort = { c: c, v: 0, dir: 1 }; }
      draw();
      var again = host.querySelector('th.pv-sortable[data-si="' + th.getAttribute('data-si') + '"]');
      if (again) { again.focus(); }
    }
    draw();
    host.setAttribute('data-tree-ready', '');
  }

  /** init(root, options): expandable rows under root (the document or a ShadowRoot), now and as panels are swapped in. */
  function init(root) {
    function scan(node) {
      if (!node.querySelectorAll) { return; }
      var tables = Array.prototype.slice.call(node.querySelectorAll('table.tbl-tree:not([data-tree-ready])'));
      if (node.matches && node.matches('table.tbl-tree:not([data-tree-ready])')) { tables.push(node); }
      tables.forEach(function (t) { t.setAttribute('data-tree-ready', ''); treeTable(t); });
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
