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
/* The grid body's pointer work: a press, a drag across a range, a click and Shift+click, a double click, the context menu, a column's
   resize handle and a group's toggle. GridBody (drishti_grid-body.js) extends this and keeps the drawing. */
import { nonAdditiveNote } from './drishti_grid-ui.js';

/** The pointer work of a grid body; every member works on GridBody's own fields. */
export class GridPointer {
  /** @private */
  down(e) {
    if (e.button !== 0) { return; }
    const t = e.target;
    if (t.closest('.dg-tw, input, button, select, a[href], .dg-resize, .dg-hcell, .dg-editor')) { return; }
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
      if (col) { this.view.notes.showTip(nonAdditiveNote(col.measure || col.header, x.el.textContent), x.el); }   // a column gone since the cell was drawn: no note
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
    if (this.model.editing.canEdit(x.r, x.c)) { this.view.startEditing(x.r, x.c); return; }
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
}
