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
/* The look every drawing component shares (moved here from drishti-chart so the graph and the tree draw with the same palette): the
   tokens read from the page (or the theme table under Node), the categorical palette, the tones a spec names, the sequential and
   diverging ramps, colours blended and faded, and a formatter for a format's name by drishti-core's rules. */
import { token, parseColor } from './drishti_theme.js';
import { formatCompact, formatNumber, formatPercent, formatBasisPoints, formatCurrency, formatNamed, hasFormat } from './drishti_format.js';
import { formatDate } from './drishti_dates.js';

const NAMES = ['bg', 'bg-2', 'surface', 'surface-2', 'border', 'ink', 'muted', 'faint', 'accent', 'accent-strong', 'link', 'pos', 'neg', 'ok', 'warn', 'bad', 'font-sans', 'font-mono'];

/**
 * A colour between two (each a hex or rgb() colour), as rgb().
 * @param {string} a
 * @param {string} b
 * @param {number} f 0 is a, 1 is b
 * @returns {string}
 */
export function blend(a, b, f) {
  const x = parseColor(a), y = parseColor(b);
  if (!x || !y) { return f > 0.5 ? b : a; }
  const k = Math.max(0, Math.min(1, f)), m = (p, q) => Math.round(p + (q - p) * k);
  return 'rgb(' + m(x.r, y.r) + ',' + m(x.g, y.g) + ',' + m(x.b, y.b) + ')';
}

/**
 * A colour with an alpha, as rgba().
 * @param {string} c
 * @param {number} a 0 to 1
 * @returns {string}
 */
export function alpha(c, a) {
  const x = parseColor(c);
  return x ? 'rgba(' + x.r + ',' + x.g + ',' + x.b + ',' + a + ')' : c;
}

/** The densities every component shares (the grid's three), in the order a picker lists them. */
export const DENSITIES = Object.freeze(['comfortable', 'compact', 'terminal']);

/**
 * The look every drawing component shares (the chart, the graph, the tree): the tokens it draws with, the categorical palette, the
 * tones a spec names, the ramps of scales and the density.
 * @param {{element: Element, theme: string, density: string, tokens: Object<string, string>}} [o] element: read the page's computed
 *   tokens on it; theme: read a theme of the table instead; tokens: given outright (tests)
 * @returns {VisualTheme}
 */
export function visualTheme(o) {
  o = o || {};
  const t = {};
  NAMES.forEach((n) => { t[n] = (o.tokens && o.tokens['--d-' + n]) || token('--d-' + n, { element: o.element, theme: o.theme }) || ''; });
  const dark = t.bg ? (parseColor(t.bg) ? (parseColor(t.bg).r + parseColor(t.bg).g + parseColor(t.bg).b) < 384 : true) : true;
  const palette = [t.link, t.accent, t.ok, t.neg, blend(t.link, t.bad, 0.55), blend(t.ok, t.link, 0.5), t.warn, blend(t.accent, t.bad, 0.5), t.muted, blend(t.link, t.ink, 0.45)];
  const density = DENSITIES.includes(o.density) ? o.density : 'compact';
  return {
    dark: dark, tokens: t, palette: palette, density: density,
    ink: t.ink, muted: t.muted, faint: t.faint, border: t.border, surface: t.surface, surface2: t['surface-2'], ground: t['bg-2'],
    grid: blend(t.surface, t.border, 0.7), axis: t.border,
    font: density === 'terminal' ? t['font-mono'] : t['font-sans'], mono: t['font-mono'],
    tones: { good: t.ok, ok: t.ok, bad: t.bad, warn: t.warn, accent: t.accent, link: t.link, pos: t.pos, neg: t.neg, muted: t.muted, faint: t.faint, ink: t.ink },
    sequential: [blend(t['surface-2'], t.link, 0.12), blend(t['surface-2'], t.link, 0.35), blend(t['surface-2'], t.link, 0.58), blend(t['surface-2'], t.link, 0.8), t.link],
    diverging: [t.neg, blend(t.neg, t['surface-2'], 0.55), t['surface-2'], blend(t.pos, t['surface-2'], 0.55), t.pos],
    nodata: blend(t.surface, t.border, 0.55)
  };
}

/**
 * @typedef {Object} VisualTheme
 * @property {boolean} dark
 * @property {Object<string, string>} tokens the token values by name without --d- (ink, muted, link ...)
 * @property {string[]} palette the categorical colours, in order
 * @property {string} density
 * @property {Object<string, string>} tones good, bad, warn, accent, link, pos, neg, muted, faint, ink
 * @property {string[]} sequential five colours from faint to strong
 * @property {string[]} diverging five colours, negative to positive through the ground
 * @property {string} nodata
 */

/**
 * A tone a spec names (good, bad, warn, accent, muted ...) as its colour, or the colour itself, or the palette's nth.
 * @param {VisualTheme} th
 * @param {*} tone a name, a colour, or a number (the palette's index)
 * @param {number} [i] the palette's index when tone is empty
 * @returns {string}
 */
export function toneColour(th, tone, i) {
  if (typeof tone === 'number') { return th.palette[tone % th.palette.length]; }
  if (tone && th.tones[tone]) { return th.tones[tone]; }
  if (tone && /^(#|rgb|hsl)/.test(tone)) { return tone; }
  return th.palette[(i || 0) % th.palette.length];
}

/**
 * The colour of a value on a ramp (five colours) between lo and hi.
 * @param {string[]} ramp
 * @param {number} v
 * @param {number} lo
 * @param {number} hi
 * @returns {string}
 */
export function rampColour(ramp, v, lo, hi) {
  const f = hi === lo ? 1 : Math.max(0, Math.min(1, (v - lo) / (hi - lo))) * (ramp.length - 1), i = Math.min(ramp.length - 2, Math.floor(f));
  return blend(ramp[i], ramp[i + 1], f - i);
}

/**
 * A formatter for a spec's format: a drishti-core named format (amount0, pct2, compact ...), money (compact, two decimals), number,
 * integer, percent, bp, date, {currency: 'USD'}, or a function. Nothing given: a compact figure for large values, else up to two
 * decimals.
 * @param {*} f
 * @returns {function(*): string}
 */
export function formatterFor(f) {
  if (typeof f === 'function') { return f; }
  if (f && typeof f === 'object') {
    if (f.currency) { return (v) => formatCurrency(v, f.currency, f); }
    if (f.style === 'percent') { return (v) => formatPercent(v, f); }
    if (f.style === 'compact') { return (v) => formatCompact(v, f); }
    return (v) => formatNumber(v, f);
  }
  switch (f) {
    case 'money': return (v) => formatCompact(v, { decimals: 2 });
    case 'compact': return (v) => formatCompact(v, { decimals: 1 });
    case 'number': return (v) => formatNumber(v, { decimals: 0, maxDecimals: 2 });
    case 'integer': case 'count': return (v) => formatNumber(v, { decimals: 0 });
    case 'percent': return (v) => formatPercent(v, { decimals: 1 });
    case 'bp': return (v) => formatBasisPoints(v);
    case 'date': return (v) => (v === null || v === undefined ? '' : formatDate(v));
    case 'text': return (v) => (v === null || v === undefined ? '' : String(v));
    default:
      if (f && hasFormat(f)) { return (v) => formatNamed(f, v); }
      return (v) => {
        if (typeof v !== 'number') { return v === null || v === undefined ? '' : String(v); }
        return Math.abs(v) >= 1e5 ? formatCompact(v, { decimals: 2 }) : formatNumber(v, { decimals: 0, maxDecimals: 2 });
      };
  }
}
