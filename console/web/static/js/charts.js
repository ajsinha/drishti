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
/* The chart panel kinds the server computes and ECharts draws: waterfall, histogram, scatter, candlestick and graph.
   Each panel carries its view model in data-xchart (panels.html); colours come from the theme's tokens, so a theme
   change redraws them. A chart whose data cannot be drawn says "No data available" and leaves the others alone; the
   "Data" table under each chart holds the same numbers for keyboards, screen readers and print. A point or node
   that names an entity opens it on click (or Enter), through a link, so a workspace pane routes it like any other. */
(function () {
  'use strict';
  var charts = [];

  function tokens() {
    var s = getComputedStyle(document.documentElement), t = {};
    ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'ok', 'warn', 'bad', 'surface', 'bg-2'].forEach(function (k) {
      t[k] = s.getPropertyValue('--d-' + k).trim();
    });
    t.mono = s.getPropertyValue('--d-font-mono').trim();
    return t;
  }
  function compact(v) {
    if (typeof v !== 'number' || !isFinite(v)) { return ''; }
    var a = Math.abs(v), sign = v < 0 ? '−' : '';
    // two decimals at most, trailing zeros dropped, so neighbouring axis ticks (1.75m, 1.8m) stay distinct
    var trim = function (x, dp) { return String(Number(x.toFixed(dp))); };
    if (a >= 1e9) { return sign + trim(a / 1e9, 2) + 'bn'; }
    if (a >= 1e6) { return sign + trim(a / 1e6, a >= 1e8 ? 0 : 2) + 'm'; }
    if (a >= 1e4) { return sign + trim(a / 1e3, a >= 1e6 ? 0 : 1) + 'k'; }
    return sign + (Math.round(a * 100) / 100);
  }
  function num(v) { return typeof v === 'number' && isFinite(v); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function tone(t, name) { return t[name] || t.link; }
  /** A palette for groups: the theme's tones first, then their tints. */
  function palette(t) { return [t.link, t.accent, t.ok, t.neg, t.warn, t.bad, t.muted, t.ink]; }
  function axis(t) {
    return { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
      splitLine: { lineStyle: { color: t.border, opacity: .5 } }, nameTextStyle: { color: t.muted, fontSize: 10 } };
  }
  function tooltip(t, extra) {
    return Object.assign({ backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } }, extra || {});
  }
  function open(el, link) {
    if (!link || !link.kind || !link.id) { return; }
    var a = document.createElement('a');
    a.href = '/v/' + encodeURIComponent(link.kind) + '/' + encodeURIComponent(link.id);
    a.hidden = true;
    el.appendChild(a);
    a.click();                                   // bubbles: a workspace pane turns it into a selection (view.js)
    a.remove();
  }

  // ---- waterfall: each step a bar from the running total before it to the one after; totals stand on zero --------------
  function waterfall(d, t) {
    var steps = d.steps || [], labels = steps.map(function (s) { return s.label; }), A = axis(t);
    var data = steps.map(function (s, i) { return { value: [i, s.from, s.to], step: s }; });
    var many = steps.length > 6, range = {};
    // big totals beside small steps (yesterday's MTM, today's moves): start the axis near the steps, not at zero, and
    // let the totals run off the bottom (their labels carry the full figure)
    var ends = [];
    steps.forEach(function (s) { if (s.total) { ends.push(s.value); } else { ends.push(s.from, s.to); } });
    var lo = Math.min.apply(null, ends.length ? ends : [0]), hi = Math.max.apply(null, ends.length ? ends : [0]);
    if (lo > 0 && hi - lo < 0.5 * hi) { range.min = Math.max(0, lo - (hi - lo) * 0.6); }
    else if (hi < 0 && hi - lo < 0.5 * -lo) { range.max = Math.min(0, hi + (hi - lo) * 0.6); }
    return {
      animationDuration: 400, grid: { left: 52, right: 12, top: 18, bottom: many ? 58 : 26 },
      tooltip: tooltip(t, { formatter: function (p) {
        var s = p.data && p.data.step;
        return s ? esc(s.label) + ': <b>' + esc(s.text) + '</b>' + (s.total ? '' : '<br>running total ' + esc(compact(s.to))) : '';
      } }),
      xAxis: { type: 'category', data: labels, axisLine: A.axisLine,
        axisLabel: Object.assign({}, A.axisLabel, { interval: 0, rotate: many ? 30 : 0 }) },
      yAxis: Object.assign({ type: 'value', name: d.unit || '', axisLine: A.axisLine, splitLine: A.splitLine, nameTextStyle: A.nameTextStyle,
        axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }, range),
      series: [{ type: 'custom', clip: true, data: data, encode: { x: 0, y: [1, 2] },
        renderItem: function (params, api) {
          var s = steps[params.dataIndex], a = api.coord([api.value(0), api.value(1)]), b = api.coord([api.value(0), api.value(2)]);
          var w = Math.min(46, api.size([1, 0])[0] * 0.6), top = Math.min(a[1], b[1]);
          var fill = s.total ? t.link : s.value < 0 ? t.neg : t.pos;
          var kids = [{ type: 'rect', shape: { x: a[0] - w / 2, y: top, width: w, height: Math.max(1, Math.abs(b[1] - a[1])) },
            style: { fill: fill, opacity: s.total ? 1 : .85 } }];
          if (steps.length <= 12) {
            kids.push({ type: 'text', style: { text: s.text, x: a[0], y: top - 3, textAlign: 'center', textVerticalAlign: 'bottom',
              fill: t.muted, font: '10px ' + t.mono } });
          }
          return { type: 'group', children: kids };
        } }]
    };
  }

  // ---- histogram: bins as touching bars on a value axis, markers as labelled vertical lines ---------------------------
  function histogram(d, t) {
    var bins = d.bins || [], A = axis(t);
    var data = bins.map(function (b) { return { value: [b.from, b.to, b.count], label: b.label }; });
    var markers = (d.markers || []).filter(function (m) { return num(m.value); }).map(function (m, i) {
      var c = tone(t, m.tone || 'accent');
      return { xAxis: m.value, lineStyle: { color: c, type: 'dashed', width: 1.5 },
        label: { formatter: (m.label ? m.label + ' ' : '') + m.text, color: c, fontSize: 10, position: i % 2 ? 'insideEndBottom' : 'insideEndTop' } };
    });
    var lo = bins.length ? bins[0].from : 0, hi = bins.length ? bins[bins.length - 1].to : 1;
    (d.markers || []).forEach(function (m) { if (num(m.value)) { lo = Math.min(lo, m.value); hi = Math.max(hi, m.value); } });
    var pad = (hi - lo) * 0.03 || 1;
    return {
      animationDuration: 400, grid: { left: 44, right: 16, top: 18, bottom: 26 },
      tooltip: tooltip(t, { formatter: function (p) { return p.data && p.data.label ? esc(p.data.label) + ': <b>' + p.data.value[2] + '</b>' : ''; } }),
      xAxis: Object.assign({ type: 'value', min: lo - pad, max: hi + pad, name: d.unit || '', nameLocation: 'end' }, A,
        { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }), splitLine: { show: false } }),
      yAxis: Object.assign({ type: 'value', minInterval: 1 }, A),
      series: [{ type: 'custom', data: data, encode: { x: [0, 1], y: 2 },
        renderItem: function (params, api) {
          var a = api.coord([api.value(0), api.value(2)]), b = api.coord([api.value(1), 0]);
          return { type: 'rect', shape: { x: a[0] + 0.5, y: a[1], width: Math.max(1, b[0] - a[0] - 1), height: b[1] - a[1] },
            style: { fill: t.link, opacity: .8 } };
        },
        markLine: { symbol: 'none', silent: true, data: markers } }]
    };
  }

  // ---- scatter: one series per group (a legend when there are several); size scales the symbol -----------------------
  function scatter(d, t) {
    var pts = d.points || [], groups = [], colours = palette(t), A = axis(t);
    pts.forEach(function (q) { if (groups.indexOf(q.group || '') < 0) { groups.push(q.group || ''); } });
    if (!groups.length) { groups = ['']; }
    var maxSize = 0;
    pts.forEach(function (q) { if (num(q.size)) { maxSize = Math.max(maxSize, q.size); } });
    var size = function (v) { return d.sized && num(v[2]) && maxSize ? 6 + 22 * Math.sqrt(v[2] / maxSize) : 9; };
    var series = groups.map(function (g, i) {
      return { type: 'scatter', name: g || (d.yLabel || ''), symbolSize: size, itemStyle: { color: colours[i % colours.length], opacity: .85 },
        emphasis: { focus: 'series' }, cursor: 'pointer',
        data: pts.filter(function (q) { return (q.group || '') === g; }).map(function (q) {
          return { value: [q.x, q.y, q.size], point: q };
        }),
        label: { show: pts.length <= 24, position: 'right', color: t.muted, fontSize: 9, formatter: function (p) { return p.data.point.label || ''; } } };
    });
    return {
      animationDuration: 400, grid: { left: 56, right: 20, top: groups.length > 1 ? 30 : 16, bottom: 36 },
      legend: groups.length > 1 ? { top: 0, textStyle: { color: t.muted, fontSize: 10 }, itemHeight: 8 } : undefined,
      tooltip: tooltip(t, { formatter: function (p) {
        var q = p.data.point;
        return (q.label ? '<b>' + esc(q.label) + '</b>' + (q.group ? ' · ' + esc(q.group) : '') + '<br>' : '') +
          esc(d.xLabel) + ' ' + esc(q.xText) + '<br>' + esc(d.yLabel) + ' ' + esc(q.yText);
      } }),
      xAxis: Object.assign({ type: 'value', scale: true, name: d.xLabel, nameLocation: 'middle', nameGap: 22 }, A,
        { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }),
      yAxis: Object.assign({ type: 'value', scale: true, name: d.yLabel, nameLocation: 'middle', nameGap: 42 }, A,
        { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }),
      series: series
    };
  }

  // ---- candlestick: prices above, volume below on its own axis when there is any --------------------------------------
  function candlestick(d, t) {
    var cs = d.candles || [], A = axis(t), vol = !!d.volume;
    var grids = vol ? [{ left: 56, right: 14, top: 12, height: '62%' }, { left: 56, right: 14, top: '80%', bottom: 22 }]
      : [{ left: 56, right: 14, top: 12, bottom: 26 }];
    var x = cs.map(function (c) { return c.x; });
    var xAxes = [Object.assign({ type: 'category', data: x, gridIndex: 0, boundaryGap: true }, A, { splitLine: { show: false },
      axisLabel: Object.assign({}, A.axisLabel, { show: !vol }) })];
    var yAxes = [Object.assign({ type: 'value', scale: true, gridIndex: 0, name: d.unit || '' }, A)];
    var series = [{ type: 'candlestick', name: 'Price', xAxisIndex: 0, yAxisIndex: 0,
      data: cs.map(function (c) { return [c.open, c.close, c.low, c.high]; }),
      itemStyle: { color: t.pos, color0: t.neg, borderColor: t.pos, borderColor0: t.neg } }];
    if (vol) {
      xAxes.push(Object.assign({ type: 'category', data: x, gridIndex: 1, boundaryGap: true }, A, { splitLine: { show: false } }));
      yAxes.push(Object.assign({ type: 'value', gridIndex: 1, splitNumber: 2 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }));
      series.push({ type: 'bar', name: 'Volume', xAxisIndex: 1, yAxisIndex: 1,
        data: cs.map(function (c) { return { value: c.volume, itemStyle: { color: c.close >= c.open ? t.pos : t.neg, opacity: .5 } }; }) });
    }
    return {
      animationDuration: 400, grid: grids, xAxis: xAxes, yAxis: yAxes, series: series,
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      dataZoom: cs.length > 60 ? [{ type: 'inside', xAxisIndex: vol ? [0, 1] : [0], start: 100 - Math.round(6000 / cs.length), end: 100 }] : [],
      tooltip: tooltip(t, { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: function (ps) {
        var c = cs[ps[0].dataIndex];
        if (!c) { return ''; }
        return esc(c.x) + '<br>O ' + c.open + '  H ' + c.high + '<br>L ' + c.low + '  C <b>' + c.close + '</b>' + (num(c.volume) ? '<br>Vol ' + compact(c.volume) : '');
      } })
    };
  }

  // ---- graph: a layered tree from the roots (nodes nothing points to), or a force layout -----------------------------
  function layers(nodes, edges, w, h) {
    var depth = {}, incoming = {}, children = {};
    nodes.forEach(function (n) { incoming[n.id] = 0; children[n.id] = []; });
    edges.forEach(function (e) { if (children[e.from] && e.from !== e.to) { children[e.from].push(e.to); incoming[e.to]++; } });
    var queue = nodes.filter(function (n) { return !incoming[n.id]; }).map(function (n) { return n.id; });
    if (!queue.length && nodes.length) { queue = [nodes[0].id]; }
    queue.forEach(function (id) { depth[id] = 0; });
    for (var i = 0; i < queue.length; i++) {
      children[queue[i]].forEach(function (c) { if (depth[c] == null) { depth[c] = depth[queue[i]] + 1; queue.push(c); } });
    }
    nodes.forEach(function (n) { if (depth[n.id] == null) { depth[n.id] = 0; } });
    var rows = {};
    nodes.forEach(function (n) { (rows[depth[n.id]] = rows[depth[n.id]] || []).push(n.id); });
    // positions in the panel's own pixels: a box of another shape would be stretched to fit, and the nodes with it
    var pos = {}, deepest = Math.max(1, Object.keys(rows).length - 1);
    Object.keys(rows).forEach(function (r) {
      rows[r].forEach(function (id, i) { pos[id] = [(i + 1) / (rows[r].length + 1) * w, Number(r) / deepest * h]; });
    });
    return pos;
  }
  function graph(d, t, size) {
    var nodes = d.nodes || [], edges = d.edges || [], groups = [], colours = palette(t);
    nodes.forEach(function (n) { var g = n.group || ''; if (groups.indexOf(g) < 0) { groups.push(g); } });
    var tree = d.layout !== 'force', top = groups.length > 1 ? 40 : 24;
    var pos = tree ? layers(nodes, edges, Math.max(200, size.width - 180), Math.max(120, size.height - top - 28)) : {};
    return {
      animationDuration: 400,
      legend: groups.length > 1 ? { top: 0, data: groups.filter(Boolean), textStyle: { color: t.muted, fontSize: 10 }, itemHeight: 8 } : undefined,
      tooltip: tooltip(t, { formatter: function (p) {
        if (p.dataType === 'edge') { return esc(p.data.source) + ' → ' + esc(p.data.target) + (p.data.label && p.data.label.formatter ? ' · ' + esc(p.data.label.formatter) : ''); }
        var n = p.data.node;
        return '<b>' + esc(n.label) + '</b>' + (n.group ? ' · ' + esc(n.group) : '') + (n.link ? '<br>' + esc(n.id) + ' (click to open)' : '');
      } }),
      series: [{ type: 'graph', layout: tree ? 'none' : 'force', roam: true, draggable: !tree, top: top, bottom: 28, left: 90, right: 90,
        force: { repulsion: 220, edgeLength: 90 }, categories: groups.map(function (g, i) { return { name: g, itemStyle: { color: colours[i % colours.length] } }; }),
        edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7, lineStyle: { color: t.faint || t.border, width: 1.2, curveness: tree ? 0 : .1 },
        label: { show: true, position: 'bottom', color: t.ink, fontSize: 10, formatter: function (p) { return p.data.node.label; } },
        edgeLabel: { show: edges.length <= 30, color: t.muted, fontSize: 9, formatter: function (p) { return p.data.text || ''; } },
        emphasis: { focus: 'adjacency' },
        data: nodes.map(function (n) {
          var o = { id: n.id, name: n.id, category: groups.indexOf(n.group || ''), node: n, symbolSize: n.focus ? 26 : 16,
            symbol: n.link ? 'circle' : 'roundRect', cursor: n.link ? 'pointer' : 'default',
            itemStyle: { borderColor: n.focus ? t.accent : t.surface, borderWidth: n.focus ? 3 : 1 } };
          if (tree) { o.x = pos[n.id][0]; o.y = pos[n.id][1]; }
          return o;
        }),
        links: edges.map(function (e) { return { source: e.from, target: e.to, text: e.label || '' }; }) }]
    };
  }

  var BUILD = { waterfall: waterfall, histogram: histogram, scatter: scatter, candlestick: candlestick, graph: graph };

  function draw(root) {
    if (!window.echarts) { return; }
    var t = tokens();
    (root || document).querySelectorAll('.xchart[data-xchart]').forEach(function (el) {
      var kind = el.getAttribute('data-kind'), d;
      try {
        d = JSON.parse(el.getAttribute('data-xchart')) || {};
        var c = window.echarts.getInstanceByDom(el) || window.echarts.init(el, null, { renderer: 'svg' });
        c.setOption(BUILD[kind](d, t, { width: el.clientWidth || 800, height: el.clientHeight || 360 }), true);
        if (charts.indexOf(c) < 0) { charts.push(c); }
        if (!el.__clicks) {
          el.__clicks = true;
          c.on('click', function (p) {
            var link = p.data && ((p.data.point && p.data.point.link) || (p.data.node && p.data.node.link));
            open(el, link);
          });
        }
      } catch (e) {
        // one chart whose data cannot be drawn must not stop the others or the page
        var broken = window.echarts.getInstanceByDom(el);
        if (broken) { broken.dispose(); }
        el.removeAttribute('data-xchart');
        el.className = 'pnl-empty';
        el.textContent = 'No data available';
      }
    });
  }
  draw(document);
  document.addEventListener('drishti:theme', function () { draw(document); });
  window.addEventListener('resize', function () {
    charts = charts.filter(function (c) { return !c.isDisposed(); });
    charts.forEach(function (c) { c.resize(); });
  });
  window.drishtiCharts = { draw: draw, options: BUILD };   // options: the builders, for tests and tools
})();
