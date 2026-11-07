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
/* What every drishti-inputs component shares: the base (an element it fills, options, a value, typed events on its own drishti-core bus
   and as DOM events "drishti:<kebab-name>" on the element), the popover (a floating panel under its anchor: a click outside and Escape
   close it, focus goes back), and the listbox (options with role option, the active one by aria-activedescendant, the arrows, Home, End,
   the page keys, Enter and Space, a cap on how many are drawn with "refine the search" past it). Nothing inline: elements are made with
   the DOM and styled through classes, so a page with a strict CSP draws them. */
import { EventBus, h, placeUnder } from '../../drishti-core/src/drishti_index.js';

let seq = 0;

/**
 * A new id for an element of a component ("di-<prefix>-<n>").
 * @param {string} prefix
 * @returns {string}
 */
export function uid(prefix) { return 'di-' + prefix + '-' + (++seq); }

/**
 * A function that runs fn once calls stop for ms milliseconds (the last call's arguments); .cancel() drops a pending call, .flush() runs it now.
 * @param {function(...*): void} fn
 * @param {number} ms
 * @returns {function(...*): void}
 */
export function debounce(fn, ms) {
  let t = 0, args = null;
  const d = function () { args = arguments; clearTimeout(t); t = setTimeout(() => { t = 0; fn.apply(null, args); }, ms); };
  d.cancel = () => { clearTimeout(t); t = 0; };
  d.flush = () => { if (t) { clearTimeout(t); t = 0; fn.apply(null, args); } };
  return d;
}

/**
 * The base of every input component: the host element, the options, typed events on a bus and on the element.
 */
export class InputBase {
  /**
   * @param {HTMLElement} host the element the component fills
   * @param {Object} options
   * @param {string} cls the component's class on the host (di-date, di-multiselect ...)
   */
  constructor(host, options, cls) {
    if (!host || !host.ownerDocument) { throw new Error('an input needs an element to fill'); }
    this.host = host;
    this.doc = host.ownerDocument;
    this.win = this.doc.defaultView;
    this.o = Object.assign({}, options);
    /** @type {EventBus} the component's events (change, input, open, close, invalid ...) */
    this.events = new EventBus();
    host.classList.add('di', cls);
  }

  /**
   * Listens to one of the component's events.
   * @param {string} name
   * @param {function(Object, string): void} fn
   * @returns {function(): void} stops listening
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * Emits an event on the bus and as a DOM event drishti:<kebab-name> on the host (bubbling).
   * @param {string} name
   * @param {Object} [detail]
   * @returns {void}
   */
  emit(name, detail) {
    this.events.emit(name, detail || {});
    const ev = 'drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
    this.host.dispatchEvent(new this.win.CustomEvent(ev, { detail: detail || {}, bubbles: true, composed: true }));
  }

  /**
   * Makes an element of this document.
   * @param {string} tag
   * @param {Object} [p] as drishti-core's h
   * @param {Array} [children]
   * @returns {HTMLElement}
   */
  el(tag, p, children) { return h(this.doc, tag, p, children); }

  /**
   * Takes the component out of its element.
   * @returns {void}
   */
  destroy() { if (this.pop) { this.pop.close(); } this.events.clear(); this.host.textContent = ''; }
}

/**
 * A floating panel under an anchor (position fixed, in the page's body): a click outside and Escape close it; focus goes back to where it was.
 */
export class Popover {
  /**
   * @param {Document} doc
   * @param {{cls: string, label: string, role: string, onClose: function(): void, theme: Element}} [o] theme: the element whose density
   *   the panel copies
   */
  constructor(doc, o) {
    this.doc = doc;
    this.o = o || {};
    this.el = null;
    this.onDown = (e) => { if (this.el && !this.el.contains(e.target) && !(this.anchor && this.anchor.contains(e.target))) { this.close(); } };
    this.onKey = (e) => { if (e.key === 'Escape' && this.el) { e.stopPropagation(); this.close(true); } };
  }

  /**
   * Whether it is open.
   * @returns {boolean}
   */
  isOpen() { return !!this.el; }

  /**
   * Opens the panel under an anchor with its content.
   * @param {Element} anchor
   * @param {Node} content
   * @param {{focus: boolean}} [o] focus: keep the focus where it is (a combobox's field keeps it)
   * @returns {HTMLElement}
   */
  open(anchor, content, o) {
    this.close();
    this.anchor = anchor;
    this.before = this.doc.activeElement;
    const el = this.el = h(this.doc, 'div', { cls: 'di-pop ' + (this.o.cls || ''), attrs: { role: this.o.role || 'dialog', 'aria-label': this.o.label || null } });
    if (this.o.theme && this.o.theme.closest) { const t = this.o.theme.closest('[data-density]'); if (t) { el.setAttribute('data-density', t.getAttribute('data-density')); } }
    el.appendChild(content);
    el.addEventListener('keydown', this.onKey);
    (anchor.closest && anchor.closest('dialog[open]') || this.doc.body).appendChild(el);   /* inside an open modal dialog: in its top layer, not inert */
    placeUnder(el, anchor, { matchWidth: true });
    this.doc.addEventListener('mousedown', this.onDown, true);
    if (!o || o.focus !== false) { const f = el.querySelector('[autofocus], input, button, [tabindex="0"]'); if (f) { f.focus(); } }
    return el;
  }

  /**
   * Places the panel again (its content changed size).
   * @returns {void}
   */
  place() { if (this.el && this.anchor) { placeUnder(this.el, this.anchor, { matchWidth: true }); } }

  /**
   * Closes the panel.
   * @param {boolean} [refocus] give the focus back to what had it
   * @returns {void}
   */
  close(refocus) {
    if (!this.el) { return; }
    this.doc.removeEventListener('mousedown', this.onDown, true);
    this.el.remove();
    this.el = null;
    if (refocus && this.before && this.before.focus && this.doc.contains(this.before)) { this.before.focus(); }
    if (this.o.onClose) { this.o.onClose(); }
  }
}

/**
 * Options in a list (role listbox): drawn up to a cap, the active one kept by aria-activedescendant on the element that keeps the focus.
 */
export class Listbox {
  /**
   * @param {Document} doc
   * @param {{multiple: boolean, cap: number, label: string, render: function(Object, HTMLElement): void, onPick: function(Object, Event): void}} o
   *   cap: options drawn at most (default 300); render: draws an option's content into its element (default: its label and detail)
   */
  constructor(doc, o) {
    this.doc = doc;
    this.o = Object.assign({ cap: 300 }, o);
    this.id = uid('lb');
    this.el = h(doc, 'div', { cls: 'di-listbox', attrs: { role: 'listbox', id: this.id, 'aria-label': this.o.label || 'Options', 'aria-multiselectable': this.o.multiple ? 'true' : null } });
    this.items = [];
    this.active = -1;
    this.selected = () => false;
    this.el.addEventListener('mousedown', (e) => e.preventDefault());
    this.el.addEventListener('click', (e) => {
      const opt = e.target.closest('.di-option');
      if (opt && !opt.classList.contains('di-disabled')) { this.setActive(+opt.dataset.i); this.o.onPick(this.items[+opt.dataset.i], e); }
    });
  }

  /**
   * Draws options.
   * @param {Array<{value: *, label: string, detail: string, disabled: boolean, group: string}>} items
   * @param {function(Object): boolean} selected whether an option is selected
   * @param {{more: number, empty: string}} [o] more: how many matched beyond these; empty: the words when there are none
   * @returns {void}
   */
  render(items, selected, o) {
    o = o || {};
    this.items = items.slice(0, this.o.cap);
    this.selected = selected;
    const el = this.el;
    el.textContent = '';
    let group = null;
    this.items.forEach((it, i) => {
      if (it.group && it.group !== group) { group = it.group; el.appendChild(h(this.doc, 'div', { cls: 'di-option-group', text: group, attrs: { role: 'presentation' } })); }
      const sel = !!selected(it);
      const opt = h(this.doc, 'div', { cls: 'di-option' + (sel ? ' di-selected' : '') + (it.disabled ? ' di-disabled' : '') + (i === this.active ? ' di-active' : ''),
        attrs: { role: 'option', id: this.id + '-' + i, 'aria-selected': String(sel), 'aria-disabled': it.disabled ? 'true' : null }, data: { i: String(i) } });
      if (this.o.multiple) { opt.appendChild(h(this.doc, 'span', { cls: 'di-tick', text: sel ? '☑' : '☐', attrs: { 'aria-hidden': 'true' } })); }
      if (this.o.render) { this.o.render(it, opt); }
      else {
        opt.appendChild(h(this.doc, 'span', { cls: 'di-option-label', text: it.label }));
        if (it.detail) { opt.appendChild(h(this.doc, 'span', { cls: 'di-option-detail', text: it.detail })); }
      }
      el.appendChild(opt);
    });
    const more = (o.more || 0) + Math.max(0, items.length - this.items.length);
    if (!items.length) { el.appendChild(h(this.doc, 'div', { cls: 'di-empty', text: o.empty || 'Nothing matches', attrs: { role: 'presentation' } })); }
    if (more > 0) { el.appendChild(h(this.doc, 'div', { cls: 'di-more', text: more + ' more: refine the search', attrs: { role: 'presentation' } })); }
    if (this.active >= this.items.length) { this.active = this.items.length - 1; }
  }

  /**
   * Makes an option the active one (scrolled into view) and tells the focused element.
   * @param {number} i
   * @param {Element} [owner] the element with the focus (its aria-activedescendant)
   * @returns {void}
   */
  setActive(i, owner) {
    this.active = Math.max(-1, Math.min(this.items.length - 1, i));
    Array.prototype.forEach.call(this.el.querySelectorAll('.di-option'), (o) => o.classList.toggle('di-active', +o.dataset.i === this.active));
    const cur = this.active >= 0 ? this.doc.getElementById(this.id + '-' + this.active) : null;
    if (cur && cur.scrollIntoView) { cur.scrollIntoView({ block: 'nearest' }); }
    if (owner) { if (cur) { owner.setAttribute('aria-activedescendant', cur.id); } else { owner.removeAttribute('aria-activedescendant'); } }
  }

  /**
   * A key from the element with the focus: moves the active option or picks it. Returns whether the key was the list's.
   * @param {KeyboardEvent} e
   * @param {Element} owner
   * @returns {boolean}
   */
  key(e, owner) {
    const n = this.items.length, k = e.key;
    if (!n) { return false; }
    const page = 8;
    let next = null;
    if (k === 'ArrowDown') { next = this.active + 1 >= n ? 0 : this.active + 1; }
    else if (k === 'ArrowUp') { next = this.active <= 0 ? n - 1 : this.active - 1; }
    else if (k === 'PageDown') { next = Math.min(n - 1, this.active + page); }
    else if (k === 'PageUp') { next = Math.max(0, this.active - page); }
    else if (k === 'Home' && e.ctrlKey) { next = 0; }
    else if (k === 'End' && e.ctrlKey) { next = n - 1; }
    if (next !== null) { e.preventDefault(); this.setActive(next, owner); return true; }
    if ((k === 'Enter' || (k === ' ' && this.o.multiple && e.target === owner && owner.tagName !== 'INPUT')) && this.active >= 0) {
      const it = this.items[this.active];
      if (it && !it.disabled) { e.preventDefault(); this.o.onPick(it, e); return true; }
    }
    return false;
  }
}

/**
 * Options as the components take them: strings, numbers, or {value, label, detail, disabled, group}.
 * @param {Array<(string|number|Object)>} list
 * @returns {Array<{value: *, label: string, detail: string, disabled: boolean, group: string}>}
 */
export function normalizeOptions(list) {
  return (list || []).map((x) => (x !== null && typeof x === 'object' ? { value: x.value !== undefined ? x.value : x.label, label: String(x.label !== undefined ? x.label : x.value), detail: x.detail || '',
    disabled: !!x.disabled, group: x.group || '' } : { value: x, label: String(x), detail: '', disabled: false, group: '' }));
}

/**
 * Options that match a search, best first: the label starting with it, then a word of it starting with it, then containing it; the
 * detail last. Every word of the search must match.
 * @param {Array<{label: string, detail: string}>} items
 * @param {string} q
 * @returns {Array<Object>}
 */
export function searchOptions(items, q) {
  const words = String(q || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) { return items.slice(); }
  const scored = [];
  items.forEach((it, i) => {
    const l = it.label.toLowerCase(), d = (it.detail || '').toLowerCase();
    let score = 0;
    for (const w of words) {
      if (l.indexOf(w) === 0) { score += 4; } else if (new RegExp('[\\s\\-_./(]' + w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).test(l)) { score += 3; } else if (l.indexOf(w) >= 0) { score += 2; } else if (d.indexOf(w) >= 0) { score += 1; } else { return; }
    }
    scored.push({ it: it, score: score, i: i });
  });
  return scored.sort((a, b) => b.score - a.score || a.i - b.i).map((x) => x.it);
}
