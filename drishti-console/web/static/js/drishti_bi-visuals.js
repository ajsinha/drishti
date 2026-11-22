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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Rupaka phase 3 (ARCHITECTURE.md §28 › Rūpaka: the original design and every phase as built, section 8): the charts and cards of a report, drawn from one visual's query result. The
   charts are drishti-chart's (Drishti Elements, its report part: reportOption and drawReport build and draw them from the answer, with the keyboard's
   marks, hover, click, live redraw in place and the brush); this file only gives them the report's adapter (theme tokens, palette, number formats,
   conditional colours, the callbacks that make a mark interactive). Cards are plain elements. Every renderer takes the host element, the visual (as the
   report model has it), the result ({columns, rows, meta, highlight}) and a context, and returns {dispose, resize}. Tables, matrices, slicers and the static
   visuals are in drishti_bi-grids.js. Everything is built with DOM calls and textContent, never markup strings. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = (B.visuals = B.visuals || {});
  const R = () => window.Drishti.elements && window.Drishti.elements.chart_report;   // drishti-chart's report part (the page loads it beside the bundle)

  /** Reads a result into helpers: the index of a column by name, the rows, whether a column is masked (drishti-chart's reportTable). */
  V.table = (res) => R().reportTable(res);

  /** The colour a conditional format gives a value of a measure, or null. */
  V.conditional = function (spec, measure, value) {
    const list = (spec.format && spec.format.conditional) || [];
    for (let i = 0; i < list.length; i++) {
      const c = list[i];
      if (c.measure !== measure || typeof value !== 'number') { continue; }
      const v = Number(c.value);
      const hit = { lt: value < v, le: value <= v, gt: value > v, ge: value >= v, eq: value === v, ne: value !== v }[c.op];
      if (hit) { return c.colour || null; }
    }
    return null;
  };

  V.formatOf = (ctx, name) => (ctx.formats && ctx.formats[name]) || (ctx.index.measures[name] && ctx.index.measures[name].format) || (ctx.index.columns[name] && ctx.index.columns[name].format) || null;
  V.numFmt = (spec, ctx, name) => (spec.format && spec.format.numberFormat) || V.formatOf(ctx, name);

  /** A live redraw (`ctx.update`) of a number that changed: the new value flashes for a moment (the style sheet turns the flash off for people who ask for less motion). */
  V.flashIfChanged = function (host, el, ctx) {
    if (!ctx.update) { return; }
    const old = host.querySelector('.bi-card-value');
    if (old && old.getAttribute('data-value') !== el.getAttribute('data-value')) { el.classList.add('bi-flash'); }
  };

  const CLASSES = { chart: 'bi-chart', empty: 'bi-vis-empty', live: 'visually-hidden', crumb: 'bi-map-crumb', up: 'bi-vis-btn bi-map-up', here: 'bi-map-here', note: 'bi-map-note', cut: 'bi-vis-note bi-multiples-cut' };
  function emptyNote(host, text) {
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text }));
    return { dispose() {}, resize() {} };
  }

  /** The map helper of drishti-chart's geo part, over the vendored shapes (an embedded report names where they are: B.mapsBase). */
  function geo() {
    const E = window.Drishti.elements, P = E && E.chart_geo;
    return P ? P.geoHelper({ base: B.mapsBase || E.core.staticUrl('vendor/echarts/maps/') }) : null;
  }

  /** The report's adapter for drishti-chart's report part: the context, its formats and conditional colours, the class names the style sheet knows. */
  V.env = function (host, spec, ctx) {
    return Object.assign({}, ctx, {
      fmt: B.fmt, compact: B.compact, label: B.label, numFmt: (m) => V.numFmt(spec, ctx, m), styler: V.styler ? (tb) => V.styler(spec, tb, ctx) : null,
      rolling: ctx.rolling || (host.__rolling = host.__rolling || {}), geo: spec.type === 'map' ? geo() : null, classes: CLASSES
    });
  };

  // ---- cards ---------------------------------------------------------------------------------------------------------

  function card(host, spec, ctx, res) {
    const tb = V.table(res);
    const m = spec.wells.values[0];
    const v = tb.rows.length ? tb.rows[0][tb.at(m)] : null;
    const f = spec.format || {};
    const big = h('div', { class: 'bi-card-value', 'data-value': v == null ? '' : String(v), text: B.fmt(v, V.numFmt(spec, ctx, m), tb.isMasked(m)) });
    cardStyle(big, spec, tb, ctx, m, v, tb.rows[0] || null);
    V.flashIfChanged(host, big, ctx);
    B.clear(host).appendChild(h('div', { class: 'bi-card-body' }, big, (spec.title && f.showLabel !== true) || f.showLabel === false ? null : h('div', { class: 'bi-card-label', text: m })));
    return { dispose() {}, resize() {} };
  }

  /** A card's number as its format says: its size, and the colour (or background and icon) of its conditional formatting. */
  function cardStyle(el, spec, tb, ctx, m, v, row) {
    const f = spec.format || {};
    if (typeof f.calloutSize === 'number') { el.style.fontSize = f.calloutSize + 'px'; }
    const st = V.styler(spec, tb, ctx);
    const c = st ? st.cell(m, v, row) : null;
    if (c && c.colour && !c.background) { el.style.color = c.colour; }
    if (c && c.background) { el.style.backgroundColor = c.background; el.style.color = c.colour; el.classList.add('bi-card-filled'); }
    if (c && c.icon) { V.paintCell(el, { icon: c.icon }); }
    if (c) { el.setAttribute('data-cf', c.background || c.colour || (c.icon && c.icon.words) || ''); }
  }

  function multirow(host, spec, ctx, res) {
    const tb = V.table(res);
    const axis = (spec.wells.axis || [])[0];
    const ms = spec.wells.values;
    const list = h('div', { class: 'bi-multirow', role: 'list' });
    if (axis) {
      const ai = tb.at(axis);
      tb.rows.forEach((r) => list.appendChild(h('div', { class: 'bi-multirow-row', role: 'listitem' }, h('strong', { text: r[ai] == null ? '–' : String(r[ai]) }),
        ms.map((m) => h('span', { class: 'bi-multirow-v', 'data-measure': m }, h('small', { text: m }), ' ', B.fmt(r[tb.at(m)], V.numFmt(spec, ctx, m), tb.isMasked(m)))))));
    } else {
      const r = tb.rows[0] || [];
      ms.forEach((m) => list.appendChild(h('div', { class: 'bi-multirow-row', role: 'listitem' }, h('strong', { text: m }), h('span', { class: 'bi-multirow-v', text: B.fmt(r[tb.at(m)], V.numFmt(spec, ctx, m), tb.isMasked(m)) }))));
    }
    B.clear(host).appendChild(list);
    return { dispose() {}, resize() {} };
  }

  function kpi(host, spec, ctx, res) {
    const tb = V.table(res);
    const m = spec.wells.values[0], tm = (spec.wells.target || [])[0], axis = (spec.wells.axis || [])[0];
    const last = tb.rows[tb.rows.length - 1] || [];
    const v = last[tb.at(m)];
    const target = tm ? last[tb.at(tm)] : null;
    const nf = V.numFmt(spec, ctx, m);
    const body = h('div', { class: 'bi-card-body' });
    const value = h('div', { class: 'bi-card-value', 'data-value': v == null ? '' : String(v), text: B.fmt(v, nf, tb.isMasked(m)) });
    cardStyle(value, spec, tb, ctx, m, v, last);
    V.flashIfChanged(host, value, ctx);
    body.appendChild(value);
    if (!spec.title) { body.appendChild(h('div', { class: 'bi-card-label', text: m })); }
    if (typeof v === 'number' && typeof target === 'number' && target !== 0) {
      const pct = (v - target) / Math.abs(target);
      const good = (spec.format || {}).goodWhen === 'lower' ? pct <= 0 : pct >= 0;          // better when higher (the default) or lower, in the theme's good and bad colours
      const line = h('div', { class: 'bi-kpi-target ' + (pct >= 0 ? 'bi-up' : 'bi-down'), 'data-good': good ? 'yes' : 'no', text: (pct >= 0 ? '▲ ' : '▼ ') + Math.abs(Math.round(pct * 1000) / 10) + '% vs target ' + B.fmt(target, nf) });
      if (ctx.theme && (ctx.theme.good || ctx.theme.bad)) { const gb = B.theme.goodBad(ctx); line.style.color = good ? gb.good : gb.bad; }
      body.appendChild(line);
    }
    B.clear(host).appendChild(body);
    if (!axis || tb.rows.length < 2 || !R()) { return { dispose() {}, resize() {} }; }
    const spark = h('div', { class: 'bi-spark', 'aria-hidden': 'true' });
    body.appendChild(spark);
    const handle = R().sparkline(spark, tb.rows.map((r) => r[tb.at(axis)]), tb.rows.map((r) => r[tb.at(m)]), ctx.palette[0]);
    return handle;
  }
  const CARDS = { card, 'multirow-card': multirow, kpi };

  /** Draws the visual if it is a chart or a card; returns the handle, or null when the type is not one of these (drishti_bi-grids.js draws the rest). */
  V.drawChartOrCard = function (host, spec, ctx, res) {
    if (CARDS[spec.type]) { return CARDS[spec.type](host, spec, ctx, res); }
    if (V.elementVisuals && V.elementVisuals[spec.type]) { return V.elementVisuals[spec.type](host, spec, ctx, res); }
    if (!V.isChart(spec.type)) { return null; }
    return R() ? R().drawReport(host, spec, res, V.env(host, spec, ctx)) : emptyNote(host, 'The chart library did not load.');
  };
  V.isChart = (type) => !!((R() && R().REPORT_CHART_KINDS.indexOf(type) >= 0) || (V.elementVisuals && V.elementVisuals[type]));

  /**
   * The data-as-of line of an answer (2.6.7 patch): each table reads its latest date on or before the asked one, so a fact and a dimension
   * may stand at different dates. One date: "Data as of D", with "(asked A: no later data)" when the asked date A is later. Several:
   * "Data as of: trades D1, prices D2 (asked A)". meta: the answer's meta ({dataAsOf: {asked, tables}, dates}); asked: the page's date,
   * used when the answer does not say it. Empty when nothing dated was read.
   */
  V.asOfLine = function (meta, asked) {
    const m = meta || {};
    const da = m.dataAsOf || {};
    const tables = da.tables || {};
    const ask = da.asked || asked || '';
    const days = Array.from(new Set(Object.values(tables))).sort();
    if (days.length > 1) {
      const each = Object.keys(tables).sort().map((t) => t + ' ' + tables[t]).join(', ');
      return 'Data as of: ' + each + (ask ? ' (asked ' + ask + ')' : '');
    }
    const ds = m.dates || [];
    const day = days[0] || ds[ds.length - 1];
    if (!day) { return ''; }
    return 'Data as of ' + day + (ask && ask > day ? ' (asked ' + ask + ': no later data)' : '');
  };
})();
