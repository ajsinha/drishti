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
/* Frames of patches applied to a painted view: in sequence order (a frame whose seq is not above the last applied one is dropped), each
   frame whole, all frames that arrived since the last paint in ONE animation frame. A patch is routed by its identity (a strip index,
   a panel id) through a Map to the one node it changes: no validation, no deep copy and no re-layout on this path. The contract's
   ops: strip, panel, provenance, deleted, restored, and rows (contract 1.1: a dataset's rows by key, handed to the dataset, which tells
   every panel that reads it); another op is ignored and counted. While the view's subject is deleted, patches
   other than restored are ignored. Frames that arrive faster than the page paints are conflated: the newest strip cell per index,
   panel per id and provenance win, in the order of their last arrival, never across a deleted or restored. A hidden page paints
   nothing: it keeps only the conflated pending state and catches up in one paint when shown. */
import { now } from '../../drishti-core/src/drishti_index.js';

/** The patch ops of contract 1 (rows since 1.1). */
export const OPS = Object.freeze(['strip', 'panel', 'provenance', 'deleted', 'restored', 'rows']);

/**
 * The patches of frames, conflated: for each identity (a strip index, a panel id, the provenance) only the newest, placed where it last
 * arrived; deleted and restored are kept and nothing is conflated across them. Patches with no identity are kept as they are.
 * @param {Object[]} patches in arrival order
 * @returns {{patches: Object[], conflated: number}}
 */
export function conflate(patches) {
  const out = [];
  let seg = [], conflated = 0;
  const close = () => {
    const last = new Map();
    seg.forEach((p, i) => { const k = identity(p); if (k !== null) { last.set(k, i); } });
    seg.forEach((p, i) => { const k = identity(p); if (k === null || last.get(k) === i) { out.push(p); } else { conflated++; } });
    seg = [];
  };
  patches.forEach((p) => {
    if (p && (p.op === 'deleted' || p.op === 'restored')) { close(); out.push(p); } else { seg.push(p); }
  });
  close();
  return { patches: out, conflated: conflated };
}

function identity(p) {
  if (!p || typeof p !== 'object') { return null; }
  if (p.op === 'strip' && Number.isInteger(p.index)) { return 's' + p.index; }
  if (p.op === 'panel' && p.panel && typeof p.panel === 'object' && p.panel.id !== undefined) { return 'p' + String(p.panel.id); }
  if (p.op === 'provenance') { return 'v'; }
  return null;
}

/** Applies frames to one painted view; owned by its handle. */
export class Patcher {
  /**
   * @param {Object} view the handle's internals: vm, screen, frames, root, ctx, metrics, raf(fn)
   */
  constructor(view) {
    this.view = view;
    this.queue = [];
    this.lastSeq = null;
    this.frozen = false;
    this.scheduled = false;
  }

  /**
   * Queues a frame ({seq?, patches}) or a list of patches; drawn in the next animation frame.
   * @param {(Object|Object[])} frame
   * @returns {boolean} false when the frame was dropped as old or a duplicate
   */
  push(frame) {
    const f = Array.isArray(frame) ? { patches: frame } : frame;
    if (!f || typeof f !== 'object') { return false; }
    const m = this.view.stats;
    m.received = (m.received || 0) + 1;
    if (typeof f.seq === 'number') {
      const last = this.queue.length && typeof this.queue[this.queue.length - 1].seq === 'number' ? this.queue[this.queue.length - 1].seq : this.lastSeq;
      if (last !== null && f.seq <= last) { m.dropped = (m.dropped || 0) + 1; return false; }
    }
    this.queue.push(f);
    if (this.hidden()) {
      if (this.queue.length > 1) { this.compact(); }
      this.whenShown();
      return true;
    }
    if (!this.scheduled) {
      this.scheduled = true;
      this.view.raf(() => this.flush());
    }
    return true;
  }

  /** @private whether the view's page is hidden (it has no animation frames) */
  hidden() { const d = this.view.el.ownerDocument; return !!(d && d.hidden); }

  /** @private the queue as one conflated frame (its seq the newest) */
  compact() {
    const frames = this.queue.splice(0);
    let seq = null;
    const all = [];
    frames.forEach((f) => { if (typeof f.seq === 'number') { seq = f.seq; } (Array.isArray(f.patches) ? f.patches : []).forEach((p) => all.push(p)); });
    const c = conflate(all), m = this.view.stats;
    m.conflated = (m.conflated || 0) + c.conflated;
    this.queue.push(seq === null ? { patches: c.patches } : { seq: seq, patches: c.patches });
  }

  /** @private one paint when the page is shown again */
  whenShown() {
    if (this.waitingShow) { return; }
    const d = this.view.el.ownerDocument;
    this.waitingShow = () => {
      if (d.hidden) { return; }
      d.removeEventListener('visibilitychange', this.waitingShow);
      this.waitingShow = null;
      if (!this.scheduled && this.queue.length) { this.scheduled = true; this.view.raf(() => this.flush()); }
    };
    d.addEventListener('visibilitychange', this.waitingShow);
  }

  /**
   * Stops waiting for the page to be shown (the view is disposed).
   * @returns {void}
   */
  stop() {
    if (this.waitingShow) { this.view.el.ownerDocument.removeEventListener('visibilitychange', this.waitingShow); this.waitingShow = null; }
    this.queue.length = 0;
  }

  /**
   * Applies every queued frame now (the animation frame's work); answers the patches applied.
   * @returns {number}
   */
  flush() {
    this.scheduled = false;
    if (this.view.disposed) { this.queue.length = 0; return 0; }
    if (this.queue.length > 1) { this.compact(); }
    const frames = this.queue.splice(0);
    const t0 = now();
    let applied = 0;
    frames.forEach((f) => {
      (Array.isArray(f.patches) ? f.patches : []).forEach((p) => { applied += this.apply(p) ? 1 : 0; });
      if (typeof f.seq === 'number') { this.lastSeq = f.seq; }
    });
    const m = this.view.stats;
    m.paints = (m.paints || 0) + 1;
    m.patches = (m.patches || 0) + applied;
    m.lastApplyMs = now() - t0;
    if (frames.length) { this.view.emit('patch', { frames: frames.length, applied: applied, seq: this.lastSeq }); }
    return applied;
  }

  apply(p) {
    const v = this.view, m = v.stats;
    if (!p || typeof p !== 'object') { m.ignored = (m.ignored || 0) + 1; return false; }
    if (this.frozen && p.op !== 'restored') { m.ignored = (m.ignored || 0) + 1; return false; }
    let done = false;
    if (p.op === 'strip' && Number.isInteger(p.index)) {
      done = v.screen.setCell(p.index, p.cell);
      if (done && Array.isArray(v.vm.strip)) { v.vm.strip[p.index] = p.cell; }
    } else if (p.op === 'panel' && p.panel && typeof p.panel === 'object') {
      const frame = v.frames.get(String(p.panel.id));
      if (frame) {
        frame.draw(p.panel);
        const i = v.vm.panels.findIndex((x) => x && String(x.id) === String(p.panel.id));
        if (i >= 0) { v.vm.panels[i] = p.panel; }
        done = true;
      }
    } else if (p.op === 'provenance' && p.provenance && typeof p.provenance === 'object') {
      v.screen.setProvenance(p.provenance);
      v.vm.provenance = p.provenance;
      done = true;
    } else if (p.op === 'rows' && typeof p.dataset === 'string') {
      const ds = v.datasets && v.datasets.get(p.dataset);
      done = !!ds && ds.apply(p);
    } else if (p.op === 'deleted') {
      this.frozen = true;
      v.root.classList.add('dv-frozen');
      v.screen.setDeleted(typeof p.at === 'string' ? p.at : '');
      done = true;
    } else if (p.op === 'restored') {
      this.frozen = false;
      v.root.classList.remove('dv-frozen');
      v.screen.setDeleted(null);
      done = true;
    }
    if (!done) { m.ignored = (m.ignored || 0) + 1; }
    return done;
  }
}
