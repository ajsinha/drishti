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
/* The business-date picker: a field that reads what a person types (2026-11-06, 6 Nov 2026, T-1, ME) and a calendar under it. The
   calendar knows the business calendar: weekends dimmed, holidays marked with their names, days that are not business days refused
   when the picker is for business dates (the default), the shortcuts a risk person uses (the business date, T-1, the previous
   month-end), min and max. The keys are the date grid's (WAI-ARIA): arrows a day or a week, Page Up and Down a month (with Shift a year),
   Home and End the week's ends, Enter or Space picks, Escape closes. CalendarView, the month grid, also serves the range picker. */
import { formatDate, isoDate, parseDate, announce } from '../../drishti-core/src/drishti_index.js';
import { calendar, monthGrid, parseDateInput, shiftDate } from './drishti_calendar.js';
import { InputBase, Popover, uid } from './drishti_inputs-dom.js';

const DOW = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];
const MONTH = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/**
 * One or more months of days as a grid (role grid), keyed by the business calendar: what the pickers draw in their popovers.
 */
export class CalendarView {
  /**
   * @param {Document} doc
   * @param {{calendar: Object, months: number, weekStart: number, businessOnly: boolean, min: string, max: string, focus: string,
   *   range: function(): {from: ?string, to: ?string, hover: ?string}, selected: function(string): boolean, onPick: function(string): void,
   *   onHover: function(string): void}} o months: how many months side by side (default 1); range: the range to shade (range picker)
   */
  constructor(doc, o) {
    this.doc = doc;
    this.o = Object.assign({ months: 1, weekStart: 1, businessOnly: true }, o);
    this.cal = calendar(this.o.calendar);
    this.focus = isoDate(this.o.focus) || this.cal.onOrBefore(isoDate(new Date()));
    this.id = uid('cal');
    this.el = doc.createElement('div');
    this.el.className = 'di-cal';
    this.el.addEventListener('keydown', (e) => this.key(e));
    this.el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-day]');
      if (b && b.getAttribute('aria-disabled') !== 'true') { this.focus = b.dataset.day; this.o.onPick(b.dataset.day); }
      const nav = e.target.closest('[data-nav]');
      if (nav) { this.move(+nav.dataset.nav, 'month'); }
    });
    this.el.addEventListener('mouseover', (e) => { const b = e.target.closest('[data-day]'); if (b && this.o.onHover) { this.o.onHover(b.dataset.day); } });
    this.render();
  }

  /**
   * Why a day may not be picked, or null when it may.
   * @param {string} d
   * @returns {?string}
   */
  refusal(d) {
    if (this.o.min && d < this.o.min) { return 'before ' + formatDate(this.o.min, 'd MMM yyyy'); }
    if (this.o.max && d > this.o.max) { return 'after ' + formatDate(this.o.max, 'd MMM yyyy'); }
    if (this.o.businessOnly && !this.cal.isBusinessDay(d)) { return this.cal.holidayName(d) || 'a weekend'; }
    return null;
  }

  /**
   * Draws the months from the focused day's.
   * @returns {void}
   */
  render() {
    const doc = this.doc, el = this.el, f = parseDate(this.focus), sel = this.o.selected || (() => false), rng = this.o.range ? this.o.range() : null;
    el.textContent = '';
    const head = doc.createElement('div');
    head.className = 'di-cal-head';
    const prev = this.btn('‹', 'Previous month', -1), next = this.btn('›', 'Next month', 1);
    const title = doc.createElement('span');
    title.className = 'di-cal-title';
    title.setAttribute('aria-live', 'polite');
    title.id = this.id + '-title';
    head.append(prev, title, next);
    el.appendChild(head);
    const months = doc.createElement('div');
    months.className = 'di-cal-months';
    const titles = [];
    for (let k = 0; k < this.o.months; k++) {
      const first = shiftDate(this.focus.slice(0, 8) + '01', k, 'month'), b = parseDate(first);
      titles.push(MONTH[b.m - 1] + ' ' + b.y);
      const grid = doc.createElement('div');
      grid.className = 'di-cal-grid';
      grid.setAttribute('role', 'grid');
      grid.setAttribute('aria-label', MONTH[b.m - 1] + ' ' + b.y);
      const hr = doc.createElement('div');
      hr.className = 'di-cal-row di-cal-dow';
      hr.setAttribute('role', 'row');
      for (let i = 0; i < 7; i++) { const c = doc.createElement('span'); c.setAttribute('role', 'columnheader'); c.textContent = DOW[(i + this.o.weekStart) % 7]; hr.appendChild(c); }
      grid.appendChild(hr);
      monthGrid(b.y, b.m, this.o.weekStart).forEach((week) => {
        const row = doc.createElement('div');
        row.className = 'di-cal-row';
        row.setAttribute('role', 'row');
        week.forEach((day) => {
          const cell = doc.createElement('span');
          cell.setAttribute('role', 'gridcell');
          if (!day.inMonth) { cell.className = 'di-cal-out'; row.appendChild(cell); return; }
          const why = this.refusal(day.iso), hol = this.cal.holidayName(day.iso), we = this.cal.isWeekend(day.iso);
          const d = doc.createElement('button');
          d.type = 'button';
          d.dataset.day = day.iso;
          d.textContent = String(day.day);
          d.tabIndex = day.iso === this.focus ? 0 : -1;
          let cls = 'di-day';
          if (we) { cls += ' di-weekend'; }
          if (hol) { cls += ' di-holiday'; d.title = hol; }
          if (why) { d.setAttribute('aria-disabled', 'true'); cls += ' di-refused'; }
          if (sel(day.iso)) { cls += ' di-picked'; d.setAttribute('aria-pressed', 'true'); }
          if (rng && rng.from) {
            const to = rng.to || rng.hover;
            const lo = to && to < rng.from ? to : rng.from, hi = to && to < rng.from ? rng.from : to;
            if (hi && day.iso > lo && day.iso < hi) { cls += ' di-in-range'; }
            if (day.iso === lo || day.iso === hi) { cls += ' di-range-end'; }
          }
          if (day.iso === isoDate(new Date())) { cls += ' di-today'; }
          d.className = cls;
          d.setAttribute('aria-label', formatDate(day.iso, 'EEEE d MMMM yyyy') + (hol ? ', ' + hol : '') + (why ? ', not available: ' + why : ''));
          cell.appendChild(d);
          row.appendChild(cell);
        });
        grid.appendChild(row);
      });
      months.appendChild(grid);
    }
    title.textContent = titles.join(' – ');
    el.appendChild(months);
    if (f) { this.el.dataset.month = this.focus.slice(0, 7); }
  }

  /** @private */
  btn(text, label, n) {
    const b = this.doc.createElement('button');
    b.type = 'button';
    b.className = 'di-cal-nav';
    b.textContent = text;
    b.dataset.nav = String(n);
    b.setAttribute('aria-label', label);
    return b;
  }

  /**
   * Moves the focused day (and the months shown) and focuses its button.
   * @param {number} n
   * @param {('day'|'month'|'year')} unit
   * @returns {void}
   */
  move(n, unit) {
    let d = shiftDate(this.focus, n, unit);
    if (this.o.min && d < this.o.min) { d = this.o.min; }
    if (this.o.max && d > this.o.max) { d = this.o.max; }
    this.focus = d;
    this.render();
    this.focusDay();
  }

  /**
   * Puts the keyboard focus on the focused day's button.
   * @returns {void}
   */
  focusDay() { const b = this.el.querySelector('[data-day="' + this.focus + '"]'); if (b) { b.focus(); } }

  /** @private the date grid's keys */
  key(e) {
    if (!e.target.dataset || !e.target.dataset.day) { return; }
    const k = e.key, f = parseDate(this.focus), wdx = (new Date(Date.UTC(f.y, f.m - 1, f.d)).getUTCDay() - this.o.weekStart + 7) % 7;
    const map = { ArrowLeft: [-1, 'day'], ArrowRight: [1, 'day'], ArrowUp: [-7, 'day'], ArrowDown: [7, 'day'], PageUp: [e.shiftKey ? -1 : -1, e.shiftKey ? 'year' : 'month'],
      PageDown: [1, e.shiftKey ? 'year' : 'month'], Home: [-wdx, 'day'], End: [6 - wdx, 'day'] };
    if (map[k]) { e.preventDefault(); this.move(map[k][0], map[k][1]); if (this.o.onHover) { this.o.onHover(this.focus); } return; }
    if (k === 'Enter' || k === ' ') {
      e.preventDefault();
      const why = this.refusal(this.focus);
      if (why) { announce(formatDate(this.focus, 'd MMM yyyy') + ' is not available: ' + why); return; }
      this.o.onPick(this.focus);
    }
  }
}

/**
 * @typedef {Object} DatePickerOptions
 * @property {string} [value] the date (ISO)
 * @property {(string|Object)} [calendar='weekends'] the business calendar: US, UK, TARGET, CA, US+UK, a defined one, or a HolidayCalendar
 * @property {string} [asOf] the business date T (default today rolled back to a business day)
 * @property {boolean} [businessOnly=true] only business days may be picked
 * @property {string} [min]
 * @property {string} [max]
 * @property {string} [format='d MMM yyyy'] how the field shows the date
 * @property {number} [weekStart=1] 0 Sunday, 1 Monday
 * @property {boolean} [shortcuts=true] the business date, T-1 and the previous month-end under the calendar
 * @property {boolean} [required=false]
 * @property {string} [label] the field's name for a reader
 * @property {string} [placeholder]
 */

/**
 * A business-date picker: a field and a calendar. Events: change {value}, invalid {text, reason}, open, close.
 */
export class DatePicker extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {DatePickerOptions} [options]
   */
  constructor(host, options) {
    super(host, options, 'di-date');
    const o = this.o = Object.assign({ businessOnly: true, format: 'd MMM yyyy', shortcuts: true, weekStart: 1 }, this.o);
    this.cal = calendar(o.calendar);
    this.asOf = o.asOf || this.cal.onOrBefore(isoDate(new Date()));
    this.value = o.value ? isoDate(o.value) : null;
    this.pop = new Popover(this.doc, { cls: 'di-date-pop', label: 'Choose a date', theme: host, onClose: () => { this.input.setAttribute('aria-expanded', 'false'); this.emit('close'); } });
    const id = uid('date');
    this.input = this.el('input', { cls: 'di-field', attrs: { type: 'text', id: id, role: 'combobox', 'aria-haspopup': 'dialog', 'aria-expanded': 'false', autocomplete: 'off',
      spellcheck: 'false', 'aria-label': o.label || 'Date', placeholder: o.placeholder || 'T-1, ME or 6 Nov 2026', 'aria-required': o.required ? 'true' : null, 'aria-describedby': id + '-msg' } });
    this.button = this.el('button', { cls: 'di-icon-btn', text: '▦', attrs: { type: 'button', 'aria-label': 'Open the calendar', tabindex: '-1' } });
    this.msg = this.el('div', { cls: 'di-msg', attrs: { id: id + '-msg', 'aria-live': 'polite' } });
    host.append(this.el('div', { cls: 'di-control' }, [this.input, this.button]), this.msg);
    this.input.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' && (e.altKey || !this.pop.isOpen())) { e.preventDefault(); this.open(); }
      else if (e.key === 'Enter') { e.preventDefault(); this.commitText(); }
      else if (e.key === 'Escape' && this.pop.isOpen()) { this.pop.close(); }
    });
    this.input.addEventListener('blur', () => { if (!this.pop.isOpen()) { this.commitText(); } });
    this.button.addEventListener('click', () => (this.pop.isOpen() ? this.pop.close(true) : this.open()));
    this.show();
  }

  /** @private the field's text from the value */
  show() { this.input.value = this.value ? formatDate(this.value, this.o.format) : ''; }

  /**
   * Why a date may not be picked, or null.
   * @param {string} d ISO
   * @returns {?string}
   */
  refusal(d) {
    if (this.o.min && d < this.o.min) { return 'it is before ' + formatDate(this.o.min, this.o.format); }
    if (this.o.max && d > this.o.max) { return 'it is after ' + formatDate(this.o.max, this.o.format); }
    if (this.o.businessOnly && !this.cal.isBusinessDay(d)) {
      const hol = this.cal.holidayName(d);
      return (hol ? 'it is a holiday (' + hol + ')' : 'it is a weekend') + ': the business day before is ' + formatDate(this.cal.onOrBefore(d), this.o.format);
    }
    return null;
  }

  /** @private reads the field */
  commitText() {
    const text = this.input.value.trim();
    if (!text) {
      if (this.o.required) { this.fail(text, 'A date is required'); return; }
      if (this.value !== null) { this.setValue(null, true); }
      this.clearMsg();
      return;
    }
    const d = parseDateInput(text, { asOf: this.asOf, calendar: this.cal });
    if (!d) { this.fail(text, 'Not a date: write 2026-11-06, 6 Nov 2026, T-1 or ME'); return; }
    const why = this.refusal(d);
    if (why) { this.fail(text, formatDate(d, this.o.format) + ' cannot be picked: ' + why); return; }
    this.clearMsg();
    this.setValue(d, true);
  }

  /** @private */
  fail(text, reason) {
    this.input.setAttribute('aria-invalid', 'true');
    this.msg.textContent = reason;
    this.host.classList.add('di-invalid');
    this.emit('invalid', { text: text, reason: reason });
  }

  /** @private */
  clearMsg() { this.input.removeAttribute('aria-invalid'); this.msg.textContent = ''; this.host.classList.remove('di-invalid'); }

  /**
   * Opens the calendar on the value (or the business date).
   * @returns {void}
   */
  open() {
    const box = this.el('div', { cls: 'di-date-box' });
    this.view = new CalendarView(this.doc, { calendar: this.cal, weekStart: this.o.weekStart, businessOnly: this.o.businessOnly, min: this.o.min, max: this.o.max,
      focus: this.value || this.asOf, selected: (d) => d === this.value, onPick: (d) => { this.clearMsg(); this.setValue(d, true); this.pop.close(true); } });
    box.appendChild(this.view.el);
    if (this.o.shortcuts) {
      const cuts = [['T', 'Business date', this.asOf], ['T-1', 'T-1', this.cal.tMinus(this.asOf, 1)], ['ME', 'Month-end', this.cal.previousMonthEnd(this.asOf)]];
      const row = this.el('div', { cls: 'di-shortcuts', attrs: { role: 'group', 'aria-label': 'Shortcuts' } });
      cuts.forEach(([key, label, d]) => {
        const why = this.refusal(d);
        row.appendChild(this.el('button', { cls: 'di-chip-btn', text: label, attrs: { type: 'button', title: formatDate(d, this.o.format), 'aria-label': label + ': ' + formatDate(d, this.o.format), disabled: why ? true : null },
          data: { shortcut: key }, on: { click: () => { this.clearMsg(); this.setValue(d, true); this.pop.close(true); } } }));
      });
      row.appendChild(this.el('span', { cls: 'di-cal-name', text: this.cal.name === 'weekends' ? 'Weekends only' : this.cal.name + ' calendar' }));
      box.appendChild(row);
    }
    this.pop.open(this.input.closest('.di-control'), box, { focus: false });
    this.input.setAttribute('aria-expanded', 'true');
    this.view.focusDay();
    this.emit('open');
  }

  /**
   * Closes the calendar.
   * @returns {void}
   */
  close() { this.pop.close(); }

  /**
   * The date (ISO), or null.
   * @returns {?string}
   */
  getValue() { return this.value; }

  /**
   * Sets the date (ISO, a Date or null); change is emitted when a person changed it (or when emit is true).
   * @param {*} v
   * @param {boolean} [emit]
   * @returns {void}
   */
  setValue(v, emit) {
    const d = v === null || v === undefined || v === '' ? null : isoDate(v);
    const changed = d !== this.value;
    this.value = d;
    this.show();
    if (changed && emit) { this.emit('change', { value: d }); announce(d ? 'Date ' + formatDate(d, 'EEEE d MMMM yyyy') : 'Date cleared'); }
  }

  /**
   * Whether the field holds a date that may be picked (and one when it is required).
   * @returns {boolean}
   */
  validate() { this.commitText(); return !this.input.hasAttribute('aria-invalid'); }

  /**
   * Puts the keyboard focus in the field.
   * @returns {void}
   */
  focus() { this.input.focus(); }
}
