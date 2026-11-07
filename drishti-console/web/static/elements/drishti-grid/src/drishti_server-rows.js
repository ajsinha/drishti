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
/* The server-side row model: the rows stay on the server and come in blocks as they scroll into view. A block request names its rows
   (startRow, endRow), the sort and filter models, the row groups and the group keys of the level it is under (a group row opens a
   level of its own, with its own blocks), the values and the pivot (the request a pivot server answers), and carries an AbortSignal:
   a sort or filter change aborts what is in flight and starts again. Blocks are cached (least recently used are dropped past
   maxBlocks); refresh() drops them all (or one level) and asks again. A row not loaded yet is a placeholder (loading: true). */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { SEP } from './drishti_pivot.js';

/**
 * @typedef {Object} BlockRequest
 * @property {number} startRow the first row, inclusive
 * @property {number} endRow the last, exclusive
 * @property {Array<{colId: string, sort: string}>} sortModel
 * @property {Object<string, Object>} filterModel
 * @property {string} quickFilter
 * @property {string[]} rowGroupCols the row group columns, outermost first
 * @property {string[]} groupKeys the members of the level asked for (its parents' values); [] for the top
 * @property {Array<{colId: string, aggFunc: string}>} valueCols
 * @property {string[]} pivotCols
 * @property {boolean} pivotMode
 * @property {AbortSignal} [signal]
 */

/**
 * @typedef {Object} BlockAnswer
 * @property {Object[]} rows the rows of the block (a group level's rows are its members: {[groupCol]: value, childCount, ...figures})
 * @property {number} [rowCount] how many rows the level has, when known (else the model grows as blocks come back short)
 */

/**
 * A data source: the one function the model calls.
 * @typedef {Object} Datasource
 * @property {function(BlockRequest): Promise<BlockAnswer>} getRows
 */

function Level(keys) { this.keys = keys; this.blocks = new Map(); this.rowCount = null; this.knownEnd = 0; }

/**
 * The server-side row model.
 */
export class ServerRowModel {
  /**
   * @param {{datasource: Datasource, blockSize: number, maxBlocks: number, getRowId: function(Object, number, string[]): string}} o
   *   blockSize default 100; maxBlocks default 50 (5,000 rows held)
   */
  constructor(o) {
    if (!o || !o.datasource || typeof o.datasource.getRows !== 'function') { throw new Error('a server-side row model needs a datasource with getRows(request)'); }
    this.datasource = o.datasource;
    this.blockSize = o.blockSize || 100;
    this.maxBlocks = o.maxBlocks || 50;
    this.getRowId = o.getRowId || ((r, i, keys) => (r && r.id !== undefined ? String(r.id) : keys.concat([String(i)]).join(SEP)));
    /** @type {EventBus} rowsChanged {reason}, blockLoaded {keys, block}, loadFailed {error, keys, block} */
    this.events = new EventBus();
    this.sortModel = [];
    this.filterModel = {};
    this.quickFilter = '';
    this.rowGroupCols = [];
    this.valueCols = [];
    this.pivotCols = [];
    this.pivotMode = false;
    this.expanded = new Set();
    this.stamp = 0;
    this.lru = [];
    this.inFlight = new Map();
    this.reset();
  }

  /** @private */
  reset() {
    this.inFlight.forEach((x) => x.ctl && x.ctl.abort());
    this.inFlight.clear();
    this.levels = new Map([['', new Level([])]]);
    this.lru = [];
    this.stamp++;
    this.display = null;
  }

  /** Sorts by a sort model: what is in flight is aborted, the blocks asked again. @param {Array} m @returns {void} */
  setSortModel(m) { this.sortModel = (m || []).slice(); this.reset(); this.events.emit('rowsChanged', { reason: 'sort' }); }
  /** Filters by a filter model: what is in flight is aborted, the blocks asked again. @param {Object} m @returns {void} */
  setFilterModel(m) { this.filterModel = Object.assign({}, m); this.reset(); this.events.emit('rowsChanged', { reason: 'filter' }); }
  /** Filters by words (the server decides where it looks). @param {string} q @returns {void} */
  setQuickFilter(q) { this.quickFilter = q || ''; this.reset(); this.events.emit('rowsChanged', { reason: 'filter' }); }

  /**
   * Sets the row groups, values and pivot the requests carry.
   * @param {{rowGroupCols: string[], valueCols: Array, pivotCols: string[], pivotMode: boolean}} s
   * @returns {void}
   */
  setGrouping(s) {
    this.rowGroupCols = (s.rowGroupCols || []).slice();
    this.valueCols = (s.valueCols || []).slice();
    this.pivotCols = (s.pivotCols || []).slice();
    this.pivotMode = !!s.pivotMode;
    this.expanded.clear();
    this.reset();
    this.events.emit('rowsChanged', { reason: 'group' });
  }

  /**
   * Opens or closes a group row (its level loads when it comes into view).
   * @param {string[]} keys the group's members from the top
   * @param {boolean} [open]
   * @returns {void}
   */
  setExpanded(keys, open) {
    const k = keys.join(SEP), now = open === undefined ? !this.expanded.has(k) : open;
    if (now) { this.expanded.add(k); if (!this.levels.has(k)) { this.levels.set(k, new Level(keys.slice())); } } else { this.expanded.delete(k); }
    this.display = null;
    this.events.emit('rowsChanged', { reason: 'expand' });
  }

  /**
   * Drops cached blocks and asks again: every level, or one level and those under it.
   * @param {{keys: string[], purge: boolean}} [o] keys: the level; purge default true (forget the row count too)
   * @returns {void}
   */
  refresh(o) {
    if (!o || !o.keys) { const exp = new Set(this.expanded); this.reset(); exp.forEach((k) => this.levels.set(k, new Level(k ? k.split(SEP) : []))); }
    else {
      const k = o.keys.join(SEP);
      this.levels.forEach((lv, key) => {
        if (key === k || key.indexOf(k ? k + SEP : '') === 0) {
          lv.blocks.clear();
          if (!o || o.purge !== false) { lv.rowCount = null; lv.knownEnd = 0; }
        }
      });
      this.lru = this.lru.filter((x) => this.levels.get(x.level).blocks.has(x.block));
      this.display = null;
    }
    this.events.emit('rowsChanged', { reason: 'refresh' });
  }

  /**
   * Forgets one block (its rows are asked again when they are in view); the row count is kept.
   * @param {string[]} keys the level's group keys ([] for the top)
   * @param {number} block
   * @returns {boolean} whether the block was held
   */
  invalidateBlock(keys, block) {
    const lv = this.levels.get(keys.join(SEP));
    const had = !!(lv && lv.blocks.delete(block));
    if (had) { this.lru = this.lru.filter((x) => !(x.level === keys.join(SEP) && x.block === block)); this.display = null; this.events.emit('rowsChanged', { reason: 'invalidate' }); }
    return had;
  }

  /**
   * Forgets everything held (blocks, counts, open groups' levels kept open) and aborts what is in flight: the next draw asks again.
   * @returns {void}
   */
  purge() { this.refresh(); }

  /**
   * A server's "data changed" notice (drishti-data's live feed): {purge: true} forgets everything; {keys} a level (and those under it);
   * {keys, blocks} only those blocks; {rows: [ids]} the blocks holding those rows. The rows on screen are asked again at the next draw.
   * @param {{purge: boolean, keys: string[], blocks: number[], rows: string[]}} notice
   * @returns {string} what was done: purge, level, blocks, rows or nothing
   */
  dataChanged(notice) {
    const n = notice || {};
    if (n.purge || (!n.keys && !n.rows)) { this.purge(); return 'purge'; }
    if (n.rows) {
      const ids = new Set(n.rows.map(String));
      let hit = 0;
      this.levels.forEach((lv, key) => lv.blocks.forEach((b, bi) => {
        if (b.rows.some((r, i) => ids.has(this.getRowId(r, bi * this.blockSize + i, lv.keys)))) { this.invalidateBlock(lv.keys, bi); hit++; }
      }));
      return hit ? 'rows' : 'nothing';
    }
    if (n.blocks) { n.blocks.forEach((b) => this.invalidateBlock(n.keys, b)); return 'blocks'; }
    this.refresh({ keys: n.keys });
    return 'level';
  }

  /** @private how many rows a level shows: its count when known, else what is loaded plus one block to come */
  levelCount(lv) { return lv.rowCount !== null ? lv.rowCount : lv.knownEnd + this.blockSize; }

  /** @private the display: [levelKey, row index in the level] pairs, groups opened inline */
  flatten() {
    if (this.display) { return this.display; }
    const out = [];
    const walk = (key) => {
      const lv = this.levels.get(key), n = this.levelCount(lv), depth = lv.keys.length;
      for (let i = 0; i < n; i++) {
        out.push([key, i]);
        if (depth < this.rowGroupCols.length) {
          const r = this.blockRow(lv, i);
          if (r && !r.loading) {
            const childKeys = lv.keys.concat([String(r[this.rowGroupCols[depth]])]), ck = childKeys.join(SEP);
            if (this.expanded.has(ck)) { if (!this.levels.has(ck)) { this.levels.set(ck, new Level(childKeys)); } walk(ck); }
          }
        }
      }
    };
    walk('');
    this.display = out;
    return out;
  }

  /** @private */
  blockRow(lv, i) {
    const b = lv.blocks.get(Math.floor(i / this.blockSize));
    if (!b) { return null; }
    const r = b.rows[i % this.blockSize];
    return r === undefined ? null : r;
  }

  /**
   * How many rows are displayed (an estimate while a level's count is not known).
   * @returns {number}
   */
  rowCount() { return this.expanded.size ? this.flatten().length : this.levelCount(this.levels.get('')); }

  /** @private a display index as [level key, row in the level]: no open group, no flattening (a million rows cost nothing) */
  at(i) { return this.expanded.size ? this.flatten()[i] : ['', i]; }

  /**
   * The row at a display index: the data when its block is in, else a placeholder ({loading: true}); asks for the block.
   * @param {number} i
   * @returns {?Object} {kind: 'group'|'leaf'|'loading', id, depth, row, keys, expanded, loading}
   */
  rowAt(i) {
    if (i < 0 || i >= this.rowCount()) { return null; }
    const [key, n] = this.at(i), lv = this.levels.get(key), depth = lv.keys.length;
    const r = this.blockRow(lv, n);
    if (!r) { this.ensureBlock(key, Math.floor(n / this.blockSize)); return { kind: 'loading', id: 'loading:' + key + ':' + n, depth: depth, row: null, loading: true }; }
    if (depth < this.rowGroupCols.length) {
      const keys = lv.keys.concat([String(r[this.rowGroupCols[depth]])]);
      return { kind: 'group', id: 'group:' + keys.join(SEP), depth: depth, row: r, keys: keys, field: this.rowGroupCols[depth], value: r[this.rowGroupCols[depth]],
        count: r.childCount, expanded: this.expanded.has(keys.join(SEP)) };
    }
    return { kind: 'leaf', id: this.getRowId(r, n, lv.keys), depth: depth, row: r, keys: lv.keys };
  }

  /**
   * Asks for the blocks of a range of display rows (a renderer calls it with the viewport's rows).
   * @param {number} first
   * @param {number} last inclusive
   * @returns {Promise<void>} when those blocks are in (or failed)
   */
  ensureRange(first, last) {
    const count = this.rowCount(), waits = [];
    const seen = new Set();
    for (let i = Math.max(0, first); i <= Math.min(last, count - 1); i++) {
      const [key, n] = this.at(i), b = Math.floor(n / this.blockSize), id = key + '#' + b;
      if (seen.has(id)) { continue; }
      seen.add(id);
      waits.push(this.ensureBlock(key, b));
    }
    return Promise.all(waits).then(() => undefined);
  }

  /** @private */
  request(lv, block, signal) {
    return { startRow: block * this.blockSize, endRow: (block + 1) * this.blockSize, sortModel: this.sortModel.slice(), filterModel: Object.assign({}, this.filterModel),
      quickFilter: this.quickFilter, rowGroupCols: this.rowGroupCols.slice(), groupKeys: lv.keys.slice(), valueCols: this.valueCols.slice(),
      pivotCols: this.pivotCols.slice(), pivotMode: this.pivotMode, signal: signal };
  }

  /**
   * Loads one block of a level (once; a block in flight is shared).
   * @param {string} levelKey the level's group keys joined
   * @param {number} block
   * @returns {Promise<void>}
   */
  ensureBlock(levelKey, block) {
    const lv = this.levels.get(levelKey);
    if (!lv || lv.blocks.has(block)) { return Promise.resolve(); }
    const id = levelKey + '#' + block;
    if (this.inFlight.has(id)) { return this.inFlight.get(id).p; }
    const stamp = this.stamp, ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const p = Promise.resolve().then(() => this.datasource.getRows(this.request(lv, block, ctl ? ctl.signal : undefined))).then((a) => {
      this.inFlight.delete(id);
      if (stamp !== this.stamp) { return; }                         // the sort or filter changed meanwhile: a stale answer is dropped
      const rows = (a && a.rows) || [];
      lv.blocks.set(block, { rows: rows });
      this.touch(levelKey, block);
      const end = block * this.blockSize + rows.length;
      lv.knownEnd = Math.max(lv.knownEnd, end);
      if (a && typeof a.rowCount === 'number') { lv.rowCount = a.rowCount; } else if (rows.length < this.blockSize) { lv.rowCount = end; }
      this.display = null;
      this.events.emit('blockLoaded', { keys: lv.keys, block: block });
      this.events.emit('rowsChanged', { reason: 'block' });
    }, (e) => {
      this.inFlight.delete(id);
      if (stamp !== this.stamp || (e && (e.aborted || e.name === 'AbortError'))) { return; }
      this.events.emit('loadFailed', { error: e, keys: lv.keys, block: block });
    });
    this.inFlight.set(id, { p: p, ctl: ctl });
    return p;
  }

  /** @private least recently used blocks dropped past maxBlocks */
  touch(level, block) {
    this.lru = this.lru.filter((x) => !(x.level === level && x.block === block));
    this.lru.push({ level: level, block: block });
    while (this.lru.length > this.maxBlocks) {
      const old = this.lru.shift(), lv = this.levels.get(old.level);
      if (lv) { lv.blocks.delete(old.block); }
    }
  }

  /**
   * How many blocks are held, and how many are being asked for.
   * @returns {{blocks: number, inFlight: number}}
   */
  cacheStats() { return { blocks: this.lru.length, inFlight: this.inFlight.size }; }
}

/**
 * A data source over rows held in memory (tests, demos, and a page that wants the server-side model's paging over local data): sorts,
 * filters and groups as a server would, with an optional delay.
 * @param {Object[]} rows
 * @param {{delay: number, columns: import('./drishti_columns.js').ColumnModel}} [o]
 * @returns {Datasource & {calls: BlockRequest[]}}
 */
export function memoryDatasource(rows, o) {
  o = o || {};
  const calls = [];
  return {
    calls: calls,
    getRows(req) {
      calls.push(req);
      return new Promise((resolve, reject) => {
        const run = () => {
          if (req.signal && req.signal.aborted) { const e = new Error('aborted'); e.aborted = true; reject(e); return; }
          let list = req.groupKeys.length ? rows.filter((r) => req.groupKeys.every((k, i) => String(r[req.rowGroupCols[i]]) === k)) : rows;
          Object.keys(req.filterModel || {}).forEach((k) => {
            const f = req.filterModel[k];
            if (f && f.type === 'set' && f.values) { list = list.filter((r) => f.values.map(String).indexOf(String(r[k])) >= 0); }
          });
          if (req.groupKeys.length < req.rowGroupCols.length) {
            const col = req.rowGroupCols[req.groupKeys.length], m = new Map();
            list.forEach((r) => { const k = String(r[col]); const g = m.get(k) || { [col]: r[col], childCount: 0 }; g.childCount++; m.set(k, g); });
            list = Array.from(m.values()).sort((a, b) => String(a[col]).localeCompare(String(b[col])));
          } else if (req.sortModel && req.sortModel.length) {
            const s = req.sortModel[0], dir = s.sort === 'desc' ? -1 : 1;
            list = list.slice().sort((a, b) => (a[s.colId] < b[s.colId] ? -dir : a[s.colId] > b[s.colId] ? dir : 0));
          }
          resolve({ rows: list.slice(req.startRow, req.endRow), rowCount: list.length });
        };
        if (o.delay) { setTimeout(run, o.delay); } else { run(); }
      });
    }
  };
}
