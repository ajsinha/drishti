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
/* Grid conveniences built on the grid's own state and conditional formats: the rule builder (a form from a schema, drishti-inputs'), a
   link that carries a saved view, and the heat-cell pivot preset. No store of their own: a rule is a ConditionalFormat the grid draws, a
   view is the grid's getState(), the preset is a set of grid options. */
import { openDialog, closeDialog } from '../../drishti-core/src/drishti_index.js';
import { Form } from '../../drishti-inputs/src/drishti_index.js';
import { CF_TYPES } from './drishti_grid-ui.js';

const OPS = { gt: 'is more than', ge: 'is at least', lt: 'is less than', le: 'is at most', eq: 'equals', between: 'is between' };
const TONES = ['bad', 'warn', 'good'];

/**
 * The words of a rule ("MTM is more than 1,000,000: bad").
 * @param {import('./drishti_grid-ui.js').ConditionalFormat} r
 * @returns {string}
 */
export function ruleText(r) {
  const on = r.measure || r.column || 'the column';
  if (r.type === 'threshold') { return on + ' ' + OPS[r.op] + ' ' + r.value + (r.op === 'between' ? ' and ' + r.value2 : '') + ': ' + (r.tone || 'bad'); }
  if (r.type === 'top') { return on + ' in the top ' + (r.percent || 10) + '% of its range: ' + (r.tone || 'bad'); }
  return on + ': ' + (r.type === 'dataBar' ? 'data bars' : 'colour scale');
}

/**
 * The problems of a rule, in words (none: an empty list).
 * @param {import('./drishti_grid-ui.js').ConditionalFormat} r
 * @returns {string[]}
 */
export function ruleProblems(r) {
  const out = [];
  if (!r || CF_TYPES.indexOf(r.type) < 0) { return ['The type is one of ' + CF_TYPES.join(', ')]; }
  if (!r.column && !r.measure) { out.push('Name the column the rule formats'); }
  if (r.type === 'threshold') {
    if (!OPS[r.op]) { out.push('The comparison is one of ' + Object.keys(OPS).join(', ')); }
    if (!Number.isFinite(Number(r.value)) || r.value === '' || r.value === null) { out.push('Give the figure to compare with'); }
    if (r.op === 'between' && !Number.isFinite(Number(r.value2))) { out.push('Give the upper figure'); }
  }
  if (r.type === 'top' && !(Number(r.percent) >= 1 && Number(r.percent) <= 100)) { out.push('The percent is 1 to 100'); }
  if ((r.type === 'threshold' || r.type === 'top') && r.tone && TONES.indexOf(r.tone) < 0) { out.push('The tone is one of ' + TONES.join(', ')); }
  return out;
}

/**
 * The schema of the rule form (drishti-inputs' Form) for the columns of a grid.
 * @param {Array<{id: string, header: string}>} columns
 * @returns {Object}
 */
export function ruleFormSchema(columns) {
  return { type: 'object', title: 'Conditional format', required: ['column', 'type'], properties: {
    column: { type: 'string', title: 'Column', enum: columns.map((c) => c.id), enumLabels: columns.map((c) => c.header || c.id) },
    type: { type: 'string', title: 'Kind', enum: CF_TYPES.slice(), enumLabels: ['Colour scale', 'Data bars', 'Compare with a figure', 'Top share of the range'], default: 'threshold' },
    op: { type: 'string', title: 'Comparison', enum: Object.keys(OPS), enumLabels: Object.keys(OPS).map((k) => OPS[k]), default: 'gt', when: { field: 'type', equals: 'threshold' } },
    value: { type: 'number', title: 'Figure', when: { field: 'type', equals: 'threshold' } },
    value2: { type: 'number', title: 'and', when: { field: 'op', equals: 'between' } },
    percent: { type: 'number', title: 'Top percent', default: 10, when: { field: 'type', equals: 'top' } },
    tone: { type: 'string', title: 'Tone', enum: TONES.slice(), enumLabels: ['Bad (red)', 'Warning (amber)', 'Good (green)'], default: 'bad' } } };
}

/**
 * Opens the rule builder over a grid: the form in a dialog; Save adds the rule to the grid's conditional formats.
 * @param {Object} grid a Grid (drishti-grid): its model's columns and setConditionalFormats
 * @param {{onAdded: function(Object): void}} [o]
 * @returns {{dialog: HTMLDialogElement, form: Form, close: function(): void}}
 */
export function openRuleBuilder(grid, o) {
  const doc = grid.doc, dlg = doc.createElement('dialog');
  dlg.className = 'dg-rule-builder';
  dlg.setAttribute('aria-label', 'Conditional format');
  doc.body.appendChild(dlg);
  const cols = grid.model.shownColumns().filter((c) => c.type === 'number');
  const form = new Form(dlg, { schema: ruleFormSchema(cols), submitLabel: 'Add rule', doneText: '', onSubmit: (v) => {
    const rule = Object.assign({}, v);
    const bad = ruleProblems(rule);
    if (bad.length) { throw new Error(bad[0]); }
    grid.setConditionalFormats(grid.formats.concat([rule]));
    if (o && o.onAdded) { o.onAdded(rule); }
    close();
  } });
  const close = () => { closeDialog(dlg); form.destroy(); dlg.remove(); };
  dlg.addEventListener('close', () => { if (dlg.parentNode) { form.destroy(); dlg.remove(); } });
  openDialog(dlg);
  return { dialog: dlg, form: form, close: close };
}

/**
 * A grid's state as text safe in an address (base64url of its JSON, versioned).
 * @param {Object} state a Grid's getState()
 * @returns {string}
 */
export function encodeView(state) {
  const json = JSON.stringify({ v: 1, s: state });
  const bytes = typeof Buffer !== 'undefined' ? Buffer.from(json, 'utf8').toString('base64') : btoa(String.fromCharCode(...new TextEncoder().encode(json)));
  return bytes.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * The state an address's text carries; throws in words when it is not one of ours.
 * @param {string} text
 * @returns {Object}
 */
export function decodeView(text) {
  try {
    const b = String(text).replace(/-/g, '+').replace(/_/g, '/'), pad = b + '==='.slice((b.length + 3) % 4);
    const json = typeof Buffer !== 'undefined' ? Buffer.from(pad, 'base64').toString('utf8') : new TextDecoder().decode(Uint8Array.from(atob(pad), (c) => c.charCodeAt(0)));
    const o = JSON.parse(json);
    if (!o || o.v !== 1 || typeof o.s !== 'object') { throw new Error('not a view'); }
    return o.s;
  } catch (e) { throw new Error('This link does not carry a grid view (it may be cut short).'); }
}

/**
 * The link that opens a page with a view laid out: the address with the view in its fragment (#grid-view=...), so a server never sees it.
 * @param {string} href the page's address
 * @param {Object} state a Grid's getState()
 * @param {string} [key='grid-view'] the fragment's name (one per grid on a page)
 * @returns {string}
 */
export function shareLink(href, state, key) {
  return String(href).replace(/#.*$/, '') + '#' + (key || 'grid-view') + '=' + encodeView(state);
}

/**
 * The view an address carries, or null when it has none.
 * @param {string} href
 * @param {string} [key='grid-view']
 * @returns {?Object}
 */
export function readSharedView(href, key) {
  const m = new RegExp('[#&]' + (key || 'grid-view') + '=([A-Za-z0-9_-]+)').exec(String(href));
  return m ? decodeView(m[1]) : null;
}

/**
 * The heat-cell pivot: every value column coloured by its figure on a scale, with the marginal totals (the grand column and the column
 * group totals) and the grand row. Spread into the grid's options: new Grid(el, Object.assign({columns, rows}, heatPivotPreset(['mtm', 'var'])))
 * @param {string[]} measures the value columns (ids or pivot value labels)
 * @param {{colour: string, pinTotals: boolean}} [o] colour: a theme token (default --d-accent)
 * @returns {Object} grid options
 */
export function heatPivotPreset(measures, o) {
  o = o || {};
  return { conditionalFormats: measures.map((m) => ({ measure: m, column: m, type: 'colourScale', colour: o.colour || '--d-accent' })),
    grandColumn: true, columnGroupTotals: true, pinTotals: o.pinTotals !== false };
}
