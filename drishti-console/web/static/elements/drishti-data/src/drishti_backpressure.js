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
/* Backpressure between a live feed and a grid (tested in Node): messages arrive faster than a grid draws, so a TransactionSink keeps
   at most maxInFlight batches in the grid's applyTransactionAsync and folds everything that arrives meanwhile into one pending
   transaction by row id: a row's later update wins, an add then an update stays an add of the new row, an add then a remove is
   nothing, an update then a remove is a remove. A "rows" message (every row replaced) drops what is pending; a dataChanged notice is
   passed on after the pending rows. Nothing is lost that matters (the last state of every row reaches the grid) and the grid never
   queues more than it can draw. The counts say how hard it worked (received, applied, coalesced, batches, the deepest queue). */

/**
 * The pending transaction of one sink: rows by id with what happens to each.
 * @private
 */
class Pending {
  constructor(getRowId) { this.getRowId = getRowId; this.map = new Map(); }
  get size() { return this.map.size; }
  add(row) { const id = this.getRowId(row), cur = this.map.get(id); this.map.set(id, { op: cur && cur.op === 'remove' ? 'update' : 'add', row: row }); return !!cur; }
  update(row) { const id = this.getRowId(row), cur = this.map.get(id); this.map.set(id, { op: cur && cur.op === 'add' ? 'add' : 'update', row: row }); return !!cur; }
  remove(id) { const cur = this.map.get(id); if (cur && cur.op === 'add') { this.map.delete(id); return true; } this.map.set(id, { op: 'remove', id: id }); return !!cur; }
  take() {
    const tx = { add: [], update: [], remove: [] };
    this.map.forEach((x, id) => { if (x.op === 'remove') { tx.remove.push(id); } else { tx[x.op].push(x.row); } });
    this.map.clear();
    return tx;
  }
  clear() { this.map.clear(); }
}

/**
 * Feeds a grid from a live feed with backpressure.
 */
export class TransactionSink {
  /**
   * @param {{applyTransactionAsync: function(Object): Promise, setRows: function(Object[]): void, dataChanged: function(Object): void}} target
   *   a drishti-grid Grid or GridModel (or anything with those methods)
   * @param {{getRowId: function(Object): string, maxInFlight: number, highWater: number, onStats: function(Object): void}} [o]
   *   getRowId default row.id; maxInFlight default 1; highWater: pending rows past which the sink says it is behind (default 10,000)
   */
  constructor(target, o) {
    if (!target || typeof target.applyTransactionAsync !== 'function') { throw new Error('a transaction sink needs a target with applyTransactionAsync (a drishti-grid Grid)'); }
    this.target = target;
    this.o = Object.assign({ getRowId: (r) => String(r.id), maxInFlight: 1, highWater: 10000 }, o);
    this.pending = new Pending((r) => String(this.o.getRowId(r)));
    this.inFlight = 0;
    this.after = [];
    this.stats = { received: 0, applied: 0, coalesced: 0, batches: 0, maxQueue: 0, behind: false };
    this.idle = [];
  }

  /**
   * One message of the feed: {type: transaction, add, update, remove} | {type: rows, rows} | {type: dataChanged, ...}.
   * @param {Object} msg
   * @returns {void}
   */
  push(msg) {
    if (!msg) { return; }
    const t = msg.type || 'transaction';
    if (t === 'rows') {
      this.pending.clear();
      this.after = [];
      if (this.target.setRows) { this.target.setRows(msg.rows || []); }
      this.report();
      return;
    }
    if (t === 'dataChanged') { this.after.push(msg); this.pump(); return; }
    if (t !== 'transaction') { return; }
    let n = 0, folded = 0;
    (msg.add || []).forEach((r) => { n++; if (this.pending.add(r)) { folded++; } });
    (msg.update || []).forEach((r) => { n++; if (this.pending.update(r)) { folded++; } });
    (msg.remove || []).forEach((id) => { n++; if (this.pending.remove(String(typeof id === 'object' ? this.o.getRowId(id) : id))) { folded++; } });
    this.stats.received += n;
    this.stats.coalesced += folded;
    this.stats.maxQueue = Math.max(this.stats.maxQueue, this.pending.size);
    this.stats.behind = this.pending.size > this.o.highWater;
    this.pump();
  }

  /** @private gives the grid what is pending when it has room */
  pump() {
    while (this.inFlight < this.o.maxInFlight && (this.pending.size || this.after.length)) {
      if (!this.pending.size) {
        const notice = this.after.shift();
        if (this.target.dataChanged) { this.target.dataChanged(notice); }
        continue;
      }
      const tx = this.pending.take(), rows = tx.add.length + tx.update.length + tx.remove.length;
      this.inFlight++;
      this.stats.batches++;
      Promise.resolve(this.target.applyTransactionAsync(tx)).then(() => {
        this.inFlight--;
        this.stats.applied += rows;
        this.stats.behind = this.pending.size > this.o.highWater;
        this.report();
        this.pump();
      }, (err) => { this.inFlight--; this.report(err); this.pump(); });
    }
    if (!this.inFlight && !this.pending.size && !this.after.length) { const w = this.idle; this.idle = []; w.forEach((f) => f()); }
  }

  /** @private */
  report(err) { if (this.o.onStats) { this.o.onStats(Object.assign({ pending: this.pending.size, inFlight: this.inFlight, error: err || null }, this.stats)); } }

  /**
   * Resolves when everything received has reached the grid.
   * @returns {Promise<void>}
   */
  drained() { return new Promise((resolve) => { this.idle.push(resolve); this.pump(); }); }
}

/**
 * Wires a feed (a LiveClient, or any {subscribe}) into a grid through a TransactionSink.
 * @param {{subscribe: function(function(Object): void): function(): void}} feed
 * @param {Object} grid a drishti-grid Grid or GridModel
 * @param {{getRowId: function(Object): string, maxInFlight: number, highWater: number, onStats: function(Object): void}} [o]
 * @returns {{sink: TransactionSink, stop: function(): void}}
 */
export function pipeToGrid(feed, grid, o) {
  const sink = new TransactionSink(grid, o);
  const stop = feed.subscribe((m) => sink.push(m));
  return { sink: sink, stop: stop };
}
