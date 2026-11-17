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
/* <drishti-search>: search across Drishti from the host's page (docs/guides/ELEMENTS.md). A box and a short list of what matches: entities the signed-in user may
   open, as the console's own type-ahead finds them (fields masked for the user are neither shown nor matched; an embedded call always masks). It needs the scope
   embed:search and is read-only. Picking a result fires the cancellable `drishti:select`; with `target="#view"` (a CSS selector of a <drishti-view> or <drishti-panel>)
   the element also points that view at the pick. Everything it draws is text (no markup from the server), in a Shadow DOM, with a constructable sheet (a host CSP
   with style-src 'self' is fine). Results are 44 px targets. */
import { API, cfg, problem } from './config.js';
import { authed, onTokenNeeded, providerChanged } from './tokens.js';

const ATTRS = ['server', 'theme', 'placeholder', 'limit', 'label', 'target', 'min-chars', 'delay'];

const style = new CSSStyleSheet();
style.replaceSync(':host{display:block;font:14px/1.4 system-ui,sans-serif;color:var(--d-fg,#1b2430)}'
  + ':host([data-theme="terminal"]){color:#d8e2ee}'
  + '.ds-box{display:flex;gap:.5rem;align-items:center}'
  + 'input{flex:1;min-height:44px;box-sizing:border-box;padding:.4rem .7rem;border:1px solid #8a96a6;border-radius:6px;font:inherit;background:#fff;color:#1b2430}'
  + 'input:focus-visible,button:focus-visible{outline:2px solid #2457c5;outline-offset:2px}'
  + 'ul{list-style:none;margin:.4rem 0 0;padding:0;border:1px solid #d5dae1;border-radius:6px;background:#fff;color:#1b2430}ul:empty{display:none}'
  + 'li+li{border-top:1px solid #eef1f5}'
  + 'button.ds-hit{display:block;width:100%;min-height:44px;text-align:left;padding:.35rem .7rem;border:0;background:transparent;font:inherit;color:inherit;cursor:pointer}'
  + 'button.ds-hit:hover,button.ds-hit:focus-visible{background:#eef3fb}'
  + '.ds-kind{display:inline-block;min-width:3rem;margin-right:.5rem;color:#4a5668;font-size:12px;text-transform:uppercase;letter-spacing:.03em}'
  + '.ds-sub{display:block;color:#4a5668;font-size:12px}'
  + '.ds-note{margin:.4rem 0 0;color:#4a5668;font-size:12px}.ds-note[data-bad]{color:#a11d1d}'
  + '@media (max-width:640px){input{font-size:16px}}');

export class DrishtiSearch extends HTMLElement {
  static get observedAttributes() { return ATTRS; }
  #input = null; #list = null; #note = null; #timer = null; #ctrl = null; #seq = 0; #state = 'idle'; #offToken = null; #results = []; #lastError = null;

  constructor() {
    super();
    const root = this.attachShadow({ mode: 'open' });
    root.adoptedStyleSheets = [style];
    this.#input = Object.assign(document.createElement('input'), { type: 'search', autocomplete: 'off', spellcheck: false });
    this.#input.setAttribute('role', 'combobox');
    this.#input.setAttribute('aria-expanded', 'false');
    this.#input.setAttribute('aria-autocomplete', 'list');
    this.#list = Object.assign(document.createElement('ul'), { id: 'ds-list' });
    this.#list.setAttribute('role', 'listbox');
    this.#input.setAttribute('aria-controls', 'ds-list');
    this.#note = Object.assign(document.createElement('p'), { className: 'ds-note' });
    this.#note.setAttribute('role', 'status');
    this.#note.setAttribute('aria-live', 'polite');
    const box = Object.assign(document.createElement('div'), { className: 'ds-box' });
    box.appendChild(this.#input);
    root.append(box, this.#list, this.#note);
    this.#input.addEventListener('input', () => this.#queue());
    this.#input.addEventListener('keydown', (e) => this.#keys(e));
    this.#list.addEventListener('keydown', (e) => this.#keys(e));
  }
  get server() { return (this.getAttribute('server') || cfg.server).replace(/\/$/, ''); }
  get theme() { return this.getAttribute('theme') || 'light'; } set theme(v) { this.#attr('theme', v); }
  get value() { return this.#input.value; } set value(v) { this.#input.value = v == null ? '' : String(v); this.#queue(); }
  get results() { return this.#results.slice(); }
  get state() { return this.#state; }
  get lastError() { return this.#lastError; }
  get limit() { return Math.max(1, Math.min(50, parseInt(this.getAttribute('limit') || '10', 10) || 10)); } set limit(v) { this.#attr('limit', v); }
  get minChars() { return Math.max(1, parseInt(this.getAttribute('min-chars') || '2', 10) || 2); }
  get delay() { return Math.max(0, parseInt(this.getAttribute('delay') || '250', 10) || 0); }
  set tokenProvider(fn) { cfg.tokenProvider = fn; providerChanged(); }
  get tokenProvider() { return cfg.tokenProvider; }
  #attr(name, v) { if (v == null || v === '') { this.removeAttribute(name); } else { this.setAttribute(name, String(v)); } }

  connectedCallback() {
    this.#offToken = this.#offToken || onTokenNeeded((reason) => this.#emit('token-needed', { reason }));
    this.#theme();
    this.#chrome();
  }
  disconnectedCallback() {
    clearTimeout(this.#timer);
    this.#ctrl && this.#ctrl.abort();
    this.#offToken && this.#offToken(); this.#offToken = null;
  }
  attributeChangedCallback(name) {
    if (name === 'theme') { this.#theme(); }
    if (name === 'placeholder' || name === 'label') { this.#chrome(); }
  }
  #theme() { const t = this.theme; if (t === 'inherit') { this.removeAttribute('data-theme'); } else { this.setAttribute('data-theme', t); } }
  #chrome() {
    this.#input.placeholder = this.getAttribute('placeholder') || 'Search';
    this.#input.setAttribute('aria-label', this.getAttribute('label') || this.getAttribute('placeholder') || 'Search');
  }
  #emit(name, detail, cancelable = false) {
    const e = new CustomEvent('drishti:' + name, { detail, bubbles: true, composed: true, cancelable });
    this.dispatchEvent(e);
    return e;
  }
  #setState(s) {
    if (s === this.#state) { return; }
    const previous = this.#state;
    this.#state = s;
    this.setAttribute('data-state', s);
    this.#emit('state', { state: s, previous });
  }
  #queue() {
    clearTimeout(this.#timer);
    const q = this.#input.value.trim();
    if (q.length < this.minChars) { this.clear(false); return; }
    this.#timer = setTimeout(() => this.search(q), this.delay);
  }

  /** Empties the box and the list. */
  clear(box = true) {
    this.#seq++; this.#ctrl && this.#ctrl.abort();
    if (box) { this.#input.value = ''; }
    this.#show([]);
    this.#note.textContent = '';
    this.#note.removeAttribute('data-bad');
    this.#setState('idle');
  }

  /** Searches for `q` now (the box shows it). Resolves with the results. */
  async search(q) {
    const text = String(q == null ? this.#input.value : q).trim();
    if (this.#input.value.trim() !== text) { this.#input.value = text; }
    const seq = ++this.#seq;
    this.#ctrl && this.#ctrl.abort();
    const ctrl = this.#ctrl = new AbortController();
    if (text.length < this.minChars) { this.#show([]); return []; }
    this.#setState('loading');
    try {
      const res = await authed(this.server + API + '/search?q=' + encodeURIComponent(text) + '&limit=' + this.limit, { headers: { Accept: 'application/json' } }, ctrl.signal);
      if (seq !== this.#seq) { return this.#results; }
      if (!res.ok) {
        const err = await problem(res);
        if (seq !== this.#seq) { return this.#results; }
        this.#lastError = err;
        this.#show([]);
        this.#note.textContent = err.code + ' ' + err.detail;
        this.#note.setAttribute('data-bad', '');
        this.#setState('error');
        this.#emit('error', Object.assign({ fatal: false }, err));
        return [];
      }
      const body = await res.json();
      if (seq !== this.#seq) { return this.#results; }
      this.#lastError = null;
      this.#note.removeAttribute('data-bad');
      this.#show(body.results || []);
      this.#note.textContent = (body.results || []).length ? (body.results.length + (body.results.length === 1 ? ' result' : ' results')) : (body.note || 'Nothing found');
      this.#setState('idle');
      this.#emit('results', { query: text, results: this.#results });
      return this.#results;
    } catch (e) {
      if (seq !== this.#seq || (e && e.name === 'AbortError')) { return this.#results; }
      this.#lastError = { code: 'HTTP-0', status: 0, detail: (e && e.message) || 'the search could not be reached' };
      this.#note.textContent = this.#lastError.detail;
      this.#note.setAttribute('data-bad', '');
      this.#setState('error');
      this.#emit('error', Object.assign({ fatal: false }, this.#lastError));
      return [];
    }
  }

  #show(results) {
    this.#results = results;
    this.#list.textContent = '';
    results.forEach((r, i) => {
      const li = document.createElement('li');
      li.setAttribute('role', 'presentation');
      const b = document.createElement('button');
      b.type = 'button'; b.className = 'ds-hit'; b.setAttribute('role', 'option'); b.id = 'ds-hit-' + i; b.setAttribute('data-kind', r.kind || ''); b.setAttribute('data-id', r.id || '');
      const kind = document.createElement('span'); kind.className = 'ds-kind'; kind.textContent = r.mnemonic || r.kind || '';
      const title = document.createElement('span'); title.textContent = r.title || r.id || '';
      b.append(kind, title);
      if (r.subtitle) { const sub = document.createElement('span'); sub.className = 'ds-sub'; sub.textContent = r.subtitle; b.appendChild(sub); }
      b.addEventListener('click', () => this.#pick(r));
      li.appendChild(b);
      this.#list.appendChild(li);
    });
    this.#input.setAttribute('aria-expanded', results.length ? 'true' : 'false');
  }
  #pick(r) {
    const e = this.#emit('select', { kind: r.kind, id: r.id, mnemonic: r.mnemonic, title: r.title, type: r.type }, true);
    if (e.defaultPrevented) { return; }
    const sel = this.getAttribute('target');
    const view = sel ? document.querySelector(sel) : null;
    if (view && 'kind' in view && 'entity' in view) { view.setAttribute('kind', r.kind); view.setAttribute('entity', r.id); }
  }
  #keys(e) {
    const hits = [...this.#list.querySelectorAll('button.ds-hit')];
    if (e.key === 'Escape') { this.clear(); this.#input.focus(); return; }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (!hits.length) { return; }
      e.preventDefault();
      const at = hits.indexOf(this.shadowRoot.activeElement);
      const next = e.key === 'ArrowDown' ? (at + 1) % hits.length : (at <= 0 ? hits.length - 1 : at - 1);
      hits[next].focus();
    } else if (e.key === 'Enter' && e.target === this.#input && hits.length) {
      e.preventDefault(); this.#pick(this.#results[0]);
    }
  }
}

if (!customElements.get('drishti-search')) { customElements.define('drishti-search', DrishtiSearch); }
