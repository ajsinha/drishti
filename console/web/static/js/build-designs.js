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
/* Build workbench, My designs (/build): rename, duplicate and delete, each one request to the console, which asks the server.
 * Times are shown in the viewer's own zone. All names go in as text nodes. */
(function () {
  'use strict';
  var root = document.querySelector('[data-designs]');
  if (!root) { return; }
  var status = root.querySelector('[data-status]');
  function say(text, bad) { status.textContent = text; status.classList.toggle('bad', !!bad); }
  function json(method, url, body) {
    return fetch(url, { method: method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) { return (r.status === 204 ? Promise.resolve({}) : r.json()).then(function (j) { return { ok: r.ok, body: j, status: r.status }; }); });
  }
  function why(r) { return (r.body.detail || 'The request failed') + ' (' + (r.body.code || r.status) + ')'; }

  root.querySelectorAll('time[data-ms]').forEach(function (t) {
    var ms = parseInt(t.dataset.ms, 10);
    if (ms) { var d = new Date(ms); t.dateTime = d.toISOString(); t.textContent = d.toLocaleString(); }
  });

  root.querySelectorAll('tr[data-design]').forEach(function (row) {
    var id = row.dataset.design;
    row.querySelector('[data-delete]').addEventListener('click', function () {
      var name = row.dataset.name || 'this unnamed design';
      if (!window.confirm('Delete ' + name + ' and the samples it holds? This cannot be undone.')) { return; }
      json('DELETE', '/build/designs/' + id).then(function (r) {
        if (!r.ok) { say(why(r), true); return; }
        row.remove();
        say('Deleted.');
      }, function () { say('The console could not be reached. Try again.', true); });
    });
    row.querySelector('[data-duplicate]').addEventListener('click', function () {
      json('POST', '/build/designs/' + id + '/duplicate', {}).then(function (r) {
        if (!r.ok) { say(why(r), true); return; }
        window.location.reload();
      }, function () { say('The console could not be reached. Try again.', true); });
    });
    row.querySelector('[data-rename]').addEventListener('click', function () {
      var cell = row.querySelector('[data-cell-name]'), old = cell.innerHTML;
      var input = document.createElement('input');
      input.className = 'studio-in'; input.type = 'text'; input.maxLength = 120; input.value = row.dataset.name;
      input.setAttribute('aria-label', 'New name');
      var save = document.createElement('button'); save.type = 'button'; save.className = 'btn-pill btn-accent'; save.textContent = 'Save';
      var cancel = document.createElement('button'); cancel.type = 'button'; cancel.className = 'btn-pill btn-ghost'; cancel.textContent = 'Cancel';
      cell.textContent = '';
      cell.appendChild(input); cell.appendChild(save); cell.appendChild(cancel);
      input.focus();
      cancel.addEventListener('click', function () { cell.innerHTML = old; });
      function commit() {
        json('PATCH', '/build/designs/' + id, { name: input.value }).then(function (r) {
          if (!r.ok) { say(why(r), true); return; }
          window.location.reload();
        }, function () { say('The console could not be reached. Try again.', true); });
      }
      save.addEventListener('click', commit);
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { commit(); } else if (e.key === 'Escape') { cell.innerHTML = old; }
      });
    });
  });
})();
