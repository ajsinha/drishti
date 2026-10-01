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
/* Sutra Studio's assistant, driven by the server's Rachana JSON Schema (/studio/schema):
   - completion (Ctrl+Space, and on its own after "$.", "@." and "key: "): keys valid where the cursor is
     (panel options by the panel's kind), values of enums and booleans, Rachana-EL functions with their
     signature, and field paths from the JSON of the entity being previewed ("@." uses the panel's rows/each);
   - a light check while typing (unknown keys, missing required keys, options a panel's kind does not take,
     values outside an enum), marked in the gutter and handed to Studio's problem list;
   - field help: what the key under the cursor means.
   Uses window.drishtiYaml (studio-yaml.js). Exposes window.drishtiAssist.attach(cm, opts). */
(function () {
  'use strict';
  var Y = window.drishtiYaml;
  var PANEL_BASE = ['id', 'kind', 'title', 'description', 'key', 'code', 'area', 'infer', 'columns', 'body'];
  var FN_ARGS = {
    abs: ['number'], coalesce: ['a', 'b', '…'], contains: ['textOrList', 'value'], first: ['list'], fmt: ['value', "'format'"],
    last: ['list'], link: ['id', "'kind'", 'label'], lower: ['text'], max: ['a', '…'], min: ['a', '…'], size: ['listOrText'],
    startsWith: ['text', "'prefix'"], sum: ['list', "'field'"], upper: ['text']
  };
  var FN_HELP = {
    abs: 'the absolute value', coalesce: 'the first argument that is not null', contains: 'true when the text or list holds the value',
    first: 'the first element of a list', fmt: 'the value formatted with a format (amount0, pct4, date, …)', last: 'the last element of a list',
    link: 'a link that opens the entity id (of a kind; with a label)', lower: 'lower-case text', max: 'the largest value', min: 'the smallest value',
    size: 'the number of elements (or characters)', startsWith: 'true when the text starts with the prefix', sum: 'the sum of a list (or of a field of its rows)',
    upper: 'upper-case text'
  };

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  // ---- the schema ---------------------------------------------------------------------------------------
  function Schema(raw) {
    this.raw = raw;
    this.defs = raw.$defs || {};
    this.fns = raw['x-rachana-functions'] || {};
    var panel = this.defs.panel || {}, byKind = {}, optionKeys = {};
    (panel.allOf || []).forEach(function (a) {
      var k = a['if'] && a['if'].properties && a['if'].properties.kind && a['if'].properties.kind['const'];
      if (k && a.then) { byKind[k] = { required: a.then.required || [], options: a.then['x-rachana-options'] || [] }; }
      if (a.then) { (a.then['x-rachana-options'] || []).forEach(function (o) { optionKeys[o] = (optionKeys[o] || []).concat([k]); }); }
    });
    this.byKind = byKind;
    this.optionKinds = optionKeys;
    this.panelBase = PANEL_BASE.filter(function (k) { return panel.properties && panel.properties[k]; });
  }
  Schema.prototype.resolve = function (n) {
    var guard = 0;
    while (n && n.$ref && guard++ < 8) { n = this.defs[n.$ref.replace('#/$defs/', '')]; }
    return n || null;
  };
  Schema.prototype.at = function (path) {
    var n = this.resolve(this.raw);
    for (var i = 0; i < path.length && n; i++) {
      n = this.resolve(path[i] === '[]' ? n.items : (n.properties || {})[path[i]]);
    }
    return n;
  };
  Schema.prototype.isPanel = function (n) { return !!n && n === this.defs.panel; };
  /** The keys a map may hold: for a panel, the common keys plus its kind's options. */
  Schema.prototype.keys = function (n, kind) {
    if (!n || !n.properties) { return []; }
    if (this.isPanel(n) && kind && this.byKind[kind]) {
      return this.panelBase.concat(this.byKind[kind].options.filter(function (o) { return PANEL_BASE.indexOf(o) < 0; }));
    }
    return Object.keys(n.properties);
  };
  Schema.prototype.child = function (n, key) { return n && n.properties ? this.resolve(n.properties[key]) : null; };
  Schema.prototype.isExpr = function (n) { return !!n && /Rachana-EL/.test(n.description || ''); };
  Schema.prototype.signature = function (name) {
    var spec = this.fns[name] || { min: 1, max: 1 }, args = (FN_ARGS[name] || []).slice();
    if (args.indexOf('…') < 0) {
      args = args.slice(0, spec.max);
      while (args.length < Math.min(spec.max, 3)) { args.push('arg' + (args.length + 1)); }
      if (spec.max > args.length) { args.push('…'); }
    }
    return name + '(' + args.map(function (a, i) { return i >= spec.min && a !== '…' ? a + '?' : a; }).join(', ') + ')';
  };
  Schema.prototype.describe = function (n, key, kind, inPanel) {
    if (!n) { return ''; }
    var bits = [n.description || ''];
    if (n['enum']) { bits.push('One of: ' + n['enum'].slice(0, 16).join(', ') + (n['enum'].length > 16 ? ', …' : '')); }
    if (n.type === 'boolean') { bits.push('true or false'); }
    if (inPanel && this.optionKinds[key] && PANEL_BASE.indexOf(key) < 0) { bits.push('Panel option of: ' + this.optionKinds[key].join(', ') + (kind ? ' (this panel is ' + kind + ')' : '')); }
    return bits.filter(Boolean).join(' · ');
  };

  // ---- the sample document and the rows "@" stands for ------------------------------------------------------
  function walk(v, expr) {
    var re = /\.([A-Za-z_$][\w$-]*)|\[(\d+)\]/g, m;
    while ((m = re.exec(expr)) && v != null) {
      if (m[2] !== undefined) { v = Array.isArray(v) ? v[+m[2]] : undefined; }
      else { if (Array.isArray(v)) { v = v[0]; } v = v != null && typeof v === 'object' ? v[m[1]] : undefined; }
    }
    return v;
  }
  function sample(v) {
    if (!Array.isArray(v)) { return v; }
    var o = {};
    v.slice(0, 25).forEach(function (e) { if (e && typeof e === 'object' && !Array.isArray(e)) { Object.keys(e).forEach(function (k) { if (!(k in o)) { o[k] = e[k]; } }); } });
    return Object.keys(o).length ? o : v[0];
  }
  /** What "@" is at the cursor: the element of the nearest enclosing rows/each, else the document. */
  function rowOf(chain, doc, skipLast) {
    var maps = chain.filter(function (n) { return n && n.t === 'map'; });
    if (skipLast) { maps = maps.slice(0, -1); }
    for (var i = maps.length - 1; i >= 0; i--) {
      var src = Y.get(maps[i], 'rows') || Y.get(maps[i], 'each');
      if (src && src.t === 'scalar' && typeof src.v === 'string') {
        var m = src.v.trim().match(/^([$@])((?:\.[A-Za-z_$][\w$-]*|\[\d+\])*)$/);
        if (!m) { return undefined; }
        var base = m[1] === '$' ? doc : rowOf(maps.slice(0, i), doc, false);
        return sample(walk(base, m[2]));
      }
    }
    return doc;
  }
  function preview(v) {
    if (v === null) { return 'null'; }
    if (Array.isArray(v)) { return 'list [' + v.length + ']'; }
    if (typeof v === 'object') { return '{ ' + Object.keys(v).slice(0, 4).join(', ') + (Object.keys(v).length > 4 ? ', …' : '') + ' }'; }
    var s = JSON.stringify(v);
    return s.length > 40 ? s.slice(0, 39) + '…' : s;
  }

  /** The light check: problems of a parsed Sutra against the schema (code CHECK, 1-based line and column). */
  function problemsOf(schema, tree) {
    var out = [];
    function add(node, len, msg) { out.push({ code: 'CHECK', location: { line: node.line + 1, column: (node.ch || 0) + 1 }, len: len, message: msg }); }
    function visit(node, s, label, key) {
      s = schema.resolve(s);
      if (!s || !node) { return; }
      if (s['const'] !== undefined && node.t === 'scalar' && node.v !== s['const']) { add(node, String(node.raw).length, label + ' must be ' + s['const']); }
      if (s['enum'] && node.t === 'scalar' && node.v !== '' && node.v !== null && s['enum'].indexOf(node.v) < 0 && !/\$\{/.test(String(node.v))) {
        add(node, String(node.raw).length, '"' + node.v + '" is not a ' + (key === 'kind' && label.indexOf('match') < 0 ? 'panel kind' : 'valid ' + key) + (s['enum'].length <= 16 ? ' (' + s['enum'].join(', ') + ')' : ''));
      }
      if (s.type === 'boolean' && node.t === 'scalar' && typeof node.v !== 'boolean') { add(node, String(node.raw).length, label + ' is true or false'); }
      if (s.type === 'integer' && node.t === 'scalar' && typeof node.v !== 'number') { add(node, String(node.raw).length, label + ' is a whole number'); }
      if (s.type === 'array' && node.t !== 'seq') { if (node.t !== 'scalar' || node.v !== '') { add(node, 1, label + ' is a list'); } return; }
      if (s.type === 'array' && s.maxItems && node.items.length > s.maxItems) { add(node, 1, label + ' holds at most ' + s.maxItems); }
      if (node.t === 'seq') { node.items.forEach(function (it, i) { visit(it, s.items, label + '[' + i + ']', key); }); return; }
      if (!s.properties) { return; }
      if (node.t !== 'map') { if (s.type === 'object') { add(node, String(node.raw || '').length || 1, label + ' is a map of keys'); } return; }
      var kindNode = Y.get(node, 'kind'), kind = schema.isPanel(s) && kindNode && kindNode.t === 'scalar' ? kindNode.v : '';
      var allowed = schema.keys(s, schema.byKind[kind] ? kind : ''), present = {};
      node.entries.forEach(function (e) {
        if (present[e.key] && !e.partial) { add(e, e.key.length, 'duplicate key "' + e.key + '"' + (label ? ' in ' + label : '')); }
        present[e.key] = true;
        if (e.partial) { add(e, e.key.length, '"' + e.key + '" needs a colon: key: value'); return; }
        if ((s.additionalProperties === false || schema.isPanel(s)) && allowed.indexOf(e.key) < 0) {
          add(e, e.key.length, s.properties[e.key] && kind ? '"' + e.key + '" is not an option of ' + kind + ' panels (it is of ' + (schema.optionKinds[e.key] || []).join(', ') + ')'
            : 'unknown key "' + e.key + '" in ' + (label || 'the Sutra'));
          return;
        }
        visit(e.value, s.properties[e.key], (label ? label + '.' : '') + e.key, e.key);
      });
      var req = (s.required || []).slice();
      if (kind && schema.byKind[kind]) { req = req.concat(schema.byKind[kind].required); }
      if (key === 'body') { req = req.filter(function (r) { return r !== 'id'; }); }
      req.forEach(function (r) { if (!present[r]) { add(node, 1, (label || 'the Sutra') + ' needs "' + r + '"' + (kind && schema.byKind[kind].required.indexOf(r) >= 0 ? ' (' + kind + ' panels)' : '')); } });
    }
    visit(tree, schema.raw, '', '');
    return out;
  }

  function attach(cm, opts) {
    var schema = null, hints = null, sel = 0, open = null, keymap = null, checkTimer = 0, helpTimer = 0, marks = [];
    fetch('/studio/schema').then(function (r) { return r.ok ? r.json() : null; })
      .then(function (raw) { if (raw && raw.properties) { schema = new Schema(raw); check(); help(); } })
      .catch(function () { /* the editor works without it */ });

    function lines() { return cm.getValue().split('\n'); }
    function where() {
      var cur = cm.getCursor(), ls = lines(), c = Y.context(ls, cur.line, cur.ch);
      c.tree = Y.parse(ls.join('\n'));
      c.chain = Y.chain(c.tree, c.path || [], cur.line);
      c.resolved = c.chain.length === (c.path || []).length + 1;
      c.map = c.resolved && c.chain[c.chain.length - 1].t === 'map' ? c.chain[c.chain.length - 1] : null;
      c.node = schema ? schema.at(c.path || []) : null;
      var kind = c.map && Y.get(c.map, 'kind');
      if (c.flow && !kind) { var mk = ls[cur.line].match(/[{,]\s*kind:\s*([\w-]+)/); kind = mk ? { v: mk[1] } : null; }
      c.kind = kind && typeof kind.v === 'string' ? kind.v : '';
      c.cur = cur;
      return c;
    }
    function wordEnd(line, ch) { var t = cm.getLine(line); while (ch < t.length && /[\w-]/.test(t[ch])) { ch++; } return ch; }

    // ---- completions ------------------------------------------------------------------------------------
    function gather(explicit) {
      if (!schema) { return Promise.resolve(null); }
      var c = where();
      if (c.scalar || c.comment || !c.mode) { return Promise.resolve(null); }
      var line = c.cur.line, to = wordEnd(line, c.cur.ch), p = (c.prefix || '').toLowerCase();
      if (c.mode === 'key') {
        if (!/^[\w-]*$/.test(c.prefix)) { return Promise.resolve(null); }
        var have = {};
        if (c.map) { c.map.entries.forEach(function (e) { if (e.line !== line) { have[e.key] = true; } }); }
        if (c.flow) { (cm.getLine(line).match(/[{,]\s*([\w-]+)\s*:/g) || []).forEach(function (k) { have[k.replace(/[{,:\s]/g, '')] = true; }); }
        var keys = schema.keys(c.node, c.kind).filter(function (k) { return !have[k] && k.toLowerCase().indexOf(p) === 0; });
        return Promise.resolve({ from: c.from, to: to, line: line, items: keys.map(function (k) {
          var n = schema.child(c.node, k);
          return { text: k + ': ', label: k, detail: n ? schema.describe(n, k, c.kind, schema.isPanel(c.node)) : '', kind: 'key', retrigger: !!(n && (n['enum'] || n.type === 'boolean' || schema.isExpr(n))) };
        }) });
      }
      var vs = c.mode === 'item' ? schema.resolve(c.node && c.node.items) : schema.child(c.node, c.key);
      if (!vs) { return Promise.resolve(null); }
      var values = vs['enum'] || (vs['const'] !== undefined ? [vs['const']] : null) || (vs.type === 'boolean' ? [true, false] : null);
      if (values) {
        var vp = p.replace(/^["']/, '');
        return Promise.resolve({ from: c.from, to: to, line: line, items: values.map(String).filter(function (v) { return v.toLowerCase().indexOf(vp) === 0; })
          .map(function (v) { return { text: v, label: v, detail: c.key === 'kind' && schema.byKind[v] ? 'options: ' + (schema.byKind[v].options.join(', ') || 'none') : '', kind: 'value' }; }) });
      }
      if (!schema.isExpr(vs)) { return Promise.resolve(null); }
      return expression(c, explicit, to);
    }
    function expression(c, explicit, to) {
      var line = c.cur.line, before = cm.getLine(line).slice(0, c.cur.ch), tail = before.slice(c.from);
      var m = tail.match(/([$@])((?:\.[A-Za-z_$][\w$-]*|\[\d+\])*)\.([\w$-]*)$/);
      if (m) {
        return opts.getDoc().then(function (doc) {
          if (doc === undefined) { return null; }
          var onRows = c.mode === 'value' && (c.key === 'rows' || c.key === 'each');
          var base = m[1] === '$' ? doc : rowOf(c.chain, doc, onRows);
          var v = walk(base, m[2]), dot = c.cur.ch - m[3].length - 1, items = [];
          if (Array.isArray(v)) {
            var el = sample(v);
            if (el && typeof el === 'object') {
              Object.keys(el).forEach(function (k) { items.push({ text: '[0].' + k, label: '[0].' + k, detail: preview(el[k]), kind: 'field', from: dot }); });
            }
          } else if (v && typeof v === 'object') {
            Object.keys(v).forEach(function (k) { items.push({ text: k, label: k, detail: preview(v[k]), kind: 'field' }); });
          }
          var q = m[3].toLowerCase();
          items = items.filter(function (it) { return it.label.replace('[0].', '').toLowerCase().indexOf(q) === 0; });
          return { from: c.cur.ch - m[3].length, to: to, line: line, items: items };
        });
      }
      var w = tail.match(/(^|[^\w$@.])([A-Za-z_]\w*)$/);
      var word = w ? w[2] : '';
      if (!word && !explicit && tail.trim()) { return Promise.resolve(null); }
      var items = Object.keys(schema.fns).filter(function (f) { return f.toLowerCase().indexOf(word.toLowerCase()) === 0; })
        .map(function (f) { return { text: f + '(', label: schema.signature(f), detail: FN_HELP[f] || '', kind: 'fn', name: f }; });
      if (!word) {
        items.unshift({ text: '@.', label: '@.', detail: 'the current row', kind: 'field', retrigger: true },
          { text: '$.', label: '$.', detail: 'the document', kind: 'field', retrigger: true });
      }
      return Promise.resolve({ from: c.cur.ch - word.length, to: to, line: line, items: items });
    }

    // ---- the popup ---------------------------------------------------------------------------------------
    function ensureBox() {
      if (hints) { return hints; }
      hints = document.createElement('div');
      hints.className = 'studio-hints';
      hints.setAttribute('role', 'listbox');
      hints.innerHTML = '<ul class="studio-hint-list"></ul><div class="studio-hint-detail"></div>';
      hints.addEventListener('mousedown', function (e) { e.preventDefault(); });
      hints.addEventListener('click', function (e) { var li = e.target.closest('li[data-i]'); if (li) { sel = +li.dataset.i; accept(); } });
      document.body.appendChild(hints);
      return hints;
    }
    function close() {
      open = null;
      if (hints) { hints.hidden = true; }
      if (keymap) { cm.removeKeyMap(keymap); keymap = null; }
    }
    function draw() {
      var box = ensureBox(), ul = box.querySelector('ul');
      ul.innerHTML = open.items.slice(0, 80).map(function (it, i) {
        return '<li data-i="' + i + '" role="option" class="sh-' + it.kind + '"' + (i === sel ? ' aria-selected="true"' : '') + '><span class="sh-label">' +
          esc(it.label) + '</span><span class="sh-detail">' + esc(it.kind === 'fn' ? '' : it.detail) + '</span></li>';
      }).join('');
      var cur = open.items[sel];
      box.querySelector('.studio-hint-detail').textContent = cur ? (cur.kind === 'fn' ? cur.label + ': ' + cur.detail : cur.detail) : '';
      box.querySelector('.studio-hint-detail').hidden = !cur || !cur.detail || cur.kind === 'field' || cur.kind === 'value';
      var on = ul.querySelector('[aria-selected]');
      if (on) { on.scrollIntoView({ block: 'nearest' }); }
    }
    function show(res, explicit) {
      if (!res || !res.items.length || (res.line !== cm.getCursor().line)) { close(); if (explicit && res) { opts.say('Nothing to complete here.'); } return; }
      if (!explicit && res.items.length === 1 && res.items[0].text.trim() === cm.getLine(res.line).slice(res.from, res.to)) { close(); return; }
      open = res; sel = Math.min(sel, res.items.length - 1);
      var box = ensureBox(), at = cm.cursorCoords({ line: res.line, ch: res.from }, 'page');
      box.hidden = false;
      box.style.left = Math.max(4, Math.min(at.left, window.innerWidth - 460)) + 'px';
      box.style.top = (at.bottom + 2) + 'px';
      draw();
      if (!keymap) {
        keymap = {
          Up: function () { sel = (sel - 1 + open.items.length) % open.items.length; draw(); },
          Down: function () { sel = (sel + 1) % open.items.length; draw(); },
          PageUp: function () { sel = Math.max(0, sel - 8); draw(); },
          PageDown: function () { sel = Math.min(open.items.length - 1, sel + 8); draw(); },
          Enter: accept, Tab: accept, Esc: close
        };
        cm.addKeyMap(keymap);
      }
    }
    function accept() {
      if (!open) { return; }
      var it = open.items[sel], from = it.from !== undefined ? it.from : open.from, line = open.line, to = open.to;
      if (it.kind === 'key') {
        var rest = cm.getLine(line).slice(to);
        if (/^\s*:/.test(rest)) { to += rest.match(/^\s*:\s?/)[0].length; }
      }
      close();
      cm.replaceRange(it.text, { line: line, ch: from }, { line: line, ch: to }, '+complete');
      if (it.kind === 'fn') { opts.say(schema.signature(it.name) + ': ' + (FN_HELP[it.name] || '')); }
      if (it.retrigger) { setTimeout(function () { trigger(false); }, 0); }
    }
    var seq = 0;
    function trigger(explicit) {
      var mine = ++seq;
      if (!open) { sel = 0; }
      gather(explicit).then(function (res) { if (mine === seq) { show(res, explicit); } }).catch(function () { close(); });
    }

    cm.addKeyMap({ 'Ctrl-Space': function () { sel = 0; trigger(true); } });
    cm.on('inputRead', function (_cm, ch) {
      var cur = cm.getCursor(), before = cm.getLine(cur.line).slice(0, cur.ch);
      if (ch.origin === '+complete' || ch.origin === 'paste') { return; }
      if (/[$@](?:\.[A-Za-z_$][\w$-]*|\[\d+\])*\.$/.test(before) || /(^\s*(-\s+)*|[{,]\s*)[\w-]+:\s$/.test(before)) { sel = 0; trigger(false); return; }
      if (open) { trigger(false); return; }
      if (/^\s*(-\s+)*[A-Za-z][\w-]?$/.test(before)) { sel = 0; trigger(false); }
    });
    cm.on('cursorActivity', function () {
      if (open) {
        var cur = cm.getCursor();
        if (cur.line !== open.line || cur.ch < open.from) { close(); } else { trigger(false); }
      }
      clearTimeout(helpTimer); helpTimer = setTimeout(help, 150);
    });
    cm.on('blur', function () { setTimeout(close, 120); });
    cm.on('change', function () { clearTimeout(checkTimer); checkTimer = setTimeout(check, 400); });
    window.addEventListener('resize', close);

    // ---- the light check -----------------------------------------------------------------------------------
    function check() {
      if (!schema) { return; }
      var ps;
      try { ps = problemsOf(schema, Y.parse(cm.getValue())); } catch (e) { ps = []; }
      cm.operation(function () {
        marks.forEach(function (m) { m.clear(); });
        marks = [];
        cm.clearGutter('studio-gutter');
        ps.forEach(function (p) {
          var l = p.location.line - 1, ch = p.location.column - 1;
          marks.push(cm.markText({ line: l, ch: ch }, { line: l, ch: ch + Math.max(1, p.len) }, { className: 'studio-chk', title: p.message }));
          var g = document.createElement('span');
          g.className = 'studio-gutter-mark';
          g.title = p.message;
          g.textContent = '●';
          cm.setGutterMarker(l, 'studio-gutter', g);
        });
      });
      opts.onCheck(ps);
    }

    // ---- field help ---------------------------------------------------------------------------------------------
    function help() {
      if (!schema || !opts.helpEl) { return; }
      var c;
      try { c = where(); } catch (e) { return; }
      var el = opts.helpEl, cur = c.cur, t = cm.getLine(cur.line), a = cur.ch, b = cur.ch;
      while (a > 0 && /[\w-]/.test(t[a - 1])) { a--; }
      while (b < t.length && /[\w-]/.test(t[b])) { b++; }
      var word = t.slice(a, b), title = '', text = '';
      if (c.mode === 'key' || (c.mode === 'value' && /^\s*:/.test(t.slice(b)))) {
        var kn = schema.child(c.mode === 'key' ? c.node : schema.child(c.node, c.key), word);
        if (kn) { title = (c.path || []).concat([word]).join('.').replace(/\.\[\]/g, '[]'); text = schema.describe(kn, word, c.kind, schema.isPanel(c.mode === 'key' ? c.node : schema.child(c.node, c.key))); }
      } else if (c.mode === 'value' && schema.fns[word] && t[b] === '(') {
        title = schema.signature(word); text = FN_HELP[word] || '';
      } else if (c.mode === 'value' && c.key) {
        var vn = schema.child(c.node, c.key);
        if (vn) { title = (c.path || []).concat([c.key]).join('.').replace(/\.\[\]/g, '[]'); text = schema.describe(vn, c.key, c.kind, schema.isPanel(c.node)); }
      }
      el.hidden = !title;
      if (title) { el.innerHTML = '<span class="mono fh-key">' + esc(title) + '</span> <span class="fh-text">' + esc(text || 'No description.') + '</span>'; }
    }

    return { complete: function () { sel = 0; trigger(true); }, check: check, schema: function () { return schema; } };
  }

  window.drishtiAssist = { attach: attach, Schema: Schema, check: problemsOf };
})();
