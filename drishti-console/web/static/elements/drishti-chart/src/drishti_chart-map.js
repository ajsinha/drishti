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
/* The map kind's figures and drawing, without a page: each place's value from the rows (matched by ISO code or name), the choropleth's
   classes (quantile, with their counts) or its continuous ramp, no-data places hatched, bubbles at places or at points, the ranking,
   the table, and the ECharts option over the projected shapes of drishti_chart-geo.js (an identity projection: the shapes are projected
   already). The view (drishti_map-view.js) draws it with its legend, ranking, breadcrumb and drill.

     { "kind": "map", "level": "world", "data": { "rows": [...] }, "place": "code",
       "value": { "field": "ead", "title": "Exposure (EAD)", "format": "money" },
       "metrics": [{ "field": "ead", "title": "Exposure (EAD)", "format": "money" }, { "field": "cpty", "title": "Counterparties", "format": "integer" }],
       "scale": { "type": "quantile", "classes": 5 }, "bubbles": { "field": "trades" }, "drill": "regions" } */
import { registerKind } from './drishti_chart-spec.js';
import { fieldOf, num, quantileBreaks, classOf } from './drishti_chart-data.js';
import { rampColour, alpha, formatterFor, textStyle, blend } from './drishti_chart-theme.js';
import { findPlace, bounds, fitView } from './drishti_chart-geo.js';
import { rowId } from './drishti_chart-kinds.js';

/**
 * The metrics a map offers (its picker): the spec's metrics, or its value alone.
 * @param {Object} spec
 * @returns {Array<{field: string, title: string, format: *}>}
 */
export function mapMetrics(spec) {
  const list = (spec.metrics || []).map((m) => (typeof m === 'string' ? { field: m, title: m } : Object.assign({}, m)));
  const v = spec.value || {};
  if (!list.length && fieldOf(v)) { list.push({ field: fieldOf(v), title: v.title || fieldOf(v), format: v.format }); }
  return list;
}

/**
 * Each place's value on a level: the rows matched to places (by the place field: an ISO code or a name) and summed per place; places
 * without a figure keep null (no data); rows that match no place are counted.
 * @param {Object} spec
 * @param {Object[]} rows
 * @param {import('./drishti_chart-geo.js').RegionGeo} geo
 * @param {{field: string}} metric
 * @returns {{places: Array<{code: string, name: string, point: boolean, v: ?number, r: number[], ids: string[]}>, unmatched: number}}
 */
export function placeValues(spec, rows, geo, metric) {
  const pf = fieldOf(spec.place) || 'code', at = new Map();
  let unmatched = 0;
  rows.forEach((r, i) => {
    const p = findPlace(geo, r[pf]);
    if (!p) { unmatched++; return; }
    const e = at.get(p.code) || { v: null, r: [], ids: [] }, v = num(r[metric.field]);
    if (v !== null) { e.v = (e.v || 0) + v; }
    e.r.push(i); e.ids.push(rowId(spec, r, i));
    at.set(p.code, e);
  });
  const places = geo.list.map((p) => { const e = at.get(p.code) || { v: null, r: [], ids: [] }; return { code: p.code, name: p.name, point: p.point, v: e.v, r: e.r, ids: e.ids }; });
  return { places: places, unmatched: unmatched };
}

/**
 * The choropleth's scale: quantile classes (each with its range, colour and count of places, and the no-data count) or a continuous
 * ramp; colourOf gives a value's colour, classOf its class.
 * @param {Array<{v: ?number}>} places
 * @param {Object} th the chart theme
 * @param {{type: ('quantile'|'continuous'), classes: number}} [scale]
 * @returns {{type: string, classes: Array<{from: number, to: number, colour: string, count: number}>, nodata: number, min: ?number, max: ?number, colourOf: function(?number): ?string, classOf: function(?number): number}}
 */
export function mapScale(places, th, scale) {
  const type = scale && scale.type === 'continuous' ? 'continuous' : 'quantile', k = Math.max(2, Math.min(7, (scale && scale.classes) || 5));
  const vals = places.map((p) => p.v), q = quantileBreaks(vals, k), ramp = th.sequential;
  const colours = [];
  for (let i = 0; i < k; i++) { colours.push(rampColour(ramp, i, 0, k - 1)); }
  const nodata = places.filter((p) => p.v === null).length;
  if (type === 'continuous') {
    return { type: type, classes: [], nodata: nodata, min: q.min, max: q.max, classOf: (v) => (num(v) === null ? -1 : 0),
      colourOf: (v) => (num(v) === null ? null : rampColour(ramp, num(v), q.min, q.max)) };
  }
  const edges = [q.min].concat(q.breaks).concat([q.max]);
  const classes = colours.map((c, i) => ({ from: edges[i], to: edges[i + 1], colour: c, count: 0 }));
  places.forEach((p) => { const c = classOf(p.v, q.breaks); if (c >= 0) { classes[c].count++; } });
  return { type: type, classes: classes, nodata: nodata, min: q.min, max: q.max, classOf: (v) => classOf(v, q.breaks), colourOf: (v) => { const c = classOf(v, q.breaks); return c < 0 ? null : colours[c]; } };
}

/**
 * The top places by value (largest first), with their share of the level's total.
 * @param {Array<{code: string, name: string, v: ?number}>} places
 * @param {number} [n=10]
 * @returns {Array<{code: string, name: string, v: number, share: number, rank: number}>}
 */
export function ranking(places, n) {
  const withV = places.filter((p) => p.v !== null), tot = withV.reduce((s, p) => s + p.v, 0);
  return withV.slice().sort((a, b) => b.v - a.v).slice(0, n || 10).map((p, i) => ({ code: p.code, name: p.name, v: p.v, share: tot ? p.v / tot : 0, rank: i + 1 }));
}

/** @private the middle of a shape's largest ring (a bubble's place) */
function centreOf(shape) {
  const g = shape.geometry, polys = g.type === 'Polygon' ? [g.coordinates] : g.coordinates;
  let best = null, area = -1;
  polys.forEach((p) => { const b = bounds(p[0]), a = (b[2] - b[0]) * (b[3] - b[1]); if (a > area) { area = a; best = b; } });
  return best ? [(best[0] + best[2]) / 2, (best[1] + best[3]) / 2] : [0, 0];
}

/**
 * The map as the engine draws it, with what the view shows around it.
 * @param {Object} spec normalised
 * @param {Object[]} rows
 * @param {import('./drishti_chart-geo.js').RegionGeo} geo projected shapes of the level
 * @param {{theme: Object, mapName: string, metric: Object, highlight: ?number, selected: string[], hatch: *, width: number, height: number}} env mapName: the name the
 *   shapes are registered under; highlight: a legend class to keep bright; selected: place codes outlined; hatch: the no-data fill (a
 *   pattern; default the theme's no-data colour); width and height: the drawing's size in pixels (the view fits the level to it)
 * @returns {Object} {option, places, scale, ranking, table, summary, unmatched, legend}
 */
export function buildMap(spec, rows, geo, env) {
  const th = env.theme, metrics = mapMetrics(spec), metric = env.metric || metrics[0] || { field: fieldOf(spec.value) || 'value', title: 'value' };
  const fmt = formatterFor(metric.format || (spec.value && spec.value.format)), pv = placeValues(spec, rows, geo, metric), places = pv.places;
  const sc = mapScale(places, th, spec.scale), sel = new Set(env.selected || []), hl = env.highlight === undefined ? null : env.highlight;
  const shapeByCode = new Map(geo.shapes.map((s) => [s.properties.name, s]));
  const W = geo.fit[2] - geo.fit[0];
  const regions = places.filter((p) => !p.point && shapeByCode.has(p.code)).map((p) => {
    const c = sc.colourOf(p.v), cls = sc.classOf(p.v), dim = hl !== null && cls !== hl;
    const r = { name: p.code, itemStyle: { areaColor: c || env.hatch || th.nodata, opacity: dim ? 0.28 : 1 } };
    if (sel.has(p.code)) { Object.assign(r.itemStyle, { borderColor: th.tokens.accent, borderWidth: 2 }); }
    if (geo.level !== 'world') {
      const b = bounds([].concat.apply([], (shapeByCode.get(p.code).geometry.type === 'Polygon' ? [shapeByCode.get(p.code).geometry.coordinates] : shapeByCode.get(p.code).geometry.coordinates).map((q) => q[0])));
      if ((b[2] - b[0]) > W * 0.055 && (b[3] - b[1]) > W * 0.025) { r.label = { show: true, formatter: p.code.split('-').pop(), color: th.ink, fontFamily: th.font, fontSize: th.size.font - 1 }; }
    }
    return r;
  });
  geo.frames.forEach((f) => regions.push({ name: '__frame_' + f.code, silent: true, itemStyle: { areaColor: 'transparent', borderColor: th.faint, borderWidth: 0.8 }, emphasis: { disabled: true }, tooltip: { show: false } }));
  const pointData = places.filter((p) => p.point).map((p) => {
    const g = geo.points.find((x) => x.code === p.code);
    const c = sc.colourOf(p.v) || th.nodata;
    return { name: p.code, value: g.xy.concat([p.v]), r: p.r, ids: p.ids, itemStyle: { color: c, borderColor: sel.has(p.code) ? th.tokens.accent : th.faint, borderWidth: sel.has(p.code) ? 2 : 0.8, opacity: hl !== null && sc.classOf(p.v) !== hl ? 0.28 : 1 } };
  });
  const series = [{ type: 'map', geoIndex: 0, name: metric.title || metric.field, field: metric.field, data: places.filter((p) => !p.point).map((p) => ({ name: p.code, value: p.v, r: p.r, ids: p.ids })) },
    { type: 'scatter', name: 'small places', helper: true, coordinateSystem: 'geo', z: 5, symbolSize: th.size.symbol + 1, data: pointData }];
  const bub = spec.bubbles;
  if (bub) {
    const bf = fieldOf(bub), bm = { field: bf }, bvals = placeValues(spec, rows, geo, bm).places.filter((p) => p.v !== null);
    const pts = [];
    rows.forEach((r, i) => { if (bub.lon && bub.lat && num(r[bub.lon]) !== null && num(r[bub.lat]) !== null) { pts.push({ xy: geo.project(num(r[bub.lon]), num(r[bub.lat])), v: num(r[bf]), name: r[bub.label || fieldOf(spec.place) || 'code'], r: [i], ids: [rowId(spec, r, i)] }); } });
    if (!bub.lon) {
      bvals.forEach((p) => { const s = shapeByCode.get(p.code), g = geo.points.find((x) => x.code === p.code); pts.push({ xy: s ? centreOf(s) : g ? g.xy : null, v: p.v, name: p.code, r: p.r, ids: p.ids }); });
    }
    const mx = Math.max.apply(null, pts.map((p) => Math.abs(p.v || 0)).concat([1e-9]));
    series.push({ type: 'scatter', name: bub.title || bf, field: bf, coordinateSystem: 'geo', z: 6, itemStyle: { color: alpha(th.tokens.accent, 0.55), borderColor: th.tokens.accent, borderWidth: 1 },
      data: pts.filter((p) => p.xy).map((p) => ({ name: String(p.name), value: p.xy.concat([p.v]), symbolSize: 4 + 30 * Math.sqrt(Math.abs(p.v || 0) / mx), r: p.r, ids: p.ids })) });
  }
  const option = {
    animation: false, textStyle: textStyle(th),
    tooltip: { trigger: 'item', confine: true },
    geo: { map: env.mapName, projection: { project: (p) => p, unproject: (p) => p }, roam: true, scaleLimit: { min: 0.8, max: 24 },
      aspectScale: 1, left: 8, right: 8, top: 8, bottom: 8, center: fitView(geo, env.width, env.height).center, zoom: fitView(geo, env.width, env.height).zoom,
      itemStyle: { areaColor: env.hatch || th.nodata, borderColor: blend(th.border, th.surface, 0.2), borderWidth: 0.5 },
      emphasis: { itemStyle: { areaColor: alpha(th.tokens.accent, 0.55) }, label: { show: false } }, select: { disabled: true }, label: { show: false }, regions: regions },
    series: series
  };
  const tot = places.reduce((s, p) => s + (p.v || 0), 0);
  const table = { columns: [{ header: 'Code' }, { header: geo.level === 'world' ? 'Country' : 'Region' }, { header: metric.title || metric.field, format: metric.format }, { header: 'Share', format: 'percent' }],
    rows: places.map((p) => [p.code, p.name, p.v, p.v === null || !tot ? null : p.v / tot]) };
  return { option: option, places: places, scale: sc, metric: metric, fmt: fmt, ranking: ranking(places, 10), table: table, unmatched: pv.unmatched, legend: [],
    summary: 'Map of ' + (metric.title || metric.field) + ' by ' + (geo.level === 'world' ? 'country' : 'region') + ': ' + places.filter((p) => p.v !== null).length + ' of ' + places.length + ' places have a figure' };
}

/**
 * Registers the map kind: its build needs the shapes, so the view draws it (drishti_map-view.js); the registry lists it for the studio
 * and the spec's checks.
 * @returns {void}
 */
export function registerMapKind() {
  registerKind('map', { label: 'map', family: 'map', build: () => { throw new Error('a map is drawn by its view: new Chart(el, {kind: "map", ...})'); } });
}
