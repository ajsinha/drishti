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
/* Admin → Packs → Data loads: the expectations settings dialog. The tables and filters are plain server-rendered HTML; this only
   saves and resets the settings (same-origin /admin/api/loads/...), and adds two keys: "/" focuses the kind filter, "e" opens settings. */
(function () {
  'use strict';
  var root = document.querySelector('[data-loads]');
  if (!root) { return; }
  var pack = root.getAttribute('data-pack');
  var dlg = root.querySelector('[data-config-dialog]');
  var msg = root.querySelector('[data-config-msg]');
  var form = root.querySelector('[data-config-form]');
  var rows = root.querySelector('[data-expect-rows] tbody');
  function say(text, bad) { msg.textContent = text; msg.classList.toggle('t-bad', !!bad); msg.classList.toggle('t-ok', !bad); }
  function csv(s) { return s.split(',').map(function (x) { return x.trim(); }).filter(Boolean); }
  function openDialog() {
    if (dlg.showModal) { dlg.showModal(); } else { dlg.setAttribute('open', ''); }
    var first = form.elements.roles; if (first) { first.focus(); }
  }
  function closeDialog() { if (dlg.close) { dlg.close(); } else { dlg.removeAttribute('open'); } }
  function collect() {
    var expect = {};
    Array.prototype.forEach.call(rows.querySelectorAll('tr'), function (tr) {
      var kind = tr.querySelector('[name=x-kind]').value, e = { by: tr.querySelector('[name=x-by]').value.trim() };
      var zone = tr.querySelector('[name=x-zone]').value.trim(), cal = tr.querySelector('[name=x-cal]').value.trim();
      if (zone) { e.zone = zone; }
      if (cal) { e.calendar = cal; }
      expect[kind] = e;
    });
    return { notify: { roles: csv(form.elements.roles.value), users: csv(form.elements.users.value), email: form.elements.email.checked },
             smoke: parseInt(form.elements.smoke.value || '0', 10), expect: expect };
  }
  function send(method, body) {
    return fetch('/admin/api/loads/' + encodeURIComponent(pack) + '/config', { method: method, headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  root.querySelector('[data-config-open]').addEventListener('click', openDialog);
  root.querySelector('[data-config-close]').addEventListener('click', closeDialog);
  root.querySelector('[data-config-save]').addEventListener('click', function () {
    say('Saving...');
    send('PUT', collect()).then(function (res) {
      if (res.ok) { location.reload(); } else { say((res.body.code ? res.body.code + ' ' : '') + (res.body.detail || 'could not save'), true); }
    });
  });
  root.querySelector('[data-config-reset]').addEventListener('click', function () {
    send('DELETE').then(function (res) {
      if (res.ok) { location.reload(); } else { say(res.body.detail || 'could not reset', true); }
    });
  });
  root.querySelector('[data-expect-add]').addEventListener('click', function () {
    var t = root.querySelector('[data-expect-template]');
    rows.appendChild(t.content.cloneNode(true));
    var added = rows.querySelectorAll('tr'); added[added.length - 1].querySelector('select').focus();
  });
  rows.addEventListener('click', function (e) {
    var b = e.target.closest('[data-expect-remove]');
    if (b) { b.closest('tr').remove(); }
  });
  document.addEventListener('keydown', function (e) {
    var t = e.target, typing = t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable);
    if (typing || e.ctrlKey || e.metaKey || e.altKey || (dlg && dlg.open)) { return; }
    if (e.key === '/') { var f = root.querySelector('[data-filter-kind]'); if (f) { e.preventDefault(); f.focus(); } }
    if (e.key === 'e') { e.preventDefault(); openDialog(); }
  });
})();
