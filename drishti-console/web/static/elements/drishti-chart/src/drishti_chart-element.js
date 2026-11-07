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
/* <drishti-chart>: the web component, for hosts with any framework or none. Its spec is a property (el.spec = {...}), its options too
   (el.options = {explain, link, ...}), its mode, density and map mode attributes (mode="studio" density="terminal" map-mode="table"),
   its events DOM events on the element (drishti:mark-clicked, drishti:selection-changed, drishti:level-changed ...) and el.chart the
   plain JavaScript Chart with every method. No shadow root: the page's theme tokens and style sheet reach it.

     <drishti-chart density="compact"></drishti-chart>
     <script>document.querySelector('drishti-chart').spec = { kind: 'bar', data: { rows }, x: 'desk', y: 'var' };</script> */
import { Chart } from './drishti_chart-view.js';

/** The element's tag name. */
export const CHART_ELEMENT = 'drishti-chart';

/**
 * Defines <drishti-chart> in a registry (once; the bundle does it when it loads in a page).
 * @param {CustomElementRegistry} [registry] default: the window's
 * @param {Window} [win] the window whose HTMLElement the element extends
 * @returns {boolean} whether it was defined now
 */
export function defineChartElement(registry, win) {
  const w = win || (typeof window !== 'undefined' ? window : null);
  const reg = registry || (w && w.customElements);
  if (!w || !reg || reg.get(CHART_ELEMENT)) { return false; }
  class DrishtiChartElement extends w.HTMLElement {
    static get observedAttributes() { return ['mode', 'density', 'map-mode']; }

    constructor() { super(); this.chart = null; this._spec = null; this._options = {}; }

    connectedCallback() { if (!this.chart && this._spec) { this.mount(); } }

    disconnectedCallback() { if (this.chart) { this.chart.destroy(); this.chart = null; } }

    attributeChangedCallback(name, old, value) {
      if (!this.chart || old === value || !value) { return; }
      if (name === 'mode') { this.chart.setMode(value); }
      if (name === 'density') { this.chart.setDensity(value); }
      if (name === 'map-mode') { this.chart.setMapMode(value); }
    }

    set spec(s) { this._spec = s; if (this.isConnected) { if (this.chart) { this.chart.setSpec(s); } else { this.mount(); } } }

    get spec() { return this._spec; }

    set options(o) { this._options = o || {}; if (this.isConnected && this._spec) { this.mount(); } }

    get options() { return this._options; }

    mount() {
      if (this.chart) { this.chart.destroy(); }
      const o = Object.assign({}, this._options);
      if (this.getAttribute('mode')) { o.mode = this.getAttribute('mode'); }
      if (this.getAttribute('density')) { o.density = this.getAttribute('density'); }
      if (this.getAttribute('map-mode')) { o.mapMode = this.getAttribute('map-mode'); }
      this.chart = new Chart(this, this._spec, o);
      this.dispatchEvent(new w.CustomEvent('drishti:ready', { detail: { chart: this.chart }, bubbles: true }));
    }
  }
  reg.define(CHART_ELEMENT, DrishtiChartElement);
  return true;
}
