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
/* Admin → Cluster → Modules: Plan sends {placement, reason} from drishti-inputs' Form (POST /admin/net/api/placement/plan); a second
   administrator's Apply or Reject, or the proposer's Withdraw, is sent the same way. The server decides and says why it refuses (DRS-1030,
   DRS-1031, DRS-2007); the answer stays in the line above the matrix and the page reloads on the Modules tab to show the change. */
(function () {
  'use strict';
  var root = document.querySelector('[data-drishti-placement]');
  if (!root) { return; }
  var said = root.querySelector('[data-placement-said]');
  var host = root.querySelector('[data-placement-fields]');
  var F = null;

  function say(text, bad) {
    if (!said) { return; }
    said.textContent = text;
    said.classList.toggle('t-bad', !!bad);
    said.classList.toggle('t-ok', !bad);
  }

  function send(action, body) {
    return window.Drishti.elements.core.api.send('POST', '/admin/net/api/placement/' + action, body || {})   // drishti-core's client: never rejects
      .then(function (res) {
        var j = res.body || {};
        if (!res.ok) {
          say((j.code ? j.code + ' ' : '') + (j.detail || 'the server said no'), true);
          return;
        }
        say(action === 'plan' ? 'Planned: a second administrator applies it.' : 'Done: ' + (j.state || action) + '.');
        setTimeout(function () { window.location.assign('/admin/net?tab=modules'); }, 1200);
      });
  }

  root.addEventListener('click', function (e) {
    if (e.target.closest('[data-placement-plan]') && host) {
      F = F || window.Drishti.elements.inputs.formOf(host);
      if (!F.validate().ok) { say('Give the placement and say why it changes.', true); return; }
      var v = F.getValue();
      send('plan', { placement: v.placement, reason: v.reason });
      return;
    }
    var act = e.target.closest('[data-plan-act]');
    if (act) {
      send(act.getAttribute('data-plan-act'), { id: act.getAttribute('data-id') });
    }
  });
})();
