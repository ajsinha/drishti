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
/* The chart's look from drishti-core's tokens (every colour is a theme token, light and dark come with the page) and its density
   (comfortable, compact, terminal: the grid's three), and the formats of axes and tooltips by drishti-core's rules. Without a page the
   tokens come from the theme table, so the kinds build the same option under Node. */
import { visualTheme } from '../../drishti-core/src/drishti_index.js';
export { blend, alpha, toneColour, rampColour, formatterFor } from '../../drishti-core/src/drishti_index.js';

/** The densities: font size, the chart's inner padding, the symbol size and the line width. */
export const CHART_DENSITIES = Object.freeze({
  comfortable: Object.freeze({ font: 12, pad: 14, symbol: 7, line: 2, title: 14 }),
  compact: Object.freeze({ font: 11, pad: 10, symbol: 6, line: 1.6, title: 13 }),
  terminal: Object.freeze({ font: 10.5, pad: 6, symbol: 5, line: 1.4, title: 12 })
});

/**
 * The chart's theme: drishti-core's visualTheme (tokens, palette, tones, ramps) and the chart's sizes at the density.
 * @param {{element: Element, theme: string, density: string, tokens: Object<string, string>}} [o] as visualTheme's
 * @returns {ChartTheme}
 */
export function chartTheme(o) {
  const th = visualTheme(Object.assign({}, o, { density: o && CHART_DENSITIES[o.density] ? o.density : 'compact' }));
  return Object.assign(th, { size: CHART_DENSITIES[th.density] });
}

/**
 * The chart's theme: a VisualTheme (drishti-core) with the chart's sizes.
 * @typedef {Object} ChartTheme
 * @property {boolean} dark
 * @property {Object<string, string>} tokens the token values by name without --d- (ink, muted, link ...)
 * @property {string[]} palette the categorical colours, in order
 * @property {string} density
 * @property {{font: number, pad: number, symbol: number, line: number, title: number}} size
 * @property {Object<string, string>} tones good, bad, warn, accent, link, pos, neg, muted, faint, ink
 * @property {string[]} sequential five colours from faint to strong
 * @property {string[]} diverging five colours, negative to positive through the ground
 * @property {string} nodata
 */

/**
 * The text style of the chart's words at the density.
 * @param {ChartTheme} th
 * @param {string} [colour] default: faint
 * @returns {Object}
 */
export function textStyle(th, colour) { return { color: colour || th.faint, fontFamily: th.font, fontSize: th.size.font }; }

/**
 * An axis of the theme: its line, ticks, split lines and labels with their formatter.
 * @param {ChartTheme} th
 * @param {Object} base the ECharts axis (type, data, name ...)
 * @param {function(*): string} [fmt] the labels' formatter (value axes)
 * @returns {Object}
 */
export function axisOf(th, base, fmt) {
  const value = base.type === 'value' || base.type === 'log';
  return Object.assign({
    axisLine: { show: !value, lineStyle: { color: th.axis } }, axisTick: { show: false },
    splitLine: { show: value, lineStyle: { color: th.grid } },
    axisLabel: Object.assign(textStyle(th), fmt && value ? { formatter: (v) => fmt(v) } : { hideOverlap: true }),
    nameTextStyle: textStyle(th, th.muted), nameGap: 26
  }, base);
}
