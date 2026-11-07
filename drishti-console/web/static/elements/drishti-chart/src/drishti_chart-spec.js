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
/* drishti-chart's spec: one engine-neutral description of a chart (what a page, a Sutra panel, a BI visual and the AI all write;
   nothing in it names ECharts), its checks, and the registry of kinds. A kind is a module that turns the spec and its rows into the
   drawing engine's option, the data table (the accessible alternative and the CSV), the legend and a one-line description; a domain
   library registers its own (drishti-risk: risk.pnl-distribution, risk.backtest, risk.exposure-profile) and they appear in the studio
   and in Sutras like the general ones.

     { "kind": "line", "title": "Daily P&L", "data": { "rows": [...] } or { "source": "/api/..." },
       "x": { "field": "date", "type": "time" }, "y": { "format": "money" },
       "series": [{ "field": "hypothetical" }, { "field": "var99", "negate": true, "style": "dashed", "tone": "bad" }],
       "marks": [{ "when": "hypothetical < -var99", "as": "point", "tone": "bad" }],
       "annotations": [{ "type": "line", "axis": "y", "value": 0 }, { "type": "band", "axis": "x", "from": "2026-03-02", "to": "2026-03-20" }],
       "legend": "bottom", "zoom": { "window": [150, 250] }, "onClick": "explain", "export": ["png", "svg", "csv", "spec"], "a11y": { "table": true } } */
import { compileCondition } from './drishti_chart-data.js';

const registry = new Map();

/**
 * A kind: how a spec of that kind is drawn.
 * @typedef {Object} KindDef
 * @property {function(Object, Object[], Object): BuiltChart} build the spec (normalised), its rows and the environment ({theme, fmt}) to
 *   what the engine draws
 * @property {string} [label] the kind's name in the studio's rail
 * @property {('general'|'risk'|'map'|string)} [family='general'] the rail's group (a domain library's name for its kinds)
 * @property {boolean} [cartesian] x and y axes (zoom and the crosshair apply)
 * @property {function(): Object} [sample] a small spec with data, for the studio and the gallery
 */

/**
 * What a kind builds.
 * @typedef {Object} BuiltChart
 * @property {Object} option the drawing engine's option (ECharts)
 * @property {{columns: Array<{header: string, format: *}>, rows: Array<Array<*>>}} table the data table and the CSV
 * @property {Array<{name: string, colour: string, style: string}>} legend the entries (style: line, dash, box, dot)
 * @property {string} summary one line a screen reader reads ("Line chart, 3 series, 250 points, from 2025-11-11 to 2026-11-06")
 * @property {Object} [badge] a status the card shows beside its title ({tone, text}: the backtest's zone)
 */

/**
 * Registers a kind (a domain library's too). A name already taken is refused unless replace is said.
 * @param {string} name
 * @param {KindDef} def
 * @param {{replace: boolean}} [o]
 * @returns {void}
 */
export function registerKind(name, def, o) {
  if (!name || typeof def !== 'object' || typeof def.build !== 'function') { throw new Error('registerKind: a kind needs a name and a build function'); }
  if (registry.has(name) && !(o && o.replace)) { throw new Error('the kind ' + name + ' is registered already'); }
  registry.set(name, Object.assign({ label: name, family: 'general', cartesian: false }, def));
}

/**
 * A registered kind, or null.
 * @param {string} name
 * @returns {?KindDef}
 */
export function kindOf(name) { return registry.get(name) || null; }

/**
 * The registered kinds in the order they were registered: name, label and family.
 * @returns {Array<{name: string, label: string, family: string, cartesian: boolean}>}
 */
export function kindList() { return Array.from(registry.entries()).map(([n, d]) => ({ name: n, label: d.label, family: d.family, cartesian: !!d.cartesian })); }

/** The aliases a spec may use for a kind. */
export const KIND_ALIASES = Object.freeze({ column: 'bar', columns: 'bar', bars: 'bar', lines: 'line', point: 'scatter', points: 'scatter', doughnut: 'donut', box: 'boxplot',
  'box-plot': 'boxplot', hist: 'histogram', choropleth: 'map', geo: 'map', candles: 'candlestick' });

const LEGENDS = ['bottom', 'top', 'right', 'none'];
const ON_CLICK = ['explain', 'select', 'none'];
const EXPORTS = ['png', 'svg', 'csv', 'spec'];

/**
 * A spec's problems, each with its path: an unknown kind, a missing field, a condition that does not read, a value of the wrong kind.
 * @param {Object} spec
 * @returns {Array<{path: string, message: string}>}
 */
export function checkSpec(spec) {
  const out = [];
  const bad = (path, message) => out.push({ path: path, message: message });
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) { bad('$', 'a spec is an object'); return out; }
  const kind = KIND_ALIASES[spec.kind] || spec.kind;
  if (!kind) { bad('$.kind', 'every spec names its kind'); } else if (!registry.has(kind)) { bad('$.kind', 'no kind ' + spec.kind + ' (there are ' + kindList().map((k) => k.name).join(', ') + ')'); }
  if (spec.data !== undefined && spec.data !== null && typeof spec.data !== 'object') { bad('$.data', 'data is rows, {rows}, {values}, {columns, rows} or {source}'); }
  if (spec.data && spec.data.source !== undefined && typeof spec.data.source !== 'string') { bad('$.data.source', 'a source is the address of a server answer'); }
  if (spec.legend !== undefined && typeof spec.legend === 'string' && LEGENDS.indexOf(spec.legend) < 0) { bad('$.legend', 'the legend is one of ' + LEGENDS.join(', ')); }
  if (spec.onClick !== undefined && ON_CLICK.indexOf(spec.onClick) < 0) { bad('$.onClick', 'onClick is one of ' + ON_CLICK.join(', ')); }
  (Array.isArray(spec.export) ? spec.export : []).forEach((e, i) => { if (EXPORTS.indexOf(e) < 0) { bad('$.export[' + i + ']', 'an export is one of ' + EXPORTS.join(', ')); } });
  if (spec.series !== undefined && !Array.isArray(spec.series) && typeof spec.series !== 'string' && !(spec.series && spec.series.by)) {
    bad('$.series', 'series is a list of {field}, or {by: field} (one series per member of a field)');
  }
  (Array.isArray(spec.series) ? spec.series : []).forEach((s, i) => { if (!s || (!s.field && !s.value)) { bad('$.series[' + i + ']', 'a series names its field'); } });
  (spec.annotations || []).forEach((a, i) => {
    if (['line', 'band', 'point', 'text'].indexOf(a && a.type) < 0) { bad('$.annotations[' + i + '].type', 'an annotation is a line, a band, a point or a text'); }
    if (a && a.type === 'band' && (a.from === undefined || a.to === undefined)) { bad('$.annotations[' + i + ']', 'a band has from and to'); }
  });
  (spec.marks || []).forEach((m, i) => {
    if (!m || !m.when) { bad('$.marks[' + i + '].when', 'a mark has a condition (when)'); return; }
    try { compileCondition(m.when); } catch (e) { bad('$.marks[' + i + '].when', e.message); }
  });
  if (spec.zoom && spec.zoom.window && (!Array.isArray(spec.zoom.window) || spec.zoom.window.length !== 2)) { bad('$.zoom.window', 'a zoom window is [start, end] (indexes or values)'); }
  return out;
}

/**
 * A spec ready to draw: the kind's alias resolved, x, y and value as objects, the defaults filled in (legend at the bottom, click to
 * Explain, every export, the data table on). The spec given is not changed.
 * @param {Object} spec
 * @returns {Object}
 * @throws {Error} the first problem of checkSpec, with its path
 */
export function normalizeSpec(spec) {
  const problems = checkSpec(spec);
  if (problems.length) { const e = new Error(problems[0].path + ': ' + problems[0].message); e.problems = problems; throw e; }
  const s = JSON.parse(JSON.stringify(spec, (k, v) => (typeof v === 'function' ? undefined : v)));
  ['format', 'tooltipFormat'].forEach((k) => { if (typeof spec[k] === 'function') { s[k] = spec[k]; } });
  s.kind = KIND_ALIASES[s.kind] || s.kind;
  ['x', 'y', 'value', 'size', 'colour', 'category'].forEach((k) => { if (typeof s[k] === 'string') { s[k] = { field: s[k] }; } });
  if (s.color && !s.colour) { s.colour = typeof s.color === 'string' ? { field: s.color } : s.color; }
  if (typeof s.series === 'string') { s.series = { by: s.series }; }
  s.legend = s.legend === undefined ? 'bottom' : s.legend === false ? 'none' : s.legend;
  s.onClick = s.onClick || 'explain';
  s.export = Array.isArray(s.export) ? s.export : EXPORTS.slice();
  s.a11y = Object.assign({ table: true }, s.a11y);
  s.annotations = s.annotations || [];
  s.marks = s.marks || [];
  if (s.data && typeof spec.data === 'object' && spec.data && !Array.isArray(spec.data) && typeof spec.data.fetch === 'function') { s.data.fetch = spec.data.fetch; }
  return s;
}

/**
 * The spec as people read it: two-space JSON with the keys in a stable order (kind, title, data first); inline rows shortened to their
 * count so the spec stays readable beside the chart.
 * @param {Object} spec
 * @param {{rows: boolean}} [o] rows: keep inline rows whole (copy spec does)
 * @returns {string}
 */
export function specText(spec, o) {
  const order = ['kind', 'title', 'subtitle', 'data', 'x', 'y', 'category', 'value', 'series', 'size', 'colour', 'stack', 'orient'];
  const keys = Object.keys(spec).sort((a, b) => (order.indexOf(a) < 0 ? 99 : order.indexOf(a)) - (order.indexOf(b) < 0 ? 99 : order.indexOf(b)));
  const copy = {};
  keys.forEach((k) => {
    if (typeof spec[k] === 'function') { return; }
    if (k === 'data' && spec.data && !(o && o.rows)) {
      const d = Object.assign({}, spec.data);
      ['rows', 'values'].forEach((r) => { if (Array.isArray(d[r]) && d[r].length > 3) { d[r] = '… ' + d[r].length + ' rows'; } });
      if (typeof d.fetch === 'function') { delete d.fetch; }
      copy[k] = d;
    } else { copy[k] = spec[k]; }
  });
  return JSON.stringify(copy, null, 2);
}
