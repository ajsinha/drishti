/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The flow between a message's arrival and its listeners. Messages can arrive faster than a page can draw, so each subscription has a
   small pipeline:

     1. conflate by key: a later message replaces an earlier undelivered one with the same key (a price per symbol);
     2. hold in a bounded buffer: the overflow rule is the subscription's, and every message dropped is counted with its reason;
     3. deliver now, once per animation frame, or at most every everyMs, optionally as one list. A hidden page has no animation frames:
        the pipeline then delivers on a one-second timer and keeps conflating, so memory stays bounded and the page is right when shown.

   The grid sink (subscription.toGrid) is this pipeline ending in a grid's applyTransactionAsync with at most one batch in flight; its own
   folding is the grid's (an add then an update is an add of the new row, an add then a remove is nothing, an update then a remove is a
   remove; a 'rows' message replaces every row; a 'dataChanged' notice follows the rows before it). */
import { EventError } from './drishti_envelope.js';

/** The overflow rules of a buffer. */
export const OVERFLOW_RULES = Object.freeze(['conflate', 'drop-oldest', 'drop-newest', 'error']);

/** How long a hidden page waits between deliveries (it has no animation frames). */
export const HIDDEN_DELIVERY_MS = 1000;

/**
 * The latest value by key: push messages, take what is held in arrival order (a later message with a held key takes the earlier one's
 * place, or is merged into it). Bounded: past max the overflow rule applies.
 */
export class Conflater {
  /**
   * @param {{key: (string|function(Object): ?string), merge: function(Object, Object): Object, max: number, overflow: string,
   *   onDrop: function(Object, string): void}} [o] key: a field of the message ('key', 'topic'), else of its data, or a function (null:
   *   not conflated); default the message's key. max default 10,000; overflow default 'conflate' (a message whose key is held replaces
   *   it, else the oldest goes); onDrop hears each dropped message and its reason (conflated, overflow)
   */
  constructor(o) {
    this.o = Object.assign({ max: 10000, overflow: 'conflate' }, o);
    if (OVERFLOW_RULES.indexOf(this.o.overflow) < 0) { throw new EventError('buffer.invalid', 'an overflow rule is one of ' + OVERFLOW_RULES.join(', ') + '; got ' + this.o.overflow); }
    this.keyOf = keyFunction(this.o.key);
    /** @private */ this.held = new Map();
    /** @private */ this.n = 0;
  }

  /**
   * How many messages are held.
   * @type {number}
   */
  get size() { return this.held.size; }

  /**
   * Adds a message (it may replace a held one with its key, or be dropped by the overflow rule).
   * @param {Object} m
   * @returns {boolean} false: the message was refused (overflow 'error' or 'drop-newest')
   */
  push(m) {
    const k = this.keyOf ? this.keyOf(m) : null;
    const slot = k === null || k === undefined ? 'u' + (this.n++) : 'k' + k;
    const old = this.held.get(slot);
    if (old) {
      this.held.set(slot, this.o.merge ? this.o.merge(old, m) : m);
      this.drop(old, 'conflated');
      return true;
    }
    if (this.held.size >= this.o.max) {
      const rule = this.o.overflow;
      if (rule === 'drop-newest' || rule === 'error') { this.drop(m, 'overflow'); return false; }
      const first = this.held.keys().next().value;
      const gone = this.held.get(first);
      this.held.delete(first);
      this.drop(gone, 'overflow');
    }
    this.held.set(slot, m);
    return true;
  }

  /**
   * Takes everything held, in arrival order.
   * @returns {Object[]}
   */
  take() {
    const out = Array.from(this.held.values());
    this.held.clear();
    return out;
  }

  /**
   * Forgets everything held.
   * @returns {void}
   */
  clear() { this.held.clear(); }

  /** @private */
  drop(m, why) { if (this.o.onDrop) { this.o.onDrop(m, why); } }
}

/** @private the key of a message: a function, a field of the message or of its data, or the message's key */
function keyFunction(key) {
  if (typeof key === 'function') { return (m) => { const k = key(m); return k === undefined || k === null ? null : String(k); }; }
  if (typeof key === 'string' && key) {
    return (m) => {
      const v = m[key] !== undefined ? m[key] : (m.data && typeof m.data === 'object' ? m.data[key] : undefined);
      return v === undefined || v === null ? null : String(v);
    };
  }
  return (m) => (m.key === undefined || m.key === null ? null : String(m.key));
}

/**
 * A subscription's pipeline. The subscription pushes; the flow hands messages (or lists) to its _deliver.
 */
export class Flow {
  /**
   * @param {Object} sub the subscription (its _deliver)
   * @param {Object} o its options: conflate, deliver, batch, buffer
   * @param {Object} events the Events (clock, limits, drops)
   */
  constructor(sub, o, events) {
    this.sub = sub;
    this.events = events;
    this.clock = events.clock;
    const d = o.deliver || 'now';
    /** @type {string} now, frame or every */
    this.mode = d === 'frame' ? 'frame' : (d && typeof d === 'object' && d.everyMs > 0) ? 'every' : 'now';
    if (d !== 'now' && d !== 'frame' && this.mode === 'now') { throw new EventError('deliver.invalid', "deliver is 'now', 'frame' or { everyMs }"); }
    this.everyMs = this.mode === 'every' ? Number(d.everyMs) : 0;
    this.batch = !!o.batch;
    const buf = Object.assign({ max: events.limits.undelivered, overflow: 'conflate' }, o.buffer);
    const c = o.conflate;
    this.conflating = !!c;
    /** @private */
    this.q = new Conflater({ key: c ? (c === true ? undefined : c.key) : () => null, merge: c && c.merge, max: buf.max, overflow: buf.overflow,
      onDrop: (m, why) => {
        events._drop(why, m, sub, why === 'conflated' ? 'Conflated: replaced by a later message with the same key' : 'Dropped: the buffer was full (' + buf.max + '), rule ' + buf.overflow);
        if (why === 'overflow' && buf.overflow === 'error') { events._error(new EventError('limit.buffer', 'the subscription to ' + sub.pattern + ' holds ' + buf.max + ' undelivered messages'), { pattern: sub.pattern, where: 'buffer' }); }
      } });
    this.direct = this.mode === 'now' && !this.conflating;
    this.frame = null;
    this.timer = null;
    this.last = 0;
    this.stopped = false;
  }

  /**
   * A message in.
   * @param {Object} m
   * @returns {void}
   */
  push(m) {
    if (this.stopped) { return; }
    if (this.direct) { this.sub._deliver(this.batch ? [m] : m); return; }
    this.q.push(m);
    if (this.mode === 'now') { this.drain(); return; }
    this.schedule();
  }

  /** @private one delivery is asked for: the next frame (with a one-second timer behind it: a hidden page has none), or the next tick */
  schedule() {
    if (this.mode === 'frame') {
      if (this.frame === null && !this.clock.hidden()) { this.frame = this.clock.requestFrame(() => { this.frame = null; this.drain(); }); }
      if (this.timer === null) { this.timer = this.clock.setTimeout(() => { this.timer = null; this.drain(); }, HIDDEN_DELIVERY_MS); }
      return;
    }
    if (this.timer === null) {
      const wait = this.clock.hidden() ? Math.max(this.everyMs, HIDDEN_DELIVERY_MS) : Math.max(0, this.everyMs - (this.clock.now() - this.last));
      this.timer = this.clock.setTimeout(() => { this.timer = null; this.drain(); }, wait);
    }
  }

  /**
   * Delivers what is held now.
   * @returns {number} how many messages
   */
  drain() {
    if (this.frame !== null) { this.clock.cancelFrame(this.frame); this.frame = null; }
    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
    this.last = this.clock.now();
    const out = this.q.take();
    if (!out.length || this.stopped) { return 0; }
    if (this.batch) { this.sub._deliver(out); } else { out.forEach((m) => this.sub._deliver(m)); }
    return out.length;
  }

  /**
   * How many messages wait.
   * @type {number}
   */
  get size() { return this.q.size; }

  /**
   * Stops: what waits is not delivered.
   * @returns {void}
   */
  stop() {
    this.stopped = true;
    if (this.frame !== null) { this.clock.cancelFrame(this.frame); this.frame = null; }
    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
    this.q.clear();
  }
}

/**
 * The flow of a subscription.
 * @param {Object} sub
 * @param {Object} o
 * @param {Object} events
 * @returns {Flow}
 */
export function createFlow(sub, o, events) { return new Flow(sub, o, events); }

// ---- the grid sink ------------------------------------------------------------------------------------------------------------------------

/** @private the pending transaction of a grid sink: rows by id with what happens to each */
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
 * The options of a grid sink.
 * @typedef {Object} GridSinkOptions
 * @property {function(Object): string} [getRowId] default row.id
 * @property {number} [maxInFlight=1] batches in the grid's applyTransactionAsync at once
 * @property {number} [highWater=10000] pending rows past which the sink says it is behind
 * @property {function(Object): void} [onStats] the counts after each batch (received, applied, coalesced, batches, maxQueue, behind, pending)
 */

/**
 * A grid fed with backpressure: at most maxInFlight batches in the grid, everything arriving meanwhile folded by row id. A message's data is
 * {type: 'transaction', add, update, remove}, {type: 'rows', rows} or {type: 'dataChanged', ...}.
 */
export class GridSink {
  /**
   * @param {{applyTransactionAsync: function(Object): Promise, setRows: function(Object[]): void, dataChanged: function(Object): void}} target
   *   a drishti-grid Grid or GridModel (or anything with those methods)
   * @param {GridSinkOptions} [o]
   */
  constructor(target, o) {
    if (!target || typeof target.applyTransactionAsync !== 'function') { throw new EventError('grid.invalid', 'a grid sink needs a target with applyTransactionAsync (a drishti-grid Grid)'); }
    this.target = target;
    this.o = Object.assign({ getRowId: (r) => String(r.id), maxInFlight: 1, highWater: 10000 }, o);
    this.pending = new Pending((r) => String(this.o.getRowId(r)));
    this.inFlight = 0;
    this.after = [];
    /** @type {{received: number, applied: number, coalesced: number, batches: number, maxQueue: number, behind: boolean}} */
    this.stats = { received: 0, applied: 0, coalesced: 0, batches: 0, maxQueue: 0, behind: false };
    this.idle = [];
  }

  /**
   * One message's data: a transaction, every row, or a data-changed notice.
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
