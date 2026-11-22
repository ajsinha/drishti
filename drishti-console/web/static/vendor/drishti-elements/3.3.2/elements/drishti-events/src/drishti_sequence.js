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
/* Order, duplicates, gaps, replay, resume and silence: the one sequencer of drishti-events (docs/DESIGN.md, drishti-events). A server numbers the
   messages of each stream with `seq` and names the stream's incarnation with an `epoch`. The sequencer keeps the last seq heard of every
   stream (bounded: 5,000 streams, the least recently heard forgotten) and says, for each message that arrives, what may be delivered now and
   in which order:
   - a seq not above the last delivered is a repeat and is dropped; a message with no seq is a repeat when its id was seen in the last 2,048;
   - a seq that jumps is a gap: the messages after it are held (at most 5,000 per stream, at most gapWaitMs) while the owner asks the server
     to replay the missing range (onReplay); when the range arrives everything is delivered in order; when it cannot be had the held messages
     are delivered, the gap is reported (onGap) and the stream resyncs (onResync);
   - after a reconnect positions() says where each stream was (the `after` of a `sub`) and resumed() takes the server's answer (subd):
     recovered false, or a new epoch, is a resync.
   The feed monitor is this same sequencer seen from outside: stats (received, gaps, missed, repeats, stale) and watch() for silence. It
   owns no timer but those it sets on the injected clock, so a test drives it with testClock. It has no transport and no subscriptions. */

/** The bounds of section 3.8. */
export const SEQUENCE_LIMITS = Object.freeze({ streams: 5000, held: 5000, ids: 2048, gapWaitMs: 2000 });

/**
 * @typedef {Object} SequencerOptions
 * @property {import('./drishti_timing.js').Clock} clock the one clock (timers and the time of the last message)
 * @property {number} [gapWaitMs=2000] how long held messages wait for a replay
 * @property {number} [maxHeld=5000] held messages per stream
 * @property {number} [maxStreams=5000] streams remembered
 * @property {number} [idWindow=2048] ids remembered for messages with no seq
 * @property {boolean} [canReplay=true] whether the server offers replay (welcome.can); without it a gap resyncs at once
 * @property {function({stream: string, epoch: ?string, from: number, to: number}): void} [onReplay] asks the server to send a range again
 * @property {function({stream: string, from: number, to: number, recoverable: boolean}): void} [onGap] a range that will not arrive
 * @property {function({stream: string, reason: string}): void} [onResync] the stream's state must be asked for again
 * @property {function(Object[]): void} [onRelease] messages released later than the call that held them (a replay arrived, or the wait ended)
 * @property {function({stream: string, quietMs: number}): void} [onStale] a watched stream was quiet for too long
 * @property {function(): void} [onFresh] a stale stream spoke again
 */

/** Orders, deduplicates and repairs the messages of numbered streams. */
export class Sequencer {
  /**
   * @param {SequencerOptions} o
   */
  constructor(o) {
    if (!o || !o.clock) { throw new TypeError('Sequencer: give the clock'); }
    const L = SEQUENCE_LIMITS;
    this.o = Object.assign({ gapWaitMs: L.gapWaitMs, maxHeld: L.held, maxStreams: L.streams, idWindow: L.ids, canReplay: true }, o);
    this.clock = o.clock;
    /** @private @type {Map<string, {epoch: ?string, last: ?number, held: Map<number, Object>, timer: *, asked: ?number, at: number}>} */
    this.streams = new Map();
    /** @private */ this.ids = new Map();
    /** @private */ this.watched = new Map();
    this.stats = { received: 0, gaps: 0, missed: 0, repeats: 0, stale: 0 };
    /** @type {Array<{stream: string, from: number, to: number, recoverable: boolean}>} the gaps reported, newest last (bounded) */
    this.gaps = [];
  }

  /** @private */
  state(stream) {
    let s = this.streams.get(stream);
    if (s) { this.streams.delete(stream); this.streams.set(stream, s); return s; }              // heard now: the most recent
    s = { epoch: null, last: null, held: new Map(), timer: null, asked: null, at: 0 };
    this.streams.set(stream, s);
    while (this.streams.size > this.o.maxStreams) {
      const oldest = this.streams.keys().next().value;
      const gone = this.streams.get(oldest);
      if (gone.timer != null) { this.clock.clearTimeout(gone.timer); }
      this.streams.delete(oldest);
    }
    return s;
  }

  /**
   * Takes one message from the server and says what may be delivered now, in order. A message held for a gap is delivered later through
   * onRelease. A message with no seq is delivered unless its id was seen lately.
   * @param {Object} m the message (id, stream, seq ...)
   * @returns {Object[]} the messages to deliver now (possibly none)
   */
  accept(m) {
    this.stats.received++;
    this.touch(m);
    const stream = m.stream || m.topic;
    if (typeof m.seq !== 'number' || m.retained) { return this.noSeq(m); }        // a retained value is a snapshot from before the position
    const s = this.state(stream);
    s.at = this.clock.now();
    if (s.last === null) { s.last = m.seq; return [m]; }                                         // the first heard: where the stream begins for us
    if (m.seq <= s.last) { this.stats.repeats++; return []; }
    if (m.seq === s.last + 1) { s.last = m.seq; return [m].concat(this.drain(stream, s)); }
    if (s.held.has(m.seq)) { this.stats.repeats++; return []; }
    s.held.set(m.seq, m);                                                                         // a gap: hold it and ask
    if (s.held.size > this.o.maxHeld) { return this.giveUp(stream, s, 'overflow'); }
    if (!this.o.canReplay) { return this.giveUp(stream, s, 'no-replay'); }
    this.ask(stream, s);
    return [];
  }

  /** @private */
  noSeq(m) {
    if (m.id == null) { return [m]; }
    if (this.ids.has(m.id)) { this.stats.repeats++; return []; }
    this.ids.set(m.id, true);
    while (this.ids.size > this.o.idWindow) { this.ids.delete(this.ids.keys().next().value); }
    return [m];
  }

  /** @private the contiguous held messages after last, delivered in order */
  drain(stream, s) {
    const out = [];
    while (s.held.has(s.last + 1)) { s.last++; out.push(s.held.get(s.last)); s.held.delete(s.last); }
    if (!s.held.size) { this.stopTimer(s); s.asked = null; }
    return out;
  }

  /** @private asks for the missing range once, and starts the wait */
  ask(stream, s) {
    const first = Math.min(...s.held.keys());
    if (s.asked === null || first < s.asked) {
      s.asked = first;
      if (this.o.onReplay) { this.o.onReplay({ stream: stream, epoch: s.epoch, from: s.last + 1, to: first - 1 }); }
    }
    if (s.timer == null) { s.timer = this.clock.setTimeout(() => { s.timer = null; this.release(this.giveUp(stream, s, 'timeout')); }, this.o.gapWaitMs); }
  }

  /** @private gives up waiting: delivers what is held in order, reports the ranges lost, resyncs */
  giveUp(stream, s, reason) {
    this.stopTimer(s);
    const out = [];
    Array.from(s.held.keys()).sort((a, b) => a - b).forEach((seq) => {
      if (seq > s.last + 1) { this.lose(stream, s.last + 1, seq - 1, false); }
      s.last = seq;
      out.push(s.held.get(seq));
    });
    s.held.clear();
    s.asked = null;
    if (this.o.onResync) { this.o.onResync({ stream: stream, reason: reason }); }
    return out;
  }

  /** @private */
  lose(stream, from, to, recoverable) {
    const g = { stream: stream, from: from, to: to, recoverable: recoverable };
    this.stats.gaps++;
    this.stats.missed += to - from + 1;
    this.gaps.push(g);
    if (this.gaps.length > 100) { this.gaps.shift(); }
    if (this.o.onGap) { this.o.onGap(g); }
  }

  /** @private */
  release(list) { if (list.length && this.o.onRelease) { this.o.onRelease(list); } }

  /** @private */
  stopTimer(s) { if (s.timer != null) { this.clock.clearTimeout(s.timer); s.timer = null; } }

  /**
   * A `gap` frame of the server: it did not send this range (a slow client, a trimmed log). A recoverable one is asked for again; one that
   * is not is declared lost, and what follows is delivered.
   * @param {{stream: string, from: number, to: number, recoverable: boolean}} g
   * @returns {Object[]} messages to deliver now
   */
  serverGap(g) {
    const s = this.state(g.stream);
    if (g.recoverable && this.o.canReplay) {
      if (this.o.onReplay) { this.o.onReplay({ stream: g.stream, epoch: s.epoch, from: g.from, to: g.to }); }
      if (s.timer == null && s.held.size) { s.timer = this.clock.setTimeout(() => { s.timer = null; this.release(this.giveUp(g.stream, s, 'timeout')); }, this.o.gapWaitMs); }
      return [];
    }
    if (s.last !== null && g.to > s.last) {
      this.lose(g.stream, Math.max(g.from, s.last + 1), g.to, false);
      s.last = g.to;
    }
    if (this.o.onResync) { this.o.onResync({ stream: g.stream, reason: 'gap' }); }
    return this.drain(g.stream, s);
  }

  /**
   * Where each stream was: the `after` of a `sub` after a reconnect.
   * @returns {Object<string, {epoch: ?string, seq: number}>}
   */
  positions() {
    const out = {};
    this.streams.forEach((s, k) => { if (s.last !== null) { out[k] = { epoch: s.epoch, seq: s.last }; } });
    return out;
  }

  /**
   * The server's answer to a `sub` (`subd`): the streams as they stand now, and whether everything after `after` will follow. A new
   * epoch, or recovered false, starts the stream again from the server's position and asks for a resync.
   * @param {Object<string, {epoch: string, seq: number}>} streams
   * @param {boolean} recovered
   * @returns {string[]} the streams that must resync
   */
  resumed(streams, recovered) {
    const resync = [];
    Object.keys(streams || {}).forEach((name) => {
      const s = this.state(name), now = streams[name];
      const fresh = s.last === null || (s.epoch != null && now.epoch !== s.epoch);
      if (fresh || !recovered) {
        s.held.clear(); this.stopTimer(s); s.asked = null;
        s.last = now.seq;
        if (s.epoch != null || !recovered) { resync.push(name); if (this.o.onResync) { this.o.onResync({ stream: name, reason: recovered ? 'epoch' : 'unrecovered' }); } }
      }
      s.epoch = now.epoch;
    });
    return resync;
  }

  /**
   * Takes positions heard elsewhere (the tabs of a shared hub that took over): a stream not yet known here resumes after them.
   * @param {Object<string, {epoch: ?string, seq: number}>} positions
   * @returns {number} how many streams were taken
   */
  seed(positions) {
    let n = 0;
    Object.keys(positions || {}).forEach((name) => {
      const p = positions[name];
      if (!p || typeof p.seq !== 'number' || !isFinite(p.seq)) { return; }
      const s = this.state(name);
      if (s.last !== null) { return; }
      s.last = p.seq;
      s.epoch = p.epoch != null ? String(p.epoch) : null;
      n++;
    });
    return n;
  }

  /**
   * Forgets one stream (the server no longer knows it: a restart that lost the log); its next message starts it again.
   * @param {string} name
   * @returns {void}
   */
  forgetStream(name) {
    const s = this.streams.get(name);
    if (!s) { return; }
    this.stopTimer(s);
    this.streams.delete(name);
  }

  /**
   * Forgets the numbering (a new session with no resume): positions are empty, held messages are dropped.
   * @returns {void}
   */
  reset() {
    this.streams.forEach((s) => this.stopTimer(s));
    this.streams.clear(); this.ids.clear();
    this.stats = { received: 0, gaps: 0, missed: 0, repeats: 0, stale: 0 };
    this.gaps = [];
  }

  /** @private records that a stream spoke (for watch) */
  touch(m) {
    const key = m.stream || m.topic;
    const w = this.watched.get(key);
    if (w) { w.at = this.clock.now(); if (w.stale) { w.stale = false; if (this.o.onFresh) { this.o.onFresh(); } } this.arm(key, w); }
  }

  /** @private */
  arm(key, w) {
    if (w.timer != null) { this.clock.clearTimeout(w.timer); }
    w.timer = this.clock.setTimeout(() => {
      w.timer = null;
      if (!w.stale) { w.stale = true; this.stats.stale++; if (this.o.onStale) { this.o.onStale({ stream: key, quietMs: this.clock.now() - w.at }); } }
    }, w.staleMs);
  }

  /**
   * Says (onStale, once) when a stream is quiet for longer than it should be, and (onFresh) when it speaks again.
   * @param {string} stream
   * @param {number} staleMs 0 or less: not watched
   * @returns {function(): void} stops watching
   */
  watch(stream, staleMs) {
    if (!(staleMs > 0)) { return () => {}; }
    const w = { at: this.clock.now(), staleMs: staleMs, timer: null, stale: false };
    this.watched.set(stream, w);
    this.arm(stream, w);
    return () => { if (w.timer != null) { this.clock.clearTimeout(w.timer); } this.watched.delete(stream); };
  }

  /**
   * Whether a watched stream is stale now.
   * @param {string} stream
   * @returns {boolean}
   */
  isStale(stream) { const w = this.watched.get(stream); return !!(w && w.stale); }

  /**
   * How many messages wait for a gap, over every stream.
   * @returns {number}
   */
  held() { let n = 0; this.streams.forEach((s) => { n += s.held.size; }); return n; }
}
