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
/* The scenario matrix, SMAT <id> [N] (terminal/smat.html; VAR_GUIDE.md section 3.9; the approved mock-up scenario-matrix-B). One answer of
   /api/smat/matrix fills the page: the columns (each date's source, VaR, ES, base PV, tail and gap) and the rows (a scenario day each, a
   cell per date: its P&L, or null where the date's window does not hold the day or the date is a gap). Everything else is computed here
   from it: PV (base PV + P&L), the change against the date before, each cell's rank, the tail outline (the date's VaR scenario and ES
   tail, outlined and marked V / E so it is not colour only), the sort, the tail-only filter. The matrix is a risk grid (drishti-risk's
   preset: <drishti-grid>, terminal layout; it draws only the rows in view, so 509 rows by 60 dates scroll as fast as 20), each date's
   VaR, ES and base PV a second grid under it; the chart (Line, Dots, Area or Bars) is a drishti-chart: the selected date's scenarios in
   the order of their days (Scenarios; a click selects one) or the selected row across the dates (Across dates), against the reference
   (-VaR in P&L, PV at VaR = base PV - VaR and the base PV in PV, none for the change; the legend follows). A big matrix comes as Arrow
   (drishti_table.js), a small one as JSON. By trade (a position whose trades are known) asks again with by=trade and the selected
   scenario. Every request goes to this console's own host. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the risk grid preset and chart (drishti-risk)
  var root = document.querySelector("[data-smat]");
  if (!root || !root.dataset.target) { return; }
  var $ = function (sel) { return root.querySelector(sel); };
  var ARROW_CELLS = 4000;               // a matrix with more cells than this travels as Arrow (when Arrow JS is on the page)
  var S = {
    target: root.dataset.target, days: parseInt(root.dataset.days, 10) || 20, ccy: root.dataset.ccy || "",
    mode: "pnl", by: "scenario", outline: true, tailOnly: false, sort: "worst-last", answer: null, trades: null,
    selRow: null, selCol: -1, view: [], rankCache: {}, chart: "line", chartView: "scenarios"
  };
  var CHART_TYPES = ["line", "dots", "area", "bars"], CHART_VIEWS = ["scenarios", "dates"];
  var CHART_KEY = "drishti.smat.chart", VIEW_KEY = "drishti.smat.chartView";   // the viewer's chart type and view, remembered in this browser
  try {
    var savedChart = window.localStorage.getItem(CHART_KEY), savedView = window.localStorage.getItem(VIEW_KEY);
    if (CHART_TYPES.indexOf(savedChart) >= 0) { S.chart = savedChart; }
    if (CHART_VIEWS.indexOf(savedView) >= 0) { S.chartView = savedView; }
  } catch (e) { /* storage off: Line, Scenarios */ }

  // ------------------------------------------------------------------------------------------------------------- formatting

  function fmt(v) {
    return v === null || v === undefined || isNaN(v) ? "—" : F.formatCompact(v, { units: [["bn", 1e9], ["m", 1e6], ["k", 1e3, 1e4]], decimals: function (a, u) { return u === "k" ? 1 : 2; },
      smallDecimals: 2, trim: "small", sign: "always" });
  }
  function full(v) {
    if (v === null || v === undefined || isNaN(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function short(d) { return String(d || "").slice(5); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }

  // -------------------------------------------------------------------------------------------------------------- the data

  function params(extra) {
    var p = new URLSearchParams();
    p.set("id", S.target);
    p.set("days", String(S.days));
    if (S.ccy) { p.set("ccy", S.ccy); }
    Object.keys(extra || {}).forEach(function (k) { if (extra[k]) { p.set(k, extra[k]); } });
    return p;
  }

  function load(extra) {
    var p = params(extra);
    var url = "/api/smat/matrix?" + p.toString();
    var big = S.days * 509 > ARROW_CELLS;
    if (big && window.Drishti.fetchTable && window.Arrow) {
      return window.Drishti.fetchTable(url, { shape: "records", at: "rows" }).then(function (t) {
        var a = t.answer();
        a.__format = t.format;
        return a;
      }, function (e) { var x = new Error(e.detail || e.message); x.code = e.code; throw x; });
    }
    return fetch(url, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.code = b.code; throw e; }
        b.__format = "json";
        return b;
      });
    });
  }

  function notice(text) {
    var n = $("[data-sm-notice]");
    n.hidden = !text;
    n.textContent = text || "";
  }

  // --------------------------------------------------------------------------------------------------- values by the mode

  function cols() { return (S.by === "trade" ? S.trades : S.answer).dates || []; }
  function rowsAll() { return (S.by === "trade" ? S.trades : S.answer).rows || []; }
  function key(r) { return S.by === "trade" ? r.trade : r.scenario; }

  /** A cell's P&L (null: none). */
  function pnl(r, i) { var c = cols()[i]; var v = c ? r[c.date] : null; return v === undefined ? null : v; }

  /** A cell in the current mode: [value, why-none]. */
  function cell(r, i) {
    var c = cols()[i];
    if (c.gap) { return [null, c.gap]; }
    var v = pnl(r, i);
    if (v === null) { return [null, S.by === "trade" ? "not in the position on " + c.date : "not in this date's scenario window"]; }
    if (S.mode === "pv") {
      if (c.basePv === null || c.basePv === undefined) { return [null, "no base PV for " + c.date + " (" + (c.source === "official" ? "the official record keeps none" : "the records carry none") + ")"]; }
      return [c.basePv + v, null];
    }
    if (S.mode === "change") {
      if (i === 0) { return [null, "no date before " + c.date + " in this matrix"]; }
      var p = pnl(r, i - 1);
      if (cols()[i - 1].gap || p === null) { return [null, "no value on " + cols()[i - 1].date]; }
      return [v - p, null];
    }
    return [v, null];
  }

  /** The tail of a date: scenario -> {rank, varScenario}. */
  function tailOf(i) {
    var c = cols()[i];
    if (!c || c.gap || !c.tail) { return {}; }
    if (c.__tail) { return c.__tail; }
    var t = {};
    c.tail.forEach(function (e) { t[e.scenario] = { rank: e.rank, isVar: e.rank === c.k, inEs: e.rank <= c.esCount }; });
    Object.defineProperty(c, "__tail", { value: t, enumerable: false });
    return t;
  }

  /** A cell's rank among its date's scenarios (1 = the worst P&L), computed once per date. */
  function rankOf(scenario, i) {
    var c = cols()[i];
    if (!c || c.gap || S.by === "trade") { return null; }
    var r = S.rankCache[c.date];
    if (!r) {
      var vals = [];
      rowsAll().forEach(function (row) { var v = row[c.date]; if (v !== null && v !== undefined) { vals.push([v, row.scenario]); } });
      vals.sort(function (a, b) { return a[0] - b[0]; });
      r = {};
      vals.forEach(function (x, k) { r[x[1]] = k + 1; });
      r.__n = vals.length;
      S.rankCache[c.date] = r;
    }
    return r[scenario] ? [r[scenario], r.__n] : null;
  }

  // --------------------------------------------------------------------------------------------------------- the view

  function inSomeTail(r) {
    for (var i = 0; i < cols().length; i++) { if (tailOf(i)[r.scenario]) { return true; } }
    return false;
  }

  function computeView() {
    var rows = rowsAll().slice();
    if (S.tailOnly && S.by !== "trade") { rows = rows.filter(inSomeTail); }
    var s = S.sort;
    if (s === "day") {
      rows.sort(function (a, b) { return key(a) < key(b) ? 1 : -1; });
    } else {
      var i = s === "worst-last" ? cols().length - 1 : cols().findIndex(function (c) { return c.date === s; });
      if (i < 0) { i = cols().length - 1; }
      rows.sort(function (a, b) {
        var x = cell(a, i)[0], y = cell(b, i)[0];
        if (x === null && y === null) { return key(a) < key(b) ? 1 : -1; }
        if (x === null) { return 1; }
        if (y === null) { return -1; }
        return x - y;
      });
    }
    S.view = rows;
  }

  function heat(node, v, maxAbs) {
    if (v === null || !maxAbs) { return; }
    var p = Math.min(1, Math.abs(v) / maxAbs);
    var pct = Math.round(8 + p * 42);
    node.style.backgroundColor = "color-mix(in srgb, var(" + (v < 0 ? "--d-neg" : "--d-pos") + ") " + pct + "%, transparent)";
  }


  function maxAbsOf(i) {
    var m = 0;
    rowsAll().forEach(function (r) { var v = cell(r, i)[0]; if (v !== null && Math.abs(v) > m) { m = Math.abs(v); } });
    return m;
  }

  // --------------------------------------------------------------------------------------------------- the matrix (a risk grid)

  function grid() { var g = $("[data-sm-scroll] drishti-grid"); return g && g.grid; }
  function colIndex(id) { return /^d\d+$/.test(id || "") ? parseInt(id.slice(1), 10) : -1; }
  function dateTip(c) {
    return c.date + " · " + (c.gap ? "no values: " + c.gap : (c.sourceNote || c.source) + (c.scenarioSet ? " · scenario set " + c.scenarioSet : "")
      + (c.k ? " · VaR is rank " + c.k + " of " + c.scenarios + ", ES the mean of the worst " + c.esCount : ""));
  }
  function dateHeader(c) { return short(c.date) + " " + (c.gap ? (c.code || "gap") : c.source); }

  /** A matrix cell as drawn: the value in the mode (or — and · with why), its heat, and V or E on a date's VaR scenario and ES tail. */
  function cellNode(r, i) {
    var cv = cell(r, i), s = el("span", "sm-v");
    if (cv[0] === null) {
      s.textContent = cols()[i].gap || S.mode !== "pnl" || pnl(r, i) !== null ? "—" : "·";
      s.className += " sm-none";
      s.title = cv[1];
      s.setAttribute("aria-label", "no value: " + cv[1]);
    } else {
      s.textContent = fmt(cv[0]);
      heat(s, cv[0], S.__maxes[i]);
      s.title = full(cv[0]);
    }
    var t = S.by !== "trade" && S.outline ? tailOf(i)[r.scenario] : null;
    if (t) {
      var mk = el("b", "mk", t.isVar ? "V" : "E");
      mk.setAttribute("aria-label", t.isVar ? "the VaR scenario of " + cols()[i].date : "in the ES tail of " + cols()[i].date + " (rank " + t.rank + ")");
      s.appendChild(mk);
    }
    return s;
  }

  /** The matrix: a row per scenario day (or trade) in the page's order, a column per business date; the page sorts, the grid draws. */
  function drawMatrix() {
    var head = { field: "__key", id: "__key", header: S.by === "trade" ? "Trade" : "Scenario (historical day)", width: S.by === "trade" ? 190 : 120, pinned: "left", sortable: false,
      valueGetter: function (r) { return S.by === "trade" ? r.trade + (r.counterparty ? " · " + r.counterparty : "") : r.scenario; } };
    var dates = cols().map(function (c, i) {
      return { id: "d" + i, header: dateHeader(c), description: dateTip(c), type: "number", additive: false, sortable: false, width: 84,
        valueGetter: function (r) { return cell(r, i)[0]; },
        format: function (v, r) { return v === null || v === undefined ? (r && (cols()[i].gap || S.mode !== "pnl" || pnl(r, i) !== null) ? "—" : "·") : fmt(v); },
        cellRenderer: function (x) { return cellNode(x.row, i); },
        cellClass: function (v, r) {
          var t = S.by !== "trade" && S.outline ? tailOf(i)[r.scenario] : null;
          return (t ? "sm-tail" + (t.isVar ? " sm-var" : "") : "") + (key(r) === S.selRow && i === S.selCol ? " sm-sel" : "") + (i === S.selCol ? " sm-selcol" : "") || null;
        } };
    });
    R.riskGrid($("[data-sm-scroll]"), { title: "Scenario matrix", label: "The scenario matrix: scroll, or use the arrow keys to move the selection",
      rows: S.view, columns: [head].concat(dates), getRowId: function (r) { return key(r); }, fitRows: 0,
      getRowClass: function (r) { return key(r) === S.selRow ? "sm-selrow" : null; }, getRowData: function (r) { return { key: key(r) }; } });
  }

  /** Each date's figures under the matrix: VaR (and its rank), ES, base PV; by trade the position and its rank. */
  function drawFoot() {
    var L = S.answer.labels || {};
    var defs = S.by === "trade" ? [["total", "Position (this scenario)"], ["rank", "Its rank that day"]]
      : [["var", L.var || "VaR"], ["es", L.es || "ES"], ["basePv", L.basePv || "Base PV"]];
    var rows = defs.map(function (d) { return { id: d[0], label: d[0] === "var" ? d[1] + " (rank)" : d[1] }; });
    var dates = cols().map(function (c, i) {
      return { id: "d" + i, header: dateHeader(c), description: dateTip(c), type: "number", additive: false, sortable: false, width: 84,
        valueGetter: function (r) { return c.gap ? null : c[r.id]; },
        format: function (v, r) {
          if (c.gap) { return "—"; }
          if (r && r.id === "rank") { return v ? "#" + v : "—"; }
          return fmt(v) + (r && r.id === "var" && c.k ? " #" + c.k : "");
        } };
    });
    R.riskGrid($("[data-sm-tfoot]"), { title: "Each date's figures", rows: rows, getRowId: function (r) { return r.id; }, fitRows: 3,
      columns: [{ field: "label", header: "", width: S.by === "trade" ? 190 : 120, pinned: "left", sortable: false }].concat(dates) });
  }

  function drawAll() {
    S.__maxes = cols().map(function (c, i) { return maxAbsOf(i); });
    computeView();
    fit();
    drawMatrix();
    drawFoot();
    fit();                                     // again, now that the figures under the matrix have their height
    var size = $("[data-sm-size]");
    var cut = S.by === "trade" && S.trades && S.trades.truncated && S.trades.total
      ? " · " + Number(S.trades.shown).toLocaleString("en-US") + " of " + Number(S.trades.total).toLocaleString("en-US")
        + " trades shown (the largest by gross size); the totals are over all " + Number(S.trades.total).toLocaleString("en-US") : "";
    size.textContent = S.view.length + (S.by === "trade" ? " trades" : " scenarios") + " × " + cols().length + " dates" + cut + (S.answer.__format === "arrow" ? " · read as Arrow" : "");
    fillPick();
    drawSide();
  }

  // ------------------------------------------------------------------------------------------------------ the selection

  function selectedRow() {
    var all = rowsAll();
    for (var i = 0; i < all.length; i++) { if (key(all[i]) === S.selRow) { return all[i]; } }
    return null;
  }

  /** Scrolls the matrix so the selected row is in view (below the headings, above the bottom). */
  function reveal(k) {
    var g = grid(), sc = $("[data-sm-scroll] .dg-scroll");
    if (!g || !sc) { return; }
    var at = S.view.findIndex(function (r) { return key(r) === k; }), rh = g.model.viewport.rowHeight;
    if (at >= 0 && (at * rh < sc.scrollTop || (at + 1) * rh > sc.scrollTop + sc.clientHeight - 80)) { sc.scrollTop = Math.max(0, at * rh - 60); }
  }

  function select(k, col, scroll) {
    S.selRow = k;
    if (col !== undefined && col !== null && col >= 0) { S.selCol = col; }
    if (S.selCol < 0) { S.selCol = cols().length - 1; }
    var g = grid();
    if (scroll) { reveal(k); }
    if (g) { g.refresh(); }
    var pick = $("[data-sm-pick]");
    if (pick.value !== k) { pick.value = k; }
    drawSide();
  }

  function fillPick() {
    var pick = $("[data-sm-pick]");
    pick.textContent = "";
    var rows = S.view.slice(0, 200);
    if (S.selRow && !rows.some(function (r) { return key(r) === S.selRow; })) { var s = selectedRow(); if (s) { rows.unshift(s); } }
    rows.forEach(function (r) {
      var o = el("option", null, key(r));
      o.value = key(r);
      pick.appendChild(o);
    });
    if (S.selRow) { pick.value = S.selRow; }
  }

  // --------------------------------------------------------------------------------------------------------- the chart

  var WORD = { pnl: "scenario P&L", pv: "scenario PV", change: "change vs day before" };
  var KIND = { line: "line", dots: "line", area: "area", bars: "bar" };

  /** The chart's view: Scenarios (the selected date's column, the scenario days in order) or Across dates (the selected row); by trade
      the rows are trades, so only Across dates. */
  function chartView() { return S.by === "trade" ? "dates" : S.chartView; }

  /** The reference of a date: -VaR (P&L), the PV at VaR = base PV - VaR (PV); none for the change or by trade. */
  function refOf(c) {
    if (S.by === "trade" || S.mode === "change" || !c || c.gap || c.var === null || c.var === undefined) { return null; }
    if (S.mode === "pv") { return c.basePv === null || c.basePv === undefined ? null : c.basePv - c.var; }
    return -c.var;
  }
  function refName() { return S.by === "trade" || S.mode === "change" ? "" : (S.mode === "pv" ? "PV at VaR" : "−VaR"); }

  /** The rows in the order of their scenario days, oldest first (whatever the matrix's sort), kept until the next answer. */
  function chrono() {
    if (!S.__chrono || S.__chrono.of !== S.answer) {
      S.__chrono = { of: S.answer, rows: S.answer.rows.slice().sort(function (a, b) { return a.scenario < b.scenario ? -1 : (a.scenario > b.scenario ? 1 : 0); }) };
    }
    return S.__chrono.rows;
  }

  /** The legend under the chart says what is drawn: the mark of the chart type, the reference and base PV when drawn, the marked points. */
  function drawLegend(o) {
    $("[data-sm-key-row]").className = "sm-key sm-key-" + S.chart;
    $("[data-sm-row-label]").textContent = o.row;
    $("[data-sm-key-ref]").hidden = !o.ref;
    $("[data-sm-ref-label]").textContent = o.ref || "";
    $("[data-sm-key-base]").hidden = !o.base;
    $("[data-sm-key-mark]").hidden = !o.mark;
    $("[data-sm-mark-label]").textContent = o.mark || "";
    $("[data-sm-chart]").setAttribute("aria-label", o.aria);
  }

  /** A drishti-chart of rows {x, v, ref, base, flag}: the series in the chosen type (Line, Dots, Area or Bars), the references as dashed
      lines on every type, the flagged points (beyond VaR, in the tail; the VaR scenario apart) marked by shape too, the selected point
      an annotation; a click on a point selects its row. The y axis keeps zero for a P&L and its change, a PV its own range. */
  function chartOf(box, host, rows, o) {
    var series = [{ field: "v", name: o.name, type: S.chart === "dots" ? "scatter" : S.chart === "bars" ? "bar" : "line", tone: "link" }];
    if (o.base) { series.push({ field: "base", name: "base PV", type: "line", style: "dotted", tone: "muted" }); }
    if (o.ref) { series.push({ field: "ref", name: o.ref, type: "line", style: "dashed", tone: "bad" }); }
    var marks = o.marks || [];
    var notes = [];
    if (o.sel >= 0 && rows[o.sel] && rows[o.sel].v !== null) { notes.push({ type: "point", x: rows[o.sel].x, y: rows[o.sel].v, tone: "accent", label: o.selLabel || "" }); }
    R.riskChart(host, { kind: KIND[S.chart], data: { rows: rows }, id: "x", x: { field: "x", type: "category" }, series: series, marks: marks,
      annotations: notes, y: { format: "compact", zero: S.mode !== "pv" }, legend: "none", markers: S.chart !== "line" || rows.length <= 60, labels: rows.length === 1 },
      { height: chartHeight(box), onPick: function (row) { if (row && o.pick) { o.pick(row); } } });
    box.dataset.chart = S.chart;
    box.dataset.view = chartView();
  }

  function drawChart(r) {
    var box = $("[data-sm-chart]");
    var view = chartView();
    root.querySelectorAll("[data-sm-chart-view]").forEach(function (b) {
      b.setAttribute("aria-pressed", b.dataset.smChartView === view ? "true" : "false");
      if (b.dataset.smChartView === "scenarios") {
        b.disabled = S.by === "trade";
        b.title = b.disabled ? "By trade the rows are trades: the chart is the selected trade across the dates" : "The selected date's scenarios, in the order of their days";
      }
    });
    var host = $("[data-sm-chart] .sm-chart-host");
    if (!host) { box.textContent = ""; host = el("div", "sm-chart-host"); box.appendChild(host); }
    Array.prototype.slice.call(box.querySelectorAll(".sm-note")).forEach(function (n) { n.remove(); });
    host.hidden = false;
    if (view === "scenarios") { drawScenarios(box, host, r); } else { drawDates(box, host, r); }
  }
  function say(box, host, text, attr) {
    host.hidden = true;
    var p = el("p", "sm-note", text);
    if (attr) { p.setAttribute(attr, ""); }
    box.appendChild(p);
    return p;
  }

  /** The selected business date's column: every scenario day in order (oldest first), its value in the mode; the reference, the base PV
      (PV), the tail marked; the selected scenario an annotation; a click on a point selects that scenario day. */
  function drawScenarios(box, host, r) {
    var i = S.selCol >= 0 ? S.selCol : cols().length - 1, c = cols()[i];
    var label = S.answer.label || S.answer.scope || S.target;
    $("[data-sm-chart-title]").textContent = (c ? label + " on " + c.date : label) + ":";
    $("[data-sm-chart-sub]").textContent = WORD[S.mode] + " by scenario day";
    var ref = refOf(c), base = S.mode === "pv" && c && !c.gap && c.basePv !== null && c.basePv !== undefined ? c.basePv : null;
    var tail = tailOf(i);
    drawLegend({ row: "each scenario day", ref: ref !== null ? refName() : "", base: base !== null, mark: c && c.tail ? "in the tail (the larger: the VaR scenario)" : "",
      aria: "The scenarios of " + (c ? c.date : "the date") + " in the order of their days, as " + S.chart + (ref !== null ? ", against " + refName() : "") });
    if (!c) { say(box, host, "No dates in this matrix."); return; }
    if (c.gap) { say(box, host, "No values on " + c.date + ": " + c.gap + ". Pick another date in the matrix."); return; }
    var rows = chrono().map(function (row) {
      var t = tail[row.scenario];
      return { x: row.scenario, v: cell(row, i)[0], ref: ref, base: base, flag: t ? (t.isVar ? 2 : 1) : 0 };
    });
    if (!rows.some(function (x) { return x.v !== null; })) {
      say(box, host, "No " + WORD[S.mode] + " on " + c.date + (S.mode === "change" ? " (no date before it in this matrix)" : "") + ".");
      return;
    }
    var sel = rows.findIndex(function (x) { return x.x === S.selRow; });
    chartOf(box, host, rows, { name: WORD[S.mode], ref: ref !== null ? refName() : "", base: base !== null, sel: sel, selLabel: S.selRow || "",
      marks: c.tail ? [{ when: "flag >= 1", field: "v", tone: "warn", name: "in the tail" }, { when: "flag == 2", field: "v", tone: "bad", name: "the VaR scenario" }] : [],
      pick: function (row) { select(row.x, null, true); } });
  }

  /** The selected row across the business dates against the reference per date; one date open: the chart says it needs two. */
  function drawDates(box, host, r) {
    $("[data-sm-chart-title]").textContent = r ? (S.by === "trade" ? "Trade " + r.trade + " under " + S.trades.scenario : "Scenario " + r.scenario) : "Select a scenario";
    $("[data-sm-chart-sub]").textContent = "across the dates";
    var cs = cols(), name = refName();
    var refs = cs.map(refOf);
    var anyRef = refs.some(function (v) { return v !== null; });
    drawLegend({ row: S.by === "trade" ? "this trade" : "this scenario", ref: anyRef ? name : "", base: false, mark: anyRef ? "beyond VaR" : "",
      aria: "The selected " + (S.by === "trade" ? "trade" : "scenario") + " across the business dates as " + S.chart + (anyRef ? ", against " + name : "") });
    if (!r || !cs.length) { say(box, host, "Select a row of the matrix to chart it across the dates."); return; }
    var rows = cs.map(function (c, i) {
      var v = cell(r, i)[0];
      return { x: c.date, v: v, ref: refs[i], flag: v !== null && refs[i] !== null && v <= refs[i] ? 1 : 0, col: i };
    });
    if (!rows.some(function (x) { return x.v !== null || x.ref !== null; })) { say(box, host, "No values for this row on these dates."); return; }
    chartOf(box, host, rows, { name: S.by === "trade" ? "this trade" : "this scenario", ref: anyRef ? name : "", sel: S.selCol, selLabel: "",
      marks: anyRef ? [{ when: "flag == 1", field: "v", tone: "bad", name: "beyond VaR" }] : [],
      pick: function (row) { select(S.selRow, row.col, false); } });
    if (cs.length === 1) {
      var asked = (S.by === "trade" ? S.trades : S.answer).asked || S.days;
      var one = el("p", "sm-note sm-one", asked > 1 ? "Only 1 date is open; the chart needs two or more (" + asked + " asked)."
        : "One date asked; the chart needs two or more.");
      one.setAttribute("data-sm-one", "");
      box.appendChild(one);
    }
  }

  function daysInTail(r) {
    var n = 0, of = 0;
    cols().forEach(function (c, i) { if (!c.gap && pnl(r, i) !== null) { of++; if (tailOf(i)[r.scenario]) { n++; } } });
    return [n, of];
  }
  function kv(dl, k, v, href) {
    dl.appendChild(el("dt", null, k));
    var dd = el("dd", "mono");
    if (href) { var a = el("a", null, v); a.href = href; dd.appendChild(a); } else { dd.textContent = v; }
    dl.appendChild(dd);
  }

  function drawSide() {
    var r = selectedRow();
    drawChart(r);
    var dl = $("[data-sm-kv]");
    dl.textContent = "";
    var glance = $("[data-sm-glance]");
    glance.textContent = "";
    if (!r) { return; }
    var i = S.selCol >= 0 ? S.selCol : cols().length - 1;
    var c = cols()[i];
    if (S.by !== "trade") {
      var dt = daysInTail(r);
      glance.textContent = "in the tail on " + dt[0] + " of " + dt[1] + " dates";
    }
    kv(dl, "Selected", (S.by === "trade" ? r.trade : r.scenario) + " on " + c.date);
    if (c.gap) {
      kv(dl, "No values", c.gap);
    } else {
      var v = pnl(r, i);
      var rk = S.by === "trade" ? null : rankOf(r.scenario, i);
      kv(dl, "P&L", v === null ? "— (not in this date's scenario window)" : full(v) + (rk ? " (" + ordinal(rk[0]) + " worst of " + rk[1] + ")" : ""));
      if (S.by !== "trade") {
        if (c.basePv !== null && c.basePv !== undefined && v !== null) { kv(dl, "Scenario PV", full(c.basePv + v) + " · base " + full(c.basePv)); }
        else { kv(dl, "Scenario PV", "— (no base PV for this date)"); }
        var t = tailOf(i)[r.scenario];
        kv(dl, "Tail that day", t ? (t.isVar ? "the VaR scenario (rank " + t.rank + ")" : "in the ES tail (rank " + t.rank + " of the worst " + c.esCount + ")") : "no");
        kv(dl, "Days in tail", daysInTail(r)[0] + " of " + daysInTail(r)[1]);
      } else if (c.total !== undefined) {
        kv(dl, "Position", full(c.total) + (c.rank ? " (rank " + c.rank + " that day)" : ""));
      }
      if (c.fx) { kv(dl, "FX used", c.fx); }
      if (c.valueType) { kv(dl, "Input", c.valueType === "PV" ? "scenario PVs (P&L = PV − BASE_PV)" : c.valueType === "PNL" ? "P&L vectors" : "P&L vectors and scenario PVs"); }
    }
    var src = (c.source || "") + (c.batch ? " · batch " + c.batch : "") + (c.dataVersion ? " · " + c.dataVersion : "");
    kv(dl, "Source", src || "—", c.link ? "/asof?d=" + encodeURIComponent(c.date) + "&next=" + encodeURIComponent(c.link) : null);
    if (c.sourceNote) { kv(dl, "Read", c.sourceNote); }
  }

  function ordinal(n) {
    var s = ["th", "st", "nd", "rd"], v = n % 100;
    return n + (s[(v - 20) % 10] || s[v] || s[0]);
  }

  // -------------------------------------------------------------------------------------------------------- the controls

  function pressed(sel, attr, value) {
    root.querySelectorAll(sel).forEach(function (b) { b.setAttribute("aria-pressed", b.getAttribute(attr) === value ? "true" : "false"); });
  }

  function exports() {
    var extra = S.by === "trade" ? { by: "trade", scenario: S.trades && S.trades.scenario } : {};
    var q = params(extra).toString();
    $("[data-sm-csv]").href = "/api/smat/matrix.csv?" + q;
    $("[data-sm-arrow]").href = "/api/smat/matrix.arrow?" + q;
  }

  function fillSort() {
    var sel = $("[data-sm-sort]");
    sel.textContent = "";
    var o = el("option", null, "worst on the newest date"); o.value = "worst-last"; sel.appendChild(o);
    cols().slice().reverse().forEach(function (c) { var x = el("option", null, "worst on " + c.date); x.value = c.date; sel.appendChild(x); });
    var d = el("option", null, S.by === "trade" ? "by trade" : "by scenario day (newest first)"); d.value = "day"; sel.appendChild(d);
    sel.value = S.sort;
  }

  function meta(a) {
    var parts = [a.kind || "", a.label && a.label !== a.scope ? a.label : "", a.entity && a.entity !== a.scope ? a.entity : ""].filter(Boolean);
    var cs = a.dates || [];
    var set = cs.filter(function (c) { return c.scenarioSet; }).map(function (c) { return c.scenarioSet; });
    var sources = {};
    cs.forEach(function (c) { sources[c.source] = (sources[c.source] || 0) + 1; });
    return parts.join(" · ") + (set.length ? " · scenario set " + set[set.length - 1] : "") + " · last " + cs.length + " business dates"
      + (cs.length ? " to " + cs[cs.length - 1].date : "") + " · " + (a.currency || "USD") + " · "
      + Object.keys(sources).map(function (k) { return sources[k] + " " + k; }).join(", ");
  }

  function start() {
    $("[data-sm-meta]").textContent = "loading…";
    load().then(function (a) {
      S.answer = a;
      S.rankCache = {};
      $("[data-sm-title]").textContent = a.scope || S.target;
      $("[data-sm-meta]").textContent = meta(a);
      $("[data-sm-tail-label]").textContent = "(" + (a.labels && a.labels.var ? a.labels.var.split(" ").slice(0, 2).join(" ") : "VaR") + ", ES)";
      var gaps = (a.dates || []).filter(function (c) { return c.gap; });
      notice((a.note ? a.note + ". " : "") + (gaps.length ? gaps.length + " of " + a.dates.length + " dates have no values: their columns say why (" + gaps.map(function (c) { return c.date + " " + (c.code || ""); }).join(", ") + ")." : ""));
      var byOk = a.kind === "position" && !a.supplied;
      var bt = root.querySelector("[data-sm-by='trade']");
      bt.disabled = !byOk;
      bt.title = byOk ? "The position's trades under the selected scenario, across the dates" : "By trade is for a position whose trades are known (this is " + (a.supplied ? "a position its source sends whole" : "a " + a.kind) + ")";
      var last = (a.dates || []).length - 1;
      var newest = last >= 0 ? a.dates[last] : null;
      S.selCol = last;
      fillSort();
      computeView();
      S.selRow = newest && newest.varScenario ? newest.varScenario : (S.view[0] ? key(S.view[0]) : null);
      drawAll();
      exports();
    }, function (e) {
      $("[data-sm-meta]").textContent = "";
      notice((e.code ? e.code + " " : "") + e.message);
    });
  }

  function byTrade(on) {
    if (!on) { S.by = "scenario"; S.trades = null; pressed("[data-sm-by]", "data-sm-by", "scenario"); S.sort = "worst-last"; fillSort(); drawAll(); exports(); return; }
    var scen = S.by === "trade" ? S.trades.scenario : S.selRow;
    $("[data-sm-meta]").textContent = "loading the trades…";
    load({ by: "trade", scenario: scen }).then(function (t) {
      S.by = "trade";
      S.trades = t;
      pressed("[data-sm-by]", "data-sm-by", "trade");
      $("[data-sm-meta]").textContent = meta(S.answer) + " · by trade under " + t.scenario;
      S.sort = "worst-last";
      fillSort();
      computeView();
      S.selRow = S.view[0] ? key(S.view[0]) : null;
      drawAll();
      exports();
    }, function (e) { notice((e.code ? e.code + " " : "") + e.message); $("[data-sm-meta]").textContent = meta(S.answer); });
  }

  root.querySelectorAll("[data-sm-mode]").forEach(function (b) {
    b.addEventListener("click", function () { S.mode = b.dataset.smMode; pressed("[data-sm-mode]", "data-sm-mode", S.mode); if (S.answer) { drawAll(); } });
  });
  root.querySelectorAll("[data-sm-chart-type]").forEach(function (b) {
    b.addEventListener("click", function () {
      S.chart = b.dataset.smChartType;
      try { window.localStorage.setItem(CHART_KEY, S.chart); } catch (e) { /* storage off: this page only */ }
      pressed("[data-sm-chart-type]", "data-sm-chart-type", S.chart);
      if (S.answer) { drawChart(selectedRow()); }
    });
  });
  pressed("[data-sm-chart-type]", "data-sm-chart-type", S.chart);
  root.querySelectorAll("[data-sm-chart-view]").forEach(function (b) {
    b.addEventListener("click", function () {
      if (b.disabled) { return; }
      S.chartView = b.dataset.smChartView;
      try { window.localStorage.setItem(VIEW_KEY, S.chartView); } catch (e) { /* storage off: this page only */ }
      if (S.answer) { drawChart(selectedRow()); } else { pressed("[data-sm-chart-view]", "data-sm-chart-view", S.chartView); }
    });
  });
  pressed("[data-sm-chart-view]", "data-sm-chart-view", S.chartView);
  root.querySelectorAll("[data-sm-by]").forEach(function (b) {
    b.addEventListener("click", function () { if (S.answer && !b.disabled) { byTrade(b.dataset.smBy === "trade"); } });
  });
  $("[data-sm-outline]").addEventListener("click", function (ev) {
    S.outline = !S.outline;
    ev.currentTarget.setAttribute("aria-pressed", String(S.outline));
    ev.currentTarget.classList.toggle("on", S.outline);
    if (S.answer) { drawMatrix(); }
  });
  $("[data-sm-tailonly]").addEventListener("click", function (ev) {
    S.tailOnly = !S.tailOnly;
    ev.currentTarget.setAttribute("aria-pressed", String(S.tailOnly));
    ev.currentTarget.classList.toggle("on", S.tailOnly);
    if (S.answer) { drawAll(); }
  });
  $("[data-sm-sort]").addEventListener("change", function (ev) { S.sort = ev.target.value; if (S.answer) { drawAll(); } });
  $("[data-sm-pick]").addEventListener("change", function (ev) { select(ev.target.value, null, true); });
  // the grid's own events: a click on a cell selects its row and date; the arrow keys move the grid's focus, and the selection with it
  var scroll = $("[data-sm-scroll]");
  function fromGrid(rowIndex, colId) {
    var g = grid(), x = g && g.model.rowAt(rowIndex);
    if (!x || x.kind !== "leaf" || !x.row) { return; }
    var i = colIndex(colId);
    if (key(x.row) !== S.selRow || (i >= 0 && i !== S.selCol)) { select(key(x.row), i >= 0 ? i : null, false); }
  }
  scroll.addEventListener("drishti:cell-clicked", function (ev) { var d = ev.detail || {}; fromGrid(d.row, d.columnId); });
  scroll.addEventListener("drishti:focus-changed", function (ev) {
    var d = ev.detail || {}, g = grid(), c = g && g.model.shownColumns()[d.col];
    fromGrid(d.row, c ? c.id : null);
  });
  // the page fills the window: the matrix in the height left below its controls, the chart about half of it
  function viewportLeft(elem, below) {
    var top = elem.getBoundingClientRect().top + window.scrollY;
    return Math.max(MIN_PX, window.innerHeight - top - below);
  }

  function chartHeight(box) {
    var w = box.clientWidth || 420;
    return Math.round(Math.max(220, Math.min(viewportLeft(box, 0) * 0.55, w * 0.9)));
  }

  function fit() {
    var legend = root.querySelector(".sm-legend"), foot = $("[data-sm-tfoot]");
    scroll.style.height = viewportLeft(scroll, (legend ? legend.offsetHeight : 0) + (foot ? foot.offsetHeight : 0) + FIT_GAP) + "px";
  }

  var MIN_PX = 320, FIT_GAP = 28;   // the smallest matrix height; the room kept under the legend
  var resizing = 0;
  window.addEventListener("resize", function () {
    clearTimeout(resizing);
    resizing = setTimeout(function () { fit(); if (S.answer) { drawSide(); } }, 80);
  });
  fit();
  start();
})();
