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
/* The general kinds with axes: line, area, bar (grouped, stacked, stacked to 100%, horizontal), scatter, bubble, histogram, waterfall,
   box plot, heatmap and candlestick. Each turns a normalised spec and its rows into the ECharts option, the data table, the legend and
   a description (BuiltChart, drishti_chart-spec.js), without a page: the same option under Node and in a browser. Every data item
   carries the rows it was made of (r: row indexes, ids: row ids), so a click explains the right rows and a grid's selection finds its
   marks. Annotations (lines, bands, points, texts) and conditional marks apply to every kind here. */
import { registerKind } from './drishti_chart-spec.js';
import { fieldOf, num, members, bin, boxStats, compileCondition } from './drishti_chart-data.js';
import { toneColour, axisOf, textStyle, alpha, formatterFor } from './drishti_chart-theme.js';

/**
 * The id of a row (the spec's id field, else its index).
 * @param {Object} spec
 * @param {Object} row
 * @param {number} i
 * @returns {string}
 */
export function rowId(spec, row, i) { const f = spec.id || (spec.live && spec.live.key); return f && row[f] !== undefined && row[f] !== null ? String(row[f]) : String(i); }

/**
 * The series a spec draws over x: a list of fields (wide rows), one series per member of a field ({by}: long rows, y.field the value),
 * or y's field alone.
 * @param {Object} spec
 * @param {Object[]} rows
 * @returns {Array<{name: string, field: string, test: ?function(Object): boolean, def: Object}>}
 */
export function seriesDefs(spec, rows) {
  const yField = fieldOf(spec.y) || fieldOf(spec.value);
  if (Array.isArray(spec.series) && spec.series.length) { return spec.series.map((s) => ({ name: s.name || s.field, field: s.field, test: null, def: s })); }
  if (spec.series && spec.series.by) {
    const by = spec.series.by;
    return members(rows, by, spec.series.order).map((m) => ({ name: m, field: yField, test: (r) => String(r[by] === null || r[by] === undefined ? '' : r[by]) === m, def: {} }));
  }
  if (spec.colour && spec.colour.field && spec.kind !== 'heatmap') {
    const by = spec.colour.field;
    return members(rows, by).map((m) => ({ name: m, field: yField, test: (r) => String(r[by]) === m, def: {} }));
  }
  return yField ? [{ name: (spec.y && spec.y.title) || yField, field: yField, test: null, def: spec.tone ? { tone: spec.tone } : {} }] : [];
}

function xTypeOf(spec, dflt) {
  const x = spec.x || {};
  if (x.scale === 'log' || x.type === 'log') { return 'log'; }
  if (x.type === 'time' || x.type === 'temporal') { return 'time'; }
  if (x.type === 'value' || x.type === 'quantitative') { return 'value'; }
  if (x.type === 'category' || x.type === 'nominal' || x.type === 'ordinal') { return 'category'; }
  return dflt;
}

function lineStyleOf(def, th, colour) { return { color: colour, width: def.width || th.size.line, type: def.style === 'dashed' ? 'dashed' : def.style === 'dotted' ? 'dotted' : 'solid' }; }

/** @private the value of a mark-line or band end: a number, a field of the first row, or a category */
function annValue(a, key, rows) {
  const v = a[key];
  if (typeof v === 'string' && rows.length && rows[0][v] !== undefined && a.fromField !== false && num(rows[0][v]) !== null && !/^\d{4}-/.test(v)) { return num(rows[0][v]); }
  return v;
}

/**
 * The annotations of a spec as ECharts mark lines, mark areas and mark points of a series (the first one drawn).
 * @param {Object} spec
 * @param {Object[]} rows
 * @param {Object} th the chart theme
 * @param {{horizontal: boolean, fmt: function(*): string, xfmt: function(*): string}} o
 * @returns {Object} {markLine, markArea, markPoint} for the series
 */
export function annotationMarks(spec, rows, th, o) {
  const lines = [], areas = [], points = [];
  (spec.annotations || []).forEach((a) => {
    const colour = toneColour(th, a.tone || 'muted'), axis = a.axis || (a.x !== undefined ? 'x' : 'y');
    const onX = (axis === 'x') !== !!o.horizontal, key = onX ? 'xAxis' : 'yAxis', f = axis === 'x' ? o.xfmt : o.fmt;
    const label = { show: !!a.label || a.showValue !== false, formatter: () => (a.label || '') + (a.showValue === false ? '' : (a.label ? ' ' : '') + f(annValue(a, 'value', rows) !== undefined ? annValue(a, 'value', rows) : annValue(a, axis, rows))),
      color: colour, fontFamily: th.font, fontSize: th.size.font, position: onX ? 'insideEndTop' : 'insideEndTop' };
    if (a.type === 'line') {
      const v = a.value !== undefined ? annValue(a, 'value', rows) : annValue(a, axis, rows);
      lines.push({ [key]: v, lineStyle: { color: colour, type: a.style || 'dashed', width: a.width || 1.4 }, label: label, name: a.label || '' });
    } else if (a.type === 'band') {
      areas.push([{ [key]: annValue(a, 'from', rows), itemStyle: { color: alpha(colour, a.opacity || 0.12) }, label: { show: !!a.label, formatter: a.label || '', color: colour, fontFamily: th.font, fontSize: th.size.font, position: 'insideTop' } },
        { [key]: annValue(a, 'to', rows) }]);
    } else if (a.type === 'point' || a.type === 'text') {
      points.push({ coord: o.horizontal ? [a.y, a.x] : [a.x, a.y], symbol: a.type === 'text' ? 'none' : 'circle', symbolSize: a.type === 'text' ? 0 : 9, itemStyle: { color: colour },
        label: { show: !!a.label, formatter: a.label || '', color: colour, position: 'top', fontFamily: th.font, fontSize: th.size.font } });
    }
  });
  const out = {};
  if (lines.length) { out.markLine = { silent: true, symbol: 'none', data: lines, animation: false }; }
  if (areas.length) { out.markArea = { silent: true, data: areas }; }
  if (points.length) { out.markPoint = { silent: true, data: points }; }
  return out;
}

/** @private the shared frame of a cartesian kind */
function frame(spec, env, xAxis, yAxis, series, extra) {
  const th = env.theme;
  return Object.assign({
    animationDuration: 260, textStyle: textStyle(th), color: th.palette,
    grid: { left: th.size.pad + 6, right: th.size.pad + 6, top: th.size.pad + 6, bottom: th.size.pad + (spec.zoom ? 36 : 4), containLabel: true },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'cross', lineStyle: { color: th.faint, type: 'dashed' }, crossStyle: { color: th.faint },
      label: { backgroundColor: th.surface2, color: th.ink, borderColor: th.border, fontFamily: th.font, fontSize: th.size.font } } },
    legend: { show: false, data: series.filter((s) => s.name && !s.helper).map((s) => s.name) },
    xAxis: xAxis, yAxis: yAxis, series: series
  }, extra || {});
}

/** @private value axis for y (format, title, min and max, log) */
function yAxisOf(spec, env, extra) {
  const y = spec.y || {};
  return axisOf(env.theme, Object.assign({ type: y.scale === 'log' ? 'log' : 'value', name: y.title || '', nameLocation: 'middle', nameGap: 52, min: y.min, max: y.max, scale: y.zero === false }, extra || {}), env.fmt);
}

/** @private an x axis of the spec's type: categories, time or values */
function xAxisOf(spec, env, type, cats) {
  const x = spec.x || {};
  const base = { type: type, name: x.title || '', nameLocation: 'middle' };
  if (type === 'category') { base.data = cats; base.boundaryGap = spec.kind !== 'line' && spec.kind !== 'area'; }
  if (type === 'value' || type === 'log') { base.scale = x.zero !== true; base.min = x.min; base.max = x.max; }
  const ax = axisOf(env.theme, base, env.xfmt);
  if (type === 'category' || type === 'time') { ax.axisPointer = { label: { show: false } }; }   // the tooltip names the category: no second label over the axis's own
  if (type === 'category' && x.format) { ax.axisLabel.formatter = (v) => env.xfmt(v); }
  if (type === 'time') { ax.axisLabel.formatter = x.format ? (v) => env.xfmt(new Date(v).toISOString().slice(0, 10)) : (v) => new Date(v).toISOString().slice(0, 10); ax.splitLine = { show: false }; }
  return ax;
}

const timeOf = (v) => (v instanceof Date ? v.getTime() : typeof v === 'number' ? v : Date.parse(String(v).length === 10 ? v + 'T00:00:00Z' : v));

/**
 * Line, area and bar: categories (or time, or values) on one axis, one series per field or member, with stacks, percent stacks,
 * negated series, dashed styles, a right-hand axis, conditional marks and annotations.
 * @param {Object} spec
 * @param {Object[]} rows
 * @param {{theme: Object, fmt: function(*): string, xfmt: function(*): string}} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildSeriesChart(spec, rows, env) {
  const th = env.theme, kind = spec.kind, xField = fieldOf(spec.x) || fieldOf(spec.category);
  const horizontal = kind === 'bar' && (spec.orient === 'horizontal' || spec.horizontal === true);
  const xType = xTypeOf(spec, 'category'), defs = seriesDefs(spec, rows);
  const pct = spec.stack === 'percent';
  let cats = xType === 'category' ? (spec.x && spec.x.domain ? spec.x.domain.map(String) : members(rows, xField, spec.x && spec.x.order)) : null;
  const sums = (def) => {
    const m = new Map();
    rows.forEach((r, i) => {
      if (def.test && !def.test(r)) { return; }
      const k = xType === 'category' ? String(r[xField] === null || r[xField] === undefined ? '' : r[xField]) : xType === 'time' ? timeOf(r[xField]) : num(r[xField]);
      const v = num(r[def.field]);
      if (k === null || v === null) { return; }
      const e = m.get(k) || { v: 0, r: [], ids: [] };
      e.v += def.def.negate ? -v : v;
      e.r.push(i); e.ids.push(rowId(spec, r, i));
      m.set(k, e);
    });
    return m;
  };
  const maps = defs.map(sums);
  const when = (spec.colourWhen || []).map((c) => ({ test: compileCondition(c.when), colour: toneColour(th, c.tone || 'bad') }));
  const colourFor = (e) => { const r = e && e.r.length ? rows[e.r[0]] : null; const w = r ? when.find((c) => c.test(r)) : null; return w ? w.colour : null; };
  if (cats && spec.sort && /value/.test(spec.sort) && maps.length) {
    const desc = /desc/.test(spec.sort);
    cats = cats.slice().sort((a, b) => ((maps[0].get(a) || { v: 0 }).v - (maps[0].get(b) || { v: 0 }).v) * (desc ? -1 : 1));
  }
  const totals = cats && pct ? cats.map((c) => maps.reduce((s, m) => s + Math.abs((m.get(c) || { v: 0 }).v), 0)) : null;
  const keys = cats || Array.from(new Set([].concat.apply([], maps.map((m) => Array.from(m.keys()))))).sort((a, b) => a - b);
  const series = defs.map((d, si) => {
    const colour = toneColour(th, d.def.tone, si), m = maps[si], type = d.def.type || (kind === 'area' ? 'line' : kind);
    const data = keys.map((k, ci) => {
      const e = m.get(k);
      if (!e) { return xType === 'category' ? { value: null } : null; }
      const v = pct ? (totals[ci] ? 100 * e.v / totals[ci] : 0) : e.v;
      const item = xType === 'category' ? { value: v, r: e.r, ids: e.ids } : { value: [k, v], r: e.r, ids: e.ids }, c = when.length ? colourFor(e) : null;
      if (c) { item.itemStyle = { color: c }; }
      return item;
    }).filter((x) => x !== null);
    const s = { type: type, name: d.name, data: data, itemStyle: { color: colour }, emphasis: { focus: 'series' }, selectedMode: 'multiple',
      select: { itemStyle: { borderColor: th.tokens.accent, borderWidth: 2 } }, field: d.field };
    if (type === 'line') {
      Object.assign(s, { showSymbol: !!spec.markers || data.length <= 60, symbolSize: th.size.symbol, lineStyle: lineStyleOf(d.def, th, colour), smooth: !!(d.def.smooth || spec.smooth), connectNulls: true });
      if (kind === 'area' || d.def.area) { s.areaStyle = { color: alpha(colour, d.def.opacity || 0.16) }; }
      if (spec.step) { s.step = spec.step === true ? 'middle' : spec.step; }
    }
    if (type === 'bar') { s.barMaxWidth = 42; s.itemStyle.borderRadius = horizontal ? [0, 3, 3, 0] : [3, 3, 0, 0]; if (d.def.opacity) { s.itemStyle.opacity = d.def.opacity; } }
    if (spec.stack || d.def.stack) { s.stack = typeof d.def.stack === 'string' ? d.def.stack : 'total'; if (type === 'bar') { s.itemStyle.borderRadius = 0; } }
    if (d.def.axis === 'right') { s.yAxisIndex = 1; }
    if (spec.labels) { s.label = { show: true, position: type === 'bar' ? (horizontal ? 'right' : 'top') : 'top', color: th.muted, fontFamily: th.font, fontSize: th.size.font, formatter: (p) => env.fmt(Array.isArray(p.value) ? p.value[1] : p.value) }; }
    return s;
  });
  if (series.length) { Object.assign(series[0], annotationMarks(spec, rows, th, { horizontal: horizontal, fmt: pct ? formatterFor('number') : env.fmt, xfmt: env.xfmt })); }
  (spec.marks || []).forEach((mk, mi) => {
    const test = compileCondition(mk.when), field = mk.field || (defs[0] && defs[0].field), colour = toneColour(th, mk.tone || 'bad');
    const data = [];
    rows.forEach((r, i) => {
      if (!test(r)) { return; }
      const v = num(r[field]);
      if (v === null) { return; }
      const k = xType === 'time' ? timeOf(r[xField]) : xType === 'category' ? String(r[xField]) : num(r[xField]);
      data.push({ value: horizontal ? [v, k] : [k, v], r: [i], ids: [rowId(spec, r, i)] });
    });
    series.push({ type: 'scatter', name: mk.name || 'mark ' + (mi + 1), data: data, symbolSize: th.size.symbol + 4, itemStyle: { color: colour, borderColor: th.surface, borderWidth: 1.5 }, z: 5, field: field, mark: true,
      selectedMode: 'multiple', select: { itemStyle: { borderColor: th.tokens.accent, borderWidth: 2 } } });
  });
  const valueAxes = [yAxisOf(spec, env, pct ? { max: 100, axisLabel: Object.assign(textStyle(th), { formatter: (v) => v + '%' }) } : null)];
  if (defs.some((d) => d.def.axis === 'right')) { valueAxes.push(yAxisOf(spec, env, { name: (spec.y2 && spec.y2.title) || '', splitLine: { show: false }, position: 'right' })); }
  const catAxis = xAxisOf(spec, env, xType, xType === 'category' ? keys : null);
  const option = frame(spec, env, horizontal ? valueAxes[0] : catAxis, horizontal ? Object.assign(catAxis, { inverse: true }) : valueAxes, series);
  if (horizontal) { option.series.forEach((s) => { if (!s.mark) { s.data = s.data.map((x) => (x && Array.isArray(x.value) ? Object.assign({}, x, { value: [x.value[1], x.value[0]] }) : x)); } }); }
  if (kind === 'bar' && !spec.stack) { option.tooltip.axisPointer = { type: 'shadow', shadowStyle: { color: alpha(th.tokens.link, 0.06) } }; }
  const table = { columns: [{ header: (spec.x && spec.x.title) || xField || '', format: spec.x && spec.x.format }].concat(defs.map((d) => ({ header: d.name, format: spec.y && spec.y.format }))),
    rows: keys.map((k) => [xType === 'time' ? new Date(k).toISOString().slice(0, 10) : k].concat(maps.map((m) => (m.get(k) ? m.get(k).v : null)))) };
  const legend = series.filter((s) => !s.helper).map((s) => ({ name: s.name, colour: s.itemStyle.color, style: s.mark ? 'dot' : s.type === 'line' ? (s.lineStyle && s.lineStyle.type !== 'solid' ? 'dash' : 'line') : 'box' }));
  const first = table.rows.length ? table.rows[0][0] : '', last = table.rows.length ? table.rows[table.rows.length - 1][0] : '';
  const words = { line: 'Line', area: 'Area', bar: horizontal ? 'Horizontal bar' : 'Bar' }[kind] || 'Chart';
  return { option: option, table: table, legend: legend, summary: words + ' chart' + (spec.title ? ' of ' + spec.title : '') + ', ' + defs.length + ' series, ' + keys.length + ' points' + (keys.length ? ', from ' + first + ' to ' + last : '') };
}

/**
 * Scatter and bubble: two value axes, a series per colour member, a size field scaled by area for bubbles.
 * @param {Object} spec
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildScatter(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x), yf = fieldOf(spec.y), sf = fieldOf(spec.size), by = spec.colour && spec.colour.field || (spec.series && spec.series.by);
  const groups = by ? members(rows, by) : [(spec.y && spec.y.title) || yf];
  const xt = xTypeOf(spec, 'value'), yt = spec.y && (spec.y.type === 'category' || spec.y.type === 'nominal' || spec.y.type === 'ordinal') ? 'category' : 'value';
  const val = (r, f, t) => (t === 'category' ? (r[f] === null || r[f] === undefined ? null : String(r[f])) : t === 'time' ? timeOf(r[f]) : num(r[f]));
  const when = (spec.colourWhen || []).map((c) => ({ test: compileCondition(c.when), colour: toneColour(th, c.tone || 'bad') }));
  const sym = spec.symbol === 'tick-v' ? { symbol: 'rect', size: [3, 20] } : spec.symbol === 'tick-h' ? { symbol: 'rect', size: [20, 3] } : { symbol: spec.symbol === 'rect' ? 'rect' : 'circle', size: null };
  const sizes = sf ? rows.map((r) => Math.abs(num(r[sf]) || 0)) : [];
  const maxS = sizes.length ? Math.max.apply(null, sizes.concat([1e-9])) : 1;
  const series = groups.map((g, gi) => ({ type: 'scatter', name: g, itemStyle: { color: toneColour(th, by ? null : spec.tone, gi), opacity: spec.opacity || 0.85 }, selectedMode: 'multiple', field: yf,
    select: { itemStyle: { borderColor: th.tokens.accent, borderWidth: 2 } }, emphasis: { focus: 'series' }, symbol: sym.symbol,
    data: rows.map((r, i) => ({ r: r, i: i })).filter((x) => !by || String(x.r[by]) === g).filter((x) => val(x.r, xf, xt) !== null && val(x.r, yf, yt) !== null)
      .map((x) => {
        const w = when.find((c) => c.test(x.r)), item = { value: [val(x.r, xf, xt), val(x.r, yf, yt)], r: [x.i], ids: [rowId(spec, x.r, x.i)],
          symbolSize: sym.size || (sf ? 6 + 38 * Math.sqrt(Math.abs(num(x.r[sf]) || 0) / maxS) : th.size.symbol + 2) };
        if (w) { item.itemStyle = { color: w.colour }; }
        return item;
      }) }));
  if (series.length) { Object.assign(series[0], annotationMarks(spec, rows, th, { fmt: env.fmt, xfmt: env.xfmt })); }
  const dom = (k, f) => (spec[k] && spec[k].domain ? spec[k].domain.map(String) : members(rows, f, spec[k] && spec[k].order));
  const xAxis = xt === 'category' ? xAxisOf(spec, env, 'category', dom('x', xf)) : xAxisOf(spec, env, xt, null);
  const yAxis = yt === 'category' ? axisOf(th, { type: 'category', data: dom('y', yf), name: (spec.y && spec.y.title) || '', nameLocation: 'middle', nameGap: 60, inverse: true, splitLine: { show: true, lineStyle: { color: th.grid } } }) : yAxisOf(spec, env);
  const option = frame(spec, env, xAxis, yAxis, series);
  option.tooltip.trigger = 'item';
  const cols = [{ header: (spec.x && spec.x.title) || xf, format: spec.x && spec.x.format }, { header: (spec.y && spec.y.title) || yf, format: spec.y && spec.y.format }]
    .concat(sf ? [{ header: (spec.size && spec.size.title) || sf }] : []).concat(by ? [{ header: by }] : []);
  const table = { columns: cols, rows: rows.map((r) => [r[xf], r[yf]].concat(sf ? [r[sf]] : []).concat(by ? [r[by]] : [])) };
  return { option: option, table: table, legend: series.map((s) => ({ name: s.name, colour: s.itemStyle.color, style: 'dot' })),
    summary: (sf ? 'Bubble' : 'Scatter') + ' chart' + (spec.title ? ' of ' + spec.title : '') + ', ' + rows.length + ' points in ' + groups.length + ' groups' };
}

/**
 * Histogram: the values of a field in equal bins (a value axis, so lines at a value, VaR's for one, sit where they belong), with the
 * tail beyond a value in its own tone.
 * @param {Object} spec bins (a count or "auto"), binned (the server's bins {edges, counts}: drawn as they are, no rows needed), tail
 *   {below|above: a number or a field of the first row, tone}
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildHistogram(spec, rows, env) {
  const th = env.theme, vf = fieldOf(spec.value) || fieldOf(spec.x), pre = spec.binned;
  const values = pre ? [] : rows.map((r) => r[vf]);
  // the server's bins ({edges, counts}: the server sends a summary, never the vector) or bins of the rows' values
  const bins = pre ? pre.counts.map((c, i) => ({ x0: num(pre.edges[i]), x1: num(pre.edges[i + 1]), count: num(c) || 0 })) : bin(values, { bins: spec.bins || 'auto', extent: spec.extent });
  if (pre) { rows = []; }
  const tail = spec.tail || null, cut = tail ? num(annValue(tail, tail.below !== undefined ? 'below' : 'above', rows)) : null;
  const inTail = (b) => cut !== null && (tail.below !== undefined ? b.x1 <= cut + 1e-9 : b.x0 >= cut - 1e-9);
  const base = toneColour(th, spec.tone || 'link'), tailColour = toneColour(th, tail && tail.tone || 'bad');
  const data = bins.map((b) => ({ value: [b.x0, b.x1, b.count], itemStyle: { color: inTail(b) ? tailColour : base },
    r: rows.map((r, i) => i).filter((i) => { const v = num(rows[i][vf]); return v !== null && v >= b.x0 && (v < b.x1 || b === bins[bins.length - 1]); }) }));
  data.forEach((d) => { d.ids = d.r.map((i) => rowId(spec, rows[i], i)); });
  const series = [Object.assign({ type: 'custom', name: (spec.value && spec.value.title) || vf, field: vf, encode: { x: [0, 1], y: 2 }, data: data,
    renderItem: (params, api) => {
      const a = api.coord([api.value(0), api.value(2)]), b = api.coord([api.value(1), 0]);
      return { type: 'rect', shape: { x: a[0] + 0.5, y: a[1], width: Math.max(1, b[0] - a[0] - 1), height: b[1] - a[1] }, style: api.style() };
    } }, annotationMarks(spec, rows, th, { fmt: env.fmt, xfmt: env.fmt }))];
  const option = frame(spec, env, axisOf(th, { type: 'value', scale: true, name: (spec.value && spec.value.title) || '', nameLocation: 'middle' }, env.fmt),
    axisOf(th, { type: 'value', name: 'count', nameLocation: 'middle', nameGap: 40 }, formatterFor('integer')), series);
  option.tooltip = { trigger: 'item', confine: true };
  const legend = [{ name: series[0].name, colour: base, style: 'box' }].concat(tail ? [{ name: tail.label || 'tail', colour: tailColour, style: 'box' }] : []);
  return { option: option, legend: legend, table: { columns: [{ header: 'from', format: spec.value && spec.value.format }, { header: 'to', format: spec.value && spec.value.format }, { header: 'count' }],
    rows: bins.map((b) => [b.x0, b.x1, b.count]) },
  summary: 'Histogram' + (spec.title ? ' of ' + spec.title : '') + ', ' + (pre ? bins.reduce((t, b) => t + b.count, 0) : values.length) + ' values in ' + bins.length + ' bins' + (tail ? ', ' + data.filter((d, i) => inTail(bins[i])).reduce((s, d) => s + d.value[2], 0) + ' in the tail' : '') };
}

/**
 * Waterfall: each step's change from the one before, as floating bars, with totals (the first, the last, or rows marked by a field)
 * drawn from zero; increases and decreases in their tones.
 * @param {Object} spec category, value, totals (['first', 'last'] or {field}), up and down (tones; default good and bad)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildWaterfall(spec, rows, env) {
  const th = env.theme, cf = fieldOf(spec.category) || fieldOf(spec.x), vf = fieldOf(spec.value) || fieldOf(spec.y);
  const totals = spec.totals || ['first', 'last'];
  const isTotal = (r, i) => (Array.isArray(totals) ? (totals.indexOf('first') >= 0 && i === 0) || (totals.indexOf('last') >= 0 && i === rows.length - 1) : !!r[totals.field]);
  let run = 0;
  const base = [], bars = [];
  rows.forEach((r, i) => {
    const v = num(r[vf]) || 0, item = { r: [i], ids: [rowId(spec, r, i)] };
    if (isTotal(r, i)) { run = v; base.push({ value: Math.min(0, v) }); bars.push(Object.assign({ value: Math.abs(v), real: v, itemStyle: { color: toneColour(th, spec.total || 'link') } }, item)); return; }
    const lo = v >= 0 ? run : run + v;
    base.push({ value: lo });
    bars.push(Object.assign({ value: Math.abs(v), real: v, itemStyle: { color: toneColour(th, v >= 0 ? spec.up || 'good' : spec.down || 'bad') } }, item));
    run += v;
  });
  const cats = rows.map((r) => String(r[cf]));
  const series = [{ type: 'bar', name: '_base', helper: true, stack: 'w', data: base, itemStyle: { color: 'transparent' }, emphasis: { disabled: true }, silent: true, tooltip: { show: false } },
    Object.assign({ type: 'bar', name: (spec.value && spec.value.title) || vf, field: vf, stack: 'w', data: bars, barMaxWidth: 46, selectedMode: 'multiple',
      select: { itemStyle: { borderColor: th.tokens.accent, borderWidth: 2 } }, itemStyle: { color: th.tokens.link },
      label: { show: spec.labels !== false, position: 'top', color: th.muted, fontFamily: th.font, fontSize: th.size.font, formatter: (p) => (p.data.real >= 0 && !isTotal(rows[p.dataIndex], p.dataIndex) ? '+' : '') + env.fmt(p.data.real) } },
    annotationMarks(spec, rows, th, { fmt: env.fmt, xfmt: env.xfmt }))];
  const option = frame(spec, env, xAxisOf(spec, env, 'category', cats), yAxisOf(spec, env), series);
  option.tooltip = { trigger: 'item', confine: true };
  return { option: option, legend: [{ name: 'increase', colour: toneColour(th, spec.up || 'good'), style: 'box' }, { name: 'decrease', colour: toneColour(th, spec.down || 'bad'), style: 'box' }, { name: 'total', colour: toneColour(th, spec.total || 'link'), style: 'box' }],
    table: { columns: [{ header: cf }, { header: vf, format: spec.value && spec.value.format }], rows: rows.map((r) => [r[cf], num(r[vf])]) },
    summary: 'Waterfall' + (spec.title ? ' of ' + spec.title : '') + ', ' + rows.length + ' steps from ' + (cats[0] || '') + ' to ' + (cats[cats.length - 1] || ''), legendStatic: true };
}

/**
 * Box plot: the five figures of a field's values per category (whiskers at 1.5 IQR) and the values beyond them as points.
 * @param {Object} spec category, value
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildBoxplot(spec, rows, env) {
  const th = env.theme, cf = fieldOf(spec.category) || fieldOf(spec.x), vf = fieldOf(spec.value) || fieldOf(spec.y), cats = members(rows, cf);
  const stats = cats.map((c) => { const idx = rows.map((r, i) => i).filter((i) => String(rows[i][cf]) === c); return { idx: idx, s: boxStats(idx.map((i) => rows[i][vf])) }; });
  const colour = toneColour(th, spec.tone || 'link');
  const series = [Object.assign({ type: 'boxplot', name: (spec.value && spec.value.title) || vf, field: vf, itemStyle: { color: alpha(colour, 0.18), borderColor: colour },
    data: stats.map((x) => ({ value: x.s ? x.s.box : [], r: x.idx, ids: x.idx.map((i) => rowId(spec, rows[i], i)) })) }, annotationMarks(spec, rows, th, { fmt: env.fmt, xfmt: env.xfmt })),
  { type: 'scatter', name: 'outliers', field: vf, itemStyle: { color: toneColour(th, 'bad') }, symbolSize: th.size.symbol,
    data: [].concat.apply([], stats.map((x, ci) => (x.s ? x.s.outliers : []).map((v) => ({ value: [cats[ci], v], r: x.idx.filter((i) => num(rows[i][vf]) === v), ids: [] })))) }];
  const option = frame(spec, env, xAxisOf(spec, env, 'category', cats), yAxisOf(spec, env), series);
  option.tooltip = { trigger: 'item', confine: true };
  return { option: option, legend: [{ name: series[0].name, colour: colour, style: 'box' }, { name: 'outliers', colour: toneColour(th, 'bad'), style: 'dot' }],
    table: { columns: [{ header: cf }, { header: 'low' }, { header: 'Q1' }, { header: 'median' }, { header: 'Q3' }, { header: 'high' }, { header: 'n' }].map((c, i) => (i ? Object.assign(c, { format: i < 6 && spec.value && spec.value.format }) : c)),
      rows: stats.map((x, i) => [cats[i]].concat(x.s ? x.s.box : [null, null, null, null, null]).concat([x.s ? x.s.n : 0])) },
    summary: 'Box plot' + (spec.title ? ' of ' + spec.title : '') + ', ' + cats.length + ' groups' };
}

/**
 * Heatmap: a value at each (x, y) pair of categories, coloured on a sequential or diverging scale (diverging is centred on mid, 0 by
 * default), with the legend's scale under it.
 * @param {Object} spec x, y, value, scale {type: 'sequential'|'diverging', mid}, labels
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildHeatmap(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x), yf = fieldOf(spec.y), vf = fieldOf(spec.value) || fieldOf(spec.colour);
  const xs = members(rows, xf, spec.x && spec.x.order), ys = members(rows, yf, spec.y && spec.y.order);
  const data = rows.map((r, i) => ({ value: [xs.indexOf(String(r[xf])), ys.indexOf(String(r[yf])), num(r[vf])], r: [i], ids: [rowId(spec, r, i)] })).filter((d) => d.value[2] !== null);
  const vals = data.map((d) => d.value[2]), div = spec.scale && spec.scale.type === 'diverging', mid = div ? (spec.scale.mid || 0) : 0;
  let lo = vals.length ? Math.min.apply(null, vals) : 0, hi = vals.length ? Math.max.apply(null, vals) : 1;
  if (div) { const m = Math.max(Math.abs(lo - mid), Math.abs(hi - mid)); lo = mid - m; hi = mid + m; }
  const series = [{ type: 'heatmap', name: (spec.value && spec.value.title) || vf, field: vf, data: data, selectedMode: 'multiple', select: { itemStyle: { borderColor: th.tokens.accent, borderWidth: 2 } },
    itemStyle: { borderColor: th.surface, borderWidth: 1 }, emphasis: { itemStyle: { borderColor: th.ink, borderWidth: 1 } },
    label: { show: !!spec.labels, color: th.ink, fontFamily: th.font, fontSize: th.size.font - 1, formatter: (p) => env.fmt(p.value[2]) } }];
  const option = frame(spec, env, Object.assign(xAxisOf(spec, env, 'category', xs), { splitArea: { show: false } }), Object.assign(axisOf(th, { type: 'category', data: ys, inverse: true }), { splitArea: { show: false } }), series, {
    visualMap: { min: lo, max: hi, calculable: false, orient: 'horizontal', left: 'center', bottom: 0, itemWidth: 10, itemHeight: 140, inRange: { color: div ? th.diverging : th.sequential },
      textStyle: textStyle(th), formatter: (v) => env.fmt(v), text: [env.fmt(hi), env.fmt(lo)] } });
  option.grid.bottom = th.size.pad + 34;
  option.tooltip = { trigger: 'item', confine: true };
  return { option: option, legend: [], table: { columns: [{ header: yf }].concat(xs.map((x) => ({ header: x, format: spec.value && spec.value.format }))),
    rows: ys.map((y) => [y].concat(xs.map((x) => { const r = rows.find((q) => String(q[xf]) === x && String(q[yf]) === y); return r ? num(r[vf]) : null; }))) },
  summary: 'Heatmap' + (spec.title ? ' of ' + spec.title : '') + ', ' + ys.length + ' rows by ' + xs.length + ' columns, from ' + env.fmt(lo) + ' to ' + env.fmt(hi) };
}

/**
 * Candlestick: open, close, low and high of each date, up in the good tone and down in the bad one.
 * @param {Object} spec x (the date), open, close, low, high (fields)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildCandlestick(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x), f = (k) => fieldOf(spec[k]) || k;
  const cats = rows.map((r) => String(r[xf]));
  const series = [Object.assign({ type: 'candlestick', name: spec.title || 'price', field: f('close'), itemStyle: { color: th.tones.good, color0: th.tones.bad, borderColor: th.tones.good, borderColor0: th.tones.bad },
    data: rows.map((r, i) => ({ value: [num(r[f('open')]), num(r[f('close')]), num(r[f('low')]), num(r[f('high')])], r: [i], ids: [rowId(spec, r, i)] })) }, annotationMarks(spec, rows, th, { fmt: env.fmt, xfmt: env.xfmt }))];
  const option = frame(spec, env, xAxisOf(spec, env, 'category', cats), yAxisOf(spec, env, { scale: true }), series);
  return { option: option, legend: [{ name: 'up', colour: th.tones.good, style: 'box' }, { name: 'down', colour: th.tones.bad, style: 'box' }], legendStatic: true,
    table: { columns: [{ header: xf }, { header: 'open' }, { header: 'close' }, { header: 'low' }, { header: 'high' }], rows: rows.map((r) => [r[xf], r[f('open')], r[f('close')], r[f('low')], r[f('high')]]) },
    summary: 'Candlestick chart' + (spec.title ? ' of ' + spec.title : '') + ', ' + rows.length + ' days' };
}

/**
 * Layers: several cartesian specs over the same axes (a strip of dots with a tick at each median; bars with a line), the data and the
 * encodings a layer leaves out taken from the spec around it, categories the union of every layer's.
 * @param {Object} spec layers: [specs]
 * @param {Object[]} rows the spec's rows (a layer with no data of its own uses them)
 * @param {Object} env
 * @param {function(string): ?Object} kindOf the kind registry's lookup
 * @param {function(Object): Object[]} rowsOfSpec a layer's rows
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildLayers(spec, rows, env, kindOf, rowsOfSpec) {
  const layers = (spec.layers || []).map((l) => {
    const o = Object.assign({}, l);
    ['x', 'y', 'id', 'legend'].forEach((k) => { if (o[k] === undefined && spec[k] !== undefined) { o[k] = spec[k]; } });
    ['x', 'y'].forEach((k) => { if (typeof o[k] === 'string') { o[k] = { field: o[k] }; } else if (o[k]) { o[k] = Object.assign({}, o[k]); } });
    o.annotations = o.annotations || []; o.marks = o.marks || [];
    return { spec: o, rows: o.data ? rowsOfSpec(o) : rows };
  });
  ['x', 'y'].forEach((k) => {
    const cats = layers.filter((l) => l.spec[k] && ['category', 'nominal', 'ordinal'].indexOf(l.spec[k].type) >= 0 || (l.spec[k] && !l.spec[k].type && k === 'x' && l.spec.kind !== 'scatter'));
    if (!cats.length) { return; }
    let all = [];
    cats.forEach((l) => { const ms = members(l.rows, l.spec[k].field, l.spec[k].order); ms.forEach((m) => { if (all.indexOf(m) < 0) { all.push(m); } }); });
    if (cats[0].spec[k].order) { all = members(cats.map((l) => l.rows).reduce((a, b) => a.concat(b), []), cats[0].spec[k].field, cats[0].spec[k].order); }
    cats.forEach((l) => { l.spec[k].domain = all; });
  });
  const built = layers.map((l) => { const kd = kindOf(l.spec.kind); if (!kd) { throw new Error('no kind ' + l.spec.kind + ' in a layer'); } return kd.build(l.spec, l.rows, env); });
  const first = built[0], option = first.option;
  built.slice(1).forEach((b, i) => b.option.series.forEach((s) => {
    s.layer = i + 1;
    s.data = (s.data || []).map((d) => (d && d.r ? Object.assign({}, d, { layerRows: i + 1 }) : d));
    option.series.push(s);
  }));
  option.series.forEach((s) => { if (s.layer === undefined) { s.layer = 0; } });
  option.legend.data = option.series.filter((s) => s.name && !s.helper).map((s) => s.name);
  const table = { columns: [{ header: 'layer' }].concat(first.table.columns), rows: [] };
  built.forEach((b, i) => b.table.rows.forEach((r) => table.rows.push([String(i + 1)].concat(r))));
  return { option: option, table: table, legend: [].concat.apply([], built.map((b) => b.legend)), summary: built.map((b) => b.summary).join('; '), layers: layers.map((l) => l.rows) };
}

/**
 * Registers the kinds of this module (the library's entry calls it once).
 * @returns {void}
 */
export function registerAxisKinds() {
  registerKind('line', { build: buildSeriesChart, label: 'line', cartesian: true });
  registerKind('area', { build: buildSeriesChart, label: 'area', cartesian: true });
  registerKind('bar', { build: buildSeriesChart, label: 'bar', cartesian: true });
  registerKind('scatter', { build: buildScatter, label: 'scatter', cartesian: true });
  registerKind('bubble', { build: buildScatter, label: 'bubble', cartesian: true });
  registerKind('histogram', { build: buildHistogram, label: 'histogram', cartesian: true });
  registerKind('waterfall', { build: buildWaterfall, label: 'waterfall', cartesian: true });
  registerKind('boxplot', { build: buildBoxplot, label: 'box plot', cartesian: true });
  registerKind('heatmap', { build: buildHeatmap, label: 'heatmap', cartesian: false });
  registerKind('candlestick', { build: buildCandlestick, label: 'candlestick', cartesian: true });
}
