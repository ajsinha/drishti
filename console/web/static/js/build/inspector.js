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
/* The inspector: a form for whatever is selected, made from the Rachana schema (GET /studio/schema, the same document the YAML
 * editor completes from). A panel: its required options first, then the rest of what its kind accepts; enums are selects,
 * booleans toggles, numbers number fields, expressions text fields with autocomplete of shape paths and functions, and the lists
 * (columns, fields, series, the strip) add, remove and reorder. Nothing selected: the screen's own match and function keys. The title
 * and the strip, when selected on the canvas, have their own editors. Each change is one operation (SetOption, SetTitle, SetStrip,
 * SetKeys, SetMatch); the form is not redrawn under your hands while you type.
 *
 *   new WB.Inspector(box, store, getSchema, ctx) -> {show(sel, opts)}      ctx: {paths(): shape rows, actions}                   */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var LISTS = {                                  // the items of a list option: [key, label, kind]; kind x = expression
    columns: [['label', 'Label'], ['bind', 'Value', 'x'], ['fmt', 'Format', 'fmt'], ['tone', 'Tone', 'tone'], ['total', 'Total', 'b'], ['link', 'Link', 'b']],
    fields: [['label', 'Label'], ['bind', 'Value', 'x'], ['fmt', 'Format', 'fmt'], ['tone', 'Tone', 'tone']],
    series: [['label', 'Label'], ['value', 'Value', 'x'], ['tone', 'Tone', 'tone']],
    strip: [['label', 'Label'], ['bind', 'Value', 'x'], ['fmt', 'Format', 'fmt'], ['tone', 'Tone', 'tone'], ['emphasis', 'Emphasis', 'b']]
  };
  var BASE = ['title', 'description', 'area', 'span', 'height', 'key', 'code'];
  var uid = 0;

  WB.Inspector = function (box, store, getSchema, ctx) {
    var cur = null, schema = null, mine = false, timers = {}, enums = {};
    function el(t, c, x, a) { return WB.el(t, c, x, a); }
    function label(text, input, extra) {
      var id = 'wbi' + (++uid), w = el('div', 'wb-field' + (extra ? ' ' + extra : ''));
      var l = el('label', null, text, { 'for': id });
      input.id = id; w.appendChild(l); w.appendChild(input);
      return w;
    }
    function commit(op, now) {
      var fire = function () { mine = true; return store.send([op], 'Changed').then(function (r) { mine = false; return r; }); };
      var key = op.op + ':' + (op.panel || '') + ':' + (op.option || '');
      clearTimeout(timers[key]);
      if (now) { fire(); } else { timers[key] = setTimeout(fire, 450); }
    }
    var prop = function (n) { return ((schema.raw.$defs.panel.properties || {})[n]) || {}; };
    function enumOf(p) { return p.enum || (p.oneOf && []) || null; }

    // ---- one value --------------------------------------------------------------------------------------------------------
    function textInput(value, onChange, expr, src) {
      var i = el('input', 'studio-in mono', null, { type: 'text', autocomplete: 'off', spellcheck: 'false' });
      i.value = value == null ? '' : String(value);
      i.addEventListener('input', function () { onChange(i.value, false); });
      i.addEventListener('change', function () { onChange(i.value, true); });
      return { input: i, mount: function (w) { if (expr) { WB.expr.attach(i, src); } return w; } };
    }
    function select(options, value, onChange) {
      var s = el('select', 'studio-in');
      if (value && options.indexOf(String(value)) < 0) { options = options.concat([String(value)]); }          // a value the list does not know (a kind from outside the packs) is still shown
      [''].concat(options).forEach(function (o) { var op = el('option', null, o || '(none)', { value: o }); if (String(value == null ? '' : value) === o) { op.selected = true; } s.appendChild(op); });
      s.addEventListener('change', function () { onChange(s.value, true); });
      return s;
    }
    function exprSource(panel) {
      return { paths: function () { return ctx.paths(); }, rows: function () { var m = WB.model(store.state.yaml).panels.filter(function (p) { return p.id === panel; })[0]; return m && typeof m.values.rows === 'string' ? m.values.rows : ''; },
               functions: schema.fns };
    }
    /** The control for one option of a panel. */
    function control(panelId, name, value, p, req) {
      var set = function (v, now) { commit({ op: 'setOption', panel: panelId, option: name, value: v }, now); };
      var desc = p.description || '';
      var isExpr = /Rachana-EL/.test(desc), node;
      if (p.enum) { node = select(p.enum, value, function (v, n) { set(v === '' ? null : v, n); }); return label(name, node); }
      if (p.type === 'boolean' || (Array.isArray(p.type) && p.type.indexOf('boolean') >= 0 && typeof value !== 'object')) {
        var cb = el('input', null, null, { type: 'checkbox', role: 'switch' });
        cb.checked = value === true;
        cb.addEventListener('change', function () { set(cb.checked ? true : null, true); });
        var w = label(name, cb, 'wb-toggle'); return w;
      }
      if (value && typeof value === 'object' && !LISTS[name]) {
        var ro = el('div', 'wb-field'); ro.appendChild(el('label', null, name));
        ro.appendChild(el('p', 'text-muted-d', 'Set in the YAML tab (' + (Array.isArray(value) ? 'a list' : 'several settings') + ').'));
        return ro;
      }
      if (p.type === 'integer') {
        var n = el('input', 'studio-in', null, { type: 'number', min: p.minimum != null ? p.minimum : 1, max: p.maximum != null ? p.maximum : '' });
        n.value = value == null ? '' : value;
        n.addEventListener('change', function () { set(n.value === '' ? null : parseInt(n.value, 10), true); });
        return label(name, n);
      }
      if (LISTS[name] && (p.type === 'array' || name === 'series' || name === 'fields')) { return listEditor(name, value, function (v) { set(v.length ? v : null, true); }, panelId); }
      var t = textInput(value, set, isExpr || /^(rows|source|children|each|x|y|label|value|mark|max|text)$/.test(name), exprSource(panelId));
      var wrap = label(name + (req ? ' *' : ''), t.input);
      t.mount(wrap);
      if (desc) { t.input.setAttribute('aria-description', desc); t.input.title = desc; }
      if (req) { wrap.classList.add('wb-req'); wrap.setAttribute('data-required', name); }
      return wrap;
    }

    // ---- lists: columns, fields, series, the strip ------------------------------------------------------------------------
    function listEditor(name, value, onChange, panelId) {
      var items = Array.isArray(value) ? value.map(function (x) { return Object.assign({}, x); }) : [], keys = LISTS[name];
      var wrap = el('fieldset', 'wb-list'), body = el('div'), src = exprSource(panelId);
      wrap.appendChild(el('legend', null, name));
      wrap.appendChild(body);
      function clean() {
        return items.map(function (it) { var o = {}; keys.forEach(function (k) { var v = it[k[0]]; if (v !== '' && v != null && v !== false) { o[k[0]] = v; } }); return o; }).filter(function (o) { return Object.keys(o).length; });
      }
      function paint(focusAt) {
        body.textContent = '';
        items.forEach(function (it, idx) {
          var row = el('div', 'wb-item', null, { role: 'group', 'aria-label': name + ' ' + (idx + 1) });
          keys.forEach(function (k) {
            var cell;
            if (k[2] === 'b') {
              cell = el('input', null, null, { type: 'checkbox' }); cell.checked = it[k[0]] === true;
              cell.addEventListener('change', function () { it[k[0]] = cell.checked; onChange(clean()); });
            } else if (k[2] === 'fmt' || k[2] === 'tone') {
              cell = select(enums[k[2]] || [], it[k[0]], function (v) { it[k[0]] = v; onChange(clean()); });
            } else {
              cell = el('input', 'studio-in mono', null, { type: 'text', autocomplete: 'off', spellcheck: 'false' });
              cell.value = it[k[0]] == null ? '' : it[k[0]];
              cell.addEventListener('change', function () { it[k[0]] = cell.value; onChange(clean()); });
            }
            var w = label(k[1] + ' ' + (idx + 1), cell, 'wb-cell');
            row.appendChild(w);
            if (k[2] === 'x') { WB.expr.attach(cell, src); }
          });
          var tools = el('div', 'wb-item-tools');
          [['↑', 'Move up', -1], ['↓', 'Move down', 1]].forEach(function (b) {
            var btn = el('button', 'btn-pill btn-ghost', b[0], { type: 'button', 'aria-label': b[1] + ': ' + name + ' ' + (idx + 1) });
            btn.disabled = idx + b[2] < 0 || idx + b[2] >= items.length;
            btn.addEventListener('click', function () { items.splice(idx + b[2], 0, items.splice(idx, 1)[0]); onChange(clean()); paint(idx + b[2]); });
            tools.appendChild(btn);
          });
          var rm = el('button', 'btn-pill btn-ghost', 'Remove', { type: 'button', 'aria-label': 'Remove ' + name + ' ' + (idx + 1) });
          rm.addEventListener('click', function () { items.splice(idx, 1); onChange(clean()); paint(Math.max(0, idx - 1)); });
          tools.appendChild(rm);
          row.appendChild(tools);
          body.appendChild(row);
        });
        if (focusAt != null) { var f = body.children[focusAt] && body.children[focusAt].querySelector('input, select'); if (f) { f.focus(); } }
      }
      var add = el('button', 'btn-pill btn-ghost', 'Add ' + name.replace(/s$/, ''), { type: 'button' });
      add.addEventListener('click', function () { items.push({}); paint(items.length - 1); });
      wrap.appendChild(add);
      paint();
      return wrap;
    }

    // ---- what is selected -----------------------------------------------------------------------------------------------------
    function panelForm(sel, opts) {
      var m = WB.model(store.state.yaml).panels.filter(function (p) { return p.id === sel.id; })[0];
      if (!m) { box.appendChild(el('p', 'text-muted-d', 'This panel is gone.')); return; }
      var kind = m.kind, k = schema.byKind[kind] || { required: [], options: [] };
      var head = el('div', 'wb-ins-h');
      head.appendChild(el('span', 'bs-role', kind));
      head.appendChild(el('b', 'mono', m.id));
      var help = el('a', 'lnk', 'About ' + kind, { href: '/help/panel-kinds#' + kind, target: '_blank', rel: 'noopener' });
      head.appendChild(help);
      box.appendChild(head);
      var bar = el('div', 'bs-actions');
      var bind = el('button', 'btn-pill btn-ghost', 'Bind field…', { type: 'button' });
      bind.addEventListener('click', function () { ctx.actions.menuBind(bind); });
      var rm = el('button', 'btn-pill btn-ghost', 'Remove panel', { type: 'button' });
      rm.addEventListener('click', function () { ctx.actions.remove(m.id, null); });
      bar.appendChild(bind); bar.appendChild(rm); box.appendChild(bar);
      var missing = [];
      var sect = function (title, names, req) {
        var list = names.filter(function (n) { return n !== 'id' && n !== 'kind'; });
        if (!list.length) { return; }
        var s = el('fieldset', 'wb-sect'); s.appendChild(el('legend', null, title));
        list.forEach(function (n) {
          var has = m.values[n] !== undefined && m.values[n] !== null && m.values[n] !== '' && !(Array.isArray(m.values[n]) && !m.values[n].length);
          var c = control(m.id, n, m.values[n], prop(n), req);
          if (req && (!has || (opts && opts.required))) { c.classList.add('wb-missing'); missing.push(c); }
          s.appendChild(c);
        });
        box.appendChild(s);
      };
      sect('Required', k.required.filter(function (n) { return n !== 'id' && n !== 'kind'; }), true);
      var optional = k.options.filter(function (n) { return k.required.indexOf(n) < 0; });
      sect('Options of a ' + kind + ' panel', optional.filter(function (n) { return n !== 'columns' || true; }), false);
      sect('Placement and heading', BASE, false);
      if (opts && opts.required && missing.length) {
        var f = missing[0].querySelector('input, select, button');
        box.insertBefore(el('p', 'wb-hint', 'Required options are marked (filled from your data when it could; check them): ' + missing.map(function (x) { return x.getAttribute('data-required') || x.querySelector('legend, label').textContent; }).join(', ') + '.', { role: 'status' }), box.children[2]);
        if (f) { f.focus(); }
      }
    }
    function section(name, val, build) {
      var s = el('fieldset', 'wb-sect'); s.appendChild(el('legend', null, name)); build(s, val); box.appendChild(s);
    }
    function titleForm() {
      var t = WB.model(store.state.yaml).title || {}, cur2 = Object.assign({}, t);
      section('Title', t, function (s) {
        [['id', 'Id (expression)', true], ['pill', 'Pill (text, may hold ${…})', false], ['with', 'With (expression)', true]].forEach(function (f) {
          var tin = textInput(cur2[f[0]], function (v, now) { if (v) { cur2[f[0]] = v; } else { delete cur2[f[0]]; } if (cur2.id) { commit({ op: 'setTitle', title: Object.assign({}, cur2) }, now); } }, f[2], exprSource(''));
          var w = label(f[1], tin.input); tin.mount(w); s.appendChild(w);
        });
      });
    }
    function stripForm() {
      var items = WB.model(store.state.yaml).strip || [];
      var le = listEditor('strip', items, function (v) { commit({ op: 'setStrip', items: v }, true); }, '');
      box.appendChild(le);
      box.appendChild(el('p', 'text-muted-d', 'Up to eight figures. Drag a field from the shape onto the strip to add one.'));
    }
    function screenForm() {
      var md = WB.model(store.state.yaml), match = Object.assign({}, md.match), keys = Object.assign({}, md.keys);
      var cm = (schema.raw.properties.match || {}).properties || {};
      section('Applies to (match)', match, function (s) {
        var kinds = (cm.kind && cm.kind.enum) || [];
        var send = function () { commit({ op: 'setMatch', match: Object.assign({}, match) }, true); };
        s.appendChild(label('kind', select(kinds, match.kind, function (v) { if (v) { match.kind = v; send(); } })));
        var w = textInput(match.where, function (v, now) { if (v) { match.where = v; } else { delete match.where; } if (match.kind && now) { send(); } }, true, exprSource(''));
        var lw = label('where (expression)', w.input); w.mount(lw); s.appendChild(lw);
        var pr = el('input', 'studio-in', null, { type: 'number', min: 0 }); pr.value = match.priority == null ? '' : match.priority;
        pr.addEventListener('change', function () { if (pr.value === '') { delete match.priority; } else { match.priority = parseInt(pr.value, 10); } if (match.kind) { send(); } });
        s.appendChild(label('priority', pr));
      });
      section('Function keys', keys, function (s) {
        for (var n = 1; n <= 12; n++) {
          (function (key) {
            var t = textInput(keys[key], function (v, now) { if (v) { keys[key] = v; } else { delete keys[key]; } commit({ op: 'setKeys', keys: Object.assign({}, keys) }, now); }, true, exprSource(''));
            var w = label(key, t.input, 'wb-cell'); t.mount(w); s.appendChild(w);
          })('F' + n);
        }
      });
      box.appendChild(el('p', 'text-muted-d', 'Select a panel, the title or the strip on the canvas to edit it here.'));
    }

    function show(sel, opts) {
      cur = sel;
      box.textContent = '';
      if (!schema) { box.appendChild(el('p', 'text-muted-d', 'Loading the Rachana schema...')); return; }
      var labelText = !sel ? 'the screen' : sel.type === 'panel' ? 'panel ' + sel.id : sel.type;
      box.setAttribute('aria-label', 'Inspector: ' + labelText);
      if (!sel) { screenForm(); } else if (sel.type === 'panel') { panelForm(sel, opts); } else if (sel.type === 'title') { titleForm(); } else if (sel.type === 'strip') { stripForm(); }
    }
    getSchema().then(function (raw) {
      schema = new window.drishtiAssist.Schema(raw);
      var col = raw.$defs.column.properties;
      enums.fmt = (col.fmt || {}).enum || []; enums.tone = (col.tone || {}).enum || [];
      show(cur);
    });
    // the Sutra changed somewhere else (the YAML tab, undo, another panel): redraw unless the form is what changed it
    store.on('doc', function (d) { if (!mine && d.changed && cur !== undefined) { if (!box.contains(document.activeElement) || d.source !== 'ops') { show(cur); } } });
    return { required: function (kind) { return schema && schema.byKind[kind] ? schema.byKind[kind].required : []; }, show: show, current: function () { return cur; }, focusFirst: function () { var f = box.querySelector('input, select, button'); if (f) { f.focus(); } } };
  };
})();
