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
/* drishti-grid's tree data without a page: the tree made from each row's path (intermediate nodes made, a row that is itself a node),
   opening by path and by level, sorting within siblings, a filter keeping the nodes above a match, a node's figures from its rows or
   left for the server (clientAggregation off), the server's figures put in by path, and transactions. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';

const COLS = [{ field: 'name', header: 'Name' }, { field: 'mtm', header: 'MTM', type: 'number', aggFunc: 'sum' }, { field: 'var', header: 'VaR', type: 'number', aggFunc: 'sum', additive: false }];
const ROWS = () => [
  { id: 'r', path: ['BANK', 'RATES'], name: 'Rates desk', mtm: 1, var: 9 },
  { id: 't1', path: ['BANK', 'RATES', 'IRS-1'], name: 'IRS-1', mtm: 10, var: 5 },
  { id: 't2', path: ['BANK', 'RATES', 'IRS-2'], name: 'IRS-2', mtm: 30, var: 6 },
  { id: 'f1', path: ['BANK', 'FX', 'FXF-1'], name: 'FXF-1', mtm: 20, var: 4 }
];
const model = (o) => G.createGridModel(Object.assign({ columns: COLS, rows: ROWS(), treeData: true, getDataPath: (r) => r.path }, o));
const shown = (m) => { const out = []; for (let i = 0; i < m.rowCount(); i++) { const x = m.rowAt(i); out.push('  '.repeat(x.depth) + x.treeKey + (x.kind === 'group' ? (x.expanded ? ' -' : ' +') : '')); } return out; };

test('the tree is made from the paths: intermediate nodes, a row that is a node, closed by default', () => {
  const m = model();
  assert.deepEqual(shown(m), ['BANK +']);
  m.toggleRow(0, true);
  assert.deepEqual(shown(m), ['BANK -', '  RATES +', '  FX +']);
  const rates = m.rowAt(1);
  assert.equal(rates.row.name, 'Rates desk');
  assert.equal(rates.id, 'r');
  assert.equal(m.cellText(1, 0), 'RATES');
  assert.equal(m.cellValue(1, 1), 1);                    // a node a row names shows its row
  assert.equal(m.cellValue(0, 1), 61);                   // a node no row names: the sum of its rows
  assert.equal(m.cellText(0, 2), '…');                   // a non-additive figure waits for the server
});

test('nodes open by level, and siblings keep the sort order', () => {
  const m = model({ groupDefaultExpanded: -1 });
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-1', '    IRS-2', '  FX -', '    FXF-1']);
  m.setSortModel([{ colId: 'mtm', sort: 'desc' }]);
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-2', '    IRS-1', '  FX -', '    FXF-1']);
  const two = model({ groupDefaultExpanded: 1 });
  assert.deepEqual(shown(two), ['BANK -', '  RATES +', '  FX +']);
  assert.equal(two.rowModel.indexOfId('t1'), -1);
  two.toggleRow(1, true);
  assert.equal(two.rowModel.indexOfId('t1'), 2);
});

test('a filter keeps a match and every node above it', () => {
  const m = model({ groupDefaultExpanded: -1 });
  m.rowModel.setQuickFilter('FXF');
  assert.deepEqual(shown(m), ['BANK -', '  FX -', '    FXF-1']);
  assert.equal(m.cellValue(0, 1), 20);                   // the figures follow the rows kept
  assert.deepEqual(m.rowModel.filteredRows().map((r) => r.id), ['f1']);
});

test('with clientAggregation off every node figure is the server\'s, put in by path', () => {
  const m = model({ clientAggregation: false, groupDefaultExpanded: -1 });
  assert.equal(m.cellText(0, 1), '…');
  assert.deepEqual(m.rowModel.serverGroupColumns(), [{ path: ['BANK'], columns: ['mtm', 'var'] }, { path: ['BANK', 'FX'], columns: ['mtm', 'var'] }]);
  assert.equal(m.rowModel.setGroupServerValues(['BANK', 'FX'], { mtm: 20, var: 4 }), true);
  assert.equal(m.cellValue(4, 1), 20);
  assert.equal(m.cellValue(4, 2), 4);
});

test('transactions rebuild the tree; a path twice or none is refused in words', () => {
  const m = model({ groupDefaultExpanded: -1 });
  m.rowModel.applyTransaction({ add: [{ id: 'c1', path: ['BANK', 'CREDIT', 'CDS-1'], name: 'CDS-1', mtm: 5 }], update: [{ id: 't1', path: ['BANK', 'RATES', 'IRS-1'], name: 'IRS-1', mtm: 11, var: 5 }] });
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-1', '    IRS-2', '  FX -', '    FXF-1', '  CREDIT -', '    CDS-1']);
  assert.equal(m.cellValue(0, 1), 67);
  m.rowModel.applyTransaction({ update: [{ id: 't2', path: ['BANK', 'RATES', 'IRS-2'], name: 'IRS-2', mtm: 31, var: 6 }] });
  assert.equal(m.cellValue(0, 1), 68);
  assert.throws(() => model({ rows: ROWS().concat([{ id: 'x', path: ['BANK', 'FX', 'FXF-1'] }]) }), /two rows have the path BANK \/ FX \/ FXF-1/);
  assert.throws(() => G.createGridModel({ columns: COLS, rows: [], treeData: true }), /getDataPath/);
});

// ---- tree data on server-side rows ---------------------------------------------------------------------------------------------------
const SROWS = () => [
  { id: 'bank', path: ['BANK'], name: 'Bank', mtm: 61 },
  { id: 'r', path: ['BANK', 'RATES'], name: 'Rates desk', mtm: 40 },
  { id: 't1', path: ['BANK', 'RATES', 'IRS-1'], name: 'IRS-1', mtm: 10 },
  { id: 't2', path: ['BANK', 'RATES', 'IRS-2'], name: 'IRS-2', mtm: 30 },
  { id: 'fx', path: ['BANK', 'FX'], name: 'FX desk', mtm: 20 },
  { id: 'f1', path: ['BANK', 'FX', 'FXF-1'], name: 'FXF-1', mtm: 20 }
];
const serverTree = (o) => {
  const ds = G.memoryDatasource(SROWS(), { getDataPath: (r) => r.path });
  const m = G.createGridModel(Object.assign({ columns: COLS, datasource: ds, treeData: true, isServerSideGroup: (r) => r.treeGroup, getServerSideGroupKey: (r) => r.treeKey }, o));
  return { m: m, ds: ds };
};
const settle = async (m) => { for (let i = 0; i < 6; i++) { await m.rowModel.ensureRange(0, m.rowCount() + 5); } };

test('server-side tree data: the top level first, each node\'s level asked when it opens with groupKeys = its path', async () => {
  const { m, ds } = serverTree();
  await settle(m);
  assert.deepEqual(shown(m), ['BANK +']);
  assert.deepEqual(ds.calls.map((q) => [q.treeData, q.groupKeys]), [[true, []]]);
  assert.equal(m.cellText(0, 0), 'BANK');
  assert.equal(m.cellValue(0, 1), 61);                                  // a node is a row: its figures are the server's
  assert.equal(m.rowAt(0).id, 'bank');
  m.toggleRow(0, true);
  await settle(m);
  assert.deepEqual(shown(m), ['BANK -', '  RATES +', '  FX +']);
  assert.deepEqual(ds.calls.map((q) => q.groupKeys), [[], ['BANK']]);
  m.toggleRow(1, true);
  await settle(m);
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-1', '    IRS-2', '  FX +']);
  assert.deepEqual(ds.calls[2].groupKeys, ['BANK', 'RATES']);
  assert.equal(m.rowAt(2).kind, 'leaf');
  assert.equal(m.rowAt(2).tree, true);
  assert.equal(m.cellText(2, 0), 'IRS-1');
  assert.equal(m.idAt(2), 't1');
  assert.equal(m.rowModel.treeData, true);
});

test('server-side tree data: a closed node reopens from the cache; refresh asks a level again; a sort asks every open level again', async () => {
  const { m, ds } = serverTree();
  await settle(m);
  m.toggleRow(0, true);
  await settle(m);
  m.toggleRow(1, true);
  await settle(m);
  const n = ds.calls.length;
  m.toggleRow(1, false);
  await settle(m);
  assert.deepEqual(shown(m), ['BANK -', '  RATES +', '  FX +']);
  m.toggleRow(1, true);
  await settle(m);
  assert.equal(ds.calls.length, n);                                     // reopened from the blocks held
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-1', '    IRS-2', '  FX +']);
  m.rowModel.refresh({ keys: ['BANK', 'RATES'] });
  await settle(m);
  assert.deepEqual(ds.calls.slice(n).map((q) => q.groupKeys), [['BANK', 'RATES']]);
  const before = ds.calls.length;
  m.setSortModel([{ colId: 'mtm', sort: 'desc' }]);
  await settle(m);
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-2', '    IRS-1', '  FX +']);
  assert.deepEqual(ds.calls.slice(before).map((q) => q.groupKeys).sort(), [[], ['BANK'], ['BANK', 'RATES']]);
  assert.ok(ds.calls.slice(before).every((q) => q.sortModel[0].colId === 'mtm'));
});

test('server-side tree data: nodes open by level as they load; expand all and collapse all; the callbacks are required', async () => {
  const { m } = serverTree({ groupDefaultExpanded: -1 });
  await settle(m);
  assert.deepEqual(shown(m), ['BANK -', '  RATES -', '    IRS-1', '    IRS-2', '  FX -', '    FXF-1']);
  m.toggleRow(1, false);
  await settle(m);
  assert.deepEqual(shown(m), ['BANK -', '  RATES +', '  FX -', '    FXF-1']);
  m.rowModel.expandToLevel(0);
  assert.deepEqual(shown(m), ['BANK +']);
  m.rowModel.expandToLevel(99);
  await settle(m);
  assert.equal(m.rowCount(), 6);
  const one = serverTree({ groupDefaultExpanded: 1 }).m;
  await settle(one);
  assert.deepEqual(shown(one), ['BANK -', '  RATES +', '  FX +']);
  assert.throws(() => G.createGridModel({ columns: COLS, datasource: G.memoryDatasource([]), treeData: true }), /isServerSideGroup/);
});
