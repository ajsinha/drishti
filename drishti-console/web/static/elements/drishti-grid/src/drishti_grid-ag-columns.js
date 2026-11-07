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
/* The AG Grid-style adapter's handle, its filter, column, grouping and pivot calls (AgGridApi extends this): setFilterModel and
   getFilterModel translated both ways, the column state, widths and visibility, the row group, pivot and value columns (the wells), pivot
   mode, the pivot's result columns, expand and collapse, and refreshServerSide. Each forwards to drishti-grid's own model. */
import { translateFilterModel } from './drishti_grid-ag-map.js';

/**
 * The filter, column, grouping and pivot calls of the adapter's handle (it holds go, t, report, model, grid and events).
 */
export class AgColumnsApi {
  // ---- filters, sort, columns -------------------------------------------------------------------------------------------------------------
  /**
   * Filters by an AG-shaped filter model (null clears).
   * @param {?Object} model
   * @returns {void}
   */
  setFilterModel(model) {
    const type = (id) => { const c = this.model.columns.get(id); return c ? c.type : null; };
    this.model.setFilterModel(translateFilterModel(model || {}, true, type, (w) => this.report.add('setFilterModel', 'partial', w)));
  }

  /**
   * The filter model in AG's shape.
   * @returns {Object}
   */
  getFilterModel() { return translateFilterModel(this.model.filterModel, false); }

  /**
   * Whether any column is filtered (or the quick filter).
   * @returns {boolean}
   */
  isAnyFilterPresent() { return Object.keys(this.model.filterModel).some((k) => this.model.filterModel[k]) || !!this.model.rowModel.quickFilter; }

  /**
   * Each column's state: width, hidden, pinned, sort, row group, pivot and aggregation.
   * @returns {Array<Object>}
   */
  getColumnState() { return this.events.columnState(); }

  /**
   * Gives back column states (by colId): width, hide, pinned, sort and sortIndex, rowGroup and rowGroupIndex, pivot and pivotIndex,
   * aggFunc; applyOrder moves the columns to the state's order; defaultState applies to the columns not named.
   * @param {{state: Array<Object>, applyOrder: boolean, defaultState: Object}} p
   * @returns {boolean}
   */
  applyColumnState(p) { return this.events.applyColumnState(p || {}); }

  /**
   * The column state the grid was made with.
   * @returns {void}
   */
  resetColumnState() { this.setColumnDefs(this.go.columnDefs); this.model.setSortModel(this.t.sort); }

  /**
   * Shares the element's width among the shown columns (by flex where given, else by width).
   * @returns {void}
   */
  sizeColumnsToFit() { this.events.sizeToFit(); }

  /**
   * Sizes every shown column to its content.
   * @returns {void}
   */
  autoSizeAllColumns() { if (this.grid) { this.grid.autosize(-1); } }

  /**
   * Sizes some columns to their content.
   * @param {string[]} keys column ids
   * @returns {void}
   */
  autoSizeColumns(keys) { if (this.grid) { const cols = this.model.shownColumns(); (keys || []).forEach((k) => { const i = cols.findIndex((c) => c.id === k); if (i >= 0) { this.grid.autosize(i); } }); } }

  /**
   * Shows or hides columns.
   * @param {string[]} keys
   * @param {boolean} visible
   * @returns {void}
   */
  setColumnsVisible(keys, visible) { (keys || []).forEach((k) => this.model.columns.setVisible(String(k), !!visible)); }

  /**
   * Pins columns left or right, or unpins them.
   * @param {string[]} keys
   * @param {?string} pinned
   * @returns {void}
   */
  setColumnsPinned(keys, pinned) { (keys || []).forEach((k) => this.model.columns.pin(String(k), pinned === 'right' ? 'right' : pinned ? 'left' : null)); }

  /**
   * Sets columns' widths.
   * @param {Array<{key: string, newWidth: number}>} list
   * @returns {void}
   */
  setColumnWidths(list) { (list || []).forEach((x) => this.model.columns.setWidth(String(x.key), x.newWidth)); }

  /**
   * Moves columns to a place in the order.
   * @param {string[]} keys
   * @param {number} toIndex
   * @returns {void}
   */
  moveColumns(keys, toIndex) { (keys || []).forEach((k, i) => this.model.columns.move(String(k), toIndex + i)); }

  /**
   * The column definitions as the application gave them.
   * @returns {Object[]}
   */
  getColumnDefs() { return (this.go.columnDefs || []).slice(); }

  /**
   * The columns (not the adapter's group column) as {colId, colDef, visible, pinned}.
   * @returns {Object[]}
   */
  getColumns() { return this.model.columns.all().filter((c) => !c.system).map((c) => this.events.columnOf(c)); }

  // ---- grouping and pivot -----------------------------------------------------------------------------------------------------------------
  /**
   * Turns pivot mode on or off.
   * @param {boolean} on
   * @returns {Promise<void>}
   */
  setPivotMode(on) {
    this.go.pivotMode = !!on;
    const p = this.grid ? this.grid.setPivotMode(on) : this.model.setPivotMode(on);
    if (!on && this.model.wells.state.rows.length) { this.model.setRowGroups(this.model.wells.state.rows.slice()); }
    if (on && !this.model.serverSide && this.model.rowModel.groupBy && this.model.rowModel.groupBy.length) { this.model.rowModel.setGroupBy([]); }
    return p;
  }

  /**
   * Whether the grid is a pivot.
   * @returns {boolean}
   */
  isPivotMode() { return this.model.pivotMode; }

  /** @private a well changed: Rows also groups the grid when it is not a pivot */
  setWell(well, ids) {
    const s = this.model.wells.getState();
    s[well] = well === 'values' ? ids.map((x) => (typeof x === 'string' ? { colId: x, aggFunc: (this.model.columns.get(x) || {}).aggFunc || 'sum' } : x)) : ids.map(String);
    this.model.wells.setState(s);
    if (well === 'rows' && !this.model.pivotMode) { this.model.setRowGroups(s.rows); }
  }

  /**
   * Sets the row group columns, in order.
   * @param {string[]} keys
   * @returns {void}
   */
  setRowGroupColumns(keys) { this.setWell('rows', keys || []); }

  /**
   * Adds row group columns at the end.
   * @param {string[]} keys
   * @returns {void}
   */
  addRowGroupColumns(keys) { this.setWell('rows', this.model.wells.state.rows.concat((keys || []).filter((k) => this.model.wells.state.rows.indexOf(k) < 0))); }

  /**
   * Takes columns out of the row groups.
   * @param {string[]} keys
   * @returns {void}
   */
  removeRowGroupColumns(keys) { this.setWell('rows', this.model.wells.state.rows.filter((k) => (keys || []).indexOf(k) < 0)); }

  /**
   * The row group columns.
   * @returns {Object[]}
   */
  getRowGroupColumns() { return this.model.wells.state.rows.map((id) => this.events.columnOf(this.model.columns.get(id))); }

  /**
   * Sets the pivot columns, in order.
   * @param {string[]} keys
   * @returns {void}
   */
  setPivotColumns(keys) { this.setWell('columns', keys || []); }

  /**
   * The pivot columns.
   * @returns {Object[]}
   */
  getPivotColumns() { return this.model.wells.state.columns.map((id) => this.events.columnOf(this.model.columns.get(id))); }

  /**
   * Sets the value columns (ids take their column's aggFunc; {colId, aggFunc} sets it).
   * @param {Array<(string|{colId: string, aggFunc: string})>} keys
   * @returns {void}
   */
  setValueColumns(keys) { this.setWell('values', keys || []); }

  /**
   * The value columns.
   * @returns {Object[]}
   */
  getValueColumns() { return this.model.wells.state.values.map((v) => Object.assign(this.events.columnOf(this.model.columns.get(v.colId)), { aggFunc: this.t.aggNames[v.colId] || v.aggFunc })); }

  /**
   * The pivot's result columns: {colId, colDef: {headerName, pivotKeys, pivotValueColumn}}.
   * @returns {?Object[]}
   */
  getPivotResultColumns() { return this.model.pivotMode ? this.model.shownColumns().filter((c) => !c.rowHeader).map((c) => ({ colId: c.id, colDef: { headerName: c.header, pivotKeys: (c.headerPath || []).slice(0, -1), pivotValueColumn: c.measure } })) : null; }

  /** @private open every group (pivot or grouped rows) down to a level */
  expandTo(level) {
    const m = this.model;
    if (m.pivotMode) { m.pivotState.expandToLevel('rows', level); m.pivotState.expandToLevel('columns', level); return m.runPivot(); }
    if (m.rowModel.expandToLevel) { m.rowModel.expandToLevel(level >= 99 ? Infinity : level - 1); }
    return Promise.resolve();
  }

  /**
   * Opens every group (and every pivot column group).
   * @returns {Promise<void>}
   */
  expandAll() { return this.expandTo(99); }

  /**
   * Closes every group.
   * @returns {Promise<void>}
   */
  collapseAll() { return this.expandTo(1); }

  /**
   * Asks the server again: every level (or the route's level and those under it); purge forgets the row counts too. A server pivot is
   * computed again.
   * @param {{route: string[], purge: boolean}} [p]
   * @returns {void}
   */
  refreshServerSide(p) {
    const m = this.model;
    if (m.pivotMode) { m.runPivot(); return; }
    if (!m.serverSide) { this.report.add('refreshServerSide on client-side rows', 'unsupported', 'setGridOption(\'rowData\', rows) or applyTransaction'); return; }
    if (p && p.route && p.route.length) { m.rowModel.refresh({ keys: p.route.map(String), purge: p.purge !== false }); } else { m.rowModel.refresh(); }
  }

  /**
   * Forgets every server-side block and asks again (the older name of refreshServerSide({purge: true})).
   * @returns {void}
   */
  purgeServerSideCache() { this.refreshServerSide({ purge: true }); }
}
