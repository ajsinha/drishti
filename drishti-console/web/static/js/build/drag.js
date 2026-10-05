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
/* One pointer-driven drag for the whole workbench (mouse, pen and touch alike; no HTML5 drag-and-drop, which differs between
 * browsers and cannot be tested the same way). Palette kinds, shape fields and panel headings are dragged with it; the canvas
 * is the one place things can be dropped.
 *
 *   WB.drag.attach(el, {payload: function (event) -> object|null, label: function (payload) -> text, handle?: selector})
 *   WB.drag.drop = {over(x, y, payload) -> void, end(x, y, payload, committed) -> void}    set by the canvas
 *   WB.drag.track(event, {move(ev), end(ev, committed)})                                    a bare tracked pointer (resize edges)
 *
 * A press moves a few pixels before it becomes a drag, so a click on the same element still clicks. Escape cancels. Everything a
 * drag does is repeated by a key (Add panel..., Bind field..., Alt+arrows, Shift+arrows); the drag is the quick way, not the only one. */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var THRESHOLD = 5, EDGE = 48, active = null;

  function track(e, h) {
    var id = e.pointerId, sx = e.clientX, sy = e.clientY, moved = false;
    function end(ev, committed) {
      document.removeEventListener('pointermove', move, true);
      document.removeEventListener('pointerup', up, true);
      document.removeEventListener('pointercancel', cancel, true);
      document.removeEventListener('keydown', key, true);
      active = null;
      if (moved) { document.addEventListener('click', swallow, true); setTimeout(function () { document.removeEventListener('click', swallow, true); }, 0); }
      h.end(ev, committed, moved);
    }
    function swallow(ev) { ev.stopPropagation(); ev.preventDefault(); }
    function move(ev) {
      if (ev.pointerId !== id) { return; }
      if (!moved && Math.abs(ev.clientX - sx) + Math.abs(ev.clientY - sy) < THRESHOLD) { return; }
      if (!moved && h.start && h.start(ev) === false) { end(ev, false); return; }
      moved = true;
      ev.preventDefault();
      h.move(ev);
    }
    function up(ev) { if (ev.pointerId === id) { end(ev, moved); } }
    function cancel(ev) { if (ev.pointerId === id) { end(ev, false); } }
    function key(ev) { if (ev.key === 'Escape') { ev.preventDefault(); ev.stopPropagation(); end(ev, false); } }
    document.addEventListener('pointermove', move, true);
    document.addEventListener('pointerup', up, true);
    document.addEventListener('pointercancel', cancel, true);
    document.addEventListener('keydown', key, true);
    active = { id: id };
  }

  function attach(el, spec) {
    el.style.touchAction = 'none';
    el.addEventListener('pointerdown', function (e) {
      if (active || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
      if (e.target.closest('button, input, select, textarea, a') && e.target !== el) { return; }
      var payload = spec.payload(e);
      if (!payload) { return; }
      var ghost = null, api = WB.drag;
      track(e, {
        start: function () {
          if (window.getSelection) { window.getSelection().removeAllRanges(); }
          ghost = WB.el('div', 'wb-ghost', spec.label ? spec.label(payload) : '');
          ghost.setAttribute('aria-hidden', 'true');
          document.body.appendChild(ghost);
          document.body.classList.add('wb-dragging');
        },
        move: function (ev) {
          ghost.style.transform = 'translate(' + (ev.clientX + 14) + 'px,' + (ev.clientY + 14) + 'px)';
          if (api.drop) { api.drop.over(ev.clientX, ev.clientY, payload); }
          if (ev.clientY < EDGE) { window.scrollBy(0, -12); } else if (ev.clientY > window.innerHeight - EDGE) { window.scrollBy(0, 12); }
        },
        end: function (ev, committed, moved) {
          if (ghost) { ghost.remove(); }
          document.body.classList.remove('wb-dragging');
          if (moved && api.drop) { api.drop.end(ev.clientX, ev.clientY, payload, committed); }
        }
      });
    });
  }

  WB.drag = { attach: attach, track: track, drop: null };
})();
