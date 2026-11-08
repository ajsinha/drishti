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
/* Admin → Packs → Data loads: the expectations settings dialog. The tables are server-rendered HTML; the filter, the settings and the
   announcement are drishti-inputs' Forms from the server's schemas (core/admin_forms.py; the filter embedded in the page's GET form). This
   saves and resets the settings (same-origin /admin/api/loads/...), announces a load, and adds two keys: "/" focuses the kind filter,
   "e" opens settings. */
(function () {
  'use strict';
  var root = document.querySelector('[data-loads]');
  if (!root) { return; }
  var pack = root.getAttribute('data-pack');
  var dlg = root.querySelector('[data-config-dialog]');
  var msg = root.querySelector('[data-config-msg]');
  var I = window.Drishti.elements.inputs;
  root.querySelectorAll('.loads-filter [data-di-form]').forEach(function (h) { I.formOf(h); });
  var form = I.formOf(root.querySelector('[data-config-form]'));
  function say(text, bad) { msg.textContent = text; msg.classList.toggle('t-bad', !!bad); msg.classList.toggle('t-ok', !bad); }
  function csv(s) { return s.split(',').map(function (x) { return x.trim(); }).filter(Boolean); }
  function openDialog() {
    if (dlg.showModal) { dlg.showModal(); } else { dlg.setAttribute('open', ''); }
    var first = form.form.querySelector('[name=roles]'); if (first) { first.focus(); }
  }
  function closeDialog() { if (dlg.close) { dlg.close(); } else { dlg.removeAttribute('open'); } }
  function collect() {
    var v = form.getValue(), expect = {};
    (v.expect || []).forEach(function (x) {
      var e = { by: String(x.by || '').trim() }, zone = String(x.zone || '').trim(), cal = String(x.calendar || '').trim();
      if (zone) { e.zone = zone; }
      if (cal) { e.calendar = cal; }
      expect[x.kind] = e;
    });
    return { notify: { roles: csv(v.roles || ''), users: csv(v.users || ''), email: !!v.email }, smoke: v.smoke || 0, expect: expect };
  }
  function send(method, body) {
    return fetch('/admin/api/loads/' + encodeURIComponent(pack) + '/config', { method: method, headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  root.querySelector('[data-config-open]').addEventListener('click', openDialog);
  root.querySelector('[data-config-close]').addEventListener('click', closeDialog);
  root.querySelector('[data-config-save]').addEventListener('click', function () {
    if (!form.validate().ok) { say('Check the settings marked above.', true); return; }
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
  // Announce a load (2.9.7, O4): the same POST /api/v1/packs/{pack}/loads an ETL makes, through the console as this administrator
  var ann = root.querySelector('[data-announce-dialog]');
  if (ann) {
    var af = I.formOf(ann.querySelector('[data-announce-form]'));
    var am = ann.querySelector('[data-announce-msg]');
    root.querySelector('[data-announce-open]').addEventListener('click', function () {
      if (ann.showModal) { ann.showModal(); } else { ann.setAttribute('open', ''); }
      var k = af.form.querySelector('[name=kind]'); if (k) { k.focus(); }
    });
    ann.querySelector('[data-announce-close]').addEventListener('click', function () { if (ann.close) { ann.close(); } else { ann.removeAttribute('open'); } });
    ann.querySelector('[data-announce-send]').addEventListener('click', function () {
      if (!af.validate().ok) { am.textContent = 'Choose the kind and the business date.'; return; }
      var v = af.getValue(), body = { kind: v.kind, businessDate: v.businessDate, status: v.status || 'ready' };
      if (typeof v.rows === 'number' && !isNaN(v.rows)) { body.rows = v.rows; }
      if (v.batchId && String(v.batchId).trim()) { body.batchId = String(v.batchId).trim(); }
      if (v.note && String(v.note).trim()) { body.note = String(v.note).trim(); }
      am.textContent = 'Announcing...';
      fetch('/admin/api/packs/' + encodeURIComponent(pack) + '/loads', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
        .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); })
        .then(function (res) {
          if (res.ok) { am.textContent = (res.body.duplicate ? 'Announced before: nothing ran again.' : 'Announced: ' + (res.body.summary || res.body.status || 'done')); window.setTimeout(function () { location.reload(); }, 1200); }
          else { am.textContent = (res.body.code ? res.body.code + ' ' : '') + (res.body.detail || 'could not announce'); }
        });
    });
  }
  function noDialog() { return !(dlg && dlg.open) && !(ann && ann.open); }
  window.Drishti.keys.register({ id: 'loads.filter', keys: ['/'], scope: 'screen', area: 'admin-loads', group: 'Loads', description: 'Focus the kind filter',
    when: noDialog, run: function () { var f = root.querySelector('[data-filter-kind]'); if (!f) { return false; } f.focus(); } });
  window.Drishti.keys.register({ id: 'loads.config', keys: ['E'], scope: 'screen', area: 'admin-loads', group: 'Loads', description: 'Edit the loads settings',
    when: noDialog, run: function () { openDialog(); } });
})();
