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
/* The YAML tab: Studio's CodeMirror editor (cm-yaml.js, studio-yaml.js, studio-assist.js: completion, field help, the light check)
 * on the Design's Sutra. Typing is an operation like any other: after a pause the text goes as a Text operation, so undo, the
 * revision and the log cover it; when the server refuses it (a located DRS problem) the editor keeps your text and the Problems tab
 * says where. When the Sutra changes some other way (the canvas, undo) the editor takes the new text and keeps the cursor and scroll.
 *
 *   new WB.YamlTab(textarea, helpEl, store) -> {goto(line, col), focus(), value(), checks(): [problems], refresh()} */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var PAUSE = 700;

  WB.YamlTab = function (ta, helpEl, store) {
    var editor = window.CodeMirror ? window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, indentUnit: 2, tabSize: 2,
      gutters: ['studio-gutter', 'CodeMirror-linenumbers'], extraKeys: { Tab: function (cm) { cm.replaceSelection('  '); }, Enter: newline } }) : null;
    var timer = 0, applying = false, live = [], docCache = {}, sending = false;
    if (editor) { editor.getInputField().setAttribute('aria-label', 'Sutra (YAML)'); }
    function newline(cm) {          // Enter keeps YAML's indentation
      var c = cm.getCursor(), before = cm.getLine(c.line).slice(0, c.ch).replace(/\s+#.*$/, '');
      var m = before.match(/^(\s*)((?:-\s+)*)/), n = m[1].length + m[2].length, rest = before.slice(n);
      if (m[2] && (/^[{["']/.test(rest) || !/^[^\s#][^:#]*:(\s|$)/.test(rest))) { n = m[1].length; }
      if (/^\s*(?:-\s+)*[^\s#][^:#]*:\s*([|>][-+0-9]*)?\s*$/.test(before) && !/[{[]/.test(before)) { n += 2; }
      cm.replaceSelection('\n' + ' '.repeat(n), 'end', '+input');
    }
    function value() { return editor ? editor.getValue() : ta.value; }
    function set(text) {
      applying = true;
      if (editor) {
        var cur = editor.getCursor(), sc = editor.getScrollInfo();
        editor.setValue(text);
        editor.setCursor({ line: Math.min(cur.line, editor.lineCount() - 1), ch: cur.ch });
        editor.scrollTo(sc.left, sc.top);
      } else { ta.value = text; }
      applying = false;
    }
    function sampleDoc() {
      var name = store.state.sample;
      if (!name || store.state.file) { return Promise.resolve(store.state.file ? store.state.file.document : undefined); }
      if (!docCache[name]) {
        docCache[name] = WB.call('GET', '/build/designs/' + encodeURIComponent(store.state.id) + '/sample?name=' + encodeURIComponent(name)).then(function (r) { return r.ok ? r.body : undefined; });
      }
      return docCache[name];
    }
    var assist = editor && window.drishtiAssist ? window.drishtiAssist.attach(editor, {
      say: function (m, bad) { store.emit('say', m, bad); }, helpEl: helpEl, getDoc: sampleDoc,
      validKinds: function () { return [store.state.kind]; },       // the design's own kind is valid even when no pack defines it (UX-01)
      onCheck: function (ps) { live = ps; store.emit('yamlcheck', ps); }
    }) : null;

    function send() {
      var text = value();
      if (text === store.state.yaml) { store.emit('say', 'The YAML matches the design.'); return Promise.resolve(); }
      sending = true;
      return store.send([{ op: 'text', yaml: text }], 'Applied the YAML').then(function (r) { sending = false; return r; });
    }
    /** Sends what was typed now rather than after the pause (before a save, a preview or a palette command). */
    function flush() { if (timer) { clearTimeout(timer); timer = 0; return send(); } return Promise.resolve(); }
    if (editor) {
      editor.on('change', function (cm, ch) {
        if (applying || ch.origin === 'setValue') { return; }
        clearTimeout(timer);
        timer = setTimeout(send, PAUSE);
      });
      editor.on('blur', function () { if (timer) { clearTimeout(timer); timer = 0; send(); } });
    } else { ta.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(send, PAUSE); }); }

    store.on('doc', function (d) {
      if (sending && d.source === 'ops') { return; }          // our own text: kept as typed, even when the server refused it (Problems says where)
      if (d.yaml !== value() && (d.source !== 'ops' || !timer)) { set(d.yaml); }
      if (d.source === 'ops' && d.yaml === value()) { clearTimeout(timer); timer = 0; }
    });
    store.on('conflict', function () { clearTimeout(timer); timer = 0; set(store.state.yaml); });
    set(store.state.yaml);

    return {
      goto: function (line, col) { if (editor) { editor.focus(); editor.setCursor({ line: Math.max(0, line - 1), ch: Math.max(0, (col || 1) - 1) }); editor.scrollIntoView(null, 80); } else { ta.focus(); } },
      focus: function () { if (editor) { editor.focus(); } else { ta.focus(); } },
      pending: function () { return !!timer; }, refresh: function () { if (editor) { editor.refresh(); } }, flush: flush, value: value, checks: function () { return live; }, editor: editor
    };
  };
})();
