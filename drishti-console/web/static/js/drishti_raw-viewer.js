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
/* Raw document viewer: an entity's JSON (/api/raw) as drishti-tree's tree grid (Drishti Elements, loaded on demand; a Key and a Value
   column) in a dialog on the same page, coloured from the theme's tokens, keys in the document's own order, long arrays cut at 50
   with "show all". It highlights and scrolls to a path
   ($.SLOPE_2S_10S, $.legs[0].notional), filters keys and values, copies the whole document or the selected node, and downloads
   <kind>-<id>-<date>.json. Masked values are drawn exactly as the server sent them (•••). Escape closes it and focus goes back.
   window.Drishti.Raw.open({kind, id, path, date, asOf}) opens it; Drishti.Raw.render(el, data, {path}) draws a tree anywhere (F9; a promise of the tree). */
(function () {
  'use strict';
  var CUT = 50, OPEN_DEPTH = 2, MAX_HITS = 500, MASK = /^[•*]{3,}$/;
  var dlg = null, state = null, MORE = '#more';

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

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function scalarEl(v) {
    if (v === null) { return el('span', 'jt-null', 'null'); }
    if (typeof v === 'string') {
      var m = el('span', MASK.test(v) ? 'jt-mask' : 'jt-str', MASK.test(v) ? v : '"' + v + '"');
      if (MASK.test(v)) { m.title = 'masked for you'; }
      return m;
    }
    return el('span', typeof v === 'number' ? 'jt-num' : typeof v === 'boolean' ? 'jt-bool' : '', String(v));
  }
  function summary(v) {
    var n = keysOf(v).length;
    return Array.isArray(v) ? '[ ' + n + (n === 1 ? ' item' : ' items') + ' ]' : '{ ' + n + (n === 1 ? ' key' : ' keys') + ' }';
  }

  /** The document as rows of a tree (ID: the node's path): opts.show the paths to draw (a search), opts.open paths whose arrays are not
      cut (a path to highlight past the cut), opts.all the arrays shown whole ("show all"). */
  function rows(data, opts) {
    var out = [];
    (function walk(key, v, jp, parent) {
      out.push({ ID: jp, PARENT: parent, LABEL: key === null ? '$' : String(key), key: key, v: v, box: isBox(v) });
      if (!isBox(v)) { return; }
      var keys = keysOf(v).filter(function (k) { return !opts.show || opts.show.has(child(jp, k)); });
      var need = opts.open ? keys.findIndex(function (k) { return opts.open.has(child(jp, k)); }) : -1;
      var n = Array.isArray(v) && !opts.show && !opts.all.has(jp) && keys.length > CUT && need < CUT ? CUT : keys.length;
      keys.slice(0, n).forEach(function (k) { walk(k, v[k], child(jp, k), jp); });
      if (n < keys.length) { out.push({ ID: jp + MORE, PARENT: jp, LABEL: 'Show all ' + keys.length + ' (' + (keys.length - n) + ' more)', more: jp }); }
    })(null, data, '$', null);
    return out;
  }
  function keyCell(c) {
    var r = c.row;
    if (r.more) { return el('span', 'jt-more', r.LABEL); }
    return r.key === null ? el('span', 'jt-p', '$') : el('span', typeof r.key === 'number' ? 'jt-idx' : 'jt-key', String(r.key));
  }
  function valueCell(c) { var r = c.row; return r.more ? '' : r.box ? el('span', 'jt-sum', summary(r.v)) : scalarEl(r.v); }

  /** The library (drishti-tree, on demand). */
  function library() {
    var E = window.Drishti && window.Drishti.elements;
    if (E && E.tree) { return Promise.resolve(E.tree); }
    var load = E && (E.load || (E.core && E.core.load));
    return load ? load('drishti-tree') : Promise.reject(new Error('Drishti Elements are not on this page'));
  }

  /**
   * Draws `data` into `host` as a tree grid: opts.show/hits a search's paths, opts.path a path opened and selected, opts.onSelect(jp)
   * a click on a node. Returns a promise of {tree, target} (target: the path's node id, or null).
   */
  function draw(host, data, opts) {
    opts = opts || {};
    var at = opts.path ? resolve(data, opts.path) : null, all = new Set(), hits = opts.hits || (at ? new Set([at.jp]) : new Set());
    var open = at ? new Set(at.chain) : null, sel = { jp: at ? at.jp : null };
    return library().then(function (T) {
      if (host._jt) { host._jt.destroy(); }
      host.textContent = '';
      host.classList.add('jt');
      var source = function () { return { rows: rows(data, { show: opts.show || null, open: open, all: all }), idField: 'ID', parentField: 'PARENT', labelField: 'LABEL' }; };
      var tree = new T.Tree(host, { data: source(), chrome: false, mode: 'grid', density: 'compact', title: 'Key', label: 'Raw document',
        openDepth: opts.show ? -1 : OPEN_DEPTH,
        grid: { phoneWidth: 1, fitRows: 0, labelColumn: { header: 'Key', width: 240, cellRenderer: keyCell },
          columns: [{ id: 'value', header: 'Value', width: 560, sortable: false, valueGetter: function (r) { return r.box ? summary(r.v) : r.v; }, cellRenderer: valueCell }],
          rowClass: function (r) { return ((hits.has(r.ID) ? 'jt-hit ' : '') + (sel.jp === r.ID ? 'jt-sel' : '')).trim() || null; },
          rowData: function (r) { return r.more ? { jtMore: '', jp: null } : { jp: r.ID }; } } });
      host._jt = tree;
      tree.sel = sel;
      tree.on('selectionChanged', function (e) {
        var id = e.ids[0], n = id ? tree.model.node(id) : null;
        if (n && n.data.more) { all.add(n.data.more); tree.setData(source()); tree.select(n.data.more, { quiet: true }); return; }
        sel.jp = id || null;
        if (opts.onSelect) { opts.onSelect(sel.jp); }
        tree.select(sel.jp, { quiet: true });
      });
      if (at) { tree.model.reveal(at.jp); tree.select(at.jp); }
      return { tree: tree, target: at ? at.jp : null };
    });
  }

  /** Draws `data` into `el` as a tree; with opts.path, opens down to that path and marks it (F9's drawer). A promise of the tree. */
  function render(el, data, opts) { return draw(el, data, { path: (opts || {}).path }); }

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
  function select(jp) {
    state.selected = jp || null;
    dlg.querySelector('[data-rv-copy-node]').disabled = !state.selected;
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
    dlg.querySelector('[data-rv-close]').addEventListener('click', function () { dlg.close(); });
    dlg.addEventListener('close', function () {
      var back = state && state.opener;
      state = null;
      if (back && back.isConnected && typeof back.focus === 'function') { back.focus(); }
    });
    dlg.addEventListener('click', function (e) { if (e.target === dlg) { dlg.close(); } });   // a click on the backdrop
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
    var box = dlg.querySelector('[data-rv-tree]'), count = dlg.querySelector('[data-rv-count]'), mine = state;
    select(null);
    if (!q) { count.textContent = ''; show(); return; }
    var r = search(state.data, q);
    count.textContent = r.hits.size >= MAX_HITS ? 'first ' + MAX_HITS + ' matches' : r.hits.size + (r.hits.size === 1 ? ' match' : ' matches');
    if (!r.hits.size) { if (box._jt) { box._jt.destroy(); box._jt = null; } box.innerHTML = '<p class="raw-dnone text-muted-d">Nothing matches “' + esc(q) + '”.</p>'; return; }
    draw(box, state.data, { show: r.show, hits: r.hits, onSelect: select }).then(function (t) { if (state === mine) { state.tree = t.tree; } });
  }
  function show() {
    var mine = state;
    return draw(dlg.querySelector('[data-rv-tree]'), state.data, { path: state.path, onSelect: select }).then(function (t) {
      if (state !== mine) { return; }
      state.tree = t.tree;
      select(t.target);
    });
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
    var box = dlg.querySelector('[data-rv-tree]');
    if (box._jt) { box._jt.destroy(); box._jt = null; }
    box.textContent = '';
    say('Loading…');
    if (!dlg.open) { dlg.showModal(); }
    dlg.querySelector('[data-rv-search]').focus();
    var mine = state, q = opts.asOf ? '?asOf=' + encodeURIComponent(opts.asOf) : '';
    fetch('/api/raw/' + encodeURIComponent(opts.kind) + '/' + encodeURIComponent(opts.id) + q)
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
      .then(function (res) {
        if (state !== mine) { return; }
        if (!res.ok) { say(window.Drishti.drsMessage ? window.Drishti.drsMessage(res.body) : 'Could not load the document.'); return; }
        var d = res.body, p = d.provenance || {};
        state.data = d.data !== undefined ? d.data : d;
        state.date = state.date || p.businessDate || p.dataDate || null;
        dlg.querySelector('[data-rv-meta]').textContent = [p.source, p.generation != null ? 'gen ' + p.generation : '', p.businessDate ? 'as of ' + p.businessDate : '']
          .filter(Boolean).join(' · ');
        say('');
        return show().then(function () { if (state === mine) { dlg.setAttribute('data-rv-ready', '1'); } });
      })
      .catch(function (e) { if (state === mine) { say('Could not load the document: ' + e); } });
  }

  window.Drishti.Raw = { open: open, render: render, resolve: resolve, fileName: fileName, copyText: copyText };
})();
