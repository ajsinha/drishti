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
/* The WebSocket transport: text frames, the sub-protocol events.v1 (and, for a gateway that decides at the handshake, the token as a
   second sub-protocol). Binary frames are reserved: they are dropped and counted. The connection does the rest (hello, heartbeats, close
   codes). */
import { EventError } from './drishti_envelope.js';
import { encodeFrames } from './drishti_protocol.js';

/**
 * What a transport is given (the connection makes it for each attempt).
 * @typedef {Object} TransportContext
 * @property {string} url the address (for SSE in protocol 1, the events address: /stream and /send are under it)
 * @property {(1|'raw')} protocol
 * @property {{WebSocket: Function, EventSource: Function, fetch: Function}} env the browser's classes (stand-ins in tests)
 * @property {Object<string, string>} headers fetch: the request's headers (the token's included)
 * @property {string} credentials fetch: 'same-origin' by default
 * @property {string[]} protocols WebSocket: the sub-protocols
 * @property {?string} lastEventId SSE: the last cursor heard
 * @property {Object} hello SSE (protocol 1): the hello frame
 * @property {Object[]} subs SSE (protocol 1): the sub frames of the subscriptions held
 * @property {string[]} events raw SSE: the event names that carry messages
 * @property {function(): ?string} session the session the server named (for /send)
 * @property {function(): void} onOpen
 * @property {function(string, {event: string, id: ?string}): void} onText a transport message
 * @property {function(): void} onComment an SSE comment line (a heartbeat)
 * @property {function(number): void} onRetry SSE's retry: field
 * @property {function(): void} onBinary a binary frame (dropped)
 * @property {function({code: number, status: number, reason: string}): void} onClose the transport ended (once)
 */

/**
 * A transport: sends frames, closes.
 * @typedef {Object} Transport
 * @property {string} kind 'websocket', 'sse' or 'fetch'
 * @property {function(Object[]): Promise<void>} send
 * @property {function(number): void} close
 * @property {function(): number} bufferedAmount bytes the transport has not sent yet
 */

/**
 * The WebSocket transport.
 * @param {TransportContext} ctx
 * @returns {Transport}
 */
export function websocketTransport(ctx) {
  const WS = ctx.env.WebSocket || globalThis.WebSocket;
  if (!WS) { throw new EventError('transport.missing', 'this page has no WebSocket'); }
  const ws = new WS(ctx.url, ctx.protocols && ctx.protocols.length ? ctx.protocols : undefined);
  let ended = false;
  const end = (info) => { if (!ended) { ended = true; ctx.onClose(info); } };
  ws.addEventListener('open', () => { if (!ended) { ctx.onOpen(); } });
  ws.addEventListener('message', (e) => {
    if (ended) { return; }
    if (typeof e.data !== 'string') { ctx.onBinary(); return; }
    ctx.onText(e.data, { event: 'message', id: null });
  });
  ws.addEventListener('close', (e) => end({ code: (e && e.code) || 1006, status: 0, reason: (e && e.reason) || 'the socket closed' }));
  ws.addEventListener('error', () => { /* a close follows */ });
  return {
    kind: 'websocket',
    raw: ws,
    send: (frames) => {
      if (ended) { return Promise.reject(new EventError('not.connected', 'the socket is closed')); }
      ws.send(encodeFrames(frames));
      return Promise.resolve();
    },
    close: (code) => { ended = true; try { ws.close(code || 1000); } catch (e) { /* closed already */ } },
    bufferedAmount: () => ws.bufferedAmount || 0
  };
}
