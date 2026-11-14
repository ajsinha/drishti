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
   export, keyboard navigation, the Explain hook and the AG Grid-style adapter (createGridFromAgOptions). The layouts (wells, panel, terminal) draw it; a plain renderer serves tests. */
export { ADDITIVE_FUNCS, normalizeColumn, getValue, formatValue, ColumnModel } from './drishti_columns.js';
export { AGG_FUNCS, aggFunc, registerAggFunc, unregisterAggFunc, parseMeasure, isServerMeasure, aggregate } from './drishti_aggregate.js';
export { SEP, BLANK, memberText, valueLabel, normalizeView, serverRequest, memberFilters, PivotState, pivotLocal } from './drishti_pivot.js';
export { pivotApplyUpdates, mergeAnswer, applyValueFilters, resultColumns, cellValue, measureText, answerFormat, answerScales } from './drishti_pivot-answer.js';
export { WELLS, VALUE_FUNCS, FUNC_LABELS, FUNC_ARGS, funcLabel, WellsModel } from './drishti_wells.js';
export { fieldIcon, fieldGroups, pickField, openPicker } from './drishti_grid-fields.js';
export { ViewPicker } from './drishti_grid-views.js';
export { ClientRowModel } from './drishti_client-rows.js';
export { ServerRowModel, memoryDatasource } from './drishti_server-rows.js';
export { MAX_SCROLL_HEIGHT, columnAt, Viewport } from './drishti_viewport.js';
export { SelectionModel, rangeStats, toTsv, rangeCells } from './drishti_selection.js';
export { crc32, zipStore, excelFormat, toXlsx, XLSX_TYPE } from './drishti_export.js';
export { GridNavigator, keyLabel } from './drishti_keyboard.js';
export { GridModel, createGridModel } from './drishti_grid.js';
export { LAYOUTS, DENSITIES, placeGrandRow, GridModelBase } from './drishti_grid-model-base.js';
export { GRID_EVENTS, domEventName, GridLive } from './drishti_grid-live.js';
export { EDITORS, editorOf, parseCellText, validateCell, parseTsv, GridEditing } from './drishti_grid-edit.js';
export { CellEditor } from './drishti_grid-editors.js';
export { manualFeed } from './drishti_live.js';
export { ROW_KEY, ROW_VERSION, WINDOW_ROWS, gridRow, gridColumns, LiveRows, windowIds, editItems, editSchema, pivotLayout, pivotHref, linkFor, maskedPaths,
  changedFields, httpLiveApi } from './drishti_live-model.js';
export { stateWords, isPending, itemsText, RecordPanel } from './drishti_live-panel.js';
export { LIVE_STATES, LiveSetGrid } from './drishti_live-set.js';
export { renderWindow, renderText, renderHtml } from './drishti_plain-render.js';
export { docTable, docPivotable, docDrill, heatBucket, docPivot, docPlainTable } from './drishti_doc-table.js';
export { pageInfo, enhanceDocTables } from './drishti_doc-behave.js';
export { consoleTables } from './drishti_doc-console.js';
export { rowSelection } from './drishti_doc-select.js';
export { VIEW_EVENTS, PHONE_WIDTH, FUNCTION_KEYS, CF_TYPES, headerSpans, pivotColumnLabels, markGroups, isTotalCell, nonAdditiveNote, colourScaleAlpha,
  dataBarWidth, cfBackground, figureRange, ruleFor, phoneColumns, pivotMeasures, columnMenuItems, cellMenuItems, commandText, parseCommand,
  parseCondition, conditionText, statusItems, cellLook, FlashTracker } from './drishti_grid-ui.js';
export { Menu, Notes } from './drishti_grid-dom.js';
export { sparkline, renderExplain } from './drishti_grid-explain.js';
export { localDrill } from './drishti_grid-actions.js';
export { GridBase } from './drishti_grid-base.js';
export { Grid } from './drishti_grid-view.js';
export { ELEMENT_NAME, defineGridElement } from './drishti_grid-element.js';
export { AG_MAPPING, AG_NOT_COVERED, AG_GRID_OPTIONS, AG_COLUMN_KEYS, AG_UNSUPPORTED, AG_NO_OP, AG_EVENTS, agFilterToDrishti, drishtiFilterToAg,
  translateFilterModel } from './drishti_grid-ag-map.js';
export { AG_GROUP_COLUMN, AgReport, agAggFunc, translateAgOptions } from './drishti_grid-ag.js';
export { callGetRows, agRequest, agDatasource, parseResultField, agServerPivot } from './drishti_grid-ag-server.js';
export { AgEvents } from './drishti_grid-ag-events.js';
export { AgColumnsApi } from './drishti_grid-ag-columns.js';
export { AgGridApi, createGridFromAgOptions } from './drishti_grid-ag-api.js';
import { defineGridElement } from './drishti_grid-element.js';

/**
 * Starts drishti-grid in a page (the bundle calls it when it loads; nothing happens without a document): defines <drishti-grid>.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { defineGridElement(root.customElements, root); } }
