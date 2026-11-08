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
/* The basket's sheet (docs/guides/VAR_GUIDE.md section 3.5): drishti-grid with cell editing (ELEMENTS.md 8.2) in drishti-risk's terminal
   layout, one column per kind of part and the Op column (+ or −), made by drishti_var-basket.js. The grid draws only the rows in view
   (a sheet holds 20,000 rows), so 10,000 pasted ids scroll as fast as ten.

   Keys are the grid's: the arrows, Tab and Shift+Tab move; Enter, F2 or typing edits a cell (Enter keeps it and moves down, Escape drops
   the edit); Delete clears; undo and redo. In the Op column "+" or "-" (or − typed) sets the row's sign. Paste (Ctrl+V) fills from the
   focused cell downward, and rightward for several columns, Excel's tab-and-newline text; a first row of headings (Op, Trade, TRD,
   "Trade (TRD)": the sheet's own workbook) puts each pasted column under its heading. Each cell is coloured by the server's check of it
   (found, not found, or found but adding or taking away nothing), with its reason as the cell's tooltip; the console checks nothing. */
(function () {
  "use strict";
  var MIN_ROWS = 14, SPARE = 20, MAX_ROWS = 20000;
  var E = window.Drishti.elements;

  /**
   * The sheet over a model: {rows() [{op, TRD, ...}], columns() [{key, title}], mode() "+" or "-", changed(what, info), flag(key, row) ->
   * {status, reason} | null, checked(key, row) -> the text the server checked (a cell edited since shows no colour until the next answer)}.
   */
  function Sheet(model) {
    var s = this;
    s.model = model;
    s.root = document.createElement("div");
    s.root.className = "vbs";
    s.root.dataset.vbSheet = "";
    s.root.setAttribute("aria-label", "The basket's sheet: a column per kind of part; paste columns from Excel");
    s.grid = null;
    s.sig = "";
    // a paste is the sheet's (its headings, its Op column, its 20,000 rows), not the grid's own: caught before the grid sees it
    s.root.addEventListener("paste", function (ev) {
      if (s.editing) {
        var t0 = (ev.clipboardData || window.clipboardData).getData("text");
        if (!/[\t\r\n]/.test(t0.replace(/[\r\n]+$/, ""))) return;            // one value into the open editor: the editor's own
        s.grid.stopEditing(true);
      }
      var t = (ev.clipboardData || window.clipboardData).getData("text");
      if (!t) return;
      ev.preventDefault();
      ev.stopPropagation();
      s.paste(t);
    }, true);
  }

  Sheet.prototype.keys = function () { return ["op"].concat(this.model.columns().map(function (c) { return c.key; })); };
  Sheet.prototype.count = function () { return Math.min(MAX_ROWS, Math.max(MIN_ROWS, this.model.rows().length + SPARE)); };
  Object.defineProperty(Sheet.prototype, "editing", { get: function () { return !!(this.grid && this.grid.editor && this.grid.editor.el); } });
  Object.defineProperty(Sheet.prototype, "cur", {
    get: function () { var f = this.grid && this.grid.model.navigator.focus; return f ? { r: f.row, c: f.col } : { r: 0, c: 1 }; },
    set: function (v) { if (this.grid && v) this.grid.model.navigator.setFocus(v.r, v.c); }
  });

  /** The rows the grid shows: the model's, then empty rows to type or paste into. */
  Sheet.prototype.gridRows = function () {
    var rows = this.model.rows(), n = this.count(), out = new Array(n);
    for (var i = 0; i < n; i++) out[i] = Object.assign({ __i: i }, rows[i] || { __empty: true });
    return out;
  };

  Sheet.prototype.columnsOf = function () {
    var s = this;
    var status = function (k) {
      return function (c) {
        var r = c.row.__i, v = c.row[k] || "", span = document.createElement("span");
        span.dataset.vbCell = k + ":" + r;
        span.textContent = v;
        if (!v) { span.className = "empty"; return span; }
        var f = s.model.checked(k, r) === v ? s.model.flag(k, r) : undefined;
        if (f === undefined) span.className = "pending";
        else if (!f) span.className = "okc";
        else { span.className = f.status === "not-found" ? "badc" : "warnc"; span.title = f.reason; span.dataset.status = f.status; }
        return span;
      };
    };
    return [{ field: "op", header: "Op (+ or −)", width: 74, pinned: "left", editable: function (r) { return true; }, sortable: false, filterable: false,
        format: function (v, r) { return r && r.__empty && !v ? "" : v === "-" ? "−" : "+"; },
        validate: function (v) { return /^\s*[-−–+]?\s*$/.test(String(v || "")) ? null : "+ or −"; } }]
      .concat(s.model.columns().map(function (c) {
        return { field: c.key, header: c.title + " (" + c.key + ")", width: 150, editable: true, sortable: false, filterable: false, cellRenderer: status(c.key) };
      }));
  };

  /** Draws the sheet: the grid made again when the columns changed, else its rows given again. */
  Sheet.prototype.draw = function () {
    var s = this, sig = s.keys().join(",");
    if (!s.grid || s.sig !== sig) {
      s.sig = sig;
      s.grid = E.risk.riskGrid(s.root, { title: "The basket's sheet", label: "The basket's sheet", columns: s.columnsOf(), rows: s.gridRows(), fitRows: 15,
        getRowId: function (r) { return String(r.__i); }, rowSelection: "none" });
      s.grid.on("editTransaction", function (tx) { s.edited(tx); });
      return;
    }
    var f = s.grid.model.navigator.focus;
    s.grid.setRows(s.gridRows());
    if (f) s.grid.model.navigator.setFocus(f.row, f.col);
  };

  /** An edit, a Delete or an undo in the grid: into the model's rows, then the basket hears it. */
  Sheet.prototype.edited = function (tx) {
    var s = this, any = false;
    (tx.edits || []).forEach(function (e) {
      var r = +e.rowId, key = e.field || e.colId;
      if (!(r >= 0)) return;
      s.set(r, key, e.value === null || e.value === undefined ? "" : e.value);
      any = true;
    });
    if (any) { s.model.changed(tx.source === "clear" ? "clear" : key0(tx)); s.draw(); }
    function key0(t) { return (t.edits || []).some(function (e) { return (e.field || e.colId) === "op"; }) ? "op" : "edit"; }
  };

  Sheet.prototype.select = function (r, c) {
    var s = this;
    if (!s.grid) s.draw();
    r = Math.max(0, Math.min(MAX_ROWS - 1, r));
    c = Math.max(0, Math.min(s.keys().length - 1, c));
    if (r >= s.grid.model.rowCount()) { s.ensure(r); s.draw(); }
    s.grid.focusCell(r, c);
  };

  Sheet.prototype.ensure = function (r) {
    var rows = this.model.rows();
    while (rows.length <= r) rows.push({ op: this.model.mode ? this.model.mode() : "+" });
    return rows[r];
  };

  Sheet.prototype.set = function (r, key, value) {
    var row = this.ensure(r);
    if (key === "op") row.op = /^\s*[-−–]/.test(value) ? "-" : "+";
    else row[key] = String(value || "").trim();
  };

  /** Pastes Excel's text (tabs between columns, newlines between rows) at the focused cell. */
  Sheet.prototype.paste = function (text) {
    var s = this, keys = s.keys(), at = s.cur, lines = String(text).replace(/\r\n?/g, "\n").replace(/\n+$/, "").split("\n");
    var grid = lines.map(function (l) { return l.split("\t"); });
    var map = s.headings(grid[0]), r0 = at.r;
    if (map) grid.shift();
    var room = MAX_ROWS - r0, cut = grid.length > room;
    if (cut) grid = grid.slice(0, room);
    grid.forEach(function (cells, i) {
      cells.forEach(function (v, j) {
        var key = map ? map[j] : keys[at.c + j];
        if (!key) return;
        if (key === "op") { if (v.trim()) s.set(r0 + i, "op", v); }
        else if (v.trim() || (s.model.rows()[r0 + i] || {})[key]) s.set(r0 + i, key, v);
      });
    });
    s.model.changed("paste", { rows: grid.length, cut: cut });
    s.draw();
    s.select(r0 + Math.max(0, grid.length - 1), at.c);
  };

  /** A first pasted row of headings (Op, Trade, TRD, "Trade (TRD)"): each column's key, or null when the row is data. */
  Sheet.prototype.headings = function (cells) {
    var cols = this.model.columns(), out = [], any = false;
    for (var j = 0; j < cells.length; j++) {
      var c = cells[j].trim(), m = /\(([A-Za-z]+)\)\s*$/.exec(c), key = null;
      if (!c) { out.push(null); continue; }
      if (/^op$/i.test(c)) key = "op";
      cols.forEach(function (col) {
        if (key) return;
        if ((m && m[1].toUpperCase() === col.key) || c.toUpperCase() === col.key || c.toLowerCase() === String(col.title).toLowerCase()) key = col.key;
      });
      if (!key) return null;                                        // a cell that is no heading: the row is data
      any = true;
      out.push(key);
    }
    return any ? out : null;
  };

  window.Drishti.VarBasketGrid = { create: function (model) { return new Sheet(model); }, MAX_ROWS: MAX_ROWS };
})();
