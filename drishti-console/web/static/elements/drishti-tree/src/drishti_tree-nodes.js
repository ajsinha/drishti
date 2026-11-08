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
/* An outline's nested nodes (a designer's structure: pages and their visuals, sections and their panels) as plain data: the rows shown,
   where a node is, whether one is inside another, a move before, after or into another as a new tree, and the nodes as an outline's
   hierarchy (TreeOutline's nodes and setNodes). Pure functions, no DOM. */
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
export function rowDataset(attrs) {
  const out = {};
  Object.keys(attrs || {}).forEach((k) => {
    if (/^data-(?!node$)[a-z][\w-]*$/.test(k) && attrs[k] !== null && attrs[k] !== undefined) { out[k.slice(5).replace(/-([a-z])/g, (m, c) => c.toUpperCase())] = String(attrs[k]); }
  });
  return out;
}
