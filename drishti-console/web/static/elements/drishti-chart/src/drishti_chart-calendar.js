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
/* The calendar heatmap: one square per day, a month grid per year, on the vendored engine's calendar; a day with no row is left hollow
   (a non-business day, or one without a figure), so a gap never reads as a zero. */
import { fieldOf, num } from './drishti_chart-data.js';
import { rowId, heatScale } from './drishti_chart-kinds.js';
import { textStyle } from './drishti_chart-theme.js';

const year = (d) => String(d).slice(0, 4);

/**
 * Calendar: a value per date.
 * @param {Object} spec x (the date field), value (the measure field), scale ({type: 'diverging', mid}), title
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('./drishti_chart-spec.js').BuiltChart}
 */
export function buildCalendar(spec, rows, env) {
  const th = env.theme, xf = fieldOf(spec.x) || 'date', vf = fieldOf(spec.value);
  const data = [];
  rows.forEach((r, i) => {
    const d = String(r[xf]).slice(0, 10), v = num(r[vf]);
    if (/^\d{4}-\d{2}-\d{2}$/.test(d) && v !== null) { data.push({ value: [d, v], name: d, r: [i], ids: [rowId(spec, r, i)] }); }
  });
  data.sort((a, b) => (a.name < b.name ? -1 : 1));
  const years = Array.from(new Set(data.map((d) => year(d.name))));
  const vals = data.map((d) => d.value[1]), { lo, hi, visualMap } = heatScale(spec, vals, env);
  const cell = Math.max(10, th.size.font + 2), top = 28, gap = 26, heightOne = cell * 7 + gap;
  const calendars = years.map((y, i) => ({ range: y, top: top + i * heightOne, left: 40, right: 16, cellSize: ['auto', cell], itemStyle: { color: 'transparent', borderColor: th.tokens.border || th.ink, borderWidth: 1 },
    splitLine: { show: true, lineStyle: { color: th.tokens.border || th.ink, width: 1 } }, dayLabel: Object.assign({ firstDay: 1, nameMap: 'en' }, textStyle(th)), monthLabel: Object.assign({ nameMap: 'en' }, textStyle(th)),
    yearLabel: Object.assign({ show: years.length > 1 }, textStyle(th)) }));
  const series = years.map((y, i) => ({ type: 'heatmap', coordinateSystem: 'calendar', calendarIndex: i, name: (spec.value && spec.value.title) || vf, field: vf, data: data.filter((d) => year(d.name) === y),
    selectedMode: 'multiple', select: { itemStyle: { borderColor: th.tokens.accent, borderWidth: 2 } }, emphasis: { itemStyle: { borderColor: th.ink, borderWidth: 1 } } }));
  const option = { calendar: calendars, series: series, tooltip: { trigger: 'item', confine: true },
    visualMap: visualMap,
    textStyle: textStyle(th), animation: false };
  return { option: option, legend: [], minHeight: top + years.length * heightOne + 30,
    table: { columns: [{ header: xf }, { header: vf, format: spec.value && spec.value.format }], rows: data.map((d) => [d.name, d.value[1]]) },
    summary: 'Calendar' + (spec.title ? ' of ' + spec.title : '') + ', ' + data.length + ' days' + (vals.length ? ', from ' + env.fmt(lo) + ' to ' + env.fmt(hi) : '') };
}
