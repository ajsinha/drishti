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
/*
 * The sign-in page under busy protection (docs/admin/OPERATIONS.md "Busy protection"; mock-up A, panel 1). The form is sent in the
 * background; when the server says sign-ins wait (503 DRS-9501, Retry-After), the calm message counts down above the fields, the form keeps
 * what was typed, and it signs in by itself when the time is up (three times at most; Try now and Cancel beside it). Any other answer is
 * the page the server sent, shown as it is.
 */
(function () {
  "use strict";
  var form = document.querySelector("form.login-box");
  var host = document.querySelector("[data-busy-host]");
  var button = document.querySelector("[data-sign-in]");
  if (!form || !host || !window.DrishtiBusy || !window.fetch) return;
  var label = button ? button.textContent : "Sign in";

  function waiting(on) {
    if (!button) return;
    button.disabled = on;
    button.textContent = on ? "Waiting to sign you in…" : label;
  }

  // while the form is on its way the page says so (html[data-signing-in]): the page that follows has no such mark, and a busy answer
  // takes it away as the countdown shows; a script driving the page (the browser tests) waits on it as on a navigation
  function sending(on) {
    if (on) { document.documentElement.setAttribute("data-signing-in", "1"); } else { document.documentElement.removeAttribute("data-signing-in"); }
  }

  function send() {
    sending(true);
    var body = new URLSearchParams(new FormData(form));
    return fetch(form.action || "/login", {
      method: "POST", credentials: "same-origin", redirect: "follow",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Drishti-Busy": "1" }, body: body.toString()
    }).then(function (r) {
      if (r.status === 503 && (r.headers.get("content-type") || "").indexOf("json") >= 0) {
        return r.json().then(function (b) {
          var p = window.DrishtiBusy.problem(b);
          if (!p) { location.reload(); return; }
          sending(false);
          waiting(true);
          window.DrishtiBusy.render(host, p, { retry: send, keep: "keep this page open.",
            cancel: function () { window.DrishtiBusy.done(host); host.textContent = ""; waiting(false); } });
        });
      }
      if (r.redirected) { location.assign(r.url); return; }
      return r.text().then(function (html) { document.open(); document.write(html); document.close(); });
    }).catch(function () { sending(false); window.DrishtiBusy.done(host); waiting(false); form.submit(); });
  }

  form.addEventListener("submit", function (e) {
    if (!form.checkValidity()) return;
    e.preventDefault();
    window.DrishtiBusy.done(host);
    send();
  });

  // the page came back from the server with sign-ins waiting (sent without this script): count down; the password is typed again
  var raw = host.getAttribute("data-busy");
  if (raw) {
    var p = window.DrishtiBusy.problem(JSON.parse(raw));
    if (p) window.DrishtiBusy.render(host, p, {});
  }
})();
