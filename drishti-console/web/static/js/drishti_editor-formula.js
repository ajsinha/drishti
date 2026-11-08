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
/* The editors' Rachana input (docs/architecture/SHARED_SCREENS.md section 7, "one grammar"): every formula, filter, row rule and computed column is
   typed in one of these: drishti-inputs' code editor in Rachana (one line for an input, the whole text for a text area), the element kept
   as the value's holder (upgradeCodeArea). As you type, the word before the caret is completed from Rachana's function catalogue (the
   server's, with each function's signature and one line) and the names the editor knows (columns, aliases, measures, tables): the code
   editor's completion (Up and Down, Enter or Tab, Escape). The console never parses Rachana for meaning: the server checks every
   formula as typed and the problem shows at its line. */
(function () {
  'use strict';
  var E = (window.Drishti.editors = window.Drishti.editors || {});

  /** What completes a word: the editor's names first, then the functions (a name or a dotted part starting with it). */
  function entries(w, names) {
    w = String(w || '').toLowerCase();
    if (!w) { return []; }
    var ns = (names ? names() : []).filter(function (n) { var x = n.name.toLowerCase(); return x.indexOf(w) === 0 || x.indexOf('.' + w) > 0; })
      .map(function (n) { return { name: n.name, description: n.note || '', kind: 'field' }; });
    var fns = (E.functions || []).filter(function (f) { return f.name.toLowerCase().indexOf(w) === 0; })
      .map(function (f) { return { name: f.name, signature: f.signature || f.name + '()', description: String(f.description || '').split('. ')[0], kind: 'function' }; });
    return ns.concat(fns).slice(0, 12);
  }

  /** The code editor's completion hook over the editor's names ({@code names()}: [{name, note}]) and the functions (a Form's formula widget takes it as options.complete). */
  E.complete = function (names) { return function (c) { return entries(c.word.replace(/^[$@]/, ''), names); }; };

  /** A live drishti-inputs Form for some of the document's fields (the table and model editors): each change goes into the document
   * ({@code apply(doc, path, value)}), each focus tells the editor where the cursor is (a field's hook data-path). */
  E.liveForm = function (ctx, cls, label, schema, value, apply) {
    var host = ctx.h('div', { class: cls });
    var f = new window.Drishti.elements.inputs.Form(host, { schema: { type: 'object', properties: schema }, value: value, live: true, layout: 'columns', label: label, pathAttr: 'data-form-path' });
    f.on('change', function (e) { ctx.change(function (d) { apply(d, e.path, e.value); }, e.path); });
    host.addEventListener('focusin', function (e) { var p = e.target.getAttribute && e.target.getAttribute('data-path'); if (p) { ctx.focus(p); } });
    return host;
  };

  /** The rows of a document's list (a model's tables, relationships, measures, hierarchies and row rules; a table's columns and sources) as
   * drishti-grid with cell editing: Enter, F2, a double click or typing edits a cell; a select's options, a checkbox, a formula (the
   * column's editor 'code': drishti-inputs' one-line CodeEditor completing from the editor's names and the functions). Each edit
   * transaction (an edit, a paste, Delete, undo or redo) goes into the document through the column's {@code set(doc, row, value)}.
   * {@code o}: {label, rows, id(row), rowData(row) -> data-* of the row, columns: [{field, header, width, set, options, editor, render(row,
   * doc) -> Node (a cell drawn by the host: a button, a computed word)}]}. Answers the box (its grid as box.grid). */
  E.rowGrid = function (ctx, key, o) {
    var G = window.Drishti.elements.grid, ro = !ctx.S || ctx.S.readOnly;
    var box = ctx.h('div', { class: 'ed-rowgrid', 'data-rowgrid': key });
    var byField = {};
    var cols = o.columns.map(function (c) {
      byField[c.field] = c;
      var x = { id: c.field, field: c.field, header: c.header, width: c.width || 160, validate: c.validate, sortable: false, filterable: false, enableRowGroup: false, enablePivot: false, enableValue: false,
        editable: !ro && !!c.set && (c.editable === undefined ? true : c.editable), options: c.options, editor: c.editor, type: c.type || 'text',
        complete: c.editor === 'code' ? E.complete(ctx.names) : undefined };
      if (c.render) { x.cellRenderer = function (p) { return c.render(p.row, p.doc); }; }
      return x;
    });
    var rows = (o.rows || []).map(function (r, i) { return Object.assign({ __id: String(o.id ? o.id(r, i) : i) }, r); });
    var grid = box.grid = new G.Grid(box, { columns: cols, rows: rows, getRowId: function (r) { return r.__id; }, layout: 'wells', density: 'compact', chrome: false,
      checkboxes: false, rowSelection: 'none', clientAggregation: false, pinTotals: false, label: o.label, getRowData: o.rowData });
    var byId = {};
    rows.forEach(function (r) { byId[r.__id] = r; });
    grid.on('editTransaction', function (tx) {
      if (!tx.edits.length) { return; }
      ctx.change(function (d) { tx.edits.forEach(function (e) { var c = byField[e.field]; if (c && c.set) { c.set(d, byId[e.rowId], e.value); } }); }, o.path || key);
      if (tx.edits.some(function (e) { return (byField[e.field] || {}).redraw; })) { setTimeout(function () { ctx.redraw(); }, 0); }   // a column whose edit changes the rows (a rename, show or hide)
    });
    var rh = (grid.model.viewport && grid.model.viewport.rowHeight) || 28;                // as tall as its rows and heading (a 44 px heading on a touch screen) and a scroll bar, scrolling past 14 rows
    box.style.height = (Math.min(rows.length, 14) * rh + Math.max(44, rh + 4) + 18) + 'px';
    return box;
  };

  /** Makes {@code el} (an input or textarea outside a Form) a Rachana input. {@code names()} answers the editor's names: [{name, note}]. */
  E.formula = function (el, names) {
    if (el.getAttribute('data-di-code')) { return el; }
    el.drishtiCodeOptions = { complete: E.complete(names) };
    el.setAttribute('data-di-code', 'rachana');
    if (el.isConnected) { window.Drishti.elements.inputs.upgradeCodeArea(el); }   // else drishti-inputs makes it one as it arrives in the page
    return el;
  };
})();
