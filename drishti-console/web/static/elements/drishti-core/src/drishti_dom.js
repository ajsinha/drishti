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
/* The small DOM parts every drawn component uses: an element maker (no markup strings, no style attributes, so a page with a strict CSP
   draws every component), placements that keep a floating element (a menu, a popover, a calendar) inside the window, and the rules for
   drawing untrusted data (attrAllowed, safeUrl: the client renderer draws a ViewModel through them, EMBED_GUIDE.md section 9). */

/** Attribute names a drawing of untrusted data may set (with data-* and aria-*): never an event handler, a style or a script address. */
const SAFE_ATTRS = new Set(('class id role title href target rel type name value placeholder for colspan rowspan scope tabindex hidden disabled '
  + 'open datetime lang dir aria-label aria-hidden maxlength autocomplete').split(' '));
/** A link a drawing may write: a path on this or the configured server, a fragment, a query, or http(s); never javascript:, data:, vbscript:. */
const SAFE_URL = /^(?:\/(?!\/)|#|\?|\.\/|https?:\/\/)/i;

/**
 * Whether an attribute name may carry a value from data (the allow-list, data-* and aria-*; never on*, style, src, srcdoc, formaction).
 * @param {string} name
 * @returns {boolean}
 */
export function attrAllowed(name) {
  const n = String(name).toLowerCase();
  if (n.startsWith('on') || n === 'style' || n === 'srcdoc' || n === 'src' || n === 'formaction' || n === 'xlink:href') { return false; }
  return SAFE_ATTRS.has(n) || /^data-[a-z0-9-]+$/.test(n) || /^aria-[a-z]+$/.test(n);
}

/**
 * The address itself when it is safe in an href (control characters and spaces around it ignored), else null.
 * @param {*} u
 * @returns {?string}
 */
export function safeUrl(u) {
  if (u === null || u === undefined) { return null; }
  const s = String(u);
  return SAFE_URL.test(s.replace(/[\u0000- ]/g, '')) ? s : null;
}

/**
 * Makes an element: classes, text, attributes, data, CSSOM styles (never a style attribute), listeners and children.
 * @param {Document} doc
 * @param {string} tag
 * @param {{cls: string, text: string, attrs: Object<string, string>, data: Object<string, string>, style: Object<string, string>, on: Object<string, function(Event): void>}} [p]
 * @param {Array<(Node|string|null)>} [children]
 * @returns {HTMLElement}
 */
export function h(doc, tag, p, children) {
  const el = doc.createElement(tag);
  p = p || {};
  if (p.cls) { el.className = p.cls; }
  if (p.text !== undefined && p.text !== null) { el.textContent = p.text; }
  if (p.attrs) { Object.keys(p.attrs).forEach((k) => { if (p.attrs[k] !== null && p.attrs[k] !== undefined && p.attrs[k] !== false) { el.setAttribute(k, p.attrs[k] === true ? '' : String(p.attrs[k])); } }); }
  if (p.data) { Object.keys(p.data).forEach((k) => { el.dataset[k] = p.data[k]; }); }
  if (p.style) { Object.keys(p.style).forEach((k) => { el.style.setProperty(k, p.style[k]); }); }
  if (p.on) { Object.keys(p.on).forEach((k) => el.addEventListener(k, p.on[k])); }
  (children || []).forEach((c) => { if (c !== null && c !== undefined) { el.appendChild(typeof c === 'string' ? doc.createTextNode(c) : c); } });
  return el;
}

/**
 * Keeps a floating element inside the window: at (x, y), moved left or up when it would leave it.
 * @param {HTMLElement} el
 * @param {number} x
 * @param {number} y
 * @returns {void}
 */
export function placeAt(el, x, y) {
  const win = el.ownerDocument.defaultView, r = el.getBoundingClientRect();
  el.style.left = Math.max(4, Math.min(x, win.innerWidth - r.width - 8)) + 'px';
  el.style.top = Math.max(4, Math.min(y, win.innerHeight - r.height - 8)) + 'px';
}

/**
 * Places a floating element (position: fixed) under an anchor, or above it when there is no room below, inside the window.
 * @param {HTMLElement} el
 * @param {Element} anchor
 * @param {{gap: number, matchWidth: boolean}} [o] gap default 4 px; matchWidth: at least as wide as the anchor
 * @returns {void}
 */
export function placeUnder(el, anchor, o) {
  o = o || {};
  const win = el.ownerDocument.defaultView, a = anchor.getBoundingClientRect(), gap = o.gap === undefined ? 4 : o.gap;
  if (o.matchWidth) { el.style.minWidth = a.width + 'px'; }
  const r = el.getBoundingClientRect();
  const below = a.bottom + gap + r.height <= win.innerHeight - 4 || a.top - gap - r.height < 4;
  placeAt(el, a.left, below ? a.bottom + gap : a.top - gap - r.height);
}
