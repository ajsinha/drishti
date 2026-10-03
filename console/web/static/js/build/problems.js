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
/* The Problems tab: three kinds, one list, each with a jump to where it is.
 *   YAML        the editor's light check (line and column) and what the server refused in the last Text operation
 *   operations  what the server refused of the last operations (DRS-502x), with the operation and the panel
 *   bindings    a field the Sutra reads that the samples rarely have, or that has two types (from the shape report)
 *   check       a panel that fails on a sample (from the tests matrix)
 * A YAML or operation problem with a line opens the YAML tab at that line; a panel one selects the panel on the canvas.
 *
 *   new WB.Problems(box, countEl, store, hooks) -> {update()}        hooks: {gotoLine(line, col), gotoPanel(id)}                        */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var RARE = 0.5;

  WB.Problems = function (box, countEl, store, hooks) {
    var shape = null, matrix = null, yamlLive = [];
    var list = WB.el('ul', 'bs-list wb-problems', null, { 'aria-label': 'Problems' });
    box.appendChild(list);

    /** Bindings the Sutra reads: [{panel, path, plain}] from every string that starts with $. or @. in a panel. */
    function reads() {
      var out = [];
      WB.model(store.state.yaml).panels.forEach(function (p) {
        var rows = typeof p.values.rows === 'string' ? p.values.rows.replace(/\[\]$/, '') : '';
        // `children:` names the list a tree row nests under itself: it is rare by nature (leaves have none), so it is not a finding
        (function walk(v, key) {
          if (key === 'children') { return; }
          if (typeof v === 'string') {
            (v.match(/[$@]\.[\w.\[\]'-]+/g) || []).forEach(function (m) { out.push({ panel: p.id, expr: m, path: m[0] === '@' ? (rows ? rows + '[].' + m.slice(2) : null) : m }); });
          } else if (Array.isArray(v)) { v.forEach(walk); } else if (v && typeof v === 'object') { Object.keys(v).forEach(function (k) { walk(v[k], k); }); }
        })(p.values);
      });
      return out;
    }
    function bindings() {
      if (!shape) { return []; }
      var by = {}, out = [], seen = {};
      (shape.paths || []).forEach(function (r) { by[r.path.replace(/\[\]/g, '')] = r; });
      reads().forEach(function (b) {
        if (!b.path) { return; }
        var r = by[b.path.replace(/\[\]/g, '')], key = b.panel + b.expr;
        if (!r || seen[key]) { return; }
        seen[key] = 1;
        if (r.conflict) { out.push({ kind: 'binding', text: b.expr + ' has different types in different samples; panel ' + b.panel + ' reads it.', panel: b.panel }); }
        else if (r.presence < RARE) { out.push({ kind: 'binding', text: b.expr + ' is in only ' + Math.round(r.presence * 100) + '% of the samples; panel ' + b.panel + ' will be empty or blank for the rest.', panel: b.panel }); }
      });
      return out;
    }
    function all() {
      var out = [];
      (store.state.problems || []).forEach(function (p) {
        out.push({ kind: 'operation', code: p.code, text: (p.name ? p.name + ': ' : '') + p.message, line: p.line || 0 });
      });
      yamlLive.forEach(function (p) { out.push({ kind: 'yaml', code: 'check', text: p.message, line: p.location.line, col: p.location.column }); });
      bindings().forEach(function (b) { out.push(b); });
      if (matrix) {
        matrix.panels.forEach(function (row) {
          row.cells.forEach(function (c, i) {
            if (c.status === 'error' || c.status === 'noAccess') {
              out.push({ kind: 'check', text: 'Panel ' + row.id + ' ' + (c.status === 'error' ? 'fails' : 'is not allowed') + ' on ' + matrix.samples[i].name + (c.message ? ': ' + c.message : '.'), panel: row.id, sample: matrix.samples[i].name });
            }
          });
        });
      }
      return out;
    }
    function update() {
      var items = all();
      list.textContent = '';
      items.forEach(function (p) {
        var li = WB.el('li', 'wb-problem pr-' + p.kind), b = WB.el('button', 'wb-problem-b', null, { type: 'button' });
        b.appendChild(WB.el('span', 'bs-role', p.kind === 'operation' ? (p.code || 'operation') : p.kind));
        b.appendChild(document.createTextNode(' ' + (p.line ? 'line ' + p.line + ': ' : '') + p.text));
        b.addEventListener('click', function () {
          if (p.line && hooks.gotoLine) { hooks.gotoLine(p.line, p.col); } else if (p.panel && hooks.gotoPanel) { if (p.sample) { store.setSample(p.sample); } hooks.gotoPanel(p.panel); }
        });
        li.appendChild(b); list.appendChild(li);
      });
      if (!items.length) { list.appendChild(WB.el('li', 'text-muted-d', 'No problems: the YAML reads, the operations applied and the bound fields are in the samples.')); }
      countEl.textContent = items.length ? String(items.length) : '';
      countEl.hidden = !items.length;
      countEl.setAttribute('aria-label', items.length + ' problems');
    }
    store.on('doc', update);
    store.on('yamlcheck', function (ps) { yamlLive = ps; update(); });
    store.on('shape', function (r) { shape = r; update(); });
    store.on('checked', function (c) { matrix = c ? c.matrix : null; update(); });
    update();
    return { update: update, items: all };
  };
})();
