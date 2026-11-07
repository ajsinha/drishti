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
/* The map inside a chart card (kind: 'map'): the world, then double-click a country (or "Drill into regions ›") for its first-level
   regions, loaded on demand (regions/<ISO3>.json) and cached; a breadcrumb back; the metric picker; a choropleth with its quantile or
   continuous legend and counts, no-data places hatched, bubbles; wheel and drag to zoom and pan, + − ⟲ too. Two modes of one component
   (g3): 'ranking' (A, the default: a side column with the legend, the top ten with bars and the selected place with Explain and the
   drill) and 'table' (B: a floating legend, a world inset once drilled, the selected place in a floating card, a sortable table under
   the map). A click selects a place (its rows go to a linked grid) and explains it. */
import { h, ToastTray } from '../../drishti-core/src/drishti_index.js';
import { createGeoLoader } from './drishti_chart-geo.js';
import { buildMap } from './drishti_chart-map.js';
import { rowsOf } from './drishti_chart-data.js';
import { tooltipElement } from './drishti_chart-parts.js';

/** A map's modes: ranking (g3 A, the default) and table (g3 B). */
export const MAP_MODES = Object.freeze(['ranking', 'table']);

const loaders = new Map();

/**
 * The shared loader of a maps folder (one per folder in a page: every map of the page shares its cache).
 * @param {string} base
 * @param {function(string): Promise<Object>} [fetchJson]
 * @returns {Object}
 */
export function geoLoader(base, fetchJson) {
  if (fetchJson) { return createGeoLoader({ base: base, fetchJson: fetchJson }); }
  if (!loaders.has(base)) { loaders.set(base, createGeoLoader({ base: base })); }
  return loaders.get(base);
}

/**
 * The map's view inside a Chart: the level, its shapes and rows, the legend, ranking, table, breadcrumb and drill.
 */
export class MapView {
  /**
   * @param {Object} chart the Chart it draws in
   */
  constructor(chart) {
    this.c = chart;
    const w = chart.win, eb = w.Drishti && w.Drishti.elements && w.Drishti.elements.base;
    this.base = chart.o.mapsBase || (eb ? eb.replace(/elements\/$/, '') + 'vendor/echarts/maps/' : '/static/vendor/echarts/maps/');
    this.loader = geoLoader(this.base, chart.o.fetchMap);
    this.mode = MAP_MODES.indexOf(chart.o.mapMode) >= 0 ? chart.o.mapMode : 'ranking';
    this.metricIndex = 0;
    this.highlight = null;
    this.sel = null;
    this.sort = { k: 'v', d: -1 };
    this.levelRows = {};
    this.remount();
  }

  /**
   * Attaches to the card again (after the chart's mode changed): the breadcrumb, the metric picker, the side column or the table, events.
   * @returns {MapView}
   */
  remount() {
    const c = this.c, doc = c.doc;
    this.crumb = h(doc, 'nav', { cls: 'dc-crumb', attrs: { 'aria-label': 'Map level' } });
    this.picker = h(doc, 'select', { cls: 'dc-metric', attrs: { 'aria-label': 'Metric' }, on: { change: () => { this.metricIndex = this.picker.selectedIndex; this.highlight = null; this.draw(); c.emit('metricChanged', { metric: this.metric() }); } } });
    c.headExtra.textContent = '';
    c.headExtra.append(this.crumb, this.picker);
    this.zoomBox = h(doc, 'div', { cls: 'dc-zoom' }, [['in', '+', 'Zoom in'], ['out', '−', 'Zoom out'], ['reset', '⟲', 'Reset the view']].map((z) => h(doc, 'button', { cls: 'dc-tool', text: z[1],
      attrs: { type: 'button', 'data-zoom': z[0], title: z[2], 'aria-label': z[2] }, on: { click: () => this.zoom(z[0]) } })));
    this.float = h(doc, 'div', { cls: 'dc-map-float' });
    c.body.append(this.zoomBox, this.float);
    this.below = h(doc, 'div', { cls: 'dc-map-table' });
    c.card.insertBefore(this.below, c.legendHost);
    c.el.setAttribute('data-map-mode', this.mode);
    c.side.hidden = this.mode !== 'ranking';
    c.engine && c.engine.off && c.engine.off('dblclick');
    if (c.engine) { c.engine.on('dblclick', (p) => { const code = p && p.name; if (this.geo && this.geo.level === 'world' && code && code.indexOf('__') !== 0) { this.drill(code); } }); }
    return this;
  }

  /**
   * The metric shown (the picker's).
   * @returns {Object}
   */
  metric() { const ms = this.metrics(); return ms[Math.min(this.metricIndex, ms.length - 1)] || null; }

  /** @private the spec's metrics */
  metrics() {
    const s = this.c.spec, list = (s.metrics || []).map((m) => (typeof m === 'string' ? { field: m, title: m } : m));
    if (!list.length && s.value) { list.push({ field: s.value.field, title: s.value.title || s.value.field, format: s.value.format }); }
    return list;
  }

  /**
   * Loads the spec's level (the world, or a country's regions) and draws it.
   * @returns {Promise<void>}
   */
  load() {
    const s = this.c.spec, d = s.data || {};
    const rows = d.source ? Promise.resolve((this.c.o.fetch || ((u) => this.c.win.fetch(u, { credentials: 'same-origin' }).then((r) => r.json())))(d.source)).then(rowsOf)
      : Promise.resolve(rowsOf(Array.isArray(this.c.rawSpec.data) ? this.c.rawSpec.data : d));
    return rows.then((r) => { this.c.rows = r; this.levelRows = {}; return this.go(s.level && s.level !== 'world' ? String(s.level).toUpperCase() : 'world'); }, (e) => this.c.fail(e));
  }

  /** @private loads a level's shapes (and its rows when the page gives them per level) and draws */
  go(level) {
    const c = this.c;
    c.loadingEl.hidden = false;
    c.loadingEl.classList.remove('dc-error');
    c.loadingEl.textContent = level === 'world' ? 'Loading the world…' : 'Loading regions/' + level + '.json…';
    const shapes = level === 'world' ? this.loader.world() : this.loader.regions(level);
    const rows = level !== 'world' && c.o.placeData && !this.levelRows[level] ? Promise.resolve(c.o.placeData(level)).then((r) => { this.levelRows[level] = rowsOf(r); }) : Promise.resolve();
    return Promise.all([shapes, rows]).then((got) => {
      const geo = got[0], name = 'dc-' + geo.level.toLowerCase();
      if (!geo.registered) {
        const frames = geo.frames.map((f) => ({ type: 'Feature', properties: { name: '__frame_' + f.code }, geometry: { type: 'Polygon', coordinates: [[[f.box[0], f.box[1]], [f.box[2], f.box[1]], [f.box[2], f.box[3]], [f.box[0], f.box[3]], [f.box[0], f.box[1]]]] } }));
        c.E.registerMap(name, { type: 'FeatureCollection', features: frames.concat(geo.shapes) });
        geo.registered = name;
      }
      this.geo = geo;
      this.sel = null;
      this.highlight = null;
      c.loadingEl.hidden = true;
      this.draw();
      c.emit('levelChanged', { level: geo.level, places: geo.list.length });
    }, (e) => {
      c.loadingEl.hidden = true;
      this.say(level === 'world' ? 'The world map did not load: ' + e.message : 'No regions for ' + level + ' (' + e.message + ')');
      if (!this.geo) { c.fail(e); }
    });
  }

  /**
   * Opens a country's first-level regions (loaded once, then from the cache).
   * @param {string} iso3
   * @returns {Promise<void>}
   */
  drill(iso3) {
    const first = !this.loader.hasRegions(iso3);
    return this.go(String(iso3).toUpperCase()).then(() => {
      if (this.geo && this.geo.level === String(iso3).toUpperCase() && first) {
        const pts = this.geo.points.length;
        this.say(this.placeName(iso3, true) + ': ' + this.geo.list.length + ' regions loaded' + (pts ? ' (' + pts + ' as points)' : ''));
      }
    });
  }

  /**
   * A short message at the card's foot (a drill's regions loaded, a country without a regions file).
   * @param {string} text
   * @returns {void}
   */
  say(text) {
    if (!this.tray || !this.tray.tray.isConnected) { this.tray = new ToastTray({ parent: this.c.card, className: 'dc-toasts', max: 2, timeout: 3500, single: true }); }
    this.tray.show({ text: text, className: 'dc-toast' });
  }

  /**
   * Back to the world.
   * @returns {Promise<void>}
   */
  back() { return this.go('world'); }

  /** @private a place's name: a country's from the world, else the level's */
  placeName(code, country) {
    const w = this.worldGeo || (this.geo && this.geo.level === 'world' ? this.geo : null);
    if (country) { const p = w && w.by.code[String(code).toUpperCase()]; return p ? p.name : code; }
    const p = this.geo && (this.geo.by.code[String(code).toUpperCase()]);
    return p ? p.name : code;
  }

  /** @private the rows of the level shown */
  rows() { return this.geo && this.geo.level !== 'world' && this.levelRows[this.geo.level] ? this.levelRows[this.geo.level] : this.c.rows; }

  /** @private a hatched fill for places without a figure (a small canvas pattern) */
  hatch(th) {
    if (this.hatchFor === th.nodata + th.border) { return this.hatchFill; }
    const cv = this.c.doc.createElement('canvas');
    cv.width = 8; cv.height = 8;
    const g = cv.getContext && cv.getContext('2d');
    if (!g) { return null; }
    g.fillStyle = th.nodata; g.fillRect(0, 0, 8, 8);
    g.strokeStyle = th.border; g.lineWidth = 1.4;
    g.beginPath(); g.moveTo(-2, 10); g.lineTo(10, -2); g.moveTo(-2, 2); g.lineTo(2, -2); g.moveTo(6, 10); g.lineTo(10, 6); g.stroke();
    this.hatchFor = th.nodata + th.border;
    this.hatchFill = { image: cv, repeat: 'repeat' };
    return this.hatchFill;
  }

  /**
   * Draws the level: the map, the legend, the ranking or the table, the breadcrumb and the status line.
   * @returns {void}
   */
  draw() {
    const c = this.c;
    if (!this.geo || !c.engine) { return; }
    if (this.geo.level === 'world') { this.worldGeo = this.geo; }
    const env = c.envNow = c.env(), th = env.theme, rows = this.rows();
    const selCodes = new Set(this.sel ? [this.sel] : []);
    const built = this.built = buildMap(c.spec, rows, this.geo, { theme: th, mapName: this.geo.registered, metric: this.metric(), highlight: this.highlight, selected: [], hatch: this.hatch(th) });
    if (c.selected.size) { built.places.forEach((p) => { if (p.ids.some((x) => c.selected.has(x))) { selCodes.add(p.code); } }); }
    const again = buildMap(c.spec, rows, this.geo, { theme: th, mapName: this.geo.registered, metric: this.metric(), highlight: this.highlight, selected: Array.from(selCodes), hatch: this.hatch(th) });
    Object.assign(built, { option: again.option });
    const fmt = built.fmt, tot = built.places.reduce((s, p) => s + (p.v || 0), 0), byCode = new Map(built.places.map((p) => [p.code, p]));
    built.option.tooltip = Object.assign(built.option.tooltip, c.tooltipStyle(env), {
      formatter: (p) => {
        const pl = byCode.get(p.name), el = tooltipElement(c.doc, Object.assign({}, p, { seriesName: built.metric.title || built.metric.field, value: pl ? pl.v : null, seriesType: 'map' }), { fmt: fmt, xfmt: fmt, names: () => (pl ? pl.name + ' · ' + pl.code : p.name) });
        if (pl && pl.v !== null && tot) { el.appendChild(h(c.doc, 'div', { cls: 'dc-tip-r' }, [h(c.doc, 'span', { text: 'share' }), h(c.doc, 'b', { text: (100 * pl.v / tot).toFixed(1) + '%' })])); }
        if (this.geo.level === 'world' && pl && !pl.point) { el.appendChild(h(c.doc, 'div', { cls: 'dc-tip-h', text: 'double-click for its regions' })); }
        return el;
      } });
    c.built = { option: built.option, table: built.table, legend: [], summary: built.summary };
    c.engine.setOption(built.option, true);
    c.titleEl.textContent = c.spec.title || ((built.metric.title || built.metric.field) + ' by ' + (this.geo.level === 'world' ? 'country' : 'region'));
    c.kindEl.textContent = 'map';
    c.canvas.setAttribute('aria-label', built.summary);
    c.badgeEl.hidden = true;
    this.chrome(built, fmt);
    if (c.tableOpen) { c.tableEl.textContent = ''; c.tableEl.appendChild(this.tableEl(built, fmt, 'dc-dtable')); }
    c.emit('rendered', { kind: 'map', level: this.geo.level });
  }

  /** @private the breadcrumb, picker, side column or table, floating legend and card, status line */
  chrome(built, fmt) {
    const c = this.c, doc = c.doc, lvl = this.geo.level;
    this.crumb.textContent = '';
    if (lvl === 'world') { this.crumb.appendChild(h(doc, 'b', { text: 'World' })); } else {
      this.crumb.append(h(doc, 'button', { cls: 'dc-link', text: 'World', attrs: { type: 'button', 'data-go': 'world' }, on: { click: () => this.back() } }), ' › ', h(doc, 'b', { text: this.placeName(lvl, true) }));
    }
    const ms = this.metrics();
    if (this.picker.options.length !== ms.length) { this.picker.textContent = ''; ms.forEach((m) => this.picker.appendChild(h(doc, 'option', { text: m.title || m.field, attrs: { value: m.field } }))); }
    this.picker.selectedIndex = Math.min(this.metricIndex, ms.length - 1);
    this.picker.hidden = ms.length < 2;
    const legend = this.legendEl(built, fmt), detail = this.detailEl(built, fmt);
    c.side.textContent = '';
    this.float.textContent = '';
    this.below.textContent = '';
    c.side.hidden = this.mode !== 'ranking';
    if (this.mode === 'ranking') {
      c.side.append(h(doc, 'h4', { cls: 'dc-h', text: 'Legend · ' + (built.scale.type === 'quantile' ? 'quantiles' : 'continuous') }), legend, h(doc, 'h4', { cls: 'dc-h', text: 'Top 10' }), this.rankEl(built, fmt),
        h(doc, 'h4', { cls: 'dc-h', text: 'Selected' }), detail || h(doc, 'p', { cls: 'dc-hint', text: 'Click a ' + (lvl === 'world' ? 'country' : 'region') + ' for its figure and how it was made.' }));
    } else {
      this.float.append(h(doc, 'div', { cls: 'dc-float-legend' }, [h(doc, 'div', { text: built.metric.title || built.metric.field }), legend]));
      if (lvl !== 'world' && this.worldGeo) {
        this.float.appendChild(h(doc, 'button', { cls: 'dc-inset', text: '‹ World · ' + this.placeName(lvl, true), attrs: { type: 'button', 'data-go': 'world', title: 'Back to the world' }, on: { click: () => this.back() } }));
      }
      if (detail) { detail.classList.add('dc-float-card'); this.float.appendChild(detail); }
      if (!c.tableOpen) { this.below.appendChild(this.tableEl(built, fmt, 'dc-sortable')); }
    }
    const nd = built.places.filter((p) => p.v === null).length;
    c.status.textContent = '';
    [h(doc, 'b', { text: lvl === 'world' ? 'world-countries.json' : 'regions/' + lvl + '.json' }), h(doc, 'span', { text: 'Natural Earth ' + (lvl === 'world' ? '1:110m' : '1:10m admin-1') + ' · ' + this.geo.projection }),
      h(doc, 'span', { cls: 'dc-rows', text: built.places.length + (lvl === 'world' ? ' countries' : ' regions') }), h(doc, 'span', { text: nd + ' without data' }),
      built.unmatched ? h(doc, 'span', { text: built.unmatched + ' rows match no place' }) : null,
      h(doc, 'span', { cls: 'dc-hint', text: lvl === 'world' ? 'double-click a country to open its regions' : 'loaded on demand, cached for the session' })].forEach((x) => { if (x) { c.status.appendChild(x); } });
  }

  /** @private the legend: quantile classes with counts (a click keeps one class bright) or the continuous ramp; no data hatched */
  legendEl(built, fmt) {
    const doc = this.c.doc, sc = built.scale;
    const sw = (colour, cls) => { const i = h(doc, 'i', { cls: 'dc-sw dc-sw-box' + (cls ? ' ' + cls : '') }); if (colour) { i.style.setProperty('background', colour); } return i; };
    const rows = [];
    if (sc.type === 'quantile') {
      sc.classes.forEach((k, i) => rows.push(h(doc, 'button', { cls: 'dc-lgq' + (this.highlight === i ? ' dc-on' : ''), attrs: { type: 'button', 'data-class': String(i), 'aria-pressed': String(this.highlight === i) },
        on: { click: () => { this.highlight = this.highlight === i ? null : i; this.draw(); } } }, [sw(k.colour), h(doc, 'span', { text: fmt(k.from) + ' – ' + fmt(k.to) }), h(doc, 'span', { cls: 'dc-n', text: String(k.count) })])));
    } else {
      const ramp = h(doc, 'div', { cls: 'dc-ramp' });
      ramp.style.setProperty('background', 'linear-gradient(to right, ' + this.c.envNow.theme.sequential.join(', ') + ')');
      rows.push(ramp, h(doc, 'div', { cls: 'dc-ramp-ticks' }, [h(doc, 'span', { text: fmt(sc.min) }), h(doc, 'span', { text: fmt(sc.max) })]));
    }
    rows.push(h(doc, 'div', { cls: 'dc-lgq dc-nodata-row' }, [sw(null, 'dc-hatch'), h(doc, 'span', { text: 'No data' }), h(doc, 'span', { cls: 'dc-n', text: String(sc.nodata) })]));
    return h(doc, 'div', { cls: 'dc-lgq-list' }, rows);
  }

  /** @private the top ten with bars */
  rankEl(built, fmt) {
    const doc = this.c.doc, mx = built.ranking.length ? Math.abs(built.ranking[0].v) || 1 : 1;
    return h(doc, 'ol', { cls: 'dc-rank' }, built.ranking.map((r) => {
      const bar = h(doc, 'span', { cls: 'dc-rank-bar' });
      bar.style.setProperty('width', Math.round(100 * Math.abs(r.v) / mx) + '%');
      return h(doc, 'li', {}, [h(doc, 'button', { cls: 'dc-rank-row' + (this.sel === r.code ? ' dc-on' : ''), attrs: { type: 'button', 'data-pick': r.code }, on: { click: () => this.pick(r.code) } },
        [h(doc, 'i', { text: String(r.rank) }), h(doc, 'span', { text: r.name }), h(doc, 'span', { cls: 'dc-rank-track' }, [bar]), h(doc, 'b', { text: fmt(r.v) })])]);
    }));
  }

  /** @private the selected place: its figure, rank and share, Explain and the drill */
  detailEl(built, fmt) {
    const doc = this.c.doc, p = this.sel && built.places.find((x) => x.code === this.sel);
    if (!p) { return null; }
    const withV = built.places.filter((x) => x.v !== null), tot = withV.reduce((s, x) => s + x.v, 0), rank = withV.filter((x) => x.v > (p.v || 0)).length + 1;
    const links = [h(doc, 'button', { cls: 'dc-btn', text: 'Explain', attrs: { type: 'button', 'data-act': 'explain' }, on: { click: () => this.c.explain(this.context(p, built)) } })];
    if (this.geo.level === 'world' && !p.point) { links.unshift(h(doc, 'button', { cls: 'dc-btn dc-go', text: 'Drill into regions ›', attrs: { type: 'button', 'data-drill': p.code }, on: { click: () => this.drill(p.code) } })); }
    return h(doc, 'div', { cls: 'dc-detail', attrs: { 'data-place': p.code } }, [h(doc, 'div', { cls: 'dc-detail-k', text: (built.metric.title || built.metric.field) + ' · ' + p.name }),
      h(doc, 'div', { cls: 'dc-detail-v', text: fmt(p.v) }),
      h(doc, 'div', { cls: 'dc-detail-k', text: p.v === null ? 'No figure here' : 'rank ' + rank + ' of ' + withV.length + ' · ' + (tot ? (100 * p.v / tot).toFixed(1) : '0') + '% of the total' }),
      h(doc, 'div', { cls: 'dc-detail-links' }, links)]);
  }

  /** @private the table of places, sortable by its headings */
  tableEl(built, fmt, cls) {
    const doc = this.c.doc, k = this.sort.k, d = this.sort.d;
    const rows = built.places.slice().sort((a, b) => (k === 'n' ? d * a.name.localeCompare(b.name) : k === 'c' ? d * a.code.localeCompare(b.code) : d * ((a.v === null ? -Infinity : a.v) - (b.v === null ? -Infinity : b.v))));
    const tot = built.places.reduce((s, p) => s + (p.v || 0), 0), mx = Math.max.apply(null, built.places.map((p) => Math.abs(p.v || 0)).concat([1e-9]));
    const head = (key, text) => h(doc, 'th', { attrs: { scope: 'col', 'aria-sort': k === key ? (d < 0 ? 'descending' : 'ascending') : 'none' } }, [h(doc, 'button', { cls: 'dc-sort', text: text + (k === key ? (d < 0 ? ' ▼' : ' ▲') : ''),
      attrs: { type: 'button', 'data-sort': key }, on: { click: () => { this.sort = { k: key, d: this.sort.k === key ? -this.sort.d : -1 }; this.draw(); } } })]);
    return h(doc, 'table', { cls: cls }, [h(doc, 'caption', { text: built.summary }),
      h(doc, 'thead', {}, [h(doc, 'tr', {}, [head('c', 'Code'), head('n', this.geo.level === 'world' ? 'Country' : 'Region'), head('v', built.metric.title || built.metric.field), h(doc, 'th', { text: 'Share', attrs: { scope: 'col' } })])]),
      h(doc, 'tbody', {}, rows.map((p) => {
        const bar = h(doc, 'span', { cls: 'dc-cellbar' });
        bar.style.setProperty('width', Math.round(60 * Math.abs(p.v || 0) / mx) + 'px');
        return h(doc, 'tr', { cls: this.sel === p.code ? 'dc-on' : '', attrs: { 'data-pick': p.code }, on: { click: () => this.pick(p.code) } }, [h(doc, 'td', { text: p.code }), h(doc, 'th', { text: p.name, attrs: { scope: 'row' } }),
          h(doc, 'td', { cls: 'dc-num' }, [bar, fmt(p.v)]), h(doc, 'td', { cls: 'dc-num', text: p.v === null || !tot ? '—' : (100 * p.v / tot).toFixed(1) + '%' })]);
      }))]);
  }

  /** @private a place's Explain context */
  context(p, built) {
    const rows = this.rows();
    return { kind: 'map', title: this.c.spec.title || '', series: built.metric.title || built.metric.field, field: built.metric.field, value: p.v, text: built.fmt(p.v), category: p.name, place: p.code,
      level: this.geo.level, rows: p.r.map((i) => rows[i]), ids: p.ids };
  }

  /**
   * Selects a place: its figure in the side column or card, its rows to a linked grid.
   * @param {string} code
   * @param {{explain: boolean}} [o]
   * @returns {void}
   */
  pick(code, o) {
    const p = this.built && this.built.places.find((x) => x.code === code);
    if (!p) { return; }
    this.sel = code;
    this.c.emit('placeSelected', { code: code, name: p.name, value: p.v, level: this.geo.level });
    if (p.ids.length) { this.c.setSelection(p.ids); } else { this.draw(); }
    if (o && o.explain) { this.c.explain(this.context(p, this.built)); }
  }

  /** @private a click on the map */
  onClick(p) {
    const code = p && p.name;
    if (!code || code.indexOf('__') === 0) { return; }
    this.pick(code, { explain: this.c.spec.onClick === 'explain' && !!this.c.o.explain });
  }

  /** @private + − and reset */
  zoom(what) {
    const g = (this.c.engine.getOption().geo || [])[0] || {}, z = g.zoom || 1;
    this.c.engine.setOption({ geo: what === 'reset' ? { zoom: 1, center: null } : { zoom: Math.max(0.8, Math.min(24, what === 'in' ? z * 1.4 : z / 1.4)) } });
  }

  /**
   * Switches the mode: ranking (g3 A) or table (g3 B).
   * @param {('ranking'|'table')} mode
   * @returns {void}
   */
  setMode(mode) {
    if (MAP_MODES.indexOf(mode) < 0) { return; }
    this.mode = mode;
    this.c.el.setAttribute('data-map-mode', mode);
    this.draw();
    this.c.engine.resize();
    this.c.emit('modeChanged', { mapMode: mode });
  }

  /**
   * The table of places (the CSV export).
   * @returns {{columns: Object[], rows: Array<Array<*>>}}
   */
  table() { return this.built ? this.built.table : { columns: [], rows: [] }; }

  /**
   * What the SVG export changes: the hatching (a canvas pattern) drawn as the plain no-data colour.
   * @returns {Object}
   */
  svgOption() {
    const th = this.c.envNow.theme, o = this.built.option;
    return { geo: [Object.assign({}, o.geo, { itemStyle: Object.assign({}, o.geo.itemStyle, { areaColor: th.nodata }), regions: o.geo.regions.map((r) => (r.itemStyle && typeof r.itemStyle.areaColor === 'object' ? Object.assign({}, r, { itemStyle: Object.assign({}, r.itemStyle, { areaColor: th.nodata }) }) : r)) })] };
  }
}
