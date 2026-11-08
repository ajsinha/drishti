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
/* Upgrading a page's own fields in place (progressive enhancement): a server-drawn form keeps working without scripts, and with them its
   `<input type="date">` becomes the business-date picker, `<input type="time">` the time picker, `<input type="datetime-local">` the
   date-and-time picker (its zone from data-time-zone), and its group of checkboxes the multi-select. The page's field stays the one
   that is submitted and read: the date input turns into a hidden input with the same name, id and value (its `value` setter moves the
   picker too, and the picker's changes are written back with `input` and `change` events, so the page's own listeners keep working);
   the checkboxes stay in their group, hidden, ticked and unticked by the multi-select with a `change` event each. Attributes the page
   changes later follow: `hidden`, `disabled`, `min` and `max` on the date; on a checkbox's label a class that takes the option away
   (a dependent list's `offClass`), and ticks made by the page. */
import { HolidayCalendar } from './drishti_calendar.js';
import { DatePicker } from './drishti_date-picker.js';
import { TimePicker, DateTimePicker } from './drishti_time-picker.js';
import { MultiSelect } from './drishti_multiselect.js';
import { upgradeForms } from './drishti_form-host.js';

/** @private the text of the label(s) that name a field, or its aria-label */
function labelOf(input, fallback) {
  const own = input.getAttribute('aria-label');
  if (own) { return own; }
  const ls = input.labels ? Array.from(input.labels) : [];
  const t = ls.map((l) => Array.from(l.childNodes).filter((n) => n.nodeType === 3 || (n.nodeType === 1 && !/^(INPUT|SELECT|TEXTAREA|SPAN)$/.test(n.tagName) && !n.classList.contains('di'))).map((n) => n.textContent).join(' ')).join(' ').replace(/\s+/g, ' ').trim();
  return t || input.name || fallback || 'Date';
}

/**
 * @typedef {Object} UpgradeDateOptions
 * @property {(string|Object)} [calendar] the business calendar; default: the field's data-calendar, or its data-holidays (dates,
 *   comma separated, from the server) as a calendar of their own, else weekends only
 * @property {boolean} [businessOnly] only business days; default: the field's data-business attribute
 * @property {string} [asOf] the business date T; default: the field's data-current
 * @property {boolean} [shortcuts] the business date, T-1 and month-end; default true when businessOnly
 * @property {string} [label] default: the field's aria-label or its label's text
 * @property {string} [placeholder]
 */

/**
 * Upgrades an `<input type="date">` to the business-date picker in place (once; a second call answers the same picker). The input
 * becomes a hidden input with its name, id and value; the picker is drawn just after it.
 * @param {HTMLInputElement} input
 * @param {UpgradeDateOptions} [options]
 * @returns {?DatePicker}
 */
export function upgradeDateInput(input, options) {
  return upgrade(input, options, (o, ds, start, cal, business) => ({ Cls: DatePicker, opts: {
    value: start || null, calendar: cal || undefined, businessOnly: business, asOf: o.asOf || ds.current || undefined,
    min: input.getAttribute('min') || undefined, max: input.getAttribute('max') || undefined, required: input.required,
    shortcuts: o.shortcuts !== undefined ? o.shortcuts : business, label: o.label || labelOf(input),
    placeholder: o.placeholder || input.getAttribute('placeholder') || undefined } }));
}

/**
 * Upgrades an `<input type="time">` to the time picker in place ("HH:MM"), as upgradeDateInput does a date.
 * @param {HTMLInputElement} input
 * @param {{label: string, stepMinutes: number}} [options] stepMinutes: default the field's step (seconds) in minutes, else 15
 * @returns {?TimePicker}
 */
export function upgradeTimeInput(input, options) {
  return upgrade(input, options, (o, ds, start) => ({ Cls: TimePicker, opts: { value: start || null, required: input.required, label: o.label || labelOf(input, 'Time'),
    step: o.stepMinutes || (+input.getAttribute('step') >= 60 ? +input.getAttribute('step') / 60 : 15), min: input.getAttribute('min') || undefined, max: input.getAttribute('max') || undefined } }));
}

/**
 * Upgrades an `<input type="datetime-local">` to the date-and-time picker in place ("YYYY-MM-DDTHH:MM"; data-time-zone names the zone).
 * @param {HTMLInputElement} input
 * @param {{label: string, timeZone: string}} [options]
 * @returns {?DateTimePicker}
 */
export function upgradeDateTimeInput(input, options) {
  return upgrade(input, options, (o, ds, start, cal, business) => ({ Cls: DateTimePicker, opts: { value: start || null, required: input.required, label: o.label || labelOf(input, 'Date and time'),
    timeZone: o.timeZone || ds.timeZone || undefined, calendar: cal || undefined, businessOnly: business, min: input.getAttribute('min') || undefined, max: input.getAttribute('max') || undefined } }));
}

/** @private the upgrade itself: the input hidden in place, the picker after it, the value kept in both directions */
function upgrade(input, options, make) {
  if (!input || !input.ownerDocument) { return null; }
  if (input.diPicker) { return input.diPicker; }
  const doc = input.ownerDocument, win = doc.defaultView, o = Object.assign({}, options);
  const ds = input.dataset || {};
  let cal = o.calendar || ds.calendar || null;
  if (!cal && ds.holidays !== undefined) { cal = new HolidayCalendar({ name: ds.calendarName || 'the business', dates: String(ds.holidays).split(',').map((x) => x.trim()).filter(Boolean) }); }
  const business = o.businessOnly !== undefined ? !!o.businessOnly : ds.business !== undefined;
  const host = doc.createElement('span');
  host.className = 'di-upgraded' + (input.className ? ' ' + input.className.split(/\s+/).filter((c) => c && c !== 'mono').map((c) => 'di-was-' + c).join(' ') : '');
  input.insertAdjacentElement('afterend', host);
  const proto = Object.getOwnPropertyDescriptor(win.HTMLInputElement.prototype, 'value');
  const start = proto.get.call(input) || input.getAttribute('value') || '';
  const made = make(o, ds, start, cal, business), picker = new made.Cls(host, made.opts);
  input.type = 'hidden';
  proto.set.call(input, start);
  Object.defineProperty(input, 'value', {
    configurable: true,
    get() { return proto.get.call(input); },
    set(v) { proto.set.call(input, v == null ? '' : String(v)); picker.setValue(v ? String(v) : null); }
  });
  input.focus = () => picker.focus();
  input.showPicker = () => (picker.open ? picker.open() : picker.focus());
  picker.on('change', (e) => {
    proto.set.call(input, e.value || '');
    input.dispatchEvent(new win.Event('input', { bubbles: true }));
    input.dispatchEvent(new win.Event('change', { bubbles: true }));
  });
  const follow = () => {
    host.hidden = input.hidden;
    host.querySelectorAll('input, button').forEach((e) => { e.disabled = input.disabled; });
    picker.o.min = input.getAttribute('min') || undefined;
    picker.o.max = input.getAttribute('max') || undefined;
  };
  follow();
  if (win.MutationObserver) { new win.MutationObserver(follow).observe(input, { attributes: true, attributeFilter: ['hidden', 'disabled', 'min', 'max'] }); }
  if (input.form) {                        // the form's own check: a required or unreadable date stops the submit, its words beside it
    input.form.addEventListener('submit', (e) => {
      if (input.disabled || host.hidden || picker.validate()) { return; }
      e.preventDefault();
      e.stopImmediatePropagation();
      picker.focus();
    }, true);
  }
  input.diPicker = picker;
  return picker;
}

/**
 * @typedef {Object} UpgradeChecksOptions
 * @property {string} [label] default: the group's aria-label
 * @property {string} [offClass] a label with this class is not offered (a dependent list's values that do not apply)
 * @property {number} [maxChips]
 * @property {string} [placeholder]
 */

/**
 * Upgrades a group of checkbox labels to the multi-select in place (once). The group stays in the page, hidden, with its checkboxes:
 * the form submits them as before. Each option is a checkbox: its value, and as its label the checkbox's data-text or its label's text.
 * @param {HTMLElement} group
 * @param {UpgradeChecksOptions} [options]
 * @returns {?{picker: MultiSelect, host: HTMLElement, sync: function(): void}}
 */
export function upgradeCheckboxes(group, options) {
  if (!group || !group.ownerDocument) { return null; }
  if (group.diUpgrade) { return group.diUpgrade; }
  const doc = group.ownerDocument, win = doc.defaultView, o = Object.assign({}, options);
  const boxes = () => Array.from(group.querySelectorAll('input[type=checkbox]'));
  const text = (b) => b.getAttribute('data-text') || ((b.closest('label') || b.parentNode).textContent || '').trim() || b.value;
  const offered = () => boxes().filter((b) => !(o.offClass && b.closest('label') && b.closest('label').classList.contains(o.offClass)));
  const opts = () => offered().map((b) => ({ value: b.value, label: text(b), disabled: b.disabled }));
  const ticked = () => boxes().filter((b) => b.checked).map((b) => b.value);
  const host = doc.createElement('div');
  host.className = 'di-upgraded di-upgraded-checks';
  if (group.id) { host.setAttribute('data-for', group.id); }
  group.insertAdjacentElement('beforebegin', host);
  const picker = new MultiSelect(host, { options: opts(), value: ticked(), label: o.label || group.getAttribute('aria-label') || 'Options', maxChips: o.maxChips || 3, placeholder: o.placeholder || 'Any' });
  group.hidden = true;
  let writing = false;
  picker.on('change', (e) => {
    const want = new Set(e.value.map(String));
    writing = true;
    try {
      boxes().forEach((b) => {
        const on = want.has(b.value);
        if (b.checked !== on) { b.checked = on; b.dispatchEvent(new win.Event('change', { bubbles: true })); }
      });
    } finally { writing = false; }
  });
  let last = '';
  const sync = () => {
    if (writing) { return; }
    const sig = JSON.stringify([opts(), ticked()]);
    if (sig === last) { return; }
    last = sig;
    picker.setOptions(opts());
    picker.setValue(ticked());
  };
  last = JSON.stringify([opts(), ticked()]);
  group.addEventListener('change', sync);
  if (win.MutationObserver && o.offClass) { new win.MutationObserver(sync).observe(group, { attributes: true, subtree: true, attributeFilter: ['class'] }); }
  const out = { picker: picker, host: host, sync: sync };
  group.diUpgrade = out;
  return out;
}

/**
 * Upgrades every `<input type="date">`, `"time"` and `"datetime-local"` under a root (not those marked data-native) and every group marked `[data-multiselect]` with more than
 * `small` checkboxes (fewer stay a compact row of checkboxes).
 * @param {(Document|Element)} root
 * @param {{small: number, offClass: string, dates: UpgradeDateOptions}} [options]
 * @returns {{dates: DatePicker[], checks: Array<Object>, forms: Array<Object>}} (forms: the server's form hosts made Forms, drishti_form-host.js)
 */
export function upgradeFields(root, options) {
  const o = Object.assign({ small: 6 }, options);
  const out = { dates: [], checks: [], forms: [] };
  if (!root || !root.querySelectorAll) { return out; }
  out.forms = upgradeForms(root);                            // the server's forms first (their fields are the library's own)
  root.querySelectorAll('input[type=date]:not([data-native])').forEach((i) => { const p = upgradeDateInput(i, o.dates); if (p) { out.dates.push(p); } });
  root.querySelectorAll('input[type=time]:not([data-native])').forEach((i) => { const p = upgradeTimeInput(i); if (p) { out.dates.push(p); } });
  root.querySelectorAll('input[type=datetime-local]:not([data-native])').forEach((i) => { const p = upgradeDateTimeInput(i); if (p) { out.dates.push(p); } });
  root.querySelectorAll('[data-multiselect]').forEach((g) => {
    if (g.diUpgrade || g.querySelectorAll('input[type=checkbox]').length <= o.small) { return; }
    const u = upgradeCheckboxes(g, { offClass: o.offClass });
    if (u) { out.checks.push(u); }
  });
  return out;
}
