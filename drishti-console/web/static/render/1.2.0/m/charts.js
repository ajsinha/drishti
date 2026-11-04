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
/* The chart kinds through the vendored ECharts (CLIENT_RENDERER.md section 7.3): line and area (the options of the console's view.js),
   waterfall, histogram, scatter, candlestick, graph and the donut (charts.js), the surface (heatmap, or 3D with the vendored ECharts GL)
   and the map (the vendored Natural Earth shapes through the console's geomap.js). Colours are the theme's tokens, read from the
   rendered view, so a theme change redraws them. Every tooltip formatter escapes what it prints: ECharts writes tooltips as markup. */
import { compact, lineCompact } from './format.js';

const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const num = (v) => typeof v === 'number' && isFinite(v);

/** The theme's colours as the console names them (--d-ink, --d-link, ...), read from an element of the view. */
export function tokens(el) {
  const s = getComputedStyle(el), t = {};
  ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'ok', 'warn', 'bad', 'surface', 'bg-2'].forEach((k) => { t[k] = s.getPropertyValue('--d-' + k).trim(); });
  t.mono = s.getPropertyValue('--d-font-mono').trim();
  return t;
}
const tone = (t, n) => t[n] || t.link;
const palette = (t) => [t.link, t.accent, t.ok, t.neg, t.warn, t.bad, t.muted, t.ink];
function axis(t) {
  return { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
    splitLine: { lineStyle: { color: t.border, opacity: 0.5 } }, nameTextStyle: { color: t.muted, fontSize: 10 } };
}
const tip = (t, extra) => Object.assign({ backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } }, extra || {});

// ---- line and area -----------------------------------------------------------------------------------------------------------
function line(d0, t, kind) {
  const d = JSON.parse(JSON.stringify(d0 || {})), area = kind === 'area';
  d.x = d.x || []; d.series = d.series || [];
  const series = d.series.map((s) => {
    const values = (s.values || []).map((v) => (num(v) ? v : null));
    const o = { type: 'line', name: s.label, data: values, connectNulls: false, smooth: false, symbol: 'circle', symbolSize: 5,
      lineStyle: { width: 1.6, color: tone(t, s.tone) }, itemStyle: { color: tone(t, s.tone) } };
    if (area) { o.areaStyle = { color: tone(t, s.tone), opacity: 0.12 }; o.symbol = 'none'; }
    if (d.step) { o.step = 'end'; o.symbol = 'none'; }
    if (d.mark) {
      const i = d.x.indexOf(d.mark);
      if (i >= 0) { o.markPoint = { symbol: 'circle', symbolSize: 10, itemStyle: { color: t.accent }, label: { show: false }, data: [{ coord: [i, values[i]] }] }; }
    }
    return o;
  });
  if (d.limit != null && series.length) {
    series[0].markLine = { symbol: 'none', silent: true, lineStyle: { type: 'dashed', color: t.neg },
      label: { position: 'insideEndTop', formatter: (d.limitLabel || 'limit') + ' ' + lineCompact(d.limit), color: t.neg, fontSize: 10 }, data: [{ yAxis: d.limit }] };
  }
  const A = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 }, splitLine: { lineStyle: { color: t.border, opacity: 0.5 } } };
  const yAxis = { type: 'value', scale: !area, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, formatter: lineCompact }, axisLine: A.axisLine, splitLine: A.splitLine };
  if (num(d.limit)) {
    yAxis.max = (v) => { const m = Math.max(v.max, d.limit); return m + Math.abs(m - Math.min(v.min, d.limit)) * 0.05; };
    yAxis.min = (v) => { const m = Math.min(v.min, d.limit); return area && m >= 0 ? 0 : m - Math.abs(Math.max(v.max, d.limit) - m) * 0.05; };
  }
  return { animationDuration: 500, grid: { left: 44, right: 12, top: 12, bottom: 22 },
    tooltip: { trigger: 'axis', backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } },
    xAxis: Object.assign({ type: 'category', data: d.x, boundaryGap: false }, A), yAxis, series };
}

// ---- waterfall ------------------------------------------------------------------------------------------------------------------
const CHAR_W = 6.4, LINE_H = 12, GRID_LEFT = 52, GRID_RIGHT = 12;
function labelFit(steps, size) {
  const width = (size && size.width) || 800, height = (size && size.height) || 360;
  const band = Math.max(1, (width - GRID_LEFT - GRID_RIGHT) / Math.max(1, steps.length));
  const longest = steps.reduce((m, s) => Math.max(m, String(s.text == null ? '' : s.text).length), 0) * CHAR_W;
  if (band >= longest + 6) { return { mode: 'flat', top: 18 }; }
  if (band >= LINE_H + 2 && longest + 10 <= height * 0.4) { return { mode: 'up', top: Math.ceil(longest) + 12 }; }
  return { mode: 'none', top: 18 };
}
function waterfall(d, t, size) {
  const steps = d.steps || [], A = axis(t), many = steps.length > 6, range = {}, fit = labelFit(steps, size), ends = [];
  steps.forEach((s) => { if (s.total) { ends.push(s.value); } else { ends.push(s.from, s.to); } });
  const lo = Math.min(...(ends.length ? ends : [0])), hi = Math.max(...(ends.length ? ends : [0]));
  if (lo > 0 && hi - lo < 0.5 * hi) { range.min = Math.max(0, lo - (hi - lo) * 0.6); } else if (hi < 0 && hi - lo < 0.5 * -lo) { range.max = Math.min(0, hi + (hi - lo) * 0.6); }
  return {
    animationDuration: 400, grid: { left: GRID_LEFT, right: GRID_RIGHT, top: fit.top, bottom: many ? 58 : 26 },
    tooltip: tip(t, { formatter: (p) => { const s = p.data && p.data.step; return s ? esc(s.label) + ': <b>' + esc(s.text) + '</b>' + (s.total ? '' : '<br>running total ' + esc(compact(s.to))) : ''; } }),
    xAxis: { type: 'category', data: steps.map((s) => s.label), axisLine: A.axisLine, axisLabel: Object.assign({}, A.axisLabel, { interval: 0, rotate: many ? 30 : 0 }) },
    yAxis: Object.assign({ type: 'value', name: d.unit || '', axisLine: A.axisLine, splitLine: A.splitLine, nameTextStyle: A.nameTextStyle, axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }, range),
    series: [{ type: 'custom', clip: true, data: steps.map((s, i) => ({ value: [i, s.from, s.to], step: s })), encode: { x: 0, y: [1, 2] },
      renderItem: (params, api) => {
        const s = steps[params.dataIndex], a = api.coord([api.value(0), api.value(1)]), b = api.coord([api.value(0), api.value(2)]);
        const w = Math.min(46, api.size([1, 0])[0] * 0.6), top = Math.min(a[1], b[1]);
        const fill = s.tone ? tone(t, s.tone) : s.total ? t.muted : s.value < 0 ? t.bad : t.ok;
        const kids = [{ type: 'rect', shape: { x: a[0] - w / 2, y: top, width: w, height: Math.max(1, Math.abs(b[1] - a[1])) }, style: { fill } }];
        if (fit.mode === 'flat') { kids.push({ type: 'text', style: { text: s.text, x: a[0], y: top - 3, textAlign: 'center', textVerticalAlign: 'bottom', fill: t.muted, font: '10px ' + t.mono } }); }
        else if (fit.mode === 'up') { kids.push({ type: 'text', x: a[0], y: top - 3, rotation: -Math.PI / 2, style: { text: s.text, x: 0, y: 0, textAlign: 'left', textVerticalAlign: 'middle', fill: t.muted, font: '10px ' + t.mono } }); }
        return { type: 'group', children: kids };
      } }]
  };
}

function histogram(d, t) {
  const bins = d.bins || [], A = axis(t);
  const markers = (d.markers || []).filter((m) => num(m.value)).map((m, i) => {
    const c = tone(t, m.tone || 'accent');
    return { xAxis: m.value, lineStyle: { color: c, type: 'dashed', width: 1.5 }, label: { formatter: (m.label ? m.label + ' ' : '') + m.text, color: c, fontSize: 10, position: i % 2 ? 'insideEndBottom' : 'insideEndTop' } };
  });
  let lo = bins.length ? bins[0].from : 0, hi = bins.length ? bins[bins.length - 1].to : 1;
  (d.markers || []).forEach((m) => { if (num(m.value)) { lo = Math.min(lo, m.value); hi = Math.max(hi, m.value); } });
  const pad = (hi - lo) * 0.03 || 1;
  return {
    animationDuration: 400, grid: { left: 44, right: 16, top: 18, bottom: 26 },
    tooltip: tip(t, { formatter: (p) => (p.data && p.data.label ? esc(p.data.label) + ': <b>' + p.data.value[2] + '</b>' : '') }),
    xAxis: Object.assign({ type: 'value', min: lo - pad, max: hi + pad, name: d.unit || '', nameLocation: 'end' }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }), splitLine: { show: false } }),
    yAxis: Object.assign({ type: 'value', minInterval: 1 }, A),
    series: [{ type: 'custom', data: bins.map((b) => ({ value: [b.from, b.to, b.count], label: b.label })), encode: { x: [0, 1], y: 2 },
      renderItem: (params, api) => {
        const a = api.coord([api.value(0), api.value(2)]), b = api.coord([api.value(1), 0]);
        return { type: 'rect', shape: { x: a[0] + 0.5, y: a[1], width: Math.max(1, b[0] - a[0] - 1), height: b[1] - a[1] }, style: { fill: t.link, opacity: 0.8 } };
      }, markLine: { symbol: 'none', silent: true, data: markers } }]
  };
}

function scatter(d, t) {
  const pts = d.points || [], colours = palette(t), A = axis(t);
  let groups = [];
  pts.forEach((q) => { if (!groups.includes(q.group || '')) { groups.push(q.group || ''); } });
  if (!groups.length) { groups = ['']; }
  const maxSize = pts.reduce((m, q) => (num(q.size) ? Math.max(m, q.size) : m), 0);
  const size = (v) => (d.sized && num(v[2]) && maxSize ? 6 + 22 * Math.sqrt(v[2] / maxSize) : 9);
  return {
    animationDuration: 400, grid: { left: 56, right: 20, top: groups.length > 1 ? 30 : 16, bottom: 36 },
    legend: groups.length > 1 ? { top: 0, textStyle: { color: t.muted, fontSize: 10 }, itemHeight: 8 } : undefined,
    tooltip: tip(t, { formatter: (p) => { const q = p.data.point; return (q.label ? '<b>' + esc(q.label) + '</b>' + (q.group ? ' · ' + esc(q.group) : '') + '<br>' : '') + esc(d.xLabel) + ' ' + esc(q.xText) + '<br>' + esc(d.yLabel) + ' ' + esc(q.yText); } }),
    xAxis: Object.assign({ type: 'value', scale: true, name: d.xLabel, nameLocation: 'middle', nameGap: 22 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }),
    yAxis: Object.assign({ type: 'value', scale: true, name: d.yLabel, nameLocation: 'middle', nameGap: 42 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }),
    series: groups.map((g, i) => ({ type: 'scatter', name: g || (d.yLabel || ''), symbolSize: size, itemStyle: { color: colours[i % colours.length], opacity: 0.85 },
      emphasis: { focus: 'series' }, cursor: 'pointer', data: pts.filter((q) => (q.group || '') === g).map((q) => ({ value: [q.x, q.y, q.size], point: q })),
      label: { show: pts.length <= 24, position: 'right', color: t.muted, fontSize: 9, formatter: (p) => p.data.point.label || '' } }))
  };
}

function candlestick(d, t) {
  const cs = d.candles || [], A = axis(t), vol = !!d.volume, x = cs.map((c) => c.x);
  const grids = vol ? [{ left: 56, right: 14, top: 12, height: '62%' }, { left: 56, right: 14, top: '80%', bottom: 22 }] : [{ left: 56, right: 14, top: 12, bottom: 26 }];
  const xAxes = [Object.assign({ type: 'category', data: x, gridIndex: 0, boundaryGap: true }, A, { splitLine: { show: false }, axisLabel: Object.assign({}, A.axisLabel, { show: !vol }) })];
  const yAxes = [Object.assign({ type: 'value', scale: true, gridIndex: 0, name: d.unit || '' }, A)];
  const series = [{ type: 'candlestick', name: 'Price', xAxisIndex: 0, yAxisIndex: 0, data: cs.map((c) => [c.open, c.close, c.low, c.high]),
    itemStyle: { color: t.pos, color0: t.neg, borderColor: t.pos, borderColor0: t.neg } }];
  if (vol) {
    xAxes.push(Object.assign({ type: 'category', data: x, gridIndex: 1, boundaryGap: true }, A, { splitLine: { show: false } }));
    yAxes.push(Object.assign({ type: 'value', gridIndex: 1, splitNumber: 2 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: compact }) }));
    series.push({ type: 'bar', name: 'Volume', xAxisIndex: 1, yAxisIndex: 1, data: cs.map((c) => ({ value: c.volume, itemStyle: { color: c.close >= c.open ? t.pos : t.neg, opacity: 0.5 } })) });
  }
  return { animationDuration: 400, grid: grids, xAxis: xAxes, yAxis: yAxes, series, axisPointer: { link: [{ xAxisIndex: 'all' }] },
    dataZoom: cs.length > 60 ? [{ type: 'inside', xAxisIndex: vol ? [0, 1] : [0], start: 100 - Math.round(6000 / cs.length), end: 100 }] : [],
    tooltip: tip(t, { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: (ps) => {
      const c = cs[ps[0].dataIndex];
      return c ? esc(c.x) + '<br>O ' + c.open + '  H ' + c.high + '<br>L ' + c.low + '  C <b>' + c.close + '</b>' + (num(c.volume) ? '<br>Vol ' + compact(c.volume) : '') : '';
    } }) };
}

function layers(nodes, edges, w, h) {
  const depth = {}, incoming = {}, children = {};
  nodes.forEach((n) => { incoming[n.id] = 0; children[n.id] = []; });
  edges.forEach((e) => { if (children[e.from] && e.from !== e.to) { children[e.from].push(e.to); incoming[e.to]++; } });
  let queue = nodes.filter((n) => !incoming[n.id]).map((n) => n.id);
  if (!queue.length && nodes.length) { queue = [nodes[0].id]; }
  queue.forEach((id) => { depth[id] = 0; });
  for (let i = 0; i < queue.length; i++) { children[queue[i]].forEach((c) => { if (depth[c] == null) { depth[c] = depth[queue[i]] + 1; queue.push(c); } }); }
  nodes.forEach((n) => { if (depth[n.id] == null) { depth[n.id] = 0; } });
  const rows = {};
  nodes.forEach((n) => { (rows[depth[n.id]] = rows[depth[n.id]] || []).push(n.id); });
  const pos = {}, deepest = Math.max(1, Object.keys(rows).length - 1);
  Object.keys(rows).forEach((r) => { rows[r].forEach((id, i) => { pos[id] = [(i + 1) / (rows[r].length + 1) * w, Number(r) / deepest * h]; }); });
  return pos;
}
function graph(d, t, size) {
  const nodes = d.nodes || [], edges = d.edges || [], groups = [], colours = palette(t);
  nodes.forEach((n) => { const g = n.group || ''; if (!groups.includes(g)) { groups.push(g); } });
  const tree = d.layout !== 'force', top = groups.length > 1 ? 40 : 24;
  const pos = tree ? layers(nodes, edges, Math.max(200, size.width - 180), Math.max(120, size.height - top - 28)) : {};
  return { animationDuration: 400,
    legend: groups.length > 1 ? { top: 0, data: groups.filter(Boolean), textStyle: { color: t.muted, fontSize: 10 }, itemHeight: 8 } : undefined,
    tooltip: tip(t, { formatter: (p) => {
      if (p.dataType === 'edge') { return esc(p.data.source) + ' → ' + esc(p.data.target) + (p.data.label && p.data.label.formatter ? ' · ' + esc(p.data.label.formatter) : ''); }
      const n = p.data.node;
      return '<b>' + esc(n.label) + '</b>' + (n.group ? ' · ' + esc(n.group) : '') + (n.link ? '<br>' + esc(n.id) + ' (click to open)' : '');
    } }),
    series: [{ type: 'graph', layout: tree ? 'none' : 'force', roam: true, draggable: !tree, top, bottom: 28, left: 90, right: 90,
      force: { repulsion: 220, edgeLength: 90 }, categories: groups.map((g, i) => ({ name: g, itemStyle: { color: colours[i % colours.length] } })),
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7, lineStyle: { color: t.faint || t.border, width: 1.2, curveness: tree ? 0 : 0.1 },
      label: { show: true, position: 'bottom', color: t.ink, fontSize: 10, formatter: (p) => p.data.node.label },
      edgeLabel: { show: edges.length <= 30, color: t.muted, fontSize: 9, formatter: (p) => p.data.text || '' }, emphasis: { focus: 'adjacency' },
      data: nodes.map((n) => {
        const o = { id: n.id, name: n.id, category: groups.indexOf(n.group || ''), node: n, symbolSize: n.focus ? 26 : 16, symbol: n.link ? 'circle' : 'roundRect',
          cursor: n.link ? 'pointer' : 'default', itemStyle: { borderColor: n.focus ? t.accent : t.surface, borderWidth: n.focus ? 3 : 1 } };
        if (tree) { o.x = pos[n.id][0]; o.y = pos[n.id][1]; }
        return o;
      }),
      links: edges.map((e) => ({ source: e.from, target: e.to, text: e.label || '' })) }] };
}

function donut(d, t) {
  const bars = (d.bars || []).filter((b) => num(b.value)), colours = palette(t);
  return { animationDuration: 400,
    tooltip: tip(t, { trigger: 'item', formatter: (p) => esc(p.name) + ': <b>' + esc(p.data.text) + '</b> (' + p.percent + '%)' }),
    title: { text: d.total || '', left: 'center', top: 'middle', textStyle: { color: t.ink, fontSize: 13, fontWeight: 600 } },
    series: [{ type: 'pie', radius: ['52%', '78%'], avoidLabelOverlap: true, label: { show: false }, itemStyle: { borderColor: t.surface, borderWidth: 1 },
      data: bars.map((b, i) => ({ name: b.label, value: Math.abs(b.value), text: b.text, itemStyle: { color: colours[i % colours.length] } })) }] };
}

function surfaceOption(d, t, view) {
  const x = d.x || [], y = d.y || [], z = d.z || [], cells = [];
  z.forEach((row, j) => { (row || []).forEach((v, i) => { if (num(v)) { cells.push([i, j, v]); } }); });
  const lo = d.min != null ? d.min : 0, hi = d.max != null ? d.max : 1;
  const visual = { min: lo, max: hi, calculable: true, orient: 'horizontal', left: 'center', bottom: 0, itemHeight: 120, itemWidth: 10,
    textStyle: { color: t.muted, fontFamily: t.mono, fontSize: 10 }, inRange: { color: [t.link, t.surface, t.accent] } };
  if (view === '3d') {
    const axis3 = (name, labels) => ({ type: 'value', name, nameTextStyle: { color: t.muted }, min: 0, max: Math.max(labels.length - 1, 1), interval: 1,
      axisLabel: { color: t.muted, fontSize: 9, formatter: (v) => labels[Math.round(v)] || '' } });
    const pad = (hi - lo) * 0.15 || 1;
    return { visualMap: Object.assign(visual, { show: false }), xAxis3D: axis3('', x), yAxis3D: axis3('', y),
      zAxis3D: { type: 'value', name: d.unit || '', min: Math.floor((lo - pad) * 100) / 100, max: Math.ceil((hi + pad) * 100) / 100, nameTextStyle: { color: t.muted }, axisLabel: { color: t.muted, fontSize: 9 } },
      grid3D: { viewControl: { alpha: 28, beta: 38, distance: 190 }, axisLine: { lineStyle: { color: t.border } }, splitLine: { lineStyle: { color: t.border, opacity: 0.4 } } },
      series: [{ type: 'surface', wireframe: { show: true, lineStyle: { color: t.border, opacity: 0.6 } }, shading: 'color', data: cells }] };
  }
  return { tooltip: { position: 'top', formatter: (p) => esc(y[p.value[1]]) + ' · ' + esc(x[p.value[0]]) + ': ' + p.value[2] },
    grid: { left: 56, right: 12, top: 8, bottom: 70 },
    xAxis: { type: 'category', data: x, splitArea: { show: true }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 } },
    yAxis: { type: 'category', data: y, splitArea: { show: true }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 } },
    visualMap: visual,
    series: [{ type: 'heatmap', data: cells, label: { show: x.length * y.length <= 72, color: t.ink, fontFamily: t.mono, fontSize: 10, formatter: (p) => Math.round(p.value[2] * 100) / 100 } }] };
}

/** The option builders by kind (exported for tests and tools). */
export const BUILD = { line: (d, t) => line(d, t, 'line'), area: (d, t) => line(d, t, 'area'), waterfall, histogram, scatter, candlestick, graph, donut,
  surface: (d, t, s, view) => surfaceOption(d, t, view || 'heatmap') };
export { labelFit, esc };

// ---- maps: the console's geomap.js (vendored shapes, loaded from this console) ------------------------------------------------
const TOKEN = /\{(iso3|iso2|code)\}/;
const upper = (v) => String(v == null ? '' : v).trim().toUpperCase();

/** Draws a map panel; the shapes load on first use. onLink(link) opens a clicked place (in place). */
export function drawMap(E, G, el, d, t, compactFn, onLink, chartsOut) {
  const isRegion = d.mode === 'region', tick = el.__tick = (el.__tick || 0) + 1;
  const note = (txt) => { const n = el.parentNode && el.parentNode.querySelector('.xmap-note'); if (n) { n.textContent = txt || ''; n.hidden = !txt; } };
  const list = (places, linkFor) => {
    const ul = el.parentNode && el.parentNode.querySelector('.xmap-list');
    if (!ul) { return; }
    while (ul.firstChild) { ul.removeChild(ul.firstChild); }
    places.forEach((p) => { const li = document.createElement('li'); li.appendChild(linkFor(p)); if (p.text) { const s = document.createElement('span'); s.className = 'mono'; s.textContent = p.text; li.appendChild(s); } ul.appendChild(li); });
    const det = ul.closest('details'); if (det) { det.hidden = !places.length; }
  };
  return G.world().then((w) => {
    const c = isRegion ? G.find(w, d.region) : null;
    if (isRegion && !c) { throw new Error('unknown country'); }
    return (isRegion ? G.region(c.iso3) : Promise.resolve(w)).then((geo) => ({ w, geo }));
  }).then(({ w, geo }) => {
    if (tick !== el.__tick) { return; }
    const link = d.link, shade = d.shade, notes = [];
    const codeFor = (raw) => { if (isRegion) { return upper(raw); } const f = G.find(w, raw); return f ? f.iso3 : null; };
    const items = ((shade && shade.items) || []).map((i) => { const k = codeFor(i.code); return k ? { code: k, value: i.value, text: i.text } : null; }).filter(Boolean);
    const highlight = d.highlight == null ? [] : [codeFor(d.highlight)].filter(Boolean);
    if (d.highlight != null && !highlight.length) { notes.push('Not on the map: ' + d.highlight); }
    if (shade && shade.note) { notes.push('Not shaded: ' + shade.note); }
    if (items.length) { notes.push('Shaded by ' + ((shade && shade.label) || 'value') + (d.unit ? ' (' + d.unit + ')' : '')); }
    let clickable = null, make = null;
    if (link) {
      const tk = TOKEN.exec(link.template);
      clickable = (link.codes || []).map((x) => (tk && tk[1] === 'iso2' ? codeFor(x) : upper(x))).filter(Boolean);
      make = (place) => {
        const rec = isRegion ? { code: place, iso2: null, iso3: geo.iso3 } : G.find(w, place);
        const v = tk[1] === 'code' ? place : tk[1] === 'iso2' ? rec && rec.iso2 : rec && rec.iso3;
        return v ? { kind: link.kind, id: link.template.replace(TOKEN, v) } : null;
      };
      if (link.note) { notes.push('Nothing to open: ' + link.note); }
    }
    const chart = E.getInstanceByDom(el) || E.init(el, null, { renderer: 'svg' });
    chart.setOption(G.option({ level: isRegion ? 'region' : 'world', geo, t, items, highlight, clickable, linked: !!(link && !link.note),
      legend: shade && shade.label, unit: d.unit || (shade && shade.unit) || '', numText: compactFn }), true);
    if (!chartsOut.includes(chart)) { chartsOut.push(chart); }
    el.__places = { make, clickable };
    if (!el.__clicks) {
      el.__clicks = true;
      chart.on('click', (p) => { const code = G.codeOf(p), pl = el.__places; if (code && pl && pl.make && (!pl.clickable || pl.clickable.includes(code))) { const l = pl.make(code); if (l) { onLink(l); } } });
    }
    const places = [];
    if (make) {
      const names = (code) => { if (isRegion) { const r = geo.by.code[code]; return r ? r.name : code; } const f = w.by.iso3[code]; return f ? f.name : code; };
      const byCode = {};
      items.forEach((i) => { byCode[i.code] = i; });
      clickable.forEach((code) => { const l = make(code); if (l) { places.push({ name: names(code), link: l, text: byCode[code] ? byCode[code].text : '' }); } });
      places.sort((a, b) => a.name.localeCompare(b.name));
    }
    return { places, note: notes.join(' · '), list, setNote: note };
  });
}
