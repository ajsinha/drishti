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
/* The shared hub: connect(url, { share: true, namespace }) gives all the tabs of one application (and one signed-in person) a single
   server connection. The hub (HubHost) holds the one Connection and serves the tabs (HubClient, each tab's 'server' scope):

     1. in a SharedWorker (drishti_events-worker.js), when the browser has one and the options can travel to it;
     2. else in the leader tab (the tab holding a Web Lock), relaying over a BroadcastChannel; when it closes, the next tab leads and
        resumes by sequence from the positions the tabs report (raw mode: from the server's event id they last heard);
     3. else each tab connects for itself.

   A pattern held by several tabs is one subscription to the server; a tab that joins late is given the values the hub retained. Each
   tab keeps its own pipeline (conflation, frames, buffers), its own publishes until acknowledged (sent again, same ids, after the hub
   changes) and its own requests. auth is asked of a tab (a SharedWorker has no page to ask). Frames between tabs and the hub are plain
   objects, checked before use. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { EventError, checkPattern, MAX_ID_LENGTH } from './drishti_envelope.js';
import { systemClock } from './drishti_timing.js';
import { createEvents } from './drishti_events-hub.js';
import { Connection } from './drishti_connection.js';
import { wireOf, messageFromWire, broadcastChannelOf, locksOf, Leader } from './drishti_tabs.js';

/** How often a tab tells the hub it is alive, and how long the hub waits before it forgets a silent tab. */
export const HUB_TIMING = Object.freeze({ aliveMs: 5000, forgetMs: 15000, authMs: 10000 });

const HUB_FRAMES = ['join', 'sub', 'unsub', 'pub', 'req', 'cancel', 'auth', 'alive', 'bye', 'reconnect', 'close', 'flush', 'clearOutbox', 'signout', 'metrics'];
const CLIENT_FRAMES_IN = ['hello', 'leader', 'state', 'msg', 'substate', 'ok', 'err', 'res', 'auth?', 'gap', 'metrics', 'done'];

/** @private options that cannot travel to a SharedWorker (functions other than auth and headers, which a tab answers) */
function travels(o) {
  return Object.keys(o).every((k) => ['auth', 'headers', 'env', 'clock', 'signal', 'bus', 'onError', 'worker', 'routes', 'share', 'namespace'].indexOf(k) >= 0 || typeof o[k] !== 'function');
}

/** @private the options the hub's Connection is given (what a tab's options hold that a connection uses) */
function hostOptions(o) {
  const out = {};
  Object.keys(o || {}).forEach((k) => {
    if (['env', 'clock', 'signal', 'bus', 'onError', 'worker', 'routes', 'share', 'auth', 'headers', 'tabs'].indexOf(k) >= 0) { return; }
    out[k] = o[k];
  });
  return out;
}

/**
 * The hub: one Connection serving many tabs (in a SharedWorker or the leader tab).
 */
export class HubHost {
  /**
   * @param {{address: *, options: Object, clock: Object, env: Object, id: string, post: function(string, Object): void,
   *   auth: function(Object): Promise<Object>}} o post(clientId, frame) sends to a tab; auth (optional) answers auth in place
   */
  constructor(o) {
    this.o = o;
    this.id = o.id;
    this.clients = new Map();
    this.held = new Map();
    this.closed = false;
    this.events = createEvents({ bus: new EventBus(), clock: o.clock, routes: { '>': 'server' }, tabs: false, namespace: o.options.namespace,
      limits: o.options.limits, source: 'hub' });
    const copts = hostOptions(o.options);
    copts.env = o.env;
    if (o.auth) { copts.auth = o.auth; } else if (o.options.hasAuth) { copts.auth = (a) => this._askAuth(a); }
    if (o.headers) { copts.headers = o.headers; } else if (o.options.headerValues) { copts.headers = o.options.headerValues; }
    delete copts.hasAuth; delete copts.headerValues;
    this.authAsks = new Map();
    /** @private publishes waiting for the server, by id: the tabs (and their ops) to tell */ this.pubs = new Map();
    this.nextAsk = 1;
    this.conn = new Connection(this.events, o.address, copts);
    // raw mode resumes by the server's own event id: the one the tabs last heard (a hub that takes over carries on where the last left off)
    if (this.conn.protocol === 'raw' && typeof o.lastEventId === 'string' && o.lastEventId.length <= MAX_ID_LENGTH) { this.conn.lastEventId = o.lastEventId; }
    this.events.on('$events.state', (m) => { this.lastState = m.data; this._all({ t: 'state', d: plain(m.data) }); });
    this.events.on('$events.gap', (m) => this._all({ t: 'gap', d: plain(m.data) }));
    this.events.on('$events.subscription', (m) => this._subState(m.data));
    this.sweep = o.clock.setTimeout(() => this._sweep(), HUB_TIMING.forgetMs);
  }

  /** @private to every tab */
  _all(frame) { this.clients.forEach((c, id) => this._to(id, frame)); }

  /** @private */
  _to(id, frame) { try { this.o.post(id, frame); } catch (e) { /* a tab gone */ } }

  /** @private forgets tabs silent too long */
  _sweep() {
    if (this.closed) { return; }
    const now = this.o.clock.now();
    this.clients.forEach((c, id) => { if (now - c.seen > HUB_TIMING.forgetMs) { this._leave(id); } });
    this.sweep = this.o.clock.setTimeout(() => this._sweep(), HUB_TIMING.forgetMs);
  }

  /** @private asks a tab that can for a token */
  _askAuth(a) {
    const id = Array.from(this.clients.keys()).find((k) => this.clients.get(k).hasAuth);
    if (!id) { return Promise.reject(new EventError('auth.required', 'no tab of this hub can answer auth')); }
    const op = this.nextAsk++;
    return new Promise((resolve, reject) => {
      const timer = this.o.clock.setTimeout(() => { this.authAsks.delete(op); reject(new EventError('timeout', 'the tab did not answer auth in time')); }, HUB_TIMING.authMs);
      this.authAsks.set(op, { resolve: resolve, reject: reject, timer: timer });
      this._to(id, { t: 'auth?', op: op, reason: a.reason, status: a.status });
    });
  }

  /** @private a subscription of the hub changed state: told to the tabs holding its pattern */
  _subState(d) {
    const h = this.held.get(d.pattern);
    if (!h) { return; }
    const entry = this.conn.bySub.get(h.sub);
    h.holders.forEach((subId, key) => {
      const cid = key.slice(0, key.lastIndexOf('|'));
      this._to(cid, { t: 'substate', id: subId, state: d.state, reason: d.reason || null, streams: entry && entry.streams ? entry.streams : undefined });
    });
  }

  /**
   * A frame from a tab.
   * @param {string} cid the tab
   * @param {Object} f
   * @returns {void}
   */
  frame(cid, f) {
    if (this.closed || !f || typeof f !== 'object' || HUB_FRAMES.indexOf(f.t) < 0) { return; }
    const c = this.clients.get(cid);
    if (!c && f.t !== 'join') { if (f.t !== 'bye') { this._to(cid, { t: 'leader', host: this.id, rejoin: true }); } return; }
    if (c) { c.seen = this.o.clock.now(); }
    switch (f.t) {
      case 'join': this._join(cid, f); break;
      case 'alive': break;
      case 'bye': this._leave(cid); break;
      case 'sub': this._sub(cid, c, f); break;
      case 'unsub': this._unsub(cid, c, f.id); break;
      case 'pub': this._pub(cid, f); break;
      case 'req': this._req(cid, c, f); break;
      case 'cancel': { const ctl = c.reqs.get(f.op); if (ctl) { c.reqs.delete(f.op); ctl.abort(); } break; }
      case 'auth': {
        const a = this.authAsks.get(f.op);
        if (!a) { return; }
        this.authAsks.delete(f.op);
        this.o.clock.clearTimeout(a.timer);
        if (typeof f.token === 'string') { a.resolve({ token: f.token, expiresAt: f.expiresAt }); } else { a.reject(new EventError('auth.failed', String(f.error || 'the tab\'s auth failed'))); }
        break;
      }
      case 'reconnect': this.conn.reconnect(); break;
      case 'close': this.conn.close(); break;
      case 'flush': this.conn.flush().then((n) => this._to(cid, { t: 'done', op: f.op, n: n })); break;
      case 'clearOutbox': this.conn.clearOutbox().then((n) => this._to(cid, { t: 'done', op: f.op, n: n })); break;
      case 'signout': this.conn.signOut().then(() => this._to(cid, { t: 'done', op: f.op })); break;
      case 'metrics': this._to(cid, { t: 'metrics', op: f.op, d: plain(Object.assign({ tabs: this.clients.size, subscriptions: this.held.size }, this.conn.metrics())) }); break;
      default: break;
    }
  }

  /** @private a tab joins (again): its old subscriptions are forgotten, the positions it heard seed the sequencer */
  _join(cid, f) {
    if (this.clients.has(cid)) { this._leave(cid, true); }
    this.clients.set(cid, { seen: this.o.clock.now(), subs: new Map(), reqs: new Map(), hasAuth: !!f.hasAuth });
    if (f.positions && typeof f.positions === 'object' && this.conn.sequencer) { this.conn.sequencer.seq.seed(f.positions); }
    if (this.conn.protocol === 'raw' && this.conn.state === 'idle' && this.conn.lastEventId === null && typeof f.lastEventId === 'string' &&
      f.lastEventId.length <= MAX_ID_LENGTH) { this.conn.lastEventId = f.lastEventId; }
    this._to(cid, { t: 'hello', host: this.id, state: this.lastState ? plain(this.lastState) : { state: this.conn.state } });
  }

  /** @private a tab left (or was silent too long) */
  _leave(cid, rejoining) {
    const c = this.clients.get(cid);
    if (!c) { return; }
    c.subs.forEach((s, subId) => this._unsub(cid, c, subId));
    c.reqs.forEach((ctl) => ctl.abort());
    this.clients.delete(cid);
    if (!rejoining && !this.clients.size && this.o.onEmpty) { this.o.onEmpty(); }
  }

  /** @private a tab's subscription: one subscription of the hub per pattern */
  _sub(cid, c, f) {
    try { checkPattern(f.pattern); } catch (e) { this._to(cid, { t: 'substate', id: f.id, state: 'refused', reason: 'not a valid pattern' }); return; }
    const o = f.o && typeof f.o === 'object' ? f.o : {};
    c.subs.set(f.id, f.pattern);
    let h = this.held.get(f.pattern);
    const fresh = !h;
    if (fresh) {
      h = { holders: new Map(), sub: null };
      this.held.set(f.pattern, h);
      h.holders.set(cid + '|' + f.id, f.id);
      const so = { scope: 'server' };
      ['retained', 'from', 'onGap', 'staleMs'].forEach((k) => { if (o[k] !== undefined) { so[k] = o[k]; } });
      if (o.conflate && o.conflate.server) { so.conflate = { server: true, everyMs: o.conflate.everyMs }; }
      h.sub = this.events.subscribe(f.pattern, (m) => this._fanOut(h, m), so);
      return;
    }
    h.holders.set(cid + '|' + f.id, f.id);
    this._to(cid, { t: 'substate', id: f.id, state: h.sub.state, reason: h.sub.reason || null });
    if (o.retained) {
      const kept = h.sub.latestAll();
      const last = h.sub.latest();
      (kept.length ? kept : (last ? [last] : [])).forEach((m) => this._to(cid, { t: 'msg', m: Object.assign(wireOf(m), { retained: true }) }));
    }
  }

  /** @private a hub's subscription heard a message: to each tab holding the pattern, once */
  _fanOut(h, m) {
    const w = wireOf(m), sent = new Set(), eid = this.conn.protocol === 'raw' ? this.conn.lastEventId : null;
    h.holders.forEach((subId, key) => {
      const cid = key.slice(0, key.lastIndexOf('|'));
      if (sent.has(cid)) { return; }
      sent.add(cid);
      this._to(cid, eid ? { t: 'msg', m: w, eid: eid } : { t: 'msg', m: w });
    });
  }

  /** @private */
  _unsub(cid, c, subId) {
    const pattern = c.subs.get(subId);
    if (pattern === undefined) { return; }
    c.subs.delete(subId);
    const h = this.held.get(pattern);
    if (!h) { return; }
    h.holders.delete(cid + '|' + subId);
    if (!h.holders.size) { this.held.delete(pattern); h.sub.off(); }
  }

  /** @private a tab's publish: through the one connection (its id kept, so a second sending is harmless) */
  _pub(cid, f) {
    const m = messageFromWire(f.m, 'server', this.o.clock.now());
    if (typeof m === 'string') { this._to(cid, { t: 'err', op: f.op, code: 'invalid', detail: 'the message: ' + m }); return; }
    const o = f.o && typeof f.o === 'object' ? { ack: f.o.ack, queue: f.o.queue, ttlMs: f.o.ttlMs } : {};
    const waiting = this.pubs.get(m.id);
    if (waiting) { waiting.push([cid, f.op]); return; }                    // sent again by a tab that rejoined: one publish, both told
    const tell = [[cid, f.op]];
    this.pubs.set(m.id, tell);
    const done = (frame) => { this.pubs.delete(m.id); tell.forEach(([c, op]) => this._to(c, Object.assign({ op: op }, frame))); };
    this.conn.publish(m, o).then((r) => done({ t: 'ok', acked: !r || r.acked !== false }),
      (e) => done({ t: 'err', code: (e && e.code) || 'error', detail: (e && (e.detail || e.message)) || String(e) }));
  }

  /** @private a tab's request */
  _req(cid, c, f) {
    const m = messageFromWire(f.m, 'server', this.o.clock.now());
    if (typeof m === 'string') { this._to(cid, { t: 'err', op: f.op, code: 'invalid', detail: 'the request: ' + m }); return; }
    const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (ctl) { c.reqs.set(f.op, ctl); }
    this.conn.request(m, { timeoutMs: Number(f.timeoutMs) || 10000, signal: ctl ? ctl.signal : undefined }).then((a) => {
      c.reqs.delete(f.op);
      this._to(cid, { t: 'res', op: f.op, m: wireOf(a) });
    }, (e) => {
      c.reqs.delete(f.op);
      this._to(cid, { t: 'err', op: f.op, code: (e && e.code) || 'error', detail: (e && (e.detail || e.message)) || String(e) });
    });
  }

  /**
   * Closes the connection; the tabs are told.
   * @returns {void}
   */
  destroy() {
    if (this.closed) { return; }
    this.closed = true;
    this.o.clock.clearTimeout(this.sweep);
    this.authAsks.forEach((a) => { this.o.clock.clearTimeout(a.timer); a.reject(new EventError('closed', 'the hub closed')); });
    this.events.destroy();
  }
}

/**
 * A tab's side of the shared hub: the 'server' scope of its Events.
 */
export class HubClient {
  /**
   * @param {Object} events the tab's Events
   * @param {{post: function(Object): void, listen: function(function(Object): void): void, close: function(): void}} link
   * @param {{mode: string, auth: Function, headers: *, clock: Object}} o
   */
  constructor(events, link, o) {
    this.events = events;
    this.link = link;
    this.o = o;
    this.clock = events.clock;
    /** @type {string} 'worker' or 'leader' */ this.mode = o.mode;
    /** @type {string} */ this.state = 'connecting';
    this.transportKind = 'shared';
    this.host = null;
    this.subs = new Map();
    this.bySub = new Map();
    this.nextId = 1;
    this.ops = new Map();
    this.nextOp = 1;
    this.positions = {};
    /** @type {?string} raw mode: the server's event id last heard (a hub that takes over resumes from it) */ this.lastEventId = null;
    this.seen = new Map();
    this.hostMetrics = null;
    this.closed = false;
    link.listen((f) => this._in(f));
    this.detach = events._attach('server', this);
    this.alive = this.clock.setTimeout(() => this._alive(), HUB_TIMING.aliveMs);
    this._join();
  }

  /** @private */
  _post(f) { if (!this.closed) { try { this.link.post(f); } catch (e) { this.events._error(e, { where: 'hub' }); } } }

  /** @private */
  _alive() { if (this.closed) { return; } this._post({ t: 'alive' }); this.alive = this.clock.setTimeout(() => this._alive(), HUB_TIMING.aliveMs); }

  /** @private (re)joins: says where its streams were and whether it can answer auth */
  _join() {
    this._post(Object.assign({ t: 'join', hasAuth: typeof this.o.auth === 'function', positions: this.positions }, this.lastEventId ? { lastEventId: this.lastEventId } : {}));
  }

  /** @private a frame from the hub, checked */
  _in(f) {
    if (this.closed || !f || typeof f !== 'object' || CLIENT_FRAMES_IN.indexOf(f.t) < 0) { return; }
    switch (f.t) {
      case 'leader': if (f.rejoin) { this.host = null; } if (f.host !== this.host) { this._join(); } break;
      case 'hello': this._hello(f); break;
      case 'state': this._state(f.d); break;
      case 'msg': if (typeof f.eid === 'string' && f.eid.length <= MAX_ID_LENGTH) { this.lastEventId = f.eid; } this._msg(f.m); break;
      case 'substate': {
        const s = this.subs.get(f.id);
        if (!s) { return; }
        if (f.streams && typeof f.streams === 'object') {
          Object.keys(f.streams).forEach((k) => {
            const p = f.streams[k];
            if (!p || typeof p.seq !== 'number') { return; }
            if (!this.positions[k]) { this.positions[k] = { epoch: p.epoch != null ? String(p.epoch) : null, seq: p.seq }; }
            else if (p.epoch != null) { this.positions[k].epoch = String(p.epoch); }
          });
        }
        if (['active', 'recovering', 'refused', 'pending'].indexOf(f.state) >= 0) { s.sub._setState(f.state, f.reason || undefined); }
        if (f.state === 'refused') { this._forget(s.sub); }
        break;
      }
      case 'ok': case 'err': case 'res': case 'done': case 'metrics': this._answer(f); break;
      case 'auth?': this._auth(f); break;
      case 'gap': if (f.d && typeof f.d === 'object') { this.events._emitOwn('$events.gap', f.d); } break;
      default: break;
    }
  }

  /** @private a hub answered our join: a new hub is given our subscriptions and our unacknowledged publishes */
  _hello(f) {
    if (typeof f.host !== 'string' || f.host === this.host) { return; }
    this.host = f.host;
    if (f.state && typeof f.state.state === 'string') { this._state(f.state); }
    this.subs.forEach((s, id) => this._post({ t: 'sub', id: id, pattern: s.sub.pattern, o: subOptions(s.sub.o) }));
    this.ops.forEach((op, n) => {
      if (op.kind === 'req' && op.sent) { this.ops.delete(n); op.reject(new EventError('restart', 'the shared hub changed while the request waited')); return; }
      if (op.kind === 'metrics' && op.sent) { this.ops.delete(n); op.resolve(this.hostMetrics); return; }
      op.sent = true;
      this._post(Object.assign({ op: n }, op.frame));
    });
  }

  /** @private */
  _state(d) {
    if (!d || typeof d.state !== 'string' || d.state === this.state && d.state !== 'reconnecting') { return; }
    const was = this.state;
    this.state = d.state;
    this.events._emitOwn('$events.state', Object.assign({ state: d.state, was: was, attempt: d.attempt || 0, waitMs: d.waitMs == null ? null : d.waitMs,
      reason: d.reason || null, transport: d.transport || null, resumed: !!d.resumed }, { shared: true, mode: this.mode }));
  }

  /** @private a message from the hub: once (two patterns of the hub may bring it), its stream's position noted */
  _msg(w) {
    const m = messageFromWire(w, 'server', this.clock.now());
    if (typeof m === 'string') { this.events._drop('invalid', null, null, 'a message from the shared hub: ' + m); return; }
    if (this.seen.has(m.id)) { return; }
    this.seen.set(m.id, true);
    if (this.seen.size > this.events.limits.ids) { this.seen.delete(this.seen.keys().next().value); }
    if (typeof m.seq === 'number') {
      const k = m.stream || m.topic, p = this.positions[k];
      if (!p || m.seq > p.seq) { this.positions[k] = { epoch: p ? p.epoch : null, seq: m.seq }; }
      const keys = Object.keys(this.positions);
      if (keys.length > this.events.limits.streams) { delete this.positions[keys[0]]; }
    }
    this.events._receive(m);
  }

  /** @private */
  _answer(f) {
    const op = this.ops.get(f.op);
    if (f.t === 'metrics') { this.hostMetrics = f.d; }
    if (!op) { return; }
    this.ops.delete(f.op);
    if (f.t === 'err') { op.reject(new EventError(typeof f.code === 'string' ? f.code : 'error', String(f.detail || f.code))); return; }
    if (f.t === 'ok') { op.resolve({ acked: f.acked !== false }); return; }
    if (f.t === 'res') {
      const m = messageFromWire(f.m, 'server', this.clock.now());
      if (typeof m === 'string') { op.reject(new EventError('invalid', 'the answer: ' + m)); return; }
      op.resolve(m);
      return;
    }
    op.resolve(f.t === 'metrics' ? f.d : f.n);
  }

  /** @private the hub needs a token: this tab's auth answers */
  _auth(f) {
    Promise.resolve().then(() => this.o.auth({ reason: f.reason || 'connect', status: f.status || 0 })).then((a) => {
      this._post({ t: 'auth', op: f.op, token: a && a.token, expiresAt: a && a.expiresAt });
    }, (e) => this._post({ t: 'auth', op: f.op, error: (e && e.message) || String(e) }));
  }

  /** @private */
  _op(kind, frame) {
    const n = this.nextOp++;
    return new Promise((resolve, reject) => {
      const op = { kind: kind, frame: frame, resolve: resolve, reject: reject, sent: !!this.host };
      this.ops.set(n, op);
      if (op.sent) { this._post(Object.assign({ op: n }, frame)); }
    });
  }

  /**
   * Holds a subscription at the hub.
   * @param {Object} sub
   * @returns {boolean} true: it waits for the hub's answer
   */
  subscribe(sub) {
    if (this.bySub.has(sub)) { return true; }
    const id = this.nextId++;
    this.subs.set(id, { sub: sub });
    this.bySub.set(sub, id);
    if (this.host) { this._post({ t: 'sub', id: id, pattern: sub.pattern, o: subOptions(sub.o) }); }
    return true;
  }

  /** @private */
  _forget(sub) { const id = this.bySub.get(sub); if (id !== undefined) { this.bySub.delete(sub); this.subs.delete(id); } return id; }

  /**
   * Gives a subscription back.
   * @param {Object} sub
   * @returns {void}
   */
  unsubscribe(sub) { const id = this._forget(sub); if (id !== undefined && this.host) { this._post({ t: 'unsub', id: id }); } }

  /**
   * Whether a subscription still waits for the hub.
   * @param {Object} sub
   * @returns {boolean}
   */
  pending(sub) { return this.bySub.has(sub) && sub.state === 'pending'; }

  /**
   * Publishes through the hub; resolves when the server acknowledges. Kept until then, and sent again (same id) if the hub changes.
   * @param {Object} m
   * @param {Object} o
   * @returns {Promise<{acked: boolean}>}
   */
  publish(m, o) {
    if (o && o.queue === false && this.state !== 'open') { return Promise.reject(new EventError('not.connected', 'not connected (' + this.state + '), and the publish asked not to wait')); }
    const wire = wireOf(m), opts = { ack: o ? o.ack : undefined, queue: o && o.queue === 'persist' ? 'persist' : undefined, ttlMs: o ? o.ttlMs : undefined };
    return this._op('pub', { t: 'pub', m: wire, o: opts });
  }

  /**
   * Asks the server through the hub. Aborting tells the hub (which tells the server).
   * @param {Object} m
   * @param {{timeoutMs: number, signal: AbortSignal}} o
   * @returns {Promise<Object>}
   */
  request(m, o) {
    const n = this.nextOp;
    const p = this._op('req', { t: 'req', m: wireOf(m), timeoutMs: o.timeoutMs });
    if (o.signal) {
      o.signal.addEventListener('abort', () => {
        const op = this.ops.get(n);
        if (!op) { return; }
        this.ops.delete(n);
        if (op.sent) { this._post({ t: 'cancel', op: n }); }
      }, { once: true });
    }
    return p;
  }

  /** Sends what waits at the hub now. @returns {Promise<number>} */
  flush() { return this._op('flush', { t: 'flush' }); }

  /** Empties the hub's queue. @returns {Promise<number>} */
  clearOutbox() { return this._op('clearOutbox', { t: 'clearOutbox' }); }

  /** The person signs out: the hub's queue and positions are forgotten, and so are this tab's. @returns {Promise<void>} */
  signOut() {
    this.positions = {};
    this.ops.forEach((op, n) => { if (op.kind === 'pub') { this.ops.delete(n); op.reject(new EventError('signed.out', 'the person signed out')); } });
    return this._op('signout', { t: 'signout' }).then(() => undefined);
  }

  /**
   * Opens the shared connection again now.
   * @returns {void}
   */
  reconnect() { this._post({ t: 'reconnect' }); }

  /**
   * Closes the shared connection (for every tab).
   * @returns {void}
   */
  close() { this._post({ t: 'close' }); }

  /**
   * The figures: the hub's (as last heard) and this tab's.
   * @returns {Object}
   */
  metrics() {
    if (this.host) { this._op('metrics', { t: 'metrics' }).catch(() => {}); }
    return Object.assign({ state: this.state, shared: true, mode: this.mode, host: this.host, waiting: this.ops.size }, this.hostMetrics ? { hub: this.hostMetrics } : {});
  }

  /**
   * Leaves the hub.
   * @returns {void}
   */
  destroy() {
    if (this.closed) { return; }
    this._post({ t: 'bye' });
    this.closed = true;
    this.clock.clearTimeout(this.alive);
    const gone = new EventError('closed', 'the Events was closed');
    this.ops.forEach((op) => op.reject(gone));
    this.ops.clear();
    try { this.link.close(); } catch (e) { /* closed */ }
  }
}

/** @private what a tab's subscription asks of the hub (what the server is told) */
function subOptions(o) {
  const out = {};
  ['retained', 'from', 'onGap', 'staleMs'].forEach((k) => { if (o[k] !== undefined) { out[k] = o[k]; } });
  if (o.conflate && o.conflate.server) { out.conflate = { server: true, everyMs: (o.deliver && o.deliver.everyMs) || o.conflate.everyMs }; }
  return out;
}

/** @private a structured-clone-safe copy (drops errors and functions) */
function plain(d) {
  const out = {};
  Object.keys(d || {}).forEach((k) => { const v = d[k]; if (typeof v !== 'function' && !(v instanceof Error)) { out[k] = v; } });
  return out;
}

/** @private the key the hub's channel, worker and lock carry: the namespace and the address */
function hubKey(o, address) {
  const a = typeof address === 'string' ? address : (typeof o.hubKey === 'string' ? o.hubKey : 'provider');
  return o.namespace + '|' + a;
}

/**
 * The address of the hub's worker file: the option, else beside the loaded bundle.
 * @param {Object} o
 * @returns {?string}
 */
export function workerAddress(o) {
  if (o.worker === false) { return null; }
  if (typeof o.worker === 'string') { return o.worker; }
  const g = typeof globalThis !== 'undefined' ? globalThis : null;
  const base = g && g.Drishti && g.Drishti.elements && g.Drishti.elements.base;
  return base ? base + 'drishti-events/drishti_events-worker.js' : null;
}

/**
 * Gives an Events its server through the shared hub (worker, else leader tab), or answers false when neither is possible here (the
 * caller connects for itself).
 * @param {Object} events
 * @param {*} address
 * @param {Object} o the connect options (share: true, namespace required)
 * @returns {(string|boolean)} the mode ('worker' or 'leader'), or false
 */
export function shareConnection(events, address, o) {
  if (!o.namespace) {
    throw new EventError('share.namespace', 'share: true needs a namespace (one per application and signed-in person), so two people\'s tabs never share a connection');
  }
  const env = o.env || {};
  const key = hubKey(o, address);
  const Worker = env.SharedWorker || (typeof window !== 'undefined' && window.SharedWorker) || null;
  const url = workerAddress(o);
  if (Worker && url && typeof address === 'string' && travels(o)) {
    const w = new Worker(url, { name: 'drishti-events.' + key });
    const port = w.port;
    const options = Object.assign(hostOptions(o), { hasAuth: typeof o.auth === 'function' });
    if (o.headers && typeof o.headers !== 'function') { options.headerValues = o.headers; }
    if (typeof o.headers === 'function') { try { options.headerValues = o.headers(); } catch (e) { /* none */ } }
    const link = {
      post: (f) => port.postMessage(f.t === 'join' ? Object.assign({ address: address, options: options }, f) : f),
      listen: (fn) => { port.onmessage = (e) => fn(e && e.data); if (port.start) { port.start(); } },
      close: () => { if (port.close) { port.close(); } }
    };
    new HubClient(events, link, { mode: 'worker', auth: o.auth });
    return 'worker';
  }
  const Channel = broadcastChannelOf(env);
  const locks = locksOf(env);
  if (Channel && locks) {
    const me = 'tab:' + events.ids.tab;
    const ch = new Channel('drishti-events.hub.' + key);
    let host = null, local = null, leader = null;
    const toHost = (f) => {
      if (host) { Promise.resolve().then(() => { if (host) { host.frame(me, f); } }); return; }
      ch.postMessage(Object.assign({ to: 'leader', from: me }, f));
    };
    const link = { post: toHost, listen: (fn) => { local = fn; }, close: () => { if (leader) { leader.stop(); } if (host) { host.destroy(); host = null; } ch.close(); } };
    ch.onmessage = (e) => {
      const f = e && e.data;
      if (!f || typeof f !== 'object' || typeof f.from !== 'string' || f.from.length > MAX_ID_LENGTH) { return; }
      if (f.to === 'leader') { if (host) { host.frame(f.from, f); } return; }
      if ((f.to === me || f.to === '*') && local) { local(f); }
    };
    leader = new Leader({ name: 'drishti-events.leader.' + key, env: env, onLead: () => {
      const hostId = me + ':' + events.clock.now().toString(36);
      host = new HubHost({ id: hostId, address: address, options: o, clock: events.clock, env: env, auth: o.auth, lastEventId: client.lastEventId,
        post: (cid, f) => { if (cid === me) { Promise.resolve().then(() => { if (local) { local(f); } }); } else { ch.postMessage(Object.assign({ to: cid, from: me }, f)); } } });
      ch.postMessage({ t: 'leader', to: '*', from: me, host: hostId });
      Promise.resolve().then(() => { if (local) { local({ t: 'leader', host: hostId }); } });
    } });
    const client = new HubClient(events, link, { mode: 'leader', auth: o.auth });
    leader.start();
    return 'leader';
  }
  return false;
}

/**
 * Serves the shared hub inside a SharedWorker: every tab's port joins the one HubHost of this worker (the worker's name carries the
 * namespace and the address).
 * @param {Object} scope the worker's global (self)
 * @param {{clock: Object, env: Object}} [o]
 * @returns {{host: function(): ?HubHost}}
 */
export function serveSharedWorker(scope, o) {
  const opts = Object.assign({}, o);
  let host = null, n = 0;
  const ports = new Map();
  const clock = opts.clock || null;
  scope.onconnect = (e) => {
    const port = e.ports[0], cid = 'port:' + (++n);
    ports.set(cid, port);
    port.onmessage = (ev) => {
      const f = ev && ev.data;
      if (!f || typeof f !== 'object') { return; }
      if (f.t === 'join' && !host) {
        if (!f.options || typeof f.options !== 'object') { return; }
        host = new HubHost({ id: 'worker:' + Date.now().toString(36), address: f.address, options: f.options, env: opts.env || {}, lastEventId: f.lastEventId,
          clock: clock || systemClock(), post: (id, fr) => { const p = ports.get(id); if (p) { p.postMessage(fr); } },
          onEmpty: () => { host.conn.close(); host.destroy(); host = null; } });
      }
      if (f.t === 'bye') { ports.delete(cid); }
      if (host) { host.frame(cid, f); }
    };
    if (port.start) { port.start(); }
  };
  return { host: () => host };
}


