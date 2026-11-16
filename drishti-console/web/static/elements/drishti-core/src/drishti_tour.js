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
/* A tour: a page walked in steps (a guided mode, a first-time presenter, a phone), one step's parts shown at a time, with Back, Next and
   the step in words. The page marks its parts; the tour only shows and hides them, so the cards stay the page's own components:

     <p data-tour-say="1">The sentence of step 1</p>                    shown at step 1 only
     <section data-tour-steps="2 3">…</section>                        shown at steps 2 and 3
     <button data-tour="back">  <span data-tour="at">  <button data-tour="next">   the controls (any of them may be left out)

   new Tour(root, { steps: 4, onStep: (n) => ... }) */
import { announce } from './drishti_a11y.js';

/**
 * The options of a tour.
 * @typedef {Object} TourOptions
 * @property {number} [steps] how many steps (default: the highest step the marks name)
 * @property {number} [at=1] the step shown first
 * @property {function(number): void} [onStep] told each step shown (after its parts are shown: a chart there may resize)
 * @property {function(number, number): string} [words] the step in words (default "Step 2 of 4")
 */

/**
 * A page walked in steps: the parts marked for a step shown, the others hidden, Back and Next and the step in words.
 */
export class Tour {
  /**
   * @param {Element} root the part of the page the tour walks
   * @param {TourOptions} [o]
   */
  constructor(root, o) {
    this.root = root;
    this.o = Object.assign({ at: 1, words: (n, of) => 'Step ' + n + ' of ' + of }, o);
    const named = [];
    root.querySelectorAll('[data-tour-say]').forEach((el) => named.push(Number(el.getAttribute('data-tour-say'))));
    root.querySelectorAll('[data-tour-steps]').forEach((el) => el.getAttribute('data-tour-steps').split(/\s+/).forEach((n) => named.push(Number(n))));
    this.steps = this.o.steps || Math.max.apply(null, named.filter((n) => n > 0).concat([1]));
    this.off = [];
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((b) => { b.addEventListener('click', fn); this.off.push(() => b.removeEventListener('click', fn)); });
    on('[data-tour="back"]', () => this.back());
    on('[data-tour="next"]', () => this.next());
    this.go(this.o.at);
  }

  /**
   * Shows a step (clamped to the steps there are).
   * @param {number} n
   * @returns {number} the step shown
   */
  go(n) {
    this.at = Math.max(1, Math.min(this.steps, Math.round(n) || 1));
    const at = String(this.at), r = this.root;
    r.setAttribute('data-tour-at', at);
    r.querySelectorAll('[data-tour-say]').forEach((el) => { el.hidden = el.getAttribute('data-tour-say') !== at; });
    r.querySelectorAll('[data-tour-steps]').forEach((el) => { el.hidden = el.getAttribute('data-tour-steps').split(/\s+/).indexOf(at) < 0; });
    const words = this.o.words(this.at, this.steps);
    r.querySelectorAll('[data-tour="at"]').forEach((el) => { el.textContent = words; });
    r.querySelectorAll('[data-tour="back"]').forEach((b) => { b.disabled = this.at <= 1; });
    r.querySelectorAll('[data-tour="next"]').forEach((b) => { b.disabled = this.at >= this.steps; });
    if (r.ownerDocument && r.ownerDocument.defaultView) { announce(words, { document: r.ownerDocument }); }
    if (this.o.onStep) { this.o.onStep(this.at); }
    return this.at;
  }

  /**
   * The next step.
   * @returns {number}
   */
  next() { return this.go(this.at + 1); }

  /**
   * The step before.
   * @returns {number}
   */
  back() { return this.go(this.at - 1); }

  /**
   * Stops the tour: every part shown again, the controls let go.
   * @returns {void}
   */
  destroy() {
    this.off.forEach((f) => f());
    this.root.querySelectorAll('[data-tour-say], [data-tour-steps]').forEach((el) => { el.hidden = false; });
    this.root.removeAttribute('data-tour-at');
  }
}
