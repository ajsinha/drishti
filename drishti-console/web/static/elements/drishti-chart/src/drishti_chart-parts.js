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
/* The chart card's parts: the crosshair tooltip as an element (no markup strings, so a strict CSP holds), a mark's figure in words, the
   data table (the accessible alternative of every chart, and what CSV exports), the legend's entries, the outline of selected marks
   (linked with a grid's selection), the zoom (an overview window under the chart and drag-to-zoom), and the SVG of a chart drawn off
   screen. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { formatterFor, alpha, textStyle } from './drishti_chart-theme.js';

/**
 * A mark's figure in words, by its series type (a bar's value, a box's five figures, a bin's range and count, a flow's value).
 * @param {Object} p an engine event's params (seriesType, value, data, percent)
 * @param {function(*): string} fmt the value format
 * @returns {string}
 */
export function valueText(p, fmt) {
  const v = p.value, t = p.seriesType;
  if (t === 'boxplot' && Array.isArray(v)) { const b = v.slice(-5); return 'low ' + fmt(b[0]) + ' · Q1 ' + fmt(b[1]) + ' · median ' + fmt(b[2]) + ' · Q3 ' + fmt(b[3]) + ' · high ' + fmt(b[4]); }
  if (t === 'candlestick' && Array.isArray(v)) { const b = v.slice(-4); return 'open ' + fmt(b[0]) + ' · close ' + fmt(b[1]) + ' · low ' + fmt(b[2]) + ' · high ' + fmt(b[3]); }
  if (t === 'custom' && Array.isArray(v)) { return fmt(v[0]) + ' to ' + fmt(v[1]) + ': ' + v[2]; }
  if (t === 'heatmap' && Array.isArray(v)) { return fmt(v[2]); }
  if (t === 'pie') { return fmt(v) + (p.percent !== undefined ? ' · ' + p.percent + '%' : ''); }
  if (t === 'sankey') { return fmt(p.data && p.data.value !== undefined ? p.data.value : v); }
  if (Array.isArray(v)) { return t === 'scatter' && v.length === 3 ? fmt(v[2]) : fmt(v[1]); }
  return v === null || v === undefined || (typeof v === 'number' && isNaN(v)) ? 'no data' : fmt(v);
}

/**
 * The tooltip: a heading (the axis value or the mark's name) and one line per series with its swatch; an element, built with the DOM.
 * @param {Document} doc
 * @param {(Object|Object[])} params the engine's params (an array for an axis tooltip)
 * @param {{fmt: function(*): string, xfmt: function(*): string, names: function(Object): string}} o
 * @returns {HTMLElement}
 */
export function tooltipElement(doc, params, o) {
  const list = (Array.isArray(params) ? params : [params]).filter((p) => p && !(p.seriesName || '').startsWith('__') && p.seriesName !== '_base');
  const first = list[0] || {};
  let head = o.names ? o.names(first) : first.name;
  if (Array.isArray(params) && first.axisValueLabel !== undefined) { head = first.axisType === 'xAxis.time' || /^\d{10,}$/.test(String(first.axisValue)) ? new Date(first.axisValue).toISOString().slice(0, 10) : o.xfmt(first.axisValue); }
  const box = h(doc, 'div', { cls: 'dc-tip' }, [h(doc, 'div', { cls: 'dc-tip-h', text: head === undefined || head === null ? '' : String(head) })]);
  list.forEach((p) => {
    const sw = h(doc, 'i', { cls: 'dc-tip-sw' });
    const c = typeof p.color === 'string' ? p.color : null;
    if (c) { sw.style.setProperty('background', c); }
    box.appendChild(h(doc, 'div', { cls: 'dc-tip-r' }, [h(doc, 'span', {}, [sw, p.seriesName || '']), h(doc, 'b', { text: valueText(p, o.fmt) })]));
  });
  return box;
}

/**
 * The data table: a caption (the chart's description), the columns' headings and every row formatted as on the chart; the first
 * column is the row's heading.
 * @param {Document} doc
 * @param {{columns: Array<{header: string, format: *}>, rows: Array<Array<*>>}} table
 * @param {string} caption
 * @param {{limit: number}} [o] limit: the most rows drawn (default 2,000; the CSV has every row)
 * @returns {HTMLElement}
 */
export function dataTable(doc, table, caption, o) {
  const fmts = table.columns.map((c) => (c.format ? formatterFor(c.format) : null)), limit = (o && o.limit) || 2000;
  const cell = (v, i) => (v === null || v === undefined ? '—' : fmts[i] ? fmts[i](v) : typeof v === 'number' ? formatterFor()(v) : String(v));
  return h(doc, 'table', { cls: 'dc-dtable' }, [
    h(doc, 'caption', { text: caption }),
    h(doc, 'thead', {}, [h(doc, 'tr', {}, table.columns.map((c) => h(doc, 'th', { text: c.header === undefined || c.header === null ? '' : String(c.header), attrs: { scope: 'col' } })))]),
    h(doc, 'tbody', {}, table.rows.slice(0, limit).map((r) => h(doc, 'tr', {}, r.map((v, i) => h(doc, i === 0 ? 'th' : 'td', { text: cell(v, i), attrs: i === 0 ? { scope: 'row' } : null, cls: typeof v === 'number' ? 'dc-num' : '' })))))
  ]);
}

/**
 * The legend's entries: buttons that hide and show their series (aria-pressed), or plain keys where the entries are not series.
 * @param {Document} doc
 * @param {Array<{name: string, colour: string, style: string}>} entries
 * @param {{toggles: boolean, hidden: Set<string>, onToggle: function(string): void}} o
 * @returns {HTMLElement}
 */
export function legendElement(doc, entries, o) {
  return h(doc, 'div', { cls: 'dc-legend', attrs: { role: o.toggles ? 'group' : 'list', 'aria-label': 'Legend' } }, entries.map((e) => {
    const sw = h(doc, 'i', { cls: 'dc-sw dc-sw-' + (e.style || 'box') });
    sw.style.setProperty(e.style === 'dash' ? 'border-color' : 'background', e.colour);
    if (e.style === 'line') { sw.style.setProperty('background', e.colour); }
    if (!o.toggles) { return h(doc, 'span', { cls: 'dc-lg', attrs: { role: 'listitem' } }, [sw, e.name]); }
    const off = o.hidden.has(e.name);
    return h(doc, 'button', { cls: 'dc-lg' + (off ? ' dc-off' : ''), attrs: { type: 'button', 'aria-pressed': off ? 'false' : 'true', 'data-name': e.name, title: (off ? 'Show ' : 'Hide ') + e.name },
      on: { click: () => o.onToggle(e.name) } }, [sw, e.name]);
  }));
}

/** @private where a data item sits on the chart, for its outline */
function placeOf(s, item, i, option) {
  const v = item.value, xAx = Array.isArray(option.xAxis) ? option.xAxis[0] : option.xAxis, yAx = Array.isArray(option.yAxis) ? option.yAxis[0] : option.yAxis;
  if (s.type === 'heatmap') { return [v[0], v[1]]; }
  if (s.type === 'custom') { return [(v[0] + v[1]) / 2, v[2]]; }
  if (s.type === 'boxplot') { return [i, v[2]]; }
  if (s.type === 'candlestick') { return [i, v[1]]; }
  if (Array.isArray(v)) { return [v[0], v[1]]; }
  if (yAx && yAx.type === 'category') { return [v, i]; }
  return [xAx && xAx.data ? xAx.data[i] : i, v];
}

/**
 * Marks the selected rows' marks: an outline over each (a cartesian chart), or the slices selected (a pie). Rows are matched by id.
 * @param {Object} option the engine's option (changed in place)
 * @param {Set<string>} ids the selected row ids
 * @param {Object} th the chart theme
 * @returns {number} how many marks are marked
 */
export function markSelection(option, ids, th) {
  if (!ids || !ids.size || !option.series) { return 0; }
  const hits = [], has = (it) => it && it.ids && it.ids.some((x) => ids.has(x));
  let n = 0;
  option.series.forEach((s) => {
    if (s.helper || s.name === '__selected') { return; }
    if (s.type === 'pie') { (s.data || []).forEach((it) => { if (has(it)) { it.selected = true; n++; } }); return; }
    if (['line', 'bar', 'scatter', 'heatmap', 'custom', 'boxplot', 'candlestick'].indexOf(s.type) < 0 || s.coordinateSystem === 'geo') { return; }
    (s.data || []).forEach((it, i) => { if (has(it) && it.value !== null && it.value !== undefined) { hits.push(placeOf(s, it, i, option)); n++; } });
  });
  if (hits.length) {
    option.series.push({ type: 'scatter', name: '__selected', helper: true, silent: true, z: 12, data: hits, symbol: 'circle', symbolSize: th.size.symbol + 9, tooltip: { show: false },
      itemStyle: { color: alpha(th.tokens.accent, 0.12), borderColor: th.tokens.accent, borderWidth: 2.5 } });
  }
  return n;
}

/**
 * The overview window of a cartesian chart: an 'inside' zoom on the wheel and a slider with the series' shape under the chart (the
 * card adds drag-to-zoom: dragBrush). A window of indexes ([150, 250]) or values.
 * @param {Object} option changed in place
 * @param {Object} spec
 * @param {Object} built the kind's answer (its table gives an index's value)
 * @param {Object} th
 * @returns {boolean} whether the chart zooms
 */
export function addZoom(option, spec, built, th) {
  const z = spec.zoom;
  if (!z || !option.xAxis || Array.isArray(option.xAxis) || option.xAxis.type === 'value' && option.yAxis && option.yAxis.type === 'category') { return false; }
  const slider = { type: 'slider', xAxisIndex: 0, height: 22, bottom: 6, filterMode: 'none', borderColor: th.border, backgroundColor: th.surface2, fillerColor: alpha(th.tokens.accent, 0.16),
    handleStyle: { color: th.tokens.accent, borderColor: th.tokens.accent }, moveHandleStyle: { color: th.tokens.accent }, textStyle: textStyle(th),
    dataBackground: { lineStyle: { color: th.faint }, areaStyle: { color: alpha(th.faint, 0.2) } }, selectedDataBackground: { lineStyle: { color: th.tokens.accent }, areaStyle: { color: alpha(th.tokens.accent, 0.2) } },
    brushSelect: false, labelFormatter: option.xAxis.type === 'time' ? (v) => new Date(v).toISOString().slice(0, 10) : undefined };
  const inside = { type: 'inside', xAxisIndex: 0, filterMode: 'none', zoomOnMouseWheel: true, moveOnMouseMove: false, moveOnMouseWheel: false };
  if (z.window && built.table.rows.length) {
    const rows = built.table.rows, at = (i) => rows[Math.max(0, Math.min(rows.length - 1, i))][0];
    const val = (i) => (option.xAxis.type === 'time' ? Date.parse(at(i) + 'T00:00:00Z') : option.xAxis.type === 'category' ? Math.max(0, Math.min(rows.length - 1, i)) : at(i));
    const a = typeof z.window[0] === 'number' && z.window[0] <= rows.length ? val(z.window[0]) : z.window[0], b = typeof z.window[1] === 'number' && z.window[1] <= rows.length ? val(z.window[1] - 1) : z.window[1];
    slider.startValue = inside.startValue = a; slider.endValue = inside.endValue = b;
  }
  option.dataZoom = [inside, slider];
  option.grid.bottom = (option.grid.bottom || 10) + 30;
  return true;
}

/**
 * The chart as SVG text, drawn off screen by the engine's server-side renderer at the card's size (the export's SVG).
 * @param {Object} E the engine (echarts)
 * @param {Object} option
 * @param {number} width
 * @param {number} height
 * @returns {string}
 */
export function svgOf(E, option, width, height) {
  const off = E.init(null, null, { renderer: 'svg', ssr: true, width: Math.max(200, Math.round(width)), height: Math.max(150, Math.round(height)) });
  try {
    const o = Object.assign({}, option, { animation: false, dataZoom: (option.dataZoom || []).filter((d) => d.type !== 'inside') });
    off.setOption(o);
    return off.renderToSVGString();
  } finally { off.dispose(); }
}

/**
 * A drag on the grid that picks a range of the axis (linked brushing): a band while it moves, onBrush(from, to) with the axis's own values
 * at the end; the band stays until the returned function clears it.
 * @param {Object} engine the chart's engine instance
 * @param {function(): boolean} active whether the chart brushes now
 * @param {string} colour the band's fill
 * @param {function(*, *): void} onBrush given the axis values at the two ends, lower first
 * @param {('x'|'y')} [axis='x']
 * @returns {function(): void} clears the band
 */
export function rangeBrush(engine, active, colour, onBrush, axis) {
  const zr = engine.getZr(), dim = axis === 'y' ? 'offsetY' : 'offsetX';
  let from = null, moved = false;
  const band = (a, b) => engine.setOption({ graphic: [{ id: 'dc-range', type: 'rect', silent: true, z: 100, shape: axis === 'y' ? { x: 0, y: Math.min(a, b), width: zr.getWidth(), height: Math.abs(b - a) } : { x: Math.min(a, b), y: 0, width: Math.abs(b - a), height: zr.getHeight() }, style: { fill: colour } }] });
  zr.on('mousedown', (e) => { from = active() && engine.containPixel('grid', [e.offsetX, e.offsetY]) ? e[dim] : null; moved = false; });
  zr.on('mousemove', (e) => { if (from !== null && Math.abs(e[dim] - from) > 4) { moved = true; band(from, e[dim]); } });
  zr.on('mouseup', (e) => {
    if (from === null) { return; }
    const a = from, b = e[dim];
    from = null;
    if (!moved) { return; }
    const finder = axis === 'y' ? { yAxisIndex: 0 } : { xAxisIndex: 0 };
    onBrush(engine.convertFromPixel(finder, Math.min(a, b)), engine.convertFromPixel(finder, Math.max(a, b)));
  });
  return () => engine.setOption({ graphic: [{ id: 'dc-range', $action: 'remove' }] });
}

/**
 * Drag-to-zoom on a chart's plot: a drag across draws a band and zooms the x axis to it when the mouse is let go (a click, with no
 * drag, stays a click on a mark).
 * @param {Object} engine the chart's engine instance
 * @param {function(): boolean} active whether the chart zooms now
 * @param {string} colour the band's fill
 * @returns {void}
 */
export function dragBrush(engine, active, colour) {
  const zr = engine.getZr();
  let from = null;
  const band = (x0, x1) => engine.setOption({ graphic: [{ id: 'dc-brush', type: 'rect', silent: true, z: 100, shape: { x: Math.min(x0, x1), y: 0, width: Math.abs(x1 - x0), height: zr.getHeight() }, style: { fill: colour } }] });
  zr.on('mousedown', (e) => { from = active() && engine.containPixel('grid', [e.offsetX, e.offsetY]) ? e.offsetX : null; });
  zr.on('mousemove', (e) => { if (from !== null && Math.abs(e.offsetX - from) > 4) { band(from, e.offsetX); } });
  zr.on('mouseup', (e) => {
    if (from === null) { return; }
    const x0 = from, x1 = e.offsetX;
    from = null;
    engine.setOption({ graphic: [{ id: 'dc-brush', $action: 'remove' }] });
    if (Math.abs(x1 - x0) <= 6) { return; }
    const a = engine.convertFromPixel({ xAxisIndex: 0 }, Math.min(x0, x1)), b = engine.convertFromPixel({ xAxisIndex: 0 }, Math.max(x0, x1));
    engine.dispatchAction({ type: 'dataZoom', dataZoomIndex: 0, startValue: a, endValue: b });
  });
}
