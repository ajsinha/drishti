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
/* The Data pane (left): the Design's samples (add files, remove, choose the previewed one), the shape tree with the roles the
 * shape extractor found, and the palette. A field of the tree is draggable onto the canvas (a panel: bind it; an empty place: ranked
 * panel suggestions; the strip: a new figure) and has the same two things as buttons: "Suggest panels" and "Bind to the selected
 * panel". Text from the data goes in as text nodes only.
 *
 *   new WB.Data(root, store, actions, opts) -> {reloadShape(), fields()}      opts: {maxFile, canvasSelection(): sel}                */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  var F = window.Drishti.Files;

  WB.Data = function (root, store, actions, opts) {
    var $ = function (s) { return root.querySelector(s); };
    var list = $('[data-samples]'), shapeBox = $('[data-shape]'), skipped = $('[data-skipped]'), note = $('[data-data-note]');
    var fields = [];
    function bindField(path) {
      var s = opts.selection();
      if (!s || s.type !== 'panel') { store.emit('say', 'Select a panel on the canvas first, then bind ' + path + ' to it.', true); return; }
      actions.bind(s.id, path);
    }
    var tree = window.Drishti.ShapeTree(shapeBox, {
      rowClass: function () { return 'wb-field-row'; },
      onField: function (r, detail) {
        var bar = WB.el('div', 'bs-actions');
        var sug = WB.el('button', 'btn-pill btn-ghost', 'Suggest panels…', { type: 'button' });
        sug.addEventListener('click', function () { var rect = sug.getBoundingClientRect(); actions.suggest(r.path, { x: rect.left, y: rect.bottom }, actions.afterSelected()); });
        var bind = WB.el('button', 'btn-pill btn-ghost', 'Bind to selected panel', { type: 'button' });
        bind.addEventListener('click', function () { bindField(r.path); });
        bar.appendChild(sug); bar.appendChild(bind); detail.appendChild(bar);
      },
      onKey: function (r, e) {                                                    // e.target: the tree grid, its focused row named by aria-activedescendant
        var at = document.getElementById(e.target.getAttribute('aria-activedescendant') || '') || e.target;
        if (e.key === 's' || e.key === 'S') { var rc = at.getBoundingClientRect(); actions.suggest(r.path, { x: rc.left, y: rc.bottom }, actions.afterSelected()); return true; }
        if (e.key === 'b' || e.key === 'B') { bindField(r.path); return true; }
        return false;
      }
    });
    var treeHost = shapeBox.querySelector('[data-tree]');
    treeHost.title = 'Drag a field onto the canvas: a panel binds it, an empty place suggests panels';
    WB.drag.attach(treeHost, { payload: function (e) { var r = tree.fieldAt(e.target); return r ? { type: 'field', path: r.path } : null; }, label: function (p) { return p.path; } });

    function paintSamples() {
      var st = store.state;
      list.textContent = '';
      var info = st.sampleInfo || [];
      st.samples.forEach(function (name) {
        var s = info.filter(function (i) { return i.name === name; })[0] || {};
        var li = WB.el('li', 'wb-sample' + (name === st.sample && !st.file ? ' on' : ''), null, { 'data-sample': name });
        var pick = WB.el('button', 'wb-sample-pick mono', name, { type: 'button', 'aria-pressed': name === st.sample && !st.file ? 'true' : 'false', title: 'Preview the design with this sample' });
        pick.addEventListener('click', function () { store.setSample(name); });
        li.appendChild(pick);
        if (name.indexOf('pack sample ') === 0) {
          li.appendChild(WB.el('span', 'bs-role', 'from the pack', { title: 'Picked for you: the first record of the pack\'s samples this Sutra matches' }));
          li.appendChild(WB.el('p', 'text-muted-d wb-sample-note', 'Sample picked from the pack: ' + name.slice('pack sample '.length) + '; add your own.', { 'data-pack-sample': '' }));
        } else if (s.type === 'ref') { li.appendChild(WB.el('span', 'bs-role', 'stored entity', { title: 'Read again each time with your rights and masks' })); }
        else if (s.synthetic) { li.appendChild(WB.el('span', 'bs-role', 'synthetic', { title: 'Generated from a schema: not evidence that a screen works on real data' })); }
        var rm = WB.el('button', 'btn-pill btn-ghost', 'Remove', { type: 'button', 'data-remove': '', 'aria-label': 'Remove ' + name });
        rm.addEventListener('click', function () {
          WB.call('DELETE', '/build/designs/' + encodeURIComponent(st.id) + '/samples?name=' + encodeURIComponent(name)).then(function (r) {
            if (!r.ok) { store.emit('say', WB.why(r), true); return; }
            adopt(r.body); store.emit('say', 'Removed ' + name + '.');
          });
        });
        li.appendChild(rm);
        list.appendChild(li);
      });
      if (!st.samples.length) { list.appendChild(WB.el('li', 'text-muted-d', 'No samples yet. Add JSON files: the shape and the preview come from them.')); }
    }
    function adopt(design) {
      store.state.sampleInfo = design.samples || [];
      store.setSamples((design.samples || []).map(function (s) { return s.name; }));
      paintSamples();
      reloadShape();
      store.refresh();
      store.emit('samplesChanged');
    }
    var add = $('[data-add-files]');
    add.addEventListener('change', function () {
      var p = F.pick(add.files);
      add.value = '';
      if (!p.entries.length) { store.emit('say', 'No .json or .jsonl file chosen.', true); return; }
      store.emit('say', 'Sending ' + p.entries.length + ' file' + (p.entries.length === 1 ? '' : 's') + '...');
      F.read(p.entries, opts.maxFile).then(function (files) { return F.send(store.state.id, files); }).then(function (r) {
        if (!r.ok) { store.emit('say', WB.why(r), true); return; }
        adopt(r.body); store.emit('say', 'Added ' + p.entries.length + ' file' + (p.entries.length === 1 ? '' : 's') + '.');
      });
    });

    function reloadShape() {
      if (!store.state.samples.length) { shapeBox.hidden = true; fields = []; actions.fields([]); return Promise.resolve(); }
      return WB.call('GET', '/build/designs/' + encodeURIComponent(store.state.id) + '/shape').then(function (r) {
        if (!r.ok) { note.textContent = 'The shape could not be made: ' + WB.why(r); return; }
        note.textContent = '';
        shapeBox.hidden = false;                                                  // shown first: the tree grid measures its width
        tree.show(r.body.report);
        fields = (r.body.report.paths || []).filter(function (p) { return p.path !== '$'; });
        actions.fields(fields);
        skipped.textContent = '';
        (r.body.skipped || []).forEach(function (s) { skipped.appendChild(WB.el('p', 'prob', s.name + ' is left out of the shape: ' + s.reason + '.')); });
        store.emit('shape', r.body.report);
      });
    }
    store.on('sample', paintSamples);
    paintSamples();
    reloadShape();
    return { adopt: adopt, reloadShape: reloadShape, fields: function () { return fields; }, paint: paintSamples };
  };
})();
