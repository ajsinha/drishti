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
/* (trackPointer is drishti-core's: one tracked pointer for every page.) One pointer drag for every designer (mouse, pen and touch alike; Pointer Events, not HTML5 drag and drop, which differs between
   browsers). A press becomes a drag after a few pixels, so a click still clicks; Escape cancels; the click that ends a drag is
   swallowed. A drag carries a payload (an item from the palette, a field, an item of the canvas) to the drop targets a page registers
   on a DragHub; the target under the pointer is told as the pointer moves (over) and when it is let go (drop). Everything a drag does
   has a key too: the drag is the quick way, never the only one. */
import { trackPointer } from '../../drishti-core/src/drishti_index.js';



/**
 * A drop target.
 * @typedef {Object} DropTarget
 * @property {Element} el where drops land
 * @property {function(Object): boolean} [accepts] whether it takes this payload (default: every one)
 * @property {function(number, number, Object): void} [over] the pointer is over it (clientX, clientY, payload)
 * @property {function(): void} [leave]
 * @property {function(number, number, Object): void} drop
 */

/**
 * The drags of one page: sources made draggable with draggable(), targets registered with target(). Each page (each designer) makes
 * its own hub.
 */
export class DragHub {
  /**
   * A hub with no targets yet.
   */
  constructor() {
    /** @type {DropTarget[]} */
    this.targets = [];
    this.active = null;
  }

  /**
   * Registers a drop target.
   * @param {DropTarget} t
   * @returns {function(): void} removes it
   */
  target(t) { this.targets.push(t); return () => { this.targets = this.targets.filter((x) => x !== t); }; }

  /**
   * The target under a point that takes the payload (the innermost when targets nest).
   * @param {number} x
   * @param {number} y
   * @param {Object} payload
   * @param {Document} doc
   * @returns {?DropTarget}
   */
  hit(x, y, payload, doc) {
    const el = doc.elementFromPoint(x, y);
    if (!el) { return null; }
    this.targets = this.targets.filter((t) => t.el.isConnected !== false);      // a target drawn again is registered again: the old one goes
    const hits = this.targets.filter((t) => t.el.contains(el) && (!t.accepts || t.accepts(payload)));
    hits.sort((a, b) => (a.el.contains(b.el) ? 1 : b.el.contains(a.el) ? -1 : 0));
    return hits[0] || null;
  }

  /**
   * Makes an element a drag source.
   * @param {Element} el
   * @param {{payload: function(PointerEvent): ?Object, label: function(Object): string, handle: string}} o payload: what is dragged
   *   (null: not this press); label: the ghost's text; handle: a selector the press must start in
   * @returns {function(): void} stops it
   */
  draggable(el, o) {
    el.style.touchAction = 'none';
    const down = (e) => {
      if (this.active || (e.pointerType === 'mouse' && e.button !== 0) || (o.handle && !e.target.closest(o.handle))) { return; }
      const btn = e.target.closest('button, a');                      // a button inside the source is its own; the source may be a button itself
      if (e.target.closest('input, select, textarea, [contenteditable]') || (btn && btn !== el && el.contains(btn) && !o.handle)) { return; }
      const payload = o.payload(e);
      if (payload) { this.start(e, payload, o.label ? o.label(payload) : ''); }
    };
    el.addEventListener('pointerdown', down);
    return () => el.removeEventListener('pointerdown', down);
  }

  /**
   * Starts dragging a payload from a press (a ghost follows the pointer; the target under it is told).
   * @param {PointerEvent} e
   * @param {Object} payload
   * @param {string} [label]
   * @returns {void}
   */
  start(e, payload, label) {
    const doc = e.target.ownerDocument;
    let ghost = null, over = null;
    const setOver = (t) => { if (over && over !== t && over.leave) { over.leave(); } over = t; };
    this.active = trackPointer(e, {
      start: () => {
        if (doc.defaultView.getSelection) { doc.defaultView.getSelection().removeAllRanges(); }
        ghost = doc.createElement('div');
        ghost.className = 'dd-ghost';
        ghost.setAttribute('aria-hidden', 'true');
        ghost.textContent = '⠿ ' + (label || payload.label || '');
        doc.body.appendChild(ghost);
        doc.body.classList.add('dd-dragging');
      },
      move: (ev) => {
        ghost.style.transform = 'translate(' + (ev.clientX + 14) + 'px,' + (ev.clientY + 14) + 'px)';
        const win = doc.defaultView;                                           // near the window's top or bottom edge the page scrolls
        if (ev.clientY < 48) { win.scrollBy(0, -12); } else if (ev.clientY > win.innerHeight - 48) { win.scrollBy(0, 12); }
        const t = this.hit(ev.clientX, ev.clientY, payload, doc);
        setOver(t);
        if (t && t.over) { t.over(ev.clientX, ev.clientY, payload); }
      },
      end: (ev, committed) => {
        if (ghost) { ghost.remove(); }
        doc.body.classList.remove('dd-dragging');
        const t = committed ? this.hit(ev.clientX, ev.clientY, payload, doc) : null;
        setOver(null);
        this.active = null;
        if (t) { t.drop(ev.clientX, ev.clientY, payload); }
      }
    });
  }
}

/**
 * A list whose rows a person re-orders by dragging (a card list, a device's order): one pointer drag per press on a row (not on its
 * buttons and fields); the row under the pointer is marked; letting go calls onMove(from, to) with the rows' indexes. The keys that do
 * the same (Alt+arrows, buttons) are the host's.
 * @param {Element} list
 * @param {{row: string, canDrag: function(Element): boolean, canDrop: function(Element): boolean, onMove: function(number, number): void,
 *   dragClass: string, overClass: string}} o row: the rows' selector; dragClass and overClass: the moving row's and the target's classes
 * @returns {function(): void} stops it
 */
export function sortableList(list, o) {
  const rows = () => Array.prototype.filter.call(list.children, (x) => x.matches(o.row));
  const down = (e) => {
    const li = e.target.closest(o.row);
    if (!li || li.parentNode !== list || (e.pointerType === 'mouse' && e.button !== 0) || e.target.closest('button, input, select, textarea, label, a')) { return; }
    if (o.canDrag && !o.canDrag(li)) { return; }
    let over = null;
    const mark = (x) => { if (over && o.overClass) { over.classList.remove(o.overClass); } over = x; if (x && o.overClass) { x.classList.add(o.overClass); } };
    trackPointer(e, {
      start: () => { if (o.dragClass) { li.classList.add(o.dragClass); } },
      move: (ev) => {
        const t = list.ownerDocument.elementFromPoint(ev.clientX, ev.clientY), r = t && t.closest(o.row);
        mark(r && r.parentNode === list && r !== li && (!o.canDrop || o.canDrop(r)) ? r : null);
      },
      end: (ev, committed) => {
        if (o.dragClass) { li.classList.remove(o.dragClass); }
        const all = rows(), from = all.indexOf(li), to = over ? all.indexOf(over) : -1;
        mark(null);
        if (committed && to >= 0 && to !== from) { o.onMove(from, to); }
      }
    });
  };
  list.addEventListener('pointerdown', down);
  return () => list.removeEventListener('pointerdown', down);
}
