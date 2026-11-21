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
/* A button, and a group of buttons: the one way a screen draws a button (the no-bespoke-UI rule). A Button is a real <button type="button">
   with its words, an optional action name (data-action), an optional pressed state (aria-pressed; toggle: true flips it on a click), a
   role (tab, menuitem ...) with its selected state, aria-current, a title and extra data and aria attributes. Variants: plain (bordered, the
   default), primary, link (reads as a link), bare (no look of its own: the page's classes give it one). button(doc, o) makes the element
   in one call (the component is on el.component); a Button on a <button> element draws on that element. A ButtonGroup is a labelled row of
   buttons: mode "single" keeps exactly one pressed (a segmented control), "none" leaves the buttons alone. */
import { InputBase } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} ButtonOptions
 * @property {string} [label] the words
 * @property {string} [action] the action's name (data-action)
 * @property {string} [variant='plain'] plain, primary, link or bare
 * @property {boolean} [pressed] a toggle's state (aria-pressed); absent: not a toggle
 * @property {boolean} [toggle=false] a click flips pressed (otherwise the caller sets it)
 * @property {string} [role] the button's role (tab, menuitem ...)
 * @property {boolean} [selected] aria-selected, for a tab
 * @property {boolean} [current] aria-current
 * @property {string} [title] the tooltip
 * @property {boolean} [disabled=false]
 * @property {string} [cls] extra classes
 * @property {Object<string,string>} [data] data-* attributes (without the prefix)
 * @property {Object<string,string>} [attrs] other attributes (aria-controls, tabindex ...); null or undefined values are left out
 * @property {function(Event): void} [onClick] a click listener
 */

const VARIANTS = ['plain', 'primary', 'link', 'bare'];

/**
 * A button. Events: click {event}, change {pressed} (a toggle flipped by a click).
 */
export class Button extends InputBase {
  /**
   * @param {HTMLElement} host a <button> to draw on, or any element to hold a new one
   * @param {ButtonOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-button');
    const o = this.o;
    this.button = host.tagName === 'BUTTON' ? host : this.el('button');
    if (this.button === host) { host.classList.remove('di'); }       // a <button> is its own control: the page's classes (not the field's font) style it
    this.button.setAttribute('type', 'button');
    if (this.button !== host) { host.appendChild(this.button); }
    const variant = VARIANTS.includes(o.variant) ? o.variant : 'plain';
    this.button.classList.add('di-btn-' + variant);
    String(o.cls || '').split(/\s+/).filter(Boolean).forEach((c) => this.button.classList.add(c));
    Object.keys(o.data || {}).forEach((k) => this.button.setAttribute('data-' + k, String(o.data[k])));
    Object.keys(o.attrs || {}).forEach((k) => { if (o.attrs[k] !== null && o.attrs[k] !== undefined) { this.button.setAttribute(k, String(o.attrs[k])); } });
    if (o.action) { this.button.setAttribute('data-action', o.action); }
    if (o.role) { this.button.setAttribute('role', o.role); }
    if (o.title) { this.button.setAttribute('title', o.title); }
    this.setLabel(o.label || '');
    this.paint();
    this.button.addEventListener('click', (e) => {
      if (this.o.disabled) { return; }
      if (this.o.toggle && typeof this.o.pressed === 'boolean') { this.setPressed(!this.o.pressed); this.emit('change', { pressed: this.o.pressed }); }
      this.emit('click', { event: e });
      if (typeof this.o.onClick === 'function') { this.o.onClick(e); }
    });
    this.setDisabled(!!o.disabled);
  }
  /** @private Draws the state attributes. */
  paint() {
    const b = this.button, o = this.o;
    if (typeof o.pressed === 'boolean') { b.setAttribute('aria-pressed', String(o.pressed)); }
    if (typeof o.selected === 'boolean') { b.setAttribute('aria-selected', String(o.selected)); }
    if (typeof o.current === 'boolean') { b.setAttribute('aria-current', o.current ? 'true' : 'false'); }
  }
  /**
   * The words on the button.
   * @param {string} text
   * @returns {void}
   */
  setLabel(text) { this.o.label = text; if (this.button.textContent !== text) { this.button.textContent = text; } }
  /**
   * The pressed state of a toggle (false for a button that is not one).
   * @returns {boolean}
   */
  getValue() { return this.o.pressed === true; }
  /**
   * Sets the pressed state, without an event.
   * @param {boolean} on
   * @returns {void}
   */
  setValue(on) { this.setPressed(on); }
  /**
   * Sets the pressed state (aria-pressed), without an event.
   * @param {boolean} on
   * @returns {void}
   */
  setPressed(on) { this.o.pressed = on === true || on === 'true'; this.paint(); }
  /**
   * Sets the selected state of a tab (aria-selected), without an event.
   * @param {boolean} on
   * @returns {void}
   */
  setSelected(on) { this.o.selected = !!on; this.paint(); }
  /**
   * Turns the button off for use, or back on.
   * @param {boolean} off
   * @returns {void}
   */
  setDisabled(off) { this.o.disabled = !!off; this.button.disabled = !!off; this.host.classList.toggle('di-disabled', !!off); }
  /**
   * Moves the focus to the button.
   * @returns {void}
   */
  focus() { this.button.focus(); }
  /**
   * Takes the component off its element: the listeners stay on a <button> it drew on, so the button is left as it is.
   * @returns {void}
   */
  destroy() { this.events.clear(); }
}

/**
 * Makes a button element in one call.
 * @param {Document} doc
 * @param {ButtonOptions} options
 * @returns {HTMLButtonElement} the button; its Button is on .component
 */
export function button(doc, options) {
  const b = doc.createElement('button');
  b.component = new Button(b, options);
  return b;
}

/**
 * @typedef {Object} ButtonGroupOptions
 * @property {string} [label] the group's name (aria-label)
 * @property {ButtonOptions[]} buttons each with a value (data-value) in data or action
 * @property {string} [mode='none'] single: exactly one button is pressed and a click moves it; none: the buttons are left alone
 * @property {string} [value] the pressed button's value in mode single (the value of a button is its data.value, else its action)
 * @property {string} [cls] extra classes on the group
 */

/** @private the value a group knows a button by */
function valueOf(o) { return String((o.data && o.data.value) || o.action || o.label || ''); }

/**
 * A labelled row of buttons. Events: change {value} (mode single).
 */
export class ButtonGroup extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {ButtonGroupOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-button-group');
    const o = this.o;
    host.setAttribute('role', 'group');
    if (o.label) { host.setAttribute('aria-label', o.label); }
    String(o.cls || '').split(/\s+/).filter(Boolean).forEach((c) => host.classList.add(c));
    this.single = o.mode === 'single';
    this.value = o.value === undefined ? null : String(o.value);
    this.buttons = (o.buttons || []).map((b) => {
      const bo = Object.assign({}, b);
      if (this.single) { bo.pressed = valueOf(b) === this.value; }
      const el = button(this.doc, bo);
      el.component.value = valueOf(b);
      host.appendChild(el);
      if (this.single) { el.component.on('click', () => this.pick(el.component.value, true)); }
      return el;
    });
  }
  /**
   * The pressed button's value (mode single).
   * @returns {string|null}
   */
  getValue() { return this.value; }
  /**
   * Presses the button of a value, without an event.
   * @param {string} v
   * @returns {void}
   */
  setValue(v) { this.pick(v, false); }
  /** @private */
  pick(v, fire) {
    const changed = v !== this.value;
    this.value = v === null || v === undefined ? null : String(v);
    this.buttons.forEach((b) => b.component.setPressed(b.component.value === this.value));
    if (fire && changed) { this.emit('change', { value: this.value }); }
  }
}
