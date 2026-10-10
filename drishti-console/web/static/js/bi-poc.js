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
// RUPAKA PHASE 0 PROOF OF CONCEPT (docs/architecture/RUPAKA_POC.md). Throw-away. Loaded by /bi/poc only.
// Perspective (vendored, loaded from this page on demand) shows the live rows; DuckDB-Wasm (vendored, loaded on first use)
// re-slices a masked Apache Arrow extract the server sent. No CDN, no inline script, no blob worker.
const root = document.getElementById('poc');
const PSP = root.dataset.perspective;
const DUCK = root.dataset.duckdb;
const $ = (id) => document.getElementById(id);
const stats = { applied: 0, ups: 0, frames: 0, fps: 0, dropped: 0, batches: 0, pspMs: null, duckMs: null, feedRows: 0, synRows: 0, errors: [] };
const timings = {};
let table = null;
let duck = null;
let pubTimer = 0;
let inflight = 0;

const SCHEMA = { tradeId: 'string', desk: 'string', currency: 'string', productType: 'string', side: 'string', trader: 'string',
                 notional: 'float', mtm: 'float', generation: 'integer', at: 'string' };
const DESKS = ['DESK-FI', 'DESK-FX', 'DESK-EQ', 'DESK-CMD', 'DESK-RATES', 'DESK-CREDIT'];
const CCYS = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD', 'SGD'];
const PRODUCTS = ['IRS_FIXFLOAT', 'FX_FORWARD', 'FX_OPTION', 'GOVT_BOND', 'CORP_BOND', 'CDS_SINGLE', 'EQ_OPTION_LISTED', 'STIR_FUTURE', 'REPO', 'CMS_SWAP'];
const SYNTHETIC_IDS = 2000;

function fail(where, e) {
  const text = `${where}: ${e && e.message ? e.message : e}`;
  stats.errors.push(text);
  $('pocState').textContent = 'error';
  console.error(text, e);
}

function theme() {
  const t = document.documentElement.dataset.theme || '';
  return /light|paper/.test(t) ? 'Pro Light' : 'Pro Dark';
}

// ---- a. Perspective -------------------------------------------------------------------------------------------------
const stage = (name) => { document.body.dataset.pspStage = name; };

async function startPerspective() {
  const t0 = performance.now();
  stage('viewer');
  await import(`${PSP}/perspective-viewer/cdn/perspective-viewer.js`);
  stage('plugins');
  await Promise.all([import(`${PSP}/datagrid/cdn/perspective-viewer-datagrid.js`), import(`${PSP}/d3fc/cdn/perspective-viewer-d3fc.js`)]);
  stage('engine');
  const perspective = (await import(`${PSP}/perspective/cdn/perspective.js`)).default;
  // the engine's worker is a script of this origin (worker-src 'self'), not the library's default blob worker
  const worker = new Worker(`${PSP}/perspective/cdn/perspective-server.worker.js`, { type: 'module' });
  const client = await perspective.worker(Promise.resolve(worker));
  stage('table');
  table = await client.table(SCHEMA, { index: 'tradeId' });
  const grid = $('pocGrid');
  const pivot = $('pocPivot');
  stage('styles');
  // the viewer looks its theme up in the page's stylesheets when it is restored: be sure they are parsed first (a cached page races them)
  await Promise.all([...document.querySelectorAll('link[rel=stylesheet]')].map((l) => (l.sheet ? null : new Promise((r) => { l.addEventListener('load', r); l.addEventListener('error', r); }))));
  stage('load');
  await Promise.all([grid.load(table), pivot.load(table)]);
  stage('restore');
  await grid.restore({ plugin: 'Datagrid', settings: false, theme: theme(),
    columns: ['tradeId', 'desk', 'currency', 'productType', 'side', 'trader', 'notional', 'mtm', 'generation'] });
  await pivot.restore({ plugin: 'Datagrid', settings: false, theme: theme(), group_by: ['desk'], split_by: ['side'],
    columns: ['mtm'], aggregates: { mtm: 'sum' } });
  stats.pspMs = Math.round(performance.now() - t0);
  $('stPspMs').textContent = String(stats.pspMs);
  document.body.dataset.pspReady = 'true';
}

function apply(rows, source) {
  if (!table || !rows.length) return Promise.resolve();
  inflight += 1;
  stats.batches += 1;
  return table.update(rows).then(() => {
    stats.applied += rows.length;
    if (source === 'feed') stats.feedRows += rows.length; else stats.synRows += rows.length;
  }).catch((e) => fail('update', e)).finally(() => { inflight -= 1; });
}

function startFeed() {
  // the server's rows come over the console's one live channel (channel.js), as views, alerts and monitors do: no stream of this page's own
  const take = (rows) => { try { apply(rows, 'feed'); } catch (x) { fail('feed', x); } };
  window.DrishtiChannel.subscribe('poc:rows', {
    view: (rows) => { $('feedState').textContent = 'feed: live'; take(rows); },
    row: take,
    gone: (e) => { $('feedState').textContent = 'feed: ' + (e && e.detail ? e.detail : 'ended'); },
    error: () => { $('feedState').textContent = 'feed: reconnecting'; },
  });
}

function synthetic(n) {
  const mtm = Math.round((Math.random() - 0.5) * 2000000);
  return { tradeId: 'SYN-' + String(n).padStart(4, '0'), desk: DESKS[n % DESKS.length], currency: CCYS[n % CCYS.length],
           productType: PRODUCTS[n % PRODUCTS.length], side: n % 2 ? 'BUY' : 'SELL', trader: 'TRDR-' + (n % 12),
           notional: (1 + (n % 500)) * 100000, mtm, generation: stats.batches, at: new Date().toISOString() };
}

function setRate(rate) {
  clearInterval(pubTimer);
  pubTimer = 0;
  if (!rate) return;
  const everyMs = 20;
  const per = Math.max(1, Math.round(rate * everyMs / 1000));
  pubTimer = setInterval(() => {
    if (inflight > 4) { stats.dropped += per; return; }       // the engine is behind: count what could not be offered, never queue without bound
    const rows = [];
    for (let i = 0; i < per; i++) rows.push(synthetic(Math.floor(Math.random() * SYNTHETIC_IDS)));
    apply(rows, 'synthetic');
  }, everyMs);
}

// ---- measurements --------------------------------------------------------------------------------------------------
function measure() {
  let last = performance.now();
  let lastApplied = 0;
  const frame = () => { stats.frames += 1; requestAnimationFrame(frame); };
  requestAnimationFrame(frame);
  let lastFrames = 0;
  setInterval(async () => {
    const now = performance.now();
    const dt = (now - last) / 1000;
    stats.ups = Math.round((stats.applied - lastApplied) / dt);
    stats.fps = Math.round((stats.frames - lastFrames) / dt);
    last = now; lastApplied = stats.applied; lastFrames = stats.frames;
    $('stUps').textContent = String(stats.ups);
    $('stFps').textContent = String(stats.fps);
    $('stHeap').textContent = performance.memory ? (performance.memory.usedJSHeapSize / 1048576).toFixed(1) : 'n/a';
    if (table) { try { stats.rows = await table.size(); $('stRows').textContent = String(stats.rows); } catch (e) { /* closing */ } }
  }, 1000);
}

// ---- b. DuckDB-Wasm ------------------------------------------------------------------------------------------------
async function startDuck() {
  if (duck) return duck;
  if (!DUCK) throw new Error('DuckDB-Wasm is not installed (tools/fetch-duckdb-wasm.sh)');
  $('duckState').textContent = 'loading';
  const t0 = performance.now();
  const mod = await import(`${DUCK}/duckdb-browser.mjs`);
  const worker = new Worker(`${DUCK}/duckdb-browser-eh.worker.js`);
  const db = new mod.AsyncDuckDB(new mod.VoidLogger(), worker);
  await db.instantiate(`${DUCK}/duckdb-eh.wasm`);
  const conn = await db.connect();
  stats.duckMs = Math.round(performance.now() - t0);
  $('stDuckMs').textContent = String(stats.duckMs);
  $('duckState').textContent = 'ready';
  document.body.dataset.duckReady = 'true';
  duck = { db, conn };
  return duck;
}

async function arrowFrom(body) {
  const t0 = performance.now();
  const r = await fetch('/bi/poc/query', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), credentials: 'same-origin' });
  if (!r.ok) throw new Error(`${r.status} ${(await r.text()).slice(0, 200)}`);
  const bytes = new Uint8Array(await r.arrayBuffer());
  return { bytes, ms: performance.now() - t0, masked: r.headers.get('x-poc-masked') || '', timing: r.headers.get('server-timing') || '' };
}

async function load(name, bytes) {
  const { conn } = await startDuck();
  await conn.query(`DROP TABLE IF EXISTS ${name}`);
  const t0 = performance.now();
  await conn.insertArrowFromIPCStream(bytes, { name, create: true });
  return performance.now() - t0;
}

async function rowsOf(sql, ...params) {
  const { conn } = await startDuck();
  const t0 = performance.now();
  let result;
  if (params.length) {
    const st = await conn.prepare(sql);
    try { result = await st.query(...params); } finally { await st.close(); }
  } else {
    result = await conn.query(sql);
  }
  const rows = result.toArray().map((r) => r.toJSON());
  return { rows, ms: performance.now() - t0 };
}

function fill(tableEl, rows, cols) {
  const body = tableEl.tBodies[0];
  body.replaceChildren();
  for (const r of rows) {
    const tr = document.createElement('tr');
    for (const c of cols) {
      const td = document.createElement('td');
      const v = r[c];
      td.textContent = typeof v === 'bigint' ? v.toString() : typeof v === 'number' ? v.toLocaleString('en-US', { maximumFractionDigits: 0 }) : String(v ?? '');
      tr.appendChild(td);
    }
    body.appendChild(tr);
  }
}

async function fetchExtract() {
  $('duckState').textContent = 'fetching';
  const a = await arrowFrom({ groupBy: ['desk', 'currency', 'productType', 'side'], layout: 'typed' });
  timings.fetchMs = Math.round(a.ms); timings.arrowBytes = a.bytes.length; timings.server = a.timing;
  timings.loadMs = Math.round(await load('extract', a.bytes));
  const ccy = await rowsOf('SELECT DISTINCT currency FROM extract ORDER BY 1');
  const sel = $('selFilter');
  sel.replaceChildren(new Option('(all)', ''));
  for (const r of ccy.rows) sel.appendChild(new Option(r.currency, r.currency));
  $('btnSlice').disabled = false;
  $('duckState').textContent = 'extract loaded';
  timings.extractRows = Number((await rowsOf('SELECT count(*) AS n FROM extract')).rows[0].n);
  document.body.dataset.extract = 'true';
  await slice();
}

async function slice() {
  const group = $('selGroup').value;
  if (!['desk', 'currency', 'productType', 'side'].includes(group)) return;        // the select is the whitelist
  const filter = $('selFilter').value;
  const sql = `SELECT ${group} AS grp, sum(notional) AS notional, sum(mtm) AS mtm, sum(trades) AS trades FROM extract` +
              `${filter ? ' WHERE currency = ?' : ''} GROUP BY ${group} ORDER BY ${group}`;
  const t0 = performance.now();
  const res = filter ? await rowsOf(sql, filter) : await rowsOf(sql);
  const total = performance.now() - t0;
  fill($('sliceTable'), res.rows, ['grp', 'notional', 'mtm', 'trades']);
  timings.sliceMs = Math.round(res.ms * 100) / 100;
  timings.sliceTotalMs = Math.round(total * 100) / 100;
  timings.sliceRows = res.rows.length;
  $('sliceTimes').textContent = `fetch ${timings.fetchMs} ms · load ${timings.loadMs} ms · ${timings.extractRows} rows · re-slice ${timings.sliceMs} ms → ${res.rows.length} groups`;
  document.body.dataset.sliced = String(res.rows.length);
}

// ---- c. masks ------------------------------------------------------------------------------------------------------
async function masks() {
  const t0 = performance.now();
  const [mine, theirs] = await Promise.all([arrowFrom({ groupBy: ['trader'] }), arrowFrom({ groupBy: ['trader'], preview: 'masked' })]);
  await load('as_you', mine.bytes);
  await load('as_masked', theirs.bytes);
  const a = await rowsOf('SELECT trader, trades FROM as_you ORDER BY trader');
  const b = await rowsOf('SELECT trader, trades FROM as_masked ORDER BY trader');
  fill($('maskAsYou'), a.rows, ['trader', 'trades']);
  fill($('maskPreview'), b.rows, ['trader', 'trades']);
  $('maskTimes').textContent = `masked columns: ${theirs.masked || 'none'} · ${Math.round(performance.now() - t0)} ms`;
  document.body.dataset.masks = b.rows.map((r) => r.trader).join('|');
}

// ---- wiring ----------------------------------------------------------------------------------------------------------
function guarded(fn, where) {
  return () => fn().catch((e) => fail(where, e));
}

$('btnFetch').addEventListener('click', guarded(fetchExtract, 'extract'));
$('btnSlice').addEventListener('click', guarded(slice, 'slice'));
$('selGroup').addEventListener('change', guarded(slice, 'slice'));
$('selFilter').addEventListener('change', guarded(slice, 'slice'));
$('btnMasks').addEventListener('click', guarded(masks, 'masks'));
$('pubRate').addEventListener('change', (e) => setRate(Number(e.target.value)));
$('pubClear').addEventListener('click', () => { if (table) table.clear(); });

// the hooks the browser tests and the measurements use
window.drishtiPoc = { stats, timings, setRate, fetchExtract, slice, masks, startDuck, apply, synthetic,
  size: () => (table ? table.size() : Promise.resolve(0)) };

measure();
startPerspective().then(() => { $('pocState').textContent = 'ready'; startFeed(); }).catch((e) => fail('perspective', e));
