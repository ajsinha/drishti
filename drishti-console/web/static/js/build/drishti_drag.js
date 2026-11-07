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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The workbench's drags are drishti-designer's (one pointer drag for every designer: mouse, pen and touch alike, Escape cancels, a
 * click still clicks). Palette kinds and shape fields are drag sources; the canvas is the one drop target.
 *
 *   WB.drag.hub                                                      the page's DragHub (the canvas registers its target on it)
 *   WB.drag.attach(el, {payload: function (event) -> object|null, label: function (payload) -> text})
 *   WB.drag.track(event, {start?(ev), move(ev), end(ev, committed, moved)})       a bare tracked pointer
 *
 * Everything a drag does is repeated by a key (Add panel..., Bind field..., Alt+arrows, Shift+arrows); the drag is the quick way, not the only one. */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  var K = window.Drishti.elements.designer, hub = new K.DragHub();
  WB.drag = {
    hub: hub,
    attach: function (el, spec) { return hub.draggable(el, { payload: spec.payload, label: spec.label }); },
    track: function (e, h) { return window.Drishti.elements.core.trackPointer(e, h); }
  };
})();
