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
/* Build -> New connector: the connector and binding designer (docs/guides/ADMIN_GUIDE.md, "Connectors and bindings"), part one: the workspace, its rail of six
 * steps, and the first four (Connector type, Settings, Test connection, Preview data). Everything is drawn with Drishti Elements (Grid, Select, Form); the
 * work is the server's, through Admin -> Connectors' own API (plugins, validate, test, preview). Part two (drishti_conn-designer-bind.js) is Bind to kinds and
 * Review and apply; it reads the state this file shares as Drishti.ConnDesigner. Nothing is saved until step 6. */
(function () {
  'use strict';
  var root = document.querySelector('[data-conn-designer]');
  if (!root) { return; }
  var E = window.Drishti.elements, C = E.core, I = E.inputs, G = E.grid, CS = window.Drishti.connSettings;
  var $ = function (s) { return root.querySelector(s); };
  var ORDER = ['type', 'settings', 'test', 'preview', 'bind', 'review'];
  var NAME_OK = /^[a-z0-9][a-z0-9-]*$/;

  var S = { ctx: null, plugin: null, name: '', description: '', kinds: [], settings: {}, form: null, keys: {}, tested: null, previewed: null, step: 'type', on: {} };
  var msg = $('[data-msg]');

  // ---- small helpers ------------------------------------------------------------------------------------------------------
  function api(method, url, body) { return C.api.send(method, url, body); }          // drishti-core's client: {ok, status, body}, never rejects
  function why(r) { return window.Drishti.drsMessage ? window.Drishti.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed') : (r.body.detail || 'The request failed'); }
  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('bad', !!bad); }
  function field(name) { return $('[data-f="' + name + '"]'); }
  function sumOf(step, text) { var n = $('[data-sum="' + step + '"]'); if (n) { n.textContent = text; } }
  function fire(name, arg) { (S.on[name] || []).forEach(function (f) { f(arg); }); }
  function on(name, f) { (S.on[name] = S.on[name] || []).push(f); }
  var CON = '/admin/connectors/api';
  function q(s) { return encodeURIComponent(s); }
  function list(text) { return String(text || '').split(/[,\s]+/).map(function (s) { return s.trim(); }).filter(Boolean); }
  function spec() { return S.ctx && S.plugin ? S.ctx.plugins.filter(function (p) { return p.name === S.plugin; })[0] : null; }

  /** The draft as the connector API takes it: {plugin, enabled, kinds, description, settings}. */
  function draft() {
    readSettings();
    return { plugin: S.plugin, enabled: true, kinds: S.kinds.slice(), description: S.description, settings: Object.assign({}, S.settings) };
  }

  // ---- the rail ---------------------------------------------------------------------------------------------------------------
  function show(step) {
    if (S.step === 'settings' && step !== 'settings') { readSettings(); }
    S.step = step;
    ORDER.forEach(function (s) { $('[data-pane="' + s + '"]').hidden = s !== step; });
    Array.prototype.forEach.call(root.querySelectorAll('[data-go]'), function (b) {
      b.setAttribute('aria-current', b.dataset.go === step ? 'step' : 'false');
      b.classList.toggle('done', ORDER.indexOf(b.dataset.go) < ORDER.indexOf(step));
    });
    $('[data-back]').hidden = step === 'type';
    $('[data-next]').hidden = step === 'review';
    if (step === 'settings') { drawSettings(); }
    fire('show', step);
  }
  function go(step) {
    var to = ORDER.indexOf(step);
    say('');
    if (to >= 1 && !S.plugin) { say('Pick a connector type first (step 1).', true); return Promise.resolve(false); }
    if (to < 2) { show(step); return Promise.resolve(true); }
    return checkSettings().then(function (ok) {
      if (!ok) { show('settings'); return false; }
      if (to >= 4 && !S.kinds.length) { say('Name the kinds this source holds (step 2) to preview and bind them.', true); show('settings'); return false; }
      show(step);
      return true;
    });
  }
  Array.prototype.forEach.call(root.querySelectorAll('[data-go]'), function (b) { b.addEventListener('click', function () { go(b.dataset.go); }); });
  $('[data-back]').addEventListener('click', function () { go(ORDER[Math.max(0, ORDER.indexOf(S.step) - 1)]); });
  $('[data-next]').addEventListener('click', function () { go(ORDER[Math.min(ORDER.length - 1, ORDER.indexOf(S.step) + 1)]); });

  // ---- step 1: the connector type --------------------------------------------------------------------------------------------------
  var typesGrid = null;
  function drawTypes() {
    var rows = S.ctx.plugins.map(function (p) {
      return { type: p.name, settings: (p.settings || []).length, summary: (S.summaries && S.summaries[p.name]) || '' };
    });
    var host = $('[data-types-grid]');
    if (typesGrid) { typesGrid.setRows(rows); return; }
    typesGrid = new G.Grid(host, { rows: rows, getRowId: function (r) { return r.type; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'single', checkboxes: false, label: 'Connector types',
      columns: [{ field: 'type', header: 'Type', width: 170, cellClass: 'mono' }, { field: 'summary', header: 'What it reads', width: 560 },
        { field: 'settings', header: 'Settings', type: 'number', width: 90, enableValue: false, aggFunc: null }] });
    typesGrid.on('selectionChanged', function (e) { if (e.ids && e.ids.length) { choose(e.ids[0]); } });
    host.style.height = (Math.min(Math.max(rows.length, 3), 16) * 34 + 56) + 'px';
  }
  function choose(type) {
    if (S.plugin === type) { return; }
    S.plugin = type;
    S.settings = {};
    S.form = null;
    S.tested = null;
    S.previewed = null;
    sumOf('type', type);
    sumOf('settings', 'after the type');
    say(type + ' selected: its settings are in step 2.');
  }
  function loadSummaries() {
    S.summaries = {};
    return Promise.all(S.ctx.plugins.map(function (p) {
      return api('GET', CON + '/types/' + q(p.name) + '/readme').then(function (r) { if (r.ok && r.body.meta && r.body.meta.summary) { S.summaries[p.name] = r.body.meta.summary; } });
    })).then(function () { drawTypes(); });
  }

  // ---- step 2: the settings -------------------------------------------------------------------------------------------------------
  function drawSettings() {
    var sp = spec();
    if (!sp) { return; }
    if (S.form && S.form.of === S.plugin) { return; }
    var built = CS.build($('[data-settings-form]'), sp, S.settings, null);
    S.form = { f: built.form, of: S.plugin };
    S.keys = built.keys;
    field('name').value = S.name;
    field('description').value = S.description;
    field('kinds').value = S.kinds.join(', ');
  }
  function readSettings() {
    if (!(S.form && S.form.f && S.form.of === S.plugin)) { return; }              // the pane was never drawn: the fields hold nothing yet
    S.name = field('name').value.trim();
    S.description = field('description').value.trim();
    S.kinds = list(field('kinds').value);
    S.settings = CS.read(S.form.f, S.keys);
  }
  function secretProblems() {
    var errs = {}, sp = spec();
    ((sp && sp.settings) || []).forEach(function (s) {
      var v = S.settings[s.name];
      if (s.secret && v && !CS.SECRET_OK.test(v) && S.keys[s.name]) { errs[S.keys[s.name].path] = s.name + ' is a credential: give a reference from the site secret store, ${ENV_VAR} or file:/path, never the value.'; }
    });
    return errs;
  }
  /** Name, secret references and the server's own validation of the draft: true when step 3 may start. */
  function checkSettings() {
    readSettings();
    var smsg = $('[data-settings-msg]');
    smsg.textContent = '';
    smsg.classList.remove('bad');
    var problem = !S.name ? 'Name the connector.' : !NAME_OK.test(S.name) ? 'A connector name is lower case letters, digits and hyphens, starting with a letter or a digit.'
      : S.ctx.connectors.indexOf(S.name) >= 0 ? S.name + ' exists already: change it in Admin → Connectors, or choose another name.' : '';
    if (problem) { smsg.textContent = problem; smsg.classList.add('bad'); say(problem, true); return Promise.resolve(false); }
    var errs = secretProblems();
    if (S.form && S.form.f) { S.form.f.showErrors(errs); }
    if (Object.keys(errs).length) { var m = errs[Object.keys(errs)[0]]; smsg.textContent = m; smsg.classList.add('bad'); say(m, true); return Promise.resolve(false); }
    return api('POST', CON + '/' + q(S.name) + '/validate', draft()).then(function (r) {
      if (!r.ok) { say(why(r), true); return false; }
      var bad = (r.body.problems || []).filter(function (p) { return p.level === 'error'; });
      var warn = (r.body.problems || []).filter(function (p) { return p.level !== 'error'; });
      if (bad.length) {
        var t = bad.map(function (p) { return p.field + ': ' + p.message; }).join('; ');
        smsg.textContent = t; smsg.classList.add('bad'); say(t, true);
        return false;
      }
      if (warn.length) { smsg.textContent = 'Notes: ' + warn.map(function (p) { return p.field + ': ' + p.message; }).join('; '); }
      sumOf('settings', S.name + ' · ' + Object.keys(S.settings).length + ' set');
      return true;
    });
  }

  // ---- step 3: test the connection ---------------------------------------------------------------------------------------------------
  var checksGrid = null, seesGrid = null;
  function grid(host, old, cfg) {
    if (old) { old.destroy ? old.destroy() : null; host.textContent = ''; }
    host.hidden = false;
    var g = new G.Grid(host, Object.assign({ layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none' }, cfg));
    host.style.height = (Math.min(Math.max(cfg.rows.length, 1), 12) * 34 + 56) + 'px';
    return g;
  }
  $('[data-test]').addEventListener('click', function () {
    var btn = $('[data-test]'), said = $('[data-test-said]');
    readSettings();
    btn.disabled = true;
    said.classList.remove('bad');
    said.textContent = 'Connecting ...';
    api('POST', CON + '/' + q(S.name) + '/test', draft()).then(function (r) {
      btn.disabled = false;
      if (!r.ok) { said.textContent = why(r); said.classList.add('bad'); return; }
      var t = S.tested = r.body;
      said.classList.toggle('bad', !t.ok);
      said.textContent = t.ok ? 'Connected · ' + t.ms + ' ms' + (t.via ? ' · ' + t.via : '') : 'Not connected: ' + (t.error || t.health) + (t.hint ? ' ' + t.hint : '');
      var checks = [{ check: 'Reachable', result: t.ok ? 'ok' : 'failed', note: t.ok ? '' : (t.error || '') },
        { check: 'Secrets are references', result: 'ok', note: Object.keys(secretProblems()).length ? 'a value was typed' : 'no value is held in the file' }];
      (t.warnings || []).forEach(function (w) { checks.push({ check: 'Note', result: 'note', note: w }); });
      checksGrid = grid($('[data-checks-grid]'), checksGrid, { rows: checks, getRowId: function (x) { return x.check + '|' + x.note; }, label: 'Checks',
        columns: [{ field: 'check', header: 'Check', width: 200 }, { field: 'result', header: '', width: 90 }, { field: 'note', header: 'Detail', width: 520 }] });
      var rows = [];
      (t.kinds || []).forEach(function (k) {
        if (!(k.dates || []).length) { rows.push({ kind: k.kind, date: '', rows: null, note: k.note || '' }); }
        (k.dates || []).forEach(function (d) { rows.push({ kind: k.kind, date: d.date || 'latest', rows: d.rows, note: k.note || '' }); });
      });
      $('[data-sees-h]').hidden = !rows.length;
      if (rows.length) {
        seesGrid = grid($('[data-sees-grid]'), seesGrid, { rows: rows, getRowId: function (x) { return x.kind + '|' + x.date + '|' + x.note; }, label: 'What the source holds',
          columns: [{ field: 'kind', header: 'Kind', width: 200, cellClass: 'mono' }, { field: 'date', header: 'Business date', width: 130 },
            { field: 'rows', header: 'Rows', type: 'number', width: 110, enableValue: false, aggFunc: null }, { field: 'note', header: 'Note', width: 360 }] });
      } else { $('[data-sees-grid]').hidden = true; }
      sumOf('test', t.ok ? 'connected · ' + t.ms + ' ms' : 'not connected');
      fire('tested', t);
    });
  });

  // ---- step 4: preview real rows -------------------------------------------------------------------------------------------------------
  var selKind = null, selDate = null, fieldsGrid = null, rowsGrid = null, pvKind = null;
  function drawPreviewPickers() {
    var kinds = S.kinds, dates = ['latest'];
    ((S.tested && S.tested.kinds) || []).forEach(function (k) { if (k.kind === (selKind ? pvKind : kinds[0])) { (k.dates || []).forEach(function (d) { if (d.date && dates.indexOf(d.date) < 0) { dates.push(d.date); } }); } });
    $('[data-sel-kind]').textContent = '';
    pvKind = pvKind && kinds.indexOf(pvKind) >= 0 ? pvKind : kinds[0];
    selKind = new I.Select($('[data-sel-kind]'), { options: kinds.map(function (k) { return { value: k, label: k }; }), value: pvKind, label: 'Kind to preview' });
    selKind.on('change', function (e) { pvKind = e.value; drawPreviewPickers(); });
    $('[data-sel-date]').textContent = '';
    selDate = new I.Select($('[data-sel-date]'), { options: dates.map(function (d) { return { value: d, label: d === 'latest' ? 'latest available' : d }; }), value: 'latest', label: 'Business date' });
  }
  on('show', function (step) { if (step === 'preview') { drawPreviewPickers(); } });
  function detect(v) {
    if (v === null || v === undefined) { return 'null'; }
    if (Array.isArray(v)) { return 'list'; }
    if (typeof v === 'object') { return 'object'; }
    if (typeof v === 'boolean') { return 'boolean'; }
    if (typeof v === 'number') { return Number.isInteger(v) ? 'integer' : 'decimal'; }
    var s = String(v);
    if (/^\d{4}-\d{2}-\d{2}([T ][\d:.]+Z?)?$/.test(s)) { return /[T ]/.test(s) ? 'timestamp' : 'date'; }
    if (/^-?\d+(\.\d+)?$/.test(s)) { return 'number (as text)'; }
    return 'text';
  }
  function show1(v) { return v === null || v === undefined ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v)); }
  $('[data-preview]').addEventListener('click', function () {
    var said = $('[data-preview-said]'), btn = $('[data-preview]');
    if (!pvKind) { said.textContent = 'Name a kind in step 2.'; return; }
    btn.disabled = true;
    said.classList.remove('bad');
    said.textContent = 'Reading ...';
    var body = Object.assign(draft(), { kind: pvKind, limit: 20 });
    var d = selDate ? selDate.getValue() : 'latest';
    if (d && d !== 'latest') { body.date = d; }
    api('POST', CON + '/' + q(S.name) + '/preview', body).then(function (r) {
      btn.disabled = false;
      if (!r.ok) { said.textContent = why(r); said.classList.add('bad'); return; }
      var p = S.previewed = r.body, rows = p.rows || [];
      if (!p.ok) { said.textContent = 'Could not read: ' + (p.error || 'no answer'); said.classList.add('bad'); return; }
      if (!rows.length) { said.textContent = 'No rows for ' + pvKind + (body.date ? ' on ' + body.date : '') + ': check the kind, the date and the settings that say where the records are.'; return; }
      var names = [];
      rows.forEach(function (row) { Object.keys(row && typeof row === 'object' ? row : { value: row }).forEach(function (k) { if (names.indexOf(k) < 0) { names.push(k); } }); });
      var info = names.map(function (n) {
        var seen = {}, nulls = 0, sample = '';
        rows.forEach(function (row) { var v = row[n]; var t = detect(v); if (t === 'null') { nulls++; } else { seen[t] = (seen[t] || 0) + 1; if (!sample) { sample = show1(v); } } });
        var types = Object.keys(seen);
        return { field: n, type: types.length ? types.join(' / ') : 'null', sample: sample.slice(0, 80), nulls: nulls };
      });
      $('[data-types-h]').hidden = false;
      $('[data-rows-h]').hidden = false;
      fieldsGrid = grid($('[data-fields-grid]'), fieldsGrid, { rows: info, getRowId: function (x) { return x.field; }, label: 'Fields and detected types',
        columns: [{ field: 'field', header: 'Field', width: 220, cellClass: 'mono' }, { field: 'type', header: 'Detected type', width: 150 }, { field: 'sample', header: 'Example', width: 300 },
          { field: 'nulls', header: 'Empty', type: 'number', width: 80, enableValue: false, aggFunc: null }] });
      var cols = names.slice(0, 14).map(function (n, i) { return { field: 'c' + i, header: n, width: 140 }; });
      var rrows = rows.map(function (row, ri) { var o = { _id: ri }; names.slice(0, 14).forEach(function (n, i) { o['c' + i] = show1(row[n]); }); return o; });
      rowsGrid = grid($('[data-rows-grid]'), rowsGrid, { rows: rrows, getRowId: function (x) { return x._id; }, columns: cols, label: 'The first rows of ' + pvKind });
      said.textContent = rows.length + ' real row' + (rows.length === 1 ? '' : 's') + ' of ' + pvKind + ' · ' + names.length + ' field' + (names.length === 1 ? '' : 's') + ' · ' + p.ms + ' ms';
      sumOf('preview', rows.length + ' rows · ' + names.length + ' fields');
      fire('previewed', p);
    });
  });

  // ---- what part two uses ----------------------------------------------------------------------------------------------------------------
  window.Drishti.ConnDesigner = { root: root, S: S, api: api, why: why, say: say, show: show, go: go, sumOf: sumOf, on: on, fire: fire, field: field, draft: draft, q: q, CON: CON, list: list };

  // ---- start: the types installed, the kinds, the bindings ----------------------------------------------------------------------------
  api('GET', '/build/connector/api/context').then(function (r) {
    if (!r.ok) { say(why(r), true); return; }
    S.ctx = r.body;
    $('[data-known-kinds]').innerHTML = '';
    (S.ctx.knownKinds || []).forEach(function (k) { var o = document.createElement('option'); o.value = k; $('[data-known-kinds]').appendChild(o); });
    $('[data-type-note]').textContent = S.ctx.plugins.length + ' types installed: ' + S.ctx.plugins.map(function (p) { return p.name; }).join(', ') + '. Pick one: its settings are generated from its own description.';
    drawTypes();
    loadSummaries();
    var pre = root.getAttribute('data-new');
    if (pre) {                                                                // a pack's link: ?new=<name> pre-fills from its suggestion
      api('GET', '/build/connector/api/suggestion/' + q(pre)).then(function (s) {
        if (s.ok && s.body.plugin) {
          S.plugin = s.body.plugin; S.name = pre; S.kinds = s.body.kinds || []; S.settings = s.body.settings || {}; S.description = s.body.description || '';
          sumOf('type', S.plugin);
          say('Pre-filled from the suggestion of pack ' + s.body.pack + '. Check the settings, then test.');
          show('settings');
        } else { S.name = pre; say('Nothing defines ' + pre + ' yet: choose a type.'); }
      });
    }
  });
  show('type');
})();
