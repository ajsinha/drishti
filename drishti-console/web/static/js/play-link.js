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
/* The VaR page's "Playground" link (VAR_GUIDE.md section 3.10): loaded by base.html on the VaR explorer only when the person's edition
   or lens has the PLAY word (Drishti Risk and Drishti Trading), it adds one link to the explorer's header strip that opens the VaR
   Playground of the scope on screen, with the page's currency and lens (read from the page's address when the link is used). */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  var strip = root && root.querySelector(".vx-status");
  if (!strip || strip.querySelector("[data-play-link]")) { return; }
  var a = document.createElement("a");
  a.className = "vx-help vx-what";
  a.setAttribute("data-play-link", "");
  a.title = "VaR Playground: every figure of this scope worked in a grid with the explanation beside it, and exported as an Excel workbook of real formulas (PLAY VAR)";
  var i = document.createElement("i");
  i.className = "bi bi-calculator";
  i.setAttribute("aria-hidden", "true");
  a.appendChild(i);
  a.appendChild(document.createTextNode(" Playground"));
  function refresh() {
    var q = new URLSearchParams(window.location.search), out = new URLSearchParams();
    out.set("scope", q.get("scope") || root.dataset.scope || "BANK");
    ["ccy", "h", "m", "q", "esq", "set"].forEach(function (k) { if (q.get(k)) { out.set(k, q.get(k)); } });
    a.href = "/play?" + out.toString();
  }
  refresh();
  ["mouseenter", "focus", "mousedown", "touchstart"].forEach(function (e) { a.addEventListener(e, refresh, { passive: true }); });
  window.addEventListener("popstate", refresh);
  var help = strip.querySelector(".vx-help");
  strip.insertBefore(a, help ? help.nextSibling : null);
})();
