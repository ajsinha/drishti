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
/* <drishti-about>: the "About this page" layers of one view (where each number comes from, what the sources are, the masks and rules that applied to this reader)
   in a panel of the host's own, without the view (docs/guides/ELEMENTS.md). It signs in like <drishti-view> (the host's token provider; scope embed:about), asks
   the console for the About body, and draws it sanitised in a Shadow DOM. Links in it open the console in a new tab. */
import { API, cfg, esc, problem } from './config.js';
import { authed, onTokenNeeded, providerChanged } from './tokens.js';
import { sheet, frameSheet, font } from './assets.js';
import { setHtml } from './sanitize.js';

const ATTRS = ['kind', 'entity', 'theme', 'server', 'label'];
const RELOAD = new Set(['kind', 'entity', 'server']);

export class DrishtiAbout extends HTMLElement {
  static get observedAttributes() { return ATTRS; }
  #seq = 0; #ctrl = null; #state = 'idle'; #queued = false; #ready = null; #lastError = null; #offToken = null; #cleanup = false;

  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
  }
  get kind() { return this.getAttribute('kind') || ''; } set kind(v) { this.#attr('kind', v); }
  get entity() { return this.getAttribute('entity') || ''; } set entity(v) { this.#attr('entity', v); }
  get theme() { return this.getAttribute('theme') || 'light'; } set theme(v) { this.#attr('theme', v); }
  get server() { return (this.getAttribute('server') || cfg.server).replace(/\/$/, ''); }
  get state() { return this.#state; }
  get lastError() { return this.#lastError; }
  set tokenProvider(fn) { cfg.tokenProvider = fn; providerChanged(); if (this.#state === 'error' && this.#lastError && this.#lastError.status === 401) { this.reload(); } }
  get tokenProvider() { return cfg.tokenProvider; }
  #attr(name, v) { if (v == null || v === '') { this.removeAttribute(name); } else { this.setAttribute(name, String(v)); } }

  connectedCallback() {
    this.#cleanup = false;
    this.#offToken = this.#offToken || onTokenNeeded((reason) => this.#emit('token-needed', { reason }));
    this.#theme();
    this.#label();
    this.#schedule();
  }
  disconnectedCallback() {
    this.#cleanup = true;
    queueMicrotask(() => { if (this.#cleanup) { this.#seq++; this.#ctrl && this.#ctrl.abort(); this.#offToken && this.#offToken(); this.#offToken = null; this.#setState('idle'); } });
  }
  attributeChangedCallback(name, was, now) {
    if (was === now) { return; }
    if (name === 'theme') { this.#theme(); }
    if (name === 'label') { this.#label(); }
    if (RELOAD.has(name) && this.isConnected) { this.#schedule(); }
  }
  #theme() { const t = this.theme; if (t === 'inherit') { this.removeAttribute('data-theme'); } else { this.setAttribute('data-theme', t); } }
  #label() { this.setAttribute('role', 'region'); this.setAttribute('aria-label', this.getAttribute('label') || 'About this page'); }
  #emit(name, detail) { this.dispatchEvent(new CustomEvent('drishti:' + name, { detail, bubbles: true, composed: true })); }
  #setState(s) {
    if (s === this.#state) { return; }
    const previous = this.#state;
    this.#state = s;
    this.setAttribute('data-state', s);
    this.#emit('state', { state: s, previous });
  }
  #adopt() {
    if (!this.#ready) {
      this.shadowRoot.adoptedStyleSheets = [frameSheet];
      this.#ready = sheet(this.server).then((s) => { this.shadowRoot.adoptedStyleSheets = [s, frameSheet]; font(this.server).catch(() => {}); }).catch((e) => { this.#ready = null; throw e; });
    }
    return this.#ready;
  }
  #schedule() { if (this.#queued) { return; } this.#queued = true; queueMicrotask(() => { this.#queued = false; this.reload(); }); }

  /** Asks the console for the About body of the view again. */
  async reload() {
    const seq = ++this.#seq;
    this.#ctrl && this.#ctrl.abort();
    const ctrl = this.#ctrl = new AbortController();
    if (!this.kind || !this.entity) { this.#setState('idle'); return; }
    this.#setState('loading');
    try {
      const url = this.server + API + '/views/' + encodeURIComponent(this.kind) + '/' + this.entity.split('/').map(encodeURIComponent).join('/') + '/about';
      const res = await authed(url, { headers: { Accept: 'text/html' } }, ctrl.signal);
      if (seq !== this.#seq) { return; }
      if (!res.ok) {
        const err = await problem(res);
        if (seq !== this.#seq) { return; }
        this.#fail(err);
        return;
      }
      const html = await res.text();
      if (seq !== this.#seq) { return; }
      await this.#adopt();
      let box = this.shadowRoot.querySelector('.about-body');
      if (!box) { box = Object.assign(document.createElement('div'), { className: 'about-body' }); this.shadowRoot.appendChild(box); }
      setHtml(box, html);
      for (const a of box.querySelectorAll('a[href^="/"]')) { a.setAttribute('href', this.server + a.getAttribute('href')); a.setAttribute('target', '_blank'); a.setAttribute('rel', 'noopener'); }
      this.#lastError = null;
      this.#setState('loaded');
      this.#emit('loaded', { kind: this.kind, entity: this.entity });
    } catch (e) {
      if (seq !== this.#seq || (e && e.name === 'AbortError')) { return; }
      this.#fail({ code: 'HTTP-0', status: 0, detail: (e && e.message) || 'the About body could not be loaded' });
    }
  }
  #fail(err) {
    this.#lastError = err;
    this.#setState('error');
    this.#emit('error', Object.assign({ fatal: true }, err));
    let box = this.shadowRoot.querySelector('.about-body');
    if (!box) { box = Object.assign(document.createElement('div'), { className: 'about-body' }); this.shadowRoot.appendChild(box); }
    setHtml(box, '<div class="dv-error" role="alert"><b>' + esc(err.code) + '</b> ' + esc(err.detail) + '</div>');
  }
}

if (!customElements.get('drishti-about')) { customElements.define('drishti-about', DrishtiAbout); }
