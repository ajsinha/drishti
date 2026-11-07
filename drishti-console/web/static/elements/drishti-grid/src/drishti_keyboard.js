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
/* Keyboard navigation, the model only: which cell has focus and what each key does. A layout (wells, panel, terminal) draws the focus
   and runs the action; the keys are the same in every layout:

     Arrows                one cell              Ctrl+Arrows         to the edge
     Home / End            first / last column   Ctrl+Home / End     first / last cell
     PageUp / PageDown     a page of rows        Tab / Shift+Tab     next / previous cell, wrapping to the next row
     Shift+Arrows          stretch the range     Shift+Space         select the row        Ctrl+A   select all
     Space                 toggle the row's selection, or a group open
     Right / Left on a group row's first cell: open / close it (PIVOT_ENGINE.md section 11: Shift+Right opens everything below)
     Enter or F2           edit (an editable cell) or open (a group, a link, Explain)
     Ctrl+C                copy (the range, else the cell)          Escape   clear the range, or leave editing
     E                     Explain this figure (when the grid has an Explain hook)

   Pure: give it the grid's size and the event, it gives the new focus and an action. Runs under Node. */
import { keyName } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} KeyResult
 * @property {?{row: number, col: number}} focus the new focus (null: unchanged)
 * @property {?string} action what the layout does: select-row, toggle-row, select-all, extend-range, copy, edit, open, expand,
 *   collapse, expand-all, collapse-all, explain, clear, null
 * @property {boolean} handled whether the key was the grid's (the layout then prevents the browser's default)
 */

/**
 * The focused cell and the keys' meaning.
 */
export class GridNavigator {
  /**
   * @param {{rowCount: function(): number, columnCount: function(): number, pageSize: function(): number, isGroup: function(number): boolean, isExpanded: function(number): boolean, explain: boolean}} grid
   */
  constructor(grid) {
    this.grid = grid;
    this.focus = null;
  }

  /**
   * Puts the focus on a cell (clamped), or nowhere (null).
   * @param {?number} row
   * @param {number} [col]
   * @returns {?{row: number, col: number}}
   */
  setFocus(row, col) {
    if (row === null) { this.focus = null; return null; }
    const R = this.grid.rowCount(), C = this.grid.columnCount();
    if (!R || !C) { this.focus = null; return null; }
    this.focus = { row: Math.max(0, Math.min(R - 1, row)), col: Math.max(0, Math.min(C - 1, col || 0)) };
    return this.focus;
  }

  /**
   * What a key does.
   * @param {{key: string, ctrlKey: boolean, metaKey: boolean, shiftKey: boolean, altKey: boolean}} e
   * @returns {KeyResult}
   */
  handleKey(e) {
    const R = this.grid.rowCount(), C = this.grid.columnCount();
    const none = { focus: null, action: null, handled: false };
    if (!R || !C) { return none; }
    if (!this.focus) { this.setFocus(0, 0); return { focus: this.focus, action: null, handled: true }; }
    const f = this.focus, mod = e.ctrlKey || e.metaKey, page = Math.max(1, this.grid.pageSize ? this.grid.pageSize() : 10);
    const move = (r, c, action) => ({ focus: this.setFocus(r, c), action: action || null, handled: true });
    const k = e.key;
    const group = this.grid.isGroup ? this.grid.isGroup(f.row) : false;
    if (mod && !e.shiftKey && (k === 'c' || k === 'C')) { return { focus: null, action: 'copy', handled: true }; }
    if (mod && (k === 'a' || k === 'A')) { return { focus: null, action: 'select-all', handled: true }; }
    if (k === 'Escape') { return { focus: null, action: 'clear', handled: true }; }
    if (group && f.col === 0 && !mod && (k === 'ArrowRight' || k === 'ArrowLeft')) {
      const open = this.grid.isExpanded ? this.grid.isExpanded(f.row) : false;
      if (e.shiftKey) { return { focus: null, action: k === 'ArrowRight' ? 'expand-all' : 'collapse-all', handled: true }; }
      if (k === 'ArrowRight' && !open) { return { focus: null, action: 'expand', handled: true }; }
      if (k === 'ArrowLeft' && open) { return { focus: null, action: 'collapse', handled: true }; }
    }
    const ext = e.shiftKey && !mod ? 'extend-range' : null;
    switch (k) {
      case 'ArrowUp': return move(mod ? 0 : f.row - 1, f.col, ext);
      case 'ArrowDown': return move(mod ? R - 1 : f.row + 1, f.col, ext);
      case 'ArrowLeft': return move(f.row, mod ? 0 : f.col - 1, ext);
      case 'ArrowRight': return move(f.row, mod ? C - 1 : f.col + 1, ext);
      case 'Home': return move(mod ? 0 : f.row, 0, e.shiftKey ? 'extend-range' : null);
      case 'End': return move(mod ? R - 1 : f.row, C - 1, e.shiftKey ? 'extend-range' : null);
      case 'PageUp': return move(f.row - page, f.col, ext);
      case 'PageDown': return move(f.row + page, f.col, ext);
      case 'Tab': {
        if (e.shiftKey) { return f.col > 0 ? move(f.row, f.col - 1) : f.row > 0 ? move(f.row - 1, C - 1) : none; }
        return f.col < C - 1 ? move(f.row, f.col + 1) : f.row < R - 1 ? move(f.row + 1, 0) : none;
      }
      case ' ':
      case 'Spacebar':
        if (e.shiftKey) { return { focus: null, action: 'select-row', handled: true }; }
        return { focus: null, action: group ? (this.grid.isExpanded && this.grid.isExpanded(f.row) ? 'collapse' : 'expand') : 'toggle-row', handled: true };
      case 'Enter':
      case 'F2': return { focus: null, action: group && k === 'Enter' ? 'open' : 'edit', handled: true };
      default:
        if ((k === 'e' || k === 'E') && !mod && !e.altKey && this.grid.explain) { return { focus: null, action: 'explain', handled: true }; }
        return none;
    }
  }

  /**
   * The keys as words for a help line ("Arrows: move · Shift+Arrows: select a range · ..."), the same in every layout.
   * @returns {Array<{keys: string, does: string}>}
   */
  static help() {
    return [
      { keys: 'Arrows', does: 'move' }, { keys: 'Shift+Arrows', does: 'select a range' }, { keys: 'Ctrl+C', does: 'copy' },
      { keys: 'Space', does: 'select the row, or open a group' }, { keys: 'Right / Left', does: 'open / close a group' },
      { keys: 'Shift+Right / Left', does: 'open / close everything below' }, { keys: 'Enter', does: 'open or edit' }, { keys: 'E', does: 'Explain the figure' },
      { keys: 'Ctrl+A', does: 'select all' }, { keys: 'Escape', does: 'clear' }
    ];
  }
}

/**
 * The name of a key press as the help line writes it (drishti-core's keyName).
 * @param {KeyboardEvent} e
 * @returns {string}
 */
export function keyLabel(e) { return keyName(e); }
