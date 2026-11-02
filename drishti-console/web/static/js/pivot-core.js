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
/* The pivot grid (PIVOT_GUIDE.md; grown from the MR cube's cube-core.js): draws an answer of /api/v1/pivot/query as it is, rows in display
   order (headers, members, Others, subtotals below or above, the grand total), columns × measures, a + or − on every row that can open,
   ≈ beside an approximate figure and a mark on holistic columns. It computes nothing: every figure, total and subtotal is the server's.
   window.PivotCore.render(el, answer, opts) returns a small controller (selection, keyboard). */
(function () {
  "use strict";

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return {"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]; });
  }

  /** A figure as the grid shows it: bn and m for large money, integers for counts, percentages for show-as. */
  function fmt(v, m) {
    if (v == null || typeof v !== "number" || !isFinite(v)) { return ""; }
    if (m && m.showAs && String(m.showAs).indexOf("pct") === 0) { return (v * 100).toFixed(2) + "%"; }
    if (m && (m.showAs === "rank" || /^count|^trades/.test(m.id || ""))) { return Math.round(v).toLocaleString("en-US"); }
    var a = Math.abs(v);
    if (a >= 1e9) { return (v / 1e9).toFixed(2) + " bn"; }
    if (a >= 1e6) { return (v / 1e6).toFixed(2) + " m"; }
    return v.toLocaleString("en-US", {minimumFractionDigits: 2, maximumFractionDigits: 2});
  }

  function render(el, a, opts) {
    opts = opts || {};
    var measures = a.measures || [];
    var cols = a.columns || [];
    var rows = a.rows || [];
    var cells = a.cells || {};
    var diff = opts.diff || null;
    var h = [];
    h.push('<table class="pv-table" role="grid"><thead>');
    if (cols.length > 1 || (cols[0] && cols[0].kind !== "grand")) {
      h.push('<tr><th class="pv-corner" rowspan="2">' + esc((a.rowDimensions || []).join(" › ")) + '</th>');
      cols.forEach(function (c) {
        var cls = c.kind === "grand" || c.kind === "total" ? " pv-coltotal" : "";
        h.push('<th class="pv-colhead' + cls + '" colspan="' + measures.length + '" scope="colgroup">' + esc(c.label) + '</th>');
      });
      h.push('</tr><tr>');
    } else {
      h.push('<tr><th class="pv-corner">' + esc((a.rowDimensions || []).join(" › ")) + '</th>');
    }
    cols.forEach(function () {
      measures.forEach(function (m) {
        var mark = m.kind === "holistic" ? ' <span class="pv-kind" title="holistic: each cell and total from its own members">H</span>'
          : m.exact === false ? ' <span class="pv-kind approx" title="approximate: shown ≈ with its error">≈</span>' : "";
        h.push('<th class="pv-mhead" scope="col" title="' + esc(m.id + " · " + m.kind) + '">' + esc(m.label) + mark + '</th>');
      });
    });
    h.push('</tr></thead><tbody>');
    rows.forEach(function (r, ri) {
      var kind = r.kind;
      var depth = r.depth || 0;
      var toggle = "";
      if (kind === "node" || kind === "header") {
        if (r.expanded || kind === "header") {
          toggle = '<button type="button" class="pv-tg" data-pv-toggle="' + ri + '" aria-expanded="true" title="Collapse (←)">−</button>';
        } else if (r.expandable) {
          toggle = '<button type="button" class="pv-tg" data-pv-toggle="' + ri + '" aria-expanded="false" title="Expand (→)">+</button>';
        } else {
          toggle = '<span class="pv-tg pv-leaf"></span>';
        }
      }
      var by = (kind === "header" || (kind === "node" && r.expanded)) && r.by ? ' <span class="pv-by" title="its children are broken down by ' + esc(r.by) + '">by ' + esc(r.by) + '</span>' : "";
      var label = '<span class="pv-lab">' + esc(r.label) + '</span>' + (kind === "others" ? "" : by);
      var div = r.diversification ? ' <span class="pv-div" title="Σ children − this total, for each holistic measure">Σ−total ' + Object.keys(r.diversification).map(function (k) { return esc(k) + " " + fmt(r.diversification[k], {}); }).join(", ") + '</span>' : "";
      h.push('<tr class="pv-r pv-' + kind + '" data-row="' + ri + '" aria-level="' + Math.max(1, depth) + '">');
      h.push('<th class="pv-rowhead" scope="row" style="padding-left:' + (8 + Math.max(0, depth - 1) * 16) + 'px">' + toggle + label + div + '</th>');
      cols.forEach(function (c, ci) {
        var key = r.key + "|" + c.key;
        var cell = kind === "header" ? null : cells[key];
        measures.forEach(function (m, mi) {
          var v = cell && cell.values ? cell.values[m.label] : null;
          var note = cell && cell.notes ? cell.notes[m.label] : "";
          var text = fmt(v, m);
          if (cell && v != null && m.exact === false) { text = "≈ " + text; }
          var d = diff && diff[key] && diff[key].delta ? diff[key].delta[m.label] : null;
          var cls = "pv-c" + (c.kind === "grand" || c.kind === "total" ? " pv-coltotal" : "") + (m.kind === "holistic" ? " pv-hol" : "");
          var title = note ? ' title="' + esc(note) + '"' : "";
          h.push('<td class="' + cls + '" data-cell="' + esc(key) + '" data-m="' + mi + '" data-c="' + ci + '" tabindex="-1"' + title + '>' + text
            + (d != null && d !== 0 ? '<span class="pv-delta ' + (d > 0 ? "up" : "down") + '">' + (d > 0 ? "+" : "") + fmt(d, m) + '</span>' : "")
            + (diff && diff[key] && diff[key].status ? '<span class="pv-delta">' + esc(diff[key].status) + '</span>' : "") + '</td>');
        });
      });
      h.push('</tr>');
    });
    h.push('</tbody></table>');
    el.innerHTML = h.join("");

    var selected = [];
    function select(td, add) {
      if (!add) { selected.forEach(function (x) { x.classList.remove("sel"); }); selected = []; }
      if (td && selected.indexOf(td) < 0) { td.classList.add("sel"); selected.push(td); td.focus({preventScroll: false}); }
      if (opts.onSelect) { opts.onSelect(selected.map(function (x) { return {key: x.getAttribute("data-cell"), m: +x.getAttribute("data-m"), c: +x.getAttribute("data-c"), row: +x.parentNode.getAttribute("data-row")}; })); }
    }
    el.onclick = function (e) {
      var t = e.target.closest("[data-pv-toggle]");
      if (t) { opts.onToggle && opts.onToggle(rows[+t.getAttribute("data-pv-toggle")], t.getAttribute("aria-expanded") === "true", e.shiftKey); return; }
      var td = e.target.closest("td[data-cell]");
      if (td) {
        select(td, e.shiftKey || e.ctrlKey || e.metaKey);
        if (opts.onCell && !e.shiftKey) { opts.onCell(td.getAttribute("data-cell"), measures[+td.getAttribute("data-m")], td); }
      }
    };
    el.onkeydown = function (e) {
      var cur = selected[selected.length - 1] || el.querySelector("td[data-cell]");
      if (!cur) { return; }
      var tr = cur.parentNode;
      var row = rows[+tr.getAttribute("data-row")];
      var idx = Array.prototype.indexOf.call(tr.children, cur);
      if (e.key === "ArrowRight" && (row.kind === "node" || row.kind === "header") && idx <= 1 && opts.onToggle) {
        if (!row.expanded && row.expandable || e.shiftKey) { e.preventDefault(); opts.onToggle(row, false, e.shiftKey); return; }
      }
      if (e.key === "ArrowLeft" && idx <= 1 && opts.onToggle && (row.expanded || row.kind === "header" || row.kind === "total")) {
        e.preventDefault(); opts.onToggle(row, true, e.shiftKey); return;
      }
      var next = null;
      if (e.key === "ArrowRight") { next = cur.nextElementSibling; }
      if (e.key === "ArrowLeft") { next = cur.previousElementSibling && cur.previousElementSibling.tagName === "TD" ? cur.previousElementSibling : null; }
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        var sib = e.key === "ArrowDown" ? tr.nextElementSibling : tr.previousElementSibling;
        while (sib && !sib.children[idx]) { sib = e.key === "ArrowDown" ? sib.nextElementSibling : sib.previousElementSibling; }
        next = sib ? sib.children[idx] : null;
        if (next && next.tagName !== "TD") { next = null; }
      }
      if (next) { e.preventDefault(); select(next, e.shiftKey); return; }
      if (e.key === "Enter" && opts.onRows) { e.preventDefault(); opts.onRows(cur.getAttribute("data-cell")); }
    };
    return {
      selected: function () { return selected.slice(); },
      focus: function () { var td = el.querySelector("td[data-cell]"); if (td) { select(td, false); } }
    };
  }

  window.PivotCore = {render: render, fmt: fmt, esc: esc};
})();
