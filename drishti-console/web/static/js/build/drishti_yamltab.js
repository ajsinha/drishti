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
/* The YAML tab: drishti-designer's YamlPane (drishti-inputs' CodeEditor in YAML mode) on the Design's Sutra, with the YAML assistant over
 * the Rachana schema (completion of keys, a panel's options by its kind, values, functions and the sample's field paths; the light
 * check; the help of the key under the cursor). Typing is an operation like any other: after a pause the text goes as a Text operation,
 * so undo, the revision and the log cover it; when the server refuses it (a located DRS problem) the editor keeps your text and the
 * Problems tab says where. When the Sutra changes some other way (the canvas, undo) the editor takes the new text and keeps the cursor
 * and scroll.
 *
 *   new WB.YamlTab(hostEl, helpEl, store) -> {goto(line, col), focus(), value(), checks(): [problems], refresh(), flush(), pending()} */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  var K = window.Drishti.elements.designer;
  var PAUSE = 700;

  WB.YamlTab = function (ta, helpEl, store) {
    var host = ta, schema = null, live = [], docCache = {}, sending = false;
    host.classList.add('wb-yaml');
    function sampleDoc() {
      var name = store.state.sample;
      if (!name || store.state.file) { return Promise.resolve(store.state.file ? store.state.file.document : undefined); }
      if (!docCache[name]) {
        docCache[name] = WB.call('GET', '/build/designs/' + encodeURIComponent(store.state.id) + '/sample?name=' + encodeURIComponent(name)).then(function (r) { return r.ok ? r.body : undefined; });
      }
      return docCache[name];
    }
    var pane = new K.YamlPane(host, { label: 'Sutra (YAML)', height: 520, delay: PAUSE,
      fromYaml: function (text) {                     // the server applies the text (one operation); the pane keeps it as typed
        if (text === store.state.yaml) { return text; }
        sending = true;
        return store.send([{ op: 'text', yaml: text }], 'Applied the YAML').then(function () { sending = false; return text; }, function (e) { sending = false; throw e; });
      },
      complete: function (c) {
        if (!schema) { return []; }
        return sampleDoc().then(function (doc) { return K.yamlCompletions(schema, c.text, c.lineNo, c.ch, { doc: doc, explicit: c.forced }); });
      },
      completeAfter: function (before) { return /[$@](?:\.[A-Za-z_$][\w$-]*|\[\d+\])*\.$/.test(before) || /(^\s*(-\s+)*|[{,]\s*)[\w-]+:\s$/.test(before); },
      lint: function (text) {
        if (!schema) { return []; }
        var ps = K.yamlCheck(schema, text, [store.state.kind]);         // the design's own kind is valid even when no pack defines it (UX-01)
        live = ps.map(function (p) { return { code: 'CHECK', message: p.message, location: { line: p.line, column: p.from + 1 } }; });
        store.emit('yamlcheck', live);
        return ps;
      } });
    var editor = pane.editor;
    fetch('/studio/schema').then(function (r) { return r.ok ? r.json() : null; })
      .then(function (raw) { if (raw && raw.properties) { schema = new K.YamlSchema(raw); editor.runLint(); } })
      .catch(function () { /* the editor works without it */ });
    editor.on('cursor', function (c) {
      if (!helpEl || !schema) { return; }
      var help = K.yamlHelp(schema, editor.getValue(), c.line, c.ch);
      helpEl.hidden = !help;
      helpEl.textContent = '';
      if (help) {
        var k = document.createElement('span'); k.className = 'mono fh-key'; k.textContent = help.title;
        var t = document.createElement('span'); t.className = 'fh-text'; t.textContent = help.text;
        helpEl.append(k, ' ', t);
      }
    });
    editor.on('ready', function () { if (editor.cm) { editor.cm.on('blur', function () { if (pane.dirty) { pane.read(); } }); } });
    store.on('doc', function (d) {
      if (sending && d.source === 'ops') { return; }          // our own text: kept as typed, even when the server refused it (Problems says where)
      if (d.yaml !== pane.getText() && (d.source !== 'ops' || !pane.dirty)) { pane.setText(d.yaml); }
    });
    store.on('conflict', function () { pane.setText(store.state.yaml); });
    pane.setText(store.state.yaml);

    /** Sends what was typed now rather than after the pause (before a save, a preview or a palette command). */
    function flush() { return pane.dirty ? pane.read() : Promise.resolve(); }
    return {
      goto: function (line, col) { editor.goTo(Math.max(1, line), Math.max(0, (col || 1) - 1)); },
      focus: function () { editor.focus(); },
      pending: function () { return !!pane.dirty; }, refresh: function () { pane.refresh(); }, flush: flush, value: function () { return pane.getText(); },
      checks: function () { return live; }, editor: editor
    };
  };
})();
