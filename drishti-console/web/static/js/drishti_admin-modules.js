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
/* Admin → Modules → Deploy: Upload sends the .dmod's bytes (POST /admin/modules/api/upload; the server runs the fifteen checks once and keeps
   it in the module store); Plan sends {action, module, version, reason} from drishti-inputs' Form (POST /admin/modules/api/plan) and opens the
   plan; Apply, Reject and Withdraw send the decision. The server decides and says why it refuses (DRS-1033, DRS-1031, DRS-2007); the answer
   stays in the line under the title. */
(function () {
  'use strict';
  var root = document.querySelector('[data-module-deploy]');
  if (!root) { return; }
  var said = root.querySelector('[data-module-said]');
  var api = window.Drishti.elements.core.api;

  function say(text, bad) {
    if (!said) { return; }
    said.textContent = text;
    said.classList.toggle('t-bad', !!bad);
    said.classList.toggle('t-ok', !bad);
  }

  function problem(j) { return (j && j.code ? j.code + ' ' : '') + ((j && j.detail) || 'the server said no'); }

  root.addEventListener('click', function (e) {
    if (e.target.closest('[data-module-upload-go]')) {
      var input = root.querySelector('[data-module-file] input[type=file], input[type=file][data-module-file]');
      var f = input && input.files && input.files[0];
      if (!f) { say('Choose a .dmod file first.', true); return; }
      say('Uploading ' + f.name + ' and running the fifteen checks …');
      fetch('/admin/modules/api/upload', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f })
        .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); })
        .then(function (res) {
          if (!res.ok) { say(problem(res.body), true); return; }
          say('Stored ' + res.body.id + ' ' + res.body.version + ': all checks passed. Plan it on the right.');
          setTimeout(function () {
            window.location.assign('/admin/modules/deploy?module=' + encodeURIComponent(res.body.id) + '&version=' + encodeURIComponent(res.body.version));
          }, 900);
        });
      return;
    }
    if (e.target.closest('[data-module-plan-go]')) {
      var host = root.querySelector('[data-module-plan-fields]');
      var F = window.Drishti.elements.inputs.formOf(host);
      if (!F.validate().ok) { say('Choose the action and the module, and say why.', true); return; }
      var v = F.getValue();
      api.send('POST', '/admin/modules/api/plan', { action: v.action, module: v.module, version: v.version || '', reason: v.reason }).then(function (res) {
        if (!res.ok) { say(problem(res.body), true); return; }
        window.location.assign('/admin/modules/deploy?plan=' + encodeURIComponent(res.body.id));
      });
      return;
    }
    var act = e.target.closest('[data-module-act]');
    if (act) {
      var box = root.querySelector('[data-module-apply]');
      var what = act.getAttribute('data-module-act');
      act.disabled = true;
      if (what === 'apply') { say('Applying: staging on every placed node, then activating …'); }
      api.send('POST', '/admin/modules/api/' + what, { id: box.getAttribute('data-id') }).then(function (res) {
        act.disabled = false;
        if (!res.ok) { say(problem(res.body), true); return; }
        say((res.body.state || what) + (res.body.outcome ? ': ' + res.body.outcome : '') + '.', res.body.state === 'failed');
        setTimeout(function () { window.location.reload(); }, 1200);
      });
    }
  });
})();
