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
/* The graph component (new Graph(el, options); <drishti-graph> wraps it): what a person and a host do with it. Selection (one, several,
   by lasso; linked on drishti-core's bus with grids, charts and trees on the same channel), the shortest path, expand on demand (the host's
   lazy hook, else what was collapsed or hidden comes back), collapse, groups by an attribute opened one by one, pin and unpin, hide,
   types drawn or not, the encodings, the layout, the mode and the density, Explain (the host's hook, drawn by drishti-core's
   renderExplain), export (PNG, SVG, CSV, JSON), the adjacency table, live changes drawn at most once a throttle window, the terminal's
   command line, and the state for a host to keep. */
import { h, renderExplain, announce, download as saveFile, SELECTION_EVENT, bus as sharedBus, DENSITIES } from '../../drishti-core/src/drishti_index.js';
import { GraphBase } from './drishti_graph-base.js';
import { GRAPH_MODES, answerLine, inspectorContent } from './drishti_graph-chrome.js';
import { shortestPath } from './drishti_graph-algo.js';
import { LAYOUTS } from './drishti_graph-layout.js';
import { toSvg, graphCsv } from './drishti_graph-export.js';
import { nodeColumns } from './drishti_graph-tables.js';
import { runCommand, complete, FUNCTION_KEYS } from './drishti_graph-terminal.js';

/**
 * The graph, drawn: the plain JavaScript API every host uses.
 */
export class Graph extends GraphBase {
  /**
   * @param {HTMLElement} host
   * @param {import('./drishti_graph-base.js').GraphOptions} options
   */
  constructor(host, options) {
    super(host, options);
    this.queue = []; this.flushTimer = 0;
    this.bus = this.o.bus || sharedBus;
    if (this.o.link && this.o.link.channel) {
      this.offLink = this.bus.on(SELECTION_EVENT, (p) => { if (p && p.source !== this && p.channel === this.o.link.channel) { this.select((p.ids || []).filter((id) => this.byId.has(id)), { silent: true }); } });
    }
  }

  // ---- selection ---------------------------------------------------------------------------------------------------------------------
  /** The selected node ids. @returns {string[]} */
  selection() { return Array.from(this.selected); }

  /**
   * Selects nodes (add: to the selection; silent: not published on the bus).
   * @param {string[]} ids
   * @param {{add: boolean, silent: boolean, from: string}} [o]
   * @returns {void}
   */
  select(ids, o) {
    o = o || {};
    const next = new Set(o.add ? this.selected : []);
    (ids || []).forEach((id) => { if (this.byId.has(id)) { if (o.add && this.selected.has(id) && ids.length === 1) { next.delete(id); } else { next.add(id); } } });
    if (next.size === this.selected.size && [...next].every((id) => this.selected.has(id))) { return; }
    this.selected = next;
    this.afterSelection(o);
  }

  /** Clears the selection. @returns {void} */
  clearSelection() { if (!this.selected.size) { return; } this.selected = new Set(); this.afterSelection({}); }

  /** @private */
  afterSelection(o) {
    if (this.P.insp) { this.P.insp.replaceChildren(this.inspector()); }
    if (this.P.card && this.o.card !== false) { this.cardFor(this.selection()[0]); }
    if (this.tables) { if (o.from === 'table') { this.tables.syncSelection(); } else { this.tables.refresh(false); } }
    this.draw();
    const ids = this.selection();
    this.emit('selectionChanged', { ids, source: o.from || (o.silent ? 'bus' : 'graph') });
    if (this.o.link && this.o.link.channel && !o.silent) { this.bus.emit(SELECTION_EVENT, { channel: this.o.link.channel, ids, source: this }); }
  }

  /** @private the inspector's content (chrome) */
  inspector() { return inspectorContent(this); }

  /** @private the table mode's card at the node */
  cardFor(id) {
    const card = this.P.card;
    if (!id) { card.hidden = true; return; }
    const s = this.toScreen(id);
    card.replaceChildren(h(this.doc, 'button', { cls: 'dn-x', text: '×', attrs: { type: 'button', 'aria-label': 'Close' }, on: { click: () => this.clearSelection() } }), this.inspector());
    card.hidden = false;
    if (s) { card.style.setProperty('left', Math.max(8, Math.min(this.size.w - 310, s.x + 16)) + 'px'); card.style.setProperty('top', Math.max(8, Math.min(this.size.h - 200, s.y - 30)) + 'px'); }
  }

  /** @private a click on a node: select, open a group, emit */
  nodeClicked(id, o) {
    this.setFocus(id, { quiet: true });
    this.select([id], { add: o && o.add });
    const n = this.nodeOrGroup(id);
    this.emit('nodeClicked', { id, node: n, add: !!(o && o.add) });
    if (this.o.openOnClick && n && n.link && !(o && o.add)) { this.openLink(n); }
  }

  /** @private a double click: a group opens, a node expands */
  nodeDoubleClicked(id) {
    const n = this.byId.get(id);
    this.emit('nodeDoubleClicked', { id, node: n });
    if (n && n.members) { this.openGroup(id); } else { this.expand(id); }
  }

  /**
   * The keyboard's node (announced, outlined).
   * @param {?string} id
   * @param {{quiet: boolean}} [o]
   * @returns {void}
   */
  setFocus(id, o) {
    if (!id || id === this.focusId) { return; }
    this.focusId = id;
    const n = this.byId.get(id), s = this.toScreen(id);
    if (s && (s.x < 20 || s.y < 20 || s.x > this.size.w - 20 || s.y > this.size.h - 20)) { this.centerOn(id); }
    if (n && !(o && o.quiet)) { announce(n.label + ', ' + (this.typeLabel(n.type) || 'node') + ', ' + this.neighbourList(id).length + ' neighbours' + (this.selected.has(id) ? ', selected' : '')); }
    this.emit('focusChanged', { id });
    this.draw();
  }

  /**
   * Marks the nodes a search matched: the others are dimmed (null: none marked). The selection is kept.
   * @param {?string[]} ids
   * @returns {void}
   */
  highlight(ids) { this.highlighted = ids ? new Set(ids) : null; this.draw(); }

  /** @private the toolbar's Find */
  findAndShow(words) { const n = this.model.find(words, 1)[0]; if (!n || !this.byId.has(n.id)) { announce('No node matches ' + words); return null; } this.select([n.id]); this.centerOn(n.id); this.setFocus(n.id); return n.id; }

  // ---- the path ----------------------------------------------------------------------------------------------------------------------
  /**
   * The shortest path between two visible nodes, highlighted (the fewest hops, or the least cost by 1 / weight with weightedPath).
   * @param {string} from
   * @param {string} to
   * @returns {?{nodes: string[], edges: string[], cost: number}}
   */
  findPath(from, to) {
    const p = shortestPath({ nodes: this.vis.nodes, edges: this.vis.edges }, from, to, { directed: false, cost: this.o.weightedPath ? (e) => 1 / Math.max(1e-9, e.weight) : null });
    this.path = p;
    if (this.tables) { this.tables.refresh(false); }
    this.draw();
    if (p) { this.fit(p.nodes); this.emit('pathFound', Object.assign({ from, to }, p)); announce('Path of ' + (p.nodes.length - 1) + ' hops: ' + p.nodes.map((id) => this.nodeOrGroup(id).label).join(', ')); }
    else { announce('No path between them'); this.emit('pathFound', { from, to, nodes: [], edges: [], cost: null }); }
    return p;
  }

  /** @private the toolbar's Path: the two ends by id or words */
  pathFromInputs() {
    const a = this.model.find(this.P.from.value, 1)[0], b = this.model.find(this.P.to.value, 1)[0];
    return a && b ? this.findPath(a.id, b.id) : (announce('Give two nodes for the path'), null);
  }

  /** @private "Path from here" */
  setPathEnd(which, id) { this.P[which].value = id; if (which === 'from' && this.P.to) { this.P.to.focus(); } }

  /** The path drawn, or null. @returns {?{nodes: string[], edges: string[], cost: number}} */
  pathNow() { return this.path; }

  /** Clears the path. @returns {void} */
  clearPath() { if (!this.path) { return; } this.path = null; if (this.tables) { this.tables.refresh(false); } this.draw(); this.emit('pathCleared', {}); }

  // ---- expand, collapse, groups, pins, hiding, types ----------------------------------------------------------------------------------
  /**
   * Shows a node's neighbours: the host's lazy hook (expand option) adds them; without one, what was collapsed below or hidden next to it
   * comes back.
   * @param {string} id
   * @returns {Promise<{added: number}>}
   */
  async expand(id) {
    const n = this.model.node(id);
    if (!n) { return { added: 0 }; }
    let added = 0;
    this.st.collapsed.delete(id);
    this.model.neighbours(id).forEach((x) => { if (this.st.hidden.delete(x)) { added++; } });
    if (this.o.expand) {
      try {
        const got = (await this.o.expand(n)) || {};
        const nodes = (got.nodes || []).filter((x) => !this.model.node(x.id)), edges = (got.edges || []).filter((e) => !this.model.edges.has(String(e.id)) && !this.model.edgesOf(String(e.source || e.from), 'out').some((y) => y.target === String(e.target || e.to)));
        if (nodes.length || edges.length) { this.model.applyTransaction({ addNodes: nodes, addEdges: edges }); added += nodes.length; }
      } catch (e) { this.emit('error', { action: 'expand', id, error: e }); announce('Could not expand ' + n.label); }
    }
    await this.recompute(true);
    this.emit('expanded', { id, added });
    return { added };
  }

  /**
   * Hides what is below a node (reached only through it).
   * @param {string} id
   * @returns {Promise<void>}
   */
  collapse(id) { this.st.collapsed.add(id); this.emit('collapsed', { id }); return this.recompute(true); }

  /**
   * Folds the nodes into groups by an attribute (null: none).
   * @param {?string} attr
   * @returns {Promise<void>}
   */
  setGroupBy(attr) { this.st.groupBy = attr || null; this.st.openGroups = new Set(); this.clearPath(); this.emit('groupChanged', { groupBy: this.st.groupBy }); return this.recompute(true); }

  /** Opens a group: its members drawn. @param {string} gid @returns {Promise<void>} */
  openGroup(gid) { this.st.openGroups.add(gid); this.selected.delete(gid); this.emit('groupChanged', { groupBy: this.st.groupBy, opened: gid }); return this.recompute(true); }

  /** Pins nodes where they are (every layout keeps them). @param {string[]} ids @returns {void} */
  pin(ids) { (ids || []).forEach((id) => { const n = this.model.node(id); if (n) { n.pinned = true; } }); this.renderParts(); this.draw(); this.emit('pinned', { ids }); }

  /** Unpins nodes. @param {string[]} ids @returns {void} */
  unpin(ids) { (ids || []).forEach((id) => { const n = this.model.node(id); if (n) { n.pinned = false; } }); this.renderParts(); this.draw(); this.emit('unpinned', { ids }); }

  /** Leaves nodes out of the picture. @param {string[]} ids @returns {Promise<void>} */
  hide(ids) { (ids || []).forEach((id) => this.st.hidden.add(id)); this.emit('hidden', { ids }); return this.recompute(true); }

  /** Draws every node again (hidden and collapsed ones). @returns {Promise<void>} */
  showAll() { this.st.hidden = new Set(); this.st.collapsed = new Set(); return this.recompute(true); }

  /** Draws a type or leaves it out. @param {string} type @returns {Promise<void>} */
  toggleType(type) { if (!this.st.typesOff.delete(type)) { this.st.typesOff.add(type); } this.emit('filterChanged', { typesOff: Array.from(this.st.typesOff) }); return this.recompute(true); }

  /** Draws only these types. @param {string[]} types @returns {Promise<void>} */
  setTypes(types) { this.st.typesOff = new Set(this.model.types().map((t) => t.type).filter((t) => !types.includes(t))); this.emit('filterChanged', { typesOff: Array.from(this.st.typesOff) }); return this.recompute(true); }

  /** Node size by a measure (null: one size). @param {?string} m @returns {void} */
  setSizeBy(m) { this.st.sizeBy = m || null; this.emit('encodingChanged', { sizeBy: this.st.sizeBy }); this.recompute(false); this.reindex(); }

  /** Node colour by an attribute or a measure. @param {string} f @returns {void} */
  setColourBy(f) { this.st.colourBy = f || 'type'; this.emit('encodingChanged', { colourBy: this.st.colourBy }); this.recompute(false); }

  /**
   * Switches the layout (pinned nodes stay; the move is animated unless motion is reduced).
   * @param {string} name one of LAYOUTS
   * @returns {Promise<void>}
   */
  setLayout(name) {
    if (!LAYOUTS.includes(name)) { throw new Error('no graph layout ' + name + ' (' + LAYOUTS.join(', ') + ')'); }
    this.st.layout = name; this.keepForce = false; this.fitted = false;
    this.P.shell.querySelectorAll('[data-layout]').forEach((b) => b.setAttribute('aria-pressed', String(b.getAttribute('data-layout') === name)));
    this.emit('layoutChanged', { layout: name });
    return this.recompute(true);
  }

  /** Switches the mode (g5 A, B or C; the same graph, selection and layout). @param {string} mode @returns {void} */
  setMode(mode) {
    if (!GRAPH_MODES.includes(mode)) { throw new Error('no graph mode ' + mode + ' (' + GRAPH_MODES.join(', ') + ')'); }
    if (mode === this.st.mode) { return; }
    this.st.mode = mode; this.build(); this.renderParts(); this.reindex(); this.fit();
    this.emit('modeChanged', { mode });
  }

  /** Switches the density. @param {string} d @returns {void} */
  setDensity(d) {
    if (!DENSITIES.includes(d)) { throw new Error('no density ' + d + ' (' + DENSITIES.join(', ') + ')'); }
    this.st.density = d; this.host.setAttribute('data-density', d); this.themeNow = null;
    if (this.tables) { this.tables.setDensity(d); }
    this.recompute(false); this.emit('densityChanged', { density: d });
  }

  // ---- Explain, links, export, the adjacency table, the lasso -------------------------------------------------------------------------
  /**
   * Explains a node through the host's hook, drawn in the inspector (A), the card (B) or the answers (C).
   * @param {string} id
   * @returns {Promise<*>}
   */
  explain(id) {
    const n = this.nodeOrGroup(id);
    if (!n) { return Promise.resolve(null); }
    if (!this.selected.has(id) || this.selected.size > 1) { this.select([id]); }
    const ctx = { kind: 'node', id: n.id, label: n.label, type: n.type, node: n, members: n.members || null, measures: this.measureList() };
    const box = this.explainBox && this.explainBox.isConnected ? this.explainBox : null;
    const draw = (st) => { if (box) { box.hidden = false; renderExplain(box, st, 'card', { prefix: 'dn', onClose: () => { box.hidden = true; } }); } };
    this.emit('explainOpened', ctx);
    if (!this.o.explain) { draw({ result: 'This graph has no Explain hook: ' + n.label }); return Promise.resolve(null); }
    draw({ loading: true });
    return Promise.resolve().then(() => this.o.explain(ctx)).then((res) => { draw({ result: res }); this.emit('explainShown', { context: ctx, result: res }); return res; },
      (e) => { draw({ error: e }); this.emit('error', { action: 'explain', id, error: e }); return null; });
  }

  /** @private a node that names an entity, opened by the host */
  openLink(n) { if (n.link && this.o.onLink) { this.o.onLink(n.link, n); } this.emit('linkOpened', { id: n.id, link: n.link || null }); }

  /**
   * Exports what is drawn: png (2×), svg, csv (nodes then edges) or json (the data); saved through the download option or the browser.
   * @param {('png'|'svg'|'csv'|'json')} kind
   * @returns {{name: string, content: (string|Blob), type: string}}
   */
  export(kind) {
    const base = (this.o.title || 'graph').replace(/[^\w.-]+/g, '-').toLowerCase(), f = this.frame();
    let out;
    if (kind === 'svg') { out = { name: base + '.svg', content: toSvg(f, this.box, { title: this.o.title }), type: 'image/svg+xml' }; }
    else if (kind === 'csv') { out = { name: base + '.csv', content: graphCsv(this.vis.nodes.map((n) => Object.assign({}, n, { _degree: this.model.degree(n.id) })), this.vis.edges, nodeColumns(this)), type: 'text/csv' }; }
    else if (kind === 'json') { out = { name: base + '.json', content: JSON.stringify(this.model.toData(), null, 1), type: 'application/json' }; }
    else if (kind === 'png') {
      const c = this.doc.createElement('canvas'), R = new (this.renderer.constructor)(c);
      R.resize(this.size.w * 2, this.size.h * 2, 1);
      R.ctx.fillStyle = f.theme.surface; R.ctx.fillRect(0, 0, c.width, c.height);
      R.draw(f, { k: this.t.k * 2, tx: this.t.tx * 2, ty: this.t.ty * 2 });
      out = { name: base + '.png', content: c.toDataURL('image/png'), type: 'image/png' };
    } else { throw new Error('export png, svg, csv or json'); }
    if (this.o.download) { this.o.download(out.name, out.content, out.type); } else { saveFile(this.doc, out.name, out.content, out.type); }
    this.emit('exported', { kind, name: out.name });
    return out;
  }

  /** Shows or hides the adjacency table (the text alternative). @param {boolean} [on] @returns {boolean} shown */
  toggleAdjacency(on) {
    const show = on === undefined ? this.P.adj.hidden : !!on;
    if (this.tables) { this.tables.showAdjacency(show); }
    this.P.adjBtn.setAttribute('aria-pressed', String(show));
    return show;
  }

  /** The lasso tool on or off (Alt-drag is the lasso without it). @returns {boolean} */
  toggleLasso() { this.lassoOn = !this.lassoOn; this.P.lassoBtn.setAttribute('aria-pressed', String(this.lassoOn)); this.P.canvas.classList.toggle('dn-lasso', this.lassoOn); return this.lassoOn; }

  /** @private the lasso being drawn (screen points), or null */
  drawLasso(pts) { this.lassoPts = pts ? pts.slice() : null; this.drawNow(); }

  // ---- data and live changes ---------------------------------------------------------------------------------------------------------
  /**
   * Replaces the data (the view's state stays; a new layout).
   * @param {import('./drishti_graph-model.js').GraphData} data
   * @returns {Promise<void>}
   */
  setData(data) { this.model.setData(data); this.pos = new Map(); this.fitted = false; this.keepForce = false; this.selected = new Set(); this.path = null; return this.recompute(true); }

  /**
   * A live change by id (nodes and edges added, updated, removed): drawn at most once a throttle window, the picture moving little (the
   * force layout starts from where the nodes are).
   * @param {import('./drishti_graph-model.js').GraphTransaction} tx
   * @returns {Promise<void>} resolves when it is drawn
   */
  applyTransaction(tx) {
    this.queue.push(tx);
    const ms = (this.o.live && this.o.live.throttleMs !== undefined) ? this.o.live.throttleMs : 200;
    if (!this.pending) {
      this.pending = new Promise((resolve) => {
        this.flushTimer = this.win.setTimeout(() => { this.flushTimer = 0; const done = this.flush(); this.pending = null; done.then(resolve); }, ms);
      });
    }
    return this.pending;
  }

  /** @private applies the queued changes and draws once */
  flush() {
    const q = this.queue; this.queue = [];
    let topology = false;
    q.forEach((tx) => { const r = this.model.applyTransaction(tx); if (r.addedNodes.length || r.removedNodes.length || r.addedEdges.length || r.removedEdges.length) { topology = true; } });
    return this.recompute(topology);
  }

  // ---- the terminal ------------------------------------------------------------------------------------------------------------------
  /** @private the GRAPH> line: Enter runs, ↑ ↓ history, Tab completes, / focuses, F-keys */
  wireTerminal() {
    const inp = this.P.input;
    this.history = this.history || []; this.hi = this.history.length;
    inp.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); const line = inp.value; inp.value = ''; this.command(line); }
      else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') { e.preventDefault(); this.hi = Math.max(0, Math.min(this.history.length, this.hi + (e.key === 'ArrowUp' ? -1 : 1))); inp.value = this.history[this.hi] || ''; }
      else if (e.key === 'Tab') {
        e.preventDefault();
        const c = complete(this, inp.value);
        if (c.length === 1) { inp.value = inp.value.replace(/\S*$/, '') + c[0] + ' '; } else if (c.length) { this.answer([{ text: c.join('  '), tone: 'dim' }]); }
      } else if (/^F\d+$/.test(e.key)) { e.preventDefault(); this.functionKey(e.key); }
    });
    this.host.addEventListener('keydown', (e) => { if (e.key === '/' && e.target !== inp && this.st.mode === 'terminal') { e.preventDefault(); inp.focus(); } });
    if (!this.answered) { this.answer([{ text: 'drishti-graph · ' + this.model.nodes.size + ' nodes, ' + this.model.edges.size + ' edges', tone: 'dim' }, { text: 'type help for the commands; click a node, or a node in these answers', tone: 'dim' }]); }
  }

  /**
   * Runs a terminal command (any mode may call it; the terminal mode prints the answer).
   * @param {string} line
   * @returns {Promise<Array<{text: string, tone: string, node: string}>>}
   */
  async command(line) {
    if (line.trim()) { this.history = (this.history || []).concat([line]).slice(-100); this.hi = this.history.length; }
    const out = [{ text: 'GRAPH> ' + line, tone: 'echo' }].concat(await runCommand(this, line));
    this.answer(out);
    this.emit('command', { line, lines: out });
    return out;
  }

  /** @private the function keys: F2 explains the selection, F7 the path between the first two selected, the others their command */
  functionKey(k) {
    const sel = this.selection();
    if (k === 'F7') { return sel.length > 1 ? this.command('path ' + sel[0] + ' ' + sel[1]) : this.command('help'); }
    return FUNCTION_KEYS[k] ? this.command(FUNCTION_KEYS[k]) : null;
  }

  /** @private prints answer lines (terminal mode) */
  answer(lines) {
    if (!this.P.out) { return; }
    this.answered = true;
    lines.forEach((l) => this.P.out.appendChild(answerLine(this, l)));
    while (this.P.out.childNodes.length > 400) { this.P.out.removeChild(this.P.out.firstChild); }
    this.P.out.scrollTop = this.P.out.scrollHeight;
  }

  // ---- state -------------------------------------------------------------------------------------------------------------------------
  /** The view's state for a host to keep (mode, density, layout, encodings, filters, selection, pins). @returns {Object} */
  getState() {
    return Object.assign(this.state(), { hidden: Array.from(this.st.hidden), collapsed: Array.from(this.st.collapsed), openGroups: Array.from(this.st.openGroups), selection: this.selection(),
      pinned: this.model.nodeList().filter((n) => n.pinned).map((n) => ({ id: n.id, x: (this.pos.get(n.id) || {}).x, y: (this.pos.get(n.id) || {}).y })) });
  }

  /** Puts a kept state back. @param {Object} s @returns {Promise<void>} */
  async setState(s) {
    if (s.mode && s.mode !== this.st.mode) { this.setMode(s.mode); }
    if (s.density) { this.setDensity(s.density); }
    Object.assign(this.st, { layout: s.layout || this.st.layout, groupBy: s.groupBy === undefined ? this.st.groupBy : s.groupBy, sizeBy: s.sizeBy === undefined ? this.st.sizeBy : s.sizeBy,
      colourBy: s.colourBy || this.st.colourBy, typesOff: new Set(s.typesOff || []), hidden: new Set(s.hidden || []), collapsed: new Set(s.collapsed || []), openGroups: new Set(s.openGroups || []) });
    (s.pinned || []).forEach((p) => { const n = this.model.node(p.id); if (n) { n.pinned = true; if (typeof p.x === 'number') { this.pos.set(p.id, { x: p.x, y: p.y }); } } });
    this.fitted = false;
    await this.recompute(true);
    if (s.selection) { this.select(s.selection); }
  }

  /** Removes the graph from its element. @returns {void} */
  destroy() {
    this.run++;
    if (this.offLink) { this.offLink(); }
    if (this.offPointer) { this.offPointer(); }
    if (this.tables) { this.tables.destroy(); }
    if (this.ro) { this.ro.disconnect(); }
    if (this.mo) { this.mo.disconnect(); }
    if (this.flushTimer) { this.win.clearTimeout(this.flushTimer); }
    this.host.replaceChildren();
    this.host.classList.remove('dn');
    this.renderer = null;
  }
}
