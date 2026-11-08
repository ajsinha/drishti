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
/* The graph's tables, each a drishti-grid (never a table of its own): the selection under the canvas (inspector and terminal modes), the
   Nodes, Edges and Path tabs of the table mode (g5 B) with its filter, "selected only" and "Select these on the graph", and the adjacency
   table, the text alternative (▦). The Nodes table and the graph select each other's rows (a selection that came from the other side is
   not sent back); a row click flies the view to the node. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { Grid, GRID_EVENTS, VIEW_EVENTS } from '../../drishti-grid/src/drishti_index.js';
import { adjacencyRows } from './drishti_graph-export.js';

/** The columns of the node tables: label, type, id, the measures, the degree. */
export function nodeColumns(G) {
  return [{ field: 'label', header: 'Node', width: 200 }, { field: 'type', header: 'Type', width: 110, valueGetter: (r) => G.typeLabel(r.type) }, { field: 'id', header: 'Id', width: 150 }]
    .concat(G.measureList().map((m) => ({ field: m.field, header: (m.additive === false ? '◆ ' : '') + (m.label || m.field), type: 'number', format: m.format || 'compact', width: 120, additive: m.additive !== false })))
    .concat([{ field: '_degree', header: 'Degree', type: 'number', format: 'integer', width: 80 }]);
}

const EDGE_COLUMNS = [{ field: 'from', header: 'From', width: 200 }, { field: 'to', header: 'To', width: 200 }, { field: 'label', header: 'Label', width: 140 },
  { field: 'type', header: 'Type', width: 110 }, { field: 'weight', header: 'Weight', type: 'number', format: 'compact', width: 100 }, { field: 'count', header: 'Links', type: 'number', format: 'integer', width: 70 }];

const ADJ_COLUMNS = [{ field: 'label', header: 'Node', width: 200 }, { field: 'type', header: 'Type', width: 110 }, { field: 'outText', header: 'Points to', width: 320 },
  { field: 'inText', header: 'Pointed to by', width: 320 }, { field: 'degree', header: 'Edges', type: 'number', format: 'integer', width: 70 }];

/**
 * The graph's tables in its shell.
 */
export class GraphTables {
  /**
   * @param {Object} G the Graph
   * @param {Object<string, HTMLElement>} P the shell's parts
   */
  constructor(G, P) {
    this.G = G; this.P = P; this.doc = G.doc;
    this.mode = G.state().mode;
    this.tab = this.mode === 'table' ? 'nodes' : 'selection';
    this.q = ''; this.selOnly = false; this.applying = false;
    this.adj = null;
    this.makeTabs();
    this.stopEvents(P.tableHost); this.stopEvents(P.adj);
    this.grid = new Grid(P.tableHost, { columns: nodeColumns(G), rows: [], getRowId: (r) => r.id, density: G.state().density, chrome: false, rowSelection: 'multiple', clientAggregation: false });
    this.grid.model.setEventTarget(null);                // the tables' own events stay inside: the graph's host hears the graph's
    this.offs = [
      this.grid.model.events.on('selectionChanged', (p) => {
        if (this.applying || this.tab !== 'nodes') { return; }
        this.G.select((p && p.ids) || [], { from: 'table' });
      }),
      this.grid.model.events.on('cellClicked', (ctx) => {
        if (!ctx || ctx.rowId === undefined) { return; }
        if (this.tab === 'edges') { const e = ctx.data; if (e) { this.G.select([e.source, e.target]); this.G.centerOn(e.source); } return; }
        if (this.tab === 'path') { if (ctx.data && ctx.data.nodeId) { this.G.centerOn(ctx.data.nodeId); } return; }
        if (this.tab === 'selection') { this.G.centerOn(ctx.rowId); this.G.setFocus(ctx.rowId); }
      })
    ];
  }

  /** @private the tables' own DOM events stay inside: a host listening on the graph hears the graph's events only */
  stopEvents(el) {
    GRID_EVENTS.concat(VIEW_EVENTS).forEach((n) => el.addEventListener('drishti:' + n.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), (e) => e.stopPropagation()));
  }

  /** @private the tabs, and the table mode's filter */
  makeTabs() {
    const doc = this.doc, tabs = this.mode === 'table' ? [['nodes', 'Nodes'], ['edges', 'Edges'], ['path', 'Path']] : [['selection', 'Selection']];
    const parts = tabs.map(([id, text]) => h(doc, 'button', { cls: 'dn-tab' + (this.tab === id ? ' dn-on' : ''), text, attrs: { type: 'button', role: 'tab', 'aria-selected': String(this.tab === id), 'data-tab': id },
      on: { click: () => { this.tab = id; this.makeTabs(); this.refresh(true); } } }));
    parts.push(h(doc, 'span', { cls: 'dn-sp' }));
    if (this.mode === 'table') {
      const q = h(doc, 'input', { cls: 'dn-input', attrs: { type: 'search', placeholder: 'Filter rows', 'aria-label': 'Filter rows', 'data-ctl': 'rows', value: this.q }, on: { input: (e) => { this.q = e.target.value; this.grid.model.setQuickFilter(this.q); } } });
      const only = h(doc, 'input', { attrs: { type: 'checkbox', 'data-ctl': 'selOnly' }, on: { change: (e) => { this.selOnly = e.target.checked; this.refresh(true); } } });
      only.checked = this.selOnly;
      parts.push(q, h(doc, 'label', { cls: 'dn-lbl' }, [only, ' selected only']),
        h(doc, 'button', { cls: 'dn-btn', text: 'Select these on the graph', attrs: { type: 'button', 'data-act': 'selrows' }, on: { click: () => this.selectShown() } }));
    } else {
      parts.push(h(doc, 'span', { cls: 'dn-lbl', text: 'linked: a row click flies to the node' }));
    }
    this.P.tabs.replaceChildren(...parts);
  }

  /** @private the rows of the table mode's filter, selected on the graph */
  selectShown() {
    const m = this.grid.model, ids = [];
    for (let i = 0; i < m.rowCount(); i++) { const id = m.idAt(i); if (id !== null) { ids.push(this.tab === 'edges' ? null : id); } }
    this.G.select(ids.filter(Boolean));
  }

  /**
   * Draws the current tab's rows again (after a change of the graph or of the selection).
   * @param {boolean} [columnsToo] the tab changed: its columns too
   * @returns {void}
   */
  refresh(columnsToo) {
    const G = this.G, sel = new Set(G.selection()), label = (id) => { const n = G.nodeOrGroup(id); return n ? n.label : id; };
    let rows, cols;
    if (this.tab === 'edges') {
      cols = EDGE_COLUMNS;
      rows = G.visibleEdges().filter((e) => !this.selOnly || sel.has(e.source) || sel.has(e.target)).map((e) => ({ id: e.id, source: e.source, target: e.target, from: label(e.source), to: label(e.target), label: e.label || '', type: e.type || '', weight: e.weight, count: e.count || 1 }));
    } else if (this.tab === 'path') {
      cols = [{ field: 'step', header: '#', type: 'number', format: 'integer', width: 50 }, { field: 'label', header: 'Node', width: 220 }, { field: 'type', header: 'Type', width: 120 }, { field: 'via', header: 'Via', width: 200 }];
      const p = G.pathNow();
      rows = p ? p.nodes.map((id, i) => ({ id: 'p' + i, nodeId: id, step: i, label: label(id), type: G.typeLabel((G.nodeOrGroup(id) || {}).type), via: i ? (G.edgeLabel(p.edges[i - 1]) || '') : '' })) : [];
    } else {
      cols = nodeColumns(G);
      const ns = this.tab === 'selection' ? G.selection().map((id) => G.nodeOrGroup(id)).filter(Boolean) : G.visibleNodes().filter((n) => !this.selOnly || sel.has(n.id));
      rows = ns.map((n) => Object.assign({}, n, { _degree: n.members ? null : G.model.degree(n.id) }));
    }
    if (columnsToo) { this.grid.model.columns.setColumns(cols); }
    this.grid.setRows(rows);
    this.syncSelection();
    this.P.tableHost.setAttribute('data-tab', this.tab);
    this.P.tableHost.setAttribute('aria-label', this.tab === 'selection' ? 'The selected nodes' : 'The ' + this.tab);
    if (this.adj && !this.P.adj.hidden) { this.refreshAdjacency(); }
  }

  /** The graph's selection on the Nodes table (not sent back to the graph). */
  syncSelection() {
    if (this.tab !== 'nodes') { return; }
    this.applying = true;
    try { this.grid.model.selection.selectAll(this.G.selection()); } finally { this.applying = false; }
  }

  /**
   * Shows or hides the adjacency table (the text alternative).
   * @param {boolean} on
   * @returns {void}
   */
  showAdjacency(on) {
    this.P.adj.hidden = !on;
    if (!on) { return; }
    if (!this.adj) {
      this.P.adj.replaceChildren(h(this.doc, 'h3', { cls: 'dn-sec', text: 'Adjacency: each node, what it points to and what points to it' }), this.adjHost = h(this.doc, 'div', { cls: 'dn-adj-grid' }));
      this.adj = new Grid(this.adjHost, { columns: ADJ_COLUMNS, rows: [], getRowId: (r) => r.id, density: this.G.state().density, chrome: false, rowSelection: 'single', clientAggregation: false });
      this.adj.model.setEventTarget(null);
    }
    this.refreshAdjacency();
  }

  /** @private */
  refreshAdjacency() {
    const G = this.G;
    this.adj.setRows(adjacencyRows(G.visibleNodes(), G.visibleEdges(), G.model.directed).map((r) => Object.assign(r, { type: G.typeLabel(r.type), outText: r.out.join(', '), inText: r.in.join(', ') })));
  }

  /**
   * The density of the tables.
   * @param {string} d
   * @returns {void}
   */
  setDensity(d) { this.grid.setDensity(d); if (this.adj) { this.adj.setDensity(d); } }

  /** Removes the tables. */
  destroy() { this.offs.forEach((f) => f()); this.grid.destroy(); if (this.adj) { this.adj.destroy(); } }
}
