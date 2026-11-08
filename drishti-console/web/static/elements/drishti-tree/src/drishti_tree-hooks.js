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
/* What a host adds to a tree without a second tree of its own: its look in the tree grid (TreeOptions.grid: the node's cell, more
   columns, a row's class and data-* attributes, the column widths given and kept, the grid as tall as its rows) and children that come
   later (TreeOptions.loadChildren): a node read as lazy asks the host for its children the first time it is open, its waiting row shows
   until they come (or why they did not), and they join the model as nodes of their own (HierarchyModel.addChildren). */

/**
 * The tree grid's hooks for a host (TreeOptions.grid).
 * @typedef {Object} TreeGridHooks
 * @property {Object} [labelColumn] drishti-grid ColumnDef properties for the node's column (header, width, pinned, cellRenderer given
 *   {value, text, row, doc}: the row carries the node's own row's fields)
 * @property {Object[]} [columns] more drishti-grid ColumnDefs after the measures', reading the node's own row's fields (valueGetter,
 *   format (value, row), cellClass, cellRenderer); left empty on a waiting row
 * @property {function(Object): ?string} [rowClass] more classes for a node's row, from the node's own row (and __id, the node's id)
 * @property {function(Object): ?Object<string, *>} [rowData] data-* attributes for a node's row ({key: 'A-1'}: data-key)
 * @property {Object<string, number>} [widths] column widths by column id (the node's column is __label, a measure's its field)
 * @property {function(Object<string, number>): void} [onResize] a column was resized: every column's width by id (for the host to keep)
 * @property {number} [fitRows] the grid as tall as its rows, up to this many (then it scrolls)
 * @property {number} [phoneWidth] below this width the grid is drawn for a phone (drishti-grid's; 1: never, a narrow panel is not a phone)
 */

/**
 * What the host's loadChildren hook is given.
 * @typedef {Object} TreeChildrenRequest
 * @property {string} id the node's id
 * @property {string} label
 * @property {string} kind
 * @property {string[]} path the ids from the top
 * @property {Object} data the node's own row
 */

/**
 * Asks the host for the children of every open lazy node not asked yet (TreeOptions.loadChildren); each answer joins the model, a
 * failure is shown in the waiting row. A node read again (new data) while its answer is out is asked again.
 * @param {Object} tree the Tree (its model, o.loadChildren and the map of the answers out)
 * @returns {number} how many were asked now
 */
export function loadOpenChildren(tree) {
  const m = tree.model, ask = tree.o.loadChildren;
  if (!ask) { return 0; }
  const out = tree.asking || (tree.asking = new Map());
  let k = 0;
  m.open.forEach((id) => {
    const n = m.node(id);
    if (!n || !n.lazy || n.lazyError || out.get(id) === n) { return; }
    out.set(id, n);
    k++;
    const done = () => { if (out.get(id) === n) { out.delete(id); } return m.node(id) === n; };
    Promise.resolve().then(() => ask({ id: n.id, label: n.label, kind: n.kind, path: m.pathOf(n.id).map((x) => x.id), data: n.data }))
      .then((rows) => { if (done()) { m.addChildren(id, rows || []); } }, (e) => { if (done()) { m.childrenFailed(id, e && e.message ? e.message : e); } });
  });
  return k;
}
