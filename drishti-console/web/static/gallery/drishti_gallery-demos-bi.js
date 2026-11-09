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
/* The gallery's demo of the decomposition tree. A stand-in server answers each split with fixed figures, as a real pivot engine would: the box's
   own figure, the Σ of the parts, and the diversification (never worked out by the element). */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);

  const SPLITS = [{ field: 'desk', label: 'Desk' }, { field: 'region', label: 'Region' }, { field: 'risk', label: 'Risk class' }];
  /* eslint-disable */
  function standIn() {
  const figures = {
    '': [29.6, { Rates: 13.4, Commodities: 8.9, Equities: 8.1, Credit: 7.6, FX: 7.2 }],
    Rates: [13.4, { Americas: 6.7, APAC: 6.2, EMEA: 6.0 }],
    Commodities: [8.9, { Americas: 5.0, APAC: 3.4, EMEA: 3.2 }]
  };
  return function load({ split, path }) {
    const key = path.map((p) => p.value).join('/');
    const node = figures[key] || figures[path[path.length - 1]?.value] || [1.5, { One: 1.0, Two: 0.8 }];
    const kids = split ? Object.entries(node[1]) : [];
    const sum = kids.reduce((a, k) => a + k[1], 0);
    return Promise.resolve({ node: { value: node[0] * 1e6 }, additive: false, unit: 'money', componentLabel: 'Component',
      children: kids.map(([label, v]) => ({ label, member: label, value: v * 1e6, component: v * 1e6 * node[0] / sum, others: false })),
      sumStandalone: kids.length ? sum * 1e6 : undefined, diversification: kids.length ? (sum - node[0]) * 1e6 : undefined });
  };
  }
  const SERVER = '(' + standIn.toString() + '())';

  const JS = "const load = " + SERVER + ";\n\nnew Drishti.elements.tree.Decomposition(document.getElementById('visual'), {\n  title: '99% VaR',\n  splits: " + JSON.stringify(SPLITS) + ",\n  load\n});\n";

  demos.push({
    id: 'decomposition', library: 'drishti-tree', kind: 'function', title: 'Decomposition tree', about: 'drishti_decomposition.js', ag: false, types: ['DecompositionOptions'], events: [],
    summary: 'One measure split level by level, the dimension chosen by the reader at each column head. Every box is the server\'s figure for that slice; a split of a measure that does not add says "Σ standalone − diversification = node", with the component beside the standalone figure.',
    values: {}, examples: [{ title: '99% VaR, by desk then region', values: {} }],
    page: () => ({ html: '<div id="visual" style="height: 360px"></div>', js: JS }),
    mount: function (host, values, ctx) {
      const box = ctx.h(host.ownerDocument, 'div', { cls: 'bi-decomp' }); box.style.height = '360px';
      host.appendChild(box);
      const d = new ctx.E.tree.Decomposition(box, { title: '99% VaR', splits: SPLITS, load: standIn() });
      return { element: box, destroy: () => { d.dispose(); box.remove(); } };
    },
    code: () => ({ element: '<!-- a plain JavaScript element -->', js: JS }),
    sutra: () => null
  });
}(typeof window !== 'undefined' ? window : null));
