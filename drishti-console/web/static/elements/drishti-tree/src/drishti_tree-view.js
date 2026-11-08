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
/* The tree, drawn (g6, the product owner's choice of 2026-11-07: option B, the tree grid, the default; A, the org chart, and C, the
   sunburst and the icicle, as modes of the same component; one engine, the modes only draw). The chrome is g6's: the toolbar (search
   with its count, expand and collapse all, the modes, the size of a partition, ✎ Edit (propose), PNG, SVG, CSV, JSON), the edit bar,
   the breadcrumbs of the selected node, the view and the side panel (the node, its figures with ◆, Explain, the proposal), the status
   line. A non-additive figure is asked of the host (serverMeasures, debounced) and never summed; a selection is shared on drishti-core's
   bus (link); the edit mode collects moves into one proposal the host submits for four eyes (proposal, then applyProposal or
   discardProposal). Every event is on the tree's bus and a DOM event on the host (drishti:selection-changed ...). */
import { h, EventBus, bus as sharedBus, SELECTION_EVENT, visualTheme, formatterFor, renderExplain, announce, DENSITIES } from '../../drishti-core/src/drishti_index.js';
import { HierarchyModel } from './drishti_tree-model.js';
import { drawOrg, drawPartition, partitionTable } from './drishti_tree-draw.js';
import { TreeGrid } from './drishti_tree-grid.js';
import { sideContent, statusParts } from './drishti_tree-side.js';
import { exportTree, keyMove } from './drishti_tree-io.js';
import { loadOpenChildren } from './drishti_tree-hooks.js';

/** The modes of one tree: grid (g6 B, the default), org (A), sunburst and icicle (C). */
export const TREE_MODES = Object.freeze(['grid', 'org', 'sunburst', 'icicle']);

/** Every event a tree emits (on tree.events, and as drishti:<kebab-name> on its host). */
export const TREE_EVENTS = Object.freeze(['selectionChanged', 'nodeExpanded', 'nodeCollapsed', 'searched', 'modeChanged', 'densityChanged', 'explainOpened',
  'explainShown', 'valuesRequested', 'valuesUpdated', 'valuesFailed', 'proposal', 'proposalSubmitted', 'proposalApplied', 'proposalDiscarded', 'moveRefused', 'exported']);

const MODE_NAMES = { grid: 'Tree grid', org: 'Org chart', sunburst: 'Sunburst', icicle: 'Icicle' };

/**
 * The options of a tree.
 * @typedef {Object} TreeOptions
 * @property {import('./drishti_tree-model.js').HierarchySource} data the hierarchy (rows with parents, a nested root, or paths) and its measures
 * @property {('grid'|'org'|'sunburst'|'icicle')} [mode='grid']
 * @property {('comfortable'|'compact'|'terminal')} [density='compact']
 * @property {string} [title] the tree's name (the grid's first heading, the status line)
 * @property {string} [label] its name for a screen reader (default: the title)
 * @property {string[]} [modes] the modes the toolbar offers (default all four; one: no switch)
 * @property {function(Array<{id: string, path: string[], fields: string[]}>): Promise<Array<{id: string, values: Object}>>} [serverMeasures]
 *   the non-additive figures of the nodes that lack them, asked of the host (debounced), never computed here
 * @property {number} [serverDebounceMs=60]
 * @property {function(Object): Promise<*>} [explain] the Explain hook: given a node's context ({id, label, kind, path, field, measure,
 *   value, text, additive, values, childSum}), the explanation (drishti-core's ExplainResult, words or a node)
 * @property {{channel: string, bus: Object}} [link] share the selection on drishti-core's bus (a grid, a chart, a graph on the channel)
 * @property {boolean} [editable=false] offer the edit mode (✎ Edit (propose)): moves become one proposal for four eyes
 * @property {string} [proposalNote] words under the proposal (who approves, the impact)
 * @property {string} [sizeBy] the additive measure that sizes a partition (default: the first additive one)
 * @property {string} [colourBy] the measure whose bar's status colours the nodes (default: the first bar with a status)
 * @property {string[]} [shown] the measures an org chart box shows (default: the first two)
 * @property {number} [openDepth=1] nodes open down to this depth at the start (-1: all)
 * @property {string[]} [status] more words for the status line (the business date)
 * @property {string} [emptyText] the side panel's words when nothing is selected
 * @property {function(string, (string|Blob), string): void} [download] saves an export (default: drishti-core's download)
 * @property {boolean} [chrome=true] false: the view alone (no toolbar, breadcrumbs, side panel or status line), for a screen with its own
 * @property {import('./drishti_tree-hooks.js').TreeGridHooks} [grid] the host's look in the tree grid (its columns, a row's class, widths)
 * @property {function(import('./drishti_tree-hooks.js').TreeChildrenRequest): Promise<Object[]>} [loadChildren] a lazy node's children
 *   (rows, read as the data's), asked the first time it is open
 * @property {string} [lazyText='loading…'] the waiting row's words while a lazy node's children are asked
 */

/**
 * The tree: a hierarchy in one of four modes, with the plain JavaScript API every host uses (the web component wraps it).
 */
export class Tree {
  /**
   * @param {HTMLElement} host the element the tree fills
   * @param {TreeOptions} options
   */
  constructor(host, options) {
    if (!host || !host.ownerDocument) { throw new Error('a tree needs an element to fill'); }
    const o = this.o = Object.assign({ serverDebounceMs: 60, openDepth: 1 }, options);
    if (!o.data) { throw new Error('a tree needs data (rows, root or getPath)'); }
    this.host = host;
    this.doc = host.ownerDocument;
    this.win = this.doc.defaultView;
    this.events = new EventBus();
    this.bus = (o.link && o.link.bus) || sharedBus;
    this.model = new HierarchyModel(o.data);
    this.model.o.asksHost = !!o.serverMeasures;
    if (o.openDepth !== 0) { this.model.expandAll({ depth: o.openDepth < 0 ? Infinity : o.openDepth }); }
    this.mode = TREE_MODES.indexOf(o.mode) >= 0 ? o.mode : 'grid';
    this.density = DENSITIES.indexOf(o.density) >= 0 ? o.density : 'compact';
    this.selected = null;
    this.hits = new Set();
    this.query = '';
    this.editing = false;
    this.focus = null;
    this.zoom = 1;
    this.altOpen = false;
    const add = this.model.measures.filter((m) => m.additive !== false);
    this.sizeBy = o.sizeBy || (add[0] && add[0].field) || null;
    this.colourBy = o.colourBy || ((this.model.measures.find((m) => m.bar && m.bar.status) || {}).field) || null;
    this.shown = o.shown || this.model.measures.filter((m) => !m.bar).slice(0, 2).map((m) => m.field);
    this.fmt = {};
    this.model.measures.forEach((m) => { this.fmt[m.field] = formatterFor(m.format); });
    this.build();
    this.offModel = this.model.events.on('changed', (p) => this.modelChanged(p));
    loadOpenChildren(this);
    if (o.link) { this.offLink = this.bus.on(SELECTION_EVENT, (p) => { if (p && p.source !== this && p.channel === o.link.channel) { const id = (p.ids || []).find((x) => this.model.node(x)); this.select(id || null, { silent: true }); } }); }
    if (this.win.MutationObserver) { this.watch = new this.win.MutationObserver(() => this.draw()); this.watch.observe(this.doc.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] }); }
    this.draw();
    this.askSoon();
  }

  /** A measure's figure as text, by its format. @param {string} field @param {*} v @returns {string} */
  text(field, v) { return (this.fmt[field] || formatterFor())(v); }

  /** @private emits on the bus and as a DOM event */
  emit(name, detail) {
    this.events.emit(name, detail);
    this.host.dispatchEvent(new this.win.CustomEvent('drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), { detail: detail || {}, bubbles: true, composed: true }));
  }

  /**
   * Listens to an event of TREE_EVENTS.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void} stops listening
   */
  on(name, fn) { return this.events.on(name, fn); }

  // ---- the chrome -----------------------------------------------------------------------------------------------------------------------

  /** @private */
  build() {
    const doc = this.doc, o = this.o, b = (act, text, title) => h(doc, 'button', { cls: 'dt-btn', text: text, attrs: { type: 'button', title: title || null }, data: { act: act } });
    this.host.classList.add('dt');
    this.host.classList.toggle('dt-bare', o.chrome === false);
    this.host.textContent = '';
    this.search = h(doc, 'input', { cls: 'dt-input dt-search', attrs: { type: 'search', placeholder: 'Search the tree…', 'aria-label': 'Search the tree' }, data: { ctl: 'search' } });
    this.found = h(doc, 'span', { cls: 'dt-lbl', attrs: { 'aria-live': 'polite' } });
    const modes = (o.modes || TREE_MODES).filter((m) => TREE_MODES.indexOf(m) >= 0);
    this.modeSeg = modes.length > 1 ? h(doc, 'span', { cls: 'dt-seg', attrs: { role: 'group', 'aria-label': 'Mode' } }, modes.map((m) => h(doc, 'button', { text: MODE_NAMES[m], attrs: { type: 'button', 'aria-pressed': 'false' }, data: { mode: m } }))) : null;
    const add = this.model.measures.filter((m) => m.additive !== false);
    this.sizeSel = h(doc, 'select', { cls: 'dt-input', attrs: { 'aria-label': 'Size by (an additive measure)' }, data: { ctl: 'size' } }, add.map((m) => h(doc, 'option', { text: m.label, attrs: { value: m.field } })));
    if (this.sizeBy) { this.sizeSel.value = this.sizeBy; }
    this.sizeBox = h(doc, 'span', { cls: 'dt-sizebox' }, [h(doc, 'span', { cls: 'dt-lbl', text: 'size' }), this.sizeSel]);
    this.editBtn = o.editable ? b('edit', '✎ Edit (propose)', 'Re-parent nodes: the moves become one proposal for review') : null;
    this.altBtn = b('alt', '▦', 'The figures as a table');
    this.altBtn.setAttribute('aria-pressed', 'false');
    this.toolbar = h(doc, 'div', { cls: 'dt-tb', attrs: { role: 'toolbar', 'aria-label': 'Tree tools' } }, [this.search, this.found, h(doc, 'span', { cls: 'dt-div' }),
      b('expand-all', 'Expand all'), b('collapse-all', 'Collapse all'), this.modeSeg ? h(doc, 'span', { cls: 'dt-div' }) : null, this.modeSeg, this.sizeBox,
      h(doc, 'span', { cls: 'dt-sp' }), this.editBtn, h(doc, 'span', { cls: 'dt-div' }), b('png', 'PNG', 'Export the drawing as PNG'), b('svg', 'SVG', 'Export the drawing as SVG'),
      b('csv', 'CSV', 'Export the data as CSV'), b('json', 'JSON', 'Export the data as JSON'), this.altBtn]);
    this.editBar = h(doc, 'div', { cls: 'dt-editbar', attrs: { hidden: true, role: 'status' } });
    this.crumbs = h(doc, 'nav', { cls: 'dt-crumbs', attrs: { 'aria-label': 'Breadcrumbs' } });
    this.view = h(doc, 'div', { cls: 'dt-view' });
    this.side = h(doc, 'aside', { cls: 'dt-side', attrs: { 'aria-label': 'The selected node' } });
    this.explainBox = h(doc, 'div', { cls: 'dt-explain', attrs: { hidden: true, 'aria-live': 'polite' } });
    this.status = h(doc, 'div', { cls: 'dt-status', attrs: { role: 'status' } });
    this.shell = h(doc, 'div', { cls: 'dt-shell' }, [this.toolbar, this.editBar, this.crumbs, h(doc, 'div', { cls: 'dt-body' }, [this.view, this.side]), this.status]);
    this.host.appendChild(this.shell);
    this.host.addEventListener('click', (e) => this.clicked(e));
    this.host.addEventListener('change', (e) => this.changed(e));
    this.search.addEventListener('input', () => this.find(this.search.value));
    this.view.addEventListener('keydown', (e) => this.key(e));
    this.view.addEventListener('pointerdown', (e) => this.pressed(e));
    this.view.addEventListener('pointerup', (e) => this.released(e));
  }

  /** @private the theme, the mode's drawing and the panels */
  draw() {
    this.theme = visualTheme({ element: this.host, density: this.density });
    this.host.setAttribute('data-density', this.density);
    this.host.setAttribute('data-mode', this.mode);
    this.host.classList.toggle('dt-editing', this.editing);
    if (this.modeSeg) { this.modeSeg.querySelectorAll('[data-mode]').forEach((x) => x.setAttribute('aria-pressed', String(x.getAttribute('data-mode') === this.mode))); }
    const part = this.mode === 'sunburst' || this.mode === 'icicle';
    this.sizeBox.hidden = !part;
    this.altBtn.hidden = !part;
    this.toolbar.querySelectorAll('[data-act="png"],[data-act="svg"]').forEach((x) => { x.disabled = this.mode === 'grid'; });
    if (this.editBtn) { this.editBtn.setAttribute('aria-pressed', String(this.editing)); this.editBtn.classList.toggle('dt-on', this.editing); }
    this.editBar.hidden = !this.editing;
    this.editBar.textContent = 'Edit mode · ' + (part ? 'select a segment and choose "Move to"' : 'drag a ' + (this.mode === 'org' ? 'box' : 'row') + ' onto its new parent') +
      ' · every move becomes part of one proposal for review (four eyes); nothing changes for anyone until it is approved';
    this.drawView();
    this.drawSide();
  }

  /** @private the view of the mode */
  drawView() {
    if (this.mode === 'grid') {
      if (this.svgBox) { this.view.textContent = ''; this.svgBox = null; }
      if (!this.grid) { const box = h(this.doc, 'div', { cls: 'dt-gridbox' }); this.view.textContent = ''; this.view.appendChild(box); this.grid = new TreeGrid(box, this); }
      else { this.grid.setDensity(this.density); this.grid.refresh(); }
      if (this.selected) { this.grid.select(this.selected); }
      return;
    }
    if (this.grid) { this.grid.destroy(); this.grid = null; }
    const doc = this.doc, c = { model: this.model, theme: this.theme, text: (f, v) => this.text(f, v), selected: this.selected, hits: this.hits, colourBy: this.colourBy,
      shown: this.shown, sizeBy: this.sizeBy, focus: this.focus, editing: this.editing };
    this.view.textContent = '';
    try {
      if (this.mode === 'org') {
        const d = drawOrg(doc, c);
        this.drawn = d;
        const vw = this.view.getBoundingClientRect().width;
        if (!this.fitted && vw) { this.fitted = true; this.zoom = Math.max(0.35, Math.min(1, (vw - 24) / d.width)); }
        d.svg.setAttribute('width', String(Math.round(d.width * this.zoom)));
        d.svg.setAttribute('height', String(Math.round(d.height * this.zoom)));
        const zoom = h(doc, 'div', { cls: 'dt-zoom' }, [['zoom-in-org', '+', 'Zoom in'], ['zoom-out-org', '−', 'Zoom out'], ['fit', '⤢', 'Fit']].map((x) => h(doc, 'button', { cls: 'dt-btn', text: x[1], attrs: { type: 'button', 'aria-label': x[2] }, data: { act: x[0] } })));
        this.svgBox = h(doc, 'div', { cls: 'dt-canvas' }, [d.svg]);
        this.view.appendChild(this.svgBox);
        this.view.appendChild(zoom);
      } else {
        if (!this.sizeBy) { throw new Error('a ' + this.mode + ' needs an additive measure to size it'); }
        const r = this.view.getBoundingClientRect(), size = { width: Math.round(r.width) || 640, height: Math.max(360, Math.round(r.height) || 440) };
        const d = drawPartition(doc, c, this.mode, size);
        this.drawn = d;
        this.svgBox = h(doc, 'div', { cls: 'dt-canvas' }, [d.svg]);
        this.view.appendChild(this.svgBox);
        const alt = partitionTable(doc, c, d.slices);
        alt.hidden = !this.altOpen;
        this.view.appendChild(alt);
      }
      const at = this.view.querySelector('[data-id="' + cssId(this.active || this.selected || '') + '"]') || this.view.querySelector('[data-id]');
      if (at) { at.setAttribute('tabindex', '0'); }
    } catch (e) {
      this.drawn = null;
      this.view.appendChild(h(doc, 'p', { cls: 'dt-error', text: String(e && e.message ? e.message : e) }));
    }
  }

  /** @private the side panel, the breadcrumbs, the status line */
  drawSide() {
    this.side.textContent = '';
    sideContent(this).forEach((x) => this.side.appendChild(x));
    this.crumbs.textContent = '';
    const id = this.selected || (this.focus && this.mode !== 'grid' && this.mode !== 'org' ? this.focus : null) || (this.model.roots[0] && this.model.roots[0].id);
    this.model.pathOf(id).forEach((n, i, all) => {
      if (i) { this.crumbs.appendChild(h(this.doc, 'span', { cls: 'dt-sep', text: '›', attrs: { 'aria-hidden': 'true' } })); }
      this.crumbs.appendChild(h(this.doc, 'button', { cls: 'dt-crumb', text: n.label, attrs: { type: 'button', 'aria-current': i === all.length - 1 ? 'location' : null }, data: { crumb: n.id } }));
    });
    this.status.textContent = '';
    statusParts(this).forEach((x) => this.status.appendChild(x));
  }

  // ---- the model and the host's figures -------------------------------------------------------------------------------------------------

  /** @private */
  modelChanged(p) {
    if (p.what === 'open' && p.id) { this.emit(p.open ? 'nodeExpanded' : 'nodeCollapsed', { id: p.id, path: this.model.breadcrumbs(p.id) }); }
    if (p.what === 'open' || p.what === 'data') { loadOpenChildren(this); }
    if (this.quiet) { return; }
    if (this.mode === 'grid' && this.grid && p.what === 'open') { this.grid.syncOpen(); this.drawSide(); return; }
    this.draw();
  }

  /** @private asks the host for the non-additive figures, once the changes settle */
  askSoon() {
    if (!this.o.serverMeasures) { return; }
    clearTimeout(this.askTimer);
    this.askTimer = setTimeout(() => this.askNow(), this.o.serverDebounceMs);
  }

  /**
   * Asks the host now for every non-additive figure the nodes lack.
   * @returns {Promise<number>} how many nodes took figures
   */
  askNow() {
    clearTimeout(this.askTimer);
    const reqs = this.model.requests();
    if (!reqs.length || !this.o.serverMeasures) { return Promise.resolve(0); }
    this.waiting = true;
    this.failed = null;
    this.emit('valuesRequested', { requests: reqs });
    return Promise.resolve().then(() => this.o.serverMeasures(reqs)).then((answers) => {
      this.waiting = false;
      const k = this.model.setValues(answers || []);
      this.emit('valuesUpdated', { nodes: k });
      if (!k) { this.drawSide(); }
      return k;
    }, (e) => {
      this.waiting = false;
      this.failed = 'the server\'s figures did not come: ' + (e && e.message ? e.message : e);
      this.emit('valuesFailed', { error: e });
      this.drawSide();
      return 0;
    });
  }

  // ---- selecting, finding, explaining ------------------------------------------------------------------------------------------------------

  /**
   * Selects a node (null: none), shows it and its breadcrumbs, shares it on the link's channel.
   * @param {?string} id
   * @param {{silent: boolean, quiet: boolean, from: string}} [o] silent: not published on the bus; quiet: only marked (the host's own
   *   selection followed: nothing opened, no event, nothing announced)
   * @returns {void}
   */
  select(id, o) {
    const n = id ? this.model.node(id) : null;
    this.selected = n ? n.id : null;
    if (o && o.quiet) { if (this.grid) { this.grid.repaint(); } else { this.draw(); } return; }
    this.active = this.selected || this.active;
    this.explainBox.hidden = true;
    if (n && this.mode !== 'sunburst' && this.mode !== 'icicle' && n.parent && !this.model.pathOf(n.id).slice(0, -1).every((x) => this.model.isOpen(x.id))) {
      this.quiet = true; try { this.model.reveal(n.id); } finally { this.quiet = false; }
    }
    if (this.mode === 'grid' && this.grid) { if (!(o && o.from === 'grid')) { this.grid.refresh(); this.grid.select(this.selected); } else { this.grid.repaint(); } this.drawSide(); }
    else { this.draw(); }
    if (n) { announce(n.label + ' selected, ' + this.model.breadcrumbs(n.id).join(', ')); }
    this.emit('selectionChanged', { ids: this.selected ? [this.selected] : [], path: this.selected ? this.model.breadcrumbs(this.selected) : [] });
    if (this.o.link && !(o && o.silent)) { this.bus.emit(SELECTION_EVENT, { channel: this.o.link.channel, ids: this.selected ? [this.selected] : [], source: this }); }
  }

  /**
   * Finds nodes by words: marks them, opens their branches, selects the first.
   * @param {string} q
   * @returns {Array<{id: string, label: string, breadcrumbs: string[]}>}
   */
  find(q) {
    this.query = String(q || '').trim();
    if (this.search.value !== q) { this.search.value = q || ''; }
    this.quiet = true;
    let hits;
    try { hits = this.query ? this.model.search(this.query) : []; } finally { this.quiet = false; }
    this.hits = new Set(hits.map((x) => x.id));
    this.found.textContent = this.query ? hits.length + ' found' : '';
    this.emit('searched', { query: this.query, matches: hits });
    if (hits.length) { this.select(hits[0].id, { silent: true }); } else { this.draw(); }
    return hits;
  }

  /**
   * Explains a node (its figure of a measure, or the node): the host's Explain hook, drawn in the side panel.
   * @param {string} id
   * @param {?string} [field] the measure
   * @param {boolean} [fromGrid] the grid asked (the grid shows nothing of its own)
   * @returns {Promise<*>}
   */
  explainNode(id, field, fromGrid) {
    const n = this.model.node(id);
    if (!n) { return Promise.resolve(null); }
    if (this.selected !== n.id) { this.select(n.id, { from: fromGrid ? 'grid' : null }); }
    const m = field ? this.model.measure(field) : (this.model.measures.find((x) => x.additive === false) || this.model.measures[0]);
    const v = m ? n.values[m.field] : null;
    const ctx = { id: n.id, label: n.label, kind: n.kind, path: this.model.breadcrumbs(n.id), ids: this.model.pathOf(n.id).map((x) => x.id), field: m ? m.field : null,
      measure: m ? m.label : null, value: v === undefined ? null : v, text: m && v !== undefined && v !== null ? this.text(m.field, v) : '', additive: m ? m.additive !== false : true,
      values: Object.assign({}, n.values), childSum: m ? this.model.childSum(n.id, m.field) : null };
    const box = this.explainBox, close = () => { box.hidden = true; };
    box.hidden = false;
    if (!this.o.explain) { renderExplain(box, { result: 'This tree has no Explain hook: ' + n.label + (ctx.text ? ' · ' + ctx.measure + ' ' + ctx.text : '') }, 'card', { prefix: 'dt', onClose: close }); return Promise.resolve(null); }
    renderExplain(box, { loading: true }, 'card', { prefix: 'dt', onClose: close });
    this.emit('explainOpened', ctx);
    return Promise.resolve().then(() => this.o.explain(ctx)).then((res) => {
      renderExplain(box, { result: res }, 'card', { prefix: 'dt', onClose: close });
      this.emit('explainShown', { context: ctx, result: res });
      return res;
    }, (e) => { renderExplain(box, { error: e }, 'card', { prefix: 'dt', onClose: close }); return null; });
  }

  // ---- modes, density, open state -----------------------------------------------------------------------------------------------------------

  /** Draws the tree in another mode (one engine: the open state, the selection and the draft stay). @param {('grid'|'org'|'sunburst'|'icicle')} mode @returns {void} */
  setMode(mode) {
    if (TREE_MODES.indexOf(mode) < 0 || mode === this.mode) { return; }
    this.mode = mode;
    this.draw();
    this.emit('modeChanged', { mode: mode });
  }

  /** Changes the density. @param {('comfortable'|'compact'|'terminal')} d @returns {void} */
  setDensity(d) {
    if (DENSITIES.indexOf(d) < 0 || d === this.density) { return; }
    this.density = d;
    this.draw();
    this.emit('densityChanged', { density: d });
  }

  /** Opens or closes a node. @param {string} id @param {boolean} [open] @returns {void} */
  toggle(id, open) { this.model.toggle(id, open); }

  /** Opens every node. @returns {void} */
  expandAll() { this.model.expandAll(); }

  /** Closes every node but the top. @returns {void} */
  collapseAll() { this.model.collapseAll(); }

  /**
   * New data (the open state and the selection kept where the ids remain); the host's figures asked again.
   * @param {import('./drishti_tree-model.js').HierarchySource} data
   * @returns {void}
   */
  setData(data) {
    this.quiet = true;
    try { this.model.load(data); } finally { this.quiet = false; }
    if (this.selected && !this.model.node(this.selected)) { this.selected = null; }
    this.draw();
    this.askSoon();
  }

  // ---- the edit mode (four eyes) ---------------------------------------------------------------------------------------------------------------

  /** Turns the edit mode on or off (default: the other state). @param {boolean} [on] @returns {void} */
  setEditing(on) {
    this.editing = on === undefined ? !this.editing : !!on;
    if (this.grid) { this.grid.refresh(); }
    this.draw();
  }

  /**
   * Proposes a move (the edit mode): drawn at once as a draft; the proposal event carries every move so far.
   * @param {string} id
   * @param {string} to the new parent
   * @returns {boolean} whether it was taken (a refused move says why: moveRefused)
   */
  proposeMove(id, to) {
    const why = this.model.moveProblem(id, to);
    if (why) { this.emit('moveRefused', { id: id, to: to, reason: why }); announce(why, { politeness: 'assertive' }); this.failed = why; this.drawSide(); this.failed = null; return false; }
    this.model.move(id, to);
    announce('Proposed: ' + this.model.node(id).label + ' to ' + this.model.node(to).label);
    this.emit('proposal', this.model.proposal());
    this.askSoon();
    return true;
  }

  /** The draft's proposal (its moves). @returns {{moves: Array<Object>}} */
  proposal() { return this.model.proposal(); }

  /** Undoes the last proposed move. @returns {void} */
  undoMove() { if (this.model.undoMove()) { this.emit('proposal', this.model.proposal()); this.askSoon(); } }

  /** The host's approval came: the draft becomes the hierarchy. @returns {void} */
  applyProposal() { const p = this.model.proposal(); this.model.commitMoves(); this.emit('proposalApplied', p); }

  /** The draft is thrown away. @returns {void} */
  discardProposal() { const p = this.model.proposal(); this.model.discardMoves(); this.emit('proposalDiscarded', p); this.askSoon(); }

  // ---- export -------------------------------------------------------------------------------------------------------------------------------

  /**
   * Exports the drawing (png, svg: the org chart and the partitions) or the data (csv, json).
   * @param {('png'|'svg'|'csv'|'json')} kind
   * @returns {Promise<{name: string, content: string, type: string}>}
   */
  exportAs(kind) { return exportTree(this, kind); }

  // ---- the person's actions ---------------------------------------------------------------------------------------------------------------------

  /** @private */
  clicked(e) {
    const t = e.target.closest ? e.target : null;
    if (!t) { return; }
    const mode = t.closest('[data-mode]'), act = t.closest('[data-act]'), crumb = t.closest('[data-crumb]'), tog = t.closest('[data-toggle]'), node = t.closest('.dt-view [data-id]');
    if (mode && this.toolbar.contains(mode)) { this.setMode(mode.getAttribute('data-mode')); return; }
    if (crumb) { this.select(crumb.getAttribute('data-crumb')); return; }
    if (tog) { this.toggle(tog.getAttribute('data-toggle')); return; }
    if (act) { this.act(act.getAttribute('data-act')); return; }
    if (node && !this.dragged) { this.active = node.getAttribute('data-id'); this.select(this.active); }
  }

  /** @private a toolbar's or the side panel's action */
  act(a) {
    const s = this.selected;
    const run = { 'expand-all': () => this.expandAll(), 'collapse-all': () => this.collapseAll(), edit: () => this.setEditing(), explain: () => s && this.explainNode(s),
      toggle: () => s && this.toggle(s), 'expand-below': () => s && this.model.expandAll({ under: s }), 'zoom-in': () => { this.focus = s; this.draw(); },
      'zoom-out': () => { const f = this.model.node(this.focus); this.focus = f && f.parent ? f.parent.id : null; this.draw(); },
      'zoom-in-org': () => { this.zoom = Math.min(3, this.zoom * 1.25); this.draw(); }, 'zoom-out-org': () => { this.zoom = Math.max(0.2, this.zoom / 1.25); this.draw(); }, fit: () => this.fit(),
      alt: () => { this.altOpen = !this.altOpen; this.altBtn.setAttribute('aria-pressed', String(this.altOpen)); this.draw(); },
      submit: () => { this.emit('proposalSubmitted', this.model.proposal()); announce('Submitted for review'); }, undo: () => this.undoMove(), discard: () => this.discardProposal(),
      png: () => this.exportAs('png').catch(() => null), svg: () => this.exportAs('svg').catch(() => null), csv: () => this.exportAs('csv'), json: () => this.exportAs('json') };
    if (run[a]) { run[a](); }
  }

  /** @private the org chart fitted to its view */
  fit() {
    if (!this.drawn || this.mode !== 'org') { return; }
    const r = this.view.getBoundingClientRect();
    this.zoom = Math.max(0.2, Math.min(1.2, (r.width - 20) / this.drawn.width, (r.height - 20) / this.drawn.height || 1));
    this.draw();
  }

  /** @private a select changed: the size of a partition, a move for the keyboard */
  changed(e) {
    const ctl = e.target.getAttribute && e.target.getAttribute('data-ctl');
    if (ctl === 'size') { this.sizeBy = e.target.value; this.draw(); }
    if (ctl === 'move-to' && e.target.value && this.selected) { this.proposeMove(this.selected, e.target.value); }
  }

  /** @private keys of the drawn modes */
  key(e) {
    if (this.mode === 'grid' || e.ctrlKey || e.metaKey || e.altKey) { return; }
    const part = this.mode !== 'org', order = part && this.drawn && this.drawn.slices ? this.drawn.slices.map((s) => s.id) : this.model.visible().map((n) => n.id);
    const mv = keyMove(e.key, this.model, order, this.active || this.selected || order[0], !part);
    if (!mv) { return; }
    e.preventDefault();
    this.active = mv.to;
    if (mv.act === 'open') { this.toggle(mv.to, true); } else if (mv.act === 'close') { this.toggle(mv.to, false); } else if (mv.act === 'expand-below') { this.model.expandAll({ under: mv.to }); }
    else if (mv.act === 'explain') { this.explainNode(mv.to); } else if (mv.act === 'select') { this.select(mv.to); }
    else { this.view.querySelectorAll('[data-id][tabindex="0"]').forEach((x) => x.setAttribute('tabindex', '-1')); }
    const el = this.view.querySelector('[data-id="' + cssId(mv.to) + '"]');
    if (el) { el.setAttribute('tabindex', '0'); el.focus(); const n = this.model.node(mv.to); announce(n.label + ', level ' + (n.depth + 1)); }
  }

  /** @private the edit mode: a box or a slice pressed */
  pressed(e) { this.dragged = false; this.from = this.editing && e.button === 0 && e.target.closest && e.target.closest('[data-id]') ? e.target.closest('[data-id]').getAttribute('data-id') : null; }

  /** @private released over another node: the move proposed */
  released(e) {
    const from = this.from;
    this.from = null;
    if (!from || this.mode === 'grid') { return; }
    const t = this.doc.elementFromPoint ? this.doc.elementFromPoint(e.clientX, e.clientY) : e.target, to = t && t.closest && t.closest('[data-id]');
    if (to && to.getAttribute('data-id') !== from) { this.dragged = true; this.proposeMove(from, to.getAttribute('data-id')); setTimeout(() => { this.dragged = false; }, 0); }
  }

  /** Takes the tree down. @returns {void} */
  destroy() {
    clearTimeout(this.askTimer);
    if (this.offModel) { this.offModel(); }
    if (this.offLink) { this.offLink(); }
    if (this.watch) { this.watch.disconnect(); }
    if (this.grid) { this.grid.destroy(); this.grid = null; }
    this.host.textContent = '';
    this.host.classList.remove('dt');
  }
}

function cssId(id) { return String(id).replace(/["\\]/g, '\\$&'); }
