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
/* The offline queue: a publish made while the connection is down is held and sent, in order, when it is back; every publish waits here
   until the server acknowledges it (and is sent again after a reconnect: its id makes that harmless). Bounded (1,000 messages, 1 MB,
   24 hours; past a bound a publish is refused in words, or with overflow 'drop-oldest' the oldest goes) and aged (a publish's ttlMs).

   Persistence is off by default: the payloads would rest on the person's disk. queue: 'persist' on a publish, or outbox: { persist: true },
   keeps it in IndexedDB so it survives a reload; where IndexedDB is not to be had the queue is memory and says so (persisted: false).
   What a reload left behind is sent by one tab at a time (a Web Lock), so tabs never send a queued message twice. Tokens are never
   written: a frame carries none. */
import { EventError } from './drishti_envelope.js';
import { encodeFrames } from './drishti_protocol.js';

/** The queue's bounds by default. */
export const OUTBOX_DEFAULTS = Object.freeze({ max: 1000, maxBytes: 1048576, maxAgeMs: 86400000, overflow: 'reject' });

/**
 * Where persisted publishes rest: IndexedDB, or a stand-in with the same calls.
 * @typedef {Object} OutboxStore
 * @property {string} kind 'indexeddb' or 'memory'
 * @property {function(Object): Promise<void>} put
 * @property {function(string): Promise<void>} delete
 * @property {function(): Promise<Object[]>} all
 * @property {function(): Promise<void>} clear
 */

/**
 * A store in memory, with the calls of the IndexedDB one (tests; a page that wants none on disk). Kept across connections it stands
 * for a reload.
 * @returns {OutboxStore}
 */
export function memoryStore() {
  const rows = new Map();
  return {
    kind: 'memory',
    put: (e) => { rows.set(e.id, JSON.parse(JSON.stringify(e))); return Promise.resolve(); },
    delete: (id) => { rows.delete(id); return Promise.resolve(); },
    all: () => Promise.resolve(Array.from(rows.values()).map((e) => JSON.parse(JSON.stringify(e)))),
    clear: () => { rows.clear(); return Promise.resolve(); }
  };
}

/**
 * The IndexedDB store of an application (and a signed-in person: the namespace), or null where there is no IndexedDB.
 * @param {{namespace: string, indexedDB: Object}} [o] namespace: part of the database's name; indexedDB: a stand-in
 * @returns {?OutboxStore}
 */
export function indexedDbStore(o) {
  const IDB = (o && o.indexedDB) || (typeof indexedDB !== 'undefined' ? indexedDB : null);
  if (!IDB) { return null; }
  const name = 'drishti-events' + (o && o.namespace ? '.' + o.namespace : '');
  let db = null;
  const open = () => db || (db = new Promise((resolve, reject) => {
    let r;
    try { r = IDB.open(name, 1); } catch (e) { reject(e); return; }
    r.onupgradeneeded = () => { r.result.createObjectStore('outbox', { keyPath: 'id' }); };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error || new Error('IndexedDB would not open'));
  }));
  const run = (mode, fn) => open().then((d) => new Promise((resolve, reject) => {
    const t = d.transaction('outbox', mode);
    const req = fn(t.objectStore('outbox'));
    t.oncomplete = () => resolve(req ? req.result : undefined);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error || new Error('the IndexedDB transaction was aborted'));
  }));
  return {
    kind: 'indexeddb',
    put: (e) => run('readwrite', (s) => s.put(e)).then(() => undefined),
    delete: (id) => run('readwrite', (s) => s.delete(id)).then(() => undefined),
    all: () => run('readonly', (s) => s.getAll()).then((x) => x || []),
    clear: () => run('readwrite', (s) => s.clear()).then(() => undefined)
  };
}

/**
 * The options of the queue (connect's outbox option).
 * @typedef {Object} OutboxOptions
 * @property {boolean} [persist=false] every publish kept in IndexedDB until acknowledged
 * @property {number} [max=1000] messages
 * @property {number} [maxBytes=1048576] bytes
 * @property {number} [maxAgeMs=86400000] a message older than this is dropped (expired)
 * @property {('reject'|'drop-oldest')} [overflow='reject'] past a bound: the publish is refused, or the oldest message goes
 * @property {OutboxStore} [store] where persisted messages rest (default IndexedDB; memoryStore() in tests)
 * @property {boolean} [restore] take back, when the connection first opens, what an earlier page left on disk (before this page's own
 *   publishes); default true when persist is on or a store is given (a page that never persists opens no database)
 */

/**
 * The publishes not yet acknowledged, in the order they were made.
 */
export class Outbox {
  /**
   * @param {{clock: Object, options: OutboxOptions, namespace: string, env: Object, onDrop: function(Object, string, string): void}} o
   */
  constructor(o) {
    this.clock = o.clock;
    this.o = Object.assign({}, OUTBOX_DEFAULTS, o.options);
    if (['reject', 'drop-oldest'].indexOf(this.o.overflow) < 0) { throw new EventError('outbox.invalid', "the queue's overflow is 'reject' or 'drop-oldest'; got " + this.o.overflow); }
    this.onDrop = o.onDrop || (() => undefined);
    this.env = o.env || {};
    this.namespace = o.namespace || '';
    /** @private */ this.entries = new Map();
    /** @private */ this.bytes = 0;
    /** @private */ this.timer = null;
    /** @private */ this.storeAsked = false;
    /** @private */ this.store = null;
    /** @type {boolean} whether persisted publishes can rest on disk here */
    this.persisted = false;
    this.restored = false;
  }

  /** @private the store, made when first wanted */
  _store() {
    if (!this.storeAsked) {
      this.storeAsked = true;
      this.store = this.o.store || indexedDbStore({ namespace: this.namespace, indexedDB: this.env.indexedDB });
      this.persisted = !!this.store;
    }
    return this.store;
  }

  /**
   * How many messages wait.
   * @type {number}
   */
  get size() { return this.entries.size; }

  /**
   * Whether a message waits.
   * @param {string} id
   * @returns {boolean}
   */
  has(id) { return this.entries.has(id); }

  /**
   * Holds a publish (refused in words past a bound, unless the oldest may go).
   * @param {Object} frame the pub frame
   * @param {{persist: boolean, ttlMs: number, ack: boolean, resolve: Function, reject: Function}} o
   * @returns {Object} the entry
   */
  add(frame, o) {
    this.sweep();
    const bytes = encodeFrames([frame]).length;
    const now = this.clock.now();
    while (this.entries.size >= this.o.max || this.bytes + bytes > this.o.maxBytes) {
      if (this.o.overflow !== 'drop-oldest' || !this.entries.size) {
        throw new EventError('limit.outbox', this.entries.size >= this.o.max ? this.entries.size + ' messages wait already (the limit is ' + this.o.max + ')' :
          'the waiting messages would pass ' + this.o.maxBytes + ' bytes');
      }
      const first = this.entries.values().next().value;
      this.remove(first.id, new EventError('dropped', 'the queue was full: the oldest message was dropped'), 'overflow');
    }
    const persist = !!(o.persist || this.o.persist) && !!this._store();
    const e = { id: frame.id, frame: frame, bytes: bytes, at: now, expiresAt: Math.min(now + this.o.maxAgeMs, o.ttlMs ? now + o.ttlMs : Infinity),
      persist: persist, ack: o.ack !== false, sent: false, tries: 0, resolve: o.resolve || null, reject: o.reject || null };
    this.entries.set(e.id, e);
    this.bytes += bytes;
    if (persist) { this.store.put({ id: e.id, frame: frame, at: e.at, expiresAt: isFinite(e.expiresAt) ? e.expiresAt : null }).catch(() => { this.persisted = false; }); }
    this._arm();
    return e;
  }

  /**
   * What waits to be sent, in order (expired messages dropped first); each is marked sent.
   * @returns {Object[]} the entries
   */
  takeUnsent() {
    this.sweep();
    const out = [];
    this.entries.forEach((e) => { if (!e.sent) { e.sent = true; e.tries++; out.push(e); } });
    return out;
  }

  /**
   * After a reconnect every message not acknowledged is to be sent again.
   * @returns {number} how many were sent before
   */
  resend() {
    let n = 0;
    this.entries.forEach((e) => { if (e.sent) { e.sent = false; n++; } });
    return n;
  }

  /**
   * A message was sent and wants no acknowledgement, or it was acknowledged: it leaves the queue (and the disk).
   * @param {string} id
   * @param {boolean} acked
   * @returns {boolean} whether it was waiting
   */
  done(id, acked) {
    const e = this.entries.get(id);
    if (!e) { return false; }
    this._forget(e);
    if (e.resolve) { e.resolve({ acked: acked }); }
    return true;
  }

  /**
   * A message is given up (refused, dropped, aborted, expired): it leaves the queue and its publish is rejected.
   * @param {string} id
   * @param {Error} err
   * @param {string} [reason] counted as a drop with this reason
   * @returns {boolean}
   */
  remove(id, err, reason) {
    const e = this.entries.get(id);
    if (!e) { return false; }
    this._forget(e);
    if (reason) { this.onDrop(e.frame, reason, err.detail || err.message); }
    if (e.reject) { e.reject(err); }
    return true;
  }

  /** @private */
  _forget(e) {
    this.entries.delete(e.id);
    this.bytes -= e.bytes;
    if (e.persist && this.store) { this.store.delete(e.id).catch(() => undefined); }
    if (!this.entries.size && this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
  }

  /**
   * Drops what is older than its age or ttl.
   * @returns {number} how many
   */
  sweep() {
    const now = this.clock.now();
    let n = 0;
    Array.from(this.entries.values()).forEach((e) => {
      if (e.expiresAt <= now) { n++; this.remove(e.id, new EventError('expired', 'the message waited longer than it may (' + Math.round((now - e.at) / 1000) + ' s) and was not sent'), 'expired'); }
    });
    return n;
  }

  /** @private a timer at the earliest expiry, so an expired publish is refused even while nothing else happens */
  _arm() {
    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
    let soon = Infinity;
    this.entries.forEach((e) => { if (e.expiresAt < soon) { soon = e.expiresAt; } });
    if (!isFinite(soon)) { return; }
    this.timer = this.clock.setTimeout(() => { this.timer = null; this.sweep(); this._arm(); }, Math.max(0, soon - this.clock.now()));
  }

  /**
   * Whether the connection's first opening takes back what an earlier page left (once).
   * @returns {boolean}
   */
  wantsRestore() {
    if (this.restored) { return false; }
    const want = this.o.restore !== undefined ? !!this.o.restore : !!(this.o.persist || this.o.store);
    if (!want) { this.restored = true; }
    return want;
  }

  /**
   * What an earlier page left on disk, taken back (under a Web Lock, so one tab at a time): those messages are sent with the rest. The
   * lock is held until they are acknowledged (or for at most holdMs).
   * @param {function(): void} send called when the restored messages are in the queue
   * @param {number} [holdMs=10000]
   * @returns {Promise<number>} how many were restored
   */
  restore(send, holdMs) {
    if (this.restored || !this._store()) { this.restored = true; send(); return Promise.resolve(0); }
    this.restored = true;
    const store = this.store;
    const work = () => store.all().then((rows) => {
      const now = this.clock.now();
      const back = rows.filter((r) => !this.entries.has(r.id)).sort((a, b) => a.at - b.at);
      back.forEach((r) => {
        if (r.expiresAt !== null && r.expiresAt <= now || now - r.at > this.o.maxAgeMs) { store.delete(r.id).catch(() => undefined); this.onDrop(r.frame, 'expired', 'a message left by an earlier page was too old'); return; }
        const e = { id: r.id, frame: r.frame, bytes: encodeFrames([r.frame]).length, at: r.at, expiresAt: r.expiresAt === null ? r.at + this.o.maxAgeMs : r.expiresAt,
          persist: true, ack: r.frame.ack !== false, sent: false, tries: 0, resolve: null, reject: null, restored: true };
        this.entries.set(e.id, e);
        this.bytes += e.bytes;
      });
      // the restored go first: they were made before this page's own
      const mine = Array.from(this.entries.values()).filter((e) => !e.restored);
      mine.forEach((e) => { this.entries.delete(e.id); this.entries.set(e.id, e); });
      this._arm();
      send();
      if (!back.length) { return 0; }
      return new Promise((resolve) => {
        const end = this.clock.now() + (holdMs || 10000);
        const look = () => {
          if (back.every((r) => !this.entries.has(r.id)) || this.clock.now() >= end) { resolve(back.length); return; }
          this.clock.setTimeout(look, 100);
        };
        look();
      });
    }, () => { this.persisted = false; return 0; });
    const locks = this.env.locks || (typeof navigator !== 'undefined' && navigator.locks) || null;
    if (locks && locks.request) { return Promise.resolve(locks.request('drishti-events.outbox' + (this.namespace ? '.' + this.namespace : ''), work)); }
    return work();
  }

  /**
   * Empties the queue (and the disk): every waiting publish is rejected.
   * @returns {Promise<number>} how many were dropped
   */
  clear() {
    const n = this.entries.size;
    Array.from(this.entries.values()).forEach((e) => this.remove(e.id, new EventError('cleared', 'the queue was emptied before the message was acknowledged')));
    const st = this.persisted || this.o.store ? this._store() : null;
    return (st ? st.clear().catch(() => undefined) : Promise.resolve()).then(() => n);
  }

  /**
   * The queue's figures.
   * @returns {{count: number, bytes: number, unsent: number, persisted: boolean, oldestMs: ?number}}
   */
  metrics() {
    let unsent = 0, oldest = Infinity;
    this.entries.forEach((e) => { if (!e.sent) { unsent++; } if (e.at < oldest) { oldest = e.at; } });
    return { count: this.entries.size, bytes: this.bytes, unsent: unsent, persisted: this.storeAsked ? this.persisted : !!(this.o.store || this.o.persist),
      oldestMs: isFinite(oldest) ? this.clock.now() - oldest : null };
  }

  /**
   * Stops the expiry timer and rejects what waits (the connection is destroyed); the disk keeps persisted messages for the next page.
   * @param {Error} err
   * @returns {void}
   */
  destroy(err) {
    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
    Array.from(this.entries.values()).forEach((e) => { this.entries.delete(e.id); if (e.reject) { e.reject(err); } });
    this.bytes = 0;
  }
}
