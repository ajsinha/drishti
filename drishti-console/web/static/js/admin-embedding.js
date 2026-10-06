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
/* Admin → Embedding. The list is server-rendered; this registers an application (New application dialog), shows a client secret once
   (after create and after rotate), enables, disables and deletes, filters the rows, and adds two keys: "n" opens the new-application
   dialog and "/" focuses the filter. Every call is same-origin JSON to /admin/embedding/api/...; the page is reloaded after a change,
   so the table is always what the server holds. The secret is only ever in the dialog: not in the URL, storage or the page source. */
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
  function lines(s) { return s.split(/\r?\n/).map(function (x) { return x.trim(); }).filter(Boolean); }
  function csv(s) { return s.split(',').map(function (x) { return x.trim(); }).filter(Boolean); }

  // ---- the filter ---------------------------------------------------------------------------------------------------------
  var filter = $('[data-filter]'), none = $('[data-filter-none]');
  filter.addEventListener('input', function () {
    var q = filter.value.trim().toLowerCase(), shown = 0;
    Array.prototype.forEach.call(root.querySelectorAll('[data-apps] tbody tr[data-app]'), function (tr) {
      var hit = !q || tr.textContent.toLowerCase().indexOf(q) >= 0;
      tr.hidden = !hit; if (hit) { shown++; }
    });
    none.hidden = shown > 0 || !root.querySelector('tr[data-app]');
  });

  // ---- the secret, once ---------------------------------------------------------------------------------------------------
  var secretDlg = $('[data-secret-dialog]'), secretIn = $('[data-secret-value]');
  function showSecret(app, secret, note) {
    root.querySelector('[data-secret-app]').textContent = app;
    secretIn.value = secret;
    $('[data-secret-note]').textContent = note || '';
    show(secretDlg);
    secretIn.focus(); secretIn.select();
  }
  function closeSecret() { secretIn.value = ''; hide(secretDlg); location.reload(); }
  $('[data-secret-done]').addEventListener('click', closeSecret);
  secretDlg.addEventListener('cancel', function (e) { e.preventDefault(); closeSecret(); });      // Esc ends the same way: forget it, reload
  $('[data-secret-copy]').addEventListener('click', function () {
    secretIn.focus(); secretIn.select();
    var done = function () { $('[data-secret-note]').textContent = 'Copied.'; };
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(secretIn.value).then(done, function () { document.execCommand('copy'); done(); }); }
    else { document.execCommand('copy'); done(); }
  });

  // ---- New application ----------------------------------------------------------------------------------------------------
  var newDlg = $('[data-new-dialog]'), form = $('[data-new-form]'), newMsg = $('[data-new-msg]'), idEdited = false;
  var jwksField = $('[data-jwks-field]');
  function openNew() { form.reset(); idEdited = false; jwksField.hidden = true; say(newMsg, ''); show(newDlg); form.elements.name.focus(); }
  $('[data-new]').addEventListener('click', openNew);
  form.elements.id.addEventListener('input', function () { idEdited = true; });
  form.elements.name.addEventListener('input', function () {
    if (!idEdited) { form.elements.id.value = form.elements.name.value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^[^a-z]+|-+$/g, '').slice(0, 32); }
  });
  Array.prototype.forEach.call(form.elements.auth, function (r) {
    r.addEventListener('change', function () { jwksField.hidden = form.elements.auth.value === 'secret'; });
  });
  $('[data-create]').addEventListener('click', function () {
    var auth = form.elements.auth.value;
    var scopes = Array.prototype.map.call(form.querySelectorAll('input[name=scope]:checked'), function (c) { return c.value; });
    var body = { id: form.elements.id.value.trim(), name: form.elements.name.value.trim(), contact: form.elements.contact.value.trim(),
                 origins: lines(form.elements.origins.value), kinds: csv(form.elements.kinds.value), scopes: scopes, secret: auth !== 'key' };
    if (auth !== 'secret') { body.jwks = form.elements.jwks.value.trim(); }
    ['callsPerMinute', 'userCallsPerMinute', 'tokenSeconds'].forEach(function (k) {
      var n = parseInt(form.elements[k].value, 10); if (!isNaN(n)) { body[k] = n; }
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
  var rotDlg = $('[data-rotate-dialog]'), delDlg = $('[data-delete-dialog]'), current = null;
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
    var grace = parseInt($('[data-rotate-grace]').value, 10);
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
  document.addEventListener('keydown', function (e) {
    var t = e.target, typing = t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable);
    if (typing || e.ctrlKey || e.metaKey || e.altKey || root.querySelector('dialog[open]')) { return; }
    if (e.key === '/') { e.preventDefault(); filter.focus(); }
    if (e.key === 'n') { e.preventDefault(); openNew(); }
  });
})();
