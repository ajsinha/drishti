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
/* The workbench page (/build/d/{id}): wires the Data pane, the canvas, the YAML tab, the Summary, the inspector, the Problems and Tests
 * tabs and the status bar around one store (ops.js). The status bar: previous/next sample, desktop or phone width, theme, the check
 * result, the revision, undo and redo, and "Preview with a file...". What is said goes to the status line and to a live region.
 * Ctrl+Z and Ctrl+Shift+Z undo and redo the design anywhere except inside a text field or the YAML editor, which keep their own.   */
(function () {
  'use strict';
  var root = document.querySelector('[data-workbench]');
  if (!root) { return; }
  var WB = window.DrishtiWB, $ = function (s, r) { return (r || root).querySelector(s); }, $$ = function (s, r) { return Array.prototype.slice.call((r || root).querySelectorAll(s)); };
  var d = root.dataset, init = JSON.parse(d.init || '{}');
  var store = WB.Store({ id: d.id, kind: d.kind, rev: parseInt(d.rev, 10), yaml: $('[data-yaml-src]').value, samples: init.samples.map(function (s) { return s.name; }), opsAt: init.opsAt, opsCount: init.opsCount, status: init.status });
  store.state.sampleInfo = init.samples;
  var live = $('[data-live]'), status = $('[data-say]');

  // ---- what is said ---------------------------------------------------------------------------------------------------------
  store.on('say', function (text, bad) {
    status.textContent = text; status.classList.toggle('bad', !!bad);
    live.textContent = ''; setTimeout(function () { live.textContent = text; }, 30);
  });

  // ---- tabs -------------------------------------------------------------------------------------------------------------------
  function tabs(list, onShow) {
    var buttons = $$('[role="tab"]', list);
    function show(b, focus) {
      buttons.forEach(function (x) { var on = x === b; x.setAttribute('aria-selected', on ? 'true' : 'false'); x.tabIndex = on ? 0 : -1; var p = document.getElementById(x.getAttribute('aria-controls')); if (p) { p.hidden = !on && !(p.hasAttribute('data-split-ok') && split); } });
      if (focus) { b.focus(); }
      if (onShow) { onShow(b.dataset.tab); }
    }
    buttons.forEach(function (b) {
      b.addEventListener('click', function () { show(b); });
      b.addEventListener('keydown', function (e) {
        var i = buttons.indexOf(b), n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? buttons.length - 1 : -1;
        if (n >= 0) { e.preventDefault(); show(buttons[(n + buttons.length) % buttons.length], true); }
      });
    });
    return { show: function (name, focus) { var b = buttons.filter(function (x) { return x.dataset.tab === name; })[0]; if (b) { show(b, focus); } }, current: function () { var b = buttons.filter(function (x) { return x.getAttribute('aria-selected') === 'true'; })[0]; return b && b.dataset.tab; } };
  }
  var split = false;
  var centre = tabs($('[data-centre-tabs]'), function (name) {
    if (name === 'yaml' || split) { yaml.refresh(); }
    if (name === 'summary') { summarise(); }
  });
  var right = tabs($('[data-right-tabs]'), function (name) { if (name === 'versions' && versions) { versions.refresh(); } });
  $('[data-split]').addEventListener('click', function (e) {
    split = !split; e.currentTarget.setAttribute('aria-pressed', split ? 'true' : 'false'); $('[data-centre]').classList.toggle('wb-split', split);
    centre.show(centre.current()); yaml.refresh();
    store.emit('say', split ? 'Split view: the design and the YAML side by side.' : 'Single view.');
  });
  function summarise() {
    WB.call('POST', '/studio/summary', { yaml: store.state.yaml }).then(function (r) { $('[data-summary]').innerHTML = r.body.html || ''; });
  }
  store.on('doc', function (x) { if (x.changed && centre.current() === 'summary') { summarise(); } });

  // ---- the parts ---------------------------------------------------------------------------------------------------------------
  var canvas, inspector, actions, data, versions;
  var schemaP = fetch('/studio/schema').then(function (r) { return r.json(); });
  var ui = { required: function (k) { return inspector ? inspector.required(k) : []; }, canvas: function () { return canvas; }, inspect: function (s, o) { if (s) { right.show('inspector'); } inspector.show(s, o); }, selection: function () { return canvas.selected(); } };
  actions = WB.Actions(store, ui);
  canvas = WB.Canvas($('[data-preview]'), store, actions, {
    onSelect: function (s) { inspector.show(s); },
    picked: function () { right.show('inspector'); },                 // a click on the canvas is a wish to inspect; a cell of the Tests matrix is not
    openInspector: function () { right.show('inspector'); inspector.focusFirst(); },
    menuAdd: function () { actions.menuAdd(); }, menuBind: function () { actions.menuBind(); }
  });
  inspector = WB.Inspector($('[data-inspector]'), store, function () { return schemaP; }, { paths: function () { return data ? data.fields() : []; }, actions: actions });
  data = WB.Data($('[data-left]'), store, actions, { maxFile: parseFloat(d.maxFileMb) * 1048576, selection: function () { return canvas.selected(); } });
  WB.palette.render($('[data-palette]'), function (kind) { actions.add(kind, actions.afterSelected()); });
  var yaml = WB.YamlTab($('[data-yaml-src]'), $('[data-field-help]'), store);
  function gotoLine(line, col) { centre.show('yaml'); yaml.goto(line, col); }
  function gotoPanel(id) { centre.show('design'); canvas.select({ type: 'panel', id: id }); canvas.focus(); }
  WB.Problems($('[data-problems]'), $('[data-problem-count]'), store, { gotoLine: gotoLine, gotoPanel: gotoPanel });
  var tests = WB.Tests($('[data-tests]'), store, { gotoPanel: gotoPanel });
  versions = WB.Versions($('[data-versions]'), store, init.base || '');
  var ship = WB.Ship(root, store, { yaml: yaml });
  var saving = WB.Saving(root, store, { yaml: yaml, data: data, ship: ship, examples: JSON.parse(d.examples || '[]') });
  var commands = WB.Commands(store, { actions: actions, centre: centre, right: right, canvas: function () { return canvas; }, tests: tests, saving: saving, ship: ship, versions: versions,
    yaml: yaml, guide: '/help/screen-designer' });

  // ---- the status bar --------------------------------------------------------------------------------------------------------
  var pos = $('[data-sample-pos]'), result = $('[data-result]'), revEl = $('[data-rev]'), undoB = $('[data-undo]'), redoB = $('[data-redo]'), fileChip = $('[data-file-chip]');
  function paintBar() {
    var s = store.state, i = s.samples.indexOf(s.sample);
    pos.textContent = s.file ? 'file ' + s.file.name : (s.samples.length ? 'sample ' + (i + 1) + '/' + s.samples.length + ': ' + s.sample : 'no samples');
    $('[data-prev]').disabled = $('[data-next]').disabled = s.samples.length < 2;
    revEl.textContent = 'rev ' + s.rev;
    undoB.disabled = s.opsAt <= 0; redoB.disabled = s.opsAt >= s.opsCount;
    fileChip.hidden = !s.file;
  }
  function step(n) { var s = store.state, i = s.samples.indexOf(s.sample); if (s.samples.length) { store.setSample(s.samples[(i + n + s.samples.length) % s.samples.length]); } }
  $('[data-prev]').addEventListener('click', function () { step(-1); });
  $('[data-next]').addEventListener('click', function () { step(1); });
  ['doc', 'sample', 'samples'].forEach(function (e) { store.on(e, paintBar); });
  store.on('sample', function (name) { store.emit('say', 'Previewing ' + (name || 'nothing') + '.'); });
  undoB.addEventListener('click', function () { store.undo(); });
  redoB.addEventListener('click', function () { store.redo(); });
  store.on('checking', function () { result.textContent = '… checking'; result.className = 'wb-result'; });
  store.on('checked', function (c) {
    if (!c) { result.textContent = ''; return; }
    result.textContent = (c.matrix.ok ? '✓ ' : '✗ ') + c.passing + '/' + c.total; result.className = 'wb-result ' + (c.matrix.ok ? 'ok' : 'bad');
    result.title = c.matrix.ok ? 'Every sample renders without errors' : 'Some samples fail: see the Tests tab';
  });
  store.on('checkfailed', function () { result.textContent = '? not checked'; result.className = 'wb-result bad'; });
  $$('[data-width]').forEach(function (b) {
    b.addEventListener('click', function () {
      $$('[data-width]').forEach(function (x) { x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
      $('[data-preview]').parentNode.classList.toggle('wb-phone', b.dataset.width === 'phone');
      window.dispatchEvent(new Event('resize'));
      store.emit('say', b.dataset.width === 'phone' ? 'Phone width.' : 'Desktop width.');
    });
  });
  var themeSel = $('[data-theme-pick]'), choices = $$('[data-theme-choice]', document);
  if (choices.length) {
    choices.forEach(function (c) { themeSel.appendChild(WB.el('option', null, c.textContent.trim(), { value: c.dataset.themeChoice })); });
    themeSel.value = document.documentElement.getAttribute('data-theme') || '';
    themeSel.addEventListener('change', function () { var c = choices.filter(function (x) { return x.dataset.themeChoice === themeSel.value; })[0]; if (c) { c.click(); window.dispatchEvent(new Event('resize')); } });
  } else { themeSel.parentNode.hidden = true; }

  // ---- preview with a file ----------------------------------------------------------------------------------------------------
  var pf = $('[data-preview-file]');
  pf.addEventListener('change', function () {
    var f = pf.files[0];
    pf.value = '';
    if (!f) { return; }
    if (f.size > parseFloat(d.maxFileMb) * 1048576) { store.emit('say', f.name + ' is over the ' + d.maxFileMb + ' MB limit.', true); return; }
    f.text().then(function (t) {
      var doc;
      try { doc = /\.jsonl$/i.test(f.name) ? JSON.parse(t.split('\n').filter(Boolean)[0]) : JSON.parse(t); } catch (e) { store.emit('say', f.name + ' is not valid JSON: ' + e.message, true); return; }
      store.previewFile({ name: f.name, document: doc }).then(function () { paintBar(); store.emit('say', 'Previewing the design with ' + f.name + '. It is not added to the design.'); });
    });
  });
  $('[data-file-clear]').addEventListener('click', function () { store.clearFile().then(paintBar); });

  // ---- buttons, auto-design, keys ----------------------------------------------------------------------------------------------
  $('[data-add-menu]').addEventListener('click', function () { actions.menuAdd(this); });
  $('[data-bind-menu]').addEventListener('click', function () { actions.menuBind(this); });
  var auto = $('[data-autodesign]');
  if (auto) {
    auto.addEventListener('click', function () {
      if (store.state.yaml && !window.confirm('Auto-design replaces the Sutra (revision ' + store.state.rev + ') with a new draft. Undo brings the old one back. Continue?')) { return; }
      store.emit('say', 'Drafting a screen...');
      WB.call('POST', '/build/designs/' + encodeURIComponent(store.state.id) + '/autodesign', {}).then(function (r) {
        if (!r.ok) { store.emit('say', WB.why(r), true); return; }
        WB.call('GET', store.base).then(function (g) {
          if (g.ok) { store.adopt({ rev: g.body.rev, yaml: g.body.sutra, status: g.body.status, opsAt: g.body.opsAt, opsCount: (g.body.ops || []).length, problems: [], previewHtml: r.body.previewHtml }, 'autodesign'); }
          WB.draft($('[data-draft]'), r.body);
          store.emit('say', 'Drafted a screen: revision ' + store.state.rev + '; ' + (r.body.pruned || []).length + ' left out. Undo brings the old one back.');
        });
      });
    });
  }
  function typing(t) { var tag = (t.tagName || '').toLowerCase(); return tag === 'input' || tag === 'textarea' || tag === 'select' || t.isContentEditable || !!(t.closest && t.closest('.CodeMirror')); }
  document.addEventListener('keydown', function (e) {
    var k = e.key.toLowerCase();
    if ((e.ctrlKey || e.metaKey) && !e.altKey && (k === 'z' || k === 'y') && !typing(e.target)) {
      e.preventDefault();
      if (k === 'y' || e.shiftKey) { store.redo(); } else { store.undo(); }
    } else if (!e.ctrlKey && !e.metaKey && !e.altKey && !e.shiftKey && (k === 'n') && e.target.closest && e.target.closest('[data-preview]') && e.target.matches('.pnl[data-panel], [data-wb-region]')) {
      e.preventDefault(); actions.menuAdd();
    }
  });
  window.addEventListener('beforeunload', function (e) { if (store.state.sending) { e.preventDefault(); } });
  if (init.sample && store.state.samples.indexOf(init.sample) >= 0) { store.state.sample = init.sample; }
  if (init.tab) { centre.show(init.tab); }
  data.paint(); paintBar();
  store.refresh().then(function () { tests.later(); });
  window.drishtiWorkbench = { store: store, canvas: canvas, actions: actions, tabs: { centre: centre, right: right }, tests: tests, versions: versions, saving: saving, ship: ship, commands: commands };
})();
