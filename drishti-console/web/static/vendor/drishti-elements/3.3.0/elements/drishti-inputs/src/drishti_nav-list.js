/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* A searchable list under headings, which may nest into a tree: the navigator of a screen (a catalogue of kinds, a palette of blocks,
   a list of documents by folder). Items with the same group sit under that group's heading; items with children expand and collapse.
   A search field filters by label, detail and group (drishti-inputs' searchOptions: every word must match; a match keeps its parents
   and opens them); the original order stays. Keyboard: the focus stays in the search field (or on the list when there is none) and
   the arrows move the active row (aria-activedescendant); Right and Left open and close a branch (in the search field only while it is empty), Enter picks, Home and End go to the
   first and last (on the list). A printable key typed on the list goes to the search field. Roles: tree, treeitem, group. */
import { InputBase, normalizeOptions, searchOptions, uid } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} NavItem
 * @property {string} id
 * @property {string} label
 * @property {string} [detail] a second line of words, searched too
 * @property {string} [group] the heading it sits under (top-level items)
 * @property {NavItem[]} [children] makes it a branch
 * @property {boolean} [disabled]
 */

/**
 * @typedef {Object} NavListOptions
 * @property {NavItem[]} items
 * @property {string} [value] the picked item's id
 * @property {boolean} [search=true] show the search field
 * @property {string} [placeholder] the search field's words
 * @property {string} [empty] the words when nothing matches
 * @property {string} [label] names the list (aria-label)
 * @property {boolean} [expanded=false] branches start open
 */

/**
 * A navigator. Events: change {value, item}, expand {id, open}, search {query, count}.
 */
export class NavList extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {NavListOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-navlist');
    const o = this.o;
    this.id = uid('nav');
    this.value = o.value === undefined ? null : o.value;
    this.open = new Set();
    this.byItem = new Map();
    this.flat = [];                                          // every item with its parent and depth
    this.index(o.items || [], null, 0);
    if (o.expanded) { this.flat.forEach((n) => { if (n.item.children && n.item.children.length) { this.open.add(n.item.id); } }); }
    this.active = -1;
    this.rows = [];
    this.query = this.el('input', { cls: 'di-field di-nav-search', attrs: { type: 'search', placeholder: o.placeholder || 'Search', 'aria-label': (o.label || 'List') + ': search',
      role: 'searchbox', autocomplete: 'off', spellcheck: 'false', 'aria-controls': this.id } });
    this.tree = this.el('div', { cls: 'di-nav-tree', attrs: { role: 'tree', id: this.id, tabindex: o.search === false ? '0' : '-1', 'aria-label': o.label || 'List' } });
    if (o.search !== false) { host.appendChild(this.query); }
    host.appendChild(this.tree);
    this.keyed = o.search === false ? this.tree : this.query;
    this.query.addEventListener('input', () => { this.paint(); this.emit('search', { query: this.query.value, count: this.rows.filter((r) => !r.heading).length }); });
    this.tree.addEventListener('mousedown', (e) => { if (e.target.closest('.di-nav-row')) { e.preventDefault(); this.keyed.focus(); } });
    this.tree.addEventListener('click', (e) => this.onClick(e));
    [this.query, this.tree].forEach((el) => el.addEventListener('keydown', (e) => this.onKey(e)));
    this.paint();
  }

  /** @private flattens the items, keeping each one's parent, depth and top-level group */
  index(items, parent, depth) {
    items.forEach((item) => {
      const n = { item: item, parent: parent, depth: depth, group: parent ? parent.group : item.group || '' };
      this.flat.push(n);
      this.byItem.set(item, n);
      if (item.children) { this.index(item.children, n, depth + 1); }
    });
  }

  /** @private the nodes a search keeps: matches and their parents */
  matching() {
    const q = this.query.value.trim();
    if (!q) { return null; }
    const opts = this.flat.map((n) => Object.assign(normalizeOptions([{ value: n.item.id, label: n.item.label, detail: (n.item.detail || '') + ' ' + n.group }])[0], { n: n }));
    const keep = new Set();
    searchOptions(opts, q).forEach((x) => { for (let n = x.n; n; n = n.parent) { keep.add(n); } });
    return keep;
  }

  /** @private draws the visible rows: headings, items, the branches open */
  paint() {
    const keep = this.matching(), t = this.tree, d = this.doc;
    t.textContent = '';
    this.rows = [];
    let group = null, box = t;
    const walk = (n) => {
      if (keep && !keep.has(n)) { return; }
      const kids = n.item.children || [], branch = kids.length > 0, open = branch && (keep ? true : this.open.has(n.item.id));
      const i = this.rows.length, sel = n.item.id === this.value;
      const row = this.el('div', { cls: 'di-nav-row' + (sel ? ' di-selected' : '') + (n.item.disabled ? ' di-disabled' : ''), data: { id: n.item.id },
        attrs: { role: 'treeitem', id: this.id + '-' + i, 'aria-level': String(n.depth + 1), 'aria-selected': String(sel), 'aria-expanded': branch ? String(open) : null, 'aria-disabled': n.item.disabled ? 'true' : null } });
      row.style.paddingLeft = (8 + n.depth * 16) + 'px';
      row.appendChild(this.el('span', { cls: 'di-nav-twist', text: branch ? (open ? '▾' : '▸') : '', attrs: { 'aria-hidden': 'true' }, data: branch ? { twist: '1' } : {} }));
      row.appendChild(this.el('span', { cls: 'di-nav-label', text: n.item.label }));
      if (n.item.detail) { row.appendChild(this.el('span', { cls: 'di-nav-detail', text: n.item.detail })); }
      this.rows.push({ n: n, el: row });
      box.appendChild(row);
      if (open) { kids.forEach((k) => walk(this.byItem.get(k))); }
    };
    this.flat.filter((n) => n.depth === 0).forEach((n) => {
      if (keep && !keep.has(n)) { return; }
      if (n.group !== group) {
        group = n.group;
        if (group) {
          const hid = this.id + '-g' + t.children.length;
          const head = this.el('div', { cls: 'di-nav-group', text: group, attrs: { id: hid } });
          box = this.el('div', { cls: 'di-nav-section', attrs: { role: 'group', 'aria-labelledby': hid } });
          t.append(head, box);
        } else { box = t; }
      }
      walk(n);
    });
    if (!this.rows.length) { t.appendChild(this.el('div', { cls: 'di-nav-empty', text: this.o.empty || 'Nothing matches', attrs: { role: 'presentation' } })); }
    const keepActive = this.rows.findIndex((r) => r.n.item.id === this.value);
    this.active = this.rows.length ? Math.max(0, Math.min(this.active < 0 ? (keepActive >= 0 ? keepActive : 0) : this.active, this.rows.length - 1)) : -1;
    this.markActive(false);
  }

  /** @private marks the active row and tells the focused element */
  markActive(scroll) {
    this.rows.forEach((r, i) => r.el.classList.toggle('di-active', i === this.active));
    const cur = this.rows[this.active];
    [this.query, this.tree].forEach((el) => { if (cur) { el.setAttribute('aria-activedescendant', cur.el.id); } else { el.removeAttribute('aria-activedescendant'); } });
    if (cur && scroll) {                                      // the list's own scroll only: the page never moves
      const t = this.tree, top = cur.el.offsetTop, bottom = top + cur.el.offsetHeight;
      if (top < t.scrollTop) { t.scrollTop = top; } else if (bottom > t.scrollTop + t.clientHeight) { t.scrollTop = bottom - t.clientHeight; }
    }
  }

  /** @private */
  onClick(e) {
    const el = e.target.closest('.di-nav-row');
    if (!el) { return; }
    const i = this.rows.findIndex((r) => r.el === el);
    if (i < 0) { return; }
    this.active = i;
    if (e.target.closest('[data-twist]')) { this.toggle(this.rows[i].n.item.id); return; }
    this.pick(i);
  }

  /** @private */
  pick(i) {
    const r = this.rows[i];
    if (!r || r.n.item.disabled) { return; }
    this.value = r.n.item.id;
    this.paint();
    this.emit('change', { value: this.value, item: r.n.item });
  }

  /** @private */
  onKey(e) {
    const n = this.rows.length, cur = this.rows[this.active], inSearch = e.target === this.query;
    let handled = true;
    if (e.key === 'ArrowDown') { this.active = n ? Math.min(n - 1, this.active + 1) : -1; this.markActive(true); }
    else if (e.key === 'ArrowUp') { this.active = n ? Math.max(0, this.active - 1) : -1; this.markActive(true); }
    else if (e.key === 'Home' && !inSearch) { this.active = n ? 0 : -1; this.markActive(true); }
    else if (e.key === 'End' && !inSearch) { this.active = n ? n - 1 : -1; this.markActive(true); }
    else if (e.key === 'ArrowRight' && cur && (!inSearch || !this.query.value)) { if (cur.n.item.children && cur.n.item.children.length) { this.toggle(cur.n.item.id, true); } else { handled = false; } }
    else if (e.key === 'ArrowLeft' && cur && (!inSearch || !this.query.value)) {
      if (cur.n.item.children && cur.n.item.children.length && this.open.has(cur.n.item.id)) { this.toggle(cur.n.item.id, false); }
      else if (cur.n.parent) { this.active = this.rows.findIndex((r) => r.n === cur.n.parent); this.markActive(true); } else { handled = false; }
    }
    else if (e.key === 'Enter' && cur) { this.pick(this.active); }
    else if (e.key === 'Escape' && inSearch && this.query.value) { this.query.value = ''; this.paint(); }
    else if (!inSearch && this.o.search !== false && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) { this.query.focus(); handled = false; }
    else { handled = false; }
    if (handled) { e.preventDefault(); }
  }

  /**
   * Opens or closes a branch (no argument: turns it).
   * @param {string} id
   * @param {boolean} [open]
   * @returns {void}
   */
  toggle(id, open) {
    const on = open === undefined ? !this.open.has(id) : open;
    if (on) { this.open.add(id); } else { this.open.delete(id); }
    this.paint();
    this.emit('expand', { id: id, open: on });
  }

  /**
   * The picked item's id.
   * @returns {(string|null)}
   */
  getValue() { return this.value; }

  /**
   * Picks an item by id, without an event (its parents open).
   * @param {(string|null)} id
   * @returns {void}
   */
  setValue(id) {
    this.value = id === undefined ? null : id;
    const n = this.flat.find((f) => f.item.id === id);
    for (let p = n && n.parent; p; p = p.parent) { this.open.add(p.item.id); }
    this.active = -1;
    this.paint();
  }

  /**
   * Replaces the items.
   * @param {NavItem[]} items
   * @returns {void}
   */
  setItems(items) {
    this.o.items = items;
    this.flat = [];
    this.byItem = new Map();
    this.index(items || [], null, 0);
    this.active = -1;
    this.paint();
  }

  /**
   * Sets the search words (the list filters; same as typing).
   * @param {string} q
   * @returns {void}
   */
  setQuery(q) { this.query.value = q || ''; this.paint(); }

  /**
   * Moves the focus to the search field (or the list when there is none).
   * @returns {void}
   */
  focus() { this.keyed.focus(); }
}
