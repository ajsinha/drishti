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
/* drishti-data under Node: the back-off and the heartbeat, backpressure (a grid never queues more than it draws, the last state of each
   row reaches it), the live client over stand-in EventSource and WebSocket classes (reconnect with back-off, resume from the last event
   id, heartbeat, close), the data sources (in memory, REST, readAll for a chart) and the Arrow client against the vendored Arrow JS
   (columns read without a copy, types, JSON the same, Flight over HTTP when offered). */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as D from '../../web/static/elements/drishti-data/src/drishti_index.js';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';

const require = createRequire(import.meta.url);
const Arrow = require('../../web/static/vendor/apache-arrow/21.1.0/Arrow.es2015.min.js');
const tick = (ms) => new Promise((r) => setTimeout(r, ms || 0));

test('back-off: doubling up to the cap, jittered between half the base and the cap, reset; the heartbeat fires once per silence', () => {
  const b = new D.Backoff({ baseMs: 100, maxMs: 1000, jitter: false });
  assert.deepEqual([b.next(), b.next(), b.next(), b.next(), b.next()], [100, 200, 400, 800, 1000]);
  b.reset();
  assert.equal(b.next(), 100);
  const j = new D.Backoff({ baseMs: 100, maxMs: 1000, random: () => 0 });
  assert.equal(j.next(), 50);
  let fired = 0, pending = null;
  const hb = new D.Heartbeat({ timeoutMs: 50, onTimeout: () => fired++, setTimeout: (f) => { pending = f; return 1; }, clearTimeout: () => { pending = null; } });
  hb.beat();
  pending();
  assert.equal(fired, 1);
  hb.beat(); hb.stop();
  assert.equal(pending, null);
});

test('backpressure: one batch in the grid at a time, the rest folded by row id; adds, updates and removes fold right; rows replace', async () => {
  const applied = [];
  let release = null;
  const target = { applyTransactionAsync: (tx) => { applied.push(JSON.parse(JSON.stringify(tx))); return new Promise((r) => { release = r; }); }, setRows: (rows) => applied.push({ rows: rows.length }), dataChanged: (n) => applied.push({ notice: n.purge }) };
  const sink = new D.TransactionSink(target, { getRowId: (r) => r.id });
  sink.push({ type: 'transaction', update: [{ id: 'A', v: 1 }] });
  assert.equal(applied.length, 1);
  for (let i = 2; i <= 1000; i++) { sink.push({ type: 'transaction', update: [{ id: 'A', v: i }, { id: 'B', v: i }] }); }
  sink.push({ type: 'transaction', add: [{ id: 'C', v: 1 }] });
  sink.push({ type: 'transaction', remove: ['C'] });                          // added and removed while waiting: nothing
  sink.push({ type: 'transaction', remove: ['B'] });                          // updated then removed: a remove
  sink.push({ type: 'dataChanged', purge: true });
  assert.equal(applied.length, 1);                                            // the grid is still busy
  release();
  await tick(5);
  assert.deepEqual(applied[1], { add: [], update: [{ id: 'A', v: 1000 }], remove: ['B'] });
  release();
  await tick(5);
  assert.deepEqual(applied[2], { notice: true });                             // the notice after the rows
  assert.equal(sink.stats.received, 2002);
  assert.ok(sink.stats.coalesced >= 1998);
  assert.equal(sink.stats.batches, 2);
  sink.push({ type: 'rows', rows: [{ id: 'Z' }] });
  assert.deepEqual(applied[3], { rows: 1 });
  assert.throws(() => new D.TransactionSink({}), /applyTransactionAsync/);
});

test('backpressure into a real grid model: a burst of ticks arrives as the last value of each row', async () => {
  const g = G.createGridModel({ columns: [{ field: 'id' }, { field: 'px', type: 'number' }], rows: [{ id: 'A', px: 0 }, { id: 'B', px: 0 }], live: { asyncWaitMs: 2 } });
  const feed = { subs: [], subscribe(fn) { this.subs.push(fn); return () => {}; }, push(m) { this.subs.forEach((f) => f(m)); } };
  const p = D.pipeToGrid(feed, g, { getRowId: (r) => r.id });
  for (let i = 1; i <= 5000; i++) { feed.push({ type: 'transaction', update: [{ id: i % 2 ? 'A' : 'B', px: i }] }); }
  await p.sink.drained();
  assert.equal(g.rowModel.getRowById('A').px, 4999);
  assert.equal(g.rowModel.getRowById('B').px, 5000);
  assert.ok(p.sink.stats.batches <= 3, 'batches ' + p.sink.stats.batches);
});

class FakeES extends EventTarget {
  constructor(url) { super(); this.url = url; this.readyState = 0; FakeES.all.push(this); }
  open() { this.readyState = 1; this.dispatchEvent(new Event('open')); }
  push(data, id) { const e = new Event('message'); e.data = JSON.stringify(data); e.lastEventId = id === undefined ? '' : String(id); this.dispatchEvent(e); }
  fail() { this.readyState = 2; this.dispatchEvent(new Event('error')); }
  close() { this.readyState = 2; this.closed = true; }
}
FakeES.all = [];

test('the live client over SSE: messages, ids kept, a drop reconnects after the back-off and resumes from the last id; close stops it', async () => {
  FakeES.all = [];
  const c = new D.LiveClient({ url: '/live/trades?desk=FX', EventSource: FakeES, heartbeatMs: 0, backoff: { baseMs: 10, jitter: false }, window: {} });
  const got = [], states = [];
  c.on('state', (s) => states.push(s.state));
  const off = c.subscribe((m) => got.push(m));
  FakeES.all[0].open();
  FakeES.all[0].push({ type: 'transaction', update: [{ id: 'A' }] }, 41);
  FakeES.all[0].push({ type: 'heartbeat' });
  FakeES.all[0].push({ type: 'transaction', update: [{ id: 'B' }] }, 42);
  assert.equal(got.length, 2);                                                // the heartbeat is not passed on
  assert.equal(c.lastEventId, '42');
  FakeES.all[0].fail();
  assert.equal(c.state, 'reconnecting');
  await tick(20);
  assert.equal(FakeES.all.length, 2);
  assert.equal(FakeES.all[1].url, '/live/trades?desk=FX&lastEventId=42');
  FakeES.all[1].open();
  assert.equal(c.state, 'open');
  assert.equal(c.stats.reconnects, 1);
  off();
  assert.equal(c.state, 'closed');
  assert.equal(FakeES.all[1].closed, true);
  await tick(20);
  assert.equal(FakeES.all.length, 2);                                         // closed: no more attempts
  assert.deepEqual(states.slice(0, 5), ['connecting', 'open', 'reconnecting', 'connecting', 'open']);
});

test('the live client: a silent connection is dropped after the heartbeat; a WebSocket resumes with a message and pings', async () => {
  FakeES.all = [];
  const c = new D.LiveClient({ url: '/live', EventSource: FakeES, heartbeatMs: 30, backoff: { baseMs: 5, jitter: false }, window: {} });
  c.subscribe(() => {});
  FakeES.all[0].open();
  await tick(60);
  assert.ok(FakeES.all.length >= 2, 'reconnected after silence');
  c.destroy();
  class FakeWS extends EventTarget { constructor(url) { super(); this.url = url; this.sent = []; FakeWS.all.push(this); } send(t) { this.sent.push(JSON.parse(t)); } close() { this.closed = true; } }
  FakeWS.all = [];
  const w = new D.LiveClient({ url: 'wss://x/live', WebSocket: FakeWS, heartbeatMs: 0, pingMs: 10, backoff: { baseMs: 5, jitter: false }, window: {} });
  assert.equal(w.transport, 'websocket');
  const got = [];
  w.subscribe((m) => got.push(m));
  FakeWS.all[0].dispatchEvent(new Event('open'));
  const m = new Event('message'); m.data = JSON.stringify({ type: 'transaction', seq: 7, update: [] });
  FakeWS.all[0].dispatchEvent(m);
  await tick(25);
  assert.ok(FakeWS.all[0].sent.some((x) => x.type === 'ping'));
  FakeWS.all[0].dispatchEvent(Object.assign(new Event('close'), { code: 1006 }));
  await tick(15);
  FakeWS.all[1].dispatchEvent(new Event('open'));
  assert.deepEqual(FakeWS.all[1].sent[0], { type: 'resume', lastEventId: '7' });
  w.destroy();
  assert.throws(() => new D.LiveClient({}), /url/);
  assert.throws(() => new D.LiveClient({ url: '/x', transport: 'carrier-pigeon' }), /no live transport/);
});

test('the live client over fetch: SSE read with fetch so a bearer travels, the address asked at every connect, the event name in each message, a 401 remembered for the next headers', async () => {
  const P = D.parseSse('event: frame\ndata: {"seq":1}\nid: 5\n\n: keep-alive\n\ndata: {"a":\ndata: 2}\n\nevent: vi');
  assert.deepEqual(P.events.map((e) => [e.event, e.data, e.id, e.comment]), [['frame', '{"seq":1}', '5', false], ['message', '', null, true], ['message', '{"a":\n2}', null, false]]);
  assert.equal(P.rest, 'event: vi');
  const calls = [];
  let answer = 0;
  const enc = new TextEncoder();
  const fakeFetch = (url, init) => {
    calls.push({ url, auth: init.headers.Authorization, accept: init.headers.Accept });
    if (answer++ === 0) { return Promise.resolve({ ok: false, status: 401 }); }
    const chunks = ['event: view\ndata: {"ref":{"kind":"trade"}}\nid: 9\n\nevent: fr', 'ame\ndata: {"seq":2}\n\nevent: heartbeat\ndata: {}\n\n'];
    let i = 0;
    return Promise.resolve({ ok: true, status: 200, body: { getReader: () => ({ read: () => (i < chunks.length ? Promise.resolve({ value: enc.encode(chunks[i++]), done: false }) : new Promise(() => {})) }) } });
  };
  let keys = 'a';
  const seen = [];
  const c = new D.LiveClient({ url: () => '/channel?s=' + keys, transport: 'fetch', fetch: fakeFetch, events: ['view', 'frame'], eventField: '$event',
    headers: (info) => ({ Authorization: 'Bearer ' + (info.status === 401 ? 'fresh' : 'stale') }), heartbeatMs: 0, backoff: { baseMs: 5, jitter: false }, window: {} });
  const errors = [];
  c.on('error', (e) => { if (e.status) { errors.push(e.status); } });
  c.subscribe((m) => seen.push(m));
  await tick(2);
  assert.deepEqual(errors, [401]);
  keys = 'a&s=b';
  await tick(25);
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map((x) => x.auth), ['Bearer stale', 'Bearer fresh']);
  assert.equal(calls[1].url, '/channel?s=a&s=b');
  assert.equal(calls[1].accept, 'text/event-stream');
  assert.deepEqual(seen, [{ ref: { kind: 'trade' }, $event: 'view' }, { seq: 2, $event: 'frame' }]);
  assert.equal(c.lastEventId, '2');
  assert.equal(c.state, 'open');
  c.destroy();
  assert.equal(c.state, 'closed');
});

test('data sources: in memory (filter, sort, groups, windows), REST by POST and by GET, readAll for a chart, a live source', async () => {
  const rows = Array.from({ length: 250 }, (_, i) => ({ id: 'T' + i, desk: ['Rates', 'FX', 'Credit'][i % 3], mtm: (i % 7) - 3 }));
  const s = D.memorySource(rows);
  const a = await s.getRows({ startRow: 0, endRow: 10, filterModel: { desk: { type: 'set', values: ['FX'] } }, sortModel: [{ colId: 'mtm', sort: 'desc' }] });
  assert.equal(a.rowCount, 83);
  assert.equal(a.rows[0].mtm, 3);
  const g = await s.getRows({ rowGroupCols: ['desk'], valueCols: [{ colId: 'mtm', aggFunc: 'sum' }] });
  assert.deepEqual(g.rows.map((x) => x.desk), ['Credit', 'FX', 'Rates']);
  assert.equal(g.rows.reduce((t, x) => t + x.childCount, 0), 250);
  const lvl = await s.getRows({ rowGroupCols: ['desk'], groupKeys: ['FX'], startRow: 0, endRow: 5 });
  assert.equal(lvl.rowCount, 83);
  const all = await D.readAll(s, { filterModel: { desk: { type: 'set', values: ['Rates'] } } }, { pageSize: 30 });
  assert.equal(all.rows.length, 84);
  const calls = [];
  const fakeFetch = (url, init) => { calls.push({ url: url, init: init }); return Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ items: [{ id: 1 }], total: 99 })) }); };
  const r = await D.restSource('/api/rows', { fetch: fakeFetch, method: 'GET' }).getRows({ startRow: 20, endRow: 40, sortModel: [{ colId: 'mtm', sort: 'desc' }], quickFilter: 'fx' });
  assert.deepEqual(r, { rows: [{ id: 1 }], rowCount: 99 });
  assert.equal(calls[0].url, '/api/rows?offset=20&limit=20&sort=mtm%3Adesc&q=fx');
  await D.serverSideSource('/api/pivot/rows', { fetch: fakeFetch }).getRows({ startRow: 0, endRow: 100, rowGroupCols: ['desk'] });
  assert.equal(JSON.parse(calls[1].init.body).rowGroupCols[0], 'desk');
  const failing = (url) => Promise.resolve({ ok: false, status: 403, statusText: 'Forbidden', text: () => Promise.resolve('{"code":"DRS-1403","detail":"not yours"}') });
  await assert.rejects(D.restSource('/x', { fetch: failing }).getRows({}), /DRS-1403 not yours/);
  const live = D.liveSource(s, { subscribe: () => () => {} });
  assert.equal(live.capabilities.live, true);
  const grid = G.createGridModel({ columns: [{ field: 'id' }, { field: 'desk' }, { field: 'mtm', type: 'number' }], datasource: s, blockSize: 50 });
  await grid.rowModel.ensureRange(0, 60);
  assert.equal(grid.rowCount(), 250);                                         // a source is the grid's datasource as it is
});

function arrowBytes() {
  const t = Arrow.tableFromArrays({ ID: ['T1', 'T2', 'T3'], DESK: Arrow.vectorFromArray(['Rates', 'FX', 'Rates'], new Arrow.Dictionary(new Arrow.Utf8(), new Arrow.Int32())),
    MTM: Float64Array.from([1.5, -2, 3]), N: Int32Array.from([1, 2, 3]), DAY: Arrow.vectorFromArray([new Date(Date.UTC(2026, 10, 6)), null, new Date(Date.UTC(2026, 10, 5))], new Arrow.DateDay()),
    BIG: Arrow.vectorFromArray([1n, 2n ** 60n, null], new Arrow.Int64()), OK: [true, false, null] });
  return Arrow.tableToIPC(t, 'stream');
}

test('Arrow: columns without a copy, types for a grid, row views, plain copies, JSON the same, Flight over HTTP when offered', async () => {
  const fr = D.fromArrow(arrowBytes(), Arrow);
  assert.equal(fr.format, 'arrow');
  assert.equal(fr.length, 3);
  assert.ok(fr.column('MTM') instanceof Float64Array);
  assert.equal(fr.cols.MTM.copied, false);                                    // the Arrow buffer itself
  assert.deepEqual(fr.fields().map((f) => f.type), ['text', 'text', 'number', 'number', 'date', 'text', 'boolean']);
  const rows = fr.rows();
  assert.equal(rows[1].DESK, 'FX');
  assert.equal(rows[1].DAY, null);
  assert.equal(rows[1].BIG, '1152921504606846976');                           // past 2^53: exact text
  assert.equal(rows[0].BIG, 1);
  assert.deepEqual(Object.keys(rows[0]), ['$i']);                             // a view: one number, the values read when asked
  assert.deepEqual(rows[0].toObject(), { ID: 'T1', DESK: 'Rates', MTM: 1.5, N: 1, DAY: '2026-11-06', BIG: 1, OK: true });
  assert.equal(JSON.stringify(rows[2]), JSON.stringify(rows[2].toObject()));
  const g = fr.toGrid({ id: 'ID', formats: { MTM: 'amount2' } });
  const grid = G.createGridModel({ columns: g.columns, rows: g.rows, getRowId: g.getRowId });
  grid.setSortModel([{ colId: 'MTM', sort: 'desc' }]);
  assert.equal(grid.cellText(0, 2), '3.00');
  const js = D.fromJson([{ ID: 'T1', MTM: 1.5, DAY: '2026-11-06' }, { ID: 'T2', MTM: null, DAY: null }]);
  assert.deepEqual(js.fields().map((f) => f.type), ['text', 'number', 'date']);
  assert.equal(js.rows()[1].MTM, null);
  const bytes = arrowBytes(), asked = [];
  const response = (body, type) => ({ ok: true, status: 200, headers: { get: () => type }, arrayBuffer: () => Promise.resolve(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)), text: () => Promise.resolve(body), json: () => Promise.resolve(JSON.parse(body)) });
  const fetch = (url, init) => {
    asked.push(url + ' ' + ((init && init.method) || 'GET'));
    if (url === '/caps') { return Promise.resolve(response(JSON.stringify({ flightHttp: { doGet: '/flight/do-get' } }), 'application/json')); }
    if (url === '/nocaps') { return Promise.resolve({ ok: false, json: () => Promise.resolve({}) }); }
    return Promise.resolve(response(bytes, D.ARROW_MEDIA));
  };
  const viaFlight = await new D.ArrowClient({ capabilitiesUrl: '/caps', fetch: fetch, Arrow: Arrow }).read({ ticket: { kind: 'trades' }, url: '/rows' });
  assert.equal(viaFlight.via, 'flight-http');
  assert.equal(viaFlight.length, 3);
  const viaHttp = await new D.ArrowClient({ capabilitiesUrl: '/nocaps', fetch: fetch, Arrow: Arrow }).read({ ticket: { kind: 'trades' }, url: '/rows' });
  assert.equal(viaHttp.via, 'arrow-http');
  assert.deepEqual(asked, ['/caps GET', '/flight/do-get POST', '/nocaps GET', '/rows GET']);
  await assert.rejects(new D.ArrowClient({ flight: false, fetch: fetch }).read({ ticket: {} }), /no Flight over HTTP/);
  const src = D.arrowSource(fr);
  const page = await src.getRows({ startRow: 0, endRow: 2, sortModel: [{ colId: 'MTM', sort: 'asc' }] });
  assert.deepEqual(page.rows.map((r) => r.ID), ['T2', 'T1']);
});

test('the bundle: drishti-data is a CommonJS module under Node with every name', () => {
  const B = require('../../web/static/elements/drishti-data/drishti_data.js');
  assert.equal(B.library, 'drishti-data');
  ['LiveClient', 'TransactionSink', 'pipeToGrid', 'ArrowClient', 'DataFrame', 'memorySource', 'restSource', 'serverSideSource', 'arrowSource', 'liveSource', 'readAll', 'live'].forEach((n) => assert.equal(typeof B[n], 'function', n));
});
