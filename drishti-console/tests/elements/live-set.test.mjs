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
/* drishti-grid's live mode, the model without a page: rows merged by key and version (an older or equal version never replaces a newer one,
   a row outside the viewer's window is not added), the grid's rows and columns from a declaration, the page window, the edit's items and
   form schema (a field the server did not send is not drawn; the key is never edited), the Pivot's layout and address, the links of a row,
   the masked paths of a record, and the HTTP calls' addresses. The behaviours on a real page are tests/test_live_grid_browser.py. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';

const spec = {
  id: 'trade', kind: 'trade', key: 'TRADE_ID', version: 'TRADE_VERSION', groupBy: [], subtotals: ['QUANTITY'], reason: 'required',
  columns: [{ field: 'TRADE_ID', type: 'id' }, { field: 'TRADE_VERSION', type: 'number' }, { field: 'PORTFOLIO' }, { field: 'QUANTITY', type: 'amount', format: 'amount0' },
    { field: 'PRICE', type: 'number', format: 'number4' }, { field: 'TAGS', type: 'list' }, { field: 'TRADE_STATUS', type: 'status' }],
  fields: [{ id: 'trade_id', label: 'Trade', type: 'string', bind: 'TRADE_ID' }, { id: 'price', label: 'Price', type: 'number', bind: 'PRICE', min: 0 },
    { id: 'status', label: 'Status', type: 'enum', bind: 'TRADE_STATUS', options: [{ value: 'NEW', label: 'NEW' }, { value: 'AMENDED', label: 'AMENDED' }] }]
};
const server = (id, version, doc) => ({ id: id, version: version, seq: 1, doc: Object.assign({ TRADE_ID: id, TRADE_VERSION: version }, doc || {}) });

test('rows are held by key and merged by version', () => {
  const rows = new G.LiveRows();
  const grid = rows.load([server('T-1', 1, { PRICE: 1 }), server('T-2', 1)], 2, 5);
  assert.equal(grid.length, 2);
  assert.equal(grid[0][G.ROW_KEY], 'T-1');
  assert.equal(rows.seq, 5);
  const tx = rows.merge({ upserts: [server('T-1', 2, { PRICE: 2 }), server('T-3', 1)], removes: ['T-2'], total: 2, seq: 6 });
  assert.deepEqual([tx.add.length, tx.update.length, tx.remove.length, tx.stale], [1, 1, 1, 0]);
  assert.equal(rows.byId.get('T-1').PRICE, 2);
  const again = rows.merge({ upserts: [server('T-1', 2, { PRICE: 99 }), server('T-1', 1, { PRICE: 0 })] });      // a replayed and an older version
  assert.deepEqual([again.update.length, again.stale], [0, 2]);
  assert.equal(rows.byId.get('T-1').PRICE, 2);
  assert.equal(rows.merge({ removes: ['T-9'] }).remove.length, 0);                                               // not held: nothing to remove
});

test('a viewer holding a window only adds the rows it holds', () => {
  const rows = new G.LiveRows();
  rows.load([server('T-1', 1)], 1000, 1);
  const tx = rows.merge({ upserts: [server('T-1', 2), server('T-500', 1)], total: 1000 }, true);
  assert.deepEqual([tx.add.length, tx.update.length], [0, 1]);
  assert.equal(rows.total, 1000);
});

test('the page window names the rows in view and a margin, or everything when the set fits', () => {
  const ids = Array.from({ length: 2000 }, (_, i) => 'T-' + i);
  assert.equal(G.windowIds(ids, 0, 20, false), null);
  const w = G.windowIds(ids, 1000, 1020, true);
  assert.equal(w[0], 'T-' + (1000 - G.WINDOW_ROWS / 2));
  assert.equal(w[w.length - 1], 'T-' + (1020 + G.WINDOW_ROWS / 2));
  assert.deepEqual(G.windowIds(ids, 0, 5, true)[0], 'T-0');
});

test('the declaration becomes the grid columns: the key pinned, a list joined, subtotals summed, an unnamed format by its decimals', () => {
  const cols = G.gridColumns(spec), by = Object.fromEntries(cols.map((c) => [c.id, c]));
  assert.equal(by.TRADE_ID.pinned, 'left');
  assert.equal(by.QUANTITY.aggFunc, 'sum');
  assert.equal(by.PRICE.aggFunc, undefined);
  assert.equal(by.QUANTITY.format, 'amount0');
  assert.deepEqual(by.PRICE.format, { decimals: 4 });
  assert.equal(by.TAGS.valueGetter({ TAGS: ['A', 'B'] }), 'A, B');
  assert.equal(by.TRADE_STATUS.cellClass('AMENDED'), 'dg-live-st dg-live-st-amended');
});

test('an edit lists only what changed, by the field the record binds; the form never offers the key for editing and asks for the reason', () => {
  const record = { TRADE_ID: 'T-1', PRICE: 1.05, TRADE_STATUS: 'NEW' };
  assert.deepEqual(G.editItems(record, { trade_id: 'T-1', price: 1.5, status: 'NEW', reason: 'x' }, spec.fields), [{ field: 'PRICE', from: 1.05, to: 1.5 }]);
  assert.deepEqual(G.editItems(record, { price: 1.05 }, spec.fields), []);
  const s = G.editSchema(spec, record);
  assert.equal(s.schema.properties.trade_id.readOnly, true);
  assert.deepEqual(s.schema.properties.status.enum, ['NEW', 'AMENDED']);
  assert.equal(s.schema.properties.price.minimum, 0);
  assert.ok(s.schema.required.indexOf('reason') >= 0);
  assert.equal(s.value.price, 1.05);
  const readOnly = G.editSchema(Object.assign({}, spec, { fields: undefined, reason: 'optional' }), record);          // the server sent no fields: only the reason is left
  assert.deepEqual(Object.keys(readOnly.schema.properties), ['reason']);
});

test('the Pivot gets the kind, the filters as members, the grouping and the numbers (not the version), and live when the kind is live', () => {
  const l = G.pivotLayout(spec, { groupBy: ['PORTFOLIO'], filters: { PORTFOLIO: ['BOOK-A', 'BOOK-B'], COUNTERPARTY: [] } });
  assert.equal(l.source, 'trade');
  assert.deepEqual(l.view.rows, ['PORTFOLIO']);
  assert.deepEqual(l.view.filters, { PORTFOLIO: { in: ['BOOK-A', 'BOOK-B'] } });
  assert.deepEqual(l.view.values.map((v) => v.measure), ['sum(QUANTITY)', 'sum(PRICE)']);
  const href = G.pivotHref(l, true);
  assert.match(href, /^\/pivot\?source=trade&lay=/);
  assert.match(href, /&live=1$/);
  assert.equal(G.pivotHref(l, false).indexOf('live'), -1);
  assert.deepEqual(G.pivotLayout(spec, { groupBy: [] }).view.rows, []);
});

test('a link takes the row values, encoded; a record\'s masked fields are found', () => {
  assert.deepEqual(G.linkFor({ label: 'VaR', href: '/var?scope=book:{PORTFOLIO}' }, { PORTFOLIO: 'BOOK A&B' }), { label: 'VaR', href: '/var?scope=book:BOOK%20A%26B' });
  assert.deepEqual(G.maskedPaths({ A: 1, B: { C: '•••' }, D: ['x', '•••'] }), ['B.C', 'D[1]']);
  assert.deepEqual(G.changedFields({ a: 1, b: 2 }, { a: 1, b: 3, c: 4 }).sort(), ['b', 'c']);
});

test('a change\'s state in words, and which changes are still open', () => {
  assert.equal(G.stateWords('awaiting', 2), 'waiting for 2 approvers (four eyes)');
  assert.equal(G.stateWords('queued'), 'on its way to the source');
  assert.equal(G.isPending({ state: 'awaiting' }), true);
  assert.equal(G.isPending({ state: 'applied' }), false);
  assert.equal(G.itemsText({ items: [{ path: 'PRICE', from: 1, to: 2 }, { path: 'TAGS', from: null, to: ['A'] }] }), 'PRICE 1 → 2, TAGS — → ["A"]');
});

test('the HTTP api asks the console\'s /api/live addresses and rejects with the server\'s words', async () => {
  const calls = [];
  const fetch = (url, o) => { calls.push([o.method, url, o.body]); return Promise.resolve({ ok: !/edit/.test(url), status: /edit/.test(url) ? 409 : 200, json: () => Promise.resolve(/edit/.test(url) ? { code: 'DRS-1', detail: 'changed by feed' } : {}) }); };
  const api = G.httpLiveApi({ id: 'trade', fetch: fetch });
  await api.rows({ limit: 10, sort: 'TRADE_ID', descending: '' });
  await api.row('T/1');
  await api.window('s-1', { all: true });
  await api.history('trade', 'T-1');
  await assert.rejects(api.edit({ entity: 'T-1' }), (e) => e.status === 409 && e.code === 'DRS-1' && /changed by feed/.test(e.message));
  assert.deepEqual(calls.map((c) => c[1]), ['/api/live/trade/rows?limit=10&sort=TRADE_ID', '/api/live/trade/rows/T%2F1', '/api/live/trade/stream/s-1/window',
    '/api/changes?kind=trade&entity=T-1&limit=50', '/api/live/trade/edit']);
  assert.equal(calls[2][0], 'POST');
});
