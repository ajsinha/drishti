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
/* What leaves the graph: an SVG of what is drawn (vector, every node and edge, the labels, the theme's colours), the nodes and the
   edges as tables (drishti-core's CSV writer, or the grid's rows), the data as JSON, and the adjacency table, the graph's text
   alternative (each node with what it points to and what points to it, as the chart's ▦ gives its data). Pure: no page. */
import { toCsv } from '../../drishti-core/src/drishti_index.js';

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const esc = (s) => String(s === undefined || s === null ? '' : s).replace(/[&<>"']/g, (c) => ESC[c]);
const n2 = (v) => Math.round(v * 100) / 100;

/** An SVG shape for a node. */
function svgShape(shape, x, y, r) {
  switch (shape) {
    case 'square': return '<rect x="' + n2(x - r * 0.88) + '" y="' + n2(y - r * 0.88) + '" width="' + n2(r * 1.76) + '" height="' + n2(r * 1.76) + '"';
    case 'diamond': return '<polygon points="' + [[x, y - r * 1.15], [x + r * 1.15, y], [x, y + r * 1.15], [x - r * 1.15, y]].map((p) => p.map(n2).join(',')).join(' ') + '"';
    case 'triangle': return '<polygon points="' + [[x, y - r * 1.15], [x + r * 1.1, y + r * 0.8], [x - r * 1.1, y + r * 0.8]].map((p) => p.map(n2).join(',')).join(' ') + '"';
    case 'hexagon': return '<polygon points="' + [0, 1, 2, 3, 4, 5].map((i) => { const a = Math.PI / 3 * i + Math.PI / 6; return n2(x + r * Math.cos(a)) + ',' + n2(y + r * Math.sin(a)); }).join(' ') + '"';
    default: return '<circle cx="' + n2(x) + '" cy="' + n2(y) + '" r="' + n2(r) + '"';
  }
}

/**
 * The graph as drawn, as an SVG document: every visible node and edge at the layout's positions, the labels, arrows on a directed graph,
 * the path and the selection in the accent.
 * @param {import('./drishti_graph-canvas.js').Frame} f
 * @param {{x: number, y: number, w: number, h: number}} box the layout's bounds
 * @param {{title: string}} [o]
 * @returns {string}
 */
export function toSvg(f, box, o) {
  const th = f.theme, pad = 40, w = Math.ceil(box.w + pad * 2), h = Math.ceil(box.h + pad * 2), dx = pad - box.x, dy = pad - box.y;
  const out = ['<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '" font-family="' + esc(th.font) + '" font-size="' + f.labelSize + '">',
    '<title>' + esc((o && o.title) || 'Graph') + '</title>',
    '<defs><marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0L10,5L0,10z" fill="' + esc(th.muted) + '"/></marker></defs>',
    '<rect width="100%" height="100%" fill="' + esc(th.surface) + '"/>'];
  (f.frames || []).forEach((fr) => out.push('<g><rect x="' + n2(fr.x + dx) + '" y="' + n2(fr.y + dy) + '" width="' + n2(fr.w) + '" height="' + n2(fr.h) + '" rx="10" fill="' + esc(th.ground) + '" stroke="' + esc(th.border) + '"/><text x="' + n2(fr.x + dx + 12) + '" y="' + n2(fr.y + dy + 18) + '" fill="' + esc(th.muted) + '" font-weight="600">' + esc(String(fr.label).toUpperCase()) + '</text></g>'));
  out.push('<g fill="none">');
  f.edges.forEach((e) => {
    const a = f.pos.get(e.source), b = f.pos.get(e.target);
    if (!a || !b) { return; }
    const hot = (f.path && f.path.edges.has(e.id)) || f.selected.has(e.source) || f.selected.has(e.target);
    const r = ((f.look.get(e.target) || {}).r || 6) + 2, d = Math.hypot(b.x - a.x, b.y - a.y) || 1, ex = b.x - ((b.x - a.x) / d) * r, ey = b.y - ((b.y - a.y) / d) * r;
    out.push('<line x1="' + n2(a.x + dx) + '" y1="' + n2(a.y + dy) + '" x2="' + n2(ex + dx) + '" y2="' + n2(ey + dy) + '" stroke="' + esc(hot ? th.tones.accent : th.muted) + '" stroke-opacity="' + (hot ? 1 : 0.45) + '" stroke-width="' + n2((f.edgeLook.get(e.id) || {}).width || 1) + '"' + (f.directed ? ' marker-end="url(#arrow)"' : '') + '><title>' + esc(e.source + ' → ' + e.target + (e.label ? ' · ' + e.label : '')) + '</title></line>');
  });
  out.push('</g><g>');
  f.nodes.forEach((n) => {
    const p = f.pos.get(n.id), L = f.look.get(n.id);
    if (!p || !L) { return; }
    const hot = f.selected.has(n.id) || (f.path && f.path.nodes.has(n.id));
    out.push('<g>' + svgShape(L.shape, p.x + dx, p.y + dy, L.r) + ' fill="' + esc(L.colour) + '" stroke="' + esc(hot ? th.tones.accent : th.surface) + '" stroke-width="' + (hot ? 2.5 : 1) + '"><title>' + esc(n.label) + '</title></' + (L.shape === 'square' ? 'rect' : L.shape === 'circle' || !L.shape ? 'circle' : 'polygon') + '>' +
      '<text x="' + n2(p.x + dx) + '" y="' + n2(p.y + dy + L.r + 3 + f.labelSize) + '" text-anchor="middle" fill="' + esc(th.ink) + '">' + esc(n.label) + '</text></g>');
  });
  out.push('</g></svg>');
  return out.join('\n');
}

/**
 * The nodes as a table (the fields given, each with its header), for CSV, Excel or a grid.
 * @param {Object[]} nodes
 * @param {Array<{field: string, header: string, type: string, format: *}>} columns
 * @returns {import('../../drishti-core/src/drishti_files.js').ExportTable}
 */
export function nodesTable(nodes, columns) {
  return { columns: columns.map((c) => ({ header: c.header || c.field, type: c.type || 'text', format: c.format })), rows: nodes.map((n) => columns.map((c) => (n[c.field] === undefined ? null : n[c.field]))) };
}

/**
 * The edges as a table: source, target, label, type, weight.
 * @param {Object[]} edges
 * @param {function(string): string} [labelOf] a node's label by id
 * @returns {import('../../drishti-core/src/drishti_files.js').ExportTable}
 */
export function edgesTable(edges, labelOf) {
  const L = labelOf || ((id) => id);
  return { columns: [{ header: 'Source', type: 'text' }, { header: 'Target', type: 'text' }, { header: 'Label', type: 'text' }, { header: 'Type', type: 'text' }, { header: 'Weight', type: 'number' }],
    rows: edges.map((e) => [L(e.source), L(e.target), e.label || null, e.type || null, e.weight]) };
}

/**
 * The nodes and edges as CSV (two sections in one file: the nodes, a blank line, the edges).
 * @param {Object[]} nodes
 * @param {Object[]} edges
 * @param {Array<{field: string, header: string, type: string, format: *}>} columns the node columns
 * @returns {string}
 */
export function graphCsv(nodes, edges, columns) {
  const byId = new Map(nodes.map((n) => [n.id, n.label]));
  return toCsv(nodesTable(nodes, columns)) + '\r\n' + toCsv(edgesTable(edges, (id) => byId.get(id) || id), { bom: false });
}

/**
 * The adjacency table, the graph's text alternative: one row per node with its type, what it points to and what points to it (each
 * by label, with the edge's label), its degree. Sorted by label.
 * @param {Object[]} nodes
 * @param {Object[]} edges
 * @param {boolean} directed false: one list of neighbours
 * @returns {Array<{id: string, label: string, type: string, out: string[], in: string[], degree: number}>}
 */
export function adjacencyRows(nodes, edges, directed) {
  const byId = new Map(nodes.map((n) => [n.id, { id: n.id, label: n.label, type: n.type || '', out: [], in: [], degree: 0 }]));
  const name = (id, e) => (byId.has(id) ? byId.get(id).label : id) + (e.label ? ' (' + e.label + ')' : '') + (e.count > 1 ? ' ×' + e.count : '');
  edges.forEach((e) => {
    const a = byId.get(e.source), b = byId.get(e.target);
    if (!a || !b) { return; }
    a.out.push(name(e.target, e)); a.degree++;
    if (directed) { b.in.push(name(e.source, e)); } else { b.out.push(name(e.source, e)); }
    b.degree++;
  });
  return Array.from(byId.values()).sort((x, y) => x.label.localeCompare(y.label));
}
