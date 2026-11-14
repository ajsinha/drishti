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
/* The wells: where a person puts fields to build a pivot. One model drawn three ways (BI_GUIDE.md section 2): the compact line above the
   grid (the trader view, B, with the View picker in front, C), the panes beside it (Fields and Build, A) and the terminal's lane. Four
   wells: Rows, Columns, Values (each value with its function, the function's arguments, a custom formula or a show-as) and Filters (each
   with its member filter); any number of fields in each. What may go where comes from the columns (enableRowGroup, enablePivot,
   enableValue) and the functions valid for a field from the column (funcs, from the server's catalogue); a drop or a choice that cannot be
   made says why. A hierarchy (MGMT: Division › Desk › Book) is one item: its levels sit side by side in Rows or Columns and a stepper adds
   or takes away its next level. The wells make the pivot view. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { aggFunc } from './drishti_aggregate.js';

/** The wells, in the ribbon's order. */
export const WELLS = Object.freeze(['rows', 'columns', 'values', 'filters']);
/** The aggregations a value can take in the browser (a non-additive column's value is the server's measure). */
export const VALUE_FUNCS = Object.freeze(['sum', 'count', 'avg', 'min', 'max', 'count_distinct', 'first', 'last']);
/** The functions in plain words, as the value pickers say them (BI_GUIDE.md section 4); a name not here reads as itself. */
export const FUNC_LABELS = Object.freeze({ sum: 'Sum', count: 'Count', count_distinct: 'Distinct count', distinct: 'Distinct count', avg: 'Average',
  wavg: 'Weighted average…', min: 'Min', max: 'Max', median: 'Median', percentile: 'Percentile…', var_samp: 'Variance', var_pop: 'Variance (population)',
  stddev_samp: 'Std dev', stddev_pop: 'Std dev (population)', first: 'First', last: 'Last', custom: 'Custom ƒ…' });
/** The functions that take an argument after the field, and what it is: wavg a weight field, percentile a quantile. */
export const FUNC_ARGS = Object.freeze({ wavg: { kind: 'field', label: 'Weight' }, percentile: { kind: 'number', label: 'Quantile (0 to 1)', default: 0.95 } });

/**
 * A function's label in plain words.
 * @param {string} name
 * @returns {string}
 */
export function funcLabel(name) {
  if (FUNC_LABELS[name]) { return FUNC_LABELS[name]; }
  const f = aggFunc(name);
  return f ? f.label : name;
}

/**
 * @typedef {Object} WellValue
 * @property {string} colId
 * @property {string} aggFunc its function (custom: a formula)
 * @property {Array<(string|number)>} [args] the function's arguments after the field (wavg: the weight field's id; percentile: q)
 * @property {string} [formula] a custom formula (Rachana, or a custom function's call), the measure's text as written
 * @property {string} [as] its label
 * @property {string} [showAs] how the server shows it (pct-grand, rank, diff:1:MONTH)
 */

/**
 * @typedef {Object} Hierarchy
 * @property {string} id MGMT
 * @property {string} title Management
 * @property {Array<{id: string, label: string}>} levels its level fields, top first (MGMT.DIVISION, MGMT.DESK, MGMT.BOOK)
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
    /** @type {Hierarchy[]} the source's hierarchies, each one item of the pickers */
    this.hierarchies = [];
    if (initial) { this.setState(initial); }
  }

  /**
   * The source's hierarchies (the server's catalogue: {id, title, levels: [{field, label}]}); levels the grid has no column for are left out.
   * @param {Array<Object>} list
   * @returns {void}
   */
  setHierarchies(list) {
    this.hierarchies = (list || []).map((x) => ({ id: String(x.id), title: x.title || String(x.id),
      levels: (x.levels || []).map((l) => (typeof l === 'string' ? { id: l, label: l } : { id: l.id || l.field, label: l.label || l.id || l.field }))
        .filter((l) => this.columns.get(l.id)) })).filter((x) => x.levels.length);
  }

  /**
   * The hierarchy a field is a level of, or null.
   * @param {string} colId
   * @returns {?Hierarchy}
   */
  hierarchyOf(colId) { return this.hierarchies.find((x) => x.levels.some((l) => l.id === colId)) || null; }

  /**
   * Rows or Columns as the line draws them: consecutive levels of one hierarchy as one item, each other field as its own.
   * @param {('rows'|'columns')} well
   * @returns {Array<{hierarchy: ?Hierarchy, ids: string[], index: number}>} index: the first field's place in the well
   */
  segments(well) {
    const out = [];
    this.state[well].forEach((id, i) => {
      const hh = this.hierarchyOf(id), last = out[out.length - 1];
      if (hh && last && last.hierarchy === hh) { last.ids.push(id); } else { out.push({ hierarchy: hh, ids: [id], index: i }); }
    });
    return out;
  }

  /**
   * The level stepper of a hierarchy in a well: down (+1) adds its next level after its deepest one there (the first level when it is not
   * there yet), up (−1) takes its deepest level away.
   * @param {('rows'|'columns')} well
   * @param {string} id the hierarchy
   * @param {number} dir +1 or −1
   * @returns {{ok: boolean, why: ?string}}
   */
  stepHierarchy(well, id, dir) {
    const hh = this.hierarchies.find((x) => x.id === id);
    if (!hh) { return { ok: false, why: 'there is no hierarchy ' + id }; }
    const l = this.state[well], at = hh.levels.map((x) => l.indexOf(x.id));
    const deepest = at.reduce((m, p, k) => (p >= 0 ? k : m), -1);
    if (dir < 0) {
      if (deepest < 0) { return { ok: false, why: hh.title + ' is not in ' + well }; }
      if (at.filter((p) => p >= 0).length === 1) { return { ok: false, why: hh.title + ' is at its top level: remove it with ×' }; }
      l.splice(at[deepest], 1);
    } else {
      const next = deepest + 1;
      if (next >= hh.levels.length) { return { ok: false, why: hh.title + ' has no level below ' + hh.levels[deepest].label }; }
      const nid = hh.levels[next].id;
      this.state.rows = this.state.rows.filter((x) => x !== nid);
      this.state.columns = this.state.columns.filter((x) => x !== nid);
      const list = this.state[well], after = deepest >= 0 ? list.indexOf(hh.levels[deepest].id) : list.length - 1;
      list.splice(after + 1, 0, nid);
    }
    this.events.emit('changed', { well: well });
    return { ok: true, why: null };
  }

  /**
   * Puts exactly these levels of a hierarchy in a well (in the hierarchy's order), where its first level was (else at the end).
   * @param {('rows'|'columns')} well
   * @param {string} id the hierarchy
   * @param {string[]} levels level field ids
   * @returns {void}
   */
  setHierarchyLevels(well, id, levels) {
    const hh = this.hierarchies.find((x) => x.id === id);
    if (!hh) { throw new Error('there is no hierarchy ' + id); }
    const all = hh.levels.map((x) => x.id), keep = all.filter((x) => levels.indexOf(x) >= 0);
    const l = this.state[well];
    let at = l.findIndex((x) => all.indexOf(x) >= 0);
    if (at < 0) { at = l.length; }
    const other = well === 'rows' ? 'columns' : 'rows';
    this.state[other] = this.state[other].filter((x) => all.indexOf(x) < 0);
    const rest = l.filter((x, i) => all.indexOf(x) < 0 && i < at), tail = l.filter((x, i) => all.indexOf(x) < 0 && i >= at);
    this.state[well] = rest.concat(keep, tail);
    this.events.emit('changed', { well: well });
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
   * The functions a value of a field may take, in plain words: the column's own list (funcs, the server's catalogue says which are valid
   * for the field) or else the grid's (funcs()) by its type; a function that would mislead is shown off with the reason (a rate is never
   * summed, a text is counted). A measure computed per cell (VaR, ES: a column with a server measure that is not additive) takes none.
   * @param {string} colId
   * @returns {Array<{name: string, label: string, server: boolean, disabled: boolean, why: ?string, arg: ?Object}>}
   */
  funcsFor(colId) {
    const c = this.columns.get(colId);
    if (!c || (c.measure && c.additive === false) || c.perCell) { return []; }
    const grid = this.funcs(), known = (n) => grid.find((f) => f.name === n);
    const names = Array.isArray(c.funcs) ? c.funcs.slice() : grid.map((f) => f.name).filter((n) => c.type === 'number' || ['count', 'count_distinct', 'first', 'last', 'min', 'max'].indexOf(n) >= 0);
    const out = names.map((n) => ({ name: n, label: funcLabel(n), server: known(n) ? known(n).server : true, disabled: false, why: null, arg: FUNC_ARGS[n] || null }));
    const rate = c.unit === 'rate' || c.unit === 'pct';
    if (names.indexOf('sum') < 0 && (rate || c.type !== 'number')) {
      out.unshift({ name: 'sum', label: 'Sum', server: false, disabled: true, arg: null,
        why: rate ? c.header + ' is a ' + (c.unit === 'pct' ? 'percentage' : 'rate') + ': it is never summed (an average or a weighted average is)' : c.header + ' is not a number: count it, or count its distinct values' });
    }
    return out;
  }

  /**
   * An aggregation's label (Sum, Median), or its name when it is not known.
   * @param {string} name
   * @returns {string}
   */
  funcLabel(name) { return funcLabel(name); }

  /**
   * The field list, searched (ColumnModel.fields), each field with the wells it is in and its hierarchy.
   * @param {string} [search]
   * @returns {Array<Object>}
   */
  fieldList(search) {
    return this.columns.fields(search).map((f) => Object.assign(f, { in: WELLS.filter((w) => this.has(w, f.id)), hierarchy: this.hierarchyOf(f.id) }));
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
    if (well === 'values' && !c.measure && Array.isArray(c.funcs) && !c.funcs.length) { return { ok: false, why: c.header + ' has no function here: the source\'s values are its own measures' }; }
    if ((well === 'rows' && this.has('columns', colId)) || (well === 'columns' && this.has('rows', colId))) {
      return { ok: true, why: c.header + ' moves from ' + (well === 'rows' ? 'Columns' : 'Rows') };
    }
    return { ok: true, why: null };
  }

  /**
   * Drops a field in a well at a place (moving it from Rows to Columns or back; a field may be a value and a filter as well, and a value
   * several times with different functions).
   * @param {string} colId
   * @param {string} well
   * @param {number} [index] the place (default: the end)
   * @param {{aggFunc: string, args: Array}} [value] a value's function and arguments (default: the column's)
   * @returns {void}
   */
  drop(colId, well, index, value) {
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
      const fs = this.funcsFor(colId).filter((f) => !f.disabled), first = fs.length ? fs[0].name : null;
      const dflt = c.aggFunc && (!Array.isArray(c.funcs) || c.funcs.indexOf(c.aggFunc) >= 0) ? c.aggFunc : first || (c.type === 'number' ? 'sum' : 'count');
      const v = { colId: colId, aggFunc: value && value.aggFunc ? value.aggFunc : dflt };
      if (value && value.args) { v.args = value.args.slice(); }
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
   * Sets a value's function (and its arguments: wavg's weight field, percentile's q). A value computed per cell by the server (VaR, ES)
   * keeps its measure whatever is asked, and says so; a function that is not valid for the field is refused with the reason.
   * @param {number} index the value's place
   * @param {string} func
   * @param {Array<(string|number)>} [args]
   * @returns {{ok: boolean, why: ?string}}
   */
  setAggregation(index, func, args) {
    const v = this.state.values[index];
    if (!v) { throw new Error('no value ' + index); }
    const c = this.columns.get(v.colId);
    if (c && (c.additive === false && c.measure || c.perCell)) { return { ok: false, why: c.header + ' is computed per cell by the server from the cell\'s own members, never a ' + func + ' made of other figures' }; }
    const fs = this.funcsFor(v.colId), f = fs.find((x) => x.name === func);
    if (!f && !this.funcs().some((x) => x.name === func)) { throw new Error('no aggregation ' + func + ' (' + fs.map((x) => x.name).join(', ') + ')'); }
    if (f && f.disabled) { return { ok: false, why: f.why }; }
    if (FUNC_ARGS[func] && FUNC_ARGS[func].kind === 'field' && (!args || !this.columns.get(String(args[0])))) { return { ok: false, why: funcLabel(func) + ' needs a ' + FUNC_ARGS[func].label.toLowerCase() + ' field' }; }
    if (FUNC_ARGS[func] && FUNC_ARGS[func].kind === 'number') {
      const q = args && args.length ? +args[0] : FUNC_ARGS[func].default;
      if (!(q >= 0 && q <= 1)) { return { ok: false, why: funcLabel(func) + ' needs a quantile between 0 and 1 (0.95)' }; }
      args = [q];
    }
    if (v.as && (v.as === this.measureOf(v) || v.as === this.labelOf(v))) { delete v.as; }   // a label that was only the measure's text follows the new function
    v.aggFunc = func;
    delete v.formula;
    if (args && args.length) { v.args = args.slice(); } else { delete v.args; }
    this.events.emit('changed', { well: 'values' });
    return { ok: true, why: null };
  }

  /**
   * Makes a value a custom formula (Rachana, or a custom function's call: wret(RET, W)); the server checks it when it answers.
   * @param {number} index
   * @param {string} formula
   * @param {string} [label]
   * @returns {{ok: boolean, why: ?string}}
   */
  setFormula(index, formula, label) {
    const v = this.state.values[index];
    if (!v) { throw new Error('no value ' + index); }
    const text = String(formula || '').trim();
    if (!text) { return { ok: false, why: 'write a formula: sum(PV) / sum(NOTIONAL)' }; }
    v.aggFunc = 'custom';
    v.formula = text;
    delete v.args;
    if (label) { v.as = label; } else if (!v.as) { v.as = text; }
    this.events.emit('changed', { well: 'values' });
    return { ok: true, why: null };
  }

  /**
   * Shows a value as (the server's show-as: pct-grand, pct-row, pct-column, pct-parent-row, rank, ntile:4, diff:1:<dim>, pct-change:1:<dim>),
   * or as its figure (null).
   * @param {number} index
   * @param {?string} showAs
   * @returns {void}
   */
  setShowAs(index, showAs) {
    const v = this.state.values[index];
    if (!v) { throw new Error('no value ' + index); }
    if (showAs) { v.showAs = showAs; } else { delete v.showAs; }
    this.events.emit('changed', { well: 'values' });
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
   * A value's measure text: the column's own server measure when it has one, a custom formula as written, else func(field[, args]).
   * @param {WellValue} v
   * @returns {string}
   */
  measureOf(v) {
    const c = this.columns.get(v.colId);
    if (c && c.measure) { return c.measure; }
    if (v.formula) { return v.formula; }
    if (v.aggFunc === 'count' && !c) { return 'count()'; }
    const args = (v.args || []).map((a) => { const ac = typeof a === 'string' ? this.columns.get(a) : null; return ac ? ac.field : String(a); });
    return (v.aggFunc || 'sum') + '(' + [c ? c.field : v.colId].concat(args).join(', ') + ')';
  }

  /**
   * A value's label when it has none of its own: the field's header, with its function in words unless a sum (PV, PV (median),
   * PV (weighted average by NOTIONAL), PV (percentile 0.95)); a custom formula its text; a server measure its header.
   * @param {WellValue} v
   * @returns {string}
   */
  labelOf(v) {
    const c = this.columns.get(v.colId);
    if (c && c.measure) { return c.header; }
    if (v.formula) { return v.formula; }
    const base = c ? c.header : v.colId;
    if (!v.aggFunc || v.aggFunc === 'sum') { return base; }
    const fn = aggFunc(v.aggFunc) ? aggFunc(v.aggFunc).label.toLowerCase() : FUNC_LABELS[v.aggFunc] ? FUNC_LABELS[v.aggFunc].replace('…', '').toLowerCase() : v.aggFunc;
    const a = v.args && v.args.length ? (FUNC_ARGS[v.aggFunc] && FUNC_ARGS[v.aggFunc].kind === 'field' ? ' by ' + this.headerOf(String(v.args[0])) : ' ' + v.args.join(', ')) : '';
    return base + ' (' + fn + a + ')';
  }

  /** @private a field's header */
  headerOf(id) { const c = this.columns.get(id); return c ? c.header : id; }

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
        const label = v.as || this.labelOf(v);
        return v.showAs ? { measure: this.measureOf(v), as: label, showAs: v.showAs } : { measure: this.measureOf(v), as: label };
      }),
      filters: filters
    }, extra || {});
  }
}
