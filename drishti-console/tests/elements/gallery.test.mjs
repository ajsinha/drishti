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
/* The gallery under Node, without a page: the generated catalogue (every library of the manifest with its version, options, events and
   changelog; the frozen lists equal to what the code exports; the planned libraries; the AG Grid mapping), the pure parts (option
   controls from JSDoc types, the code written from the mounted object, the AG Grid shapes) and the demo registry (every demo names a
   library, typedefs, events and modules the catalogue holds). The drawn gallery is tested in test_elements_gallery_browser.py.
   The libraries are reached by joined paths, so this file does not count as one of their own tests in the catalogue. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const STATIC = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'web', 'static');
const GALLERY = join(STATIC, 'gallery');
const cat = require(join(GALLERY, 'drishti_catalogue.js'));
const code = require(join(GALLERY, 'drishti_gallery-code.js'));
const manifest = JSON.parse(readFileSync(join(STATIC, 'elements', 'drishti_elements.json'), 'utf8'));
const lib = (n) => cat.libraries.find((l) => l.name === n);

test('the catalogue holds every library of the manifest, in its order, with its version, bundle, licence and tests', () => {
  assert.deepEqual(cat.libraries.map((l) => l.name), Object.keys(manifest.libraries));
  assert.equal(cat.elements, manifest.elements);
  cat.libraries.forEach((l) => {
    const m = manifest.libraries[l.name];
    assert.equal(l.version, m.version);
    assert.equal(l.server, m.module ? 'licence' : 'none', l.name);
    assert.ok(l.bundle.bytes > 1000 && l.bundle.lines > 10, l.name + ' bundle');
    assert.ok(l.modules.length > 0 && l.modules.every((x) => x.file.startsWith('drishti_')), l.name + ' modules');
    assert.ok(l.symbols.length > 0, l.name + ' symbols');
    assert.ok(l.tests.node > 0, l.name + ' has Node tests');
  });
  assert.equal(lib('drishti-risk').module, 'banking');
});

test('the options come from the typed JSDoc: names, types, defaults and words', () => {
  const grid = lib('drishti-grid');
  const gv = grid.types.find((t) => t.name === 'GridViewOptions');
  const layout = gv.props.find((p) => p.name === 'layout');
  assert.deepEqual([layout.type, layout.default, layout.optional], ["('wells'|'panel'|'terminal')", "'wells'", true]);
  assert.ok(gv.desc.indexOf('Everything GridModel takes') === 0, 'a typedef\'s words after its @typedef line are its description');
  const go = grid.types.find((t) => t.name === 'GridOptions');
  assert.equal(go.props.find((p) => p.name === 'columns').optional, false);
  const col = grid.types.find((t) => t.name === 'ColumnDef');
  assert.ok(col.props.some((p) => p.name === 'additive' && /VaR/.test(p.desc)));
  assert.ok(lib('drishti-chart').types.find((t) => t.name === 'ChartOptions').props.some((p) => p.name === 'mapMode' && p.default === "'ranking'"));
  assert.ok(lib('drishti-core').types.find((t) => t.name === 'NumberOptions'));
});

test('the frozen lists are what the code exports (events, layouts, modes)', () => {
  const G = require(join(STATIC, 'elements', 'drishti-grid', 'drishti_grid.js'));
  const C = require(join(STATIC, 'elements', 'drishti-chart', 'drishti_chart.js'));
  assert.deepEqual(lib('drishti-grid').constants.GRID_EVENTS, Array.from(G.GRID_EVENTS));
  assert.deepEqual(lib('drishti-grid').constants.LAYOUTS, Array.from(G.LAYOUTS));
  assert.deepEqual(lib('drishti-chart').constants.CHART_EVENTS, Array.from(C.CHART_EVENTS));
  assert.deepEqual(lib('drishti-chart').constants.MAP_MODES, ['ranking', 'table']);
});

test('the planned libraries, the AG Grid mapping and the changelog come from ELEMENTS.md and the CHANGELOG', () => {
  const built = new Set(cat.libraries.map((l) => l.name));
  assert.ok(cat.planned.length + built.size >= 8);
  cat.planned.forEach((p) => { assert.ok(!built.has(p.name)); assert.ok(['none', 'server', 'licence'].includes(p.server)); assert.ok(p.what.length > 10); });
  if (!built.has('drishti-insight')) { assert.equal(cat.planned.find((p) => p.name === 'drishti-insight').server, 'server'); }
  assert.equal(typeof cat.agGrid.built, 'boolean');
  assert.ok(cat.agGrid.mapping.length >= 15);
  assert.ok(cat.agGrid.mapping.some((r) => /pivotMode/.test(r.ag)));
  assert.ok(cat.agGrid.mapping.some((r) => /serverSide/.test(r.ag)) && cat.agGrid.mapping.some((r) => /treeData/.test(r.ag)));
  assert.ok(/master-detail/.test(cat.agGrid.notCovered));
  const log = lib('drishti-grid').changelog;
  assert.ok(log.some((c) => c.version === '0.2.0' && c.release === '2.9.11.e'));
  assert.ok(log.every((c) => c.text.indexOf('**') < 0 && !/\]\(/.test(c.text)), 'plain words: no bold, no links');
});

test('option controls from JSDoc types; defaults read', () => {
  assert.deepEqual(code.controlOf("('wells'|'panel'|'terminal')"), { kind: 'select', values: ['wells', 'panel', 'terminal'] });
  assert.deepEqual(code.controlOf("'a'|'b'"), { kind: 'select', values: ['a', 'b'] });
  assert.equal(code.controlOf('boolean').kind, 'switch');
  assert.equal(code.controlOf('number').kind, 'number');
  assert.equal(code.controlOf('string').kind, 'text');
  assert.equal(code.controlOf('function(Object): Promise<*>'), null);
  assert.equal(code.controlOf('{rows: string[]}'), null);
  assert.deepEqual([code.defaultValue("'wells'"), code.defaultValue('true'), code.defaultValue('640'), code.defaultValue(null)], ['wells', true, 640, undefined]);
  const opts = code.optionsOf(cat, 'drishti-grid', ['GridViewOptions', 'GridOptions']);
  assert.equal(opts.filter((o) => o.name === 'layout').length, 1, 'a name once, the first typedef wins');
  assert.equal(opts.find((o) => o.name === 'layout').from, 'GridViewOptions');
  assert.ok(opts.find((o) => o.name === 'clientAggregation').control.kind === 'switch');
});

test('the code is the mounted object: shared values by name, shorthand, both forms', () => {
  const rows = [{ ID: 'T1' }], explain = () => null;
  const names = new Map([[rows, 'rows'], [explain, 'explain']]);
  assert.equal(code.literal({ rows: rows, explain: explain, title: "Desk's", n: 3, on: true }, names), "{ rows, explain, title: 'Desk\\'s', n: 3, on: true }");
  assert.equal(code.literal({ a: undefined, b: null }), '{ b: null }');
  const long = code.literal({ columns: Array.from({ length: 8 }, (x, i) => ({ field: 'FIELD_' + i, header: 'A header ' + i })) });
  assert.ok(long.split('\n').length > 8, 'long values are laid out');
  const b = { tag: 'drishti-grid', ctor: 'Grid', library: 'drishti-grid', attrs: { layout: 'terminal', density: undefined }, options: { rows: rows, title: 'T' }, jsOptions: { layout: 'terminal' }, names: names, given: ['rows'] };
  const c = code.elementCode(b);
  assert.match(c.element, /^<drishti-grid layout="terminal"><\/drishti-grid>/);
  assert.match(c.element, /Drishti\.elements\.load\('drishti-grid'\)/);
  assert.match(c.element, /el\.options = \{ rows, title: 'T' \};/);
  assert.match(c.js, /import \{ Grid \} from '\/static\/elements\/drishti-grid\/src\/drishti_index\.js';/);
  assert.match(c.js, /new Grid\(document\.getElementById\('grid'\), \{ rows, title: 'T', layout: 'terminal' \}\);/);
  const chart = code.elementCode({ tag: 'drishti-chart', ctor: 'Chart', library: 'drishti-chart', spec: { kind: 'bar' }, options: {}, names: new Map(), given: [] });
  assert.match(chart.js, /new Chart\(document\.getElementById\('chart'\), \{ kind: 'bar' \}, \{\}\);/);
});

test('the AG Grid shapes of a pivot: row groups, pivot columns, aggregations, the server for non-additive measures', () => {
  const o = { columns: [{ field: 'DESK', header: 'Desk' }, { field: 'REGION', header: 'Region' }, { field: 'MTM', type: 'number' }, { field: 'VAR', type: 'number', additive: false }],
    pivot: { rows: ['DESK'], columns: ['REGION'], values: [{ colId: 'MTM', aggFunc: 'sum' }, { colId: 'VAR', aggFunc: 'sum' }] }, layout: 'panel' };
  const ag = code.agOptions(o, new Map());
  assert.match(ag, /field: 'DESK'[^}]*rowGroup: true[^}]*rowGroupIndex: 0/);
  assert.match(ag, /field: 'REGION'[^}]*pivot: true/);
  assert.match(ag, /field: 'MTM'[^}]*aggFunc: 'sum'/);
  assert.match(ag, /field: 'VAR'[^}]*aggFunc: 'drishti:server'/);
  assert.match(ag, /pivotMode: true/);
  assert.match(ag, /sideBar: \['columns', 'filters'\]/);
  assert.match(code.agOptions({ columns: [], datasource: {} }), /rowModelType: 'serverSide'/);
  assert.match(ag, /createGridFromAgOptions\(document\.getElementById\('grid'\)/);
  assert.ok(ag.indexOf('planned') < 0);
  assert.equal(cat.agGrid.built, true);
  assert.ok(cat.agGrid.mapping.every((r) => ['mapped', 'partial', 'unsupported', 'no-op'].includes(r.state)));
});

test('the AG Grid tab is live: the shapes it prints, given to the adapter, make the same pivot as the grid on the stage', async () => {
  const G = require(join(STATIC, 'elements', 'drishti' + '-grid', 'drishti_grid.js'));
  const rows = [{ ID: 'T1', DESK: 'Rates', REGION: 'EMEA', MTM: 5 }, { ID: 'T2', DESK: 'Rates', REGION: 'APAC', MTM: 7 }, { ID: 'T3', DESK: 'FX', REGION: 'EMEA', MTM: -2 }];
  const o = { columns: [{ field: 'ID' }, { field: 'DESK', header: 'Desk' }, { field: 'REGION', header: 'Region' }, { field: 'MTM', header: 'MTM', type: 'number' }], rows: rows,
    getRowId: (r) => r.ID, pivot: { rows: ['DESK'], columns: ['REGION'], values: [{ colId: 'MTM', aggFunc: 'sum' }] }, layout: 'panel' };
  const s = code.agShape(o, new Map(), { grandTotalRow: 'bottom' });
  const api = G.createGridFromAgOptions(null, s.options, { out: { warn: () => {}, info: () => {} } });
  await api.ready;
  const own = G.createGridModel(o);
  own.wells.setState(Object.assign({ filters: [] }, o.pivot));
  own.pivotState.expandToLevel('rows', 2);
  await own.setPivotMode(true);
  assert.deepEqual(JSON.parse(JSON.stringify(api.model.answer.cells)), JSON.parse(JSON.stringify(own.answer.cells)));
  assert.equal(api.model.layout, 'panel');
  assert.equal(api.model.rowModel.getRowId(rows[1], 1), 'T2');
  assert.deepEqual(api.reportUnsupported(), []);
});

test('every demo names what the catalogue holds: its library, typedefs, events and modules', () => {
  globalThis.Drishti = undefined;
  ['drishti_gallery-demos.js', 'drishti_gallery-demos-chart.js', 'drishti_gallery-demos-inputs.js', 'drishti_gallery-demos-graph.js', 'drishti_gallery-demos-tree.js'].forEach((f) => require(join(GALLERY, f)));
  const demos = globalThis.Drishti.gallery.demos;
  const ids = demos.map((d) => d.id);
  assert.equal(new Set(ids).size, ids.length, 'ids are unique');
  const planned = new Set(cat.planned.map((p) => p.name));
  const everyModule = new Set([].concat(...cat.libraries.map((l) => l.modules.map((m) => m.file))));
  const everyConstant = new Set([].concat(...cat.libraries.map((l) => Object.keys(l.constants))));
  let shown = 0;
  demos.forEach((d) => {
    assert.ok(lib(d.library) || planned.has(d.library), d.id + ': ' + d.library);
    assert.ok(d.title && d.summary && typeof d.mount === 'function', d.id);
    assert.ok(['element', 'function'].includes(d.kind), d.id);
    if (!lib(d.library)) { return; }
    shown++;
    const types = lib(d.typesFrom || d.library).types.map((t) => t.name);
    (d.types || []).forEach((t) => assert.ok(types.includes(t), d.id + ' names typedef ' + t));
    (d.events || []).forEach((c) => assert.ok(everyConstant.has(c), d.id + ' listens to ' + c));
    [d.about].concat(d.keys || []).filter(Boolean).forEach((m) => assert.ok(everyModule.has(m), d.id + ' names module ' + m));
    (d.examples || []).forEach((ex) => assert.ok(ex.title && ex.values, d.id + ' example'));
    if (d.kind === 'element') { assert.ok(typeof d.build === 'function', d.id + ' builds the object it mounts and prints'); }
  });
  ['drishti-core', 'drishti-grid', 'drishti-chart', 'drishti-graph', 'drishti-tree', 'drishti-risk'].forEach((l) => assert.ok(demos.some((d) => d.library === l), l + ' has demos'));
  assert.ok(shown >= 20);
});

test('the ARIA smoke check is exported with its parts (drawn checks run in the browser file)', () => {
  assert.equal(typeof code.audit, 'function');
  assert.equal(code.sizeText(98167), '95.9 KB');
  assert.deepEqual(code.codeSpans('a `b` c'), [{ code: false, text: 'a ' }, { code: true, text: 'b' }, { code: false, text: ' c' }]);
});
