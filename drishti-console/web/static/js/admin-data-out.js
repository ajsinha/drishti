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
/* Admin -> Data out (templates/admin/data_out.html; FLIGHT_GUIDE.md section 9; mock-ups A and B of docs/architecture/mockups/data-out/).
   One answer (GET /admin/api/data-out), two views switched by the toggle (the choice kept in the address, ?view=map): the page (A) and the
   map (B). The shared parts (the service bar, a node card, a read's detail with Stop) are drawn by the same functions in both views. The
   answer is read again every 5 seconds; Stop asks the server to end the read after its current batch (the client gets DRS-5610). */
(function () {
  'use strict';
  var root = document.querySelector('[data-dout]');
  if (!root) { return; }
  var data = JSON.parse(root.querySelector('[data-dout-json]').getAttribute('data-dout-json') || '{}');
  var view = root.getAttribute('data-dout-view') === 'map' ? 'map' : 'page';
  var selected = null;     // the id of the live read selected on the map
  var $ = function (sel) { return root.querySelector(sel); };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; });
  }
  function bytes(n) {
    n = Number(n || 0);
    if (n >= 1 << 30) { return (n / (1 << 30)).toFixed(1) + ' GB'; }
    if (n >= 1 << 20) { return (n / (1 << 20)).toFixed(1) + ' MB'; }
    if (n >= 1 << 10) { return Math.round(n / (1 << 10)) + ' kB'; }
    return n + ' B';
  }
  function num(n) { return Number(n || 0).toLocaleString('en-US'); }
  function when(iso) {
    if (!iso) { return ''; }
    var d = new Date(iso);
    return isNaN(d) ? esc(iso) : d.toISOString().slice(11, 16) + ' UTC';
  }
  function pill(cls, text) { return '<span class="dout-pill ' + cls + '">' + esc(text) + '</span>'; }
  function who(person, onBehalfOf) {
    return esc(person) + (onBehalfOf ? '<br><span class="dout-sub">for <b>' + esc(onBehalfOf) + '</b></span>' : '');
  }
  function limit(v, unit) { return Number(v || 0) <= 0 ? 'none' : (unit === 'bytes' ? bytes(v) : unit === 's' ? num(v) + ' s' : num(v)); }

  // ---------------------------------------------------------------------------------------------------------- shared parts

  function drawService() {
    var s = data.service || {};
    var mem = s.memory || {};
    var share = mem.cap ? Math.round(100 * mem.used / mem.cap) : 0;
    $('[data-dout-service]').innerHTML =
      '<span class="dout-sw' + (s.running ? ' on' : '') + '"><i aria-hidden="true"></i><b>Flight service ' + (s.running ? 'running' : (s.enabled ? 'not running' : 'off')) + '</b></span>' +
      '<span><span class="k">Port</span><b class="mono">' + esc(s.port) + '</b></span>' +
      '<span><span class="k">TLS</span>' + pill('g', 'on') + (s.mutualTls ? ' ' + pill('b', 'mutual TLS') : ' <span class="dout-sub">mutual TLS off</span>') + '</span>' +
      '<span><span class="k">Arrow memory</span><b class="mono">' + bytes(mem.used) + '</b> of ' + bytes(mem.cap) + ' (' + share + ' %)</span>' +
      '<span><span class="k">Serves</span>' + esc((s.forms || []).join(', ')) + '</span>' +
      (s.problem ? '<span class="dout-bad" role="alert">' + esc(s.problem) + '</span>' : '') +
      '<span class="acts"><a class="btn btn-sm btn-outline-secondary" href="/admin/engines">Admin → Engines</a></span>';
  }

  function liveOn(nodeId) { return (data.live || []).filter(function (l) { return l.node === nodeId; }).length; }

  function nodeCard(n) {
    var live = liveOn(n.id);
    return '<div class="dout-node"><div class="h"><b>' + esc(n.id) + '</b>' + pill('g', 'up') + '<span class="dout-sub">' + live + ' live' +
      (n.self ? ' · this node' : '') + '</span></div><span class="ad">grpc+tls://' + esc(n.host) + ':' + esc(n.port) + '</span></div>';
  }

  function detail(l) {
    if (!l) { return '<h3>Selected read</h3><p class="dout-note">Select a line of the map.</p>'; }
    return '<h3>Read ' + esc(l.id) + ' ' + (l.stream ? pill('b', 'stream') : pill('n', 'snapshot')) + '</h3>' +
      '<dl class="dout-dl"><dt>Token · person</dt><dd>' + esc(l.token) + ' · ' + who(l.person, l.onBehalfOf) + '</dd>' +
      '<dt>Reading</dt><dd><span class="dout-req">' + esc(l.form) + ': ' + esc(l.request) + '</span></dd>' +
      '<dt>Node · part</dt><dd>' + esc(l.node) + ' · ' + esc(l.part) + '</dd>' +
      '<dt>Rows · bytes</dt><dd class="mono">' + num(l.rows) + ' · ' + bytes(l.bytes) + '</dd>' +
      (l.stream ? '<dt>Sequence</dt><dd class="mono">' + esc(l.sequence) + '</dd>' : '') +
      '<dt>Arrow memory</dt><dd class="mono">' + bytes(l.memory) + '</dd>' +
      '<dt>Since</dt><dd>' + when(l.started) + '</dd></dl>' +
      '<button type="button" class="btn btn-sm btn-outline-danger" data-dout-stop="' + esc(l.id) + '">Stop</button>';
  }

  // ---------------------------------------------------------------------------------------------------------- view A: the page

  function drawPage() {
    var nodes = data.nodes || [];
    $('[data-dout-nodes-count]').textContent = nodes.length + (nodes.length === 1 ? ' node' : ' in the cluster');
    $('[data-dout-nodes]').innerHTML = nodes.map(nodeCard).join('');
    var mem = (data.service || {}).memory || {};
    var share = mem.cap ? Math.min(100, Math.round(100 * mem.used / mem.cap)) : 0;
    $('[data-dout-gauge]').innerHTML = '<div class="dout-gauge"><div class="t"><b>Arrow memory</b><span class="mono">' + bytes(mem.used) + ' of ' +
      bytes(mem.cap) + ' cap · ' + share + ' %</span></div><div class="track"><div class="fill ' + (share >= 90 ? 'r' : share >= 70 ? 'a' : 'g') +
      '" style="width:' + share + '%"></div></div><div class="dout-sub">busy protection counts it with the rest of the server (Health, arrow-flight)</div></div>';
    var live = data.live || [];
    $('[data-dout-live-count]').textContent = live.length + ' now';
    $('[data-dout-live]').innerHTML = '<thead><tr><th class="l">token · person</th><th class="l">reading</th><th class="l">node</th><th>rows</th><th>bytes</th>' +
      '<th class="l">since</th><th class="l"></th></tr></thead><tbody>' + (live.length ? live.map(function (l) {
        return '<tr class="live"><td class="l">' + esc(l.token) + '<br><span class="dout-sub">' + who(l.person, l.onBehalfOf) + '</span></td>' +
          '<td class="l wr">' + esc(l.form) + (l.stream ? ' ' + pill('b', 'stream') + ' <span class="dout-sub">seq ' + esc(l.sequence) + '</span>' : '') +
          '<span class="dout-req">' + esc(l.request) + '</span></td><td class="l">' + esc(l.node) + '</td><td>' + num(l.rows) + '</td><td>' +
          bytes(l.bytes) + '</td><td class="l">' + when(l.started) + '</td><td class="l"><button type="button" class="btn btn-sm btn-outline-danger" data-dout-stop="' +
          esc(l.id) + '">Stop</button></td></tr>';
      }).join('') : '<tr><td class="l" colspan="7">No live read now.</td></tr>') + '</tbody>';
    var recent = (data.recent || []).slice().sort(function (a, b) { return (b.refusal ? 1 : 0) - (a.refusal ? 1 : 0); });
    $('[data-dout-recent]').innerHTML = '<thead><tr><th class="l">time</th><th class="l">token · person</th><th class="l">read</th><th class="l">node</th>' +
      '<th>rows</th><th>bytes</th><th>time</th><th class="l">outcome</th></tr></thead><tbody>' + (recent.length ? recent.map(function (a) {
        return '<tr' + (a.refusal ? ' class="hot"' : '') + '><td class="l">' + when(a.at) + '</td><td class="l">' + esc(a.tokenId) + '<br><span class="dout-sub">' +
          who(a.person, a.onBehalfOf) + '</span></td><td class="l wr">' + esc(a.form) + '<span class="dout-req">' + esc(a.request) + '</span></td><td class="l">' +
          esc(a.node) + '</td><td>' + num(a.rows) + '</td><td>' + bytes(a.bytes) + '</td><td>' + (Number(a.millis || 0) / 1000).toFixed(1) + ' s</td><td class="l">' +
          (a.refusal ? pill('r', 'refused') + ' <span class="dout-sub">' + esc(a.refusal) + '</span>' : pill('g', 'sent')) + '</td></tr>';
      }).join('') : '<tr><td class="l" colspan="8">No read in the last day.</td></tr>') + '</tbody>';
    var tokens = data.tokens || [];
    $('[data-dout-tokens-count]').textContent = String(tokens.length);
    $('[data-dout-tokens]').innerHTML = '<thead><tr><th class="l">token</th><th class="l">owner</th><th class="l">scopes</th><th class="l">expires</th>' +
      '<th class="l">last used</th></tr></thead><tbody>' + (tokens.length ? tokens.map(function (t) {
        return '<tr><td class="l">' + esc(t.name) + '<br><span class="dout-sub mono">' + esc(t.id) + '</span></td><td class="l">' + esc(t.user) +
          '</td><td class="l">' + esc((t.scopes || []).join(', ')) + '</td><td class="l">' + esc((t.expiresAt || 'never').slice(0, 10)) + '</td><td class="l">' +
          esc((t.lastUsedAt || '').slice(0, 16).replace('T', ' ')) + '</td></tr>';
      }).join('') : '<tr><td class="l" colspan="5">No token has a Flight scope (data:read).</td></tr>') + '</tbody>';
    var lim = data.limits || {};
    var rows = [['every other role', lim['default'] || {}]];
    Object.keys(lim.roles || {}).forEach(function (r) { rows.push([r, lim.roles[r]]); });
    $('[data-dout-limits]').innerHTML = '<thead><tr><th class="l">role</th><th>rows</th><th>bytes</th><th>time</th><th>Arrow memory</th></tr></thead><tbody>' +
      rows.map(function (r) {
        return '<tr><td class="l">' + esc(r[0]) + '</td><td>' + limit(r[1].rows) + '</td><td>' + limit(r[1].bytes, 'bytes') + '</td><td>' +
          limit(r[1].seconds, 's') + '</td><td>' + limit(r[1].memory, 'bytes') + '</td></tr>';
      }).join('') + '</tbody>';
  }

  // ---------------------------------------------------------------------------------------------------------- view B: the map

  var SVG = 'http://www.w3.org/2000/svg';

  function el(name, attrs, text) {
    var e = document.createElementNS(SVG, name);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text != null) { e.textContent = text; }
    return e;
  }

  function drawMap() {
    var svg = $('[data-dout-map]');
    var live = data.live || [];
    var nodes = data.nodes || [];
    var clients = [];
    live.forEach(function (l) {
      var key = l.token + ' · ' + (l.onBehalfOf || l.person);
      if (clients.indexOf(key) < 0) { clients.push(key); }
    });
    $('[data-dout-map-empty]').hidden = live.length > 0;
    var rows = Math.max(clients.length, nodes.length, 1);
    var h = 30 + rows * 80;
    svg.setAttribute('viewBox', '0 0 660 ' + h);
    while (svg.firstChild) { svg.removeChild(svg.firstChild); }
    var nodeY = {};
    nodes.forEach(function (n, i) {
      var y = 20 + i * 80;
      nodeY[n.id] = y + 30;
      svg.appendChild(el('rect', {'class': 'box' + (n.self ? ' self' : ''), x: 430, y: y, width: 210, height: 60, rx: 8}));
      svg.appendChild(el('text', {x: 442, y: y + 22}, n.id + (n.self ? ' · this node' : '')));
      svg.appendChild(el('text', {'class': 'm', x: 442, y: y + 42}, n.host + ':' + n.port));
    });
    var clientY = {};
    clients.forEach(function (c, i) {
      var y = 26 + i * 80;
      clientY[c] = y + 17;
      svg.appendChild(el('rect', {'class': 'cl', x: 10, y: y, width: 200, height: 34, rx: 17}));
      svg.appendChild(el('text', {x: 24, y: y + 21}, c.length > 30 ? c.slice(0, 29) + '…' : c));
    });
    var maxBytes = Math.max.apply(null, live.map(function (l) { return Number(l.bytes || 0); }).concat([1]));
    live.forEach(function (l) {
      var y1 = clientY[l.token + ' · ' + (l.onBehalfOf || l.person)];
      var y2 = nodeY[l.node] != null ? nodeY[l.node] : nodeY[(nodes[0] || {}).id];
      if (y1 == null || y2 == null) { return; }
      var w = 2 + Math.round(6 * Number(l.bytes || 0) / maxBytes);
      var p = el('path', {'class': 'lns' + (l.id === selected ? ' sel' : '') + (l.stream ? ' stream' : ''), d: 'M210 ' + y1 + ' C320 ' + y1 + ' 320 ' + y2 + ' 430 ' + y2,
        'stroke-width': w, tabindex: 0, role: 'button', 'aria-label': 'Read ' + l.id + ': ' + l.form + ' by ' + (l.onBehalfOf || l.person) + ' on ' + l.node,
        'data-dout-line': l.id});
      p.appendChild(el('title', {}, l.form + ' · ' + num(l.rows) + ' rows · ' + bytes(l.bytes)));
      svg.appendChild(p);
    });
    var sel = live.filter(function (l) { return l.id === selected; })[0];
    $('[data-dout-detail]').innerHTML = detail(sel);
  }

  // ---------------------------------------------------------------------------------------------------------- the screen

  function draw() {
    drawService();
    if (view === 'map') { drawMap(); } else { drawPage(); }
  }

  function show(v) {
    view = v;
    root.querySelectorAll('[data-dout-pane]').forEach(function (p) { p.hidden = p.getAttribute('data-dout-pane') !== v; });
    root.querySelectorAll('[data-dout-switch]').forEach(function (b) { b.setAttribute('aria-checked', String(b.getAttribute('data-dout-switch') === v)); });
    var url = new URL(location.href);
    if (v === 'map') { url.searchParams.set('view', 'map'); } else { url.searchParams.delete('view'); }
    history.replaceState(null, '', url.toString());
    draw();
  }

  function message(sel, text) {
    var m = $(sel);
    m.textContent = text;
    m.hidden = !text;
  }

  function refresh() {
    fetch('/admin/api/data-out', {headers: {Accept: 'application/json'}, credentials: 'same-origin'})
      .then(function (r) { return r.json().then(function (j) { return {ok: r.ok, j: j}; }); })
      .then(function (res) {
        if (!res.ok) { message('[data-dout-error]', (res.j.code ? res.j.code + ' ' : '') + (res.j.detail || 'the server did not answer')); return; }
        message('[data-dout-error]', '');
        data = res.j;
        draw();
      })
      .catch(function () { message('[data-dout-error]', 'the console could not reach the server; the figures shown are the last ones read'); });
  }

  function stop(id) {
    fetch('/admin/api/data-out/streams/' + encodeURIComponent(id) + '/stop', {method: 'POST', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
      credentials: 'same-origin', body: '{}'})
      .then(function (r) { return r.json().then(function (j) { return {ok: r.ok, j: j}; }); })
      .then(function (res) {
        if (!res.ok) { message('[data-dout-error]', (res.j.code ? res.j.code + ' ' : '') + (res.j.detail || 'not stopped')); return; }
        message('[data-dout-done]', 'Read ' + id + ' stops after its current batch; its client is told DRS-5610.');
        refresh();
      });
  }

  root.addEventListener('click', function (e) {
    var sw = e.target.closest('[data-dout-switch]');
    if (sw) { show(sw.getAttribute('data-dout-switch')); return; }
    var st = e.target.closest('[data-dout-stop]');
    if (st) { stop(st.getAttribute('data-dout-stop')); return; }
    var line = e.target.closest('[data-dout-line]');
    if (line) { selected = line.getAttribute('data-dout-line'); drawMap(); }
  });
  root.addEventListener('keydown', function (e) {
    var line = e.target.closest && e.target.closest('[data-dout-line]');
    if (line && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); selected = line.getAttribute('data-dout-line'); drawMap(); }
  });

  draw();
  setInterval(function () { if (!document.hidden) { refresh(); } }, 5000);
}());
