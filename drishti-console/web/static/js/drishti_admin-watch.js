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
/* Admin → Packs and Admin → Connectors notice changes made elsewhere: a file edited on disk, a deploy, another administrator, the CLI.
   Every few seconds (data-watch-seconds, default 5) the page asks a short route for one row per pack or connector and a digest of them
   (data-watch="/admin/api/packs/stamp" or "/admin/connectors/api/stamp"). The first answer is the baseline; when the digest moves, a banner says
   what changed and offers Refresh (the list is not redrawn under the reader's hands). A connector's own page also updates its status words in
   place, so a Save shows the connector starting, then running. Polling pauses while the tab is hidden. Text from the server is only ever
   put in the page as text. */
(function () {
  'use strict';
  var root = document.querySelector('[data-watch]');
  if (!root) { return; }
  var url = root.getAttribute('data-watch'), every = (parseInt(root.getAttribute('data-watch-seconds'), 10) || 5) * 1000;
  var detail = root.getAttribute('data-detail') || '', only = root.getAttribute('data-pack-detail') || '';   // a connector's page updates in place; a pack's page watches its own row
  var baseline = null, rowsThen = {}, banner = null, timer = null, failures = 0;

  function words(r) {
    if (r.label) { return r.label; }
    if (r.loaded === false) { return 'not loaded'; }
    if (r.loaded === true) { return r.enabled ? 'on' : 'off'; }
    return String(r.state || '').toLowerCase();
  }
  function describe(now) {
    var out = [], seen = {};
    now.forEach(function (r) {
      seen[r.name] = true;
      var was = rowsThen[r.name];
      if (!was) { out.push(r.name + ' was added'); return; }
      var a = JSON.stringify([words(was), was.etag || was.version, was.problem || '', was.sutraProblems || 0, (was.kinds || []).join(',')]);
      var b = JSON.stringify([words(r), r.etag || r.version, r.problem || '', r.sutraProblems || 0, (r.kinds || []).join(',')]);
      if (a !== b) { out.push(r.name + (words(was) !== words(r) ? ': ' + words(was) + ' to ' + words(r) : ' changed')); }
    });
    Object.keys(rowsThen).forEach(function (n) { if (!seen[n]) { out.push(n + ' was removed'); } });
    return out;
  }
  function show(changes) {
    if (!banner) {
      banner = document.createElement('div');
      banner.className = 'adm-warn adm-changed';
      banner.setAttribute('role', 'status');
      banner.setAttribute('data-changed', '');
      var span = document.createElement('span');
      span.setAttribute('data-changed-text', '');
      var b = document.createElement('button');
      b.type = 'button'; b.className = 'fk'; b.textContent = 'Refresh'; b.setAttribute('data-changed-refresh', '');
      b.addEventListener('click', function () { location.reload(); });
      banner.appendChild(span); banner.appendChild(document.createTextNode(' ')); banner.appendChild(b);
      var anchor = root.querySelector('[data-msg]');
      if (anchor && anchor.parentNode) { anchor.parentNode.insertBefore(banner, anchor.nextSibling); } else { root.insertBefore(banner, root.firstChild); }
    }
    banner.querySelector('[data-changed-text]').textContent = 'Changed on the server: ' + (changes.length ? changes.slice(0, 4).join('; ') + (changes.length > 4 ? '; and ' + (changes.length - 4) + ' more' : '') : 'the list is out of date') + '.';
  }
  // a connector's own page: its status badge and health sentence follow the server
  function live(r) {
    var badge = root.querySelector('[data-state]'), health = root.querySelector('[data-health]');
    if (!badge || !r) { return; }
    badge.textContent = r.label || { RUNNING: 'running', DISABLED: 'disabled', OFFLINE: 'off (offline mode)', FAILED: 'failed', NOT_LOADED: 'not started' }[r.state] || String(r.state || '').toLowerCase();
    badge.classList.remove('st-ok', 'st-bad', 'st-warn');
    var cls = { ok: 'st-ok', down: 'st-bad', warn: 'st-warn' }[r.level];
    if (cls) { badge.classList.add(cls); }
    if (health) {
      health.textContent = r.health || '';
      health.classList.remove('t-bad', 't-warn', 'text-muted-d');
      health.classList.add(r.level === 'down' ? 't-bad' : r.level === 'warn' ? 't-warn' : 'text-muted-d');
    }
  }
  function tick() {
    clearTimeout(timer);
    if (document.hidden) { timer = setTimeout(tick, every); return; }
    fetch(url, { cache: 'no-store', credentials: 'same-origin' }).then(function (r) { return r.ok ? r.json() : Promise.reject(new Error(String(r.status))); }).then(function (b) {
      failures = 0;
      var rows = (b.rows || []).filter(function (r) { return !only || r.name === only; });
      if (only) { b.stamp = JSON.stringify(rows); }
      if (detail) { live(rows.filter(function (r) { return r.name === detail; })[0]); }
      if (baseline === null) {
        baseline = b.stamp; rowsThen = {};
        rows.forEach(function (r) { rowsThen[r.name] = r; });
      } else if (b.stamp !== baseline && !detail) {
        show(describe(rows));
      } else if (detail && b.stamp !== baseline) {
        baseline = b.stamp;
        rows.forEach(function (r) { rowsThen[r.name] = r; });
      }
    }).catch(function () { failures++; }).then(function () { timer = setTimeout(tick, failures > 3 ? every * 4 : every); });
  }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { tick(); } });
  tick();
})();
