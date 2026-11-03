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
      it, any number of levels deep; the filter box and the column filters (colfilter.js) keep the rows that match and the
      ancestors of those rows: a parent shows, open, when any row below it matches.
   2. A pivot by a list of fields (div.pv-tree-host): the nested rows are drawn by the pivot grid (pivot-grid.js, the Pivot
      screen's: + and − on the groups, subtotals, the grand total) from the panel's own data, every figure the pivot engine's
      (ChartBinder computes them with drishti-bicompute) and shown as the server's text; one renderer for pivot rows.
   Both are plain buttons, so Tab, Enter and Space work; the state is aria-expanded. Live updates replace panels: new ones
   are enhanced when they appear (data-tree-ready marks the ones already done). */
(function () {
  'use strict';
  var me = document.currentScript, reg0 = window.drishtiModules || {};
  var ROOT = me && /\/js\/tree-rows\.js/.test(me.src || '') ? me.src.replace(/js\/tree-rows\.js.*$/, '') : '/static/';
  var VER = me && /[?&]v=([^&]+)/.exec(me.src || '') ? /[?&]v=([^&]+)/.exec(me.src)[1] : '';

  var treeState = {};                                      // a tree's filters by panel, so a live patch that replaces the table keeps them
  var openMem = {};                                        // the rows a reader opened, by table: a live patch repaints the panel, and what was opened stays open
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }

  // ---- table rows with children -----------------------------------------------------------------------------------
  function treeTable(t, root, scope) {
    var body = t.tBodies[0];
    if (!body) { return; }
    var rows = Array.prototype.slice.call(body.rows), q = '', CF = reg0.colFilter, ctl = null;
    var heads = t.tHead && t.tHead.rows.length ? Array.prototype.slice.call(t.tHead.rows[0].cells) : [];
    var count = null, clearBtn = null, lastLive = '';
    function cellText(r, i) { var c = r.cells[i]; return c ? c.textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').trim() : ''; }
    var panelOf = t.closest('[data-panel], section[id], .pnl'), pidOf = panelOf && (panelOf.getAttribute('data-panel') || panelOf.id) || 'tree';
    var memKey = scope + '|' + pidOf + '|' + (panelOf ? Array.prototype.indexOf.call(panelOf.querySelectorAll('table.tbl'), t) : 0);
    var mem = openMem[memKey];
    rows.forEach(function (r, i) {
      r.__depth = +r.getAttribute('data-depth');
      var b = r.querySelector('.tr-tog');
      r.__open = !!b && (mem && mem.length === rows.length ? mem[i] === true : b.getAttribute('aria-expanded') === 'true');
      r.__text = r.textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').toLowerCase();
    });
    function textHit(r) { return !q || r.__text.indexOf(q) >= 0; }
    function paint(say) {
      var hit = [], real = [], on = !!q || !!(ctl && ctl.active());
      if (ctl) { ctl.prepare(); }
      if (on) {                                            // a match, and every ancestor of a match (shown open)
        real = rows.map(function (r) { return textHit(r) && (!ctl || ctl.pass(r)); });
        hit = real.slice();
        rows.forEach(function (r, i) {
          if (!real[i]) { return; }
          for (var j = i - 1, need = r.__depth - 1; j >= 0 && need >= 0; j--) {
            if (rows[j].__depth === need) { hit[j] = true; need--; }
          }
        });
      }
      var parentOpen = [true];                             // per depth: is the nearest row above at this depth open and shown?
      rows.forEach(function (r, i) {
        var d = r.__depth, visible = on ? hit[i] : (d === 0 || parentOpen[d - 1] === true);
        r.hidden = !visible;
        var open = on ? hasKept(i, hit) : r.__open;
        parentOpen[d] = visible && open;
        var b = r.querySelector('.tr-tog');
        if (b) {
          b.setAttribute('aria-expanded', open ? 'true' : 'false');
          b.setAttribute('aria-label', (open ? 'Collapse ' : 'Expand ') + b.getAttribute('aria-label').replace(/^(Collapse|Expand) /, ''));
          b.textContent = open ? '▾' : '▸';
        }
      });
      t.setAttribute('data-shown', String(rows.filter(function (r) { return !r.hidden; }).length));
      var matched = real.filter(Boolean).length, text = on ? matched + ' of ' + rows.length + ' rows (filtered)' : rows.length + ' rows';
      if (count) { count.textContent = on ? text : ''; }
      if (clearBtn) { clearBtn.hidden = !on; }
      if (say === true && CF && text !== lastLive) { CF.say(root, text); }
      lastLive = text;
    }
    function hasKept(i, hit) {                             // a row is shown open while a row below it (deeper) is kept
      var d = rows[i].__depth;
      for (var j = i + 1; j < rows.length && rows[j].__depth > d; j++) { if (hit[j] === true) { return true; } }
      return false;
    }
    body.addEventListener('click', function (e) {
      var b = e.target.closest && e.target.closest('.tr-tog');
      if (!b || q || (ctl && ctl.active())) { return; }
      var r = b.closest('tr');
      r.__open = !r.__open;
      openMem[memKey] = rows.map(function (x) { return x.__open === true; });
      paint();
      var again = r.querySelector('.tr-tog');
      if (again) { again.focus(); }
    });
    if (!t.hasAttribute('data-no-search')) {
      var wrap = t.closest('.tbl-wrap') || t.parentNode;
      var box = el('input', 'tbl-q tr-q');
      box.type = 'search'; box.placeholder = 'Filter rows'; box.setAttribute('aria-label', 'Filter rows');
      box.addEventListener('input', function () { q = box.value.trim().toLowerCase(); paint(true); });
      var bar = el('div', 'tr-bar');
      count = el('span', 'tr-count mono'); count.setAttribute('aria-hidden', 'true');
      clearBtn = el('button', 'fk tbl-pg-b tbl-clear', '✕ Clear filters'); clearBtn.type = 'button'; clearBtn.hidden = true;
      clearBtn.title = 'Clear all filters on this table (Alt+Shift+X)';
      t.__tblClear = function () { q = ''; box.value = ''; if (ctl) { ctl.clear(true); } paint(true); };
      clearBtn.addEventListener('click', t.__tblClear);
      bar.appendChild(box); bar.appendChild(clearBtn); bar.appendChild(count);
      wrap.parentNode.insertBefore(bar, wrap);
      if (CF && heads.length) {
        var panel = t.closest('[data-panel], section[id], .pnl'), pid = panel && (panel.getAttribute('data-panel') || panel.id) || 'tree';
        var n = panel ? Array.prototype.indexOf.call(panel.querySelectorAll('table.tbl'), t) : 0;
        var kk = scope + '|' + pid + '|tree' + n;
        var st = treeState[kk] || (treeState[kk] = { f: {} });
        ctl = CF.attach({
          id: kk, key: pid + (n > 0 ? '~' + (n + 1) : ''), table: t, root: root, heads: heads, state: st, useUrl: root === document,
          rows: function () { return rows; }, text: cellText, base: textHit, sort: null,
          onChange: function () { paint(true); }
        });
      }
    }
    paint();
  }

  // ---- a pivot by several fields, drawn by the pivot grid -------------------------------------------------------------
  /** The grid's sheet (css/pivot-screen.css), once per document or shadow root that shows a nested pivot. */
  function sheet(host) {
    var at = host.getRootNode ? host.getRootNode() : document, into = at === document || !at.host ? document.head : at;
    if (into.querySelector('link[data-pivot-grid-css]')) { return; }
    var l = document.createElement('link');
    l.rel = 'stylesheet'; l.href = ROOT + 'css/pivot-screen.css' + (VER ? '?v=' + VER : ''); l.setAttribute('data-pivot-grid-css', '');
    into.appendChild(l);
  }
  var US = '\u001f';
  /**
   * The panel's nested rows as a pivot answer the grid draws: each group a node (open or closed), its rows under it while open, the
   * columns of `across` and the total; every cell keeps the server's text and tone (formats, masks), its figure for the heat.
   */
  function answerOf(d, closed) {
    var cols = (d.columns || []).map(function (c) { return { kind: 'node', key: 'c' + US + c, label: c }; });
    if (d.totals) { cols.push({ kind: 'grand', key: '*', label: 'Total' }); }
    var label = { sum: 'Sum', count: 'Count', avg: 'Average', min: 'Min', max: 'Max' }[d.agg] || d.agg || 'Value';
    var rows = [], cells = {}, hidden = null, fmt = {};
    fmt[label] = { style: 'scale' };
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
      rows.push({ kind: 'node', key: key, label: r.label, depth: path.length, expandable: !!r.group, expanded: !!r.group && !closed[key] });
      (r.cells || []).forEach(function (c, i) {
        if (!cols[i]) { return; }
        cells[key + '|' + cols[i].key] = cell((r['values'] || [])[i], c);
      });
      if (r.total) { cells[key + '|*'] = cell(null, r.total); }
    });
    if (d.totals) {
      rows.push({ kind: 'grand', key: '*', label: 'Total', depth: 0 });
      d.totals.forEach(function (c, i) {
        var k = i < (d.columns || []).length ? cols[i].key : '*';
        cells['*|' + k] = cell(null, c);
      });
    }
    return { rowDimensions: d.levels || [], columns: cols, rows: rows, cells: cells, measures: [{ id: d.agg || 'value', label: label, kind: 'mergeable' }],
      view: d.heat ? { formats: fmt } : {} };
  }

  function pivotTree(host) {
    var PG = reg0.pivotGrid || window.PivotCore || (window.drishtiModules || {}).pivotGrid;
    if (!PG) { return; }
    var d = JSON.parse(host.getAttribute('data-pv-tree')), closed = {};
    (d.rows || []).forEach(function (r) {                  // the groups shown closed at first: those at or below `expand` levels
      if (r.group && (r.path || []).length >= d.expand) { closed[(r.path || []).join(US)] = true; }
    });
    sheet(host);
    function draw(focusKey) {
      var a = answerOf(d, closed), box = host.querySelector('.pv-tree-grid');
      if (!box) { box = document.createElement('div'); box.className = 'pv-tree-grid pv-scroll'; host.textContent = ''; host.appendChild(box); }
      PG.render(box, a, {
        onToggle: function (row) {
          if (!row || !row.expandable) { return; }
          if (closed[row.key]) { delete closed[row.key]; } else { closed[row.key] = true; }
          draw(row.key);
        }
      });
      if (focusKey != null) {
        var again = Array.prototype.filter.call(box.querySelectorAll('[data-pv-toggle]'), function (x) {
          return a.rows[+x.getAttribute('data-pv-toggle')].key === focusKey;
        })[0];
        if (again) { again.focus(); }
      }
    }
    draw();
    host.setAttribute('data-tree-ready', '');
  }

  /** init(root, options): expandable rows under root (the document or a ShadowRoot), now and as panels are swapped in. */
  function init(root, options) {
    function scan(node) {
      if (!node.querySelectorAll) { return; }
      var tables = Array.prototype.slice.call(node.querySelectorAll('table.tbl-tree:not([data-tree-ready])'));
      if (node.matches && node.matches('table.tbl-tree:not([data-tree-ready])')) { tables.push(node); }
      tables.forEach(function (t) { t.setAttribute('data-tree-ready', ''); treeTable(t, root, (options && options.scope) || location.pathname); });
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
