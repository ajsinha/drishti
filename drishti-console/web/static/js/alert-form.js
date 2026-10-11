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
/* The alert form (alert_form.html): shows only the fields that belong to the chosen source, condition and trigger (without this file all of them show),
   and "Check now" asks the server what the alert would read for you right now, without saving it. The answer is your own number, read with your masks:
   a masked number is reported as masked, never shown. */
(function () {
  'use strict';
  var form = document.querySelector('[data-alert-form]');
  if (!form) { return; }
  function sync() {
    form.querySelectorAll('[data-pick]').forEach(function (sel) {
      var group = sel.getAttribute('data-pick');
      if (group === 'range') { return; }
      form.querySelectorAll('[data-group="' + group + '"]').forEach(function (g) { g.hidden = g.getAttribute('data-when') !== sel.value; });
    });
    var op = form.elements.op;
    var high = form.querySelector('[data-range-high]');
    if (op && high) { high.hidden = op.value !== 'between' && op.value !== 'outside'; }
  }
  form.addEventListener('change', sync);
  sync();

  var out = form.querySelector('[data-check-out]');
  var button = form.querySelector('[data-check-now]');
  function fields() {
    var body = {};
    new FormData(form).forEach(function (v, k) { body[k] = String(v); });
    ['d_inbox', 'd_email', 'd_bridge', 'enabled'].forEach(function (k) { body[k] = form.elements[k] && form.elements[k].checked ? 'on' : ''; });
    return body;
  }
  function say(text, bad) {
    out.textContent = text;
    out.classList.toggle('t-bad', !!bad);
  }
  function number(v) { return typeof v === 'number' ? v.toLocaleString(undefined, { maximumFractionDigits: 4 }) : String(v); }
  button.addEventListener('click', function () {
    say('Reading…');
    fetch('/alerts/preview', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(fields()) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
      .then(function (res) {
        if (!res.ok) { say((res.b.code ? res.b.code + ' ' : '') + (res.b.detail || 'could not read'), true); return; }
        var b = res.b;
        if (b.status === 'ok') {
          say('Right now it reads ' + number(b.value) + (b.asOf ? ' (as of ' + b.asOf + ')' : '') + '. ' + (b.holds === true ? 'That is past the limit: it would fire when saved.' : b.holds === false ? 'That is inside the limit.' : '')
            + (b.live ? ' This source is live, so "when it changes" is available.' : ''));
        } else if (b.status === 'masked') {
          say('That number is masked for you, so an alert on it would never judge it. Ask for the access, or watch another number.', true);
        } else {
          say('Nothing to read yet: ' + (b.note || b.status), b.status === 'error');
        }
      })
      .catch(function () { say('Could not reach the server.', true); });
  });
})();
