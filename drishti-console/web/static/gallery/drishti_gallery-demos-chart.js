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
/* The gallery's demos of drishti-chart (every kind, the studio, the maps in both modes, linked selection with a grid, live updates, the
   Vega-Lite translator) and of drishti-risk's four kinds. Each spec is the chart harness's sample of its kind over the same synthetic
   series (drishti_gallery-data.js); the options panel is made from ChartOptions' JSDoc. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const CHART_SCALARS = ['mode', 'density', 'mapMode', 'height', 'asOf', 'mapsBase'];

  function typed(values, names) {
    const o = {};
    names.forEach((n) => { if (values[n] !== undefined && values[n] !== '') { o[n] = values[n]; } });
    return o;
  }

  /** The chart harness's spec of each sample, over the gallery's series. */
  function specs(ctx) {
    return ctx.memo('chart-specs', () => {
      const S = ctx.data.chart(), C = ctx.E.chart;
      const vegaBar = C.fromVegaLite({ mark: { type: 'bar', cornerRadiusEnd: 3 }, encoding: { x: { field: 'DESK', type: 'nominal', sort: '-y', title: 'Desk' }, y: { field: 'MTM', type: 'quantitative', title: 'Net MTM' },
        color: { condition: { test: 'datum.MTM < 0', value: '#b3261e' }, value: '#4f7cff' }, tooltip: [{ field: 'DESK' }, { field: 'MTM', format: ',.0f' }] } },
      { rows: S.DESKS.map((d) => ({ DESK: d, MTM: S.trades.filter((t) => t.DESK === d).reduce((s, t) => s + t.MTM, 0) })), title: 'Net MTM by desk (Vega-Lite, translated)' });
      const strip = S.trades.map((t) => ({ desk: t.DESK, pnl1d: Math.round(t.MTM / 20) }));
      const med = S.DESKS.map((d) => { const xs = strip.filter((r) => r.desk === d).map((r) => r.pnl1d).sort((a, b) => a - b); return { desk: d, median: xs[Math.floor(xs.length / 2)] }; });
      const vegaStrip = C.fromVegaLite({ layer: [
        { data: { values: strip }, mark: { type: 'circle', opacity: 0.6, size: 40 }, encoding: { x: { field: 'pnl1d', type: 'quantitative', title: 'One-day P&L' }, y: { field: 'desk', type: 'nominal', title: null } } },
        { data: { values: med }, mark: { type: 'tick', thickness: 3, size: 22, color: '#d9534f' }, encoding: { x: { field: 'median', type: 'quantitative' }, y: { field: 'desk', type: 'nominal' } } }] },
      { title: 'One-day P&L by desk, dots and medians (layers)' });
      return {
        line: { kind: 'line', title: 'Daily P&L against VaR', subtitle: '250 days · drag to zoom', id: 'date', data: { rows: S.backtest }, x: { field: 'date', type: 'time' }, y: { format: 'money' },
          series: [{ field: 'hypothetical', name: 'Hypothetical P&L' }, { field: 'actual', name: 'Actual P&L' }, { field: 'var99', name: '−VaR 99%', negate: true, style: 'dashed', tone: 'bad' }],
          marks: [{ when: 'hypothetical < -var99', as: 'point', tone: 'bad', name: 'Exception' }], zoom: { window: [150, 250] }, live: { throttleMs: 50 } },
        area: { kind: 'area', title: 'VaR by region, stacked', id: 'id', data: { rows: S.deskRegion }, x: 'desk', y: { field: 'var', format: 'money' }, series: { by: 'region' }, stack: true },
        bar: { kind: 'bar', title: 'VaR by desk, today and yesterday', id: 'id', data: { rows: S.deskVar }, x: 'desk', y: { format: 'money' }, series: [{ field: 'today', name: '2026-11-06' }, { field: 'yesterday', name: '2026-11-05', tone: 'muted' }], live: { throttleMs: 50 } },
        'bar-stacked': { kind: 'bar', title: 'VaR by desk and region, stacked to 100%', data: { rows: S.deskRegion }, x: 'desk', y: { field: 'var', format: 'money' }, series: { by: 'region' }, stack: 'percent' },
        'bar-horizontal': { kind: 'bar', title: 'VaR today, largest first', data: { rows: S.deskVar }, x: 'desk', y: { field: 'today', format: 'money' }, orient: 'horizontal', sort: 'value desc', labels: true, legend: 'none' },
        scatter: { kind: 'scatter', title: 'Trades: MTM against VaR', id: 'ID', data: { rows: S.trades }, x: { field: 'VAR', format: 'money', title: 'VaR' }, y: { field: 'MTM', format: 'money', title: 'MTM' }, colour: 'DESK' },
        bubble: { kind: 'bubble', title: 'Trades sized by notional', id: 'ID', data: { rows: S.trades }, x: { field: 'VAR', format: 'money' }, y: { field: 'MTM', format: 'money' }, size: 'NOTIONAL', colour: 'REGION' },
        pie: { kind: 'pie', title: 'VaR by desk', data: { rows: S.deskVar }, category: 'desk', value: { field: 'today', format: 'money' } },
        donut: { kind: 'donut', title: 'Notional by region', data: { rows: S.trades }, category: 'REGION', value: { field: 'NOTIONAL', format: 'money' } },
        heatmap: { kind: 'heatmap', title: 'DV01 by desk and tenor', data: { rows: S.dv01 }, x: { field: 'tenor', order: S.TENORS }, y: 'desk', value: { field: 'dv01', format: 'money' }, scale: { type: 'diverging', mid: 0 } },
        waterfall: { kind: 'waterfall', title: 'P&L attribution', data: { rows: [{ step: 'Opening', v: 4e6 }, { step: 'Carry', v: 0.8e6 }, { step: 'Rates', v: -1.6e6 }, { step: 'FX', v: 0.5e6 }, { step: 'Credit', v: -0.3e6 }, { step: 'Closing', v: 3.4e6 }] }, category: 'step', value: { field: 'v', format: 'money' } },
        histogram: { kind: 'histogram', title: 'Trade MTM', data: { rows: S.trades }, value: { field: 'MTM', format: 'money' }, bins: 18, annotations: [{ type: 'line', axis: 'x', value: 0, label: 'zero', tone: 'muted' }] },
        boxplot: { kind: 'boxplot', title: 'Trade MTM by desk', data: { rows: S.trades }, category: 'DESK', value: { field: 'MTM', format: 'money' } },
        gauge: { kind: 'gauge', title: 'VaR limit used', data: { rows: [{ used: 0.78 }] }, value: { field: 'used', title: 'of the limit', format: 'percent' }, min: 0, max: 1, bands: [{ to: 0.7, tone: 'good' }, { to: 0.9, tone: 'warn' }, { to: 1, tone: 'bad' }] },
        treemap: { kind: 'treemap', title: 'Notional by desk and book', data: { rows: S.trades }, levels: ['DESK', 'BOOK'], value: { field: 'NOTIONAL', format: 'money' } },
        sankey: { kind: 'sankey', title: 'Exposure: region to desk to collateral', data: { rows: S.flows }, source: 'source', target: 'target', value: { field: 'value', format: 'money' } },
        candlestick: { kind: 'candlestick', title: 'Index, 60 days', data: { rows: S.prices }, x: 'date', open: 'open', close: 'close', low: 'low', high: 'high', y: { format: 'price2' } },
        layer: vegaStrip.spec,
        'risk.pnl-distribution': { kind: 'risk.pnl-distribution', title: 'P&L distribution, 509 scenarios', data: { rows: S.scenarios }, value: { field: 'pnl', format: 'money' }, id: 'id' },
        'risk.backtest': { kind: 'risk.backtest', title: 'Backtest, 250 days', data: { rows: S.backtest }, x: 'date', pnl: 'hypothetical', actual: 'actual', var: 'var99', id: 'date', zoom: true },
        'risk.exposure-profile': { kind: 'risk.exposure-profile', title: 'Exposure profile · ACME Bank', data: { rows: S.exposure }, x: 'tenor', ee: 'ee', pfe: 'pfe', limit: 42e6, y: { format: 'money' } },
        'risk.var-change': { kind: 'risk.var-change', title: 'What moved VaR', data: { rows: S.varSteps } },
        map: { kind: 'map', title: 'Exposure by country of risk', data: { rows: ctx.data.world }, place: 'code', id: 'code',
          metrics: [{ field: 'ead', title: 'Exposure (EAD)', format: 'money' }, { field: 'pfe', title: 'Peak PFE 95%', format: 'money' }, { field: 'cpty', title: 'Counterparties', format: 'integer' }],
          scale: { type: 'quantile', classes: 5 }, drill: 'regions' },
        vega: vegaBar
      };
    });
  }
  GAL.chartSpecs = specs;
  const GENERAL = ['line', 'area', 'bar', 'bar-stacked', 'bar-horizontal', 'scatter', 'bubble', 'pie', 'donut', 'heatmap', 'waterfall', 'histogram', 'boxplot', 'gauge', 'treemap', 'sankey', 'candlestick', 'layer'];

  /** The hooks every chart gets from the gallery: Explain from the stand-in server, downloads kept, a country's rows. */
  function hooks(ctx) {
    return ctx.memo('chart-hooks', () => {
      const maps = ctx.E.chart.geoLoader(ctx.mapsBase);
      return { explain: ctx.data.chartExplain(ctx.E.chart.formatterFor('money')), placeData: ctx.data.placeData(maps), samples: null };
    });
  }

  function chartBuild(spec, values, ctx, more) {
    const k = hooks(ctx), S = ctx.data.chart();
    const o = Object.assign({ explain: k.explain, download: ctx.download, mapsBase: ctx.mapsBase }, typed(values, CHART_SCALARS.filter((n) => n !== 'mode' && n !== 'density' && n !== 'mapMode')), more);
    const names = new Map([[k.explain, 'explain'], [ctx.download, 'download'], [k.placeData, 'placeData'], [S.backtest, 'rows'], [S.trades, 'rows'], [S.deskVar, 'rows'],
      [S.deskRegion, 'rows'], [S.scenarios, 'rows'], [S.exposure, 'rows'], [S.dv01, 'rows'], [S.flows, 'rows'], [S.prices, 'rows'], [S.varSteps, 'rows'], [ctx.data.world, 'rows']]);
    if (more && more.samples) { names.set(more.samples, 'samples'); }
    if (spec.data && spec.data.rows && !names.has(spec.data.rows)) { names.set(spec.data.rows, 'rows'); }
    return { tag: 'drishti-chart', ctor: 'Chart', library: spec.kind && spec.kind.indexOf('risk.') === 0 ? 'drishti-risk' : 'drishti-chart',
      attrs: { mode: values.mode, density: values.density, 'map-mode': values.mapMode }, spec: spec, options: o, jsOptions: typed(values, ['mode', 'density', 'mapMode']),
      names: names, given: Array.from(new Set(names.values())) };
  }

  function mountElement(host, b) {
    const el = host.ownerDocument.createElement(b.tag);
    Object.keys(b.attrs || {}).forEach((k) => { if (b.attrs[k] !== undefined && b.attrs[k] !== null && b.attrs[k] !== '') { el.setAttribute(k, String(b.attrs[k])); } });
    host.appendChild(el);
    el.options = b.options;
    el.spec = b.spec;
    return { target: el, element: el, ready: el.chart ? el.chart.ready : Promise.resolve(), destroy: () => el.remove() };
  }

  const SUTRA = {
    line: (s) => 'panels:\n  - id: pnl\n    kind: line\n    title: ' + s.title + '\n    rows: $.DAYS\n    x: date\n    y: hypothetical\n    fmt: compact',
    area: (s) => 'panels:\n  - id: var\n    kind: area\n    title: ' + s.title + '\n    rows: $.DESKS\n    x: desk\n    series:\n      - { value: var, label: VaR }',
    'bar-horizontal': (s) => 'panels:\n  - id: var\n    kind: hbar\n    title: ' + s.title + '\n    rows: $.DESKS\n    label: desk\n    value: today\n    fmt: compact',
    donut: (s) => 'panels:\n  - id: notional\n    kind: hbar\n    shape: donut\n    title: ' + s.title + '\n    rows: $.REGIONS\n    label: REGION\n    value: NOTIONAL\n    fmt: compact',
    scatter: (s) => 'panels:\n  - id: trades\n    kind: scatter\n    title: ' + s.title + '\n    rows: $.TRADES\n    x: VAR\n    y: MTM\n    group: DESK\n    fmt: compact',
    bubble: (s) => 'panels:\n  - id: trades\n    kind: scatter\n    title: ' + s.title + '\n    rows: $.TRADES\n    x: VAR\n    y: MTM\n    size: NOTIONAL\n    group: REGION',
    waterfall: (s) => 'panels:\n  - id: attribution\n    kind: waterfall\n    title: ' + s.title + '\n    rows: $.STEPS\n    label: step\n    value: v\n    fmt: compact',
    histogram: (s) => 'panels:\n  - id: mtm\n    kind: histogram\n    title: ' + s.title + '\n    rows: $.TRADES\n    value: MTM\n    bins: 18\n    fmt: compact',
    gauge: (s) => 'panels:\n  - id: limit\n    kind: gauge\n    title: ' + s.title + '\n    value: $.USED\n    max: 1\n    fmt: pct1',
    candlestick: (s) => 'panels:\n  - id: index\n    kind: candlestick\n    title: ' + s.title + '\n    rows: $.PRICES\n    x: date\n    open: open\n    high: high\n    low: low\n    close: close',
    map: () => 'panels:\n  - id: countries\n    kind: map\n    title: Exposure by country of risk\n    region: $.COUNTRY\n    shade: { field: EAD, by: $.COUNTRIES, fmt: compact, label: Exposure }'
  };
  const sutraOf = (key, spec) => (SUTRA[key] ? SUTRA[key](spec) + '\n# A Sutra panel draws drishti-chart.' : null);

  function chartDemo(spec) {
    return Object.assign({
      library: 'drishti-chart', kind: 'element', about: 'drishti_chart-view.js', types: ['ChartOptions'], events: ['CHART_EVENTS'], keys: ['drishti_chart-view.js'], ag: false,
      mount: function (host, values, ctx) { return mountElement(host, this.build(values, ctx)); }
    }, spec);
  }

  demos.push(chartDemo({
    id: 'chart', title: 'Chart', summary: 'One engine-neutral spec over the vendored ECharts: every general kind, crosshair tooltips, legend toggles, drag-to-zoom, Explain on any mark, the data table, export, live updates.',
    extra: [{ name: 'sample', control: { kind: 'select', values: GENERAL }, desc: 'The kind (the chart harness\'s sample of each).' }],
    values: { sample: 'bar', mode: 'card', density: 'compact', height: 300, asOf: '2026-11-06' },
    examples: [{ title: 'Bars, grouped', values: {} }, { title: 'A line with exceptions marked and zoom', values: { sample: 'line' } }, { title: 'A heatmap, diverging', values: { sample: 'heatmap' } },
      { title: 'Sankey', values: { sample: 'sankey' } }, { title: 'The studio (g2 B)', values: { mode: 'studio', sample: 'histogram' } }],
    build: function (values, ctx) {
      const all = specs(ctx), samples = ctx.memo('samples', () => { const o = {}; Object.keys(all).forEach((k) => { if (all[k].kind && !o[all[k].kind]) { o[all[k].kind] = all[k]; } }); return o; });
      return chartBuild(all[values.sample] || all.bar, values, ctx, values.mode === 'studio' ? { samples: samples } : null);
    },
    sutra: (values, ctx) => sutraOf(values.sample, specs(ctx)[values.sample] || {})
  }));

  demos.push(chartDemo({
    id: 'map', title: 'Map', about: 'drishti_map-view.js', summary: 'The world to every country\'s first-level regions (Natural Earth, vendored, loaded per country): a choropleth with quantile classes, bubbles, drill by double-click, the breadcrumb back, two modes.',
    extra: [{ name: 'level', control: { kind: 'select', values: ['world', 'USA', 'CAN', 'FRA', 'IND', 'BRA', 'DEU', 'AUS'] }, desc: 'The level it opens at (a country\'s ISO alpha-3 opens its regions).' }],
    values: { level: 'world', mapMode: 'ranking', density: 'compact', height: 440 },
    examples: [{ title: 'The world, ranking (g3 A)', values: {} }, { title: 'The United States over its table (g3 B)', values: { level: 'USA', mapMode: 'table' } }, { title: 'Canada: provinces and territories', values: { level: 'CAN' } }],
    build: function (values, ctx) {
      const base = specs(ctx).map, spec = values.level && values.level !== 'world' ? Object.assign({}, base, { level: values.level }) : base;
      return chartBuild(spec, values, ctx, { placeData: hooks(ctx).placeData });
    },
    sutra: () => sutraOf('map')
  }));

  demos.push(chartDemo({
    id: 'linked', title: 'Linked selection', about: 'drishti_link.js', summary: 'A grid and a chart that share a selection through drishti-core\'s bus: click marks, the grid selects the rows; select rows, the chart outlines them.',
    values: { density: 'compact' }, types: ['ChartOptions'],
    examples: [{ title: 'Trades: a grid and a scatter', values: {} }],
    build: function (values, ctx) { return chartBuild(specs(ctx).scatter, values, ctx, { link: { channel: 'gallery-trades' } }); },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, S = ctx.data.chart();
      const wrap = ctx.h(doc, 'div', { cls: 'gx-linked' });
      host.appendChild(wrap);
      const g = doc.createElement('drishti-grid');
      g.setAttribute('density', values.density || 'compact');
      wrap.appendChild(g);
      g.options = { columns: [{ field: 'ID', header: 'Trade' }, { field: 'DESK', header: 'Desk' }, { field: 'REGION', header: 'Region' }, { field: 'MTM', header: 'MTM', type: 'number', format: 'compact' },
        { field: 'VAR', header: 'VaR', type: 'number', format: 'compact', additive: false }], rows: S.trades.slice(), getRowId: (r) => r.ID, rowSelection: 'multiple', title: 'Trades' };
      const unlink = ctx.E.chart.linkGrid(g.grid, { channel: 'gallery-trades' });
      const m = mountElement(wrap, this.build(values, ctx));
      m.target = wrap;
      m.grid = g.grid;
      m.destroy = () => { unlink(); g.grid.destroy(); wrap.remove(); };
      return m;
    },
    code: function (values, ctx) {
      const c = ctx.code.elementCode(this.build(values, ctx));
      c.js = c.js.replace('import { Chart }', 'import { Chart, linkGrid }') + "\n\n// the grid (drishti-grid) joins the same channel\nlinkGrid(grid, { channel: 'gallery-trades' });";
      return c;
    },
    sutra: () => null
  }));

  demos.push(chartDemo({
    id: 'chart-live', title: 'Live chart', summary: 'apply({add, update, remove}) by row id, append and replace, drawn once per throttle window; live.keep caps the rows kept.',
    extra: [{ name: 'sample', control: { kind: 'select', values: ['line', 'bar'] }, desc: 'A day added to the line, or a desk\'s VaR moved.' }],
    values: { sample: 'line', density: 'compact' },
    examples: [{ title: 'A new day each tick', values: {} }, { title: 'A desk moved each tick', values: { sample: 'bar' } }],
    build: function (values, ctx) {
      const base = specs(ctx)[values.sample];
      const own = ctx.memo('live-' + values.sample, () => Object.assign({}, base, { data: { rows: base.data.rows.slice() } }));
      return chartBuild(own, values, ctx);
    },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, S = ctx.data.chart();
      let timer = 0, m = null;
      const tick = () => {
        const c = m.element.chart;
        if (!c) { return; }
        if (values.sample === 'line') {
          const last = c.rows[c.rows.length - 1], t = Date.parse(last.date + 'T00:00:00Z'), d = new Date(t + 86400000 * (new Date(t).getUTCDay() === 5 ? 3 : 1)).toISOString().slice(0, 10);
          const row = { date: d, hypothetical: Math.round(S.gauss() * 1e6), actual: Math.round(S.gauss() * 1e6), var99: 2.6e6 };
          c.apply({ add: [row] });
          ctx.log('apply', { add: d });
        } else {
          const row = { id: 'Rates', today: Math.round(9.82e6 * (0.9 + 0.2 * S.rnd())) };
          c.apply({ update: [row] });
          ctx.log('apply', { update: 'Rates' });
        }
      };
      const bar = ctx.h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'toolbar', 'aria-label': 'Live' } }, [
        ctx.h(doc, 'button', { cls: 'gx-btn', text: 'Tick', attrs: { type: 'button', 'data-action': 'tick' }, on: { click: tick } }),
        ctx.h(doc, 'button', { cls: 'gx-btn', text: 'Stream', attrs: { type: 'button', 'aria-pressed': 'false', 'data-action': 'stream' }, on: { click: (e) => { const on = e.currentTarget.getAttribute('aria-pressed') !== 'true'; e.currentTarget.setAttribute('aria-pressed', String(on)); clearInterval(timer); if (on) { timer = setInterval(tick, 300); } } } })]);
      host.appendChild(bar);
      m = mountElement(host, this.build(values, ctx));
      m.tick = tick;
      m.destroy = () => { clearInterval(timer); bar.remove(); m.element.remove(); };
      return m;
    },
    code: function (values, ctx) {
      const c = ctx.code.elementCode(this.build(values, ctx));
      c.js += values.sample === 'line' ? "\n\nchart.apply({ add: [{ date: '2026-11-09', hypothetical: 120000, actual: 95000, var99: 2600000 }] });" : "\n\nchart.apply({ update: [{ id: 'Rates', today: 10100000 }] });";
      return c;
    },
    sutra: () => null
  }));

  const calendarSpec = (ctx) => ctx.memo('calendar-spec', () => {
    const rows = [];
    const d = new Date(Date.UTC(2026, 0, 2));
    let i = 7;
    while (d.getUTCFullYear() === 2026) {
      if (d.getUTCDay() !== 0 && d.getUTCDay() !== 6) { i = (i * 16807) % 2147483647; rows.push({ day: d.toISOString().slice(0, 10), breaches: i % 7 === 0 ? (i % 3) + 1 : 0 }); }
      d.setUTCDate(d.getUTCDate() + 1);
    }
    return { kind: 'calendar', title: 'Limit breaches per day', x: { field: 'day' }, value: { field: 'breaches', title: 'Breaches' }, data: { rows: rows } };
  });
  demos.push(chartDemo({
    id: 'calendar', title: 'Calendar heatmap', summary: 'A figure per business date on a month grid, a day with no row hollow (a non-business day never reads as a zero); click a day to select it.',
    values: { mode: 'card', density: 'compact', height: 240 }, examples: [{ title: 'Limit breaches', values: {} }],
    build: function (values, ctx) { return chartBuild(calendarSpec(ctx), values, ctx); },
    sutra: () => null
  }));

  demos.push(chartDemo({
    id: 'vega-lite', title: 'Vega-Lite translated', about: 'drishti_chart-vega.js', summary: 'fromVegaLite(vl, {rows}) gives a drishti-chart spec, with what it could not translate named (O1: Vega is retired as the screens move).',
    values: { density: 'compact' },
    examples: [{ title: 'A bar with a colour condition', values: {} }],
    build: function (values, ctx) { return chartBuild(specs(ctx).vega.spec, values, ctx); },
    mount: function (host, values, ctx) {
      const m = mountElement(host, this.build(values, ctx)), v = specs(ctx).vega;
      const note = ctx.h(host.ownerDocument, 'p', { cls: 'gx-note', text: 'Translated: ' + (v.unsupported.length ? 'not translated: ' + v.unsupported.join(', ') : 'everything') + (v.refused && v.refused.length ? '; refused: ' + v.refused.join(', ') : '') });
      host.insertBefore(note, m.element);
      const destroy = m.destroy;
      m.destroy = () => { note.remove(); destroy(); };
      return m;
    },
    code: function (values, ctx) {
      const c = ctx.code.elementCode(this.build(values, ctx));
      c.js = "import { Chart, fromVegaLite } from '/static/elements/drishti-chart/src/drishti_index.js';\n\nconst { spec, unsupported } = fromVegaLite({\n  mark: { type: 'bar', cornerRadiusEnd: 3 },\n  encoding: {\n    x: { field: 'DESK', type: 'nominal', sort: '-y' },\n    y: { field: 'MTM', type: 'quantitative' },\n    color: { condition: { test: 'datum.MTM < 0', value: '#b3261e' }, value: '#4f7cff' }\n  }\n}, { rows });\nnew Chart(document.getElementById('chart'), spec, { explain });";
      return c;
    },
    sutra: () => null
  }));

  /* ---------------- drishti-chart 0.3.0: tracks, category cells, segments, surface, the rectangle brush, frames, lollipop, 3D trace ----------------
     Synthetic and seeded (the same pictures every time); generic data (desks, sessions, a path): a domain library builds its kinds on these. */
  const seeded = (seed) => { let x = seed; return () => { x = (x * 16807) % 2147483647; return (x - 1) / 2147483646; }; };
  const more = (ctx) => ctx.memo('chart-0.3', () => {
    const rnd = seeded(11), desks = ['Rates', 'FX', 'Credit', 'Equity', 'Commodities', 'Funding'], days = [];
    for (let d = 1; d <= 30; d++) { days.push(String(d).padStart(2, '0')); }
    const usage = [], status = [];
    desks.forEach((desk, i) => days.forEach((day) => {
      const u = Math.max(0, Math.min(1.2, 0.35 + 0.1 * i + (rnd() - 0.5) * 0.6));
      usage.push({ id: desk + day, DESK: desk, DAY: day, USED: Math.round(u * 100) / 100 });
      status.push({ id: desk + day, DESK: desk, DAY: day, STATUS: u > 1 ? 'breach' : u > 0.85 ? 'warning' : 'within' });
    }));
    const sessions = [{ name: 'Asia', from: 0, to: 480 }, { name: 'Europe', from: 480, to: 960 }, { name: 'Americas', from: 960, to: 1440 }];
    const ticks = [];
    for (let i = 0; i < 700; i++) { const m = Math.floor(rnd() * 1440); ticks.push({ id: 'T' + i, MINUTE: m, MOVE: Math.round(Math.abs((rnd() + rnd() + rnd() - 1.5) * 4) * (m % 480 < 30 ? 2.2 : 1) * 100) / 100, PAIR: ['EURUSD', 'USDJPY', 'GBPUSD', 'AUDUSD'][i % 4] }); }
    const clusters = [[-2, -1], [2, 1.5], [0, 3]], points = [];
    for (let i = 0; i < 360; i++) { const c = clusters[i % 3]; points.push({ ID: 'C' + i, X: Math.round((c[0] + (rnd() - 0.5) * 2) * 100) / 100, Y: Math.round((c[1] + (rnd() - 0.5) * 2) * 100) / 100, GROUP: 'G' + (i % 3 + 1), SCORE: Math.round(rnd() * 100) }); }
    const steps = [], field = [];
    for (let q = 1; q <= 6; q++) { for (let i = 0; i < 60; i++) { const t = q / 6; steps.push({ ID: 'S' + q + '-' + i, STEP: q, X: Math.round(((rnd() - 0.5) * 6 * (1 - t) + 2 * t) * 100) / 100, Y: Math.round(((rnd() - 0.5) * 6 * (1 - t) + 1.5 * t) * 100) / 100, SCORE: Math.round((0.5 + 2.5 * t + rnd()) * 100) / 100 }); } }
    for (let x = -3; x <= 3; x += 0.5) { for (let y = -3; y <= 3; y += 0.5) { field.push({ x: x, y: y, value: Math.round((3 * Math.exp(-((x - 2) ** 2 + (y - 1.5) ** 2) / 3) + Math.exp(-((x + 1.5) ** 2 + (y + 1.5) ** 2) / 1.5)) * 100) / 100 }); } }
    const events = [];
    for (let i = 0; i < 24; i++) { events.push({ ID: 'E' + i, AT: Math.floor(rnd() * 360) + 1, COUNT: 1 + Math.floor(rnd() * 9), EFFECT: Math.round((rnd() * 2 - 1) * 100) / 100 }); }
    const path = [];
    for (let i = 0; i < 160; i++) { const a = i / 8; path.push({ STEP: i + 1, X: Math.round(Math.cos(a) * (10 + i / 20) * 100) / 100, Y: Math.round(Math.sin(a) * (10 + i / 20) * 100) / 100, Z: Math.round((i / 4 + Math.sin(a * 3) * 2) * 100) / 100, LOAD: Math.round(Math.sin(i / 15) * 100) / 100 }); }
    return { usage, status, desks, days, sessions, ticks, points, steps, field, events, path };
  });
  const SPECS3 = {
    tracks: (M) => ({ kind: 'heatmap', title: 'Limit used by desk and day, with tracks', id: 'id', data: { rows: M.usage }, x: { field: 'DAY', title: 'Day' }, y: 'DESK', value: { field: 'USED', format: 'percent' },
      tracks: [{ axis: 'x', title: 'Month end', values: { 29: 'yes', 30: 'yes' }, tones: { yes: 'warn' } }, { axis: 'x', title: 'Volume', values: Object.fromEntries(M.days.map((d, i) => [d, (i * 37) % 100])) },
        { axis: 'y', title: 'Region', values: { Rates: 'EMEA', FX: 'APAC', Credit: 'EMEA', Equity: 'AMER', Commodities: 'AMER', Funding: 'EMEA' } }] }),
    cells: (M) => ({ kind: 'heatmap', title: 'Limit status by desk and day (category cells)', id: 'id', data: { rows: M.status }, x: { field: 'DAY', title: 'Day' }, y: 'DESK',
      value: { field: 'STATUS', type: 'category', order: ['within', 'warning', 'breach'], tones: { within: 'good', warning: 'warn', breach: 'bad' } }, gap: 0 }),
    matrix: (M) => ({ kind: 'matrix', title: 'Limit breaches: desks by day (the matrix kind)', id: 'id', data: { rows: M.status.filter((r) => r.STATUS !== 'within') },
      x: { field: 'DAY', domain: M.days, title: 'Day' }, y: { field: 'DESK', domain: M.desks }, value: { field: 'STATUS', order: ['warning', 'breach'], tones: { warning: 'warn', breach: 'bad' } },
      tracks: [{ title: 'Month end', values: { 29: 'yes', 30: 'yes' }, tones: { yes: 'accent' } }] }),
    segments: (M) => ({ kind: 'segmented', title: 'FX moves through the day, by session (the segmented scatter)', id: 'id', data: { rows: M.ticks }, x: { field: 'MINUTE', title: 'Minute of the day' }, y: { field: 'MOVE', title: 'Move (pips)' },
      segments: M.sessions, thresholds: [{ value: 10, label: 'alert', tone: 'bad' }, { value: 6, label: 'watch', tone: 'warn' }], labelTop: { field: 'PAIR', n: 3 } }),
    surface: (M) => ({ kind: 'scatter', title: 'Clients over a score surface', id: 'ID', data: { rows: M.points }, x: { field: 'X', min: -3, max: 3 }, y: { field: 'Y', min: -3, max: 3 }, colour: 'GROUP', surface: { rows: M.field, cell: [0.5, 0.5] } }),
    brush: (M) => ({ kind: 'scatter', title: 'Clients: drag a rectangle to pick a group', subtitle: 'the brush selects the points inside; the grid follows', id: 'ID', data: { rows: M.points }, x: 'X', y: 'Y', colour: 'GROUP', brush: 'rect' }),
    frames: (M) => ({ kind: 'scatter', title: 'Six steps of a search over a surface (frames)', subtitle: 'press Play, or move the slider', id: 'ID', data: { rows: M.steps }, x: { field: 'X', min: -3, max: 3 }, y: { field: 'Y', min: -3, max: 3 },
      size: 'SCORE', frames: { field: 'STEP', label: 'Step', interval: 900 }, surface: { rows: M.field, cell: [0.5, 0.5] } }),
    lollipop: (M) => ({ kind: 'lollipop', title: 'Events along a year, coloured by their effect', id: 'ID', data: { rows: M.events }, x: { field: 'AT', title: 'Day of the year' }, y: { field: 'COUNT', title: 'Count' }, length: 366,
      colour: { field: 'EFFECT', scale: 'diverging', title: 'Effect', legend: [{ name: 'hurts', value: -1 }, { name: 'neutral', value: 0 }, { name: 'helps', value: 1 }] }, bands: [{ name: 'Q1 close', from: 80, to: 92 }, { name: 'Q3 close', from: 262, to: 274 }] }),
    trace3d: (M) => ({ kind: 'trace3d', title: 'A path through space, coloured by load', subtitle: 'drag to turn; flat where WebGL is off', id: 'STEP', data: { rows: M.path }, x: 'X', y: 'Y', z: 'Z', order: 'STEP',
      colour: { field: 'LOAD', scale: 'diverging', title: 'Load' }, turn: true, selected: ['40', '80'] }),
    'trace3d-table': (M) => Object.assign(SPECS3.trace3d(M), { title: 'The same path, its table where WebGL is off', fallback: 'table' }),
    overview: (M) => ({ kind: 'heatmap', title: 'A wide matrix with its overview strip', id: 'id', data: { rows: M.usage }, x: { field: 'DAY', title: 'Day' }, y: 'DESK', value: { field: 'USED', format: 'percent' }, zoom: { window: [0, 9] } })
  };
  [['chart-tracks', 'Heatmap tracks', 'drishti_chart-tracks.js', 'Strips of cells aligned to a heatmap\'s columns (above) or rows (at the right): a category by its tones, a number on a ramp; category cells colour the heatmap itself by a category. Click a cell or a track.',
    ['tracks', 'cells', 'overview'], ['HeatTrack']],
  ['chart-matrix', 'Matrix', 'drishti_chart-kinds3.js', 'Category cells in the server\'s order of rows and columns, every column kept, tracks over either axis, the overview strip past 150 columns: a limit-status matrix, a who-has-what matrix.', ['matrix'], []],
  ['chart-segments', 'Segmented scatter and surface', 'drishti_chart-overlays.js', 'The segmented kind: a value axis made of named segments with alternating bands and tones, threshold lines with the points past the first in its tone, the tallest named one per member; and a surface of values under a scatter.', ['segments', 'surface'], []],
  ['chart-brush', 'Rectangle brush', 'drishti_chart-parts.js', 'brush: \'rect\' on a scatter: a drag draws a rectangle, the points inside are selected and told to the link\'s channel (the grid selects their rows), and the chart emits brushed with the box and the ids.', ['brush'], []],
  ['chart-frames', 'Frames', 'drishti_chart-frames.js', 'frames: {field} steps one field of the rows with a slider and Play (no Play when reduced motion is asked for); the marks move from frame to frame; frameChanged tells the page.', ['frames'], ['ChartFrames']],
  ['chart-lollipop', 'Lollipop', 'drishti_chart-kinds3.js', 'A stem and a head at each position of one long axis; the head\'s colour a category or a number on a diverging ramp, with the legend\'s words; stretches of the axis as bands.', ['lollipop'], []],
  ['chart-trace3d', '3D trace', 'drishti_chart-kinds3.js', 'Points in three dimensions joined in order, coloured by a figure, turned by a drag on the vendored ECharts GL (loaded the first time); the selection drawn larger; flat where WebGL is off.', ['trace3d', 'trace3d-table'], []]
  ].forEach((d) => {
    const demo = {
      id: d[0], title: d[1], about: d[2], summary: d[3], types: ['ChartOptions'].concat(d[5]),
      values: Object.assign({ density: 'compact', height: d[0] === 'chart-trace3d' ? 380 : 320 }, d[4].length > 1 ? { sample: d[4][0] } : {}),
      examples: d[4].map((k) => ({ title: SPECS3[k](more({ memo: (n, f) => f() })).title, values: d[4].length > 1 ? { sample: k } : {} })),
      build: function (values, ctx) { const k = d[4].length > 1 && d[4].indexOf(values.sample) >= 0 ? values.sample : d[4][0]; return chartBuild(SPECS3[k](more(ctx)), values, ctx, d[0] === 'chart-brush' ? { link: { channel: 'gallery-clients' } } : null); },
      sutra: () => null
    };
    if (d[4].length > 1) { demo.extra = [{ name: 'sample', control: { kind: 'select', values: d[4] }, desc: 'Which example.' }]; }
    if (d[0] === 'chart-brush') {
      demo.mount = function (host, values, ctx) {
        const doc = host.ownerDocument, wrap = ctx.h(doc, 'div', { cls: 'gx-linked' });
        host.appendChild(wrap);
        const g = doc.createElement('drishti-grid');
        g.setAttribute('density', values.density || 'compact');
        wrap.appendChild(g);
        g.options = { columns: [{ field: 'ID', header: 'Client' }, { field: 'GROUP', header: 'Group' }, { field: 'SCORE', header: 'Score', type: 'number' }], rows: more(ctx).points.slice(), getRowId: (r) => r.ID, rowSelection: 'multiple', title: 'Clients' };
        const unlink = ctx.E.chart.linkGrid(g.grid, { channel: 'gallery-clients' });
        const m = mountElement(wrap, this.build(values, ctx));
        m.target = wrap;
        m.grid = g.grid;
        m.destroy = () => { unlink(); g.grid.destroy(); wrap.remove(); };
        return m;
      };
    }
    demos.push(chartDemo(demo));
  });

  /* ---------------- drishti-risk: the banking module's kinds ---------------- */
  [['risk.pnl-distribution', 'P&L distribution', 'The histogram of scenario P&L with the VaR and ES lines and the tail beyond VaR in the bad tone; VaR and ES are the server\'s (computed here only without one, and the card says so).'],
    ['risk.backtest', 'Backtest', 'P&L against minus the day-before VaR, exceptions marked, the Basel traffic light with its plus factor on the card (green 0 to 4, amber 5 to 9, red 10 and more over 250 days).'],
    ['risk.exposure-profile', 'Exposure profile', 'EE and PFE on a log tenor axis, EEPE over the first year, the limit, a breach on the card.'],
    ['risk.var-change', 'VaR change', 'A waterfall of what moved VaR, where an increase is bad.']].forEach((k) => {
    demos.push(chartDemo({
      id: k[0], library: 'drishti-risk', typesFrom: 'drishti-chart', title: k[1], summary: k[2], about: 'drishti_risk-kinds.js',
      values: { density: 'compact', height: 300 },
      examples: [{ title: k[1], values: {} }],
      build: function (values, ctx) { return chartBuild(specs(ctx)[k[0]], values, ctx); },
      sutra: () => null
    }));
  });

  /* ---------------- a module's own visual types (the custom-visual SDK) ----------------
     The console lists the visuals of the modules hosted now (/api/elements/visuals); the boot script loads each module's library (which
     registers its kinds in drishti-chart's registry) and calls this for each kind: a library entry in the catalogue (once per library)
     and a page for the kind, drawn from the kind's own sample. A module that is off or removed is not listed: its pages are not here. */
  GAL.moduleVisual = function (v, catalogue) {
    const cat = catalogue || GAL.catalogue;
    if (!cat.libraries.some((l) => l.name === v.library)) {
      cat.libraries.push({ name: v.library, version: v.version, title: 'The ' + v.module + " module's library (served while the module is on)",
        requires: ['drishti-core', 'drishti-chart'], module: v.module, server: 'none', global: 'Drishti.elements.libs[\'' + v.library + '\']',
        bundle: { path: v.script, bytes: 0, lines: 0 }, tests: { node: 0, files: [], browser: [] }, modules: [{ file: v.file, about: v.summary }],
        symbols: [], types: [], constants: {}, changelog: [] });
    }
    if (demos.some((d) => d.id === v.kind)) { return; }
    demos.push(chartDemo({
      id: v.kind, library: v.library, typesFrom: 'drishti-chart', title: v.title, summary: v.summary, about: v.file, moduleVisual: v.module,
      values: { density: 'compact', height: 320 },
      examples: [{ title: v.title, values: {} }],
      build: function (values, ctx) {
        const C = ctx.E.chart, def = C.kindOf(v.kind), spec = def && def.sample ? def.sample() : { kind: v.kind, data: { rows: [] } };
        return Object.assign(chartBuild(spec, values, ctx), { library: v.library });
      },
      sutra: () => null
    }));
  };
}(typeof window !== 'undefined' ? window : globalThis));
