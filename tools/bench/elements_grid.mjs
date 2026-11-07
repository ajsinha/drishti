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
/* drishti-grid's engine at a million rows (docs/admin/BENCHMARK.md, "Drishti Elements: the grid engine"), in Node, no browser:

     node tools/bench/elements_grid.mjs [rows=1000000] [frames=10000]

   What it times: the rows made and indexed; sorts (a number, a text, two columns); filters (members, a range, a quick filter); a scroll
   frame (the viewport's window and every drawn cell's text, at random positions, plus a full scroll top to bottom); row groups with
   their figures; a client-side pivot (two row levels, a column level, two values); the server-side row model paging through the rows.
   One JSON line at the end for the record. */
import { performance } from 'node:perf_hooks';
import * as G from '../../drishti-console/web/static/elements/drishti-grid/src/drishti_index.js';

const N = +(process.argv[2] || 1000000), FRAMES = +(process.argv[3] || 10000);
const out = { rows: N, node: process.version };
const t = (name, fn) => { const a = performance.now(); const r = fn(); out[name] = Math.round((performance.now() - a) * 10) / 10; return r; };
const pct = (xs, p) => { const s = xs.slice().sort((a, b) => a - b); return Math.round(s[Math.min(s.length - 1, Math.floor(p * s.length))] * 1000) / 1000; };

const DESKS = ['RATES', 'FX', 'EQUITY', 'CREDIT', 'COMMODITIES', 'EM', 'XVA', 'TREASURY'];
const CCY = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD'];
const rows = t('makeRowsMs', () => {
  const r = new Array(N);
  let seed = 7;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  for (let i = 0; i < N; i++) {
    r[i] = { id: 'T' + i, DESK: DESKS[i % 8], BOOK: 'B' + (i % 250), CCY: CCY[i % 6], CPTY: 'C' + Math.floor(rnd() * 5000), NOTIONAL: Math.round(rnd() * 1e8),
      PNL: Math.round((rnd() - 0.5) * 1e6) / 100, VAR: rnd() * 1e5, TRADE_DATE: '2026-' + String(1 + (i % 12)).padStart(2, '0') + '-15', STATUS: i % 17 ? 'LIVE' : 'MATURED' };
  }
  return r;
});
const COLS = [{ field: 'id' }, { field: 'DESK' }, { field: 'BOOK' }, { field: 'CCY' }, { field: 'CPTY' }, { field: 'NOTIONAL', type: 'number', format: 'amount0' },
  { field: 'PNL', type: 'number', format: 'amount2' }, { field: 'VAR', type: 'number', additive: false }, { field: 'TRADE_DATE', type: 'date' }, { field: 'STATUS' }];
const g = t('modelMs', () => G.createGridModel({ columns: COLS, rows: rows }));
const m = g.rowModel;
t('sortNumberMs', () => g.setSortModel([{ colId: 'NOTIONAL', sort: 'desc' }]));
t('sortTextMs', () => g.setSortModel([{ colId: 'CPTY', sort: 'asc' }]));
t('sortTwoColumnsMs', () => g.setSortModel([{ colId: 'DESK', sort: 'asc' }, { colId: 'PNL', sort: 'desc' }]));
g.setSortModel([]);
t('filterMembersMs', () => g.setFilterModel({ DESK: { type: 'set', values: ['RATES', 'FX'] } }));
out.filterMembersRows = m.filteredCount();
t('filterRangeMs', () => g.setFilterModel({ NOTIONAL: { type: 'number', op: 'between', value: 1e7, valueTo: 2e7 } }));
t('quickFilterMs', () => { g.setFilterModel({}); g.setQuickFilter('rates c42'); });
out.quickFilterRows = m.filteredCount();
g.setQuickFilter('');

// a scroll frame: the window at a position and every drawn cell's text (what a layout paints), 40 rows on screen
g.viewport.resize(1400, 1120);
g.viewport.setRowCount(g.rowCount());
const frames = [];
let seed = 3;
for (let f = 0; f < FRAMES; f++) {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff;
  const a = performance.now();
  g.viewport.scrollTo(seed / 0x7fffffff * g.viewport.maxScrollTop(), 0);
  const w = G.renderWindow(g);
  if (!w.cells.length) { throw new Error('nothing drawn'); }
  frames.push(performance.now() - a);
}
out.frameMedianMs = pct(frames, 0.5);
out.frameP99Ms = pct(frames, 0.99);
out.frameMaxMs = Math.round(Math.max.apply(null, frames) * 1000) / 1000;
out.cellsPerFrame = G.renderWindow(g).cells.length * G.renderWindow(g).columns.length;
out.scrollHeightPx = g.viewport.scrollHeight();
g.viewport.scrollTo(g.viewport.maxScrollTop());
out.lastRowReachable = g.viewport.rows().lastVisible === g.rowCount() - 1;
t('fullScrollMs', () => { for (let top = 0; top <= g.viewport.maxScrollTop(); top += g.viewport.height) { g.viewport.scrollTo(top); G.renderWindow(g); } });
out.fullScrollFrames = Math.ceil(g.viewport.maxScrollTop() / g.viewport.height) + 1;

t('groupTwoLevelsMs', () => g.setRowGroups(['DESK', 'BOOK']));
out.groupRows = g.rowCount();
t('expandAllGroupsMs', () => m.expandToLevel(Infinity));
out.expandedRows = g.rowCount();
g.setRowGroups([]);

g.wells.drop('DESK', 'rows'); g.wells.drop('BOOK', 'rows'); g.wells.drop('CCY', 'columns'); g.wells.drop('NOTIONAL', 'values'); g.wells.drop('PNL', 'values');
t('pivotMs', () => g.setPivotMode(true));
out.pivotRows = g.rowCount();
out.pivotColumns = g.shownColumns().length;
t('pivotExpandAllMs', () => g.expandPivotTo('rows', 2));
out.pivotRowsExpanded = g.rowCount();
await g.setPivotMode(false);

// the server-side row model over a memory source: every block of the rows asked as a scroll would
const ds = G.memoryDatasource(rows);
const s = new G.ServerRowModel({ datasource: ds, blockSize: 200, maxBlocks: 50 });
const a = performance.now();
await s.ensureRange(0, 199);
for (let i = 0; i < N; i += 200) { await s.ensureRange(i, i + 199); }
out.serverPagingMs = Math.round(performance.now() - a);
out.serverBlocks = ds.calls.length;
out.serverBlocksHeld = s.cacheStats().blocks;
out.heapMb = Math.round(process.memoryUsage().heapUsed / 1048576);
console.log(JSON.stringify(out));
