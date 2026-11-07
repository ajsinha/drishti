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
/* drishti-chart and drishti-risk under Node, without a page: the spec and its checks, the data helpers and the condition language, the
   formats and the theme, every kind's option, table and legend, the risk kinds' figures, the Vega-Lite translator (the report's and the
   Python sample's specs among them), linked selection, and the bundles. The map loader and projections are in maps.test.mjs; the drawn
   chart is tested in a browser against the fixture harness (test_elements_chart_browser.py). */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as C from '../../web/static/elements/drishti-chart/src/drishti_index.js';
import * as R from '../../web/static/elements/drishti-risk/src/drishti_index.js';
import { EventBus } from '../../web/static/elements/drishti-core/src/drishti_index.js';

const require = createRequire(import.meta.url);
const th = C.chartTheme({ theme: 'terminal' });
const env = { theme: th, fmt: C.formatterFor('money'), xfmt: C.formatterFor() };
const build = (spec, rows) => { const s = C.normalizeSpec(spec); return C.kindOf(s.kind).build(s, rows || C.rowsOf(s.data), env); };
const DESKS = [{ id: 'r', desk: 'Rates', today: 9e6, yday: 8e6 }, { id: 'f', desk: 'FX', today: -3e6, yday: 2e6 }, { id: 'c', desk: 'Credit', today: 6e6, yday: 7e6 }];

test('the spec: checks with paths, aliases and defaults, and the readable text', () => {
  assert.deepEqual(C.checkSpec({ kind: 'nope' }).map((p) => p.path), ['$.kind']);
  const probs = C.checkSpec({ kind: 'line', legend: 'left', onClick: 'zoom', export: ['pdf'], series: [{}], annotations: [{ type: 'band', from: 1 }], marks: [{ when: 'a <' }] });
  assert.deepEqual(probs.map((p) => p.path), ['$.legend', '$.onClick', '$.export[0]', '$.series[0]', '$.annotations[0]', '$.marks[0].when']);
  assert.throws(() => C.normalizeSpec({ kind: 'nope' }), /\$\.kind: no kind nope/);
  const s = C.normalizeSpec({ kind: 'column', x: 'desk', y: 'today', color: 'region', series: 'region' });
  assert.equal(s.kind, 'bar');
  assert.deepEqual([s.x, s.y, s.colour, s.series], [{ field: 'desk' }, { field: 'today' }, { field: 'region' }, { by: 'region' }]);
  assert.deepEqual([s.legend, s.onClick, s.export, s.a11y.table], ['bottom', 'explain', ['png', 'svg', 'csv', 'spec'], true]);
  const text = C.specText({ data: { rows: [1, 2, 3, 4, 5] }, title: 't', kind: 'line', x: 'd' });
  assert.ok(text.indexOf('"kind"') < text.indexOf('"title"') && text.indexOf('"title"') < text.indexOf('"data"'));
  assert.match(text, /… 5 rows/);
  assert.match(C.specText({ kind: 'line', data: { rows: [1, 2, 3, 4] } }, { rows: true }), /4\n/);
  assert.throws(() => C.registerKind('line', { build: () => null }), /registered already/);
  assert.ok(C.kindList().length >= 21);
});

test('data: rows of every shape, bins, boxes, quantile classes', () => {
  assert.deepEqual(C.rowsOf({ columns: ['a', { name: 'b' }], rows: [[1, 2]] }), [{ a: 1, b: 2 }]);
  assert.deepEqual(C.rowsOf({ values: [{ a: 1 }] }), [{ a: 1 }]);
  assert.deepEqual(C.rowsOf([{ a: 1 }]), [{ a: 1 }]);
  const b = C.bin([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], { bins: 5 });
  assert.equal(b.length, 5);
  assert.equal(b.reduce((s, x) => s + x.count, 0), 10);
  assert.deepEqual(b.map((x) => x.count), [2, 2, 2, 2, 2]);
  const box = C.boxStats([1, 2, 3, 4, 5, 6, 7, 8, 100]);
  assert.deepEqual(box.box, [1, 3, 5, 7, 8]);
  assert.deepEqual(box.outliers, [100]);
  const q = C.quantileBreaks([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, null], 5);
  assert.deepEqual(q.breaks, [3, 5, 7, 9]);
  assert.deepEqual([C.classOf(1, q.breaks), C.classOf(3, q.breaks), C.classOf(10, q.breaks), C.classOf(null, q.breaks)], [0, 1, 4, -1]);
  assert.deepEqual(C.members([{ d: 'b' }, { d: 'a' }, { d: 'b' }], 'd', ['a']), ['a', 'b']);
});

test('the condition language: comparisons, arithmetic, datum, precedence; no calls, no eval', () => {
  const r = { hypothetical: -5, var99: 4, MTM: -1, 'P&L': 3, desk: 'FX' };
  assert.equal(C.compileCondition('hypothetical < -var99')(r), true);
  assert.equal(C.compileCondition('datum.MTM < 0')(r), true);
  assert.equal(C.compileCondition("datum['P&L'] >= 3 && desk == 'FX'")(r), true);
  assert.equal(C.compileCondition('1 + 2 * 3 == 7 || false')({}), true);
  assert.equal(C.compileCondition('!(var99 > 10)')(r), true);
  assert.equal(C.compileCondition('(MTM + var99) / 3')(r), 1);
  assert.throws(() => C.compileCondition('alert(1)'), /has more after|cannot use/);
  assert.throws(() => C.compileCondition('a = 1'), /cannot read/);
  assert.throws(() => C.compileCondition('a <'), /ends too soon/);
  assert.deepEqual(C.conditionFields('hypothetical < -var99 && datum.MTM > 0'), ['hypothetical', 'var99', 'MTM']);
});

test('formats and the theme come from drishti-core', () => {
  assert.equal(C.formatterFor('money')(-30205543.2), '−30.21m');
  assert.equal(C.formatterFor('amount0')(1234.5), '1,235');
  assert.equal(C.formatterFor('pct2')(0.01234), '1.23%');
  assert.equal(C.formatterFor()(1234567), '1.23m');
  assert.equal(C.formatterFor()(12.345), '12.35');
  assert.equal(C.formatterFor({ currency: 'USD' })(12), '$12.00');
  assert.equal(th.tokens.link, '#7fb2ff');
  assert.equal(th.palette[0], th.tokens.link);
  assert.equal(C.toneColour(th, 'bad'), th.tokens.bad);
  assert.equal(C.chartTheme({ theme: 'light' }).dark, false);
  assert.equal(C.chartTheme({ theme: 'terminal', density: 'terminal' }).size.font, 10.5);
  assert.equal(C.rampColour(['#000000', '#ffffff'], 5, 0, 10), 'rgb(128,128,128)');
});

test('line: wide series, a negated dashed one, conditional marks, annotations, ids on every item', () => {
  const rows = [{ date: '2026-01-02', hyp: 1, v: 2 }, { date: '2026-01-05', hyp: -3, v: 2 }, { date: '2026-01-06', hyp: 0.5, v: 2 }];
  const b = build({ kind: 'line', id: 'date', data: { rows }, x: { field: 'date', type: 'time' }, series: [{ field: 'hyp' }, { field: 'v', negate: true, style: 'dashed', tone: 'bad' }],
    marks: [{ when: 'hyp < -v', tone: 'bad', name: 'Exception' }], annotations: [{ type: 'line', axis: 'y', value: 0 }, { type: 'band', axis: 'x', from: '2026-01-02', to: '2026-01-05', label: 'stress' }] });
  const [hyp, v, mark] = b.option.series;
  assert.equal(b.option.xAxis.type, 'time');
  assert.deepEqual(v.data.map((d) => d.value[1]), [-2, -2, -2]);
  assert.equal(v.lineStyle.type, 'dashed');
  assert.equal(v.itemStyle.color, th.tokens.bad);
  assert.deepEqual(mark.data.map((d) => d.ids), [['2026-01-05']]);
  assert.equal(hyp.markLine.data[0].yAxis, 0);
  assert.equal(hyp.markArea.data.length, 1);
  assert.deepEqual(b.legend.map((l) => l.style), ['line', 'dash', 'dot']);
  assert.equal(b.table.rows.length, 3);
  assert.match(b.summary, /2 series, 3 points, from 2026-01-02 to 2026-01-06/);
});

test('bar: grouped, stacked to 100%, horizontal and sorted, coloured by a condition; long rows by a field', () => {
  const g = build({ kind: 'bar', data: { rows: DESKS }, x: 'desk', series: [{ field: 'today' }, { field: 'yday' }] });
  assert.deepEqual(g.option.xAxis.data, ['Rates', 'FX', 'Credit']);
  assert.equal(g.option.series.length, 2);
  const p = build({ kind: 'bar', data: { rows: DESKS }, x: 'desk', series: [{ field: 'today' }, { field: 'yday' }], stack: 'percent' });
  p.option.xAxis.data.forEach((c, i) => assert.ok(Math.abs(p.option.series.reduce((s, x) => s + Math.abs(x.data[i].value), 0) - 100) < 1e-9));
  const hz = build({ kind: 'bar', data: { rows: DESKS }, x: 'desk', y: 'today', orient: 'horizontal', sort: 'value desc', colourWhen: [{ when: 'today < 0', tone: 'bad' }] });
  assert.equal(hz.option.yAxis.type, 'category');
  assert.deepEqual(hz.option.yAxis.data, ['Rates', 'Credit', 'FX']);
  assert.equal(hz.option.series[0].data[2].itemStyle.color, th.tokens.bad);
  const long = build({ kind: 'bar', data: { rows: [{ d: 'A', r: 'x', v: 1 }, { d: 'A', r: 'y', v: 2 }, { d: 'B', r: 'x', v: 3 }] }, x: 'd', y: 'v', series: { by: 'r' }, stack: true });
  assert.deepEqual(long.option.series.map((s) => s.name), ['x', 'y']);
  assert.deepEqual(long.option.series[1].data.map((d) => d.value), [2, null]);
  assert.equal(long.option.series[0].stack, 'total');
});

test('scatter, bubble, histogram with its tail, waterfall, box plot, heatmap, candlestick', () => {
  const trades = [{ ID: 'a', v: 1, m: 5, n: 10, d: 'X' }, { ID: 'b', v: 2, m: -1, n: 40, d: 'Y' }, { ID: 'c', v: 3, m: 2, n: 0, d: 'X' }];
  const sc = build({ kind: 'bubble', id: 'ID', data: { rows: trades }, x: 'v', y: 'm', size: 'n', colour: 'd' });
  assert.deepEqual(sc.option.series.map((s) => s.name), ['X', 'Y']);
  assert.ok(sc.option.series[1].data[0].symbolSize > sc.option.series[0].data[0].symbolSize);
  assert.deepEqual(sc.option.series[0].data.map((d) => d.ids[0]), ['a', 'c']);
  const strip = build({ kind: 'scatter', data: { rows: trades }, x: { field: 'm', type: 'value' }, y: { field: 'd', type: 'category' } });
  assert.equal(strip.option.yAxis.type, 'category');
  assert.deepEqual(strip.option.series[0].data[0].value, [5, 'X']);
  const hist = build({ kind: 'histogram', data: { rows: [-5, -4, -1, 0, 1, 2, 3].map((x) => ({ p: x })) }, value: 'p', bins: 4, tail: { below: -3, tone: 'bad' } });
  const bars = hist.option.series[0].data;
  assert.equal(bars.reduce((s, d) => s + d.value[2], 0), 7);
  assert.equal(bars[0].itemStyle.color, th.tokens.bad);
  assert.notEqual(bars[3].itemStyle.color, th.tokens.bad);
  const wf = build({ kind: 'waterfall', data: { rows: [{ s: 'open', v: 10 }, { s: 'up', v: 3 }, { s: 'down', v: -5 }, { s: 'close', v: 8 }] }, category: 's', value: 'v' });
  assert.deepEqual(wf.option.series[0].data.map((d) => d.value), [0, 10, 8, 0]);
  assert.deepEqual(wf.option.series[1].data.map((d) => d.real), [10, 3, -5, 8]);
  assert.equal(wf.option.series[1].data[1].itemStyle.color, th.tokens.ok);
  const bx = build({ kind: 'boxplot', data: { rows: [1, 2, 3, 4, 50].map((x) => ({ d: 'A', v: x })) }, category: 'd', value: 'v' });
  assert.deepEqual(bx.option.series[1].data.map((d) => d.value), [['A', 50]]);
  const hm = build({ kind: 'heatmap', data: { rows: [{ x: 'a', y: 'p', v: -4 }, { x: 'b', y: 'p', v: 2 }] }, x: 'x', y: 'y', value: 'v', scale: { type: 'diverging' } });
  assert.deepEqual([hm.option.visualMap.min, hm.option.visualMap.max], [-4, 4]);
  assert.deepEqual(hm.table.rows, [['p', -4, 2]]);
  const cs = build({ kind: 'candlestick', data: { rows: [{ d: '1', open: 1, close: 2, low: 0.5, high: 2.5 }] }, x: 'd' });
  assert.deepEqual(cs.option.series[0].data[0].value, [1, 2, 0.5, 2.5]);
});

test('pie, donut with Other, gauge with bands, treemap, sankey; layers share their categories', () => {
  const rows = 'abcdefghijklmn'.split('').map((c, i) => ({ c: c, v: 14 - i }));
  const pie = build({ kind: 'donut', data: { rows }, category: 'c', value: 'v', top: 5 });
  assert.equal(pie.option.series[0].data.length, 5);
  assert.equal(pie.option.series[0].data[4].name, 'Other');
  assert.equal(pie.option.series[0].data[4].value, rows.slice(4).reduce((s, r) => s + r.v, 0));
  assert.equal(pie.option.series[0].radius[0], '52%');
  const g = build({ kind: 'gauge', data: { rows: [{ u: 0.78 }] }, value: { field: 'u' }, min: 0, max: 1, bands: [{ to: 0.7, tone: 'good' }, { to: 1, tone: 'bad' }] });
  assert.deepEqual(g.option.series[0].axisLine.lineStyle.color.map((x) => x[0]), [0.7, 1]);
  const tm = build({ kind: 'treemap', data: { rows: [{ a: 'X', b: '1', v: 2 }, { a: 'X', b: '2', v: 3 }, { a: 'Y', b: '1', v: 4 }] }, levels: ['a', 'b'], value: 'v' });
  assert.deepEqual(tm.option.series[0].data.map((n) => [n.name, n.value, n.children.length]), [['X', 5, 2], ['Y', 4, 1]]);
  const sk = build({ kind: 'sankey', data: { rows: [{ s: 'A', t: 'B', v: 1 }, { s: 'A', t: 'B', v: 2 }, { s: 'B', t: 'C', v: 1 }] }, source: 's', target: 't', value: 'v' });
  assert.deepEqual(sk.option.series[0].links.map((l) => l.value), [3, 1]);
  const layers = build({ kind: 'layer', layers: [{ kind: 'bar', data: { rows: [{ d: 'A', v: 1 }] }, x: 'd', y: 'v' }, { kind: 'line', data: { rows: [{ d: 'B', w: 2 }, { d: 'A', w: 3 }] }, x: 'd', y: 'w' }] }, []);
  assert.deepEqual(layers.option.xAxis.data, ['A', 'B']);
  assert.deepEqual(layers.option.series.map((s) => [s.type, s.layer]), [['bar', 0], ['line', 1]]);
  assert.deepEqual(layers.option.series[1].data.map((d) => d.value), [3, 2]);
});

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
  assert.deepEqual(R.RISK_KINDS.map((k) => C.kindOf(k).family), ['risk', 'risk', 'risk', 'risk']);
});

test('Vega-Lite: the report\'s bar and the Python sample\'s layers translate whole and draw', () => {
  const bar = C.fromVegaLite({ mark: { type: 'bar', cornerRadiusEnd: 3 }, encoding: { x: { field: 'DESK', type: 'nominal', sort: '-y', title: 'Desk' }, y: { field: 'MTM', type: 'quantitative', title: 'Net MTM' },
    color: { condition: { test: 'datum.MTM < 0', value: '#b3261e' }, value: '#4f7cff' }, tooltip: [{ field: 'DESK' }, { field: 'MTM', format: ',.0f' }] } }, { rows: [{ DESK: 'FX', MTM: -3 }, { DESK: 'Rates', MTM: 5 }] });
  assert.deepEqual(bar.unsupported, []);
  assert.deepEqual([bar.spec.kind, bar.spec.sort, bar.spec.x.title, bar.spec.tone, bar.spec.colourWhen[0].tone], ['bar', 'value desc', 'Desk', 'link', 'bad']);
  const bb = build(bar.spec);
  assert.deepEqual(bb.option.xAxis.data, ['Rates', 'FX']);
  assert.equal(bb.option.series[0].data[1].itemStyle.color, th.tokens.bad);
  const py = C.fromVegaLite({ width: 'container', height: 'container', layer: [
    { data: { values: [{ desk: 'FX', pnl1d: 10 }, { desk: 'FX', pnl1d: 30 }, { desk: 'RATES', pnl1d: -5 }] }, mark: { type: 'circle', opacity: 0.6, size: 40 }, encoding: { x: { field: 'pnl1d', type: 'quantitative', title: 'One-day P&L' }, y: { field: 'desk', type: 'nominal', title: null } } },
    { data: { values: [{ desk: 'FX', median: 20 }, { desk: 'RATES', median: -5 }] }, mark: { type: 'tick', thickness: 3, size: 22, color: '#d9534f' }, encoding: { x: { field: 'median', type: 'quantitative' }, y: { field: 'desk', type: 'nominal' } } }] });
  assert.deepEqual(py.unsupported, []);
  assert.equal(py.spec.kind, 'layer');
  assert.deepEqual(py.spec.layers.map((l) => [l.kind, l.symbol, l.tone || null]), [['scatter', 'circle', null], ['scatter', 'tick-v', 'bad']]);
  const pb = build(py.spec, []);
  assert.deepEqual(pb.option.yAxis.data, ['FX', 'RATES']);
  assert.deepEqual(pb.option.series[1].symbolSize === undefined ? pb.option.series[1].data[0].symbolSize : null, [3, 20]);
});

test('Vega-Lite: aggregates, bins, arcs, rects, boxplots, temporal lines with colour, transforms, rules; the rest listed', () => {
  const rows = [{ d: 'A', r: 'x', v: 1, t: '2026-01-01' }, { d: 'A', r: 'y', v: 2, t: '2026-01-02' }, { d: 'B', r: 'x', v: 4, t: '2026-01-01' }];
  const agg = C.fromVegaLite({ mark: 'bar', encoding: { x: { field: 'd', type: 'nominal' }, y: { aggregate: 'sum', field: 'v', type: 'quantitative' } } }, { rows });
  assert.deepEqual(agg.spec.data.rows, [{ d: 'A', v: 3 }, { d: 'B', v: 4 }]);
  const cnt = C.fromVegaLite({ mark: 'bar', encoding: { x: { field: 'd', type: 'nominal' }, y: { aggregate: 'count', type: 'quantitative' } } }, { rows });
  assert.deepEqual(cnt.spec.data.rows.map((r) => r.__count), [2, 1]);
  assert.equal(C.fromVegaLite({ mark: 'bar', encoding: { x: { field: 'v', bin: { maxbins: 10 } }, y: { aggregate: 'count' } } }, { rows }).spec.kind, 'histogram');
  const arc = C.fromVegaLite({ mark: { type: 'arc', innerRadius: 50 }, encoding: { theta: { field: 'v', type: 'quantitative' }, color: { field: 'd', type: 'nominal' } } }, { rows });
  assert.deepEqual([arc.spec.kind, arc.spec.category.field], ['donut', 'd']);
  assert.equal(C.fromVegaLite({ mark: 'rect', encoding: { x: { field: 'd' }, y: { field: 'r' }, color: { field: 'v', type: 'quantitative' } } }, { rows }).spec.kind, 'heatmap');
  assert.equal(C.fromVegaLite({ mark: 'boxplot', encoding: { x: { field: 'd', type: 'nominal' }, y: { field: 'v', type: 'quantitative' } } }, { rows }).spec.kind, 'boxplot');
  const area = C.fromVegaLite({ mark: 'area', encoding: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative', stack: 'normalize' }, color: { field: 'r', type: 'nominal' } } }, { rows });
  assert.deepEqual([area.spec.kind, area.spec.x.type, area.spec.series.by, area.spec.stack], ['area', 'time', 'r', 'percent']);
  const tf = C.fromVegaLite({ transform: [{ filter: 'datum.v > 1' }, { calculate: 'datum.v * 2', as: 'w' }, { filter: { field: 'd', oneOf: ['A'] } }], mark: 'point', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' } } }, { rows });
  assert.deepEqual(tf.spec.data.rows.map((r) => r.w), [4]);
  const ruled = C.fromVegaLite({ layer: [{ mark: 'line', encoding: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' } } }, { mark: { type: 'rule', color: 'red' }, encoding: { y: { datum: 3 } } }] }, { rows });
  assert.deepEqual(ruled.spec.annotations, [{ type: 'line', axis: 'y', value: 3, tone: 'bad' }]);
  const rest = C.fromVegaLite({ facet: { row: { field: 'd' } }, mark: 'text', encoding: { text: { field: 'd' } } }, { rows });
  assert.equal(rest.spec, null);
  assert.deepEqual(rest.unsupported, ['$.facet', '$.facet', '$.encoding.text', '$.mark (text)']);
  assert.match(C.fromVegaLite({ data: { url: 'http://x' }, mark: 'bar' }).refused, /may not load anything from a URL/);
  assert.deepEqual(['#b3261e', '#4f7cff', '#2e7d32', '#e8a33a', '#888888', 'red'].map(C.colourTone), ['bad', 'link', 'good', 'accent', 'muted', 'bad']);
  assert.deepEqual([',.0f', ',.2f', '.0%', '.2%', '~s', '$,.0f', 'x'].map(C.d3Format), ['amount0', 'amount2', 'pct0', 'pct2', 'compact', 'amount0', null]);
});

test('linked selection: a grid\'s selection goes out on the bus, a chart\'s comes back in, never echoed', () => {
  const b = new EventBus(), selected = [];
  const model = { events: new EventBus(), selection: { selectAll: (ids) => { selected.push(ids.slice()); model.events.emit('selectionChanged', { ids: ids }); } } };
  const heard = [];
  b.on(C.SELECTION_EVENT, (p) => heard.push([p.channel, p.ids.join(), p.source === model ? 'grid' : 'other']));
  const off = C.linkGrid({ model: model }, { channel: 'trades', bus: b });
  model.events.emit('selectionChanged', { ids: ['T1', 'T2'] });
  C.publishSelection('trades', ['T9'], { bus: b, source: 'chart' });
  C.publishSelection('other', ['T5'], { bus: b, source: 'chart' });
  assert.deepEqual(selected, [['T9']]);
  assert.deepEqual(heard, [['trades', 'T1,T2', 'grid'], ['trades', 'T9', 'other'], ['other', 'T5', 'other']]);
  off();
  C.publishSelection('trades', ['T3'], { bus: b });
  assert.equal(selected.length, 1);
  assert.throws(() => C.linkGrid({}, { channel: 'x' }), /give a drishti-grid/);
});

test('the bundles: the chart and the risk library load under Node with the same API', () => {
  const chart = require('../../web/static/elements/drishti-chart/drishti_chart.js');
  const risk = require('../../web/static/elements/drishti-risk/drishti_risk.js');
  assert.equal(chart.library, 'drishti-chart');
  assert.equal(typeof chart.Chart, 'function');
  assert.equal(typeof chart.fromVegaLite, 'function');
  assert.ok(chart.kindOf('risk.backtest'));
  assert.equal(risk.trafficLight(12).zone, 'red');
});
