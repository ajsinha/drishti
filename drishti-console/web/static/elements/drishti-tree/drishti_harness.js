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
/* The tree harness (drishti_harness.html): a synthetic VaR hierarchy (a bank, four divisions, their desks and books: drishti-risk's
   preset) with a stand-in server that answers the non-additive figures (each parent's VaR under its children's sum: the
   diversification) and Explain, and a synthetic lineage (sources, tables, models, reports; a table with two parents) for the org
   chart's DAG. ?view=grid|org|sunburst|icicle, &data=lineage, &density=..., &theme=light, &edit=1. window.__harness is what the browser
   test drives. The figures are invented. */
(function () {
  'use strict';
  const D = window.Drishti.elements, T = D.tree, R = D.risk, core = D.core;
  const q = new URLSearchParams(location.search);
  let seed = 11;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };

  /* ---- a VaR hierarchy: 4 divisions, 3 desks each, 3 books each (the books carry stand-alone figures, the parents none) ---- */
  const DIV = { MKT: ['Markets', ['Rates', 'Credit', 'FX']], EQD: ['Equities', ['Cash equities', 'Derivatives', 'Prime']], TSY: ['Treasury', ['ALM', 'Liquidity', 'Funding']],
    COM: ['Commodities', ['Energy', 'Metals', 'Agri']] };
  const rows = [{ ID: 'BANK', LABEL: 'Bank (synthetic)', LEVEL: 'bank' }];
  const book = {};
  const st = (u) => (u > 1 ? 'breach' : u >= 0.8 ? 'near' : 'within');                  // the stand-in server's limit status (drishti.risk.limit-near 0.8)
  Object.keys(DIV).forEach((dk) => {
    rows.push({ ID: dk, PARENT: 'BANK', LABEL: DIV[dk][0], LEVEL: 'division' });
    DIV[dk][1].forEach((desk, i) => {
      const id = dk + '-' + (i + 1);
      rows.push({ ID: id, PARENT: dk, LABEL: desk, LEVEL: 'desk' });
      for (let b = 1; b <= 3; b++) {
        const v = Math.round((0.4 + rnd() * 2.6) * 1e6), lim = Math.round(v * (0.95 + rnd() * 0.75) / 1e5) * 1e5;
        const r = { ID: id + '-B' + b, PARENT: id, LABEL: desk + ' book ' + b, LEVEL: 'book', VAR: v, ES: Math.round(v * 1.27), LIMIT: lim, LIMIT_USE: Math.round(v / lim * 1000) / 1000, LIMIT_STATUS: st(v / lim),
          POSITIONS: 20 + Math.floor(rnd() * 400), NOTIONAL: Math.round((20 + rnd() * 900) * 1e6) };
        book[r.ID] = r;
        rows.push(r);
      }
    });
  });
  const kids = {};
  rows.forEach((r) => { if (r.PARENT) { (kids[r.PARENT] = kids[r.PARENT] || []).push(r.ID); } });

  /* ---- the stand-in server: a parent's VaR is the root of the squares of its children's (with a correlation), never their sum ---- */
  const H = window.__harness = { tree: null, events: [], asked: [], explains: 0, downloads: [], ready: false, rows: rows };
  const serverVar = (id) => {
    if (book[id]) { return book[id].VAR; }
    const xs = kids[id].map(serverVar);
    const sq = xs.reduce((a, x) => a + x * x, 0), sum = xs.reduce((a, x) => a + x, 0);
    return Math.round(Math.sqrt(sq + 0.35 * (sum * sum - sq)));
  };
  const limitOf = (id) => (book[id] ? book[id].LIMIT : Math.round(kids[id].map(limitOf).reduce((a, x) => a + x, 0) * 0.8 / 1e5) * 1e5);
  const serverMeasures = (reqs) => {
    H.asked.push(reqs.map((r) => r.id));
    return new Promise((resolve) => setTimeout(() => resolve(reqs.map((r) => {
      const v = serverVar(r.id), lim = limitOf(r.id);
      return { id: r.id, values: { VAR: v, ES: Math.round(v * 1.26), LIMIT: lim, LIMIT_USE: Math.round(v / lim * 1000) / 1000, LIMIT_STATUS: st(v / lim) } };
    })), 15));
  };
  const explain = (ctx) => {
    H.explains++;
    const kidsOf = kids[ctx.id] || [];
    return { title: (ctx.measure || 'Node') + ' · ' + ctx.label, value: ctx.text, scope: ctx.path.join(' › ') + ' · 2026-11-06 · 509 scenarios',
      steps: [{ label: 'Scenario P&L of the positions under ' + ctx.label, value: '509 scenarios' }, { label: 'The 99th percentile loss', value: ctx.text || '—' }],
      parts: kidsOf.map((k) => ({ label: rows.find((r) => r.ID === k).LABEL, value: serverVar(k), text: core.formatCompact(serverVar(k), { decimals: 2 }) })), partsTitle: 'Stand-alone by child',
      note: kidsOf.length ? 'The children\'s figures add up to more than the parent\'s: the gap is the diversification.' : 'A book: its own scenario P&L.' };
  };

  /* ---- a lineage: sources → tables → models → reports, one table read by two models (a DAG) ---- */
  const lineage = [{ id: 'LAKE', label: 'Lake', kind: 'root' },
    { id: 'S1', parent: 'LAKE', label: 'Trades feed', kind: 'source', rows: 120000 }, { id: 'S2', parent: 'LAKE', label: 'Market data', kind: 'source', rows: 40000 },
    { id: 'T1', parent: 'S1', label: 'trades', kind: 'table', rows: 118000 }, { id: 'T2', parent: 'S2', label: 'curves', kind: 'table', rows: 38000, also: ['S1'] },
    { id: 'M1', parent: 'T1', label: 'Positions model', kind: 'model', rows: 9000 }, { id: 'M2', parent: 'T2', label: 'Curves model', kind: 'model', rows: 2000 },
    { id: 'R1', parent: 'M1', label: 'Daily report', kind: 'report', rows: 300 }, { id: 'R2', parent: 'M2', label: 'Curve report', kind: 'report', rows: 120 }];

  const view = q.get('view') || 'grid', density = q.get('density') || null;
  if (q.get('theme') === 'light') { document.documentElement.setAttribute('data-theme', 'light'); }
  const host = document.getElementById('hx-tree');
  const download = (name, content, type) => { H.downloads.push({ name: name, type: type, size: String(content).length, head: String(content).slice(0, 120) }); };
  const opts = q.get('data') === 'lineage'
    ? { data: { rows: lineage, measures: [{ field: 'rows', label: 'Rows', format: 'integer' }] }, mode: view, title: 'Lineage', openDepth: -1, editable: true, download: download, explain: explain }
    : R.varHierarchyTree(rows, { mode: view, serverMeasures: serverMeasures, explain: explain, link: { channel: 'harness-tree' }, editable: true, download: download,
      proposalNote: 'Approver: any hierarchy approver but the proposer (four eyes)', status: ['business date 2026-11-06', '509 scenarios'] });
  if (density) { opts.density = density; }
  const t = H.tree = new T.Tree(host, opts);
  T.TREE_EVENTS.forEach((n) => t.on(n, (p) => { H.events.push(n); H.last = H.last || {}; H.last[n] = p; }));
  if (q.get('edit') === '1') { t.setEditing(true); }
  H.publish = (ids) => core.publishSelection('harness-tree', ids);
  H.heard = [];
  core.bus.on(core.SELECTION_EVENT, (p) => { if (p && p.source === t) { H.heard.push(p.ids); } });

  document.querySelectorAll('.hx-seg [data-view]').forEach((b) => {
    b.classList.toggle('on', b.dataset.view === view);
    b.addEventListener('click', () => { const p = new URLSearchParams(location.search); p.set('view', b.dataset.view); location.search = p.toString(); });
  });
  document.querySelectorAll('.hx-seg [data-density]').forEach((b) => {
    b.addEventListener('click', () => { t.setDensity(b.dataset.density); document.querySelectorAll('.hx-seg [data-density]').forEach((x) => x.classList.toggle('on', x === b)); });
  });
  document.getElementById('hx-theme').addEventListener('click', () => core.setTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'terminal' : 'light', { persist: false }));
  (opts.serverMeasures ? t.askNow() : Promise.resolve()).then(() => { H.ready = true; }, (e) => { H.error = String(e); H.ready = true; });
}());
