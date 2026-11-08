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
/* The geometry of a layout on a grid, without a page: rectangles in grid units {x, y, w, h} (x and w in columns, y and h in rows),
   snapping a pixel offset to the grid, clamping into the grid, overlap and settling (what a move overlaps is pushed down, as a report
   page does), align and distribute, the bounding box, and the panel rules of a 12-column screen (a width in columns, a height in rows
   or 0 for "as tall as its content", the sentence a screen reader hears). */

/** The columns of a screen. */
export const GRID = 12;
/** The tallest a panel is, in rows. */
export const MAX_H = 24;
/** A panel narrower than this is unreadable. */
export const DEFAULT_MIN_SPAN = 3;

/**
 * A rectangle in grid units.
 * @typedef {Object} Rect
 * @property {number} x first column (0-based)
 * @property {number} y first row (0-based)
 * @property {number} w columns
 * @property {number} h rows
 */

/**
 * n between lo and hi.
 * @param {number} n
 * @param {number} lo
 * @param {number} hi
 * @returns {number}
 */
export function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

/**
 * A width in columns, between the minimum and the whole grid.
 * @param {number} n
 * @param {number} [min=3]
 * @param {number} [grid=12]
 * @returns {number}
 */
export function clampSpan(n, min, grid) { const g = grid || GRID; return clamp(Math.round(n), clamp(min || DEFAULT_MIN_SPAN, 1, g), g); }

/**
 * A height in rows; 0 is "as tall as its content".
 * @param {number} n
 * @param {number} [max=24]
 * @returns {number}
 */
export function clampHeight(n, max) { return n ? clamp(Math.round(n), 1, max || MAX_H) : 0; }

/**
 * Whether hiding the panel at index is refused: it would leave nothing visible.
 * @param {boolean[]} hiddenFlags
 * @param {number} index
 * @returns {boolean}
 */
export function hideRefused(hiddenFlags, index) { return !hiddenFlags[index] && hiddenFlags.every((h, i) => i === index || h); }

/**
 * What a screen reader hears for a panel's size.
 * @param {string} title
 * @param {number} span
 * @param {number} height rows, 0 for the content's height
 * @param {number} [grid=12]
 * @returns {string}
 */
export function sizeText(title, span, height, grid) {
  return title + ': ' + span + ' of ' + (grid || GRID) + ' columns wide, ' + (height ? height + (height === 1 ? ' row' : ' rows') + ' tall' : 'as tall as its content');
}

/**
 * The rows a panel measured on screen fills (never short of what is shown).
 * @param {number} px
 * @param {number} rowPx
 * @param {number} [max=24]
 * @returns {number}
 */
export function rowsFromPixels(px, rowPx, max) { return clampHeight(Math.ceil(px / rowPx - 0.05), max); }

/**
 * A pixel offset as whole grid steps.
 * @param {number} px
 * @param {number} stepPx the size of one step (a column and its gap, a row and its gap)
 * @returns {number}
 */
export function snap(px, stepPx) { return stepPx > 0 ? Math.round(px / stepPx) || 0 : 0; }

/**
 * A rectangle inside the grid: at least minW by minH, at most cols wide and maxH high, never left of 0 or above 0, never past the
 * last column.
 * @param {Rect} r
 * @param {{cols: number, maxH: number, minW: number, minH: number}} g
 * @returns {Rect}
 */
export function clampRect(r, g) {
  const cols = g.cols || GRID, w = clamp(Math.round(r.w), g.minW || 1, cols), h = clamp(Math.round(r.h), g.minH || 1, g.maxH || 40);
  return { x: clamp(Math.round(r.x), 0, cols - w), y: Math.max(0, Math.round(r.y)), w, h };
}

/**
 * Whether two rectangles share a cell.
 * @param {Rect} a
 * @param {Rect} b
 * @returns {boolean}
 */
export function overlaps(a, b) { return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h; }

/**
 * Settles a layout after `moved` changed: whatever it overlaps goes down below it, and so on (the top one stays where it was put).
 * Changes the rectangles in place.
 * @param {Array<{id: string, x: number, y: number, w: number, h: number}>} items
 * @param {{id: string}} moved
 * @returns {string[]} the ids that went down
 */
export function settle(items, moved) {
  const queue = [moved], out = [];
  let guard = 0;
  while (queue.length && guard++ < 500) {
    const a = queue.shift();
    items.filter((b) => b !== a && b.id !== a.id && overlaps(a, b)).sort((p, q) => p.y - q.y).forEach((b) => { b.y = a.y + a.h; queue.push(b); if (out.indexOf(b.id) < 0) { out.push(b.id); } });
  }
  return out;
}

/**
 * The box around rectangles.
 * @param {Rect[]} rects
 * @returns {?Rect}
 */
export function bounds(rects) {
  if (!rects.length) { return null; }
  const x = Math.min(...rects.map((r) => r.x)), y = Math.min(...rects.map((r) => r.y));
  return { x, y, w: Math.max(...rects.map((r) => r.x + r.w)) - x, h: Math.max(...rects.map((r) => r.y + r.h)) - y };
}

/** The edges align() takes. */
export const ALIGN_EDGES = Object.freeze(['left', 'right', 'top', 'bottom', 'center', 'middle']);

/**
 * Rectangles lined up on one edge of their box (center: the same middle column; middle: the same middle row).
 * @param {Rect[]} rects
 * @param {('left'|'right'|'top'|'bottom'|'center'|'middle')} edge
 * @returns {Rect[]} new rectangles, in the same order
 */
export function alignRects(rects, edge) {
  const b = bounds(rects);
  if (!b) { return []; }
  return rects.map((r) => {
    const o = Object.assign({}, r);
    if (edge === 'left') { o.x = b.x; } else if (edge === 'right') { o.x = b.x + b.w - r.w; }
    else if (edge === 'top') { o.y = b.y; } else if (edge === 'bottom') { o.y = b.y + b.h - r.h; }
    else if (edge === 'center') { o.x = Math.round(b.x + (b.w - r.w) / 2); } else if (edge === 'middle') { o.y = Math.round(b.y + (b.h - r.h) / 2); }
    else { throw new Error('no edge ' + edge + ' (' + ALIGN_EDGES.join(', ') + ')'); }
    return o;
  });
}

/**
 * Rectangles spread evenly along an axis between the first and the last (three or more): equal gaps, whole grid units.
 * @param {Rect[]} rects
 * @param {('x'|'y')} axis
 * @returns {Rect[]} new rectangles, in the same order
 */
export function distributeRects(rects, axis) {
  const out = rects.map((r) => Object.assign({}, r));
  if (out.length < 3) { return out; }
  const size = axis === 'x' ? 'w' : 'h';
  const order = out.map((r, i) => i).sort((a, b) => out[a][axis] - out[b][axis]);
  const first = out[order[0]], last = out[order[order.length - 1]];
  const span = last[axis] + last[size] - first[axis];
  const used = order.reduce((s, i) => s + out[i][size], 0);
  const gap = (span - used) / (order.length - 1);
  let at = first[axis];
  order.forEach((i) => { out[i][axis] = Math.round(at); at += out[i][size] + gap; });
  return out;
}
