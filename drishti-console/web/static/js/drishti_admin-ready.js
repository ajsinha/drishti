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
/* Admin → Data loads → Upload (DATA_LAKE_GUIDE.md section 3.5): the form on the left, the exact .ready on the right, the checks below, Push at the
   bottom. Every rule is the server's (the text, the field errors, the checks, the push); this file holds one person's form in this tab and
   draws what the server answers. Built from drishti-inputs only (FileDrop, Form, Select, CodeEditor). */
(function () {
  'use strict';
  var root = document.querySelector('[data-ready-page]');
  if (!root) { return; }
  var I = window.Drishti.elements.inputs;
  var API = '/admin/api/dataload';
  var $ = function (s) { return root.querySelector(s); };
  var S = { choices: null, item: null, state: null, form: null, values: {}, edited: {}, drop: null, code: null, checks: null, tick: false, timer: 0, polling: 0, seq: 0, tables: [] };

  function call(method, path, body) {
    return fetch(API + path, { method: method, credentials: 'same-origin', headers: body === undefined ? { Accept: 'application/json' } : { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); })
      .catch(function () { return { ok: false, status: 0, body: { detail: 'the console could not reach the server' } }; });
  }
  function problem(b) { return (b.code ? b.code + ' ' : '') + (b.detail || 'the server said no'); }
  function say(text, bad) { var s = $('[data-ready-said]'); s.textContent = text || ''; s.classList.toggle('t-bad', !!bad); }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text !== undefined) { e.textContent = text; } return e; }
  function num(n) { return Number(n).toLocaleString('en-US'); }
  function size(n) {
    if (n === null || n === undefined || n < 0) { return '-'; }
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
    while (n >= 1024 && i < 4) { n /= 1024; i++; }
    return (i ? n.toFixed(1) : n) + ' ' + u[i];
  }

  // ---- the file -----------------------------------------------------------------------------------------------------------------------
  function setSource(which) {
    root.setAttribute('data-source', which);
    root.querySelectorAll('[data-src]').forEach(function (b) { b.setAttribute('aria-pressed', b.getAttribute('data-src') === which ? 'true' : 'false'); });
    $('[data-pane="upload"]').hidden = which !== 'upload' || !!S.item;
    $('[data-pane="inbox"]').hidden = which !== 'inbox' || !!S.item;
  }

  function picked(file) {
    say('');
    S.drop.send(API + '/upload?file=' + encodeURIComponent(file.name)).then(function (r) {
      if (r.status >= 300 || !r.body) { S.drop.clear(); say(problem(r.body || {}), true); return; }
      begin(r.body);
    }).catch(function (e) { S.drop.clear(); if (e.message !== 'cancelled') { say(e.message, true); } });
  }

  function pickInbox(name) {
    say('');
    call('POST', '/pick', { file: name }).then(function (r) {
      if (!r.ok) { say(problem(r.body), true); return; }
      begin(r.body);
    });
  }

  function begin(state) {
    S.item = state.id;
    S.state = state;
    S.values = { mode: S.edited.mode ? S.values.mode : 'append', lake: S.values.lake || S.choices.defaultLake };
    S.edited = {};
    S.tick = false;
    var sug = state.suggest || {};
    S.inferred = {};
    if (!state.tar) {
      var hit = S.tables.filter(function (t) { return t.table === sug.table; })[0];
      if (hit) { S.values.pick = hit.id; S.values.key = hit.key || ''; S.inferred.table = true; }
      if (sug.date) { S.values.businessDate = sug.date; S.inferred.date = true; }
    }
    fileLine();
    setSource(root.getAttribute('data-source'));
    drawForm();
    poll();
  }

  function fileLine() {
    var line = $('[data-file-line]');
    line.textContent = '';
    if (!S.item) { line.hidden = true; return; }
    line.hidden = false;
    var st = S.state;
    line.appendChild(el('span', 'st st-ok', st.source === 'inbox' ? 'in inbox/' : 'uploaded'));
    line.appendChild(el('span', 'mono ib-name', st.file));
    line.appendChild(el('span', 'text-muted-d', size(st.size) + ' · ' + (st.tar ? 'tar archive' : 'one table, one date')));
    var b = el('button', 'btn-pill ib-btn', 'Choose another');
    b.type = 'button';
    b.setAttribute('data-ready-clear', '');
    line.appendChild(b);
    var w = S.state.state;
    if (w === 'inspecting') { line.appendChild(el('span', 'text-muted-d', 'SHA-256 and rows are being computed on the server: ' + num(st.rowsSoFar || 0) + ' rows so far')); }
    if (w === 'failed') { line.appendChild(el('span', 't-bad', st.error || 'the file could not be read')); }
  }

  function clearFile() {
    var id = S.item, st = S.state;
    S.item = null; S.state = null; S.checks = null; S.values = {}; S.edited = {};
    clearTimeout(S.polling);
    if (id && st && st.source === 'upload') { call('DELETE', '/upload/' + encodeURIComponent(id)); }
    S.drop.clear();
    fileLine();
    drawForm();
    drawChecks();
    readyText('');
    setPush();
    setSource(root.getAttribute('data-source'));
    loadChoices();
  }

  function poll() {
    clearTimeout(S.polling);
    if (!S.item) { return; }
    call('GET', '/item/' + encodeURIComponent(S.item)).then(function (r) {
      if (!S.item || !r.ok) { return; }
      S.state = r.body;
      fileLine();
      if (r.body.state === 'inspecting') { S.polling = setTimeout(poll, 800); return; }
      drawForm();
      refresh();
    });
  }

  // ---- the form ------------------------------------------------------------------------------------------------------------------------
  function drawForm() {
    var host = $('[data-ready-form]');
    host.textContent = '';
    if (S.form && S.form.destroy) { S.form.destroy(); }
    S.form = null;
    if (!S.item) { host.appendChild(el('p', 'text-muted-d', 'Choose a file: the form fills from its name.')); return; }
    if (S.state.tar) { drawTar(host); return; }
    var ch = S.choices;
    var props = {
      lake: { type: 'string', title: 'Lake', enum: ch.lakes, default: ch.defaultLake, description: 'The lake the rows go to.', hook: 'ready-lake', allowEmpty: false },
      pick: { type: 'string', title: 'Table', enum: S.tables.map(function (t) { return t.id; }), enumLabels: S.tables.map(function (t) { return t.label; }), hook: 'ready-table', emptyLabel: 'choose a table',
        description: S.inferred && S.inferred.table ? 'From the file name.' : 'The table the rows load into.', when: { field: 'newTable', equals: false } },
      newName: { type: 'string', title: 'New table', hook: 'ready-new-name', when: { field: 'newTable', equals: true }, description: 'It becomes a table of the pack.' },
      newTable: { type: 'boolean', title: 'Create a new table', widget: 'switch', hook: 'ready-new', description: ch.newTables ? 'Off for almost everything.' : 'This inbox does not allow new tables (drishti.dataload.new-tables).', readOnly: !ch.newTables },
      pack: { type: 'string', title: 'Pack', enum: ch.packs.map(function (p) { return p.pack; }), hook: 'ready-pack', when: { field: 'newTable', equals: true } },
      kind: { type: 'string', title: 'Kind', hook: 'ready-kind', when: { field: 'newTable', equals: true }, description: 'A new table\'s kind is its own name.' },
      businessDate: { type: 'date', title: 'Business date', hook: 'ready-date', description: S.inferred && S.inferred.date ? 'From the file name.' : 'Blank: the date in the file name, else the business today.', options: { businessOnly: false } },
      mode: { type: 'string', title: 'Mode', enum: ['append', 'replace'], widget: 'radio', hook: 'ready-mode', optionHook: 'ready-mode-value', description: 'append adds the rows; replace removes every row of this date in this table first.' },
      key: { type: 'string', title: 'Key', hook: 'ready-key', description: 'The field that identifies a row, from the contract.' },
      hold: { type: 'boolean', title: 'Hold', widget: 'switch', hook: 'ready-hold', description: 'Wait in the inbox until released.' }
    };
    S.form = new I.Form(host, { schema: { type: 'object', properties: props, order: ['lake', 'pick', 'newTable', 'newName', 'pack', 'kind', 'businessDate', 'mode', 'key', 'hold'] },
      value: Object.assign({ newTable: false, hold: false }, S.values), live: true, actions: false, label: 'The .ready file' });
    S.form.on('change', function (d) {
      S.values = Object.assign({}, S.values, d.values);
      S.edited[d.path] = true;
      if (d.path === 'pick') {
        var t = byId(d.value);
        if (t && !S.edited.key) { S.values.key = t.key || ''; S.form.setField('key', t.key || ''); }
      }
      showDerived();
      schedule();
    });
    var d = el('div', 'ready-derived');
    d.setAttribute('data-ready-derived', '');
    host.appendChild(d);
    showDerived();
  }

  function byId(id) { return S.tables.filter(function (t) { return t.id === id; })[0]; }

  function showDerived() {
    var d = $('[data-ready-derived]');
    if (!d) { return; }
    d.textContent = '';
    var t = byId(S.values.pick);
    [['Kind', t && t.kind], ['Pack', t && t.pack], ['Connector', (t && t.connector) || (t ? 'the one serving the kind' : '')]].forEach(function (p) {
      var r = el('div', 'ready-fact');
      r.appendChild(el('span', 'ib-k', p[0]));
      r.appendChild(el('span', 'mono', p[1] || '-'));
      r.setAttribute('data-ready-derived-' + p[0].toLowerCase(), '');
      d.appendChild(r);
    });
  }

  function drawTar(host) {
    var st = S.state;
    host.appendChild(el('p', 'text-muted-d', 'A tar names its own tables and dates in its folders (<table>/<YYYY-MM-DD>/); nothing overrides them, so neither is written. Read from the archive:'));
    if (st.layoutError) { host.appendChild(el('p', 't-bad', st.layoutError)); }
    var tbl = el('table', 'ready-folders');
    var head = el('tr');
    ['Table', 'Dates', 'Rows'].forEach(function (x) { head.appendChild(el('th', '', x)); });
    tbl.appendChild(head);
    (st.folders || []).forEach(function (f) {
      var tr = el('tr');
      tr.appendChild(el('td', 'mono', f.folder));
      tr.appendChild(el('td', 'mono', f.dates.join(', ')));
      tr.appendChild(el('td', 'mono', f.rows === null || f.rows === undefined ? '-' : num(f.rows)));
      tbl.appendChild(tr);
    });
    host.appendChild(tbl);
    var form = el('div');
    host.appendChild(form);
    S.form = new I.Form(form, { schema: { type: 'object', order: ['lake', 'mode', 'hold'], properties: {
      lake: { type: 'string', title: 'Lake', enum: S.choices.lakes, default: S.choices.defaultLake, hook: 'ready-lake', allowEmpty: false },
      mode: { type: 'string', title: 'Mode', enum: ['append', 'replace'], widget: 'radio', hook: 'ready-mode', optionHook: 'ready-mode-value' },
      hold: { type: 'boolean', title: 'Hold', widget: 'switch', hook: 'ready-hold' } } },
      value: { lake: S.values.lake, mode: S.values.mode, hold: false }, live: true, actions: false, label: 'The .ready file' });
    S.form.on('change', function (d) { S.values = Object.assign({}, S.values, d.values); schedule(); });
  }

  /** The form as the server's fields. */
  function fields() {
    var v = S.values, t = byId(v.pick), f = { item: S.item, lake: v.lake || null, mode: v.mode || 'append', hold: !!v.hold, ackReplace: S.tick };
    if (S.state && S.state.tar) { return f; }
    f.businessDate = v.businessDate || null;
    f.key = v.key || null;
    if (v.newTable) {
      f.newTable = true; f.table = v.newName || null; f.pack = v.pack || null; f.kind = v.kind || null;
    } else if (t) {
      f.table = t.table; f.pack = t.pack; f.kind = t.kind; f.connector = t.connector || null;
    }
    return f;
  }

  function schedule() { clearTimeout(S.timer); S.timer = setTimeout(refresh, 300); }

  function refresh() {
    if (!S.item || S.state.state === 'inspecting') { return; }
    var seq = ++S.seq, body = fields();
    call('POST', '/ready/preview', body).then(function (r) {
      if (seq !== S.seq) { return; }
      if (!r.ok) { say(problem(r.body), true); return; }
      readyText(r.body.text || '');
      fieldErrors(r.body.errors || {});
    });
    clearTimeout(S.checkTimer);
    S.checkTimer = setTimeout(function () {
      var at = S.seq;
      call('POST', '/ready/check', body).then(function (r) {
        if (at !== S.seq) { return; }
        if (!r.ok) { say(problem(r.body), true); return; }
        S.checks = r.body;
        drawChecks();
        setPush();
      });
    }, 500);
  }

  function fieldErrors(errors) {
    var host = $('[data-ready-errors]');
    host.textContent = '';
    Object.keys(errors).forEach(function (k) { host.appendChild(el('li', 't-bad', errors[k])); });
  }

  function readyText(t) {
    $('[data-ready-name]').textContent = S.state ? S.state.file + '.ready' : '<data file>.ready';
    if (S.code) { S.code.setValue(t); }
    root.querySelector('[data-ready-text]').value = t;
    root.querySelectorAll('[data-ready-copy],[data-ready-dl]').forEach(function (b) { b.disabled = !t; });
  }

  // ---- the checks and Push -------------------------------------------------------------------------------------------------------------
  function drawChecks() {
    var host = $('[data-ready-checks]'), tally = $('[data-ready-tally]');
    host.textContent = '';
    tally.textContent = '';
    if (!S.checks) { host.appendChild(el('p', 'text-muted-d', S.item ? 'The checks run when the file has been read.' : 'Choose a file: a sample is checked here, before anything reaches the inbox.')); return; }
    var n = { pass: 0, warning: 0, refused: 0 };
    S.checks.checks.forEach(function (c) {
      n[c.status]++;
      var row = el('div', 'ready-chk ready-' + c.status);
      row.setAttribute('data-check', c.status);
      row.appendChild(el('span', 'st ' + (c.status === 'pass' ? 'st-ok' : c.status === 'warning' ? 'st-warn' : 'st-bad'), c.status));
      var text = el('div');
      text.appendChild(el('div', '', c.text));
      if (c.fix) { text.appendChild(el('div', 'text-muted-d', c.fix)); }
      if (c.field) {
        var b = el('button', 'btn-pill ib-btn', 'Use ' + c.value);
        b.type = 'button';
        b.setAttribute('data-ready-fix', c.value);
        b.setAttribute('data-field', c.field);
        text.appendChild(b);
      }
      if (c.tick) {
        var box = el('div');
        var f = new I.Form(box, { schema: { type: 'object', properties: { yes: { type: 'boolean', title: 'Yes: replace the rows named above', hook: 'ready-tick' } } }, value: { yes: S.tick }, live: true, actions: false, label: 'Confirm the replace' });
        f.on('change', function (d) { S.tick = !!d.values.yes; setPush(); });
        text.appendChild(box);
      }
      row.appendChild(text);
      host.appendChild(row);
    });
    tally.appendChild(el('span', 'st st-ok', n.pass + ' passed'));
    tally.appendChild(el('span', 'st st-warn', n.warning + ' warning' + (n.warning === 1 ? '' : 's')));
    if (n.refused) { tally.appendChild(el('span', 'st st-bad', n.refused + ' refused')); }
  }

  function canPush() {
    if (!S.item || !S.checks || !S.choices.on || S.state.state !== 'ready' || S.done) { return false; }
    if (S.checks.blocked) { return false; }
    return !S.checks.tick || S.tick;
  }

  function setPush() {
    var b = $('[data-ready-push]'), why = $('[data-ready-why]');
    b.disabled = !canPush();
    why.textContent = S.choices && !S.choices.on ? 'The data inbox is off.' : S.checks && S.checks.blocked ? 'Push is blocked: fix the refusal above.' : S.checks && S.checks.tick && !S.tick ? 'Tick the replace above, or use append.' : '';
  }

  function push() {
    say('');
    $('[data-ready-push]').disabled = true;
    call('POST', '/ready/push', fields()).then(function (r) {
      if (!r.ok) { say(problem(r.body), true); refresh(); return; }
      S.done = r.body;
      S.item = null;
      var bar = $('[data-ready-pushbar]');
      bar.textContent = '';
      var d = el('div', 'ready-done');
      d.setAttribute('data-ready-done', '');
      d.appendChild(el('h3', '', 'Pushed to the inbox'));
      d.appendChild(el('div', '', r.body.file + ' and its .ready are in the inbox on ' + r.body.node + (r.body.hold ? ': held until released.' : '.' + (r.body.nextCycleText ? ' Next cycle: ' + r.body.nextCycleText + '.' : ''))));
      var a = el('a', 'btn-pill btn-accent ib-btn', 'Follow it on the Inbox board');
      a.href = '/admin/loads/inbox';
      a.setAttribute('data-ready-board', '');
      d.appendChild(a);
      bar.appendChild(d);
    });
  }

  // ---- the page -------------------------------------------------------------------------------------------------------------------------
  function drawInbox() {
    var host = $('[data-pane="inbox"]');
    host.textContent = '';
    var w = S.choices.waiting || [];
    if (!w.length) { host.appendChild(el('p', 'text-muted-d', 'No file in the inbox folder is waiting without a .ready.')); return; }
    var t = el('table', 'ready-folders');
    var head = el('tr');
    ['In inbox/, no .ready yet', 'Size', 'Unchanged for', ''].forEach(function (x) { head.appendChild(el('th', '', x)); });
    t.appendChild(head);
    w.forEach(function (f) {
      var tr = el('tr');
      tr.appendChild(el('td', 'mono', f.file));
      tr.appendChild(el('td', 'mono', size(f.size)));
      tr.appendChild(el('td', '', f.stable ? Math.round(f.unchangedSeconds / 60) + ' min' : 'still being written'));
      var td = el('td');
      if (f.stable) { var b = el('button', 'btn-pill ib-btn', 'Pick'); b.type = 'button'; b.setAttribute('data-ready-pick', f.file); td.appendChild(b); }
      tr.appendChild(td);
      t.appendChild(tr);
    });
    host.appendChild(t);
  }

  function loadChoices() {
    return call('GET', '/choices').then(function (r) {
      if (!r.ok) { say(problem(r.body), true); return null; }
      S.choices = r.body;
      S.tables = (r.body.tables || []).map(function (t) { return Object.assign({ id: t.pack + '/' + t.kind + '/' + t.table }, t); });
      facts();
      drawInbox();
      setPush();
      return r.body;
    });
  }

  function facts() {
    var c = S.choices, f = $('[data-ready-facts]');
    f.textContent = '';
    [['Data inbox', c.on ? 'on' : 'off'], ['Owner node', c.node], ['Next cycle', c.nextCycleText || '-'], ['Free in inbox folder', size(c.freeBytes)], ['Largest upload', c.maxGb + ' GB']].forEach(function (p) {
      var s = el('div', 'ib-fact'); s.appendChild(el('span', 'ib-k', p[0])); s.appendChild(el('span', 'mono', String(p[1]))); f.appendChild(s);
    });
    if (!c.on) {
      var a = el('a', '', 'Admin → Engines');
      a.href = '/admin/engines';
      var w = el('p', 'adm-warn', 'The data inbox is off: nothing can be pushed. Turn it on in ');
      w.appendChild(a);
      f.appendChild(w);
    }
  }

  function prefill(text) {
    call('POST', '/ready/read', { text: text }).then(function (r) {
      if (!r.ok) { say(problem(r.body), true); return; }
      if (r.body.refused) { say(r.body.refused, true); return; }
      var b = r.body, t = S.tables.filter(function (x) { return x.table === b.table || (b.kind && x.kind === b.kind && (!b.pack || x.pack === b.pack)); })[0];
      ['lake', 'mode', 'key', 'hold'].forEach(function (k) { if (b[k] !== null && b[k] !== undefined) { S.values[k] = b[k]; } });
      if (t) { S.values.pick = t.id; }
      if (S.item) { drawForm(); refresh(); say('Filled from the .ready: its SHA-256, rows and date stay this file\'s own.'); }
    });
  }

  root.addEventListener('click', function (e) {
    var t = e.target.closest('button,a');
    if (!t) { return; }
    if (t.hasAttribute('data-src')) { setSource(t.getAttribute('data-src')); }
    else if (t.hasAttribute('data-ready-clear')) { clearFile(); }
    else if (t.hasAttribute('data-ready-pick')) { pickInbox(t.getAttribute('data-ready-pick')); }
    else if (t.hasAttribute('data-ready-push')) { push(); }
    else if (t.hasAttribute('data-ready-fix')) {
      var v = t.getAttribute('data-ready-fix'), field = t.getAttribute('data-field');
      S.values[field] = v;
      S.form.setField(field, v);
      S.tick = false;
      refresh();
    } else if (t.hasAttribute('data-ready-copy')) {
      var txt = root.querySelector('[data-ready-text]').value;
      if (navigator.clipboard) { navigator.clipboard.writeText(txt); }
      t.textContent = 'Copied';
      setTimeout(function () { t.textContent = 'Copy'; }, 1200);
    } else if (t.hasAttribute('data-ready-dl')) {
      var u = URL.createObjectURL(new Blob([root.querySelector('[data-ready-text]').value], { type: 'text/plain' })), a = document.createElement('a');
      a.href = u; a.download = S.state ? S.state.file + '.ready' : 'file.ready'; a.click(); URL.revokeObjectURL(u);
    } else if (t.hasAttribute('data-ready-load')) {
      $('[data-ready-load-file]').component.input.click();
    }
  });

  loadChoices().then(function (c) {
    if (!c) { return; }
    S.drop = new I.FileDrop($('[data-ready-drop]'), { label: 'Drop a data file here', accept: c.accepted });
    S.drop.on('pick', function (d) { picked(d.file); });
    S.drop.on('refused', function (d) { say(d.reason, true); });
    var loader = new I.FileDrop($('[data-ready-load-file]'), { label: 'Upload a .ready to fill the form', accept: ['.ready'] });
    $('[data-ready-load-file]').component = loader;
    loader.on('pick', function (d) { d.file.text().then(function (t) { loader.clear(); prefill(t); }); });
    S.code = new I.CodeEditor($('[data-ready-code]'), { value: '', mode: 'yaml', readOnly: true, height: 260, lineNumbers: false, label: 'The .ready text', lineWrapping: true });
    setSource('upload');
    drawChecks();
    var q = new URLSearchParams(location.search);
    if (q.get('inbox')) { setSource('inbox'); pickInbox(q.get('inbox')); }
    if (q.get('item')) { call('GET', '/item/' + encodeURIComponent(q.get('item'))).then(function (r) { if (r.ok) { begin(r.body); } else { say(problem(r.body), true); } }); }
    drawForm();
  });
}());
