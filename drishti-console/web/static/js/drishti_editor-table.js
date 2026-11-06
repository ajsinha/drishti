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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis);
/* The table editor (docs/architecture/EDITORS.md section 2, option A, with option C as its Text toggle): tables and virtual tables. The
   source and its look-ups as rows (a look-up is a join to another table on a key: Rachana's related(), one many-to-one hop, read on the
   server; across connectors it shows its cost and is refused above drishti.tables.scan-warn-rows until the reader filters); the columns
   as a checklist (the name here, where it comes from, its type, the key) with computed columns as Rachana formulas; the filter, the grain
   and the materialisation; business dates follow the source. The Text toggle shows the same table as one Rachana statement. */
(function () {
  'use strict';
  var E = window.Drishti.editors;
  var TYPES = ['string', 'integer', 'decimal', 'boolean', 'date', 'timestamp'];
  var text = false;     // the Text toggle, for this page

  function kindOf(ctx, doc) {
    var k = doc.from && doc.from.kind;
    return k ? (ctx.sources().kinds || []).filter(function (x) { return x.kind === k; })[0] : null;
  }
  function relatedOf(expr) {
    var m = /related\(\s*([a-z][a-z0-9_]*\.[a-z][a-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*,\s*on\s*=\s*([^)]+)\)/.exec(expr || '');
    return m ? { table: m[1], column: m[2], on: m[3].trim() } : null;
  }
  function lookups(doc) {
    var out = {};
    Object.keys(doc.columns || {}).forEach(function (c) {
      var r = relatedOf(doc.columns[c].expr);
      if (r) { var k = r.table + '|' + r.on; (out[k] = out[k] || { table: r.table, on: r.on, columns: [] }).columns.push(c); }
    });
    return Object.keys(out).map(function (k) { return out[k]; });
  }
  function typeOf(t) {
    var s = String(t || '').toLowerCase();
    if (/int|long/.test(s)) { return 'integer'; }
    if (/dec|num|double|float|money|amount/.test(s)) { return 'decimal'; }
    if (/bool/.test(s)) { return 'boolean'; }
    if (/timestamp|datetime/.test(s)) { return 'timestamp'; }
    if (/date/.test(s)) { return 'date'; }
    return 'string';
  }

  function render(ctx, host) {
    var h = ctx.h, S = ctx.S, doc = S.doc || {};
    host.setAttribute('data-editor', 'table');
    var bar = h('div', { class: 'ed-bar' },
      h('div', { class: 'ed-modes', role: 'group', 'aria-label': 'Grid or text' },
        h('button', { type: 'button', class: 'ed-mode', 'data-table-view': 'grid', 'data-always': '', 'aria-pressed': text ? 'false' : 'true', on: { click: function () { text = false; ctx.redraw(); } } }, 'Visual'),
        h('button', { type: 'button', class: 'ed-mode', 'data-table-view': 'text', 'data-always': '', 'aria-pressed': text ? 'true' : 'false',
          title: S.statementRefused || 'The same table as one Rachana statement', disabled: !!S.statementRefused, on: { click: function () { text = true; ctx.redraw(); } } }, 'Text')));
    host.appendChild(bar);
    if (text && !S.statementRefused) { return statement(ctx, host); }
    var about = h('div', { class: 'ed-row2' },
      field(ctx, 'Name', doc.table, 'table', function (d, v) { d.table = v; }),
      field(ctx, 'Title', doc.title, 'title', function (d, v) { if (v) { d.title = v; } else { delete d.title; } }));
    host.appendChild(about);
    host.appendChild(sources(ctx, doc));
    host.appendChild(h('p', { class: 'ed-cost', 'data-cost-line': '', hidden: true }));
    host.appendChild(columns(ctx, doc));
    host.appendChild(more(ctx, doc));
    refresh(ctx, host);
  }

  function field(ctx, label, value, path, set, formula) {
    var h = ctx.h;
    var input = h('input', { class: 'ed-in', value: value || '', 'data-path': path, 'aria-label': label,
      on: { input: function (e) { var v = e.target.value; ctx.change(function (d) { set(d, v.trim()); }, path); }, focus: function () { ctx.focus(path); } } });
    if (formula) { E.formula(input, ctx.names); }
    return h('label', { class: 'ed-field' }, h('span', { text: label }), input);
  }

  function sources(ctx, doc) {
    var h = ctx.h;
    var sec = h('section', { class: 'ed-card', 'aria-labelledby': 'edSrcH' }, h('h3', { id: 'edSrcH', text: 'Source and look-ups' }));
    var t = h('table', { class: 'ed-grid-t', 'data-sources': '', 'data-resize': 'columns', 'data-resize-key': 'editors.table-sources' });
    t.appendChild(h('thead', {}, h('tr', {}, ['Source', 'Connector', 'Look-up', 'On', 'Rows (now)', ''].map(function (x) { return h('th', { scope: 'col', text: x }); }))));
    var tb = h('tbody');
    var from = doc.from || {};
    var src = ctx.sources();
    var sel = h('select', { class: 'ed-in', 'data-path': 'from', 'data-source-pick': '', 'aria-label': 'Source',
      on: { change: function (e) {
        var v = e.target.value;
        ctx.change(function (d) { d.from = v.indexOf('table:') === 0 ? { table: v.slice(6) } : { kind: v.slice(5) }; }, 'from');
        ctx.redraw();
      } } });
    var cur = from.table ? 'table:' + from.table : from.kind ? 'kind:' + from.kind : '';
    if (!from.table && !from.kind) { sel.appendChild(h('option', { value: '', text: from.python ? 'Python' : from.query ? 'SQL' : from.model ? 'a model\'s answer' : 'Choose…' })); }
    (src.kinds || []).forEach(function (k) { sel.appendChild(h('option', { value: 'kind:' + k.kind, selected: cur === 'kind:' + k.kind, text: k.kind })); });
    (src.tables || []).filter(function (x) { return x.table !== doc.table; }).forEach(function (x) { sel.appendChild(h('option', { value: 'table:' + x.table, selected: cur === 'table:' + x.table, text: x.table })); });
    if (cur && !Array.prototype.some.call(sel.options, function (o) { return o.value === cur; })) { sel.appendChild(h('option', { value: cur, selected: true, text: cur.replace(/^\w+:/, '') })); }
    tb.appendChild(h('tr', { 'data-source-row': 'base' }, h('td', {}, sel), h('td', { 'data-conn': 'base', text: (kindOf(ctx, doc) || {}).connector || '' }), h('td', { text: 'base' }), h('td', { text: '' }),
      h('td', { 'data-rows': 'base', text: '' }), h('td')));
    lookups(doc).forEach(function (l, i) {
      var other = (src.tables || []).filter(function (x) { return x.table === l.table; })[0] || {};
      tb.appendChild(h('tr', { 'data-source-row': 'lookup', 'data-lookup': l.table },
        h('td', { text: l.table }), h('td', { 'data-conn': l.table, text: other.connector || '' }), h('td', { text: 'left (look-up)' }), h('td', {}, h('code', { text: l.on })),
        h('td', { 'data-rows': l.table, text: '' }),
        h('td', {}, h('button', { type: 'button', class: 'ed-linkbtn', 'data-edit': '', 'data-remove-lookup': l.table, 'aria-label': 'Remove the look-up of ' + l.table,
          on: { click: function () { ctx.change(function (d) { l.columns.forEach(function (c) { delete d.columns[c]; }); }, 'columns'); ctx.redraw(); } } }, 'Remove'))));
    });
    t.appendChild(tb);
    sec.appendChild(h('div', { class: 'ed-scroll' }, t));
    sec.appendChild(h('p', {}, h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-add-lookup': '', on: { click: function () { addLookup(ctx); } } }, '+ Look-up')));
    return sec;
  }

  function addLookup(ctx) {
    var h = ctx.h, src = ctx.sources(), doc = ctx.S.doc;
    var table = h('select', { class: 'ed-in', 'data-lookup-table': '', 'aria-label': 'The table to look up' }, (src.tables || []).filter(function (x) { return x.table !== doc.table; })
      .map(function (x) { return h('option', { value: x.table, text: x.table + (x.connector ? ' · ' + x.connector : '') }); }));
    var on = h('input', { class: 'ed-in', 'data-lookup-on': '', placeholder: '$.DESK', 'aria-label': 'On: the key in this table\'s source (a path or a formula)' });
    E.formula(on, ctx.names);
    var cols = h('div', { class: 'ed-picks', 'data-lookup-columns': '' });
    function fill() {
      E.clear(cols);
      var t = (src.tables || []).filter(function (x) { return x.table === table.value; })[0];
      (t ? t.columns : []).forEach(function (c) { cols.appendChild(h('label', {}, h('input', { type: 'checkbox', value: c.name, 'data-lookup-col': c.name }), ' ' + c.name + ' ', h('small', { text: c.type }))); });
    }
    table.addEventListener('change', fill);
    fill();
    var go = h('button', { type: 'button', class: 'ed-btn ed-primary', 'data-lookup-go': '' }, 'Add the look-up');
    go.addEventListener('click', function () {
      var key = on.value.trim();
      var picked = Array.prototype.filter.call(cols.querySelectorAll('input:checked'), function () { return true; }).map(function (x) { return x.value; });
      if (!key || !picked.length) { E.say('Say the key it looks up on and tick at least one column.', true); return; }
      var t = (src.tables || []).filter(function (x) { return x.table === table.value; })[0] || { columns: [] };
      ctx.change(function (d) {
        d.columns = d.columns || {};
        picked.forEach(function (c) {
          var col = t.columns.filter(function (x) { return x.name === c; })[0] || {};
          var name = d.columns[c] ? table.value.split('.').pop().toUpperCase() + '_' + c : c;
          d.columns[name] = { expr: 'related(' + table.value + '.' + c + ', on = ' + key + ')', type: col.type || 'string' };
        });
      }, 'columns');
      E.closeDialog();
      ctx.redraw();
    });
    ctx.dialog('Look up a column from another table', h('div', { class: 'ed-form' },
      h('p', { class: 'ed-small', text: 'Each row of this table looks up one row of the other by its key (many to one). To combine tables row by row, relate them in a semantic model. A look-up across connectors reads both sides into the server: its cost shows under the source.' }),
      h('label', {}, 'Table ', table), h('label', {}, 'On ', on), h('p', { text: 'Columns to bring in' }), cols, h('p', {}, go)));
  }

  function columns(ctx, doc) {
    var h = ctx.h, cols = doc.columns || {}, keys = doc.keys || [];
    var k = kindOf(ctx, doc);
    var available = (k ? k.columns : []).filter(function (c) {
      return !Object.keys(cols).some(function (n) { return cols[n].path === '$.' + c.name || n === c.name; });
    });
    var sec = h('section', { class: 'ed-card', 'aria-labelledby': 'edColH' },
      h('h3', { id: 'edColH' }, 'Columns ', h('small', { 'data-col-count': '', text: '(' + Object.keys(cols).length + ' chosen' + (k ? ' of ' + (Object.keys(cols).length + available.length) : '') + ')' })));
    var t = h('table', { class: 'ed-grid-t', 'data-columns': '', 'data-resize': 'columns', 'data-resize-key': 'editors.table-columns' });
    t.appendChild(h('thead', {}, h('tr', {}, ['Show', 'Name here', 'From (path or Rachana formula)', 'Type', 'Key', ''].map(function (x) { return h('th', { scope: 'col', text: x }); }))));
    var tb = h('tbody');
    Object.keys(cols).forEach(function (name) {
      var c = cols[name] || {}, isExpr = c.expr !== undefined && c.expr !== null, p = 'columns.' + name;
      var nameIn = h('input', { class: 'ed-in ed-narrow', value: name, 'data-col-name': name, 'data-path': p, 'aria-label': 'Name of ' + name,
        on: { change: function (e) {
          var v = e.target.value.trim();
          if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(v) || v === name) { e.target.value = name; return; }
          ctx.change(function (d) { var out = {}; Object.keys(d.columns).forEach(function (x) { out[x === name ? v : x] = d.columns[x]; }); d.columns = out; }, 'columns.' + v);
          ctx.redraw();
        }, focus: function () { ctx.focus(p); } } });
      var fromIn = h('input', { class: 'ed-in', value: isExpr ? c.expr : c.path || '', 'data-col-from': name, 'data-path': p + (isExpr ? '.expr' : '.path'), 'aria-label': 'Where ' + name + ' comes from',
        on: { input: function (e) {
          var v = e.target.value.trim();
          ctx.change(function (d) {
            var col = d.columns[name];
            delete col.path; delete col.expr;
            if (/^(\$|@|\^+)(\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*$/.test(v)) { col.path = v; } else { col.expr = v; }
          }, p + (/^[$@^]/.test(v) && !/[ (+\-*/]/.test(v) ? '.path' : '.expr'));
        }, focus: function () { ctx.focus(p); } } });
      E.formula(fromIn, ctx.names);
      var typeSel = h('select', { class: 'ed-in ed-narrow', 'data-col-type': name, 'data-path': p + '.type', 'aria-label': 'Type of ' + name,
        on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.columns[name].type = v; }, p + '.type'); } } },
        TYPES.map(function (x) { return h('option', { value: x, selected: (c.type || 'string') === x, text: x }); }));
      var isKey = c.key === true || c.role === 'key' || keys.indexOf(name) >= 0;
      var keyBox = h('input', { type: 'checkbox', checked: isKey, 'data-col-key': name, 'aria-label': name + ' is a key',
        on: { change: function (e) {
          var on = e.target.checked;
          ctx.change(function (d) {
            if (Array.isArray(d.keys)) { d.keys = d.keys.filter(function (x) { return x !== name; }); if (on) { d.keys.push(name); } }
            else if (on) { d.columns[name].key = true; } else { delete d.columns[name].key; if (d.columns[name].role === 'key') { delete d.columns[name].role; } }
          }, p);
        } } });
      tb.appendChild(h('tr', { 'data-col-row': name, 'data-path': p },
        h('td', {}, h('input', { type: 'checkbox', checked: true, 'data-col-show': name, 'aria-label': 'Show ' + name,
          on: { change: function () { ctx.change(function (d) { delete d.columns[name]; if (Array.isArray(d.keys)) { d.keys = d.keys.filter(function (x) { return x !== name; }); } }, 'columns'); ctx.redraw(); } } })),
        h('td', {}, nameIn), h('td', {}, fromIn, isExpr ? h('small', { class: 'ed-tag', text: relatedOf(c.expr) ? 'look-up' : 'computed' }) : null), h('td', {}, typeSel), h('td', {}, keyBox), h('td')));
    });
    available.forEach(function (c) {
      tb.appendChild(h('tr', { class: 'ed-off', 'data-col-available': c.name },
        h('td', {}, h('input', { type: 'checkbox', checked: false, 'data-col-add': c.name, 'aria-label': 'Show ' + c.name,
          on: { change: function () { ctx.change(function (d) { d.columns = d.columns || {}; d.columns[c.name] = { path: '$.' + c.name, type: typeOf(c.type) }; }, 'columns.' + c.name); ctx.redraw(); } } })),
        h('td', { text: c.name }), h('td', {}, h('small', { text: '$.' + c.name + (c.description ? ' · ' + c.description : '') })), h('td', { text: typeOf(c.type) }), h('td'), h('td')));
    });
    tb.appendChild(h('tr', { class: 'ed-builtin' }, h('td', { text: '●' }), h('td', { text: 'business_date' }), h('td', { text: 'follows the source (built in)' }), h('td', { text: 'date' }), h('td', { text: 'with the keys' }), h('td')));
    t.appendChild(tb);
    sec.appendChild(h('div', { class: 'ed-scroll' }, t));
    sec.appendChild(h('p', {}, h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-add-computed': '', on: { click: function () {
      var n = 'NEW_COLUMN', i = 1;
      while ((ctx.S.doc.columns || {})[n]) { n = 'NEW_COLUMN_' + (++i); }
      ctx.change(function (d) { d.columns = d.columns || {}; d.columns[n] = { expr: '$.' , type: 'decimal' }; }, 'columns.' + n);
      ctx.redraw();
      var el = E.root.querySelector('[data-col-from="' + n + '"]');
      if (el) { el.focus(); }
    } } }, '+ Computed column')));
    return sec;
  }

  function more(ctx, doc) {
    var h = ctx.h;
    var mat = typeof doc.materialize === 'string' ? doc.materialize : doc.materialize && doc.materialize.live ? 'live' : 'none';
    var matSel = h('select', { class: 'ed-in', 'data-path': 'materialize', 'data-materialize': '', 'aria-label': 'How its rows are kept',
      on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.materialize = v; }, 'materialize'); } } },
      [['none', 'read on demand'], ['on-load', 'kept per business date, rebuilt on each load']].map(function (o) { return h('option', { value: o[0], selected: mat === o[0], text: o[1] }); }));
    if (mat === 'live') { matSel.appendChild(h('option', { value: 'live', selected: true, text: 'live (in memory by key)' })); matSel.disabled = true; }
    return h('section', { class: 'ed-card' }, h('h3', { text: 'Rows' }),
      h('div', { class: 'ed-row2' },
        field(ctx, 'Keep the rows where (Rachana over the source)', doc.filter, 'filter', function (d, v) { if (v) { d.filter = v; } else { delete d.filter; } }, true),
        field(ctx, 'One row per (grain: a list in the document)', doc.grain, 'grain', function (d, v) { if (v) { d.grain = v; } else { delete d.grain; } })),
      h('label', { class: 'ed-field' }, h('span', { text: 'Materialisation' }), matSel));
  }

  function statement(ctx, host) {
    var h = ctx.h;
    var ta = h('textarea', { class: 'ed-in ed-code', rows: '12', spellcheck: 'false', 'data-statement': '', 'aria-label': 'The table as one Rachana statement' });
    ta.value = ctx.S.statement || '';
    ta.readOnly = ctx.S.readOnly;
    E.formula(ta, ctx.names);
    var note = h('p', { class: 'ed-small', 'data-statement-state': '', text: 'table … from … select … where …: one statement in the same grammar as every formula; type-ahead knows the columns and functions.' });
    var timer;
    ta.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () {
        ctx.api('POST', 'statement', { statement: ta.value, yaml: ctx.S.yaml, original: ctx.S.original }).then(function (r) {
          if (r.ok) {
            ctx.S.doc = r.doc; ctx.S.yaml = r.yaml; ctx.S.dirty = true; ctx.markDirty();
            ctx.setYaml(r.yaml);
            note.textContent = '✓ reads · ' + Object.keys(r.doc.columns || {}).length + ' columns';
            note.className = 'ed-small ed-good';
          } else {
            var p = (r.problems || [])[0] || {};
            note.textContent = (p.line ? 'line ' + p.line + ': ' : '') + (p.message || 'does not read yet');
            note.className = 'ed-small ed-bad';
          }
        }).catch(function (e) { note.textContent = e.message; note.className = 'ed-small ed-bad'; });
      }, 350);
    });
    host.appendChild(h('section', { class: 'ed-card' }, h('h3', { text: 'Text: the table as one statement' }), ta, note));
  }

  function refresh(ctx, host) {
    var r = ctx.S.preview;
    if (!r || !r.cost || !host) { return; }
    (r.cost.sides || []).forEach(function (s) {
      var key = s.role === 'base' ? 'base' : s.table;
      var cell = host.querySelector('[data-rows="' + key + '"]');
      if (cell) { cell.textContent = s.rows >= 0 ? E.num(s.rows) : 'not read'; }
      var conn = host.querySelector('[data-conn="' + key + '"]');
      if (conn && s.connector) { conn.textContent = s.connector; }
    });
    var line = host.querySelector('[data-cost-line]');
    if (line) {
      line.hidden = !r.cost.crossConnector;
      line.textContent = '! ' + r.cost.text;
      line.className = 'ed-cost ' + (r.cost.refused ? 'ed-bad' : 'ed-warn');
    }
  }

  E.register('table', { render: render, refresh: refresh });
})();
