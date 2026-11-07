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
/* A retired name, kept only as a loader. Pivot rows are drawn by drishti-grid (Drishti Elements) since 2.9.11.e; the renderer 1.2.0, whose
   released folder never changes, still loads js/pivot-grid.js before js/tree-rows.js. So this file draws nothing: it adds the console's
   drishti-core and drishti-grid bundles to the page (once, in order, before the scripts loaded after it), and tree rows find the grid. The
   embed routes serve the same bundles beside the scripts (/embed/v1/elements/..., /embed/v2/elements/...), so the base works there too.
   Deleted with the renderer 1.2.0 (renderer 2.0.0 draws with the libraries; ELEMENTS.md section 12, step 8). */
(function () {
  "use strict";
  var me = document.currentScript, D = window.Drishti || {};
  if (!me || !me.src || (D.elements && D.elements.grid)) { return; }
  var base = me.src.replace(/js\/(?:drishti_)?pivot-grid\.js.*$/, "");
  var v = /[?&]v=([^&]+)/.exec(me.src);
  ["elements/drishti-core/drishti_core.js", "elements/drishti-grid/drishti_grid.js"].forEach(function (p) {
    if (p.indexOf("core") > 0 && D.elements && D.elements.core) { return; }
    try {
      var s = document.createElement("script");
      s.src = base + p + (v ? "?v=" + v[1] : "");
      s.async = false;
      document.head.appendChild(s);
    } catch (e) { /* a host that allows only its own trusted script URLs: tree rows keep the plain table */ }
  });
})();
