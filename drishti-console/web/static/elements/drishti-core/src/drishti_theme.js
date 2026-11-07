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
/* Theme tokens: the colours live in one place, static/css/drishti_tokens.css (CSS custom properties --d-*, a theme per data-theme on
   <html>, light or dark); this module is their JavaScript side: the themes and every token's value (generated from that sheet by the
   build, so charts and canvases read the same colours without a page), the theme in force and a change of it, and WCAG contrast
   arithmetic (text 4.5:1, graphics 3:1), the same sums tests/test_contrast.py makes. */
import { THEME_TABLE } from './drishti_theme-table.js';

/** The default theme (the sheet's :root). */
export const DEFAULT_THEME = Object.keys(THEME_TABLE)[0];
/** Where a person's last theme is kept in this browser (as the console's start-up script reads it). */
export const THEME_STORAGE_KEY = 'drishti.theme';
/** The tokens a theme uses for text, each held to 4.5:1 on every ground. */
export const TEXT_TOKENS = Object.freeze(['--d-ink', '--d-muted', '--d-faint', '--d-link', '--d-accent', '--d-accent-strong', '--d-pos', '--d-neg', '--d-ok', '--d-warn', '--d-bad']);
/** The grounds text sits on. */
export const GROUND_TOKENS = Object.freeze(['--d-bg', '--d-bg-2', '--d-surface', '--d-surface-2']);

/**
 * Every theme: its name, light or dark.
 * @returns {Array<{name: string, scheme: string}>}
 */
export function themes() { return Object.keys(THEME_TABLE).map((n) => ({ name: n, scheme: THEME_TABLE[n].scheme })); }

/**
 * Light or dark.
 * @param {string} name a theme
 * @returns {('light'|'dark')}
 */
export function scheme(name) { return (THEME_TABLE[name] || THEME_TABLE[DEFAULT_THEME]).scheme === 'light' ? 'light' : 'dark'; }

/**
 * The theme in force on a document (data-theme on <html>, else the default).
 * @param {Document} [doc]
 * @returns {string}
 */
export function currentTheme(doc) {
  doc = doc || (typeof document !== 'undefined' ? document : null);
  const t = doc && doc.documentElement.getAttribute('data-theme');
  return t && THEME_TABLE[t] ? t : DEFAULT_THEME;
}

/**
 * Puts a theme in force: data-theme on <html>, kept in this browser unless persist is false; tells the page (event drishti:theme).
 * @param {string} name a theme (themes())
 * @param {{persist: boolean, document: Document}} [o]
 * @returns {void}
 */
export function setTheme(name, o) {
  if (!THEME_TABLE[name]) { throw new Error('setTheme: no theme ' + name + ' (' + Object.keys(THEME_TABLE).join(', ') + ')'); }
  o = o || {};
  const doc = o.document || document;
  doc.documentElement.setAttribute('data-theme', name);
  if (o.persist !== false) { try { doc.defaultView.localStorage.setItem(THEME_STORAGE_KEY, name); } catch (e) { /* not kept */ } }
  doc.dispatchEvent(new CustomEvent('drishti:theme', { detail: { theme: name, scheme: scheme(name) } }));
}

/**
 * A token's value: in a page, as the page's style computes it (on the element given, else <html>); otherwise from the theme table.
 * @param {string} token "--d-ink" (the "--d-" may be left out)
 * @param {{theme: string, element: Element}} [o] theme: read the table for that theme, not the page
 * @returns {string} '' when unknown
 */
export function token(token, o) {
  o = o || {};
  const t = token.indexOf('--') === 0 ? token : '--d-' + token;
  if (!o.theme && typeof getComputedStyle === 'function' && typeof document !== 'undefined') {
    const v = getComputedStyle(o.element || document.documentElement).getPropertyValue(t).trim();
    if (v) { return v; }
  }
  const th = THEME_TABLE[o.theme || currentTheme()] || THEME_TABLE[DEFAULT_THEME];
  return th.tokens[t] || '';
}

/**
 * Every token of a theme.
 * @param {string} [name] default: the theme in force
 * @returns {Object<string, string>}
 */
export function tokens(name) { return Object.assign({}, (THEME_TABLE[name || currentTheme()] || THEME_TABLE[DEFAULT_THEME]).tokens); }

/**
 * A colour as red, green, blue (0 to 255) and alpha: #rgb, #rrggbb, rgb() and rgba().
 * @param {string} c
 * @returns {?{r: number, g: number, b: number, a: number}}
 */
export function parseColor(c) {
  const s = String(c || '').trim();
  let m = /^#([0-9a-f]{3})$/i.exec(s);
  if (m) { return { r: parseInt(m[1][0] + m[1][0], 16), g: parseInt(m[1][1] + m[1][1], 16), b: parseInt(m[1][2] + m[1][2], 16), a: 1 }; }
  m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(s);
  if (m) { return { r: parseInt(m[1].slice(0, 2), 16), g: parseInt(m[1].slice(2, 4), 16), b: parseInt(m[1].slice(4, 6), 16), a: m[2] ? parseInt(m[2], 16) / 255 : 1 }; }
  m = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/i.exec(s);
  if (m) { return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] }; }
  return null;
}

/**
 * WCAG relative luminance.
 * @param {string} c a colour
 * @returns {number} 0 (black) to 1 (white)
 */
export function luminance(c) {
  const p = parseColor(c);
  if (!p) { throw new Error('luminance: not a colour: ' + c); }
  const ch = [p.r, p.g, p.b].map((x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

/**
 * The WCAG contrast ratio of two colours, 1 to 21.
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
export function contrastRatio(a, b) {
  const la = luminance(a), lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/**
 * Of some colours, the one that reads best on a ground (the first that reaches the minimum, else the highest).
 * @param {string} ground
 * @param {string[]} candidates
 * @param {number} [min=4.5]
 * @returns {string}
 */
export function readableOn(ground, candidates, min) {
  min = min || 4.5;
  let best = candidates[0], score = -1;
  for (const c of candidates) {
    const r = contrastRatio(c, ground);
    if (r >= min) { return c; }
    if (r > score) { best = c; score = r; }
  }
  return best;
}

/**
 * The text tokens of a theme that read under 4.5:1 on one of its grounds (opaque colours only): empty when the theme is accessible.
 * @param {string} name
 * @returns {Array<{text: string, ground: string, ratio: number}>}
 */
export function contrastProblems(name) {
  const t = tokens(name), out = [];
  for (const fg of TEXT_TOKENS) {
    for (const bg of GROUND_TOKENS) {
      const a = parseColor(t[fg]), b = parseColor(t[bg]);
      if (!a || !b || a.a < 1 || b.a < 1) { continue; }
      const r = contrastRatio(t[fg], t[bg]);
      if (r < 4.5) { out.push({ text: fg, ground: bg, ratio: Math.round(r * 100) / 100 }); }
    }
  }
  return out;
}
