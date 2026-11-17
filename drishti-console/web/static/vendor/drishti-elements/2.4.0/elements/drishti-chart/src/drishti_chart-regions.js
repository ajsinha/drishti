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
/* The world's regions as map levels: continents, then a continent's sub-regions, then a sub-region's countries (then a country's own
   first-level regions, as before). The grouping is WORLD_REGIONS, generated from world-regions.yaml, the one file the server reads too, so
   a figure by region is computed on the server by the same grouping and the map only draws it. A region's shape is its countries'
   polygons drawn as one place (merged by listing them together, not by computing a union: no new geometry file). Codes are CT-<ABC> for a
   continent and SR-<NAME> for a sub-region; a row names a region by that code or by its name. */
import { WORLD_REGIONS } from './drishti_world-regions.js';

/** The level names the map starts at: the world's countries, or its continents. */
export const REGION_START_LEVELS = Object.freeze(['world', 'continents']);

/**
 * Whether a level string is one of the region levels (continents, a continent's code CT-…, a sub-region's code SR-…).
 * @param {string} level
 * @returns {boolean}
 */
export function isRegionLevel(level) { return level === 'continents' || /^(CT|SR)-[A-Z-]+$/.test(String(level)); }

/**
 * A country's region: its sub-region and continent (code and name), or null when the table does not list it.
 * @param {string} iso3
 * @returns {?{subregion: {code: string, name: string}, continent: {code: string, name: string}}}
 */
export function regionOf(iso3) {
  const sr = WORLD_REGIONS.byCountry[String(iso3).toUpperCase()];
  if (!sr) { return null; }
  const s = WORLD_REGIONS.subregions[sr];
  return { subregion: { code: sr, name: s.name }, continent: { code: s.continent, name: WORLD_REGIONS.continents[s.continent] } };
}

/**
 * The name of a region by its code (a continent's or a sub-region's), else the code.
 * @param {string} code
 * @returns {string}
 */
export function regionName(code) {
  const k = String(code).toUpperCase();
  return WORLD_REGIONS.continents[k] || (WORLD_REGIONS.subregions[k] && WORLD_REGIONS.subregions[k].name) || String(code);
}

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

/** @private every point of a projected geometry */
function pointsOf(g) { return [].concat.apply([], (g.type === 'Polygon' ? [g.coordinates] : g.coordinates).map((p) => [].concat.apply([], p))); }

/** @private one MultiPolygon from the geometries of several shapes (listed together) */
function together(geoms) {
  const polys = [];
  geoms.forEach((g) => { if (g.type === 'Polygon') { polys.push(g.coordinates); } else if (g.type === 'MultiPolygon') { g.coordinates.forEach((p) => polys.push(p)); } });
  return { type: 'MultiPolygon', coordinates: polys };
}

/**
 * The geography of a region level from the projected world: 'continents' (every continent as one place), 'CT-…' (that continent's
 * sub-regions as places), 'SR-…' (that sub-region's countries, drawn as the world draws them).
 * @param {import('./drishti_chart-geo.js').RegionGeo} world prepareWorld's answer
 * @param {string} level
 * @returns {import('./drishti_chart-geo.js').RegionGeo} with regionKind ('continents'|'subregions'|'countries'), title and trail (the levels above, to go back to)
 */
export function prepareRegionLevel(world, level) {
  const R = WORLD_REGIONS, byShape = new Map(world.shapes.map((s) => [s.properties.name, s]));
  const by = { code: {}, name: {}, short: {} }, list = [], shapes = [], points = [];
  const place = (code, name, countries) => {
    const have = countries.filter((c) => byShape.has(c));
    const item = { code: code, name: name, point: false, countries: countries.length };
    list.push(item);
    by.code[code.toUpperCase()] = item; by.name[name.toLowerCase()] = item;
    if (have.length) { shapes.push({ type: 'Feature', properties: { name: code, fullName: name }, geometry: together(have.map((c) => byShape.get(c).geometry)) }); }
    return item;
  };
  let kind, title, trail;
  if (level === 'continents') {
    kind = 'continents'; title = 'World regions'; trail = [];
    Object.keys(R.continents).forEach((ct) => place(ct, R.continents[ct], [].concat.apply([], Object.keys(R.subregions).filter((s) => R.subregions[s].continent === ct).map((s) => R.subregions[s].countries))));
  } else if (/^CT-/.test(level)) {
    if (!R.continents[level]) { throw new Error('no continent ' + level); }
    kind = 'subregions'; title = R.continents[level]; trail = [{ level: 'continents', label: 'World regions' }];
    Object.keys(R.subregions).filter((s) => R.subregions[s].continent === level).forEach((s) => place(s, R.subregions[s].name, R.subregions[s].countries));
  } else if (/^SR-/.test(level)) {
    const sr = R.subregions[level];
    if (!sr) { throw new Error('no sub-region ' + level); }
    kind = 'countries'; title = sr.name; trail = [{ level: 'continents', label: 'World regions' }, { level: sr.continent, label: R.continents[sr.continent] }];
    sr.countries.forEach((c) => {
      const item = world.by.code[c];
      if (!item) { return; }
      list.push(item); by.code[c] = item; by.name[item.name.toLowerCase()] = item; if (item.iso2) { by.short[item.iso2.toUpperCase()] = item; }
      if (item.point) { points.push(item); } else if (byShape.has(c)) { shapes.push(byShape.get(c)); }
    });
  } else { throw new Error('no region level ' + level); }
  const fit = bounds([].concat.apply([], shapes.map((s) => pointsOf(s.geometry))).concat(points.map((p) => p.xy)));
  const box = isFinite(fit[0]) ? fit : [-1, -1, 1, 1];
  return { level: level, iso3: null, projection: world.projection, project: world.project, shapes: shapes, points: points, by: by, list: list, fit: box, frames: [], all: box,
    regionKind: kind, title: title, trail: trail };
}
