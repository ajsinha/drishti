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
   buttons (a structure browser, a field list, a design's pages and visuals, a screen's sections and panels). One engine and one grid;
   the outline adds the panel's part. Marks after a node's label (a type, a badge with a tone and a title, a share), drawn through the
   tree grid's labelColumn hook; a class and data-* attributes on a node's row; a filter that keeps the matches and the nodes above them
   (the rest leave the tree; their open state is remembered); the keys of a tree on the rows (arrows, Home and End, Right and Left to
   open, close and go up; Enter or Space: a node with children opens or closes, a node without shows its detail and is "opened"); and
   the detail: the host's words about a node in a box under the tree (Enter, a click; Enter again or Escape closes it). The host's own
   keys on a node come first (onKey). Every text goes in as text, never as HTML.
   For a designer it also follows a Selection both ways (drishti-designer's: a click or the arrows pick, Shift or Ctrl add; a pick
   elsewhere shows and marks the rows), and moves: a row dragged to a line between rows (before, after) or onto a row (inside), or
   moved with the keys (Alt+Up and Alt+Down among its siblings, Alt+Right into the node before it, Alt+Left out of its parent). The
   host decides whether a move is allowed (canDrop) and does it ("move" event); moveNode is the move on plain nested nodes for a host
   that keeps its tree itself. A fixed node (a heading, a "drop here" row) is never dragged and is still a place to drop on. */
import { h, announce, EventBus, trackPointer } from '../../drishti-core/src/drishti_index.js';
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
 * @property {import('./drishti_tree-model.js').HierarchySource} [data] the hierarchy (rows with parents, a nested root, or paths)
 * @property {OutlineNode[]} [nodes] or the hierarchy as nested nodes (a designer's structure); neither: an empty outline (setNodes later)
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

/**
 * A nested node of an outline (OutlineOptions.nodes).
 * @typedef {Object} OutlineNode
 * @property {string} id
 * @property {string} label
 * @property {string} [detail] a short note after the label (its kind, "opens on a click"): a muted mark
 * @property {string} [icon] the classes of an icon before the label
 * @property {OutlineNode[]} [children]
 * @property {boolean} [fixed] cannot be dragged (a heading, a "drop here" row: still a place to drop on)
 * @property {string} [cls] more classes on its row (the host's look: a drop row, a heading)
 * @property {Object<string, string>} [attrs] the host's own data-* attributes on its row (its selectors: data-panel="trades"); other
 *   names are left out, the row's role and state stay the outline's
 */

/**
 * Nested nodes as visible rows (depth first), with their level, parent and position.
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
 * Where a nested node is: its parent's id (null at the top), its list and its index.
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
 * A nested node moved before or after another, or inside it (as its last child): a new tree (the given one is not changed), or null
 * when the move is impossible (onto itself, or into its own branch).
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
 * Nested nodes as an outline's hierarchy: rows naming their parent, each keeping its node's own fields (its children left out).
 * @param {OutlineNode[]} nodes
 * @returns {import('./drishti_tree-model.js').HierarchySource}
 */
export function nodesSource(nodes) {
  const rows = [];
  const walk = (list, parent) => (list || []).forEach((n) => {
    const r = Object.assign({}, n, { id: String(n.id), parent: parent });
    delete r.children;
    rows.push(r);
    walk(n.children, r.id);
  });
  walk(nodes, null);
  return { rows: rows };
}

/** @private a node's own data-* attributes as a grid row's dataset (data-ol-panel: olPanel); data-node is the outline's */
function rowDataset(attrs) {
  const out = {};
  Object.keys(attrs || {}).forEach((k) => {
    if (/^data-(?!node$)[a-z][\w-]*$/.test(k) && attrs[k] !== null && attrs[k] !== undefined) { out[k.slice(5).replace(/-([a-z])/g, (m, c) => c.toUpperCase())] = String(attrs[k]); }
  });
  return out;
}

const OID = '__oid', OPARENT = '__oparent', OLABEL = '__olabel', OKIND = '__okind';
const DROPS = ['dt-drop-before', 'dt-drop-after', 'dt-drop-inside'];

/**
 * The outline: a hierarchy as a compact tree grid (a Tree without its chrome), with marks, a hiding filter, a tree's keys and a detail;
 * for a designer, a Selection followed both ways and moves by drag or Alt+arrows. Its rows carry data-node (the node's id) and the
 * host's data-* attributes. Events (on): move {id, target, where, parent}, open {id} (Enter on a node without children, a double
 * click), toggle {id, open}.
 */
export class TreeOutline {
  /**
   * @param {HTMLElement} host the element the outline fills
   * @param {OutlineOptions} options
   */
  constructor(host, options) {
    if (!host || !host.ownerDocument) { throw new Error('an outline needs an element to fill'); }
    const o = this.o = Object.assign({ title: 'Node', labelWidth: 480, openDepth: -1, fitRows: 24 }, options);
    if (!o.data) { o.data = nodesSource(o.nodes || []); if (o.mono === undefined) { o.mono = false; } }
    this.host = host;
    this.doc = host.ownerDocument;
    this.events = new EventBus();
    this.sel = o.selection || null;
    this.movable = !o.readOnly && (o.movable !== undefined ? !!o.movable : !!(o.canDrop || o.selection));
    this.syncing = false;
    this.mods = false;
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
        rowClass: (r) => this.rowClass(r.__id), rowData: (r) => { const n = this.full.node(r.__id); return n ? Object.assign(rowDataset(n.data.attrs), { node: n.id }) : null; } } });
    this.offs = [
      this.tree.on('selectionChanged', (p) => {
        if (!p.ids || !p.ids.length) { return; }
        if (this.sel && !this.syncing) { this.sel.pick(p.ids[0], this.mods, 'outline'); }
        this.showDetail(p.ids[0]);
      }),
      this.tree.on('nodeExpanded', (p) => this.events.emit('toggle', { id: p.id, open: true })),
      this.tree.on('nodeCollapsed', (p) => this.events.emit('toggle', { id: p.id, open: false }))
    ];
    if (this.sel) { this.offs.push(this.sel.on('change', (x) => { if (x.source !== 'outline') { this.follow(); } else { this.repaint(); } })); }
    this.onKeyDown = (e) => this.key(e);
    this.onDown = (e) => { this.mods = !!(e.shiftKey || e.ctrlKey || e.metaKey); this.dragStart(e); };
    this.onDbl = (e) => { const n = this.nodeAt(e.target); if (n) { this.events.emit('open', { id: n.id }); } };
    host.addEventListener('keydown', this.onKeyDown, true);
    host.addEventListener('pointerdown', this.onDown);
    host.addEventListener('dblclick', this.onDbl);
    if (this.sel) { this.follow(); }
  }

  /**
   * Listens to one of its events (move, open, toggle); answers the function that stops listening.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * New nested nodes (the design changed): the open state kept, the filter applied again, the selection shown.
   * @param {OutlineNode[]} nodes
   * @returns {void}
   */
  setNodes(nodes) { this.setData(nodesSource(nodes)); if (this.sel) { this.follow(); } }

  /** @private the rows drawn again (their classes: a pick, a drop) */
  repaint() { if (this.tree.grid) { this.tree.grid.repaint(); } }

  /** @private a node's row classes: the host's, its own (cls), fixed, picked in the selection */
  rowClass(id) {
    const n = this.full.node(id);
    if (!n) { return null; }
    const c = [this.o.rowClass ? this.o.rowClass(n) : null, n.data.cls || null, n.data.fixed ? 'dt-fixed' : null, this.sel && this.sel.has(n.id) ? 'dt-picked' : null];
    return c.filter(Boolean).join(' ') || null;
  }

  /** @private the selection changed elsewhere (a canvas, the YAML): its primary shown and focused, every picked row marked */
  follow() {
    const p = this.sel.primary(), t = this.tree;
    this.syncing = true;
    try { if (p && t.model.node(p) && t.selected !== p) { t.select(p, { silent: true }); } } finally { this.syncing = false; }
    this.repaint();
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
    const marks = (this.o.marks ? this.o.marks(n) || [] : []).slice();
    if (n.data.detail) { marks.push({ text: n.data.detail, detail: true }); }
    const icon = n.data.icon ? h(p.doc, 'i', { cls: 'dt-oicon ' + n.data.icon, attrs: { 'aria-hidden': 'true' } }) : null;
    return h(p.doc, 'span', { cls: 'dg-text dt-oline' }, [icon, h(p.doc, 'span', { cls: 'dt-oname' + (this.o.mono === false ? ' dt-plain' : ''), text: n.label })].concat(marks.map((m) => h(p.doc, 'span', {
      cls: 'dt-mark dt-tone-' + (OUTLINE_TONES.indexOf(m.tone) >= 0 ? m.tone : 'muted') + (m.badge ? ' dt-badge' : '') + (m.mono ? ' dt-mono' : '') + (m.clip ? ' dt-clip' : '') + (m.detail ? ' dt-odetail' : ''),
      text: String(m.text), attrs: { title: m.title || null } }))));
  }

  /** @private the tree's rows again (the kept nodes), the open state remembered across the filter */
  reload(fresh) {
    const t = this.tree;
    t.model.all().forEach((n) => { if (n.children.length) { this.memory.delete(n.id); } });   // a branch shown: its open state is the tree's
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
    const known = new Set(this.full.all().filter((n) => n.children.length).map((n) => n.id)), d = this.o.openDepth < 0 ? Infinity : this.o.openDepth;
    this.full.load(data);
    if (this.detailOf && !this.full.node(this.detailOf)) { this.closeDetail(); }
    this.memory.forEach((id) => { if (!this.full.node(id)) { this.memory.delete(id); } });
    this.full.all().forEach((n) => { if (!known.has(n.id) && n.children.length && n.depth < d) { this.memory.add(n.id); } });   // a new branch (or a node that now has children) opens as at the start
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
    if (e.ctrlKey || e.metaKey || !g || !g.grid.body.scroller.contains(e.target)) { return; }
    const order = this.shownIds(), at = this.focusedId() || order[0];
    if (!at) { return; }
    const stop = () => { e.preventDefault(); e.stopPropagation(); };
    if (this.o.onKey && this.o.onKey(this.full.node(at), e) === true) { stop(); return; }
    if (e.altKey) { if (this.movable && /^Arrow/.test(e.key)) { stop(); this.keyMove(at, e.key); } return; }
    if (e.key === 'Escape' && this.detailOf) { stop(); this.closeDetail(); return; }
    const mv = outlineKey(e.key, this.tree.model, order, at);
    if (!mv) { return; }
    stop();
    this.mods = !!e.shiftKey;
    if (mv.act === 'open' || mv.act === 'close') { this.tree.toggle(mv.to, mv.act === 'open'); }
    if (mv.act === 'detail') {
      if (this.detailOf === mv.to) { this.closeDetail(); } else { this.tree.select(mv.to, { from: 'grid' }); }
      this.events.emit('open', { id: mv.to });
    }
    if (mv.act === 'focus' && this.sel) { this.tree.select(mv.to, { from: 'grid' }); }     // a designer's pick follows the focus
    this.focusNode(mv.to);
  }

  /** @private Alt+arrows: among the siblings, into the node before, out of the parent */
  keyMove(id, key) {
    const n = this.full.node(id), sibs = n ? (n.parent ? n.parent.children : this.full.roots) : [], i = sibs.indexOf(n);
    if (key === 'ArrowUp' && i > 0) { this.requestMove(id, sibs[i - 1].id, 'before'); }
    else if (key === 'ArrowDown' && i >= 0 && i < sibs.length - 1) { this.requestMove(id, sibs[i + 1].id, 'after'); }
    else if (key === 'ArrowRight' && i > 0) { this.requestMove(id, sibs[i - 1].id, 'inside'); }
    else if (key === 'ArrowLeft' && n && n.parent) { this.requestMove(id, n.parent.id, 'after'); }
    else { announce('It cannot move that way.'); }
  }

  /**
   * Asks for a move: the host's canDrop decides, the "move" event asks the host to do it (the outline changes when the host gives it
   * the new nodes).
   * @param {string} id
   * @param {string} target
   * @param {('before'|'after'|'inside')} where
   * @returns {boolean} whether it was asked
   */
  requestMove(id, target, where) {
    if (this.o.readOnly) { announce('This design is read-only.'); return false; }
    const t = this.full.node(target);
    if (!t || !this.full.node(id)) { return false; }
    if (this.full.pathOf(target).some((x) => x.id === id)) { announce('An item cannot go inside itself.'); return false; }
    const ok = this.o.canDrop ? this.o.canDrop(id, target, where) : true;
    if (ok !== true) { announce(typeof ok === 'string' ? ok : 'It cannot go there.'); return false; }
    this.events.emit('move', { id, target, where, parent: where === 'inside' ? target : (t.parent ? t.parent.id : null) });
    return true;
  }

  /** @private a row dragged: a line before or after a row, or the row marked when it would go inside */
  dragStart(e) {
    const n = this.movable ? this.nodeAt(e.target) : null;
    if (!n || n.data.fixed || (e.target.closest && e.target.closest('.dg-tw')) || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
    let drop = null;
    const box = this.treeBox, clear = () => box.querySelectorAll('.' + DROPS.join(', .')).forEach((x) => DROPS.forEach((c) => x.classList.remove(c)));
    trackPointer(e, {
      start: () => { box.classList.add('dt-dragging'); const b = this.tree.grid && this.tree.grid.grid.body; if (b) { b.drag = null; } },
      move: (ev) => {
        clear();
        const under = this.doc.elementFromPoint(ev.clientX, ev.clientY), row = under && under.closest ? under.closest('[data-node]') : null;
        if (!row || !box.contains(row)) { drop = null; return; }
        const r = row.getBoundingClientRect(), y = (ev.clientY - r.top) / (r.height || 1);
        drop = { target: row.getAttribute('data-node'), where: y < 0.3 ? 'before' : y > 0.7 ? 'after' : 'inside' };
        row.classList.add('dt-drop-' + drop.where);
      },
      end: (ev, committed) => {
        clear();
        box.classList.remove('dt-dragging');
        if (committed && drop && drop.target !== n.id) { this.requestMove(n.id, drop.target, drop.where); }
      }
    });
  }

  /** Takes the outline down. @returns {void} */
  destroy() {
    this.offs.forEach((off) => off());
    this.events.clear();
    this.host.removeEventListener('keydown', this.onKeyDown, true);
    this.host.removeEventListener('pointerdown', this.onDown);
    this.host.removeEventListener('dblclick', this.onDbl);
    this.tree.destroy();
    this.host.textContent = '';
    this.host.classList.remove('dt-outline');
  }
}
