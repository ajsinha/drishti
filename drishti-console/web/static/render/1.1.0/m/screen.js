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
/* One rendered screen in one container (CLIENT_RENDERER.md sections 4, 10, 11 and 12): it paints a ViewModel, applies live patches and
   streamed messages, navigates in place with its own back stack, and tells the host what happens through drishti:* events on the
   container. A static ViewModel, a live one and a streamed (ephemeral, GenUI) one all go through the same paint and the same patch. */
import { h, icon, str } from './dom.js';
import * as assets from './assets.js';
import { view, stripItem, banners, body } from './view.js';
import { panel } from './kinds.js';
import { enhance } from './behave.js';
import { charts } from './draw.js';
import { gridBehave } from './grid.js';
import { entityHref } from './cells.js';
import { problem, fetchView, openStream } from './net.js';
import { startBehaviours } from './console.js';
import { aboutSheet } from './about.js';
import { embedRecords } from './console.js';
import { signInFor, gate } from './signin.js';

const NAV = ['in-place', 'host', 'new-window', 'follow'];
const BEHAVIOURS = ['library', 'host', 'console'];
const AUTH = ['token', 'popup', 'session'];
const MODULE_ORIGIN = new URL(import.meta.url).origin;
const EMBED = '/embed/v2';
const enc = (x) => encodeURIComponent(String(x));
const idPath = (id) => String(id).split('/').map(enc).join('/');

export class Screen {
  constructor(container, options) {
    if (!container || container.nodeType !== 1) { throw problem('DRS-9101', 'render(container, …): the container must be an element', 'Pass a DOM element, e.g. document.getElementById("risk").'); }
    const o = Object.assign({ shadow: true, navigation: 'in-place', designOn: false, tables: true, help: true, keys: false, header: 'full', live: true,
      outsideLinks: 'event', consoleLinks: 'event', theme: 'inherit', server: '', behaviours: 'library', chrome: false, bare: false, about: false }, options || {});
    if (!NAV.includes(o.navigation)) { throw problem('DRS-9102', 'navigation must be one of ' + NAV.join(', ') + ', not ' + o.navigation, 'Leave it out for in-place, the default.'); }
    if (!BEHAVIOURS.includes(o.behaviours)) { throw problem('DRS-9106', 'behaviours must be one of ' + BEHAVIOURS.join(', ') + ', not ' + o.behaviours, "Leave it out for the library's own, the default."); }
    if (o.bare) { o.shadow = false; }
    o.server = String(o.server || '').replace(/\/$/, '');
    // 1.1.0: how the person gets in (CLIENT_RENDERER.md 10.1). 'token': the host's backend vouches (the token exchange); 'session': the console's
    // own sign-in (its pages, same-origin hosts); 'popup': a host without a backend, through the console's single sign-on in a window on a click
    this.auth = o.auth || (typeof o.token === 'function' ? 'token' : 'session');
    if (!AUTH.includes(this.auth)) { throw problem('DRS-9108', 'auth must be one of ' + AUTH.join(', ') + ', not ' + this.auth, "Leave it out: 'token' when you give a token, else the console's session."); }
    if (this.auth === 'popup') {
      o.server = o.server || MODULE_ORIGIN;
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
      if (typeof o.recordsUrl !== 'function') { o.recordsUrl = embedRecords(o.server, EMBED); }
      if (!options || options.live === undefined) { o.live = false; }     // live frames for a signed-in page: Elements 2.1 holds the page's one channel
      this.offSign = this.signin.on((what, d) => this.signed(what, d));
    }
    this.container = container; this.o = o; this.vm = null; this.stack = []; this.deleted = false; this.streaming = false; this.unsub = null; this.disposers = [];
    this.root = o.shadow ? (container.shadowRoot || container.attachShadow({ mode: 'open' })) : container;
    if (!o.bare && !container.hasAttribute('role')) { container.setAttribute('role', 'region'); }
    this.theme(o.theme);
    const R = this.R = {
      server: o.server, designOn: !!o.designOn, tables: o.tables, help: o.help, keys: o.keys, header: o.header, only: o.panels || null,
      outsideLinks: o.outsideLinks, asOf: o.asOf, businessDate: o.businessDate,
      behaviours: o.behaviours, chrome: !!o.chrome, grid: o.grid || null, about: !!o.about && !o.bare, backToLive: o.backToLive || null,
      emit: (name, detail) => this.emit(name, detail),
      open: (link, info) => this.open(link, info),
      vm: () => this.vm || {},
      back: () => (this.stack.length ? h('nav', { class: 'r-back', 'aria-label': 'Back' }, h('button', { type: 'button', class: 'fk', 'data-r-back': true, 'aria-keyshortcuts': 'Alt+ArrowLeft' },
        icon('arrow-left'), ' Back to ' + str(this.stack[this.stack.length - 1].label))) : null),
      pivotTab: (host, pid) => this.pivotTab(host, pid)
    };
    this.onClick = (e) => this.click(e);
    this.onKey = (e) => { if (e.altKey && e.key === 'ArrowLeft' && this.stack.length) { e.preventDefault(); this.back(); } };
    this.root.addEventListener('click', this.onClick);
    this.root.addEventListener('keydown', this.onKey);
    this.ready = o.shadow ? Promise.all([assets.sheet().then((s) => { this.root.adoptedStyleSheets = [s]; }), assets.font().catch(() => {})]) : Promise.resolve();
  }

  /** The theme on the container (a name from the console's tokens.css), or 'inherit' (none of its own: the host's custom properties). */
  theme(t) {
    this.o.theme = t || 'inherit';
    if (this.o.bare) { return; }                    // drawn into the host's own view: the host's page has the theme
    if (this.o.theme === 'inherit') { this.container.removeAttribute('data-theme'); } else { this.container.setAttribute('data-theme', this.o.theme); }
    if (this.charts && this.vm) { this.charts.redraw(); }
  }

  /** A drishti:<name> event on the container (bubbling, composed, cancelable); true when the host did not cancel it. */
  emit(name, detail) {
    const e = new CustomEvent('drishti:' + name, { detail, bubbles: true, composed: true, cancelable: true });
    return this.container.dispatchEvent(e);
  }

  // ---- painting --------------------------------------------------------------------------------------------------------------
  async paint(vm) {
    if (!vm || typeof vm !== 'object' || !Array.isArray(vm.panels)) { return this.fail(problem('DRS-9103', 'not a ViewModel: it has no panels list', 'Pass the JSON of GET /api/v1/views/{kind}/{id}, or {kind, id} to fetch it.')); }
    await this.ready.catch(() => {});
    this.teardown();
    this.vm = JSON.parse(JSON.stringify(vm));
    this.deleted = false;
    if (!this.o.bare) { this.container.setAttribute('aria-label', this.o.label || str((vm.title || {}).id || (vm.ref || {}).id)); }
    this.wipe();
    const v = view(this.R, this.vm);
    if (this.streaming) { v.setAttribute('aria-busy', 'true'); }
    this.wire(v);                                  // enhanced before it is in the page: the accessibility tree is built once, from the final roles
    if (this.o.bare) {                             // the host's own .view holds the parts (the console's page: its chrome stays around them)
      while (v.firstChild) { this.root.appendChild(v.firstChild); }
    } else { this.root.appendChild(v); }
    this.emit('paint', { ref: this.vm.ref, generation: (this.vm.provenance || {}).generation || 0 });
    if (this.R.behaviours === 'console') { this.behaviours = await startBehaviours(this, this.viewEl()); }
    // a host that lays the page out after the paint (the console's grid script) says when: charts are drawn at their final width
    if (this.o.beforeCharts && !this.chartsWaited) { this.chartsWaited = true; await Promise.resolve(this.o.beforeCharts).catch(() => {}); }
    await this.charts.draw(this.viewEl());
    return this;
  }
  wire(v) {
    enhance(v, this.R);
    this.charts = charts(this.R, this.root);
    const g = v.querySelector('[data-sgrid]');
    if (g && this.R.behaviours !== 'host') { this.disposers.push(gridBehave(g, (shown) => shown && this.charts.draw(shown))); }
    if (this.R.about) { this.disposers.push(aboutSheet(this, v)); }
  }
  teardown() {
    if (this.behaviours) { this.behaviours.dispose(); this.behaviours = null; }
    if (this.charts) { this.charts.dispose(); this.charts = null; }
    this.disposers.splice(0).forEach((f) => { try { f(); } catch (e) { /* gone */ } });
  }
  viewEl() { return this.o.bare ? this.container : this.root.querySelector('.view'); }
  /** Empties the root of what this screen drew; a host's own nodes marked data-r-keep (an element's slots) stay. */
  wipe() { [...this.root.childNodes].forEach((n) => { if (!(n.nodeType === 1 && n.hasAttribute('data-r-keep'))) { n.remove(); } }); }

  /** A failure in words with its DRS code and the fix; emitted as drishti:error. */
  fail(err) {
    this.emit('error', err);
    this.wipe();
    this.root.appendChild(h('div', { class: 'view' }, h('div', { class: 'dv-error asof-banner', role: 'alert', 'data-code': err.code },
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
    this.unsubscribe(); this.teardown(); this.wipe(); this.vm = null; this.stack = [];
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
  /** Opens an entity: drishti:navigate first (cancel it to route it yourself), then in place, or a new window if the host asked. */
  open(link, info) {
    const href = entityHref(this.R, link);
    const detail = Object.assign({ kind: link.kind, id: link.id, href, target: 'entity', navigation: this.o.navigation }, info || {});
    if (!this.emit('navigate', detail)) { return false; }
    if (this.o.navigation === 'host') { return false; }
    if (this.o.navigation === 'new-window') { window.open(href, '_blank', 'noopener'); return true; }
    if (this.o.navigation === 'follow') { window.location.assign(href); return true; }       // a chart point or a key: the page goes there, as a link would
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
  click(e) {
    const path = e.composedPath ? e.composedPath() : [e.target];
    if (path.some((n) => n && n.getAttribute && n.hasAttribute('data-r-back'))) { e.preventDefault(); this.back(); return; }
    const key = path.find((n) => n && n.getAttribute && n.hasAttribute('data-fkey'));
    if (key) { this.key(key); return; }
    const a = path.find((n) => n instanceof HTMLAnchorElement);
    if (!a || e.defaultPrevented) { return; }
    const kind = a.getAttribute('data-r-link');
    const pnl = a.closest('.pnl'), panelId = pnl ? pnl.getAttribute('data-panel') : null;
    // a link the browser follows itself (navigation 'follow', the console's own pages): the event first, then the link as a link, so
    // Ctrl+click, a middle click and "open in a new tab" behave as on any page; cancelling the event keeps the page where it is
    const native = (target, mode) => {
      const d = { target, href: a.href, panel: panelId, source: 'link', navigation: this.o.navigation, mode };
      if (target === 'entity') { d.kind = a.getAttribute('data-r-kind'); d.id = a.getAttribute('data-r-id'); }
      if (!this.emit('navigate', d)) { e.preventDefault(); }
    };
    if (kind === 'entity') {
      if (this.o.navigation === 'follow') { native('entity', 'follow'); return; }
      e.preventDefault(); this.open({ kind: a.getAttribute('data-r-kind'), id: a.getAttribute('data-r-id') }, { panel: panelId, source: 'link' }); return;
    }
    if (kind === 'console' || kind === 'outside') {
      const mode = kind === 'console' ? this.o.consoleLinks : this.o.outsideLinks;
      if (mode === 'follow' || (mode === 'new-window' && kind === 'outside')) { native(kind, mode); return; }   // the link itself (target=_blank: the user's own new tab)
      e.preventDefault();
      if (!this.emit('navigate', { target: kind, href: a.href, panel: panelId, source: 'link', navigation: this.o.navigation })) { return; }
      if (mode === 'new-window') { window.open(a.href, '_blank', 'noopener'); }
    }
  }
  key(b) {
    const action = b.getAttribute('data-action');
    if (action === 'panel') { const p = this.root.querySelector('[id="p-' + b.getAttribute('data-target') + '"]'); if (p) { p.scrollIntoView({ block: 'start' }); p.focus({ preventScroll: true }); } }
    else if (action === 'link') { this.open({ kind: b.getAttribute('data-r-kind'), id: b.getAttribute('data-r-id') }, { source: 'key' }); }
    else { this.emit('key', { key: b.getAttribute('data-fkey'), action }); }
  }

  /** The Pivot tab with the library's own behaviours: the host is told (drishti:pivot); behaviours 'console' builds the grid itself. */
  pivotTab(host, pid) {
    if (this.emit('pivot', { panel: pid, host }) && !host.firstChild) {
      host.appendChild(h('p', { class: 'pnl-empty', role: 'status' }, icon('grid-3x3'), " The pivot grid comes with behaviours: 'console'."));
    }
  }

  // ---- live patches (VIEWMODEL.md section 5) --------------------------------------------------------------------------------
  /** Applies one frame {seq, generation, patches}: strip, panel, provenance, deleted, restored, in order. Returns what changed. */
  patch(frame) {
    const changed = { strip: [], panels: [] };
    if (!this.vm || !frame) { return changed; }
    for (const p of frame.patches || []) {
      try {
        if (p.op === 'restored') { if (this.deleted) { this.deleted = false; this.viewEl().classList.remove('r-deleted'); this.emit('restored', {}); if (this.vm.ref) { this.load(this.vm.ref, false); } } continue; }
        if (this.deleted) { continue; }
        if (p.op === 'deleted') { this.deleted = true; this.freeze(p.at); continue; }
        if (p.op === 'strip') { if (this.stripPatch(p.index, p.cell)) { changed.strip.push(p.index); } }
        else if (p.op === 'panel' && p.panel) { if (this.replacePanel(p.panel)) { changed.panels.push(p.panel.id); } }
        else if (p.op === 'provenance' && p.provenance) { this.vm.provenance = p.provenance; this.provenanceChanged(); }
      } catch (e) { if (window.console) { console.warn('drishti-render: patch not applied', p.op, e); } }
    }
    const v = this.viewEl();
    if (v && typeof frame.generation === 'number') { v.setAttribute('data-generation', String(frame.generation)); }
    this.emit('tick', { generation: frame.generation, seq: frame.seq, changed });
    return changed;
  }
  stripPatch(i, cell) {
    const old = this.root.querySelector('[data-r-strip="' + i + '"]');
    if (!old || !cell) { return false; }
    this.vm.strip[i] = cell;
    const fresh = stripItem(this.R, cell, i);
    fresh.querySelector('dd').classList.add('live-cell');
    old.replaceWith(fresh);
    return true;
  }
  replacePanel(p) {
    const old = this.root.querySelector('[id="p-' + (window.CSS && CSS.escape ? CSS.escape(p.id) : p.id) + '"]');
    const at = this.vm.panels.findIndex((x) => x.id === p.id);
    if (at >= 0) { this.vm.panels[at] = Object.assign({}, this.vm.panels[at].grid ? { grid: this.vm.panels[at].grid } : {}, p); }
    if (!old) { return false; }
    const fresh = panel(this.R, Object.assign({}, p, { area: p.area || (this.vm.panels[at] || {}).area }), this.vm.ref);
    if (old.style.getPropertyValue('--sg-po')) { fresh.style.setProperty('--sg-po', old.style.getPropertyValue('--sg-po')); }
    if (old.hidden) { fresh.hidden = true; } if (old.hasAttribute('role')) { fresh.setAttribute('role', old.getAttribute('role')); }
    const after = typeof this.o.onReplace === 'function' ? this.o.onReplace(old, p) : null;     // the host keeps what it put in the panel (a control in its header)
    if (this.R.chrome) {                             // the reader's own sizes (layout mode) and whether they hid it stay through a tick
      const mine = /^(c-(span|h)-\d+|pnl-off)$/;
      fresh.className = fresh.className.split(/\s+/).filter((c) => !mine.test(c)).concat(old.className.split(/\s+/).filter((c) => mine.test(c))).join(' ');
      ['data-span', 'data-height'].forEach((k) => { if (old.hasAttribute(k)) { fresh.setAttribute(k, old.getAttribute(k)); } });
    }
    const fold = old.querySelector(':scope > .pnl-h > .sg-fold');
    if (fold) { fresh.querySelector('.pnl-h').insertBefore(fold, fresh.querySelector('.pnl-h').firstChild); if (old.classList.contains('sg-folded')) { fresh.classList.add('sg-folded'); } }
    old.replaceWith(fresh);
    enhance(fresh, this.R);
    if (typeof after === 'function') { after(fresh); }
    if (this.behaviours) { this.behaviours.enhance(fresh); }
    this.emit('panel', { panel: p.id, element: fresh });
    if (this.charts) { this.charts.draw(fresh); }
    return true;
  }
  /** The panels of another ViewModel in their places (the flow's columns or the grid; with the host's arranged grid), enhanced: for a host
      that redraws a page's body itself (the console's Customize, Outline and Grid modes after a step). */
  drawBody(vm, grid) {
    const R = Object.assign({}, this.R, { grid: grid || null });
    const el = body(R, vm);
    enhance(el, R);
    return el;
  }
  /** One panel drawn from its JSON, for a host that swaps it in itself (a control, a row's event, a refresh): enhanced, charts drawn once placed. */
  draw(p) {
    const fresh = panel(this.R, p, (this.vm || {}).ref);
    enhance(fresh, this.R);
    return fresh;
  }
  provenanceChanged() {
    const v = this.viewEl();
    if (!v) { return; }
    v.querySelectorAll(':scope > .asof-banner').forEach((b) => b.remove());
    const bs = banners(this.R, this.vm), headEl = v.querySelector(':scope > .vhead') || v.querySelector(':scope > .vgrid');
    bs.forEach((b) => v.insertBefore(b, headEl));
  }
  freeze(at) {
    const v = this.viewEl();
    v.classList.add('r-deleted');
    v.insertBefore(h('p', { class: 'asof-banner r-deleted-banner', role: 'alert' }, icon('trash'), ' ', h('b', null, str((this.vm.ref || {}).id) + ' was deleted'),
      at ? ' at ' + at : '', '. What you see is how it was; it updates again if it comes back.'), v.firstChild);
    this.emit('deleted', { at });
  }

  /** Subscribes to the view's stream (a URL of Server-Sent Events: view and frame events), or the default for this server. */
  subscribe(source) {
    this.unsubscribe();
    const ref = (this.vm || {}).ref;
    if (!ref && !source) { return () => {}; }
    let first = true;
    const on = {
      view: (vm) => { if (first) { first = false; if (((vm.provenance || {}).generation || 0) <= ((this.vm.provenance || {}).generation || 0)) { return; } } this.paint(vm); },
      frame: (f) => { first = false; this.patch(f); },
      message: (m) => this.apply(m),
      gone: (err) => this.emit('error', Object.assign({ fatal: false }, err))
    };
    // a function is the host's own subscription (the console's one channel per tab, an Elements connection): it is handed these
    // handlers and returns the function that stops it; a URL, or nothing, is an EventSource on the stream
    this.unsub = typeof source === 'function' ? (source(on) || (() => {})) : openStream(this.o, source || ref, on);
    return () => this.unsubscribe();
  }
  unsubscribe() { if (this.unsub) { this.unsub(); this.unsub = null; } }

  // ---- a streamed ViewModel (GenUI: an ephemeral Sutra bound on the server, panel by panel) ------------------------------
  /** Applies one stream message: skeleton, panel-add, panel-replace, patch, done or error (CLIENT_RENDERER.md section 11). */
  async apply(m) {
    if (!m || typeof m !== 'object') { return this; }
    switch (m.type) {
      case 'skeleton': this.streaming = true; await this.paint(Object.assign({ strip: [], keys: [], provenance: {} }, m.view, { panels: (m.view.panels || []).map(pendingPanel) })); break;
      case 'panel-add': await this.addPanel(m.panel, m.after); break;
      case 'panel-replace': this.replacePanel(m.panel); break;
      case 'patch': this.patch(m.frame || m); break;
      case 'done': {
        this.streaming = false;
        if (m.provenance && this.vm) { this.vm.provenance = m.provenance; this.provenanceChanged(); }
        const v = this.viewEl(); if (v) { v.removeAttribute('aria-busy'); }
        this.emit('ready', { ref: (this.vm || {}).ref, panels: ((this.vm || {}).panels || []).length });
        break;
      }
      case 'error': {
        if (m.panel) { this.replacePanel({ id: m.panel, kind: ((this.vm.panels.find((p) => p.id === m.panel)) || {}).kind || 'markdown', title: ((this.vm.panels.find((p) => p.id === m.panel)) || {}).title, empty: true, error: m.detail || m.code }); }
        else { this.fail(problem(m.code || 'DRS-5003', m.detail || 'the stream failed', m.fix)); }
        break;
      }
      default: break;                              // a message this client does not know: ignored (VIEWMODEL.md section 8)
    }
    return this;
  }
  async addPanel(p, after) {
    if (!p || !p.id) { return; }
    if (this.vm.panels.some((x) => x.id === p.id)) { this.replacePanel(p); return; }
    const i = after ? this.vm.panels.findIndex((x) => x.id === after) : -1;
    this.vm.panels.splice(i >= 0 ? i + 1 : this.vm.panels.length, 0, p);
    if (this.vm.layout) { const s = this.streaming; await this.paint(this.vm); this.streaming = s; return; }     // a new place in a grid: lay it out again
    const col = this.root.querySelector(p.area === 'right' ? '.vright' : '.vmain');
    const fresh = panel(this.R, p, this.vm.ref), prev = after && this.root.querySelector('[id="p-' + after + '"]');
    if (prev && prev.parentNode === col) { prev.after(fresh); } else { col.appendChild(fresh); }
    enhance(fresh, this.R);
    this.charts.draw(fresh);
  }

  /** The About sheet (about: true): opens it, or with a boolean opens or closes it. */
  about(open) {
    const v = this.viewEl(), b = v && v.querySelector('[data-r-about]');
    if (b && (open == null || open !== (b.getAttribute('aria-expanded') === 'true'))) { b.click(); }
  }

  dispose() {
    if (this.offSign) { this.offSign(); this.offSign = null; }
    this.unsubscribe(); this.teardown();
    this.root.removeEventListener('click', this.onClick); this.root.removeEventListener('keydown', this.onKey);
    this.wipe(); this.vm = null; this.stack = [];
  }
}

/** A panel announced by a skeleton and not bound yet: its frame, saying it is on its way. */
function pendingPanel(p) {
  return p.data !== undefined || p.empty !== undefined ? p : Object.assign({}, p, { empty: false, wire: { waits: 'Loading…' } });
}
