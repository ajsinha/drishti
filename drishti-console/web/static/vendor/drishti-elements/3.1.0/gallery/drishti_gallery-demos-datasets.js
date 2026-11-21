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
/* The gallery's datasets in views (contract 1.1, made-up data from a seeded generator, the same on every load): a blotter of 10,000
   trades in a grid grouped by desk with a chart that shares its dataset and ticks with rows patches; a hierarchy of legal entities, desks
   and books with VaR against limits in a tree of four modes; multi-series charts of a year of daily P&L, VaR by region, DV01 by desk and
   tenor, trades by currency and the P&L distribution; positions pivoted by desk and currency in the browser. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);

  /** A seeded generator (mulberry32): the same made-up figures on every load. */
  const rng = (seed) => () => { seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const DESKS = ['Rates', 'FX', 'Credit', 'Equities', 'Commodities'];
  const REGIONS = ['EMEA', 'Americas', 'APAC'];
  const BOOKS = {};
  DESKS.forEach((d) => { BOOKS[d] = REGIONS.map((r) => d.slice(0, 3).toUpperCase() + '-' + r.slice(0, 2).toUpperCase()); });
  const CCYS = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD'];
  const CPTYS = ['Northwind Bank', 'Contoso Capital', 'Fabrikam AM', 'Tailspin Insurance', 'Litware Pension', 'Adatum Partners', 'Woodgrove Securities', 'Proseware Fund'];
  const PRODUCTS = { Rates: ['IRS', 'FRA', 'Swaption', 'Cap'], FX: ['Spot', 'Forward', 'FX Swap', 'FX Option'], Credit: ['CDS', 'Bond', 'TRS'],
    Equities: ['Equity Swap', 'Option', 'Future'], Commodities: ['Oil Swap', 'Gas Forward', 'Metal Option'] };
  const pick = (r, a) => a[Math.floor(r() * a.length)];
  const day = (i) => new Date(Date.UTC(2025, 10, 21) + i * 86400000).toISOString().slice(0, 10);

  function trades(n) {
    const r = rng(20261121), out = [];
    for (let i = 0; i < n; i++) {
      const desk = DESKS[i % DESKS.length], book = pick(r, BOOKS[desk]), notional = Math.round(r() * r() * 500) * 100000 + 100000;
      out.push({ id: 'T-' + String(100001 + i), desk: desk, book: book, region: REGIONS[BOOKS[desk].indexOf(book)], product: pick(r, PRODUCTS[desk]),
        cpty: pick(r, CPTYS), ccy: pick(r, CCYS), notional: notional, pv: Math.round((r() - 0.48) * notional * 0.02 * 100) / 100,
        dv01: desk === 'Rates' || desk === 'Credit' ? Math.round((r() - 0.5) * notional / 10000) : 0, traded: day(-Math.floor(r() * 365)), live: r() > 0.07 });
    }
    return out;
  }
  const TRADE_FIELDS = [{ name: 'id', type: 'string', label: 'Trade' }, { name: 'desk', type: 'string', label: 'Desk' }, { name: 'book', type: 'string', label: 'Book' },
    { name: 'region', type: 'string', label: 'Region' }, { name: 'product', type: 'string', label: 'Product' }, { name: 'cpty', type: 'string', label: 'Counterparty' },
    { name: 'ccy', type: 'string', label: 'Ccy' }, { name: 'notional', type: 'number', label: 'Notional', fmt: 'amount0' }, { name: 'pv', type: 'number', label: 'PV', fmt: 'amount2' },
    { name: 'dv01', type: 'number', label: 'DV01', fmt: 'amount0' }, { name: 'traded', type: 'date', label: 'Traded' }, { name: 'live', type: 'boolean', label: 'Live' }];
  const columnar = (fields, rows) => rows.map((x) => fields.map((f) => (x[f.name] === undefined ? null : x[f.name])));

  function hierarchy() {
    const r = rng(7), rows = [{ id: 'GRP', parent: '', name: 'Northwind Group', kind: 'group' }];
    ['NW Bank plc', 'NW Securities Inc', 'NW Asia Ltd'].forEach((le, i) => {
      const leId = 'LE-' + (i + 1);
      rows.push({ id: leId, parent: 'GRP', name: le, kind: 'entity' });
      DESKS.forEach((d) => {
        const dId = leId + '-' + d.slice(0, 3).toUpperCase();
        rows.push({ id: dId, parent: leId, name: d, kind: 'desk' });
        BOOKS[d].forEach((b) => {
          const v = Math.round(200000 + r() * 1800000), limit = Math.round(v * (1.05 + r() * 0.9) / 100000) * 100000;
          rows.push({ id: dId + '-' + b, parent: dId, name: b, kind: 'book', var: v, es: Math.round(v * (1.2 + r() * 0.2)), limit: limit, trades: Math.round(20 + r() * 900) });
        });
      });
    });
    return rows;
  }
  const ORG_FIELDS = [{ name: 'id', type: 'string' }, { name: 'parent', type: 'string' }, { name: 'name', type: 'string', label: 'Name' }, { name: 'kind', type: 'string', label: 'Kind' },
    { name: 'var', type: 'number', label: 'VaR 99% 1d', fmt: 'amount0' }, { name: 'es', type: 'number', label: 'ES 97.5%', fmt: 'amount0' },
    { name: 'limit', type: 'number', label: 'VaR limit', fmt: 'amount0' }, { name: 'trades', type: 'number', label: 'Trades', fmt: 'amount0' }];

  function daily() {
    const r = rng(99), out = [], level = {};
    DESKS.forEach((d) => { level[d] = 0; });
    for (let i = -249; i <= 0; i++) {
      DESKS.forEach((d, k) => {
        const pnl = Math.round((r() - 0.47) * (300000 + k * 90000));
        level[d] += pnl;
        out.push({ date: day(i), desk: d, region: REGIONS[(i + 300 + k) % 3], pnl: pnl, cumulative: level[d], var: Math.round(500000 + k * 120000 + r() * 150000) });
      });
    }
    return out;
  }
  const DAILY_FIELDS = [{ name: 'date', type: 'date', label: 'Date' }, { name: 'desk', type: 'string', label: 'Desk' }, { name: 'region', type: 'string', label: 'Region' },
    { name: 'pnl', type: 'number', label: 'P&L', fmt: 'amount0' }, { name: 'cumulative', type: 'number', label: 'Cumulative P&L', fmt: 'amount0' }, { name: 'var', type: 'number', label: 'VaR', fmt: 'amount0' }];

  function dv01() {
    const r = rng(3), out = [];
    DESKS.forEach((d) => ['3M', '6M', '1Y', '2Y', '5Y', '10Y', '30Y'].forEach((t) => { out.push({ desk: d, tenor: t, dv01: Math.round((r() - 0.5) * 80000) }); }));
    return out;
  }

  const prov = { layout: 'made-up', fingerprint: null, source: 'synthetic', generation: 1, fetchedAt: '2026-11-21T09:30:00Z', live: true, businessDate: '2026-11-21',
    updatedAt: '2026-11-21T09:30:00Z', staleAfter: 'PT5M', stale: false, sutra: null, masked: 0, maskedPanels: [] };
  const base = (pill, id, panels, datasets) => ({ contract: '1.1', ref: { kind: 'desk', id: id }, title: { pill: pill, id: id, with: null }, strip: [], panels: panels,
    keys: [], provenance: prov, timings: {}, datasets: datasets });
  const P = (id, kind, title, span, data, height) => Object.assign({ id: id, kind: kind, title: title, area: 'main', inferred: false, empty: false, span: span, data: data }, height ? { height: height } : {});

  const VIEWS = {
    blotter: () => {
      const rows = trades(10000);
      const live = rows.filter((t) => t.live).length;
      const v = base('Blotter · Trades', 'ALL DESKS', [
        P('trades', 'grid', 'Trades (10,000)', 12, { dataset: 'trades', group: ['desk'], sort: [{ field: 'notional', desc: true }],
          columns: [{ field: 'id', pinned: 'left', width: 150 }, { field: 'desk' }, { field: 'book' }, { field: 'product' }, { field: 'cpty', width: 170 }, { field: 'ccy', width: 64 },
            { field: 'notional', agg: 'sum' }, { field: 'pv', tone: 'sign', agg: 'sum' }, { field: 'dv01', tone: 'sign', agg: 'sum' }, { field: 'traded' }] }, 14),
        P('by-desk', 'chart', 'PV by desk and region', 6, { dataset: 'trades', type: 'bar', x: 'desk', by: 'region', y: [{ field: 'pv', agg: 'sum', fmt: 'compact' }], stack: 'sum' }),
        P('by-ccy', 'chart', 'Notional by currency', 6, { dataset: 'trades', type: 'donut', x: 'ccy', y: [{ field: 'notional', agg: 'sum', fmt: 'compact' }] })],
      { trades: { key: ['id'], fields: TRADE_FIELDS, values: columnar(TRADE_FIELDS, rows) } });
      v.strip = [{ label: 'Trades', text: '10,000', emphasis: true }, { label: 'Live', text: live.toLocaleString('en-US') }];
      return v;
    },
    hierarchy: () => {
      const rows = hierarchy();
      return base('Risk · Hierarchy', 'NORTHWIND GROUP', [
        P('org', 'tree', 'VaR against limit: group, entities, desks, books', 12, { dataset: 'org', node: 'id', parent: 'parent', label: 'name', open: 2,
          measures: [{ field: 'var', agg: 'sum' }, { field: 'es', agg: 'sum' }, { field: 'limit', agg: 'sum' }, { field: 'trades', agg: 'sum' }] }, 16),
        P('books', 'grid', 'Books', 7, { dataset: 'org', columns: [{ field: 'name' }, { field: 'kind' }, { field: 'var', agg: 'sum' }, { field: 'limit', agg: 'sum' }, { field: 'trades', agg: 'sum' }],
          sort: [{ field: 'var', desc: true }] }, 10),
        P('top', 'chart', 'Largest VaR by book', 5, { dataset: 'org', type: 'bar', x: 'name', y: [{ field: 'var', agg: 'sum', fmt: 'compact' }, { field: 'limit', agg: 'sum', fmt: 'compact' }], horizontal: true, limit: 10 })],
      { org: { key: ['id'], fields: ORG_FIELDS, values: columnar(ORG_FIELDS, rows) } });
    },
    charts: () => {
      const d = daily(), t = trades(3000), dv = dv01();
      return base('Desk · Performance', '250 DAYS', [
        P('cum', 'chart', 'Cumulative P&L by desk, 250 days', 8, { dataset: 'daily', type: 'line', x: 'date', by: 'desk', y: [{ field: 'cumulative', fmt: 'compact' }] }),
        P('var-region', 'chart', 'VaR by region, stacked', 4, { dataset: 'daily', type: 'area', x: 'desk', by: 'region', y: [{ field: 'var', agg: 'avg', fmt: 'compact' }], stack: 'sum' }),
        P('dv01', 'chart', 'DV01 by desk and tenor', 6, { dataset: 'dv01', type: 'heatmap', x: 'tenor', by: 'desk', y: [{ field: 'dv01', fmt: 'amount0' }] }),
        P('mtm', 'chart', 'PV against notional, by desk', 6, { dataset: 'sample', type: 'scatter', x: 'notional', by: 'desk', y: [{ field: 'pv', fmt: 'compact' }] }),
        P('dist', 'chart', 'Daily P&L distribution', 6, { dataset: 'daily', type: 'histogram', y: [{ field: 'pnl', fmt: 'compact' }], bins: 40 }),
        P('cpty', 'chart', 'Notional by counterparty', 6, { dataset: 'sample', type: 'pie', x: 'cpty', y: [{ field: 'notional', agg: 'sum', fmt: 'compact' }] })],
      { daily: { fields: DAILY_FIELDS, values: columnar(DAILY_FIELDS, d) },
        dv01: { fields: [{ name: 'desk', type: 'string', label: 'Desk' }, { name: 'tenor', type: 'string', label: 'Tenor' }, { name: 'dv01', type: 'number', label: 'DV01', fmt: 'amount0' }],
          values: dv.map((x) => [x.desk, x.tenor, x.dv01]) },
        sample: { key: ['id'], fields: TRADE_FIELDS, values: columnar(TRADE_FIELDS, t) } });
    },
    pivot: () => {
      const rows = trades(10000);
      return base('Positions · Pivot', 'ALL DESKS', [
        P('pivot', 'grid', 'Notional and PV: desk by currency (computed in the browser)', 12, { dataset: 'positions',
          pivot: { rows: ['desk', 'product'], columns: ['ccy'], values: [{ field: 'notional', agg: 'sum', label: 'Notional' }, { field: 'pv', agg: 'sum', label: 'PV' }] } }, 14)],
      { positions: { key: ['id'], fields: TRADE_FIELDS, values: columnar(TRADE_FIELDS, rows) } });
    }
  };

  demos.push({
    id: 'view-datasets', library: 'drishti-views', kind: 'function', title: 'Datasets: grids, trees and charts', about: 'drishti_views-datakinds.js', types: ['PaintOptions'],
    events: ['select', 'drawn', 'patch'], keys: [], ag: false, sutra: () => null,
    summary: 'Contract 1.1: a view carries its rows as named datasets (raw values, each field with its format) and grid, tree and chart panels read them. Panels share a dataset, and a rows patch (by key) reaches all of them. The grid draws only the rows in view, groups, sorts and pivots in the browser; the tree draws in four modes; a chart combines rows per category itself.',
    extra: [{ name: 'example', control: { kind: 'select', values: ['blotter', 'hierarchy', 'charts', 'pivot'] }, desc: 'Which made-up view: 10,000 trades, a risk hierarchy, a year of desk performance, a pivot of positions.' },
      { name: 'live', control: { kind: 'switch' }, desc: 'The blotter ticks: rows patches update 50 trades a second (the grid redraws only what changed).' }],
    values: { example: 'blotter', live: false },
    examples: [{ title: 'A blotter of 10,000 trades', values: { example: 'blotter' } }, { title: 'Live', values: { example: 'blotter', live: true } },
      { title: 'A risk hierarchy', values: { example: 'hierarchy' } }, { title: 'Multi-series charts', values: { example: 'charts' } }, { title: 'A pivot', values: { example: 'pivot' } }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, box = ctx.h(doc, 'div', { cls: 'gx-fn' });
      host.appendChild(box);
      const which = VIEWS[values.example] ? values.example : 'blotter', vm = VIEWS[which]();
      const handle = ctx.E.views.paint(box, vm, { density: 'compact', shadow: false, validate: 'off' });
      ['select'].forEach((n) => handle.on(n, (e) => ctx.log(n, JSON.stringify(e.detail))));
      let timer = null, seq = 0;
      if (values.live && which === 'blotter') {
        const r = rng(5), ids = vm.datasets.trades.values.map((x) => x[0]);
        timer = doc.defaultView.setInterval(() => {
          const ds = handle.dataset('trades');
          if (!ds || !ds.held) { return; }
          const update = [];
          for (let i = 0; i < 50; i++) { const id = ids[Math.floor(r() * ids.length)], row = ds.index.get(id); if (row) { update.push({ id: id, pv: Math.round((row.pv + (r() - 0.5) * 2000) * 100) / 100 }); } }
          handle.patch({ seq: ++seq, generation: 0, patches: [{ op: 'rows', dataset: 'trades', update: update }] });
        }, 1000);
      }
      return { target: box, element: box, ready: handle.ready, destroy: () => { if (timer) { doc.defaultView.clearInterval(timer); } handle.dispose(); box.remove(); } };
    },
    code: () => ({ js: "Drishti.elements.views.paint(document.getElementById('blotter'), {\n  contract: '1.1', ref: { kind: 'desk', id: 'ALL' }, title: { pill: 'Blotter', id: 'ALL' },\n  datasets: { trades: { key: ['id'], fields: [{ name: 'id', type: 'string' }, { name: 'desk', type: 'string' }, { name: 'notional', type: 'number', fmt: 'amount0' }],\n    values: [['T-100001', 'Rates', 2500000], ['T-100002', 'FX', 700000]] } },\n  panels: [{ id: 'trades', kind: 'grid', title: 'Trades', data: { dataset: 'trades', group: ['desk'], sort: [{ field: 'notional', desc: true }] } },\n    { id: 'by-desk', kind: 'chart', title: 'By desk', data: { dataset: 'trades', type: 'bar', x: 'desk', y: [{ field: 'notional', agg: 'sum' }] } }]\n});\n// a tick: handle.patch({ seq: 1, patches: [{ op: 'rows', dataset: 'trades', update: [{ id: 'T-100001', notional: 2600000 }] }] })",
      element: '<div id="blotter"></div>' })
  });
}(typeof window !== 'undefined' ? window : null));
