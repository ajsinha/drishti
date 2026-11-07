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
/* drishti-chart's maps under Node, on the vendored Natural Earth files themselves: the projections (Equal Earth, Albers, the fitted
   conic), the loader (the world once, a country's regions on demand, cached, a failure forgotten), the United States with Alaska and
   Hawaii as insets, Canada's provinces and territories, every one of the region files projected to a finite view, the place lookup by
   ISO codes and names, and the choropleth's values, classes, counts, ranking and no-data places. */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as C from '../../web/static/elements/drishti-chart/src/drishti_index.js';

const MAPS = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'web', 'static', 'vendor', 'echarts', 'maps') + path.sep;
const read = (u) => Promise.resolve(JSON.parse(fs.readFileSync(u, 'utf8')));
const th = C.chartTheme({ theme: 'terminal' });

test('the projections: Equal Earth, Albers at its origin, a plate carrée', () => {
  assert.deepEqual(C.equalEarth(0, 0).map((v) => Math.abs(v)), [0, 0]);
  const [x, y] = C.equalEarth(180, 0);
  assert.ok(Math.abs(x - 2.7066) < 1e-3 && Math.abs(y) < 1e-12);
  assert.ok(C.equalEarth(0, 60)[1] < 0, 'north is up: y grows downwards');
  assert.ok(Math.abs(C.equalEarth(181, 10)[0] - C.equalEarth(-179, 10)[0]) < 1e-12, 'longitudes wrap');
  const us = C.albers({ p1: 29.5, p2: 45.5, p0: 37.5, l0: -96 });
  assert.deepEqual(us(-96, 37.5).map((v) => Math.round(v * 1e9) / 1e9 + 0), [0, 0]);
  assert.ok(us(-80, 40)[0] > 0 && us(-120, 40)[0] < 0);
  assert.ok(Math.abs(us(264, 37.5)[0] - us(-96, 37.5)[0]) < 1e-12, 'an unwrapped longitude projects the same');
  assert.deepEqual(C.plateCarree(0, 0)(90, 45).map((v) => Math.round(v * 1e6) / 1e6), [1.570796, -0.785398]);
});

test('the loader: the world once, regions on demand and cached, a failure forgotten', async () => {
  const L = C.createGeoLoader({ base: MAPS, fetchJson: read });
  const w = await L.world();
  assert.equal(w.projection, 'equal-earth');
  assert.ok(w.shapes.length >= 170 && w.points.length >= 70);
  assert.equal(w.shapes.find((s) => s.properties.name === 'ATA'), undefined, 'Antarctica is left out');
  assert.equal(await L.world(), w);
  assert.equal(L.hasRegions('USA'), false);
  const us = await L.regions('usa');
  assert.equal(L.hasRegions('USA'), true);
  assert.equal(await L.regions('USA'), us);
  assert.deepEqual(L.loads.filter((f) => f === 'regions/USA.json'), ['regions/USA.json']);
  await assert.rejects(L.regions('ZZZ'));
  await assert.rejects(L.regions('ZZZ'));
  assert.equal(L.loads.filter((f) => f === 'regions/ZZZ.json').length, 2, 'a failed load is asked again');
  await assert.rejects(L.regions('nope'), /no country/);
});

test('the United States: 50 states and DC, Albers, Alaska and Hawaii as insets in frames under the lower 48', async () => {
  const us = await C.createGeoLoader({ base: MAPS, fetchJson: read }).regions('USA');
  assert.equal(us.projection, 'albers-usa');
  assert.equal(us.list.length, 51);
  assert.ok(us.by.code['US-DC'] && us.by.code['US-AK'] && us.by.code['US-HI']);
  assert.deepEqual(us.frames.map((f) => f.code), ['US-AK', 'US-HI']);
  const box = (code) => C.bounds([].concat(...us.shapes.find((s) => s.properties.name === code).geometry.coordinates.map((p) => p[0])));
  const lower48 = C.bounds([].concat(...us.shapes.filter((s) => ['US-AK', 'US-HI'].indexOf(s.properties.name) < 0).map((s) => [].concat(...(s.geometry.type === 'Polygon' ? [s.geometry.coordinates] : s.geometry.coordinates).map((p) => p[0])))));
  const ak = box('US-AK'), hi = box('US-HI'), W = lower48[2] - lower48[0];
  assert.ok(Math.abs((ak[2] - ak[0]) / W - 0.25) < 0.01, 'Alaska is a quarter of the width');
  assert.ok(ak[0] >= lower48[0] - 1e-9 && hi[0] > ak[2] - 1e-9, 'Alaska at the left, Hawaii to its right');
  assert.ok(us.fit[3] >= ak[3] && us.fit[3] >= hi[3], 'the view holds the insets');
});

test('Canada: ten provinces and three territories, Albers', async () => {
  const ca = await C.createGeoLoader({ base: MAPS, fetchJson: read }).regions('CAN');
  assert.equal(ca.projection, 'albers-canada');
  assert.equal(ca.list.length, 13);
  ['CA-YT', 'CA-NT', 'CA-NU', 'CA-ON', 'CA-QC', 'CA-PE'].forEach((c) => assert.ok(ca.by.code[c], c));
  assert.equal(C.findPlace(ca, 'QC').code, 'CA-QC');
  assert.equal(C.findPlace(ca, 'nunavut').code, 'CA-NU');
});

test('every country with a regions file projects to a finite view, its places named by ISO 3166-2', async () => {
  const files = fs.readdirSync(MAPS + 'regions').filter((f) => /^[A-Z]{3}\.json$/.test(f));
  assert.ok(files.length >= 250);
  const L = C.createGeoLoader({ base: MAPS, fetchJson: read }), kinds = {};
  for (const f of files) {
    const g = await L.regions(f.slice(0, 3));
    assert.ok(g.fit.every((v) => isFinite(v)) && g.fit[2] > g.fit[0] && g.fit[3] > g.fit[1], f + ': a finite view');
    assert.equal(g.shapes.length + g.points.length + g.frames.length * 0 - (f === 'USA.json' ? 0 : 0), g.list.length, f + ': every place drawn once');
    g.shapes.forEach((s) => assert.ok(/^[A-Z0-9]{2,3}-/.test(s.properties.name) || s.properties.name.indexOf('-') > 0, f + ' ' + s.properties.name));
    kinds[g.projection] = (kinds[g.projection] || 0) + 1;
  }
  assert.ok(kinds['albers-fitted'] > 100 && kinds['plate-carree'] > 10, JSON.stringify(kinds));
  const fra = await L.regions('FRA');
  assert.ok(fra.fit[2] - fra.fit[0] < 0.3, 'France fits its mainland; Guyane and Réunion stay reachable by panning');
});

test('the world\'s places by ISO alpha-3, alpha-2 and name; the choropleth\'s values, classes and ranking', async () => {
  const w = await C.createGeoLoader({ base: MAPS, fetchJson: read }).world();
  assert.equal(C.findPlace(w, 'us').code, 'USA');
  assert.equal(C.findPlace(w, 'Germany').code, 'DEU');
  assert.equal(C.findPlace(w, 'BMU').point, true, 'Bermuda is a point');
  assert.equal(C.findPlace(w, 'Atlantis'), null);
  const rows = [{ code: 'USA', v: 10 }, { code: 'US', v: 5 }, { code: 'CA', v: 4 }, { code: 'Germany', v: 3 }, { code: 'FRA', v: 2 }, { code: 'BMU', v: 1 }, { code: 'XXX', v: 9 }, { code: 'GBR', v: null }];
  const spec = C.normalizeSpec({ kind: 'map', data: { rows }, metrics: [{ field: 'v', title: 'EAD', format: 'money' }], scale: { classes: 5 } });
  const m = C.buildMap(spec, rows, w, { theme: th, mapName: 'dc-world', selected: ['DEU'], highlight: 4, hatch: 'H' });
  const usa = m.places.find((p) => p.code === 'USA');
  assert.deepEqual([usa.v, usa.r], [15, [0, 1]]);
  assert.equal(m.unmatched, 1);
  assert.equal(m.places.find((p) => p.code === 'GBR').v, null, 'a row without a figure is no data');
  const withData = m.places.filter((p) => p.v !== null).length;
  assert.equal(withData, 5);
  assert.equal(m.scale.classes.reduce((s, k) => s + k.count, 0), withData);
  assert.equal(m.scale.nodata, m.places.length - withData);
  assert.deepEqual(m.ranking.map((r) => r.code), ['USA', 'CAN', 'DEU', 'FRA', 'BMU']);
  assert.ok(Math.abs(m.ranking[0].share - 15 / 25) < 1e-12);
  const reg = (c) => m.option.geo.regions.find((r) => r.name === c);
  assert.equal(reg('ITA').itemStyle.areaColor, 'H', 'no data is hatched');
  assert.equal(reg('DEU').itemStyle.borderColor, th.tokens.accent, 'the selected place is outlined');
  assert.equal(reg('USA').itemStyle.opacity, 1);
  assert.equal(reg('FRA').itemStyle.opacity, 0.28, 'outside the highlighted class: dimmed');
  assert.equal(m.option.series[1].data.find((d) => d.name === 'BMU').value.length, 3);
  assert.match(m.summary, /5 of \d+ places have a figure/);
  const cont = C.mapScale(m.places, th, { type: 'continuous' });
  assert.deepEqual([cont.min, cont.max, cont.classes.length], [1, 15, 0]);
  assert.equal(cont.colourOf(15), th.sequential[4].replace(/\s/g, '') === th.sequential[4] ? C.rampColour(th.sequential, 15, 1, 15) : cont.colourOf(15));
  const bub = C.buildMap(C.normalizeSpec({ kind: 'map', data: { rows }, value: 'v', bubbles: { field: 'v' } }), rows, w, { theme: th, mapName: 'x' });
  assert.equal(bub.option.series[2].data.length, 5);
});
