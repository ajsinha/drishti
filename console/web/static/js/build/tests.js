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
/* Tests as you type: a moment after the last change (800 ms) every sample is checked against the Design's Sutra by the server's one
 * checker (POST /build/designs/{id}/check), a run that a newer change has made stale is dropped, and the panel by sample matrix and
 * the status bar's "x/N" follow. A click on a cell previews that sample and selects that panel. Cells are words, not only colours
 * (ok, empty, error, no access), so the matrix reads without them.
 *
 *   new WB.Tests(box, store, hooks) -> {run(), last()}          hooks: {gotoPanel(id)}
 *   events: 'checked' {matrix, passing, total} | 'checking' | 'checkfailed' (text)                                                   */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var IDLE = 800, WORD = { ok: 'ok', empty: 'empty', error: 'error', noAccess: 'no access' };

  WB.Tests = function (box, store, hooks) {
    var timer = 0, token = 0, last = null, ctl = null;
    var summary = WB.el('p', 'wb-sum', 'Not checked yet.', { role: 'status' }), holder = WB.el('div', 'wb-matrix-wrap');
    box.appendChild(summary); box.appendChild(holder);

    function passing(m) {
      var n = m.samples.length, bad = {};
      m.panels.forEach(function (p) { p.cells.forEach(function (c, i) { if (c.status === 'error' || c.status === 'noAccess') { bad[i] = true; } }); });
      return { passing: n - Object.keys(bad).length, total: n };
    }
    function paint(m) {
      var p = passing(m), c = m.counts || {};
      summary.textContent = p.passing + ' of ' + p.total + ' samples render without errors' + (c.empty ? '; ' + c.empty + ' cell' + (c.empty === 1 ? ' is' : 's are') + ' empty' : '') + (c.error ? '; ' + c.error + ' error' + (c.error === 1 ? '' : 's') : '') + '.';
      summary.className = 'wb-sum ' + (m.ok ? 'ok' : 'bad');
      holder.textContent = '';
      var t = WB.el('table', 'wb-matrix', null, { 'aria-label': 'Panels by samples' }), head = WB.el('tr');
      head.appendChild(WB.el('th', null, 'Panel', { scope: 'col' }));
      m.samples.forEach(function (s) {
        var th = WB.el('th', 'mono', s.name.length > 18 ? s.name.slice(0, 17) + '…' : s.name, { scope: 'col', title: s.name });
        head.appendChild(th);
      });
      t.appendChild(WB.el('thead')).appendChild(head);
      var body = WB.el('tbody');
      var cellFor = function (rowId, c, i) {
        var td = WB.el('td', 'wb-cell-' + c.status), b = WB.el('button', 'wb-cell-b', WORD[c.status] || c.status, { type: 'button', 'data-status': c.status,
          'aria-label': (rowId || 'strip') + ' on ' + m.samples[i].name + ': ' + (WORD[c.status] || c.status) + (c.message ? ', ' + c.message : '') + '. Preview this sample.', title: c.message || '' });
        b.addEventListener('click', function () { store.setSample(m.samples[i].name); if (rowId && hooks.gotoPanel) { hooks.gotoPanel(rowId); } });
        td.appendChild(b);
        return td;
      };
      m.panels.forEach(function (row) {
        var tr = WB.el('tr', null, null, { 'data-panel-row': row.id });
        tr.appendChild(WB.el('th', 'mono', row.id, { scope: 'row' }));
        row.cells.forEach(function (c, i) { tr.appendChild(cellFor(row.id, c, i)); });
        body.appendChild(tr);
      });
      var blanks = (m.strip || []).filter(function (s) { return s.blank; });
      if (blanks.length) {
        var tr2 = WB.el('tr');
        tr2.appendChild(WB.el('th', null, 'Strip: ' + blanks.map(function (s) { return s.label + ' (' + s.blank + ' blank)'; }).join(', '), { scope: 'row' }));
        tr2.appendChild(WB.el('td', null, null, { colspan: String(m.samples.length) }));
        body.appendChild(tr2);
      }
      t.appendChild(body); holder.appendChild(t);
    }

    function run() {
      clearTimeout(timer);
      var mine = ++token;
      if (ctl) { ctl.abort(); }
      if (!store.state.samples.length || !store.state.yaml) { summary.textContent = 'Add a sample to check the design against.'; holder.textContent = ''; store.emit('checked', null); return Promise.resolve(); }
      ctl = typeof AbortController === 'function' ? new AbortController() : null;
      store.emit('checking');
      summary.textContent = 'Checking ' + store.state.samples.length + ' samples...';
      return fetch(store.base + '/check', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}', signal: ctl ? ctl.signal : undefined })
        .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b, status: r.status }; }); })
        .then(function (r) {
          if (mine !== token) { return; }                        // a newer change has started a newer run
          if (!r.ok) {
            var cp = r.body.checkProblems || [];
            if (cp.length) { store.state.checkProblems = cp; store.emit('checkprobs', cp); }
            summary.textContent = cp.length ? 'The check cannot run while the Sutra has problems: ' + cp.length + ' listed under Problems.' : 'The check could not run: ' + WB.why(r);
            summary.className = 'wb-sum bad'; holder.textContent = ''; store.emit('checkfailed', WB.why(r)); return;
          }
          if ((store.state.checkProblems || []).length) { store.state.checkProblems = []; store.emit('checkprobs', []); }
          last = r.body; paint(r.body);
          var p = passing(r.body);
          store.emit('checked', { matrix: r.body, passing: p.passing, total: p.total });
        }, function (e) { if (e && e.name === 'AbortError') { return; } if (mine === token) { summary.textContent = 'The console could not be reached.'; store.emit('checkfailed', 'unreachable'); } });
    }
    function later() { clearTimeout(timer); timer = setTimeout(run, IDLE); }
    store.on('doc', function (d) { if (d.changed) { later(); } });
    store.on('samplesChanged', later);
    return { run: run, last: function () { return last; }, later: later };
  };
})();
