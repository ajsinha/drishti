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
/* The query builder: conditions as chips ("desk = Rates", "VaR > 100"), joined by AND. A person adds one with a field, an operator and a
   value (three native controls and Add; Enter in the value adds), removes one with its ×, or with Backspace in the empty value box; the
   whole query is also text that can be typed or pasted (parseQuery, queryText), so the chips are one view of it. The result is data
   ({field, op, value}) the host turns into a server filter; the builder never evaluates anything. */
import { InputBase } from './drishti_inputs-dom.js';
import { announce } from '../../drishti-core/src/drishti_index.js';

/** The operators by field type, with their words. */
export const QUERY_OPS = Object.freeze({ eq: '=', ne: '!=', gt: '>', ge: '>=', lt: '<', le: '<=', in: 'in', contains: 'contains' });
const BY_TYPE = { text: ['eq', 'ne', 'contains', 'in'], enum: ['eq', 'ne', 'in'], number: ['eq', 'ne', 'gt', 'ge', 'lt', 'le'], date: ['eq', 'gt', 'ge', 'lt', 'le'] };

/**
 * @typedef {Object} QueryField
 * @property {string} id
 * @property {string} [label]
 * @property {('text'|'number'|'enum'|'date')} [type='text']
 * @property {string[]} [values] enum: the values offered
 */

/**
 * @typedef {Object} QueryCondition
 * @property {string} field
 * @property {string} op a key of QUERY_OPS
 * @property {(string|number|string[])} value
 */

/**
 * A query as text ("desk = Rates AND var > 100"; a value with a space is quoted; in takes a list: desk in (Rates, FX)).
 * @param {QueryCondition[]} conds
 * @returns {string}
 */
export function queryText(conds) {
  const q = (v) => (/^[\w.\-+:]+$/.test(String(v)) ? String(v) : '"' + String(v).replace(/"/g, '\\"') + '"');
  return conds.map((c) => c.field + ' ' + QUERY_OPS[c.op] + ' ' + (Array.isArray(c.value) ? '(' + c.value.map(q).join(', ') + ')' : q(c.value))).join(' AND ');
}

/**
 * The conditions of a query text; throws in words at the first thing it cannot read.
 * @param {string} text
 * @param {QueryField[]} [fields] when given, a field must be one of them and a number must be a number
 * @returns {QueryCondition[]}
 */
export function parseQuery(text, fields) {
  const out = [];
  const parts = String(text).trim() ? String(text).split(/\s+AND\s+/i) : [];
  const ops = Object.keys(QUERY_OPS).map((k) => [k, QUERY_OPS[k]]).sort((a, b) => b[1].length - a[1].length);
  parts.forEach((part, i) => {
    const m = /^\s*([\w.]+)\s*(>=|<=|!=|=|>|<|\bin\b|\bcontains\b)\s*(.+?)\s*$/i.exec(part);
    if (!m) { throw new Error('Condition ' + (i + 1) + ' ("' + part.trim() + '") is not "field operator value"'); }
    const op = (ops.find((o) => o[1].toLowerCase() === m[2].toLowerCase()) || [])[0], f = (fields || []).find((x) => x.id === m[1]);
    if (fields && !f) { throw new Error('There is no field ' + m[1] + '; the fields are ' + fields.map((x) => x.id).join(', ')); }
    const unq = (s) => (s.length > 1 && s[0] === '"' && s[s.length - 1] === '"' ? s.slice(1, -1).replace(/\\"/g, '"') : s);
    let value;
    if (op === 'in') { value = m[3].replace(/^\(|\)$/g, '').split(',').map((x) => unq(x.trim())).filter(Boolean); } else { value = unq(m[3]); }
    if (f && f.type === 'number' && !Array.isArray(value)) { if (!Number.isFinite(Number(value))) { throw new Error(m[1] + ' is a number; "' + value + '" is not'); } value = Number(value); }
    if (f && BY_TYPE[f.type || 'text'].indexOf(op) < 0) { throw new Error(m[1] + ' cannot be compared with ' + m[2]); }
    out.push({ field: m[1], op: op, value: value });
  });
  return out;
}

/**
 * @typedef {Object} QueryBuilderOptions
 * @property {QueryField[]} fields
 * @property {QueryCondition[]} [value] the starting conditions
 * @property {string} [label='Filter']
 */

/** The chips and the add row. Events: change {value, text}. */
export class QueryBuilder extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {QueryBuilderOptions} options
   */
  constructor(host, options) {
    super(host, Object.assign({ label: 'Filter', value: [] }, options), 'di-query');
    if (!this.o.fields || !this.o.fields.length) { throw new Error('a query builder needs fields'); }
    this.conds = this.o.value.map((c) => Object.assign({}, c));
    this.chips = this.el('ul', { cls: 'di-query-chips', attrs: { 'aria-label': this.o.label + ': conditions' } });
    const f = this.o.fields;
    this.field = this.el('select', { cls: 'di-field', attrs: { 'aria-label': 'Field' }, on: { change: () => this.fieldChanged() } }, f.map((x) => this.el('option', { text: x.label || x.id, attrs: { value: x.id } })));
    this.op = this.el('select', { cls: 'di-field', attrs: { 'aria-label': 'Operator' } });
    this.val = this.el('input', { cls: 'di-field', attrs: { type: 'text', 'aria-label': 'Value', placeholder: 'value' }, on: { keydown: (e) => this.valueKey(e) } });
    this.add = this.el('button', { cls: 'di-btn', text: 'Add', attrs: { type: 'button' }, on: { click: () => this.addFromRow() } });
    this.err = this.el('div', { cls: 'di-query-error', attrs: { role: 'alert' } });
    host.append(this.el('div', { cls: 'di-query-label', text: this.o.label }), this.chips, this.el('div', { cls: 'di-query-row', attrs: { role: 'group', 'aria-label': 'Add a condition' } }, [this.field, this.op, this.val, this.add]), this.err);
    this.fieldChanged();
    this.draw();
  }

  /** @private the operators of the chosen field */
  fieldChanged() {
    const f = this.o.fields.find((x) => x.id === this.field.value) || this.o.fields[0];
    while (this.op.firstChild) { this.op.removeChild(this.op.firstChild); }
    BY_TYPE[f.type || 'text'].forEach((k) => this.op.appendChild(this.el('option', { text: QUERY_OPS[k], attrs: { value: k } })));
    this.val.setAttribute('inputmode', f.type === 'number' ? 'decimal' : 'text');
  }

  /** @private Enter adds; Backspace in an empty box removes the last chip */
  valueKey(e) {
    if (e.key === 'Enter') { e.preventDefault(); this.addFromRow(); }
    else if (e.key === 'Backspace' && !this.val.value && this.conds.length) { this.remove(this.conds.length - 1); }
  }

  /** @private */
  addFromRow() {
    const text = this.field.value + ' ' + QUERY_OPS[this.op.value] + ' ' + (this.op.value === 'in' ? '(' + this.val.value + ')' : (/\s/.test(this.val.value) ? '"' + this.val.value + '"' : this.val.value));
    try {
      if (!this.val.value.trim()) { throw new Error('Give a value'); }
      const c = parseQuery(text, this.o.fields)[0];
      this.err.textContent = '';
      this.conds.push(c);
      this.val.value = '';
      this.changed();
      this.val.focus();
    } catch (e) { this.err.textContent = e.message; }
  }

  /**
   * Takes condition i out.
   * @param {number} i
   * @returns {void}
   */
  remove(i) { const c = this.conds.splice(i, 1)[0]; if (c) { announce('Removed ' + queryText([c]), { document: this.doc }); this.changed(); } }

  /** @private */
  changed() { this.draw(); this.emit('change', { value: this.getValue(), text: this.getText() }); }

  /** @private draws the chips */
  draw() {
    while (this.chips.firstChild) { this.chips.removeChild(this.chips.firstChild); }
    this.conds.forEach((c, i) => this.chips.appendChild(this.el('li', { cls: 'di-query-chip' }, [this.el('span', { text: queryText([c]) }),
      this.el('button', { cls: 'di-query-x', text: '×', attrs: { type: 'button', 'aria-label': 'Remove ' + queryText([c]) }, on: { click: () => this.remove(i) } })])));
  }

  /**
   * The conditions.
   * @returns {QueryCondition[]}
   */
  getValue() { return this.conds.map((c) => Object.assign({}, c)); }

  /**
   * The query as text.
   * @returns {string}
   */
  getText() { return queryText(this.conds); }

  /**
   * Sets the conditions (a list, or a query text), without an event.
   * @param {(QueryCondition[]|string)} v
   * @returns {void}
   */
  setValue(v) { this.conds = (typeof v === 'string' ? parseQuery(v, this.o.fields) : v).map((c) => Object.assign({}, c)); this.draw(); }
}
