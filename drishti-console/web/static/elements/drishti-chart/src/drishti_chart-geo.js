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
/* The map's shapes: the vendored Natural Earth data (public domain, static/vendor/echarts/maps: world-countries.json at 1:110m,
   world-points.json for the countries too small for it, regions/<ISO3>.json for a country's first-level regions at 1:10m, simplified)
   loaded per country on demand and cached, and projected here, before the engine sees them: Equal Earth for the world, Albers for the
   United States (the lower 48 and DC, with Alaska and Hawaii as insets) and for Canada (its provinces and territories), and a conic
   fitted to any other country (a plate carrée scaled at the middle latitude where a conic cannot fit, near the equator). Projected y
   grows downwards, as on a screen; the engine draws the shapes with an identity projection. No tiles, no map service: nothing leaves
   the network. Pure functions and a loader with an injected fetch, so Node tests read the same files. */

const RAD = Math.PI / 180;

/**
 * The Equal Earth projection (Šavrič, Patterson and Jenny, 2018): equal area, the world's shapes kept pleasant.
 * @param {number} lon degrees
 * @param {number} lat degrees
 * @returns {number[]} [x, y] (y down)
 */
export function equalEarth(lon, lat) {
  const A1 = 1.340264, A2 = -0.081106, A3 = 0.000893, A4 = 0.003796, M = Math.sqrt(3) / 2;
  const l = wrap(lon) * RAD, t = Math.asin(M * Math.sin(lat * RAD)), t2 = t * t, t6 = t2 * t2 * t2;
  const x = l * Math.cos(t) / (M * (A1 + 3 * A2 * t2 + t6 * (7 * A3 + 9 * A4 * t2)));
  const y = t * (A1 + A2 * t2 + t6 * (A3 + A4 * t2));
  return [x, -y];
}

/** @private a longitude in (-180, 180] */
function wrap(d) { let x = d; while (x > 180) { x -= 360; } while (x <= -180) { x += 360; } return x; }

/**
 * An Albers equal-area conic projection with two standard parallels.
 * @param {{p1: number, p2: number, p0: number, l0: number}} o standard parallels, the origin's latitude and the central meridian
 * @returns {function(number, number): number[]} (lon, lat) to [x, y] (y down)
 */
export function albers(o) {
  const s1 = Math.sin(o.p1 * RAD), s2 = Math.sin(o.p2 * RAD), n = (s1 + s2) / 2, C = Math.cos(o.p1 * RAD) ** 2 + 2 * n * s1;
  const r0 = Math.sqrt(C - 2 * n * Math.sin(o.p0 * RAD)) / n;
  return (lon, lat) => {
    const r = Math.sqrt(Math.max(0, C - 2 * n * Math.sin(lat * RAD))) / n, th = n * wrap(lon - o.l0) * RAD;
    return [r * Math.sin(th), -(r0 - r * Math.cos(th))];
  };
}

/**
 * A plate carrée scaled at a latitude (for a country where a conic does not fit).
 * @param {number} midLat
 * @param {number} l0 the central meridian
 * @returns {function(number, number): number[]}
 */
export function plateCarree(midLat, l0) { const k = Math.cos(midLat * RAD); return (lon, lat) => [wrap(lon - l0) * RAD * k, -lat * RAD]; }

/**
 * A projected copy of a GeoJSON geometry (Polygon, MultiPolygon, Point).
 * @param {Object} g
 * @param {function(number, number): number[]} fn
 * @returns {Object}
 */
export function projectGeometry(g, fn) {
  const ring = (r) => r.map((p) => fn(p[0], p[1]));
  if (g.type === 'Polygon') { return { type: 'Polygon', coordinates: g.coordinates.map(ring) }; }
  if (g.type === 'MultiPolygon') { return { type: 'MultiPolygon', coordinates: g.coordinates.map((poly) => poly.map(ring)) }; }
  if (g.type === 'Point') { return { type: 'Point', coordinates: fn(g.coordinates[0], g.coordinates[1]) }; }
  return g;
}

/** @private every outer ring of a geometry */
function outerRings(g) { return g.type === 'Polygon' ? [g.coordinates[0]] : g.type === 'MultiPolygon' ? g.coordinates.map((p) => p[0]) : []; }

/**
 * The box of points [[x, y], ...]: [minX, minY, maxX, maxY].
 * @param {number[][]} pts
 * @returns {number[]}
 */
export function bounds(pts) {
  const b = [Infinity, Infinity, -Infinity, -Infinity];
  pts.forEach((p) => { b[0] = Math.min(b[0], p[0]); b[1] = Math.min(b[1], p[1]); b[2] = Math.max(b[2], p[0]); b[3] = Math.max(b[3], p[1]); });
  return b;
}

/** @private a ring's area in square degrees (shoelace, absolute) */
function ringArea(r) { let a = 0; for (let i = 0, j = r.length - 1; i < r.length; j = i++) { a += (r[j][0] + r[i][0]) * (r[j][1] - r[i][1]); } return Math.abs(a / 2); }

/**
 * The extent a country's map is fitted to (lon/lat): its rings near the area-weighted centre, so far-away parts (France's overseas
 * regions) do not shrink the mainland; they stay on the map, reachable by panning.
 * @param {Object[]} features GeoJSON features (polygons; points ignored)
 * @returns {{lon0: number, lat0: number, lat1: number, lonMin: number, lonMax: number}}
 */
export function coreExtent(features) {
  const rings = [];
  features.forEach((f) => outerRings(f.geometry).forEach((r) => { const b = bounds(r); rings.push({ b: b, a: ringArea(r) || 1e-6, c: [(b[0] + b[2]) / 2, (b[1] + b[3]) / 2] }); }));
  if (!rings.length) { return { lon0: 0, lat0: -10, lat1: 10, lonMin: -10, lonMax: 10 }; }
  const tot = rings.reduce((s, r) => s + r.a, 0), cx = rings.reduce((s, r) => s + r.c[0] * r.a, 0) / tot, cy = rings.reduce((s, r) => s + r.c[1] * r.a, 0) / tot;
  const core = rings.filter((r) => Math.abs(r.c[0] - cx) <= 30 && Math.abs(r.c[1] - cy) <= 25);
  const use = core.length ? core : rings;
  const b = [Math.min.apply(null, use.map((r) => r.b[0])), Math.min.apply(null, use.map((r) => r.b[1])), Math.max.apply(null, use.map((r) => r.b[2])), Math.max.apply(null, use.map((r) => r.b[3]))];
  return { lon0: (b[0] + b[2]) / 2, lat0: b[1], lat1: b[3], lonMin: b[0], lonMax: b[2] };
}

/**
 * The projection of a country's regions: Albers for the United States and Canada (Statistics Canada's parallels), a conic fitted to the
 * country's core extent otherwise, a scaled plate carrée where a conic cannot fit (the parallels on both sides of the equator).
 * @param {string} iso3
 * @param {Object[]} features
 * @returns {{name: string, project: function(number, number): number[], extent: Object}}
 */
export function countryProjection(iso3, features) {
  const e = coreExtent(features);
  if (iso3 === 'USA') { return { name: 'albers-usa', project: albers({ p1: 29.5, p2: 45.5, p0: 37.5, l0: -96 }), extent: e }; }
  if (iso3 === 'CAN') { return { name: 'albers-canada', project: albers({ p1: 49, p2: 77, p0: 63.39, l0: -91.87 }), extent: e }; }
  const span = e.lat1 - e.lat0, p1 = e.lat0 + span / 6, p2 = e.lat1 - span / 6;
  if (p1 * p2 > 0 && Math.abs(Math.sin(p1 * RAD) + Math.sin(p2 * RAD)) / 2 > 0.12) { return { name: 'albers-fitted', project: albers({ p1: p1, p2: p2, p0: (e.lat0 + e.lat1) / 2, l0: e.lon0 }), extent: e }; }
  return { name: 'plate-carree', project: plateCarree((e.lat0 + e.lat1) / 2, e.lon0), extent: e };
}

/** The insets of the United States: Alaska and Hawaii, each in its own Albers, scaled and set under the lower 48's south-west. */
export const US_INSETS = Object.freeze({
  'US-AK': Object.freeze({ proj: Object.freeze({ p1: 55, p2: 65, p0: 50, l0: -154 }), width: 0.25, left: 0 }),
  'US-HI': Object.freeze({ proj: Object.freeze({ p1: 8, p2: 18, p0: 3, l0: -157 }), width: 0.11, left: 0.27 })
});

/** @private moves projected geometry by a scale and an offset */
function affine(g, k, dx, dy) { return projectGeometry(g, (x, y) => [x * k + dx, y * k + dy]); }

/** @private every [x, y] of a projected geometry */
function allPoints(g) { return g.type === 'Point' ? [g.coordinates] : [].concat.apply([], (g.type === 'Polygon' ? [g.coordinates] : g.coordinates).map((p) => [].concat.apply([], p))); }

/**
 * A country's regions projected: polygons named by their ISO 3166-2 code (so the engine's map matches data by code), the small ones as
 * points, the extent to fit, and for the United States Alaska and Hawaii moved into insets with their frames.
 * @param {Object} geo regions/<ISO3>.json
 * @returns {RegionGeo}
 */
export function prepareRegions(geo) {
  const iso3 = (geo.properties && geo.properties.iso3) || (geo.features[0] && geo.features[0].properties.iso3) || '';
  const proj = countryProjection(iso3, geo.features.filter((f) => f.geometry.type !== 'Point' && !US_INSETS[f.properties.code]));
  const shapes = [], points = [], by = { code: {}, name: {}, short: {} }, list = [], frames = [];
  geo.features.forEach((f) => {
    const p = f.properties, item = { code: p.code, name: p.name, point: f.geometry.type === 'Point' };
    list.push(item);
    by.code[p.code.toUpperCase()] = item; by.name[p.name.toLowerCase()] = item; by.short[p.code.split('-').pop().toUpperCase()] = item;
    if (iso3 === 'USA' && US_INSETS[p.code]) { return; }
    const g = projectGeometry(f.geometry, proj.project);
    if (g.type === 'Point') { item.xy = g.coordinates; points.push(item); } else { shapes.push({ type: 'Feature', properties: { name: p.code, fullName: p.name }, geometry: g }); }
  });
  const main = bounds([].concat.apply([], shapes.map((s) => allPoints(s.geometry))).concat(points.map((p) => p.xy)));
  const extent = Object.assign({}, proj.extent);
  let fit = isFinite(main[0]) ? main : [-1, -1, 1, 1];
  if (proj.name === 'albers-fitted' || proj.name === 'plate-carree') {
    const corners = [];
    for (let i = 0; i <= 8; i++) { corners.push(proj.project(extent.lonMin + (extent.lonMax - extent.lonMin) * i / 8, extent.lat0), proj.project(extent.lonMin + (extent.lonMax - extent.lonMin) * i / 8, extent.lat1)); }
    fit = bounds(corners);
  }
  if (iso3 === 'USA') {
    const W = main[2] - main[0];
    geo.features.filter((f) => US_INSETS[f.properties.code]).forEach((f) => {
      const ins = US_INSETS[f.properties.code], g0 = projectGeometry(f.geometry, albers(ins.proj)), b = bounds(allPoints(g0));
      const k = ins.width * W / (b[2] - b[0]), x0 = main[0] + ins.left * W, y1 = main[3] + 0.03 * W;
      const g = affine(g0, k, x0 - b[0] * k, y1 - b[3] * k);
      shapes.push({ type: 'Feature', properties: { name: f.properties.code, fullName: f.properties.name }, geometry: g });
      const fb = bounds(allPoints(g)), pad = 0.01 * W;
      frames.push({ code: f.properties.code, box: [fb[0] - pad, fb[1] - pad, fb[2] + pad, fb[3] + pad] });
    });
    fit = bounds([[fit[0], fit[1]], [fit[2], fit[3]]].concat(frames.map((fr) => [fr.box[2], fr.box[3]])));
  }
  return { level: iso3, iso3: iso3, projection: proj.name, project: proj.project, shapes: shapes, points: points, by: by, list: list, fit: fit, frames: frames };
}

/**
 * @typedef {Object} RegionGeo
 * @property {string} level 'world' or the country's ISO alpha-3
 * @property {string} projection equal-earth, albers-usa, albers-canada, albers-fitted or plate-carree
 * @property {function(number, number): number[]} project (lon, lat) to the map's projected [x, y]
 * @property {Object[]} shapes projected GeoJSON features named by place code
 * @property {Array<{code: string, name: string, xy: number[]}>} points the places drawn as points
 * @property {{code: Object, name: Object, short: Object}} by lookups (a country's iso3 and iso2 for the world)
 * @property {Array<{code: string, name: string, point: boolean}>} list every place
 * @property {number[]} fit [minX, minY, maxX, maxY] the view fits
 * @property {Array<{code: string, box: number[]}>} frames inset frames (Alaska, Hawaii)
 */

/**
 * The world projected (Equal Earth): countries named by ISO alpha-3, the small ones (world-points.json) as points.
 * @param {Object} geo world-countries.json
 * @param {Array<{iso3: string, iso2: string, name: string, lon: number, lat: number}>} [pts] world-points.json
 * @returns {RegionGeo}
 */
export function prepareWorld(geo, pts) {
  const shapes = [], points = [], by = { code: {}, name: {}, short: {} }, list = [];
  geo.features.forEach((f) => {
    const p = f.properties, item = { code: p.iso3, iso2: p.iso2 || null, name: p.name, point: false };
    if (p.iso3 === 'ATA') { return; }
    list.push(item);
    by.code[p.iso3.toUpperCase()] = item; if (p.iso2) { by.short[p.iso2.toUpperCase()] = item; } by.name[p.name.toLowerCase()] = item;
    shapes.push({ type: 'Feature', properties: { name: p.iso3, fullName: p.name }, geometry: projectGeometry(f.geometry, equalEarth) });
  });
  (pts || []).forEach((p) => {
    if (by.code[p.iso3]) { return; }
    const item = { code: p.iso3, iso2: p.iso2 || null, name: p.name, point: true, xy: equalEarth(p.lon, p.lat) };
    list.push(item); points.push(item);
    by.code[p.iso3] = item; if (p.iso2) { by.short[p.iso2.toUpperCase()] = item; } by.name[p.name.toLowerCase()] = item;
  });
  const fit = bounds([equalEarth(-180, 84), equalEarth(180, -58), equalEarth(0, 84), equalEarth(0, -58)]);
  return { level: 'world', iso3: null, projection: 'equal-earth', project: equalEarth, shapes: shapes, points: points, by: by, list: list, fit: fit, frames: [] };
}

/**
 * The place a code or name means on a level: an ISO alpha-3 or alpha-2 country, an ISO 3166-2 region ("US-CA"), a region's code
 * without its country ("CA" on the United States' map) or an English name; null when the map has no such place.
 * @param {RegionGeo} geo
 * @param {*} code
 * @returns {?{code: string, name: string}}
 */
export function findPlace(geo, code) {
  if (code === null || code === undefined || !geo) { return null; }
  const k = String(code).trim();
  return geo.by.code[k.toUpperCase()] || geo.by.short[k.toUpperCase()] || geo.by.name[k.toLowerCase()] || null;
}

/**
 * A loader of the shapes: the world once, each country's regions the first time they are asked, cached for the session (a failed
 * load is forgotten, so it can be asked again).
 * @param {{base: string, fetchJson: function(string): Promise<Object>}} o base: the maps folder ("/static/vendor/echarts/maps/");
 *   fetchJson: how to read a file (default: fetch, same origin)
 * @returns {{world: function(): Promise<RegionGeo>, regions: function(string): Promise<RegionGeo>, hasRegions: function(string): boolean, loads: string[]}}
 */
export function createGeoLoader(o) {
  o = o || {};
  const base = (o.base || '/static/vendor/echarts/maps/').replace(/\/?$/, '/');
  const get = o.fetchJson || ((url) => fetch(url, { credentials: 'same-origin' }).then((r) => { if (!r.ok) { throw new Error('map ' + url + ': ' + r.status); } return r.json(); }));
  const cache = { world: null, regions: {} }, loads = [];
  const fetched = (file) => { loads.push(file); return Promise.resolve().then(() => get(base + file)); };
  return {
    loads: loads,
    world() {
      if (!cache.world) {
        cache.world = Promise.all([fetched('world-countries.json'), fetched('world-points.json').catch(() => [])]).then((got) => prepareWorld(got[0], got[1]));
        cache.world.catch(() => { cache.world = null; });
      }
      return cache.world;
    },
    regions(iso3) {
      const k = String(iso3 || '').toUpperCase();
      if (!/^[A-Z]{3}$/.test(k)) { return Promise.reject(new Error('no country ' + iso3)); }
      if (!cache.regions[k]) {
        cache.regions[k] = fetched('regions/' + k + '.json').then(prepareRegions);
        cache.regions[k].catch(() => { delete cache.regions[k]; });
      }
      return cache.regions[k];
    },
    hasRegions(iso3) { return !!cache.regions[String(iso3).toUpperCase()]; }
  };
}
