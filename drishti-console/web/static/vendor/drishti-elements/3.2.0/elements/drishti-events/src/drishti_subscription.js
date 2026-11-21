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
/* A subscription: a pattern heard in some scopes, with its state, its statistics and its helpers. Every message passes the same small
   path: heard once (a message that two scopes bring is delivered once), the where filter, the kind's check, the latest value kept, the
   flow (conflation, the buffer, delivery now or per frame) and finally the listeners: the function given, feeds (subscribe(fn): the data,
   so a grid's connectLive and liveSource take a subscription as a feed), handlers by kind, and async iterators. */
import { EventError, AbortError, makeMessage, isMessage, isOwnTopic, matches } from './drishti_envelope.js';
import { createFlow, GridSink } from './drishti_flow.js';

/** A subscription's states. */
export const SUBSCRIPTION_STATES = Object.freeze(['pending', 'active', 'recovering', 'refused', 'closed']);

/**
 * A subscription to a pattern.
 */
export class Subscription {
  /**
   * @param {Object} events the Events it belongs to
   * @param {string} pattern
   * @param {?function(Object): void} fn
   * @param {Object} o its options (SubscribeOptions)
   */
  constructor(events, pattern, fn, o) {
    this.events = events;
    /** @type {string} */ this.pattern = pattern;
    this.fn = fn || null;
    this.o = o;
    /** @type {string} pending, active, recovering, refused, closed */
    this.state = 'pending';
    /** @type {{received: number, delivered: number, dropped: number, drops: Object<string, number>, lastAt: number}} */
    this.stats = { received: 0, delivered: 0, dropped: 0, drops: {}, lastAt: 0 };
    /** @type {?string} why it was refused (in words) */ this.reason = null;
    /** @private */ this._fns = fn ? [fn] : [];
    /** @private */ this._latest = new Map();
    /** @private */ this._last = null;
    /** @private */ this._offs = [];
    /** @private */ this._scopes = [];
    /** @private ids heard lately (a message two scopes bring is delivered once) */ this._seen = new Map();
    /** @private */ this._iters = new Set();
    let ok, ko;
    /** @type {Promise<Subscription>} resolves when it is active (a server accepted it), rejects when refused */
    this.ready = new Promise((a, b) => { ok = a; ko = b; });
    this.ready.catch(() => undefined);
    /** @private */ this._ok = ok;
    /** @private */ this._ko = ko;
    /** @private */ this._flow = createFlow(this, o, events);
    if (o.signal) {
      if (o.signal.aborted) { this.state = 'closed'; } else { o.signal.addEventListener('abort', () => this.off(), { once: true }); }
    }
  }

  /**
   * The scopes it hears now.
   * @type {string[]}
   */
  get scopes() { return this._scopes.slice(); }

  /**
   * Listens in the scopes its options or the routes give (again, when the routes or the scopes change).
   * @returns {void}
   * @private
   */
  _listen() {
    if (this.state === 'closed') { return; }
    const ev = this.events, was = this._scopes;
    this._offs.forEach((f) => f());
    this._offs = [];
    if (isOwnTopic(this.pattern)) {
      this._scopes = [];
      this._offs.push(ev._own.on(this.pattern, (m) => this._accept(m)));
      if (matches(this.pattern, '$events.metrics') && ev._tickMetrics) { ev._tickMetrics(); }
      this._setState('active');
      return;
    }
    const scopes = this.o.scope ? [this.o.scope] : ev.scopesOf(this.pattern);
    this._scopes = scopes;
    if (scopes.indexOf('page') >= 0) {
      this._offs.push(ev._bus.on(this.pattern, (payload, type) => this._accept(isMessage(payload) ? payload : this._wrap(payload, type))));
    }
    if (scopes.some((s) => s !== 'page')) {
      this._offs.push(ev._remote.on(this.pattern, (m) => { if (this._scopes.indexOf(m.scope) >= 0) { this._accept(m); } }));
    }
    let waiting = false;
    const held = this._held || (this._held = {});
    ['tabs', 'server'].forEach((s) => {
      const a = ev._adapter(s), has = scopes.indexOf(s) >= 0 && !!a && !!a.subscribe, old = held[s];
      if (old && (old !== a || scopes.indexOf(s) < 0)) { delete held[s]; if (old.unsubscribe) { old.unsubscribe(this); } }
      if (has && !held[s]) { held[s] = a; if (a.subscribe(this) === true) { waiting = true; } }
      else if (has && this.state === 'pending' && a.pending && a.pending(this)) { waiting = true; }
    });
    if (scopes.indexOf('server') >= 0 && !ev._adapter('server') && this.o.scope === 'server') {
      this._setState('refused', 'this page has no server: connect(url) gives it one');
      return;
    }
    if (!waiting && this.state === 'pending') { this._setState('active'); }
  }

  /** @private a payload some other code put on the page's bus, as a message */
  _wrap(payload, type) {
    const ev = this.events, now = ev.clock.now();
    return makeMessage({ id: ev.ids.next(), topic: type, data: payload === undefined ? null : payload, time: now, source: 'page', scope: 'page', receivedAt: now });
  }

  /**
   * A subscription's state changed (the server accepted, refused, or it is recovering): published on $events.subscription.
   * @param {string} state
   * @param {string} [reason]
   * @returns {void}
   * @private
   */
  _setState(state, reason) {
    if (this.state === state || (this.state === 'closed' && state !== 'closed')) { return; }
    const was = this.state;
    this.state = state;
    if (reason) { this.reason = reason; }
    if (state === 'active') { this._ok(this); }
    if (state === 'refused') { this._ko(new EventError('refused', 'the subscription to ' + this.pattern + ' was refused: ' + (reason || 'no reason given'))); }
    if (state === 'closed') { this._ko(new AbortError('the subscription to ' + this.pattern + ' was closed before it was ready')); }
    if (!isOwnTopic(this.pattern)) {
      this.events._emitOwn('$events.subscription', { pattern: this.pattern, state: state, was: was, reason: reason || null });
    }
  }

  /**
   * A message heard: once only, filtered, checked, kept as the latest, then into the flow.
   * @param {Object} m a message
   * @returns {void}
   * @private
   */
  _accept(m) {
    if (this.state === 'closed') { return; }
    const ev = this.events;
    if (m.id && this._scopes.length > 1) {
      if (this._seen.has(m.id)) { ev._drop('duplicate', m, this, 'heard from a second scope'); return; }
      this._seen.set(m.id, true);
      if (this._seen.size > 256) { this._seen.delete(this._seen.keys().next().value); }
    }
    this.stats.received++;
    this.stats.lastAt = m.receivedAt || ev.clock.now();
    if (this.o.where) {
      let keep = false;
      try { keep = !!this.o.where(m); } catch (e) { ev._error(e, { topic: m.topic, where: 'where', pattern: this.pattern }); }
      if (!keep) { ev._drop('filtered', m, this, 'the subscription\'s where said no'); return; }
    }
    if (ev.kinds.size || this.o.validate) {
      const bad = ev.kinds.check(m.kind, m.data, m, this.o.validate);
      if (bad !== true) {
        if (this.o.onInvalid !== 'deliver') { ev._drop('invalid', m, this, 'Dropped as invalid: ' + bad); return; }
        m = makeMessage(Object.assign({}, m, { invalid: bad }));
      }
    }
    this._last = m;
    if (m.key !== undefined && m.key !== null) {
      this._latest.delete(m.key);
      this._latest.set(m.key, m);
      if (this._latest.size > ev.limits.undelivered) { this._latest.delete(this._latest.keys().next().value); }
    }
    this._flow.push(m);
  }

  /**
   * Hands a message (or, batched, a list) to every listener. The flow calls this.
   * @param {(Object|Object[])} item
   * @returns {void}
   * @private
   */
  _deliver(item) {
    if (this.state === 'closed') { return; }
    const ev = this.events, n = Array.isArray(item) ? item.length : 1;
    this.stats.delivered += n;
    ev.counters.delivered += n;
    const t0 = now();
    this._fns.slice().forEach((f) => {
      try { f(item); } catch (e) { ev._error(e, { topic: Array.isArray(item) ? this.pattern : item.topic, where: 'listener', pattern: this.pattern }); }
    });
    if (this._iters.size) { (Array.isArray(item) ? item : [item]).forEach((m) => this._iters.forEach((it) => it.push(m))); }
    if (ev._delivered) { ev._delivered(this, item, this._fns.length + this._iters.size, now() - t0); }
  }

  /**
   * The latest message (of a key, when given; a message's key is what a later one replaces).
   * @param {string} [key]
   * @returns {?Object}
   */
  latest(key) { return key === undefined ? this._last : (this._latest.get(String(key)) || null); }

  /**
   * Every latest message by key, oldest first.
   * @returns {Object[]}
   */
  latestAll() { return Array.from(this._latest.values()); }

  /**
   * A feed: fn hears each message's data (and the message), so drishti-grid's connectLive and drishti-data's liveSource take a
   * subscription as they take any feed.
   * @param {function(*, Object): void} fn
   * @returns {function(): void} stops it
   */
  subscribe(fn) { return this._add((item) => (Array.isArray(item) ? item : [item]).forEach((m) => fn(m.data, m))); }

  /**
   * Handlers by the message's kind ({ rows: fn, resync: fn, ... }; '*' hears the kinds no handler names): each hears the data and the
   * message. With it a subscription is a live-set channel: { subscribe: (h) => subscription.byKind(h) }.
   * @param {Object<string, function(*, Object): void>} handlers
   * @returns {function(): void} stops them
   */
  byKind(handlers) {
    return this._add((item) => (Array.isArray(item) ? item : [item]).forEach((m) => {
      const f = handlers[m.kind] || handlers['*'];
      if (f) { f(m.data, m); }
    }));
  }

  /**
   * Feeds a grid with backpressure: the flow ends in the grid's applyTransactionAsync with at most one batch in flight, everything that
   * arrives meanwhile folded by row id (each message's data: a transaction, every row, or a data-changed notice).
   * @param {Object} grid a drishti-grid Grid or GridModel (anything with applyTransactionAsync)
   * @param {import('./drishti_flow.js').GridSinkOptions} [o] getRowId, maxInFlight, highWater, onStats
   * @returns {{sink: GridSink, stop: function(): void}} stop: the grid hears no more
   */
  toGrid(grid, o) {
    const sink = new GridSink(grid, o);
    const stop = this.subscribe((data) => sink.push(data));
    return { sink: sink, stop: stop };
  }

  /**
   * How many messages wait in the flow (conflated, held for a frame or a tick).
   * @type {number}
   */
  get undelivered() { return this._flow.size; }

  /** @private */
  _add(f) {
    this._fns.push(f);
    return () => { const i = this._fns.indexOf(f); if (i >= 0) { this._fns.splice(i, 1); } };
  }

  /**
   * Messages as an async iterator (ends when the subscription does; at most the buffer's max wait, the oldest dropped first).
   * @returns {AsyncIterator<Object>}
   */
  [Symbol.asyncIterator]() {
    const q = [], waits = [], max = (this.o.buffer && this.o.buffer.max) || this.events.limits.undelivered;
    let ended = this.state === 'closed';
    const it = {
      push: (m) => {
        if (waits.length) { waits.shift()({ value: m, done: false }); return; }
        q.push(m);
        if (q.length > max) { const old = q.shift(); this.events._drop('overflow', old, this, 'the iterator holds ' + max + ' messages at most'); }
      },
      end: () => { ended = true; while (waits.length) { waits.shift()({ value: undefined, done: true }); } }
    };
    this._iters.add(it);
    const self = this;
    return {
      next() {
        if (q.length) { return Promise.resolve({ value: q.shift(), done: false }); }
        if (ended) { return Promise.resolve({ value: undefined, done: true }); }
        return new Promise((r) => waits.push(r));
      },
      return() { self._iters.delete(it); it.end(); if (!self._fns.length && !self._iters.size) { self.off(); } return Promise.resolve({ value: undefined, done: true }); },
      [Symbol.asyncIterator]() { return this; }
    };
  }

  /**
   * Ends it: the listeners are stopped, a server is told, iterators end.
   * @returns {void}
   */
  off() {
    if (this.state === 'closed' && !this._offs.length) { return; }
    this._offs.forEach((f) => f());
    this._offs = [];
    if (this._held) { Object.keys(this._held).forEach((s) => { const a = this._held[s]; if (a.unsubscribe) { a.unsubscribe(this); } }); this._held = null; }
    this._flow.stop();
    this._setState('closed');
    this._iters.forEach((it) => it.end());
    this._iters.clear();
    this._fns = [];
    this.events._forget(this);
  }
}

/** @private a fine clock for the listeners' time (performance.now where there is one) */
function now() { return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now(); }
