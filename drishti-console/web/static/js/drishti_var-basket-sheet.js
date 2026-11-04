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
/* The basket's sheet (docs/guides/VAR_GUIDE.md section 3.5): a grid like Excel, one column per kind of part and the Op column (+ or −),
   drawn by drishti_var-basket.js. Only the rows in view are drawn (a sheet holds 20,000 rows), so 10,000 pasted ids scroll as fast as ten.

   Keys: the arrows, Tab and Shift+Tab move; Enter or typing edits a cell (Enter again keeps it and moves down, Escape drops the edit);
   Delete clears; in the Op column a click, Space, "+" or "-" sets the row's sign. Paste (Ctrl+V) fills from the selected cell downward,
   and rightward for several columns, Excel's tab-and-newline text; a first row of headings (Op, Trade, TRD, "Trade (TRD)": the sheet's own
   workbook) puts each pasted column under its heading. Each cell is coloured by the server's check of it (found, not found, or found but
   adding or taking away nothing), with its reason as the cell's tooltip; the console checks nothing itself. */
(function () {
  "use strict";
  var ROW_H = 24, MIN_ROWS = 14, SPARE = 20, MAX_ROWS = 20000;

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  /**
   * A grid over a model: {rows() [{op, TRD, ...}], columns() [{key, title}], changed(what), flag(key, row) -> {status, reason} | null,
   * checked(key, row) -> the text the server checked (a cell edited since shows no colour until the next answer)}.
   */
  function Grid(model) {
    var g = this;
    g.model = model;
    g.cur = { r: 0, c: 1 };
    g.editing = null;
    g.root = h("div", "vbs");
    g.root.dataset.vbSheet = "";
    g.root.tabIndex = 0;
    g.root.setAttribute("role", "grid");
    g.root.setAttribute("aria-label", "The basket's sheet: a column per kind of part; paste columns from Excel");
    g.table = h("table", "vbs-table");
    g.head = h("thead");
    g.body = h("tbody");
    g.table.appendChild(g.head);
    g.table.appendChild(g.body);
    g.root.appendChild(g.table);
    g.root.addEventListener("scroll", function () { g.draw(); });
    g.root.addEventListener("mousedown", function (ev) { g.onMouse(ev); });
    g.root.addEventListener("keydown", function (ev) { g.onKey(ev); });
    g.root.addEventListener("paste", function (ev) { g.onPaste(ev); });
    g.root.addEventListener("copy", function (ev) { g.onCopy(ev); });
  }

  Grid.prototype.keys = function () { return ["op"].concat(this.model.columns().map(function (c) { return c.key; })); };
  Grid.prototype.count = function () { return Math.max(MIN_ROWS, this.model.rows().length + SPARE); };

  /** Draws the headings and the rows in view (with spacers above and below). */
  Grid.prototype.draw = function () {
    var g = this, cols = g.model.columns(), rows = g.model.rows(), n = g.count();
    var sig = cols.map(function (c) { return c.key; }).join(",");
    if (g.headSig !== sig) {
      g.headSig = sig;
      g.head.textContent = "";
      var tr = h("tr");
      tr.appendChild(h("th", "rn"));
      var op = h("th", "op");
      op.appendChild(document.createTextNode("Op"));
      op.appendChild(h("span", "t", "+ or −"));
      tr.appendChild(op);
      cols.forEach(function (c) {
        var th = h("th");
        th.dataset.vbCol = c.key;
        th.appendChild(document.createTextNode(c.title));
        th.appendChild(h("span", "t", c.key));
        tr.appendChild(th);
      });
      g.head.appendChild(tr);
    }
    var top = g.root.scrollTop, view = g.root.clientHeight || 360;
    var first = Math.max(0, Math.floor(top / ROW_H) - 5), last = Math.min(n, Math.ceil((top + view) / ROW_H) + 5);
    g.body.textContent = "";
    var keys = g.keys();
    if (first > 0) { var sp = h("tr", "vbs-spacer"); sp.style.height = (first * ROW_H) + "px"; sp.appendChild(h("td")).colSpan = keys.length + 1; g.body.appendChild(sp); }
    for (var r = first; r < last; r++) {
      var row = rows[r] || null, tr2 = h("tr");
      tr2.dataset.row = String(r);
      tr2.appendChild(h("td", "rn", (r + 1).toLocaleString("en-US")));
      keys.forEach(function (k, ci) {
        var v = row ? (k === "op" ? (row.op === "-" ? "−" : "+") : (row[k] || "")) : "";
        var td = h("td", k === "op" ? "op" : "cell", v);
        td.dataset.c = String(ci);
        if (k !== "op") {
          td.dataset.vbCell = k + ":" + r;
          if (v) {
            var f = g.model.checked(k, r) === row[k] ? g.model.flag(k, r) : undefined;
            if (f === undefined) td.classList.add("pending");
            else if (!f) td.classList.add("okc");
            else { td.classList.add(f.status === "not-found" ? "badc" : "warnc"); td.title = f.reason; td.dataset.status = f.status; }
          } else td.classList.add("empty");
        } else if (!row) td.classList.add("empty");
        if (g.cur.r === r && g.cur.c === ci) td.classList.add("cur");
        tr2.appendChild(td);
      });
      g.body.appendChild(tr2);
    }
    if (last < n) { var sp2 = h("tr", "vbs-spacer"); sp2.style.height = ((n - last) * ROW_H) + "px"; sp2.appendChild(h("td")).colSpan = keys.length + 1; g.body.appendChild(sp2); }
    if (g.editing && g.editing.r >= first && g.editing.r < last) g.placeEditor();
  };

  Grid.prototype.td = function (r, c) { return this.body.querySelector('tr[data-row="' + r + '"] td[data-c="' + c + '"]'); };

  Grid.prototype.select = function (r, c, keepView) {
    var g = this, keys = g.keys();
    r = Math.max(0, Math.min(MAX_ROWS - 1, r));
    c = Math.max(0, Math.min(keys.length - 1, c));
    g.cur = { r: r, c: c };
    if (!keepView) {
      var top = r * ROW_H, view = g.root.clientHeight || 360, head = g.head.offsetHeight || ROW_H;
      if (top < g.root.scrollTop) g.root.scrollTop = top;
      else if (top + ROW_H > g.root.scrollTop + view - head) g.root.scrollTop = top + ROW_H - view + head;
    }
    g.draw();
  };

  Grid.prototype.onMouse = function (ev) {
    var td = ev.target.closest("td[data-c]");
    if (!td || ev.target.tagName === "INPUT") return;
    ev.preventDefault();
    var r = +td.parentNode.dataset.row, c = +td.dataset.c;
    this.commit();
    this.root.focus({ preventScroll: true });
    if (c === 0 && this.cur.r === r && this.cur.c === 0) { this.toggleOp(r); return; }
    this.select(r, c, true);
    if (ev.detail === 2 && c > 0) this.edit("");
  };

  Grid.prototype.ensure = function (r) {
    var rows = this.model.rows();
    while (rows.length <= r) rows.push({ op: this.model.mode ? this.model.mode() : "+" });
    return rows[r];
  };

  Grid.prototype.set = function (r, key, value) {
    var row = this.ensure(r);
    if (key === "op") row.op = /^\s*[-−–]/.test(value) ? "-" : "+";
    else row[key] = String(value || "").trim();
  };

  Grid.prototype.toggleOp = function (r) {
    var row = this.ensure(r);
    row.op = row.op === "-" ? "+" : "-";
    this.model.changed("op");
    this.draw();
  };

  Grid.prototype.onKey = function (ev) {
    var g = this;
    if (g.editing) return;                                       // the editor has its own keys
    var k = ev.key, r = g.cur.r, c = g.cur.c;
    if ((ev.ctrlKey || ev.metaKey) && (k === "v" || k === "c" || k === "x")) return;   // the paste and copy events
    if (k === "ArrowDown") { ev.preventDefault(); g.select(r + 1, c); }
    else if (k === "ArrowUp") { ev.preventDefault(); g.select(r - 1, c); }
    else if (k === "ArrowRight") { ev.preventDefault(); g.select(r, c + 1); }
    else if (k === "ArrowLeft") { ev.preventDefault(); g.select(r, c - 1); }
    else if (k === "Tab") { ev.preventDefault(); g.select(r, c + (ev.shiftKey ? -1 : 1)); }
    else if (k === "PageDown") { ev.preventDefault(); g.select(r + 12, c); }
    else if (k === "PageUp") { ev.preventDefault(); g.select(r - 12, c); }
    else if (k === "Home" && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); g.select(0, c); }
    else if (k === "End" && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); g.select(Math.max(0, g.model.rows().length - 1), c); }
    else if (k === "Delete" || k === "Backspace") {
      ev.preventDefault();
      if (c === 0) return;
      var row = g.model.rows()[r];
      if (row && row[g.keys()[c]]) { row[g.keys()[c]] = ""; g.model.changed("clear"); g.draw(); }
    } else if (c === 0 && (k === " " || k === "Enter" || k === "+" || k === "-")) {
      ev.preventDefault();
      if (k === "+" || k === "-") { g.set(r, "op", k); g.model.changed("op"); g.draw(); } else g.toggleOp(r);
    } else if (k === "Enter" || k === "F2") { ev.preventDefault(); g.edit(null); }
    else if (k.length === 1 && !ev.ctrlKey && !ev.metaKey && !ev.altKey && c > 0) { ev.preventDefault(); g.edit(k); }
  };

  /** Edits the selected cell in place: {@code start} replaces the text (a typed key), null keeps it (Enter, F2, a double click). */
  Grid.prototype.edit = function (start) {
    var g = this, key = g.keys()[g.cur.c];
    if (key === "op") return;
    var row = g.model.rows()[g.cur.r];
    var inp = h("input", "vbs-edit mono");
    inp.value = start === null || start === "" ? (row && row[key]) || "" : start;
    inp.setAttribute("aria-label", "Cell " + key + " row " + (g.cur.r + 1));
    inp.dataset.vbEdit = "";
    g.editing = { r: g.cur.r, c: g.cur.c, input: inp };
    inp.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter") { ev.preventDefault(); g.commit(); g.select(g.cur.r + 1, g.cur.c); g.root.focus({ preventScroll: true }); }
      else if (ev.key === "Tab") { ev.preventDefault(); g.commit(); g.select(g.cur.r, g.cur.c + (ev.shiftKey ? -1 : 1)); g.root.focus({ preventScroll: true }); }
      else if (ev.key === "Escape") { ev.preventDefault(); ev.stopPropagation(); g.editing = null; g.draw(); g.root.focus({ preventScroll: true }); }
      else if ((ev.key === "ArrowDown" || ev.key === "ArrowUp") && !ev.shiftKey) {
        ev.preventDefault(); g.commit(); g.select(g.cur.r + (ev.key === "ArrowDown" ? 1 : -1), g.cur.c); g.root.focus({ preventScroll: true });
      }
    });
    inp.addEventListener("paste", function (ev) {
      var t = (ev.clipboardData || window.clipboardData).getData("text");
      if (/[\t\r\n]/.test(t.replace(/[\r\n]+$/, ""))) { ev.preventDefault(); ev.stopPropagation(); g.editing = null; g.paste(t); g.root.focus({ preventScroll: true }); }
    });
    inp.addEventListener("blur", function () { setTimeout(function () { if (g.editing && g.editing.input === inp) g.commit(); }, 0); });
    g.placeEditor();
    inp.focus();
    var n = inp.value.length;
    inp.setSelectionRange(n, n);
  };

  Grid.prototype.placeEditor = function () {
    var e = this.editing, td = e && this.td(e.r, e.c);
    if (!td || td.contains(e.input)) return;
    td.textContent = "";
    td.classList.add("editing");
    td.appendChild(e.input);
  };

  Grid.prototype.commit = function () {
    var e = this.editing;
    if (!e) return;
    this.editing = null;
    var key = this.keys()[e.c], row = this.model.rows()[e.r], before = row ? row[key] || "" : "", v = e.input.value.trim();
    if (v !== before) { this.set(e.r, key, v); this.model.changed("edit"); }
    this.draw();
  };

  /** Pastes Excel's text (tabs between columns, newlines between rows) at the selected cell. */
  Grid.prototype.onPaste = function (ev) {
    if (this.editing) return;
    var t = (ev.clipboardData || window.clipboardData).getData("text");
    if (!t) return;
    ev.preventDefault();
    this.paste(t);
  };

  Grid.prototype.paste = function (text) {
    var g = this, keys = g.keys(), lines = String(text).replace(/\r\n?/g, "\n").replace(/\n+$/, "").split("\n");
    var grid = lines.map(function (l) { return l.split("\t"); });
    var map = g.headings(grid[0]), r0 = g.cur.r;
    if (map) grid.shift();
    var room = MAX_ROWS - r0, cut = grid.length > room;
    if (cut) grid = grid.slice(0, room);
    grid.forEach(function (cells, i) {
      cells.forEach(function (v, j) {
        var key = map ? map[j] : keys[g.cur.c + j];
        if (!key) return;
        if (key === "op") { if (v.trim()) g.set(r0 + i, "op", v); }
        else if (v.trim() || (g.model.rows()[r0 + i] || {})[key]) g.set(r0 + i, key, v);
      });
    });
    g.model.changed("paste", { rows: grid.length, cut: cut });
    g.select(r0 + Math.max(0, grid.length - 1), g.cur.c);
  };

  /** A first pasted row of headings (Op, Trade, TRD, "Trade (TRD)"): each column's key, or null when the row is data. */
  Grid.prototype.headings = function (cells) {
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

  Grid.prototype.onCopy = function (ev) {
    if (this.editing) return;
    var key = this.keys()[this.cur.c], row = this.model.rows()[this.cur.r];
    var v = row ? (key === "op" ? row.op : row[key] || "") : "";
    (ev.clipboardData || window.clipboardData).setData("text/plain", v);
    ev.preventDefault();
  };

  window.Drishti.VarBasketGrid = { create: function (model) { return new Grid(model); }, ROW_H: ROW_H, MAX_ROWS: MAX_ROWS };
})();
