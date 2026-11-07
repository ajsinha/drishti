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
/* The AG Grid-style adapter's harness (drishti_ag-harness.html): a grid made only from AG Grid Enterprise-shaped options by
   createGridFromAgOptions, over invented trades. The non-additive VaR's totals come from a stand-in server in the page (each cell's
   diversified VaR of its own trades, never a sum); ?mode=server answers an AG-shaped serverSideDatasource in the page; ?mode=tree is
   tree data. window.__ag is what the browser test drives: the handle (api), the AG events heard, the requests the datasource saw. */
(function () {
  'use strict';
  const G = window.Drishti.elements.grid;
  const q = new URLSearchParams(location.search), mode = q.get('mode') || 'pivot';
  const RHO = 0.32;
  let seed = 7;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const DESKS = ['Rates', 'Credit', 'FX', 'Equities'], BOOKS = ['Book 1', 'Book 2', 'Book 3'], REGIONS = ['Americas', 'EMEA', 'APAC'];
  const data = [];
  DESKS.forEach((d) => BOOKS.forEach((b) => REGIONS.forEach((r) => {
    for (let k = 0; k < 3; k++) {
      data.push({ id: 'T' + (data.length + 1), desk: d, book: d + ' · ' + b, region: r, mtm: Math.round((rnd() - 0.4) * 8e6), var: Math.round((0.5 + rnd() * 1.5) * 1e6),
        path: ['Bank', d, d + ' · ' + b, 'T' + (data.length + 1)] });
    }
  })));
  const H = window.__ag = { data: data, events: [], requests: [], asked: 0, ready: false };
  const log = document.getElementById('hx-log');
  const heard = (name) => (e) => { H.events.push({ type: name, colId: e.column ? e.column.colId : null, value: e.value === undefined ? null : e.value, rowIndex: e.rowIndex === undefined ? null : e.rowIndex }); log.textContent = H.events.length + ' events · last ' + name; };
  const diversified = (rows) => { let s2 = 0, s1 = 0; rows.forEach((r) => { s2 += r.var * r.var; s1 += r.var; }); return Math.sqrt((1 - RHO) * s2 + RHO * s1 * s1); };

  /* the stand-in server for a client-side pivot's non-additive VaR: each cell from its own trades */
  function serverPivot(body, signal, rows) {
    H.asked++;
    const src = rows || data, view = body.view;
    const a = G.pivotLocal(src, Object.assign({}, view, { values: [{ measure: 'count()', as: '__n' }] }));
    Object.keys(a.cells).forEach((k) => {
      const c = a.cells[k], members = src.filter((r) => Object.keys(c.coords).every((d) => String(r[d]) === c.coords[d]));
      c.values = {};
      view.values.forEach((v) => { c.values[v.as || v.measure] = diversified(members); });
    });
    return new Promise((resolve) => setTimeout(() => resolve(a), 30));
  }

  /* an AG-shaped server-side datasource: group levels with their figures (VaR diversified, never summed) */
  const serverSideDatasource = {
    getRows(params) {
      const r = params.request;
      H.requests.push(JSON.parse(JSON.stringify(r)));
      let list = data.filter((x) => r.groupKeys.every((k, i) => String(x[r.rowGroupCols[i].field]) === k));
      Object.keys(r.filterModel || {}).forEach((k) => { const f = r.filterModel[k]; if (f.filterType === 'set' && f.values) { list = list.filter((x) => f.values.indexOf(String(x[k])) >= 0); } });
      let out;
      if (r.groupKeys.length < r.rowGroupCols.length) {
        const field = r.rowGroupCols[r.groupKeys.length].field, by = new Map();
        list.forEach((x) => { (by.get(x[field]) || by.set(x[field], []).get(x[field])).push(x); });
        out = Array.from(by.keys()).sort().map((k) => {
          const rows = by.get(k), row = { [field]: k, childCount: rows.length };
          r.valueCols.forEach((v) => { row[v.field] = v.aggFunc === 'drishti:server' ? Math.round(diversified(rows)) : rows.reduce((s, x) => s + x[v.field], 0); });
          return row;
        });
      } else {
        out = list.slice();
        const s = r.sortModel[0];
        if (s) { const d = s.sort === 'desc' ? -1 : 1; out.sort((a, b) => (a[s.colId] < b[s.colId] ? -d : a[s.colId] > b[s.colId] ? d : 0)); }
      }
      setTimeout(() => params.success({ rowData: out.slice(r.startRow, r.endRow), rowCount: out.length }), 20);
    }
  };

  const money = (p) => (p.value === null || p.value === undefined ? '' : (p.value / 1e6).toFixed(2) + 'm');
  const columnDefs = [
    { field: 'desk', headerName: 'Desk', rowGroup: mode !== 'tree', enableRowGroup: true, filter: true },
    { field: 'book', headerName: 'Book', enableRowGroup: true },
    { field: 'region', headerName: 'Region', pivot: mode === 'pivot', enablePivot: true, filter: 'agSetColumnFilter' },
    { field: 'mtm', headerName: 'MTM', aggFunc: 'sum', enableValue: true, valueFormatter: money, cellClassRules: { 'hx-neg': (p) => p.value < 0 } },
    { field: 'var', headerName: 'VaR 99% 1d', aggFunc: 'drishti:server:var(99, 1d)', enableValue: true, valueFormatter: money }
  ];
  const gridOptions = {
    columnDefs: columnDefs, defaultColDef: { width: 130, sortable: true, resizable: true }, getRowId: (p) => p.data.id || [].concat(p.parentKeys, [p.data.desk]).join('/'),
    rowSelection: { mode: 'multiRow' }, animateRows: true, sideBar: mode === 'pivot', context: { harness: 'ag' },
    onGridReady: heard('gridReady'), onFirstDataRendered: heard('firstDataRendered'), onCellClicked: heard('cellClicked'), onSortChanged: heard('sortChanged'),
    onColumnPivotModeChanged: heard('columnPivotModeChanged'), onRowGroupOpened: heard('rowGroupOpened'), onSelectionChanged: heard('selectionChanged'),
    drishti: { serverPivot: serverPivot, title: 'From AG Grid-shaped options', download: (name, blob) => { H.download = { name: name, size: blob.size }; } }
  };
  if (mode === 'pivot') { Object.assign(gridOptions, { rowData: data, pivotMode: true, groupDefaultExpanded: 0, grandTotalRow: 'bottom', pivotRowTotals: 'after', suppressAggFuncInHeader: true }); }
  if (mode === 'server') { Object.assign(gridOptions, { rowModelType: 'serverSide', serverSideDatasource: serverSideDatasource, cacheBlockSize: 50 }); }
  if (mode === 'tree') {
    Object.assign(gridOptions, { rowData: data, treeData: true, getDataPath: (d) => d.path, autoGroupColumnDef: { headerName: 'Organisation', width: 260 }, groupDefaultExpanded: 1 });
    columnDefs.forEach((c) => { delete c.rowGroup; delete c.pivot; });
    delete columnDefs[4].aggFunc;                                         // a tree node's VaR is not a sum: none shown here
  }
  const box = document.createElement('div');
  box.className = 'hx-one';
  document.getElementById('hx-stage').appendChild(box);
  H.api = G.createGridFromAgOptions(box, gridOptions);
  H.ready = true;
}());
