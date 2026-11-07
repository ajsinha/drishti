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
/* Dialogs, drawers and toasts: the one way a page opens something over itself. A dialog is the platform's <dialog> (showModal: the page
   behind is inert, Escape closes), its first field focused and focus given back on close. A drawer is a side panel that keeps Tab
   inside while open and closes on Escape. A toast is a short message in a tray (a live region): it goes by itself, a burst shows a few
   and "N more", an action (Undo) is a button in it. The look is the page's: every class name is the caller's. */
import { trapFocus } from './drishti_a11y.js';

const FIELD = 'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])';

/**
 * Opens a <dialog> as a modal (when it is not open already) and focuses its first field (or opts.focus); focus goes back to what had it
 * when the dialog closes.
 * @param {HTMLDialogElement} dialog
 * @param {{focus: (Element|string), within: Element}} [o] focus: the element (or a selector inside the dialog) to focus; within: look
 *   for the first field only inside this part of the dialog (its body)
 * @returns {HTMLDialogElement}
 */
export function openDialog(dialog, o) {
  o = o || {};
  const before = dialog.ownerDocument.activeElement;
  if (!dialog.open) {
    dialog.showModal();
    if (before && before.focus) {
      dialog.addEventListener('close', () => { if (dialog.ownerDocument.contains(before)) { before.focus(); } }, { once: true });
    }
  }
  const f = typeof o.focus === 'string' ? dialog.querySelector(o.focus) : o.focus || (o.within || dialog).querySelector(FIELD);
  if (f && f.focus) { f.focus(); }
  return dialog;
}

/**
 * Closes a <dialog> when it is open.
 * @param {HTMLDialogElement} dialog
 * @param {string} [value] its returnValue
 * @returns {void}
 */
export function closeDialog(dialog, value) { if (dialog && dialog.open) { dialog.close(value); } }

/**
 * Opens a drawer (any element): shown, marked open (data-open, aria-hidden false), Tab kept inside, Escape and the returned function
 * close it; focus goes back to what had it.
 * @param {HTMLElement} el
 * @param {{onClose: function(): void, initial: Element, escape: boolean}} [o] escape default true
 * @returns {function(): void} closes the drawer
 */
export function openDrawer(el, o) {
  o = o || {};
  if (el.__drishtiDrawer) { return el.__drishtiDrawer; }
  const doc = el.ownerDocument;
  el.hidden = false;
  el.setAttribute('data-open', '');
  el.setAttribute('aria-hidden', 'false');
  const release = trapFocus(el, { initial: o.initial });
  const onKey = (e) => { if (e.key === 'Escape' && o.escape !== false) { e.preventDefault(); close(); } };
  doc.addEventListener('keydown', onKey);
  function close() {
    if (!el.__drishtiDrawer) { return; }
    el.__drishtiDrawer = null;
    doc.removeEventListener('keydown', onKey);
    el.removeAttribute('data-open');
    el.setAttribute('aria-hidden', 'true');
    el.hidden = true;
    release();
    if (o.onClose) { o.onClose(); }
  }
  el.__drishtiDrawer = close;
  return close;
}

/**
 * @typedef {Object} ToastOptions
 * @property {string} [text] the message, as text
 * @property {string} [html] the message as markup (escape what is not yours)
 * @property {string} [href] the toast is a link
 * @property {string} [className] the toast's classes
 * @property {Object<string, string>} [attrs] more attributes (data-toast ...)
 * @property {{label: string, run: function(): void, className: string}} [action] a button in the toast; a click runs it and closes the toast
 * @property {number} [timeout] ms before it goes (the tray's default; 0: it stays)
 */

/**
 * A tray of toasts.
 */
export class ToastTray {
  /**
   * @param {Object} [o]
   * @param {Element} [o.parent] where the tray goes (default: document.body)
   * @param {string} [o.className='drishti-toasts'] the tray's classes
   * @param {boolean} [o.bare=false] no tray element: the toasts go straight into parent
   * @param {number} [o.max=3] at most this many at once; more are counted in one "more" toast
   * @param {boolean} [o.single=false] a new toast replaces the one shown
   * @param {number} [o.timeout=9000]
   * @param {{href: string, className: string, html: function(number): string, timeout: number}} [o.more] the "N more" toast
   * @param {Document} [o.document]
   */
  constructor(o) {
    this.o = Object.assign({ className: 'drishti-toasts', max: 3, timeout: 9000, single: false, bare: false }, o);
    this.doc = this.o.document || (this.o.parent && this.o.parent.ownerDocument) || document;
    this.moreEl = null;
    this.moreCount = 0;
    this.shown = [];
    if (this.o.bare) { this.tray = this.o.parent || this.doc.body; } else {
      this.tray = this.doc.createElement('div');
      this.tray.className = this.o.className;
      this.tray.setAttribute('role', 'status');
      this.tray.setAttribute('aria-live', 'polite');
      (this.o.parent || this.doc.body).appendChild(this.tray);
    }
  }

  /**
   * Shows a toast.
   * @param {ToastOptions} t
   * @returns {?{element: HTMLElement, close: function(): void}} null when the toast was counted in "more"
   */
  show(t) {
    if (this.o.single) { this.shown.slice().forEach((s) => s.close()); }
    this.shown = this.shown.filter((s) => s.element.isConnected);
    if (!this.o.single && this.o.more && this.shown.length >= this.o.max) {
      this.moreCount++;
      const m = this.o.more;
      if (!this.moreEl) {
        this.moreEl = this.doc.createElement(m.href ? 'a' : 'div');
        this.moreEl.className = m.className || '';
        if (m.href) { this.moreEl.href = m.href; }
        this.tray.appendChild(this.moreEl);
        setTimeout(() => { if (this.moreEl) { this.moreEl.remove(); this.moreEl = null; this.moreCount = 0; } }, m.timeout || this.o.timeout);
      }
      this.moreEl.innerHTML = m.html ? m.html(this.moreCount) : '<b>+' + this.moreCount + '</b> more';
      return null;
    }
    const el = this.doc.createElement(t.href ? 'a' : 'div');
    if (t.className) { el.className = t.className; }
    if (t.href) { el.href = t.href; }
    Object.keys(t.attrs || {}).forEach((k) => el.setAttribute(k, t.attrs[k]));
    if (t.html !== undefined) { el.innerHTML = t.html; } else if (t.action) {
      const s = this.doc.createElement('span'); s.textContent = t.text || ''; el.appendChild(s);
    } else { el.textContent = t.text || ''; }
    let timer = 0;
    const entry = { element: el, close: () => { clearTimeout(timer); el.remove(); this.shown = this.shown.filter((x) => x !== entry); } };
    if (t.action) {
      const b = this.doc.createElement('button');
      b.type = 'button';
      if (t.action.className) { b.className = t.action.className; }
      b.textContent = t.action.label;
      b.addEventListener('click', () => { entry.close(); t.action.run(); });
      el.appendChild(b);
    }
    this.tray.insertBefore(el, this.o.bare ? null : this.moreEl);
    this.shown.push(entry);
    const ms = t.timeout !== undefined ? t.timeout : this.o.timeout;
    if (ms) { timer = setTimeout(entry.close, ms); }
    return entry;
  }
}

let shared = null;
/**
 * Shows a toast in the page's shared tray (made on first use).
 * @param {(ToastOptions|string)} t the options, or the text
 * @returns {?{element: HTMLElement, close: function(): void}}
 */
export function toast(t) {
  if (!shared || !shared.tray.isConnected) { shared = new ToastTray(); }
  return shared.show(typeof t === 'string' ? { text: t } : t);
}
