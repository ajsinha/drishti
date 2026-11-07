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
/* new Chart(element, spec, options): the chart, drawn by the vendored ECharts from the spec. Every chart gets, without asking: the
   theme (it follows the page's) and the density (comfortable, compact, terminal), drishti-core's formats on axes and tooltips, a
   crosshair tooltip, legend toggles, click on any mark: Explain (the grid's hook and drawing) and the mark's rows selected (linked with
   a grid through drishti-core's bus), the data table (▦, the accessible alternative), export (PNG at 2x, SVG, CSV, the spec), drag-to-
   zoom with an overview window, the keyboard (arrows walk the marks, Enter explains, T the table), live updates by row id (throttled),
   and the card's chrome and status line. Two modes of one component (g2): 'card' (A, the default) and 'studio' (B: the kinds in a rail,
   the chart with its table, the spec beside it with options that change both). A map (kind: 'map') is drawn by its view
   (drishti_map-view.js) inside the same card. */
import { h, renderExplain, toCsv, download, EventBus, bus as sharedBus, announce } from '../../drishti-core/src/drishti_index.js';
import { normalizeSpec, kindOf, specText } from './drishti_chart-spec.js';
import { rowsOf } from './drishti_chart-data.js';
import { chartTheme, formatterFor } from './drishti_chart-theme.js';
import { rowId } from './drishti_chart-kinds.js';
import { tooltipElement, dataTable, legendElement, markSelection, addZoom, svgOf, valueText } from './drishti_chart-parts.js';
import { MapView } from './drishti_map-view.js';
import { Studio } from './drishti_chart-studio.js';

/** Every event a chart emits (on chart.events and as DOM events drishti:<kebab-name> on its element). */
export const CHART_EVENTS = Object.freeze(['rendered', 'markClicked', 'selectionChanged', 'legendToggled', 'zoomChanged', 'explainOpened', 'explainShown', 'exported',
  'tableToggled', 'dataUpdated', 'modeChanged', 'densityChanged', 'kindChanged', 'levelChanged', 'metricChanged', 'placeSelected', 'error']);

/** The modes of a chart: card (g2 A, the default) and studio (g2 B). */
export const CHART_MODES = Object.freeze(['card', 'studio']);

/** The selection's event on drishti-core's bus: {channel, ids, source}. */
export const SELECTION_EVENT = 'drishti:selection';

/**
 * The options of a chart (the spec says what to draw; these say how it lives in its page).
 * @typedef {Object} ChartOptions
 * @property {('card'|'studio')} [mode='card']
 * @property {('comfortable'|'compact'|'terminal')} [density='compact']
 * @property {function(Object): Promise<*>} [explain] the Explain hook (the grid's): a mark's context ({kind, title, series, field, value,
 *   category, rows, ids}) to an ExplainResult, words or an element
 * @property {{channel: string, bus: Object}} [link] linked selection: the bus (default drishti-core's) and the channel a grid shares
 * @property {function(string): Promise<Object>} [fetch] reads a spec's data.source (default: fetch, same origin, JSON)
 * @property {Object} [echarts] the engine (default window.echarts)
 * @property {string} [mapsBase] the folder of the map shapes (default /static/vendor/echarts/maps/)
 * @property {function(string): Promise<Object[]>} [placeData] a map's rows of a level (a country's ISO alpha-3) when it is drilled
 * @property {Object<string, Object>} [samples] the studio's spec of each kind
 * @property {('ranking'|'table')} [mapMode='ranking'] a map's mode (g3 A or B)
 * @property {function(string, (string|Blob), string): void} [download] where exports go (default: a download)
 * @property {number} [height] the drawing's height in pixels (default 300; a map 440)
 * @property {string} [asOf] the data's date, for the status line
 */

/**
 * A chart: the spec drawn in its card (or studio), with its tools, Explain, selection, export and live data.
 */
export class Chart {
  /**
   * @param {HTMLElement} el where the chart lives
   * @param {Object} spec the chart's spec (drishti_chart-spec.js)
   * @param {ChartOptions} [o]
   */
  constructor(el, spec, o) {
    this.el = el;
    this.doc = el.ownerDocument;
    this.win = this.doc.defaultView;
    this.o = Object.assign({ mode: 'card', density: 'compact' }, o);
    this.E = this.o.echarts || this.win.echarts;
    if (!this.E) { throw new Error('drishti-chart needs ECharts (vendor/echarts/echarts.min.js): load it first, or let Drishti.elements.load do it'); }
    /** @type {EventBus} */
    this.events = new EventBus();
    this.bus = (this.o.link && this.o.link.bus) || sharedBus;
    this.selected = new Set();
    this.hidden = new Set();
    this.rows = [];
    this.tableOpen = false;
    this.focusIndex = -1;
    this.timer = 0;
    this.setSpec(spec, { draw: false });
    this.mode = CHART_MODES.indexOf(this.o.mode) >= 0 ? this.o.mode : 'card';
    this.density = this.o.density;
    this.mount();
    if (this.o.link) { this.offLink = this.bus.on(SELECTION_EVENT, (p) => { if (p && p.source !== this && p.channel === this.o.link.channel) { this.setSelection(p.ids, { silent: true }); } }); }
    this.watch = new this.win.MutationObserver(() => this.draw());
    this.watch.observe(this.doc.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] });
    this.ready = this.load();
  }

  /** @private builds the DOM of the mode */
  mount() {
    const doc = this.doc;
    if (this.engine) { this.engine.dispose(); this.engine = null; }
    if (this.studio) { this.studio.destroy(); this.studio = null; }
    if (this.ro) { this.ro.disconnect(); }
    this.el.textContent = '';
    this.el.classList.add('dc');
    this.el.setAttribute('data-density', this.density);
    this.el.setAttribute('data-mode', this.mode);
    const tool = (id, text, title) => h(doc, 'button', { cls: 'dc-tool', text: text, attrs: { type: 'button', 'data-tool': id, title: title, 'aria-label': title }, on: { click: (e) => this.tool(id, e.currentTarget) } });
    this.titleEl = h(doc, 'span', { cls: 'dc-title' });
    this.kindEl = h(doc, 'span', { cls: 'dc-kind' });
    this.badgeEl = h(doc, 'span', { cls: 'dc-badge', attrs: { hidden: true } });
    this.headExtra = h(doc, 'span', { cls: 'dc-head-extra' });
    this.zoomReset = tool('zoom-reset', '⟲', 'Reset the zoom');
    this.zoomReset.hidden = true;
    this.tableBtn = tool('table', '▦', 'Data table');
    this.tableBtn.setAttribute('aria-pressed', 'false');
    this.head = h(doc, 'div', { cls: 'dc-head' }, [this.titleEl, this.kindEl, this.badgeEl, this.headExtra, h(doc, 'span', { cls: 'dc-sp' }),
      h(doc, 'span', { cls: 'dc-tools', attrs: { role: 'toolbar', 'aria-label': 'Chart tools' } }, [this.zoomReset, this.tableBtn, tool('export', '⤓', 'Export'), tool('explain', '?', 'Explain'), tool('full', '⛶', 'Full screen')])]);
    this.subEl = h(doc, 'div', { cls: 'dc-sub' });
    this.canvas = h(doc, 'div', { cls: 'dc-canvas', attrs: { role: 'img', tabindex: '0' }, on: { keydown: (e) => this.key(e) } });
    this.tableEl = h(doc, 'div', { cls: 'dc-table', attrs: { hidden: true } });
    this.loadingEl = h(doc, 'div', { cls: 'dc-loading', text: 'Loading…', attrs: { hidden: true, 'aria-live': 'polite' } });
    this.body = h(doc, 'div', { cls: 'dc-body' }, [this.canvas, this.tableEl, this.loadingEl]);
    this.side = h(doc, 'div', { cls: 'dc-side', attrs: { hidden: true } });
    this.main = h(doc, 'div', { cls: 'dc-main' }, [this.body, this.side]);
    this.legendHost = h(doc, 'div', { cls: 'dc-legend-host' });
    this.status = h(doc, 'div', { cls: 'dc-status' });
    this.sheet = h(doc, 'aside', { cls: 'dc-explain', attrs: { hidden: true, 'aria-label': 'Explain' } });
    this.card = h(doc, 'div', { cls: 'dc-card' }, [this.head, this.subEl, this.main, this.legendHost, this.status, this.sheet]);
    if (this.mode === 'studio') { this.studio = new Studio(this); } else { this.el.appendChild(this.card); }
    const height = this.o.height || this.spec.height || (this.spec.kind === 'map' ? 440 : 300);
    this.canvas.style.setProperty('height', height + 'px');
    this.engine = this.E.init(this.canvas, null, { renderer: this.o.renderer || 'canvas' });
    this.engine.on('click', (p) => this.onMark(p));
    this.engine.getZr().on('click', (e) => { if (!e.target && this.selected.size && !this.map) { this.setSelection([]); } });
    this.engine.on('datazoom', () => { const z = this.engine.getOption().dataZoom; const d = z && z[0]; this.zoomReset.hidden = !d || (d.start <= 0 && d.end >= 100); this.emit('zoomChanged', d ? { start: d.start, end: d.end } : {}); });
    this.ro = new this.win.ResizeObserver(() => { if (this.engine) { this.engine.resize(); } });
    this.ro.observe(this.canvas);
    if (this.spec.kind === 'map') { this.map = this.map ? this.map.remount() : new MapView(this); } else { this.map = null; }
  }

  /**
   * Replaces the spec (the studio's kind rail does; a page may too) and draws it.
   * @param {Object} spec
   * @param {{draw: boolean}} [o] draw default true
   * @returns {Promise<void>}
   */
  setSpec(spec, o) {
    this.rawSpec = spec;
    this.spec = normalizeSpec(spec);
    if (o && o.draw === false) { return Promise.resolve(); }
    const wasMap = !!this.map, isMap = this.spec.kind === 'map';
    if (wasMap !== isMap) { this.map = null; this.mount(); }
    this.hidden.clear();
    this.ready = this.load();
    this.emit('kindChanged', { kind: this.spec.kind });
    return this.ready;
  }

  /** @private reads the spec's data (inline, or from its source) and draws */
  load() {
    const d = this.spec.data || {};
    if (this.map) { return this.map.load(); }
    if (d.source) {
      this.loadingEl.hidden = false;
      const get = this.o.fetch || ((u) => this.win.fetch(u, { credentials: 'same-origin' }).then((r) => { if (!r.ok) { throw new Error(u + ': ' + r.status); } return r.json(); }));
      return Promise.resolve(get(d.source)).then((ans) => { this.rows = rowsOf(ans); this.loadingEl.hidden = true; this.draw(); }, (e) => { this.loadingEl.hidden = true; this.fail(e); });
    }
    this.rows = rowsOf(this.rawSpec.data && Array.isArray(this.rawSpec.data) ? this.rawSpec.data : d);
    this.draw();
    return Promise.resolve();
  }

  /** @private the theme and formats of a drawing */
  env() {
    const s = this.spec, th = chartTheme({ element: this.el, density: this.density });
    return { theme: th, fmt: formatterFor(s.format || (s.y && s.y.format) || (s.value && s.value.format)), xfmt: formatterFor(s.x && s.x.format) };
  }

  /**
   * Draws the chart again from its rows (after a theme, density, data or selection change; live updates come here throttled).
   * @returns {void}
   */
  draw() {
    if (!this.engine) { return; }
    if (this.map) { this.map.draw(); return; }
    const t0 = Date.now(), s = this.spec, kind = kindOf(s.kind), env = this.envNow = this.env();
    let built;
    try { built = kind.build(s, this.rows, env); } catch (e) { this.fail(e); return; }
    this.built = built;
    const option = built.option;
    option.backgroundColor = 'transparent';
    if (option.tooltip && option.tooltip.show !== false) { option.tooltip = Object.assign({}, option.tooltip, this.tooltipStyle(env)); }
    const zooms = kind.cartesian && addZoom(option, s, built, env.theme);
    markSelection(option, this.selected, env.theme);
    if (option.legend && this.hidden.size) { option.legend.selected = {}; this.hidden.forEach((n) => { option.legend.selected[n] = false; }); }
    this.engine.setOption(option, true);
    if (zooms) { this.engine.dispatchAction({ type: 'takeGlobalCursor', key: 'dataZoomSelect', dataZoomSelectActive: true }); }
    this.chrome(built, Date.now() - t0);
    this.emit('rendered', { kind: s.kind, ms: Date.now() - t0 });
  }

  /** @private the tooltip's look and its element */
  tooltipStyle(env) {
    const th = env.theme;
    return { confine: true, className: 'dc-tip-box', backgroundColor: th.surface2, borderColor: th.border, padding: [6, 9], extraCssText: '', textStyle: { color: th.ink, fontFamily: th.font, fontSize: th.size.font },
      formatter: (p) => tooltipElement(this.doc, p, { fmt: env.fmt, xfmt: env.xfmt }) };
  }

  /** @private the card around the drawing: title, badge, legend, table, status, the canvas's description */
  chrome(built, ms) {
    const s = this.spec;
    this.titleEl.textContent = s.title || '';
    this.kindEl.textContent = s.kind;
    this.kindEl.classList.toggle('dc-kind-risk', /^risk\./.test(s.kind));
    this.subEl.textContent = s.subtitle || '';
    this.subEl.hidden = !s.subtitle;
    this.badgeEl.hidden = !built.badge;
    if (built.badge) { this.badgeEl.textContent = '● ' + built.badge.text; this.badgeEl.className = 'dc-badge dc-tone-' + (built.badge.tone || 'muted'); }
    this.canvas.setAttribute('aria-label', built.summary || s.kind);
    this.legendHost.textContent = '';
    if (s.legend !== 'none' && built.legend && built.legend.length) {
      this.legendHost.appendChild(legendElement(this.doc, built.legend, { toggles: !built.legendStatic, hidden: this.hidden, onToggle: (n) => this.toggleSeries(n) }));
    }
    this.legendHost.setAttribute('data-at', s.legend || 'bottom');
    if (this.tableOpen) { this.tableEl.textContent = ''; this.tableEl.appendChild(dataTable(this.doc, built.table, built.summary || '')); }
    const src = (s.data && s.data.source) || (s.from === 'vega-lite' ? 'Vega-Lite, translated' : 'inline rows');
    this.status.textContent = '';
    [h(this.doc, 'b', { text: src }), this.o.asOf || s.asOf ? h(this.doc, 'span', { text: 'as of ' + (this.o.asOf || s.asOf) }) : null, h(this.doc, 'span', { cls: 'dc-rows', text: this.rows.length + ' rows' }),
      h(this.doc, 'span', { text: ms + ' ms' }), h(this.doc, 'span', { cls: 'dc-hint', text: s.onClick === 'none' ? '' : 'click a mark to explain it' })].forEach((x) => { if (x) { this.status.appendChild(x); } });
    if (this.studio) { this.studio.update(built); }
  }

  /** @private a failure, in words, in the card */
  fail(e) {
    this.loadingEl.hidden = false;
    this.loadingEl.textContent = 'This chart could not be drawn: ' + (e && e.message ? e.message : e);
    this.loadingEl.classList.add('dc-error');
    this.emit('error', { error: e });
  }

  /**
   * Emits an event on chart.events and as a DOM event drishti:<kebab-name> on the element.
   * @param {string} name one of CHART_EVENTS
   * @param {Object} [detail]
   * @returns {void}
   */
  emit(name, detail) {
    this.events.emit(name, detail || {});
    if (this.el && this.win.CustomEvent) { this.el.dispatchEvent(new this.win.CustomEvent('drishti:' + name.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), { detail: detail || {}, bubbles: true })); }
  }

  // ---- marks: Explain and selection -------------------------------------------------------------------------------------------------
  /** @private the rows a series' items index (a layer's own) */
  seriesRows(s) { return this.built && this.built.layers && s && s.layer !== undefined ? this.built.layers[s.layer] : this.rows; }

  /**
   * The context of a mark, as the Explain hook gets it.
   * @param {Object} p the engine's event params (seriesIndex, dataIndex, data, name, value)
   * @returns {Object} {kind, title, series, field, value, text, category, rows, ids}
   */
  markContext(p) {
    const opt = this.built ? this.built.option : {}, s = (opt.series || [])[p.seriesIndex] || {}, item = p.data || {}, rows = this.seriesRows(s);
    const env = this.envNow || this.env();
    return { kind: this.spec.kind, title: this.spec.title || '', series: p.seriesName, field: s.field || null, value: Array.isArray(p.value) ? p.value : p.value,
      text: valueText(p, env.fmt), category: p.name, seriesIndex: p.seriesIndex, dataIndex: p.dataIndex, rows: (item.r || []).map((i) => rows[i]).filter(Boolean), ids: item.ids || [] };
  }

  /** @private a click on a mark: select its rows (ctrl or shift adds), then Explain */
  onMark(p) {
    if (this.map) { this.map.onClick(p); return; }
    if (!p || (p.seriesName || '').indexOf('__') === 0 || p.seriesName === '_base') { return; }
    const ctx = this.markContext(p), ev = p.event && p.event.event, add = ev && (ev.ctrlKey || ev.metaKey || ev.shiftKey);
    this.emit('markClicked', ctx);
    if (this.spec.onClick === 'none') { return; }
    const next = add ? new Set(this.selected) : new Set();
    ctx.ids.forEach((id) => { if (add && this.selected.has(id)) { next.delete(id); } else { next.add(id); } });
    this.setSelection(Array.from(next));
    if (this.spec.onClick === 'explain') { this.explain(ctx); }
  }

  /**
   * Selects rows by id: their marks are outlined; tells the linked grid (unless silent).
   * @param {string[]} ids
   * @param {{silent: boolean}} [o] silent: a selection that came from the link (not sent back)
   * @returns {void}
   */
  setSelection(ids, o) {
    const next = new Set((ids || []).map(String));
    if (next.size === this.selected.size && Array.from(next).every((x) => this.selected.has(x))) { return; }
    this.selected = next;
    this.draw();
    this.emit('selectionChanged', { ids: Array.from(next), source: o && o.silent ? 'link' : 'chart' });
    if (this.o.link && !(o && o.silent)) { this.bus.emit(SELECTION_EVENT, { channel: this.o.link.channel, ids: Array.from(next), source: this }); }
  }

  /**
   * The ids selected.
   * @returns {string[]}
   */
  selection() { return Array.from(this.selected); }

  /**
   * Explains a mark through the hook, in the side sheet (the grid's drawing of an ExplainResult).
   * @param {Object} ctx a mark's context (markContext)
   * @returns {Promise<*>}
   */
  explain(ctx) {
    const close = () => { this.sheet.hidden = true; this.canvas.focus(); };
    this.sheet.hidden = false;
    if (!this.o.explain) { renderExplain(this.sheet, { result: 'This chart has no Explain hook: ' + (ctx.series || '') + ' ' + (ctx.text || '') }, 'drawer', { prefix: 'dc', onClose: close }); return Promise.resolve(null); }
    renderExplain(this.sheet, { loading: true }, 'drawer', { prefix: 'dc', onClose: close });
    this.emit('explainOpened', ctx);
    return Promise.resolve().then(() => this.o.explain(ctx)).then((res) => {
      renderExplain(this.sheet, { result: res }, 'drawer', { prefix: 'dc', onClose: close });
      this.emit('explainShown', { context: ctx, result: res });
      return res;
    }, (e) => { renderExplain(this.sheet, { error: e }, 'drawer', { prefix: 'dc', onClose: close }); return null; });
  }

  // ---- tools, legend, keys ----------------------------------------------------------------------------------------------------------
  /** @private a toolbar button */
  tool(id, btn) {
    if (id === 'table') { this.toggleTable(); }
    if (id === 'export') { this.exportMenu(btn); }
    if (id === 'explain') { const first = this.firstMark(); if (first) { this.explain(this.markContext(first)); } }
    if (id === 'full') { this.el.classList.toggle('dc-full'); btn.setAttribute('aria-pressed', String(this.el.classList.contains('dc-full'))); this.engine.resize(); }
    if (id === 'zoom-reset') { this.engine.dispatchAction({ type: 'dataZoom', start: 0, end: 100 }); }
  }

  /** @private the first mark with a figure: the toolbar's Explain and the keyboard's start */
  firstMark(i) {
    const opt = this.built && this.built.option;
    if (!opt) { return null; }
    const si = opt.series.findIndex((s) => !s.helper && s.name !== '__selected' && (s.data || []).length), s = opt.series[si];
    if (!s) { return null; }
    const di = Math.max(0, Math.min(s.data.length - 1, i || 0)), d = s.data[di];
    return { seriesIndex: si, dataIndex: di, data: d, seriesName: s.name, seriesType: s.type, value: d && d.value !== undefined ? d.value : d, name: d && d.name !== undefined ? d.name : (opt.xAxis && opt.xAxis.data ? opt.xAxis.data[di] : String(di)) };
  }

  /**
   * Shows or hides the data table (the accessible alternative) in place of the drawing.
   * @param {boolean} [open] default: the other state
   * @returns {void}
   */
  toggleTable(open) {
    this.tableOpen = open === undefined ? !this.tableOpen : !!open;
    this.tableEl.hidden = !this.tableOpen;
    this.canvas.hidden = this.tableOpen;
    this.tableBtn.setAttribute('aria-pressed', String(this.tableOpen));
    this.tableBtn.classList.toggle('dc-on', this.tableOpen);
    if (this.map) { this.map.draw(); } else { this.draw(); }
    if (!this.tableOpen && this.engine) { this.engine.resize(); }
    this.emit('tableToggled', { open: this.tableOpen });
  }

  /**
   * Hides or shows a series (a legend entry).
   * @param {string} name
   * @returns {void}
   */
  toggleSeries(name) {
    if (this.hidden.has(name)) { this.hidden.delete(name); } else { this.hidden.add(name); }
    this.engine.dispatchAction({ type: 'legendToggleSelect', name: name });
    const b = this.legendHost.querySelector('.dc-lg[data-name="' + name.replace(/["\\]/g, '\\$&') + '"]');
    if (b) { b.classList.toggle('dc-off', this.hidden.has(name)); b.setAttribute('aria-pressed', String(!this.hidden.has(name))); }
    this.emit('legendToggled', { name: name, shown: !this.hidden.has(name) });
  }

  /** @private the keys of the drawing: arrows walk the first series' marks, Enter or E explains, T the table, Escape closes */
  key(e) {
    const k = e.key, first = this.firstMark(0);
    if (!first) { return; }
    const n = this.built.option.series[first.seriesIndex].data.length;
    let i = this.focusIndex;
    if (k === 'ArrowRight' || k === 'ArrowDown') { i = Math.min(n - 1, i + 1); } else if (k === 'ArrowLeft' || k === 'ArrowUp') { i = Math.max(0, i - 1); } else if (k === 'Home') { i = 0; } else if (k === 'End') { i = n - 1; } else if (k === 'Enter' || k === 'e' || k === 'E') {
      e.preventDefault(); this.explain(this.markContext(this.firstMark(Math.max(0, i)))); return;
    } else if (k === 't' || k === 'T') { e.preventDefault(); this.toggleTable(); return; } else if (k === 'Escape') { this.sheet.hidden = true; this.el.classList.remove('dc-full'); return; } else { return; }
    e.preventDefault();
    this.focusIndex = i;
    const m = this.firstMark(i);
    this.engine.dispatchAction({ type: 'showTip', seriesIndex: m.seriesIndex, dataIndex: m.dataIndex });
    announce((m.name !== undefined ? m.name + ': ' : '') + valueText(m, this.envNow.fmt), { document: this.doc });
  }

  // ---- export -------------------------------------------------------------------------------------------------------------------------
  /** @private the export menu under its button */
  exportMenu(btn) {
    if (this.menu) { this.menu.remove(); this.menu = null; return; }
    const words = { png: 'PNG image (2x)', svg: 'SVG drawing', csv: 'CSV of the data', spec: 'Copy the spec' };
    const close = () => { if (this.menu) { this.menu.remove(); this.menu = null; this.doc.removeEventListener('keydown', onKey, true); } };
    const onKey = (e) => { if (e.key === 'Escape') { close(); btn.focus(); } };
    this.menu = h(this.doc, 'div', { cls: 'dc-menu', attrs: { role: 'menu', 'aria-label': 'Export' } }, this.spec.export.map((x) => h(this.doc, 'button', { cls: 'dc-menu-item', text: words[x],
      attrs: { type: 'button', role: 'menuitem', 'data-export': x }, on: { click: () => { close(); this.export(x); } } })));
    this.card.appendChild(this.menu);
    this.doc.addEventListener('keydown', onKey, true);
    const first = this.menu.querySelector('button');
    if (first) { first.focus(); }
  }

  /**
   * Exports the chart: a PNG at twice the pixels, an SVG drawn off screen, the data as CSV, or the spec (copied, and handed back).
   * @param {('png'|'svg'|'csv'|'spec')} what
   * @returns {string} the content (a data URL for PNG)
   */
  export(what) {
    const name = (this.spec.title || this.spec.kind).replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '').toLowerCase() || 'chart';
    const th = (this.envNow || this.env()).theme;
    let out, type, file;
    if (what === 'png') { out = this.engine.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: th.surface }); file = name + '.png'; type = 'image/png'; }
    if (what === 'svg') { out = svgOf(this.E, Object.assign({}, this.engine.getOption(), this.map ? this.map.svgOption() : {}), this.canvas.clientWidth, this.canvas.clientHeight); file = name + '.svg'; type = 'image/svg+xml'; }
    if (what === 'csv') { const t = this.map ? this.map.table() : this.built.table; out = toCsv({ columns: t.columns, rows: t.rows }); file = name + '.csv'; type = 'text/csv'; }
    if (what === 'spec') { out = specText(this.rawSpec, { rows: true }); file = name + '.json'; type = 'application/json'; if (this.win.navigator.clipboard) { this.win.navigator.clipboard.writeText(out).catch(() => {}); } }
    if (out === undefined) { throw new Error('no export ' + what + ' (png, svg, csv, spec)'); }
    if (what !== 'spec') { (this.o.download || ((f, c, t) => download(this.doc, f, c, t)))(file, out, type); }
    this.emit('exported', { what: what, file: file, size: out.length });
    return out;
  }

  // ---- live data ------------------------------------------------------------------------------------------------------------------
  /**
   * Applies a transaction by row id (the spec's id field): rows added, updated (merged) and removed; drawn again, throttled.
   * @param {{add: Object[], update: Object[], remove: Array<(string|Object)>}} tx
   * @returns {{added: number, updated: number, removed: number}}
   */
  apply(tx) {
    const at = new Map(this.rows.map((r, i) => [rowId(this.spec, r, i), i]));
    let updated = 0, removed = 0;
    (tx.update || []).forEach((u) => { const i = at.get(rowId(this.spec, u, -1)); if (i !== undefined) { this.rows[i] = Object.assign({}, this.rows[i], u); updated++; } });
    if (tx.remove && tx.remove.length) {
      const gone = new Set(tx.remove.map((r) => (typeof r === 'object' ? rowId(this.spec, r, -1) : String(r))));
      const before = this.rows.length;
      this.rows = this.rows.filter((r, i) => !gone.has(rowId(this.spec, r, i)));
      removed = before - this.rows.length;
    }
    (tx.add || []).forEach((r) => this.rows.push(r));
    const keep = this.spec.live && this.spec.live.keep;
    if (keep && this.rows.length > keep) { this.rows.splice(0, this.rows.length - keep); }
    this.later();
    const out = { added: (tx.add || []).length, updated: updated, removed: removed };
    this.emit('dataUpdated', out);
    return out;
  }

  /**
   * Appends rows (a feed's new points), drawn again throttled; the spec's live.keep caps how many stay.
   * @param {Object[]} rows
   * @returns {void}
   */
  append(rows) { this.apply({ add: rows }); }

  /**
   * Replaces every row.
   * @param {Object[]} rows
   * @returns {void}
   */
  replace(rows) { this.rows = rows.slice(); this.later(); this.emit('dataUpdated', { replaced: rows.length }); }

  /** @private one drawing per throttle window (the spec's live.throttleMs, default 250) */
  later() {
    if (this.timer) { return; }
    const ms = this.spec.live && this.spec.live.throttleMs !== undefined ? this.spec.live.throttleMs : 250;
    this.timer = this.win.setTimeout(() => { this.timer = 0; this.draw(); }, ms);
  }

  // ---- modes and density ----------------------------------------------------------------------------------------------------------
  /**
   * Switches the mode: card (g2 A) or studio (g2 B).
   * @param {('card'|'studio')} mode
   * @returns {void}
   */
  setMode(mode) {
    if (CHART_MODES.indexOf(mode) < 0 || mode === this.mode) { return; }
    this.mode = mode;
    this.mount();
    this.draw();
    this.emit('modeChanged', { mode: mode });
  }

  /**
   * Switches a map's mode: ranking (g3 A) or table (g3 B); nothing for other kinds.
   * @param {('ranking'|'table')} mode
   * @returns {void}
   */
  setMapMode(mode) { this.o.mapMode = mode; if (this.map) { this.map.setMode(mode); } }

  /**
   * Switches the density: comfortable, compact or terminal (the grid's three).
   * @param {('comfortable'|'compact'|'terminal')} d
   * @returns {void}
   */
  setDensity(d) {
    this.density = d;
    this.el.setAttribute('data-density', d);
    this.draw();
    this.emit('densityChanged', { density: d });
  }

  /**
   * Stops the chart: the engine, the observers, the link.
   * @returns {void}
   */
  destroy() {
    if (this.offLink) { this.offLink(); }
    if (this.watch) { this.watch.disconnect(); }
    if (this.ro) { this.ro.disconnect(); }
    if (this.timer) { this.win.clearTimeout(this.timer); }
    if (this.engine) { this.engine.dispose(); this.engine = null; }
    this.el.textContent = '';
  }
}
