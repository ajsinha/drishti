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
/* Four general kinds for things laid along a line, over segments, in a matrix or through space:

     segmented a scatter over one long axis made of named segments (sessions of a day, books on one axis, chromosomes of a genome), with
               threshold lines and the points past the first threshold in its tone, the tallest named one per member
               { kind: 'segmented', x: 'X', y: 'Y', segments: [{ name, from, to }], thresholds: [{ value, label, tone }], labelTop: { field, n } }
     matrix    a matrix of category cells (a status per desk and day, an alteration per row and column) in the server's order, every
               column kept (an empty one too), annotation tracks over either axis, the overview strip past many columns
               { kind: 'matrix', x: { field, domain }, y: { field, domain }, value: { field, tones, order }, tracks: [...] }

     lollipop  a stem and a head at each position of one long axis (events along a timeline, breaches along a curve, changes along a
               sequence): the head's height a figure, its colour a category (a tone each) or a number (a ramp, diverging around a middle),
               stretches of the axis as named bands
               { kind: 'lollipop', x: 'POS', y: 'COUNT', colour: { field: 'SCORE', scale: 'diverging', mid: 0 } | { field: 'CLASS', tones: {...} },
                 length: 400, bands: [{ name, from, to }] }
     trace3d   points in three dimensions joined in order (a path, a chain, a trajectory), each coloured by a figure, turned by a drag
               (and slowly by itself unless reduced motion is asked for); the selection's points are drawn larger. On the vendored
               ECharts GL, loaded the first time a trace3d is drawn; where WebGL is off the same points are drawn flat (x against y), with
               the same table.
               { kind: 'trace3d', x: 'X', y: 'Y', z: 'Z', order: 'POS', colour: { field: 'MEAN', scale: 'diverging' }, id: 'POS', turn: true } */
import { fieldOf, num } from './drishti_chart-data.js';
import { toneColour, alpha, textStyle } from './drishti_chart-theme.js';
import { registerKind } from './drishti_chart-spec.js';
import { rowId, buildScatter, buildHeatmap } from './drishti_chart-kinds.js';
import { trackColour } from './drishti_chart-tracks.js';
import { loadScript, staticUrl } from '../../drishti-core/src/drishti_index.js';

/** @private a colour encoding over the rows: category (tones) or number (a continuous scale the legend shows) */
function colourScale(spec, rows, th) {
  const c = spec.colour || {}, f = fieldOf(c);
  if (!f) { return null; }
  const t = trackColour({ type: c.type, tones: c.tones, order: c.order || c.domain, scale: c.scale, mid: c.mid, min: c.min, max: c.max }, rows.map((r) => r[f]), th);
  const keys = t.category ? t.keys : Array.isArray(c.legend) && c.legend.length ? c.legend.map((k) => ({ name: String(k.name), colour: t.colour(k.value), style: 'dot' }))
    : [{ name: (c.title || f) + ': low to high', colour: th.sequential[2], style: 'box' }];
  return Object.assign(t, { field: f, title: c.title || f, legendKeys: keys });
}

/**
 * Lollipop: a stem and a head at each position; the head's colour a category or a number.
 * @param {Object} spec x (position), y (height), colour ({field, tones} or {field, scale, mid, legend: [{name, value}]: the keys of a number}), length (the axis's end), bands [{name, from, to}]
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildLollipop(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x) || 'x', yf = fieldOf(spec.y) || 'y', cs = colourScale(spec, rows, th);
  const bands = (spec.bands || []).map((b) => ({ type: 'band', axis: 'x', from: num(b.from), to: num(b.to), label: b.name, tone: b.tone || 'accent' }));
  const s = Object.assign({}, spec, { kind: 'scatter', colour: undefined, colourWhen: undefined,
    x: Object.assign({ field: xf, min: 0, max: num(spec.length) || undefined }, typeof spec.x === 'object' ? spec.x : {}),
    y: Object.assign({ field: yf, min: 0 }, typeof spec.y === 'object' ? spec.y : {}), annotations: (spec.annotations || []).concat(bands) });
  const built = buildScatter(s, rows, env), heads = built.option.series[0];
  heads.name = (spec.y && spec.y.title) || yf;
  heads.symbolSize = th.size.symbol + 4;
  if (cs) {
    heads.data.forEach((it) => { const c = cs.colour(rows[it.r[0]][cs.field]); if (c) { it.itemStyle = { color: c }; } });
    built.legend = cs.legendKeys;
    built.legendStatic = true;
    built.table.columns.push({ header: cs.title });
    built.table.rows.forEach((r, i) => r.push(rows[i][cs.field]));
  }
  const stems = { type: 'bar', name: '__stems', helper: true, barWidth: 1, silent: true, z: 1, tooltip: { show: false }, itemStyle: { color: toneColour(th, 'muted') },
    data: rows.filter((r) => num(r[xf]) !== null && num(r[yf]) !== null).map((r) => [num(r[xf]), num(r[yf])]) };
  built.option.series = [stems].concat(built.option.series);
  built.summary = 'Lollipop chart' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' marks along an axis' + (spec.length ? ' of ' + spec.length : '') + (bands.length ? ', ' + bands.length + ' bands' : '');
  return built;
}

/**
 * Whether three dimensions can be drawn here: ECharts GL loaded and WebGL on.
 * @param {Object} [root] the window
 * @returns {boolean}
 */
export function canDraw3d(root) {
  const w = root || (typeof window !== 'undefined' ? window : null);
  if (!w || !w.echarts || !w.echarts.graphicGL || !w.document) { return false; }
  if (w.__drishtiWebGL === undefined) {
    try { const c = w.document.createElement('canvas'); w.__drishtiWebGL = !!(c.getContext('webgl') || c.getContext('experimental-webgl')); } catch (e) { w.__drishtiWebGL = false; }
  }
  return w.__drishtiWebGL;
}

let gl3d = null;
/**
 * Loads the vendored ECharts GL once (vendor/echarts-gl/); resolves either way (a trace3d is drawn flat without it).
 * @returns {Promise<boolean>} whether three dimensions can be drawn
 */
export function ensure3d() {
  if (typeof window === 'undefined') { return Promise.resolve(false); }
  if (window.echarts && window.echarts.graphicGL) { return Promise.resolve(canDraw3d(window)); }
  gl3d = gl3d || loadScript(staticUrl('vendor/echarts-gl/echarts-gl.min.js')).then(() => canDraw3d(window), () => false);
  return gl3d;
}

/**
 * A 3D trace: points joined in order, coloured by a figure; flat (x against y) where three dimensions cannot be drawn.
 * @param {Object} spec x, y, z, order (the field the points are joined by), colour, id, turn, fallback ('flat', the default: x against y;
 *   'table': the data table shown where three dimensions cannot be drawn), selected (ids drawn larger; the card's
 *   selection does it too), gl (true or false forces the drawing; default canDraw3d)
 * @param {Object[]} rows
 * @param {Object} env {theme, fmt, still: reduced motion}
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildTrace3d(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x) || 'x', yf = fieldOf(spec.y) || 'y', zf = fieldOf(spec.z) || 'z', of = fieldOf(spec.order);
  const pts = rows.map((r, i) => ({ r: r, i: i })).filter((p) => num(p.r[xf]) !== null && num(p.r[yf]) !== null && num(p.r[zf]) !== null);
  if (of) { pts.sort((a, b) => (num(a.r[of]) || 0) - (num(b.r[of]) || 0)); }
  const cs = colourScale(spec, rows, th), base = toneColour(th, spec.tone || 'link'), sel = new Set((spec.selected || []).map(String));
  const colourOf = (p) => (cs ? cs.colour(p.r[cs.field]) || th.nodata : base);
  const gl = spec.gl !== undefined ? !!spec.gl : canDraw3d();
  const table = { columns: [{ header: of || 'point' }, { header: xf }, { header: yf }, { header: zf }].concat(cs ? [{ header: cs.title }] : []),
    rows: pts.map((p) => [of ? p.r[of] : p.i, p.r[xf], p.r[yf], p.r[zf]].concat(cs ? [p.r[cs.field]] : [])) };
  const legend = cs ? cs.legendKeys : [];
  const name = (spec.colour && spec.colour.title) || (cs && cs.field) || 'points';
  const item = (p, v) => { const id = rowId(spec, p.r, p.i), on = sel.has(id); return { value: v, r: [p.i], ids: [id], name: of ? String(p.r[of]) : id, symbolSize: on ? th.size.symbol + 8 : th.size.symbol + 1,
    itemStyle: { color: on ? th.tokens.accent : colourOf(p), borderColor: on ? th.ink : undefined, borderWidth: on ? 1 : 0 } }; };
  let option;
  if (gl) {
    const ax = { type: 'value', show: false, axisLine: { show: false }, splitLine: { show: false }, axisLabel: { show: false } };
    option = { textStyle: textStyle(th), tooltip: { trigger: 'item', confine: true }, xAxis3D: Object.assign({}, ax), yAxis3D: Object.assign({}, ax), zAxis3D: Object.assign({}, ax),
      grid3D: { show: false, boxWidth: 100, boxHeight: 100, boxDepth: 100, environment: 'none', light: { main: { intensity: 1.2 }, ambient: { intensity: 0.4 } },
        viewControl: { autoRotate: !!spec.turn && !env.still, autoRotateSpeed: 6, distance: 220, rotateSensitivity: 1.2, zoomSensitivity: 0.6, animation: !env.still } },
      series: [{ type: 'line3D', name: '__chain', helper: true, silent: true, data: pts.map((p) => [num(p.r[xf]), num(p.r[yf]), num(p.r[zf])]), lineStyle: { width: 2, color: alpha(th.faint, 0.8) } },
        { type: 'scatter3D', name: name, field: cs ? cs.field : null, data: pts.map((p) => item(p, [num(p.r[xf]), num(p.r[yf]), num(p.r[zf])])) }] };
  } else {
    option = { textStyle: textStyle(th), tooltip: { trigger: 'item', confine: true }, grid: { left: 8, right: 8, top: 8, bottom: 8, containLabel: false },
      xAxis: { type: 'value', show: false, scale: true }, yAxis: { type: 'value', show: false, scale: true },
      series: [{ type: 'line', name: '__chain', helper: true, silent: true, symbol: 'none', data: pts.map((p) => [num(p.r[xf]), num(p.r[yf])]), lineStyle: { width: 1.5, color: alpha(th.faint, 0.8) } },
        { type: 'scatter', name: name, field: cs ? cs.field : null, data: pts.map((p) => item(p, [num(p.r[xf]), num(p.r[yf])])) }] };
  }
  return { option: option, table: table, legend: legend, legendStatic: true, flat: !gl, preferTable: !gl && spec.fallback === 'table',
    summary: (gl ? '3D trace' : 'Trace, drawn flat (x against y: three dimensions are not available here)') + (spec.title ? ' of ' + spec.title : '') + ', ' + pts.length + ' points' + (sel.size ? ', ' + sel.size + ' selected' : '') };
}

/**
 * Segmented scatter: one long axis of named segments, threshold lines, the points past the first threshold in its tone, top labels.
 * @param {Object} spec x, y, segments ([{name, from, to}] or {values, tones}), thresholds ([{value, label, tone}]: the first colours the
 *   points at or above it), labelTop ({field, n})
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildSegmented(spec, rows, env) {
  const segs = Array.isArray(spec.segments) ? { values: spec.segments } : spec.segments, th = spec.thresholds || [], yf = fieldOf(spec.y) || 'y';
  const first = th.length ? num(th[0].value) : null;
  const s = Object.assign({}, spec, { kind: 'scatter', segments: segs,
    colourWhen: (spec.colourWhen || []).concat(first !== null && !/[^\w]/.test(yf) ? [{ when: yf + ' >= ' + first, tone: th[0].tone || 'bad' }] : []),
    annotations: (spec.annotations || []).concat(th.map((t) => ({ type: 'line', axis: 'y', value: num(t.value), label: t.label, tone: t.tone || 'muted', showValue: !t.label }))) });
  const b = buildScatter(s, rows, env);
  b.legend = th.filter((t) => t.label).map((t) => ({ name: t.label, colour: toneColour(env.theme, t.tone || 'muted'), style: 'dash' }));
  b.legendStatic = true;
  b.summary = 'Segmented scatter' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' points over ' + ((segs && segs.values) || []).length + ' segments'
    + (first !== null ? ', ' + rows.filter((r) => num(r[yf]) >= first).length + ' at or past ' + (th[0].label || first) : '');
  return b;
}

/**
 * Matrix: category cells in the given order of rows and columns, every column kept, tracks, the overview strip past 150 columns.
 * @param {Object} spec x ({field, domain, labels}), y ({field, domain}), value ({field, tones, order, title}), tracks, zoom
 * @param {Object[]} rows the non-empty cells
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildMatrix(spec, rows, env) {
  const cols = spec.x && Array.isArray(spec.x.domain) ? spec.x.domain.length : 0;
  const s = Object.assign({ gap: 0, trackSize: 9 }, spec, { kind: 'heatmap', value: Object.assign({ type: 'category' }, spec.value),
    x: Object.assign({ labels: cols <= 60 }, spec.x), zoom: spec.zoom !== undefined ? spec.zoom : cols > 150 || undefined,
    tracks: (spec.tracks || []).map((t) => Object.assign({ axis: 'x', type: 'category' }, t)) });
  const b = buildHeatmap(s, rows, env);
  b.summary = 'Matrix' + (spec.title ? ' of ' + spec.title : '') + ': ' + ((spec.y && spec.y.domain) || []).length + ' rows by ' + cols + ' columns, ' + rows.length + ' cells filled';
  return b;
}

/**
 * Registers lollipop, trace3d, segmented and matrix.
 * @returns {void}
 */
export function registerSequenceKinds() {
  registerKind('lollipop', { build: buildLollipop, label: 'lollipop', cartesian: true });
  registerKind('trace3d', { build: buildTrace3d, label: '3D trace', cartesian: false, prepare: ensure3d });
  registerKind('segmented', { build: buildSegmented, label: 'segmented scatter', cartesian: true });
  registerKind('matrix', { build: buildMatrix, label: 'category matrix', cartesian: false });
}
