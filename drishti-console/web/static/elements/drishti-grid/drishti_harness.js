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
/* The fixture harness (drishti_harness.html): synthetic trades (desks, books, traders, regions, currencies; MTM additive, VaR and ES not),
   a stand-in "server" in the page for the non-additive figures (a diversified VaR of each cell's own trades, never a sum) and for the
   server-side rows, an Explain hook, a live tick, and the grid as <drishti-grid> in the layout, density and theme the address asks for.
   The figures are invented. window.__harness is what the browser test drives. */
(function () {
  'use strict';
  const D = window.Drishti.elements, G = D.grid, core = D.core;
  const q = new URLSearchParams(location.search);
  const RHO = 0.32, DELAY = +(q.get('delay') || 40);
  let seed = 11;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };

  const DESKS = { Rates: ['Govt Bonds', 'Swaps', 'Inflation'], Credit: ['IG Corp', 'HY Corp', 'CDS Index'], FX: ['G10 Spot', 'EM Forwards'], Equities: ['Cash EQ', 'Index Options', 'Delta One'] };
  const CCY = { Americas: ['USD', 'CAD'], EMEA: ['EUR', 'GBP', 'CHF'], APAC: ['JPY', 'AUD'] };
  const TRADERS = ['A. Rao', 'M. Chen', 'J. Smith', 'L. Duval'];

  function trades(n) {
    const out = [];
    let i = 0;
    while (out.length < n) {
      Object.keys(DESKS).forEach((d, di) => DESKS[d].forEach((b) => Object.keys(CCY).forEach((r) => CCY[r].forEach((c) => {
        if (out.length >= n) { return; }
        const scale = [1.8, 1.1, 0.7, 1.3][di] * (r === 'APAC' ? 0.55 : 1) * (c === 'CHF' || c === 'CAD' ? 0.5 : 1);
        out.push({ ID: 'T' + (++i), DESK: d, BOOK: b, TRADER: TRADERS[Math.floor(rnd() * 4)], REGION: r, CCY: c,
          MTM: Math.round((rnd() - 0.38) * 9.5e6 * scale), VAR: Math.round((0.6 + rnd() * 1.7) * 1e6 * scale), TRADES: Math.round(40 + rnd() * 900) });
      }))));
    }
    out.forEach((t) => { t.ES = Math.round(t.VAR * 1.19); });
    return out;
  }

  const COLUMNS = [
    { field: 'ID', header: 'Trade', enableRowGroup: false, enablePivot: false, width: 90 },
    { field: 'DESK', header: 'Desk', group: 'Hierarchy' }, { field: 'BOOK', header: 'Book', group: 'Hierarchy' }, { field: 'TRADER', header: 'Trader', group: 'Hierarchy' },
    { field: 'REGION', header: 'Region', group: 'Market' }, { field: 'CCY', header: 'Currency', group: 'Market', width: 96 },
    { field: 'MTM', header: 'MTM', type: 'number', format: 'compact', group: 'Measures', description: 'mark to market, additive' },
    { field: 'VAR', header: 'VaR 99% 1d', type: 'number', format: 'compact', additive: false, measure: 'var(99, 1d)', group: 'Measures', description: 'value at risk: not additive' },
    { field: 'ES', header: 'ES 97.5% 1d', type: 'number', format: 'compact', additive: false, measure: 'es(97.5, 1d)', group: 'Measures', description: 'expected shortfall: not additive' },
    { field: 'TRADES', header: 'Trades', type: 'number', format: 'amount0', group: 'Measures' }];

  const n = +(q.get('rows') || 231);
  const data = trades(n);
  const diversified = (rows) => { let s2 = 0, s1 = 0; rows.forEach((r) => { s2 += r.VAR * r.VAR; s1 += r.VAR; }); return Math.sqrt((1 - RHO) * s2 + RHO * s1 * s1); };
  const log = document.getElementById('hx-log');
  const H = window.__harness = { data: data, columns: COLUMNS, asked: 0, events: [], downloads: [], charts: [] };

  /* the stand-in server: each cell's VaR and ES from its own trades (diversified), additive figures as sums */
  function serverPivot(body, signal, rows) {
    H.asked++;
    const src = rows || data, want = body.view.values;
    return new Promise((resolve, reject) => setTimeout(() => {
      if (signal && signal.aborted) { reject(new Error('aborted')); return; }
      const local = want.filter((w) => !/^(var|es)\(/.test(w.measure));
      const view = Object.assign({}, body.view, { values: local.concat([{ measure: 'sum(__V2)', as: '__v2' }, { measure: 'sum(__V1)', as: '__v1' }]) });
      const a = G.pivotLocal(src.map((r) => Object.assign({}, r, { __V2: r.VAR * r.VAR, __V1: r.VAR })), view);
      Object.keys(a.cells).forEach((k) => {
        const c = a.cells[k], v = Math.sqrt((1 - RHO) * c.values.__v2 + RHO * c.values.__v1 * c.values.__v1);
        delete c.values.__v2; delete c.values.__v1;
        want.forEach((w) => { if (/^var\(/.test(w.measure)) { c.values[w.as || w.measure] = v; } else if (/^es\(/.test(w.measure)) { c.values[w.as || w.measure] = v * 1.19; } });
      });
      a.measures = want.map((w) => ({ label: w.as || w.measure, server: /^(var|es)\(/.test(w.measure) }));
      a.computedBy = 'server';
      resolve(a);
    }, DELAY));
  }

  /* Explain: how a figure was made (the stand-in's words and figures) */
  function membersOf(ctx) {
    if (ctx.kind === 'pivot') { const k = Object.keys(ctx.coords || {}); return data.filter((r) => k.every((f) => ctx.coords[f] === '*others*' || String(r[f]) === ctx.coords[f])); }
    if (ctx.kind === 'leaf') { return [ctx.data]; }
    return data;
  }
  function explain(ctx) {
    const rows = membersOf(ctx), fmt = (x) => G.formatValue(x, { type: 'number', format: 'compact' });
    const nadd = ctx.additive === false, depth = ctx.coords ? Object.keys(ctx.coords).length : 0;
    const by = ['DESK', 'BOOK', 'CCY', 'TRADER'].find((f) => !(ctx.coords && f in ctx.coords)) || 'TRADER';
    const groups = {};
    rows.forEach((r) => { (groups[r[by]] = groups[r[by]] || []).push(r); });
    const field = /ES/.test(ctx.measure || ctx.header) ? 'ES' : /VaR/.test(ctx.measure || ctx.header) ? 'VAR' : /Trades/.test(ctx.measure || ctx.header) ? 'TRADES' : 'MTM';
    const fig = (rs) => (field === 'VAR' ? diversified(rs) : field === 'ES' ? diversified(rs) * 1.19 : rs.reduce((s, r) => s + r[field], 0));
    const parts = Object.keys(groups).map((k) => ({ label: k, value: fig(groups[k]) })).sort((a, b) => Math.abs(b.value) - Math.abs(a.value)).map((p) => Object.assign(p, { text: fmt(p.value) }));
    const sum = parts.reduce((s, p) => s + p.value, 0), total = typeof ctx.value === 'number' ? ctx.value : fig(rows);
    const scope = ctx.coords && depth ? Object.keys(ctx.coords).map((k) => k + ' = ' + ctx.coords[k]).join(', ') : ctx.kind === 'leaf' ? 'trade ' + ctx.rowId : 'everything';
    const res = { title: ctx.measure || ctx.header, value: ctx.text, scope: scope + ' · as of 2026-11-06 · ' + rows.length + ' trades',
      partsTitle: (nadd ? 'Stand-alone by ' : 'By ') + by, parts: parts.slice(0, 6),
      steps: nadd ? [{ label: '509 scenario P&L vectors of the ' + rows.length + ' trades added scenario by scenario', value: '' }, { label: (ctx.measure || ctx.header) + ' of the summed vector', value: fmt(total) },
        { label: 'Sum of the ' + parts.length + ' parts by ' + by + ' (shown, not used)', value: fmt(sum) }, { label: 'Diversification', value: fmt(total - sum) }]
        : [{ label: 'Sum of ' + rows.length + ' trades', value: fmt(total) }, { label: 'Additive: every subtotal is the sum of what it holds', value: '✓' }],
      scenarios: [0, 1, 2, 3, 4, 5, 6, 7].map((i) => ({ label: new Date(Date.UTC(2024, 2 + i * 3, 3 + i * 2)).toISOString().slice(0, 10), value: '−' + fmt(Math.abs(total) * (1.6 - i * 0.07)) })),
      trend: Array.from({ length: 20 }, (x, i) => Math.abs(total) * (0.85 + 0.3 * Math.sin(i / 3))), limit: Math.abs(total) * 1.3,
      links: [{ label: 'Workbook with formulas', href: '#' }, { label: 'Why did it move?', href: '#' }] };
    return new Promise((resolve) => setTimeout(() => resolve(res), 30));
  }

  function options(layout, density, mode) {
    const o = { columns: COLUMNS, getRowId: (r) => r.ID, layout: layout, density: density, title: 'Desk P&L and risk by region', subtitle: 'synthetic · as of 2026-11-06',
      explain: explain, serverPivot: serverPivot, conditionalFormats: [{ measure: 'VaR 99% 1d', type: 'colourScale' }, { column: 'VAR', type: 'colourScale' }, { measure: 'MTM', type: 'dataBar' }, { column: 'MTM', type: 'dataBar' }],
      live: { flashMs: 1200, serverDebounceMs: 60 }, download: (name, blob) => H.downloads.push({ name: name, size: blob.size }), chart: (d) => H.charts.push(d) };
    if (mode === 'server') { o.datasource = G.memoryDatasource(data, { delay: DELAY }); }
    else { o.rows = data.slice(); }
    if (mode !== 'grid' && mode !== 'server') {
      o.pivot = { rows: ['DESK', 'BOOK'], columns: ['REGION', 'CCY'], values: [{ colId: 'MTM', aggFunc: 'sum' }, { colId: 'VAR', aggFunc: 'sum' }], filters: [{ colId: 'TRADER', filter: null }] };
    }
    return o;
  }

  const stage = document.getElementById('hx-stage');
  const layout = q.get('layout') || 'wells', density = q.get('density') || 'compact', mode = q.get('mode') || 'pivot';
  if (q.get('theme') === 'light') { document.documentElement.setAttribute('data-theme', 'light'); }

  function mount(where, l, d) {
    const el = document.createElement('drishti-grid');
    el.setAttribute('layout', l);
    el.setAttribute('density', d);
    where.appendChild(el);
    el.options = options(l, d, mode);
    if (el.grid.model.pivotMode) { el.grid.model.pivotState.expandToLevel('columns', 2); el.grid.model.pivotState.expandToLevel('rows', 2); el.grid.model.runPivot(); }
    return el;
  }

  if (q.get('matrix')) {
    stage.className = 'hx-stage hx-matrix';
    ['wells', 'panel', 'terminal'].forEach((l) => ['comfortable', 'compact', 'terminal'].forEach((d) => {
      const cell = document.createElement('section');
      cell.className = 'hx-cell';
      const h2 = document.createElement('h2');
      h2.textContent = l + ' · ' + d;
      cell.appendChild(h2);
      stage.appendChild(cell);
      mount(cell, l, d);
    }));
  } else {
    const box = document.createElement('div');
    box.className = 'hx-one';
    stage.appendChild(box);
    const el = H.element = mount(box, layout, density);
    H.grid = el.grid;
    Object.keys({ cellClicked: 1, rangeChanged: 1, sortChanged: 1, filterChanged: 1, pivotChanged: 1, rowExpanded: 1, rowCollapsed: 1, explainOpened: 1, rowDataUpdated: 1,
      columnPinned: 1, columnVisible: 1, columnResized: 1, layoutChanged: 1, densityChanged: 1, menuOpened: 1, menuAction: 1, chartRequested: 1, drillThrough: 1, viewSaved: 1,
      measureSwitched: 1, explainShown: 1, copied: 1, exported: 1, phoneChanged: 1 }).forEach((name) => {
      el.addEventListener('drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), () => { H.events.push(name); log.textContent = H.events.length + ' events · last ' + name; });
    });
  }

  /* live: a tick changes some trades' MTM and VaR (a transaction by id); Stream ticks every 400 ms */
  H.tick = function (k) {
    const g = H.grid;
    if (!g) { return null; }
    const up = [];
    for (let i = 0; i < (k || 6); i++) {
      const t = data[Math.floor(rnd() * data.length)];
      t.MTM = Math.round(t.MTM + (rnd() - 0.45) * 4e5);
      up.push(Object.assign({}, t));
    }
    return g.applyTransaction({ update: up });
  };
  document.getElementById('hx-tick').addEventListener('click', () => H.tick());
  let timer = 0;
  document.getElementById('hx-stream').addEventListener('click', (e) => {
    const on = e.currentTarget.getAttribute('aria-pressed') !== 'true';
    e.currentTarget.setAttribute('aria-pressed', String(on));
    clearInterval(timer);
    if (on) { timer = setInterval(() => H.tick(3), 400); }
  });
  document.getElementById('hx-theme').addEventListener('click', () => {
    core.setTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'terminal' : 'light', { persist: false });
  });
  document.querySelectorAll('.hx-seg [data-layout]').forEach((b) => {
    b.classList.toggle('on', b.dataset.layout === layout);
    b.addEventListener('click', () => { if (H.element) { H.element.setAttribute('layout', b.dataset.layout); } document.querySelectorAll('.hx-seg [data-layout]').forEach((x) => x.classList.toggle('on', x === b)); });
  });
  document.querySelectorAll('.hx-seg [data-density]').forEach((b) => {
    b.classList.toggle('on', b.dataset.density === density);
    b.addEventListener('click', () => { if (H.element) { H.element.setAttribute('density', b.dataset.density); } document.querySelectorAll('.hx-seg [data-density]').forEach((x) => x.classList.toggle('on', x === b)); });
  });
  H.ready = true;
}());
