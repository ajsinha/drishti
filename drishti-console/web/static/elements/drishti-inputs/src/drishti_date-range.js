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
/* The date-range picker: a button that reads the range in few words ("3–7 Nov 2026 · Month to date") and opens a panel with the presets
   (Today, T-1, WTD, MTD, QTD, YTD, the last 5 and 20 business days, the previous month, quarter and year, the last 12 months), two
   months side by side keyed by the business calendar, and the two ends as fields that read typed text (T-5, ME, 6 Nov 2026). A first
   click picks the start, a second the end (in either order), the days between shaded as the pointer or the keys move. Apply keeps the
   range, Escape or Cancel leaves it as it was. A preset stays a preset: kept as its id, it moves with the business date. */
import { formatDate, formatDateRange, isoDate, announce } from '../../drishti-core/src/drishti_index.js';
import { calendar, DATE_PRESETS, resolvePreset, parseDateInput } from './drishti_calendar.js';
import { CalendarView } from './drishti_date-picker.js';
import { InputBase, Popover } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} DateRange
 * @property {?string} from ISO
 * @property {?string} to ISO
 * @property {?string} preset the preset's id when the range is one (it moves with the business date)
 */

/**
 * @typedef {Object} DateRangeOptions
 * @property {DateRange} [value]
 * @property {(string|Object)} [calendar='weekends']
 * @property {string} [asOf] the business date the presets count from
 * @property {(boolean|string[])} [presets=true] true: every preset; or the ids to offer
 * @property {boolean} [businessOnly=false] the ends must be business days
 * @property {string} [min]
 * @property {string} [max]
 * @property {number} [maxDays] the longest range in calendar days
 * @property {number} [weekStart=1]
 * @property {string} [label]
 */

/**
 * A date-range picker with presets. Events: change {from, to, preset}, invalid {reason}, open, close.
 */
export class DateRangePicker extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {DateRangeOptions} [options]
   */
  constructor(host, options) {
    super(host, options, 'di-range');
    const o = this.o = Object.assign({ presets: true, businessOnly: false, weekStart: 1 }, this.o);
    this.cal = calendar(o.calendar);
    this.asOf = o.asOf || this.cal.onOrBefore(isoDate(new Date()));
    this.value = { from: null, to: null, preset: null };
    this.pop = new Popover(this.doc, { cls: 'di-range-pop', label: o.label || 'Choose a date range', theme: host, onClose: () => { this.button.setAttribute('aria-expanded', 'false'); this.emit('close'); } });
    this.button = this.el('button', { cls: 'di-field di-range-btn', attrs: { type: 'button', 'aria-haspopup': 'dialog', 'aria-expanded': 'false', 'aria-label': (o.label || 'Date range') } },
      [this.el('span', { cls: 'di-range-text' }), this.el('span', { cls: 'di-caret', text: '▾', attrs: { 'aria-hidden': 'true' } })]);
    host.appendChild(this.button);
    this.button.addEventListener('click', () => (this.pop.isOpen() ? this.pop.close(true) : this.open()));
    this.button.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown') { e.preventDefault(); this.open(); } });
    if (o.value) { this.setValue(o.value); } else { this.show(); }
  }

  /**
   * The presets this picker offers.
   * @returns {Array<{id: string, label: string}>}
   */
  presets() {
    const p = this.o.presets;
    if (!p) { return []; }
    return p === true ? DATE_PRESETS.slice() : DATE_PRESETS.filter((x) => p.indexOf(x.id) >= 0);
  }

  /**
   * The range in words: "3–7 Nov 2026 · Month to date".
   * @returns {string}
   */
  text() {
    const v = this.value;
    if (!v.from && !v.to) { return 'Any date'; }
    const words = formatDateRange(v.from || v.to, v.to || v.from);
    const p = v.preset ? DATE_PRESETS.find((x) => x.id === v.preset) : null;
    return p ? words + ' · ' + p.label : words;
  }

  /** @private */
  show() { this.button.querySelector('.di-range-text').textContent = this.text(); this.button.title = this.text(); }

  /**
   * Why a range may not be kept, or null.
   * @param {?string} from
   * @param {?string} to
   * @returns {?string}
   */
  refusal(from, to) {
    if (!from || !to) { return 'Pick both ends'; }
    if (this.o.min && from < this.o.min) { return 'The start is before ' + formatDate(this.o.min, 'd MMM yyyy'); }
    if (this.o.max && to > this.o.max) { return 'The end is after ' + formatDate(this.o.max, 'd MMM yyyy'); }
    if (this.o.businessOnly) {
      if (!this.cal.isBusinessDay(from)) { return 'The start, ' + formatDate(from, 'd MMM yyyy') + ', is not a business day'; }
      if (!this.cal.isBusinessDay(to)) { return 'The end, ' + formatDate(to, 'd MMM yyyy') + ', is not a business day'; }
    }
    if (this.o.maxDays) {
      const days = Math.round((Date.parse(to) - Date.parse(from)) / 864e5) + 1;
      if (days > this.o.maxDays) { return 'The range is ' + days + ' days; the most is ' + this.o.maxDays; }
    }
    return null;
  }

  /**
   * Opens the panel: presets, two months, the two ends.
   * @returns {void}
   */
  open() {
    const draft = this.draft = Object.assign({}, this.value, { hover: null });
    const box = this.el('div', { cls: 'di-range-box' });
    const side = this.el('div', { cls: 'di-presets', attrs: { role: 'listbox', 'aria-label': 'Presets' } });
    this.presets().forEach((p) => {
      const r = resolvePreset(p.id, { asOf: this.asOf, calendar: this.cal });
      side.appendChild(this.el('button', { cls: 'di-preset' + (draft.preset === p.id ? ' on' : ''), attrs: { type: 'button', role: 'option', 'aria-selected': String(draft.preset === p.id),
        title: formatDateRange(r.from, r.to) }, data: { preset: p.id }, on: { click: () => { Object.assign(draft, { from: r.from, to: r.to, preset: p.id }); this.redraw(); } } },
      [this.el('span', { text: p.label }), this.el('span', { cls: 'di-preset-dates', text: formatDateRange(r.from, r.to) })]));
    });
    const main = this.el('div', { cls: 'di-range-main' });
    this.fromIn = this.end('From', 'from');
    this.toIn = this.end('To', 'to');
    this.view = new CalendarView(this.doc, { calendar: this.cal, months: 2, weekStart: this.o.weekStart, businessOnly: this.o.businessOnly, min: this.o.min, max: this.o.max,
      focus: draft.from ? draft.from : this.cal.monthStart(this.asOf),
      range: () => ({ from: draft.from, to: draft.to, hover: draft.hover }),
      selected: (d) => d === draft.from || d === draft.to,
      onHover: (d) => { if (draft.from && !draft.to && draft.hover !== d) { draft.hover = d; this.view.render(); } },
      onPick: (d) => {
        if (!draft.from || draft.to) { Object.assign(draft, { from: d, to: null, preset: null }); }
        else { if (d < draft.from) { draft.to = draft.from; draft.from = d; } else { draft.to = d; } draft.preset = null; }
        this.redraw(true);
      } });
    this.msg = this.el('div', { cls: 'di-msg', attrs: { 'aria-live': 'polite' } });
    const foot = this.el('div', { cls: 'di-range-foot' }, [this.msg, this.el('span', { cls: 'di-sp' }),
      this.el('button', { cls: 'di-btn', text: 'Clear', attrs: { type: 'button' }, data: { act: 'clear' }, on: { click: () => { Object.assign(draft, { from: null, to: null, preset: null }); this.redraw(); } } }),
      this.el('button', { cls: 'di-btn', text: 'Cancel', attrs: { type: 'button' }, data: { act: 'cancel' }, on: { click: () => this.pop.close(true) } }),
      this.el('button', { cls: 'di-btn di-primary', text: 'Apply', attrs: { type: 'button' }, data: { act: 'apply' }, on: { click: () => this.apply() } })]);
    main.append(this.el('div', { cls: 'di-range-ends' }, [this.fromIn.wrap, this.el('span', { text: '→', attrs: { 'aria-hidden': 'true' } }), this.toIn.wrap]), this.view.el, foot);
    if (this.presets().length) { box.appendChild(side); }
    box.appendChild(main);
    this.pop.open(this.button, box);
    this.button.setAttribute('aria-expanded', 'true');
    this.redraw();
    this.emit('open');
  }

  /** @private one end's field */
  end(label, which) {
    const input = this.el('input', { cls: 'di-field', attrs: { type: 'text', 'aria-label': label, placeholder: label + ': T-5, ME, 6 Nov 2026', spellcheck: 'false' }, data: { end: which } });
    const read = () => {
      const t = input.value.trim();
      if (!t) { this.draft[which] = null; this.draft.preset = null; this.redraw(); return; }
      const d = parseDateInput(t, { asOf: this.asOf, calendar: this.cal });
      if (!d) { input.setAttribute('aria-invalid', 'true'); this.say('Not a date: write 2026-11-06, 6 Nov 2026, T-5 or ME'); return; }
      input.removeAttribute('aria-invalid');
      this.draft[which] = d;
      this.draft.preset = null;
      if (this.draft.from && this.draft.to && this.draft.from > this.draft.to) { const x = this.draft.from; this.draft.from = this.draft.to; this.draft.to = x; }
      this.view.focus = d;
      this.redraw();
    };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); read(); if (which === 'to' && !input.hasAttribute('aria-invalid')) { this.apply(); } } });
    input.addEventListener('change', read);
    return { input: input, wrap: this.el('label', { cls: 'di-end' }, [this.el('span', { cls: 'di-end-label', text: label }), input]) };
  }

  /** @private the panel after a change of the draft */
  redraw(keepFocus) {
    const d = this.draft;
    if (this.fromIn) { this.fromIn.input.value = d.from ? formatDate(d.from, 'd MMM yyyy') : ''; this.toIn.input.value = d.to ? formatDate(d.to, 'd MMM yyyy') : ''; }
    Array.prototype.forEach.call(this.pop.el ? this.pop.el.querySelectorAll('.di-preset') : [], (b) => { const on = b.dataset.preset === d.preset; b.classList.toggle('on', on); b.setAttribute('aria-selected', String(on)); });
    if (this.view) { if (d.from && !keepFocus) { this.view.focus = d.from; } this.view.render(); if (keepFocus) { this.view.focusDay(); } }
    this.say(d.from && !d.to ? 'Now pick the end' : d.from && d.to ? formatDateRange(d.from, d.to) + ' · ' + (this.cal.businessDaysBetween(d.from, d.to) + (this.cal.isBusinessDay(d.from) ? 1 : 0)) + ' business days' : '');
  }

  /** @private */
  say(t) { if (this.msg) { this.msg.textContent = t; } }

  /**
   * Keeps the panel's range (when it may be kept) and closes it.
   * @returns {boolean} whether it was kept
   */
  apply() {
    const d = this.draft;
    if (!d.from && !d.to) { this.setValue({ from: null, to: null, preset: null }, true); this.pop.close(true); return true; }
    const why = this.refusal(d.from, d.to);
    if (why) { this.say(why); this.emit('invalid', { reason: why }); return false; }
    this.setValue({ from: d.from, to: d.to, preset: d.preset }, true);
    this.pop.close(true);
    return true;
  }

  /**
   * Closes the panel without keeping it.
   * @returns {void}
   */
  close() { this.pop.close(); }

  /**
   * The range ({from, to, preset}); a preset's dates as of the business date.
   * @returns {DateRange}
   */
  getValue() { return Object.assign({}, this.value); }

  /**
   * Sets the range: {from, to}, or {preset} (its dates from the business date), or a preset's id.
   * @param {(DateRange|string)} v
   * @param {boolean} [emit] tell listeners (change)
   * @returns {void}
   */
  setValue(v, emit) {
    if (typeof v === 'string') { v = { preset: v }; }
    v = v || {};
    let next;
    if (v.preset && (!v.from || !v.to)) { const r = resolvePreset(v.preset, { asOf: this.asOf, calendar: this.cal }); next = { from: r.from, to: r.to, preset: v.preset }; }
    else { next = { from: v.from ? isoDate(v.from) : null, to: v.to ? isoDate(v.to) : null, preset: v.preset || null }; }
    const changed = next.from !== this.value.from || next.to !== this.value.to || next.preset !== this.value.preset;
    this.value = next;
    this.show();
    if (changed && emit) { this.emit('change', this.getValue()); announce('Date range ' + this.text()); }
  }

  /**
   * Moves the business date: a preset's dates follow it.
   * @param {string} asOf
   * @returns {void}
   */
  setAsOf(asOf) { this.asOf = isoDate(asOf); if (this.value.preset) { this.setValue({ preset: this.value.preset }, true); } }
}
