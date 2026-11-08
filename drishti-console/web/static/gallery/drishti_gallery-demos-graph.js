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
/* The gallery's demos of drishti-graph (every mode: inspector, table and terminal; every layout; the lineage preset; a grid and a graph
   linked through the bus) and of drishti-risk's counterparty structure preset. The networks are synthetic and made here (a company's
   services calling each other, with teams, regions, request rates and errors); a stand-in server answers Explain and expands a node's
   neighbours on demand. The options panel is made from GraphOptions' JSDoc; the event log listens to GRAPH_EVENTS. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const SCALARS = ['mode', 'density', 'layout', 'title', 'groupBy', 'sizeBy', 'colourBy', 'minimap', 'animate', 'height'];

  function typed(values, names) {
    const o = {};
    names.forEach((n) => { if (values[n] !== undefined && values[n] !== '') { o[n] = values[n]; } });
    return o;
  }

  /** Services calling services: gateways, services, stores and queues of six teams in three regions (deterministic). */
  function services(ctx) {
    return ctx.memo('graph-services', () => {
      let seed = 9;
      const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
      const TEAMS = ['Accounts', 'Search', 'Payments', 'Catalogue', 'Delivery', 'Identity'], REGIONS = ['east', 'west', 'central'];
      const nodes = [], edges = [];
      TEAMS.forEach((team, t) => {
        nodes.push({ id: 'gw-' + t, label: team + ' gateway', type: 'gateway', team, region: REGIONS[t % 3], rps: Math.round(800 + rnd() * 4000), errors: Math.round(rnd() * 40) / 10 });
        for (let s = 0; s < 6; s++) {
          const id = 'svc-' + t + '-' + s;
          nodes.push({ id, label: team + ' service ' + (s + 1), type: 'service', team, region: REGIONS[(t + s) % 3], rps: Math.round(100 + rnd() * 2000), errors: Math.round(rnd() * 60) / 10, latency: Math.round(5 + rnd() * 240) });
          edges.push({ source: 'gw-' + t, target: id, weight: Math.round(1 + rnd() * 9) });
          if (s % 2 === 0) { const st = 'db-' + t + '-' + s; nodes.push({ id: st, label: team + ' store ' + (s / 2 + 1), type: 'store', team, region: REGIONS[(t + s) % 3], size: Math.round(rnd() * 900) }); edges.push({ source: id, target: st, label: 'reads', weight: 3 }); }
          if (s % 3 === 1) { const q = 'q-' + t + '-' + s; nodes.push({ id: q, label: team + ' queue ' + s, type: 'queue', team, region: REGIONS[t % 3], depth: Math.round(rnd() * 5000) }); edges.push({ source: id, target: q, label: 'publishes', weight: 2 }); }
        }
      });
      for (let i = 0; i < 40; i++) { const a = Math.floor(rnd() * 6), b = (a + 1 + Math.floor(rnd() * 5)) % 6; edges.push({ source: 'svc-' + a + '-' + Math.floor(rnd() * 6), target: 'svc-' + b + '-' + Math.floor(rnd() * 6), label: 'calls', weight: Math.round(1 + rnd() * 6) }); }
      const seen = new Set();
      const data = { directed: true, nodes, edges: edges.filter((e) => { const k = e.source + '>' + e.target; if (seen.has(k) || e.source === e.target) { return false; } seen.add(k); return true; }) };
      const types = { gateway: { label: 'Gateways', shape: 'hexagon', colour: 'accent' }, service: { label: 'Services', shape: 'circle', colour: 'link' }, store: { label: 'Stores', shape: 'square', colour: 'good' }, queue: { label: 'Queues', shape: 'diamond', colour: 'warn' } };
      const measures = [{ field: 'rps', label: 'Requests/s', format: 'integer' }, { field: 'errors', label: 'Error rate %', format: 'number', additive: false }, { field: 'latency', label: 'p99 ms', format: 'integer', additive: false },
        { field: 'size', label: 'Size GB', format: 'integer' }, { field: 'depth', label: 'Depth', format: 'integer' }];
      let extra = 0;
      const expand = (n) => new Promise((res) => setTimeout(() => { const more = [1, 2].map((i) => ({ id: n.id + '/c' + (++extra), label: 'Client ' + extra, type: 'service', team: n.team, region: n.region, rps: 50 * i })); res({ nodes: more, edges: more.map((x) => ({ source: x.id, target: n.id, label: 'calls' })) }); }, 30));
      const explain = (c) => ({ title: (c.node.rps !== undefined ? 'Requests/s of ' : '') + c.label, value: c.node.rps !== undefined ? String(c.node.rps) : c.type, scope: (c.node.team || '') + ' · ' + (c.node.region || ''),
        steps: [{ label: 'Calls in', value: String(c.node.rps || 0) }, { label: 'Error rate (the server\'s, not summed)', value: String(c.node.errors || 0) + '%' }], note: 'The gallery\'s stand-in server.' });
      return { data, types, measures, expand, explain };
    });
  }

  function mountGraph(host, b) {
    const el = host.ownerDocument.createElement(b.tag);
    Object.keys(b.attrs || {}).forEach((k) => { if (b.attrs[k] !== undefined && b.attrs[k] !== null && b.attrs[k] !== '') { el.setAttribute(k, String(b.attrs[k])); } });
    host.appendChild(el);
    el.options = b.options;
    return { target: el, element: el, ready: Promise.resolve(), destroy: () => el.remove() };
  }

  function graphDemo(spec) {
    return Object.assign({
      library: 'drishti-graph', kind: 'element', about: 'drishti_graph-view.js', types: ['GraphOptions'], events: ['GRAPH_EVENTS'], keys: ['drishti_graph-pointer.js'], ag: false,
      mount: function (host, values, ctx) { return mountGraph(host, this.build(values, ctx)); },
      sutra: () => null
    }, spec);
  }

  function servicesBuild(values, ctx, more) {
    const s = services(ctx);
    const o = Object.assign({ types: s.types, measures: s.measures, explain: s.explain, expand: s.expand, download: ctx.download, link: { channel: 'gallery-graph' } },
      typed(values, SCALARS.filter((n) => n !== 'mode' && n !== 'density' && n !== 'layout')), more);
    const names = new Map([[s.data, 'data'], [s.types, 'types'], [s.measures, 'measures'], [s.explain, 'explain'], [s.expand, 'expand'], [ctx.download, 'download']]);
    return { tag: 'drishti-graph', ctor: 'Graph', library: 'drishti-graph', attrs: { mode: values.mode, density: values.density, layout: values.layout }, data: s.data, options: Object.assign({ data: s.data }, o),
      jsOptions: typed(values, ['mode', 'density', 'layout']), names, given: Array.from(new Set(names.values())) };
  }

  demos.push(graphDemo({
    id: 'graph', title: 'Graph', summary: 'Networks, cyclic or acyclic, on a canvas for thousands of nodes: force (seeded), layered, radial, circular and grouped layouts; zoom, pan, minimap; expand on demand; the shortest path; groups by an attribute; size and colour by a measure; pin, lasso; Explain; three modes of one component (g5).',
    values: { mode: 'inspector', density: 'compact', layout: 'force', title: 'Services (synthetic)', sizeBy: 'rps', height: 460 },
    examples: [{ title: 'Canvas and inspector (g5 A, the default)', values: {} }, { title: 'Canvas over the linked tables (g5 B)', values: { mode: 'table' } }, { title: 'Terminal with the GRAPH> line (g5 C)', values: { mode: 'terminal', density: 'terminal' } },
      { title: 'Layered (a DAG\'s layers, cycles broken)', values: { layout: 'layered' } }, { title: 'Radial around the busiest node', values: { layout: 'radial' } }, { title: 'Grouped by team', values: { layout: 'grouped', groupBy: '' } },
      { title: 'Folded into teams', values: { groupBy: 'team' } }, { title: 'Coloured by error rate', values: { colourBy: 'errors' } }],
    build: function (values, ctx) { return servicesBuild(values, ctx); }
  }));

  demos.push(graphDemo({
    id: 'graph-lineage', title: 'Graph: lineage', about: 'drishti_graph-lineage.js', summary: 'The lineage DAG, a preset of the layered layout: sources → tables → models → reports, left to right, each stage a column, status badges on the nodes (ok, stale, failed, running).',
    values: { mode: 'inspector', density: 'compact', title: 'Lineage (synthetic)', height: 420 },
    examples: [{ title: 'The lineage', values: {} }, { title: 'Over its tables', values: { mode: 'table' } }],
    build: function (values, ctx) {
      const spec = ctx.memo('graph-lineage', () => ({ title: 'Lineage (synthetic)',
        nodes: [{ id: 's0', label: 'Orders feed', stage: 'source' }, { id: 's1', label: 'Prices feed', stage: 'source', status: 'stale' }, { id: 's2', label: 'Reference data', stage: 'source' },
          { id: 't0', label: 'ORDERS', stage: 'table' }, { id: 't1', label: 'PRICES', stage: 'table' }, { id: 't2', label: 'CUSTOMERS', stage: 'table' }, { id: 't3', label: 'POSITIONS', stage: 'table', status: 'bad' },
          { id: 'm0', label: 'Sales model', stage: 'model' }, { id: 'm1', label: 'Finance model', stage: 'model' },
          { id: 'r0', label: 'Daily sales', stage: 'report' }, { id: 'r1', label: 'Board pack', stage: 'report' }, { id: 'r2', label: 'Desk dashboard', stage: 'report', status: 'running' }],
        edges: [['s0', 't0'], ['s1', 't1'], ['s2', 't2'], ['t0', 't3'], ['t1', 't3'], ['t3', 'm0'], ['t2', 'm0'], ['t0', 'm1'], ['m0', 'r0'], ['m0', 'r2'], ['m1', 'r1'], ['t2', 'r1']].map(([a, b]) => ({ source: a, target: b })) }));
      const o = ctx.E.graph.lineageOptions(spec, Object.assign({ download: ctx.download }, typed(values, ['title', 'height'])));
      const names = new Map([[o.data, 'data'], [ctx.download, 'download']]);
      return { tag: 'drishti-graph', ctor: 'Graph', library: 'drishti-graph', attrs: { mode: values.mode, density: values.density }, data: o.data, options: o, jsOptions: typed(values, ['mode', 'density']), names, given: Array.from(new Set(names.values())) };
    },
    code: function (values) { return presetCode('lineageOptions', 'drishti-graph', 'spec', values); }
  }));

  /** The code of a demo made by a preset: the preset's call, then the graph. */
  function presetCode(fn, lib, args, values) {
    const o = JSON.stringify(typed(values, ['mode', 'density']));
    const imp = "import { " + fn + " } from '/static/elements/" + lib + "/src/drishti_index.js';";
    return {
      element: ['<drishti-graph' + (values.mode ? ' mode="' + values.mode + '"' : '') + '></drishti-graph>', '<script type="module">', imp,
        "import '/static/elements/drishti-graph/src/drishti_index.js';", '// ' + args + ': your data',
        "document.querySelector('drishti-graph').options = " + fn + '(' + args + ');', '</script>'].join('\n'),
      js: ["import { Graph } from '/static/elements/drishti-graph/src/drishti_index.js';", imp, '', '// ' + args + ': your data',
        "const graph = new Graph(document.getElementById('graph'), " + fn + '(' + args + ', ' + o + '));'].join('\n')
    };
  }

  demos.push(graphDemo({
    id: 'graph-linked', title: 'Graph and grid, linked', about: 'drishti_link.js', summary: 'A drishti-grid and a graph on the same channel of drishti-core\'s bus: select nodes, the grid selects their rows; select rows, the graph selects the nodes.',
    values: { mode: 'inspector', density: 'compact', height: 420 },
    examples: [{ title: 'Services: a grid and the graph', values: {} }],
    build: function (values, ctx) { return servicesBuild(values, ctx, { tables: false }); },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, s = services(ctx);
      const wrap = ctx.h(doc, 'div', { cls: 'gx-linked' });
      host.appendChild(wrap);
      const m = mountGraph(wrap, this.build(values, ctx));
      const g = doc.createElement('drishti-grid');
      g.setAttribute('density', values.density || 'compact');
      wrap.appendChild(g);
      g.options = { columns: [{ field: 'label', header: 'Node', width: 190 }, { field: 'team', header: 'Team' }, { field: 'rps', header: 'Requests/s', type: 'number', format: 'integer' }], rows: s.data.nodes.slice(), getRowId: (r) => r.id, rowSelection: 'multiple', title: 'Nodes' };
      const unlink = ctx.E.core.linkGrid(g.grid, { channel: 'gallery-graph' });
      m.target = wrap; m.grid = g.grid;
      m.destroy = () => { unlink(); g.grid.destroy(); wrap.remove(); };
      return m;
    },
    code: function (values, ctx) {
      const c = ctx.code.elementCode(this.build(values, ctx));
      c.js = c.js + "\n\n// the grid (drishti-grid) joins the same channel (drishti-core)\nlinkGrid(grid, { channel: 'gallery-graph' });";
      return c;
    }
  }));

  /* ---------------- drishti-risk: the counterparty structure ---------------- */
  function structure(ctx) {
    return ctx.memo('graph-structure', () => {
      let seed = 3;
      const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
      const rows = [], links = [];
      ['Northwind', 'Meridian', 'Halcyon'].forEach((g, i) => {
        rows.push({ ID: 'G' + i, NAME: g + ' Group', LEVEL: 'group', COUNTRY: ['GB', 'US', 'DE'][i] });
        for (let e = 0; e < 3; e++) {
          const le = 'LE' + i + e;
          rows.push({ ID: le, NAME: g + ' ' + ['Bank', 'Capital', 'Markets'][e], LEVEL: 'legal-entity', PARENT: 'G' + i, COUNTRY: ['GB', 'US', 'DE', 'FR'][(i + e) % 4], RATING: ['AA', 'A', 'BBB'][e] });
          for (let s = 0; s < 2; s++) {
            const ns = 'NS' + i + e + s, mtm = Math.round((rnd() - 0.3) * 4e7), coll = Math.round(rnd() * 2e7);
            rows.push({ ID: ns, NAME: 'Set ' + i + e + s, LEVEL: 'netting-set', PARENT: le, MTM: mtm, COLLATERAL: coll, EAD: Math.round(1.4 * (Math.max(0, mtm - coll) + rnd() * 6e6)), PFE: Math.round(rnd() * 3e7) });
            if (s === 0) { rows.push({ ID: 'CSA' + i + e, NAME: 'CSA ' + i + e, LEVEL: 'csa', PARENT: ns, THRESHOLD: Math.round(rnd() * 10) * 1e6 }); }
          }
        }
      });
      links.push({ FROM: 'LE00', TO: 'LE11', KIND: 'guarantees' });
      return { rows, links };
    });
  }

  demos.push(graphDemo({
    id: 'counterparty-structure', title: 'Counterparty structure', library: 'drishti-risk', typesFrom: 'drishti-graph', about: 'drishti_risk-graph.js',
    summary: 'drishti-risk\'s preset on drishti-graph: groups → legal entities → netting sets → CSAs, layered top-down, sized by EAD; PFE does not add up (◆, never summed for a group or a selection).',
    values: { mode: 'inspector', density: 'compact', height: 440 },
    examples: [{ title: 'The structure', values: {} }, { title: 'Terminal', values: { mode: 'terminal', density: 'terminal' } }],
    build: function (values, ctx) {
      const s = structure(ctx), o = ctx.E.risk.counterpartyStructureGraph(s.rows, s.links, Object.assign({ download: ctx.download }, typed(values, ['height'])));
      const names = new Map([[s.rows, 'rows'], [ctx.download, 'download']]);
      return { tag: 'drishti-graph', ctor: 'Graph', library: 'drishti-graph', attrs: { mode: values.mode, density: values.density }, data: o.data, options: o, jsOptions: typed(values, ['mode', 'density']), names, given: Array.from(new Set(names.values())) };
    },
    code: function (values) { return presetCode('counterpartyStructureGraph', 'drishti-risk', 'rows, links', values); }
  }));
}(typeof window !== 'undefined' ? window : globalThis));
