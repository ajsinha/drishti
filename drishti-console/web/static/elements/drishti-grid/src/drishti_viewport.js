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
/* Virtual scrolling: which rows and columns are drawn for a scroll position, so a grid of a million rows draws the forty on screen. Rows
   have one height; the scrolled height is capped (browsers stop near 16 to 33 million pixels), and above the cap a scroll position is
   mapped onto the rows in proportion, so the last row is always reachable. Columns: the pinned ones always, the middle ones whose
   span meets the view, found by binary search over their left edges. Pure arithmetic: runs under Node. */

/** The tallest scrolled height used (px); Firefox's limit is near 17.9 million. */
export const MAX_SCROLL_HEIGHT = 15000000;

/**
 * The first index whose left edge is past x (binary search of the edges, offsets[0] = 0, offsets[n] = the total).
 * @param {(Float64Array|number[])} offsets n + 1 edges
 * @param {number} x
 * @returns {number} the column holding x
 */
export function columnAt(offsets, x) {
  let lo = 0, hi = offsets.length - 2;
  if (hi < 0) { return -1; }
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (offsets[mid] <= x) { lo = mid; } else { hi = mid - 1; }
  }
  return lo;
}

/**
 * The scroll arithmetic of a grid's body.
 */
export class Viewport {
  /**
   * @param {{rowHeight: number, height: number, width: number, overscanRows: number, overscanColumns: number, maxScrollHeight: number}} [o]
   *   rowHeight default 28 (the design's densities pass theirs), overscan: rows and columns drawn beyond the view (default 6 and 2)
   */
  constructor(o) {
    o = o || {};
    this.rowHeight = o.rowHeight || 28;
    this.height = o.height || 600;
    this.width = o.width || 1000;
    this.overscanRows = o.overscanRows === undefined ? 6 : o.overscanRows;
    this.overscanColumns = o.overscanColumns === undefined ? 2 : o.overscanColumns;
    this.maxScrollHeight = o.maxScrollHeight || MAX_SCROLL_HEIGHT;
    this.rowCount = 0;
    this.scrollTop = 0;
    this.scrollLeft = 0;
    /** @private row index to the px a row is taller than rowHeight (an expanded list cell); empty for almost every grid */
    this.extras = new Map();
  }

  /**
   * Makes a row taller than the rest by px (a list cell expanded in place); 0 gives it the usual height back. Virtual scrolling stays
   * exact: the rows' tops, the scrolled height and the rows in view all count the extra.
   * @param {number} index
   * @param {number} px
   * @returns {boolean} whether anything changed
   */
  setExtra(index, px) {
    const was = this.extras.get(index) || 0, now = Math.max(0, Math.round(px) || 0);
    if (was === now) { return false; }
    if (now) { this.extras.set(index, now); } else { this.extras.delete(index); }
    return true;
  }

  /**
   * Every row has the usual height again.
   * @returns {boolean} whether any was taller
   */
  clearExtras() { const had = this.extras.size > 0; this.extras.clear(); return had; }

  /** The px a row is taller than the usual height. @param {number} index @returns {number} */
  extra(index) { return this.extras.get(index) || 0; }

  /** The height of a row (px). @param {number} index @returns {number} */
  heightOf(index) { return this.rowHeight + this.extra(index); }

  /** @private the extras of the rows that exist, by index */
  sortedExtras() {
    const out = [];
    this.extras.forEach((px, i) => { if (i < this.rowCount) { out.push([i, px]); } });
    return out.sort((a, b) => a[0] - b[0]);
  }

  /** The real top of a row (px from the first row's top). @param {number} index @returns {number} */
  rowTop(index) {
    let top = index * this.rowHeight;
    this.extras.forEach((px, i) => { if (i < index && i < this.rowCount) { top += px; } });
    return top;
  }

  /**
   * The row holding a real offset (px from the first row's top), clamped to the rows.
   * @param {number} y
   * @returns {number}
   */
  indexAt(y) {
    const h = this.rowHeight;
    let grown = 0;
    for (const [i, px] of this.sortedExtras()) {
      const top = i * h + grown;
      if (y < top) { break; }
      if (y < top + h + px) { return i; }
      grown += px;
    }
    return Math.max(0, Math.min(this.rowCount - 1, Math.floor((y - grown) / h)));
  }

  /**
   * Sets the size of the view (px).
   * @param {number} width
   * @param {number} height
   * @returns {void}
   */
  resize(width, height) { this.width = width; this.height = height; }

  /**
   * Sets how many rows there are.
   * @param {number} n
   * @returns {void}
   */
  setRowCount(n) { this.rowCount = Math.max(0, n | 0); this.scrollTop = Math.min(this.scrollTop, this.maxScrollTop()); }

  /** The real height of every row (px). @returns {number} */
  contentHeight() {
    let total = this.rowCount * this.rowHeight;
    this.extras.forEach((px, i) => { if (i < this.rowCount) { total += px; } });
    return total;
  }

  /** The height the scroller is given (the real one, capped). @returns {number} */
  scrollHeight() { return Math.min(this.contentHeight(), this.maxScrollHeight); }

  /** The largest scroll position. @returns {number} px */
  maxScrollTop() { return Math.max(0, this.scrollHeight() - this.height); }

  /** @private the real offset of a scroll position */
  realTop(scrollTop) {
    const max = this.maxScrollTop(), realMax = Math.max(0, this.contentHeight() - this.height);
    if (this.contentHeight() <= this.maxScrollHeight || max === 0) { return scrollTop; }
    return scrollTop / max * realMax;
  }

  /**
   * Scrolls (clamped).
   * @param {number} top
   * @param {number} [left]
   * @returns {void}
   */
  scrollTo(top, left) {
    this.scrollTop = Math.max(0, Math.min(this.maxScrollTop(), top));
    if (left !== undefined) { this.scrollLeft = Math.max(0, left); }
  }

  /**
   * The rows to draw now: first to last (inclusive, overscan included), and where the first goes (px from the scroller's top).
   * @returns {{first: number, last: number, offset: number, firstVisible: number, lastVisible: number}}
   */
  rows() {
    if (!this.rowCount) { return { first: 0, last: -1, offset: 0, firstVisible: 0, lastVisible: -1 }; }
    const real = this.realTop(this.scrollTop);
    const firstVisible = this.indexAt(real);
    const lastVisible = Math.max(firstVisible, this.indexAt(real + this.height - 0.001));
    const first = Math.max(0, firstVisible - this.overscanRows), last = Math.min(this.rowCount - 1, lastVisible + this.overscanRows);
    const offset = this.scrollTop + (this.rowTop(first) - real);       // in the scroller's coordinates: where the first drawn row sits
    return { first: first, last: last, offset: offset, firstVisible: firstVisible, lastVisible: lastVisible };
  }

  /**
   * The scrollTop that shows a row (the least scroll that brings it into view; null when it is in view already).
   * @param {number} index
   * @returns {?number}
   */
  scrollToRow(index) {
    const r = this.rows();
    if (index >= r.firstVisible && index < r.lastVisible) { return null; }
    const realMax = Math.max(0, this.contentHeight() - this.height);
    const realTop = index < r.firstVisible ? this.rowTop(index) : this.rowTop(index) + this.heightOf(index) - this.height;
    const clamped = Math.max(0, Math.min(realMax, realTop));
    const max = this.maxScrollTop();
    const top = this.contentHeight() <= this.maxScrollHeight || realMax === 0 ? clamped : clamped / realMax * max;
    this.scrollTo(top);
    return this.scrollTop;
  }

  /**
   * The middle columns to draw for the scroll position, given their edges (ColumnModel.layout().centerOffsets) and the room the pinned
   * columns take.
   * @param {(Float64Array|number[])} offsets
   * @param {number} [pinnedWidth=0]
   * @returns {{first: number, last: number}} inclusive; last < first when there are none
   */
  columns(offsets, pinnedWidth) {
    const n = offsets.length - 1;
    if (n <= 0) { return { first: 0, last: -1 }; }
    const room = Math.max(0, this.width - (pinnedWidth || 0));
    const a = columnAt(offsets, this.scrollLeft), b = columnAt(offsets, this.scrollLeft + room);
    return { first: Math.max(0, a - this.overscanColumns), last: Math.min(n - 1, b + this.overscanColumns) };
  }
}
