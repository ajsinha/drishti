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
/* Alerts page: create rules (or start from a pack's suggestion) and delete them. */
(function () {
  'use strict';
  var root = document.querySelector('[data-alerts]');
  if (!root) { return; }
  var form = root.querySelector('[data-rule-form]'), msg = root.querySelector('[data-msg]');
  function post(url, body) {
    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); });
  }
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var f = form.elements;
    post('/alerts/api/' + encodeURIComponent(f.name.value.trim()), { kind: f.kind.value.trim(), id: f.id.value.trim(), when: f.when.value,
      severity: f.severity.value, message: f.message.value })
      .then(function (res) { if (res.ok) { location.reload(); } else { msg.textContent = drsMessage(res.b); msg.classList.add('t-bad'); } });
  });
  root.querySelectorAll('[data-suggest]').forEach(function (b) {
    b.addEventListener('click', function () {
      var s = JSON.parse(b.getAttribute('data-suggest')), f = form.elements;
      f.name.value = s.name; f.kind.value = s.kind; f.when.value = s.when; f.severity.value = s.severity || 'warn'; f.message.value = s.message || '';
      if (!f.id.value) { f.id.focus(); }
    });
  });
  root.querySelectorAll('[data-delete-rule]').forEach(function (b) {
    b.addEventListener('click', function () { post('/alerts/api/' + encodeURIComponent(b.dataset.deleteRule) + '/delete').then(function () { location.reload(); }); });
  });
})();
