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
/* Admin: "Retry now" for what failed (packs, connectors, AI providers, MCP servers), and the countdown in "next retry in Ns".
   POSTs {} to same-origin /admin/api/retry/<area>; the server enforces the admin role and audits it. Text from the server is only
   ever put in the page as text. */
(function () {
  'use strict';

  document.addEventListener('click', function (ev) {
    var b = ev.target.closest ? ev.target.closest('[data-retry-now]') : null;
    if (!b) { return; }
    var msg = b.parentNode.querySelector('[data-retry-msg]');
    b.disabled = true;
    if (msg) { msg.textContent = 'Trying again…'; }
    fetch('/admin/api/retry/' + encodeURIComponent(b.getAttribute('data-retry-now')), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (body) { return { ok: r.ok, body: body }; });
    }).then(function (r) {
      if (!r.ok) {
        if (msg) { msg.textContent = (window.drsMessage ? window.drsMessage({ code: r.body.code, detail: r.body.detail }, 'Retry failed') : (r.body.detail || 'Retry failed')); }
        b.disabled = false;
        return;
      }
      if (msg) { msg.textContent = r.body.serverError ? 'Tried; the server said: ' + r.body.serverError : 'Tried again. Refreshing…'; }
      setTimeout(function () { location.reload(); }, 900);
    }).catch(function () {
      if (msg) { msg.textContent = 'Retry failed: the console could not be reached.'; }
      b.disabled = false;
    });
  });

  // Count the seconds down so the page does not say "in 5s" for a minute; at zero it says the retry is due.
  setInterval(function () {
    document.querySelectorAll('[data-retry] [data-retry-in]').forEach(function (n) {
      var v = parseInt(n.textContent, 10);
      if (!isNaN(v) && v > 0) { n.textContent = String(v - 1); }
    });
  }, 1000);
})();
