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
/* The outline: a tree (Tree, its grid mode, chrome: false) inside a narrow panel of an authoring screen that has its own filter box and
   buttons (a structure browser, a field list). One engine and one grid; the outline adds the panel's part. Marks after a node's label
   (a type, a badge with a tone and a title, a share), drawn through the tree grid's labelColumn hook; a class on a node's row; a filter
   that keeps the matches and the nodes above them (the rest leave the tree; their open state is remembered); the keys of a tree on the
   rows (arrows, Home and End, Right and Left to open, close and go up; Enter or Space: a node with children opens or closes, a node
   without shows its detail); and the detail: the host's words about a node in a box under the tree (Enter, a click; Enter again or
   Escape closes it). The host's own keys on a node come first (onKey). Every text goes in as text, never as HTML. */
import { h, announce } from '../../drishti-core/src/drishti_index.js';
import { HierarchyModel } from './drishti_tree-model.js';
import { keyMove } from './drishti_tree-io.js';
import { Tree } from './drishti_tree-view.js';

/** The tones a mark may take. */
export const OUTLINE_TONES = Object.freeze(['muted', 'accent', 'link', 'good', 'warn', 'bad']);

/**
 * A mark after a node's label.
 * @typedef {Object} OutlineMark
 * @property {string} text
 * @property {('muted'|'accent'|'link'|'good'|'warn'|'bad')} [tone='muted']
 * @property {boolean} [badge=false] drawn as a pill (a role, a state) rather than plain words
 * @property {string} [title] the words on hover (the reason, the whole text of a clipped mark)
 * @property {boolean} [mono=false] in the monospace face (a type, an example value)
 * @property {boolean} [clip=false] long text cut with an ellipsis
 */

/**
 * The options of an outline.
 * @typedef {Object} OutlineOptions
 * @property {import('./drishti_tree-model.js').HierarchySource} data the hierarchy (rows with parents, a nested root, or paths)
 * @property {string} [title='Node'] the grid's heading
 * @property {string} [label] its name for a screen reader (default: the title)
 * @property {('comfortable'|'compact'|'terminal')} [density='compact']
 * @property {number} [labelWidth=480] the width of the node's column (its marks included); a narrower panel scrolls across
 * @property {number} [openDepth=-1] nodes open down to this depth at the start (-1: all)
 * @property {number} [fitRows=24] the tree as tall as its rows, up to this many (then it scrolls)
 * @property {function(import('./drishti_tree-model.js').TreeNode): OutlineMark[]} [marks] the marks after a node's label
 * @property {function(import('./drishti_tree-model.js').TreeNode): ?string} [rowClass] more classes on a node's row
 * @property {function(import('./drishti_tree-model.js').TreeNode): string} [text] the words a filter looks in (default: label, id, kind)
 * @property {function(import('./drishti_tree-model.js').TreeNode, HTMLElement): (string[]|void)} [detail] a node's detail: lines of text
 *   to show, or nothing after filling the box itself; no lines and an empty box: the node has no detail
 * @property {function(import('./drishti_tree-model.js').TreeNode, KeyboardEvent): boolean} [onKey] the host's keys on the focused node,
 *   before the outline's (true: handled)
 */

/**
 * The filter of an outline: the nodes whose words hold every word of the query (any case), and the nodes to keep (those and every node
 * above them). An empty query keeps everything (keep null).
 * @param {HierarchyModel} model
 * @param {string} q
 * @param {function(import('./drishti_tree-model.js').TreeNode): string} [textOf] default: label, id and kind
 * @returns {{hits: string[], keep: ?Set<string>}}
 */
export function outlineFilter(model, q, textOf) {
  const words = String(q || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) { return { hits: [], keep: null }; }
  const wordsOf = textOf || ((n) => n.label + ' ' + n.id + ' ' + n.kind), hits = [], keep = new Set();
  model.all().forEach((n) => {
    const hay = String(wordsOf(n) || '').toLowerCase();
    if (!words.every((w) => hay.indexOf(w) >= 0)) { return; }
    hits.push(n.id);
    for (let p = n; p && !keep.has(p.id); p = p.parent) { keep.add(p.id); }
  });
  return { hits: hits, keep: keep };
}

/**
 * What a key does on an outline's focused node: keyMove's tree keys, with Enter and Space opening or closing a node with children and
 * showing the detail of one without.
 * @param {string} key
 * @param {HierarchyModel} model
 * @param {string[]} order the ids of the rows shown, top to bottom
 * @param {string} at the focused node
 * @returns {?{to: string, act: ('focus'|'open'|'close'|'detail')}}
 */
export function outlineKey(key, model, order, at) {
  if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'ArrowRight', 'ArrowLeft', 'Enter', ' '].indexOf(key) < 0) { return null; }
  const mv = keyMove(key, model, order, at, true);
  if (!mv) { return null; }
  if (key !== 'Enter' && key !== ' ') { return mv; }
  const n = model.node(mv.to);
  return n && n.children.length ? { to: n.id, act: model.isOpen(n.id) ? 'close' : 'open' } : { to: mv.to, act: 'detail' };
}

const OID = '__oid', OPARENT = '__oparent', OLABEL = '__olabel', OKIND = '__okind';

/**
 * The outline: a hierarchy as a compact tree grid (a Tree without its chrome), with marks, a hiding filter, a tree's keys and a detail.
 */
export class TreeOutline {
  /**
   * @param {HTMLElement} host the element the outline fills
   * @param {OutlineOptions} options
   */
  constructor(host, options) {
    if (!host || !host.ownerDocument) { throw new Error('an outline needs an element to fill'); }
    const o = this.o = Object.assign({ title: 'Node', labelWidth: 480, openDepth: -1, fitRows: 24 }, options);
    if (!o.data) { throw new Error('an outline needs data (rows, root or getPath)'); }
    this.host = host;
    this.doc = host.ownerDocument;
    /** @type {HierarchyModel} every node, whatever the filter keeps */
    this.full = new HierarchyModel(o.data);
    this.keep = null;
    this.query = '';
    this.memory = new Set();
    this.detailOf = null;
    host.classList.add('dt-outline');
    host.textContent = '';
    this.treeBox = h(this.doc, 'div', { cls: 'dt-outline-tree' });
    this.detailBox = h(this.doc, 'div', { cls: 'dt-detail', attrs: { hidden: true, role: 'region', 'aria-label': 'Details', 'aria-live': 'polite' } });
    host.appendChild(this.treeBox);
    host.appendChild(this.detailBox);
    /** @type {Tree} the tree drawn (grid mode, no chrome) */
    this.tree = new Tree(this.treeBox, { data: this.source(), chrome: false, modes: ['grid'], title: o.title, label: o.label || o.title, density: o.density,
      openDepth: o.openDepth, grid: { fitRows: o.fitRows, phoneWidth: 1, labelColumn: { width: o.labelWidth, cellRenderer: (p) => this.labelCell(p) },
        rowClass: o.rowClass ? (r) => { const n = this.full.node(r.__id); return n ? o.rowClass(n) : null; } : undefined } });
    this.offSel = this.tree.on('selectionChanged', (p) => { if (p.ids && p.ids.length) { this.showDetail(p.ids[0]); } });
    this.onKeyDown = (e) => this.key(e);
    host.addEventListener('keydown', this.onKeyDown, true);
  }

  /** @private the tree's rows: the kept nodes, each with its node's own row */
  source() {
    const keep = this.keep;
    return { idField: OID, parentField: OPARENT, labelField: OLABEL, kindField: OKIND, alsoField: '__oalso', lazyField: '__olazy',
      rows: this.full.all().filter((n) => !keep || keep.has(n.id)).map((n) => {
        const r = Object.assign({}, n.data);
        r[OID] = n.id; r[OPARENT] = n.parent ? n.parent.id : null; r[OLABEL] = n.label; r[OKIND] = n.kind;
        return r;
      }) };
  }

  /** @private the node's cell: its label, then its marks */
  labelCell(p) {
    const n = p.row ? this.full.node(p.row.__id) : null;
    if (!n) { return null; }
    const marks = this.o.marks ? this.o.marks(n) || [] : [];
    return h(p.doc, 'span', { cls: 'dg-text dt-oline' }, [h(p.doc, 'span', { cls: 'dt-oname', text: n.label })].concat(marks.map((m) => h(p.doc, 'span', {
      cls: 'dt-mark dt-tone-' + (OUTLINE_TONES.indexOf(m.tone) >= 0 ? m.tone : 'muted') + (m.badge ? ' dt-badge' : '') + (m.mono ? ' dt-mono' : '') + (m.clip ? ' dt-clip' : ''),
      text: String(m.text), attrs: { title: m.title || null } }))));
  }

  /** @private the tree's rows again (the kept nodes), the open state remembered across the filter */
  reload(fresh) {
    const t = this.tree;
    t.model.all().forEach((n) => this.memory.delete(n.id));
    t.model.open.forEach((id) => this.memory.add(id));
    t.setData(this.source());
    const m = t.model;
    m.open.clear();
    this.memory.forEach((id) => { if (m.node(id)) { m.open.add(id); } });
    if (fresh && !m.open.size && this.o.openDepth !== 0) { m.expandAll({ depth: this.o.openDepth < 0 ? Infinity : this.o.openDepth }); }
    else { m.events.emit('changed', { what: 'open' }); }
  }

  /**
   * New data: the open state kept for the nodes still there (opened by openDepth when none is), the filter applied again.
   * @param {import('./drishti_tree-model.js').HierarchySource} data
   * @returns {void}
   */
  setData(data) {
    const known = new Set(this.full.all().map((n) => n.id)), d = this.o.openDepth < 0 ? Infinity : this.o.openDepth;
    this.full.load(data);
    if (this.detailOf && !this.full.node(this.detailOf)) { this.closeDetail(); }
    this.memory.forEach((id) => { if (!this.full.node(id)) { this.memory.delete(id); } });
    this.full.all().forEach((n) => { if (!known.has(n.id) && n.children.length && n.depth < d) { this.memory.add(n.id); } });   // a new branch opens as at the start
    const f = outlineFilter(this.full, this.query, this.o.text);
    this.keep = f.keep;
    this.tree.hits = new Set(f.hits);
    this.reload(true);
  }

  /**
   * Keeps the nodes whose words hold the query and the nodes above them (an empty query: every node); their branches open.
   * @param {string} q
   * @returns {string[]} the ids of the matching nodes
   */
  filter(q) {
    this.query = String(q || '').trim();
    const f = outlineFilter(this.full, this.query, this.o.text);
    this.keep = f.keep;
    this.tree.model.open.forEach((id) => this.memory.add(id));
    f.hits.forEach((id) => this.full.pathOf(id).slice(0, -1).forEach((n) => this.memory.add(n.id)));
    this.tree.hits = new Set(f.hits);
    this.reload(false);
    return f.hits;
  }

  /** Opens every node. @returns {void} */
  expandAll() { this.tree.expandAll(); }

  /** Closes every node: the top level shows. @returns {void} */
  collapseAll() { this.memory.clear(); this.tree.model.open.clear(); this.tree.model.events.emit('changed', { what: 'open' }); }

  /**
   * The node of an element inside the outline (a row's cell, its marks), for a host's drag or menu.
   * @param {?Element} el
   * @returns {?import('./drishti_tree-model.js').TreeNode}
   */
  nodeAt(el) {
    const c = el && el.closest ? el.closest('[data-r]') : null;
    return c && this.treeBox.contains(c) && this.tree.grid ? this.full.node(this.tree.grid.idOfRow(Number(c.getAttribute('data-r')))) : null;
  }

  /** The ids of the rows shown, top to bottom. @returns {string[]} */
  shownIds() {
    const g = this.tree.grid, out = [];
    if (!g) { return out; }
    for (let r = 0, n = g.grid.model.rowCount(); r < n; r++) { const id = g.idOfRow(r); if (id) { out.push(id); } }
    return out;
  }

  /** The focused node's id (the grid's focus), or null. @returns {?string} */
  focusedId() { const g = this.tree.grid, f = g ? g.grid.model.navigator.focus : null; return f ? g.idOfRow(f.row) : null; }

  /**
   * Moves the grid's focus to a node's row.
   * @param {string} id
   * @returns {void}
   */
  focusNode(id) {
    const g = this.tree.grid, n = this.tree.model.node(id);
    if (!g || !n) { return; }
    const i = g.grid.model.rowModel.indexOfId(id);
    if (i < 0) { return; }
    g.grid.model.navigator.setFocus(i, 0);
    g.grid.afterFocus(true);
    announce(n.label + ', level ' + (n.depth + 1));
  }

  /**
   * Shows a node's detail under the tree (the detail option); nothing when the node has none.
   * @param {string} id
   * @returns {boolean} whether a detail shows
   */
  showDetail(id) {
    const n = this.full.node(id), box = this.detailBox;
    if (!n || !this.o.detail) { return false; }
    box.textContent = '';
    const lines = this.o.detail(n, box), own = Array.prototype.slice.call(box.childNodes);
    (Array.isArray(lines) ? lines : []).forEach((t) => box.insertBefore(h(this.doc, 'div', { cls: 'dt-line', text: String(t) }), own[0] || null));
    if (!box.firstChild) { this.closeDetail(); return false; }
    box.hidden = false;
    box.setAttribute('data-node', n.id);
    this.detailOf = n.id;
    return true;
  }

  /** Closes the detail. @returns {void} */
  closeDetail() { this.detailBox.hidden = true; this.detailBox.textContent = ''; this.detailBox.removeAttribute('data-node'); this.detailOf = null; }

  /** @private the keys of a tree on the rows, the host's first */
  key(e) {
    const g = this.tree.grid;
    if (e.ctrlKey || e.metaKey || e.altKey || !g || !g.grid.body.scroller.contains(e.target)) { return; }
    const order = this.shownIds(), at = this.focusedId() || order[0];
    if (!at) { return; }
    const stop = () => { e.preventDefault(); e.stopPropagation(); };
    if (this.o.onKey && this.o.onKey(this.full.node(at), e) === true) { stop(); return; }
    if (e.key === 'Escape' && this.detailOf) { stop(); this.closeDetail(); return; }
    const mv = outlineKey(e.key, this.tree.model, order, at);
    if (!mv) { return; }
    stop();
    if (mv.act === 'open' || mv.act === 'close') { this.tree.toggle(mv.to, mv.act === 'open'); }
    if (mv.act === 'detail') { if (this.detailOf === mv.to) { this.closeDetail(); } else { this.tree.select(mv.to, { from: 'grid' }); } }
    this.focusNode(mv.to);
  }

  /** Takes the outline down. @returns {void} */
  destroy() {
    this.offSel();
    this.host.removeEventListener('keydown', this.onKeyDown, true);
    this.tree.destroy();
    this.host.textContent = '';
    this.host.classList.remove('dt-outline');
  }
}
