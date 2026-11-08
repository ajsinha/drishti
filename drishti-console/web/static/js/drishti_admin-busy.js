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
/* Admin -> Health, busy protection's operator override (docs/guides/OPERATIONS_GUIDE.md "Busy protection"): Automatic, Let everything through or
   Hold all new heavy work, with a reason and the minutes it lasts. The server checks the reason, applies it, audits it and ends it by
   itself; the card above shows the override in force at its next refresh. */
(function () {
  "use strict";
  // the gauges' bars and markers: widths from data attributes (the page's policy allows no inline style), again after each refresh
  function bars() {
    document.querySelectorAll("[data-busy-w]").forEach(function (i) { i.style.width = i.getAttribute("data-busy-w") + "%"; });
    document.querySelectorAll("[data-busy-x]").forEach(function (u) { u.style.left = "calc(" + u.getAttribute("data-busy-x") + "% - 1px)"; });
  }
  bars();
  var body = document.querySelector("[data-health-body]");
  if (body && window.MutationObserver) new MutationObserver(bars).observe(body, { childList: true, subtree: true });
  var box = document.querySelector("[data-busy-override]");
  if (!box) return;
  var said = box.querySelector("[data-busy-said]");
  box.querySelector("[data-busy-apply]").addEventListener("click", function () {
    var mode = (box.querySelector("input[name=busy-mode]:checked") || {}).value || "automatic";
    var reason = box.querySelector("[data-busy-reason]").value.trim();
    if (mode !== "automatic" && !reason) {
      said.textContent = "A reason is required.";
      box.querySelector("[data-busy-reason]").focus();
      return;
    }
    said.textContent = "Applying…";
    fetch("/admin/api/busy/override", {
      method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ mode: mode, reason: reason, minutes: parseInt(box.querySelector("[data-busy-minutes]").value, 10) || 30 })
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { said.textContent = (b.code ? b.code + " " : "") + (b.detail || "not applied"); return; }
        var o = b.override || {};
        said.textContent = o.mode === "automatic" ? "Automatic again." : "Applied until " + String(o.until || "").slice(11, 16) + " UTC; recorded in the audit log.";
      });
    }).catch(function () { said.textContent = "The console could not reach the server; nothing changed."; });
  });
})();
