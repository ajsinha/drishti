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
/* User administration and the account page. Every action goes to same-origin /admin/api/... (the server
   enforces the admin role) and reports the server's message; the page reloads to show the new state. */
(function () {
  'use strict';
  function post(url, body) {
    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  function say(el, text, bad) { if (el) { el.textContent = text; el.classList.toggle('t-bad', !!bad); el.classList.toggle('t-ok', !bad); } }

  // ---- account: change own password ----------------------------------------------------------
  var pw = document.querySelector('[data-password]');
  if (pw) {
    pw.addEventListener('submit', function (e) {
      e.preventDefault();
      var msg = pw.querySelector('[data-msg]'), f = pw.elements;
      if (f.next.value !== f.repeat.value) { say(msg, 'The new passwords differ.', true); return; }
      post('/account/password', { current: f.current.value, next: f.next.value }).then(function (res) {
        if (res.ok) { pw.reset(); say(msg, 'Password changed.'); } else { say(msg, (res.body.code || 'Error') + ': ' + res.body.detail, true); }
      });
    });
  }

  // ---- users page -------------------------------------------------------------------------------
  var root = document.querySelector('[data-admin]');
  if (!root) { return; }
  var msg = root.querySelector('[data-msg]'), dlg = root.querySelector('[data-dialog]'), form = root.querySelector('[data-form]');
  var dmsg = root.querySelector('[data-dlg-msg]'), editing = null;
  function api(user, action, body) { return post('/admin/api/users/' + encodeURIComponent(user) + '/' + action, body); }
  function done(res, text) { if (res.ok) { say(msg, text); setTimeout(function () { location.reload(); }, 500); } else { say(msg, (res.body.code || 'Error') + ': ' + res.body.detail, true); } }

  function open(user) {
    editing = user;
    form.reset();
    say(dmsg, '');
    root.querySelector('[data-title]').textContent = user ? 'Edit ' + user.username : 'New user';
    form.elements.username.value = user ? user.username : '';
    form.elements.username.readOnly = !!user;
    form.querySelectorAll('[data-pw]').forEach(function (el) { el.hidden = !!user; });
    form.elements.password.required = !user;
    if (user) {
      form.elements.displayName.value = user.displayName || '';
      form.elements.desk.value = user.desk || '';
      form.elements.email.value = user.email || '';
      form.elements.enabled.checked = !!user.enabled;
      form.querySelectorAll('[name="roles"]').forEach(function (c) { c.checked = user.roles.indexOf(c.value) >= 0; });
      form.querySelectorAll('[name="packs"]').forEach(function (c) { c.checked = !user.packs || user.packs.indexOf(c.value) >= 0; });
    }
    dlg.showModal();
    (user ? form.elements.displayName : form.elements.username).focus();
  }

  root.querySelector('[data-new]').addEventListener('click', function () { open(null); });
  root.querySelector('[data-cancel]').addEventListener('click', function () { dlg.close(); });
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var f = form.elements, roles = [];
    form.querySelectorAll('[name="roles"]:checked').forEach(function (c) { roles.push(c.value); });
    var body = { displayName: f.displayName.value, desk: f.desk.value, email: f.email.value, roles: roles, enabled: f.enabled.checked };
    var packs = [].map.call(form.querySelectorAll('[name="packs"]:checked'), function (c) { return c.value; });
    if (form.querySelector('[name="packs"]')) { body.packs = packs; }
    var call = editing ? api(editing.username, 'update', body)
      : post('/admin/api/users', Object.assign(body, { username: f.username.value.trim(), password: f.password.value, mustChangePassword: f.mustChangePassword.checked }));
    call.then(function (res) {
      if (res.ok) { dlg.close(); done(res, editing ? 'Saved ' + editing.username + '.' : 'Created ' + f.username.value + '.'); }
      else { say(dmsg, (res.body.code || 'Error') + ': ' + res.body.detail, true); }
    });
  });

  root.querySelectorAll('[data-user]').forEach(function (row) {
    var user = JSON.parse(row.getAttribute('data-json'));
    row.querySelector('[data-edit]').addEventListener('click', function () { open(user); });
    row.querySelector('[data-toggle]').addEventListener('click', function () {
      api(user.username, 'enabled', { enabled: !user.enabled }).then(function (res) { done(res, (user.enabled ? 'Disabled ' : 'Enabled ') + user.username + '.'); });
    });
    row.querySelector('[data-reset]').addEventListener('click', function () {
      var p = window.prompt('New password for ' + user.username + ' (at least 10 characters, letters and digits):');
      if (p) { api(user.username, 'password', { password: p }).then(function (res) { done(res, 'Password reset for ' + user.username + '.'); }); }
    });
    row.querySelector('[data-delete]').addEventListener('click', function () {
      if (window.confirm('Delete ' + user.username + '? This cannot be undone.')) {
        api(user.username, 'delete').then(function (res) { done(res, 'Deleted ' + user.username + '.'); });
      }
    });
  });
})();

/* Caches page: purge one cache, or all, then reload to show the new sizes. */
(function () {
  'use strict';
  var root = document.querySelector('[data-caches]');
  if (!root) { return; }
  var status = root.querySelector('[data-purge-status]');
  root.addEventListener('click', function (e) {
    var b = e.target.closest('[data-purge]');
    if (!b) { return; }
    var name = b.getAttribute('data-purge');
    if (name === 'all' && !window.confirm('Purge every cache? Views refill from the sources; the first reads will be slower.')) { return; }
    b.disabled = true;
    fetch('/admin/api/caches/' + encodeURIComponent(name) + '/purge', { method: 'POST' })
      .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
      .then(function (res) {
        status.textContent = res.ok ? 'Purged ' + res.d.purged.join(', ') + ' in ' + res.d.elapsedMs + ' ms.' : (res.d.code + ': ' + res.d.detail);
        if (res.ok) { window.setTimeout(function () { window.location.reload(); }, 900); }
      })
      .catch(function (err) { status.textContent = 'Purge failed: ' + err; })
      .then(function () { b.disabled = false; });
  });
})();
