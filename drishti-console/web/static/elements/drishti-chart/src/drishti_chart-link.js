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
/* Linked selection between a grid and charts, through drishti-core's event bus: rows selected in a drishti-grid outline their marks in
   every chart on the same channel, and marks (or map places) clicked in a chart select their rows in the grid. A grid is linked by its
   model's typed events (selectionChanged) and its selection model, so drishti-chart needs no import of drishti-grid; a chart is linked by
   its link option ({channel}). The ids are the rows' ids (the grid's getRowId, the chart spec's id field). */
import { bus as sharedBus } from '../../drishti-core/src/drishti_index.js';
import { SELECTION_EVENT } from './drishti_chart-view.js';

/**
 * Links a drishti-grid (the Grid, the element's grid, or its GridModel) to a selection channel: its row selection is published, and a
 * selection published by a chart selects the grid's rows.
 * @param {Object} grid
 * @param {{channel: string, bus: Object}} o the channel the charts share; bus default drishti-core's
 * @returns {function(): void} unlinks
 */
export function linkGrid(grid, o) {
  const model = grid && grid.model ? grid.model : grid, b = (o && o.bus) || sharedBus, channel = o && o.channel;
  if (!model || !model.events || !model.selection) { throw new Error('linkGrid: give a drishti-grid (Grid, the element\'s grid, or its model)'); }
  let applying = false;
  const off1 = model.events.on('selectionChanged', (p) => { if (!applying) { b.emit(SELECTION_EVENT, { channel: channel, ids: (p && p.ids) || [], source: model }); } });
  const off2 = b.on(SELECTION_EVENT, (p) => {
    if (!p || p.channel !== channel || p.source === model) { return; }
    applying = true;
    try { model.selection.selectAll(p.ids || []); } finally { applying = false; }
  });
  return () => { off1(); off2(); };
}

/**
 * Publishes a selection on a channel (a page's own control, a test).
 * @param {string} channel
 * @param {string[]} ids
 * @param {{bus: Object, source: *}} [o]
 * @returns {void}
 */
export function publishSelection(channel, ids, o) { ((o && o.bus) || sharedBus).emit(SELECTION_EVENT, { channel: channel, ids: ids || [], source: (o && o.source) || null }); }
