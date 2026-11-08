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
/* The inspector: drishti-designer's Inspector (one live drishti-inputs Form) for whatever is selected, its schema made from the Rachana
 * schema (GET /studio/schema, the same document the YAML editor completes from). A panel: its required options first, then the rest of
 * what its kind accepts, then its placement; enums are selects, booleans switches, numbers number fields, expressions the Form's formula
 * widget (a one-line code editor completing shape paths and functions: WB.expr), the structured options WB.Forms' fields, and the lists
 * (columns, fields, series, the strip) repeatable groups that add, remove and move. Nothing selected: the screen's own match and function
 * keys. The title and the strip, when selected on the canvas, have their own forms. Each change is one operation (SetOption, SetTitle,
 * SetStrip, SetKeys, SetMatch), typed text after a pause; the form is not drawn again under your hands while you type.
 *
 *   new WB.Inspector(box, store, getSchema, ctx) -> {show(sel, opts), flush(), pending(), required(kind), current(), focusFirst()}
 *   ctx: {paths(): shape rows, actions, remove(id)}                                                                                      */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  var LISTS = {                                  // the items of a list option: [key, label, kind]; kind x = expression
    columns: [['label', 'Label'], ['bind', 'Value', 'x'], ['fmt', 'Format', 'fmt'], ['tone', 'Tone', 'tone'], ['total', 'Total', 'b'], ['link', 'Link', 'b']],
    fields: [['label', 'Label'], ['bind', 'Value', 'x'], ['fmt', 'Format', 'fmt'], ['tone', 'Tone', 'tone']],
    series: [['label', 'Label'], ['value', 'Value', 'x'], ['tone', 'Tone', 'tone']],
    markers: [['label', 'Label'], ['value', 'Value', 'x'], ['tone', 'Tone', 'tone']],
    strip: [['label', 'Label'], ['bind', 'Value', 'x'], ['fmt', 'Format', 'fmt'], ['tone', 'Tone', 'tone'], ['emphasis', 'Emphasis', 'b']]
  };
  var BASE = ['title', 'description', 'area', 'span', 'height', 'key', 'code'];
  var EXPR = /^(rows|source|children|each|x|y|label|value|mark|max|text)$/;

  WB.Inspector = function (box, store, getSchema, ctx) {
    var D = window.Drishti.elements.designer, cur = null, schema = null, mine = false, timers = {}, waiting = {}, enums = {};
    var built = null;                            // the selection as a form: {schema, values, change(path, values)}, a panel's {kind, model, required}
    function el(t, c, x, a) { return WB.el(t, c, x, a); }
    function keyOf(op) { return op.op + ':' + (op.panel || '') + ':' + (op.option || ''); }
    function cancel(op) { var key = keyOf(op); clearTimeout(timers[key]); delete waiting[key]; }
    function commit(op, now) {
      var fire = function () { mine = true; return store.send([op], 'Changed').then(function (r) { mine = false; return r; }); };
      var key = keyOf(op);
      cancel(op);
      if (now) { fire(); } else { waiting[key] = fire; timers[key] = setTimeout(function () { delete waiting[key]; fire(); }, 450); }
    }
    var prop = function (n) { return ((schema.raw.$defs.panel.properties || {})[n]) || {}; };
    /** The option as THIS kind takes it (the schema's per-kind properties), else the shared definition. */
    function kindProp(kind, n) {
      var k = schema.byKind[kind], own = k && k.props && k.props[n];
      own = own ? (schema.resolve(own) || own) : null;
      return own && (own.type || own['enum'] || own.oneOf) ? own : Object.assign({}, prop(n), own || {});
    }
    function exprSource(panel) {
      return { paths: function () { return ctx.paths(); }, rows: function () { var m = WB.model(store.state.yaml).panels.filter(function (p) { return p.id === panel; })[0]; return m && typeof m.values.rows === 'string' ? m.values.rows : ''; },
               functions: schema.fns };
    }
    function formula(title, panel, extra) { return Object.assign({ type: 'string', title: title, widget: 'formula', options: { complete: WB.expr.complete(exprSource(panel)) } }, extra || {}); }
    function plain(f) { return { field: f, into: function (x) { return x == null || x === '' ? undefined : String(x); }, out: function (x) { return x === undefined || x === '' ? null : x; } }; }

    // ---- one option as a field: {field, into(value), out(formValue)} ---------------------------------------------------------------
    function listField(name, panelId) {
      var keys = LISTS[name], props = {};
      keys.forEach(function (k) {
        props[k[0]] = k[2] === 'x' ? formula(k[1], panelId) : k[2] === 'b' ? { type: 'boolean', title: k[1] }
          : k[2] ? { type: 'string', title: k[1], 'enum': enums[k[2]] || [], widget: 'select', emptyLabel: '(none)' } : { type: 'string', title: k[1], lazy: true };
      });
      var clean = function (items) {
        return (items || []).map(function (it) { var o = {}; keys.forEach(function (k) { var v = (it || {})[k[0]]; if (v !== '' && v != null && v !== false) { o[k[0]] = v; } }); return o; })
          .filter(function (o) { return Object.keys(o).length; });
      };
      return { field: { type: 'array', title: name, addLabel: 'Add ' + name.replace(/s$/, ''), items: { type: 'object', title: name, properties: props } },
        into: function (v) { return (Array.isArray(v) ? v : []).map(function (x) { return Object.assign({}, x); }); },
        out: function (v) { var c = clean(v); return c.length ? c : null; } };
    }
    /** A tabs panel's `body`: the panel each tab shows, by its kind and its columns or fields (the rest is kept as it is). */
    function bodyField(value, panelId) {
      var kinds = Object.keys(schema.byKind), body = value && typeof value === 'object' ? value : {};
      var having = function (o) { return kinds.filter(function (k) { return schema.byKind[k].options.indexOf(o) >= 0; }); };
      var withCols = having('columns'), withFields = having('fields').filter(function (k) { return withCols.indexOf(k) < 0; });
      var cols = listField('columns', panelId), flds = listField('fields', panelId);
      var rest = Object.keys(body).filter(function (x) { return x !== 'kind' && x !== 'columns' && x !== 'fields'; });
      return { field: { type: 'object', title: 'body', description: rest.length ? 'Also set in the YAML tab: ' + rest.join(', ') + '.' : undefined, properties: {
          kind: { type: 'string', title: 'kind of the tab body', 'enum': kinds, widget: 'select', emptyLabel: '(none)' },
          columns: Object.assign({}, cols.field, { when: { field: 'kind', 'in': withCols } }),
          fields: Object.assign({}, flds.field, { when: { field: 'kind', 'in': withFields } }) } },
        into: function (v) { v = v && typeof v === 'object' ? v : {}; return { kind: v.kind, columns: cols.into(v.columns), fields: flds.into(v.fields) }; },
        out: function (v) {
          v = v || {};
          if (!v.kind) { return null; }
          var o = {}, list = withCols.indexOf(v.kind) >= 0 ? 'columns' : withFields.indexOf(v.kind) >= 0 ? 'fields' : '';
          rest.forEach(function (k) { o[k] = body[k]; });
          o.kind = v.kind;
          var c = list === 'columns' ? cols.out(v.columns) : list === 'fields' ? flds.out(v.fields) : null;
          if (c) { o[list] = c; }
          return o;
        } };
    }
    function optionField(panelId, name, value, p, kind) {
      var desc = p.description || '', tip = desc ? { 'aria-description': desc, title: desc } : null;
      if (p['enum'] || p.type === 'boolean') { return WB.Forms.toField(p, value, name, enums); }
      if (LISTS[name] && (p.type === 'array' || name === 'series' || name === 'fields')) { return listField(name, panelId); }
      if (name === 'body' && kind === 'tabs') { return bodyField(value, panelId); }
      var structured = p.type === 'integer' || p.type === 'number' || p.oneOf || p.type === 'array' || (Array.isArray(p.type) && p.type.indexOf('object') >= 0) || (value && typeof value === 'object');
      if (structured) { return WB.Forms.toField(p.type || p.oneOf ? p : {}, value, name, enums); }
      if (/Rachana-EL/.test(desc) || EXPR.test(name)) { return plain(formula(name, panelId, { hook: tip })); }
      return plain({ type: 'string', title: name, hook: tip });
    }

    // ---- what is selected, as a form ---------------------------------------------------------------------------------------------
    function panelForm(sel) {
      var m = WB.model(store.state.yaml).panels.filter(function (p) { return p.id === sel.id; })[0];
      if (!m) { return null; }
      var kind = m.kind, k = schema.byKind[kind] || { required: [], options: [] }, conv = {}, at = {}, props = {}, values = {};
      var optional = k.options.filter(function (n) { return k.required.indexOf(n) < 0; });
      if (kind === 'tabs' && optional.indexOf('body') < 0) { optional.push('body'); }          // what each tab shows: a panel of its own, not listed among the options
      [['req', 'Required', k.required], ['opt', 'Options of a ' + kind + ' panel', optional], ['base', 'Placement and heading', BASE]].forEach(function (s) {
        var names = s[2].filter(function (n) { return n !== 'id' && n !== 'kind' && !at[n]; });
        if (!names.length) { return; }
        var sp = {}, sv = {};
        names.forEach(function (n) {
          var c = conv[n] = optionField(m.id, n, m.values[n], kindProp(kind, n), kind);
          at[n] = s[0];
          sp[n] = Object.assign({ title: n }, c.field);
          var x = c.into(m.values[n]);
          if (x !== undefined) { sv[n] = x; }
        });
        props[s[0]] = { type: 'object', title: s[1], properties: sp, required: s[0] === 'req' ? names : undefined };
        values[s[0]] = sv;
      });
      return { kind: kind, model: m, required: k.required.filter(function (n) { return n !== 'id' && n !== 'kind'; }), schema: { type: 'object', title: kind, properties: props }, values: values,
        change: function (path, all) {
          var n = path.split('.')[1].split('[')[0], c = conv[n];
          if (!c) { return; }
          var out = c.out(((all || {})[at[n]] || {})[n]);
          if (out === WB.Forms.SKIP) { return; }
          var op = { op: 'setOption', panel: m.id, option: n, value: out === undefined ? null : out };
          var now = WB.model(store.state.yaml).panels.filter(function (p) { return p.id === m.id; })[0], was = now ? now.values[n] : undefined;
          if (JSON.stringify(was === undefined ? null : was) === JSON.stringify(op.value)) { cancel(op); return; }   // an empty item added, a text typed back: nothing to send
          commit(op, typeof out !== 'string');
        } };
    }
    function titleForm() {
      var t = WB.model(store.state.yaml).title || {};
      return { schema: { type: 'object', properties: { title: { type: 'object', title: 'Title', properties: {
          id: formula('Id (expression)', ''), pill: { type: 'string', title: 'Pill (text, may hold ${…})' }, 'with': formula('With (expression)', '') } } } },
        values: { title: { id: t.id, pill: t.pill, 'with': t['with'] } },
        change: function (path, all) {
          var v = Object.assign({}, t), f = (all || {}).title || {};
          ['id', 'pill', 'with'].forEach(function (k) { if (f[k]) { v[k] = f[k]; } else { delete v[k]; } });
          if (v.id) { commit({ op: 'setTitle', title: v }, false); }
        } };
    }
    function stripForm() {
      var c = listField('strip', '');
      return { schema: { type: 'object', description: 'Up to eight figures. Drag a field from the shape onto the strip to add one.', properties: { strip: c.field } },
        values: { strip: c.into(WB.model(store.state.yaml).strip || []) },
        change: function (path, all) { commit({ op: 'setStrip', items: c.out((all || {}).strip) || [] }, !/\.(label|bind)$/.test(path)); } };
    }
    function screenForm() {
      var md = WB.model(store.state.yaml), match = md.match || {}, keys = md.keys || {}, cm = (schema.raw.properties.match || {}).properties || {}, fk = {}, kv = {};
      for (var n = 1; n <= 12; n++) { fk['F' + n] = formula('F' + n, ''); if (keys['F' + n]) { kv['F' + n] = keys['F' + n]; } }
      return { schema: { type: 'object', description: 'Select a panel, the title or the strip on the canvas to edit it here.', properties: {
          match: { type: 'object', title: 'Applies to (match)', properties: { kind: { type: 'string', title: 'kind', 'enum': (cm.kind && cm.kind['enum']) || [], widget: 'select', emptyLabel: '(none)' },
            where: formula('where (expression)', ''), priority: { type: 'integer', title: 'priority', minimum: 0, lazy: true } } },
          keys: { type: 'object', title: 'Function keys', properties: fk } } },
        values: { match: { kind: match.kind, where: match.where, priority: match.priority }, keys: kv },
        change: function (path, all) {
          all = all || {};
          if (path.indexOf('keys.') === 0) {
            var k = Object.assign({}, keys), f = all.keys || {};
            Object.keys(fk).forEach(function (x) { if (f[x]) { k[x] = f[x]; } else { delete k[x]; } });
            commit({ op: 'setKeys', keys: k }, false);
            return;
          }
          var mt = Object.assign({}, match), g = all.match || {};
          ['kind', 'where', 'priority'].forEach(function (x) { var v = g[x]; if (v !== undefined && v !== '' && !(typeof v === 'number' && isNaN(v))) { mt[x] = v; } else { delete mt[x]; } });
          if (mt.kind) { commit({ op: 'setMatch', match: mt }, path !== 'match.where'); }
        } };
    }

    // ---- the kit's inspector ------------------------------------------------------------------------------------------------------
    function header() {
      if (!built || !built.model) { return null; }
      var m = built.model, kind = built.kind;
      var rm = el('button', 'btn-pill btn-ghost wb-remove', 'Remove panel', { type: 'button', 'aria-label': 'Remove panel ' + (m.values.title || m.id) });
      rm.addEventListener('click', function () { if (ctx.remove) { ctx.remove(m.id); } else { ctx.actions.remove(m.id, null); } });
      var bind = el('button', 'btn-pill btn-ghost', 'Bind field…', { type: 'button' });
      bind.addEventListener('click', function () { ctx.actions.menuBind(bind); });
      return [el('span', 'bs-role', kind), el('b', 'mono', m.id), el('a', 'lnk', 'About ' + kind, { href: '/help/rachana-reference#' + kind, target: '_blank', rel: 'noopener' }), rm, bind];
    }
    var kit = new D.Inspector(box, { empty: 'Loading the Rachana schema...', head: header,
      schemaFor: function () { return built ? built.schema : null; }, valuesFor: function () { return built ? JSON.parse(JSON.stringify(built.values)) : {}; } });
    kit.on('change', function (c) { if (built) { built.change(c.path, c.values); } });

    /** Marks the required options (the empty ones; all of them when a panel was just added: filled from the data, to be checked); the first takes the focus. */
    function markRequired(opts) {
      if (!built || !built.required) { return; }
      var marked = [], fresh = !!(opts && opts.required);
      built.required.forEach(function (n) {
        var w = box.querySelector('[data-path="req.' + n + '"]'), v = built.model.values[n];
        if (!w) { return; }
        w.setAttribute('data-required', n);
        if (fresh || v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length)) { w.classList.add('wb-missing'); marked.push(n); }
      });
      if (fresh && marked.length) {
        kit.body.insertBefore(el('p', 'wb-hint', 'Required options are marked (filled from your data when it could; check them): ' + marked.join(', ') + '.', { role: 'status' }), kit.body.firstChild);
        var at = '[data-path="req.' + marked[0] + '"] ', f = box.querySelector(at + 'input, ' + at + 'select, ' + at + 'button');
        if (f) { f.focus(); }
      }
    }
    function show(sel, opts) {
      cur = sel;
      if (!schema) { built = null; kit.show([]); return; }
      box.setAttribute('aria-label', 'Inspector: ' + (!sel ? 'the screen' : sel.type === 'panel' ? 'panel ' + sel.id : sel.type));
      built = !sel ? screenForm() : sel.type === 'panel' ? panelForm(sel) : sel.type === 'title' ? titleForm() : sel.type === 'strip' ? stripForm() : null;
      kit.show([!sel ? 'screen' : sel.type === 'panel' ? 'panel:' + sel.id : sel.type]);
      markRequired(opts);
    }
    getSchema().then(function (raw) {
      schema = new D.YamlSchema(raw);                                  // the Rachana schema as drishti-designer reads it
      var col = raw.$defs.column.properties;
      enums.fmt = (col.fmt || {})['enum'] || []; enums.tone = (col.tone || {})['enum'] || [];
      show(cur);
    });
    // the Sutra changed somewhere else (the YAML tab, undo, another panel): redraw unless the form is what changed it
    store.on('doc', function (d) { if (!mine && d.changed && cur !== undefined) { if (!box.contains(document.activeElement) || d.source !== 'ops') { show(cur); } } });
    /** Sends the edits still waiting for their pause (before the page is left: F1, a link). */
    function flush() {
      var all = Object.keys(waiting).map(function (k) { clearTimeout(timers[k]); var f = waiting[k]; delete waiting[k]; return f(); });
      return Promise.all(all);
    }
    var FOCUS = '.dd-inspector-body input:not([type=hidden]), .dd-inspector-body select, .dd-inspector-body textarea, .dd-inspector-body button';
    return { flush: flush, pending: function () { return Object.keys(waiting).length > 0; }, required: function (kind) { return schema && schema.byKind[kind] ? schema.byKind[kind].required : []; },
      show: show, current: function () { return cur; }, focusFirst: function () { var f = box.querySelector(FOCUS) || box.querySelector('button'); if (f) { f.focus(); } } };
  };
})();
