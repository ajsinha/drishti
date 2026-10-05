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
/* The pivot's grid (USER_GUIDE.md, The Pivot tab): draws a CUBE (pivot-engine.js, or the server's search pivot) as a
   table with multi-level row and column headings, subtotals and grand totals, collapsible groups (▸/▾), rows sorted by
   any value column within their group, show-as percentages and optional heat shading. The same layout gives the export
   (flattened, numbers raw) and the chart (ECharts options). It draws; pivot.js owns the state and the events. */
(function () {
  'use strict';
  var E = window.drishtiPivotEngine;

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function join(p) { return p.join(E.US); }

  /** The groups of an axis as a tree, children in key order (or in the sort's order). */
  function tree(keys, depth) {
    var root = { path: [], kids: [], map: {} };
    keys.forEach(function (k) {
      var node = root;
      for (var l = 0; l < depth; l++) {
        var next = node.map[k[l]];
        if (!next) { next = node.map[k[l]] = { path: k.slice(0, l + 1), kids: [], map: {} }; node.kids.push(next); }
        node = next;
      }
    });
    return root;
  }

  /** Raw cube value of (row prefix, column prefix, value), then shown as the value asks (% of row, column, total). */
  function valueOf(cube, rp, cp, vi) {
    var c = cube.cells[E.cellKey(rp, cp)], raw = c ? c[vi] : null;
    var show = (cube.values[vi] || {}).show || 'value';
    if (show === 'value' || raw === null || raw === undefined) { return raw === undefined ? null : raw; }
    var d = show === 'pctRow' ? cube.cells[E.cellKey(rp, [])] : show === 'pctColumn' ? cube.cells[E.cellKey([], cp)] : cube.cells[E.RS];
    var den = d ? d[vi] : null;
    return typeof den === 'number' && den !== 0 && typeof raw === 'number' ? raw / den : null;
  }

  /**
   * The lines (rows of the grid) and slots (column groups of the grid, each one column per value):
   * leaf, collapsed (a group shown as its total), sub (a group's subtotal after its members) and grand.
   * @param st {collapsedRows, collapsedCols, sort: {c: joined column prefix, v, dir}, totals}
   */
  function layout(cube, st) {
    var R = cube.rows.length, C = cube.columns.length, totals = st.totals !== false;
    var cr = st.collapsedRows || {}, cc = st.collapsedCols || {}, sort = st.sort;
    function kidsOf(node) {
      if (!sort || !sort.dir) { return node.kids; }
      var cp = sort.c ? sort.c.split(E.US) : [];
      var keyed = node.kids.map(function (k, i) { return { k: k, v: valueOf(cube, k.path, cp, sort.v || 0), i: i }; });
      keyed.sort(function (a, b) {
        var an = typeof a.v === 'number', bn = typeof b.v === 'number';
        if (an && bn && a.v !== b.v) { return sort.dir * (a.v - b.v); }
        if (an !== bn) { return an ? -1 : 1; }
        return a.i - b.i;
      });
      return keyed.map(function (x) { return x.k; });
    }
    var lines = [];
    function walkRows(node) {
      kidsOf(node).forEach(function (k) {
        if (k.path.length === R) { lines.push({ type: 'leaf', path: k.path }); }
        else if (cr[join(k.path)]) { lines.push({ type: 'collapsed', path: k.path }); }
        else { walkRows(k); if (totals) { lines.push({ type: 'sub', path: k.path }); } }
      });
    }
    if (R) { walkRows(tree(cube.rowKeys, R)); }
    if (!R || totals) { lines.push({ type: 'grand', path: [] }); }
    var slots = [];
    function walkCols(node) {
      node.kids.forEach(function (k) {
        if (k.path.length === C) { slots.push({ type: 'leaf', path: k.path, top: C - 1 }); }
        else if (cc[join(k.path)]) { slots.push({ type: 'collapsed', path: k.path, top: k.path.length - 1 }); }
        else { walkCols(k); if (totals) { slots.push({ type: 'sub', path: k.path, top: k.path.length - 1 }); } }
      });
    }
    if (C) { walkCols(tree(cube.columnKeys, C)); }
    if (!C || totals) { slots.push({ type: 'grand', path: [], top: 0 }); }
    return { lines: lines, slots: slots, R: R, C: C };
  }

  function slotLabel(s) {
    return s.type === 'grand' ? 'Total' : s.type === 'sub' ? s.path.join(' / ') + ' total' : s.path.join(' / ');
  }
  function lineLabel(l) {
    return l.type === 'grand' ? 'Total' : l.type === 'sub' ? l.path.join(' / ') + ' total' : l.path.join(' / ');
  }

  /** Heat buckets per value over the body cells (not totals): 0-9, and whether the data has both signs. */
  function heatScale(cube, lay) {
    var out = cube.values.map(function () { return { lo: Infinity, hi: -Infinity }; });
    lay.lines.forEach(function (l) {
      if (l.type === 'sub' || l.type === 'grand') { return; }
      lay.slots.forEach(function (s) {
        if (lay.C && (s.type === 'sub' || s.type === 'grand')) { return; }
        cube.values.forEach(function (v, vi) {
          var x = valueOf(cube, l.path, s.path, vi);
          if (typeof x === 'number') { out[vi].lo = Math.min(out[vi].lo, x); out[vi].hi = Math.max(out[vi].hi, x); }
        });
      });
    });
    return out;
  }
  function heatClass(x, sc) {
    if (typeof x !== 'number' || !isFinite(sc.lo)) { return ''; }
    if (sc.lo < 0 && sc.hi > 0) {
      var m = Math.max(-sc.lo, sc.hi);
      return ' pv-heat-' + Math.min(9, Math.round(Math.abs(x) / m * 9)) + (x < 0 ? ' pv-hneg' : ' pv-hpos');
    }
    var span = sc.hi - sc.lo;
    return ' pv-heat-' + (span ? Math.round((x - sc.lo) / span * 9) : 5) + (sc.hi <= 0 ? ' pv-hneg' : '');
  }

  /**
   * Draws the grid into {@code host}. Returns {lay, toggles: [{axis, key}]} so pivot.js can act on the buttons:
   * button.pv-tog[data-t] (a toggle), th.pv-vh[data-si][data-vi] (sort), td.pv-c[data-li][data-si][data-vi] (drill).
   */
  function render(host, cube, st, fields) {
    var lay = layout(cube, st), R = lay.R, C = lay.C, nv = cube.values.length, toggles = [];
    var valueRow = nv > 1 || !C, headRows = C + (valueRow ? 1 : 0), rcols = Math.max(1, R);
    var fmts = cube.values.map(function (v) { return E.valueFormat(v, fields); });
    var heat = st.heat ? heatScale(cube, lay) : null;
    var t = el('table', 'tbl pv-grid' + (st.heat ? ' pv-heat' : ''));
    t.setAttribute('data-plain', '');
    t.setAttribute('role', 'grid');
    t.setAttribute('aria-label', 'Pivot: ' + cube.values.map(function (v) { return v.label; }).join(', ') +
      (R ? ' by ' + cube.rows.map(function (n) { return E.label(fields, n); }).join(', ') : '') +
      (C ? ' across ' + cube.columns.map(function (n) { return E.label(fields, n); }).join(', ') : ''));
    function toggle(axis, path, open) {
      var b = el('button', 'pv-tog', open ? '▾' : '▸');
      b.type = 'button';
      b.setAttribute('data-t', String(toggles.length));
      b.setAttribute('aria-expanded', open ? 'true' : 'false');
      b.setAttribute('aria-label', (open ? 'Collapse ' : 'Expand ') + path.join(' / '));
      toggles.push({ axis: axis, key: join(path) });
      return b;
    }
    var thead = el('thead');
    for (var h = 0; h < headRows; h++) {
      var tr = el('tr');
      if (h === 0 && headRows > 1) {
        var corner = el('th', 'pv-corner', C ? cube.columns.map(function (n) { return E.label(fields, n); }).join(' › ') : '');
        corner.colSpan = rcols; corner.rowSpan = headRows - 1; tr.appendChild(corner);
      }
      if (h === headRows - 1) {
        if (R) { cube.rows.forEach(function (n) { var th = el('th', 'pv-rf', E.label(fields, n)); th.scope = 'col'; tr.appendChild(th); }); }
        else { tr.appendChild(el('th', 'pv-rf', '')); }
      }
      if (h < C) {
        var last = null;
        lay.slots.forEach(function (s, si) {
          if (h > s.top) { return; }
          if (h < s.top && s.type !== 'grand') {
            var key = join(s.path.slice(0, h + 1));
            if (last && last.key === key) { last.th.colSpan += nv; return; }
            var g = el('th', 'pv-ch');
            g.colSpan = nv; g.scope = 'colgroup';
            if (h < C - 1) { g.appendChild(toggle('c', s.path.slice(0, h + 1), true)); }
            g.appendChild(document.createTextNode(s.path[h]));
            tr.appendChild(g);
            last = { key: key, th: g };
            return;
          }
          last = null;
          var th = el('th', 'pv-ch' + (s.type === 'sub' || s.type === 'grand' ? ' pv-tot' : ''));
          th.colSpan = nv; th.rowSpan = C - h; th.scope = 'col';
          if (s.type === 'collapsed') { th.appendChild(toggle('c', s.path, false)); }
          th.appendChild(document.createTextNode(s.type === 'grand' ? 'Total' : s.type === 'sub' ? 'Total ' + s.path[h] : s.path[h]));
          if (!valueRow) { sortable(th, si, 0); }
          tr.appendChild(th);
        });
      } else if (valueRow) {
        lay.slots.forEach(function (s, si) {
          cube.values.forEach(function (v, vi) {
            var th = el('th', 'pv-vh num' + (s.type === 'sub' || s.type === 'grand' ? ' pv-tot' : ''), C ? v.label : v.label);
            th.scope = 'col';
            sortable(th, si, vi);
            tr.appendChild(th);
          });
        });
      }
      thead.appendChild(tr);
    }
    function sortable(th, si, vi) {
      var s = lay.slots[si], on = st.sort && st.sort.dir && st.sort.c === join(s.path) && (st.sort.v || 0) === vi;
      th.classList.add('pv-sortable');
      th.tabIndex = 0;
      th.setAttribute('data-si', String(si));
      th.setAttribute('data-vi', String(vi));
      th.setAttribute('aria-sort', on ? (st.sort.dir > 0 ? 'ascending' : 'descending') : 'none');
      th.title = 'Sort the rows by this column (again to reverse, a third time for key order)';
    }
    t.appendChild(thead);
    var tbody = el('tbody'), prev = [];
    lay.lines.forEach(function (l, li) {
      var tr = el('tr', 'pv-' + l.type);
      if (!R) {
        var g = el('th', 'pv-rh', 'Total'); g.scope = 'row'; tr.appendChild(g);
      } else {
        for (var lv = 0; lv < R; lv++) {
          var th = el('th', 'pv-rh pv-l' + lv);
          th.scope = 'row';
          if (l.type === 'grand') { if (lv === 0) { th.textContent = 'Total'; } }
          else if (l.type === 'sub') { if (lv === l.path.length - 1) { th.textContent = 'Total ' + l.path[lv]; } }
          else if (lv < l.path.length) {
            var fresh = join(prev.slice(0, lv + 1)) !== join(l.path.slice(0, lv + 1)) || prev.length <= lv;
            if (fresh) {
              if (l.type === 'collapsed' && lv === l.path.length - 1) { th.appendChild(toggle('r', l.path, false)); }
              else if (lv < R - 1) { th.appendChild(toggle('r', l.path.slice(0, lv + 1), true)); }
              th.appendChild(document.createTextNode(l.path[lv]));
            }
          }
          tr.appendChild(th);
        }
        if (l.type === 'leaf' || l.type === 'collapsed') { prev = l.path; }
      }
      lay.slots.forEach(function (s, si) {
        cube.values.forEach(function (v, vi) {
          var x = valueOf(cube, l.path, s.path, vi), body = l.type !== 'sub' && l.type !== 'grand' && (!C || (s.type !== 'sub' && s.type !== 'grand'));
          var td = el('td', 'pv-c mono num' + (s.type === 'sub' || s.type === 'grand' ? ' pv-tot' : '') +
            (typeof x === 'number' && x < 0 && (v.show === 'value' || !v.show) ? ' t-neg' : '') +
            (heat && body ? heatClass(x, heat[vi]) : ''), E.format(x, fmts[vi]));
          td.tabIndex = -1;
          td.setAttribute('data-li', String(li));
          td.setAttribute('data-si', String(si));
          td.setAttribute('data-vi', String(vi));
          td.title = lineLabel(l) + (C ? ' · ' + slotLabel(s) : '') + ' · ' + v.label + ': Enter or click for the rows';
          tr.appendChild(td);
        });
      });
      tbody.appendChild(tr);
    });
    t.appendChild(tbody);
    var first = tbody.querySelector('td.pv-c');
    if (first) { first.tabIndex = 0; }
    host.textContent = '';
    var wrap = el('div', 'tbl-wrap pv-wrap');
    wrap.appendChild(t);
    host.appendChild(wrap);
    return { lay: lay, toggles: toggles, table: t };
  }

  /** The grid as a header and rows for CSV and Excel: every key spelled out, numbers raw (fractions for show-as). */
  function flatten(cube, st, fields) {
    var lay = layout(cube, st), R = lay.R, nv = cube.values.length;
    var header = R ? cube.rows.map(function (n) { return E.label(fields, n); }) : [''];
    lay.slots.forEach(function (s) {
      cube.values.forEach(function (v) {
        header.push(lay.C ? slotLabel(s) + (nv > 1 ? ' · ' + v.label : '') : v.label);
      });
    });
    var rows = lay.lines.map(function (l) {
      var out = [];
      for (var i = 0; i < Math.max(1, R); i++) {
        if (l.type === 'grand') { out.push(i === 0 ? 'Total' : ''); }
        else if (l.type === 'sub') { out.push(i < l.path.length - 1 ? l.path[i] : i === l.path.length - 1 ? l.path[i] + ' total' : ''); }
        else { out.push(i < l.path.length ? l.path[i] : ''); }
      }
      lay.slots.forEach(function (s) { cube.values.forEach(function (v, vi) { out.push(valueOf(cube, l.path, s.path, vi)); }); });
      return out;
    });
    return { header: header, rows: rows };
  }

  /** ECharts options for the result: bar or line (a series per column key, or per value), or a heatmap of one value. */
  function chart(cube, st, kind, t) {
    var lay = layout(cube, st), vi = Math.min(st.chartValue || 0, cube.values.length - 1);
    var lines = lay.lines.filter(function (l) { return l.type === 'leaf' || l.type === 'collapsed'; });
    if (!lines.length) { lines = lay.lines.filter(function (l) { return l.type === 'grand'; }); }
    var slots = lay.slots.filter(function (s) { return s.type === 'leaf' || s.type === 'collapsed'; });
    if (!slots.length) { slots = lay.slots.filter(function (s) { return s.type === 'grand'; }); }
    lines = lines.slice(0, 200); slots = slots.slice(0, 40);
    var cats = lines.map(lineLabel), fmt = E.valueFormat(cube.values[vi], []) === 'pct' ? 'pct' : 'compact';
    var axis = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
      splitLine: { lineStyle: { color: t.border, opacity: .5 } } };
    var tip = { backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } };
    var palette = [t.link, t.accent, t.ok, t.neg, t.warn, t.bad, t.muted, t.ink];
    var formatter = function (x) { return typeof x === 'number' ? E.format(x, fmt) : x; };
    if (kind === 'heatmap') {
      var data = [], lo = Infinity, hi = -Infinity;
      lines.forEach(function (l, y) {
        slots.forEach(function (s, x) {
          var v = valueOf(cube, l.path, s.path, vi);
          if (typeof v === 'number') { lo = Math.min(lo, v); hi = Math.max(hi, v); }
          data.push([x, y, typeof v === 'number' ? v : '-']);
        });
      });
      return { animation: false, color: palette, tooltip: Object.assign({ position: 'top', formatter: function (p) {
          return lineLabel(lines[p.value[1]]) + ' · ' + slotLabel(slots[p.value[0]]) + ': <b>' + E.format(p.value[2], fmt) + '</b>'; } }, tip),
        grid: { left: 110, right: 16, top: 12, bottom: 70 },
        xAxis: Object.assign({ type: 'category', data: slots.map(slotLabel) }, axis, { axisLabel: Object.assign({}, axis.axisLabel, { rotate: 30 }) }),
        yAxis: Object.assign({ type: 'category', data: cats }, axis),
        visualMap: { min: isFinite(lo) ? lo : 0, max: isFinite(hi) ? hi : 1, calculable: true, orient: 'horizontal', left: 'center', bottom: 0,
          textStyle: { color: t.muted, fontSize: 10 }, inRange: { color: lo < 0 && hi > 0 ? [t.neg, t.surface, t.pos] : [t.surface, t.accent] },
          formatter: formatter },
        series: [{ type: 'heatmap', data: data, label: { show: data.length <= 120, color: t.ink, fontSize: 9, formatter: function (p) { return E.format(p.value[2], fmt); } } }] };
    }
    var series;
    if (!lay.C && cube.values.length > 1) {
      series = cube.values.map(function (v, k) {
        return { name: v.label, type: kind === 'line' ? 'line' : 'bar', data: lines.map(function (l) { return valueOf(cube, l.path, [], k); }) };
      });
    } else {
      series = slots.map(function (s) {
        return { name: lay.C ? slotLabel(s) : cube.values[vi].label, type: kind === 'line' ? 'line' : 'bar',
          data: lines.map(function (l) { return valueOf(cube, l.path, s.path, vi); }) };
      });
    }
    return { animation: false, color: palette, tooltip: Object.assign({ trigger: 'axis', valueFormatter: formatter }, tip),
      legend: { type: 'scroll', top: 0, textStyle: { color: t.muted, fontSize: 10 } },
      grid: { left: 64, right: 16, top: 30, bottom: cats.length > 6 ? 80 : 30 },
      xAxis: Object.assign({ type: 'category', data: cats }, axis, { axisLabel: Object.assign({}, axis.axisLabel, { rotate: cats.length > 6 ? 30 : 0, interval: 0 }) }),
      yAxis: Object.assign({ type: 'value' }, axis, { axisLabel: Object.assign({}, axis.axisLabel, { formatter: formatter }) }),
      series: series };
  }

  window.drishtiPivotGrid = { layout: layout, render: render, flatten: flatten, chart: chart, valueOf: valueOf,
    lineLabel: lineLabel, slotLabel: slotLabel };
})();
