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
/* The shell's parts beside its frame (drishti_shell.js): the header's actions in their four forms (a button, a link, a toggle, a
   choice), the open documents above the editor (a studio that edits several objects in turn: each a tab that goes to its address, with
   a close button) and the dock under the editor (tabs of panes the host fills: problems, a query, a log; a count on a tab; the tab that
   is open pressed again folds the dock away). Every text goes in as text; a host's own attributes are data-* only. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { entryAttrs } from './drishti_palette.js';

/**
 * One of the header's actions.
 * @typedef {Object} ShellAction
 * @property {string} id
 * @property {string} label
 * @property {string} [title] the words on hover
 * @property {boolean} [primary]
 * @property {Object<string, string>} [attrs] the host's data-* attributes (its selectors: data-save)
 * @property {string} [href] a link (View, the item's page): an anchor, never disabled
 * @property {boolean} [toggle] pressed or not (aria-pressed); the "action" event says {id, pressed}
 * @property {boolean} [pressed] a toggle's start
 * @property {Array<{value: string, label: string, disabled: boolean}>} [choices] a choice (a select); the "action" event says {id, value}
 * @property {boolean} [enabled] on from the start (default: off until setActions turns it on)
 * @property {boolean} [hidden]
 */

/**
 * The element of one action; its clicks or changes emit the shell's "action" event.
 * @param {Document} doc
 * @param {ShellAction} a
 * @param {function(Object): void} emit
 * @returns {HTMLElement}
 */
export function actionElement(doc, a, emit) {
  const attrs = Object.assign({ 'data-act': a.id, title: a.title || null, 'data-title': a.title || '', hidden: !!a.hidden }, entryAttrs(a.attrs));
  const cls = 'dd-btn' + (a.primary ? ' dd-primary' : '');
  if (a.href !== undefined) { return h(doc, 'a', { cls: cls + ' dd-link', text: a.label, attrs: Object.assign(attrs, { href: a.href || '#' }) }); }
  if (a.choices) {
    attrs['aria-label'] = a.label;
    const sel = h(doc, 'select', { cls: 'dd-layout-pick dd-choice', attrs: Object.assign(attrs, { disabled: !a.enabled }) }, a.choices.map((c) =>
      h(doc, 'option', { text: c.label, attrs: { value: c.value, disabled: !!c.disabled } })));
    sel.addEventListener('change', () => emit({ id: a.id, value: sel.value }));
    return sel;
  }
  if (a.toggle) { attrs['aria-pressed'] = String(!!a.pressed); }
  const b = h(doc, 'button', { cls, text: a.label, attrs: Object.assign(attrs, { type: 'button', disabled: !a.enabled }) });
  b.addEventListener('click', () => {
    if (a.toggle) { const on = b.getAttribute('aria-pressed') !== 'true'; b.setAttribute('aria-pressed', String(on)); emit({ id: a.id, pressed: on }); return; }
    emit({ id: a.id });
  });
  return b;
}

/**
 * The open documents: a strip of tabs above the editor. setDocs draws them; a tab is a link to the document's address (the current one
 * marked), its close button emits "close" {id}.
 */
export class DocTabs {
  /**
   * @param {Document} doc
   * @param {function(string, Object): void} emit the shell's events
   */
  constructor(doc, emit) {
    this.doc = doc;
    this.emit = emit;
    this.el = h(doc, 'nav', { cls: 'dd-docs', attrs: { role: 'tablist', 'aria-label': 'Open items', 'data-dd-docs': '' } });
  }

  /**
   * @param {Array<{id: string, label: string, href: string, current: boolean, title: string}>} list
   * @returns {void}
   */
  set(list) {
    const doc = this.doc;
    this.el.textContent = '';
    (list || []).forEach((d) => {
      const tab = h(doc, 'a', { cls: 'dd-doc-tab', text: d.label, attrs: { href: d.href, role: 'tab', 'data-dd-doc': d.id, 'aria-selected': String(!!d.current), title: d.title || d.label } });
      const x = h(doc, 'button', { cls: 'dd-doc-x', text: '×', attrs: { type: 'button', 'data-dd-doc-close': d.id, 'aria-label': 'Close ' + d.label } });
      x.addEventListener('click', (e) => { e.preventDefault(); this.emit('close', { id: d.id }); });
      this.el.appendChild(h(doc, 'span', { cls: 'dd-doc' + (d.current ? ' dd-on' : '') }, [tab, x]));
    });
    this.el.hidden = !(list && list.length);
  }
}

/**
 * The dock under the editor: tabs over panes the host fills. A tab opens its pane; the open tab pressed again folds the dock (the tabs
 * stay). Emits "dock" {id} (null: folded) on a person's press.
 */
export class Dock {
  /**
   * @param {Document} doc
   * @param {Array<{id: string, label: string}>} tabs
   * @param {function(string, Object): void} emit the shell's events
   */
  constructor(doc, tabs, emit) {
    this.doc = doc;
    this.emit = emit;
    this.tabs = {};
    this.open = null;
    const list = h(doc, 'div', { cls: 'dd-dock-tabs', attrs: { role: 'tablist', 'aria-label': 'Dock' } });
    this.body = h(doc, 'div', { cls: 'dd-dock-body', attrs: { hidden: true } });
    tabs.forEach((t) => {
      const count = h(doc, 'span', { cls: 'dd-dock-count', attrs: { 'data-dd-dock-count': t.id } });
      const tab = h(doc, 'button', { cls: 'dd-ptab dd-dock-tab', attrs: { type: 'button', role: 'tab', 'aria-selected': 'false', 'data-dd-dock-tab': t.id } }, [t.label + ' ', count]);
      const pane = h(doc, 'div', { cls: 'dd-dock-pane', attrs: { role: 'tabpanel', 'aria-label': t.label, 'data-dd-dock-pane': t.id, hidden: true } });
      tab.addEventListener('click', () => { this.show(this.open === t.id ? null : t.id); this.emit('dock', { id: this.open }); });
      list.appendChild(tab);
      this.body.appendChild(pane);
      this.tabs[t.id] = { tab, pane, count };
    });
    this.el = h(doc, 'section', { cls: 'dd-dock', attrs: { 'aria-label': 'Dock', 'data-dd-dock': '' } }, [list, this.body]);
  }

  /**
   * Opens a pane (null folds the dock).
   * @param {?string} id
   * @returns {void}
   */
  show(id) {
    this.open = id && this.tabs[id] ? id : null;
    Object.keys(this.tabs).forEach((k) => { const on = k === this.open; this.tabs[k].tab.setAttribute('aria-selected', String(on)); this.tabs[k].pane.hidden = !on; });
    this.body.hidden = !this.open;
    this.el.classList.toggle('dd-dock-folded', !this.open);
  }

  /**
   * The count on a tab (0 or null: none); bad marks it as a problem.
   * @param {string} id
   * @param {?number} n
   * @param {boolean} [bad]
   * @returns {void}
   */
  count(id, n, bad) {
    const t = this.tabs[id];
    if (!t) { return; }
    t.count.textContent = n ? String(n) : '';
    t.count.classList.toggle('dd-bad', !!bad);
  }
}
