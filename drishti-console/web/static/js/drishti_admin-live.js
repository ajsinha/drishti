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
/* Admin → Data bindings, a kind's Live drawer (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 9.1, option B; mock-up admin-B.html):
   the kind's feeds with their state in the one list of words and a switch each (drishti-inputs' Switch), the WAITING feeds said in words,
   the kind's rules from its contract in words, how it flows, Pause / Resume / Apply now (audited by the server), and Propose change: the
   kind's binding with the feeds switched on, sent as a binding proposal a second administrator approves. Drishti Elements only: the
   drawer is drishti-core's openDrawer, the requests drishti-core's api, the switches and the form drishti-inputs'. */
(function () {
  'use strict';
  var root = document.querySelector('[data-bindings]');
  var drawer = document.querySelector('[data-live-drawer]');
  var data = document.querySelector('[data-live-feeds]');
  if (!root || !drawer || !data) { return; }
  var E = window.Drishti.elements, C = E.core, I = E.inputs;
  var page = JSON.parse(data.textContent || '{}');
  var $ = function (sel) { return drawer.querySelector(sel); };
  var kind = '', feeds = [], switches = {}, close = null, form = null, current = '';

  function say(text, bad) {
    var s = $('[data-live-said]');
    s.textContent = text;
    s.classList.toggle('t-bad', !!bad);
    s.classList.toggle('t-ok', !bad);
  }

  /** The live connectors the kind's binding names now: a name, a list, or {connectors, apply}. */
  function bound(b) {
    var v = (b || {}).live;
    if (v && typeof v === 'object' && !Array.isArray(v)) { v = v.connectors; }
    return Array.isArray(v) ? v.slice() : v ? String(v).split(',').map(function (x) { return x.trim(); }).filter(Boolean) : [];
  }

  function on(f, b) {
    var named = bound(b);
    return named.length ? named.indexOf(f.connector) >= 0 : f.state !== 'OFF' && f.state !== 'NOT CONFIGURED';
  }

  function cell(tr, text, cls) {
    var td = document.createElement('td');
    if (cls) { td.className = cls; }
    td.textContent = text;
    tr.appendChild(td);
    return td;
  }

  function tone(f) {
    return f.attention === 'fault' ? 'st-bad' : f.attention === 'amber' ? 'st-warn' : f.state === 'LIVE' ? 'st-ok' : '';
  }

  function draw() {
    var b = (page.kinds[kind] || {}).binding;
    var rows = $('[data-live-rows]');
    rows.textContent = '';
    switches = {};
    var n = 0;
    feeds.forEach(function (f) {
      var tr = document.createElement('tr');
      tr.setAttribute('data-live-row', f.connector);
      cell(tr, f.companionOf || '-', 'mono');
      cell(tr, f.connector, 'mono');
      var st = cell(tr, '');
      var pill = document.createElement('span');
      pill.className = 'st ' + tone(f);
      pill.textContent = f.state + (f.state === 'NOT CONFIGURED' && f.why ? ' · ' + f.why : '');
      st.appendChild(pill);
      cell(tr, f.perMinute == null ? '-' : String(f.perMinute) + ' /min', 'num mono');
      var host = cell(tr, '');
      var isOn = on(f, b);
      if (isOn) { n++; }
      switches[f.connector] = new I.Switch(host, { label: f.connector, value: isOn, help: 'proposed with Propose change; a second administrator approves' });
      rows.appendChild(tr);
    });
    $('[data-live-count]').textContent = n + ' of ' + feeds.length + ' switched on';
    var notes = $('[data-live-notes]');
    notes.textContent = '';
    feeds.forEach(function (f) {
      if (f.state === 'WAITING' || f.state === 'DOWN' || f.state === 'DEGRADED' || f.state === 'PAUSED') {
        var p = document.createElement('p');
        p.className = f.state === 'DOWN' ? 'adm-warn' : 'ib-warn';
        p.textContent = f.connector + ': ' + (f.health || f.state) + (f.state === 'WAITING'
          ? '. Live updates paused; its rows are shown from the lake. Retrying.' : f.state === 'PAUSED'
            ? (f.paused && f.paused.skips ? ' (paused: messages are skipped and read again at the next start)' : ' (paused: messages wait at the broker)') : '');
        notes.appendChild(p);
      }
    });
    $('[data-live-act="apply-now"]').hidden = !feeds.some(function (f) { return !!f.landing; });
    form.setValue({ both: !!(b && b.live && typeof b.live === 'object' && !Array.isArray(b.live) && b.live.apply === 'both'), reason: '' });
  }

  function detail() {
    current = (feeds.find(function (f) { return f.state !== 'OFF'; }) || feeds[0] || {}).connector || '';
    $('[data-live-connector-link]').setAttribute('href', drishti_url('/admin/connectors/') + encodeURIComponent(current));
    var dl = $('[data-live-rules]');
    dl.textContent = '';
    $('[data-live-flow]').textContent = '';
    if (!current) { return; }
    C.api.send('GET', drishti_url('/admin/api/live/') + encodeURIComponent(current)).then(function (res) {
      var d = res.body || {};
      if (!res.ok) { say((d.code ? d.code + ' ' : '') + (d.detail || 'the server did not give the feed'), true); return; }
      var words = (d.rules || {})[kind] || {};
      Object.keys(words).forEach(function (k) {
        var dt = document.createElement('dt');
        dt.textContent = k;
        var dd = document.createElement('dd');
        dd.textContent = words[k];
        dl.appendChild(dt);
        dl.appendChild(dd);
      });
      $('[data-live-flow]').textContent = (d.flow || []).join('  →  ');
    });
  }

  function open(btn) {
    kind = btn.getAttribute('data-live-open');
    feeds = (page.feeds || {})[kind] || [];
    form = form || I.formOf($('[data-live-propose-fields]'));
    $('[data-live-kind]').textContent = kind;
    say('');
    draw();
    detail();
    close = C.openDrawer(drawer, { initial: $('[data-live-close]'), onClose: function () { btn.focus(); } });
  }

  function act(what) {
    if (!current) { return; }
    C.api.send('POST', drishti_url('/admin/api/live/') + encodeURIComponent(current) + '/' + what, {}).then(function (res) {
      var d = res.body || {};
      if (!res.ok) { say((d.code ? d.code + ' ' : '') + (d.detail || 'the server said no'), true); return; }
      say(what === 'apply-now' ? 'Landed: the waiting window is in the lake.' : current + ': ' + (d.state || what) + '.');
      setTimeout(function () { window.location.reload(); }, 1200);
    });
  }

  function propose() {
    if (!form.validate().ok) { say('Say why the live feeds change.', true); return; }
    var v = form.getValue();
    var row = page.kinds[kind] || {};
    var b = Object.assign({}, row.binding || {});
    if (!(b.read && b.read.length) && row.read && row.read.length && !b.demo) { b.read = row.read.slice(); }
    var picked = feeds.filter(function (f) { return switches[f.connector] && switches[f.connector].value; }).map(function (f) { return f.connector; });
    delete b.live;
    if (picked.length) { b.live = v.both ? { connectors: picked, apply: 'both' } : picked; }
    C.api.send('POST', drishti_url('/admin/api/bindings/propose'), { kind: kind, binding: b, reason: v.reason }).then(function (res) {
      var d = res.body || {};
      if (!res.ok) { say((d.code ? d.code + ' ' : '') + (d.detail || 'the server said no'), true); return; }
      say('Proposed: a second administrator approves it.');
      setTimeout(function () { window.location.reload(); }, 1200);
    });
  }

  root.addEventListener('click', function (e) {
    var b = e.target.closest('[data-live-open]');
    if (b) { open(b); }
  });
  drawer.addEventListener('click', function (e) {
    if (e.target.closest('[data-live-close]')) { if (close) { close(); } return; }
    var a = e.target.closest('[data-live-act]');
    if (a) { act(a.getAttribute('data-live-act')); return; }
    if (e.target.closest('[data-live-propose]')) { propose(); }
  });
})();
