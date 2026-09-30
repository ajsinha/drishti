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
/* Workspaces: panes are same-origin embedded views (each keeps its own live stream and keys). A link clicked in
   a pane arrives here as a message; if another pane follows that pane, the follower opens the entity, otherwise
   the pane itself navigates. Alt+1..4 focuses a pane. Save stores the workspace for the signed-in user. */
(function () {
  'use strict';
  var root = document.querySelector('[data-ws]');
  if (!root) { return; }
  var ws = JSON.parse(root.getAttribute('data-json'));
  var grid = root.querySelector('[data-grid]'), tpl = document.getElementById('paneTpl'), msg = root.querySelector('[data-msg]');
  var layoutSel = root.querySelector('[data-layout]');
  var frames = [];

  function say(t, bad) { msg.textContent = t; msg.classList.toggle('t-bad', !!bad); }
  function src(ref) { return ref ? '/v/' + encodeURIComponent(ref.kind) + '/' + encodeURIComponent(ref.id) + '?embed=1' : 'about:blank'; }

  function render() {
    grid.className = 'ws-grid ws-' + ws.layout.replace('+', 'p');
    layoutSel.value = ws.layout;
    grid.innerHTML = '';
    frames = [];
    ws.panes.forEach(function (p, i) {
      var node = tpl.content.firstElementChild.cloneNode(true);
      node.querySelector('.ws-num').textContent = String(i + 1);
      var input = node.querySelector('[data-pane-input]');
      input.value = p.ref ? p.ref.id : '';
      input.setAttribute('aria-label', 'Entity for pane ' + (i + 1));
      var follows = node.querySelector('[data-follows]');
      ws.panes.forEach(function (q, j) {
        if (j !== i) { var o = document.createElement('option'); o.value = j; o.textContent = 'pane ' + (j + 1); follows.appendChild(o); }
      });
      follows.value = p.follows == null ? '' : String(p.follows);
      follows.addEventListener('change', function () { p.follows = follows.value === '' ? null : +follows.value; });
      node.querySelector('[data-pane-cmd]').addEventListener('submit', function (e) {
        e.preventDefault();
        fetch('/api/resolve', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: input.value }) })
          .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
          .then(function (res) { if (res.ok) { open(i, res.b.ref); } else { say(res.b.detail || 'Unknown entity', true); } });
      });
      node.querySelector('[data-remove]').addEventListener('click', function () {
        if (ws.panes.length <= 1) { return; }
        ws.panes.splice(i, 1);
        ws.panes.forEach(function (q) { if (q.follows === i) { q.follows = null; } else if (q.follows > i) { q.follows--; } });
        render();
      });
      var frame = node.querySelector('iframe');
      frame.title = 'Pane ' + (i + 1) + (p.title ? ': ' + p.title : '');
      frame.src = src(p.ref);
      node.querySelector('[data-open]').href = p.ref ? '/v/' + p.ref.kind + '/' + p.ref.id : '#';
      frames.push(frame);
      grid.appendChild(node);
    });
  }

  function open(i, ref) {
    ws.panes[i].ref = ref;
    var pane = grid.children[i];
    pane.querySelector('[data-pane-input]').value = ref.id;
    pane.querySelector('[data-open]').href = '/v/' + ref.kind + '/' + ref.id;
    frames[i].src = src(ref);
  }

  window.addEventListener('message', function (e) {
    if (e.origin !== location.origin || !e.data || e.data.type !== 'drishti:select') { return; }
    var from = frames.findIndex(function (f) { return f.contentWindow === e.source; });
    if (from < 0) { return; }
    var ref = { kind: e.data.kind, id: e.data.id };
    var followers = ws.panes.map(function (p, j) { return p.follows === from ? j : -1; }).filter(function (j) { return j >= 0; });
    if (followers.length) { followers.forEach(function (j) { open(j, ref); }); } else { open(from, ref); }
  });

  layoutSel.addEventListener('change', function () { ws.layout = layoutSel.value; render(); });
  root.querySelector('[data-add]').addEventListener('click', function () {
    if (ws.panes.length >= 4) { say('A workspace has at most four panes.', true); return; }
    ws.panes.push({ ref: null, follows: null, title: '' });
    render();
  });
  function save(name) {
    return fetch('/w/api/' + encodeURIComponent(name), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ws) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
      .then(function (res) {
        if (res.ok) { say('Saved “' + name + '”.'); if (name !== root.dataset.name) { location.href = '/w/' + encodeURIComponent(name); } }
        else { say((res.b.code || 'Error') + ': ' + res.b.detail, true); }
      });
  }
  root.querySelector('[data-save]').addEventListener('click', function () { save(root.dataset.name); });
  root.querySelector('[data-saveas]').addEventListener('click', function () {
    var n = window.prompt('Save workspace as:', root.dataset.name);
    if (n) { save(n.trim()); }
  });
  root.querySelector('[data-delete]').addEventListener('click', function () {
    if (!window.confirm('Delete workspace “' + root.dataset.name + '”?')) { return; }
    fetch('/w/api/' + encodeURIComponent(root.dataset.name) + '/delete', { method: 'POST' }).then(function () { location.href = '/w'; });
  });
  document.addEventListener('keydown', function (e) {
    if (e.altKey && /^[1-4]$/.test(e.key) && frames[+e.key - 1]) {
      e.preventDefault();
      grid.children[+e.key - 1].focus();
      frames[+e.key - 1].focus();
    }
  });
  render();
})();
