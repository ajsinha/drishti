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
/* <drishti-grid>: the web component, for hosts with any framework or none. Its options are a property (el.options = {...}), its layout and
   density attributes (layout="terminal" density="compact"), its events DOM events on the element (drishti:cell-clicked,
   drishti:pivot-changed, drishti:explain-shown ...) and el.grid the plain JavaScript Grid with every method. No shadow root: the page's
   theme tokens and style sheet reach it, as they reach the rest of the page.

     <drishti-grid layout="terminal" density="compact"></drishti-grid>
     <script>document.querySelector('drishti-grid').options = { columns, rows, pivot: { rows: ['DESK'], values: [{ colId: 'VAR' }] } };</script> */
import { Grid } from './drishti_grid-view.js';

/** The element's tag name. */
export const ELEMENT_NAME = 'drishti-grid';

/**
 * Defines <drishti-grid> in a registry (once; the bundle does it when it loads in a page).
 * @param {CustomElementRegistry} [registry] default: the window's
 * @param {Window} [win] the window whose HTMLElement the element extends (default: the page's)
 * @returns {boolean} whether it was defined now (false: no registry, or defined already)
 */
export function defineGridElement(registry, win) {
  const w = win || (typeof window !== 'undefined' ? window : null);
  const reg = registry || (w && w.customElements);
  if (!w || !reg || reg.get(ELEMENT_NAME)) { return false; }
  class DrishtiGridElement extends w.HTMLElement {
    static get observedAttributes() { return ['layout', 'density']; }

    constructor() { super(); this.grid = null; this._options = null; }

    connectedCallback() { if (!this.grid && this._options) { this.mount(); } }

    attributeChangedCallback(name, old, value) {
      if (!this.grid || old === value || !value) { return; }
      if (name === 'layout' && value !== this.grid.model.layout) { this.grid.setLayout(value); }
      if (name === 'density' && value !== this.grid.model.density) { this.grid.setDensity(value); }
    }

    set options(o) { this._options = o; if (this.isConnected) { this.mount(); } }

    get options() { return this._options; }

    mount() {
      if (this.grid) { this.grid.destroy(); }
      const o = Object.assign({}, this._options);
      if (this.getAttribute('layout')) { o.layout = this.getAttribute('layout'); }
      if (this.getAttribute('density')) { o.density = this.getAttribute('density'); }
      this.grid = new Grid(this, o);
      this.dispatchEvent(new w.CustomEvent('drishti:ready', { detail: { grid: this.grid }, bubbles: true }));
    }
  }
  reg.define(ELEMENT_NAME, DrishtiGridElement);
  return true;
}
