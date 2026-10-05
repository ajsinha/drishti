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
/* The About tab (docs/architecture/CONTEXT_HELP.md, step 7): the design's About text (the pack's config/about.yaml: the page's text,
 * the panels' text and the glossary) written beside the Sutra, with the About card it makes for the sample being previewed.
 *
 *   edit      a small CodeMirror on the text. After a pause (600 ms) the card is asked for (POST /build/designs/{id}/about/preview, nothing
 *             saved); after a longer one (1.5 s) or on leaving the box the text is kept as a step of the design's log (PUT .../about), so
 *             undo, redo and revisions cover it like a Sutra edit. A stale revision is a 409 and the design is reloaded, as for operations.
 *   card      layer 1 (page text, the Sutra's description, the panels' notes) and layer 2 (the glossary of the fields this preview shows),
 *             from the server's own About code, so it reads as the drawer will. A failing ${...} reads as a dash and is counted.
 *   problems  what is wrong with the text (DRS-2040 to DRS-2044, with line and column) and the lint warnings (DRS-2045 to DRS-2047);
 *             each with a line jumps there. They also go to the Problems tab (event 'aboutlint').
 *   starter   writes a skeleton for the kind, its panels and the fields shown that have no entry yet.
 * All text from the server goes in as text.
 *
 *   new WB.About(pane, store) -> {show(), later(), goto(line), flush(), pending(), value()}                                    */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var PREVIEW = 600, SAVE = 1500;

  WB.About = function (pane, store) {
    var ta = pane.querySelector('[data-about-src]'), cardBox = pane.querySelector('[data-about-card]'), lintBox = pane.querySelector('[data-about-lint]'),
      statusEl = pane.querySelector('[data-about-status]'), starterB = pane.querySelector('[data-about-starter]');
    var editor = window.CodeMirror ? window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, lineWrapping: true, indentUnit: 2, tabSize: 2,
      extraKeys: { Tab: false, 'Shift-Tab': false } }) : null;          // Tab leaves the box: the tab is reachable and left by keyboard alone
    if (editor) { editor.getInputField().setAttribute('aria-label', 'About text (about.yaml)'); }
    var base = '/build/designs/' + encodeURIComponent(store.state.id), previewT = 0, saveT = 0, token = 0, applying = false, loaded = false, last = null;

    function value() { return editor ? editor.getValue() : ta.value; }
    function set(text) {
      applying = true;
      if (editor) { var cur = editor.getCursor(); editor.setValue(text); editor.setCursor({ line: Math.min(cur.line, editor.lineCount() - 1), ch: cur.ch }); } else { ta.value = text; }
      applying = false;
    }
    function sampleName() { return store.state.sample || undefined; }

    // ---- painting -------------------------------------------------------------------------------------------------------------
    function line(parent, label, text, cls) {
      if (!text) { return; }
      var p = WB.el('p', cls || 'wb-about-line');
      if (label) { p.appendChild(WB.el('strong', null, label + ' ')); }
      p.appendChild(document.createTextNode(text)); parent.appendChild(p);
    }
    function paintCard(a) {
      cardBox.textContent = '';
      if (a.error && !a.card) { cardBox.appendChild(WB.el('p', 'text-muted-d', a.error)); return; }
      var c = a.card || {}, about = c.about || {}, panels = about.panels || [], gloss = c.glossary || [];
      cardBox.appendChild(WB.el('h3', 'wb-about-h', about.kindTitle || 'About this page'));
      if (about.pack) { cardBox.appendChild(WB.el('p', 'text-muted-d wb-about-from', 'Text from: ' + (about.pack.title || about.pack.name))); }
      if (about.text) { cardBox.appendChild(WB.el('p', 'wb-about-text', about.text)); }
      else { cardBox.appendChild(WB.el('p', 'text-muted-d', 'No page text yet: add kinds.' + (a.kind || '<kind>') + '.about to the box.')); }
      line(cardBox, 'The screen:', about.sutraDescription);
      if (c.errors) { cardBox.appendChild(WB.el('p', 'wb-about-warn', c.errors + ' expression' + (c.errors === 1 ? '' : 's') + ' failed and read as a dash.')); }
      var ph = WB.el('h4', 'wb-about-h2', 'Panels'); cardBox.appendChild(ph);
      var withText = panels.filter(function (p) { return p.description; });
      if (!withText.length) { cardBox.appendChild(WB.el('p', 'text-muted-d', 'No panel text yet.')); }
      var ul = WB.el('ul', 'wb-about-list');
      withText.forEach(function (p) { var li = WB.el('li'); li.appendChild(WB.el('strong', null, p.title || p.id)); li.appendChild(document.createTextNode(': ' + p.description)); ul.appendChild(li); });
      cardBox.appendChild(ul);
      cardBox.appendChild(WB.el('h4', 'wb-about-h2', 'Glossary (' + gloss.length + ')'));
      if (!gloss.length) { cardBox.appendChild(WB.el('p', 'text-muted-d', 'No field this preview shows has an entry yet.')); }
      var dl = WB.el('dl', 'wb-about-gloss');
      gloss.forEach(function (t) {
        dl.appendChild(WB.el('dt', null, t.term || t.label));
        var dd = WB.el('dd');
        dd.appendChild(document.createTextNode(t.means || ''));
        var bits = [t.unit && 'unit ' + t.unit, t.sign, t.note, t.masked ? 'hidden for you' : ''].filter(Boolean);
        if (bits.length) { dd.appendChild(WB.el('span', 'text-muted-d', ' (' + bits.join('; ') + ')')); }
        if (t.formula) { dd.appendChild(WB.el('code', 'mono wb-about-formula', t.formula)); }
        dd.appendChild(WB.el('span', 'text-muted-d wb-about-key', ' ' + t.key));
        dl.appendChild(dd);
      });
      cardBox.appendChild(dl);
    }
    function paintLint(a) {
      lintBox.textContent = '';
      var items = (a.problems || []).concat(a.lint || []);
      // the Problems tab hears of the coverage warnings once the author has started writing About text (before that every design would list
      // one per field shown); the tab itself always lists them. The F1 warning is about the Sutra and always goes.
      var started = value().trim() !== '';
      store.emit('aboutlint', (a.lint || []).concat(a.problems || []).filter(function (p) { return started || p.code === 'DRS-2045'; })
        .map(function (p) { return { code: p.code, message: p.message, line: p.line || 0 }; }));
      var cov = a.coverage && a.coverage.shown ? a.coverage.text : '';
      statusEl.textContent = a.error ? a.error : (cov ? 'The preview shows ' + a.coverage.shown + ' field' + (a.coverage.shown === 1 ? '' : 's') + '; ' + a.coverage.covered + ' explained (' + cov + ').' : '');
      if (!items.length) { lintBox.appendChild(WB.el('p', 'text-muted-d', 'No problems in the About text.')); return; }
      var ul = WB.el('ul', 'bs-list wb-about-problems', null, { 'aria-label': 'About text problems' });
      items.forEach(function (p) {
        var li = WB.el('li', 'wb-problem pr-' + (p.severity === 'error' ? 'yaml' : 'help')), b = WB.el('button', 'wb-problem-b', null, { type: 'button' });
        b.appendChild(WB.el('span', 'bs-role', p.code));
        b.appendChild(document.createTextNode(' ' + (p.line ? 'line ' + p.line + ': ' : '') + p.message));
        b.addEventListener('click', function () { goto(p.line); });
        li.appendChild(b); ul.appendChild(li);
      });
      lintBox.appendChild(ul);
    }
    function paint(a) { last = a; paintCard(a); paintLint(a); }

    // ---- asking for the card, keeping the text ----------------------------------------------------------------------------------
    function refresh() {
      loaded = true; var mine = ++token;
      return WB.call('POST', base + '/about/preview', { text: value(), sample: sampleName() }).then(function (r) {
        if (mine !== token) { return; }
        if (!r.ok) { statusEl.textContent = 'The About card could not be made: ' + WB.why(r); return; }
        paint(r.body);
      });
    }
    function schedule() { clearTimeout(previewT); previewT = setTimeout(function () { previewT = 0; refresh(); }, PREVIEW); }
    function save() {
      clearTimeout(saveT); saveT = 0;
      if (value() === store.state.about) { return Promise.resolve(); }
      return store.exclusive(function () {
        var text = value();
        if (text === store.state.about) { return null; }
        return WB.call('PUT', base + '/about', { baseRev: store.state.rev, text: text, sample: sampleName() }).then(function (r) {
          if (r.status === 409) { return store.reload(); }
          if (!r.ok) { store.emit('say', 'The About text was not saved: ' + WB.why(r), true); return null; }
          store.adoptAbout(r.body);
          if (value() === r.body.about) { paint(r.body); }
          store.emit('say', 'About text saved (revision ' + r.body.rev + '). Ctrl+Z undoes it.');
          return null;
        });
      });
    }
    function changed() {
      if (applying) { return; }
      schedule(); clearTimeout(saveT); saveT = setTimeout(save, SAVE);
    }
    function pending() { return !!saveT; }
    function flush() { return saveT ? save() : Promise.resolve(); }
    if (editor) { editor.on('change', changed); editor.on('blur', function () { if (saveT) { save(); } }); } else { ta.addEventListener('input', changed); }

    store.on('doc', function (d) {
      if (typeof d.about === 'string' && d.about !== value() && !saveT) { set(d.about); }       // undo, redo or a reload moved the text
      if (d.source !== 'about' && loaded) { schedule(); }                                       // the Sutra changed: the card is of the new screen
    });
    store.on('sample', function () { if (loaded) { schedule(); } });

    // ---- the starter -----------------------------------------------------------------------------------------------------------
    function q(s) { return JSON.stringify(String(s)); }
    function starter(a) {
      var kind = (a && a.kind) || store.state.kind || 'kind', out = ['about: 1', 'kinds:', '  ' + kind + ':', '    title: ' + q(kind.charAt(0).toUpperCase() + kind.slice(1).replace(/[-_]/g, ' ')),
        '    about: ' + q('Say what this ${$.id} is, with its own numbers.')];
      var panels = (a && a.panels) || [];
      if (panels.length) { out.push('    panels:'); panels.forEach(function (p) { out.push('      ' + p.id + ':', '        about: ' + q('What ' + (p.title || p.id) + ' shows.')); }); }
      var missing = (a && a.coverage && a.coverage.missing) || [];
      if (missing.length) { out.push('    glossary:'); missing.forEach(function (f) { out.push('      ' + f + ':', '        term: ' + q(f), '        means: ' + q('What ' + f + ' means.')); }); }
      return out.join('\n') + '\n';
    }
    if (starterB) {
      starterB.addEventListener('click', function () {
        // the skeleton is of what the preview shows with no text of its own: ask for that, then write it
        var put = function () {
          WB.call('POST', base + '/about/preview', { text: '', sample: sampleName() }).then(function (r) {
            set(starter(r.ok ? r.body : null)); changed(); if (editor) { editor.focus(); }
            store.emit('say', 'A starter was written for the kind, its panels and the fields shown without an entry.');
          });
        };
        if (!value().trim()) { put(); return; }
        WB.ask({ title: 'Replace the About text?', message: 'The starter replaces what is in the box. Undo (Ctrl+Z) brings it back.', ok: 'Replace' }).then(function (yes) { if (yes) { put(); } });
      });
    }

    set(store.state.about || '');
    function goto(l) { if (editor) { editor.focus(); if (l) { editor.setCursor({ line: Math.max(0, l - 1), ch: 0 }); editor.scrollIntoView(null, 60); } } else { ta.focus(); } }
    return {
      show: function () { if (editor) { editor.refresh(); } if (!loaded || last === null) { refresh(); } },
      later: function () { setTimeout(function () { if (!loaded) { refresh(); } }, 1200); },       // the Problems tab lists the warnings of a design with About text without the tab being opened
      goto: goto, flush: flush, pending: pending, value: value, refresh: refresh, editor: editor
    };
  };
})();
