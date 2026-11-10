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
/* An on/off switch: one button (role switch, aria-checked) with its track, its words and an optional ? that says what it does. Click,
   Space or Enter turns it; setValue turns it without an event. The value is true or false. */
import { InputBase } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} SwitchOptions
 * @property {string} [label] the words beside the track
 * @property {string} [help] what the switch does, on its ? (title and aria-description)
 * @property {boolean} [value=false] on or off
 * @property {boolean} [disabled=false]
 */

/**
 * A switch. Events: change {value}.
 */
export class Switch extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {SwitchOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-switch');
    const o = this.o;
    this.value = o.value === true || o.value === 'true';
    this.button = this.el('button', { cls: 'di-switch-btn', attrs: { type: 'button', role: 'switch', 'aria-checked': String(this.value) } });
    this.button.appendChild(this.el('i', { cls: 'di-switch-track', attrs: { 'aria-hidden': 'true' } }));
    this.words = this.el('span', { cls: 'di-switch-label', text: o.label || '' });
    this.button.appendChild(this.words);
    host.appendChild(this.button);
    if (o.help) {
      this.q = this.el('span', { cls: 'di-switch-q', text: '?', attrs: { title: o.help, role: 'img', 'aria-label': o.help } });
      this.button.setAttribute('aria-description', o.help);
      host.appendChild(this.q);
    }
    this.button.addEventListener('click', () => { if (!this.o.disabled) { this.setValue(!this.value); this.emit('change', { value: this.value }); } });
    this.setDisabled(!!o.disabled);
    this.paint();
  }
  /** @private Draws the state: aria-checked and the host's di-on. */
  paint() {
    this.button.setAttribute('aria-checked', String(this.value));
    this.host.classList.toggle('di-on', this.value);
  }
  /**
   * The value: on or off.
   * @returns {boolean}
   */
  getValue() { return this.value; }
  /**
   * Turns it on or off, without an event.
   * @param {boolean} v on
   * @returns {void}
   */
  setValue(v) { this.value = v === true || v === 'true'; this.paint(); }
  /**
   * Changes the words beside the track.
   * @param {string} text the words
   * @returns {void}
   */
  setLabel(text) { this.o.label = text; this.words.textContent = text; }
  /**
   * Turns the switch off for use, or back on.
   * @param {boolean} off disabled
   * @returns {void}
   */
  setDisabled(off) {
    this.o.disabled = !!off;
    this.button.disabled = !!off;
    this.host.classList.toggle('di-disabled', !!off);
  }
  /**
   * Moves the focus to the switch.
   * @returns {void}
   */
  focus() { this.button.focus(); }
}
