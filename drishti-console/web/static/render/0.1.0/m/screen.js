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
import { h, clear, icon, str } from './dom.js';
import * as assets from './assets.js';
import { view, stripItem, banners } from './view.js';
import { panel } from './kinds.js';
import { enhance } from './behave.js';
import { charts } from './draw.js';
import { gridBehave } from './grid.js';
import { entityHref } from './cells.js';
import { problem, fetchView, openStream } from './net.js';

const NAV = ['in-place', 'host', 'new-window'];

export class Screen {
  constructor(container, options) {
    if (!container || container.nodeType !== 1) { throw problem('DRS-9101', 'render(container, …): the container must be an element', 'Pass a DOM element, e.g. document.getElementById("risk").'); }
    const o = Object.assign({ shadow: true, navigation: 'in-place', designOn: false, tables: true, help: true, keys: false, header: 'full', live: true,
      outsideLinks: 'event', consoleLinks: 'event', theme: 'inherit', server: '' }, options || {});
    if (!NAV.includes(o.navigation)) { throw problem('DRS-9102', 'navigation must be one of ' + NAV.join(', ') + ', not ' + o.navigation, 'Leave it out for in-place, the default.'); }
    o.server = String(o.server || '').replace(/\/$/, '');
    this.container = container; this.o = o; this.vm = null; this.stack = []; this.deleted = false; this.streaming = false; this.unsub = null; this.disposers = [];
    this.root = o.shadow ? (container.shadowRoot || container.attachShadow({ mode: 'open' })) : container;
    if (!container.hasAttribute('role')) { container.setAttribute('role', 'region'); }
    this.theme(o.theme);
    const R = this.R = {
      server: o.server, designOn: !!o.designOn, tables: o.tables, help: o.help, keys: o.keys, header: o.header, only: o.panels || null,
      outsideLinks: o.outsideLinks, asOf: o.asOf, businessDate: o.businessDate,
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
    this.container.setAttribute('aria-label', this.o.label || str((vm.title || {}).id || (vm.ref || {}).id));
    clear(this.root);
    const v = view(this.R, this.vm);
    if (this.streaming) { v.setAttribute('aria-busy', 'true'); }
    this.wire(v);                                  // enhanced before it is in the page: the accessibility tree is built once, from the final roles
    this.root.appendChild(v);
    this.emit('paint', { ref: this.vm.ref, generation: (this.vm.provenance || {}).generation || 0 });
    await this.charts.draw(v);
    return this;
  }
  wire(v) {
    enhance(v, this.R);
    this.charts = charts(this.R, this.root);
    const g = v.querySelector('[data-sgrid]');
    if (g) { this.disposers.push(gridBehave(g, (shown) => shown && this.charts.draw(shown))); }
  }
  teardown() {
    if (this.charts) { this.charts.dispose(); this.charts = null; }
    this.disposers.splice(0).forEach((f) => { try { f(); } catch (e) { /* gone */ } });
  }
  viewEl() { return this.root.querySelector('.view'); }

  /** A failure in words with its DRS code and the fix; emitted as drishti:error. */
  fail(err) {
    this.emit('error', err);
    clear(this.root);
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
      this.fail(e && e.code ? e : problem('DRS-5003', String((e && e.message) || e), 'Check the server option and that this page may reach it (CORS).'));
    } finally { this.container.removeAttribute('aria-busy'); }
    return this;
  }
  /** Opens an entity: drishti:navigate first (cancel it to route it yourself), then in place, or a new window if the host asked. */
  open(link, info) {
    const href = entityHref(this.R, link);
    const detail = Object.assign({ kind: link.kind, id: link.id, href, target: 'entity', navigation: this.o.navigation }, info || {});
    if (!this.emit('navigate', detail)) { return false; }
    if (this.o.navigation === 'host') { return false; }
    if (this.o.navigation === 'new-window') { window.open(href, '_blank', 'noopener'); return true; }
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
    if (kind === 'entity') { e.preventDefault(); this.open({ kind: a.getAttribute('data-r-kind'), id: a.getAttribute('data-r-id') }, { panel: panelId, source: 'link' }); return; }
    if (kind === 'console' || kind === 'outside') {
      const mode = kind === 'console' ? this.o.consoleLinks : this.o.outsideLinks;
      if (mode === 'new-window' && kind === 'outside') { return; }        // target=_blank on the link itself: the user's own new tab
      e.preventDefault();
      if (!this.emit('navigate', { target: kind, href: a.href, panel: panelId, source: 'link', navigation: this.o.navigation })) { return; }
      if (mode === 'follow') { window.location.assign(a.href); } else if (mode === 'new-window') { window.open(a.href, '_blank', 'noopener'); }
    }
  }
  key(b) {
    const action = b.getAttribute('data-action');
    if (action === 'panel') { const p = this.root.querySelector('[id="p-' + b.getAttribute('data-target') + '"]'); if (p) { p.scrollIntoView({ block: 'start' }); p.focus({ preventScroll: true }); } }
    else if (action === 'link') { this.open({ kind: b.getAttribute('data-r-kind'), id: b.getAttribute('data-r-id') }, { source: 'key' }); }
    else { this.emit('key', { key: b.getAttribute('data-fkey'), action }); }
  }

  /** The Pivot tab: the console's pivot grid builds it when the page has it; otherwise the host is told (phase 2 ports it). */
  pivotTab(host, pid) {
    const reg = window.drishtiModules || {};
    if (this.emit('pivot', { panel: pid, host }) && !(reg.pivot && reg.pivot.init)) {
      host.appendChild(h('p', { class: 'pnl-empty', role: 'status' }, icon('grid-3x3'), ' The pivot opens in the console.'));
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
    const fold = old.querySelector(':scope > .pnl-h > .sg-fold');
    if (fold) { fresh.querySelector('.pnl-h').insertBefore(fold, fresh.querySelector('.pnl-h').firstChild); if (old.classList.contains('sg-folded')) { fresh.classList.add('sg-folded'); } }
    old.replaceWith(fresh);
    enhance(fresh, this.R);
    if (this.charts) { this.charts.draw(fresh); }
    return true;
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
    this.unsub = openStream(this.o, source || ref, {
      view: (vm) => { if (first) { first = false; if (((vm.provenance || {}).generation || 0) <= ((this.vm.provenance || {}).generation || 0)) { return; } } this.paint(vm); },
      frame: (f) => { first = false; this.patch(f); },
      message: (m) => this.apply(m),
      gone: (err) => this.emit('error', Object.assign({ fatal: false }, err))
    });
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

  dispose() {
    this.unsubscribe(); this.teardown();
    this.root.removeEventListener('click', this.onClick); this.root.removeEventListener('keydown', this.onKey);
    clear(this.root); this.vm = null; this.stack = [];
  }
}

/** A panel announced by a skeleton and not bound yet: its frame, saying it is on its way. */
function pendingPanel(p) {
  return p.data !== undefined || p.empty !== undefined ? p : Object.assign({}, p, { empty: false, wire: { waits: 'Loading…' } });
}
