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
/* Build -> New pack: the Pack designer (docs/guides/BUILD_GUIDE.md section 8), part one: the workspace, its rail of six steps, and the first
 * three steps (Data, Kind and keys, Source). Everything is drawn with Drishti Elements (FileDrop, Select, MultiSelect, Switch, Grid); the work is
 * the server's: the files are uploaded, every record is read there (the code of `drishti.py pack new`), and this page shows what it found.
 * Part two (drishti_pack-designer-steps.js) is Design, Finalize and Deploy; it reads the state this file shares as Drishti.PackDesigner. */
(function () {
  'use strict';
  var root = document.querySelector('[data-pack-designer]');
  if (!root) { return; }
  var E = window.Drishti.elements, C = E.core, I = E.inputs, G = E.grid;
  var $ = function (s) { return root.querySelector(s); };
  var BASE = '/build/pack/api/ws';
  var ORDER = ['data', 'kind', 'source', 'design', 'finalize', 'deploy'];

  var S = {
    id: null, step: 'data', files: [], analysis: null, kind: null, busy: false, poll: null,
    cfg: {},                      // kind -> {match: [fields], key, date}: the choices (inferred first, then yours)
    edits: {},                    // Sutra name -> the YAML as edited
    checks: {},                   // Sutra name -> {ok, total}
    result: null, plan: null, canAuthor: root.dataset.canAuthor === 'true', isAdmin: root.dataset.isAdmin === 'true',
    maxMb: parseInt(root.dataset.maxMb, 10) || 4096, on: {}
  };
  var msg = $('[data-msg]');

  // ---- small helpers ------------------------------------------------------------------------------------------------------
  function api(method, url, body) {
    return fetch(url, { method: method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) { return (r.status === 204 ? Promise.resolve({}) : r.json().catch(function () { return {}; })).then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); },
        function () { return { ok: false, status: 0, body: { detail: 'The console could not be reached.' } }; });
  }
  function why(r) { return window.Drishti.drsMessage ? window.Drishti.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed') : (r.body.detail || 'The request failed'); }
  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('bad', !!bad); }
  function num(n) { return C.formatNumber(Number(n)); }
  function bytes(n) { return C.formatBytes(n); }
  function field(name) { return $('[data-f="' + name + '"]'); }
  function fire(name, arg) { (S.on[name] || []).forEach(function (f) { f(arg); }); }
  function on(name, f) { (S.on[name] = S.on[name] || []).push(f); }
  function kinds() { return S.analysis ? Object.keys(S.analysis.kinds) : []; }
  function sumOf(step, text) { var n = $('[data-sum="' + step + '"]'); if (n) { n.textContent = text; } }
  function ws() { return BASE + '/' + S.id; }
  function save() { try { sessionStorage.setItem('drishti_pd_ws', S.id || ''); } catch (e) { /* private window */ } }

  // ---- the rail ---------------------------------------------------------------------------------------------------------------
  function show(step) {
    S.step = step;
    ORDER.forEach(function (s) { $('[data-pane="' + s + '"]').hidden = s !== step; });
    Array.prototype.forEach.call(root.querySelectorAll('[data-go]'), function (b) {
      var i = ORDER.indexOf(b.dataset.go);
      b.setAttribute('aria-current', b.dataset.go === step ? 'step' : 'false');
      b.classList.toggle('done', i < ORDER.indexOf(step));
    });
    $('[data-back]').hidden = step === 'data';
    $('[data-next]').hidden = step === 'deploy';
    fire('show', step);
  }
  Array.prototype.forEach.call(root.querySelectorAll('[data-go]'), function (b) {
    b.addEventListener('click', function () { go(b.dataset.go); });
  });
  function go(step) {
    var to = ORDER.indexOf(step);
    if (to > 0 && !S.analysis) { say('Upload the files and let every record be read first (step 1).', true); return; }
    say('');
    show(step);
  }
  $('[data-back]').addEventListener('click', function () { go(ORDER[Math.max(0, ORDER.indexOf(S.step) - 1)]); });
  $('[data-next]').addEventListener('click', function () { go(ORDER[Math.min(ORDER.length - 1, ORDER.indexOf(S.step) + 1)]); });

  // ---- step 1: the data ------------------------------------------------------------------------------------------------------
  var drop = new I.FileDrop($('[data-drop]'), { multiple: true, label: 'Drop files or a folder here', accept: root.dataset.accept.split(','),
    hint: '.jsonl, .jsonl.gz, .ndjson (or click to choose); up to ' + num(S.maxMb) + ' MB' });
  var filesGrid = null;
  function drawFiles() {
    var host = $('[data-files-grid]');
    host.hidden = !S.files.length;
    $('[data-total]').textContent = S.files.length ? S.files.length + ' file' + (S.files.length === 1 ? '' : 's') + ', ' + bytes(S.files.reduce(function (n, f) { return n + f.bytes; }, 0)) + ' on the server' : '';
    var rows = S.files.map(function (f) { return { path: f.path, size: f.bytes, state: f.state || 'uploaded' }; });
    if (filesGrid) { filesGrid.setRows(rows); } else if (rows.length) {
      filesGrid = new G.Grid(host, { rows: rows, getRowId: function (r) { return r.path; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none', label: 'Uploaded files',
        columns: [{ field: 'path', header: 'File', width: 360, cellClass: 'mono' }, { field: 'size', header: 'Size', type: 'number', width: 110, enableValue: false, aggFunc: null, valueFormatter: function (p) { return bytes(p.value); } },
          { field: 'state', header: 'State', width: 130 }] });
    }
    host.style.height = (Math.min(Math.max(rows.length, 1), 8) * 34 + 56) + 'px';
    $('[data-analyse]').disabled = !S.files.length || S.busy || !S.canAuthor;
    sumOf('data', S.analysis ? S.files.length + ' files · ' + num(S.analysis.rows) + ' rows' : (S.files.length ? S.files.length + ' files uploaded' : 'no files yet'));
  }
  function refreshFiles() { return api('GET', ws()).then(function (r) { if (r.ok) { S.files = r.body.files; drawFiles(); } return r; }); }
  function begin() {
    if (S.id) { return Promise.resolve(true); }
    return api('POST', BASE).then(function (r) { if (!r.ok) { say(why(r), true); return false; } S.id = r.body.id; save(); return true; });
  }
  drop.on('refused', function (e) { say(e.reason, true); });
  drop.on('pick', function (e) {
    if (!S.canAuthor) { say('Starting a pack needs the author power.', true); return; }
    var items = e.files;
    begin().then(function (ok) {
      if (!ok) { return; }
      say('Uploading ' + items.length + ' file' + (items.length === 1 ? '' : 's') + ' ...');
      drop.sendMany(function (it) { return ws() + '/file?path=' + encodeURIComponent(it.path); }).then(function (out) {
        drop.clear();
        var bad = out.filter(function (o) { return o.status >= 400; })[0];
        if (bad) { say(bad.path + ': ' + ((bad.body && bad.body.detail) || 'the upload failed'), true); }
        else { say(''); }
        S.analysis = null;
        guessKind(items);
        refreshFiles().then(function () { if (!bad) { analyse(); } });
      }, function (err) { drop.clear(); say(String(err.message || err), true); });
    });
  });
  /** One kind for files that share a name before the first '_', '-' or '.' (trade_endur.jsonl, trade_murex.jsonl: trade). */
  function guessKind(items) {
    var stems = items.map(function (it) { return it.file.name.split('/').pop().split('.')[0].split(/[_-]/)[0].toLowerCase(); });
    var k = stems.length > 1 && stems.every(function (s) { return s === stems[0]; }) ? stems[0] : '';
    if (!field('kind').value) { field('kind').value = k; }
  }

  // ---- reading every record ----------------------------------------------------------------------------------------------------
  function choices() {
    var cur = S.kind && S.cfg[S.kind] ? S.cfg[S.kind] : null, ks = Object.keys(S.cfg).filter(function (k) { return !(S.analysis && S.analysis.kinds[k] && S.analysis.kinds[k].parent); }), one = ks.length <= 1;
    var pairs = function (pick) { return ks.map(function (k) { return S.cfg[k][pick] ? (one ? '' : k + '=') + (Array.isArray(S.cfg[k][pick]) ? S.cfg[k][pick].join(',') : S.cfg[k][pick]) : null; }).filter(Boolean); };
    var r = { name: (field('name').value || 'my-pack').trim(), version: field('version').value.trim() || '1.0.0', title: field('title').value.trim(), kind: field('kind').value.trim(),
      kindPrefix: field('kindPrefix').value.trim(), filesRoot: field('filesRoot').value.trim(), connector: field('connector').value.trim(), load: S.load !== false };
    if (cur) {
      r.match = one ? pairs('match').join('') : ks.filter(function (k) { return S.cfg[k].match.length; }).map(function (k) { return k + '=' + S.cfg[k].match.join(','); }).join(';');
      r.key = pairs('key').join(',');
      r.date = pairs('date').join(',');
      if (!r.match && S.touched) { r.match = 'none'; }
      if (S.childTouched) { r.child = ks.map(function (k) { return k + '=' + (S.cfg[k].children.length ? S.cfg[k].children.join(',') : 'none'); }).join(';'); }   // child tables: as switched
    }
    return r;
  }
  function analyse() {
    if (!S.id || S.busy) { return; }
    S.busy = true;
    drawFiles();
    $('[data-read-prog]').hidden = false;
    $('[data-read-said]').textContent = 'starting ...';
    api('POST', ws() + '/analyse', choices()).then(function (r) {
      if (!r.ok) { S.busy = false; $('[data-read-prog]').hidden = true; say(why(r), true); drawFiles(); return; }
      poll('analyse');
    });
  }
  function poll(op) {
    window.clearTimeout(S.poll);
    api('GET', ws() + '/status').then(function (r) {
      var s = r.body;
      if (op === 'analyse') { $('[data-read-said]').textContent = (s.rows ? num(s.rows) + ' rows read · ' : '') + s.seconds + ' s' + (s.lines && s.lines.length ? ' · ' + s.lines[s.lines.length - 1].trim() : ''); }
      if (s.state === 'running') { S.poll = window.setTimeout(function () { poll(op); }, 700); return; }
      if (op === 'analyse') { analysed(s); } else { fire('finalised', s); }
    });
  }
  function analysed(s) {
    S.busy = false;
    $('[data-read-prog]').hidden = true;
    if (s.state !== 'done') { say('Reading stopped: ' + (s.error || 'see the log') + '.', true); drawFiles(); return; }
    api('GET', ws() + '/analysis').then(function (r) {
      if (!r.ok) { say(why(r), true); return; }
      S.analysis = r.body;
      Object.keys(S.analysis.kinds).forEach(function (k) {
        var a = S.analysis.kinds[k];
        S.cfg[k] = { match: a.match.slice(), key: a.key, date: a.date || '', children: (a.arrays || []).filter(function (x) { return x.child; }).map(function (x) { return x.field; }) };
      });
      if (!S.kind || !S.analysis.kinds[S.kind]) { S.kind = kinds()[0]; }
      say(num(S.analysis.rows) + ' records read in ' + S.analysis.seconds + ' s; ' + S.analysis.sutras.length + ' Sutras drafted.');
      drawFiles();
      drawKind();
      fire('analysed', S.analysis);
      if (S.step === 'data') { show('kind'); }
    });
  }
  $('[data-analyse]').addEventListener('click', analyse);
  $('[data-cancel]').addEventListener('click', function () { api('POST', ws() + '/cancel', {}); });

  // ---- step 2: kind and keys ----------------------------------------------------------------------------------------------------
  var msMatch = null, selKey = null, selDate = null, selKind = null, combosGrid = null;
  function drawKind() {
    var a = S.analysis && S.analysis.kinds[S.kind];
    if (!a) { return; }
    var cfg = S.cfg[S.kind];
    var fields = a.fields.map(function (f) { return f.path; });
    var cand = {};
    a.matchCandidates.forEach(function (c) { cand[c.field] = c; });
    var opts = fields.map(function (f) { return { value: f, label: f, detail: cand[f] ? (cand[f].distinct + ' values') : '' }; });
    if (msMatch) { msMatch.destroy(); }
    $('[data-ms-match]').textContent = '';
    msMatch = new I.MultiSelect($('[data-ms-match]'), { options: opts, value: cfg.match, label: 'Discriminator columns', placeholder: 'None: one Sutra for the kind', maxChips: 4, selectAll: true });
    msMatch.on('change', function (e) { cfg.match = e.value.slice(); S.touched = true; });
    var keyOpts = fields.map(function (f) { return { value: f, label: f }; });
    $('[data-sel-key]').textContent = '';
    selKey = new I.Select($('[data-sel-key]'), { options: keyOpts, value: cfg.key, label: 'ID column', search: true });
    selKey.on('change', function (e) { cfg.key = e.value; S.touched = true; });
    $('[data-sel-date]').textContent = '';
    selDate = new I.Select($('[data-sel-date]'), { options: [{ value: '', label: '(none: the file name\'s date)' }].concat(keyOpts), value: cfg.date || '', label: 'Business date column', search: true });
    selDate.on('change', function (e) { cfg.date = e.value; S.touched = true; });
    drawChildren(a, cfg);
    var ks = kinds();
    if (ks.length > 1) {                                                // several kinds: choose the one to set up
      var host = $('[data-ms-match]').parentNode;
      var old = host.querySelector('.pd-kindsel');
      if (old) { old.remove(); }
      var box = document.createElement('div');
      box.className = 'pd-kindsel';
      host.insertBefore(box, host.firstChild);
      selKind = new I.Select(box, { options: ks.map(function (k) { return { value: k, label: k + ' (' + num(S.analysis.kinds[k].rows) + ')' }; }), value: S.kind, label: 'Kind to set up' });
      selKind.on('change', function (e) { S.kind = e.value; drawKind(); fire('kind', S.kind); });
    }
    var kc = $('[data-key-check]');
    kc.classList.toggle('bad', !a.unique);
    kc.textContent = a.unique ? 'unique over all ' + num(a.rows) + ' rows (checked across every file)'
      : (a.dupRows ? num(a.dupRows) + ' duplicate rows' + (a.dupNamed.length ? ' (e.g. ' + a.dupNamed.slice(0, 3).join(', ') + ')' : '') : '') + (a.noKey ? ' ' + num(a.noKey) + ' rows without it' : '') + ' (one entity per id per date)';
    $('[data-date-check]').textContent = a.date ? num(a.dateCount) + ' business date' + (a.dateCount === 1 ? '' : 's') + (a.noDate ? ', ' + num(a.noDate) + ' rows without one' : '') : 'taken from each file\'s name';
    var rows = S.analysis.sutras.filter(function (s) { return s.kind === S.kind; }).map(function (s) {
      return { combo: s.label, rows: s.count, share: a.rows ? s.count / a.rows : 0, sutra: s.name, note: s.rare ? 'rare' : '' };
    });
    var cols = [{ field: 'combo', header: cfg.match.length ? cfg.match.join(' · ') : 'Combination', width: 300 }, { field: 'rows', header: 'Rows', type: 'number', width: 110, enableValue: false, aggFunc: null },
      { field: 'share', header: 'Share', type: 'number', width: 90, enableValue: false, aggFunc: null, valueFormatter: function (p) { return C.formatPercent(p.value); } },
      { field: 'sutra', header: 'Sutra', width: 280, cellClass: 'mono' }, { field: 'note', header: '', width: 80 }];
    var host2 = $('[data-combos-grid]');
    host2.textContent = '';
    combosGrid = new G.Grid(host2, { rows: rows, columns: cols, getRowId: function (r) { return r.sutra; }, layout: 'wells', density: 'compact', chrome: false, rowSelection: 'none',
      label: 'Combinations and their Sutras' });
    host2.style.height = (Math.min(Math.max(rows.length, 1), 14) * 34 + 56) + 'px';
    sumOf('kind', S.kind + ' · ' + cfg.match.length + ' discriminator' + (cfg.match.length === 1 ? '' : 's') + ' · ' + a.combinations + ' Sutra' + (a.combinations === 1 ? '' : 's'));
    if (!field('kind').value && ks.length === 1 && !/[_]/.test(S.kind)) { field('kind').placeholder = S.kind; }
  }
  /** Child tables: one switch per array of records of the kind (on: a child kind of its own); a child kind says whose rows it is. */
  function drawChildren(a, cfg) {
    var box = $('[data-children-box]'), host = $('[data-children]');
    host.textContent = '';
    var arrays = a.arrays || [];
    box.hidden = !arrays.length && !a.parent;
    if (a.parent) {
      host.textContent = 'This kind is the rows of ' + a.parent.kind + '\'s ' + a.parent.field + ': ID ' + a.key + ', link ' + a.parent.link + ' to the ' + a.parent.kind + '.';
      return;
    }
    arrays.forEach(function (x) {
      var row = document.createElement('div');
      row.className = 'pd-child';
      row.setAttribute('data-child', x.field);
      host.appendChild(row);
      var sw = new I.Switch(row, { label: x.field + ' → ' + (x.kind || '') + ' (' + x.avg + ' per row on average' + (x.natural ? ', ID ' + x.natural : '') + ')',
        value: cfg.children.indexOf(x.field) >= 0, help: 'one row per element of ' + x.field + '; Apply and read again to profile it' });
      sw.on('change', function (e) {
        cfg.children = cfg.children.filter(function (f) { return f !== x.field; }).concat(e.value ? [x.field] : []);
        S.childTouched = true;
      });
    });
  }
  $('[data-reanalyse]').addEventListener('click', function () { S.edits = {}; S.checks = {}; analyse(); });
  var sw = new I.Switch($('[data-sw-load]'), { label: 'Write the records to the files store', value: true, help: 'the file connector reads the records from the files store; switch off to bind to a store you fill yourself' });
  sw.on('change', function (e) { S.load = e.value; });

  // ---- step 3: the source ---------------------------------------------------------------------------------------------------------
  ['name', 'title', 'version', 'kindPrefix', 'filesRoot', 'connector'].forEach(function (f) {
    field(f).addEventListener('input', function () {
      var n = field('name').value.trim();
      sumOf('source', n ? n + ' ' + (field('version').value || '1.0.0') + ' · ' + (field('connector').value.trim() || n + '-files') : 'file connector');
    });
  });

  // ---- what part two uses -----------------------------------------------------------------------------------------------------------
  window.Drishti.PackDesigner = { root: root, S: S, api: api, why: why, say: say, num: num, bytes: bytes, ws: ws, show: show, go: go, sumOf: sumOf, poll: poll, choices: choices, on: on, fire: fire,
    field: field };

  // ---- resume a workspace of this tab ----------------------------------------------------------------------------------------------
  var had = null;
  try { had = sessionStorage.getItem('drishti_pd_ws'); } catch (e) { had = null; }
  if (had) {
    S.id = had;
    api('GET', ws()).then(function (r) {
      if (!r.ok) { S.id = null; save(); return; }
      S.files = r.body.files;
      var q = r.body.request || {};
      ['name', 'title', 'version', 'kind', 'kindPrefix', 'filesRoot', 'connector'].forEach(function (f) { if (q[f]) { field(f).value = q[f]; } });
      drawFiles();
      if (r.body.status && r.body.status.state === 'running') { S.busy = true; $('[data-read-prog]').hidden = false; poll(r.body.status.op === 'finalize' ? 'finalize' : 'analyse'); }
      else if (r.body.analysed) { analysed({ state: 'done' }); }
    });
  }
  show('data');
  drawFiles();
  if (!S.canAuthor) { drop.setDisabled(true); }
})();
