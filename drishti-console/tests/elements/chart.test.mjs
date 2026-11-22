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
/* drishti-risk's kinds on drishti-chart under Node, without a page: the risk kinds' figures, the bundles of the chart and the risk
   library, and the panel charts against the frozen renderer 1.2.0. drishti-chart's
   own tests are in the Drishti Elements project. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as C from '../../web/static/vendor/drishti-elements/3.3.2/elements/drishti-chart/src/drishti_index.js';
import * as R from '../../web/static/elements/drishti-risk/src/drishti_index.js';
import { EventBus } from '../../web/static/vendor/drishti-elements/3.3.2/elements/drishti-core/src/drishti_index.js';
import * as fs from 'node:fs';

const require = createRequire(import.meta.url);
const th = C.chartTheme({ theme: 'terminal' });
const env = { theme: th, fmt: C.formatterFor('money'), xfmt: C.formatterFor() };
const build = (spec, rows) => { const s = C.normalizeSpec(spec); return C.kindOf(s.kind).build(s, rows || C.rowsOf(s.data), env); };
const DESKS = [{ id: 'r', desk: 'Rates', today: 9e6, yday: 8e6 }, { id: 'f', desk: 'FX', today: -3e6, yday: 2e6 }, { id: 'c', desk: 'Credit', today: 6e6, yday: 7e6 }];

test('risk: the traffic light, the tail figures, the backtest badge, the exposure profile, the distribution\'s lines', () => {
  assert.deepEqual([4, 5, 9, 10, 0].map((n) => R.trafficLight(n).zone), ['green', 'amber', 'amber', 'red', 'green']);
  assert.equal(R.trafficLight(7).plus, 0.65);
  const pnl = Array.from({ length: 100 }, (x, i) => i - 50);
  const t = R.tailFigures(pnl, 0.99, 0.975);
  assert.ok(Math.abs(t.var - 49.01) < 1e-9);
  assert.ok(t.es >= t.var - 1);
  const days = Array.from({ length: 260 }, (x, i) => ({ date: '2026-01-01', hyp: i % 40 === 0 ? -10 : 1, v: 5 }));
  const bt = build({ kind: 'risk.backtest', data: { rows: days }, pnl: 'hyp', var: 'v', x: { field: 'date', type: 'category' } });
  assert.equal(bt.figures.exceptions, 6);                                  // the last 250 days: days 40, 80 ... 240
  assert.equal(bt.badge.zone, 'amber');
  assert.match(bt.badge.text, /amber · 6 exceptions · plus 0\.50/);
  const ex = build({ kind: 'risk.exposure-profile', data: { rows: [{ t: 0.25, ee: 4, pfe: 9 }, { t: 1, ee: 6, pfe: 12 }, { t: 5, ee: 3, pfe: 50 }] }, x: 't', ee: 'ee', pfe: 'pfe', limit: 42 });
  assert.equal(ex.option.xAxis.type, 'log');
  assert.ok(Math.abs(ex.figures.eepe - 5.5) < 1e-9);
  assert.equal(ex.badge.text, 'PFE over the limit');
  assert.equal(R.tenorText(0.25) + R.tenorText(1) + R.tenorText(0.02), '3M1Y1W');
  const d = build({ kind: 'risk.pnl-distribution', data: { rows: pnl.map((p) => ({ pnl: p })) }, var: 30, es: { value: 40 } });
  assert.deepEqual(d.option.series[0].markLine.data.map((l) => l.xAxis), [-30, -40]);
  assert.equal(d.figures.computedHere, false);
  assert.equal(d.badge, undefined);
  const vc = build({ kind: 'risk.var-change', data: { rows: [{ step: 'a', change: 10 }, { step: 'b', change: 2 }, { step: 'c', change: 12 }] } });
  assert.equal(vc.option.series[1].data[1].itemStyle.color, th.tokens.bad);
  assert.deepEqual(R.RISK_KINDS.map((k) => C.kindOf(k).family), ['risk', 'risk', 'risk', 'risk', 'risk', 'risk', 'risk']);
});

test('risk: the server\'s bins drawn as given, its VaR and ES and fitted normal; the grid preset\'s columns never summed', () => {
  const binned = { edges: [-50, -30, -10, 10, 30], counts: [2, 5, 9, 4] };
  const d = build({ kind: 'risk.pnl-distribution', data: { rows: [] }, binned: binned, var: 30, es: 42,
    parametric: { var: 25, curve: { x: [-50, 0, 30], y: [0.5, 8, 1] }, gap: 5, gapPct: 0.2, describe: ['a normal fitted to the 20 scenarios'] } });
  assert.deepEqual(d.option.series[0].data.map((x) => x.value), [[-50, -30, 2], [-30, -10, 5], [-10, 10, 9], [10, 30, 4]]);
  assert.deepEqual(d.table.rows, [[-50, -30, 2], [-30, -10, 5], [-10, 10, 9], [10, 30, 4]]);
  assert.deepEqual(d.option.series[0].markLine.data.map((l) => l.xAxis), [-30, -42, -25]);
  assert.deepEqual([d.figures.var, d.figures.es, d.figures.computedHere, d.figures.fatTailGap], [30, 42, false, 5]);
  assert.equal(d.option.series[d.option.series.length - 1].data.length, 3);
  assert.match(d.summary, /^P&L distribution of 20 scenarios/);
  const c = R.figureColumn('VAR', 'VaR', { tone: (v) => (v > 10 ? 'bad' : null) });
  assert.deepEqual([c.type, c.additive, c.format, c.cellClass(11, {}), c.cellClass(1, {})], ['number', false, 'compact', 'dg-tone-bad', null]);
  assert.equal(R.figureColumn('PV', 'PV', { additive: true }).additive, true);
  assert.deepEqual([R.RISK_GRID_DEFAULTS.layout, R.RISK_GRID_DEFAULTS.clientAggregation], ['terminal', false]);
});

test('the bundles: the chart and the risk library load under Node with the same API', () => {
  const chart = require('../../web/static/vendor/drishti-elements/3.3.2/elements/drishti-chart/drishti_chart.js');
  const risk = require('../../web/static/elements/drishti-risk/drishti_risk.js');
  assert.equal(chart.library, 'drishti-chart');
  assert.equal(typeof chart.Chart, 'function');
  assert.equal(typeof chart.fromVegaLite, 'function');
  assert.ok(chart.kindOf('risk.backtest'));
  assert.equal(risk.trafficLight(12).zone, 'red');
});

// ---- the ViewModel's panel charts (moved from the client renderer 1.2.0): the same series as the frozen builders -------------------
const TOK = { ink: '#111111', muted: '#666666', faint: '#999999', border: '#cccccc', link: '#0b5cad', accent: '#b8860b', neg: '#c0392b', pos: '#1e8449',
  ok: '#27ae60', warn: '#e67e22', bad: '#a93226', surface: '#ffffff', 'bg-2': '#f4f4f4', mono: 'monospace' };
const SIZE = { width: 640, height: 300 };
const showcase = JSON.parse(fs.readFileSync(new URL('../fixtures/view_sutra_all-panels-showcase.json', import.meta.url), 'utf8'));
// the shapes of render_cases.py the showcase lacks
const EXTRA = [
  { kind: 'line', data: { x: ['1M', '3M', '1Y', '5Y'], series: [{ label: 'SOFR', values: [3.9, null, 3.7, 3.6], tone: 'link' }, { label: 'Old', values: [4.0, 3.9, 3.8, 'x'], tone: 'accent' }],
    mark: '5Y', markText: '5Y point 3.60%', unit: '%', step: true, limit: 3.95, limitLabel: 'cap' } },
  { kind: 'surface', data: { x: ['a', 'b'], y: ['r1', 'r2'], z: [[1.0, null], [2.5, 3.0]], min: 1.0, max: 3.0, view: 'heatmap' } },
  { kind: 'scatter', data: { points: [{ x: 1, y: 2, size: 4, xText: '1', yText: '2', group: 'g1', link: { kind: 'book', id: 'B1' } }, { x: 2, y: 1, size: 9, xText: '2', yText: '1', label: 'two', group: 'g2' }],
    groups: ['g1', 'g2'], xLabel: 'X', yLabel: 'Y', sized: true, more: 7 } },
  { kind: 'candlestick', data: { candles: [{ x: '2026-09-01', open: 1, high: 2.5, low: 0.5, close: 2 }, { x: '2026-09-02', open: 2, high: 2, low: 1e-5, close: 1e17 }], volume: false } },
  { kind: 'donut', data: { shape: 'donut', total: '9.0m', bars: [{ label: 'Rates', value: 4.5e6, text: '4.5m' }, { label: 'FX', value: -3e6, text: '−3.0m' }, { label: 'Credit', value: 1.5e6, text: '1.5m' }] } },
  { kind: 'waterfall', data: { unit: 'USD', steps: [1, 2, 3, 4, 5, 6, 7, 8].map((i) => ({ label: 'S' + i, value: i, from: i * (i - 1) / 2, to: i * (i + 1) / 2, text: '+' + i, total: false })) } },
  { kind: 'histogram', data: { bins: [{ from: 0, to: 1, count: 3, label: '0 to 1' }, { from: 1, to: 2, count: 1, label: '1 to 2' }],
    markers: [{ label: 'VaR', value: 0.5, text: '0.5', tone: 'neg' }, { value: 1.5, text: '1.5' }], count: 4, dropped: 2, unit: 'm' } },
  { kind: 'area', data: {} }, { kind: 'waterfall', data: {} }, { kind: 'histogram', data: {} }, { kind: 'scatter', data: {} }, { kind: 'candlestick', data: {} },
  { kind: 'donut', data: {} }, { kind: 'surface', data: {} }];
// a graph panel is drishti-graph's (its own tests: graph.test.mjs, test_elements_graph_browser.py); PanelCharts hands it over
const CHART_KINDS = new Set(['line', 'area', 'waterfall', 'histogram', 'scatter', 'candlestick', 'surface']);
const CASES = showcase.panels.filter((p) => CHART_KINDS.has(p.kind)).map((p) => ({ kind: p.kind, data: p.data })).concat(EXTRA);
const seriesOf = (o) => JSON.parse(JSON.stringify((o.series || []).map((s) => ({ type: s.type, name: s.name == null ? '' : String(s.name), data: s.data || null })),
  (k, v) => (typeof v === 'function' || k === 'bar' ? undefined : v)));   // Elements 3.2 hands a donut's slice its own bar record (the click's evidence): not a point of the series

test('panel charts: every kind builds its option, with series for the data it is given', () => {
  assert.ok(CASES.length >= 20);
  assert.deepEqual([...C.PANEL_KINDS], ['line', 'area', 'waterfall', 'histogram', 'scatter', 'candlestick', 'graph', 'donut', 'surface', 'map', 'treemap']);
  for (const c of CASES) {
    const views = c.kind === 'surface' ? ['heatmap', '3d'] : [undefined];
    for (const v of views) {
      const mine = C.panelOption(c.kind, c.data, TOK, SIZE, v);
      assert.deepEqual(seriesOf(mine), seriesOf(C.panelOption(c.kind, JSON.parse(JSON.stringify(c.data)), TOK, SIZE, v)), c.kind + (v ? ' ' + v : ''));   // the same data, the same series
    }
  }
  assert.throws(() => C.panelOption('sankey', {}, TOK), /no panel chart sankey/);
  assert.throws(() => C.panelOption('graph', {}, TOK), /no panel chart graph/);              // drawn by drishti-graph now
  assert.ok(C.labelFit([{ text: 'x' }], SIZE));
  assert.equal(C.escapeTip('<b a="1">&'), '&lt;b a=&quot;1&quot;&gt;&amp;');
});

test('panel charts: the tick formatters run on drishti-core formatCompact (decimal half up, no minus zero)', () => {
  assert.deepEqual([C.panelCompact(1.005), C.panelCompact(9.995e6), C.panelCompact(-0.001), C.panelCompact(1234567), C.panelCompact(NaN)].slice(0, 4), ['1.01', '10m', '0', '1.23m']);
  assert.equal(typeof C.panelLineCompact(12345), 'string');
});

test('panel charts: tokens are read from the view, and the manager draws, links, breaks in words and disposes', async () => {
  const styles = { '--d-ink': ' #111 ', '--d-link': '#00f', '--d-font-mono': ' mono ' };
  const fakeEl = { ownerDocument: { defaultView: { getComputedStyle: () => ({ getPropertyValue: (k) => styles[k] || '' }) } } };
  const t = C.panelTokens(fakeEl);
  assert.equal(t.ink, '#111');
  assert.equal(t.link, '#00f');
  assert.equal(t.mono, 'mono');
  assert.equal(t['bg-2'], '');
  const made = [], clicks = {};
  const engine = {
    getInstanceByDom: (el) => el.__inst || null,
    init: (el, theme, o) => {
      const inst = { el, renderer: o.renderer, option: null, disposed: false, setOption(opt) { if (el.__fail) { throw new Error('bad'); } this.option = opt; },
        on(n, fn) { clicks[el.id] = fn; }, isDisposed() { return this.disposed; }, dispose() { this.disposed = true; el.__inst = null; }, resize() {} };
      el.__inst = inst; made.push(inst); return inst;
    }
  };
  const box = (id, kind, data, fail) => {
    const attrs = { 'data-r-chart': kind };
    return { id, __drishti: { kind, data }, __fail: fail, clientWidth: 600, clientHeight: 280, className: 'chart', textContent: '',
      getAttribute: (k) => (k in attrs ? attrs[k] : null), removeAttribute: (k) => { delete attrs[k]; }, setAttribute: (k, v) => { attrs[k] = v; }, attrs, closest: () => null };
  };
  const els = [box('a', 'scatter', CASES.find((c) => c.kind === 'scatter' && c.data.groups && c.data.groups.length).data), box('b', 'line', { x: ['1'], series: [] }, true), box('c', 'map', { mode: 'world' })];
  const listeners = {};
  const win = { ResizeObserver: null, requestAnimationFrame: (f) => f(), document: { body: {} } };
  const root = { ownerDocument: { defaultView: win }, querySelectorAll: () => els, querySelector: () => null, host: null, firstElementChild: null,
    addEventListener: (n, f) => { listeners[n] = f; }, removeEventListener: (n) => { delete listeners[n]; } };
  const opened = [];
  const pc = new C.PanelCharts(root, { engine: () => Promise.resolve(engine), tokensFrom: () => fakeEl, onLink: (l, el) => opened.push([l, el.id]) });
  await pc.draw();
  assert.equal(made[0].renderer, 'svg');
  assert.deepEqual(seriesOf(made[0].option), seriesOf(C.panelOption('scatter', els[0].__drishti.data, t, { width: 600, height: 280 })));
  clicks.a({ data: { point: { link: { kind: 'book', id: 'B1' } } } });
  assert.deepEqual(opened, [[{ kind: 'book', id: 'B1' }, 'a']]);
  assert.equal(els[1].textContent, 'No data available');
  assert.equal(els[1].className, 'pnl-empty');
  assert.equal(els[1].attrs['data-r-chart'], undefined);
  assert.equal(els[2].textContent, 'The map did not load');           // no map helper given
  assert.equal(pc.instances().length, 1);
  pc.dispose();
  assert.equal(made[0].disposed, true);
  assert.equal(listeners.click, undefined);
  const none = box('d', 'line', {});
  const pc2 = new C.PanelCharts(Object.assign({}, root, { querySelectorAll: () => [none] }), { engine: () => Promise.reject(new Error('blocked')) });
  await pc2.draw();
  assert.equal(none.textContent, 'Charts are not available');
});

