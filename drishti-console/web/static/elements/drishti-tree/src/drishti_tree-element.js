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
/* <drishti-tree>: the web component, for hosts with any framework or none. Its hierarchy is a property (el.data = {rows, measures}, or
   options.data), its options too (el.options = {serverMeasures, explain, link, editable, ...}), its mode and density attributes (mode="org"
   density="terminal"), its events DOM events on the element (drishti:selection-changed, drishti:proposal ...) and el.tree the plain
   JavaScript Tree with every method. No shadow root: the page's theme tokens and style sheets reach it.

     <drishti-tree mode="grid" density="compact"></drishti-tree>
     <script>document.querySelector('drishti-tree').data = { rows, measures: [{ field: 'count' }] };</script> */
import { Tree } from './drishti_tree-view.js';

/** The element's tag name. */
export const TREE_ELEMENT = 'drishti-tree';

/**
 * Defines <drishti-tree> in a registry (once; the bundle does it when it loads in a page).
 * @param {CustomElementRegistry} [registry] default: the window's
 * @param {Window} [win] the window whose HTMLElement the element extends
 * @returns {boolean} whether it was defined now
 */
export function defineTreeElement(registry, win) {
  const w = win || (typeof window !== 'undefined' ? window : null);
  const reg = registry || (w && w.customElements);
  if (!w || !reg || reg.get(TREE_ELEMENT)) { return false; }
  class DrishtiTreeElement extends w.HTMLElement {
    static get observedAttributes() { return ['mode', 'density']; }

    constructor() { super(); this.tree = null; this._data = null; this._options = {}; }

    connectedCallback() { if (!this.tree && this._data) { this.mount(); } }

    disconnectedCallback() { if (this.tree) { this.tree.destroy(); this.tree = null; } }

    attributeChangedCallback(name, old, value) {
      if (!this.tree || old === value || !value) { return; }
      if (name === 'mode') { this.tree.setMode(value); }
      if (name === 'density') { this.tree.setDensity(value); }
    }

    set data(d) { this._data = d; if (this.isConnected) { if (this.tree) { this.tree.setData(d); } else { this.mount(); } } }

    get data() { return this._data; }

    set options(o) { this._options = o || {}; if (this._options.data) { this._data = this._options.data; } if (this.isConnected && this._data) { this.mount(); } }

    get options() { return this._options; }

    mount() {
      if (this.tree) { this.tree.destroy(); }
      const o = Object.assign({}, this._options, { data: this._data });
      if (this.getAttribute('mode')) { o.mode = this.getAttribute('mode'); }
      if (this.getAttribute('density')) { o.density = this.getAttribute('density'); }
      this.tree = new Tree(this, o);
      this.dispatchEvent(new w.CustomEvent('drishti:ready', { detail: { tree: this.tree }, bubbles: true }));
    }
  }
  reg.define(TREE_ELEMENT, DrishtiTreeElement);
  return true;
}
