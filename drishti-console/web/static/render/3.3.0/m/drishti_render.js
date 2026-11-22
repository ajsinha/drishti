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
/* The client renderer's API (docs/architecture/CLIENT_RENDERER.md), the same since 1.x, on Drishti Elements' painter (drishti-views): every
   panel is drawn by the painter; this file and the screen around it are Drishti's host part (where a ViewModel comes from, sign-in,
   navigation, About, the drishti:* events). One import, one call:

     <script type="module">
       import { render } from 'https://drishti.bank.example/static/render/<version>/drishti-render.js';
       render(document.getElementById('risk'), { kind: 'trade', id: 'IRS-48213' });
     </script>

   or one tag: <drishti-screen kind="trade" entity="IRS-48213"></drishti-screen>. Types: drishti-render.d.ts in the version's folder. */
import { ELEMENTS_VERSION } from '../../../vendor/drishti-elements/3.3.0/elements/drishti-core/src/drishti_index.js';
import { SPECS, kinds } from '../../../vendor/drishti-elements/3.3.0/elements/drishti-views/src/drishti_index.js';
import { Screen } from './drishti_screen.js';
import { problem } from './drishti_net.js';
import { trustedTypesActive } from './drishti_assets.js';

/** The renderer's version: the one version of Drishti Elements. */
export const VERSION = ELEMENTS_VERSION;
/** The ViewModel contract majors this renderer draws (the painter's: VIEWMODEL.md section 8, a ViewModel without a contract is 1). */
export const CONTRACTS = Object.freeze(SPECS.slice());
/** The panel kinds the painter draws. */
export const KINDS = Object.freeze(kinds());

const screens = new WeakMap();

/** A handle on one rendered screen: what render() returns. */
class Handle {
  constructor(s) { this._s = s; }
  /** The ViewModel shown now (a copy; patches keep it current). */
  get viewModel() { return this._s.view && this._s.view.viewModel ? this._s.view.viewModel : this._s.vm; }
  get container() { return this._s.container; }
  /** How many screens Back returns through. */
  get depth() { return this._s.stack.length; }
  /** The painter's handle (drishti-views ViewHandle) of the view shown now, or null. */
  get view() { return this._s.view; }
  patch(frame) { return this._s.patch(frame); }
  subscribe(source) { return this._s.subscribe(source); }
  unsubscribe() { this._s.unsubscribe(); }
  navigate(ref) { return this._s.open(ref, { source: 'api' }); }
  /** Opens the About sheet (about: true); about(false) closes it. */
  about(open) { this._s.about(open); }
  back() { return this._s.back(); }
  setTheme(theme) { this._s.theme(theme); }
  reload() { const ref = (this._s.vm || {}).ref; return ref ? this._s.load(ref, false) : Promise.resolve(this._s); }
  /** auth 'popup': who signed in ('' when nobody), and the host taking over: its own token ({token, expiresIn} or a string), or signing out. */
  get user() { return this._s.signin ? this._s.signin.user || '' : ''; }
  signIn(token) { if (this._s.signin) { this._s.signin.accept(typeof token === 'string' ? { access_token: token, expires_in: 300 } : { access_token: token.token, expires_in: token.expiresIn || 300, user: token.user }, 'signed-in'); } }
  signOut() { if (this._s.signin) { this._s.signin.drop('host'); } }
  dispose() { this._s.dispose(); screens.delete(this._s.container); }
}

function contractOk(vm) {
  const c = vm && vm.contract ? parseInt(String(vm.contract), 10) : 1;
  return CONTRACTS.includes(c);
}

/**
 * Paints a screen and returns its handle; handle.ready settles when the first paint is done. Rendering into a container again replaces
 * what it showed.
 * @param {HTMLElement} container where the screen is drawn
 * @param {(Object|{kind: string, id: string, controls: Object}|{stream: string})} source a ViewModel (the JSON of GET /api/v1/views/{kind}/{id});
 *   {kind, id} to fetch it as the person; {stream: url} for a live screen's stream
 * @param {Object} [options] see drishti-render.d.ts (every option has a default: shadow true, navigation 'in-place', live true, theme 'inherit')
 * @returns {Handle}
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
    if (source && source.stream) { s.subscribe(source.stream); return s; }
    if (source && source.kind && source.id) { return s.auth === 'popup' ? s.signedLoad(source) : s.load(source, false); }
    return s.fail(problem('DRS-9103', 'render() needs a ViewModel, {kind, id} or {stream}', 'render(el, { kind: "trade", id: "IRS-48213" }).'));
  })().then(() => handle);
  return handle;
}

/** patch(handle, frame): applies a live frame {seq, generation, patches} (VIEWMODEL.md section 5). */
export const patch = (handle, frame) => handle.patch(frame);
/** subscribe(handle, source?): follows the view's stream (default: this console's relay); returns the function that stops it. */
export const subscribe = (handle, source) => handle.subscribe(source);

/**
 * Defines <drishti-screen kind="trade" entity="IRS-48213"> on render() (once; a page with Elements 2.x has it as the alias of <drishti-view>).
 * @param {Window} win
 * @returns {void}
 */
export function defineScreen(win) {
  if (!win.customElements || win.customElements.get('drishti-screen')) { return; }
  win.customElements.define('drishti-screen', class extends win.HTMLElement {
    static get observedAttributes() { return ['kind', 'entity', 'theme', 'server', 'navigation', 'auth']; }
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
        navigation: this.getAttribute('navigation') || 'in-place' }, this.getAttribute('auth') ? { auth: this.getAttribute('auth') } : {}, this.options || {}));
    }
  });
}

/**
 * The renderer's page-wide names: window.DrishtiRender (version, render, patch, subscribe, KINDS, CONTRACTS, trustedTypes) and, after the
 * module graph has run, <drishti-screen>.
 * @param {Window} win
 * @returns {void}
 */
export function installRender(win) {
  win.DrishtiRender = Object.freeze({ version: VERSION, render, patch, subscribe, KINDS, CONTRACTS, trustedTypes: trustedTypesActive });
  if (typeof win.queueMicrotask === 'function') { win.queueMicrotask(() => defineScreen(win)); } else { defineScreen(win); }
}
