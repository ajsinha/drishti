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
/* A small CodeMirror 5 mode for Rachana YAML: comments, keys, quoted strings, numbers, booleans and the
/* The Markdown half of Sutra Studio: toolbar and shortcuts (headings, emphasis, code, links, lists, quotes,
   tables), Rachana snippets inserted inside the sutra block, a heading outline to jump around long
   documents, and soft wrap. Exposes window.drishtiMd.attach(editor, root). */
(function () {
  'use strict';
  var PANELS = {
    kv: '  - id: details\n    kind: kv\n    title: Details\n    columns:\n      - { label: Name, bind: $.name }\n',
    table: '  - id: rows\n    kind: table\n    title: Rows\n    rows: $.items\n    columns:\n      - { label: Name, bind: "@.name" }\n      - { label: Amount, bind: "@.amount", fmt: amount0, total: true }\n',
    tabs: '  - id: legs\n    kind: tabs\n    title: Legs\n    each: $.legs\n    tabTitle: "@.label"\n    body:\n      kind: kv\n      columns:\n        - { label: Currency, bind: "@.currency" }\n',
    line: '  - id: curve\n    kind: line\n    title: Curve\n    area: right\n    rows: $.points\n    x: tenor\n    y: value\n',
    area: '  - id: profile\n    kind: area\n    title: Profile\n    rows: $.profile\n    x: tenor\n    series:\n      - { label: Expected, value: ee, tone: link }\n',
    hbar: '  - id: bars\n    kind: hbar\n    title: By bucket\n    area: right\n    rows: $.buckets\n    label: bucket\n    value: amount\n    fmt: signed0\n',
    ladder: '  - id: ladder\n    kind: ladder\n    title: Schedule\n    rows: $.schedule\n    highlight: "#index == 0"\n    columns:\n      - { label: Date, bind: "@.date", fmt: date }\n',
    status: '  - id: ops\n    kind: status\n    title: Status\n    fields:\n      - { label: Status, bind: $.status, tone: status }\n',
    gauge: '  - { id: usage, kind: gauge, title: Utilisation, area: right, value: $.used, max: $.limit, fmt: pct1 }\n',
    markdown: '  - { id: notes, kind: markdown, title: Notes, text: "Static notes for the reader." }\n',
    links: '  - { id: refs, kind: links, title: Linked entities, area: right }\n',
    provenance: '  - { id: built, kind: provenance, title: How this view was built }\n'
  };
  var BLOCK = '```sutra\nsutra: my-layout\nversion: 1\nmatch: { kind: trade }\ntitle: { pill: "Trade", id: $.tradeId }\npanels:\n' + PANELS.links + '```\n';
  var FENCE = /^\s*(```|~~~)\s*([\w-]*)\s*$/;

  function blockRange(cm) {
    var from = -1, fence = null;
    for (var i = 0; i < cm.lineCount(); i++) {
      var t = cm.getLine(i), m = t.match(FENCE);
      if (fence === null && m) { fence = m[1]; if (m[2] === 'sutra') { from = i; } }
      else if (fence !== null && t.trim() === fence) { if (from >= 0) { return { from: from, to: i }; } fence = null; }
    }
    return null;
  }
  function wrap(cm, before, after, placeholder) {
    var sel = cm.getSelection() || placeholder;
    cm.replaceSelection(before + sel + after);
    if (sel === placeholder) {
      var c = cm.getCursor();
      cm.setSelection({ line: c.line, ch: c.ch - after.length - sel.length }, { line: c.line, ch: c.ch - after.length });
    }
    cm.focus();
  }
  function prefix(cm, mark) {
    var a = cm.getCursor('from').line, b = cm.getCursor('to').line;
    cm.operation(function () {
      for (var i = a, n = 1; i <= b; i++, n++) {
        var p = typeof mark === 'function' ? mark(n) : mark, line = cm.getLine(i);
        if (line.indexOf(p) === 0) { cm.replaceRange('', { line: i, ch: 0 }, { line: i, ch: p.length }); }
        else { cm.replaceRange(p, { line: i, ch: 0 }); }
      }
    });
    cm.focus();
  }
  function insertLines(cm, text) {
    var c = cm.getCursor(), line = cm.getLine(c.line);
    cm.replaceRange((line.trim() ? '\n' : '') + text, { line: c.line, ch: line.length });
    cm.focus();
  }
  var ACTIONS = {
    h2: function (cm) { prefix(cm, '## '); },
    bold: function (cm) { wrap(cm, '**', '**', 'bold'); },
    italic: function (cm) { wrap(cm, '*', '*', 'italic'); },
    code: function (cm) { wrap(cm, '`', '`', '$.field'); },
    link: function (cm) { wrap(cm, '[', '](https://)', 'text'); },
    ul: function (cm) { prefix(cm, '- '); },
    ol: function (cm) { prefix(cm, function (n) { return n + '. '; }); },
    quote: function (cm) { prefix(cm, '> '); },
    table: function (cm) { insertLines(cm, '\n| Column | Meaning |\n|---|---|\n| `$.field` | What it shows |\n'); }
  };

  function insertSnippet(cm, what, say) {
    var r = blockRange(cm);
    if (what === 'block') {
      if (r) { say('This Sutra already has its sutra block.', true); return; }
      insertLines(cm, '\n' + BLOCK);
      return;
    }
    if (!r) { say('Insert a sutra block first.', true); return; }
    var text;
    if (what === 'strip') {
      text = '  - { label: Label, bind: $.field }\n';
      for (var i = r.from + 1; i < r.to; i++) {
        if (/^strip:/.test(cm.getLine(i))) {
          var j = i + 1;
          while (j < r.to && /^\s+-/.test(cm.getLine(j))) { j++; }
          cm.replaceRange(text, { line: j, ch: 0 });
          cm.setCursor({ line: j, ch: 4 }); cm.focus();
          return;
        }
      }
      text = 'strip:\n' + text;
    } else {
      text = PANELS[what.split(':')[1]];
      var hasPanels = false;
      for (var k = r.from + 1; k < r.to; k++) { if (/^panels:/.test(cm.getLine(k))) { hasPanels = true; } }
      if (!hasPanels) { text = 'panels:\n' + text; }
      // panels go at the end of the panels list: before keys: or the closing fence
      for (var m = r.from + 1; m < r.to; m++) {
        if (/^keys:/.test(cm.getLine(m))) { cm.replaceRange(text, { line: m, ch: 0 }); cm.setCursor({ line: m, ch: 4 }); cm.focus(); return; }
      }
    }
    cm.replaceRange(text, { line: r.to, ch: 0 });
    cm.setCursor({ line: r.to, ch: 4 });
    cm.focus();
  }

  function outline(cm, sel) {
    var html = '<option value="">Jump to…</option>', fence = null, r = blockRange(cm);
    for (var i = 0; i < cm.lineCount(); i++) {
      var t = cm.getLine(i), m = t.match(FENCE);
      if (m && fence === null) { fence = m[1]; continue; }
      if (fence !== null) { if (t.trim() === fence) { fence = null; } continue; }
      var h = t.match(/^(#{1,3})\s+(.*)$/);
      if (h) {
        html += '<option value="' + i + '">' + '  '.repeat(h[1].length - 1) + h[2].replace(/[&<>"]/g, function (c) {
          return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }) + '</option>';
      }
    }
    if (r) { html += '<option value="' + r.from + '">⟨sutra block⟩</option>'; }
    sel.innerHTML = html;
  }

  window.drishtiMd = {
    blockRange: blockRange,
    attach: function (cm, root, say) {
      var keys = {};
      keys['Ctrl-B'] = keys['Cmd-B'] = ACTIONS.bold;
      keys['Ctrl-I'] = keys['Cmd-I'] = ACTIONS.italic;
      keys['Ctrl-K'] = keys['Cmd-K'] = ACTIONS.link;
      cm.addKeyMap(keys);
      root.querySelectorAll('[data-md]').forEach(function (b) {
        b.addEventListener('click', function () { ACTIONS[b.getAttribute('data-md')](cm); });
      });
      var ins = root.querySelector('[data-insert]');
      ins.addEventListener('change', function () { if (ins.value) { insertSnippet(cm, ins.value, say); } ins.value = ''; });
      var sel = root.querySelector('[data-outline]');
      sel.addEventListener('focus', function () { outline(cm, sel); });
      sel.addEventListener('change', function () {
        if (sel.value !== '') { var l = +sel.value; cm.focus(); cm.setCursor({ line: l, ch: 0 }); cm.scrollIntoView({ line: l, ch: 0 }, 80); }
        sel.value = '';
      });
      outline(cm, sel);
      var wrapBox = root.querySelector('[data-wrap]');
      cm.setOption('lineWrapping', wrapBox.checked);
      wrapBox.addEventListener('change', function () { cm.setOption('lineWrapping', wrapBox.checked); });
    }
  };
})();
