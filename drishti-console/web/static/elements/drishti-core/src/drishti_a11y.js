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
/* Accessibility helpers: a focus trap (Tab stays inside a dialog or drawer, and focus goes back where it was when it closes), one polite
   and one assertive live region a page shares (announce), and a roving tabindex (one Tab stop for a toolbar, a list or a grid's header;
   arrows move inside). The key arithmetic (rovingIndex) is pure and runs under Node. */

/** What can take focus, in a selector. */
export const FOCUSABLE = 'a[href], area[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), ' +
  'textarea:not([disabled]), iframe, [contenteditable="true"], [tabindex]:not([tabindex="-1"])';

/**
 * The elements inside a container that take focus by Tab, in order (hidden ones left out).
 * @param {Element} container
 * @returns {Element[]}
 */
export function focusable(container) {
  return Array.prototype.filter.call(container.querySelectorAll(FOCUSABLE), (el) =>
    !el.hasAttribute('inert') && !el.closest('[inert]') && (el.offsetParent !== null || el === container.ownerDocument.activeElement || el.getClientRects().length > 0));
}

/**
 * Keeps Tab and Shift+Tab inside a container until released; focuses its first focusable element (or opts.initial) now, and gives focus
 * back to what had it when released.
 * @param {Element} container
 * @param {{initial: Element, returnFocus: boolean}} [o] returnFocus default true
 * @returns {function(): void} releases the trap
 */
export function trapFocus(container, o) {
  o = o || {};
  const doc = container.ownerDocument;
  const before = doc.activeElement;
  const onKey = (e) => {
    if (e.key !== 'Tab') { return; }
    const f = focusable(container);
    if (!f.length) { e.preventDefault(); return; }
    const first = f[0], last = f[f.length - 1];
    if (e.shiftKey && (doc.activeElement === first || !container.contains(doc.activeElement))) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && (doc.activeElement === last || !container.contains(doc.activeElement))) { e.preventDefault(); first.focus(); }
  };
  doc.addEventListener('keydown', onKey, true);
  const start = o.initial || focusable(container)[0];
  if (start && start.focus) { start.focus(); }
  return function release() {
    doc.removeEventListener('keydown', onKey, true);
    if (o.returnFocus !== false && before && before.focus && doc.contains(before)) { before.focus(); }
  };
}

const regions = {};
/**
 * Says something to a screen reader without moving focus (one shared live region per politeness, made on first use).
 * @param {string} text
 * @param {{politeness: ('polite'|'assertive'), document: Document}} [o]
 * @returns {void}
 */
export function announce(text, o) {
  o = o || {};
  const doc = o.document || (typeof document !== 'undefined' ? document : null);
  if (!doc) { return; }
  const p = o.politeness === 'assertive' ? 'assertive' : 'polite';
  let r = regions[p];
  if (!r || !doc.contains(r)) {
    r = doc.createElement('div');
    r.setAttribute('aria-live', p);
    r.setAttribute('role', p === 'assertive' ? 'alert' : 'status');
    r.setAttribute('data-drishti-live', p);
    r.style.cssText = 'position:absolute;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0';
    doc.body.appendChild(r);
    regions[p] = r;
  }
  r.textContent = '';
  setTimeout(() => { r.textContent = String(text); }, 30);   // emptied first, so the same words twice are said twice
}

/**
 * The index a key moves to in a roving tabindex: arrows by one (by orientation), Home and End to the ends; null when the key does not
 * move.
 * @param {number} index the current index
 * @param {number} count how many items
 * @param {string} key KeyboardEvent.key
 * @param {{orientation: ('horizontal'|'vertical'|'both'), wrap: boolean, columns: number}} [o] columns: a grid of that many per row
 * @returns {?number}
 */
export function rovingIndex(index, count, key, o) {
  o = o || {};
  if (!count) { return null; }
  const orient = o.orientation || 'horizontal', cols = o.columns || 0;
  let step = 0;
  if ((orient !== 'vertical' || cols) && key === 'ArrowRight') { step = 1; }
  else if ((orient !== 'vertical' || cols) && key === 'ArrowLeft') { step = -1; }
  else if ((orient !== 'horizontal' || cols) && key === 'ArrowDown') { step = cols || 1; }
  else if ((orient !== 'horizontal' || cols) && key === 'ArrowUp') { step = -(cols || 1); }
  else if (key === 'Home') { return 0; }
  else if (key === 'End') { return count - 1; }
  else { return null; }
  let n = index + step;
  if (o.wrap) { n = ((n % count) + count) % count; } else { n = Math.max(0, Math.min(count - 1, n)); }
  return n;
}

/**
 * Makes a container's items one Tab stop: the current item has tabindex 0, the others -1; arrows (and Home, End) move between them.
 * @param {Element} container
 * @param {{selector: string, orientation: string, wrap: boolean, columns: number, onMove: function(Element, number): void}} [o]
 *   selector: the items (default: [role=tab], [role=option], [role=menuitem], [role=gridcell], button)
 * @returns {{focus: function(number): void, refresh: function(): void, destroy: function(): void, index: function(): number}}
 */
export function rovingTabindex(container, o) {
  o = o || {};
  const sel = o.selector || '[role="tab"], [role="option"], [role="menuitem"], [role="gridcell"], button';
  let at = 0;
  const items = () => Array.prototype.slice.call(container.querySelectorAll(sel)).filter((el) => !el.disabled);
  const apply = () => { items().forEach((el, i) => el.setAttribute('tabindex', i === at ? '0' : '-1')); };
  const focus = (i) => {
    const l = items();
    if (!l.length) { return; }
    at = Math.max(0, Math.min(l.length - 1, i));
    apply();
    l[at].focus();
    if (o.onMove) { o.onMove(l[at], at); }
  };
  const onKey = (e) => {
    const l = items(), i = l.indexOf(e.target);
    if (i < 0) { return; }
    const n = rovingIndex(i, l.length, e.key, o);
    if (n === null) { return; }
    e.preventDefault();
    focus(n);
  };
  const onFocus = (e) => { const i = items().indexOf(e.target); if (i >= 0 && i !== at) { at = i; apply(); } };
  container.addEventListener('keydown', onKey);
  container.addEventListener('focusin', onFocus);
  apply();
  return {
    focus: focus,
    refresh: apply,
    index: () => at,
    destroy: () => { container.removeEventListener('keydown', onKey); container.removeEventListener('focusin', onFocus); }
  };
}
