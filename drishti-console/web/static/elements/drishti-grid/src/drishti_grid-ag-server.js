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
/* The AG Grid-style adapter's server side: an application's serverSideDatasource (getRows(params) with params.request, success and fail,
   as AG Grid's public documentation describes them) becomes drishti-grid's Datasource for its server-side row model, and, in pivot mode,
   its serverPivot: the pivot's view asked level by level of the same function (the open nodes only), the rows and pivotResultFields
   answered made into the pivot answer drishti-grid draws (row entries, result columns, cells). Every figure is the server's: nothing is
   summed in the browser, so a non-additive measure stays right at every total. */
import { SEP, PivotState } from './drishti_pivot.js';
import { parseMeasure } from './drishti_aggregate.js';
import { translateFilterModel } from './drishti_grid-ag-map.js';

/**
 * @typedef {Object} AgServerContext
 * @property {function(string): ?Object} column a drishti column by id
 * @property {function(string): ?string} aggOf the AG aggFunc name of a value column (what the application gave)
 * @property {Object} [api] the adapter's handle (params.api)
 * @property {*} [context] the application's context (params.context)
 * @property {string} [separator='_'] serverSidePivotResultFieldSeparator
 * @property {number} [levelLimit=100000] rows asked of one pivot level
 * @property {boolean} [grandRow=false] ask the grand total row
 * @property {boolean} [rowTotals=false] ask each row's total across the pivot columns
 * @property {boolean} [infinite=false] the infinite row model's datasource: getRows({startRow, endRow, sortModel, filterModel, successCallback, failCallback})
 */

/** @private an AG column object from a drishti column id */
function colObj(ctx, id, aggFunc) {
  const c = ctx.column(id);
  const o = { id: id, displayName: c ? c.header || c.field || id : id, field: c ? c.field || id : id };
  if (aggFunc !== undefined) { o.aggFunc = aggFunc; }
  return o;
}

/**
 * Calls an AG-style getRows once and answers a promise: success({rowData, rowCount, pivotResultFields}) or the older
 * successCallback(rows, lastRow) resolve it; fail() or failCallback() reject it. An abort rejects it at once.
 * @param {{getRows: function(Object): void}} ds
 * @param {Object} request the AG-shaped request
 * @param {AgServerContext} ctx
 * @param {AbortSignal} [signal]
 * @param {Object} [parentNode]
 * @returns {Promise<{rows: Object[], rowCount: ?number, pivotResultFields: ?string[]}>}
 */
export function callGetRows(ds, request, ctx, signal, parentNode) {
  return new Promise((resolve, reject) => {
    let done = false;
    const ok = (rows, count, fields) => { if (!done) { done = true; resolve({ rows: rows || [], rowCount: typeof count === 'number' && count >= 0 ? count : null, pivotResultFields: fields || null }); } };
    const no = (why) => { if (!done) { done = true; const e = new Error(why || 'the server-side datasource failed (params.fail)'); reject(e); } };
    if (signal) {
      if (signal.aborted) { const e = new Error('aborted'); e.aborted = true; reject(e); return; }
      signal.addEventListener('abort', () => { if (!done) { done = true; const e = new Error('aborted'); e.aborted = true; reject(e); } });
    }
    if (ctx.infinite) {
      try { ds.getRows(Object.assign({}, request, { context: ctx.context, successCallback: (rows, last) => ok(rows, last), failCallback: () => no() })); }
      catch (e) { no(e && e.message ? 'the datasource threw: ' + e.message : undefined); }
      return;
    }
    const params = {
      request: request, api: ctx.api || null, context: ctx.context, parentNode: parentNode || { level: -1, key: null, data: null, id: 'root' },
      success: (p) => ok(p && p.rowData, p && p.rowCount, p && p.pivotResultFields),
      fail: () => no(),
      successCallback: (rows, last) => ok(rows, last),
      failCallback: () => no()
    };
    try { ds.getRows(params); } catch (e) { no(e && e.message ? 'the server-side datasource threw: ' + e.message : undefined); }
  });
}

/**
 * The AG-shaped request of a drishti block request (columns as objects, the filter model in AG's shape).
 * @param {import('./drishti_server-rows.js').BlockRequest} req
 * @param {AgServerContext} ctx
 * @returns {Object}
 */
export function agRequest(req, ctx) {
  return {
    startRow: req.startRow, endRow: req.endRow,
    rowGroupCols: (req.rowGroupCols || []).map((id) => colObj(ctx, id)),
    valueCols: (req.valueCols || []).map((v) => colObj(ctx, v.colId, ctx.aggOf(v.colId) || v.aggFunc)),
    pivotCols: (req.pivotCols || []).map((id) => colObj(ctx, id)),
    pivotMode: !!req.pivotMode, groupKeys: (req.groupKeys || []).slice(),
    filterModel: translateFilterModel(req.filterModel || {}, false), sortModel: (req.sortModel || []).map((s) => ({ colId: s.colId, sort: s.sort }))
  };
}

/**
 * drishti-grid's Datasource over an application's serverSideDatasource (or an infinite model's datasource).
 * @param {{getRows: function(Object): void}} ds
 * @param {AgServerContext} ctx
 * @returns {import('./drishti_server-rows.js').Datasource & {calls: Object[]}}
 */
export function agDatasource(ds, ctx) {
  if (!ds || typeof ds.getRows !== 'function') { throw new Error('rowModelType serverSide needs serverSideDatasource with getRows(params)'); }
  const calls = [];
  return {
    calls: calls,
    getRows(req) {
      const request = agRequest(req, ctx);
      calls.push(request);
      const parent = req.groupKeys && req.groupKeys.length ? { level: req.groupKeys.length - 1, key: req.groupKeys[req.groupKeys.length - 1], id: req.groupKeys.join(SEP) } : null;
      return callGetRows(ds, request, ctx, req.signal, parent).then((a) => ({ rows: a.rows, rowCount: a.rowCount === null ? undefined : a.rowCount }));
    }
  };
}

/**
 * A pivot result field's keys: the field less its value column (and the separator before it), split by the separator.
 * @param {string} field e.g. "EMEA_EUR_MTM"
 * @param {string[]} valueIds the value columns' ids
 * @param {string} sep
 * @param {number} depth how many pivot columns
 * @returns {?{keys: string[], valueId: string}}
 */
export function parseResultField(field, valueIds, sep, depth) {
  const v = valueIds.slice().sort((a, b) => b.length - a.length).find((id) => field === id || field.slice(-(id.length + sep.length)) === sep + id);
  if (!v) { return null; }
  if (field === v) { return { keys: [], valueId: v }; }
  const rest = field.slice(0, field.length - v.length - sep.length);
  const parts = rest.split(sep);
  const keys = parts.length > depth && depth > 0 ? parts.slice(0, depth - 1).concat([parts.slice(depth - 1).join(sep)]) : parts;
  return { keys: keys, valueId: v };
}

/**
 * drishti-grid's serverPivot over an application's serverSideDatasource: the view's top level, each open row node's level and, when asked,
 * the grand total row and the rows' totals, each one getRows call in pivot mode; the answer in the pivot answer's shape.
 * @param {{getRows: function(Object): void}} ds
 * @param {AgServerContext} ctx
 * @returns {function({view: Object}, AbortSignal): Promise<Object>}
 */
export function agServerPivot(ds, ctx) {
  const sep = ctx.separator || '_';
  const limit = ctx.levelLimit || 100000;
  return function serverPivot(body, signal) {
    const view = body.view, state = new PivotState(view);
    const byField = (f) => ctx.column(f) || ctx.column(String(f));
    const R = view.rows.map((f) => (byField(f) ? byField(f).id : f)), C = view.columns.map((f) => (byField(f) ? byField(f).id : f));
    const vals = view.values.map((v) => {
      const p = parseMeasure(v.measure), c = p && p.field ? byField(p.field) : null;
      return { label: v.as || v.measure, id: c ? c.id : (p && p.field) || v.measure, agg: c ? ctx.aggOf(c.id) || (p && p.func) : p && p.func, server: !!(c && c.additive === false) };
    });
    const filterModel = {};
    Object.keys(view.filters || {}).forEach((f) => {
      const m = view.filters[f], id = byField(f) ? byField(f).id : f;
      filterModel[id] = m.in && m.in.length ? { filterType: 'set', values: m.in.slice() } : { filterType: 'set', values: null, excluded: (m.notIn || []).slice() };
    });
    const ask = (keys, groupCols, pivotCols) => callGetRows(ds, {
      startRow: 0, endRow: limit, rowGroupCols: groupCols.map((id) => colObj(ctx, id)), valueCols: vals.map((v) => colObj(ctx, v.id, v.agg)),
      pivotCols: pivotCols.map((id) => colObj(ctx, id)), pivotMode: true, groupKeys: keys.slice(), filterModel: filterModel, sortModel: []
    }, ctx, signal, keys.length ? { level: keys.length - 1, key: keys[keys.length - 1], id: keys.join(SEP) } : null);
    const fieldSet = [], seen = new Set();
    const note = (a) => (a.pivotResultFields || []).forEach((f) => { if (!seen.has(f)) { seen.add(f); fieldSet.push(f); } });
    const levels = new Map();
    const level = (keys) => {
      const lp = ask(keys, R, C).then((a) => {
        note(a);
        const tot = ctx.rowTotals && C.length ? ask(keys, R, []) : Promise.resolve(null);
        return tot.then((t) => {
          levels.set(keys.join(SEP), { rows: a.rows, totals: t ? t.rows : null });
          if (keys.length >= R.length - 1) { return null; }
          const field = ctx.column(R[keys.length]) ? ctx.column(R[keys.length]).field : R[keys.length];
          return Promise.all(a.rows.map((r) => {
            const k = keys.concat([String(r[field])]);
            return state.isOpen('rows', k) ? level(k) : null;
          }));
        });
      });
      return lp;
    };
    const top = R.length ? level([]) : Promise.resolve();
    const grand = ctx.grandRow || !R.length ? ask([], [], C).then((a) => {
      note(a);
      const row = a.rows[0] || {};
      return ctx.rowTotals && C.length ? ask([], [], []).then((t) => ({ row: row, total: t.rows[0] || {} })) : { row: row, total: null };
    }) : Promise.resolve(null);
    return Promise.all([top, grand]).then((res) => buildAnswer({ view: view, R: R, C: C, vals: vals, sep: sep, fields: fieldSet, levels: levels, grand: res[1], state: state, ctx: ctx, rowTotals: !!ctx.rowTotals }));
  };
}

/** @private the pivot answer from the levels the server answered */
function buildAnswer(o) {
  const { view, R, C, vals, sep, levels, state, ctx } = o;
  const fieldOf = (id) => (ctx.column(id) ? ctx.column(id).field : id);
  const valueIds = vals.map((v) => v.id);
  const cols = [], colByKey = new Map(), cellField = [];
  const pathPairs = (dims, keys) => keys.map((k, i) => [view[dims][i], k]);
  const coordsOf = (dims, keys) => { const c = {}; keys.forEach((k, i) => { c[view[dims][i]] = k; }); return c; };
  if (C.length) {
    o.fields.forEach((f) => {
      const p = parseResultField(f, valueIds, sep, C.length);
      if (!p || !p.keys.length) { return; }
      const key = p.keys.join(SEP);
      if (!colByKey.has(key)) {
        const e = { key: key, kind: 'node', depth: p.keys.length, label: p.keys[p.keys.length - 1], dim: view.columns[p.keys.length - 1], value: p.keys[p.keys.length - 1],
          path: pathPairs('columns', p.keys), coords: coordsOf('columns', p.keys), expandable: false, expanded: false };
        colByKey.set(key, e);
        cols.push(e);
      }
      cellField.push({ colKey: key, valueId: p.valueId, field: f });
    });
  }
  if (!C.length || o.rowTotals) {
    cols.push({ key: '', kind: 'grand', depth: 0, label: 'Grand total', dim: null, value: null, path: [], coords: {}, expandable: false, expanded: false });
    vals.forEach((v) => cellField.push({ colKey: '', valueId: v.id, field: fieldOf(v.id), total: true }));
  }
  const rows = [], cells = {};
  const put = (rowKey, coords, data, totals) => {
    cellField.forEach((cf) => {
      const src = cf.total && C.length ? totals : data;
      if (!src) { return; }
      const x = src[cf.field];
      if (x === undefined) { return; }
      const k = rowKey + '|' + cf.colKey, col = colByKey.get(cf.colKey);
      const cell = cells[k] || (cells[k] = { values: {}, members: data && typeof data.childCount === 'number' ? data.childCount : 0, coords: Object.assign({}, coords, col ? col.coords : {}) });
      vals.filter((v) => v.id === cf.valueId).forEach((v) => { cell.values[v.label] = typeof x === 'number' || x === null ? x : isFinite(+x) && x !== '' ? +x : x; });
    });
  };
  const walk = (keys) => {
    const lv = levels.get(keys.join(SEP));
    if (!lv) { return; }
    const field = fieldOf(R[keys.length]);
    const totalsBy = new Map();
    (lv.totals || []).forEach((t) => totalsBy.set(String(t[field]), t));
    lv.rows.forEach((r) => {
      const k = keys.concat([String(r[field])]), key = k.join(SEP), expandable = k.length < R.length;
      const e = { key: key, kind: 'node', depth: k.length, label: k[k.length - 1], dim: view.rows[k.length - 1], value: k[k.length - 1], path: pathPairs('rows', k),
        coords: coordsOf('rows', k), expandable: expandable, expanded: expandable && state.isOpen('rows', k) };
      put(key, e.coords, r, totalsBy.get(String(r[field])) || null);
      if (!e.expanded) { rows.push(e); return; }
      if (view.subtotals === 'below') {
        rows.push(Object.assign({}, e, { kind: 'header' }));
        walk(k);
        rows.push(Object.assign({}, e, { kind: 'total', label: e.label + ' total' }));
      } else { rows.push(e); walk(k); }
    });
  };
  if (R.length) { walk([]); }
  if (o.grand) {
    rows.push({ key: '', kind: 'grand', depth: 0, label: 'Grand total', dim: null, value: null, path: [], coords: {}, expandable: false, expanded: false });
    put('', {}, o.grand.row, o.grand.total);
  }
  const measures = vals.map((v, i) => ({ index: i, id: view.values[i].measure, label: v.label, kind: v.server ? 'server' : 'mergeable', plainSum: false, exact: true, server: v.server }));
  return { measures: measures, rowDimensions: view.rows, columnDimensions: view.columns, rows: rows, columns: cols, cells: cells, pending: [], computedBy: 'server', notes: [] };
}
