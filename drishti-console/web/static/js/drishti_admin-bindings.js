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
  var form = root.querySelector('[data-bind-form]');

  function say(text, bad) {
    if (!said) { return; }
    said.textContent = text;
    said.classList.toggle('t-bad', !!bad);
    said.classList.toggle('t-ok', !bad);
  }

  function send(action, body) {
    return fetch('/admin/api/bindings/' + action, { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, body: j }; }); })
      .catch(function () { return { ok: false, body: { detail: 'the console could not reach the server' } }; })
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
    form.elements.kind.value = btn.getAttribute('data-binding-edit');
    form.elements.read.value = btn.getAttribute('data-read') || '';
    form.elements.live.value = btn.getAttribute('data-live') || '';
    form.elements.union.checked = !!btn.getAttribute('data-union');
    form.elements.demo.checked = !!btn.getAttribute('data-demo');
    form.elements.reason.value = '';
    root.querySelector('[data-bind-kind-label]').textContent = form.elements.kind.value;
    box.hidden = false;
    form.elements.read.focus();
  }

  root.addEventListener('click', function (e) {
    var edit = e.target.closest('[data-binding-edit]');
    if (edit) { open(edit); return; }
    var act = e.target.closest('[data-proposal-act]');
    if (act) {
      act.disabled = true;
      send(act.getAttribute('data-proposal-act'), { id: act.getAttribute('data-id') }).then(function (ok) { if (!ok) { act.disabled = false; } });
      return;
    }
    if (e.target.closest('[data-bind-cancel]')) { box.hidden = true; return; }
    if (e.target.closest('[data-bind-remove]')) {
      send('propose', { kind: form.elements.kind.value, binding: null, reason: form.elements.reason.value });
    }
  });

  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var b = {};
      var read = list(form.elements.read.value);
      if (read.length) { b.read = read; }
      if (form.elements.union.checked) { b.union = true; }
      if (form.elements.demo.checked) { b.demo = true; }
      if (form.elements.live.value.trim()) { b.live = form.elements.live.value.trim(); }
      send('propose', { kind: form.elements.kind.value, binding: b, reason: form.elements.reason.value });
    });
  }
})();
