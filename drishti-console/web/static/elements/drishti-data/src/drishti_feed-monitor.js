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
/* The live feed monitor: is the feed whole and fresh? It reads the sequence number a feed puts on its messages and says "a gap" (the
   numbers it missed), "out of order" and "a repeat", and "stale" when nothing has come for longer than the feed should be quiet. A
   model with an injectable clock (runs under Node) and a badge that draws it with drishti-core's state kit. It watches; the live client
   already reconnects and resumes. */
import { showState } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} FeedMonitorOptions
 * @property {function(Object): ?number} [seqOf] the sequence number of a message (default: message.seq)
 * @property {number} [staleMs=15000] quiet for this long is stale (0: never)
 * @property {function(): number} [now] the clock (default Date.now)
 */

/** Counts what a feed delivered and what it missed. Events through on(): gap {from, to, missed}, stale {since}, fresh, order, repeat. */
export class FeedMonitor {
  /**
   * @param {FeedMonitorOptions} [o]
   */
  constructor(o) {
    this.o = Object.assign({ seqOf: (m) => (m && typeof m.seq === 'number' ? m.seq : null), staleMs: 15000, now: () => Date.now() }, o);
    this.last = null;
    this.stats = { received: 0, gaps: 0, missed: 0, outOfOrder: 0, repeats: 0 };
    this.gaps = [];
    this.at = null;
    this.wasStale = false;
    this.subs = [];
  }

  /**
   * Listens to the monitor's events.
   * @param {function(string, Object): void} fn
   * @returns {function(): void} stops listening
   */
  on(fn) { this.subs.push(fn); return () => { this.subs = this.subs.filter((f) => f !== fn); }; }

  /** @private */
  emit(name, d) { this.subs.forEach((f) => f(name, d || {})); }

  /**
   * Takes one message (call it for every message of the feed).
   * @param {Object} message
   * @returns {void}
   */
  receive(message) {
    this.stats.received++;
    this.at = this.o.now();
    if (this.wasStale) { this.wasStale = false; this.emit('fresh'); }
    const s = this.o.seqOf(message);
    if (s === null) { return; }
    if (this.last === null) { this.last = s; return; }
    if (s === this.last + 1) { this.last = s; return; }
    if (s === this.last) { this.stats.repeats++; this.emit('repeat', { seq: s }); return; }
    if (s < this.last) { this.stats.outOfOrder++; this.emit('order', { seq: s, after: this.last }); return; }
    const gap = { from: this.last + 1, to: s - 1, missed: s - this.last - 1 };
    this.stats.gaps++; this.stats.missed += gap.missed; this.gaps.push(gap);
    this.last = s;
    this.emit('gap', gap);
  }

  /**
   * Checks the clock: call it on a timer (the badge does); says stale once when the quiet is too long.
   * @returns {boolean} whether the feed is stale now
   */
  check() {
    const quiet = this.at === null ? 0 : this.o.now() - this.at, stale = !!this.o.staleMs && this.at !== null && quiet > this.o.staleMs;
    if (stale && !this.wasStale) { this.wasStale = true; this.emit('stale', { since: this.at, quietMs: quiet }); }
    return stale;
  }

  /**
   * Starts again (a new connection with a new numbering).
   * @returns {void}
   */
  reset() { this.last = null; this.at = null; this.wasStale = false; this.gaps = []; this.stats = { received: 0, gaps: 0, missed: 0, outOfOrder: 0, repeats: 0 }; }

  /**
   * Feeds the monitor from a live client (drishti-data's LiveClient).
   * @param {{subscribe: function(function(Object): void): function(): void}} client
   * @returns {function(): void} stops
   */
  attach(client) { return client.subscribe((m) => this.receive(m)); }
}

/**
 * A small badge for a monitor: "Live", "Stale: nothing for 20 s" with a Refresh, or "Missed 3 updates" (until acknowledged). The badge
 * is a live region.
 * @param {HTMLElement} el
 * @param {FeedMonitor} monitor
 * @param {{refresh: function(): void, tickMs: number}} [o] refresh: what Refresh does (resync); tickMs: how often the clock is checked (default 2000)
 * @returns {function(): void} stops
 */
export function feedBadge(el, monitor, o) {
  o = o || {};
  const win = el.ownerDocument.defaultView;
  const draw = () => {
    const stale = monitor.check();
    if (stale) { showState(el, 'stale', { text: 'No updates', compact: true, since: new Date(monitor.at).toISOString().slice(11, 19), fix: o.refresh ? { label: 'Refresh', run: () => { monitor.reset(); o.refresh(); } } : undefined }); }
    else if (monitor.gaps.length) { showState(el, 'stale', { text: 'Missed ' + monitor.stats.missed + (monitor.stats.missed === 1 ? ' update' : ' updates'), compact: true, fix: { label: 'Got it', run: () => { monitor.gaps = []; draw(); } } }); }
    else { showState(el, 'empty', { text: monitor.at === null ? 'Waiting for updates' : 'Live', compact: true }); }
  };
  const off = monitor.on(draw), t = win.setInterval(draw, o.tickMs || 2000);
  draw();
  return () => { off(); win.clearInterval(t); };
}
