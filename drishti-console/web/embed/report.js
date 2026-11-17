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
/* <drishti-report>: a published BI report, or one page or one visual of it, inside another web application, in a Shadow DOM, no iframe (docs/architecture/ELEMENTS.md
   section 18). It signs in like <drishti-view> (the host's token provider, an embed token on every call, fields masked whatever the user's roles), draws with the
   console's report code (report-page.js), follows the report's live tables over the page's one embed connection, and tells the host what happens through
   `drishti:*` events: loaded, filter-changed, page-changed, state, tick, masked, error and token-needed. */
import { API, cfg, problem } from './config.js';
import { authed, onTokenNeeded, providerChanged } from './tokens.js';
import { sheet, reportSheet, frameSheet, font, reportLibs } from './assets.js';
import { connectionFor } from './connection.js';
import { ReportPage } from './report-page.js';

const ATTRS = ['report', 'page', 'visual', 'filters', 'chrome', 'theme', 'server', 'live', 'label'];
const RELOAD = new Set(['report', 'visual', 'chrome', 'server', 'live']);
const instances = new Set();
const catalogues = new Map();

export class DrishtiReport extends HTMLElement {
  static get observedAttributes() { return ATTRS; }
  #seq = 0; #ctrl = null; #state = 'idle'; #page = null; #queued = false; #ready = null; #unsub = null; #lastError = null; #cleanup = false; #offToken = null;
  #ro = null; #io = null; #timer = null; #hidden = null; #paused = false; #away = false; #initial = null; #mod = false; #info = null;

  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    const mods = (e) => { this.#mod = !!(e.ctrlKey || e.metaKey || e.shiftKey); };
    this.shadowRoot.addEventListener('pointerdown', mods, true);
    this.shadowRoot.addEventListener('click', mods, true);
    this.shadowRoot.addEventListener('keydown', mods, true);
  }
  get report() { return this.getAttribute('report') || ''; } set report(v) { this.#attr('report', v); }
  get page() { return this.#page ? this.#page.pageId : (this.getAttribute('page') || ''); } set page(v) { this.#attr('page', v); }
  get visual() { return this.getAttribute('visual') || ''; } set visual(v) { this.#attr('visual', v); }
  get theme() { return this.getAttribute('theme') || 'light'; } set theme(v) { this.#attr('theme', v); }
  get server() { return (this.getAttribute('server') || cfg.server).replace(/\/$/, ''); }
  /** The filters in force: `{page, slicers, dates, selections, carry, mode}`. Assign the same shape to set them (anything left out is cleared). */
  get filters() { return this.#page ? this.#page.filters() : this.#parseFilters(); }
  set filters(v) {
    this.#initial = v || {};
    if (this.#page) { this.#page.apply(this.#initial); this.#page.drawGrid(); this.#emit('filter-changed', Object.assign(this.#page.filters(), { source: 'api' })); }
  }
  get state() { return this.#state; }
  get lastError() { return this.#lastError; }
  get modifier() { return this.#mod; }
  get width() { return this.clientWidth || this.getBoundingClientRect().width; }
  set tokenProvider(fn) {
    cfg.tokenProvider = fn; providerChanged();
    for (const el of instances) { if (el.state === 'error' && el.lastError && el.lastError.status === 401) { el.reload(); } }
  }
  get tokenProvider() { return cfg.tokenProvider; }
  #attr(name, v) { if (v == null || v === '') { this.removeAttribute(name); } else { this.setAttribute(name, String(v)); } }
  #parseFilters() {
    const raw = this.getAttribute('filters');
    if (!raw) { return {}; }
    try { return JSON.parse(raw); } catch (e) { this.#emit('error', { code: 'DRS-5001', status: 0, detail: 'the filters attribute is not JSON: ' + e.message, fatal: false }); return {}; }
  }

  connectedCallback() {
    this.#cleanup = false;
    instances.add(this);
    this.#offToken = this.#offToken || onTokenNeeded((reason) => this.#emit('token-needed', { reason }));
    this.#theme();
    if (typeof ResizeObserver !== 'undefined') { this.#ro = this.#ro || new ResizeObserver(() => this.#page && this.#page.resized()); this.#ro.observe(this); }
    if (typeof IntersectionObserver !== 'undefined') { this.#io = this.#io || new IntersectionObserver((es) => { this.#away = !es[es.length - 1].isIntersecting; this.#visibility(); }); this.#io.observe(this); }
    document.addEventListener('visibilitychange', this.#onVisible);
    this.#schedule();
  }
  disconnectedCallback() {
    this.#cleanup = true;
    queueMicrotask(() => { if (this.#cleanup) { this.#teardown(); } });
  }
  attributeChangedCallback(name, was, now) {
    if (was === now) { return; }
    if (name === 'theme') { this.#theme(); this.#restyle(); } else if (name === 'page') { this.selectPage(now || ''); } else if (name === 'filters') { this.filters = this.#parseFilters(); }
    if (RELOAD.has(name) && this.isConnected) { this.#schedule(); }
  }
  #onVisible = () => this.#visibility();
  #theme() { const t = this.theme; if (t === 'inherit') { this.removeAttribute('data-theme'); } else { this.setAttribute('data-theme', t); } }
  #restyle() { if (this.#page) { this.#page.restyle(); this.#page.drawGrid(); } }
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
  #teardown() {
    this.#seq++; this.#ctrl && this.#ctrl.abort(); this.#ctrl = null;
    this.#stopLive();
    clearTimeout(this.#hidden);
    this.#ro && this.#ro.disconnect(); this.#ro = null;
    this.#io && this.#io.disconnect(); this.#io = null;
    document.removeEventListener('visibilitychange', this.#onVisible);
    this.#offToken && this.#offToken(); this.#offToken = null;
    instances.delete(this);
    this.#page && this.#page.dispose(); this.#page = null;
    this.#setState('idle');
  }

  // -- the load ---------------------------------------------------------------------------------------------------------------
  #schedule() { if (this.#queued) { return; } this.#queued = true; queueMicrotask(() => { this.#queued = false; this.reload(); }); }
  #adopt() {
    if (!this.#ready) {
      this.shadowRoot.adoptedStyleSheets = [frameSheet];
      this.#ready = Promise.all([sheet(this.server), reportSheet(this.server)]).then(([s, r]) => { this.shadowRoot.adoptedStyleSheets = [s, r, frameSheet]; font(this.server).catch(() => {}); })
        .catch((e) => { this.#ready = null; throw e; });
    }
    return this.#ready;
  }
  async #call(method, path, body, signal) {
    const res = await authed(this.server + API + path, { method, headers: Object.assign({ Accept: 'application/json' }, body ? { 'Content-Type': 'application/json' } : {}), body: body ? JSON.stringify(body) : undefined }, signal);
    if (!res.ok) { const p = await problem(res); throw Object.assign(new Error(p.code + ' ' + p.detail), p); }
    return res.json();
  }
  reload() {
    const seq = ++this.#seq;
    this.#ctrl && this.#ctrl.abort();
    const ctrl = this.#ctrl = new AbortController();
    this.#stopLive();
    return this.#load(seq, ctrl);
  }
  async #load(seq, ctrl) {
    if (!this.report) { this.#setState('idle'); return; }
    this.#setState('loading');
    this.removeAttribute('data-ready');
    try {
      await Promise.all([this.#adopt(), reportLibs(this.server)]);
      const id = encodeURIComponent(this.report).replace(/%3A/g, ':');
      if (!catalogues.has(this.server)) { catalogues.set(this.server, this.#call('GET', '/reports/catalogue', null).catch((e) => { catalogues.delete(this.server); throw e; })); }
      const [rep, cat] = await Promise.all([this.#call('GET', '/reports/' + id, null, ctrl.signal), catalogues.get(this.server)]);
      if (seq !== this.#seq) { return; }
      if (!rep.report || !rep.dataset) {
        const why = (rep.problems || [])[0];
        throw Object.assign(new Error('report'), { code: why ? why.code : 'DRS-9031', status: 422, detail: why ? why.path + ': ' + why.message : 'the report cannot be drawn' });
      }
      rep.id = this.report;
      this.#page && this.#page.dispose();
      const page = this.#page = new ReportPage({ root: this.shadowRoot, host: this, rep, cat, label: this.getAttribute('label') || '',
        api: (m, p, b) => this.#call(m, p, b, ctrl.signal), emit: (n, d) => this.#emit(n, d), options: { page: this.getAttribute('page') || '', visual: this.visual, chrome: this.getAttribute('chrome') || 'full' } });
      page.apply(this.#initial || this.#parseFilters());
      this.shadowRoot.querySelectorAll('.bi-embed, .dv-error').forEach((n) => n.remove());
      this.shadowRoot.appendChild(page.build());
      page.syncBar();
      const done = page.start();
      this.#lastError = null;
      await done;
      if (seq !== this.#seq) { return; }
      const live = (rep.dataset.tables || []).some((t) => t.mode === 'live') && this.getAttribute('live') !== 'off';
      this.#setState(live ? 'live' : 'static');
      if (!live) { const bar = page.q('[data-live-bar]'); if (bar) { bar.remove(); } }      // a report with no live table has no live bar (its hidden attribute loses to the bar's display rule)
      this.setAttribute('data-ready', 'yes');
      this.#emit('loaded', { report: this.report, title: rep.title, page: page.pageId, visuals: [...page.frames.keys()], live });
      if (page.masked.size) { this.#emit('masked', { columns: [...page.masked] }); }
      if (live && !this.#paused) { this.#startLive(page); }
    } catch (e) {
      if (e && e.name === 'AbortError') { return; }
      if (seq !== this.#seq) { return; }
      this.#fail({ code: e.code || 'DRS-5003', status: e.status || 0, detail: e.detail || String(e.message || e) });
    }
  }
  #fail(err) {
    this.#lastError = err;
    this.#setState('error');
    this.#emit('error', Object.assign({ fatal: true }, err));
    if (!this.#page) {
      const root = this.shadowRoot;
      root.querySelectorAll('.dv-error').forEach((n) => n.remove());
      const box = document.createElement('div');
      box.className = 'dv-error'; box.setAttribute('role', 'alert');
      const b = document.createElement('b'); b.textContent = err.code;
      box.append(b, ' ' + err.detail);
      root.appendChild(box);
    }
  }

  // -- live ------------------------------------------------------------------------------------------------------------------
  #stopLive() { this.#unsub && this.#unsub(); this.#unsub = null; clearInterval(this.#timer); this.#timer = null; this.#info = null; }
  #startLive(page) {
    const L = window.drishtiBi.live, bar = page.q('[data-live-bar]');
    const info = this.#info = { state: 'connecting', reason: '', lastUpdate: null, generation: 0 };
    let paused = false, pending = false, heard = 0, off = false;
    const watch = { info: () => info, paused: () => paused, pause() { paused = true; if (info.state === 'live') { set('paused'); } },
      resume() { paused = false; if (info.state === 'paused') { set('live'); } if (pending) { pending = false; refresher.request(); } } };
    const ind = bar ? L.indicator(bar, () => watch) : { draw() {} };
    const set = (state, reason) => {
      if (info.state === state && info.reason === (reason || '')) { return; }
      info.state = state; info.reason = reason || ''; ind.draw();
      this.#setState(state === 'live' || state === 'paused' ? 'live' : state === 'disconnected' ? 'reconnecting' : state);
    };
    const refresher = L.coalesce(() => page.refreshLive().then(() => this.#emit('tick', { generation: info.generation, at: new Date().toISOString() })), cfg.liveMinMs || 250);
    const fromServer = (d) => {
      heard = Date.now();
      if (d.lastUpdate) { info.lastUpdate = new Date(d.lastUpdate); }
      info.generation = Math.max(info.generation, Number(d.generation) || 0);
      if (d.state === 'failed') { set('failed', ((d.tables || []).find((x) => x.error) || {}).error || 'the live feed is failing'); } else if (d.state === 'stale') { set('stale', 'the source has pushed nothing for a while'); } else if (d.state === 'closed') { set('disconnected', 'the table was closed'); } else { set(paused ? 'paused' : 'live'); }
      ind.draw();
    };
    this.#unsub = connectionFor(this.server).subscribe('bilive:' + this.report, {
      hello: (d) => { fromServer(d); if (off || Number(d.generation) > page.seen) { refresher.request(); } off = false; },
      tick: (d) => { fromServer(d); if (paused) { pending = true; } else { refresher.request(); } },
      status: (d) => fromServer(d),
      gone: (d) => { off = true; set('disconnected', (d && d.detail) || 'the stream ended'); },
      state: (s) => { if (s === 'reconnecting') { off = true; set('disconnected', 'the connection dropped; trying again'); } },
      reconnected: () => { off = true; }
    });
    this.#timer = setInterval(() => { if (heard && Date.now() - heard > (cfg.liveStaleMs || 30000) && info.state === 'live') { set('stale', 'nothing heard from the server for ' + Math.round((Date.now() - heard) / 1000) + ' s'); } }, 1000);
  }
  #visibility() {
    clearTimeout(this.#hidden);
    const away = document.hidden || this.#away;
    if (away && !this.#paused && this.#page) { this.#hidden = setTimeout(() => this.pause(), cfg.hiddenGraceMs); }
    if (!away && this.#paused) { this.resume(); }
  }
  pause() { if (this.#paused || !this.#page) { return; } this.#paused = true; this.#stopLive(); this.#setState('paused'); }
  resume() { if (!this.#paused) { return; } this.#paused = false; this.reload(); }

  // -- for the host --------------------------------------------------------------------------------------------------------------
  /** Shows another page of the report (its filters are cleared, as when a reader clicks the tab). */
  selectPage(id) {
    if (!this.#page || !id || id === this.#page.pageId) { return false; }
    if (!this.#page.page(id)) { this.#emit('error', { code: 'DRS-9031', status: 0, detail: `the report has no page '${id}'`, fatal: false }); return false; }
    this.#page.go(id, [], 'api');
    return true;
  }
  /** Clears slicers, selections and the date (the Reset button). */
  clearFilters() { this.#page && this.#page.reset(); }
  /** Asks every visual again, quietly. */
  refresh() { return this.#page ? this.#page.refreshLive() : this.reload(); }
}

if (!customElements.get('drishti-report')) { customElements.define('drishti-report', DrishtiReport); }
