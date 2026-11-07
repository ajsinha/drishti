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
import { headerSpans, pivotColumnLabels, markGroups, isTotalCell, nonAdditiveNote, cfBackground, figureRange, ruleFor } from './drishti_grid-ui.js';

const CF_SCAN = 100000;

/**
 * The drawn grid: headings, rows, cells and their pointer work, for a view (drishti_grid-view.js).
 */
export class GridBody {
  /**
   * @param {Object} view the Grid (drishti_grid-view.js): its model, its display columns, its menus and its Explain
   */
  constructor(view) {
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
    if (!c || !this.scroller.contains(c)) { return null; }
    return { el: c, r: +c.dataset.r, c: +c.dataset.c };
  }

  /** @private */
  down(e) {
    if (e.button !== 0) { return; }
    const t = e.target;
    if (t.closest('.dg-tw, input, button, .dg-resize, .dg-hcell')) { return; }
    const x = this.cellOf(e);
    if (!x) { return; }
    e.preventDefault();
    this.scroller.focus({ preventScroll: true });
    this.model.cellClicked(x.r, x.c, { shift: e.shiftKey, ctrl: e.ctrlKey || e.metaKey });
    this.drag = { r: x.r, c: x.c };
    this.view.afterFocus(false);
  }

  /** @private */
  over(e) {
    const x = this.cellOf(e);
    if (this.drag && x && (x.r !== this.drag.r || x.c !== this.drag.c)) {
      this.drag = { r: x.r, c: x.c };
      this.model.selection.extendRange(x.r, x.c);
    }
    if (x && x.el.classList.contains('dg-nadd') && !this.drag) {
      const col = this.model.shownColumns()[x.c];
      this.view.notes.showTip(nonAdditiveNote(col.measure || col.header, x.el.textContent), x.el);
    } else { this.view.notes.hideTip(); }
  }

  /** @private */
  click(e) {
    const t = e.target, v = this.view, m = this.model;
    const tw = t.closest('.dg-tw');
    if (tw) { e.stopPropagation(); m.toggleRow(+tw.dataset.r); v.announceRow(+tw.dataset.r); return; }
    if (t.matches('input.dg-check')) {
      const r = +t.dataset.r;
      if (t.dataset.all !== undefined) {
        const ids = [];
        if (t.checked && m.pivotMode) { for (let i = 0; i < m.rowCount(); i++) { const id = m.idAt(i); if (id !== null) { ids.push(id); } } }
        if (t.checked && !m.pivotMode) { m.selectAll(); } else { m.selection.selectAll(ids); }
      }
      else { m.selection.clickRow(r, (i) => m.idAt(i), { ctrl: true }); }
      return;
    }
    const ct = t.closest('.dg-ctoggle');
    if (ct) { const sp = this.groupAt(ct); if (sp) { m.togglePivotNode('columns', sp.group.path); v.say((sp.group.open ? 'Collapsed ' : 'Expanded ') + sp.label); } return; }
    const menu = t.closest('.dg-hmenu');
    if (menu) { e.stopPropagation(); v.openColumnMenu(+menu.dataset.c, menu); return; }
    if (t.closest('.dg-resize')) { return; }
    const hc = t.closest('.dg-hcell.dg-leaf');
    if (hc && !this.resized) { v.sortColumn(+hc.dataset.c, e.shiftKey); }
    this.resized = false;
  }

  /** @private */
  dbl(e) {
    const rz = e.target.closest('.dg-resize');
    if (rz) { this.view.autosize(+rz.dataset.c); return; }
    const x = this.cellOf(e);
    if (!x) { return; }
    this.model.cellDoubleClicked(x.r, x.c);
    if (this.model.navigator.grid.isGroup(x.r)) { this.model.toggleRow(x.r); this.view.announceRow(x.r); }
  }

  /** @private */
  context(e) {
    const x = this.cellOf(e);
    if (!x) { return; }
    e.preventDefault();
    if (!this.model.selection.inRange(x.r, x.c)) { this.model.cellClicked(x.r, x.c, {}); this.view.afterFocus(false); }
    this.view.openCellMenu(x.r, x.c, { x: e.clientX, y: e.clientY });
  }

  /** @private a column's resize by its handle */
  resizeStart(e, d) {
    e.preventDefault();
    e.stopPropagation();
    const x0 = e.clientX, w0 = d.width, win = this.doc.defaultView;
    const move = (ev) => { this.view.setColumnWidth(d, Math.max(40, w0 + ev.clientX - x0)); this.resized = true; };
    const up = () => { win.removeEventListener('pointermove', move); win.removeEventListener('pointerup', up); };
    win.addEventListener('pointermove', move);
    win.addEventListener('pointerup', up);
  }

  /** @private the span of a column-group toggle */
  groupAt(btn) {
    const lv = +btn.dataset.level, key = btn.dataset.key;
    const row = this.spans && this.spans[lv];
    return row ? row.find((s) => s.key === key && s.group) || null : null;
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
   * Draws the headings and the rows in view.
   * @returns {void}
   */
  render() {
    const m = this.model, v = this.view, vp = m.viewport, doc = this.doc;
    const disp = v.displayColumns();
    const rh = vp.rowHeight, hrh = rh + 4;
    const sp = this.paneSpans(disp);
    const headH = sp.levels * hrh;
    const W = disp.leftW + disp.centerW + disp.rightW;
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
    s.setAttribute('role', m.pivotMode || (m.rowModel.groupBy && m.rowModel.groupBy.length) ? 'treegrid' : 'grid');
    s.setAttribute('aria-rowcount', String(m.rowCount() + sp.levels));
    s.setAttribute('aria-colcount', String(m.shownColumns().length));
    const fid = focus ? this.uid + '-r' + focus.row + '-c' + focus.col : null;
    if (fid && doc.getElementById(fid)) { s.setAttribute('aria-activedescendant', fid); } else { s.removeAttribute('aria-activedescendant'); }
    const why = v.emptyText();
    this.empty.hidden = !why;
    this.empty.textContent = why || '';
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
      'aria-level': m.pivotMode || x.kind === 'group' || x.depth ? String((x.depth || 0) + 1) : null, 'aria-expanded': expandable ? String(expanded) : null },
    style: { height: m.viewport.rowHeight + 'px' } });
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
    const focused = k.focus && k.focus.row === r && k.focus.col === c;
    if (focused) { cls += ' dg-focus'; }
    if (num && typeof value === 'number' && !total && !loading) {
      const rule = ruleFor(this.view.formats, { id: col.id, measure: col.measure, source: this.view.sourceOf(col) });
      if (rule) { const bg = cfBackground(rule, value, this.cfRange(d)); if (bg) { style['background-image'] = bg; cls += ' dg-cf'; } }
    }
    if (num && typeof value === 'number') {
      const f = k.flash.seen(k.rowKey + '|' + col.id, value);
      if (f) { cls += ' dg-flash-' + f.dir; style['animation-delay'] = (-f.age) + 'ms'; }
    }
    const cell = h(doc, 'div', { cls: cls, attrs: { role: col.rowHeader ? 'rowheader' : 'gridcell', id: this.uid + '-r' + r + '-c' + c, 'aria-colindex': String(c + 1),
      'aria-selected': k.selected || m.selection.inRange(r, c) ? 'true' : null }, data: { r: String(r), c: String(c) }, style: style });
    if (k.first && !loading) {
      const depth = x.depth || 0;
      if (depth) { cell.style.paddingLeft = (8 + depth * 16) + 'px'; }
      if (this.view.checkboxes() && (m.idAt(r) !== null)) {
        const box = h(doc, 'input', { cls: 'dg-check', attrs: { type: 'checkbox', tabindex: '-1', 'aria-label': 'Select row' }, data: { r: String(r) } });
        box.checked = !!k.selected;
        cell.appendChild(box);
      }
      if (k.expandable) { cell.appendChild(h(doc, 'span', { cls: 'dg-tw', text: k.expanded ? '−' : '+', attrs: { 'aria-hidden': 'true' }, data: { r: String(r) } })); }
      else if (m.pivotMode || (m.rowModel.groupBy && m.rowModel.groupBy.length)) { cell.appendChild(h(doc, 'span', { cls: 'dg-tw dg-tw-none', attrs: { 'aria-hidden': 'true' } })); }
    }
    cell.appendChild(h(doc, 'span', { cls: 'dg-text', text: text }));
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
