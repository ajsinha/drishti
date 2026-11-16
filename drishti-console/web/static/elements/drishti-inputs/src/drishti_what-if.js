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
/* What-if controls: a slider, a stepper or a range for an assumption (a shock in basis points, a confidence level, a horizon), each
   asking the server a question when the person stops: the call is debounced, an answer to an older value is dropped, a failure is shown
   with its Retry (drishti-core's state kit). The control is native underneath (range inputs and buttons: the arrow keys, Home, End,
   PageUp and PageDown work, a screen reader names the value), and the answer is words in a live region, so nothing is only in a drawing. */
import { InputBase } from './drishti_inputs-dom.js';
import { showState } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} WhatIfOptions
 * @property {('slider'|'stepper'|'range')} [mode='slider'] one value by a slider, one by minus and plus, or a low and a high
 * @property {string} [label] what is assumed ("Parallel shock")
 * @property {number} [min=0]
 * @property {number} [max=100]
 * @property {number} [step=1]
 * @property {(number|number[])} [value] the starting value (range: [low, high])
 * @property {function(number): string} [format] how a figure is written ("+25 bp")
 * @property {function((number|number[])): Promise<*>} [call] the question to the server for a value: its answer is shown by describe
 * @property {function(*): string} [describe] the answer in words ("VaR 99% 1d: 12.4m, +1.1m")
 * @property {number} [debounce=300] milliseconds of rest before the call
 */

/** A slider, stepper or range whose value is asked of the server once it rests. Events: input {value}, change {value}, result {value, result}, error {error}. */
export class WhatIf extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {WhatIfOptions} options
   */
  constructor(host, options) {
    super(host, Object.assign({ mode: 'slider', min: 0, max: 100, step: 1, debounce: 300, format: String }, options), 'di-whatif');
    const o = this.o, two = o.mode === 'range';
    const start = o.value === undefined ? (two ? [o.min, o.max] : o.min) : o.value;
    this.value = two ? (Array.isArray(start) ? start.slice(0, 2) : [o.min, start]) : (Array.isArray(start) ? start[0] : start);
    this.seq = 0;
    this.timer = 0;
    this.id = 'wi' + Math.random().toString(36).slice(2, 8);
    this.name = this.el('label', { cls: 'di-whatif-label', text: o.label || '', attrs: { id: this.id + '-l' } });
    this.shown = this.el('output', { cls: 'di-whatif-value', attrs: { for: this.id, 'aria-live': 'off' } });
    this.row = this.el('div', { cls: 'di-whatif-row' });
    this.out = this.el('div', { cls: 'di-whatif-answer', attrs: { role: 'status', 'aria-live': 'polite' } });
    host.append(this.el('div', { cls: 'di-whatif-head' }, [this.name, this.shown]), this.row, this.out);
    if (o.mode === 'stepper') { this.stepper(); } else { this.sliders(two); }
    this.paint();
  }

  /** @private one range input, or two */
  sliders(two) {
    const o = this.o;
    const make = (i) => this.el('input', { cls: 'di-whatif-range', attrs: { type: 'range', min: String(o.min), max: String(o.max), step: String(o.step), 'aria-label': (o.label || 'Value') + (two ? (i === 0 ? ', low' : ', high') : ''),
      id: i === 0 ? this.id : this.id + '-hi' } });
    this.inputs = [make(0)].concat(two ? [make(1)] : []);
    this.inputs.forEach((el, i) => {
      el.value = String(two ? this.value[i] : this.value);
      el.addEventListener('input', () => this.set(two ? (i === 0 ? [Math.min(Number(el.value), this.value[1]), this.value[1]] : [this.value[0], Math.max(Number(el.value), this.value[0])]) : Number(el.value), true));
      this.row.appendChild(el);
    });
  }

  /** @private minus, the value, plus */
  stepper() {
    const o = this.o, btn = (text, label, d) => this.el('button', { cls: 'di-whatif-step', text: text, attrs: { type: 'button', 'aria-label': label + ' ' + (o.label || '') }, on: { click: () => this.set(this.value + d * o.step, true) } });
    this.row.append(btn('−', 'Decrease', -1), btn('+', 'Increase', 1));
  }

  /** @private draws the value (and puts it in the sliders) */
  paint() {
    const f = this.o.format, v = this.value;
    this.shown.textContent = Array.isArray(v) ? f(v[0]) + ' to ' + f(v[1]) : f(v);
    (this.inputs || []).forEach((el, i) => { const x = String(Array.isArray(v) ? v[i] : v); if (el.value !== x) { el.value = x; } });
  }

  /**
   * Sets the value (kept within min and max, to a step); with ask, the server is asked once it rests.
   * @param {(number|number[])} v
   * @param {boolean} [ask=false] a person's change: the debounced call and the events
   * @returns {void}
   */
  set(v, ask) {
    const o = this.o, fit = (x) => Math.max(o.min, Math.min(o.max, Math.round((Number(x) - o.min) / o.step) * o.step + o.min));
    this.value = Array.isArray(v) ? [fit(v[0]), fit(v[1])] : fit(v);
    this.paint();
    if (!ask) { return; }
    this.emit('input', { value: this.value });
    this.win.clearTimeout(this.timer);
    this.timer = this.win.setTimeout(() => this.ask(), o.debounce);
  }

  /**
   * Asks the server about the value now (an answer to an older value that arrives later is dropped).
   * @returns {Promise<void>}
   */
  ask() {
    const o = this.o, mine = ++this.seq, value = this.value;
    this.emit('change', { value: value });
    if (!o.call) { return Promise.resolve(); }
    this.host.setAttribute('aria-busy', 'true');
    return Promise.resolve().then(() => o.call(value)).then((result) => {
      if (mine !== this.seq) { return; }
      this.host.removeAttribute('aria-busy');
      this.out.className = 'di-whatif-answer';
      this.out.textContent = o.describe ? o.describe(result) : String(result);
      this.emit('result', { value: value, result: result });
    }, (error) => {
      if (mine !== this.seq) { return; }
      this.host.removeAttribute('aria-busy');
      showState(this.out, 'error', { text: (error && error.message) || String(error), compact: true, fix: { label: 'Retry', run: () => this.ask() } });
      this.emit('error', { error: error });
    });
  }

  /**
   * The value (a number, or [low, high] for a range).
   * @returns {(number|number[])}
   */
  getValue() { return Array.isArray(this.value) ? this.value.slice() : this.value; }

  /**
   * Sets the value without asking the server or an event.
   * @param {(number|number[])} v
   * @returns {void}
   */
  setValue(v) { this.set(v, false); }

  /**
   * Stops a pending call and takes the control out.
   * @returns {void}
   */
  destroy() { this.win.clearTimeout(this.timer); this.seq++; super.destroy(); }
}
