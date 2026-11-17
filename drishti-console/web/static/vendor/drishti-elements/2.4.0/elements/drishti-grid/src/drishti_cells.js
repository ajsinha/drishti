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
/* The grid's small drawings in a cell: a sparkline (a row's series over time), a bar (a figure against the largest) and a bullet (a figure
   against its limit and bands). Each is a cellRenderer maker: columns: [{field: 'var', cellRenderer: bulletCell({limit: 'limit'})}]. The
   sparkline is drishti-core's; the bar and the bullet are drishti-chart's bullet (one drawing in the library). A cell's text stays the
   formatted figure (the copy, the export, the sort and the table of a screen reader's alternative); the drawing carries its words as
   its label. Colours: drishti_grid.css and drishti_chart.css, tokens only. */
import { sparkline, h } from '../../drishti-core/src/drishti_index.js';
import { bullet } from '../../drishti-chart/src/drishti_index.js';

const pick = (spec, row, fallback) => (typeof spec === 'function' ? spec(row) : (typeof spec === 'string' ? (row ? row[spec] : undefined) : (spec === undefined ? fallback : spec)));
const wrap = (doc, drawn, text, show) => h(doc, 'span', { cls: 'dg-cell-draw' }, [drawn, show ? h(doc, 'span', { cls: 'dg-cell-fig', text: text }) : null]);

/**
 * @typedef {Object} CellDrawOptions
 * @property {boolean} [showValue=false] the formatted figure beside the drawing
 */

/**
 * A cell renderer drawing the cell's series (an array of numbers: the value, or the row's field) as a sparkline.
 * @param {CellDrawOptions & {values: (string|function(Object): number[]), limit: (number|string|function(Object): number)}} [o] values: the row's field or a function (default: the cell's own value)
 * @returns {function({value: *, text: string, row: Object, doc: Document}): ?Node}
 */
export function sparklineCell(o) {
  o = o || {};
  return (p) => {
    const series = o.values === undefined ? p.value : pick(o.values, p.row);
    if (!Array.isArray(series) || !series.length) { return null; }
    const nums = series.map(Number).filter(Number.isFinite);
    if (!nums.length) { return null; }
    const lim = pick(o.limit, p.row);
    const svg = sparkline(p.doc, nums, typeof lim === 'number' ? lim : undefined, 'dg-cell');
    return wrap(p.doc, svg, p.text, o.showValue);
  };
}

/**
 * A cell renderer drawing the cell's figure as a bullet against a limit and bands (drishti-chart's bullet).
 * @param {CellDrawOptions & {limit: (number|string|function(Object): number), bands: Array<{to: number, tone: string}>, min: number, max: number, orientation: string}} [o] limit: a number, the row's field or a function
 * @returns {function({value: *, text: string, row: Object, doc: Document}): ?Node}
 */
export function bulletCell(o) {
  o = o || {};
  return (p) => {
    const v = Number(p.value);
    if (!Number.isFinite(v)) { return null; }
    const lim = pick(o.limit, p.row);
    const svg = bullet(p.doc, { value: v, limit: typeof lim === 'number' ? lim : undefined, bands: o.bands, min: o.min, max: o.max, orientation: o.orientation,
      format: () => p.text, prefix: 'dc-bullet' });
    return wrap(p.doc, svg, p.text, o.showValue !== false);
  };
}

/**
 * A cell renderer drawing the cell's figure as a bar from the scale's start to its end (the bullet without a limit).
 * @param {CellDrawOptions & {min: number, max: (number|string|function(Object): number)}} [o] max: a number, the row's field or a function (default: the figure's own scale)
 * @returns {function({value: *, text: string, row: Object, doc: Document}): ?Node}
 */
export function barCell(o) {
  o = o || {};
  return (p) => {
    const v = Number(p.value);
    if (!Number.isFinite(v)) { return null; }
    const mx = pick(o.max, p.row);
    const svg = bullet(p.doc, { value: v, min: o.min, max: typeof mx === 'number' ? mx : undefined, format: () => p.text, prefix: 'dc-bullet' });
    return wrap(p.doc, svg, p.text, o.showValue !== false);
  };
}
