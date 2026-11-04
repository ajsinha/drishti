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
  var ASOF = '@as-of';

  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('t-bad', !!bad); }
  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }
  function option(sel, value, label, selected) {
    var o = el('option', { value: value }, label);
    if (selected) { o.selected = true; }
    sel.appendChild(o);
  }
  function show(step) { $('[data-step="' + step + '"]').hidden = false; }
  function fmt(n) { return Number(n || 0).toLocaleString(); }
  function mb(n) { return (n / 1048576).toFixed(1) + ' MB'; }
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
  $('[data-file]').addEventListener('change', function (ev) {
    var f = ev.target.files && ev.target.files[0];
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
    var key = $('[data-key]'), date = $('[data-date]');
    key.textContent = ''; date.textContent = '';
    var scalar = scan.fields.filter(function (f) { return !f.types.array && !f.types.object; });
    scalar.forEach(function (f) {
      option(key, f.path, f.path + (f.many || f.distinct === f.present ? '' : ' (repeats)'), f.path === (s.key || [])[0]);
      if (f.dateLike) { option(date, f.path, f.path, f.path === (s.date || [])[0]); }
    });
    option(date, ASOF, 'None: one date for the whole file', !(s.date || []).length);
    syncAsOf();
    var body = $('[data-fields] tbody');
    body.textContent = '';
    var classes = s.partitionBy || [];
    scan.fields.forEach(function (f, i) {
      var tr = el('tr');
      tr.appendChild(el('th', { scope: 'row', 'class': 'mono' }, f.path));
      tr.appendChild(el('td', {}, fmt(f.present)));
      tr.appendChild(el('td', {}, f.many ? 'many' : (f.types.array ? 'list' : f.types.object ? 'object' : fmt(f.distinct))));
      tr.appendChild(el('td', { 'class': 'lk-top' }, (f.top || []).slice(0, 4).map(function (t) { return (t[0] === '' ? '(blank)' : t[0]) + ' ×' + t[1]; }).join(', ')));
      var nested = f.types.array || f.types.object;
      [['classifier', classes.indexOf(f.path) === 0], ['promote', false]].forEach(function (c) {
        var td = el('td');
        if (!nested) {
          var box = el('input', { type: 'checkbox', 'data-pick': c[0], value: f.path, id: 'lk-' + c[0] + '-' + i });
          box.checked = c[1];
          td.appendChild(box);
          td.appendChild(el('label', { 'for': 'lk-' + c[0] + '-' + i, 'class': 'visually-hidden' }, c[0] + ' ' + f.path));
        }
        tr.appendChild(td);
      });
      body.appendChild(tr);
    });
    show('fields');
  }
  function syncAsOf() {
    var on = $('[data-date]').value === ASOF;
    $('[data-asof-wrap]').hidden = !on;
    if (on && !$('[data-asof]').value) { $('[data-asof]').value = new Date().toISOString().slice(0, 10); }
  }
  $('[data-date]').addEventListener('change', syncAsOf);
  function picked(kind) {
    return Array.prototype.map.call(root.querySelectorAll('[data-pick="' + kind + '"]:checked'), function (b) { return b.value; });
  }

  // 3. target ------------------------------------------------------------------------------------------------------
  function targets() {
    return api('GET', '/admin/api/lakes/targets').then(function (t) {
      state.targets = t;
      var table = $('[data-table]'), rootSel = $('[data-root]'), pack = $('[data-pack]');
      table.textContent = ''; rootSel.textContent = ''; pack.textContent = '';
      option(table, '', 'A new lake (table)', true);
      t.tables.forEach(function (x, i) { option(table, String(i), x.domain + ' / ' + x.kind + (x.partitionBy.length ? ' (by ' + x.partitionBy.join(', ') + ')' : '') + ' in ' + x.root); });
      t.roots.forEach(function (r, i) { option(rootSel, r, r, i === 0); });
      option(pack, '', 'No pack (bind and announce later)', !t.connectors.length);
      t.connectors.forEach(function (c, i) {
        (c.packs.length ? c.packs : ['']).forEach(function (p) {
          option(pack, JSON.stringify({ pack: p, connector: c.name }), (p || '(no pack)') + ' · connector ' + c.name + (c.kinds.length ? ' (' + c.kinds.join(', ') + ')' : ''), i === 0);
        });
      });
      if (!$('[data-kind]').value && state.upload) { $('[data-kind]').value = state.upload.name.replace(/\..*$/, '').toLowerCase().replace(/[^a-z0-9_-]/g, '-').slice(0, 40); }
      show('target');
    }).catch(function (e) { say(e.message, true); });
  }
  $('[data-table]').addEventListener('change', function (ev) {
    var x = state.targets.tables[Number(ev.target.value)];
    var warn = $('[data-target-warn]');
    warn.hidden = true;
    if (!x) { $('[data-domain]').disabled = false; $('[data-kind]').disabled = false; $('[data-root]').disabled = false; return; }
    $('[data-root]').value = x.root; $('[data-domain]').value = x.domain; $('[data-kind]').value = x.kind;
    ['[data-domain]', '[data-kind]', '[data-root]'].forEach(function (s) { $(s).disabled = true; });
    Array.prototype.forEach.call(root.querySelectorAll('[data-pick="classifier"]'), function (b) { b.checked = x.partitionBy.indexOf(b.value.replace(/\./g, '__')) >= 0; });
    warn.textContent = 'This lake is partitioned by ' + (['business_date'].concat(x.partitionBy).join(', ')) + '; the classifiers are set to match it.';
    warn.hidden = false;
  });

  // 4. run -------------------------------------------------------------------------------------------------------------
  $('[data-run]').addEventListener('click', function () {
    var chosen = $('[data-pack]').value ? JSON.parse($('[data-pack]').value) : {};
    var asOf = $('[data-date]').value === ASOF;
    var body = { upload: state.upload && state.upload.id, root: $('[data-root]').value, domain: $('[data-domain]').value.trim(), kind: $('[data-kind]').value.trim(),
      key: $('[data-key]').value, date: asOf ? null : $('[data-date]').value, asOf: asOf ? $('[data-asof]').value : null, partitionBy: picked('classifier'),
      promote: picked('promote'), mode: $('[data-mode]').value, pack: chosen.pack || null, connector: chosen.connector || null,
      bind: !!chosen.connector && $('[data-bind]').checked, signal: !!chosen.pack && $('[data-signal]').checked };
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
