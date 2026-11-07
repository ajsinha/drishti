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
/* Selection: rows by id (a click, Ctrl+click adds, Shift+click a run from the anchor, select all of the filtered rows) and cell ranges
   (rectangles of display rows by shown columns; Ctrl adds another); a range's figures for the status line (count, sum, average, min,
   max, with a warning when a column is non-additive: a sum of VaRs is not a VaR); and the copy as TSV (tab-separated, as Excel pastes). */
import { EventBus } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} CellRange
 * @property {number} r1 first display row (inclusive)
 * @property {number} r2 last display row (inclusive)
 * @property {number} c1 first shown column
 * @property {number} c2 last shown column
 */

/**
 * Rows and cell ranges selected.
 */
export class SelectionModel {
  /**
   * @param {{mode: ('single'|'multiple'|'none')}} [o] row selection mode (default multiple)
   */
  constructor(o) {
    this.mode = (o && o.mode) || 'multiple';
    /** @type {EventBus} changed {what: 'rows'|'ranges'} */
    this.events = new EventBus();
    this.ids = new Set();
    this.anchorIndex = null;
    this.ranges = [];
    this.rangeAnchor = null;
  }

  /**
   * A click on a row: alone it selects only that row; ctrl toggles it; shift selects the run from the anchor.
   * @param {number} index the display index
   * @param {function(number): ?string} idAt the id of a display index (null for a group or a placeholder)
   * @param {{ctrl: boolean, shift: boolean}} [mods]
   * @returns {void}
   */
  clickRow(index, idAt, mods) {
    if (this.mode === 'none') { return; }
    const m = mods || {}, id = idAt(index);
    if (this.mode === 'single' || (!m.ctrl && !m.shift)) {
      this.ids.clear();
      if (id !== null) { this.ids.add(id); }
      this.anchorIndex = index;
    } else if (m.shift && this.anchorIndex !== null) {
      if (!m.ctrl) { this.ids.clear(); }
      const a = Math.min(this.anchorIndex, index), b = Math.max(this.anchorIndex, index);
      for (let i = a; i <= b; i++) { const x = idAt(i); if (x !== null) { this.ids.add(x); } }
    } else {
      if (id !== null) { if (this.ids.has(id)) { this.ids.delete(id); } else { this.ids.add(id); } }
      this.anchorIndex = index;
    }
    this.events.emit('changed', { what: 'rows' });
  }

  /**
   * Selects every given id (the filtered rows'), or clears.
   * @param {Iterable<string>} [ids] none: clear
   * @returns {void}
   */
  selectAll(ids) {
    this.ids.clear();
    if (ids) { for (const id of ids) { this.ids.add(id); } }
    this.events.emit('changed', { what: 'rows' });
  }

  /**
   * Whether a row is selected.
   * @param {string} id
   * @returns {boolean}
   */
  isSelected(id) { return this.ids.has(id); }

  /**
   * The selected ids, in the order they were selected.
   * @returns {string[]}
   */
  selectedIds() { return Array.from(this.ids); }

  /**
   * Starts a cell range at a cell (ctrl: as another range, else replacing).
   * @param {number} row
   * @param {number} col
   * @param {{ctrl: boolean}} [mods]
   * @returns {void}
   */
  startRange(row, col, mods) {
    if (!(mods && mods.ctrl)) { this.ranges = []; }
    this.rangeAnchor = { row: row, col: col };
    this.ranges.push({ r1: row, r2: row, c1: col, c2: col });
    this.events.emit('changed', { what: 'ranges' });
  }

  /**
   * Stretches the last range from its anchor to a cell (a drag, or Shift+click, or Shift+arrows).
   * @param {number} row
   * @param {number} col
   * @returns {void}
   */
  extendRange(row, col) {
    if (!this.rangeAnchor) { this.startRange(row, col); return; }
    const a = this.rangeAnchor;
    this.ranges[this.ranges.length - 1] = { r1: Math.min(a.row, row), r2: Math.max(a.row, row), c1: Math.min(a.col, col), c2: Math.max(a.col, col) };
    this.events.emit('changed', { what: 'ranges' });
  }

  /**
   * Forgets every range.
   * @returns {void}
   */
  clearRanges() { this.ranges = []; this.rangeAnchor = null; this.events.emit('changed', { what: 'ranges' }); }

  /**
   * Whether a cell is in a range.
   * @param {number} row
   * @param {number} col
   * @returns {boolean}
   */
  inRange(row, col) { return this.ranges.some((r) => row >= r.r1 && row <= r.r2 && col >= r.c1 && col <= r.c2); }
}

/**
 * The figures of the selected cells for the status line: how many cells, how many numbers, and their sum, average, min and max. When a
 * number comes from a non-additive column (VaR, ES, a ratio) the sum and average are not given (null) and `warning` says why, in words.
 * @param {CellRange[]} ranges
 * @param {function(number, number): *} valueAt the raw value at (display row, shown column)
 * @param {function(number): {header: string, additive: boolean}} columnAt the shown column at an index
 * @returns {{cells: number, count: number, sum: ?number, avg: ?number, min: ?number, max: ?number, nonAdditive: string[], warning: ?string}}
 */
export function rangeStats(ranges, valueAt, columnAt) {
  let cells = 0, count = 0, sum = 0, min = null, max = null;
  const seen = new Set(), nonAdd = new Set();
  ranges.forEach((r) => {
    for (let i = r.r1; i <= r.r2; i++) {
      for (let j = r.c1; j <= r.c2; j++) {
        const k = i + ':' + j;
        if (seen.has(k)) { continue; }
        seen.add(k);
        cells++;
        const v = valueAt(i, j);
        if (typeof v !== 'number' || !isFinite(v)) { continue; }
        count++;
        sum += v;
        min = min === null || v < min ? v : min;
        max = max === null || v > max ? v : max;
        const c = columnAt(j);
        if (c && c.additive === false) { nonAdd.add(c.header || c.id); }
      }
    }
  });
  const na = Array.from(nonAdd);
  return { cells: cells, count: count, sum: count && !na.length ? sum : null, avg: count && !na.length ? sum / count : null, min: min, max: max,
    nonAdditive: na, warning: na.length ? na.join(', ') + (na.length > 1 ? ' are' : ' is') + ' not additive: a sum of them is not their total (ask the server for the total of a scope)' : null };
}

/**
 * Cells as TSV, Excel's paste format: rows by line, cells by tab; a cell with a tab, a line break or a quote is quoted ("" for a quote).
 * @param {Array<Array<*>>} grid rows of cells (already the text to copy)
 * @returns {string}
 */
export function toTsv(grid) {
  const cell = (v) => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[\t\n\r"]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  return grid.map((r) => r.map(cell).join('\t')).join('\n');
}

/**
 * The cells of the ranges as rows of text (each range's rows under the previous), for a copy.
 * @param {CellRange[]} ranges
 * @param {function(number, number): string} textAt the copied text at (display row, shown column): raw by default for numbers
 * @param {{headers: function(number): string}} [o] headers: a first line of the columns' headings
 * @returns {Array<Array<string>>}
 */
export function rangeCells(ranges, textAt, o) {
  const out = [];
  ranges.forEach((r) => {
    if (o && o.headers) { const h = []; for (let j = r.c1; j <= r.c2; j++) { h.push(o.headers(j)); } out.push(h); }
    for (let i = r.r1; i <= r.r2; i++) { const line = []; for (let j = r.c1; j <= r.c2; j++) { line.push(textAt(i, j)); } out.push(line); }
  });
  return out;
}
