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
/* One screen in one container (CLIENT_RENDERER.md sections 4, 10, 11 and 12), drawn by Drishti Elements' painter (drishti-views paint()):
   the host part around it is Drishti's own. It fetches the ViewModel as the person (the console's session, the host's token or the sign-in
   window), paints it, applies live frames (the painter's patches) and the view's stream, navigates in place with its own back stack, and
   tells the host what happens through drishti:* events on the container. The painter draws every panel, the header, the strip, the
   freshness line and the keys; the host bar above it holds Back and About this page. */
import { paint as paintView } from '../../../vendor/drishti-elements/3.2.0/elements/drishti-views/src/drishti_index.js';
import * as ChartLib from '../../../vendor/drishti-elements/3.2.0/elements/drishti-chart/src/drishti_index.js';
import * as GraphLib from '../../../vendor/drishti-elements/3.2.0/elements/drishti-graph/src/drishti_index.js';
import * as EventsLib from '../../../vendor/drishti-elements/3.2.0/elements/drishti-events/src/drishti_index.js';
import * as DataLib from '../../../vendor/drishti-elements/3.2.0/elements/drishti-data/src/drishti_index.js';
import * as InputsLib from '../../../vendor/drishti-elements/3.2.0/elements/drishti-inputs/src/drishti_index.js';
import * as GeoPart from '../../../vendor/drishti-elements/3.2.0/elements/drishti-chart/src/drishti_geohelper.js';
import { url as drishtiUrl, basePath } from '../../../vendor/drishti-elements/3.2.0/elements/drishti-core/src/drishti_index.js';
import { h, icon, str } from './drishti_dom.js';
import * as assets from './drishti_assets.js';
import { problem, fetchView, openStream, personFetch } from './drishti_net.js';
import { aboutSheet } from './drishti_about.js';
import { signInFor, gate } from './drishti_signin.js';

const NAV = ['in-place', 'host', 'new-window', 'follow'];
const BEHAVIOURS = ['library', 'host', 'console'];
const AUTH = ['token', 'popup', 'session'];
const HEADERS = ['full', 'strip', 'none'];
const EMBED = '/embed/v2';
const enc = (x) => encodeURIComponent(String(x));
const idPath = (id) => String(id).split('/').map(enc).join('/');

/** The console's address of an entity (the same as the console's own links), under the screen's server. */
function entityHref(o, link) { return (o.server || basePath()) + '/v/' + enc(link.kind) + '/' + idPath(link.id); }

export class Screen {
  constructor(container, options) {
    if (!container || container.nodeType !== 1) { throw problem('DRS-9101', 'render(container, …): the container must be an element', 'Pass a DOM element, e.g. document.getElementById("risk").'); }
    const o = Object.assign({ shadow: true, navigation: 'in-place', designOn: false, keys: false, header: 'full', live: true, theme: 'inherit', server: '',
      behaviours: 'library', about: false }, options || {});
    if (!NAV.includes(o.navigation)) { throw problem('DRS-9102', 'navigation must be one of ' + NAV.join(', ') + ', not ' + o.navigation, 'Leave it out for in-place, the default.'); }
    if (!BEHAVIOURS.includes(o.behaviours)) { throw problem('DRS-9106', 'behaviours must be one of ' + BEHAVIOURS.join(', ') + ', not ' + o.behaviours, "Leave it out for the library's own, the default."); }
    if (!HEADERS.includes(o.header)) { o.header = 'full'; }
    o.server = String(o.server || '').replace(/\/$/, '');
    // how the person gets in (CLIENT_RENDERER.md 10.1). 'token': the host's backend vouches (the token exchange); 'session': the console's
    // own sign-in (its pages, same-origin hosts); 'popup': a host without a backend, through the console's single sign-on in a window on a click
    this.auth = o.auth || (typeof o.token === 'function' ? 'token' : 'session');
    if (!AUTH.includes(this.auth)) { throw problem('DRS-9108', 'auth must be one of ' + AUTH.join(', ') + ', not ' + this.auth, "Leave it out: 'token' when you give a token, else the console's session."); }
    if (this.auth === 'popup') {
      o.server = o.server || assets.statics('').replace(/static\/$/, '').replace(/\/$/, '');   // the console's root, its URL sub-path kept
      this.signin = signInFor(o.server);
      if (typeof o.token !== 'function') { o.token = () => this.signin.get(); }
      o.credentials = 'omit';
      const views = (r, extra) => o.server + EMBED + '/views/' + enc(r.kind) + '/' + idPath(r.id) + (extra || '');
      if (typeof o.viewUrl !== 'function') {
        o.viewUrl = (r) => {
          const q = new URLSearchParams();
          for (const [k, v] of Object.entries(r.controls || {})) { q.set(k.startsWith('ctl.') ? k : 'ctl.' + k, v); }
          return views(r, q.toString() ? '?' + q : '');
        };
      }
      if (typeof o.aboutUrl !== 'function') { o.aboutUrl = (r, g) => views(r, '/about' + (g ? '?generation=' + g : '')); }
      if (!options || options.live === undefined) { o.live = false; }     // live frames for a signed-in page: Elements 2.x holds the page's one channel
      this.offSign = this.signin.on((what, d) => this.signed(what, d));
    }
    this.container = container; this.o = o; this.vm = null; this.view = null; this.stack = []; this.unsub = null; this.disposers = [];
    this.root = o.shadow ? (container.shadowRoot || container.attachShadow({ mode: 'open' })) : container;
    if (!container.hasAttribute('role')) { container.setAttribute('role', 'region'); }
    this.theme(o.theme);
    this.R = { server: o.server, designOn: !!o.designOn };          // what About this page reads
    this.onClick = (e) => this.click(e);
    this.onKey = (e) => { if (e.altKey && e.key === 'ArrowLeft' && this.stack.length) { e.preventDefault(); this.back(); } };
    this.root.addEventListener('click', this.onClick);
    this.root.addEventListener('keydown', this.onKey);
    this.ready = o.shadow ? Promise.all([assets.sheet().then((s) => { this.sheet = s; this.root.adoptedStyleSheets = [s]; }), assets.font().catch(() => {})]) : Promise.resolve();
  }

  /** The theme on the container (a name of Drishti Elements' tokens), or 'inherit' (none of its own: the host page's tokens). */
  theme(t) {
    this.o.theme = t || 'inherit';
    if (this.o.theme === 'inherit') { this.container.removeAttribute('data-theme'); } else { this.container.setAttribute('data-theme', this.o.theme); }
    if (this.view && this.vm) { this.view.update(this.vm); }          // the charts read the theme's colours when they draw
  }

  /** A drishti:<name> event on the container (bubbling, composed, cancelable); true when the host did not cancel it. */
  emit(name, detail) {
    const e = new CustomEvent('drishti:' + name, { detail, bubbles: true, composed: true, cancelable: true });
    return this.container.dispatchEvent(e);
  }

  // ---- painting --------------------------------------------------------------------------------------------------------------
  /** The painter's options: the view's links as the console's addresses, opened by this screen; the libraries from this module graph; in a
      shadow root the host's one sheet (the painter links none); ECharts loaded through this console's Trusted Types policy; behaviours
      'console' gives every table the reader's tools (column funnels, Chart any table: the painter's). */
  paintOptions() {
    const o = this.o;
    return {
      sheets: o.shadow ? (this.sheet ? [this.sheet] : []) : undefined,
      engine: () => assets.echarts(),
      tableTools: o.behaviours === 'console' ? { filters: true, chart: true } : undefined,
      shadow: false, header: o.header, validate: 'warn', complete: true, keys: !!o.keys, label: o.label || undefined, locale: o.locale, density: o.density,
      panels: Array.isArray(o.panels) ? o.panels : undefined,
      links: { href: (link) => (link && link.kind && link.id ? drishtiUrl(entityHref(o, link)) : null) },
      fetch: personFetch(o),
      libraries: { 'drishti-chart': ChartLib, 'drishti-graph': GraphLib, 'drishti-events': EventsLib, 'drishti-data': DataLib, 'drishti-inputs': InputsLib, 'drishti-chart:geo': GeoPart },
      gl: () => assets.echartsGl()
    };
  }

  async paint(vm) {
    if (!vm || typeof vm !== 'object' || !Array.isArray(vm.panels)) { return this.fail(problem('DRS-9103', 'not a ViewModel: it has no panels list', 'Pass the JSON of GET /api/v1/views/{kind}/{id}, or {kind, id} to fetch it.')); }
    await this.ready.catch(() => {});
    this.vm = JSON.parse(JSON.stringify(vm));
    this.container.setAttribute('aria-label', this.o.label || str((vm.title || {}).id || (vm.ref || {}).id));
    if (!this.view) { this.mount(this.vm); await this.view.ready; } else { this.bar(); await this.view.update(this.vm); }
    this.emit('paint', { ref: this.vm.ref, generation: (this.vm.provenance || {}).generation || 0 });
    return this;
  }

  /** The host bar (Back, About) and the painter's element, drawn once; the painter's events become the screen's. */
  mount(vm) {
    this.wipe();
    this.barEl = h('div', { class: 'r-bar', 'data-r-bar': true });
    const el = h('div', { class: 'r-view', 'data-r-view': true });
    this.root.append(this.barEl, el);
    this.view = paintView(el, vm, this.paintOptions());
    this.view.on('link', (e) => { e.preventDefault(); this.open(e.detail.link, { source: 'link' }); });
    this.view.on('key', (e) => { if (e.detail.action !== 'panel' && e.detail.action !== 'link') { this.emit('key', { key: e.detail.key, action: e.detail.action }); } });
    this.view.on('select', (e) => this.emit('select', e.detail));
    this.view.on('pick', (e) => this.emit('row-click', { panel: e.detail.panel, pick: e.detail.pick }));
    if (this.o.about) { this.aboutBtn = h('button', { type: 'button', class: 'fk r-about-btn', 'data-r-about': true, 'aria-expanded': 'false', 'aria-keyshortcuts': '?' }, icon('info-circle'), ' About'); this.disposers.push(aboutSheet(this, this.aboutBtn, this.root)); }
    this.bar();
  }

  /** The Back step (when this screen opened another in place) and About. */
  bar() {
    while (this.barEl.firstChild) { this.barEl.removeChild(this.barEl.firstChild); }
    if (this.stack.length) {
      this.barEl.appendChild(h('nav', { class: 'r-back', 'aria-label': 'Back' }, h('button', { type: 'button', class: 'fk', 'data-r-back': true, 'aria-keyshortcuts': 'Alt+ArrowLeft' },
        icon('arrow-left'), ' Back to ' + str(this.stack[this.stack.length - 1].label))));
    }
    if (this.aboutBtn) { this.barEl.appendChild(this.aboutBtn); }
    this.barEl.hidden = !this.barEl.firstChild;
  }

  /** Empties the root of what this screen drew; a host's own nodes marked data-r-keep (an element's slots) stay. */
  wipe() {
    if (this.view) { this.view.dispose(); this.view = null; }
    this.disposers.splice(0).forEach((f) => { try { f(); } catch (e) { /* gone */ } });
    this.aboutBtn = null;
    [...this.root.childNodes].forEach((n) => { if (!(n.nodeType === 1 && n.hasAttribute('data-r-keep'))) { n.remove(); } });
  }

  /** A failure in words with its DRS code and the fix; emitted as drishti:error. */
  fail(err) {
    this.emit('error', err);
    this.wipe();
    this.root.appendChild(h('div', { class: 'r-problem' }, h('div', { class: 'dv-error asof-banner', role: 'alert', 'data-code': err.code },
      h('b', null, err.code), ' ' + err.detail, err.fix ? [' ', h('span', { class: 'r-fix' }, err.fix)] : null)));
    return this;
  }

  // ---- loading by reference, and in-place navigation ---------------------------------------------------------------------
  async load(ref, push) {
    const label = this.vm ? str((this.vm.title || {}).id || (this.vm.ref || {}).id) : '';
    const prev = this.vm;
    this.container.setAttribute('aria-busy', 'true');
    try {
      const vm = await fetchView(this.o, ref);
      if (push && prev) { this.stack.push({ vm: prev, label }); }
      await this.paint(vm);
      this.emit('loaded', { ref: vm.ref, generation: (vm.provenance || {}).generation || 0, depth: this.stack.length });
      if (this.o.live !== false && (vm.provenance || {}).live) { this.subscribe(); }
    } catch (e) {
      if (this.auth === 'popup' && e && e.status === 401) {
        this.pendingRef = ref; this.signin.drop('refused');
        return this.showGate('Your sign-in was not accepted any more; sign in again to see this.');
      }
      this.fail(e && e.code ? e : problem('DRS-5003', String((e && e.message) || e), 'Check the server option and that this page may reach it (CORS).'));
    } finally { this.container.removeAttribute('aria-busy'); }
    return this;
  }

  // ---- auth: 'popup' (CLIENT_RENDERER.md 10.1): the screen once signed in, else the sign-in button in place; never a window on load ----
  async signedLoad(ref) {
    this.pendingRef = ref;
    if (await this.signin.get()) { this.gating = false; return this.load(ref, false); }
    return this.showGate();
  }
  async showGate(note) {
    await this.ready.catch(() => {});
    const check = await this.signin.check();
    if (this.signin.has() && this.pendingRef) { this.gating = false; return this.load(this.pendingRef, false); }   // another screen signed in meanwhile
    const again = this.gating;
    this.gating = true;
    this.unsubscribe(); this.wipe(); this.vm = null; this.stack = [];
    this.root.appendChild(gate(check, (b, msg) => this.signInClick(b, msg), note));
    if (!again) { this.emit('auth-required', { server: this.o.server, allowed: !!check.allowed, code: check.code || null, detail: check.detail || null }); }
    return this;
  }
  signInClick(b, msg) {
    b.disabled = true;
    msg.textContent = 'Signing in…';
    this.signin.open(this.o.signInWindow).catch((e) => {
      b.disabled = false;
      msg.textContent = e && e.code === 'closed' ? 'The sign-in window was closed before you signed in.' : ((e && e.code) || 'DRS-8001') + ' ' + ((e && e.detail) || e);
    });
  }
  signed(what, d) {
    if (what === 'signed-in') {
      this.emit('signed-in', { user: d.user || '', server: this.o.server });
      if (this.gating && this.pendingRef) { this.gating = false; this.load(this.pendingRef, false); }
    } else if (what === 'signed-out') {
      this.emit('signed-out', { reason: d.reason || 'expired', server: this.o.server });
      this.pendingRef = (this.vm || {}).ref || this.pendingRef;
      if (this.o.signOutGate !== false) { this.showGate(d.reason === 'host' ? '' : 'Your sign-in has ended; sign in again to see this.'); }
    }
  }

  /** Opens an entity: drishti:navigate first (cancel it to route it yourself), then in place, or a new window or the page if the host asked. */
  open(link, info) {
    if (!link || !link.kind || !link.id) { return false; }
    const href = entityHref(this.o, link);
    const detail = Object.assign({ kind: link.kind, id: link.id, href, target: 'entity', navigation: this.o.navigation }, info || {});
    if (!this.emit('navigate', detail)) { return false; }
    if (this.o.navigation === 'host') { return false; }
    if (this.o.navigation === 'new-window') { window.open(drishtiUrl(href), '_blank', 'noopener'); return true; }
    if (this.o.navigation === 'follow') { window.location.assign(drishtiUrl(href)); return true; }
    this.unsubscribe();
    this.load({ kind: link.kind, id: link.id }, true);
    return true;
  }
  back() {
    const top = this.stack.pop();
    if (!top) { return false; }
    this.unsubscribe();
    this.paint(top.vm).then(() => { this.emit('loaded', { ref: top.vm.ref, depth: this.stack.length, back: true }); });
    return true;
  }
  /** Clicks on the host's own parts: Back, and the links of About this page (an entity opened as a link of the view is). */
  click(e) {
    const path = e.composedPath ? e.composedPath() : [e.target];
    if (path.some((n) => n && n.getAttribute && n.hasAttribute('data-r-back'))) { e.preventDefault(); this.back(); return; }
    const a = path.find((n) => n && n.getAttribute && n.hasAttribute('data-r-link'));
    if (!a || e.defaultPrevented) { return; }
    if (a.getAttribute('data-r-link') === 'entity') { e.preventDefault(); this.open({ kind: a.getAttribute('data-r-kind'), id: a.getAttribute('data-r-id') }, { source: 'link' }); return; }
    if (!this.emit('navigate', { target: 'console', href: drishtiUrl(a.href), source: 'link', navigation: this.o.navigation })) { e.preventDefault(); }
  }

  // ---- live frames (VIEWMODEL.md section 5), applied by the painter ------------------------------------------------------------
  /** Applies one frame {seq, generation, patches}: strip, panel, provenance, rows, deleted, restored. Returns what it names. */
  patch(frame) {
    const changed = { strip: [], panels: [] };
    if (!this.vm || !this.view || !frame) { return changed; }
    const patches = Array.isArray(frame.patches) ? frame.patches : [];
    patches.forEach((p) => {
      if (!p) { return; }
      if (p.op === 'strip') { changed.strip.push(p.index); } else if (p.op === 'panel' && p.panel) { changed.panels.push(p.panel.id); }
    });
    const ok = this.view.patch(frame);
    if (ok === false) { return changed; }
    patches.forEach((p) => {
      if (p && p.op === 'deleted') { this.emit('deleted', { at: p.at }); }
      if (p && p.op === 'restored') { this.emit('restored', {}); if (this.vm.ref) { this.load(this.vm.ref, false); } }
    });
    if (typeof frame.generation === 'number') { this.container.setAttribute('data-generation', String(frame.generation)); }
    this.emit('tick', { generation: frame.generation, seq: frame.seq, changed });
    return changed;
  }

  /** Subscribes to the view's stream (a URL of Server-Sent Events: view and frame events), or the default for this server. */
  subscribe(source) {
    this.unsubscribe();
    const ref = (this.vm || {}).ref;
    if (!ref && !source) { return () => {}; }
    let first = true;
    const on = {
      view: (vm) => { if (first) { first = false; if (this.vm && ((vm.provenance || {}).generation || 0) <= ((this.vm.provenance || {}).generation || 0)) { return; } } this.paint(vm); },
      frame: (f) => { first = false; this.patch(f); },
      message: () => {},                             // a GenUI stream message: not drawn here (GenUI inside Drishti uses the painter's pieces, i9)
      gone: (err) => this.emit('error', Object.assign({ fatal: false }, err))
    };
    // a function is the host's own subscription (the console's one channel per tab, an Elements connection): it is handed these
    // handlers and returns the function that stops it; a URL, or nothing, is an EventSource on the stream
    this.unsub = typeof source === 'function' ? (source(on) || (() => {})) : openStream(this.o, source || ref, on);
    return () => this.unsubscribe();
  }
  unsubscribe() { if (this.unsub) { this.unsub(); this.unsub = null; } }

  /** The About sheet (about: true): opens it, or with a boolean opens or closes it. */
  about(open) {
    const b = this.aboutBtn;
    if (b && (open == null || open !== (b.getAttribute('aria-expanded') === 'true'))) { b.click(); }
  }

  dispose() {
    if (this.offSign) { this.offSign(); this.offSign = null; }
    this.unsubscribe();
    this.root.removeEventListener('click', this.onClick); this.root.removeEventListener('keydown', this.onKey);
    this.wipe(); this.vm = null; this.stack = [];
  }
}
