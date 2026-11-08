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
/* Admin → Data loads → Feeds: Run now (POST /admin/api/feeds/<feed>/run; the server starts the feed in the background or says one is
   running); the answer stays in the line above the tables, and the page reloads a little later to show the run. The tables themselves are
   console tables (drishti-grid). */
(function () {
  'use strict';
  var root = document.querySelector('[data-feeds]');
  if (!root) { return; }
  var said = root.querySelector('[data-feeds-said]');

  function say(text, bad) {
    if (!said) { return; }
    said.textContent = text;
    said.classList.toggle('t-bad', !!bad);
    said.classList.toggle('t-ok', !bad);
  }

  root.addEventListener('click', function (e) {
    var b = e.target.closest('[data-feed-run]');
    if (!b) { return; }
    var feed = b.getAttribute('data-feed-run');
    b.disabled = true;
    say('Starting ' + feed + '…');
    fetch('/admin/api/feeds/' + encodeURIComponent(feed) + '/run', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: '{}' })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, body: j }; }); })
      .catch(function () { return { ok: false, body: { detail: 'the console could not reach the server' } }; })
      .then(function (res) {
        var j = res.body || {};
        if (!res.ok) {
          b.disabled = false;
          say((j.code ? j.code + ' ' : '') + (j.detail || 'the server said no'), true);
          return;
        }
        if (j.started) {
          say(feed + ' started (process ' + j.pid + '); its output goes to ' + j.log + '. The run appears below when it ends.');
          setTimeout(function () { window.location.reload(); }, 8000);
        } else {
          b.disabled = false;
          say(j.skipped || (feed + ' did not start'), true);
        }
      });
  });
})();
