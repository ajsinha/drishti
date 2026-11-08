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
/* The shape tree of the Build workbench: conflicts and rare fields first, then the fields as drishti-tree's outline (a tree grid without
 * chrome: arrows, Home/End, Right/Left to open, close and go up, Enter for a field's reason, its files and examples), with a filter.
 * Used by a Design's page. window.Drishti.ShapeTree(root, opts) wires the elements inside root that carry data-tree, data-filter,
 * data-counts, data-attention, data-conflicts, data-rare, data-expand-all and data-collapse-all, and returns {show(report),
 * fieldAt(element)}. All text from the data goes in as text, never as HTML. opts (the workbench's): rowClass(row) more classes on a
 * field's row; onField(row, box) adds to a field's detail; onKey(row, event) its keys on a focused field (true: handled). */
(function () {
  'use strict';
  window.Drishti.ShapeTree = function (root, opts) {
    opts = opts || {};
    var $ = function (s) { return root.querySelector(s); };
    var host = $('[data-tree]'), counts = $('[data-counts]'), filter = $('[data-filter]');
    var outline = null, total = 0;

    function el(tag, cls, text) {
      var e = document.createElement(tag);
      if (cls) { e.className = cls; }
      if (text !== undefined && text !== null) { e.textContent = text; }
      return e;
    }
    function pct(p) { return (Math.round(p * 1000) / 10) + '%'; }

    // ---- conflicts and rare fields ---------------------------------------------------------------------------------
    function attention(rep) {
      var box = $('[data-attention]'), c = $('[data-conflicts]'), r = $('[data-rare]');
      c.textContent = ''; r.textContent = '';
      if (rep.conflicts.length) {
        c.appendChild(el('h3', 'bs-h', 'Conflicts: ' + rep.conflicts.length));
        c.appendChild(el('p', 'text-muted-d', 'The same field has different types in different files.'));
        var ul = el('ul', 'bs-list');
        rep.conflicts.forEach(function (x) { ul.appendChild(el('li', 'mono', x.path + '  ' + x.types.join(' | '))); });
        c.appendChild(ul);
      }
      if (rep.rare.length) {
        r.appendChild(el('h3', 'bs-h', 'Rare fields: ' + rep.rare.length));
        r.appendChild(el('p', 'text-muted-d', 'Present in under half of their records: a panel built on one may be empty for many files.'));
        var ul2 = el('ul', 'bs-list');
        rep.rare.forEach(function (x) { ul2.appendChild(el('li', 'mono', x.path + '  ' + pct(x.presence))); });
        r.appendChild(ul2);
      }
      box.hidden = !(rep.conflicts.length || rep.rare.length);
    }

    // ---- the fields as a hierarchy: one node per step of a path ($.legs[].notional: legs, [], notional) ----------------
    var SEG = /\.([^.\[\]{}]+)|\[\]|\{\}|\['((?:[^'\\]|\\.)*)'\]/g;
    function segments(path) {
      var out = [], m;
      SEG.lastIndex = 0;
      while ((m = SEG.exec(path.slice(1))) !== null) { out.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2].replace(/\\'/g, "'") : m[0]); }
      return out;
    }
    function rows(paths) {
      var out = [], seen = {};
      paths.forEach(function (p) {
        var segs = segments(p.path), key = '$', parent = null;
        for (var i = 0; i < segs.length; i++) {
          key += '\u0001' + segs[i];
          if (!seen[key]) { seen[key] = { id: key, parent: parent, label: segs[i] }; out.push(seen[key]); }
          parent = key;
        }
        if (segs.length) { seen[key].field = p; }
      });
      return out;
    }

    function marks(n) {
      var r = n.data.field, out = [];
      if (!r) { return out; }
      out.push({ text: r.type, mono: true });
      if (r.role) { out.push({ text: r.role, badge: true, tone: r.conflict ? 'bad' : 'accent', title: r.reason || '' }); }
      if (r.conflict) { out.push({ text: 'conflict', badge: true, tone: 'bad' }); }
      out.push({ text: pct(r.presence), tone: r.presence < 0.5 ? 'warn' : 'muted' });
      if (r.examples && r.examples.length) { out.push({ text: r.examples.join(', '), mono: true, clip: true, title: r.examples.join(', ') }); }
      return out;
    }
    function detail(n, box) {
      var r = n.data.field;
      if (!r) { return []; }
      var lines = ['Path: ' + r.path, 'Role: ' + (r.role || 'none') + (r.reason ? ' — ' + r.reason : ''),
        'Present in ' + pct(r.presence) + ' of its records' + (r.masked ? ' (masked)' : '')];
      if (r.examples && r.examples.length) { lines.push('Examples: ' + r.examples.join(', ')); }
      if (r.files && r.files.length) { lines.push('Files: ' + r.files.join(', ')); }
      if (opts.onField) { opts.onField(r, box); }
      return lines;
    }
    function words(n) { var r = n.data.field; return r ? r.path + ' ' + r.type + ' ' + (r.role || '') + ' ' + (r.reason || '') : n.label; }

    function build(paths) {
      var data = { rows: rows(paths) };
      total = data.rows.filter(function (r) { return r.field; }).length;      // the paths with a step ($ itself is not a field)
      if (outline) { outline.setData(data); applyFilter(); return; }
      var T = window.Drishti.elements && window.Drishti.elements.tree;
      if (!T) { host.textContent = 'The shape tree needs drishti-tree (elements/drishti-tree/drishti_tree.js).'; return; }
      outline = new T.TreeOutline(host, {
        data: data, title: 'Field', label: 'Shape of the samples', marks: marks, detail: detail, text: words,
        rowClass: function (n) { return n.data.field && opts.rowClass ? opts.rowClass(n.data.field) : null; },
        onKey: function (n, e) { return !!(n && n.data.field && opts.onKey && opts.onKey(n.data.field, e)); }
      });
      applyFilter();
    }

    // ---- filter, expand and collapse -----------------------------------------------------------------------------------
    function applyFilter() {
      if (!outline) { return; }
      var q = filter.value.trim(), hits = outline.filter(q);
      var shown = hits.filter(function (id) { var n = outline.full.node(id); return n && n.data.field; }).length;
      counts.textContent = q ? shown + ' of ' + total + ' paths match' : total + ' paths';
    }
    filter.addEventListener('input', applyFilter);
    $('[data-expand-all]').addEventListener('click', function () { if (outline) { outline.expandAll(); } });
    $('[data-collapse-all]').addEventListener('click', function () { if (outline) { outline.collapseAll(); } });

    return {
      show: function (rep) { attention(rep); build(rep.paths); },
      fieldAt: function (elm) { var n = outline ? outline.nodeAt(elm) : null; return n && n.data.field ? n.data.field : null; }
    };
  };
})();
