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
/* The message envelope and the words around it: the errors (EventError, TimeoutError, AbortError, in the shape of drishti-core's error
   contract: a code and words), message ids (sortable by time, random per page), the topic grammar's checks (the grammar and its one
   matcher are drishti-core's), what a pattern has in common with another (routes), the registry of kinds and the small shape checker. */
import { isTopic, isPattern, topicMatches } from '../../drishti-core/src/drishti_index.js';

/**
 * A failure of drishti-events, in words: a code and the detail.
 */
export class EventError extends Error {
  /**
   * @param {string} code 'topic.invalid', 'timeout', 'no.responder', 'forbidden', ... (a server may send codes of its own)
   * @param {string} detail what happened, in words
   * @param {Object} [extra] more fields kept on the error (retry, status)
   */
  constructor(code, detail, extra) {
    super(String(code) + ' ' + String(detail || ''));
    this.name = 'EventError';
    /** @type {string} */ this.code = String(code);
    /** @type {string} */ this.detail = String(detail || '');
    if (extra) { Object.assign(this, extra); }
  }
}

/**
 * A wait that ran out of time.
 */
export class TimeoutError extends EventError {
  /**
   * @param {string} detail
   */
  constructor(detail) { super('timeout', detail); this.name = 'TimeoutError'; }
}

/**
 * A wait that was given up (its AbortSignal fired, or the Events was closed).
 */
export class AbortError extends EventError {
  /**
   * @param {string} [detail]
   */
  constructor(detail) { super('aborted', detail || 'given up'); this.name = 'AbortError'; }
}

/**
 * A message.
 * @template T
 * @typedef {Object} Message
 * @property {string} id unique per publisher; made by the publisher
 * @property {string} topic 'prices.fx.USDCAD'
 * @property {T} data any JSON value
 * @property {number} time the publisher's clock, ms since 1970
 * @property {string} source who published: a server's name, 'page', or 'tab:<id>'
 * @property {number} [seq] the position in its stream (a server's messages)
 * @property {string} [stream] the stream the seq counts in (default: the topic)
 * @property {string} [key] what a later message replaces (conflation)
 * @property {string} [kind] the name of the data's shape ('tick', 'rows')
 * @property {string} [re] the id of the request this answers
 * @property {Object<string, string>} [meta] headers (a trace id)
 * @property {('page'|'tabs'|'server')} scope where it came from (added on receipt)
 * @property {number} receivedAt this page's clock (added on receipt)
 * @property {boolean} [retained] a last value sent on subscribing
 * @property {boolean} [replayed] a message sent again after a gap
 * @property {boolean} [invalid] it failed its kind's check and was delivered anyway (onInvalid: 'deliver')
 */

/** The longest id the protocol allows. */
export const MAX_ID_LENGTH = 64;

const ENVELOPES = new WeakSet();

/**
 * Says whether a value is a message made by drishti-events (and not a plain payload some other code put on the page's bus).
 * @param {*} x
 * @returns {boolean}
 */
export function isMessage(x) { return !!x && typeof x === 'object' && ENVELOPES.has(x); }

/**
 * Makes a message (and marks it as one).
 * @param {Object} fields the message's fields (section 2.2 of the design)
 * @returns {Message<*>}
 */
export function makeMessage(fields) {
  const m = {};
  Object.keys(fields).forEach((k) => { if (fields[k] !== undefined) { m[k] = fields[k]; } });
  ENVELOPES.add(m);
  return m;
}

/**
 * Makes message ids: time first (so they sort by time), then a random part per page, then a counter.
 */
export class IdMaker {
  /**
   * @param {{now: function(): number, random: function(): number}} clock
   */
  constructor(clock) {
    this.clock = clock;
    /** @type {string} this page's random part */
    this.tab = Math.floor(clock.random() * 2176782336).toString(36).padStart(6, '0');
    this.n = 0;
  }

  /**
   * The next id.
   * @returns {string}
   */
  next() { return this.clock.now().toString(36).padStart(9, '0') + '-' + this.tab + '-' + (this.n++).toString(36); }
}

/**
 * Says whether a topic is one of a library's own ('$events.state'): such topics never leave the page.
 * @param {string} topic
 * @returns {boolean}
 */
export function isOwnTopic(topic) { return typeof topic === 'string' && topic[0] === '$'; }

/**
 * Throws when a topic is not one a message may be published on (or, with own, a library's own '$' topic).
 * @param {*} topic
 * @param {boolean} [own] '$' topics allowed
 * @returns {string} the topic
 */
export function checkTopic(topic, own) {
  if (own && isOwnTopic(topic) && isTopic(topic.slice(1))) { return topic; }
  if (!isTopic(topic)) {
    throw new EventError('topic.invalid', describeBadName(topic, false));
  }
  return topic;
}

/**
 * Throws when a pattern is not one a listener may name ('$events.*' allowed: the library's own topics).
 * @param {*} pattern
 * @returns {string} the pattern
 */
export function checkPattern(pattern) {
  if (isOwnTopic(pattern) && isPattern(pattern.slice(1))) { return pattern; }
  if (!isPattern(pattern)) { throw new EventError('topic.invalid', describeBadName(pattern, true)); }
  return pattern;
}

function describeBadName(name, pattern) {
  if (typeof name !== 'string' || !name) { return 'a ' + (pattern ? 'pattern' : 'topic') + ' is a text such as prices.fx.USDCAD; got ' + (name === '' ? 'an empty text' : typeof name); }
  if (isOwnTopic(name)) { return '"' + name.slice(0, 80) + '": topics beginning $ are the library\'s own'; }
  if (!pattern && isPattern(name)) { return '"' + name.slice(0, 80) + '" is a pattern; a message is published on one topic (no * or >)'; }
  return '"' + name.slice(0, 80) + '" is not a ' + (pattern ? 'pattern' : 'topic') + ': segments of letters, digits, _ - : ~ joined by dots' +
    (pattern ? ', * for one segment and a last > for the rest' : '') + ', at most 16 segments and 255 characters (segment(text) encodes any text)';
}

/**
 * Says whether a topic matches a pattern (drishti-core's matcher; a library's own '$' topics too).
 * @param {string} pattern
 * @param {string} topic
 * @returns {boolean}
 */
export function matches(pattern, topic) {
  if (isOwnTopic(pattern) !== isOwnTopic(topic)) { return false; }
  return pattern === topic || topicMatches(pattern, topic);
}

/**
 * Says whether some topic matches both patterns.
 * @param {string} a
 * @param {string} b
 * @returns {boolean}
 */
export function patternsOverlap(a, b) {
  if (isOwnTopic(a) !== isOwnTopic(b)) { return false; }
  const p = a.split('.'), q = b.split('.');
  for (let i = 0; ; i++) {
    const x = p[i], y = q[i];
    if (x === '>' || y === '>') { return (x === '>' ? q.length > i : true) && (y === '>' ? p.length > i : true); }
    if (x === undefined || y === undefined) { return x === y; }
    if (x !== '*' && y !== '*' && x !== y) { return false; }
  }
}

/**
 * How specific a pattern is, for "the most specific route wins": more literal segments first, then more segments, then "*" before ">".
 * @param {string} pattern
 * @returns {number}
 */
export function specificity(pattern) {
  const s = pattern.split('.');
  const literal = s.filter((x) => x !== '*' && x !== '>').length;
  return literal * 10000 + s.length * 10 + (s[s.length - 1] === '>' ? 0 : 1);
}

/**
 * A check of a message's data: true, or what is wrong in words.
 * @callback Validator
 * @param {*} data
 * @param {Message<*>} [message]
 * @returns {(boolean|string)}
 */

/**
 * The kinds a page knows: the name of a data's shape and its check.
 */
export class Kinds {
  constructor() { /** @private */ this.map = new Map(); }

  /**
   * Registers (or, with no check, forgets) the check of a kind.
   * @param {string} kind
   * @param {Validator} [validate]
   * @returns {void}
   */
  define(kind, validate) {
    if (typeof kind !== 'string' || !kind) { throw new EventError('kind.invalid', 'a kind is a name such as tick'); }
    if (validate == null) { this.map.delete(kind); return; }
    if (typeof validate !== 'function') { throw new EventError('kind.invalid', 'the check of kind ' + kind + ' is a function of the data'); }
    this.map.set(kind, validate);
  }

  /**
   * Checks data against its kind (and, when given, one more check): true, or words.
   * @param {(string|undefined)} kind
   * @param {*} data
   * @param {Message<*>} [message]
   * @param {Validator} [extra]
   * @returns {(true|string)}
   */
  check(kind, data, message, extra) {
    const v = kind ? this.map.get(kind) : null;
    if (!v && !extra) { return true; }
    for (const f of [v, extra]) {
      if (!f) { continue; }
      let r;
      try { r = f(data, message); } catch (e) { r = 'its check failed: ' + (e && e.message ? e.message : String(e)); }
      if (r !== true && r !== undefined) { return typeof r === 'string' ? r : 'it does not have the shape of ' + (kind || 'its topic'); }
    }
    return true;
  }

  /**
   * Whether any check is registered.
   * @returns {boolean}
   */
  get size() { return this.map.size; }
}

/**
 * A small shape checker: shape({ symbol: 'string', bid: 'number', note: 'string?', legs: [{ side: 'string' }], at: { x: 'number' } }).
 * Types: string, number, boolean, object, array, any, null; a final "?" makes a field optional; an array of one shape is a list of that
 * shape; an object is a nested shape. Fields not named are allowed.
 * @param {Object} spec
 * @returns {Validator}
 */
export function shape(spec) {
  const check = (s, v, path) => {
    if (Array.isArray(s)) {
      if (!Array.isArray(v)) { return '`' + path + '` should be a list'; }
      for (let i = 0; i < v.length; i++) { const r = check(s[0], v[i], path + '[' + i + ']'); if (r !== true) { return r; } }
      return true;
    }
    if (s && typeof s === 'object') {
      if (!v || typeof v !== 'object' || Array.isArray(v)) { return '`' + (path || 'the data') + '` should be an object'; }
      for (const k of Object.keys(s)) {
        let t = s[k], optional = false;
        if (typeof t === 'string' && t.endsWith('?')) { optional = true; t = t.slice(0, -1); }
        const has = Object.prototype.hasOwnProperty.call(v, k) && v[k] !== undefined;
        if (!has) { if (optional) { continue; } return '`' + (path ? path + '.' : '') + k + '` is missing'; }
        const r = check(t, v[k], (path ? path + '.' : '') + k);
        if (r !== true) { return r; }
      }
      return true;
    }
    const t = String(s);
    const ok = t === 'any' || (t === 'array' ? Array.isArray(v) : t === 'null' ? v === null : t === 'object' ? (!!v && typeof v === 'object' && !Array.isArray(v)) :
      t === 'number' ? (typeof v === 'number' && isFinite(v)) : typeof v === t);
    return ok ? true : '`' + (path || 'the data') + '` should be ' + (t === 'array' ? 'a list' : t === 'object' ? 'an object' : t === 'any' ? 'present' : 'a ' + t);
  };
  if (!spec || typeof spec !== 'object') { throw new EventError('kind.invalid', 'shape() takes an object naming each field\'s type'); }
  return (data) => check(spec, data, '');
}
