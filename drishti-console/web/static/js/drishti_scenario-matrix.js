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
   tail, outlined and marked V / E so it is not colour only), the sort, the tail-only filter, the chart (Line, Dots, Area or Bars): the
   selected date's scenarios in the order of their days (Scenarios; a click selects one) or the selected row across the dates (Across
   dates), against the reference (-VaR in P&L, PV at VaR = base PV - VaR and the base PV in PV, none for the change; the legend follows).
   The rows are virtualised (only those in view are in the table), so 509 rows by 60 dates scroll as fast as 20. A big matrix comes as
   Arrow (drishti_table.js), a small one as JSON. By trade (a position whose trades are known) asks again with by=trade and the selected
   scenario. Every request goes to this console's own host. */
(function () {
  "use strict";
  var root = document.querySelector("[data-smat]");
  if (!root || !root.dataset.target) { return; }
  var $ = function (sel) { return root.querySelector(sel); };
  var ROW_H = 26;                       // the table's row height (drishti_scenario-matrix.css .sm-table td)
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
    if (v === null || v === undefined || isNaN(v)) { return "—"; }
    var a = Math.abs(v), s = v < 0 ? "−" : (v > 0 ? "+" : "");
    if (a >= 1e9) { return s + (a / 1e9).toFixed(2) + "bn"; }
    if (a >= 1e6) { return s + (a / 1e6).toFixed(2) + "m"; }
    if (a >= 1e4) { return s + (a / 1e3).toFixed(1) + "k"; }
    return s + a.toLocaleString(undefined, { maximumFractionDigits: 2 });
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

  function heat(td, v, maxAbs) {
    if (v === null || !maxAbs) { return; }
    var p = Math.min(1, Math.abs(v) / maxAbs);
    var pct = Math.round(8 + p * 42);
    td.style.backgroundColor = "color-mix(in srgb, var(" + (v < 0 ? "--d-neg" : "--d-pos") + ") " + pct + "%, transparent)";
  }

  function maxAbsOf(i) {
    var m = 0;
    rowsAll().forEach(function (r) { var v = cell(r, i)[0]; if (v !== null && Math.abs(v) > m) { m = Math.abs(v); } });
    return m;
  }

  function drawHead() {
    var thead = $("[data-sm-thead]");
    thead.textContent = "";
    var tr = el("tr");
    var th0 = el("th", "sm-first", S.by === "trade" ? "Trade" : "Scenario (historical day)");
    th0.scope = "col";
    tr.appendChild(th0);
    cols().forEach(function (c, i) {
      var th = el("th", "sm-date" + (c.gap ? " sm-gapcol" : "") + (i === S.selCol ? " sm-selcol" : ""));
      th.scope = "col";
      th.appendChild(el("span", "sm-d mono", short(c.date)));
      var src = el("span", "sm-src sm-src-" + (c.source || "x"), c.gap ? (c.code || "gap") : c.source);
      th.appendChild(src);
      var tip = c.date + " · " + (c.gap ? "no values: " + c.gap : (c.sourceNote || c.source) + (c.scenarioSet ? " · scenario set " + c.scenarioSet : "")
        + (c.k ? " · VaR is rank " + c.k + " of " + c.scenarios + ", ES the mean of the worst " + c.esCount : ""));
      th.title = tip;
      th.setAttribute("aria-label", tip);
      tr.appendChild(th);
    });
    thead.appendChild(tr);
  }

  function drawFoot() {
    var tfoot = $("[data-sm-tfoot]");
    tfoot.textContent = "";
    var L = S.answer.labels || {};
    var defs = S.by === "trade" ? [["total", "Position (this scenario)"], ["rank", "Its rank that day"]]
      : [["var", L.var || "VaR"], ["es", L.es || "ES"], ["basePv", L.basePv || "Base PV"]];
    defs.forEach(function (d) {
      var tr = el("tr", "sm-sum");
      var th = el("th", "sm-first", d[0] === "var" ? d[1] + " (rank)" : d[1]);
      th.scope = "row";
      tr.appendChild(th);
      cols().forEach(function (c) {
        var v = c[d[0]];
        var td = el("td", "mono", c.gap ? "—" : d[0] === "rank" ? (v ? "#" + v : "—") : fmt(v));
        if (d[0] === "var" && !c.gap && c.k) { td.appendChild(el("span", "sm-k", " #" + c.k)); }
        if (c.gap) { td.title = c.gap; }
        else if (v !== null && v !== undefined && d[0] !== "rank") { td.title = full(v); }
        tr.appendChild(td);
      });
      tfoot.appendChild(tr);
    });
  }

  function drawBody() {
    var tbody = $("[data-sm-tbody]");
    var box = $("[data-sm-scroll]");
    var n = S.view.length;
    var top = box.scrollTop;
    var h = box.clientHeight || 520;
    var first = Math.max(0, Math.floor(top / ROW_H) - 8);
    var last = Math.min(n, Math.ceil((top + h) / ROW_H) + 8);
    tbody.textContent = "";
    var nc = cols().length;
    var maxes = S.__maxes;
    if (first > 0) { var pad = el("tr", "sm-pad"); var pd = el("td"); pd.colSpan = nc + 1; pd.style.height = (first * ROW_H) + "px"; pad.appendChild(pd); tbody.appendChild(pad); }
    for (var k = first; k < last; k++) {
      var r = S.view[k];
      var tr = el("tr", key(r) === S.selRow ? "sm-selrow" : "");
      tr.dataset.key = key(r);
      var th = el("th", "sm-first mono", S.by === "trade" ? (r.trade + (r.counterparty ? " · " + r.counterparty : "")) : r.scenario);
      th.scope = "row";
      th.tabIndex = -1;
      tr.appendChild(th);
      var tail = S.by === "trade" ? null : r.scenario;
      for (var i = 0; i < nc; i++) {
        var cv = cell(r, i);
        var td = el("td", "mono");
        td.dataset.col = String(i);
        if (cv[0] === null) {
          var inWin = cols()[i].gap || S.mode !== "pnl" || pnl(r, i) !== null;
          td.textContent = inWin ? "—" : "·";
          td.className += " sm-none";
          td.title = cv[1];
          td.setAttribute("aria-label", "no value: " + cv[1]);
        } else {
          td.textContent = fmt(cv[0]);
          heat(td, cv[0], maxes[i]);
          td.title = full(cv[0]);
        }
        if (tail && S.outline) {
          var t = tailOf(i)[tail];
          if (t) {
            td.className += " sm-tail" + (t.isVar ? " sm-var" : "");
            var mk = el("b", "mk", t.isVar ? "V" : "E");
            mk.setAttribute("aria-label", t.isVar ? "the VaR scenario of " + cols()[i].date : "in the ES tail of " + cols()[i].date + " (rank " + t.rank + ")");
            td.appendChild(mk);
          }
        }
        if (key(r) === S.selRow && i === S.selCol) { td.className += " sm-sel"; }
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    }
    if (last < n) { var pad2 = el("tr", "sm-pad"); var pd2 = el("td"); pd2.colSpan = nc + 1; pd2.style.height = ((n - last) * ROW_H) + "px"; pad2.appendChild(pd2); tbody.appendChild(pad2); }
  }

  function drawAll() {
    S.__maxes = cols().map(function (c, i) { return maxAbsOf(i); });
    computeView();
    drawHead();
    drawFoot();
    fit();
    drawBody();
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

  function select(k, col, scroll) {
    S.selRow = k;
    if (col !== undefined && col !== null && col >= 0) { S.selCol = col; }
    if (S.selCol < 0) { S.selCol = cols().length - 1; }
    if (scroll) {
      var at = S.view.findIndex(function (r) { return key(r) === k; });
      var box = $("[data-sm-scroll]");
      if (at >= 0 && (at * ROW_H < box.scrollTop || (at + 1) * ROW_H > box.scrollTop + box.clientHeight - 80)) { box.scrollTop = Math.max(0, at * ROW_H - 60); }
    }
    drawHead();
    drawBody();
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

  var NS = "http://www.w3.org/2000/svg";
  function svg(tag, attrs) {
    var e = document.createElementNS(NS, tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }

  var CW = 420, CH = 220, CL = 64, CR = 12, CT = 14, CB = 26;   // the chart's frame, in viewBox units
  var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  var WORD = { pnl: "scenario P&L", pv: "scenario PV", change: "change vs day before" };

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

  function newSvg(cls) { return svg("svg", { viewBox: "0 0 " + CW + " " + CH, "class": "sm-svg " + cls, "aria-hidden": "true" }); }

  /** The y scale: zero on the axis for a P&L and its change, a PV's own range otherwise (a band around one value); the grid drawn. */
  function yScale(s, all, signed) {
    var lo = all[0], hi = all[0];
    all.forEach(function (v) { if (v < lo) { lo = v; } if (v > hi) { hi = v; } });
    if (signed) { lo = Math.min(lo, 0); hi = Math.max(hi, 0); }
    var flat = hi === lo;
    var pad = flat ? Math.max(Math.abs(hi) * 0.05, 1) : (hi - lo) * 0.08;
    lo -= pad; hi += pad;
    var inner = flat ? pad / 2 : pad;
    var y = function (v) { return CT + (hi - v) * (CH - CT - CB) / (hi - lo); };
    var ticks = [hi - inner, (hi + lo) / 2, lo + inner];
    var big = Math.max(Math.abs(ticks[0]), Math.abs(ticks[2]));
    var unit = big >= 1e9 ? 1e9 : big >= 1e6 ? 1e6 : big >= 1e4 ? 1e3 : 1, sfx = { 1e9: "bn", 1e6: "m", 1e3: "k", 1: "" }[unit];
    var gap = (ticks[0] - ticks[2]) / 2 / unit;                   // the tick spacing in the unit: enough decimals to tell the ticks apart
    var dec = Math.min(6, Math.max(unit === 1 ? 0 : 1, gap > 0 ? Math.ceil(-Math.log10(gap)) + 1 : 2));
    ticks.forEach(function (v) {
      s.appendChild(svg("line", { x1: CL, x2: CW - CR, y1: y(v), y2: y(v), "class": "sm-grid-line" }));
      var t = svg("text", { x: CL - 5, y: y(v) + 4, "text-anchor": "end", "class": "sm-axis" });
      var a = Math.abs(v) / unit;
      t.textContent = Math.abs(v) < 1e-9 * Math.max(1, big) ? "0" : (v < 0 ? "−" : "") + a.toFixed(dec) + sfx;
      s.appendChild(t);
    });
    if (signed) { s.appendChild(svg("line", { x1: CL, x2: CW - CR, y1: y(0), y2: y(0), "class": "sm-zero" })); }
    return { y: y, floor: signed ? 0 : lo };
  }

  function xScale(n) {
    var plotW = CW - CL - CR;
    var inset = n === 1 ? plotW / 2 : Math.max(4, Math.min(20, plotW / (2 * n)));
    var step = n > 1 ? (plotW - 2 * inset) / (n - 1) : 0;
    return { x: function (i) { return n === 1 ? CL + plotW / 2 : CL + inset + i * step; }, step: step };
  }

  /** The x labels: each centred under its point (start / end at the edges). */
  function placeLabels(s, ticks, x, sel) {
    ticks.forEach(function (tk) {
      var cx = x(tk[0]), anchor = cx - 16 < 0 ? "start" : (cx + 16 > CW ? "end" : "middle");
      var t = svg("text", { x: cx.toFixed(1), y: CH - 8, "text-anchor": anchor, "class": "sm-axis sm-xlabel" + (tk[0] === sel ? " sm-xlabel-sel" : "") });
      t.textContent = tk[1];
      s.appendChild(t);
    });
  }

  /** Day ticks (MM-DD): as many as fit, the last always. */
  function dayTicks(days, x) {
    var n = days.length, room = 46, span = n > 1 ? x(n - 1) - x(0) : 0;
    var every = n > 1 ? Math.max(1, Math.ceil((n - 1) / Math.max(1, Math.floor(span / room)))) : 1;
    var at = [];
    for (var i = 0; i < n; i += every) { at.push(i); }
    if (at[at.length - 1] !== n - 1) {
      if (at.length > 1 && x(n - 1) - x(at[at.length - 1]) < room) { at.pop(); }
      at.push(n - 1);
    }
    return at.map(function (k) { return [k, short(days[k])]; });
  }

  /** Month ticks for a long run of days (Jan 25, Apr 25, ...; years alone when months do not fit); day ticks for a short one. */
  function monthTicks(days, x) {
    var firsts = [];
    days.forEach(function (d, i) { var m = d.slice(0, 7); if (!firsts.length || firsts[firsts.length - 1][1] !== m) { firsts.push([i, m]); } });
    if (firsts.length < 3) { return dayTicks(days, x); }
    var fit = Math.max(2, Math.floor((CW - CL - CR) / 46));
    var every = [1, 2, 3, 6, 12].filter(function (e) { return firsts.length / e <= fit; })[0] || 12;
    var out = firsts.filter(function (f) { return (parseInt(f[1].slice(5, 7), 10) - 1) % every === 0; });
    if (out.length && out[0][0] === 0 && out.length > 1 && x(out[1][0]) - x(0) < 40) { out.shift(); }
    return out.map(function (f) {
      var mo = parseInt(f[1].slice(5, 7), 10) - 1;
      return [f[0], every === 12 ? f[1].slice(0, 4) : MONTHS[mo] + " " + f[1].slice(2, 4)];
    });
  }

  /** One series in the chosen type: Line, Dots, Area (to zero for a P&L or a change, to the floor of the axis for a PV) or Bars; the
      references drawn on every type; a flagged point (beyond VaR, or in the tail) a diamond, not colour alone; the selected point larger. */
  function series(s, pts, X, Y, o) {
    var x = X.x, y = Y.y;
    function segments(values) {
      var out = [], seg = [];
      values.forEach(function (v, i) { if (v === null) { if (seg.length) { out.push(seg); } seg = []; } else { seg.push(i); } });
      if (seg.length) { out.push(seg); }
      return out;
    }
    function poly(seg, values, cls) {
      return svg("polyline", { points: seg.map(function (i) { return x(i).toFixed(1) + "," + y(values[i]).toFixed(1); }).join(" "), "class": cls });
    }
    function refs() {
      o.refs.forEach(function (rf) {
        segments(rf.values).forEach(function (seg) {
          if (seg.length > 1) { s.appendChild(poly(seg, rf.values, rf.cls)); return; }
          var i = seg[0], half = Math.max(4, X.step / 2);           // a reference alone between gaps: a short dash at its point
          s.appendChild(svg("line", { x1: x(i) - half, x2: x(i) + half, y1: y(rf.values[i]), y2: y(rf.values[i]), "class": rf.cls }));
        });
      });
    }
    function mark(i, rad) {
      var cx = x(i), cy = y(pts[i]), sel = i === o.sel, flag = o.flag(i), node;
      if (flag) {
        var d = rad + (flag === "var" ? 3 : 2) + (sel ? 1 : 0);
        node = svg("path", { d: "M" + cx.toFixed(1) + "," + (cy - d).toFixed(1) + "L" + (cx + d).toFixed(1) + "," + cy.toFixed(1) + "L" + cx.toFixed(1) + ","
          + (cy + d).toFixed(1) + "L" + (cx - d).toFixed(1) + "," + cy.toFixed(1) + "Z", "class": "sm-dot sm-beyond" + (flag === "var" ? " sm-varpt" : "") + (sel ? " sm-dot-sel" : "") });
      } else {
        node = svg("circle", { cx: cx.toFixed(1), cy: cy.toFixed(1), r: sel ? rad + 2 : rad, "class": "sm-dot" + (sel ? " sm-dot-sel" : "") });
      }
      node.setAttribute("data-k", String(i));
      var t = svg("title", {}); t.textContent = o.tip(i); node.appendChild(t);
      return node;
    }
    if (o.sel >= 0 && pts.length > 1) { s.appendChild(svg("line", { x1: x(o.sel), x2: x(o.sel), y1: CT, y2: CH - CB, "class": "sm-selguide" })); }
    if (S.chart === "bars") {
      var bw = Math.max(0.6, Math.min(28, X.step * (X.step > 3 ? 0.7 : 0.85)));
      pts.forEach(function (v, i) {
        if (v === null) { return; }
        var y0 = y(Y.floor), y1 = y(v);
        var b = svg("rect", { x: (x(i) - bw / 2).toFixed(2), y: Math.min(y0, y1).toFixed(1), width: bw.toFixed(2), height: Math.max(1, Math.abs(y1 - y0)).toFixed(1),
          "class": "sm-bar" + (o.flag(i) ? " sm-beyond" : "") + (i === o.sel ? " sm-bar-sel" : "") });
        b.setAttribute("data-k", String(i));
        s.appendChild(b);
      });
      refs();
      pts.forEach(function (v, i) { if (v !== null && (o.flag(i) || i === o.sel)) { s.appendChild(mark(i, o.rad)); } });
      return;
    }
    if (S.chart === "area") {
      segments(pts).forEach(function (seg) {
        if (seg.length < 2) { return; }
        s.appendChild(svg("path", { d: "M" + x(seg[0]).toFixed(1) + "," + y(Y.floor).toFixed(1)
          + seg.map(function (i) { return "L" + x(i).toFixed(1) + "," + y(pts[i]).toFixed(1); }).join("")
          + "L" + x(seg[seg.length - 1]).toFixed(1) + "," + y(Y.floor).toFixed(1) + "Z", "class": "sm-area" }));
      });
    }
    if (S.chart !== "dots") { segments(pts).forEach(function (seg) { if (seg.length > 1) { s.appendChild(poly(seg, pts, "sm-line")); } }); }
    refs();                                                       // over the line: a reference inside a dense run stays visible
    var every = S.chart === "dots" || o.allDots;
    pts.forEach(function (v, i) {
      if (v !== null && (every || o.flag(i) || i === o.sel)) { s.appendChild(mark(i, S.chart === "dots" ? o.dotRad : o.rad)); }
    });
  }

  function drawChart(r) {
    var box = $("[data-sm-chart]");
    box.textContent = "";
    CH = chartHeight(box);
    var view = chartView();
    root.querySelectorAll("[data-sm-chart-view]").forEach(function (b) {
      b.setAttribute("aria-pressed", b.dataset.smChartView === view ? "true" : "false");
      if (b.dataset.smChartView === "scenarios") {
        b.disabled = S.by === "trade";
        b.title = b.disabled ? "By trade the rows are trades: the chart is the selected trade across the dates" : "The selected date's scenarios, in the order of their days";
      }
    });
    if (view === "scenarios") { drawScenarios(box, r); } else { drawDates(box, r); }
  }

  /** The selected business date's column: every scenario day in order (oldest first), its value in the mode; the reference, the base PV
      (PV), the tail marked; the selected scenario larger on a dotted guide; a click on the chart selects the nearest scenario day. */
  function drawScenarios(box, r) {
    var i = S.selCol >= 0 ? S.selCol : cols().length - 1, c = cols()[i];
    var label = S.answer.label || S.answer.scope || S.target;
    $("[data-sm-chart-title]").textContent = (c ? label + " on " + c.date : label) + ":";
    $("[data-sm-chart-sub]").textContent = WORD[S.mode] + " by scenario day";
    var ref = refOf(c), base = S.mode === "pv" && c && !c.gap && c.basePv !== null && c.basePv !== undefined ? c.basePv : null;
    var tail = tailOf(i);
    drawLegend({ row: "each scenario day", ref: ref !== null ? refName() : "", base: base !== null, mark: c && c.tail ? "in the tail (the larger: the VaR scenario)" : "",
      aria: "The scenarios of " + (c ? c.date : "the date") + " in the order of their days, as " + S.chart + (ref !== null ? ", against " + refName() : "") });
    if (!c) { box.appendChild(el("p", "sm-note", "No dates in this matrix.")); return; }
    if (c.gap) { box.appendChild(el("p", "sm-note", "No values on " + c.date + ": " + c.gap + ". Pick another date in the matrix.")); return; }
    var rows = chrono();
    var pts = rows.map(function (row) { return cell(row, i)[0]; });
    var all = pts.filter(function (v) { return v !== null; });
    if (!all.length) { box.appendChild(el("p", "sm-note", "No " + WORD[S.mode] + " on " + c.date + (S.mode === "change" ? " (no date before it in this matrix)" : "") + ".")); return; }
    if (ref !== null) { all.push(ref); }
    if (base !== null) { all.push(base); }
    var s = newSvg("sm-svg-scen sm-svg-" + S.chart);
    var n = rows.length, X = xScale(n), Y = yScale(s, all, S.mode !== "pv");
    var hit = svg("rect", { x: CL, y: CT, width: CW - CL - CR, height: CH - CT - CB, "class": "sm-hit" });
    var ht = svg("title", {}); ht.textContent = "Click to select the nearest scenario day"; hit.appendChild(ht);
    s.appendChild(hit);
    placeLabels(s, monthTicks(rows.map(function (row) { return row.scenario; }), X.x), X.x, -1);
    var sel = rows.findIndex(function (row) { return row.scenario === S.selRow; });
    var refs = [];
    if (base !== null) { refs.push({ cls: "sm-baseline", values: pts.map(function () { return base; }) }); }
    if (ref !== null) { refs.push({ cls: "sm-varline", values: pts.map(function () { return ref; }) }); }
    series(s, pts, X, Y, {
      sel: sel, rad: 2.6, dotRad: n > 120 ? 1.7 : 2.8, allDots: n <= 60, refs: refs,
      flag: function (k) { var t = tail[rows[k].scenario]; return t ? (t.isVar ? "var" : "tail") : ""; },
      tip: function (k) {
        var t = tail[rows[k].scenario];
        return rows[k].scenario + ": " + full(pts[k]) + (t ? " · rank " + t.rank + (t.isVar ? ", the VaR scenario" : ", in the ES tail") : "");
      }
    });
    s.addEventListener("click", function (ev) {
      var k = ev.target.getAttribute && ev.target.getAttribute("data-k");
      if (k === null || k === undefined) {
        var m = s.getScreenCTM();
        if (!m) { return; }
        var p = s.createSVGPoint(); p.x = ev.clientX; p.y = ev.clientY;
        var vx = p.matrixTransform(m.inverse()).x;
        k = n === 1 ? 0 : Math.round((vx - X.x(0)) / X.step);
      }
      k = Math.max(0, Math.min(n - 1, parseInt(k, 10)));
      if (rows[k]) { select(rows[k].scenario, null, true); }
    });
    box.appendChild(s);
  }

  /** The selected row across the business dates against the reference per date; one date open: its value written beside its point. */
  function drawDates(box, r) {
    $("[data-sm-chart-title]").textContent = r ? (S.by === "trade" ? "Trade " + r.trade + " under " + S.trades.scenario : "Scenario " + r.scenario) : "Select a scenario";
    $("[data-sm-chart-sub]").textContent = "across the dates";
    var cs = cols(), name = refName();
    var refs = cs.map(refOf);
    var anyRef = refs.some(function (v) { return v !== null; });
    drawLegend({ row: S.by === "trade" ? "this trade" : "this scenario", ref: anyRef ? name : "", base: false, mark: anyRef ? "beyond VaR" : "",
      aria: "The selected " + (S.by === "trade" ? "trade" : "scenario") + " across the business dates as " + S.chart + (anyRef ? ", against " + name : "") });
    if (!r || !cs.length) { box.appendChild(el("p", "sm-note", "Select a row of the matrix to chart it across the dates.")); return; }
    var n = cs.length;
    var pts = cs.map(function (c, i) { return cell(r, i)[0]; });
    var all = pts.concat(refs).filter(function (v) { return v !== null; });
    if (!all.length) { box.appendChild(el("p", "sm-note", "No values for this row on these dates.")); return; }
    var s = newSvg(n === 1 ? "sm-svg-one" : "sm-svg-" + S.chart);
    var X = xScale(n), Y = yScale(s, all, S.mode !== "pv");
    placeLabels(s, dayTicks(cs.map(function (c) { return c.date; }), X.x), X.x, S.selCol);
    var beyond = function (i) { return pts[i] !== null && refs[i] !== null && pts[i] <= refs[i]; };
    var tip = function (i) { return cs[i].date + ": " + full(pts[i]) + (refs[i] !== null ? " · " + name + " " + full(refs[i]) : "") + (beyond(i) ? " (beyond VaR)" : ""); };
    if (n === 1) {
      var cx = X.x(0), y = Y.y;
      if (refs[0] !== null) {
        s.appendChild(svg("line", { x1: cx - 90, x2: cx, y1: y(refs[0]), y2: y(refs[0]), "class": "sm-varline" }));   // left of the point: its value reads on the right
        var rt = svg("text", { x: cx - 12, y: y(refs[0]) - 7, "text-anchor": "end", "class": "sm-val sm-val-ref" });
        rt.textContent = name + " " + fmt(refs[0]); s.appendChild(rt);
      }
      if (pts[0] !== null) {
        var sv = svg(beyond(0) ? "path" : "circle", beyond(0)
          ? { d: "M" + cx + "," + (y(pts[0]) - 7) + "l7,7l-7,7l-7,-7Z", "class": "sm-dot sm-beyond sm-dot-sel" }
          : { cx: cx, cy: y(pts[0]), r: 5, "class": "sm-dot sm-dot-sel" });
        var st = svg("title", {}); st.textContent = tip(0); sv.appendChild(st); s.appendChild(sv);
        var vt = svg("text", { x: cx + 12, y: y(pts[0]) + 4, "class": "sm-val" }); vt.textContent = fmt(pts[0]); s.appendChild(vt);
      }
      box.appendChild(s);
      var asked = (S.by === "trade" ? S.trades : S.answer).asked || S.days;
      var one = el("p", "sm-note sm-one", asked > 1 ? "Only 1 date is open; the chart needs two or more (" + asked + " asked)."
        : "One date asked; the chart needs two or more.");
      one.setAttribute("data-sm-one", "");
      box.appendChild(one);
      return;
    }
    series(s, pts, X, Y, { sel: S.selCol, rad: 2.6, dotRad: 3.6, allDots: true, refs: [{ cls: "sm-varline", values: refs }],
      flag: function (i) { return beyond(i) ? "beyond" : ""; }, tip: tip });
    box.appendChild(s);
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
    if (S.answer) { drawBody(); }
  });
  $("[data-sm-tailonly]").addEventListener("click", function (ev) {
    S.tailOnly = !S.tailOnly;
    ev.currentTarget.setAttribute("aria-pressed", String(S.tailOnly));
    ev.currentTarget.classList.toggle("on", S.tailOnly);
    if (S.answer) { $("[data-sm-scroll]").scrollTop = 0; drawAll(); }
  });
  $("[data-sm-sort]").addEventListener("change", function (ev) { S.sort = ev.target.value; if (S.answer) { $("[data-sm-scroll]").scrollTop = 0; drawAll(); } });
  $("[data-sm-pick]").addEventListener("change", function (ev) { select(ev.target.value, null, true); });
  var raf = 0;
  $("[data-sm-scroll]").addEventListener("scroll", function () {
    if (raf) { return; }
    raf = window.requestAnimationFrame(function () { raf = 0; if (S.answer) { drawBody(); } });
  });
  $("[data-sm-tbody]").addEventListener("click", function (ev) {
    var tr = ev.target.closest("tr[data-key]");
    if (!tr) { return; }
    var td = ev.target.closest("td[data-col]");
    select(tr.dataset.key, td ? parseInt(td.dataset.col, 10) : null, false);
  });
  $("[data-sm-scroll]").addEventListener("keydown", function (ev) {
    if (!S.answer || ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].indexOf(ev.key) < 0) { return; }
    ev.preventDefault();
    var at = S.view.findIndex(function (r) { return key(r) === S.selRow; });
    if (ev.key === "ArrowUp" || ev.key === "ArrowDown") {
      var next = Math.max(0, Math.min(S.view.length - 1, at + (ev.key === "ArrowUp" ? -1 : 1)));
      if (S.view[next]) { select(key(S.view[next]), null, true); }
    } else {
      var c = Math.max(0, Math.min(cols().length - 1, S.selCol + (ev.key === "ArrowLeft" ? -1 : 1)));
      select(S.selRow, c, false);
    }
  });
  // the page fills the window: the matrix scrolls in the height left below its controls, the chart takes about half of it
  function viewportLeft(elem, below) {
    var top = elem.getBoundingClientRect().top + window.scrollY;
    return Math.max(MIN_PX, window.innerHeight - top - below);
  }

  function chartHeight(box) {
    var w = box.clientWidth || CW;
    var px = Math.max(220, Math.min(viewportLeft(box, 0) * 0.55, w * 0.9));
    return Math.round(px * CW / w);
  }

  function fit() {
    var scroll = $("[data-sm-scroll]");
    var legend = root.querySelector(".sm-legend");
    scroll.style.maxHeight = viewportLeft(scroll, (legend ? legend.offsetHeight : 0) + FIT_GAP) + "px";
  }

  var MIN_PX = 320, FIT_GAP = 28;   // the smallest matrix height; the room kept under the legend
  var resizing = 0;
  window.addEventListener("resize", function () {
    clearTimeout(resizing);
    resizing = setTimeout(function () { fit(); if (S.answer) { drawBody(); drawSide(); } }, 80);
  });
  fit();
  start();
})();
