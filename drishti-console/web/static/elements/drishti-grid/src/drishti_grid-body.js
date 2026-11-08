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
/* The grid's body as drawn: one scroller with role grid; the heading rows stuck to its top (a pivot's member groups spanning their
   columns, collapsible); the rows the viewport says are in view (virtual rows and columns: a million rows draw the forty on screen);
   pinned columns stuck left and right inside each row, so a row is one element a screen reader reads as one. Cells carry their kind
   (total, ◆ non-additive total, pending server figure, loading block), the range, the focus, conditional formats and the flash of a
   live change. Pointer work: click and Shift+click, drag a range, the context menu, header sort, the column menu, a group's toggle,
   resize and autosize by the handle, the row checkboxes. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { GridPointer } from './drishti_grid-pointer.js';
import { DENSITIES } from './drishti_grid-model-base.js';
import { getValue, formatValue } from './drishti_columns.js';
import { headerSpans, pivotColumnLabels, markGroups, isTotalCell, nonAdditiveNote, cfBackground, figureRange, ruleFor, cellLook } from './drishti_grid-ui.js';

const CF_SCAN = 100000;

/**
 * The drawn grid: headings, rows, cells and their pointer work, for a view (drishti_grid-view.js).
 */
/** @private the styles a host's decoration may set on a cell (a conditional format's colours), through the CSSOM */
const DECO_STYLES = ['color', 'background-color', 'font-weight', 'font-style'];

/** @private class names a host or the server gave, kept to plain words (no markup, no selectors) */
function safeClasses(text) { return String(text).split(/\s+/).filter((t) => /^[A-Za-z][\w-]*$/.test(t)).join(' '); }

export class GridBody extends GridPointer {
  /**
   * @param {Object} view the Grid (drishti_grid-view.js): its model, its display columns, its menus and its Explain
   */
  constructor(view) {
    super();
    this.view = view;
    this.model = view.model;
    const doc = this.doc = view.doc;
    this.uid = view.uid;
    this.scroller = h(doc, 'div', { cls: 'dg-scroll', attrs: { role: 'grid', tabindex: '0', 'aria-multiselectable': 'true', 'aria-label': view.o.label || 'Grid', id: this.uid + '-grid' } });
    this.canvas = h(doc, 'div', { cls: 'dg-canvas', attrs: { role: 'presentation' } });
    this.head = h(doc, 'div', { cls: 'dg-head', attrs: { role: 'rowgroup' } });
    this.area = h(doc, 'div', { cls: 'dg-area', attrs: { role: 'presentation' } });
    this.rows = h(doc, 'div', { cls: 'dg-rows', attrs: { role: 'rowgroup' } });
    this.empty = h(doc, 'div', { cls: 'dg-empty', attrs: { hidden: true } });
    this.area.appendChild(this.rows);
    this.canvas.append(this.head, this.area);
    this.scroller.append(this.canvas, this.empty);
    this.cfCache = new Map();
    this.version = 0;
    this.drag = null;
    this.frame = 0;
    this.listen();
  }

  /** @private pointer and scroll work, delegated */
  listen() {
    const s = this.scroller, win = this.doc.defaultView;
    s.addEventListener('scroll', () => this.schedule());
    s.addEventListener('mousedown', (e) => this.down(e));
    s.addEventListener('mouseover', (e) => this.over(e));
    s.addEventListener('mouseleave', () => this.view.notes.hideTip());
    s.addEventListener('click', (e) => this.click(e));
    s.addEventListener('dblclick', (e) => this.dbl(e));
    s.addEventListener('contextmenu', (e) => this.context(e));
    this.onUp = () => { if (this.drag) { this.drag = null; this.view.afterRange(); } };
    win.addEventListener('mouseup', this.onUp);
  }

  /**
   * Draws again on the next frame (scrolling, a change of the model).
   * @returns {void}
   */
  schedule() {
    if (this.frame) { return; }
    const win = this.doc.defaultView;
    this.frame = win.requestAnimationFrame(() => { this.frame = 0; this.render(); });
  }

  /**
   * The data changed: conditional-format ranges are measured again.
   * @returns {void}
   */
  invalidate() { this.version++; this.cfCache.clear(); this.spans = null; }

  /** @private the cell (r, display entry) under an event */
  cellOf(e) {
    const c = e.target.closest ? e.target.closest('.dg-cell') : null;
    if (!c || c.dataset.pinned !== undefined || !this.scroller.contains(c)) { return null; }
    return { el: c, r: +c.dataset.r, c: +c.dataset.c };
  }

  // ---- drawing ---------------------------------------------------------------------------------------------------------------------------
  /** @private the heading labels of the shown columns, by level */
  labels(display) {
    const m = this.model;
    if (m.pivotMode) {
      const a = m.answer, L = a ? a.columnDimensions.length : 0, byKey = {};
      (a ? a.columns : []).forEach((e) => { byKey[e.key] = e; });
      const entries = display.map((d) => (d.col.rowHeader ? null : byKey[d.col.colKey] || null));
      const labels = display.map((d, i) => (d.col.rowHeader ? new Array(L).fill('').concat([d.col.header]) : pivotColumnLabels(d.col, entries[i], L)));
      return { labels: labels, entries: entries };
    }
    const grouped = display.some((d) => d.col.group);
    return { labels: display.map((d) => (grouped ? [d.col.group || '', d.col.header] : [d.col.header])), entries: null };
  }

  /** @private heading spans per pane (they never cross a pane) */
  paneSpans(disp) {
    const all = disp.all, lab = this.labels(all);
    const parts = { left: [], center: [], right: [] };
    all.forEach((d, i) => parts[d.pane].push(i));
    const out = { left: null, center: null, right: null };
    Object.keys(parts).forEach((p) => {
      const idx = parts[p];
      const sp = headerSpans(idx.map((i) => lab.labels[i]));
      if (lab.entries) { markGroups(sp, idx.map((i) => lab.entries[i])); }
      out[p] = sp.map((row) => row.map((s) => Object.assign(s, { start: all[idx[s.start]].i, end: all[idx[s.end]].i })));
    });
    const levels = lab.labels.reduce((n, l) => Math.max(n, l.length), 1);
    this.spans = [];
    for (let lv = 0; lv < levels; lv++) { this.spans.push([].concat(out.left[lv] || [], out.center[lv] || [], out.right[lv] || [])); }
    return { out: out, levels: levels };
  }

  /**
   * @private the option fitRows: the host as tall as its rows (up to fitRows of them, then it scrolls), the chrome kept; a small table
   * on a screen takes the room it needs and no more
   */
  fit(headH, rh, W) {
    const host = this.view.host, sc = this.scroller;
    const n = Math.max(1, Math.min(this.model.rowCount(), this.view.o.fitRows));
    const bar = W > sc.clientWidth + 1 ? sc.offsetHeight - sc.clientHeight || 12 : 0;
    const want = Math.ceil(host.offsetHeight - sc.clientHeight + headH + n * rh + bar + 2);
    if (want > 0 && Math.abs(host.offsetHeight - want) > 1) { host.style.height = want + 'px'; }
  }

  /**
   * Draws the headings and the rows in view.
   * @returns {void}
   */
  render() {
    const m = this.model, v = this.view, vp = m.viewport, doc = this.doc;
    const disp = v.displayColumns();
    const win = doc.defaultView, touch = !!(win && win.matchMedia && win.matchMedia('(pointer: coarse)').matches);
    // rows that open and close (a tree, groups, a pivot) are 45 px tall (44 within the border) on a touch screen (a phone, a tablet), so the twisty is a target a
    // finger can hit (its 44 px width is the sheet's); every other grid keeps its density's height
    const twisty = touch && !!(m.pivotMode || m.rowModel.treeData || (m.rowModel.groupBy && m.rowModel.groupBy.length));
    const want = twisty ? Math.max(45, DENSITIES[m.density] || 28) : DENSITIES[m.density] || vp.rowHeight;
    if (vp.rowHeight !== want) { vp.rowHeight = want; }
    const rh = vp.rowHeight, hrh = touch ? Math.max(44, rh + 4) : rh + 4;   // a heading is a target a finger can hit on a touch screen (44 px)
    const sp = this.paneSpans(disp);
    const pinned = v.o.pinnedTopRows || [];
    const headH = sp.levels * hrh + pinned.length * rh;
    const W = disp.leftW + disp.centerW + disp.rightW;
    if (v.o.fitRows) { this.fit(headH, rh, W); }
    vp.resize(Math.max(1, this.scroller.clientWidth), Math.max(rh, this.scroller.clientHeight - headH));
    vp.setRowCount(m.rowCount());
    vp.scrollTo(this.scroller.scrollTop, this.scroller.scrollLeft);
    this.canvas.style.width = W + 'px';
    this.area.style.height = vp.scrollHeight() + 'px';
    this.head.style.height = headH + 'px';
    const rows = vp.rows();
    const cr = vp.columns(disp.offs, disp.leftW + disp.rightW);
    this.window = { rows: rows, cols: cr };
    const centerShown = disp.center.slice(Math.max(0, cr.first), cr.last + 1);
    const lead = disp.center.length && cr.first > 0 ? disp.offs[cr.first] : 0;
    const trail = disp.center.length ? disp.centerW - disp.offs[Math.min(disp.center.length, cr.last + 1)] : 0;
    // headings
    this.head.textContent = '';
    for (let lv = 0; lv < sp.levels; lv++) {
      const leaf = lv === sp.levels - 1;
      const row = h(doc, 'div', { cls: 'dg-hrow' + (leaf ? ' dg-hleaf' : ''), attrs: { role: 'row', 'aria-rowindex': String(lv + 1) }, style: { height: hrh + 'px' } });
      this.headPane(row, sp.out.left[lv] || [], disp, 'left', lv, leaf);
      if (lead) { row.appendChild(h(doc, 'div', { cls: 'dg-gap', attrs: { role: 'presentation' }, style: { width: lead + 'px' } })); }
      const firstI = centerShown.length ? centerShown[0].i : -1, lastI = centerShown.length ? centerShown[centerShown.length - 1].i : -2;
      this.headPane(row, (sp.out.center[lv] || []).filter((s) => s.end >= firstI && s.start <= lastI).map((s) => Object.assign({}, s, { start: Math.max(s.start, firstI), end: Math.min(s.end, lastI) })), disp, 'center', lv, leaf);
      if (trail > 0) { row.appendChild(h(doc, 'div', { cls: 'dg-gap', attrs: { role: 'presentation' }, style: { width: trail + 'px' } })); }
      this.headPane(row, sp.out.right[lv] || [], disp, 'right', lv, leaf);
      this.head.appendChild(row);
    }
    // rows pinned under the headings (pinnedTopRows: a spreadsheet's header rows), kept in view as the rows scroll
    pinned.forEach((data, k) => this.head.appendChild(this.pinnedRowEl(data, k, disp, centerShown, lead, trail, rh)));
    // rows
    this.rows.textContent = '';
    this.rows.style.top = rows.offset + 'px';
    this.rows.style.width = W + 'px';
    const focus = m.navigator.focus;
    const flash = v.flash;
    for (let r = rows.first; r <= rows.last; r++) {
      const x = m.rowAt(r);
      if (!x) { continue; }
      this.rows.appendChild(this.rowEl(r, x, disp, centerShown, lead, trail, focus, flash, sp.levels));
    }
    flash.settle();
    if (flash.active()) { this.view.doc.defaultView.setTimeout(() => this.schedule(), 120); }
    // the grid's names for a reader
    const s = this.scroller;
    s.setAttribute('role', m.pivotMode || (m.rowModel.groupBy && m.rowModel.groupBy.length) || m.rowModel.treeData ? 'treegrid' : 'grid');
    s.setAttribute('aria-rowcount', String(m.rowCount() + sp.levels));
    s.setAttribute('aria-colcount', String(m.shownColumns().length));
    const fid = focus ? this.uid + '-r' + focus.row + '-c' + focus.col : null;
    if (fid && doc.getElementById(fid)) { s.setAttribute('aria-activedescendant', fid); } else { s.removeAttribute('aria-activedescendant'); }
    const why = v.emptyText();
    this.empty.hidden = !why;
    this.empty.textContent = why || '';
    if (v.editor) { v.editor.place(); }
  }

  /** @private one pane's heading cells at a level */
  headPane(row, spans, disp, pane, lv, leaf) {
    const doc = this.doc, m = this.model;
    const all = disp.all, byIndex = disp.byIndex;
    let edge = 0;
    const paneCols = pane === 'left' ? disp.left : pane === 'right' ? disp.right : null;
    const offsetOf = (i) => { let o = 0; for (const d of paneCols) { if (d.i === i) { return o; } o += d.width; } return o; };
    const rightOf = (i) => { let o = 0; for (let k = paneCols.length - 1; k >= 0; k--) { o += paneCols[k].width; if (paneCols[k].i === i) { return o - paneCols[k].width; } } return o; };
    spans.forEach((s) => {
      let w = 0;
      for (let i = s.start; i <= s.end; i++) { if (byIndex[i]) { w += byIndex[i].width; } }
      const d = byIndex[s.start];
      const style = { width: w + 'px' };
      if (pane === 'left') { style.left = offsetOf(s.start) + 'px'; }
      if (pane === 'right') { style.right = rightOf(s.end) + 'px'; }
      const cls = 'dg-hcell dg-' + pane + (leaf ? ' dg-leaf' : ' dg-hgroup') + (d && d.col.type === 'number' ? ' dg-num' : '') + (pane === 'right' && s.start === disp.right[0].i ? ' dg-edge' : '');
      const cell = h(doc, 'div', { cls: cls, attrs: { role: 'columnheader', 'aria-colindex': String(s.start + 1), 'aria-colspan': s.end > s.start ? String(s.end - s.start + 1) : null }, data: { c: String(s.start) }, style: style });
      if (leaf && d) {
        const col = d.col, sort = this.view.sortOf(d);
        if (sort) { cell.setAttribute('aria-sort', sort === 'asc' ? 'ascending' : 'descending'); }
        if (col.rowHeader || (!m.pivotMode && s.start === all[0].i)) { this.allCheck(cell); }
        cell.appendChild(h(doc, 'span', { cls: 'dg-hlabel', text: s.label }));
        if (col.additive === false && col.type === 'number') { cell.appendChild(h(doc, 'span', { cls: 'dg-na', text: '◆', attrs: { title: 'Not additive: every total is the server\'s own figure for its group', 'aria-label': 'not additive' } })); }
        if (sort) { cell.appendChild(h(doc, 'span', { cls: 'dg-sort', text: sort === 'asc' ? '▲' : '▼', attrs: { 'aria-hidden': 'true' } })); }
        cell.appendChild(h(doc, 'button', { cls: 'dg-hmenu', text: '☰', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Column menu: ' + s.label }, data: { c: String(s.start) } }));
        const rz = h(doc, 'div', { cls: 'dg-resize', attrs: { role: 'presentation', title: 'Drag to resize; double-click to fit' }, data: { c: String(s.start) } });
        rz.addEventListener('pointerdown', (e) => this.resizeStart(e, d));
        cell.appendChild(rz);
      } else {
        if (s.group) {
          cell.appendChild(h(doc, 'button', { cls: 'dg-ctoggle', text: s.group.open ? '▾' : '▸', attrs: { type: 'button', tabindex: '-1', 'aria-expanded': String(s.group.open), 'aria-label': (s.group.open ? 'Collapse ' : 'Expand ') + s.label }, data: { level: String(lv), key: s.key } }));
        }
        cell.appendChild(h(doc, 'span', { cls: 'dg-hlabel', text: s.label }));
      }
      edge += w;
      row.appendChild(cell);
    });
    return edge;
  }

  /** @private the select-all checkbox in the first heading */
  allCheck(cell) {
    if (!this.view.checkboxes()) { return; }
    const m = this.model;
    const n = m.selection.selectedIds().length;
    const box = h(this.doc, 'input', { cls: 'dg-check', attrs: { type: 'checkbox', tabindex: '-1', 'aria-label': 'Select all rows' }, data: { all: '1', r: '-1' } });
    let all = m.rowModel.filteredCount ? m.rowModel.filteredCount() : m.rowCount();
    if (m.pivotMode) { all = 0; for (let i = 0; i < m.rowCount(); i++) { if (m.idAt(i) !== null) { all++; } } }
    box.checked = n > 0 && n >= all;
    box.indeterminate = n > 0 && n < all;
    cell.appendChild(box);
  }

  /** @private a pinned top row: its cells drawn from its data by each column's format, class and renderer (not part of the rows) */
  pinnedRowEl(data, k, disp, centerShown, lead, trail, rh) {
    const doc = this.doc;
    const row = h(doc, 'div', { cls: 'dg-row dg-pinned-top', attrs: { role: 'row' }, data: { pinned: String(k) }, style: { height: rh + 'px' } });
    const cellOf = (d) => {
      const col = d.col, value = getValue(data, col), text = formatValue(value, col, data);
      const style = { width: d.width + 'px' };
      if (d.pane === 'left') { style.left = disp.leftOffset[d.i] + 'px'; }
      if (d.pane === 'right') { style.right = disp.rightOffset[d.i] + 'px'; }
      const extra = col.cellClass ? col.cellClass(value, data) : null;
      const cell = h(doc, 'div', { cls: 'dg-cell dg-' + d.pane + (col.type === 'number' ? ' dg-num' : '') + (extra ? ' ' + extra : ''),
        attrs: { role: 'gridcell', 'aria-colindex': String(d.i + 1) }, data: { pinned: String(k), c: String(d.i) }, style: style });
      const drawn = col.cellRenderer ? col.cellRenderer({ value: value, text: text, row: data, doc: doc, pinned: k }) : null;
      cell.appendChild(drawn !== null && drawn !== undefined && drawn !== '' ? (typeof drawn === 'string' ? h(doc, 'span', { cls: 'dg-text', text: drawn }) : drawn) : h(doc, 'span', { cls: 'dg-text', text: text }));
      return cell;
    };
    disp.left.forEach((d) => row.appendChild(cellOf(d)));
    if (lead) { row.appendChild(h(doc, 'div', { cls: 'dg-gap', attrs: { role: 'presentation' }, style: { width: lead + 'px' } })); }
    centerShown.forEach((d) => row.appendChild(cellOf(d)));
    if (trail > 0) { row.appendChild(h(doc, 'div', { cls: 'dg-gap', attrs: { role: 'presentation' }, style: { width: trail + 'px' } })); }
    disp.right.forEach((d) => row.appendChild(cellOf(d)));
    row.addEventListener('pointerdown', (e) => {
      const c = e.target.closest('.dg-cell');
      if (c) { this.model.live.emit('pinnedCellClicked', { pinned: k, col: +c.dataset.c, columnId: this.model.shownColumns()[+c.dataset.c].id, data: data }); }
    });
    return row;
  }

  /** @private a row's element */
  rowEl(r, x, disp, centerShown, lead, trail, focus, flash, levels) {
    const doc = this.doc, m = this.model;
    const e = x.kind === 'pivot' ? x.entry : null;
    const expandable = e ? !!e.expandable : x.kind === 'group';
    const expanded = e ? !!e.expanded : !!x.expanded;
    const kind = e ? e.kind : x.kind;
    const id = m.idAt(r);
    const selected = id !== null && m.selection.isSelected(id);
    const cls = 'dg-row dg-k-' + kind + (expandable ? ' dg-parent' : '') + (selected ? ' dg-selected' : '') + (r % 2 ? ' dg-odd' : '');
    const row = h(doc, 'div', { cls: cls, attrs: { role: 'row', 'aria-rowindex': String(r + levels + 1), 'aria-selected': id !== null ? String(selected) : null,
      'aria-level': m.pivotMode || x.kind === 'group' || x.tree || x.depth ? String((x.depth || 0) + 1) : null, 'aria-expanded': expandable ? String(expanded) : null },
    style: { height: m.viewport.rowHeight + 'px' } });
    const o = this.view.o;
    if (x.row && (x.kind === 'leaf' || x.tree) && (o.getRowClass || o.getRowData)) {
      // a host's class and data-* attributes for a leaf's row (a row it highlights; the key a row stands for)
      const rc = o.getRowClass ? o.getRowClass(x.row) : null, rd = o.getRowData ? o.getRowData(x.row) : null;
      if (rc) { row.className += ' ' + rc; }
      if (rd) { Object.keys(rd).forEach((k) => { if (rd[k] !== null && rd[k] !== undefined) { row.dataset[k] = String(rd[k]); } }); }
    }
    const rowKey = e ? 'p:' + e.key : x.kind === 'group' ? 'g:' + (x.path || x.keys || []).join('\u001f') : 'l:' + (x.id !== undefined ? x.id : r);
    const first = disp.all[0];
    const add = (d) => row.appendChild(this.cellEl(r, x, d, { kind: kind, expandable: expandable, expanded: expanded, first: d === first, focus: focus, flash: flash, rowKey: rowKey, selected: selected }, disp));
    disp.left.forEach(add);
    if (lead) { row.appendChild(h(doc, 'div', { cls: 'dg-gap', attrs: { role: 'presentation' }, style: { width: lead + 'px' } })); }
    centerShown.forEach(add);
    if (trail > 0) { row.appendChild(h(doc, 'div', { cls: 'dg-gap', attrs: { role: 'presentation' }, style: { width: trail + 'px' } })); }
    disp.right.forEach(add);
    return row;
  }

  /** @private a cell's element */
  cellEl(r, x, d, k, disp) {
    const doc = this.doc, m = this.model, col = d.col, c = d.i;
    const style = { width: d.width + 'px' };
    if (d.pane === 'left') { style.left = disp.leftOffset[c] + 'px'; }
    if (d.pane === 'right') { style.right = disp.rightOffset[c] + 'px'; }
    const loading = x.kind === 'loading';
    const text = loading ? '' : m.cellText(r, c);
    const value = loading ? undefined : m.cellValue(r, c);
    const num = col.type === 'number' && !col.rowHeader;
    const total = num && isTotalCell(k.kind, m.pivotMode ? col.kind : null, k.expandable);
    const pending = text === '…' && !k.first;
    let cls = 'dg-cell dg-' + d.pane + (num ? ' dg-num' : '') + (typeof value === 'number' && value < 0 ? ' dg-neg' : '') + (total ? ' dg-total' : '');
    if (total && col.additive === false && !pending) { cls += ' dg-nadd'; }
    if (pending) { cls += ' dg-pending'; }
    if (loading) { cls += ' dg-loading'; }
    if (d.pane === 'right' && disp.right[0] === d) { cls += ' dg-edge'; }
    if (m.selection.inRange(r, c)) { cls += ' dg-in-range'; }
    let note = null;
    if (x.kind === 'leaf' && col.editable) {
      const mark = m.editing.markOf(x.id, col.id);
      if (m.editing.canEdit(r, c)) { cls += ' dg-editable'; }
      if (mark) { cls += ' dg-' + mark; note = mark === 'proposed' ? 'Waiting for a second person to approve' : 'Changed here'; }
    }
    let deco = null;
    if (x.kind === 'pivot' && !col.rowHeader && m.answer) {
      const pc = m.answer.cells[x.entry.key + '|' + col.colKey];
      if (pc && pc.tones && pc.tones[col.measure]) { cls += ' ' + safeClasses(pc.tones[col.measure]); }
      if (pc && pc.notes && pc.notes[col.measure]) { note = String(pc.notes[col.measure]); }
    }
    if (this.view.o.decorateCell && !loading) {
      deco = this.view.o.decorateCell({ row: r, col: c, columnId: col.id, column: col, value: value, text: text, kind: x.kind, entry: x.kind === 'pivot' ? x.entry : null,
        measure: col.measure || null, colKey: col.colKey || null, cell: x.kind === 'pivot' && m.answer ? m.answer.cells[x.entry.key + '|' + col.colKey] || null : null,
        data: x.kind === 'leaf' ? x.row : null, rowHeader: !!col.rowHeader });
      if (deco && deco.cls) { cls += ' ' + safeClasses(deco.cls); }
      if (deco && deco.title) { note = note ? note + '\n' + deco.title : String(deco.title); }
      if (deco && deco.style) { Object.keys(deco.style).forEach((k) => { if (DECO_STYLES.indexOf(k) >= 0 && deco.style[k]) { style[k] = String(deco.style[k]); } }); }
    }
    const focused = k.focus && k.focus.row === r && k.focus.col === c;
    if (focused) { cls += ' dg-focus'; }
    if ((col.cellClass || col.cellStyle) && !loading && (x.kind === 'leaf' || x.kind === 'group' || x.tree)) {
      const look = cellLook(col, value, x.row || {});
      if (look.cls) { cls += ' ' + look.cls; }
      Object.assign(style, look.style);
    }
    if (num && typeof value === 'number' && !total && !loading) {
      const rule = ruleFor(this.view.formats, { id: col.id, measure: col.measure, source: this.view.sourceOf(col) });
      if (rule) { const bg = cfBackground(rule, value, this.cfRange(d)); if (bg) { style['background-image'] = bg; cls += ' dg-cf'; } }
    }
    if (num && typeof value === 'number') {
      const f = k.flash.seen(k.rowKey + '|' + col.id, value);
      if (f) { cls += ' dg-flash-' + f.dir; style['animation-delay'] = (-f.age) + 'ms'; }
    }
    const cell = h(doc, 'div', { cls: cls, attrs: { role: col.rowHeader ? 'rowheader' : 'gridcell', id: this.uid + '-r' + r + '-c' + c, 'aria-colindex': String(c + 1),
      'aria-selected': k.selected || m.selection.inRange(r, c) ? 'true' : null, 'aria-readonly': x.kind === 'leaf' && col.editable && !m.editing.canEdit(r, c) ? 'true' : null,
      title: note }, data: x.kind === 'pivot' && !col.rowHeader ? { r: String(r), c: String(c), cell: x.entry.key + '|' + col.colKey } : { r: String(r), c: String(c) }, style: style });
    if (k.first && !loading) {
      const depth = x.depth || 0;
      if (depth) { cell.style.paddingLeft = (8 + depth * 16) + 'px'; }
      if (this.view.checkboxes() && (m.idAt(r) !== null)) {
        const box = h(doc, 'input', { cls: 'dg-check', attrs: { type: 'checkbox', tabindex: '-1', 'aria-label': 'Select row' }, data: { r: String(r) } });
        box.checked = !!k.selected;
        cell.appendChild(box);
      }
      if (k.expandable) { cell.appendChild(h(doc, 'span', { cls: 'dg-tw', text: k.expanded ? '−' : '+', attrs: { 'aria-hidden': 'true' }, data: { r: String(r) } })); }
      else if (m.pivotMode || (m.rowModel.groupBy && m.rowModel.groupBy.length) || m.rowModel.treeData) { cell.appendChild(h(doc, 'span', { cls: 'dg-tw dg-tw-none', attrs: { 'aria-hidden': 'true' } })); }
    }
    const leafRow = !loading && x.row && (x.kind === 'leaf' || x.tree) ? x.row : null;   // a leaf's row, or a tree node's own
    const drawn = leafRow && col.cellRenderer && !(deco && deco.content) ? col.cellRenderer({ value: value, text: text, row: leafRow, doc: doc }) : null;
    if (deco && deco.before && typeof deco.before === 'object' && deco.before.nodeType) { cell.appendChild(deco.before); }
    if (deco && deco.content && typeof deco.content === 'object' && deco.content.nodeType) {
      const box = h(doc, 'span', { cls: 'dg-text' });
      box.appendChild(deco.content);
      cell.appendChild(box);
    } else if (drawn !== null && drawn !== undefined && drawn !== '') {
      cell.appendChild(typeof drawn === 'string' ? h(doc, 'span', { cls: 'dg-text', text: drawn }) : drawn);
    } else { cell.appendChild(h(doc, 'span', { cls: 'dg-text', text: text })); }
    if (deco && deco.after !== undefined && deco.after !== null && deco.after !== '') {
      cell.appendChild(typeof deco.after === 'object' && deco.after.nodeType ? deco.after : h(doc, 'span', { cls: 'dg-after', text: String(deco.after) }));
    }
    return cell;
  }

  /** @private the figures of a column's leaf cells, for its conditional format (measured once per change of the data) */
  cfRange(d) {
    const key = d.col.id;
    if (this.cfCache.has(key)) { return this.cfCache.get(key); }
    const m = this.model, n = Math.min(m.rowCount(), CF_SCAN), vals = [];
    if (!(m.pivotMode && (d.col.kind === 'total' || d.col.kind === 'grand'))) {
      for (let r = 0; r < n; r++) {
        const x = m.rowAt(r);
        if (!x || (x.kind === 'pivot' ? (x.entry.kind !== 'node' || x.entry.expandable) : x.kind !== 'leaf')) { continue; }
        vals.push(m.cellValue(r, d.i));
      }
    }
    const range = figureRange(vals);
    this.cfCache.set(key, range);
    return range;
  }

  /**
   * Scrolls so the focused cell is in view (after the keys moved it).
   * @returns {void}
   */
  scrollToFocus() {
    const m = this.model, f = m.navigator.focus;
    if (!f) { return; }
    const vp = m.viewport, rh = vp.rowHeight;
    const top = this.scroller.scrollTop, room = vp.height;
    const real = f.row * rh;
    if (vp.contentHeight() <= vp.maxScrollHeight) {
      if (real < top) { this.scroller.scrollTop = real; } else if (real + rh > top + room) { this.scroller.scrollTop = real + rh - room; }
    } else if (vp.scrollToRow(f.row) !== null) { this.scroller.scrollTop = vp.scrollTop; }
    const disp = this.view.displayColumns(), d = disp.byIndex[f.col];
    if (d && d.pane === 'center') {
      const left = disp.offs[disp.center.indexOf(d)], vis = this.scroller.clientWidth - disp.leftW - disp.rightW, sl = this.scroller.scrollLeft;
      if (left < sl) { this.scroller.scrollLeft = left; } else if (left + d.width > sl + vis) { this.scroller.scrollLeft = left + d.width - vis; }
    }
  }

  /**
   * Stops listening (the grid is destroyed).
   * @returns {void}
   */
  destroy() {
    this.doc.defaultView.removeEventListener('mouseup', this.onUp);
    if (this.frame) { this.doc.defaultView.cancelAnimationFrame(this.frame); }
  }
}
