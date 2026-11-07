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
/* drishti-grid: the grid's engine, without a look. The data model (columns, rows, ids, sort and filter models), the client-side and
   server-side row models, virtual scrolling for a million rows, the pivot (the server's view and answer: client-side for rows in the
   page, server-side for the rest, non-additive totals always the server's), the wells, selection with range figures, CSV and Excel
   export, keyboard navigation and the Explain hook. The layouts (wells, panel, terminal) draw it; a plain renderer serves tests. */
export { ADDITIVE_FUNCS, normalizeColumn, getValue, formatValue, ColumnModel } from './drishti_columns.js';
export { AGG_FUNCS, aggFunc, parseMeasure, isServerMeasure, aggregate } from './drishti_aggregate.js';
export { SEP, BLANK, memberText, valueLabel, normalizeView, serverRequest, memberFilters, PivotState, pivotLocal, pivotApplyUpdates, mergeAnswer, applyValueFilters,
  resultColumns, cellValue, measureText, answerFormat } from './drishti_pivot.js';
export { WELLS, VALUE_FUNCS, WellsModel } from './drishti_wells.js';
export { ClientRowModel } from './drishti_client-rows.js';
export { ServerRowModel, memoryDatasource } from './drishti_server-rows.js';
export { MAX_SCROLL_HEIGHT, columnAt, Viewport } from './drishti_viewport.js';
export { SelectionModel, rangeStats, toTsv, rangeCells } from './drishti_selection.js';
export { toCsv, crc32, zipStore, excelFormat, toXlsx, XLSX_TYPE } from './drishti_export.js';
export { GridNavigator, keyLabel } from './drishti_keyboard.js';
export { LAYOUTS, DENSITIES, GridModel, createGridModel } from './drishti_grid.js';
export { GRID_EVENTS, domEventName, GridLive } from './drishti_grid-live.js';
export { EDITORS, editorOf, parseCellText, validateCell, parseTsv, GridEditing } from './drishti_grid-edit.js';
export { CellEditor } from './drishti_grid-editors.js';
export { manualFeed } from './drishti_live.js';
export { renderWindow, renderText, renderHtml } from './drishti_plain-render.js';
export { VIEW_EVENTS, PHONE_WIDTH, FUNCTION_KEYS, CF_TYPES, headerSpans, pivotColumnLabels, markGroups, isTotalCell, nonAdditiveNote, colourScaleAlpha,
  dataBarWidth, cfBackground, figureRange, ruleFor, phoneColumns, pivotMeasures, columnMenuItems, cellMenuItems, commandText, parseCommand,
  parseCondition, conditionText, statusItems, FlashTracker } from './drishti_grid-ui.js';
export { Menu, Notes } from './drishti_grid-dom.js';
export { sparkline, renderExplain } from './drishti_grid-explain.js';
export { localDrill } from './drishti_grid-actions.js';
export { GridBase } from './drishti_grid-base.js';
export { Grid } from './drishti_grid-view.js';
export { ELEMENT_NAME, defineGridElement } from './drishti_grid-element.js';
import { defineGridElement } from './drishti_grid-element.js';

/**
 * Starts drishti-grid in a page (the bundle calls it when it loads; nothing happens without a document): defines <drishti-grid>.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { defineGridElement(root.customElements, root); } }
