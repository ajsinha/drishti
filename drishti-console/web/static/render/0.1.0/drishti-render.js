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
/* Drishti Render, the client-side renderer (docs/architecture/CLIENT_RENDERER.md): paints a Drishti screen from the server's ViewModel
   JSON, in the browser, with no build step and no framework. One import, one call:

     <script type="module">
       import { render } from 'https://drishti.bank.example/static/render/0.1.0/drishti-render.js';
       render(document.getElementById('risk'), { kind: 'trade', id: 'IRS-48213' });
     </script>

   or one tag: <drishti-screen kind="trade" entity="IRS-48213"></drishti-screen>. Plain ES modules (m/*.js), the vendored ECharts of the
   console that serves them, nothing from a CDN. Types: drishti-render.d.ts beside this file. */
import { Screen } from './m/screen.js';
import { KINDS } from './m/kinds.js';
import { problem } from './m/net.js';
import { trustedTypesActive } from './m/assets.js';

export const VERSION = '0.1.0';
/** The ViewModel contract majors this renderer draws (VIEWMODEL.md section 8: a ViewModel without a contract is 1). */
export const CONTRACTS = Object.freeze([1]);

const screens = new WeakMap();

/** A handle on one rendered screen: what render() returns. */
class Handle {
  constructor(s) { this._s = s; }
  /** The ViewModel shown now (a copy; patches keep it current). */
  get viewModel() { return this._s.vm; }
  get container() { return this._s.container; }
  /** How many screens Back returns through. */
  get depth() { return this._s.stack.length; }
  patch(frame) { return this._s.patch(frame); }
  apply(message) { return this._s.apply(message); }
  subscribe(source) { return this._s.subscribe(source); }
  unsubscribe() { this._s.unsubscribe(); }
  navigate(ref) { return this._s.open(ref, { source: 'api' }); }
  back() { return this._s.back(); }
  setTheme(theme) { this._s.theme(theme); }
  reload() { const ref = (this._s.vm || {}).ref; return ref ? this._s.load(ref, false) : Promise.resolve(this._s); }
  dispose() { this._s.dispose(); screens.delete(this._s.container); }
}

function contractOk(vm) {
  const c = vm && vm.contract ? parseInt(String(vm.contract), 10) : 1;
  return CONTRACTS.includes(c);
}

/**
 * render(container, source, options): paints a screen and returns its handle; `handle.ready` settles when the first paint is done.
 *   source: a ViewModel (the JSON of GET /api/v1/views/{kind}/{id}); or {kind, id[, controls]} to fetch it as the person; or
 *           {stream: url} for a streamed (GenUI) or live screen; or an async iterable of stream messages.
 *   options: see drishti-render.d.ts (every option has a default: shadow true, navigation 'in-place', live true, theme 'inherit').
 * Rendering into a container again replaces what it showed.
 */
export function render(container, source, options) {
  const old = container && screens.get(container);
  if (old) { old.dispose(); }
  const s = new Screen(container, options);
  const handle = new Handle(s);
  screens.set(container, handle);
  handle.ready = (async () => {
    if (source && Array.isArray(source.panels)) {
      if (!contractOk(source)) { return s.fail(problem('DRS-9105', 'this ViewModel speaks contract ' + source.contract + '; this renderer draws ' + CONTRACTS.join(', '), 'Load the renderer version this server serves.')); }
      await s.paint(source);
      if (s.o.live !== false && s.o.subscribe) { s.subscribe(s.o.subscribe === true ? undefined : s.o.subscribe); }
      return s;
    }
    if (source && source.stream) { s.streaming = true; s.subscribe(source.stream); return s; }
    if (source && typeof source[Symbol.asyncIterator] === 'function') {
      for await (const m of source) { await s.apply(m); }
      return s;
    }
    if (source && source.kind && source.id) { return s.load(source, false); }
    return s.fail(problem('DRS-9103', 'render() needs a ViewModel, {kind, id} or {stream}', 'render(el, { kind: "trade", id: "IRS-48213" }).'));
  })().then(() => handle);
  return handle;
}

/** patch(handle, frame): applies a live frame {seq, generation, patches} (VIEWMODEL.md section 5). */
export const patch = (handle, frame) => handle.patch(frame);
/** subscribe(handle, source?): follows the view's stream (default: this console's relay); returns the function that stops it. */
export const subscribe = (handle, source) => handle.subscribe(source);
/** The kinds this renderer draws. */
export { KINDS };

// ---- <drishti-screen kind="trade" entity="IRS-48213">: the same in one tag (light: the Elements' <drishti-view> moves onto it in phase 2)
if (typeof customElements !== 'undefined' && !customElements.get('drishti-screen')) {
  customElements.define('drishti-screen', class extends HTMLElement {
    static get observedAttributes() { return ['kind', 'entity', 'theme', 'server', 'navigation']; }
    connectedCallback() { this._go(); }
    attributeChangedCallback(n, a, b) {
      if (a === b || !this.isConnected) { return; }
      if (n === 'theme' && this.handle) { this.handle.setTheme(b); return; }
      this._go();
    }
    disconnectedCallback() { if (this.handle) { this.handle.dispose(); this.handle = null; } }
    _go() {
      const kind = this.getAttribute('kind'), id = this.getAttribute('entity') || this.getAttribute('id-ref');
      if (!kind || !id) { return; }
      this.handle = render(this, { kind, id }, Object.assign({ theme: this.getAttribute('theme') || 'inherit', server: this.getAttribute('server') || '',
        navigation: this.getAttribute('navigation') || 'in-place' }, this.options || {}));
    }
  });
}

if (typeof window !== 'undefined') {
  window.DrishtiRender = Object.freeze({ version: VERSION, render, patch, subscribe, KINDS, CONTRACTS, trustedTypes: trustedTypesActive });
}
