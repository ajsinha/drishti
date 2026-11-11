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
/* The ViewModel's panel charts (CLIENT_RENDERER.md section 7.3), moved here from the client renderer (1.2.0's m/charts.js): the
   options of the console's drishti_view.js (line, area) and drishti_charts.js (waterfall, histogram, scatter, candlestick, graph,
   donut, the surface as a heatmap or in 3D), built from a panel's data and the console's colour tokens (--d-*). The series are the
   console's, point for point, so the renderer and the console's pages draw the same chart (the parity suite compares them); axis
   ticks are drishti-core's formatCompact. Tooltips are markup written by the engine: every formatter escapes what it prints. The
   drawing (the engine, sizes, clicks, the map) is drishti_chart-panel-draw.js. */
import { formatCompact } from '../../drishti-core/src/drishti_index.js';

/** The panel kinds drawn with the engine (HOW_IT_FITS.md section V4). */
export const PANEL_KINDS = Object.freeze(['line', 'area', 'waterfall', 'histogram', 'scatter', 'candlestick', 'graph', 'donut', 'surface', 'map']);

const ENT = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' };
/**
 * Text made safe for a tooltip (the engine writes tooltips as markup).
 * @param {*} s
 * @returns {string}
 */
export function escapeTip(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ENT[c]); }
const esc = escapeTip;
const num = (v) => typeof v === 'number' && isFinite(v);

const UNITS = [['bn', 1e9], ['m', 1e6], ['k', 1e3, 1e4]];
/**
 * An axis tick as the console's charts write it: 1.75m, 250k, −1.2bn (at most two decimals for bn, none past 100m, one for k, two
 * below 10,000; never grouped, as the console never groups a tick; trailing zeros dropped).
 * @param {number} v
 * @returns {string}
 */
export function panelCompact(v) {
  if (!num(v)) { return ''; }
  return formatCompact(v, { units: UNITS, trim: true, smallDecimals: 2, smallGrouping: false, empty: '',
    decimals: (a, unit) => (unit === 'bn' ? 2 : unit === 'm' ? (a >= 1e8 ? 0 : 2) : unit === 'k' ? 1 : 2) }).replace(/,/g, '');
}

/**
 * The line and area charts' own ticks (the console's view.js): fixed decimals (2.0bn, 1.5m, 250k, 12.5k), a plain minus, and at most
 * two decimals below 10,000.
 * @param {number} v
 * @returns {string}
 */
export function panelLineCompact(v) {
  if (!num(v)) { return ''; }
  return formatCompact(v, { units: UNITS, trim: 'small', smallDecimals: 2, smallGrouping: false, minus: '-', empty: '',
    decimals: (a, unit) => (unit === 'bn' ? 1 : unit === 'm' ? (a >= 1e7 ? 0 : 1) : unit === 'k' ? (a >= 1e5 ? 0 : 1) : 2) }).replace(/,/g, '');
}

const TOKEN_NAMES = ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'ok', 'warn', 'bad', 'surface', 'bg-2'];
/**
 * The theme's colours as the console names them (--d-ink, --d-link, ...) and its mono font, read from an element of the view.
 * @param {Element} el
 * @returns {Object<string, string>} ink, muted, faint, border, link, accent, neg, pos, ok, warn, bad, surface, bg-2, mono
 */
export function panelTokens(el) {
  const s = el.ownerDocument.defaultView.getComputedStyle(el), t = {};
  TOKEN_NAMES.forEach((k) => { t[k] = s.getPropertyValue('--d-' + k).trim(); });
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
      label: { position: 'insideEndTop', formatter: (d.limitLabel || 'limit') + ' ' + panelLineCompact(d.limit), color: t.neg, fontSize: 10 }, data: [{ yAxis: d.limit }] };
  }
  const A = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 }, splitLine: { lineStyle: { color: t.border, opacity: 0.5 } } };
  const yAxis = { type: 'value', scale: !area, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, formatter: panelLineCompact }, axisLine: A.axisLine, splitLine: A.splitLine };
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
/**
 * Where a waterfall's step labels go: flat above the bars, turned up, or none, from the step count and the chart's size.
 * @param {Array<{text: string}>} steps
 * @param {{width: number, height: number}} [size]
 * @returns {{mode: ('flat'|'up'|'none'), top: number}}
 */
export function labelFit(steps, size) {
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
    tooltip: tip(t, { formatter: (p) => { const s = p.data && p.data.step; return s ? esc(s.label) + ': <b>' + esc(s.text) + '</b>' + (s.total ? '' : '<br>running total ' + esc(panelCompact(s.to))) : ''; } }),
    xAxis: { type: 'category', data: steps.map((s) => s.label), axisLine: A.axisLine, axisLabel: Object.assign({}, A.axisLabel, { interval: 0, rotate: many ? 30 : 0 }) },
    yAxis: Object.assign({ type: 'value', name: d.unit || '', axisLine: A.axisLine, splitLine: A.splitLine, nameTextStyle: A.nameTextStyle, axisLabel: Object.assign({}, A.axisLabel, { formatter: panelCompact }) }, range),
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
    xAxis: Object.assign({ type: 'value', min: lo - pad, max: hi + pad, name: d.unit || '', nameLocation: 'end' }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: panelCompact }), splitLine: { show: false } }),
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
    xAxis: Object.assign({ type: 'value', scale: true, name: d.xLabel, nameLocation: 'middle', nameGap: 22 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: panelCompact }) }),
    yAxis: Object.assign({ type: 'value', scale: true, name: d.yLabel, nameLocation: 'middle', nameGap: 42 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: panelCompact }) }),
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
    yAxes.push(Object.assign({ type: 'value', gridIndex: 1, splitNumber: 2 }, A, { axisLabel: Object.assign({}, A.axisLabel, { formatter: panelCompact }) }));
    series.push({ type: 'bar', name: 'Volume', xAxisIndex: 1, yAxisIndex: 1, data: cs.map((c) => ({ value: c.volume, itemStyle: { color: c.close >= c.open ? t.pos : t.neg, opacity: 0.5 } })) });
  }
  return { animationDuration: 400, grid: grids, xAxis: xAxes, yAxis: yAxes, series, axisPointer: { link: [{ xAxisIndex: 'all' }] },
    dataZoom: cs.length > 60 ? [{ type: 'inside', xAxisIndex: vol ? [0, 1] : [0], start: 100 - Math.round(6000 / cs.length), end: 100 }] : [],
    tooltip: tip(t, { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: (ps) => {
      const c = cs[ps[0].dataIndex];
      return c ? esc(c.x) + '<br>O ' + esc(c.open) + '  H ' + esc(c.high) + '<br>L ' + esc(c.low) + '  C <b>' + esc(c.close) + '</b>' + (num(c.volume) ? '<br>Vol ' + panelCompact(c.volume) : '') : '';
    } }) };
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

const BUILD = { line: (d, t) => line(d, t, 'line'), area: (d, t) => line(d, t, 'area'), waterfall, histogram, scatter, candlestick, donut,
  surface: (d, t, s, view) => surfaceOption(d, t, view || 'heatmap') };

/**
 * A panel's chart option: the console's, from the panel's data (HOW_IT_FITS.md section V4) and the theme's tokens (panelTokens).
 * @param {string} kind one of PANEL_KINDS but map and graph (line, area, waterfall, histogram, scatter, candlestick, donut, surface; a graph panel
 *   is drishti-graph's: PanelCharts hands it over)
 * @param {Object} data the panel's data
 * @param {Object<string, string>} tokens panelTokens of the view
 * @param {{width: number, height: number}} [size] the drawing's size (the waterfall's labels use it)
 * @param {('heatmap'|'3d')} [view] the surface's view (default heatmap)
 * @returns {Object} the engine's option
 */
export function panelOption(kind, data, tokens, size, view) {
  const b = BUILD[kind];
  if (!b) { throw new Error('no panel chart ' + kind + ' (' + Object.keys(BUILD).join(', ') + ')'); }
  return b(data || {}, tokens, size || { width: 800, height: 360 }, view);
}
