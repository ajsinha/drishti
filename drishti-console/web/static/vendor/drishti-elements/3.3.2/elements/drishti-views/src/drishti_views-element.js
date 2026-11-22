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
/* <elements-view>: a view as an element, a thin wrapper over paint(). Its ViewModel is the property viewModel, the address in src, or an
   inline <script type="application/json"> child (a page that is one file); the options are the property options, and the attributes
   density, header, panels, locale, validate, label, controls (the control values the reader set: "legs=2&when=2026-01-01..") and live (the
   events address of its live stream) over them; the property controls reads the values the reader has set since, as query text. Changing src, the
   ViewModel or an attribute paints again; leaving the page disposes the view. The tag is the library's own (no host's tag collides);
   define({ tag }) registers it under another name. */
import { paint } from './drishti_views-paint.js';

/** The element's tag. */
export const VIEW_ELEMENT = 'elements-view';

/** The attributes the element reads (each as the option of the same name; live is the events address). */
export const VIEW_ATTRIBUTES = Object.freeze(['src', 'density', 'header', 'panels', 'locale', 'validate', 'label', 'live', 'complete', 'controls']);

/**
 * Registers <elements-view> (or another tag) in a window's custom elements; nothing when the window has none or the tag is taken.
 * @param {{tag: string, window: Object}} [o] tag: another name for a host's own; window: default the page's
 * @returns {boolean} whether it was registered now
 */
export function define(o) {
  o = o || {};
  const w = o.window || (typeof window !== 'undefined' ? window : null), tag = o.tag || VIEW_ELEMENT;
  if (!w || !w.customElements || !w.HTMLElement || w.customElements.get(tag)) { return false; }
  class ElementsView extends w.HTMLElement {
    static get observedAttributes() { return VIEW_ATTRIBUTES.slice(); }

    constructor() { super(); this._vm = null; this._options = {}; this.handle = null; this._queued = false; }

    connectedCallback() { this.repaint(); }

    disconnectedCallback() { if (this.handle) { this.handle.dispose(); this.handle = null; } }

    attributeChangedCallback(name, old, value) { if (old !== value && this.isConnected) { this.repaint(); } }

    /** The ViewModel as an object (painted again when set). */
    set viewModel(vm) { this._vm = vm || null; if (this.isConnected) { this.repaint(); } }

    get viewModel() { return this.handle && this.handle.viewModel ? this.handle.viewModel : this._vm; }

    /** paint()'s options; the attributes are over them. */
    set options(o) { this._options = o || {}; if (this.isConnected) { this.repaint(); } }

    get options() { return this._options; }

    /** The control values the reader set, as query text (ctl.legs=2&...); setting it paints again with those values. */
    get controls() { return this.handle ? this.handle.controlQuery() : (this.getAttribute('controls') || ''); }

    set controls(v) { if (v == null || v === '') { this.removeAttribute('controls'); } else { this.setAttribute('controls', String(v)); } }

    /** @private one paint per task, however many changes */
    repaint() {
      if (this._queued) { return; }
      this._queued = true;
      Promise.resolve().then(() => { this._queued = false; if (this.isConnected) { this.draw(); } });
    }

    /** @private */
    draw() {
      const source = this._vm || this.getAttribute('src') || this.inline();
      if (!source) { return; }
      const o = Object.assign({}, this._options);
      ['density', 'header', 'locale', 'validate', 'label'].forEach((a) => { if (this.hasAttribute(a)) { o[a] = this.getAttribute(a); } });
      if (this.hasAttribute('complete')) { o.complete = true; }
      if (this.hasAttribute('controls')) { o.controls = this.getAttribute('controls'); }
      if (this.hasAttribute('panels')) { o.panels = this.getAttribute('panels').split(',').map((s) => s.trim()).filter(Boolean); }
      if (this.getAttribute('live')) { o.live = Object.assign({}, o.live || {}, { url: this.getAttribute('live') }); }
      this.handle = paint(this, source, o);
      this.handle.ready.then(() => this.dispatchEvent(new w.CustomEvent('elements-view:painted', { detail: { handle: this.handle }, bubbles: true, composed: true })));
    }

    /** @private an inline ViewModel: a <script type="application/json"> child */
    inline() {
      const s = this.querySelector('script[type="application/json"]');
      if (!s) { return null; }
      try { return JSON.parse(s.textContent); } catch (e) { return null; }
    }
  }
  w.customElements.define(tag, ElementsView);
  return true;
}
