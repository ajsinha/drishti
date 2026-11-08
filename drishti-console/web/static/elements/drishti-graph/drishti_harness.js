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
/* The graph harness (drishti_harness.html): a synthetic network (groups own entities, entities hold sets, sets are covered by agreements,
   trades sit in sets; one dense cluster), a lineage, a 5,000-node graph for the timings, a stand-in Explain and a lazy expand hook, a
   drishti-grid linked through the bus, and a view's graph panel. The figures and names are invented. window.__harness is what the
   browser test drives. */
(function () {
  'use strict';
  const D = window.Drishti.elements, GR = D.graph, core = D.core;
  const q = new URLSearchParams(location.search);
  const view = q.get('view') || 'inspector', density = q.get('density') || 'compact';
  if (q.get('theme') === 'light') { core.setTheme('light', { persist: false }); }
  let seed = 11;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const COUNTRIES = ['GB', 'US', 'DE', 'FR', 'JP', 'CA'];

  /** The network: 6 groups, 48 entities, 96 sets, 72 agreements, trades (dense under the first entity). */
  function network(trades) {
    const nodes = [], edges = [];
    for (let g = 0; g < 6; g++) {
      const gname = ['Northwind', 'Meridian', 'Halcyon', 'Aurora', 'Borealis', 'Cobalt'][g];
      nodes.push({ id: 'GRP-' + (g + 1), label: gname + ' Group', type: 'group', country: COUNTRIES[g], rating: ['AA', 'A', 'BBB', 'A', 'BB', 'AA'][g] });
      for (let e = 0; e < 8; e++) {
        const le = 'LE-' + (g * 8 + e + 1), c = COUNTRIES[(g + e) % 6];
        nodes.push({ id: le, label: gname + ' ' + ['Bank', 'Capital', 'Markets', 'Securities', 'Funding', 'Leasing', 'Trust', 'Holdings'][e], type: 'entity', country: c, rating: ['AA', 'A', 'BBB', 'BB'][e % 4] });
        edges.push({ source: 'GRP-' + (g + 1), target: le, label: 'owns', weight: 50 + Math.round(rnd() * 50) });
        for (let s = 0; s < 2; s++) {
          const ns = 'NS-' + le.slice(3) + '-' + (s + 1), mtm = Math.round((rnd() - 0.35) * 40e6), coll = Math.round(rnd() * 20e6);
          nodes.push({ id: ns, label: 'Set ' + le.slice(3) + '.' + (s + 1), type: 'set', country: c, mtm, collateral: coll, exposure: Math.max(0, mtm - coll) + Math.round(rnd() * 5e6), risk: Math.round(rnd() * 3e6) });
          edges.push({ source: le, target: ns, label: 'nets', weight: 1 });
          if ((g + e + s) % 4 !== 3) {
            const csa = 'CSA-' + le.slice(3) + '-' + (s + 1);
            nodes.push({ id: csa, label: 'Agreement ' + le.slice(3) + '.' + (s + 1), type: 'agreement', country: c, threshold: Math.round(rnd() * 10) * 1e6 });
            edges.push({ source: csa, target: ns, label: 'covers', weight: 1 });
          }
        }
      }
      if (g) { edges.push({ source: 'LE-' + (g * 8 + 1), target: 'LE-' + ((g - 1) * 8 + 2), label: 'guarantees', weight: 30 }); }
    }
    const sets = nodes.filter((n) => n.type === 'set');
    for (let t = 0; t < trades; t++) {
      const ns = t < trades / 3 ? sets[t % 2] : sets[Math.floor(rnd() * sets.length)], id = 'TRD-' + (1000 + t);
      nodes.push({ id, label: id, type: 'trade', country: ns.country, mtm: Math.round((rnd() - 0.5) * 6e6), exposure: Math.round(rnd() * 3e6) });
      edges.push({ source: ns.id, target: id, label: 'holds', weight: 1 });
    }
    return { directed: true, nodes, edges };
  }

  const TYPES = { group: { label: 'Groups', shape: 'hexagon', colour: 'accent' }, entity: { label: 'Entities', shape: 'circle', colour: 'link' }, set: { label: 'Sets', shape: 'square', colour: 'good' },
    agreement: { label: 'Agreements', shape: 'diamond', colour: 'warn' }, trade: { label: 'Trades', shape: 'triangle', colour: 'muted' } };
  const MEASURES = [{ field: 'exposure', label: 'Exposure', format: 'compact' }, { field: 'mtm', label: 'MTM', format: 'compact' }, { field: 'collateral', label: 'Collateral', format: 'compact' },
    { field: 'risk', label: 'Risk', format: 'compact', additive: false }, { field: 'threshold', label: 'Threshold', format: 'compact' }];

  const H = window.__harness = { ready: false, error: null, events: [], graphs: {}, explained: [], expanded: [], downloads: [] };
  const explain = (ctx) => new Promise((res) => setTimeout(() => { H.explained.push(ctx.id); res({ title: (ctx.node.exposure !== undefined ? 'Exposure of ' : '') + ctx.label, value: ctx.node.exposure !== undefined ? core.formatCompact(ctx.node.exposure, { decimals: 1 }) : ctx.type,
    scope: ctx.type + ' · ' + ctx.id, steps: [{ label: 'Replacement cost', value: core.formatCompact(Math.max(0, (ctx.node.mtm || 0) - (ctx.node.collateral || 0)), { decimals: 1 }) }, { label: 'Add-on', value: '1.2M' }],
    note: 'The stand-in server of the harness.' }); }, 30));
  let extra = 0;
  const expand = (n) => new Promise((res) => setTimeout(() => {
    H.expanded.push(n.id);
    const nodes = [1, 2, 3].map((i) => ({ id: n.id + '/X' + (++extra), label: 'More ' + extra, type: 'trade', exposure: 1e5 * i }));
    res({ nodes, edges: nodes.map((x) => ({ source: n.id, target: x.id, label: 'holds' })) });
  }, 20));
  const download = (name, content, type) => H.downloads.push({ name, type, size: typeof content === 'string' ? content.length : 0 });
  const stage = document.getElementById('hx-stage');
  const base = { density, types: TYPES, measures: MEASURES, explain, expand, download, title: 'Counterparty network (synthetic)', sizeBy: 'exposure' };

  function mount(id, options, cls) {
    const el = document.createElement('div');
    el.id = 'g-' + id; if (cls) { el.className = cls; }
    stage.appendChild(el);
    const g = new GR.Graph(el, options);
    H.graphs[id] = g;
    return g;
  }

  let done;
  if (view === 'lineage') {
    const L = { title: 'Lineage (synthetic)', nodes: [], edges: [] };
    ['Trades feed', 'Market data', 'Static data'].forEach((s, i) => L.nodes.push({ id: 's' + i, label: s, stage: 'source', status: i === 1 ? 'stale' : 'ok' }));
    ['TRADES', 'PRICES', 'COUNTERPARTIES', 'POSITIONS'].forEach((s, i) => L.nodes.push({ id: 't' + i, label: s, stage: 'table', status: i === 3 ? 'bad' : 'ok' }));
    ['Risk model', 'Finance model'].forEach((s, i) => L.nodes.push({ id: 'm' + i, label: s, stage: 'model', status: 'ok' }));
    ['Daily risk report', 'Board pack', 'Desk dashboard'].forEach((s, i) => L.nodes.push({ id: 'r' + i, label: s, stage: 'report', status: i === 2 ? 'running' : 'ok' }));
    [['s0', 't0'], ['s1', 't1'], ['s2', 't2'], ['t0', 't3'], ['t1', 't3'], ['t3', 'm0'], ['t2', 'm0'], ['t0', 'm1'], ['m0', 'r0'], ['m0', 'r2'], ['m1', 'r1'], ['t2', 'r1']].forEach(([a, b]) => L.edges.push({ source: a, target: b }));
    H.lineage = L; H.health = GR.lineageHealth(L);
    done = mount('lineage', GR.lineageOptions(L, { density, explain, download, mode: 'inspector' }));
  } else if (view === 'big') {
    const N = 5000, E = 20000, nodes = [], edges = [];
    for (let i = 0; i < N; i++) { nodes.push({ id: 'n' + i, label: 'Node ' + i, type: 't' + (i % 5), group: 'c' + (i % 12), value: Math.round(rnd() * 1000) }); }
    for (let i = 0; i < E; i++) { const a = Math.floor(rnd() * N), b = Math.floor(Math.pow(rnd(), 2) * N); if (a !== b) { edges.push({ source: 'n' + a, target: 'n' + b }); } }
    const t0 = performance.now();
    const g = mount('big', { data: { directed: false, nodes, edges }, density, mode: 'inspector', tables: false, sizeBy: 'value', title: '5,000 nodes, 20,000 edges', download });
    H.big = { constructMs: Math.round(performance.now() - t0) };
    done = g;
  } else if (view === 'link') {
    const data = network(0), wrap = document.createElement('div');
    wrap.className = 'hx-link'; stage.appendChild(wrap);
    const gEl = document.createElement('div'), tEl = document.createElement('div');
    gEl.id = 'g-link'; tEl.id = 'grid-link'; wrap.appendChild(gEl); wrap.appendChild(tEl);
    H.graphs.link = done = new GR.Graph(gEl, Object.assign({}, base, { data, mode: 'inspector', link: { channel: 'harness' }, tables: false, height: 440 }));
    H.grid = new D.grid.Grid(tEl, { columns: [{ field: 'label', header: 'Node', width: 200 }, { field: 'type', header: 'Type' }, { field: 'exposure', header: 'Exposure', type: 'number', format: 'compact' }],
      rows: data.nodes, getRowId: (r) => r.id, density, rowSelection: 'multiple' });
    H.unlink = core.linkGrid(H.grid, { channel: 'harness' });
  } else if (view === 'panel') {
    const box = document.createElement('div');
    box.className = 'hx-panel'; box.id = 'panel'; stage.appendChild(box);
    H.opened = [];
    const data = { layout: q.get('layout') || 'tree', nodes: [{ id: 'CP-1', label: 'Northwind Bank', group: 'Counterparty', focus: true }, { id: 'GRP-1', label: 'Northwind Group', group: 'Group', link: { kind: 'group', id: 'GRP-1' } },
      { id: 'ISDA-1', label: 'ISDA 2002', group: 'Agreement', link: { kind: 'agreement', id: 'ISDA-1' } }, { id: 'NS-1', label: 'Netting set 1', group: 'Netting set' }, { id: 'NS-2', label: 'Netting set 2', group: 'Netting set' }],
    edges: [{ from: 'GRP-1', to: 'CP-1', label: 'parent' }, { from: 'CP-1', to: 'ISDA-1' }, { from: 'ISDA-1', to: 'NS-1' }, { from: 'ISDA-1', to: 'NS-2' }] };
    done = H.graphs.panel = GR.drawGraphPanel(box, data, { onLink: (l) => H.opened.push(l), density });
  } else {
    done = mount(view, Object.assign({}, base, { data: network(view === 'inspector' ? 600 : 300), mode: view }));
  }

  GR.GRAPH_EVENTS.forEach((name) => stage.addEventListener('drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), () => {
    H.events.push(name);
    document.getElementById('hx-log').textContent = H.events.length + ' events · last ' + name;
  }));
  /* live: three trades added under a set, one removed, an exposure changed (throttled into one redraw) */
  let tick = 0;
  H.tick = function () {
    const g = H.graphs[Object.keys(H.graphs)[0]], set = g.model.nodeList().find((n) => n.type === 'set');
    if (!set) { return Promise.resolve(); }
    tick++;
    const add = [1, 2, 3].map((i) => ({ id: 'LIVE-' + tick + '-' + i, label: 'Live ' + tick + '.' + i, type: 'trade', exposure: 2e6 * i }));
    g.applyTransaction({ addNodes: add, addEdges: add.map((n) => ({ source: set.id, target: n.id, label: 'holds' })) });
    return g.applyTransaction({ updateNodes: [{ id: set.id, exposure: 9e7 }] });
  };
  document.getElementById('hx-tick').addEventListener('click', () => H.tick());
  document.getElementById('hx-theme').addEventListener('click', () => core.setTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'terminal' : 'light', { persist: false }));
  document.querySelectorAll('.hx-seg [data-view]').forEach((b) => {
    b.classList.toggle('on', b.dataset.view === view);
    b.addEventListener('click', () => { const p = new URLSearchParams(location.search); p.set('view', b.dataset.view); location.search = p.toString(); });
  });
  document.querySelectorAll('.hx-seg [data-density]').forEach((b) => {
    b.classList.toggle('on', b.dataset.density === density);
    b.addEventListener('click', () => { Object.keys(H.graphs).forEach((k) => H.graphs[k].setDensity(b.dataset.density)); document.querySelectorAll('.hx-seg [data-density]').forEach((x) => x.classList.toggle('on', x === b)); });
  });
  const g0 = done;
  const settle = () => new Promise((res) => { const t0 = performance.now(); const check = () => { if (g0.layoutMs !== null || performance.now() - t0 > 20000) { res(); } else { setTimeout(check, 30); } }; check(); });
  settle().then(() => { if (H.big) { H.big.layoutMs = g0.layoutMs; H.big.frameMs = g0.stats().frameMs; } H.ready = true; }, (e) => { H.error = String(e); H.ready = true; });
}());
