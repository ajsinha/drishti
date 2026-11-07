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
/* The timing parts of a live connection (tested in Node with a fake clock): the back-off between reconnect attempts (exponential with
   a cap and "full jitter", so a thousand browsers do not come back in the same second after a server restart) and the heartbeat
   watchdog (no message for longer than the heartbeat, the connection is taken as dead and opened again). */

/**
 * Exponential back-off with full jitter: attempt n waits a random time up to min(max, base * factor^n).
 */
export class Backoff {
  /**
   * @param {{baseMs: number, maxMs: number, factor: number, jitter: boolean, random: function(): number}} [o] baseMs default 500; maxMs
   *   default 30,000; factor default 2; jitter default true; random: for tests
   */
  constructor(o) {
    this.o = Object.assign({ baseMs: 500, maxMs: 30000, factor: 2, jitter: true, random: Math.random }, o);
    this.attempt = 0;
  }

  /**
   * The wait before the next attempt (and the attempt counted).
   * @returns {number} ms
   */
  next() {
    const cap = Math.min(this.o.maxMs, this.o.baseMs * Math.pow(this.o.factor, this.attempt));
    this.attempt++;
    return Math.round(this.o.jitter ? Math.max(this.o.baseMs / 2, cap * this.o.random()) : cap);
  }

  /**
   * A connection held: the next wait starts small again.
   * @returns {void}
   */
  reset() { this.attempt = 0; }
}

/**
 * A watchdog: beat() on every message; when none comes for timeoutMs, onTimeout runs (once until the next beat).
 */
export class Heartbeat {
  /**
   * @param {{timeoutMs: number, onTimeout: function(): void, setTimeout: Function, clearTimeout: Function}} o the timer functions: for tests
   */
  constructor(o) {
    this.o = Object.assign({ setTimeout: (f, ms) => setTimeout(f, ms), clearTimeout: (t) => clearTimeout(t) }, o);
    this.timer = null;
    this.last = 0;
  }

  /**
   * A sign of life: the watch starts again.
   * @returns {void}
   */
  beat() {
    this.last = Date.now();
    if (!this.o.timeoutMs) { return; }
    this.stop();
    this.timer = this.o.setTimeout(() => { this.timer = null; this.o.onTimeout(); }, this.o.timeoutMs);
  }

  /**
   * Stops watching.
   * @returns {void}
   */
  stop() { if (this.timer !== null) { this.o.clearTimeout(this.timer); this.timer = null; } }
}
