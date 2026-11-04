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
/* Admin -> AI: "Test connection" beside each console provider. POSTs {} to same-origin /admin/ai/api/test/<name>; the console enforces the admin role,
   bounds the wait and audits the test. The answer (ok or not, what the endpoint said, the models it lists) is only ever put in the page as text. */
(function () {
  'use strict';

  document.addEventListener('click', function (ev) {
    var b = ev.target.closest ? ev.target.closest('[data-ai-test]') : null;
    if (!b) { return; }
    var name = b.getAttribute('data-ai-test');
    var out = null;
    document.querySelectorAll('[data-ai-test-result]').forEach(function (n) { if (n.getAttribute('data-ai-test-result') === name) { out = n; } });
    b.disabled = true;
    if (out) { out.textContent = 'Testing…'; out.removeAttribute('data-ok'); }
    fetch('/admin/ai/api/test/' + encodeURIComponent(name), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (body) { return { ok: r.ok, body: body }; });
    }).then(function (r) {
      var d = r.body || {};
      if (out) {
        if (!r.ok) {
          out.textContent = d.detail || 'The test failed.';
          out.setAttribute('data-ok', 'no');
        } else {
          var line = (d.ok ? 'OK: ' : 'Not OK: ') + (d.detail || '') + (d.millis !== undefined ? ' (' + d.millis + ' ms)' : '');
          if (d.models && d.models.length) { line += ' Models listed now: ' + d.models.slice(0, 8).join(', ') + (d.models.length > 8 ? ', …' : '') + '.'; }
          if (d.network === false && d.ok) { line += ' This type makes no live call.'; }
          out.textContent = line;
          out.setAttribute('data-ok', d.ok ? 'yes' : 'no');
        }
      }
      b.disabled = false;
    }).catch(function () {
      if (out) { out.textContent = 'The console could not be reached.'; out.setAttribute('data-ok', 'no'); }
      b.disabled = false;
    });
  });
})();
