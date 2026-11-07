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
/* Reading what a person typed or pasted, the one home of it (a form's field, a grid's cell editor, a filter's value, a paste from
   Excel): numbers in a locale, with grouping, a true minus, brackets for a negative, k, m and bn, percentages. */

/**
 * A number from typed or pasted text: 1,250,000.5; −1.5; (2,000); 1.25m, 300k, 2bn; 12.5%; in a locale whose marks differ
 * (de-DE: 1.250.000,5). Null when it does not read.
 * @param {string} text
 * @param {string} [locale]
 * @returns {?number}
 */
export function parseNumberText(text, locale) {
  let s = String(text === null || text === undefined ? '' : text).trim().replace(/[  \s]/g, '').replace(/−/g, '-');
  if (!s) { return null; }
  let neg = false;
  if (/^\(.*\)$/.test(s)) { neg = true; s = s.slice(1, -1); }
  let dec = '.', grp = ',';
  if (locale && typeof Intl !== 'undefined') {
    const parts = new Intl.NumberFormat(locale).formatToParts(12345.6);
    dec = (parts.find((p) => p.type === 'decimal') || { value: '.' }).value;
    grp = (parts.find((p) => p.type === 'group') || { value: ',' }).value.replace(/[  \s]/g, '');
  }
  let mult = 1;
  const m = /(%|bn|b|m|k)$/i.exec(s);
  if (m) { mult = { '%': 0.01, bn: 1e9, b: 1e9, m: 1e6, k: 1e3 }[m[1].toLowerCase()]; s = s.slice(0, -m[1].length); }
  if (grp) { s = s.split(grp).join(''); }
  if (dec !== '.') { s = s.replace(dec, '.'); }
  if (!/^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(s)) { return null; }
  const v = mult === 1 ? Number(s) : +(Number(s) * mult).toPrecision(15);
  return isNaN(v) ? null : (neg ? -v : v);
}

/**
 * A number as an editor shows it for changing: no grouping, every digit, the locale's decimal mark and the true minus.
 * @param {*} v
 * @param {string} [locale]
 * @returns {string}
 */
export function numberEditText(v, locale) {
  if (typeof v !== 'number' || isNaN(v)) { return v === null || v === undefined ? '' : String(v); }
  const s = String(v);
  if (!locale || typeof Intl === 'undefined') { return s.replace('-', '−'); }
  const dec = (new Intl.NumberFormat(locale).formatToParts(1.5).find((p) => p.type === 'decimal') || { value: '.' }).value;
  return s.replace('.', dec).replace('-', '−');
}
