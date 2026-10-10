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
            pmsg.textContent = res.ok ? name + ' is ' + (on ? 'on' : 'off') + '.' : drsMessage(res.body);
            pmsg.classList.toggle('t-bad', !res.ok);
            if (res.ok) { setTimeout(function () { location.reload(); }, 400); }
          });
      });
    });
  }
  if (packs) {
    var lmsg = packs.querySelector('[data-msg]');
    packs.querySelectorAll('[data-load], [data-registry]').forEach(function (b) {
      b.addEventListener('click', function () {
        var row = b.closest('tr'), name = row.getAttribute('data-pack'), version = row.getAttribute('data-version');
        var action = b.getAttribute('data-load') || b.getAttribute('data-registry');
        var url = b.hasAttribute('data-registry')
          ? '/admin/api/registry/' + encodeURIComponent(name) + (action === 'install' ? '/' + encodeURIComponent(version) + '/install' : '/rollback')
          : '/admin/api/packs/' + encodeURIComponent(name) + '/' + action;
        var verb = { load: 'Load ', unload: 'Unload ', install: 'Install ', rollback: 'Roll back ' }[action];
        if (!window.confirm(verb + name + (action === 'install' ? ' ' + version : '') + '? It takes effect now, with no restart.')) { return; }
        lmsg.classList.remove('t-bad');
        lmsg.textContent = (action === 'unload' ? 'Unloading ' : 'Checking ') + name + '…';
        fetch(url, { method: 'POST' })
          .then(function (r) { return r.json().then(function (body) { return { ok: r.ok, body: body }; }); })
          .then(function (res) {
            if (!res.ok) { lmsg.textContent = drsMessage(res.body); lmsg.classList.add('t-bad'); return; }
            lmsg.textContent = res.body.note;
            if (!res.body.restarting) { setTimeout(function () { location.reload(); }, 1200); return; }
            // wait for the server to go and come back, then show the new state
            var seenDown = false, started = Date.now();
            (function poll() {
              fetch('/readyz', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (s) {
                if (s.status !== 'UP') { seenDown = true; }
                if (s.status === 'UP' && (seenDown || Date.now() - started > 15000)) { location.reload(); return; }
                lmsg.textContent = 'Restarting the server… ' + Math.round((Date.now() - started) / 1000) + ' s';
                setTimeout(poll, 1000);
              }).catch(function () { seenDown = true; setTimeout(poll, 1000); });
            })();
          });
      });
    });
  }
  var tadm = document.querySelector('[data-token-admin]');
  if (tadm) {
    tadm.querySelectorAll('[data-revoke-any]').forEach(function (b) {
      b.addEventListener('click', function () {
        var id = b.closest('tr').getAttribute('data-token');
        if (!window.confirm('Revoke token ' + id + '? Anything using it stops working at once.')) { return; }
        fetch('/admin/api/tokens/' + encodeURIComponent(id) + '/revoke', { method: 'POST' }).then(function (r) {
          if (r.ok) { location.reload(); } else { r.json().then(function (body) { tadm.querySelector('[data-msg]').textContent = drsMessage(body); }); }
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
      ['raw', 'author', 'approve', 'admin', 'calc'].forEach(function (f) { form.elements[f].checked = !!role[f]; });
      form.elements.layout.checked = role.layout !== false;                // on unless the role says otherwise
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
      raw: f.raw.checked, author: f.author.checked, approve: f.approve.checked, admin: f.admin.checked, calc: f.calc.checked,
      layout: f.layout.checked
    };
    post('/admin/api/roles/' + encodeURIComponent(f.name.value.trim()), body).then(function (res) {
      if (res.ok) { dlg.close(); say(msg, (editing ? 'Saved ' : 'Created ') + res.body.name + '.'); setTimeout(function () { location.reload(); }, 400); }
      else { say(dmsg, drsMessage(res.body), true); }
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
          else { say(msg, drsMessage(res.body), true); }
        });
      });
    }
  });
})();
