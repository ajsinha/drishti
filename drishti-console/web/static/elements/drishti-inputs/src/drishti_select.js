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
/* The single-choice select: one value from a list, the WAI-ARIA select-only combobox. The control (role combobox, its value's label and a
   caret) keeps the focus; the options open under it as a listbox (the same Listbox and Popover as the combobox and the multi-select:
   groups, disabled options, a cap with "refine the search"). Keys on the control: Down, Up, Enter, Space or Alt+Down open; Home and End
   open on the first or last; a printable key types ahead (the first option whose label starts with what was typed in the last half
   second). Keys in the open list: the arrows, Page Up and Down, Home and End move; Enter, Space or Alt+Up pick; Escape and Tab close
   without picking. A long list (more than searchMin options, or search: true) has a search field at the top of the open list, which
   then holds the focus: typing filters (drishti-inputs' searchOptions), the arrows still move. In a page's markup the options may be
   <option> and <optgroup> children of <drishti-select> (selected marks the value); name makes a hidden input for a plain form. */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { InputBase, Listbox, Popover, normalizeOptions, searchOptions, uid } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} SelectOptions
 * @property {Array<(string|number|{value: *, label: string, detail: string, group: string, disabled: boolean})>} [options] default: the
 *   host's <option> and <optgroup> children
 * @property {*} [value] the value picked (default: the option marked selected, else the first enabled option; null with placeholder)
 * @property {string} [label] the control's accessible name
 * @property {string} [placeholder] the words while nothing is picked (and nothing is picked at first)
 * @property {(boolean|string)} [search='auto'] the search field in the open list: true, false, or auto (more than searchMin options)
 * @property {number} [searchMin=8]
 * @property {string} [name] a hidden input of this name carries the value in a plain form
 * @property {boolean} [disabled=false]
 * @property {function(Object, HTMLElement): void} [renderOption] draws an option's content
 * @property {boolean} [native=false] a change also fires input and change on the host, as a native select's did (a page's own listeners
 *   and delegated handlers keep working); selectElement turns it on, and so does the attribute native on a page's <drishti-select>
 */

const TYPE_AHEAD_MS = 500;

/** @private two values are the same choice: a template's "0.99" is the number 0.99 */
const same = (a, b) => a === b || (a !== null && a !== undefined && b !== null && b !== undefined && String(a) === String(b));

/** @private the options a host's markup gives: <option> and <optgroup> children, and the one marked selected */
function fromMarkup(host) {
  const out = [], picked = [];
  const take = (o, group) => {
    out.push({ value: o.hasAttribute('value') ? o.getAttribute('value') : o.textContent.trim(), label: o.textContent.trim(), group: group || '',
      disabled: o.hasAttribute('disabled'), detail: o.getAttribute('title') || '' });
    if (o.hasAttribute('selected')) { picked.push(out[out.length - 1].value); }
  };
  Array.prototype.slice.call(host.children).forEach((c) => {
    const t = c.tagName.toLowerCase();
    if (t === 'option') { take(c, ''); } else if (t === 'optgroup') { Array.prototype.forEach.call(c.querySelectorAll('option'), (o) => take(o, c.getAttribute('label'))); }
    if (t === 'option' || t === 'optgroup') { c.remove(); }
  });
  return { options: out, value: picked.length ? picked[0] : undefined };
}

/**
 * A single-choice select with keyboard, type-ahead and search. Events: change {value, option} (the value changed), pick {value, option} (a person picked, the same value too), open, close.
 */
export class Select extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {SelectOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-select');
    const o = this.o = Object.assign({ search: 'auto', searchMin: 8 }, this.o);
    const marked = o.options === undefined ? fromMarkup(host) : null;
    this.options = normalizeOptions(marked ? marked.options : o.options);
    this.value = null;
    this.typed = '';
    this.typedAt = 0;
    this.list = new Listbox(this.doc, { label: o.label || 'Options', render: o.renderOption, onPick: (it) => this.pick(it) });
    this.pop = new Popover(this.doc, { cls: 'di-select-pop', role: 'presentation', theme: host, onClose: () => this.closed() });
    this.query = this.el('input', { cls: 'di-field di-select-search', attrs: { type: 'text', role: 'combobox', 'aria-expanded': 'true', 'aria-autocomplete': 'list',
      'aria-label': 'Search ' + (o.label || 'the options'), placeholder: 'Type to search', autocomplete: 'off', spellcheck: 'false', id: uid('sel-q') } });
    this.query.addEventListener('input', () => this.filter(true));
    this.query.addEventListener('keydown', (e) => this.onListKey(e, this.query));
    this.status = this.el('div', { cls: 'di-select-status', attrs: { role: 'status', 'aria-live': 'polite' } });
    this.panel = this.el('div', { cls: 'di-select-panel' });
    this.text = this.el('span', { cls: 'di-select-text' });
    this.control = this.el('div', { cls: 'di-control di-select-control', attrs: { role: 'combobox', tabindex: '0', 'aria-haspopup': 'listbox', 'aria-expanded': 'false',
      'aria-label': o.label || null, id: uid('sel') } }, [this.text, this.el('span', { cls: 'di-caret', text: '▾', attrs: { 'aria-hidden': 'true' } })]);
    host.appendChild(this.control);
    if (o.name) {
      this.hidden = this.el('input', { attrs: { type: 'hidden', name: o.name } });
      // the form's named field reads and sets the Select, as a native select's did (form.elements.<name>.value = ...): a hidden input's
      // value is its value attribute, which the form submits, so the field keeps it and a value set on the field picks that option
      const sel = this;
      Object.defineProperty(this.hidden, 'value', { configurable: true, get() { return this.getAttribute('value') || ''; }, set(v) { sel.setValue(v === '' && !sel.options.some((x) => same(x.value, '')) ? null : v); } });
      host.appendChild(this.hidden);
    }
    this.control.addEventListener('mousedown', (e) => { e.preventDefault(); if (this.o.disabled) { return; } this.control.focus(); if (this.pop.isOpen()) { this.close(); } else { this.open(); } });
    this.control.addEventListener('keydown', (e) => this.onKey(e));
    this.control.addEventListener('blur', () => { if (this.pop.isOpen() && !this.searching()) { this.close(); } });
    this.setDisabled(!!o.disabled);
    const first = o.value !== undefined ? o.value : marked && marked.value !== undefined ? marked.value : undefined;
    if (first !== undefined && first !== null) { this.setValue(first); } else if (!o.placeholder) { const f = this.options.find((x) => !x.disabled); this.setValue(f ? f.value : null); } else { this.show(); }
  }

  /** @private whether the open list has its search field */
  searching() { const s = this.o.search; return s === true || (s === 'auto' && this.options.length > this.o.searchMin); }

  /** @private the value's label (or the placeholder) in the control, and the hidden input */
  show() {
    const it = this.getOption();
    this.text.textContent = it ? it.label : this.value !== null ? String(this.value) : (this.o.placeholder || '');
    this.control.classList.toggle('di-placeholder', this.value === null);
    if (this.hidden) { this.hidden.setAttribute('value', this.value === null ? '' : String(this.value)); }
  }

  /**
   * Opens the list (on the picked option, or the first).
   * @param {number} [at] the option to make active (an index of the list drawn)
   * @returns {void}
   */
  open(at) {
    if (this.o.disabled) { return; }
    const search = this.searching();
    this.query.value = '';
    this.panel.textContent = '';
    if (search) { this.panel.appendChild(this.query); }
    this.panel.append(this.list.el, this.status);
    this.filter(false);
    this.pop.open(this.control, this.panel, { focus: false });
    const owner = search ? this.query : this.control;
    owner.setAttribute('aria-controls', this.list.id);
    this.control.setAttribute('aria-controls', this.list.id);
    this.control.setAttribute('aria-expanded', 'true');
    const cur = this.matches.findIndex((x) => same(x.value, this.value));
    this.list.setActive(at !== undefined ? at : cur >= 0 ? cur : 0, owner);
    if (search) { this.query.focus(); }
    this.emit('open');
  }

  /**
   * Closes the list.
   * @param {boolean} [refocus] put the focus back on the control
   * @returns {void}
   */
  close(refocus) { const had = this.doc.activeElement === this.query; this.pop.close(); if ((refocus || had) && this.control.isConnected) { this.control.focus(); } }

  /** @private */
  closed() {
    this.control.setAttribute('aria-expanded', 'false');
    this.control.removeAttribute('aria-activedescendant');
    this.control.removeAttribute('aria-controls');
    this.query.removeAttribute('aria-activedescendant');
    this.emit('close');
  }

  /** @private the options for the search field's words */
  filter(typed) {
    const q = this.searching() ? this.query.value : '';
    this.matches = searchOptions(this.options, q);
    this.list.render(this.matches, (it) => same(it.value, this.value), { empty: q ? 'Nothing matches "' + q + '"' : 'Nothing to pick' });
    this.status.textContent = q ? this.matches.length + (this.matches.length === 1 ? ' match' : ' matches') : '';
    if (typed) { this.list.setActive(this.matches.length ? 0 : -1, this.query); }
    if (this.pop.isOpen()) { this.pop.place(); }
  }

  /** @private keys on the control */
  onKey(e) {
    if (this.o.disabled) { return; }
    const k = e.key;
    if (this.pop.isOpen()) { this.onListKey(e, this.control); return; }
    if (k === 'ArrowDown' || k === 'ArrowUp' || k === 'Enter' || k === ' ') { e.preventDefault(); this.open(); return; }
    if (k === 'Home' || k === 'End') { e.preventDefault(); this.open(); this.list.setActive(k === 'Home' ? 0 : this.matches.length - 1, this.control); return; }
    if (this.printable(e)) {
      e.preventDefault();
      if (this.searching()) { this.open(); this.query.value = k; this.filter(true); return; }
      this.open();
      this.typeAhead(k);
    }
  }

  /** @private a key that types a character */
  printable(e) { return e.key.length === 1 && e.key !== ' ' && !e.ctrlKey && !e.metaKey && !e.altKey; }

  /** @private keys while the list is open, from the control or the search field */
  onListKey(e, owner) {
    const k = e.key;
    if (k === 'Escape') { e.preventDefault(); e.stopPropagation(); this.close(true); return; }
    if (k === 'Tab') { this.close(); return; }
    if (k === 'ArrowUp' && e.altKey) { e.preventDefault(); const it = this.matches[this.list.active]; if (it && !it.disabled) { this.pick(it); } else { this.close(true); } return; }
    if ((k === 'Home' || k === 'End') && owner === this.control) { e.preventDefault(); this.list.setActive(k === 'Home' ? 0 : this.matches.length - 1, owner); return; }
    if (k === ' ' && owner === this.control) { e.preventDefault(); const it = this.matches[this.list.active]; if (it && !it.disabled) { this.pick(it); } return; }
    if (this.list.key(e, owner)) { return; }
    if (owner === this.control && this.printable(e)) { e.preventDefault(); this.typeAhead(k); }
  }

  /** @private moves to the first option (after the active one when the same letter is typed again) whose label starts with what was typed */
  typeAhead(ch) {
    const now = Date.now();
    this.typed = now - this.typedAt > TYPE_AHEAD_MS ? ch : this.typed + ch;
    this.typedAt = now;
    const t = this.typed.toLowerCase(), n = this.matches.length;
    const repeat = t.length > 1 && t.split('').every((c) => c === t[0]);
    const word = repeat ? t[0] : t, start = repeat || t.length === 1 ? this.list.active + 1 : this.list.active;
    for (let i = 0; i < n; i++) {
      const j = (Math.max(0, start) + i) % n;
      if (this.matches[j].label.toLowerCase().indexOf(word) === 0) { this.list.setActive(j, this.control); return; }
    }
  }

  /**
   * Picks an option (a disabled one is refused).
   * @param {{value: *, label: string}} it
   * @returns {void}
   */
  pick(it) {
    if (!it || it.disabled) { return; }
    this.close(true);
    this.setValue(it.value, true);
    this.emit('pick', { value: this.value, option: this.getOption() });      // every pick, the same value too (a cell editor saves on it)
  }

  /**
   * The value picked, or null.
   * @returns {*}
   */
  getValue() { return this.value; }

  /**
   * The option picked ({value, label, detail, group, disabled}), or null (nothing picked, or a value no option has).
   * @returns {?Object}
   */
  getOption() { const v = this.value; return v === null ? null : this.options.find((x) => same(x.value, v)) || null; }

  /**
   * Sets the value: an option's value (the option's own value is kept: "0.99" picks the option 0.99), or one no option has (shown as it is).
   * @param {*} v
   * @param {boolean} [emit] emit change when it changed
   * @returns {void}
   */
  setValue(v, emit) {
    const before = this.value;
    const it = v === null || v === undefined ? null : this.options.find((x) => same(x.value, v));
    this.value = it ? it.value : v === undefined ? null : v;
    this.show();
    if (emit && !same(before, this.value)) {
      announce((it ? it.label : String(this.value)) + ' picked');
      this.emit('change', { value: this.value, option: this.getOption() });
      if (this.o.native) { ['input', 'change'].forEach((t) => this.host.dispatchEvent(new this.win.Event(t, { bubbles: true }))); }
    }
  }

  /**
   * Replaces the options; the value is kept when an option still has it, else value (when given), else the first enabled option.
   * @param {Array} list as the options option
   * @param {*} [value]
   * @returns {void}
   */
  setOptions(list, value) {
    this.options = normalizeOptions(list);
    const want = value !== undefined ? value : this.value;
    const keep = want !== null && this.options.some((x) => same(x.value, want));
    if (keep || (want !== null && value !== undefined)) { this.setValue(want); } else if (this.o.placeholder) { this.setValue(null); } else { const f = this.options.find((x) => !x.disabled); this.setValue(f ? f.value : null); }
    if (this.pop.isOpen()) { this.filter(false); }
  }

  /**
   * Turns the select on or off.
   * @param {boolean} off
   * @returns {void}
   */
  setDisabled(off) {
    this.o.disabled = !!off;
    this.control.setAttribute('aria-disabled', off ? 'true' : 'false');
    this.control.setAttribute('tabindex', off ? '-1' : '0');
    this.host.classList.toggle('di-disabled', !!off);
    if (off) { this.close(); }
  }

  /**
   * Puts the keyboard focus on the control.
   * @returns {void}
   */
  focus() { this.control.focus(); }
}

/**
 * A Select in its own <drishti-select> element, for a screen or a library that draws one in code: the element carries the attributes
 * (an id, a hook, a class), its value and component are as on any page (el.value, el.component, el.disabled, el.focus()), and a pick fires input
 * and change on it as a native select's did. options as the Select's; options.options may be values, [value, label] pairs or objects.
 * @param {Document} doc
 * @param {SelectOptions} options
 * @param {Object<string, *>} [attrs] attributes of the element (null, undefined and false are left out; true is the empty value)
 * @returns {HTMLElement} the <drishti-select>
 */
export function selectElement(doc, options, attrs) {
  const box = doc.createElement('drishti-select'), o = Object.assign({ native: true }, options);
  if (Array.isArray(o.options)) { o.options = o.options.map((x) => (Array.isArray(x) ? { value: x[0], label: String(x[1]) } : x)); }
  Object.keys(attrs || {}).forEach((k) => { const v = attrs[k]; if (v !== null && v !== undefined && v !== false) { box.setAttribute(k, v === true ? '' : String(v)); } });
  box.component = new Select(box, o);                           // made here: the element does not make a second one when it joins the page
  if (!('value' in box)) { selectProperties(box); }             // no custom elements here (a test's document): the same properties
  return box;
}

/**
 * Gives a <drishti-select> element the properties a native select had, read through its component: value, disabled and focus().
 * @param {HTMLElement} box
 * @returns {void}
 */
export function selectProperties(box) {
  Object.defineProperty(box, 'value', { configurable: true, get() { return this.component ? this.component.getValue() : null; }, set(v) { if (this.component) { this.component.setValue(v); } } });
  box.focus = function () { if (this.component) { this.component.focus(); } };
  Object.defineProperty(box, 'disabled', { configurable: true, get() { return !!(this.component && this.component.o.disabled); }, set(v) { if (this.component) { this.component.setDisabled(!!v); } } });
}
