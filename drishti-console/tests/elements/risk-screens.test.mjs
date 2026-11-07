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
/* The VaR screens' charts as specs under Node (the screens moved onto Drishti Elements, ELEMENTS.md section 12 step 9): the backtest
   showing the server's zone and count (the page's own count kept beside it for a check), the stress window's area with today's line,
   the candidates and the selection, the MR lens's histogram of the server's bins and its components' waterfall, and the scenario
   matrix's chart in each of its four types with the reference and the tail marked. Each spec passes checkSpec and builds; the drawn
   screens are tested in their browser files. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../../web/static/elements/drishti-chart/src/drishti_index.js';
import '../../web/static/elements/drishti-risk/src/drishti_index.js';

const th = C.chartTheme({ theme: 'terminal' });
const env = { theme: th, fmt: C.formatterFor('compact'), xfmt: C.formatterFor() };
const build = (spec) => {
  assert.deepEqual(C.checkSpec(spec), []);
  const s = C.normalizeSpec(spec);
  return C.kindOf(s.kind).build(s, C.rowsOf(s.data), env);
};

test('backtest: the server\'s zone and count on the card, the page\'s count kept, the hypothetical exceptions marked too', () => {
  const days = Array.from({ length: 30 }, (x, i) => ({ date: '2026-10-' + String(i + 1).padStart(2, '0'), actual: i % 7 === 0 ? -50 : 5, hypothetical: i % 5 === 0 ? -60 : 4, var990: 40 }));
  const b = build({ kind: 'risk.backtest', data: { rows: days }, id: 'date', x: { field: 'date', type: 'category' }, pnl: 'actual', actual: 'hypothetical', var: 'var990',
    confidence: 0.99, window: days.length, light: { exceptions: 5, zone: 'amber', multiplier: 3.4 },
    marks: [{ when: 'hypothetical < -var990', as: 'point', tone: 'warn', field: 'hypothetical', name: 'Hypothetical exception' }] });
  assert.deepEqual([b.figures.counted, b.figures.exceptions, b.figures.zone, b.figures.fromServer], [5, 5, 'amber', true]);
  assert.match(b.badge.text, /^amber · 5 exceptions · multiplier 3\.40$/);
  assert.equal(b.option.series[0].data.length, 30);
  assert.equal(b.option.series.find((s) => s.name === 'Hypothetical exception').data.length, 6);
  const own = build({ kind: 'risk.backtest', data: { rows: days }, x: { field: 'date', type: 'category' }, pnl: 'actual', var: 'var990', window: 30 });
  assert.deepEqual([own.figures.exceptions, own.figures.fromServer], [5, false]);                  // no server: the page counts
});

test('stress window, MR lens: an area with annotations, the server\'s bins, a waterfall to the total', () => {
  const sw = build({ kind: 'area', data: { rows: [{ start: '2020-01-02', var: 10 }, { start: '2020-01-03', var: 12 }] }, id: 'start', x: { field: 'start', type: 'category' },
    series: [{ field: 'var', name: 'stressed VaR', tone: 'link' }], legend: 'none', zoom: true,
    annotations: [{ type: 'line', axis: 'y', value: 11, tone: 'muted', label: 'today', showValue: false }, { type: 'point', x: '2020-01-03', y: 12, tone: 'bad', label: 'worst' }] });
  assert.equal(sw.option.series[0].data.length, 2);
  assert.equal(sw.option.series[0].markPoint.data.length, 1);
  const h = build({ kind: 'risk.pnl-distribution', data: { rows: [] }, binned: { edges: [-3, -1, 1, 3], counts: [2, 6, 3] }, var: 2, es: 2.5 });
  assert.equal(h.figures.computedHere, false);
  const w = build({ kind: 'waterfall', data: { rows: [{ step: 'A', change: 5 }, { step: 'B', change: -2 }, { step: 'Total', change: 3, total: true }] }, category: { field: 'step' },
    value: { field: 'change' }, totals: { field: 'total' }, up: 'bad', down: 'good', total: 'warn' });
  assert.ok(w.option.series.length >= 2);
});

test('the scenario matrix\'s chart: line, dots, area and bars, the reference a line on each, the tail marked', () => {
  const rows = [{ x: '2024-01-02', v: -5, ref: -4, flag: 1 }, { x: '2024-01-03', v: 3, ref: -4, flag: 0 }, { x: '2024-01-04', v: null, ref: -4, flag: 0 }];
  for (const [kind, type] of [['line', 'line'], ['line', 'scatter'], ['area', 'line'], ['bar', 'bar']]) {
    const b = build({ kind: kind, data: { rows: rows }, id: 'x', x: { field: 'x', type: 'category' }, legend: 'none',
      series: [{ field: 'v', name: 'scenario P&L', type: type }, { field: 'ref', name: '−VaR', type: 'line', style: 'dashed', tone: 'bad' }],
      marks: [{ when: 'flag >= 1', field: 'v', tone: 'warn', name: 'in the tail' }], y: { zero: false } });
    assert.equal(b.option.series[0].type, type);
    assert.equal(b.option.series[0].data.filter((d) => d && d.value !== null).length, 2);           // a day outside the window: no point
    assert.equal(b.option.series[1].type, 'line');
    assert.equal(b.option.series.find((s) => s.name === 'in the tail').data.length, 1);
    assert.equal(b.option.yAxis[0].scale, true);
  }
});
