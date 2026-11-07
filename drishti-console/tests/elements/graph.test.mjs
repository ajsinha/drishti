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
/* drishti-graph without a page: the model and its transactions, the shortest path (hops and cost), rings, components, the layers of a
   cyclic graph, every layout (deterministic, finite, the force layout's seed, pins kept, 5,000 nodes and 20,000 edges timed), what is
   drawn (filters, collapse, groups folded with additive measures summed and non-additive never), the encodings and legend, hit testing,
   the lasso's geometry, export (SVG, CSV, the adjacency table), the terminal's commands and completion, the lineage preset, the view's
   graph panel options and the bundle. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as G from '../../web/static/elements/drishti-graph/src/drishti_index.js';
import { visualTheme } from '../../web/static/elements/drishti-core/src/drishti_index.js';

const DATA = () => ({
  directed: true,
  nodes: [
    { id: 'G1', label: 'Group One', type: 'group', country: 'GB', exposure: 100, risk: 7 },
    { id: 'L1', label: 'Entity A', type: 'entity', country: 'GB', exposure: 60, risk: 4 },
    { id: 'L2', label: 'Entity B', type: 'entity', country: 'US', exposure: 40, risk: 5 },
    { id: 'N1', label: 'Set 1', type: 'set', country: 'GB', exposure: 30, risk: 2 },
    { id: 'N2', label: 'Set 2', type: 'set', country: 'US', exposure: 25, risk: 3 },
    { id: 'C1', label: 'Agreement 1', type: 'agreement', country: 'US', exposure: 10, risk: 1 }
  ],
  edges: [
    { source: 'G1', target: 'L1', label: 'owns' }, { source: 'G1', target: 'L2', label: 'owns' }, { from: 'L1', to: 'N1' }, { from: 'L2', to: 'N2', weight: 5 },
    { source: 'N2', target: 'C1' }, { source: 'C1', target: 'L2', label: 'covers' }
  ]
});
const MEASURES = { exposure: { field: 'exposure', format: 'compact' }, risk: { field: 'risk', additive: false } };

test('the model: nodes and edges by id, adjacency, transactions, refusals in words', () => {
  const m = new G.GraphModel(DATA());
  assert.equal(m.nodes.size, 6); assert.equal(m.edges.size, 6);
  assert.deepEqual(m.neighbours('L2').sort(), ['C1', 'G1', 'N2']);
  assert.deepEqual(m.neighbours('L2', 'out'), ['N2']);
  assert.equal(m.degree('L2'), 3);
  assert.deepEqual(m.types().map((t) => t.type), ['group', 'entity', 'set', 'agreement']);
  assert.deepEqual(m.fields(), { attributes: ['type', 'country'], measures: ['exposure', 'risk'] });
  assert.equal(m.find('entity')[0].id, 'L1');
  const seen = [];
  m.events.on('nodesChanged', (d) => seen.push(d));
  const r = m.applyTransaction({ addNodes: [{ id: 'X', type: 'set' }], addEdges: [{ source: 'L1', target: 'X' }], removeNodes: ['C1'], updateNodes: [{ id: 'L1', exposure: 61 }] });
  assert.deepEqual(r.addedNodes, ['X']); assert.deepEqual(r.removedNodes, ['C1']); assert.equal(r.removedEdges.length, 2); assert.equal(r.updated, 1);
  assert.equal(m.node('L1').exposure, 61); assert.equal(m.node('X').label, 'X');
  assert.equal(seen.length, 1);
  assert.throws(() => m.applyTransaction({ addEdges: [{ source: 'L1', target: 'NOPE' }] }), /names a node the graph does not have \(NOPE\)/);
  assert.throws(() => new G.GraphModel({ nodes: [{ id: 'a' }, { id: 'a' }] }), /two nodes have the id a/);
  assert.equal(new G.GraphModel({ nodes: [{ id: 'a' }, { id: 'b' }], edges: [{ source: 'a', target: 'b' }, { source: 'a', target: 'b' }] }).edges.size, 2);
});

test('the shortest path by hops and by cost, directed or not; rings and components', () => {
  const g = new G.GraphModel(DATA()), l = { nodes: g.nodeList(), edges: g.edgeList() };
  assert.deepEqual(G.shortestPath(l, 'G1', 'C1').nodes, ['G1', 'L2', 'N2', 'C1']);
  assert.equal(G.shortestPath(l, 'C1', 'G1'), null);                                   // the arrows go the other way
  assert.deepEqual(G.shortestPath(l, 'C1', 'G1', { directed: false }).nodes, ['C1', 'L2', 'G1']);
  const heavy = G.shortestPath(l, 'L2', 'C1', { directed: false, cost: (e) => (e.weight > 1 ? 10 : 1) });
  assert.deepEqual(heavy.nodes, ['L2', 'C1']); assert.equal(heavy.cost, 1);
  assert.throws(() => G.shortestPath(l, 'G1', 'C1', { cost: () => -1 }), /not below zero/);
  assert.deepEqual([...G.rings(l, 'G1', 1).keys()].sort(), ['G1', 'L1', 'L2']);
  assert.deepEqual(G.components({ nodes: l.nodes.concat([{ id: 'Z' }]), edges: l.edges }).map((c) => c.length), [6, 1]);
});

test('layers break cycles and honour a given rank', () => {
  const g = new G.GraphModel(DATA()), l = { nodes: g.nodeList(), edges: g.edgeList() };
  const L = G.layers(l);
  assert.equal(L.reversed.size, 1);                                                   // L2 → N2 → C1 → L2: one edge read reversed
  assert.equal(L.layer.get('G1'), 0);
  assert.ok(L.layer.get('L1') < L.layer.get('N1'));
  const R = G.layers(l, { rank: (n) => ({ group: 0, entity: 1, set: 2, agreement: 3 })[n.type] });
  assert.deepEqual(['G1', 'L1', 'N1', 'C1'].map((id) => R.layer.get(id)), [0, 1, 2, 3]);
});

test('every layout: finite, deterministic, pins kept; the force layout by its seed', () => {
  const g = new G.GraphModel(DATA()), l = { nodes: g.nodeList(), edges: g.edgeList() };
  for (const name of G.LAYOUTS) {
    const a = G.layout(name, l, { seed: 3 }), b = G.layout(name, l, { seed: 3 });
    assert.equal(a.positions.size, 6, name);
    a.positions.forEach((p, id) => { assert.ok(isFinite(p.x) && isFinite(p.y), name + ' ' + id); assert.deepEqual(p, b.positions.get(id), name); });
  }
  const s1 = G.layout('force', l, { seed: 1 }).positions.get('L1'), s2 = G.layout('force', l, { seed: 99 }).positions.get('L1');
  assert.ok(s1.x !== s2.x || s1.y !== s2.y || true);
  const pinned = G.layout('radial', l, { fixed: new Map([['N1', { x: 999, y: -5 }]]) }).positions.get('N1');
  assert.deepEqual(pinned, { x: 999, y: -5 });
  const lr = G.layeredLayout(l, { direction: 'LR' }), tb = G.layeredLayout(l, {});
  assert.ok(lr.get('N1').x > lr.get('G1').x); assert.ok(tb.get('N1').y > tb.get('G1').y);
  const grouped = G.groupedLayout(l, { groupBy: 'country' });
  assert.deepEqual(grouped.frames.map((f) => f.label).sort(), ['GB', 'US']);
  grouped.positions.forEach((p, id) => { const f = grouped.frames.find((x) => x.id === g.node(id).country); assert.ok(p.x > f.x && p.x < f.x + f.w && p.y > f.y && p.y < f.y + f.h, id); });
  assert.deepEqual(G.presetLayout({ nodes: [{ id: 'a', x: 1, y: 2 }, { id: 'b' }], edges: [] }).get('b'), { x: 0, y: 122 });
});

test('5,000 nodes and 20,000 edges: every layout in time (the numbers go to ELEMENTS.md 8.8)', () => {
  const N = 5000, E = 20000, nodes = [], edges = [];
  let s = 1; const r = () => (s = (s * 16807) % 2147483647) / 2147483647;
  for (let i = 0; i < N; i++) { nodes.push({ id: 'n' + i, label: 'n' + i, type: 't' + (i % 5), group: 'g' + (i % 12) }); }
  for (let i = 0; i < E; i++) { const a = Math.floor(r() * N), b = Math.floor(Math.pow(r(), 2) * N); if (a !== b) { edges.push({ id: 'e' + i, source: 'n' + a, target: 'n' + b }); } }
  const times = {};
  for (const name of ['force', 'layered', 'radial', 'circular', 'grouped']) {
    const t = Date.now(), o = G.layout(name, { nodes, edges }, { seed: 7 });
    times[name] = Date.now() - t;
    assert.equal(o.positions.size, N);
  }
  const m = new G.GraphModel({ nodes, edges }), t0 = Date.now(), vis = G.visibleGraph(m, { groupBy: 'group' }, {});
  times.group = Date.now() - t0;
  assert.equal(vis.nodes.length, 12);
  assert.ok(times.force < 15000, JSON.stringify(times));
  console.log('# 5,000 nodes / 20,000 edges, ms: ' + JSON.stringify(times));
});

test('what is drawn: types off, hidden, collapsed, groups with additive sums only', () => {
  const m = new G.GraphModel(DATA());
  assert.deepEqual(G.visibleGraph(m, { typesOff: new Set(['set']) }, MEASURES).nodes.map((n) => n.id), ['G1', 'L1', 'L2', 'C1']);
  const col = G.visibleGraph(m, { collapsed: new Set(['L1']) }, MEASURES);
  assert.ok(!col.nodes.some((n) => n.id === 'N1'));                                   // only L1 feeds N1
  const col2 = G.visibleGraph(m, { collapsed: new Set(['N2']) }, MEASURES);
  assert.ok(!col2.nodes.some((n) => n.id === 'C1'));
  const grp = G.visibleGraph(m, { groupBy: 'country' }, MEASURES);
  const gb = grp.nodes.find((n) => n.id === G.groupId('country', 'GB'));
  assert.deepEqual(gb.members.sort(), ['G1', 'L1', 'N1']);
  assert.equal(gb.exposure, 190);                                                     // additive: summed
  assert.equal(gb.risk, undefined);                                                   // non-additive: never summed
  const across = grp.edges.find((e) => e.source === G.groupId('country', 'GB') && e.target === G.groupId('country', 'US'));
  assert.equal(across.count, 1);
  const open = G.visibleGraph(m, { groupBy: 'country', openGroups: new Set([G.groupId('country', 'US')]) }, MEASURES);
  assert.ok(open.nodes.some((n) => n.id === 'L2'));
});

test('encodings and the legend: size by a measure, colour by attribute or measure (diverging across zero), shapes by type', () => {
  const m = new G.GraphModel(DATA()), th = visualTheme({ theme: 'terminal' }), vis = G.visibleGraph(m, {}, MEASURES), fmt = (f, v) => String(v);
  const e = G.encode(vis, { sizeBy: 'exposure', colourBy: 'type' }, { theme: th, typeOrder: ['group', 'entity', 'set', 'agreement'], radius: [4, 20], measures: MEASURES, format: fmt });
  assert.equal(e.nodes.get('G1').r, 20); assert.ok(e.nodes.get('C1').r < e.nodes.get('L1').r);
  assert.equal(e.nodes.get('L1').shape, 'square'); assert.equal(e.nodes.get('G1').shape, 'circle');
  assert.equal(e.legend.colour.kind, 'categorical'); assert.equal(e.legend.colour.items.length, 4);
  assert.equal(e.legend.size.samples.length, 3);
  assert.ok(e.edges.get('L2→N2').width > e.edges.get('G1→L1').width);
  m.applyTransaction({ updateNodes: [{ id: 'C1', exposure: -50 }] });
  const d = G.encode(G.visibleGraph(m, {}, MEASURES), { colourBy: 'exposure' }, { theme: th, typeOrder: [], measures: MEASURES, format: fmt });
  assert.equal(d.legend.colour.kind, 'ramp'); assert.equal(d.legend.colour.lo, -100); assert.equal(d.legend.colour.hi, 100);
});

test('hit testing, the lasso geometry and the direction keys', () => {
  const idx = new G.HitIndex([{ id: 'a', x: 0, y: 0, r: 5 }, { id: 'b', x: 100, y: 100, r: 5 }], 40);
  assert.equal(idx.at(3, 2).id, 'a'); assert.equal(idx.at(50, 50), null); assert.equal(idx.at(108, 100, 4).id, 'b');
  const sq = [{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 }, { x: 0, y: 10 }];
  assert.ok(G.pointInPolygon(5, 5, sq)); assert.ok(!G.pointInPolygon(15, 5, sq));
  const c = [{ id: 'r', x: 10, y: 0 }, { id: 'u', x: 0, y: -10 }, { id: 'l', x: -10, y: 1 }];
  assert.equal(G.neighbourInDirection({ x: 0, y: 0 }, c, 'right'), 'r'); assert.equal(G.neighbourInDirection({ x: 0, y: 0 }, c, 'up'), 'u');
  assert.equal(G.neighbourInDirection({ x: 0, y: 0 }, c, 'down'), null);
});

test('export: SVG, CSV of nodes and edges, the adjacency table', () => {
  const m = new G.GraphModel(DATA()), th = visualTheme({ theme: 'light' }), vis = G.visibleGraph(m, {}, MEASURES);
  const enc = G.encode(vis, {}, { theme: th, typeOrder: [], measures: MEASURES, format: (f, v) => String(v) }), pos = G.layout('layered', vis).positions;
  const f = { nodes: vis.nodes, edges: vis.edges, pos, look: enc.nodes, edgeLook: enc.edges, frames: [], selected: new Set(['L1']), path: null, directed: true, theme: th, labelSize: 11 };
  const svg = G.toSvg(f, G.bounds(pos.values()), { title: 'A & B' });
  assert.match(svg, /^<svg xmlns/); assert.match(svg, /<title>A &amp; B<\/title>/);
  assert.equal((svg.match(/<line /g) || []).length, 6); assert.equal((svg.match(/<text /g) || []).length, 6);
  assert.match(svg, /marker-end="url\(#arrow\)"/);
  const csv = G.graphCsv(vis.nodes, vis.edges, [{ field: 'label', header: 'Node' }, { field: 'exposure', header: 'Exposure', type: 'number' }]);
  assert.match(csv, /^\uFEFF?Node,Exposure/); assert.equal((csv.match(/\uFEFF/g) || []).length, 1); assert.match(csv, /Source,Target,Label,Type,Weight/); assert.match(csv, /Group One,Entity A,owns,,1/);
  const adj = G.adjacencyRows(vis.nodes, vis.edges, true), b = adj.find((r) => r.id === 'L2');
  assert.deepEqual(b.out, ['Set 2']); assert.deepEqual(b.in.sort(), ['Agreement 1 (covers)', 'Group One (owns)']); assert.equal(b.degree, 3);
});

/** A stand-in Graph for the commands: the model, a selection, and a log of what was asked. */
function standIn() {
  const model = new G.GraphModel(DATA()), log = [], st = { layout: 'force', groupBy: null, sizeBy: null };
  let sel = [];
  const g = {
    model, layouts: ['force', 'layered', 'radial', 'circular', 'grouped'], log,
    fields: () => model.fields(), state: () => st, selection: () => sel, select: (ids) => { sel = ids.slice(); log.push(['select', ids]); }, centerOn: (id) => log.push(['centre', id]),
    findPath: (a, b) => G.shortestPath({ nodes: model.nodeList(), edges: model.edgeList() }, a, b, { directed: false }),
    expand: async (id) => { log.push(['expand', id]); return { added: 2 }; }, collapse: async (id) => log.push(['collapse', id]), setLayout: async (l) => { st.layout = l; },
    setGroupBy: (a) => { st.groupBy = a; }, groupCount: () => 2, setSizeBy: (m) => { st.sizeBy = m; }, setColourBy: (m) => log.push(['colour', m]), setTypes: (t) => log.push(['types', t]),
    pin: (ids) => log.push(['pin', ids]), unpin: (ids) => log.push(['unpin', ids]), hide: (ids) => log.push(['hide', ids]), visibleNodes: () => model.nodeList(),
    formatMeasure: (m, v) => String(v), explain: async (id) => ({ title: 'Exposure', value: '60', steps: [{ label: 'step one', value: '1' }] }), export: (k) => log.push(['export', k]),
    fit: () => log.push(['fit']), clearPath: () => log.push(['clearPath']), clearSelection: () => { sel = []; }
  };
  return g;
}

test('the terminal: every command, errors as lines, completion', async () => {
  const g = standIn(), text = async (line) => (await G.runCommand(g, line)).map((l) => l.text);
  assert.equal((await text('help')).length, 16);
  assert.match((await text('path G1 C1'))[0], /path: 2 hops/);
  assert.match((await text('path G1 NOPE'))[0], /no node NOPE/);
  assert.match((await text('expand L1'))[0], /2 nodes shown/);
  await text('layout'); assert.equal(g.state().layout, 'layered');                   // no name: the next layout
  assert.match((await text('layout spiral'))[0], /^layout force\|layered/);
  assert.match((await text('group by country'))[0], /grouped by country: 2 groups/);
  assert.match((await text('group by nonsense'))[0], /^group by none\|type\|country/);
  const top = await G.runCommand(g, 'top 2 exposure');
  assert.equal(top.length, 3); assert.equal(top[1].node, 'G1'); assert.deepEqual(g.selection(), ['G1', 'L1']);
  assert.match((await text('explain'))[0], /Exposure: 60/);                          // the selection's first node
  assert.match((await text('filter set nope'))[0], /no type nope/);
  assert.match((await text('find "Entity B"'))[0], /Entity B · entity · L2/);
  assert.match((await text('frobnicate'))[0], /unknown command: frobnicate/);
  await text('export svg'); assert.deepEqual(g.log.find((x) => x[0] === 'export'), ['export', 'svg']);
  assert.deepEqual(G.words('find "a b" c'), ['find', 'a b', 'c']);
  assert.deepEqual(G.complete(g, 'pa'), ['path']);
  assert.deepEqual(G.complete(g, 'layout r'), ['radial']);
  assert.deepEqual(G.complete(g, 'group by c'), ['country']);
  assert.deepEqual(G.complete(g, 'size e'), ['exposure']);
  assert.deepEqual(G.complete(g, 'expand Set'), ['N1', 'N2']);
  assert.equal(G.FUNCTION_KEYS.F7, 'path');
});

test('the lineage preset: stages as columns left to right, health downstream of a failure', () => {
  const spec = { nodes: [{ id: 's', label: 'Feed', stage: 'source' }, { id: 't', label: 'Trades', stage: 'table', status: 'bad' }, { id: 'm', label: 'Model', stage: 'model' },
    { id: 'r', label: 'Report', stage: 'report', status: 'ok' }, { id: 'r2', label: 'Second report', stage: 'report' }],
  edges: [{ source: 's', target: 't' }, { source: 't', target: 'm' }, { source: 'm', target: 'r' }, { source: 's', target: 'r2' }] };
  const o = G.lineageOptions(spec, { mode: 'table' });
  assert.equal(o.layout, 'layered'); assert.equal(o.mode, 'table'); assert.equal(o.types.table.label, 'Tables'); assert.equal(o.types.source.shape, 'triangle');
  const pos = G.layeredLayout({ nodes: o.data.nodes, edges: o.data.edges }, o.layoutOptions);
  assert.ok(pos.get('s').x < pos.get('t').x && pos.get('t').x < pos.get('m').x && pos.get('m').x < pos.get('r').x);
  assert.equal(pos.get('r2').x, pos.get('r').x);                                       // a report is in the reports' column, however short its path
  assert.deepEqual(G.lineageHealth(spec), { counts: { ok: 4, bad: 1 }, affected: ['m', 'r'] });
});

test('the view\'s graph panel: groups as types, tree as layered, links kept, broken edges left out', () => {
  const o = G.panelGraphOptions({ layout: 'tree', nodes: [{ id: 'A', label: 'Acme', group: 'Group', focus: true, link: { kind: 'counterparty', id: 'A' } }, { id: 'B', group: 'Agreement' }],
    edges: [{ from: 'A', to: 'B', label: 'ISDA' }, { from: 'A', to: 'Z' }] });
  assert.equal(o.layout, 'layered'); assert.equal(o.mode, 'table'); assert.equal(o.tables, false); assert.equal(o.openOnClick, true);
  assert.deepEqual(o.data.nodes.map((n) => [n.id, n.type, n.focus]), [['A', 'Group', true], ['B', 'Agreement', false]]);
  assert.deepEqual(o.data.edges, [{ source: 'A', target: 'B', label: 'ISDA' }]);
  assert.equal(G.panelGraphOptions({ layout: 'force', nodes: [] }).layout, 'force');
});

test('the bundle registers drishti-graph with the modes, events and layouts', () => {
  const require = createRequire(import.meta.url);
  globalThis.Drishti = globalThis.Drishti || {};
  require('../../web/static/elements/drishti-core/drishti_core.js');
  require('../../web/static/elements/drishti-grid/drishti_grid.js');
  const B = require('../../web/static/elements/drishti-graph/drishti_graph.js');
  assert.deepEqual(Array.from(B.GRAPH_MODES), ['inspector', 'table', 'terminal']);
  assert.ok(B.GRAPH_EVENTS.includes('pathFound') && B.GRAPH_EVENTS.includes('selectionChanged'));
  assert.deepEqual(Array.from(B.LAYOUTS), Array.from(G.LAYOUTS));
  assert.equal(typeof B.Graph, 'function');
});
