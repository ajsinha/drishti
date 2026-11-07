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
/* The layout canvas's model, without a page: the items of a design on a grid and every change to them as one undoable step. Two
   kinds of layout share it. A **grid** layout places each item at {x, y, w, h} (a report page: snap, settle, align, distribute,
   z-order, groups). A **flow** layout keeps items in order inside named areas (a screen's main and side columns), each with a span in
   columns and a height in rows (0: the content's). Both have copy and paste, and **breakpoints**: a phone's (or a tablet's) own order,
   hidden items and heights, kept apart from the full layout. A host that keeps its own document (and its own undo) passes stack: null
   and hears each step as a "commit" event with the rectangles before and after. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';
import { CommandStack } from './drishti_commands.js';
import { clampRect, settle as settleRects, alignRects, distributeRects, clampSpan, clampHeight, bounds } from './drishti_layout-rules.js';

/**
 * An item of a layout.
 * @typedef {Object} LayoutItem
 * @property {string} id
 * @property {number} [x] grid: first column
 * @property {number} [y] grid: first row
 * @property {number} [w] grid: columns
 * @property {number} [h] grid: rows (flow: the height in rows, 0 for the content's)
 * @property {number} [z] stacking order (higher is in front)
 * @property {string} [group] items of one group move, copy and delete together
 * @property {string} [area] flow: the area it sits in
 * @property {number} [span] flow: its width in columns
 * @property {string} [label] what a screen reader and the outline call it
 * @property {Object} [data] the host's own (a visual, a panel): copied with the item, never read here
 */

/**
 * @typedef {Object} LayoutOptions
 * @property {('grid'|'flow')} [mode='grid']
 * @property {number} [cols=12]
 * @property {number} [maxH=40] grid: the tallest item, in rows (flow: 24)
 * @property {number} [minW=1]
 * @property {number} [minH=1]
 * @property {number} [minSpan=3] flow: the narrowest item
 * @property {boolean} [settle=true] grid: what a move overlaps goes down
 * @property {string[]} [areas=['main']] flow: the areas, in order
 * @property {(CommandStack|null)} [stack] the undo stack (default: its own; null: the host keeps undo and hears "commit")
 * @property {function(string): string} [newId] an id for a pasted item (default: the id with -2, -3 ...)
 */

const copy = (x) => JSON.parse(JSON.stringify(x));

/**
 * The layout model. Events: change {label, ids} after every step, undo and redo; commit {label, before, after, ids} for a host that
 * keeps its own undo.
 */
export class LayoutModel {
  /**
   * @param {LayoutOptions} [o]
   */
  constructor(o) {
    this.o = Object.assign({ mode: 'grid', cols: 12, minW: 1, minH: 1, minSpan: 3, settle: true, areas: ['main'] }, o);
    if (!this.o.maxH) { this.o.maxH = this.o.mode === 'flow' ? 24 : 40; }
    this.stack = this.o.stack === undefined ? new CommandStack() : this.o.stack;
    this.events = new EventBus();
    this.state = { items: [], breakpoints: {} };
    this.clipboard = null;
  }

  /**
   * Listens to one of its events; answers the function that stops listening.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * Loads items (and breakpoints) without an undo step: a document opened, a host's model redrawn.
   * @param {LayoutItem[]} items
   * @param {Object<string, {order: string[], hidden: string[], heights: Object<string, number>}>} [breakpoints]
   * @returns {void}
   */
  load(items, breakpoints) {
    this.state = { items: copy(items || []), breakpoints: copy(breakpoints || {}) };
    this.events.emit('change', { label: 'load', ids: this.state.items.map((i) => i.id) });
  }

  /**
   * The items, in reading order (grid: top to bottom, left to right; flow: area by area, in order).
   * @returns {LayoutItem[]}
   */
  list() {
    const items = this.state.items.slice();
    if (this.o.mode === 'flow') {
      const areas = this.o.areas;
      return items.map((it, i) => ({ it, i })).sort((a, b) => (areas.indexOf(a.it.area || areas[0]) - areas.indexOf(b.it.area || areas[0])) || a.i - b.i).map((x) => x.it);
    }
    return items.sort((a, b) => a.y - b.y || a.x - b.x);
  }

  /**
   * An item by its id, or null.
   * @param {string} id
   * @returns {?LayoutItem}
   */
  get(id) { return this.state.items.find((i) => i.id === id) || null; }

  /**
   * The ids in reading order.
   * @returns {string[]}
   */
  ids() { return this.list().map((i) => i.id); }

  /**
   * The ids with every member of their groups.
   * @param {string[]} ids
   * @returns {string[]}
   */
  expand(ids) {
    const groups = new Set(ids.map((id) => (this.get(id) || {}).group).filter(Boolean));
    return this.state.items.filter((i) => ids.indexOf(i.id) >= 0 || (i.group && groups.has(i.group))).map((i) => i.id);
  }

  /**
   * One step: fn changes this.state.items in place; kept on the stack (or handed to the host as "commit").
   * @param {string} label
   * @param {function(LayoutItem[]): (string[]|void)} fn answers the ids it changed
   * @param {{mergeKey: string}} [o]
   * @returns {boolean} whether anything changed
   */
  commit(label, fn, o) {
    const before = copy(this.state);
    const ids = fn(this.state.items) || [];
    const after = copy(this.state);
    if (JSON.stringify(before) === JSON.stringify(after)) { return false; }
    const set = (s) => { this.state = copy(s); this.events.emit('change', { label, ids }); };
    if (this.stack) { this.stack.push({ label, mergeKey: o && o.mergeKey, redo: () => set(after), undo: () => set(before) }); }
    this.events.emit('commit', { label, before: before.items, after: after.items, ids, breakpoints: after.breakpoints });
    this.events.emit('change', { label, ids });
    return true;
  }

  /** @private a rectangle inside the grid */
  fit(r) { return clampRect(r, this.o); }

  /**
   * Adds items (grid: placed inside the grid and settled; flow: at the end of their area or before an id).
   * @param {(LayoutItem|LayoutItem[])} items
   * @param {{label: string, before: string}} [o]
   * @returns {string[]} the ids added
   */
  add(items, o) {
    const list = (Array.isArray(items) ? items : [items]).map(copy);
    const taken = new Set(this.state.items.map((i) => i.id));
    list.forEach((it) => { if (!it.id || taken.has(it.id)) { throw new Error('an item needs a new id: ' + it.id); } taken.add(it.id); });
    this.commit((o && o.label) || (list.length === 1 ? 'Add ' + (list[0].label || 'an item') : 'Add ' + list.length + ' items'), (items0) => {
      list.forEach((it) => {
        if (this.o.mode === 'grid') {
          Object.assign(it, this.fit({ x: it.x || 0, y: it.y === undefined ? this.bottom() : it.y, w: it.w || 4, h: it.h || 3 }));
          it.z = it.z === undefined ? this.topZ() + 1 : it.z;
          items0.push(it);
          if (this.o.settle) { settleRects(items0, it); }
        } else {
          it.area = it.area || this.o.areas[0];
          const at = o && o.before ? items0.findIndex((x) => x.id === o.before) : -1;
          if (at >= 0) { items0.splice(at, 0, it); } else { items0.push(it); }
        }
      });
      return list.map((i) => i.id);
    });
    return list.map((i) => i.id);
  }

  /**
   * Removes items (with their groups).
   * @param {string[]} ids
   * @param {string} [label]
   * @returns {boolean}
   */
  remove(ids, label) {
    const all = this.expand(ids);
    return this.commit(label || (all.length === 1 ? 'Delete ' + ((this.get(all[0]) || {}).label || 'an item') : 'Delete ' + all.length + ' items'), (items) => {
      for (let i = items.length - 1; i >= 0; i--) { if (all.indexOf(items[i].id) >= 0) { items.splice(i, 1); } }
      Object.keys(this.state.breakpoints).forEach((b) => { const bp = this.state.breakpoints[b]; bp.order = (bp.order || []).filter((x) => all.indexOf(x) < 0); bp.hidden = (bp.hidden || []).filter((x) => all.indexOf(x) < 0); });
      return all;
    });
  }

  /**
   * The first free row under every item (grid).
   * @returns {number} the first free row under everything (grid)
   */
  bottom() { return this.state.items.reduce((m, i) => Math.max(m, (i.y || 0) + (i.h || 0)), 0); }

  /** @private */
  topZ() { return this.state.items.reduce((m, i) => Math.max(m, i.z || 0), 0); }

  /**
   * Moves items (and their groups) by whole grid steps, as one block that stays inside the grid.
   * @param {string[]} ids
   * @param {number} dx columns
   * @param {number} dy rows
   * @param {{mergeKey: string, label: string}} [o]
   * @returns {boolean}
   */
  moveBy(ids, dx, dy, o) {
    const all = this.expand(ids), items = all.map((id) => this.get(id)).filter(Boolean);
    const b = bounds(items);
    if (!b) { return false; }
    dx = Math.max(-b.x, Math.min(this.o.cols - (b.x + b.w), dx));
    dy = Math.max(-b.y, dy);
    return this.commit((o && o.label) || (all.length === 1 ? 'Move ' + (items[0].label || 'an item') : 'Move ' + all.length + ' items'), (list) => {
      list.filter((i) => all.indexOf(i.id) >= 0).forEach((i) => { i.x += dx; i.y += dy; });
      if (this.o.settle) { list.filter((i) => all.indexOf(i.id) >= 0).sort((p, q) => p.y - q.y).forEach((i) => settleRects(list.filter((x) => all.indexOf(x.id) < 0 || x === i), i)); }
      return all;
    }, o);
  }

  /**
   * Resizes items by whole grid steps (each inside the grid).
   * @param {string[]} ids
   * @param {number} dw
   * @param {number} dh
   * @param {{mergeKey: string, label: string}} [o]
   * @returns {boolean}
   */
  resizeBy(ids, dw, dh, o) {
    return this.commit((o && o.label) || 'Resize', (list) => {
      list.filter((i) => ids.indexOf(i.id) >= 0).forEach((i) => {
        const r = this.fit({ x: i.x, y: i.y, w: i.w + dw, h: i.h + dh });
        i.w = Math.min(r.w, this.o.cols - i.x); i.h = r.h;
        if (this.o.settle) { settleRects(list, i); }
      });
      return ids;
    }, o);
  }

  /**
   * Puts one item at a rectangle (a pointer's drop, a typed size).
   * @param {string} id
   * @param {import('./drishti_layout-rules.js').Rect} rect
   * @param {string} [label]
   * @returns {boolean}
   */
  setRect(id, rect, label) {
    return this.commit(label || 'Move', (list) => {
      const i = list.find((x) => x.id === id);
      if (!i) { return []; }
      Object.assign(i, this.fit(Object.assign({ x: i.x, y: i.y, w: i.w, h: i.h }, rect)));
      return [id].concat(this.o.settle ? settleRects(list, i) : []);
    });
  }

  /**
   * Lines items up on an edge of their box.
   * @param {string[]} ids
   * @param {('left'|'right'|'top'|'bottom'|'center'|'middle')} edge
   * @returns {boolean}
   */
  align(ids, edge) {
    const items = ids.map((id) => this.get(id)).filter(Boolean);
    if (items.length < 2) { return false; }
    const rects = alignRects(items, edge);
    return this.commit('Align ' + edge, (list) => { items.forEach((it, k) => { const i = list.find((x) => x.id === it.id); i.x = rects[k].x; i.y = rects[k].y; }); return ids; });
  }

  /**
   * Spreads three or more items evenly across ('x') or down ('y').
   * @param {string[]} ids
   * @param {('x'|'y')} axis
   * @returns {boolean}
   */
  distribute(ids, axis) {
    const items = ids.map((id) => this.get(id)).filter(Boolean);
    if (items.length < 3) { return false; }
    const rects = distributeRects(items, axis);
    return this.commit(axis === 'x' ? 'Distribute across' : 'Distribute down', (list) => { items.forEach((it, k) => { const i = list.find((x) => x.id === it.id); i[axis] = rects[k][axis]; }); return ids; });
  }

  /**
   * Changes the stacking order: 'front', 'back', 'forward' (one up) or 'backward'.
   * @param {string[]} ids
   * @param {('front'|'back'|'forward'|'backward')} how
   * @returns {boolean}
   */
  zOrder(ids, how) {
    const label = { front: 'Bring to front', back: 'Send to back', forward: 'Bring forward', backward: 'Send backward' }[how];
    if (!label) { throw new Error('no z-order ' + how); }
    return this.commit(label, (list) => {
      const ordered = list.slice().sort((a, b) => (a.z || 0) - (b.z || 0));
      const picked = ordered.filter((i) => ids.indexOf(i.id) >= 0), rest = ordered.filter((i) => ids.indexOf(i.id) < 0);
      let out;
      if (how === 'front') { out = rest.concat(picked); } else if (how === 'back') { out = picked.concat(rest); } else {
        out = ordered.slice();
        const step = how === 'forward' ? 1 : -1;
        const seq = how === 'forward' ? out.map((x, k) => k).reverse() : out.map((x, k) => k);
        seq.forEach((k) => { const j = k + step; if (ids.indexOf(out[k].id) >= 0 && j >= 0 && j < out.length && ids.indexOf(out[j].id) < 0) { const t = out[k]; out[k] = out[j]; out[j] = t; } });
      }
      out.forEach((i, k) => { i.z = k + 1; });
      return ids;
    });
  }

  /**
   * Makes the items one group (they move, copy and delete together).
   * @param {string[]} ids two or more
   * @returns {?string} the group's id
   */
  group(ids) {
    if (ids.length < 2) { return null; }
    let n = 1;
    const used = new Set(this.state.items.map((i) => i.group));
    while (used.has('g' + n)) { n++; }
    const gid = 'g' + n;
    this.commit('Group ' + ids.length + ' items', (list) => { list.filter((i) => ids.indexOf(i.id) >= 0).forEach((i) => { i.group = gid; }); return ids; });
    return gid;
  }

  /**
   * Breaks the groups of these items.
   * @param {string[]} ids
   * @returns {boolean}
   */
  ungroup(ids) {
    const all = this.expand(ids);
    return this.commit('Ungroup', (list) => { list.filter((i) => all.indexOf(i.id) >= 0).forEach((i) => { delete i.group; }); return all; });
  }

  /**
   * Copies items (with their groups) to the model's clipboard; answers the copy as text (JSON) for the system clipboard.
   * @param {string[]} ids
   * @returns {string}
   */
  copy(ids) {
    const all = this.expand(ids);
    this.clipboard = copy(this.state.items.filter((i) => all.indexOf(i.id) >= 0));
    return JSON.stringify({ drishtiLayout: 1, items: this.clipboard });
  }

  /**
   * Pastes copied items with new ids (and new groups): grid, one row lower than the copies (or at a point); flow, after the last one.
   * @param {(string|LayoutItem[])} [clip] the copy (text or items; default: the model's clipboard)
   * @param {{x: number, y: number}} [at]
   * @returns {string[]} the new ids
   */
  paste(clip, at) {
    let items = clip === undefined || clip === null ? this.clipboard : clip;
    if (typeof items === 'string') { try { items = JSON.parse(items).items; } catch (e) { return []; } }
    if (!Array.isArray(items) || !items.length) { return []; }
    const taken = new Set(this.state.items.map((i) => i.id)), groups = {};
    const newId = this.o.newId || ((id) => { const base = String(id).replace(/-\d+$/, ''); let n = 2; while (taken.has(base + '-' + n)) { n++; } return base + '-' + n; });
    const b = bounds(items.filter((i) => i.w !== undefined)) || { x: 0, y: 0 };
    const made = items.map((src) => {
      const it = copy(src);
      it.id = newId(src.id);
      taken.add(it.id);
      if (it.group) { groups[it.group] = groups[it.group] || 'p' + it.group + '-' + it.id; it.group = groups[it.group]; }
      if (this.o.mode === 'grid') {
        it.x = at ? at.x + (src.x - b.x) : src.x;
        it.y = at ? at.y + (src.y - b.y) : src.y + 1;
        delete it.z;
      }
      return it;
    });
    return this.add(made, { label: made.length === 1 ? 'Paste' : 'Paste ' + made.length + ' items' });
  }

  /**
   * Copies and pastes in one step (Ctrl+D).
   * @param {string[]} ids
   * @returns {string[]} the new ids
   */
  duplicate(ids) {
    const keep = this.clipboard;
    this.copy(ids);
    const items = this.clipboard;
    this.clipboard = keep;
    if (this.o.mode === 'grid') { const b = bounds(items); items.forEach((i) => { i.y += b.h - 1; }); }
    return this.paste(items);
  }

  // ---- flow ----------------------------------------------------------------------------------------------------------------------

  /**
   * The items of one area, in order (flow).
   * @param {string} area
   * @returns {LayoutItem[]}
   */
  inArea(area) { return this.state.items.filter((i) => (i.area || this.o.areas[0]) === area); }

  /**
   * Moves an item to an area, before another item (or to the area's end).
   * @param {string} id
   * @param {{area: string, before: (string|null), after: (string|null)}} to
   * @returns {boolean}
   */
  moveTo(id, to) {
    const it = this.get(id);
    if (!it || to.before === id || to.after === id) { return false; }
    return this.commit('Move ' + (it.label || 'an item'), (list) => {
      const i = list.findIndex((x) => x.id === id), item = list.splice(i, 1)[0];
      item.area = to.area || item.area || this.o.areas[0];
      let at = to.before ? list.findIndex((x) => x.id === to.before) : to.after ? list.findIndex((x) => x.id === to.after) + 1 : -1;
      if (at < 0 || (!to.before && !to.after)) { const inArea = list.filter((x) => (x.area || this.o.areas[0]) === item.area); at = inArea.length ? list.indexOf(inArea[inArea.length - 1]) + 1 : list.length; }
      list.splice(at, 0, item);
      return [id];
    });
  }

  /**
   * One place earlier (-1) or later (+1) in its area; false when it is already first or last.
   * @param {string} id
   * @param {number} step
   * @returns {boolean}
   */
  step(id, step) {
    const it = this.get(id);
    if (!it) { return false; }
    const area = this.inArea(it.area || this.o.areas[0]), k = area.indexOf(it), to = area[k + step];
    if (!to) { return false; }
    return this.moveTo(id, step < 0 ? { area: it.area, before: to.id } : { area: it.area, after: to.id });
  }

  /**
   * Sets an item's span (flow) in columns, or its height in rows (0: the content's).
   * @param {string} id
   * @param {{span: number, height: number}} size
   * @returns {boolean}
   */
  setSize(id, size) {
    return this.commit('Resize', (list) => {
      const i = list.find((x) => x.id === id);
      if (!i) { return []; }
      if (size.span !== undefined) { i.span = clampSpan(size.span, this.o.minSpan, this.o.cols); }
      if (size.height !== undefined) { i.h = clampHeight(size.height, this.o.maxH); }
      return [id];
    });
  }

  // ---- breakpoints (a phone's order) -------------------------------------------------------------------------------------------

  /**
   * The order a breakpoint shows (its own, else the reading order), without the hidden ones unless asked.
   * @param {string} bp 'phone', 'tablet' ...
   * @param {{withHidden: boolean}} [o]
   * @returns {string[]}
   */
  order(bp, o) {
    const own = (this.state.breakpoints[bp] || {}).order || [], all = this.ids();
    const out = own.filter((id) => all.indexOf(id) >= 0).concat(all.filter((id) => own.indexOf(id) < 0));
    const hidden = (this.state.breakpoints[bp] || {}).hidden || [];
    return o && o.withHidden ? out : out.filter((id) => hidden.indexOf(id) < 0);
  }

  /**
   * A breakpoint's own arrangement, as kept (order, hidden, heights), or null when it follows the full layout.
   * @param {string} bp
   * @returns {?{order: string[], hidden: string[], heights: Object<string, number>}}
   */
  breakpoint(bp) { return this.state.breakpoints[bp] ? copy(this.state.breakpoints[bp]) : null; }

  /**
   * Changes a breakpoint: an item moved by `step` places, hidden or shown, or its height; arranging it back to the full layout's
   * order with nothing hidden and no heights removes the breakpoint's entry.
   * @param {string} bp
   * @param {{move: [string, number], hide: [string, boolean], height: [string, number], order: string[]}} change
   * @returns {boolean}
   */
  arrange(bp, change) {
    return this.commit('Arrange ' + bp, () => {
      const cur = this.state.breakpoints[bp] || { order: [], hidden: [], heights: {} };
      const order = this.order(bp, { withHidden: true });
      if (change.order) { order.splice(0, order.length, ...change.order.filter((id) => this.get(id))); }
      if (change.move) { const [id, st] = change.move, k = order.indexOf(id), j = k + st; if (k >= 0 && j >= 0 && j < order.length) { order.splice(k, 1); order.splice(j, 0, id); } }
      const hidden = (cur.hidden || []).slice();
      if (change.hide) { const [id, on] = change.hide, k = hidden.indexOf(id); if (on && k < 0) { hidden.push(id); } else if (!on && k >= 0) { hidden.splice(k, 1); } }
      const heights = Object.assign({}, cur.heights);
      if (change.height) { const [id, n] = change.height; if (n) { heights[id] = clampHeight(n, this.o.maxH); } else { delete heights[id]; } }
      const natural = this.ids().join('|') === order.join('|') && !hidden.length && !Object.keys(heights).length;
      if (natural) { delete this.state.breakpoints[bp]; } else { this.state.breakpoints[bp] = { order, hidden, heights }; }
      return [];
    });
  }

  /**
   * The model as data (items and breakpoints), for a host to save.
   * @returns {{items: LayoutItem[], breakpoints: Object}}
   */
  toJSON() { return copy(this.state); }
}
