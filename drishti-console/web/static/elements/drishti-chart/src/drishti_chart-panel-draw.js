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
/* Draws a rendered view's panel charts (moved here from the client renderer's m/draw.js and m/charts.js drawMap): it finds the chart
   containers a host made (data-r-chart, the panel's kind and data on el.__drishti), draws each with the engine the host loads, keeps
   them sized and themed, hands a click on a point, a node or a place that names an entity to the host, switches a surface between its
   heatmap and 3D, and draws a map from the console's shapes (drishti_geomap.js, injected). One chart that cannot be drawn says so in
   words and leaves the others. Every engine call of a panel chart is in this module. */
import { panelOption, panelTokens, panelCompact } from './drishti_chart-panels.js';

const TOKEN = /\{(iso3|iso2|code)\}/;
const upper = (v) => String(v == null ? '' : v).trim().toUpperCase();
const panelOf = (el) => { const p = el.closest('.pnl'); return p ? p.getAttribute('data-panel') : null; };

/**
 * Draws a map panel from the console's shapes; resolves with its clickable places and its note.
 * @param {Object} E the engine (echarts)
 * @param {Object} G the console's map helper (drishti_geomap.js: world, region, find, option, codeOf)
 * @param {HTMLElement} el the chart's container
 * @param {Object} d the map panel's data (mode, region, highlight, shade, link, unit)
 * @param {Object<string, string>} t panelTokens of the view
 * @param {function(Object): void} onLink a clicked place that names an entity ({kind, id})
 * @param {Object[]} chartsOut the engine instances drawn (the map's is added)
 * @returns {Promise<?{places: Array<{name: string, link: Object, text: string}>, note: string}>} null when a later draw replaced this one
 */
export function drawMap(E, G, el, d, t, onLink, chartsOut) {
  const isRegion = d.mode === 'region', tick = el.__tick = (el.__tick || 0) + 1;
  return G.world().then((w) => {
    const c = isRegion ? G.find(w, d.region) : null;
    if (isRegion && !c) { throw new Error('unknown country'); }
    return (isRegion ? G.region(c.iso3) : Promise.resolve(w)).then((geo) => ({ w, geo }));
  }).then(({ w, geo }) => {
    if (tick !== el.__tick) { return null; }
    const link = d.link, shade = d.shade, notes = [];
    const codeFor = (raw) => { if (isRegion) { return upper(raw); } const f = G.find(w, raw); return f ? f.iso3 : null; };
    const items = ((shade && shade.items) || []).map((i) => { const k = codeFor(i.code); return k ? { code: k, value: i.value, text: i.text } : null; }).filter(Boolean);
    const highlight = d.highlight == null ? [] : [codeFor(d.highlight)].filter(Boolean);
    if (d.highlight != null && !highlight.length) { notes.push('Not on the map: ' + d.highlight); }
    if (shade && shade.note) { notes.push('Not shaded: ' + shade.note); }
    if (items.length) { notes.push('Shaded by ' + ((shade && shade.label) || 'value') + (d.unit ? ' (' + d.unit + ')' : '')); }
    let clickable = null, make = null;
    if (link) {
      const tk = TOKEN.exec(link.template);
      clickable = (link.codes || []).map((x) => (tk && tk[1] === 'iso2' ? codeFor(x) : upper(x))).filter(Boolean);
      make = (place) => {
        const rec = isRegion ? { code: place, iso2: null, iso3: geo.iso3 } : G.find(w, place);
        const v = tk[1] === 'code' ? place : tk[1] === 'iso2' ? rec && rec.iso2 : rec && rec.iso3;
        return v ? { kind: link.kind, id: link.template.replace(TOKEN, v) } : null;
      };
      if (link.note) { notes.push('Nothing to open: ' + link.note); }
    }
    const chart = E.getInstanceByDom(el) || E.init(el, null, { renderer: 'svg' });
    chart.setOption(G.option({ level: isRegion ? 'region' : 'world', geo, t, items, highlight, clickable, linked: !!(link && !link.note),
      legend: shade && shade.label, unit: d.unit || (shade && shade.unit) || '', numText: panelCompact }), true);
    if (!chartsOut.includes(chart)) { chartsOut.push(chart); }
    el.__places = { make, clickable };
    if (!el.__clicks) {
      el.__clicks = true;
      chart.on('click', (p) => { const code = G.codeOf(p), pl = el.__places; if (code && pl && pl.make && (!pl.clickable || pl.clickable.includes(code))) { const l = pl.make(code); if (l) { onLink(l); } } });
    }
    const places = [];
    if (make) {
      const names = (code) => { if (isRegion) { const r = geo.by.code[code]; return r ? r.name : code; } const f = w.by.iso3[code]; return f ? f.name : code; };
      const byCode = {};
      items.forEach((i) => { byCode[i.code] = i; });
      clickable.forEach((code) => { const l = make(code); if (l) { places.push({ name: names(code), link: l, text: byCode[code] ? byCode[code].text : '' }); } });
      places.sort((a, b) => a.name.localeCompare(b.name));
    }
    return { places, note: notes.join(' · ') };
  });
}

/**
 * @typedef {Object} PanelChartsOptions
 * @property {function(): Promise<Object>} engine loads the engine (ECharts) and resolves with it (the host's loader: its own policy for scripts)
 * @property {function(): Promise<void>} [gl] loads ECharts GL (the surface in 3D); without it a 3D surface is drawn as its heatmap
 * @property {function(): Promise<Object>} [geo] the console's map helper (drishti_geomap.js); without it a map says it did not load
 * @property {function(Object, HTMLElement): void} [onLink] a point, a node or a place that names an entity was clicked ({kind, id}, the container)
 * @property {function(HTMLElement, Array<{name: string, link: Object, text: string}>, string): void} [onPlaces] a map's clickable places and its note, for the host to list
 * @property {string} [selector='[data-r-chart]'] the chart containers
 * @property {function(HTMLElement): ?{kind: string, data: Object}} [specOf] a container's kind and data (default el.__drishti)
 * @property {function(): Element} [tokensFrom] the element the colour tokens are read from (default the root's .view, its host, or the page)
 * @property {string} [attr='data-r-chart'] the attribute that marks a container still to draw (taken off one that cannot be drawn)
 * @property {boolean} [relayout=false] a change of size draws again (the waterfall's labels are fitted to the width), not only resizes
 */

/**
 * The panel charts of one rendered view (a shadow root or an element): draw, redraw after a theme change, dispose.
 */
export class PanelCharts {
  /**
   * Whether a chart container is drawn, still animating, or not drawn yet (a page's settled signal asks; the engine is the page's).
   * @param {HTMLElement} el
   * @returns {('drawn'|'animating'|'none')}
   */
  static state(el) {
    const E = el.ownerDocument.defaultView.echarts, c = E && E.getInstanceByDom(el);
    if (!c || c.isDisposed()) { return 'none'; }
    return c.getZr().animation.isFinished() ? 'drawn' : 'animating';
  }

  /**
   * @param {(ShadowRoot|Element)} root where the view is drawn
   * @param {PanelChartsOptions} o
   */
  constructor(root, o) {
    this.root = root;
    this.o = Object.assign({ selector: '[data-r-chart]', attr: 'data-r-chart', specOf: (el) => el.__drishti || null }, o);
    this.all = [];
    this.ro = null;
    this.onClick = (e) => this.surfaceButton(e);
    root.addEventListener('click', this.onClick);
  }

  /** @private the window of the view */
  win() { const d = this.root.ownerDocument || this.root; return (d.defaultView || window); }

  /** @private the engine instances still drawn */
  live() { this.all = this.all.filter((c) => !c.isDisposed()); return this.all; }

  /** @private a chart that cannot be drawn: words instead */
  broken(E, el, words) {
    const c = E && E.getInstanceByDom(el);
    if (c) { c.dispose(); }
    el.removeAttribute(this.o.attr);
    el.className = 'pnl-empty';
    el.textContent = words || 'No data available';
  }

  /** @private the tokens of the view */
  tokens() {
    const r = this.root, el = (this.o.tokensFrom && this.o.tokensFrom()) || r.querySelector('.view') || r.host || this.win().document.documentElement;
    return panelTokens(el);
  }

  /** @private */
  async drawOne(E, el, t) {
    const spec = this.o.specOf(el);
    if (!spec) { return; }
    const { kind, data } = spec;
    if (kind === 'map') {
      try {
        if (!this.o.geo) { throw new Error('no map helper'); }
        const G = await this.o.geo();
        const got = await drawMap(E, G, el, data || {}, t, (l) => this.link(l, el), this.all);
        if (got && this.o.onPlaces) { this.o.onPlaces(el, got.places, got.note); }
      } catch (e) { this.broken(E, el, data && data.mode === 'region' ? 'No regions available for this country' : 'The map did not load'); }
      return;
    }
    if (kind === 'surface') { await this.surface(E, el, t, el.getAttribute('data-view') || 'heatmap'); return; }
    try {
      const c = E.getInstanceByDom(el) || E.init(el, null, { renderer: 'svg' });
      c.setOption(panelOption(kind, data || {}, t, { width: el.clientWidth || 800, height: el.clientHeight || 360 }), true);
      if (!this.all.includes(c)) { this.all.push(c); }
      if (!el.__clicks) {
        el.__clicks = true;
        c.on('click', (p) => {
          const link = p.data && ((p.data.point && p.data.point.link) || (p.data.node && p.data.node.link));
          if (link && link.kind && link.id) { this.link(link, el); }
        });
      }
    } catch (e) { this.broken(E, el); }
  }

  /** @private an entity named by a mark */
  link(l, el) { if (this.o.onLink) { this.o.onLink(l, el); } }

  /** @private a surface in its view: the heatmap, or 3D with ECharts GL (the heatmap when GL or WebGL fails) */
  async surface(E, el, t, view) {
    const d = (this.o.specOf(el) || {}).data || {};
    const draw = (mode) => {
      const old = E.getInstanceByDom(el); if (old) { old.dispose(); }
      const c = E.init(el, null, { renderer: mode === '3d' ? 'canvas' : 'svg' });
      c.setOption(panelOption('surface', d, t, null, mode), true);
      this.all.push(c);
    };
    let mode = view;
    if (view === '3d') { try { if (!this.o.gl) { throw new Error('no GL'); } await this.o.gl(); } catch (e) { mode = 'heatmap'; } }
    try { draw(mode); } catch (e) {
      el.setAttribute('data-error', String((e && e.message) || e));
      try { draw('heatmap'); } catch (e2) { this.broken(E, el); }
    }
  }

  /**
   * Draws every chart container in a scope (the whole view by default, or one panel a live frame replaced).
   * @param {(ShadowRoot|Element)} [scope]
   * @returns {Promise<void>}
   */
  async draw(scope) {
    const els = Array.from((scope || this.root).querySelectorAll(this.o.selector));
    if (!els.length) { return; }
    let E;
    try { E = await this.o.engine(); } catch (e) { els.forEach((el) => this.broken(null, el, 'Charts are not available')); return; }
    const t = this.tokens();
    await Promise.all(els.map((el) => this.drawOne(E, el, t)));
    const win = this.win();
    if (!this.ro && win.ResizeObserver) {
      let first = true;                                   // an observer reports the size it starts with: not a change
      this.ro = new win.ResizeObserver(() => {
        if (first) { first = false; return; }
        win.requestAnimationFrame(() => { this.live().forEach((c) => c.resize()); if (this.o.relayout) { this.draw(this.root); } });
      });
      this.ro.observe(this.root.host || this.root.firstElementChild || win.document.body);
    }
  }

  /**
   * Draws every chart again (a theme change: the colours are read anew).
   * @returns {Promise<void>}
   */
  redraw() { return this.draw(this.root); }

  /**
   * Moves a drawn chart to its container's new data without drawing it anew (a live panel's next point): the option is merged.
   * @param {HTMLElement} el a chart container of this view
   * @returns {Promise<void>}
   */
  async update(el) {
    const spec = this.o.specOf(el);
    if (!spec || spec.kind === 'map' || spec.kind === 'surface') { return this.draw(el.parentNode || this.root); }
    const E = await this.o.engine(), c = E.getInstanceByDom(el);
    if (!c) { return this.draw(el.parentNode || this.root); }
    c.setOption(panelOption(spec.kind, spec.data || {}, this.tokens(), { width: el.clientWidth || 800, height: el.clientHeight || 360 }), false);
  }

  /**
   * The engine instances drawn now (tests read their options).
   * @returns {Object[]}
   */
  instances() { return this.live().slice(); }

  /** @private the surface's Heatmap | 3D buttons */
  surfaceButton(e) {
    const b = e.target.closest && e.target.closest('[data-surface-view]');
    if (!b) { return; }
    const box = b.closest('.pnl-b'), el = box && box.querySelector('.surface[' + this.o.attr + ']');
    if (!el) { return; }
    box.querySelectorAll('[data-surface-view]').forEach((x) => x.setAttribute('aria-pressed', x === b ? 'true' : 'false'));
    el.setAttribute('data-view', b.getAttribute('data-surface-view'));
    this.o.engine().then((E) => this.surface(E, el, panelTokens(el), b.getAttribute('data-surface-view')));
  }

  /**
   * Stops listening and disposes of every chart.
   * @returns {void}
   */
  dispose() {
    this.root.removeEventListener('click', this.onClick);
    if (this.ro) { this.ro.disconnect(); this.ro = null; }
    this.live().forEach((c) => c.dispose());
    this.all = [];
  }
}
