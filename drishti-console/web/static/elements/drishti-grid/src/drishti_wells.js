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
/* The wells: where a person drops fields to build a pivot (the ribbon above the grid, or the side tool panel: the same model). Four
   wells: Rows, Columns, Values (each value with its aggregation) and Filters (each with its member filter). What may go where comes
   from the columns (enableRowGroup, enablePivot, enableValue); a drop that cannot be made says why. The wells make the pivot view. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { aggFunc } from './drishti_aggregate.js';

/** The wells, in the ribbon's order. */
export const WELLS = Object.freeze(['rows', 'columns', 'values', 'filters']);
/** The aggregations a value can take in the browser (a non-additive column's value is the server's measure). */
export const VALUE_FUNCS = Object.freeze(['sum', 'count', 'avg', 'min', 'max', 'count_distinct', 'first', 'last']);

/**
 * @typedef {Object} WellValue
 * @property {string} colId
 * @property {string} aggFunc
 * @property {string} [as] its label
 * @property {string} [showAs] how the server shows it (pct-grand, rank, diff:1:MONTH)
 */

/**
 * The wells of a pivot.
 */
export class WellsModel {
  /**
   * @param {import('./drishti_columns.js').ColumnModel} columns
   * @param {{rows: string[], columns: string[], values: WellValue[], filters: Array<{colId: string, filter: Object}>}} [initial]
   */
  constructor(columns, initial) {
    this.columns = columns;
    /** @type {EventBus} changed {well} */
    this.events = new EventBus();
    this.state = { rows: [], columns: [], values: [], filters: [] };
    /** @type {Array<{name: string, label: string}>} aggregations only a server computes (median, var_samp), offered beside VALUE_FUNCS */
    this.serverFuncs = [];
    /** @type {?string[]} the browser's aggregations offered (null: all of VALUE_FUNCS) */
    this.allowedFuncs = null;
    if (initial) { this.setState(initial); }
  }

  /**
   * The aggregations a value may take: the browser's (VALUE_FUNCS), then the server's own (GridOptions.serverFuncs).
   * @returns {Array<{name: string, label: string, server: boolean}>}
   */
  funcs() {
    const out = VALUE_FUNCS.filter((f) => !this.allowedFuncs || this.allowedFuncs.indexOf(f) >= 0).map((f) => ({ name: f, label: aggFunc(f).label, server: false }));
    this.serverFuncs.forEach((f) => { if (!out.some((x) => x.name === f.name)) { out.push({ name: f.name, label: f.label || f.name, server: true }); } });
    return out;
  }

  /**
   * An aggregation's label (Sum, Median), or its name when it is not known.
   * @param {string} name
   * @returns {string}
   */
  funcLabel(name) {
    const f = this.funcs().find((x) => x.name === name);
    return f ? f.label : name;
  }

  /**
   * The field list, searched (ColumnModel.fields), each field with the wells it is in.
   * @param {string} [search]
   * @returns {Array<Object>}
   */
  fieldList(search) {
    return this.columns.fields(search).map((f) => Object.assign(f, { in: WELLS.filter((w) => this.has(w, f.id)) }));
  }

  /**
   * Whether a field is in a well.
   * @param {string} well
   * @param {string} colId
   * @returns {boolean}
   */
  has(well, colId) {
    const l = this.state[well];
    return well === 'rows' || well === 'columns' ? l.indexOf(colId) >= 0 : l.some((x) => x.colId === colId);
  }

  /**
   * Whether a field may be dropped in a well, and if not, why (in words).
   * @param {string} colId
   * @param {string} well
   * @returns {{ok: boolean, why: ?string}}
   */
  canDrop(colId, well) {
    const c = this.columns.get(colId);
    if (!c) { return { ok: false, why: 'there is no field ' + colId }; }
    if (WELLS.indexOf(well) < 0) { return { ok: false, why: 'there is no well ' + well }; }
    if (well === 'rows' && !c.enableRowGroup) { return { ok: false, why: c.header + ' cannot group rows (a figure goes in Values)' }; }
    if (well === 'columns' && !c.enablePivot) { return { ok: false, why: c.header + ' cannot make columns (a figure goes in Values)' }; }
    if (well === 'values' && !c.enableValue && c.type !== 'number' && !c.enableRowGroup) { return { ok: false, why: c.header + ' is not a figure' }; }
    if ((well === 'rows' && this.has('columns', colId)) || (well === 'columns' && this.has('rows', colId))) {
      return { ok: true, why: c.header + ' moves from ' + (well === 'rows' ? 'Columns' : 'Rows') };
    }
    return { ok: true, why: null };
  }

  /**
   * Drops a field in a well at a place (moving it from Rows to Columns or back; a field may be a value and a filter as well).
   * @param {string} colId
   * @param {string} well
   * @param {number} [index] the place (default: the end)
   * @returns {void}
   */
  drop(colId, well, index) {
    const ok = this.canDrop(colId, well);
    if (!ok.ok) { throw new Error(ok.why); }
    const c = this.columns.get(colId);
    if (well === 'rows' || well === 'columns') {
      this.state.rows = this.state.rows.filter((x) => x !== colId);
      this.state.columns = this.state.columns.filter((x) => x !== colId);
      const l = this.state[well];
      l.splice(index === undefined ? l.length : Math.max(0, Math.min(l.length, index)), 0, colId);
    } else if (well === 'values') {
      const l = this.state.values;
      const v = { colId: colId, aggFunc: c.aggFunc || (c.type === 'number' ? 'sum' : 'count') };
      l.splice(index === undefined ? l.length : index, 0, v);
    } else {
      if (this.has('filters', colId)) { return; }
      this.state.filters.splice(index === undefined ? this.state.filters.length : index, 0, { colId: colId, filter: null });
    }
    this.events.emit('changed', { well: well });
  }

  /**
   * Takes a field out of a well (a value by its place, since a field may be a value twice).
   * @param {string} well
   * @param {(string|number)} which the field, or a value's index
   * @returns {void}
   */
  remove(well, which) {
    if (well === 'values' && typeof which === 'number') { this.state.values.splice(which, 1); }
    else if (well === 'rows' || well === 'columns') { this.state[well] = this.state[well].filter((x) => x !== which); }
    else { this.state[well] = this.state[well].filter((x) => x.colId !== which); }
    this.events.emit('changed', { well: well });
  }

  /**
   * Moves an item within its well.
   * @param {string} well
   * @param {number} from
   * @param {number} to
   * @returns {void}
   */
  reorder(well, from, to) {
    const l = this.state[well];
    const [x] = l.splice(from, 1);
    l.splice(Math.max(0, Math.min(l.length, to)), 0, x);
    this.events.emit('changed', { well: well });
  }

  /**
   * Sets a value's aggregation. A non-additive field (VaR, ES) keeps its server measure whatever is asked, and says so.
   * @param {number} index the value's place
   * @param {string} func
   * @returns {{ok: boolean, why: ?string}}
   */
  setAggregation(index, func) {
    const v = this.state.values[index];
    if (!v) { throw new Error('no value ' + index); }
    const c = this.columns.get(v.colId);
    if (c && c.additive === false) { return { ok: false, why: c.header + ' is not additive: every total is computed by the server from its members, never a ' + func + ' made in the browser' }; }
    if (!this.funcs().some((f) => f.name === func)) { throw new Error('no aggregation ' + func + ' (' + this.funcs().map((f) => f.name).join(', ') + ')'); }
    if (v.as && v.as === this.measureOf(v)) { delete v.as; }   // a label that was only the measure's text follows the new function
    v.aggFunc = func;
    this.events.emit('changed', { well: 'values' });
    return { ok: true, why: null };
  }

  /**
   * Sets a filter field's member filter ({type: 'set', values} or {type: 'set', excluded}).
   * @param {string} colId
   * @param {?Object} filter
   * @returns {void}
   */
  setFilter(colId, filter) {
    let f = this.state.filters.find((x) => x.colId === colId);
    if (!f) { f = { colId: colId, filter: null }; this.state.filters.push(f); }
    f.filter = filter;
    this.events.emit('changed', { well: 'filters' });
  }

  /**
   * The wells' state, to keep and give back.
   * @returns {{rows: string[], columns: string[], values: WellValue[], filters: Array<{colId: string, filter: Object}>}}
   */
  getState() { return JSON.parse(JSON.stringify(this.state)); }

  /**
   * Gives back a kept state.
   * @param {{rows: string[], columns: string[], values: WellValue[], filters: Array}} s
   * @returns {void}
   */
  setState(s) {
    this.state = { rows: (s.rows || []).slice(), columns: (s.columns || []).slice(), values: (s.values || []).map((x) => Object.assign({}, x)),
      filters: (s.filters || []).map((x) => Object.assign({}, x)) };
    this.events.emit('changed', { well: '*' });
  }

  /**
   * A value's measure text: the column's own server measure when it has one or is not additive, else func(field).
   * @param {WellValue} v
   * @returns {string}
   */
  measureOf(v) {
    const c = this.columns.get(v.colId);
    if (c && c.measure) { return c.measure; }
    if (v.aggFunc === 'count' && !c) { return 'count()'; }
    return (v.aggFunc || 'sum') + '(' + (c ? c.field : v.colId) + ')';
  }

  /**
   * The pivot view the wells make (with the member filters of the Filters well and of any filter given).
   * @param {Object} [extra] more of the view (expanded, sort, top, subtotals, valueFilters, source)
   * @returns {import('./drishti_pivot.js').PivotView}
   */
  toView(extra) {
    const filters = {};
    this.state.filters.forEach((f) => {
      if (!f.filter) { return; }
      const c = this.columns.get(f.colId), field = c ? c.field : f.colId;
      if (f.filter.values) { filters[field] = { in: f.filter.values.map(String), notIn: [] }; }
      else if (f.filter.excluded) { filters[field] = { in: [], notIn: f.filter.excluded.map(String) }; }
    });
    const field = (id) => { const c = this.columns.get(id); return c ? c.field : id; };
    return Object.assign({
      rows: this.state.rows.map(field), columns: this.state.columns.map(field),
      values: this.state.values.map((v) => {
        const c = this.columns.get(v.colId);
        const label = v.as || (c && c.measure ? c.header : (c ? c.header : v.colId) + (v.aggFunc && v.aggFunc !== 'sum' ? ' (' + (aggFunc(v.aggFunc) ? aggFunc(v.aggFunc).label.toLowerCase() : v.aggFunc) + ')' : ''));
        return v.showAs ? { measure: this.measureOf(v), as: label, showAs: v.showAs } : { measure: this.measureOf(v), as: label };
      }),
      filters: filters
    }, extra || {});
  }
}
