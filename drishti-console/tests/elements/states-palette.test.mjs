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
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';
import * as K from '../../web/static/elements/drishti-chart/src/drishti_index.js';

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
  const G = await import('../../web/static/elements/drishti-grid/src/drishti_index.js');
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

test('no two capabilities share a name: the chart kinds, their aliases and the risk kinds are one registry', async () => {
  await import('../../web/static/elements/drishti-risk/src/drishti_index.js');
  const names = K.kindList().map((k) => k.name);
  assert.equal(new Set(names).size, names.length, 'a kind name is registered twice');
  Object.keys(K.KIND_ALIASES).forEach((a) => {
    assert.ok(names.indexOf(a) < 0, 'the alias ' + a + ' is also a kind');
    assert.ok(names.indexOf(K.KIND_ALIASES[a]) >= 0, 'the alias ' + a + ' names no kind');
  });
  assert.throws(() => K.registerKind(names[0], { build: () => ({}) }), /already|taken/i);
});
