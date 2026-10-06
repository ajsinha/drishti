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
/* <drishti-view>: a live Drishti view inside another web application, in a Shadow DOM, no iframe (docs/architecture/ELEMENTS.md section 5).
   The state machine: every attribute or property change that matters aborts the request in flight and loads again, and only the
   latest load may paint (one sequence number and one AbortController per load). Subscriptions go over the page's one Connection;
   a hidden tab or a scrolled-away element gives its subscription back and takes it again; links are composed `drishti:navigate`
   events the host may take over; markup from the server is sanitised on the way in. */
import { API, cfg, esc, problem } from './config.js';
import { authed, onTokenNeeded, providerChanged } from './tokens.js';
import { sheet, frameSheet, font, chartLibs } from './assets.js';
import { connectionFor } from './connection.js';
import { setHtml, fragment } from './sanitize.js';
import { boot, dispose } from './enhancers.js';
import * as frames from './frames.js';

const ATTRS = ['kind', 'entity', 'as-of', 'known-at', 'theme', 'density', 'header', 'live', 'server', 'panels', 'panel', 'label'];
const RELOAD = new Set(['kind', 'entity', 'as-of', 'known-at', 'header', 'live', 'server', 'panels']);      // `panel` is reserved (<drishti-panel>), `theme`/`density`/`label` need no load
const instances = new Set();

export class DrishtiView extends HTMLElement {
  static get observedAttributes() { return ATTRS; }
  #seq = 0; #subs = 0; #ctrl = null; #unsub = null; #state = 'idle'; #gen = 0; #queued = false; #mods = null; #io = null;
  #hiddenTimer = null; #away = false; #paused = false; #retry = null; #retries = 0; #ready = null; #deleted = false; #view = null; #lastError = null;
  #cleanup = false; #offToken = null; #slots = {};

  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    for (const name of ['loading', 'empty', 'error']) {                  // host-provided content for the states without a view: <p slot="empty">…</p>
      const s = document.createElement('slot');
      s.name = name; s.className = 'dv-slot'; s.hidden = true;
      this.shadowRoot.appendChild(s);
      this.#slots[name] = s;
    }
    this.shadowRoot.addEventListener('click', (e) => this.#click(e));
    this.shadowRoot.addEventListener('keydown', (e) => { if (e.key === 'Enter') { this.#click(e); } });
  }
  get kind() { return this.getAttribute('kind') || ''; } set kind(v) { this.#attr('kind', v); }
  get entity() { return this.getAttribute('entity') || ''; } set entity(v) { this.#attr('entity', v); }
  get asOf() { return this.getAttribute('as-of') || 'live'; } set asOf(v) { this.#attr('as-of', v); }
  get knownAt() { return this.getAttribute('known-at') || ''; } set knownAt(v) { this.#attr('known-at', v); }
  get theme() { return this.getAttribute('theme') || 'light'; } set theme(v) { this.#attr('theme', v); }
  get density() { return this.getAttribute('density') || ''; } set density(v) { this.#attr('density', v); }
  get server() { return (this.getAttribute('server') || cfg.server).replace(/\/$/, ''); }
  get state() { return this.#state; }
  get view() { return this.#view; }
  get lastError() { return this.#lastError; }
  set tokenProvider(fn) {                                                // page-wide: one provider per page
    cfg.tokenProvider = fn; providerChanged();
    for (const el of instances) { if (el.state === 'error' && el.lastError && el.lastError.status === 401) { el.reload(); } }
  }
  get tokenProvider() { return cfg.tokenProvider; }
  #attr(name, v) { if (v == null || v === '') { this.removeAttribute(name); } else { this.setAttribute(name, String(v)); } }

  connectedCallback() {
    this.#cleanup = false;
    instances.add(this);
    this.#offToken = this.#offToken || onTokenNeeded((reason) => this.#emit('token-needed', { reason }));
    this.#adopt().catch(() => {});
    this.#observe();
    this.#theme();
    this.#slotsFor();
    this.#schedule();
  }
  disconnectedCallback() {                          // moved within the page (disconnect then connect in one task)? then keep everything
    this.#cleanup = true;
    queueMicrotask(() => { if (this.#cleanup) { this.#teardown(); } });
  }
  attributeChangedCallback(name, was, now) {
    if (was === now) { return; }
    if (name === 'theme') { this.#theme(); this.shadowRoot.dispatchEvent(new Event('drishti:theme')); }
    if (RELOAD.has(name) && this.isConnected) { this.#schedule(); }
  }
  #adopt() {                                         // the shared sheet (one per page) and the icon font; idempotent
    if (!this.#ready) {
      this.shadowRoot.adoptedStyleSheets = [frameSheet];
      this.#ready = sheet(this.server).then((s) => { this.shadowRoot.adoptedStyleSheets = [s, frameSheet]; font(this.server).catch(() => {}); })
        .catch((e) => { this.#ready = null; throw e; });
    }
    return this.#ready;
  }
  #theme() {
    const t = this.getAttribute('theme') || 'light';
    if (t === 'inherit') { this.removeAttribute('data-theme'); } else { this.setAttribute('data-theme', t); }
  }
  #observe() {
    if (!this.#io && typeof IntersectionObserver !== 'undefined') {
      this.#io = new IntersectionObserver((es) => { this.#away = !es[es.length - 1].isIntersecting; this.#visibility(); });
      this.#io.observe(this);
    }
    this.onVisible = this.onVisible || (() => this.#visibility());
    document.addEventListener('visibilitychange', this.onVisible);
  }
  #visibility() {
    clearTimeout(this.#hiddenTimer);
    const away = document.hidden || this.#away;
    if (away && !this.#paused && this.#view) { this.#hiddenTimer = setTimeout(() => this.pause(), cfg.hiddenGraceMs); }
    if (!away && this.#paused) { this.resume(); }
  }
  #teardown() {
    this.#seq++; this.#subs++; this.#ctrl && this.#ctrl.abort(); this.#ctrl = null;
    this.#unsub && this.#unsub(); this.#unsub = null;
    clearTimeout(this.#retry); clearTimeout(this.#hiddenTimer);
    this.#io && this.#io.disconnect(); this.#io = null;
    document.removeEventListener('visibilitychange', this.onVisible);
    this.#offToken && this.#offToken(); this.#offToken = null;
    instances.delete(this);
    this.#dispose();
    this.#setState('idle');
  }

  // -- states, events and slots -----------------------------------------------------------------------------------------
  #emit(name, detail, cancelable = false) {
    const e = new CustomEvent('drishti:' + name, { detail, bubbles: true, composed: true, cancelable });
    this.dispatchEvent(e);
    return e;
  }
  #setState(s) {
    if (s === this.#state) { this.#slotsFor(); return; }
    const previous = this.#state;
    this.#state = s;
    this.setAttribute('data-state', s);
    this.#slotsFor();
    this.#emit('state', { state: s, previous });
  }
  #hasSlot(name) { return this.#slots[name].assignedNodes().length > 0; }
  #slotsFor() {                                       // which of the host's slotted contents shows: only while there is no view to show
    const none = !this.#view, s = this.#state;
    this.#slots.loading.hidden = !(none && s === 'loading');
    this.#slots.empty.hidden = !(none && s === 'idle');
    this.#slots.error.hidden = !(none && s === 'error');
  }
  #fail(err, fatal) {
    this.#lastError = err;
    this.#setState('error');
    this.#emit('error', Object.assign({ fatal }, err));
    const root = this.shadowRoot;
    if (!this.#view) {
      if (this.#hasSlot('error')) { return; }         // the host's own error content
      let v = root.querySelector('.view');
      if (!v) { v = Object.assign(document.createElement('div'), { className: 'view' }); root.appendChild(v); }
      setHtml(v, '<div class="dv-error" role="alert"><b>' + esc(err.code) + '</b> ' + esc(err.detail) + '</div>');
    }
  }

  // -- the load sequence: one sequence number and one AbortController per load; only the latest may paint ---------------
  #schedule() {                                     // attribute changes in one task coalesce into one load
    if (this.#queued) { return; }
    this.#queued = true;
    queueMicrotask(() => { this.#queued = false; this.reload(); });
  }
  reload() { return this.#load({ keepSub: false }); }
  refresh() { return this.#load({ keepSub: true }); }
  async #load({ keepSub }) {
    const seq = ++this.#seq;
    this.#ctrl && this.#ctrl.abort();
    const ctrl = this.#ctrl = new AbortController();
    clearTimeout(this.#retry);
    if (!keepSub) { this.#unsub && this.#unsub(); this.#unsub = null; this.#deleted = false; this.#subs++; }   // a new subscription epoch: frames of the old one are dropped
    if (!this.kind || !this.entity) { this.#setState('idle'); return; }
    this.#setState(keepSub && this.#state === 'live' ? 'live' : 'loading');
    const started = performance.now();
    try {
      const params = new URLSearchParams();
      if (this.asOf !== 'live') { params.set('asOf', this.asOf); }
      if (this.knownAt) { params.set('knownAt', this.knownAt); }
      const url = this.server + API + '/views/' + encodeURIComponent(this.kind) + '/' + this.entity.split('/').map(encodeURIComponent).join('/') + (params.size ? '?' + params : '');
      const res = await authed(url, {}, ctrl.signal);
      if (seq !== this.#seq) { return; }
      if (!res.ok) {
        const err = await problem(res);
        if (seq !== this.#seq) { return; }
        const retryable = [502, 503, 504].includes(res.status);
        this.#fail(err, !retryable);
        if (retryable) { this.#backoff(); }
        return;
      }
      const data = await res.json();
      if (seq !== this.#seq) { return; }
      await this.#adopt();
      await chartLibs(this.server).catch(() => {});
      if (seq !== this.#seq) { return; }
      this.#retries = 0;
      this.#paint(data);
      const t = performance.now() - started;
      this.#gen = (data.provenance || {}).generation || 0;
      this.#view = { ref: data.ref, title: data.title, generation: this.#gen, provenance: data.provenance, panels: data.panels.map((p) => ({ id: p.id, kind: p.kind, title: p.title })), timings: { totalMs: Math.round(t) } };
      this.#lastError = null;
      const live = !!data.subscribe && this.getAttribute('live') !== 'off';
      this.#setState(live ? 'live' : 'static');
      this.#emit('loaded', { ref: data.ref, generation: this.#gen, title: data.title, live });
      if (data.masked && data.masked.count > 0) { this.#emit('masked', data.masked); }
      if (live && !keepSub && !this.#paused) { this.#subscribe(data.subscribe, this.#subs); }
    } catch (e) {
      if (e && e.name === 'AbortError') { return; }
      if (seq !== this.#seq) { return; }
      this.#fail({ code: 'DRS-5003', status: 0, detail: String(e && e.message || e) }, false);
      this.#backoff();
    }
  }
  #backoff() {
    const ms = Math.min(cfg.retryMaxMs, cfg.retryMinMs * 2 ** this.#retries++) * (0.8 + Math.random() * 0.4);
    this.#retry = setTimeout(() => this.#load({ keepSub: false }), ms);
    if (this.#view) { this.#setState('reconnecting'); }
  }
  #subscribe(key, epoch) {
    const mine = () => epoch === this.#subs;                // a refresh keeps the subscription; a reload, pause or teardown ends its epoch
    this.#unsub = connectionFor(this.server).subscribe(key, {
      view: (d) => { if (mine() && d && d.generation > this.#gen) { this.refresh(); } },
      frame: (f) => { if (mine()) { this.#frame(f); } },
      gone: (d) => { if (mine()) { this.#emit('error', { code: (d && d.code) || 'DRS-5003', status: 0, detail: d && d.detail, fatal: false }); if (!this.#deleted) { this.#setState('static'); } } },
      state: (s) => { if (mine() && !this.#deleted) { this.#setState(s); } },
      reconnected: () => { if (mine()) { this.refresh(); } }
    });
  }
  pause() { if (this.#paused || !this.#view) { return; } this.#paused = true; this.#subs++; this.#unsub && this.#unsub(); this.#unsub = null; this.#setState('paused'); }
  resume() { if (!this.#paused) { return; } this.#paused = false; this.#load({ keepSub: false }); }

  // -- painting ---------------------------------------------------------------------------------------------------------
  #paint(data) {
    const root = this.shadowRoot;
    const header = this.getAttribute('header') || 'full';
    const only = (this.getAttribute('panels') || '').split(',').map((s) => s.trim()).filter(Boolean);
    const panels = data.panels.filter((p) => !only.includes('none') && (!only.length || only.includes(p.id)));     // panels="none": the header alone
    let head = '';
    if (header !== 'none') {
      const t = document.createElement('template');
      setHtml(t, data.head.trim());
      if (header === 'strip') { const v = t.content.querySelector('.vtitle'); v && v.remove(); }
      head = t.innerHTML;
    }
    const main = panels.filter((p) => p.area !== 'right'), right = panels.filter((p) => p.area === 'right');
    this.#dispose();
    root.querySelectorAll('.view, .about.side').forEach((v) => v.remove());
    const view = document.createElement('div');
    view.className = 'view'; view.setAttribute('part', 'view'); view.setAttribute('data-view', ''); view.setAttribute('role', 'region');
    view.setAttribute('aria-label', this.getAttribute('label') || data.title.id);
    view.tabIndex = -1;                             // a click inside gives the focus to the element, so its keys (?) work and the host page's do not
    view.setAttribute('data-kind', data.ref.kind); view.setAttribute('data-id', data.ref.id); view.setAttribute('data-generation', String((data.provenance || {}).generation || 0));
    setHtml(view, head + '<div class="vgrid"><div class="vmain">' + main.map((p) => p.html).join('') + '</div><aside class="vright" aria-label="Context">'
      + right.map((p) => p.html).join('') + '</aside></div>');
    root.appendChild(view);
    this.#mods = boot(this, view, this.server, () => this.reload());
  }
  absolutise(root) {                                // console-relative links that are not entities open the console in a new tab
    root.querySelectorAll('a[href^="/"]').forEach((a) => {
      if (/^\/v\/[^/]+\//.test(a.getAttribute('href'))) { return; }
      a.href = this.server + a.getAttribute('href'); a.target = '_blank'; a.rel = 'noopener';
    });
  }
  #dispose() { const m = this.#mods; this.#mods = null; dispose(m, this.shadowRoot); }

  // -- live frames: the console's patches, applied inside the shadow root --------------------------------------------------
  #frame(f) {
    if (typeof f.generation === 'number' && f.generation <= this.#gen && !(f.patches || []).some((p) => p.op === 'deleted' || p.op === 'restored')) { return; }
    const root = this.shadowRoot, changed = { strip: [], panels: [] };
    for (const p of f.patches || []) {
      try {
        if (p.op === 'restored') { if (this.#deleted) { this.reload(); return; } continue; }
        if (p.op === 'deleted') { this.#deleted = true; this.#setState('deleted'); frames.banner(root, this.entity, p.at); continue; }
        if (this.#deleted) { continue; }
        if (p.op === 'strip') { if (frames.strip(root, p)) { changed.strip.push(p.index); } }
        else if (p.op === 'panel') { this.#swap(frames.panel(root, p, this.#mods, (n) => this.absolutise(n)), p); changed.panels.push(p.panel.id); }
        else if (p.op === 'provenance') { this.#gen = p.provenance.generation || this.#gen; }
      } catch (e) { if (window.console) { console.warn('drishti: patch not applied', p.op, e); } }
    }
    if (typeof f.generation === 'number') { this.#gen = Math.max(this.#gen, f.generation); }
    root.dispatchEvent(new CustomEvent('drishti:frame', { detail: { generation: this.#gen } }));      // the About drawer asks again when it is open
    this.#emit('tick', { generation: this.#gen, seq: f.seq, latencyMs: f.latencyMs, changed });
  }
  #swap(done, p) {                                  // a panel was replaced by a live frame: bars, tabs, charts, labels again (tables, tree rows and pivots see it through their observers)
    if (!done) { return; }
    const m = this.#mods;
    if (m) { m.view && m.view.enhance(done.fresh); m.charts && m.charts.draw(); m.view && m.view.redraw(); m.hints && m.hints.refresh(); }
    if (done.chosen) { const t = this.shadowRoot.getElementById(done.chosen); t && t.click(); }
    done.fresh.querySelectorAll('tbody td, dd').forEach((td) => td.classList.add('live-cell'));
  }

  // -- links: an entity link is an event the host may take over; the default is to show that entity here ---------------------
  #click(e) {
    const a = e.composedPath().find((n) => n instanceof HTMLAnchorElement);
    if (!a) { return; }
    const m = /^\/v\/([^/]+)\/(.+)$/.exec(a.getAttribute('href') || '');
    if (!m) { return; }
    e.preventDefault();
    const kind = decodeURIComponent(m[1]), id = decodeURIComponent(m[2].split('?')[0]);
    const ev = this.#emit('navigate', { kind, id, href: this.server + a.getAttribute('href'), panel: (a.closest('.pnl') || {}).id, source: a.closest('.xchart,.chart') ? 'chart' : 'link' }, true);
    if (!ev.defaultPrevented) { this.setAttribute('kind', kind); this.setAttribute('entity', id); }
  }
}

if (!customElements.get('drishti-view')) { customElements.define('drishti-view', DrishtiView); }
