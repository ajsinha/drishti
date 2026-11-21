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
/* The wire protocol, version 1 ("events.v1"), and the raw mode for servers that do not speak it. Frames are JSON objects with a type `t`;
   a transport message holds one frame or a list of them (a batch). Within a version unknown fields and unknown frame types are ignored
   and counted, so both sides can add. Everything received is checked before use (the type, the topic's grammar, an id's length, the
   size): it is data, never code. The written contract is the design's section 8 (later CONTRACTS.md, "The events contract"). */
import { isTopic } from '../../drishti-core/src/drishti_index.js';
import { makeMessage, MAX_ID_LENGTH } from './drishti_envelope.js';

/** The protocol's version. */
export const PROTOCOL_VERSION = 1;

/** The WebSocket sub-protocol of version 1 (a host that needs its own name gives the option subprotocol). */
export const SUBPROTOCOL = 'events.v1';

/** The frames a client sends. */
export const CLIENT_FRAMES = Object.freeze(['hello', 'sub', 'unsub', 'pub', 'req', 'cancel', 'replay', 'auth', 'ping', 'bye']);

/** The frames a server sends. */
export const SERVER_FRAMES = Object.freeze(['welcome', 'subd', 'msg', 'ok', 'res', 'err', 'gap', 'auth', 'pong', 'bye']);

/** The protocol's own error codes (a server may send any other code of its own). */
export const ERROR_CODES = Object.freeze(['auth.required', 'auth.expired', 'forbidden', 'topic.invalid', 'topic.unknown', 'limit.subs',
  'limit.rate', 'limit.size', 'timeout', 'no.responder', 'replay.unavailable', 'version', 'slow', 'restart', 'internal']);

/** WebSocket close codes: 4400 a bad frame, 4401 not authenticated, 4403 forbidden, 4408 no hello in time, 4429 too fast, 4500 the server's fault. */
export const CLOSE_CODES = Object.freeze({ badFrame: 4400, unauthenticated: 4401, forbidden: 4403, noHello: 4408, tooFast: 4429, serverFault: 4500 });

/**
 * One frame or a batch, as the text a transport sends.
 * @param {Object[]} frames
 * @returns {string}
 */
export function encodeFrames(frames) { return JSON.stringify(frames.length === 1 ? frames[0] : frames); }

/**
 * The size of a text in UTF-8 bytes.
 * @param {string} text
 * @returns {number}
 */
export function byteLength(text) {
  let n = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    n += c < 0x80 ? 1 : c < 0x800 ? 2 : (c >= 0xD800 && c <= 0xDBFF) ? (i++, 4) : 3;
  }
  return n;
}

/**
 * Reads a transport message: its frames, or why it was dropped (too-large, not-json, not-a-frame). A frame of an unknown type is kept
 * with unknown: true (to be counted and ignored).
 * @param {string} text
 * @param {{maxBytes: number}} [o] maxBytes default 1 MB
 * @returns {{frames: Object[], bytes: number, error: ?string}}
 */
export function decodeFrames(text, o) {
  const max = (o && o.maxBytes) || 1048576;
  const s = String(text);
  const bytes = s.length * 3 <= max ? s.length : byteLength(s);
  if (bytes > max) { return { frames: [], bytes: bytes, error: 'too-large' }; }
  let v;
  try { v = JSON.parse(s); } catch (e) { return { frames: [], bytes: bytes, error: 'not-json' }; }
  const list = Array.isArray(v) ? v : [v];
  const frames = [];
  for (const f of list) {
    if (!f || typeof f !== 'object' || Array.isArray(f) || typeof f.t !== 'string') { return { frames: [], bytes: bytes, error: 'not-a-frame' }; }
    if (SERVER_FRAMES.indexOf(f.t) < 0) { frames.push({ t: f.t, unknown: true }); continue; }
    frames.push(f);
  }
  return { frames: frames, bytes: bytes, error: null };
}

/**
 * Checks a server's frame before use: true, or what is wrong in words.
 * @param {Object} f
 * @returns {(true|string)}
 */
export function checkFrame(f) {
  const id = (x) => (typeof x === 'string' || typeof x === 'number') && String(x).length <= MAX_ID_LENGTH;
  switch (f.t) {
    case 'msg':
      if (!isTopic(f.topic)) { return 'a msg frame names no valid topic'; }
      if (!id(f.id)) { return 'a msg frame has no id (or one longer than 64)'; }
      if (f.seq !== undefined && !(typeof f.seq === 'number' && isFinite(f.seq))) { return 'a msg frame\'s seq is not a number'; }
      return true;
    case 'subd': case 'ok': return id(f.id) ? true : 'a ' + f.t + ' frame has no id';
    case 'res': return id(f.re) ? true : 'a res frame has no re';
    case 'welcome': return typeof f.v === 'number' ? true : 'a welcome frame has no version';
    case 'err': case 'bye': return typeof f.code === 'string' ? true : 'an ' + f.t + ' frame has no code';
    case 'gap': return typeof f.stream === 'string' ? true : 'a gap frame names no stream';
    default: return true;
  }
}

/**
 * A server's msg frame as a message.
 * @param {Object} f
 * @param {number} now the page's clock
 * @returns {Object} a Message
 */
export function messageOf(f, now) {
  return makeMessage({ id: String(f.id), topic: f.topic, data: f.data === undefined ? null : f.data, time: typeof f.time === 'number' ? f.time : now,
    source: typeof f.source === 'string' ? f.source : 'server', seq: f.seq, stream: f.stream !== undefined ? String(f.stream) : undefined,
    key: f.key != null ? String(f.key) : undefined,
    kind: typeof f.kind === 'string' ? f.kind : undefined, re: f.re, meta: f.meta && typeof f.meta === 'object' ? f.meta : undefined,
    retained: f.retained ? true : undefined, replayed: f.replayed ? true : undefined, scope: 'server', receivedAt: now });
}

/**
 * How raw mode turns a server's own JSON into messages.
 * @typedef {Object} RawMapping
 * @property {function(*, {event: string, id: ?string}): string} [topicOf] default: one topic, 'feed'
 * @property {function(*, {event: string, id: ?string}): (number|undefined)} [seqOf] default: the value's seq, when a number
 * @property {function(*, {event: string, id: ?string}): (string|undefined)} [idOf] default: the SSE event id, else the value's id
 * @property {function(*, {event: string, id: ?string}): (string|undefined)} [keyOf] default: none
 * @property {function(*, {event: string, id: ?string}): (string|undefined)} [kindOf] default: the SSE event name (other than message)
 */

/**
 * A raw server's value as a message (raw mode).
 * @param {*} value the parsed JSON
 * @param {{event: string, id: ?string}} meta the SSE event's name and id
 * @param {RawMapping} map
 * @param {function(): string} newId ids for values that carry none
 * @param {number} now
 * @returns {Object} a Message
 */
export function rawMessage(value, meta, map, newId, now) {
  const v = value && typeof value === 'object' ? value : {};
  const topic = map.topicOf ? map.topicOf(value, meta) : 'feed';
  const seq = map.seqOf ? map.seqOf(value, meta) : (typeof v.seq === 'number' ? v.seq : undefined);
  const id = map.idOf ? map.idOf(value, meta) : (meta.id ? meta.id : (v.id !== undefined && v.id !== null ? String(v.id) : undefined));
  const kind = map.kindOf ? map.kindOf(value, meta) : (meta.event && meta.event !== 'message' ? meta.event : undefined);
  const key = map.keyOf ? map.keyOf(value, meta) : undefined;
  return makeMessage({ id: id !== undefined ? String(id) : newId(), topic: topic, data: value === undefined ? null : value, time: now,
    source: 'server', seq: seq, key: key != null ? String(key) : undefined, kind: kind, scope: 'server', receivedAt: now });
}
