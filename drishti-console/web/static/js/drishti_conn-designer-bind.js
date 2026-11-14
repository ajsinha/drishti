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
/* Build -> New connector, part two (part one: drishti_conn-designer.js): step 5, Bind to kinds (read / union / live / write per kind, the resolution now and
 * after, the functions that come on), and step 6, Review and apply: the diff of the connector file and of bindings.yaml (drishti-inputs' CodeDiff), then the one
 * bindings path: the connector file is saved through Admin -> Connectors' API and each kind's binding is PROPOSED to the bindings service (POST
 * /admin/api/bindings/propose); a second administrator approves it there (four eyes, audited), and it applies without a restart. */
(function () {
  'use strict';
  var D = window.Drishti.ConnDesigner;
  if (!D) { return; }
  var E = window.Drishti.elements, I = E.inputs, G = E.grid;
  var S = D.S, root = D.root;
  var $ = function (s) { return root.querySelector(s); };
  var MODES = ['read', 'union', 'live', 'write'];
  var binds = {};                                   // kind -> {mode, lake, table}
  var current = null, bindGrid = null, funcGrid = null, healthGrid = null, selMode = null, fileDiff = null, bindDiff = null, poll = null, mine = [];

  function status() { return (S.ctx && S.ctx.bindings) || { kinds: [], off: {} }; }
  function kindOf(kind) { return (status().kinds || []).filter(function (k) { return k.kind === kind; })[0] || null; }
  function flow(kind, b) {
    var parts = [];
    if (b.read && b.read.length) { parts.push('read: [' + b.read.join(', ') + ']'); }
    if (b.union) { parts.push('union: true'); }
    if (b.live) { parts.push('live: ' + b.live); }
    if (b.write) { parts.push('write: {lake: ' + b.write.lake + ', table: ' + b.write.table + '}'); }
    if (b.demo) { parts.push('demo: true'); }
    return kind + ': {' + parts.join(', ') + '}';
  }
  /** The binding this connector gives a kind in the chosen mode, on top of the kind's site binding now. */
  function bindingFor(kind) {
    var k = kindOf(kind), was = k && k.binding ? JSON.parse(JSON.stringify(k.binding)) : {}, b = binds[kind] || { mode: 'read' }, name = S.name;
    delete was.demo;                                                          // a real source and demo data are never bound together
    if (b.mode === 'read') { was.read = [name]; delete was.union; }
    else if (b.mode === 'union') { was.read = (was.read || []).filter(function (x) { return x !== name; }).concat([name]); was.union = true; }
    else if (b.mode === 'live') { was.live = name; }
    else if (b.mode === 'write') { was.write = { lake: b.lake || '', table: b.table || '' }; }
    return was;
  }
  function describe(b) {
    var parts = [];
    if (b.read && b.read.length) { parts.push(b.read.join(' + ') + (b.union ? ' (union)' : '')); }
    if (b.live) { parts.push(b.live + ' (live)'); }
    if (b.write) { parts.push('writes ' + b.write.lake + '/' + b.write.table); }
    return parts.join(' · ') || 'unbound';
  }
  function nowText(kind) {
    var k = kindOf(kind);
    if (!k) { return 'unbound'; }
    return (k.state || 'UNBOUND').toLowerCase() + (k.read && k.read.length ? ' · ' + k.read.join(' + ') : '');
  }

  // ---- step 5: bind to kinds ------------------------------------------------------------------------------------------------------------------
  function rows() {
    return S.kinds.map(function (kind) {
      var b = binds[kind] = binds[kind] || { mode: 'read', lake: '', table: '' };
      return { kind: kind, mode: b.mode, now: nowText(kind), after: describe(bindingFor(kind)) };
    });
  }
  function drawBinds() {
    var rs = rows(), host = $('[data-binds-grid]');
    if (!bindGrid) {
      bindGrid = new G.Grid(host, { rows: rs, getRowId: function (r) { return r.kind; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'single', checkboxes: false, label: 'Kinds to bind',
        columns: [{ field: 'kind', header: 'Kind', width: 180, cellClass: 'mono' }, { field: 'mode', header: 'Mode', width: 90 }, { field: 'now', header: 'Resolution now', width: 300 },
          { field: 'after', header: 'After', width: 330 }] });
      bindGrid.on('selectionChanged', function (e) { if (e.ids && e.ids.length) { pick(e.ids[0]); } });
    } else { bindGrid.setRows(rs); }
    host.style.height = (Math.min(Math.max(rs.length, 1), 10) * 34 + 56) + 'px';
    drawFuncs();
    D.sumOf('bind', rs.length + ' kind' + (rs.length === 1 ? '' : 's') + ' · ' + rs.map(function (r) { return r.mode; }).filter(function (m, i, a) { return a.indexOf(m) === i; }).join(', '));
  }
  function pick(kind) {
    current = kind;
    var b = binds[kind], form = $('[data-bind-form]');
    form.hidden = false;
    $('[data-bind-kind]').textContent = kind;
    var host = $('[data-sel-mode]');
    host.textContent = '';
    selMode = new I.Select(host, { options: MODES.map(function (m) { return { value: m, label: m }; }), value: b.mode, label: 'Mode for ' + kind });
    selMode.on('change', function (e) { b.mode = e.value; writeFields(); drawBinds(); });
    D.field('writeLake').value = b.lake || '';
    D.field('writeTable').value = b.table || '';
    writeFields();
  }
  function writeFields() {
    var w = current && binds[current] && binds[current].mode === 'write';
    Array.prototype.forEach.call(root.querySelectorAll('[data-write-only]'), function (n) { n.hidden = !w; });
  }
  ['writeLake', 'writeTable'].forEach(function (f) {
    D.field(f).addEventListener('input', function () {
      if (!current) { return; }
      binds[current].lake = D.field('writeLake').value.trim();
      binds[current].table = D.field('writeTable').value.trim();
      drawBinds();
    });
  });
  /** The functions an unserved need switched off: each comes on when a kind its reason names is bound here. */
  function drawFuncs() {
    var off = status().off || {}, out = [];
    Object.keys(off).forEach(function (f) {
      var hit = S.kinds.filter(function (k) { return String(off[f]).indexOf(k) >= 0 && binds[k] && binds[k].mode !== 'write'; });
      if (hit.length) { out.push({ fn: f, needs: hit.join(', '), result: 'comes on', note: off[f] }); }
    });
    S.kinds.forEach(function (k) {
      var ki = kindOf(k);
      ((ki && ki.usedBy) || []).forEach(function (f) { if (!off[f] && !out.some(function (x) { return x.fn === f; })) { out.push({ fn: f, needs: k, result: 'on already', note: '' }); } });
    });
    $('[data-funcs-grid]').hidden = !out.length;
    $('[data-funcs-none]').hidden = out.length > 0;
    if (!out.length) { return; }
    var host = $('[data-funcs-grid]');
    if (funcGrid) { funcGrid.setRows(out); } else {
      funcGrid = new G.Grid(host, { rows: out, getRowId: function (r) { return r.fn; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none', label: 'Functions that come on',
        columns: [{ field: 'fn', header: 'Function', width: 120, cellClass: 'mono' }, { field: 'needs', header: 'Needs', width: 200, cellClass: 'mono' }, { field: 'result', header: '', width: 110 },
          { field: 'note', header: 'Why it was off', width: 420 }] });
    }
    host.style.height = (Math.min(out.length, 8) * 34 + 56) + 'px';
  }
  D.on('show', function (step) { if (step === 'bind') { drawBinds(); } if (step === 'review') { drawReview(); } });

  // ---- step 6: review and apply -----------------------------------------------------------------------------------------------------------------
  function bindingTexts() {
    var before = [], after = [], changed = {};
    S.kinds.forEach(function (k) { changed[k] = true; });
    (status().kinds || []).forEach(function (k) {
      if (!k.binding && !changed[k.kind]) { return; }
      if (k.binding) { before.push(flow(k.kind, k.binding)); }
      after.push(changed[k.kind] ? flow(k.kind, bindingFor(k.kind)) : flow(k.kind, k.binding));
    });
    S.kinds.forEach(function (k) { if (!kindOf(k)) { after.push(flow(k, bindingFor(k))); } });
    return { before: before.join('\n'), after: after.join('\n') };
  }
  function drawReview() {
    $('[data-file-name]').textContent = S.name;
    D.api('POST', D.CON + '/' + D.q(S.name) + '/render', D.draft()).then(function (r) {
      if (!r.ok) { D.say(D.why(r), true); return; }
      var host = $('[data-diff-file]');
      host.textContent = '';
      fileDiff = new I.CodeDiff(host, { original: '', value: r.body.text || '', mode: 'yaml', layout: 'unified', labels: ['(no file)', S.name + '.yaml'] });
    });
    var t = bindingTexts(), host = $('[data-diff-bindings]');
    host.textContent = '';
    bindDiff = new I.CodeDiff(host, { original: t.before, value: t.after, mode: 'yaml', layout: 'unified', labels: ['bindings.yaml now', 'after approval'] });
    D.sumOf('review', S.kinds.length + ' kind' + (S.kinds.length === 1 ? '' : 's') + ' · four eyes');
  }

  function eye(which, text, bad) {
    var n = $(which === 'a' ? '[data-eye-a]' : '[data-eye-b]');
    n.textContent = text;
    n.classList.toggle('bad', !!bad);
  }
  function refreshContext() {
    return D.api('GET', '/build/connector/api/context').then(function (r) { if (r.ok) { S.ctx = r.body; } return r; });
  }
  /** After the proposals: the decision. Our proposals leave the waiting list when a second administrator approves or rejects them. */
  function decided() {
    return refreshContext().then(function (r) {
      if (!r.ok) { return false; }
      var waiting = (status().proposals || []).filter(function (p) { return mine.indexOf(p.id) >= 0; });
      if (waiting.length) { eye('b', 'waiting for a second administrator (' + waiting.length + ' of ' + mine.length + ' kinds)'); return false; }
      var bound = S.kinds.filter(function (k) { var ki = kindOf(k); return ki && ki.binding && ((ki.binding.read || []).indexOf(S.name) >= 0 || ki.binding.live === S.name || ki.binding.write); });
      if (bound.length === S.kinds.length) {
        eye('b', 'approved and applied, no restart');
        D.sumOf('review', 'applied');
        D.say(S.name + ' is bound: ' + S.kinds.join(', ') + '.');
        showHealth();
      } else { eye('b', 'not applied: a second administrator rejected it (or it was withdrawn)', true); D.sumOf('review', 'not applied'); }
      window.clearInterval(poll);
      $('[data-refresh]').hidden = true;
      return true;
    });
  }
  function showHealth() {
    var rs = S.kinds.map(function (k) { var ki = kindOf(k) || {}; return { kind: k, state: ki.state || '', source: (ki.read || []).join(' + '), last: ki.lastLoad || '', why: ki.reason || '' }; });
    var host = $('[data-health-grid]');
    host.hidden = false;
    host.textContent = '';
    healthGrid = new G.Grid(host, { rows: rs, getRowId: function (r) { return r.kind; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none', label: 'Health after apply',
      columns: [{ field: 'kind', header: 'Kind', width: 180, cellClass: 'mono' }, { field: 'state', header: 'State', width: 110 }, { field: 'source', header: 'Served by', width: 240 },
        { field: 'last', header: 'Last load', width: 120 }, { field: 'why', header: '', width: 300 }] });
    host.style.height = (Math.min(rs.length, 8) * 34 + 56) + 'px';
  }
  $('[data-refresh]').addEventListener('click', function () { decided(); });
  $('[data-apply]').addEventListener('click', function () {
    var reason = D.field('reason').value.trim(), btn = $('[data-apply]');
    if (!reason) { D.say('Say why (the reason is kept in the audit log).', true); D.field('reason').focus(); return; }
    btn.disabled = true;
    D.say('Saving the connector ...');
    D.api('PUT', D.CON + '/' + D.q(S.name), D.draft()).then(function (r) {
      if (!r.ok) { btn.disabled = false; D.say(D.why(r), true); return null; }
      eye('a', 'connector saved; proposing ...');
      mine = [];
      var chain = Promise.resolve(true);
      S.kinds.forEach(function (kind) {
        chain = chain.then(function (ok) {
          if (!ok) { return false; }
          return D.api('POST', '/admin/api/bindings/propose', { kind: kind, binding: bindingFor(kind), reason: reason }).then(function (p) {
            if (!p.ok) { D.say(kind + ': ' + D.why(p), true); eye('a', 'stopped at ' + kind + ' (the connector is saved; the kinds before it are proposed)', true); return false; }
            mine.push(p.body.id);
            return true;
          });
        });
      });
      return chain;
    }).then(function (ok) {
      if (ok === null) { return; }
      btn.disabled = !ok ? false : true;
      if (!ok) { return; }
      eye('a', 'done: ' + S.kinds.length + ' proposal' + (S.kinds.length === 1 ? '' : 's') + ' by ' + (S.ctx.user || 'you'));
      eye('b', 'waiting for a second administrator');
      D.say('Proposed. A second administrator approves it in Admin → Data bindings; it applies without a restart.');
      $('[data-refresh]').hidden = false;
      $('[data-open-bindings]').hidden = false;
      D.sumOf('review', 'proposed · waiting');
      window.clearInterval(poll);
      poll = window.setInterval(decided, 5000);
    });
  });
})();
