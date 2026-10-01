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
/* Admin → Roles and Admin → Packs. Roles: create, edit and delete administrators' roles through same-origin /admin/api/roles/... (the server
   enforces the admin role, validates and audits); the page reloads to show the new state. */
(function () {
  'use strict';
  // Admin → Packs: switch a pack on or off for everyone.
  var packs = document.querySelector('[data-pack-admin]');
  if (packs) {
    var pmsg = packs.querySelector('[data-msg]');
    packs.querySelectorAll('[data-switch]').forEach(function (b) {
      b.addEventListener('click', function () {
        var name = b.closest('tr').getAttribute('data-pack'), on = b.getAttribute('data-switch') === 'on';
        if (!on && !window.confirm('Switch ' + name + ' off for everyone? Its kinds cannot be opened until it is switched on again.')) { return; }
        fetch('/admin/api/packs/' + encodeURIComponent(name), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: on }) })
          .then(function (r) { return r.json().then(function (body) { return { ok: r.ok, body: body }; }); })
          .then(function (res) {
            pmsg.textContent = res.ok ? name + ' is ' + (on ? 'on' : 'off') + '.' : (res.body.code || 'Error') + ': ' + res.body.detail;
            pmsg.classList.toggle('t-bad', !res.ok);
            if (res.ok) { setTimeout(function () { location.reload(); }, 400); }
          });
      });
    });
  }
  var root = document.querySelector('[data-roles]');
  if (!root) { return; }
  var msg = root.querySelector('[data-msg]'), dlg = root.querySelector('[data-dialog]'), form = root.querySelector('[data-form]');
  var dmsg = root.querySelector('[data-dlg-msg]'), editing = null;

  function post(url, body) {
    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  function say(el, text, bad) { el.textContent = text; el.classList.toggle('t-bad', !!bad); el.classList.toggle('t-ok', !bad); }
  function kindsVisible() { root.querySelectorAll('[data-kinds]').forEach(function (el) { el.hidden = form.elements.all.checked; }); }

  function open(role) {
    editing = role;
    form.reset();
    say(dmsg, '');
    root.querySelector('[data-title]').textContent = role ? 'Edit ' + role.name : 'New role';
    form.elements.name.value = role ? role.name : '';
    form.elements.name.readOnly = !!role;
    if (role) {
      form.elements.description.value = role.description || '';
      form.elements.all.checked = role.kinds.indexOf('*') >= 0;
      form.elements.kinds.value = role.kinds.filter(function (k) { return k !== '*'; }).join('\n');
      ['raw', 'author', 'approve', 'admin'].forEach(function (f) { form.elements[f].checked = !!role[f]; });
    }
    kindsVisible();
    dlg.showModal();
  }

  form.elements.all.addEventListener('change', kindsVisible);
  root.querySelectorAll('[data-pack-kinds]').forEach(function (b) {
    b.addEventListener('click', function () {
      var have = form.elements.kinds.value.split(/\s+/).filter(Boolean);
      b.getAttribute('data-pack-kinds').split(' ').filter(Boolean).forEach(function (k) { if (have.indexOf(k) < 0) { have.push(k); } });
      form.elements.kinds.value = have.join('\n');
    });
  });
  root.querySelector('[data-new]').addEventListener('click', function () { open(null); });
  root.querySelector('[data-cancel]').addEventListener('click', function () { dlg.close(); });
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var f = form.elements;
    var body = {
      description: f.description.value.trim(),
      kinds: f.all.checked ? ['*'] : f.kinds.value.split(/[\s,]+/).filter(Boolean),
      raw: f.raw.checked, author: f.author.checked, approve: f.approve.checked, admin: f.admin.checked
    };
    post('/admin/api/roles/' + encodeURIComponent(f.name.value.trim()), body).then(function (res) {
      if (res.ok) { dlg.close(); say(msg, (editing ? 'Saved ' : 'Created ') + res.body.name + '.'); setTimeout(function () { location.reload(); }, 400); }
      else { say(dmsg, (res.body.code || 'Error') + ': ' + res.body.detail, true); }
    });
  });
  root.querySelectorAll('tr[data-role]').forEach(function (tr) {
    var role = JSON.parse(tr.getAttribute('data-json'));
    var edit = tr.querySelector('[data-edit]'), del = tr.querySelector('[data-delete]');
    if (edit) { edit.addEventListener('click', function () { open(role); }); }
    if (del) {
      del.addEventListener('click', function () {
        if (!window.confirm('Delete the role ' + role.name + '?')) { return; }
        post('/admin/api/roles/' + encodeURIComponent(role.name) + '/delete').then(function (res) {
          if (res.ok) { say(msg, 'Deleted ' + role.name + '.'); setTimeout(function () { location.reload(); }, 400); }
          else { say(msg, (res.body.code || 'Error') + ': ' + res.body.detail, true); }
        });
      });
    }
  });
})();
