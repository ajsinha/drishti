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
/* Sutra Studio: a YAML editor for Sutras (Rachana 1) with live preview. studio-assist.js completes keys, values,
   functions and document fields, checks the YAML against the server's Rachana schema while you type and shows
   field help; the Summary tab reads the Sutra back as a page. Ctrl+Enter previews the unsaved Sutra against the
   chosen entity (or pasted JSON); problems list their line (click to jump); "Start from inference" loads what
   inference makes of the entity as an editable Sutra; Save writes the file (authors, where enabled). */
(function () {
  'use strict';
  var root = document.querySelector('[data-studio]');
  if (!root) { return; }
  var ta = root.querySelector('[data-src]'), out = root.querySelector('[data-out]'), status = root.querySelector('[data-status]');
  var list = root.querySelector('[data-problems]'), kindIn = root.querySelector('[data-kind]'), idIn = root.querySelector('[data-id]');
  var jsonTa = root.querySelector('[data-json]'), useJson = root.querySelector('[data-use-json]');
  var editor = window.CodeMirror ? window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, indentUnit: 2, tabSize: 2,
    gutters: ['studio-gutter', 'CodeMirror-linenumbers'],
    extraKeys: { 'Ctrl-Enter': preview, 'Cmd-Enter': preview, Tab: function (cm) { cm.replaceSelection('  '); }, Enter: newline } }) : null;
  /** Enter keeps YAML's indentation: under "- key: v" at the key, after "key:" one level deeper. */
  function newline(cm) {
    var c = cm.getCursor(), before = cm.getLine(c.line).slice(0, c.ch).replace(/\s+#.*$/, '');
    var m = before.match(/^(\s*)((?:-\s+)*)/), n = m[1].length + m[2].length, rest = before.slice(n);
    if (m[2] && (/^[{["']/.test(rest) || !/^[^\s#][^:#]*:(\s|$)/.test(rest))) { n = m[1].length; } // "- { … }" or "- scalar": the next item
    if (/^\s*(?:-\s+)*[^\s#][^:#]*:\s*([|>][-+0-9]*)?\s*$/.test(before) && !/[{[]/.test(before)) { n += 2; }
    cm.replaceSelection('\n' + ' '.repeat(n), 'end', '+input');
  }
  var serverProblems = [], liveProblems = [];
  var assist = editor && window.drishtiAssist ? window.drishtiAssist.attach(editor, {
    say: function (m, bad) { say(m, bad); },
    helpEl: root.querySelector('[data-field-help]'),
    getDoc: sampleDoc,
    onCheck: function (ps) { liveProblems = ps; showProblems(); }
  }) : null;
  function text() { return editor ? editor.getValue() : ta.value; }
  function setText(t) { if (editor) { editor.setValue(t); } else { ta.value = t; } }
  function say(msg, bad) { status.textContent = msg; status.classList.toggle('t-bad', !!bad); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  function problems(ps) { serverProblems = ps || []; showProblems(); }
  function showProblems() {
    list.innerHTML = serverProblems.concat(liveProblems).map(function (p) {
      var line = p.location ? p.location.line : 0, live = p.code === 'CHECK';
      return '<li data-line="' + line + '" data-col="' + (p.location && p.location.column ? p.location.column : 1) + '" class="' + (live ? 'pr-check' : 'pr-server') + '">' +
        '<span class="mono">' + esc(live ? 'check' : p.code) + '</span> ' + (line ? 'line ' + line + ': ' : '') + esc(p.message) + '</li>';
    }).join('');
  }
  list.addEventListener('click', function (e) {
    var li = e.target.closest('[data-line]');
    if (li && editor && +li.dataset.line > 0) {
      var l = +li.dataset.line - 1; editor.focus(); editor.setCursor({ line: l, ch: Math.max(0, +li.dataset.col - 1) }); editor.scrollIntoView(null, 80);
    }
  });

  // ---- sample JSON pane ---------------------------------------------------------------------------
  root.querySelectorAll('[data-tab]').forEach(function (t) {
    t.addEventListener('click', function () {
      root.querySelectorAll('[data-tab]').forEach(function (x) { x.classList.toggle('on', x === t); x.setAttribute('aria-selected', x === t ? 'true' : 'false'); });
      root.querySelectorAll('[data-pane]').forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== t.getAttribute('data-tab'); });
      if (t.getAttribute('data-tab') === 'summary') { renderSummary(); }
    });
  });
  function pastedDocument() {
    if (!useJson.checked) { return undefined; }
    try { return JSON.parse(jsonTa.value); } catch (e) { say('Sample JSON is not valid: ' + e.message, true); throw e; }
  }
  root.querySelector('[data-load-json]').addEventListener('click', function () {
    fetch('/api/raw/' + encodeURIComponent(kindIn.value.trim()) + '/' + encodeURIComponent(idIn.value.trim()))
      .then(function (r) { return r.json(); })
      .then(function (d) { jsonTa.value = JSON.stringify(d.data !== undefined ? d.data : d, null, 2); say('Loaded ' + idIn.value.trim() + ' (' + jsonTa.value.length + ' characters).'); })
      .catch(function (e) { say('Could not load: ' + e, true); });
  });
  root.querySelector('[data-format-json]').addEventListener('click', function () {
    try { jsonTa.value = JSON.stringify(JSON.parse(jsonTa.value), null, 2); } catch (e) { say('Sample JSON is not valid: ' + e.message, true); }
  });
  useJson.addEventListener('change', function () { preview(); });

  // the document the field completions read: the pasted JSON when ticked, else the chosen entity's
  var docCache = { key: null, p: null };
  function sampleDoc() {
    if (useJson.checked) { try { return Promise.resolve(JSON.parse(jsonTa.value)); } catch (e) { return Promise.resolve(undefined); } }
    var key = kindIn.value.trim() + '/' + idIn.value.trim();
    if (docCache.key !== key) {
      docCache = { key: key, p: fetch('/api/raw/' + encodeURIComponent(kindIn.value.trim()) + '/' + encodeURIComponent(idIn.value.trim()))
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (d) { if (!d) { docCache.key = null; return undefined; } return d.data !== undefined ? d.data : d; })
        .catch(function () { docCache.key = null; return undefined; }) };
    }
    return docCache.p;
  }

  // ---- Summary tab: the Sutra read back as a page ------------------------------------------------------------
  var sumPane = root.querySelector('[data-summary]');
  function renderSummary() {
    fetch('/studio/summary', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ yaml: text() }) })
      .then(function (r) { return r.json(); })
      .then(function (d) { sumPane.innerHTML = d.html || ''; })
      .catch(function (e) { say('Could not summarise the Sutra: ' + e, true); });
  }

  function preview() {
    if (!sumPane.hidden) { renderSummary(); }
    say('Previewing…');
    var t0 = performance.now();
    var doc;
    try { doc = pastedDocument(); } catch (e) { return; }
    fetch('/studio/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ yaml: text(), kind: kindIn.value.trim(), id: idIn.value.trim(), document: doc }) })
      .then(function (r) {
        var html = (r.headers.get('content-type') || '').indexOf('text/html') >= 0;
        return (html ? r.text() : r.json()).then(function (b) { return { ok: r.ok, html: html, body: b }; });
      })
      .then(function (res) {
        if (res.ok && res.html) {
          out.innerHTML = res.body;
          problems([]);
          if (window.drishti) { window.drishti.enhance(out); window.drishti.redraw(); }
          say('Preview in ' + Math.round(performance.now() - t0) + ' ms. Not saved.');
        } else {
          problems(res.body.problems);
          say(drsMessage({ code: res.body.code, detail: res.body.problems && res.body.problems.length ? res.body.problems.length + ' problem(s) below' : res.body.detail }), true);
        }
      })
      .catch(function (e) { say('Preview failed: ' + e, true); });
  }

  root.querySelector('[data-preview]').addEventListener('click', preview);
  root.querySelector('[data-infer]').addEventListener('click', function () {
    var name = (kindIn.value.trim() + '-' + idIn.value.trim()).toLowerCase().replace(/[^a-z0-9-]+/g, '-').slice(0, 60);
    var doc;
    try { doc = pastedDocument(); } catch (e) { return; }
    (doc !== undefined
      ? fetch('/studio/inferred', { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ kind: kindIn.value.trim(), id: idIn.value.trim(), name: name, document: doc }) })
      : fetch('/studio/inferred/' + encodeURIComponent(kindIn.value.trim()) + '/' + encodeURIComponent(idIn.value.trim()) + '?name=' + encodeURIComponent(name)))
      .then(function (r) { return r.ok ? r.text() : r.json().then(function (b) { throw new Error(b.detail); }); })
      .then(function (t) { setText(t); preview(); })
      .catch(function (e) { say('Could not infer: ' + e.message, true); });
  });
  root.querySelector('[data-pick]').addEventListener('change', function (e) {
    var v = e.target.value;
    if (!v) { return; }
    var parts = v.split('@');
    fetch('/studio/source/' + encodeURIComponent(parts[0]) + '/' + encodeURIComponent(parts[1]))
      .then(function (r) { return r.text(); }).then(function (t) { setText(t); preview(); });
  });
  var saveBtn = root.querySelector('[data-save]');
  saveBtn.addEventListener('click', function () {
    var note = root.querySelector('[data-note]');
    fetch('/studio/save', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ yaml: text(), note: note ? note.value : '' }) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
      .then(function (res) {
        if (res.ok && res.body.proposal) {
          say('Submitted ' + res.body.proposal.name + ' v' + res.body.proposal.version + ' for review as ' + res.body.proposal.id +
            '. It goes live when an approver approves it (Reviews).'); problems([]);
          if (note) { note.value = ''; }
        }
        else if (res.ok) { say('Saved ' + res.body.name + ' v' + res.body.latest + '. Views use it now.'); problems([]); }
        else { problems(res.body.problems); say(drsMessage(res.body), true); }
      });
  });
  // ---- test entities: the entities this author keeps for trying the Sutra ---------------------------------------
  var testPick = root.querySelector('[data-test-pick]'), tests = [], testsFor = '';
  function sutraName() {
    var m = text().match(/^sutra:\s*["']?([A-Za-z0-9._-]+)/m);
    return m ? m[1] : '';
  }
  function showTests() {
    testPick.innerHTML = '<option value="">Test entities (' + tests.length + ')…</option>' + tests.map(function (t, i) {
      return '<option value="' + i + '">' + t.kind.replace(/[<&"]/g, '') + ' · ' + t.id.replace(/[<&"]/g, '') + '</option>';
    }).join('');
  }
  function loadTests() {
    var name = sutraName();
    if (!name || name === testsFor) { return; }
    testsFor = name;
    fetch('/studio/tests/' + encodeURIComponent(name)).then(function (r) { return r.ok ? r.json() : []; })
      .then(function (l) { tests = Array.isArray(l) ? l : []; showTests(); }).catch(function () { tests = []; showTests(); });
  }
  function saveTests() {
    return fetch('/studio/tests/' + encodeURIComponent(testsFor), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(tests) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); });
  }
  testPick.addEventListener('change', function () {
    var t = tests[+testPick.value];
    if (!t) { return; }
    kindIn.value = t.kind; idIn.value = t.id; testPick.value = ''; preview();
  });
  root.querySelector('[data-test-add]').addEventListener('click', function () {
    loadTests();
    var k = kindIn.value.trim(), id = idIn.value.trim();
    if (!testsFor) { say('Give the Sutra a name first (sutra: at the top).', true); return; }
    if (!k || !id) { say('Type a kind and an id to keep.', true); return; }
    if (tests.some(function (t) { return t.kind === k && t.id === id; })) { say(id + ' is already a test entity.'); return; }
    tests.push({ kind: k, id: id });
    saveTests().then(function (res) {
      if (res.ok) { tests = res.body; showTests(); say('Kept ' + id + ' as a test entity of ' + testsFor + ' (' + tests.length + ').'); }
      else { tests.pop(); say(drsMessage(res.body), true); }
    });
  });
  root.querySelector('[data-test-run]').addEventListener('click', function () {
    loadTests();
    if (!tests.length) { say('No test entities yet: preview an entity and press + to keep it.', true); return; }
    say('Running ' + tests.length + ' test entities…');
    var yaml = text();
    Promise.all(tests.map(function (t) {
      return fetch('/studio/test', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ yaml: yaml, kind: t.kind, id: t.id }) })
        .then(function (r) { return r.json(); }).then(function (res) { res.entity = t; return res; })
        .catch(function (e) { return { ok: false, entity: t, detail: String(e) }; });
    })).then(function (results) {
      var bad = results.filter(function (r) { return !r.ok; });
      problems(bad.map(function (r) {
        var why = r.failed && r.failed.length ? r.failed.map(function (f) { return f.panel + ': ' + f.error; }).join('; ')
          : (r.problems && r.problems.length ? r.problems.length + ' problem(s) in the Sutra' : drsMessage(r, 'failed'));
        return { code: 'TEST', location: { line: 0 }, message: r.entity.kind + ' ' + r.entity.id + ' — ' + why };
      }));
      say(bad.length ? bad.length + ' of ' + results.length + ' test entities have problems (below).'
        : 'All ' + results.length + ' test entities render without problems (' + Math.round(results.reduce(function (a, r) { return a + (r.ms || 0); }, 0)) + ' ms in all).', !!bad.length);
    });
  });
  if (editor) { editor.on('blur', loadTests); }
  setTimeout(loadTests, 300);

  // ---- the editor's tools: Insert…, Jump to…, Complete, Wrap -------------------------------------------------------
  var PANELS = {
    kv: '- id: details\n  kind: kv\n  title: Details\n  columns:\n    - { label: Name, bind: $.name }\n',
    table: '- id: rows\n  kind: table\n  title: Rows\n  rows: $.items\n  columns:\n    - { label: Name, bind: "@.name" }\n    - { label: Amount, bind: "@.amount", fmt: amount0, total: true }\n',
    tabs: '- id: legs\n  kind: tabs\n  title: Legs\n  each: $.legs\n  tabTitle: "@.label"\n  body:\n    kind: kv\n    columns:\n      - { label: Currency, bind: "@.currency" }\n',
    line: '- id: curve\n  kind: line\n  title: Curve\n  area: right\n  rows: $.points\n  x: tenor\n  y: value\n',
    area: '- id: profile\n  kind: area\n  title: Profile\n  rows: $.profile\n  x: tenor\n  series:\n    - { label: Expected, value: ee, tone: link }\n',
    hbar: '- id: bars\n  kind: hbar\n  title: By bucket\n  area: right\n  rows: $.buckets\n  label: bucket\n  value: amount\n  fmt: signed0\n',
    ladder: '- id: ladder\n  kind: ladder\n  title: Schedule\n  rows: $.schedule\n  highlight: "#index == 0"\n  columns:\n    - { label: Date, bind: "@.date", fmt: date }\n',
    status: '- id: ops\n  kind: status\n  title: Status\n  fields:\n    - { label: Status, bind: $.status, tone: status }\n',
    gauge: '- { id: usage, kind: gauge, title: Utilisation, area: right, value: $.used, max: $.limit, fmt: pct2 }\n',
    markdown: '- { id: notes, kind: markdown, title: Notes, text: "Static notes for the reader." }\n',
    links: '- { id: refs, kind: links, title: Linked entities, area: right }\n',
    provenance: '- { id: built, kind: provenance, title: How this view was built }\n',
    surface: '- id: grid\n  kind: surface\n  title: Surface\n  rows: $.points\n  y: tenor\n',
    waterfall: '- id: explain\n  kind: waterfall\n  title: P&L explain\n  rows: $.pnlExplain\n  label: step\n  value: pnl\n  sum: Closing\n  fmt: signed0\n',
    histogram: '- id: dist\n  kind: histogram\n  title: Distribution\n  rows: $.scenarioPnl\n  markers:\n    - { label: VaR 99%, value: "-$.var99", tone: neg }\n',
    scatter: '- id: riskReturn\n  kind: scatter\n  title: Risk against return\n  rows: $.books\n  x: var\n  y: pnl\n  label: book\n  group: desk\n',
    candlestick: '- id: ohlc\n  kind: candlestick\n  title: Price\n  rows: $.ohlc\n  x: date\n  volume: volume\n  fmt: price2\n',
    graph: '- id: tree\n  kind: graph\n  title: Hierarchy\n  nodes: $.hierarchy.nodes\n  edges: $.hierarchy.edges\n  layout: tree\n',
    timeline: '- id: events\n  kind: timeline\n  title: Lifecycle\n  rows: $.lifecycle.timeline\n  date: date\n  label: event\n',
    pivot: '- id: grid\n  kind: pivot\n  title: By book and currency\n  rows: $.positions\n  by: book\n  across: currency\n  value: mtm\n  agg: sum\n  heat: true\n'
  };
  var SNIPPETS = { strip: ['strip', '- { label: Label, bind: $.field }\n'], key: ['keys', "F5: link($.field, 'kind')\n"],
    description: ['description', null], notes: ['notes', null] };
  var TOP = /^[A-Za-z_][\w-]*\s*:/;
  /** The lines of a top-level key's block: [from, to) with to after its last non-blank line. */
  function block(key) {
    var n = editor.lineCount(), from = -1;
    for (var i = 0; i < n; i++) { if (new RegExp('^' + key + '\\s*:').test(editor.getLine(i))) { from = i; break; } }
    if (from < 0) { return null; }
    var to = from + 1;
    while (to < n && !TOP.test(editor.getLine(to))) { to++; }
    while (to > from + 1 && /^\s*(#.*)?$/.test(editor.getLine(to - 1))) { to--; }
    return { from: from, to: to, inline: editor.getLine(from).replace(/^[^:]*:\s*/, '').replace(/\s+#.*$/, '').trim() };
  }
  function indentOf(b) {
    for (var i = b.from + 1; i < b.to; i++) { var m = editor.getLine(i).match(/^(\s*)\S/); if (m) { return m[1]; } }
    return '  ';
  }
  function put(lineNo, textToInsert) {
    if (lineNo >= editor.lineCount()) { var last = editor.lineCount() - 1; editor.replaceRange('\n' + textToInsert.replace(/\n$/, ''), { line: last, ch: editor.getLine(last).length }); lineNo = last + 1; }
    else { editor.replaceRange(textToInsert, { line: lineNo, ch: 0 }); }
    editor.focus();
    editor.setCursor({ line: lineNo, ch: editor.getLine(lineNo).length });
    editor.scrollIntoView(null, 80);
  }
  function insert(what) {
    if (!editor) { return; }
    var key, body;
    if (what.indexOf('panel:') === 0) { key = 'panels'; body = PANELS[what.slice(6)]; } else { key = SNIPPETS[what][0]; body = SNIPPETS[what][1]; }
    var b = block(key);
    if (body === null) {
      if (b) { editor.focus(); editor.setCursor({ line: b.from, ch: editor.getLine(b.from).length }); say('This Sutra already has ' + key + ' (line ' + (b.from + 1) + ').'); return; }
      var at = block('match') || block('version');
      put(at ? at.to : editor.lineCount(), key + ': ' + (key === 'notes' ? '|\n  Notes for authors and reviewers.\n' : 'What this layout shows, and for which entities.\n'));
      return;
    }
    if (b && b.inline) { say(key + ' is written inline on line ' + (b.from + 1) + ': add the entry there.', true); editor.focus(); editor.setCursor({ line: b.from, ch: 0 }); return; }
    if (b) { var pad = indentOf(b); put(b.to, body.replace(/^(?=.)/gm, pad)); return; }
    var before = key === 'strip' ? block('panels') || block('keys') : key === 'panels' ? block('keys') : null;
    var textToInsert = key + ':\n' + body.replace(/^(?=.)/gm, '  ');
    put(before ? before.from : editor.lineCount(), textToInsert);
  }
  var ins = root.querySelector('[data-insert]');
  if (ins) { ins.addEventListener('change', function () { if (ins.value) { insert(ins.value); } ins.value = ''; }); }
  var jump = root.querySelector('[data-outline]');
  function outline() {
    var html = '<option value="">Jump to…</option>', inPanels = false, item = 0;
    for (var i = 0; editor && i < editor.lineCount(); i++) {
      var t = editor.getLine(i), top = t.match(/^([A-Za-z_][\w-]*)\s*:/);
      if (top) { inPanels = top[1] === 'panels'; item = -1; html += '<option value="' + i + '">' + esc(top[1]) + '</option>'; continue; }
      var it = inPanels && t.match(/^(\s*)-\s+(?:\{.*?\bid:\s*([^,}]+)|id:\s*(.+))/);
      if (it && (item < 0 || it[1].length === item)) {
        item = it[1].length;
        var kind = (t.match(/kind:\s*([\w-]+)/) || editor.getLine(i + 1).match(/^\s*kind:\s*([\w-]+)/) || [])[1];
        html += '<option value="' + i + '">  ' + esc((it[2] || it[3]).trim()) + (kind ? ' · ' + esc(kind) : '') + '</option>';
      }
    }
    jump.innerHTML = html;
  }
  if (jump) {
    jump.addEventListener('focus', outline);
    jump.addEventListener('mousedown', outline);
    jump.addEventListener('change', function () {
      if (jump.value !== '' && editor) { var l = +jump.value; editor.focus(); editor.setCursor({ line: l, ch: 0 }); editor.scrollIntoView({ line: l, ch: 0 }, 80); }
      jump.value = '';
    });
    outline();
  }
  var completeBtn = root.querySelector('[data-complete]');
  if (completeBtn && assist) { completeBtn.addEventListener('click', function () { editor.focus(); assist.complete(); }); }
  var wrapBox = root.querySelector('[data-wrap]');
  if (wrapBox && editor) { editor.setOption('lineWrapping', wrapBox.checked); wrapBox.addEventListener('change', function () { editor.setOption('lineWrapping', wrapBox.checked); }); }

  // ---- File → Open: a Sutra (.yaml/.yml) and/or a sample document (.json), read in the browser ------------------
  var fileMenu = root.querySelector('[data-file-menu]'), fileIn = root.querySelector('[data-file-input]');
  root.querySelector('[data-file-open]').addEventListener('click', function () { fileIn.click(); });
  fileIn.addEventListener('change', function () {
    var files = Array.prototype.slice.call(fileIn.files || []), loaded = [], pending = files.length;
    if (!pending) { return; }
    files.forEach(function (f) {
      var r = new FileReader();
      r.onload = function () { loaded.push({ name: f.name, text: String(r.result) }); if (--pending === 0) { openFiles(loaded); } };
      r.onerror = function () { say('Could not read ' + f.name, true); if (--pending === 0) { openFiles(loaded); } };
      r.readAsText(f);
    });
    fileIn.value = '';
    fileMenu.open = false;
  });
  function openFiles(list) {
    var names = [], doc = null, yamlText = null;
    list.forEach(function (f) {
      if (/\.json$/i.test(f.name)) { doc = f; } else if (/\.ya?ml$/i.test(f.name)) { yamlText = f; } else { say(f.name + ' is neither .yaml, .yml nor .json', true); }
    });
    if (doc) {
      try { jsonTa.value = JSON.stringify(JSON.parse(doc.text), null, 2); } catch (e) { say(doc.name + ' is not valid JSON: ' + e.message, true); return; }
      useJson.checked = true; names.push(doc.name);
    }
    if (yamlText) {
      setText(yamlText.text); names.push(yamlText.name);
      var m = /^match:\s*\{[^}]*?\bkind:\s*([A-Za-z0-9_-]+)/m.exec(yamlText.text);
      if (m) { kindIn.value = m[1]; }
    }
    if (!names.length) { return; }
    if (!idIn.value.trim()) { idIn.value = (yamlText || doc).name.replace(/\..*$/, ''); }
    preview();
  }
  if (idIn.value.trim()) { preview(); }      // nothing to preview yet: the status line says how to start
})();
