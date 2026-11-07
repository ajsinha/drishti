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
/* drishti-grid's engine under Node (node --test): columns and the field list, sort and filter, the client-side and server-side row
   models, the viewport, the pivot (the server's shape, totals from members, non-additive left for the server and merged back, top N
   with Others, value filters, open state), the wells, selection and range figures, copy, export (CSV, a real xlsx), the keys, the
   Explain hook, state, and the bundle. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';

const require = createRequire(import.meta.url);
const M = '−';

function trades(n) {
  const desks = ['RATES', 'FX', 'EQUITY', 'CREDIT'], ccys = ['USD', 'EUR', 'GBP'], out = [];
  for (let i = 0; i < n; i++) {
    out.push({ id: 'T' + i, DESK: desks[i % 4], CCY: ccys[i % 3], BOOK: 'B' + (i % 7), NOTIONAL: (i % 10) * 1000 + 1, PNL: (i % 5) - 2, VAR: 10 + (i % 4), TRADE_DATE: '2026-11-0' + (1 + (i % 6)) });
  }
  return out;
}
const COLS = [{ field: 'DESK', header: 'Desk' }, { field: 'CCY', header: 'Currency' }, { field: 'BOOK' }, { field: 'NOTIONAL', type: 'number', format: 'amount0', header: 'Notional' },
  { field: 'PNL', type: 'number', header: 'P&L' }, { field: 'VAR', type: 'number', header: 'VaR', additive: false, measure: 'var(99, 1d)' }, { field: 'TRADE_DATE', type: 'date' }];

test('columns: defaults, pinned order, layout edges, the field list searched, state kept and given back', () => {
  const c = new G.ColumnModel(COLS);
  assert.equal(c.get('NOTIONAL').aggFunc, 'sum');
  assert.equal(c.get('VAR').additive, false);
  c.pin('PNL', 'left');
  assert.equal(c.visible()[0].id, 'PNL');
  const l = c.layout();
  assert.equal(l.left.length, 1);
  assert.equal(l.centerOffsets[1], 120);
  assert.deepEqual(c.fields('notio').map((f) => f.id), ['NOTIONAL']);
  assert.deepEqual(c.fields('cur').map((f) => f.id), ['CCY']);
  assert.equal(c.fields('var')[0].additive, false);
  c.setWidth('DESK', 10);
  assert.equal(c.get('DESK').width, 40);                                // never below its least
  const s = c.getState();
  c.setVisible('BOOK', false);
  c.setState(s);
  assert.equal(c.get('BOOK').hidden, false);
  assert.throws(() => new G.ColumnModel([{ field: 'A' }, { field: 'A' }]), /two columns/);
});

test('sort: blanks last both ways, numbers inside text in order, multi-column, the header cycle', () => {
  const c = new G.ColumnModel([{ field: 'K' }, { field: 'N', type: 'number' }]);
  const rows = [{ K: 'T10', N: 1 }, { K: 'T2', N: null }, { K: null, N: 3 }, { K: 'T1', N: 2 }];
  const m = new G.ClientRowModel({ columns: c, rows: rows });
  m.setSortModel([{ colId: 'K', sort: 'asc' }]);
  assert.deepEqual(m.filteredRows().map((r) => r.K), ['T1', 'T2', 'T10', null]);
  m.setSortModel([{ colId: 'N', sort: 'desc' }]);
  assert.deepEqual(m.filteredRows().map((r) => r.N), [3, 2, 1, null]);
  assert.deepEqual(C.cycleSort([], 'K'), [{ colId: 'K', sort: 'asc' }]);
  assert.deepEqual(C.cycleSort([{ colId: 'K', sort: 'asc' }], 'K'), [{ colId: 'K', sort: 'desc' }]);
  assert.deepEqual(C.cycleSort([{ colId: 'K', sort: 'desc' }], 'K'), []);
  assert.deepEqual(C.cycleSort([{ colId: 'K', sort: 'asc' }], 'N', true), [{ colId: 'K', sort: 'asc' }, { colId: 'N', sort: 'asc' }]);
});

test('filters: text, number, date, set (members kept or left out), blank, quick; in words', () => {
  const m = new G.ClientRowModel({ columns: new G.ColumnModel(COLS), rows: trades(40) });
  m.setFilterModel({ DESK: { type: 'set', values: ['RATES', 'FX'] } });
  assert.equal(m.filteredCount(), 20);
  m.setFilterModel({ DESK: { type: 'set', excluded: ['RATES'] }, NOTIONAL: { type: 'number', op: 'gt', value: 5000 } });
  assert.ok(m.filteredRows().every((r) => r.DESK !== 'RATES' && r.NOTIONAL > 5000));
  m.setFilterModel({ TRADE_DATE: { type: 'date', op: 'between', value: '2026-11-02', valueTo: '2026-11-03' } });
  assert.ok(m.filteredRows().every((r) => r.TRADE_DATE >= '2026-11-02' && r.TRADE_DATE <= '2026-11-03'));
  m.setFilterModel({ BOOK: { type: 'text', op: 'startsWith', value: 'b1' } });
  assert.ok(m.filteredRows().every((r) => r.BOOK === 'B1'));
  m.setFilterModel({});
  m.setQuickFilter('equity gbp');
  assert.ok(m.filteredCount() > 0 && m.filteredRows().every((r) => r.DESK === 'EQUITY' && r.CCY === 'GBP'));
  assert.equal(C.passes(null, { type: 'blank', op: 'blank' }), true);
  assert.equal(C.filterWords({ DESK: { type: 'set', values: ['RATES', 'FX'] }, NOTIONAL: { type: 'number', op: 'gt', value: 5 } }), 'DESK in RATES, FX; NOTIONAL > 5');
});

test('row groups: figures from members, a non-additive column left for the server, open and close, ids follow rows', () => {
  const m = new G.ClientRowModel({ columns: new G.ColumnModel(COLS), rows: trades(12) });
  m.setGroupBy(['DESK']);
  assert.equal(m.rowCount(), 4);
  const g = m.rowAt(0);
  assert.equal(g.kind, 'group');
  assert.equal(g.count, 3);
  assert.equal(g.aggregates.NOTIONAL, trades(12).filter((r) => r.DESK === g.value).reduce((a, r) => a + r.NOTIONAL, 0));
  assert.equal(g.aggregates.VAR, null);
  assert.deepEqual(g.pendingServer, ['VAR']);
  m.setExpanded(g.path, true);
  assert.equal(m.rowCount(), 7);
  assert.equal(m.rowAt(1).kind, 'leaf');
  m.expandToLevel(Infinity);
  assert.equal(m.rowCount(), 16);
  m.setExpanded(g.path, false);
  assert.equal(m.rowCount(), 13);
  m.setGroupBy([]);
  m.setSortModel([{ colId: 'NOTIONAL', sort: 'desc' }]);
  const id = m.rowAt(0).id;
  assert.equal(m.getRowById(id).NOTIONAL, 9001);
  m.applyTransaction({ update: [Object.assign({}, m.getRowById(id), { NOTIONAL: 1 })], add: [{ id: 'NEW', DESK: 'FX', NOTIONAL: 99999 }], remove: ['T0'] });
  assert.equal(m.rowAt(0).id, 'NEW');
  assert.equal(m.getRowById('T0'), null);
  assert.ok(m.indexOfId(id) > 0);
});

test('the viewport: a million rows, the window drawn, the scroll height capped and the last row reachable, columns by binary search', () => {
  const v = new G.Viewport({ rowHeight: 28, height: 560, overscanRows: 4 });
  v.setRowCount(1000000);
  assert.ok(v.scrollHeight() <= G.MAX_SCROLL_HEIGHT);
  let w = v.rows();
  assert.deepEqual([w.first, w.firstVisible, w.lastVisible], [0, 0, 19]);
  v.scrollTo(v.maxScrollTop());
  w = v.rows();
  assert.equal(w.lastVisible, 999999);
  v.scrollTo(0);
  v.scrollToRow(500000);
  w = v.rows();
  assert.ok(w.firstVisible <= 500000 && w.lastVisible >= 500000, JSON.stringify(w));
  const offs = [0, 100, 250, 300, 500, 900];
  assert.equal(G.columnAt(offs, 260), 2);
  v.resize(220, 560);
  v.scrollTo(0, 120);
  assert.deepEqual(v.columns(offs), { first: 0, last: 4 });
});

test('the server-side row model: blocks on demand, the request shape, a cache, groups open their own level, stale answers dropped', async () => {
  const data = trades(250);
  const ds = G.memoryDatasource(data);
  const m = new G.ServerRowModel({ datasource: ds, blockSize: 50, maxBlocks: 3 });
  assert.equal(m.rowAt(0).kind, 'loading');
  await m.ensureRange(0, 120);                                          // the first block tells the count; the renderer asks again
  assert.equal(m.rowCount(), 250);
  await m.ensureRange(0, 120);
  assert.equal(m.rowAt(120).row.id, 'T120');
  const req = ds.calls[0];
  assert.deepEqual(Object.keys(req).sort(), ['endRow', 'filterModel', 'groupKeys', 'pivotCols', 'pivotMode', 'quickFilter', 'rowGroupCols', 'signal', 'sortModel', 'startRow', 'valueCols']);
  await m.ensureRange(200, 249);
  assert.ok(m.cacheStats().blocks <= 3);
  m.setGrouping({ rowGroupCols: ['DESK'] });
  await m.ensureRange(0, 10);
  assert.equal(m.rowCount(), 4);
  const g = m.rowAt(0);
  assert.equal(g.kind, 'group');
  m.setExpanded(g.keys, true);
  await m.ensureRange(0, 70);
  assert.equal(m.rowAt(1).kind, 'leaf');
  assert.equal(m.rowAt(1).row.DESK, g.value);
  assert.equal(ds.calls[ds.calls.length - 1].groupKeys[0], g.value);
  const slow = G.memoryDatasource(data, { delay: 20 });
  const s = new G.ServerRowModel({ datasource: slow, blockSize: 50 });
  const p = s.ensureRange(0, 10);
  s.setSortModel([{ colId: 'NOTIONAL', sort: 'desc' }]);
  await p;
  await s.ensureRange(0, 10);
  assert.equal(s.rowAt(0).row.NOTIONAL, 9001);
  assert.ok(slow.calls[0].signal.aborted);
});

const VIEW = { rows: ['DESK'], columns: ['CCY'], values: [{ measure: 'sum(NOTIONAL)', as: 'Notional' }, { measure: 'count()', as: 'Trades' }] };

test('the local pivot answers in the server shape; every total from its members; subtotals below or above', () => {
  const data = trades(24);
  const a = G.pivotLocal(data, VIEW);
  assert.deepEqual(a.rows.map((r) => r.kind), ['node', 'node', 'node', 'node', 'grand']);
  assert.deepEqual(a.columns.map((c) => c.label), ['EUR', 'GBP', 'USD', 'Grand total']);
  const grand = a.cells['|'];
  assert.equal(grand.values.Notional, data.reduce((s, r) => s + r.NOTIONAL, 0));
  assert.equal(grand.values.Trades, 24);
  assert.equal(grand.members, 24);
  const fx = a.rows.find((r) => r.value === 'FX');
  assert.deepEqual(fx.path, [['DESK', 'FX']]);
  assert.equal(a.cells[fx.key + '|USD'].values.Notional, data.filter((r) => r.DESK === 'FX' && r.CCY === 'USD').reduce((s, r) => s + r.NOTIONAL, 0));
  const two = G.pivotLocal(data, Object.assign({}, VIEW, { rows: ['DESK', 'BOOK'], expandTo: { rows: 2 } }));
  assert.deepEqual(two.rows.slice(0, 2).map((r) => r.kind), ['header', 'node']);
  assert.ok(two.rows.some((r) => r.kind === 'total'));
  const above = G.pivotLocal(data, Object.assign({}, VIEW, { rows: ['DESK', 'BOOK'], expandTo: { rows: 2 }, subtotals: 'above' }));
  assert.equal(above.rows[0].kind, 'grand');
  assert.equal(above.rows[1].kind, 'node');
  assert.equal(above.rows[1].expanded, true);
});

test('non-additive measures: never added up in the browser; asked of the server for exactly those cells and merged by coordinates', () => {
  const data = trades(12);
  const view = Object.assign({}, VIEW, { values: [{ measure: 'sum(NOTIONAL)', as: 'Notional' }, { measure: 'var(99, 1d)', as: 'VaR' }, { measure: 'sum(VAR)', as: 'VaR sum' }] });
  const col = (f) => (f === 'VAR' ? { additive: false } : null);
  const a = G.pivotLocal(data, view, { column: col });
  assert.deepEqual(a.measures.map((m) => m.server), [false, true, true]);
  assert.equal(a.cells['|'].values.VaR, null);
  assert.match(a.cells['|'].notes.VaR, /asked of the server/);
  assert.equal(a.pending.length, Object.keys(a.cells).length * 2);
  const req = G.serverRequest(view, { only: ['VaR', 'VaR sum'] });
  assert.deepEqual(req.view.values.map((v) => v.as), ['VaR', 'VaR sum']);
  assert.deepEqual(Object.keys(req.view).sort(), ['columns', 'expandTo', 'expanded', 'filters', 'rows', 'sort', 'subtotals', 'top', 'values']);
  const server = { cells: {} };
  Object.values(a.cells).forEach((c, i) => { server.cells['s' + i] = { coords: c.coords, values: { VaR: 100 + i, 'VaR sum': 7 } }; });
  assert.equal(G.mergeAnswer(a, server), a.pending.length + Object.keys(a.cells).length * 2 - a.pending.length);
  assert.equal(a.pending.length, 0);
  assert.equal(typeof a.cells['|'].values.VaR, 'number');
  assert.equal(a.cells['|'].notes, undefined);
});

test('top N per level with an Others member made from its own members, and sort by a measure', () => {
  const data = trades(40);
  const a = G.pivotLocal(data, Object.assign({}, VIEW, { columns: [], top: { BOOK: { n: 3, by: 'Notional' } }, rows: ['BOOK'], sort: { BOOK: { by: 'Notional', desc: true } } }));
  const nodes = a.rows.filter((r) => r.kind === 'node' || r.kind === 'others');
  assert.equal(nodes.length, 4);
  assert.equal(nodes[3].kind, 'others');
  assert.equal(nodes[3].others, 4);
  const sumOf = (books) => data.filter((r) => books.indexOf(r.BOOK) >= 0).reduce((s, r) => s + r.NOTIONAL, 0);
  const shown = nodes.slice(0, 3).map((r) => r.value);
  const rest = ['B0', 'B1', 'B2', 'B3', 'B4', 'B5', 'B6'].filter((b) => shown.indexOf(b) < 0);
  assert.equal(a.cells[nodes[3].key + '|'].values.Notional, sumOf(rest));
  const figs = nodes.slice(0, 3).map((r) => a.cells[r.key + '|'].values.Notional);
  assert.deepEqual(figs, figs.slice().sort((x, y) => y - x));
});

test('member filters and value filters: totals follow the members kept (locally); a server answer hides rows and says so', () => {
  const data = trades(40);
  const a = G.pivotLocal(data, Object.assign({}, VIEW, { filters: { DESK: { in: ['FX', 'RATES'] } } }));
  assert.deepEqual(a.rows.filter((r) => r.kind === 'node').map((r) => r.value), ['FX', 'RATES']);
  const big = G.pivotLocal(data, Object.assign({}, VIEW, { valueFilters: [{ dim: 'DESK', by: 'Notional', op: 'gt', value: 50000 }] }));
  const kept = big.rows.filter((r) => r.kind === 'node').map((r) => r.value);
  const totals = {};
  data.forEach((r) => { totals[r.DESK] = (totals[r.DESK] || 0) + r.NOTIONAL; });
  assert.deepEqual(kept.sort(), Object.keys(totals).filter((d) => totals[d] > 50000).sort());
  assert.equal(big.cells['|'].values.Notional, kept.reduce((s, d) => s + totals[d], 0));
  const server = G.pivotLocal(data, VIEW);
  assert.equal(G.applyValueFilters(server, [{ dim: 'DESK', by: 'Notional', op: 'gt', value: 50000 }]), 4 - kept.length);
  assert.match(server.notes[0], /totals include them/);
  const mf = G.memberFilters({ DESK: { type: 'set', values: ['FX'] }, BOOK: { type: 'text', op: 'contains', value: 'B' } });
  assert.deepEqual(mf.filters, { DESK: { in: ['FX'], notIn: [] } });
  assert.equal(mf.unsupported.length, 1);
});

test('the open state: rows and column groups open and close; a collapsed column group shows its total only; the view names open nodes', () => {
  const data = trades(24);
  const st = new G.PivotState();
  const view = { rows: ['DESK', 'BOOK'], columns: ['CCY', 'TRADE_DATE'], values: [{ measure: 'sum(NOTIONAL)', as: 'N' }] };
  let a = G.pivotLocal(data, view, { state: st });
  assert.ok(a.columns.every((c) => c.kind === 'node' || c.kind === 'grand'));
  st.toggle('columns', [['CCY', 'USD']], true);
  a = G.pivotLocal(data, view, { state: st });
  assert.ok(a.columns.some((c) => c.kind === 'total' && c.label === 'USD total'));
  assert.equal(a.columns.filter((c) => c.path.length === 2).every((c) => c.path[0][1] === 'USD'), true);
  st.toggle('rows', [['DESK', 'FX']]);
  a = G.pivotLocal(data, view, { state: st });
  assert.ok(a.rows.some((r) => r.kind === 'header' && r.value === 'FX'));
  const v = st.toView(a);
  assert.equal(v.expanded.length, 2);
  st.expandToLevel('rows', 2);
  st.toggle('rows', [['DESK', 'RATES']], false);
  a = G.pivotLocal(data, view, { state: st });
  assert.equal(a.rows.find((r) => r.value === 'RATES').kind, 'node');
  const tv = st.toView(a);
  assert.equal(tv.expandTo.rows, 1);
  assert.ok(tv.expanded.some((e) => e.path[0][1] === 'FX') && !tv.expanded.some((e) => e.path[0][1] === 'RATES'));
  const cols = G.resultColumns(a);
  assert.equal(cols[cols.length - 1].headerPath.join('/'), 'Grand total/N');
});

test('the wells: what may go where, moves between Rows and Columns, aggregation per value, non-additive kept, the view made', () => {
  const w = new G.WellsModel(new G.ColumnModel(COLS));
  assert.equal(w.canDrop('NOTIONAL', 'rows').ok, false);
  assert.match(w.canDrop('NOTIONAL', 'rows').why, /Values/);
  w.drop('DESK', 'rows');
  w.drop('CCY', 'columns');
  w.drop('DESK', 'columns', 0);
  assert.deepEqual(w.getState().rows, []);
  assert.deepEqual(w.getState().columns, ['DESK', 'CCY']);
  w.drop('NOTIONAL', 'values');
  w.drop('VAR', 'values');
  assert.equal(w.setAggregation(0, 'avg').ok, true);
  assert.equal(w.setAggregation(1, 'sum').ok, false);
  w.drop('BOOK', 'filters');
  w.setFilter('BOOK', { type: 'set', values: ['B1'] });
  const v = w.toView();
  assert.deepEqual(v.values, [{ measure: 'avg(NOTIONAL)', as: 'Notional (average)' }, { measure: 'var(99, 1d)', as: 'VaR' }]);
  assert.deepEqual(v.filters, { BOOK: { in: ['B1'], notIn: [] } });
  assert.deepEqual(w.fieldList('desk')[0].in, ['columns']);
});

test('selection: rows by click, ctrl and shift; ranges; figures warn on a non-additive column; TSV copy', () => {
  const s = new G.SelectionModel();
  const idAt = (i) => 'r' + i;
  s.clickRow(2, idAt);
  s.clickRow(5, idAt, { shift: true });
  assert.deepEqual(s.selectedIds(), ['r2', 'r3', 'r4', 'r5']);
  s.clickRow(3, idAt, { ctrl: true });
  assert.equal(s.isSelected('r3'), false);
  s.startRange(0, 0);
  s.extendRange(2, 1);
  assert.equal(s.inRange(1, 1), true);
  const vals = [[1, 10], [2, 20], [3, 'x']];
  const ok = G.rangeStats(s.ranges, (r, c) => vals[r][c], (j) => ({ header: 'C' + j, additive: true }));
  assert.deepEqual([ok.cells, ok.count, ok.sum, ok.min, ok.max, ok.avg], [6, 5, 36, 1, 20, 7.2]);
  const v = G.rangeStats(s.ranges, (r, c) => vals[r][c], (j) => ({ header: j ? 'VaR' : 'N', additive: j === 0 }));
  assert.equal(v.sum, null);
  assert.match(v.warning, /VaR is not additive/);
  assert.equal(G.toTsv([['a', 'b\tc'], [1, 'say "hi"']]), 'a\t"b\tc"\n1\t"say ""hi"""');
});

test('export: CSV quoted and with a BOM; an xlsx that is a real zip with numbers, formats, a frozen bold heading and outline levels', () => {
  const t = { columns: [{ header: 'Desk', type: 'text' }, { header: 'Notional', type: 'number', format: 'amount2' }, { header: 'Share', type: 'number', format: 'pct2' },
    { header: 'Day', type: 'date' }], rows: [['RATES, NY', 1234.5, 0.25, '2026-11-06'], ['FX "spot"', null, 0.5, '2026-11-07']], levels: [0, 1], bold: [true, false] };
  const csv = G.toCsv(t);
  assert.equal(csv, '﻿Desk,Notional,Share,Day\r\n"RATES, NY",1234.5,0.25,2026-11-06\r\n"FX ""spot""",,0.5,2026-11-07\r\n');
  assert.match(G.toCsv(t, { formatted: true }), /"1,234.50",25.00%/);
  assert.equal(G.excelFormat({ type: 'number', format: 'pct2' }), '0.00%');
  assert.equal(G.excelFormat({ type: 'number', format: 'signed0' }), '+#,##0;-#,##0;#,##0');
  const x = G.toXlsx(t, { sheetName: 'Trades' });
  assert.equal(x[0], 0x50);
  assert.equal(x[1], 0x4b);
  const text = new TextDecoder().decode(x);
  assert.match(text, /<pane ySplit="1"/);
  assert.match(text, /outlineLevel="1"/);
  assert.match(text, /<v>1234.5<\/v>/);
  assert.match(text, /<v>46332<\/v>/);                                    // 2026-11-06 as an Excel date serial
  assert.match(text, /formatCode="#,##0.00"/);
  assert.equal(G.crc32(new TextEncoder().encode('123456789')), 0xCBF43926);
});

test('the grid model: grid and pivot on one model, keys, copy, range figures, the Explain hook, density and layout, state', async () => {
  const explained = [];
  const g = G.createGridModel({ columns: COLS, rows: trades(30), explain: (ctx) => { explained.push(ctx); return 'because'; }, layout: 'terminal' });
  g.viewport.resize(900, 300);
  assert.equal(g.rowCount(), 30);
  assert.match(G.renderText(g).split('\n')[0], /^Desk \| Currency/);
  g.toggleSort('NOTIONAL');
  g.toggleSort('NOTIONAL');
  assert.equal(g.cellValue(0, 3), 9001);
  assert.equal(g.cellText(0, 3), '9,001');
  let r = g.key({ key: 'ArrowDown' });
  assert.equal(r.handled, true);
  g.key({ key: 'ArrowRight', shiftKey: true });
  r = g.key({ key: 'c', ctrlKey: true });
  assert.equal(r.action, 'copy');
  assert.equal(r.copy.split('\t').length, 2);
  assert.equal(await g.explain(0, 3), 'because');
  assert.equal(explained[0].value, 9001);
  g.wells.drop('DESK', 'rows');
  g.wells.drop('CCY', 'columns');
  g.wells.drop('NOTIONAL', 'values');
  g.wells.drop('VAR', 'values');
  await g.setPivotMode(true);
  assert.equal(g.shownColumns()[0].rowHeader, true);
  assert.equal(g.rowCount(), 5);
  const varCol = g.shownColumns().findIndex((c) => c.measure === 'VaR');
  assert.equal(g.cellText(0, varCol), '…');
  g.selection.startRange(0, 1);
  g.selection.extendRange(4, varCol);
  assert.equal(g.rangeStats().warning, null);                           // the VaR cells wait for the server: nothing of theirs is summed
  const ctx = g.cellContext(0, 1);
  assert.equal(ctx.kind, 'pivot');
  assert.ok(ctx.coords.DESK && ctx.coords.CCY);
  const xlsx = g.exportXlsx();
  assert.ok(xlsx.length > 1000);
  g.setDensity('comfortable');
  assert.equal(g.viewport.rowHeight, 36);
  const state = g.getState();
  assert.equal(state.layout, 'terminal');
  const h = G.createGridModel({ columns: COLS, rows: trades(30) });
  await h.setState(state);
  assert.equal(h.pivotMode, true);
  assert.equal(h.rowCount(), 5);
  assert.throws(() => g.setLayout('cards'), /no layout/);
});

test('a non-additive pivot measure is asked of the server and merged in; a pivot of server-side rows is the server answer', async () => {
  const asked = [];
  const serverPivot = (body) => {
    asked.push(body);
    const local = G.pivotLocal(trades(30), Object.assign({}, body.view, { values: [{ measure: 'sum(VAR)', as: body.view.values[0].as }] }));
    return Promise.resolve(local);
  };
  const g = G.createGridModel({ columns: COLS, rows: trades(30), serverPivot: serverPivot });
  g.wells.drop('DESK', 'rows');
  g.wells.drop('NOTIONAL', 'values');
  g.wells.drop('VAR', 'values');
  await g.setPivotMode(true);
  assert.equal(asked.length, 1);
  assert.deepEqual(asked[0].view.values.map((v) => v.as), ['VaR']);
  assert.equal(g.answer.pending.length, 0);
  const vc = g.shownColumns().findIndex((c) => c.measure === 'VaR');
  assert.equal(typeof g.cellValue(0, vc), 'number');
  g.selection.startRange(0, 1);
  g.selection.extendRange(3, vc);
  const st = g.rangeStats();
  assert.equal(st.sum, null);
  assert.match(st.warning, /VaR is not additive/);
  const s = G.createGridModel({ columns: COLS, datasource: G.memoryDatasource(trades(10)), serverPivot: (b) => Promise.resolve(G.pivotLocal(trades(10), b.view)) });
  s.wells.drop('DESK', 'rows');
  s.wells.drop('NOTIONAL', 'values');
  await s.setPivotMode(true);
  assert.equal(s.answer.computedBy, 'browser');
  assert.equal(s.rowCount(), 5);
});

test('the keys: arrows, edges, pages, tab wraps, groups open and close, explain only with a hook', () => {
  const groups = new Set([0]);
  const open = new Set();
  const n = new G.GridNavigator({ rowCount: () => 100, columnCount: () => 5, pageSize: () => 20, isGroup: (r) => groups.has(r), isExpanded: (r) => open.has(r), explain: true });
  assert.deepEqual(n.handleKey({ key: 'ArrowDown' }).focus, { row: 0, col: 0 });
  assert.equal(n.handleKey({ key: 'ArrowRight' }).action, 'expand');
  open.add(0);
  assert.equal(n.handleKey({ key: 'ArrowLeft' }).action, 'collapse');
  assert.equal(n.handleKey({ key: 'ArrowRight', shiftKey: true }).action, 'expand-all');
  assert.deepEqual(n.handleKey({ key: 'PageDown' }).focus, { row: 20, col: 0 });
  assert.deepEqual(n.handleKey({ key: 'End', ctrlKey: true }).focus, { row: 99, col: 4 });
  assert.equal(n.handleKey({ key: 'Tab' }).handled, false);
  n.setFocus(3, 4);
  assert.deepEqual(n.handleKey({ key: 'Tab' }).focus, { row: 4, col: 0 });
  assert.equal(n.handleKey({ key: 'e' }).action, 'explain');
  assert.equal(n.handleKey({ key: 'ArrowUp', shiftKey: true }).action, 'extend-range');
  assert.equal(new G.GridNavigator({ rowCount: () => 1, columnCount: () => 1 }).handleKey({ key: 'e' }).handled, true);
});

test('the bundle is the modules and finds drishti-core', () => {
  const b = require('../../web/static/elements/drishti-grid/drishti_grid.js');
  assert.deepEqual(Object.keys(b).filter((k) => k !== 'library' && k !== 'version').sort(), Object.keys(G).sort());
  const g = b.createGridModel({ columns: [{ field: 'A', type: 'number', format: 'amount2' }], rows: [{ A: -1.005 }] });
  assert.equal(g.cellText(0, 0), M + '1.01');
});

test('clientAggregation off (the console policy): no group figure in the browser, every pivot asked of the server with the rows', async () => {
  const rows = trades(12), asked = [];
  const g = G.createGridModel({ columns: COLS, rows: rows, clientAggregation: false, serverPivot: (b, s, inline) => { asked.push(inline.length); return Promise.resolve(G.pivotLocal(inline, b.view)); } });
  g.setRowGroups(['DESK']);
  const grp = g.rowAt(0);
  assert.equal(grp.aggregates.NOTIONAL, null);
  assert.ok(grp.pendingServer.indexOf('NOTIONAL') >= 0);
  g.setRowGroups([]);
  g.wells.drop('DESK', 'rows');
  g.wells.drop('NOTIONAL', 'values');
  await g.setPivotMode(true);
  assert.deepEqual(asked, [12]);
  assert.equal(new G.ColumnModel([{ field: 'X', type: 'number', aggregate: 'server' }]).get('X').additive, false);
});
