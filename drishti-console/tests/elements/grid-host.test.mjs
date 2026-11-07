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
/* drishti-grid 0.4.0 under Node: what a screen moving onto the grid needs of it (ELEMENTS.md section 12, the BI and Pivot family): the
   server's own aggregations in the wells, a server pivot's figures shown by the answer's own fields (measureText: units, show-as, the
   server's text), an answer the host fetched itself (showAnswer, a patch), the open state given back, and a value's label kept. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';

const COLS = [{ field: 'DESK', header: 'Desk' }, { field: 'NOTIONAL', type: 'number', header: 'Notional' },
  { id: 'm:var', field: 'var', header: 'VaR', type: 'number', measure: 'var()', additive: false, enableRowGroup: false, enablePivot: false }];

function answer(v) {
  const node = (d) => ({ kind: 'node', key: 'DESK=' + d, label: d, value: d, depth: 1, path: [['DESK', d]], expandable: false, expanded: false });
  return { rowDimensions: ['DESK'], columnDimensions: [],
    measures: [{ id: 'var()', label: 'VaR', kind: 'holistic', unit: 'money' }, { id: 'count()', label: 'Rows', kind: 'mergeable', unit: 'count' }, { id: 'pct', label: 'Share', showAs: 'pct-grand' }],
    rows: [node('FX'), node('Rates'), { kind: 'grand', key: '*', label: 'Total', depth: 0, path: [] }],
    columns: [{ kind: 'grand', key: '*', label: 'Total', path: [] }],
    cells: { 'DESK=FX|*': { values: { VaR: v, Rows: 3, Share: 0.25 }, texts: {}, coords: { DESK: 'FX' } },
      'DESK=Rates|*': { values: { VaR: 2.5e6, Rows: 9, Share: 0.75 }, texts: { VaR: '•••' }, tones: { VaR: 't-neg' }, coords: { DESK: 'Rates' } },
      '*|*': { values: { VaR: 3.1e9, Rows: 12, Share: 1 }, coords: {} } } };
}

test('the server\'s aggregations are offered beside the browser\'s, limited to those a server knows, and a label that was the measure follows its function', () => {
  const m = new G.GridModel({ columns: COLS, rows: [], clientAggregation: false, serverPivot: () => Promise.resolve(answer(1)),
    serverFuncs: [{ name: 'median', label: 'Median' }], valueFuncs: ['sum', 'count', 'avg'] });
  assert.deepEqual(m.wells.funcs().map((f) => f.name), ['sum', 'count', 'avg', 'median']);
  assert.equal(m.wells.funcLabel('median'), 'Median');
  m.wells.setState({ rows: ['DESK'], columns: [], values: [{ colId: 'NOTIONAL', aggFunc: 'sum', as: 'sum(NOTIONAL)' }, { colId: 'NOTIONAL', aggFunc: 'sum', as: 'Kept', showAs: 'rank' }], filters: [] });
  m.wells.setAggregation(0, 'median');
  m.wells.setAggregation(1, 'avg');
  const v = m.wells.toView();
  assert.equal(v.values[0].measure, 'median(NOTIONAL)');
  assert.equal(v.values[0].as, 'Notional (median)');                    // the old label was only the measure's text: it follows
  assert.deepEqual(v.values[1], { measure: 'avg(NOTIONAL)', as: 'Kept', showAs: 'rank' });
  assert.throws(() => m.wells.setAggregation(0, 'first'), /no aggregation first/);
  assert.deepEqual(G.serverRequest(v).view.values[1], { measure: 'avg(NOTIONAL)', as: 'Kept', showAs: 'rank' });
});

test('a server pivot shows the server\'s text, ≈ on an approximate measure, and the answer\'s own format; a host\'s answer and patch go in as they are', async () => {
  const a = answer(1234.5);
  a.measures[0].exact = false;
  const m = new G.GridModel({ columns: COLS, rows: [], clientAggregation: false, serverPivot: () => Promise.resolve(a), pivotFormat: G.answerFormat });
  m.wells.setState({ rows: ['DESK'], columns: [], values: [{ colId: 'm:var', as: 'VaR' }], filters: [] });
  await m.setPivotMode(true);
  const cols = m.shownColumns(), varCol = cols.findIndex((c) => c.measure === 'VaR'), rows = cols.findIndex((c) => c.measure === 'Rows');
  assert.equal(m.cellText(0, varCol), '≈ 1,234.50');
  assert.equal(m.cellText(1, varCol), '•••');                           // the server's own text (a mask) wins
  assert.equal(m.cellText(2, varCol), '≈ 3.10 bn');
  assert.equal(m.cellText(2, rows), '12');                               // a count: whole
  assert.equal(m.cellText(2, cols.findIndex((c) => c.measure === 'Share')), '100.00%');
  const seen = [];
  m.on('pivotChanged', () => seen.push('pivot'));
  m.on('serverTotalsUpdated', () => seen.push('patch'));
  const b = answer(99);
  m.showAnswer(b, { patched: true });
  assert.equal(m.answer, b);
  assert.equal(m.cellValue(0, varCol), 99);
  m.showAnswer(answer(7));
  assert.deepEqual(seen, ['patch', 'pivot']);
  m.setOpenState({ expanded: [{ axis: 'rows', path: [['DESK', 'FX']], by: 'BOOK' }], expandTo: { rows: 1, columns: 2 } });
  assert.deepEqual(m.pivotView().expanded, [{ axis: 'rows', path: [['DESK', 'FX']], by: 'BOOK' }]);
  assert.deepEqual(m.pivotView().expandTo, { rows: 1, columns: 2 });
});

test('measureText: units, show-as and text, never a computation', () => {
  assert.equal(G.measureText('a<b', {}), 'a<b');
  assert.equal(G.measureText(2.5e9, { unit: 'money' }), '2.50 bn');
  assert.equal(G.measureText(-4, { unit: 'count' }), '-4');
  assert.equal(G.measureText(7, { id: 'trades' }), '7');
  assert.equal(G.measureText(null, {}), '');
  assert.equal(G.answerFormat({ showAs: 'rank' })(2), '2');
});
