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
/* Monitor page: one stream carries every row's changed strip cells; values update in place and flash. */
(function () {
  'use strict';
  var root = document.querySelector('[data-monitor]');
  if (!root) { return; }
  var name = root.dataset.name, msg = root.querySelector('[data-msg]');
  var liveText = document.querySelector('[data-live-text]'), liveBox = document.querySelector('.tbar-live');
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function entities() {
    return [].map.call(root.querySelectorAll('[data-row]'), function (r) { return { kind: r.dataset.kind, id: r.dataset.id }; });
  }
  function save(list) {
    return fetch('/m/api/' + encodeURIComponent(name), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ entities: list }) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); });
  }
  if (window.DrishtiChannel) {
    window.DrishtiChannel.subscribe('monitor:' + name, { row: onRow, error: onError });   // the tab's one live connection
  }
  function onRow(d) {
    {
      var row = root.querySelector('[data-row="' + CSS.escape(d.kind + '/' + d.id) + '"]');
      if (liveText) { liveText.textContent = 'Live, p99 ' + Math.round(d.p99Ms) + ' ms'; liveBox.setAttribute('data-live-state', 'live'); }
      if (!row) { return; }
      d.patches.forEach(function (p) {
        var dd = row.querySelector('[data-i="' + p.index + '"]');
        if (!dd) { return; }
        dd.innerHTML = p.cell.link ? '<a class="lnk" href="/v/' + encodeURIComponent(p.cell.link.kind) + '/' + encodeURIComponent(p.cell.link.id) + '">' + esc(p.cell.text) + '</a>' : esc(p.cell.text);
        dd.className = dd.className.replace(/\bt-\w+/g, '').trim() + (p.cell.tone ? ' t-' + p.cell.tone : '');
        dd.classList.remove('flash'); void dd.offsetWidth; dd.classList.add('flash');
      });
    }
  }
  function onError() { if (liveBox) { liveBox.setAttribute('data-live-state', 'reconnecting'); liveText.textContent = 'Reconnecting…'; } }
  root.querySelector('[data-add]').addEventListener('submit', function (e) {
    e.preventDefault();
    var input = e.target.querySelector('input');
    fetch('/api/resolve', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: input.value }) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
      .then(function (res) {
        if (!res.ok) { msg.textContent = res.b.detail || 'Unknown entity'; return null; }
        return save(entities().concat([res.b.ref]));
      })
      .then(function (res) { if (res && res.ok) { location.reload(); } else if (res) { msg.textContent = res.b.detail; } });
  });
  root.querySelectorAll('[data-remove]').forEach(function (b) {
    b.addEventListener('click', function () {
      var row = b.closest('[data-row]');
      save(entities().filter(function (x) { return x.kind + '/' + x.id !== row.dataset.row; })).then(function (res) { if (res.ok) { location.reload(); } else { msg.textContent = res.b.detail; } });
    });
  });
  root.querySelector('[data-delete]').addEventListener('click', function () {
    if (window.confirm('Delete monitor “' + name + '”?')) { fetch('/m/api/' + encodeURIComponent(name) + '/delete', { method: 'POST' }).then(function () { location.href = '/m'; }); }
  });
})();
