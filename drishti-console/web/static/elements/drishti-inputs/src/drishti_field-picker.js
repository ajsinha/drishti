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
/* The field picker: a panel under a chip (a pivot's Rows, Columns, Filters or Values) with a search field and the fields in groups
   (Hierarchies, Trade & position, Measures by folder, More fields collapsed), each group with its count. An item may have children (a
   hierarchy's levels) shown when it is open, each with its check; an item that cannot be used is shown off, its reason beside it. The focus
   stays in the search field and the active item follows the keys (aria-activedescendant): Up and Down move, Right opens, Left closes,
   Space checks, Enter uses (and closes), Escape closes, a letter types into the search. The picker draws; the host says what an item is
   and what a pick does (drishti-grid's pivot field pickers). */
import { announce, h } from '../../drishti-core/src/drishti_index.js';
import { Popover, uid } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} PickerItem
 * @property {string} id
 * @property {string} label
 * @property {string} [detail] muted words after the label (a hierarchy's levels: Division › Desk › Book)
 * @property {string} [icon] a mark before it (⋔ a hierarchy, Σ a measure, Aa a text field)
 * @property {string} [note] words on the right (in Rows, above the scope)
 * @property {boolean} [checked] its check (undefined: no check)
 * @property {boolean} [disabled] shown off; why says the reason
 * @property {string} [why]
 * @property {PickerItem[]} [children] shown when it is open
 * @property {boolean} [open] its children shown at first
 */

/**
 * @typedef {Object} PickerGroup
 * @property {string} id
 * @property {string} label
 * @property {PickerItem[]} items
 * @property {boolean} [collapsed] closed at first (More fields)
 * @property {boolean} [columns] two columns of items (short labels)
 */

/**
 * A field picker.
 */
export class FieldPicker {
  /**
   * @param {Document} doc
   * @param {{label: string, title: string, hint: string, groups: function(): PickerGroup[], onPick: function(PickerItem, ?PickerItem, {close: boolean}): void,
   *   link: {label: string, run: function(): void}, theme: Element, keys: string}} o groups: asked again after each pick; onPick: the item (and
   *   the child picked, else null); link: a button in the foot (Customize ›); keys: the foot's words about the keys
   */
  constructor(doc, o) {
    this.doc = doc;
    this.o = Object.assign({}, o);
    this.id = uid('fp');
    this.pop = new Popover(doc, { cls: 'di-field-picker', label: this.o.label || 'Fields', theme: this.o.theme, onClose: () => { if (this.o.onClose) { this.o.onClose(); } } });
    this.query = '';
    this.active = null;
    this.opened = new Set();
    this.closedGroups = new Set();
    this.rows = [];
  }

  /**
   * Whether it is open.
   * @returns {boolean}
   */
  isOpen() { return this.pop.isOpen(); }

  /**
   * Opens the picker under an anchor.
   * @param {Element} anchor
   * @returns {HTMLElement} the panel
   */
  open(anchor) {
    const doc = this.doc;
    this.query = '';
    this.active = null;
    this.opened = new Set();
    this.closedGroups = new Set();
    this.groups().forEach((g) => {
      if (g.collapsed) { this.closedGroups.add(g.id); }
      g.items.forEach((it) => { if (it.open) { this.opened.add(it.id); } });
    });
    this.search = h2(doc, 'input', { cls: 'di-field di-search', type: 'search', placeholder: 'Search fields…', 'aria-label': 'Search fields', 'aria-controls': this.id, autocomplete: 'off' });
    this.list = h2(doc, 'div', { cls: 'di-fp-list', role: 'tree', id: this.id, 'aria-label': this.o.label || 'Fields' });
    const head = h2(doc, 'div', { cls: 'di-fp-head' }, [h2(doc, 'b', { text: this.o.title || 'Fields' }), this.o.hint ? h2(doc, 'span', { cls: 'di-fp-hint', text: this.o.hint }) : null]);
    const foot = h2(doc, 'div', { cls: 'di-fp-foot' }, [h2(doc, 'span', { cls: 'di-fp-keys', text: this.o.keys || '↑↓ move · Space check · Enter use · Esc close' })]);
    if (this.o.link) {
      foot.append(h2(doc, 'button', { cls: 'di-btn di-fp-link', type: 'button', text: this.o.link.label, 'data-act': 'link' }));
    }
    const box = h2(doc, 'div', { cls: 'di-fp' }, [head, this.search, this.list, foot]);
    this.search.addEventListener('input', () => { this.query = this.search.value; this.active = null; this.render(); });
    this.search.addEventListener('keydown', (e) => this.key(e));
    this.list.addEventListener('mousedown', (e) => e.preventDefault());
    this.list.addEventListener('click', (e) => this.click(e));
    foot.addEventListener('click', (e) => { if (e.target.closest('[data-act="link"]')) { this.close(); this.o.link.run(); } });
    const el = this.pop.open(anchor, box);
    el.classList.add('di-fp-pop');
    this.render();
    this.search.focus();
    return el;
  }

  /**
   * Closes it.
   * @returns {void}
   */
  close() { this.pop.close(true); }

  /**
   * Draws the items again (after a pick changed them), keeping the search and the active item.
   * @returns {void}
   */
  refresh() { if (this.isOpen()) { this.render(); } }

  /** @private the host's groups */
  groups() { return (this.o.groups ? this.o.groups() : []).filter((g) => g && g.items); }

  /** @private whether an item matches the search (its label, detail or a child's label) */
  matches(it, words) {
    if (!words.length) { return true; }
    const hay = (it.label + ' ' + (it.detail || '') + ' ' + (it.children || []).map((c) => c.label).join(' ')).toLowerCase();
    return words.every((w) => hay.indexOf(w) >= 0);
  }

  /** @private draws the groups and items */
  render() {
    const doc = this.doc, words = this.query.toLowerCase().split(/\s+/).filter(Boolean);
    this.list.textContent = '';
    this.rows = [];
    let shown = 0;
    this.groups().forEach((g) => {
      const items = g.items.filter((it) => this.matches(it, words));
      if (!items.length) { return; }
      const closed = !words.length && this.closedGroups.has(g.id);
      const head = h2(doc, 'div', { cls: 'di-fp-group', role: 'presentation', 'data-group': g.id }, [
        h2(doc, 'span', { cls: 'di-fp-caret', text: closed ? '▸' : '▾', 'aria-hidden': 'true' }), h2(doc, 'span', { text: g.label }), h2(doc, 'span', { cls: 'di-fp-count', text: String(items.length) })]);
      this.list.appendChild(head);
      if (closed) {
        this.rows.push({ group: g, el: head });
        head.id = this.id + '-g-' + g.id;
        head.setAttribute('role', 'treeitem');
        head.setAttribute('aria-expanded', 'false');
        return;
      }
      const box = h2(doc, 'div', { cls: 'di-fp-items' + (g.columns ? ' di-fp-cols' : ''), role: 'group', 'aria-label': g.label });
      items.forEach((it) => {
        const open = (it.children && it.children.length) && (this.opened.has(it.id) || (words.length > 0 && it.children.some((c) => this.matches(c, words))));
        box.appendChild(this.row(it, null, open));
        shown++;
        if (open) { it.children.forEach((c) => { box.appendChild(this.row(c, it, false)); }); }
      });
      this.list.appendChild(box);
    });
    if (!shown && !this.rows.length) { this.list.appendChild(h2(doc, 'div', { cls: 'di-empty', text: 'No field matches "' + this.query + '"' })); }
    if (this.active === null || !this.rows.some((r) => r.key === this.active)) { const first = this.rows.find((r) => !r.disabled); this.active = first ? first.key : null; }
    this.mark();
  }

  /** @private one item's row */
  row(it, parent, open) {
    const doc = this.doc, key = (parent ? parent.id + '/' : '') + it.id;
    const kids = !parent && it.children && it.children.length;
    const el = h2(doc, 'div', { cls: 'di-fp-item' + (parent ? ' di-fp-child' : '') + (it.disabled ? ' di-disabled' : '') + (it.checked ? ' di-on' : ''),
      role: 'treeitem', id: this.id + '-' + this.rows.length, 'aria-level': parent ? '2' : '1', 'aria-checked': it.checked === undefined ? null : String(!!it.checked),
      'aria-expanded': kids ? String(!!open) : null, 'aria-disabled': it.disabled ? 'true' : null, title: it.disabled && it.why ? it.why : null, 'data-key': key }, [
      kids ? h2(doc, 'span', { cls: 'di-fp-caret', text: open ? '▾' : '▸', 'data-act': 'toggle', 'aria-hidden': 'true' }) : h2(doc, 'span', { cls: 'di-fp-caret' }),
      it.checked !== undefined ? h2(doc, 'span', { cls: 'di-tick', text: it.checked ? '☑' : '☐', 'aria-hidden': 'true' }) : null,
      it.icon ? h2(doc, 'span', { cls: 'di-fp-icon', text: it.icon, 'aria-hidden': 'true' }) : null,
      h2(doc, 'span', { cls: 'di-fp-label', text: it.label }),
      it.detail ? h2(doc, 'span', { cls: 'di-fp-detail', text: it.detail }) : null,
      it.note || (it.disabled && it.why) ? h2(doc, 'span', { cls: 'di-fp-note', text: it.note || it.why }) : null]);
    this.rows.push({ key: key, item: it, parent: parent, el: el, disabled: !!it.disabled, kids: !!kids, open: !!open });
    return el;
  }

  /** @private marks the active row */
  mark() {
    this.rows.forEach((r) => r.el.classList.toggle('di-active', r.key === this.active));
    const cur = this.rows.find((r) => r.key === this.active);
    if (cur) { this.search.setAttribute('aria-activedescendant', cur.el.id); if (cur.el.scrollIntoView) { cur.el.scrollIntoView({ block: 'nearest' }); } }
    else { this.search.removeAttribute('aria-activedescendant'); }
  }

  /** @private the keys (the focus stays in the search field) */
  key(e) {
    const list = this.rows.filter((r) => !r.disabled || r.group), i = list.findIndex((r) => r.key === this.active), cur = list[i];
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!list.length) { return; }
      const n = e.key === 'ArrowDown' ? (i + 1) % list.length : (i - 1 + list.length) % list.length;
      this.active = list[n].key;
      this.mark();
    } else if (e.key === 'ArrowRight' && cur && (cur.kids || cur.group) && !this.search.value.length) {
      e.preventDefault();
      if (cur.group) { this.closedGroups.delete(cur.group.id); } else { this.opened.add(cur.item.id); }
      this.render();
    } else if (e.key === 'ArrowLeft' && cur && !this.search.value.length) {
      e.preventDefault();
      if (cur.parent) { this.active = cur.parent.id; } else if (cur.kids) { this.opened.delete(cur.item.id); }
      this.render();
    } else if ((e.key === ' ' && !this.search.value.length) || e.key === 'Enter') {
      if (!cur) { return; }
      e.preventDefault();
      if (cur.group) { this.closedGroups.delete(cur.group.id); this.render(); return; }
      this.pick(cur, e.key === 'Enter');
    }
  }

  /** @private a click: the caret opens or closes, a group heading opens or closes it, the rest picks */
  click(e) {
    const g = e.target.closest('.di-fp-group');
    if (g) {
      const id = g.getAttribute('data-group');
      if (this.closedGroups.has(id)) { this.closedGroups.delete(id); } else { this.closedGroups.add(id); }
      this.render();
      return;
    }
    const el = e.target.closest('.di-fp-item');
    if (!el) { return; }
    const r = this.rows.find((x) => x.el === el);
    if (!r || r.disabled) { return; }
    this.active = r.key;
    if (e.target.closest('[data-act="toggle"]')) {
      if (this.opened.has(r.item.id)) { this.opened.delete(r.item.id); } else { this.opened.add(r.item.id); }
      this.render();
      return;
    }
    this.pick(r, false);
  }

  /** @private picks a row: the host changes the wells; the picker draws again (or closes on Enter) */
  pick(r, close) {
    if (r.disabled) { if (r.item.why) { announce(r.item.why); } return; }
    this.o.onPick(r.parent || r.item, r.parent ? r.item : null, { close: close });
    if (close) { this.close(); return; }
    this.render();
  }
}

/** @private an element: drishti-core's h with the attributes given flat ({cls, text, ...attributes}) */
function h2(doc, tag, a, children) {
  const attrs = Object.assign({}, a), cls = attrs.cls, text = attrs.text;
  delete attrs.cls;
  delete attrs.text;
  return h(doc, tag, { cls: cls, text: text, attrs: attrs }, children);
}
