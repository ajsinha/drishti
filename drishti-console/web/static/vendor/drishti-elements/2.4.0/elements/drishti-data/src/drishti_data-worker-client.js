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
/* The page's side of the data worker: decode Arrow IPC bytes into a DataFrame and count bins, in a worker when the browser has one and
   the worker file loads, else on the page's thread with the same functions (never both, never a different answer). */
import { staticUrl } from '../../drishti-core/src/drishti_index.js';
import { DataFrame, fromArrow, arrowLib, ARROW_VENDOR } from './drishti_arrow.js';
import { binCounts } from './drishti_data-compute.js';

/** The worker's file, relative to the static root. */
export const DATA_WORKER = 'elements/drishti-data/drishti_data-worker.js';

/**
 * A DataFrame from what the worker decoded.
 * @param {{names: string[], length: number, columns: Array<{name: string, type: string, data: *}>, meta: Object, bytes: number}} p
 * @returns {DataFrame}
 */
export function frameFromWorker(p) {
  const cols = {};
  p.columns.forEach((c) => { cols[c.name] = { data: c.data, get: (i) => c.data[i], type: c.type }; });
  return new DataFrame({ format: 'arrow', names: p.names, length: p.length, columns: cols, meta: p.meta, bytes: p.bytes });
}

/** Decoding and binning off the page's thread. */
export class DataWorker {
  /**
   * @param {{url: string, Worker: function, arrow: Object, worker: boolean}} [o] url: the worker's address (default: the static one);
   *   worker: false forces the page's thread (a test, a page whose policy forbids workers)
   */
  constructor(o) {
    this.o = o || {};
    this.next = 1;
    this.waiting = new Map();
    this.w = null;
    const W = this.o.Worker || (typeof Worker !== 'undefined' ? Worker : null);
    if (W && this.o.worker !== false) {
      try {
        this.w = new W(this.o.url || staticUrl(DATA_WORKER), { type: 'module' });
        this.w.onmessage = (e) => { const p = this.waiting.get(e.data.id); if (p) { this.waiting.delete(e.data.id); if (e.data.ok) { p.resolve(e.data.result); } else { p.reject(new Error(e.data.error)); } } };
        this.w.onerror = () => { this.fail(new Error('the data worker stopped')); };
      } catch (e) { this.w = null; }
    }
  }

  /** @private every waiting request fails and the worker is given up: the page's thread takes over */
  fail(err) { this.waiting.forEach((p) => p.reject(err)); this.waiting.clear(); if (this.w) { this.w.terminate(); this.w = null; } }

  /**
   * Whether a worker is doing the work.
   * @returns {boolean}
   */
  usesWorker() { return !!this.w; }

  /** @private */
  ask(msg, transfer) { return new Promise((resolve, reject) => { const id = this.next++; this.waiting.set(id, { resolve: resolve, reject: reject }); this.w.postMessage(Object.assign({ id: id }, msg), transfer || []); }); }

  /**
   * Arrow IPC bytes as a DataFrame (the bytes are transferred to the worker: do not use them after).
   * @param {ArrayBuffer} buffer
   * @returns {Promise<DataFrame>}
   */
  decode(buffer) {
    if (this.w) {
      return this.ask({ op: 'decode', buffer: buffer, arrow: new URL(staticUrl(ARROW_VENDOR), (typeof location !== 'undefined' ? location.href : undefined)).href }, [buffer]).then(frameFromWorker);
    }
    return arrowLib(this.o.arrow).then((A) => fromArrow(buffer, A));
  }

  /**
   * Counts numbers into equal-width bins (a Float64Array is transferred to the worker: do not use it after).
   * @param {Float64Array} values
   * @param {{bins: number, extent: number[]}} [options]
   * @returns {Promise<{x0: number, width: number, counts: Uint32Array, n: number}>}
   */
  bin(values, options) {
    if (this.w) { return this.ask({ op: 'bin', values: values, options: options }, [values.buffer]); }
    return Promise.resolve(binCounts(values, options));
  }

  /**
   * Ends the worker.
   * @returns {void}
   */
  destroy() { this.fail(new Error('the data worker was destroyed')); }
}
