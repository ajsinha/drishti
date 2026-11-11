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
/* The table editor (HOW_IT_FITS.md section S2, option A, with option C as its Text toggle): tables and virtual tables. The
   source and its look-ups as rows (a look-up is a join to another table on a key: Rachana's related(), one many-to-one hop, read on the
   server; across connectors it shows its cost and is refused above drishti.tables.scan-warn-rows until the reader filters); the columns
   as a checklist (the name here, where it comes from, its type, the key) with computed columns as Rachana formulas; the filter, the grain
   and the materialisation; business dates follow the source. The Text toggle shows the same table as one Rachana statement. The name
   and title, the rows' filter, grain and materialisation and the look-up dialog are drishti-inputs' Form (live, or submitted in the
   dialog) with the Form's formula widget (completing from the columns and functions: E.complete); the columns stay a grid of their own. */
(function () {
  'use strict';
  var E = window.Drishti.editors;
  var TYPES = ['string', 'integer', 'decimal', 'boolean', 'date', 'timestamp'];
  var text = false;     // the Text toggle, for this page
  var I = window.Drishti.elements && window.Drishti.elements.inputs;

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
    host.appendChild(E.liveForm(ctx, 'ed-row2', 'The table', {
      table: { type: 'string', title: 'Name', hook: { 'data-path': 'table' } },
      title: { type: 'string', title: 'Title', hook: { 'data-path': 'title' } } }, { table: doc.table, title: doc.title },
    function (d, path, v) { var t = (v || '').trim(); if (path === 'table') { d.table = t; } else if (t) { d.title = t; } else { delete d.title; } }));
    host.appendChild(sources(ctx, doc));
    host.appendChild(h('p', { class: 'ed-cost', 'data-cost-line': '', hidden: true }));
    host.appendChild(columns(ctx, doc));
    host.appendChild(more(ctx, doc));
    refresh(ctx, host);
  }

  function sources(ctx, doc) {
    var h = ctx.h;
    var sec = h('section', { class: 'ed-card', 'aria-labelledby': 'edSrcH' }, h('h3', { id: 'edSrcH', text: 'Source and look-ups' }));
    var from = doc.from || {};
    var src = ctx.sources();
    var cur = from.table ? 'table:' + from.table : from.kind ? 'kind:' + from.kind : '';
    var offered = (!from.table && !from.kind ? [{ value: '', label: from.python ? 'Python' : from.query ? 'SQL' : from.model ? 'a model\'s answer' : 'Choose…' }] : [])
      .concat((src.kinds || []).map(function (k) { return { value: 'kind:' + k.kind, label: k.kind }; }))
      .concat((src.tables || []).filter(function (x) { return x.table !== doc.table; }).map(function (x) { return { value: 'table:' + x.table, label: x.table }; }));
    if (cur && !offered.some(function (o) { return o.value === cur; })) { offered.push({ value: cur, label: cur.replace(/^\w+:/, '') }); }
    var rows = [{ key: 'base', source: cur, conn: (kindOf(ctx, doc) || {}).connector || '', join: 'base', on: '' }].concat(lookups(doc).map(function (l) {
      var other = (src.tables || []).filter(function (x) { return x.table === l.table; })[0] || {};
      return { key: l.table, source: l.table, conn: other.connector || '', join: 'left (look-up)', on: l.on, lookup: l };
    }));
    function side(r) { return ((ctx.S.preview && ctx.S.preview.cost && ctx.S.preview.cost.sides) || []).filter(function (s) { return (s.role === 'base' ? 'base' : s.table) === r.key; })[0]; }
    var box = E.rowGrid(ctx, 'sources', { label: 'Source and look-ups', rows: rows, id: function (r) { return r.key; },
      rowData: function (r) { return r.lookup ? { sourceRow: 'lookup', lookup: r.key } : { sourceRow: 'base', path: 'from' }; },
      columns: [
        { field: 'source', header: 'Source', width: 240, options: offered, editable: function (r) { return !r.lookup; }, redraw: true,
          render: function (r) { return r.lookup ? r.source : ((offered.filter(function (o) { return o.value === r.source; })[0] || {}).label || ''); },
          set: function (d, r, v) { if (v) { d.from = v.indexOf('table:') === 0 ? { table: v.slice(6) } : { kind: v.slice(5) }; } } },
        { field: 'conn', header: 'Connector', width: 140, render: function (r) { var s = side(r); return h('span', { 'data-conn': r.key, text: (s && s.connector) || r.conn }); } },
        { field: 'join', header: 'Look-up', width: 120 },
        { field: 'on', header: 'On', width: 160, render: function (r) { return r.on ? h('code', { text: r.on }) : ''; } },
        { field: 'rows', header: 'Rows (now)', width: 110, render: function (r) { var s = side(r); return h('span', { 'data-rows': r.key, text: s ? (s.rows >= 0 ? E.num(s.rows) : 'not read') : '' }); } },
        { field: 'remove', header: '', width: 90, render: function (r) {
          return r.lookup ? h('button', { type: 'button', class: 'ed-linkbtn', 'data-edit': '', 'data-remove-lookup': r.key, 'aria-label': 'Remove the look-up of ' + r.key, disabled: !!ctx.S.readOnly,
            on: { click: function () { ctx.change(function (d) { r.lookup.columns.forEach(function (c) { delete d.columns[c]; }); }, 'columns'); ctx.redraw(); } } }, 'Remove') : '';
        } }] });
    box.setAttribute('data-sources', '');
    sec.appendChild(box);
    sec.appendChild(h('p', {}, h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-add-lookup': '', on: { click: function () { addLookup(ctx); } } }, '+ Look-up')));
    return sec;
  }

  function addLookup(ctx) {
    var h = ctx.h, src = ctx.sources(), doc = ctx.S.doc;
    var tables = (src.tables || []).filter(function (x) { return x.table !== doc.table; });
    var names = [], parents = {};                                     // the columns of every table; the ones offered follow the table picked
    tables.forEach(function (t) { (t.columns || []).forEach(function (c) { if (!parents[c.name]) { parents[c.name] = []; names.push(c.name); } parents[c.name].push(t.table); }); });
    var host = h('div', { class: 'ed-form' });
    var f = new I.Form(host, { submitLabel: 'Add the look-up', submitHook: 'lookup-go', resetButton: false, pathAttr: 'data-form-path', label: 'Look up a column from another table',
      value: { table: tables.length ? tables[0].table : undefined, columns: [] },
      schema: { type: 'object', required: ['table', 'on', 'columns'], properties: {
        table: { type: 'string', title: 'Table', enum: tables.map(function (x) { return x.table; }), enumLabels: tables.map(function (x) { return x.table + (x.connector ? ' · ' + x.connector : ''); }),
          widget: 'select', allowEmpty: false, hook: 'lookup-table' },
        on: { type: 'string', title: 'On', description: 'The key in this table\'s source: a path or a formula', widget: 'formula', options: { complete: E.complete(ctx.names) }, placeholder: '$.DESK', hook: 'lookup-on' },
        columns: { type: 'array', title: 'Columns to bring in', items: { type: 'string', enum: names }, widget: 'checkboxes', optionHook: 'lookup-col', dependsOn: 'table', parents: parents } } },
      onSubmit: function (v) {
        var t = tables.filter(function (x) { return x.table === v.table; })[0] || { columns: [] };
        ctx.change(function (d) {
          d.columns = d.columns || {};
          v.columns.forEach(function (c) {
            var col = t.columns.filter(function (x) { return x.name === c; })[0] || {};
            var name = d.columns[c] ? v.table.split('.').pop().toUpperCase() + '_' + c : c;
            d.columns[name] = { expr: 'related(' + v.table + '.' + c + ', on = ' + v.on.trim() + ')', type: col.type || 'string' };
          });
        }, 'columns');
        E.closeDialog();
        ctx.redraw();
      } });
    ctx.dialog('Look up a column from another table', h('div', { class: 'ed-form' },
      h('p', { class: 'ed-small', text: 'Each row of this table looks up one row of the other by its key (many to one). To combine tables row by row, relate them in a semantic model. A look-up across connectors reads both sides into the server: its cost shows under the source.' }),
      host));
    return f;
  }

  function columns(ctx, doc) {
    var h = ctx.h, cols = doc.columns || {}, keys = doc.keys || [];
    var k = kindOf(ctx, doc);
    var available = (k ? k.columns : []).filter(function (c) {
      return !Object.keys(cols).some(function (n) { return cols[n].path === '$.' + c.name || n === c.name; });
    });
    var sec = h('section', { class: 'ed-card', 'aria-labelledby': 'edColH' },
      h('h3', { id: 'edColH' }, 'Columns ', h('small', { 'data-col-count': '', text: '(' + Object.keys(cols).length + ' chosen' + (k ? ' of ' + (Object.keys(cols).length + available.length) : '') + ')' })));
    var rows = Object.keys(cols).map(function (name) {             // the chosen columns, then the source's others (off), then the built-in date
      var c = cols[name] || {}, isExpr = c.expr !== undefined && c.expr !== null;
      return { state: 'chosen', show: true, name: name, from: isExpr ? c.expr : c.path || '', tag: isExpr ? (relatedOf(c.expr) ? 'look-up' : 'computed') : '', type: c.type || 'string',
        key: c.key === true || c.role === 'key' || keys.indexOf(name) >= 0 };
    }).concat(available.map(function (c) {
      return { state: 'available', show: false, name: c.name, from: '$.' + c.name + (c.description ? ' · ' + c.description : ''), type: typeOf(c.type), key: false, source: c };
    })).concat([{ state: 'builtin', show: true, name: 'business_date', from: 'follows the source (built in)', type: 'date', key: false }]);
    function chosen(r) { return r.state === 'chosen'; }
    var box = E.rowGrid(ctx, 'columns', { label: 'Columns', rows: rows, id: function (r) { return r.state + ':' + r.name; },
      rowData: function (r) { return r.state === 'chosen' ? { colRow: r.name, path: 'columns.' + r.name } : r.state === 'available' ? { colAvailable: r.name } : { colBuiltin: r.name }; },
      columns: [
        { field: 'show', header: 'Show', width: 70, type: 'boolean', redraw: true, editable: function (r) { return r.state !== 'builtin'; },
          set: function (d, r, on) {
            if (r.state === 'available' && on) { d.columns = d.columns || {}; d.columns[r.name] = { path: '$.' + r.name, type: typeOf(r.source.type) }; }
            if (r.state === 'chosen' && !on) { delete d.columns[r.name]; if (Array.isArray(d.keys)) { d.keys = d.keys.filter(function (x) { return x !== r.name; }); } }
          } },
        { field: 'name', header: 'Name here', width: 160, editable: chosen, redraw: true,
          validate: function (v) { return /^[A-Za-z_][A-Za-z0-9_]*$/.test(String(v || '').trim()) ? null : 'A name is letters, digits and _ (not starting with a digit)'; },
          set: function (d, r, v) {
            v = String(v || '').trim();
            if (v === r.name || d.columns[v]) { return; }
            var out = {};
            Object.keys(d.columns).forEach(function (x) { out[x === r.name ? v : x] = d.columns[x]; });
            d.columns = out;
            if (Array.isArray(d.keys)) { d.keys = d.keys.map(function (x) { return x === r.name ? v : x; }); }
          } },
        { field: 'from', header: 'From (path or Rachana formula)', width: 320, editor: 'code', editable: chosen,
          render: function (r) { return h('span', {}, h('code', { text: r.from }), r.tag ? h('small', { class: 'ed-tag', text: ' ' + r.tag }) : null); },
          set: function (d, r, v) {
            v = String(v || '').trim();
            var col = d.columns[r.name];
            delete col.path; delete col.expr;
            if (/^(\$|@|\^+)(\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*$/.test(v)) { col.path = v; } else { col.expr = v; }
          } },
        { field: 'type', header: 'Type', width: 110, options: TYPES, editable: chosen, set: function (d, r, v) { d.columns[r.name].type = v; } },
        { field: 'key', header: 'Key', width: 70, type: 'boolean', editable: chosen,
          render: function (r) { return r.state === 'builtin' ? 'with the keys' : r.key ? '✓' : ''; },
          set: function (d, r, on) {
            if (Array.isArray(d.keys)) { d.keys = d.keys.filter(function (x) { return x !== r.name; }); if (on) { d.keys.push(r.name); } }
            else if (on) { d.columns[r.name].key = true; } else { delete d.columns[r.name].key; if (d.columns[r.name].role === 'key') { delete d.columns[r.name].role; } }
          } }] });
    box.setAttribute('data-columns', '');
    sec.appendChild(box);
    sec.appendChild(h('p', {}, h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-add-computed': '', on: { click: function () {
      var n = 'NEW_COLUMN', i = 1;
      while ((ctx.S.doc.columns || {})[n]) { n = 'NEW_COLUMN_' + (++i); }
      ctx.change(function (d) { d.columns = d.columns || {}; d.columns[n] = { expr: '$.' , type: 'decimal' }; }, 'columns.' + n);
      ctx.redraw();
      var g = (E.root.querySelector('[data-rowgrid="columns"]') || {}).grid;               // its formula opened for typing
      if (g) { for (var r = 0; r < g.model.rowCount(); r++) { if (g.model.idAt(r) === 'chosen:' + n) { g.startEditing(r, 2); break; } } }
    } } }, '+ Computed column')));
    return sec;
  }

  function more(ctx, doc) {
    var h = ctx.h;
    var mat = typeof doc.materialize === 'string' ? doc.materialize : doc.materialize && doc.materialize.live ? 'live' : 'none';
    var kinds = [['none', 'read on demand'], ['on-load', 'kept per business date, rebuilt on each load']].concat(mat === 'live' ? [['live', 'live (in memory by key)']] : []);
    return h('section', { class: 'ed-card' }, h('h3', { text: 'Rows' }), E.liveForm(ctx, 'ed-rows', 'Rows', {
      filter: { type: 'string', title: 'Keep the rows where (Rachana over the source)', widget: 'formula', options: { complete: E.complete(ctx.names) }, hook: { 'data-path': 'filter' } },
      grain: { type: 'string', title: 'One row per (grain: a list in the document)', hook: { 'data-path': 'grain' } },
      materialize: { type: 'string', title: 'Materialisation', enum: kinds.map(function (k) { return k[0]; }), enumLabels: kinds.map(function (k) { return k[1]; }), widget: 'select', allowEmpty: false,
        readOnly: mat === 'live', hook: { 'data-path': 'materialize', 'data-materialize': '' } } },
    { filter: doc.filter, grain: doc.grain === undefined || doc.grain === null ? undefined : String(doc.grain), materialize: mat },
    function (d, path, v) {
      if (path === 'materialize') { d.materialize = v; return; }
      var t = (v || '').trim();
      if (t) { d[path] = t; } else { delete d[path]; }
    }));
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
    var sources = host.querySelector('[data-rowgrid="sources"]');
    if (sources && sources.grid) { sources.grid.refresh(); }                // its rows and connectors read the preview's cost as they are drawn
    var line = host.querySelector('[data-cost-line]');
    if (line) {
      line.hidden = !r.cost.crossConnector;
      line.textContent = '! ' + r.cost.text;
      line.className = 'ed-cost ' + (r.cost.refused ? 'ed-bad' : 'ed-warn');
    }
  }

  E.register('table', { render: render, refresh: refresh });
})();
