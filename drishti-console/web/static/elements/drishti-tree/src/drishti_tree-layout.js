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
/* drishti-tree's geometry, pure functions (Node runs them): the org chart's tidy layout (each leaf in its own column, a parent centred
   over its first and last child, closed nodes drawn without their children; further parents of a DAG as extra links), the partition
   of the sunburst and the icicle (an ADDITIVE measure only: a node's share of its parent's angle or width; a non-additive one is
   refused in words, since its parts do not add up to the whole), an arc's path, and the tone of a node's bar by its status. */

/**
 * The org chart's sizes.
 * @typedef {Object} OrgSizes
 * @property {number} [boxW=176] a box's width
 * @property {number} [boxH=74] a box's height
 * @property {number} [gapX=18] between boxes side by side
 * @property {number} [gapY=46] between levels
 */

/**
 * The org chart's layout of the nodes shown.
 * @param {import('./drishti_tree-model.js').HierarchyModel} model
 * @param {OrgSizes} [o]
 * @returns {{boxes: Array<{id: string, x: number, y: number, w: number, h: number, depth: number}>, links: Array<{from: string, to: string, shared: boolean, d: string}>, width: number, height: number}}
 */
export function orgLayout(model, o) {
  const s = Object.assign({ boxW: 176, boxH: 74, gapX: 18, gapY: 46 }, o);
  const pos = new Map();
  let col = 0, deepest = 0;
  const place = (n) => {
    deepest = Math.max(deepest, n.depth);
    const kids = model.isOpen(n.id) ? n.children : [];
    if (!kids.length) { pos.set(n.id, { x: col * (s.boxW + s.gapX), depth: n.depth }); col++; return; }
    kids.forEach(place);
    const a = pos.get(kids[0].id).x, b = pos.get(kids[kids.length - 1].id).x;
    pos.set(n.id, { x: (a + b) / 2, depth: n.depth });
  };
  model.roots.forEach(place);
  const boxes = [], links = [];
  pos.forEach((p, id) => boxes.push({ id: id, x: p.x, y: p.depth * (s.boxH + s.gapY), w: s.boxW, h: s.boxH, depth: p.depth }));
  const at = (id) => pos.get(id);
  const link = (from, to, shared) => {
    const a = at(from), b = at(to);
    if (!a || !b) { return; }
    const x1 = a.x + s.boxW / 2, y1 = a.depth * (s.boxH + s.gapY) + s.boxH, x2 = b.x + s.boxW / 2, y2 = b.depth * (s.boxH + s.gapY), my = (y1 + y2) / 2;
    links.push({ from: from, to: to, shared: shared, d: 'M' + r1(x1) + ',' + r1(y1) + 'C' + r1(x1) + ',' + r1(my) + ' ' + r1(x2) + ',' + r1(my) + ' ' + r1(x2) + ',' + r1(y2) });
  };
  boxes.forEach((b) => {
    const n = model.node(b.id);
    if (n.parent && pos.has(n.parent.id)) { link(n.parent.id, n.id, false); }
    n.also.forEach((pid) => { if (pos.has(pid)) { link(pid, n.id, true); } });
  });
  return { boxes: boxes, links: links, width: Math.max(s.boxW, col * (s.boxW + s.gapX) - s.gapX), height: (deepest + 1) * (s.boxH + s.gapY) - s.gapY };
}

function r1(v) { return Math.round(v * 10) / 10; }

/**
 * A slice of the partition: a node's share of the whole, from a0 up to (not including) a1, (0 to 1) and its ring (depth below the focus).
 * @typedef {Object} TreeSlice
 * @property {string} id
 * @property {number} a0
 * @property {number} a1
 * @property {number} depth
 * @property {number} value the node's size (its leaves' figures added up)
 */

/**
 * The partition of the sunburst and the icicle: each node's share of its parent by an additive measure, from a focus node down.
 * @param {import('./drishti_tree-model.js').HierarchyModel} model
 * @param {{sizeBy: string, focus: string, depth: number}} o sizeBy: the additive measure; focus: the centre (default the first root);
 *   depth: rings drawn below it (default 4)
 * @returns {TreeSlice[]}
 */
export function partition(model, o) {
  const m = model.measure(o.sizeBy);
  if (!m) { throw new Error('partition: no measure ' + o.sizeBy); }
  if (m.additive === false) { throw new Error(m.label + ' is not additive: its parts do not add up to the whole, so it cannot size a sunburst or an icicle (colour by it instead)'); }
  const focus = (o.focus && model.node(o.focus)) || model.roots[0], depth = o.depth === undefined ? 4 : o.depth, out = [];
  if (!focus) { return out; }
  const memo = new Map();
  const size = (n) => {
    if (memo.has(n.id)) { return memo.get(n.id); }
    let v;
    if (!n.children.length) { const x = n.values[m.field]; v = typeof x === 'number' && x > 0 ? x : 0; }
    else { v = n.children.reduce((a, c) => a + size(c), 0); }
    memo.set(n.id, v);
    return v;
  };
  const walk = (n, a0, a1, d) => {
    out.push({ id: n.id, a0: a0, a1: a1, depth: d, value: size(n) });
    if (d >= depth) { return; }
    const total = size(n);
    let a = a0;
    n.children.forEach((c) => { const sh = total > 0 ? (a1 - a0) * size(c) / total : 0; walk(c, a, a + sh, d + 1); a += sh; });
  };
  walk(focus, 0, 1, 0);
  return out;
}

/**
 * The SVG path of a ring's slice.
 * @param {number} cx
 * @param {number} cy
 * @param {number} r0 inner radius (0: a disc's slice)
 * @param {number} r1 outer radius
 * @param {number} a0 start, a share of the turn (0 at the top, clockwise)
 * @param {number} a1 end
 * @returns {string}
 */
export function arcPath(cx, cy, r0, r1, a0, a1) {
  const full = a1 - a0 >= 0.9999;
  if (full) { a1 = a0 + 0.9999; }
  const A0 = a0 * 2 * Math.PI - Math.PI / 2, A1 = a1 * 2 * Math.PI - Math.PI / 2, large = a1 - a0 > 0.5 ? 1 : 0;
  const p = (r, a) => r1fmt(cx + r * Math.cos(a)) + ',' + r1fmt(cy + r * Math.sin(a));
  if (r0 <= 0) { return 'M' + r1fmt(cx) + ',' + r1fmt(cy) + 'L' + p(r1, A0) + 'A' + r1 + ',' + r1 + ' 0 ' + large + ' 1 ' + p(r1, A1) + 'Z'; }
  return 'M' + p(r0, A0) + 'L' + p(r1, A0) + 'A' + r1 + ',' + r1 + ' 0 ' + large + ' 1 ' + p(r1, A1) + 'L' + p(r0, A1) + 'A' + r0 + ',' + r0 + ' 0 ' + large + ' 0 ' + p(r0, A0) + 'Z';
}

function r1fmt(v) { return String(Math.round(v * 100) / 100); }

/**
 * The tone of a node's bar: its status (the field `bar.status` of the node, a word the host's server gave, such as a limit's within,
 * near or breach) in the bar's tones. The tree never makes a status of a figure: a node without one has no tone.
 * @param {?Object} n a TreeNode (its values first: a host's answer; else the row it was read from)
 * @param {?{status: string, tones: Object<string, string>}} bar
 * @returns {?string} the tone's name, or null without a status (or one the tones do not name)
 */
export function statusTone(n, bar) {
  if (!n || !bar || !bar.status || !bar.tones) { return null; }
  const v = n.values && n.values[bar.status] !== undefined && n.values[bar.status] !== null ? n.values[bar.status] : n.data ? n.data[bar.status] : null;
  return v === undefined || v === null ? null : (Object.prototype.hasOwnProperty.call(bar.tones, v) ? bar.tones[v] : null);
}
