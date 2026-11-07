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
/* drishti-grid's visual layer, the decisions without a page (drishti_grid-ui.js) under Node: heading spans and collapsible pivot column
   groups from a real pivot answer, totals and their non-additive note, conditional formats, the phone's columns (O2), the menus' items,
   the terminal's command line both ways, value filter conditions, the status bar's figures (no sum of VaR), the flash of a changed figure,
   and the bundle's new names. The drawn grid is tested in a browser against the fixture harness (test_elements_grid_browser.py). */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';

const require = createRequire(import.meta.url);

function trades() {
  const out = [];
  ['Rates', 'FX'].forEach((d) => ['EMEA', 'APAC'].forEach((r) => ['EUR', 'JPY'].forEach((c, i) => out.push({ DESK: d, REGION: r, CCY: c, MTM: 10 + i, VAR: 5 }))));
  return out;
}

test('heading spans: members over their children, the value at the foot never merged', () => {
  const sp = G.headerSpans([['EMEA', 'EUR', 'MTM'], ['EMEA', 'EUR', 'VaR'], ['EMEA', 'GBP', 'MTM'], ['APAC', 'Total', 'MTM'], ['Grand total', '', 'MTM']]);
  assert.equal(sp.length, 3);
  assert.deepEqual(sp[0].map((s) => [s.label, s.start, s.end]), [['EMEA', 0, 2], ['APAC', 3, 3], ['Grand total', 4, 4]]);
  assert.deepEqual(sp[1].map((s) => [s.label, s.start, s.end]), [['EUR', 0, 1], ['GBP', 2, 2], ['Total', 3, 3], ['', 4, 4]]);
  assert.deepEqual(sp[2].map((s) => s.label), ['MTM', 'VaR', 'MTM', 'MTM', 'MTM']);
});

test('pivot column labels and collapsible groups come from the answer: an open group with its total, a collapsed one, the grand total', () => {
  const st = new G.PivotState();
  st.toggle('columns', [['REGION', 'EMEA']], true);
  const a = G.pivotLocal(trades(), { rows: ['DESK'], columns: ['REGION', 'CCY'], values: [{ measure: 'sum(MTM)', as: 'MTM' }] }, { state: st });
  const cols = G.resultColumns(a), byKey = {};
  a.columns.forEach((e) => { byKey[e.key] = e; });
  const entries = cols.map((c) => byKey[c.colKey]);
  const labels = cols.map((c, i) => G.pivotColumnLabels(c, entries[i], 2));
  assert.deepEqual(labels.map((l) => l.join('/')), ['APAC/Total/MTM', 'EMEA/EUR/MTM', 'EMEA/JPY/MTM', 'EMEA/Total/MTM', 'Grand total//MTM']);
  const spans = G.markGroups(G.headerSpans(labels), entries);
  const emea = spans[0].find((s) => s.label === 'EMEA'), apac = spans[0].find((s) => s.label === 'APAC');
  assert.equal(emea.group.open, true);
  assert.equal(apac.group.open, false);
  assert.deepEqual(apac.group.path, [['REGION', 'APAC']]);
  assert.equal(spans[0].find((s) => s.label === 'Grand total').group, null);
});

test('totals, and the non-additive note says the figure is the server\'s for the whole group', () => {
  assert.equal(G.isTotalCell('node', 'node', false), false);
  assert.equal(G.isTotalCell('node', 'node', true), true);
  assert.equal(G.isTotalCell('grand', 'node'), true);
  assert.equal(G.isTotalCell('node', 'grand'), true);
  assert.equal(G.isTotalCell('group', null), true);
  assert.match(G.nonAdditiveNote('VaR', '1.2m'), /server's figure for this whole group, not the sum/);
});

test('conditional formats: a colour scale and data bars from the column\'s figures, in theme tokens; a rule found by column or measure', () => {
  assert.equal(G.colourScaleAlpha(0, 0, 10), 0.08);
  assert.equal(G.colourScaleAlpha(10, 0, 10), 0.58);
  assert.equal(G.colourScaleAlpha(5, 5, 5), 0.33);
  assert.equal(G.dataBarWidth(-50, 100), 46);
  assert.equal(G.dataBarWidth(500, 100), 92);
  assert.deepEqual(G.figureRange([3, 'x', -7, null, 2]), { min: -7, max: 3, maxAbs: 7 });
  assert.equal(G.figureRange(['a']), null);
  const r = { min: 0, max: 10, maxAbs: 10 };
  assert.match(G.cfBackground({ type: 'colourScale' }, 10, r), /var\(--d-accent\) 58%/);
  assert.match(G.cfBackground({ type: 'dataBar' }, -10, r), /var\(--d-neg\).* 92%/);
  assert.equal(G.cfBackground({ type: 'dataBar' }, 'x', r), null);
  const rules = [{ column: 'VAR', type: 'colourScale' }, { measure: 'MTM', type: 'dataBar' }];
  assert.equal(G.ruleFor(rules, { id: 'x::VaR', measure: 'VaR', source: 'VAR' }).type, 'colourScale');
  assert.equal(G.ruleFor(rules, { id: 'k::MTM', measure: 'MTM' }).type, 'dataBar');
  assert.equal(G.ruleFor(rules, { id: 'PNL' }), null);
});

test('a phone (O2): a grid pins its first column; a pivot keeps its row headings and one value column, the picked measure\'s grand total', () => {
  const grid = [{ col: { id: 'A' }, i: 0, pin: null }, { col: { id: 'B' }, i: 1, pin: null }];
  assert.equal(G.phoneColumns(grid, false)[0].pin, 'left');
  const piv = [{ col: { rowHeader: true }, i: 0, pin: 'left' }, { col: { measure: 'MTM', kind: 'node' }, i: 1 }, { col: { measure: 'VaR', kind: 'node' }, i: 2 },
    { col: { measure: 'MTM', kind: 'grand' }, i: 3 }, { col: { measure: 'VaR', kind: 'grand' }, i: 4 }];
  assert.deepEqual(G.phoneColumns(piv, true).map((d) => d.i), [0, 3]);
  assert.deepEqual(G.phoneColumns(piv, true, 'VaR').map((d) => d.i), [0, 4]);
  assert.deepEqual(G.pivotMeasures(piv.map((d) => d.col)), ['MTM', 'VaR']);
});

test('the column menu: sort, filters, aggregation (a non-additive one says the server computes it), hide, autosize, pin, Explain', () => {
  const ids = (items) => items.filter((x) => x.id).map((x) => x.id);
  const grid = G.columnMenuItems({ header: 'Desk', pivot: false, sort: 'asc', numeric: false, additive: true, explain: true });
  assert.deepEqual(ids(grid), ['sort-desc', 'sort-asc', 'sort-clear', 'member-filter', 'clear-filter', 'hide', 'show-all', 'autosize', 'autosize-all', 'pin-left', 'pin-right', 'unpin', 'explain-column']);
  assert.equal(grid.find((x) => x.id === 'sort-asc').checked, true);
  assert.equal(grid.find((x) => x.id === 'unpin').disabled, true);
  const aggs = G.VALUE_FUNCS.map((f) => ({ name: f, label: G.aggFunc(f).label }));
  const varItems = G.columnMenuItems({ header: 'VaR', pivot: true, sort: null, numeric: true, additive: false, aggs: aggs, aggFunc: 'var(99, 1d)', pinTotals: true, explain: false });
  const server = varItems.find((x) => x.id === 'agg:server');
  assert.equal(server.disabled, true);
  assert.match(server.why, /not additive/);
  assert.equal(varItems.some((x) => x.id === 'agg:sum'), false);
  assert.equal(varItems.find((x) => x.id === 'pin-totals').checked, true);
  assert.equal(varItems.find((x) => x.id === 'explain-column').disabled, true);
  assert.ok(ids(varItems).indexOf('top-5') >= 0 && ids(varItems).indexOf('value-filter') >= 0);
  const mtm = G.columnMenuItems({ header: 'MTM', pivot: true, numeric: true, additive: true, aggs: aggs, aggFunc: 'avg' });
  assert.equal(mtm.find((x) => x.id === 'agg:avg').checked, true);
});

test('the context menu: Explain, copy, copy with headers, CSV and Excel, chart the range, the rows behind it, group, pin', () => {
  const items = G.cellMenuItems({ explain: true, pivot: true, cells: 6, drill: true, groupBy: 'Currency', pinTotals: false });
  assert.deepEqual(items.filter((x) => x.id).map((x) => x.id), ['explain', 'copy', 'copy-headers', 'export-csv', 'export-xlsx', 'chart', 'drill', 'group', 'pin-totals']);
  assert.equal(items.find((x) => x.id === 'copy').label, 'Copy 6 cells');
  assert.equal(items.find((x) => x.id === 'group').label, 'Group rows by Currency');
  const none = G.cellMenuItems({ explain: false, pivot: false, cells: 1, drill: false, groupBy: null, pinned: null });
  assert.equal(none.find((x) => x.id === 'explain').disabled, true);
  assert.equal(none.find((x) => x.id === 'drill').disabled, true);
  assert.equal(none[none.length - 1].id, 'pin-left');
});

test('the terminal\'s command line: the wells written as a command and read back; unknown fields and no value said in words', () => {
  const fields = [{ id: 'DESK', header: 'Desk' }, { id: 'REGION', header: 'Region' }, { id: 'MTM', header: 'MTM' }, { id: 'VAR', header: 'VaR 99% 1d' }, { id: 'TRADE_DATE', header: 'Trade date' }];
  const wells = { rows: ['DESK'], columns: ['REGION'], values: [{ colId: 'MTM', aggFunc: 'avg' }, { colId: 'VAR', aggFunc: 'sum' }], filters: [{ colId: 'TRADE_DATE', filter: null }] };
  const text = G.commandText(wells);
  assert.equal(text, 'PIVOT ROWS DESK BY REGION VALUES MTM:avg VAR FILTER TRADE_DATE');
  assert.deepEqual(G.parseCommand(text, fields).wells, wells);
  assert.deepEqual(G.parseCommand('desk region by "trade date" values "VaR 99% 1d" <GO>', fields).wells,
    { rows: ['DESK', 'REGION'], columns: ['TRADE_DATE'], values: [{ colId: 'VAR', aggFunc: 'sum' }], filters: [] });
  assert.equal(G.parseCommand('ROWS BOOK VALUES MTM', fields).error, 'no field BOOK');
  assert.match(G.parseCommand('ROWS DESK', fields).error, /at least one value/);
});

test('value filter conditions as a person types them, and as words', () => {
  assert.deepEqual(G.parseCondition('> 400k'), { op: 'gt', value: 400000 });
  assert.deepEqual(G.parseCondition('>=1.5m'), { op: 'gte', value: 1500000 });
  assert.deepEqual(G.parseCondition('< -2'), { op: 'lt', value: -2 });
  assert.deepEqual(G.parseCondition('!= 1,000'), { op: 'notEquals', value: 1000 });
  assert.equal(G.parseCondition('big'), null);
  assert.equal(G.conditionText({ by: 'VaR', op: 'gt', value: 400000 }, (x) => x / 1000 + 'k'), 'VaR > 400k');
});

test('the status bar: count, sum, average, min, max of a range; a non-additive figure gives no sum and a warning instead', () => {
  const fmt = (x) => String(Math.round(x));
  const add = G.statusItems(G.rangeStats([{ r1: 0, r2: 1, c1: 0, c2: 0 }], (r) => [10, 20][r], () => ({ header: 'MTM', additive: true })), fmt);
  assert.deepEqual(add.map((x) => x.label + ' ' + x.value), ['Count 2', 'Sum 30', 'Average 15', 'Min 10', 'Max 20']);
  const nadd = G.statusItems(G.rangeStats([{ r1: 0, r2: 1, c1: 0, c2: 0 }], (r) => [10, 20][r], () => ({ header: 'VaR', additive: false })), fmt);
  assert.equal(nadd.find((x) => x.label === 'Sum').value, '—');
  const warn = nadd.find((x) => x.warn);
  assert.match(warn.value, /a sum of VaR is not a VaR/);
  assert.deepEqual(G.statusItems({ cells: 1, count: 1, min: 7, max: 7 }, fmt), [{ label: '1 cell', value: '7', warn: false }]);
  assert.deepEqual(G.statusItems(null, fmt), []);
});

test('the flash: a figure that changed after live data flashes up or down until flashMs, and only then', () => {
  let t = 0;
  const f = new G.FlashTracker({ flashMs: 1000, now: () => t });
  assert.equal(f.seen('a|MTM', 10), null);
  assert.equal(f.seen('a|MTM', 12), null);                  // not armed: a re-sort or a new pivot does not flash
  f.arm();
  t = 100;
  assert.deepEqual(f.seen('a|MTM', 15), { dir: 'up', age: 0 });
  assert.equal(f.seen('b|MTM', 5), null);                   // first seen: nothing to compare
  f.settle();
  t = 600;
  assert.deepEqual(f.seen('a|MTM', 15), { dir: 'up', age: 500 });
  assert.equal(f.active(), true);
  f.arm();
  t = 700;
  assert.equal(f.seen('a|MTM', 9).dir, 'down');
  t = 1800;
  assert.equal(f.seen('a|MTM', 9), null);
  assert.equal(f.active(), false);
});

test('the view\'s events are their own names; the bundle carries the visual layer, and install does nothing without a document', () => {
  assert.equal(G.VIEW_EVENTS.filter((x) => G.GRID_EVENTS.indexOf(x) >= 0).length, 0);
  assert.deepEqual(G.FUNCTION_KEYS.map((k) => k.key), ['F2', 'F3', 'F4', 'F5', 'F7', 'F8', 'F9', 'F10']);
  assert.equal(G.ELEMENT_NAME, 'drishti-grid');
  assert.equal(G.defineGridElement(null, null), false);
  G.install({});
  const B = require('../../web/static/elements/drishti-grid/drishti_grid.js');
  assert.equal(B.version, '0.3.0');
  ['Grid', 'GridBase', 'defineGridElement', 'headerSpans', 'parseCommand', 'FlashTracker', 'renderExplain', 'Menu'].forEach((n) => assert.equal(typeof B[n], 'function', n));
  assert.throws(() => new B.Grid(null, {}), /needs an element/);
});
