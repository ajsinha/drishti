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
/* What is drawn, from the model and the view's state: types filtered out, nodes hidden, what is below a collapsed node, and groups by an
   attribute folded into one node each (a group opened is drawn as its members; edges between groups are summed, with their count). Then
   the encodings: a node's size by a measure (area in proportion), its colour by an attribute (the palette) or a measure (a ramp, diverging
   when the values cross zero), its shape by type, an edge's width by its weight; and the legend that says so. A group's figure of a
   measure is the sum of its members' only for a measure that adds up; a measure marked additive: false is left for the host's hook
   (never summed here). Pure: the view draws the scene, the tests read it under Node. */
import { toneColour, rampColour } from '../../drishti-core/src/drishti_index.js';

/** The node shapes, in the order types take them. */
export const SHAPES = Object.freeze(['circle', 'square', 'diamond', 'triangle', 'hexagon']);

/**
 * A measure the nodes carry: its words, format and whether it adds up.
 * @typedef {Object} GraphMeasure
 * @property {string} field
 * @property {string} [label] default the field
 * @property {*} [format] a drishti-core format name (money, compact, pct2 ...)
 * @property {boolean} [additive=true] false: a group's or a selection's figure is never a sum (the host's hook gives it, or none is shown)
 */

/**
 * A node type's look and words.
 * @typedef {Object} GraphType
 * @property {string} [label]
 * @property {('circle'|'square'|'diamond'|'triangle'|'hexagon')} [shape]
 * @property {string} [colour] a tone (accent, good, bad ...) or a colour
 */

/**
 * The view's state the scene is made from.
 * @typedef {Object} SceneState
 * @property {Set<string>} [typesOff] types not drawn
 * @property {Set<string>} [hidden] nodes not drawn
 * @property {Set<string>} [collapsed] nodes whose descendants (reached only through them) are not drawn
 * @property {?string} [groupBy] an attribute whose members fold into one node each
 * @property {Set<string>} [openGroups] groups drawn as their members
 * @property {?string} [sizeBy] a measure (null: every node the same)
 * @property {?string} [colourBy] an attribute or a measure (default type)
 */

/**
 * The id of a group's node.
 * @param {string} attr the attribute grouped by
 * @param {string} value its value
 * @returns {string}
 */
export function groupId(attr, value) { return 'group:' + attr + '=' + value; }

/**
 * The visible graph: filtered, collapsed and grouped (no encodings yet).
 * @param {import('./drishti_graph-model.js').GraphModel} model
 * @param {SceneState} st
 * @param {Object<string, GraphMeasure>} measures
 * @returns {{nodes: Object[], edges: Object[], groups: Map<string, string[]>, memberOf: Map<string, string>}}
 */
export function visibleGraph(model, st, measures) {
  const typesOff = st.typesOff || new Set(), hidden = st.hidden || new Set(), collapsed = st.collapsed || new Set();
  const keep = new Set();
  model.nodes.forEach((n, id) => { if (!typesOff.has(n.type || '') && !hidden.has(id)) { keep.add(id); } });
  if (collapsed.size) {
    // a node goes when every edge into it comes from a collapsed node or one already gone (walked down from the collapsed)
    const gone = new Set(), queue = [];
    collapsed.forEach((c) => { if (keep.has(c)) { queue.push(c); } });
    while (queue.length) {
      const c = queue.shift();
      model.neighbours(c, 'out').forEach((t) => {
        if (gone.has(t) || collapsed.has(t) && !keep.has(t)) { return; }
        const feeders = model.edgesOf(t, 'in').map((e) => e.source).filter((s) => keep.has(s) && s !== t);
        if (feeders.every((s) => collapsed.has(s) || gone.has(s))) { gone.add(t); queue.push(t); }
      });
    }
    gone.forEach((id) => { if (!collapsed.has(id)) { keep.delete(id); } });
  }
  const by = st.groupBy, open = st.openGroups || new Set(), memberOf = new Map(), groups = new Map();
  if (by) {
    keep.forEach((id) => {
      const n = model.nodes.get(id), v = n[by] === undefined || n[by] === null || n[by] === '' ? '(none)' : String(n[by]), g = groupId(by, v);
      if (open.has(g)) { return; }
      memberOf.set(id, g);
      if (!groups.has(g)) { groups.set(g, []); }
      groups.get(g).push(id);
    });
  }
  const nodes = [];
  keep.forEach((id) => { if (!memberOf.has(id)) { nodes.push(model.nodes.get(id)); } });
  groups.forEach((ids, g) => {
    const value = g.slice(g.indexOf('=') + 1), first = model.nodes.get(ids[0]), node = { id: g, label: value + ' (' + ids.length + ')', type: by === 'type' ? first.type : 'group', members: ids, _group: true };
    node[by] = value;
    Object.keys(measures || {}).forEach((f) => {
      if (measures[f].additive === false) { return; }
      let s = 0, any = false;
      ids.forEach((m) => { const v = model.nodes.get(m)[f]; if (typeof v === 'number') { s += v; any = true; } });
      if (any) { node[f] = s; }
    });
    nodes.push(node);
  });
  const at = (id) => memberOf.get(id) || id, edges = [], merged = new Map();
  model.edges.forEach((e) => {
    if (!keep.has(e.source) || !keep.has(e.target)) { return; }
    const s = at(e.source), t = at(e.target);
    if (s === e.source && t === e.target) { edges.push(e); return; }
    if (s === t) { return; }
    const k = s + '\u0000' + t;
    if (!merged.has(k)) { merged.set(k, { id: 'merged:' + s + '→' + t, source: s, target: t, weight: 0, count: 0, _merged: true }); }
    const m = merged.get(k); m.weight += e.weight; m.count++;
  });
  merged.forEach((m) => edges.push(m));
  return { nodes, edges, groups, memberOf };
}

/** The finite numbers of a field over nodes. */
function valuesOf(nodes, f) { const out = []; nodes.forEach((n) => { if (typeof n[f] === 'number' && isFinite(n[f])) { out.push(n[f]); } }); return out; }

/**
 * The encodings of a visible graph: each node's radius, colour and shape, each edge's width, and the legend.
 * @param {{nodes: Object[], edges: Object[]}} vis from visibleGraph
 * @param {SceneState} st
 * @param {{theme: import('../../drishti-core/src/drishti_visual.js').VisualTheme, types: Object<string, GraphType>, typeOrder: string[], radius: number[],
 *   measures: Object<string, GraphMeasure>, format: function(string, *): string}} o radius: [smallest, largest] in pixels at zoom 1
 * @returns {{nodes: Map<string, {r: number, colour: string, shape: string}>, edges: Map<string, {width: number}>, legend: GraphLegend}}
 */
export function encode(vis, st, o) {
  const th = o.theme, types = o.types || {}, order = o.typeOrder || [], [r0, r1] = o.radius || [5, 18];
  const shapeOf = (t) => (types[t] && types[t].shape) || SHAPES[Math.max(0, order.indexOf(t)) % SHAPES.length];
  const typeColour = (t, i) => (types[t] && types[t].colour ? toneColour(th, types[t].colour) : th.palette[i % th.palette.length]);
  const nodes = new Map(), edges = new Map(), legend = { colour: null, size: null, types: [] };
  // size
  const sizeVals = st.sizeBy ? valuesOf(vis.nodes, st.sizeBy).map(Math.abs) : [];
  const smax = sizeVals.length ? Math.max(...sizeVals) : 0;
  const radius = (n) => {
    if (n._group && !st.sizeBy) { return Math.min(r1, r0 + Math.sqrt(n.members.length) * 1.6); }
    if (!st.sizeBy || !smax || typeof n[st.sizeBy] !== 'number') { return r0 + 1.5; }
    return r0 + (r1 - r0) * Math.sqrt(Math.abs(n[st.sizeBy]) / smax);
  };
  if (st.sizeBy && smax) {
    legend.size = { field: st.sizeBy, label: (o.measures[st.sizeBy] && o.measures[st.sizeBy].label) || st.sizeBy,
      samples: [0.05, 0.3, 1].map((f) => ({ value: smax * f, text: o.format(st.sizeBy, smax * f), r: r0 + (r1 - r0) * Math.sqrt(f) })) };
  }
  // colour
  const by = st.colourBy === undefined || st.colourBy === null ? 'type' : st.colourBy;
  const numeric = by !== 'type' && valuesOf(vis.nodes, by).length > 0 && vis.nodes.every((n) => n[by] === undefined || n[by] === null || typeof n[by] === 'number');
  let colourOf;
  if (numeric) {
    const vs = valuesOf(vis.nodes, by), lo = Math.min(...vs), hi = Math.max(...vs), div = lo < 0 && hi > 0, m = Math.max(Math.abs(lo), Math.abs(hi));
    colourOf = (n) => (typeof n[by] !== 'number' ? th.nodata : div ? rampColour(th.diverging, n[by], -m, m) : rampColour(th.sequential, n[by], lo, hi));
    legend.colour = { kind: 'ramp', field: by, label: (o.measures[by] && o.measures[by].label) || by, lo: div ? -m : lo, hi: div ? m : hi,
      loText: o.format(by, div ? -m : lo), hiText: o.format(by, div ? m : hi), colours: div ? th.diverging : th.sequential };
  } else {
    const seen = [];
    if (by === 'type') { order.forEach((t) => seen.push(t)); }
    vis.nodes.forEach((n) => { const v = n[by] === undefined || n[by] === null ? '' : String(n[by]); if (!seen.includes(v)) { seen.push(v); } });
    const col = (v) => (by === 'type' ? typeColour(v, seen.indexOf(v)) : th.palette[seen.indexOf(v) % th.palette.length]);
    colourOf = (n) => col(n[by] === undefined || n[by] === null ? '' : String(n[by]));
    legend.colour = { kind: 'categorical', field: by, label: by, items: seen.filter((v) => vis.nodes.some((n) => String(n[by] === undefined || n[by] === null ? '' : n[by]) === v))
      .map((v) => ({ value: v, label: by === 'type' && types[v] && types[v].label ? types[v].label : v || '(none)', colour: col(v), shape: by === 'type' ? shapeOf(v) : 'circle' })) };
  }
  vis.nodes.forEach((n) => nodes.set(n.id, { r: radius(n), colour: colourOf(n), shape: n._group ? 'hexagon' : shapeOf(n.type || '') }));
  // edges
  const ws = vis.edges.map((e) => e.weight), wlo = ws.length ? Math.min(...ws) : 1, whi = ws.length ? Math.max(...ws) : 1;
  vis.edges.forEach((e) => edges.set(e.id, { width: whi > wlo ? 0.8 + 3.2 * Math.sqrt((e.weight - wlo) / (whi - wlo)) : 1.1 }));
  return { nodes, edges, legend };
}

/**
 * The legend of a scene.
 * @typedef {Object} GraphLegend
 * @property {?{kind: string, field: string, label: string, items: Array<{value: string, label: string, colour: string, shape: string}>, lo: number, hi: number, loText: string, hiText: string, colours: string[]}} colour
 * @property {?{field: string, label: string, samples: Array<{value: number, text: string, r: number}>}} size
 * @property {Array<{type: string, label: string, count: number, shown: number, off: boolean, shape: string, colour: string}>} types
 */
