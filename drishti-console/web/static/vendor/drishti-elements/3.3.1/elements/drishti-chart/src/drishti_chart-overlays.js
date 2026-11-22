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
/* What a scatter can carry beyond its points, each generic and each from the spec:

     segments  a value axis made of named, ordered segments (books on one long axis, chromosomes on a genome, sessions on a timeline):
               alternate bands with each segment's name under it, the points in alternating tones, the axis's own numbers hidden
               segments: { values: [{ name, from, to }], tones: ['link', 'muted'], labels: true }
     labelTop  the highest points named: at most n, one per member of a field (the tallest point of each of the top members)
               labelTop: { field: 'NAME', n: 4 }
     surface   a field of values under the points (a fitness landscape, a density, a cost surface): cells of a regular grid, coloured
               on a ramp, drawn behind and never clicked
               surface: { rows: [{ x, y, value }], x: 'x', y: 'y', value: 'value', cell: [dx, dy], scale: 'sequential' | 'diverging', mid }

   The server sends the segments, the labels' field and the surface's cells; nothing is counted here. */
import { num, fieldOf } from './drishti_chart-data.js';
import { toneColour, rampColour, alpha, textStyle } from './drishti_chart-theme.js';

/**
 * The segment a value falls in (by from and to, the segments in order), or -1.
 * @param {Array<{from: number, to: number}>} segs
 * @param {number} v
 * @returns {number}
 */
export function segmentOf(segs, v) {
  let lo = 0, hi = segs.length - 1;
  while (lo <= hi) {
    const m = (lo + hi) >> 1, s = segs[m];
    if (v < num(s.from)) { hi = m - 1; } else if (v > num(s.to)) { lo = m + 1; } else { return m; }
  }
  return -1;
}

/**
 * Adds a scatter's segments, top labels and surface to its option (in place); the first series carries the bands.
 * @param {Object} option the scatter's option
 * @param {Object} spec segments, labelTop, surface (above)
 * @param {Object[]} rows
 * @param {Object} env {theme, fmt}
 * @returns {Array<{name: string, colour: string, style: string}>} legend keys it adds (none today)
 */
export function scatterOverlays(option, spec, rows, env) {
  const th = env.theme, points = option.series.filter((s) => s.type === 'scatter' && !s.helper);
  const seg = spec.segments && Array.isArray(spec.segments.values) ? spec.segments : null;
  if (seg && points.length) {
    const segs = seg.values.slice().sort((a, b) => num(a.from) - num(b.from)), tones = seg.tones || ['link', 'muted'];
    const colourOf = (i) => toneColour(th, tones[i % tones.length], i % tones.length);
    if (!(spec.colour && spec.colour.field)) {
      points.forEach((s) => s.data.forEach((it) => { const i = segmentOf(segs, it.value[0]); if (i >= 0 && !it.itemStyle) { it.itemStyle = { color: colourOf(i) }; } }));
    }
    const areas = segs.map((s, i) => [{ xAxis: num(s.from), itemStyle: { color: i % 2 ? alpha(th.faint, 0.07) : 'transparent' },
      label: { show: seg.labels !== false, formatter: String(s.name), position: 'insideBottom', distance: -16, color: th.faint, fontFamily: th.font, fontSize: Math.max(8, th.size.font - 1.5) } }, { xAxis: num(s.to) }]);
    const first = points[0];
    first.markArea = { silent: true, data: (first.markArea ? first.markArea.data : []).concat(areas) };
    const x = Array.isArray(option.xAxis) ? option.xAxis[0] : option.xAxis;
    x.axisLabel = Object.assign({}, x.axisLabel, { show: false });
    x.splitLine = { show: false };
    x.min = num(segs[0].from);
    x.max = num(segs[segs.length - 1].to);
    option.grid.bottom = (option.grid.bottom || 10) + 14;
  }
  if (spec.labelTop && spec.labelTop.field) {
    const lf = spec.labelTop.field, best = new Map();
    points.forEach((s) => s.data.forEach((it) => {
      const r = rows[it.r[0]], k = r && r[lf];
      if (k === null || k === undefined || k === '') { return; }
      const was = best.get(String(k));
      if (!was || it.value[1] > was.value[1]) { best.set(String(k), { value: it.value, name: String(k) }); }
    }));
    const top = Array.from(best.values()).sort((a, b) => b.value[1] - a.value[1]).slice(0, spec.labelTop.n || 5);
    option.series = option.series.concat([{ type: 'scatter', name: '__labels', helper: true, silent: true, z: 11, symbolSize: 0, tooltip: { show: false }, data: top.map((t) => ({ value: t.value, name: t.name })),
      label: Object.assign({ show: true, position: 'top', distance: 6, formatter: (p) => p.name }, textStyle(th, th.ink), { fontWeight: 'bold' }) }]);
  }
  const sf = spec.surface;
  if (sf && Array.isArray(sf.rows) && sf.rows.length) {
    const xf = fieldOf(sf.x) || 'x', yf = fieldOf(sf.y) || 'y', vf = fieldOf(sf.value) || 'value', cell = sf.cell || [1, 1];
    const vals = sf.rows.map((r) => num(r[vf])).filter((v) => v !== null), div = sf.scale === 'diverging', mid = sf.mid || 0;
    let lo = Math.min.apply(null, vals), hi = Math.max.apply(null, vals);
    if (div) { const m = Math.max(Math.abs(lo - mid), Math.abs(hi - mid)) || 1; lo = mid - m; hi = mid + m; }
    const ramp = div ? th.diverging : th.sequential, op = sf.opacity || 0.55;
    option.series = [{ type: 'custom', name: '__surface', helper: true, silent: true, z: 0, tooltip: { show: false },
      data: sf.rows.map((r) => [num(r[xf]), num(r[yf]), num(r[vf])]).filter((d) => d[0] !== null && d[1] !== null && d[2] !== null),
      renderItem: (params, api) => {
        const x = api.value(0), y = api.value(1), a = api.coord([x - cell[0] / 2, y + cell[1] / 2]), b = api.coord([x + cell[0] / 2, y - cell[1] / 2]);
        return { type: 'rect', shape: { x: a[0], y: a[1], width: b[0] - a[0] + 0.5, height: b[1] - a[1] + 0.5 }, style: { fill: alpha(rampColour(ramp, api.value(2), lo, hi === lo ? lo + 1 : hi), op) } };
      } }].concat(option.series);
  }
  return [];
}
