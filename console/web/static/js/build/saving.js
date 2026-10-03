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
/* What Studio's toolbar did, in the workbench: the File menu (Open a .yaml or .json from your computer, or an example), Save to the
 * registry / Submit for review, and the two keys Studio's users have in their fingers: Ctrl+S saves (or says why it cannot, and does
 * not open the browser's save dialog) and Ctrl+Enter previews (what was typed is sent first). Designing is open to everyone; saving
 * is the author right with Studio saving switched on, and the server decides and says why not. Where governance is on the same
 * button submits a proposal for an approver; the note is for the reviewer.
 *
 *   new WB.Saving(root, store, {yaml, data, maxFile, say}) -> {save(), preview(), open()}                                       */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var F = window.DrishtiFiles;

  WB.Saving = function (root, store, hooks) {
    var $ = function (s) { return root.querySelector(s); };
    var d = root.dataset, init = JSON.parse(d.init || '{}');
    var can = !!init.canSave, review = !!init.review;
    var saveB = $('[data-save]'), note = $('[data-note]'), fileB = $('[data-file-menu]'), input = $('[data-file-input]');
    var say = function (t, bad) { store.emit('say', t, bad); };
    var why = function () { return 'Designing is open to everyone; saving needs the author right (or saving is off on this server). Your design is kept in My designs.'; };

    function save() {
      if (hooks.ship && hooks.ship.bound()) { return hooks.ship.saveFile(); }
      if (!can) { say(why(), true); return Promise.resolve(false); }
      return hooks.yaml.flush().then(function () {
        say(review ? 'Submitting for review...' : 'Saving...');
        return WB.call('POST', '/build/designs/' + encodeURIComponent(store.state.id) + '/propose', { note: note ? note.value : '' });
      }).then(function (r) {
        if (r === false) { return false; }
        if (r.ok && r.body.status && hooks.ship) { store.state.status = r.body.status; hooks.ship.paint(r.body.status); }
        if (r.ok && r.body.proposal) {
          var p = r.body.proposal;
          say('Submitted for review as ' + p.id + ': ' + p.name + ' v' + p.version + ', with the check matrix, sample names and notes. An approver makes it live.');
          var a = WB.el('a', 'lnk', ' Open the review', { href: '/build/reviews/' + encodeURIComponent(p.id) });
          document.querySelector('[data-say]').appendChild(a);
          return true;
        }
        if (r.ok) { say('Saved ' + r.body.name + ' v' + r.body.version + '. Views use it now.'); return true; }
        var more = (r.body.problems || []).slice(0, 3).map(function (p) { return p.message + (p.location ? ' (line ' + p.location.line + ')' : ''); }).join(' ');
        say(WB.why(r) + (more ? ' ' + more : ''), true);
        return false;
      });
    }

    function preview() {
      return hooks.yaml.flush().then(function () { return store.refresh(); }).then(function () { say('Previewed. Not saved.'); });
    }

    // ---- File: Open, and the examples -----------------------------------------------------------------------------------------
    function openFiles(files) {
      var yamls = files.filter(function (f) { return /\.ya?ml$/i.test(f.name); }), jsons = files.filter(function (f) { return /\.jsonl?$/i.test(f.name); });
      var chain = Promise.resolve();
      if (yamls.length) {
        chain = chain.then(function () {
          if (store.state.yaml && !window.confirm('Replace the Sutra with ' + yamls[0].name + '? Undo (Ctrl+Z) brings the old one back.')) { return null; }
          return yamls[0].text().then(function (t) { return store.send([{ op: 'text', yaml: t }], 'Opened ' + yamls[0].name); });
        });
      }
      if (jsons.length) {
        chain = chain.then(function () {
          var entries = jsons.map(function (f) { return { name: f.name, file: f }; });
          return F.read(entries, parseFloat(d.maxFileMb) * 1048576).then(function (read) { return F.send(store.state.id, read); }).then(function (r) {
            if (!r.ok) { say(WB.why(r), true); return; }
            hooks.data.adopt(r.body); say('Added ' + jsons.length + ' sample file' + (jsons.length === 1 ? '' : 's') + '.');
          });
        });
      }
      if (!yamls.length && !jsons.length) { say('Choose a Sutra (.yaml) or sample documents (.json).', true); }
      return chain;
    }
    if (input) {
      input.addEventListener('change', function () { var fs = Array.prototype.slice.call(input.files); input.value = ''; openFiles(fs); });
    }
    function open() { if (input) { input.click(); } }
    function fileMenu() {
      var items = [{ label: 'Open…', detail: 'A Sutra (.yaml) replaces this one; sample documents (.json) are added', value: 'open' }];
      (hooks.examples || []).forEach(function (n) { items.push({ label: n, badge: 'example', detail: 'Opens as a new design: your own copy', value: n }); });
      WB.menu.open({ title: 'File', anchor: fileB, filter: true, items: items, onPick: function (it) {
        if (it.value === 'open') { open(); return; }
        say('Opening a copy of ' + it.value + '...');
        WB.call('POST', '/build/examples/' + encodeURIComponent(it.value) + '/open').then(function (r) {
          if (r.ok) { window.location.href = r.body.url + '?tab=yaml'; } else { say(WB.why(r), true); }
        });
      } });
    }
    if (fileB) { fileB.addEventListener('click', fileMenu); }
    if (saveB) { saveB.addEventListener('click', save); }

    document.addEventListener('keydown', function (e) {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || WB.menu.isOpen()) { return; }
      var k = e.key.toLowerCase();
      if (k === 's' && !e.shiftKey) { e.preventDefault(); e.stopPropagation(); save(); }
      else if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); preview(); }
    }, true);

    return { save: save, preview: preview, open: open, canSave: function () { return can; }, review: function () { return review; }, fileMenu: fileMenu };
  };
})();
