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
/* The state kit: the four states every component shows in place of its content, drawn one way. Empty (nothing to show, and why), loading
   (a skeleton of the content to come, never a bare word), error (what failed, in words, with a fix: a button that retries or opens the
   setting) and stale (the figures are shown but old: since when, and a refresh). Colours, spacing and the shimmer are
   css/drishti_states.css (tokens only; reduced motion turns the shimmer off). A component asks for one block and puts it where its
   content would be; a list or a cell is "compact". */
import { h } from './drishti_dom.js';

/** The four states, in the order a component moves through them. */
export const STATES = Object.freeze(['empty', 'loading', 'error', 'stale']);

/**
 * @typedef {Object} StateOptions
 * @property {string} [text] the words ("Nothing matches", "This chart could not be drawn: ...")
 * @property {string} [detail] a second, quieter line (a code, the cause)
 * @property {{label: string, run: function(): void, href: string}} [fix] error and stale: the action ("Retry", "Refresh"); a run function or a link
 * @property {boolean} [compact] a row in a list or a cell, not a panel
 * @property {boolean} [overlay] laid over its host's content (absolute, the host is positioned): a grid's empty message, a chart's loading
 * @property {number} [lines=3] loading: the skeleton's bars
 * @property {string} [role] the ARIA role (default: alert for an error, status otherwise; "presentation" inside a listbox)
 * @property {string} [since] stale: when the figures were fresh, as the page shows it ("12:04", "3 min ago")
 */

/**
 * A skeleton: grey bars that shimmer in the place of content to come (a still block under reduced motion).
 * @param {Document} doc
 * @param {number} [lines=3]
 * @returns {HTMLElement}
 */
export function skeleton(doc, lines) {
  const n = Math.max(1, Math.min(12, lines || 3));
  const out = h(doc, 'span', { cls: 'drishti-skeleton', attrs: { 'aria-hidden': 'true' } });
  for (let i = 0; i < n; i++) { out.appendChild(h(doc, 'span', { cls: 'drishti-skeleton-bar' + (i === n - 1 && n > 1 ? ' drishti-skeleton-short' : '') })); }
  return out;
}

/**
 * Fills a block in place with one of the states (the element is kept, so a component's reference and its hidden flag stay).
 * @param {HTMLElement} el
 * @param {('empty'|'loading'|'error'|'stale')} kind
 * @param {StateOptions} [o]
 * @returns {HTMLElement}
 */
export function showState(el, kind, o) {
  o = o || {};
  if (STATES.indexOf(kind) < 0) { throw new Error('showState: no state ' + kind + '; the states are ' + STATES.join(', ')); }
  const doc = el.ownerDocument;
  el.className = 'drishti-state drishti-state-' + kind + (o.compact ? ' drishti-state-compact' : '') + (o.overlay ? ' drishti-state-overlay' : '');
  el.setAttribute('role', o.role || (kind === 'error' ? 'alert' : 'status'));
  if (kind === 'loading') { el.setAttribute('aria-busy', 'true'); } else { el.removeAttribute('aria-busy'); }
  el.setAttribute('data-state', kind);
  while (el.firstChild) { el.removeChild(el.firstChild); }
  if (kind === 'loading') { el.appendChild(skeleton(doc, o.compact ? 1 : o.lines)); }
  const words = o.text !== undefined && o.text !== null ? o.text : (kind === 'loading' ? 'Loading…' : '');
  el.appendChild(h(doc, 'span', { cls: kind === 'loading' ? 'drishti-state-text drishti-visually-hidden' : 'drishti-state-text', text: words }));
  if (kind === 'stale' && o.since) { el.appendChild(h(doc, 'span', { cls: 'drishti-state-detail', text: 'as of ' + o.since })); }
  if (o.detail) { el.appendChild(h(doc, 'span', { cls: 'drishti-state-detail', text: o.detail })); }
  if (o.fix && (kind === 'error' || kind === 'stale' || kind === 'empty')) {
    const f = o.fix;
    el.appendChild(f.href && !f.run
      ? h(doc, 'a', { cls: 'drishti-state-fix', text: f.label, attrs: { href: f.href } })
      : h(doc, 'button', { cls: 'drishti-state-fix', text: f.label, attrs: { type: 'button' }, on: { click: () => { if (f.run) { f.run(); } } } }));
  }
  return el;
}

/**
 * A new block in one of the states.
 * @param {Document} doc
 * @param {('empty'|'loading'|'error'|'stale')} kind
 * @param {StateOptions} [o]
 * @returns {HTMLElement}
 */
export function stateBlock(doc, kind, o) { return showState(doc.createElement('div'), kind, o); }

/**
 * Takes the state off a block (it keeps its element, empty).
 * @param {HTMLElement} el
 * @returns {void}
 */
export function clearState(el) {
  while (el.firstChild) { el.removeChild(el.firstChild); }
  el.className = '';
  ['role', 'aria-busy', 'data-state'].forEach((a) => el.removeAttribute(a));
}
