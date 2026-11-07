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
/* The layout canvas: the one place every designer arranges things. It draws a LayoutModel (grid: each item placed on the columns and
   rows of a CSS grid; flow: the host draws its areas and hands the canvas each item's element) and makes every item selectable
   (a click, Shift or Ctrl for more, the keys), moved by its handle and resized by its edges with the pointer (snapped to the grid,
   shown live, kept as one step when let go) and by the keys (the keymap), and a drop target for the palette (pointer drags through
   a DragHub, or HTML5 drops of the host's type). What an item looks like inside is the host's: createItem makes its element (else a
   plain frame), updateItem refreshes it. Undo, copy, paste, group, z-order and delete go through the model. A live region says what
   happened. Nothing inline: positions are set through the CSSOM. */
import { EventBus, announce, h } from '../../drishti-core/src/drishti_index.js';
import { designerKeys } from './drishti_keymap.js';
import { trackPointer } from './drishti_pointer.js';
import { snap } from './drishti_layout-rules.js';
import { flowHit, showFlowDrop, clearFlowDrop } from './drishti_layout-flow.js';

/**
 * @typedef {Object} LayoutCanvasOptions
 * @property {import('./drishti_layout-model.js').LayoutModel} model
 * @property {import('./drishti_selection.js').Selection} selection
 * @property {function(Object): HTMLElement} [createItem] grid: the element of a new item (default: a frame with a heading)
 * @property {function(Object, HTMLElement): void} [updateItem] grid: refreshes an item's element after a change
 * @property {function(Object): string} [label] what an item is called (default: item.label or its id)
 * @property {string} [handle] a selector inside an item that moves it (default: the whole item)
 * @property {number} [rowPx=56] grid: a row's height
 * @property {number} [gap=10] grid: the gap between columns and rows
 * @property {('move'|'select')} [keys='move'] the arrows' scheme (keymap)
 * @property {Object} [extraKeys] the host's bindings on top of the scheme
 * @property {boolean} [readOnly=false]
 * @property {boolean} [resize=true] edges to resize by (grid: the corner; flow: the right and bottom edges)
 * @property {import('./drishti_pointer.js').DragHub} [hub] the palette's drags land here
 * @property {string} [dropType] a type of HTML5 drag the canvas takes (its data is JSON)
 * @property {Object<string, HTMLElement>} [areas] flow: the area elements by name
 * @property {function(string, Object): (boolean|void)} [onAction] a key's action before the canvas does it; true: the host did it
 */

/**
 * The canvas. Events: select {ids}, open {id}, drop {payload, cell, target}, contextmenu {id, x, y}, action {type, ...},
 * moved {ids}, rendered.
 */
export class LayoutCanvas {
  /**
   * @param {HTMLElement} el the canvas element
   * @param {LayoutCanvasOptions} o
   */
  constructor(el, o) {
    if (!o || !o.model || !o.selection) { throw new Error('a layout canvas needs a model and a selection'); }
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ rowPx: 56, gap: 10, keys: 'move', resize: true }, o);
    this.model = o.model;
    this.sel = o.selection;
    this.flow = this.model.o.mode === 'flow';
    this.events = new EventBus();
    /** @type {Map<string, HTMLElement>} */
    this.els = new Map();
    this.key = designerKeys(this.o.keys, this.o.extraKeys);
    this.offs = [];
    el.classList.add('dd-canvas', this.flow ? 'dd-flow' : 'dd-grid');
    el.setAttribute('role', el.getAttribute('role') || 'application');
    if (!el.hasAttribute('aria-label')) { el.setAttribute('aria-label', 'Layout canvas'); }
    if (!this.flow) { el.style.setProperty('--dd-cols', String(this.model.o.cols)); el.style.setProperty('--dd-row', this.o.rowPx + 'px'); el.style.setProperty('--dd-gap', this.o.gap + 'px'); }
    this.offs.push(this.model.on('change', () => this.render()));
    this.offs.push(this.sel.on('change', (s) => { this.mark(); this.events.emit('select', s); }));
    this.wire();
    this.render();
  }

  /**
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /** @private */
  label(item) { return this.o.label ? this.o.label(item) : (item.label || item.id); }

  /**
   * Flow: the host's elements of the items (after it drew its areas); the canvas decorates them.
   * @param {Object<string, HTMLElement>} map id -> element
   * @param {Object<string, HTMLElement>} [areas] the area elements
   * @returns {void}
   */
  adopt(map, areas) {
    this.els = new Map(Object.keys(map).map((k) => [k, map[k]]));
    if (areas) { this.o.areas = areas; }
    this.render();
  }

  /**
   * The element of an item.
   * @param {string} id
   * @returns {?HTMLElement}
   */
  itemEl(id) { return this.els.get(id) || null; }

  /**
   * Draws the model: grid items created, placed and refreshed; every item decorated and marked.
   * @returns {void}
   */
  render() {
    const items = this.model.list(), ids = new Set(items.map((i) => i.id));
    if (!this.flow) {
      this.els.forEach((e, id) => { if (!ids.has(id)) { e.remove(); this.els.delete(id); } });
      items.forEach((it) => {
        let e = this.els.get(it.id);
        if (!e) { e = this.o.createItem ? this.o.createItem(it) : this.defaultItem(it); this.els.set(it.id, e); this.el.appendChild(e); }
        this.place(e, it);
        if (this.o.updateItem) { this.o.updateItem(it, e); }
      });
    }
    items.forEach((it) => { const e = this.els.get(it.id); if (e) { this.decorate(e, it); } });
    this.sel.prune((id) => ids.has(id));
    this.mark();
    this.events.emit('rendered', { ids: [...ids] });
  }

  /** @private a plain frame: a heading (the handle) and a body */
  defaultItem(it) {
    return h(this.doc, 'div', { cls: 'dd-item-frame' }, [h(this.doc, 'div', { cls: 'dd-item-head', text: this.label(it) }), h(this.doc, 'div', { cls: 'dd-item-body' })]);
  }

  /** @private grid placement through the CSSOM */
  place(e, at) {
    e.style.gridColumn = (at.x + 1) + ' / span ' + at.w;
    e.style.gridRow = (at.y + 1) + ' / span ' + at.h;
    if (at.z) { e.style.zIndex = String(at.z); } else { e.style.removeProperty('z-index'); }
  }

  /** @private the canvas's part of an item: its class, focus, name, resize edges */
  decorate(e, it) {
    e.classList.add('dd-item');
    e.setAttribute('data-dd-id', it.id);
    if (e.tabIndex < 0 || !e.hasAttribute('tabindex')) { e.tabIndex = -1; }
    e.setAttribute('aria-roledescription', 'item on the canvas');
    const where = this.flow ? (it.span ? ', ' + it.span + ' of ' + this.model.o.cols + ' columns' : '') : ', column ' + (it.x + 1) + ' row ' + (it.y + 1) + ', ' + it.w + ' wide, ' + it.h + ' high';
    e.setAttribute('aria-label', this.label(it) + where);
    e.toggleAttribute('data-dd-group', !!it.group);
    if (this.o.resize && !this.o.readOnly) {
      (this.flow ? ['e', 's'] : ['se']).forEach((side) => {
        if (!e.querySelector(':scope > .dd-edge-' + side)) { e.appendChild(h(this.doc, 'span', { cls: 'dd-edge dd-edge-' + side, attrs: { 'aria-hidden': 'true', 'data-dd-edge': side } })); }
      });
    }
  }

  /** @private the selection's marks and the one item in the tab order */
  mark() {
    const ids = this.sel.list(), primary = this.sel.primary();
    this.els.forEach((e, id) => {
      const on = ids.indexOf(id) >= 0;
      e.classList.toggle('dd-selected', on);
      e.classList.toggle('dd-primary', id === primary);
      if (on) { e.setAttribute('aria-current', 'true'); } else { e.removeAttribute('aria-current'); }
      e.tabIndex = id === (primary || this.model.ids()[0]) ? 0 : -1;
    });
  }

  /**
   * Focuses the primary selected item (or the first).
   * @returns {void}
   */
  focus() { const id = this.sel.primary() || this.model.ids()[0], e = id && this.els.get(id); if (e) { e.focus({ preventScroll: false }); } }

  /** @private one step on a column and a row in pixels */
  metrics() {
    const w = this.el.clientWidth, cols = this.model.o.cols, col = (w - (cols - 1) * this.o.gap) / cols;
    return { stepX: col + this.o.gap, stepY: this.o.rowPx + this.o.gap };
  }

  /**
   * The grid cell under a point (grid).
   * @param {number} x clientX
   * @param {number} y clientY
   * @returns {{x: number, y: number}}
   */
  cellAt(x, y) {
    const r = this.el.getBoundingClientRect(), m = this.metrics();
    return { x: Math.max(0, Math.min(this.model.o.cols - 1, Math.floor((x - r.left) / m.stepX))), y: Math.max(0, Math.floor((y - r.top + this.el.scrollTop) / m.stepY)) };
  }

  /** @private the pointer and the keys */
  wire() {
    const el = this.el;
    const itemOf = (t) => { const e = t.closest && t.closest('[data-dd-id]'); return e && this.els.get(e.getAttribute('data-dd-id')) === e ? e : null; };
    el.addEventListener('click', (e) => {
      const it = itemOf(e.target);
      if (it) { const id = it.getAttribute('data-dd-id'); if (this.pickedOnDown !== id) { this.sel.pick(id, e.shiftKey || e.ctrlKey || e.metaKey, 'canvas'); } this.pickedOnDown = null; return; }
      if (e.target === el || e.target.classList.contains('dd-area')) { this.sel.clear(); }
    });
    el.addEventListener('dblclick', (e) => { const it = itemOf(e.target); if (it && !e.target.closest('[data-dd-edge]')) { this.events.emit('open', { id: it.getAttribute('data-dd-id') }); } });
    el.addEventListener('pointerdown', () => { this.downAt = Date.now(); }, true);
    el.addEventListener('focusin', (e) => {                                 // the keys brought the focus (a click selects by itself)
      const it = itemOf(e.target);
      if (it && e.target === it && Date.now() - (this.downAt || 0) > 600 && !this.sel.has(it.getAttribute('data-dd-id'))) { this.sel.set(it.getAttribute('data-dd-id'), 'canvas'); }
    });
    el.addEventListener('contextmenu', (e) => {
      const it = itemOf(e.target);
      if (!it || !this.events.count('contextmenu')) { return; }
      e.preventDefault();
      const id = it.getAttribute('data-dd-id');
      if (!this.sel.has(id)) { this.sel.set(id, 'canvas'); }
      this.events.emit('contextmenu', { id, x: e.clientX, y: e.clientY });
    });
    el.addEventListener('keydown', (e) => this.onKey(e));
    el.addEventListener('pointerdown', (e) => this.onPointer(e, itemOf(e.target)));
    if (this.o.hub) {
      this.offs.push(this.o.hub.target({ el, accepts: (p) => !this.o.readOnly && !!p,
        over: (x, y, p) => this.dropOver(x, y, p), leave: () => this.dropClear(), drop: (x, y, p) => { this.dropClear(); this.dropAt(x, y, p); } }));
    }
    if (this.o.dropType) {
      const takes = (e) => e.dataTransfer && [...e.dataTransfer.types].indexOf(this.o.dropType) >= 0;
      el.addEventListener('dragover', (e) => { if (takes(e) && !this.o.readOnly) { e.preventDefault(); this.dropOver(e.clientX, e.clientY, {}); } });
      el.addEventListener('dragleave', (e) => { if (!el.contains(e.relatedTarget)) { this.dropClear(); } });
      el.addEventListener('drop', (e) => {
        if (!takes(e)) { return; }
        this.dropClear();
        let p = null;
        try { p = JSON.parse(e.dataTransfer.getData(this.o.dropType)); } catch (x) { p = null; }
        if (p) { e.preventDefault(); this.dropAt(e.clientX, e.clientY, p); }
      });
    }
  }

  /** @private where a drop would land, shown */
  dropOver(x, y, payload) {
    if (this.flow) { showFlowDrop(this, flowHit(this, x, y), payload); return; }
    const item = this.doc.elementFromPoint(x, y), on = item && item.closest('[data-dd-id]');
    this.els.forEach((e) => e.classList.toggle('dd-drop-on', e === on));
  }

  /** @private */
  dropClear() { if (this.flow) { clearFlowDrop(this); } this.els.forEach((e) => e.classList.remove('dd-drop-on')); }

  /** @private a drop: the host decides what it makes */
  dropAt(x, y, payload) {
    const under = this.doc.elementFromPoint(x, y), on = under && under.closest('[data-dd-id]');
    const onItem = on && this.els.get(on.getAttribute('data-dd-id')) === on ? on.getAttribute('data-dd-id') : null;
    this.events.emit('drop', { payload, item: onItem, cell: this.flow ? null : this.cellAt(x, y), target: this.flow ? flowHit(this, x, y) : null, x, y });
  }

  /** @private a press on an item's handle moves it; on an edge resizes it */
  onPointer(e, itemEl) {
    if (!itemEl || this.o.readOnly || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
    const id = itemEl.getAttribute('data-dd-id'), edge = e.target.closest('[data-dd-edge]');
    if (!edge && (e.target.closest('button, a, input, select, textarea') || (this.o.handle && !e.target.closest(this.o.handle)))) { return; }
    if (edge) { e.preventDefault(); }
    this.pickedOnDown = null;
    if (!this.sel.has(id)) { this.sel.pick(id, e.shiftKey || e.ctrlKey || e.metaKey, 'canvas'); this.pickedOnDown = id; }
    if (this.flow) { this.flowDrag(e, id, itemEl, edge && edge.getAttribute('data-dd-edge')); return; }
    const ids = edge ? [id] : this.model.expand(this.sel.list()), m = this.metrics();
    const start = ids.map((k) => Object.assign({}, this.model.get(k)));
    const cols = this.model.o.cols;
    let dx = 0, dy = 0;
    trackPointer(e, {
      start: () => { ids.forEach((k) => { const x = this.els.get(k); if (x) { x.classList.add('dd-dragging'); } }); },
      move: (ev) => {
        dx = snap(ev.clientX - e.clientX, m.stepX); dy = snap(ev.clientY - e.clientY, m.stepY);
        start.forEach((s) => {
          const x = this.els.get(s.id);
          if (!x) { return; }
          if (edge) { this.place(x, { x: s.x, y: s.y, w: Math.max(1, Math.min(cols - s.x, s.w + dx)), h: Math.max(1, Math.min(this.model.o.maxH, s.h + dy)), z: s.z }); }
          else {
            const minX = Math.min(...start.map((q) => q.x)), maxX = Math.max(...start.map((q) => q.x + q.w)), minY = Math.min(...start.map((q) => q.y));
            const cx = Math.max(-minX, Math.min(cols - maxX, dx)), cy = Math.max(-minY, dy);
            this.place(x, { x: s.x + cx, y: s.y + cy, w: s.w, h: s.h, z: s.z });
          }
        });
      },
      end: (ev, committed) => {
        this.els.forEach((x) => x.classList.remove('dd-dragging'));
        if (committed && (dx || dy)) {
          if (edge) { this.model.resizeBy([id], dx, dy, { label: 'Resize ' + this.label(start[0]) }); } else { this.model.moveBy(ids, dx, dy); }
          this.say(ids);
          this.events.emit('moved', { ids });
        } else { start.forEach((s) => { const x = this.els.get(s.id); if (x) { this.place(x, s); } }); }
      }
    });
  }

  /** @private flow: a handle drags the item to a place in an area; an edge sets its span or height */
  flowDrag(e, id, itemEl, edge) {
    if (edge) {
      const it = this.model.get(id), area = itemEl.parentNode, cs = this.doc.defaultView.getComputedStyle(area), gap = parseFloat(cs.columnGap) || 0;
      const cols = this.model.o.cols, unit = (area.getBoundingClientRect().width - gap * (cols - 1)) / cols;
      const rowPx = parseFloat(this.doc.defaultView.getComputedStyle(this.doc.documentElement).fontSize || '16') * 2.5;
      let now = edge === 'e' ? (it.span || cols) : (it.h || 0);
      itemEl.classList.add('dd-resizing');
      trackPointer(e, {
        move: (ev) => {
          const r = itemEl.getBoundingClientRect();
          now = edge === 'e' ? Math.max(1, Math.min(cols, Math.round((ev.clientX - r.left + gap) / (unit + gap)))) : Math.max(1, Math.round((ev.clientY - r.top) / rowPx));
          this.events.emit('resizing', { id, edge, value: now });
        },
        end: (ev, committed) => {
          itemEl.classList.remove('dd-resizing');
          if (committed) { this.model.setSize(id, edge === 'e' ? { span: now } : { height: now }); this.say([id]); } else { this.events.emit('resizing', { id, edge, value: null }); }
        }
      });
      return;
    }
    trackPointer(e, {
      start: () => { itemEl.classList.add('dd-dragging'); },
      move: (ev) => showFlowDrop(this, flowHit(this, ev.clientX, ev.clientY), { item: id }),
      end: (ev, committed) => {
        itemEl.classList.remove('dd-dragging');
        clearFlowDrop(this);
        if (!committed) { return; }
        const t = flowHit(this, ev.clientX, ev.clientY);
        if (!t || t.id === id) { if (!t) { announce('Dropped outside the columns: nothing moved.'); } return; }
        this.events.emit('drop', { payload: { item: id }, item: null, cell: null, target: t, x: ev.clientX, y: ev.clientY });
      }
    });
  }

  /** @private where an item is now, said */
  say(ids) {
    const it = this.model.get(ids[0]);
    if (!it) { return; }
    announce(ids.length > 1 ? ids.length + ' items moved' : this.flow ? this.label(it) + ': ' + (it.span || this.model.o.cols) + ' of ' + this.model.o.cols + ' columns' + (it.h ? ', ' + it.h + ' rows' : '')
      : this.label(it) + ' at column ' + (it.x + 1) + ', row ' + (it.y + 1) + ', ' + it.w + ' by ' + it.h);
  }

  /** @private the keys on a focused item (or the canvas) */
  onKey(e) {
    const t = e.target, tag = (t.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select' || t.isContentEditable) { return; }
    const itemEl = t.closest && t.closest('[data-dd-id]');
    if (itemEl && t !== itemEl) { return; }
    const a = this.key(e);
    if (!a) { return; }
    if (this.o.onAction && this.o.onAction(a.type, a) === true) { e.preventDefault(); e.stopPropagation(); return; }
    const ids = this.sel.list(), one = this.sel.primary(), m = this.model;
    const needs = ['move', 'size', 'delete', 'copy', 'cut', 'duplicate', 'group', 'ungroup', 'zorder', 'open', 'natural', 'column'];
    if (needs.indexOf(a.type) >= 0 && !ids.length) { if (a.type !== 'open') { announce('Select an item first.'); } return; }
    e.preventDefault(); e.stopPropagation();
    const ro = this.o.readOnly && ['select', 'copy', 'escape', 'open', 'selectAll'].indexOf(a.type) < 0;
    if (ro) { announce('This design is read-only.'); return; }
    switch (a.type) {
      case 'move':
        if (this.flow) { if (!m.step(one, a.dy || a.dx)) { announce(this.label(m.get(one)) + ' is already ' + ((a.dy || a.dx) < 0 ? 'first' : 'last') + ' in its column.'); } }
        else { m.moveBy(ids, a.dx, a.dy, { mergeKey: 'key-move' }); }
        this.say(ids); this.events.emit('moved', { ids }); break;
      case 'size':
        if (this.flow) { const it = m.get(one); if (a.dx) { m.setSize(one, { span: (it.span || m.o.cols) + a.dx }); } else { m.setSize(one, { height: Math.max(1, (it.h || this.measureRows(one)) + a.dy) }); } }
        else { m.resizeBy(ids, a.dx, a.dy, { mergeKey: 'key-size' }); }
        this.say(ids); break;
      case 'natural': if (this.flow) { m.setSize(one, { height: 0 }); this.say([one]); } break;
      case 'column': if (this.flow) { const it = m.get(one), to = m.inArea(a.area); if ((it.area || m.o.areas[0]) === a.area) { announce(this.label(it) + ' is already in that column.'); } else { m.moveTo(one, { area: a.area, before: to[0] ? to[0].id : null }); } } break;
      case 'select': this.selectDir(a.dir); break;
      case 'delete': { const all = m.expand(ids), list = m.ids(), rest = list.filter((x) => all.indexOf(x) < 0), next = rest[Math.min(list.indexOf(ids[0]), rest.length - 1)] || null; m.remove(ids); this.sel.set(next, 'canvas'); announce('Deleted. Undo brings it back.'); this.focus(); break; }
      case 'copy': m.copy(ids); announce('Copied'); break;
      case 'cut': m.copy(ids); m.remove(ids, 'Cut'); announce('Cut'); break;
      case 'paste': { const made = m.paste(); if (made.length) { this.sel.set(made, 'canvas'); announce('Pasted'); this.focus(); } break; }
      case 'duplicate': { const made = m.duplicate(ids); this.sel.set(made, 'canvas'); this.focus(); break; }
      case 'selectAll': this.sel.set(m.ids(), 'canvas'); break;
      case 'group': if (m.group(ids)) { announce('Grouped ' + ids.length + ' items'); } break;
      case 'ungroup': m.ungroup(ids); announce('Ungrouped'); break;
      case 'zorder': m.zOrder(ids, a.how); break;
      case 'undo': case 'redo':
        if (m.stack) { const c = m.stack[a.type](); announce(c ? (a.type === 'undo' ? 'Undone: ' : 'Done again: ') + c.label : 'Nothing to ' + a.type); } else { this.events.emit('action', a); }
        break;
      case 'open': this.events.emit('open', { id: one }); break;
      case 'escape': this.sel.clear(); break;
      default: this.events.emit('action', a);
    }
    if (a.type === 'move' || a.type === 'size' || a.type === 'column' || a.type === 'natural') { this.focus(); }
  }

  /** @private flow: an item's height in rows as drawn */
  measureRows(id) {
    const e = this.els.get(id), rowPx = parseFloat(this.doc.defaultView.getComputedStyle(this.doc.documentElement).fontSize || '16') * 2.5;
    return e ? Math.max(1, Math.ceil(e.getBoundingClientRect().height / rowPx - 0.05)) : 1;
  }

  /**
   * Selects another item: 'next', 'prev', 'first', 'last', or (flow) 'main' and 'side', the area's item nearest the one selected.
   * @param {string} dir
   * @returns {void}
   */
  selectDir(dir) {
    const list = this.model.ids(), i = list.indexOf(this.sel.primary());
    let j = dir === 'first' ? 0 : dir === 'last' ? list.length - 1 : dir === 'next' ? Math.min(list.length - 1, i + 1) : dir === 'prev' ? Math.max(0, i - 1) : i;
    if (dir === 'main' || dir === 'side') {
      const areas = this.model.o.areas, area = dir === 'side' ? areas[areas.length - 1] : areas[0], inArea = this.model.inArea(area);
      if (!inArea.length) { announce('No items in the ' + (dir === 'side' ? 'side' : 'main') + ' column.'); return; }
      const cur = this.model.get(this.sel.primary()), from = cur ? this.model.inArea(cur.area || areas[0]).indexOf(cur) : 0;
      j = list.indexOf(inArea[Math.min(Math.max(0, from), inArea.length - 1)].id);
    }
    if (j < 0 || !list[j]) { return; }
    this.sel.set(list[j], 'canvas');
    this.focus();
    const it = this.model.get(list[j]);
    announce(this.label(it) + ' selected');
  }

  /**
   * Stops listening (the page leaves the designer).
   * @returns {void}
   */
  destroy() { this.offs.forEach((f) => f()); this.offs = []; this.events.clear(); }
}
