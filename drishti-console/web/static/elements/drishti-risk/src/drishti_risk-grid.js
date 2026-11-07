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
/* drishti-risk's grid preset (ELEMENTS.md 4.8): every table of the VaR and exposure screens is a <drishti-grid> in the `terminal` layout
   (the product owner's default for VaR screens, 2026-11-07) and density, made the same way. The figures are the server's: a VaR, an ES
   or any column marked `additive: false` is never summed in the browser (clientAggregation false, the console's policy), and the
   preset's columns say which are which. A screen gives its columns and rows; the preset gives the rest:

     riskGrid(host, {columns, rows, title, getRowId, fitRows, onRow})   a <drishti-grid> in host, its Grid back (el.grid)

   The screen keeps its own command line and keys, so the grid's chrome (its command line, wells, toolbar and function keys) is off
   unless asked (chrome: true); the status bar and Explain stay. A row click calls onRow(row, event) (drill to a scope, open a trade). */
import { defineGridElement } from '../../drishti-grid/src/drishti_index.js';

/** What every risk table starts from. */
export const RISK_GRID_DEFAULTS = Object.freeze({ layout: 'terminal', density: 'terminal', chrome: false, checkboxes: false,
  clientAggregation: false, rowSelection: 'single', fitRows: 25 });

/** The tones a column's cellClass gives: a loss or a breach is bad, a gain good (the console's tone classes in the grid). */
export const TONES = Object.freeze({ bad: 'dg-tone-bad', good: 'dg-tone-good', warn: 'dg-tone-warn', muted: 'dg-tone-muted', strong: 'dg-tone-strong' });

/**
 * A number column of a risk figure: right-aligned, formatted by name, never summed in the browser when it is a VaR or an ES.
 * @param {string} field
 * @param {string} header
 * @param {Object} [o] format (default 'compact'), additive (default false: VaR, ES, a limit use), width, tone (a function of the value
 *   giving a key of TONES), and any other ColumnDef property
 * @returns {Object} a ColumnDef
 */
export function figureColumn(field, header, o) {
  o = o || {};
  const def = Object.assign({ field: field, header: header, type: 'number', format: o.format || 'compact', additive: o.additive === true, width: o.width || 110 }, o);
  delete def.tone;
  if (o.tone) { def.cellClass = (v, row) => TONES[o.tone(v, row)] || null; }
  return def;
}

/**
 * Draws a risk table: a <drishti-grid> in host (made once and kept: a second call gives it new columns and rows).
 * @param {HTMLElement} host the element the grid fills
 * @param {Object} options columns, rows, title, subtitle, getRowId, fitRows (rows shown before it scrolls), onRow(row, event), explain,
 *   and anything a Grid takes (chrome: true for the terminal's command line and keys)
 * @returns {Object} the Grid (its plain API: setRows, on, getState ...)
 */
export function riskGrid(host, options) {
  const doc = host.ownerDocument, win = doc.defaultView;
  if (win && win.customElements) { defineGridElement(win.customElements, win); }
  let el = host.querySelector(':scope > drishti-grid');
  if (!el) {
    el = doc.createElement('drishti-grid');
    host.textContent = '';
    host.appendChild(el);
  }
  const o = Object.assign({}, RISK_GRID_DEFAULTS, options);
  el.setAttribute('layout', o.layout);
  el.setAttribute('density', o.density);
  if (o.label || o.title) { el.setAttribute('aria-label', o.label || o.title); }
  if (el._onRow) { el.removeEventListener('drishti:cell-clicked', el._onRow); el._onRow = null; }
  el.options = o;
  if (!el.grid && el.mount) { el.mount(); }        // a host not in the page yet (a sheet made before it is placed): drawn now all the same
  if (o.onRow) {
    el._onRow = (e) => {
      const d = e.detail || {};
      if (!d.data) { return; }                       // a leaf, or a tree node that is a row of its own
      o.onRow(d.data, e);
    };
    el.addEventListener('drishti:cell-clicked', el._onRow);
  }
  return el.grid;
}
