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
/* Vega-Lite into drishti-chart's spec (the product owner's decision O1: Vega is retired, one drawing engine). A BI visual of
   type: vega-lite and a Python visual's output: vega-lite are translated here and drawn by drishti-chart, in the theme's colours.

   What translates (what Drishti's Vega-Lite visuals use, and the common subset around it):
     marks       bar, line, area, point, circle, square, tick, rule, rect, arc, boxplot (a string or {type, ...}; innerRadius makes a donut)
     encodings   x, y (quantitative, nominal, ordinal, temporal; field, title, sort, scale {type: log, zero, domain}, axis {title, format},
                 aggregate sum/mean/average/count/min/max/median, bin with maxbins, stack: null/false/normalize), color (a field: one series
                 per member; a value; a condition {test, value} with a default value: the colour of the marks the test picks), size,
                 theta, tooltip (Drishti's tooltip shows the fields anyway)
     data        values inline (a url is refused, as before), inherited by layers
     layer       layers over the same axes; a rule at a constant (datum) becomes an annotation line
     transform   filter (an expression or a field predicate: equal, oneOf, range, lt, lte, gt, gte), calculate
     title       text, or {text, subtitle}
   Everything else (facets, row and column, repeat, concat, selections and params, other transforms, text marks, scales' schemes,
   projections and geoshape) is refused, never drawn wrong: the answer has no spec, its unsupported paths, and refused, a sentence that
   names each feature with what to use instead (vegaLiteAlternative).
   Colours become tones (a red is "bad", a green "good", an orange "accent", a grey "muted", the rest "link"). d3 formats become
   drishti-core's (",.0f" is amount0, ".2%" pct2, "~s" compact). */
import { compileCondition, rowsOf, num } from './drishti_chart-data.js';
import { chartIn } from './drishti_chart-element.js';

const AGG = { sum: (a) => a.reduce((s, x) => s + x, 0), mean: (a) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : null), count: (a) => a.length,
  min: (a) => (a.length ? Math.min.apply(null, a) : null), max: (a) => (a.length ? Math.max.apply(null, a) : null),
  median: (a) => { if (!a.length) { return null; } const s = a.slice().sort((x, y) => x - y), m = Math.floor(s.length / 2); return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; } };
AGG.average = AGG.mean;

const KNOWN_TOP = ['$schema', 'data', 'mark', 'encoding', 'layer', 'transform', 'title', 'width', 'height', 'autosize', 'config', 'description', 'name', 'background', 'padding'];
const KNOWN_ENC = ['x', 'y', 'color', 'fill', 'stroke', 'size', 'theta', 'tooltip', 'opacity', 'x2', 'y2'];
const MARKS = { bar: 1, line: 1, area: 1, point: 1, circle: 1, square: 1, tick: 1, rule: 1, rect: 1, arc: 1, boxplot: 1 };

/** What to use instead of each Vega-Lite feature drishti-chart does not draw, by the feature's key in its path. */
const INSTEAD = [
  [/^(facet|row|column)$/, 'small multiples: a BI chart\'s split well, or one visual per member'],
  [/^(repeat|concat|hconcat|vconcat|spec)$/, 'one visual per chart on the report page'],
  [/^(params|selection)$/, 'the report\'s own cross-filtering: a click on a mark filters the page'],
  [/^(projection|geoshape)$/, 'drishti-chart\'s map kind (kind: map), or the BI map visual'],
  [/^resolve$/, 'one axis per chart: put a second scale in its own visual, or a combo chart\'s secondary axis'],
  [/^transform$/, 'a calculated column or measure in the model, or compute the rows in Python'],
  [/^(text|shape|strokeDash|href|url|key|order|detail|angle|radius|description|latitude|longitude|xOffset|yOffset)$/, 'a table or card visual for text, a series (color field) for detail; or return a drishti-chart spec from Python'],
  [/^mark$/, 'a BI visual of that kind (table, card, map) or a mark drishti-chart draws: bar, line, area, point, circle, square, tick, rule, rect, arc, boxplot']
];

/**
 * What to use instead of an unsupported Vega-Lite feature, from its path in the specification.
 * @param {string} path a path fromVegaLite listed ("$.facet", "$.layer[1].encoding.text", "$.transform[0]", "$.mark (text)")
 * @returns {string} the alternative, in words
 */
export function vegaLiteAlternative(path) {
  const parts = String(path || '').replace(/\s.*$/, '').split('.').map((p) => p.replace(/\[\d+\]$/, '')).filter((p) => p && p !== '$');
  for (let i = parts.length - 1; i >= 0; i--) {
    for (const [re, text] of INSTEAD) { if (re.test(parts[i])) { return text; } }
  }
  return 'return a drishti-chart spec from Python (output: drishti-chart), which draws every kind the library has';
}

/**
 * The sentence a refused specification shows: each unsupported feature with what to use instead.
 * @param {string[]} unsupported the paths fromVegaLite listed
 * @returns {string}
 */
export function vegaLiteRefusal(unsupported) {
  const seen = Array.from(new Set(unsupported || []));
  return 'Not drawn: this Vega-Lite specification uses what drishti-chart does not draw, and Drishti never draws a chart wrong. '
    + seen.map((p) => p + ': use ' + vegaLiteAlternative(p)).join('; ') + '.';
}

/**
 * A colour as the tone people meant: red is bad, green good, orange or amber accent, grey muted, anything else link.
 * @param {string} c a CSS colour (hex or a name)
 * @returns {string}
 */
export function colourTone(c) {
  const named = { red: 'bad', crimson: 'bad', firebrick: 'bad', green: 'good', seagreen: 'good', orange: 'accent', gold: 'accent', grey: 'muted', gray: 'muted', black: 'ink', steelblue: 'link', blue: 'link' };
  const s = String(c || '').trim().toLowerCase();
  if (named[s]) { return named[s]; }
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(s);
  if (!m) { return 'link'; }
  const hx = m[1].length === 3 ? m[1].replace(/./g, '$&$&') : m[1];
  const r = parseInt(hx.slice(0, 2), 16) / 255, g = parseInt(hx.slice(2, 4), 16) / 255, b = parseInt(hx.slice(4, 6), 16) / 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
  if (mx - mn < 0.12) { return 'muted'; }
  let h = mx === r ? ((g - b) / (mx - mn)) % 6 : mx === g ? (b - r) / (mx - mn) + 2 : (r - g) / (mx - mn) + 4;
  h = (h * 60 + 360) % 360;
  if (h < 18 || h >= 335) { return 'bad'; }
  if (h < 55) { return 'accent'; }
  if (h < 170) { return 'good'; }
  return 'link';
}

/**
 * A d3 number format as drishti-core's named format (or a format object): ",.0f" amount0, ",.2f" amount2, ".0%" pct0, ".2%" pct2,
 * "~s" and ".2s" compact, "$,.0f" amount0; null when there is no near match.
 * @param {string} f
 * @returns {*}
 */
export function d3Format(f) {
  if (!f || typeof f !== 'string') { return null; }
  const m = /^\$?(,)?\.?(\d+)?([fdes%])$/.exec(f.replace('~', ''));
  if (/s$/.test(f)) { return 'compact'; }
  if (!m) { return null; }
  const d = m[2] === undefined ? 0 : Number(m[2]);
  if (m[3] === '%') { return d === 0 ? 'pct0' : d === 2 ? 'pct2' : { style: 'percent', decimals: d }; }
  if (m[3] === 'f' || m[3] === 'd') { return d === 0 ? 'amount0' : d === 2 ? 'amount2' : { decimals: d, grouping: !!m[1] }; }
  return null;
}

function typeOf(enc) {
  const t = enc && enc.type;
  if (t === 'quantitative') { return 'value'; }
  if (t === 'temporal') { return 'time'; }
  if (t === 'nominal' || t === 'ordinal') { return 'category'; }
  return enc && (enc.aggregate || enc.bin) ? 'value' : 'category';
}

function axisSpec(enc) {
  if (!enc) { return undefined; }
  const out = { field: enc.field || (enc.aggregate === 'count' ? '__count' : undefined), type: typeOf(enc) };
  const title = enc.title !== undefined ? enc.title : enc.axis && enc.axis.title;
  if (title !== undefined && title !== null) { out.title = String(title); }
  const fmt = d3Format(enc.format || (enc.axis && enc.axis.format));
  if (fmt) { out.format = fmt; }
  if (enc.scale) {
    if (enc.scale.type === 'log') { out.scale = 'log'; }
    if (enc.scale.zero === false) { out.zero = false; }
    if (Array.isArray(enc.scale.domain) && enc.scale.domain.length === 2 && typeof enc.scale.domain[0] === 'number') { out.min = enc.scale.domain[0]; out.max = enc.scale.domain[1]; }
  }
  if (Array.isArray(enc.sort)) { out.order = enc.sort.map(String); }
  return out;
}

/** @private rows filtered and calculated by the transforms Drishti translates; the others are listed */
function transform(rows, list, path, unsupported) {
  let out = rows.slice();
  (list || []).forEach((t, i) => {
    if (t.filter !== undefined) {
      const f = t.filter;
      if (typeof f === 'string') { const test = compileCondition(f); out = out.filter((r) => test(r)); return; }
      if (f && f.field) {
        const v = (r) => r[f.field];
        if (f.equal !== undefined) { out = out.filter((r) => v(r) === f.equal); } else if (f.oneOf) { out = out.filter((r) => f.oneOf.indexOf(v(r)) >= 0); } else if (f.range) { out = out.filter((r) => num(v(r)) !== null && v(r) >= f.range[0] && v(r) <= f.range[1]); } else if (f.lt !== undefined) { out = out.filter((r) => v(r) < f.lt); } else if (f.lte !== undefined) { out = out.filter((r) => v(r) <= f.lte); } else if (f.gt !== undefined) { out = out.filter((r) => v(r) > f.gt); } else if (f.gte !== undefined) { out = out.filter((r) => v(r) >= f.gte); } else { unsupported.push(path + '.transform[' + i + '].filter'); }
        return;
      }
      unsupported.push(path + '.transform[' + i + '].filter');
      return;
    }
    if (t.calculate && t.as) { const fn = compileCondition(t.calculate); out = out.map((r) => Object.assign({}, r, { [t.as]: fn(r) })); return; }
    unsupported.push(path + '.transform[' + i + ']');
  });
  return out;
}

/** @private rows grouped by the encodings without an aggregate, the aggregated ones computed */
function aggregate(rows, enc) {
  const aggs = Object.keys(enc).filter((k) => enc[k] && enc[k].aggregate && ['x', 'y', 'theta', 'size', 'color'].indexOf(k) >= 0);
  if (!aggs.length) { return rows; }
  const keys = Object.keys(enc).filter((k) => enc[k] && enc[k].field && !enc[k].aggregate && ['x', 'y', 'color', 'theta'].indexOf(k) >= 0).map((k) => enc[k].field);
  const groups = new Map();
  rows.forEach((r) => { const k = JSON.stringify(keys.map((f) => r[f])); if (!groups.has(k)) { groups.set(k, []); } groups.get(k).push(r); });
  return Array.from(groups.values()).map((g) => {
    const o = {};
    keys.forEach((f) => { o[f] = g[0][f]; });
    aggs.forEach((k) => {
      const e = enc[k], fn = AGG[e.aggregate] || AGG.sum, name = e.field || '__count';
      o[name] = e.aggregate === 'count' ? g.length : fn(g.map((r) => num(r[e.field])).filter((v) => v !== null));
    });
    return o;
  });
}

/** @private one layer (or a single view) into a drishti-chart spec */
function view(v, rows, path, unsupported) {
  const mark = typeof v.mark === 'string' ? { type: v.mark } : Object.assign({}, v.mark);
  const enc = Object.assign({}, v.encoding);
  Object.keys(v).forEach((k) => { if (KNOWN_TOP.indexOf(k) < 0) { unsupported.push(path + '.' + k); } });
  Object.keys(enc).forEach((k) => { if (KNOWN_ENC.indexOf(k) < 0) { unsupported.push(path + '.encoding.' + k); } });
  if (!MARKS[mark.type]) { unsupported.push(path + '.mark' + (mark.type ? ' (' + mark.type + ')' : '')); return null; }
  rows = transform(rows, v.transform, path, unsupported);
  const colour = enc.color || enc.fill || enc.stroke;
  const s = { data: { rows: null } };
  if (colour && colour.condition && colour.condition.test) {
    s.colourWhen = [{ when: colour.condition.test, tone: colourTone(colour.condition.value) }];
    if (colour.value) { s.tone = colourTone(colour.value); }
  } else if (colour && colour.value) { s.tone = colourTone(colour.value); } else if (mark.color) { s.tone = colourTone(mark.color); }
  const x = enc.x, y = enc.y;
  if (mark.type === 'rule') {
    const a = [];
    if (x && x.datum !== undefined) { a.push({ type: 'line', axis: 'x', value: x.datum, tone: s.tone || 'muted' }); }
    if (y && y.datum !== undefined) { a.push({ type: 'line', axis: 'y', value: y.datum, tone: s.tone || 'muted' }); }
    if (!a.length && rows.length && rows.length <= 20) {
      const e = x && !x.datum ? x : y, axis = e === x ? 'x' : 'y';
      if (e && e.field) { rows.forEach((r) => a.push({ type: 'line', axis: axis, value: r[e.field], tone: s.tone || 'muted', showValue: false })); }
    }
    if (!a.length) { unsupported.push(path + '.mark (a rule without a constant)'); }
    return { rule: a };
  }
  if (x && x.bin && (!y || y.aggregate === 'count')) {
    return Object.assign(s, { kind: 'histogram', value: axisSpec(x), bins: (x.bin && x.bin.maxbins) || 'auto', data: { rows: rows } });
  }
  rows = aggregate(rows, enc);
  s.data.rows = rows;
  if (mark.type === 'arc') {
    return Object.assign(s, { kind: mark.innerRadius ? 'donut' : 'pie', category: colour && colour.field ? { field: colour.field } : undefined, value: axisSpec(enc.theta) });
  }
  if (mark.type === 'rect') {
    return Object.assign(s, { kind: 'heatmap', x: axisSpec(x), y: axisSpec(y), value: colour && colour.field ? { field: colour.field, format: d3Format(colour.format) || undefined } : undefined });
  }
  const xt = typeOf(x), yt = typeOf(y);
  if (mark.type === 'boxplot') {
    const cat = xt === 'category' ? x : y, val = xt === 'category' ? y : x;
    return Object.assign(s, { kind: 'boxplot', category: axisSpec(cat), value: axisSpec(val) });
  }
  const horizontal = xt === 'value' && yt === 'category';
  if (['point', 'circle', 'square', 'tick'].indexOf(mark.type) >= 0) {
    Object.assign(s, { kind: enc.size && enc.size.field ? 'bubble' : 'scatter', x: axisSpec(x), y: axisSpec(y) });
    if (enc.size && enc.size.field) { s.size = { field: enc.size.field }; }
    if (colour && colour.field) { s.colour = { field: colour.field }; }
    ['x', 'y'].forEach((k) => {
      const e = enc[k];
      if (e && e.field && typeOf(e) === 'category' && e.sort !== null && !Array.isArray(e.sort)) { const ms = Array.from(new Set(rows.map((r) => String(r[e.field])))).sort(); s[k].order = e.sort === 'descending' ? ms.reverse() : ms; }
    });
    s.symbol = mark.type === 'square' ? 'rect' : mark.type === 'tick' ? (horizontal ? 'tick-v' : 'tick-h') : 'circle';
    if (mark.opacity !== undefined) { s.opacity = mark.opacity; }
    return s;
  }
  const kind = mark.type === 'bar' ? 'bar' : mark.type === 'area' ? 'area' : 'line';
  const cat = horizontal ? y : x, val = horizontal ? x : y;
  Object.assign(s, { kind: kind, x: axisSpec(cat), y: axisSpec(val) });
  if (horizontal) { s.orient = 'horizontal'; }
  if (colour && colour.field) {
    s.series = { by: colour.field };
    const st = val && val.stack;
    if (st === 'normalize') { s.stack = 'percent'; } else if (st !== null && st !== false && (kind === 'bar' || kind === 'area')) { s.stack = true; }
  } else if (val && val.stack === 'normalize') { s.stack = 'percent'; }
  const sort = cat && cat.sort;
  if (sort === '-y' || sort === '-x') { s.sort = 'value desc'; } else if (sort === 'y' || sort === 'x') { s.sort = 'value asc'; } else if (cat && cat.field && typeOf(cat) === 'category' && sort !== null && !Array.isArray(sort)) {
    const ms = Array.from(new Set(rows.map((r) => String(r[cat.field])))).sort();          // Vega-Lite sorts categories ascending unless told
    s.x.order = sort === 'descending' ? ms.reverse() : ms;
  }
  if (mark.point) { s.markers = true; }
  if (mark.interpolate === 'monotone' || mark.interpolate === 'basis') { s.smooth = true; }
  if (/^step/.test(mark.interpolate || '')) { s.step = mark.interpolate === 'step-before' ? 'start' : mark.interpolate === 'step-after' ? 'end' : 'middle'; }
  return s;
}

/**
 * A Vega-Lite specification as a drishti-chart spec, with every part that did not translate.
 * @param {Object} vl the Vega-Lite specification (data inline; a url is refused)
 * @param {{rows: Object[], title: string}} [o] rows: the data when the specification has none (a BI visual's own rows)
 * @returns {{spec: ?Object, unsupported: string[], refused: ?string}} refused: why nothing is drawn (a url, nothing to draw, or the
 *   features that do not translate, each with what to use instead); a spec is only given when the whole specification translated
 */
export function fromVegaLite(vl, o) {
  o = o || {};
  const unsupported = [];
  if (!vl || typeof vl !== 'object') { return { spec: null, unsupported: [], refused: 'a Vega-Lite specification is an object' }; }
  const bad = urlIn(vl, '$');
  if (bad) { return { spec: null, unsupported: [], refused: 'a Vega-Lite specification may not load anything from a URL or name a loader (' + bad + ')' }; }
  ['facet', 'repeat', 'concat', 'hconcat', 'vconcat', 'params', 'selection', 'projection', 'resolve', 'spec'].forEach((k) => { if (vl[k] !== undefined) { unsupported.push('$.' + k); } });
  const rowsFrom = (v, inherited) => (v.data && v.data.values ? rowsOf(v.data.values) : inherited);
  const top = rowsFrom(vl, o.rows || []);
  const title = typeof vl.title === 'string' ? vl.title : vl.title && vl.title.text ? String(vl.title.text) : o.title;
  const subtitle = vl.title && vl.title.subtitle ? String(vl.title.subtitle) : undefined;
  let spec;
  if (Array.isArray(vl.layer)) {
    const parentEnc = vl.encoding || {};
    const layers = [], rules = [];
    vl.layer.forEach((l, i) => {
      const merged = Object.assign({}, l, { encoding: Object.assign({}, parentEnc, l.encoding), transform: (vl.transform || []).concat(l.transform || []) });
      ['data'].forEach((k) => { delete merged[k]; });
      const got = view(merged, rowsFrom(l, top), '$.layer[' + i + ']', unsupported);
      if (!got) { return; }
      if (got.rule) { rules.push.apply(rules, got.rule); return; }
      layers.push(got);
    });
    if (!layers.length) { return { spec: null, unsupported: Array.from(new Set(unsupported)), refused: unsupported.length ? vegaLiteRefusal(unsupported) : 'no layer this library draws' }; }
    spec = layers.length === 1 ? layers[0] : { kind: 'layer', layers: layers };
    if (rules.length) { (spec.layers ? spec.layers[0] : spec).annotations = rules; }
  } else {
    if (!vl.mark) { return { spec: null, unsupported: unsupported, refused: 'a Vega-Lite specification needs a mark or layers' }; }
    spec = view(vl, top, '$', unsupported);
    if (!spec) { return { spec: null, unsupported: Array.from(new Set(unsupported)), refused: vegaLiteRefusal(unsupported.length ? unsupported : ['$.mark']) }; }
    if (spec.rule) { return { spec: null, unsupported: unsupported, refused: 'a lone rule has nothing to sit on' }; }
  }
  if (unsupported.length) { return { spec: null, unsupported: Array.from(new Set(unsupported)), refused: vegaLiteRefusal(unsupported) }; }
  if (title) { spec.title = title; }
  if (subtitle) { spec.subtitle = subtitle; }
  spec.from = 'vega-lite';
  return { spec: spec, unsupported: [], refused: null };
}

/** @private the path of the first url or loader that is a plain value, as the page and the server refuse it */
function urlIn(node, path) {
  if (Array.isArray(node)) { for (let i = 0; i < node.length; i++) { const f = urlIn(node[i], path + '[' + i + ']'); if (f) { return f; } } return null; }
  if (node && typeof node === 'object') {
    for (const k of Object.keys(node)) {
      if ((k === 'url' || k === 'loader') && (typeof node[k] !== 'object' || node[k] === null)) { return path + '.' + k; }
      const f = urlIn(node[k], path + '.' + k);
      if (f) { return f; }
    }
  }
  return null;
}

/**
 * A Vega-Lite specification drawn in a host by drishti-chart, or refused there in words (each unsupported feature with what to use
 * instead): never drawn wrong. The refusal is a status paragraph (class dc-refused, data-refused holding the unsupported paths).
 * @param {HTMLElement} host
 * @param {Object} vl the Vega-Lite specification
 * @param {Object} [o] rows (the data when the specification has none), title, and chartIn's options
 * @returns {{chart: ?Object, refused: ?string, unsupported: string[], spec: ?Object}}
 */
export function vegaLiteIn(host, vl, o) {
  o = Object.assign({}, o);
  const t = fromVegaLite(vl, { rows: o.rows, title: o.title });
  delete o.rows; delete o.title;
  if (!t.spec) {
    const doc = host.ownerDocument, p = doc.createElement('p');
    p.className = 'dc-refused';
    p.setAttribute('role', 'status');
    p.setAttribute('data-refused', t.unsupported.join(' '));
    p.textContent = t.refused;
    host.textContent = '';
    host.appendChild(p);
    return { chart: null, refused: t.refused, unsupported: t.unsupported, spec: null };
  }
  return { chart: chartIn(host, t.spec, o), refused: null, unsupported: [], spec: t.spec };
}

