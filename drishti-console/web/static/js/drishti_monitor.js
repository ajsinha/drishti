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
/* Monitor page: one stream carries every row's changed strip cells; values update in place and flash. */
(function () {
  'use strict';
  var root = document.querySelector('[data-monitor]');
  if (!root) { return; }
  var name = root.dataset.name, msg = root.querySelector('[data-msg]');
  var liveText = document.querySelector('[data-live-text]'), liveBox = document.querySelector('[data-live-status]');
  var host = root.querySelector('[data-monitor-grid]'), table = [], grid = null;
  try { table = JSON.parse(host.getAttribute('data-rows')) || []; } catch (e) { table = []; }
  function entities() { return table.map(function (r) { return { kind: r.KIND, id: r.ID }; }); }
  function mount() {   // the records grid in its static mode (js/drishti_records.js); the monitor's stream feeds it
    var spec = JSON.parse(host.getAttribute('data-spec'));
    grid = window.Drishti.records.mount(host, { rows: table, spec: spec, keyField: 'KEY', title: name, rowActions: [{ label: 'Remove from the monitor', run: remove }] });
  }
  function remove(row) {
    save(entities().filter(function (x) { return x.kind + '/' + x.id !== row.KEY; })).then(function (res) { if (res.ok) { location.reload(); } else { msg.textContent = res.b.detail; } });
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', mount); } else { mount(); }
  function save(list) {
    return fetch('/m/api/' + encodeURIComponent(name), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ entities: list }) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); });
  }
  if (window.Drishti.Channel) {
    window.Drishti.Channel.subscribe('monitor:' + name, { row: onRow, error: onError });   // the tab's one live connection
  }
  function onRow(d) {
    {
      var row = table.filter(function (r) { return r.KEY === d.kind + '/' + d.id; })[0];
      if (liveText) { liveText.textContent = 'Live, p99 ' + Math.round(d.p99Ms) + ' ms'; liveBox.setAttribute('data-live-state', 'live'); liveBox.title = (liveBox.getAttribute('data-tip') || '') + ' · ' + liveText.textContent; }
      if (!row || !grid) { return; }
      var fields = {};
      d.patches.forEach(function (p) { var f = row._cells[String(p.index)]; if (f) { fields[f] = p.cell.text == null || p.cell.text === '' ? '—' : p.cell.text; row[f] = fields[f]; if (p.cell.tone !== undefined) { fields[f + '_TONE'] = p.cell.tone || ''; row[f + '_TONE'] = fields[f + '_TONE']; } } });
      grid.patch([{ id: row.KEY, fields: fields }]);
    }
  }
  function onError() { if (liveBox) { liveBox.setAttribute('data-live-state', 'reconnecting'); liveText.textContent = 'Reconnecting…'; liveBox.title = (liveBox.getAttribute('data-tip') || '') + ' · Reconnecting…'; } }
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
  root.querySelector('[data-delete]').addEventListener('click', function () {
    if (window.confirm('Delete monitor “' + name + '”?')) { fetch('/m/api/' + encodeURIComponent(name) + '/delete', { method: 'POST' }).then(function () { location.href = '/m'; }); }
  });
})();
