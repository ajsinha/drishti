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
/* The general kinds without axes: pie and donut, gauge, treemap and sankey. Each builds the ECharts option, the data table, the legend
   and a description from a normalised spec and its rows, without a page. */
import { registerKind } from './drishti_chart-spec.js';
import { fieldOf, num, members } from './drishti_chart-data.js';
import { toneColour, textStyle } from './drishti_chart-theme.js';
import { rowId } from './drishti_chart-kinds.js';

function base(th) { return { animationDuration: 260, textStyle: textStyle(th), color: th.palette, tooltip: { trigger: 'item', confine: true } }; }

/**
 * Pie and donut: a value per category (summed), the largest first unless the spec keeps its order; small slices beyond top folded
 * into "Other".
 * @param {Object} spec category, value, top (default 12), sort (false: as given)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildPie(spec, rows, env) {
  const th = env.theme, cf = fieldOf(spec.category) || fieldOf(spec.x), vf = fieldOf(spec.value) || fieldOf(spec.y);
  let items = members(rows, cf).map((c) => {
    const idx = rows.map((r, i) => i).filter((i) => String(rows[i][cf]) === c);
    return { name: c, value: idx.reduce((s, i) => s + (num(rows[i][vf]) || 0), 0), r: idx, ids: idx.map((i) => rowId(spec, rows[i], i)) };
  });
  if (spec.sort !== false) { items.sort((a, b) => b.value - a.value); }
  const top = spec.top || 12;
  if (items.length > top) {
    const rest = items.slice(top - 1);
    items = items.slice(0, top - 1).concat([{ name: 'Other', value: rest.reduce((s, x) => s + x.value, 0), r: [].concat.apply([], rest.map((x) => x.r)), ids: [].concat.apply([], rest.map((x) => x.ids)), other: true }]);
  }
  const total = items.reduce((s, x) => s + x.value, 0), donut = spec.kind === 'donut' || spec.donut;
  items.forEach((x, i) => { x.itemStyle = { color: toneColour(th, x.other ? 'muted' : null, i) }; });
  const option = Object.assign(base(th), {
    legend: { show: false, data: items.map((x) => x.name) },
    series: [{ type: 'pie', name: (spec.value && spec.value.title) || vf, field: vf, radius: donut ? ['52%', '76%'] : [0, '76%'], center: ['50%', '50%'], data: items, selectedMode: 'multiple', selectedOffset: 6,
      itemStyle: { borderColor: th.surface, borderWidth: 1.5 }, avoidLabelOverlap: true,
      label: { show: spec.labels !== false, color: th.muted, fontFamily: th.font, fontSize: th.size.font, formatter: (p) => p.name + ' ' + (total ? Math.round(1000 * p.value / total) / 10 + '%' : '') },
      labelLine: { lineStyle: { color: th.border } } }]
  });
  if (donut) {
    option.graphic = [{ type: 'text', left: 'center', top: 'middle', silent: true, style: { text: env.fmt(total), fill: th.ink, font: '600 ' + (th.size.title + 4) + 'px ' + th.font } }];
  }
  return { option: option, legend: items.map((x) => ({ name: x.name, colour: x.itemStyle.color, style: 'box' })),
    table: { columns: [{ header: cf }, { header: vf, format: spec.value && spec.value.format }, { header: 'share', format: 'percent' }], rows: items.map((x) => [x.name, x.value, total ? x.value / total : null]) },
    summary: (donut ? 'Donut' : 'Pie') + ' chart' + (spec.title ? ' of ' + spec.title : '') + ', ' + items.length + ' slices, the largest ' + (items[0] ? items[0].name : '') };
}

/**
 * Gauge: one figure against its range, with bands in tones (a limit's green, amber and red).
 * @param {Object} spec value ({field} of the first row, or {value}), min, max, bands [{to, tone}], format
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildGauge(spec, rows, env) {
  const th = env.theme, vf = fieldOf(spec.value), v = spec.value && spec.value.value !== undefined ? num(spec.value.value) : num(rows.length ? rows[0][vf] : null);
  const min = num(spec.min) === null ? 0 : num(spec.min), max = num(spec.max) === null ? Math.max(1, (v || 0) * 1.25) : num(spec.max);
  const bands = (spec.bands || [{ to: max, tone: 'link' }]).map((b) => [Math.max(0, Math.min(1, ((num(b.to) === null ? max : num(b.to)) - min) / ((max - min) || 1))), toneColour(th, b.tone)]);
  const option = Object.assign(base(th), {
    series: [{ type: 'gauge', name: (spec.value && spec.value.title) || vf || 'value', field: vf, min: min, max: max, startAngle: 210, endAngle: -30, radius: '88%',
      axisLine: { lineStyle: { width: 12, color: bands } }, pointer: { itemStyle: { color: th.ink }, width: 4 }, anchor: { show: true, size: 8, itemStyle: { color: th.ink } },
      axisTick: { show: false }, splitLine: { length: 10, lineStyle: { color: th.surface, width: 2 } }, axisLabel: Object.assign(textStyle(th), { distance: 16, formatter: (x) => env.fmt(x) }),
      title: { show: !!(spec.value && spec.value.title), color: th.muted, fontFamily: th.font, fontSize: th.size.font, offsetCenter: [0, '72%'] },
      detail: { valueAnimation: false, color: th.ink, fontFamily: th.font, fontSize: th.size.title + 8, fontWeight: 700, offsetCenter: [0, '38%'], formatter: (x) => env.fmt(x) },
      data: [{ value: v, name: (spec.value && spec.value.title) || '', r: rows.length ? [0] : [], ids: rows.length ? [rowId(spec, rows[0], 0)] : [] }] }]
  });
  return { option: option, legend: [], table: { columns: [{ header: (spec.value && spec.value.title) || vf || 'value', format: spec.value && spec.value.format }, { header: 'min' }, { header: 'max' }], rows: [[v, min, max]] },
    summary: 'Gauge' + (spec.title ? ' of ' + spec.title : '') + ': ' + env.fmt(v) + ' of ' + env.fmt(max) };
}

/**
 * Treemap: a value nested by levels of fields (desk, then book), each top-level member in its palette colour.
 * @param {Object} spec levels (fields, outermost first) or category, value
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildTreemap(spec, rows, env) {
  const th = env.theme, levels = spec.levels || [fieldOf(spec.category)], vf = fieldOf(spec.value);
  const tops = members(rows, levels[0]);
  function nest(idx, depth) {
    if (depth >= levels.length) { return null; }
    const f = levels[depth];
    return members(idx.map((i) => rows[i]), f).map((m) => {
      const sub = idx.filter((i) => String(rows[i][f]) === m), value = sub.reduce((s, i) => s + Math.abs(num(rows[i][vf]) || 0), 0);
      const node = { name: m, value: value, r: sub, ids: sub.map((i) => rowId(spec, rows[i], i)) };
      const kids = nest(sub, depth + 1);
      if (kids) { node.children = kids; }
      if (depth === 0) { node.itemStyle = { color: toneColour(th, null, tops.indexOf(m)) }; }
      return node;
    });
  }
  const data = nest(rows.map((r, i) => i), 0);
  const option = Object.assign(base(th), {
    legend: { show: false, data: tops },
    series: [{ type: 'treemap', name: (spec.value && spec.value.title) || vf, field: vf, data: data, roam: false, nodeClick: false, breadcrumb: { show: false }, width: '100%', height: '100%', top: 4, left: 4, right: 4, bottom: 4,
      label: { show: true, color: th.tokens['bg'] && !th.dark ? '#fff' : th.ink, fontFamily: th.font, fontSize: th.size.font, formatter: (p) => p.name + '\n' + env.fmt(p.value) },
      upperLabel: { show: levels.length > 1, height: 18, color: th.ink, fontFamily: th.font, fontSize: th.size.font },
      itemStyle: { borderColor: th.surface, borderWidth: 2, gapWidth: 2 }, levels: [{ itemStyle: { borderWidth: 0, gapWidth: 3 } }, { itemStyle: { gapWidth: 1 }, colorSaturation: [0.35, 0.6] }] }]
  });
  const flat = [];
  (function walk(nodes, path) { (nodes || []).forEach((n) => { if (n.children) { walk(n.children, path.concat([n.name])); } else { flat.push(path.concat([n.name, n.value])); } }); })(data, []);
  return { option: option, legend: tops.map((m, i) => ({ name: m, colour: toneColour(th, null, i), style: 'box' })), legendStatic: true,
    table: { columns: levels.map((l) => ({ header: l })).concat([{ header: vf, format: spec.value && spec.value.format }]), rows: flat },
    summary: 'Treemap' + (spec.title ? ' of ' + spec.title : '') + ', ' + flat.length + ' leaves under ' + tops.length + ' groups' };
}

/**
 * Sankey: flows from a source to a target with a value (the links summed), nodes in palette colours.
 * @param {Object} spec source, target, value (fields)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildSankey(spec, rows, env) {
  const th = env.theme, sf = fieldOf(spec.source) || 'source', tf = fieldOf(spec.target) || 'target', vf = fieldOf(spec.value) || 'value';
  const links = new Map(), names = [];
  rows.forEach((r, i) => {
    const s = String(r[sf]), t = String(r[tf]), v = num(r[vf]);
    if (v === null || s === t) { return; }
    [s, t].forEach((n) => { if (names.indexOf(n) < 0) { names.push(n); } });
    const k = s + '\u0000' + t, e = links.get(k) || { source: s, target: t, value: 0, r: [], ids: [] };
    e.value += v; e.r.push(i); e.ids.push(rowId(spec, r, i));
    links.set(k, e);
  });
  const option = Object.assign(base(th), {
    series: [{ type: 'sankey', name: (spec.value && spec.value.title) || vf, field: vf, emphasis: { focus: 'adjacency' }, nodeGap: 10, nodeWidth: 14, left: 8, right: 90, top: 8, bottom: 8,
      data: names.map((n, i) => ({ name: n, itemStyle: { color: toneColour(th, null, i), borderColor: th.surface } })), links: Array.from(links.values()),
      lineStyle: { color: 'gradient', opacity: 0.35, curveness: 0.5 }, label: { color: th.ink, fontFamily: th.font, fontSize: th.size.font } }]
  });
  return { option: option, legend: [], table: { columns: [{ header: sf }, { header: tf }, { header: vf, format: spec.value && spec.value.format }], rows: Array.from(links.values()).map((l) => [l.source, l.target, l.value]) },
    summary: 'Sankey' + (spec.title ? ' of ' + spec.title : '') + ', ' + links.size + ' flows between ' + names.length + ' nodes' };
}

/**
 * Registers the kinds of this module (the library's entry calls it once).
 * @returns {void}
 */
export function registerOtherKinds() {
  registerKind('pie', { build: buildPie, label: 'pie' });
  registerKind('donut', { build: buildPie, label: 'donut' });
  registerKind('gauge', { build: buildGauge, label: 'gauge' });
  registerKind('treemap', { build: buildTreemap, label: 'treemap' });
  registerKind('sankey', { build: buildSankey, label: 'sankey' });
}
