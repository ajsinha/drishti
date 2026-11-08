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
/* drishti-designer without a page (node --test): the command stack and snapshot undo, the selection, the layout model (snap, settle,
   moves and resizes inside the grid, multi-select, align, distribute, z-order, groups, copy and paste, flow areas, a phone's order),
   the keymap, the YAML written and read both ways with each path's lines, the YAML assistant over a JSON Schema, the inspector's
   shared schema and mixed values, the outline's moves, the palette's search and the shell's status words. The drawn components are
   tested in a browser against the harness (test_elements_designer_browser.py). */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as K from '../../web/static/elements/drishti-designer/src/drishti_index.js';

const require = createRequire(import.meta.url);

test('the command stack: run, undo, redo, groups as one step, merging, saved and dirty', () => {
  let t = 0;
  const s = new K.CommandStack({ now: () => t });
  let n = 0;
  const inc = (d, key) => ({ label: 'add ' + d, mergeKey: key, redo: () => { n += d; }, undo: () => { n -= d; } });
  s.run(inc(1)); s.run(inc(2));
  assert.equal(n, 3);
  assert.equal(s.undo().label, 'add 2'); assert.equal(n, 1);
  assert.equal(s.redo().label, 'add 2'); assert.equal(n, 3);
  s.group('both', () => { s.run(inc(10)); s.run(inc(20)); });
  assert.equal(n, 33); assert.equal(s.state().undoLabel, 'both');
  s.undo(); assert.equal(n, 3);
  s.run(inc(5, 'k')); t = 100; s.run(inc(5, 'k')); assert.equal(s.state().size, 3, 'two steps with one key, close together, are one');
  s.undo(); assert.equal(n, 3);
  t = 5000; s.run(inc(1, 'k')); t = 9000; s.run(inc(1, 'k')); assert.equal(s.state().size, 4, 'far apart they stay two');
  assert.equal(s.canRedo(), false, 'a new step forgets what undo took back');
  s.markSaved(); assert.equal(s.dirty(), false); s.run(inc(1)); assert.equal(s.dirty(), true);
  assert.throws(() => s.group('bad', () => { s.run(inc(100)); throw new Error('x'); }), /x/);
  assert.equal(n, 6, 'a group that throws is taken back');
});

test('snapshot undo: a host that changes its document in place', () => {
  let doc = { pages: [{ visuals: [] }] }, redoable = null;
  const hist = new K.SnapshotHistory({ get: () => doc, set: (d, how) => { doc = d; if (how === 'undo') { redoable = hist.canRedo(); } } });
  assert.equal(hist.change('Add', (m) => { m.pages[0].visuals.push({ id: 'v1' }); }), true);
  assert.equal(hist.change('Nothing', () => {}), false, 'a step that changed nothing is not kept');
  assert.equal(hist.change('Refused', (m) => { m.pages.push({}); return false; }), false);
  assert.equal(doc.pages.length, 1, 'a refused step is put back');
  hist.undo(); assert.equal(doc.pages[0].visuals.length, 0);
  assert.equal(redoable, true, 'a host redrawing its buttons during the step already sees Redo');
  hist.redo(); assert.equal(doc.pages[0].visuals[0].id, 'v1');
});

test('the selection: plain picks replace, additive picks toggle, the primary is the last, prune', () => {
  const s = new K.Selection(), seen = [];
  s.on('change', (c) => seen.push(c));
  s.pick('a'); s.pick('b', true); s.pick('c', true);
  assert.deepEqual(s.list(), ['a', 'b', 'c']); assert.equal(s.primary(), 'c');
  s.pick('b', true); assert.deepEqual(s.list(), ['a', 'c']);
  s.pick('d'); assert.deepEqual(s.list(), ['d']);
  s.set('d'); assert.equal(seen.length, 5, 'the same selection again is no change');
  s.set(['x', 'y'], 'outline'); s.prune((id) => id === 'y'); assert.deepEqual(s.list(), ['y']);
  assert.equal(seen[seen.length - 2].source, 'outline');
});

test('the grid rules: snap, clamp, settle, align, distribute', () => {
  assert.equal(K.snap(95, 50), 2); assert.equal(K.snap(-24, 50), 0); assert.equal(K.snap(-26, 50), -1);
  assert.deepEqual(K.clampRect({ x: 11, y: -2, w: 4, h: 0 }, { cols: 12 }), { x: 8, y: 0, w: 4, h: 1 });
  const items = [{ id: 'a', x: 0, y: 0, w: 6, h: 2 }, { id: 'b', x: 0, y: 2, w: 6, h: 2 }, { id: 'c', x: 6, y: 0, w: 6, h: 3 }];
  items[0].h = 3;
  assert.deepEqual(K.settle(items, items[0]), ['b']); assert.equal(items[1].y, 3);
  assert.deepEqual(K.alignRects([{ x: 2, y: 0, w: 2, h: 1 }, { x: 5, y: 3, w: 3, h: 1 }], 'left').map((r) => r.x), [2, 2]);
  assert.deepEqual(K.alignRects([{ x: 2, y: 0, w: 2, h: 1 }, { x: 5, y: 3, w: 3, h: 1 }], 'right').map((r) => r.x), [6, 5]);
  assert.deepEqual(K.distributeRects([{ x: 0, y: 0, w: 2, h: 1 }, { x: 3, y: 0, w: 2, h: 1 }, { x: 10, y: 0, w: 2, h: 1 }], 'x').map((r) => r.x), [0, 5, 10]);
  assert.equal(K.clampSpan(1), 3); assert.equal(K.clampHeight(0), 0); assert.equal(K.clampHeight(99), 24);
  assert.equal(K.sizeText('Trades', 6, 0), 'Trades: 6 of 12 columns wide, as tall as its content');
  assert.equal(K.rowsFromPixels(85, 40), 3); assert.equal(K.hideRefused([true, false], 1), true);
});

test('the layout model (grid): add settles, moves stay inside the grid, multi-select moves as one block, undo and redo', () => {
  const m = new K.LayoutModel();
  m.add([{ id: 'a', x: 0, y: 0, w: 6, h: 3 }, { id: 'b', x: 6, y: 0, w: 6, h: 3 }]);
  m.add({ id: 'c', x: 0, y: 1, w: 4, h: 2 });
  assert.equal(m.get('a').y, 3, 'what the new item overlaps goes down');
  assert.equal(m.moveBy(['b'], 5, 0), false, 'already at the right edge: nothing to do');
  m.moveBy(['c', 'b'], 1, 0);
  assert.equal(m.get('c').x, 0, 'the block is stopped by the edge as one'); assert.equal(m.get('b').x, 6);
  m.moveBy(['c'], 2, 0); assert.equal(m.get('c').x, 2);
  m.resizeBy(['c'], 20, 1); assert.equal(m.get('c').w, 10); assert.equal(m.get('c').h, 3);
  m.stack.undo(); assert.equal(m.get('c').w, 4);
  m.stack.undo(); assert.equal(m.get('c').x, 0);
  m.stack.redo(); assert.equal(m.get('c').x, 2);
  m.setRect('c', { x: 7, y: 0 }); assert.equal(m.get('c').x, 7); assert.ok(m.get('b').y >= 2, 'a rectangle set settles too');
  assert.throws(() => m.add({ id: 'a' }), /new id/);
});

test('the layout model: align, distribute, z-order, groups, copy and paste, delete', () => {
  const m = new K.LayoutModel({ settle: false });
  m.add([{ id: 'a', x: 0, y: 0, w: 2, h: 2 }, { id: 'b', x: 4, y: 3, w: 2, h: 2 }, { id: 'c', x: 9, y: 6, w: 2, h: 2 }]);
  m.align(['a', 'b', 'c'], 'top'); assert.deepEqual(['a', 'b', 'c'].map((i) => m.get(i).y), [0, 0, 0]);
  m.distribute(['a', 'b', 'c'], 'x'); assert.deepEqual(['a', 'b', 'c'].map((i) => m.get(i).x), [0, 5, 9]);
  m.zOrder(['a'], 'front'); assert.equal(m.get('a').z, 3);
  m.zOrder(['a'], 'backward'); assert.equal(m.get('a').z, 2);
  m.zOrder(['a'], 'back'); assert.equal(m.get('a').z, 1);
  const g = m.group(['a', 'b']); assert.equal(m.get('a').group, g);
  assert.deepEqual(m.expand(['a']).sort(), ['a', 'b']);
  m.moveBy(['a'], 0, 2); assert.equal(m.get('b').y, 2, 'a group moves together');
  const made = m.duplicate(['a']); assert.equal(made.length, 2, 'a copy takes its group'); assert.notEqual(m.get(made[0]).group, g);
  assert.ok(made.every((id) => /^(a|b)-2$/.test(id)));
  const clip = m.copy(['c']); const pasted = m.paste(clip, { x: 0, y: 10 }); assert.deepEqual(pasted, ['c-2']); assert.equal(m.get('c-2').y, 10);
  m.ungroup(['a']); assert.equal(m.get('b').group, undefined);
  m.remove(['c-2']); assert.equal(m.get('c-2'), null);
  m.stack.undo(); assert.ok(m.get('c-2'));
});

test('the layout model (flow): areas in order, moves before and after, a step, spans and heights', () => {
  const m = new K.LayoutModel({ mode: 'flow', areas: ['main', 'right'] });
  m.load([{ id: 'p1', area: 'main' }, { id: 'p2', area: 'main' }, { id: 's1', area: 'right' }, { id: 'p3', area: 'main' }]);
  assert.deepEqual(m.ids(), ['p1', 'p2', 'p3', 's1']);
  m.moveTo('p3', { area: 'main', before: 'p1' }); assert.deepEqual(m.inArea('main').map((i) => i.id), ['p3', 'p1', 'p2']);
  m.moveTo('p1', { area: 'right' }); assert.deepEqual(m.inArea('right').map((i) => i.id), ['s1', 'p1']);
  assert.equal(m.step('p3', -1), false, 'already first');
  m.step('p3', 1); assert.deepEqual(m.inArea('main').map((i) => i.id), ['p2', 'p3']);
  m.setSize('p2', { span: 1 }); assert.equal(m.get('p2').span, 3, 'never narrower than readable');
  m.setSize('p2', { height: 40 }); assert.equal(m.get('p2').h, 24);
  m.stack.undo(); m.stack.undo(); assert.equal(m.get('p2').span, undefined);
});

test('a host that keeps its own undo hears each step as a commit', () => {
  const m = new K.LayoutModel({ stack: null }), heard = [];
  m.on('commit', (c) => heard.push(c));
  m.load([{ id: 'v1', x: 0, y: 0, w: 4, h: 2 }]);
  m.moveBy(['v1'], 2, 1);
  assert.equal(heard.length, 1); assert.deepEqual(heard[0].ids, ['v1']); assert.equal(heard[0].before[0].x, 0); assert.equal(heard[0].after[0].x, 2);
});

test('breakpoints: a phone\'s own order, hidden items and heights; arranging back removes the entry', () => {
  const m = new K.LayoutModel();
  m.load([{ id: 'a', x: 0, y: 0, w: 6, h: 2 }, { id: 'b', x: 6, y: 0, w: 6, h: 2 }, { id: 'c', x: 0, y: 2, w: 12, h: 2 }]);
  m.arrange('phone', { move: ['c', -2] }); assert.deepEqual(m.order('phone'), ['c', 'a', 'b']);
  m.arrange('phone', { hide: ['a', true], height: ['b', 5] }); assert.deepEqual(m.order('phone'), ['c', 'b']);
  assert.deepEqual(m.breakpoint('phone'), { order: ['c', 'a', 'b'], hidden: ['a'], heights: { b: 5 } });
  m.arrange('phone', { order: ['a', 'b', 'c'], hide: ['a', false], height: ['b', 0] });
  assert.equal(m.breakpoint('phone'), null, 'the automatic arrangement again');
});

test('breakpoints: wide items (a tablet\'s whole width), a named step, a host\'s own undo; removing an item tidies every breakpoint', () => {
  const m = new K.LayoutModel({ stack: null }), heard = [];
  m.on('commit', (c) => heard.push(c));
  m.load([{ id: 'a', x: 0, y: 0, w: 6, h: 2 }, { id: 'b', x: 6, y: 0, w: 6, h: 2 }], { tablet: { order: ['b', 'a'], hidden: [], heights: { a: 3 } } });
  assert.ok(m.arrange('tablet', { wide: ['a', true] }, 'Whole width on a tablet'));
  assert.equal(heard[0].label, 'Whole width on a tablet');
  assert.deepEqual(m.breakpoint('tablet'), { order: ['b', 'a'], hidden: [], heights: { a: 3 }, wide: ['a'] });
  assert.deepEqual(heard[0].breakpoints.tablet.wide, ['a']);
  assert.equal(m.arrange('tablet', { wide: ['a', true] }), false, 'nothing changed: no step');
  m.remove(['a']);
  assert.deepEqual(m.breakpoint('tablet'), { order: ['b'], hidden: [], heights: {} });
  m.arrange('tablet', { order: ['b'] });
  assert.equal(m.breakpoint('tablet'), null, 'nothing wide, hidden or tall: the full layout again');
});

test('the keymap: two schemes of the arrows and the common keys; a host overrides', () => {
  const mv = K.designerKeys('move'), se = K.designerKeys('select');
  assert.deepEqual(mv({ key: 'ArrowLeft' }), { type: 'move', dx: -1, dy: 0 });
  assert.deepEqual(mv({ key: 'ArrowDown', shiftKey: true }), { type: 'size', dx: 0, dy: 1 });
  assert.deepEqual(se({ key: 'ArrowDown' }), { type: 'select', dir: 'next' });
  assert.deepEqual(se({ key: 'ArrowUp', altKey: true }), { type: 'move', dx: 0, dy: -1 });
  assert.deepEqual(se({ key: '+', shiftKey: true }), { type: 'size', dx: 1, dy: 0 });
  assert.equal(mv({ key: 'z', ctrlKey: true }).type, 'undo');
  assert.equal(mv({ key: 'Z', ctrlKey: true, shiftKey: true }).type, 'redo');
  assert.equal(mv({ key: 'g', ctrlKey: true }).type, 'group');
  assert.equal(mv({ key: 'Delete' }).type, 'delete');
  assert.equal(K.designerKeys('move', { Delete: null })({ key: 'Delete' }), undefined);
  assert.ok(K.keyHelp('select').some((k) => k.does === 'undo'));
});

test('YAML written and read back: plain values both ways, quoting only when needed', () => {
  const doc = { report: 'Desk P&L', version: 2, live: true, note: 'yes', empty: [], text: 'line one\nline two', pages: [{ id: 'p1', visuals: [{ id: 'v1', at: { x: 0, y: 0, w: 6, h: 4 }, wells: { values: ['NOTIONAL', 'MTM'] } }] }] };
  const text = K.dumpYaml(doc);
  assert.match(text, /^report: Desk P&L$/m); assert.match(text, /^note: "yes"$/m); assert.match(text, /^empty: \[\]$/m);
  assert.match(text, /^ {2}- id: p1$/m);
  const back = K.readYaml(text);
  assert.deepEqual(back.problems, []);
  assert.deepEqual(back.value, doc);
  assert.equal(K.yamlScalar('12'), '"12"'); assert.equal(K.yamlScalar('a: b'), '"a: b"'); assert.equal(K.yamlScalar('NOTIONAL'), 'NOTIONAL');
});

test('each path\'s lines, and the item a line belongs to', () => {
  const text = K.dumpYaml({ title: 'T', pages: [{ id: 'p1', visuals: [{ id: 'v1', type: 'bar' }, { id: 'v2', type: 'card', wells: { values: ['X'] } }] }] });
  const r = K.yamlRanges(text), lines = text.split('\n');
  const [a, b] = r.get('pages[0].visuals[1]');
  assert.match(lines[a], /- id: v2/); assert.match(lines[b], /- X/);
  assert.deepEqual(r.get('title'), [0, 0]);
  const only = (p) => /^pages\[\d+\]\.visuals\[\d+\]$/.test(p);
  assert.equal(K.pathAtLine(r, b, only), 'pages[0].visuals[1]');
  assert.equal(K.pathAtLine(r, b), 'pages[0].visuals[1].wells.values[0]');
});

test('reading YAML says what does not read, with its line', () => {
  const r = K.readYaml('title: A\nbroken\n\tx: 1\n');
  assert.deepEqual(r.problems.map((p) => p.line).sort(), [2, 3]);
  assert.ok(r.problems.some((p) => /colon/.test(p.message)));
  assert.ok(r.problems.some((p) => /tab/.test(p.message)));
});

test('the YAML assistant: keys, a kind\'s options, enum values, functions, the light check and help', () => {
  const schema = new K.YamlSchema({ type: 'object', additionalProperties: false, required: ['id'],
    properties: { id: { type: 'string', description: 'The id' }, panels: { type: 'array', items: { $ref: '#/$defs/panel' } }, live: { type: 'boolean' } },
    'x-rachana-functions': { sum: { min: 1, max: 2 } },
    $defs: { panel: { type: 'object', properties: { id: { type: 'string' }, kind: { enum: ['table', 'chart'] }, rows: { type: 'string', description: 'Rachana-EL path' }, columns: { type: 'array' }, stacked: { type: 'boolean' } },
      allOf: [{ if: { properties: { kind: { const: 'chart' } } }, then: { required: ['rows'], 'x-rachana-options': ['stacked'] } }, { if: { properties: { kind: { const: 'table' } } }, then: { 'x-rachana-options': ['columns'] } }] } } });
  const keys = K.yamlCompletions(schema, 'id: a\nli', 1, 2).map((e) => e.name);
  assert.deepEqual(keys, ['live']);
  const text = 'id: x\npanels:\n  - kind: chart\n    st';
  assert.deepEqual(K.yamlCompletions(schema, text, 3, 6).map((e) => e.insert), ['stacked: ']);
  assert.deepEqual(K.yamlCompletions(schema, 'id: x\npanels:\n  - kind: ', 2, 10).map((e) => e.name), ['table', 'chart']);
  const fns = K.yamlCompletions(schema, 'id: x\npanels:\n  - kind: chart\n    rows: su', 3, 12);
  assert.equal(fns[0].name, 'sum'); assert.match(fns[0].signature, /^sum\(/);
  const probs = K.yamlCheck(schema, 'panels:\n  - kind: table\n    stacked: true\n  - kind: chart\n  - kind: pie\nwho: 1\n');
  const words = probs.map((p) => p.message).join(' | ');
  assert.match(words, /needs "id"/); assert.match(words, /"stacked" is not an option of table panels \(it is of chart\)/); assert.match(words, /"pie" is not a panel kind/); assert.match(words, /unknown key "who"/);
  assert.match(words, /needs "rows" \(chart panels\)/);
  assert.equal(K.yamlHelp(schema, 'id: x\n', 0, 1).text, 'The id');
});

test('the inspector\'s shared schema and mixed values', () => {
  const s = K.sharedSchema([{ type: 'object', properties: { title: { type: 'string' }, w: { type: 'integer' } } }, { type: 'object', properties: { title: { type: 'string' }, h: { type: 'integer' } } }]);
  assert.deepEqual(Object.keys(s.properties), ['title']);
  assert.equal(K.sharedSchema([{ type: 'object', properties: { a: { type: 'string' } } }, { type: 'object', properties: { b: { type: 'string' } } }]), null);
  assert.deepEqual(K.commonValues([{ title: 'A', w: 2 }, { title: 'A', w: 3 }]), { values: { title: 'A' }, mixed: ['w'] });
});

test('the outline: rows, moves before, after and inside; never into itself', () => {
  const tree = [{ id: 'p1', label: 'Page 1', children: [{ id: 'v1', label: 'V1' }, { id: 'v2', label: 'V2' }] }, { id: 'p2', label: 'Page 2', children: [] }];
  assert.deepEqual(K.flattenTree(tree).map((r) => r.node.id + r.level), ['p11', 'v12', 'v22', 'p21']);
  assert.deepEqual(K.flattenTree(tree, new Set(['p1'])).map((r) => r.node.id), ['p1', 'p2']);
  assert.deepEqual(K.moveNode(tree, 'v2', 'v1', 'before')[0].children.map((n) => n.id), ['v2', 'v1']);
  assert.deepEqual(K.moveNode(tree, 'v1', 'p2', 'inside')[1].children.map((n) => n.id), ['v1']);
  assert.equal(K.moveNode(tree, 'p1', 'v1', 'inside'), null);
  assert.equal(tree[0].children.length, 2, 'the given tree is not changed');
  assert.equal(K.findNode(tree, 'v2').parent, 'p1');
});

test('the palette\'s search and categories; the shell\'s status words', () => {
  const e = [{ id: 'bar', label: 'Bar chart', category: 'Charts', keywords: ['column'] }, { id: 'card', label: 'Card', category: 'Numbers' }, { id: 'line', label: 'Line chart', category: 'Charts' }];
  assert.deepEqual(K.searchPalette(e, 'chart col').map((x) => x.id), ['bar']);
  assert.deepEqual(K.paletteGroups(e).map((g) => g.category + g.entries.length), ['Charts2', 'Numbers1']);
  assert.deepEqual(K.statusWords({ status: 'draft', version: 3, origin: 'item', dirty: true }), { word: 'Draft · unsaved', versions: 'approved v3 · a draft after it', tone: 'draft' });
  assert.equal(K.statusWords({ origin: 'file' }).word, 'Shipped');
  assert.deepEqual(K.statusWords({ text: 'Saved', status: 'published' }), { word: 'Saved', versions: '', tone: 'published' });     // a host's own words (BI Studio)
});

test('the bundle loads under Node as CommonJS', () => {
  const lib = require('../../web/static/elements/drishti-designer/drishti_designer.js');
  assert.equal(typeof lib.LayoutModel, 'function');
  assert.equal(typeof lib.YamlPane, 'function');
});
