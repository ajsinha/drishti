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
/* Business calendars, the model behind every date picker: holiday calendars built from rules (a fixed day with its weekend roll, the
   n-th weekday of a month, the weekday before a day, Easter and its offsets) or from the server's own list of dates, a weekend that may
   be any days, unions of calendars ("US+UK"), and the business dates a risk person asks for (T-1, T-n, month-end, quarter-end, year-end,
   the first business day of a month). The date-range presets (Today, T-1, WTD, MTD, QTD, YTD, the previous month, quarter and year,
   the last n business days) and the text a person types ("T-3", "ME", "2026-11-06", "6 Nov 2026") resolve here. A date is a calendar day
   (ISO text), never a moment. Pure; runs under Node.

   The bundled calendars (US, UK, TARGET, CA) are the usual market rules and are illustrative: a site gives its official calendars with
   defineCalendar (the server's holiday calendars, as dates), and those win. */
import { parseDate, isoDate, daysInMonth, today } from '../../drishti-core/src/drishti_index.js';

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

/**
 * A holiday rule. One of: {fixed: 'MM-DD', roll}, {nth, weekday, month} (nth -1: the last), {before: 'MM-DD', weekday} (the weekday
 * strictly before a day: Victoria Day), {easter: offset} (days from Easter Sunday: -2 Good Friday, 1 Easter Monday). roll for a fixed
 * day on a weekend: 'nearest' (Saturday to Friday, Sunday to Monday), 'monday' (to the next Monday, or Tuesday when Monday is taken),
 * 'none'. from and to bound the years the rule holds.
 * @typedef {Object} HolidayRule
 * @property {string} name
 * @property {string} [fixed]
 * @property {('nearest'|'monday'|'none')} [roll='none']
 * @property {number} [nth]
 * @property {number} [weekday] 0 Sunday to 6 Saturday
 * @property {number} [month] 1 to 12
 * @property {string} [before]
 * @property {number} [easter]
 * @property {number} [from] the first year
 * @property {number} [to] the last year
 */

/** The bundled market calendars as rules (illustrative; a site's own calendars replace them through defineCalendar). */
export const CALENDAR_RULES = Object.freeze({
  US: [{ name: 'New Year\'s Day', fixed: '01-01', roll: 'nearest' }, { name: 'Martin Luther King Jr. Day', nth: 3, weekday: 1, month: 1 },
    { name: 'Presidents\' Day', nth: 3, weekday: 1, month: 2 }, { name: 'Good Friday', easter: -2 }, { name: 'Memorial Day', nth: -1, weekday: 1, month: 5 },
    { name: 'Juneteenth', fixed: '06-19', roll: 'nearest', from: 2022 }, { name: 'Independence Day', fixed: '07-04', roll: 'nearest' },
    { name: 'Labor Day', nth: 1, weekday: 1, month: 9 }, { name: 'Thanksgiving', nth: 4, weekday: 4, month: 11 }, { name: 'Christmas Day', fixed: '12-25', roll: 'nearest' }],
  UK: [{ name: 'New Year\'s Day', fixed: '01-01', roll: 'monday' }, { name: 'Good Friday', easter: -2 }, { name: 'Easter Monday', easter: 1 },
    { name: 'Early May bank holiday', nth: 1, weekday: 1, month: 5 }, { name: 'Spring bank holiday', nth: -1, weekday: 1, month: 5 },
    { name: 'Summer bank holiday', nth: -1, weekday: 1, month: 8 }, { name: 'Christmas Day', fixed: '12-25', roll: 'monday' }, { name: 'Boxing Day', fixed: '12-26', roll: 'monday' }],
  TARGET: [{ name: 'New Year\'s Day', fixed: '01-01' }, { name: 'Good Friday', easter: -2 }, { name: 'Easter Monday', easter: 1 }, { name: 'Labour Day', fixed: '05-01' },
    { name: 'Christmas Day', fixed: '12-25' }, { name: 'St Stephen\'s Day', fixed: '12-26' }],
  CA: [{ name: 'New Year\'s Day', fixed: '01-01', roll: 'monday' }, { name: 'Family Day', nth: 3, weekday: 1, month: 2 }, { name: 'Good Friday', easter: -2 },
    { name: 'Victoria Day', before: '05-25', weekday: 1 }, { name: 'Canada Day', fixed: '07-01', roll: 'monday' }, { name: 'Civic Holiday', nth: 1, weekday: 1, month: 8 },
    { name: 'Labour Day', nth: 1, weekday: 1, month: 9 }, { name: 'Thanksgiving', nth: 2, weekday: 1, month: 10 }, { name: 'Christmas Day', fixed: '12-25', roll: 'monday' },
    { name: 'Boxing Day', fixed: '12-26', roll: 'monday' }]
});

function pad(n) { return String(n).padStart(2, '0'); }
function iso(y, m, d) { return String(y).padStart(4, '0') + '-' + pad(m) + '-' + pad(d); }
function wd(y, m, d) { return new Date(Date.UTC(y, m - 1, d)).getUTCDay(); }
function shiftIso(s, days) { const b = parseDate(s), t = new Date(Date.UTC(b.y, b.m - 1, b.d + days)); return iso(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()); }

/**
 * Easter Sunday of a year (the Gregorian computus).
 * @param {number} y
 * @returns {string} ISO date
 */
export function easterSunday(y) {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3);
  const hh = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - hh - k) % 7, m = Math.floor((a + 11 * hh + 22 * l) / 451);
  const month = Math.floor((hh + l - 7 * m + 114) / 31), day = ((hh + l - 7 * m + 114) % 31) + 1;
  return iso(y, month, day);
}

/**
 * The day a rule falls on in a year (null when it does not hold that year).
 * @param {HolidayRule} r
 * @param {number} y
 * @returns {?string} ISO date
 */
export function ruleDate(r, y) {
  if ((r.from && y < r.from) || (r.to && y > r.to)) { return null; }
  if (r.easter !== undefined) { return shiftIso(easterSunday(y), r.easter); }
  if (r.fixed) {
    const [m, d] = r.fixed.split('-').map(Number), w = wd(y, m, d), day = iso(y, m, d);
    if (r.roll === 'nearest') { return w === 6 ? shiftIso(day, -1) : w === 0 ? shiftIso(day, 1) : day; }
    if (r.roll === 'monday') { return w === 6 ? shiftIso(day, 2) : w === 0 ? shiftIso(day, 1) : day; }
    return day;
  }
  if (r.before) {
    const [m, d] = r.before.split('-').map(Number);
    let day = shiftIso(iso(y, m, d), -1);
    while (wd(...day.split('-').map(Number)) !== r.weekday) { day = shiftIso(day, -1); }
    return day;
  }
  if (r.nth) {
    if (r.nth > 0) { const first = wd(y, r.month, 1), d = 1 + ((r.weekday - first + 7) % 7) + (r.nth - 1) * 7; return d <= daysInMonth(y, r.month) ? iso(y, r.month, d) : null; }
    const last = daysInMonth(y, r.month), lw = wd(y, r.month, last), d = last - ((lw - r.weekday + 7) % 7) + (r.nth + 1) * 7;
    return d >= 1 ? iso(y, r.month, d) : null;
  }
  throw new Error('a holiday rule needs fixed, nth, before or easter: ' + JSON.stringify(r));
}

/**
 * A business calendar: its weekend, its holidays (from rules and from dates), and the business dates asked of it.
 */
export class HolidayCalendar {
  /**
   * @param {{name: string, rules: HolidayRule[], dates: Array<(string|{date: string, name: string})>, weekend: number[]}} [o] weekend: the
   *   days that are never business days (default [0, 6], Sunday and Saturday); dates: holidays as given (the server's list)
   */
  constructor(o) {
    o = o || {};
    this.name = o.name || 'weekends';
    this.rules = (o.rules || []).slice();
    this.weekend = new Set(o.weekend || [0, 6]);
    this.given = new Map();
    (o.dates || []).forEach((x) => { const d = typeof x === 'string' ? { date: x, name: 'Holiday' } : x; this.given.set(isoDate(d.date), d.name || 'Holiday'); });
    this.years = new Map();
  }

  /**
   * The holidays of a year in order, each with its name.
   * @param {number} y
   * @returns {Array<{date: string, name: string}>}
   */
  holidays(y) {
    if (!this.years.has(y)) {
      const m = new Map();
      this.rules.forEach((r) => {
        let d = ruleDate(r, y);
        if (!d) { return; }
        if (m.has(d) && r.roll === 'monday') { while (m.has(d) || this.weekend.has(wd(...d.split('-').map(Number)))) { d = shiftIso(d, 1); } }
        if (!m.has(d)) { m.set(d, r.name); }
      });
      this.given.forEach((name, d) => { if (+d.slice(0, 4) === y && !m.has(d)) { m.set(d, name); } });
      this.years.set(y, m);
    }
    return Array.from(this.years.get(y).entries()).sort().map(([date, name]) => ({ date: date, name: name }));
  }

  /**
   * A holiday's name, or null when the day is not a holiday (weekends are not holidays).
   * @param {*} v a date
   * @returns {?string}
   */
  holidayName(v) {
    const b = parseDate(v);
    if (!b) { return null; }
    this.holidays(b.y);
    return this.years.get(b.y).get(isoDate(b)) || null;
  }

  /**
   * Whether a day is a business day: not a weekend day and not a holiday.
   * @param {*} v
   * @returns {boolean}
   */
  isBusinessDay(v) {
    const b = parseDate(v);
    return !!b && !this.weekend.has(wd(b.y, b.m, b.d)) && !this.holidayName(b);
  }

  /**
   * Whether a day is a weekend day of this calendar.
   * @param {*} v
   * @returns {boolean}
   */
  isWeekend(v) { const b = parseDate(v); return !!b && this.weekend.has(wd(b.y, b.m, b.d)); }

  /**
   * The business date n business days away (n negative goes back; 0 rolls a holiday forward to the next business day).
   * @param {*} v
   * @param {number} n
   * @returns {string}
   */
  addBusinessDays(v, n) {
    let d = isoDate(v);
    if (!d) { throw new Error('not a date: ' + v); }
    const step = n < 0 ? -1 : 1;
    let guard = 0;
    while (!this.isBusinessDay(d)) { d = shiftIso(d, step); if (++guard > 400) { throw new Error('no business day near ' + v + ' in calendar ' + this.name); } }
    for (let left = Math.abs(n); left > 0;) { d = shiftIso(d, step); if (this.isBusinessDay(d)) { left--; } }
    return d;
  }

  /**
   * T-n: the n-th business day before a date (T-1 is the previous close).
   * @param {*} v the day T (default today)
   * @param {number} [n=1]
   * @returns {string}
   */
  tMinus(v, n) {
    let d = isoDate(v || today());
    for (let left = n === undefined ? 1 : n; left > 0;) { d = shiftIso(d, -1); if (this.isBusinessDay(d)) { left--; } }
    return d;
  }

  /**
   * The business day on or before a date (a date that is not a business day rolls back).
   * @param {*} v
   * @returns {string}
   */
  onOrBefore(v) { let d = isoDate(v); while (!this.isBusinessDay(d)) { d = shiftIso(d, -1); } return d; }

  /**
   * The business day on or after a date.
   * @param {*} v
   * @returns {string}
   */
  onOrAfter(v) { let d = isoDate(v); while (!this.isBusinessDay(d)) { d = shiftIso(d, 1); } return d; }

  /**
   * The last business day of the month a date is in (month-end).
   * @param {*} v
   * @returns {string}
   */
  monthEnd(v) { const b = parseDate(v); return this.onOrBefore(iso(b.y, b.m, daysInMonth(b.y, b.m))); }

  /**
   * The first business day of the month a date is in.
   * @param {*} v
   * @returns {string}
   */
  monthStart(v) { const b = parseDate(v); return this.onOrAfter(iso(b.y, b.m, 1)); }

  /**
   * The last business day of the quarter a date is in.
   * @param {*} v
   * @returns {string}
   */
  quarterEnd(v) { const b = parseDate(v), m = Math.ceil(b.m / 3) * 3; return this.onOrBefore(iso(b.y, m, daysInMonth(b.y, m))); }

  /**
   * The last business day of the year a date is in.
   * @param {*} v
   * @returns {string}
   */
  yearEnd(v) { const b = parseDate(v); return this.onOrBefore(iso(b.y, 12, 31)); }

  /**
   * The last month-end before a date (the latest month-end that has happened by it, the date itself when it is one).
   * @param {*} v
   * @returns {string}
   */
  previousMonthEnd(v) {
    const d = isoDate(v), me = this.monthEnd(d);
    if (me <= d) { return me; }
    const b = parseDate(d);
    return this.monthEnd(b.m === 1 ? iso(b.y - 1, 12, 1) : iso(b.y, b.m - 1, 1));
  }

  /**
   * Business days after from, up to and including to (negative when to is earlier).
   * @param {*} from
   * @param {*} to
   * @returns {number}
   */
  businessDaysBetween(from, to) {
    const a = isoDate(from), b = isoDate(to);
    if (!a || !b) { return NaN; }
    if (a === b) { return 0; }
    const sign = a < b ? 1 : -1;
    let x = a, n = 0;
    while (x !== b) { x = shiftIso(x, sign); if (this.isBusinessDay(x)) { n++; } }
    return sign * n;
  }
}

const calendars = new Map();

/**
 * Defines (or replaces) a named calendar: rules, the server's dates, a weekend.
 * @param {string} name
 * @param {{rules: HolidayRule[], dates: Array<(string|{date: string, name: string})>, weekend: number[]}} spec
 * @returns {HolidayCalendar}
 */
export function defineCalendar(name, spec) {
  const c = new HolidayCalendar(Object.assign({ name: name }, spec));
  calendars.set(name, c);
  return c;
}

/**
 * A calendar by name ("US", "UK", "TARGET", "CA", a defined one, "weekends", or a union "US+UK": a day is a holiday in the union when it
 * is one in any), or the calendar itself when one is given.
 * @param {(string|HolidayCalendar)} [name='weekends']
 * @returns {HolidayCalendar}
 */
export function calendar(name) {
  if (name instanceof HolidayCalendar) { return name; }
  const key = name || 'weekends';
  if (calendars.has(key)) { return calendars.get(key); }
  if (key.indexOf('+') > 0) {
    const parts = key.split('+').map((p) => calendar(p.trim()));
    const u = new HolidayCalendar({ name: key, weekend: Array.from(parts[0].weekend) });
    u.holidayName = (v) => { for (const p of parts) { const n = p.holidayName(v); if (n) { return n + (parts.length > 1 ? ' (' + p.name + ')' : ''); } } return null; };
    u.holidays = (y) => { const m = new Map(); parts.forEach((p) => p.holidays(y).forEach((x) => { if (!m.has(x.date)) { m.set(x.date, x.name + ' (' + p.name + ')'); } })); return Array.from(m.entries()).sort().map(([date, n]) => ({ date: date, name: n })); };
    calendars.set(key, u);
    return u;
  }
  if (key === 'weekends') { return defineCalendar('weekends', {}); }
  if (CALENDAR_RULES[key]) { return defineCalendar(key, { rules: CALENDAR_RULES[key] }); }
  throw new Error('there is no calendar ' + key + ' (bundled: ' + Object.keys(CALENDAR_RULES).join(', ') + '; or defineCalendar)');
}

/**
 * The date-range presets, in the order a picker shows them.
 * @type {Array<{id: string, label: string}>}
 */
export const DATE_PRESETS = Object.freeze([
  { id: 'today', label: 'Today' }, { id: 't-1', label: 'T-1' }, { id: 'wtd', label: 'Week to date' }, { id: 'mtd', label: 'Month to date' },
  { id: 'qtd', label: 'Quarter to date' }, { id: 'ytd', label: 'Year to date' }, { id: 'last-5bd', label: 'Last 5 business days' },
  { id: 'last-20bd', label: 'Last 20 business days' }, { id: 'prev-month', label: 'Previous month' }, { id: 'prev-quarter', label: 'Previous quarter' },
  { id: 'prev-year', label: 'Previous year' }, { id: 'last-12m', label: 'Last 12 months' }]);

/**
 * A preset as dates, from a business date and a calendar: {from, to, label}.
 * @param {string} id one of DATE_PRESETS
 * @param {{asOf: string, calendar: (string|HolidayCalendar)}} [o] asOf: the business date (default today, rolled back to a business day)
 * @returns {{from: string, to: string, label: string}}
 */
export function resolvePreset(id, o) {
  o = o || {};
  const cal = calendar(o.calendar), asOf = cal.onOrBefore(o.asOf || today()), b = parseDate(asOf);
  const label = (DATE_PRESETS.find((p) => p.id === id) || { label: id }).label;
  const out = (from, to) => ({ from: from, to: to, label: label });
  const qStart = (y, m) => iso(y, Math.floor((m - 1) / 3) * 3 + 1, 1);
  switch (id) {
    case 'today': return out(asOf, asOf);
    case 't-1': { const t = cal.tMinus(asOf, 1); return out(t, t); }
    case 'wtd': { const w = wd(b.y, b.m, b.d), mon = shiftIso(asOf, -((w + 6) % 7)); return out(cal.onOrAfter(mon) > asOf ? asOf : cal.onOrAfter(mon), asOf); }
    case 'mtd': return out(cal.monthStart(asOf), asOf);
    case 'qtd': return out(cal.onOrAfter(qStart(b.y, b.m)), asOf);
    case 'ytd': return out(cal.onOrAfter(iso(b.y, 1, 1)), asOf);
    case 'last-5bd': return out(cal.addBusinessDays(asOf, -4), asOf);
    case 'last-20bd': return out(cal.addBusinessDays(asOf, -19), asOf);
    case 'prev-month': { const p = b.m === 1 ? iso(b.y - 1, 12, 1) : iso(b.y, b.m - 1, 1); return out(cal.monthStart(p), cal.monthEnd(p)); }
    case 'prev-quarter': { const qs = qStart(b.y, b.m), p = shiftIso(qs, -1); return out(cal.onOrAfter(qStart(+p.slice(0, 4), +p.slice(5, 7))), cal.onOrBefore(p)); }
    case 'prev-year': return out(cal.onOrAfter(iso(b.y - 1, 1, 1)), cal.onOrBefore(iso(b.y - 1, 12, 31)));
    case 'last-12m': { const s = iso(b.y - 1, b.m, Math.min(b.d, daysInMonth(b.y - 1, b.m))); return out(cal.onOrAfter(shiftIso(s, 1)), asOf); }
    default: throw new Error('no date preset ' + id + ' (' + DATE_PRESETS.map((p) => p.id).join(', ') + ')');
  }
}

/**
 * Reads what a person typed as a business date: ISO (2026-11-06), "6 Nov 2026", "Nov 6 2026", T (the business date), T-3 or T+2 (business
 * days), ME, QE, YE (this month's, quarter's, year's last business day), PME (the previous month-end). Null when it does not read.
 * @param {string} text
 * @param {{asOf: string, calendar: (string|HolidayCalendar)}} [o]
 * @returns {?string} ISO date
 */
export function parseDateInput(text, o) {
  o = o || {};
  const s = String(text || '').trim();
  if (!s) { return null; }
  const cal = calendar(o.calendar), asOf = o.asOf || cal.onOrBefore(today()), up = s.toUpperCase().replace(/\s+/g, '');
  let m = /^T([+-]\d+)?$/.exec(up);
  if (m) { const n = m[1] ? +m[1] : 0; return n < 0 ? cal.tMinus(asOf, -n) : cal.addBusinessDays(asOf, n); }
  if (up === 'ME') { return cal.monthEnd(asOf); }
  if (up === 'QE') { return cal.quarterEnd(asOf); }
  if (up === 'YE') { return cal.yearEnd(asOf); }
  if (up === 'PME') { const b = parseDate(asOf); return cal.monthEnd(b.m === 1 ? iso(b.y - 1, 12, 1) : iso(b.y, b.m - 1, 1)); }
  m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(s);
  if (m) { return isoDate(iso(+m[1], +m[2], +m[3])) || null; }
  m = /^(\d{1,2})[\s-]+([A-Za-z]{3,})[\s-,]+(\d{4})$/.exec(s) || null;
  if (m) { const k = MONTHS.indexOf(m[2].slice(0, 3).toLowerCase()); return k < 0 ? null : isoDate(iso(+m[3], k + 1, +m[1])) || null; }
  m = /^([A-Za-z]{3,})[\s-]+(\d{1,2}),?[\s-]+(\d{4})$/.exec(s);
  if (m) { const k = MONTHS.indexOf(m[1].slice(0, 3).toLowerCase()); return k < 0 ? null : isoDate(iso(+m[3], k + 1, +m[2])) || null; }
  return null;
}

/**
 * The days a month view shows: six weeks of seven days from the week the month starts in.
 * @param {number} y
 * @param {number} m 1 to 12
 * @param {number} [weekStart=1] 0 Sunday, 1 Monday
 * @returns {Array<Array<{iso: string, day: number, inMonth: boolean, weekday: number}>>}
 */
export function monthGrid(y, m, weekStart) {
  const ws = weekStart === undefined ? 1 : weekStart, first = wd(y, m, 1), lead = (first - ws + 7) % 7;
  let d = shiftIso(iso(y, m, 1), -lead);
  const weeks = [];
  for (let w = 0; w < 6; w++) {
    const week = [];
    for (let k = 0; k < 7; k++) {
      const b = parseDate(d);
      week.push({ iso: d, day: b.d, inMonth: b.m === m, weekday: wd(b.y, b.m, b.d) });
      d = shiftIso(d, 1);
    }
    weeks.push(week);
  }
  return weeks;
}

/**
 * A date moved by whole days, months or years (a month that is shorter keeps its last day: 31 Jan + 1 month is 28 or 29 Feb).
 * @param {*} v
 * @param {number} n
 * @param {('day'|'month'|'year')} unit
 * @returns {string}
 */
export function shiftDate(v, n, unit) {
  const b = parseDate(v);
  if (unit === 'day') { return shiftIso(isoDate(b), n); }
  const months = unit === 'year' ? n * 12 : n, k = b.y * 12 + (b.m - 1) + months, y = Math.floor(k / 12), m = (k % 12) + 1;
  return iso(y, m, Math.min(b.d, daysInMonth(y, m)));
}
