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
/* drishti-tree's table tree without a page: a server's tree table read (rows nested by data-depth, the open state of each toggle, the
   tones, the highlighted row, the footer), and which trees are one chain (they open as the org chart). The drawing itself is checked in
   Chromium (test_tree_rows.py). */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as T from '../../web/static/elements/drishti-tree/src/drishti_index.js';

/** A cell or row as the reader sees it: text, classes, attributes and the toggle. */
function cell(text, cls) { return { textContent: text, className: cls || '', classList: { contains: (c) => (cls || '').split(' ').includes(c) } }; }
function row(depth, texts, o) {
  o = o || {};
  const attrs = { 'data-depth': String(depth) };
  if (o.kids) { attrs['data-kids'] = ''; }
  const tog = o.kids ? { getAttribute: () => (o.open ? 'true' : 'false') } : null;
  return { cells: texts.map((t, i) => cell((i === 0 && o.kids ? (o.open ? '▾ ' : '▸ ') : '') + t, i === 1 && o.tone ? 'mono num ' + o.tone : 'mono')),
    getAttribute: (k) => attrs[k], hasAttribute: (k) => k in attrs, querySelector: () => tog, classList: { contains: (c) => c === 'hl' && !!o.hl } };
}
function table(rows, foot) {
  return { tHead: { rows: [{ cells: [cell('Unit'), cell('Size', 'num')] }] }, tBodies: [{ rows: rows }], tFoot: foot ? { rows: [{ cells: foot.map((t) => cell(t)) }] } : null };
}

test('a server tree table is read as rows with their parents, open state, tones, the highlight and the footer', () => {
  const t = T.readTableTree(table([
    row(0, ['Group', '999'], { kids: true, open: true }),
    row(1, ['Desk A', '100'], { kids: true }),
    row(2, ['Book 1', '30'], { tone: 't-neg' }),
    row(1, ['Desk B', '50'], { hl: true }),
    row(0, ['Loose', '7'])
  ], ['Total', '1,006']));
  assert.deepEqual(t.headers, ['Unit', 'Size']);
  assert.deepEqual(t.numeric, [false, true]);
  assert.deepEqual(t.rows.map((r) => [r.id, r.parent, r.depth, r.label]), [['r0', null, 0, 'Group'], ['r1', 'r0', 1, 'Desk A'], ['r2', 'r1', 2, 'Book 1'], ['r3', 'r0', 1, 'Desk B'], ['r4', null, 0, 'Loose']]);
  assert.deepEqual(t.rows.map((r) => r.open), [true, false, false, false, false]);
  assert.deepEqual(t.rows.map((r) => r.kids), [true, true, false, false, false]);
  assert.equal(t.rows[2].tones[1], 't-neg');
  assert.equal(t.rows[3].highlight, true);
  assert.deepEqual(t.rows[0].texts, ['Group', '999']);                     // the toggle's arrow is not text
  assert.deepEqual(t.foot, ['Total · 1,006']);
  assert.equal(T.isChain(t.rows), false);                                   // two top rows
});

test('a chain (one top row, at most one row under each) opens as the org chart; a single row or a fan does not', () => {
  const chain = [{ id: 'a', parent: null }, { id: 'b', parent: 'a' }, { id: 'c', parent: 'b' }];
  assert.equal(T.isChain(chain), true);
  assert.equal(T.isChain(chain.slice(0, 1)), false);
  assert.equal(T.isChain(chain.concat([{ id: 'd', parent: 'a' }])), false);
  assert.deepEqual(T.PANEL_MODES, ['grid', 'org']);
});
