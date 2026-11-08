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
/* The gallery's demos of drishti-tree (every mode: the tree grid, the org chart, the sunburst and the icicle; the edit mode with its
   proposal; a lineage drawn as an org chart with a second parent) and of drishti-risk's VaR hierarchy preset. The hierarchies are
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
      const server = (reqs) => new Promise((resolve) => setTimeout(() => resolve(reqs.map((r) => ({ id: r.id, values: { engagement: eng(r.id), use: Math.round(sum(r.id, 'cost') / sum(r.id, 'budget') * 1000) / 1000 } }))), 20));
      const explain = (c) => ({ title: (c.measure || 'Node') + ' · ' + c.label, value: c.text, scope: c.path.join(' › '),
        steps: c.additive ? [{ label: 'Its teams added up', value: c.text }] : [{ label: 'Weighted by headcount, by the server', value: c.text }, { label: 'The children\'s figures', value: (kids[c.id] || []).length + ' parts' }],
        note: c.additive ? null : 'Not additive: the server computes it for the node; it is never the sum of the children.' });
      const measures = [{ field: 'headcount', label: 'Headcount', format: 'integer' }, { field: 'cost', label: 'Cost', format: 'money' },
        { field: 'engagement', label: 'Engagement', format: 'number', additive: false, childSum: false },
        { field: 'use', label: 'Budget use', format: 'percent', additive: false, bar: { max: 1, bands: [{ to: 0.9, tone: 'good' }, { to: 1, tone: 'warn' }, { to: Infinity, tone: 'bad' }] } }];
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
    examples: [{ title: 'The tree grid (g6 B, the default)', values: {} }, { title: 'The org chart (g6 A)', values: { mode: 'org' } }, { title: 'The sunburst, sized by headcount (g6 C)', values: { mode: 'sunburst' } },
      { title: 'The icicle, sized by cost', values: { mode: 'icicle', sizeBy: 'cost' } }, { title: 'Terminal density, everything open', values: { density: 'terminal', openDepth: -1 } }],
    build: function (values, ctx) { return companyBuild(values, ctx); }
  }));

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

  /* ---------------- drishti-risk: the VaR hierarchy ---------------- */
  function bank(ctx) {
    return ctx.memo('tree-bank', () => {
      let seed = 11;
      const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
      const DIV = { MKT: ['Markets', ['Rates', 'Credit', 'FX']], EQD: ['Equities', ['Cash equities', 'Derivatives']], TSY: ['Treasury', ['ALM', 'Funding']] };
      const rows = [{ ID: 'BANK', LABEL: 'Bank (synthetic)', LEVEL: 'bank' }], book = {}, kids = {};
      Object.keys(DIV).forEach((d) => {
        rows.push({ ID: d, PARENT: 'BANK', LABEL: DIV[d][0], LEVEL: 'division' });
        DIV[d][1].forEach((desk, i) => {
          const id = d + '-' + (i + 1);
          rows.push({ ID: id, PARENT: d, LABEL: desk, LEVEL: 'desk' });
          for (let b = 1; b <= 3; b++) {
            const v = Math.round((0.4 + rnd() * 2.6) * 1e6), lim = Math.round(v * (0.9 + rnd() * 0.6) / 1e5) * 1e5;
            const r = { ID: id + '-B' + b, PARENT: id, LABEL: desk + ' book ' + b, LEVEL: 'book', VAR: v, ES: Math.round(v * 1.27), LIMIT: lim, LIMIT_USE: Math.round(v / lim * 1000) / 1000,
              POSITIONS: 20 + Math.floor(rnd() * 400), NOTIONAL: Math.round((20 + rnd() * 900) * 1e6) };
            book[r.ID] = r;
            rows.push(r);
          }
        });
      });
      rows.forEach((r) => { if (r.PARENT) { (kids[r.PARENT] = kids[r.PARENT] || []).push(r.ID); } });
      const vr = (id) => { if (book[id]) { return book[id].VAR; } const xs = kids[id].map(vr), sq = xs.reduce((a, x) => a + x * x, 0), s = xs.reduce((a, x) => a + x, 0); return Math.round(Math.sqrt(sq + 0.35 * (s * s - sq))); };
      const lim = (id) => (book[id] ? book[id].LIMIT : Math.round(kids[id].map(lim).reduce((a, x) => a + x, 0) * 0.62 / 1e5) * 1e5);
      const server = (reqs) => new Promise((resolve) => setTimeout(() => resolve(reqs.map((r) => ({ id: r.id, values: { VAR: vr(r.id), ES: Math.round(vr(r.id) * 1.26), LIMIT: lim(r.id), LIMIT_USE: Math.round(vr(r.id) / lim(r.id) * 1000) / 1000 } }))), 20));
      const explain = (c) => ({ title: (c.measure || 'Node') + ' · ' + c.label, value: c.text, scope: c.path.join(' › ') + ' · 509 scenarios',
        parts: (kids[c.id] || []).map((k) => ({ label: rows.find((r) => r.ID === k).LABEL, value: vr(k), text: String(vr(k)) })), partsTitle: 'Stand-alone by child',
        note: (kids[c.id] || []).length ? 'The children\'s VaRs add up to more than the parent\'s: the gap is the diversification.' : 'A book: its own scenario P&L.' });
      return { rows: rows, server: server, explain: explain };
    });
  }

  demos.push(treeDemo({
    id: 'var-hierarchy', library: 'drishti-risk', typesFrom: 'drishti-tree', title: 'VaR hierarchy', about: 'drishti_risk-tree.js',
    summary: 'The bank, its divisions, desks and books with VaR and ES (◆: the server\'s, never summed; Σ children beside VaR, the gap is the diversification), the limit\'s use as a bar, positions and notional to size a sunburst. The VaR screens\' look: the tree grid in the terminal density.',
    values: { mode: 'grid', density: 'terminal' },
    examples: [{ title: 'The tree grid (terminal)', values: {} }, { title: 'The org chart', values: { mode: 'org', density: 'compact' } }, { title: 'Where the positions are (sunburst)', values: { mode: 'sunburst' } },
      { title: 'Notional as an icicle', values: { mode: 'icicle', sizeBy: 'NOTIONAL' } }],
    build: function (values, ctx) {
      const B = bank(ctx), R = ctx.E.risk;
      const more = typed(values, ['sizeBy', 'editable']);
      const o = R.varHierarchyTree(B.rows, Object.assign({ serverMeasures: B.server, explain: B.explain, download: ctx.download, link: { channel: 'gallery-tree' } }, more));
      delete o.mode; delete o.density;
      const names = new Map([[B.rows, 'rows'], [B.server, 'serverMeasures'], [B.explain, 'explain'], [ctx.download, 'download'], [o.data, 'varHierarchyTree(rows).data']]);
      return { tag: 'drishti-tree', ctor: 'Tree', library: 'drishti-risk', attrs: { mode: values.mode, density: values.density }, options: o, jsOptions: typed(values, ['mode', 'density']), names: names, given: ['rows', 'serverMeasures', 'explain'] };
    },
    code: function (values, ctx) {
      return { element: "<drishti-tree mode=\"" + values.mode + "\" density=\"" + values.density + "\"></drishti-tree>\n<script src=\"/static/elements/drishti-core/drishti_core.js\"></script>\n<script>\n" +
        "Promise.all([Drishti.elements.load('drishti-tree'), Drishti.elements.load('drishti-risk')]).then(([, risk]) => {\n  // rows, serverMeasures, explain: your data and hooks\n" +
        "  document.querySelector('drishti-tree').options = risk.varHierarchyTree(rows, { serverMeasures, explain, link: { channel: 'var' } });\n});\n</script>",
      js: "import { Tree } from '/static/elements/drishti-tree/src/drishti_index.js';\nimport { varHierarchyTree } from '/static/elements/drishti-risk/src/drishti_index.js';\n\n// rows, serverMeasures, explain: your data and hooks\n" +
        "const tree = new Tree(document.getElementById('tree'), varHierarchyTree(rows, { serverMeasures, explain, mode: '" + values.mode + "', density: '" + values.density + "' }));" };
    }
  }));
}(typeof window !== 'undefined' ? window : globalThis));
