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
/* The graph's algorithms over plain lists (they take {nodes, edges} with ids, source and target, so they work on the model and on a
   derived, grouped graph alike): the shortest path (breadth first by hops, or Dijkstra by a cost), the rings around a node, connected
   components, the layers of a directed graph with its cycles broken (the layered layout and the lineage), and the nodes largest by a
   measure. Pure, no page: tested under Node. */

/**
 * The adjacency of a node list: for each id its edges out and in.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @returns {Map<string, {out: Object[], in: Object[]}>}
 */
export function adjacency(g) {
  const adj = new Map();
  g.nodes.forEach((n) => adj.set(n.id, { out: [], in: [] }));
  g.edges.forEach((e) => { const a = adj.get(e.source), b = adj.get(e.target); if (a && b) { a.out.push(e); b.in.push(e); } });
  return adj;
}

/** A small binary heap of [priority, value], the least first. */
class Heap {
  constructor() { this.a = []; }
  get size() { return this.a.length; }
  push(p, v) {
    const a = this.a; a.push([p, v]);
    let i = a.length - 1;
    while (i > 0) { const j = (i - 1) >> 1; if (a[j][0] <= a[i][0]) { break; } [a[i], a[j]] = [a[j], a[i]]; i = j; }
  }
  pop() {
    const a = this.a, top = a[0], last = a.pop();
    if (a.length) {
      a[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1, r = l + 1;
        let m = i;
        if (l < a.length && a[l][0] < a[m][0]) { m = l; }
        if (r < a.length && a[r][0] < a[m][0]) { m = r; }
        if (m === i) { break; }
        [a[i], a[m]] = [a[m], a[i]]; i = m;
      }
    }
    return top;
  }
}

/**
 * The shortest path between two nodes: the fewest hops (breadth first), or the least cost when cost is given (Dijkstra; a cost is never
 * negative). Directed graphs follow the arrows unless directed is false.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {string} from
 * @param {string} to
 * @param {{directed: boolean, cost: function(Object): number}} [o] cost: an edge's cost (e.g. (e) => 1 / e.weight); default one hop
 * @returns {?{nodes: string[], edges: string[], cost: number}} null when there is none
 */
export function shortestPath(g, from, to, o) {
  const directed = !o || o.directed !== false, cost = o && o.cost, adj = adjacency(g);
  if (!adj.has(from) || !adj.has(to)) { return null; }
  if (from === to) { return { nodes: [from], edges: [], cost: 0 }; }
  const steps = (id) => {
    const a = adj.get(id), out = a.out.map((e) => [e.target, e]);
    if (!directed) { a.in.forEach((e) => out.push([e.source, e])); }
    return out;
  };
  const prev = new Map([[from, null]]);
  if (!cost) {
    const queue = [from];
    for (let i = 0; i < queue.length && !prev.has(to); i++) {
      for (const [n, e] of steps(queue[i])) { if (!prev.has(n)) { prev.set(n, [queue[i], e]); queue.push(n); } }
    }
  } else {
    const dist = new Map([[from, 0]]), heap = new Heap(), done = new Set();
    heap.push(0, from);
    while (heap.size) {
      const [d, id] = heap.pop();
      if (done.has(id)) { continue; }
      done.add(id);
      if (id === to) { break; }
      for (const [n, e] of steps(id)) {
        const c = cost(e);
        if (!(c >= 0)) { throw new Error('a path cost is a number not below zero (edge ' + e.id + ')'); }
        if (!dist.has(n) || d + c < dist.get(n)) { dist.set(n, d + c); prev.set(n, [id, e]); heap.push(d + c, n); }
      }
    }
  }
  if (!prev.has(to)) { return null; }
  const nodes = [to], edges = [];
  let total = 0;
  for (let at = prev.get(to); at; at = prev.get(at[0])) { nodes.unshift(at[0]); edges.unshift(at[1].id); total += cost ? cost(at[1]) : 1; }
  return { nodes, edges, cost: total };
}

/**
 * The rings around a node: each node's hops from it (either direction), to a depth.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {string} root
 * @param {number} [depth=Infinity]
 * @returns {Map<string, number>}
 */
export function rings(g, root, depth) {
  const adj = adjacency(g), out = new Map([[root, 0]]), queue = [root], max = depth === undefined ? Infinity : depth;
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i], d = out.get(id);
    if (d >= max) { continue; }
    const a = adj.get(id);
    if (!a) { continue; }
    a.out.forEach((e) => { if (!out.has(e.target)) { out.set(e.target, d + 1); queue.push(e.target); } });
    a.in.forEach((e) => { if (!out.has(e.source)) { out.set(e.source, d + 1); queue.push(e.source); } });
  }
  return out;
}

/**
 * The connected components (arrows ignored), largest first.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @returns {string[][]}
 */
export function components(g) {
  const parent = new Map();
  g.nodes.forEach((n) => parent.set(n.id, n.id));
  const find = (x) => { while (parent.get(x) !== x) { parent.set(x, parent.get(parent.get(x))); x = parent.get(x); } return x; };
  g.edges.forEach((e) => { if (parent.has(e.source) && parent.has(e.target)) { parent.set(find(e.source), find(e.target)); } });
  const groups = new Map();
  g.nodes.forEach((n) => { const r = find(n.id); if (!groups.has(r)) { groups.set(r, []); } groups.get(r).push(n.id); });
  return Array.from(groups.values()).sort((a, b) => b.length - a.length);
}

/**
 * The layers of a directed graph: its cycles broken (the edges back to a node still on the walk, found depth first from the nodes
 * nothing points to, are read reversed), then each node on the longest path from a source, then pulled down next to what it feeds.
 * A rank given (rank(node) => number: the lineage's stages) wins over the computed one.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {{rank: function(Object): ?number}} [o]
 * @returns {{layer: Map<string, number>, reversed: Set<string>, count: number}}
 */
export function layers(g, o) {
  const adj = adjacency(g), state = new Map(), reversed = new Set();
  const visit = (start) => {
    const stack = [[start, 0]];
    state.set(start, 1);
    while (stack.length) {
      const top = stack[stack.length - 1], outs = adj.get(top[0]).out;
      if (top[1] < outs.length) {
        const e = outs[top[1]++], s = state.get(e.target);
        if (s === 1) { reversed.add(e.id); } else if (!s) { state.set(e.target, 1); stack.push([e.target, 0]); }
      } else { state.set(top[0], 2); stack.pop(); }
    }
  };
  g.nodes.filter((n) => !adj.get(n.id).in.length).forEach((n) => { if (!state.has(n.id)) { visit(n.id); } });
  g.nodes.forEach((n) => { if (!state.has(n.id)) { visit(n.id); } });
  const forward = (e) => (reversed.has(e.id) ? [e.target, e.source] : [e.source, e.target]);
  const indeg = new Map(g.nodes.map((n) => [n.id, 0])), outs = new Map(g.nodes.map((n) => [n.id, []])), ins = new Map(g.nodes.map((n) => [n.id, []]));
  g.edges.forEach((e) => { const [a, b] = forward(e); if (a !== b && indeg.has(a) && indeg.has(b)) { indeg.set(b, indeg.get(b) + 1); outs.get(a).push(b); ins.get(b).push(a); } });
  const layer = new Map(), queue = g.nodes.filter((n) => !indeg.get(n.id)).map((n) => n.id);
  queue.forEach((id) => layer.set(id, 0));
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i];
    outs.get(id).forEach((b) => { layer.set(b, Math.max(layer.get(b) || 0, layer.get(id) + 1)); indeg.set(b, indeg.get(b) - 1); if (!indeg.get(b)) { queue.push(b); } });
  }
  // a source sits next to the first node it feeds rather than at the top (fewer long edges)
  for (let i = queue.length - 1; i >= 0; i--) {
    const id = queue[i], next = outs.get(id).map((b) => layer.get(b));
    if (!ins.get(id).length && next.length) { layer.set(id, Math.min(...next) - 1); }
  }
  const byNode = new Map(g.nodes.map((n) => [n.id, n]));
  if (o && o.rank) { byNode.forEach((n, id) => { const r = o.rank(n); if (typeof r === 'number' && isFinite(r)) { layer.set(id, r); } }); }
  const used = Array.from(new Set(layer.values())).sort((a, b) => a - b), map = new Map(used.map((v, i) => [v, i]));
  layer.forEach((v, k) => layer.set(k, map.get(v)));
  return { layer, reversed, count: used.length };
}

/**
 * The nodes largest by a measure (missing values last).
 * @param {Object[]} nodes
 * @param {string} measure
 * @param {number} n
 * @returns {Object[]}
 */
export function topBy(nodes, measure, n) {
  return nodes.filter((x) => typeof x[measure] === 'number').sort((a, b) => b[measure] - a[measure]).slice(0, n);
}
