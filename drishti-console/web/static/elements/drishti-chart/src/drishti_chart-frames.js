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
/* Frames: one field of the rows (a date, a round, a scenario) stepped by a slider and a Play button, every kind drawn at one frame at a
   time (the rows of that frame, or of every frame up to it). The engine moves the marks from one frame to the next by their row ids; a
   person who asks for reduced motion gets the slider and no Play. The rows are the server's; a frame only chooses which are drawn.

     frames: { field: 'ROUND', play: true, interval: 900, mode: 'only' | 'upto', at: 1, label: 'Round' } */
import { h } from '../../drishti-core/src/drishti_index.js';
import { num } from './drishti_chart-data.js';

/**
 * A chart's frames.
 * @typedef {Object} ChartFrames
 * @property {string} field the field each row's frame is read from
 * @property {Array<*>} [values] the frames in order (default: the field's values, numbers ascending, else text order)
 * @property {*} [at] the frame shown first (default: the first)
 * @property {boolean} [play=true] offer Play
 * @property {number} [interval=900] milliseconds between frames while playing
 * @property {('only'|'upto')} [mode='only'] only: the rows of the frame; upto: the rows of every frame up to it
 * @property {string} [label] the word before the frame's value ("Round")
 * @property {string} [playText='Play'] the Play button's words ("Play the rounds")
 */

/**
 * The frames of a field: the values given, or the field's distinct values (numbers ascending, else text order).
 * @param {Object[]} rows
 * @param {ChartFrames} frames
 * @returns {Array<*>}
 */
export function frameValues(rows, frames) {
  if (!frames || !frames.field) { return []; }
  if (Array.isArray(frames.values) && frames.values.length) { return frames.values.slice(); }
  const seen = new Map();
  rows.forEach((r) => { const v = r[frames.field]; if (v !== null && v !== undefined && !seen.has(String(v))) { seen.set(String(v), v); } });
  const vals = Array.from(seen.values()), nums = vals.every((v) => num(v) !== null);
  return vals.sort((a, b) => (nums ? num(a) - num(b) : String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0));
}

/**
 * The rows drawn at a frame: the frame's own (mode only) or every frame's up to it (mode upto).
 * @param {Object[]} rows
 * @param {ChartFrames} frames
 * @param {Array<*>} values the frames in order
 * @param {number} index the frame's index
 * @returns {Object[]}
 */
export function frameRows(rows, frames, values, index) {
  if (!frames || !frames.field || !values.length) { return rows; }
  const i = Math.max(0, Math.min(values.length - 1, index)), keep = new Set((frames.mode === 'upto' ? values.slice(0, i + 1) : [values[i]]).map(String));
  return rows.filter((r) => keep.has(String(r[frames.field])));
}

/**
 * Whether the person asked for reduced motion (Play is then not offered).
 * @param {Window} [win]
 * @returns {boolean}
 */
export function reducedMotion(win) { return !!(win && win.matchMedia && win.matchMedia('(prefers-reduced-motion: reduce)').matches); }

/**
 * The frames' bar in a chart's card: Play (or Pause), the slider and the frame's words. It tells its owner the index chosen.
 */
export class FrameBar {
  /**
   * @param {Document} doc
   * @param {function(number, {playing: boolean}): void} onFrame called with the index chosen (by the slider, a key or Play)
   */
  constructor(doc, onFrame) {
    this.doc = doc;
    this.win = doc.defaultView;
    this.onFrame = onFrame;
    this.values = [];
    this.index = 0;
    this.timer = 0;
    this.playBtn = h(doc, 'button', { cls: 'dc-tool dc-play', text: '▶ Play', attrs: { type: 'button', 'data-tool': 'play', 'aria-pressed': 'false' }, on: { click: () => (this.timer ? this.stop() : this.play()) } });
    this.slider = h(doc, 'input', { cls: 'dc-frame-slider', attrs: { type: 'range', min: '0', max: '0', step: '1', value: '0', 'aria-label': 'Frame' },
      on: { input: (e) => { this.stop(); this.go(Number(e.currentTarget.value)); } } });
    this.labelEl = h(doc, 'span', { cls: 'dc-frame-label', attrs: { 'aria-live': 'polite' } });
    this.el = h(doc, 'div', { cls: 'dc-frames', attrs: { hidden: true, role: 'group', 'aria-label': 'Frames' } }, [this.playBtn, this.slider, this.labelEl]);
  }

  /**
   * Sets the frames (a new spec or new rows); keeps the frame shown when it is still there.
   * @param {ChartFrames} frames
   * @param {Array<*>} values
   * @returns {number} the index shown
   */
  set(frames, values) {
    const was = this.values[this.index];
    this.frames = frames;
    this.values = values;
    this.el.hidden = !frames || !values.length;
    if (this.el.hidden) { this.stop(); return 0; }
    let i = values.findIndex((v) => String(v) === String(was));
    if (i < 0 && frames.at !== undefined) { i = values.findIndex((v) => String(v) === String(frames.at)); }
    this.index = Math.max(0, i);
    const still = reducedMotion(this.win);
    this.playBtn.hidden = frames.play === false;
    this.playBtn.textContent = '▶ ' + (frames.playText || 'Play');
    this.playBtn.disabled = still;
    this.playBtn.title = still ? 'Play is off: this system asks for reduced motion (the slider steps the frames)' : 'Play the frames in order';
    this.slider.max = String(values.length - 1);
    this.show();
    return this.index;
  }

  /** @private the slider and the words at the index */
  show() {
    const v = this.values[this.index], words = (this.frames.label ? this.frames.label + ' ' : '') + (v === undefined ? '' : String(v));
    this.slider.value = String(this.index);
    this.slider.setAttribute('aria-valuetext', words);
    this.labelEl.textContent = words + ' (' + (this.index + 1) + ' of ' + this.values.length + ')';
  }

  /**
   * Shows a frame by its index (clamped).
   * @param {number} i
   * @returns {void}
   */
  go(i) {
    this.index = Math.max(0, Math.min(this.values.length - 1, i));
    this.show();
    this.onFrame(this.index, { playing: !!this.timer });
  }

  /**
   * Plays the frames from the one shown (from the first when the last is shown), one each interval, and stops at the last.
   * @returns {boolean} false when Play is not offered (reduced motion, or no frames)
   */
  play() {
    if (!this.values.length || reducedMotion(this.win) || this.timer) { return false; }
    if (this.index >= this.values.length - 1) { this.go(0); }
    this.playBtn.textContent = '❚❚ Pause';
    this.playBtn.setAttribute('aria-pressed', 'true');
    this.timer = this.win.setInterval(() => {
      if (this.index >= this.values.length - 1) { this.stop(); return; }
      this.go(this.index + 1);
    }, Math.max(120, this.frames.interval || 900));
    return true;
  }

  /**
   * Stops playing.
   * @returns {void}
   */
  stop() {
    if (this.timer) { this.win.clearInterval(this.timer); this.timer = 0; }
    this.playBtn.textContent = '▶ ' + ((this.frames && this.frames.playText) || 'Play');
    this.playBtn.setAttribute('aria-pressed', 'false');
  }
}
