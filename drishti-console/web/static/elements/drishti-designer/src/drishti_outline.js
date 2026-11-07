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
/* The outline: a design's structure as a tree (pages and their visuals, sections and their panels, a panel and what a click on it
   opens). It follows the designer's Selection both ways. An item is dragged to another place (a line between items) or into another
   (re-parent), or moved with the keys: Alt+Up and Alt+Down among its siblings, Alt+Right into the item before it, Alt+Left out of
   its parent. The host decides whether a move is allowed (canDrop) and does it ("move" event); moveNode is the move on plain data
   for a host that keeps its tree itself. The tree view's keys: the arrows, Home, End, Right and Left to open and close, Enter to open
   the item's properties. */
import { EventBus, announce, h } from '../../drishti-core/src/drishti_index.js';
import { trackPointer } from './drishti_pointer.js';

/**
 * A node of the outline.
 * @typedef {Object} OutlineNode
 * @property {string} id
 * @property {string} label
 * @property {string} [detail] a short note after the label (its kind, "opens on a click")
 * @property {string} [icon]
 * @property {OutlineNode[]} [children]
 * @property {boolean} [fixed] cannot be dragged
 */

/**
 * The nodes as visible rows (depth first), with their level, parent and position.
 * @param {OutlineNode[]} nodes
 * @param {Set<string>} [closed] the ids of closed branches
 * @returns {Array<{node: OutlineNode, level: number, parent: ?string, index: number, size: number}>}
 */
export function flattenTree(nodes, closed) {
  const out = [];
  const walk = (list, level, parent) => list.forEach((n, i) => {
    out.push({ node: n, level, parent, index: i, size: list.length });
    if (n.children && n.children.length && !(closed && closed.has(n.id))) { walk(n.children, level + 1, n.id); }
  });
  walk(nodes || [], 1, null);
  return out;
}

/**
 * Where a node is: its parent's id (null at the top), its list and its index.
 * @param {OutlineNode[]} nodes
 * @param {string} id
 * @returns {?{parent: ?string, list: OutlineNode[], index: number, node: OutlineNode}}
 */
export function findNode(nodes, id) {
  const walk = (list, parent) => {
    for (let i = 0; i < list.length; i++) {
      if (list[i].id === id) { return { parent, list, index: i, node: list[i] }; }
      const sub = list[i].children ? walk(list[i].children, list[i].id) : null;
      if (sub) { return sub; }
    }
    return null;
  };
  return walk(nodes || [], null);
}

/**
 * Whether `id` is `of` or inside it.
 * @param {OutlineNode[]} nodes
 * @param {string} id
 * @param {string} of
 * @returns {boolean}
 */
export function isWithin(nodes, id, of) {
  const f = findNode(nodes, of);
  if (!f) { return false; }
  const walk = (n) => n.id === id || (n.children || []).some(walk);
  return walk(f.node);
}

/**
 * A node moved before or after another, or inside it (as its last child): a new tree (the given one is not changed), or null when
 * the move is impossible (onto itself, or into its own branch).
 * @param {OutlineNode[]} nodes
 * @param {string} id
 * @param {string} target
 * @param {('before'|'after'|'inside')} where
 * @returns {?OutlineNode[]}
 */
export function moveNode(nodes, id, target, where) {
  if (id === target || isWithin(nodes, target, id)) { return null; }
  const tree = JSON.parse(JSON.stringify(nodes));
  const from = findNode(tree, id);
  if (!from || !findNode(tree, target)) { return null; }
  const node = from.list.splice(from.index, 1)[0];
  const to = findNode(tree, target);
  if (where === 'inside') { (to.node.children = to.node.children || []).push(node); } else { to.list.splice(to.index + (where === 'after' ? 1 : 0), 0, node); }
  return tree;
}

/**
 * The outline. Events: move {id, target, where, parent}, open {id}, toggle {id, open}.
 */
export class Outline {
  /**
   * @param {HTMLElement} el
   * @param {{nodes: OutlineNode[], selection: import('./drishti_selection.js').Selection, label: string,
   *   canDrop: function(string, string, string): (boolean|string), readOnly: boolean}} o canDrop(id, target, where): true, false, or why
   *   not in words
   */
  constructor(el, o) {
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ nodes: [], label: 'Outline' }, o);
    this.sel = this.o.selection;
    this.events = new EventBus();
    this.closed = new Set();
    this.active = null;
    el.classList.add('dd-outline');
    this.tree = h(this.doc, 'ul', { cls: 'dd-tree', attrs: { role: 'tree', 'aria-label': this.o.label, 'aria-multiselectable': 'true' } });
    el.appendChild(this.tree);
    this.tree.addEventListener('click', (e) => this.onClick(e));
    this.tree.addEventListener('dblclick', (e) => { const r = this.rowOf(e.target); if (r) { this.events.emit('open', { id: r }); } });
    this.tree.addEventListener('keydown', (e) => this.onKey(e));
    this.tree.addEventListener('pointerdown', (e) => this.onPointer(e));
    if (this.sel) { this.off = this.sel.on('change', (s) => { if (s.primary) { this.active = s.primary; this.reveal(s.primary); } this.mark(); }); }
    this.render();
  }

  /**
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * New nodes (the design changed).
   * @param {OutlineNode[]} nodes
   * @returns {void}
   */
  setNodes(nodes) { this.o.nodes = nodes || []; this.render(); }

  /** @private */
  rowOf(t) { const li = t.closest && t.closest('[data-dd-node]'); return li ? li.getAttribute('data-dd-node') : null; }

  /** @private opens every branch above an id */
  reveal(id) {
    let f = findNode(this.o.nodes, id), changed = false;
    while (f && f.parent) { if (this.closed.delete(f.parent)) { changed = true; } f = findNode(this.o.nodes, f.parent); }
    if (changed) { this.render(); }
  }

  /** @private */
  render() {
    const doc = this.doc, rows = flattenTree(this.o.nodes, this.closed);
    this.tree.textContent = '';
    if (!rows.some((r) => r.node.id === this.active)) { this.active = rows.length ? rows[0].node.id : null; }
    rows.forEach((r) => {
      const n = r.node, branch = !!(n.children && n.children.length);
      const li = h(doc, 'li', { cls: 'dd-node', attrs: { role: 'treeitem', 'aria-level': String(r.level), 'aria-setsize': String(r.size), 'aria-posinset': String(r.index + 1),
        'aria-expanded': branch ? String(!this.closed.has(n.id)) : null, 'data-dd-node': n.id, tabindex: '-1' } }, [
        h(doc, 'span', { cls: 'dd-twisty' + (branch ? '' : ' dd-leaf'), attrs: { 'aria-hidden': 'true', 'data-dd-twisty': '' }, text: branch ? (this.closed.has(n.id) ? '▸' : '▾') : '' }),
        n.icon ? h(doc, 'i', { cls: n.icon, attrs: { 'aria-hidden': 'true' } }) : null,
        h(doc, 'span', { cls: 'dd-node-label', text: n.label }),
        n.detail ? h(doc, 'span', { cls: 'dd-node-detail', text: n.detail }) : null]);
      li.style.setProperty('--dd-level', String(r.level - 1));
      this.tree.appendChild(li);
    });
    this.mark();
  }

  /** @private */
  mark() {
    const ids = this.sel ? this.sel.list() : [];
    this.tree.querySelectorAll('[data-dd-node]').forEach((li) => {
      const id = li.getAttribute('data-dd-node');
      li.setAttribute('aria-selected', String(ids.indexOf(id) >= 0));
      li.tabIndex = id === this.active ? 0 : -1;
    });
  }

  /** @private */
  focusRow(id) { this.active = id; this.mark(); const li = this.tree.querySelector('[data-dd-node="' + CSS.escape(id) + '"]'); if (li) { li.focus(); } }

  /** @private */
  onClick(e) {
    const id = this.rowOf(e.target);
    if (!id) { return; }
    if (e.target.closest('[data-dd-twisty]')) { this.toggle(id); return; }
    this.active = id;
    if (this.sel) { this.sel.pick(id, e.shiftKey || e.ctrlKey || e.metaKey, 'outline'); }
  }

  /**
   * Opens or closes a branch.
   * @param {string} id
   * @param {boolean} [open] default: the other way
   * @returns {void}
   */
  toggle(id, open) {
    const now = open === undefined ? this.closed.has(id) : open;
    if (now) { this.closed.delete(id); } else { this.closed.add(id); }
    this.render();
    this.focusRow(id);
    this.events.emit('toggle', { id, open: now });
  }

  /**
   * Asks for a move (the host's canDrop decides; the "move" event asks the host to do it).
   * @param {string} id
   * @param {string} target
   * @param {('before'|'after'|'inside')} where
   * @returns {boolean} whether it was asked
   */
  requestMove(id, target, where) {
    if (this.o.readOnly) { announce('This design is read-only.'); return false; }
    if (id === target || isWithin(this.o.nodes, target, id)) { announce('An item cannot go inside itself.'); return false; }
    const ok = this.o.canDrop ? this.o.canDrop(id, target, where) : true;
    if (ok !== true) { announce(typeof ok === 'string' ? ok : 'It cannot go there.'); return false; }
    const parent = where === 'inside' ? target : (findNode(this.o.nodes, target) || {}).parent || null;
    this.events.emit('move', { id, target, where, parent });
    return true;
  }

  /** @private the tree view's keys and the moves */
  onKey(e) {
    const id = this.rowOf(e.target);
    if (!id) { return; }
    const rows = flattenTree(this.o.nodes, this.closed), i = rows.findIndex((r) => r.node.id === id), r = rows[i];
    const f = findNode(this.o.nodes, id), branch = !!(r.node.children && r.node.children.length);
    if (e.altKey && /^Arrow/.test(e.key)) {
      e.preventDefault();
      if (e.key === 'ArrowUp' && f.index > 0) { this.requestMove(id, f.list[f.index - 1].id, 'before'); }
      else if (e.key === 'ArrowDown' && f.index < f.list.length - 1) { this.requestMove(id, f.list[f.index + 1].id, 'after'); }
      else if (e.key === 'ArrowRight' && f.index > 0) { this.requestMove(id, f.list[f.index - 1].id, 'inside'); }
      else if (e.key === 'ArrowLeft' && f.parent) { this.requestMove(id, f.parent, 'after'); }
      else { announce('It cannot move that way.'); }
      return;
    }
    const go = (k) => { if (rows[k]) { e.preventDefault(); this.focusRow(rows[k].node.id); if (this.sel) { this.sel.pick(rows[k].node.id, e.shiftKey, 'outline'); } } };
    if (e.key === 'ArrowDown') { go(i + 1); } else if (e.key === 'ArrowUp') { go(i - 1); } else if (e.key === 'Home') { go(0); } else if (e.key === 'End') { go(rows.length - 1); }
    else if (e.key === 'ArrowRight') { e.preventDefault(); if (branch && this.closed.has(id)) { this.toggle(id, true); } else if (branch) { go(i + 1); } }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); if (branch && !this.closed.has(id)) { this.toggle(id, false); } else if (r.parent) { go(rows.findIndex((x) => x.node.id === r.parent)); } }
    else if (e.key === 'Enter') { e.preventDefault(); this.events.emit('open', { id }); }
  }

  /** @private a row dragged: a line before or after a row, or the row marked when it would go inside */
  onPointer(e) {
    const id = this.rowOf(e.target), n = id && findNode(this.o.nodes, id);
    if (!n || n.node.fixed || this.o.readOnly || e.target.closest('[data-dd-twisty]') || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
    let drop = null;
    const clear = () => this.tree.querySelectorAll('.dd-drop-before, .dd-drop-after, .dd-drop-inside').forEach((x) => x.classList.remove('dd-drop-before', 'dd-drop-after', 'dd-drop-inside'));
    trackPointer(e, {
      start: () => { this.tree.classList.add('dd-dragging'); },
      move: (ev) => {
        clear();
        const under = this.doc.elementFromPoint(ev.clientX, ev.clientY), li = under && under.closest('[data-dd-node]');
        if (!li || !this.tree.contains(li)) { drop = null; return; }
        const r = li.getBoundingClientRect(), y = (ev.clientY - r.top) / r.height;
        drop = { target: li.getAttribute('data-dd-node'), where: y < 0.3 ? 'before' : y > 0.7 ? 'after' : 'inside' };
        li.classList.add('dd-drop-' + drop.where);
      },
      end: (ev, committed) => {
        clear();
        this.tree.classList.remove('dd-dragging');
        if (committed && drop && drop.target !== id) { this.requestMove(id, drop.target, drop.where); }
      }
    });
  }

  /**
   * Stops following the selection.
   * @returns {void}
   */
  destroy() { if (this.off) { this.off(); } this.events.clear(); }
}
