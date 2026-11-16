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
/* The data compute that may run off the page's thread: counting values into equal-width bins (a histogram, a density's counts). One
   implementation: the page calls it when it has no worker, drishti_data-worker.js calls it in the worker. Plain functions, no page. */

/**
 * Counts numbers into equal-width bins over their range (or the extent given); NaN and values outside the extent are left out.
 * @param {(Float64Array|number[])} values
 * @param {{bins: number, extent: number[]}} [o] bins default 20
 * @returns {{x0: number, width: number, counts: Uint32Array, n: number}}
 */
export function binCounts(values, o) {
  o = o || {};
  const bins = Math.max(1, Math.round(o.bins || 20));
  let lo = Infinity, hi = -Infinity, n = 0;
  if (o.extent) { lo = o.extent[0]; hi = o.extent[1]; } else { for (let i = 0; i < values.length; i++) { const v = values[i]; if (v === v) { if (v < lo) { lo = v; } if (v > hi) { hi = v; } } } }
  if (!(hi >= lo)) { return { x0: 0, width: 1, counts: new Uint32Array(bins), n: 0 }; }
  if (hi === lo) { lo -= 0.5; hi += 0.5; }
  const width = (hi - lo) / bins, counts = new Uint32Array(bins);
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (v !== v || v < lo || v > hi) { continue; }
    counts[Math.min(bins - 1, Math.floor((v - lo) / width))]++;
    n++;
  }
  return { x0: lo, width: width, counts: counts, n: n };
}
