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
/* How the renderer makes DOM from a ViewModel (docs/architecture/CLIENT_RENDERER.md section 9): drishti-core's element maker with
   drishti-core's rules for untrusted data in front of it (attrAllowed, safeUrl). Text goes in as text nodes, attributes through the
   allow-list: no innerHTML, no outerHTML, no insertAdjacentHTML, no eval and no new Function anywhere in the library (a test reads the
   sources). A value from the ViewModel can never become markup, an event handler, a style attribute or a script URL. */
import { h as make, attrAllowed, safeUrl } from '../../drishti-core/src/drishti_index.js';

const warned = new Set();
function warn(msg) { if (!warned.has(msg)) { warned.add(msg); if (typeof console !== 'undefined') { console.warn('drishti-renderer: ' + msg); } } }

/**
 * The attributes that may be set, values as text (null, undefined and false are left out; true is an empty attribute); a refused name
 * or an unsafe link is dropped and said once in the browser's console.
 * @param {?Object<string, *>} attrs
 * @returns {Object<string, (string|boolean)>}
 */
export function safeAttrs(attrs) {
  const out = {};
  if (!attrs) { return out; }
  Object.keys(attrs).forEach((k) => {
    const v = attrs[k];
    if (v === undefined || v === null || v === false) { return; }
    if (!attrAllowed(k)) { warn('attribute refused: ' + k); return; }
    if (k === 'href') { const ok = safeUrl(v); if (ok === null) { warn('unsafe link dropped'); return; } out.href = ok; return; }
    out[k] = v === true ? true : String(v);
  });
  return out;
}

/** @private children flattened: arrays opened, null, undefined and false left out, numbers as their text */
function flat(children, out) {
  children.forEach((c) => {
    if (c === null || c === undefined || c === false) { return; }
    if (Array.isArray(c)) { flat(c, out); return; }
    out.push(typeof c === 'object' && c.nodeType ? c : String(c));
  });
  return out;
}

/**
 * One element: h(tag, attrs, ...children). attrs: allowed attributes; children: strings, numbers, nodes, arrays of those, or null.
 * @param {string} tag
 * @param {?Object<string, *>} attrs
 * @param {...*} children
 * @returns {HTMLElement}
 */
export function h(tag, attrs, ...children) { return make(document, tag, { attrs: safeAttrs(attrs) }, flat(children, [])); }

/**
 * Appends children (text, nodes, arrays; null and undefined are nothing).
 * @param {Node} el
 * @param {Array<*>} children
 * @returns {Node}
 */
export function append(el, children) {
  flat(children, []).forEach((c) => el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c));
  return el;
}

/**
 * An icon (a Bootstrap Icons glyph), hidden from screen readers like every icon of the console's macros.
 * @param {string} name
 * @returns {HTMLElement}
 */
export const icon = (name) => h('i', { class: 'bi bi-' + name, 'aria-hidden': 'true' });

/**
 * Jinja's {{ x }} of a value: the macros never print a missing cell, so null is "".
 * @param {*} v
 * @returns {string}
 */
export function str(v) { return v === null || v === undefined ? '' : String(v); }

/**
 * Empties an element.
 * @param {Node} el
 * @returns {Node}
 */
export function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } return el; }

/**
 * The tone class of a cell as the macros write it (" t-<tone>"), or "" for none: the nine tones of the contract are drawn, any other
 * word is a class the sheet ignores (the value is untoned).
 * @param {?{tone: string}} c
 * @returns {string}
 */
export function toneClass(c) { return c && c.tone ? ' t-' + c.tone : ''; }

/**
 * Jinja's capitalize: the first character upper case, the rest lower case.
 * @param {*} s
 * @returns {string}
 */
export function capitalize(s) { s = String(s || ''); return s ? s[0].toUpperCase() + s.slice(1).toLowerCase() : s; }
