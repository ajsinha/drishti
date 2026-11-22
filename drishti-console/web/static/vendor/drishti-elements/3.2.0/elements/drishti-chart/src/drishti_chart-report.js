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
/* drishti-chart's report part (drishti_chart-report.js, a page that draws report visuals loads it beside the bundle; loadPart('drishti-chart',
   'report') loads it on demand): the charts of a report page drawn from the server's answers, and the host's other option charts.

     const R = Drishti.elements.chart_report;
     R.drawReport(host, visual, answer, env)    a report visual's chart (reportOption), or its note; {dispose, resize}
     R.reportMap(host, visual, answer, env)     the map visual: countries, and a country's regions on a click (env.geo: the geo part's helper)
     R.sparkline(el, categories, values, colour) the line under a card's figure
     R.drawOption(host, option, o)              an option the server gave (a Python visual's, sanitised by pythonOption)
     R.tableChartOption(model, settings, t, o)  the option of "Chart any table"'s drawn model
     new R.OptionChart(el, {renderer})          one engine instance on an element: draw, resize, tip, SVG, PNG, dispose

   A report chart is driven from the keyboard (focus it; Left and Right move between marks, Enter opens the mark's menu, the same one a click
   opens), redrawn in place on a live update (no new instance, the keyboard cursor stays), and brushed (a drag on a line, area, histogram
   or scatter selects a range) when the host takes the selection. The host gives the class names of the elements it styles (env.classes). */
import { rangeBrush } from './drishti_index.js';
import { reportOption, reportBrush, reportBase, reportTable, sparklineOption, REPORT_NO_DATA } from './drishti_chart-report-kinds.js';

export { REPORT_CHART_KINDS, REPORT_NO_DATA, reportTable, reportSeries, reportMarks, reportOption, reportBase, reportAxis, reportBrush, sparklineOption,
  valueAxisFormat, categoryAxisFormat, labelEvery, labelPosition, percentOf, seriesColour } from './drishti_chart-report-kinds.js';

const CLASSES = { chart: 'dc-report', empty: 'dc-report-empty', live: 'dc-sr-only', crumb: 'dc-map-crumb', up: 'dc-map-up', here: 'dc-map-here', note: 'dc-map-note' };
const engineOf = (o) => (o && o.echarts) || (typeof window !== 'undefined' ? window.echarts : null);
const clsOf = (env, k) => (env && env.classes && env.classes[k]) || CLASSES[k];
const NONE = { dispose() {}, resize() {} };

function make(doc, tag, cls, attrs, text) {
  const e = doc.createElement(tag);
  if (cls) { e.className = cls; }
  Object.keys(attrs || {}).forEach((k) => { if (attrs[k] !== undefined && attrs[k] !== null) { e.setAttribute(k, attrs[k]); } });
  if (text !== undefined) { e.textContent = text; }
  return e;
}
function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } return el; }

/**
 * A visual's note in its body (nothing to draw, a mask, the engine missing).
 * @param {HTMLElement} host
 * @param {string} text
 * @param {Object} [env]
 * @returns {{dispose: function(): void, resize: function(): void}}
 */
export function reportNote(host, text, env) {
  clear(host).appendChild(make(host.ownerDocument, 'p', clsOf(env, 'empty'), { role: 'status' }, text));
  return NONE;
}

/**
 * One engine instance on an element (the engine is the vendored ECharts): what a host that draws its own options keeps.
 */
export class OptionChart {
  /**
   * @param {HTMLElement} el
   * @param {{renderer: ('canvas'|'svg'), width: number, height: number, echarts: Object}} [o]
   */
  constructor(el, o) {
    this.el = el;
    this.o = o || {};
    this.E = engineOf(this.o);
    if (!this.E) { throw new Error('drishti-chart needs ECharts (vendor/echarts/echarts.min.js): load it first'); }
    const size = this.o.width ? { width: this.o.width, height: this.o.height } : {};
    this.engine = this.E.init(el, null, Object.assign({ renderer: this.o.renderer || 'canvas' }, size));
  }
  /** @returns {boolean} whether the instance is gone */
  get disposed() { return !this.engine || this.engine.isDisposed(); }
  /**
   * Draws an option (replacing what was drawn unless merge is said).
   * @param {Object} option
   * @param {boolean} [merge]
   * @returns {OptionChart}
   */
  draw(option, merge) { this.engine.setOption(option, !merge); return this; }
  /**
   * Fits the drawing to its element again.
   * @returns {void}
   */
  resize() { if (!this.disposed) { this.engine.resize(); } }
  /**
   * Ends the instance.
   * @returns {void}
   */
  dispose() { if (!this.disposed) { this.engine.dispose(); } }
  /**
   * Listens to the engine's events (click, mouseover, mouseout, globalout).
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {void}
   */
  on(name, fn) { this.engine.on(name, fn); }
  /**
   * Shows the tip of a mark (the keyboard's cursor).
   * @param {number} seriesIndex
   * @param {number} dataIndex
   * @returns {void}
   */
  tip(seriesIndex, dataIndex) { this.engine.dispatchAction({ type: 'showTip', seriesIndex, dataIndex }); }
  /**
   * Highlights a mark, or takes the highlight off.
   * @param {number} dataIndex
   * @param {boolean} on
   * @returns {void}
   */
  highlight(dataIndex, on) { this.engine.dispatchAction({ type: on ? 'highlight' : 'downplay', seriesIndex: 0, dataIndex }); }
  /**
   * The drawing as SVG text on a background (an SVG renderer's instance).
   * @param {string} background
   * @returns {string}
   */
  svg(background) {
    this.engine.setOption({ backgroundColor: background });
    const text = this.engine.renderToSVGString ? this.engine.renderToSVGString() : decodeURIComponent(this.engine.getDataURL({ type: 'svg' }).split(',')[1]);
    this.engine.setOption({ backgroundColor: 'transparent' });
    return text;
  }
  /**
   * An option drawn off screen as a PNG data URL (twice the pixels).
   * @param {Document} doc
   * @param {Object} option
   * @param {{width: number, height: number, background: string, echarts: Object}} o
   * @returns {string}
   */
  static png(doc, option, o) {
    const box = make(doc, 'div', o.cls || 'dc-offscreen');
    doc.body.appendChild(box);
    const c = new OptionChart(box, { renderer: 'canvas', width: o.width, height: o.height, echarts: o.echarts });
    c.draw(option);
    const url = c.engine.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: o.background });
    c.dispose();
    box.remove();
    return url;
  }
}

/**
 * Draws an option in a new box in the host: the box's class, words and attributes; data-ready once drawn.
 * @param {HTMLElement} host
 * @param {Object} option
 * @param {{cls: string, label: string, attrs: Object<string, string>, echarts: Object}} [o]
 * @returns {{dispose: function(): void, resize: function(): void}}
 */
export function drawOption(host, option, o) {
  o = o || {};
  if (!engineOf(o)) { return reportNote(host, 'The chart library did not load.', o); }
  clear(host);
  const box = make(host.ownerDocument, 'div', o.cls || CLASSES.chart, Object.assign({ role: 'img', 'aria-label': o.label || 'Chart' }, o.attrs || {}));
  host.appendChild(box);
  const c = new OptionChart(box, { echarts: o.echarts }).draw(option);
  box.setAttribute('data-ready', '1');
  return { dispose() { c.dispose(); }, resize() { c.resize(); } };
}

/* ---- a report visual -------------------------------------------------------------------------------------------------------- */

/**
 * Mounts a built report chart in the host and wires click, hover, the keyboard and the brush. A live redraw (env.update) of the same
 * visual replaces the option in place.
 * @param {HTMLElement} host
 * @param {{option: Object, marks: Object, alt: string}} built
 * @param {Object} spec the visual
 * @param {Object} env the host's adapter (ReportEnv, with interactive, onMark, onHover, onHoverEnd, onBrush, classes)
 * @returns {{dispose: function(): void, resize: function(): void}}
 */
export function mountReport(host, built, spec, env) {
  if (!engineOf(env)) { return reportNote(host, 'The chart library did not load.', env); }
  const doc = host.ownerDocument, option = built.option, marks = built.marks;
  const sig = spec.type + ':' + spec.id;
  const prev = env.update ? Array.prototype.find.call(host.children, (c) => c.classList.contains(clsOf(env, 'chart').split(' ')[0])) : null;
  if (prev && prev.__chart && !prev.__chart.disposed && prev.__sig === sig) {
    prev.__state.marks = marks;
    prev.__chart.draw(option);
    if (built.alt) { prev.setAttribute('aria-label', built.alt); }
    return prev.__handle;
  }
  clear(host);
  const box = make(doc, 'div', clsOf(env, 'chart'), { tabindex: env.interactive ? '0' : '-1', role: 'img', 'aria-label': built.alt || spec.alt || spec.title || spec.type });
  host.appendChild(box);
  const chart = new OptionChart(box, { echarts: env.echarts }).draw(option);
  const state = { marks };
  box.__chart = chart; box.__sig = sig; box.__state = state;
  let cursor = -1;
  const live = make(doc, 'span', clsOf(env, 'live'), { role: 'status', 'aria-live': 'polite' });
  host.appendChild(live);
  const openMark = (m, x, y) => { if (env.interactive && env.onMark && m) { env.onMark({ visual: spec.id, field: state.marks.field, value: m.value, label: m.label }, { x, y, el: box }); } };
  const markOf = (p) => (p.name ? state.marks.byName(p.name) : null) || state.marks.items[p.seriesType === 'heatmap' || p.seriesType === 'custom' ? (p.value || [])[0] : p.dataIndex];
  if (env.onHover) {                                                                // a tooltip page: the hovered mark, after a short pause
    chart.on('mouseover', (p) => {
      const m = markOf(p), ev = p.event && p.event.event;
      if (m) { env.onHover({ visual: spec.id, field: state.marks.field, value: m.value, label: m.label }, { x: ev ? ev.clientX : 0, y: ev ? ev.clientY : 0, el: box }); }
    });
    chart.on('mouseout', () => { if (env.onHoverEnd) { env.onHoverEnd(); } });
    chart.on('globalout', () => { if (env.onHoverEnd) { env.onHoverEnd(); } });
  }
  chart.on('click', (p) => {
    const m = markOf(p), ev = p.event && p.event.event;
    if (m && state.marks.pick && state.marks.pick(m, p)) { return; }              // a map that opens a country's regions on a click (pick: true when that was all)
    openMark(m, ev ? ev.clientX : 0, ev ? ev.clientY : 0);
  });
  box.addEventListener('keydown', (e) => {
    const items = state.marks.items;
    if (!env.interactive || !items.length) { return; }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      e.preventDefault();
      const step = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1;
      if (cursor >= 0) { chart.highlight(cursor, false); }
      cursor = (cursor + step + items.length) % items.length;
      chart.highlight(cursor, true);
      live.textContent = items[cursor].text;
    } else if ((e.key === 'Enter' || e.key === ' ') && cursor >= 0) {
      e.preventDefault();
      const r = box.getBoundingClientRect();
      openMark(items[cursor], r.left + r.width / 2, r.top + r.height / 2);
    } else if (e.key === 'Escape') { cursor = -1; }
  });
  const brushed = env.interactive && env.onBrush ? reportBrush(spec, option, marks, env) : null;
  if (brushed) {                                                                     // linked brushing: what a drag selects is the report's
    box.__clearBrush = rangeBrush(chart.engine, () => true, env.t['accent-soft'] || 'rgba(127,127,127,0.18)', (a, b) => { const sel = brushed(a, b); if (sel) { env.onBrush(sel); } });
  }
  const handle = { dispose() { chart.dispose(); }, resize() { chart.resize(); } };
  box.__handle = handle;
  return handle;
}

/**
 * Draws a report visual's chart (any of REPORT_CHART_KINDS but the map), or its note; null when the type is not a chart.
 * @param {HTMLElement} host
 * @param {Object} spec
 * @param {Object} res
 * @param {Object} env
 * @returns {{dispose: function(): void, resize: function(): void}|null}
 */
export function drawReport(host, spec, res, env) {
  if (spec.type === 'map') { return reportMap(host, spec, res, env); }
  const built = reportOption(spec, res, env);
  if (!built) { return null; }
  if (built.empty) { return reportNote(host, built.empty, env); }
  const handle = mountReport(host, built, spec, env);
  const box = host.querySelector(':scope > .' + clsOf(env, 'chart').split(' ')[0]);
  if (built.multiples && box) {
    const m = built.multiples;
    box.setAttribute('data-multiples', String(m.count));
    box.setAttribute('data-split', m.split);
    box.style.minHeight = Math.max(160, m.rows * 140) + 'px';
    if (m.cut) { host.appendChild(make(host.ownerDocument, 'p', clsOf(env, 'cut'), { 'data-multiples-cut': String(m.cut.of) }, 'The first ' + m.cut.shown + ' of ' + m.cut.of + ' values of ' + m.cut.words + ' (Format, Small multiples, Most charts).')); }
  }
  return handle;
}

/**
 * The line under a card's figure.
 * @param {HTMLElement} el
 * @param {Array} categories
 * @param {Array<number>} values
 * @param {string} colour
 * @param {Object} [o] {echarts}
 * @returns {{dispose: function(): void, resize: function(): void}}
 */
export function sparkline(el, categories, values, colour, o) {
  if (!engineOf(o)) { return NONE; }
  const c = new OptionChart(el, { echarts: o && o.echarts }).draw(sparklineOption(categories, values, colour));
  return { dispose() { c.dispose(); }, resize() { c.resize(); } };
}

/* ---- the map: countries, and below a country its regions -------------------------------------------------------------------- */
// vendored shapes (no tiles, no CDN), read by the geo part's helper on first use. Drilling is the visual's own zoom: a click on a country
// selects it (the cross-filter, as ever) and opens its regions; the breadcrumb "World > Australia" goes back. The level is kept per
// visual while the page is open.
const ISO_REGION = /^([A-Za-z]{2})-[A-Za-z0-9]{1,3}$/;
const mapLevel = {};                                    // visual id -> the iso3 of the country its map has opened; null: the world

function place(G, w, raw) {
  if (raw == null) { return null; }
  const s = String(raw).trim();
  const m = ISO_REGION.exec(s);
  if (m) {
    const c = G.find(w, m[1]);
    if (c) { return { c, region: s.toUpperCase() }; }
  }
  const c = G.find(w, s);
  return c ? { c, region: null } : null;
}
function startLevel(G, w, spec) {
  const f = spec.format || {};
  if (!f.level || String(f.level).toLowerCase() === 'world') { return null; }
  const c = G.find(w, f.level);
  return c ? c.iso3 : null;
}
function selectedOf(env, spec) {
  const s = env.selected && env.selected.visual === spec.id ? env.selected.values.map(String) : [];
  return s.length ? new Set(s) : null;
}

function drawPoints(host, spec, env, tb, w, o) {
  const lat = tb.at((spec.wells.latitude || [])[0]), lon = tb.at((spec.wells.longitude || [])[0]);
  const label = (spec.wells.details || [])[0];
  const di = label ? tb.at(label) : -1;
  const pts = tb.rows.filter((r) => typeof r[lat] === 'number' && typeof r[lon] === 'number');
  const nameOf = (r) => (di >= 0 ? String(r[di]) : r[lat] + ', ' + r[lon]);
  // the helper projects each point onto the world's shapes and sizes the bubbles by area; drawn in the palette's first colour
  const g = env.geo.option({ level: 'world', geo: w, t: env.t, items: [], numText: (v) => env.compact(v),
    bubbles: pts.map((r) => ({ lon: r[lon], lat: r[lat], value: Math.abs(Number(r[o.mi]) || 0), name: nameOf(r), text: o.text(r[o.mi]) })) });
  const bub = g.series.find((x) => x.name === 'bubbles');
  if (bub) { bub.itemStyle = { color: env.palette[0], opacity: 0.8 }; }
  const option = Object.assign(reportBase(spec, env, { legend: false }), g);
  const items = pts.map((r) => ({ value: di >= 0 ? r[di] : nameOf(r), label: nameOf(r), text: nameOf(r) + ': ' + o.text(r[o.mi]) }));
  return mountReport(host, { option, marks: { field: label || null, items: label ? items : [], byName: (n) => (label ? items.find((x) => x.label === n) : null) } }, spec, env);
}

function drawPlaces(host, spec, env, tb, w, o) {
  const G = env.geo, doc = host.ownerDocument;
  const { loc, mi, text } = o;
  const f = spec.format || {};
  const li = tb.at(loc);
  const drill = f.drill !== false;
  if (!(spec.id in mapLevel)) { mapLevel[spec.id] = startLevel(G, w, spec); }
  const level = mapLevel[spec.id];                       // an iso3, or null for the world
  const parsed = tb.rows.map((r) => ({ raw: r[li], p: place(G, w, r[li]), v: typeof r[mi] === 'number' ? r[mi] : null })).filter((x) => x.raw != null);
  const missing = parsed.filter((x) => !x.p).map((x) => String(x.raw));
  const sel = selectedOf(env, spec);
  let inner = NONE;
  let dead = false;
  const redraw = () => { inner.dispose(); inner = drawPlaces(host, spec, env, tb, w, o) || inner; };
  const crumb = (country) => {
    if (!drill && !country) { return; }
    const nav = make(doc, 'nav', clsOf(env, 'crumb'), { 'aria-label': 'Map level' });
    const world = make(doc, 'button', clsOf(env, 'up'), { type: 'button', 'data-map-up': '' }, 'World');
    world.addEventListener('click', () => { mapLevel[spec.id] = null; redraw(); });
    if (!country) { nav.appendChild(make(doc, 'span', clsOf(env, 'here'), { 'aria-current': 'location' }, 'World')); }
    else { nav.appendChild(world); nav.appendChild(make(doc, 'span', '', { 'aria-hidden': 'true' }, ' › ')); nav.appendChild(make(doc, 'span', clsOf(env, 'here'), { 'aria-current': 'location' }, country.name)); }
    host.insertBefore(nav, host.firstChild);
  };
  const finish = (handle) => {
    inner = handle || inner;
    if (missing.length) { const u = [...new Set(missing)]; host.appendChild(make(doc, 'p', clsOf(env, 'note'), { 'data-map-missing': u.join(',') }, 'Not on the map: ' + u.join(', ') + ' (a country or region code is expected).')); }
  };
  const mk = (geo, lvl, items, marks) => {
    const keep = sel ? marks.items.filter((m) => m.value != null && sel.has(String(m.value))).map((m) => m.iso) : null;
    const g = G.option({ level: lvl, geo, t: env.t, items, clickable: items.map((i) => i.code), linked: !!env.interactive, numText: (v) => env.compact(v), selected: keep,
      openText: 'click to select' + (drill && lvl === 'world' ? ' and open its regions' : ''), closedText: 'no figure' });
    return mountReport(host, { option: Object.assign(reportBase(spec, env, { legend: false }), g), marks }, spec, env);
  };

  if (!level) {
    // the world: one figure per country (a country with only regional rows shows their sum), a dot for the countries the shapes are too small for
    const by = new Map();
    parsed.filter((x) => x.p).forEach((x) => {
      const k = x.p.c.iso3;
      const e = by.get(k) || { c: x.p.c, v: null, raw: null, regional: true };
      if (typeof x.v === 'number') { e.v = (e.v || 0) + x.v; }
      if (!x.p.region && e.raw == null) { e.raw = x.raw; e.regional = false; }
      by.set(k, e);
    });
    const items = [], list = [];
    by.forEach((e, k) => {
      items.push({ code: k, value: e.v, text: text(e.v) });
      list.push({ value: e.raw, label: e.c.name, iso: k, text: e.c.name + ': ' + text(e.v) + (e.regional ? ' (all regions)' : '') });
    });
    const marks = { field: loc, items: list, byName: (n) => list.find((x) => x.iso === n) };
    marks.pick = (mark) => {
      if (!drill || !mark) { return false; }
      mapLevel[spec.id] = mark.iso;
      const selects = mark.value != null && env.interactive && env.onMark;          // a selection redraws the visual itself; otherwise redraw here
      if (!selects) { redraw(); }
      return !selects;
    };
    finish(mk(w, 'world', items, marks));
    crumb(null);
    return { dispose() { dead = true; inner.dispose(); }, resize() { inner.resize(); } };
  }

  // one country's regions: a figure per region code; a click selects the region
  const country = w.by.iso3[level];
  reportNote(host, 'Loading the regions of ' + (country ? country.name : level) + '…', env);
  G.region(level).then((geo) => {
    if (dead) { return; }
    clear(host);
    const seen = new Map();
    parsed.filter((x) => x.p && x.p.c.iso3 === level && x.p.region).forEach((x) => {
      const code = x.p.region;
      if (!geo.by.code[code]) { missing.push(String(x.raw)); return; }
      const e = seen.get(code) || { v: null, raw: x.raw };
      if (typeof x.v === 'number') { e.v = (e.v || 0) + x.v; }
      seen.set(code, e);
    });
    const items = [], list = [];
    seen.forEach((e, code) => {
      const name = geo.by.code[code].name;
      items.push({ code, value: e.v, text: text(e.v) });
      list.push({ value: e.raw, label: name, iso: code, text: name + ': ' + text(e.v) });
    });
    finish(mk(geo, 'region', items, { field: loc, items: list, byName: (n) => list.find((x) => x.iso === n) }));
    crumb(country);
    if (!items.length) { host.appendChild(make(doc, 'p', clsOf(env, 'note'), { 'data-map-noregions': level }, 'No figures at region level for ' + (country ? country.name : level) + ': the location field holds countries, not their regions.')); }
  }).catch(() => {
    if (dead) { return; }
    mapLevel[spec.id] = null;                             // a country with no region file: back to the world, and say so
    redraw();
    host.appendChild(make(doc, 'p', clsOf(env, 'note'), { 'data-map-noregions': level }, 'No regions are available for ' + (country ? country.name : level) + '.'));
  });
  return { dispose() { dead = true; inner.dispose(); }, resize() { inner.resize(); } };
}

/**
 * The map visual: a measure by location (countries, or ISO 3166-2 regions; a click on a country opens its regions) or by latitude and
 * longitude (bubbles). env.geo is the map helper of the geo part (geoHelper()).
 * @param {HTMLElement} host
 * @param {Object} spec
 * @param {Object} res
 * @param {Object} env
 * @returns {{dispose: function(): void, resize: function(): void}}
 */
export function reportMap(host, spec, res, env) {
  if (!engineOf(env)) { return reportNote(host, 'The chart library did not load.', env); }
  if (!env.geo) { return reportNote(host, 'The map data did not load.', env); }
  reportNote(host, 'Loading the map…', env);
  let inner = NONE;
  let dead = false;
  Promise.resolve(env.geo.world()).then((w) => {
    if (dead) { return; }
    const tb = reportTable(res);
    const measure = (spec.wells.values || [])[0];
    const mi = tb.at(measure);
    const masked = tb.isMasked(measure), nf = env.numFmt(measure);
    const text = (v) => env.fmt(v, nf, masked);
    host.querySelectorAll(':scope > .' + clsOf(env, 'note').split(' ')[0] + ', :scope > .' + clsOf(env, 'crumb').split(' ')[0]).forEach((n) => n.remove());
    if (!tb.rows.length) { inner = reportNote(host, REPORT_NO_DATA, env); return; }
    const loc = (spec.wells.location || [])[0];
    inner = (loc ? drawPlaces(host, spec, env, tb, w, { loc, mi, text }) : drawPoints(host, spec, env, tb, w, { mi, text })) || inner;
  }).catch(() => { if (!dead) { reportNote(host, 'The map data did not load.', env); } });
  return { dispose() { dead = true; inner.dispose(); }, resize() { inner.resize(); } };
}

/* ---- options the host is given ---------------------------------------------------------------------------------------------- */

const DROP = ['formatter', 'extraCssText', 'appendToBody', 'valueFormatter'];
function clean(o) {
  if (Array.isArray(o)) { return o.map(clean); }
  if (o && typeof o === 'object') {
    const out = {};
    Object.keys(o).forEach((k) => { if (DROP.indexOf(k) < 0) { out[k] = clean(o[k]); } });
    return out;
  }
  return o;
}

/**
 * An option a program gave (a Python visual's), made safe and readable: the keys that could carry markup or code dropped (formatter,
 * extraCssText, appendToBody, valueFormatter), what it left unsaid given the theme's colours (text, axes, grid lines), no animation.
 * @param {Object} option
 * @param {Object<string, string>} t the theme tokens
 * @returns {Object}
 */
export function pythonOption(option, t) {
  const out = Object.assign({ backgroundColor: 'transparent', animation: false }, clean(option));
  if (!t || !t.ink) { return out; }
  const each = (v, fn) => { (Array.isArray(v) ? v : v ? [v] : []).forEach(fn); };
  const text = (o, key, color) => { o[key] = o[key] || {}; if (!o[key].color) { o[key].color = color; } };
  each(out.title, (x) => text(x, 'textStyle', t.ink));
  each(out.legend, (x) => text(x, 'textStyle', t.muted));
  ['xAxis', 'yAxis'].forEach((k) => each(out[k], (a) => {
    text(a, 'axisLabel', t.muted); text(a, 'nameTextStyle', t.muted);
    a.axisLine = a.axisLine || {}; a.axisLine.lineStyle = Object.assign({ color: t.border }, a.axisLine.lineStyle);
    a.splitLine = a.splitLine || {}; a.splitLine.lineStyle = Object.assign({ color: t.border, opacity: 0.5 }, a.splitLine.lineStyle);
  }));
  out.textStyle = Object.assign({ color: t.muted }, out.textStyle);
  return out;
}

/**
 * A measure's rows as bars: one bar per group (the groups joined by " / "), in the palette's first colour.
 * @param {{by: string[], rows: Array<Array<*>>, measure: string}} res
 * @param {string} title
 * @param {Object<string, string>} t
 * @param {string} colour
 * @returns {Object}
 */
export function measureBarsOption(res, title, t, colour) {
  t = t || {};
  const by = res.by || [];
  const names = res.rows.map((r) => (by.length ? r.slice(0, by.length).map((v) => (v == null ? '(blank)' : String(v))).join(' / ') : (res.measure || title || 'value')));
  const values = res.rows.map((r) => r[r.length - 1]);
  const axis = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontSize: 10 }, nameTextStyle: { color: t.muted, fontSize: 10 }, splitLine: { lineStyle: { color: t.border, opacity: 0.5 } } };
  const label = res.measure || title || 'value';
  return {
    grid: { left: 56, right: 16, top: 24, bottom: 36 }, tooltip: { trigger: 'axis' },
    xAxis: Object.assign({ type: 'category', data: names, name: by.join(' / ') }, axis), yAxis: Object.assign({ type: 'value', name: label }, axis),
    series: [{ type: 'bar', name: label, data: values, itemStyle: { color: colour || '#4f7cff' } }]
  };
}

/* ---- Chart any table's option ------------------------------------------------------------------------------------------------- */

const finite = (v) => typeof v === 'number' && isFinite(v);
/**
 * The colours of a table chart: the tokens' palette, else the tones.
 * @param {Object<string, *>} t
 * @returns {string[]}
 */
export function tableChartPalette(t) { return t.palette && t.palette.length ? t.palette.slice() : [t.link, t.accent, t.ok, t.neg, t.warn, t.bad, t.muted, t.ink].filter(Boolean); }
function sizer(data) {
  const zs = data.map((p) => p[2]).filter(finite), lo = Math.min.apply(null, zs.concat([0])), hi = Math.max.apply(null, zs.concat([1]));
  return (p) => (finite(p[2]) && hi > lo ? 4 + 20 * (p[2] - lo) / (hi - lo) : 6);
}

/**
 * The option of a drawn table-chart model (the host's engine aggregates; this draws): every colour from the theme tokens t (never a
 * literal), no animation; colour is never the only difference past the palette's length (dashes on lines, decals on bars).
 * @param {Object} model {x, xLabel, xField, axis ('category'|'time'|'value'), horizontal, series: [{name, label, data, type, axis, fmt, others, sized}], others}
 * @param {Object} settings {type, bars, percent, donut, labels, split, x: {gaps}}
 * @param {Object<string, *>} t the tokens (link, accent, ok, neg, warn, bad, muted, ink, faint, border, surface, mono; palette)
 * @param {{fmt: function(number, string): string, description: string}} o the value's words by its format, the chart's summary
 * @returns {Object}
 */
export function tableChartOption(model, settings, t, o) {
  const fmtValue = o.fmt;
  const pal = tableChartPalette(t), many = model.series.length > pal.length, stacked = settings.bars === 'stacked' || (settings.type === 'area' && !!settings.split);
  const colorOf = (d, i) => (d.others ? t.faint : pal[i % pal.length]);
  const dashOf = (i) => (i < pal.length ? 'solid' : ['dashed', 'dotted'][Math.floor(i / pal.length - 1) % 2]);
  const ax = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
    splitLine: { lineStyle: { color: t.border, opacity: 0.5 } }, nameTextStyle: { color: t.muted, fontSize: 10 } };
  const tip = { backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } };
  const base = { animation: false, aria: { enabled: true, label: { description: o.description } }, tooltip: Object.assign({ trigger: model.axis === 'value' ? 'item' : 'axis' }, tip),
    legend: { show: model.series.length > 1, type: 'scroll', top: 0, textStyle: { color: t.muted, fontSize: 11 }, pageTextStyle: { color: t.muted } } };
  if (many) { base.aria.decal = { show: true }; }
  if (settings.type === 'pie') {
    const d0 = model.series[0] || { data: [] };
    base.tooltip = Object.assign({ trigger: 'item' }, tip);
    base.legend.show = true;
    base.series = [{ type: 'pie', name: d0.label, radius: settings.donut ? ['45%', '70%'] : '70%', avoidLabelOverlap: true,
      label: { show: settings.labels || model.x.length <= 8, color: t.ink }, labelLine: { lineStyle: { color: t.border } },
      itemStyle: { borderColor: t.surface, borderWidth: 1 },
      data: model.x.map((k, j) => ({ name: k, value: d0.data[j], itemStyle: { color: model.others[j] ? t.faint : pal[j % pal.length] } })) }];
    return base;
  }
  const right = model.series.some((d) => d.axis === 'right');
  const yname = (side) => model.series.filter((d) => d.axis === side).map((d) => d.label).filter((v, i, a) => a.indexOf(v) === i).join(', ');
  const fmtOf = (side) => { const d = model.series.filter((x) => x.axis === side)[0]; return d ? d.fmt : null; };
  const valueAxis = (side) => {
    const f = fmtOf(side);
    return Object.assign({ type: 'value', name: yname(side), position: side, scale: settings.type === 'scatter' }, ax,
      { axisLabel: Object.assign({}, ax.axisLabel, { formatter: (v) => fmtValue(v, f) }) }, side === 'right' ? { splitLine: { show: false } } : {},
      settings.percent ? { max: 1 } : {});
  };
  const xAxis = model.axis === 'time' ? Object.assign({ type: 'time', name: model.xLabel }, ax)
    : model.axis === 'value' ? Object.assign({ type: 'value', name: model.xLabel, scale: true }, ax)
      : Object.assign({ type: 'category', name: model.xLabel, data: model.x }, ax, { axisLabel: Object.assign({}, ax.axisLabel, { hideOverlap: true }) });
  const yAxis = right ? [valueAxis('left'), valueAxis('right')] : [valueAxis('left')];
  if (model.horizontal) { base.xAxis = yAxis[0]; base.yAxis = Object.assign(xAxis, { inverse: true }); } else {
    base.xAxis = Object.assign(xAxis, { nameLocation: 'middle', nameGap: 28 });          // the X's name under its labels, clear of a right axis
    base.yAxis = yAxis;
  }
  base.grid = { left: model.horizontal ? 140 : 56, right: right ? 56 : 20, top: model.series.length > 1 ? 40 : 28, bottom: model.horizontal ? 40 : 48, containLabel: true };
  const signTone = model.series.length === 1 && model.series[0].type === 'bar' && model.series[0].data.some((v) => finite(v) && v < 0);
  base.series = model.series.map((d, i) => {
    const color = colorOf(d, i), type = d.type === 'area' ? 'line' : d.type;
    let data = model.axis === 'time' ? d.data.map((v, j) => [model.x[j], v]) : model.axis === 'value' ? d.data : d.data.slice();
    if (signTone && !model.horizontal) { data = data.map((v, j) => (finite(v) && v < 0 ? { value: model.axis === 'time' ? [model.x[j], v] : v, itemStyle: { color: t.neg } } : v)); }
    const s = { type, name: d.name, data, yAxisIndex: model.horizontal ? undefined : (d.axis === 'right' && right ? 1 : 0), itemStyle: { color },
      label: { show: !!settings.labels, color: t.ink, fontSize: 10, formatter: (p) => fmtValue(Array.isArray(p.value) ? p.value[1] : p.value, d.fmt) } };
    if (model.horizontal) { delete s.yAxisIndex; }
    if (type === 'line') {
      s.lineStyle = { color, type: dashOf(i), width: 2 };
      s.smooth = false; s.step = false; s.connectNulls = settings.x.gaps !== 'break';
      s.showSymbol = d.data.filter(finite).length <= 1 || d.data.length <= 60; s.symbolSize = d.data.filter(finite).length <= 1 ? 8 : 4;
    }
    if (d.type === 'area') { s.areaStyle = { color, opacity: 0.25 }; if (stacked) { s.stack = 'area-' + d.axis; } }
    if (type === 'bar' && settings.bars === 'stacked') { s.stack = 'bar-' + d.axis; }
    if (type === 'scatter') {
      s.symbolSize = d.sized ? sizer(d.data) : 6;
      s.tooltip = { formatter: (p) => d.name + ': ' + fmtValue(p.value[0], null) + ', ' + fmtValue(p.value[1], d.fmt) };
    }
    return s;
  });
  return base;
}
