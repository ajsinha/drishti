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
/* Notifications on every signed-in page: one bell in the top bar counts what is new for you, and each new thing shows as a toast (and a
   system notification when the browser allows). Alerts (rules that fired) and notices (a share sent to you, COLLABORATION.md, Decision 10)
   arrive on the same live connection; the count starts from the inbox's unread notices (GET /api/inbox/count) and the bell opens the
   inbox (Alt+I does too). The inbox page tells this file when it marks notices read (drishti:unread). Not loaded inside workspace panes. */
(function () {
  'use strict';
  var bell = document.querySelector('[data-bell]');
  if (!bell || !window.DrishtiChannel || document.querySelector('[data-embed]')) { return; }
  var count = 0, badge = bell.querySelector('[data-bell-count]');
  var tray = document.createElement('div');
  tray.className = 'toasts'; tray.setAttribute('role', 'status'); tray.setAttribute('aria-live', 'polite');
  document.body.appendChild(tray);
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function show() { badge.textContent = String(count); badge.hidden = count <= 0; }
  window.DrishtiChannel.subscribe('alerts', { alert: onAlert, notice: onNotice });   // on the tab's one live connection (channel.js)
  fetch('/api/inbox/count', { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : { unread: 0 }; })
    .then(function (j) { count += (j && j.unread) || 0; show(); })
    .catch(function () { /* the count is a convenience */ });
  document.addEventListener('drishti:unread', function (e) { count = e.detail; });
  function toast(cls, href, html) {
    var t = document.createElement('a');
    t.className = cls; t.href = href; t.innerHTML = html;
    tray.appendChild(t);
    setTimeout(function () { t.remove(); }, 9000);
  }
  function onNotice(n) {
    count++; show();
    var href = n.shareId ? '/share/' + encodeURIComponent(n.shareId) : (n.access && n.kind && n.id ? '/v/' + encodeURIComponent(n.kind) + '/' + encodeURIComponent(n.id) : '/inbox');
    toast('toast-a sev-info notice', href, '<b>' + esc(n.type || 'notice') + '</b> ' + esc(n.title) + (n.excerpt ? ' <span class="toast-x">\u201c' + esc(n.excerpt) + '\u201d</span>' : ''));
    if (window.Notification && Notification.permission === 'granted') {
      try { new Notification((document.body.getAttribute('data-product') || document.title) + ' \u00b7 ' + (n.type || 'notice'), { body: n.title }); } catch (err) { /* not available */ }
    }
  }
  function onAlert(a) {
    count++; show();
    toast('toast-a sev-' + a.severity, '/v/' + encodeURIComponent(a.kind) + '/' + encodeURIComponent(a.id),
      '<b>' + esc(a.severity) + '</b> <span class="mono">' + esc(a.id) + '</span> ' + esc(a.message));
    if (window.Notification && Notification.permission === 'granted') {
      try { new Notification((document.body.getAttribute('data-product') || document.title) + ' · ' + a.severity, { body: a.id + ': ' + a.message }); } catch (err) { /* not available */ }
    }
  }
  document.addEventListener('keydown', function (e) {           // Alt+I: the inbox (ignored while typing in a field)
    var t = e.target;
    if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.code === 'KeyI' || e.key === 'i') && !(t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)))) {
      e.preventDefault(); window.location.href = '/inbox';
    }
  });
  bell.addEventListener('click', function () {
    if (window.Notification && Notification.permission === 'default') { Notification.requestPermission(); }
  });
})();
