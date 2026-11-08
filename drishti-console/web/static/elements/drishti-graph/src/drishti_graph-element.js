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
/* <drishti-graph>: the web component, for hosts with any framework or none. Its data and options are properties (el.data = {nodes,
   edges}, el.options = {explain, expand, link, measures, types ...}; options.data serves when el.data is not set), its mode, density and layout attributes (mode="table"
   density="terminal" layout="layered"), its events DOM events on the element (drishti:selection-changed, drishti:path-found ...) and
   el.graph the plain JavaScript Graph with every method. No shadow root: the page's theme tokens and style sheet reach it.

     <drishti-graph mode="inspector" layout="force"></drishti-graph>
     <script>document.querySelector('drishti-graph').data = { nodes, edges };</script> */
import { Graph } from './drishti_graph-view.js';

/** The element's tag name. */
export const GRAPH_ELEMENT = 'drishti-graph';

/**
 * Defines <drishti-graph> in a registry (once; the bundle does it when it loads in a page).
 * @param {CustomElementRegistry} [registry] default: the window's
 * @param {Window} [win] the window whose HTMLElement the element extends
 * @returns {boolean} whether it was defined now
 */
export function defineGraphElement(registry, win) {
  const w = win || (typeof window !== 'undefined' ? window : null);
  const reg = registry || (w && w.customElements);
  if (!w || !reg || reg.get(GRAPH_ELEMENT)) { return false; }
  class DrishtiGraphElement extends w.HTMLElement {
    static get observedAttributes() { return ['mode', 'density', 'layout']; }

    constructor() { super(); this.graph = null; this._data = null; this._options = {}; }

    connectedCallback() { if (!this.graph && this._data) { this.mount(); } }

    disconnectedCallback() { if (this.graph) { this.graph.destroy(); this.graph = null; } }

    attributeChangedCallback(name, old, value) {
      if (!this.graph || old === value || !value) { return; }
      if (name === 'mode') { this.graph.setMode(value); }
      if (name === 'density') { this.graph.setDensity(value); }
      if (name === 'layout') { this.graph.setLayout(value); }
    }

    set data(d) { this._data = d; if (this.isConnected) { if (this.graph) { this.graph.setData(d); } else { this.mount(); } } }

    get data() { return this._data; }

    set options(o) { this._options = o || {}; if (this._options.data && !this._data) { this._data = this._options.data; } if (this.isConnected && this._data) { this.mount(); } }

    get options() { return this._options; }

    mount() {
      if (this.graph) { this.graph.destroy(); }
      const o = Object.assign({}, this._options, { data: this._data });
      ['mode', 'density', 'layout'].forEach((a) => { if (this.getAttribute(a)) { o[a] = this.getAttribute(a); } });
      this.graph = new Graph(this, o);
      this.dispatchEvent(new w.CustomEvent('drishti:ready', { detail: { graph: this.graph }, bubbles: true }));
    }
  }
  reg.define(GRAPH_ELEMENT, DrishtiGraphElement);
  return true;
}
