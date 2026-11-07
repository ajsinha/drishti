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
/* Times and dates with times. TimePicker: a field that reads what a person types (14:05, 1405, 2pm, 2:30 pm, 14.05, 9) and, under it,
   the times of the day by a step (every 15 minutes by default) as a listbox, the nearest one active: the arrows move, Enter picks,
   Escape closes. The value is "HH:MM" (24 hours). DateTimePicker: the business-date picker and the time picker side by side, with the
   time zone named beside them; the value is "YYYY-MM-DDTHH:MM" (a date with no time yet reads as midnight). */
import { announce, isoDate } from '../../drishti-core/src/drishti_index.js';
import { InputBase, Listbox, Popover, uid } from './drishti_inputs-dom.js';
import { DatePicker } from './drishti_date-picker.js';

const pad = (n) => (n < 10 ? '0' : '') + n;

/**
 * Reads a typed time as "HH:MM", or null: 14:05, 14.05, 1405, 905, 9, 2pm, 2:30 pm, 12am, noon, midnight.
 * @param {string} text
 * @returns {?string}
 */
export function parseTime(text) {
  const t = String(text === undefined || text === null ? '' : text).trim().toLowerCase().replace(/\s+/g, ' ');
  if (!t) { return null; }
  if (t === 'noon') { return '12:00'; }
  if (t === 'midnight') { return '00:00'; }
  let m = /^(\d{1,2})(?:[:.h ]?(\d{2}))?(?::(\d{2}))?\s*(am|pm|a|p)?$/.exec(t);
  if (!m) { m = /^(\d{1,2})(\d{2})()\s*(am|pm|a|p)?$/.exec(t); }
  if (!m) { return null; }
  let hh = +m[1];
  const mm = m[2] ? +m[2] : 0, ap = m[4] ? m[4].charAt(0) : '';
  if (ap) { if (hh < 1 || hh > 12) { return null; } hh = (hh % 12) + (ap === 'p' ? 12 : 0); }
  if (hh > 23 || mm > 59) { return null; }
  return pad(hh) + ':' + pad(mm);
}

/**
 * Reads a date with a time as "YYYY-MM-DDTHH:MM", or null: 2026-12-31T17:00, 2026-12-31 17:00, 2026-12-31 5pm; a date alone is midnight.
 * @param {string} text
 * @returns {?string}
 */
export function parseDateTime(text) {
  const t = String(text === undefined || text === null ? '' : text).trim();
  const m = /^(\d{4}-\d{2}-\d{2})(?:[T ](.+))?$/.exec(t);
  if (!m || !isoDate(m[1])) { return null; }
  const hm = m[2] ? /^(\d{1,2}:\d{2})(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?$/.exec(m[2].trim()) : null;   // seconds and a zone are dropped
  const tm = m[2] ? parseTime(hm ? hm[1] : m[2]) : '00:00';
  return tm ? m[1] + 'T' + tm : null;
}

/**
 * The times of a day by a step in minutes, between min and max ("HH:MM").
 * @param {number} [step=15]
 * @param {string} [min]
 * @param {string} [max]
 * @returns {string[]}
 */
export function timeSlots(step, min, max) {
  const s = Math.max(1, Math.round(step || 15)), out = [];
  for (let m = 0; m < 24 * 60; m += s) { const v = pad(Math.floor(m / 60)) + ':' + pad(m % 60); if ((!min || v >= min) && (!max || v <= max)) { out.push(v); } }
  return out;
}

/**
 * @typedef {Object} TimePickerOptions
 * @property {string} [value] "HH:MM"
 * @property {number} [step=15] minutes between the times offered
 * @property {string} [min]
 * @property {string} [max]
 * @property {boolean} [required=false]
 * @property {string} [label]
 * @property {string} [placeholder]
 */

/**
 * A time field with the day's times under it. Events: change {value}, invalid {text, reason}.
 */
export class TimePicker extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {TimePickerOptions} [options]
   */
  constructor(host, options) {
    super(host, options, 'di-time');
    const o = this.o = Object.assign({ step: 15 }, this.o);
    this.value = parseTime(o.value) || null;
    const id = uid('time');
    this.list = new Listbox(this.doc, { label: (o.label || 'Time') + ': times', onPick: (it) => { this.clearMsg(); this.setValue(it.value, true); this.pop.close(); this.input.focus(); } });
    this.pop = new Popover(this.doc, { cls: 'di-time-pop', role: 'presentation', theme: host, onClose: () => { this.input.setAttribute('aria-expanded', 'false'); this.input.removeAttribute('aria-activedescendant'); } });
    this.input = this.el('input', { cls: 'di-field di-time-field', attrs: { type: 'text', id: id, role: 'combobox', 'aria-expanded': 'false', 'aria-autocomplete': 'list', autocomplete: 'off',
      spellcheck: 'false', inputmode: 'numeric', 'aria-label': o.label || 'Time', placeholder: o.placeholder || '14:05 or 2pm', 'aria-required': o.required ? 'true' : null, 'aria-describedby': id + '-msg' } });
    this.button = this.el('button', { cls: 'di-icon-btn', text: '◷', attrs: { type: 'button', 'aria-label': 'Choose a time', tabindex: '-1' } });
    this.msg = this.el('div', { cls: 'di-msg', attrs: { id: id + '-msg', 'aria-live': 'polite' } });
    this.control = this.el('div', { cls: 'di-control' }, [this.input, this.button]);
    host.append(this.control, this.msg);
    this.input.addEventListener('keydown', (e) => {
      if (this.pop.isOpen() && (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'PageDown' || e.key === 'PageUp') && this.list.key(e, this.input)) { return; }
      if (e.key === 'ArrowDown' && !this.pop.isOpen()) { e.preventDefault(); this.open(); }
      else if (e.key === 'Enter') { if (this.pop.isOpen() && this.list.active >= 0 && this.input.value.trim() === this.show(true)) { e.preventDefault(); this.list.key(e, this.input); } else { e.preventDefault(); this.commitText(); this.pop.close(); } }
      else if (e.key === 'Escape' && this.pop.isOpen()) { e.preventDefault(); e.stopPropagation(); this.pop.close(); }
    });
    this.input.addEventListener('blur', () => { if (!this.pop.isOpen()) { this.commitText(); } });
    this.button.addEventListener('mousedown', (e) => e.preventDefault());
    this.button.addEventListener('click', () => (this.pop.isOpen() ? this.pop.close() : (this.input.focus(), this.open())));
    this.show();
  }

  /** @private the field's text from the value (asText: only return it) */
  show(asText) { const t = this.value || ''; if (!asText) { this.input.value = t; } return t; }

  /** @private */
  commitText() {
    const text = this.input.value.trim();
    if (!text) { if (this.o.required) { this.fail(text, 'A time is required'); return; } this.clearMsg(); if (this.value !== null) { this.setValue(null, true); } return; }
    const t = parseTime(text);
    if (!t) { this.fail(text, 'Not a time: write 14:05, 1405 or 2pm'); return; }
    if ((this.o.min && t < this.o.min) || (this.o.max && t > this.o.max)) { this.fail(text, t + ' is outside ' + (this.o.min || '00:00') + '–' + (this.o.max || '23:59')); return; }
    this.clearMsg();
    this.setValue(t, true);
  }

  /** @private */
  fail(text, reason) { this.input.setAttribute('aria-invalid', 'true'); this.msg.textContent = reason; this.host.classList.add('di-invalid'); this.emit('invalid', { text: text, reason: reason }); }

  /** @private */
  clearMsg() { this.input.removeAttribute('aria-invalid'); this.msg.textContent = ''; this.host.classList.remove('di-invalid'); }

  /**
   * Opens the day's times, the value's (or the nearest) active.
   * @returns {void}
   */
  open() {
    const slots = timeSlots(this.o.step, this.o.min, this.o.max).map((v) => ({ value: v, label: v }));
    const cur = parseTime(this.input.value) || this.value;
    this.list.render(slots, (it) => it.value === this.value);
    let at = 0;
    if (cur) { slots.forEach((s, i) => { if (s.value <= cur) { at = i; } }); }
    this.pop.open(this.control, this.list.el, { focus: false });
    this.input.setAttribute('aria-controls', this.list.id);
    this.input.setAttribute('aria-expanded', 'true');
    this.list.setActive(at, this.input);
  }

  /**
   * Closes the list.
   * @returns {void}
   */
  close() { this.pop.close(); }

  /**
   * The time ("HH:MM") or null.
   * @returns {?string}
   */
  getValue() { return this.value; }

  /**
   * Sets the time; change is emitted when emit is true and it changed.
   * @param {?string} v
   * @param {boolean} [emit]
   * @returns {void}
   */
  setValue(v, emit) {
    const t = v === null || v === undefined || v === '' ? null : parseTime(v);
    const changed = t !== this.value;
    this.value = t;
    this.show();
    if (changed && emit) { this.emit('change', { value: t }); announce(t ? 'Time ' + t : 'Time cleared'); }
  }

  /**
   * Whether the field holds a time (and one when it is required).
   * @returns {boolean}
   */
  validate() { this.commitText(); return !this.input.hasAttribute('aria-invalid'); }

  /**
   * Puts the keyboard focus in the field.
   * @returns {void}
   */
  focus() { this.input.focus(); }

  /** @returns {void} */
  destroy() { this.pop.close(); super.destroy(); }
}

/**
 * @typedef {Object} DateTimePickerOptions
 * @property {string} [value] "YYYY-MM-DDTHH:MM"
 * @property {string} [timeZone] the zone the time is in, named beside it (America/New_York)
 * @property {number} [step=15] the time list's step in minutes
 * @property {(string|Object)} [calendar]
 * @property {boolean} [businessOnly=false]
 * @property {string} [min] "YYYY-MM-DDTHH:MM"
 * @property {string} [max] "YYYY-MM-DDTHH:MM"
 * @property {boolean} [required=false]
 * @property {string} [label]
 */

/**
 * A date and a time with its zone. Events: change {value}, invalid {reason}.
 */
export class DateTimePicker extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {DateTimePickerOptions} [options]
   */
  constructor(host, options) {
    super(host, options, 'di-datetime');
    const o = this.o = Object.assign({ step: 15, businessOnly: false }, this.o);
    this.value = parseDateTime(o.value) || null;
    const L = o.label || 'Date and time', d = this.value ? this.value.slice(0, 10) : null, t = this.value ? this.value.slice(11) : null;
    const dh = this.el('div', { cls: 'di-datetime-date' }), th = this.el('div', { cls: 'di-datetime-time' });
    this.zone = this.el('span', { cls: 'di-zone', text: o.timeZone || '', attrs: { title: 'Time zone' } });
    this.zone.hidden = !o.timeZone;
    host.appendChild(this.el('div', { cls: 'di-datetime-row', attrs: { role: 'group', 'aria-label': L } }, [dh, th, this.zone]));
    this.date = new DatePicker(dh, { value: d, label: L + ': date', calendar: o.calendar, businessOnly: o.businessOnly, shortcuts: false, required: o.required,
      min: o.min ? o.min.slice(0, 10) : undefined, max: o.max ? o.max.slice(0, 10) : undefined });
    this.time = new TimePicker(th, { value: t, label: L + ': time', step: o.step });
    this.input = this.date.input;
    const changed = () => {
      const dd = this.date.getValue(), tt = this.time.getValue();
      const v = dd ? dd + 'T' + (tt || '00:00') : null;
      if (v && ((o.min && v < o.min) || (o.max && v > o.max))) { this.emit('invalid', { reason: L + ' must be between ' + (o.min || 'any') + ' and ' + (o.max || 'any') }); }
      if (v !== this.value) { this.value = v; this.emit('change', { value: v }); }
    };
    this.date.on('change', changed);
    this.time.on('change', changed);
  }

  /**
   * The value ("YYYY-MM-DDTHH:MM") or null.
   * @returns {?string}
   */
  getValue() { return this.value; }

  /**
   * Sets the value.
   * @param {?string} v
   * @param {boolean} [emit]
   * @returns {void}
   */
  setValue(v, emit) {
    const x = v ? parseDateTime(v) : null, before = this.value;
    this.value = x;
    this.date.setValue(x ? x.slice(0, 10) : null);
    this.time.setValue(x ? x.slice(11) : null);
    if (emit && x !== before) { this.emit('change', { value: x }); }
  }

  /**
   * Names the time zone beside the time.
   * @param {string} zone
   * @returns {void}
   */
  setTimeZone(zone) { this.zone.textContent = zone || ''; this.zone.hidden = !zone; }

  /**
   * Whether both parts read (and a date when required).
   * @returns {boolean}
   */
  validate() { const a = this.date.validate(), b = this.time.validate(); return a && b; }

  /**
   * Puts the keyboard focus on the date.
   * @returns {void}
   */
  focus() { this.date.focus(); }

  /** @returns {void} */
  destroy() { this.date.destroy(); this.time.destroy(); super.destroy(); }
}
