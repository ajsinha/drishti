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
/* Small multiples and linked crosshairs: one spec drawn once per member of a field, on one shared y scale (so the panels compare), and
   the crosshair of any chart followed in all the linked ones (the engine's own axis-pointer link: no second implementation). */
import { Chart } from './drishti_chart-view.js';
import { members, num, rowsOf, fieldOf } from './drishti_chart-data.js';

let groups = 0;

/**
 * Links the crosshair (and tooltip) of charts: pointing at a place on one shows it in all.
 * @param {Chart[]} charts charts that are drawn (their engines exist)
 * @param {Object} [E] the engine (default: the first chart's)
 * @returns {function(): void} takes the link off
 */
export function linkCrosshair(charts, E) {
  const engine = E || (charts[0] && charts[0].E), name = 'drishti-xh-' + (++groups), live = charts.filter((c) => c.engine);
  if (!engine || live.length < 2) { return () => {}; }
  live.forEach((c) => { c.engine.group = name; });
  engine.connect(name);
  return () => { live.forEach((c) => { if (c.engine) { c.engine.group = ''; } }); };
}

/**
 * @typedef {Object} FacetOptions
 * @property {number} [columns] panels per row (default: as many as fit 240 px, at most 4)
 * @property {boolean} [shareY=true] one y scale for every panel
 * @property {boolean} [linkCrosshair=true] a crosshair followed across the panels
 * @property {number} [height=160] each panel's drawing height
 * @property {ChartOptionsLike} [chart] options for each Chart (explain, link, echarts ...)
 */
/** @typedef {Object<string, *>} ChartOptionsLike */

/**
 * One chart per member of a field (small multiples): the spec's rows split by the field, each panel titled with the member, the same
 * y scale in all (so they compare), the crosshair linked. The panels sit in a grid of columns that wraps on a phone.
 * @param {HTMLElement} host
 * @param {Object} spec a spec with its rows in data.rows
 * @param {string} by the field to split by
 * @param {FacetOptions} [o]
 * @returns {{charts: Chart[], members: string[], ready: Promise<void>, destroy: function(): void}}
 */
export function facets(host, spec, by, o) {
  o = Object.assign({ shareY: true, linkCrosshair: true, height: 160 }, o || {});
  const doc = host.ownerDocument, rows = rowsOf(spec.data), yf = fieldOf(spec.y) || fieldOf(spec.value), mem = members(rows, by);
  const vals = yf ? rows.map((r) => num(r[yf])).filter((v) => v !== null) : [];
  const cols = o.columns || Math.max(1, Math.min(4, mem.length, Math.floor((host.clientWidth || 960) / 240)));
  const grid = doc.createElement('div');
  grid.className = 'dc-facets';
  grid.setAttribute('role', 'group');
  grid.setAttribute('aria-label', (spec.title ? spec.title + ': ' : '') + 'one chart per ' + by + ', ' + mem.length + ' in all');
  grid.style.setProperty('--dc-facet-cols', String(cols));
  host.appendChild(grid);
  const charts = mem.map((m) => {
    const cell = doc.createElement('div');
    cell.className = 'dc-facet';
    grid.appendChild(cell);
    const y = Object.assign({}, typeof spec.y === 'object' && spec.y ? spec.y : {}, o.shareY && vals.length ? { min: Math.min(0, ...vals), max: Math.max(...vals) } : {});
    const s = Object.assign({}, spec, { title: m, legend: 'none', y: y, x: spec.x, data: { rows: rows.filter((r) => String(r[by]) === m) } });
    return new Chart(cell, s, Object.assign({ height: o.height }, o.chart || {}));
  });
  const ready = Promise.all(charts.map((c) => c.ready || Promise.resolve())).then(() => { if (o.linkCrosshair) { off = linkCrosshair(charts); } });
  let off = () => {};
  return { charts: charts, members: mem, ready: ready, destroy: () => { off(); charts.forEach((c) => c.destroy()); grid.remove(); } };
}
