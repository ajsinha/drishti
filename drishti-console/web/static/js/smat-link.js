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
/* The VaR page's "Scenario matrix" link (VAR_GUIDE.md section 3.9): loaded by base.html on the VaR explorer only when the person's edition
   or lens has the SMAT word (Drishti Risk and Drishti Trading), it adds one link to the explorer's header strip that opens the scenario
   matrix of the scope on screen (the explorer moves between scopes in place: the link follows the page's address, read when it is used). */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  var strip = root && root.querySelector(".vx-status");
  if (!strip || strip.querySelector("[data-smat-link]")) { return; }
  function scope() {
    var q = new URLSearchParams(window.location.search);
    var s = q.get("scope") || root.dataset.scope || "BANK";
    return s.replace(/^POS:/, "").replace(/^TRD:/, "");
  }
  var a = document.createElement("a");
  a.className = "vx-help vx-what";
  a.setAttribute("data-smat-link", "");
  a.title = "Scenario matrix: this scope's scenario P&L by scenario day across the last 20 business dates, with each date's VaR and ES tail (SMAT)";
  var i = document.createElement("i");
  i.className = "bi bi-grid-3x3";
  i.setAttribute("aria-hidden", "true");
  a.appendChild(i);
  a.appendChild(document.createTextNode(" Scenario matrix"));
  function refresh() {
    var q = new URLSearchParams(window.location.search);
    a.href = "/smat?id=" + encodeURIComponent(scope()) + (q.get("ccy") ? "&ccy=" + encodeURIComponent(q.get("ccy")) : "");
  }
  refresh();
  ["mouseenter", "focus", "mousedown", "touchstart"].forEach(function (e) { a.addEventListener(e, refresh, { passive: true }); });
  window.addEventListener("popstate", refresh);
  var help = strip.querySelector(".vx-help");
  strip.insertBefore(a, help ? help.nextSibling : null);
})();
