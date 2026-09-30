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
/* Sutra Studio: a Markdown editor for Sutras with live preview (md-editor.js adds the toolbar, snippets and
   outline; the Document tab renders the prose). Ctrl+Enter previews the unsaved Sutra against the
   chosen entity; problems list their line (click to jump); "Start from inference" loads what inference
   makes of the entity as an editable Sutra; Save writes the file (authors, where enabled). */
(function () {
  'use strict';
  var root = document.querySelector('[data-studio]');
  if (!root) { return; }
  var ta = root.querySelector('[data-src]'), out = root.querySelector('[data-out]'), status = root.querySelector('[data-status]');
  var list = root.querySelector('[data-problems]'), kindIn = root.querySelector('[data-kind]'), idIn = root.querySelector('[data-id]');
  var editor = window.CodeMirror ? window.CodeMirror.fromTextArea(ta, { mode: modeFor(ta.value), lineNumbers: true, indentUnit: 2, tabSize: 2,
    extraKeys: { 'Ctrl-Enter': preview, 'Cmd-Enter': preview, Tab: function (cm) { cm.replaceSelection('  '); } } }) : null;
  function modeFor(t) { return /^\s*(```|~~~)\s*sutra\s*$/m.test(t) || !/^sutra:/m.test(t) ? 'sutra-markdown' : 'rachana-yaml'; }
  if (editor && window.drishtiMd) { window.drishtiMd.attach(editor, root, function (m, bad) { say(m, bad); }); }
  if (editor) { editor.on('change', function () { var m = modeFor(editor.getValue()); if (editor.getOption('mode') !== m) { editor.setOption('mode', m); } }); }
  function text() { return editor ? editor.getValue() : ta.value; }
  function setText(t) { if (editor) { editor.setValue(t); } else { ta.value = t; } }
  function say(msg, bad) { status.textContent = msg; status.classList.toggle('t-bad', !!bad); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  function problems(ps) {
    list.innerHTML = (ps || []).map(function (p) {
      var line = p.location ? p.location.line : 0;
      return '<li data-line="' + line + '"><span class="mono">' + esc(p.code) + '</span> line ' + line + ': ' + esc(p.message) + '</li>';
    }).join('');
  }
  list.addEventListener('click', function (e) {
    var li = e.target.closest('[data-line]');
    if (li && editor) { var l = Math.max(0, +li.dataset.line - 1); editor.focus(); editor.setCursor({ line: l, ch: 0 }); }
  });

  // ---- sample JSON pane ---------------------------------------------------------------------------
  var jsonTa = root.querySelector('[data-json]'), useJson = root.querySelector('[data-use-json]');
  root.querySelectorAll('[data-tab]').forEach(function (t) {
    t.addEventListener('click', function () {
      root.querySelectorAll('[data-tab]').forEach(function (x) { x.classList.toggle('on', x === t); x.setAttribute('aria-selected', x === t ? 'true' : 'false'); });
      root.querySelectorAll('[data-pane]').forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== t.getAttribute('data-tab'); });
      if (t.getAttribute('data-tab') === 'doc') { renderDoc(); }
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

  var docPane = root.querySelector('[data-doc]');
  function renderDoc() {
    fetch('/studio/render', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ yaml: text() }) })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        docPane.innerHTML = d.html || '<p class="text-muted-d">Nothing to show yet: write some Markdown around the sutra block.</p>';
        docPane.querySelectorAll('a[href^="#"]').forEach(function (a) {
          a.addEventListener('click', function (e) {
            var el = docPane.querySelector('[id="' + a.getAttribute('href').slice(1) + '"]');
            if (el) { e.preventDefault(); el.scrollIntoView({ block: 'start' }); }
          });
        });
      })
      .catch(function (e) { say('Could not render the document: ' + e, true); });
  }

  function preview() {
    if (!docPane.hidden) { renderDoc(); }
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
          say((res.body.code || 'Error') + ': ' + (res.body.problems && res.body.problems.length ? res.body.problems.length + ' problem(s) below' : res.body.detail), true);
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
        else { problems(res.body.problems); say((res.body.code || 'Error') + ': ' + res.body.detail, true); }
      });
  });
  preview();
})();
