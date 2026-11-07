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
/* One pointer drag for every designer (mouse, pen and touch alike; Pointer Events, not HTML5 drag and drop, which differs between
   browsers). A press becomes a drag after a few pixels, so a click still clicks; Escape cancels; the click that ends a drag is
   swallowed. A drag carries a payload (an item from the palette, a field, an item of the canvas) to the drop targets a page registers
   on a DragHub; the target under the pointer is told as the pointer moves (over) and when it is let go (drop). Everything a drag does
   has a key too: the drag is the quick way, never the only one. */

/**
 * @typedef {Object} TrackHandlers
 * @property {function(PointerEvent): (boolean|void)} [start] the press became a drag; false cancels it
 * @property {function(PointerEvent): void} move
 * @property {function(PointerEvent, boolean, boolean): void} end (event, committed, moved): committed is false after Escape or a cancel
 */

/**
 * Tracks one pointer from a press until it is let go.
 * @param {PointerEvent} e the pointerdown
 * @param {TrackHandlers} h
 * @param {{threshold: number}} [o] pixels before a press is a drag (default 5; 0: at once)
 * @returns {{cancel: function(): void}}
 */
export function trackPointer(e, h, o) {
  const doc = (e.target && e.target.ownerDocument) || document, win = doc.defaultView;
  const id = e.pointerId, sx = e.clientX, sy = e.clientY, threshold = o && o.threshold !== undefined ? o.threshold : 5;
  let moved = false, done = false;
  const swallow = (ev) => { ev.stopPropagation(); ev.preventDefault(); };
  function finish(ev, committed) {
    if (done) { return; }
    done = true;
    doc.removeEventListener('pointermove', move, true);
    doc.removeEventListener('pointerup', up, true);
    doc.removeEventListener('pointercancel', cancel, true);
    doc.removeEventListener('keydown', key, true);
    if (moved) { doc.addEventListener('click', swallow, true); win.setTimeout(() => doc.removeEventListener('click', swallow, true), 0); }
    h.end(ev, committed && moved, moved);
  }
  function move(ev) {
    if (ev.pointerId !== id) { return; }
    if (!moved && Math.abs(ev.clientX - sx) + Math.abs(ev.clientY - sy) < threshold) { return; }
    if (!moved && h.start && h.start(ev) === false) { finish(ev, false); return; }
    moved = true;
    ev.preventDefault();
    h.move(ev);
  }
  function up(ev) { if (ev.pointerId === id) { finish(ev, true); } }
  function cancel(ev) { if (ev.pointerId === id) { finish(ev, false); } }
  function key(ev) { if (ev.key === 'Escape') { ev.stopPropagation(); ev.preventDefault(); finish({ clientX: sx, clientY: sy, pointerId: id }, false); } }
  doc.addEventListener('pointermove', move, true);
  doc.addEventListener('pointerup', up, true);
  doc.addEventListener('pointercancel', cancel, true);
  doc.addEventListener('keydown', key, true);
  if (threshold === 0) { moved = true; if (h.start && h.start(e) === false) { finish(e, false); } }
  return { cancel: () => finish({ clientX: sx, clientY: sy, pointerId: id }, false) };
}

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
      if (e.target.closest('input, select, textarea, [contenteditable]') || (e.target !== el && e.target.closest('button, a') && !o.handle)) { return; }
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
