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
/* The graph's data model: nodes and edges (cyclic or acyclic, directed or not) with their attributes and measures, kept by id with an
   adjacency index both ways. Changes come as transactions ({add, update, remove} of nodes and of edges, like drishti-grid's rows); an
   edge whose end is not a node is refused in words, a node removed takes its edges with it. Every change is an event on the model's
   bus (nodesChanged), so the view, the tables and a host follow. No drawing here: it runs under Node. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';

/**
 * A node as given: any object with an id; label, type and group are read by name (or by the field names in GraphDataOptions), every other
 * field is an attribute (a text) or a measure (a number) the encodings and the tables can use.
 * @typedef {Object} GraphNodeInput
 * @property {string} id
 * @property {string} [label] default the id
 * @property {string} [type] what kind of node it is (a filter and a shape)
 * @property {string} [group] a parent group (a frame in the grouped layout)
 * @property {{kind: string, id: string}} [link] an entity a click opens (the view's graph panel)
 * @property {string} [status] a badge: ok, warn, bad, stale, running (the lineage preset)
 * @property {number} [x] a position given (the preset layout)
 * @property {number} [y]
 * @property {boolean} [pinned] keeps its place through every layout
 */

/**
 * An edge as given: source and target (or from and to) are node ids.
 * @typedef {Object} GraphEdgeInput
 * @property {string} [id] default source→target (and a counter for a second edge between the same nodes)
 * @property {string} [source]
 * @property {string} [target]
 * @property {string} [from] the same as source (the view's graph panel)
 * @property {string} [to] the same as target
 * @property {number} [weight] the edge's width and the path's cost (when the path asks for weights)
 * @property {string} [label]
 * @property {string} [type]
 * @property {boolean} [directed] default the graph's directed option
 */

/**
 * A graph's data.
 * @typedef {Object} GraphData
 * @property {GraphNodeInput[]} nodes
 * @property {GraphEdgeInput[]} [edges]
 * @property {boolean} [directed=true] arrows drawn and paths follow the direction
 */

/**
 * A change to the graph's data, by id.
 * @typedef {Object} GraphTransaction
 * @property {GraphNodeInput[]} [addNodes]
 * @property {GraphNodeInput[]} [updateNodes] merged into the node of the same id
 * @property {string[]} [removeNodes] their edges go too
 * @property {GraphEdgeInput[]} [addEdges]
 * @property {GraphEdgeInput[]} [updateEdges]
 * @property {string[]} [removeEdges]
 */

/** Fields a node carries that are not attributes. */
const OWN = new Set(['id', 'label', 'type', 'group', 'link', 'status', 'x', 'y', 'pinned', 'focus']);

/**
 * The graph's nodes and edges with their adjacency.
 */
export class GraphModel {
  /**
   * @param {GraphData} [data]
   * @param {{bus: EventBus}} [o] the bus its events go on (default its own)
   */
  constructor(data, o) {
    this.events = (o && o.bus) || new EventBus();
    this.directed = !data || data.directed !== false;
    /** @type {Map<string, Object>} */ this.nodes = new Map();
    /** @type {Map<string, Object>} */ this.edges = new Map();
    /** @type {Map<string, Set<string>>} */ this.outE = new Map();
    /** @type {Map<string, Set<string>>} */ this.inE = new Map();
    this.version = 0;
    if (data) { this.setData(data); }
  }

  /**
   * Replaces every node and edge.
   * @param {GraphData} data
   * @returns {void}
   */
  setData(data) {
    this.nodes.clear(); this.edges.clear(); this.outE.clear(); this.inE.clear();
    if (data.directed !== undefined) { this.directed = data.directed !== false; }
    (data.nodes || []).forEach((n) => this.putNode(n, false));
    (data.edges || []).forEach((e) => this.putEdge(e, false));
    this.changed({ replaced: true });
  }

  /** @private */
  putNode(n, merge) {
    if (!n || n.id === undefined || n.id === null || n.id === '') { throw new Error('a graph node needs an id'); }
    const id = String(n.id), old = this.nodes.get(id);
    if (old && !merge) { throw new Error('two nodes have the id ' + id); }
    const node = old ? Object.assign(old, n, { id: id }) : Object.assign({}, n, { id: id });
    if (node.label === undefined || node.label === null || node.label === '') { node.label = id; }
    node.label = String(node.label);
    if (!old) { this.nodes.set(id, node); this.outE.set(id, new Set()); this.inE.set(id, new Set()); }
    return node;
  }

  /** @private */
  putEdge(e, merge) {
    const s = String(e.source !== undefined ? e.source : e.from), t = String(e.target !== undefined ? e.target : e.to);
    if (!this.nodes.has(s) || !this.nodes.has(t)) { throw new Error('the edge ' + s + ' → ' + t + ' names a node the graph does not have (' + (this.nodes.has(s) ? t : s) + ')'); }
    let id = e.id !== undefined && e.id !== null ? String(e.id) : s + '→' + t;
    if (e.id === undefined && !merge) { let k = 2; while (this.edges.has(id)) { id = s + '→' + t + '#' + (k++); } }
    const old = this.edges.get(id);
    if (old && !merge) { throw new Error('two edges have the id ' + id); }
    const edge = Object.assign(old || {}, e, { id: id, source: s, target: t });
    delete edge.from; delete edge.to;
    edge.weight = typeof edge.weight === 'number' && isFinite(edge.weight) ? edge.weight : 1;
    if (old && (old.source !== s || old.target !== t)) { this.unindex(old); }
    this.edges.set(id, edge);
    this.outE.get(s).add(id); this.inE.get(t).add(id);
    return edge;
  }

  /** @private */
  unindex(e) { const o = this.outE.get(e.source), i = this.inE.get(e.target); if (o) { o.delete(e.id); } if (i) { i.delete(e.id); } }

  /** @private */
  changed(detail) { this.version++; this.events.emit('nodesChanged', Object.assign({ version: this.version }, detail)); }

  /**
   * Applies a change by id: nodes added, updated (merged) and removed with their edges; edges added, updated and removed.
   * @param {GraphTransaction} tx
   * @returns {{addedNodes: string[], removedNodes: string[], addedEdges: string[], removedEdges: string[], updated: number}}
   */
  applyTransaction(tx) {
    const out = { addedNodes: [], removedNodes: [], addedEdges: [], removedEdges: [], updated: 0 };
    (tx.removeEdges || []).forEach((id) => { const e = this.edges.get(String(id)); if (e) { this.unindex(e); this.edges.delete(e.id); out.removedEdges.push(e.id); } });
    (tx.removeNodes || []).forEach((raw) => {
      const id = String(raw);
      if (!this.nodes.has(id)) { return; }
      [...this.outE.get(id), ...this.inE.get(id)].forEach((eid) => { const e = this.edges.get(eid); if (e) { this.unindex(e); this.edges.delete(eid); out.removedEdges.push(eid); } });
      this.nodes.delete(id); this.outE.delete(id); this.inE.delete(id); out.removedNodes.push(id);
    });
    (tx.addNodes || []).forEach((n) => { if (this.nodes.has(String(n.id))) { this.putNode(n, true); out.updated++; } else { out.addedNodes.push(this.putNode(n, false).id); } });
    (tx.updateNodes || []).forEach((n) => { if (this.nodes.has(String(n.id))) { this.putNode(n, true); out.updated++; } });
    (tx.addEdges || []).forEach((e) => { out.addedEdges.push(this.putEdge(e, false).id); });
    (tx.updateEdges || []).forEach((e) => { if (e.id !== undefined && this.edges.has(String(e.id))) { this.putEdge(e, true); out.updated++; } });
    this.changed({ transaction: out });
    return out;
  }

  /**
   * A node by id.
   * @param {string} id
   * @returns {?Object}
   */
  node(id) { return this.nodes.get(String(id)) || null; }

  /**
   * Every node, in the order given.
   * @returns {Object[]}
   */
  nodeList() { return Array.from(this.nodes.values()); }

  /**
   * Every edge, in the order given.
   * @returns {Object[]}
   */
  edgeList() { return Array.from(this.edges.values()); }

  /**
   * The edges at a node: out, in or both.
   * @param {string} id
   * @param {('out'|'in'|'both')} [dir='both']
   * @returns {Object[]}
   */
  edgesOf(id, dir) {
    const d = dir || 'both', ids = [];
    if (d !== 'in' && this.outE.has(id)) { ids.push(...this.outE.get(id)); }
    if (d !== 'out' && this.inE.has(id)) { ids.push(...this.inE.get(id)); }
    return ids.map((e) => this.edges.get(e));
  }

  /**
   * A node's neighbours (each once), by direction.
   * @param {string} id
   * @param {('out'|'in'|'both')} [dir='both']
   * @returns {string[]}
   */
  neighbours(id, dir) {
    const out = new Set();
    this.edgesOf(String(id), dir).forEach((e) => { out.add(e.source === String(id) ? e.target : e.source); });
    out.delete(String(id));
    return Array.from(out);
  }

  /**
   * How many edges meet a node.
   * @param {string} id
   * @returns {number}
   */
  degree(id) { return (this.outE.get(id) ? this.outE.get(id).size : 0) + (this.inE.get(id) ? this.inE.get(id).size : 0); }

  /**
   * The node types with their counts, in the order first met.
   * @returns {Array<{type: string, count: number}>}
   */
  types() {
    const m = new Map();
    this.nodes.forEach((n) => { const t = n.type || ''; m.set(t, (m.get(t) || 0) + 1); });
    return Array.from(m, ([type, count]) => ({ type, count }));
  }

  /**
   * The attributes (text fields) and measures (number fields) the nodes carry, for the group, size and colour pickers.
   * @returns {{attributes: string[], measures: string[]}}
   */
  fields() {
    const attrs = new Set(), measures = new Set();
    let seen = 0;
    for (const n of this.nodes.values()) {
      Object.keys(n).forEach((k) => {
        if (OWN.has(k) || k.charAt(0) === '_') { return; }
        const v = n[k];
        if (typeof v === 'number' && isFinite(v)) { measures.add(k); } else if (typeof v === 'string' || typeof v === 'boolean') { attrs.add(k); }
      });
      if (++seen > 2000) { break; }
    }
    measures.forEach((m) => attrs.delete(m));
    return { attributes: ['type'].concat(Array.from(attrs)).filter((a, i, all) => all.indexOf(a) === i), measures: Array.from(measures) };
  }

  /**
   * Finds nodes by words in their id or label (case ignored), best first: an exact id, a label that starts with them, then any.
   * @param {string} words
   * @param {number} [limit=20]
   * @returns {Object[]}
   */
  find(words, limit) {
    const q = String(words || '').trim().toLowerCase();
    if (!q) { return []; }
    const hits = [];
    this.nodes.forEach((n) => {
      const id = n.id.toLowerCase(), label = n.label.toLowerCase();
      const rank = id === q || label === q ? 0 : label.startsWith(q) || id.startsWith(q) ? 1 : label.includes(q) || id.includes(q) ? 2 : -1;
      if (rank >= 0) { hits.push([rank, n]); }
    });
    hits.sort((a, b) => a[0] - b[0] || a[1].label.localeCompare(b[1].label));
    return hits.slice(0, limit || 20).map((x) => x[1]);
  }

  /**
   * The data as given back (for export and a host's save): nodes and edges as plain objects.
   * @returns {GraphData}
   */
  toData() {
    const strip = (o) => { const c = {}; Object.keys(o).forEach((k) => { if (k.charAt(0) !== '_') { c[k] = o[k]; } }); return c; };
    return { directed: this.directed, nodes: this.nodeList().map(strip), edges: this.edgeList().map(strip) };
  }
}
