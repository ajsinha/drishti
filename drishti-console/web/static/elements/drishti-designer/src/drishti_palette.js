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
/* The palette: the things a designer can add (visual types, panel kinds, fields, controls), given by the host, in categories, with a
   search. An entry is dragged onto the canvas (through the page's DragHub, or as an HTML5 drag of the host's type for a canvas that
   takes those) or added with a click or Enter (the "add" event: the host puts it where the selection is). The list is a listbox:
   the arrows, Home and End move, typing searches. */
import { EventBus, announce, h } from '../../drishti-core/src/drishti_index.js';

/**
 * An entry of the palette.
 * @typedef {Object} PaletteEntry
 * @property {string} id
 * @property {string} label
 * @property {string} [category] the heading it sits under
 * @property {string} [detail] one line under the label
 * @property {string} [icon] a class for an <i> (the console's icon font)
 * @property {string[]} [keywords] more words the search finds it by
 * @property {boolean} [disabled]
 * @property {string} [why] why it is disabled, in words
 * @property {Object} [payload] what a drag or an add carries (default: {palette: id})
 */

/**
 * Entries matching a search: every word of the query in the label, the detail, the category or the keywords.
 * @param {PaletteEntry[]} entries
 * @param {string} query
 * @returns {PaletteEntry[]}
 */
export function searchPalette(entries, query) {
  const words = String(query || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) { return entries.slice(); }
  return entries.filter((e) => {
    const hay = [e.label, e.detail, e.category].concat(e.keywords || []).join(' ').toLowerCase();
    return words.every((w) => hay.indexOf(w) >= 0);
  });
}

/**
 * The entries in their categories, in the order the categories first appear.
 * @param {PaletteEntry[]} entries
 * @returns {Array<{category: string, entries: PaletteEntry[]}>}
 */
export function paletteGroups(entries) {
  const out = [];
  entries.forEach((e) => {
    const c = e.category || '';
    let g = out.find((x) => x.category === c);
    if (!g) { g = { category: c, entries: [] }; out.push(g); }
    g.entries.push(e);
  });
  return out;
}

/**
 * The palette. Events: add {entry, payload}, search {query, count}.
 */
export class Palette {
  /**
   * @param {HTMLElement} el
   * @param {{entries: PaletteEntry[], hub: import('./drishti_pointer.js').DragHub, dragType: string, label: string, placeholder: string,
   *   search: boolean}} o hub: drags go through it; dragType: also an HTML5 drag of this type (JSON payload); search: a search field
   *   (default true)
   */
  constructor(el, o) {
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ entries: [], search: true, label: 'Add to the design', placeholder: 'Search' }, o);
    this.events = new EventBus();
    this.query = '';
    el.classList.add('dd-palette');
    const doc = this.doc;
    this.input = this.o.search ? h(doc, 'input', { cls: 'dd-palette-search', attrs: { type: 'search', placeholder: this.o.placeholder, 'aria-label': 'Search ' + this.o.label.toLowerCase(), 'data-dd-palette-search': '' } }) : null;
    this.list = h(doc, 'div', { cls: 'dd-palette-list', attrs: { role: 'listbox', 'aria-label': this.o.label, tabindex: '0' } });
    this.empty = h(doc, 'p', { cls: 'dd-palette-empty', text: 'Nothing matches.', attrs: { hidden: true } });
    el.append(...[this.input, this.list, this.empty].filter(Boolean));
    if (this.input) {
      this.input.addEventListener('input', () => this.filter(this.input.value));
      this.input.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown') { e.preventDefault(); this.move(0, true); } else if (e.key === 'Enter') { e.preventDefault(); const f = this.shown()[0]; if (f) { this.add(f); } } });
    }
    this.list.addEventListener('keydown', (e) => this.key(e));
    this.list.addEventListener('click', (e) => { const r = e.target.closest('[data-dd-entry]'); if (r && !this.dragged) { this.add(this.entry(r.getAttribute('data-dd-entry'))); } this.dragged = false; });
    if (this.o.hub) {
      this.o.hub.draggable(this.list, { payload: (e) => { const r = e.target.closest('[data-dd-entry]'), en = r && this.entry(r.getAttribute('data-dd-entry')); return en && !en.disabled ? this.payload(en) : null; },
        label: (p) => (this.entry(p.palette) || {}).label || '' });
    }
    this.render();
  }

  /**
   * Listens to one of its events; answers the function that stops listening.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * New entries (the host's catalogue changed).
   * @param {PaletteEntry[]} entries
   * @returns {void}
   */
  setEntries(entries) { this.o.entries = entries || []; this.render(); }

  /** @private */
  entry(id) { return this.o.entries.find((e) => e.id === id) || null; }

  /** @private */
  payload(en) { return Object.assign({ palette: en.id, label: en.label }, en.payload); }

  /**
   * The entries the search shows.
   * @returns {PaletteEntry[]} the entries the search shows
   */
  shown() { return searchPalette(this.o.entries, this.query); }

  /**
   * Searches.
   * @param {string} q
   * @returns {void}
   */
  filter(q) { this.query = q || ''; if (this.input && this.input.value !== this.query) { this.input.value = this.query; } this.render(); this.events.emit('search', { query: this.query, count: this.shown().length }); }

  /** @private */
  render() {
    const doc = this.doc, shown = this.shown();
    this.list.textContent = '';
    paletteGroups(shown).forEach((g) => {
      const box = h(doc, 'div', { cls: 'dd-palette-group', attrs: { role: 'group', 'aria-label': g.category || this.o.label } });
      if (g.category) { box.appendChild(h(doc, 'div', { cls: 'dd-palette-cat', text: g.category, attrs: { 'aria-hidden': 'true' } })); }
      g.entries.forEach((e) => {
        const opt = h(doc, 'div', { cls: 'dd-palette-entry', attrs: { role: 'option', id: 'dd-pal-' + e.id.replace(/[^\w-]/g, '_'), 'data-dd-entry': e.id, 'aria-disabled': e.disabled ? 'true' : null,
          title: e.disabled ? e.why || '' : e.detail || '', draggable: this.o.dragType && !e.disabled ? 'true' : null } }, [
          e.icon ? h(doc, 'i', { cls: e.icon, attrs: { 'aria-hidden': 'true' } }) : null,
          h(doc, 'span', { cls: 'dd-palette-label', text: e.label }),
          e.detail ? h(doc, 'span', { cls: 'dd-palette-detail', text: e.detail }) : null]);
        if (this.o.dragType && !e.disabled) {
          opt.addEventListener('dragstart', (ev) => { ev.dataTransfer.setData(this.o.dragType, JSON.stringify(this.payload(e))); ev.dataTransfer.effectAllowed = 'copy'; });
        }
        box.appendChild(opt);
      });
      this.list.appendChild(box);
    });
    this.empty.hidden = shown.length > 0;
    this.active = Math.min(this.active || 0, Math.max(0, shown.length - 1));
    this.mark();
  }

  /** @private the active option */
  mark() {
    const opts = [...this.list.querySelectorAll('[data-dd-entry]')];
    opts.forEach((o, i) => o.classList.toggle('dd-active', i === this.active));
    const a = opts[this.active];
    if (a) { this.list.setAttribute('aria-activedescendant', a.id); a.scrollIntoView && a.scrollIntoView({ block: 'nearest' }); } else { this.list.removeAttribute('aria-activedescendant'); }
  }

  /** @private */
  move(to, focus) { const n = this.shown().length; if (!n) { return; } this.active = Math.max(0, Math.min(n - 1, to)); this.mark(); if (focus) { this.list.focus(); } }

  /** @private */
  key(e) {
    const n = this.shown().length;
    if (e.key === 'ArrowDown') { e.preventDefault(); this.move(this.active + 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); if (this.active === 0 && this.input) { this.input.focus(); } else { this.move(this.active - 1); } }
    else if (e.key === 'Home') { e.preventDefault(); this.move(0); } else if (e.key === 'End') { e.preventDefault(); this.move(n - 1); }
    else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); const en = this.shown()[this.active]; if (en) { this.add(en); } }
    else if (this.input && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) { this.input.focus(); }
  }

  /**
   * Adds an entry (as a click or Enter does): the "add" event; a disabled one says why.
   * @param {PaletteEntry} en
   * @returns {void}
   */
  add(en) {
    if (!en) { return; }
    if (en.disabled) { announce(en.why || en.label + ' cannot be added here.'); return; }
    this.events.emit('add', { entry: en, payload: this.payload(en) });
  }
}
