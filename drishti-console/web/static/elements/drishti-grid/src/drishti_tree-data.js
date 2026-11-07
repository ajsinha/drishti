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
/* Tree data for the client-side row model: each row names its place with a path (getDataPath(row): ['BANK', 'RATES', 'IRS-1']), and
   the tree is made from the paths. A node a row names carries that row; a node no row names (an intermediate level) is made from the
   paths below it and carries figures as a group does: from its rows by the column's aggregation, or left for the server (a
   non-additive column, or every column when clientAggregation is off: the console's policy). Siblings keep the sort order; a filter
   keeps a matching row with every node above it. Nodes open by path (setExpanded) or by level (groupDefaultExpanded). */
import { aggFunc } from './drishti_aggregate.js';
import { SEP } from './drishti_pivot.js';

/**
 * A tree node.
 * @typedef {Object} TreeNode
 * @property {number} id
 * @property {string[]} path the keys from the top
 * @property {number} depth path length less one
 * @property {number} row the row number it carries, or -1 for a node no row names
 * @property {TreeNode[]} children
 * @property {Object<string, *>} aggregates a node without a row: its figures by column id
 * @property {string[]} serverCols the columns whose figures are the server's
 * @property {string[]} pendingServer those still waiting
 */

/**
 * Builds the tree over the rows in sort order, keeping the rows that pass the filter and every node above them.
 * @param {Object} model the ClientRowModel (rows, columns, getDataPath, clientAggregation, value)
 * @param {Uint32Array} order every row number, sorted
 * @param {?function(Object): boolean} test the filter (null: every row)
 * @returns {{nodes: TreeNode[], root: TreeNode, byKey: Map<string, TreeNode>, view: Uint32Array}} view: the rows kept, in tree order
 */
export function buildTree(model, order, test) {
  const nodes = [], byKey = new Map(), root = { id: -1, path: [], depth: -1, row: -1, children: [], keep: false };
  const node = (path) => {
    const key = path.join(SEP);
    let n = byKey.get(key);
    if (n) { return n; }
    const parent = path.length > 1 ? node(path.slice(0, -1)) : root;
    n = { id: nodes.length, path: path, depth: path.length - 1, row: -1, children: [], keep: false, aggregates: {}, serverCols: [], pendingServer: [] };
    nodes.push(n);
    byKey.set(key, n);
    parent.children.push(n);
    return n;
  };
  for (let i = 0; i < order.length; i++) {
    const r = order[i], p = model.getDataPath(model.rows[r]);
    if (!Array.isArray(p) || !p.length) { throw new Error('tree data: getDataPath gave no path for row ' + r); }
    const n = node(p.map(String));
    if (n.row >= 0) { throw new Error('tree data: two rows have the path ' + p.join(' / ')); }
    n.row = r;
  }
  const keep = (n) => { let k = n.row >= 0 && (!test || test(model.rows[n.row])); n.children.forEach((c) => { if (keep(c)) { k = true; } }); n.keep = k; return k; };
  root.children.forEach(keep);
  const prune = (n) => { n.children = n.children.filter((c) => c.keep); n.children.forEach(prune); };
  prune(root);
  const view = [];
  const walk = (n) => { if (n.row >= 0) { view.push(n.row); } n.children.forEach(walk); };
  root.children.forEach(walk);
  figures(model, nodes.filter((n) => n.keep));
  return { nodes: nodes, root: root, byKey: byKey, view: Uint32Array.from(view) };
}

/** A node without a row: its figures from the rows under it (kept ones), by each value column's aggregation, or the server's. */
function figures(model, nodes) {
  const valueCols = model.columns.all().filter((c) => c.aggFunc && c.type === 'number');
  const rowsUnder = (n, out) => { if (n.row >= 0) { out.push(n.row); } n.children.forEach((c) => rowsUnder(c, out)); return out; };
  nodes.forEach((n) => {
    if (n.row >= 0 || !n.children.length) { return; }
    const rows = [];
    n.children.forEach((c) => rowsUnder(c, rows));
    valueCols.forEach((c) => {
      const f = aggFunc(c.aggFunc);
      if (c.additive === false || !f || !model.clientAggregation) { n.aggregates[c.id] = null; n.serverCols.push(c.id); return; }
      let st = f.init();
      for (let j = 0; j < rows.length; j++) { st = f.add(st, model.value(model.rows[rows[j]], c)); }
      n.aggregates[c.id] = f.result(st);
    });
    n.pendingServer = n.serverCols.slice();
  });
}

/**
 * The display codes of the tree: -(node id + 1) for each node shown (a node shows when every node above it is open).
 * @param {Object} model the ClientRowModel (tree, isExpanded)
 * @returns {Int32Array}
 */
export function flattenTree(model) {
  const out = [];
  const walk = (n) => { out.push(-(n.id + 1)); if (n.children.length && model.isExpanded(n.path)) { n.children.forEach(walk); } };
  model.tree.root.children.forEach(walk);
  return Int32Array.from(out);
}

/**
 * A tree node as a display row: a node with children is a group (expandable; its row, if it has one, is its data), a node without
 * is a leaf; treeKey is the node's own key (the tree column shows it).
 * @param {Object} model the ClientRowModel
 * @param {TreeNode} n
 * @returns {Object} a DisplayRow with tree: true and treeKey
 */
export function treeRow(model, n) {
  const row = n.row >= 0 ? model.rows[n.row] : null, key = n.path[n.path.length - 1];
  if (!n.children.length) { return { kind: 'leaf', tree: true, treeKey: key, id: model.getRowId(row, n.row), depth: n.depth, row: row, index: n.row, path: n.path }; }
  return { kind: 'group', tree: true, treeKey: key, id: row ? model.getRowId(row, n.row) : 'group:' + n.path.join(SEP), depth: n.depth, row: row, index: n.row >= 0 ? n.row : undefined,
    field: null, value: key, path: n.path, expanded: model.isExpanded(n.path), aggregates: row ? null : n.aggregates, pendingServer: row ? [] : n.pendingServer };
}
