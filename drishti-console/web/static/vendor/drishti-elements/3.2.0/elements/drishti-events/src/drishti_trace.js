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
/* The recorder and "why didn't I get it?". Every place a message can stop records a reason against its id, topic and seq in a ring of
   the last 5,000 headers (payloads only when a developer turns them on; memory only; tokens never). explain({ topic, key, id, since })
   reads it and answers drishti-core's ExplainResult: the steps in words, the first that failed marked. */
import { matches } from './drishti_envelope.js';

/**
 * The options of the recorder.
 * @typedef {Object} RecorderOptions
 * @property {number} [max=5000] the headers kept
 * @property {boolean} [payloads=false] keep each message's data too (for the inspector; a developer's choice)
 */

/**
 * A ring of message headers and what became of each, with the connection's and the subscriptions' changes beside them.
 */
export class Recorder {
  /**
   * @param {{now: function(): number}} clock
   * @param {RecorderOptions} [o]
   */
  constructor(clock, o) {
    this.clock = clock;
    this.o = Object.assign({ max: 5000, payloads: false }, o);
    /** @type {Object[]} oldest first */ this.list = [];
    this.byId = new Map();
    /** @type {Object[]} the connection's states, oldest first (bounded) */ this.states = [];
    /** @type {Object[]} subscription changes, gaps and listener errors (bounded) */ this.notes = [];
    this.paused = false;
  }

  /**
   * Records a message coming in or going out.
   * @param {Object} m
   * @param {('in'|'out')} dir
   * @param {string} [scope]
   * @returns {?Object} the entry
   */
  record(m, dir, scope) {
    if (this.paused || !m) { return null; }
    const e = { at: this.clock.now(), dir: dir, scope: scope || m.scope || null, topic: m.topic, id: m.id || null, seq: m.seq, stream: m.stream,
      key: m.key, kind: m.kind, time: m.time, delivered: 0, listeners: 0, dropped: null, retained: !!m.retained, replayed: !!m.replayed };
    if (this.o.payloads) { e.data = m.data; }
    this.list.push(e);
    if (e.id) { this.byId.set(e.id + '|' + dir, e); }
    if (this.list.length > this.o.max) {
      const gone = this.list.shift();
      if (gone.id && this.byId.get(gone.id + '|' + gone.dir) === gone) { this.byId.delete(gone.id + '|' + gone.dir); }
    }
    return e;
  }

  /** @private the entry of a message, recorded if not yet */
  _entry(m, dir) {
    if (!m) { return null; }
    const e = m.id ? this.byId.get(m.id + '|' + (dir || 'in')) : null;
    return e || this.record(m, dir || 'in');
  }

  /**
   * A message was delivered to a subscription's listeners.
   * @param {Object} m
   * @param {string} pattern
   * @param {number} listeners
   * @returns {void}
   */
  delivered(m, pattern, listeners) {
    const e = this.byId.get((m.id || '') + '|in');
    if (!e) { return; }
    e.delivered++;
    e.listeners += listeners;
    e.deliveredAt = this.clock.now();
    (e.patterns || (e.patterns = [])).push(pattern);
  }

  /**
   * A message stopped: the reason in a word and in words.
   * @param {?Object} m
   * @param {string} reason
   * @param {string} words
   * @param {?string} pattern
   * @returns {void}
   */
  dropped(m, reason, words, pattern) {
    if (this.paused) { return; }
    const e = m && m.topic ? this._entry(m, 'in') : null;
    const d = { reason: reason, words: words || reason, pattern: pattern || null, at: this.clock.now() };
    if (e) { (e.drops || (e.drops = [])).push(d); if (!e.dropped) { e.dropped = d; } return; }
    this.note({ type: 'drop', reason: reason, words: d.words, pattern: d.pattern });
  }

  /**
   * The connection changed state.
   * @param {Object} d $events.state's data
   * @returns {void}
   */
  state(d) {
    this.states.push({ at: this.clock.now(), state: d.state, reason: d.reason || null, waitMs: d.waitMs == null ? null : d.waitMs });
    if (this.states.length > 500) { this.states.shift(); }
  }

  /**
   * Something beside the messages: a subscription's change, a gap, a listener's error.
   * @param {Object} n with type
   * @returns {void}
   */
  note(n) {
    this.notes.push(Object.assign({ at: this.clock.now() }, n));
    if (this.notes.length > 1000) { this.notes.shift(); }
  }

  /**
   * The entries matching a filter, newest last.
   * @param {{topic: string, key: string, id: string, since: number, dir: string}} [q] topic may be a pattern
   * @returns {Object[]}
   */
  find(q) {
    const f = q || {};
    return this.list.filter((e) => (!f.dir || e.dir === f.dir) && (!f.id || e.id === f.id) && (f.key === undefined || e.key === String(f.key)) &&
      (!f.since || e.at >= f.since) && (!f.topic || e.topic === f.topic || safeMatch(f.topic, e.topic)));
  }

  /**
   * Forgets everything recorded.
   * @returns {void}
   */
  clear() { this.list = []; this.byId.clear(); this.states = []; this.notes = []; }

  /**
   * The recorder's figures.
   * @returns {{headers: number, max: number, payloads: boolean}}
   */
  metrics() { return { headers: this.list.length, max: this.o.max, payloads: !!this.o.payloads }; }
}

/** @private */
function safeMatch(p, t) { try { return matches(p, t); } catch (e) { return false; } }

/** @private a time as the page shows it: hh:mm:ss.mmm (local) */
function clockText(ms) {
  const d = new Date(ms), p = (n, w) => String(n).padStart(w || 2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '.' + p(d.getMilliseconds(), 3);
}

/** @private a count with thousands */
function num(n) { return Number(n).toLocaleString('en-US'); }

/**
 * Why a message did or did not reach the page's listeners: the steps in words, the first that failed marked.
 * @param {Object} events the Events
 * @param {{topic: string, key: string, id: string, since: number}} q
 * @returns {import('../../drishti-core/src/drishti_explain.js').ExplainResult} with failed: the first step that failed (or null) and steps' ok
 */
export function explainWhy(events, q) {
  const r = events.recorder, query = Object.assign({}, q), topic = query.topic || '';
  const steps = [];
  let failed = null;
  const step = (label, ok, words) => {
    const s = { label: label, value: words, ok: ok };
    steps.push(s);
    if (!ok && !failed) { failed = s; }
    return ok;
  };
  const done = (value) => ({ title: 'Why: ' + (topic || query.id || 'a message'), value: value, steps: steps, failed: failed,
    note: r.o.max ? 'From the last ' + num(r.list.length) + ' messages recorded.' : undefined });
  const subs = Array.from(events._subs).filter((s) => topic && safeMatch(s.pattern, topic));
  if (topic && !step('A listener matched', subs.length > 0, subs.length ? subs.map((s) => '`' + s.pattern + '`').join(', ') + ' match `' + topic + '`.'
    : 'No subscription matches `' + topic + '`.' + nearest(events, topic))) { return done(failed.value); }
  if (topic) {
    const scopes = Array.from(new Set(subs.reduce((a, s) => a.concat(s.scopes), [])));
    const route = events.routeOf(topic);
    const reach = scopes.length > 0;
    if (!step('The route reached that scope', reach, reach ? 'It is heard from ' + scopes.join(' and ') + (route.indexOf('server') < 0 && !events._adapter('server') ? '.' : '; `' + topic + '` is routed to ' + route.join(' and ') + '.')
      : '`' + subs[0].pattern + '` hears no scope: it is routed to ' + route.join(' and ') + ', which this page does not have.')) { return done(failed.value); }
    const refused = subs.find((s) => s.state === 'refused');
    if (refused && subs.every((s) => s.state === 'refused' || s.state === 'closed')) {
      const n = r.notes.filter((x) => x.type === 'subscription' && x.pattern === refused.pattern && x.state === 'refused').pop();
      step('The subscription was active', false, '`' + refused.pattern + '` was refused' + (n ? ' at ' + clockText(n.at) : '') + ': ' + (refused.reason || 'no reason given') + '.');
      return done(failed.value);
    }
    step('The subscription was active', true, subs.map((s) => '`' + s.pattern + '` is ' + s.state).join('; ') + '.');
  }
  const since = query.since || 0;
  const down = downtime(r, since, events.clock.now());
  if (events._adapter('server')) { step('The connection was open', !down.length || events.state === 'open', down.length ? down.map((d) => 'The connection was ' + d.state + ' from ' + clockText(d.from) + (d.to ? ' to ' + clockText(d.to) : ' until now') + (d.reason ? ' (' + d.reason + ')' : '') + '.').join(' ') : 'It was open throughout.'); }
  const hits = r.find({ topic: topic || undefined, key: query.key, id: query.id, since: since, dir: 'in' });
  if (!hits.length) {
    const gaps = r.notes.filter((x) => x.type === 'gap' && x.at >= since && (!topic || x.stream === topic || safeMatch(x.stream + '.>', topic) || topic.indexOf(x.stream) === 0));
    const words = gaps.length ? gaps.map((g) => 'Stream `' + g.stream + '` jumped from ' + num(g.from - 1) + ' to ' + num(g.to + 1) + '; ' + (g.recoverable ? 'a replay was asked.' : 'a replay was asked and answered `unavailable`.')).join(' ')
      : 'Nothing on `' + (topic || query.id) + '`' + (query.key !== undefined ? ' with key `' + query.key + '`' : '') + ' arrived' + (since ? ' since ' + clockText(since) : '') + '.';
    step('The message arrived', false, words);
    return done(failed.value);
  }
  const e = hits[hits.length - 1];
  step('The message arrived', true, 'At ' + clockText(e.at) + (e.seq !== undefined ? ', seq ' + num(e.seq) : '') + ' from ' + (e.scope || 'the page') + '.');
  const drops = e.drops || [];
  const invalid = drops.find((d) => d.reason === 'invalid');
  if (!step('It was valid', !invalid || e.delivered > 0, invalid && !e.delivered ? invalid.words : 'Yes.')) { return done(failed.value); }
  const lost = drops.find((d) => ['conflated', 'overflow', 'duplicate', 'filtered', 'expired', 'too-large', 'unrouted'].indexOf(d.reason) >= 0);
  if (!e.delivered && lost) {
    let words = lost.words;
    if (lost.reason === 'conflated') {
      const later = r.find({ topic: e.topic, key: e.key, dir: 'in' }).filter((x) => x.at >= e.at && x !== e && x.delivered);
      if (later.length) { words = 'Conflated: replaced by ' + (later[0].seq !== undefined ? 'seq ' + num(later[0].seq) : 'a later message') + ' with the same key ' + Math.max(0, later[0].at - e.at) + ' ms later.'; }
    }
    step('It was kept', false, words);
    return done(failed.value);
  }
  step('It was kept', true, 'Yes.');
  const err = r.notes.filter((x) => x.type === 'error' && x.topic === e.topic && x.at >= e.at).pop();
  if (err) { step('The listener ran', false, 'The listener threw: ' + err.detail + ' (see `$events.error`).'); return done(failed.value); }
  if (!e.delivered) { step('The listener ran', false, 'It is waiting to be delivered (the subscription delivers per frame or on a timer).'); return done(failed.value); }
  const after = e.time ? Math.max(0, e.deliveredAt - e.time) : null;
  const words = 'Delivered at ' + clockText(e.deliveredAt) + ' to ' + e.listeners + (e.listeners === 1 ? ' listener' : ' listeners') + (after !== null ? ', ' + after + ' ms after it was published.' : '.');
  step('The listener ran', true, words);
  return done(words);
}

/** @private the nearest subscription's pattern, in words */
function nearest(events, topic) {
  const t = topic.split('.');
  let best = null, score = -1;
  events._subs.forEach((s) => {
    if (s.pattern[0] === '$') { return; }
    const p = s.pattern.split('.');
    let n = 0;
    while (n < p.length && n < t.length && (p[n] === t[n] || p[n] === '*')) { n++; }
    if (n > score) { score = n; best = s.pattern; }
  });
  return best ? ' The nearest is `' + best + '`.' : ' This page has no subscriptions.';
}

/** @private the times the connection was not open since a moment */
function downtime(r, since, now) {
  const out = [];
  let cur = null;
  r.states.forEach((s) => {
    if (s.state === 'open') { if (cur) { cur.to = s.at; if (cur.to >= since) { out.push(cur); } cur = null; } return; }
    if (s.state === 'connecting' && !cur) { return; }
    if (!cur) { cur = { state: s.state, from: s.at, to: null, reason: s.reason }; }
  });
  if (cur && now >= since) { out.push(cur); }
  return out.filter((d) => d.state !== 'idle');
}
