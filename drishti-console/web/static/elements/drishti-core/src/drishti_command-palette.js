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
/* The command palette: Ctrl+K (Cmd+K on a Mac) opens one filterable list of what a page can do: commands, pages and words the page
   registers. Fuzzy search (the letters in order, runs and word starts scoring higher), keyboard first (Up, Down, Home, End, Enter, Escape),
   the ARIA combobox pattern (a search box that owns a listbox, aria-activedescendant on the active option), a count announced to a screen
   reader, and on a phone a sheet over the whole width with 44 px rows. The page's commands come from register() or from the console's
   keymap registry (keymapCommands: every key a page registered, by its description). The look is css/drishti_palette.css (tokens only). */
import { h, safeUrl } from './drishti_dom.js';
import { announce } from './drishti_a11y.js';

/**
 * @typedef {Object} PaletteCommand
 * @property {string} id
 * @property {string} title what the list shows and the search reads ("Go to the VaR page")
 * @property {string} [group='Commands'] Commands, Pages, Words or the page's own
 * @property {string} [detail] a quieter second line
 * @property {string[]} [words] more words that find it (synonyms)
 * @property {string[]} [keys] the key chords to show beside it ("Ctrl+S")
 * @property {string} [href] a page: opened with the address (a safe one only) when there is no run
 * @property {function(PaletteCommand): void} [run] what it does
 */

/**
 * How well a query's letters follow a text, or -1 when they do not occur in order. Runs of letters and letters at the start of a word
 * score higher, an earlier match scores higher.
 * @param {string} query
 * @param {string} text
 * @returns {number}
 */
export function fuzzyScore(query, text) {
  const q = String(query).toLowerCase().replace(/\s+/g, ''), t = String(text).toLowerCase();
  if (!q) { return 0; }
  let ti = 0, score = 0, run = 0;
  for (let qi = 0; qi < q.length; qi++) {
    const at = t.indexOf(q[qi], ti);
    if (at < 0) { return -1; }
    const start = at === 0 || ' -_/:.'.indexOf(t[at - 1]) >= 0;
    run = at === ti && qi > 0 ? run + 1 : 0;
    score += 1 + (start ? 6 : 0) + run * 4 - Math.min(at - ti, 8) * 0.25;
    ti = at + 1;
  }
  return score + (t.indexOf(q) >= 0 ? 20 : 0) - t.length * 0.02;
}

/**
 * The commands that match a query, best first (title, then synonyms and the detail count less); every command when the query is empty.
 * @param {PaletteCommand[]} commands
 * @param {string} query
 * @param {number} [limit=50]
 * @returns {PaletteCommand[]}
 */
export function rankCommands(commands, query, limit) {
  const q = String(query || '').trim();
  if (!q) { return commands.slice(0, limit || 50); }
  const scored = [];
  commands.forEach((c, i) => {
    const s = Math.max(fuzzyScore(q, c.title), (c.words || []).reduce((m, w) => Math.max(m, fuzzyScore(q, w) - 4), -1), fuzzyScore(q, c.detail || '') - 8);
    if (s >= 0) { scored.push({ c: c, s: s, i: i }); }
  });
  return scored.sort((a, b) => b.s - a.s || a.i - b.i).slice(0, limit || 50).map((x) => x.c);
}

/**
 * The commands of the console's keymap registry (Drishti.keys: register/list): each registered key with a description, run by firing
 * its chord at the document, so the registry's own scope and conditions apply.
 * @param {{list: function(): Array<{id: string, keys: string[], description: string, group: string}>}} registry
 * @param {Document} doc
 * @returns {PaletteCommand[]}
 */
export function keymapCommands(registry, doc) {
  return registry.list().filter((r) => r.description && r.keys && r.keys.length && r.id.indexOf('palette') < 0).map((r) => ({
    id: 'key:' + r.id, title: r.description, group: r.group || 'Keys', keys: r.keys.slice(0, 2),
    run: () => {
      const parts = r.keys[0].split('+'), key = parts.pop();
      const win = doc.defaultView;
      doc.dispatchEvent(new win.KeyboardEvent('keydown', { key: key.length === 1 ? key.toLowerCase() : key, ctrlKey: parts.indexOf('Ctrl') >= 0, altKey: parts.indexOf('Alt') >= 0,
        shiftKey: parts.indexOf('Shift') >= 0, metaKey: parts.indexOf('Meta') >= 0, bubbles: true, cancelable: true }));
    }
  }));
}

let seq = 0;

/** The palette: a dialog with a search box and a list, over a page's registered commands. */
export class CommandPalette {
  /**
   * @param {Document} doc
   * @param {{placeholder: string, label: string, empty: string, limit: number, source: function(): PaletteCommand[], onRun: function(PaletteCommand): void}} [o]
   *   source: more commands asked for each time it opens (the page's current state)
   */
  constructor(doc, o) {
    this.doc = doc;
    this.o = Object.assign({ placeholder: 'Type a command, a page or a word', label: 'Command palette', empty: 'Nothing matches. Try fewer letters.', limit: 50 }, o || {});
    this.commands = [];
    this.shown = [];
    this.active = 0;
    this.id = 'drishti-palette-' + (++seq);
    this.input = h(doc, 'input', { cls: 'drishti-palette-input', attrs: { type: 'search', role: 'combobox', 'aria-expanded': 'true', 'aria-controls': this.id + '-list',
      'aria-autocomplete': 'list', 'aria-label': this.o.label, placeholder: this.o.placeholder, autocomplete: 'off', spellcheck: 'false', enterkeyhint: 'go' },
      on: { input: () => this.render(), keydown: (e) => this.key(e) } });
    this.list = h(doc, 'ul', { cls: 'drishti-palette-list', attrs: { role: 'listbox', id: this.id + '-list', 'aria-label': this.o.label + ' results' } });
    this.dialog = h(doc, 'dialog', { cls: 'drishti-palette', attrs: { role: 'dialog', 'aria-label': this.o.label },
      on: { close: () => { if (this.before && this.before.focus) { this.before.focus(); } }, click: (e) => { if (e.target === this.dialog) { this.close(); } } } },
      [this.input, this.list]);
    doc.body.appendChild(this.dialog);
  }

  /**
   * Registers commands; the function returned takes them off.
   * @param {(PaletteCommand|PaletteCommand[])} commands
   * @returns {function(): void}
   */
  register(commands) {
    const list = Array.isArray(commands) ? commands : [commands];
    list.forEach((c) => {
      if (!c || !c.id || !c.title) { throw new Error('palette: a command needs an id and a title'); }
      if (this.commands.some((x) => x.id === c.id)) { throw new Error('palette: the command ' + c.id + ' is registered already'); }
    });
    this.commands.push(...list);
    return () => { this.commands = this.commands.filter((c) => list.indexOf(c) < 0); };
  }

  /**
   * Whether it is open.
   * @returns {boolean}
   */
  isOpen() { return !!this.dialog.open; }

  /**
   * Opens it with an empty search (what had the focus gets it back on close).
   * @returns {void}
   */
  open() {
    if (this.dialog.open) { return; }
    this.before = this.doc.activeElement;
    this.input.value = '';
    this.all = this.commands.concat(this.o.source ? this.o.source() : []);
    this.dialog.showModal();
    this.render();
    this.input.focus();
  }

  /**
   * Closes it; focus goes back to what had it.
   * @returns {void}
   */
  close() { if (this.dialog.open) { this.dialog.close(); } }

  /**
   * Opens it when closed, closes it when open.
   * @returns {void}
   */
  toggle() { if (this.isOpen()) { this.close(); } else { this.open(); } }

  /**
   * Closes it and takes it out of the page.
   * @returns {void}
   */
  destroy() { this.close(); this.dialog.remove(); }

  /** @private the list for the query now in the box */
  render() {
    const doc = this.doc, q = this.input.value;
    this.shown = rankCommands(this.all || this.commands, q, this.o.limit);
    this.active = 0;
    while (this.list.firstChild) { this.list.removeChild(this.list.firstChild); }
    let group = null;
    this.shown.forEach((c, i) => {
      if (!q.trim() && (c.group || 'Commands') !== group) {
        group = c.group || 'Commands';
        this.list.appendChild(h(doc, 'li', { cls: 'drishti-palette-group', text: group, attrs: { role: 'presentation' } }));
      }
      const li = h(doc, 'li', { cls: 'drishti-palette-item', attrs: { role: 'option', id: this.id + '-o' + i, 'aria-selected': 'false' }, data: { i: String(i) },
        on: { click: () => this.run(c), mousemove: () => this.go(i) } }, [h(doc, 'span', { cls: 'drishti-palette-title', text: c.title }),
        c.detail ? h(doc, 'span', { cls: 'drishti-palette-detail', text: c.detail }) : null,
        q.trim() && c.group ? h(doc, 'span', { cls: 'drishti-palette-tag', text: c.group }) : null,
        (c.keys || []).length ? h(doc, 'span', { cls: 'drishti-palette-keys' }, c.keys.map((k) => h(doc, 'kbd', { text: k }))) : null]);
      this.list.appendChild(li);
    });
    if (!this.shown.length) { this.list.appendChild(h(doc, 'li', { cls: 'drishti-palette-empty', text: this.o.empty, attrs: { role: 'presentation' } })); }
    this.go(0);
    announce(this.shown.length ? this.shown.length + (this.shown.length === 1 ? ' result' : ' results') : 'No results', { document: doc });
  }

  /** @private makes option i the active one */
  go(i) {
    const items = this.list.querySelectorAll('[role="option"]');
    if (!items.length) { this.input.removeAttribute('aria-activedescendant'); return; }
    this.active = (i + items.length) % items.length;
    items.forEach((el, k) => { el.setAttribute('aria-selected', k === this.active ? 'true' : 'false'); });
    const el = items[this.active];
    this.input.setAttribute('aria-activedescendant', el.id);
    if (el.scrollIntoView) { el.scrollIntoView({ block: 'nearest' }); }
  }

  /** @private the keys of the search box */
  key(e) {
    const k = e.key;
    if (k === 'ArrowDown') { e.preventDefault(); this.go(this.active + 1); }
    else if (k === 'ArrowUp') { e.preventDefault(); this.go(this.active - 1); }
    else if (k === 'PageDown') { e.preventDefault(); this.go(Math.min(this.shown.length - 1, this.active + 5)); }
    else if (k === 'PageUp') { e.preventDefault(); this.go(Math.max(0, this.active - 5)); }
    else if (k === 'Home' && e.ctrlKey) { e.preventDefault(); this.go(0); }
    else if (k === 'End' && e.ctrlKey) { e.preventDefault(); this.go(this.shown.length - 1); }
    else if (k === 'Enter') { e.preventDefault(); if (this.shown[this.active]) { this.run(this.shown[this.active]); } }
  }

  /** @private closes and does it */
  run(c) {
    this.close();
    if (this.o.onRun) { this.o.onRun(c); }
    if (c.run) { c.run(c); }
    else if (c.href) { const u = safeUrl(c.href); if (u) { this.doc.defaultView.location.assign(u); } }
  }
}

/**
 * Binds Ctrl+K and Cmd+K to a palette: through the console's keymap registry when it is given (so the "?" list shows it and a clash is
 * found), else a keydown on the window. Returns a function that takes the binding off.
 * @param {Window} win
 * @param {CommandPalette} palette
 * @param {{register: function(Object): function(): void}} [registry]
 * @returns {function(): void}
 */
export function bindPaletteKey(win, palette, registry) {
  if (registry && registry.register) {
    return registry.register({ id: 'palette.open', keys: ['Ctrl+K', 'Meta+K'], scope: 'global', group: 'Help', description: 'The command palette', capture: true, typing: true,
      run: (e) => { e.preventDefault(); palette.toggle(); } });
  }
  const f = (e) => { if ((e.ctrlKey || e.metaKey) && !e.altKey && (e.key === 'k' || e.key === 'K')) { e.preventDefault(); palette.toggle(); } };
  win.addEventListener('keydown', f, true);
  return () => win.removeEventListener('keydown', f, true);
}
