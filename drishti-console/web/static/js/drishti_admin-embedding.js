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
/* Admin → Embedding. The list is server-rendered; this registers an application (New application dialog), shows a client secret once
   (after create and after rotate), enables, disables and deletes, filters the rows, and adds two keys: "n" opens the new-application
   dialog and "/" focuses the filter. Every call is same-origin JSON to /admin/embedding/api/...; the page is reloaded after a change,
   so the table is always what the server holds. The secret is only ever in the dialog: not in the URL, storage or the page source.
   Every field is drishti-inputs' Form from the server's schema (core/admin_forms.py). */
(function () {
  'use strict';
  var root = document.querySelector('[data-embedding]');
  if (!root) { return; }
  var msg = root.querySelector('[data-msg]');
  function $(sel) { return root.querySelector(sel); }
  function say(el, text, bad) { el.textContent = text; el.classList.toggle('t-bad', !!bad); el.classList.toggle('t-ok', !bad); }
  function show(d) { if (d.showModal) { d.showModal(); } else { d.setAttribute('open', ''); } }
  function hide(d) { if (d.close) { d.close(); } else { d.removeAttribute('open'); } }
  function send(method, path, body) {
    return fetch('/admin/embedding/api/apps' + path, { method: method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  function problem(b, fallback) { return (b.code ? b.code + ' ' : '') + (b.detail || fallback); }
  var I = window.Drishti.elements.inputs;
  function formIn(sel) { return I.formOf($(sel + ' [data-di-form]') || $(sel + '[data-di-form]')); }
  function lines(s) { return s.split(/\r?\n/).map(function (x) { return x.trim(); }).filter(Boolean); }
  function csv(s) { return s.split(',').map(function (x) { return x.trim(); }).filter(Boolean); }

  // ---- the filter ---------------------------------------------------------------------------------------------------------
  var filterForm = formIn('.emb-filter'), filter = $('[data-filter]'), none = $('[data-filter-none]');
  filterForm.on('change', function () {
    var q = String(filterForm.getField('q') || '').trim().toLowerCase(), shown = 0;
    Array.prototype.forEach.call(root.querySelectorAll('[data-apps] tbody tr[data-app]'), function (tr) {
      var hit = !q || tr.textContent.toLowerCase().indexOf(q) >= 0;
      tr.hidden = !hit; if (hit) { shown++; }
    });
    none.hidden = shown > 0 || !root.querySelector('tr[data-app]');
  });

  // ---- the secret, once ---------------------------------------------------------------------------------------------------
  var secretDlg = $('[data-secret-dialog]'), secretForm = formIn('[data-secret-dialog]');
  function showSecret(app, secret, note) {
    root.querySelector('[data-secret-app]').textContent = app;
    secretForm.setField('secret', secret);
    $('[data-secret-note]').textContent = note || '';
    show(secretDlg);
    $('[data-secret-value]').focus(); $('[data-secret-value]').select();
  }
  function closeSecret() { secretForm.setField('secret', ''); hide(secretDlg); location.reload(); }
  $('[data-secret-done]').addEventListener('click', closeSecret);
  secretDlg.addEventListener('cancel', function (e) { e.preventDefault(); closeSecret(); });      // Esc ends the same way: forget it, reload
  $('[data-secret-copy]').addEventListener('click', function () {
    $('[data-secret-value]').focus(); $('[data-secret-value]').select();
    var done = function () { $('[data-secret-note]').textContent = 'Copied.'; };
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText($('[data-secret-value]').value).then(done, function () { document.execCommand('copy'); done(); }); }
    else { document.execCommand('copy'); done(); }
  });

  // ---- New application ----------------------------------------------------------------------------------------------------
  var newDlg = $('[data-new-dialog]'), form = formIn('[data-new-form]'), newMsg = $('[data-new-msg]'), idEdited = false;
  function openNew() { form.reset(); idEdited = false; say(newMsg, ''); show(newDlg); form.form.querySelector('[name=name]').focus(); }   // the JWK set shows only with a key (the schema's condition)
  $('[data-new]').addEventListener('click', openNew);
  form.on('change', function (e) {                    // the id follows the name until it is typed in
    if (e.path === 'id') { idEdited = true; }
    if (e.path === 'name' && !idEdited) { form.setField('id', String(e.value || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^[^a-z]+|-+$/g, '').slice(0, 32) || undefined); }
  });
  $('[data-create]').addEventListener('click', function () {
    if (!form.validate().ok) { say(newMsg, 'Check the fields marked above.', true); return; }
    var v = form.getValue(), auth = v.auth || 'secret', text = function (x) { return String(x || '').trim(); };
    var body = { id: text(v.id), name: text(v.name), contact: text(v.contact), origins: lines(text(v.origins)), kinds: csv(text(v.kinds)), scopes: v.scope || [], secret: auth !== 'key' };
    if (auth !== 'secret') { body.jwks = text(v.jwks); }
    ['callsPerMinute', 'userCallsPerMinute', 'tokenSeconds'].forEach(function (k) {
      if (typeof v[k] === 'number' && !isNaN(v[k])) { body[k] = v[k]; }
    });
    say(newMsg, 'Registering...');
    send('POST', '', body).then(function (res) {
      if (!res.ok) { say(newMsg, problem(res.body, 'could not register'), true); return; }
      hide(newDlg);
      if (res.body.secret) { showSecret(res.body.app.name, res.body.secret, 'Use it as the client_secret_basic password with the id "' + res.body.app.id + '" at the token endpoint, from the application\'s backend.'); }
      else { location.reload(); }
    });
  });

  // ---- rotate, enable / disable, delete ------------------------------------------------------------------------------------
  var rotDlg = $('[data-rotate-dialog]'), rotForm = formIn('[data-rotate-dialog]'), delDlg = $('[data-delete-dialog]'), current = null;
  function rowOf(el) { return el.closest('tr[data-app]'); }
  function nameOf(tr) { return tr.querySelector('.emb-name b').textContent; }
  root.addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (!b) { return; }
    if (b.hasAttribute('data-close')) { hide(b.closest('dialog')); return; }
    var tr = rowOf(b);
    if (b.hasAttribute('data-rotate') && tr) { current = tr.getAttribute('data-app'); root.querySelector('[data-rotate-app]').textContent = nameOf(tr); say($('[data-rotate-msg]'), ''); show(rotDlg); $('[data-rotate-grace]').focus(); }
    else if (b.hasAttribute('data-delete') && tr) { current = tr.getAttribute('data-app'); root.querySelector('[data-delete-app]').textContent = nameOf(tr); say($('[data-delete-msg]'), ''); show(delDlg); b = delDlg.querySelector('[data-close]'); b.focus(); }
    else if (b.hasAttribute('data-switch') && tr) {
      var id = tr.getAttribute('data-app'), what = b.getAttribute('data-switch');
      send('POST', '/' + encodeURIComponent(id) + '/' + what).then(function (res) {
        if (res.ok) { location.reload(); } else { say(msg, problem(res.body, 'could not change it'), true); }
      });
    }
  });
  $('[data-rotate-go]').addEventListener('click', function () {
    var g = rotForm.getField('grace'), grace = typeof g === 'number' ? g : NaN;
    send('POST', '/' + encodeURIComponent(current) + '/rotate', isNaN(grace) ? {} : { graceSeconds: grace }).then(function (res) {
      if (!res.ok) { say($('[data-rotate-msg]'), problem(res.body, 'could not rotate'), true); return; }
      hide(rotDlg);
      showSecret(res.body.app.name, res.body.secret, 'The old secret keeps working for ' + (isNaN(grace) ? 3600 : grace) + ' seconds.');
    });
  });
  $('[data-delete-go]').addEventListener('click', function () {
    send('DELETE', '/' + encodeURIComponent(current)).then(function (res) {
      if (res.ok) { location.reload(); } else { say($('[data-delete-msg]'), problem(res.body, 'could not delete'), true); }
    });
  });

  // ---- keys: "n" new, "/" filter (not while typing or in a dialog) -------------------------------------------------------------
  function noDialog() { return !root.querySelector('dialog[open]'); }
  window.Drishti.keys.register({ id: 'embedding.filter', keys: ['/'], scope: 'screen', area: 'admin-embedding', group: 'Embedding', description: 'Focus the filter',
    when: noDialog, run: function () { filter.focus(); } });
  window.Drishti.keys.register({ id: 'embedding.new', keys: ['N'], scope: 'screen', area: 'admin-embedding', group: 'Embedding', description: 'A new application',
    when: noDialog, run: function () { openNew(); } });
})();
