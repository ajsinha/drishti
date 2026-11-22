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
(function (g) {
  'use strict';
  /* The role Home's behaviour, nothing drawn here (docs/architecture/ROLE_HOME_SUTRAS.md): the picker keeps the person's start Home,
     "Arrange my Home" switches the grid's arrange mode (drishti_screen-grid.js draws the handles; the arrangement is the person's, kept
     by /me/arrange/<sutra>), and a figure's click event (the Rachana events open0..open7 of core/kpi-strip, open of the lists) runs its
     command in the terminal. The Sutra's panels are drawn by the Elements renderer into [data-role-home-view]; TODO: the server's view
     model of a Home (the bind of the Sutra over the home tables) is the one thing not wired yet. */
  var root = document.querySelector('[data-role-home]');
  if (!root) { return; }
  var D = (g.Drishti = g.Drishti || {});

  function csrf() {
    var m = document.querySelector('meta[name="csrf-token"]');
    return m ? m.getAttribute('content') : '';
  }

  var pick = root.querySelector('[data-role-home-pick]');
  if (pick) {
    pick.addEventListener('change', function () {
      fetch('/api/home/me', {
        method: 'PUT', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf() },
        body: JSON.stringify({ home: pick.value })
      }).then(function (r) {
        if (r.ok) { g.location.href = '/home/role/' + encodeURIComponent(pick.value); }
      });
    });
  }

  var arrange = root.querySelector('[data-role-home-arrange]');
  if (arrange) {
    arrange.addEventListener('click', function () {
      var on = arrange.getAttribute('aria-pressed') !== 'true';
      arrange.setAttribute('aria-pressed', on ? 'true' : 'false');
      root.classList.toggle('is-arranging', on);
      root.dispatchEvent(new CustomEvent('drishti:arrange', { bubbles: true, detail: { on: on } }));
    });
  }

  /* An event the renderer raises from a panel: { name, payload: { command } }. Only commands the Home's own tables carry run. */
  root.addEventListener('rachana:event', function (e) {
    var d = e.detail || {};
    var cmd = d.payload && d.payload.command;
    if (/^(open[0-7]?|open)$/.test(d.name || '') && typeof cmd === 'string' && cmd.trim()) {
      g.location.href = '/go?q=' + encodeURIComponent(cmd.trim());
    }
  });

  D.roleHome = { root: root };
})(typeof window !== 'undefined' ? window : globalThis);
