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
/* Admin -> Ad-hoc scopes: Delete on each person's saved VaR basket, through the confirm dialog of drishti_scope-delete.js (with an optional
   reason for someone else's). A deleted basket's row leaves the table and the page says so; the filter is the page's own GET form. */
(function () {
  "use strict";
  var root = document.querySelector("[data-scope-admin]");
  if (!root || !window.Drishti.ScopeDelete) return;
  var msg = root.querySelector("[data-msg]");
  root.querySelectorAll("[data-scope-delete]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var tr = btn.closest("tr");
      var shared = tr.dataset.shared ? tr.dataset.shared.split(",") : [];
      var b = { id: tr.dataset.basket, owner: tr.dataset.owner, name: tr.dataset.name, version: tr.dataset.version, sharedWith: shared };
      window.Drishti.ScopeDelete.ask(b, { admin: true, me: root.dataset.me }).then(function (deleted) {
        if (!deleted) return;
        var body = tr.parentNode, next = tr.nextElementSibling || tr.previousElementSibling;
        tr.remove();
        msg.textContent = "Deleted the basket " + b.name + " (BASKET:" + b.id + ")" + (b.owner === root.dataset.me ? "." : "; " + b.owner + " was told in their inbox.");
        var focus = next && next.querySelector("[data-scope-delete]");
        if (focus) focus.focus(); else root.querySelector("[data-scope-owner]").focus();
        if (!body.querySelector("tr")) {
          var empty = document.createElement("tr"), td = document.createElement("td");
          td.colSpan = 8;
          td.textContent = "No baskets left here.";
          empty.appendChild(td);
          body.appendChild(empty);
        }
      });
    });
  });
})();
