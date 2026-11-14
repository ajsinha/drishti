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
/* What the grid's keys, menus, toolbar and function keys do, the same in every layout. The keys: the engine's navigator (arrows, Home,
   End, Page keys, Shift for a range, Space, Enter, Ctrl+C, Ctrl+A, Escape, E) and the view's own: F2 Explain, Ctrl+Shift+C copy with
   headings, Shift+F10 or the menu key for the context menu, the terminal's F3 to F10. The menus: the column menu (sort, top N, filters,
   aggregation, hide, autosize, pin, Explain the total), the context menu (Explain, copy, export, chart the range, the rows behind a cell,
   group, pin) and the toolbar's (formatting, export, view). */
import { memberText } from './drishti_pivot.js';
import { getValue } from './drishti_columns.js';
import { VALUE_FUNCS } from './drishti_wells.js';
import { aggFunc } from './drishti_aggregate.js';
import { XLSX_TYPE } from './drishti_export.js';
import { columnMenuItems, cellMenuItems, FUNCTION_KEYS, parseCondition, pivotMeasures } from './drishti_grid-ui.js';
import { LAYOUTS, DENSITIES } from './drishti_grid-model-base.js';
import { openDialog, h } from '../../drishti-core/src/drishti_index.js';
import { editorOf } from './drishti_grid-edit.js';

/**
 * A key on the grid: the view's own keys first, then the engine's navigator; the focus scrolled into view.
 * @param {Object} v the Grid
 * @param {KeyboardEvent} e
 * @returns {void}
 */
export function onKey(v, e) {
  const m = v.model, k = e.key, mod = e.ctrlKey || e.metaKey;
  if (e.target !== v.body.scroller && e.target.closest && e.target.closest('.dg-editor')) { return; }
  if (e.altKey && (k === 'ArrowUp' || k === 'ArrowDown') && m.pivotMode && v.wells.hostKey(e)) { return; }   // a hierarchy's level
  if (editKey(v, e)) { return; }
  if (mod && e.shiftKey && (k === 'c' || k === 'C')) { e.preventDefault(); runAction(v, 'copy-headers'); return; }
  if (k === 'F2') { e.preventDefault(); v.explainFocused(); return; }
  if ((e.shiftKey && k === 'F10') || k === 'ContextMenu') {
    e.preventDefault();
    const f = m.navigator.focus;
    if (!f) { return; }
    const cell = v.doc.getElementById(v.uid + '-r' + f.row + '-c' + f.col), r = cell ? cell.getBoundingClientRect() : { left: 40, bottom: 40 };
    v.openCellMenu(f.row, f.col, { x: r.left + 8, y: r.bottom });
    return;
  }
  const fk = m.layout === 'terminal' ? FUNCTION_KEYS.find((x) => x.key === k && x.key !== 'F2') : null;
  if (fk) { e.preventDefault(); runAction(v, fk.action); return; }
  if (k === 'Escape' && v.exOpen) { v.closeExplain(); }
  const res = m.key(e);
  if (!res.handled) { return; }
  e.preventDefault();
  const f = m.navigator.focus;
  switch (res.action) {
    case 'copy': copied(v, res.copy, false); break;
    case 'explain': v.explainFocused(); break;
    case 'open': if (f) { m.toggleRow(f.row); v.announceRow(f.row); } break;
    case 'expand': case 'collapse': if (f) { v.announceRow(f.row); } break;
    case 'expand-all': runAction(v, 'expand-all'); break;
    case 'collapse-all': runAction(v, 'collapse-all'); break;
    default: break;
  }
  v.afterFocus(true);
}

/** @private the editing keys: undo and redo, Delete, and on an editable cell Enter, F2, Space (a checkbox) and typing */
function editKey(v, e) {
  const m = v.model, k = e.key, mod = e.ctrlKey || e.metaKey, f = m.navigator.focus;
  if (mod && !e.altKey && (k === 'z' || k === 'Z')) { e.preventDefault(); if (e.shiftKey) { v.redo(); } else { v.undo(); } return true; }
  if (mod && !e.altKey && (k === 'y' || k === 'Y')) { e.preventDefault(); v.redo(); return true; }
  if (!f || mod || e.altKey) { return false; }
  if ((k === 'Delete' || k === 'Backspace') && anyEditable(v)) { e.preventDefault(); runAction(v, 'clear-cells'); return true; }
  if (!m.editing.canEdit(f.row, f.col)) { return false; }
  const ed = editorOf(m.shownColumns()[f.col]);
  if ((k === 'Enter' && !e.shiftKey) || k === 'F2' || (k === ' ' && ed === 'checkbox')) { e.preventDefault(); v.startEditing(f.row, f.col); return true; }
  if (k.length === 1 && k !== ' ' && ed !== 'checkbox') { e.preventDefault(); v.startEditing(f.row, f.col, { initial: k }); return true; }
  return false;
}

/** @private whether any shown column may be edited */
function anyEditable(v) { return !v.model.pivotMode && v.model.shownColumns().some((c) => c.editable); }

/** @private copied text: the clipboard, the note, the event */
function copied(v, text, headers) {
  v.lastCopy = text;
  const win = v.doc.defaultView;
  try { if (win.navigator.clipboard) { win.navigator.clipboard.writeText(text).catch(() => {}); } } catch (err) { /* the text stays in lastCopy */ }
  const lines = text ? text.split('\n').length : 0;
  v.say('Copied ' + lines + (lines === 1 ? ' line' : ' lines') + (headers ? ' with headers' : '') + ' as tab-separated text');
  v.emitView('copied', { text: text, headers: !!headers });
}

/** @private a file for the person: the host's download hook, or a link clicked */
function download(v, name, data, type) {
  const win = v.doc.defaultView, blob = new win.Blob([data], { type: type });
  v.lastExport = { name: name, type: type, size: blob.size };
  if (v.o.download) { v.o.download(name, blob); return; }
  const a = h(v.doc, 'a', { attrs: { href: win.URL.createObjectURL(blob), download: name } });
  v.doc.body.appendChild(a);
  a.click();
  a.remove();
}

/**
 * Opens the column menu of a shown column.
 * @param {Object} v the Grid
 * @param {number} c
 * @param {Element} anchor
 * @returns {void}
 */
export function openColumnMenu(v, c, anchor) {
  const m = v.model, col = m.shownColumns()[c];
  if (!col) { return; }
  const piv = m.pivotMode, vi = piv ? valueIndex(v, col.measure) : -1;
  const aggs = piv ? (vi >= 0 ? v.activeWells().funcs() : null) : v.grouped() && col.type === 'number' ? VALUE_FUNCS.map((f) => ({ name: f, label: aggFunc(f).label })) : null;
  const items = columnMenuItems({ header: col.headerPath ? col.headerPath.filter(Boolean).join(' · ') : col.header, pivot: piv, sort: v.sortOf({ col: col }),
    numeric: col.type === 'number', additive: col.additive, aggs: aggs, aggFunc: piv ? (vi >= 0 ? v.activeWells().state.values[vi].aggFunc : null) : col.aggFunc,
    pinned: col.pinned, pinTotals: v.ui.pinTotals, explain: !!v.o.explain, rowHeader: !!col.rowHeader, list: col.type === 'list' });
  if (piv && col.additive === false) { items.forEach((it) => { if (it.id === 'agg:server') { it.label = col.measure; } }); }
  v.menu.open(items, { anchor: anchor, label: 'Column menu: ' + col.header, theme: v.host, run: (id, val) => columnAction(v, c, id, val, anchor) });
  v.emitView('menuOpened', { menu: 'column', column: col.id });
}

/** @private the place of a pivot value by its label */
function valueIndex(v, label) { return v.activeWells().toView().values.map((x) => x.as).indexOf(label); }

/** @private a column menu's item */
function columnAction(v, c, id, val, anchor) {
  const m = v.model, col = m.shownColumns()[c], piv = m.pivotMode;
  v.emitView('menuAction', { menu: 'column', action: id, column: col.id });
  const dims = piv && m.answer ? m.answer.rowDimensions : [];
  if (id === 'sort-desc' || id === 'sort-asc' || id === 'sort-clear') {
    if (!piv) { m.setSortModel(id === 'sort-clear' ? [] : [{ colId: col.id, sort: id === 'sort-asc' ? 'asc' : 'desc' }]); return; }
    const sort = {};
    if (id !== 'sort-clear') { dims.forEach((d) => { sort[d] = { by: col.rowHeader ? 'label' : col.measure, desc: id === 'sort-desc' }; }); }
    v.setPivotOptions({ sort: sort }, id === 'sort-clear' ? 'Order cleared' : 'Rows ordered by ' + (col.rowHeader ? 'name' : col.measure));
    return;
  }
  if (id === 'top-5' || id === 'top-10') {
    const n = id === 'top-5' ? 5 : 10;
    if (piv) { const top = {}; if (dims.length) { top[dims[dims.length - 1]] = { n: n, by: col.measure }; } v.setPivotOptions({ top: top }, 'Top ' + n + ' by ' + col.measure); return; }
    const xs = m.rowModel.filteredRows ? m.rowModel.filteredRows().map((r) => getValue(r, col)).filter((x) => typeof x === 'number').sort((a, b) => b - a) : [];
    if (xs.length) {
      m.setSortModel([{ colId: col.id, sort: 'desc' }]);
      m.setFilterModel(Object.assign({}, m.filterModel, { [col.id]: { type: 'number', op: 'gte', value: xs[Math.min(n, xs.length) - 1] } }));
      v.say('Top ' + n + ' by ' + col.header);
    }
    return;
  }
  if (id === 'value-filter') {
    if (piv) { v.wells.valueFilterMenu(-1, anchor); return; }
    v.menu.open([{ heading: 'Filter ' + col.header }, { id: 'cond', label: col.header, input: { placeholder: '> 400k' } }], { anchor: anchor, label: 'Value filter', theme: v.host, run: (x, text) => {
      const cnd = parseCondition(text);
      if (!cnd) { v.say('Write a condition such as > 400k or < 0'); return; }
      m.setFilterModel(Object.assign({}, m.filterModel, { [col.id]: { type: 'number', op: cnd.op, value: cnd.value } }));
    } });
    return;
  }
  if (id === 'member-filter') { memberMenu(v, col, anchor); return; }
  if (id === 'list-has' || id === 'list-any' || id === 'list-all') {              // a list column: contains, any of, all of (values separated by commas)
    const op = id === 'list-has' ? 'has' : id === 'list-any' ? 'anyOf' : 'allOf';
    v.menu.open([{ heading: col.header + (op === 'has' ? ' contains' : op === 'anyOf' ? ' has any of' : ' has all of') }, { id: 'cond', label: col.header, input: { placeholder: op === 'has' ? 'fx' : 'fx, rates' } }],
      { anchor: anchor, label: 'List filter', theme: v.host, run: (x, text) => {
        const vs = String(text || '').split(',').map((t) => t.trim()).filter(Boolean);
        if (!vs.length) { v.say('Write a value' + (op === 'has' ? '' : 's, separated by commas')); return; }
        m.setFilterModel(Object.assign({}, m.filterModel, { [col.id]: { type: 'list', op: op, values: op === 'has' ? vs.slice(0, 1) : vs } }));
      } });
    return;
  }
  if (id === 'clear-filter') { if (piv) { v.setPivotOptions({ valueFilters: [], top: {} }, 'Filters cleared'); } else { m.setFilterModel({}); m.setQuickFilter(''); v.say('Filters cleared'); } return; }
  if (id.indexOf('agg:') === 0) {
    const f = id.slice(4);
    if (piv) { const r = v.activeWells().setAggregation(valueIndex(v, col.measure), f); if (!r.ok) { v.say(r.why); } else { v.wellsChanged(col.measure + ': ' + v.activeWells().funcLabel(f)); } return; }
    col.aggFunc = f;
    m.setRowGroups(m.rowModel.groupBy ? m.rowModel.groupBy.slice() : []);
    return;
  }
  if (id === 'hide') { if (piv) { v.ui.hidden.add(col.id); v.refresh(); } else { m.columns.setVisible(col.id, false); } v.say('Hidden: Show all columns brings it back'); return; }
  if (id === 'show-all') { if (piv) { v.ui.hidden.clear(); v.refresh(); } else { m.columns.all().forEach((x) => { if (x.hidden) { m.columns.setVisible(x.id, true); } }); } return; }
  if (id === 'autosize') { v.autosize(c); return; }
  if (id === 'autosize-all') { v.autosize(-1); return; }
  if (id === 'pin-left' || id === 'pin-right' || id === 'unpin') { m.columns.pin(col.id, id === 'unpin' ? null : id === 'pin-left' ? 'left' : 'right'); return; }
  if (id === 'pin-totals') { runAction(v, 'pin-totals'); return; }
  if (id === 'explain-column') {
    let r = 0;
    if (piv) { const rows = m.answer ? m.answer.rows : []; r = rows.findIndex((x) => x.kind === 'grand'); if (r < 0) { r = rows.length - 1; } }
    else if (m.navigator.focus) { r = m.navigator.focus.row; }
    v.focusCell(r, c);
    v.explain(r, c);
  }
}

/** @private a grid column's member filter (a check per member, kept open) */
function memberMenu(v, col, anchor) {
  const m = v.model;
  const items = () => {
    const f = m.filterModel[col.id], ex = new Set(f && f.excluded ? f.excluded.map(String) : []);
    return [{ heading: 'Show ' + col.header }].concat(v.membersOf(col.id).slice(0, 200).map((x) => ({ id: 'member:' + x.text, label: x.text + (x.count !== undefined ? '  (' + x.count + ')' : ''), checked: !ex.has(x.text), keep: true })),
      [{ sep: true }, { id: 'all', label: 'Select all' }]);
  };
  v.menu.open(items, { anchor: anchor, label: 'Filter ' + col.header, theme: v.host, run: (id) => {
    const fm = Object.assign({}, m.filterModel);
    if (id === 'all') { delete fm[col.id]; m.setFilterModel(fm); return; }
    const t = id.slice(7), f = fm[col.id], ex = new Set(f && f.excluded ? f.excluded.map(String) : []);
    if (ex.has(t)) { ex.delete(t); } else { ex.add(t); }
    if (ex.size) { fm[col.id] = { type: 'set', excluded: Array.from(ex) }; } else { delete fm[col.id]; }
    m.setFilterModel(fm);
  } });
}

/**
 * Opens the context menu of a cell.
 * @param {Object} v the Grid
 * @param {number} r
 * @param {number} c
 * @param {{x: number, y: number}} at
 * @returns {void}
 */
export function openCellMenu(v, r, c, at) {
  const m = v.model, col = m.shownColumns()[c], x = m.rowAt(r);
  if (!col || !x) { return; }
  let cells = 0;
  m.selection.ranges.forEach((g) => { cells += (g.r2 - g.r1 + 1) * (g.c2 - g.c1 + 1); });
  const items = cellMenuItems({ explain: !!v.o.explain, pivot: m.pivotMode, cells: cells, drill: drillable(v, x), groupBy: groupField(v, col), pinned: col.pinned, pinTotals: v.ui.pinTotals });
  if (anyEditable(v)) {
    const hist = m.editing.history(), can = m.editing.canEdit(r, c);
    items.push({ sep: true }, { id: 'edit', label: 'Edit the cell', key: 'F2', icon: '✎', disabled: !can, why: can ? null : 'this cell cannot be edited' },
      { id: 'undo', label: 'Undo the last edit', key: 'Ctrl+Z', disabled: !hist.undo, why: hist.undo ? null : 'nothing to undo' },
      { id: 'redo', label: 'Redo', key: 'Ctrl+Y', disabled: !hist.redo, why: hist.redo ? null : 'nothing to redo' },
      { id: 'clear-cells', label: 'Clear ' + (cells > 1 ? 'the ' + cells + ' cells' : 'the cell'), key: 'Delete' });
  }
  v.menu.open(items, { x: at.x, y: at.y, label: 'Cell menu', theme: v.host, run: (id) => { v.emitView('menuAction', { menu: 'cell', action: id, row: r, column: col.id }); runAction(v, id, { row: r, col: c }); } });
  v.emitView('menuOpened', { menu: 'cell', row: r, column: col.id });
}

/** @private whether there are rows behind a row's cells */
function drillable(v, x) {
  if (x.kind === 'pivot') { return x.entry.kind !== 'header'; }
  return x.kind === 'group' || (!!v.o.drillThrough && x.kind === 'leaf');
}

/** @private the field a "group by" would use: a grid column that may group; a pivot's innermost column field */
function groupField(v, col) {
  const m = v.model;
  if (m.pivotMode) { const cols = v.activeWells().state.columns; return cols.length ? v.headerOf(cols[cols.length - 1]) : null; }
  const groups = m.rowModel.groupBy || m.rowModel.rowGroupCols || [];
  return col.enableRowGroup && groups.indexOf(col.id) < 0 ? col.header : null;
}

/**
 * The rows in the page behind a cell's context (a pivot cell's members, a group's path).
 * @param {Object} v the Grid
 * @param {Object} ctx GridModel.cellContext
 * @returns {Object[]}
 */
export function localDrill(v, ctx) {
  const m = v.model;
  if (!m.rowModel.filteredRows) { return []; }
  const byField = {};
  m.columns.all().forEach((c) => { byField[c.field] = c; });
  let want = {};
  if (ctx.kind === 'pivot') { want = ctx.coords || {}; }
  else if (ctx.kind === 'group') { (m.rowModel.groupBy || []).slice(0, (ctx.path || []).length).forEach((id, i) => { const c = m.columns.get(id); want[c.field] = memberText(ctx.path[i]); }); }
  else if (ctx.kind === 'leaf') { return [ctx.data]; }
  const keys = Object.keys(want).filter((k) => want[k] !== '*others*');
  return m.rowModel.filteredRows().filter((row) => keys.every((k) => memberText(byField[k] ? getValue(row, byField[k]) : row[k]) === want[k]));
}

/** @private the rows behind the focused cell, in a dialog holding a plain grid */
function drill(v, at) {
  const m = v.model, f = at || m.navigator.focus;
  if (!f) { return Promise.resolve(null); }
  const ctx = m.cellContext(f.row, f.col);
  if (!ctx) { return Promise.resolve(null); }
  return Promise.resolve(v.o.drillThrough ? v.o.drillThrough(ctx) : localDrill(v, ctx)).then((rows) => {
    rows = rows || [];
    v.emitView('drillThrough', { context: ctx, rows: rows.length });
    const doc = v.doc, place = h(doc, 'div', { cls: 'dg-dialog-grid' });
    const scope = ctx.coords ? Object.keys(ctx.coords).map((k) => k + ' = ' + ctx.coords[k]).join(', ') : ctx.path ? ctx.path.join(' › ') : ctx.header;
    const dlg = h(doc, 'dialog', { cls: 'dg-dialog', attrs: { 'aria-label': 'Rows behind ' + scope } }, [
      h(doc, 'div', { cls: 'dg-dialog-head' }, [h(doc, 'b', { text: rows.length + (rows.length === 1 ? ' row' : ' rows') }), ' behind ' + (scope || 'the cell'),
        h(doc, 'button', { cls: 'dg-btn dg-dialog-close', text: 'Close', attrs: { type: 'button' }, on: { click: () => dlg.close() } })]), place]);
    doc.body.appendChild(dlg);
    openDialog(dlg, { focus: '.dg-dialog-close' });
    const inner = new v.constructor(place, { columns: v.o.columns, rows: rows, layout: 'wells', density: m.density, title: 'Rows', checkboxes: false, getRowId: v.o.getRowId });
    dlg.addEventListener('close', () => { inner.destroy(); dlg.remove(); });
    v.lastDrill = { rows: rows.length, dialog: dlg };
    return dlg;
  });
}

/**
 * Runs one of the view's actions (a toolbar button, a function key, a menu item).
 * @param {Object} v the Grid
 * @param {string} id explain, copy, copy-headers, export-csv, export-xlsx, chart, drill, group, pin-totals, pin-left, unpin, expand-all,
 *   collapse-all, toggle-pivot, save-view, toggle-fields
 * @param {{row: number, col: number}} [at] the cell (default: the focus)
 * @returns {*}
 */
export function runAction(v, id, at) {
  const m = v.model, f = at || m.navigator.focus;
  const col = f ? m.shownColumns()[f.col] : null;
  switch (id) {
    case 'explain': return f ? v.explain(f.row, f.col) : v.say('Select a figure first');
    case 'copy': case 'copy-headers': return copied(v, m.copy({ headers: id === 'copy-headers' }), id === 'copy-headers');
    case 'export-csv': { const t = m.exportCsv(); download(v, (v.o.fileName || 'grid') + '.csv', t, 'text/csv'); v.emitView('exported', { format: 'csv', size: t.length }); return v.say('CSV of the grid as shown'); }
    case 'export-xlsx': { const b = m.exportXlsx({ sheetName: v.o.title || 'Grid' }); download(v, (v.o.fileName || 'grid') + '.xlsx', b, XLSX_TYPE); v.emitView('exported', { format: 'xlsx', size: b.length }); return v.say('Excel workbook of the grid as shown'); }
    case 'chart': {
      const ranges = m.selection.ranges.slice(), cols = m.shownColumns(), table = { columns: [], rows: [] };
      if (ranges.length) {
        const g = ranges[0];
        for (let c = g.c1; c <= g.c2; c++) { table.columns.push(cols[c].header); }
        for (let r = g.r1; r <= g.r2; r++) { const line = [m.cellText(r, 0)]; for (let c = g.c1; c <= g.c2; c++) { line.push(m.cellValue(r, c)); } table.rows.push(line); }
      }
      const detail = { ranges: ranges, table: table };
      v.emitView('chartRequested', detail);
      if (v.o.chart) { v.o.chart(detail); }
      return v.say(ranges.length ? 'Chart of ' + table.rows.length + ' rows × ' + table.columns.length + ' columns asked' : 'Select a range to chart');
    }
    case 'drill': return drill(v, at);
    case 'group': {
      if (m.pivotMode) { const w = v.activeWells(), cs = w.state.columns; if (cs.length) { const d = cs[cs.length - 1]; w.drop(d, 'rows'); v.wellsChanged('Rows grouped by ' + v.headerOf(d)); } return null; }
      if (col && col.enableRowGroup) { const g = (m.rowModel.groupBy || []).concat([col.id]); m.setRowGroups(g); v.say('Rows grouped by ' + col.header); }
      return null;
    }
    case 'pin-totals': v.ui.pinTotals = !v.ui.pinTotals; v.refresh(); return v.say((v.ui.pinTotals ? 'Totals pinned' : 'Totals unpinned'));
    case 'pin-left': case 'unpin': if (col && !m.pivotMode) { m.columns.pin(col.id, id === 'unpin' ? null : 'left'); } return null;
    case 'expand-all': case 'collapse-all': {
      const open = id === 'expand-all';
      if (m.pivotMode) { m.pivotState.expandToLevel('rows', open ? 99 : 1); m.pivotState.expandToLevel('columns', open ? 99 : 1); m.runPivot(); }
      else if (m.rowModel.expandToLevel) { m.rowModel.expandToLevel(open ? 99 : 0); }
      return v.say(open ? 'Everything expanded' : 'Everything collapsed');
    }
    case 'toggle-pivot': {
      const w = m.wells;
      if (!m.pivotMode && !w.state.values.length && !w.state.rows.length) {
        const all = m.columns.all(), dim = all.find((c) => c.enableRowGroup), val = all.find((c) => c.enableValue);
        if (dim) { w.state.rows.push(dim.id); }
        if (val) { w.state.values.push({ colId: val.id, aggFunc: val.aggFunc || 'sum' }); }
      }
      return v.setPivotMode(!m.pivotMode);
    }
    case 'save-view': { const st = v.getState(); v.emitView('viewSaved', { state: st }); return v.say('View saved'); }
    case 'toggle-fields': v.ui.fieldsOpen = !v.ui.fieldsOpen; return v.renderChrome();
    case 'edit': return f ? v.startEditing(f.row, f.col) : null;
    case 'undo': return v.undo();
    case 'redo': return v.redo();
    case 'clear-cells': {
      const g = m.selection.ranges[m.selection.ranges.length - 1];
      const range = g ? { r1: Math.min(g.r1, g.r2), c1: Math.min(g.c1, g.c2), r2: Math.max(g.r1, g.r2), c2: Math.max(g.c1, g.c2) } : f ? { r1: f.row, c1: f.col, r2: f.row, c2: f.col } : null;
      if (!range) { return null; }
      return m.editing.clear(range).then((res) => { v.say(res.transaction ? 'Cleared ' + res.transaction.edits.length + (res.transaction.edits.length === 1 ? ' cell' : ' cells') : res.problems.length ? 'Not cleared: ' + res.problems[0].message : 'Nothing to clear'); return res; }, (e2) => v.say('Not cleared: ' + e2.message));
    }
    default: throw new Error('no grid action ' + id);
  }
}

/**
 * A toolbar or function-key button: a menu (formatting, export, view) or an action.
 * @param {Object} v the Grid
 * @param {string} act
 * @param {Element} btn
 * @returns {void}
 */
export function toolbarAction(v, act, btn) {
  const m = v.model;
  if (act === 'menu-format') {
    const items = () => {
      const out = [{ heading: 'Conditional formatting' }];
      targets(v).forEach((t) => {
        const has = (type) => v.formats.some((r) => r.type === type && (r.column === t.column || (t.measure && r.measure === t.measure)));
        out.push({ id: 'cf:colourScale:' + t.key, label: 'Colour scale on ' + t.label, checked: has('colourScale'), keep: true }, { id: 'cf:dataBar:' + t.key, label: 'Data bars on ' + t.label, checked: has('dataBar'), keep: true });
      });
      out.push({ sep: true }, { id: 'cf-clear', label: 'Clear all formatting' });
      return out;
    };
    v.menu.open(items, { anchor: btn, label: 'Formatting', theme: v.host, run: (id) => {
      if (id === 'cf-clear') { v.setConditionalFormats([]); return; }
      const [, type, key] = id.split(':'), t = targets(v).find((x) => x.key === key);
      const match = (r) => r.type === type && (r.column === t.column || (t.measure && r.measure === t.measure));
      v.setConditionalFormats(v.formats.some(match) ? v.formats.filter((r) => !match(r)) : v.formats.concat([Object.assign({ type: type }, t.measure ? { measure: t.measure } : { column: t.column })]));
    } });
    return;
  }
  if (act === 'menu-export') {
    v.menu.open([{ id: 'export-csv', label: 'CSV of the grid as shown', icon: '⤓' }, { id: 'export-xlsx', label: 'Excel: values and outline', key: 'F8' },
      { sep: true }, { id: 'copy-headers', label: 'Copy the selection with headers', key: 'Ctrl+Shift+C' }, { id: 'save-view', label: 'Save the view', key: 'F10' }],
    { anchor: btn, label: 'Export', theme: v.host, run: (id) => runAction(v, id) });
    return;
  }
  if (act === 'menu-view') {
    const items = [{ heading: 'Layout' }].concat(LAYOUTS.map((l) => ({ id: 'layout:' + l, label: { wells: 'Ribbon wells', panel: 'Tool panel', terminal: 'Terminal' }[l], checked: m.layout === l })),
      [{ heading: 'Density' }], Object.keys(DENSITIES).map((d) => ({ id: 'density:' + d, label: d.charAt(0).toUpperCase() + d.slice(1), checked: m.density === d })),
      [{ sep: true }, { id: 'show-all', label: 'Show all columns' }, { id: 'autosize-all', label: 'Autosize all columns' }]);
    v.menu.open(items, { anchor: btn, label: 'View', theme: v.host, run: (id) => {
      if (id.indexOf('layout:') === 0) { v.setLayout(id.slice(7)); }
      else if (id.indexOf('density:') === 0) { v.setDensity(id.slice(8)); }
      else if (id === 'autosize-all') { v.autosize(-1); }
      else if (id === 'show-all') { v.ui.hidden.clear(); m.columns.all().forEach((x) => { if (x.hidden) { m.columns.setVisible(x.id, true); } }); v.refresh(); }
    } });
    return;
  }
  runAction(v, act);
}

/** @private what may be formatted: a grid's number columns, a pivot's measures */
function targets(v) {
  const m = v.model;
  if (m.pivotMode) { return pivotMeasures(m.shownColumns()).map((x, i) => ({ key: 'm' + i, label: x, measure: x })); }
  return m.columns.visible().filter((c) => c.type === 'number').map((c) => ({ key: c.id, label: c.header, column: c.id }));
}
