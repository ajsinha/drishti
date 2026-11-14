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
/* Build -> New pack: the Pack designer, part two: Design (the Sutras, one's YAML beside its live screen on real rows, re-rendered as it is
 * edited), Finalize (the module and its fifteen checks) and Deploy (the one module deploy). Drishti Elements only: Grid, CodeEditor, Select. */
(function () {
  'use strict';
  var D = window.Drishti.PackDesigner;
  if (!D) { return; }
  var E = window.Drishti.elements, I = E.inputs, G = E.grid;
  var S = D.S, root = D.root;
  var $ = function (s) { return root.querySelector(s); };
  var DEBOUNCE = 450;

  // ---- step 4: design -----------------------------------------------------------------------------------------------------------
  var grid = null, editor = null, current = null, silent = false, timer = null, rowSel = null, rendered = 0, seq = 0;

  function sutraOf(name) { return S.analysis.sutras.filter(function (s) { return s.name === name; })[0]; }
  function badge(name) { var c = S.checks[name]; return c ? (c.error ? c.ok + '/' + c.total + ' · ' + c.error + ' failed' : c.ok + '/' + c.total) : '...'; }
  function rowsOf() {
    return S.analysis.sutras.map(function (s) { return { name: s.name, label: s.label + (S.analysis.kinds && Object.keys(S.analysis.kinds).length > 1 ? ' · ' + s.kind : ''), rows: s.count, checks: badge(s.name), note: (s.rare ? 'rare' : '') + (S.edits[s.name] ? ' edited' : '') }; });
  }
  function drawList() {
    var host = $('[data-sutras-grid]'), rows = rowsOf();
    if (grid) { grid.setRows(rows); return; }
    grid = new G.Grid(host, { rows: rows, getRowId: function (r) { return r.name; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'single', checkboxes: false, label: 'The Sutras',
      columns: [{ field: 'label', header: 'Sutra (combination)', width: 230 }, { field: 'rows', header: 'Rows', type: 'number', width: 80, enableValue: false, aggFunc: null },
        { field: 'checks', header: 'Checks', width: 90 }, { field: 'note', header: '', width: 80 }] });
    grid.on('selectionChanged', function (e) { if (e.ids && e.ids.length && e.ids[0] !== current) { choose(e.ids[0]); } });
    host.style.height = Math.max(240, Math.min(rows.length, 18) * 34 + 56) + 'px';
  }
  function choose(name) {
    var s = sutraOf(name);
    if (!s) { return; }
    current = name;
    $('[data-sutra-name]').textContent = s.name;
    $('[data-sutra-rows]').textContent = D.num(s.count) + ' rows' + (s.rare ? ' · rare' : '');
    $('[data-sutra-check]').textContent = badge(name);
    silent = true;
    editor.setValue(S.edits[name] || s.yaml);
    silent = false;
    var host = $('[data-sel-row]');
    host.textContent = '';
    rowSel = new I.Select(host, { options: s.samples.map(function (d, i) { return { value: i, label: 'row ' + (i + 1) + (d && s.kind ? '' : '') }; }), value: 0, label: 'Real row to draw the screen on' });
    rowSel.on('change', function () { render(); });
    render();
    checkOne(name);
  }
  function render() {
    var name = current, id = ++seq, text = editor.getValue(), box = $('[data-live-screen]');
    D.api('POST', D.ws() + '/preview', { sutra: name, yaml: text, sample: rowSel ? Number(rowSel.getValue()) : 0 }).then(function (r) {
      if (id !== seq) { return; }                                              // a later edit is already drawing
      if (!r.ok) {
        box.textContent = '';
        var p = document.createElement('p');
        p.className = 'prob';
        var probs = (r.body.problems || []).map(function (x) { return x.message || x; }).slice(0, 4).join(' ');
        p.textContent = D.why(r) + (probs ? ' ' + probs : '');
        box.appendChild(p);
        return;
      }
      box.innerHTML = r.body.previewHtml || '';                                  // the server's own rendering (its template escapes every value)
      rendered = Date.now();
      $('[data-live-note]').textContent = 'Showing real row ' + (r.body.sample + 1) + ' of ' + r.body.samples + ' drawn by the server from the YAML on the left; it is drawn again as you edit.';
    });
  }
  function checkOne(name) {
    var s = sutraOf(name);
    return D.api('POST', D.ws() + '/check', { sutra: name, yaml: S.edits[name] || s.yaml }).then(function (r) {
      if (!r.ok) { S.checks[name] = { ok: 0, total: 0, error: 1 }; }
      else if (r.body.refused) { S.checks[name] = { ok: 0, total: 0, error: 1 }; }
      else { var c = r.body.counts || {}; S.checks[name] = { ok: c.ok || 0, total: (c.ok || 0) + (c.empty || 0) + (c.error || 0), error: c.error || 0 }; }
      if (name === current) { $('[data-sutra-check]').textContent = badge(name); }
      if (grid) { grid.setRows(rowsOf()); }
    });
  }
  function checkAll() {
    var names = S.analysis.sutras.map(function (s) { return s.name; }), i = 0, run = S.analysis;
    (function next() { if (S.analysis !== run || i >= names.length) { summary(); return; } checkOne(names[i++]).then(next); })();
  }
  function summary() {
    var all = S.analysis ? S.analysis.sutras : [], done = all.filter(function (s) { return S.checks[s.name]; }), pass = done.filter(function (s) { var c = S.checks[s.name]; return !c.error && c.ok === c.total; });
    D.sumOf('design', all.length + ' Sutras' + (done.length === all.length ? ' · ' + pass.length + ' pass' + (all.length - pass.length ? ', ' + (all.length - pass.length) + ' warn' : '') : ''));
  }
  D.on('analysed', function (a) {
    S.checks = {};
    current = null;
    if (!editor) {
      editor = new I.CodeEditor($('[data-yaml]'), { mode: 'yaml', value: '', height: 420, lineWrapping: false, label: 'The Sutra YAML' });
      editor.on('change', function () {
        if (silent || !current) { return; }
        var s = sutraOf(current), text = editor.getValue();
        if (text === s.yaml) { delete S.edits[current]; } else { S.edits[current] = text; }
        window.clearTimeout(timer);
        timer = window.setTimeout(function () { render(); checkOne(current); }, DEBOUNCE);
      });
    }
    if (grid) { grid.setRows([]); }
    drawList();
    D.sumOf('design', a.sutras.length + ' Sutras');
    $('[data-finalize]').disabled = !S.canAuthor;
    window.setTimeout(function () { if (a.sutras.length) { choose(a.sutras[0].name); } checkAll(); }, 0);
  });
  D.on('show', function (step) {
    if (step === 'design' && editor) { editor.refresh(); if (grid) { grid.refresh(); } }
    if (step === 'finalize') { $('[data-finalize]').disabled = !S.analysis || !S.canAuthor; }
    if (step === 'deploy') { $('[data-plan]').disabled = !S.result || !S.isAdmin; $('[data-install]').disabled = !S.result || !S.isAdmin; }
  });

  // ---- step 5: finalize ------------------------------------------------------------------------------------------------------------
  var checksGrid = null;
  function drawChecks(lines) {
    var rows = [];
    lines.forEach(function (l) {
      var m = /^(C\d+) (.+?): (passed|failed.*|skipped.*)$/.exec(l.trim());
      if (m) { rows.push({ id: m[1], check: m[2], result: m[3] }); }
    });
    var host = $('[data-checks-grid]');
    host.hidden = !rows.length;
    if (!rows.length) { return 0; }
    if (checksGrid) { checksGrid.setRows(rows); } else {
      checksGrid = new G.Grid(host, { rows: rows, getRowId: function (r) { return r.id; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none', label: 'The fifteen module checks',
        columns: [{ field: 'id', header: '#', width: 60 }, { field: 'check', header: 'Check', width: 320 }, { field: 'result', header: 'Result', width: 160 }] });
      host.style.height = (rows.length * 34 + 56) + 'px';
    }
    return rows.filter(function (r) { return r.result === 'passed'; }).length;
  }
  function names() {
    var n = D.field('name').value.trim();
    if (!/^[a-z0-9][a-z0-9-]*$/.test(n)) { D.say('Give the pack a name (lower-case letters, digits and "-") in step 3 first.', true); D.show('source'); D.field('name').focus(); return null; }
    return n;
  }
  $('[data-finalize]').addEventListener('click', function () {
    if (!names()) { return; }
    var req = D.choices(), edits = {};
    Object.keys(S.edits).forEach(function (k) { edits[k] = S.edits[k]; });
    req.edits = edits;
    $('[data-finalize]').disabled = true;
    $('[data-download]').hidden = true;
    $('[data-fin-prog]').hidden = false;
    $('[data-fin-said]').textContent = 'starting ...';
    D.api('POST', D.ws() + '/finalize', req).then(function (r) {
      if (!r.ok) { $('[data-fin-prog]').hidden = true; $('[data-finalize]').disabled = false; D.say(D.why(r), true); return; }
      S.busy = true;
      D.poll('finalize');
      tick();
    });
  });
  function tick() {
    if (!S.busy) { return; }
    D.api('GET', D.ws() + '/status').then(function (r) {
      var log = r.body.log || [];
      $('[data-log]').hidden = false;
      $('[data-log]').textContent = log.slice(-60).join('\n');
      var n = drawChecks(log);
      $('[data-fin-said]').textContent = r.body.seconds + ' s' + (n ? ' · ' + n + ' of 15 checks passed so far' : '');
      if (S.busy) { window.setTimeout(tick, 900); }
    });
  }
  D.on('finalised', function (s) {
    S.busy = false;
    $('[data-fin-prog]').hidden = true;
    $('[data-finalize]').disabled = false;
    var log = s.log || [];
    var n = drawChecks(log);
    $('[data-log]').hidden = false;
    $('[data-log]').textContent = log.slice(-80).join('\n');
    if (s.state !== 'done') { D.say('The module was not made: ' + (s.error || 'see the log') + '.', true); D.sumOf('finalize', 'failed'); return; }
    D.api('GET', D.ws()).then(function (r) {
      S.result = r.body.result;
      var a = $('[data-download]');
      a.href = D.ws() + '/dmod';
      a.hidden = false;
      a.textContent = 'Download ' + S.result.dmod + ' (' + D.bytes(S.result.bytes) + ')';
      D.sumOf('finalize', n + ' of 15 checks passed');
      D.sumOf('deploy', 'ready: ' + S.result.name + ' ' + S.result.version);
      $('[data-plan]').disabled = !S.isAdmin;
      $('[data-install]').disabled = !S.isAdmin;
      D.say('The module ' + S.result.name + ' ' + S.result.version + ' is made: ' + n + ' of 15 checks passed.');
    });
  });

  // ---- step 6: deploy ---------------------------------------------------------------------------------------------------------------
  var planGrid = null;
  function drawPlan(plan) {
    var rows = [];
    var nodes = plan.nodes || plan.placement || plan.steps || [];
    (Array.isArray(nodes) ? nodes : Object.keys(nodes).map(function (k) { return Object.assign({ node: k }, nodes[k]); })).forEach(function (n) {
      rows.push({ node: String(n.node || n.id || n.name || 'this node'), action: String(n.action || plan.action || 'install'), state: String(n.state || n.status || plan.state || 'planned') });
    });
    if (!rows.length) { rows.push({ node: 'this node', action: plan.action || 'install', state: plan.state || 'planned' }); }
    var host = $('[data-plan-grid]');
    host.hidden = false;
    if (planGrid) { planGrid.setRows(rows); return; }
    planGrid = new G.Grid(host, { rows: rows, getRowId: function (r) { return r.node; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none', label: 'The plan per node',
      columns: [{ field: 'node', header: 'Node', width: 260 }, { field: 'action', header: 'Action', width: 120 }, { field: 'state', header: 'State', width: 200 }] });
    host.style.height = '140px';
  }
  function openLink() {
    var first = S.analysis.sutras[0], key = S.analysis.kinds[first.kind].key, id = first.samples[0] || {};
    key.split('.').forEach(function (k) { id = id && id[k]; });
    var a = $('[data-open]');
    a.href = '/m/' + encodeURIComponent(S.result.name) + '/' + encodeURIComponent(S.result.name) + '/' + encodeURIComponent(first.kind) + '/' + encodeURIComponent(id);
    a.textContent = 'Open the screen of ' + id;
    a.hidden = false;
  }
  var dmsg = $('[data-deploy-msg]');
  function dsay(t, bad) { dmsg.textContent = t || ''; dmsg.classList.toggle('bad', !!bad); }
  $('[data-install]').addEventListener('click', function () {
    $('[data-install]').disabled = true;
    dsay('Putting the module in the modules folder ...');
    D.api('POST', D.ws() + '/deploy/install', {}).then(function (r) {
      $('[data-install]').disabled = false;
      if (!r.ok) { dsay(D.why(r), true); return; }
      D.sumOf('deploy', 'installed on this node');
      openLink();
      dsay(S.result.name + ' is installed: ' + r.body.installed + '. Auto-deploy checks and loads it within seconds (Admin → Modules shows it).');
    });
  });
  $('[data-plan]').addEventListener('click', function () {
    $('[data-plan]').disabled = true;
    dsay('Uploading the module to the store and planning the install ...');
    D.api('POST', D.ws() + '/deploy/plan', { reason: 'Pack designer: ' + S.result.name + ' ' + S.result.version }).then(function (r) {
      $('[data-plan]').disabled = false;
      if (!r.ok) { dsay(D.why(r), true); return; }
      S.plan = r.body.plan;
      drawPlan(S.plan);
      $('[data-apply]').hidden = false;
      D.sumOf('deploy', 'planned: ' + (S.plan.id || ''));
      dsay('Planned ' + (S.plan.id || '') + '. Apply it to install ' + S.result.name + '.');
    });
  });
  $('[data-apply]').addEventListener('click', function () {
    $('[data-apply]').disabled = true;
    dsay('Applying ...');
    D.api('POST', D.ws() + '/deploy/apply', { id: S.plan.id, comment: 'Pack designer' }).then(function (r) {
      $('[data-apply]').disabled = false;
      if (!r.ok) { dsay(D.why(r), true); return; }
      S.plan = r.body;
      drawPlan(S.plan);
      D.sumOf('deploy', 'applied');
      dsay(S.result.name + ' is deployed (' + (S.plan.state || 'applied') + ').');
      openLink();
    });
  });
  D.on('kind', function () { summary(); });
})();
