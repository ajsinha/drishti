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
/* The layouts but force (drishti_graph-force.js): layered (a directed graph in layers, its cycles broken, each layer ordered by the
   barycentre of its neighbours to cross fewer edges; top-down or left to right), radial (rings of hops around a root), circular (by
   type, then by label), grouped (each group a frame on shelves, its nodes in a grid inside: what the data map draws) and preset (the
   positions given). Each returns positions in layout units (the view fits them to the canvas) and is a pure function of its input, so the
   same graph is always drawn the same way. layout(name, ...) chooses by name; LAYOUTS lists them. */
import { layers, rings } from './drishti_graph-algo.js';
import { forceLayout } from './drishti_graph-force.js';

/** The layouts by name, in the order the picker lists them. */
export const LAYOUTS = Object.freeze(['force', 'layered', 'radial', 'circular', 'grouped', 'preset']);

/**
 * A layout's input and result.
 * @typedef {Object} LayoutOptions
 * @property {number} [seed=7] the force layout's seed (the same seed, the same picture)
 * @property {('TB'|'LR')} [direction='TB'] the layered layout's flow: top to bottom or left to right (the lineage)
 * @property {string} [root] the radial layout's centre (default the node with most edges)
 * @property {function(Object): ?number} [rank] the layered layout's layer of a node (the lineage's stages)
 * @property {string} [groupBy] the grouped layout's attribute (default group, else type)
 * @property {Map<string, {x: number, y: number}>} [fixed] positions kept (pinned nodes)
 * @property {Map<string, {x: number, y: number}>} [start] positions to start from (force: the last picture, so a change moves little)
 * @property {number} [iterations] force: the steps (default by size)
 * @property {number} [spacing=1] a factor on every gap
 */

/**
 * The positions of a layout by name.
 * @param {string} name one of LAYOUTS
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {LayoutOptions} [o]
 * @returns {{positions: Map<string, {x: number, y: number}>, frames: Array<{id: string, label: string, x: number, y: number, w: number, h: number}>}}
 *   frames: the grouped layout's group frames (empty for the others)
 */
export function layout(name, g, o) {
  o = o || {};
  let out;
  switch (name) {
    case 'force': out = { positions: forceLayout(g, o), frames: [] }; break;
    case 'layered': out = { positions: layeredLayout(g, o), frames: [] }; break;
    case 'radial': out = { positions: radialLayout(g, o), frames: [] }; break;
    case 'circular': out = { positions: circularLayout(g, o), frames: [] }; break;
    case 'grouped': out = groupedLayout(g, o); break;
    case 'preset': out = { positions: presetLayout(g), frames: [] }; break;
    default: throw new Error('no graph layout ' + name + ' (' + LAYOUTS.join(', ') + ')');
  }
  if (o.fixed && name !== 'preset') { o.fixed.forEach((p, id) => { if (out.positions.has(id)) { out.positions.set(id, { x: p.x, y: p.y }); } }); }
  return out;
}

/**
 * The layered layout: layers of a directed graph (cycles broken), each ordered by its neighbours' barycentres (sweeps down and up).
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {LayoutOptions} [o]
 * @returns {Map<string, {x: number, y: number}>}
 */
export function layeredLayout(g, o) {
  o = o || {};
  const L = layers(g, { rank: o.rank }), rows = [];
  g.nodes.forEach((n) => { const k = L.layer.get(n.id) || 0; (rows[k] = rows[k] || []).push(n.id); });
  for (let k = 0; k < rows.length; k++) { rows[k] = rows[k] || []; }
  const up = new Map(), down = new Map();
  g.nodes.forEach((n) => { up.set(n.id, []); down.set(n.id, []); });
  g.edges.forEach((e) => {
    const a = L.layer.get(e.source), b = L.layer.get(e.target);
    if (a === undefined || b === undefined || a === b) { return; }
    const [hi, lo] = a < b ? [e.source, e.target] : [e.target, e.source];
    down.get(hi).push(lo); up.get(lo).push(hi);
  });
  const index = new Map();
  const reindex = (k) => rows[k].forEach((id, i) => index.set(id, i));
  rows.forEach((r, k) => { r.sort(); reindex(k); });
  const sweep = (k, by) => {
    const bc = new Map(rows[k].map((id) => { const ns = by.get(id); return [id, ns.length ? ns.reduce((s, x) => s + index.get(x), 0) / ns.length : index.get(id)]; }));
    rows[k].sort((a, b) => bc.get(a) - bc.get(b) || index.get(a) - index.get(b));
    reindex(k);
  };
  for (let pass = 0; pass < 6; pass++) {
    for (let k = 1; k < rows.length; k++) { sweep(k, up); }
    for (let k = rows.length - 2; k >= 0; k--) { sweep(k, down); }
  }
  const s = o.spacing || 1, along = 70 * s, across = 120 * s, widest = Math.max(1, ...rows.map((r) => r.length));
  const pos = new Map();
  rows.forEach((r, k) => {
    const off = (widest - r.length) / 2;
    r.forEach((id, i) => {
      const a = (off + i) * along, b = k * across;
      pos.set(id, o.direction === 'LR' ? { x: b * 1.6, y: a } : { x: a, y: b });
    });
  });
  return pos;
}

/**
 * The radial layout: the root at the centre, each ring of hops on a circle, a ring ordered by its parent's angle; nodes the root cannot
 * reach on an outer ring.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {LayoutOptions} [o]
 * @returns {Map<string, {x: number, y: number}>}
 */
export function radialLayout(g, o) {
  o = o || {};
  const pos = new Map();
  if (!g.nodes.length) { return pos; }
  let root = o.root;
  if (!root || !g.nodes.some((n) => n.id === root)) {
    const deg = new Map();
    g.edges.forEach((e) => { deg.set(e.source, (deg.get(e.source) || 0) + 1); deg.set(e.target, (deg.get(e.target) || 0) + 1); });
    root = g.nodes.reduce((best, n) => ((deg.get(n.id) || 0) > (deg.get(best) || 0) ? n.id : best), g.nodes[0].id);
  }
  const hop = rings(g, root), max = Math.max(0, ...hop.values()), ring = [];
  g.nodes.forEach((n) => { const d = hop.has(n.id) ? hop.get(n.id) : max + 1; (ring[d] = ring[d] || []).push(n.id); });
  const angle = new Map([[root, 0]]), parent = new Map();
  g.edges.forEach((e) => {
    [[e.source, e.target], [e.target, e.source]].forEach(([a, b]) => { if (hop.get(b) === hop.get(a) + 1 && !parent.has(b)) { parent.set(b, a); } });
  });
  const s = o.spacing || 1;
  pos.set(root, { x: 0, y: 0 });
  for (let d = 1; d < ring.length; d++) {
    const ids = (ring[d] || []).slice().sort((a, b) => ((angle.get(parent.get(a)) || 0) - (angle.get(parent.get(b)) || 0)) || (a < b ? -1 : 1));
    const r = d * 110 * s * Math.max(1, Math.sqrt(ids.length / (8 * d)));
    ids.forEach((id, i) => {
      const t = (i / ids.length) * Math.PI * 2;
      angle.set(id, t);
      pos.set(id, { x: Math.cos(t) * r, y: Math.sin(t) * r });
    });
  }
  return pos;
}

/**
 * The circular layout: every node on one circle, by type then label.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {LayoutOptions} [o]
 * @returns {Map<string, {x: number, y: number}>}
 */
export function circularLayout(g, o) {
  const ids = g.nodes.slice().sort((a, b) => String(a.type || '').localeCompare(String(b.type || '')) || a.label.localeCompare(b.label)).map((n) => n.id);
  const r = Math.max(120, (ids.length * 26) / (2 * Math.PI)) * ((o && o.spacing) || 1), pos = new Map();
  ids.forEach((id, i) => { const t = (i / ids.length) * Math.PI * 2 - Math.PI / 2; pos.set(id, { x: Math.cos(t) * r, y: Math.sin(t) * r }); });
  return pos;
}

/**
 * The grouped layout: a frame per group packed on shelves, the most connected group first, its nodes in a grid of one to three columns
 * inside (the most connected first).
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {LayoutOptions} [o]
 * @returns {{positions: Map<string, {x: number, y: number}>, frames: Array<{id: string, label: string, x: number, y: number, w: number, h: number}>}}
 */
export function groupedLayout(g, o) {
  o = o || {};
  const by = o.groupBy || (g.nodes.some((n) => n.group) ? 'group' : 'type');
  const groups = new Map(), deg = new Map();
  g.edges.forEach((e) => { deg.set(e.source, (deg.get(e.source) || 0) + 1); deg.set(e.target, (deg.get(e.target) || 0) + 1); });
  g.nodes.forEach((n) => { const k = n[by] === undefined || n[by] === null ? '' : String(n[by]); if (!groups.has(k)) { groups.set(k, []); } groups.get(k).push(n); });
  const W = 150, H = 54, GAP = 16, PAD = 18, HEAD = 30, WIDTH = Math.max(1180, Math.sqrt(g.nodes.length) * 180);
  const list = Array.from(groups, ([k, ns]) => ({ k, ns: ns.sort((a, b) => (deg.get(b.id) || 0) - (deg.get(a.id) || 0) || a.label.localeCompare(b.label)) }))
    .sort((a, b) => b.ns.reduce((s, n) => s + (deg.get(n.id) || 0), 0) - a.ns.reduce((s, n) => s + (deg.get(n.id) || 0), 0) || a.k.localeCompare(b.k));
  const pos = new Map(), frames = [];
  let x = 0, y = 0, shelf = 0;
  list.forEach(({ k, ns }) => {
    const cols = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(ns.length / 2)))), rowsN = Math.ceil(ns.length / cols);
    const w = PAD * 2 + cols * W + (cols - 1) * GAP, h = HEAD + PAD + rowsN * H + (rowsN - 1) * GAP;
    if (x > 0 && x + w > WIDTH) { x = 0; y += shelf + 20; shelf = 0; }
    frames.push({ id: k, label: k || '(none)', x, y, w, h });
    ns.forEach((n, i) => pos.set(n.id, { x: x + PAD + (i % cols) * (W + GAP) + W / 2, y: y + HEAD + Math.floor(i / cols) * (H + GAP) + H / 2 }));
    x += w + 20; shelf = Math.max(shelf, h);
  });
  return { positions: pos, frames };
}

/**
 * The preset layout: the positions the nodes carry (x, y); a node without one is put on a row under the others.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @returns {Map<string, {x: number, y: number}>}
 */
export function presetLayout(g) {
  const pos = new Map();
  let maxY = 0, spare = 0;
  g.nodes.forEach((n) => { if (typeof n.x === 'number' && typeof n.y === 'number') { pos.set(n.id, { x: n.x, y: n.y }); maxY = Math.max(maxY, n.y); } });
  g.nodes.forEach((n) => { if (!pos.has(n.id)) { pos.set(n.id, { x: (spare++) * 90, y: maxY + 120 }); } });
  return pos;
}

/**
 * The box around positions.
 * @param {Iterable<{x: number, y: number}>} points
 * @returns {{x: number, y: number, w: number, h: number}}
 */
export function bounds(points) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of points) { if (p.x < x0) { x0 = p.x; } if (p.y < y0) { y0 = p.y; } if (p.x > x1) { x1 = p.x; } if (p.y > y1) { y1 = p.y; } }
  return isFinite(x0) ? { x: x0, y: y0, w: x1 - x0, h: y1 - y0 } : { x: 0, y: 0, w: 0, h: 0 };
}
