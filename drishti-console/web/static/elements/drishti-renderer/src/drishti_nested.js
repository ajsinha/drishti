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
/* The nested pivot in behaviours: 'console' (EMBED_GUIDE.md section 7.4): a pivot panel by a list of fields (div.pv-tree-host, its
   rows in data-pv-tree) is drawn again by drishti-grid's own grid with tree data: each row by its path, the groups open down to the
   Sutra's expand, every figure the server's text (the browser sums nothing: clientAggregation off), the total column and the grand total
   row as the server sent them. In 'library' mode the document table drishti-grid drew first stays. */
import { Grid } from '../../drishti-grid/src/drishti_index.js';

/** The row height of the compact density (drishti-grid) and the most rows shown before the grid scrolls inside itself. */
const ROW_PX = 28, MOST = 14;

/**
 * The grid's options for a nested pivot's data (the ViewModel's pivot panel: by, levels, columns, rows with paths, totals).
 * @param {Object} d the panel's data
 * @param {string} title the panel's title (the grid's name for a screen reader)
 * @returns {Object} drishti-grid options (columns, rows, treeData, getDataPath, groupDefaultExpanded, clientAggregation false)
 */
export function nestedPivotOptions(d, title) {
  const by = String(d.by || '');
  const cols = [{ id: 'label', header: by ? by[0].toUpperCase() + by.slice(1) : 'Rows', field: 'label', type: 'text', width: 200, pinned: 'left', filterable: false }];
  (d.columns || []).forEach((c, i) => cols.push({ id: 'c' + i, header: String(c), type: 'number', additive: false, valueGetter: (r) => (r.values || [])[i],
    format: (v, r) => ((r.cells || [])[i] || {}).text || '', sortable: true, filterable: false }));
  if (d.totals || (d.rows || []).some((r) => r.total)) {
    cols.push({ id: 'total', header: 'Total', type: 'number', additive: false, valueGetter: () => null, format: (v, r) => (r.total || {}).text || '', sortable: false, filterable: false });
  }
  const rows = (d.rows || []).map((r, i) => Object.assign({ id: 'r' + i }, r, { path: r.path || [r.label] }));
  if (d.totals) { rows.push({ id: 'grand', label: 'Total', path: ['Total'], cells: d.totals.slice(0, (d.columns || []).length), total: d.totals[(d.columns || []).length] || null, grand: true }); }
  return { title: title, label: title, columns: cols, rows: rows, getRowId: (r) => r.id, treeData: true, getDataPath: (r) => r.path,
    groupDefaultExpanded: Math.max(0, (d.expand || 1) - 1), clientAggregation: false, layout: 'wells', density: 'compact', checkboxes: false };
}

/**
 * Draws every nested pivot under a root with drishti-grid, now and in panels swapped in later.
 * @param {ParentNode} root the screen's root
 * @param {Object} o the screen's options
 * @returns {{add: function(ParentNode): void, dispose: function(): void}}
 */
export function drawNestedPivots(root, o) {
  const grids = [];
  const add = (scope) => {
    (scope || root).querySelectorAll('.pv-tree-host[data-pv-tree]:not([data-tree-ready])').forEach((host) => {
      let d;
      try { d = JSON.parse(host.getAttribute('data-pv-tree')); } catch (e) { return; }
      const pnl = host.closest('.pnl'), title = pnl ? (pnl.querySelector('h3') || {}).textContent || '' : '';
      const box = host.ownerDocument.createElement('div');
      box.className = 'pv-tree-grid';
      box.style.setProperty('height', Math.min(MOST, (d.rows || []).length + 2) * ROW_PX + 80 + 'px');
      host.textContent = '';
      host.appendChild(box);
      try { grids.push(new Grid(box, nestedPivotOptions(d, title))); host.setAttribute('data-tree-ready', ''); } catch (e) {
        if (window.console) { console.warn('drishti-renderer: the nested pivot could not be drawn', e); }
      }
    });
  };
  add(root);
  return { add, dispose() { grids.splice(0).forEach((g) => { try { g.destroy(); } catch (e) { /* gone */ } }); } };
}
