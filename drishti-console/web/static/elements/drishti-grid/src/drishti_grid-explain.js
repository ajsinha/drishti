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
/* Explain as the grid draws it, in the place the layout keeps for it: the drawer beside the grid that follows the selection (wells), the
   card at the cell (panel), the split pane under the grid with the steps, the worst scenarios and the trend side by side (terminal).
   The drawing is drishti-core's (drishti_explain.js, shared with the chart and the map) under the grid's class prefix. */
import { sparkline as coreSparkline, renderExplain as coreRenderExplain } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} ExplainState
 * @property {number} row
 * @property {number} col
 * @property {boolean} loading
 * @property {?(Object|string|Node)} result an ExplainResult (drishti-core), words or an element
 * @property {?Error} error
 */

/**
 * A small line of figures over time, as SVG (the colours are the style sheet's: .dg-spark).
 * @param {Document} doc
 * @param {number[]} values
 * @param {number} [limit] a dashed line at the limit
 * @returns {SVGElement}
 */
export function sparkline(doc, values, limit) { return coreSparkline(doc, values, limit, 'dg'); }

/**
 * Draws an Explain state into its place: 'drawer' and 'card' one column, 'split' three panes (the explanation, the worst scenarios, the
 * trend).
 * @param {HTMLElement} el the place
 * @param {?ExplainState} st nothing yet: the hint of how to ask
 * @param {('drawer'|'card'|'split')} mode
 * @param {{onClose: function(): void, explainable: boolean}} [o] onClose: a close button
 * @returns {void}
 */
export function renderExplain(el, st, mode, o) {
  coreRenderExplain(el, st, mode, Object.assign({ hint: o && o.explainable === false ? 'This grid has no Explain hook.' : undefined }, o, { prefix: 'dg' }));
}
