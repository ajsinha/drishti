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
/* Numbers as Drishti shows them, the same rules as the server (drishti-rachana FormatSpec): half-up rounding of the decimal value (never
   the binary one: 1.005 to two places is 1.01), commas every three digits, the true minus sign (U+2212), a value that rounds to zero is
   zero (never "−0.00"), "+" on request. Named formats (amount0, pct2, compact, dmy, ...) come from the server's formats.yaml. Every
   function is pure and runs under Node. */
import { SERVER_FORMATS } from './drishti_formats-table.js';
import { formatDate } from './drishti_dates.js';

/** The minus sign every Drishti number uses (U+2212), as the server writes it. */
export const MINUS = '−';
/** What an empty or missing value reads as on a screen. */
export const EMPTY = '—';
/** What a value masked for the person reads as (DataNode.MASK on the server). */
export const MASK = '•••';

const named = Object.assign({}, SERVER_FORMATS);

/**
 * The digits of a number's absolute value as a plain decimal string (never an exponent): the shortest text that reads back as the
 * number, exactly what the server's BigDecimal.valueOf starts from.
 * @param {number} a a finite number
 * @returns {string} for example "0.000001" or "1230000000000000000000"
 */
export function plainDigits(a) {
  let s = String(Math.abs(a));
  const e = s.indexOf('e');
  if (e < 0) { return s; }
  const exp = parseInt(s.slice(e + 1), 10);
  let mant = s.slice(0, e);
  const dot = mant.indexOf('.');
  const digits = mant.replace('.', '');
  const pointAt = (dot < 0 ? mant.length : dot) + exp;
  if (pointAt <= 0) { return '0.' + '0'.repeat(-pointAt) + digits; }
  if (pointAt >= digits.length) { return digits + '0'.repeat(pointAt - digits.length); }
  return digits.slice(0, pointAt) + '.' + digits.slice(pointAt);
}

/**
 * The absolute value rounded half up to a number of decimals, as text: the decimal rule of the server (BigDecimal HALF_UP).
 * @param {number} v a finite number (its sign is ignored)
 * @param {number} decimals how many decimals, 0 or more
 * @returns {string} for example roundHalfUp(1.005, 2) is "1.01" and roundHalfUp(2.5, 0) is "3"
 */
export function roundHalfUp(v, decimals) {
  const s = plainDigits(v);
  const dot = s.indexOf('.');
  let whole = dot < 0 ? s : s.slice(0, dot);
  let frac = dot < 0 ? '' : s.slice(dot + 1);
  if (frac.length <= decimals) { frac = frac + '0'.repeat(decimals - frac.length); return decimals ? whole + '.' + frac : whole; }
  const up = frac.charCodeAt(decimals) >= 53;                // the first dropped digit is 5 or more
  let kept = (whole + frac.slice(0, decimals)).split('');
  if (up) {
    let i = kept.length - 1;
    while (i >= 0) {
      if (kept[i] === '9') { kept[i] = '0'; i--; } else { kept[i] = String.fromCharCode(kept[i].charCodeAt(0) + 1); break; }
    }
    if (i < 0) { kept.unshift('1'); }
  }
  const all = kept.join('');
  whole = all.slice(0, all.length - decimals) || '0';
  frac = all.slice(all.length - decimals);
  return decimals ? whole + '.' + frac : whole;
}

/**
 * Commas every three digits of the whole part of plain digits ("1234567.5" is "1,234,567.5").
 * @param {string} plain digits with an optional decimal point
 * @param {string} [separator=','] the group separator
 * @returns {string}
 */
export function group(plain, separator = ',') {
  const dot = plain.indexOf('.');
  const whole = dot < 0 ? plain : plain.slice(0, dot);
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, separator) + (dot < 0 ? '' : plain.slice(dot));
}

function isZeroText(t) { return !/[1-9]/.test(t); }
function trimZeros(t, keep) {
  const dot = t.indexOf('.');
  if (dot < 0) { return t; }
  let end = t.length;
  while (end > dot + 1 + keep && t.charAt(end - 1) === '0') { end--; }
  if (end === dot + 1) { end = dot; }
  return t.slice(0, end);
}
const localeCache = {};
function localeParts(locale) {
  if (!locale) { return null; }
  if (!localeCache[locale]) {
    const parts = new Intl.NumberFormat(locale).formatToParts(12345.6);
    const find = (type, d) => { const p = parts.find((x) => x.type === type); return p ? p.value : d; };
    localeCache[locale] = { group: find('group', ''), decimal: find('decimal', '.') };
  }
  return localeCache[locale];
}

/**
 * @typedef {Object} NumberOptions
 * @property {number} [decimals=0] decimals shown (half up); with maxDecimals, the fewest shown
 * @property {number} [maxDecimals] the most decimals shown: trailing zeros beyond decimals are dropped (1.50 reads 1.5)
 * @property {boolean} [grouping=true] commas every three digits
 * @property {('auto'|'always'|'never')} [sign='auto'] auto: a minus when negative; always: "+" on a positive value too; never: no sign
 * @property {string} [minus='−'] the minus sign (the console's few ASCII-minus charts pass '-')
 * @property {number} [scale=1] multiplied in first (100 for a percentage of a fraction)
 * @property {string} [prefix=''] before the digits, after the sign ("$")
 * @property {string} [suffix=''] after the digits ("%", " bp")
 * @property {string} [empty='—'] what null, undefined, NaN and the infinities read as
 * @property {string} [locale] a BCP 47 locale for the group and decimal separators (default: the server's: comma and point)
 */

/**
 * A number formatted by the server's rules (FormatSpec.number), with the console's options.
 * @param {*} v the value: a number, or text that reads as one; anything else is shown as text
 * @param {NumberOptions} [o]
 * @returns {string} for example formatNumber(-1234.5, {decimals: 2}) is "−1,234.50"
 * @example formatNumber(0.1234, {decimals: 1, scale: 100, suffix: '%'})   // "12.3%"
 */
export function formatNumber(v, o) {
  o = o || {};
  if (v === MASK) { return MASK; }
  if (v === null || v === undefined || v === '') { return o.empty !== undefined ? o.empty : EMPTY; }
  const n = typeof v === 'number' ? v : Number(v);
  if (typeof v !== 'number' && (typeof v !== 'string' || isNaN(n))) { return String(v); }
  if (!isFinite(n)) { return o.empty !== undefined ? o.empty : EMPTY; }
  const d = n * (o.scale === undefined ? 1 : o.scale);
  const decimals = o.maxDecimals !== undefined ? Math.max(o.maxDecimals, o.decimals || 0) : (o.decimals || 0);
  let digits = roundHalfUp(d, decimals);
  if (o.maxDecimals !== undefined) { digits = trimZeros(digits, o.decimals || 0); }
  const zero = isZeroText(digits);
  const loc = localeParts(o.locale);
  if (o.grouping !== false) { digits = group(digits, loc ? '\u0000' : ','); }
  if (loc) { digits = digits.replace('.', loc.decimal).replace(/\u0000/g, loc.group); }
  const sign = o.sign || 'auto';
  const s = sign === 'never' || zero ? '' : d < 0 ? (o.minus !== undefined ? o.minus : MINUS) : sign === 'always' ? '+' : '';
  return s + (o.prefix || '') + digits + (o.suffix || '');
}

/**
 * A fraction as a percentage (0.1234 reads "12.3%").
 * @param {*} v the fraction
 * @param {NumberOptions} [o] decimals default 1; scale default 100; suffix default "%"
 * @returns {string}
 */
export function formatPercent(v, o) {
  return formatNumber(v, Object.assign({ decimals: 1, scale: 100, suffix: '%' }, o));
}

/**
 * Basis points, as the server's bp1 format: one decimal, a sign always, " bp". The value is in basis points already; pass
 * {scale: 10000} for a fraction (0.0012 reads "+12.0 bp").
 * @param {*} v the value in basis points (or a fraction with scale 10000)
 * @param {NumberOptions} [o]
 * @returns {string}
 */
export function formatBasisPoints(v, o) {
  return formatNumber(v, Object.assign({ decimals: 1, sign: 'always', suffix: ' bp' }, o));
}

/**
 * An amount of money: the currency's usual decimals (two for USD, none for JPY) and its symbol before the digits when the locale has one
 * ("$1,234.50"), else the code after ("1,234.50 CHF").
 * @param {*} v the amount
 * @param {string} currency three letters (USD)
 * @param {NumberOptions & {symbol: boolean}} [o] symbol false: the code after the digits always
 * @returns {string}
 */
export function formatCurrency(v, currency, o) {
  o = o || {};
  const code = String(currency || '').toUpperCase();
  let decimals = 2, symbol = '';
  try {
    const f = new Intl.NumberFormat(o.locale || 'en-US', { style: 'currency', currency: code });
    decimals = f.resolvedOptions().maximumFractionDigits;
    const p = f.formatToParts(1).find((x) => x.type === 'currency');
    symbol = p && p.value !== code ? p.value : '';
  } catch (e) { /* not a currency Intl knows: two decimals and the code */ }
  const base = Object.assign({ decimals: decimals }, o);
  return o.symbol !== false && symbol ? formatNumber(v, Object.assign(base, { prefix: symbol })) : formatNumber(v, Object.assign(base, { suffix: ' ' + code }));
}

/**
 * @typedef {Object} CompactOptions
 * @property {(number|function(number, string): number)} [decimals=1] decimals, or a function of (the absolute value, the unit)
 * @property {boolean} [trim=false] drop trailing zeros (1.50m reads 1.5m)
 * @property {Array<Array<*>>} [units] [suffix, size, from] largest first; from (default size) is where the unit starts (the server's:
 *   bn, m, k; pass [['tn', 1e12], ...] for trillions)
 * @property {(number|null)} [smallDecimals] decimals below the smallest unit (default: decimals)
 * @property {boolean} [smallGrouping=true] commas below the smallest unit
 * @property {('auto'|'always'|'never')} [sign='auto']
 * @property {string} [minus='−']
 * @property {string} [prefix=''] before the digits ("$")
 * @property {string} [empty='—']
 */

/** The server's compact units (FormatSpec.compact). */
export const COMPACT_UNITS = Object.freeze([['bn', 1e9], ['m', 1e6], ['k', 1e3]]);

/**
 * A large number in few characters (1,234,567 reads "1.2m"), the server's compact format by default.
 * @param {*} v the value
 * @param {CompactOptions} [o]
 * @returns {string}
 * @example formatCompact(-2.5e9)   // "−2.5bn"
 */
export function formatCompact(v, o) {
  o = o || {};
  const empty = o.empty !== undefined ? o.empty : EMPTY;
  if (v === MASK) { return MASK; }
  if (v === null || v === undefined || v === '' || (typeof v !== 'number' && isNaN(Number(v)))) { return empty; }
  const n = Number(v);
  if (!isFinite(n)) { return empty; }
  const a = Math.abs(n), units = o.units || COMPACT_UNITS;
  let unit = '', size = 1;
  for (let i = 0; i < units.length; i++) {
    const u = units[i];
    if (a >= (u[2] !== undefined ? u[2] : u[1])) { unit = u[0]; size = u[1]; break; }
  }
  const dec = o.decimals === undefined ? 1 : o.decimals;
  let places = typeof dec === 'function' ? dec(a, unit) : dec;
  if (!unit && o.smallDecimals !== undefined && o.smallDecimals !== null) { places = o.smallDecimals; }
  const opts = { decimals: o.trim ? 0 : places, maxDecimals: o.trim ? places : undefined, grouping: unit ? true : o.smallGrouping !== false,
    sign: o.sign, minus: o.minus, prefix: o.prefix, suffix: unit, empty: empty };
  return formatNumber(n / size, opts);
}

/**
 * Adds or replaces named formats (a pack's or a site's formats.yaml, as the server reads them), for formatNamed.
 * @param {Object<string, Object>} table name to {type, decimals, grouping, sign, scale, suffix, pattern}
 * @returns {void}
 */
export function defineFormats(table) {
  Object.keys(table || {}).forEach((k) => {
    const f = table[k] || {};
    named[k] = { type: f.type || 'number', decimals: f.decimals | 0, grouping: f.grouping !== false, sign: f.sign || 'auto',
      scale: f.scale === undefined ? 1 : Number(f.scale), suffix: f.suffix === undefined ? null : f.suffix, pattern: f.pattern === undefined ? null : f.pattern };
  });
}

/**
 * Whether a named format is known (bundled or defined).
 * @param {string} name
 * @returns {boolean}
 */
export function hasFormat(name) { return Object.prototype.hasOwnProperty.call(named, name); }

/**
 * A named format's spec (a copy), or null.
 * @param {string} name
 * @returns {?{type: string, decimals: number, grouping: boolean, sign: string, scale: number, suffix: ?string, pattern: ?string}}
 */
export function namedFormat(name) { return hasFormat(name) ? Object.assign({}, named[name]) : null; }

/**
 * The known named formats.
 * @returns {string[]}
 */
export function formatNames() { return Object.keys(named).sort(); }

/**
 * A value by a format spec, exactly as the server's FormatSpec.format: null reads "" (the server's empty), a masked value its mask,
 * text, date (by a Java-style pattern), compact, or a number.
 * @param {{type: string, decimals: number, grouping: boolean, sign: string, scale: number, suffix: ?string, pattern: ?string}} spec
 * @param {*} v
 * @returns {string}
 */
export function formatSpec(spec, v) {
  if (v === null || v === undefined) { return ''; }
  if (v === MASK) { return MASK; }
  if (typeof v === 'object' && v && v.display !== undefined) { return String(v.display); }   // a link: its display text
  if (spec.type === 'text') { return String(v); }
  if (spec.type === 'date') { return formatDate(v, spec.pattern || 'yyyy-MM-dd'); }
  const n = typeof v === 'number' ? v : typeof v === 'boolean' ? (v ? 1 : 0) : Number(v);
  if (spec.type === 'compact') {
    return isNaN(n) ? '' : formatCompact(n, { decimals: spec.decimals, sign: spec.sign, empty: '' });
  }
  if (isNaN(n)) { return String(v); }
  return formatNumber(n, { decimals: spec.decimals, grouping: spec.grouping, sign: spec.sign, scale: spec.scale, suffix: spec.suffix || '', empty: '' });
}

/**
 * A value by a named format (fmt: amount0, signed2, pct2, compact, dmy, ...), the server's Formats.format: an unknown or missing name
 * shows the value as text.
 * @param {string} name the format's name
 * @param {*} v the value
 * @returns {string}
 * @example formatNamed('pct2', 0.01234)   // "1.23%"
 */
export function formatNamed(name, v) {
  const s = name ? named[name] : null;
  if (!s) { return v === null || v === undefined ? '' : String(v); }
  return formatSpec(s, v);
}

/**
 * A formatter function with its options fixed, for a screen's one-line helpers (const money = formatter('compact', {...})).
 * @param {('number'|'percent'|'bp'|'compact'|'currency')} kind
 * @param {Object} [options] the kind's options (currency: {currency, ...})
 * @returns {function(*): string}
 */
export function formatter(kind, options) {
  const o = Object.assign({}, options);
  switch (kind) {
    case 'number': return (v) => formatNumber(v, o);
    case 'percent': return (v) => formatPercent(v, o);
    case 'bp': return (v) => formatBasisPoints(v, o);
    case 'compact': return (v) => formatCompact(v, o);
    case 'currency': return (v) => formatCurrency(v, o.currency, o);
    default: throw new Error('formatter: no kind ' + kind + ' (number, percent, bp, compact, currency)');
  }
}
