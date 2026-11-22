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
/* drishti-chart's report part, the options: what a report visual (a BI page's chart: a type, its wells and its format) draws from one
   answer the server computed ({columns, rows, meta, highlight}). Pure functions of the visual, the answer and the host's adapter (its
   theme tokens, palette, number formats and conditional colours): bar, column, line, area and combo (stacks, 100%, a second axis, the
   highlighted part over the faded whole, a colour per point from the host's rules), small multiples, pie and donut, waterfall, treemap,
   heatmap, scatter and bubble, gauge, funnel, histogram (bins from the server), box plot (figures from the server), the rolling line of a
   live visual and a card's sparkline. Every number is the server's; the host formats it (env.fmt). Moved here from Drishti's BI
   (drishti_bi-visuals.js, drishti_bi-visuals-format.js, drishti_bi-multiples.js), series for series. */

/**
 * The host's adapter: how the report's page reads, formats and colours what a chart draws.
 * @typedef {Object} ReportEnv
 * @property {Object<string, string>} t the theme tokens (ink, muted, border, surface, surface-2, link, pos, neg, mono, accent-soft)
 * @property {string[]} palette the series colours
 * @property {function(*, string, boolean): string} fmt a value in a named format (null: the mask when masked, else a dash)
 * @property {function(number): string} compact a value for an axis or a tight place
 * @property {function(string): string} numFmt the format name of a measure in this visual
 * @property {function(Object): ({point: function(string, *, Array): (string|null)}|null)} [styler] the conditional colours over a table (reportTable)
 * @property {function(string): string} [label] a field's words
 * @property {{visual: string, values: Array}} [selected] the report's selection
 * @property {boolean} [interactive]
 * @property {boolean} [update] a live redraw (the chart is replaced in place)
 * @property {Object<string, Object>} [rolling] where a live visual keeps its points
 * @property {string} [rollingKey]
 */

const HEX = /^#[0-9a-fA-F]{6}$/;
/** The note of a visual with no rows. */
export const REPORT_NO_DATA = 'No data for this selection. If you expected rows: the filters may exclude them, or none of your roles has access to the rows here (ask the owner of the data).';

/**
 * An answer read into helpers: the index of a column by name, the rows, whether a column is masked.
 * @param {{columns: Array<{name: string}>, rows: Array<Array<*>>, meta: Object}} res
 * @returns {{names: string[], rows: Array<Array<*>>, masked: Set<string>, at: function(string): number, isMasked: function(string): boolean}}
 */
export function reportTable(res) {
  const names = res.columns.map((c) => c.name);
  const masked = new Set((res.meta && res.meta.masked) || []);
  return { names, rows: res.rows, masked, at: (n) => names.indexOf(n), isMasked: (n) => masked.has(n) };
}

/* ---- the format's chart parts --------------------------------------------------------------------------------------------- */

/**
 * The value axis as a format says: minimum, maximum, logarithmic, gridlines, shown or not.
 * @param {Object} axis
 * @param {Object} f the visual's format
 * @returns {Object}
 */
export function valueAxisFormat(axis, f) {
  const va = (f && f.valueAxis) || {};
  if (typeof va.min === 'number') { axis.min = va.min; }
  if (typeof va.max === 'number') { axis.max = va.max; }
  if (va.log) { axis.type = 'log'; axis.logBase = 10; }
  if (va.gridlines === false) { axis.splitLine = Object.assign({}, axis.splitLine, { show: false }); }
  if (va.gridlines === true) { axis.splitLine = Object.assign({}, axis.splitLine, { show: true }); }
  if (va.show === false) { axis.show = false; }
  return axis;
}
/**
 * The category axis as a format says: label rotation, shown or not.
 * @param {Object} axis
 * @param {Object} f
 * @returns {Object}
 */
export function categoryAxisFormat(axis, f) {
  const ca = (f && f.categoryAxis) || {};
  if (typeof ca.rotate === 'number') { axis.axisLabel = Object.assign({}, axis.axisLabel, { rotate: ca.rotate }); }
  if (ca.show === false) { axis.show = false; }
  return axis;
}
/**
 * How many points a label goes on: every point at 100%, else every n-th so that the share labelled is about the density.
 * @param {Object} f
 * @param {number} count
 * @returns {number}
 */
export function labelEvery(f, count) {
  const d = f && typeof f.labelDensity === 'number' ? f.labelDensity : 100;
  return d >= 100 || count <= 1 ? 1 : Math.max(1, Math.round(100 / Math.max(1, d)));
}
/**
 * Where a point's label goes.
 * @param {Object} f
 * @param {boolean} horizontal
 * @returns {string}
 */
export function labelPosition(f, horizontal) {
  const p = f && f.labelPosition;
  if (p === 'inside' || p === 'centre') { return 'inside'; }
  return horizontal ? 'right' : 'top';
}
/**
 * 100% stacking: each category's series as shares of the category's total.
 * @param {Array<{values: Array<number>}>} series
 * @param {number} count
 * @returns {Array<Object>}
 */
export function percentOf(series, count) {
  const totals = new Array(count).fill(0);
  series.forEach((s) => s.values.forEach((v, i) => { if (typeof v === 'number') { totals[i] += Math.abs(v); } }));
  return series.map((s) => Object.assign({}, s, { values: s.values.map((v, i) => (typeof v === 'number' && totals[i] ? v / totals[i] : null)) }));
}
/**
 * A series' colour from the format (seriesColours by series name or measure), else null.
 * @param {Object} f
 * @param {string} name
 * @param {string} measure
 * @returns {string|null}
 */
export function seriesColour(f, name, measure) {
  const m = (f && f.seriesColours) || {};
  const c = m[name] || m[measure];
  return HEX.test(c || '') ? c : null;
}

/* ---- the option's frame ----------------------------------------------------------------------------------------------------- */

/**
 * The option every report chart starts from: palette, text, tooltip, legend and grid in the theme's tokens.
 * @param {Object} spec the visual
 * @param {ReportEnv} env
 * @param {{legend: boolean, horizontal: boolean, extra: Object}} o
 * @returns {Object}
 */
export function reportBase(spec, env, o) {
  const t = env.t;
  const f = spec.format || {};
  return Object.assign({
    color: env.palette,
    animation: false,
    textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 },
    tooltip: { confine: true, renderMode: 'richText', backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } },
    legend: o.legend === false ? { show: false } : { show: true, bottom: 0, textStyle: { color: t.muted, fontSize: 10 }, type: 'scroll' },
    grid: { left: 8, right: 16, top: f.axisTitles && f.axisTitles.y && !o.horizontal ? 28 : 12, bottom: o.legend === false ? 8 : 30, containLabel: true }
  }, o.extra || {});
}
/**
 * An axis in the theme's tokens.
 * @param {Object<string, string>} t
 * @returns {Object}
 */
export function reportAxis(t) {
  return {
    axisLine: { lineStyle: { color: t.border } }, axisTick: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
    splitLine: { lineStyle: { color: t.border, opacity: 0.5 } }, nameTextStyle: { color: t.muted, fontSize: 10 }
  };
}
const tipOf = (spec, env) => reportBase(spec, env, {}).tooltip;
const fmtOf = (env, tb, measure) => (v) => env.fmt(v, env.numFmt(measure), tb.isMasked(measure));

function valueAxis(spec, env, measure) {
  const t = env.t, nf = env.numFmt(measure);
  return Object.assign({ type: 'value', name: (spec.format && spec.format.axisTitles && spec.format.axisTitles.y) || '' }, reportAxis(t),
    { axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, formatter: (v) => (nf === 'pct' ? env.fmt(v, 'pct') : env.compact(v)) } });
}

/* ---- series and marks --------------------------------------------------------------------------------------------------------- */

/**
 * The categories, series and values of a category chart: one series per measure, or one per value of the legend field (the first measure).
 * @param {Object} spec
 * @param {Object} res
 * @param {string[]} [other] more measures (a combo's second axis)
 * @returns {{tb: Object, axis: string, cats: Array, labels: string[], series: Array<{name: string, measure: string, values: Array}>, seen: Map<string, number>}}
 */
export function reportSeries(spec, res, other) {
  const tb = reportTable(res);
  const axis = (spec.wells.axis || spec.wells.rows || [])[0];
  const legend = (spec.wells.legend || [])[0];
  const measures = (spec.wells.values || []).concat(other || []);
  const ai = tb.at(axis);
  const cats = [], labels = [];
  const seen = new Map();
  tb.rows.forEach((r) => { const k = String(r[ai]); if (!seen.has(k)) { seen.set(k, cats.length); cats.push(r[ai]); labels.push(r[ai] == null ? '–' : tb.isMasked(axis) ? '•••' : String(r[ai])); } });
  const series = [];
  if (legend && tb.at(legend) >= 0) {
    const li = tb.at(legend), mi = tb.at(measures[0]);
    const by = new Map();
    tb.rows.forEach((r) => {
      const key = String(r[li]);
      if (!by.has(key)) { by.set(key, { name: key, measure: measures[0], values: new Array(cats.length).fill(null) }); }
      by.get(key).values[seen.get(String(r[ai]))] = r[mi];
    });
    by.forEach((s) => series.push(s));
  } else {
    measures.forEach((m) => {
      const mi = tb.at(m);
      const values = new Array(cats.length).fill(null);
      tb.rows.forEach((r) => { values[seen.get(String(r[ai]))] = r[mi]; });
      series.push({ name: m, measure: m, values });
    });
  }
  return { tb, axis, cats, labels, series, seen };
}

/**
 * The marks of a chart in the order of its first axis (a click, a hover and the keyboard reach them): {value, label, text}.
 * @param {string} field
 * @param {Object} s reportSeries' answer
 * @param {ReportEnv} env
 * @returns {{field: string, items: Array<{value: *, label: string, text: string}>, byName: function(string): Object}}
 */
export function reportMarks(field, s, env) {
  const items = s.cats.map((v, i) => ({
    value: v, label: s.labels[i],
    text: s.labels[i] + ': ' + s.series.map((x) => (s.series.length > 1 ? x.name + ' ' : '') + env.fmt(x.values[i], env.numFmt(x.measure), s.tb.isMasked(x.measure))).join(', ')
  }));
  return { field, items, byName: (n) => items.find((m) => m.label === n) };
}
const noMarks = () => ({ field: null, items: [], byName: () => null });

function tip(env, s) {
  return (p) => {
    const list = Array.isArray(p) ? p : [p];
    const lines = list.filter((x) => x.seriesName !== '__highlight').map((x) => {
      const sr = s.series.find((q) => q.name === x.seriesName) || s.series[0];
      return x.seriesName + ': ' + env.fmt(typeof x.value === 'number' ? x.value : x.value && x.value[1], env.numFmt(sr.measure), s.tb.isMasked(sr.measure));
    });
    return list[0].name + '\n' + lines.join('\n');
  };
}
function selectedSet(env, spec) {
  const s = env.selected && env.selected.visual === spec.id ? env.selected.values.map(String) : [];
  return s.length ? new Set(s) : null;
}
const empty = (text) => ({ empty: text || REPORT_NO_DATA });

/* ---- the kinds ------------------------------------------------------------------------------------------------------------------ */

function category(spec, res, env) {
  const f = spec.format || {};
  const t = env.t;
  const type = spec.type;
  const horizontal = type === 'bar';
  let s = reportSeries(spec, res, type === 'combo' ? spec.wells.secondary || [] : []);
  if (!s.cats.length) { return empty(); }
  const percent = !!f.percent && ['bar', 'column', 'area'].indexOf(type) >= 0;
  if (percent) { s = Object.assign({}, s, { series: percentOf(s.series, s.cats.length) }); }
  const sel = selectedSet(env, spec);
  const hl = res.highlight ? reportSeries(spec, res.highlight, []) : null;
  const fixed = typeof f.colour === 'string' && HEX.test(f.colour) ? f.colour : null;
  const count = s.series.length;
  const secondary = type === 'combo' ? new Set(spec.wells.secondary || []) : new Set();
  const styler = percent || !env.styler ? null : env.styler(s.tb);
  const axisField = s.axis;
  const every = labelEvery(f, s.cats.length);
  const series = [];
  s.series.forEach((sr, si) => {
    const isLine = type === 'line' || type === 'area' || secondary.has(sr.measure);
    const base = seriesColour(f, sr.name, sr.measure) || (fixed && count === 1 ? fixed : env.palette[si % env.palette.length]);
    const rows = s.tb.rows;
    const data = sr.values.map((v, i) => {
      const row = rows.find((r) => String(r[s.tb.at(axisField)]) === String(s.cats[i])) || null;
      const cond = styler ? styler.point(sr.measure, v, row) || styler.point(axisField, s.cats[i], row) : null;
      const faded = (sel && !sel.has(String(s.cats[i]))) || !!hl;
      return { value: v, itemStyle: { color: cond || base, opacity: faded ? 0.35 : 1 } };
    });
    series.push({
      name: sr.name, type: isLine ? 'line' : 'bar', data, yAxisIndex: secondary.has(sr.measure) ? 1 : 0, smooth: !!f.smooth, symbol: 'circle', symbolSize: 5,
      areaStyle: type === 'area' ? { opacity: hl ? 0.1 : 0.25 } : undefined, stack: (f.stacked || percent) && !isLine || percent && type === 'area' ? 'total' : undefined,
      lineStyle: { color: base, width: 2, opacity: hl && isLine ? 0.35 : 1 }, emphasis: { focus: 'none' },
      label: { show: !!f.labels, position: labelPosition(f, horizontal), color: t.ink, fontSize: 10, formatter: (p) => (p.dataIndex % every ? '' : percent ? Math.round(p.value * 100) + '%' : env.compact(p.value)) },
      barMaxWidth: 36
    });
    if (hl) {                                                                       // the highlighted part over the faded whole
      const hs = hl.series[si] || { values: [] };
      const vals = s.cats.map((c) => { const i = hl.seen.get(String(c)); return i == null || !hs.values ? null : hs.values[i]; });
      series.push(isLine ? { name: '__highlight', type: 'line', data: vals, smooth: !!f.smooth, symbol: 'circle', symbolSize: 6, lineStyle: { color: base, width: 3 }, itemStyle: { color: base },
        areaStyle: type === 'area' ? { opacity: 0.3, color: base } : undefined, silent: true, z: 5, tooltip: { show: false }, yAxisIndex: secondary.has(sr.measure) ? 1 : 0 }
        : { name: '__highlight', type: 'bar', data: vals.map((v) => ({ value: v, itemStyle: { color: base } })), barGap: '-100%', barMaxWidth: 36, silent: true, z: 5, tooltip: { show: false } });
    }
  });
  const cat = categoryAxisFormat(Object.assign({ type: 'category', data: s.labels, inverse: horizontal, name: (f.axisTitles && f.axisTitles.x) || '' }, reportAxis(t)), f);
  const val = valueAxisFormat(valueAxis(spec, env, s.series[0].measure), f);
  if (percent) { val.max = 1; val.axisLabel = Object.assign({}, val.axisLabel, { formatter: (v) => Math.round(v * 100) + '%' }); }
  const axes = horizontal ? { xAxis: val, yAxis: cat } : { xAxis: cat, yAxis: type === 'combo' && secondary.size ? [val, valueAxisFormat(valueAxis(spec, env, [...secondary][0]), {})] : val };
  const legendShown = !!(count > 1 || (spec.wells.legend || []).length) && f.legend !== 'none' && f.legend !== false;
  const option = reportBase(spec, env, { horizontal, legend: legendShown, extra: Object.assign(axes, { series, tooltip: Object.assign(tipOf(spec, env), { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: tip(env, s) }) }) });
  if (legendShown) {
    option.legend = Object.assign({}, option.legend, f.legend === 'right' ? { right: 0, top: 'middle', orient: 'vertical', bottom: undefined } : f.legend === 'top' ? { top: 0, bottom: undefined } : {});
    if (f.legend === 'top') { option.grid.top = 28; option.grid.bottom = 8; }
  }
  return { option, marks: reportMarks(s.axis, s, env) };
}

function multiples(spec, res, env) {
  const f = spec.format || {};
  const mf = f.multiples || {};
  const split = (spec.wells.split || [])[0];
  const tb = reportTable(res);
  const si = tb.at(split);
  const whole = Object.assign({}, spec, { wells: Object.assign({}, spec.wells, { split: [] }) });
  if (si < 0 || !tb.rows.length) { return category(whole, res, env); }
  const groups = new Map();
  tb.rows.forEach((r) => { const k = r[si] == null ? '–' : String(r[si]); if (!groups.has(k)) { groups.set(k, []); } groups.get(k).push(r); });
  const max = Math.max(2, Math.min(24, mf.max || 12));
  const keys = [...groups.keys()].sort().slice(0, max);
  const cols = Math.max(1, Math.min(6, mf.columns || Math.min(3, keys.length)));
  const rowsN = Math.ceil(keys.length / cols);
  const t = env.t;
  const horizontal = spec.type === 'bar';
  const shared = mf.sharedAxis !== false;
  const grids = [], xAxes = [], yAxes = [], titles = [], series = [];
  let lo = 0, hi = 0;
  const parts = keys.map((k) => reportSeries(whole, Object.assign({}, res, { rows: groups.get(k) }), []));
  parts.forEach((s) => s.series.forEach((sr) => sr.values.forEach((v) => { if (typeof v === 'number') { lo = Math.min(lo, v); hi = Math.max(hi, v); } })));
  const allCats = [];
  parts.forEach((s) => s.labels.forEach((l) => { if (allCats.indexOf(l) < 0) { allCats.push(l); } }));
  const gapX = 3, gapY = 4, head = Math.min(9, 24 / rowsN);                       // percentages of the chart: the gaps and the line for each small chart's title
  const w = (100 - gapX * (cols + 1)) / cols, hgt = (100 - gapY * (rowsN + 1)) / rowsN;
  keys.forEach((k, i) => {
    const c = i % cols, r = Math.floor(i / cols);
    const left = gapX + c * (w + gapX), top = gapY + r * (hgt + gapY);
    grids.push({ left: left + '%', top: (top + head) + '%', width: w + '%', height: Math.max(5, hgt - head) + '%', containLabel: true });
    titles.push({ text: k, left: (left + w / 2) + '%', top: top + '%', textAlign: 'center', textStyle: { color: t.ink, fontSize: 11, fontWeight: 600 } });
    const s = parts[i];
    const values = (sr) => allCats.map((l) => { const j = s.labels.indexOf(l); return j < 0 ? null : sr.values[j]; });
    const cat = categoryAxisFormat(Object.assign({ type: 'category', data: allCats, gridIndex: i, inverse: horizontal }, reportAxis(t), { axisLabel: { color: t.muted, fontSize: 9, hideOverlap: true } }), f);
    const val = valueAxisFormat(Object.assign({ type: 'value', gridIndex: i, splitNumber: 3 }, reportAxis(t), { axisLabel: { color: t.muted, fontSize: 9, formatter: (v) => env.compact(v) } }), f);
    if (shared && val.min === undefined) { val.min = lo; }
    if (shared && val.max === undefined) { val.max = hi; }
    if (horizontal) { xAxes.push(val); yAxes.push(cat); } else { xAxes.push(cat); yAxes.push(val); }
    s.series.forEach((sr, sj) => {
      const isLine = spec.type === 'line' || spec.type === 'area';
      const colour = seriesColour(f, sr.name, sr.measure) || env.palette[sj % env.palette.length];
      series.push({ name: sr.name, type: isLine ? 'line' : 'bar', xAxisIndex: i, yAxisIndex: i, data: values(sr), stack: f.stacked && !isLine ? 'total-' + i : undefined, smooth: !!f.smooth,
        symbol: 'circle', symbolSize: 4, areaStyle: spec.type === 'area' ? { opacity: 0.25 } : undefined, itemStyle: { color: colour }, lineStyle: { color: colour, width: 2 }, barMaxWidth: 24,
        label: { show: !!f.labels, position: horizontal ? 'right' : 'top', color: t.ink, fontSize: 9, formatter: (p) => env.compact(p.value) } });
    });
  });
  const legendShown = (parts[0] && parts[0].series.length > 1) && f.legend !== 'none';
  const option = reportBase(spec, env, { legend: legendShown, extra: { grid: grids, xAxis: xAxes, yAxis: yAxes, title: titles, series,
    tooltip: Object.assign(tipOf(spec, env), { trigger: 'axis', axisPointer: { type: 'shadow' } }) } });
  const first = parts[0];
  const rawOf = new Map();                                              // the marks are the categories of every small chart
  parts.forEach((s) => s.labels.forEach((l, j) => { if (!rawOf.has(l)) { rawOf.set(l, s.cats[j]); } }));
  const marks = reportMarks(first.axis, { tb, axis: first.axis, cats: allCats.map((l) => rawOf.get(l)), labels: allCats, series: [] }, env);
  const words = env.label ? env.label(split) : split;
  return { option, marks, alt: spec.alt || (spec.title || spec.type) + ': ' + keys.length + ' small charts, one per ' + words,
    multiples: { count: keys.length, split, rows: rowsN, cut: groups.size > keys.length ? { shown: keys.length, of: groups.size, words } : null } };
}

function pie(spec, res, env) {
  const f = spec.format || {};
  const s = reportSeries(spec, res, []);
  if (!s.cats.length) { return empty(); }
  const sel = selectedSet(env, spec);
  const sr = s.series[0];
  const total = sr.values.reduce((a, v) => a + (typeof v === 'number' ? Math.max(v, 0) : 0), 0);
  const data = s.labels.map((l, i) => ({ name: l, value: sr.values[i], itemStyle: Object.assign({ opacity: sel && !sel.has(String(s.cats[i])) ? 0.35 : 1 }, seriesColour(f, l, l) ? { color: seriesColour(f, l, l) } : {}) }));
  const lab = { percent: '{d}%', value: (p) => env.compact(p.value), name: '{b}', 'name-percent': '{b}: {d}%' }[f.labelStyle || 'name-percent'];
  const option = reportBase(spec, env, {
    legend: f.legend !== 'none', extra: {
      tooltip: Object.assign(tipOf(spec, env), { trigger: 'item', formatter: (p) => p.name + ': ' + fmtOf(env, s.tb, sr.measure)(p.value) + ' (' + (total ? Math.round(100 * p.value / total) : 0) + '%)' }),
      series: [{ type: 'pie', radius: spec.type === 'donut' ? ['45%', '70%'] : [0, '70%'], center: ['50%', '45%'], data, label: { show: !!f.labels, color: env.t.ink, fontSize: 10, formatter: lab }, itemStyle: { borderColor: env.t.surface, borderWidth: 1 } }]
    }
  });
  return { option, marks: reportMarks(s.axis, s, env) };
}

function waterfall(spec, res, env) {
  const f = spec.format || {};
  const t = env.t;
  const s = reportSeries(spec, res, []);
  if (!s.cats.length) { return empty(); }
  const sr = s.series[0];
  const steps = [];
  let run = 0;
  sr.values.forEach((v, i) => { const d = typeof v === 'number' ? v : 0; steps.push({ label: s.labels[i], from: run, to: run + d, delta: d }); run += d; });
  steps.push({ label: 'Total', from: 0, to: run, delta: run, total: true });
  const item = (params, api) => {
    const i = api.value(0), a = api.coord([i, api.value(1)]), b = api.coord([i, api.value(2)]);
    const w = api.size([1, 0])[0] * 0.6;
    return { type: 'rect', shape: { x: a[0] - w / 2, y: Math.min(a[1], b[1]), width: w, height: Math.max(1, Math.abs(a[1] - b[1])) }, style: api.style({ fill: steps[i].total ? t.link : steps[i].delta >= 0 ? t.pos : t.neg }) };
  };
  const option = reportBase(spec, env, {
    legend: false, extra: {
      xAxis: Object.assign({ type: 'category', data: steps.map((x) => x.label) }, reportAxis(t)), yAxis: valueAxis(spec, env, sr.measure),
      tooltip: Object.assign(tipOf(spec, env), { trigger: 'item', formatter: (p) => { const x = steps[p.value[0]]; return x.label + '\n' + (x.total ? 'Total ' : 'Change ') + fmtOf(env, s.tb, sr.measure)(x.delta); } }),
      series: [{ type: 'custom', renderItem: item, data: steps.map((x, i) => [i, x.from, x.to]), encode: { x: 0, y: [1, 2] }, label: { show: !!f.labels, position: 'top' } }]
    }
  });
  return { option, marks: reportMarks(s.axis, s, env) };
}

function treemap(spec, res, env) {
  const tb = reportTable(res);
  const axes = spec.wells.axis || [];
  const m = spec.wells.values[0], mi = tb.at(m);
  const cols = axes.map((a) => tb.at(a));
  if (!tb.rows.length) { return empty(); }
  const roots = new Map();
  tb.rows.forEach((r) => {
    const v = typeof r[mi] === 'number' ? Math.max(r[mi], 0) : 0;
    const k0 = String(r[cols[0]]);
    if (!roots.has(k0)) { roots.set(k0, { name: k0, raw: r[cols[0]], value: 0, children: [] }); }
    const node = roots.get(k0);
    node.value += v;
    if (cols.length > 1) { node.children.push({ name: String(r[cols[1]]), raw: r[cols[1]], value: v }); }
  });
  const data = [...roots.values()].map((n) => (cols.length > 1 ? n : { name: n.name, value: n.value, raw: n.raw }));
  const f = fmtOf(env, tb, m);
  const items = data.map((n) => ({ value: n.raw !== undefined ? n.raw : n.name, label: n.name, text: n.name + ': ' + f(n.value) }));
  const option = reportBase(spec, env, {
    legend: false, extra: {
      tooltip: Object.assign(tipOf(spec, env), { formatter: (p) => p.name + ': ' + f(p.value) }),
      series: [{ type: 'treemap', data, roam: false, nodeClick: false, breadcrumb: { show: false }, label: { color: env.t.ink, fontSize: 11 }, itemStyle: { borderColor: env.t.surface, borderWidth: 1 }, levels: [{ itemStyle: { borderWidth: 2, borderColor: env.t.surface, gapWidth: 2 } }] }]
    }
  });
  return { option, marks: { field: axes[0], items, byName: (n) => items.find((x) => x.label === n) } };
}

function heatmap(spec, res, env) {
  const tb = reportTable(res);
  const xf = spec.wells.axis[0], yf = spec.wells.legend[0], m = spec.wells.values[0];
  const xi = tb.at(xf), yi = tb.at(yf), mi = tb.at(m);
  if (!tb.rows.length) { return empty(); }
  const xs = [], ys = [];
  tb.rows.forEach((r) => { if (xs.indexOf(String(r[xi])) < 0) { xs.push(String(r[xi])); } if (ys.indexOf(String(r[yi])) < 0) { ys.push(String(r[yi])); } });
  const vals = tb.rows.map((r) => [xs.indexOf(String(r[xi])), ys.indexOf(String(r[yi])), r[mi]]);
  const nums = vals.map((v) => v[2]).filter((v) => typeof v === 'number');
  const t = env.t;
  const option = reportBase(spec, env, {
    legend: false, extra: {
      grid: { left: 8, right: 16, top: 10, bottom: 46, containLabel: true },
      xAxis: Object.assign({ type: 'category', data: xs, splitArea: { show: true } }, reportAxis(t)), yAxis: Object.assign({ type: 'category', data: ys, splitArea: { show: true } }, reportAxis(t)),
      visualMap: { min: Math.min(0, ...nums), max: Math.max(1, ...nums), calculable: false, orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: [t['surface-2'] || t.surface, t.link] }, textStyle: { color: t.muted, fontSize: 10 } },
      tooltip: Object.assign(tipOf(spec, env), { formatter: (p) => xs[p.value[0]] + ' / ' + ys[p.value[1]] + ': ' + fmtOf(env, tb, m)(p.value[2]) }),
      series: [{ type: 'heatmap', data: vals, label: { show: !!(spec.format && spec.format.labels), color: t.ink, fontSize: 10, formatter: (p) => env.compact(p.value[2]) } }]
    }
  });
  const items = xs.map((x) => ({ value: x, label: x, text: x }));
  return { option, marks: { field: xf, items, byName: (n) => items.find((q) => q.label === n) } };
}

function scatter(spec, res, env) {
  const tb = reportTable(res);
  const d = spec.wells.details[0], xm = spec.wells.x[0], ym = spec.wells.y[0], sm = (spec.wells.size || [])[0];
  if (!tb.rows.length) { return empty(); }
  const di = tb.at(d), xi = tb.at(xm), yi = tb.at(ym), si = sm ? tb.at(sm) : -1;
  const sizes = si >= 0 ? tb.rows.map((r) => Math.abs(Number(r[si]) || 0)) : [];
  const maxS = Math.max(1, ...sizes);
  const t = env.t;
  const fx = (v) => env.fmt(v, env.numFmt(xm)), fy = (v) => env.fmt(v, env.numFmt(ym));
  const data = tb.rows.map((r) => ({ name: String(r[di]), value: [r[xi], r[yi]], symbolSize: si >= 0 ? 8 + 28 * Math.sqrt(Math.abs(Number(r[si]) || 0) / maxS) : 10 }));
  const lab = { formatter: (v) => env.compact(v), color: t.muted, fontSize: 10 };
  const option = reportBase(spec, env, {
    legend: false, extra: {
      xAxis: Object.assign({ type: 'value', name: xm, nameLocation: 'middle', nameGap: 26 }, reportAxis(t), { axisLabel: lab }),
      yAxis: Object.assign({ type: 'value', name: ym }, reportAxis(t), { axisLabel: lab }),
      grid: { left: 8, right: 16, top: 24, bottom: 34, containLabel: true },
      tooltip: Object.assign(tipOf(spec, env), { formatter: (p) => p.name + '\n' + xm + ': ' + fx(p.value[0]) + '\n' + ym + ': ' + fy(p.value[1]) }),
      series: [{ type: 'scatter', data, itemStyle: { color: env.palette[0], opacity: 0.8 } }]
    }
  });
  const items = tb.rows.map((r) => ({ value: r[di], label: String(r[di]), text: String(r[di]) + ': ' + fx(r[xi]) + ', ' + fy(r[yi]) }));
  return { option, marks: { field: d, items, byName: (n) => items.find((q) => q.label === n) } };
}

function gauge(spec, res, env) {
  const tb = reportTable(res);
  const m = spec.wells.values[0], row = tb.rows[0] || [];
  const value = row[tb.at(m)];
  const tm = (spec.wells.target || [])[0];
  const target = tm ? row[tb.at(tm)] : null;
  const f = spec.format || {};
  if (typeof value !== 'number') { return empty(tb.isMasked(m) ? '•••' : 'No value.'); }
  const max = typeof f.max === 'number' ? f.max : typeof target === 'number' ? target * 1.25 : Math.max(Math.abs(value) * 1.25, 1);
  const min = typeof f.min === 'number' ? f.min : 0;
  const t = env.t;
  const nf = env.numFmt(m);
  const option = reportBase(spec, env, {
    legend: false, extra: {
      series: [{
        type: 'gauge', min, max, startAngle: 200, endAngle: -20, radius: '95%', center: ['50%', '62%'], progress: { show: true, width: 12, itemStyle: { color: t.link } },
        axisLine: { lineStyle: { width: 12, color: [[1, t.border]] } }, pointer: { show: false }, axisTick: { show: false }, splitLine: { show: false },
        axisLabel: { color: t.muted, fontSize: 9, distance: 16, formatter: (v) => env.compact(v) }, anchor: { show: false },
        detail: { valueAnimation: false, formatter: () => env.fmt(value, nf), color: t.ink, fontSize: 20, offsetCenter: [0, '10%'] },
        title: { show: false }, data: [{ value }]
      }]
    }
  });
  option.tooltip = { show: false };
  return { option, marks: noMarks(), alt: spec.alt || (spec.title + ': ' + env.fmt(value, nf) + (typeof target === 'number' ? ' of target ' + env.fmt(target, nf) : '')) };
}

/* Each redraw adds one point per measure (the first row of the answer) and keeps the latest format.window (default 120): a line of how the
   numbers have moved since the page was opened. The points live in the browser (env.rolling[visual id]); the server holds no history for it. */
function rolling(spec, res, env) {
  const tb = reportTable(res);
  const f = spec.format || {};
  const limit = Math.max(5, Math.min(1000, Number(f.window) || 120));
  const store = env.rolling;
  const key = env.rollingKey || spec.id;
  const buf = (store[key] = store[key] || { t: [], series: {} });
  const row = tb.rows[0] || [];
  buf.t.push(new Date().toTimeString().slice(0, 8));
  (spec.wells.values || []).forEach((m) => {
    const v = row[tb.at(m)];
    (buf.series[m] = buf.series[m] || []).push(typeof v === 'number' ? v : null);
  });
  while (buf.t.length > limit) { buf.t.shift(); Object.keys(buf.series).forEach((k) => buf.series[k].shift()); }
  const t = env.t;
  const measures = spec.wells.values || [];
  const series = measures.map((m, i) => ({
    name: m, type: 'line', data: buf.series[m] || [], showSymbol: false, smooth: !!f.smooth, lineStyle: { color: env.palette[i % env.palette.length], width: 2 }, itemStyle: { color: env.palette[i % env.palette.length] }
  }));
  const option = reportBase(spec, env, {
    legend: measures.length > 1, extra: {
      xAxis: Object.assign({ type: 'category', data: buf.t, boundaryGap: false }, reportAxis(t)), yAxis: valueAxis(spec, env, measures[0]), series,
      tooltip: Object.assign(tipOf(spec, env), { trigger: 'axis', formatter: (p) => { const l = Array.isArray(p) ? p : [p]; return l[0].name + '\n' + l.map((x) => x.seriesName + ': ' + env.fmt(x.value, env.numFmt(x.seriesName))).join('\n'); } })
    }
  });
  const last = (m) => fmtOf(env, tb, m)((buf.series[m] || []).slice(-1)[0]);
  return { option, marks: noMarks(), alt: (spec.alt || spec.title || 'Rolling chart') + ': ' + measures.map((m) => m + ' ' + last(m)).join(', '), label: true };
}

function funnel(spec, res, env) {
  const f = spec.format || {};
  const s = reportSeries(spec, res, []);
  if (!s.cats.length) { return empty(); }
  const sr = s.series[0];
  const sel = selectedSet(env, spec);
  const data = s.labels.map((l, i) => ({ name: l, value: typeof sr.values[i] === 'number' ? sr.values[i] : 0, itemStyle: { opacity: sel && !sel.has(String(s.cats[i])) ? 0.35 : 1 } }));
  const t = env.t;
  const option = reportBase(spec, env, {
    legend: false, extra: {
      tooltip: Object.assign(tipOf(spec, env), { trigger: 'item', formatter: (p) => p.name + ': ' + fmtOf(env, s.tb, sr.measure)(p.value) }),
      series: [{ type: 'funnel', sort: 'descending', left: '4%', right: '4%', top: 8, bottom: 8, gap: 2, data,
        label: { show: f.labels !== false, color: t.ink, fontSize: 11, position: 'inside', formatter: (p) => p.name + '  ' + env.compact(p.value) }, itemStyle: { borderColor: t.surface, borderWidth: 1 } }]
    }
  });
  return { option, marks: reportMarks(s.axis, s, env) };
}

/* A histogram arrives binned by the server (columns bin, count, from, to): bars of how many members fall in each band. */
function histogram(spec, res, env) {
  const tb = reportTable(res);
  const bi = tb.at('bin'), ci = tb.at('count');
  const measure = (spec.wells.values || [])[0];
  if (tb.isMasked('count') || (res.meta && (res.meta.masked || []).indexOf(measure) >= 0)) { return empty('•••'); }
  if (!tb.rows.length) { return empty(); }
  const t = env.t;
  const f = spec.format || {};
  const fi = tb.at('from');
  const bands = tb.rows.map((r) => String(r[bi]));
  const labels = tb.rows.map((r) => (fi >= 0 && typeof r[fi] === 'number' ? env.compact(r[fi]) : String(r[bi])));          // the axis names where a bar starts; the tip says the whole band
  const lab = { color: t.muted, fontFamily: t.mono, fontSize: 10, hideOverlap: true };
  const option = reportBase(spec, env, {
    legend: false, extra: {
      xAxis: Object.assign({ type: 'category', data: labels, name: (f.axisTitles && f.axisTitles.x) || (measure ? measure + ' (where a bar starts)' : ''), nameLocation: 'middle', nameGap: 26 }, reportAxis(t), { axisLabel: lab }),
      yAxis: Object.assign({ type: 'value', name: (f.axisTitles && f.axisTitles.y) || 'count' }, reportAxis(t)),
      grid: { left: 8, right: 16, top: 24, bottom: 38, containLabel: true },
      tooltip: Object.assign(tipOf(spec, env), { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: (p) => { const x = Array.isArray(p) ? p[0] : p; return bands[x.dataIndex] + '\n' + env.compact(x.value) + ' in this band'; } }),
      series: [{ type: 'bar', data: tb.rows.map((r) => r[ci]), barCategoryGap: '4%', itemStyle: { color: env.palette[0] }, label: { show: !!f.labels, position: 'top', color: t.ink, fontSize: 10 } }]
    }
  });
  return { option, marks: noMarks() };
}

/* A box plot arrives summarised by the server (columns: the category, min, q1, median, q3, max, count, outliers). */
function box(spec, res, env) {
  const tb = reportTable(res);
  const measure = (spec.wells.values || [])[0];
  const field = (spec.wells.axis || [])[0] || null;
  if (res.meta && (res.meta.masked || []).indexOf(measure) >= 0) { return empty('•••'); }
  if (!tb.rows.length) { return empty(); }
  const t = env.t;
  const nf = env.numFmt(measure), F = (v) => env.fmt(v, nf);
  const names = tb.rows.map((r) => String(r[0]));
  const boxes = tb.rows.map((r) => [r[1], r[2], r[3], r[4], r[5]]);
  const outliers = [];
  tb.rows.forEach((r, i) => String(r[7] || '').split(',').map((x) => x.trim()).filter((x) => x !== '' && !isNaN(Number(x))).forEach((x) => outliers.push([i, Number(x)])));
  const sel = selectedSet(env, spec);
  const option = reportBase(spec, env, {
    legend: false, extra: {
      xAxis: Object.assign({ type: 'category', data: names, boundaryGap: true }, reportAxis(t)),
      yAxis: valueAxis(spec, env, measure),
      tooltip: Object.assign(tipOf(spec, env), { trigger: 'item', formatter: (p) => {
        if (p.seriesType === 'scatter') { return names[p.value[0]] + '\noutlier ' + F(p.value[1]); }
        const r = tb.rows[p.dataIndex];
        return String(r[0]) + '\nmax ' + F(r[5]) + '\nQ3 ' + F(r[4]) + '\nmedian ' + F(r[3]) + '\nQ1 ' + F(r[2]) + '\nmin ' + F(r[1]) + '\nn ' + r[6];
      } }),
      series: [
        { type: 'boxplot', data: boxes.map((b, i) => ({ value: b, itemStyle: { opacity: sel && !sel.has(names[i]) ? 0.35 : 1 } })), itemStyle: { color: t['surface-2'] || t.surface, borderColor: env.palette[0], borderWidth: 2 }, boxWidth: ['20%', '60%'] },
        { type: 'scatter', data: outliers, symbolSize: 6, itemStyle: { color: env.palette[1] || env.palette[0] } }
      ]
    }
  });
  const items = names.map((n, i) => ({ value: tb.rows[i][0], label: n, text: n + ': median ' + F(tb.rows[i][3]) + ', ' + F(tb.rows[i][1]) + ' to ' + F(tb.rows[i][5]) + ', ' + tb.rows[i][6] + ' members' }));
  return { option, marks: { field: field && tb.names[0] === field ? field : null, items: field ? items : [], byName: (n) => (field ? items.find((q) => q.label === n) : null) } };
}

const splitOr = (spec, res, env) => ((spec.wells.split || []).length ? multiples(spec, res, env) : category(spec, res, env));
const KINDS = { 'live-chart': rolling, bar: splitOr, column: splitOr, line: splitOr, area: splitOr, combo: category, pie, donut: pie, waterfall, treemap, heatmap, scatter,
  bubble: scatter, funnel, histogram, box, gauge };
/** The report visuals drawn as charts by reportOption (the map is reportMap's). */
export const REPORT_CHART_KINDS = Object.freeze(Object.keys(KINDS).concat(['map']));

/**
 * What a report visual draws from its answer: the option, its marks (and words for the chart, a multiples summary), or the note of a
 * visual with nothing to draw.
 * @param {Object} spec the visual: {id, type, title, alt, wells, format}
 * @param {Object} res the server's answer
 * @param {ReportEnv} env
 * @returns {{option: Object, marks: Object, alt: string, multiples: Object}|{empty: string}|null} null for a type that is not a chart
 */
export function reportOption(spec, res, env) {
  const fn = KINDS[spec.type];
  return fn ? fn(spec, res, env) : null;
}

/**
 * The line under a card's figure: the measure over the axis, no axes, an area under it.
 * @param {Array} categories
 * @param {Array<number>} values
 * @param {string} colour
 * @returns {Object}
 */
export function sparklineOption(categories, values, colour) {
  return { animation: false, grid: { left: 2, right: 2, top: 2, bottom: 2 }, xAxis: { type: 'category', show: false, data: categories }, yAxis: { type: 'value', show: false, scale: true },
    series: [{ type: 'line', data: values, showSymbol: false, lineStyle: { color: colour, width: 2 }, areaStyle: { opacity: 0.15, color: colour } }] };
}

/**
 * What a drag selects on a chart that can be brushed (line, area, histogram, scatter, bubble): a function of the axis values at the band's
 * ends to the selection, or null when the chart cannot be brushed.
 * @param {Object} spec
 * @param {Object} option the drawn option
 * @param {Object} marks
 * @param {ReportEnv} env
 * @returns {(function(*, *): (Object|null))|null}
 */
export function reportBrush(spec, option, marks, env) {
  const xa = Array.isArray(option.xAxis) ? option.xAxis[0] : option.xAxis;
  if (!xa || ['line', 'area', 'histogram', 'scatter', 'bubble'].indexOf(spec.type) < 0) { return null; }
  if (spec.type === 'histogram') {
    const field = (spec.wells.axis || [])[0];
    return !field || xa.type !== 'value' ? null : (a, b) => ({ field, values: [a, b], range: true, label: env.compact(a) + ' to ' + env.compact(b) });
  }
  if (spec.type === 'scatter' || spec.type === 'bubble') {
    const data = (option.series[0] && option.series[0].data) || [];
    return !marks.field ? null : (a, b) => {
      const vs = [];
      data.forEach((d, i) => { const x = Array.isArray(d) ? d[0] : (d.value || [])[0]; if (typeof x === 'number' && x >= a && x <= b && marks.items[i]) { vs.push(marks.items[i].value); } });
      return vs.length ? { field: marks.field, values: vs, label: vs.length + ' points' } : null;
    };
  }
  return !marks.field ? null : (a, b) => {
    const lo = Math.max(0, Math.ceil(a)), hi = Math.min(marks.items.length - 1, Math.floor(b));
    return lo > hi ? null : { field: marks.field, values: [marks.items[lo].value, marks.items[hi].value], range: true, label: marks.items[lo].label + ' to ' + marks.items[hi].label };
  };
}
