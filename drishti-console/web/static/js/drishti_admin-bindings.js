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
/* Admin → Data bindings: Bind… opens the form for a kind; Propose sends {kind, binding, reason} (POST /admin/api/bindings/propose); a
   second administrator's Approve or Reject, or the proposer's Withdraw, is sent the same way. The server decides and says why it refuses
   (DRS-1029, DRS-2007); the answer stays in the line above the tables and the page reloads to show the change. The tables are console
   tables (drishti-grid). */
(function () {
  'use strict';
  var root = document.querySelector('[data-bindings]');
  if (!root) { return; }
  var said = root.querySelector('[data-bindings-said]');
  var box = root.querySelector('[data-bindings-form]');
  var host = root.querySelector('[data-bind-fields]');
  var F = null;                                                  // drishti-inputs' Form from the server's schema (core/admin_forms.py), asked when first used
  var kind = '';

  function say(text, bad) {
    if (!said) { return; }
    said.textContent = text;
    said.classList.toggle('t-bad', !!bad);
    said.classList.toggle('t-ok', !bad);
  }

  function send(action, body) {
    return window.Drishti.elements.core.api.send('POST', '/admin/api/bindings/' + action, body || {})   // drishti-core's client: never rejects
      .then(function (res) {
        var j = res.body || {};
        if (!res.ok) {
          say((j.code ? j.code + ' ' : '') + (j.detail || 'the server said no'), true);
          return false;
        }
        say(action === 'propose' ? 'Proposed: a second administrator approves it.' : 'Done: ' + (j.state || action) + '.');
        setTimeout(function () { window.location.reload(); }, 1200);
        return true;
      });
  }

  function list(text) {
    return String(text || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
  }

  function open(btn) {
    F = F || window.Drishti.elements.inputs.formOf(host);
    kind = btn.getAttribute('data-binding-edit');
    F.setValue({ read: btn.getAttribute('data-read') || '', live: btn.getAttribute('data-live') || '',
      union: !!btn.getAttribute('data-union'), demo: !!btn.getAttribute('data-demo'), reason: '' });
    root.querySelector('[data-bind-kind-label]').textContent = kind;
    box.hidden = false;
    var r = F.form.querySelector('[name=read]');
    if (r) { r.focus(); }
  }

  function propose(remove) {
    if (!F.validate().ok) { say('Say why the binding changes.', true); return; }
    var v = F.getValue();
    var b = null;
    if (!remove) {
      b = {};
      var read = list(v.read);
      if (read.length) { b.read = read; }
      if (v.union) { b.union = true; }
      if (v.demo) { b.demo = true; }
      if (v.live) { b.live = v.live; }
    }
    send('propose', { kind: kind, binding: b, reason: v.reason });
  }

  root.addEventListener('click', function (e) {
    var edit = e.target.closest('[data-binding-edit]');
    if (edit && host) { open(edit); return; }
    var act = e.target.closest('[data-proposal-act]');
    if (act) {
      act.disabled = true;
      send(act.getAttribute('data-proposal-act'), { id: act.getAttribute('data-id') }).then(function (ok) { if (!ok) { act.disabled = false; } });
      return;
    }
    if (e.target.closest('[data-bind-cancel]')) { box.hidden = true; return; }
    if (e.target.closest('[data-bind-propose]')) { propose(false); return; }
    if (e.target.closest('[data-bind-remove]')) { propose(true); }
  });
})();
