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
/* Counters and histograms for events.metrics(): fixed log-scale buckets (constant memory, no dependency) and rates over the last one
   and ten seconds. */

/** The upper bounds of the histogram's buckets, in milliseconds: 0.01 ms doubling to about 84 s, then one for the rest. */
export const BUCKETS = Object.freeze(Array.from({ length: 24 }, (x, i) => 0.01 * Math.pow(2, i)));

/**
 * A histogram of durations in fixed log-scale buckets.
 */
export class Histogram {
  /**
   * An empty histogram.
   */
  constructor() {
    /** @type {number[]} */ this.counts = new Array(BUCKETS.length + 1).fill(0);
    /** @type {number} */ this.n = 0;
    /** @type {number} */ this.max = 0;
  }

  /**
   * Counts one duration.
   * @param {number} ms
   * @returns {void}
   */
  add(ms) {
    if (!(ms >= 0)) { return; }
    let i = 0;
    while (i < BUCKETS.length && ms > BUCKETS[i]) { i++; }
    this.counts[i]++;
    this.n++;
    if (ms > this.max) { this.max = ms; }
  }

  /**
   * The duration under which this share of the counts falls (a bucket's upper bound; the largest seen for the last bucket).
   * @param {number} p 0.5, 0.99
   * @returns {?number} null when nothing was counted
   */
  quantile(p) {
    if (!this.n) { return null; }
    const want = Math.ceil(p * this.n);
    let seen = 0;
    for (let i = 0; i < this.counts.length; i++) {
      seen += this.counts[i];
      if (seen >= want) { return i < BUCKETS.length ? Math.min(BUCKETS[i], this.max) : this.max; }
    }
    return this.max;
  }

  /**
   * The figures a metrics object shows.
   * @returns {{count: number, p50: ?number, p99: ?number, max: ?number}}
   */
  summary() { return { count: this.n, p50: round(this.quantile(0.5)), p99: round(this.quantile(0.99)), max: this.n ? round(this.max) : null }; }
}

/**
 * Counts per second over the last one and ten seconds (ten one-second slots).
 */
export class Rate {
  /**
   * @param {{now: function(): number}} clock
   */
  constructor(clock) {
    this.clock = clock;
    this.slots = new Array(10).fill(0);
    this.at = Math.floor(clock.now() / 1000);
    /** @type {number} */ this.total = 0;
  }

  /** @private moves the window to now */
  _roll() {
    const sec = Math.floor(this.clock.now() / 1000);
    const gone = Math.min(10, sec - this.at);
    for (let i = 1; i <= gone; i++) { this.slots[(this.at + i) % 10] = 0; }
    if (gone > 0) { this.at = sec; }
  }

  /**
   * Counts n now.
   * @param {number} [n=1]
   * @returns {void}
   */
  add(n) { this._roll(); const k = n === undefined ? 1 : n; this.slots[this.at % 10] += k; this.total += k; }

  /**
   * Per second over the last second (the one before this one) and the last ten.
   * @returns {{total: number, perSecond1s: number, perSecond10s: number}}
   */
  summary() {
    this._roll();
    const last = this.slots[(this.at + 9) % 10];
    const ten = this.slots.reduce((a, b) => a + b, 0) - this.slots[this.at % 10];
    return { total: this.total, perSecond1s: last, perSecond10s: Math.round(ten / 9 * 10) / 10 };
  }
}

/** @private */
function round(x) { return x === null || x === undefined ? null : Math.round(x * 1000) / 1000; }
