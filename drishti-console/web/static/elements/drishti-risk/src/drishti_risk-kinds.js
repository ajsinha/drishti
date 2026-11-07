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
/* drishti-risk's chart kinds (the banking module's, licensed with it), built on drishti-chart's kinds and registered in its registry:

     risk.pnl-distribution   the scenario P&L as a histogram, the VaR and ES lines, the tail beyond VaR in the bad tone
     risk.backtest           daily P&L against minus the day-before VaR, each exception marked, the Basel traffic light (green 0 to 4,
                             amber 5 to 9, red 10 and more over 250 days) with its plus factor on the card
     risk.exposure-profile   EE and PFE over the tenors on a log axis, EEPE over the first year, the limit line, a breach marked
     risk.var-change         the waterfall of what moved VaR from one day to the next (an increase is bad)

   The figures are the server's: VaR, ES and EEPE come in the spec or the rows (var, es, eepe: a number or a field). Only a page with no
   server (the gallery, a standalone app) leaves them out, and then they are computed from the rows given and the card says so. */
import { buildHistogram, buildSeriesChart, buildWaterfall, quantile, num, fieldOf, registerKind, kindOf, toneColour } from '../../drishti-chart/src/drishti_index.js';

/** The Basel backtest zones over 250 days: exceptions to the zone, and the plus factor of each count. */
export const TRAFFIC_LIGHT = Object.freeze({ green: [0, 4], amber: [5, 9], red: [10, Infinity],
  plus: Object.freeze({ 0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0.40, 6: 0.50, 7: 0.65, 8: 0.75, 9: 0.85 }) });

/**
 * The zone and plus factor of a count of exceptions.
 * @param {number} n exceptions in the window
 * @returns {{zone: ('green'|'amber'|'red'), plus: number}}
 */
export function trafficLight(n) {
  const zone = n <= TRAFFIC_LIGHT.green[1] ? 'green' : n <= TRAFFIC_LIGHT.amber[1] ? 'amber' : 'red';
  return { zone: zone, plus: zone === 'red' ? 1 : TRAFFIC_LIGHT.plus[n] };
}

/** @private a figure the spec gives (a number, {value}, or a field of the first row) */
function given(v, rows) {
  if (v === undefined || v === null) { return null; }
  if (typeof v === 'number') { return v; }
  if (typeof v === 'object' && v.value !== undefined) { return num(v.value); }
  const f = typeof v === 'string' ? v : v.field;
  return rows.length && f ? num(rows[0][f]) : null;
}

/**
 * VaR and ES of scenario P&L (positive numbers: losses), as a page with no server computes them: VaR the loss at the quantile, ES the
 * mean of the losses beyond it. Inside Drishti these figures come from the server.
 * @param {number[]} pnl
 * @param {number} varConf for example 0.99
 * @param {number} esConf for example 0.975
 * @returns {{var: ?number, es: ?number}}
 */
export function tailFigures(pnl, varConf, esConf) {
  const xs = pnl.map(num).filter((v) => v !== null).sort((a, b) => a - b);
  if (!xs.length) { return { var: null, es: null }; }
  const v = -quantile(xs, 1 - varConf), cut = quantile(xs, 1 - esConf), tail = xs.filter((x) => x <= cut);
  return { var: v, es: tail.length ? -tail.reduce((s, x) => s + x, 0) / tail.length : null };
}

/**
 * The P&L distribution with VaR and ES.
 * @param {Object} spec value (the P&L field), var and es (the server's figures), confidence {var, es}, bins
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {Object}
 */
export function buildPnlDistribution(spec, rows, env) {
  const vf = fieldOf(spec.value) || 'pnl', conf = Object.assign({ var: 0.99, es: 0.975 }, spec.confidence);
  let v = given(spec.var, rows), es = given(spec.es, rows), computed = false;
  if (v === null || es === null) { const t = tailFigures(rows.map((r) => r[vf]), conf.var, conf.es); v = v === null ? t.var : v; es = es === null ? t.es : es; computed = true; }
  const pct = (c) => Math.round(c * 1000) / 10 + '%';
  const s = Object.assign({}, spec, { value: Object.assign({ field: vf, title: 'P&L' }, spec.value), bins: spec.bins || 36,
    tail: { below: -v, tone: 'bad', label: 'beyond VaR' },
    annotations: [{ type: 'line', axis: 'x', value: -v, label: 'VaR ' + pct(conf.var), tone: 'bad', style: 'solid' }, { type: 'line', axis: 'x', value: -es, label: 'ES ' + pct(conf.es), tone: 'accent' }].concat(spec.annotations || []) });
  const b = buildHistogram(s, rows, env);
  b.legend.push({ name: 'VaR ' + pct(conf.var) + ' ' + env.fmt(v), colour: toneColour(env.theme, 'bad'), style: 'line' }, { name: 'ES ' + pct(conf.es) + ' ' + env.fmt(es), colour: toneColour(env.theme, 'accent'), style: 'dash' });
  b.legendStatic = true;
  b.figures = { var: v, es: es, computedHere: computed };
  if (computed) { b.badge = { tone: 'muted', text: 'computed here (no server)' }; }
  b.summary = 'P&L distribution of ' + rows.length + ' scenarios: VaR ' + pct(conf.var) + ' ' + env.fmt(v) + ', ES ' + pct(conf.es) + ' ' + env.fmt(es);
  return b;
}

/**
 * The backtest: P&L against minus VaR, exceptions marked, the traffic light on the card.
 * @param {Object} spec x (the date field), pnl (default hypothetical), var (the VaR field; drawn negated), actual (another P&L field),
 *   window (default 250)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {Object}
 */
export function buildBacktest(spec, rows, env) {
  const pf = fieldOf(spec.pnl) || 'hypothetical', vf = fieldOf(spec.var) || 'var99', xf = fieldOf(spec.x) || 'date', af = fieldOf(spec.actual);
  const window = spec.window || 250, last = rows.slice(-window);
  const n = last.filter((r) => num(r[pf]) !== null && num(r[vf]) !== null && num(r[pf]) < -num(r[vf])).length, tl = trafficLight(n);
  const series = [{ field: pf, name: (spec.pnlTitle || 'Hypothetical P&L') }].concat(af ? [{ field: af, name: spec.actualTitle || 'Actual P&L', opacity: 0.9 }] : [])
    .concat([{ field: vf, name: '−VaR ' + (spec.confidence ? Math.round(spec.confidence * 100) + '%' : '99%') + ' (day before)', negate: true, style: 'dashed', tone: 'bad' }]);
  const s = Object.assign({}, spec, { kind: 'line', x: Object.assign({ field: xf, type: 'time' }, typeof spec.x === 'object' ? spec.x : {}), y: Object.assign({ format: 'money' }, spec.y),
    series: series, marks: [{ when: pf + ' < -' + vf, as: 'point', tone: 'bad', field: pf, name: 'Exception (' + n + ')' }],
    annotations: [{ type: 'line', axis: 'y', value: 0, tone: 'faint', style: 'solid', showValue: false }].concat(spec.annotations || []) });
  const b = buildSeriesChart(s, rows, env);
  b.badge = { tone: tl.zone === 'green' ? 'good' : tl.zone === 'amber' ? 'warn' : 'bad', text: tl.zone + ' · ' + n + ' exceptions · plus ' + tl.plus.toFixed(2), zone: tl.zone };
  b.figures = { exceptions: n, zone: tl.zone, plus: tl.plus, window: last.length };
  b.summary = 'Backtest over ' + last.length + ' days: ' + n + ' exceptions, the ' + tl.zone + ' zone, plus factor ' + tl.plus.toFixed(2);
  return b;
}

/**
 * A tenor in years as people write it (0.25: 3M, 1: 1Y, 0.02: 1W).
 * @param {?number} t
 * @returns {string}
 */
export function tenorText(t) {
  if (t === null || t === undefined) { return ''; }
  if (t < 1) { const m = Math.round(t * 12); return m < 1 ? Math.round(t * 52) + 'W' : m + 'M'; }
  return (Math.round(t * 10) / 10) + 'Y';
}

/**
 * Effective EPE: the time-weighted mean of effective EE (its running maximum) over the first year, as a page with no server computes
 * it. Inside Drishti the server's figure comes in the spec.
 * @param {number[]} tenors in years, ascending
 * @param {number[]} ee
 * @returns {?number}
 */
export function eepeOf(tenors, ee) {
  let run = 0, area = 0, prev = 0, upto = 0;
  for (let i = 0; i < tenors.length && prev < 1; i++) {
    const t = Math.min(1, tenors[i]), v = num(ee[i]);
    if (v === null) { continue; }
    run = Math.max(run, v);
    area += run * (t - prev); upto = t; prev = t;
  }
  return upto > 0 ? area / upto : null;
}

/**
 * The exposure profile: EE and PFE over the tenors (a log axis by default), EEPE over the first year, the limit, a breach of it marked.
 * @param {Object} spec x (tenor in years), ee, pfe, eepe (the server's), limit, log (default true)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {Object}
 */
export function buildExposureProfile(spec, rows, env) {
  const xf = fieldOf(spec.x) || 'tenor', ef = fieldOf(spec.ee) || 'ee', pf = fieldOf(spec.pfe) || 'pfe', th = env.theme;
  const sorted = rows.slice().sort((a, b) => num(a[xf]) - num(b[xf]));
  let eepe = given(spec.eepe, sorted), computed = false;
  if (eepe === null) { eepe = eepeOf(sorted.map((r) => num(r[xf])), sorted.map((r) => r[ef])); computed = true; }
  const limit = given(spec.limit, sorted), log = spec.log !== false;
  const s = Object.assign({}, spec, { kind: 'area', x: { field: xf, type: log ? 'log' : 'value', title: 'tenor', format: tenorText }, y: Object.assign({ format: 'money' }, spec.y),
    series: [{ field: ef, name: 'EE', tone: 'link', opacity: 0.16 }, { field: pf, name: spec.pfeTitle || 'PFE 95%', tone: 'accent', opacity: 0.1 }],
    marks: limit !== null ? [{ when: pf + ' > ' + limit, field: pf, tone: 'bad', name: 'over the limit' }] : [],
    annotations: (limit !== null ? [{ type: 'line', axis: 'y', value: limit, label: 'limit', tone: 'bad' }] : []).concat(spec.annotations || []) });
  const b = buildSeriesChart(s, sorted, env);
  const first = b.option.series[0], x0 = num(sorted.length ? sorted[0][xf] : 0.08);
  if (eepe !== null) {
    first.markLine = first.markLine || { silent: true, symbol: 'none', data: [], animation: false };
    first.markLine.data.push([{ coord: [x0, eepe], lineStyle: { color: toneColour(th, 'good'), type: 'solid', width: 2 }, label: { show: true, formatter: 'EEPE ' + env.fmt(eepe), color: toneColour(th, 'good'), position: 'end', fontFamily: th.font, fontSize: th.size.font } }, { coord: [1, eepe] }]);
  }
  b.option.xAxis.axisLabel.formatter = (v) => tenorText(v);
  b.legend.push({ name: 'EEPE ' + env.fmt(eepe), colour: toneColour(th, 'good'), style: 'line' });
  if (limit !== null) { b.legend.push({ name: 'limit ' + env.fmt(limit), colour: toneColour(th, 'bad'), style: 'dash' }); }
  b.legendStatic = false;
  const peak = Math.max.apply(null, sorted.map((r) => num(r[pf]) || 0).concat([0]));
  b.figures = { eepe: eepe, peakPfe: peak, limit: limit, computedHere: computed };
  if (limit !== null && peak > limit) { b.badge = { tone: 'bad', text: 'PFE over the limit' }; } else if (computed) { b.badge = { tone: 'muted', text: 'EEPE computed here (no server)' }; }
  b.summary = 'Exposure profile over ' + sorted.length + ' tenors: peak PFE ' + env.fmt(peak) + (limit !== null ? ' against a limit of ' + env.fmt(limit) : '') + ', EEPE ' + env.fmt(eepe);
  return b;
}

/**
 * What moved VaR from one day to the next: a waterfall where an increase is bad and a decrease good.
 * @param {Object} spec category (the step), value (the change; the first and last rows are the two days' VaR)
 * @param {Object[]} rows
 * @param {Object} env
 * @returns {Object}
 */
export function buildVarChange(spec, rows, env) {
  return buildWaterfall(Object.assign({ up: 'bad', down: 'good', total: 'link', category: { field: 'step' }, value: { field: 'change', format: 'money' } }, spec), rows, env);
}

/**
 * Registers the risk kinds in drishti-chart's registry (the library does it when it loads, and only where the banking module is
 * licensed: the console serves this library to no one else).
 * @returns {string[]} the kinds registered now
 */
export function registerRiskKinds() {
  const out = [];
  const add = (name, build, label, cartesian) => { if (!kindOf(name)) { registerKind(name, { build: build, label: label, family: 'risk', cartesian: cartesian }); out.push(name); } };
  add('risk.pnl-distribution', buildPnlDistribution, 'P&L distribution', true);
  add('risk.backtest', buildBacktest, 'backtest', true);
  add('risk.exposure-profile', buildExposureProfile, 'exposure profile', true);
  add('risk.var-change', buildVarChange, 'VaR change', true);
  return out;
}
