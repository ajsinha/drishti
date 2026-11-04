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
/* Admin → Data loads → Inbox (DATALOAD_INBOX.md section 10, mock-up A). The board is drawn by the server (_inbox_body.html); this file
   only redraws it from /admin/loads/inbox?partial=1 (every 5 s while a run loads, 30 s when quiet, never while the tab is hidden), keeps
   which columns a person opened or closed across redraws (on a phone the columns start closed but Loading), and sends Load now and Retry,
   saying the server's answer in words. It computes no figure: every count, time and duration on the board is the server's. */
(function () {
  'use strict';
  var root = document.querySelector('[data-inbox]');
  if (!root) { return; }
  var body = root.querySelector('[data-inbox-body]');
  var said = root.querySelector('[data-inbox-said]');
  var BUSY = 5000, QUIET = 30000;
  var timer = null;
  var opened = {};                                   // column -> open, as the person left it
  var phone = window.matchMedia ? window.matchMedia('(max-width: 640px)') : { matches: false };

  function say(text, bad) {
    said.textContent = text;
    said.classList.toggle('t-bad', !!bad);
    said.classList.toggle('t-ok', !bad && !!text);
  }
  function running() {
    var b = body.querySelector('[data-inbox-board]');
    return !!b && b.getAttribute('data-running') === '1';
  }
  function columns() {
    Array.prototype.forEach.call(body.querySelectorAll('details[data-col]'), function (d) {
      var col = d.getAttribute('data-col');
      if (Object.prototype.hasOwnProperty.call(opened, col)) {
        d.open = opened[col];
      } else if (phone.matches) {
        d.open = col === 'loading' && running();
      }
      d.addEventListener('toggle', function () { opened[col] = d.open; });
    });
  }
  function schedule() {
    if (timer) { window.clearTimeout(timer); }
    timer = window.setTimeout(refresh, running() ? BUSY : QUIET);
  }
  function refresh() {
    if (document.hidden) { schedule(); return; }
    fetch('/admin/loads/inbox?partial=1', { headers: { Accept: 'text/html' }, credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.text() : null; })
      .then(function (html) { if (html) { body.innerHTML = html; columns(); } })
      .catch(function () { /* offline: keep the last picture */ })
      .then(schedule);
  }
  function post(url, data) {
    return fetch(url, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(data || {}) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, body: j }; }); })
      .catch(function () { return { ok: false, body: { detail: 'the console could not reach the server' } }; });
  }
  function problem(b) { return (b.code ? b.code + ' ' : '') + (b.detail || 'the server said no'); }

  body.addEventListener('click', function (e) {
    var run = e.target.closest('[data-inbox-run]');
    var retry = e.target.closest('[data-retry]');
    if (run && !run.disabled) {
      run.disabled = true;
      say('Load now: asking the server...');
      post('/admin/api/dataload/run').then(function (res) {
        run.disabled = false;
        if (!res.ok) { say('Load now: ' + problem(res.body), true); return; }
        if (res.body.started) {
          say('Load now: started. The board follows the run.');
        } else {
          say('Load now: ' + (res.body.skipped || 'skipped') + '. Nothing was started; the waiting files stay in the inbox for the next cycle.', true);
        }
        refresh();
      });
    } else if (retry) {
      var file = retry.getAttribute('data-retry');
      retry.disabled = true;
      say('Retry ' + file + ': asking the server...');
      post('/admin/api/dataload/retry', { file: file }).then(function (res) {
        retry.disabled = false;
        if (!res.ok) { say('Retry ' + file + ': ' + problem(res.body), true); return; }
        say(res.body.file + ': back in the inbox (from ' + res.body.from + '); ' + res.body.next + '.');
        refresh();
      });
    }
  });

  columns();
  schedule();
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { refresh(); } });
})();
