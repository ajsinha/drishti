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
/* Help → Configuration: the search as you type. The server filters too (?q=, ?area=, so a link or a page without script works); here
   each settings row's words (data-hay) are matched against every word typed, the area filter keeps one area, the explanation is hidden
   while a search is on, and the address keeps the search so Back and a shared link return to it. */
(function () {
  "use strict";
  var page = document.querySelector("[data-cfg-page]");
  if (!page) return;
  var input = page.querySelector("[data-cfg-q]");
  var select = page.querySelector("[data-cfg-area]");
  var count = page.querySelector("[data-cfg-count]");
  var rows = Array.prototype.slice.call(page.querySelectorAll("tr.cfg-row"));
  var wraps = Array.prototype.slice.call(page.querySelectorAll(".cfg-wrap"));
  var areas = Array.prototype.slice.call(page.querySelectorAll("section.cfg-area"));
  var explain = page.querySelector(".cfg-explain");
  var intro = page.querySelector(".cfg-intro");
  var ref = page.querySelector(".cfg-ref");
  var timer = null;

  function apply() {
    var words = (input.value || "").toLowerCase().split(/\s+/).filter(Boolean);
    var area = select ? select.value : "";
    var filtered = words.length > 0 || area !== "";
    var shown = 0, perArea = {};
    rows.forEach(function (tr) {
      var hay = tr.getAttribute("data-hay") || "";
      var ok = (!area || tr.getAttribute("data-area") === area) && words.every(function (w) { return hay.indexOf(w) >= 0; });
      tr.hidden = !ok;
      if (ok) { shown++; perArea[tr.getAttribute("data-area")] = true; }
    });
    wraps.forEach(function (w) {
      var any = w.querySelector("tr.cfg-row:not([hidden])");
      if (any) w.removeAttribute("data-cfg-empty"); else w.setAttribute("data-cfg-empty", "");
    });
    areas.forEach(function (s) { s.hidden = filtered && !perArea[s.getAttribute("data-area")]; });
    if (explain) explain.hidden = filtered;
    if (intro) intro.hidden = filtered;
    if (ref) ref.classList.toggle("cfg-filtered", filtered);
    if (count) {
      count.textContent = filtered ? shown + " of " + rows.length + " settings match" + (shown ? "." : ": try fewer words, or another area.")
                                   : rows.length + " settings.";
    }
    var url = new URL(window.location.href);
    if (words.length) url.searchParams.set("q", input.value.trim()); else url.searchParams.delete("q");
    if (area) url.searchParams.set("area", area); else url.searchParams.delete("area");
    window.history.replaceState(null, "", url.pathname + url.search + url.hash);
  }

  input.addEventListener("input", function () { clearTimeout(timer); timer = setTimeout(apply, 120); });
  if (select) select.addEventListener("change", apply);
  page.querySelector("[data-cfg-form]").addEventListener("submit", function (e) { e.preventDefault(); apply(); });
})();
