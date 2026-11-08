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
/* drishti-graph: networks, cyclic or acyclic, on a canvas for thousands of nodes (ELEMENTS.md section 8.8). The model and its
   transactions, the algorithms (shortest path, rings, components, layers), the layouts (force with a seed, layered, radial, circular,
   grouped, preset), what is drawn (filters, collapse, groups, encodings, the legend), the canvas, export and the adjacency table, the
   component in its three modes (inspector, table, terminal; g5) with its tables on drishti-grid, the terminal's commands, the lineage
   preset, the view's graph panel and <drishti-graph>. In a page the bundle registers it as window.Drishti.elements.graph and install()
   defines the element. */
import { defineGraphElement } from './drishti_graph-element.js';

export { GraphModel } from './drishti_graph-model.js';
export { adjacency, shortestPath, rings, components, layers, topBy } from './drishti_graph-algo.js';
export { seededRandom, forceLayout, forceSteps } from './drishti_graph-force.js';
export { LAYOUTS, layout, layeredLayout, radialLayout, circularLayout, groupedLayout, presetLayout, bounds } from './drishti_graph-layout.js';
export { SHAPES, groupId, visibleGraph, encode } from './drishti_graph-scene.js';
export { pointInPolygon, HitIndex, shapePath, CanvasRenderer } from './drishti_graph-canvas.js';
export { toSvg, nodesTable, edgesTable, graphCsv, adjacencyRows } from './drishti_graph-export.js';
export { COMMANDS, FUNCTION_KEYS, words, runCommand, complete } from './drishti_graph-terminal.js';
export { GRAPH_MODES } from './drishti_graph-chrome.js';
export { GRAPH_EVENTS, GraphBase } from './drishti_graph-base.js';
export { neighbourInDirection } from './drishti_graph-pointer.js';
export { Graph } from './drishti_graph-view.js';
export { LINEAGE_STAGES, lineageOptions, lineageHealth } from './drishti_graph-lineage.js';
export { panelGraphOptions, drawGraphPanel } from './drishti_graph-panel.js';
export { GRAPH_ELEMENT, defineGraphElement } from './drishti_graph-element.js';

/**
 * Starts drishti-graph in a page: defines <drishti-graph> (the bundle calls it when it loads; nothing happens without a document).
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { defineGraphElement(root.customElements, root); } }
