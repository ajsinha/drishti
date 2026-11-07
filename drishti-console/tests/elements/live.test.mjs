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
/* drishti-grid's events and live data under Node: the typed events on the bus and as DOM events, transactions by row id (order stable
   under sort and filter, the changed cells and their flash), batching, the live feeds, the server-side model's refresh on a notice,
   group and pivot totals by difference, and the non-additive totals asked of the server again, debounced. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';
import * as D from '../../web/static/elements/drishti-data/src/drishti_index.js';

const tick = (ms) => new Promise((r) => setTimeout(r, ms));
function trades(n) {
  const out = [];
  for (let i = 0; i < n; i++) { out.push({ id: 'T' + i, DESK: ['RATES', 'FX', 'EQUITY'][i % 3], CCY: ['USD', 'EUR'][i % 2], PX: 100 + i, QTY: i % 7, VAR: 5 + i }); }
  return out;
}
const COLS = [{ field: 'id' }, { field: 'DESK' }, { field: 'CCY' }, { field: 'PX', type: 'number', editable: true }, { field: 'QTY', type: 'number', aggFunc: 'max' },
  { field: 'VAR', type: 'number', additive: false }];

test('typed events: on the bus and as DOM events on the grid element, for sort, filter, groups, columns, selection, ranges, focus, cells, Explain', async () => {
  const g = G.createGridModel({ columns: COLS, rows: trades(9), explain: () => 'x' });
  const seen = [], dom = [];
  G.GRID_EVENTS.forEach((n) => g.on(n, () => seen.push(n)));
  const el = new EventTarget();
  G.GRID_EVENTS.forEach((n) => el.addEventListener(G.domEventName(n), (e) => dom.push([e.type, e.detail])));
  g.setEventTarget(el);
  g.setSortModel([{ colId: 'PX', sort: 'desc' }]);
  g.setFilterModel({ DESK: { type: 'set', values: ['FX', 'RATES'] } });
  g.columns.setWidth('PX', 200);
  g.columns.move('PX', 0);
  g.columns.pin('DESK', 'left');
  g.columns.setVisible('CCY', false);
  g.cellClicked(1, 1, { ctrl: false });
  g.key({ key: 'ArrowDown' });
  g.cellDoubleClicked(0, 0);
  await g.explain(0, 0);
  g.setRowGroups(['DESK']);
  await g.toggleRow(0);
  assert.deepEqual(['sortChanged', 'filterChanged', 'columnResized', 'columnMoved', 'columnPinned', 'columnVisible', 'cellClicked', 'focusChanged', 'cellDoubleClicked',
    'explainOpened', 'groupChanged', 'rowExpanded'].filter((n) => !seen.includes(n)), []);
  assert.ok(seen.includes('selectionChanged') && seen.includes('rangeChanged'));
  assert.equal(G.domEventName('cellClicked'), 'drishti:cell-clicked');
  assert.ok(dom.some(([t, d]) => t === 'drishti:sort-changed' && d.sortModel[0].colId === 'PX'));
  assert.throws(() => g.live.emit('nothing'), /not a grid event/);
});

test('transactions by id: a sorted, filtered view keeps its order (ties stable), a row moves where its new value puts it, a filtered one leaves', () => {
  let now = 1000;
  const g = G.createGridModel({ columns: COLS, rows: trades(10), live: { now: () => now, flashMs: 500 } });
  g.setSortModel([{ colId: 'PX', sort: 'asc' }]);
  g.setFilterModel({ DESK: { type: 'set', excluded: ['EQUITY'] } });
  const ids = () => { const out = []; for (let i = 0; i < g.rowCount(); i++) { out.push(g.rowAt(i).id); } return out; };
  const before = ids();
  const res = g.applyTransaction({ update: [Object.assign({}, trades(10)[0], { PX: 999 }), Object.assign({}, trades(10)[1], { DESK: 'EQUITY' })] });
  const after = ids();
  assert.equal(after[after.length - 1], 'T0');
  assert.ok(!after.includes('T1'));
  assert.deepEqual(after.slice(0, -1), before.filter((x) => x !== 'T0' && x !== 'T1'));
  assert.deepEqual(res.changes[0], { id: 'T0', cells: { PX: { old: 100, value: 999, dir: 'up' } } });
  assert.equal(g.flashOf('T0', 'PX'), 'up');
  now += 600;
  assert.equal(g.flashOf('T0', 'PX'), null);
  const r = g.applyTransaction({ add: [{ id: 'N', DESK: 'FX', PX: 1 }], remove: ['T2'] });
  assert.deepEqual([r.added, r.removed], [1, 1]);
  assert.equal(ids()[0], 'N');
  g.setSortModel([]);
  const plain = ids();
  g.applyTransaction({ update: [Object.assign({}, g.rowModel.getRowById('T3'), { PX: -5 })] });
  assert.deepEqual(ids(), plain);                                         // no sort on PX: nothing moves
});

test('editing a cell is a transaction by id with cellValueChanged; a read-only column refuses', () => {
  const g = G.createGridModel({ columns: COLS, rows: trades(3) });
  const got = [];
  g.on('cellValueChanged', (e) => got.push(e));
  assert.equal(g.setCellValue(0, 3, 42), true);
  assert.equal(g.rowModel.getRowById('T0').PX, 42);
  assert.deepEqual(got[0], { rowId: 'T0', colId: 'PX', old: 100, value: 42, source: 'api', transaction: 1 });
  assert.equal(g.setCellValue(0, 1, 'X'), false);
});

test('batching: many transactions within the wait are one refresh, a row\'s last update wins, the waiters get the result; thousands a second', async () => {
  const rows = trades(20000);
  const g = G.createGridModel({ columns: COLS, rows: rows, live: { asyncWaitMs: 5 } });
  g.setSortModel([{ colId: 'PX', sort: 'desc' }]);
  let refreshes = 0;
  g.on('rowDataUpdated', () => refreshes++);
  const ps = [];
  for (let i = 0; i < 2000; i++) { ps.push(g.applyTransactionAsync({ update: [Object.assign({}, rows[i % 500], { PX: 1e6 + i })] })); }
  const res = await Promise.all(ps);
  assert.equal(refreshes, 1);
  assert.equal(res[0].updated, 500);
  assert.equal(g.rowModel.getRowById('T0').PX, 1e6 + 1500);
  assert.equal(g.rowAt(0).id, 'T499');
  const t0 = Date.now();
  let n = 0;
  for (let b = 0; b < 20; b++) {
    const upd = [];
    for (let i = 0; i < 500; i++) { upd.push(Object.assign({}, rows[(b * 500 + i * 37) % 20000], { PX: Math.random() * 1e6 })); }
    g.applyTransactionAsync({ update: upd });
    g.flushTransactions();
    n += upd.length;
  }
  const perSecond = n / Math.max(1, Date.now() - t0) * 1000;
  assert.ok(perSecond > 2000, 'updates a second: ' + Math.round(perSecond));
  for (let i = 1; i < g.rowCount(); i++) { assert.ok(g.rowAt(i - 1).row.PX >= g.rowAt(i).row.PX); }
});

test('group figures follow updates: sums by difference, max from the members, non-additive asked of the server once, debounced', async () => {
  const asks = [];
  const g = G.createGridModel({ columns: COLS, rows: trades(12), live: { serverDebounceMs: 10 },
    serverGroupTotals: (req) => { asks.push(req); return Promise.resolve(req.groups.map((x) => ({ path: x.path, values: { VAR: 7 } }))); } });
  g.setRowGroups(['DESK']);
  await tick(5);
  assert.equal(asks.length, 1);
  const fx = () => { for (let i = 0; i < g.rowCount(); i++) { const r = g.rowAt(i); if (r.kind === 'group' && r.value === 'FX') { return r; } } return null; };
  assert.equal(fx().aggregates.VAR, 7);
  const sum0 = fx().aggregates.PX;
  g.applyTransaction({ update: [Object.assign({}, g.rowModel.getRowById('T1'), { PX: 1101, QTY: 99, VAR: 1 })] });
  g.applyTransaction({ update: [Object.assign({}, g.rowModel.getRowById('T4'), { VAR: 2 })] });
  assert.equal(fx().aggregates.PX, sum0 + 1000);
  assert.equal(fx().aggregates.QTY, 99);
  await tick(30);
  assert.equal(asks.length, 2);
  assert.deepEqual(asks[1].groups.map((x) => x.path), [['FX']]);
});

test('a local pivot follows updates by difference (the same figures as computing again), a moved member computes again, VaR asked again', async () => {
  const asked = [];
  const data = trades(30);
  const g = G.createGridModel({ columns: COLS, rows: data, live: { serverDebounceMs: 10 },
    serverPivot: (b, s, rows) => { asked.push(b.view.values.map((v) => v.as)); return Promise.resolve(G.pivotLocal(rows, Object.assign({}, b.view, { values: [{ measure: 'sum(VAR)', as: b.view.values[0].as }] }))); } });
  g.wells.drop('DESK', 'rows');
  g.wells.drop('CCY', 'columns');
  g.wells.drop('PX', 'values');
  g.wells.drop('VAR', 'values');
  await g.setPivotMode(true);
  asked.length = 0;
  const a = g.answer;
  g.applyTransaction({ update: [Object.assign({}, data[0], { PX: 5000, VAR: 50 }), Object.assign({}, data[1], { PX: 1 })] });
  assert.equal(g.answer, a);                                              // by difference: the same answer, changed in place
  const fresh = G.pivotLocal(g.rowModel.filteredRows(), { rows: ['DESK'], columns: ['CCY'], values: [{ measure: 'sum(PX)', as: 'PX' }] });
  Object.keys(fresh.cells).forEach((k) => assert.equal(a.cells[k].values.PX, fresh.cells[k].values.PX, k));
  await tick(30);
  assert.deepEqual(asked, [['VAR']]);
  assert.equal(a.cells['|'].values.VAR, g.rowModel.filteredRows().reduce((s, r) => s + r.VAR, 0));
  g.applyTransaction({ update: [Object.assign({}, g.rowModel.getRowById('T2'), { DESK: 'FX' })] });
  assert.notEqual(g.answer, a);                                           // a row changed member: computed again
});

test('the live feeds: a manual feed, and the live client of drishti-data over Server-Sent Events and a WebSocket, each message applied (batched), rows replaced', async () => {
  const g = G.createGridModel({ columns: COLS, rows: trades(4), live: { asyncWaitMs: 1 } });
  const feed = G.manualFeed();
  const off = g.connectLive(feed);
  feed.push({ type: 'transaction', update: [{ id: 'T0', DESK: 'FX', PX: 7 }] });
  await tick(10);
  assert.equal(g.rowModel.getRowById('T0').PX, 7);
  feed.push({ type: 'rows', rows: [{ id: 'Z', PX: 1 }] });
  assert.equal(g.rowCount(), 1);
  off();
  class FakeES extends EventTarget { constructor(url) { super(); FakeES.last = this; this.url = url; this.readyState = 1; } close() { this.closed = true; } }
  const es = new D.LiveClient({ url: '/api/live/trades', EventSource: FakeES, heartbeatMs: 0, window: {} });
  const off2 = g.connectLive(es);
  FakeES.last.dispatchEvent(new Event('open'));
  FakeES.last.dispatchEvent(Object.assign(new Event('message'), { data: JSON.stringify({ type: 'transaction', add: [{ id: 'Y', PX: 2 }] }) }));
  await tick(10);
  assert.equal(g.rowCount(), 2);
  off2();
  assert.equal(FakeES.last.closed, true);
  class FakeWS extends EventTarget { constructor(url) { super(); FakeWS.last = this; } send() {} close() { this.closed = true; } }
  const errors = [];
  const ws = new D.LiveClient({ url: 'ws://x/live', WebSocket: FakeWS, heartbeatMs: 0, window: {} });
  ws.on('error', (e) => errors.push(e.error));
  g.connectLive(ws);
  FakeWS.last.dispatchEvent(new Event('open'));
  FakeWS.last.dispatchEvent(Object.assign(new Event('message'), { data: '{"type": "transaction", "remove": ["Y"]}' }));
  FakeWS.last.dispatchEvent(Object.assign(new Event('message'), { data: 'not json' }));
  await tick(10);
  assert.equal(g.rowCount(), 1);
  assert.match(errors[0].message, /not JSON/);
  ws.destroy();
});

test('server-side rows: a block invalidated, a level, the blocks of some rows, a purge, by a data-changed notice from a feed', async () => {
  const data = trades(300);
  const ds = G.memoryDatasource(data);
  const g = G.createGridModel({ columns: COLS, datasource: ds, blockSize: 100 });
  const loaded = [];
  g.on('blockLoaded', (e) => loaded.push(e.block));
  await g.rowModel.ensureRange(0, 50);
  await g.rowModel.ensureRange(0, 250);
  assert.deepEqual(loaded.sort(), [0, 1, 2]);
  const m = g.rowModel;
  assert.equal(m.invalidateBlock([], 1), true);
  assert.equal(m.rowAt(150).kind, 'loading');
  await m.ensureRange(100, 199);
  data[5].PX = -1;
  assert.equal(g.dataChanged({ rows: ['T5'] }), 'rows');
  assert.equal(m.rowAt(5).kind, 'loading');
  await m.ensureRange(0, 99);
  assert.equal(m.rowAt(5).row.PX, -1);
  const feed = G.manualFeed();
  g.connectLive(feed);
  feed.push({ type: 'dataChanged', purge: true });
  assert.equal(m.cacheStats().blocks, 0);
  assert.equal(g.dataChanged({ keys: [] }), 'level');
  const failing = G.createGridModel({ columns: COLS, datasource: { getRows: () => Promise.reject(new Error('down')) } });
  const failed = [];
  failing.on('blockFailed', (e) => failed.push(e.error.message));
  await failing.rowModel.ensureRange(0, 10);
  assert.deepEqual(failed, ['down']);
});
