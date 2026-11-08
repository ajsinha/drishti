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
/* Admin → Configuration (/admin/configuration): "Propose a change" opens a dialog with the key, its value in effect, a new value and the
 * reason; Propose sends it to the console, which asks the server (nothing changes until a second administrator approves). Approve,
 * Reject and Withdraw decide a waiting proposal. Every text goes in as a text node; the page reloads after a change. */
(function () {
  'use strict';
  var root = document.querySelector('[data-config-editor]');
  if (!root) { return; }
  var status = root.querySelector('[data-cfe-status]');
  var dlg = root.querySelector('[data-propose-dialog]');
  var err = dlg.querySelector('[data-dlg-error]');
  var current = null;
  var PF = window.Drishti.elements.inputs.formOf(dlg.querySelector('[data-propose-fields]'));   // the value and why: drishti-inputs' Form (core/admin_forms.py)

  function say(text, bad) { status.textContent = text; status.classList.toggle('bad', !!bad); }
  function post(url, body) {
    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, body: j, status: r.status }; }); });
  }
  function why(r) {
    var D = window.Drishti;
    return D && D.drsMessage ? D.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed')
      : (r.body.detail || 'The request failed');
  }

  root.querySelectorAll('[data-propose]').forEach(function (b) {
    b.addEventListener('click', function () {
      current = b.closest('tr');
      dlg.querySelector('[data-dlg-key]').textContent = current.dataset.key;
      dlg.querySelector('[data-dlg-was]').textContent = current.dataset.value ? '(now ' + current.dataset.value + ')' : '(now the default)';
      PF.setValue({ value: current.dataset.value || '', reason: '' });
      err.hidden = true;
      if (dlg.showModal) { dlg.showModal(); } else { dlg.setAttribute('open', ''); }
      PF.focusField('value');
    });
  });
  dlg.querySelector('[data-dlg-cancel]').addEventListener('click', function () { dlg.close(); });
  dlg.querySelector('[data-propose-form]').addEventListener('submit', function (e) {
    e.preventDefault();
    var value = String(PF.getField('value') || ''), reason = String(PF.getField('reason') || '').trim();
    if (!reason) { err.textContent = 'Say why: the approver reads it.'; err.hidden = false; return; }
    post('/admin/configuration/propose', { key: current.dataset.key, area: current.dataset.area, value: value,
                                          was: current.dataset.value || '', reason: reason }).then(function (r) {
      if (!r.ok) { err.textContent = why(r); err.hidden = false; return; }
      dlg.close();
      say('Proposed: ' + r.body.key + '. A second administrator approves it.');
      window.location.reload();
    }, function () { err.textContent = 'The console could not be reached. Try again.'; err.hidden = false; });
  });

  root.querySelectorAll('[data-decide]').forEach(function (b) {
    b.addEventListener('click', function () {
      var li = b.closest('[data-proposal]'), verb = b.dataset.decide, comment = '';
      if (verb === 'reject') { comment = window.prompt('Why reject it? (the proposer reads it)', '') || ''; }
      post('/admin/configuration/proposals/' + encodeURIComponent(li.dataset.proposal) + '/' + verb, { comment: comment }).then(function (r) {
        if (!r.ok) { say(why(r), true); return; }
        say((verb === 'approve' ? 'Approved' : verb === 'reject' ? 'Rejected' : 'Withdrawn') + ': ' + r.body.key + '.');
        window.location.reload();
      }, function () { say('The console could not be reached. Try again.', true); });
    });
  });
})();
