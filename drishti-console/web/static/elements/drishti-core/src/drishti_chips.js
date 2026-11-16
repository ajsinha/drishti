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
/* Chips: a list attribute's values (TAGS: ["fx", "rates"]) as chips, the one display of a list everywhere (detail panels, Sutras, grid
   cells). Collapsed it shows the first few chips and "+N more ▾"; a click (or Enter / Space) expands it vertically in place to every value,
   and "▴ less" collapses it again; the toggle carries aria-expanded. In a grid cell (compact) it shows the first chip and a count badge,
   and expanding grows the row downward over the rows below. Theme tokens only (drishti_chips.css), so dark and light both work. */
import { h } from './drishti_dom.js';

/**
 * @typedef {Object} ChipsOptions
 * @property {Array<string|number|boolean>|string} [values] the list, or its JSON text (["fx","rates"]) as a promoted column holds it
 * @property {number} [max] chips shown collapsed (default 3; compact: 1)
 * @property {boolean} [compact] a grid cell's form: the first chip and a count badge
 * @property {boolean} [expanded] start expanded
 * @property {string} [label] what the list is, for screen readers ("Tags")
 */

/**
 * A list attribute's values as texts: an array's elements, the JSON text of one read as it, a single value a list of one; blanks left out.
 * @param {*} v
 * @returns {string[]}
 */
export function listValues(v) {
  if (v === null || v === undefined) { return []; }
  let items = v;
  if (typeof v === 'string') {
    const t = v.trim();
    items = [v];
    if (t.startsWith('[')) {
      try { const parsed = JSON.parse(t); if (Array.isArray(parsed)) { items = parsed; } } catch (e) { items = [v]; }
    }
  } else if (!Array.isArray(v)) {
    items = [v];
  }
  return items.filter((x) => x !== null && x !== undefined && String(x).trim() !== '')
    .map((x) => (typeof x === 'object' ? JSON.stringify(x) : String(x)));
}

/** Chips over a host element: new Chips(host, {values}); setValues() redraws, toggle() expands or collapses. */
export class Chips {
  /**
   * @param {HTMLElement} host
   * @param {ChipsOptions} [options]
   */
  constructor(host, options) {
    this.host = host;
    this.options = Object.assign({ max: 3, compact: false, expanded: false, label: '' }, options || {});
    this.expanded = !!this.options.expanded;
    this.values = listValues(this.options.values);
    this.render();
  }

  /**
   * Replaces the values and redraws (collapsed).
   * @param {*} values
   * @returns {void}
   */
  setValues(values) {
    this.values = listValues(values);
    this.expanded = false;
    this.render();
  }

  /**
   * Expands (true), collapses (false) or flips; emits drishti:chips-toggle with {expanded}.
   * @param {boolean} [open]
   * @returns {void}
   */
  toggle(open) {
    this.expanded = open === undefined ? !this.expanded : !!open;
    this.render();
    const t = this.host.querySelector('.drishti-chips-toggle');
    if (t) { t.focus(); }
    this.host.dispatchEvent(new CustomEvent('drishti:chips-toggle', { bubbles: true, detail: { expanded: this.expanded, values: this.values } }));
  }

  /**
   * Draws the chips: collapsed, the first few and "+N more ▾" (compact: the first and a count); expanded, every value and "▴ less".
   * @returns {void}
   */
  render() {
    const doc = this.host.ownerDocument;
    const o = this.options;
    const max = Math.max(1, o.compact ? 1 : o.max || 3);
    const hidden = Math.max(0, this.values.length - max);
    const shown = this.expanded ? this.values : this.values.slice(0, max);
    const cls = 'drishti-chips' + (o.compact ? ' drishti-chips-compact' : '') + (this.expanded ? ' drishti-chips-open' : '');
    const kids = shown.map((v) => h(doc, 'span', { cls: 'drishti-chip', text: v, attrs: { role: 'listitem', title: v } }));
    const list = h(doc, 'span', { cls: 'drishti-chips-list', attrs: { role: 'list', 'aria-label': o.label || null } }, kids);
    const parts = [list];
    if (hidden > 0) {
      const words = this.expanded ? '▴ less' : o.compact ? '+' + hidden : '+' + hidden + ' more ▾';
      const toggle = h(doc, 'button', {
        cls: 'drishti-chips-toggle' + (o.compact && !this.expanded ? ' drishti-chips-count' : ''), text: words,
        attrs: { type: 'button', 'aria-expanded': this.expanded ? 'true' : 'false',
          'aria-label': this.expanded ? 'Show fewer' : 'Show all ' + this.values.length + ' values' },
        on: {
          click: (e) => { e.stopPropagation(); this.toggle(); },
          keydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); this.toggle(); } },
        },
      });
      parts.push(toggle);
    }
    this.host.replaceChildren(h(doc, 'span', { cls }, parts));
  }
}

/**
 * A list attribute as a chips element (a renderer's or a grid cell's one call).
 * @param {Document} doc
 * @param {*} values the list or its JSON text
 * @param {ChipsOptions} [options]
 * @returns {HTMLElement}
 */
export function chipList(doc, values, options) {
  const host = h(doc, 'span', { cls: 'drishti-chips-host' });
  const c = new Chips(host, Object.assign({}, options || {}, { values }));
  host.chips = c;
  return host;
}

/**
 * Upgrades the list values a server page wrote (span[data-drishti-chips] holding the list's JSON text) into chips, once each.
 * @param {ParentNode} root
 * @returns {number} how many were upgraded
 */
export function upgradeChips(root) {
  let n = 0;
  (root || document).querySelectorAll('[data-drishti-chips]:not([data-drishti-chips-on])').forEach((el) => {
    el.setAttribute('data-drishti-chips-on', '');
    el.chips = new Chips(el, { values: el.getAttribute('data-drishti-chips'), label: el.getAttribute('aria-label') || '' });
    n++;
  });
  return n;
}

/**
 * Whether a value is a list attribute (an array of simple values, or the JSON text of one).
 * @param {*} v
 * @returns {boolean}
 */
export function isListValue(v) {
  if (Array.isArray(v)) { return v.every((x) => x === null || typeof x !== 'object'); }
  if (typeof v === 'string' && v.trim().startsWith('[')) {
    try { const p = JSON.parse(v); return Array.isArray(p) && p.every((x) => x === null || typeof x !== 'object'); } catch (e) { return false; }
  }
  return false;
}
