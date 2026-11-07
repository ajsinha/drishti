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
/* The chart harness (drishti_harness.html): synthetic data for every kind (a 250-day backtest, 509 scenarios, desks, tenors, an exposure
   profile, trades, flows, prices, exposure by country and by every region of a country), the board of cards, the studio, the map in its
   two modes and a grid linked with a chart, an Explain hook and a live tick. The figures are invented. window.__harness is what the
   browser test drives. */
(function () {
  'use strict';
  const D = window.Drishti.elements, C = D.chart, core = D.core;
  const q = new URLSearchParams(location.search);
  let seed = 7;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const gauss = () => { const u = rnd() || 1e-9, v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); };
  const hash = (s) => { let x = 2166136261; for (let i = 0; i < s.length; i++) { x ^= s.charCodeAt(i); x = Math.imul(x, 16777619); } return (x >>> 0) / 4294967295; };
  const iso = (d) => d.toISOString().slice(0, 10);

  /* ---- the data ---- */
  const DAYS = [];
  const day = new Date(Date.UTC(2025, 10, 10));
  while (DAYS.length < 250) { day.setUTCDate(day.getUTCDate() + 1); if (day.getUTCDay() % 6) { DAYS.push(iso(day)); } }
  const backtest = DAYS.map((d, i) => {
    let t = gauss() * 1.05e6 * (1 + 0.25 * Math.sin(i / 30));
    if (rnd() < 0.012) { t -= 2.2e6 * rnd() + 1.9e6; }
    return { date: d, hypothetical: Math.round(t), actual: Math.round(t + gauss() * 2.2e5), var99: Math.round(2.55e6 + 4e5 * Math.sin(i / 45) + gauss() * 6e4) };
  });
  backtest.forEach((r, i) => { if (i % 41 === 17) { r.hypothetical = -r.var99 - 4e5; } });
  const scenarios = Array.from({ length: 509 }, (x, i) => { const z = gauss(); return { id: 'S' + (i + 1), pnl: Math.round(z * 1.05e6 * (z < 0 ? 1 + 0.16 * Math.abs(z) : 1) + 1.2e5) }; });
  const DESKS = ['Rates', 'Credit', 'FX', 'Equities', 'Commodities', 'EM Macro'];
  const deskVar = DESKS.map((d, i) => ({ id: d, desk: d, today: [9.82, 6.41, 3.12, 7.66, 2.48, 4.05][i] * 1e6, yesterday: [9.10, 6.85, 3.30, 7.02, 2.61, 3.62][i] * 1e6 }));
  const REGIONS = ['Americas', 'EMEA', 'APAC'];
  const deskRegion = [];
  DESKS.forEach((d, i) => REGIONS.forEach((r, j) => deskRegion.push({ desk: d, region: r, var: Math.round(deskVar[i].today * [0.5, 0.3, 0.2][j] * (0.8 + 0.4 * rnd())) })));
  const TENORS = ['1M', '3M', '6M', '1Y', '2Y', '3Y', '5Y', '7Y', '10Y', '15Y', '20Y', '30Y'];
  const BASE = [[-3, -5, -8, -12, -20, -24, -30, -22, -18, -9, -6, -4], [1, 2, -3, -6, -9, -14, -18, -12, -8, -3, -2, -1], [4, 6, 5, 3, 2, 1, 0, 0, 0, 0, 0, 0], [0, 0, 1, 2, 2, 1, 1, 0, 0, 0, 0, 0],
    [2, 3, 4, 3, 2, 1, 1, 0, 0, 0, 0, 0], [-2, -3, -5, -7, -6, -4, 3, 5, 4, 2, 1, 0]];
  const dv01 = [];
  DESKS.forEach((d, r) => TENORS.forEach((t, c) => dv01.push({ desk: d, tenor: t, dv01: Math.round((BASE[r][c] + gauss() * 1.4) * 1e3) * 10 })));
  const EXT = [0.08, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30];
  const exposure = EXT.map((t, i) => { const ee = 1e6 * (18 * Math.sqrt(t) * Math.exp(-t / 9) + 3); return { tenor: t, ee: Math.round(ee), pfe: Math.round(ee * (1.75 + 0.04 * i)) }; });
  const varSteps = [['VaR 2026-11-05', 22.4e6], ['New trades', 1.2e6], ['Market moves', 2.1e6], ['Positions closed', -0.8e6], ['Window roll', -0.6e6], ['VaR 2026-11-06', 24.3e6]].map((x) => ({ step: x[0], change: x[1] }));
  const BOOKS = { Rates: ['Govt', 'Swaps'], Credit: ['IG', 'HY'], FX: ['G10', 'EM'], Equities: ['Cash', 'Options'], Commodities: ['Energy', 'Metals'], 'EM Macro': ['LatAm', 'Asia'] };
  const trades = [];
  for (let i = 0; i < 72; i++) {
    const d = DESKS[i % 6], b = BOOKS[d][Math.floor(rnd() * 2)];
    trades.push({ ID: 'T' + (101 + i), DESK: d, BOOK: b, REGION: REGIONS[i % 3], MTM: Math.round((rnd() - 0.4) * 8e6), VAR: Math.round((0.4 + rnd() * 2) * 1e6), NOTIONAL: Math.round((5 + rnd() * 95) * 1e6) });
  }
  const flows = [];
  REGIONS.forEach((r) => DESKS.forEach((d) => { if (rnd() > 0.3) { flows.push({ source: r, target: d, value: Math.round(rnd() * 9e6) }); } }));
  DESKS.forEach((d) => ['Collateralised', 'Uncollateralised'].forEach((c) => flows.push({ source: d, target: c, value: Math.round(rnd() * 6e6) })));
  let px = 100;
  const prices = DAYS.slice(-60).map((d) => { const o = px, c = o * (1 + gauss() * 0.012), hi = Math.max(o, c) * (1 + rnd() * 0.006), lo = Math.min(o, c) * (1 - rnd() * 0.006); px = c; return { date: d, open: o, close: c, low: lo, high: hi }; });
  const BIG = { USA: 9.8e9, CAN: 4.1e9, GBR: 3.2e9, DEU: 2.6e9, FRA: 2.1e9, JPN: 2.4e9, CHN: 1.6e9, AUS: 1.2e9, CHE: 1.1e9, NLD: 0.9e9, BRA: 0.8e9, MEX: 1.0e9, IND: 0.7e9, ITA: 0.75e9, ESP: 0.6e9,
    KOR: 0.55e9, SGP: 0.5e9, SWE: 0.35e9, NOR: 0.3e9, IRL: 0.45e9, ZAF: 0.25e9, ARG: 0.12e9, CHL: 0.2e9, COL: 0.15e9, TUR: 0.18e9, SAU: 0.3e9, ARE: 0.28e9, IDN: 0.22e9, POL: 0.2e9, BEL: 0.33e9,
    AUT: 0.18e9, DNK: 0.24e9, FIN: 0.14e9, NZL: 0.16e9, ISR: 0.19e9, PRT: 0.11e9, GRC: 0.08e9, CZE: 0.07e9, HUN: 0.06e9, ROU: 0.05e9, THA: 0.13e9, MYS: 0.12e9, PHL: 0.07e9, VNM: 0.06e9, EGY: 0.04e9,
    NGA: 0.05e9, KEN: 0.02e9, PER: 0.09e9, QAT: 0.15e9, KWT: 0.08e9, KAZ: 0.03e9, UKR: 0.02e9, MAR: 0.03e9, PAK: 0.02e9, BGD: 0.015e9, LUX: 0.4e9, BMU: 0.2e9, CYM: 0.35e9 };
  const placeRow = (code, total) => { const ead = total * (0.92 + 0.16 * hash(code)); return { code: code, ead: Math.round(ead), pfe: Math.round(ead * (0.32 + 0.2 * hash(code + 'p'))), cpty: Math.max(1, Math.round(Math.pow(ead / 1e6, 0.62) * (0.7 + 0.6 * hash(code + 'c')))), trades: Math.round(ead / 2e6) }; };
  const world = Object.keys(BIG).map((k) => placeRow(k, BIG[k]));
  const H = window.__harness = { charts: {}, events: [], explains: 0, downloads: [], ready: false, data: { backtest: backtest, scenarios: scenarios, trades: trades, world: world } };

  /* the rows of a country's regions: a figure for nine regions in ten, from the shapes' own codes (the stand-in for a server) */
  const maps = C.geoLoader(D.base.replace(/elements\/$/, '') + 'vendor/echarts/maps/');
  function placeData(level) {
    return maps.regions(level).then((g) => g.list.filter((p) => hash(p.code + 'nd') > 0.1).map((p) => placeRow(p.code, (BIG[level] || 0.2e9) / g.list.length * (0.3 + 1.4 * hash(p.code)))));
  }

  /* Explain: the stand-in server's words for a mark or a place */
  function explain(ctx) {
    H.explains++;
    const fmt = C.formatterFor('money');
    const res = { title: (ctx.series || ctx.kind) + (ctx.category !== undefined ? ' · ' + ctx.category : ''), value: ctx.text, scope: ctx.rows.length + ' rows · as of 2026-11-06 · ' + ctx.kind,
      steps: [{ label: 'Rows behind this mark', value: String(ctx.rows.length) }, { label: 'Field', value: ctx.field || '' }],
      parts: ctx.rows.slice(0, 5).map((r, i) => { const v = typeof r[ctx.field] === 'number' ? r[ctx.field] : i + 1; return { label: String(r.ID || r.id || r.desk || r.code || r.date || i), value: v, text: fmt(v) }; }),
      links: [{ label: 'Why did it move?', href: '#' }] };
    return new Promise((resolve) => setTimeout(() => resolve(res), 20));
  }

  /* ---- the specs: one of each kind ---- */
  const vegaBar = C.fromVegaLite({ mark: { type: 'bar', cornerRadiusEnd: 3 }, encoding: { x: { field: 'DESK', type: 'nominal', sort: '-y', title: 'Desk' }, y: { field: 'MTM', type: 'quantitative', title: 'Net MTM' },
    color: { condition: { test: 'datum.MTM < 0', value: '#b3261e' }, value: '#4f7cff' }, tooltip: [{ field: 'DESK' }, { field: 'MTM', format: ',.0f' }] } },
  { rows: DESKS.map((d) => ({ DESK: d, MTM: trades.filter((t) => t.DESK === d).reduce((s, t) => s + t.MTM, 0) })), title: 'Net MTM by desk (Vega-Lite, translated)' });
  const strip = trades.map((t) => ({ desk: t.DESK, pnl1d: Math.round(t.MTM / 20) }));
  const med = DESKS.map((d) => { const xs = strip.filter((r) => r.desk === d).map((r) => r.pnl1d).sort((a, b) => a - b); return { desk: d, median: xs[Math.floor(xs.length / 2)] }; });
  const vegaStrip = C.fromVegaLite({ layer: [
    { data: { values: strip }, mark: { type: 'circle', opacity: 0.6, size: 40 }, encoding: { x: { field: 'pnl1d', type: 'quantitative', title: 'One-day P&L' }, y: { field: 'desk', type: 'nominal', title: null } } },
    { data: { values: med }, mark: { type: 'tick', thickness: 3, size: 22, color: '#d9534f' }, encoding: { x: { field: 'median', type: 'quantitative' }, y: { field: 'desk', type: 'nominal' } } }] }, { title: 'One-day P&L by desk (Vega-Lite layers, translated)' });
  H.vega = { bar: vegaBar, strip: vegaStrip };

  const S = {
    line: { kind: 'line', title: 'Daily P&L against VaR', subtitle: '250 days · zoom by dragging', id: 'date', data: { rows: backtest }, x: { field: 'date', type: 'time' }, y: { format: 'money' },
      series: [{ field: 'hypothetical', name: 'Hypothetical P&L' }, { field: 'actual', name: 'Actual P&L' }, { field: 'var99', name: '−VaR 99%', negate: true, style: 'dashed', tone: 'bad' }],
      marks: [{ when: 'hypothetical < -var99', as: 'point', tone: 'bad', name: 'Exception' }], zoom: { window: [150, 250] }, live: { throttleMs: 50 } },
    area: { kind: 'area', title: 'VaR by region, stacked', id: 'id', data: { rows: deskRegion.map((r, i) => Object.assign({ id: 'DR' + i }, r)) }, x: 'desk', y: { field: 'var', format: 'money' }, series: { by: 'region' }, stack: true },
    bar: { kind: 'bar', title: 'VaR by desk, today and yesterday', id: 'id', data: { rows: deskVar }, x: 'desk', y: { format: 'money' }, series: [{ field: 'today', name: '2026-11-06' }, { field: 'yesterday', name: '2026-11-05', tone: 'muted' }], live: { throttleMs: 50 } },
    'bar-stacked': { kind: 'bar', title: 'VaR by desk and region, stacked', data: { rows: deskRegion }, x: 'desk', y: { field: 'var', format: 'money' }, series: { by: 'region' }, stack: true },
    'bar-horizontal': { kind: 'bar', title: 'VaR today, largest first', data: { rows: deskVar }, x: 'desk', y: { field: 'today', format: 'money' }, orient: 'horizontal', sort: 'value desc', labels: true, legend: 'none' },
    scatter: { kind: 'scatter', title: 'Trades: MTM against VaR', id: 'ID', data: { rows: trades }, x: { field: 'VAR', format: 'money', title: 'VaR' }, y: { field: 'MTM', format: 'money', title: 'MTM' }, colour: 'DESK' },
    bubble: { kind: 'bubble', title: 'Trades sized by notional', id: 'ID', data: { rows: trades }, x: { field: 'VAR', format: 'money' }, y: { field: 'MTM', format: 'money' }, size: 'NOTIONAL', colour: 'REGION' },
    pie: { kind: 'pie', title: 'VaR by desk', data: { rows: deskVar }, category: 'desk', value: { field: 'today', format: 'money' } },
    donut: { kind: 'donut', title: 'Notional by region', data: { rows: trades }, category: 'REGION', value: { field: 'NOTIONAL', format: 'money' } },
    heatmap: { kind: 'heatmap', title: 'DV01 by desk and tenor', data: { rows: dv01 }, x: { field: 'tenor', order: TENORS }, y: 'desk', value: { field: 'dv01', format: 'money' }, scale: { type: 'diverging', mid: 0 } },
    waterfall: { kind: 'waterfall', title: 'P&L attribution', data: { rows: [{ step: 'Opening', v: 4e6 }, { step: 'Carry', v: 0.8e6 }, { step: 'Rates', v: -1.6e6 }, { step: 'FX', v: 0.5e6 }, { step: 'Credit', v: -0.3e6 }, { step: 'Closing', v: 3.4e6 }] }, category: 'step', value: { field: 'v', format: 'money' } },
    histogram: { kind: 'histogram', title: 'Trade MTM', data: { rows: trades }, value: { field: 'MTM', format: 'money' }, bins: 18, annotations: [{ type: 'line', axis: 'x', value: 0, label: 'zero', tone: 'muted' }] },
    boxplot: { kind: 'boxplot', title: 'Trade MTM by desk', data: { rows: trades }, category: 'DESK', value: { field: 'MTM', format: 'money' } },
    gauge: { kind: 'gauge', title: 'VaR limit used', data: { rows: [{ used: 0.78 }] }, value: { field: 'used', title: 'of the limit', format: 'percent' }, min: 0, max: 1, bands: [{ to: 0.7, tone: 'good' }, { to: 0.9, tone: 'warn' }, { to: 1, tone: 'bad' }] },
    treemap: { kind: 'treemap', title: 'Notional by desk and book', data: { rows: trades }, levels: ['DESK', 'BOOK'], value: { field: 'NOTIONAL', format: 'money' } },
    sankey: { kind: 'sankey', title: 'Exposure: region to desk to collateral', data: { rows: flows }, source: 'source', target: 'target', value: { field: 'value', format: 'money' } },
    candlestick: { kind: 'candlestick', title: 'Index, 60 days', data: { rows: prices }, x: 'date', open: 'open', close: 'close', low: 'low', high: 'high', y: { format: 'price2' } },
    'vega-bar': vegaBar.spec,
    'vega-layer': vegaStrip.spec,
    'risk.pnl-distribution': { kind: 'risk.pnl-distribution', title: 'P&L distribution, 509 scenarios', data: { rows: scenarios }, value: { field: 'pnl', format: 'money' }, id: 'id' },
    'risk.backtest': { kind: 'risk.backtest', title: 'Backtest, 250 days', data: { rows: backtest }, x: 'date', pnl: 'hypothetical', actual: 'actual', var: 'var99', id: 'date', zoom: true },
    'risk.exposure-profile': { kind: 'risk.exposure-profile', title: 'Exposure profile · ACME Bank', data: { rows: exposure }, x: 'tenor', ee: 'ee', pfe: 'pfe', limit: 42e6, y: { format: 'money' } },
    'risk.var-change': { kind: 'risk.var-change', title: 'What moved VaR', data: { rows: varSteps } },
    map: { kind: 'map', title: 'Exposure by country of risk', data: { rows: world }, place: 'code', id: 'code',
      metrics: [{ field: 'ead', title: 'Exposure (EAD)', format: 'money' }, { field: 'pfe', title: 'Peak PFE 95%', format: 'money' }, { field: 'cpty', title: 'Counterparties', format: 'integer' }],
      scale: { type: 'quantile', classes: 5 }, drill: 'regions' }
  };
  H.specs = S;
  const samples = {};
  Object.keys(S).forEach((k) => { if (S[k].kind && !samples[S[k].kind]) { samples[S[k].kind] = S[k]; } });

  const stage = document.getElementById('hx-stage'), view = q.get('view') || 'board', density = q.get('density') || 'compact';
  if (q.get('theme') === 'light') { document.documentElement.setAttribute('data-theme', 'light'); }
  const common = { density: density, explain: explain, download: (name, content) => H.downloads.push({ name: name, size: content.length }), placeData: placeData, asOf: '2026-11-06' };

  function card(where, id, spec, o) {
    const el = document.createElement('drishti-chart');
    el.id = 'c-' + id.replace(/\./g, '-');
    el.setAttribute('data-sample', id);
    where.appendChild(el);
    el.options = Object.assign({}, common, o);
    el.spec = spec;
    H.charts[id] = el.chart;
    return el.chart;
  }

  if (view === 'board') {
    stage.className = 'hx-stage hx-board';
    Object.keys(S).forEach((k) => {
      const box = document.createElement('section');
      if (k === 'line' || k === 'map' || k === 'risk.backtest') { box.className = 'hx-wide'; }
      stage.appendChild(box);
      card(box, k, S[k]);
    });
  } else if (view === 'studio') {
    const box = document.createElement('section');
    stage.appendChild(box);
    card(box, 'studio', S[q.get('kind') || 'risk.pnl-distribution'], { mode: 'studio', samples: samples });
  } else if (view === 'map') {
    const box = document.createElement('section');
    stage.appendChild(box);
    card(box, 'map', Object.assign({}, S.map, q.get('level') ? { level: q.get('level') } : {}), { mapMode: q.get('mapMode') || 'ranking', height: 470 });
  } else if (view === 'link') {
    stage.className = 'hx-stage hx-link';
    const g = document.createElement('drishti-grid');
    g.setAttribute('density', density);
    stage.appendChild(g);
    g.options = { columns: [{ field: 'ID', header: 'Trade' }, { field: 'DESK', header: 'Desk' }, { field: 'REGION', header: 'Region' }, { field: 'MTM', header: 'MTM', type: 'number', format: 'compact' },
      { field: 'VAR', header: 'VaR', type: 'number', format: 'compact', additive: false }], rows: trades.slice(), getRowId: (r) => r.ID, rowSelection: 'multiple', title: 'Trades' };
    H.grid = g.grid;
    H.unlinkGrid = C.linkGrid(g.grid, { channel: 'trades' });
    const box = document.createElement('section');
    stage.appendChild(box);
    card(box, 'link', S.scatter, { link: { channel: 'trades' } });
  }

  stage.addEventListener('drishti:rendered', () => {}, true);
  C.CHART_EVENTS.forEach((name) => stage.addEventListener('drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), (e) => {
    if (name !== 'rendered') { H.events.push(name); }
    document.getElementById('hx-log').textContent = H.events.length + ' events · last ' + name + (e.detail && e.detail.kind ? ' (' + e.detail.kind + ')' : '');
  }));

  /* live: a new day on the P&L line, the desks' VaR moved (transactions by id) */
  H.tick = function () {
    const out = {};
    const line = H.charts.line, bar = H.charts.bar;
    if (line) { const last = line.rows[line.rows.length - 1], d = new Date(Date.parse(last.date + 'T00:00:00Z') + 86400000 * (new Date(Date.parse(last.date + 'T00:00:00Z')).getUTCDay() === 5 ? 3 : 1)); out.line = line.apply({ add: [{ date: iso(d), hypothetical: Math.round(gauss() * 1e6), actual: Math.round(gauss() * 1e6), var99: 2.6e6 }] }); }
    if (bar) { out.bar = bar.apply({ update: [{ id: 'Rates', today: Math.round(deskVar[0].today * (0.9 + 0.2 * rnd())) }] }); }
    return out;
  };
  document.getElementById('hx-tick').addEventListener('click', () => H.tick());
  document.getElementById('hx-theme').addEventListener('click', () => core.setTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'terminal' : 'light', { persist: false }));
  document.querySelectorAll('.hx-seg [data-view]').forEach((b) => {
    b.classList.toggle('on', b.dataset.view === view);
    b.addEventListener('click', () => { const p = new URLSearchParams(location.search); p.set('view', b.dataset.view); location.search = p.toString(); });
  });
  document.querySelectorAll('.hx-seg [data-density]').forEach((b) => {
    b.classList.toggle('on', b.dataset.density === density);
    b.addEventListener('click', () => {
      document.querySelectorAll('drishti-chart').forEach((c) => c.setAttribute('density', b.dataset.density));
      document.querySelectorAll('.hx-seg [data-density]').forEach((x) => x.classList.toggle('on', x === b));
    });
  });
  Promise.all(Object.keys(H.charts).map((k) => H.charts[k].ready)).then(() => { H.ready = true; }, (e) => { H.error = String(e); H.ready = true; });
}());
