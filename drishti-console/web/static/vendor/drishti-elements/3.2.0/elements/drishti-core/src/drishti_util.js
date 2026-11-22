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
/* Small helpers every library shares, so none writes its own: is it a plain object, clamp a number, pad digits, copy text to the system
   clipboard, the fetch to use, a fine clock. */

/**
 * Whether a value is a plain object (not null, not a list).
 * @param {*} v
 * @returns {boolean}
 */
export function isObject(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }

/**
 * A number held between two bounds.
 * @param {number} v
 * @param {number} lo
 * @param {number} hi
 * @returns {number}
 */
export function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

/**
 * A whole number as text of a width, with leading zeros (pad(7) is "07").
 * @param {number} n
 * @param {number} [width=2]
 * @returns {string}
 */
export function pad(n, width) { return String(n).padStart(width || 2, '0'); }

/**
 * Writes text to the system clipboard when the page has one; a refusal (no permission, no clipboard) is not an error.
 * @param {Window} [win] default: the global
 * @param {string} text
 * @returns {boolean} whether a write was started
 */
export function copyText(win, text) {
  try {
    const nav = (win || globalThis).navigator;
    if (nav && nav.clipboard) { nav.clipboard.writeText(text).catch(() => {}); return true; }
  } catch (err) { /* the caller keeps its own copy of the text */ }
  return false;
}

/**
 * The fetch to use: the one given, else the page's own, bound to it; null when there is none.
 * @param {function} [custom]
 * @param {Object} [win] default: the global
 * @returns {?function}
 */
export function fetcher(custom, win) {
  if (custom) { return custom; }
  const w = win || globalThis;
  return typeof w.fetch === 'function' ? w.fetch.bind(w) : null;
}

/**
 * A fine clock in milliseconds (performance.now where there is one, else the date).
 * @returns {number}
 */
export function now() { return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now(); }
