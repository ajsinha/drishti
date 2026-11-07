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
/* The graph component's engine room (GraphBase; Graph adds the actions): the options, the state of the view, the pipeline from the model
   to the picture (what is visible, the encodings, the layout, the hit index), the layout run a few steps a frame and the move between two
   layouts animated (neither when motion is reduced), the transform (zoom, pan, fit, centre), drawing once a frame, the minimap, the
   tooltip, the theme followed (a change of the page's data-theme redraws) and the size followed. Events go on the component's bus and,
   as drishti:<name> DOM events, on its host. */
import { EventBus, visualTheme, formatterFor, DENSITIES } from '../../drishti-core/src/drishti_index.js';
import { GraphModel } from './drishti_graph-model.js';
import { visibleGraph, encode } from './drishti_graph-scene.js';
import { LAYOUTS, layout as runLayout, bounds } from './drishti_graph-layout.js';
import { forceSteps } from './drishti_graph-force.js';
import { CanvasRenderer, HitIndex } from './drishti_graph-canvas.js';
import { GRAPH_MODES, buildShell, renderRail, renderStatus, inspectorContent, typeList } from './drishti_graph-chrome.js';
import { GraphTables } from './drishti_graph-tables.js';
import { installPointer } from './drishti_graph-pointer.js';

/** The events of the graph, on its bus and as drishti:<kebab-name> DOM events on its host. */
export const GRAPH_EVENTS = Object.freeze(['nodeClicked', 'nodeDoubleClicked', 'selectionChanged', 'focusChanged', 'pathFound', 'pathCleared', 'layoutChanged', 'layoutDone',
  'modeChanged', 'densityChanged', 'groupChanged', 'encodingChanged', 'filterChanged', 'expanded', 'collapsed', 'pinned', 'unpinned', 'hidden', 'explainOpened',
  'explainShown', 'nodesChanged', 'exported', 'linkOpened', 'command', 'error']);

/**
 * The graph's options.
 * @typedef {Object} GraphOptions
 * @property {import('./drishti_graph-model.js').GraphData} data the nodes and edges
 * @property {('inspector'|'table'|'terminal')} [mode='inspector'] g5 A (the default), B or C
 * @property {('comfortable'|'compact'|'terminal')} [density='compact']
 * @property {('force'|'layered'|'radial'|'circular'|'grouped'|'preset')} [layout='force']
 * @property {import('./drishti_graph-layout.js').LayoutOptions} [layoutOptions] seed, direction, root, rank, groupBy, spacing
 * @property {string} [title]
 * @property {Object<string, import('./drishti_graph-scene.js').GraphType>} [types] each type's label, shape and colour
 * @property {import('./drishti_graph-scene.js').GraphMeasure[]} [measures] the measures the nodes carry (their words, format, additivity)
 * @property {?string} [groupBy] fold the nodes into groups by this attribute
 * @property {?string} [sizeBy] node size by this measure
 * @property {?string} [colourBy='type'] node colour by an attribute or a measure
 * @property {string[]} [typesOff] types not drawn at first
 * @property {{channel: string}} [link] linked selection on drishti-core's bus (a drishti-grid joins with linkGrid, a chart with its link)
 * @property {function(Object): Promise<*>} [explain] the Explain hook: a node's context ({kind: 'node', id, label, type, node, members}) to an
 *   explanation (drishti-core's ExplainResult); drishti-insight's hook in the console
 * @property {function(Object): Promise<{nodes: Object[], edges: Object[]}>} [expand] the neighbours of a node, asked on demand (lazy)
 * @property {function(Object, Object): void} [onLink] a node that names an entity ({kind, id}) was opened
 * @property {{throttleMs: number}} [live] changes by applyTransaction are drawn at most once a window (default 200 ms)
 * @property {boolean} [minimap=true]
 * @property {boolean} [animate=true] false (or the page's reduced motion): no animated layout
 * @property {boolean} [tables=true] the tables under the canvas (the selection, or the table mode's tabs)
 * @property {boolean} [card=true] the table mode's card at the selected node (false: the host shows the selection itself)
 * @property {number} [height] the canvas's height in pixels (default by mode)
 * @property {boolean} [openOnClick=false] a click on a node that names an entity (link) opens it (the view's graph panel)
 * @property {boolean} [weightedPath=false] the shortest path by cost (1 / weight) rather than by hops
 * @property {function(string): void} [download] who saves an exported file (name, content, type); default the browser's download
 * @property {EventBus} [bus] the bus linked selection goes on (default drishti-core's shared bus)
 */

/** A camel name as a kebab one: selectionChanged -> selection-changed. */
const kebab = (s) => s.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());

/**
 * The graph's engine room: options, state, the pipeline and the drawing.
 */
export class GraphBase {
  /**
   * @param {HTMLElement} host the element the graph fills
   * @param {GraphOptions} options
   */
  constructor(host, options) {
    if (!host || !host.ownerDocument) { throw new Error('a graph needs an element to fill'); }
    const o = this.o = Object.assign({ mode: 'inspector', density: 'compact', layout: 'force', minimap: true, animate: true, tables: true }, options);
    if (!GRAPH_MODES.includes(o.mode)) { throw new Error('no graph mode ' + o.mode + ' (' + GRAPH_MODES.join(', ') + ')'); }
    if (!LAYOUTS.includes(o.layout)) { throw new Error('no graph layout ' + o.layout + ' (' + LAYOUTS.join(', ') + ')'); }
    this.host = host; this.doc = host.ownerDocument; this.win = this.doc.defaultView;
    this.events = new EventBus();
    this.layouts = LAYOUTS.filter((l) => l !== 'preset' || o.layout === 'preset' || (o.data && (o.data.nodes || []).some((n) => typeof n.x === 'number')));
    this.model = new GraphModel(o.data || { nodes: [] });
    this.model.events.on('nodesChanged', (d) => this.emit('nodesChanged', d));
    this.measures = {};
    (o.measures || []).forEach((m) => { this.measures[m.field] = m; });
    this.st = { mode: o.mode, density: DENSITIES.includes(o.density) ? o.density : 'compact', layout: o.layout, groupBy: o.groupBy || null, sizeBy: o.sizeBy || null,
      colourBy: o.colourBy || 'type', typesOff: new Set(o.typesOff || []), hidden: new Set(), collapsed: new Set(), openGroups: new Set() };
    this.selected = new Set(); this.path = null; this.pos = new Map(); this.frames = []; this.t = { k: 1, tx: 0, ty: 0 }; this.size = { w: 800, h: 500 };
    this.focusId = null; this.hoverId = null; this.lassoOn = false; this.lassoPts = null; this.layoutMs = null; this.fitted = false; this.raf = 0; this.run = 0;
    this.build();
    this.recompute(true);
  }

  // ---- the shell -------------------------------------------------------------------------------------------------------------------
  /** @private builds (or rebuilds, on a change of mode) the shell around the canvas */
  build() {
    if (this.offPointer) { this.offPointer(); }
    if (this.tables) { this.tables.destroy(); this.tables = null; }
    const host = this.host;
    host.classList.add('dn');
    host.setAttribute('data-mode', this.st.mode);
    host.setAttribute('data-density', this.st.density);
    this.P = buildShell(this);
    host.replaceChildren(this.P.shell);
    const hgt = this.o.height || (this.st.mode === 'inspector' ? 520 : this.st.mode === 'table' ? 460 : 420);
    this.P.cv.style.setProperty('height', hgt + 'px');
    this.renderer = new CanvasRenderer(this.P.canvas);
    this.miniRenderer = new CanvasRenderer(this.P.mini);
    if (this.o.tables !== false) { this.tables = new GraphTables(this, this.P); } else { this.P.tabs.hidden = true; this.P.tableHost.hidden = true; }
    this.offPointer = installPointer(this, this.P);
    if (this.st.mode === 'terminal') { this.wireTerminal(); }
    this.watch();
  }

  /** @private size and theme followed */
  watch() {
    if (this.ro) { this.ro.disconnect(); }
    const W = this.win;
    const sized = () => { const r = this.P.cv.getBoundingClientRect(); this.size = { w: Math.max(50, Math.round(r.width)), h: Math.max(50, Math.round(r.height)) }; this.renderer.resize(this.size.w, this.size.h, W.devicePixelRatio || 1); this.miniRenderer.resize(184, 124, W.devicePixelRatio || 1); };
    sized();
    if (W.ResizeObserver) { this.ro = new W.ResizeObserver(() => { const w0 = this.size.w; sized(); if (!this.fitted || Math.abs(w0 - this.size.w) > 40) { this.fit(); } else { this.draw(); } }); this.ro.observe(this.P.cv); }
    if (!this.mo && W.MutationObserver) {
      this.mo = new W.MutationObserver(() => { this.themeNow = null; this.recompute(false); });
      this.mo.observe(this.doc.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] });
    }
  }

  // ---- the pipeline ----------------------------------------------------------------------------------------------------------------
  /** @private the theme of the page at the density */
  theme() { if (!this.themeNow) { this.themeNow = visualTheme({ element: this.host, density: this.st.density }); } return this.themeNow; }

  /** @private the type order: the types given first, then as the data has them */
  typeOrder() { const out = Object.keys(this.o.types || {}); this.model.types().forEach((t) => { if (!out.includes(t.type)) { out.push(t.type); } }); return out; }

  /**
   * What is visible, its encodings and (when the topology changed) its layout; then a frame.
   * @param {boolean} relayout
   * @returns {Promise<void>}
   */
  recompute(relayout) {
    this.vis = visibleGraph(this.model, this.st, this.measures);
    const radius = this.st.density === 'comfortable' ? [6, 22] : this.st.density === 'terminal' ? [4, 15] : [5, 18];
    this.enc = encode(this.vis, this.st, { theme: this.theme(), types: this.o.types, typeOrder: this.typeOrder(), radius, measures: this.measures, format: (f, v) => this.formatMeasure(f, v) });
    this.byId = new Map(this.vis.nodes.map((n) => [n.id, n]));
    if (this.path && !this.path.nodes.every((id) => this.byId.has(id))) { this.path = null; }
    [...this.selected].forEach((id) => { if (!this.byId.has(id)) { this.selected.delete(id); } });
    this.renderParts();
    if (relayout) { return this.layoutNow(); }
    this.reindex();
    this.draw();
    return Promise.resolve();
  }

  /** @private the rail, the inspector and the tables after a change */
  renderParts() {
    renderRail(this, this.P);
    if (this.P.insp) { this.P.insp.replaceChildren(inspectorContent(this)); }
    if (this.P.typePills) { this.P.typePills.replaceChildren(typeList(this, this.legend())); }
    if (this.tables) { this.tables.refresh(true); }
  }

  /** @private lays out what is visible: force a few steps a frame, the others at once; moves animated unless motion is reduced */
  layoutNow() {
    const run = ++this.run, t0 = Date.now(), name = this.st.layout, g = { nodes: this.vis.nodes, edges: this.vis.edges };
    const fixed = new Map();
    this.vis.nodes.forEach((n) => { if (n.pinned && this.pos.has(n.id)) { fixed.set(n.id, this.pos.get(n.id)); } });
    this.vis.groups.forEach((members, gid) => {
      if (this.pos.has(gid)) { return; }
      const ps = members.map((m) => this.pos.get(m)).filter(Boolean);
      if (ps.length) { this.pos.set(gid, { x: ps.reduce((s, p) => s + p.x, 0) / ps.length, y: ps.reduce((s, p) => s + p.y, 0) / ps.length }); }
    });
    const start = new Map();
    this.vis.nodes.forEach((n) => { if (this.pos.has(n.id)) { start.set(n.id, this.pos.get(n.id)); } });
    const lo = Object.assign({ groupBy: this.st.groupBy || undefined }, this.o.layoutOptions, { fixed, start: name === 'force' && this.keepForce ? start : undefined });
    const done = (positions, frames) => {
      if (run !== this.run) { return; }
      this.layoutMs = Date.now() - t0;
      this.frames = frames || [];
      const from = new Map(this.pos);
      positions.forEach((p, id) => this.pos.set(id, p));
      this.keepForce = name === 'force';
      const finish = () => { this.reindex(); if (!this.fitted || this.autoFit) { this.fit(); } else { this.draw(); } this.emit('layoutDone', { layout: name, ms: this.layoutMs, nodes: this.vis.nodes.length, edges: this.vis.edges.length }); };
      if (this.moving() && from.size && name !== 'force') { return this.tween(from, positions, run).then(finish); }
      finish();
      return undefined;
    };
    if (name !== 'force') { const r = runLayout(name, g, lo); return Promise.resolve(done(r.positions, r.frames)); }
    const steps = forceSteps(g, Object.assign({ seed: 7 }, lo));
    const R = this.win.requestAnimationFrame;
    if (!R || this.vis.nodes.length < 400) { let r = steps.next(); while (!r.done) { r = steps.next(); } return Promise.resolve(done(r.value, [])); }
    return new Promise((resolve) => {
      const tick = () => {
        if (run !== this.run) { resolve(); return; }
        const until = Date.now() + 24;
        let r = steps.next();
        while (!r.done && Date.now() < until) { r = steps.next(); }
        if (r.done) { resolve(done(r.value, [])); return; }
        if (this.moving() && Date.now() - (this.shownAt || 0) > 120) { this.shownAt = Date.now(); r.value.positions().forEach((p, id) => this.pos.set(id, p)); this.reindex(); if (!this.fitted || this.autoFit) { this.fit(); } else { this.drawNow(); } }
        this.P.badge.textContent = 'laying out ' + Math.round((100 * r.value.step) / r.value.steps) + '%';
        R(tick);
      };
      R(tick);
    });
  }

  /** @private whether moves are animated: the option, and the page's reduced motion */
  moving() {
    if (this.o.animate === false) { return false; }
    const mq = this.win.matchMedia && this.win.matchMedia('(prefers-reduced-motion: reduce)');
    return !(mq && mq.matches);
  }

  /** @private moves from one layout to the next in 400 ms */
  tween(from, to, run) {
    const R = this.win.requestAnimationFrame, t0 = Date.now(), ms = 400;
    return new Promise((resolve) => {
      const step = () => {
        if (run !== this.run) { resolve(); return; }
        const f = Math.min(1, (Date.now() - t0) / ms), e = f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
        to.forEach((p, id) => { const a = from.get(id) || p; this.pos.set(id, { x: a.x + (p.x - a.x) * e, y: a.y + (p.y - a.y) * e }); });
        this.drawNow();
        if (f < 1) { R(step); } else { resolve(); }
      };
      R(step);
    });
  }

  /** @private the hit index over the visible nodes */
  reindex() {
    const items = [];
    this.vis.nodes.forEach((n) => { const p = this.pos.get(n.id), L = this.enc.nodes.get(n.id); if (p && L) { items.push({ id: n.id, x: p.x, y: p.y, r: L.r }); } });
    this.hit = new HitIndex(items, 40);
    this.box = bounds(items);
  }

  // ---- drawing ---------------------------------------------------------------------------------------------------------------------
  /**
   * Draws on the next frame (several changes in one frame draw once).
   * @returns {void}
   */
  draw() {
    const R = this.win.requestAnimationFrame;
    if (!R) { this.drawNow(); return; }
    if (this.raf) { return; }
    this.raf = R(() => { this.raf = 0; this.drawNow(); });
  }

  /** @private the frame's content */
  frame() {
    const th = this.theme();
    return { nodes: this.vis.nodes, edges: this.vis.edges, pos: this.pos, look: this.enc.nodes, edgeLook: this.enc.edges, frames: this.frames, selected: this.selected,
      path: this.path ? { nodes: new Set(this.path.nodes), edges: new Set(this.path.edges) } : null, hover: this.hoverId, focus: this.focusId,
      directed: this.model.directed, highlight: this.highlighted || null, theme: th, labelSize: this.st.density === 'comfortable' ? 12 : this.st.density === 'terminal' ? 10.5 : 11, lasso: this.lassoPts };
  }

  /**
   * Draws now: the canvas, the minimap, the status line and the canvas's words for a screen reader.
   * @returns {void}
   */
  drawNow() {
    if (!this.renderer || !this.vis) { return; }
    const f = this.frame();
    this.renderer.draw(f, this.t);
    if (this.o.minimap !== false && this.box) { this.miniT = this.miniRenderer.drawMinimap(f, this.box, this.t, this.size); }
    renderStatus(this, this.P);
    this.P.canvas.setAttribute('aria-label', (this.o.title ? this.o.title + ': ' : '') + 'a graph of ' + this.vis.nodes.length + ' nodes and ' + this.vis.edges.length +
      ' edges, ' + this.st.layout + ' layout' + (this.selected.size ? ', ' + this.selected.size + ' selected' : '') + '. Arrows move between neighbours; the ▦ button gives the adjacency table.');
  }

  /**
   * What the last frame drew and how long it took, and the layout's time.
   * @returns {{nodes: number, edges: number, labels: number, frameMs: number, layoutMs: ?number, path: number, hidden: number}}
   */
  stats() {
    const s = this.renderer ? this.renderer.stats : { nodes: 0, edges: 0, labels: 0, ms: 0 };
    return { nodes: this.vis ? this.vis.nodes.length : 0, edges: this.vis ? this.vis.edges.length : 0, drawnNodes: s.nodes, drawnEdges: s.edges, labels: s.labels, frameMs: s.ms, layoutMs: this.layoutMs,
      path: this.path ? this.path.nodes.length - 1 : 0, hidden: this.model.nodes.size - (this.vis ? this.vis.nodes.reduce((k, n) => k + (n.members ? n.members.length : 1), 0) : 0) };
  }

  // ---- the transform ----------------------------------------------------------------------------------------------------------------
  /**
   * A node's place on the screen (css pixels in the canvas), or null.
   * @param {string} id
   * @returns {?{x: number, y: number}}
   */
  toScreen(id) { const p = this.pos.get(id); return p ? { x: p.x * this.t.k + this.t.tx, y: p.y * this.t.k + this.t.ty } : null; }

  /**
   * A screen point in layout units.
   * @param {number} x
   * @param {number} y
   * @returns {{x: number, y: number}}
   */
  toLayout(x, y) { return { x: (x - this.t.tx) / this.t.k, y: (y - this.t.ty) / this.t.k }; }

  /**
   * Fits the whole graph (or the selection's nodes) in the view.
   * @param {string[]} [ids]
   * @returns {void}
   */
  fit(ids) {
    const pts = (ids && ids.length ? ids : this.vis.nodes.map((n) => n.id)).map((id) => this.pos.get(id)).filter(Boolean), b = bounds(pts), pad = 40;
    if (!pts.length) { this.draw(); return; }
    const k = Math.max(0.02, Math.min(2.2, Math.min((this.size.w - pad * 2) / Math.max(1, b.w), (this.size.h - pad * 2) / Math.max(1, b.h))));
    this.t = { k, tx: this.size.w / 2 - (b.x + b.w / 2) * k, ty: this.size.h / 2 - (b.y + b.h / 2) * k };
    this.fitted = true; this.autoFit = !ids || !ids.length;
    this.draw();
  }

  /**
   * Zooms by a factor around a screen point.
   * @param {number} x
   * @param {number} y
   * @param {number} f
   * @returns {void}
   */
  zoomAt(x, y, f) {
    const k = Math.max(0.02, Math.min(8, this.t.k * f)), r = k / this.t.k;
    this.autoFit = false;
    this.t = { k, tx: x - (x - this.t.tx) * r, ty: y - (y - this.t.ty) * r };
    this.draw();
  }

  /**
   * Zooms by a factor around the middle of the view.
   * @param {number} f
   * @returns {void}
   */
  zoomBy(f) { this.zoomAt(this.size.w / 2, this.size.h / 2, f); }

  /**
   * Moves the view.
   * @param {number} tx
   * @param {number} ty
   * @returns {void}
   */
  panTo(tx, ty) { this.t = { k: this.t.k, tx, ty }; this.autoFit = false; this.draw(); }

  /**
   * Centres the view on a node.
   * @param {string} id
   * @returns {void}
   */
  centerOn(id) { const p = this.pos.get(id); if (!p) { return; } this.autoFit = false; this.t = { k: Math.max(this.t.k, 0.9), tx: 0, ty: 0 }; this.t.tx = this.size.w / 2 - p.x * this.t.k; this.t.ty = this.size.h / 2 - p.y * this.t.k; this.draw(); }

  /** @private a click on the minimap */
  centerOnMinimap(x, y) { if (!this.miniT) { return; } const lx = (x - this.miniT.tx) / this.miniT.k, ly = (y - this.miniT.ty) / this.miniT.k; this.panTo(this.size.w / 2 - lx * this.t.k, this.size.h / 2 - ly * this.t.k); }

  /**
   * The node under a screen point.
   * @param {number} x
   * @param {number} y
   * @returns {?{id: string}}
   */
  nodeAt(x, y) { if (!this.hit) { return null; } const p = this.toLayout(x, y); return this.hit.at(p.x, p.y, 4 / this.t.k); }

  /** @private the tooltip */
  hoverAt(x, y) {
    const hit = this.nodeAt(x, y), id = hit ? hit.id : null;
    if (id === this.hoverId) { if (id) { this.placeTip(x, y); } return; }
    this.hoverId = id;
    const tip = this.P.tip;
    if (!id) { tip.hidden = true; this.draw(); return; }
    const n = this.byId.get(id), ms = this.measureList().filter((m) => typeof n[m.field] === 'number').slice(0, 3);
    tip.replaceChildren(this.doc.createElement('b'), ' ' + this.typeLabel(n.type) + ms.map((m) => ' · ' + (m.label || m.field) + ' ' + this.formatMeasure(m.field, n[m.field])).join(''));
    tip.firstChild.textContent = n.label;
    tip.hidden = false;
    this.placeTip(x, y);
    this.draw();
  }

  /** @private */
  placeTip(x, y) { this.P.tip.style.setProperty('left', Math.min(this.size.w - 200, x + 14) + 'px'); this.P.tip.style.setProperty('top', (y + 14) + 'px'); }

  /** @private a node dragged (pinned when the drag ends) */
  moveNode(id, x, y) { const p = this.toLayout(x, y); this.pos.set(id, p); this.reindex(); this.draw(); }

  // ---- reading -----------------------------------------------------------------------------------------------------------------------
  /** The view's state: mode, density, layout, group, size, colour. @returns {Object} */
  state() { return Object.assign({}, this.st, { typesOff: Array.from(this.st.typesOff) }); }

  /** The visible nodes (groups as one node). @returns {Object[]} */
  visibleNodes() { return this.vis ? this.vis.nodes : []; }

  /** The visible edges. @returns {Object[]} */
  visibleEdges() { return this.vis ? this.vis.edges : []; }

  /** A visible node or group by id, else the model's node. @param {string} id @returns {?Object} */
  nodeOrGroup(id) { return (this.byId && this.byId.get(id)) || this.model.node(id); }

  /** A node's drawn look. @param {string} id @returns {?{r: number, colour: string, shape: string}} */
  lookOf(id) { return this.enc ? this.enc.nodes.get(id) || null : null; }

  /** A node's layout position. @param {string} id @returns {?{x: number, y: number}} */
  positionOf(id) { return this.pos.get(id) || null; }

  /** The measures: those given, then the other numbers the nodes carry. @returns {import('./drishti_graph-scene.js').GraphMeasure[]} */
  measureList() { const out = Object.values(this.measures); this.model.fields().measures.forEach((f) => { if (!this.measures[f]) { out.push({ field: f }); } }); return out; }

  /** A measure's words. @param {string} f @returns {string} */
  measureLabel(f) { return (this.measures[f] && this.measures[f].label) || f; }

  /** A measure's value as text by its format. @param {string} f @param {*} v @returns {string} */
  formatMeasure(f, v) { return formatterFor(this.measures[f] && this.measures[f].format)(v); }

  /** A type's words. @param {string} t @returns {string} */
  typeLabel(t) { const T = this.o.types || {}; return (T[t] && T[t].label) || t || ''; }

  /** The attributes and measures of the nodes. @returns {{attributes: string[], measures: string[]}} */
  fields() { return this.model.fields(); }

  /** The legend with the types (counts, drawn or not). @returns {import('./drishti_graph-scene.js').GraphLegend} */
  legend() {
    const leg = Object.assign({}, this.enc.legend), th = this.theme(), shown = new Map(), order = this.typeOrder();
    this.vis.nodes.forEach((n) => { shown.set(n.type || '', (shown.get(n.type || '') || 0) + (n.members ? n.members.length : 1)); });
    leg.types = this.model.types().sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type)).map((t) => {
      const T = (this.o.types || {})[t.type] || {}, i = order.indexOf(t.type);
      return { type: t.type, label: this.typeLabel(t.type), count: t.count, shown: shown.get(t.type) || 0, off: this.st.typesOff.has(t.type),
        shape: T.shape || ['circle', 'square', 'diamond', 'triangle', 'hexagon'][i % 5], colour: (this.st.colourBy === 'type' && leg.colour && leg.colour.items && (leg.colour.items.find((x) => x.value === t.type) || {}).colour) || th.palette[i % th.palette.length] };
    });
    return leg;
  }

  /** A visible node's neighbours with the edge and its direction. @param {string} id @returns {Array<{node: Object, edge: Object, dir: string}>} */
  neighbourList(id) {
    const out = [];
    this.vis.edges.forEach((e) => {
      if (e.source === id && this.byId.has(e.target)) { out.push({ node: this.byId.get(e.target), edge: e, dir: '→' }); }
      else if (e.target === id && this.byId.has(e.source)) { out.push({ node: this.byId.get(e.source), edge: e, dir: '←' }); }
    });
    return out;
  }

  /** The groups drawn. @returns {number} */
  groupCount() { return this.vis ? this.vis.groups.size : 0; }

  /** An edge's label. @param {string} id @returns {string} */
  edgeLabel(id) { const e = this.vis.edges.find((x) => x.id === id) || this.model.edges.get(id); return e ? e.label || e.type || '' : ''; }

  // ---- events ------------------------------------------------------------------------------------------------------------------------
  /**
   * Listens to one of GRAPH_EVENTS.
   * @param {string} name
   * @param {function(Object, string): void} fn
   * @returns {function(): void} stops listening
   */
  on(name, fn) { return this.events.on(name, fn); }

  /** @private an event on the bus and on the host */
  emit(name, detail) {
    this.events.emit(name, detail);
    if (this.host && this.win && this.win.CustomEvent) { this.host.dispatchEvent(new this.win.CustomEvent('drishti:' + kebab(name), { detail, bubbles: true })); }
  }
}
