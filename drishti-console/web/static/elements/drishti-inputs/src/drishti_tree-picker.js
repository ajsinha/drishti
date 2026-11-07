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
/* The tree picker: a hierarchy (desks, books, traders; regions, countries) to pick from, drawn from TreeModel. A button reads the
   selection ("Rates › Swaps, FX and 2 more") and opens a panel with a search field and the tree (role tree, each node a treeitem with
   aria-checked true, mixed or false); or, with inline, the tree sits in the page. Children come from the server the first time a node
   opens (a loading row, an error row with Retry). The keys are the tree view's: Up and Down move, Right opens or goes in, Left closes or
   goes up, Home and End, Space checks, Enter checks (or, single, picks and closes), a letter jumps to the next node starting with it. */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { TreeModel } from './drishti_tree.js';
import { InputBase, Popover, debounce, uid } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} TreePickerOptions
 * @property {import('./drishti_tree.js').TreeNode[]} nodes the roots (with children, or hasChildren for the loader)
 * @property {function(Object): Promise<Array>} [loadChildren] a node's children from the server, the first time it opens
 * @property {function(string): Promise<Array<{node: Object, path: Object[]}>>} [search] search on the server (else the loaded levels)
 * @property {boolean} [multiple=true]
 * @property {string[]} [value] the checked ids (compact: a node stands for everything under it)
 * @property {boolean} [inline=false] the tree in the page rather than in a panel
 * @property {string} [label]
 * @property {string} [placeholder='Everything'] the words when nothing is checked
 */

/**
 * A tree picker. Events: change {value, leaves}, open, close, loadFailed {id, error}.
 */
export class TreePicker extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {TreePickerOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-tree-picker');
    const o = this.o;
    this.model = new TreeModel({ nodes: o.nodes || [], loadChildren: o.loadChildren, multiple: o.multiple !== false, value: o.value });
    this.active = null;
    this.id = uid('tree');
    this.box = this.el('div', { cls: 'di-tree-box' });
    this.search = this.el('input', { cls: 'di-field di-search', attrs: { type: 'search', placeholder: 'Search', 'aria-label': 'Search ' + (o.label || 'the hierarchy'), 'aria-controls': this.id } });
    this.tree = this.el('div', { cls: 'di-tree', attrs: { role: 'tree', id: this.id, tabindex: '0', 'aria-label': o.label || 'Hierarchy', 'aria-multiselectable': this.model.multiple ? 'true' : null } });
    this.count = this.el('span', { cls: 'di-count', attrs: { 'aria-live': 'polite' } });
    const foot = this.el('div', { cls: 'di-tree-foot' }, [this.count, this.el('span', { cls: 'di-sp' })]);
    if (this.model.multiple) {
      foot.append(this.el('button', { cls: 'di-btn', text: 'All', attrs: { type: 'button' }, data: { act: 'all' }, on: { click: () => this.checkAll(true) } }),
        this.el('button', { cls: 'di-btn', text: 'None', attrs: { type: 'button' }, data: { act: 'none' }, on: { click: () => this.checkAll(false) } }));
    }
    this.box.append(this.search, this.tree, foot);
    const run = debounce((q) => this.model.search(q, o.search).then(() => { this.active = null; this.render(); }), o.search ? 250 : 60);
    this.search.addEventListener('input', () => run(this.search.value));
    this.search.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown') { e.preventDefault(); this.tree.focus(); this.move(0, true); } });
    this.tree.addEventListener('keydown', (e) => this.key(e));
    this.tree.addEventListener('click', (e) => this.click(e));
    this.tree.addEventListener('focus', () => { if (!this.active) { this.move(0, true); } });
    if (o.inline) { host.appendChild(this.box); host.classList.add('di-inline'); }
    else {
      this.pop = new Popover(this.doc, { cls: 'di-tree-pop', label: o.label || 'Choose', theme: host, onClose: () => { this.button.setAttribute('aria-expanded', 'false'); this.emit('close'); } });
      this.button = this.el('button', { cls: 'di-field di-tree-btn', attrs: { type: 'button', 'aria-haspopup': 'tree', 'aria-expanded': 'false', 'aria-label': o.label || 'Choose' } },
        [this.el('span', { cls: 'di-tree-text' }), this.el('span', { cls: 'di-caret', text: '▾', attrs: { 'aria-hidden': 'true' } })]);
      this.button.addEventListener('click', () => (this.pop.isOpen() ? this.pop.close(true) : this.open()));
      this.button.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown') { e.preventDefault(); this.open(); } });
      host.appendChild(this.button);
    }
    this.render();
  }

  /**
   * Opens the panel (focus in the search field).
   * @returns {void}
   */
  open() {
    if (!this.pop) { return; }
    this.pop.open(this.button, this.box);
    this.button.setAttribute('aria-expanded', 'true');
    this.render();
    this.search.focus();
    this.emit('open');
  }

  /**
   * Closes the panel.
   * @returns {void}
   */
  close() { if (this.pop) { this.pop.close(true); } }

  /**
   * Draws the tree, the count and the button's words.
   * @returns {void}
   */
  render() {
    const rows = this.rows = this.model.rows(), t = this.tree;
    t.textContent = '';
    rows.forEach((r, i) => {
      const n = r.node, id = this.id + '-n' + i;
      const item = this.el('div', { cls: 'di-node' + (r.match ? ' di-match' : '') + (n.id === this.active ? ' di-active' : '') + (n.disabled ? ' di-disabled' : ''),
        attrs: { role: 'treeitem', id: id, 'aria-level': String(r.depth + 1), 'aria-expanded': r.branch ? String(r.expanded) : null,
          'aria-checked': this.model.multiple ? (r.state === 'mixed' ? 'mixed' : String(r.state === 'checked')) : null,
          'aria-selected': this.model.multiple ? null : String(r.state === 'checked'), 'aria-disabled': n.disabled ? 'true' : null }, data: { id: n.id } });
      item.style.setProperty('--di-depth', String(r.depth));
      item.appendChild(this.el('span', { cls: 'di-twist' + (r.branch ? '' : ' di-leaf'), text: r.branch ? (r.expanded ? '▾' : '▸') : '', data: { twist: '1' } }));
      if (this.model.multiple) { item.appendChild(this.el('span', { cls: 'di-check di-' + r.state, text: r.state === 'checked' ? '☑' : r.state === 'mixed' ? '◩' : '☐', attrs: { 'aria-hidden': 'true' } })); }
      item.appendChild(this.el('span', { cls: 'di-node-label', text: n.label }));
      if (n.detail) { item.appendChild(this.el('span', { cls: 'di-option-detail', text: n.detail })); }
      t.appendChild(item);
      if (r.status === 'loading') { t.appendChild(this.el('div', { cls: 'di-node di-loading-row', text: 'Loading…', attrs: { role: 'presentation' } })); }
      else if (r.status) {
        t.appendChild(this.el('div', { cls: 'di-node di-error-row', attrs: { role: 'presentation' } }, [r.status + ' ',
          this.el('button', { cls: 'di-btn', text: 'Retry', attrs: { type: 'button' }, data: { retry: n.id } })]));
      }
    });
    if (!rows.length) { t.appendChild(this.el('div', { cls: 'di-empty', text: this.model.query ? 'Nothing matches "' + this.model.query + '"' : 'Nothing to choose from' })); }
    const ai = rows.findIndex((r) => r.node.id === this.active);
    if (ai >= 0) { t.setAttribute('aria-activedescendant', this.id + '-n' + ai); } else { t.removeAttribute('aria-activedescendant'); }
    const n = this.model.getValue().length;
    this.count.textContent = this.model.multiple ? (n ? n + ' selected' : 'None selected') : '';
    if (this.button) { const s = this.model.summary({ none: this.o.placeholder || 'Everything' }); this.button.querySelector('.di-tree-text').textContent = s; this.button.title = s; }
    if (this.pop && this.pop.isOpen()) { this.pop.place(); }
  }

  /** @private */
  click(e) {
    const retry = e.target.closest('[data-retry]');
    if (retry) { this.model.status.delete(retry.dataset.retry); this.expand(retry.dataset.retry, true); return; }
    const item = e.target.closest('.di-node[data-id]');
    if (!item) { return; }
    const id = item.dataset.id;
    this.active = id;
    if (e.target.closest('[data-twist]')) { this.expand(id); return; }
    this.toggleCheck(id);
  }

  /**
   * Opens or closes a node (children from the server the first time).
   * @param {string} id
   * @param {boolean} [open]
   * @returns {Promise<void>}
   */
  expand(id, open) {
    const p = this.model.toggle(id, open);
    this.render();
    return p.then(() => {
      const st = this.model.status.get(id);
      if (st) { this.emit('loadFailed', { id: id, error: st }); }
      this.render();
    });
  }

  /** @private checks a node and tells everyone */
  toggleCheck(id) {
    const n = this.model.node(id);
    if (!n || n.disabled) { return; }
    this.model.check(id);
    this.render();
    const st = this.model.stateOf(id);
    announce(n.label + (st === 'checked' ? ' selected' : st === 'mixed' ? ' partly selected' : ' not selected'));
    this.changed();
    if (!this.model.multiple && this.pop) { this.pop.close(true); }
  }

  /** @private */
  changed() { this.emit('change', { value: this.model.getValue(), leaves: this.model.getValue({ mode: 'leaves' }) }); }

  /**
   * Checks everything or nothing.
   * @param {boolean} on
   * @returns {void}
   */
  checkAll(on) { this.model.checkAll(on); this.render(); this.changed(); }

  /** @private the active row moved by n (or to an index when abs) */
  move(n, abs) {
    const rows = this.rows || [];
    if (!rows.length) { return; }
    const cur = rows.findIndex((r) => r.node.id === this.active);
    const i = Math.max(0, Math.min(rows.length - 1, abs ? n : (cur < 0 ? 0 : cur + n)));
    this.active = rows[i].node.id;
    this.render();
    const el = this.doc.getElementById(this.id + '-n' + i);
    if (el && el.scrollIntoView) { el.scrollIntoView({ block: 'nearest' }); }
  }

  /** @private the tree view's keys */
  key(e) {
    const rows = this.rows || [], i = rows.findIndex((r) => r.node.id === this.active), r = rows[i], k = e.key;
    if (k === 'ArrowDown') { e.preventDefault(); this.move(1); return; }
    if (k === 'ArrowUp') { e.preventDefault(); if (i <= 0) { this.search.focus(); } else { this.move(-1); } return; }
    if (k === 'Home') { e.preventDefault(); this.move(0, true); return; }
    if (k === 'End') { e.preventDefault(); this.move(rows.length - 1, true); return; }
    if (!r) { return; }
    if (k === 'ArrowRight') { e.preventDefault(); if (r.branch && !r.expanded) { this.expand(r.node.id, true); } else if (r.expanded) { this.move(1); } return; }
    if (k === 'ArrowLeft') {
      e.preventDefault();
      if (r.expanded) { this.expand(r.node.id, false); return; }
      const p = this.model.parent.get(r.node.id);
      if (p) { this.move(rows.findIndex((x) => x.node.id === p), true); }
      return;
    }
    if (k === ' ' || k === 'Enter') { e.preventDefault(); this.toggleCheck(r.node.id); return; }
    if (k.length === 1 && /\S/.test(k) && !e.ctrlKey && !e.metaKey && !e.altKey) {
      const lower = k.toLowerCase();
      for (let s = 1; s <= rows.length; s++) { const x = rows[(i + s) % rows.length]; if (x.node.label.toLowerCase().indexOf(lower) === 0) { this.move((i + s) % rows.length, true); break; } }
    }
  }

  /**
   * The checked ids (compact), or the loaded leaves under them.
   * @param {{mode: ('compact'|'leaves')}} [o]
   * @returns {string[]}
   */
  getValue(o) { return this.model.getValue(o); }

  /**
   * Sets the checked ids.
   * @param {string[]} ids
   * @returns {void}
   */
  setValue(ids) { this.model.setValue(ids); this.render(); }

  /**
   * Replaces the roots.
   * @param {import('./drishti_tree.js').TreeNode[]} nodes
   * @returns {void}
   */
  setNodes(nodes) { this.model.setNodes(nodes); this.render(); }
}
