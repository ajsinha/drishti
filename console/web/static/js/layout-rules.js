/* Project Drishti · Any data. Any domain. One grammar.

   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
   All rights reserved.

   PROPRIETARY AND CONFIDENTIAL.

   This file is the confidential and proprietary property of Ashutosh Sinha.
   Unauthorised copying, use, modification, distribution or disclosure of this
   file, via any medium, is strictly prohibited except with the express prior
   written permission of the copyright holder.

   See the LICENSE file in the root of this repository for the full terms. */
/* The rules of layout mode that do not need a page: how narrow a panel may get, that the last visible panel stays, and
   what the size announcement says. layout.js uses them; tests run them in Node (QA 2026-10-01 UX-14). */
(function (root) {
  'use strict';
  var GRID = 12, MAX_H = 24, DEFAULT_MIN_SPAN = 3;
  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }
  /** A width in columns, between the minimum (a panel narrower than that is unreadable) and the whole column. */
  function clampSpan(n, min) { return clamp(Math.round(n), clamp(min || DEFAULT_MIN_SPAN, 1, GRID), GRID); }
  /** A height in rows: 0 is "as tall as its content". */
  function clampHeight(n) { return n ? clamp(Math.round(n), 1, MAX_H) : 0; }
  /** Whether hiding a panel is refused: it would leave nothing visible (a layout is never saved with every panel hidden). */
  function hideRefused(hiddenFlags, index) {
    return !hiddenFlags[index] && hiddenFlags.every(function (h, i) { return i === index || h; });
  }
  /** What is announced for a panel's size; the height is the one kept, so what is said is what is saved. */
  function sizeText(title, span, height) {
    return title + ': ' + span + ' of ' + GRID + ' columns wide, ' + (height ? height + (height === 1 ? ' row' : ' rows') + ' tall' : 'as tall as its content');
  }
  /** The height in rows of a panel measured on screen (its pixels over the row's), never short of what is shown. */
  function rowsFromPixels(px, rowPx) { return clampHeight(Math.ceil(px / rowPx - 0.05)); }
  var api = { GRID: GRID, MAX_H: MAX_H, DEFAULT_MIN_SPAN: DEFAULT_MIN_SPAN, clampSpan: clampSpan, clampHeight: clampHeight,
              hideRefused: hideRefused, sizeText: sizeText, rowsFromPixels: rowsFromPixels };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; } else { root.DrishtiLayoutRules = api; }
})(typeof window !== 'undefined' ? window : this);
