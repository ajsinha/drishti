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
/* The hierarchy's model behind the tree picker: nodes (a desk, its books, their traders), some of whose children come from a server
   when the node is first opened, and a selection kept compact: a node checked means everything under it, so a desk with all its books
   checked is the desk, and a branch never loaded can be checked whole. Each node reads checked, mixed (some below it are) or unchecked.
   Search keeps the matches and their ancestors (opened), from the loaded levels or through a server hook. Pure; runs under Node. */

/**
 * @typedef {Object} TreeNode
 * @property {string} id unique across the tree
 * @property {string} label
 * @property {string} [detail] words after the label (a count, a code)
 * @property {TreeNode[]} [children] the children when known
 * @property {boolean} [hasChildren] the node has children the loader brings (lazy)
 * @property {boolean} [disabled]
 */

/**
 * A visible row of the tree.
 * @typedef {Object} TreeRow
 * @property {TreeNode} node
 * @property {number} depth 0 for the roots
 * @property {boolean} branch whether it has (or may have) children
 * @property {boolean} expanded
 * @property {('checked'|'mixed'|'unchecked')} state
 * @property {?string} status 'loading' or the error's words while its children are asked for
 * @property {boolean} match whether it matched the search
 */

/**
 * A tree with lazy children, tri-state checks and search.
 */
export class TreeModel {
  /**
   * @param {{nodes: TreeNode[], loadChildren: function(TreeNode): Promise<TreeNode[]>, multiple: boolean, value: string[]}} o multiple
   *   default true (false: one node is picked)
   */
  constructor(o) {
    o = o || {};
    this.loadChildren = o.loadChildren || null;
    this.multiple = o.multiple !== false;
    this.byId = new Map();
    this.parent = new Map();
    this.expanded = new Set();
    this.status = new Map();
    this.checked = new Set();
    this.query = '';
    this.found = null;
    this.roots = [];
    this.setNodes(o.nodes || []);
    if (o.value) { this.setValue(o.value); }
  }

  /**
   * Replaces the roots (and everything under them).
   * @param {TreeNode[]} nodes
   * @returns {void}
   */
  setNodes(nodes) {
    this.byId.clear();
    this.parent.clear();
    this.roots = nodes.slice();
    this.index(this.roots, null);
  }

  /** @private */
  index(nodes, parent) {
    nodes.forEach((n) => {
      if (this.byId.has(n.id)) { throw new Error('two nodes have the id ' + n.id); }
      this.byId.set(n.id, n);
      this.parent.set(n.id, parent ? parent.id : null);
      if (n.children) { this.index(n.children, n); }
    });
  }

  /**
   * A node by id.
   * @param {string} id
   * @returns {?TreeNode}
   */
  node(id) { return this.byId.get(id) || null; }

  /**
   * Whether a node has, or may have, children.
   * @param {TreeNode} n
   * @returns {boolean}
   */
  isBranch(n) { return !!((n.children && n.children.length) || (n.hasChildren && !n.children)); }

  /**
   * The ids from the root down to a node's parent.
   * @param {string} id
   * @returns {string[]}
   */
  ancestors(id) { const out = []; let p = this.parent.get(id); while (p) { out.unshift(p); p = this.parent.get(p); } return out; }

  /**
   * The path of labels to a node ("Rates › Swaps").
   * @param {string} id
   * @returns {string}
   */
  pathLabel(id) { return this.ancestors(id).concat([id]).map((x) => this.byId.get(x).label).join(' › '); }

  /**
   * Opens or closes a node; opening an unloaded branch asks the loader for its children.
   * @param {string} id
   * @param {boolean} [open] default: the other way
   * @returns {Promise<void>}
   */
  toggle(id, open) {
    const n = this.byId.get(id);
    if (!n || !this.isBranch(n)) { return Promise.resolve(); }
    const want = open === undefined ? !this.expanded.has(id) : open;
    if (!want) { this.expanded.delete(id); return Promise.resolve(); }
    this.expanded.add(id);
    return this.ensureChildren(n);
  }

  /**
   * Loads a node's children when they are not known yet.
   * @param {TreeNode} n
   * @returns {Promise<void>}
   */
  ensureChildren(n) {
    if (n.children || !n.hasChildren) { return Promise.resolve(); }
    if (!this.loadChildren) { n.children = []; return Promise.resolve(); }
    if (this.status.get(n.id) === 'loading') { return this.pending; }
    this.status.set(n.id, 'loading');
    this.pending = Promise.resolve().then(() => this.loadChildren(n)).then((kids) => {
      n.children = (kids || []).slice();
      this.index(n.children, n);
      this.status.delete(n.id);
    }, (err) => { this.status.set(n.id, 'Could not load: ' + (err && err.message ? err.message : err)); this.expanded.delete(n.id); });
    return this.pending;
  }

  /**
   * A node's state: checked (it or an ancestor is), mixed (something under it is), unchecked.
   * @param {string} id
   * @returns {('checked'|'mixed'|'unchecked')}
   */
  stateOf(id) {
    if (this.checked.has(id) || this.ancestors(id).some((a) => this.checked.has(a))) { return 'checked'; }
    for (const c of this.checked) { if (this.ancestors(c).indexOf(id) >= 0) { return 'mixed'; } }
    return 'unchecked';
  }

  /**
   * Checks or unchecks a node (single: picks it alone). The selection stays compact: a parent whose children are all checked becomes
   * the parent; unchecking under a checked parent checks the parent's other children instead.
   * @param {string} id
   * @param {boolean} [on] default: the other way (a mixed node becomes checked)
   * @returns {void}
   */
  check(id, on) {
    const n = this.byId.get(id);
    if (!n || n.disabled) { return; }
    const want = on === undefined ? this.stateOf(id) !== 'checked' : on;
    if (!this.multiple) { this.checked.clear(); if (want) { this.checked.add(id); } return; }
    const under = (x) => this.ancestors(x).indexOf(id) >= 0;
    Array.from(this.checked).forEach((x) => { if (under(x)) { this.checked.delete(x); } });
    if (want) {
      this.checked.add(id);
      let cur = id;
      for (let p = this.parent.get(cur); p; p = this.parent.get(cur)) {
        const kids = this.byId.get(p).children || [];
        if (!kids.length || !kids.every((k) => this.checked.has(k.id) || k.disabled)) { break; }
        kids.forEach((k) => this.checked.delete(k.id));
        this.checked.add(p);
        cur = p;
      }
      return;
    }
    if (this.checked.delete(id)) { return; }
    const path = this.ancestors(id).concat([id]);
    const top = path.findIndex((a) => this.checked.has(a));
    if (top < 0) { return; }
    this.checked.delete(path[top]);
    for (let i = top; i < path.length - 1; i++) {
      (this.byId.get(path[i]).children || []).forEach((k) => { if (k.id !== path[i + 1]) { this.checked.add(k.id); } });
    }
  }

  /**
   * Checks every root (everything), or nothing.
   * @param {boolean} on
   * @returns {void}
   */
  checkAll(on) { this.checked.clear(); if (on && this.multiple) { this.roots.forEach((r) => { if (!r.disabled) { this.checked.add(r.id); } }); } }

  /**
   * The selection: compact (a checked node stands for everything under it; the default) or the loaded leaves under it.
   * @param {{mode: ('compact'|'leaves')}} [o]
   * @returns {string[]}
   */
  getValue(o) {
    const ids = Array.from(this.checked);
    if (!o || o.mode !== 'leaves') { return ids.sort(); }
    const out = [];
    const walk = (n) => { if (n.children && n.children.length) { n.children.forEach(walk); } else { out.push(n.id); } };
    ids.forEach((id) => walk(this.byId.get(id)));
    return out.sort();
  }

  /**
   * Sets the selection (ids; ids not loaded yet are kept and apply when they load).
   * @param {string[]} ids
   * @returns {void}
   */
  setValue(ids) { this.checked = new Set(ids || []); }

  /**
   * Searches: the loaded nodes whose label or detail holds every word, with their ancestors opened. With a server hook, its answer
   * (nodes with their paths) is merged in first.
   * @param {string} q
   * @param {function(string): Promise<Array<{node: TreeNode, path: TreeNode[]}>>} [server]
   * @returns {Promise<number>} how many matched
   */
  search(q, server) {
    this.query = String(q || '').trim();
    if (!this.query) { this.found = null; return Promise.resolve(0); }
    const words = this.query.toLowerCase().split(/\s+/);
    const run = () => {
      const hit = new Set();
      this.byId.forEach((n, id) => { const t = (n.label + ' ' + (n.detail || '')).toLowerCase(); if (words.every((w) => t.indexOf(w) >= 0)) { hit.add(id); } });
      const keep = new Set(hit);
      hit.forEach((id) => this.ancestors(id).forEach((a) => { keep.add(a); this.expanded.add(a); }));
      this.found = { hit: hit, keep: keep };
      return hit.size;
    };
    if (!server) { return Promise.resolve(run()); }
    const asked = this.query;
    return Promise.resolve(server(asked)).then((found) => {
      if (asked !== this.query) { return this.found ? this.found.hit.size : 0; }
      (found || []).forEach((f) => {
        let parent = null;
        (f.path || []).concat([f.node]).forEach((p) => {
          if (!this.byId.has(p.id)) {
            const holder = parent ? this.byId.get(parent) : null;
            const entry = Object.assign({}, p, { children: p === f.node ? p.children : [] });
            if (holder) { holder.children = (holder.children || []).concat([entry]); } else { this.roots.push(entry); }
            this.byId.set(entry.id, entry);
            this.parent.set(entry.id, parent);
          }
          parent = p.id;
        });
      });
      return run();
    });
  }

  /**
   * The visible rows, in order: the roots and the children of open nodes (only the search's matches and their ancestors while searching).
   * @returns {TreeRow[]}
   */
  rows() {
    const out = [];
    const walk = (nodes, depth) => nodes.forEach((n) => {
      if (this.found && !this.found.keep.has(n.id)) { return; }
      const branch = this.isBranch(n), open = branch && this.expanded.has(n.id);
      out.push({ node: n, depth: depth, branch: branch, expanded: open, state: this.stateOf(n.id), status: this.status.get(n.id) || null, match: !!(this.found && this.found.hit.has(n.id)) });
      if (open && n.children) { walk(n.children, depth + 1); }
    });
    walk(this.roots, 0);
    return out;
  }

  /**
   * The selection in words: "Rates, FX › Spot" or "3 selected" past a few; "Everything" when every root is checked.
   * @param {{max: number, none: string}} [o] max: names shown before a count (default 2)
   * @returns {string}
   */
  summary(o) {
    o = o || {};
    const ids = this.getValue();
    if (!ids.length) { return o.none || 'None'; }
    if (this.multiple && this.roots.length && this.roots.every((r) => this.checked.has(r.id) || r.disabled)) { return 'Everything'; }
    const names = ids.map((id) => (this.byId.has(id) ? this.pathLabel(id) : id));
    const max = o.max || 2;
    return names.length <= max ? names.join(', ') : names.slice(0, max).join(', ') + ' and ' + (names.length - max) + ' more';
  }
}
