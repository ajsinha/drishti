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
/* Business dates: a date is a calendar day (yyyy-MM-dd), never a moment, so no time zone ever moves it. Patterns are the server's
   (java.time DateTimeFormatter letters: yyyy, yy, MMMM, MMM, MM, M, dd, d, EEEE, EEE; text in single quotes), and the default, yyyy-MM-dd,
   leaves the ISO text as it is, as the server does. Business days skip Saturdays, Sundays and the holidays given. Pure; runs under Node. */

const ISO = /^(\d{4})-(\d{2})-(\d{2})/;
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/**
 * A business date: year, month (1 to 12) and day.
 * @typedef {Object} BusinessDate
 * @property {number} y
 * @property {number} m 1 to 12
 * @property {number} d 1 to 31
 */

/**
 * A value read as a business date: ISO text (yyyy-MM-dd, a longer timestamp's first ten characters), a Date (its local calendar day) or a
 * BusinessDate. Anything else, or a day that does not exist (2026-02-30), is null.
 * @param {*} v
 * @returns {?BusinessDate}
 */
export function parseDate(v) {
  if (v === null || v === undefined) { return null; }
  if (typeof v === 'object' && typeof v.y === 'number') { return valid(v.y, v.m, v.d); }
  if (v instanceof Date) { return isNaN(v.getTime()) ? null : { y: v.getFullYear(), m: v.getMonth() + 1, d: v.getDate() }; }
  const m = ISO.exec(String(v));
  return m ? valid(+m[1], +m[2], +m[3]) : null;
}
function valid(y, m, d) {
  if (m < 1 || m > 12 || d < 1) { return null; }
  return d <= daysInMonth(y, m) ? { y: y, m: m, d: d } : null;
}

/**
 * Days in a month.
 * @param {number} y
 * @param {number} m 1 to 12
 * @returns {number}
 */
export function daysInMonth(y, m) { return new Date(Date.UTC(y, m, 0)).getUTCDate(); }

function pad(n, w) { return String(n).padStart(w, '0'); }

/**
 * A business date as ISO text, yyyy-MM-dd.
 * @param {*} v anything parseDate reads
 * @returns {string} '' when it is not a date
 */
export function isoDate(v) {
  const b = parseDate(v);
  return b ? pad(b.y, 4) + '-' + pad(b.m, 2) + '-' + pad(b.d, 2) : '';
}

/**
 * Today's business date in this browser's calendar, as ISO text.
 * @param {Date} [now] the moment (default: now)
 * @returns {string}
 */
export function today(now) { return isoDate(now || new Date()); }

function weekday(b) { return new Date(Date.UTC(b.y, b.m - 1, b.d)).getUTCDay(); }
function monthName(m, width, locale) {
  if (!locale || /^en\b/.test(locale)) { return width === 'long' ? MONTHS[m - 1] : MONTHS[m - 1].slice(0, 3); }
  return new Intl.DateTimeFormat(locale, { month: width, timeZone: 'UTC' }).format(new Date(Date.UTC(2000, m - 1, 1)));
}
function dayName(w, width, locale) {
  if (!locale || /^en\b/.test(locale)) { return width === 'long' ? DAYS[w] : DAYS[w].slice(0, 3); }
  return new Intl.DateTimeFormat(locale, { weekday: width, timeZone: 'UTC' }).format(new Date(Date.UTC(2000, 0, 2 + w)));
}

/**
 * A date by a server pattern (dd MMM yyyy reads "07 Nov 2026"). yyyy-MM-dd, the default, returns the ISO text unchanged, as the server
 * does; text that is not a date is returned as it is.
 * @param {*} v ISO text, a Date or a BusinessDate
 * @param {string} [pattern='yyyy-MM-dd'] DateTimeFormatter letters: yyyy yy MMMM MMM MM M dd d EEEE EEE, 'quoted text'
 * @param {{locale: string}} [o] month and day names in this locale (default English, as the server)
 * @returns {string}
 */
export function formatDate(v, pattern, o) {
  if (v === null || v === undefined) { return ''; }
  const b = parseDate(v);
  if (!b) { return String(v); }
  if (!pattern || pattern === 'yyyy-MM-dd') { return typeof v === 'string' ? v : isoDate(b); }
  const locale = o && o.locale;
  return pattern.replace(/'([^']*)'|y{4}|y{2}|M{3,4}|M{1,2}|d{1,2}|E{4}|E{1,3}/g, (t, quoted) => {
    if (quoted !== undefined) { return quoted; }
    switch (t) {
      case 'yyyy': return pad(b.y, 4);
      case 'yy': return pad(b.y % 100, 2);
      case 'MMMM': return monthName(b.m, 'long', locale);
      case 'MMM': return monthName(b.m, 'short', locale);
      case 'MM': return pad(b.m, 2);
      case 'M': return String(b.m);
      case 'dd': return pad(b.d, 2);
      case 'd': return String(b.d);
      case 'EEEE': return dayName(weekday(b), 'long', locale);
      default: return dayName(weekday(b), 'short', locale);
    }
  });
}

/**
 * A range of business dates in few words, the shared parts once: "3–7 Nov 2026", "28 Oct – 3 Nov 2026", "28 Dec 2025 – 3 Jan 2026";
 * one day reads as that day.
 * @param {*} from
 * @param {*} to
 * @param {{locale: string}} [o]
 * @returns {string}
 */
export function formatDateRange(from, to, o) {
  const a = parseDate(from), b = parseDate(to);
  if (!a || !b) { return a ? formatDate(a, 'd MMM yyyy', o) : b ? formatDate(b, 'd MMM yyyy', o) : ''; }
  if (a.y === b.y && a.m === b.m && a.d === b.d) { return formatDate(a, 'd MMM yyyy', o); }
  if (a.y === b.y && a.m === b.m) { return a.d + '–' + formatDate(b, 'd MMM yyyy', o); }
  if (a.y === b.y) { return formatDate(a, 'd MMM', o) + ' – ' + formatDate(b, 'd MMM yyyy', o); }
  return formatDate(a, 'd MMM yyyy', o) + ' – ' + formatDate(b, 'd MMM yyyy', o);
}

function shift(b, days) {
  const t = new Date(Date.UTC(b.y, b.m - 1, b.d + days));
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

/**
 * Whether a day is a business day: not a Saturday or Sunday and not one of the holidays.
 * @param {*} v the date
 * @param {{holidays: string[]}} [o] ISO dates that are not business days
 * @returns {boolean}
 */
export function isBusinessDay(v, o) {
  const b = parseDate(v);
  if (!b) { return false; }
  const w = weekday(b);
  return w !== 0 && w !== 6 && !((o && o.holidays) || []).includes(isoDate(b));
}

/**
 * The business date n business days from a date (n may be negative; 0 is the date itself, rolled forward when it is not a business day).
 * @param {*} v the date
 * @param {number} n business days
 * @param {{holidays: string[]}} [o]
 * @returns {string} ISO text
 */
export function addBusinessDays(v, n, o) {
  let b = parseDate(v);
  if (!b) { throw new Error('addBusinessDays: not a date: ' + v); }
  const step = n < 0 ? -1 : 1;
  while (!isBusinessDay(b, o)) { b = shift(b, step); }
  for (let left = Math.abs(n); left > 0;) {
    b = shift(b, step);
    if (isBusinessDay(b, o)) { left--; }
  }
  return isoDate(b);
}

/**
 * The last business day before a date (a report's usual "previous close").
 * @param {*} v
 * @param {{holidays: string[]}} [o]
 * @returns {string} ISO text
 */
export function previousBusinessDay(v, o) {
  let b = shift(parseDate(v) || parseDate(today()), -1);
  while (!isBusinessDay(b, o)) { b = shift(b, -1); }
  return isoDate(b);
}

/**
 * Business days from one date to another: those after from, up to and including to (negative when to is earlier).
 * @param {*} from
 * @param {*} to
 * @param {{holidays: string[]}} [o]
 * @returns {number}
 */
export function businessDaysBetween(from, to, o) {
  const a = parseDate(from), b = parseDate(to);
  if (!a || !b) { return NaN; }
  const ia = isoDate(a), ib = isoDate(b);
  if (ia === ib) { return 0; }
  const sign = ia < ib ? 1 : -1;
  let x = a, n = 0;
  while (isoDate(x) !== ib) { x = shift(x, sign); if (isBusinessDay(x, o)) { n++; } }
  return sign * n;
}
