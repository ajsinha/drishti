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
/* The combobox: a field whose options come as a person types, from a list or from a server. TypeAhead is its model (tested in Node):
   a search waits until typing pauses (debounce), needs a few characters (minChars), aborts the request it replaces (AbortSignal), keeps
   recent answers (a cache by query), and drops an answer that comes back after a newer one was asked. The drawn combobox follows the
   ARIA combobox pattern: the arrows move through the list, Enter picks, Escape closes (and, pressed again, clears), Tab keeps the pick.
   With allowFree, typed text that is not an option is a value of its own. */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { InputBase, Listbox, Popover, normalizeOptions, searchOptions, uid } from './drishti_inputs-dom.js';

/**
 * Options from a source as a person types: debounced, cached, aborted and in order.
 */
export class TypeAhead {
  /**
   * @param {{source: (Array|function(string, AbortSignal): Promise<Array>), debounceMs: number, minChars: number, cacheSize: number,
   *   onResult: function({query: string, options: Array, error: ?Error, loading: boolean}): void}} o debounceMs default 200 (0 for a list);
   *   minChars default 1 (0 for a list); cacheSize default 50 queries
   */
  constructor(o) {
    const local = Array.isArray(o.source);
    this.o = Object.assign({ debounceMs: local ? 0 : 200, minChars: local ? 0 : 1, cacheSize: 50 }, o);
    this.list = local ? normalizeOptions(o.source) : null;
    this.cache = new Map();
    this.timer = 0;
    this.ctrl = null;
    this.seq = 0;
    this.asked = 0;
  }

  /**
   * Asks for the options of a query (after the debounce); the answer goes to onResult.
   * @param {string} q
   * @returns {void}
   */
  query(q) {
    clearTimeout(this.timer);
    const query = String(q || '');
    const id = ++this.seq;
    if (this.list) { this.o.onResult({ query: query, options: searchOptions(this.list, query), error: null, loading: false }); return; }
    if (query.trim().length < this.o.minChars) { this.abort(); this.o.onResult({ query: query, options: [], error: null, loading: false, short: true }); return; }
    if (this.cache.has(query)) { this.abort(); const opts = this.cache.get(query); this.cache.delete(query); this.cache.set(query, opts); this.o.onResult({ query: query, options: opts, error: null, loading: false }); return; }
    this.o.onResult({ query: query, options: null, error: null, loading: true });
    this.timer = setTimeout(() => this.run(query, id), this.o.debounceMs);
  }

  /** @private */
  run(query, id) {
    this.abort();
    const ctrl = this.ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    this.asked++;
    Promise.resolve().then(() => this.o.source(query, ctrl ? ctrl.signal : undefined)).then((res) => {
      if (id !== this.seq) { return; }
      const opts = normalizeOptions(res || []);
      this.cache.set(query, opts);
      while (this.cache.size > this.o.cacheSize) { this.cache.delete(this.cache.keys().next().value); }
      this.o.onResult({ query: query, options: opts, error: null, loading: false });
    }, (err) => {
      if (id !== this.seq || (err && (err.name === 'AbortError' || err.aborted))) { return; }
      this.o.onResult({ query: query, options: [], error: err instanceof Error ? err : new Error(String(err)), loading: false });
    });
  }

  /**
   * Aborts the request in flight.
   * @returns {void}
   */
  abort() { if (this.ctrl) { this.ctrl.abort(); this.ctrl = null; } }

  /**
   * Stops: no answer is given after this.
   * @returns {void}
   */
  cancel() { clearTimeout(this.timer); this.seq++; this.abort(); }
}

/**
 * @typedef {Object} ComboboxOptions
 * @property {(Array|function(string, AbortSignal): Promise<Array>)} source the options, or a server search: (text, signal) to options
 * @property {{value: *, label: string}} [value]
 * @property {number} [debounceMs=200]
 * @property {number} [minChars=1]
 * @property {boolean} [allowFree=false] typed text that is not an option is a value of its own
 * @property {string} [label]
 * @property {string} [placeholder]
 * @property {function(Object, HTMLElement): void} [renderOption] draws an option's content
 */

/**
 * A combobox with type-ahead. Events: change {value, option}, search {query}, error {error}.
 */
export class Combobox extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {ComboboxOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-combobox');
    const o = this.o;
    this.value = o.value ? { value: o.value.value, label: String(o.value.label !== undefined ? o.value.label : o.value.value) } : null;
    this.list = new Listbox(this.doc, { label: o.label || 'Suggestions', render: o.renderOption, onPick: (it) => this.pick(it) });
    this.pop = new Popover(this.doc, { cls: 'di-combo-pop', role: 'presentation', theme: host, onClose: () => { this.input.setAttribute('aria-expanded', 'false'); this.input.removeAttribute('aria-activedescendant'); } });
    this.status = this.el('div', { cls: 'di-combo-status', attrs: { role: 'status', 'aria-live': 'polite' } });
    this.panel = this.el('div', { cls: 'di-combo-panel' }, [this.list.el, this.status]);
    this.input = this.el('input', { cls: 'di-field', attrs: { type: 'text', role: 'combobox', 'aria-expanded': 'false', 'aria-autocomplete': 'list', 'aria-controls': this.list.id,
      'aria-label': o.label || 'Search', placeholder: o.placeholder || 'Type to search', autocomplete: 'off', spellcheck: 'false', id: uid('combo') } });
    this.control = this.el('div', { cls: 'di-control' }, [this.input]);
    host.appendChild(this.control);
    this.ta = new TypeAhead({ source: o.source, debounceMs: o.debounceMs, minChars: o.minChars, onResult: (r) => this.result(r) });
    this.input.addEventListener('input', () => { this.emit('search', { query: this.input.value }); this.ta.query(this.input.value); });
    this.input.addEventListener('focus', () => { if (Array.isArray(o.source)) { this.ta.query(this.input.value === (this.value && this.value.label) ? '' : this.input.value); } });
    this.input.addEventListener('keydown', (e) => this.onKey(e));
    this.input.addEventListener('blur', () => { this.ta.cancel(); this.settle(); this.pop.close(); });
    this.show();
  }

  /** @private */
  show() { this.input.value = this.value ? this.value.label : ''; }

  /** @private an answer of the type-ahead */
  result(r) {
    if (this.doc.activeElement !== this.input) { return; }
    if (r.loading) { this.status.textContent = 'Searching…'; this.host.classList.add('di-busy'); this.openPanel(); return; }
    this.host.classList.remove('di-busy');
    if (r.error) { this.status.textContent = 'The search failed: ' + r.error.message; this.list.render([], () => false, { empty: ' ' }); this.emit('error', { error: r.error }); this.openPanel(); return; }
    if (r.short) { this.pop.close(); this.status.textContent = ''; return; }
    this.list.render(r.options, (it) => !!this.value && it.value === this.value.value, { empty: r.query ? 'Nothing matches "' + r.query + '"' : 'Nothing to pick' });
    this.list.setActive(r.options.length ? 0 : -1, this.input);
    this.status.textContent = r.options.length ? r.options.length + (r.options.length === 1 ? ' suggestion' : ' suggestions') : '';
    this.openPanel();
  }

  /** @private */
  openPanel() { if (!this.pop.isOpen()) { this.pop.open(this.control, this.panel, { focus: false }); this.input.setAttribute('aria-expanded', 'true'); } else { this.pop.place(); } }

  /** @private */
  onKey(e) {
    if (e.key === 'ArrowDown' && !this.pop.isOpen()) { e.preventDefault(); this.ta.query(this.input.value); return; }
    if (e.key === 'Escape') {
      if (this.pop.isOpen()) { e.preventDefault(); this.ta.cancel(); this.pop.close(); return; }
      if (this.input.value) { e.preventDefault(); this.input.value = ''; this.setValue(null, true); }
      return;
    }
    if (this.pop.isOpen() && this.list.key(e, this.input)) { return; }
    if (e.key === 'Enter') { e.preventDefault(); this.settle(); }
  }

  /** @private the field's text kept as a value (allowFree), or put back as the value's label */
  settle() {
    const t = this.input.value.trim();
    if (this.value && t === this.value.label) { return; }
    if (!t) { if (this.value) { this.setValue(null, true); } return; }
    if (this.o.allowFree) { this.setValue({ value: t, label: t }, true); return; }
    this.show();
  }

  /**
   * Picks an option.
   * @param {{value: *, label: string}} it
   * @returns {void}
   */
  pick(it) {
    this.ta.cancel();
    this.setValue({ value: it.value, label: it.label }, true, it);
    this.pop.close();
    this.input.focus();
  }

  /**
   * The value ({value, label}) or null.
   * @returns {?{value: *, label: string}}
   */
  getValue() { return this.value ? Object.assign({}, this.value) : null; }

  /**
   * Sets the value.
   * @param {?{value: *, label: string}} v
   * @param {boolean} [emit]
   * @param {Object} [option] the option picked (passed on in change)
   * @returns {void}
   */
  setValue(v, emit, option) {
    const before = this.value ? this.value.value : null;
    this.value = v ? { value: v.value, label: String(v.label !== undefined ? v.label : v.value) } : null;
    this.show();
    if (emit && (this.value ? this.value.value : null) !== before) { announce(this.value ? this.value.label + ' picked' : 'Cleared'); this.emit('change', { value: this.getValue(), option: option || null }); }
  }

  /**
   * Puts the keyboard focus in the field.
   * @returns {void}
   */
  focus() { this.input.focus(); }

  /**
   * Stops the type-ahead and takes the combobox out.
   * @returns {void}
   */
  destroy() { this.ta.cancel(); super.destroy(); }
}
