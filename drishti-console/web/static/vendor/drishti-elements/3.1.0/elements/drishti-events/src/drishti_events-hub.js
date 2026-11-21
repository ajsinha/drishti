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
/* Events: one object that routes. Every event is a message on a topic, carried in one or more scopes: 'page' (drishti-core's shared bus,
   the very object; there is no second bus in a page), 'tabs' (the person's other tabs) and 'server'. Which topics go where is
   configuration (routes, the most specific pattern wins), so a component publishes or listens without knowing who is on the other side.

   A scope other than the page is an adapter the hub is given (the connection gives 'server', the tab channel gives 'tabs'): it sends
   what the hub publishes and hands what it receives to receive(); the hub delivers each message to every subscription that matches it,
   once, from whichever scope brought it. The library's own events are messages too, on '$events.*' topics of the Events' own bus. */
import { bus as pageBus, EventBus, loaded, loadPart } from '../../drishti-core/src/drishti_index.js';
import { EventError, TimeoutError, AbortError, makeMessage, isMessage, IdMaker, checkTopic, checkPattern, isOwnTopic, matches,
  patternsOverlap, specificity, Kinds } from './drishti_envelope.js';
import { systemClock } from './drishti_timing.js';
import { Subscription } from './drishti_subscription.js';
import { TabsScope, broadcastChannelOf } from './drishti_tabs.js';
import { Histogram, Rate } from './drishti_metrics.js';
import { Recorder, explainWhy } from './drishti_trace.js';

/** The scopes a message travels in. */
export const SCOPES = Object.freeze(['page', 'tabs', 'server']);

/** The library's own topics. */
export const OWN_TOPICS = Object.freeze(['$events.state', '$events.error', '$events.gap', '$events.drop', '$events.subscription',
  '$events.auth', '$events.metrics']);

/** The ceilings of every store, and their defaults (section 3.8 of the design); `limits` changes any of them. */
export const DEFAULT_LIMITS = Object.freeze({ undelivered: 10000, heldPerGap: 5000, streams: 5000, ids: 2048, outboxMessages: 1000,
  outboxBytes: 1048576, outboxAgeMs: 86400000, recorder: 5000, pendingRequests: 1000, frameBytes: 1048576 });

/** The repliers of every Events in this page (a request in the page scope is answered by any of them). */
const PAGE_REPLIERS = [];

/**
 * What a publish answers once it is delivered.
 * @typedef {Object} Receipt
 * @property {string} id the message's id
 * @property {string} topic
 * @property {number} time when it was published
 * @property {string[]} to the scopes it went to
 * @property {boolean} acked a server acknowledged it (always true for the page and the tabs once sent)
 * @property {string[]} [unrouted] scopes routed to that this Events does not have (counted as dropped: unrouted)
 */

/**
 * The options of a subscription (section 2.3 of the design).
 * @typedef {Object} SubscribeOptions
 * @property {('page'|'tabs'|'server')} [scope] hear only this scope (default: every scope the routes give)
 * @property {AbortSignal} [signal] ends it
 * @property {function(Message<*>): boolean} [where] false drops the message (recorded as filtered)
 * @property {function(*, Message<*>): (boolean|string)} [validate] one more check of the data (true, or words)
 * @property {('drop'|'deliver')} [onInvalid='drop'] a message that fails its check: dropped, or delivered marked invalid
 * @property {({key: (string|function(Message<*>): string), merge: function(Message<*>, Message<*>): Message<*>}|boolean)} [conflate]
 *   keep only the latest message per key until delivery (key: a field of the message, or of its data, or a function; default the
 *   message's key); merge combines an older and a newer one
 * @property {('now'|'frame'|{everyMs: number})} [deliver='now'] when to deliver: at once, once per animation frame, at most this often
 * @property {boolean} [batch=false] the listener receives an array per delivery
 * @property {{max: number, overflow: ('conflate'|'drop-oldest'|'drop-newest'|'error')}} [buffer] the most messages held undelivered
 *   (default 10,000, overflow conflate)
 * @property {boolean} [retained=false] ask the server for the last value of each matching topic first
 * @property {('now'|'resume')} [from='resume'] after a reconnect: nothing missed, or what was missed
 * @property {('replay'|'resync'|'ignore')} [onGap='replay'] what a gap in a stream does
 * @property {number} [staleMs] how long silence is normal
 */

/**
 * The options of a publish.
 * @typedef {Object} PublishOptions
 * @property {(string|string[])} [to] the scope or scopes (default: the routes)
 * @property {string} [key] what a later message replaces
 * @property {string} [kind] the name of the data's shape
 * @property {Object<string, string>} [meta] headers
 * @property {boolean} [ack] wait for a server's acknowledgement (default true to a server)
 * @property {('memory'|'persist'|boolean)} [queue='memory'] while not connected: held in memory, kept on disk, or false: fail at once
 * @property {number} [ttlMs] dropped from the queue when older
 * @property {AbortSignal} [signal]
 */

/**
 * The options of a request.
 * @typedef {Object} RequestOptions
 * @property {number} [timeoutMs=10000]
 * @property {AbortSignal} [signal] aborting tells the server (cancel)
 * @property {(string|string[])} [to] the scope (default: the routes)
 * @property {string} [kind]
 * @property {Object<string, string>} [meta]
 */

/**
 * The options of an Events.
 * @typedef {Object} EventsOptions
 * @property {Object<string, (string|string[])>} [routes] pattern to scope or scopes ('ui.>': 'page', '>': 'server'); the most specific
 *   pattern wins; a topic no route matches takes the default (with a server: 'ui.>' the page, everything else the server; without one:
 *   the page)
 * @property {Clock} [clock] every timer, time and random number (testClock() in tests)
 * @property {EventBus} [bus] the page's bus (default drishti-core's shared bus)
 * @property {string} [source='page'] the source named on this page's messages
 * @property {Object<string, number>} [limits] the ceilings (DEFAULT_LIMITS)
 * @property {AbortSignal} [signal] ends the whole Events (a component's life)
 * @property {string} [namespace] differs per application and signed-in person: the tabs' channel, the shared hub and the queue's
 *   database carry it, so a second person on the same origin shares nothing
 * @property {boolean} [tabs=true] false: no 'tabs' scope (it is there wherever the browser has a BroadcastChannel)
 * @property {Object} [env] stand-ins for the browser's classes (BroadcastChannel, SharedWorker, locks, WebSocket, ...) in tests
 * @property {function(Error, Object): void} [onError] also told of every error published on $events.error
 * @property {(import('./drishti_trace.js').RecorderOptions|boolean)} [recorder] the recorder behind explain and the inspector (false: off)
 * @property {function(Object): void} [onMetrics] given events.metrics() once a second (a host's own telemetry)
 */

/**
 * Events in the page, across tabs and with a server: publish and subscribe, request and reply, streams.
 */
export class Events {
  /**
   * @param {EventsOptions} [o]
   */
  constructor(o) {
    this.o = Object.assign({}, o);
    /** @type {Clock} */ this.clock = this.o.clock || systemClock();
    /** @type {Object<string, number>} */ this.limits = Object.assign({}, DEFAULT_LIMITS, this.o.limits);
    this.ids = new IdMaker(this.clock);
    this.kinds = new Kinds();
    this.source = this.o.source || 'page';
    /** @private */ this._bus = this.o.bus || pageBus;
    /** @private the messages of the other scopes, by topic */ this._remote = new EventBus({ onError: (e, t) => this._error(e, { topic: t, where: 'listener' }) });
    /** @private the library's own topics */ this._own = new EventBus({ onError: (e, t) => this._ownFailed(e, t) });
    /** @private */ this._adapters = new Map();
    /** @private */ this._routes = this.o.routes ? toRoutes(this.o.routes) : null;
    /** @private */ this._shared = [];
    /** @private */ this._subs = new Set();
    /** @private */ this._repliers = [];
    /** @private ids this page published to more than one scope: a server's echo is not delivered twice */ this._mine = new Map();
    /** @private */ this._pending = new Map();
    /** @private */ this._destroyed = false;
    /** @type {Object} counters the metrics read */
    this.counters = { published: 0, delivered: 0, requests: 0, answered: 0, timedOut: 0, drops: {} };
    /** @type {Recorder} the last messages' headers and what became of them (explain, the inspector) */
    this.recorder = new Recorder(this.clock, this.o.recorder === false ? { max: 0 } : Object.assign({ max: this.limits.recorder }, this.o.recorder));
    /** @private */ this._hist = { latency: new Histogram(), listener: new Histogram(), ack: new Histogram() };
    /** @private */ this._rateIn = new Rate(this.clock);
    /** @private */ this._byPattern = new Map();
    /** @private */ this._slowest = { pattern: null, ms: 0 };
    /** @private */ this._metricsTimer = null;
    this.page = new ScopedEvents(this, 'page');
    this.tabs = new ScopedEvents(this, 'tabs');
    this.server = new ScopedEvents(this, 'server');
    if (this.o.tabs !== false && broadcastChannelOf(this.o.env)) { this._attach('tabs', new TabsScope(this, { namespace: this.o.namespace, env: this.o.env })); }
    if (this.o.signal) { onAbort(this.o.signal, () => this.destroy()); }
    if (this.o.onMetrics) { this._tickMetrics(); }
  }

  // ---- routes --------------------------------------------------------------------------------------------------------------------

  /** @private the routes in force, most specific first */
  _candidates() {
    const own = (this._routes || []).concat(this._shared);
    return own.sort((a, b) => specificity(b.pattern) - specificity(a.pattern));
  }

  /** @private the default routes */
  _defaults() {
    return this._adapters.has('server') ? [{ pattern: 'ui.>', to: ['page'] }, { pattern: '>', to: ['server'] }] : [{ pattern: '>', to: ['page'] }];
  }

  /**
   * The scopes a topic is published to (the most specific route that matches it).
   * @param {string} topic
   * @returns {string[]}
   */
  routeOf(topic) {
    const hit = this._candidates().find((r) => matches(r.pattern, topic)) || this._defaults().find((r) => matches(r.pattern, topic));
    return hit ? hit.to.slice() : ['page'];
  }

  /**
   * The scopes a pattern is heard from: every scope a route that some topic of the pattern can take names.
   * @param {string} pattern
   * @returns {string[]}
   */
  scopesOf(pattern) {
    if (isOwnTopic(pattern)) { return []; }
    const out = new Set();
    const c = this._candidates().filter((r) => patternsOverlap(r.pattern, pattern));
    c.forEach((r) => r.to.forEach((s) => out.add(s)));
    const covered = c.some((r) => covers(r.pattern, pattern));
    if (!covered) { this._defaults().filter((r) => patternsOverlap(r.pattern, pattern)).forEach((r) => r.to.forEach((s) => out.add(s))); }
    return SCOPES.filter((s) => out.has(s));
  }

  /**
   * Routes a pattern to a scope as well ('ui.selection.>' to 'tabs': selections reach the other tabs and still the page).
   * @param {string} pattern
   * @param {{to: (string|string[])}} o
   * @returns {function(): void} takes the route away
   */
  share(pattern, o) {
    checkPattern(pattern);
    const to = scopeList(o && o.to);
    const base = this.scopesOfCovering(pattern);
    const route = { pattern: pattern, to: SCOPES.filter((s) => base.indexOf(s) >= 0 || to.indexOf(s) >= 0) };
    this._shared.push(route);
    this._rescope();
    return () => { const i = this._shared.indexOf(route); if (i >= 0) { this._shared.splice(i, 1); this._rescope(); } };
  }

  /** @private the scopes of the most specific route that takes every topic of the pattern */
  scopesOfCovering(pattern) {
    const hit = this._candidates().find((r) => covers(r.pattern, pattern)) || this._defaults().find((r) => covers(r.pattern, pattern));
    return hit ? hit.to.slice() : this.scopesOf(pattern);
  }

  /** @private routes changed: every subscription that follows the routes listens again */
  _rescope() { this._subs.forEach((s) => { if (!s.o.scope) { s._listen(); } }); }

  // ---- scopes (adapters) ---------------------------------------------------------------------------------------------------------

  /**
   * Gives the Events a scope (the connection gives 'server', the tab channel 'tabs'). An adapter has publish(message, options),
   * request(message, options), subscribe(subscription), unsubscribe(subscription) and close().
   * @param {string} name
   * @param {Object} adapter
   * @returns {function(): void} takes it away
   * @private
   */
  _attach(name, adapter) {
    this._adapters.set(name, adapter);
    const tabs = this._adapters.get('tabs');
    if (name === 'server' && tabs && tabs._open) { tabs._open(); }           // a sign-out in another tab must reach this one's server scope
    this._rescope();
    return () => { if (this._adapters.get(name) === adapter) { this._adapters.delete(name); this._rescope(); } };
  }

  /**
   * The adapter of a scope, when this Events has it.
   * @param {string} name
   * @returns {?Object}
   * @private
   */
  _adapter(name) { return this._adapters.get(name) || null; }

  /**
   * A message from another scope (an adapter calls this): delivered to every subscription of that scope that matches it.
   * @param {Message<*>} m with scope set
   * @returns {number} how many subscriptions heard it
   * @private
   */
  _receive(m) {
    if (this._destroyed) { return 0; }
    if (!isMessage(m)) { m = makeMessage(m); }
    if (m.receivedAt === undefined) { m.receivedAt = this.clock.now(); }
    this._rateIn.add();
    this.recorder.record(m, 'in');
    if (m.id && this._mine.has(m.id) && m.scope !== 'page') { this._drop('duplicate', m, null, 'this page published it to the page as well'); return 0; }
    const n = this._remote.emit(m.topic, m);
    if (!n) { this._drop('unrouted', m, null, 'no subscription matches ' + m.topic); }
    return n;
  }

  // ---- subscribe -----------------------------------------------------------------------------------------------------------------

  /**
   * Hears every message matching the pattern (as EventBus.on).
   * @param {string} pattern 'prices.*.USDCAD', 'prices.>', '$events.state'
   * @param {function(Message<*>): void} fn
   * @param {SubscribeOptions} [options]
   * @returns {function(): void} stops it
   */
  on(pattern, fn, options) {
    if (typeof fn !== 'function') { throw new TypeError('events.on: a listener is a function'); }
    const s = this.subscribe(pattern, fn, options);
    return () => s.off();
  }

  /**
   * The same as on, as an object with state, statistics and helpers (a feed for a grid, an async iterator, latest values).
   * @param {string} pattern
   * @param {function(Message<*>): void} [fn]
   * @param {SubscribeOptions} [options]
   * @returns {Subscription}
   */
  subscribe(pattern, fn, options) {
    this._alive();
    checkPattern(pattern);
    if (options && options.scope && SCOPES.indexOf(options.scope) < 0) { throw new EventError('scope.invalid', 'a scope is page, tabs or server; got ' + options.scope); }
    const s = new Subscription(this, pattern, fn, options || {});
    this._subs.add(s);
    s._listen();
    return s;
  }

  /**
   * The next message matching the pattern.
   * @param {string} pattern
   * @param {{timeoutMs: number, signal: AbortSignal, where: function(Message<*>): boolean, scope: string}} [options]
   * @returns {Promise<Message<*>>}
   */
  once(pattern, options) {
    const o = options || {};
    return new Promise((resolve, reject) => {
      let timer = null, s = null;
      const end = () => { if (timer !== null) { this.clock.clearTimeout(timer); } if (s) { s.off(); } };
      s = this.subscribe(pattern, (m) => { end(); resolve(m); }, { scope: o.scope, where: o.where });
      if (o.timeoutMs) { timer = this.clock.setTimeout(() => { timer = null; end(); reject(new TimeoutError('nothing on ' + pattern + ' within ' + o.timeoutMs + ' ms')); }, o.timeoutMs); }
      if (o.signal) { onAbort(o.signal, () => { end(); reject(new AbortError('the wait for ' + pattern + ' was given up')); }); }
    });
  }

  /**
   * Stops one listener, every listener of a pattern, or (with nothing) every listener.
   * @param {string} [pattern]
   * @param {function(Message<*>): void} [fn]
   * @returns {void}
   */
  off(pattern, fn) {
    Array.from(this._subs).forEach((s) => {
      if (pattern !== undefined && pattern !== null && s.pattern !== pattern) { return; }
      if (fn && s.fn !== fn) { return; }
      s.off();
    });
  }

  /**
   * Messages as an async iterator: for await (const m of events.stream('prices.>', { signal })).
   * @param {string} pattern
   * @param {SubscribeOptions} [options]
   * @returns {AsyncIterable<Message<*>>}
   */
  stream(pattern, options) {
    const s = this.subscribe(pattern, null, options);
    return { [Symbol.asyncIterator]: () => s[Symbol.asyncIterator]() };
  }

  /** @private a subscription ended */
  _forget(s) { this._subs.delete(s); }

  // ---- publish -------------------------------------------------------------------------------------------------------------------

  /**
   * Publishes; resolves when delivered (the page: at once; a server: when it acknowledges).
   * @param {string} topic
   * @param {*} data any JSON value
   * @param {PublishOptions} [options]
   * @returns {Promise<Receipt>}
   */
  publish(topic, data, options) {
    try {
      this._alive();
      const o = options || {};
      checkTopic(topic);
      const m = this._message(topic, data, o);
      const bad = this.kinds.check(m.kind, data, m, o.validate);
      if (bad !== true) { throw new EventError('invalid', 'the data of ' + topic + (m.kind ? ' (kind ' + m.kind + ')' : '') + ' is not valid: ' + bad); }
      const to = o.to ? scopeList(o.to) : this.routeOf(topic);
      if (!to.length) { throw new EventError('scope.invalid', 'a publish needs a scope: page, tabs or server'); }
      if (o.signal && o.signal.aborted) { throw new AbortError('the publish to ' + topic + ' was given up before it was sent'); }
      this.counters.published++;
      this.recorder.record(m, 'out', to.join('+'));
      if (to.length > 1) { this._remember(m.id); }
      const unrouted = [], waits = [];
      to.forEach((scope) => {
        if (scope === 'page') { this._publishPage(m); return; }
        const a = this._adapters.get(scope);
        if (!a) { unrouted.push(scope); this._drop('unrouted', m, null, 'this page has no ' + scope + ' scope'); return; }
        waits.push(a.publish(m, o));
      });
      if (unrouted.length === to.length) {
        throw new EventError('unrouted', topic + ' is routed to ' + to.join(' and ') + ', which this page does not have' +
          (to.indexOf('server') >= 0 ? ' (connect(url) gives it a server)' : ''));
      }
      return Promise.all(waits).then((rs) => {
        if (waits.length) { this._hist.ack.add(Math.max(0, this.clock.now() - m.time)); }
        const r = { id: m.id, topic: topic, time: m.time, to: to.filter((s) => unrouted.indexOf(s) < 0), acked: rs.every((x) => !x || x.acked !== false) };
        if (unrouted.length) { r.unrouted = unrouted; }
        return r;
      });
    } catch (e) { return Promise.reject(e); }
  }

  /** @private a new message of this page */
  _message(topic, data, o) {
    return makeMessage({ id: this.ids.next(), topic: topic, data: data === undefined ? null : data, time: this.clock.now(), source: this.source,
      key: o.key != null ? String(o.key) : undefined, kind: o.kind, meta: o.meta });
  }

  /** @private the page's bus is the page scope */
  _publishPage(m) {
    const copy = makeMessage(Object.assign({}, m, { scope: 'page', receivedAt: this.clock.now() }));
    this.recorder.record(copy, 'in');
    this._bus.emit(m.topic, copy);
  }

  /** @private */
  _remember(id) {
    this._mine.set(id, true);
    if (this._mine.size > this.limits.ids) { this._mine.delete(this._mine.keys().next().value); }
  }

  // ---- request and reply ---------------------------------------------------------------------------------------------------------

  /**
   * Sends and waits for the one answer; rejects with a TimeoutError after timeoutMs (default 10 s).
   * @param {string} topic
   * @param {*} data
   * @param {RequestOptions} [options]
   * @returns {Promise<Message<*>>}
   */
  request(topic, data, options) {
    try {
      this._alive();
      const o = Object.assign({ timeoutMs: 10000 }, options);
      checkTopic(topic);
      const m = this._message(topic, data, o);
      const bad = this.kinds.check(m.kind, data, m);
      if (bad !== true) { throw new EventError('invalid', 'the data of the request ' + topic + ' is not valid: ' + bad); }
      if (this._pending.size >= this.limits.pendingRequests) { throw new EventError('limit.requests', this._pending.size + ' requests are waiting already (the limit)'); }
      const to = o.to ? scopeList(o.to) : this.routeOf(topic);
      const scope = ['server', 'tabs', 'page'].find((s) => to.indexOf(s) >= 0 && (s === 'page' || this._adapters.has(s)));
      if (!scope) { throw new EventError('unrouted', 'the request ' + topic + ' is routed to ' + to.join(' and ') + ', which this page does not have'); }
      this.counters.requests++;
      return this._wait(m, o, scope === 'page' ? (signal) => this._askPage(m, signal) : (signal) => this._adapters.get(scope).request(m, Object.assign({}, o, { signal: signal })));
    } catch (e) { return Promise.reject(e); }
  }

  /** @private a request's wait: the timeout, the signal, the pending count */
  _wait(m, o, ask) {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    return new Promise((resolve, reject) => {
      let done = false, timer = null;
      const finish = (f, v) => {
        if (done) { return; }
        done = true;
        this._pending.delete(m.id);
        if (timer !== null) { this.clock.clearTimeout(timer); }
        f(v);
      };
      this._pending.set(m.id, () => { if (ctrl) { ctrl.abort(); } finish(reject, new AbortError('the Events was closed')); });
      timer = this.clock.setTimeout(() => {
        timer = null;
        this.counters.timedOut++;
        if (ctrl) { ctrl.abort(); }
        finish(reject, new TimeoutError('no answer to ' + m.topic + ' within ' + o.timeoutMs + ' ms'));
      }, o.timeoutMs);
      if (o.signal) { onAbort(o.signal, () => { if (ctrl) { ctrl.abort(); } finish(reject, new AbortError('the request ' + m.topic + ' was given up')); }); }
      Promise.resolve().then(() => ask(ctrl ? ctrl.signal : undefined)).then((answer) => { this.counters.answered++; finish(resolve, answer); },
        (e) => finish(reject, e));
    });
  }

  /** @private a request in the page: the first replier whose pattern matches answers */
  _askPage(m, signal) {
    const r = PAGE_REPLIERS.find((x) => matches(x.pattern, m.topic));
    if (!r) { return Promise.reject(new EventError('no.responder', 'nothing in this page replies to ' + m.topic)); }
    const req = makeMessage(Object.assign({}, m, { scope: 'page', receivedAt: this.clock.now(), signal: signal }));
    return Promise.resolve().then(() => r.fn(req)).then((data) => makeMessage({ id: this.ids.next(), topic: m.topic, data: data === undefined ? null : data,
      time: this.clock.now(), source: r.events.source, re: m.id, scope: 'page', receivedAt: this.clock.now() }), (e) => {
      throw e instanceof EventError ? e : new EventError((e && e.code) || 'reply.failed', (e && e.message) || String(e));
    });
  }

  /**
   * Answers requests on a topic or pattern in the page and the tabs (what the function answers is the reply; what it throws is the
   * requester's rejection).
   * @param {string} topic
   * @param {function(Message<*>): (*|Promise<*>)} fn
   * @returns {function(): void} stops answering
   */
  reply(topic, fn) {
    this._alive();
    checkPattern(topic);
    if (typeof fn !== 'function') { throw new TypeError('events.reply: the replier is a function'); }
    const r = { pattern: topic, fn: fn, events: this };
    PAGE_REPLIERS.push(r);
    this._repliers.push(r);
    const tabs = this._adapters.get('tabs');
    const offTabs = tabs && tabs.reply ? tabs.reply(topic, fn) : null;
    return () => {
      [PAGE_REPLIERS, this._repliers].forEach((l) => { const i = l.indexOf(r); if (i >= 0) { l.splice(i, 1); } });
      if (offTabs) { offTabs(); }
    };
  }

  // ---- typed topics and kinds ----------------------------------------------------------------------------------------------------

  /**
   * A typed handle on a topic or pattern: ticks.on(fn), ticks.publish(data), ticks.request(data).
   * @param {string} name
   * @param {{kind: string, validate: function(*, Message<*>): (boolean|string), scope: string}} [o]
   * @returns {Topic}
   */
  topic(name, o) {
    checkPattern(name);
    const opts = Object.assign({}, o);
    if (opts.kind && opts.validate) { this.define(opts.kind, opts.validate); }
    return new Topic(this, name, opts);
  }

  /**
   * Registers the check of a kind: a message of that kind whose data fails it is dropped on the way in (recorded as invalid) and refused
   * on the way out.
   * @param {string} kind
   * @param {function(*, Message<*>): (boolean|string)} validate true, or words
   * @returns {void}
   */
  define(kind, validate) { this.kinds.define(kind, validate); }

  // ---- the library's own events --------------------------------------------------------------------------------------------------

  /**
   * Publishes one of the library's own events ('$events.state', ...) to whoever listens.
   * @param {string} topic
   * @param {Object} data
   * @returns {number} how many heard it
   * @private
   */
  _emitOwn(topic, data) {
    const now = this.clock.now();
    if (topic === '$events.state') { this.recorder.state(data); }
    else if (topic === '$events.gap') { this.recorder.note(Object.assign({ type: 'gap' }, data)); }
    else if (topic === '$events.subscription') { this.recorder.note(Object.assign({ type: 'subscription' }, data)); }
    return this._own.emit(topic, makeMessage({ id: this.ids.next(), topic: topic, data: data, time: now, source: this.source, scope: 'page', receivedAt: now }));
  }

  /**
   * An error: published on $events.error (and given to onError).
   * @param {Error} e
   * @param {Object} [about] topic, where, subscription
   * @returns {void}
   * @private
   */
  _error(e, about) {
    const d = Object.assign({ error: e, code: (e && e.code) || 'error', detail: (e && (e.detail || e.message)) || String(e) }, about);
    this.recorder.note({ type: 'error', topic: d.topic || null, where: d.where || null, detail: d.detail, pattern: d.pattern || null });
    if (this.o.onError) { try { this.o.onError(e, d); } catch (x) { /* the host's own */ } }
    if (!this._emitOwn('$events.error', d) && !this.o.onError && typeof console !== 'undefined') {
      console.error('drishti-events: ' + d.detail, e);
    }
  }

  /** @private a listener of the library's own events failed: told on the console, never in a loop */
  _ownFailed(e, t) { if (typeof console !== 'undefined') { console.error('drishti-events: a listener of ' + t + ' failed', e); } }

  /**
   * A message dropped, with its reason (counted; published on $events.drop when someone listens).
   * @param {string} reason duplicate, conflated, overflow, invalid, filtered, too-large, expired, unrouted, gap
   * @param {?Message<*>} m
   * @param {?Subscription} sub
   * @param {string} [words]
   * @returns {void}
   * @private
   */
  _drop(reason, m, sub, words) {
    this.counters.drops[reason] = (this.counters.drops[reason] || 0) + 1;
    this.recorder.dropped(m, reason, words, sub && sub.pattern);
    if (sub) { sub.stats.dropped++; sub.stats.drops[reason] = (sub.stats.drops[reason] || 0) + 1; }
    if (reason !== 'conflated' && this._own.count('$events.drop') + this._own.count('$events.>') > 0) {
      this._emitOwn('$events.drop', { reason: reason, id: m && m.id, topic: m && m.topic, seq: m && m.seq, pattern: sub && sub.pattern, detail: words || reason });
    }
  }

  // ---- the connection and the queue (given by connect) ---------------------------------------------------------------------------

  /**
   * The connection's state: idle (no server, or not yet asked), connecting, open, reconnecting, offline, refused, closed.
   * @type {string}
   */
  get state() { const c = this._adapters.get('server'); return c && c.state ? c.state : (this._destroyed ? 'closed' : 'idle'); }

  /**
   * Opens the connection again now (a person pressed Reconnect).
   * @returns {void}
   */
  reconnect() { const c = this._adapters.get('server'); if (c && c.reconnect) { c.reconnect(); } }

  /**
   * Closes the connection and stops trying (subscriptions stay, and are sent again by reconnect()).
   * @returns {void}
   */
  close() { const c = this._adapters.get('server'); if (c && c.close) { c.close(); } }

  /**
   * Sends what the queue holds now (it is sent on its own when the connection opens).
   * @returns {Promise<number>} how many were sent
   */
  flush() { const c = this._adapters.get('server'); return c && c.flush ? c.flush() : Promise.resolve(0); }

  /**
   * Empties the queue: what waits is not sent.
   * @returns {Promise<number>} how many were dropped
   */
  clearOutbox() { const c = this._adapters.get('server'); return c && c.clearOutbox ? c.clearOutbox() : Promise.resolve(0); }

  /**
   * The person signs out: the queue is emptied, the positions in streams are forgotten and the other tabs are told to do the same.
   * @returns {Promise<void>}
   */
  signOut() {
    const work = [];
    this._adapters.forEach((a) => { if (a.signOut) { work.push(a.signOut()); } });
    return Promise.all(work).then(() => undefined);
  }

  /**
   * Another tab said the person signed out: every scope but that one forgets (the queue, the stream positions).
   * @param {string} from the scope that told
   * @returns {Promise<void>}
   * @private
   */
  _signOutHere(from) {
    const work = [];
    this._adapters.forEach((a, name) => { if (name !== from && a.signOut) { work.push(a.signOut()); } });
    this._emitOwn('$events.auth', { reason: 'signed-out', ok: false, from: from });
    return Promise.all(work).then(() => undefined);
  }

  /**
   * The figures so far (one plain object).
   * @returns {Object}
   */
  metrics() {
    const out = { state: this.state, subscriptions: this._subs.size, pendingRequests: this._pending.size, counters: JSON.parse(JSON.stringify(this.counters)) };
    this._adapters.forEach((a, name) => { if (a.metrics) { out[name] = a.metrics(); } });
    if (out.server && out.server.outbox) { out.outbox = out.server.outbox; }
    const srv = out.server || {}, o = srv.out || {};
    let undelivered = 0;
    this._subs.forEach((sub) => { undelivered += sub._flow && typeof sub._flow.size === 'number' ? sub._flow.size : 0; });
    const byPattern = {};
    this._byPattern.forEach((n, p) => { byPattern[p] = n; });
    out.connection = { state: this.state, transport: srv.transport || null, shared: !!srv.shared, connects: srv.connects || 0, reconnects: srv.reconnects || 0,
      connectedMs: srv.connectedMs || 0, rtt: srv.rtt || { last: null, p50: null, p99: null }, offset: srv.offset === undefined ? null : srv.offset };
    out.in = Object.assign(this._rateIn.summary(), { bytes: srv.bytesIn || 0, byPattern: byPattern, latency: this._hist.latency.summary(),
      latencyApproximate: srv.offset == null || this._hist.latency.n < 3,
      listenerMs: Object.assign(this._hist.listener.summary(), { slowest: this._slowest.pattern }) });
    out.out = { publishes: this.counters.published, acked: o.acked || 0, retried: o.retried || 0, failed: o.failed || 0, ackMs: this._hist.ack.summary(),
      requests: { sent: this.counters.requests, answered: this.counters.answered, timedOut: this.counters.timedOut } };
    out.drops = Object.assign({}, this.counters.drops);
    out.stores = { undelivered: undelivered, heldForGaps: srv.held || 0, outbox: out.outbox || null, pendingRequests: this._pending.size, recorder: this.recorder.metrics() };
    return out;
  }

  /**
   * Opens the events inspector (its own file, loaded now with drishti-grid): into an element (a dock's panel, a page's place), or a drawer
   * at the page's side. A page of ES modules imports src/drishti_inspector.js and calls openInspector(events, el) itself.
   * @param {HTMLElement} [el]
   * @param {{refreshMs: number}} [o]
   * @returns {Promise<Object>} the Inspector
   */
  inspect(el, o) {
    const have = loaded('drishti-events:inspector');
    return (have ? Promise.resolve(have) : loadPart('drishti-events', 'inspector')).then((api) => api.openInspector(this, el, o));
  }

  /**
   * Why a message did or did not reach this page's listeners, in words: the steps (a listener matched, the route, the subscription,
   * the connection, arrival, validity, kept, the listener ran), the first that failed marked. Draw it with drishti-core's renderExplain.
   * @param {{topic: string, key: string, id: string, since: number}} query
   * @returns {Object} an ExplainResult (title, value, steps with ok, failed)
   */
  explain(query) { return explainWhy(this, query || {}); }

  /** @private a subscription delivered: the figures of the metrics and the recorder */
  _delivered(sub, item, listeners, ms) {
    const now = this.clock.now(), list = Array.isArray(item) ? item : [item];
    list.forEach((m) => {
      this.recorder.delivered(m, sub.pattern, listeners);
      if (typeof m.time === 'number' && m.scope !== 'page') { this._hist.latency.add(Math.max(0, now - m.time)); }
    });
    this._hist.listener.add(ms);
    if (ms > this._slowest.ms) { this._slowest = { pattern: sub.pattern, ms: ms }; }
    if (this._byPattern.size < 1000 || this._byPattern.has(sub.pattern)) { this._byPattern.set(sub.pattern, (this._byPattern.get(sub.pattern) || 0) + list.length); }
  }

  /** @private once a second while someone listens to $events.metrics (or onMetrics is given) */
  _tickMetrics() {
    if (this._metricsTimer !== null || this._destroyed) { return; }
    this._metricsTimer = this.clock.setTimeout(() => {
      this._metricsTimer = null;
      if (this._destroyed) { return; }
      const heard = this._own.count('$events.metrics') + this._own.count('$events.>') > 0;
      if (!heard && !this.o.onMetrics) { return; }
      const m = this.metrics();
      if (this.o.onMetrics) { try { this.o.onMetrics(m); } catch (e) { this._ownFailed(e, 'onMetrics'); } }
      if (heard) { this._emitOwn('$events.metrics', m); }
      this._tickMetrics();
    }, 1000);
  }
  /**
   * Closes the connection, ends every subscription and request, stops answering: the Events is done.
   * @returns {void}
   */
  destroy() {
    if (this._destroyed) { return; }
    if (this._metricsTimer !== null) { this.clock.clearTimeout(this._metricsTimer); this._metricsTimer = null; }
    Array.from(this._subs).forEach((s) => s.off());
    Array.from(this._pending.values()).forEach((f) => f());
    this._repliers.slice().forEach((r) => { const i = PAGE_REPLIERS.indexOf(r); if (i >= 0) { PAGE_REPLIERS.splice(i, 1); } });
    this._repliers = [];
    this._adapters.forEach((a) => { if (a.destroy) { a.destroy(); } else if (a.close) { a.close(); } });
    this._destroyed = true;
    this._adapters.clear();
    this._remote.clear();
    this._own.clear();
  }

  /** @private */
  _alive() { if (this._destroyed) { throw new EventError('closed', 'this Events was destroyed'); } }
}

/**
 * The same calls pinned to one scope, whatever the routes say (events.page, events.tabs, events.server).
 */
export class ScopedEvents {
  /**
   * @param {Events} events
   * @param {string} scope
   */
  constructor(events, scope) { this.events = events; /** @type {string} */ this.scope = scope; }

  /**
   * As Events.on, in this scope.
   * @param {string} pattern
   * @param {function(Message<*>): void} fn
   * @param {SubscribeOptions} [options]
   * @returns {function(): void}
   */
  on(pattern, fn, options) { return this.events.on(pattern, fn, this._sub(options)); }

  /**
   * As Events.subscribe, in this scope.
   * @param {string} pattern
   * @param {function(Message<*>): void} [fn]
   * @param {SubscribeOptions} [options]
   * @returns {Subscription}
   */
  subscribe(pattern, fn, options) { return this.events.subscribe(pattern, fn, this._sub(options)); }

  /**
   * As Events.once, in this scope.
   * @param {string} pattern
   * @param {Object} [options]
   * @returns {Promise<Message<*>>}
   */
  once(pattern, options) { return this.events.once(pattern, this._sub(options)); }

  /**
   * As Events.off.
   * @param {string} [pattern]
   * @param {Function} [fn]
   * @returns {void}
   */
  off(pattern, fn) { this.events.off(pattern, fn); }

  /**
   * As Events.publish, to this scope.
   * @param {string} topic
   * @param {*} data
   * @param {PublishOptions} [options]
   * @returns {Promise<Receipt>}
   */
  publish(topic, data, options) { return this.events.publish(topic, data, Object.assign({}, options, { to: this.scope })); }

  /**
   * As Events.request, in this scope.
   * @param {string} topic
   * @param {*} data
   * @param {RequestOptions} [options]
   * @returns {Promise<Message<*>>}
   */
  request(topic, data, options) { return this.events.request(topic, data, Object.assign({}, options, { to: this.scope })); }

  /**
   * As Events.reply.
   * @param {string} topic
   * @param {function(Message<*>): *} fn
   * @returns {function(): void}
   */
  reply(topic, fn) { return this.events.reply(topic, fn); }

  /**
   * As Events.stream, in this scope.
   * @param {string} pattern
   * @param {SubscribeOptions} [options]
   * @returns {AsyncIterable<Message<*>>}
   */
  stream(pattern, options) { return this.events.stream(pattern, this._sub(options)); }

  /**
   * As Events.topic, in this scope.
   * @param {string} name
   * @param {Object} [o]
   * @returns {Topic}
   */
  topic(name, o) { return this.events.topic(name, Object.assign({}, o, { scope: this.scope })); }

  /** @private */
  _sub(options) { return Object.assign({}, options, { scope: this.scope }); }
}

/**
 * A typed handle on a topic (or a pattern: publishing then names the topic in options.topic).
 * @template T
 */
export class Topic {
  /**
   * @param {Events} events
   * @param {string} name
   * @param {{kind: string, validate: Function, scope: string}} o
   */
  constructor(events, name, o) { this.events = events; /** @type {string} */ this.name = name; this.o = o; }

  /** @private */
  _sub(options) {
    const x = Object.assign({}, options);
    if (this.o.scope && !x.scope) { x.scope = this.o.scope; }
    if (this.o.validate && !this.o.kind && !x.validate) { x.validate = this.o.validate; }
    return x;
  }

  /** @private */
  _pub(options) {
    const x = Object.assign({}, options);
    if (this.o.kind && !x.kind) { x.kind = this.o.kind; }
    if (this.o.scope && !x.to) { x.to = this.o.scope; }
    if (this.o.validate && !this.o.kind) { x.validate = this.o.validate; }
    return x;
  }

  /** @private the concrete topic of a publish */
  _topic(options) {
    if (options && options.topic) {
      if (!matches(this.name, options.topic)) { throw new EventError('topic.invalid', options.topic + ' is not a topic of ' + this.name); }
      return options.topic;
    }
    return this.name;
  }

  /**
   * Hears this topic's messages.
   * @param {function(Message<T>): void} fn
   * @param {SubscribeOptions} [options]
   * @returns {function(): void}
   */
  on(fn, options) { return this.events.on(this.name, fn, this._sub(options)); }

  /**
   * A subscription to this topic.
   * @param {function(Message<T>): void} [fn]
   * @param {SubscribeOptions} [options]
   * @returns {Subscription}
   */
  subscribe(fn, options) { return this.events.subscribe(this.name, fn, this._sub(options)); }

  /**
   * The next message.
   * @param {Object} [options]
   * @returns {Promise<Message<T>>}
   */
  once(options) { return this.events.once(this.name, this._sub(options)); }

  /**
   * Publishes data of this topic's kind (options.topic names the topic when the handle is a pattern).
   * @param {T} data
   * @param {PublishOptions} [options]
   * @returns {Promise<Receipt>}
   */
  publish(data, options) {
    try { return this.events.publish(this._topic(options), data, this._pub(options)); } catch (e) { return Promise.reject(e); }
  }

  /**
   * Asks on this topic.
   * @param {T} data
   * @param {RequestOptions} [options]
   * @returns {Promise<Message<*>>}
   */
  request(data, options) {
    try { return this.events.request(this._topic(options), data, this._pub(options)); } catch (e) { return Promise.reject(e); }
  }

  /**
   * This topic's messages as an async iterator.
   * @param {SubscribeOptions} [options]
   * @returns {AsyncIterable<Message<T>>}
   */
  stream(options) { return this.events.stream(this.name, this._sub(options)); }
}

/**
 * A new Events with no server: the page and (when given) the tabs.
 * @param {EventsOptions} [options]
 * @returns {Events}
 */
export function createEvents(options) { return new Events(options); }

// ---- helpers ---------------------------------------------------------------------------------------------------------------------------

function toRoutes(spec) {
  if (!spec || typeof spec !== 'object') { throw new EventError('route.invalid', 'routes are an object of pattern to scope'); }
  return Object.keys(spec).map((p) => { checkPattern(p); return { pattern: p, to: scopeList(spec[p]) }; });
}

function scopeList(to) {
  const l = Array.isArray(to) ? to : [to];
  l.forEach((s) => { if (SCOPES.indexOf(s) < 0) { throw new EventError('scope.invalid', 'a scope is page, tabs or server; got ' + s); } });
  return SCOPES.filter((s) => l.indexOf(s) >= 0);
}

/**
 * Says whether every topic of the specific pattern is a topic of the general one.
 * @param {string} general
 * @param {string} specific
 * @returns {boolean}
 */
export function covers(general, specific) {
  const g = general.split('.'), s = specific.split('.');
  for (let i = 0; i < g.length; i++) {
    if (g[i] === '>') { return s.length > i; }
    if (i >= s.length) { return false; }
    if (s[i] === '>') { return false; }
    if (g[i] !== '*' && g[i] !== s[i]) { return false; }
  }
  return g.length === s.length;
}

/**
 * Runs fn when the signal fires (at once when it has already).
 * @param {AbortSignal} signal
 * @param {function(): void} fn
 * @returns {void}
 */
export function onAbort(signal, fn) {
  if (!signal) { return; }
  if (signal.aborted) { fn(); return; }
  signal.addEventListener('abort', fn, { once: true });
}
