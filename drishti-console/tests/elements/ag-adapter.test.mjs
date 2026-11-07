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
/* drishti-grid's AG Grid-style adapter without a page (ELEMENTS.md section 6): the option mapping, a pivot made from AG-shaped options
   equal to drishti-grid's own pivot of the same data, custom and non-additive aggregations, the totals and open levels, the server-side
   request translated (and a server pivot built from rows and pivotResultFields), tree data, the AG-named events, the grid API calls, the
   filter models both ways, and every option not covered named in a warning and in reportUnsupported(). The data is invented. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';

const DESKS = ['Rates', 'FX', 'Credit'], REGIONS = ['EMEA', 'APAC', 'Americas'], BOOKS = ['A', 'B'];
const DATA = () => {
  const out = [];
  let n = 0;
  DESKS.forEach((d, i) => REGIONS.forEach((r, j) => BOOKS.forEach((b, k) => {
    n++;
    out.push({ id: 'T' + n, desk: d, region: r, book: b, mtm: (i + 1) * 100 - j * 7 + k * 3, qty: n % 4 + 1, var: 10 + i * 3 + j + k });
  })));
  return out;
};
const quiet = () => { const said = []; return { said: said, out: { warn: (m) => said.push('warn ' + m), info: (m) => said.push('info ' + m) } }; };
const make = (go, q) => G.createGridFromAgOptions(null, go, { out: (q || quiet()).out });
const texts = (m) => { const out = []; for (let r = 0; r < m.rowCount(); r++) { const row = []; for (let c = 0; c < m.shownColumns().length; c++) { row.push(m.cellText(r, c)); } out.push(row.join(' | ')); } return out; };
const tick = () => new Promise((r) => setTimeout(r, 5));

/** A server written as an application's AG-style serverSideDatasource would be: groups, pivot result fields, set filters, sort. */
function agServer(rows, o) {
  o = o || {};
  const calls = [];
  const agg = (list, v) => {
    const xs = list.map((r) => r[v.field]);
    if (v.aggFunc === 'drishti:server') { return Math.round(Math.sqrt(xs.reduce((a, x) => a + x * x, 0))); }       // not a sum: diversified
    if (v.aggFunc === 'avg') { return xs.reduce((a, x) => a + x, 0) / xs.length; }
    return xs.reduce((a, x) => a + x, 0);
  };
  const figures = (list, q, row, fields) => {
    if (q.pivotMode && q.pivotCols.length) {
      const by = new Map();
      list.forEach((r) => { const k = q.pivotCols.map((c) => r[c.field]).join('_'); (by.get(k) || by.set(k, []).get(k)).push(r); });
      Array.from(by.keys()).sort().forEach((k) => q.valueCols.forEach((v) => { const f = k + '_' + v.id; row[f] = agg(by.get(k), v); fields.add(f); }));
    } else { q.valueCols.forEach((v) => { row[v.field] = agg(list, v); }); }
    return row;
  };
  return {
    calls: calls,
    getRows(params) {
      const q = params.request;
      calls.push(q);
      if (o.fail) { params.fail(); return; }
      let list = rows.filter((r) => q.groupKeys.every((k, i) => String(r[q.rowGroupCols[i].field]) === k));
      Object.keys(q.filterModel).forEach((k) => { const f = q.filterModel[k]; if (f.filterType === 'set' && f.values) { list = list.filter((r) => f.values.indexOf(String(r[k])) >= 0); } });
      const fields = new Set();
      let out;
      if (q.groupKeys.length < q.rowGroupCols.length) {
        const col = q.rowGroupCols[q.groupKeys.length].field, m = new Map();
        list.forEach((r) => { (m.get(r[col]) || m.set(r[col], []).get(r[col])).push(r); });
        out = Array.from(m.keys()).sort().map((k) => figures(m.get(k), q, { [col]: k, childCount: m.get(k).length }, fields));
      } else if (q.pivotMode) { out = [figures(list, q, {}, fields)]; }
      else {
        out = list.slice();
        if (q.sortModel.length) { const s = q.sortModel[0], d = s.sort === 'desc' ? -1 : 1; out.sort((a, b) => (a[s.colId] < b[s.colId] ? -d : a[s.colId] > b[s.colId] ? d : 0)); }
      }
      const answer = () => params.success({ rowData: out.slice(q.startRow, q.endRow), rowCount: out.length, pivotResultFields: q.pivotMode ? Array.from(fields).sort() : undefined });
      if (o.delay) { setTimeout(answer, o.delay); } else { answer(); }
    }
  };
}

test('columns: fields, headings, widths, pinning, hiding, groups, defaults, types, functions; rows, ids, selection, layout and density', () => {
  const q = quiet();
  const api = make({
    defaultColDef: { width: 111, filter: true },
    columnTypes: { money: { cellDataType: 'number', valueFormatter: (p) => '$' + p.value } },
    columnDefs: [
      { headerName: 'Book', children: [{ field: 'desk', headerName: 'Desk', pinned: true }, { field: 'book', hide: true, headerTooltip: 'the book' }] },
      { field: 'mtm', type: 'money', minWidth: 50, maxWidth: 300, sortable: false, cellClass: (p) => (p.value < 200 ? 'low' : 'high'), cellStyle: { fontWeight: 'bold', position: 'absolute' } },
      { colId: 'twice', valueGetter: (p) => p.data.qty * 2, type: 'numericColumn', comparator: (a, b) => b - a, filter: false },
      { field: 'qty', width: 80, pinned: 'right', resizable: false, editable: true }],
    rowData: DATA(), getRowId: (p) => p.data.id, rowSelection: { mode: 'singleRow', checkboxes: false }, sideBar: true, rowHeight: 22
  }, q);
  const c = (id) => api.model.columns.get(id);
  assert.equal(c('desk').header, 'Desk');
  assert.equal(c('desk').group, 'Book');
  assert.equal(c('desk').pinned, 'left');
  assert.equal(c('book').hidden, true);
  assert.equal(c('book').description, 'the book');
  assert.equal(c('desk').width, 111);
  assert.equal(c('desk').filterable, true);
  assert.equal(c('mtm').type, 'number');
  assert.equal(c('mtm').sortable, false);
  assert.equal(c('mtm').minWidth, 50);
  assert.equal(G.formatValue(118, c('mtm'), {}), '$118');
  assert.deepEqual(G.cellLook(c('mtm'), 118, {}), { cls: 'low', style: { 'font-weight': 'bold' } });     // position is not a safe style
  assert.equal(c('twice').type, 'number');
  assert.equal(G.getValue({ qty: 3 }, c('twice')), 6);
  assert.equal(c('twice').comparator(1, 2), 1);
  assert.equal(c('twice').filterable, false);
  assert.equal(c('qty').type, 'number');                                                                // from the first row
  assert.equal(c('qty').pinned, 'right');
  assert.equal(c('qty').resizable, false);
  assert.equal(c('qty').editable, true);
  assert.equal(api.model.rowModel.getRowId(DATA()[4], 4), 'T5');
  assert.equal(api.model.selection.mode, 'single');
  assert.equal(api.model.layout, 'panel');
  assert.equal(api.model.density, 'terminal');
  assert.equal(api.model.rowCount(), DATA().length);
  assert.equal(api.model.columns.get(G.AG_GROUP_COLUMN), null);                                        // nothing groups: no group column
  assert.ok(q.said.some((s) => /rowHeight is mapped in part/.test(s)));
  const plain = make({ columnDefs: [{ field: 'desk' }], rowData: DATA() });
  assert.equal(plain.model.selection.mode, 'none');
  assert.equal(plain.model.layout, 'wells');
  assert.equal(plain.model.columns.get('desk').filterable, false);                                     // AG: no filter unless asked
});

test('a pivot from AG-shaped options equals drishti-grid\'s own pivot of the same data', async () => {
  const api = make({
    columnDefs: [{ field: 'desk', rowGroup: true, rowGroupIndex: 0 }, { field: 'book', rowGroup: true, rowGroupIndex: 1 }, { field: 'region', pivot: true },
      { field: 'mtm', aggFunc: 'sum' }, { field: 'qty', aggFunc: 'avg' }],
    rowData: DATA(), pivotMode: true, groupDefaultExpanded: 1, groupTotalRow: 'bottom', grandTotalRow: 'bottom'
  });
  await api.ready;
  const own = G.createGridModel({ columns: [{ field: 'desk' }, { field: 'book' }, { field: 'region' }, { field: 'mtm', type: 'number' }, { field: 'qty', type: 'number' }], rows: DATA() });
  own.wells.setState({ rows: ['desk', 'book'], columns: ['region'], values: [{ colId: 'mtm', aggFunc: 'sum' }, { colId: 'qty', aggFunc: 'avg' }], filters: [] });
  own.pivotState.expandToLevel('rows', 2);
  await own.setPivotMode(true);
  assert.deepEqual(JSON.parse(JSON.stringify(api.model.answer.cells)), JSON.parse(JSON.stringify(own.answer.cells)));
  assert.deepEqual(api.model.answer.rows.map((r) => r.kind + ':' + r.key), own.answer.rows.map((r) => r.kind + ':' + r.key));
  assert.deepEqual(texts(api.model), texts(own));
  assert.equal(api.isPivotMode(), true);
  assert.deepEqual(api.getPivotResultColumns().slice(0, 2).map((c) => c.colDef.headerName), ['Americas › mtm', 'Americas › qty (average)']);
});

test('aggregations: built-in, a function, an aggFuncs name, and a non-additive measure asked of the server (never summed)', async () => {
  const asked = [];
  const serverPivot = (body) => { asked.push(body.view.values.map((v) => v.measure)); return Promise.resolve({ cells: {} }); };
  const api = make({
    columnDefs: [{ field: 'desk', rowGroup: true }, { field: 'mtm', aggFunc: (p) => Math.max(...p.values) - Math.min(...p.values) }, { field: 'qty', aggFunc: 'mid' },
      { field: 'var', aggFunc: 'drishti:server:var(99, 1d)' }],
    aggFuncs: { mid: (p) => p.values.slice().sort((a, b) => a - b)[Math.floor(p.values.length / 2)] },
    rowData: DATA(), pivotMode: true, grandTotalRow: 'top', suppressAggFuncInHeader: true, drishti: { serverPivot: serverPivot }
  });
  await api.ready;
  const m = api.model, a = m.answer;
  assert.equal(a.rows[0].kind, 'grand');                                                       // grandTotalRow top
  const fx = a.cells['FX|'].values;
  const fxRows = DATA().filter((r) => r.desk === 'FX');
  assert.equal(fx.mtm, Math.max(...fxRows.map((r) => r.mtm)) - Math.min(...fxRows.map((r) => r.mtm)));
  assert.equal(fx.qty, fxRows.map((r) => r.qty).sort((x, y) => x - y)[3]);
  assert.equal(fx.var, null);                                                                   // left for the server
  assert.equal(a.measures.find((x) => x.label === 'var').server, true);
  assert.deepEqual(asked, [['var(99, 1d)']]);                                                  // only the non-additive one asked
  assert.deepEqual(m.wells.toView().values.map((v) => v.as), ['mtm', 'qty', 'var']);            // suppressAggFuncInHeader
  const names = api.t.registered.slice();
  assert.equal(names.length, 2);
  assert.ok(names.every((n) => G.aggFunc(n)));
  api.destroy();
  assert.ok(names.every((n) => !G.aggFunc(n)));                                                // forgotten with the grid
  assert.throws(() => G.registerAggFunc('sum', () => 0), /new plain name/);
});

test('totals and open levels: grandTotalRow none, groupTotalRow off (figures on the group row), groupDefaultExpanded -1, expandAll and collapseAll', async () => {
  const api = make({ columnDefs: [{ field: 'desk', rowGroup: true }, { field: 'book', rowGroup: true }, { field: 'mtm', aggFunc: 'sum' }], rowData: DATA(), pivotMode: true,
    groupDefaultExpanded: -1 });
  await api.ready;
  const kinds = api.model.answer.rows.map((r) => r.kind);
  assert.ok(kinds.indexOf('grand') < 0);
  assert.ok(kinds.indexOf('total') < 0 && kinds.indexOf('header') < 0);
  assert.equal(api.model.rowCount(), 3 + 6);
  await api.collapseAll();
  assert.equal(api.model.rowCount(), 3);
  await api.expandAll();
  assert.equal(api.model.rowCount(), 9);
  api.setGridOption('grandTotalRow', 'bottom');
  await tick();
  assert.equal(api.model.answer.rows[api.model.answer.rows.length - 1].kind, 'grand');
  const grouped = make({ columnDefs: [{ field: 'desk', rowGroup: true, hide: true }, { field: 'mtm', aggFunc: 'sum' }], rowData: DATA(), autoGroupColumnDef: { headerName: 'Desk tree', minWidth: 90 } });
  const g = grouped.model;
  assert.equal(g.shownColumns()[0].header, 'Desk tree');
  assert.equal(g.rowCount(), 3);
  assert.equal(g.cellText(0, 0), 'Rates (6)');                                                        // in the data's order, as AG Grid groups
  assert.equal(g.cellValue(0, 1), DATA().filter((r) => r.desk === 'Rates').reduce((s, r) => s + r.mtm, 0));
  grouped.expandAll();
  assert.equal(g.rowCount(), 3 + 18);
  assert.equal(g.columns.fields().some((f) => f.id === G.AG_GROUP_COLUMN), false);                    // never in the field list
});

test('the server-side row model: the AG-shaped request, groups, rowCount, fail, refreshServerSide and purge', async () => {
  const server = agServer(DATA());
  const failed = [];
  const api = make({ rowModelType: 'serverSide', serverSideDatasource: server, cacheBlockSize: 4, getRowId: (p) => (p.data.id || p.parentKeys.concat([p.data.desk]).join('/')),
    columnDefs: [{ field: 'desk', rowGroup: true }, { field: 'region' }, { field: 'mtm', aggFunc: 'sum' }] });
  api.addEventListener('modelUpdated', () => {});
  api.model.on('blockFailed', (p) => failed.push(p));
  await api.model.rowModel.ensureRange(0, 3);
  const q = server.calls[0];
  assert.deepEqual(Object.keys(q).sort(), ['endRow', 'filterModel', 'groupKeys', 'pivotCols', 'pivotMode', 'rowGroupCols', 'sortModel', 'startRow', 'valueCols']);
  assert.deepEqual(q.rowGroupCols, [{ id: 'desk', displayName: 'desk', field: 'desk' }]);
  assert.deepEqual(q.valueCols, [{ id: 'mtm', displayName: 'mtm', field: 'mtm', aggFunc: 'sum' }]);
  assert.deepEqual([q.startRow, q.endRow, q.groupKeys, q.pivotMode], [0, 4, [], false]);
  assert.equal(api.model.rowCount(), 3);
  assert.equal(api.model.cellText(0, 0), 'Credit (6)');
  api.model.toggleRow(1, true);
  await api.model.rowModel.ensureRange(0, 10);
  const lv = server.calls[server.calls.length - 1];
  assert.deepEqual(lv.groupKeys, ['FX']);
  assert.equal(api.model.rowCount(), 3 + 6);
  api.setFilterModel({ region: { filterType: 'set', values: ['EMEA'] } });
  api.model.setSortModel([{ colId: 'mtm', sort: 'desc' }]);
  await api.model.rowModel.ensureRange(0, 2);
  const f = server.calls[server.calls.length - 1];
  assert.deepEqual(f.filterModel, { region: { filterType: 'set', values: ['EMEA'] } });
  assert.deepEqual(f.sortModel, [{ colId: 'mtm', sort: 'desc' }]);
  const before = server.calls.length;
  api.refreshServerSide({ purge: true });
  await api.model.rowModel.ensureRange(0, 2);
  assert.ok(server.calls.length > before);
  const bad = make({ rowModelType: 'serverSide', serverSideDatasource: agServer(DATA(), { fail: true }), columnDefs: [{ field: 'desk' }] });
  const errs = [];
  bad.model.on('blockFailed', (p) => errs.push(p.error.message));
  await bad.model.rowModel.ensureRange(0, 1);
  assert.match(errs[0], /datasource failed/);
  assert.equal(failed.length, 0);
});

test('a server-side pivot: each open level asked, pivotResultFields made into columns, the server\'s totals (non-additive kept)', async () => {
  const server = agServer(DATA());
  const api = make({ rowModelType: 'serverSide', serverSideDatasource: server, pivotMode: true, grandTotalRow: 'bottom', pivotRowTotals: 'after',
    columnDefs: [{ field: 'desk', rowGroup: true }, { field: 'book', rowGroup: true }, { field: 'region', pivot: true }, { field: 'mtm', aggFunc: 'sum' },
      { field: 'var', aggFunc: 'drishti:server' }] });
  await api.ready;
  const a = api.model.answer;
  assert.equal(a.computedBy, 'server');
  assert.deepEqual(a.columns.map((c) => c.key), ['APAC', 'Americas', 'EMEA', '']);
  assert.deepEqual(a.rows.map((r) => r.label), ['Credit', 'FX', 'Rates', 'Grand total']);
  const local = G.pivotLocal(DATA(), { rows: ['desk', 'book'], columns: ['region'], values: [{ measure: 'sum(mtm)', as: 'mtm' }] });
  ['Credit|EMEA', 'FX|APAC', 'Rates|', '|EMEA', '|'].forEach((k) => assert.equal(a.cells[k].values.mtm, local.cells[k].values.mtm, k));
  const fxVar = Math.round(Math.sqrt(DATA().filter((r) => r.desk === 'FX').reduce((s, r) => s + r.var * r.var, 0)));
  assert.equal(a.cells['FX|'].values.var, fxVar);                                              // the server's figure, not a sum
  assert.ok(server.calls.every((q) => q.pivotMode && q.valueCols.find((v) => v.id === 'var').aggFunc === 'drishti:server'));
  const n = server.calls.length;
  await api.model.toggleRow(1, true);
  assert.ok(server.calls.slice(n).some((q) => q.groupKeys.length === 1 && q.groupKeys[0] === 'FX'));
  assert.deepEqual(api.model.answer.rows.map((r) => r.label).slice(0, 4), ['Credit', 'FX', 'A', 'B']);
  assert.deepEqual(G.parseResultField('EMEA_EUR_mtm_usd', ['mtm_usd', 'qty'], '_', 2), { keys: ['EMEA', 'EUR'], valueId: 'mtm_usd' });
  assert.deepEqual(G.parseResultField('A_B_C_qty', ['qty'], '_', 2), { keys: ['A', 'B_C'], valueId: 'qty' });
});

test('tree data: paths, the group column headed by autoGroupColumnDef, levels open by groupDefaultExpanded', () => {
  const rows = [{ id: 'a', path: ['Bank', 'Rates'], mtm: 1 }, { id: 'b', path: ['Bank', 'Rates', 'IRS-1'], mtm: 10 }, { id: 'c', path: ['Bank', 'FX', 'FXF-1'], mtm: 20 }];
  const api = make({ treeData: true, getDataPath: (d) => d.path, autoGroupColumnDef: { headerName: 'Organisation' }, groupDefaultExpanded: -1,
    columnDefs: [{ field: 'mtm', aggFunc: 'sum' }], rowData: rows, getRowId: (p) => p.data.id });
  const m = api.model;
  assert.equal(m.shownColumns()[0].header, 'Organisation');
  assert.deepEqual(texts(m), ['Bank | 31', 'Rates | 1', 'IRS-1 | 10', 'FX | 20', 'FXF-1 | 20']);
  assert.throws(() => make({ treeData: true, columnDefs: [{ field: 'mtm' }], rowData: rows }), /getDataPath/);
  const q = quiet();
  make({ treeData: true, getDataPath: (d) => d.path, rowModelType: 'serverSide', serverSideDatasource: agServer([]), isServerSideGroup: () => true, columnDefs: [{ field: 'mtm' }] }, q);
  assert.ok(q.said.some((s) => /isServerSideGroup is not supported/.test(s)) && q.said.some((s) => /treeData with rowModelType serverSide/.test(s)));
});

test('events: AG names with AG-shaped parameters, from drishti-grid\'s own; gridReady and firstDataRendered once', async () => {
  const seen = [];
  const log = (name) => (e) => seen.push([name, e]);
  const api = make({ columnDefs: [{ field: 'desk' }, { field: 'region' }, { field: 'mtm', aggFunc: 'sum' }], rowData: DATA(), getRowId: (p) => p.data.id, rowSelection: 'multiple',
    context: { app: 'x' }, onGridReady: log('ready'), onFirstDataRendered: log('first'), onCellClicked: log('cell'), onRowClicked: log('row'), onSelectionChanged: log('sel'),
    onSortChanged: log('sort'), onFilterChanged: log('filter'), onColumnPivotChanged: log('pivotCols'), onColumnRowGroupChanged: log('groups'),
    onColumnPivotModeChanged: log('mode'), onRowGroupOpened: log('open'), onColumnResized: log('resized'), onCellValueChanged: log('value') });
  await tick();
  assert.deepEqual(seen.map((s) => s[0]), ['ready', 'first']);
  assert.equal(seen[0][1].api, api);
  assert.deepEqual(seen[0][1].context, { app: 'x' });
  api.model.cellClicked(2, 0, {});
  const cell = seen.find((s) => s[0] === 'cell')[1];
  assert.equal(cell.type, 'cellClicked');
  assert.equal(cell.value, DATA()[2].desk);
  assert.equal(cell.data.id, 'T3');
  assert.equal(cell.column.colId, 'desk');
  assert.equal(cell.node.id, 'T3');
  assert.ok(seen.some((s) => s[0] === 'row') && seen.some((s) => s[0] === 'sel'));
  assert.deepEqual(api.getSelectedRows().map((r) => r.id), ['T3']);
  api.model.setSortModel([{ colId: 'mtm', sort: 'asc' }]);
  api.setFilterModel({ mtm: { filterType: 'number', type: 'greaterThan', filter: 150 } });
  api.setPivotColumns(['region']);
  api.setRowGroupColumns(['desk']);
  api.model.toggleRow(0, true);
  api.setColumnWidths([{ key: 'mtm', newWidth: 150 }]);
  await api.setPivotMode(true);
  const names = seen.map((s) => s[0]);
  ['sort', 'filter', 'pivotCols', 'groups', 'open', 'resized', 'mode'].forEach((n) => assert.ok(names.indexOf(n) >= 0, n));
  assert.equal(seen.find((s) => s[0] === 'open')[1].expanded, true);
  const extra = [];
  const fn = (e) => extra.push(e.type);
  api.addEventListener('modelUpdated', fn);
  api.setGridOption('rowData', DATA().slice(0, 4));
  await tick();
  assert.equal(extra.length >= 1 && extra.every((t) => t === 'modelUpdated'), true);
  const n = extra.length;
  api.removeEventListener('modelUpdated', fn);
  api.setGridOption('rowData', DATA());
  await tick();
  assert.equal(extra.length, n);
  assert.equal(names.filter((x) => x === 'first').length, 1);
});

test('the grid API: transactions, selection, nodes, CSV and Excel, filter and column state both ways, options at run time', async () => {
  const api = make({ columnDefs: [{ field: 'desk', filter: 'agSetColumnFilter' }, { field: 'region' }, { field: 'mtm', filter: 'agNumberColumnFilter' }], rowData: DATA(),
    getRowId: (p) => p.data.id, rowSelection: { mode: 'multiRow' } });
  const res = api.applyTransaction({ add: [{ id: 'N1', desk: 'Equities', region: 'EMEA', mtm: 5 }], update: [Object.assign(DATA()[0], { mtm: -1 })], remove: [DATA()[1]] });
  assert.deepEqual([res.add[0].id, res.update[0].id, res.remove[0].id], ['N1', 'T1', 'T2']);
  assert.equal(api.getDisplayedRowCount(), DATA().length);
  assert.equal(api.getRowNode('T1').data.mtm, -1);
  const ids = [];
  api.forEachNode((n) => ids.push(n.id));
  assert.equal(ids.length, DATA().length);
  assert.ok(ids.indexOf('N1') >= 0 && ids.indexOf('T2') < 0);
  api.getRowNode('N1').setSelected(true);
  api.getRowNode('T3').setSelected(true);
  assert.deepEqual(api.getSelectedRows().map((r) => r.id).sort(), ['N1', 'T3']);
  api.deselectAll();
  assert.equal(api.getSelectedNodes().length, 0);
  const done = await api.applyTransactionAsync({ update: [{ id: 'N1', desk: 'Equities', region: 'EMEA', mtm: 6 }] });
  assert.equal(done.update[0].data.mtm, 6);
  assert.match(api.getDataAsCsv().split('\r\n')[0], /desk,region,mtm/);
  const xlsx = api.getDataAsExcel({ sheetName: 'T' });
  assert.deepEqual([xlsx[0], xlsx[1]], [0x50, 0x4b]);                                             // a zip
  api.setFilterModel({ desk: { filterType: 'set', values: ['FX'] }, mtm: { filterType: 'number', operator: 'AND', conditions: [{ type: 'inRange', filter: 90, filterTo: 300 }, { type: 'lessThan', filter: 9 }] } });
  assert.deepEqual(api.model.filterModel, { desk: { type: 'set', values: ['FX'] }, mtm: { type: 'number', op: 'between', value: 90, valueTo: 300 } });
  assert.deepEqual(api.getFilterModel(), { desk: { filterType: 'set', values: ['FX'] }, mtm: { filterType: 'number', type: 'inRange', filter: 90, filterTo: 300 } });
  assert.equal(api.isAnyFilterPresent(), true);
  assert.ok(api.reportUnsupported().some((x) => /2 conditions/.test(x.words)));
  api.setFilterModel(null);
  assert.equal(api.isAnyFilterPresent(), false);
  api.applyColumnState({ state: [{ colId: 'mtm', sort: 'desc', width: 140, pinned: 'left' }, { colId: 'region', hide: true }, { colId: 'nope' }], defaultState: { pinned: null } });
  const st = api.getColumnState();
  assert.deepEqual(st.find((s) => s.colId === 'mtm'), { colId: 'mtm', width: 140, hide: false, pinned: 'left', sort: 'desc', sortIndex: 0, rowGroup: false, rowGroupIndex: null, pivot: false,
    pivotIndex: null, aggFunc: null, flex: null });
  assert.equal(st.find((s) => s.colId === 'region').hide, true);
  api.applyColumnState({ state: [{ colId: 'region', hide: false }, { colId: 'mtm' }, { colId: 'desk' }], applyOrder: true });
  assert.deepEqual(api.model.columns.all().map((c) => c.id), ['region', 'mtm', 'desk']);
  api.applyColumnState({ state: [{ colId: 'desk', rowGroup: true }, { colId: 'mtm', aggFunc: 'max' }] });
  assert.deepEqual(api.getRowGroupColumns().map((c) => c.colId), ['desk']);
  assert.equal(api.model.rowCount(), 4);                                                          // grouped by desk
  api.setGridOption('pivotMode', true);
  await tick();
  assert.equal(api.getValueColumns()[0].aggFunc, 'max');
  api.setGridOption('quickFilterText', 'FX');
  api.updateGridOptions({ pivotMode: false, rowSelection: 'single' });
  assert.ok(api.reportUnsupported().some((x) => x.option === 'rowSelection (at run time)'));
  assert.equal(api.getGridOption('pivotMode'), false);
});

test('the filter models both ways: text, number, date, set, blank, multi', () => {
  const t = (f, type) => G.agFilterToDrishti(f, type);
  assert.deepEqual(t({ filterType: 'text', type: 'startsWith', filter: 'Ra' }), { type: 'text', op: 'startsWith', value: 'Ra' });
  assert.deepEqual(t({ filterType: 'number', type: 'greaterThanOrEqual', filter: 3 }), { type: 'number', op: 'gte', value: 3 });
  assert.deepEqual(t({ filterType: 'date', type: 'inRange', dateFrom: '2026-01-01 00:00:00', dateTo: '2026-02-01 00:00:00' }), { type: 'date', op: 'between', value: '2026-01-01', valueTo: '2026-02-01' });
  assert.deepEqual(t({ filterType: 'set', values: ['A', null] }), { type: 'set', values: ['A', null] });
  assert.deepEqual(t({ type: 'blank' }), { type: 'blank', op: 'blank' });
  assert.deepEqual(t({ type: 'equals', filter: 5 }, 'number'), { type: 'number', op: 'equals', value: 5 });
  assert.deepEqual(t({ filterType: 'multi', filterModels: [null, { filterType: 'set', values: ['x'] }] }), { type: 'set', values: ['x'] });
  const said = [];
  assert.equal(G.agFilterToDrishti({ filterType: 'text', type: 'fuzzy', filter: 'x' }, null, (w) => said.push(w)), null);
  assert.match(said[0], /fuzzy/);
  ['contains', 'notContains', 'equals', 'notEqual', 'startsWith', 'endsWith'].forEach((type) => {
    const ag = { filterType: 'text', type: type, filter: 'q' };
    assert.deepEqual(G.drishtiFilterToAg(G.agFilterToDrishti(ag)), ag);
  });
  ['equals', 'notEqual', 'lessThan', 'lessThanOrEqual', 'greaterThan', 'greaterThanOrEqual'].forEach((type) => {
    const ag = { filterType: 'number', type: type, filter: 4 };
    assert.deepEqual(G.drishtiFilterToAg(G.agFilterToDrishti(ag)), ag);
  });
});

test('options not covered: named once in a warning with drishti-grid\'s way, information for no-ops, all in reportUnsupported(); the map in step', () => {
  const q = quiet();
  const api = make({ columnDefs: [{ field: 'desk', valueGetter: 'data.desk', cellRenderer: 'x', wobble: 1 }, { field: 'b', cellRenderer: 'y' }], rowData: [], pagination: true,
    animateRows: true, enableCharts: true, masterDetail: true, onPaginationChanged: () => {}, processPivotResultColDef: () => {}, mystery: 2, rowModelType: 'clientSide' }, q);
  const r = api.reportUnsupported(), by = (o) => r.find((x) => x.option === o);
  assert.equal(by('pagination').state, 'unsupported');
  assert.match(by('pagination').words, /virtual scrolling/);
  assert.equal(by('animateRows').state, 'no-op');
  assert.equal(by('enableCharts').state, 'unsupported');
  assert.equal(by('masterDetail').state, 'unsupported');
  assert.equal(by('onPaginationChanged').state, 'unsupported');
  assert.equal(by('processPivotResultColDef').state, 'unsupported');
  assert.equal(by('mystery').state, 'unknown');
  assert.equal(by('columnDefs[desk].valueGetter').state, 'unsupported');
  assert.equal(by('columnDefs[desk].wobble').state, 'unknown');
  assert.ok(by('columnDefs[desk].cellRenderer') && by('columnDefs[b].cellRenderer'));
  assert.equal(q.said.filter((s) => s.indexOf('warn ') === 0).length, r.filter((x) => x.state !== 'no-op').length);
  assert.equal(q.said.filter((s) => s.indexOf('info ') === 0).length, 1);
  assert.ok(q.said.every((s) => s.indexOf('drishti-grid (AG Grid-style adapter): ') > 0));
  api.setGridOption('getRowStyle', () => ({}));
  assert.ok(by('getRowStyle (at run time)') === undefined && api.reportUnsupported().some((x) => x.option === 'getRowStyle (at run time)'));
  const table = G.AG_MAPPING.map((x) => x.ag).join(' ') + ' ' + G.AG_NOT_COVERED;
  Object.keys(G.AG_UNSUPPORTED).concat(G.AG_GRID_OPTIONS.filter((k) => k !== 'drishti' && k !== 'context' && k !== 'datasource')).forEach((k) => {
    assert.ok(table.indexOf(k) >= 0 || /Renderer|Editor|Overlay|Component|Params|lock/.test(k), 'the map names ' + k);
  });
  assert.ok(G.AG_MAPPING.length >= 30 && G.AG_MAPPING.every((x) => ['mapped', 'partial', 'unsupported', 'no-op'].indexOf(x.state) >= 0));
  assert.ok(/master-detail/.test(G.AG_NOT_COVERED));
});
