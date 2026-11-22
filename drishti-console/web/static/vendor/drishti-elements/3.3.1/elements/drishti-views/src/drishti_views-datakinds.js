/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The kinds of contract 1.1 that read a dataset: grid (drishti-grid: virtual rows, typed columns, groups, an order, a pivot computed in
   the browser, the server's windows for pages and server sources), tree (drishti-tree in its four modes) and chart (drishti-chart over
   a derived read of the rows). Each loads its library (and adopts its sheets) the first time a view has such a panel, reads its dataset
   by name and follows the dataset's rows patches: a grid by transaction (only the rows in view are drawn again), a tree and a chart by
   drawing their rows again in the next animation frame. Values are raw: each column formats them with its field's fmt. */
import { h, stateBlock, isMasked, formatCount } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { say, toneClass } from './drishti_views-cells.js';
import { valueText, rowKey, derive, engineAgg } from './drishti_views-data.js';

const SEP = '\u001f';

/** The kinds drawn from a dataset (contract 1.1; live 1.2). */
export const DATASET_KINDS = Object.freeze(['grid', 'tree', 'chart', 'live']);

const list = (x) => (Array.isArray(x) ? x : []);
const GRID_TYPES = { number: 'number', boolean: 'boolean', date: 'date', datetime: 'date' };

/**
 * drishti-grid's columns of a grid panel: its columns, or every field of its dataset; each formats its raw values with the field's fmt.
 * @param {Object} d the panel's data (Grid)
 * @param {Object} ds the dataset
 * @returns {Object[]}
 */
export function gridColumns(d, ds) {
  const cols = list(d.columns).length ? list(d.columns) : list(ds.fields).map((f) => ({ field: f.name }));
  return cols.filter((c) => c && typeof c.field === 'string').map((c) => {
    const f = ds.field(c.field) || { name: c.field, type: 'string' }, fmt = c.fmt || f.fmt || null;
    const col = { field: c.field, header: c.label || f.label || c.field, type: GRID_TYPES[f.type] || 'text', format: (v) => valueText(v, fmt, f.type) };
    if (c.width > 0) { col.width = c.width; }
    if (c.pinned === 'left' || c.pinned === 'right') { col.pinned = c.pinned; }
    if (c.hidden === true) { col.hidden = true; }
    if (c.agg) { col.aggFunc = engineAgg(c.agg); col.enableValue = true; }
    if (c.tone === 'sign') { col.cellClass = (v) => (typeof v === 'number' ? (v > 0 ? 'dv-t-pos' : v < 0 ? 'dv-t-neg' : '') : ''); } else if (c.tone) { const t = toneClass(c.tone); col.cellClass = () => t; }
    if (col.type !== 'number') { col.enableRowGroup = true; col.enablePivot = true; }
    return col;
  });
}

/**
 * drishti-chart's spec of a chart panel over rows already derived (grouped and combined).
 * @param {Object} d the panel's data (DataChart)
 * @param {Object} ds the dataset
 * @param {Object[]} rows
 * @returns {Object}
 */
export function chartSpec(d, ds, rows) {
  const y = list(d.y).filter((m) => m && m.field), m0 = y[0] || { field: '' };
  const label = (n) => { const f = ds.field(n); return (f && f.label) || n; };
  const fmt = m0.fmt || (ds.field(m0.field) || {}).fmt || 'number';
  const clean = rows.map((r) => { const o = {}; Object.keys(r).forEach((k) => { o[k] = isMasked(r[k]) ? null : r[k]; }); return o; });
  const base = { title: '', data: { rows: clean } };
  const type = d.type || 'bar';
  if (type === 'bar' || type === 'line' || type === 'area') {
    const s = Object.assign(base, { kind: type, x: { field: d.x, title: d.xLabel || label(d.x) }, y: { format: fmt, title: d.yLabel || '' } });
    if (d.by) { s.y.field = m0.field; s.series = { by: d.by }; } else { s.series = y.map((m) => ({ field: m.field, name: m.label || label(m.field) })); }
    if (d.stack === 'percent') { s.stack = 'percent'; } else if (d.stack === 'sum') { s.stack = true; }
    if (d.horizontal === true) { s.orient = 'horizontal'; }
    return s;
  }
  if (type === 'scatter') { return Object.assign(base, { kind: 'scatter', x: { field: d.x, title: d.xLabel || label(d.x) }, y: { field: m0.field, format: fmt, title: d.yLabel || label(m0.field) }, colour: d.by || undefined }); }
  if (type === 'pie' || type === 'donut') { return Object.assign(base, { kind: type, category: d.x, value: { field: m0.field, format: fmt } }); }
  if (type === 'heatmap') { return Object.assign(base, { kind: 'heatmap', x: { field: d.x, title: d.xLabel || label(d.x) }, y: d.by, value: { field: m0.field, format: fmt } }); }
  if (type === 'histogram') { return Object.assign(base, { kind: 'histogram', value: { field: m0.field, format: fmt }, bins: d.bins > 0 ? d.bins : 20 }); }
  return Object.assign(base, { kind: type, x: d.x, y: { field: m0.field, format: fmt } });
}

/** The words of a panel that cannot draw: in its body, never a blank. */
function words(body, ctx, key, vars, detail) {
  const b = stateBlock(ctx.doc, key === 'noDataset' || key === 'dataFailed' ? 'error' : 'empty', { text: say(ctx.messages, key, vars), detail: detail || null });
  body.appendChild(b);
  return { dispose() { b.remove(); } };
}

/**
 * A dataset kind's painter: finds its dataset, loads its library and its sheets, draws, follows the rows patches, and draws again when
 * the panel is replaced.
 * @param {string} lib the library
 * @param {function(Object, HTMLElement, Object, Object, Object): Object} make (lib, box, panel, ds, ctx) => {change(c), destroy()}
 */
function datasetKind(lib, make) {
  return {
    paint(body, panel, ctx) {
      const d = panel.data || {}, ds = ctx.dataset(d.dataset);
      if (!ds) { return words(body, ctx, 'noDataset', { name: String(d.dataset) }); }
      const doc = ctx.doc;
      const box = h(doc, 'div', { cls: 'dv-ds dv-ds-' + panel.kind, attrs: { 'data-dataset': ds.name, 'aria-label': panel.title || panel.id } });
      const host = h(doc, 'div', { cls: 'dv-ds-host' });          // the library's own element (a grid makes its host .dg, height 100%)
      box.appendChild(host);
      body.appendChild(box);
      const note = ds.more > 0 ? h(doc, 'p', { cls: 'dv-more', text: say(ctx.messages, 'rowsLeft', { count: formatCount(ds.more) }) }) : null;
      if (note) { body.appendChild(note); }
      let drawn = null, gone = false, off = null;
      Promise.all([ctx.library(lib), ctx.styles(lib)]).then(([L]) => {
        if (gone) { return null; }
        return Promise.resolve(make(L, host, panel, ds, ctx)).then((inst) => {
          if (gone) { if (inst && inst.destroy) { inst.destroy(); } return; }
          drawn = inst;
          off = ds.on((c) => { if (drawn && drawn.change) { drawn.change(c); } });
          box.setAttribute('data-drawn', '');
          ctx.emit('drawn', { panel: panel.id, kind: panel.kind });
        });
      }).catch((e) => { if (!gone) { host.textContent = ''; words(host, ctx, 'dataFailed', null, String(e && e.message || e)); } });
      return {
        /** @returns {Object} the drawing (the grid, tree or chart), for a host that needs it */
        component() { return drawn && drawn.component; },
        dispose() {
          gone = true;
          if (off) { off(); }
          if (drawn && drawn.destroy) { try { drawn.destroy(); } catch (e) { /* a library's teardown never stops the view's */ } }
          box.remove();
          if (note) { note.remove(); }
        }
      };
    }
  };
}

/** Calls fn once in the next animation frame however often it is asked. */
function later(ctx, fn) {
  let asked = false;
  return () => {
    if (asked) { return; }
    asked = true;
    const win = ctx.doc.defaultView, run = () => { asked = false; fn(); };
    if (win && win.requestAnimationFrame) { win.requestAnimationFrame(run); } else { setTimeout(run, 0); }
  };
}

/**
 * Bars in the cells of the columns that ask for one (contract 1.2 GridColumn.bar): drishti-grid's barCell against the column's largest
 * magnitude among the rows now held.
 * @param {Object} G drishti-grid
 * @param {Object[]} cols the grid's columns
 * @param {Object} d the panel's data
 * @param {function(): Object[]} rows the rows now held
 */
function withBars(G, cols, d, rows) {
  const barred = new Set(list(d.columns).filter((c) => c && c.bar === true).map((c) => c.field));
  if (!barred.size || typeof G.barCell !== 'function') { return cols; }
  return cols.map((c) => {
    if (!barred.has(c.field)) { return c; }
    const max = () => rows().reduce((m, r) => { const v = r && typeof r[c.field] === 'number' ? Math.abs(r[c.field]) : 0; return v > m ? v : m; }, 0) || 1;
    return Object.assign({}, c, { cellRenderer: G.barCell({ max: max }), cellClass: c.cellClass, bar: true });
  });
}

/**
 * Chart any table on a grid (the host's tableTools.chart): the grid's "Chart the range" (its context menu) opens the range as a chart in a
 * dialog beside the grid, the row's first column across. Without the tool the grid only raises chartRequested.
 * @param {Object} ctx the painter's context
 * @param {Object} panel
 * @param {Object[]} cols the grid's columns
 * @returns {?function(Object): void} drishti-grid's chart option
 */
export function gridChart(ctx, panel, cols) {
  if (!(ctx.tools || {}).chart) { return null; }
  return (detail) => {
    const g = detail && list(detail.ranges)[0], t = (detail && detail.table) || { columns: [], rows: [] };
    if (!g || !t.rows.length) { return; }
    const first = g.c1 === 0;                       // the range holds the first column: its cells are the row's label already
    const columns = first ? t.columns.slice() : [String((cols[0] && cols[0].header) || '')].concat(t.columns);
    const rows = t.rows.map((r) => (first ? r.slice(1) : r.slice()));
    const opener = ctx.root && ctx.root.ownerDocument ? ctx.root.ownerDocument.activeElement : null;
    ctx.chartTable({ title: panel.title || '', columns: columns, rows: rows }, opener).catch(() => null);
  };
}

const grid = datasetKind('drishti-grid', (G, box, panel, ds, ctx) => ds.source().then((src) => {
  const d = panel.data;
  const o = { columns: withBars(G, gridColumns(d, ds), d, () => (Array.isArray(src) ? ds.held || src : [])), layout: 'wells', density: ctx.density || 'compact', rowSelection: 'single',
    getRowId: (r, i) => rowKey(r, ds.key, i) };
  const chart = gridChart(ctx, panel, o.columns);
  if (chart) { o.chart = chart; }
  if (Array.isArray(src)) { o.rows = src; } else { o.datasource = src; if (ds.def.pages && ds.def.pages.size > 0) { o.blockSize = ds.def.pages.size; } }
  if (d.pivot && list(d.pivot.values).length) {
    o.pivot = { rows: list(d.pivot.rows), columns: list(d.pivot.columns), filters: [],
      values: list(d.pivot.values).map((m) => Object.assign({ colId: m.field, aggFunc: engineAgg(m.agg || 'sum') }, m.label ? { as: m.label } : {})) };
  }
  const g = new G.Grid(box, o);
  if (list(d.group).length) { g.model.setRowGroups(list(d.group)); }
  if (list(d.sort).length) { g.model.setSortModel(list(d.sort).map((s) => ({ colId: s.field, sort: s.desc ? 'desc' : 'asc' }))); }
  g.refresh();
  g.on('selectionChanged', () => {
    const ids = g.model.selection && g.model.selection.selectedIds ? g.model.selection.selectedIds() : [];
    ctx.emit('select', { panel: panel.id, dataset: ds.name, keys: ids });
  });
  return {
    component: g,
    change(c) { if (c.reset) { g.setRows(ds.held); } else { g.applyTransaction({ add: c.add, update: c.update, remove: c.remove }); } },
    destroy() { g.destroy(); }
  };
}));

function pathOf(v) {
  if (Array.isArray(v)) { return v.map(String); }
  return v === null || v === undefined ? [] : String(v).split('/').filter(Boolean);
}

const tree = datasetKind('drishti-tree', (T, box, panel, ds, ctx) => ds.rows().then((rows) => {
  const d = panel.data;
  const measures = list(d.measures).filter((m) => m && m.field).map((m) => {
    const f = ds.field(m.field) || {}, fmt = m.fmt || f.fmt;
    return { field: m.field, label: m.label || f.label || m.field, format: (v) => valueText(v, fmt, 'number'), additive: m.agg !== 'min' && m.agg !== 'max' && m.agg !== 'avg' };
  });
  const options = () => ({ data: { rows: ds.held || rows, idField: d.node, parentField: d.parent || 'parent', labelField: d.label || d.node,
    getPath: d.path ? (r) => pathOf(r[d.path]) : undefined, measures: measures }, mode: d.mode || 'grid', density: ctx.density || 'compact',
  title: panel.title || '', label: panel.title || panel.id, openDepth: d.open === -1 ? -1 : d.open > 0 ? d.open : 1 });
  let t = new T.Tree(box, options());
  const redraw = later(ctx, () => { const mode = t.mode; t.destroy(); box.textContent = ''; t = new T.Tree(box, Object.assign(options(), mode ? { mode: mode } : {})); });
  return { get component() { return t; }, change: redraw, destroy() { t.destroy(); } };
}));

const chart = datasetKind('drishti-chart', (C, box, panel, ds, ctx) => ds.rows().then((rows) => {
  const d = panel.data, q = { x: d.x, by: d.by, y: list(d.y), limit: d.limit || 0 };
  const win = ctx.doc.defaultView;
  if (!win.echarts) { throw new Error('no chart engine'); }
  const c = new C.Chart(box, chartSpec(d, ds, derive(ds.held || rows, q)), { density: ctx.density || 'compact', height: (panel.height || 0) > 0 ? Math.max(160, panel.height * 32) : 280 });
  const redraw = later(ctx, () => { c.setSpec(chartSpec(d, ds, derive(ds.held || rows, q))); });
  return { component: c, change: redraw, destroy() { c.destroy(); } };
}));

/**
 * The live kind (contract 1.2): a grid over a live table of the Data Protocol. The dataset's live reference names the table, the window,
 * top and the filter; options.tables(ref) (the page's: drishti-rachana's liveTables over its DataClient) answers page(query) and
 * follow(after, keys, top, onPatch, onGap). The first window is read in the panel's order, the grid subscribes with its keys, each patch
 * (PROTOCOL 8.1, 16: reset, remove by key lists, update, add, total, top) is applied as one transaction (changed cells tick), rows past
 * the window leave from the bottom and the window follows the rows shown; a gap reads the page again. Nothing here decides rights.
 */
const live = datasetKind('drishti-grid', (G, box, panel, ds, ctx) => {
  const d = panel.data, ref = ds.def.live || {}, t = ctx.tables(ref);
  if (!t) { throw new Error(say(ctx.messages, 'noTables', { name: String(ref.table) })); }
  const size = ref.window > 0 ? ref.window : 200, key = ds.key;
  const sort = list(d.sort).map((s) => ({ col: s.field, dir: s.desc ? 'desc' : 'asc' }));
  const query = Object.assign({ limit: size }, sort.length ? { sort: sort } : {}, ref.filter ? { filter: ref.filter } : {});
  const held = new Map();
  const idOf = (r) => rowKey(r, key, -1);
  const keysOf = () => Array.from(held.values()).map((r) => key.map((k) => r[k]));
  const note = h(ctx.doc, 'p', { cls: 'dv-more dv-live-count', attrs: { 'aria-live': 'polite' } });
  box.parentNode.insertBefore(note, box.nextSibling);
  const lane = h(ctx.doc, 'div', { cls: 'dv-live-lane' }), editLane = h(ctx.doc, 'div', { cls: 'dv-live-edit' });   // the notices (paused, "N new") and the edit (status, reason, record)
  note.parentNode.insertBefore(lane, note.nextSibling);
  lane.parentNode.insertBefore(editLane, lane.nextSibling);
  const mode = ref.edit === 'cells' || ref.edit === 'beside' ? ref.edit : null;
  const schemaOf = () => (typeof t.schema === 'function' ? Promise.resolve().then(() => t.schema()).then((s) => s || null, () => null) : Promise.resolve(null));
  let g = null, f = null, total = null, gone = false, edit = null, notices = null, timer = null;
  const count = () => { note.textContent = say(ctx.messages, 'liveRows', { shown: formatCount(held.size), total: formatCount(total === null ? held.size : total) }); };
  const labelOf = (name) => { const x = ds.field(name); return (x && x.label) || name; };
  const idsOf = (p) => list(p.remove).map((k) => (Array.isArray(k) ? k.map((x) => (x === null || x === undefined ? '' : String(x))).join(SEP) : String(k))).concat(list(p.update).concat(list(p.add)).map(idOf));
  const apply = (p) => {
    if (!p || gone) { return; }
    let moved = false;
    const scrolled = g && g.body && g.body.scroller ? g.body.scroller.scrollTop : 0;       // how far the page is scrolled down, before the rows arrive
    if (Array.isArray(p.reset)) { held.clear(); p.reset.forEach((r) => held.set(idOf(r), Object.assign({}, r))); moved = true; }
    list(p.remove).forEach((k) => { if (held.delete(Array.isArray(k) ? k.map((x) => (x === null || x === undefined ? '' : String(x))).join(SEP) : String(k))) { moved = true; } });
    const update = [], top = new Set(list(p.top).map((k) => (Array.isArray(k) ? k.map(String).join(SEP) : String(k)))), first = [];
    let fresh = 0;
    list(p.update).concat(list(p.add)).forEach((r) => {
      const id = idOf(r), was = held.get(id);
      if (was) { const n = Object.assign({}, was, r); held.set(id, n); update.push(n); if (top.has(id)) { first.push(id); } } else { held.set(id, Object.assign({}, r)); if (top.has(id)) { first.push(id); fresh++; } moved = true; }
    });
    if (first.length) {                                             // a newest-first order: listed rows go on top, in the patch's order
      const rest = Array.from(held.entries()).filter((e) => first.indexOf(e[0]) < 0);
      const order = first.map((id) => [id, held.get(id)]).concat(rest);
      held.clear();
      order.forEach((e) => held.set(e[0], e[1]));
      moved = true;
    }
    if (edit) { if (Array.isArray(p.reset)) { edit.reset(); } else { edit.patched(idsOf(p)); } }     // the table's patch: what was pending is settled
    if (typeof p.total === 'number') { total = p.total; }
    while (held.size > size) { held.delete(Array.from(held.keys())[held.size - 1]); }   // past the window: the bottom rows leave
    if (moved) { g.setRows(Array.from(held.values())); } else if (update.length) { g.applyTransaction({ update: update }); }   // ticks flash
    if (moved && f) { f.window(keysOf(), !!ref.top); }
    if (notices && fresh) { notices.arrived(fresh, scrolled); }
    count();
  };
  const drop = (id) => { if (held.delete(id) && g) { g.setRows(Array.from(held.values())); if (f) { f.window(keysOf(), !!ref.top); } count(); } };
  const showSchema = (schema) => {
    if (!notices) { notices = new G.LiveNotices(lane, { scroller: () => (g && g.body ? g.body.scroller : null) }); }
    notices.state(schema);
    const spec = G.writeSpec(schema);
    if (mode && spec && !edit && G.LiveEdit) {
      edit = new G.LiveEdit(editLane, { spec: spec, mode: mode, table: t, idOf: idOf, rowOf: (id) => held.get(id) || null, drop: drop, label: labelOf, grid: () => g });
    } else if (edit && !spec) { edit.lockAll = 'You may no longer edit this table'; }
  };
  const read = () => Promise.all([schemaOf(), t.page(query)]).then((both) => {
    const schema = both[0], page = both[1];
    if (gone) { return; }
    showSchema(schema);
    if (edit) { edit.reset(); }
    held.clear();
    list(page.rows).slice(0, size).forEach((r) => held.set(idOf(r), Object.assign({}, r)));
    total = typeof page.total === 'number' ? page.total : null;
    if (g) { g.setRows(Array.from(held.values())); } else {
      const cols = withBars(G, gridColumns(d, ds), d, () => Array.from(held.values()));
      g = new G.Grid(box, Object.assign({ columns: edit ? cols.map((c) => edit.column(c)) : cols, layout: 'wells', density: ctx.density || 'compact',
        rowSelection: 'single', getRowId: (r) => idOf(r), rows: Array.from(held.values()), chart: gridChart(ctx, panel, cols) || undefined }, edit ? edit.gridOptions() : {}));
      if (list(d.group).length) { g.model.setRowGroups(list(d.group)); }   // the rows keep the host's order (the query's sort)
      g.refresh();
      g.on('selectionChanged', () => {
        const ids = g.model.selection && g.model.selection.selectedIds ? g.model.selection.selectedIds() : [];
        if (edit) { edit.select(ids); }
        ctx.emit('select', { panel: panel.id, dataset: ds.name, keys: ids });
      });
    }
    count();
    if (f) { f.stop(); }
    f = page.version ? t.follow(page.version, keysOf(), !!ref.top, apply, () => { f = null; if (!gone) { read(); } }) : null;
    if (!timer && typeof t.schema === 'function') {                 // the paused and stale banners follow the table's schema
      const every = () => { timer = ctx.doc.defaultView.setTimeout(() => { schemaOf().then((s) => { if (!gone && s) { showSchema(s); } }).then(() => { if (!gone) { every(); } }); }, ctx.schemaEvery); };
      every();
    }
  });
  return read().then(() => ({
    get component() { return g; },
    change() { /* a live dataset is changed by its table's patches, never by the view's rows op */ },
    destroy() { gone = true; if (timer) { ctx.doc.defaultView.clearTimeout(timer); } if (f) { f.stop(); } if (edit) { edit.destroy(); } if (notices) { notices.destroy(); } if (g) { g.destroy(); } note.remove(); lane.remove(); editLane.remove(); }
  }));
});

defineKind('grid', grid);
defineKind('live', live);
defineKind('tree', tree);
defineKind('chart', chart);
