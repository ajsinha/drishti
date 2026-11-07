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
/* drishti-tree's engine: one hierarchy, whatever draws it (the tree grid, the org chart, the sunburst, the icicle). It reads a hierarchy
   in any of three shapes (rows naming their parent, nested children, or each row's path from the top), keeps which nodes are open,
   finds nodes with their breadcrumbs, and holds each node's measures. An additive measure (a count, a notional) may roll up from the
   children when a node has none of its own; a measure marked additive: false (a VaR, an ES, a ratio) is NEVER computed here: a node
   without the figure waits for the host's answer (requests(), setValues()), and the children's figures added up are offered only
   beside it, labelled as what they are (the gap is the diversification), never in its place. A node may name further parents (a DAG:
   the org chart draws them as dashed links). Re-parenting in the edit mode collects moves into one proposal: shown at once as a draft,
   undone or discarded by the person, and applied only when the host says the proposal was approved (four eyes). No page needed. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';

/**
 * A measure of the nodes.
 * @typedef {Object} TreeMeasure
 * @property {string} field the node's property
 * @property {string} [label] its heading (default: the field)
 * @property {(string|Object|function(*): string)} [format] a drishti-core named format (compact, amount0, pct1 ...), money, percent ...
 * @property {boolean} [additive=true] false: never summed here (◆); a node's figure is the host's (TreeOptions.measures hook)
 * @property {boolean} [rollup=true] an additive measure: a node with no figure of its own shows its children's sum
 * @property {boolean} [childSum=false] a non-additive measure: show the children's figures added up beside it (Σ children, not a figure
 *   of the measure: the gap is the diversification)
 * @property {{bands: Array<{to: number, tone: string}>, max: number}} [bar] draw the figure as a bar (a ratio such as a limit's use):
 *   its tone by the first band whose `to` it stays under; max the full bar (default 1)
 * @property {Object} [column] drishti-grid ColumnDef properties for its column in the tree grid (header, width, format (value, row),
 *   cellClass, cellRenderer: drawn where the tree draws nothing of its own, the waiting mark and the bar)
 */

/**
 * A node of the hierarchy.
 * @typedef {Object} TreeNode
 * @property {string} id
 * @property {string} label
 * @property {string} kind its type (a level's name: division, desk, book ...)
 * @property {?TreeNode} parent
 * @property {TreeNode[]} children
 * @property {string[]} also further parents' ids (a DAG), shown, never walked
 * @property {number} depth 0 at the root
 * @property {Object<string, *>} values the measures' figures by field (the data's, the host's answers and the roll-ups)
 * @property {Object} data the row it was read from
 * @property {boolean} moved part of the draft proposal
 * @property {boolean} lazy its children come later, from the host (TreeOptions.loadChildren), the first time it opens
 * @property {?string} lazyError why its children did not come (shown in their place)
 */

/**
 * How a hierarchy is read.
 * @typedef {Object} HierarchySource
 * @property {Object[]} [rows] rows naming their parent (parentField), or each with a path (pathField or getPath)
 * @property {Object} [root] nested: a node with its children (childrenField)
 * @property {string} [idField='id']
 * @property {string} [parentField='parent']
 * @property {string} [labelField='label']
 * @property {string} [kindField='kind']
 * @property {string} [childrenField='children']
 * @property {string} [alsoField='also'] further parents' ids (a DAG)
 * @property {function(Object): string[]} [getPath] a row's path of ids or names from the top (instead of parentField)
 * @property {string} [lazyField='lazy'] a row's field that is true when the node has children the host gives later (addChildren)
 * @property {TreeMeasure[]} [measures]
 */

const SEP = '\u001f';

/**
 * The hierarchy: nodes, open state, search, measures (additive roll-ups; non-additive figures from the host) and the edit draft.
 */
export class HierarchyModel {
  /** @param {HierarchySource} o */
  constructor(o) {
    this.o = Object.assign({ idField: 'id', parentField: 'parent', labelField: 'label', kindField: 'kind', childrenField: 'children', alsoField: 'also', lazyField: 'lazy' }, o);
    /** @type {EventBus} changed {what: 'data'|'open'|'values'|'draft'} */
    this.events = new EventBus();
    this.measures = (o.measures || []).map((m) => Object.assign({ label: m.field, additive: true, rollup: true, childSum: false }, m));
    this.open = new Set();
    /** @type {Array<{id: string, from: string, to: string}>} the draft's moves, in order */
    this.moves = [];
    this.load(o);
  }

  /**
   * Reads the hierarchy again (the open state is kept for the ids still there).
   * @param {HierarchySource} src rows, root, or getPath (the field names of the first reading are kept)
   * @returns {void}
   */
  load(src) {
    const o = this.o, byId = new Map(), roots = [];
    const make = (row, id, name) => this.makeNode(byId, row, id, name);
    if (src.root) {
      const walk = (row, parent) => {
        const n = make(row, row[o.idField]);
        if (parent) { n.parent = parent; parent.children.push(n); } else { roots.push(n); }
        (row[o.childrenField] || []).forEach((c) => walk(c, n));
      };
      walk(src.root, null);
    } else if (src.getPath || o.getPath) {
      const gp = src.getPath || o.getPath;
      (src.rows || []).forEach((row) => {
        const p = gp(row).map(String);
        if (!p.length) { throw new Error('tree: a row has no path'); }
        let parent = null;
        p.forEach((key, i) => {
          const id = p.slice(0, i + 1).join('/');
          let n = byId.get(id);
          if (!n) { n = make(i === p.length - 1 ? row : {}, id, key); if (parent) { n.parent = parent; parent.children.push(n); } else { roots.push(n); } }
          else if (i === p.length - 1) { n.data = row; n.label = String(row[o.labelField] != null ? row[o.labelField] : key); n.kind = row[o.kindField] != null ? String(row[o.kindField]) : n.kind;
            this.measures.forEach((m) => { if (row[m.field] !== undefined && row[m.field] !== null) { n.values[m.field] = row[m.field]; } }); }
          parent = n;
        });
      });
    } else {
      const rows = src.rows || [];
      rows.forEach((row) => make(row, row[o.idField]));
      rows.forEach((row) => {
        const n = byId.get(String(row[o.idField])), pid = row[o.parentField];
        if (pid === undefined || pid === null || pid === '') { roots.push(n); return; }
        const p = byId.get(String(pid));
        if (!p) { throw new Error('tree: ' + n.id + ' names a parent ' + pid + ' that is not there'); }
        n.parent = p;
        p.children.push(n);
      });
      roots.forEach((r) => { const seen = new Set(); const walk = (n) => { if (seen.has(n.id)) { throw new Error('tree: a cycle at ' + n.id); } seen.add(n.id); n.children.forEach(walk); }; walk(r); });
      if (roots.length === 0 && rows.length) { throw new Error('tree: every node has a parent (a cycle): no root'); }
    }
    this.byId = byId;
    this.roots = roots;
    this.moves = [];
    this.reindex();
    const keep = new Set();
    this.open.forEach((id) => { if (byId.has(id)) { keep.add(id); } });
    this.open = keep;
    if (!keep.size) { roots.forEach((r) => this.open.add(r.id)); }
    this.events.emit('changed', { what: 'data' });
  }

  /** @private a node read from its row, kept by its id */
  makeNode(byId, row, id, name) {
    const o = this.o;
    const n = { id: String(id), label: String(row[o.labelField] != null ? row[o.labelField] : (name !== undefined ? name : id)), kind: row[o.kindField] != null ? String(row[o.kindField]) : '',
      parent: null, children: [], also: (row[o.alsoField] || []).map(String), depth: 0, values: {}, data: row, moved: false, lazy: !!row[o.lazyField], lazyError: null };
    this.measures.forEach((m) => { if (row[m.field] !== undefined && row[m.field] !== null) { n.values[m.field] = row[m.field]; } });
    if (byId.has(n.id)) { throw new Error('tree: two nodes have the id ' + n.id); }
    byId.set(n.id, n);
    return n;
  }

  /**
   * The children the host gave for a node read as lazy (lazyField): rows with their ids, each under the node or under another of
   * these rows named by its parent field; a row may be lazy in turn. The open state is kept.
   * @param {string} id
   * @param {Object[]} rows
   * @returns {number} how many nodes were added
   */
  addChildren(id, rows) {
    const p = this.node(id), o = this.o;
    if (!p) { return 0; }
    const fresh = new Map();
    (rows || []).forEach((row) => { const n = this.makeNode(this.byId, row, row[o.idField]); fresh.set(n.id, n); });
    (rows || []).forEach((row) => {
      const n = fresh.get(String(row[o.idField])), q = fresh.get(String(row[o.parentField])) || p;
      n.parent = q;
      q.children.push(n);
      q.lazy = false;                                  // a row given with its children has none to come
    });
    p.lazy = false;
    p.lazyError = null;
    this.reindex();
    this.events.emit('changed', { what: 'data', id: p.id });
    return fresh.size;
  }

  /**
   * The host could not give a lazy node's children: the words shown in their place (not asked again until the data is read again).
   * @param {string} id
   * @param {string} why
   * @returns {void}
   */
  childrenFailed(id, why) { const n = this.node(id); if (n) { n.lazyError = String(why || 'failed'); this.events.emit('changed', { what: 'data', id: n.id }); } }

  /**
   * Whether a node has children, or children to come (lazy).
   * @param {string} id
   * @returns {boolean}
   */
  hasChildren(id) { const n = this.node(id); return !!n && (n.children.length > 0 || n.lazy); }

  /** @private depths and the roll-ups again */
  reindex() {
    const walk = (n, d) => { n.depth = d; n.children.forEach((c) => walk(c, d + 1)); };
    this.roots.forEach((r) => walk(r, 0));
    this.rollup();
  }

  /** @private the additive measures a node lacks: its children's sum (deepest first) */
  rollup() {
    const add = this.measures.filter((m) => m.additive !== false && m.rollup !== false);
    if (!add.length) { return; }
    const walk = (n) => {
      n.children.forEach(walk);
      if (!n.children.length) { return; }
      add.forEach((m) => {
        if (n.data && n.data[m.field] !== undefined && n.data[m.field] !== null && !n.moved && !n.children.some((c) => c.moved)) { n.values[m.field] = n.data[m.field]; return; }
        let s = 0, any = false;
        n.children.forEach((c) => { const v = c.values[m.field]; if (typeof v === 'number') { s += v; any = true; } });
        n.values[m.field] = any ? s : null;
      });
    };
    this.roots.forEach(walk);
  }

  // ---- reading ----------------------------------------------------------------------------------------------------------------------

  /** A node by its id. @param {string} id @returns {?TreeNode} */
  node(id) { return this.byId.get(String(id)) || null; }

  /** Every node, depth first. @returns {TreeNode[]} */
  all() { const out = []; const walk = (n) => { out.push(n); n.children.forEach(walk); }; this.roots.forEach(walk); return out; }

  /** The number of nodes. @returns {number} */
  size() { return this.byId.size; }

  /**
   * The nodes from the root down to this one.
   * @param {string} id
   * @returns {TreeNode[]}
   */
  pathOf(id) { const out = []; let n = this.node(id); while (n) { out.unshift(n); n = n.parent; } return out; }

  /**
   * The breadcrumbs of a node: the labels from the root.
   * @param {string} id
   * @returns {string[]}
   */
  breadcrumbs(id) { return this.pathOf(id).map((n) => n.label); }

  /**
   * Whether a node is under another (or is it).
   * @param {string} id
   * @param {string} ancestor
   * @returns {boolean}
   */
  isUnder(id, ancestor) { let n = this.node(id); while (n) { if (n.id === String(ancestor)) { return true; } n = n.parent; } return false; }

  /** A measure by its field. @param {string} field @returns {?TreeMeasure} */
  measure(field) { return this.measures.find((m) => m.field === field) || null; }

  /**
   * A node's figure of a measure: its own, the host's, or (additive) its children's sum; null while a non-additive figure waits.
   * @param {string} id
   * @param {string} field
   * @returns {*}
   */
  value(id, field) { const n = this.node(id); return n && n.values[field] !== undefined ? n.values[field] : null; }

  /**
   * Whether a node's non-additive figure is still waiting for the host.
   * @param {string} id
   * @param {string} field
   * @returns {boolean}
   */
  isPending(id, field) {
    const n = this.node(id), m = this.measure(field);
    return !!n && !!m && m.additive === false && (n.values[field] === undefined || n.values[field] === null) && !!this.o.asksHost;
  }

  /**
   * The children's figures of a non-additive measure added up (null unless every child has one): shown beside the node's own figure,
   * never as it.
   * @param {string} id
   * @param {string} field
   * @returns {?number}
   */
  childSum(id, field) {
    const n = this.node(id);
    if (!n || !n.children.length) { return null; }
    let s = 0;
    for (const c of n.children) { const v = c.values[field]; if (typeof v !== 'number') { return null; } s += v; }
    return s;
  }

  // ---- the host's figures -------------------------------------------------------------------------------------------------------------

  /**
   * What the host must give: every node without a figure of a non-additive measure, with its path of ids.
   * @param {string[]} [ids] only these nodes (default: all)
   * @returns {Array<{id: string, path: string[], fields: string[]}>}
   */
  requests(ids) {
    const fields = this.measures.filter((m) => m.additive === false).map((m) => m.field);
    if (!fields.length) { return []; }
    const nodes = ids ? ids.map((i) => this.node(i)).filter(Boolean) : this.all();
    return nodes.map((n) => ({ id: n.id, path: this.pathOf(n.id).map((x) => x.id), fields: fields.filter((f) => n.values[f] === undefined || n.values[f] === null) }))
      .filter((r) => r.fields.length);
  }

  /**
   * Forgets the non-additive figures of some nodes (they changed: a move, a live update) so they are asked again.
   * @param {string[]} ids
   * @returns {void}
   */
  invalidate(ids) {
    const fields = this.measures.filter((m) => m.additive === false).map((m) => m.field);
    ids.forEach((id) => { const n = this.node(id); if (n) { fields.forEach((f) => { delete n.values[f]; }); } });
  }

  /**
   * Puts the host's answers in (the figures of non-additive measures; any other field too).
   * @param {Array<{id: string, values: Object<string, *>}>} answers
   * @returns {number} how many nodes took figures
   */
  setValues(answers) {
    let k = 0;
    (answers || []).forEach((a) => { const n = this.node(a.id); if (n && a.values) { Object.assign(n.values, a.values); k++; } });
    if (k) { this.rollup(); this.events.emit('changed', { what: 'values' }); }
    return k;
  }

  // ---- open and closed ------------------------------------------------------------------------------------------------------------------

  /** Whether a node is open. @param {string} id @returns {boolean} */
  isOpen(id) { return this.open.has(String(id)); }

  /**
   * Opens or closes a node (default: the other state).
   * @param {string} id
   * @param {boolean} [open]
   * @returns {boolean} whether it is open now
   */
  toggle(id, open) {
    const k = String(id), now = open === undefined ? !this.open.has(k) : !!open;
    if (now) { this.open.add(k); } else { this.open.delete(k); }
    this.events.emit('changed', { what: 'open', id: k, open: now });
    return now;
  }

  /**
   * Opens every node (under one, or everywhere), or down to a depth.
   * @param {{under: string, depth: number}} [o]
   * @returns {void}
   */
  expandAll(o) {
    const under = o && o.under ? this.node(o.under) : null, depth = o && o.depth !== undefined ? o.depth : Infinity;
    const walk = (n) => { if (n.children.length && n.depth < depth) { this.open.add(n.id); n.children.forEach(walk); } };
    (under ? [under] : this.roots).forEach(walk);
    this.events.emit('changed', { what: 'open' });
  }

  /** Closes every node but the roots. @returns {void} */
  collapseAll() { this.open.clear(); this.roots.forEach((r) => this.open.add(r.id)); this.events.emit('changed', { what: 'open' }); }

  /**
   * Opens the nodes above one so it shows.
   * @param {string} id
   * @returns {void}
   */
  reveal(id) { this.pathOf(id).slice(0, -1).forEach((n) => this.open.add(n.id)); this.events.emit('changed', { what: 'open' }); }

  /**
   * The nodes shown (each node whose ancestors are all open), depth first.
   * @returns {TreeNode[]}
   */
  visible() { const out = []; const walk = (n) => { out.push(n); if (this.open.has(n.id)) { n.children.forEach(walk); } }; this.roots.forEach(walk); return out; }

  // ---- search -------------------------------------------------------------------------------------------------------------------------

  /**
   * Finds the nodes whose label, id or kind holds the words (each word, any case), and opens their branches.
   * @param {string} q
   * @param {{open: boolean, limit: number}} [o] open default true
   * @returns {Array<{id: string, label: string, breadcrumbs: string[]}>}
   */
  search(q, o) {
    const words = String(q || '').toLowerCase().split(/\s+/).filter(Boolean);
    if (!words.length) { return []; }
    const limit = (o && o.limit) || Infinity, out = [];
    for (const n of this.all()) {
      const hay = (n.label + ' ' + n.id + ' ' + n.kind).toLowerCase();
      if (words.every((w) => hay.indexOf(w) >= 0)) { out.push({ id: n.id, label: n.label, breadcrumbs: this.breadcrumbs(n.id) }); if (out.length >= limit) { break; } }
    }
    if (!o || o.open !== false) { out.forEach((m) => this.pathOf(m.id).slice(0, -1).forEach((n) => this.open.add(n.id))); if (out.length) { this.events.emit('changed', { what: 'open' }); } }
    return out;
  }

  // ---- the edit draft (four eyes) ---------------------------------------------------------------------------------------------------------

  /**
   * Why a node cannot move under another, in words (null: it can).
   * @param {string} id
   * @param {string} to
   * @returns {?string}
   */
  moveProblem(id, to) {
    const n = this.node(id), t = this.node(to);
    if (!n || !t) { return 'No such node'; }
    if (!n.parent) { return n.label + ' is the top of the tree: it has no parent to change'; }
    if (n === t) { return 'A node cannot move under itself'; }
    if (n.parent === t) { return n.label + ' is already under ' + t.label; }
    if (this.isUnder(t.id, n.id)) { return 'A node cannot move under its own descendant (' + t.label + ' is under ' + n.label + ')'; }
    return null;
  }

  /**
   * Moves a node under a new parent in the draft (shown at once, marked proposed; nothing is sent).
   * @param {string} id
   * @param {string} to
   * @returns {{id: string, from: string, to: string}} the move
   */
  move(id, to) {
    const why = this.moveProblem(id, to);
    if (why) { throw new Error(why); }
    const n = this.node(id), from = n.parent, t = this.node(to);
    from.children.splice(from.children.indexOf(n), 1);
    n.parent = t;
    t.children.push(n);
    n.moved = true;
    const mv = { id: n.id, from: from.id, to: t.id };
    this.moves.push(mv);
    this.open.add(t.id);
    this.afterMove([from.id, t.id]);
    return mv;
  }

  /**
   * Undoes the draft's last move.
   * @returns {?{id: string, from: string, to: string}} the move undone
   */
  undoMove() {
    const mv = this.moves.pop();
    if (!mv) { return null; }
    const n = this.node(mv.id), t = this.node(mv.to), f = this.node(mv.from);
    t.children.splice(t.children.indexOf(n), 1);
    n.parent = f;
    f.children.push(n);
    n.moved = this.moves.some((m) => m.id === mv.id);
    this.afterMove([f.id, t.id]);
    return mv;
  }

  /**
   * The draft as one proposal for review: its moves with each node's label and breadcrumbs before and after.
   * @returns {{moves: Array<{id: string, label: string, from: string, to: string, fromPath: string[], toPath: string[]}>}}
   */
  proposal() {
    return { moves: this.moves.map((m) => ({ id: m.id, label: this.node(m.id).label, from: m.from, to: m.to,
      fromPath: this.breadcrumbs(m.from), toPath: this.breadcrumbs(m.to) })) };
  }

  /** Undoes every move of the draft. @returns {number} how many */
  discardMoves() { let k = 0; while (this.undoMove()) { k++; } return k; }

  /** The draft became the hierarchy (the host says it was approved): the marks go. @returns {number} how many moves */
  commitMoves() {
    const k = this.moves.length;
    this.moves = [];
    this.all().forEach((n) => { n.moved = false; });
    this.reindex();
    this.events.emit('changed', { what: 'draft' });
    return k;
  }

  /** @private the depths, roll-ups and the moved nodes' ancestors' figures again */
  afterMove(ids) {
    const touched = new Set();
    ids.forEach((id) => this.pathOf(id).forEach((n) => touched.add(n.id)));
    this.invalidate(Array.from(touched));
    this.reindex();
    this.events.emit('changed', { what: 'draft', touched: Array.from(touched) });
  }

  // ---- export ---------------------------------------------------------------------------------------------------------------------------

  /**
   * The hierarchy nested, as JSON data (id, label, kind, the measures' figures, children).
   * @returns {Object[]}
   */
  toJSON() {
    const j = (n) => ({ id: n.id, label: n.label, kind: n.kind, values: Object.assign({}, n.values), children: n.children.map(j) });
    return this.roots.map(j);
  }

  /**
   * One row per node (the path, id, kind and every measure), for drishti-core's toCsv.
   * @returns {import('../../drishti-core/src/drishti_files.js').ExportTable}
   */
  toTable() {
    const ms = this.measures;
    return { columns: [{ header: 'Path', type: 'text' }, { header: 'Id', type: 'text' }, { header: 'Kind', type: 'text' }].concat(ms.map((m) => ({ header: m.label, type: 'number', format: typeof m.format === 'string' ? m.format : undefined }))),
      rows: this.all().map((n) => [this.breadcrumbs(n.id).join(' / '), n.id, n.kind].concat(ms.map((m) => (n.values[m.field] === undefined ? null : n.values[m.field])))),
      levels: this.all().map((n) => n.depth) };
  }
}

/** The separator of a path's keys inside the library. */
export const PATH_SEP = SEP;
