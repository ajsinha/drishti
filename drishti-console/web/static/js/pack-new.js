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
/* Build -> New pack (/build/pack/new): five steps from schema files and sample documents to a pack. 1 Sources (files and folders read in
 * this browser; only a sample and the counts go on), 2 Kinds (one card per kind with what was detected), 3 Pack (name, code, connectors,
 * mnemonics), 4 Preview (every Sutra drafted by the server as a background job, rendered on samples), 5 Output (download the bundle, or
 * hand it to Admin -> Packs -> Deploy archive). The state can be kept as a draft (a design of yours) and resumed. */
(function () {
  'use strict';
  var root = document.querySelector('[data-pack-new]');
  if (!root) { return; }
  var U = window.DrishtiPackUI, R = window.DrishtiPackRead, K = window.DrishtiPackKinds, PV = window.DrishtiPackPreview;
  var el = U.el, api = U.api;
  var $ = function (s) { return root.querySelector(s); };
  var NAME_RE = /^[a-z0-9][a-z0-9-]*$/;
  var cfg = {
    sampleDocs: parseInt(root.dataset.sampleDocs, 10) || 200, readRows: parseInt(root.dataset.readRows, 10) || 500000, readMb: parseInt(root.dataset.readMb, 10) || 1024,
    canAuthor: root.dataset.canAuthor === 'true', isAdmin: root.dataset.isAdmin === 'true'
  };
  var S = {
    step: 1, schemas: [], data: [], missing: [], kindOf: {}, overrides: {}, pack: { name: '', code: '', title: '', description: '', version: '1.0.0', connector: '' },
    samples: 5, strip: 6, plan: null, planFiles: [], planKey: '', job: null, jobKey: '', jobView: null, draftId: null, reading: false, stop: false, busy: false,
    opts: { maxRows: Math.min(200000, cfg.readRows), maxMb: Math.min(500, cfg.readMb) }
  };
  var msg = $('[data-msg]'), nextB = $('[data-next]'), backB = $('[data-back]');
  var steps = ['', '[data-step="1"]', '[data-step="2"]', '[data-step="3"]', '[data-step="4"]', '[data-step="5"]'];

  function say(t, bad) { U.say(msg, t, bad); }

  // ---- steps ----------------------------------------------------------------------------------------------------------------
  function show(n) {
    S.step = n;
    for (var i = 1; i <= 5; i++) { $(steps[i]).hidden = i !== n; }
    Array.prototype.forEach.call(root.querySelectorAll('[data-go]'), function (b) {
      var i = parseInt(b.dataset.go, 10);
      b.setAttribute('aria-current', i === n ? 'step' : 'false');
      b.classList.toggle('done', i < n);
    });
    backB.hidden = n === 1;
    nextB.hidden = n === 5;
    nextB.textContent = n === 3 ? 'Preview the pack' : n === 4 ? 'Output' : 'Next';
    var h = $(steps[n] + ' h2');
    if (h) { h.setAttribute('tabindex', '-1'); h.focus(); }
  }

  function canLeave(n, to) {
    if (to <= n) { return true; }
    if (n === 1 && !(S.schemas.length || S.data.length)) { say('Add at least one schema file or one JSON / JSON Lines file first.', true); return false; }
    if (n === 1 && S.reading) { say('Still reading files. Wait, or cancel the reading.', true); return false; }
    if (n === 2 && S.plan) {
      var nokey = S.plan.kinds.filter(function (k) { return !k.key; });
      if (nokey.length) { say('Choose the key field of ' + nokey.map(function (k) { return k.kind; }).join(', ') + '.', true); return false; }
      if (!S.plan.kinds.length) { say('No kind could be read from these files.', true); return false; }
    }
    if (n === 3) {
      if (!NAME_RE.test(S.pack.name)) { say('The pack name is lower-case letters, digits and "-".', true); return false; }
      if (S.plan && S.plan.conflicts.mnemonics.length) { say('A mnemonic is already used by another pack: ' + S.plan.conflicts.mnemonics.join(', ') + '.', true); return false; }
    }
    if (n === 4 && !(S.jobView && S.jobView.state === 'done')) { say('The preview is not finished.', true); return false; }
    if (n === 4 && S.jobView.sutras.some(function (s) { return !s.yaml; })) { say('Some Sutras could not be drafted. Go back, change what was refused, and preview again.', true); return false; }
    return true;
  }

  function go(n) {
    if (!canLeave(S.step, n)) { return; }
    say('');
    if (n >= 2 && S.step !== n) { autosave(); }
    show(n);
    if (n === 2) { loadPlan(); } else if (n === 3) { drawPackForm(); } else if (n === 4) { startPreview(); } else if (n === 5) { drawOutput(); }
  }

  nextB.addEventListener('click', function () { go(S.step + 1); });
  backB.addEventListener('click', function () { go(S.step - 1); });
  Array.prototype.forEach.call(root.querySelectorAll('[data-go]'), function (b) {
    b.addEventListener('click', function () { go(parseInt(b.dataset.go, 10)); });
  });

  // ---- 1. sources -------------------------------------------------------------------------------------------------------------
  var list = $('[data-sources]'), total = $('[data-total]');
  function renderSources() {
    U.clear(list);
    var rows = S.schemas.map(function (s) { return { kind: 'schema', name: s.name, info: U.bytes(s.bytes || s.text.length), ref: s }; })
      .concat(S.data.map(function (d) { return { kind: 'data', name: d.name, info: d.reading ? Math.round(100 * d.bytesRead / Math.max(d.bytes, 1)) + '% read, ' + U.num(d.rows) + ' rows'
        : U.num(d.rows) + ' rows, ' + U.bytes(d.bytes) + ', ' + U.plural(d.docs.length, 'document') + ' sampled' + (d.truncated ? ' (stopped at the cap)' : '') + (d.bad ? ', ' + d.bad + ' bad line(s) skipped' : ''), ref: d }; }));
    rows.forEach(function (r) {
      var rm = el('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Remove', 'aria-label': 'Remove ' + r.name });
      rm.addEventListener('click', function () {
        if (r.kind === 'schema') { S.schemas = S.schemas.filter(function (x) { return x !== r.ref; }); } else { S.data = S.data.filter(function (x) { return x !== r.ref; }); }
        invalidate(); renderSources();
      });
      var prog = r.ref.reading ? el('progress', { max: '100', value: String(Math.round(100 * r.ref.bytesRead / Math.max(r.ref.bytes, 1))), 'aria-label': 'Reading ' + r.name }) : null;
      list.appendChild(el('li', { class: 'pk-src-row' }, [el('span', { class: 'bs-role', text: r.kind }), el('code', { text: r.name }), el('span', { class: 'text-muted-d', text: r.info }), prog, r.ref.reading ? null : rm]));
    });
    S.missing.forEach(function (m) {
      list.appendChild(el('li', { class: 'pk-src-row pk-missing' }, [el('span', { class: 'bs-role conflict', text: 'add again' }), el('code', { text: m.name }),
        el('span', { class: 'text-muted-d', text: 'was part of the saved draft (' + U.num(m.rows) + ' rows); choose it again to use its data' })]));
    });
    list.hidden = !list.firstChild;
    var rowsRead = S.data.reduce(function (a, d) { return a + d.rows; }, 0), bytesAll = S.data.reduce(function (a, d) { return a + d.bytes; }, 0) + S.schemas.reduce(function (a, s) { return a + (s.bytes || 0); }, 0);
    var sampled = S.data.reduce(function (a, d) { return a + d.docs.length; }, 0);
    total.textContent = (S.schemas.length || S.data.length) ? U.plural(S.schemas.length, 'schema') + ', ' + U.plural(S.data.length, 'data file') + ' · ' + U.num(rowsRead) + ' rows read · ' + U.bytes(bytesAll) +
      ' · ' + U.num(sampled) + ' documents sampled. Only the sample, the counts and the schemas go to the console; your files stay in this browser.' : '';
    $('[data-cancel-read]').hidden = !S.reading;
    nextB.disabled = S.reading;
  }

  function invalidate() { S.plan = null; S.planKey = ''; S.job = null; S.jobKey = ''; S.jobView = null; }

  function readAll(entries) {
    S.reading = true; S.stop = false;
    var opts = { sampleDocs: Math.min(cfg.sampleDocs, parseInt($('[data-sample-docs]').value, 10) || cfg.sampleDocs), maxRows: parseInt($('[data-max-rows]').value, 10) || S.opts.maxRows,
      maxBytes: (parseInt($('[data-max-mb]').value, 10) || S.opts.maxMb) * 1048576, maxJsonBytes: 64 * 1048576 };
    var problems = [];
    var chain = entries.reduce(function (p, e) {
      return p.then(function () {
        if (S.stop) { return; }
        var row = { name: e.name, bytes: e.file.size, bytesRead: 0, rows: 0, docs: [], reading: true, truncated: false, bad: 0 };
        var isData = /\.(jsonl|ndjson)$/i.test(e.name);
        if (isData) { S.data = S.data.filter(function (x) { return x.name !== e.name; }); S.data.push(row); }
        renderSources();
        var t = 0;
        return R.readEntry(e, opts, function (read, rows) { row.bytesRead = read; row.rows = rows; var n = Date.now(); if (n - t > 120) { t = n; renderSources(); } }, function () { return S.stop; })
          .then(function (r) {
            S.data = S.data.filter(function (x) { return x !== row; });
            S.schemas = S.schemas.filter(function (x) { return x.name !== r.name; });
            S.data = S.data.filter(function (x) { return x.name !== r.name; });
            if (r.type === 'schema') { S.schemas.push({ name: r.name, text: r.text, bytes: r.bytes }); } else { r.reading = false; S.data.push(r); }
            S.missing = S.missing.filter(function (m) { return m.name !== r.name; });
          }, function (err) {
            S.data = S.data.filter(function (x) { return x !== row; });
            if (String(err && err.message) !== 'cancelled') { problems.push(e.name + ' ' + (err && err.message ? err.message : 'could not be read')); }
          });
      });
    }, Promise.resolve());
    return chain.then(function () {
      S.reading = false; invalidate(); renderSources();
      say(problems.length ? problems.join(' · ') : S.stop ? 'Reading cancelled.' : '', problems.length > 0);
    });
  }

  function addFiles(res) {
    if (res.ignored) { say(res.ignored + ' file(s) that are not .json, .jsonl, .yaml or .yml were left out.'); }
    if (res.entries.length) { readAll(res.entries); }
  }
  $('[data-files]').addEventListener('change', function (e) { addFiles(R.fromFileList(e.target.files)); e.target.value = ''; });
  $('[data-folder]').addEventListener('change', function (e) { addFiles(R.fromFileList(e.target.files)); e.target.value = ''; });
  var drop = $('[data-drop]');
  ['dragenter', 'dragover'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
  ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('over'); }); });
  drop.addEventListener('drop', function (e) { R.fromDrop(e.dataTransfer).then(addFiles); });
  $('[data-cancel-read]').addEventListener('click', function () { S.stop = true; });
  $('[data-paste-add]').addEventListener('click', function () {
    var text = $('[data-paste]').value, name = $('[data-paste-name]').value.trim() || 'pasted.schema.json';
    if (!text.trim()) { say('Paste a schema first.', true); return; }
    try { JSON.parse(text); } catch (e) { if (!/\.ya?ml$/i.test(name)) { say('That is not valid JSON (' + e.message + '). Name it .yaml if it is YAML.', true); return; } }
    S.schemas = S.schemas.filter(function (x) { return x.name !== name; });
    S.schemas.push({ name: name, text: text, bytes: text.length });
    $('[data-paste]').value = ''; invalidate(); renderSources(); say('Added ' + name + '.');
  });
  $('[data-max-rows]').value = S.opts.maxRows; $('[data-max-mb]').value = S.opts.maxMb; $('[data-sample-docs]').value = Math.min(cfg.sampleDocs, 200);
  $('[data-max-rows]').max = cfg.readRows; $('[data-max-mb]').max = cfg.readMb; $('[data-sample-docs]').max = cfg.sampleDocs;

  // ---- 2. kinds ---------------------------------------------------------------------------------------------------------------
  function payload() {
    return { schemas: S.schemas.map(function (s) { return { name: s.name, text: s.text }; }),
      data: S.data.map(function (d) { return { name: d.name, rows: d.rows, bytes: d.bytes, docs: d.docs }; }), kindOf: S.kindOf, overrides: S.overrides, pack: S.pack,
      samples: S.samples, strip: S.strip };
  }
  var planTimer = null, planSeq = 0;
  function loadPlan(after) {
    clearTimeout(planTimer);
    var host = $('[data-kinds]'), seq = ++planSeq;
    var focus = document.activeElement && document.activeElement.getAttribute ? document.activeElement.getAttribute('data-focus') : null;
    S.busy = true; nextB.disabled = true;
    say('Planning…');
    api('POST', '/build/pack/api/plan', payload()).then(function (r) {
      if (seq !== planSeq) { return; }
      S.busy = false; nextB.disabled = false;
      if (!r.ok) { say(U.why(r), true); if (!S.plan) { U.clear(host); } return; }
      say('');
      S.plan = r.body; S.planFiles = r.body.files || [];
      ['name', 'code', 'title', 'description', 'version', 'connector'].forEach(function (k) { if (!S.pack[k] && r.body.pack[k]) { S.pack[k] = r.body.pack[k]; } });
      K.render(host, S, change, assignFile);
      if (focus) { var f = host.querySelector('[data-focus="' + focus.replace(/"/g, '') + '"]'); if (f) { f.focus(); } }
      if (after) { after(); }
    });
  }
  function change(orig, patch) {
    var o = S.overrides[orig] = S.overrides[orig] || {};
    Object.keys(patch).forEach(function (k) {
      if (k === 'links') { o.links = o.links || {}; Object.keys(patch.links).forEach(function (f) { o.links[f] = patch.links[f]; }); }
      else if (k === 'kind') {
        var old = S.plan.kinds.filter(function (x) { return (x.origKind || x.kind) === orig; })[0];
        if (old) { Object.keys(S.kindOf).forEach(function (f) { if (S.kindOf[f] === old.kind) { S.kindOf[f] = patch.kind; } }); }
        o.kind = patch.kind;
      } else { o[k] = patch[k]; }
    });
    S.job = null; S.jobView = null;
    loadPlan();
  }
  function assignFile(name, kind) {
    if (kind === '__new') { kind = name.replace(/^.*\//, '').replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'documents'; }
    S.kindOf[name] = kind; S.job = null; S.jobView = null; loadPlan();
  }

  // ---- 3. the pack ------------------------------------------------------------------------------------------------------------
  function drawPackForm() {
    var host = $('[data-pack-form]'), p = S.plan;
    U.clear(host);
    if (!p) { return; }
    function fld(label, key, hint, attrs) {
      var input = el(attrs && attrs.area ? 'textarea' : 'input', Object.assign({ class: 'studio-in' + (attrs && attrs.mono ? ' mono' : ''), autocomplete: 'off', 'data-pack-field': key }, (attrs && attrs.area) ? { rows: '3' } : { type: 'text', value: S.pack[key] || '' }));
      if (attrs && attrs.area) { input.value = S.pack[key] || ''; }
      input.addEventListener('change', function () { S.pack[key] = input.value.trim(); S.job = null; S.jobView = null; if (key === 'name') { recheck(); } });
      var id = 'pkp-' + key; input.id = id;
      return el('div', { class: 'pk-field' }, [el('label', { for: id, text: label }), input, hint ? el('p', { class: 'pk-hint text-muted-d', text: hint, id: id + '-h' }) : null]);
    }
    var warnName = p.conflicts.packName ? 'A pack named ' + S.pack.name + ' already exists on the server: deploying this bundle replaces it with a new version (the old one is kept for a rollback).' : '';
    host.appendChild(el('div', { class: 'pk-grid' }, [
      fld('Pack name', 'name', 'Lower-case letters, digits and "-". It is the folder name and the identity of the pack. ' + warnName, { mono: true }),
      fld('Pack code', 'code', 'Typed alone in the terminal to open the pack\'s overview.', { mono: true }),
      fld('Title', 'title', 'Shown in the pack switcher and the Admin pages.'),
      fld('Version', 'version', 'A plain version such as 1.0.0. Deploying an older or equal version is allowed but flagged.', { mono: true }),
      fld('Description', 'description', 'One or two sentences for the Admin → Packs list.', { area: true })]));
    var cnt = el('div', { class: 'pk-grid' });
    var sIn = el('input', { type: 'number', min: '1', max: '20', value: String(S.samples), class: 'studio-in', id: 'pkSamples' });
    sIn.addEventListener('change', function () { S.samples = Math.max(1, Math.min(20, parseInt(sIn.value, 10) || 5)); S.job = null; S.jobView = null; });
    var stIn = el('input', { type: 'number', min: '1', max: '12', value: String(S.strip), class: 'studio-in', id: 'pkStrip' });
    stIn.addEventListener('change', function () { S.strip = Math.max(1, Math.min(12, parseInt(stIn.value, 10) || 6)); S.job = null; S.jobView = null; recheck(); });
    cnt.appendChild(el('div', { class: 'pk-field' }, [el('label', { for: 'pkSamples', text: 'Samples per Sutra' }), sIn, el('p', { class: 'pk-hint text-muted-d', text: 'Real documents first, then the schema\'s examples, then synthetic ones.' })]));
    cnt.appendChild(el('div', { class: 'pk-field' }, [el('label', { for: 'pkStrip', text: 'Figures in the strip' }), stIn, el('p', { class: 'pk-hint text-muted-d', text: 'The most important numbers across the top of each view: required and described numbers first.' })]));
    host.appendChild(cnt);

    host.appendChild(el('h3', { class: 'bs-h2', text: 'Kinds, mnemonics and connectors' }));
    host.appendChild(el('p', { class: 'text-muted-d', text: 'A pack refers to its data sources by a logical connector name only; where that connector points (a folder, a lake, a topic) is set on the server, in the connector\'s own settings. Without real documents the pack serves its samples and needs no connector.' }));
    var t = el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('thead', {}, [el('tr', {}, ['Kind', 'Mnemonic', 'Source', 'Connector name'].map(function (h) { return el('th', { text: h }); }))])]);
    var tb = el('tbody');
    p.kinds.forEach(function (k) {
      var id = k.origKind || k.kind, o = S.overrides[id] = S.overrides[id] || {};
      var mn = el('input', { type: 'text', class: 'studio-in mono', value: k.mnemonic, maxlength: '8', 'aria-label': 'Mnemonic of ' + k.kind, autocomplete: 'off' });
      mn.addEventListener('change', function () { o.mnemonic = mn.value.trim().toUpperCase(); S.job = null; S.jobView = null; recheck(); });
      var clash = p.conflicts.mnemonics.indexOf(k.mnemonic) >= 0;
      var sel = U.select([{ value: 'samples', label: 'Samples only (no connector)' }, { value: 'file', label: 'File connector (JSON Lines per day)' }, { value: 'delta', label: 'Delta lake' }], k.template, { 'aria-label': 'Source of ' + k.kind });
      var cn = el('input', { type: 'text', class: 'studio-in mono', value: k.connector || '', placeholder: (S.pack.connector || (S.pack.name + '-store')), 'aria-label': 'Connector name for ' + k.kind, autocomplete: 'off' });
      cn.disabled = k.template === 'samples';
      sel.addEventListener('change', function () { o.template = sel.value; cn.disabled = sel.value === 'samples'; S.job = null; S.jobView = null; });
      cn.addEventListener('change', function () { o.connector = cn.value.trim(); S.job = null; S.jobView = null; });
      tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: k.kind }), el('td', {}, [mn, clash ? el('p', { class: 'pk-hint pk-warn', text: 'Used by another pack.' }) : null]), el('td', {}, [sel]), el('td', {}, [cn])]));
    });
    t.appendChild(tb);
    host.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
    if (p.warnings.length) {
      var d = el('details', { class: 'pk-warnbox', open: true }, [el('summary', { text: U.plural(p.warnings.length, 'thing') + ' to look at' })]);
      var ul = el('ul', { class: 'pk-warns' });
      p.warnings.forEach(function (w) { ul.appendChild(el('li', { text: w })); });
      d.appendChild(ul); host.appendChild(d);
    }
  }
  function recheck() { loadPlan(function () { if (S.step === 3) { drawPackForm(); } }); }

  // ---- 4. preview -------------------------------------------------------------------------------------------------------------
  var pollTimer = null;
  function key() { return JSON.stringify([S.schemas.map(function (s) { return [s.name, s.text.length]; }), S.data.map(function (d) { return [d.name, d.rows]; }), S.kindOf, S.overrides, S.pack, S.samples, S.strip]); }
  function startPreview() {
    var host = $('[data-preview]'), prog = $('[data-progress]'), bar = $('[data-bar]'), cancel = $('[data-cancel-job]');
    if (S.jobView && S.jobKey === key() && S.jobView.state === 'done') { mountPreview(); return; }
    U.clear(host); prog.hidden = false; cancel.hidden = false; nextB.disabled = true;
    U.say(bar, 'Starting…');
    api('POST', '/build/pack/api/jobs', payload()).then(function (r) {
      if (!r.ok) { prog.hidden = true; nextB.disabled = false; say(U.why(r), true); return; }
      S.job = r.body.id; S.jobKey = key(); S.jobView = null;
      poll();
    });
  }
  function poll() {
    clearTimeout(pollTimer);
    api('GET', '/build/pack/api/jobs/' + encodeURIComponent(S.job)).then(function (r) {
      var prog = $('[data-progress]'), bar = $('[data-bar]'), pr = $('[data-prog]');
      if (!r.ok) { prog.hidden = true; nextB.disabled = false; say(U.why(r), true); return; }
      var v = r.body;
      pr.max = Math.max(v.total, 1); pr.value = v.done;
      U.say(bar, v.state === 'running' || v.state === 'queued' ? 'Drafting Sutras: ' + v.done + ' of ' + v.total + (v.current ? ' (' + v.current + ')' : '') : '');
      if (v.state === 'queued' || v.state === 'running') { pollTimer = setTimeout(poll, 500); return; }
      prog.hidden = true; $('[data-cancel-job]').hidden = true; nextB.disabled = false;
      S.jobView = v;
      if (v.state === 'cancelled') { say('Cancelled. The ' + v.done + ' Sutras drafted so far are shown; preview again to finish.'); }
      else if (v.state === 'failed') { say(v.error || 'The preview failed.', true); }
      else if (v.error) { say(v.error, true); }
      mountPreview();
    });
  }
  function mountPreview() {
    var host = $('[data-preview]');
    PV.mount(host, S.jobView, S, {
      preview: function (sutra, sample) { return api('GET', '/build/pack/api/jobs/' + encodeURIComponent(S.job) + '/preview?sutra=' + encodeURIComponent(sutra) + '&sample=' + encodeURIComponent(sample)); },
      open: function (sutra) { return api('POST', '/build/pack/api/jobs/' + encodeURIComponent(S.job) + '/open', { sutra: sutra }); }
    });
  }
  $('[data-cancel-job]').addEventListener('click', function () {
    if (S.job) { api('DELETE', '/build/pack/api/jobs/' + encodeURIComponent(S.job)).then(function () { clearTimeout(pollTimer); poll(); }); }
  });

  // ---- 5. output --------------------------------------------------------------------------------------------------------------
  function drawOutput() {
    var host = $('[data-output]');
    U.clear(host); U.say(msg, 'Building the bundle…');
    api('GET', '/build/pack/api/jobs/' + encodeURIComponent(S.job) + '/summary').then(function (r) {
      if (!r.ok) { say(U.why(r), true); return; }
      say('');
      var b = r.body, base = '/build/pack/api/jobs/' + encodeURIComponent(S.job) + '/bundle';
      host.appendChild(el('dl', { class: 'pk-dl' }, [el('dt', { text: 'Bundle' }), el('dd', { class: 'mono', text: b.file + ' (' + U.bytes(b.bytes) + ', ' + b.files + ' files)' }),
        el('dt', { text: 'SHA-256' }), el('dd', { class: 'mono pk-sha', text: b.sha256 }),
        el('dt', { text: 'Pack' }), el('dd', { text: b.pack + ' ' + b.version + ' · ' + U.plural(S.jobView.sutras.length, 'Sutra') })]));
      if (b.synthetic.length) { host.appendChild(el('p', { class: 'pk-warn', text: U.plural(b.synthetic.length, 'Sutra') + ' tested on synthetic samples only (' + b.synthetic.join(', ') + '). They prove each Sutra renders, not that it suits your data.' })); }
      if (b.todo) { host.appendChild(el('p', { class: 'text-muted-d', text: b.todo + ' field(s) had no description in the schema; the About text says TODO there. Add "description" and generate again, or edit config/about.yaml.' })); }
      var actions = el('p', { class: 'bs-actions' });
      if (b.canAuthor) {
        actions.appendChild(el('a', { class: 'btn-pill btn-accent', href: base, download: b.file, 'data-download': true }, [el('i', { class: 'bi bi-download', 'aria-hidden': 'true' }), ' Download the bundle']));
        actions.appendChild(el('a', { class: 'btn-pill btn-ghost', href: base + '?part=sha256', download: b.file + '.sha256', text: 'Checksum file' }));
        actions.appendChild(el('a', { class: 'btn-pill btn-ghost', href: base + '?part=manifest', download: true, text: 'Manifest' }));
      } else { actions.appendChild(el('span', { class: 'pk-warn', text: 'Downloading needs a role with the author power.' })); }
      host.appendChild(actions);
      host.appendChild(el('h3', { class: 'bs-h2', text: 'Check it' }));
      host.appendChild(el('pre', { class: 'bs-yaml', tabindex: '0', 'aria-label': 'Commands to verify the bundle', text: 'python3 tools/drishti.py pack verify ' + b.file + '\nsha256sum -c ' + b.file + '.sha256' }));
      host.appendChild(el('h3', { class: 'bs-h2', text: 'Deploy it' }));
      if (b.canDeploy) {
        host.appendChild(el('p', { class: 'text-muted-d', text: 'Hands the bundle to Admin → Packs → Deploy archive: the server checks it, shows what it changes (breaking changes first), and only then deploys. The version it replaces is kept, so you can roll back there.' }));
        host.appendChild(el('p', { class: 'bs-actions' }, [el('a', { class: 'btn-pill btn-accent', href: '/admin/packs?bundle=' + encodeURIComponent(S.job) + '#deploy', 'data-deploy-link': true, text: 'Check and deploy in Admin → Packs' })]));
      } else {
        host.appendChild(el('p', { class: 'text-muted-d', text: 'Deploying needs an administrator. Send them the bundle and its checksum; they use Admin → Packs → Deploy archive.' }));
      }
      host.appendChild(el('p', { class: 'text-muted-d' }, ['The full guide, with the command line twin (', el('code', { text: 'drishti.py pack make --schema' }), '): ', el('a', { class: 'lnk', href: '/help/schema-to-pack', text: 'Schema to pack' }), '.']));
    });
  }

  // ---- drafts -----------------------------------------------------------------------------------------------------------------
  function state() {
    return { v: 1, step: S.step, schemas: S.schemas.map(function (s) { return { name: s.name, text: s.text }; }),
      files: S.data.map(function (d) { return { name: d.name, rows: d.rows, bytes: d.bytes }; }).concat(S.missing), kindOf: S.kindOf, overrides: S.overrides, pack: S.pack, samples: S.samples, strip: S.strip };
  }
  function draftName() { return S.pack.name || (S.plan && S.plan.pack && S.plan.pack.name) || 'untitled'; }
  var saveMsg = $('[data-save-msg]');
  function save(quiet) {
    if (!(S.schemas.length || S.data.length)) { if (!quiet) { U.say(saveMsg, 'Nothing to save yet.', true); } return Promise.resolve(); }
    return api('POST', '/build/pack/api/drafts', { id: S.draftId || undefined, name: draftName(), state: state() }).then(function (r) {
      if (r.ok) { S.draftId = r.body.id; U.say(saveMsg, 'Draft saved as the design "New pack: ' + r.body.name + '". Resume it from this page.'); $('[data-discard]').hidden = false; }
      else if (!quiet) { U.say(saveMsg, U.why(r), true); } else { U.say(saveMsg, 'Not saved: ' + U.why(r), true); }
    });
  }
  function autosave() { if (S.step >= 1) { save(true); } }
  $('[data-save]').addEventListener('click', function () { save(false); });
  $('[data-discard]').addEventListener('click', function () {
    if (!S.draftId) { return; }
    api('DELETE', '/build/pack/api/drafts/' + encodeURIComponent(S.draftId)).then(function () { S.draftId = null; $('[data-discard]').hidden = true; U.say(saveMsg, 'Draft discarded.'); });
  });
  function resume(id) {
    api('GET', '/build/pack/api/drafts/' + encodeURIComponent(id)).then(function (r) {
      if (!r.ok) { say(U.why(r), true); return; }
      var s = r.body.state;
      S.draftId = r.body.id; S.schemas = (s.schemas || []).map(function (x) { return { name: x.name, text: x.text, bytes: x.text.length }; }); S.data = [];
      S.missing = s.files || []; S.kindOf = s.kindOf || {}; S.overrides = s.overrides || {}; S.pack = Object.assign(S.pack, s.pack || {}); S.samples = s.samples || 5; S.strip = s.strip || 6;
      invalidate(); renderSources(); $('[data-discard]').hidden = false;
      say('Resumed "' + r.body.name + '". ' + (S.missing.length ? 'Choose the data files again to use their data; the schemas were kept.' : ''));
      show(1);
    });
  }
  api('GET', '/build/pack/api/drafts').then(function (r) {
    if (!r.ok || !(r.body.drafts || []).length) { return; }
    var box = $('[data-resume]'), sel = U.select(r.body.drafts.map(function (d) { return { value: d.id, label: d.name }; }), r.body.drafts[0].id, { 'aria-label': 'Draft to resume', id: 'pkDraft' });
    var b = el('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Resume' });
    b.addEventListener('click', function () { resume(sel.value); });
    box.appendChild(el('label', { for: 'pkDraft', text: 'Resume a draft ' })); box.appendChild(sel); box.appendChild(b); box.hidden = false;
  });

  show(1);
  renderSources();
})();
