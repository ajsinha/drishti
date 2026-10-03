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
/* Screen Builder, shape extractor page (/build/shape). Reads the chosen files in the browser, sends their text to the
 * console (which enforces the limits and asks the server), and draws the shape: conflicts and rare fields first, then an
 * accessible tree (role=tree: arrows, Home/End, Right/Left to open and close, Enter for a field's reason). All text from
 * the data goes in as text nodes, never as HTML. */
(function () {
  'use strict';
  var root = document.querySelector('[data-shape]');
  if (!root) { return; }
  var $ = function (s) { return root.querySelector(s); };
  var MB = 1048576;
  var maxFile = parseFloat(root.dataset.maxFileMb) * MB;
  var maxSamples = parseInt(root.dataset.maxSamples, 10);
  var status = $('[data-status]'), list = $('[data-file-list]'), result = $('[data-result]'), tree = $('[data-tree]');
  var counts = $('[data-counts]'), filter = $('[data-filter]');
  var rows = [];            // [{path, node, li}]
  var current = null;

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }
  function say(text, bad) { status.textContent = text; status.classList.toggle('bad', !!bad); }
  function pct(p) { return (Math.round(p * 1000) / 10) + '%'; }
  function mb(n) { return n >= MB ? (n / MB).toFixed(1) + ' MB' : n >= 1024 ? Math.round(n / 1024) + ' KB' : n + ' bytes'; }

  // ---- upload ----------------------------------------------------------------------------------------------------
  function readAll(files) {
    var picked = Array.prototype.slice.call(files);
    if (!picked.length) { return; }
    if (picked.length > maxSamples) {
      say(picked.length + ' files chosen; at most ' + maxSamples + ' are taken in one upload. Choose fewer.', true);
      return;
    }
    say('Reading ' + picked.length + ' file' + (picked.length === 1 ? '' : 's') + '...');
    Promise.all(picked.map(function (f) {
      if (f.size > maxFile) { return Promise.resolve({ name: f.name, size: f.size }); }
      return f.text().then(function (t) { return { name: f.name, text: t }; },
        function () { return { name: f.name }; });
    })).then(send);
  }

  function send(files) {
    say('Extracting the shape...');
    fetch('/build/shape', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ files: files }) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); })
      .then(function (r) {
        if (r.body && r.body.files) { showFiles(r.body.files); }
        if (!r.ok) { say((r.body.detail || 'The shape could not be made') + ' (' + (r.body.code || r.status) + ')', true); return; }
        show(r.body, true);
      })
      .catch(function () { say('The console could not be reached. Try again.', true); });
  }

  function showFiles(files) {
    list.textContent = '';
    files.forEach(function (f) {
      var li = el('li');
      li.appendChild(el('span', 'mono', f.name));
      li.appendChild(document.createTextNode(' · ' + (f.samples || 0) + ' sample' + (f.samples === 1 ? '' : 's') + (f.size ? ' · ' + mb(f.size) : '')));
      (f.problems || []).forEach(function (p) { li.appendChild(el('div', 'prob', 'Problem: ' + p)); });
      list.appendChild(li);
    });
    list.hidden = !files.length;
  }

  function show(body, fresh) {
    current = body;
    showFiles(body.files || []);
    var problems = (body.files || []).filter(function (f) { return f.problems && f.problems.length; }).length;
    var rep = body.shape.report;
    say((fresh ? 'Shaped ' : 'Restored ') + body.sampleCount + ' sample' + (body.sampleCount === 1 ? '' : 's') + ' from ' + body.files.length + ' file' +
      (body.files.length === 1 ? '' : 's') + ': ' + rep.paths.filter(function (p) { return p.path !== '$'; }).length + ' paths, ' + rep.conflicts.length + ' conflict' + (rep.conflicts.length === 1 ? '' : 's') +
      ', ' + rep.rare.length + ' rare' + (problems ? '; ' + problems + ' file' + (problems === 1 ? ' had' : 's had') + ' problems and ' + (problems === 1 ? 'was' : 'were') + ' left out' : '') + '.', false);
    attention(rep);
    build(rep.paths);
    result.hidden = false;
  }

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

  // ---- actions ---------------------------------------------------------------------------------------------------
  $('[data-copy]').addEventListener('click', function () {
    fetch('/build/shape/download?kind=schema').then(function (r) { return r.text(); }).then(function (t) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        return navigator.clipboard.writeText(t).then(function () { say('Copied the plain JSON Schema.'); },
          function () { say('The browser would not copy. Use the JSON Schema download instead.', true); });
      }
      say('This browser cannot copy. Use the JSON Schema download instead.', true);
    });
  });
  $('[data-forget]').addEventListener('click', function () {
    fetch('/build/shape/last', { method: 'DELETE' }).then(function () {
      result.hidden = true; list.hidden = true; list.textContent = ''; current = null;
      say('The uploaded files are forgotten.');
    });
  });

  // ---- choosing and dropping files -------------------------------------------------------------------------------
  var input = $('[data-files]'), drop = $('[data-drop]');
  input.addEventListener('change', function () { readAll(input.files); input.value = ''; });
  ['dragenter', 'dragover'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
  ['dragleave', 'drop'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.remove('over'); }); });
  drop.addEventListener('drop', function (e) { if (e.dataTransfer) { readAll(e.dataTransfer.files); } });

  // ---- a reload keeps the last set (until it expires) ------------------------------------------------------------
  fetch('/build/shape/last').then(function (r) { return r.ok ? r.json() : null; }).then(function (b) { if (b) { show(b, false); } }, function () {});
})();
