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
/* More kinds of the spec: radar (a profile over axes), violin and ridgeline (a distribution per category, from a kernel density), curve
   (a term structure: the points ordered by tenor, curves compared by date) and density (counts in bins, square or hexagonal, usually binned
   by the server: the chart draws the bins it is given and never bins a million points itself). Each builds the engine's option, the data
   table and a one-line description like every kind. */
import { registerKind } from './drishti_chart-spec.js';
import { fieldOf, num, members } from './drishti_chart-data.js';
import { textStyle, axisOf } from './drishti_chart-theme.js';
import { rowId, frame, xAxisOf, yAxisOf, buildSeriesChart, heatScale } from './drishti_chart-kinds.js';

/**
 * A Gaussian kernel density estimate of numbers on a grid of points (Silverman's rule of thumb for the bandwidth unless given).
 * @param {number[]} values
 * @param {{points: number, bandwidth: number, extent: number[]}} [o] points default 48
 * @returns {Array<{x: number, d: number}>} the density at each point (d at most 1 after scaling by the peak)
 */
export function kernelDensity(values, o) {
  o = o || {};
  const xs = values.map(num).filter((v) => v !== null).sort((a, b) => a - b), n = xs.length;
  if (!n) { return []; }
  const mean = xs.reduce((s, v) => s + v, 0) / n, sd = Math.sqrt(xs.reduce((s, v) => s + (v - mean) * (v - mean), 0) / Math.max(1, n - 1)) || 1;
  const iqr = (xs[Math.floor(0.75 * (n - 1))] - xs[Math.floor(0.25 * (n - 1))]) || sd * 1.34;
  const bw = o.bandwidth || 0.9 * Math.min(sd, iqr / 1.34) * Math.pow(n, -0.2) || 1;
  const lo = o.extent ? o.extent[0] : xs[0] - 2 * bw, hi = o.extent ? o.extent[1] : xs[n - 1] + 2 * bw, m = o.points || 48;
  const out = [];
  let peak = 0;
  for (let i = 0; i < m; i++) {
    const x = lo + (hi - lo) * i / (m - 1);
    let d = 0;
    for (let k = 0; k < n; k++) { const z = (x - xs[k]) / bw; d += Math.exp(-0.5 * z * z); }
    out.push({ x: x, d: d });
    peak = Math.max(peak, d);
  }
  return out.map((p) => ({ x: p.x, d: peak ? p.d / peak : 0 }));
}

/**
 * Days a tenor label stands for (ON, 1W, 3M, 10Y, a bare number of days) so a curve's points sit in order; unknown labels go last.
 * @param {string} label
 * @returns {number}
 */
export function tenorDays(label) {
  const s = String(label).trim().toUpperCase();
  if (s === 'ON' || s === 'O/N') { return 1; }
  if (s === 'TN' || s === 'T/N') { return 2; }
  const m = /^(\d+(?:\.\d+)?)\s*([DWMY]?)$/.exec(s);
  if (!m) { return Number.MAX_SAFE_INTEGER; }
  return Number(m[1]) * ({ D: 1, W: 7, M: 30.4375, Y: 365.25, '': 1 }[m[2]]);
}

function table(cols, rows) { return { columns: cols.map((c) => (typeof c === 'string' ? { header: c } : c)), rows: rows }; }

/**
 * Radar: one polygon per series over the members of an axes field (the profile of a desk over risk classes).
 * @param {Object} spec axes (the field naming each spoke), value, series ({by}), max
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildRadar(spec, rows, env) {
  const th = env.theme, af = fieldOf(spec.axes) || fieldOf(spec.category) || fieldOf(spec.x), vf = fieldOf(spec.value) || fieldOf(spec.y);
  const sf = spec.series && spec.series.by, axes = members(rows, af), groups = sf ? members(rows, sf) : [spec.title || vf || 'value'];
  const val = (g, a) => rows.filter((r) => String(r[af]) === a && (!sf || String(r[sf]) === g)).reduce((s, r) => s + (num(r[vf]) || 0), 0);
  const top = typeof spec.max === 'number' ? spec.max : Math.max(1e-9, ...groups.map((g) => Math.max(...axes.map((a) => val(g, a))))) * 1.1;
  const data = groups.map((g, i) => ({ name: g, value: axes.map((a) => val(g, a)), r: rows.map((r, k) => k).filter((k) => !sf || String(rows[k][sf]) === g),
    ids: rows.map((r, k) => rowId(spec, r, k)).filter((x, k) => !sf || String(rows[k][sf]) === g) }));
  const option = { animationDuration: 260, textStyle: textStyle(th), color: th.palette, tooltip: { trigger: 'item', confine: true },
    radar: { indicator: axes.map((a) => ({ name: a, max: top })), axisName: { color: th.muted, fontFamily: th.font, fontSize: th.size.font },
      splitLine: { lineStyle: { color: th.border } }, splitArea: { show: false }, axisLine: { lineStyle: { color: th.border } } },
    series: [{ type: 'radar', name: vf, data: data, areaStyle: { opacity: 0.12 }, symbolSize: 4 }] };
  return { option: option, legend: groups.map((g, i) => ({ name: g, colour: th.palette[i % th.palette.length], style: 'box' })),
    table: table([af].concat(groups), axes.map((a) => [a].concat(groups.map((g) => val(g, a))))),
    summary: 'Radar' + (spec.title ? ' of ' + spec.title : '') + ', ' + groups.length + ' series over ' + axes.length + ' axes, up to ' + env.fmt(top) };
}

/** @private the shared build of violin and ridgeline: one density per member of the category field */
function densities(spec, rows) {
  const cf = fieldOf(spec.category) || fieldOf(spec.x), vf = fieldOf(spec.value) || fieldOf(spec.y), cats = members(rows, cf, spec.order);
  const all = rows.map((r) => num(r[vf])).filter((v) => v !== null);
  const lo = Math.min(...all), hi = Math.max(...all), pad = (hi - lo) * 0.08 || 1;
  const per = cats.map((c) => kernelDensity(rows.filter((r) => String(r[cf]) === c).map((r) => r[vf]), { extent: [lo - pad, hi + pad], points: spec.points || 48 }));
  return { cf, vf, cats, per, lo: lo - pad, hi: hi + pad, n: cats.map((c) => rows.filter((r) => String(r[cf]) === c).length) };
}

/**
 * Violin: the density of a value per category, mirrored about the category's centre.
 * @param {Object} spec category, value, points (default 48), order
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildViolin(spec, rows, env) {
  const th = env.theme, D = densities(spec, rows);
  const series = [{ type: 'custom', name: D.vf, data: D.cats.map((c, i) => ({ value: [i, D.n[i]], name: c, r: rows.map((r, k) => k).filter((k) => String(rows[k][D.cf]) === c), ids: [] })),
    encode: { x: 0, y: 1 }, silent: false,
    renderItem: (p, api) => {
      const i = api.value(0), pts = D.per[i], w = 0.42;
      const left = pts.map((q) => api.coord([i - w * q.d, q.x])), right = pts.map((q) => api.coord([i + w * q.d, q.x])).reverse();
      return { type: 'polygon', shape: { points: left.concat(right) }, style: api.style({ fill: th.palette[i % th.palette.length], opacity: 0.55, stroke: th.palette[i % th.palette.length] }) };
    } }];
  const y = axisOf(th, { type: 'value', min: D.lo, max: D.hi, name: (spec.y && spec.y.title) || '', nameLocation: 'middle', nameGap: 52 }, env.fmt);
  const option = frame(spec, env, xAxisOf(spec, env, 'category', D.cats), y, series);
  option.tooltip = { trigger: 'item', confine: true, formatter: (p) => p.name + ': ' + D.n[p.data.value[0]] + ' values' };
  return { option: option, legend: [], table: table([D.cf, 'values'], D.cats.map((c, i) => [c, D.n[i]])),
    summary: 'Violin' + (spec.title ? ' of ' + spec.title : '') + ', the distribution of ' + D.vf + ' for ' + D.cats.length + ' ' + D.cf + ' members' };
}

/**
 * Ridgeline: the densities of a value per category stacked, one row each, overlapping slightly.
 * @param {Object} spec category, value, points (default 48), overlap (default 1.6 rows), order
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildRidgeline(spec, rows, env) {
  const th = env.theme, D = densities(spec, rows), over = spec.overlap || 1.6;
  const series = [{ type: 'custom', name: D.vf, data: D.cats.map((c, i) => ({ value: [i, D.n[i]], name: c, r: rows.map((r, k) => k).filter((k) => String(rows[k][D.cf]) === c), ids: [] })),
    renderItem: (p, api) => {
      const i = api.value(0), pts = D.per[i], rowPx = api.size([0, 1])[1] || 20, base = api.coord([D.lo, i])[1];
      const top = pts.map((q) => [api.coord([q.x, i])[0], base - Math.abs(rowPx) * over * q.d]);
      const flat = [[api.coord([D.hi, i])[0], base], [api.coord([D.lo, i])[0], base]];
      return { type: 'polygon', shape: { points: top.concat(flat) }, style: api.style({ fill: th.palette[i % th.palette.length], opacity: 0.6, stroke: th.ink, lineWidth: 1 }) };
    } }];
  const option = frame(spec, env, axisOf(th, { type: 'value', min: D.lo, max: D.hi, name: (spec.x && spec.x.title) || '', nameLocation: 'middle' }, env.fmt),
    axisOf(th, { type: 'category', data: D.cats, inverse: true, boundaryGap: true }), series);
  option.tooltip = { trigger: 'item', confine: true, formatter: (p) => p.name + ': ' + D.n[p.data.value[0]] + ' values' };
  return { option: option, legend: [], table: table([D.cf, 'values'], D.cats.map((c, i) => [c, D.n[i]])),
    summary: 'Ridgeline' + (spec.title ? ' of ' + spec.title : '') + ', ' + D.cats.length + ' ' + D.cf + ' members' };
}

/**
 * Curve (term structure): points ordered by tenor (ON, 1W, 3M, 2Y ...), one line per curve (a date, a scenario) to compare.
 * @param {Object} spec x (the tenor field), y (the rate), series ({by}: curves to compare)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildCurve(spec, rows, env) {
  const xf = fieldOf(spec.x), order = members(rows, xf).sort((a, b) => tenorDays(a) - tenorDays(b));
  const s = Object.assign({}, spec, { kind: 'line', x: Object.assign({}, typeof spec.x === 'object' ? spec.x : { field: xf }, { type: 'category', order: order }) });
  const built = buildSeriesChart(s, rows, env);
  built.option.series.forEach((x) => { x.showSymbol = true; x.symbolSize = 6; x.smooth = false; });
  built.summary = built.summary.replace(/^Line chart/, 'Curve');
  return built;
}

/**
 * Density: counts in bins as squares or hexagons on two value axes, in a sequential scale. The bins are the server's (x, y the bin
 * centres, value the count); this draws them and never bins the points.
 * @param {Object} spec x, y (bin centres), value (the count), shape ('square' or 'hex'), cell ({x, y}: the bin size, default the least step)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildDensity(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x), yf = fieldOf(spec.y), vf = fieldOf(spec.value) || 'count', hex = spec.shape === 'hex';
  const pts = rows.map((r, i) => ({ value: [num(r[xf]), num(r[yf]), num(r[vf])], r: [i], ids: [rowId(spec, r, i)] })).filter((d) => d.value.every((v) => v !== null));
  const step = (k) => { const u = Array.from(new Set(pts.map((p) => p.value[k]))).sort((a, b) => a - b); let m = Infinity; for (let i = 1; i < u.length; i++) { m = Math.min(m, u[i] - u[i - 1]); } return Number.isFinite(m) ? m : 1; };
  const cx = (spec.cell && spec.cell.x) || step(0), cy = (spec.cell && spec.cell.y) || step(1);
  const { lo, hi, visualMap } = heatScale(Object.assign({}, spec, { scale: { type: 'sequential' } }), pts.map((p) => p.value[2]), env);
  visualMap.dimension = 2;
  const series = [{ type: 'custom', name: vf, data: pts, dimensions: [xf, yf, vf], encode: { x: 0, y: 1 },
    renderItem: (p, api) => {
      const x = api.value(0), y = api.value(1), fill = api.visual('color');
      let poly;
      if (hex) {
        const a = [], rx = cx * 0.5, ry = cy * 0.5;
        for (let k = 0; k < 6; k++) { const t = Math.PI / 3 * k + Math.PI / 6; a.push(api.coord([x + rx * 1.1547 * Math.cos(t), y + ry * 1.1547 * Math.sin(t)])); }
        poly = a;
      } else { poly = [api.coord([x - cx / 2, y - cy / 2]), api.coord([x + cx / 2, y - cy / 2]), api.coord([x + cx / 2, y + cy / 2]), api.coord([x - cx / 2, y + cy / 2])]; }
      return { type: 'polygon', shape: { points: poly }, style: { fill: fill, stroke: th.surface, lineWidth: 0.5 } };
    } }];
  const option = frame(spec, env, xAxisOf(spec, env, 'value'), yAxisOf(spec, env, { scale: true }), series, { visualMap: visualMap });
  option.tooltip = { trigger: 'item', confine: true, formatter: (p) => xf + ' ' + env.xfmt(p.value[0]) + ', ' + yf + ' ' + env.fmt(p.value[1]) + ': ' + env.fmt(p.value[2]) };
  option.grid.bottom = th.size.pad + 34;
  return { option: option, legend: [], table: table([xf, yf, vf], pts.map((p) => p.value)),
    summary: 'Density' + (spec.title ? ' of ' + spec.title : '') + ', ' + pts.length + ' ' + (hex ? 'hexagonal' : 'square') + ' bins, counts from ' + env.fmt(lo) + ' to ' + env.fmt(hi) };
}

/**
 * Registers the kinds of this module (the library's entry calls it once).
 * @returns {void}
 */
export function registerDistributionKinds() {
  registerKind('radar', { build: buildRadar, label: 'radar' });
  registerKind('violin', { build: buildViolin, label: 'violin', cartesian: true });
  registerKind('ridgeline', { build: buildRidgeline, label: 'ridgeline', cartesian: true });
  registerKind('curve', { build: buildCurve, label: 'curve (term structure)', cartesian: true });
  registerKind('density', { build: buildDensity, label: 'density (square or hex bins)', cartesian: true });
}
