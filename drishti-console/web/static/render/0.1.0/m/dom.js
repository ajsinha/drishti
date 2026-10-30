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
/* The only way the renderer makes DOM (docs/architecture/CLIENT_RENDERER.md section 9). Elements are created with createElement,
   text goes in as text nodes, attributes through setAttribute after an allow-list: no innerHTML, no outerHTML, no insertAdjacentHTML,
   no eval and no new Function anywhere in the library (a test reads the sources). A value from the ViewModel can never become markup,
   an event handler, a style attribute or a script URL. */

/** Attribute names the renderer may set: the macros' own, plus data-* and aria-*. Never on* and never style. */
const ATTRS = new Set(('class id role title href target rel type name value placeholder for colspan rowspan scope tabindex hidden disabled '
  + 'open datetime lang dir aria-label aria-hidden').split(' '));
/** A link the renderer may write: a path on this or the configured server, a fragment, or http(s). Not javascript:, data:, vbscript:. */
const SAFE_URL = /^(?:\/(?!\/)|#|\?|\.\/|https?:\/\/)/i;

/** Whether an attribute name is allowed (the allow-list, data-* and aria-*; never an event handler or style). */
export function attrAllowed(name) {
  const n = String(name).toLowerCase();
  if (n.startsWith('on') || n === 'style' || n === 'srcdoc' || n === 'src' || n === 'formaction' || n === 'xlink:href') { return false; }
  return ATTRS.has(n) || /^data-[a-z0-9-]+$/.test(n) || /^aria-[a-z]+$/.test(n);
}

/** The URL itself when it is safe to put in href (control characters and spaces around it ignored), else null. */
export function safeUrl(u) {
  if (u == null) { return null; }
  const s = String(u);
  return SAFE_URL.test(s.replace(/[\u0000- ]/g, '')) ? s : null;
}

/** Sets an attribute through the allow-list; a refused name or an unsafe href is dropped (and says so in the console once). */
export function attr(el, name, value) {
  if (value === undefined || value === null || value === false) { return el; }
  if (!attrAllowed(name)) { warn('attribute refused: ' + name); return el; }
  if (name === 'href') {
    const ok = safeUrl(value);
    if (ok == null) { warn('unsafe link dropped'); return el; }
    el.setAttribute('href', ok);
    return el;
  }
  el.setAttribute(name, value === true ? '' : String(value));
  return el;
}

const warned = new Set();
function warn(msg) { if (!warned.has(msg)) { warned.add(msg); if (typeof console !== 'undefined') { console.warn('drishti-render: ' + msg); } } }

/**
 * h(tag, attrs, ...children): one element. attrs: an object of allowed attributes (null and false are left out, true is an empty
 * attribute); children: strings (text nodes), numbers (their text), nodes, arrays of those, or null (nothing).
 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) { for (const k of Object.keys(attrs)) { attr(el, k, attrs[k]); } }
  append(el, children);
  return el;
}

/** Appends children (text, nodes, arrays; null and undefined are nothing). */
export function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) { continue; }
    if (Array.isArray(c)) { append(el, c); continue; }
    el.appendChild(typeof c === 'object' && c.nodeType ? c : document.createTextNode(String(c)));
  }
  return el;
}

/** A text node. */
export const text = (s) => document.createTextNode(s == null ? '' : String(s));

/** An icon (Bootstrap Icons glyph), hidden from screen readers like every icon of the macros. */
export const icon = (name) => h('i', { class: 'bi bi-' + name, 'aria-hidden': 'true' });

/** Jinja's `{{ x }}` of a value: None prints "None" in Jinja, but the macros never print a missing cell, so null is "". */
export function str(v) { return v == null ? '' : String(v); }

/** Empties an element. */
export function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } return el; }

/** A fragment of several nodes. */
export function frag(...children) { return append(document.createDocumentFragment(), children); }
