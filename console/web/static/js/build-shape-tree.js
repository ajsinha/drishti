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
/* The shape tree of the Build workbench: conflicts and rare fields first, then an accessible tree (role=tree: arrows,
 * Home/End, Right/Left to open and close, Enter for a field's reason), with a filter. Used by New and by a Design's page.
 * window.DrishtiShapeTree(root) wires the elements inside root that carry data-tree, data-filter, data-counts, data-attention,
 * data-conflicts, data-rare, data-expand-all and data-collapse-all, and returns {show(report)}. All text from the data goes in as
 * text nodes, never as HTML. opts.onRow(row, li, line, detail), when given, is called for every field so the workbench can make it
 * draggable and add its own buttons. */
(function () {
  'use strict';
  window.DrishtiShapeTree = function (root, opts) {
    opts = opts || {};
    var $ = function (s) { return root.querySelector(s); };
    var tree = $('[data-tree]'), counts = $('[data-counts]'), filter = $('[data-filter]');
    var rows = [];            // [{n, li, kids, detail, text}]

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

  // ---- the tree --------------------------------------------------------------------------------------------------
  var SEG = /\.([^.\[\]{}]+)|\[\]|\{\}|\['((?:[^'\\]|\\.)*)'\]/g;
  function segments(path) {
    var out = [], m;
    SEG.lastIndex = 0;
    while ((m = SEG.exec(path.slice(1))) !== null) { out.push(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2].replace(/\\'/g, "'") : m[0]); }
    return out;
  }

  function build(paths) {
    tree.textContent = '';
    rows = [];
    var byPath = {};
    var rootNode = { children: [], ul: tree, level: 0 };
    byPath['$'] = rootNode;
    paths.forEach(function (p) {
      var segs = segments(p.path), parent = rootNode;
      if (!segs.length) { return; }
      var key = '$';
      for (var i = 0; i < segs.length; i++) {
        key += '\u0001' + segs[i];
        var n = byPath[key];
        if (!n) {
          n = { label: segs[i], row: null, children: [], parent: parent, level: parent.level + 1, path: key };
          byPath[key] = n;
          parent.children.push(n);
        }
        parent = n;
      }
      parent.row = p;
    });
    rootNode.children.forEach(function (n) { render(n, tree); });
    var first = tree.querySelector('[role="treeitem"]');
    if (first) { first.tabIndex = 0; }
    applyFilter();
  }

  function render(n, ul) {
    var li = el('li');
    li.setAttribute('role', 'treeitem');
    li.setAttribute('aria-level', n.level);
    li.tabIndex = -1;
    var r = n.row, hasKids = n.children.length > 0;
    if (hasKids) { li.setAttribute('aria-expanded', 'true'); }
    var line = el('div', 'bs-row');
    line.appendChild(el('span', 'bs-twist', hasKids ? '▾' : ''));
    line.appendChild(el('span', 'bs-name mono', n.label));
    if (r) {
      line.appendChild(el('span', 'bs-type mono', r.type));
      if (r.role) {
        var b = el('span', 'bs-role' + (r.conflict ? ' conflict' : ''), r.role);
        b.title = r.reason || '';
        line.appendChild(b);
      }
      if (r.conflict) { line.appendChild(el('span', 'bs-role conflict', 'conflict')); }
      line.appendChild(el('span', 'bs-pres' + (r.presence < 0.5 ? ' rare' : ''), pct(r.presence)));
      if (r.examples && r.examples.length) {
        var ex = el('span', 'bs-ex mono', r.examples.join(', '));
        ex.title = r.examples.join(', ');
        line.appendChild(ex);
      }
    }
    li.appendChild(line);
    var detail = null;
    if (r) {
      detail = el('div', 'bs-detail');
      detail.hidden = true;
      detail.appendChild(el('div', null, 'Path: ' + r.path));
      detail.appendChild(el('div', null, 'Role: ' + (r.role || 'none') + (r.reason ? ' — ' + r.reason : '')));
      detail.appendChild(el('div', null, 'Present in ' + pct(r.presence) + ' of its records' + (r.masked ? ' (masked)' : '')));
      if (r.examples && r.examples.length) { detail.appendChild(el('div', null, 'Examples: ' + r.examples.join(', '))); }
      if (r.files && r.files.length) { detail.appendChild(el('div', null, 'Files: ' + r.files.join(', '))); }
      li.appendChild(detail);
    }
    var kids = null;
    if (hasKids) {
      kids = el('ul');
      kids.setAttribute('role', 'group');
      n.children.forEach(function (c) { render(c, kids); });
      li.appendChild(kids);
    }
    if (r && opts.onRow) { opts.onRow(r, li, line, detail); }
    ul.appendChild(li);
    var rec = { n: n, li: li, kids: kids, detail: detail, text: ((r ? r.path + ' ' + r.type + ' ' + (r.role || '') + ' ' + (r.reason || '') : n.label)).toLowerCase() };
    li._rec = rec;
    rows.push(rec);
    line.addEventListener('click', function () { focusItem(li); toggle(li); });
  }

  function toggle(li, open) {
    var rec = li._rec;
    if (rec.kids) {
      var want = open === undefined ? li.getAttribute('aria-expanded') !== 'true' : open;
      li.setAttribute('aria-expanded', want ? 'true' : 'false');
      rec.kids.hidden = !want;
      li.querySelector('.bs-twist').textContent = want ? '▾' : '▸';
    } else if (rec.detail) {
      rec.detail.hidden = !rec.detail.hidden;
    }
  }

  function visibleItems() {
    return Array.prototype.filter.call(tree.querySelectorAll('[role="treeitem"]'), function (li) {
      for (var p = li; p && p !== tree; p = p.parentNode) { if (p.hidden) { return false; } }
      return true;
    });
  }
  function focusItem(li) {
    Array.prototype.forEach.call(tree.querySelectorAll('[role="treeitem"][tabindex="0"]'), function (x) { x.tabIndex = -1; });
    li.tabIndex = 0;
    li.focus();
  }

  tree.addEventListener('keydown', function (e) {
    var li = e.target.closest && e.target.closest('[role="treeitem"]');
    if (!li || e.target !== li) { return; }
    var items = visibleItems(), i = items.indexOf(li), rec = li._rec;
    var handled = true;
    switch (e.key) {
      case 'ArrowDown': if (i < items.length - 1) { focusItem(items[i + 1]); } break;
      case 'ArrowUp': if (i > 0) { focusItem(items[i - 1]); } break;
      case 'Home': focusItem(items[0]); break;
      case 'End': focusItem(items[items.length - 1]); break;
      case 'ArrowRight':
        if (rec.kids && li.getAttribute('aria-expanded') !== 'true') { toggle(li, true); }
        else if (rec.kids) { focusItem(rec.kids.querySelector('[role="treeitem"]')); }
        break;
      case 'ArrowLeft':
        if (rec.kids && li.getAttribute('aria-expanded') === 'true') { toggle(li, false); }
        else { var up = li.parentNode.closest('[role="treeitem"]'); if (up) { focusItem(up); } }
        break;
      case 'Enter': case ' ': toggle(li); break;
      default: handled = false;
    }
    if (handled) { e.preventDefault(); }
  });

  function setAll(open) {
    rows.forEach(function (r) { if (r.kids) { toggle(r.li, open); } });
  }
  $('[data-expand-all]').addEventListener('click', function () { setAll(true); });
  $('[data-collapse-all]').addEventListener('click', function () { setAll(false); });

  // ---- filter ----------------------------------------------------------------------------------------------------
  function applyFilter() {
    var q = filter.value.trim().toLowerCase(), shown = 0, total = 0;
    rows.forEach(function (r) { r.hit = !q || r.text.indexOf(q) >= 0; if (r.n.row) { total++; } });
    rows.slice().reverse().forEach(function (r) {            // a parent stays when a descendant matches
      r.keep = r.hit || (r.kids && Array.prototype.some.call(r.kids.children, function (c) { return c._rec.keep; }));
    });
    rows.forEach(function (r) {
      r.li.hidden = !r.keep;
      if (r.keep && r.n.row && r.hit) { shown++; }
      if (q && r.keep && r.kids) { toggle(r.li, true); }
    });
    counts.textContent = q ? shown + ' of ' + total + ' paths match' : total + ' paths';
  }
  filter.addEventListener('input', applyFilter);

    return { show: function (rep) { attention(rep); build(rep.paths); } };
  };
})();
