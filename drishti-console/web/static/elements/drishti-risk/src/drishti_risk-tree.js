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
/* drishti-risk's VaR hierarchy on drishti-tree (g6 with the VaR data set): the bank, its divisions, desks and books, each with its VaR and
   ES (not additive: ◆, every figure the server's, a parent's never the sum of its children; "Σ children" beside VaR shows the
   stand-alone figures added up, and the gap is the diversification), the use of its VaR limit (the server's ratio, a bar in bands:
   under 75% good, to 90% amber, to 100% warn, over it a breach) and the additive figures a sunburst may be sized by (positions,
   notional). The VaR screens' look: the tree grid in the terminal density. A screen gives its rows and its server hooks:

     varHierarchyTree(rows, {serverMeasures, explain, link, editable})   the TreeOptions for new Tree(host, ...) or <drishti-tree> */

/** The VaR limit's use bands (the colours of the bar and of the nodes). */
export const LIMIT_USE_BANDS = Object.freeze([{ to: 0.75, tone: 'good' }, { to: 0.9, tone: 'accent' }, { to: 1, tone: 'warn' }, { to: Infinity, tone: 'bad' }]);

/** The measures of a VaR hierarchy, in the order the tree grid shows them. */
export const VAR_TREE_MEASURES = Object.freeze([
  Object.freeze({ field: 'VAR', label: 'VaR 99% 1d', format: 'money', additive: false, childSum: true }),
  Object.freeze({ field: 'ES', label: 'ES 97.5%', format: 'money', additive: false }),
  Object.freeze({ field: 'LIMIT', label: 'Limit', format: 'money', additive: false }),
  Object.freeze({ field: 'LIMIT_USE', label: 'Limit use', format: 'percent', additive: false, bar: Object.freeze({ max: 1, bands: LIMIT_USE_BANDS }) }),
  Object.freeze({ field: 'POSITIONS', label: 'Positions', format: 'integer' }),
  Object.freeze({ field: 'NOTIONAL', label: 'Notional', format: 'money' })
]);

/**
 * The options of a VaR hierarchy tree.
 * @param {Object[]} rows the nodes: {ID, PARENT, LABEL, LEVEL} and any figures the server sent with them (VAR, ES, LIMIT, LIMIT_USE,
 *   POSITIONS, NOTIONAL); the books' POSITIONS and NOTIONAL roll up, the risk figures never do
 * @param {Object} [o] serverMeasures (the non-additive figures of the nodes that lack them), explain, link, editable, proposalNote,
 *   mode (default grid), density (default terminal), title, status, and any TreeOptions
 * @returns {Object} TreeOptions (drishti-tree)
 */
export function varHierarchyTree(rows, o) {
  o = o || {};
  return Object.assign({ mode: 'grid', density: 'terminal', title: 'VaR hierarchy', openDepth: 2, sizeBy: 'POSITIONS', colourBy: 'LIMIT_USE', shown: ['VAR', 'ES'],
    emptyText: 'Select a node to see its VaR, ES and limit use, its place in the hierarchy and Explain. Over the limit: red; near it: amber.' }, o, {
    data: { rows: rows, idField: 'ID', parentField: 'PARENT', labelField: 'LABEL', kindField: 'LEVEL', measures: VAR_TREE_MEASURES.map((m) => Object.assign({}, m)) }
  });
}

/**
 * The nodes over their VaR limit (the server's use of 1 or more), for a screen's warning.
 * @param {Object[]} rows
 * @returns {string[]} their ids
 */
export function limitBreaches(rows) { return (rows || []).filter((r) => typeof r.LIMIT_USE === 'number' && r.LIMIT_USE >= 1).map((r) => String(r.ID)); }
