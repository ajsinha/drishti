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
/* Admin -> Engines (templates/admin/engines.html; docs/guides/ADMIN_GUIDE.md section 8, mock-up A). Flipping a switch only stages
   the change; the side panel shows the dependency check (refused changes say why and offer "Turn on both" / "Turn off all three"), what
   disappears for people (from the catalogue), what happens on the server (live, drain up to 30 s, or restart needed) and asks for the
   reason. Apply sends one batch; the server writes each engine's file (refusing a file changed by hand since the page read it) and the
   page draws again from its answer. The Cluster tab is the nodes x engines matrix (mock-up C); a cell stages a change on that node. */
(function () {
  'use strict';
  var root = document.querySelector('[data-engines]');
  if (!root) { return; }
  var data = JSON.parse(root.querySelector('[data-eng-json]').getAttribute('data-eng-json') || '{}');
  var view = data.engines || {};
  var history = data.history || [];
  var staged = [];           // [{engine, on, node}] in the order flipped
  var withNeeds = false;
  var withDependents = false;
  var $ = function (sel) { return root.querySelector(sel); };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; });
  }
  function mb(bytes) {
    var n = Number(bytes || 0);
    if (n <= 0) { return '-'; }
    if (n >= 1 << 30) { return window.Drishti.elements.core.formatBytes(n); }
    return Math.max(1, Math.round(n / (1 << 20))) + ' MB';
  }
  function byId(id) { return (view.engines || []).filter(function (e) { return e.id === id; })[0]; }
  function title(id) { var e = byId(id); return e ? e.title : id; }
  function list(ids) {
    var t = ids.map(title);
    return t.length <= 1 ? t.join('') : t.slice(0, -1).join(', ') + ' and ' + t[t.length - 1];
  }
  function pill(state) {
    var cls = {running: 'g', off: 'n', blocked: 'r', starting: 'a', stopping: 'a', 'not installed': 'n'}[state] || 'n';
    return '<span class="eng-pill ' + cls + '">' + esc(state) + '</span>';
  }
  function stagedFor(id, node) {
    return staged.filter(function (s) { return s.engine === id && (s.node || '') === (node || ''); })[0];
  }
  function effectiveOn(e) {
    var s = stagedFor(e.id, '');
    return s ? s.on : !!e.on;
  }

  // ---------------------------------------------------------------------------------------------------------- this server
  function drawServer() {
    var body = $('[data-eng-table] tbody');
    var heap = Number(view.heapMaxBytes || 0) || 1;
    body.innerHTML = (view.engines || []).map(function (e) {
      var s = stagedFor(e.id, '');
      var on = effectiveOn(e);
      var installed = e.installed;
      var share = Math.min(100, Math.round(100 * Number(e.memoryBytes || 0) / heap));
      var state = pill(e.state) + (e.restartNeeded ? ' ' + pill('restart needed').replace('eng-pill n', 'eng-pill a') : '')
        + (s ? ' <span class="eng-pill a">' + (s.on ? 'on' : 'off') + ' staged</span>' : '')
        + (e.reason ? ' <span class="eng-muted" title="' + esc(e.reason) + '">' + esc(e.reason) + '</span>' : '');
      var source = esc(e.source) + (e.alias ? '<br><span class="eng-warn">old name ' + esc(e.alias) + ' (DRS-1024)</span>' : '');
      return '<tr data-engine="' + esc(e.id) + '"' + (s ? ' class="staged"' : '') + (installed ? '' : ' class="eng-dim"') + '>'
        + '<td class="l"><b>' + esc(e.title) + '</b> <span class="eng-id">' + esc(e.id) + '</span></td>'
        + '<td class="l">' + (installed
          ? '<button type="button" role="switch" class="eng-sw" aria-checked="' + on + '" aria-label="' + esc(e.title) + ' on this server" data-eng-switch="' + esc(e.id) + '"><span></span></button> '
            + '<span class="eng-sw-l">' + (on ? 'on' : 'off') + '</span>'
          : '<span class="eng-muted">not installed</span>') + '</td>'
        + '<td class="l eng-state">' + state + '</td>'
        + '<td class="l">' + (e.answering === false ? '<span class="eng-muted" title="its figures did not come within the page\'s time">not answering</span>'
          : '<span class="eng-bar" aria-hidden="true"><i data-w="' + share + '"></i></span> ' + mb(e.memoryBytes)) + '</td>'
        + '<td>' + (e.threads || (e.state === 'running' ? 0 : '-')) + '</td>'
        + '<td class="l eng-wrap">' + (e.needs.length ? esc(e.needs.join(', ')) : '-') + '</td>'
        + '<td class="l eng-wrap">' + esc(e.neededBy.concat(e.usedBy.map(function (u) { return u + ' (uses)'; })).join(', ') || '-') + '</td>'
        + '<td class="l eng-src">' + source + '</td></tr>';
    }).join('');
    body.querySelectorAll('.eng-bar i[data-w]').forEach(function (i) { i.style.width = i.getAttribute('data-w') + '%'; });   // the CSP forbids inline style attributes
    var notes = [];
    (view.engines || []).forEach(function (e) {
      if (e.restartNeeded) { notes.push(e.title + ' was turned on (' + e.restartNeeded + '): written to ' + e.file + '; it starts at the next restart.'); }
      if (e.overridden && e.installed) { notes.push(e.title + "'s switch comes from " + e.source + ': a change here is written to the file but ' + e.source + ' still wins until it is removed.'); }
    });
    $('[data-eng-notes]').textContent = notes.join(' ');
    var running = (view.engines || []).filter(function (e) { return e.state === 'running'; }).length;
    if (!view.engines) { $('[data-eng-meta]').textContent = asking; return; }
    $('[data-eng-meta]').textContent = 'this server' + (view.node && view.node !== 'this server' ? ' · ' + view.node : '') + ' · ' + running
      + ' of ' + (view.installed || 0) + ' engines running · ' + (Number(view.memoryBytes) > 0 ? mb(view.memoryBytes) : 'nothing') + ' held by engines'
      + ' · files in ' + (view.dir || '');
  }

  // ---------------------------------------------------------------------------------------------------------- the cluster (C)
  function drawCluster() {
    var c = view.cluster || {};
    var peers = c.peers || {};
    var nodes = Object.keys(peers);
    var tab = $('[data-eng-cluster-tab]');
    tab.textContent = c.on ? 'Cluster (' + (nodes.length + 1) + ' nodes)' : 'Cluster';
    var head = '<thead><tr><th class="l">Engine</th><th class="l">' + esc(c.self || view.node || 'this server') + ' (this one)</th>'
      + nodes.map(function (n) { return '<th class="l">' + esc(n) + '</th>'; }).join('') + '<th class="l">Available to people</th></tr></thead>';
    var rows = (view.engines || []).map(function (e) {
      var cells = '<td class="l">' + pill(e.state) + '</td>' + nodes.map(function (n) {
        var st = (peers[n] || {})[e.id] || 'not installed';
        var s = stagedFor(e.id, n);
        var on = s ? s.on : st === 'running';
        return '<td class="l">' + (st === 'not-installed' || st === 'not installed' ? '<span class="eng-muted">-</span>'
          : '<button type="button" role="switch" class="eng-sw" aria-checked="' + on + '" aria-label="' + esc(e.title) + ' on ' + esc(n)
            + '" data-eng-switch="' + esc(e.id) + '" data-eng-node="' + esc(n) + '"><span></span></button> ' + pill(st.replace('-', ' '))
            + (s ? ' <span class="eng-pill a">staged</span>' : '')) + '</td>';
      }).join('');
      var avail = (c.available || {})[e.id];
      return '<tr><td class="l"><b>' + esc(e.title) + '</b> <span class="eng-id">' + esc(e.id) + '</span></td>' + cells
        + '<td class="l">' + (avail ? '<span class="eng-pill g">available</span>' : '<span class="eng-pill n">nowhere</span>') + '</td></tr>';
    }).join('');
    $('[data-eng-matrix]').innerHTML = head + '<tbody>' + rows + '</tbody>';
  }

  // ---------------------------------------------------------------------------------------------------------- History
  function drawHistory() {
    $('[data-eng-history] tbody').innerHTML = history.length ? history.map(function (h) {
      var cls = h.outcome === 'refused' ? 'r' : h.outcome === 'live' ? 'g' : 'a';
      return '<tr><td class="l">' + esc(String(h.at || '').replace('T', ' ').slice(0, 16)) + '</td><td class="l">' + esc(title(h.engine)) + '</td>'
        + '<td class="l">' + esc(h.change) + '</td><td class="l">' + esc(h.who || '-') + '</td><td class="l">' + esc(h.why || '-') + '</td>'
        + '<td class="l">' + esc(h.node) + '</td><td class="l"><span class="eng-pill ' + cls + '">' + esc(h.outcome) + '</span> '
        + (h.outcome === 'refused' || h.outcome === 'written' ? '<span class="eng-muted">' + esc(h.detail) + '</span>' : '') + '</td></tr>';
    }).join('') : '<tr><td class="l eng-muted" colspan="7">No change yet.</td></tr>';
  }

  // ---------------------------------------------------------------------------------------------------------- the review panel
  /** What the staged batch would leave on (this node), and the refusals in the server's words. */
  function check() {
    var local = staged.filter(function (s) { return !s.node; });
    var on = {};
    (view.engines || []).forEach(function (e) { on[e.id] = e.state === 'running' || e.state === 'starting'; });
    local.forEach(function (s) { on[s.engine] = s.on; });
    var refusals = [];
    local.forEach(function (s) {
      var e = byId(s.engine);
      if (s.on) {
        var missing = e.needs.filter(function (n) { return !on[n]; });
        if (missing.length) {
          refusals.push({text: e.title + ' needs ' + list(missing) + ' on this server. Turn on ' + list(missing) + ' first, or '
            + (missing.length === 1 ? 'both' : 'all of them') + ' together.', fix: 'needs', label: 'Turn on ' + (missing.length === 1 ? 'both' : 'all of them')});
        }
      } else {
        var users = e.neededBy.filter(function (u) { return on[u]; });
        if (users.length) {
          var n = users.length + 1;
          refusals.push({text: e.title + ' is needed by ' + list(users) + ' on this server. Turn ' + (users.length === 1 ? 'it' : 'them')
            + ' off first, or ' + (n === 2 ? 'both' : 'all ' + (n === 3 ? 'three' : n)) + ' together.', fix: 'dependents',
            label: 'Turn off ' + (n === 2 ? 'both' : 'all ' + (n === 3 ? 'three' : n))});
        }
      }
    });
    return refusals;
  }

  var RF = window.Drishti.elements.inputs.formOf($('[data-field-of=reason]'));     // the reason: drishti-inputs' Form (core/admin_forms.py)
  function drawPanel() {
    var panel = $('[data-eng-panel]');
    if (!staged.length) { panel.hidden = true; return; }
    panel.hidden = false;
    var first = staged[0];
    var node = first.node || view.node || 'this server';
    $('[data-eng-panel-title]').textContent = staged.length === 1
      ? 'Turn ' + title(first.engine) + ' ' + (first.on ? 'on' : 'off') + ' on ' + node
      : staged.length + ' changes on ' + node;
    $('[data-eng-staged-count]').textContent = staged.length + ' change' + (staged.length === 1 ? '' : 's') + ' staged';
    var refusals = withNeeds || withDependents ? [] : check();
    var deps = [];
    staged.forEach(function (s) {
      var e = byId(s.engine);
      if (!s.on) {
        if (!e.neededBy.length) { deps.push('<p class="eng-ok">✓ Nothing on this server needs ' + esc(e.title) + '.</p>'); }
        e.usedBy.forEach(function (u) { deps.push('<p class="eng-warnline">• ' + esc(title(u)) + ' <b>uses</b> it: its parts that read ' + esc(e.title) + ' will be hidden.</p>'); });
        if (e.needs.length) { deps.push('<p class="eng-muted">' + esc(list(e.needs)) + ' stay on (' + esc(e.title) + ' needs them, not the other way).</p>'); }
      } else if (e.needs.length) {
        deps.push('<p class="eng-muted">' + esc(e.title) + ' needs ' + esc(list(e.needs)) + ' on this server.</p>');
      }
    });
    if (withNeeds) { deps.push('<p class="eng-ok">✓ What they need is turned on with them.</p>'); }
    if (withDependents) { deps.push('<p class="eng-ok">✓ The engines that need them are turned off with them.</p>'); }
    refusals.forEach(function (r) {
      deps.push('<p class="eng-bad" data-eng-refusal>✗ ' + esc(r.text) + ' (DRS-1021) <button type="button" class="btn-pill btn-ghost eng-fix" data-eng-fix="' + r.fix + '">' + esc(r.label) + '</button></p>');
    });
    $('[data-eng-deps]').innerHTML = deps.join('');
    var hides = [];
    var saved = '';
    staged.filter(function (s) { return !s.on; }).forEach(function (s) {
      var e = byId(s.engine);
      Object.keys(e.hides || {}).sort(function (a, b) { return (a === 'words' ? 0 : 1) - (b === 'words' ? 0 : 1); }).forEach(function (k) {
        (e.hides[k] || []).forEach(function (v) { hides.push('<span class="eng-chip">' + esc(k === 'words' ? v : k + ': ' + v) + '</span>'); });
      });
      var peers = (view.cluster || {}).peers || {};
      var others = Object.keys(peers).filter(function (n) { return n !== s.node && (peers[n] || {})[s.engine] === 'running'; });
      if (s.node && e.state === 'running') { others.push(view.node || 'this server'); }
      saved += '<p class="eng-muted">' + (others.length
        ? esc(list(others)) + ' still run' + (others.length === 1 ? 's' : '') + ' ' + esc(e.title) + ': people keep it, served from there.'
        : 'Nowhere else in the cluster runs ' + esc(e.title) + ', so these disappear for everyone.') + '</p>';
    });
    staged.filter(function (s) { return s.on; }).forEach(function (s) { hides.push('<span class="eng-chip g">' + esc(title(s.engine)) + ' comes back</span>'); });
    $('[data-eng-hides]').innerHTML = (hides.join(' ') || '<p class="eng-muted">Nothing.</p>') + saved;
    var effect = staged.map(function (s) {
      var e = byId(s.engine);
      if (s.on && !e.liveOn) { return '<p><span class="eng-pill a">restart needed</span> ' + esc(e.title) + ' is written to its file and starts at the next restart (its direct memory is set at start).</p>'; }
      if (s.on) { return '<p>Live, no restart: ' + esc(e.title) + ' starts; its words come back at once.</p>'; }
      return '<p>Live, no restart: new requests answer "not running"; requests in flight finish (up to 30 s); '
        + (Number(e.memoryBytes) > 0 ? mb(e.memoryBytes) : 'what it holds') + ' and ' + (e.threads || 0) + ' thread' + (e.threads === 1 ? ' is' : 's are')
        + ' released.</p>';
    }).join('');
    $('[data-eng-effect]').innerHTML = effect;
    $('[data-eng-writes]').textContent = 'writes ' + staged.map(function (s) { var e = byId(s.engine); return s.node ? s.node + ': config/engines/' + s.engine + '.yaml' : e.file; }).join(', ');
    var apply = $('[data-eng-apply]');
    apply.textContent = staged.length === 1 ? 'Turn ' + title(first.engine) + ' ' + (first.on ? 'on' : 'off') : 'Apply ' + staged.length + ' changes';
    apply.disabled = refusals.length > 0 || !String(RF.getField('reason') || '').trim() || nodesOf().length > 1;
    if (nodesOf().length > 1) { $('[data-eng-deps]').insertAdjacentHTML('beforeend', '<p class="eng-bad">Apply the changes of one node at a time.</p>'); }
  }

  function nodesOf() {
    var n = {};
    staged.forEach(function (s) { n[s.node || ''] = true; });
    return Object.keys(n);
  }

  function drawAll() { drawServer(); drawCluster(); drawHistory(); drawPanel(); }

  function stage(id, node, on) {
    var s = stagedFor(id, node);
    if (s) {
      staged.splice(staged.indexOf(s), 1);            // flipped back: no longer a change
    } else {
      staged.push({engine: id, on: on, node: node || ''});
    }
    withNeeds = false;
    withDependents = false;
    drawAll();
  }

  root.addEventListener('click', function (ev) {
    var sw = ev.target.closest('[data-eng-switch]');
    if (sw) {
      stage(sw.getAttribute('data-eng-switch'), sw.getAttribute('data-eng-node') || '', sw.getAttribute('aria-checked') !== 'true');
      return;
    }
    var tab = ev.target.closest('[data-eng-tab]');
    if (tab) {
      var t = tab.getAttribute('data-eng-tab');
      root.querySelectorAll('[data-eng-tab]').forEach(function (b) { b.setAttribute('aria-selected', String(b === tab)); });
      root.querySelector('[data-eng-view="server"]').hidden = t !== 'server';
      root.querySelector('[data-eng-view="cluster"]').hidden = t !== 'cluster';
      root.querySelector('[data-eng-history-card]').hidden = t === 'cluster';
      return;
    }
    var fix = ev.target.closest('[data-eng-fix]');
    if (fix) {
      if (fix.getAttribute('data-eng-fix') === 'needs') { withNeeds = true; } else { withDependents = true; }
      drawPanel();
      return;
    }
    if (ev.target.closest('[data-eng-undo]') || ev.target.closest('[data-eng-cancel]')) {
      staged = [];
      withNeeds = withDependents = false;
      $('[data-eng-error]').hidden = true;
      drawAll();
      return;
    }
    if (ev.target.closest('[data-eng-apply]')) { apply(); }
  });
  RF.on('change', drawPanel);

  function apply() {
    var reason = String(RF.getField('reason') || '').trim();
    var node = staged[0].node || '';
    var expected = {};
    if (!node) { staged.forEach(function (s) { var e = byId(s.engine); expected[s.engine] = e.checksum || ''; }); }
    var body = {reason: reason, node: node, withNeeds: withNeeds, withDependents: withDependents, expected: node ? null : expected,
      changes: staged.map(function (s) { return {engine: s.engine, on: s.on}; })};
    var btn = $('[data-eng-apply]');
    btn.disabled = true;
    $('[data-eng-done]').hidden = true;
    $('[data-eng-error]').hidden = true;
    fetch('/admin/api/engines', {method: 'POST', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
      credentials: 'same-origin', body: JSON.stringify(body)})
      .then(function (r) { return r.json().then(function (j) { return {ok: r.ok, j: j}; }); })
      .then(function (res) {
        var err = $('[data-eng-error]');
        if (!res.ok) {
          err.hidden = false;
          err.textContent = String((res.j && res.j.detail) || 'The server refused the change') + (res.j && res.j.code ? ' (' + res.j.code + ')' : '');
          btn.disabled = false;
          return refresh();
        }
        err.hidden = true;
        var out = (res.j.outcomes || []).map(function (o) { return title(o.engine) + ' ' + (o.on ? 'on' : 'off') + ': ' + o.outcome; }).join('; ');
        if (!res.j.engines && res.j.forwardedTo) { out = 'sent to ' + res.j.forwardedTo + ': ' + out; }
        staged = [];
        RF.setValue({ reason: '' });
        return refresh().then(function () {                 // said once the list and History show the change
          var done = $('[data-eng-done]');
          done.hidden = false;
          done.textContent = out;
        });
      })
      .catch(function (e) { $('[data-eng-error]').hidden = false; $('[data-eng-error]').textContent = String(e); btn.disabled = false; });
  }

  // one answer within its own time: a server that does not answer is said in words (never a page held open)
  function ask(url, ms) {
    var ctl = window.AbortController ? new AbortController() : null;
    var t = ctl ? setTimeout(function () { ctl.abort(); }, ms) : 0;
    return fetch(url, {headers: {Accept: 'application/json'}, credentials: 'same-origin', signal: ctl ? ctl.signal : undefined})
      .then(function (r) { clearTimeout(t); return r.ok ? r.json() : null; })
      .catch(function () { clearTimeout(t); return null; });
  }
  var asking = 'asking the server…';
  function refresh() {
    var engines = ask('/admin/api/engines', 15000).then(function (j) {     // each answer drawn as it arrives
      if (j && j.engines) { view = j; asking = ''; } else if (!view.engines) { asking = 'the server is not answering: reload to ask again'; }
      drawAll();
    });
    var hist = ask('/admin/api/engines/history?limit=50', 15000).then(function (j) { if (j && j.changes) { history = j.changes; drawHistory(); } });
    return Promise.all([engines, hist]);
  }

  drawAll();
  if (data.pending) { refresh(); }                          // the page was drawn before an answer came: ask for it now
})();
