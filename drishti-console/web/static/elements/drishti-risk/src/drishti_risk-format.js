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
/* The risk screens' figures in words, one home (they were each screen's own): compact money as the VaR mock-ups show it (1.23m, -412k,
   950: whole thousands, two decimals from a million up, the plain minus; drishti-core's rounding, half up as the server's FormatSpec),
   the same with a sign, whole units with grouping and a share with one decimal (as the screens printed them before Elements, so no
   figure on a screen changed when it moved). */
import { formatter } from '../../drishti-core/src/drishti_index.js';

const compact = formatter('compact', { decimals: (a, u) => (u === 'k' || !u ? 0 : 2), minus: '-' });

/**
 * Compact money: 1.23m, -412k, 950; an em dash for no figure.
 * @param {?number} v
 * @returns {string}
 */
export function riskMoney(v) { return v === null || v === undefined || Number.isNaN(v) ? '—' : compact(v); }

/**
 * Compact money with its sign: +1.23m, -412k.
 * @param {?number} v
 * @returns {string}
 */
export function riskSigned(v) { return v === null || v === undefined || Number.isNaN(v) ? '—' : (v > 0 ? '+' : '') + compact(v); }

/**
 * Whole units with grouping: 1,234,567 and -12,000; an em dash for no figure.
 * @param {?number} v
 * @returns {string}
 */
export function riskInt(v) { return v === null || v === undefined || Number.isNaN(v) ? '—' : Math.round(v).toLocaleString('en-US'); }

/**
 * A share with one decimal: 0.4567 is 45.7%; an em dash for no figure.
 * @param {?number} v
 * @returns {string}
 */
export function riskPct(v) { return v === null || v === undefined || Number.isNaN(v) ? '—' : (v * 100).toFixed(1) + '%'; }
