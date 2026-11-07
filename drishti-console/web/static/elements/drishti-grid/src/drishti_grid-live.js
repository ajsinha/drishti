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
/* The grid's typed events and its live data. Events: one name each (GRID_EVENTS), emitted on the grid's drishti-core bus and, when the
   grid has an element (the web component), as DOM CustomEvents "drishti:<name in kebab case>" that bubble out of a shadow root.
   Live data: transactions by row id, applied at once or batched (applyTransactionAsync: a high-frequency feed's updates gathered for a
   few milliseconds, the last of a row's updates winning), each changed cell's direction kept for a flash, the figures of groups and of
   a local pivot changed by difference, and the non-additive ones (VaR, ES) asked of the server again, debounced. */

/** Every event a grid emits, by name. */
export const GRID_EVENTS = Object.freeze(['cellClicked', 'cellDoubleClicked', 'cellValueChanged', 'focusChanged', 'selectionChanged', 'rangeChanged',
  'sortChanged', 'filterChanged', 'pivotChanged', 'groupChanged', 'rowExpanded', 'rowCollapsed', 'columnMoved', 'columnResized', 'columnPinned',
  'columnVisible', 'rowDataUpdated', 'blockLoaded', 'blockFailed', 'explainOpened', 'serverTotalsUpdated', 'serverTotalsFailed', 'cellEditStarted',
  'cellEditStopped', 'editTransaction', 'editUndone', 'editRedone', 'proposalResolved']);

/**
 * The DOM name of a grid event: "drishti:" and the name in kebab case (cellClicked: drishti:cell-clicked).
 * @param {string} name
 * @returns {string}
 */
export function domEventName(name) { return 'drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()); }

/**
 * A row's update as the grid tells it: the row's id and each changed cell's old and new value and direction.
 * @typedef {Object} RowChange
 * @property {string} id
 * @property {Object<string, {old: *, value: *, dir: ?string}>} cells
 */

/**
 * The live side of a grid model: its events, transactions, batching, flashes, the feed and the server's totals.
 */
export class GridLive {
  /**
   * @param {Object} grid the GridModel
   * @param {{asyncWaitMs: number, flashMs: number, serverDebounceMs: number, now: function(): number}} [o] asyncWaitMs default 50; flashMs
   *   default 1000 (how long a change is a flash); serverDebounceMs default 250 (updates gathered before the server is asked again)
   */
  constructor(grid, o) {
    this.grid = grid;
    this.o = Object.assign({ asyncWaitMs: 50, flashMs: 1000, serverDebounceMs: 250, now: () => Date.now() }, o);
    this.queue = [];
    this.waiters = [];
    this.timer = 0;
    this.flashes = new Map();
    this.target = null;
    this.serverTimer = 0;
    this.staleGroups = new Map();
    this.stalePivot = false;
    this.stats = { batches: 0, updates: 0 };
  }

  /**
   * Emits a typed event on the bus and, when there is one, on the grid's element.
   * @param {string} name one of GRID_EVENTS
   * @param {Object} [detail]
   * @returns {void}
   */
  emit(name, detail) {
    if (GRID_EVENTS.indexOf(name) < 0) { throw new Error('not a grid event: ' + name); }
    this.grid.events.emit(name, detail || {});
    if (this.target && typeof CustomEvent === 'function') {
      this.target.dispatchEvent(new CustomEvent(domEventName(name), { detail: detail || {}, bubbles: true, composed: true }));
    }
  }

  /**
   * Applies a transaction now and tells everyone (rowDataUpdated, with every changed cell).
   * @param {{add: Object[], update: Object[], remove: string[]}} tx
   * @returns {{added: number, updated: number, removed: number, changes: RowChange[]}}
   */
  apply(tx) {
    const g = this.grid;
    if (g.serverSide) { throw new Error('the rows are the server\'s: send a dataChanged notice (dataChanged(notice))'); }
    const before = g.pivotMode && g.answer && g.answer.incremental ? (tx.update || []).map((r) => {
      const id = g.rowModel.getRowId(r, -1), i = g.rowModel.idIndex.get(id);
      return i === undefined ? null : { old: g.rowModel.rows[i], value: r };
    }).filter(Boolean) : null;
    const res = g.rowModel.applyTransaction(tx);
    const t = this.o.now();
    res.changes.forEach((ch) => Object.keys(ch.cells).forEach((col) => { if (ch.cells[col].dir) { this.flashes.set(ch.id + '\u001f' + col, { dir: ch.cells[col].dir, at: t }); } }));
    this.stats.updates += res.updated;
    if (g.pivotMode) { this.pivotAfter(before, res); }
    if (res.staleGroups.length) { res.staleGroups.forEach((p) => this.staleGroups.set(p.join('\u001f'), p)); this.askServerSoon(); }
    this.emit('rowDataUpdated', { added: res.added, updated: res.updated, removed: res.removed, changes: res.changes });
    return { added: res.added, updated: res.updated, removed: res.removed, changes: res.changes };
  }

  /** @private a local pivot after a transaction: by difference when it can be, else again; the non-additive measures asked again */
  pivotAfter(before, res) {
    const g = this.grid, a = g.answer;
    if (!a || g.serverSide || g.o.clientAggregation === false) { this.stalePivot = true; this.askServerSoon(); return; }
    const done = before && !res.added && !res.removed ? g.applyPivotUpdates(before) : false;
    if (!done) { g.runPivot(); return; }
    if (a.measures.some((m) => m.server)) { this.stalePivot = true; this.askServerSoon(); }
    g.events.emit('changed', { what: 'pivot' });
  }

  /**
   * Queues a transaction to apply with the others that arrive within asyncWaitMs (one refresh for many updates; a row's last update
   * wins; adds and removes keep their order).
   * @param {{add: Object[], update: Object[], remove: string[]}} tx
   * @returns {Promise<Object>} the batch's result, when applied
   */
  applyAsync(tx) {
    this.queue.push(tx);
    const p = new Promise((resolve) => this.waiters.push(resolve));
    if (!this.timer) { this.timer = setTimeout(() => this.flush(), this.o.asyncWaitMs); }
    return p;
  }

  /**
   * Applies the queued transactions now as one.
   * @returns {?Object} the batch's result (null when nothing was queued)
   */
  flush() {
    clearTimeout(this.timer);
    this.timer = 0;
    if (!this.queue.length) { return null; }
    const g = this.grid, add = [], remove = [], update = new Map();
    this.queue.forEach((tx) => {
      (tx.add || []).forEach((r) => add.push(r));
      (tx.remove || []).forEach((r) => { const id = typeof r === 'string' ? r : g.rowModel.getRowId(r, -1); remove.push(id); update.delete(id); });
      (tx.update || []).forEach((r) => update.set(g.rowModel.getRowId(r, -1), r));
    });
    this.queue = [];
    const waiters = this.waiters;
    this.waiters = [];
    this.stats.batches++;
    const res = this.apply({ add: add, update: Array.from(update.values()), remove: remove });
    waiters.forEach((w) => w(res));
    return res;
  }

  /**
   * A cell's flash: up or down while the change is younger than flashMs, else null.
   * @param {string} rowId
   * @param {string} colId
   * @returns {?string}
   */
  flashOf(rowId, colId) {
    const k = rowId + '\u001f' + colId, f = this.flashes.get(k);
    if (!f) { return null; }
    if (this.o.now() - f.at > this.o.flashMs) { this.flashes.delete(k); return null; }
    return f.dir;
  }

  /** @private the server asked again for what changed, once the updates have settled */
  askServerSoon() {
    clearTimeout(this.serverTimer);
    this.serverTimer = setTimeout(() => this.askServer(), this.o.serverDebounceMs);
  }

  /**
   * Asks the server now for the non-additive figures that went stale (group totals through serverGroupTotals; a pivot's through
   * serverPivot).
   * @returns {Promise<void>}
   */
  askServer() {
    clearTimeout(this.serverTimer);
    this.serverTimer = 0;
    const g = this.grid, waits = [];
    if (this.staleGroups.size && g.o.serverGroupTotals && !g.serverSide) {
      const want = g.rowModel.serverGroupColumns().filter((x) => this.staleGroups.has(x.path.join('\u001f')));
      this.staleGroups.clear();
      if (want.length) {
        waits.push(Promise.resolve(g.o.serverGroupTotals({ groupBy: g.rowModel.groupBy.slice(), groups: want })).then((answers) => {
          (answers || []).forEach((x) => g.rowModel.setGroupServerValues(x.path, x.values || {}));
          this.emit('serverTotalsUpdated', { groups: (answers || []).map((x) => x.path) });
          g.events.emit('changed', { what: 'rows', reason: 'server-totals' });
        }, (e) => this.emit('serverTotalsFailed', { error: e })));
      }
    }
    if (this.stalePivot && g.pivotMode && g.o.serverPivot) {
      this.stalePivot = false;
      waits.push(g.refreshServerMeasures().then((n) => this.emit('serverTotalsUpdated', { pivotCells: n }), (e) => this.emit('serverTotalsFailed', { error: e })));
    }
    return Promise.all(waits).then(() => undefined);
  }

  /**
   * Connects a live feed (drishti_live.js): transactions are batched into the rows in the page, dataChanged notices refresh server-side
   * rows, a rows message replaces every row.
   * @param {{subscribe: function(function(Object): void): function(): void}} feed
   * @returns {function(): void} disconnects
   */
  connect(feed) {
    const g = this.grid;
    return feed.subscribe((m) => {
      if (!m || !m.type) { return; }
      if (m.type === 'transaction') { if (g.serverSide) { g.dataChanged({ rows: (m.update || []).map((r) => g.rowModel.getRowId(r, -1)) }); } else { this.applyAsync(m); } }
      else if (m.type === 'dataChanged') { g.dataChanged(m); }
      else if (m.type === 'rows' && !g.serverSide) { g.rowModel.setRows(m.rows || []); this.emit('rowDataUpdated', { replaced: true, added: (m.rows || []).length, updated: 0, removed: 0, changes: [] }); }
    });
  }
}
