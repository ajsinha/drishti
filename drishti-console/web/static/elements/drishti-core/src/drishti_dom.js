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
/* The element maker every library draws with: no markup strings, no style attributes (a page with a strict CSP draws every component),
   and the floating element kept inside the window. */

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
