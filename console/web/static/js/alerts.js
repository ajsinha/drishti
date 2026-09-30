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
/* Notifications on every signed-in page: a bell in the top bar counts new alerts, and each one shows as a toast
   (and a system notification when the browser allows). Not loaded inside workspace panes. */
(function () {
  'use strict';
  var bell = document.querySelector('[data-bell]');
  if (!bell || !window.DrishtiChannel || document.querySelector('[data-embed]')) { return; }
  var count = 0, badge = bell.querySelector('[data-bell-count]');
  var tray = document.createElement('div');
  tray.className = 'toasts'; tray.setAttribute('role', 'status'); tray.setAttribute('aria-live', 'polite');
  document.body.appendChild(tray);
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  window.DrishtiChannel.subscribe('alerts', { alert: onAlert });   // on the tab's one live connection (channel.js)
  function onAlert(a) {
    count++; badge.textContent = String(count); badge.hidden = false;
    var t = document.createElement('a');
    t.className = 'toast-a sev-' + a.severity;
    t.href = '/v/' + encodeURIComponent(a.kind) + '/' + encodeURIComponent(a.id);
    t.innerHTML = '<b>' + esc(a.severity) + '</b> <span class="mono">' + esc(a.id) + '</span> ' + esc(a.message);
    tray.appendChild(t);
    setTimeout(function () { t.remove(); }, 9000);
    if (window.Notification && Notification.permission === 'granted') {
      try { new Notification('Drishti · ' + a.severity, { body: a.id + ': ' + a.message }); } catch (err) { /* not available */ }
    }
  }
  bell.addEventListener('click', function () {
    if (window.Notification && Notification.permission === 'default') { Notification.requestPermission(); }
  });
})();
