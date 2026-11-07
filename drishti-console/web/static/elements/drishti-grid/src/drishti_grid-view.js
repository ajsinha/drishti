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
/* drishti-grid as a person sees it: new Grid(element, options) draws the engine's model (drishti_grid.js) in one of three layouts
   (ELEMENTS.md 5.1): 'wells' (A: the ribbon of wells above the grid, Explain in a drawer that follows the selection; the default),
   'panel' (B: the side tool panel, Explain as a card at the cell) and 'terminal' (C: the command line, the lane of wells, Explain in a
   split pane, the function keys; the default of VaR screens). The density (comfortable, compact, terminal) is separate. Below 640 px a
   grid keeps its first column pinned and scrolls across; a pivot shows one value column with a measure switcher (O2). The chrome (toolbar,
   status bar, command line, keys) is drishti_grid-shell.js; menus, keys and actions drishti_grid-actions.js. */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { GridModel } from './drishti_grid.js';
import { LAYOUTS, DENSITIES } from './drishti_grid-model-base.js';
import { GridBody } from './drishti_grid-body.js';
import { WellsView } from './drishti_grid-wells-ui.js';
import { Menu, Notes } from './drishti_grid-dom.js';
import { FlashTracker, PHONE_WIDTH, VIEW_EVENTS } from './drishti_grid-ui.js';
import { GridBase } from './drishti_grid-base.js';
import { buildShell, renderChrome, renderStatus, renderExplainArea } from './drishti_grid-shell.js';
import { onKey, openColumnMenu, openCellMenu, runAction } from './drishti_grid-actions.js';
import { CellEditor } from './drishti_grid-editors.js';

let seq = 0;

/**
 * @typedef {Object} GridViewOptions
 * Everything GridModel takes (columns, rows or datasource, serverPivot, explain, clientAggregation, live ...), and:
 * @property {('wells'|'panel'|'terminal')} [layout='wells'] the arrangement (VaR screens: terminal)
 * @property {('comfortable'|'compact'|'terminal')} [density='compact']
 * @property {string} [title] the toolbar's title
 * @property {string} [subtitle] words after it (the source, the date)
 * @property {string} [label] the grid's name for a screen reader (default: the title)
 * @property {{rows: string[], columns: string[], values: Array<{colId: string, aggFunc: string}>, filters: Array}} [pivot] start as a pivot of these wells
 * @property {Object} [pivotOptions] the view's other parts at the start (sort, top, valueFilters, subtotals)
 * @property {boolean} [pinTotals=true] the pivot's grand total columns pinned on the right
 * @property {import('./drishti_grid-ui.js').ConditionalFormat[]} [conditionalFormats] colour scales and data bars
 * @property {boolean} [checkboxes=true] row checkboxes (when rows may be selected)
 * @property {function(Object): (Promise<Object[]>|Object[])} [drillThrough] the rows behind a cell (default: the rows in the page that
 *   match the cell's members)
 * @property {function(string): (Promise<Array<{text: string, count: number}>>|Array)} [members] a field's members for its filter (default:
 *   the rows in the page)
 * @property {number} [phoneWidth=640] below this width the grid is drawn for a phone
 * @property {GridModel} [model] draw this model rather than make one
 */

/**
 * The grid, drawn: a layout around the engine's model, with the plain JavaScript API every host uses (the web component wraps it).
 */
export class Grid extends GridBase {
  /**
   * @param {HTMLElement} host the element the grid fills
   * @param {GridViewOptions} options
   */
  constructor(host, options) {
    super();
    if (!host || !host.ownerDocument) { throw new Error('a grid needs an element to fill'); }
    const o = this.o = Object.assign({}, options);
    this.host = host;
    this.doc = host.ownerDocument;
    this.uid = 'dg' + (++seq);
    this.model = o.model || new GridModel(o);
    this.model.setEventTarget(host);
    this.formats = (o.conditionalFormats || []).slice();
    this.ui = { pinTotals: o.pinTotals !== false, hidden: new Set(), widths: {}, grandColumn: true, phoneMeasure: null, fieldsOpen: false };
    this.flash = new FlashTracker({ flashMs: (o.live && o.live.flashMs) || 1000 });
    this.draft = null;
    this.draftDirty = false;
    this.ex = null;
    this.exOpen = false;
    this.phone = false;
    this.listeners = [];
    this.menu = new Menu(this.doc, { onClose: () => {} });
    host.classList.add('dg');
    host.setAttribute('data-layout', this.model.layout);
    host.setAttribute('data-density', this.model.density);
    this.notes = new Notes(host, this.uid);
    this.wells = new WellsView(this);
    this.body = new GridBody(this);
    this.editor = new CellEditor(this);
    buildShell(this);
    this.wire();
    if (o.pivotOptions) { this.model.pivotExtra = Object.assign({}, o.pivotOptions); }
    if (o.pivot) { this.model.wells.setState(o.pivot); this.model.setPivotMode(true); }
    this.watchSize();
    this.refresh();
  }

  /** @private the model's events */
  wire() {
    const m = this.model;
    const on = (name, fn) => this.listeners.push(m.on(name, fn));
    on('changed', (p) => {
      if (p.what === 'columns' || p.what === 'pivot' || p.what === 'state') { this._disp = null; }
      if (p.what === 'rows' || p.what === 'pivot' || p.what === 'columns') { this.body.invalidate(); }
      if (p.what === 'pivot' || p.what === 'state') { this.chromeSoon(); }
      if (p.what === 'state') {
        this.host.setAttribute('data-layout', m.layout);
        this.host.setAttribute('data-density', m.density);
      }
      this.body.schedule();
      this.statusSoon();
    });
    on('rowDataUpdated', () => { this.flash.arm(); this.body.invalidate(); this.body.schedule(); });
    on('serverTotalsUpdated', () => { this.flash.arm(); this.body.invalidate(); this.body.schedule(); });
    on('pivotError', (p) => this.say('The pivot could not be computed: ' + (p.error && p.error.message ? p.error.message : p.error)));
    on('sortChanged', (p) => announce(p.sortModel.length ? 'Sorted by ' + p.sortModel.map((s) => this.headerOf(s.colId) + ' ' + (s.sort === 'asc' ? 'ascending' : 'descending')).join(', ') : 'Sort cleared'));
    on('filterChanged', () => announce('Filter changed: ' + m.rowCount() + ' rows'));
    on('pivotChanged', (p) => { announce('Pivot: ' + p.rows + ' rows'); this.flash.reset(); });
    this.body.scroller.addEventListener('keydown', (e) => onKey(this, e));
    this.body.scroller.addEventListener('paste', (e) => {
      if (e.target.closest && e.target.closest('.dg-editor')) { return; }
      const t = e.clipboardData ? e.clipboardData.getData('text/plain') : '';
      if (t) { e.preventDefault(); this.pasteText(t); }
    });
    on('changed', (p) => { if (p.what === 'edit') { this.body.invalidate(); this.body.schedule(); } });
  }

  /** @private follows the element's width: a phone below phoneWidth */
  watchSize() {
    const win = this.doc.defaultView;
    const check = () => {
      const w = this.host.clientWidth || win.innerWidth;
      const phone = w > 0 && w < (this.o.phoneWidth || PHONE_WIDTH);
      if (phone !== this.phone) {
        this.phone = phone;
        this.host.toggleAttribute('data-phone', phone);
        this._disp = null;
        this.emitView('phoneChanged', { phone: phone, width: w });
        this.refresh();
      } else { this.body.schedule(); }
    };
    if (win.ResizeObserver) { this.ro = new win.ResizeObserver(check); this.ro.observe(this.host); }
    else { this.onResize = check; win.addEventListener('resize', check); }
    check();
  }

  /** @private */
  chromeSoon() {
    if (this.chromeFrame) { return; }
    this.chromeFrame = this.doc.defaultView.requestAnimationFrame(() => { this.chromeFrame = 0; this.renderChrome(); });
  }

  /** @private */
  statusSoon() {
    if (this.statusFrame) { return; }
    this.statusFrame = this.doc.defaultView.requestAnimationFrame(() => { this.statusFrame = 0; renderStatus(this); });
  }

  /**
   * Draws everything again now (the chrome and the rows in view).
   * @returns {void}
   */
  refresh() { this._disp = null; this.body.invalidate(); this.renderChrome(); this.body.render(); renderStatus(this); }

  /**
   * Draws the chrome again: toolbar, wells, command line, function keys, status bar and the Explain area.
   * @returns {void}
   */
  renderChrome() { renderChrome(this); }

  // ---- focus, ranges, Explain ----------------------------------------------------------------------------------------------------------
  /**
   * The focus moved (a click or a key): the rows drawn again, the status bar, and Explain following the selection where it is open.
   * @param {boolean} scroll scroll the focus into view
   * @returns {void}
   */
  afterFocus(scroll) {
    if (scroll) { this.body.scrollToFocus(); }
    this.body.schedule();
    this.statusSoon();
    const f = this.model.navigator.focus;
    if (f && this.exOpen && this.o.explain && this.model.layout !== 'panel') {
      const win = this.doc.defaultView;
      win.clearTimeout(this.exTimer);
      this.exTimer = win.setTimeout(() => this.explain(f.row, f.col), 120);
    } else if (this.exOpen && this.model.layout === 'panel') { this.closeExplain(); }
  }

  /**
   * A drag across cells ended.
   * @returns {void}
   */
  afterRange() { this.body.schedule(); renderStatus(this); }

  /**
   * Says a row's new open state to a screen reader.
   * @param {number} r
   * @returns {void}
   */
  announceRow(r) {
    const x = this.model.rowAt(r);
    if (!x) { return; }
    const open = x.kind === 'pivot' ? x.entry.expanded : x.expanded;
    announce((open ? 'Expanded ' : 'Collapsed ') + (x.kind === 'pivot' ? x.entry.label : x.value));
  }

  /**
   * Explains a cell through the Explain hook, in the layout's place for it (drawer, card or split pane).
   * @param {number} r
   * @param {number} c
   * @returns {Promise<*>}
   */
  explain(r, c) {
    if (!this.o.explain) { this.say('This grid has no Explain hook'); return Promise.resolve(null); }
    const ex = this.ex = { row: r, col: c, loading: true, result: null, error: null };
    this.exOpen = true;
    renderExplainArea(this);
    return this.model.explain(r, c).then((res) => {
      if (this.ex !== ex) { return res; }
      ex.loading = false;
      ex.result = res;
      renderExplainArea(this);
      const ctx = this.model.cellContext(r, c);
      this.emitView('explainShown', { context: ctx, result: res });
      announce('Explain: ' + (res && res.title ? res.title + ' ' + (res.value || '') : (ctx ? ctx.header + ' ' + ctx.text : '')));
      return res;
    }, (err) => { if (this.ex === ex) { ex.loading = false; ex.error = err; renderExplainArea(this); } return null; });
  }

  /**
   * Explains the focused cell.
   * @returns {Promise<*>}
   */
  explainFocused() { const f = this.model.navigator.focus; return f ? this.explain(f.row, f.col) : Promise.resolve(null); }

  /**
   * Closes Explain.
   * @returns {void}
   */
  closeExplain() { this.ex = null; this.exOpen = false; renderExplainArea(this); }

  /**
   * Says something at the grid's foot (a live region).
   * @param {string} text
   * @returns {void}
   */
  say(text) { this.notes.say(text); }

  /**
   * Opens the column menu of a shown column.
   * @param {number} c
   * @param {Element} anchor
   * @returns {void}
   */
  openColumnMenu(c, anchor) { openColumnMenu(this, c, anchor); }

  /**
   * Opens the context menu of a cell.
   * @param {number} r
   * @param {number} c
   * @param {{x: number, y: number}} at
   * @returns {void}
   */
  openCellMenu(r, c, at) { openCellMenu(this, r, c, at); }

  /**
   * Runs one of the view's actions by id (a toolbar button, a function key, a menu item): explain, copy, copy-headers, export-csv,
   * export-xlsx, chart, drill, group, pin-totals, expand-all, collapse-all, toggle-pivot, save-view, ...
   * @param {string} id
   * @param {Object} [arg]
   * @returns {*}
   */
  runAction(id, arg) { return runAction(this, id, arg); }

  // ---- editing (drishti_grid-edit.js, drishti_grid-editors.js) ------------------------------------------------------------------------
  /**
   * Starts editing a cell (Enter, F2, a double click or typing do it); a checkbox turns at once.
   * @param {number} r
   * @param {number} c
   * @param {{initial: string}} [o] a typed character that replaces the value
   * @returns {boolean} whether editing started
   */
  startEditing(r, c, o) { return this.editor.start(r, c, o); }

  /**
   * Stops editing: saves the value (cancel false) or leaves the cell as it was.
   * @param {boolean} [cancel]
   * @returns {Promise<boolean>} whether a value was saved
   */
  stopEditing(cancel) { if (cancel) { this.editor.cancel(); return Promise.resolve(false); } return this.editor.commit(null); }

  /**
   * Takes the last edit transaction back.
   * @returns {Promise<?Object>}
   */
  undo() { return this.model.editing.undo().then((tx) => { this.say(tx ? 'Undone: ' + tx.edits.length + (tx.edits.length === 1 ? ' cell' : ' cells') : 'Nothing to undo'); return tx; }, (e) => { this.say('Not undone: ' + e.message); return null; }); }

  /**
   * Does the last undone edit transaction again.
   * @returns {Promise<?Object>}
   */
  redo() { return this.model.editing.redo().then((tx) => { this.say(tx ? 'Redone: ' + tx.edits.length + (tx.edits.length === 1 ? ' cell' : ' cells') : 'Nothing to redo'); return tx; }, (e) => { this.say('Not redone: ' + e.message); return null; }); }

  /**
   * Pastes tab-separated text (from Excel) at the focus, or fills the selected range with one value; one transaction.
   * @param {string} text
   * @returns {Promise<Object>} {transaction, pasted, skipped, problems}
   */
  pasteText(text) {
    const m = this.model, f = m.navigator.focus, g = m.selection.ranges[m.selection.ranges.length - 1];
    if (!f && !g) { this.say('Pick a cell to paste into'); return Promise.resolve({ transaction: null, pasted: 0, skipped: 0, problems: [] }); }
    const at = g ? { r: Math.min(g.r1, g.r2), c: Math.min(g.c1, g.c2), r2: Math.max(g.r1, g.r2), c2: Math.max(g.c1, g.c2) } : { r: f.row, c: f.col };
    return m.editing.paste(text, at).then((res) => {
      const words = 'Pasted ' + res.pasted + (res.pasted === 1 ? ' cell' : ' cells') + (res.skipped ? '; ' + res.skipped + ' cannot be edited' : '') + (res.problems.length ? '; ' + res.problems.length + ' not valid: ' + res.problems[0].message : '');
      this.say(words);
      return res;
    }, (e) => { this.say('Not pasted: ' + e.message); return { transaction: null, pasted: 0, skipped: 0, problems: [{ message: e.message }] }; });
  }

  /**
   * A four-eyes proposal of an edit was decided: approved keeps the values, rejected puts them back.
   * @param {string} proposalId
   * @param {('approved'|'rejected')} decision
   * @returns {boolean}
   */
  resolveProposal(proposalId, decision) { const ok = this.model.editing.resolveProposal(proposalId, decision); if (ok) { this.say('Proposal ' + proposalId + ' ' + decision); } return ok; }

  /**
   * Follows a filter bar's filter model on a bus (GridModel.followFilters).
   * @param {Object} bus drishti-core's EventBus
   * @param {{channel: string, source: string}} [o]
   * @returns {function(): void} stops following
   */
  followFilters(bus, o) { const off = this.model.followFilters(bus, o); this.listeners.push(off); return off; }

  // ---- the public API ---------------------------------------------------------------------------------------------------------------------
  /**
   * Changes the layout: 'wells', 'panel' or 'terminal'.
   * @param {('wells'|'panel'|'terminal')} layout
   * @returns {void}
   */
  setLayout(layout) {
    if (LAYOUTS.indexOf(layout) < 0) { throw new Error('no layout ' + layout + ' (' + LAYOUTS.join(', ') + ')'); }
    this.model.setLayout(layout);
    this.closeExplain();
    buildShell(this);
    this.refresh();
    this.emitView('layoutChanged', { layout: layout });
  }

  /**
   * Changes the density: 'comfortable', 'compact' or 'terminal'.
   * @param {('comfortable'|'compact'|'terminal')} density
   * @returns {void}
   */
  setDensity(density) {
    if (!DENSITIES[density]) { throw new Error('no density ' + density); }
    this.model.setDensity(density);
    this.refresh();
    this.emitView('densityChanged', { density: density });
  }

  /**
   * Turns the pivot on or off.
   * @param {boolean} on
   * @returns {Promise<void>}
   */
  setPivotMode(on) { this.closeExplain(); this.model.navigator.setFocus(null); this.model.selection.clearRanges(); return this.model.setPivotMode(on).then(() => this.renderChrome()); }

  /**
   * Sets the wells and turns the pivot on.
   * @param {{rows: string[], columns: string[], values: Array<{colId: string, aggFunc: string}>, filters: Array}} wells
   * @returns {Promise<void>}
   */
  setPivot(wells) { this.model.wells.setState(wells); return this.setPivotMode(true); }

  /**
   * Replaces the conditional formats.
   * @param {import('./drishti_grid-ui.js').ConditionalFormat[]} rules
   * @returns {void}
   */
  setConditionalFormats(rules) { this.formats = (rules || []).slice(); this.body.invalidate(); this.body.schedule(); }

  /**
   * Replaces the rows (client-side).
   * @param {Object[]} rows
   * @returns {void}
   */
  setRows(rows) { this.model.rowModel.setRows(rows); if (this.model.pivotMode) { this.model.runPivot(); } this.flash.reset(); }

  /**
   * Adds, updates and removes rows by id now; changed figures flash.
   * @param {{add: Object[], update: Object[], remove: string[]}} tx
   * @returns {Object}
   */
  applyTransaction(tx) { return this.model.applyTransaction(tx); }

  /**
   * Queues a transaction with the others of the next few milliseconds (a live feed).
   * @param {{add: Object[], update: Object[], remove: string[]}} tx
   * @returns {Promise<Object>}
   */
  applyTransactionAsync(tx) { return this.model.applyTransactionAsync(tx); }

  /**
   * Connects a live feed (drishti_live.js).
   * @param {{subscribe: function(function(Object): void): function(): void}} feed
   * @returns {function(): void} disconnects
   */
  connectLive(feed) { const off = this.model.connectLive(feed); this.live = true; this.renderChrome(); return () => { off(); this.live = false; this.renderChrome(); }; }

  /**
   * Puts the focus on a cell (and the range there) and scrolls it into view.
   * @param {number} r
   * @param {number} c
   * @returns {void}
   */
  focusCell(r, c) { this.model.cellClicked(r, c, {}); this.afterFocus(true); this.body.scroller.focus({ preventScroll: true }); }

  /**
   * Everything a person set, to keep and give back: the model's state and the view's (pinned totals, hidden columns, widths, formats).
   * @returns {Object}
   */
  getState() {
    return Object.assign(this.model.getState(), { view: { pinTotals: this.ui.pinTotals, hidden: Array.from(this.ui.hidden), widths: Object.assign({}, this.ui.widths),
      grandColumn: this.ui.grandColumn, formats: this.formats.slice() } });
  }

  /**
   * Gives back a kept state.
   * @param {Object} s
   * @returns {Promise<void>}
   */
  setState(s) {
    if (s.view) {
      this.ui.pinTotals = s.view.pinTotals !== false;
      this.ui.hidden = new Set(s.view.hidden || []);
      this.ui.widths = Object.assign({}, s.view.widths);
      this.ui.grandColumn = s.view.grandColumn !== false;
      if (s.view.formats) { this.formats = s.view.formats.slice(); }
    }
    const layout = s.layout && s.layout !== this.model.layout ? s.layout : null;
    return this.model.setState(s).then(() => { if (layout) { buildShell(this); } this.refresh(); });
  }

  /**
   * Listens to a grid event (GRID_EVENTS) or a view event (VIEW_EVENTS).
   * @param {string} name
   * @param {function(Object, string): void} fn
   * @returns {function(): void} stops listening
   */
  on(name, fn) { return this.model.on(name, fn); }

  /**
   * Emits a view event on the grid's bus and as a DOM event on the element (drishti:<kebab-name>).
   * @param {string} name one of VIEW_EVENTS
   * @param {Object} [detail]
   * @returns {void}
   */
  emitView(name, detail) {
    if (VIEW_EVENTS.indexOf(name) < 0) { throw new Error('not a view event: ' + name); }
    this.model.events.emit(name, detail || {});
    const ev = 'drishti:' + name.replace(/[A-Z]/g, (ch) => '-' + ch.toLowerCase());
    this.host.dispatchEvent(new this.doc.defaultView.CustomEvent(ev, { detail: detail || {}, bubbles: true, composed: true }));
  }

  /**
   * Takes the grid out of its element: listeners, menus, notes.
   * @returns {void}
   */
  destroy() {
    this.editor.cancel();
    this.listeners.forEach((off) => off());
    this.body.destroy();
    this.menu.close();
    this.notes.destroy();
    if (this.ro) { this.ro.disconnect(); }
    if (this.onResize) { this.doc.defaultView.removeEventListener('resize', this.onResize); }
    this.model.setEventTarget(null);
    this.host.textContent = '';
    this.host.classList.remove('dg');
  }
}
