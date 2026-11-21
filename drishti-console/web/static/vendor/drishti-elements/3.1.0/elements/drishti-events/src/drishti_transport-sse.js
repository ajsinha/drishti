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
/* Server-Sent Events, two ways. EventSource (GET, the site's cookie: it can send neither a body nor a header; the subscriptions travel
   in the address as topic=, the resume position as lastEventId=) and fetch (the token in Authorization, the hello and the subscriptions in
   a JSON body, the resume position in Last-Event-ID; the answer read as a stream). In protocol 1 the stream is <events>/stream and a
   client's frames go to <events>/send with the header Events-Session (the answer is 202; the results come on the stream); each SSE
   event's name is a frame's type and its data the frame. In raw mode the address is the server's own feed and every event is a value.
   parseSse and readSse(response) read the format for anyone (a POST that answers a stream, for example). */
import { EventError } from './drishti_envelope.js';
import { SERVER_FRAMES } from './drishti_protocol.js';

/**
 * An SSE event.
 * @typedef {Object} SseEvent
 * @property {string} event its name (default message)
 * @property {string} data its data lines joined by new lines
 * @property {?string} id its id (the cursor)
 * @property {boolean} comment a comment line alone (a sign of life)
 * @property {(number|undefined)} retry the server's advice on how long to wait before reconnecting, ms
 */

/**
 * Reads Server-Sent Events from text: the whole events and what is left for the next chunk.
 * @param {string} text
 * @returns {{events: SseEvent[], rest: string}}
 */
export function parseSse(text) {
  const events = [];
  const blocks = String(text).replace(/\r\n?/g, '\n').split('\n\n');
  const rest = blocks.pop();
  blocks.forEach((b) => {
    let event = 'message', id = null, comment = false, retry;
    const data = [];
    b.split('\n').forEach((line) => {
      if (line === '') { return; }
      if (line[0] === ':') { comment = true; return; }
      const at = line.indexOf(':'), field = at < 0 ? line : line.slice(0, at);
      let value = at < 0 ? '' : line.slice(at + 1);
      if (value[0] === ' ') { value = value.slice(1); }
      if (field === 'event') { event = value; } else if (field === 'data') { data.push(value); } else if (field === 'id') { id = value; }
      else if (field === 'retry' && /^\d+$/.test(value)) { retry = Number(value); }
    });
    if (data.length || comment || retry !== undefined) { events.push({ event: event, data: data.join('\n'), id: id, comment: comment && !data.length, retry: retry }); }
  });
  return { events: events, rest: rest };
}

/**
 * The events of a fetch answer that streams Server-Sent Events, as an async iterator (ends with the answer, or when the signal fires).
 * @param {Response} response
 * @param {{signal: AbortSignal}} [o]
 * @returns {AsyncIterable<SseEvent>}
 */
export function readSse(response, o) {
  const signal = o && o.signal;
  return {
    [Symbol.asyncIterator]: async function* events() {
      if (!response || !response.body || !response.body.getReader) { throw new EventError('sse.unreadable', 'the answer has no body to read as a stream'); }
      const reader = response.body.getReader(), dec = new TextDecoder();
      let buf = '';
      try {
        for (;;) {
          if (signal && signal.aborted) { return; }
          const r = await reader.read();
          if (r.done) { break; }
          const got = parseSse(buf + dec.decode(r.value, { stream: true }));
          buf = got.rest;
          for (const e of got.events) { yield e; }
        }
        const tail = parseSse(buf + dec.decode() + '\n\n');
        for (const e of tail.events) { yield e; }
      } finally {
        try { reader.releaseLock(); } catch (e) { /* released */ }
      }
    }
  };
}

/** @private an address with query parameters added */
function withParams(url, params) {
  const q = params.filter((p) => p[1] !== null && p[1] !== undefined && p[1] !== '').map((p) => encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]));
  return q.length ? url + (url.indexOf('?') >= 0 ? '&' : '?') + q.join('&') : url;
}

/** @private the events address with a part under it ('stream', 'send') */
function under(url, part) {
  const at = url.indexOf('?'), base = at < 0 ? url : url.slice(0, at), q = at < 0 ? '' : url.slice(at);
  return base.replace(/\/+$/, '') + '/' + part + q;
}

/**
 * Sends a client's frames over SSE (protocol 1): POST <events>/send with the session; the answer is 202.
 * @param {TransportContext} ctx
 * @param {Object[]} frames
 * @returns {Promise<void>}
 */
export function postFrames(ctx, frames) {
  if (ctx.protocol !== 1) { return Promise.reject(new EventError('protocol', 'a raw stream takes nothing from the page (protocol 1 sends to <events>/send)')); }
  const F = ctx.env.fetch || globalThis.fetch;
  if (!F) { return Promise.reject(new EventError('transport.missing', 'this page has no fetch')); }
  const session = ctx.session();
  if (!session) { return Promise.reject(new EventError('not.connected', 'the server has not named a session yet')); }
  return Promise.resolve(F(under(ctx.url, 'send'), { method: 'POST', credentials: ctx.credentials,
    headers: Object.assign({ 'Content-Type': 'application/json' }, ctx.headers, { 'Events-Session': session }), body: JSON.stringify(frames) }))
    .then((res) => { if (!res.ok) { throw new EventError(res.status === 401 ? 'auth.required' : res.status === 403 ? 'forbidden' : 'send.failed', '<events>/send answered ' + res.status, { status: res.status }); } });
}

/**
 * The EventSource transport (GET, the site's cookie).
 * @param {TransportContext} ctx
 * @returns {Transport}
 */
export function eventSourceTransport(ctx) {
  const Source = ctx.env.EventSource || globalThis.EventSource;
  if (!Source) { throw new EventError('transport.missing', 'this page has no EventSource'); }
  const params = ctx.protocol === 1 ? ctx.subs.map((s) => ['topic', s.topic]) : [];
  params.push(['lastEventId', ctx.lastEventId]);
  const address = withParams(ctx.protocol === 1 ? under(ctx.url, 'stream') : ctx.url, params);
  const es = new Source(address, { withCredentials: ctx.credentials === 'include' });
  let ended = false;
  const end = (info) => { if (!ended) { ended = true; try { es.close(); } catch (e) { /* closed */ } ctx.onClose(info); } };
  const names = ctx.protocol === 1 ? SERVER_FRAMES : (ctx.events && ctx.events.length ? ctx.events : ['message']);
  const on = (e) => { if (!ended) { ctx.onText(e.data, { event: e.type, id: e.lastEventId || null }); } };
  names.forEach((n) => es.addEventListener(n, on));
  es.addEventListener('open', () => { if (!ended) { ctx.onOpen(); } });
  es.addEventListener('error', () => end({ code: 0, status: 0, reason: es.readyState === 2 ? 'the stream was refused or closed' : 'the stream dropped' }));
  return {
    kind: 'sse',
    raw: es,
    address: address,
    send: (frames) => postFrames(ctx, frames),
    close: () => { ended = true; names.forEach((n) => es.removeEventListener(n, on)); try { es.close(); } catch (e) { /* closed */ } },
    bufferedAmount: () => 0
  };
}

/**
 * The fetch transport: Server-Sent Events read with fetch, so headers travel (a bearer token).
 * @param {TransportContext} ctx
 * @returns {Transport}
 */
export function fetchTransport(ctx) {
  const F = ctx.env.fetch || globalThis.fetch;
  if (!F) { throw new EventError('transport.missing', 'this page has no fetch'); }
  const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  let ended = false;
  const end = (info) => { if (!ended) { ended = true; if (ctrl) { ctrl.abort(); } ctx.onClose(info); } };
  const headers = Object.assign({ Accept: 'text/event-stream' }, ctx.headers);
  if (ctx.lastEventId) { headers['Last-Event-ID'] = ctx.lastEventId; }
  const init = { headers: headers, credentials: ctx.credentials, cache: 'no-store', signal: ctrl ? ctrl.signal : undefined };
  let address = ctx.url;
  if (ctx.protocol === 1) {
    address = under(ctx.url, 'stream');
    init.method = 'POST';
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify({ hello: ctx.hello, subs: ctx.subs });
  }
  const run = async () => {
    const res = await F(address, init);
    if (ended) { return; }
    if (!res.ok) { end({ code: 0, status: res.status, reason: 'the stream answered ' + res.status }); return; }
    ctx.onOpen();
    for await (const e of readSse(res, { signal: ctrl ? ctrl.signal : null })) {
      if (ended) { return; }
      if (e.retry !== undefined) { ctx.onRetry(e.retry); }
      if (e.comment) { ctx.onComment(); continue; }
      if (e.data === '' && e.retry !== undefined) { continue; }
      if (ctx.protocol === 1 || !ctx.events || !ctx.events.length || ctx.events.indexOf(e.event) >= 0) { ctx.onText(e.data, { event: e.event, id: e.id }); }
    }
    end({ code: 0, status: 0, reason: 'the stream ended' });
  };
  run().catch((e) => end({ code: 0, status: 0, reason: (e && e.message) || 'the stream failed' }));
  return {
    kind: 'fetch',
    raw: ctrl,
    send: (frames) => postFrames(ctx, frames),
    close: () => { ended = true; if (ctrl) { ctrl.abort(); } },
    bufferedAmount: () => 0
  };
}
