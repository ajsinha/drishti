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
/* A page's code areas made code editors: every <textarea data-di-code="yaml|python|rachana|text"> becomes a CodeEditor in its place, and
   the text area stays in the page as the value's holder, so the form it sits in submits it and the page's own script reads and writes
   it as before: reading its value reads the editor, setting it sets the editor, and each edit is an "input" event on it. Optional
   attributes: data-di-code-height (px), readonly, aria-label. A page with a code area of its own has nothing to write. */
import { CodeEditor } from './drishti_code-editor.js';

const PROTO = typeof HTMLTextAreaElement !== 'undefined' ? Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value') : null;

/**
 * Makes one text area a code editor (once); answers the editor.
 * @param {HTMLTextAreaElement} ta
 * @param {{mode: string, height: number, complete: Function, lint: Function}} [o] beside the element's own attributes
 * @returns {CodeEditor}
 */
export function upgradeCodeArea(ta, o) {
  if (ta.drishtiCode) { return ta.drishtiCode; }
  o = o || {};
  const doc = ta.ownerDocument, host = doc.createElement('div');
  host.className = 'di-code-host';
  (ta.closest('label') || ta).after(host);                    // out of a label: a click in the editor is the editor's, not the label's
  const native = PROTO || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(ta), 'value');
  const editor = new CodeEditor(host, { mode: o.mode || ta.getAttribute('data-di-code') || 'text', value: native.get.call(ta), label: ta.getAttribute('aria-label') || o.label || 'Code',
    height: o.height || Number(ta.getAttribute('data-di-code-height')) || Math.max(120, (Number(ta.getAttribute('rows')) || 8) * 19), readOnly: ta.readOnly, lineWrapping: true,
    complete: o.complete, lint: o.lint, keys: o.keys });
  ta.hidden = true;
  ta.setAttribute('data-di-code-upgraded', '');
  let quiet = false, committed = editor.getValue();           // committed: the text as the page last set it or the person last left it
  Object.defineProperty(ta, 'value', {
    configurable: true,
    get: () => editor.getValue(),
    set: (v) => { native.set.call(ta, v); quiet = true; try { editor.setValue(v); } finally { quiet = false; } committed = editor.getValue(); }   // the page's own text is no edit: no "change" on leaving
  });
  editor.on('change', (c) => {
    native.set.call(ta, c.value);
    if (!quiet && c.origin !== 'setValue') { ta.dispatchEvent(new doc.defaultView.Event('input', { bubbles: true })); }
  });
  // leaving the editor after an edit is a "change" on the text area, as a text area's is
  Promise.resolve(editor.ready).then(() => { if (editor.cm) { editor.cm.on('blur', () => { if (editor.getValue() !== committed) { committed = editor.getValue(); ta.dispatchEvent(new doc.defaultView.Event('change', { bubbles: true })); } }); } });
  ta.focus = () => editor.focus();                            // the page's focus() on its text area reaches the editor
  ta.drishtiCode = editor;
  return editor;
}

/**
 * Upgrades every <textarea data-di-code> inside a root (a page, a dialog drawn later).
 * @param {ParentNode} root
 * @returns {CodeEditor[]}
 */
export function upgradeCodeAreas(root) {
  return Array.prototype.map.call(root.querySelectorAll('textarea[data-di-code]:not([data-di-code-upgraded])'), (ta) => upgradeCodeArea(ta));
}
