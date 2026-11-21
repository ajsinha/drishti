/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* drishti-chart's geography part (drishti_chart-geohelper.js, loaded by loadPart('drishti-chart', 'geo') only when a page draws a map
   panel): the map helper a view's map panels draw with (PanelCharts' option geo, drishti-views' default), over the vendored Natural
   Earth shapes (public domain: world-countries.json at 1:110m, world-points.json, regions/<ISO3>.json for every country's first-level
   regions, among them the United States' states and Canada's provinces and territories) and the projections of drishti_chart-geo.js
   (Equal Earth for the world, Albers for the United States with Alaska and Hawaii as insets and for Canada, a fitted conic elsewhere).
   No tiles, no map service, no CDN: each file is read from the page's own server the first time a map needs it, and cached.

     const G = createGeoHelper({ base: staticUrl('vendor/echarts/maps/') });
     G.world()                 the world: countries by ISO alpha-3 (by.iso3, by.iso2, by.name), the small ones as points
     G.region('USA')           one country's regions by ISO 3166-2 code (by.code, by.short, by.name); rejects when it has none
     G.find(world, 'DE')       a country by ISO alpha-2 or alpha-3 or English name ({iso3, iso2, name}), or null
     G.place(geo, 'CA')        a place of a level by code, short code or name ({code, name}), or null
     G.project(geo, lon, lat)  a longitude and latitude on the level's map; G.bounds(geo) its boxes
     G.option(cfg)             the ECharts option of a world or region map (a choropleth, highlights, clickable places, bubbles)
     G.codeOf(params)          the place code of a click or hover on that map */
import { escapeHtml } from '../../drishti-core/src/drishti_index.js';
import { createGeoLoader, findPlace, fitView, locate, blend, alpha, panelCompact } from './drishti_index.js';

const helpers = new Map();
const esc = escapeHtml;
const finite = (v) => typeof v === 'number' && isFinite(v);
const DEFAULT_T = { ink: '#1b2430', muted: '#6b7785', border: '#c9d1da', surface: '#ffffff', link: '#1f5fbf', accent: '#d9822b', pos: '#2e8b57', mono: 'monospace' };

/**
 * The map helper of a maps folder: one per folder in a page (every map of the page shares its files and cache), unless fetchJson is
 * given (a test's reader: a helper of its own).
 * @param {GeoHelperOptions} [o]
 * @returns {GeoHelper}
 */
export function geoHelper(o) {
  o = o || {};
  if (o.fetchJson || !o.base) { return createGeoHelper(o); }
  const k = String(o.base);
  if (!helpers.has(k)) { helpers.set(k, createGeoHelper(o)); }
  return helpers.get(k);
}

/**
 * @typedef {Object} GeoHelperOptions
 * @property {string} [base] the maps folder (default: drishti-core's static root + "vendor/echarts/maps/")
 * @property {function(string): Promise<Object>} [fetchJson] how to read a file (default: fetch, same origin)
 * @property {(Object|function(): Object)} [engine] ECharts, or a function that gives it (default: the page's window.echarts when a map
 *   is drawn); the shapes are registered with it under drishti-geo-world and drishti-geo-<ISO3>
 */

/**
 * @typedef {Object} GeoHelper
 * @property {function(): Promise<Object>} world the world (a RegionGeo with by.iso3 and by.iso2 too)
 * @property {function(string): Promise<Object>} region one country's regions by ISO alpha-3 (a RegionGeo)
 * @property {function(Object, *): ?{iso3: string, iso2: ?string, name: string}} find a country of the world by code or name
 * @property {function(Object, *): ?{code: string, name: string}} place a place of a level by code, short code or name
 * @property {function(Object, number, number): number[]} project a longitude and latitude on the level's map
 * @property {function(Object): {fit: number[], all: number[]}} bounds the box the map fits and the box of everything drawn
 * @property {function(Object): Object} option the ECharts option of a map (see GeoMapConfig)
 * @property {function(Object): ?string} codeOf the place code of an ECharts click or hover on a map built by option()
 * @property {string[]} loads the files read so far (for a test)
 * @property {string} base the maps folder
 */

/**
 * @typedef {Object} GeoMapConfig
 * @property {('world'|'region')} level the world's countries, or one country's regions
 * @property {Object} geo world() or region(iso3)
 * @property {Object<string, string>} [t] the theme's colours (panelTokens: ink, muted, border, surface, link, accent, pos, mono)
 * @property {Array<{code: string, value: ?number, text: ?string}>} [items] the figures that shade the places (a code, short code or name)
 * @property {string[]} [highlight] places drawn in the accent colour, with their names
 * @property {?string[]} [clickable] null: every place; else the places a click opens (the others say so on hover)
 * @property {boolean} [linked] whether clicking opens anything at all
 * @property {string[]} [selected] the places a cross-filter keeps (the others dimmed)
 * @property {Array<{code: string, lon: number, lat: number, value: number, name: string, text: string}>} [bubbles] a layer of circles sized
 *   by value, each at a place (code) or a longitude and latitude
 * @property {string} [legend] the title of the colour scale; unit: appended to a figure
 * @property {string} [unit]
 * @property {function(number): string} [numText] a figure as text (default panelCompact)
 * @property {string} [openText] the hover text of a place a click opens; closedText: of one it does not
 * @property {string} [closedText]
 * @property {number} [width] the drawing's width and height in pixels (fit the map to it; default 800 by 400)
 * @property {number} [height]
 */

/**
 * A map helper of its own (geoHelper shares one per folder).
 * @param {GeoHelperOptions} [o]
 * @returns {GeoHelper}
 */
export function createGeoHelper(o) {
  o = o || {};
  let base = o.base;
  if (!base) {
    const g = typeof window !== 'undefined' ? window : globalThis, core = g.Drishti && g.Drishti.elements && g.Drishti.elements.core;
    if (!core || !core.staticUrl) { throw new Error('createGeoHelper: the maps folder is not known: give the option base ("/vendor/echarts/maps/")'); }
    base = core.staticUrl('vendor/echarts/maps/');
  }
  const loader = createGeoLoader({ base: base, fetchJson: o.fetchJson });
  const decorated = new WeakMap(), registered = new WeakMap();
  const engine = () => { const e = typeof o.engine === 'function' ? o.engine() : o.engine; return e || (typeof window !== 'undefined' ? window.echarts : null); };

  /* the world gains the console helper's lookups (by.iso3, by.iso2) and each country its iso3; a level its engine name */
  const dress = (geo) => {
    if (decorated.has(geo)) { return decorated.get(geo); }
    const isWorld = geo.level === 'world';
    if (isWorld) { geo.list.forEach((p) => { p.iso3 = p.code; }); }
    const by = isWorld ? Object.assign({}, geo.by, { iso3: geo.by.code, iso2: geo.by.short }) : geo.by;
    const out = Object.assign({}, geo, { by: by, name: 'drishti-geo-' + (isWorld ? 'world' : geo.iso3), bbox: geo.fit, raw: geo });
    decorated.set(geo, out);
    return out;
  };
  const register = (geo) => {
    const E = engine();
    if (!E || !E.registerMap) { throw new Error('the map helper needs ECharts: load it first, or give the option engine'); }
    const done = registered.get(E) || new Set();
    if (!done.has(geo.name)) {
      const frames = geo.frames.map((f) => ({ type: 'Feature', properties: { name: '__frame_' + f.code },
        geometry: { type: 'Polygon', coordinates: [[[f.box[0], f.box[1]], [f.box[2], f.box[1]], [f.box[2], f.box[3]], [f.box[0], f.box[3]], [f.box[0], f.box[1]]]] } }));
      E.registerMap(geo.name, { type: 'FeatureCollection', features: frames.concat(geo.shapes) });
      done.add(geo.name);
      registered.set(E, done);
    }
  };

  const api = {
    base: base,
    loads: loader.loads,
    world: () => loader.world().then(dress),
    region: (iso3) => loader.regions(iso3).then(dress),
    find(w, code) {
      if (!w || code == null) { return null; }
      const p = findPlace(w, code);
      return p ? { iso3: p.code, iso2: p.iso2 || null, name: p.name } : null;
    },
    place: (geo, code) => { const p = findPlace(geo, code); return p ? { code: p.code, name: p.name } : null; },
    project: (geo, lon, lat) => locate(geo, Number(lon), Number(lat)),
    bounds: (geo) => ({ fit: geo.fit.slice(), all: (geo.all || geo.fit).slice() }),
    option: (cfg) => buildGeoOption(cfg, register),
    codeOf(p) {
      if (!p) { return null; }
      const c = p.seriesType === 'scatter' ? (p.data && p.data.code) : p.name;
      return c && String(c).indexOf('__') !== 0 ? String(c) : null;
    }
  };
  return api;
}

/** @private the middle of a shape's largest ring (a bubble at a place) */
function centreOf(shape) {
  const g = shape.geometry, polys = g.type === 'Polygon' ? [g.coordinates] : g.coordinates;
  let best = null, area = -1;
  polys.forEach((p) => {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    p[0].forEach((q) => { x0 = Math.min(x0, q[0]); y0 = Math.min(y0, q[1]); x1 = Math.max(x1, q[0]); y1 = Math.max(y1, q[1]); });
    if ((x1 - x0) * (y1 - y0) > area) { area = (x1 - x0) * (y1 - y0); best = [(x0 + x1) / 2, (y0 + y1) / 2]; }
  });
  return best;
}

/**
 * The ECharts option of a world or region map over the projected shapes (an identity projection: the helper projected them).
 * @param {GeoMapConfig} cfg
 * @param {function(Object): void} register registers the level's shapes with the engine
 * @returns {Object}
 */
export function buildGeoOption(cfg, register) {
  const geo = cfg.geo, t = Object.assign({}, DEFAULT_T, Object.fromEntries(Object.entries(cfg.t || {}).filter((e) => e[1])));
  const isWorld = cfg.level ? cfg.level === 'world' : geo.level === 'world', num = cfg.numText || panelCompact;
  if (register) { register(geo); }
  const codeOf = (c) => { const p = findPlace(geo, c); return p ? p.code : String(c == null ? '' : c).trim().toUpperCase(); };
  const items = (cfg.items || []).map((i) => ({ code: codeOf(i.code), value: i.value, text: i.text }));
  const hi = new Set((cfg.highlight || []).map(codeOf)), ok = cfg.clickable ? new Set(cfg.clickable.map(codeOf)) : null;
  const sel = cfg.selected && cfg.selected.length ? new Set(cfg.selected.map(codeOf)) : null;
  const byCode = {};
  items.forEach((i) => { byCode[i.code] = i; });
  const nums = items.map((i) => i.value).filter(finite);
  let lo = nums.length ? Math.min(0, Math.min.apply(null, nums)) : 0, top = nums.length ? Math.max(1, Math.max.apply(null, nums)) : 1, clipped = false;
  if (nums.length >= 20) {          // a few extremes would leave every other place one colour: the scale ends at the 95th percentile
    const s = nums.slice().sort((a, b) => a - b), p95 = s[Math.floor((s.length - 1) * 0.95)];
    if (p95 > lo && p95 < top) { top = p95; clipped = true; }
  }
  const baseC = blend(t.surface, t.muted, 0.3), topC = t.link;
  const nameOf = (code) => { const p = geo.by.code[String(code).toUpperCase()]; return p ? p.name : code; };
  const paint = (code) => { const i = byCode[code]; return i && finite(i.value) ? blend(baseC, topC, Math.max(0, Math.min(1, (i.value - lo) / ((top - lo) || 1)))) : null; };
  const shapes = new Map(geo.shapes.map((s) => [s.properties.name, s]));
  const data = [], regions = [], seen = new Set();
  const add = (code) => {
    if (seen.has(code) || !shapes.has(code)) { return; }
    seen.add(code);
    const i = byCode[code];
    data.push({ name: code, value: i && finite(i.value) ? i.value : null });
    const r = { name: code };
    if (sel && !sel.has(code)) { r.itemStyle = { opacity: 0.4 }; }
    if (hi.has(code)) {
      r.itemStyle = { areaColor: t.accent, borderColor: t.ink, borderWidth: 1.2 };
      r.emphasis = { itemStyle: { areaColor: t.accent }, label: { show: true, color: t.ink } };
      r.label = { show: true, color: t.ink, fontSize: 11, fontWeight: 600, formatter: nameOf(code) };
    }
    if (r.itemStyle || r.label) { regions.push(r); }
  };
  items.forEach((i) => add(i.code));
  hi.forEach(add);
  geo.frames.forEach((f) => regions.push({ name: '__frame_' + f.code, silent: true, itemStyle: { areaColor: 'transparent', borderColor: t.border, borderWidth: 0.8 },
    emphasis: { disabled: true }, tooltip: { show: false } }));
  const series = [{ type: 'map', geoIndex: 0, data: data, selectedMode: false }];
  if (geo.points.length) {
    series.push({ type: 'scatter', coordinateSystem: 'geo', z: 5, symbol: 'circle', name: 'places',
      data: geo.points.map((p) => {
        const h = hi.has(p.code);
        return { name: p.code, code: p.code, value: p.xy.slice(), symbolSize: h ? 11 : paint(p.code) ? 7 : 5,
          itemStyle: { color: h ? t.accent : (paint(p.code) || t.muted), opacity: sel && !sel.has(p.code) ? 0.4 : 1, borderColor: !ok || ok.has(p.code) ? t.ink : t.border, borderWidth: h ? 1.5 : 1 } };
      }) });
  }
  const bubbles = (cfg.bubbles || []).map((b) => {
    const code = b.code != null ? codeOf(b.code) : null, s = code && shapes.get(code), pt = code && geo.points.find((p) => p.code === code);
    const xy = finite(Number(b.lon)) && finite(Number(b.lat)) && b.lon !== null && b.lat !== null ? locate(geo, Number(b.lon), Number(b.lat)) : s ? centreOf(s) : pt ? pt.xy : null;
    return xy ? { xy: xy, v: Number(b.value) || 0, code: code, name: b.name || (code ? nameOf(code) : ''), text: b.text } : null;
  }).filter(Boolean);
  const bubbleIndex = series.length;
  if (bubbles.length) {
    const mx = Math.max.apply(null, bubbles.map((b) => Math.abs(b.v)).concat([1e-9]));
    series.push({ type: 'scatter', coordinateSystem: 'geo', z: 6, name: 'bubbles', itemStyle: { color: alpha(t.accent, 0.55), borderColor: t.accent, borderWidth: 1 },
      data: bubbles.map((b) => ({ name: b.name, code: b.code, bubble: true, text: b.text, value: b.xy.concat([b.v]), symbolSize: 4 + 30 * Math.sqrt(Math.abs(b.v) / mx) })) });
  }
  const labelled = !isWorld && geo.shapes.length > 0 && geo.shapes.length <= 14;
  const shaded = nums.length > 0, view = fitView(geo, cfg.width || 800, cfg.height || 400);
  const o = {
    animation: false, backgroundColor: 'transparent',
    tooltip: { trigger: 'item', confine: true, backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 },
      formatter: (p) => {
        if (p.seriesIndex === bubbleIndex && p.data && p.data.bubble) {
          return '<b>' + esc(p.data.name) + '</b><br>' + esc(p.data.text != null ? p.data.text : num(p.data.value[2]) + (cfg.unit ? ' ' + cfg.unit : ''));
        }
        const code = p.seriesType === 'scatter' ? p.data && p.data.code : p.name, i = byCode[code];
        let line = '<b>' + esc(nameOf(code)) + '</b>';
        if (i && (i.text != null || finite(i.value))) { line += '<br>' + esc((cfg.legend ? cfg.legend + ': ' : '') + (i.text != null ? i.text : num(i.value)) + (cfg.unit && i.text == null ? ' ' + cfg.unit : '')); } else if (shaded) { line += '<br>no figure'; }
        if (cfg.linked) { line += '<br>' + esc(!ok || ok.has(code) ? (cfg.openText || 'click to open') : (cfg.closedText || 'no page for this ' + (isWorld ? 'country' : 'region'))); }
        return line;
      } },
    geo: { map: geo.name, projection: { project: (p) => p, unproject: (p) => p }, roam: false, aspectScale: 1, preserveAspect: 'contain', left: 6, right: 6, top: 6, bottom: shaded ? 40 : 6,
      center: view.center, zoom: view.zoom,
      itemStyle: { areaColor: baseC, borderColor: t.border, borderWidth: 0.6 },
      label: { show: labelled, color: t.ink, fontSize: 10, formatter: (p) => (String(p.name).indexOf('__') === 0 ? '' : String(p.name).split('-').pop()) },
      emphasis: { label: { show: labelled, color: t.ink }, itemStyle: { areaColor: t.pos || t.link } }, select: { disabled: true }, regions: regions },
    series: series
  };
  if (shaded) {
    o.visualMap = { type: 'continuous', min: lo, max: top, seriesIndex: 0, calculable: false, orient: 'horizontal', left: 'center', bottom: 0, itemWidth: 12, itemHeight: 110,
      inRange: { color: [baseC, topC] }, outOfRange: { color: [baseC] }, textStyle: { color: t.muted, fontSize: 10 }, text: [num(top) + (clipped ? '+' : ''), num(lo)] };
  }
  return o;
}
