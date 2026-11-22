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
/* The mock server and MockTabs: drishti-events' part for tests and demos (drishti_events-mock.js beside the bundle; not in it).

   MockServer speaks the wire protocol, version 1, through an in-memory transport, so the client under test is the real client:
   connect(server) or connect('mock:') (a server registers its address). It numbers streams, retains last values, keeps a replay log,
   acknowledges and deduplicates publishes, answers requests, and misbehaves on purpose: drop, partition, lose, duplicate, delay, reorder,
   refuse, restart (a new epoch), slow (a gap). It is the protocol's reference: the conformance vectors (tests/events/protocol-v1.json)
   pass against it.

   MockTabs: several Events in one page (or one Node process) joined as if they were tabs: an in-memory BroadcastChannel, Web Locks and
   a SharedWorker for each "tab", so the tabs scope, the leader tab and the shared hub run (and are tested) without a browser. A tab
   can be closed (its channels close, its locks are released: the next tab leads). Delivery is asynchronous, as a browser's is: on the
   clock when one is given (clock.tick), else on the next microtask. */
import { serveSharedWorker, registerMock, matches, encodeFrames, systemClock, PROTOCOL_VERSION, EventError } from './drishti_index.js';

/**
 * Tabs in one page.
 */
export class MockTabs {
  /**
   * @param {{clock: Object, worker: boolean}} [o] clock: delivery on its timers; worker: false leaves the SharedWorker out (the leader
   *   tab is then the hub)
   */
  constructor(o) {
    this.o = Object.assign({ worker: true }, o);
    /** @private */ this.channels = new Map();
    /** @private */ this.locks = new Map();
    /** @private */ this.workers = new Map();
    /** @type {number} how many frames crossed between tabs */
    this.posted = 0;
  }

  /** @private */
  _later(fn) {
    if (this.o.clock) { this.o.clock.setTimeout(fn, 0); } else { Promise.resolve().then(fn); }
  }

  /**
   * The environment of one more tab: give it to createEvents or connect as env.
   * @param {Object} [extra] more of the environment (a WebSocket stand-in, ...)
   * @returns {Object} env, with close() to close the tab
   */
  tab(extra) {
    const mock = this;
    const tab = { closed: false, channels: new Set(), ports: new Set() };
    class Channel {
      constructor(name) {
        this.name = String(name);
        this.onmessage = null;
        this.closed = false;
        this.tab = tab;
        tab.channels.add(this);
        if (!mock.channels.has(this.name)) { mock.channels.set(this.name, new Set()); }
        mock.channels.get(this.name).add(this);
      }
      postMessage(data) {
        if (this.closed) { throw new Error('InvalidStateError: the channel is closed'); }
        const copy = clone(data);
        mock.channels.get(this.name).forEach((c) => {
          if (c === this) { return; }
          mock.posted++;
          mock._later(() => { if (!c.closed && c.onmessage) { c.onmessage({ data: clone(copy) }); } });
        });
      }
      close() { this.closed = true; const set = mock.channels.get(this.name); if (set) { set.delete(this); } tab.channels.delete(this); }
    }
    const locks = { request: (name, a, b) => mock._lock(tab, String(name), typeof a === 'function' ? a : b) };
    const env = Object.assign({ BroadcastChannel: Channel, locks: locks, window: {} }, extra);
    if (this.o.worker) {
      env.SharedWorker = function SharedWorker(url, opts) {
        const name = (opts && opts.name) || String(url);
        const w = mock._worker(name);
        const pair = ports(mock);
        tab.ports.add(pair[0]);
        mock._later(() => w.scope.onconnect({ ports: [pair[1]] }));
        this.port = pair[0];
      };
    }
    env.close = () => mock.close(env);
    env._tab = tab;
    return env;
  }

  /** @private one in-memory SharedWorker per name */
  _worker(name) {
    let w = this.workers.get(name);
    if (!w) {
      const scope = { onconnect: null };
      w = { scope: scope, served: serveSharedWorker(scope, { clock: this.o.clock, env: this.o.workerEnv || {} }) };
      this.workers.set(name, w);
    }
    return w;
  }

  /**
   * The hub a mock SharedWorker holds (tests look inside), or null.
   * @param {string} [name] default the first worker
   * @returns {?Object}
   */
  workerHost(name) {
    const w = name ? this.workers.get(name) : this.workers.values().next().value;
    return w ? w.served.host() : null;
  }

  /** @private Web Locks: one holder per name, the rest wait in order */
  _lock(tab, name, fn) {
    let l = this.locks.get(name);
    if (!l) { l = { holder: null, queue: [] }; this.locks.set(name, l); }
    return new Promise((resolve, reject) => {
      l.queue.push({ tab: tab, fn: fn, resolve: resolve, reject: reject });
      this._grant(name);
    });
  }

  /** @private */
  _grant(name) {
    const l = this.locks.get(name);
    if (!l || l.holder) { return; }
    while (l.queue.length && l.queue[0].tab.closed) { l.queue.shift().reject(new Error('AbortError: the tab closed')); }
    const next = l.queue.shift();
    if (!next) { return; }
    l.holder = next;
    let out;
    try { out = Promise.resolve(next.fn({ name: name, mode: 'exclusive' })); } catch (e) { out = Promise.reject(e); }
    const free = () => { if (l.holder === next) { l.holder = null; this._later(() => this._grant(name)); } };
    next.free = free;
    out.then((v) => { free(); next.resolve(v); }, (e) => { free(); next.reject(e); });
  }

  /**
   * Closes a tab: its channels and ports close, its locks are released (the next tab waiting takes them).
   * @param {Object} env a tab's environment
   * @returns {void}
   */
  close(env) {
    const tab = env._tab;
    if (!tab || tab.closed) { return; }
    tab.closed = true;
    tab.channels.forEach((c) => c.close());
    tab.ports.forEach((p) => p.close());
    this.locks.forEach((l, name) => {
      if (l.holder && l.holder.tab === tab) { l.holder.free(); }
      l.queue = l.queue.filter((q) => q.tab !== tab);
      this._grant(name);
    });
  }
}

/** @private a pair of joined message ports */
function ports(mock) {
  const make = () => ({ onmessage: null, other: null, closed: false,
    postMessage(data) {
      if (this.closed || !this.other) { return; }
      const to = this.other, copy = clone(data);
      mock.posted++;
      mock._later(() => { if (!to.closed && to.onmessage) { to.onmessage({ data: copy }); } });
    },
    start() {},
    close() { this.closed = true; } });
  const a = make(), b = make();
  a.other = b; b.other = a;
  return [a, b];
}

/** @private a structured clone of plain data (what a browser's channel copies) */
function clone(v) { return v === undefined ? undefined : (typeof structuredClone === 'function' ? structuredClone(v) : JSON.parse(JSON.stringify(v))); }

/** What a MockServer offers in its welcome. */
export const MOCK_CAN = Object.freeze(['replay', 'retained', 'conflate', 'request', 'publish', 'batch']);

/**
 * The options of a MockServer.
 * @typedef {Object} MockServerOptions
 * @property {Object} [clock] its timers (testClock in tests; default the system's)
 * @property {string} [address='mock:'] connect(address) finds it; null: not registered
 * @property {number} [heartbeatMs=15000] said in welcome
 * @property {number} [logSize=10000] the messages kept per stream for replay
 * @property {number} [dedupeMs=120000] how long a publish's id is remembered
 * @property {function(?string): (boolean|string)} [auth] checks hello's token: true, or why not (closed with 4401)
 * @property {string[]} [can] what welcome offers (default MOCK_CAN)
 * @property {string} [name='mock'] the source named on its messages
 */

/**
 * An in-page server that speaks the events protocol, version 1, for tests and demos.
 */
export class MockServer {
  /**
   * @param {MockServerOptions} [o]
   */
  constructor(o) {
    this.o = Object.assign({ address: 'mock:', heartbeatMs: 15000, logSize: 10000, dedupeMs: 120000, can: MOCK_CAN, name: 'mock' }, o);
    this.clock = this.o.clock || systemClock();
    /** @type {string} the numbering's epoch (a restart starts a new one) */
    this.epoch = 'e1';
    this.generation = 1;
    this.streams = new Map();
    this.retainedValues = new Map();
    this.conns = new Set();
    this.repliers = [];
    this.seenIds = new Map();
    this.sessions = new Set();
    this.n = 0;
    this.faults = { lose: 0, duplicate: 0, delayMs: 0, reorder: 0, held: [], refuse: null, partitionUntil: 0, slow: 0 };
    this.timers = new Set();
    /** @type {Object} what the server counted */
    this.stats = { connects: 0, framesIn: 0, framesOut: 0, published: 0, duplicates: 0, requests: 0, lost: 0 };
    if (this.o.address) { registerMock(this.o.address, this); }
  }

  /**
   * The transport the client's connection uses (connect(server) calls it for every attempt).
   * @param {Object} ctx the connection's transport context (onOpen, onText, onClose)
   * @returns {{kind: string, send: function(Object[]): Promise<void>, close: function(number): void, bufferedAmount: function(): number}}
   */
  transport(ctx) {
    const c = { ctx: ctx, subs: new Map(), open: false, closed: false, session: null, hello: false, reqs: new Map() };
    this.stats.connects++;
    const refuse = this.faults.refuse;
    this._later(() => {
      if (c.closed) { return; }
      if (this.clock.now() < this.faults.partitionUntil) { this._close(c, 1006, 'the network is partitioned'); return; }
      if (refuse === 'forbidden') { this._close(c, 4403, 'forbidden'); return; }
      this.conns.add(c);
      c.open = true;
      ctx.onOpen();
    });
    return {
      kind: 'websocket',
      send: (frames) => {
        if (c.closed) { return Promise.reject(new EventError('not.connected', 'the mock connection is closed')); }
        const text = encodeFrames(frames);
        this._later(() => { if (!c.closed) { this._in(c, text); } }, this.faults.delayMs);
        return Promise.resolve();
      },
      close: () => { if (!c.closed) { c.closed = true; this.conns.delete(c); this._endReqs(c); } },
      bufferedAmount: () => 0
    };
  }

  /** @private delivery: on the next microtask, or after ms on the clock */
  _later(fn, ms) {
    if (ms) { const id = this.clock.setTimeout(() => { this.timers.delete(id); fn(); }, ms); this.timers.add(id); } else { Promise.resolve().then(fn); }
  }

  /** @private frames to one connection (as one transport message) */
  _send(c, frames) {
    if (c.closed || !frames.length) { return; }
    const text = encodeFrames(frames);
    this.stats.framesOut += frames.length;
    this._later(() => { if (!c.closed) { c.ctx.onText(text, { event: 'message', id: null }); } }, this.faults.delayMs);
  }

  /** @private closes a connection with a code (the client sees the transport end) */
  _close(c, code, reason) {
    if (c.closed) { return; }
    c.closed = true;
    this.conns.delete(c);
    this._endReqs(c);
    this._later(() => c.ctx.onClose({ code: code, status: 0, reason: reason }));
  }

  /** @private */
  _endReqs(c) { c.reqs.forEach((ctl) => ctl.abort()); c.reqs.clear(); }

  /** @private one transport message from a client */
  _in(c, text) {
    if (text.length > 1048576) { this._close(c, 4400, 'a frame over 1 MB'); return; }
    let list;
    try { list = JSON.parse(text); } catch (e) { this._close(c, 4400, 'not JSON'); return; }
    list = Array.isArray(list) ? list : [list];
    for (const f of list) {
      this.stats.framesIn++;
      if (!f || typeof f !== 'object' || typeof f.t !== 'string') { this._close(c, 4400, 'not a frame'); return; }
      if (!c.hello && f.t !== 'hello') { this._close(c, 4401, 'hello first'); return; }
      this._frame(c, f);
      if (c.closed) { return; }
    }
  }

  /** @private */
  _frame(c, f) {
    switch (f.t) {
      case 'hello': this._hello(c, f); break;
      case 'sub': this._sub(c, f); break;
      case 'unsub': c.subs.delete(String(f.id)); break;
      case 'pub': this._pub(c, f); break;
      case 'req': this._req(c, f); break;
      case 'cancel': { const ctl = c.reqs.get(String(f.id)); if (ctl) { c.reqs.delete(String(f.id)); ctl.abort(); } break; }
      case 'replay': this._replay(c, f); break;
      case 'auth': this._send(c, [{ t: 'auth', expiresAt: this.clock.now() + 3600000 }]); break;
      case 'ping': this._send(c, [{ t: 'pong', ts: f.ts, time: this.clock.now() }]); break;
      case 'bye': c.closed = true; this.conns.delete(c); break;
      default: break;                                              // unknown types are ignored (version 1's rule)
    }
  }

  /** @private */
  _hello(c, f) {
    if (f.v !== PROTOCOL_VERSION) { this._send(c, [{ t: 'bye', code: 'version', detail: 'this server speaks version 1', reconnect: false }]); this._close(c, 1000, 'version'); return; }
    if (this.faults.refuse === 'auth') { this._close(c, 4401, 'not authenticated'); return; }
    if (this.o.auth) {
      const ok = this.o.auth(typeof f.token === 'string' ? f.token : null);
      if (ok !== true) { this._close(c, 4401, typeof ok === 'string' ? ok : 'not authenticated'); return; }
    }
    c.hello = true;
    const resumed = typeof f.session === 'string' && this.sessions.has(f.session);
    c.session = resumed ? f.session : 's-' + this.generation + '-' + (++this.n).toString(36);
    this.sessions.add(c.session);
    this._send(c, [{ t: 'welcome', v: PROTOCOL_VERSION, session: c.session, time: this.clock.now(), heartbeatMs: this.o.heartbeatMs,
      can: this.o.can.slice(), limits: { frameBytes: 1048576, subs: 1000 }, resumed: resumed }]);
  }

  /** @private a stream's state */
  _stream(name) {
    let s = this.streams.get(name);
    if (!s) { s = { seq: 0, log: [] }; this.streams.set(name, s); }
    return s;
  }

  /** @private */
  _sub(c, f) {
    const id = String(f.id);
    if (typeof f.topic !== 'string' || !/^[^\s]+$/.test(f.topic)) { this._send(c, [{ t: 'err', id: f.id, code: 'topic.invalid', detail: 'not a pattern' }]); return; }
    if (this.faults.forbid && matches(this.faults.forbid, f.topic.replace(/\.>$/, '.x').replace(/\*/g, 'x'))) {
      this._send(c, [{ t: 'err', id: f.id, code: 'forbidden', detail: 'not for this client' }]); return;
    }
    c.subs.set(id, { pattern: f.topic, retained: !!f.retained });
    const streams = {}, out = [];
    let recovered = true;
    this.streams.forEach((s, name) => { if (this._streamMatches(name, f.topic)) { streams[name] = { epoch: this.epoch, seq: s.seq }; } });
    if (f.after && typeof f.after === 'object') {
      Object.keys(f.after).forEach((name) => {
        const a = f.after[name], s = this.streams.get(name);
        if (!s || !a || (a.epoch != null && a.epoch !== this.epoch)) { recovered = false; return; }      // no epoch heard: the stream began in this one
        const first = s.log.length ? s.log[0].seq : s.seq + 1;
        if (a.seq + 1 < first) { recovered = false; return; }
        s.log.forEach((m) => { if (m.seq > a.seq) { out.push(Object.assign({}, m, { replayed: true })); } });
      });
    }
    const frames = [{ t: 'subd', id: f.id, streams: streams, recovered: recovered }];
    if (f.retained) { this.retainedValues.forEach((m) => { if (matches(f.topic, m.topic)) { frames.push(Object.assign({}, m, { retained: true })); } }); }
    this._send(c, frames.concat(out));
  }

  /** @private a stream belongs to a pattern when a topic of that name matches (a stream is a topic by default) */
  _streamMatches(name, pattern) { try { return matches(pattern, name); } catch (e) { return false; } }

  /** @private */
  _pub(c, f) {
    this._forgetOld();
    const id = String(f.id);
    if (this.seenIds.has(id)) { this.stats.duplicates++; if (f.ack !== false) { this._send(c, [{ t: 'ok', id: f.id }]); } return; }
    if (this.faults.readOnly) { this._send(c, [{ t: 'err', id: f.id, code: 'forbidden', detail: 'this server is read only', retry: false }]); return; }
    this.seenIds.set(id, this.clock.now());
    this.publish(f.topic, f.data, { id: id, key: f.key, kind: f.kind, meta: f.meta, source: 'client', time: f.time });
    if (f.ack !== false) { this._send(c, [{ t: 'ok', id: f.id }]); }
  }

  /** @private publish ids older than the window are forgotten */
  _forgetOld() {
    const old = this.clock.now() - this.o.dedupeMs;
    for (const [id, at] of this.seenIds) { if (at >= old) { break; } this.seenIds.delete(id); }
  }

  /** @private */
  _req(c, f) {
    this.stats.requests++;
    const r = this.repliers.find((x) => matches(x.pattern, f.topic));
    if (!r) { this._send(c, [{ t: 'res', re: f.id, error: { code: 'no.responder', detail: 'nothing on this server answers ' + f.topic } }]); return; }
    const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (ctl) { c.reqs.set(String(f.id), ctl); }
    Promise.resolve().then(() => r.fn({ topic: f.topic, data: f.data === undefined ? null : f.data, id: f.id, signal: ctl ? ctl.signal : undefined })).then((data) => {
      if (ctl && ctl.signal.aborted) { return; }
      c.reqs.delete(String(f.id));
      this._send(c, [{ t: 'res', re: f.id, data: data === undefined ? null : data, time: this.clock.now(), source: this.o.name }]);
    }, (e) => {
      c.reqs.delete(String(f.id));
      this._send(c, [{ t: 'res', re: f.id, error: { code: (e && e.code) || 'internal', detail: (e && (e.detail || e.message)) || String(e) } }]);
    });
  }

  /** @private */
  _replay(c, f) {
    const s = this.streams.get(String(f.stream));
    const from = Number(f.from), to = Number(f.to);
    if (!s || (f.epoch != null && f.epoch !== this.epoch) || !s.log.length || s.log[0].seq > from) {
      this._send(c, [{ t: 'gap', stream: f.stream, from: from, to: to, recoverable: false }]);
      return;
    }
    this._send(c, s.log.filter((m) => m.seq >= from && m.seq <= to).map((m) => Object.assign({}, m, { replayed: true })));
  }

  // ---- what a test or a demo does --------------------------------------------------------------------------------------------------

  /**
   * Publishes a message to every connection subscribed to a matching pattern (numbered in its stream, retained, logged).
   * @param {string} topic
   * @param {*} data
   * @param {{key: string, kind: string, stream: string, meta: Object, id: string, source: string, retain: boolean}} [o]
   * @returns {Object} the msg frame
   */
  publish(topic, data, o) {
    const opts = Object.assign({}, o);
    const name = opts.stream || topic, s = this._stream(name);
    const m = { t: 'msg', id: opts.id || 'm-' + this.generation + '-' + (++this.n).toString(36), topic: topic, stream: name, seq: ++s.seq,
      data: data === undefined ? null : data, time: typeof opts.time === 'number' ? opts.time : this.clock.now(), source: opts.source || this.o.name };
    if (opts.key != null) { m.key = String(opts.key); }
    if (opts.kind) { m.kind = String(opts.kind); }
    if (opts.meta) { m.meta = opts.meta; }
    s.log.push(m);
    if (s.log.length > this.o.logSize) { s.log.shift(); }
    if (opts.retain !== false) { this.retainedValues.set(topic + (m.key !== undefined ? '\u0000' + m.key : ''), m); }
    this.stats.published++;
    this._fanOut(m);
    return m;
  }

  /** @private to every connection whose subscriptions match (once each), through the faults */
  _fanOut(m) {
    if (this.faults.lose > 0) { this.faults.lose--; this.stats.lost++; return; }
    if (this.faults.slow > 0) {                                     // dropped for a slow client: logged, not sent; then a gap says so
      this.faults.slow--;
      if (!this.faults.slowFrom) { this.faults.slowFrom = m.seq; }
      if (!this.faults.slow) {
        const gap = { t: 'gap', stream: m.stream, from: this.faults.slowFrom, to: m.seq, recoverable: true };
        this.faults.slowFrom = 0;
        this.conns.forEach((c) => { if (c.hello && this._wants(c, m.topic)) { this._send(c, [gap]); } });
      }
      return;
    }
    if (this.faults.reorder > 0) {
      this.faults.held.push(m);
      if (this.faults.held.length < this.faults.reorder) { return; }
      const list = this.faults.held.reverse();
      this.faults.held = [];
      this.faults.reorder = 0;
      list.forEach((x) => this._toWanting(x));
      return;
    }
    this._toWanting(m);
    if (this.faults.duplicate > 0) { this.faults.duplicate--; this._toWanting(m); }
  }

  /** @private */
  _toWanting(m) { this.conns.forEach((c) => { if (c.hello && this._wants(c, m.topic)) { this._send(c, [m]); } }); }

  /** @private */
  _wants(c, topic) { for (const s of c.subs.values()) { if (matches(s.pattern, topic)) { return true; } } return false; }

  /**
   * Answers requests on a topic or pattern.
   * @param {string} pattern
   * @param {function({topic: string, data: *, id: string, signal: AbortSignal}): *} fn what it returns is the answer; what it throws, the error
   * @returns {function(): void} stops answering
   */
  reply(pattern, fn) {
    const r = { pattern: pattern, fn: fn };
    this.repliers.push(r);
    return () => { const i = this.repliers.indexOf(r); if (i >= 0) { this.repliers.splice(i, 1); } };
  }

  /**
   * Runs fn every ms on the server's clock (a ticker).
   * @param {number} ms
   * @param {function(): void} fn
   * @returns {function(): void} stops it
   */
  every(ms, fn) {
    let id = null, stopped = false;
    const tick = () => { this.timers.delete(id); if (stopped) { return; } fn(); id = this.clock.setTimeout(tick, ms); this.timers.add(id); };
    id = this.clock.setTimeout(tick, ms);
    this.timers.add(id);
    return () => { stopped = true; this.clock.clearTimeout(id); this.timers.delete(id); };
  }

  /**
   * How many clients are connected now.
   * @returns {number}
   */
  get connections() { return this.conns.size; }

  // ---- misbehaving on purpose ------------------------------------------------------------------------------------------------------

  /**
   * Drops every connection (as a network would: close 1006).
   * @returns {void}
   */
  drop() { Array.from(this.conns).forEach((c) => this._close(c, 1006, 'the mock dropped the connection')); }

  /**
   * Drops every connection and refuses new ones for ms.
   * @param {number} ms
   * @returns {void}
   */
  partition(ms) { this.faults.partitionUntil = this.clock.now() + ms; this.drop(); }

  /**
   * The next n messages published are not sent (their numbers are used: the clients see a gap).
   * @param {number} n
   * @returns {void}
   */
  lose(n) { this.faults.lose += n; }

  /**
   * The next n messages are sent twice.
   * @param {number} n
   * @returns {void}
   */
  duplicate(n) { this.faults.duplicate += n; }

  /**
   * Every frame, both ways, takes ms (0 to stop).
   * @param {number} ms
   * @returns {void}
   */
  delay(ms) { this.faults.delayMs = Math.max(0, Number(ms) || 0); }

  /**
   * The next n messages are held and sent in reverse order.
   * @param {number} n
   * @returns {void}
   */
  reorder(n) { this.faults.reorder = Math.max(2, n); this.faults.held = []; }

  /**
   * Refuses connections: 'auth' (close 4401: the client asks auth once more), 'forbidden' (4403: it stops), or null to accept again;
   * a pattern for 'subscribe' refuses subscriptions it covers; 'publish' makes the server read only.
   * @param {(?string)} what 'auth', 'forbidden', 'publish', null
   * @param {string} [pattern] with 'subscribe': which patterns
   * @returns {void}
   */
  refuse(what, pattern) {
    if (what === 'subscribe') { this.faults.forbid = pattern || '>'; return; }
    if (what === 'publish') { this.faults.readOnly = true; return; }
    if (what === null) { this.faults.refuse = null; this.faults.forbid = null; this.faults.readOnly = false; return; }
    this.faults.refuse = what;
    if (what === 'auth') { Array.from(this.conns).forEach((c) => this._close(c, 4401, 'not authenticated')); }
  }

  /**
   * Restarts: a new epoch (the numbering starts again), the log, the retained values and the sessions forgotten, every connection dropped.
   * @returns {void}
   */
  restart() {
    this.generation++;
    this.epoch = 'e' + this.generation;
    this.streams.clear();
    this.retainedValues.clear();
    this.sessions.clear();
    this.seenIds.clear();
    Array.from(this.conns).forEach((c) => { this._send(c, [{ t: 'bye', code: 'restart', detail: 'the server restarts', reconnect: { afterMs: 0 } }]); this._close(c, 1012, 'restart'); });
  }

  /**
   * The next n messages are dropped for every client as a slow client's would be; then a gap frame says so.
   * @param {number} [n=5]
   * @returns {void}
   */
  slow(n) { this.faults.slow = Math.max(1, n || 5); this.faults.slowFrom = 0; }

  /**
   * Stops every timer and connection; the address is free again.
   * @returns {void}
   */
  close() {
    this.timers.forEach((id) => this.clock.clearTimeout(id));
    this.timers.clear();
    this.drop();
    if (this.o.address) { registerMock(this.o.address, null, this); }
  }
}
