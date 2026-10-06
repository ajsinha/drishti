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
/* <drishti-view>: a live Drishti view inside another web application, in a Shadow DOM, no iframe.
   PROOF OF CONCEPT (docs/architecture/ELEMENTS.md, build step 0): the load sequence with last-request-wins, one page
   connection (fetch + ReadableStream with Authorization), reconnect with backoff, pause when hidden, links as events.
   Not yet: About, Pivot, sorting/paging enhancers, Trusted Types, versioned asset URLs (steps 6 to 9). */
const MODULE_ORIGIN = new URL(import.meta.url).origin;
const API = '/embed/v1';

const cfg = { server: MODULE_ORIGIN, tokenProvider: null, hiddenGraceMs: 10000, retryMinMs: 2000, retryMaxMs: 30000,
  silenceMs: 30000, closeGraceMs: 1500 };

const sleepUntil = (ms, signal) => new Promise((resolve, reject) => {
  const t = setTimeout(resolve, ms);
  signal && signal.addEventListener('abort', () => { clearTimeout(t); reject(new DOMException('aborted', 'AbortError')); });
});
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const problem = async (res) => { let b = {}; try { b = await res.json(); } catch (e) { /* not JSON */ } return { code: b.code || ('HTTP-' + res.status), status: res.status, detail: b.detail || res.statusText }; };

// ---- tokens: asked of the host's provider, kept until 60 s before they expire ------------------------------------------
const tokenState = { token: null, exp: 0, pending: null };
function jwtExp(token) { try { return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).exp * 1000; } catch (e) { return 0; } }
let providerSet; const providerReady = new Promise((r) => { providerSet = r; });
async function getToken(force) {
  // a host that sets its provider after the module has loaded (modules run in order, elements upgrade at once): wait briefly for it
  if (!cfg.tokenProvider) { await Promise.race([providerReady, new Promise((r) => setTimeout(r, 3000))]); }
  if (!cfg.tokenProvider) { return null; }
  if (!force && tokenState.token && Date.now() < tokenState.exp - 60000) { return tokenState.token; }
  if (!tokenState.pending) {
    tokenState.pending = Promise.resolve(cfg.tokenProvider()).then((r) => {
      const token = typeof r === 'string' ? r : r.token;
      tokenState.token = token;
      tokenState.exp = (r && r.expiresAt) || jwtExp(token) || Date.now() + 300000;
      return token;
    }).finally(() => { tokenState.pending = null; });
  }
  return tokenState.pending;
}
async function authed(url, init, signal) {            // a fetch with the bearer; one new token on a 401
  for (let attempt = 0; ; attempt++) {
    const token = await getToken(attempt > 0);
    const res = await fetch(url, Object.assign({}, init, { signal, cache: 'no-store',
      headers: Object.assign({}, init && init.headers, token ? { Authorization: 'Bearer ' + token } : {}) }));
    if (res.status === 401 && attempt === 0 && cfg.tokenProvider) { continue; }
    return res;
  }
}

// ---- assets: the sheet (shared by every element), the icon font and the chart libraries (once per page) ---------------
const once = {};
const memo = (key, fn) => (once[key] || (once[key] = fn().catch((e) => { delete once[key]; throw e; })));
const sheet = (server) => memo('sheet:' + server, async () => {
  const text = await (await fetch(server + API + '/poc/drishti-view.css')).text();
  const s = new CSSStyleSheet();
  s.replaceSync(text);
  return s;
});
const frameSheet = new CSSStyleSheet();
frameSheet.replaceSync(':host{display:block;position:relative;contain:content}.view{min-height:0}'
  + ':host([data-state="loading"]) .view{opacity:.55;transition:opacity .15s}'
  + '.dv-note{padding:.4rem .8rem;font:12px/1.4 var(--d-font-mono,monospace);color:var(--d-muted,#666)}'
  + '.dv-error{padding:.8rem;border:1px solid var(--d-bad,#b00);color:var(--d-bad,#b00)}');
const font = (server) => memo('font:' + server, async () => {
  const face = new FontFace('bootstrap-icons', 'url(' + server + API + '/poc/icons.woff2) format("woff2")');
  document.fonts.add(await face.load());
});
const script = (url) => memo('script:' + url, () => new Promise((resolve, reject) => {
  const s = document.createElement('script');
  s.src = url; s.async = true; s.onload = resolve; s.onerror = () => reject(new Error('cannot load ' + url));
  document.head.appendChild(s);
}));
const chartLibs = (server) => memo('charts:' + server, async () => { await script(server + API + '/poc/echarts.js'); await script(server + API + '/poc/charts.js'); });

// ---- the one connection per page and server --------------------------------------------------------------------------
class Connection {
  constructor(server) {
    this.server = server; this.keys = new Map(); this.synced = new Set(); this.cid = null; this.epoch = 0; this.ctrl = null;
    this.retries = 0; this.timer = null; this.closeTimer = null; this.silence = null; this.busy = false; this.lost = false; this.kicking = false;
  }
  subscribe(key, handlers) {
    const set = this.keys.get(key) || new Set();
    set.add(handlers); this.keys.set(key, set);
    clearTimeout(this.closeTimer);
    this.kick();
    return () => { set.delete(handlers); if (!set.size) { this.keys.delete(key); } this.kick(); };
  }
  all(fn) { for (const set of this.keys.values()) { for (const h of [...set]) { fn(h); } } }
  kick() {                                         // one reconcile per task: elements that subscribe together share a request
    if (this.kicking) { return; }
    this.kicking = true;
    setTimeout(() => { this.kicking = false; this.reconcile(); }, 0);
  }
  reconcile() {
    if (!this.keys.size) {
      if (this.ctrl && !this.closeTimer) { this.closeTimer = setTimeout(() => { this.closeTimer = null; if (!this.keys.size) { this.close(); } }, cfg.closeGraceMs); }
      return;
    }
    if (!this.ctrl && !this.timer) { this.open(); return; }
    if (this.cid) { this.sync(); }
  }
  async sync() {
    const add = [...this.keys.keys()].filter((k) => !this.synced.has(k)), remove = [...this.synced].filter((k) => !this.keys.has(k));
    if (!add.length && !remove.length) { return; }
    add.forEach((k) => this.synced.add(k)); remove.forEach((k) => this.synced.delete(k));
    try {
      const res = await authed(this.server + API + '/channel/' + encodeURIComponent(this.cid), { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ add, remove }) });
      if (!res.ok) { throw new Error('channel ' + res.status); }
    } catch (e) { this.loss(); }                    // the channel is gone (or the console is): start afresh
  }
  close() { this.intentional = true; clearTimeout(this.timer); this.timer = null; clearTimeout(this.silence); if (this.ctrl) { this.ctrl.abort(); } this.ctrl = null; this.cid = null; this.synced = new Set(); }
  loss() {
    if (this.ctrl) { this.intentional = true; this.ctrl.abort(); }
    this.ctrl = null; this.cid = null; this.synced = new Set(); clearTimeout(this.silence);
    if (!this.keys.size) { return; }
    if (!this.lost) { this.lost = true; this.all((h) => h.state && h.state('reconnecting')); }
    const base = Math.min(cfg.retryMaxMs, cfg.retryMinMs * 2 ** this.retries++);
    this.timer = setTimeout(() => { this.timer = null; this.reconcile(); }, base * (0.8 + Math.random() * 0.4));
  }
  watch(ctrl) {                                    // no bytes for silenceMs (two missed heartbeats): the connection is dead
    clearTimeout(this.silence);
    this.silence = setTimeout(() => { if (this.ctrl === ctrl) { this.loss(); } }, cfg.silenceMs);
  }
  async open() {
    const ctrl = this.ctrl = new AbortController();
    this.intentional = false;
    const keys = [...this.keys.keys()];
    try {
      const res = await authed(this.server + API + '/channel?' + keys.map((k) => 's=' + encodeURIComponent(k)).join('&'),
        { headers: { Accept: 'text/event-stream' } }, ctrl.signal);
      if (!res.ok) { throw new Error('channel ' + res.status); }
      this.synced = new Set(keys);
      this.watch(ctrl);
      const reader = res.body.getReader(), decoder = new TextDecoder();
      let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) { break; }
        this.watch(ctrl);
        buf += decoder.decode(value, { stream: true });
        let at;
        while ((at = buf.indexOf('\n\n')) >= 0) { this.dispatch(buf.slice(0, at)); buf = buf.slice(at + 2); }
      }
    } catch (e) { /* a loss, handled below */ }
    if (this.ctrl === ctrl && !this.intentional) { this.loss(); }
  }
  dispatch(block) {
    let event = 'message', data = '';
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) { event = line.slice(6).trim(); } else if (line.startsWith('data:')) { data += line.slice(5).trim(); }
    }
    if (!data) { return; }
    let m; try { m = JSON.parse(data); } catch (e) { return; }
    if (event === 'channel') {
      this.cid = m.d.id; this.epoch++; this.retries = 0;
      if (this.lost) { this.lost = false; this.all((h) => h.reconnected && h.reconnected()); }
      this.sync();
      return;
    }
    if (event === 'end') { return; }
    const set = this.keys.get(m.ch);
    if (set) { for (const h of [...set]) { h[event] && h[event](m.d); } }
  }
}
const connections = {};
const connectionFor = (server) => connections[server] || (connections[server] = new Connection(server));

// ---- charts of the line and area panels (the console's view.js draws them the same way) ---------------------------------
const compact = (v) => {
  if (typeof v !== 'number' || !isFinite(v)) { return ''; }
  const a = Math.abs(v);
  if (a >= 1e9) { return (v / 1e9).toFixed(1) + 'bn'; }
  if (a >= 1e6) { return (v / 1e6).toFixed(a >= 1e7 ? 0 : 1) + 'm'; }
  if (a >= 1e4) { return (v / 1e3).toFixed(a >= 1e5 ? 0 : 1) + 'k'; }
  return Math.round(v * 100) / 100;
};
function lineOption(d, area, t) {
  d.x = d.x || []; d.series = d.series || [];
  const tone = (n) => t[n] || t.link;
  const series = d.series.map((s) => {
    const o = { type: 'line', name: s.label, data: (s.values || []).map((v) => (typeof v === 'number' && isFinite(v) ? v : null)), symbol: area ? 'none' : 'circle',
      symbolSize: 5, lineStyle: { width: 1.6, color: tone(s.tone) }, itemStyle: { color: tone(s.tone) } };
    if (area) { o.areaStyle = { color: tone(s.tone), opacity: .12 }; }
    return o;
  });
  const axis = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 }, splitLine: { lineStyle: { color: t.border, opacity: .5 } } };
  return { animationDuration: 300, grid: { left: 44, right: 12, top: 12, bottom: 22 },
    tooltip: { trigger: 'axis', backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } },
    xAxis: Object.assign({ type: 'category', data: d.x, boundaryGap: false }, axis),
    yAxis: Object.assign({ type: 'value', scale: !area }, axis, { axisLabel: Object.assign({}, axis.axisLabel, { formatter: compact }) }), series };
}

// ---- <drishti-view> ----------------------------------------------------------------------------------------------------
const ATTRS = ['kind', 'entity', 'as-of', 'known-at', 'theme', 'header', 'live', 'server', 'panels', 'label'];
const RELOAD = new Set(['kind', 'entity', 'as-of', 'known-at', 'header', 'live', 'server', 'panels']);

class DrishtiView extends HTMLElement {
  static get observedAttributes() { return ATTRS; }
  #seq = 0; #subs = 0; #ctrl = null; #unsub = null; #state = 'idle'; #gen = 0; #queued = false; #charts = new Set(); #ro = null; #io = null;
  #hiddenTimer = null; #away = false; #paused = false; #retry = null; #retries = 0; #ready = null; #deleted = false; #view = null; #lastError = null;
  #cleanup = false;

  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    this.shadowRoot.addEventListener('click', (e) => this.#click(e));
    this.shadowRoot.addEventListener('keydown', (e) => { if (e.key === 'Enter') { this.#click(e); } });
  }
  get kind() { return this.getAttribute('kind') || ''; } set kind(v) { this.#attr('kind', v); }
  get entity() { return this.getAttribute('entity') || ''; } set entity(v) { this.#attr('entity', v); }
  get asOf() { return this.getAttribute('as-of') || 'live'; } set asOf(v) { this.#attr('as-of', v); }
  get knownAt() { return this.getAttribute('known-at') || ''; } set knownAt(v) { this.#attr('known-at', v); }
  get server() { return (this.getAttribute('server') || cfg.server).replace(/\/$/, ''); }
  get state() { return this.#state; }
  get view() { return this.#view; }
  get lastError() { return this.#lastError; }
  set tokenProvider(fn) { cfg.tokenProvider = fn; providerSet(); }
  get tokenProvider() { return cfg.tokenProvider; }
  #attr(name, v) { if (v == null || v === '') { this.removeAttribute(name); } else { this.setAttribute(name, String(v)); } }

  connectedCallback() {
    this.#cleanup = false;
    this.#adopt().catch(() => {});
    this.#observe();
    this.#theme();
    this.#schedule();
  }
  disconnectedCallback() {                          // moved within the page (disconnect then connect in one task)? then keep everything
    this.#cleanup = true;
    queueMicrotask(() => { if (this.#cleanup) { this.#teardown(); } });
  }
  attributeChangedCallback(name, was, now) {
    if (was === now) { return; }
    if (name === 'theme') { this.#theme(); this.#redraw(); }
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
    if (!this.#ro && typeof ResizeObserver !== 'undefined') {
      this.#ro = new ResizeObserver(() => requestAnimationFrame(() => this.#charts.forEach((c) => c.isDisposed() || c.resize())));
      this.#ro.observe(this);
    }
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
    this.#ro && this.#ro.disconnect(); this.#ro = null; this.#io && this.#io.disconnect(); this.#io = null;
    document.removeEventListener('visibilitychange', this.onVisible);
    this.#charts.forEach((c) => c.isDisposed() || c.dispose()); this.#charts.clear();
    this.#setState('idle');
  }

  // -- states and events ----------------------------------------------------------------------------------------------
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
  #fail(err, fatal) {
    this.#lastError = err;
    this.#setState('error');
    this.#emit('error', Object.assign({ fatal }, err));
    const root = this.shadowRoot;
    if (!this.#view) {
      root.querySelector('.view') || root.appendChild(Object.assign(document.createElement('div'), { className: 'view' }));
      root.querySelector('.view').innerHTML = '<div class="dv-error" role="alert"><b>' + esc(err.code) + '</b> ' + esc(err.detail) + '</div>';
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
      t.innerHTML = data.head.trim();
      if (header === 'strip') { const v = t.content.querySelector('.vtitle'); v && v.remove(); }
      head = t.innerHTML;
    }
    const main = panels.filter((p) => p.area !== 'right'), right = panels.filter((p) => p.area === 'right');
    this.#charts.forEach((c) => c.isDisposed() || c.dispose()); this.#charts.clear();
    root.querySelectorAll('.view').forEach((v) => v.remove());
    const view = document.createElement('div');
    view.className = 'view'; view.setAttribute('part', 'view'); view.setAttribute('data-view', ''); view.setAttribute('role', 'region');
    view.setAttribute('aria-label', this.getAttribute('label') || data.title.id);
    view.innerHTML = head + '<div class="vgrid"><div class="vmain">' + main.map((p) => p.html).join('') + '</div><aside class="vright" aria-label="Context">'
      + right.map((p) => p.html).join('') + '</aside></div>';
    root.appendChild(view);
    this.#absolutise(view);
    this.#enhance(view);
  }
  #absolutise(root) {                               // console-relative links that are not entities open the console in a new tab
    root.querySelectorAll('a[href^="/"]').forEach((a) => {
      if (/^\/v\/[^/]+\//.test(a.getAttribute('href'))) { return; }
      a.href = this.server + a.getAttribute('href'); a.target = '_blank'; a.rel = 'noopener';
    });
  }
  #enhance(root) {
    root.querySelectorAll('[data-w]').forEach((el) => {
      const w = parseFloat(el.getAttribute('data-w')) || 0;
      requestAnimationFrame(() => { el.style.width = Math.max(0, Math.min(100, w)) + '%'; });
    });
    root.querySelectorAll('[data-tabs]').forEach((box) => {
      const heads = box.querySelectorAll('[role="tab"]');
      heads.forEach((h, i) => h.addEventListener('click', () => heads.forEach((x, k) => {
        x.classList.toggle('on', k === i); x.setAttribute('aria-selected', k === i ? 'true' : 'false');
        const pane = this.shadowRoot.getElementById(x.getAttribute('aria-controls'));
        if (pane) { pane.hidden = k !== i; }
      })));
    });
    this.#draw(root);
  }
  #tokens() {
    const s = getComputedStyle(this), t = {};
    ['ink', 'muted', 'border', 'link', 'accent', 'neg', 'pos', 'surface'].forEach((k) => { t[k] = s.getPropertyValue('--d-' + k).trim(); });
    t.mono = s.getPropertyValue('--d-font-mono').trim();
    return t;
  }
  #draw(root) {
    if (!window.echarts) { return; }
    const t = this.#tokens();
    root.querySelectorAll('.chart[data-chart]').forEach((el) => {
      try {
        const d = JSON.parse(el.getAttribute('data-chart')) || {};
        const c = window.echarts.getInstanceByDom(el) || window.echarts.init(el, null, { renderer: 'svg' });
        c.setOption(lineOption(d, el.getAttribute('data-kind') === 'area', t), true);
        this.#charts.add(c);
      } catch (e) { el.className = 'pnl-empty'; el.textContent = 'No data available'; }
    });
    if (window.drishtiCharts) { window.drishtiCharts.draw(this.shadowRoot); }
  }
  #redraw() { if (this.#view) { this.#draw(this.shadowRoot); } }

  // -- live frames: the console's patches, applied inside the shadow root --------------------------------------------------
  #frame(f) {
    if (typeof f.generation === 'number' && f.generation <= this.#gen && !(f.patches || []).some((p) => p.op === 'deleted' || p.op === 'restored')) { return; }
    const root = this.shadowRoot, changed = { strip: [], panels: [] };
    for (const p of f.patches || []) {
      try {
        if (p.op === 'restored') { if (this.#deleted) { this.reload(); return; } continue; }
        if (p.op === 'deleted') { this.#deleted = true; this.#setState('deleted'); this.#banner(p.at); continue; }
        if (this.#deleted) { continue; }
        if (p.op === 'strip') { this.#strip(p); changed.strip.push(p.index); }
        else if (p.op === 'panel') { this.#panel(p); changed.panels.push(p.panel.id); }
        else if (p.op === 'provenance') { this.#gen = p.provenance.generation || this.#gen; }
      } catch (e) { if (window.console) { console.warn('drishti: patch not applied', p.op, e); } }
    }
    if (typeof f.generation === 'number') { this.#gen = Math.max(this.#gen, f.generation); }
    this.#emit('tick', { generation: this.#gen, seq: f.seq, latencyMs: f.latencyMs, changed });
    if (root) { /* the frame is applied */ }
  }
  #strip(p) {
    const dd = this.shadowRoot.querySelectorAll('.strip-i dd')[p.index];
    if (!dd) { return; }
    const c = p.cell;
    dd.innerHTML = c.link ? '<a class="lnk" href="/v/' + encodeURIComponent(c.link.kind) + '/' + encodeURIComponent(c.link.id) + '">' + esc(c.text) + '</a>' : esc(c.text);
    dd.className = dd.className.replace(/\bt-\w+/g, '').trim() + (c.tone ? ' t-' + c.tone : '');
    dd.classList.remove('flash'); void dd.offsetWidth; dd.classList.add('flash');
  }
  #panel(p) {
    const old = this.shadowRoot.getElementById('p-' + p.panel.id);
    if (!old) { return; }
    if (!p.html) {
      const el = old.querySelector('.chart[data-chart]');
      if (el && window.echarts) {
        el.setAttribute('data-chart', JSON.stringify(p.panel.data));
        const c = window.echarts.getInstanceByDom(el);
        c && c.setOption(lineOption(JSON.parse(el.getAttribute('data-chart')), el.getAttribute('data-kind') === 'area', this.#tokens()), false);
      }
      return;
    }
    const chosen = old.querySelector('[role="tab"][aria-selected="true"]');
    const tpl = document.createElement('template');
    tpl.innerHTML = p.html.trim();
    const fresh = tpl.content.firstElementChild;
    const mine = /^(c-(span|h)-\d+|pnl-off)$/;
    fresh.className = fresh.className.split(/\s+/).filter((c) => !mine.test(c)).concat(old.className.split(/\s+/).filter((c) => mine.test(c))).join(' ');
    old.replaceWith(fresh);
    this.#absolutise(fresh);
    this.#enhance(fresh);
    if (chosen) { const t = this.shadowRoot.getElementById(chosen.id); t && t.click(); }
    fresh.querySelectorAll('tbody td, dd').forEach((td) => td.classList.add('live-cell'));
  }
  #banner(at) {
    const view = this.shadowRoot.querySelector('.view');
    if (!view || view.querySelector('[data-deleted-banner]')) { return; }
    const b = document.createElement('p');
    b.className = 'asof-banner deleted-banner'; b.setAttribute('role', 'alert'); b.setAttribute('data-deleted-banner', '');
    b.textContent = (this.entity) + ' was deleted' + (at ? ' at ' + at : '') + ' by its source. What you see is its last state.';
    view.insertBefore(b, view.firstChild);
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

// ---- the page-level API ---------------------------------------------------------------------------------------------------
async function resolve(text, opts = {}) {
  const server = (opts.server || cfg.server).replace(/\/$/, '');
  const res = await authed(server + API + '/resolve?text=' + encodeURIComponent(text), {}, opts.signal);
  if (!res.ok) { throw Object.assign(new Error('resolve failed'), await problem(res)); }
  return res.json();
}
window.DrishtiElements = Object.freeze({
  version: 'poc-0', configure: (o) => { Object.assign(cfg, o || {}); if (cfg.tokenProvider) { providerSet(); } }, resolve,
  invalidateToken: () => { tokenState.token = null; tokenState.exp = 0; },       // the host's user changed: the next call asks again
  connections: () => Object.keys(connections).length
});
export { DrishtiView, resolve };
