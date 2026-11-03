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
/* Formats and tones (CLIENT_RENDERER.md section 6). The server formats every value a person reads; the renderer formats only what the
   macros themselves compute: axis ticks (compact), a donut's share, a bar's width, a heat bucket, and the few raw numbers a chart's
   "Data" table prints. Those follow Python and Jinja exactly (round half to even, a float printed with its ".0"), so the library and the
   console's macros show the same characters. */

/** The tones VIEWMODEL.md lists; any other word draws the value untoned (the console prints the class, the CSS ignores it). */
export const TONES = Object.freeze(['pos', 'neg', 'ok', 'warn', 'bad', 'link', 'accent', 'muted', 'inactive']);

/** The class suffix of a cell's tone, as the macros write it (` t-<tone>`), or '' for none. */
export function toneClass(c) { return c && c.tone ? ' t-' + c.tone : ''; }

/** Whether a tone is one the contract knows. */
export const knownTone = (t) => TONES.includes(t);

/** Python's round(x, n) on a float: half to even at the exact decimal, as Jinja's round filter does. */
export function pyRound(x, n = 0) {
  if (typeof x !== 'number' || !isFinite(x) || Math.abs(x) >= 1e21) { return x; }
  // the double's exact decimal digits (toFixed is exact), then half to even on them, as CPython does
  const s = Math.abs(x).toFixed(Math.min(100, n + 40)), dot = s.indexOf('.');
  const ip = s.slice(0, dot), fp = s.slice(dot + 1), keep = fp.slice(0, n), rest = fp.slice(n);
  const digits = ip + keep, last = Number(digits[digits.length - 1]);
  const up = /^50*$/.test(rest) ? last % 2 === 1 : rest[0] >= '5';
  const r = (Number(digits) + (up ? 1 : 0)) / Math.pow(10, n);
  return x < 0 ? -r : r;
}

/** Python's str() of a float: "74.0" for a whole number, exponents past 1e16 and below 1e-4 the way Python writes them. */
export function pyFloat(x) {
  if (typeof x !== 'number') { return x == null ? '' : String(x); }
  if (!isFinite(x)) { return isNaN(x) ? 'nan' : (x > 0 ? 'inf' : '-inf'); }
  if (x === 0) { return Object.is(x, -0) ? '-0.0' : '0.0'; }
  const a = Math.abs(x);
  if (a >= 1e16 || a < 1e-4) {
    let s = x.toExponential();                     // "1e+16", "1.5e-5"
    s = s.replace(/e([+-])(\d)$/, 'e$10$2');       // Python pads the exponent to two digits
    return s;
  }
  const s = String(x);
  return /[.e]/.test(s) ? s : s + '.0';
}

/** Python's str() of a value Jackson wrote as an integer (counts) or as a double (prices): ints print plain, doubles with ".0". */
export const pyNum = (x, isFloat) => (typeof x === 'number' && isFloat ? pyFloat(x) : (x == null ? '' : String(x)));

/** An axis tick as the console's charts write it (charts.js compact): 1.75m, 250k, −1.2bn. */
export function compact(v) {
  if (typeof v !== 'number' || !isFinite(v)) { return ''; }
  const a = Math.abs(v), sign = v < 0 ? '−' : '';
  const trim = (x, dp) => String(Number(x.toFixed(dp)));
  if (a >= 1e9) { return sign + trim(a / 1e9, 2) + 'bn'; }
  if (a >= 1e6) { return sign + trim(a / 1e6, a >= 1e8 ? 0 : 2) + 'm'; }
  if (a >= 1e4) { return sign + trim(a / 1e3, a >= 1e6 ? 0 : 1) + 'k'; }
  return sign + (Math.round(a * 100) / 100);
}

/** The line chart's own ticks (view.js compact), which differ from the other charts': kept as they are so both draw the same axes. */
export function lineCompact(v) {
  if (typeof v !== 'number' || !isFinite(v)) { return ''; }
  const a = Math.abs(v);
  if (a >= 1e9) { return (v / 1e9).toFixed(1) + 'bn'; }
  if (a >= 1e6) { return (v / 1e6).toFixed(a >= 1e7 ? 0 : 1) + 'm'; }
  if (a >= 1e5) { return (v / 1e3).toFixed(0) + 'k'; }
  if (a >= 1e4) { return (v / 1e3).toFixed(1) + 'k'; }
  return Math.round(v * 100) / 100;
}

/** A bar's width in percent of the longest (the macro's data-w): |value| / max * 100, rounded to one decimal. */
export function barWidth(value, max) { return pyRound(Math.abs(value) / (max || 1) * 100, 1); }

/** A share of a whole as the donut legend prints it: one decimal, always shown ("50.0"). */
export function share(value, whole) { return pyFloat(pyRound(Math.abs(value) / (whole || 1) * 100, 1)); }

/** The heat bucket 0..9 of a pivot cell between min and max (5 when the scale is flat). */
export function heat(v, lo, hi) {
  const span = (typeof lo === 'number' && typeof hi === 'number' && hi > lo) ? hi - lo : 0;
  return span ? Math.trunc(pyRound((v - lo) / span * 9)) : 5;
}

/** The gauge's level in percent, as the macro computes it (0 when the value is not a number). */
export function gaugeWidth(v, max) {
  if (typeof v !== 'number' || !(typeof max === 'number' || max == null)) { return 0; }
  return pyRound(v / (max || 1) * 100, 1);
}

/** Jinja's `capitalize`: the first character upper case, the rest lower case. */
export const capitalize = (s) => { s = String(s || ''); return s ? s[0].toUpperCase() + s.slice(1).toLowerCase() : s; };

/** A plain number for a data-w attribute, as Jinja prints a float (36.0 stays "36.0"). */
export const wText = (x) => (typeof x === 'number' && !Number.isInteger(x) ? String(x) : (typeof x === 'number' ? pyFloat(x) : String(x)));
