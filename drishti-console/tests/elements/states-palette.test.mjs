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
/* The state kit, the command palette's search, the bullet bar and the grid's cell drawings, under Node (the parts that need no document). */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-core/src/drishti_index.js';
import * as K from '../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-chart/src/drishti_index.js';

test('the four states are named, and an unknown one is refused', () => {
  assert.deepEqual(Array.from(C.STATES), ['empty', 'loading', 'error', 'stale']);
  assert.throws(() => C.showState({ ownerDocument: {} }, 'broken'), /no state broken/);
});

test('fuzzy search: letters in order, word starts and runs score higher, a miss is -1', () => {
  assert.equal(C.fuzzyScore('zz', 'Go to the VaR page'), -1);
  assert.ok(C.fuzzyScore('var', 'Go to the VaR page') > C.fuzzyScore('vpg', 'Go to the VaR page'));
  assert.ok(C.fuzzyScore('gv', 'Go to the VaR page') >= 0);
  assert.equal(C.fuzzyScore('', 'anything'), 0);
});

test('ranking: best first, synonyms find a command, an empty query keeps the order', () => {
  const cmds = [{ id: 'a', title: 'Export as CSV' }, { id: 'b', title: 'Switch theme', words: ['dark', 'light'] }, { id: 'c', title: 'Go to the Exposure page' }];
  assert.deepEqual(C.rankCommands(cmds, 'dark').map((c) => c.id), ['b']);
  assert.equal(C.rankCommands(cmds, 'exp')[0].id, 'a');
  assert.deepEqual(C.rankCommands(cmds, '').map((c) => c.id), ['a', 'b', 'c']);
  assert.deepEqual(C.rankCommands(cmds, 'qqq'), []);
});

test('the bullet: a scale with room past the limit, and words with the figure, the limit and the use', () => {
  const sc = K.bulletScale({ value: 82, limit: 100 });
  assert.equal(sc.min, 0);
  assert.ok(sc.max > 100);
  assert.ok(sc.at(100) < 100 && sc.at(0) === 0 && sc.at(1e9) === 100);
  assert.equal(K.bulletSummary({ value: 82, limit: 100, label: 'VaR' }), 'VaR: 82 of a limit of 100, 82% used');
  assert.match(K.bulletSummary({ value: 120, limit: 100 }), /over the limit/);
  assert.equal(K.bulletSummary({ value: 5 }), '5');
});

test('the grid draws sparkline, bar and bullet cells through one chart bullet', async () => {
  const G = await import('../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-grid/src/drishti_index.js');
  const calls = [];
  const el = (tag) => ({ tag, children: [], setAttribute() {}, appendChild(c) { this.children.push(c); return c; }, append(...c) { c.forEach((x) => this.appendChild(x)); }, style: {},
    set className(v) { this.cls = v; }, set textContent(v) { this.text = v; } });
  const doc = { createElement: el, createElementNS: (ns, t) => { calls.push(t); return el(t); }, createTextNode: (t) => ({ text: t }) };
  assert.equal(G.bulletCell()({ value: 'x', text: 'x', row: {}, doc }), null);
  assert.ok(G.bulletCell({ limit: 'limit' })({ value: 5, text: '5', row: { limit: 10 }, doc }));
  assert.ok(G.barCell({ max: 10 })({ value: 5, text: '5', row: {}, doc }));
  assert.equal(G.sparklineCell({ values: 'trend' })({ value: 1, text: '1', row: { trend: [] }, doc }), null);
  assert.ok(G.sparklineCell({ values: 'trend' })({ value: 1, text: '1', row: { trend: [1, 2, 3] }, doc }));
  assert.ok(calls.indexOf('rect') >= 0 && calls.indexOf('polyline') >= 0);
});

test('no two capabilities share a name: the chart kinds and the risk kinds are one registry (one name per kind, no aliases)', async () => {
  await import('../../web/static/elements/drishti-risk/src/drishti_index.js');
  const names = K.kindList().map((k) => k.name);
  assert.equal(new Set(names).size, names.length, 'a kind name is registered twice');
  assert.equal(K.KIND_ALIASES, undefined, 'Drishti Elements keeps one name per chart kind');
  assert.throws(() => K.registerKind(names[0], { build: () => ({}) }), /already|taken/i);
});

test('a kernel density peaks at 1 near the data, tenors sort by length, a diverging scale is centred on its midpoint', () => {
  const d = K.kernelDensity([1, 2, 2, 3, 2, 2.5, 1.5], { points: 33 });
  assert.equal(d.length, 33);
  assert.equal(Math.max(...d.map((p) => p.d)), 1);
  assert.ok(Math.abs(d.reduce((m, p) => (p.d > m.d ? p : m)).x - 2) < 0.6);
  assert.deepEqual(['10Y', '3M', 'ON', '1W', '2Y'].sort((a, b) => K.tenorDays(a) - K.tenorDays(b)), ['ON', '1W', '3M', '2Y', '10Y']);
  const sc = K.colourScale({ type: 'diverging', domain: [-10, 40], midpoint: 5, ramp: ['#000000', '#808080', '#ffffff'] });
  assert.equal(sc.lo, -30);
  assert.equal(sc.hi, 40);
  assert.equal(sc.colour(5), 'rgb(128,128,128)');
  assert.throws(() => K.colourScale({ domain: [0, 1] }), /ramp/);
});

test('grid: threshold and top rules colour the right figures, a view survives a share link, the heat preset formats every measure', async () => {
  const G = await import('../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-grid/src/drishti_index.js');
  const range = { min: 0, max: 100, maxAbs: 100 };
  assert.ok(G.cfBackground({ type: 'threshold', op: 'gt', value: 50, tone: 'bad' }, 60, range));
  assert.equal(G.cfBackground({ type: 'threshold', op: 'gt', value: 50 }, 40, range), null);
  assert.ok(G.cfBackground({ type: 'threshold', op: 'between', value: 10, value2: 20, tone: 'good' }, 15, range));
  assert.ok(G.cfBackground({ type: 'top', percent: 10 }, 95, range));
  assert.equal(G.cfBackground({ type: 'top', percent: 10 }, 50, range), null);
  assert.deepEqual(G.ruleProblems({ type: 'threshold', column: 'mtm', op: 'gt', value: 5 }), []);
  assert.ok(G.ruleProblems({ type: 'threshold', column: 'mtm', op: 'between', value: 5 }).length === 1);
  assert.match(G.ruleText({ type: 'threshold', column: 'MTM', op: 'gt', value: 100, tone: 'bad' }), /MTM is more than 100: bad/);
  const state = { layout: 'wells', view: { hidden: ['x'], formats: [{ type: 'dataBar', column: 'a' }] }, note: 'é→' };
  const link = G.shareLink('https://h/p?x=1#old', state);
  assert.ok(link.startsWith('https://h/p?x=1#grid-view='));
  assert.deepEqual(G.readSharedView(link), state);
  assert.equal(G.readSharedView('https://h/p'), null);
  assert.throws(() => G.decodeView('###'), /does not carry/);
  const p = G.heatPivotPreset(['var', 'mtm']);
  assert.equal(p.conditionalFormats.length, 2);
  assert.ok(p.grandColumn && p.columnGroupTotals);
});

test('dock: split, tab, close and a saved workspace that survives a panel added or removed', () => {
  const m = C.DockModel.of(['a', 'b', 'c']);
  assert.deepEqual(m.panels(), ['a', 'b', 'c']);
  m.split('c', 'row');
  assert.equal(m.root.type, 'split');
  assert.deepEqual(m.groupOf('c').panels, ['c']);
  assert.deepEqual(m.groupOf('a').panels, ['a', 'b']);
  m.moveToGroupOf('c', 'a');
  assert.equal(m.root.type, 'tabs');
  assert.deepEqual(m.root.panels, ['a', 'b', 'c']);
  m.split('b', 'column', 'a', true);
  assert.equal(m.root.dir, 'column');
  assert.equal(m.root.a.panels[0], 'b');
  m.close('b');
  assert.equal(m.root.type, 'tabs');
  assert.throws(() => m.add('a'), /already/);
  m.add('d');
  assert.equal(m.groupOf('d').active, 'd');
  const saved = m.toJSON();
  const next = C.DockModel.fromJSON(saved, ['a', 'c', 'e']);
  assert.deepEqual(next.panels().sort(), ['a', 'c', 'e']);
  let seen = 0;
  next.onChange(() => { seen++; });
  next.activate('a');
  assert.equal(seen, 1);
});

test('the selection history keeps each set once, newest first, and never an empty one', () => {
  const hs = new C.SelectionHistory(3);
  hs.push('t', ['1', '2']); hs.push('t', []); hs.push('t', ['3']); hs.push('t', ['2', '1']); hs.push('t', ['4']); hs.push('t', ['5']);
  assert.deepEqual(hs.list().map((x) => x.ids.join()), ['5', '4', '2,1']);
});

test('query builder text: conditions round-trip, quoted values, lists, and the errors say what is wrong', async () => {
  const I = await import('../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-inputs/src/drishti_index.js');
  const fields = [{ id: 'desk', type: 'enum', values: ['Rates', 'FX'] }, { id: 'var', type: 'number' }, { id: 'name' }];
  const q = I.parseQuery('desk in (Rates, FX) AND var >= 100 AND name = "Big Fund"', fields);
  assert.deepEqual(q, [{ field: 'desk', op: 'in', value: ['Rates', 'FX'] }, { field: 'var', op: 'ge', value: 100 }, { field: 'name', op: 'eq', value: 'Big Fund' }]);
  assert.equal(I.queryText(q), 'desk in (Rates, FX) AND var >= 100 AND name = "Big Fund"');
  assert.deepEqual(I.parseQuery('', fields), []);
  assert.throws(() => I.parseQuery('nope = 1', fields), /no field nope/);
  assert.throws(() => I.parseQuery('var > abc', fields), /is a number/);
  assert.throws(() => I.parseQuery('desk > 1', fields), /cannot be compared/);
  assert.throws(() => I.parseQuery('just words', fields), /field operator value/);
});

test('data: bins are counted once (page or worker), a worker-less page still answers', async () => {
  const D = await import('../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-data/src/drishti_index.js');
  const r = D.binCounts(new Float64Array([0, 1, 1, 2, 9, NaN]), { bins: 5, extent: [0, 10] });
  assert.deepEqual(Array.from(r.counts), [3, 1, 0, 0, 1]);
  assert.equal(r.n, 5);
  const w = new D.DataWorker({ worker: false });
  assert.equal(w.usesWorker(), false);
  assert.deepEqual(Array.from((await w.bin(new Float64Array([1, 2, 3, 4]), { bins: 2 })).counts), [2, 2]);
  const f = D.frameFromWorker({ names: ['a', 'b'], length: 2, columns: [{ name: 'a', type: 'number', data: new Float64Array([1, 2]) }, { name: 'b', type: 'text', data: ['x', 'y'] }], meta: {}, bytes: 3 });
  assert.equal(f.length, 2);
  assert.deepEqual(Array.from(f.column('a')), [1, 2]);
});

test('grid master-detail: Alt+Down opens the focused row\'s detail and Alt+Up closes it, only when the grid has a detail', async () => {
  const G = await import('../../web/static/vendor/drishti-elements/3.3.0/elements/drishti-grid/src/drishti_index.js');
  const mk = (on) => new G.GridNavigator({ rowCount: () => 5, columnCount: () => 3, detailEnabled: () => on });
  const key = (k) => ({ key: k, altKey: true, ctrlKey: false, metaKey: false, shiftKey: false });
  const n = mk(true);
  n.setFocus(1, 0);
  assert.equal(n.handleKey(key('ArrowDown')).action, 'detail-open');
  assert.equal(n.handleKey(key('ArrowUp')).action, 'detail-close');
  const off = mk(false);
  off.setFocus(1, 0);
  assert.equal(off.handleKey(key('ArrowDown')).action === 'detail-open', false);
});

test('insight: a chart snapshot with a note is kept as data, a non-image is refused, and a note change is heard', async () => {
  const I = await import('../../web/static/elements/drishti-insight/src/drishti_index.js');
  const s = new I.SnapshotStore({ now: () => new Date('2026-11-16T10:00:00Z') });
  const heard = [];
  s.onChange(() => heard.push(s.items.length));
  const chart = { snapshot: (what) => ({ what, data: what === 'svg' ? '<svg/>' : 'data:image/png;base64,AAAA', title: 'VaR by desk', summary: 'Bar chart, 4 bars', asOf: '2026-11-13' }) };
  const a = s.take(chart, { note: 'Rates up on the curve move' });
  const b = s.take(chart, { what: 'svg' });
  assert.ok(a.image.startsWith('data:image/png;') && b.image.startsWith('data:image/svg+xml;charset=utf-8,'));
  s.setNote(a.id, 'Revised');
  s.remove(b.id);
  const out = s.toJSON();
  assert.equal(out.snapshots.length, 1);
  assert.equal(out.snapshots[0].note, 'Revised');
  assert.equal(out.snapshots[0].at, '2026-11-16T10:00:00.000Z');
  assert.deepEqual(heard, [1, 2, 2, 1]);
  assert.throws(() => s.add({ title: 'x', image: 'javascript:alert(1)' }), /PNG or SVG/);
});
