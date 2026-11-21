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
/* The connection: the 'server' scope of an Events. One state machine over three transports (a WebSocket, Server-Sent Events by
   EventSource, Server-Sent Events read with fetch) and two protocols (version 1, and raw for a server that only sends its own JSON).

     idle -> connecting -> open -> closed;  open -> reconnecting -> connecting;  offline (the browser says so: waits for online);
     refused (trying again cannot help: says why)

   A dropped connection is opened again after a back-off with full jitter (a server's advice is a floor), at once when the browser comes
   back online or the page becomes visible, never in a hot loop: a refusal trying again cannot cure stops in 'refused'. Any frame is a
   sign of life; with none for 1.5 times the heartbeat the connection is taken as dead. On a WebSocket the client pings every third of the
   heartbeat and the pong gives the round trip and the clock offset. auth is asked before every connect and a minute before a token runs
   out; a refusal asks once more (reason 'refused'), a second refusal is 'refused'. An address whose query names a secret is refused.

   Subscriptions are sent when the connection opens and again after every reconnect; publishes wait (in order) while it is down and are
   sent again until acknowledged (ids make that safe); requests wait for the connection and fail on their timeout.

   The sequencer is the seam of step 4 (order, duplicates, gaps, replay, resume): accept(message, deliver) between a msg frame and the
   Events, after(subscription) for a sub frame's resume positions, subscribed(subscription, subd), gap(frame) and forget(). Until it is
   given, messages pass straight through. */
import { ELEMENTS_VERSION, endpoint, isTopic } from '../../drishti-core/src/drishti_index.js';
import { EventError, makeMessage, matches } from './drishti_envelope.js';
import { Backoff, Heartbeat } from './drishti_timing.js';
import { PROTOCOL_VERSION, SUBPROTOCOL, CLOSE_CODES, decodeFrames, checkFrame, messageOf, rawMessage, encodeFrames } from './drishti_protocol.js';
import { websocketTransport } from './drishti_transport-ws.js';
import { Outbox } from './drishti_outbox.js';
import { Sequencer } from './drishti_sequence.js';
import { eventSourceTransport, fetchTransport } from './drishti_transport-sse.js';

/** The connection's states. */
export const CONNECTION_STATES = Object.freeze(['idle', 'connecting', 'open', 'reconnecting', 'offline', 'refused', 'closed']);

/** Query parameters an address may not carry: a secret travels in auth or headers, never in an address. */
export const SECRET_PARAMS = Object.freeze(['token', 'access_token', 'jwt', 'key', 'apikey', 'secret', 'password', 'auth', 'signature']);

const TRANSPORTS = { websocket: websocketTransport, sse: eventSourceTransport, fetch: fetchTransport };

/** Publishing pauses while a WebSocket holds more than this many bytes unsent. */
export const BUFFERED_LIMIT = 1048576;

/**
 * The options of connect (with those of an Events: routes, clock, limits, signal, ...).
 * @typedef {Object} ConnectOptions
 * @property {('websocket'|'sse'|'fetch'|function(Object): Object)} [transport] default by the address: ws: and wss: a WebSocket, else
 *   fetch when auth or headers are given, else sse (EventSource); or a function making a transport (a stand-in, the mock server)
 * @property {(1|'raw')} [protocol=1] raw: a server that only sends its own JSON (each value a message by topicOf, seqOf, idOf, keyOf, kindOf)
 * @property {function({reason: string, status: number}): Promise<{token: string, expiresAt: number}>} [auth] asked before every
 *   connect ('connect'), a minute before the token runs out ('expiring') and once after a refusal ('refused')
 * @property {('hello'|'subprotocol'|'cookie')} [wsAuth='hello'] how a WebSocket carries the token: in the first frame, as a second
 *   sub-protocol (bearer.<token, base64url>), or not at all (the site's cookie)
 * @property {(Object<string, string>|function(): Object<string, string>)} [headers] fetch: more headers
 * @property {string} [credentials='same-origin'] fetch and EventSource ('include': cross-site cookies)
 * @property {number} [heartbeatMs] silence allowed before the connection is taken as dead (1.5 times this); default the server's figure
 *   from welcome, else 45,000; EventSource cannot see comment lines, so its watch is on only when this is given
 * @property {{baseMs: number, maxMs: number, factor: number, jitter: boolean}} [backoff]
 * @property {string} [subprotocol='events.v1'] the WebSocket sub-protocol's name
 * @property {import('./drishti_outbox.js').OutboxOptions} [outbox] the offline queue (persist, bounds, overflow, store)
 * @property {string} [namespace] differs per application and signed-in person: the queue's database and the tabs' channel carry it
 * @property {boolean} [share=false] the application's tabs share one connection (a SharedWorker, else the leader tab); needs a namespace
 * @property {(string|boolean)} [worker] the shared hub's worker file (default drishti_events-worker.js beside the bundle; false: no worker)
 * @property {number} [idleCloseMs] a page hidden for longer than this gives its connection back and takes it again when shown (off by
 *   default); a page put away (pagehide, freeze) always closes cleanly and opens again when shown (pageshow, resume)
 * @property {string[]} [events] raw SSE: the event names that carry values (default every name for fetch, message for EventSource)
 * @property {function(*, Object): string} [topicOf] raw: the topic of a value (default 'feed')
 * @property {function(*, Object): number} [seqOf] raw
 * @property {function(*, Object): string} [idOf] raw
 * @property {function(*, Object): string} [keyOf] raw
 * @property {function(*, Object): string} [kindOf] raw (default the SSE event's name)
 * @property {{WebSocket: Function, EventSource: Function, fetch: Function, window: Object}} [env] the browser's classes and window
 *   (stand-ins in tests; default the page's)
 */

/**
 * Throws when an address names a secret in its query (a token, a key, a password...), and says how to send it instead.
 * @param {string} url
 * @returns {string} the address
 */
export function checkAddress(url) {
  const at = String(url).indexOf('?');
  if (at < 0) { return url; }
  const names = String(url).slice(at + 1).split('#')[0].split('&').map((p) => decodeURIComponent(p.split('=')[0] || '').toLowerCase());
  const bad = names.find((n) => SECRET_PARAMS.indexOf(n) >= 0);
  if (bad) {
    throw new EventError('address.secret', 'the address names a secret (' + bad + '=) in its query, where logs and proxies keep it: give it to ' +
      'connect\'s auth option (a token) or headers instead');
  }
  return url;
}

/**
 * The connection's sequencer seam (step 4) over a Sequencer: order, repeats, gaps and replay per stream, the `after` positions of a `sub`,
 * resume (subd) with resync notices, and `$events.gap`. A message goes through accept(m, deliver); what the Sequencer holds is delivered
 * when the range arrives or the wait ends.
 * @private
 */
class ConnectionSequencer {
  /**
   * @param {Connection} conn
   */
  constructor(conn) {
    this.conn = conn;
    this.deliver = null;
    this.seq = new Sequencer({ clock: conn.clock, gapWaitMs: conn.o.gapWaitMs, maxHeld: conn.o.maxHeld,
      onReplay: (r) => { conn._send([{ t: 'replay', stream: r.stream, epoch: r.epoch, from: r.from, to: r.to }]); },
      onGap: (g) => { conn.events._emitOwn('$events.gap', { stream: g.stream, from: g.from, to: g.to, recoverable: g.recoverable }); },
      onResync: (r) => { this._resync([r.stream], r.reason); },
      onRelease: (list) => { list.forEach((m) => this._out(m)); } });
  }

  /** @private */
  _out(m) { if (this.deliver) { this.deliver(m); } }

  /** @private whether the server offers replay (welcome.can) */
  _can() { this.seq.o.canReplay = this.conn.can.indexOf('replay') >= 0; }

  /** @private the subscriptions that hear a stream enter `recovering`, are told by a resync notice (to that subscription only), and are active again */
  _resync(streams, reason) {
    this.conn.subs.forEach((s) => {
      // the streams the server named when it answered, or (a stream born after the subscription) the streams its pattern hears
      const hears = (x) => (s.streams && Object.prototype.hasOwnProperty.call(s.streams, x)) || (isTopic(x) && matches(s.sub.pattern, x));
      if (!streams.some(hears)) { return; }
      s.sub._setState('recovering');
      const now = this.conn.clock.now();
      s.sub._accept(makeMessage({ id: this.conn.events.ids.next(), topic: streams[0], data: { streams: streams, reason: reason },
        time: now, source: 'server', kind: 'resync', scope: 'server', receivedAt: now }));
      s.sub._setState('active');
    });
  }

  accept(m, deliver) { this.deliver = deliver; this._can(); this.seq.accept(m).forEach(deliver); }

  after(sub, s) {
    const all = this.seq.positions(), out = {};
    const known = Object.keys(s.streams || {});
    if (known.length) { known.forEach((k) => { if (all[k]) { out[k] = all[k]; } }); return out; }
    // a subscription the server has not answered yet (a new shared hub taking over): the streams named like its pattern
    Object.keys(all).forEach((k) => { if (isTopic(k) && matches(sub.pattern, k)) { out[k] = all[k]; } });
    return out;
  }

  subscribed(sub, f) {
    const s = this.conn.subs.get(Number(f.id)) || this.conn.subs.get(f.id);
    if (s && !s.streams) { s.streams = f.streams || {}; }
    const gone = [];
    if (f.recovered === false && s && s.after) {                     // streams asked for that the server no longer names: start again
      Object.keys(s.after).forEach((k) => { if (!f.streams || !Object.prototype.hasOwnProperty.call(f.streams, k)) { this.seq.forgetStream(k); gone.push(k); } });
    }
    this.seq.resumed(f.streams || {}, f.recovered !== false);
    if (gone.length) { this._resync(gone, 'unrecovered'); }
  }

  gap(f) {
    this._can();
    if (f.recoverable) { this.conn.events._emitOwn('$events.gap', { stream: f.stream, from: f.from, to: f.to, recoverable: true }); }
    this.seq.serverGap(f).forEach((m) => this._out(m));
  }

  forget() { this.seq.reset(); }

  unsubscribed() {}
}

/**
 * The connection to a server: the 'server' scope of an Events.
 */
export class Connection {
  /**
   * @param {Object} events the Events it serves
   * @param {(string|function(): string|Object)} address the server's address, a function asked at every connect, or a provider with
   *   transport(context) (the mock server)
   * @param {ConnectOptions} [o]
   */
  constructor(events, address, o) {
    this.events = events;
    this.o = Object.assign({ wsAuth: 'hello', credentials: 'same-origin', subprotocol: SUBPROTOCOL }, o);
    this.clock = events.clock;
    this.address = address;
    if (typeof address === 'string') { checkAddress(address); }
    /** @type {(1|'raw')} */ this.protocol = this.o.protocol === 'raw' ? 'raw' : PROTOCOL_VERSION;
    if (this.o.protocol !== undefined && this.o.protocol !== 'raw' && this.o.protocol !== PROTOCOL_VERSION) {
      throw new EventError('version', 'this client speaks protocol 1 (or raw); asked for ' + this.o.protocol);
    }
    /** @type {string} */ this.transportKind = this._pickTransport();
    this.env = Object.assign({}, this.o.env);
    this.win = this.env.window || (typeof window !== 'undefined' ? window : null);
    /** @type {string} */ this.state = 'idle';
    this.backoff = new Backoff(Object.assign({}, this.o.backoff, { random: this.clock.random }));
    this.heartbeatMs = this.o.heartbeatMs || 45000;
    this.heart = new Heartbeat({ clock: this.clock, timeoutMs: 0, onTimeout: () => this._dropped({ reason: 'no message or heartbeat for ' + Math.round(this.heartbeatMs * 1.5 / 1000) + ' s' }) });
    /** @type {?string} */ this.session = null;
    /** @type {string[]} */ this.can = [];
    /** @type {Object} */ this.serverLimits = {};
    /** @type {?string} */ this.lastEventId = null;
    this.subs = new Map();
    this.bySub = new Map();
    this.nextSub = 1;
    /** @type {Outbox} every publish until the server acknowledges it */
    this.outbox = new Outbox({ clock: this.clock, options: Object.assign({ max: events.limits.outboxMessages, maxBytes: events.limits.outboxBytes,
      maxAgeMs: events.limits.outboxAgeMs }, this.o.outbox), namespace: this.o.namespace, env: this.env,
      onDrop: (f, why, words) => events._drop(why, { id: f.id, topic: f.topic }, null, words) });
    this.reqs = new Map();
    this.token = null;
    this.tokenExpiresAt = 0;
    this.refusals = 0;
    this.attempt = 0;
    this.floorMs = 0;
    this.stopped = false;
    this.transport = null;
    this.timers = { retry: null, ping: null, refresh: null, rescope: null, pump: null, idle: null };
    /** @type {boolean} closed because the page was put away or hidden too long: opened again when it is shown */
    this.suspended = false;
    /** @type {Object} */ this.sequencer = new ConnectionSequencer(this);
    /** @type {Object} the figures the metrics read */
    this.stats = { connects: 0, reconnects: 0, openedAt: 0, connectedMs: 0, framesIn: 0, framesOut: 0, bytesIn: 0, unknown: 0, bad: 0,
      rtt: null, rtts: [], offset: null, published: 0, acked: 0, retried: 0, failed: 0 };
    this._onOnline = () => { if (this.state === 'offline' || this.state === 'reconnecting') { this._connectNow('connect'); } };
    this._onVisible = () => {
      const d = this._doc();
      if (!d) { return; }
      if (d.visibilityState === 'hidden') {
        if (this.o.idleCloseMs && this.timers.idle === null) { this.timers.idle = this.clock.setTimeout(() => { this.timers.idle = null; this._suspend('hidden for longer than ' + Math.round(this.o.idleCloseMs / 1000) + ' s'); }, this.o.idleCloseMs); }
        return;
      }
      if (this.timers.idle !== null) { this.clock.clearTimeout(this.timers.idle); this.timers.idle = null; }
      if (this.suspended) { this._wake(); } else if (this.state === 'reconnecting') { this._connectNow('connect'); }
    };
    this._onHide = () => this._suspend('the page was put away');
    this._onShow = () => this._wake();
    if (this.win && this.win.addEventListener) {
      this.win.addEventListener('online', this._onOnline);
      this.win.addEventListener('pagehide', this._onHide);
      this.win.addEventListener('pageshow', this._onShow);
    }
    const doc = this._doc();
    if (doc && doc.addEventListener) {
      doc.addEventListener('visibilitychange', this._onVisible);
      doc.addEventListener('freeze', this._onHide);
      doc.addEventListener('resume', this._onShow);
    }
    this.detach = events._attach('server', this);
    // the first attempt waits for the end of the current task: whoever listens to $events.state or subscribes right after connect()
    // hears 'connecting', and a stream that carries its subscriptions in its address opens with them
    Promise.resolve().then(() => { if (this.state === 'idle' && !this.stopped) { this._connectNow('connect'); } });
  }

  /** @private the page's document (or the stand-in's) */
  _doc() { return this.env.document || (typeof document !== 'undefined' ? document : null); }

  /** @private the page was put away (or hidden too long): closed cleanly, so it stays eligible for the back-forward cache */
  _suspend(reason) {
    if (this.stopped || this.suspended || this.state === 'idle' || this.state === 'refused' || this.state === 'closed') { return; }
    if (this.transport && this.state === 'open' && this.protocol === 1 && this.transport.kind === 'websocket') { this._send([{ t: 'bye' }]); }
    this.suspended = true;
    this.attempt++;
    this.clock.clearTimeout(this.timers.retry);
    this.timers.retry = null;
    this._stopTimers();
    this._teardown();
    this.subs.forEach((s) => { s.held = false; });
    this._setState('closed', { reason: reason, suspended: true });
  }

  /** @private the page is shown again: open and resume */
  _wake() {
    if (!this.suspended) { return; }
    this.suspended = false;
    this.backoff.reset();
    this._connectNow('connect');
  }

  /** @private */
  _pickTransport() {
    const t = this.o.transport;
    if (typeof t === 'function') { return 'custom'; }
    if (this.address && typeof this.address === 'object' && typeof this.address.transport === 'function') { return 'custom'; }
    if (t) {
      if (!TRANSPORTS[t]) { throw new EventError('transport.invalid', 'no transport ' + t + ' (websocket, sse, fetch)'); }
      return t;
    }
    const url = typeof this.address === 'function' ? String(this.address()) : String(this.address || '');
    if (/^wss?:/i.test(url)) { return 'websocket'; }
    return this.o.auth || this.o.headers ? 'fetch' : 'sse';
  }

  /** @private the address now */
  _url() {
    const u = typeof this.address === 'function' ? String(this.address()) : (typeof this.address === 'string' ? this.address : '');
    if (u) { checkAddress(u); }
    return u;
  }

  /** @private */
  _setState(state, extra) {
    if (this.state === state && state !== 'reconnecting') { return; }
    const was = this.state;
    if (was === 'open' && this.stats.openedAt) { this.stats.connectedMs += this.clock.now() - this.stats.openedAt; this.stats.openedAt = 0; }
    this.state = state;
    if (state === 'open') { this.stats.openedAt = this.clock.now(); }
    this.events._emitOwn('$events.state', Object.assign({ state: state, was: was, attempt: this.backoff.attempt, waitMs: null, reason: null,
      transport: this.transportKind, shared: false, resumed: false }, extra));
  }

  // ---- opening --------------------------------------------------------------------------------------------------------------------

  /** @private asks auth, then opens a transport */
  _connectNow(reason, status) {
    if (this.stopped) { return; }
    this.clock.clearTimeout(this.timers.retry);
    this.timers.retry = null;
    this._teardown();
    this._setState('connecting', { attempt: this.backoff.attempt });
    this.stats.connects++;
    const attempt = ++this.attempt;
    const go = () => { if (attempt === this.attempt && !this.stopped) { this._openTransport(); } };
    if (this.o.auth && this.o.wsAuth !== 'cookie') {
      this._askAuth(reason, status).then(go, (e) => {
        if (attempt !== this.attempt) { return; }
        this._refuse('auth', 'the auth function failed: ' + ((e && e.message) || String(e)));
      });
    } else { go(); }
  }

  /** @private */
  _askAuth(reason, status) {
    return Promise.resolve().then(() => this.o.auth({ reason: reason || 'connect', status: status || 0 })).then((a) => {
      if (!a || typeof a.token !== 'string') { throw new EventError('auth.invalid', 'auth answered no token'); }
      this.token = a.token;
      this.tokenExpiresAt = Number(a.expiresAt) || 0;
      this.events._emitOwn('$events.auth', { reason: reason || 'connect', ok: true, expiresAt: this.tokenExpiresAt || null });
      return a;
    });
  }

  /** @private the headers of a fetch, the token's included */
  _headers() {
    const h = typeof this.o.headers === 'function' ? this.o.headers() : (this.o.headers || {});
    const out = Object.assign({}, h);
    if (this.token && this.transportKind !== 'websocket') { out.Authorization = 'Bearer ' + this.token; }
    return out;
  }

  /** @private */
  _hello() {
    const f = { t: 'hello', v: PROTOCOL_VERSION, client: 'drishti-events/' + ELEMENTS_VERSION };
    if (this.session) { f.session = this.session; }
    if (this.token && this.transportKind !== 'websocket') { return f; }
    if (this.token && this.o.wsAuth === 'hello') { f.token = this.token; }
    return f;
  }

  /** @private a transport for this attempt */
  _openTransport(swapping) {
    let ctx;
    try {
      ctx = this._context();
      const make = this.transportKind === 'custom' ? (typeof this.o.transport === 'function' ? this.o.transport : (c) => this.address.transport(c)) : TRANSPORTS[this.transportKind];
      const t = make(ctx);
      ctx.transport = t;
      if (swapping) { this.swap = { old: swapping, next: t }; } else { this.transport = t; }
    } catch (e) {
      if (e && e.code === 'address.secret') { this._refuse('address.secret', e.detail); return; }
      this._dropped({ reason: (e && e.message) || String(e), code: 0 });
    }
  }

  /** @private what a transport is given */
  _context() {
    const url = this._url();
    const ctx = {
      url: url, protocol: this.protocol, env: this.env, credentials: this.o.credentials, lastEventId: this.lastEventId,
      headers: this._headers(), events: this.o.events || null, session: () => this.session,
      protocols: this.protocol === 1 ? [this.o.subprotocol].concat(this.token && this.o.wsAuth === 'subprotocol' ? ['bearer.' + base64url(this.token)] : []) : [],
      hello: this._hello(), subs: this.protocol === 1 && this._inAddress() ? Array.from(this.subs.values()).map((s) => { s.held = true; return this._subFrame(s); }) : [],
      clock: this.clock
    };
    const live = () => ctx.transport && (ctx.transport === this.transport || (this.swap && ctx.transport === this.swap.next));
    ctx.onOpen = () => { if (live()) { this._transportOpen(ctx); } };
    ctx.onText = (text, meta) => { if (live() || (this.swap && ctx.transport === this.swap.old)) { this._text(text, meta, ctx); } };
    ctx.onComment = () => { if (live()) { this.heart.beat(); } };
    ctx.onRetry = (ms) => { this.floorMs = ms; };
    ctx.onBinary = () => { this.stats.bad++; this.events._drop('invalid', null, null, 'a binary frame (reserved in version 1)'); };
    ctx.onClose = (info) => {
      if (this.swap && ctx.transport === this.swap.old) { return; }
      if (this.swap && ctx.transport === this.swap.next) { const old = this.swap.old; this.swap = null; this.transport = old; return; }
      if (ctx.transport === this.transport) { this._dropped(info); }
    };
    return ctx;
  }

  /** @private the transport opened: hello (a WebSocket in protocol 1), else wait for welcome; raw is open at once */
  _transportOpen(ctx) {
    this.heart.beat();
    if (this.protocol === 'raw') { this._opened({ resumed: this.lastEventId !== null }); return; }
    if (ctx.transport.kind === 'websocket' || this.transportKind === 'custom') {
      ctx.transport.send([this._hello()]).catch(() => undefined);
    }
  }

  // ---- receiving --------------------------------------------------------------------------------------------------------------------

  /** @private a transport message */
  _text(text, meta, ctx) {
    this.heart.beat();
    if (meta && meta.id) { this.lastEventId = meta.id; }
    if (this.protocol === 'raw') { this._raw(text, meta); return; }
    const got = decodeFrames(text, { maxBytes: this.events.limits.frameBytes });
    this.stats.bytesIn += got.bytes;
    if (got.error) {
      this.stats.bad++;
      this.events._drop(got.error === 'too-large' ? 'too-large' : 'invalid', null, null, got.error === 'too-large' ? 'a frame of ' + got.bytes + ' bytes (the limit is ' +
        this.events.limits.frameBytes + ')' : 'a transport message that is not a frame (' + got.error + ')');
      return;
    }
    got.frames.forEach((f) => {
      this.stats.framesIn++;
      if (f.unknown) { this.stats.unknown++; return; }
      const ok = checkFrame(f);
      if (ok !== true) { this.stats.bad++; this.events._drop('invalid', null, null, ok); return; }
      try { this._frame(f, ctx); } catch (e) { this.events._error(e, { where: 'frame', frame: f.t }); }
    });
  }

  /** @private raw mode: every value a message */
  _raw(text, meta) {
    let v;
    try { v = typeof text === 'string' ? JSON.parse(text) : text; } catch (e) {
      this.stats.bad++;
      this.events._drop('invalid', null, null, 'the feed sent text that is not JSON: ' + String(text).slice(0, 80));
      return;
    }
    const m = rawMessage(v, meta || { event: 'message', id: null }, this.o, () => this.events.ids.next(), this.clock.now());
    this._deliver(m);
  }

  /** @private a message into the Events, through the sequencer when there is one */
  _deliver(m) {
    if (this.sequencer) { this.sequencer.accept(m, (x) => this.events._receive(x)); } else { this.events._receive(m); }
  }

  /** @private one server frame */
  _frame(f, ctx) {
    switch (f.t) {
      case 'welcome': this._welcome(f, ctx); break;
      case 'subd': {
        const s = this.subs.get(Number(f.id)) || this.subs.get(f.id);
        if (!s) { return; }
        s.streams = f.streams || {};
        if (this.sequencer) { this.sequencer.subscribed(s.sub, f); }
        if (s.sub.state !== 'recovering') { s.sub._setState('active'); }
        break;
      }
      case 'msg': this._deliver(messageOf(f, this.clock.now())); break;
      case 'ok': {
        if (this.outbox.done(String(f.id), true)) { this.stats.acked++; }
        break;
      }
      case 'res': {
        const r = this.reqs.get(String(f.re));
        if (!r) { return; }
        this.reqs.delete(String(f.re));
        if (f.error) { r.reject(new EventError(String(f.error.code || 'error'), String(f.error.detail || ''))); return; }
        const now = this.clock.now();
        r.resolve(makeMessage({ id: f.id !== undefined ? String(f.id) : this.events.ids.next(), topic: r.frame.topic, data: f.data === undefined ? null : f.data,
          time: typeof f.time === 'number' ? f.time : now, source: typeof f.source === 'string' ? f.source : 'server', re: String(f.re), kind: f.kind, scope: 'server', receivedAt: now }));
        break;
      }
      case 'err': this._err(f); break;
      case 'gap':
        if (this.sequencer && this.sequencer.gap) { this.sequencer.gap(f); } else { this.events._emitOwn('$events.gap', { stream: f.stream, from: f.from, to: f.to, recoverable: !!f.recoverable }); }
        break;
      case 'auth':
        if (f.need) { this._refresh(); } else if (f.expiresAt) { this.tokenExpiresAt = Number(f.expiresAt); this._scheduleRefresh(); }
        break;
      case 'pong': {
        if (typeof f.ts !== 'number') { return; }
        const now = this.clock.now(), rtt = Math.max(0, now - f.ts);
        this.stats.rtt = rtt;
        this.stats.rtts.push(rtt);
        if (this.stats.rtts.length > 64) { this.stats.rtts.shift(); }
        if (typeof f.time === 'number') { this.stats.offset = f.time - (f.ts + rtt / 2); }
        break;
      }
      case 'bye': this._bye(f); break;
      default: break;
    }
  }

  /** @private */
  _welcome(f, ctx) {
    if (f.v !== PROTOCOL_VERSION) { this._refuse('version', 'the server speaks protocol ' + f.v + '; this client speaks ' + PROTOCOL_VERSION); return; }
    this.session = typeof f.session === 'string' ? f.session : this.session;
    this.can = Array.isArray(f.can) ? f.can.map(String) : [];
    this.serverLimits = f.limits && typeof f.limits === 'object' ? f.limits : {};
    if (typeof f.heartbeatMs === 'number' && f.heartbeatMs > 0) { this.heartbeatMs = this.o.heartbeatMs || f.heartbeatMs; }
    if (f.tokenExpiresAt) { this.tokenExpiresAt = Number(f.tokenExpiresAt); }
    if (this.swap && ctx.transport === this.swap.next) {
      const old = this.swap.old;
      this.swap = null;
      this.transport = ctx.transport;
      try { old.close(); } catch (e) { /* closed */ }
      this._scheduleRefresh();
      return;
    }
    this._opened({ resumed: !!f.resumed });
  }

  /** @private the connection is up (welcome, or raw's open) */
  _opened(info) {
    if (this.stats.connects > 1) { this.stats.reconnects++; }
    this.backoff.reset();
    this.refusals = 0;
    this.floorMs = 0;
    const watch = this.transportKind === 'sse' && !this.o.heartbeatMs ? 0 : Math.round(this.heartbeatMs * (this.protocol === 'raw' ? 1 : 1.5));
    this.heart.setTimeoutMs(watch);
    this.heart.beat();
    this._setState('open', { resumed: !!info.resumed });
    if (this.protocol === 1) {
      const unsent = Array.from(this.subs.values()).filter((s) => !s.held);
      if (unsent.length && this.transportKind === 'sse') { this._rescope(); }
      else if (unsent.length) { unsent.forEach((s) => { s.held = true; }); this._send(unsent.map((s) => this._subFrame(s))); }
      this._resend();
      this._ping();
      this._scheduleRefresh();
    } else {
      this.subs.forEach((s) => s.sub._setState('active'));
    }
  }

  /** @private the connection opened: what an earlier page left is taken back (one tab at a time), then everything not acknowledged is sent
   again, in order, and the requests that wait */
  _resend() {
    this.stats.retried += this.outbox.resend();
    const reqs = [];
    this.reqs.forEach((r) => { if (!r.sent) { r.sent = true; reqs.push(r.frame); } });
    if (reqs.length) { this._send(reqs); }
    if (this.outbox.wantsRestore()) { this.outbox.restore(() => this._pumpOut()).catch(() => this._pumpOut()); } else { this._pumpOut(); }
  }

  /** @private sends what waits in the queue, in order, while the connection is open and the socket has room */
  _pumpOut() {
    if (this.state !== 'open' || !this.transport) { return 0; }
    if (this._congested()) { this._schedulePump(); return 0; }
    const list = this.outbox.takeUnsent();
    if (!list.length) { return 0; }
    this._send(list.map((e) => e.frame)).then((ok) => {
      list.forEach((e) => {
        if (!ok) { e.sent = false; return; }
        if (!e.ack) { this.outbox.done(e.id, false); }
      });
    });
    return list.length;
  }

  /** @private */
  _err(f) {
    const words = String(f.detail || f.code);
    if (f.id !== undefined && f.id !== null) {
      const s = this.subs.get(Number(f.id)) || this.subs.get(f.id);
      if (s) { s.sub._setState('refused', f.code + ': ' + words); this._forgetSub(s.sub); return; }
      if (this.outbox.has(String(f.id))) {
        if (f.retry === false || !retryable(f.code)) { this.stats.failed++; this.outbox.remove(String(f.id), new EventError(f.code, words, { retry: false })); }
        return;
      }
      const r = this.reqs.get(String(f.id));
      if (r) { this.reqs.delete(String(f.id)); r.reject(new EventError(f.code, words)); return; }
      return;
    }
    if (f.code === 'auth.expired' || f.code === 'auth.required') { this._authRefused(401, words); return; }
    this.events._error(new EventError(f.code, words), { where: 'server' });
  }

  /** @private */
  _bye(f) {
    const r = f.reconnect;
    if (f.code === 'auth.expired' || f.code === 'auth.required') { this._authRefused(401, String(f.detail || f.code)); return; }
    if (r === false || f.code === 'forbidden' || f.code === 'version') { this._refuse(f.code, String(f.detail || 'the server said goodbye')); return; }
    if (r && typeof r.afterMs === 'number') { this.floorMs = r.afterMs; }
    this._dropped({ reason: 'the server closed: ' + (f.detail || f.code), code: 1000 });
  }

  // ---- dropping and refusing -------------------------------------------------------------------------------------------------------

  /** @private a failure that trying again cannot cure */
  _refuse(code, words) {
    this.stopped = true;
    this._stopTimers();
    this._teardown();
    this._setState('refused', { reason: words, code: code });
    this.subs.forEach((s) => { if (s.sub.state === 'pending') { s.sub._setState('refused', words); } });
  }

  /** @private refused for want of a token: ask auth once more, then give up */
  _authRefused(status, words) {
    if (this.o.auth && this.o.wsAuth !== 'cookie' && this.refusals === 0) {
      this.refusals++;
      this.events._emitOwn('$events.auth', { reason: 'refused', ok: false, detail: words });
      this._stopTimers();
      this._teardown();
      this._connectNow('refused', status);
      return;
    }
    this._refuse('auth', 'not signed in: ' + words);
  }

  /** @private the transport ended: back off and try again, or stop when trying cannot help */
  _dropped(info) {
    const i = info || {};
    this._stopTimers();
    this._teardown();
    if (this.stopped) { return; }
    const code = i.code || 0, status = i.status || 0;
    if (code === CLOSE_CODES.badFrame || code === CLOSE_CODES.forbidden || status === 403 || status === 400 || status === 404 || status === 405) {
      this._refuse(code === CLOSE_CODES.badFrame || status === 400 ? 'invalid' : status === 404 || status === 405 ? 'not.found' : 'forbidden',
        i.reason + (status ? ' (HTTP ' + status + ')' : code ? ' (close ' + code + ')' : ''));
      return;
    }
    if (code === CLOSE_CODES.unauthenticated || status === 401) { this._authRefused(401, i.reason || 'refused'); return; }
    this.subs.forEach((s) => { s.held = false; });
    const nav = this.win && this.win.navigator;
    if (nav && nav.onLine === false) { this._setState('offline', { reason: i.reason || 'the browser is offline' }); return; }
    const wait = this.backoff.next(this.floorMs);
    this._setState('reconnecting', { waitMs: wait, reason: i.reason || 'the connection dropped' });
    this.timers.retry = this.clock.setTimeout(() => { this.timers.retry = null; this._connectNow('connect'); }, wait);
  }

  /** @private */
  _teardown() {
    this.heart.stop();
    if (this.swap) { try { this.swap.next.close(); } catch (e) { /* closed */ } this.swap = null; }
    if (this.transport) { const t = this.transport; this.transport = null; try { t.close(1000); } catch (e) { /* closed */ } }
  }

  /** @private */
  _stopTimers() {
    ['ping', 'refresh', 'rescope', 'pump'].forEach((k) => { if (this.timers[k] !== null) { this.clock.clearTimeout(this.timers[k]); this.timers[k] = null; } });
  }

  // ---- heartbeat and token refresh -------------------------------------------------------------------------------------------------

  /** @private a WebSocket pings every third of the heartbeat */
  _ping() {
    if (!this.transport || this.transport.kind !== 'websocket') { return; }
    const every = Math.max(1000, Math.round(this.heartbeatMs / 3));
    const tick = () => {
      this.timers.ping = null;
      if (this.state !== 'open' || !this.transport) { return; }
      this._send([{ t: 'ping', ts: this.clock.now() }]);
      this.timers.ping = this.clock.setTimeout(tick, every);
    };
    this.timers.ping = this.clock.setTimeout(tick, every);
  }

  /** @private a minute before the token runs out, ask for a fresh one */
  _scheduleRefresh() {
    if (this.timers.refresh !== null) { this.clock.clearTimeout(this.timers.refresh); this.timers.refresh = null; }
    if (!this.o.auth || !this.tokenExpiresAt || this.o.wsAuth === 'cookie') { return; }
    const wait = Math.max(0, this.tokenExpiresAt - 60000 - this.clock.now());
    this.timers.refresh = this.clock.setTimeout(() => { this.timers.refresh = null; this._refresh(); }, wait);
  }

  /** @private a fresh token for the open connection: an auth frame (WebSocket), or a new stream resumed before the old one closes (fetch) */
  _refresh() {
    if (this.state !== 'open' || !this.o.auth) { return; }
    this._askAuth('expiring').then(() => {
      if (this.state !== 'open' || !this.transport) { return; }
      if (this.transport.kind === 'fetch') { this._openTransport(this.transport); return; }
      this._send([{ t: 'auth', token: this.token }]);
    }, (e) => this.events._error(e, { where: 'auth' }));
  }

  // ---- the scope's calls (the Events calls these) ---------------------------------------------------------------------------------

  /** @private frames out: now when open, else dropped (what must survive waits in its own queue) */
  _send(frames) {
    if (!this.transport || this.state !== 'open') { return Promise.resolve(false); }
    this.stats.framesOut += frames.length;
    return this.transport.send(frames).then(() => true, (e) => { this.events._error(e, { where: 'send' }); return false; });
  }

  /** @private whether the subscriptions travel with the stream's opening (Server-Sent Events: in the address or the body) */
  _inAddress() { return this.transportKind === 'sse' || this.transportKind === 'fetch'; }

  /** @private */
  _subFrame(s) {
    const f = { t: 'sub', id: s.id, topic: s.sub.pattern };
    const o = s.sub.o;
    if (o.retained) { f.retained = true; }
    if (o.conflate && o.conflate.server && this.can.indexOf('conflate') >= 0) {
      f.conflate = { key: true };
      const every = (o.deliver && o.deliver.everyMs) || o.conflate.everyMs;
      if (every) { f.conflate.everyMs = every; }
    }
    const after = this.sequencer && o.from !== 'now' ? this.sequencer.after(s.sub, s) : undefined;
    s.after = after && Object.keys(after).length ? after : null;
    if (s.after) { f.after = after; }
    return f;
  }

  /**
   * Holds a subscription at the server ('sub' now when open, and after every reconnect).
   * @param {Object} sub a Subscription
   * @returns {boolean} true: it waits for the server's answer
   */
  subscribe(sub) {
    if (this.bySub.has(sub)) { return this.protocol === 1; }
    const s = { id: this.nextSub++, sub: sub, streams: {} };
    this.subs.set(s.id, s);
    this.bySub.set(sub, s);
    if (this.protocol === 'raw') { if (this.state === 'open') { sub._setState('active'); return false; } return true; }
    if (this.state === 'open') {
      if (this.transportKind === 'sse') { this._rescope(); } else { s.held = true; this._send([this._subFrame(s)]); }
    }
    return true;
  }

  /**
   * Gives a subscription back.
   * @param {Object} sub
   * @returns {void}
   */
  unsubscribe(sub) {
    const s = this.bySub.get(sub);
    if (!s) { return; }
    this._forgetSub(sub);
    if (this.protocol === 1 && this.state === 'open') {
      if (this.transportKind === 'sse') { this._rescope(); } else { this._send([{ t: 'unsub', id: s.id }]); }
    }
  }

  /** @private */
  _forgetSub(sub) {
    const s = this.bySub.get(sub);
    if (!s) { return; }
    this.bySub.delete(sub);
    this.subs.delete(s.id);
    if (this.sequencer && this.sequencer.unsubscribed) { this.sequencer.unsubscribed(sub, s); }
  }

  /**
   * Whether a subscription still waits for the server.
   * @param {Object} sub
   * @returns {boolean}
   */
  pending(sub) { return this.bySub.has(sub) && sub.state === 'pending'; }

  /** @private EventSource's subscriptions are in its address: a change reconnects (with resume), once per turn */
  _rescope() {
    if (this.timers.rescope !== null) { return; }
    this.timers.rescope = this.clock.setTimeout(() => { this.timers.rescope = null; if (this.state === 'open') { this._connectNow('connect'); } }, 0);
  }

  /**
   * Publishes a message to the server: sent now, or held (in order) until the connection opens; resolves when acknowledged.
   * @param {Object} m the message
   * @param {Object} o the publish's options
   * @returns {Promise<{acked: boolean}>}
   */
  publish(m, o) {
    if (this.protocol === 'raw') { return Promise.reject(new EventError('protocol', 'publishing needs the events protocol; this connection is raw (it only listens)')); }
    if (this.state === 'open' && this.can.length && this.can.indexOf('publish') < 0) {
      return Promise.reject(new EventError('not.offered', 'the server takes no publishes (its welcome does not offer publish)'));
    }
    const frame = { t: 'pub', id: m.id, topic: m.topic, data: m.data, time: m.time };
    if (m.key !== undefined) { frame.key = m.key; }
    if (m.kind !== undefined) { frame.kind = m.kind; }
    if (m.meta !== undefined) { frame.meta = m.meta; }
    const ack = !o || o.ack !== false;
    if (!ack) { frame.ack = false; }
    const size = encodeFrames([frame]).length;
    if (size > (this.serverLimits.frameBytes || this.events.limits.frameBytes)) {
      return Promise.reject(new EventError('limit.size', 'the message is ' + size + ' bytes; a frame is at most ' + (this.serverLimits.frameBytes || this.events.limits.frameBytes)));
    }
    if (o && o.queue === false && this.state !== 'open') { return Promise.reject(new EventError('not.connected', 'not connected (' + this.state + '), and the publish asked not to wait')); }
    if ((this.state === 'refused' || this.state === 'closed') && !this.suspended) { return Promise.reject(new EventError('not.connected', 'the connection is ' + this.state)); }
    this.stats.published++;
    return new Promise((resolve, reject) => {
      try {
        this.outbox.add(frame, { persist: !!(o && o.queue === 'persist'), ttlMs: o && o.ttlMs, ack: ack, resolve: resolve, reject: reject });
      } catch (e) { reject(e); return; }
      if (o && o.signal) {
        o.signal.addEventListener('abort', () => {
          const e = this.outbox.entries.get(m.id);
          if (e && !e.sent) { this.outbox.remove(m.id, new EventError('aborted', 'the publish was given up before it was sent')); }
        }, { once: true });
      }
      this._pumpOut();
    });
  }

  /** @private the socket holds too much unsent: publishing waits */
  _congested() { return !!(this.transport && this.transport.bufferedAmount && this.transport.bufferedAmount() > BUFFERED_LIMIT); }

  /** @private while congested, look again every 50 ms; send what waits once the socket has room */
  _schedulePump() {
    if (this.timers.pump !== null) { return; }
    this.timers.pump = this.clock.setTimeout(() => {
      this.timers.pump = null;
      if (this.state !== 'open') { return; }
      this._pumpOut();
    }, 50);
  }

  /**
   * Sends a request and waits for its answer (res); the Events times it out. Aborting tells the server (cancel).
   * @param {Object} m the message
   * @param {{timeoutMs: number, signal: AbortSignal}} o
   * @returns {Promise<Object>} the answer, a message
   */
  request(m, o) {
    if (this.protocol === 'raw') { return Promise.reject(new EventError('protocol', 'request and reply need the events protocol; this connection is raw')); }
    if ((this.state === 'refused' || this.state === 'closed') && !this.suspended) { return Promise.reject(new EventError('not.connected', 'the connection is ' + this.state)); }
    const frame = { t: 'req', id: m.id, topic: m.topic, data: m.data, ttl: o.timeoutMs };
    if (m.kind !== undefined) { frame.kind = m.kind; }
    if (m.meta !== undefined) { frame.meta = m.meta; }
    return new Promise((resolve, reject) => {
      const r = { frame: frame, resolve: resolve, reject: reject, sent: false };
      this.reqs.set(m.id, r);
      if (this.state === 'open') { r.sent = true; this._send([frame]); }
      if (o.signal) {
        o.signal.addEventListener('abort', () => {
          if (!this.reqs.has(m.id)) { return; }
          this.reqs.delete(m.id);
          if (r.sent) { this._send([{ t: 'cancel', id: m.id }]); }
        }, { once: true });
      }
    });
  }

  /**
   * Sends what waits now (it is sent on its own when the connection opens).
   * @returns {Promise<number>} how many were sent
   */
  flush() {
    if (this.state !== 'open') { return Promise.resolve(0); }
    return Promise.resolve(this._pumpOut());
  }

  /**
   * Empties the queue: what waits is not sent (each publish rejects).
   * @returns {Promise<number>} how many were dropped
   */
  clearOutbox() { return this.outbox.clear(); }

  /**
   * The person signs out: the queue emptied, the stream positions forgotten.
   * @returns {Promise<void>}
   */
  signOut() {
    if (this.sequencer && this.sequencer.forget) { this.sequencer.forget(); }
    this.lastEventId = null;
    this.session = null;
    return this.clearOutbox().then(() => undefined);
  }

  /**
   * Opens again now (a person pressed Reconnect; also after close or refused).
   * @returns {void}
   */
  reconnect() {
    this.stopped = false;
    this.refusals = 0;
    this.backoff.reset();
    this._stopTimers();
    this._connectNow('connect');
  }

  /**
   * Closes and stops trying (a WebSocket says bye first).
   * @returns {void}
   */
  close() {
    if (this.transport && this.state === 'open' && this.protocol === 1 && this.transport.kind === 'websocket') { this._send([{ t: 'bye' }]); }
    this.stopped = true;
    this.suspended = false;
    this.attempt++;
    this.clock.clearTimeout(this.timers.retry);
    this.timers.retry = null;
    this._stopTimers();
    this._teardown();
    if (this.state !== 'idle' && this.state !== 'closed') { this._setState('closed'); }
  }

  /**
   * Closes, rejects what waits and forgets the page's listeners.
   * @returns {void}
   */
  destroy() {
    this.close();
    const gone = new EventError('closed', 'the connection was destroyed');
    this.outbox.destroy(gone);
    this.reqs.forEach((r) => r.reject(gone));
    this.reqs.clear();
    if (this.timers.idle !== null) { this.clock.clearTimeout(this.timers.idle); this.timers.idle = null; }
    if (this.win && this.win.removeEventListener) {
      this.win.removeEventListener('online', this._onOnline);
      this.win.removeEventListener('pagehide', this._onHide);
      this.win.removeEventListener('pageshow', this._onShow);
    }
    const doc = this._doc();
    if (doc && doc.removeEventListener) {
      doc.removeEventListener('visibilitychange', this._onVisible);
      doc.removeEventListener('freeze', this._onHide);
      doc.removeEventListener('resume', this._onShow);
    }
  }

  /**
   * The connection's figures.
   * @returns {Object}
   */
  metrics() {
    const r = this.stats.rtts.slice().sort((a, b) => a - b);
    const q = (p) => (r.length ? r[Math.min(r.length - 1, Math.floor(p * r.length))] : null);
    return { state: this.state, transport: this.transportKind, protocol: this.protocol, session: this.session, connects: this.stats.connects,
      reconnects: this.stats.reconnects, connectedMs: this.stats.connectedMs + (this.stats.openedAt ? this.clock.now() - this.stats.openedAt : 0),
      rtt: { last: this.stats.rtt, p50: q(0.5), p99: q(0.99) }, offset: this.stats.offset, framesIn: this.stats.framesIn, framesOut: this.stats.framesOut,
      bytesIn: this.stats.bytesIn, unknownFrames: this.stats.unknown, badFrames: this.stats.bad,
      out: { published: this.stats.published, acked: this.stats.acked, retried: this.stats.retried, failed: this.stats.failed, waiting: this.outbox.size },
      outbox: this.outbox.metrics(),
      requests: { pending: this.reqs.size } };
  }
}

/** @private codes a publish is sent again after */
function retryable(code) { return ['limit.rate', 'slow', 'restart', 'internal', 'timeout'].indexOf(code) >= 0; }

/**
 * A token as base64url (a WebSocket sub-protocol's characters).
 * @param {string} text
 * @returns {string}
 */
export function base64url(text) {
  const bytes = new TextEncoder().encode(String(text));
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  const b64 = typeof btoa === 'function' ? btoa(bin) : globalThis.Buffer.from(bin, 'binary').toString('base64');
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * The address connect() uses when given none: the host's configured events endpoint (configure({ endpoints: { events } })), or ''.
 * @returns {string}
 */
export function eventsEndpoint() { return endpoint('events'); }
