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
/* drishti-core under Node (node --test): formats by the server's rules, dates, theme tokens and contrast, the API client, the bus, the
   keys, roving index arithmetic, the loader's manifest, and the bundle giving the same API as the modules. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';

const require = createRequire(import.meta.url);
const M = '−';

test('numbers round half up on the decimal value, as the server (BigDecimal HALF_UP), never on the binary one', () => {
  assert.equal(C.roundHalfUp(1.005, 2), '1.01');
  assert.equal(C.roundHalfUp(2.5, 0), '3');
  assert.equal(C.roundHalfUp(9.995, 2), '10.00');
  assert.equal(C.roundHalfUp(0.125, 2), '0.13');
  assert.equal(C.plainDigits(1e-7), '0.0000001');
  assert.equal(C.plainDigits(1.5e22), '15000000000000000000000');
});

test('the server named formats: amount, signed, pct, compact, dates; null is empty; unknown names show text', () => {
  assert.equal(C.formatNamed('amount0', 1234567.5), '1,234,568');
  assert.equal(C.formatNamed('amount2', -1234.005), M + '1,234.01');
  assert.equal(C.formatNamed('signed0', 12), '+12');
  assert.equal(C.formatNamed('signed0', -0.4), '0');                     // rounds to zero: no sign
  assert.equal(C.formatNamed('pct2', 0.01234), '1.23%');
  assert.equal(C.formatNamed('compact', 1234567), '1.2m');
  assert.equal(C.formatNamed('compact', -2.5e9), M + '2.5bn');
  assert.equal(C.formatNamed('compact', 999), '999.0');                   // the server's: one decimal below a thousand too
  assert.equal(C.formatNamed('dmy', '2026-11-07'), '07 Nov 2026');
  assert.equal(C.formatNamed('date', '2026-11-07T10:00:00Z'), '2026-11-07T10:00:00Z');
  assert.equal(C.formatNamed('amount0', null), '');
  assert.equal(C.formatNamed('amount0', 'n/a'), 'n/a');
  assert.equal(C.formatNamed('nope', 12), '12');
  assert.equal(C.formatNamed('amount0', C.MASK), C.MASK);
});

test('a pack format defined at run time is used like a bundled one (bp1 from the core pack)', () => {
  assert.equal(C.hasFormat('bp1'), false);
  C.defineFormats({ bp1: { type: 'number', decimals: 1, sign: 'always', suffix: ' bp' } });
  assert.equal(C.formatNamed('bp1', 12.34), '+12.3 bp');
  assert.equal(C.formatBasisPoints(0.0012, { scale: 10000 }), '+12.0 bp');
});

test('number options: grouping, minus, sign, prefix, max decimals, locale, empty', () => {
  assert.equal(C.formatNumber(-1234.5, { decimals: 2 }), M + '1,234.50');
  assert.equal(C.formatNumber(-1234.5, { decimals: 0, minus: '-' }), '-1,235');
  assert.equal(C.formatNumber(1.5, { decimals: 0, maxDecimals: 2 }), '1.5');
  assert.equal(C.formatNumber(1234.5, { decimals: 2, grouping: false }), '1234.50');
  assert.equal(C.formatNumber(1234.5, { decimals: 1, locale: 'de-DE' }), '1.234,5');
  assert.equal(C.formatNumber(null), '—');
  assert.equal(C.formatNumber(NaN, { empty: '' }), '');
  assert.equal(C.formatPercent(0.1234), '12.3%');
  assert.equal(C.formatCurrency(1234.5, 'USD'), '$1,234.50');
  assert.equal(C.formatCurrency(1234.5, 'JPY'), '¥1,235');
  assert.equal(C.formatCurrency(1, 'CHF'), '1.00 CHF');
});

test('compact: the server units, trims, tiers and per-unit decimals for the console screens', () => {
  assert.equal(C.formatCompact(1500000, { decimals: 2, trim: true }), '1.5m');
  assert.equal(C.formatCompact(1.2e12, { units: [['tn', 1e12], ['bn', 1e9], ['m', 1e6], ['k', 1e3]], trim: true }), '1.2tn');
  assert.equal(C.formatCompact(5000, { units: [['k', 1e3, 1e4]], decimals: 1, smallDecimals: 2, trim: true }), '5,000');
  assert.equal(C.formatCompact(150000, { decimals: (a) => (a >= 1e5 ? 0 : 1) }), '150k');
  assert.equal(C.formatCompact(-3, { decimals: 0, minus: '-' }), '-3');
  assert.equal(C.formatCompact(2e6, { prefix: '$' }), '$2.0m');
});

test('business dates: ranges, business days, holidays', () => {
  assert.equal(C.formatDateRange('2026-11-03', '2026-11-07'), '3–7 Nov 2026');
  assert.equal(C.formatDateRange('2026-10-28', '2026-11-03'), '28 Oct – 3 Nov 2026');
  assert.equal(C.formatDateRange('2025-12-28', '2026-01-03'), '28 Dec 2025 – 3 Jan 2026');
  assert.equal(C.addBusinessDays('2026-11-06', 1), '2026-11-09');                       // Friday to Monday
  assert.equal(C.addBusinessDays('2026-11-09', -1), '2026-11-06');
  assert.equal(C.addBusinessDays('2026-12-24', 1, { holidays: ['2026-12-25'] }), '2026-12-28');
  assert.equal(C.previousBusinessDay('2026-11-09'), '2026-11-06');
  assert.equal(C.businessDaysBetween('2026-11-06', '2026-11-13'), 5);
  assert.equal(C.parseDate('2026-02-30'), null);
  assert.equal(C.formatDate('2026-11-07', 'EEE d MMMM yy'), 'Sat 7 November 26');
});

test('theme tokens come from drishti_tokens.css: every theme light or dark, text 4.5:1 on every ground', () => {
  const names = C.themes().map((t) => t.name);
  assert.ok(names.includes('terminal') && names.includes('light'));
  assert.equal(C.scheme('light'), 'light');
  assert.equal(C.scheme('terminal'), 'dark');
  assert.match(C.token('ink', { theme: 'light' }), /^#/);
  names.forEach((n) => assert.deepEqual(C.contrastProblems(n), [], n));
  assert.equal(Math.round(C.contrastRatio('#000', '#fff')), 21);
  assert.equal(C.readableOn('#ffffff', ['#eeeeee', '#172440']), '#172440');
});

test('the API client: errors in words with the DRS code, retries a GET on 503, never a POST, aborts, sends the as-of date', async () => {
  const calls = [];
  const replies = [{ status: 503, body: {} }, { status: 200, body: { ok: 1 } }];
  const fake = (url, init) => { calls.push({ url, init }); const r = replies.shift(); return Promise.resolve(new Response(JSON.stringify(r.body), { status: r.status })); };
  const api = C.createClient({ fetch: fake, retryDelay: 1, asOf: '2026-11-06', base: '/api' });
  assert.deepEqual(await api.get('things'), { ok: 1 });
  assert.equal(calls.length, 2);
  assert.equal(calls[0].url, '/api/things');
  assert.equal(calls[0].init.headers['X-Drishti-As-Of'], '2026-11-06');
  const bad = C.createClient({ fetch: () => Promise.resolve(new Response(JSON.stringify({ code: 'DRS-4005', detail: 'The table has no column X' }), { status: 400 })) });
  await assert.rejects(bad.post('/x', { a: 1 }), (e) => e instanceof C.ApiError && e.message === 'DRS-4005 The table has no column X' && e.code === 'DRS-4005' && e.status === 400);
  let n = 0;
  const down = C.createClient({ fetch: () => { n++; return Promise.reject(new TypeError('fetch failed')); }, retryDelay: 1 });
  await assert.rejects(down.post('/x', {}), (e) => e.status === 0 && /could not be reached/.test(e.message));
  assert.equal(n, 1);
  const env = await down.send('GET', '/y', undefined, { retries: 0 });
  assert.equal(env.ok, false);
  assert.equal(env.status, 0);
  const ctl = new AbortController();
  const slow = C.createClient({ fetch: (u, i) => new Promise((res, rej) => i.signal.addEventListener('abort', () => rej(new Error('aborted')))) });
  const p = slow.get('/z', { signal: ctl.signal });
  ctl.abort();
  await assert.rejects(p, (e) => e.aborted === true);
  assert.equal((await C.createClient({ fetch: () => Promise.resolve(new Response(null, { status: 204 })) }).del('/a')), null);
});

test('the bus: order, once, wildcard, a failing listener does not stop the others', () => {
  const errs = [];
  const b = C.createBus({ onError: (e) => errs.push(e) });
  const seen = [];
  b.on('x', (p) => seen.push('a' + p));
  b.on('x', () => { throw new Error('boom'); });
  b.once('x', (p) => seen.push('once' + p));
  b.on('*', (p, t) => seen.push('*' + t));
  b.emit('x', 1);
  b.emit('x', 2);
  assert.deepEqual(seen, ['a1', 'once1', '*x', 'a2', '*x']);
  assert.equal(errs.length, 2);
});

test('keys: names, maps, and the panel keys kept for layout mode and the workbench', () => {
  assert.equal(C.keyName({ key: 'ArrowLeft', ctrlKey: true, shiftKey: true }), 'Ctrl+Shift+ArrowLeft');
  assert.equal(C.normalizeKey('shift+left'), 'Shift+ArrowLeft');
  assert.equal(C.normalizeKey('Mod+c', { mac: true }), 'Meta+C');
  const act = C.keymap({ 'Mod+A': 'all', Space: 'toggle' });
  assert.equal(act({ key: 'a', ctrlKey: true }), 'all');
  assert.equal(act({ key: ' ' }), 'toggle');
  assert.deepEqual(C.GridKeys.interpret({ key: 'ArrowUp' }, C.GridKeys.LAYOUT), { type: 'move', step: -1 });
  assert.deepEqual(C.GridKeys.interpret({ key: 'ArrowUp' }, C.GridKeys.WORKBENCH), { type: 'select', dir: 'prev' });
  assert.equal(C.GridKeys.interpret({ key: 'z', ctrlKey: true }, 'layout'), null);
});

test('roving tabindex arithmetic: arrows by orientation, wrap, Home and End, grids by columns', () => {
  assert.equal(C.rovingIndex(0, 5, 'ArrowRight'), 1);
  assert.equal(C.rovingIndex(4, 5, 'ArrowRight'), 4);
  assert.equal(C.rovingIndex(4, 5, 'ArrowRight', { wrap: true }), 0);
  assert.equal(C.rovingIndex(2, 5, 'ArrowDown'), null);
  assert.equal(C.rovingIndex(2, 5, 'ArrowDown', { orientation: 'vertical' }), 3);
  assert.equal(C.rovingIndex(1, 9, 'ArrowDown', { columns: 3 }), 4);
  assert.equal(C.rovingIndex(3, 5, 'Home'), 0);
  assert.equal(C.rovingIndex(0, 5, 'End'), 4);
});

test('the loader: versions and requirements from the manifest; under Node it says so', async () => {
  assert.equal(C.libraryVersion('drishti-core'), C.manifest().libraries['drishti-core'].version);
  assert.deepEqual(C.requirements('drishti-grid'), ['drishti-core', 'drishti-grid']);
  assert.throws(() => C.requirements('drishti-nothing'), /no library drishti-nothing/);
  await assert.rejects(C.load('drishti-grid'), /no page to load it into|is not loaded/);
});

test('the loader: a library ships with its module and is refused where that module is not on for the person', async () => {
  assert.equal(C.manifest().libraries['drishti-risk'].module, 'banking');
  assert.equal(C.manifest().libraries['drishti-grid'].module, 'core');
  assert.equal(C.refused('drishti-risk'), null);                               // nothing said (no Drishti page): every library loads
  try {
    C.configure({ modules: ['economy'] });
    assert.match(C.refused('drishti-risk'), /drishti-risk belongs to the module 'banking', which is not available here/);
    assert.equal(C.refused('drishti-chart'), null);                            // the core's libraries load everywhere
    await assert.rejects(C.load('drishti-risk'), /module 'banking'/);          // refused before any file is asked for
    C.configure({ modules: ['banking'] });
    assert.equal(C.refused('drishti-risk'), null);
  } finally {
    C.configure({ modules: null });
  }
});

test('the bundle is the modules: the same names, the version from the manifest, registered on Drishti.elements', () => {
  const b = require('../../web/static/elements/drishti-core/drishti_core.js');
  const names = Object.keys(C).sort();
  assert.deepEqual(Object.keys(b).filter((k) => k !== 'library' && k !== 'version').sort(), names);
  assert.equal(b.version, C.libraryVersion('drishti-core'));
  assert.equal(globalThis.Drishti.elements.core, b);
  assert.equal(b.formatNamed('pct2', 0.5), '50.00%');
});
