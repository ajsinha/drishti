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
   every <input data-di-code="rachana"> a one-line CodeEditor (a formula, a filter: Enter adds no line, it grows as the line wraps). The
   element stays in the page as the value's holder, so the form it sits in submits it and the page's own script reads and writes it as
   before: reading its value reads the editor, setting it sets the editor, each edit is an "input" event on it, leaving the editor after
   an edit a "change", and the editor's focus and blur are its focus, focusin, blur and focusout. Optional attributes: data-di-code-height
   (px), readonly, aria-label, placeholder; options the page's script puts on the element (drishtiCodeOptions: complete, completeAfter, lint, keys)
   join the call's. An element not yet in the page is made an editor when it arrives (install's observer). */
import { CodeEditor } from './drishti_code-editor.js';

const SELECTOR = 'textarea[data-di-code]:not([data-di-code-upgraded]), input[data-di-code]:not([data-di-code-upgraded])';

/** @private the native value property of the element's kind */
function nativeValue(ta) {
  const C = ta.tagName === 'INPUT' ? (typeof HTMLInputElement !== 'undefined' ? HTMLInputElement : null) : (typeof HTMLTextAreaElement !== 'undefined' ? HTMLTextAreaElement : null);
  return (C && Object.getOwnPropertyDescriptor(C.prototype, 'value')) || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(ta), 'value');
}

/**
 * Makes one text area (or one-line input) a code editor (once); answers the editor.
 * @param {(HTMLTextAreaElement|HTMLInputElement)} ta
 * @param {{mode: string, height: number, complete: Function, completeAfter: Function, lint: Function, keys: Object}} [o] beside the element's own attributes and
 *   drishtiCodeOptions
 * @returns {CodeEditor}
 */
export function upgradeCodeArea(ta, o) {
  if (ta.drishtiCode) { return ta.drishtiCode; }
  o = Object.assign({}, ta.drishtiCodeOptions || {}, o || {});
  const doc = ta.ownerDocument, host = doc.createElement('div'), one = ta.tagName === 'INPUT', win = doc.defaultView;
  host.className = 'di-code-host' + (one ? ' di-code-line' : '');
  (ta.closest('label') || ta).after(host);                    // out of a label: a click in the editor is the editor's, not the label's
  const native = nativeValue(ta);
  const editor = new CodeEditor(host, { mode: o.mode || ta.getAttribute('data-di-code') || 'text', value: native.get.call(ta), label: ta.getAttribute('aria-label') || o.label || ta.getAttribute('placeholder') || 'Code',
    height: one ? 'auto' : o.height || Number(ta.getAttribute('data-di-code-height')) || Math.max(120, (Number(ta.getAttribute('rows')) || 8) * 19), readOnly: ta.readOnly, lineWrapping: true,
    singleLine: one, lineNumbers: !one, tabLeaves: one || !!o.tabLeaves, complete: o.complete, completeAfter: o.completeAfter, catalogue: o.catalogue, lint: o.lint, keys: o.keys });
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
    if (!quiet && c.origin !== 'setValue') { ta.dispatchEvent(new win.Event('input', { bubbles: true })); }
  });
  const fire = (names) => names.forEach((n) => ta.dispatchEvent(new win.FocusEvent(n, { bubbles: n === 'focusin' || n === 'focusout' })));
  Promise.resolve(editor.ready).then(() => {
    if (!editor.cm) { return; }
    editor.cm.on('focus', () => fire(['focus', 'focusin']));
    editor.cm.on('blur', () => {                               // leaving the editor after an edit is a "change" on the element, as a text field's is
      if (editor.getValue() !== committed) { committed = editor.getValue(); ta.dispatchEvent(new win.Event('change', { bubbles: true })); }
      fire(['blur', 'focusout']);
    });
  });
  ta.focus = () => editor.focus();                            // the page's focus() on its element reaches the editor
  ta.drishtiCode = editor;
  return editor;
}

/**
 * Upgrades every <textarea data-di-code> and <input data-di-code> inside a root (a page, a dialog drawn later).
 * @param {ParentNode} root
 * @returns {CodeEditor[]}
 */
export function upgradeCodeAreas(root) {
  return Array.prototype.map.call(root.querySelectorAll(SELECTOR), (ta) => upgradeCodeArea(ta));
}

/** The elements upgradeCodeAreas makes code editors (install's observer looks for them). */
export const CODE_AREA_SELECTOR = SELECTOR;
