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
/* Admin → Compute. The page is server-rendered; this carries the administrator's decisions: approve, reject or revoke an approval
   request (a dialog asks for the reason, which goes into the audit log; the server refuses the person who asked), build a pool's
   approved environment, test a pool with the sample engine, cancel a running request. Every call is same-origin JSON to
   /admin/compute/api/...; the page reloads after a change, so it always shows what the server holds. */
(function () {
  'use strict';
  var root = document.querySelector('[data-compute]');
  if (!root) { return; }
  var msg = root.querySelector('[data-msg]');
  function $(sel) { return root.querySelector(sel); }
  function say(el, text, bad) { el.textContent = text; el.classList.toggle('t-bad', !!bad); el.classList.toggle('t-ok', !bad); }
  function show(d) { if (d.showModal) { d.showModal(); } else { d.setAttribute('open', ''); } }
  function hide(d) { if (d.close) { d.close(); } else { d.removeAttribute('open'); } }
  function send(path, body) {
    return fetch('/admin/compute/api' + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  function problem(b, fallback) { return (b.code ? b.code + ' ' : '') + (b.detail || fallback); }

  // ---- approve, reject, revoke --------------------------------------------------------------------------------------------
  var dlg = $('[data-decide-dialog]'), reason = $('[data-decide-reason]'), dmsg = $('[data-decide-msg]'), go = $('[data-decide-go]');
  var HELP = {
    approve: 'Its items become approved for every connector and pool that names them. Environments are built by Build (or at once with compute.build: on-approval).',
    reject: 'The connector or pool stays off; the same set of requirements stays rejected until it changes.',
    revoke: 'New requests are refused at once; running ones finish. Connectors that need these items stop.'
  };
  var current = null;
  Array.prototype.forEach.call(root.querySelectorAll('[data-decide]'), function (b) {
    b.addEventListener('click', function () {
      var tr = b.closest('tr[data-request]');
      current = { id: tr.getAttribute('data-request'), verb: b.getAttribute('data-decide') };
      var label = current.verb.charAt(0).toUpperCase() + current.verb.slice(1);
      $('[data-decide-verb]').textContent = label;
      $('[data-decide-id]').textContent = current.id;
      $('[data-decide-help]').textContent = HELP[current.verb];
      go.textContent = label;
      reason.value = '';
      say(dmsg, '');
      show(dlg);
      reason.focus();
    });
  });
  Array.prototype.forEach.call(dlg.querySelectorAll('[data-close]'), function (b) { b.addEventListener('click', function () { hide(dlg); }); });
  go.addEventListener('click', function () {
    if (!current) { return; }
    if (!reason.value.trim()) { say(dmsg, 'Say why: the reason is recorded with the decision.', true); reason.focus(); return; }
    go.disabled = true;
    send('/requests/' + encodeURIComponent(current.id) + '/' + current.verb, { reason: reason.value.trim() }).then(function (r) {
      go.disabled = false;
      if (!r.ok) { say(dmsg, problem(r.body, 'The decision was not recorded.'), true); return; }
      hide(dlg);
      location.reload();
    });
  });

  // ---- pools: build, test -------------------------------------------------------------------------------------------------
  function poolAction(b, action, busyText) {
    var pool = b.closest('tr[data-pool]').getAttribute('data-pool');
    b.disabled = true;
    say(msg, busyText.replace('%s', pool));
    send('/pools/' + encodeURIComponent(pool) + '/' + action).then(function (r) {
      b.disabled = false;
      if (!r.ok) { say(msg, problem(r.body, action + ' failed'), true); return; }
      if (action === 'test') {
        say(msg, 'Pool ' + pool + ' answered from ' + r.body.worker + ' in ' + r.body.totalMs + ' ms (queued ' + r.body.queuedMs + ' ms, ran ' + r.body.runMs + ' ms).');
      } else {
        say(msg, 'Environment ' + r.body.id + (r.body.reused ? ' was already built.' : ' built.'));
        setTimeout(function () { location.reload(); }, 800);
      }
    });
  }
  Array.prototype.forEach.call(root.querySelectorAll('[data-test]'), function (b) {
    b.addEventListener('click', function () { poolAction(b, 'test', 'Testing pool %s…'); });
  });
  Array.prototype.forEach.call(root.querySelectorAll('[data-build]'), function (b) {
    b.addEventListener('click', function () { poolAction(b, 'build', 'Building the environment of %s (pip, offline, by hash): this can take minutes…'); });
  });

  // ---- running requests: cancel -------------------------------------------------------------------------------------------
  Array.prototype.forEach.call(root.querySelectorAll('[data-cancel]'), function (b) {
    b.addEventListener('click', function () {
      var id = b.closest('tr[data-running]').getAttribute('data-running');
      b.disabled = true;
      send('/running/' + encodeURIComponent(id) + '/cancel').then(function (r) {
        if (!r.ok) { b.disabled = false; say(msg, problem(r.body, 'not cancelled'), true); return; }
        location.reload();
      });
    });
  });
}());
