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
/* The command palette (Ctrl+K, or the Commands button): one filterable list of everything the workbench can do without the mouse:
 * add a panel (any kind), go to a panel, next and previous sample, run the check, switch a tab, undo and redo, preview, save or
 * propose, compare with the base, open a file, open the guide. It is the same listbox as "Add panel..." (WB.menu): the filter box
 * is a combobox, Up and Down move through the options (aria-activedescendant), Enter runs, Escape closes and puts the focus back.
 * The commands are built when it opens, so the panel list and the state of undo are current.
 *
 *   new WB.Commands(store, ctx) -> {open(), items()}   ctx: {actions, centre, right, canvas, tests, saving, versions, yaml, guide}   */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  WB.Commands = function (store, ctx) {
    var say = function (t, bad) { store.emit('say', t, bad); };

    function items() {
      var s = store.state, out = [];
      var cmd = function (label, detail, run, badge) { out.push({ label: label, detail: detail, badge: badge, run: run }); };
      cmd('Add panel…', 'Choose a panel kind and add it after the selected one', function () { ctx.centre.show('design'); ctx.actions.menuAdd(); });
      WB.palette.items().forEach(function (k) {
        cmd('Add panel: ' + k.label, k.detail, function () { ctx.centre.show('design'); ctx.actions.add(k.label, ctx.actions.afterSelected()); }, 'add');
      });
      WB.model(s.yaml).ids.forEach(function (id) {
        cmd('Go to panel: ' + id, 'Select it on the canvas', function () { ctx.centre.show('design'); ctx.canvas().select({ type: 'panel', id: id }); ctx.canvas().focus(); }, 'go to');
      });
      if (s.samples.length > 1) {
        var step = function (n) { var i = s.samples.indexOf(s.sample); store.setSample(s.samples[(i + n + s.samples.length) % s.samples.length]); };
        cmd('Next sample', 'Preview the design with the next sample', function () { step(1); });
        cmd('Previous sample', 'Preview the design with the previous sample', function () { step(-1); });
      }
      s.samples.forEach(function (n) { cmd('Go to sample: ' + n, 'Preview the design with it', function () { store.setSample(n); }, 'sample'); });
      cmd('Run check', 'Check every sample against the design now', function () { ctx.right.show('tests'); ctx.tests.run(); });
      [['design', 'Design', 'centre'], ['yaml', 'YAML', 'centre'], ['summary', 'Summary', 'centre'], ['inspector', 'Inspector', 'right'],
       ['problems', 'Problems', 'right'], ['tests', 'Tests', 'right'], ['versions', 'Versions', 'right']].forEach(function (t) {
        cmd('Switch to ' + t[1], 'Show the ' + t[1] + ' tab', function () { ctx[t[2]].show(t[0], true); }, 'tab');
      });
      cmd('Undo', s.opsAt > 0 ? 'Take the last change back' : 'Nothing to undo yet', function () { store.undo(); });
      cmd('Redo', s.opsAt < s.opsCount ? 'Bring back the change undo took' : 'Nothing to redo', function () { store.redo(); });
      cmd('Preview', 'Send what you typed and draw the preview again (Ctrl+Enter)', function () { ctx.saving.preview(); });
      cmd(ctx.saving.review() ? 'Propose: submit for review' : 'Save to the registry', ctx.saving.canSave() ? 'Needs the author right (Ctrl+S)' : 'Not allowed here: saving needs the author right', function () { ctx.saving.save(); }, 'save');
      cmd('Compare with the base or an earlier version', 'Open the Versions tab', function () { ctx.right.show('versions', true); ctx.versions.refresh(); }, 'diff');
      cmd('Open a file…', 'A Sutra (.yaml) or sample documents (.json) from your computer', function () { ctx.saving.open(); });
      cmd('Open the screen designer guide', 'The guide, step by step (F1)', function () { window.location.href = ctx.guide; }, 'help');
      return out;
    }

    function open() {
      var list = items();
      WB.menu.open({ title: 'Command palette', filter: true, at: { x: Math.max(8, (window.innerWidth - 380) / 2), y: 60 }, items: list, empty: 'No command matches. Try "add", "go to", "sample", "check", "undo" or "save".',
        onPick: function (it) { ctx.yaml.flush(); it.run(); say(it.label + '.'); } });
    }

    document.addEventListener('keydown', function (e) {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault(); e.stopPropagation();
        if (WB.menu.isOpen()) { WB.menu.close(); } else { open(); }
      }
    }, true);
    var button = document.querySelector('[data-palette-open]');
    if (button) { button.addEventListener('click', open); }
    return { open: open, items: items };
  };
})();
