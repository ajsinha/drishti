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
/* The pivot's field handling under Node (PIVOT_GUIDE.md sections 2 and 4; docs/lenses/mockups/pivot-fields): a hierarchy is one item with
   its levels side by side and a level stepper, any number of row and column fields, the functions valid for each field (a rate never
   summed, a text counted, VaR computed per cell), a function's argument (wavg's weight, percentile's q), a custom formula, show-as, several
   values of one field, the field pickers' groups (hierarchies, folders, More fields) and one number format per column. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';
import { fieldGroups } from '../../web/static/elements/drishti-grid/src/drishti_grid-fields.js';

const ALL = ['sum', 'count', 'count_distinct', 'avg', 'wavg', 'min', 'max', 'median', 'percentile', 'var_samp', 'stddev_samp'];
const COLS = [
  { field: 'MGMT.DIVISION', header: 'MGMT › division', enableRowGroup: true, enablePivot: true, enableValue: false, funcs: [], listHidden: true },
  { field: 'MGMT.DESK', header: 'MGMT › desk', enableRowGroup: true, enablePivot: true, enableValue: false, funcs: [], listHidden: true },
  { field: 'MGMT.BOOK', header: 'MGMT › book', enableRowGroup: true, enablePivot: true, enableValue: false, funcs: [], listHidden: true },
  { field: 'DESK', header: 'desk', enableRowGroup: true, enablePivot: true, funcs: ['count', 'count_distinct'], listHidden: true },
  { field: 'CURRENCY', header: 'currency', enableRowGroup: true, enablePivot: true, enableValue: true, funcs: ['count', 'count_distinct'] },
  { field: 'RISK_CLASS', header: 'Risk class', enableRowGroup: true, enablePivot: true, funcs: ['count', 'count_distinct'] },
  { field: 'PV', header: 'PV', type: 'number', funcs: ALL, unit: 'money', enableRowGroup: true, enablePivot: true },
  { field: 'NOTIONAL', header: 'Notional', type: 'number', funcs: ALL, unit: 'money', enableRowGroup: true, enablePivot: true },
  { field: 'RATE', header: 'Rate', type: 'number', funcs: ALL.filter((f) => f !== 'sum'), unit: 'rate', enableRowGroup: true, enablePivot: true },
  { id: 'm:var', field: 'var', header: 'VaR', type: 'number', measure: 'var()', additive: false, enableRowGroup: false, enablePivot: false, role: 'measure', folder: 'VaR', perCell: true },
  { id: 'm:comp_var', field: 'comp_var', header: 'Comp VaR', type: 'number', measure: 'comp_var()', additive: false, enableRowGroup: false, enablePivot: false, role: 'measure', folder: 'Contribution', perCell: true }];
const HIER = [{ id: 'MGMT', title: 'Management', levels: [{ field: 'MGMT.DIVISION', label: 'Division' }, { field: 'MGMT.DESK', label: 'Desk' }, { field: 'MGMT.BOOK', label: 'Book' }] }];

function model() { return new G.GridModel({ columns: COLS, rows: [], clientAggregation: false, serverPivot: () => Promise.resolve({}), hierarchies: HIER }); }

test('a hierarchy is one item: its levels sit side by side and the stepper adds the next level or takes the deepest away', () => {
  const w = model().wells;
  assert.equal(w.hierarchies.length, 1);
  w.setState({ rows: ['MGMT.DESK', 'CURRENCY'], columns: ['RISK_CLASS'], values: [], filters: [] });
  assert.deepEqual(w.segments('rows').map((s) => (s.hierarchy ? s.hierarchy.id : s.ids[0])), ['MGMT', 'CURRENCY']);
  assert.deepEqual(w.stepHierarchy('rows', 'MGMT', 1), { ok: true, why: null });
  assert.deepEqual(w.state.rows, ['MGMT.DESK', 'MGMT.BOOK', 'CURRENCY']);   // the next level right after the deepest, before the other field
  assert.equal(w.stepHierarchy('rows', 'MGMT', 1).ok, false);                // no level below Book
  assert.match(w.stepHierarchy('rows', 'MGMT', 1).why, /no level below Book/);
  w.stepHierarchy('rows', 'MGMT', -1);
  assert.deepEqual(w.state.rows, ['MGMT.DESK', 'CURRENCY']);
  assert.match(w.stepHierarchy('rows', 'MGMT', -1).why, /top level/);
  w.setHierarchyLevels('rows', 'MGMT', ['MGMT.BOOK', 'MGMT.DIVISION']);       // in the hierarchy's order, where it was
  assert.deepEqual(w.state.rows, ['MGMT.DIVISION', 'MGMT.BOOK', 'CURRENCY']);
  w.setHierarchyLevels('columns', 'MGMT', ['MGMT.DESK']);                     // to Columns: out of Rows
  assert.deepEqual(w.state.rows, ['CURRENCY']);
  assert.deepEqual(w.state.columns, ['RISK_CLASS', 'MGMT.DESK']);
});

test('any number of rows and columns: every field may be added, nested in the order given', () => {
  const w = model().wells;
  ['MGMT.DIVISION', 'MGMT.DESK', 'MGMT.BOOK', 'CURRENCY', 'PV', 'NOTIONAL'].forEach((f) => w.drop(f, 'rows'));
  ['RISK_CLASS', 'DESK', 'RATE'].forEach((f) => w.drop(f, 'columns'));
  const v = w.toView();
  assert.equal(v.rows.length, 6);
  assert.equal(v.columns.length, 3);
});

test('the functions valid for a field: a rate is never summed, a text is counted, VaR is computed per cell; invalid ones say why', () => {
  const w = model().wells;
  assert.deepEqual(w.funcsFor('PV').filter((f) => !f.disabled).map((f) => f.name), ALL);
  const rate = w.funcsFor('RATE');
  assert.equal(rate[0].name, 'sum');
  assert.equal(rate[0].disabled, true);
  assert.match(rate[0].why, /never summed/);
  const ccy = w.funcsFor('CURRENCY');
  assert.deepEqual(ccy.filter((f) => !f.disabled).map((f) => f.label), ['Count', 'Distinct count']);
  assert.match(ccy.find((f) => f.name === 'sum').why, /not a number/);
  assert.deepEqual(w.funcsFor('m:var'), []);
  w.setState({ rows: ['CURRENCY'], columns: [], values: [{ colId: 'm:var', as: 'VaR' }, { colId: 'RATE', aggFunc: 'avg' }], filters: [] });
  assert.match(w.setAggregation(0, 'sum').why, /computed per cell/);
  assert.match(w.setAggregation(1, 'sum').why, /never summed/);
  assert.equal(w.toView().values[1].measure, 'avg(RATE)');
  assert.deepEqual(G.FUNC_LABELS.wavg, 'Weighted average…');
});

test('a function with an argument, a custom formula, show-as and several values of one field', () => {
  const w = model().wells;
  w.setState({ rows: ['CURRENCY'], columns: [], values: [{ colId: 'PV', aggFunc: 'sum' }], filters: [] });
  w.drop('PV', 'values', undefined, { aggFunc: 'count' });                  // Sum of PV and Count of PV side by side
  assert.equal(w.setAggregation(0, 'wavg').ok, false);                       // a weight is needed
  assert.equal(w.setAggregation(0, 'wavg', ['NOTIONAL']).ok, true);
  assert.equal(w.setAggregation(1, 'percentile', [1.5]).ok, false);
  assert.equal(w.setAggregation(1, 'percentile', [0.99]).ok, true);
  let v = w.toView();
  assert.deepEqual(v.values.map((x) => x.measure), ['wavg(PV, NOTIONAL)', 'percentile(PV, 0.99)']);
  assert.deepEqual(v.values.map((x) => x.as), ['PV (weighted average by Notional)', 'PV (percentile 0.99)']);
  w.drop('PV', 'values');
  assert.equal(w.setFormula(2, 'sum(PV) / sum(NOTIONAL)', 'PV per notional').ok, true);
  w.setShowAs(0, 'pct-grand');
  v = w.toView();
  assert.deepEqual(v.values[2], { measure: 'sum(PV) / sum(NOTIONAL)', as: 'PV per notional' });
  assert.equal(v.values[0].showAs, 'pct-grand');
  w.setShowAs(0, null);
  assert.equal(w.toView().values[0].showAs, undefined);
});

test('the pickers\' groups: hierarchies first with their levels, fields by folder, measures by folder, the level columns under More fields', () => {
  const w = model().wells;
  w.setState({ rows: ['MGMT.DESK'], columns: [], values: [{ colId: 'm:var', as: 'VaR' }], filters: [] });
  const rows = fieldGroups(w, 'rows');
  assert.equal(rows[0].id, 'hierarchies');
  assert.equal(rows[0].items[0].label, 'MGMT');
  assert.equal(rows[0].items[0].checked, true);
  assert.deepEqual(rows[0].items[0].children.map((c) => [c.label, c.checked]), [['Division', false], ['Desk', true], ['Book', false]]);
  assert.ok(rows.every((g) => g.id.indexOf('m:') !== 0), 'no measure is offered for Rows');
  const more = rows.find((g) => g.id === 'more');
  assert.deepEqual(more.items.map((i) => i.id), ['DESK']);
  assert.equal(more.collapsed, true);
  const values = fieldGroups(w, 'values');
  assert.deepEqual(values.filter((g) => g.id.indexOf('m:') === 0).map((g) => g.label), ['VaR', 'Contribution']);
  assert.ok(!values.some((g) => g.id === 'hierarchies'));
});

test('one number format per column: every figure of a measure in its column\'s unit, a missing one blank', () => {
  const s = C.columnScale([74.63e6, 0.84e6, 938400, null]);
  assert.deepEqual(s, { unit: 'm', size: 1e6 });
  assert.equal(C.formatScaled(938400, s), '0.94 m');
  assert.equal(C.formatScaled(null, s), '');
  const a = { measures: [{ id: 'var()', label: 'VaR', unit: 'money' }, { id: 'trades()', label: 'Trades', unit: 'count' }],
    cells: { a: { values: { VaR: 74.63e6, Trades: 1284 } }, b: { values: { VaR: 938400, Trades: 3 } } } };
  const sc = G.answerScales(a);
  assert.deepEqual(Object.keys(sc), ['VaR']);
  assert.equal(G.measureText(938400, Object.assign({}, a.measures[0], { scale: sc.VaR })), '0.94 m');
  assert.equal(G.measureText(null, Object.assign({}, a.measures[0], { scale: sc.VaR })), '');
});
