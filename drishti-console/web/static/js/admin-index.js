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
/* Admin: reload a connector's type-ahead index. The connector page lists kinds by days with a Reload per day, per kind and for all;
   Admin -> Health has a Reload index per connector. Same-origin JSON to /admin/connectors/api/<name>/index[/reload]; the server enforces
   the admin role, allows one reload per connector at a time and audits each. Progress is polled; text from the server is only ever put in
   the page as text. */
(function () {
  'use strict';
  var panel = document.querySelector('[data-index-panel]');
  var timers = {};

  function url(name, tail) { return '/admin/connectors/api/' + encodeURIComponent(name) + '/index' + (tail || ''); }
  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text != null) { e.textContent = text; }
    return e;
  }
  function say(name, text, bad) {
    document.querySelectorAll('[data-index-msg]').forEach(function (m) {
      var host = m.closest('[data-index-panel]') || m.parentNode;
      var btn = host.querySelector('[data-index-reload]');
      if (btn && btn.getAttribute('data-connector') === name) { m.textContent = text; m.classList.toggle('t-bad', !!bad); }
    });
  }
  function mb(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : b >= 1024 ? Math.round(b / 1024) + ' KB' : b + ' B'; }
  function when(t) { return t ? new Date(t).toLocaleString() : 'not yet'; }

  function render(name, s) {
    if (!panel || panel.getAttribute('data-connector') !== name) { return; }
    panel.hidden = !s.enabled;
    if (!s.enabled) { return; }
    var a = s.auto || {};
    panel.querySelector('[data-index-auto]').textContent = a.enabled
      ? 'Automatic check every ' + a.everySeconds + ' s. Last check: ' + when(a.lastCheck) + '. Next check: ' + when(a.nextCheck) +
        '. Last change found: ' + (a.lastChange ? when(a.lastChange) + ' (' + a.lastChangeWhat + ')' : 'none') + '.'
    : 'Automatic check is off (index.reload-every is 0).';
    var body = panel.querySelector('[data-index-table] tbody');
    body.textContent = '';
    var lastKind = null;
    (s.days || []).forEach(function (d) {
      var tr = el('tr', { 'data-day': d.date, 'data-kind': d.kind });
      tr.appendChild(el('th', { scope: 'row', 'class': 'mono' }, d.kind === lastKind ? '' : d.kind));
      if (d.kind !== lastKind) {
        var kb = el('button', { type: 'button', 'class': 'fk', 'data-index-reload': '', 'data-connector': panel.getAttribute('data-connector'), 'data-kind': d.kind, 'aria-label': 'Reload every day of ' + d.kind }, 'Reload kind');
        tr.firstChild.appendChild(document.createTextNode(' '));
        tr.firstChild.appendChild(kb);
      }
      lastKind = d.kind;
      tr.appendChild(el('td', { 'class': 'mono' }, d.date));
      tr.appendChild(el('td', {}, d.tier === 'heap' ? 'memory' : d.tier === 'disk' ? 'disk' : 'not built'));
      tr.appendChild(el('td', { 'class': 'num mono' }, String(d.ids)));
      tr.appendChild(el('td', { 'class': 'num mono' }, mb(d.bytes)));
      tr.appendChild(el('td', { 'class': 'mono' }, d.builtAt ? when(d.builtAt) : '—'));
      var st = el('td', { 'data-state': d.building ? 'building' : d.lastError ? 'error' : 'ok' });
      st.appendChild(el('span', { 'class': 'st ' + (d.building ? 'st-warn' : d.lastError ? 'st-bad' : 'st-ok') }, d.building ? 'rebuilding' : d.lastError ? 'failed' : 'fresh'));
      if (d.lastError) { st.appendChild(el('div', { 'class': 't-bad' }, d.lastError)); }
      tr.appendChild(st);
      var td = el('td');
      td.appendChild(el('button', { type: 'button', 'class': 'fk', 'data-index-reload': '', 'data-connector': panel.getAttribute('data-connector'), 'data-kind': d.kind, 'data-date': d.date,
        'aria-label': 'Reload ' + d.kind + ' ' + d.date }, 'Reload'));
      tr.appendChild(td);
      body.appendChild(tr);
    });
    var busy = !!(s.reload && s.reload.running);
    panel.querySelectorAll('[data-index-reload]').forEach(function (b) { b.disabled = busy; });
  }

  function poll(name) {
    clearTimeout(timers[name]);
    fetch(url(name)).then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); }).then(function (r) {
      if (!r.ok) { say(name, window.drsMessage ? window.drsMessage(r.b, 'Could not read the index') : 'Could not read the index', true); return; }
      var rl = r.b.reload || {};
      render(name, r.b);
      if (rl.running) {
        say(name, 'Rebuilding ' + (rl.current || '…') + ': ' + rl.done + ' of ' + rl.total + ' days done' + (rl.trigger === 'auto' ? ' (automatic)' : ''));
        timers[name] = setTimeout(function () { poll(name); }, 700);
      } else {
        if (rl.finishedAt) {
          say(name, rl.error ? 'Reload finished with errors: ' + rl.error : 'Reloaded ' + rl.done + (rl.done === 1 ? ' day' : ' days') + ' from the source (' + when(rl.finishedAt) + ').', !!rl.error);
        }
        timers[name] = setTimeout(function () { poll(name); }, 15000);
      }
    }).catch(function () { say(name, 'The console could not be reached.', true); });
  }

  document.addEventListener('click', function (ev) {
    var b = ev.target.closest ? ev.target.closest('[data-index-reload]') : null;
    if (!b || b.disabled) { return; }
    var name = b.getAttribute('data-connector');
    var body = {};
    if (b.getAttribute('data-kind')) { body.kind = b.getAttribute('data-kind'); }
    if (b.getAttribute('data-date')) { body.date = b.getAttribute('data-date'); }
    say(name, 'Starting…');
    fetch(url(name, '/reload'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, body: j }; });
    }).then(function (r) {
      if (!r.ok) {
        say(name, r.body.code === 'DRS-5032' ? 'A reload of this connector is already running: ' + (r.body.detail || '') : (window.drsMessage ? window.drsMessage(r.body, 'Reload failed') : (r.body.detail || 'Reload failed')), true);
        poll(name);
        return;
      }
      render(name, r.body);
      poll(name);
    }).catch(function () { say(name, 'Reload failed: the console could not be reached.', true); });
  });

  if (panel) { poll(panel.getAttribute('data-connector')); }
})();
