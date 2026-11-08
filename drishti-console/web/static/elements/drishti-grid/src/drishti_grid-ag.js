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
/* The AG Grid-style adapter (ELEMENTS.md section 6): an application written for AG Grid Enterprise's option shapes (row grouping, pivoting,
   the server-side row model, tree data) gets a drishti-grid by changing its constructor call:

     const api = createGridFromAgOptions(document.getElementById('grid'), gridOptions);   // or Drishti.elements.grid.agGrid(el, gridOptions)

   translateAgOptions turns the options into drishti-grid's (columns, rows or a datasource, the pivot's wells, totals, open levels, layout);
   the handle (drishti_grid-ag-api.js) answers the common grid API calls and raises the AG-named events from drishti-grid's own. Every
   option it does not cover is named in a console warning with drishti-grid's way, once, and listed by reportUnsupported(): nothing is
   ignored silently. Drishti never uses AG Grid: no AG Grid code, package, CSS or look; written from its public documentation only. */
import { registerAggFunc } from './drishti_aggregate.js';
import { AG_GRID_OPTIONS, AG_COLUMN_KEYS, AG_UNSUPPORTED, AG_NO_OP, AG_EVENTS, translateFilterModel } from './drishti_grid-ag-map.js';
import { agDatasource, agServerPivot } from './drishti_grid-ag-server.js';

/** The adapter's group column (added when rows may be grouped or make a tree): its id. */
export const AG_GROUP_COLUMN = '__group';
const BUILT_IN = ['sum', 'min', 'max', 'count', 'avg', 'first', 'last'];
let seq = 0;

/**
 * What the adapter did with one option: mapped in part, not supported, nothing to do, or a name it does not know.
 * @typedef {Object} AgReportItem
 * @property {string} option the option's name (a column's as columnDefs[ID].name)
 * @property {('partial'|'unsupported'|'no-op'|'unknown')} state
 * @property {string} words what happens instead (drishti-grid's way)
 */

/**
 * The adapter's report: each option said once in the console (a warning, or information for one that needs nothing) and kept.
 */
export class AgReport {
  /** @param {{warn: function(string): void, info: function(string): void}} [out] default: the console */
  constructor(out) {
    const c = typeof console !== 'undefined' ? console : null;
    this.out = out || { warn: (m) => c && c.warn(m), info: (m) => c && c.info(m) };
    /** @type {AgReportItem[]} */
    this.items = [];
    this.seen = new Set();
  }

  /**
   * Says and keeps one item (once per option and state).
   * @param {string} option
   * @param {('partial'|'unsupported'|'no-op'|'unknown')} state
   * @param {string} words
   * @returns {void}
   */
  add(option, state, words) {
    const k = option + '\u001f' + state;
    if (this.seen.has(k)) { return; }
    this.seen.add(k);
    this.items.push({ option: option, state: state, words: words });
    const lead = 'drishti-grid (AG Grid-style adapter): ' + option;
    const text = state === 'unsupported' ? lead + ' is not supported. The drishti-grid way: ' + words
      : state === 'partial' ? lead + ' is mapped in part: ' + words
        : state === 'unknown' ? lead + ' is not an option the adapter knows and is ignored' + (words ? ': ' + words : '')
          : lead + ' needs nothing: ' + words;
    if (state === 'no-op') { this.out.info(text); } else { this.out.warn(text); }
  }
}

/** @private a column definition with its types, the default definition and the column's own keys, in that order */
function merged(d, go, report) {
  const out = Object.assign({}, go.defaultColDef || {});
  const types = d.type === undefined ? [] : Array.isArray(d.type) ? d.type : [d.type];
  const flags = {};
  types.forEach((t) => {
    if (go.columnTypes && go.columnTypes[t]) { Object.assign(out, go.columnTypes[t]); } else if (t === 'numericColumn') { flags.number = true; }
    else if (t !== 'rightAligned') { report.add('columnDefs[].type ' + t, 'unknown', 'name it in columnTypes'); }
  });
  Object.assign(out, d);
  delete out.type;
  return { def: out, flags: flags };
}

/** @private the type of a column: cellDataType, numericColumn, else the first row's value */
function typeOf(def, flags, sample) {
  const t = def.cellDataType;
  if (t === 'number' || flags.number) { return 'number'; }
  if (t === 'date' || t === 'dateString' || t === 'dateTime' || t === 'dateTimeString') { return 'date'; }
  if (t === 'boolean') { return 'boolean'; }
  if (t === 'text' || t === 'object') { return 'text'; }
  if (sample) { const v = def.valueGetter && typeof def.valueGetter === 'function' ? undefined : sample[def.field]; return typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : 'text'; }
  return 'text';
}

/** @private AG-shaped parameters of a cell (what valueGetter, valueFormatter, cellClass and cellStyle are given) */
function cellParams(ctx, def, id, value, row) {
  return { value: value, data: row, node: { data: row, id: null, group: false }, colDef: def, column: { colId: id, getColId: () => id }, context: ctx.context, api: ctx.api,
    getValue: (f) => (row ? row[f] : undefined) };
}

/**
 * An aggregation as the AG option names it, as drishti-grid's: a built-in name, a function or an aggFuncs name (registered), a
 * non-additive server measure ('drishti:server', 'drishti:server:var(99, 1d)'), or another name (the server's measure).
 * @param {*} agg the colDef's aggFunc
 * @param {string} id the column's id
 * @param {Object} ctx the translation's context (aggFuncs, registered names, the report)
 * @returns {{aggFunc: ?string, additive: (boolean|undefined), measure: (string|undefined), agName: (string|undefined)}}
 */
export function agAggFunc(agg, id, ctx) {
  if (agg === undefined || agg === null || agg === false) { return { aggFunc: null }; }
  if (typeof agg === 'function') {
    const name = 'ag' + ctx.seq + '_' + String(id).replace(/\W/g, '_');
    if (!ctx.registered.has(name)) { registerAggFunc(name, (values) => agg({ values: values, column: { colId: id }, context: ctx.context, api: ctx.api }), { numeric: true }); ctx.registered.add(name); }
    return { aggFunc: name, agName: 'custom' };
  }
  const s = String(agg);
  if (s.indexOf('drishti:server') === 0) { const m = s.slice('drishti:server'.length).replace(/^:/, '').trim(); return { aggFunc: 'sum', additive: false, measure: m || undefined, agName: s }; }
  if (BUILT_IN.indexOf(s) >= 0) { return { aggFunc: s, agName: s }; }
  if (ctx.aggFuncs && typeof ctx.aggFuncs[s] === 'function') {
    const name = 'ag' + ctx.seq + '_' + s.replace(/\W/g, '_');
    if (!ctx.registered.has(name)) { const fn = ctx.aggFuncs[s]; registerAggFunc(name, (values) => fn({ values: values, column: { colId: id }, context: ctx.context, api: ctx.api }), { label: s, numeric: true }); ctx.registered.add(name); }
    return { aggFunc: name, agName: s };
  }
  if (!ctx.serverSide && !ctx.serverPivot) { ctx.report.add('columnDefs[' + id + '].aggFunc ' + s, 'partial', 'the browser does not compute ' + s + ': it is asked of the server, so give drishti.serverPivot (else its cells say so)'); }
  return { aggFunc: s, agName: s };
}

/** @private one column definition (types and defaults merged) as drishti-grid's ColumnDef */
function column(d, group, ctx, i) {
  const { def, flags } = merged(d, ctx.go, ctx.report);
  const id = String(def.colId || def.field || 'col' + i);
  const where = 'columnDefs[' + id + ']';
  Object.keys(d).forEach((k) => {
    if (AG_UNSUPPORTED[k]) { ctx.report.add(where + '.' + k, 'unsupported', AG_UNSUPPORTED[k]); }
    else if (AG_NO_OP.indexOf(k) >= 0) { ctx.report.add(where + '.' + k, 'no-op', 'drishti-grid draws it its own way'); }
    else if (AG_COLUMN_KEYS.indexOf(k) < 0 && k !== 'checkboxSelection' && k !== 'headerCheckboxSelection') { ctx.report.add(where + '.' + k, 'unknown', ''); }
  });
  if (def.checkboxSelection) { ctx.checkboxes = true; }
  const c = { id: id, field: def.field || id, type: typeOf(def, flags, ctx.sample) };
  if (def.headerName !== undefined) { c.header = def.headerName; }
  if (def.headerTooltip) { c.description = def.headerTooltip; }
  if (group.length) { c.group = group.join(' › '); }
  const width = def.width !== undefined ? def.width : def.initialWidth;
  if (width !== undefined) { c.width = width; }
  if (def.minWidth !== undefined) { c.minWidth = def.minWidth; }
  if (def.maxWidth !== undefined) { c.maxWidth = def.maxWidth; }
  if (def.flex) { ctx.flex[id] = def.flex; ctx.report.add('columnDefs[].flex', 'partial', 'a share of the element\'s width when the grid is drawn and on sizeColumnsToFit; it does not follow later resizes'); }
  const hide = def.hide !== undefined ? def.hide : def.initialHide;
  if (hide) { c.hidden = true; }
  const pin = def.pinned !== undefined ? def.pinned : def.initialPinned;
  if (pin) { c.pinned = pin === 'right' ? 'right' : 'left'; }
  c.sortable = def.sortable !== false;
  c.resizable = def.resizable !== false;
  c.filterable = !!def.filter;
  if (def.filter && def.filter !== true && typeof def.filter !== 'string') { ctx.report.add(where + '.filter', 'partial', 'a filter component of your own is drishti-grid\'s filter by the column\'s type'); }
  const fn = (name, make) => {
    const v = def[name];
    if (v === undefined || v === null) { return; }
    if (typeof v === 'function') { make(v); } else { ctx.report.add(where + '.' + name, 'unsupported', 'a function (string expressions are not evaluated)'); }
  };
  fn('valueGetter', (g) => { c.valueGetter = (row) => g(cellParams(ctx, def, id, undefined, row)); });
  fn('valueFormatter', (f) => { c.format = (v, row) => { const s = f(cellParams(ctx, def, id, v, row)); return s === null || s === undefined ? '' : String(s); }; });
  fn('comparator', (f) => { c.comparator = (a, b) => f(a, b, null, null, false); });
  const rules = def.cellClassRules || null, base = def.cellClass;
  if (base || rules) {
    if (rules) { Object.keys(rules).forEach((k) => { if (typeof rules[k] !== 'function') { ctx.report.add(where + '.cellClassRules.' + k, 'unsupported', 'a function (string expressions are not evaluated)'); } }); }
    c.cellClass = (v, row) => {
      const p = cellParams(ctx, def, id, v, row);
      const own = typeof base === 'function' ? base(p) : base;
      const out = own ? (Array.isArray(own) ? own.slice() : String(own).split(/\s+/)) : [];
      if (rules) { Object.keys(rules).forEach((k) => { if (typeof rules[k] === 'function' && rules[k](p)) { out.push(k); } }); }
      return out;
    };
  }
  if (def.cellStyle) { const s = def.cellStyle; c.cellStyle = typeof s === 'function' ? (v, row) => s(cellParams(ctx, def, id, v, row)) : s; }
  if (def.editable) { c.editable = true; if (typeof def.editable === 'function') { ctx.report.add(where + '.editable', 'partial', 'a function is read as true: every row may be edited (refuse an edit with the write-back hook)'); } }
  ['enableRowGroup', 'enablePivot', 'enableValue'].forEach((k) => { if (def[k] !== undefined) { c[k] = !!def[k]; } });
  const rg = def.rowGroup !== undefined ? def.rowGroup : def.initialRowGroup, pv = def.pivot !== undefined ? def.pivot : def.initialPivot;
  if (rg || def.rowGroupIndex !== undefined && def.rowGroupIndex !== null) { c.enableRowGroup = true; ctx.groups.push({ id: id, at: def.rowGroupIndex !== undefined && def.rowGroupIndex !== null ? def.rowGroupIndex : 1e6 + i }); }
  if (pv || def.pivotIndex !== undefined && def.pivotIndex !== null) { c.enablePivot = true; ctx.pivots.push({ id: id, at: def.pivotIndex !== undefined && def.pivotIndex !== null ? def.pivotIndex : 1e6 + i }); }
  const a = agAggFunc(def.aggFunc !== undefined ? def.aggFunc : def.initialAggFunc, id, ctx);
  c.aggFunc = a.aggFunc;
  if (a.additive === false) { c.additive = false; if (!ctx.serverSide && !ctx.serverPivot) { ctx.report.add(where + '.aggFunc drishti:server', 'partial', 'a non-additive measure\'s figures come from the server: give drishti.serverPivot'); } }
  if (a.measure) { c.measure = a.measure; }
  if (a.aggFunc) { c.enableValue = true; ctx.values.push({ id: id, aggFunc: a.aggFunc, agName: a.agName, header: c.header || c.field }); }
  if (a.agName) { ctx.aggNames[id] = a.agName; }
  if (def.defaultAggFunc) { ctx.report.add(where + '.defaultAggFunc', 'partial', 'a figure dropped on Values takes its aggFunc, else sum'); }
  const sort = def.sort !== undefined ? def.sort : def.initialSort;
  if (sort === 'asc' || sort === 'desc') { ctx.sort.push({ colId: id, sort: sort, at: def.sortIndex !== undefined && def.sortIndex !== null ? def.sortIndex : 1e6 + i }); }
  return c;
}

/** @private the column definitions flattened, column groups as headings */
function columns(defs, group, ctx, out) {
  (defs || []).forEach((d) => {
    if (d && d.children) {
      Object.keys(d).forEach((k) => { if (['children', 'headerName', 'groupId', 'marryChildren', 'openByDefault', 'columnGroupShow'].indexOf(k) < 0) { ctx.report.add('columnDefs[group ' + (d.headerName || d.groupId) + '].' + k, 'unknown', ''); } });
      if (d.marryChildren || d.openByDefault !== undefined) { ctx.report.add('column group marryChildren / openByDefault', 'partial', 'a group is a heading over its columns; they move and hide one by one'); }
      columns(d.children, group.concat([d.headerName || d.groupId || '']).filter(Boolean), ctx, out);
    } else if (d) { out.push(column(d, group, ctx, out.length)); }
  });
  return out;
}

const totalRow = (v) => (v === 'top' || v === 'pinnedTop' ? 'top' : v === 'bottom' || v === 'pinnedBottom' || v === true ? 'bottom' : 'none');

/**
 * The translation: AG Grid-shaped grid options as drishti-grid's options, the pivot's wells, the start state and the report.
 * @param {Object} go the AG Grid-shaped grid options
 * @param {{report: AgReport, api: Object}} [o] report: where options not covered are said; api: the handle (params.api)
 * @returns {{options: Object, wells: Object, rowGroups: string[], sort: Array, flex: Object<string, number>, expand: {rows: number, columns: number},
 *   autoHeight: boolean, events: Object<string, function>, aggNames: Object<string, string>, registered: string[], report: AgReport}}
 */
export function translateAgOptions(go, o) {
  go = go || {};
  o = o || {};
  const report = o.report || new AgReport();
  const dx = go.drishti || {};
  const rmt = go.rowModelType || 'clientSide';
  const ctx = { go: go, report: report, seq: ++seq, registered: new Set(), aggFuncs: go.aggFuncs, context: go.context, api: o.api || null, flex: {}, groups: [], pivots: [],
    values: [], sort: [], aggNames: {}, checkboxes: false, serverSide: rmt === 'serverSide' || rmt === 'infinite', serverPivot: !!dx.serverPivot,
    sample: Array.isArray(go.rowData) ? go.rowData.find((r) => r) : null };
  const events = {};
  Object.keys(go).forEach((k) => {
    if (/^on[A-Z]/.test(k) && typeof go[k] === 'function') {
      const name = k.charAt(2).toLowerCase() + k.slice(3);
      if (AG_EVENTS.indexOf(name) >= 0) { events[name] = go[k]; } else { report.add(k, 'unsupported', 'one of the events the adapter raises (' + AG_EVENTS.join(', ') + '), or drishti-grid\'s own on(name)'); }
    } else if (AG_UNSUPPORTED[k]) { report.add(k, 'unsupported', AG_UNSUPPORTED[k]); }
    else if (AG_NO_OP.indexOf(k) >= 0) { report.add(k, 'no-op', 'drishti-grid has it always (live changes flash, cell ranges, the status bar)'); }
    else if (AG_GRID_OPTIONS.indexOf(k) < 0) { report.add(k, 'unknown', ''); }
  });
  const cols = columns(go.columnDefs, [], ctx, []);
  const tree = !!go.treeData;
  if (tree && rmt === 'infinite') { report.add('treeData with rowModelType infinite', 'unsupported', 'rowModelType serverSide with isServerSideGroup and getServerSideGroupKey (server-side tree data)'); }
  if (ctx.groups.length || tree || cols.some((c) => c.enableRowGroup)) {
    const a = go.autoGroupColumnDef || {};
    Object.keys(a).forEach((k) => { if (['headerName', 'width', 'minWidth', 'maxWidth', 'pinned', 'cellRendererParams', 'field'].indexOf(k) < 0) { report.add('autoGroupColumnDef.' + k, 'unknown', ''); } });
    if (a.cellRendererParams) { report.add('autoGroupColumnDef.cellRendererParams', 'unsupported', 'the group column shows the member and its count'); }
    cols.unshift({ id: AG_GROUP_COLUMN, field: AG_GROUP_COLUMN, header: a.headerName || 'Group', type: 'text', width: a.width || 200, minWidth: a.minWidth || 60, maxWidth: a.maxWidth,
      pinned: a.pinned === 'right' ? 'right' : a.pinned ? 'left' : null, hidden: !(tree || (ctx.groups.length && !go.pivotMode)), sortable: false, filterable: false, system: true,
      enableRowGroup: false, enablePivot: false, enableValue: false, aggFunc: null, valueGetter: () => '' });
  }
  if (go.groupDisplayType && go.groupDisplayType !== 'singleColumn') { report.add('groupDisplayType ' + go.groupDisplayType, 'partial', 'drawn as one group column (singleColumn)'); }
  const byAt = (l) => l.slice().sort((a, b) => a.at - b.at).map((x) => x.id);
  const sel = go.rowSelection;
  const mode = !sel ? 'none' : typeof sel === 'string' ? (sel === 'single' ? 'single' : 'multiple') : sel.mode === 'singleRow' ? 'single' : 'multiple';
  const checkboxes = !!sel && (typeof sel === 'object' ? sel.checkboxes !== false : ctx.checkboxes);
  const density = dx.density || (go.rowHeight ? (go.rowHeight <= 24 ? 'terminal' : go.rowHeight <= 31 ? 'compact' : 'comfortable') : 'compact');
  if (go.rowHeight) { report.add('rowHeight', 'partial', 'the nearest density, ' + density + ' (rows of 22, 28 or 36 px)'); }
  if (go.domLayout === 'autoHeight') { report.add('domLayout autoHeight', 'partial', 'the element\'s height follows the row count when the grid is drawn; give large data a fixed height'); }
  else if (go.domLayout && go.domLayout !== 'normal') { report.add('domLayout ' + go.domLayout, 'unsupported', 'a fixed height (virtual rows); export to print'); }
  if (go.pivotRowTotals === 'before' || go.pivotColumnGroupTotals === 'before') { report.add('pivotRowTotals / pivotColumnGroupTotals before', 'partial', 'drishti-grid draws totals after the members'); }
  if (rmt === 'viewport') { report.add('rowModelType viewport', 'unsupported', 'rowModelType serverSide (drishti-grid\'s server-side row model)'); }
  if (rmt === 'infinite') { report.add('rowModelType infinite', 'partial', 'the server-side row model without groups'); }
  if (rmt === 'clientSide' && (go.grandTotalRow || go.groupTotalRow || go.groupIncludeFooter || go.groupIncludeTotalFooter) && !go.pivotMode) {
    report.add('grandTotalRow / groupTotalRow on grouped rows', 'partial', 'total rows are drawn in pivot mode; grouped rows carry their figures on the group row');
  }
  const out = Object.assign({ columns: cols, rowSelection: mode, checkboxes: checkboxes, layout: dx.layout || (go.sideBar ? 'panel' : 'wells'), density: density,
    pinTotals: false, grandColumn: !!go.pivotRowTotals, columnGroupTotals: !!go.pivotColumnGroupTotals, treeData: tree,
    groupDefaultExpanded: go.groupDefaultExpanded || 0 }, ctx.serverSide ? {} : { rows: go.rowData || [] });
  if (tree && ctx.serverSide) {                                                 // server-side tree data: the server answers one level at a time
    if (typeof go.isServerSideGroup !== 'function' || typeof go.getServerSideGroupKey !== 'function') { throw new Error('treeData with rowModelType serverSide needs isServerSideGroup(data) and getServerSideGroupKey(data)'); }
    out.isServerSideGroup = (row) => !!go.isServerSideGroup(row);
    out.getServerSideGroupKey = (row) => go.getServerSideGroupKey(row);
  } else if (tree) { if (typeof go.getDataPath !== 'function') { throw new Error('treeData needs getDataPath(data)'); } out.getDataPath = (row) => go.getDataPath(row); }
  if (typeof go.getRowId === 'function') { out.getRowId = (row, i, keys) => String(go.getRowId({ data: row, level: keys ? keys.length : 0, parentKeys: keys ? keys.slice() : [], context: go.context, api: ctx.api })); }
  if (go.cacheBlockSize) { out.blockSize = go.cacheBlockSize; }
  const sctx = { column: (id) => o.columnOf ? o.columnOf(id) : cols.find((c) => c.id === id || c.field === id) || null, aggOf: (id) => ctx.aggNames[id], context: go.context,
    get api() { return ctx.api; }, separator: go.serverSidePivotResultFieldSeparator || '_', grandRow: totalRow(go.grandTotalRow || go.groupIncludeTotalFooter) !== 'none',
    rowTotals: !!go.pivotRowTotals, infinite: rmt === 'infinite' };
  if (ctx.serverSide) {
    out.datasource = agDatasource(rmt === 'infinite' ? go.datasource : go.serverSideDatasource, sctx);
    if (!dx.serverPivot && rmt === 'serverSide') { out.serverPivot = agServerPivot(go.serverSideDatasource, sctx); }
  }
  Object.keys(dx).forEach((k) => { out[k] = dx[k]; });
  const wells = { rows: byAt(ctx.groups), columns: byAt(ctx.pivots),
    values: ctx.values.map((v) => (go.suppressAggFuncInHeader ? { colId: v.id, aggFunc: v.aggFunc, as: v.header } : { colId: v.id, aggFunc: v.aggFunc })), filters: [] };
  out.pivotOptions = Object.assign({ subtotals: totalRow(go.groupTotalRow || go.groupIncludeFooter) === 'bottom' ? 'below' : 'above',
    grandTotalRow: totalRow(go.grandTotalRow || go.groupIncludeTotalFooter) }, dx.pivotOptions || {});
  const lv = (n) => (n === -1 ? 99 : Math.max(1, (n || 0) + 1));
  return { options: out, wells: wells, rowGroups: go.pivotMode ? [] : wells.rows, sort: ctx.sort.slice().sort((a, b) => a.at - b.at).map((s) => ({ colId: s.colId, sort: s.sort })),
    flex: ctx.flex, expand: { rows: lv(go.groupDefaultExpanded), columns: lv(go.pivotDefaultExpanded) }, autoHeight: go.domLayout === 'autoHeight', events: events,
    aggNames: ctx.aggNames, registered: Array.from(ctx.registered), report: report, pivotMode: !!go.pivotMode, quickFilter: go.quickFilterText || '', setApi: (api) => { ctx.api = api; },
    filterModel: (model, typeOf2) => translateFilterModel(model, true, typeOf2, (w) => report.add('filterModel', 'partial', w)) };
}
