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
/* Raw document viewer: an entity's JSON (/api/raw) as a collapsible tree in a dialog on the same page, coloured from the theme's
   tokens, keys in the document's own order, long arrays cut at 50 with "show all". It highlights and scrolls to a path
   ($.SLOPE_2S_10S, $.legs[0].notional), filters keys and values, copies the whole document or the selected node, and downloads
   <kind>-<id>-<date>.json. Masked values are drawn exactly as the server sent them (•••). Escape closes it and focus goes back.
   window.DrishtiRaw.open({kind, id, path, date, asOf}) opens it; DrishtiRaw.render(el, data, {path}) draws a tree anywhere (F9). */
(function () {
  'use strict';
  var CUT = 50, OPEN_DEPTH = 2, MAX_HITS = 500, MASK = /^[•*]{3,}$/;
  var dlg = null, state = null;

  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function child(jp, key) {
    if (typeof key === 'number') { return jp + '[' + key + ']'; }
    return /^[A-Za-z_$][\w$]*$/.test(key) ? jp + '.' + key : jp + "['" + String(key).replace(/'/g, "\\'") + "']";
  }
  function isBox(v) { return v !== null && typeof v === 'object'; }
  function keysOf(v) { return Array.isArray(v) ? v.map(function (_, i) { return i; }) : Object.keys(v); }

  /** The steps of a path ('$.a[0]["b c"]' -> ['a', 0, 'b c']); stops at the first step it cannot follow (a wildcard, a filter). */
  function steps(path) {
    var out = [], s = String(path || '').trim().replace(/^\$/, ''), m;
    var re = /^(?:\.([^.\[\]]+)|\[(\d+)\]|\['([^']*)'\]|\["([^"]*)"\])/;
    while (s && (m = re.exec(s))) {
      out.push(m[2] != null ? Number(m[2]) : m[1] != null ? m[1] : m[3] != null ? m[3] : m[4]);
      s = s.slice(m[0].length);
    }
    return out;
  }
  /** The deepest node of `data` the path reaches: its canonical path and the chain of paths above it. */
  function resolve(data, path) {
    var v = data, jp = '$', chain = ['$'];
    steps(path).some(function (k) {
      if (!isBox(v)) { return true; }
      if (Array.isArray(v) ? typeof k !== 'number' || k >= v.length : !Object.prototype.hasOwnProperty.call(v, k)) { return true; }
      v = v[k]; jp = child(jp, k); chain.push(jp);
      return false;
    });
    return { jp: jp, chain: chain };
  }
  function node(data, jp) {
    var v = data;
    steps(jp).forEach(function (k) { v = isBox(v) ? v[k] : undefined; });
    return v;
  }

  function scalar(v) {
    if (v === null) { return '<span class="jt-null">null</span>'; }
    if (typeof v === 'string') {
      return MASK.test(v) ? '<span class="jt-mask" title="masked for you">' + esc(v) + '</span>' : '<span class="jt-str">"' + esc(v) + '"</span>';
    }
    if (typeof v === 'number') { return '<span class="jt-num">' + esc(v) + '</span>'; }
    if (typeof v === 'boolean') { return '<span class="jt-bool">' + v + '</span>'; }
    return esc(v);
  }
  function summary(v) {
    var n = keysOf(v).length;
    return Array.isArray(v) ? '[ ' + n + (n === 1 ? ' item' : ' items') + ' ]' : '{ ' + n + (n === 1 ? ' key' : ' keys') + ' }';
  }

  /** A tree over `data`. opts.show: the paths to draw (a search); opts.open: paths to open; opts.hits: paths to mark. */
  function Tree(root, data, opts) {
    this.root = root; this.data = data; this.opts = opts || {};
    root.innerHTML = '';
    root.classList.add('jt');
    root.setAttribute('role', 'tree');
    root.appendChild(this.node(null, data, '$', 0));
  }
  Tree.prototype.node = function (key, v, jp, depth) {
    var o = this.opts, row = document.createElement('div'), box = isBox(v), line = document.createElement('div');
    row.className = 'jt-n';
    row.setAttribute('role', 'treeitem');
    row.setAttribute('data-jp', jp);
    line.className = 'jt-l';
    line.style.paddingLeft = (depth * 1.1) + 'rem';
    var label = key === null ? '' : typeof key === 'number' ? '<span class="jt-idx">' + key + '</span><span class="jt-p">:</span> '
      : '<span class="jt-key">' + esc(key) + '</span><span class="jt-p">:</span> ';
    line.innerHTML = (box ? '<button type="button" class="jt-t" tabindex="-1" aria-hidden="true">▸</button>' : '<span class="jt-t jt-leaf"></span>')
      + label + (box ? '<span class="jt-sum">' + summary(v) + '</span>' : scalar(v));
    if (o.hits && o.hits.has(jp)) { line.classList.add('jt-hit'); }
    row.appendChild(line);
    if (box) {
      row.setAttribute('aria-expanded', 'false');
      row._v = v; row._depth = depth;
      if (o.show || depth < OPEN_DEPTH || o.open && o.open.has(jp)) { this.expand(row); }
    }
    return row;
  };
  Tree.prototype.expand = function (row, all) {
    if (row.getAttribute('aria-expanded') === 'true' && !all) { return; }
    var self = this, v = row._v, jp = row.getAttribute('data-jp'), o = this.opts, kids = row.querySelector(':scope > .jt-c');
    if (kids) { kids.remove(); }
    kids = document.createElement('div');
    kids.className = 'jt-c';
    kids.setAttribute('role', 'group');
    var keys = keysOf(v).filter(function (k) { return !o.show || o.show.has(child(jp, k)); });
    var need = o.open ? keys.findIndex(function (k) { return o.open.has(child(jp, k)); }) : -1;   // a path to highlight past the cut: no cut
    var cut = Array.isArray(v) && !all && !o.show && keys.length > CUT && need < CUT ? CUT : keys.length;
    keys.slice(0, cut).forEach(function (k) { kids.appendChild(self.node(k, v[k], child(jp, k), row._depth + 1)); });
    if (cut < keys.length) {
      var more = document.createElement('button');
      more.type = 'button';
      more.className = 'jt-more';
      more.setAttribute('data-jt-more', '');
      more.style.marginLeft = ((row._depth + 1) * 1.1 + 1.2) + 'rem';
      more.textContent = 'Show all ' + keys.length + ' (' + (keys.length - cut) + ' more)';
      more.addEventListener('click', function () { self.expand(row, true); });
      kids.appendChild(more);
    }
    row.appendChild(kids);
    row.setAttribute('aria-expanded', 'true');
    row.querySelector(':scope > .jt-l .jt-t').textContent = '▾';
    var sum = row.querySelector(':scope > .jt-l .jt-sum');
    if (sum) { sum.textContent = Array.isArray(v) ? '[' : '{'; sum.title = summary(v); }
  };
  Tree.prototype.collapse = function (row) {
    var kids = row.querySelector(':scope > .jt-c');
    if (kids) { kids.remove(); }
    row.setAttribute('aria-expanded', 'false');
    row.querySelector(':scope > .jt-l .jt-t').textContent = '▸';
    row.querySelector(':scope > .jt-l .jt-sum').textContent = summary(row._v);
  };
  Tree.prototype.toggle = function (row) {
    if (row.getAttribute('aria-expanded') === 'true') { this.collapse(row); } else { this.expand(row); }
  };
  Tree.prototype.find = function (jp) {
    var rows = this.root.querySelectorAll('.jt-n');
    for (var i = 0; i < rows.length; i++) { if (rows[i].getAttribute('data-jp') === jp) { return rows[i]; } }
    return null;
  };

  /** Draws `data` into `el` as a tree; with opts.path, opens down to that path and marks it. Returns the tree (tree.target). */
  function render(el, data, opts) {
    opts = opts || {};
    var at = opts.path ? resolve(data, opts.path) : null;
    var tree = new Tree(el, data, at ? { open: new Set(at.chain), hits: new Set([at.jp]) } : {});
    tree.target = at ? tree.find(at.jp) : null;
    if (!el._jtClick) {   // the toggles of a tree drawn outside the dialog (F9)
      el._jtClick = true;
      el.addEventListener('click', function (e) {
        var t = e.target.closest('.jt-t'), row = t && t.closest('.jt-n');
        if (row && row._v && el._jtTree && !el.closest('.raw-dlg')) { el._jtTree.toggle(row); }
      });
    }
    el._jtTree = tree;
    return tree;
  }

  /** Every path whose key or scalar value contains the text, and every node above them (what a search shows). */
  function search(data, text) {
    var q = text.toLowerCase(), hits = new Set(), show = new Set(['$']);
    (function walk(v, jp, chain) {
      keysOf(v).forEach(function (k) {
        if (hits.size >= MAX_HITS) { return; }
        var p = child(jp, k), x = v[k];
        if (String(k).toLowerCase().indexOf(q) >= 0 || !isBox(x) && String(x).toLowerCase().indexOf(q) >= 0) {
          hits.add(p);
          chain.concat([p]).forEach(function (c) { show.add(c); });
        }
        if (isBox(x)) { walk(x, p, chain.concat([p])); }
      });
    })(data, '$', []);
    hits.forEach(function (p) {   // a container that matched shows its whole content
      var v = node(data, p);
      if (isBox(v)) {
        (function all(x, jp) { keysOf(x).forEach(function (k) { var c = child(jp, k); show.add(c); if (isBox(x[k])) { all(x[k], c); } }); })(v, p);
      }
    });
    return { hits: hits, show: show };
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) { return navigator.clipboard.writeText(text); }
    return new Promise(function (ok, no) {   // plain http on a LAN address: no navigator.clipboard
      var ta = document.createElement('textarea');
      ta.value = text; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
      (dlg || document.body).appendChild(ta); ta.select();
      var done = false;
      try { done = document.execCommand('copy'); } catch (e) { done = false; }
      ta.remove();
      if (done) { ok(); } else { no(new Error('copy refused')); }
    });
  }
  function fileName(kind, id, date) {
    var safe = function (s) { return String(s).replace(/[^\w.\-]+/g, '_'); };
    return safe(kind) + '-' + safe(id) + '-' + safe(date || new Date().toISOString().slice(0, 10)) + '.json';
  }

  function say(t) { dlg.querySelector('[data-rv-msg]').textContent = t; }
  function select(row) {
    dlg.querySelectorAll('.jt-sel').forEach(function (l) { l.classList.remove('jt-sel'); });
    state.selected = row ? row.getAttribute('data-jp') : null;
    if (row) { row.querySelector(':scope > .jt-l').classList.add('jt-sel'); }
    dlg.querySelector('[data-rv-copy-node]').disabled = !row;
  }
  function build() {
    dlg = document.createElement('dialog');
    dlg.className = 'raw-dlg';
    dlg.setAttribute('aria-labelledby', 'rawDlgTitle');
    dlg.innerHTML = '<header class="raw-dh"><h3 id="rawDlgTitle" data-rv-title>Raw document</h3><span class="mono raw-dmeta" data-rv-meta></span>'
      + '<button type="button" class="raw-x" data-rv-close aria-label="Close the raw document">×</button></header>'
      + '<div class="raw-dtools"><input type="search" class="raw-dsearch" data-rv-search placeholder="Search keys and values" aria-label="Search keys and values">'
      + '<span class="raw-dcount text-muted-d" data-rv-count aria-live="polite"></span>'
      + '<button type="button" class="fk" data-rv-copy-node disabled title="Copy the selected node as JSON">Copy node</button>'
      + '<button type="button" class="fk" data-rv-copy title="Copy the whole document as JSON">Copy JSON</button>'
      + '<button type="button" class="fk" data-rv-download title="Save the document as a .json file">Download</button></div>'
      + '<p class="raw-dpath" data-rv-path hidden></p><p class="raw-dmsg text-muted-d" data-rv-msg role="status"></p>'
      + '<div class="raw-dbody mono" data-rv-tree tabindex="0"></div>';
    document.body.appendChild(dlg);
    var tree = dlg.querySelector('[data-rv-tree]');
    dlg.querySelector('[data-rv-close]').addEventListener('click', function () { dlg.close(); });
    dlg.addEventListener('close', function () {
      var back = state && state.opener;
      state = null;
      if (back && back.isConnected && typeof back.focus === 'function') { back.focus(); }
    });
    dlg.addEventListener('click', function (e) { if (e.target === dlg) { dlg.close(); } });   // a click on the backdrop
    tree.addEventListener('click', function (e) {
      if (!state || !state.tree) { return; }
      var row = e.target.closest('.jt-n');
      if (!row || e.target.closest('[data-jt-more]')) { return; }
      if (e.target.closest('.jt-t') && row._v) { state.tree.toggle(row); return; }
      select(row);
    });
    var timer = null;
    dlg.querySelector('[data-rv-search]').addEventListener('input', function (e) {
      clearTimeout(timer);
      var q = e.target.value.trim();
      timer = setTimeout(function () { filter(q); }, 120);
    });
    dlg.querySelector('[data-rv-copy]').addEventListener('click', function () {
      if (!state || state.data === undefined) { return; }
      copyText(JSON.stringify(state.data, null, 2)).then(function () { say('Copied the whole document.'); }, function () { say('The browser refused to copy.'); });
    });
    dlg.querySelector('[data-rv-copy-node]').addEventListener('click', function () {
      if (!state || !state.selected) { return; }
      var v = node(state.data, state.selected), at = state.selected;
      copyText(JSON.stringify(v, null, isBox(v) ? 2 : 0)).then(function () { say('Copied ' + at + '.'); }, function () { say('The browser refused to copy.'); });
    });
    dlg.querySelector('[data-rv-download]').addEventListener('click', function () {
      if (!state || state.data === undefined) { return; }
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([JSON.stringify(state.data, null, 2)], { type: 'application/json' }));
      a.download = fileName(state.kind, state.id, state.date);
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
      say('Saved ' + a.download + '.');
    });
  }

  function filter(q) {
    if (!state || state.data === undefined) { return; }
    var el = dlg.querySelector('[data-rv-tree]'), count = dlg.querySelector('[data-rv-count]');
    select(null);
    if (!q) { count.textContent = ''; show(); return; }
    var r = search(state.data, q);
    state.tree = new Tree(el, state.data, { show: r.show, hits: r.hits });
    count.textContent = r.hits.size >= MAX_HITS ? 'first ' + MAX_HITS + ' matches' : r.hits.size + (r.hits.size === 1 ? ' match' : ' matches');
    if (!r.hits.size) { el.innerHTML = '<p class="raw-dnone text-muted-d">Nothing matches “' + esc(q) + '”.</p>'; }
  }
  function show() {
    state.tree = render(dlg.querySelector('[data-rv-tree]'), state.data, { path: state.path });
    if (state.tree.target) {
      select(state.tree.target);
      var line = state.tree.target.querySelector(':scope > .jt-l');
      requestAnimationFrame(function () { line.scrollIntoView({ block: 'center' }); });
    }
  }

  /** Opens the viewer on kind/id: opts.path is highlighted, opts.date names the download, opts.asOf asks for that business day. */
  function open(opts) {
    if (!dlg) { build(); }
    state = { kind: opts.kind, id: opts.id, path: opts.path || null, date: opts.date || null, opener: opts.opener || document.activeElement };
    dlg.removeAttribute('data-rv-ready');
    dlg.querySelector('[data-rv-title]').textContent = opts.kind + ' ' + opts.id + ' · raw document';
    dlg.querySelector('[data-rv-meta]').textContent = '';
    dlg.querySelector('[data-rv-search]').value = '';
    dlg.querySelector('[data-rv-count]').textContent = '';
    dlg.querySelector('[data-rv-copy-node]').disabled = true;
    var pathEl = dlg.querySelector('[data-rv-path]');
    pathEl.hidden = !state.path;
    pathEl.innerHTML = state.path ? 'Highlighted: <span class="mono">' + esc(state.path) + '</span>' : '';
    dlg.querySelector('[data-rv-tree]').innerHTML = '';
    say('Loading…');
    if (!dlg.open) { dlg.showModal(); }
    dlg.querySelector('[data-rv-search]').focus();
    var mine = state, q = opts.asOf ? '?asOf=' + encodeURIComponent(opts.asOf) : '';
    fetch('/api/raw/' + encodeURIComponent(opts.kind) + '/' + encodeURIComponent(opts.id) + q)
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
      .then(function (res) {
        if (state !== mine) { return; }
        if (!res.ok) { say(window.drsMessage ? window.drsMessage(res.body) : 'Could not load the document.'); return; }
        var d = res.body, p = d.provenance || {};
        state.data = d.data !== undefined ? d.data : d;
        state.date = state.date || p.businessDate || p.dataDate || null;
        dlg.querySelector('[data-rv-meta]').textContent = [p.source, p.generation != null ? 'gen ' + p.generation : '', p.businessDate ? 'as of ' + p.businessDate : '']
          .filter(Boolean).join(' · ');
        say('');
        show();
        dlg.setAttribute('data-rv-ready', '1');
      })
      .catch(function (e) { if (state === mine) { say('Could not load the document: ' + e); } });
  }

  window.DrishtiRaw = { open: open, render: render, resolve: resolve, fileName: fileName };
})();
