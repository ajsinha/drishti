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
/* Drishti Elements 2.3 (CLIENT_RENDERER.md sections 13 and 19; part of drishti-renderer, decision O7): <drishti-view> and <drishti-panel>
   drawn by the renderer from the ViewModel of the embed API v2 (/embed/v2/views/{kind}/{id}). The element API is 2.2's, unchanged: the
   same attributes, properties, methods, events, slots, page-wide token provider and one live connection per page (drishti_embed.js); only
   the inside changed (the panels are drawn with drishti-grid and drishti-chart). <drishti-screen> is an alias of <drishti-view>. The
   classes are made for a window (elementClasses), so the library loads under Node and in a page without custom elements. */
import { render } from './drishti_render.js';
import { embedRecords } from './drishti_console.js';
import { cfg, API, serverOf, usePopup, getToken, providerChanged, tokenNeeders as needers, connectionFor } from './drishti_embed.js';

// ---- <drishti-view> 2.0 ----------------------------------------------------------------------------------------------------------------
const enc = (s) => encodeURIComponent(String(s));
const idPath = (id) => String(id).split('/').map(enc).join('/');
const ATTRS = ['kind', 'entity', 'as-of', 'known-at', 'theme', 'density', 'header', 'live', 'server', 'panels', 'panel', 'label', 'controls', 'navigation', 'auth'];
const RELOAD = new Set(['kind', 'entity', 'as-of', 'known-at', 'header', 'live', 'server', 'panels', 'controls', 'navigation', 'auth']);

/**
 * The element classes for a window: DrishtiView (<drishti-view>, <drishti-screen>) and DrishtiPanel (<drishti-panel>).
 * @param {Window} win
 * @returns {{DrishtiView: Function, DrishtiPanel: Function}}
 */
export function elementClasses(win) {
  if (classes) { return classes; }
  class DrishtiView extends win.HTMLElement {
    static get observedAttributes() { return ATTRS; }
    constructor() {
      super();
      this._state = 'idle'; this._queued = false; this._unsub = null; this._handle = null; this._view = null; this._lastError = null;
      this.attachShadow({ mode: 'open' });                       // the renderer draws into this shadow root (its .view, as 1.x's), beside the slots
      this._slots = {};
      for (const name of ['loading', 'empty', 'error']) { const s = document.createElement('slot'); s.name = name; s.hidden = true; s.setAttribute('data-r-keep', ''); this.shadowRoot.appendChild(s); this._slots[name] = s; }
      // the renderer's events are dispatched on the element itself, as 1.x's; here only what the element does with them
      ['loaded', 'error'].forEach((n) => this.addEventListener('drishti:' + n, (e) => this._seen(e)));
      this.addEventListener('drishti:auth-required', () => this._setState('sign-in'));          // 2.1: the sign-in button is shown in place
      this.addEventListener('drishti:navigate', (e) => this._navigate(e));
    }
    get kind() { return this.getAttribute('kind') || ''; } set kind(v) { this._attr('kind', v); }
    get entity() { return this.getAttribute('entity') || ''; } set entity(v) { this._attr('entity', v); }
    get asOf() { return this.getAttribute('as-of') || 'live'; } set asOf(v) { this._attr('as-of', v); }
    get theme() { return this.getAttribute('theme') || 'light'; } set theme(v) { this._attr('theme', v); }
    get server() { return (this.getAttribute('server') || serverOf()).replace(/\/$/, ''); }
    get controls() { return this.getAttribute('controls') || ''; } set controls(v) { this._attr('controls', v); }
    get state() { return this._state; }
    get view() { return this._view; }
    get lastError() { return this._lastError; }
    get handle() { return this._handle; }
    set tokenProvider(fn) { cfg.tokenProvider = fn; providerChanged(); if (this._state === 'error') { this.reload(); } }
    get tokenProvider() { return cfg.tokenProvider; }
    _attr(n, v) { if (v == null || v === '') { this.removeAttribute(n); } else { this.setAttribute(n, String(v)); } }
    connectedCallback() { this._offToken = this._offToken || ((fn) => { needers.add(fn); return () => needers.delete(fn); })((reason) => this._emit('token-needed', { reason })); this._schedule(); }
    disconnectedCallback() { this._teardown(); }
    attributeChangedCallback(n, a, b) {
      if (a === b) { return; }
      if (n === 'theme' && this._handle) { this._handle.setTheme(b || 'light'); return; }
      if (RELOAD.has(n) && this.isConnected) { this._schedule(); }
    }
    _emit(name, detail, cancelable = false) { const e = new CustomEvent('drishti:' + name, { detail, bubbles: true, composed: true, cancelable }); this.dispatchEvent(e); return e; }
    _seen(e) {
      if (e.type === 'drishti:loaded') { this._painted(); }
      if (e.type === 'drishti:error') { this._lastError = e.detail; if (!this._view || e.detail.fatal !== false) { this._setState('error'); } }
    }
    /** An entity link, as 1.x: unless the host cancels drishti:navigate, the element shows that entity (its kind and entity attributes change).
        navigation="in-place" draws it in place with a Back bar instead; "host" leaves it to the host. */
    _navigate(e) {
      if (this.getAttribute('navigation') || e.detail.target !== 'entity') { return; }
      queueMicrotask(() => { if (!e.defaultPrevented) { this.setAttribute('kind', e.detail.kind); this.setAttribute('entity', e.detail.id); } });
    }
    _setState(s) {
      if (s === this._state) { return; }
      const previous = this._state; this._state = s; this.setAttribute('data-state', s);
      const none = !this._view;
      this._slots.loading.hidden = !(none && s === 'loading'); this._slots.empty.hidden = !(none && s === 'idle'); this._slots.error.hidden = !(none && s === 'error');
      this._emit('state', { state: s, previous });
    }
    _schedule() { if (this._queued) { return; } this._queued = true; queueMicrotask(() => { this._queued = false; this.reload(); }); }
    _teardown() { if (this._unsub) { this._unsub(); this._unsub = null; } if (this._handle) { this._handle.dispose(); this._handle = null; } if (this._offToken) { this._offToken(); this._offToken = null; } this._setState('idle'); }
    _query() {
      const q = new URLSearchParams();
      if (this.asOf !== 'live') { q.set('asOf', this.asOf); }
      if (this.getAttribute('known-at')) { q.set('knownAt', this.getAttribute('known-at')); }
      for (const [k, v] of new URLSearchParams(this.controls)) { q.set(k.startsWith('ctl.') ? k : 'ctl.' + k, v); }
      return q;
    }
    /** Loads the view again (a new subscription epoch). */
    reload() {
      if (this._unsub) { this._unsub(); this._unsub = null; }
      if (!this.kind || !this.entity) { this._setState('idle'); return Promise.resolve(); }
      this._setState('loading');
      const server = this.server, only = (this.getAttribute('panels') || '').split(',').map((s) => s.trim()).filter(Boolean);
      const views = (r, extra) => server + API + '/views/' + enc(r.kind) + '/' + idPath(r.id) + (extra || '');
      const q = this._query(), qs = q.toString() ? '?' + q : '';
      const popup = (this.getAttribute('auth') || cfg.auth) === 'popup';
      if (popup) { usePopup(server); }
      this._handle = render(this, { kind: this.kind, id: this.entity }, {
        theme: this.theme, server, credentials: 'omit', behaviours: 'console', about: true, live: false, auth: popup ? 'popup' : 'token',
        navigation: this.getAttribute('navigation') || 'host', header: this.getAttribute('header') || 'full', label: this.getAttribute('label') || undefined,
        panels: only.length && !only.includes('none') ? only : (only.includes('none') ? ['\u0000none'] : undefined),
        token: () => getToken(false),
        viewUrl: (r) => views(r, r.id === this.entity && r.kind === this.kind ? qs : ''),
        aboutUrl: (r, g) => views(r, '/about' + (g ? '?generation=' + g : '')),
        recordsUrl: embedRecords(server, API),
        panelsUrl: (query) => views({ kind: this.kind, id: this.entity }, '/panels?' + query)
      });
      return this._handle.ready.then(() => { if (this._handle && this._handle.viewModel) { this._painted(); } });
    }
    refresh() { return this._handle ? this._handle.reload() : this.reload(); }
    _painted() {
      const vm = this._handle && this._handle.viewModel;
      if (!vm) { return; }
      this._view = { ref: vm.ref, title: vm.title, generation: (vm.provenance || {}).generation || 0, provenance: vm.provenance, panels: (vm.panels || []).map((p) => ({ id: p.id, kind: p.kind, title: p.title })) };
      this._lastError = null;
      const live = !!(vm.provenance || {}).live && this.getAttribute('live') !== 'off' && this.asOf === 'live';
      this._setState(live ? 'live' : 'static');
      if ((vm.provenance || {}).masked > 0) { this._emit('masked', { count: vm.provenance.masked, panels: vm.provenance.maskedPanels || [] }); }
      const q = [...this._query()].filter(([k]) => k.startsWith('ctl.')).map(([k, v]) => enc(k) + '=' + enc(v)).join('&');
      const key = 'vm:' + vm.ref.kind + '/' + vm.ref.id + (q ? '?' + q : '');
      if (this._unsub && this._key !== key) { this._unsub(); this._unsub = null; }        // shown in place: the new entity's stream
      if (live && !this._unsub) {
        this._key = key;
        this._handle.subscribe((on) => {
          this._unsub = connectionFor(this.server).subscribe(key, {
            frame: (f) => on.frame(f), view: () => {}, gone: (d) => { on.gone({ code: (d && d.code) || 'DRS-5003', detail: d && d.detail }); this._setState('static'); },
            state: (s) => this._setState(s), reconnected: () => { this._setState('live'); this.refresh(); }
          });
          return () => { if (this._unsub) { this._unsub(); this._unsub = null; } };
        });
      }
    }
  }

  /** <drishti-panel>: one panel of a view in a card of the host's own (the header off unless asked; panel="legs" is panels="legs"). */
  class DrishtiPanel extends DrishtiView {
    connectedCallback() { if (!this.hasAttribute('header')) { this.setAttribute('header', 'none'); } this._sync(); super.connectedCallback(); }
    attributeChangedCallback(n, a, b) { if (n === 'panel') { this._sync(); } super.attributeChangedCallback(n, a, b); }
    _sync() { const id = (this.getAttribute('panel') || '').trim(); if (id) { if (this.getAttribute('panels') !== id) { this.setAttribute('panels', id); } } else if (this.hasAttribute('panels')) { this.removeAttribute('panels'); } }
    get panel() { return this.getAttribute('panel') || ''; }
    set panel(v) { this._attr('panel', v); }
  }

  classes = { DrishtiView, DrishtiPanel };
  return classes;
}
let classes = null;

/**
 * Defines the tags (once per page; a page that loaded Elements 1.x keeps its own).
 * @param {Window} win
 * @returns {{DrishtiView: Function, DrishtiPanel: Function}}
 */
export function define(win) {
  const reg = win.customElements, c = elementClasses(win);
  if (!reg.get('drishti-view')) { reg.define('drishti-view', c.DrishtiView); }
  if (!reg.get('drishti-panel')) { reg.define('drishti-panel', c.DrishtiPanel); }
  if (!reg.get('drishti-screen')) { reg.define('drishti-screen', class extends c.DrishtiView {}); }     // the preview's tag: an alias
  return c;
}
