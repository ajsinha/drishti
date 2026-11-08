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
/* The view's graph panel (a Sutra's kind: graph; VIEWMODEL.md section 4) drawn by drishti-graph: the panel's data (nodes with id, label,
   group, link and focus; edges with from, to and label; layout tree or force; more) becomes a Graph in the table mode without its tables
   (the controls float on the canvas, the card at the node), its groups as types, tree as the layered layout top-down, the panel's own
   node (focus) outlined, and a click on a node that names an entity opens it through the host (the console opens it in place, the
   renderer in its pane). drishti-chart's PanelCharts asks for it by the panel's kind; the server's words (the Entities list) stay under it. */
import { Graph } from './drishti_graph-view.js';

/**
 * The graph's options for a view's graph panel.
 * @param {{nodes: Object[], edges: Object[], layout: string, title: string}} data the panel's data
 * @param {{onLink: function(Object): void, density: string, height: number}} [o]
 * @returns {import('./drishti_graph-base.js').GraphOptions}
 */
export function panelGraphOptions(data, o) {
  o = o || {};
  const nodes = (data.nodes || []).map((n) => ({ id: String(n.id), label: n.label || String(n.id), type: n.group || '', link: n.link || undefined, focus: !!n.focus }));
  const ids = new Set(nodes.map((n) => n.id));
  const edges = (data.edges || []).filter((e) => ids.has(String(e.from)) && ids.has(String(e.to))).map((e) => ({ source: String(e.from), target: String(e.to), label: e.label || '' }));
  return { data: { directed: true, nodes, edges }, mode: 'table', tables: false, minimap: false, density: o.density || 'compact', height: o.height,
    layout: data.layout === 'force' ? 'force' : 'layered', layoutOptions: { direction: 'TB' }, openOnClick: true, title: data.title || '',
    onLink: (link) => { if (o.onLink && link && link.kind && link.id) { o.onLink(link); } } };
}

/**
 * Draws a view's graph panel into its container (again, after a theme change or new data: the graph is replaced).
 * @param {HTMLElement} el the panel's chart container
 * @param {Object} data the panel's data
 * @param {{onLink: function(Object): void, density: string}} [o]
 * @returns {Graph}
 */
export function drawGraphPanel(el, data, o) {
  if (el.__drishtiGraph) { el.__drishtiGraph.destroy(); }
  const g = new Graph(el, panelGraphOptions(data || {}, Object.assign({ height: Math.max(220, el.clientHeight || 320) }, o)));
  const focus = g.model.nodeList().find((n) => n.focus);
  if (focus) { g.focusId = focus.id; g.draw(); }
  el.__drishtiGraph = g;
  return g;
}
