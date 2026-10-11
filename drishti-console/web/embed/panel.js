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
/* <drishti-panel>: ONE panel of a view in a card of the host's own (docs/architecture/ELEMENTS.md section 5, build step 14). It is <drishti-view> with the
   `panel` attribute: the same attributes, properties, methods and events, the header off unless the host asks for one, and `panel="legs"` meaning
   `panels="legs"`. The view is still built whole by the console (a panel can read the view's data); the element shows the one panel, live. */
import { DrishtiView } from './element.js';

export class DrishtiPanel extends DrishtiView {
  connectedCallback() {
    if (!this.hasAttribute('header')) { this.setAttribute('header', 'none'); }
    this.#sync();
    super.connectedCallback();
  }
  attributeChangedCallback(name, was, now) {
    if (name === 'panel') { this.#sync(); }
    super.attributeChangedCallback(name, was, now);
  }
  #sync() {
    const id = (this.getAttribute('panel') || '').trim();
    if (id) { if (this.getAttribute('panels') !== id) { this.setAttribute('panels', id); } } else if (this.hasAttribute('panels')) { this.removeAttribute('panels'); }
  }
  /** The panel shown. */
  get panel() { return this.getAttribute('panel') || ''; }
  set panel(v) { if (v == null || v === '') { this.removeAttribute('panel'); } else { this.setAttribute('panel', String(v)); } }
}

if (!customElements.get('drishti-panel')) { customElements.define('drishti-panel', DrishtiPanel); }
