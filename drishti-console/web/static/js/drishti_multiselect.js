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
/* A retired name, kept only as a loader. The multi-select is drishti-inputs' MultiSelect (Drishti Elements) since 2.9.11.e; the renderer
   1.2.0, whose released folder never changes, still loads js/multiselect.js before js/controls.js. So this file draws nothing: it adds
   the console's drishti-core and drishti-inputs bundles and the inputs' style sheet to the page (once, in order, before the scripts
   loaded after it), and the controls find the pickers. Deleted with the renderer 1.2.0 (ELEMENTS.md section 12, step 8). */
(function () {
  "use strict";
  var me = document.currentScript, D = window.Drishti || {};
  if (!me || !me.src || (D.elements && D.elements.inputs)) { return; }
  var base = me.src.replace(/js\/(?:drishti_)?multiselect\.js.*$/, "");
  var v = /[?&]v=([^&]+)/.exec(me.src), q = v ? "?v=" + v[1] : "";
  try {
    var l = document.createElement("link");
    l.rel = "stylesheet"; l.href = base + "elements/drishti-inputs/drishti_inputs.css" + q;
    document.head.appendChild(l);
  } catch (e) { /* no style sheet: the pickers still work */ }
  ["elements/drishti-core/drishti_core.js", "elements/drishti-inputs/drishti_inputs.js"].forEach(function (p) {
    if (p.indexOf("core") > 0 && D.elements && D.elements.core) { return; }
    try {
      var s = document.createElement("script");
      s.src = base + p + q;
      s.async = false;
      document.head.appendChild(s);
    } catch (e) { /* a host that allows only its own trusted script URLs: the controls keep plain fields */ }
  });
})();
