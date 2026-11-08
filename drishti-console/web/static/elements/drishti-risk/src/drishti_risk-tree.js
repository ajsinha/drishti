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
   stand-alone figures added up, and the gap is the diversification), the use of its VaR limit (the server's ratio, a bar in the tone of
   the server's status: within neutral, near amber, breach red; the server's one rule, drishti.risk.limit-near, decides, never this page) and the additive figures a sunburst may be sized by (positions,
   notional). The VaR screens' look: the tree grid in the terminal density and layout, the figures in compact money (riskMoney), a node
   without a limit use saying why in its LIMIT_NOTE ("no limit" by default). A screen gives its rows and its hooks:

     varHierarchyTree(rows, {serverMeasures, explain, link, editable})   the TreeOptions for new Tree(host, ...) or <drishti-tree>
     varHierarchyTree(rows, {fields: ['VAR', 'ES', 'LIMIT_USE'], measures: {VAR: {label: 'VaR EUR'}}, chrome: false, grid, loadChildren})
                                                                         a screen's own columns of it, inside its own panels */
import { riskMoney } from './drishti_risk-format.js';
import { TONES } from './drishti_risk-grid.js';

/**
 * The tones of the server's limit status (a node's LIMIT_STATUS: within, near or breach, by the server's one rule drishti.risk.limit-near),
 * the colours of the bar and of the nodes; a node without a status has no tone.
 */
export const LIMIT_STATUS_TONES = Object.freeze({ within: 'good', near: 'warn', breach: 'bad' });

/** The tones' words in the legend. */
const LIMIT_STATUS_WORDS = Object.freeze({ within: 'within the limit', near: 'near the limit', breach: 'over the limit' });

/**
 * A node's limit use in words: its share of the limit, or why it has none (its LIMIT_NOTE: "n/c" for a limit of another horizon or
 * confidence; "no limit" by default).
 * @param {?number} v the server's use (1 = at the limit)
 * @param {Object} [row] the node's row
 * @returns {string}
 */
export function limitUseText(v, row) { return typeof v === 'number' ? Math.round(v * 100) + '%' : (row && row.LIMIT_NOTE) || 'no limit'; }

/** The measures of a VaR hierarchy, in the order the tree grid shows them. */
export const VAR_TREE_MEASURES = Object.freeze([
  Object.freeze({ field: 'VAR', label: 'VaR 99% 1d', format: riskMoney, additive: false, childSum: true }),
  Object.freeze({ field: 'ES', label: 'ES 97.5%', format: riskMoney, additive: false }),
  Object.freeze({ field: 'LIMIT', label: 'Limit', format: riskMoney, additive: false }),
  Object.freeze({ field: 'LIMIT_USE', label: 'Limit use', format: 'percent', additive: false, bar: Object.freeze({ max: 1, status: 'LIMIT_STATUS', tones: LIMIT_STATUS_TONES, words: LIMIT_STATUS_WORDS }),
    column: Object.freeze({ format: limitUseText, cellClass: (v) => (typeof v === 'number' ? null : TONES.muted) }) }),
  Object.freeze({ field: 'POSITIONS', label: 'Positions', format: 'integer' }),
  Object.freeze({ field: 'NOTIONAL', label: 'Notional', format: riskMoney })
]);

/**
 * The options of a VaR hierarchy tree.
 * @param {Object[]} rows the nodes: {ID, PARENT, LABEL, LEVEL} and any figures the server sent with them (VAR, ES, LIMIT, LIMIT_USE,
 *   POSITIONS, NOTIONAL), LIMIT_STATUS (the server's within, near or breach: the bar's tone), LIMIT_NOTE (why a node has no limit use), lazy (its children come later, loadChildren) and any field of the
 *   screen's own; the books' POSITIONS and NOTIONAL roll up, the risk figures never do
 * @param {Object} [o] serverMeasures (the non-additive figures of the nodes that lack them), explain, link, editable, proposalNote,
 *   mode (default grid), density (default terminal), title, status; fields (the measures shown, in order: default all), measures
 *   (by field, what a screen changes of one: its label, childSum, its column); and any TreeOptions (chrome, grid, loadChildren ...)
 * @returns {Object} TreeOptions (drishti-tree)
 */
export function varHierarchyTree(rows, o) {
  o = o || {};
  const per = o.measures || {}, fields = o.fields || VAR_TREE_MEASURES.map((m) => m.field);
  const measures = fields.map((f) => {
    const m = VAR_TREE_MEASURES.find((x) => x.field === f);
    if (!m) { throw new Error('a VaR hierarchy has no measure ' + f); }
    const own = per[f] || {};
    return Object.assign({}, m, own, { column: Object.assign({}, m.column, own.column) });
  });
  const opts = Object.assign({ mode: 'grid', density: 'terminal', gridLayout: 'terminal', title: 'VaR hierarchy', openDepth: 2, colourBy: 'LIMIT_USE', shown: ['VAR', 'ES'],
    emptyText: 'Select a node to see its VaR, ES and limit use, its place in the hierarchy and Explain. Over the limit: red; near it: amber.' }, o, {
    data: { rows: rows, idField: 'ID', parentField: 'PARENT', labelField: 'LABEL', kindField: 'LEVEL', measures: measures }
  });
  if (!opts.sizeBy && fields.indexOf('POSITIONS') >= 0) { opts.sizeBy = 'POSITIONS'; }
  if (fields.indexOf(opts.colourBy) < 0) { delete opts.colourBy; }
  delete opts.fields;
  delete opts.measures;
  return opts;
}

/**
 * The nodes over their VaR limit (the server's status breach), for a screen's warning.
 * @param {Object[]} rows
 * @returns {string[]} their ids
 */
export function limitBreaches(rows) { return (rows || []).filter((r) => r.LIMIT_STATUS === 'breach').map((r) => String(r.ID)); }
