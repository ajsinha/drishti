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
/* The gallery's map pages (made-up figures): a view's map panels drawn out of the box by drishti-views with drishti-chart's geo part
   (the world shaded by country, the United States by state, Canada by province), and the map helper itself drawing a bubbles layer. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const items = (pairs) => pairs.map((p) => ({ code: p[0], value: p[1], text: p[1].toLocaleString('en-US') }));
  const WORLD = { mode: 'world', highlight: 'GBR', unit: 'USD m',
    shade: { label: 'Exposure', items: items([['USA', 4200], ['CAN', 1800], ['GBR', 1500], ['DEU', 1300], ['FRA', 950], ['JPN', 880], ['CHE', 640], ['IND', 600], ['BRA', 410], ['AUS', 370], ['SGP', 220], ['ZAF', 140], ['MEX', 120]]) },
    link: { kind: 'country', template: 'country/{iso3}', codes: ['USA', 'GBR', 'DEU', 'FRA', 'JPN'] } };
  const US = { mode: 'region', region: 'USA', shade: { label: 'Orders', items: items([['US-CA', 910], ['US-TX', 720], ['US-NY', 650], ['US-FL', 400], ['US-IL', 380], ['US-WA', 260], ['US-MA', 240], ['US-GA', 200], ['US-AK', 40], ['US-HI', 35]]) } };
  const CA = { mode: 'region', region: 'CAN', shade: { label: 'Orders', items: items([['CA-ON', 500], ['CA-QC', 350], ['CA-BC', 300], ['CA-AB', 220], ['CA-MB', 60], ['CA-NS', 45]]) } };
  const HUBS = { mode: 'world', unit: 'USD bn', shade: WORLD.shade,                          // contract 1.1: points drawn as bubbles
    points: [['New York', 40.71, -74.01, 610], ['London', 51.51, -0.13, 420], ['Tokyo', 35.68, 139.69, 230], ['Hong Kong', 22.32, 114.17, 210], ['Singapore', 1.35, 103.82, 180],
      ['Frankfurt', 50.11, 8.68, 120], ['Zurich', 47.37, 8.54, 95], ['Toronto', 43.65, -79.38, 80], ['Sydney', -33.87, 151.21, 60], ['Sao Paulo', -23.55, -46.63, 45], ['Mumbai', 19.08, 72.88, 40]]
      .map((x) => ({ label: x[0], lat: x[1], lon: x[2], value: x[3], text: x[3] + ' bn', link: { kind: 'hub', id: x[0], mnemonic: null } })) };
  const view = (which) => ({
    contract: which === 'hubs' ? '1.1' : '1.0', ref: { kind: 'book', id: 'GLOBAL-1' }, title: { pill: 'Book · Exposure', id: 'GLOBAL-1' },
    panels: (which === 'all' ? ['world', 'us', 'ca'] : [which]).map((id) => ({ id: id, kind: 'map', inferred: false, empty: false,
      title: { world: 'Exposure by country', us: 'Orders by state', ca: 'Orders by province', hubs: 'Trading hubs, sized by daily volume' }[id], data: { world: WORLD, us: US, ca: CA, hubs: HUBS }[id] })),
    keys: [{ key: 'F9', label: 'Raw JSON', action: 'raw' }] });

  demos.push({
    id: 'view-maps', library: 'drishti-views', kind: 'function', title: 'Maps in a view', about: 'drishti_views-charts.js', types: ['PaintOptions'],
    events: ['link', 'key'], keys: [], ag: false, sutra: () => null,
    summary: 'A map panel draws out of the box: drishti-views loads drishti-chart\'s geo part and the vendored Natural Earth shapes (public domain) the first time a view has a map, and only the files that map needs. The world by country, a country by its first-level regions (states, provinces); a click on a place opens its link.',
    extra: [{ name: 'map', control: { kind: 'select', values: ['all', 'world', 'us', 'ca', 'hubs'] }, desc: 'Which map panels the view has: the world, the United States, Canada, all three, or trading hubs as points (contract 1.1).' }],
    values: { map: 'all' },
    examples: [{ title: 'The world', values: { map: 'world' } }, { title: 'United States', values: { map: 'us' } }, { title: 'Canada', values: { map: 'ca' } }, { title: 'Points as bubbles', values: { map: 'hubs' } }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, box = ctx.h(doc, 'div', { cls: 'gx-fn' });
      host.appendChild(box);
      const handle = ctx.E.views.paint(box, view(values.map || 'all'), { density: 'compact', shadow: false });
      ['link', 'key'].forEach((n) => handle.on(n, (e) => ctx.log(n, JSON.stringify(e.detail))));
      return { target: box, element: box, ready: handle.ready, destroy: () => { handle.dispose(); box.remove(); } };
    },
    code: () => ({ js: "Drishti.elements.views.paint(document.getElementById('book'), {\n  contract: '1.0', ref: { kind: 'book', id: 'GLOBAL-1' }, title: { id: 'GLOBAL-1' },\n  panels: [{ id: 'us', kind: 'map', title: 'Orders by state', data: { mode: 'region', region: 'USA',\n    shade: { label: 'Orders', items: [{ code: 'US-CA', value: 910 }, { code: 'TX', value: 720 }] } } }]\n});", element: '<div id="book"></div>' })
  });

  const BUBBLES = {
    world: [{ lon: -74.0, lat: 40.7, value: 420, name: 'New York' }, { lon: -0.1, lat: 51.5, value: 300, name: 'London' }, { lon: 139.7, lat: 35.7, value: 180, name: 'Tokyo' },
      { lon: 103.8, lat: 1.35, value: 120, name: 'Singapore' }, { lon: 8.7, lat: 50.1, value: 90, name: 'Frankfurt' }, { lon: -79.4, lat: 43.7, value: 80, name: 'Toronto' }, { lon: 151.2, lat: -33.9, value: 40, name: 'Sydney' }],
    us: [{ lon: -74.0, lat: 40.7, value: 650, name: 'New York' }, { lon: -87.6, lat: 41.9, value: 380, name: 'Chicago' }, { lon: -118.2, lat: 34.1, value: 300, name: 'Los Angeles' },
      { lon: -95.4, lat: 29.8, value: 210, name: 'Houston' }, { lon: -122.3, lat: 47.6, value: 150, name: 'Seattle' }, { lon: -149.9, lat: 61.2, value: 40, name: 'Anchorage' }, { lon: -157.9, lat: 21.3, value: 30, name: 'Honolulu' }]
  };

  demos.push({
    id: 'geo-helper', library: 'drishti-chart', kind: 'function', title: 'The map helper: bubbles', about: 'drishti_geohelper.js', types: ['GeoMapConfig'],
    events: [], keys: [], ag: false, sutra: () => null,
    summary: 'drishti-chart\'s geo part, loaded with loadPart(\'drishti-chart\', \'geo\'): the world and every country\'s first-level regions from the vendored shapes, lookups by ISO code or name, the projection and an ECharts option: a choropleth and a layer of bubbles at places or at a longitude and latitude (Alaska and Hawaii in their insets).',
    extra: [{ name: 'level', control: { kind: 'select', values: ['world', 'us'] }, desc: 'The world, or the United States by state.' }],
    values: { level: 'world' },
    examples: [{ title: 'Financial centres', values: { level: 'world' } }, { title: 'US offices', values: { level: 'us' } }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, us = values.level === 'us';
      const el = ctx.h(doc, 'div', { cls: 'gx-fn', attrs: { role: 'img', 'aria-label': us ? 'Offices in the United States, sized by headcount' : 'Financial centres, sized by trades' } });
      el.style.height = '420px';
      host.appendChild(el);
      let chart = null;
      const core = ctx.E.core, ready = core.loadPart('drishti-chart', 'geo').then((P) => {
        const G = P.geoHelper({ base: core.staticUrl('vendor/echarts/maps/') });
        return (us ? G.region('USA') : G.world()).then((geo) => {
          chart = doc.defaultView.echarts.init(el, null, { renderer: 'svg' });
          chart.setOption(G.option({ level: us ? 'region' : 'world', geo: geo, t: ctx.E.chart.panelTokens(el), bubbles: BUBBLES[us ? 'us' : 'world'], unit: us ? 'people' : 'trades',
            width: el.clientWidth || 800, height: 420 }));
        });
      });
      return { target: el, element: el, ready: ready, destroy: () => { if (chart) { chart.dispose(); } el.remove(); } };
    },
    code: () => ({ js: "const core = Drishti.elements.core;\ncore.loadPart('drishti-chart', 'geo').then(async (P) => {\n  const G = P.geoHelper(), us = await G.region('USA');\n  echarts.init(document.getElementById('offices'), null, { renderer: 'svg' }).setOption(G.option({ level: 'region', geo: us,\n    bubbles: [{ lon: -74.0, lat: 40.7, value: 650, name: 'New York' }, { lon: -149.9, lat: 61.2, value: 40, name: 'Anchorage' }] }));\n});", element: '<div id="offices" style="height: 420px"></div>' })
  });
}(typeof window !== 'undefined' ? window : null));
