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
/* What the grid adds to the aggregation engine (drishti-core's: the functions, their states, registered ones): reading a measure's text
   ("sum(NOTIONAL_USD)") and telling the measures the browser never adds up. */
export { AGG_FUNCS, aggFunc, registerAggFunc, unregisterAggFunc, aggregate } from '../../drishti-core/src/drishti_index.js';
import { aggFunc } from '../../drishti-core/src/drishti_index.js';

/**
 * A measure's text read: func(field), or count() for the members; null when it is not that simple form (var(99, 1d), ratio(...)).
 * @param {string} measure for example "sum(NOTIONAL_USD)"
 * @returns {?{func: string, field: ?string}}
 */
export function parseMeasure(measure) {
  const m = /^\s*([A-Za-z_][\w]*)\s*\(\s*([A-Za-z_][\w.]*)?\s*\)\s*$/.exec(String(measure || ''));
  return m ? { func: m[1], field: m[2] || null } : null;
}

/**
 * Whether a measure must be asked of the server for every cell and total: the browser does not compute its function, or its field is
 * marked non-additive (VaR, ES, a ratio: their totals are never sums of the parts).
 * @param {string} measure
 * @param {function(string): ?Object} [column] a field's column ({additive})
 * @returns {boolean}
 */
export function isServerMeasure(measure, column) {
  const p = parseMeasure(measure);
  if (!p || !aggFunc(p.func)) { return true; }
  if (p.field && column) { const c = column(p.field); if (c && c.additive === false) { return true; } }
  return false;
}
