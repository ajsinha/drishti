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
/* The 'tabs' scope: the same application in the person's other tabs and windows, over a BroadcastChannel whose name carries the
   namespace (an application and a signed-in person), so a second person on the same origin shares nothing. Messages travel as JSON text,
   checked before use (version, frame type, topic grammar, id, size): data, never code. Only topics routed to 'tabs' leave the page, never
   '$' topics. Request and reply reach the first tab that answers; sign-out reaches every tab. The leader tab (one per namespace, held by
   a Web Lock) is the shared hub's fallback when there is no SharedWorker. */
import { EventError, AbortError, makeMessage, isOwnTopic, matches, MAX_ID_LENGTH } from './drishti_envelope.js';
import { isTopic } from '../../drishti-core/src/drishti_index.js';
import { byteLength } from './drishti_protocol.js';

/** The version of the tab frames. */
export const TABS_VERSION = 1;

const TAB_FRAMES = ['msg', 'req', 'res', 'err', 'cancel', 'signout'];
const WIRE_FIELDS = ['id', 'topic', 'data', 'time', 'source', 'seq', 'stream', 'key', 'kind', 're', 'meta', 'retained', 'replayed', 'epoch'];

/**
 * A message as a plain object to send (the fields of the envelope only: no scope, no receivedAt, no signal).
 * @param {Object} m
 * @returns {Object}
 */
export function wireOf(m) {
  const out = {};
  WIRE_FIELDS.forEach((k) => { if (m[k] !== undefined) { out[k] = m[k]; } });
  return out;
}

/**
 * A message received from another tab or the shared hub, checked: a Message, or words saying what is wrong.
 * @param {*} w
 * @param {string} scope
 * @param {number} now
 * @returns {(Object|string)}
 */
export function messageFromWire(w, scope, now) {
  if (!w || typeof w !== 'object' || Array.isArray(w)) { return 'not a message'; }
  if (!isTopic(w.topic) || isOwnTopic(w.topic)) { return 'no valid topic'; }
  if (typeof w.id !== 'string' || !w.id || w.id.length > MAX_ID_LENGTH) { return 'no valid id'; }
  if (w.seq !== undefined && !(typeof w.seq === 'number' && isFinite(w.seq))) { return 'a seq that is not a number'; }
  const f = {};
  WIRE_FIELDS.forEach((k) => { if (w[k] !== undefined && w[k] !== null) { f[k] = w[k]; } });
  if (f.data === undefined) { f.data = null; }
  if (typeof f.time !== 'number') { f.time = now; }
  if (typeof f.source !== 'string') { f.source = scope === 'tabs' ? 'tab' : 'server'; }
  if (f.meta !== undefined && (typeof f.meta !== 'object' || Array.isArray(f.meta))) { delete f.meta; }
  ['key', 'kind', 'stream', 're'].forEach((k) => { if (f[k] !== undefined) { f[k] = String(f[k]); } });
  f.scope = scope;
  f.receivedAt = now;
  return makeMessage(f);
}

/**
 * The BroadcastChannel class of the environment (a stand-in's, the window's), or null.
 * @param {Object} [env]
 * @returns {?Function}
 */
export function broadcastChannelOf(env) {
  if (env && env.BroadcastChannel) { return env.BroadcastChannel; }
  if (env && env.noTabs) { return null; }
  return typeof window !== 'undefined' && window.BroadcastChannel ? window.BroadcastChannel : null;
}

/**
 * The Web Locks of the environment (a stand-in's, the browser's), or null.
 * @param {Object} [env]
 * @returns {?{request: Function}}
 */
export function locksOf(env) {
  if (env && env.locks) { return env.locks; }
  return typeof navigator !== 'undefined' && navigator.locks && navigator.locks.request ? navigator.locks : null;
}

/**
 * The 'tabs' scope of an Events: an adapter over one BroadcastChannel, opened when first used.
 */
export class TabsScope {
  /**
   * @param {Object} events the Events it serves
   * @param {{namespace: string, env: Object}} [o]
   */
  constructor(events, o) {
    this.events = events;
    this.o = Object.assign({}, o);
    this.Channel = broadcastChannelOf(this.o.env);
    /** @type {string} the channel's name: the namespace keeps applications and people apart */
    this.name = 'drishti-events.tabs' + (this.o.namespace ? '.' + this.o.namespace : '');
    /** @type {string} this tab's id */
    this.id = 'tab:' + events.ids.tab;
    this.channel = null;
    this.reqs = new Map();
    this.repliers = [];
    this.working = new Map();
    this.stats = { sent: 0, received: 0, bad: 0, requests: 0, answered: 0 };
    this.closed = false;
  }

  /** @private opens the channel on first use */
  _open() {
    if (this.channel || this.closed || !this.Channel) { return this.channel; }
    this.channel = new this.Channel(this.name);
    this.channel.onmessage = (e) => this._in(e && e.data);
    return this.channel;
  }

  /** @private */
  _post(frame) {
    const ch = this._open();
    if (!ch) { throw new EventError('tabs.unavailable', 'this browser has no BroadcastChannel: the tabs scope is off'); }
    const text = JSON.stringify(Object.assign({ v: TABS_VERSION, from: this.id }, frame));
    if (byteLength(text) > this.events.limits.frameBytes) { throw new EventError('limit.size', 'a message to the other tabs is at most ' + this.events.limits.frameBytes + ' bytes'); }
    this.stats.sent++;
    ch.postMessage(text);
  }

  /** @private one frame from another tab, checked before use */
  _in(text) {
    if (this.closed) { return; }
    let f;
    try {
      if (typeof text !== 'string' || byteLength(text) > this.events.limits.frameBytes) { throw new Error('too large or not text'); }
      f = JSON.parse(text);
    } catch (e) { this.stats.bad++; this.events._drop('invalid', null, null, 'a frame from another tab was not JSON text within the size limit'); return; }
    if (!f || f.v !== TABS_VERSION || TAB_FRAMES.indexOf(f.t) < 0 || typeof f.from !== 'string' || f.from === this.id) {
      if (!f || f.from !== this.id) { this.stats.bad++; this.events._drop('invalid', null, null, 'a frame from another tab of another version or type'); }
      return;
    }
    if (f.to && f.to !== this.id) { return; }
    this.stats.received++;
    const now = this.events.clock.now();
    switch (f.t) {
      case 'msg': {
        const m = messageFromWire(f.m, 'tabs', now);
        if (typeof m === 'string') { this.stats.bad++; this.events._drop('invalid', null, null, 'a message from another tab: ' + m); return; }
        this.events._receive(m);
        break;
      }
      case 'req': this._request(f, now); break;
      case 'res': case 'err': {
        const r = this.reqs.get(f.re);
        if (!r) { return; }
        this.reqs.delete(f.re);
        if (f.t === 'err') { r.reject(new EventError(typeof f.code === 'string' ? f.code : 'reply.failed', typeof f.detail === 'string' ? f.detail : 'the other tab failed')); return; }
        const m = messageFromWire(f.m, 'tabs', now);
        if (typeof m === 'string') { r.reject(new EventError('invalid', 'the answer from another tab: ' + m)); return; }
        this.stats.answered++;
        r.resolve(m);
        break;
      }
      case 'cancel': { const w = this.working.get(f.re); if (w) { this.working.delete(f.re); w.abort(); } break; }
      case 'signout': if (this.events._signOutHere) { this.events._signOutHere('tabs'); } break;
      default: break;
    }
  }

  /** @private a request from another tab: the first replier here whose pattern matches answers (silence when none does) */
  _request(f, now) {
    const m0 = messageFromWire(f.m, 'tabs', now);
    if (typeof m0 === 'string') { this.stats.bad++; return; }
    const r = this.repliers.find((x) => matches(x.pattern, m0.topic));
    if (!r) { return; }
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (ctrl) { this.working.set(m0.id, ctrl); }
    const req = makeMessage(Object.assign({}, m0, { signal: ctrl ? ctrl.signal : undefined }));
    Promise.resolve().then(() => r.fn(req)).then((data) => {
      this.working.delete(m0.id);
      const ans = { id: this.events.ids.next(), topic: m0.topic, data: data === undefined ? null : data, time: this.events.clock.now(), source: this.events.source, re: m0.id };
      this._safePost({ t: 'res', to: f.from, re: m0.id, m: ans });
    }, (e) => {
      this.working.delete(m0.id);
      this._safePost({ t: 'err', to: f.from, re: m0.id, code: (e && e.code) || 'reply.failed', detail: (e && (e.detail || e.message)) || String(e) });
    });
  }

  /** @private */
  _safePost(frame) { try { this._post(frame); } catch (e) { this.events._error(e, { where: 'tabs' }); } }

  /**
   * Sends a message to the other tabs.
   * @param {Object} m
   * @returns {Promise<{acked: boolean}>}
   */
  publish(m) {
    try { this._post({ t: 'msg', m: wireOf(m) }); } catch (e) { return Promise.reject(e); }
    return Promise.resolve({ acked: true });
  }

  /**
   * Asks the other tabs; the first answer wins (the Events times it out). Aborting tells the tab that works on it.
   * @param {Object} m
   * @param {{signal: AbortSignal}} o
   * @returns {Promise<Object>}
   */
  request(m, o) {
    return new Promise((resolve, reject) => {
      this.reqs.set(m.id, { resolve: resolve, reject: reject });
      if (this.reqs.size > this.events.limits.pendingRequests) { this.reqs.delete(this.reqs.keys().next().value); }
      try { this._post({ t: 'req', m: wireOf(m) }); this.stats.requests++; } catch (e) { this.reqs.delete(m.id); reject(e); return; }
      if (o && o.signal) {
        o.signal.addEventListener('abort', () => {
          if (!this.reqs.has(m.id)) { return; }
          this.reqs.delete(m.id);
          this._safePost({ t: 'cancel', re: m.id });
          reject(new AbortError('the request ' + m.topic + ' was given up'));
        }, { once: true });
      }
    });
  }

  /**
   * Listening needs the channel open; nothing is asked of anyone.
   * @returns {boolean} false: active at once
   */
  subscribe() { this._open(); return false; }

  /**
   * Nothing to give back.
   * @returns {void}
   */
  unsubscribe() {}

  /**
   * Answers requests from the other tabs.
   * @param {string} pattern
   * @param {function(Object): *} fn
   * @returns {function(): void}
   */
  reply(pattern, fn) {
    this._open();
    const r = { pattern: pattern, fn: fn };
    this.repliers.push(r);
    return () => { const i = this.repliers.indexOf(r); if (i >= 0) { this.repliers.splice(i, 1); } };
  }

  /**
   * Tells the other tabs the person signed out (each empties its queue and forgets its stream positions).
   * @returns {Promise<void>}
   */
  signOut() {
    try { this._post({ t: 'signout' }); } catch (e) { /* no channel: nothing else to tell */ }
    return Promise.resolve();
  }

  /**
   * The scope's figures.
   * @returns {Object}
   */
  metrics() { return Object.assign({ channel: this.name, open: !!this.channel, pendingRequests: this.reqs.size }, this.stats); }

  /**
   * Closes the channel.
   * @returns {void}
   */
  destroy() {
    this.closed = true;
    this.reqs.forEach((r) => r.reject(new EventError('closed', 'the Events was closed')));
    this.reqs.clear();
    this.working.forEach((c) => c.abort());
    this.working.clear();
    if (this.channel) { try { this.channel.close(); } catch (e) { /* closed */ } this.channel = null; }
  }
}

/**
 * The leader among the tabs of one namespace: the tab holding a Web Lock. When it closes, the next tab waiting takes over.
 */
export class Leader {
  /**
   * @param {{name: string, env: Object, onLead: function(): void}} o
   */
  constructor(o) {
    this.o = o;
    this.locks = locksOf(o.env);
    /** @type {boolean} */ this.leading = false;
    this.release = null;
    this.stopped = false;
  }

  /**
   * Whether leader election is possible here.
   * @returns {boolean}
   */
  get possible() { return !!this.locks; }

  /**
   * Waits for the lock; onLead runs when this tab leads (held until stop()).
   * @returns {void}
   */
  start() {
    if (!this.locks || this.stopped) { return; }
    const held = new Promise((resolve) => { this.release = resolve; });
    Promise.resolve(this.locks.request(this.o.name, () => {
      if (this.stopped) { return undefined; }
      this.leading = true;
      try { this.o.onLead(); } catch (e) { if (typeof console !== 'undefined') { console.error('drishti-events: the leader tab failed to start', e); } }
      return held;
    })).catch(() => { /* the lock was given up */ });
  }

  /**
   * Gives the lock back (the next tab leads).
   * @returns {void}
   */
  stop() {
    this.stopped = true;
    this.leading = false;
    if (this.release) { this.release(); this.release = null; }
  }
}
