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
/* A plain renderer of the grid model: the rows and columns the viewport draws, as text lines or as plain table markup. For tests and for
   measuring the engine without a page; the layouts (wells, panel, terminal) draw the real grid. */

/**
 * The window the viewport draws: its rows and columns and every cell's text.
 * @param {import('./drishti_grid.js').GridModel} grid
 * @returns {{first: number, last: number, columns: Object[], cells: string[][], offset: number}}
 */
export function renderWindow(grid) {
  const vp = grid.viewport, rows = vp.rows(), shown = grid.shownColumns();
  const pinned = shown.filter((c) => c.pinned), center = shown.filter((c) => !c.pinned);
  const offs = new Float64Array(center.length + 1);
  center.forEach((c, i) => { offs[i + 1] = offs[i] + (c.width || 120); });
  const span = vp.columns(offs, pinned.reduce((a, c) => a + (c.width || 120), 0));
  const cols = pinned.filter((c) => c.pinned === 'left').concat(center.slice(span.first, span.last + 1), pinned.filter((c) => c.pinned === 'right'));
  const idx = cols.map((c) => shown.indexOf(c));
  if (grid.serverSide && grid.rowModel.ensureRange) { grid.rowModel.ensureRange(rows.first, rows.last); }
  const cells = [];
  for (let r = rows.first; r <= rows.last; r++) { cells.push(idx.map((j) => grid.cellText(r, j))); }
  return { first: rows.first, last: rows.last, columns: cols, cells: cells, offset: rows.offset };
}

/**
 * The window as text, a line per row, cells by " | ", headings first.
 * @param {import('./drishti_grid.js').GridModel} grid
 * @returns {string}
 */
export function renderText(grid) {
  const w = renderWindow(grid);
  return [w.columns.map((c) => c.header).join(' | ')].concat(w.cells.map((r) => r.join(' | '))).join('\n');
}

/**
 * The window as plain table markup (escaped), with the grid roles a screen reader needs.
 * @param {import('./drishti_grid.js').GridModel} grid
 * @returns {string}
 */
export function renderHtml(grid) {
  const w = renderWindow(grid);
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return '<table role="grid" aria-rowcount="' + (grid.rowCount() + 1) + '"><thead><tr>' + w.columns.map((c) => '<th role="columnheader">' + esc(c.header) + '</th>').join('') +
    '</tr></thead><tbody>' + w.cells.map((r, i) => '<tr aria-rowindex="' + (w.first + i + 2) + '">' + r.map((t) => '<td role="gridcell">' + esc(t) + '</td>').join('') + '</tr>').join('') +
    '</tbody></table>';
}
