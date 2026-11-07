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
/* Columns: what each column is (its field, type, format, width, pinning, what it may be used for in a pivot, whether its figures add
   up), their order, and the field list a person searches to build a view. Values are read the same way everywhere (getValue) and
   shown the same way (formatValue: the server's named formats through drishti-core). */
import { formatNamed, hasFormat, formatNumber, formatDate, EventBus } from '../../drishti-core/src/drishti_index.js';

/** Aggregation functions the browser computes from members (never from other totals). */
export const ADDITIVE_FUNCS = Object.freeze(['sum', 'count']);

/**
 * @typedef {Object} ColumnDef
 * @property {string} [id] the column's id (default: field)
 * @property {string} [field] the row's property
 * @property {string} [header] the heading (default: the field)
 * @property {('text'|'number'|'date'|'boolean')} [type='text']
 * @property {(string|Object|function(*, Object): string)} [format] a named format (amount0, pct2, compact, dmy), number options, or a function
 * @property {function(Object): *} [valueGetter] the value from the row (default: row[field])
 * @property {function(*, *): number} [comparator] the order of two values
 * @property {number} [width=120]
 * @property {number} [minWidth=40]
 * @property {number} [maxWidth]
 * @property {('left'|'right'|null)} [pinned]
 * @property {boolean} [hidden=false]
 * @property {boolean} [sortable=true]
 * @property {boolean} [filterable=true]
 * @property {boolean} [resizable=true]
 * @property {boolean} [editable=false] a person may change its values (setCellValue)
 * @property {string} [aggFunc] the aggregation of a value column (sum, count, min, max, avg, first, last, distinct, or a server measure)
 * @property {boolean} [additive] whether its figures may be summed: false for VaR, ES, ratios (a total is asked of the server);
 *   default: true for numbers
 * @property {string} [aggregate] 'server': the same as additive false (ELEMENTS.md's name)
 * @property {string} [measure] the server's measure text when it is not func(field) (var(99, 1d))
 * @property {boolean} [enableRowGroup] may be a row group (default: text and date columns)
 * @property {boolean} [enablePivot] may be a column pivot (default: text and date columns)
 * @property {boolean} [enableValue] may be a value (default: numbers)
 * @property {string} [group] a heading above several columns
 * @property {string} [description] words for the field list
 * @property {function(*, Object): ?string} [cellClass] more classes for a leaf's cell from its value and row (a tone: dg-tone-bad,
 *   dg-tone-good, dg-tone-warn, dg-tone-muted; a host's own class)
 * @property {function({value: *, text: string, row: Object, doc: Document}): (Node|string|null)} [cellRenderer] a leaf's cell content
 *   drawn by the host (a link, a button); null keeps the formatted text. Copy, export and sort still use the value and its text.
 */

/**
 * A column definition with every default filled in.
 * @param {ColumnDef} def
 * @param {number} [index]
 * @returns {Object}
 */
export function normalizeColumn(def, index) {
  const id = def.id || def.field;
  if (!id) { throw new Error('column ' + (index || 0) + ': give it an id or a field'); }
  const type = def.type || 'text';
  const num = type === 'number';
  return Object.assign({
    id: id, field: def.field || id, header: def.header || def.field || id, type: type, width: 120, minWidth: 40, maxWidth: null,
    pinned: null, hidden: false, sortable: true, filterable: true, resizable: true, editable: false, aggFunc: num ? 'sum' : null,
    additive: num, enableRowGroup: !num, enablePivot: !num, enableValue: num, group: null, description: '', format: null,
    valueGetter: null, comparator: null, measure: null, cellClass: null, cellRenderer: null
  }, def, { id: id, type: type }, def.aggregate === 'server' ? { additive: false } : {});
}

/**
 * A column's value in a row.
 * @param {Object} row
 * @param {Object} col a normalized column
 * @returns {*}
 */
export function getValue(row, col) {
  if (!row) { return undefined; }
  return col.valueGetter ? col.valueGetter(row) : row[col.field];
}

/**
 * A value as the column shows it.
 * @param {*} v
 * @param {Object} col a normalized column
 * @param {Object} [row]
 * @returns {string}
 */
export function formatValue(v, col, row) {
  const f = col.format;
  if (typeof f === 'function') { return f(v, row); }
  if (typeof f === 'string' && hasFormat(f)) { return formatNamed(f, v); }
  if (v === null || v === undefined || v === '') { return ''; }
  if (f && typeof f === 'object') { return formatNumber(v, f); }
  if (col.type === 'number' && typeof v === 'number') { return formatNumber(v, { decimals: 0, maxDecimals: Number.isInteger(v) ? 0 : 2 }); }
  if (col.type === 'date') { return formatDate(v, typeof f === 'string' ? f : 'yyyy-MM-dd'); }
  return String(v);
}

/**
 * The columns of a grid: their order, widths, pinning and visibility, and the field list.
 */
export class ColumnModel {
  /**
   * @param {ColumnDef[]} defs
   */
  constructor(defs) {
    /** @type {EventBus} column events: changed {what, id} */
    this.events = new EventBus();
    this.setColumns(defs || []);
  }

  /**
   * Replaces every column.
   * @param {ColumnDef[]} defs
   * @returns {void}
   */
  setColumns(defs) {
    const seen = {};
    /** @private */ this.cols = defs.map((d, i) => {
      const c = normalizeColumn(d, i);
      if (seen[c.id]) { throw new Error('two columns are called ' + c.id); }
      seen[c.id] = true;
      return c;
    });
    this.changed('columns');
  }

  /** @private */
  changed(what, id) { this._layout = null; this.events.emit('changed', { what: what, id: id }); }

  /**
   * Every column, in order (hidden ones too).
   * @returns {Object[]}
   */
  all() { return this.cols.slice(); }

  /**
   * A column by id.
   * @param {string} id
   * @returns {?Object}
   */
  get(id) { return this.cols.find((c) => c.id === id) || null; }

  /** @private */
  need(id) { const c = this.get(id); if (!c) { throw new Error('no column ' + id); } return c; }

  /**
   * The shown columns in display order: pinned left, the middle, pinned right.
   * @returns {Object[]}
   */
  visible() {
    const v = this.cols.filter((c) => !c.hidden);
    return v.filter((c) => c.pinned === 'left').concat(v.filter((c) => !c.pinned), v.filter((c) => c.pinned === 'right'));
  }

  /**
   * Where each part of the shown columns sits: the pinned ones and the middle, each column's left edge within its part, and widths.
   * @returns {{left: Object[], center: Object[], right: Object[], centerOffsets: Float64Array, centerWidth: number, leftWidth: number, rightWidth: number}}
   */
  layout() {
    if (this._layout) { return this._layout; }
    const v = this.visible();
    const left = v.filter((c) => c.pinned === 'left'), right = v.filter((c) => c.pinned === 'right'), center = v.filter((c) => !c.pinned);
    const offs = new Float64Array(center.length + 1);
    center.forEach((c, i) => { offs[i + 1] = offs[i] + c.width; });
    const sum = (l) => l.reduce((a, c) => a + c.width, 0);
    this._layout = { left: left, center: center, right: right, centerOffsets: offs, centerWidth: offs[center.length], leftWidth: sum(left), rightWidth: sum(right) };
    return this._layout;
  }

  /**
   * Sets a column's width, within its least and most.
   * @param {string} id
   * @param {number} width px
   * @returns {number} the width set
   */
  setWidth(id, width) {
    const c = this.need(id);
    c.width = Math.max(c.minWidth, c.maxWidth ? Math.min(c.maxWidth, width) : width);
    this.changed('width', id);
    return c.width;
  }

  /**
   * Shows or hides a column.
   * @param {string} id
   * @param {boolean} shown
   * @returns {void}
   */
  setVisible(id, shown) { this.need(id).hidden = !shown; this.changed('visible', id); }

  /**
   * Pins a column left or right, or unpins it (null).
   * @param {string} id
   * @param {('left'|'right'|null)} side
   * @returns {void}
   */
  pin(id, side) {
    if (side !== null && side !== 'left' && side !== 'right') { throw new Error('pin: left, right or null'); }
    this.need(id).pinned = side;
    this.changed('pinned', id);
  }

  /**
   * Moves a column to a place in the order of all columns.
   * @param {string} id
   * @param {number} to the new index
   * @returns {void}
   */
  move(id, to) {
    const i = this.cols.indexOf(this.need(id));
    const [c] = this.cols.splice(i, 1);
    this.cols.splice(Math.max(0, Math.min(this.cols.length, to)), 0, c);
    this.changed('order', id);
  }

  /**
   * The field list a person picks from to build a view: every column with what it can be used for, filtered by words (all of them, in
   * the header, the id or the description; case and accents ignored), grouped columns kept together.
   * @param {string} [search]
   * @returns {Array<{id: string, header: string, type: string, group: ?string, rowGroup: boolean, pivot: boolean, value: boolean, additive: boolean, description: string}>}
   */
  fields(search) {
    const fold = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
    const words = fold(search).split(/\s+/).filter(Boolean);
    return this.cols.filter((c) => {
      const hay = fold(c.header + ' ' + c.id + ' ' + c.description + ' ' + (c.group || ''));
      return words.every((w) => hay.indexOf(w) >= 0);
    }).map((c) => ({ id: c.id, header: c.header, type: c.type, group: c.group, rowGroup: !!c.enableRowGroup, pivot: !!c.enablePivot,
      value: !!c.enableValue, additive: c.additive !== false, description: c.description }));
  }

  /**
   * The columns' state, to keep and give back (widths, order, pinning, visibility).
   * @returns {Array<{id: string, width: number, pinned: ?string, hidden: boolean}>}
   */
  getState() { return this.cols.map((c) => ({ id: c.id, width: c.width, pinned: c.pinned, hidden: c.hidden })); }

  /**
   * Gives back a kept state (columns it does not name keep theirs, after the named ones).
   * @param {Array<{id: string, width: number, pinned: ?string, hidden: boolean}>} state
   * @returns {void}
   */
  setState(state) {
    const by = {};
    state.forEach((s, i) => { by[s.id] = Object.assign({ i: i }, s); });
    this.cols.forEach((c) => { const s = by[c.id]; if (s) { if (s.width) { c.width = s.width; } c.pinned = s.pinned || null; c.hidden = !!s.hidden; } });
    this.cols.sort((a, b) => (by[a.id] ? by[a.id].i : 1e9) - (by[b.id] ? by[b.id].i : 1e9));
    this.changed('state');
  }
}
