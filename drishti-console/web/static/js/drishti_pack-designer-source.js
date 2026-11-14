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
/* Build -> New pack, part three: the second mode, "a source for an existing kind" (docs/guides/BUILD_GUIDE.md section 8.9; mock-up A of
 * docs/lenses/mockups/pack-designer-source/). After the analysis the person says what the files are; for a source the steps are Data, Map,
 * Layout, Finalize, Deploy. The map is the server's (the job `map`, the code of `drishti.py pack new --source-for`): every record read, each
 * native field matched to a field of the kind with a confidence. The page edits it (Grid cells, Select), sends the edits, and the module
 * is made by the same job code. Drishti Elements only: Select, Switch, Grid, Chips. */
(function () {
  'use strict';
  var D = window.Drishti.PackDesigner;
  if (!D) { return; }
  var E = window.Drishti.elements, C = E.core, I = E.inputs, G = E.grid;
  var root = D.root, S = D.S;
  var $ = function (s) { return root.querySelector(s); };
  var inp = function (n) { return $('[data-s="' + n + '"]'); };
  S.mapping = null;
  S.mapEdits = {};                          // canonical field -> null (drop) | {from} | {value} | {values}
  S.layout = 'own';
  S.live = false;
  S.writeBack = false;
  var selMode = null, selKind = null, selLayout = null, mapGrid = null, chips = null, picked = null;

  function pct(x) { return Math.round(100 * (x || 0)) + '%'; }
  function sys() { return (inp('system').value || '').trim().toUpperCase(); }
  function lowSys() { return sys().toLowerCase().replace(/_/g, '-'); }
  function kindOf() { return selKind ? (selKind.getValue() || '') : ''; }

  // ---- the mode, after the analysis ------------------------------------------------------------------------------------------
  D.on('analysed', function (a) {
    $('[data-mode-box]').hidden = false;
    var sug = a.sourceFor || [];
    if (!selMode) {
      selMode = new I.Select($('[data-sel-mode]'), { label: 'What the files are', value: S.mode,
        options: [{ value: 'kind', label: 'A new kind', detail: 'its own pack, Sutras and screens' },
          { value: 'source', label: 'A source for an existing kind', detail: sug.length ? 'suggested: ' + sug[0].kind + ' (' + sug[0].overlap + ' of ' + sug[0].fields + ' fields fit)' : 'a source module' }] });
      selMode.on('change', function (e) { D.mode(e.value); if (e.value === 'source') { D.show('map'); } });
    }
    var opts = sug.map(function (s) { return { value: s.kind, label: s.kind, detail: s.pack + ' · ' + s.overlap + ' of ' + s.fields + ' fields fit' }; });
    if (!opts.length) { opts = [{ value: 'trade', label: 'trade', detail: 'trading' }]; }
    $('[data-sel-srckind]').textContent = '';
    selKind = new I.Select($('[data-sel-srckind]'), { options: opts, value: opts[0].value, label: 'The existing kind', search: true });
    selKind.on('change', function () { S.mapEdits = {}; });
    $('[data-srckind-hint]').textContent = sug.length ? 'suggested from the overlap of the fields: ' + sug[0].kind : '';
    if (!inp('system').value) {
      var f = (S.files[0] || {}).path || '';
      var stem = f.split('/').pop().split('.')[0].split(/[_-]/)[0];
      inp('system').value = stem ? stem.toUpperCase() : '';
    }
  });

  // ---- step 2: the map -------------------------------------------------------------------------------------------------------
  function propose() {
    if (!sys()) { D.say('Give the system its name (upper case, such as SWAPONE).', true); return; }
    $('[data-map-prog]').hidden = false;
    $('[data-map-said]').textContent = 'reading every record ...';
    D.api('POST', D.ws() + '/map', { sourceFor: kindOf(), system: sys(), mapEdits: S.mapEdits, name: inp('name').value.trim() || 'source-' + lowSys() }).then(function (r) {
      if (!r.ok) { $('[data-map-prog]').hidden = true; D.say(D.why(r), true); return; }
      D.poll('map');
    });
  }
  $('[data-propose]').addEventListener('click', propose);
  D.on('mapped', function (s) {
    $('[data-map-prog]').hidden = true;
    if (s.state !== 'done') { D.say('The map was not made: ' + (s.error || 'see the log') + '.', true); return; }
    D.api('GET', D.ws() + '/mapping').then(function (r) {
      if (!r.ok) { D.say(D.why(r), true); return; }
      S.mapping = r.body;
      drawMap();
      D.say(D.num(S.mapping.rows) + ' records read: ' + S.mapping.mapped + ' of ' + S.mapping.fields.length + ' fields of ' + S.mapping.kind + ' mapped.');
    });
  });

  function rowsOf(m) {
    return m.fields.map(function (f) {
      var e = S.mapEdits[f.to];
      var src = f.from || (f.value !== null && f.value !== undefined ? '= ' + f.value : '');
      if (e === null) { src = ''; } else if (e && e.from !== undefined) { src = e.from || ''; } else if (e && e.value !== undefined) { src = '= ' + e.value; }
      var vm = Object.keys(f.values || {}).map(function (k) { return k + '→' + f.values[k]; }).join(', ');
      return { to: f.to, required: f.required ? 'yes' : '', native: src, confidence: src ? f.confidence : 0,
        why: f.why || '', values: vm, state: src ? (f.confidence >= 0.75 || (e && e !== null) ? 'sure' : 'check') : (f.required ? 'missing' : '') };
    });
  }
  function drawMap() {
    var m = S.mapping;
    var natives = [''].concat(Object.keys(m.native || {}));
    var rows = rowsOf(m);
    var host = $('[data-map-grid]');
    var cols = [{ field: 'to', header: 'Canonical field', width: 230, cellClass: 'mono', pinned: 'left' },
      { field: 'required', header: 'Req.', width: 60 },
      { field: 'native', header: m.system + ' field', width: 200, cellClass: 'mono', editable: true, options: natives.concat(rows.filter(function (r) { return /^= /.test(r.native); }).map(function (r) { return r.native; })) },
      { field: 'confidence', header: 'Confidence', type: 'number', width: 110, enableValue: false, aggFunc: null, valueFormatter: function (p) { return p.value ? pct(p.value) : ''; },
        cellClass: function (v) { return v >= 0.75 ? 'pd-ok' : v ? 'pd-warn' : ''; } },
      { field: 'state', header: 'State', width: 90, cellClass: function (v) { return v === 'missing' ? 'pd-bad' : v === 'check' ? 'pd-warn' : ''; } },
      { field: 'values', header: 'Value map', width: 220, cellClass: 'mono' },
      { field: 'why', header: 'Why', width: 360 }];
    host.textContent = '';
    mapGrid = new G.Grid(host, { rows: rows, columns: cols, getRowId: function (r) { return r.to; }, layout: 'wells', density: 'compact', chrome: false,
      rowSelection: 'single', label: 'The field map: canonical field and its native field' });
    host.style.height = (Math.min(rows.length, 18) * 34 + 56) + 'px';
    mapGrid.on('cellValueChanged', function (e) {
      if (e.colId !== 'native') { return; }
      var v = String(e.value || '');
      S.mapEdits[e.rowId] = v === '' ? null : (/^= /.test(v) ? { value: v.slice(2) } : { from: v });
      kpi();
    });
    mapGrid.on('cellClicked', function (e) { evidence(e.rowId); });
    mapGrid.on('selectionChanged', function (e) { var id = (e.ids || e.rowIds || [])[0]; if (id) { evidence(id); } });
    var un = (m.unmapped || []).map(function (u) { return u.field + ' (' + D.num(u.rows) + ')'; });
    var uh = $('[data-unmapped]');
    if (C.Chips) { if (chips) { chips.setValues(un); } else { chips = new C.Chips(uh, { values: un, label: 'Unmapped native fields' }); } } else { uh.textContent = un.join(', '); }
    kpi();
    D.sumOf('map', 'source for ' + m.kind + ' · ' + m.mapped + ' of ' + m.fields.length + ' mapped');
    layoutText();
  }
  function kpi() {
    var m = S.mapping;
    if (!m) { return; }
    var rows = rowsOf(m), mapped = rows.filter(function (r) { return r.native; }).length, check = rows.filter(function (r) { return r.state === 'check'; }).length;
    var missing = rows.filter(function (r) { return r.state === 'missing'; }).map(function (r) { return r.to; });
    $('[data-map-kpi]').textContent = mapped + ' mapped · ' + check + ' to check · ' + (m.unmapped || []).length + ' native unmapped · ' + missing.length + ' required missing'
      + (missing.length ? ' (' + missing.slice(0, 4).join(', ') + (missing.length > 4 ? ' …' : '') + ')' : '') + (Object.keys(S.mapEdits).length ? ' · ' + Object.keys(S.mapEdits).length + ' edit(s): Propose again' : '');
  }
  function evidence(to) {
    var f = (S.mapping.fields || []).filter(function (x) { return x.to === to; })[0];
    if (!f) { return; }
    picked = to;
    $('[data-ev-title]').textContent = f.to + (f.from ? ' ← ' + f.from : '');
    var n = f.from && S.mapping.native[f.from];
    var parts = [f.why || 'no native field fits'];
    if (f.evidence) { parts.push('name ' + pct(f.evidence.name) + ', type ' + pct(f.evidence.type) + ', values ' + pct(f.evidence.values)); }
    if (n) { parts.push(D.num(n.rows) + ' rows, ' + n.kind + ', ' + D.num(n.distinct) + ' distinct: ' + n.values.slice(0, 5).map(function (v) { return v[0]; }).join(', ')); }
    if (f.enum && f.enum.length) { parts.push('the kind\'s values: ' + f.enum.join(', ')); }
    if (f.others && f.others.length) { parts.push('other candidates: ' + f.others.map(function (o) { return o.from + ' ' + pct(o.confidence); }).join(', ')); }
    $('[data-ev-body]').textContent = parts.join(' · ');
  }

  // ---- step 3: the layout ------------------------------------------------------------------------------------------------------
  selLayout = new I.Select($('[data-sel-layout]'), { label: 'The table', value: 'own', options: [
    { value: 'own', label: 'Its own table', detail: 'one lake table for this system' },
    { value: 'shared', label: 'A shared table + where', detail: 'one lake table for several systems, read with where: SOURCE_SYSTEM = ...' }] });
  selLayout.on('change', function (e) { S.layout = e.value; layoutText(); });
  var swLive = new I.Switch($('[data-sw-live]'), { label: 'A Kafka listener', value: false, help: 'off until the site sets the servers and the topic' });
  swLive.on('change', function (e) { S.live = e.value; layoutText(); });
  var swWb = new I.Switch($('[data-sw-wb]'), { label: 'The blotter writes back through it', value: false, help: 'an amend command on the listener (needs the listener)' });
  swWb.on('change', function (e) { S.writeBack = e.value; layoutText(); });
  ['table', 'name', 'version', 'system'].forEach(function (n) { inp(n).addEventListener('input', layoutText); });
  function table() {
    var t = inp('table').value.trim();
    if (t) { return t; }
    return S.layout === 'shared' ? 'trading.trades_shared' : 'trading.' + (kindOf() || 'trade').replace(/-/g, '_') + '_' + sys().toLowerCase();
  }
  function layoutText() {
    var k = kindOf() || 'trade', name = inp('name').value.trim() || 'source-' + lowSys();
    $('[data-table-hint]').textContent = S.layout === 'shared' ? 'blank: trading.trades_shared' : 'blank: ' + table();
    var lines = ['# connectors/' + name + '.yaml', 'plugin: delta', 'kinds: [' + k + ']', 'settings:', '  lake: main', '  lake-tables: ' + table()];
    if (S.layout === 'shared') { lines.push("  where: \"SOURCE_SYSTEM = '" + sys() + "'\""); }
    lines.push('  set: { SOURCE_SYSTEM: ' + sys() + ' }', '  map: mappings/' + lowSys() + '-' + k + '.yaml');
    if (S.live) { lines.push('# + connectors/' + name + '-live.yaml: Kafka, off until the site turns it on' + (S.writeBack ? ', with the amend-' + k + ' write-back' : '')); }
    $('[data-layout-yaml]').textContent = lines.join('\n');
    D.sumOf('layout', (S.layout === 'shared' ? 'shared: ' : 'own: ') + table());
  }

  // ---- what finalize sends --------------------------------------------------------------------------------------------------------
  D.sourceChoices = function () {
    var name = inp('name').value.trim() || 'source-' + lowSys();
    var r = { mode: 'source', name: name, version: inp('version').value.trim() || '1.0.0', sourceFor: kindOf(), system: sys(), mapEdits: S.mapEdits,
      live: S.live, writeBack: S.live && S.writeBack };
    r[S.layout === 'shared' ? 'sharedTable' : 'ownTable'] = table();
    return r;
  };
  D.on('show', function (step) {
    if (step === 'map' && !S.mapping && S.analysis) { propose(); }
    if (step === 'layout') { layoutText(); }
  });
  D.on('mode', function (m) { if (selMode && selMode.getValue() !== m) { selMode.setValue(m); } });
})();
