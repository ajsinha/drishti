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
/* The multi-select: chips for what is picked (a chip's × or Backspace in the empty field takes one away; past maxChips a "+N" chip),
   a search field in the control (role combobox), and under it the options as a listbox with Select all (of what the search matches) and
   Clear. The arrows move through the options, Enter or a click picks or unpicks one and keeps the list open, Escape closes. Options are
   given (strings or {value, label, detail, group}); thousands are fine (the list draws the best 300 matches and says how many more). */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { InputBase, Listbox, Popover, normalizeOptions, searchOptions, uid } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} MultiSelectOptions
 * @property {Array<(string|number|{value: *, label: string, detail: string, group: string, disabled: boolean})>} options
 * @property {Array<*>} [value] the picked values
 * @property {number} [maxChips=3] chips shown before "+N"
 * @property {number} [max] the most that may be picked
 * @property {boolean} [selectAll=true] the Select all and Clear buttons
 * @property {string} [label]
 * @property {string} [placeholder='Any']
 */

/**
 * A multi-select with search, chips and select-all. Events: change {value, added, removed}, open, close.
 */
export class MultiSelect extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {MultiSelectOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-multiselect');
    const o = this.o = Object.assign({ maxChips: 3, selectAll: true, placeholder: 'Any' }, this.o);
    this.options = normalizeOptions(o.options);
    this.picked = new Set();
    (o.value || []).forEach((v) => this.picked.add(this.key(v)));
    this.matches = this.options;
    this.list = new Listbox(this.doc, { multiple: true, label: o.label || 'Options', onPick: (it) => this.toggle(it.value) });
    this.pop = new Popover(this.doc, { cls: 'di-ms-pop', role: 'presentation', theme: host, onClose: () => { this.input.setAttribute('aria-expanded', 'false'); this.input.removeAttribute('aria-activedescendant'); this.input.removeAttribute('aria-controls'); this.emit('close'); } });
    this.chips = this.el('span', { cls: 'di-chips' });
    this.input = this.el('input', { cls: 'di-inline-field', attrs: { type: 'text', role: 'combobox', 'aria-expanded': 'false', 'aria-autocomplete': 'list',
      'aria-label': o.label || 'Choose', autocomplete: 'off', spellcheck: 'false', id: uid('ms') } });
    this.control = this.el('div', { cls: 'di-control di-ms-control' }, [this.chips, this.input, this.el('span', { cls: 'di-caret', text: '▾', attrs: { 'aria-hidden': 'true' } })]);
    host.appendChild(this.control);
    this.head = this.el('div', { cls: 'di-ms-head' });
    this.panel = this.el('div', { cls: 'di-ms-panel' }, [this.head, this.list.el]);
    this.control.addEventListener('mousedown', (e) => { if (e.target.closest('.di-chip-x')) { return; } if (e.target !== this.input) { e.preventDefault(); this.input.focus(); } if (!this.pop.isOpen()) { this.open(); } });   // a press anywhere on the control, the field too, opens the list
    this.control.addEventListener('click', (e) => { const x = e.target.closest('.di-chip-x'); if (x) { this.toggle(this.options.find((it) => this.key(it.value) === x.dataset.key).value); } });
    this.input.addEventListener('input', () => { if (!this.pop.isOpen()) { this.open(); } this.filter(); });
    this.input.addEventListener('keydown', (e) => this.onKey(e));
    this.render();
  }

  /** @private a value's key in the picked set */
  key(v) { return typeof v + ':' + String(v); }

  /** @private */
  onKey(e) {
    if (e.key === 'ArrowDown' && !this.pop.isOpen()) { e.preventDefault(); this.open(); return; }
    if (e.key === 'Escape' && this.pop.isOpen()) { e.preventDefault(); this.pop.close(); return; }
    if (e.key === 'Backspace' && !this.input.value && this.picked.size) {
      const last = Array.from(this.picked).pop(), it = this.options.find((x) => this.key(x.value) === last);
      if (it) { e.preventDefault(); this.toggle(it.value); }
      return;
    }
    if (e.key === 'a' && (e.ctrlKey || e.metaKey) && this.pop.isOpen() && !this.input.value) { e.preventDefault(); this.selectAll(true); return; }
    if (this.pop.isOpen() && this.list.key(e, this.input)) { return; }
  }

  /**
   * Opens the list.
   * @returns {void}
   */
  open() {
    this.filter();
    this.pop.open(this.control, this.panel, { focus: false });
    this.input.setAttribute('aria-controls', this.list.id);              // only while the listbox is in the page (a closed one is not)
    this.input.setAttribute('aria-expanded', 'true');
    this.emit('open');
  }

  /**
   * Closes the list.
   * @returns {void}
   */
  close() { this.pop.close(); }

  /** @private the options for the search */
  filter() {
    this.matches = searchOptions(this.options, this.input.value);
    this.list.render(this.matches, (it) => this.picked.has(this.key(it.value)), { empty: 'Nothing matches "' + this.input.value + '"' });
    if (this.list.active < 0 && this.matches.length) { this.list.setActive(0, this.input); }
    this.head.textContent = '';
    if (this.o.selectAll) {
      const q = this.input.value.trim();
      this.head.append(this.el('button', { cls: 'di-btn', text: q ? 'Select the ' + this.matches.length + ' matching' : 'Select all', attrs: { type: 'button', tabindex: '-1' }, data: { act: 'all' },
        on: { mousedown: (e) => e.preventDefault(), click: () => this.selectAll(true) } }),
      this.el('button', { cls: 'di-btn', text: 'Clear', attrs: { type: 'button', tabindex: '-1' }, data: { act: 'clear' }, on: { mousedown: (e) => e.preventDefault(), click: () => this.selectAll(false) } }));
    }
    this.head.appendChild(this.el('span', { cls: 'di-count', text: this.picked.size + ' of ' + this.options.length }));
    if (this.pop.isOpen()) { this.pop.place(); }
  }

  /**
   * Picks or unpicks one value.
   * @param {*} v
   * @returns {void}
   */
  toggle(v) {
    const k = this.key(v), it = this.options.find((x) => this.key(x.value) === k);
    if (!it || it.disabled) { return; }
    const added = [], removed = [];
    if (this.picked.has(k)) { this.picked.delete(k); removed.push(v); }
    else {
      if (this.o.max && this.picked.size >= this.o.max) { announce('At most ' + this.o.max + ' may be picked'); this.emit('invalid', { reason: 'At most ' + this.o.max + ' may be picked' }); return; }
      this.picked.add(k); added.push(v);
    }
    announce(it.label + (added.length ? ' picked' : ' removed') + ', ' + this.picked.size + ' picked');
    this.render();
    if (this.pop.isOpen()) { this.filter(); }
    this.emit('change', { value: this.getValue(), added: added, removed: removed });
  }

  /**
   * Picks every option the search matches (or every option), or none (Clear: every pick, matching or not).
   * @param {boolean} on
   * @returns {void}
   */
  selectAll(on) {
    const before = new Set(this.picked);
    if (on) { (this.input.value.trim() ? this.matches : this.options).forEach((it) => { if (!it.disabled && (!this.o.max || this.picked.size < this.o.max)) { this.picked.add(this.key(it.value)); } }); }
    else { this.picked.clear(); }
    this.render();
    if (this.pop.isOpen()) { this.filter(); }
    const added = this.options.filter((it) => this.picked.has(this.key(it.value)) && !before.has(this.key(it.value))).map((it) => it.value);
    const removed = this.options.filter((it) => !this.picked.has(this.key(it.value)) && before.has(this.key(it.value))).map((it) => it.value);
    announce(this.picked.size + ' picked');
    this.emit('change', { value: this.getValue(), added: added, removed: removed });
  }

  /**
   * Draws the chips.
   * @returns {void}
   */
  render() {
    this.chips.textContent = '';
    const picked = this.options.filter((it) => this.picked.has(this.key(it.value)));
    picked.slice(0, this.o.maxChips).forEach((it) => this.chips.appendChild(this.el('span', { cls: 'di-chip' }, [this.el('span', { cls: 'di-chip-label', text: it.label }),
      this.el('button', { cls: 'di-chip-x', text: '×', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Remove ' + it.label }, data: { key: this.key(it.value) } })])));
    if (picked.length > this.o.maxChips) { this.chips.appendChild(this.el('span', { cls: 'di-chip di-chip-more', text: '+' + (picked.length - this.o.maxChips), attrs: { title: picked.slice(this.o.maxChips).map((x) => x.label).join(', ') } })); }
    this.input.placeholder = picked.length ? '' : this.o.placeholder;
    this.host.title = picked.map((x) => x.label).join(', ');
  }

  /**
   * The picked values, in the options' order.
   * @returns {Array<*>}
   */
  getValue() { return this.options.filter((it) => this.picked.has(this.key(it.value))).map((it) => it.value); }

  /**
   * Sets the picked values.
   * @param {Array<*>} values
   * @returns {void}
   */
  setValue(values) { this.picked = new Set((values || []).map((v) => this.key(v))); this.render(); if (this.pop.isOpen()) { this.filter(); } }

  /**
   * Replaces the options (picks that are no longer options are dropped).
   * @param {Array} options
   * @returns {void}
   */
  setOptions(options) {
    this.options = normalizeOptions(options);
    const keys = new Set(this.options.map((it) => this.key(it.value)));
    this.picked = new Set(Array.from(this.picked).filter((k) => keys.has(k)));
    this.render();
    if (this.pop.isOpen()) { this.filter(); }
  }

  /**
   * Puts the keyboard focus in the field.
   * @returns {void}
   */
  focus() { this.input.focus(); }
}
