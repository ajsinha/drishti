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
/* drishti-risk's second set of chart kinds, built on drishti-chart's:

     risk.limit-ladder       every limit as a bar of its use (value over limit), worst first, the zones in tones, the limit at 100%
     risk.var-attribution    the waterfall of what makes VaR: each position's or desk's contribution, the diversification as a step down,
                             the total last
     risk.scenario-matrix    the P&L of each stress scenario (rows) in each bucket (columns): diverging scale about zero, the figures written

   The figures are the server's: use, contribution and P&L come in the rows; nothing is computed here but the order and the share. */
import { buildWaterfall, buildHeatmap, num, fieldOf, toneColour, textStyle, axisOf } from '../../../vendor/drishti-elements/3.3.1/elements/drishti-chart/src/drishti_index.js';

/** The zones of a limit's use (a share of the limit): up to ok is calm, up to warn is near, above is a breach. */
export const LIMIT_ZONES = Object.freeze({ ok: 0.75, warn: 1 });

/**
 * The zone of a use: ok, warn or bad.
 * @param {number} use value over limit
 * @param {{ok: number, warn: number}} [zones]
 * @returns {('ok'|'warn'|'bad')}
 */
export function limitZone(use, zones) {
  const z = zones || LIMIT_ZONES;
  return use <= z.ok ? 'ok' : use <= z.warn ? 'warn' : 'bad';
}

/**
 * Limit ladder: horizontal bars of each limit's use, worst first, a line at 100%.
 * @param {Object} spec category (the limit's name), value, limit (fields), or use (a field holding value over limit), zones {ok, warn}
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {import('../../drishti-chart/src/drishti_chart-spec.js').BuiltChart}
 */
export function buildLimitLadder(spec, rows, env) {
  const th = env.theme, cf = fieldOf(spec.category) || fieldOf(spec.x) || 'limit', vf = fieldOf(spec.value) || 'value', lf = (spec.limit && fieldOf(spec.limit)) || 'limit', uf = spec.use && fieldOf(spec.use);
  const items = rows.map((r, i) => {
    const use = uf ? num(r[uf]) : (num(r[lf]) ? (num(r[vf]) || 0) / num(r[lf]) : null);
    return { name: String(r[cf]), use: use, value: num(r[vf]), limit: num(r[lf]), i: i };
  }).filter((x) => x.use !== null).sort((a, b) => b.use - a.use);
  const zone = (u) => limitZone(u, spec.zones);
  const pct = (u) => Math.round(u * 100) + '%';
  const option = { animationDuration: 260, textStyle: textStyle(th), tooltip: { trigger: 'item', confine: true, formatter: (p) => p.name + ': ' + pct(p.value) + ' of the limit' },
    grid: { left: th.size.pad + 6, right: th.size.pad + 24, top: th.size.pad, bottom: th.size.pad, containLabel: true },
    xAxis: axisOf(th, { type: 'value', min: 0, max: Math.max(1.2, ...items.map((x) => x.use * 1.05)), axisLabel: {} }, (v) => pct(v)),
    yAxis: axisOf(th, { type: 'category', data: items.map((x) => x.name), inverse: true }),
    series: [{ type: 'bar', name: 'use', field: vf, barMaxWidth: 22, data: items.map((x) => ({ value: x.use, name: x.name, r: [x.i], ids: [], itemStyle: { color: toneColour(th, { ok: 'good', warn: 'warn', bad: 'bad' }[zone(x.use)]) } })),
      markLine: { silent: true, symbol: 'none', label: { show: true, formatter: 'limit', color: th.muted }, lineStyle: { color: th.ink, type: 'dashed' }, data: [{ xAxis: 1 }] } }] };
  return { option: option, legend: [{ name: 'within 75%', colour: toneColour(th, 'good'), style: 'box' }, { name: 'near the limit', colour: toneColour(th, 'warn'), style: 'box' }, { name: 'breach', colour: toneColour(th, 'bad'), style: 'box' }], legendStatic: true,
    table: { columns: [{ header: cf }, { header: 'Value', format: spec.value && spec.value.format }, { header: 'Limit', format: spec.value && spec.value.format }, { header: 'Use', format: 'percent' }, { header: 'Zone' }],
      rows: items.map((x) => [x.name, x.value, x.limit, x.use, zone(x.use)]) },
    summary: 'Limit ladder' + (spec.title ? ' of ' + spec.title : '') + ', ' + items.length + ' limits, ' + items.filter((x) => zone(x.use) === 'bad').length + ' in breach, the worst ' + (items.length ? items[0].name + ' at ' + pct(items[0].use) : 'none') };
}

/**
 * VaR attribution: contributions stepping up to the total, a negative one (diversification) stepping down.
 * @param {Object} spec category (position or desk), value (the contribution; the last row is the total VaR)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {Object}
 */
export function buildVarAttribution(spec, rows, env) {
  return buildWaterfall(Object.assign({ up: 'warn', down: 'good', total: 'link', totals: ['last'], category: { field: 'position' }, value: { field: 'contribution', format: 'money' } }, spec), rows, env);
}

/**
 * Scenario matrix: P&L by scenario and bucket on a diverging scale about zero, the figures written in the cells.
 * @param {Object} spec x (the bucket), y (the scenario), value (the P&L)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {Object}
 */
export function buildScenarioMatrix(spec, rows, env) {
  return buildHeatmap(Object.assign({ x: { field: 'bucket' }, y: { field: 'scenario' }, value: { field: 'pnl', format: 'money' }, scale: { type: 'diverging', mid: 0 }, labels: true }, spec), rows, env);
}
