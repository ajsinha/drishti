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
/* Admin → Data loads → Lakes: upload (streamed, with progress), the file's fields with suggestions, the target (lake, pack), the
   build job's progress and its summary. Everything goes through same-origin /admin/api/lakes/... calls; the page holds no data
   beyond the scan's field list. */
(function () {
  'use strict';
  var root = document.querySelector('[data-lakes]');
  if (!root) { return; }
  var $ = function (sel) { return root.querySelector(sel); };
  var msg = $('[data-msg]');
  var state = { upload: null, scan: null, targets: null, job: null };

  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('t-bad', !!bad); }
  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }
  // the steps' fields: drishti-inputs' Forms from the server's schemas (core/admin_forms.py); the lists are filled here as the server answers
  var I = window.Drishti.elements.inputs, KF = I.formOf($('[data-lake-fields]')), TF = I.formOf($('[data-lake-target]'));
  function fileForm() { return I.formOf($('[data-field-of=file]')); }
  function show(step) { $('[data-step="' + step + '"]').hidden = false; }
  function fmt(n) { return window.Drishti.elements.core.formatNumber(Number(n || 0), { maxDecimals: 3 }); }
  function mb(n) { return window.Drishti.elements.core.formatBytes(n); }
  function api(method, url, body) {
    return fetch(url, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json', 'Accept': 'application/json' } : { 'Accept': 'application/json' },
      body: body ? JSON.stringify(body) : undefined }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) { throw new Error((j && j.detail) || ('the console said ' + r.status)); }
        return j;
      });
    });
  }

  // 1. the file ---------------------------------------------------------------------------------------------------
  fileForm().on('change', function () {
    var input = $('[data-file]'), f = input && input.files && input.files[0];
    if (!f) { return; }
    var max = Number(root.getAttribute('data-max-mb')) * 1048576;
    if (f.size > max) { say('The file is ' + mb(f.size) + ', over the ' + root.getAttribute('data-max-mb') + ' MB this console takes (lakes.max_upload_mb).', true); return; }
    var box = $('[data-upload-progress]'), bar = $('[data-bar]'), text = $('[data-upload-text]');
    box.hidden = false;
    say('');
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/admin/api/lakes/upload?name=' + encodeURIComponent(f.name));
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.upload.onprogress = function (e) {
      if (e.lengthComputable) { bar.value = Math.round(100 * e.loaded / e.total); text.textContent = 'Uploading ' + mb(e.loaded) + ' of ' + mb(e.total); }
    };
    xhr.onload = function () {
      var j = {};
      try { j = JSON.parse(xhr.responseText); } catch (e) { j = {}; }
      if (xhr.status !== 200) { say(j.detail || ('Upload failed (' + xhr.status + ')'), true); text.textContent = 'Not uploaded'; return; }
      state.upload = j;
      bar.value = 100;
      text.textContent = 'Uploaded ' + j.name + ' (' + mb(j.size) + '). Reading its fields…';
      api('POST', '/admin/api/lakes/uploads/' + encodeURIComponent(j.id) + '/scan').then(function (scan) {
        state.scan = scan;
        text.textContent = 'Uploaded ' + j.name + ' (' + mb(j.size) + ').';
        fields(scan);
        return targets();
      }).catch(function (e) { say(e.message, true); });
    };
    xhr.onerror = function () { say('The upload was interrupted; choose the file again.', true); };
    xhr.send(f);
  });

  // 2. fields -------------------------------------------------------------------------------------------------------
  function fields(scan) {
    var s = scan.suggest || {};
    $('[data-scan-note]').textContent = 'Read ' + fmt(scan.documents) + ' documents' + (scan.limited ? ' (the first ones; the build reads them all)' : '') +
      (scan.rejected ? ', ' + fmt(scan.rejected) + ' not usable' : '') + '. Suggested choices are selected; change any of them.';
    var scalar = scan.fields.filter(function (f) { return !f.types.array && !f.types.object; });
    KF.setOptions('key', scalar.map(function (f) { return f.path; }), scalar.map(function (f) { return f.path + (f.many || f.distinct === f.present ? '' : ' (repeats)'); }));
    KF.setField('key', (s.key || [])[0] && scalar.some(function (f) { return f.path === s.key[0]; }) ? s.key[0] : (scalar[0] ? scalar[0].path : ''));
    businessDate();
    var classes = s.partitionBy || [];
    state.rows = scan.fields.map(function (f) {                       // the file's fields as drishti-grid rows: Classifier and Promote are checkbox cells
      var nested = !!(f.types.array || f.types.object);
      return { path: f.path, present: f.present, values: f.many ? 'many' : (f.types.array ? 'list' : f.types.object ? 'object' : fmt(f.distinct)),
        top: (f.top || []).slice(0, 4).map(function (t) { return (t[0] === '' ? '(blank)' : t[0]) + ' ×' + t[1]; }).join(', '),
        nested: nested, classifier: !nested && classes.indexOf(f.path) === 0, promote: false };
    });
    show('fields');
    drawFields();
  }
  /** The fields' grid (drishti-grid with cell editing: Space, Enter or a double click turns a checkbox; a nested field has none). */
  function drawFields() {
    var host = $('[data-fields]'), G = window.Drishti.elements.grid, scalar = function (r) { return !r.nested; };
    if (state.grid) { state.grid.setRows(state.rows); return; }
    state.grid = new G.Grid(host, { rows: state.rows, getRowId: function (r) { return r.path; }, getRowData: function (r) { return { field: r.path }; },
      layout: 'wells', density: 'compact', chrome: false, checkboxes: false, rowSelection: 'none', clientAggregation: false, label: 'The file\'s fields: classifiers become partition folders, promoted fields become columns',
      columns: [
        { field: 'path', header: 'Field', width: 220, cellClass: 'mono' },
        { field: 'present', header: 'Present', type: 'number', width: 100, enableValue: false, aggFunc: null },
        { field: 'values', header: 'Values', width: 100 },
        { field: 'top', header: 'Most frequent', width: 300 },
        { field: 'classifier', header: 'Classifier', type: 'boolean', width: 100, editable: scalar },
        { field: 'promote', header: 'Promote', type: 'boolean', width: 100, editable: scalar }] });
    state.grid.on('editTransaction', function (tx) {                    // each tick (or its undo) kept in the page's rows
      tx.edits.forEach(function (e) { state.rows.forEach(function (r) { if (r.path === e.rowId) { r[e.field] = e.value; } }); });
    });
    host.style.height = (Math.min(state.rows.length, 16) * state.grid.model.viewport.rowHeight + 64) + 'px';
  }
  // The business date by the load rules (docs/guides/DATA_LAKE_GUIDE.md): a date given here, else the file name's, else the
  // business today; never a field of the rows.
  function businessDate() {
    var u = state.upload || {}, given = KF.getField('asOf');
    $('[data-date-said]').textContent = given ? given + ', the date given here'
      : u.nameDate ? u.nameDate + ', from the file name (' + u.name + ')'
      : (u.businessToday || 'today') + ', the business today: the file name carries no date (name it <table>.YYYY-MM-DD.jsonl, or give one)';
  }
  KF.on('change', function (e) { if (e.path === 'asOf') { businessDate(); } });
  function picked(kind) {
    return (state.rows || []).filter(function (r) { return !r.nested && r[kind]; }).map(function (r) { return r.path; });   // the grid edits its rows in place
  }

  // 3. target ------------------------------------------------------------------------------------------------------
  function targets() {
    return api('GET', '/admin/api/lakes/targets').then(function (t) {
      state.targets = t;
      var packs = [['', 'No pack (bind and announce later)']], firstPack = '';
      t.connectors.forEach(function (c, i) {
        (c.packs.length ? c.packs : ['']).forEach(function (p) {
          var v = JSON.stringify({ pack: p, connector: c.name });
          if (i === 0 && !firstPack) { firstPack = v; }
          packs.push([v, (p || '(no pack)') + ' · connector ' + c.name + (c.kinds.length ? ' (' + c.kinds.join(', ') + ')' : '')]);
        });
      });
      var P = TF.schema.properties;
      P.table['enum'] = [''].concat(t.tables.map(function (x, i) { return String(i); }));
      P.table.enumLabels = ['A new lake (table)'].concat(t.tables.map(function (x) { return x.domain + ' / ' + x.kind + (x.partitionBy.length ? ' (by ' + x.partitionBy.join(', ') + ')' : '') + ' in ' + x.root; }));
      P.root['enum'] = t.roots.slice();
      P.pack['enum'] = packs.map(function (p) { return p[0]; }); P.pack.enumLabels = packs.map(function (p) { return p[1]; });
      var kind = TF.getField('kind') || (state.upload ? state.upload.name.replace(/\..*$/, '').toLowerCase().replace(/[^a-z0-9_-]/g, '-').slice(0, 40) : '');
      TF.setValue(Object.assign({}, TF.values, { table: '', root: t.roots[0] || '', kind: kind, pack: firstPack }));
      show('target');
    }).catch(function (e) { say(e.message, true); });
  }
  function fixed(on, values) {                                          // an existing lake fixes its root, domain and kind
    ['root', 'domain', 'kind'].forEach(function (k) { TF.schema.properties[k].readOnly = on; });
    TF.setValue(Object.assign({}, TF.values, values || {}));
  }
  TF.on('change', function (ev) {
    if (ev.path !== 'table') { return; }
    var x = ev.value === '' || ev.value === undefined ? null : state.targets.tables[Number(ev.value)];
    var warn = $('[data-target-warn]');
    warn.hidden = true;
    if (!x) { fixed(false); return; }
    fixed(true, { root: x.root, domain: x.domain, kind: x.kind });
    state.rows = (state.rows || []).map(function (r) { return Object.assign({}, r, { classifier: !r.nested && x.partitionBy.indexOf(r.path.replace(/\./g, '__')) >= 0 }); });
    if (state.grid) { drawFields(); }
    warn.textContent = 'This lake is partitioned by ' + (['business_date'].concat(x.partitionBy).join(', ')) + '; the classifiers are set to match it.';
    warn.hidden = false;
  });

  // 4. run -------------------------------------------------------------------------------------------------------------
  $('[data-run]').addEventListener('click', function () {
    var t = function (k) { return TF.getField(k); }, chosen = t('pack') ? JSON.parse(t('pack')) : {};
    var body = { upload: state.upload && state.upload.id, root: t('root') || '', domain: String(t('domain') || '').trim(), kind: String(t('kind') || '').trim(),
      key: KF.getField('key') || '', asOf: KF.getField('asOf') || null, partitionBy: picked('classifier'),
      promote: picked('promote'), mode: t('mode'), pack: chosen.pack || null, connector: chosen.connector || null,
      bind: !!chosen.connector && t('bind') === true, signal: !!chosen.pack && t('signal') === true };     // an unticked box is false
    say('');
    $('[data-run]').disabled = true;
    api('POST', '/admin/api/lakes/jobs', body).then(function (job) {
      state.job = job;
      show('run');
      $('[data-step="run"]').scrollIntoView({ block: 'start' });
      poll();
    }).catch(function (e) { say(e.message, true); $('[data-run]').disabled = false; });
  });
  function poll() {
    api('GET', '/admin/api/lakes/jobs/' + encodeURIComponent(state.job.id)).then(function (j) {
      var p = j.progress || {}, bar = $('[data-run-bar]'), text = $('[data-run-text]');
      if (p.phase === 'reading' || p.phase === 'read') {
        bar.value = p.bytesTotal ? Math.round(60 * p.bytesRead / p.bytesTotal) : 5;
        text.textContent = 'Reading: ' + fmt(p.documents) + ' documents' + (p.rejected ? ', ' + fmt(p.rejected) + ' rejected' : '');
      } else if (p.phase === 'writing') {
        bar.value = 60 + (p.partsTotal ? Math.round(40 * p.partsDone / p.partsTotal) : 0);
        text.textContent = 'Writing with ' + p.engine + ': ' + fmt(p.partsDone) + ' of ' + fmt(p.partsTotal) + ' partitions';
      }
      if (j.state === 'running') { setTimeout(poll, 800); return; }
      bar.value = 100;
      text.textContent = j.state === 'failed' ? 'Failed' : 'Done in ' + j.seconds + ' s';
      result(j);
      $('[data-run]').disabled = false;
    }).catch(function (e) { say(e.message, true); $('[data-run]').disabled = false; });
  }
  function result(j) {
    var box = $('[data-result]'), s = j.summary || {};
    box.textContent = '';
    if (j.state === 'failed') { box.appendChild(el('p', { 'class': 'adm-warn', role: 'alert' }, 'The build failed: ' + (j.error || 'see the run log') + '. Nothing was committed.')); return; }
    box.appendChild(el('p', { 'class': 'loads-sum' }, (s.status === 'ok' ? 'Loaded ' : 'Loaded with rejects: ') + fmt(s.accepted) + ' rows from ' + fmt(s.documents) +
      ' documents into ' + s.table + ' (engine ' + s.engine + ').'));
    var t = el('table', { 'class': 'tbl adm-tbl' });
    t.appendChild(el('caption', { 'class': 'visually-hidden' }, 'Rows per business date and partition'));
    var head = el('tr');
    var names = (s.partitions && s.partitions[0]) ? Object.keys(s.partitions[0].values) : [];
    ['Business date'].concat(names).concat(['Rows']).forEach(function (h) { head.appendChild(el('th', { scope: 'col' }, h)); });
    var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
    var tb = el('tbody');
    (s.partitions || []).forEach(function (x) {
      var tr = el('tr');
      tr.appendChild(el('td', { 'class': 'mono' }, x.date));
      names.forEach(function (n) { tr.appendChild(el('td', {}, x.values[n] === null ? '(none)' : x.values[n])); });
      tr.appendChild(el('td', {}, fmt(x.rows)));
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    var wrap = el('div', { 'class': 'tbl-wrap' }); wrap.appendChild(t); box.appendChild(wrap);
    Object.keys((s.rejects || {}).byReason || {}).forEach(function (r) { box.appendChild(el('p', { 'class': 'lk-warn' }, fmt(s.rejects.byReason[r]) + ' rejected: ' + r)); });
    if (j.bind) { box.appendChild(el('p', { 'class': j.bind.ok ? 'loads-sum' : 'lk-warn' }, j.bind.ok ? 'Connector ' + j.bind.connector + (j.bind.created ? ' created' : ' updated') + ' to read this lake.' : 'The connector was not bound: ' + j.bind.error)); }
    (j.signal || []).forEach(function (x) { box.appendChild(el('p', { 'class': x.ok ? '' : 'lk-warn' }, 'Load of ' + x.date + (x.ok ? ' announced' + (x.verified ? ' (' + x.verified + ')' : '') : ' not announced: ' + x.error))); });
    if (s.sampleId) {
      var a = el('a', { href: '/v/' + encodeURIComponent(s.kind) + '/' + encodeURIComponent(s.sampleId) + (s.sampleDate ? '?asOf=' + s.sampleDate : ''), 'class': 'btn-pill btn-accent' }, 'Open ' + s.kind + ' ' + s.sampleId);
      var p = el('p'); p.appendChild(a); box.appendChild(p);
    }
    if (s.summaryFile) { box.appendChild(el('p', { 'class': 'text-muted-d' }, 'Summary: ' + s.summaryFile)); }
  }
}());
