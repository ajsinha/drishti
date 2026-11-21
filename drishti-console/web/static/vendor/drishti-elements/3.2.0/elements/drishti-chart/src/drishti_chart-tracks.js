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
/* A heatmap's annotation tracks and its category cells. A track is a strip of cells aligned to the heatmap's columns (axis x, drawn
   above it) or rows (axis y, drawn at its right), one cell per member, coloured by a category (a tone each, or the palette in order) or
   a number (the sequential ramp, or the diverging one around a middle): a limit's status over desks, a gene's role over samples, a
   residue's burial over positions. Category cells colour the heatmap's own cells by a category instead of a scale (an alteration, a
   status), with the legend's keys. The heatmap stays one grid of cells; the tracks are further grids on the same members, so the
   drawing, the tooltip, a click (a track's cell gives its member and value) and the data table come with them.

     tracks: [{ axis: 'x', title: 'Burial', values: { '1': 0.2, '2': 0.9 } },              a number per member: the sequential ramp
              { axis: 'x', title: 'Structure', field: 'SECONDARY', tones: { helix: 'accent', strand: 'link' } },   a field of the rows
              { axis: 'y', title: 'Role', values: { ... }, type: 'category' }]
     value: { field: 'ALTERATION', type: 'category', tones: { missense: 'good', truncating: 'bad' }, order: [...] }      category cells */
import { fieldOf, num, members } from './drishti_chart-data.js';
import { toneColour, rampColour, axisOf, textStyle } from './drishti_chart-theme.js';

/**
 * A track of a heatmap: a strip of cells on one axis's members.
 * @typedef {Object} HeatTrack
 * @property {('x'|'y')} [axis='x'] x: a strip above the heatmap per column; y: a strip at its right per row
 * @property {string} title the track's name (its label and its column in the data table)
 * @property {Object<string, *>} [values] the value of each member (the member as text)
 * @property {string} [field] else the value of a member is this field of the first row of that member
 * @property {('category'|'value')} [type] default: value when every value is a number
 * @property {Object<string, string>} [tones] a category's tone (good, bad, warn, accent, link, muted ...) or colour
 * @property {('sequential'|'diverging')} [scale='sequential'] a number's ramp
 * @property {number} [mid=0] the middle of a diverging ramp
 * @property {number} [min] the ramp's low end (default: the smallest value)
 * @property {number} [max] the ramp's high end (default: the largest value)
 */

/**
 * The values of a track by member: its values, or its field read from the first row of each member.
 * @param {HeatTrack} t
 * @param {Object[]} rows the heatmap's rows
 * @param {string} keyField the field of the track's axis (x or y)
 * @param {string[]} keys the axis's members, in order
 * @returns {Array<*>} one value per member (null where none)
 */
export function trackValues(t, rows, keyField, keys) {
  if (t.values && typeof t.values === 'object') { return keys.map((k) => (t.values[k] === undefined ? null : t.values[k])); }
  if (!t.field) { return keys.map(() => null); }
  const first = new Map();
  rows.forEach((r) => { const k = String(r[keyField]); if (!first.has(k)) { first.set(k, r[t.field]); } });
  return keys.map((k) => (first.has(k) ? first.get(k) : null));
}

/**
 * How a track's (or category cells') values are coloured: a function of a value to its colour, and the legend's keys for a category.
 * @param {HeatTrack} t
 * @param {Array<*>} vals
 * @param {Object} th the chart theme
 * @returns {{colour: function(*): ?string, category: boolean, keys: Array<{name: string, colour: string, style: string}>}}
 */
export function trackColour(t, vals, th) {
  const present = vals.filter((v) => v !== null && v !== undefined && v !== '');
  const category = t.type ? t.type === 'category' : present.some((v) => num(v) === null);
  if (category) {
    const order = (t.order || []).map(String).concat(Array.from(new Set(present.map(String))).filter((v) => (t.order || []).map(String).indexOf(v) < 0).sort());
    const tones = t.tones || {}, colourOf = (v) => toneColour(th, tones[v], order.indexOf(v));
    return { category: true, colour: (v) => (v === null || v === undefined || v === '' ? null : colourOf(String(v))),
      keys: order.filter((v) => present.some((p) => String(p) === v)).map((v) => ({ name: v, colour: colourOf(v), style: 'box' })) };
  }
  const nums = present.map(num).filter((v) => v !== null), div = t.scale === 'diverging', mid = t.mid || 0;
  let lo = t.min !== undefined ? t.min : (nums.length ? Math.min.apply(null, nums) : 0), hi = t.max !== undefined ? t.max : (nums.length ? Math.max.apply(null, nums) : 1);
  if (div) { const m = Math.max(Math.abs(lo - mid), Math.abs(hi - mid)) || 1; lo = mid - m; hi = mid + m; }
  const ramp = div ? th.diverging : th.sequential;
  return { category: false, keys: [], colour: (v) => (num(v) === null ? null : rampColour(ramp, num(v), lo, hi === lo ? lo + 1 : hi)) };
}

/**
 * Category cells: the heatmap's cells coloured by a category (spec.value.type 'category') rather than a scale.
 * @param {Object} spec the heatmap's spec (value: {field, type: 'category', tones, order})
 * @param {Object[]} rows
 * @param {Object} th
 * @returns {{colour: function(*): ?string, keys: Array<{name: string, colour: string, style: string}>, codes: string[]}} codes: the categories in order (a cell's number is its index)
 */
export function categoryCells(spec, rows, th) {
  const vf = fieldOf(spec.value) || fieldOf(spec.colour), v = spec.value || {};
  const c = trackColour({ type: 'category', tones: v.tones, order: v.order }, rows.map((r) => r[vf]), th);
  return { colour: c.colour, keys: c.keys, codes: c.keys.map((k) => k.name) };
}

/**
 * Adds a heatmap's tracks to its option: the heatmap's grid keeps index 0 (its axes too, so a selection's outline lands on it), and each
 * track is a grid of its own on the same members, sharing the heatmap's left and right edges (x) or top and bottom (y). The heatmap's
 * grid is laid out with room for them; the data table gains a column (x: a row) per track. Changes option and table in place.
 * @param {Object} option the heatmap's option (one grid, one x and one y axis)
 * @param {Object} spec its spec (tracks, trackSize: a strip's thickness in pixels, default 10)
 * @param {Object[]} rows
 * @param {{xs: string[], ys: string[], xf: string, yf: string}} axes the heatmap's members and fields
 * @param {Object} env {theme, fmt}
 * @param {{columns: Array, rows: Array}} table the data table (rows by y member)
 * @returns {Array<{name: string, colour: string, style: string}>} the legend's keys of the category tracks
 */
export function addTracks(option, spec, rows, axes, env, table) {
  const tracks = (spec.tracks || []).filter((t) => t && (t.values || t.field));
  if (!tracks.length) { return []; }
  const th = env.theme, size = spec.trackSize || 10, gap = 2, pad = th.size.pad;
  const xt = tracks.filter((t) => (t.axis || 'x') === 'x'), yt = tracks.filter((t) => t.axis === 'y');
  const labelChars = Math.max.apply(null, axes.ys.map((y) => String(y).length).concat(xt.map((t) => String(t.title || '').length)).concat([2]));
  const left = pad + 8 + Math.min(180, Math.round(labelChars * th.size.font * 0.62)), right = pad + 6 + yt.length * (size + gap) + (yt.length ? 4 : 0);
  const top = pad + 6 + xt.length * (size + gap) + (xt.length ? 4 : 0), bottom = option.grid.bottom;
  option.grid = [{ left: left, right: right, top: top, bottom: bottom, containLabel: false }];
  option.xAxis = [Object.assign(option.xAxis, { gridIndex: 0 })];
  option.yAxis = [Object.assign(option.yAxis, { gridIndex: 0 })];
  const keys = [];
  const hidden = (base) => axisOf(th, Object.assign({ axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false }, splitArea: { show: false } }, base));
  const strip = (t, i, onX) => {
    const members = onX ? axes.xs : axes.ys, vals = trackValues(t, rows, onX ? axes.xf : axes.yf, members), c = trackColour(t, vals, th), g = option.grid.length;
    c.keys.forEach((k) => { if (!keys.some((x) => x.name === k.name)) { keys.push(k); } });
    option.grid.push(onX ? { left: left, right: right, top: pad + 6 + i * (size + gap), height: size, containLabel: false }
      : { top: top, bottom: bottom, right: pad + 6 + (yt.length - 1 - i) * (size + gap), width: size, containLabel: false });
    const lab = { type: 'category', data: [t.title || ''], gridIndex: g };
    option.xAxis.push(onX ? hidden({ type: 'category', data: members, gridIndex: g, axisLabel: { show: false } }) : hidden(Object.assign(lab, { position: 'top', axisLabel: { show: false } })));
    option.yAxis.push(onX ? hidden(Object.assign(lab, { axisLabel: Object.assign(textStyle(th), { fontSize: Math.max(8, th.size.font - 1.5) }) })) : hidden({ type: 'category', data: members, inverse: true, gridIndex: g, axisLabel: { show: false } }));
    option.series.push({ type: 'heatmap', name: t.title || '', field: t.field || null, track: true, xAxisIndex: g, yAxisIndex: g, itemStyle: { borderWidth: 0 },
      emphasis: { itemStyle: { borderColor: th.ink, borderWidth: 1 } },
      data: vals.map((v, j) => (v === null || v === undefined || v === '' ? null : { value: onX ? [j, 0, v] : [0, j, v], name: members[j], r: [], ids: [members[j]], itemStyle: { color: c.colour(v) } })).filter(Boolean) });
    if (onX) { table.rows.push([t.title || ''].concat(vals).concat(yt.map(() => null))); } else { table.columns.push({ header: t.title || '' }); table.rows.forEach((r, j) => r.push(vals[j])); }
  };
  yt.forEach((t, i) => strip(t, i, false));
  xt.forEach((t, i) => strip(t, i, true));
  return keys;
}
