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
/* One tracked pointer for every page (mouse, pen and touch alike; Pointer Events): a press becomes a drag after a few pixels, so a click
   still clicks; Escape cancels; the click that ends a drag is swallowed. drishti-designer's drags, a slider's thumb, a divider and a pane
   moved across frames all use it. */

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
 * @param {{threshold: number, capture: Element}} [o] threshold: pixels before a press is a drag (default 5; 0: at once); capture: an element
 *   that captures the pointer (the moves over frames and outside the window still come here)
 * @returns {{cancel: function(): void}}
 */
export function trackPointer(e, h, o) {
  const doc = (e.target && e.target.ownerDocument) || document, win = doc.defaultView;
  const id = e.pointerId, sx = e.clientX, sy = e.clientY, threshold = o && o.threshold !== undefined ? o.threshold : 5, cap = o && o.capture;
  if (cap) { try { cap.setPointerCapture(id); } catch (err) { /* a pointer that has gone already */ } }
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
