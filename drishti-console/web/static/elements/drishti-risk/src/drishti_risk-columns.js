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
/* drishti-risk's columns for the exposure, FRTB-SA and SIMM screens (ELEMENTS.md 4.8): one way to write a label column and a money
   column, so the screens do not each carry their own. The money is the screen's own rule (compact for exposure, two decimals for a
   capital charge, whole units for initial margin), given as a function; a total row (`total` or `blank` true) leaves an empty figure empty.

     textColumn(field, header, o)            a label column (default 130 px)
     moneyColumn(field, header, fmt, o)      a figure column (figureColumn) that writes its values with fmt; "—" for none */
import { figureColumn } from './drishti_risk-grid.js';

/**
 * A label column.
 * @param {string} field
 * @param {string} header
 * @param {Object} [o] any ColumnDef property (width, pinned, cellRenderer ...)
 * @returns {Object} a ColumnDef
 */
export function textColumn(field, header, o) {
  return Object.assign({ field: field, header: header, width: 130 }, o || {});
}

/**
 * A money column: the server's figure written by the screen's rule; an empty figure of a total row is left empty.
 * @param {string} field
 * @param {string} header
 * @param {function(*): string} fmt the screen's money rule
 * @param {Object} [o] figureColumn's options (additive, tone, width ...); a format here wins
 * @returns {Object} a ColumnDef
 */
export function moneyColumn(field, header, fmt, o) {
  return figureColumn(field, header, Object.assign({ width: 120,
    format: (v, r) => (v === null || v === undefined ? (r && (r.total === true || r.blank) ? '' : '—') : fmt(v)) }, o || {}));
}
