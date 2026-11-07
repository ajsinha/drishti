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
/* Editing, the model (tested in Node): which cells may change (a leaf row, a column with editable true or a function of the row; never a
   pivot, a group or a figure the server computes), text read as a value by the column's type (numbers in the grid's locale with
   grouping, a true minus, brackets for a negative, k, m and bn, percentages; dates; yes and no; a select's options), validation in
   words (required, min, max, the options, the column's own validate), and every change as one edit transaction: the cells with their
   old and new values, applied by row id (rows re-sorted and re-filtered in place, totals by difference), told as cellValueChanged per
   cell and editTransaction once, kept for undo and redo. A paste from Excel (tab-separated text, quoted cells, a single value filling a
   range) and Delete over a range are transactions too. With a write-back hook the server hears each transaction: applied, refused
   (put back), or proposed for a second person (four eyes: the cells are marked until the proposal is approved or rejected). */

/**
 * One cell's change.
 * @typedef {Object} CellEdit
 * @property {string} rowId
 * @property {string} colId
 * @property {string} field
 * @property {*} old
 * @property {*} value
 */

/**
 * An edit transaction.
 * @typedef {Object} EditTransaction
 * @property {number} id
 * @property {('edit'|'paste'|'clear'|'undo'|'redo'|'api')} source
 * @property {CellEdit[]} edits
 * @property {('applied'|'proposed'|'refused'|'pending')} status
 * @property {?string} proposalId the four-eyes proposal, when one was made
 */

import { parseNumberText } from '../../drishti-core/src/drishti_index.js';

/** The editors a column may name; a column without one gets its type's. */
export const EDITORS = Object.freeze(['text', 'number', 'date', 'select', 'checkbox']);

/**
 * A column's editor: its editor, else checkbox for a boolean, date for a date, number for a number, select when it has options, text.
 * @param {Object} col a normalized column
 * @returns {string}
 */
export function editorOf(col) {
  if (col.editor && EDITORS.indexOf(col.editor) >= 0) { return col.editor; }
  if (col.type === 'boolean') { return 'checkbox'; }
  if (col.type === 'date') { return 'date'; }
  if (col.options) { return 'select'; }
  if (col.type === 'number') { return 'number'; }
  return 'text';
}

/** @private an option's value and label */
function opt(o) { return o !== null && typeof o === 'object' ? { value: o.value, label: String(o.label !== undefined ? o.label : o.value) } : { value: o, label: String(o) }; }

/**
 * Text read as a column's value: {value} or {error} in words.
 * @param {Object} col
 * @param {string} text
 * @param {{locale: string}} [o]
 * @returns {{value: *, error: ?string}}
 */
export function parseCellText(col, text, o) {
  const t = String(text === null || text === undefined ? '' : text).trim(), ed = editorOf(col), L = col.header;
  if (col.parse) { try { return { value: col.parse(t), error: null }; } catch (e) { return { value: undefined, error: e.message }; } }
  if (!t) { return { value: null, error: null }; }
  if (ed === 'number') { const v = parseNumberText(t, o && o.locale); return v === null ? { value: undefined, error: L + ': "' + t + '" is not a number (write 1,250,000, 1.25m or −5k)' } : { value: v, error: null }; }
  if (ed === 'checkbox') {
    if (/^(true|yes|y|1|x|✓|☑)$/i.test(t)) { return { value: true, error: null }; }
    if (/^(false|no|n|0|☐)$/i.test(t)) { return { value: false, error: null }; }
    return { value: undefined, error: L + ': "' + t + '" is not yes or no' };
  }
  if (ed === 'date') {
    let m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(t), y, mo, d;
    if (m) { y = +m[1]; mo = +m[2]; d = +m[3]; }
    else if ((m = /^(\d{1,2})[/.](\d{1,2})[/.](\d{4})$/.exec(t))) { const dmy = !(o && o.locale && /^en-US/.test(o.locale)); y = +m[3]; mo = dmy ? +m[2] : +m[1]; d = dmy ? +m[1] : +m[2]; }
    else if ((m = /^(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{4})$/.exec(t))) { y = +m[3]; mo = 'janfebmaraprmayjunjulaugsepoctnovdec'.indexOf(m[2].toLowerCase()) / 3 + 1; d = +m[1]; }
    const ok = y && mo >= 1 && mo <= 12 && d >= 1 && d <= new Date(Date.UTC(y, mo, 0)).getUTCDate();
    return ok ? { value: String(y).padStart(4, '0') + '-' + String(mo).padStart(2, '0') + '-' + String(d).padStart(2, '0'), error: null } : { value: undefined, error: L + ': "' + t + '" is not a date (write 2026-11-06 or 6 Nov 2026)' };
  }
  if (ed === 'select') {
    const opts = (col.options || []).map(opt), hit = opts.find((x) => x.label.toLowerCase() === t.toLowerCase() || String(x.value).toLowerCase() === t.toLowerCase());
    return hit ? { value: hit.value, error: null } : { value: undefined, error: L + ': "' + t + '" is not one of ' + opts.map((x) => x.label).slice(0, 8).join(', ') + (opts.length > 8 ? ', …' : '') };
  }
  return { value: t, error: null };
}

/**
 * A value's problem in words for a column, or null: required, min, max, maxLength, options, and the column's validate(value, row)
 * (which answers a message, true or null for fine, false for "is not valid").
 * @param {Object} col
 * @param {*} v
 * @param {Object} row the row as it would be
 * @returns {?string}
 */
export function validateCell(col, v, row) {
  const L = col.header, blank = v === null || v === undefined || v === '';
  if (blank) { return col.required ? L + ' is required' : null; }
  if (typeof v === 'number') {
    if (col.min !== undefined && v < col.min) { return L + ' must be at least ' + col.min; }
    if (col.max !== undefined && v > col.max) { return L + ' must be at most ' + col.max; }
  }
  if (typeof v === 'string' && col.maxLength && v.length > col.maxLength) { return L + ' may have at most ' + col.maxLength + ' characters'; }
  if (col.options && editorOf(col) === 'select' && !(col.options.map(opt).some((x) => x.value === v))) { return L + ' must be one of the options'; }
  if (col.validate) {
    const r = col.validate(v, row);
    if (typeof r === 'string' && r) { return r; }
    if (r === false) { return L + ' is not valid'; }
  }
  return null;
}

/**
 * Tab-separated text (what Excel and the grid copy) as rows of cells: quoted cells may hold tabs, new lines and doubled quotes; a
 * final line break is not an empty row.
 * @param {string} text
 * @returns {string[][]}
 */
export function parseTsv(text) {
  const s = String(text || '').replace(/\r\n?/g, '\n'), rows = [];
  let row = [], cell = '', i = 0, quoted = false, start = true;
  while (i < s.length) {
    const ch = s[i];
    if (quoted) {
      if (ch === '"' && s[i + 1] === '"') { cell += '"'; i += 2; continue; }
      if (ch === '"') { quoted = false; i++; continue; }
      cell += ch; i++; continue;
    }
    if (ch === '"' && start) { quoted = true; start = false; i++; continue; }
    if (ch === '\t') { row.push(cell); cell = ''; start = true; i++; continue; }
    if (ch === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; start = true; i++; continue; }
    cell += ch; start = false; i++;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows;
}

/** @private a row as a plain object (rows read from Arrow are views) */
function plain(row) { return row && typeof row.toObject === 'function' ? row.toObject() : Object.assign({}, row); }

/**
 * The editing of a grid model: what may change, transactions, undo and redo, paste, write-back and four eyes.
 */
export class GridEditing {
  /**
   * @param {Object} grid the GridModel
   * @param {{writeBack: function(EditTransaction): Promise<{status: string, proposalId: string, message: string}>, fourEyes: boolean,
   *   locale: string, undoLimit: number}} [o] writeBack: the server hears each transaction (applied, refused, or proposed); fourEyes: every
   *   transaction is a proposal until resolveProposal; undoLimit default 100
   */
  constructor(grid, o) {
    this.grid = grid;
    this.o = Object.assign({ undoLimit: 100 }, o);
    this.undoStack = [];
    this.redoStack = [];
    this.seq = 0;
    this.marks = new Map();
    this.proposals = new Map();
  }

  /**
   * Whether a shown cell may be edited.
   * @param {number} r
   * @param {number} c
   * @returns {boolean}
   */
  canEdit(r, c) {
    const g = this.grid, x = g.rowAt(r), col = g.shownColumns()[c];
    if (!x || x.kind !== 'leaf' || !col || g.pivotMode || g.serverSide || col.valueGetter || col.rowHeader) { return false; }
    if (this.marks.get(x.id + '\u001f' + col.id) === 'proposed') { return false; }
    return typeof col.editable === 'function' ? !!col.editable(x.row) : !!col.editable;
  }

  /**
   * A cell's mark: edited (changed here), proposed (waiting for a second person), or null.
   * @param {string} rowId
   * @param {string} colId
   * @returns {?string}
   */
  markOf(rowId, colId) { return this.marks.get(rowId + '\u001f' + colId) || null; }

  /**
   * The edits of shown cells as a transaction's cells, validated: {edits, problems}.
   * @param {Array<{r: number, c: number, value: *}>} cells
   * @returns {{edits: CellEdit[], problems: Array<{r: number, c: number, message: string}>}}
   */
  plan(cells) {
    const g = this.grid, edits = [], problems = [], next = new Map();
    cells.forEach((x) => {
      if (!this.canEdit(x.r, x.c)) { problems.push({ r: x.r, c: x.c, message: 'This cell cannot be edited' }); return; }
      const row = g.rowAt(x.r), col = g.shownColumns()[x.c];
      const base = next.get(row.id) || plain(row.row);
      base[col.field] = x.value;
      const why = validateCell(col, x.value, base);
      if (why) { problems.push({ r: x.r, c: x.c, message: why }); return; }
      next.set(row.id, base);
      const old = row.row[col.field];
      if (old === x.value || (old === undefined && x.value === null)) { return; }
      edits.push({ rowId: row.id, colId: col.id, field: col.field, old: old === undefined ? null : old, value: x.value });
    });
    return { edits: edits, problems: problems };
  }

  /**
   * Applies cell edits as one transaction (validated first: any problem refuses them all), tells the write-back hook.
   * @param {Array<{r: number, c: number, value: *}>} cells
   * @param {string} [source='edit']
   * @returns {Promise<EditTransaction>} rejects with .problems when a cell is not valid, or the server's refusal
   */
  commit(cells, source) {
    const p = this.plan(cells);
    if (p.problems.length) { const e = new Error(p.problems[0].message); e.problems = p.problems; return Promise.reject(e); }
    return this.apply(p.edits, source || 'edit');
  }

  /**
   * Applies prepared edits (by row and column id) as one transaction.
   * @param {CellEdit[]} edits
   * @param {string} source
   * @returns {Promise<EditTransaction>}
   */
  apply(edits, source) {
    const g = this.grid;
    if (!edits.length) { return Promise.resolve({ id: 0, source: source, edits: [], status: 'applied', proposalId: null }); }
    const tx = { id: ++this.seq, source: source, edits: edits, status: 'pending', proposalId: null };
    this.write(edits, 'value');
    edits.forEach((e) => this.marks.set(e.rowId + '\u001f' + e.colId, this.o.fourEyes ? 'proposed' : 'edited'));
    if (source !== 'undo' && source !== 'redo') { this.undoStack.push(tx); if (this.undoStack.length > this.o.undoLimit) { this.undoStack.shift(); } this.redoStack = []; }
    edits.forEach((e) => g.live.emit('cellValueChanged', { rowId: e.rowId, colId: e.colId, old: e.old, value: e.value, source: source, transaction: tx.id }));
    const settle = (res) => {
      res = res || {};
      if (res.status === 'refused') { return this.refuse(tx, res.message || 'The server refused the change'); }
      if (res.status === 'proposed' || this.o.fourEyes) {
        tx.status = 'proposed';
        tx.proposalId = res.proposalId || ('P-' + tx.id);
        this.proposals.set(tx.proposalId, tx);
        edits.forEach((e) => this.marks.set(e.rowId + '\u001f' + e.colId, 'proposed'));
      } else { tx.status = 'applied'; }
      g.live.emit('editTransaction', this.describe(tx));
      g.events.emit('changed', { what: 'edit' });
      return tx;
    };
    if (!this.o.writeBack) { return Promise.resolve(settle({ status: this.o.fourEyes ? 'proposed' : 'applied' })); }
    return Promise.resolve().then(() => this.o.writeBack(this.describe(tx))).then(settle, (err) => this.refuse(tx, err && err.message ? err.message : String(err)));
  }

  /** @private the server said no: the old values back, the transaction off the undo stack */
  refuse(tx, message) {
    this.write(tx.edits, 'old');
    tx.edits.forEach((e) => this.marks.delete(e.rowId + '\u001f' + e.colId));
    this.undoStack = this.undoStack.filter((t) => t !== tx);
    tx.status = 'refused';
    tx.message = message;
    this.grid.live.emit('editTransaction', this.describe(tx));
    this.grid.events.emit('changed', { what: 'edit' });
    const e = new Error(message);
    e.transaction = tx;
    throw e;
  }

  /** @private the edits' values (or old values) into the rows, one update per row */
  write(edits, which) {
    const g = this.grid, rows = new Map();
    edits.forEach((e) => {
      const cur = rows.get(e.rowId) || plain(g.rowModel.getRowById(e.rowId));
      cur[e.field] = e[which];
      rows.set(e.rowId, cur);
    });
    g.live.apply({ update: Array.from(rows.values()) });
  }

  /** @private a transaction as listeners and the server see it */
  describe(tx) { return { id: tx.id, source: tx.source, status: tx.status, proposalId: tx.proposalId, message: tx.message || null, edits: tx.edits.map((e) => Object.assign({}, e)) }; }

  /**
   * Takes the last transaction back (a new transaction, source undo).
   * @returns {Promise<?EditTransaction>}
   */
  undo() {
    const tx = this.undoStack.pop();
    if (!tx) { return Promise.resolve(null); }
    this.redoStack.push(tx);
    const back = tx.edits.slice().reverse().map((e) => Object.assign({}, e, { old: e.value, value: e.old }));
    return this.apply(back, 'undo').then((t) => { back.forEach((e) => { if (this.marks.get(e.rowId + '\u001f' + e.colId) === 'edited') { this.marks.delete(e.rowId + '\u001f' + e.colId); } }); this.grid.live.emit('editUndone', { transaction: tx.id }); return t; });
  }

  /**
   * Does the last undone transaction again.
   * @returns {Promise<?EditTransaction>}
   */
  redo() {
    const tx = this.redoStack.pop();
    if (!tx) { return Promise.resolve(null); }
    this.undoStack.push(tx);
    return this.apply(tx.edits.map((e) => Object.assign({}, e)), 'redo').then((t) => { this.grid.live.emit('editRedone', { transaction: tx.id }); return t; });
  }

  /**
   * Whether there is something to undo and to redo.
   * @returns {{undo: number, redo: number}}
   */
  history() { return { undo: this.undoStack.length, redo: this.redoStack.length }; }

  /**
   * A four-eyes proposal decided: approved keeps the values (marked edited), rejected puts the old ones back.
   * @param {string} proposalId
   * @param {('approved'|'rejected')} decision
   * @returns {boolean} whether there was such a proposal
   */
  resolveProposal(proposalId, decision) {
    const tx = this.proposals.get(proposalId);
    if (!tx) { return false; }
    this.proposals.delete(proposalId);
    if (decision === 'rejected') { this.write(tx.edits, 'old'); tx.edits.forEach((e) => this.marks.delete(e.rowId + '\u001f' + e.colId)); this.undoStack = this.undoStack.filter((t) => t !== tx); }
    else { tx.edits.forEach((e) => this.marks.set(e.rowId + '\u001f' + e.colId, 'edited')); }
    tx.status = decision === 'rejected' ? 'rejected' : 'applied';
    this.grid.live.emit('proposalResolved', { proposalId: proposalId, decision: decision, transaction: this.describe(tx) });
    this.grid.events.emit('changed', { what: 'edit' });
    return true;
  }

  /**
   * Pasted text (tab-separated) from a cell down and right; one value over a larger range fills it. Cells that cannot be edited are
   * skipped; values that do not read or are not valid are left out and listed. One transaction.
   * @param {string} text
   * @param {{r: number, c: number, r2: number, c2: number}} at the focus or the range (r2, c2: its far corner)
   * @returns {Promise<{transaction: ?EditTransaction, pasted: number, skipped: number, problems: Array<{r: number, c: number, message: string}>}>}
   */
  paste(text, at) {
    const g = this.grid, grid = parseTsv(text), R = g.rowCount(), C = g.shownColumns().length, cells = [], problems = [];
    if (!grid.length) { return Promise.resolve({ transaction: null, pasted: 0, skipped: 0, problems: [] }); }
    const fill = grid.length === 1 && grid[0].length === 1 && at.r2 !== undefined && (at.r2 > at.r || at.c2 > at.c);
    const rows = fill ? at.r2 - at.r + 1 : grid.length, cols = fill ? at.c2 - at.c + 1 : Math.max.apply(null, grid.map((x) => x.length));
    let skipped = 0;
    for (let i = 0; i < rows && at.r + i < R; i++) {
      for (let j = 0; j < cols && at.c + j < C; j++) {
        const text1 = fill ? grid[0][0] : (grid[i] || [])[j];
        if (text1 === undefined) { continue; }
        const r = at.r + i, c = at.c + j;
        if (!this.canEdit(r, c)) { skipped++; continue; }
        const p = parseCellText(g.shownColumns()[c], text1, { locale: this.o.locale });
        if (p.error) { problems.push({ r: r, c: c, message: p.error }); continue; }
        cells.push({ r: r, c: c, value: p.value });
      }
    }
    const plan = this.plan(cells);
    plan.problems.forEach((x) => problems.push(x));
    return this.apply(plan.edits, 'paste').then((tx) => ({ transaction: tx.edits.length ? tx : null, pasted: plan.edits.length, skipped: skipped, problems: problems }));
  }

  /**
   * Empties the editable cells of a range (Delete); a required cell is left as it is and listed.
   * @param {{r1: number, c1: number, r2: number, c2: number}} range
   * @returns {Promise<{transaction: ?EditTransaction, problems: Array}>}
   */
  clear(range) {
    const cells = [];
    for (let r = range.r1; r <= range.r2; r++) { for (let c = range.c1; c <= range.c2; c++) { if (this.canEdit(r, c)) { cells.push({ r: r, c: c, value: null }); } } }
    const plan = this.plan(cells);
    return this.apply(plan.edits, 'clear').then((tx) => ({ transaction: tx.edits.length ? tx : null, problems: plan.problems }));
  }
}
