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
/* The kinds of charts and maps. line, area, waterfall, histogram, scatter, candlestick, surface, graph, map and hbar's donut are
   drawn by drishti-chart's panel charts (and a graph by drishti-graph): one drawing of each, loaded the first time a view has such a
   panel (drishti-core's loader: a page that shows only words and tables never loads a chart engine). hbar's bars and gauge are drawn
   as elements (a bar's width, a meter). Every chart carries a data table, its text alternative, closed until opened. A map draws with
   drishti-chart's geo part and the vendored shapes (or the host's map helper, options.geo); geo: false turns maps off, in words. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { say, toneClass } from './drishti_views-cells.js';
import { marksOf, hasEffects, clickable } from './drishti_views-state.js';

/** The kinds drawn by drishti-chart's panel charts (hbar only in its donut shape). */
export const CHART_KINDS = Object.freeze(['line', 'area', 'waterfall', 'histogram', 'scatter', 'candlestick', 'surface', 'graph', 'map', 'treemap']);

const list = (x) => (Array.isArray(x) ? x : []);
/* a surface point's place among its marks, row by row: the engine's datum is [column, row, value] (contract 1.2) */
const surfaceIndex = (datum, d) => { const v = Array.isArray(datum) ? datum : datum && datum.value; return Array.isArray(v) ? v[1] * list(d && d.x).length + v[0] : -1; };
const str = (v) => (v === null || v === undefined ? '' : String(v));
const LIMIT_ROWS = 500;
/** The chart kinds whose marks fire point-click (a point, a step, a candle, an x position). */
const POINT_CHARTS = { line: true, area: true, scatter: true, waterfall: true, candlestick: true, treemap: true, surface: true };

/**
 * The rows of a chart's data table: its column heads and rows of text (at most 500 rows).
 * @param {string} kind
 * @param {Object} d the panel's data
 * @returns {{columns: string[], rows: string[][]}}
 */
export function chartTable(kind, d) {
  d = d || {};
  let columns = [], rows = [];
  if (kind === 'line' || kind === 'area') {
    const s = list(d.series);
    columns = [''].concat(s.map((x) => str(x && x.label)));
    rows = list(d.x).map((x, i) => [str(x)].concat(s.map((y) => str(list(y && y.values)[i]))));
  } else if (kind === 'treemap') {
    columns = ['', '', ''];
    rows = list(d.cells).map((c) => [str(c && c.group), str(c && c.label), str(c && (c.text || c.value))]);
  } else if (kind === 'hbar') {
    columns = ['', ''];
    rows = list(d.bars).map((b) => [str(b && b.label), str(b && (b.text || b.value))]);
  } else if (kind === 'waterfall') {
    columns = ['', ''];
    rows = list(d.steps).map((s) => [str(s && s.label), str(s && (s.text || s.value))]);
  } else if (kind === 'histogram') {
    columns = ['from', 'to', 'count'];
    rows = list(d.bins).map((b) => [str(b && (b.label || b.from)), str(b && b.to), str(b && b.count)]);
  } else if (kind === 'scatter') {
    columns = ['', str(d.xLabel || 'x'), str(d.yLabel || 'y')];
    rows = list(d.points).map((p) => [str(p && (p.label || p.group)), str(p && p.x), str(p && p.y)]);
  } else if (kind === 'candlestick') {
    columns = ['', 'open', 'high', 'low', 'close'];
    rows = list(d.candles).map((c) => [str(c && c.x), str(c && c.open), str(c && c.high), str(c && c.low), str(c && c.close)]);
  } else if (kind === 'surface') {
    columns = [''].concat(list(d.x).map(str));
    rows = list(d.y).map((y, i) => [str(y)].concat(list(list(d.z)[i]).map(str)));
  } else if (kind === 'graph') {
    columns = ['from', 'to', ''];
    const label = new Map(list(d.nodes).map((n) => [str(n && n.id), str(n && (n.label || n.id))]));
    rows = list(d.edges).map((e) => [label.get(str(e && e.from)) || str(e && e.from), label.get(str(e && e.to)) || str(e && e.to), str(e && e.label)]);
  } else if (kind === 'map') {
    const sh = d.shade || {};
    columns = ['', str(sh.label)];
    rows = list(sh.items).map((it) => [str(it && it.code), str(it && (it.text || it.value))]);
    list(d.points).forEach((pt) => { if (pt) { rows.push([str(pt.label || (pt.lat + ', ' + pt.lon)), str(pt.text || pt.value)]); } });   // contract 1.1: bubbles
  }
  return { columns: columns, rows: rows.slice(0, LIMIT_ROWS) };
}

function dataTable(ctx, kind, d) {
  const doc = ctx.doc, t = chartTable(kind, d);
  const table = h(doc, 'table', { cls: 'dv-tbl' }, [h(doc, 'thead', {}, [h(doc, 'tr', {}, t.columns.map((c) => h(doc, 'th', { text: c, attrs: { scope: 'col' } })))]),
    h(doc, 'tbody', {}, t.rows.map((r) => h(doc, 'tr', {}, r.map((c, i) => h(doc, i ? 'td' : 'th', { text: c, attrs: i ? null : { scope: 'row' } })))))]);
  return h(doc, 'details', { cls: 'dv-data' }, [h(doc, 'summary', { text: say(ctx.messages, 'dataTable') }), h(doc, 'div', { cls: 'dv-tbl-scroll' }, [table])]);
}

const SWATCHES = ['link', 'accent', 'ok', 'neg', 'warn', 'bad', 'muted', 'ink'];
const TONES = new Set(SWATCHES.concat(['pos']));
/**
 * The legend of a line or an area of several series (contract 1.2 layers or by), as elements: each series' name and its colour (the
 * series' tone, else the palette's place, as drishti-chart draws it); a bar, an area or a point says so in its swatch's shape.
 */
function legendOf(ctx, kind, d) {
  const s = (kind === 'line' || kind === 'area') ? list(d && d.series) : [];
  if (s.length < 2) { return null; }
  return h(ctx.doc, 'ul', { cls: 'dv-legend', attrs: { 'aria-label': say(ctx.messages, 'legend') } }, s.map((x, i) => {
    const tone = x && TONES.has(x.tone) ? x.tone : i > 0 ? SWATCHES[i % SWATCHES.length] : 'link';
    return h(ctx.doc, 'li', {}, [h(ctx.doc, 'span', { cls: 'dv-sw dv-sw-' + ((x && x.mark) || 'line'), style: { '--dv-sw': 'var(--d-' + tone + ')' } }),
      str(x && x.label) + (x && x.axis === 'right' ? ' (right)' : '')]);
  }));
}

/** A chart drawn by drishti-chart's panel charts. */
function engineKind(kind) {
  return {
    chart: true,
    paint(body, panel, ctx) {
      const doc = ctx.doc;
      const k = kind === 'hbar' ? 'donut' : kind;
      const el = h(doc, 'div', { cls: 'dv-chart dv-chart-' + k, attrs: { 'data-r-chart': '', role: 'img', 'aria-label': panel.title || panel.id, 'data-view': panel.data.view || null } });
      el.__drishti = { kind: k, data: panel.data };
      let table = dataTable(ctx, kind, panel.data);
      const legend = legendOf(ctx, kind, panel.data);
      const box = h(doc, 'div', { cls: 'dv-chart-box' }, [el, legend, table]);
      body.appendChild(box);
      let gone = false;
      const words = (w) => { el.removeAttribute('data-r-chart'); el.className = 'dv-chart-off'; el.textContent = w; };
      if (kind === 'map' && !ctx.can('geo')) { words(say(ctx.messages, 'noMaps')); } else {
        ctx.charts().then((pc) => { if (!gone) { return pc.draw(box); } return null; }).then(() => { if (!gone) { wire(); } })
          .catch(() => { if (!gone) { words(say(ctx.messages, 'noCharts')); } });
      }
      /* a click on a point, a step, a candle or a line's x position fires point-click on that mark (contract 1.2); the engine's click
         names the datum, whose own object (or its index) is the mark's place */
      let cur = panel;
      const wire = () => {
        const win = doc.defaultView, E = win && win.echarts, chart = E && E.getInstanceByDom && E.getInstanceByDom(el);
        if (!chart || el.__dvMarks || !(POINT_CHARTS[kind] || kind === 'hbar')) { return; }
        el.__dvMarks = true;
        chart.on('click', (e) => {
          const marks = marksOf(cur);
          if (!marks.some(hasEffects)) { return; }
          const d = e && e.data ? e.data : null;
          const find = (m) => { let i = marks.indexOf(m); if (i < 0 && m) { const t = JSON.stringify(m); i = marks.findIndex((x) => JSON.stringify(x) === t); } return i; };   // the engine may hold a copy
          const trig = kind === 'hbar' ? 'bar-click' : 'point-click';                // the donut's slices are the bars (contract 1.2)
          let ix = kind === 'hbar' ? find(d && d.bar) : kind === 'scatter' ? find(d && d.point) : kind === 'waterfall' ? find(d && d.step) : kind === 'treemap' ? find(d && d.cell) : kind === 'surface' ? surfaceIndex(d, cur.data) : e.dataIndex;
          if (kind === 'candlestick' && e.seriesIndex > 0) { ix = -1; }
          if (Number.isInteger(ix) && ix >= 0) { ctx.fire(String(cur.id), trig, ix); }
        });
        if (marksOf(cur).some(hasEffects)) { el.classList.add('dv-pick'); }
      };
      return {
        update(p) {
          cur = p;
          el.__drishti = { kind: k, data: p.data };
          const fresh = dataTable(ctx, kind, p.data);
          fresh.open = table.open;
          table.replaceWith(fresh);
          table = fresh;
          if (el.hasAttribute('data-r-chart')) { ctx.charts().then((pc) => (gone ? null : pc.update(el))).catch(() => undefined); }
        },
        dispose() {
          gone = true;
          const win = doc.defaultView, E = win && win.echarts, c = E && E.getInstanceByDom && E.getInstanceByDom(el);
          if (c) { c.dispose(); }
          if (el.__drishtiGraph && el.__drishtiGraph.destroy) { el.__drishtiGraph.destroy(); }
          box.remove();
        }
      };
    }
  };
}

/* ---- hbar: bars scaled to the largest, as elements; the donut shape by the engine ---- */
const bars = {
  paint(body, panel, ctx) {
    if (panel.data.shape === 'donut') { return engineKind('hbar').paint(body, panel, ctx); }
    const doc = ctx.doc, d = panel.data, items = list(d.bars);
    const max = Math.max.apply(null, items.map((b) => Math.abs(Number(b && b.value) || 0)).concat([0]));
    const ul = h(doc, 'ul', { cls: 'dv-bars' }), marks = marksOf(panel);
    items.forEach((b) => {
      b = b || {};
      const share = max > 0 ? Math.abs(Number(b.value) || 0) / max : 0;
      const bar = h(doc, 'span', { cls: ['dv-bar', toneClass(b.tone) || 'dv-t-accent'].join(' '), style: { '--dv-share': String(Math.round(share * 1000) / 1000) } });
      const li = h(doc, 'li', {}, [h(doc, 'span', { cls: 'dv-bar-label', text: str(b.label) }), h(doc, 'span', { cls: 'dv-bar-track' }, [bar]),
        h(doc, 'span', { cls: 'dv-bar-text', text: str(b.text || b.value) })]);
      clickable(ctx, li, panel, 'bar-click', marks.indexOf(b), b);               // a bar that carries effects (contract 1.2)
      ul.appendChild(li);
    });
    body.appendChild(ul);
    if (typeof d.total === 'string' && d.total) { body.appendChild(h(doc, 'p', { cls: 'dv-more', text: d.total })); }
    return { dispose() { while (body.firstChild) { body.removeChild(body.firstChild); } } };
  }
};

/* ---- gauge: a level out of a maximum ---- */
const gauge = {
  paint(body, panel, ctx) {
    const doc = ctx.doc, d = panel.data, v = Number(d.value), max = Number(d.max) || 0;
    const share = max > 0 && isFinite(v) ? Math.max(0, Math.min(1, v / max)) : 0;
    const text = typeof d.text === 'string' && d.text ? d.text : str(d.value);
    const meter = h(doc, 'div', { cls: 'dv-gauge', attrs: { role: 'meter', 'aria-valuemin': '0', 'aria-valuemax': str(max), 'aria-valuenow': isFinite(v) ? str(v) : null,
      'aria-valuetext': text, 'aria-label': typeof d.label === 'string' && d.label ? d.label : (panel.title || panel.id) } },
    [h(doc, 'span', { cls: 'dv-gauge-fill' + (share >= 1 ? ' dv-t-bad' : share >= 0.8 ? ' dv-t-warn' : ' dv-t-accent'), style: { '--dv-share': String(Math.round(share * 1000) / 1000) } })]);
    const box = h(doc, 'div', { cls: 'dv-gauge-box' }, [h(doc, 'div', { cls: 'dv-gauge-text', text: text }), meter,
      typeof d.label === 'string' && d.label ? h(doc, 'div', { cls: 'dv-metric-caption', text: d.label }) : null]);
    body.appendChild(box);
    clickable(ctx, box, panel, 'point-click', 0, marksOf(panel)[0]);            // the gauge is one mark (contract 1.2)
    return { dispose() { box.remove(); } };
  }
};

/* ---- bullet (contract 1.2): one figure on a band with its bounds and warning zones, as elements ---- */
const bullet = {
  paint(body, panel, ctx) {
    const doc = ctx.doc, d = panel.data, min = Number(d.min) || 0, max = Number(d.max), span = max > min ? max - min : 1;
    const at = (v) => String(Math.round(Math.max(0, Math.min(1, (Number(v) - min) / span)) * 1000) / 1000);
    const track = h(doc, 'div', { cls: 'dv-bullet', attrs: { role: 'meter', 'aria-valuemin': str(min), 'aria-valuemax': str(max),
      'aria-valuenow': d.value === null || d.value === undefined ? null : str(d.value), 'aria-valuetext': str(d.text), 'aria-label': str(d.label || panel.title || panel.id) } },
    list(d.bands).map((b) => h(doc, 'span', { cls: 'dv-bullet-band ' + (toneClass(b && b.tone) || ''), style: { '--dv-from': at(Math.min(b.from, b.to)), '--dv-to': at(Math.max(b.from, b.to)) } }))
      .concat([d.lower, d.upper].filter((v) => v !== null && v !== undefined).map((v) => h(doc, 'span', { cls: 'dv-bullet-bound', style: { '--dv-at': at(v) } })))
      .concat(d.value === null || d.value === undefined ? [] : [h(doc, 'span', { cls: 'dv-bullet-mark ' + (toneClass(d.tone) || 'dv-t-accent'), style: { '--dv-at': at(d.value) } })]));
    const scale = h(doc, 'div', { cls: 'dv-bullet-scale' }, [h(doc, 'span', { text: str(d.lowerText) }), h(doc, 'span', { text: str(d.upperText) })]);
    const box = h(doc, 'div', { cls: 'dv-gauge-box dv-bullet-box' }, [h(doc, 'div', { cls: 'dv-gauge-text ' + (toneClass(d.tone) || ''), text: str(d.text) }), track, scale,
      d.label || d.caption ? h(doc, 'div', { cls: 'dv-metric-caption', text: str(d.caption || d.label) }) : null]);
    body.appendChild(box);
    return { dispose() { box.remove(); } };
  }
};

CHART_KINDS.forEach((k) => defineKind(k, engineKind(k)));
defineKind('bullet', bullet);
defineKind('hbar', bars);
defineKind('gauge', gauge);
