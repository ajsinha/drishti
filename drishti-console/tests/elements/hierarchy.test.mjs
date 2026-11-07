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
/* drishti-tree without a page: the hierarchy read in its three shapes, open state, search with breadcrumbs, the measures (an additive
   one rolled up, a non-additive one never summed and asked of the host), the org chart's layout, the partitions (refusing a
   non-additive size), the grid's rows, the edit draft (a move under its own descendant refused, undo, discard, commit, the proposal),
   the export text, the keys, the bundle's exports, and drishti-risk's VaR hierarchy preset. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as T from '../../web/static/elements/drishti-tree/src/drishti_index.js';
import * as R from '../../web/static/elements/drishti-risk/src/drishti_index.js';

const MEASURES = [{ field: 'n', label: 'Count' }, { field: 'v', label: 'Risk', additive: false, childSum: true }, { field: 'u', label: 'Use', additive: false, bar: { max: 1, bands: [{ to: 0.75, tone: 'good' }, { to: 1, tone: 'warn' }, { to: Infinity, tone: 'bad' }] } }];
const ROWS = () => [
  { id: 'B', label: 'Bank', kind: 'bank' },
  { id: 'D1', parent: 'B', label: 'Markets', kind: 'division' },
  { id: 'D2', parent: 'B', label: 'Treasury', kind: 'division' },
  { id: 'K1', parent: 'D1', label: 'Rates', kind: 'desk', n: 10, v: 4 },
  { id: 'K2', parent: 'D1', label: 'FX', kind: 'desk', n: 5, v: 3 },
  { id: 'K3', parent: 'D2', label: 'ALM', kind: 'desk', n: 7, v: 2, u: 1.2 }
];
const model = () => new T.HierarchyModel({ rows: ROWS(), measures: MEASURES });

test('the three shapes give the same hierarchy; a missing parent, a twice-used id and a cycle are refused in words', () => {
  const a = model();
  const nested = new T.HierarchyModel({ root: { id: 'B', label: 'Bank', children: [{ id: 'D1', label: 'Markets', children: [{ id: 'K1', label: 'Rates', n: 10 }, { id: 'K2', label: 'FX', n: 5 }] }, { id: 'D2', label: 'Treasury', children: [{ id: 'K3', label: 'ALM', n: 7 }] }] }, measures: MEASURES });
  const paths = new T.HierarchyModel({ rows: [{ p: ['Bank', 'Markets', 'Rates'], n: 10 }, { p: ['Bank', 'Markets', 'FX'], n: 5 }, { p: ['Bank', 'Treasury', 'ALM'], n: 7 }], getPath: (r) => r.p, measures: MEASURES });
  for (const m of [a, nested, paths]) {
    assert.equal(m.size(), 6);
    assert.equal(m.roots.length, 1);
    assert.deepEqual(m.all().map((n) => n.label), ['Bank', 'Markets', 'Rates', 'FX', 'Treasury', 'ALM']);
    assert.equal(m.value(m.roots[0].id, 'n'), 22);                      // an additive measure rolls up
  }
  assert.equal(paths.node('Bank/Markets/FX').depth, 2);
  assert.throws(() => new T.HierarchyModel({ rows: [{ id: 'a', parent: 'zz' }] }), /names a parent zz that is not there/);
  assert.throws(() => new T.HierarchyModel({ rows: [{ id: 'a' }, { id: 'a' }] }), /two nodes have the id a/);
  assert.throws(() => new T.HierarchyModel({ rows: [{ id: 'a', parent: 'b' }, { id: 'b', parent: 'a' }] }), /no root/);
});

test('a non-additive measure is never summed: a parent waits for the host, and its children\'s sum is only beside it', () => {
  const m = model();
  m.o.asksHost = true;
  assert.equal(m.value('D1', 'v'), null);                               // never 7 (4 + 3)
  assert.equal(m.isPending('D1', 'v'), true);
  assert.equal(m.childSum('D1', 'v'), 7);                               // shown beside it, labelled
  assert.deepEqual(m.requests().map((r) => r.id), ['B', 'D1', 'K1', 'K2', 'D2']);   // ALM has both figures already
  assert.deepEqual(m.requests().find((r) => r.id === 'D1'), { id: 'D1', path: ['B', 'D1'], fields: ['v', 'u'] });
  assert.equal(m.setValues([{ id: 'D1', values: { v: 5.5, u: 0.6 } }, { id: 'nope', values: { v: 1 } }]), 1);
  assert.equal(m.value('D1', 'v'), 5.5);
  assert.equal(m.requests().some((r) => r.id === 'D1'), false);
  m.invalidate(['D1']);
  assert.equal(m.value('D1', 'v'), null);
});

test('open state, visible nodes, reveal, and search with breadcrumbs that opens the branches', () => {
  const m = model();
  assert.deepEqual(m.visible().map((n) => n.id), ['B', 'D1', 'D2']);
  m.toggle('D1', true);
  assert.deepEqual(m.visible().map((n) => n.id), ['B', 'D1', 'K1', 'K2', 'D2']);
  m.collapseAll();
  assert.deepEqual(m.visible().map((n) => n.id), ['B', 'D1', 'D2']);
  const hits = m.search('alm');
  assert.deepEqual(hits, [{ id: 'K3', label: 'ALM', breadcrumbs: ['Bank', 'Treasury', 'ALM'] }]);
  assert.equal(m.isOpen('D2'), true);
  assert.deepEqual(m.search('desk r').map((x) => x.id), ['K1']);         // every word, any field (kind too)
  assert.deepEqual(m.search(''), []);
  m.collapseAll(); m.expandAll({ under: 'D1' });
  assert.equal(m.isOpen('D1'), true); assert.equal(m.isOpen('D2'), false);
  m.collapseAll(); m.reveal('K2');
  assert.deepEqual(m.visible().map((n) => n.id), ['B', 'D1', 'K1', 'K2', 'D2']);
});

test('the org chart: a parent centred over its children, closed nodes without them, a further parent dashed', () => {
  const m = new T.HierarchyModel({ rows: ROWS().concat([{ id: 'S', parent: 'D1', label: 'Shared', also: ['D2'] }]), measures: MEASURES });
  m.expandAll();
  const L = T.orgLayout(m, { boxW: 100, boxH: 50, gapX: 10, gapY: 20 });
  const at = (id) => L.boxes.find((b) => b.id === id);
  assert.equal(L.boxes.length, 7);
  assert.equal(at('D1').x, (at('K1').x + at('S').x) / 2);
  assert.equal(at('B').y, 0); assert.equal(at('K1').y, 140);
  assert.equal(L.links.filter((l) => l.shared).length, 1);
  assert.equal(L.links.length, 7);
  m.toggle('D1', false);
  assert.equal(T.orgLayout(m).boxes.length, 4);
});

test('the partition: shares by an additive measure; a non-additive one cannot size it', () => {
  const m = model();
  const P = T.partition(m, { sizeBy: 'n' });
  const s = (id) => P.find((x) => x.id === id);
  assert.equal(s('B').a0, 0); assert.equal(s('B').a1, 1); assert.equal(s('B').value, 22);
  assert.ok(Math.abs(s('D1').a1 - s('D1').a0 - 15 / 22) < 1e-12);
  assert.ok(Math.abs(s('K3').a1 - 1) < 1e-12);
  assert.equal(T.partition(m, { sizeBy: 'n', focus: 'D1' }).length, 3);
  assert.equal(T.partition(m, { sizeBy: 'n', depth: 1 }).length, 3);
  assert.throws(() => T.partition(m, { sizeBy: 'v' }), /Risk is not additive: its parts do not add up to the whole/);
  assert.match(T.arcPath(100, 100, 20, 40, 0, 0.25), /^M100,80L100,60A40,40 0 0 1 140,100L120,100A20,20 0 0 0 100,80Z$/);
  assert.equal(T.bandTone(0.5, MEASURES[2].bar.bands), 'good');
  assert.equal(T.bandTone(1.2, MEASURES[2].bar.bands), 'bad');
  assert.equal(T.bandTone(null, MEASURES[2].bar.bands), null);
});

test('the grid\'s rows: one per node with its path of unique keys and its children\'s sum', () => {
  const m = new T.HierarchyModel({ rows: ROWS().concat([{ id: 'K4', parent: 'D1', label: 'FX', n: 1, v: 1 }]), measures: MEASURES });
  const g = T.gridRows(m);
  assert.equal(g.rows.length, 7);
  assert.deepEqual(g.keysById.get('K2'), ['Bank', 'Markets', 'FX · K2']);
  assert.equal(g.idByKeys.get(['Bank', 'Markets', 'FX · K4'].join('\u001f')), 'K4');
  const d1 = g.rows.find((r) => r.__id === 'D1');
  assert.equal(d1.v, null);                                            // never summed
  assert.equal(d1.__sum_v, 8);
  assert.equal(d1.n, 16);
});

test('the edit draft: moves shown at once, one proposal, a move under its own descendant refused, undo, discard and commit', () => {
  const m = model();
  assert.match(m.moveProblem('D1', 'K1'), /cannot move under its own descendant/);
  assert.match(m.moveProblem('B', 'D1'), /top of the tree/);
  assert.match(m.moveProblem('K1', 'D1'), /already under Markets/);
  assert.throws(() => m.move('D1', 'K2'), /own descendant/);
  m.move('K2', 'D2');
  assert.equal(m.node('K2').parent.id, 'D2');
  assert.equal(m.node('K2').moved, true);
  assert.equal(m.value('D2', 'n'), 12);                                 // the roll-up follows the draft
  m.move('K1', 'D2');
  assert.deepEqual(m.proposal(), { moves: [
    { id: 'K2', label: 'FX', from: 'D1', to: 'D2', fromPath: ['Bank', 'Markets'], toPath: ['Bank', 'Treasury'] },
    { id: 'K1', label: 'Rates', from: 'D1', to: 'D2', fromPath: ['Bank', 'Markets'], toPath: ['Bank', 'Treasury'] }] });
  assert.equal(m.undoMove().id, 'K1');
  assert.equal(m.node('K1').parent.id, 'D1');
  assert.equal(m.discardMoves(), 1);
  assert.equal(m.node('K2').parent.id, 'D1');
  assert.equal(m.node('K2').moved, false);
  m.move('K3', 'D1');
  assert.equal(m.commitMoves(), 1);
  assert.deepEqual(m.proposal(), { moves: [] });
  assert.equal(m.node('K3').moved, false);
  assert.equal(m.node('K3').parent.id, 'D1');
});

test('export text: CSV a row per node with its path, JSON nested', () => {
  const m = model();
  m.setValues([{ id: 'D1', values: { v: 5.5 } }]);
  const csv = T.dataText(m, 'csv').replace(/^\uFEFF/, '').split(/\r?\n/);
  assert.equal(csv[0], 'Path,Id,Kind,Count,Risk,Use');
  assert.ok(csv.some((l) => l.startsWith('Bank / Markets,D1,division,15,5.5')), csv.join('\n'));
  const j = JSON.parse(T.dataText(m, 'json'));
  assert.equal(j[0].children[0].children[1].label, 'FX');
  assert.equal(j[0].values.n, 22);
});

test('the keys of the drawn modes', () => {
  const m = model();
  const order = () => m.visible().map((n) => n.id);
  assert.deepEqual(T.keyMove('ArrowDown', m, order(), 'B'), { to: 'D1', act: 'focus' });
  assert.deepEqual(T.keyMove('ArrowRight', m, order(), 'D1'), { to: 'D1', act: 'open' });
  m.toggle('D1', true);
  assert.deepEqual(T.keyMove('ArrowRight', m, order(), 'D1'), { to: 'K1', act: 'focus' });
  assert.deepEqual(T.keyMove('ArrowLeft', m, order(), 'K1'), { to: 'D1', act: 'focus' });
  assert.deepEqual(T.keyMove('ArrowLeft', m, order(), 'D1'), { to: 'D1', act: 'close' });
  assert.deepEqual(T.keyMove('End', m, order(), 'B'), { to: 'D2', act: 'focus' });
  assert.deepEqual(T.keyMove('e', m, order(), 'K2'), { to: 'K2', act: 'explain' });
  assert.deepEqual(T.keyMove('ArrowRight', m, ['B', 'D1'], 'B', false), { to: 'D1', act: 'focus' });   // a partition walks its slices
  assert.equal(T.keyMove('x', m, order(), 'B'), null);
});

test('the outline: a filter keeps the matches and the nodes above them; Enter opens a node with children and shows a leaf\'s detail', () => {
  const m = model();
  const words = (n) => n.label + ' ' + (n.data.n === undefined ? '' : 'count ' + n.data.n);
  assert.deepEqual(T.outlineFilter(m, '', words), { hits: [], keep: null });                   // an empty query keeps everything
  const f = T.outlineFilter(m, 'COUNT 5', words);                                               // every word, any case
  assert.deepEqual(f.hits, ['K2']);
  assert.deepEqual([...f.keep].sort(), ['B', 'D1', 'K2']);
  assert.deepEqual(T.outlineFilter(m, 'nowhere').hits, []);
  assert.deepEqual(T.outlineFilter(m, 'desk').hits, ['K1', 'K2', 'K3']);                        // default words: label, id and kind
  m.expandAll();
  const order = m.visible().map((n) => n.id);
  assert.deepEqual(T.outlineKey('Enter', m, order, 'D1'), { to: 'D1', act: 'close' });
  m.toggle('D1', false);
  assert.deepEqual(T.outlineKey(' ', m, m.visible().map((n) => n.id), 'D1'), { to: 'D1', act: 'open' });
  assert.deepEqual(T.outlineKey('Enter', m, order, 'K3'), { to: 'K3', act: 'detail' });
  assert.deepEqual(T.outlineKey('Home', m, order, 'K3'), { to: 'B', act: 'focus' });
  assert.deepEqual(T.outlineKey('ArrowLeft', m, order, 'K3'), { to: 'D2', act: 'focus' });     // a leaf: up to its parent
  assert.equal(T.outlineKey('e', m, order, 'K3'), null);                                        // not the outline's: the host's or the grid's
  assert.ok(Object.isFrozen(T.OUTLINE_TONES) && T.OUTLINE_TONES.includes('warn'));
});

test('the lists the gallery reads, and the bundle registers the same API', () => {
  assert.deepEqual(T.TREE_MODES, ['grid', 'org', 'sunburst', 'icicle']);
  assert.ok(Object.isFrozen(T.TREE_EVENTS) && T.TREE_EVENTS.includes('proposal') && T.TREE_EVENTS.includes('selectionChanged'));
  const require = createRequire(import.meta.url);
  globalThis.Drishti = undefined;
  require('../../web/static/elements/drishti-core/drishti_core.js');
  require('../../web/static/elements/drishti-grid/drishti_grid.js');
  const B = require('../../web/static/elements/drishti-tree/drishti_tree.js');
  assert.deepEqual(Object.keys(B).filter((k) => k !== 'library' && k !== 'version').sort(), Object.keys(T).sort());
});

test('drishti-risk\'s VaR hierarchy: VaR, ES, limit and its use never summed; positions and notional roll up', () => {
  const rows = [{ ID: 'BANK', LABEL: 'Bank', LEVEL: 'bank' }, { ID: 'EQ', PARENT: 'BANK', LABEL: 'Equities', LEVEL: 'division' },
    { ID: 'B1', PARENT: 'EQ', LABEL: 'Book 1', LEVEL: 'book', VAR: 3e6, POSITIONS: 120, NOTIONAL: 5e8, LIMIT_USE: 1.04 }, { ID: 'B2', PARENT: 'EQ', LABEL: 'Book 2', LEVEL: 'book', VAR: 2e6, POSITIONS: 80, NOTIONAL: 2e8 }];
  const o = R.varHierarchyTree(rows, { editable: true });
  assert.equal(o.mode, 'grid'); assert.equal(o.density, 'terminal'); assert.equal(o.editable, true);
  const m = new T.HierarchyModel(o.data);
  assert.deepEqual(m.measures.filter((x) => x.additive === false).map((x) => x.field), ['VAR', 'ES', 'LIMIT', 'LIMIT_USE']);
  assert.equal(m.value('EQ', 'VAR'), null);
  assert.equal(m.childSum('EQ', 'VAR'), 5e6);
  assert.equal(m.value('EQ', 'POSITIONS'), 200);
  assert.equal(m.value('BANK', 'NOTIONAL'), 7e8);
  assert.deepEqual(R.limitBreaches(rows), ['B1']);
  assert.throws(() => T.partition(m, { sizeBy: 'VAR' }), /not additive/);
  assert.equal(T.partition(m, { sizeBy: o.sizeBy }).length, 4);
});

test('lazy children: a waiting row until the host answers, then nodes of their own; a failure said in the waiting row', async () => {
  const rows = ROWS().map((r) => (r.id === 'K1' || r.id === 'K2' ? Object.assign({ lazy: true, code: r.id.toLowerCase() }, r) : r));
  const m = new T.HierarchyModel({ rows: rows, measures: MEASURES });
  assert.equal(m.hasChildren('K1'), true);
  assert.equal(m.hasChildren('K3'), false);
  let g = T.gridRows(m, { lazyText: 'computing…' });
  const wait = g.rows.find((r) => r.__wait === 'K1');
  assert.deepEqual(wait.__keys, ['Bank', 'Markets', 'Rates', '…']);
  assert.equal(wait.__label, 'computing…');
  assert.equal(g.rows.find((r) => r.__id === 'K1').code, 'k1');                // the node's own row's fields, for a host's columns
  const asked = [];
  const tree = { model: m, o: { loadChildren: (req) => { asked.push(req); return req.id === 'K1' ? Promise.resolve([{ id: 'P1', label: 'Pos 1', v: 2, lazy: true }, { id: 'T1', parent: 'P1', label: 'Trade 1', v: 1 }]) : Promise.reject(new Error('no answer')); } } };
  assert.equal(T.loadOpenChildren(tree), 0);                                   // nothing open: nothing asked
  m.toggle('D1', true); m.toggle('K1', true); m.toggle('K2', true);
  assert.equal(T.loadOpenChildren(tree), 2);
  assert.equal(T.loadOpenChildren(tree), 0);                                   // asked once while out
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(asked.map((r) => [r.id, r.path.join('>'), r.data.code]), [['K1', 'B>D1>K1', 'k1'], ['K2', 'B>D1>K2', 'k2']]);
  assert.deepEqual(m.node('K1').children.map((n) => n.id), ['P1']);
  assert.equal(m.node('T1').parent.id, 'P1');                                  // a row under another of the answer
  assert.equal(m.node('P1').lazy, false);
  assert.equal(m.node('K1').lazy, false);
  assert.equal(m.value('K1', 'v'), 4);                                         // the server's figure kept, never the children's sum
  assert.equal(m.node('K2').lazyError, 'no answer');
  g = T.gridRows(m);
  assert.equal(g.rows.find((r) => r.__wait === 'K2').__label, 'no answer');
  assert.equal(g.rows.some((r) => r.__wait === 'K1'), false);
  assert.equal(T.loadOpenChildren(tree), 0);                                   // a failure is not asked again until the data is read again
  assert.throws(() => m.addChildren('K3', [{ id: 'P1' }]), /two nodes have the id P1/);
});

test('drishti-risk\'s VaR hierarchy for a screen: its fields in order, its labels and columns, the limit use in words', () => {
  const o = R.varHierarchyTree([{ ID: 'BANK', LABEL: 'Bank' }], { fields: ['VAR', 'ES', 'LIMIT_USE'], measures: { VAR: { label: 'VaR EUR', childSum: false, column: { width: 86 } } }, chrome: false });
  assert.deepEqual(o.data.measures.map((m) => m.field), ['VAR', 'ES', 'LIMIT_USE']);
  assert.equal(o.data.measures[0].label, 'VaR EUR');
  assert.equal(o.data.measures[0].childSum, false);
  assert.equal(o.data.measures[0].column.width, 86);
  assert.equal(o.data.measures[0].additive, false);
  assert.equal(o.chrome, false);
  assert.equal(o.gridLayout, 'terminal');
  assert.equal(o.fields, undefined);
  assert.equal(o.sizeBy, undefined);
  assert.equal(R.limitUseText(0.854), '85%');
  assert.equal(R.limitUseText(null, { LIMIT_NOTE: 'n/c' }), 'n/c');
  assert.equal(R.limitUseText(undefined, {}), 'no limit');
  assert.equal(R.VAR_TREE_MEASURES[0].format(1234567), R.riskMoney(1234567));
  assert.throws(() => R.varHierarchyTree([], { fields: ['PNL'] }), /no measure PNL/);
});
