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
/* The live client: one connection to a server's feed over Server-Sent Events or a WebSocket, kept up. It opens with the first
   subscriber and closes with the last; a dropped connection is opened again after a back-off (exponential, capped, jittered; at once
   when the browser comes back online); a silent one is taken as dead after the heartbeat (any message, a ping or the server's
   heartbeat event counts as life; on a WebSocket the client pings); and it resumes from the last event id it saw (SSE: the
   lastEventId parameter on the new address, as EventSource's own Last-Event-ID header only travels on its own reconnects; WebSocket: a
   resume message first), so the server sends what was missed. A message is JSON; heartbeats and pongs are not passed on. The client is
   itself a feed ({subscribe}): drishti-grid's connectLive takes it, and pipeToGrid puts backpressure between them.

   A third transport, 'fetch', reads Server-Sent Events with fetch, so headers travel (a bearer token: an EventSource cannot send one);
   the address may be a function, asked at every connect (a channel whose subscriptions are in its address), and eventField writes each
   message's SSE event name into it (a stream whose event names are its message types: view, frame, gone).

   States: idle, connecting, open, reconnecting, closed; each change is a state event with the attempt and the wait. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { Backoff, Heartbeat } from './drishti_backoff.js';

/** The client's states. */
export const LIVE_STATES = Object.freeze(['idle', 'connecting', 'open', 'reconnecting', 'closed']);

/**
 * @typedef {Object} LiveClientOptions
 * @property {(string|function(): string)} url the feed's address (http(s) for SSE, ws(s) for a WebSocket), or a function asked at
 *   every connect
 * @property {('sse'|'websocket'|'fetch')} [transport] default: websocket for ws:// and wss://, else sse; fetch: SSE read with fetch
 * @property {(Object<string, string>|function({status: number}): (Object<string, string>|Promise<Object<string, string>>))} [headers]
 *   fetch: the request's headers, or a function of the last failure (status 401 after a refused token) that answers them
 * @property {string} [credentials='same-origin'] fetch: the request's credentials
 * @property {string} [eventField] each message gets its SSE event name under this field (when it has none of its own)
 * @property {Function} [fetch] fetch: for tests
 * @property {number} [heartbeatMs=45000] no message for this long: reconnect (0: no watchdog)
 * @property {number} [pingMs] WebSocket: send {"type":"ping"} this often (default heartbeatMs / 3)
 * @property {{baseMs: number, maxMs: number, factor: number, jitter: boolean}} [backoff]
 * @property {string} [resumeParam='lastEventId'] SSE: the parameter that carries the last event id
 * @property {string[]} [events] SSE event names that carry messages (default ['message'])
 * @property {boolean} [withCredentials=false]
 * @property {string[]} [protocols] WebSocket sub-protocols
 * @property {Function} [EventSource] for tests or a polyfill
 * @property {Function} [WebSocket] for tests
 * @property {Object} [window] the window (online and offline events; default the page's)
 */

/**
 * A live connection with reconnect, heartbeat and resume.
 */
export class LiveClient {
  /**
   * @param {LiveClientOptions} o
   */
  constructor(o) {
    if (!o || !o.url) { throw new Error('a live client needs the feed\'s url'); }
    this.o = Object.assign({ heartbeatMs: 45000, resumeParam: 'lastEventId', events: ['message'] }, o);
    this.transport = this.o.transport || (/^wss?:/i.test(this.url()) ? 'websocket' : 'sse');
    if (['sse', 'websocket', 'fetch'].indexOf(this.transport) < 0) { throw new Error('no live transport ' + this.transport + ' (sse, websocket, fetch)'); }
    this.lastStatus = 0;
    /** @type {EventBus} state {state, attempt, waitMs}, error {error}, message {message} */
    this.events = new EventBus();
    this.backoff = new Backoff(this.o.backoff);
    this.heart = new Heartbeat({ timeoutMs: this.o.heartbeatMs, onTimeout: () => this.dropped(new Error('no message or heartbeat for ' + Math.round(this.o.heartbeatMs / 1000) + ' s')) });
    this.subs = new Set();
    this.state = 'idle';
    this.lastEventId = null;
    this.conn = null;
    this.retry = 0;
    this.stats = { messages: 0, connects: 0, reconnects: 0, heartbeats: 0 };
    const win = this.o.window || (typeof window !== 'undefined' ? window : null);
    this.win = win;
    this.onOnline = () => { if (this.state === 'reconnecting') { this.connectNow(); } };
    if (win && win.addEventListener) { win.addEventListener('online', this.onOnline); }
  }

  /**
   * Listens to the client's own events: state, error, message.
   * @param {string} name
   * @param {function(Object, string): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * Receives every message (the connection opens with the first subscriber and closes with the last).
   * @param {function(Object): void} fn
   * @returns {function(): void} unsubscribes
   */
  subscribe(fn) {
    this.subs.add(fn);
    if (this.state === 'idle' || this.state === 'closed') { this.open(); }
    return () => { this.subs.delete(fn); if (!this.subs.size) { this.close(); } };
  }

  /** @private */
  setState(s, extra) { this.state = s; this.events.emit('state', Object.assign({ state: s, attempt: this.backoff.attempt }, extra)); }

  /**
   * Opens the connection (subscribe does it).
   * @returns {void}
   */
  open() { this.stopped = false; this.connectNow(); }

  /** @private the feed's address now */
  url() { return typeof this.o.url === 'function' ? String(this.o.url()) : this.o.url; }

  /** @private the address with the resume parameter */
  address() {
    const u = this.url();
    if (this.transport === 'websocket' || this.lastEventId === null) { return u; }
    return u + (u.indexOf('?') >= 0 ? '&' : '?') + encodeURIComponent(this.o.resumeParam) + '=' + encodeURIComponent(this.lastEventId);
  }

  /** @private */
  connectNow() {
    clearTimeout(this.retry);
    this.teardown();
    this.setState('connecting');
    this.stats.connects++;
    try { this.conn = this.transport === 'sse' ? this.sse() : this.transport === 'fetch' ? this.fetchSse() : this.ws(); }
    catch (e) { this.dropped(e); }
  }

  /** @private */
  sse() {
    const ES = this.o.EventSource || globalThis.EventSource;
    if (!ES) { throw new Error('this page has no EventSource'); }
    const es = new ES(this.address(), { withCredentials: !!this.o.withCredentials });
    const onMsg = (e) => this.received(e.data, e.lastEventId, e.type);
    this.o.events.forEach((n) => es.addEventListener(n, onMsg));
    es.addEventListener('heartbeat', () => this.alive());
    es.addEventListener('open', () => this.opened());
    es.addEventListener('error', () => { if (es.readyState === 2 || this.state !== 'open') { this.dropped(new Error('the live feed ' + this.o.url + ' failed')); } else { this.dropped(new Error('the live feed ' + this.o.url + ' dropped')); } });
    return { close: () => { this.o.events.forEach((n) => es.removeEventListener(n, onMsg)); es.close(); }, raw: es };
  }

  /** @private */
  ws() {
    const WS = this.o.WebSocket || globalThis.WebSocket;
    if (!WS) { throw new Error('this page has no WebSocket'); }
    const ws = new WS(this.url(), this.o.protocols);
    let ping = 0;
    ws.addEventListener('open', () => {
      if (this.lastEventId !== null) { ws.send(JSON.stringify({ type: 'resume', lastEventId: this.lastEventId })); }
      const every = this.o.pingMs || (this.o.heartbeatMs ? Math.round(this.o.heartbeatMs / 3) : 0);
      if (every) { ping = setInterval(() => { try { ws.send('{"type":"ping"}'); } catch (e) { /* closing */ } }, every); }
      this.opened();
    });
    ws.addEventListener('message', (e) => this.received(e.data, null));
    ws.addEventListener('close', (e) => { clearInterval(ping); if (!this.closing) { this.dropped(new Error('the live feed closed' + (e && e.code ? ' (' + e.code + ')' : ''))); } });
    ws.addEventListener('error', () => { /* close follows */ });
    return { close: () => { clearInterval(ping); this.closing = true; try { ws.close(); } catch (e) { /* already */ } this.closing = false; }, raw: ws };
  }

  /** @private Server-Sent Events read with fetch: the headers travel; a refusal's status is kept for the next headers call */
  fetchSse() {
    const F = this.o.fetch || globalThis.fetch, ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (!F) { throw new Error('this page has no fetch'); }
    let closed = false;
    const run = async () => {
      const hd = typeof this.o.headers === 'function' ? await this.o.headers({ status: this.lastStatus }) : (this.o.headers || {});
      const res = await F(this.address(), { headers: Object.assign({ Accept: 'text/event-stream' }, hd), credentials: this.o.credentials || 'same-origin',
        cache: 'no-store', signal: ctrl ? ctrl.signal : undefined });
      if (!res.ok) { this.lastStatus = res.status; throw Object.assign(new Error('the live feed answered ' + res.status), { status: res.status }); }
      this.lastStatus = 0;
      this.opened();
      const reader = res.body.getReader(), dec = new TextDecoder();
      let buf = '';
      for (;;) {
        const r = await reader.read();
        if (r.done || closed) { break; }
        const got = parseSse(buf + dec.decode(r.value, { stream: true }));
        buf = got.rest;
        got.events.forEach((e) => {
          if (e.event === 'heartbeat' || (e.data === '' && e.comment)) { this.alive(); return; }
          if (this.o.events.indexOf(e.event) >= 0) { this.received(e.data, e.id, e.event); }
        });
      }
      throw new Error('the live feed ' + this.url() + ' ended');
    };
    run().catch((e) => { if (!closed) { this.events.emit('error', { error: e, status: e.status || 0 }); this.dropped(e); } });
    return { close: () => { closed = true; if (ctrl) { ctrl.abort(); } }, raw: ctrl };
  }

  /** @private */
  opened() {
    if (this.stats.connects > 1) { this.stats.reconnects++; }
    this.backoff.reset();
    this.heart.beat();
    this.setState('open', { resumedFrom: this.lastEventId });
  }

  /** @private */
  alive() { this.stats.heartbeats++; this.heart.beat(); }

  /** @private a message: parsed, its id kept, heartbeats kept back, its event name added when asked */
  received(data, eventId, eventName) {
    this.heart.beat();
    let m;
    try { m = typeof data === 'string' ? JSON.parse(data) : data; }
    catch (e) { this.events.emit('error', { error: new Error('the live feed sent text that is not JSON: ' + String(data).slice(0, 80)) }); return; }
    if (!m || typeof m !== 'object') { return; }
    if (m.type === 'heartbeat' || m.type === 'pong' || m.type === 'ping') { this.stats.heartbeats++; return; }
    if (this.o.eventField && eventName && m[this.o.eventField] === undefined) { m[this.o.eventField] = eventName; }
    const id = eventId || (m.eventId !== undefined ? m.eventId : m.seq !== undefined ? m.seq : null);
    if (id !== null && id !== undefined && id !== '') { this.lastEventId = String(id); }
    this.stats.messages++;
    this.events.emit('message', { message: m });
    this.subs.forEach((fn) => { try { fn(m); } catch (e) { this.events.emit('error', { error: e }); } });
  }

  /** @private the connection is gone: try again after the back-off */
  dropped(err) {
    this.heart.stop();
    this.teardown();
    if (this.stopped) { return; }
    this.events.emit('error', { error: err });
    const offline = this.win && this.win.navigator && this.win.navigator.onLine === false;
    const wait = this.backoff.next();
    this.setState('reconnecting', { waitMs: offline ? null : wait, offline: !!offline, error: err.message });
    if (!offline) { this.retry = setTimeout(() => this.connectNow(), wait); }
  }

  /** @private */
  teardown() { if (this.conn) { const c = this.conn; this.conn = null; c.close(); } }

  /**
   * Opens again now (a person pressed Reconnect).
   * @returns {void}
   */
  reconnect() { this.stopped = false; this.backoff.reset(); this.connectNow(); }

  /**
   * Closes the connection and stops trying.
   * @returns {void}
   */
  close() {
    this.stopped = true;
    clearTimeout(this.retry);
    this.heart.stop();
    this.teardown();
    if (this.state !== 'idle') { this.setState('closed'); }
  }

  /**
   * Closes and forgets the page's online listener.
   * @returns {void}
   */
  destroy() { this.close(); this.subs.clear(); if (this.win && this.win.removeEventListener) { this.win.removeEventListener('online', this.onOnline); } }
}

/**
 * Reads Server-Sent Events from text (the fetch transport, and anything else that has the raw stream): the whole events and what is left
 * for the next chunk. An event: its name (default message), its data lines joined by new lines, its id; a comment line alone is a sign
 * of life.
 * @param {string} text
 * @returns {{events: Array<{event: string, data: string, id: ?string, comment: boolean}>, rest: string}}
 */
export function parseSse(text) {
  const events = [];
  const blocks = String(text).replace(/\r\n?/g, '\n').split('\n\n');
  const rest = blocks.pop();
  blocks.forEach((b) => {
    let event = 'message', id = null, comment = false;
    const data = [];
    b.split('\n').forEach((line) => {
      if (line === '') { return; }
      if (line[0] === ':') { comment = true; return; }
      const at = line.indexOf(':'), field = at < 0 ? line : line.slice(0, at);
      let value = at < 0 ? '' : line.slice(at + 1);
      if (value[0] === ' ') { value = value.slice(1); }
      if (field === 'event') { event = value; } else if (field === 'data') { data.push(value); } else if (field === 'id') { id = value; }
    });
    if (data.length || comment) { events.push({ event: event, data: data.join('\n'), id: id, comment: comment && !data.length }); }
  });
  return { events: events, rest: rest };
}
