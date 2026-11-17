/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The gallery's demos of drishti-tree (every mode: the tree grid, the org chart, the sunburst and the icicle; the edit mode with its
   proposal; a lineage drawn as an org chart with a second parent). The hierarchies are
   synthetic and made here; a stand-in server answers the non-additive figures (never the children's sum) and Explain. The options
   panel is made from TreeOptions' JSDoc; the event log listens to TREE_EVENTS. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const SCALARS = ['mode', 'density', 'title', 'editable', 'openDepth', 'sizeBy', 'colourBy'];

  function typed(values, names) {
    const o = {};
    names.forEach((n) => { if (values[n] !== undefined && values[n] !== '') { o[n] = values[n]; } });
    return o;
  }

  /** A deterministic company: four divisions, their departments and teams (headcount and cost add up; engagement and budget use do not). */
  function company(ctx) {
    return ctx.memo('tree-company', () => {
      let seed = 5;
      const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
      const DIV = { ENG: ['Engineering', ['Platform', 'Apps', 'Data']], OPS: ['Operations', ['Support', 'Logistics']], SAL: ['Sales', ['Americas', 'EMEA', 'APAC']], FIN: ['Finance', ['Accounting', 'Planning']] };
      const rows = [{ id: 'CO', label: 'Company (synthetic)', kind: 'company' }], teams = {};
      Object.keys(DIV).forEach((d) => {
        rows.push({ id: d, parent: 'CO', label: DIV[d][0], kind: 'division' });
        DIV[d][1].forEach((dep, i) => {
          const id = d + '-' + (i + 1);
          rows.push({ id: id, parent: d, label: dep, kind: 'department' });
          for (let t = 1; t <= 3; t++) {
            const hc = 4 + Math.floor(rnd() * 26), budget = hc * (90 + rnd() * 60) * 1e3;
            const r = { id: id + '-T' + t, parent: id, label: dep + ' team ' + t, kind: 'team', headcount: hc, cost: Math.round(budget * (0.75 + rnd() * 0.4)), budget: Math.round(budget),
              engagement: Math.round((55 + rnd() * 40) * 10) / 10 };
            r.use = Math.round(r.cost / r.budget * 1000) / 1000;
            teams[r.id] = r;
            rows.push(r);
          }
        });
      });
      const kids = {};
      rows.forEach((r) => { if (r.parent) { (kids[r.parent] = kids[r.parent] || []).push(r.id); } });
      const sum = (id, f) => (teams[id] ? teams[id][f] : kids[id].reduce((a, k) => a + sum(k, f), 0));
      const eng = (id) => (teams[id] ? teams[id].engagement : Math.round(kids[id].reduce((a, k) => a + eng(k) * sum(k, 'headcount'), 0) / sum(id, 'headcount') * 10) / 10);
      const used = (id) => Math.round(sum(id, 'cost') / sum(id, 'budget') * 1000) / 1000;
      const said = (u) => (u > 1 ? 'over' : u >= 0.9 ? 'near' : 'within');            // the stand-in server's status; the tree only shows it
      const server = (reqs) => new Promise((resolve) => setTimeout(() => resolve(reqs.map((r) => ({ id: r.id, values: { engagement: eng(r.id), use: used(r.id), useStatus: said(used(r.id)) } }))), 20));
      const explain = (c) => ({ title: (c.measure || 'Node') + ' · ' + c.label, value: c.text, scope: c.path.join(' › '),
        steps: c.additive ? [{ label: 'Its teams added up', value: c.text }] : [{ label: 'Weighted by headcount, by the server', value: c.text }, { label: 'The children\'s figures', value: (kids[c.id] || []).length + ' parts' }],
        note: c.additive ? null : 'Not additive: the server computes it for the node; it is never the sum of the children.' });
      const measures = [{ field: 'headcount', label: 'Headcount', format: 'integer' }, { field: 'cost', label: 'Cost', format: 'money' },
        { field: 'engagement', label: 'Engagement', format: 'number', additive: false, childSum: false },
        { field: 'use', label: 'Budget use', format: 'percent', additive: false, bar: { max: 1, status: 'useStatus', tones: { within: 'good', near: 'warn', over: 'bad' } } }];
      return { rows: rows, measures: measures, server: server, explain: explain };
    });
  }

  /** A lineage: sources, tables, models, reports; one table also read from a second source (a DAG). */
  function lineage() {
    return [{ id: 'LAKE', label: 'Lake', kind: 'lake' },
      { id: 'S1', parent: 'LAKE', label: 'Orders feed', kind: 'source', rows: 120000 }, { id: 'S2', parent: 'LAKE', label: 'Reference data', kind: 'source', rows: 40000 },
      { id: 'T1', parent: 'S1', label: 'orders', kind: 'table', rows: 118000 }, { id: 'T2', parent: 'S2', label: 'customers', kind: 'table', rows: 38000, also: ['S1'] },
      { id: 'M1', parent: 'T1', label: 'Sales model', kind: 'model', rows: 9000 }, { id: 'M2', parent: 'T2', label: 'Customer model', kind: 'model', rows: 2000 },
      { id: 'R1', parent: 'M1', label: 'Daily sales', kind: 'report', rows: 300 }, { id: 'R2', parent: 'M2', label: 'Churn report', kind: 'report', rows: 120 }];
  }

  function mountTree(host, b) {
    const el = host.ownerDocument.createElement(b.tag);
    Object.keys(b.attrs || {}).forEach((k) => { if (b.attrs[k] !== undefined && b.attrs[k] !== null && b.attrs[k] !== '') { el.setAttribute(k, String(b.attrs[k])); } });
    host.appendChild(el);
    el.options = b.options;
    return { target: el, element: el, ready: el.tree ? el.tree.askNow() : Promise.resolve(), destroy: () => el.remove() };
  }

  function treeDemo(spec) {
    return Object.assign({
      library: 'drishti-tree', kind: 'element', about: 'drishti_tree-view.js', types: ['TreeOptions'], events: ['TREE_EVENTS'], keys: ['drishti_tree-io.js'], ag: false,
      mount: function (host, values, ctx) { return mountTree(host, this.build(values, ctx)); },
      sutra: () => null
    }, spec);
  }

  function companyBuild(values, ctx, more) {
    const c = company(ctx);
    const data = { rows: c.rows, measures: c.measures };
    const o = Object.assign({ data: data, serverMeasures: c.server, explain: c.explain, download: ctx.download, link: { channel: 'gallery-tree' } }, typed(values, SCALARS.filter((n) => n !== 'mode' && n !== 'density')), more);
    const names = new Map([[c.rows, 'rows'], [c.measures, 'measures'], [c.server, 'serverMeasures'], [c.explain, 'explain'], [ctx.download, 'download']]);
    return { tag: 'drishti-tree', ctor: 'Tree', library: 'drishti-tree', attrs: { mode: values.mode, density: values.density }, options: o, jsOptions: typed(values, ['mode', 'density']),
      names: names, given: Array.from(new Set(names.values())) };
  }

  demos.push(treeDemo({
    id: 'tree', title: 'Tree', summary: 'Hierarchies in four modes of one component: the tree grid (the default, on drishti-grid\'s tree data), the org chart, the sunburst and the icicle. Search with breadcrumbs, measures with non-additive ones (◆) asked of the server and never summed, Explain, linked selection, export, keyboard.',
    values: { mode: 'grid', density: 'compact', title: 'Company', openDepth: 1 },
    examples: [{ title: 'The tree grid (the default)', values: {} }, { title: 'The org chart ', values: { mode: 'org' } }, { title: 'The sunburst, sized by headcount ', values: { mode: 'sunburst' } },
      { title: 'The icicle, sized by cost', values: { mode: 'icicle', sizeBy: 'cost' } }, { title: 'Terminal density, everything open', values: { density: 'terminal', openDepth: -1 } }],
    build: function (values, ctx) { return companyBuild(values, ctx); }
  }));

  ['sunburst', 'icicle'].forEach((mode) => demos.push(treeDemo({
    id: mode, title: mode === 'sunburst' ? 'Sunburst' : 'Icicle', summary: 'Size by an additive measure, colour by a non-additive one read per node: click a part to select, Zoom in and out from the side panel.',
    values: { mode: mode, density: 'compact', title: 'Company', openDepth: -1 }, examples: [{ title: 'Sized by headcount', values: {} }, { title: 'Sized by cost', values: { sizeBy: 'cost' } }],
    build: function (values, ctx) { return companyBuild(values, ctx); }
  })));

  demos.push(treeDemo({
    id: 'tree-edit', title: 'Tree: propose a change', summary: 'The edit mode: drag a node onto its new parent (or pick it from "Move to"); the moves collect into one proposal for another person to approve (four eyes). Nothing changes until the host applies it.',
    values: { mode: 'org', density: 'compact', title: 'Company', editable: true, openDepth: 1 },
    examples: [{ title: 'Re-parent in the org chart', values: {} }, { title: 'Re-parent in the tree grid', values: { mode: 'grid' } }],
    build: function (values, ctx) { return companyBuild(values, ctx, { proposalNote: 'Approver: anyone in HR but the proposer · impact: 2 reports' }); },
    mount: function (host, values, ctx) {
      const m = mountTree(host, this.build(values, ctx));
      if (m.element.tree) { m.element.tree.setEditing(true); }
      return m;
    }
  }));

  demos.push(treeDemo({
    id: 'tree-lineage', title: 'Tree: a lineage', summary: 'Sources, tables, models and reports drawn as an org chart; a node read from two parents is marked ⧉ and its second link is dashed.',
    values: { mode: 'org', density: 'compact', title: 'Lineage', openDepth: -1 },
    examples: [{ title: 'The lineage', values: {} }, { title: 'As a tree grid', values: { mode: 'grid' } }],
    build: function (values, ctx) {
      const rows = ctx.memo('tree-lineage', lineage), data = { rows: rows, measures: [{ field: 'rows', label: 'Rows', format: 'integer' }] };
      const o = Object.assign({ data: data, download: ctx.download }, typed(values, SCALARS.filter((n) => n !== 'mode' && n !== 'density')));
      const names = new Map([[rows, 'rows'], [ctx.download, 'download']]);
      return { tag: 'drishti-tree', ctor: 'Tree', library: 'drishti-tree', attrs: { mode: values.mode, density: values.density }, options: o, jsOptions: typed(values, ['mode', 'density']), names: names, given: Array.from(new Set(names.values())) };
    }
  }));
}(typeof window !== 'undefined' ? window : globalThis));
