/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* paint(el, source, options): a ViewModel drawn into an element, and the handle that keeps it. How a paint goes: read the source (a
   ViewModel, an address, a Response, a promise of one), refuse a major it does not draw, validate (warn by default), then draw the
   frame in ONE pass: the title line, the strip, the freshness line, the keys and every panel's frame in its place, each in its state.
   That first paint needs no engine. Then ready. A view is drawn inside a shadow root by default, so a ViewModel's names can neither
   collide with the page nor be styled by it; the theme's tokens still reach it (custom properties inherit).
   The painter computes nothing: every figure arrives as the producer's text. */
import { h, manifest, staticUrl, loadStyle, loadScript, load, loaded, loadPart, now, fetcher } from '../../drishti-core/src/drishti_index.js';
import { CONTRACT } from './drishti_views-schema.js';
import { validate, formatIssue } from './drishti_views-validate.js';
import { complete } from './drishti_views-build.js';
import { MESSAGES, say, safeHref } from './drishti_views-cells.js';
import { PanelFrame } from './drishti_views-panels.js';
import { arrange, Layout } from './drishti_views-layout.js';
import { Screen, problemCard } from './drishti_views-screen.js';
import { Patcher } from './drishti_views-patch.js';
import { LiveLink } from './drishti_views-live.js';
import { datasetsOf } from './drishti_views-data.js';
import { StateStore, resolveFire, resolveWire, requestText } from './drishti_views-state.js';
import { ask } from './drishti_views-forms.js';

/** The contract majors this painter draws. */
export const SPECS = Object.freeze([Number(String(CONTRACT).split('.')[0])]);

const HANDLES = new WeakMap();
const EVENTS = ['ready', 'error', 'issues', 'link', 'key', 'patch', 'navigate', 'pick', 'select', 'state-change', 'notify', 'host', 'refused', 'bound', 'drawn', 'dispose', 'point', 'filter', 'chart-table'];
const CANCELABLE = { link: true, key: true };
let counter = 0;

/**
 * @typedef {Object} PaintOptions
 * @property {boolean} [shadow=true] draw inside a shadow root (false: into the element itself, styled by the page's sheets)
 * @property {CSSStyleSheet[]} [sheets] the host styles the view: these constructable sheets (Elements' tokens and the painter's and libraries'
 *   rules, as the host builds them) are adopted by the view's shadow root, or without one by the root the element is in (a host's shadow
 *   root or the document), and the painter links no stylesheet of its own or of a library: a host page whose style-src refuses links works
 * @property {function(): Promise<Object>} [engine] loads the chart engine (ECharts) the host's way (its Trusted Types policy); default: the
 *   painter loads the vendored engine the first time a view has a chart (drishti-core's loadScript of drishti-chart's scripts)
 * @property {{filters: boolean, chart: boolean}} [tableTools] filters: a funnel on each column heading of a table (its values with counts,
 *   Select all, a search, a condition on a number column such as "> 1m"), filters that combine across columns; chart: Chart any table (a
 *   table's rows as shown, or a grid's range, opened as a chart in a dialog beside it: drishti-chart's openTableChart)
 * @property {('comfortable'|'compact'|'terminal')} [density] the density of the view (default: the page's)
 * @property {Object<string, string>} [messages] the painter's own words, over the bundled English ones
 * @property {('full'|'strip'|'none')} [header='full'] how much of the header is drawn
 * @property {string[]} [panels] draw only these panel ids
 * @property {('off'|'warn'|'refuse')} [validate='warn'] warn: paint and report the issues; refuse: do not paint an invalid view
 * @property {boolean} [complete=false] fill the fields contract 1 requires and the producer left out (ref, provenance, timings, a title's pill ...)
 *   with null or the type's empty value before checking, on a copy: a view made by hand, or by a producer that sends the minimum, then draws without
 *   the console warning "has no ref" (the contract still requires them of a producer that claims to follow it)
 * @property {boolean} [keys=false] the view's function keys answer the keyboard (inside the view)
 * @property {{href: function(Object): ?string, resolveView: function(Object): *}} [links] href: an address for a Link (else a link is a
 *   button that raises the link event); resolveView: a ViewModel, an address or a promise of either for a Link, painted in place with a back step
 * @property {function(string, Object): Promise<Response>} [fetch] the host's fetch, for a view given by its address
 * @property {string} [label] the region's accessible name (default: the title)
 * @property {string} [locale] how times are printed (default: the page's)
 * @property {Object<string, Object>} [libraries] the libraries a kind draws with, given by the host (drishti-chart, drishti-graph); else
 *   the ones on the page, else loaded by drishti-core's loader the first time a view has such a panel
 * @property {(function(): Promise<Object>|boolean)} [geo] the map helper of map panels (world, region, find, option, codeOf): by default
 *   drishti-chart's geo part (loaded the first time a view has a map, with the vendored Natural Earth shapes); a host's own function
 *   overrides it; false: no maps (a map panel says so in words)
 * @property {string} [mapsBase] the folder of the map shapes for the default helper (default: the static root's vendor/echarts/maps/)
 * @property {function(): Promise<void>} [gl] loads the 3D engine for a surface's 3D view
 * @property {{notify: ?function(Object, Object): void, run: ?function(Object, Object): void, bind: ?function(Object): *}} [host] the host's hooks for the screen's
 *   state and effects (contract 1.2). notify(effect, where) shows a notify effect {do, text, tone}; run(effect, where) runs a host effect {do, action, with}
 *   (Elements never runs one); bind({state, params}) is the next bind after a click wrote state: state is every value as text (null: cleared, its default
 *   stands), params the same as state.<id> request parameters; it answers the new ViewModel (or a promise of one), of which only the panels the changed
 *   states' reads name are drawn again. Without bind the values are kept and nothing is drawn again. where is {panel, trigger, mark}.
 * @property {import('./drishti_views-live.js').LiveOptions} [live] the view's live stream over drishti-events (subscribed after the first paint)
 */

function isView(v) { return v !== null && typeof v === 'object' && !Array.isArray(v) && typeof v.then !== 'function' && !(typeof Response !== 'undefined' && v instanceof Response); }

/** A painted view: what paint() answers. */
export class ViewHandle {
  /**
   * @param {HTMLElement} el the container
   * @param {PaintOptions} [options]
   */
  constructor(el, options) {
    this.el = el;
    this.options = Object.assign({ header: 'full', validate: 'warn', shadow: true }, options || {});
    this.listeners = new Map();
    this.stats = { paints: 0, patches: 0, ignored: 0, dropped: 0 };
    this.history = [];
    this.frames = new Map();
    this.vm = null;
    this.state = new StateStore();
    this.firing = Promise.resolve();
    this.disposed = false;
    this.prefix = 'dv' + (++counter) + '-';
    this.ctx = this.context();
    this.patcher = new Patcher(this);
    this.host = this.mountRoot();
    this.root = h(this.ctx.doc, 'div', { cls: 'dv-view', attrs: { role: 'region', 'data-density': this.options.density || null } });
    this.root.addEventListener('keydown', (e) => this.onKey(e));
    this.host.appendChild(this.root);
    this.observe();
    let done;
    /** @type {Promise<ViewHandle>} the first paint is done (every panel drawn or in a state, or a card in its place) */
    this.ready = new Promise((r) => { done = r; });
    this.resolveReady = done;
    if (this.options.live) {
      this.ready.then(() => { if (!this.disposed && !this.liveLink) { this.liveLink = new LiveLink(this, this.options.live); this.liveLink.ready.catch((e) => this.emit('error', { code: 'view.live', error: e })); } });
    }
  }

  /** @private */
  context() {
    const self = this, o = this.options, doc = this.el.ownerDocument;
    const messages = Object.assign({}, MESSAGES, o.messages || {});
    let timeFmt = null;
    return {
      doc: doc, messages: messages, label: o.label || '',
      uid: (s) => self.prefix + String(s).replace(/[^A-Za-z0-9_-]/g, '_'),
      hrefOf: (link) => (o.links && typeof o.links.href === 'function' ? safeHref(o.links.href(link)) : null),
      open: (link, how) => self.openLink(link, how),
      emit: (name, detail) => self.emit(name, detail),
      key: (k) => self.pressKey(k),
      back: () => self.back(),
      flash: (node) => self.flash(node),
      library: (name) => self.library(name),
      charts: () => self.charts(),
      tools: Object.assign({ filters: false, chart: false }, o.tableTools || {}),
      chartTable: (table, opener) => self.chartTable(table, opener),
      styles: (name) => self.styles(name),
      fire: (id, trigger, mark) => self.fire(id, trigger, mark).catch(() => null),
      root: self.root,
      checkForm: (id, values) => self.checkForm(id, values),
      submitForm: (id, values) => self.fire(id, 'submit', 0, values, true),
      ask: (text) => (o.host && typeof o.host.confirm === 'function' ? Promise.resolve(o.host.confirm(text)).then((v) => v === true) : ask(self.ctx, text)),
      // fail closed (G2 gate F10, owner O8): an action that names a permission is enabled only when the host answers exactly true; no hook, no answer, or any
      // other answer leaves it disabled (a page that wants every button passes allowed: () => true). The host still decides in run: Elements runs no host action.
      allowed: (name) => { try { return !!(o.host && typeof o.host.allowed === 'function') && o.host.allowed(name) === true; } catch (e) { return false; } },
      dataset: (name) => (self.datasets && typeof name === 'string' ? self.datasets.get(name) || null : null),
      tables: (ref) => (typeof o.tables === 'function' ? o.tables(ref) || null : null),
      density: o.density || null,
      schemaEvery: o.liveSchemaMs > 0 ? o.liveSchemaMs : 15000,
      can: (what) => (what === 'geo' ? o.geo !== false : false),
      time: (iso) => {
        const d = new Date(iso);
        if (isNaN(d.getTime())) { return String(iso); }
        try { timeFmt = timeFmt || new Intl.DateTimeFormat(o.locale || undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' }); return timeFmt.format(d); } catch (e) { return String(iso); }
      }
    };
  }

  /** @private */
  mountRoot() {
    const el = this.el;
    if (this.options.shadow !== false && typeof el.attachShadow === 'function') {
      let sr = el.shadowRoot;
      if (!sr) { try { sr = el.attachShadow({ mode: 'open' }); } catch (e) { sr = null; } }
      if (sr) {
        while (sr.firstChild) { sr.removeChild(sr.firstChild); }
        if (this.hostSheets()) { this.sheets = []; adoptSheets(sr, this.options.sheets); return sr; }
        this.sheets = this.links(sr);
        return sr;
      }
    }
    this.sheets = [];
    if (this.hostSheets()) { adoptSheets(el.getRootNode ? el.getRootNode() : el.ownerDocument, this.options.sheets); } else { sheetPaths().forEach((p) => loadStyle(staticUrl(p))); }
    while (el.firstChild) { el.removeChild(el.firstChild); }
    return el;
  }

  /** @private whether the host styles the view (options.sheets): the painter then links no stylesheet */
  hostSheets() { return Array.isArray(this.options.sheets); }

  /** @private */
  links(sr) {
    const doc = this.el.ownerDocument, pack = this.packSheet(doc);
    if (pack) { this.styled = this.styled || new Set(); this.styled.add(pack.href); }          // a pack's one stylesheet stands for every sheet
    return (pack ? [pack.href] : sheetPaths().map((p) => staticUrl(p))).map((href) => {
      const l = doc.createElement('link');
      l.rel = 'stylesheet';
      l.href = href;
      const loaded = new Promise((r) => { l.addEventListener('load', r, { once: true }); l.addEventListener('error', r, { once: true }); });
      sr.appendChild(l);
      return loaded;
    });
  }

  /** @private */
  observe() {
    const win = this.el.ownerDocument.defaultView;
    if (win && typeof win.ResizeObserver === 'function') {
      this.ro = new win.ResizeObserver((entries) => {
        const w = entries[entries.length - 1].contentRect.width;
        this.width = w;
        if (this.layout) { this.layout.fit(w); }
      });
      this.ro.observe(this.root);
    }
  }

  /**
   * Reads and paints a source: a ViewModel, an address, a Response or a promise of one.
   * @param {*} source
   * @returns {ViewHandle}
   */
  load(source) {
    const t0 = now();
    if (isView(source)) {                       // drawn in a microtask: listeners attached right after paint() hear its events
      Promise.resolve().then(() => { if (!this.disposed) { this.show(source, t0); } });
      return this;
    }
    let p;
    if (typeof source === 'string') {
      this.src = source;
      const f = fetcher(this.options.fetch) || ((u, init) => fetch(u, init));
      p = Promise.resolve().then(() => f(source, { credentials: 'same-origin', headers: { Accept: 'application/json' } })).then((r) => {
        if (!r.ok) { throw Object.assign(new Error(r.status + ' ' + (r.statusText || '')), { status: r.status }); }
        return r.json();
      });
    } else if (source && typeof source.json === 'function') {
      p = source.json();
    } else if (source && typeof source.then === 'function') {
      p = source.then((x) => (x && typeof x.json === 'function' && !isView(x) ? x.json() : x));
    } else {
      Promise.resolve().then(() => { if (!this.disposed) { this.show(source, t0); } });
      return this;
    }
    this.root.setAttribute('aria-busy', 'true');
    p.then((vm) => { if (!this.disposed) { this.show(vm, now()); } }, (e) => {
      if (this.disposed) { return; }
      this.card({ title: say(this.ctx.messages, 'viewFetch'), detail: String(e && e.message || e), retry: () => this.load(source) });
      this.emit('error', { code: 'view.fetch', status: e && e.status, error: e });
      this.settle();
    });
    return this;
  }

  /** @private */
  show(vm, t0) {
    const m = this.ctx.messages;
    if (!isView(vm)) {
      this.card({ title: say(m, 'viewInvalid'), detail: 'the view is not a JSON object' });
      this.emit('error', { code: 'view.invalid', issues: [] });
      return this.settle();
    }
    const major = typeof vm.contract === 'string' ? Number(vm.contract.split('.')[0]) : SPECS[0];
    if (SPECS.indexOf(major) < 0) {
      this.card({ title: say(m, 'viewVersion', { major: major }), detail: 'contract ' + vm.contract });
      this.emit('error', { code: 'view.version', contract: vm.contract });
      return this.settle();
    }
    if (this.options.complete) { vm = complete(JSON.parse(JSON.stringify(vm)), 'ViewModel'); }
    if (this.options.validate !== 'off') {
      const r = validate(vm);
      if (r.issues.length) {
        this.emit('issues', { issues: r.issues });
        if (!r.ok && this.options.validate === 'refuse') {
          this.card({ title: say(m, 'viewInvalid'), issues: r.issues.filter((i) => i.level === 'error').map(formatIssue) });
          this.emit('error', { code: 'view.invalid', issues: r.issues });
          return this.settle();
        }
        if (!r.ok && typeof console !== 'undefined') { console.warn('drishti-views: the view has ' + r.issues.filter((i) => i.level === 'error').length + ' errors; it is drawn as far as it can be', r.issues.slice(0, 10).map(formatIssue)); }
      }
    }
    this.build(vm);
    this.stats.firstPaintMs = now() - t0;
    return this.settle(t0);
  }

  /** @private */
  build(vm) {
    this.clear();
    this.vm = vm;
    this.state.load(vm.state);
    const o = this.options, doc = this.el.ownerDocument, given = o.data && typeof o.data === 'object' ? o.data : {};
    this.datasets = datasetsOf(vm, { library: (n) => this.library(n), data: given, fetch: o.fetch,
      where: { page: (doc.defaultView && doc.defaultView.location && doc.defaultView.location.href) || doc.baseURI || 'http://localhost/', view: this.src || null,
        allow: Array.isArray(given.allow) ? given.allow : [] } });
    const ctx = this.ctx, only = Array.isArray(this.options.panels) ? this.options.panels.map(String) : null;
    const panels = (Array.isArray(vm.panels) ? vm.panels : []).filter((p) => p && typeof p === 'object' && (!only || only.indexOf(String(p.id)) >= 0));
    ctx.label = this.options.label || (vm.title && (vm.title.pill || vm.title.id)) || (vm.ref && vm.ref.id) || '';
    this.screen = new Screen(ctx, vm, this.options.header);
    if (this.linkState) { this.screen.setLink(this.linkState); }
    panels.forEach((p) => { if (!this.frames.has(String(p.id))) { this.frames.set(String(p.id), new PanelFrame(ctx, p)); } });
    this.layout = new Layout(ctx, arrange(Object.assign({}, vm, { panels: panels })), this.frames);
    this.root.setAttribute('aria-labelledby', this.screen.title.id);
    if (this.options.label) { this.root.setAttribute('aria-label', this.options.label); this.root.removeAttribute('aria-labelledby'); }
    this.root.append(this.screen.element, this.layout.element);
    this.screen.back.hidden = !this.history.length;
    this.layout.fit(this.width || 1280);
    this.layout.mounted();
  }

  /** @private */
  card(o) {
    this.clear();
    this.root.appendChild(problemCard(this.ctx, o));
  }

  /** @private */
  settle(t0) {
    this.root.removeAttribute('aria-busy');
    const sheets = Promise.all(this.sheets || []);
    sheets.then(() => this.raf(() => {
      if (this.disposed) { return; }
      if (t0 !== undefined) { this.stats.readyMs = now() - t0; }
      this.emit('ready', {});
      this.resolveReady(this);
    }));
    return this;
  }

  /** @private */
  clear() {
    this.frames.forEach((f) => f.dispose());
    this.frames.clear();
    this.layout = null;
    this.screen = null;
    this.patcher.queue.length = 0;
    this.patcher.frozen = false;
    this.root.classList.remove('dv-frozen');
    while (this.root.firstChild) { this.root.removeChild(this.root.firstChild); }
  }

  /** @private */
  raf(fn) {
    const win = this.el.ownerDocument.defaultView;
    if (win && typeof win.requestAnimationFrame === 'function') { win.requestAnimationFrame(fn); } else { setTimeout(fn, 0); }
  }

  /**
   * The ViewModel as it stands, patches applied.
   * @returns {?Object}
   */
  get viewModel() { return this.vm; }

  /**
   * A new ViewModel (or address) for the same container.
   * @param {*} source
   * @returns {Promise<ViewHandle>}
   */
  update(source) {
    let done;
    this.ready = new Promise((r) => { done = r; });
    this.resolveReady = done;
    this.load(source);
    return this.ready;
  }

  /**
   * Applies a frame of the live stream ({seq, patches}) or a list of patches, in the next animation frame.
   * @param {(Object|Object[])} frame
   * @returns {boolean} false when the frame was dropped (not newer than the last applied)
   */
  patch(frame) { return this.disposed || !this.vm ? false : this.patcher.push(frame); }

  /**
   * One panel: its frame element, the panel as drawn, and its body.
   * @param {string} id
   * @returns {?{element: HTMLElement, body: HTMLElement, panel: Object, state: string}}
   */
  panel(id) {
    const f = this.frames.get(String(id));
    return f ? { element: f.element, body: f.body, panel: f.panel, state: f.element.getAttribute('data-state') } : null;
  }

  /**
   * Listens to the view's events: ready, error, issues, link*, key*, patch, navigate, pick (a row with a pick: {panel, pick, ev}), dispose (* cancelable). Each is also a DOM event
   * elements-view:<name> on the container.
   * @param {string} name
   * @param {function(Object): void} fn receives {type, detail, preventDefault}
   * @returns {function(): void} stops listening
   */
  on(name, fn) {
    if (EVENTS.indexOf(name) < 0) { throw new Error('drishti-views: no event ' + name + '; the events are ' + EVENTS.join(', ')); }
    if (!this.listeners.has(name)) { this.listeners.set(name, new Set()); }
    this.listeners.get(name).add(fn);
    return () => { const s = this.listeners.get(name); if (s) { s.delete(fn); } };
  }

  /** @private */
  emit(name, detail) {
    let prevented = false;
    const ev = { type: name, detail: detail, preventDefault: () => { if (CANCELABLE[name]) { prevented = true; } } };
    (this.listeners.get(name) || []).forEach((fn) => { try { fn(ev); } catch (e) { if (typeof console !== 'undefined') { console.error(e); } } });
    const win = this.el.ownerDocument.defaultView;
    if (win && typeof win.CustomEvent === 'function') {
      const de = new win.CustomEvent('elements-view:' + name, { detail: detail, bubbles: true, composed: true, cancelable: !!CANCELABLE[name] });
      if (!this.el.dispatchEvent(de)) { prevented = true; }
    }
    return !prevented;
  }

  /** @private */
  openLink(link, how) {
    if (!this.emit('link', { link: link })) { return false; }
    if (how && how.follow === false) { return true; }
    const resolve = this.options.links && this.options.links.resolveView;
    if (typeof resolve === 'function') {
      Promise.resolve(resolve(link)).then((next) => {
        if (!next || this.disposed) { return; }
        if (this.vm) { this.history.push(this.vm); }
        this.emit('navigate', { link: link, depth: this.history.length });
        this.load(next);
      }, (e) => this.emit('error', { code: 'view.fetch', error: e, link: link }));
    }
    return true;
  }

  /**
   * Opens a Link the way a click on it does: the cancelable link event, then the host's resolveView, painted in place.
   * @param {Object} link
   * @returns {boolean} false when a listener cancelled it
   */
  open(link) { return this.openLink(link); }

  /**
   * Goes back to the view before the last one opened in place.
   * @returns {boolean} whether there was one
   */
  back() {
    if (!this.history.length) { return false; }
    const vm = this.history.pop();
    this.emit('navigate', { back: true, depth: this.history.length });
    this.show(vm, now());
    return true;
  }

  /** @private */
  pressKey(k) {
    if (!this.emit('key', { key: k.key, action: k.action, panel: k.panel || null, link: k.link || null })) { return; }
    if (k.action === 'panel' && k.panel) {
      const f = this.frames.get(String(k.panel));
      if (f) { f.element.hidden = false; f.element.scrollIntoView({ block: 'nearest' }); f.element.focus({ preventScroll: true }); }
    } else if (k.action === 'link' && k.link) {
      this.openLink(k.link);
    }
  }

  /** @private */
  onKey(e) {
    if (!this.options.keys || !this.vm || !Array.isArray(this.vm.keys) || e.altKey || e.ctrlKey || e.metaKey) { return; }
    const k = this.vm.keys.find((x) => x && x.key === e.key && x.action !== 'denied');
    if (k) { e.preventDefault(); this.pressKey(k); }
  }

  /** @private */
  flash(node) {
    const win = this.el.ownerDocument.defaultView;
    if (!node.animate || (win && win.matchMedia && win.matchMedia('(prefers-reduced-motion: reduce)').matches)) { return; }
    node.animate([{ opacity: 0.35 }, { opacity: 1 }], { duration: 450, easing: 'ease-out' });
  }

  /**
   * The screen's state values now (contract 1.2), id to value, the empty ones left out.
   * @returns {Object<string, *>}
   */
  get stateValues() { return this.state.values(); }

  /**
   * Fires a trigger on a mark of a panel as a click does (a row, a bar, a point, a step, an event, a candle, a figure): the mark's effect
   * list runs in order (set, toggle, clear on the state; notify and host go to the host's hooks), the state goes back to the host's bind, and the
   * panels the changed states' reads name are drawn again from the ViewModel it answers. Triggers run one after the other.
   * @param {string} panel the panel's id
   * @param {string} trigger row-click, bar-click, point-click, submit or cancel (a form panel), or action (a panel's button)
   * @param {number} [mark=0] the mark's place, in the order the specification counts them (an action's place in the panel's list)
   * @param {Object} [values] a form's submitted values (event.<field> in its effects)
   * @param {boolean} [checked=false] the values were checked already (the form drawn by the painter checks them as the reader submits)
   * @returns {Promise<{effects: Object[], problems: Object[], state: Object<string, *>}>} effects as run; problems: RCH-5001 for a panel, trigger or mark the screen does not have (nothing runs)
   */
  fire(panel, trigger, mark, values, checked) {
    const job = this.firing.then(() => this.runFire(panel, trigger, mark, values, checked));
    this.firing = job.catch(() => null);
    return job;
  }

  /**
   * A control's change (contract 1.2): the control takes the value and its change effects run in order with that value as the event, the
   * state and the control go back to the host's bind, and the panels that read them are drawn again. A control the screen lacks is refused
   * (RCH-5001; nothing runs). The host draws the controls (the painter draws panels): it calls this when the reader picks a value.
   * @param {string} control the control's id
   * @param {*} value the new value (a list for a multi-select)
   * @returns {Promise<{effects: Object[], problems: Object[], state: Object<string, *>}>}
   */
  change(control, value) {
    const job = this.firing.then(() => this.runFire(null, 'change', 0, undefined, false, control, value));
    this.firing = job.catch(() => null);
    return job;
  }

  /**
   * The problems the host's check hook answers for a form's values (the form's rules, required fields and limits: evaluated by the Rachana
   * runtime, never here), as RCH-5003 problems; the values naming a field the form lacks are refused first (RCH-5001). No hook: none.
   * @private
   * @param {string} id the form panel's id
   * @param {Object} values
   * @returns {Promise<Object[]>}
   */
  async checkForm(id, values) {
    const frame = this.frames.get(String(id)), found = resolveWire(frame ? frame.panel : null, String(id), 'submit', { values: values });
    if (found.problems.length) { this.emit('refused', { panel: String(id), problems: found.problems }); return found.problems; }
    const check = this.options.host && this.options.host.check;
    if (typeof check !== 'function') { return []; }
    let got;
    try { got = await check({ panel: String(id), form: frame.panel.form, values: values }); } catch (x) { this.emit('error', { code: 'view.check', error: x }); return []; }
    if (got && !Array.isArray(got) && Array.isArray(got.problems)) { got = got.problems; }
    const problems = (Array.isArray(got) ? got : []).map((p) => (typeof p === 'string' ? { message: p } : p)).filter((p) => p && typeof p.message === 'string')
      .map((p) => ({ code: typeof p.code === 'string' ? p.code : 'RCH-5003', message: p.message, severity: p.severity || 'error' }));
    if (problems.length) { this.emit('refused', { panel: String(id), problems: problems }); }
    return problems;
  }

  /** @private */
  async runFire(id, trigger, mark, values, checked, control, value) {
    const hostHooks = this.options.host || {};
    let found, where;
    if (trigger === 'change') {
      const ctl = (Array.isArray(this.vm && this.vm.controls) ? this.vm.controls : []).find((c) => c && String(c.id) === String(control));
      found = resolveWire(null, String(control), 'change', { control: ctl || null, value: value });
      where = { control: String(control), trigger: 'change', value: value };
      id = control;
    } else if (trigger === 'submit' || trigger === 'cancel' || trigger === 'action') {
      const frame = this.frames.get(String(id)), panel = frame ? frame.panel : null;
      found = resolveWire(panel, String(id), trigger, { mark: mark, values: values });
      where = { panel: String(id), trigger: trigger, mark: mark || 0 };
      if (!found.problems.length && trigger === 'submit' && !checked) { found = { problems: await this.checkForm(id, values || {}), effects: found.effects, event: found.event }; }
    } else {
      const frame = this.frames.get(String(id));
      found = resolveFire(frame ? frame.panel : null, String(id), trigger, mark);
      where = { panel: String(id), trigger: trigger, mark: mark || 0 };
    }
    if (found.problems.length) {
      return { effects: [], problems: found.problems, state: this.state.values() };
    }
    const done = trigger === 'change' || trigger === 'submit' || trigger === 'cancel' ? this.state.run(found.effects, found.event) : trigger === 'action' ? this.state.run(found.effects, null) : this.state.run(found.effects);
    done.ran.forEach((e) => {
      const hook = e.do === 'notify' ? hostHooks.notify : e.do === 'host' ? hostHooks.run : null;
      if (e.do === 'notify' || e.do === 'host') {
        this.emit(e.do, Object.assign({}, e, where));
        if (typeof hook === 'function') { try { hook(e, where); } catch (x) { this.emit('error', { code: 'view.host', error: x }); } }
      }
    });
    const ctlChanged = trigger === 'change' ? [{ id: String(control), value: value }] : [];
    if (done.changed.length || ctlChanged.length) {
      if (done.changed.length) { this.emit('state-change', { changed: done.changed, values: this.state.values(), panel: where.panel || null, trigger: trigger, mark: where.mark }); }
      await this.rebind(done.changed, ctlChanged);
    }
    return { effects: done.ran, problems: [], state: this.state.values() };
  }

  /** @private sends the state back to the host and draws what the changed states' reads name */
  async rebind(changed, controls) {
    const bind = this.options.host && this.options.host.bind;
    if (typeof bind !== 'function') { return; }
    controls = controls || [];
    let readers = [];           // the panels the changed states and controls read (null: one of them does not say, so the whole view)
    const add = (names) => { if (readers === null) { return; } if (!Array.isArray(names)) { readers = null; return; } names.forEach((r) => { if (readers.indexOf(r) < 0) { readers.push(r); } }); };
    if (changed.length) { add(this.state.readers(changed)); }
    controls.forEach((c) => { const v = (Array.isArray(this.vm && this.vm.controls) ? this.vm.controls : []).find((x) => x && String(x.id) === c.id); add(v ? v.reads : null); });
    const state = this.state.request(), params = {}, ctl = {};
    changed.forEach((id) => { if (this.state.get(id) === null) { state[id] = null; } });
    Object.keys(state).forEach((id) => { if (state[id] !== null) { params['state.' + id] = state[id]; } });
    controls.forEach((c) => { const t = requestText(c.value); ctl[c.id] = t; if (t !== null) { params['ctl.' + c.id] = t; } });
    const req = { state: state, params: params, changed: changed.slice() };
    if (controls.length) { req.controls = ctl; }
    let next;
    try { next = await bind(req); } catch (e) { this.emit('error', { code: 'view.bind', error: e }); return; }
    if (this.disposed || !isView(next)) { return; }
    if (this.options.complete) { next = complete(JSON.parse(JSON.stringify(next)), 'ViewModel'); }
    this.adopt(next, readers);
  }

  /** @private the ViewModel a bind answered: the named panels (and the strip) drawn again, else the whole view */
  adopt(next, readers) {
    const vm = this.vm, ids = (v) => (Array.isArray(v.panels) ? v.panels.map((p) => String(p && p.id)) : []);
    const same = vm && ids(vm).join('\u0001') === ids(next).join('\u0001');
    const partial = same && readers && readers.every((r) => r === 'strip' || this.frames.has(String(r))) && readers.indexOf('title') < 0 && !Array.isArray(this.options.panels);
    if (!partial) { this.show(next, now()); this.emit('bound', { panels: ids(next), full: true }); return; }
    const drawn = [];
    readers.forEach((r) => {
      if (r === 'strip') {
        (Array.isArray(next.strip) ? next.strip : []).forEach((cell, i) => { if (this.screen && this.screen.setCell(i, cell) && Array.isArray(vm.strip)) { vm.strip[i] = cell; } });
        return;
      }
      const i = next.panels.findIndex((x) => x && String(x.id) === String(r)), frame = this.frames.get(String(r));
      if (i >= 0 && frame) { frame.draw(next.panels[i]); vm.panels[vm.panels.findIndex((x) => x && String(x.id) === String(r))] = next.panels[i]; drawn.push(String(r)); }
    });
    vm.state = next.state;
    if (next.controls !== undefined) { vm.controls = next.controls; }
    this.state.load(next.state);
    this.emit('bound', { panels: drawn, full: false });
  }

  /**
   * Paint times and counts: firstPaintMs, readyMs, paints, patches applied, ignored and dropped, lastApplyMs.
   * @returns {Object<string, number>}
   */
  metrics() { return Object.assign({}, this.stats); }

  /** @private the stylesheet of a pack (drishti-elements.css) the page links, or null */
  packSheet(doc) {
    const l = doc.querySelector('link[rel~="stylesheet"][href$="/drishti-elements.css"], link[rel~="stylesheet"][href="drishti-elements.css"]');
    return l && l.href ? l : null;
  }

  /**
   * Adopts a library's sheets (and those of what it requires, as the manifest lists them) into the view's shadow root once; without a
   * shadow root they go to the page. A pack's one stylesheet, when the page links it, is what the shadow root takes.
   * @private
   * @param {string} name
   * @returns {Promise<void>}
   */
  styles(name) {
    const libs = manifest().libraries, css = (libs[name] && libs[name].css) || [];
    if (this.hostSheets()) { return Promise.resolve(); }                  // the host's sheets hold every rule
    if (this.host === this.el) { css.forEach((c) => loadStyle(staticUrl(c))); return Promise.resolve(); }
    this.styled = this.styled || new Set();
    const doc = this.el.ownerDocument;
    const pack = this.packSheet(doc);
    if (pack) {                                          // a pack's one stylesheet (the page links it): the shadow root takes it once, in place of one file per library
      if (!this.styled.has(pack.href)) {
        this.styled.add(pack.href);
        const l = doc.createElement('link');
        l.rel = 'stylesheet';
        l.href = pack.href;
        const p = new Promise((r) => { l.addEventListener('load', r, { once: true }); l.addEventListener('error', r, { once: true }); });
        this.host.insertBefore(l, this.root);
        return p;
      }
      return Promise.resolve();
    }
    return Promise.all(css.filter((c) => !this.styled.has(c) && !/drishti_tokens\.css$/.test(c)).map((c) => {
      this.styled.add(c);
      const l = doc.createElement('link');
      l.rel = 'stylesheet';
      l.href = staticUrl(c);
      const p = new Promise((r) => { l.addEventListener('load', r, { once: true }); l.addEventListener('error', r, { once: true }); });
      this.host.insertBefore(l, this.root);
      return p;
    })).then(() => undefined);
  }

  /**
   * A dataset of the painted view (contract 1.1): its fields, its rows (held, with rows patches applied) or its source.
   * @param {string} name
   * @returns {?import('./drishti_views-data.js').Dataset}
   */
  dataset(name) { return this.datasets ? this.datasets.get(String(name)) || null : null; }

  /**
   * A library a kind draws with: the host's own (options.libraries), one already on the page, or loaded now by drishti-core's loader.
   * @private
   * @param {string} name
   * @returns {Promise<Object>}
   */
  library(name) {
    const given = this.options.libraries && this.options.libraries[name];
    if (given) { return Promise.resolve(given); }
    const have = loaded(name);
    return have ? Promise.resolve(have) : load(name);
  }

  /**
   * The view's panel charts (drishti-chart's PanelCharts over this view's root), made the first time a chart is drawn.
   * @private
   * @returns {Promise<Object>}
   */
  charts() {
    if (!this.chartsP) {
      const win = this.el.ownerDocument.defaultView, o = this.options;
      this.chartsP = this.library('drishti-chart').then((C) => {
        if (this.disposed) { throw new Error('disposed'); }
        this.panelCharts = new C.PanelCharts(this.host, { engine: () => this.engine(),
          tokensFrom: () => this.root, onLink: (l) => this.openLink(l),
          onPoint: (m, el) => { const pn = el.closest('[data-panel]'); this.emit('point', Object.assign({ panel: pn ? pn.getAttribute('data-panel') : null }, m)); }, geo: typeof o.geo === 'function' ? o.geo : () => this.geoHelper(), gl: o.gl, graph: () => this.library('drishti-graph') });
        return this.panelCharts;
      });
    }
    return this.chartsP;
  }

  /**
   * The chart engine (ECharts): the page's own, else loaded the host's way (options.engine), else the vendored copy drishti-chart names.
   * @private
   * @returns {Promise<Object>}
   */
  engine() {
    const win = this.el.ownerDocument.defaultView, o = this.options;
    if (win.echarts) { return Promise.resolve(win.echarts); }
    if (!this.engineP) {
      const lib = manifest().libraries['drishti-chart'] || {};
      const go = typeof o.engine === 'function' ? Promise.resolve(o.engine()) : (lib.scripts || []).reduce((p, src) => p.then(() => loadScript(staticUrl(src))), Promise.resolve());
      this.engineP = go.then(() => { if (!win.echarts) { throw new Error('no chart engine'); } return win.echarts; });
      this.engineP.catch(() => { this.engineP = null; });
    }
    return this.engineP;
  }

  /**
   * Opens a table's rows as a chart in a dialog inside the view (tableTools.chart): drishti-chart's openTableChart, its engine and sheets.
   * @private
   * @param {{title: string, columns: string[], rows: Array<Array<*>>}} table
   * @param {HTMLElement} opener gets the focus back
   * @returns {Promise<Object>} the TableChart
   */
  chartTable(table, opener) {
    return Promise.all([this.library('drishti-chart'), this.engine(), this.styles('drishti-chart')]).then(([C]) => {
      if (this.disposed) { throw new Error('disposed'); }
      const t = new C.TableChart(this.root, table, { opener: opener, chart: { density: this.options.density || 'compact' } });
      this.emit('chart-table', { title: table.title || '', rows: table.rows.length });
      return t.open();
    }).catch((e) => { this.emit('error', { code: 'view.chart', error: e }); throw e; });
  }

  /**
   * The default map helper: drishti-chart's geo part (given by the host as libraries['drishti-chart:geo'], else loaded), over the maps folder.
   * @private
   * @returns {Promise<Object>}
   */
  geoHelper() {
    const o = this.options, given = o.libraries && o.libraries['drishti-chart:geo'], win = this.el.ownerDocument.defaultView;
    const part = given ? Promise.resolve(given) : (loaded('drishti-chart:geo') ? Promise.resolve(loaded('drishti-chart:geo')) : loadPart('drishti-chart', 'geo'));
    return part.then((P) => P.geoHelper({ base: o.mapsBase || staticUrl('vendor/echarts/maps/'), engine: () => win.echarts }));
  }

  /**
   * The live stream's connection on the view: a chip in the status line and the view marked stale while it is down.
   * @private
   * @param {?string} down the state's words' name (linkConnecting, linkReconnecting, linkOffline, linkRefused, linkClosed), or null when open
   * @returns {void}
   */
  setLink(down) {
    this.linkState = down;
    this.root.classList.toggle('dv-link-down', !!down);
    if (this.screen) { this.screen.setLink(down); }
  }

  /**
   * Stops everything, disposes every panel's drawing and empties the container.
   * @returns {void}
   */
  dispose() {
    if (this.disposed) { return; }
    this.clear();
    if (this.panelCharts) { this.panelCharts.dispose(); }
    if (this.ro) { this.ro.disconnect(); }
    this.patcher.stop();
    if (this.liveLink) { this.liveLink.stop(); }
    this.disposed = true;
    this.emit('dispose', {});
    this.listeners.clear();
    if (this.host !== this.el) { while (this.host.firstChild) { this.host.removeChild(this.host.firstChild); } } else if (this.root.parentNode) { this.root.remove(); }
    if (HANDLES.get(this.el) === this) { HANDLES.delete(this.el); }
  }
}

/* the host's constructable sheets adopted by a root (a ShadowRoot or a Document), each once, after the ones it has */
function adoptSheets(root, sheets) {
  if (!root || !('adoptedStyleSheets' in root)) { return; }
  const have = root.adoptedStyleSheets;
  root.adoptedStyleSheets = have.concat(sheets.filter((x) => x && have.indexOf(x) < 0));
}

function sheetPaths() {
  const libs = manifest().libraries;
  const out = [];
  ['drishti-core', 'drishti-views'].forEach((n) => ((libs[n] && libs[n].css) || []).forEach((c) => { if (out.indexOf(c) < 0 && !/drishti_tokens\.css$/.test(c)) { out.push(c); } }));
  return out;
}

/**
 * Paints a view into an element (replacing what paint drew there before) and answers its handle.
 * @param {HTMLElement} el the container
 * @param {*} source a ViewModel, its address, a Response, or a promise of one
 * @param {PaintOptions} [options]
 * @returns {ViewHandle}
 * @example const view = paint(document.getElementById('orders'), '/views/orders.json'); view.ready.then(() => view.patch(frame));
 */
export function paint(el, source, options) {
  if (!el || typeof el.appendChild !== 'function') { throw new Error('paint: the first argument is the element to draw into'); }
  const old = HANDLES.get(el);
  if (old) { old.dispose(); }
  const handle = new ViewHandle(el, options);
  HANDLES.set(el, handle);
  return handle.load(source);
}
