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
/* Small multiples (R6, 2.4.0; BI_PARITY_PLAN gap 13): a bar, column, line or area chart with a field in its Small multiples well (`split`) is drawn as one small
   chart per value of that field, side by side in rows (`format.multiples: { columns, max, sharedAxis }`), from the one answer the server gave (grouped by the
   axis, the legend and the split field). One ECharts instance holds every small chart (a grid each), so it is one canvas to resize and dispose; a click on a
   mark selects the mark's axis value as on any chart, and the keyboard walks the marks of the first chart. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = B.visuals;

  V.multiples = function (host, spec, ctx, res) {
    const f = spec.format || {};
    const mf = f.multiples || {};
    const split = (spec.wells.split || [])[0];
    const tb = V.table(res);
    const si = tb.at(split);
    if (si < 0 || !tb.rows.length) { return V.categoryChart(host, Object.assign({}, spec, { wells: Object.assign({}, spec.wells, { split: [] }) }), ctx, res); }
    const groups = new Map();
    tb.rows.forEach((r) => { const k = r[si] == null ? '–' : String(r[si]); if (!groups.has(k)) { groups.set(k, []); } groups.get(k).push(r); });
    const max = Math.max(2, Math.min(24, mf.max || 12));
    const keys = [...groups.keys()].sort().slice(0, max);
    const cols = Math.max(1, Math.min(6, mf.columns || Math.min(3, keys.length)));
    const rowsN = Math.ceil(keys.length / cols);
    const t = ctx.t;
    const horizontal = spec.type === 'bar';
    const base = Object.assign({}, spec, { wells: Object.assign({}, spec.wells, { split: [] }) });
    const shared = mf.sharedAxis !== false;
    const grids = [], xAxes = [], yAxes = [], titles = [], series = [];
    let lo = 0, hi = 0;
    const parts = keys.map((k) => V.seriesOf(base, ctx, Object.assign({}, res, { rows: groups.get(k) }), []));
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
      const cat = V.categoryAxisFormat(Object.assign({ type: 'category', data: allCats, gridIndex: i, inverse: horizontal }, V.axisStyle(t), { axisLabel: { color: t.muted, fontSize: 9, hideOverlap: true } }), f);
      const val = V.valueAxisFormat(Object.assign({ type: 'value', gridIndex: i, splitNumber: 3 }, V.axisStyle(t), { axisLabel: { color: t.muted, fontSize: 9, formatter: (v) => B.compact(v) } }), f);
      if (shared && val.min === undefined) { val.min = lo; }
      if (shared && val.max === undefined) { val.max = hi; }
      if (horizontal) { xAxes.push(val); yAxes.push(cat); } else { xAxes.push(cat); yAxes.push(val); }
      s.series.forEach((sr, sj) => {
        const isLine = spec.type === 'line' || spec.type === 'area';
        const colour = V.seriesColour(f, sr.name, sr.measure) || ctx.palette[sj % ctx.palette.length];
        series.push({ name: sr.name, type: isLine ? 'line' : 'bar', xAxisIndex: i, yAxisIndex: i, data: values(sr), stack: f.stacked && !isLine ? 'total-' + i : undefined, smooth: !!f.smooth,
          symbol: 'circle', symbolSize: 4, areaStyle: spec.type === 'area' ? { opacity: 0.25 } : undefined, itemStyle: { color: colour }, lineStyle: { color: colour, width: 2 }, barMaxWidth: 24,
          label: { show: !!f.labels, position: horizontal ? 'right' : 'top', color: t.ink, fontSize: 9, formatter: (p) => B.compact(p.value) } });
      });
    });
    const legendShown = (parts[0] && parts[0].series.length > 1) && f.legend !== 'none';
    const option = V.baseOption(spec, ctx, { legend: legendShown, extra: { grid: grids, xAxis: xAxes, yAxis: yAxes, title: titles, series,
      tooltip: Object.assign(V.baseOption(spec, ctx, {}).tooltip, { trigger: 'axis', axisPointer: { type: 'shadow' } }) } });
    const first = parts[0];
    const rawOf = new Map();                                              // the marks are the categories of every small chart (a click or a hover on any of them)
    parts.forEach((s) => s.labels.forEach((l, j) => { if (!rawOf.has(l)) { rawOf.set(l, s.cats[j]); } }));
    const marks = V.marksFor(first.axis, { tb, axis: first.axis, cats: allCats.map((l) => rawOf.get(l)), labels: allCats, series: [] }, ctx, spec);
    const handle = V.mount(host, option, Object.assign({}, spec, { alt: spec.alt || (spec.title || spec.type) + ': ' + keys.length + ' small charts, one per ' + B.label(split) }), ctx, marks);
    const box = B.q('.bi-chart', host);
    if (box) {
      box.setAttribute('data-multiples', String(keys.length));
      box.setAttribute('data-split', split);
      box.style.minHeight = Math.max(160, rowsN * 140) + 'px';
    }
    if (groups.size > keys.length) { host.appendChild(h('p', { class: 'bi-vis-note bi-multiples-cut', 'data-multiples-cut': String(groups.size), text: 'The first ' + keys.length + ' of ' + groups.size + ' values of ' + B.label(split) + ' (Format, Small multiples, Most charts).' })); }
    return handle;
  };
})();
