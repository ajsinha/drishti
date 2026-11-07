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
  contentHeight() { return this.rowCount * this.rowHeight; }

  /** The height the scroller is given (the real one, capped). @returns {number} */
  scrollHeight() { return Math.min(this.contentHeight(), this.maxScrollHeight); }

  /** @returns {number} the largest scrollTop */
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
    const real = this.realTop(this.scrollTop), h = this.rowHeight;
    const firstVisible = Math.min(this.rowCount - 1, Math.floor(real / h));
    const lastVisible = Math.min(this.rowCount - 1, Math.ceil((real + this.height) / h) - 1);
    const first = Math.max(0, firstVisible - this.overscanRows), last = Math.min(this.rowCount - 1, lastVisible + this.overscanRows);
    const offset = this.scrollTop + (first * h - real);       // in the scroller's coordinates: where the first drawn row sits
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
    const realTop = index < r.firstVisible ? index * this.rowHeight : (index + 1) * this.rowHeight - this.height;
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
