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
/* drishti-risk's counterparty structure on drishti-graph (g5 with the counterparty data set): groups own legal entities, a legal entity
   trades under netting sets, a CSA covers a netting set (and guarantees link entities across groups). The layered layout top-down by
   level (group, legal entity, netting set, CSA), a shape and a tone per level, node size by exposure (EAD), the measures with their
   words: EAD, MTM and collateral add up across netting sets; PFE does not (◆, never summed for a group or a selection: the server's).
   A screen gives its rows and its hooks:

     counterpartyStructureGraph(rows, links, {explain, expand, link, mode})   the GraphOptions for new Graph(host, ...) or <drishti-graph> */

/** The levels of a counterparty structure, top to bottom, with their words, shapes and tones. */
export const COUNTERPARTY_LEVELS = Object.freeze({
  group: Object.freeze({ label: 'Groups', shape: 'hexagon', colour: 'accent', rank: 0 }),
  'legal-entity': Object.freeze({ label: 'Legal entities', shape: 'circle', colour: 'link', rank: 1 }),
  'netting-set': Object.freeze({ label: 'Netting sets', shape: 'square', colour: 'good', rank: 2 }),
  csa: Object.freeze({ label: 'CSAs', shape: 'diamond', colour: 'warn', rank: 3 })
});

/** The measures of a counterparty structure. */
export const COUNTERPARTY_MEASURES = Object.freeze([
  Object.freeze({ field: 'EAD', label: 'EAD (SA-CCR)', format: 'money' }),
  Object.freeze({ field: 'PFE', label: 'Peak PFE 95%', format: 'money', additive: false }),
  Object.freeze({ field: 'MTM', label: 'Net MTM', format: 'money' }),
  Object.freeze({ field: 'COLLATERAL', label: 'Collateral', format: 'money' }),
  Object.freeze({ field: 'THRESHOLD', label: 'Threshold', format: 'money' })
]);

/**
 * The options of a counterparty structure graph.
 * @param {Object[]} rows the nodes: {ID, NAME, LEVEL (group, legal-entity, netting-set or csa), PARENT (the node above it), COUNTRY,
 *   RATING, LEI} and the figures the server sent (EAD, PFE, MTM, COLLATERAL, THRESHOLD)
 * @param {Array<{FROM: string, TO: string, KIND: string}>} [links] links beyond the parents (a guarantee, a CSA covering a second set)
 * @param {Object} [o] explain, expand, link, mode (default inspector), density, title and any GraphOptions
 * @returns {Object} GraphOptions (drishti-graph)
 */
export function counterpartyStructureGraph(rows, links, o) {
  const ids = new Set((rows || []).map((r) => String(r.ID)));
  const nodes = (rows || []).map((r) => {
    const n = Object.assign({}, r, { id: String(r.ID), label: r.NAME || String(r.ID), type: r.LEVEL });
    delete n.ID; delete n.NAME; delete n.LEVEL; delete n.PARENT;
    return n;
  });
  const edges = [];
  (rows || []).forEach((r) => {
    if (r.PARENT === undefined || r.PARENT === null || !ids.has(String(r.PARENT))) { return; }
    const csa = r.LEVEL === 'csa';
    edges.push(csa ? { source: String(r.ID), target: String(r.PARENT), label: 'covers' } : { source: String(r.PARENT), target: String(r.ID), label: r.LEVEL === 'legal-entity' ? 'owns' : 'trades under' });
  });
  (links || []).forEach((l) => { if (ids.has(String(l.FROM)) && ids.has(String(l.TO))) { edges.push({ source: String(l.FROM), target: String(l.TO), label: l.KIND || '' }); } });
  return Object.assign({ mode: 'inspector', title: 'Counterparty structure', layout: 'layered', sizeBy: 'EAD', colourBy: 'type' }, o, {
    data: { directed: true, nodes, edges },
    types: Object.assign({}, COUNTERPARTY_LEVELS),
    measures: COUNTERPARTY_MEASURES.map((m) => Object.assign({}, m)),
    layoutOptions: Object.assign({ direction: 'TB', rank: (n) => (COUNTERPARTY_LEVELS[n.type] ? COUNTERPARTY_LEVELS[n.type].rank : null) }, o && o.layoutOptions)
  });
}
