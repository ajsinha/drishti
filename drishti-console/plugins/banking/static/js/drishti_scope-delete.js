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
/* Deleting a saved VaR basket (an ad-hoc scope kept by name, docs/guides/VAR_GUIDE.md section 3.5), shared by the VaR explorer's
   Ad-hoc scopes list and Admin -> Ad-hoc scopes. One modal dialog names the basket, its owner and whom it is shared with (they lose it
   too), says captures of it are kept, and, for an administrator deleting someone else's, takes an optional reason (the audit log and the
   owner's inbox carry it). It sends DELETE /api/var/baskets/<owner>/<name>?version=<the version shown> so a basket changed meanwhile is
   refused (409) instead of deleted unseen. Esc or Cancel keeps it; focus starts on Cancel and returns to the button that opened it.
   window.Drishti.ScopeDelete.ask(basket, {admin, me}) resolves true when the basket was deleted, false when kept. */
(function () {
  "use strict";

  var dlg = null, el = {};

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function build() {
    dlg = h("dialog", "adm-dialog sd-dialog");
    dlg.dataset.sdDialog = "";
    dlg.setAttribute("aria-labelledby", "sdTitle");
    el.title = h("h2", null, "Delete basket");
    el.title.id = "sdTitle";
    el.what = h("p", "sd-what");
    el.shared = h("p", "sd-shared");
    el.shared.dataset.sdShared = "";
    el.kept = h("p", "sd-kept", "Captures taken of it are kept and still open, with the scope and members they were taken with. "
      + "Deleting cannot be undone.");
    el.reasonRow = h("label", "sd-reason");
    el.reasonRow.appendChild(document.createTextNode("Reason (optional: written to the audit log and sent to the owner)"));
    el.reason = h("input", "studio-in");
    el.reason.type = "text";
    el.reason.maxLength = 500;
    el.reason.dataset.sdReason = "";
    el.reasonRow.appendChild(el.reason);
    el.msg = h("p", "adm-msg");
    el.msg.setAttribute("role", "status");
    el.msg.setAttribute("aria-live", "polite");
    var row = h("div", "adm-dlg-actions ds-actions");
    el.no = h("button", "btn-pill btn-ghost", "Keep it");
    el.no.type = "button";
    el.no.dataset.sdNo = "";
    el.yes = h("button", "btn-pill btn-accent sd-danger", "Delete basket");
    el.yes.type = "button";
    el.yes.dataset.sdYes = "";
    row.appendChild(el.no);
    row.appendChild(el.yes);
    [el.title, el.what, el.shared, el.kept, el.reasonRow, el.msg, row].forEach(function (x) { dlg.appendChild(x); });
    document.body.appendChild(dlg);
  }

  function sharedLine(list) {
    if (!list || !list.length) return "Not shared with anyone.";
    return "Shared read-only with " + list.map(function (w) { return String(w).replace(/^role:/, "the role "); }).join(", ")
      + ": they lose it too.";
  }

  function remove(b, reason) {
    var cut = String(b.id).indexOf("/");
    var url = "/api/var/baskets/" + encodeURIComponent(String(b.id).slice(0, cut)) + "/" + encodeURIComponent(String(b.id).slice(cut + 1));
    var q = new URLSearchParams();
    if (b.version !== undefined && b.version !== null) q.set("version", String(b.version));
    if (reason) q.set("reason", reason);
    url += q.toString() ? "?" + q.toString() : "";
    return fetch(url, { method: "DELETE", credentials: "same-origin", headers: { Accept: "application/json" } }).then(function (r) {
      if (r.ok) return true;
      return r.json().catch(function () { return {}; }).then(function (body) {
        var e = new Error(body.detail || ("HTTP " + r.status));
        e.status = r.status;
        throw e;
      });
    });
  }

  /** Asks, then deletes: true when deleted, false when kept. {admin: the viewer is an administrator, me: the viewer's user name}. */
  var pending = null;   // the open ask's finish: a new ask settles it first (its dialog's close event may still be queued)
  function ask(b, opts) {
    opts = opts || {};
    if (!dlg) build();
    if (pending) pending(false);
    var opener = document.activeElement;
    var others = opts.admin && b.owner !== opts.me;
    el.title.textContent = "Delete basket “" + (b.name || b.id) + "”?";
    el.what.textContent = "BASKET:" + b.id + " · owned by " + (b.owner === opts.me ? "you" : b.owner)
      + (others ? " (you are deleting it as an administrator)" : "") + ".";
    el.shared.textContent = sharedLine(b.sharedWith);
    el.reasonRow.hidden = !others;
    el.reason.value = "";
    el.msg.textContent = "";
    el.yes.disabled = false;
    return new Promise(function (resolve) {
      var done = false;
      function finish(v) {
        if (done) return;
        done = true;
        if (pending === finish) pending = null;
        el.yes.removeEventListener("click", go);
        el.no.removeEventListener("click", keep);
        dlg.removeEventListener("close", closed);
        if (dlg.open) dlg.close();
        if (opener && opener.isConnected && opener.focus) opener.focus();
        resolve(v);
      }
      function keep() { finish(false); }
      function closed() { if (!dlg.open) finish(false); }   // a close event that arrives after the dialog opened again is not this one's
      function go() {
        el.yes.disabled = true;
        el.msg.textContent = "Deleting…";
        remove(b, others ? el.reason.value.trim() : "").then(function () { finish(true); }).catch(function (e) {
          el.yes.disabled = false;
          el.msg.textContent = (e.status === 409 ? "It changed since you looked: close and look again. " : "") + e.message;
        });
      }
      el.yes.addEventListener("click", go);
      el.no.addEventListener("click", keep);
      dlg.addEventListener("close", closed);
      pending = finish;
      dlg.showModal();
      el.no.focus();
    });
  }

  window.Drishti.ScopeDelete = { ask: ask, remove: remove };
})();
