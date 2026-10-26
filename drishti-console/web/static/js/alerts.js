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
  window.DrishtiChannel.subscribe('alerts', { alert: onAlert, notice: onNotice, switches: onSwitches, edit: onEdit });   // on the tab's one live connection (channel.js)
  fetch('/api/inbox/count', { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : { unread: 0 }; })
    .then(function (j) { count += (j && j.unread) || 0; show(); })
    .catch(function () { /* the count is a convenience */ });
  document.addEventListener('drishti:unread', function (e) { count = e.detail; });
  var MAX_TOASTS = 3, more = null, moreCount = 0;                      // a burst shows three toasts and one "N more on the bell"
  /* Admin -> Commands changed what is on (docs/admin/COMMANDS.md): a page of a kind now off is drawn again (the server says "not active"),
     the not-active page of a command now on too, and links to a kind now off stop being links at once. Other pages hear it as drishti:switches. */
  function onSwitches(d) {
    var kinds = (d && d.kinds) || [], commands = (d && d.commands) || [];
    var page = document.querySelector('[data-view][data-kind]'), off = document.querySelector('[data-command-off]');
    if ((page && kinds.indexOf(page.getAttribute('data-kind')) >= 0) || (off && commands.indexOf(off.getAttribute('data-command-off')) < 0)) {
      window.location.reload();
      return;
    }
    kinds.forEach(function (k) {
      document.querySelectorAll('a[href^="/v/' + k + '/"]').forEach(function (a) {
        var s = document.createElement('span');
        s.className = 'lnk-off'; s.textContent = a.textContent; s.title = 'Not active: an administrator turned this command off';
        a.replaceWith(s);
      });
    });
    document.dispatchEvent(new CustomEvent('drishti:switches', { detail: d }));
  }
  /* Write-back (WRITE_BACK.md 18.5): your own change's steps as a toast (and to the review sheet that follows it); a change applied to the
     record this page shows says "changed by A. Sinha just now" (the name only for who may see the edit history; the page's live view
     brings the new values, masked for this reader). Everything else hears it as drishti:edit. */
  function onEdit(d) {
    document.dispatchEvent(new CustomEvent('drishti:edit', { detail: d }));
    var page = document.querySelector('[data-view][data-kind]');
    var here = page && ((page.getAttribute('data-kind') === d.kind && page.getAttribute('data-id') === d.entity)
      || (page.getAttribute('data-kind') === d.pageKind && page.getAttribute('data-id') === d.pageId));
    var href = '/v/' + encodeURIComponent(d.kind) + '/' + encodeURIComponent(d.entity);
    if (d.mine && ['applied', 'refused', 'conflicted', 'rejected', 'approved', 'confirmed', 'refused-at-source'].indexOf(d.type) >= 0) {
      var what = { applied: 'was applied', refused: 'was refused', conflicted: 'is conflicted: the record moved', rejected: 'was rejected',
        approved: 'was approved', confirmed: 'was confirmed by the source', 'refused-at-source': 'was refused by the source' }[d.type];
      toast('sev-' + (d.type === 'applied' || d.type === 'approved' || d.type === 'confirmed' ? 'info' : 'warn') + ' notice', href,
        '<b>Your change to ' + esc(d.kind) + ' ' + esc(d.entity) + ' ' + what + '</b>' + (d.reason ? '<br>' + esc(d.reason) : ''));
    }
    if (here && (d.type === 'applied' || d.type === 'withdrawn-overlay' || d.type === 'refused-at-source')) {
      var editing = document.querySelector('section.pnl[data-editing], [data-wb-sheet][open]');
      var words = (d.type === 'applied' ? 'Changed' : 'A correction was withdrawn') + (d.mine ? ' by you' : d.by ? ' by ' + d.by : '') + ' just now';
      // a page whose source streams gets the new values in its live frames; any other page of the current date is drawn again (its
      // values read with your masks), unless you are editing on it: then it says so and leaves your work alone
      if (!editing && !page.hasAttribute('data-live') && !page.hasAttribute('data-static')) {
        try { sessionStorage.setItem('drishti-edit-mark', JSON.stringify({ kind: page.getAttribute('data-kind'), id: page.getAttribute('data-id'), text: words })); } catch (e) { /* a convenience */ }
        window.location.reload();
        return;
      }
      editMark(page, words + (editing ? ': it changed while you edited; check your values before you save' : ''));
    }
  }
  function editMark(page, text) {
    var mark = document.querySelector('[data-edit-mark]');
    if (!mark) {
      mark = document.createElement('p'); mark.className = 'asof-banner'; mark.setAttribute('data-edit-mark', ''); mark.setAttribute('role', 'status');
      page.prepend(mark);
    }
    mark.textContent = text;
  }
  (function () {                                       // the mark of a change that drew this page again
    var page = document.querySelector('[data-view][data-kind]'), m = null;
    try { m = JSON.parse(sessionStorage.getItem('drishti-edit-mark') || 'null'); sessionStorage.removeItem('drishti-edit-mark'); } catch (e) { m = null; }
    if (page && m && m.kind === page.getAttribute('data-kind') && m.id === page.getAttribute('data-id')) { editMark(page, m.text); }
  }());
  function toast(cls, href, html) {
    if (tray.querySelectorAll('.toast-a:not(.toast-more)').length >= MAX_TOASTS) {
      moreCount++;
      if (!more) {
        more = document.createElement('a');
        more.className = 'toast-a sev-info notice toast-more'; more.href = '/inbox';
        tray.appendChild(more);
        setTimeout(function () { if (more) { more.remove(); more = null; moreCount = 0; } }, 9000);
      }
      more.innerHTML = '<b>+' + moreCount + '</b> more on the bell';
      return;
    }
    var t = document.createElement('a');
    t.className = cls; t.href = href; t.innerHTML = html;
    tray.insertBefore(t, more);
    setTimeout(function () { t.remove(); }, 9000);
  }
  function onNotice(n) {
    count++; show();
    var alertPath = n.type === 'alert' && typeof n.id === 'string' && n.id.charAt(0) === '/' && n.id.charAt(1) !== '/' ? n.id : '';       // an alert on a number: the path it opens
    var run = n.type === 'bi-run' ? String(n.entityId || n.id || '') : '';                 // a BI run finished (BI_RUNS.md 3.5): it opens where its result is drawn
    var href = run ? '/bi/run/' + encodeURIComponent(run) : alertPath ? alertPath : n.shareId ? '/share/' + encodeURIComponent(n.shareId) : (n.access && n.kind && n.id ? '/v/' + encodeURIComponent(n.kind) + '/' + encodeURIComponent(n.id) : '/inbox');
    var label = run ? (/^Run\b/.test(n.title || '') ? '' : 'Run') : n.type || 'notice';       // "Run finished: …" needs no second "Run"
    toast('toast-a sev-info notice', href, (label ? '<b>' + esc(label) + '</b> ' : '') + esc(n.title) + (n.excerpt ? ' <span class="toast-x">\u201c' + esc(n.excerpt) + '\u201d</span>' : ''));
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
