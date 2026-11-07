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
/* The gallery's sample data and stand-in server, the same synthetic figures as the libraries' fixture harnesses (drishti-grid's and
   drishti-chart's drishti_harness.js): trades by desk, book, trader, region and currency (MTM additive, VaR and ES not), a 250-day
   backtest, 509 scenarios, desks, tenors, an exposure profile, flows, prices and exposure by country and by region. The stand-in server
   answers what a Drishti server would (a diversified VaR of each pivot cell's own trades, never a sum; Explain; a country's region rows)
   inside the page, so every library without a server runs from static files. The figures are invented and seeded: every load draws the
   same. Drishti.gallery.data is what the demos read. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const G = (NS.gallery = NS.gallery || {});

  function random(seed) {
    let s = seed;
    const rnd = () => { s = (s * 16807) % 2147483647; return s / 2147483647; };
    const gauss = () => { const u = rnd() || 1e-9, v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); };
    return { rnd: rnd, gauss: gauss };
  }
  const hash = (s) => { let x = 2166136261; for (let i = 0; i < s.length; i++) { x ^= s.charCodeAt(i); x = Math.imul(x, 16777619); } return (x >>> 0) / 4294967295; };
  const iso = (d) => d.toISOString().slice(0, 10);
  const RHO = 0.32;

  /* ---- trades, as the grid's harness makes them ---- */
  const DESK_BOOKS = { Rates: ['Govt Bonds', 'Swaps', 'Inflation'], Credit: ['IG Corp', 'HY Corp', 'CDS Index'], FX: ['G10 Spot', 'EM Forwards'], Equities: ['Cash EQ', 'Index Options', 'Delta One'] };
  const CCY = { Americas: ['USD', 'CAD'], EMEA: ['EUR', 'GBP', 'CHF'], APAC: ['JPY', 'AUD'] };
  const TRADERS = ['A. Rao', 'M. Chen', 'J. Smith', 'L. Duval'];

  /**
   * Synthetic trades (ID, DESK, BOOK, TRADER, REGION, CCY, MTM, VAR, ES, TRADES), seeded.
   * @param {number} n how many
   * @returns {Object[]}
   */
  function trades(n) {
    const r = random(11), out = [];
    let i = 0;
    while (out.length < n) {
      Object.keys(DESK_BOOKS).forEach((d, di) => DESK_BOOKS[d].forEach((b) => Object.keys(CCY).forEach((reg) => CCY[reg].forEach((c) => {
        if (out.length >= n) { return; }
        const scale = [1.8, 1.1, 0.7, 1.3][di] * (reg === 'APAC' ? 0.55 : 1) * (c === 'CHF' || c === 'CAD' ? 0.5 : 1);
        out.push({ ID: 'T' + (++i), DESK: d, BOOK: b, TRADER: TRADERS[Math.floor(r.rnd() * 4)], REGION: reg, CCY: c,
          MTM: Math.round((r.rnd() - 0.38) * 9.5e6 * scale), VAR: Math.round((0.6 + r.rnd() * 1.7) * 1e6 * scale), TRADES: Math.round(40 + r.rnd() * 900) });
      }))));
    }
    out.forEach((t) => { t.ES = Math.round(t.VAR * 1.19); });
    return out;
  }

  /** The grid's columns over the trades (the harness's): MTM additive, VaR and ES not. */
  const COLUMNS = [
    { field: 'ID', header: 'Trade', enableRowGroup: false, enablePivot: false, width: 90 },
    { field: 'DESK', header: 'Desk', group: 'Hierarchy' }, { field: 'BOOK', header: 'Book', group: 'Hierarchy' }, { field: 'TRADER', header: 'Trader', group: 'Hierarchy' },
    { field: 'REGION', header: 'Region', group: 'Market' }, { field: 'CCY', header: 'Currency', group: 'Market', width: 96 },
    { field: 'MTM', header: 'MTM', type: 'number', format: 'compact', group: 'Measures', description: 'mark to market, additive' },
    { field: 'VAR', header: 'VaR 99% 1d', type: 'number', format: 'compact', additive: false, measure: 'var(99, 1d)', group: 'Measures', description: 'value at risk: not additive' },
    { field: 'ES', header: 'ES 97.5% 1d', type: 'number', format: 'compact', additive: false, measure: 'es(97.5, 1d)', group: 'Measures', description: 'expected shortfall: not additive' },
    { field: 'TRADES', header: 'Trades', type: 'number', format: 'amount0', group: 'Measures' }];

  const diversified = (rows) => { let s2 = 0, s1 = 0; rows.forEach((x) => { s2 += x.VAR * x.VAR; s1 += x.VAR; }); return Math.sqrt((1 - RHO) * s2 + RHO * s1 * s1); };

  /**
   * The stand-in server's pivot: each cell's VaR and ES from its own trades (diversified), additive figures as sums.
   * @param {Object} grid drishti-grid's bundle (Drishti.elements.grid)
   * @param {Object[]} data the trades
   * @param {number} [delay] milliseconds
   * @returns {function({view: Object}, AbortSignal, Object[]): Promise<Object>}
   */
  function serverPivot(grid, data, delay) {
    return function (body, signal, rows) {
      const src = rows || data, want = body.view.values;
      return new Promise((resolve, reject) => setTimeout(() => {
        if (signal && signal.aborted) { reject(new Error('aborted')); return; }
        const local = want.filter((w) => !/^(var|es)\(/.test(w.measure));
        const view = Object.assign({}, body.view, { values: local.concat([{ measure: 'sum(__V2)', as: '__v2' }, { measure: 'sum(__V1)', as: '__v1' }]) });
        const a = grid.pivotLocal(src.map((x) => Object.assign({}, x, { __V2: x.VAR * x.VAR, __V1: x.VAR })), view);
        Object.keys(a.cells).forEach((k) => {
          const c = a.cells[k], v = Math.sqrt((1 - RHO) * c.values.__v2 + RHO * c.values.__v1 * c.values.__v1);
          delete c.values.__v2; delete c.values.__v1;
          want.forEach((w) => { if (/^var\(/.test(w.measure)) { c.values[w.as || w.measure] = v; } else if (/^es\(/.test(w.measure)) { c.values[w.as || w.measure] = v * 1.19; } });
        });
        a.measures = want.map((w) => ({ label: w.as || w.measure, server: /^(var|es)\(/.test(w.measure) }));
        a.computedBy = 'server';
        resolve(a);
      }, delay === undefined ? 40 : delay));
    };
  }

  /**
   * The stand-in server's Explain of a grid cell: the parts by the next level down, and for a non-additive figure the diversification.
   * @param {Object} grid drishti-grid's bundle
   * @param {Object[]} data
   * @returns {function(Object): Promise<Object>}
   */
  function gridExplain(grid, data) {
    return function (ctx) {
      const rows = ctx.kind === 'pivot' ? data.filter((x) => Object.keys(ctx.coords || {}).every((f) => ctx.coords[f] === '*others*' || String(x[f]) === ctx.coords[f]))
        : ctx.kind === 'leaf' ? [ctx.data] : data;
      const fmt = (x) => grid.formatValue(x, { type: 'number', format: 'compact' });
      const nadd = ctx.additive === false;
      const by = ['DESK', 'BOOK', 'CCY', 'TRADER'].find((f) => !(ctx.coords && f in ctx.coords)) || 'TRADER';
      const groups = {};
      rows.forEach((x) => { (groups[x[by]] = groups[x[by]] || []).push(x); });
      const label = ctx.measure || ctx.header || '';
      const field = /ES/.test(label) ? 'ES' : /VaR/.test(label) ? 'VAR' : /Trades/.test(label) ? 'TRADES' : 'MTM';
      const fig = (rs) => (field === 'VAR' ? diversified(rs) : field === 'ES' ? diversified(rs) * 1.19 : rs.reduce((s, x) => s + x[field], 0));
      const parts = Object.keys(groups).map((k) => ({ label: k, value: fig(groups[k]) })).sort((a, b) => Math.abs(b.value) - Math.abs(a.value)).map((p) => Object.assign(p, { text: fmt(p.value) }));
      const sum = parts.reduce((s, p) => s + p.value, 0), total = typeof ctx.value === 'number' ? ctx.value : fig(rows);
      const res = { title: label, value: ctx.text, scope: rows.length + ' trades · as of 2026-11-06', partsTitle: (nadd ? 'Stand-alone by ' : 'By ') + by, parts: parts.slice(0, 6),
        steps: nadd ? [{ label: 'Scenario P&L vectors of the ' + rows.length + ' trades added scenario by scenario', value: '' }, { label: label + ' of the summed vector', value: fmt(total) },
          { label: 'Sum of the parts (shown, not used)', value: fmt(sum) }, { label: 'Diversification', value: fmt(total - sum) }]
          : [{ label: 'Sum of ' + rows.length + ' trades', value: fmt(total) }, { label: 'Additive: every subtotal is the sum of what it holds', value: '✓' }],
        trend: Array.from({ length: 20 }, (x, i) => Math.abs(total) * (0.85 + 0.3 * Math.sin(i / 3))), limit: Math.abs(total) * 1.3,
        links: [{ label: 'Why did it move?', href: '#' }] };
      return new Promise((resolve) => setTimeout(() => resolve(res), 30));
    };
  }

  /* ---- the chart harness's series ---- */
  function chartData() {
    const r = random(7), gauss = r.gauss, rnd = r.rnd;
    const DAYS = [];
    const day = new Date(Date.UTC(2025, 10, 10));
    while (DAYS.length < 250) { day.setUTCDate(day.getUTCDate() + 1); if (day.getUTCDay() % 6) { DAYS.push(iso(day)); } }
    const backtest = DAYS.map((d, i) => {
      let t = gauss() * 1.05e6 * (1 + 0.25 * Math.sin(i / 30));
      if (rnd() < 0.012) { t -= 2.2e6 * rnd() + 1.9e6; }
      return { date: d, hypothetical: Math.round(t), actual: Math.round(t + gauss() * 2.2e5), var99: Math.round(2.55e6 + 4e5 * Math.sin(i / 45) + gauss() * 6e4) };
    });
    backtest.forEach((x, i) => { if (i % 41 === 17) { x.hypothetical = -x.var99 - 4e5; } });
    const scenarios = Array.from({ length: 509 }, (x, i) => { const z = gauss(); return { id: 'S' + (i + 1), pnl: Math.round(z * 1.05e6 * (z < 0 ? 1 + 0.16 * Math.abs(z) : 1) + 1.2e5) }; });
    const DESKS = ['Rates', 'Credit', 'FX', 'Equities', 'Commodities', 'EM Macro'];
    const deskVar = DESKS.map((d, i) => ({ id: d, desk: d, today: [9.82, 6.41, 3.12, 7.66, 2.48, 4.05][i] * 1e6, yesterday: [9.10, 6.85, 3.30, 7.02, 2.61, 3.62][i] * 1e6 }));
    const REGIONS = ['Americas', 'EMEA', 'APAC'];
    const deskRegion = [];
    DESKS.forEach((d, i) => REGIONS.forEach((g, j) => deskRegion.push({ id: 'DR' + deskRegion.length, desk: d, region: g, var: Math.round(deskVar[i].today * [0.5, 0.3, 0.2][j] * (0.8 + 0.4 * rnd())) })));
    const TENORS = ['1M', '3M', '6M', '1Y', '2Y', '3Y', '5Y', '7Y', '10Y', '15Y', '20Y', '30Y'];
    const BASE = [[-3, -5, -8, -12, -20, -24, -30, -22, -18, -9, -6, -4], [1, 2, -3, -6, -9, -14, -18, -12, -8, -3, -2, -1], [4, 6, 5, 3, 2, 1, 0, 0, 0, 0, 0, 0], [0, 0, 1, 2, 2, 1, 1, 0, 0, 0, 0, 0],
      [2, 3, 4, 3, 2, 1, 1, 0, 0, 0, 0, 0], [-2, -3, -5, -7, -6, -4, 3, 5, 4, 2, 1, 0]];
    const dv01 = [];
    DESKS.forEach((d, i) => TENORS.forEach((t, c) => dv01.push({ desk: d, tenor: t, dv01: Math.round((BASE[i][c] + gauss() * 1.4) * 1e3) * 10 })));
    const EXT = [0.08, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30];
    const exposure = EXT.map((t, i) => { const ee = 1e6 * (18 * Math.sqrt(t) * Math.exp(-t / 9) + 3); return { tenor: t, ee: Math.round(ee), pfe: Math.round(ee * (1.75 + 0.04 * i)) }; });
    const varSteps = [['VaR 2026-11-05', 22.4e6], ['New trades', 1.2e6], ['Market moves', 2.1e6], ['Positions closed', -0.8e6], ['Window roll', -0.6e6], ['VaR 2026-11-06', 24.3e6]].map((x) => ({ step: x[0], change: x[1] }));
    const BOOKS = { Rates: ['Govt', 'Swaps'], Credit: ['IG', 'HY'], FX: ['G10', 'EM'], Equities: ['Cash', 'Options'], Commodities: ['Energy', 'Metals'], 'EM Macro': ['LatAm', 'Asia'] };
    const book = [];
    for (let i = 0; i < 72; i++) {
      const d = DESKS[i % 6], b = BOOKS[d][Math.floor(rnd() * 2)];
      book.push({ ID: 'T' + (101 + i), DESK: d, BOOK: b, REGION: REGIONS[i % 3], MTM: Math.round((rnd() - 0.4) * 8e6), VAR: Math.round((0.4 + rnd() * 2) * 1e6), NOTIONAL: Math.round((5 + rnd() * 95) * 1e6) });
    }
    const flows = [];
    REGIONS.forEach((g) => DESKS.forEach((d) => { if (rnd() > 0.3) { flows.push({ source: g, target: d, value: Math.round(rnd() * 9e6) }); } }));
    DESKS.forEach((d) => ['Collateralised', 'Uncollateralised'].forEach((c) => flows.push({ source: d, target: c, value: Math.round(rnd() * 6e6) })));
    let px = 100;
    const prices = DAYS.slice(-60).map((d) => { const o = px, c = o * (1 + gauss() * 0.012), hi = Math.max(o, c) * (1 + rnd() * 0.006), lo = Math.min(o, c) * (1 - rnd() * 0.006); px = c; return { date: d, open: o, close: c, low: lo, high: hi }; });
    return { DAYS: DAYS, DESKS: DESKS, TENORS: TENORS, backtest: backtest, scenarios: scenarios, deskVar: deskVar, deskRegion: deskRegion, dv01: dv01, exposure: exposure,
      varSteps: varSteps, trades: book, flows: flows, prices: prices, gauss: gauss, rnd: rnd };
  }

  const BIG = { USA: 9.8e9, CAN: 4.1e9, GBR: 3.2e9, DEU: 2.6e9, FRA: 2.1e9, JPN: 2.4e9, CHN: 1.6e9, AUS: 1.2e9, CHE: 1.1e9, NLD: 0.9e9, BRA: 0.8e9, MEX: 1.0e9, IND: 0.7e9, ITA: 0.75e9, ESP: 0.6e9,
    KOR: 0.55e9, SGP: 0.5e9, SWE: 0.35e9, NOR: 0.3e9, IRL: 0.45e9, ZAF: 0.25e9, ARG: 0.12e9, CHL: 0.2e9, COL: 0.15e9, TUR: 0.18e9, SAU: 0.3e9, ARE: 0.28e9, IDN: 0.22e9, POL: 0.2e9, BEL: 0.33e9,
    AUT: 0.18e9, DNK: 0.24e9, FIN: 0.14e9, NZL: 0.16e9, ISR: 0.19e9, PRT: 0.11e9, GRC: 0.08e9, CZE: 0.07e9, HUN: 0.06e9, ROU: 0.05e9, THA: 0.13e9, MYS: 0.12e9, PHL: 0.07e9, VNM: 0.06e9, EGY: 0.04e9,
    NGA: 0.05e9, KEN: 0.02e9, PER: 0.09e9, QAT: 0.15e9, KWT: 0.08e9, KAZ: 0.03e9, UKR: 0.02e9, MAR: 0.03e9, PAK: 0.02e9, BGD: 0.015e9, LUX: 0.4e9, BMU: 0.2e9, CYM: 0.35e9 };
  const placeRow = (code, total) => { const ead = total * (0.92 + 0.16 * hash(code)); return { code: code, ead: Math.round(ead), pfe: Math.round(ead * (0.32 + 0.2 * hash(code + 'p'))), cpty: Math.max(1, Math.round(Math.pow(ead / 1e6, 0.62) * (0.7 + 0.6 * hash(code + 'c')))), trades: Math.round(ead / 2e6) }; };
  const world = Object.keys(BIG).map((k) => placeRow(k, BIG[k]));

  /**
   * A level's rows for a drilled map: a figure for nine regions in ten, from the shapes' own codes (the stand-in for a server).
   * @param {Object} maps a drishti-chart geoLoader
   * @returns {function(string): Promise<Object[]>}
   */
  function placeData(maps) {
    return (level) => maps.regions(level).then((g) => g.list.filter((p) => hash(p.code + 'nd') > 0.1).map((p) => placeRow(p.code, (BIG[level] || 0.2e9) / g.list.length * (0.3 + 1.4 * hash(p.code)))));
  }

  /**
   * The stand-in server's Explain of a chart mark or a place.
   * @param {function(*): string} fmt a money formatter
   * @returns {function(Object): Promise<Object>}
   */
  function chartExplain(fmt) {
    return (ctx) => new Promise((resolve) => setTimeout(() => resolve({ title: (ctx.series || ctx.kind) + (ctx.category !== undefined ? ' · ' + ctx.category : ''), value: ctx.text,
      scope: ctx.rows.length + ' rows · as of 2026-11-06 · ' + ctx.kind,
      steps: [{ label: 'Rows behind this mark', value: String(ctx.rows.length) }, { label: 'Field', value: ctx.field || '' }],
      parts: ctx.rows.slice(0, 5).map((x, i) => { const v = typeof x[ctx.field] === 'number' ? x[ctx.field] : i + 1; return { label: String(x.ID || x.id || x.desk || x.code || x.date || i), value: v, text: fmt(v) }; }),
      links: [{ label: 'Why did it move?', href: '#' }] }), 20));
  }

  let chartCache = null;
  G.data = {
    trades: trades, COLUMNS: COLUMNS, serverPivot: serverPivot, gridExplain: gridExplain, diversified: diversified,
    /** @returns {Object} the chart harness's series (made once) */
    chart: () => (chartCache = chartCache || chartData()),
    world: world, placeData: placeData, chartExplain: chartExplain, hash: hash, random: random
  };
}(typeof window !== 'undefined' ? window : globalThis));
