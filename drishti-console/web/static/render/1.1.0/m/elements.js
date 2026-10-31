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
/* Drishti Elements 2.1 (ELEMENTS.md section 19; CLIENT_RENDERER.md sections 10.1 and 13): <drishti-view> and <drishti-panel> drawn by the client-side
   renderer from the ViewModel of the embed API v2 (/embed/v2/views/{kind}/{id}), beside Elements 1.x (HTML over /embed/v1, unchanged) for
   one release. The element API is 1.x's: the same attributes, properties, methods, events, slots, page-wide token provider and one live
   connection per page; only the inside changed. <drishti-screen> is an alias of <drishti-view>. */
import { render } from '../drishti-render.js';
import { embedRecords } from './console.js';
import { signInFor } from './signin.js';

export const ELEMENTS_VERSION = '2.1.0';
export const API = '/embed/v2';
const MODULE_ORIGIN = new URL(import.meta.url).origin;
export const cfg = { server: MODULE_ORIGIN, tokenProvider: null, auth: null, retryMinMs: 2000, retryMaxMs: 30000, silenceMs: 30000, closeGraceMs: 1500 };

/** 2.1: auth 'popup' (configure({ auth: 'popup' }) or the auth="popup" attribute): the page's token comes from the console's sign-in window
    (CLIENT_RENDERER.md 10.1) instead of a host backend; every element and the live channel share it. */
export function usePopup(server) {
  const si = signInFor(server || cfg.server);
  if (!cfg.tokenProvider || cfg.tokenProvider.popup !== si) {
    const provider = () => si.get().then((t) => (t ? { token: t, expiresAt: si.exp } : null));
    provider.popup = si;
    cfg.tokenProvider = provider;
    si.on((what) => { if (what === 'signed-out') { invalidateToken(); } });
    providerChanged();
  }
  return si;
}

// ---- tokens: asked of the host's provider, kept until shortly before they expire, renewed once after a 401 ----------------------------
const tok = { token: null, exp: 0, life: 0, pending: null };
const needers = new Set();
const jwtExp = (t) => { try { return JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).exp * 1000; } catch (e) { return 0; } };
export const invalidateToken = () => { tok.token = null; tok.exp = 0; tok.life = 0; };
let providerSet;
const providerReady = new Promise((r) => { providerSet = r; });
/** The host set its provider (configure, or the property): a load waiting for one goes on. */
export const providerChanged = () => { invalidateToken(); providerSet(); };
export async function getToken(force) {
  // a host that sets its provider after the module has loaded (modules run in order, elements upgrade at once): wait briefly for it
  if (!cfg.tokenProvider) { await Promise.race([providerReady, new Promise((r) => setTimeout(r, 3000))]); }
  if (!cfg.tokenProvider) { return null; }
  if (!force && tok.token && Date.now() < tok.exp - Math.min(60000, (tok.life || 60000) * 0.4)) { return tok.token; }
  if (!tok.pending) {
    tok.pending = Promise.resolve().then(() => cfg.tokenProvider()).then((r) => {
      const t = typeof r === 'string' ? r : r && r.token;
      if (!t) { throw new Error('the token provider returned no token'); }
      tok.token = t; tok.exp = (r && r.expiresAt) || jwtExp(t) || Date.now() + 300000; tok.life = Math.max(0, tok.exp - Date.now());
      return t;
    }).catch((e) => { needers.forEach((fn) => fn('provider-failed')); throw e; }).finally(() => { tok.pending = null; });
  }
  return tok.pending;
}
/** A typed command ("TRD 1000008") to the entity it names: {ref, ...} or {ref: null, candidates} (the host's search box). */
export async function resolve(text, opts = {}) {
  const server = (opts.server || cfg.server).replace(/\/$/, '');
  const res = await authed(server + API + '/resolve?text=' + encodeURIComponent(text), {}, opts.signal);
  if (!res.ok) { let b = {}; try { b = await res.json(); } catch (e) { b = {}; } throw Object.assign(new Error('resolve failed'), { code: b.code || ('HTTP-' + res.status), status: res.status, detail: b.detail || res.statusText }); }
  return res.json();
}

async function authed(url, init, signal) {
  for (let attempt = 0; ; attempt++) {
    const t = await getToken(attempt > 0);
    const res = await fetch(url, Object.assign({}, init, { signal, cache: 'no-store', credentials: 'omit',
      headers: Object.assign({}, init && init.headers, t ? { Authorization: 'Bearer ' + t } : {}) }));
    if (res.status === 401) { if (attempt === 0 && cfg.tokenProvider) { continue; } needers.forEach((fn) => fn(cfg.tokenProvider ? 'refused' : 'missing')); }
    return res;
  }
}

// ---- one live connection per page and server: /embed/v2/channel, read with fetch (an EventSource cannot send the bearer) ----------------
class Connection {
  constructor(server) { this.server = server; this.keys = new Map(); this.synced = new Set(); this.cid = null; this.ctrl = null; this.retries = 0; this.timer = null; this.kicking = false; }
  subscribe(key, h) {
    const set = this.keys.get(key) || new Set(); set.add(h); this.keys.set(key, set); this.kick();
    return () => { set.delete(h); if (!set.size) { this.keys.delete(key); } this.kick(); };
  }
  kick() { if (this.kicking) { return; } this.kicking = true; setTimeout(() => { this.kicking = false; this.reconcile(); }, 0); }
  reconcile() {
    if (!this.keys.size) { if (this.ctrl) { setTimeout(() => { if (!this.keys.size) { this.close(); } }, cfg.closeGraceMs); } return; }
    if (!this.ctrl && !this.timer) { this.open(); return; }
    if (this.cid) { this.sync(); }
  }
  async sync() {
    const add = [...this.keys.keys()].filter((k) => !this.synced.has(k)), remove = [...this.synced].filter((k) => !this.keys.has(k));
    if (!add.length && !remove.length) { return; }
    add.forEach((k) => this.synced.add(k)); remove.forEach((k) => this.synced.delete(k));
    try {
      const r = await authed(this.server + API + '/channel/' + encodeURIComponent(this.cid), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ add, remove }) });
      if (!r.ok) { throw new Error('channel ' + r.status); }
    } catch (e) { this.loss(); }
  }
  close() { this.intentional = true; if (this.ctrl) { this.ctrl.abort(); } this.ctrl = null; this.cid = null; this.synced = new Set(); }
  loss() {
    if (this.ctrl) { this.intentional = true; this.ctrl.abort(); }
    this.ctrl = null; this.cid = null; this.synced = new Set();
    if (!this.keys.size) { return; }
    this.each((h) => h.state && h.state('reconnecting'));
    const ms = Math.min(cfg.retryMaxMs, cfg.retryMinMs * 2 ** this.retries++) * (0.8 + Math.random() * 0.4);
    this.timer = setTimeout(() => { this.timer = null; this.lost = true; this.reconcile(); }, ms);
  }
  each(fn) { for (const set of this.keys.values()) { for (const h of [...set]) { fn(h); } } }
  async open() {
    const ctrl = this.ctrl = new AbortController(), keys = [...this.keys.keys()];
    this.intentional = false;
    try {
      const res = await authed(this.server + API + '/channel?' + keys.map((k) => 's=' + encodeURIComponent(k)).join('&'), { headers: { Accept: 'text/event-stream' } }, ctrl.signal);
      if (!res.ok) { throw new Error('channel ' + res.status); }
      this.synced = new Set(keys);
      const reader = res.body.getReader(), dec = new TextDecoder();
      let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) { break; }
        buf += dec.decode(value, { stream: true });
        let at;
        while ((at = buf.indexOf('\n\n')) >= 0) { this.dispatch(buf.slice(0, at)); buf = buf.slice(at + 2); }
      }
    } catch (e) { /* a loss: below */ }
    if (this.ctrl === ctrl && !this.intentional) { this.loss(); }
  }
  dispatch(block) {
    let event = 'message', data = '';
    for (const line of block.split('\n')) { if (line.startsWith('event:')) { event = line.slice(6).trim(); } else if (line.startsWith('data:')) { data += line.slice(5).trim(); } }
    let m; try { m = JSON.parse(data); } catch (e) { return; }
    if (event === 'channel') { this.cid = m.d.id; this.retries = 0; if (this.lost) { this.lost = false; this.each((h) => h.reconnected && h.reconnected()); } this.sync(); return; }
    if (event === 'token') { invalidateToken(); return; }
    if (event === 'gone' && !m.ch) { this.loss(); return; }
    const set = this.keys.get(m.ch);
    if (set) { for (const h of [...set]) { if (h[event]) { h[event](m.d); } } }
  }
}
const connections = {};
const connectionFor = (server) => connections[server] || (connections[server] = new Connection(server));
export const connectionCount = () => Object.keys(connections).length;

// ---- <drishti-view> 2.0 ----------------------------------------------------------------------------------------------------------------
const enc = (s) => encodeURIComponent(String(s));
const idPath = (id) => String(id).split('/').map(enc).join('/');
const ATTRS = ['kind', 'entity', 'as-of', 'known-at', 'theme', 'density', 'header', 'live', 'server', 'panels', 'panel', 'label', 'controls', 'navigation', 'auth'];
const RELOAD = new Set(['kind', 'entity', 'as-of', 'known-at', 'header', 'live', 'server', 'panels', 'controls', 'navigation', 'auth']);

export class DrishtiView extends HTMLElement {
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
  get server() { return (this.getAttribute('server') || cfg.server).replace(/\/$/, ''); }
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
export class DrishtiPanel extends DrishtiView {
  connectedCallback() { if (!this.hasAttribute('header')) { this.setAttribute('header', 'none'); } this._sync(); super.connectedCallback(); }
  attributeChangedCallback(n, a, b) { if (n === 'panel') { this._sync(); } super.attributeChangedCallback(n, a, b); }
  _sync() { const id = (this.getAttribute('panel') || '').trim(); if (id) { if (this.getAttribute('panels') !== id) { this.setAttribute('panels', id); } } else if (this.hasAttribute('panels')) { this.removeAttribute('panels'); } }
  get panel() { return this.getAttribute('panel') || ''; }
  set panel(v) { this._attr('panel', v); }
}

/** Defines the tags (once per page; a page that loaded Elements 1.x keeps its own) and publishes window.DrishtiElements. */
export function define() {
  if (!customElements.get('drishti-view')) { customElements.define('drishti-view', DrishtiView); }
  if (!customElements.get('drishti-panel')) { customElements.define('drishti-panel', DrishtiPanel); }
  if (!customElements.get('drishti-screen')) { customElements.define('drishti-screen', class extends DrishtiView {}); }     // the preview's tag: an alias
}
