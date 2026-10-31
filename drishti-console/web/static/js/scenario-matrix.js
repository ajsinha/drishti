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
/* The scenario matrix, SMAT <id> [N] (terminal/smat.html; VAR_GUIDE.md section 3.9; the approved mock-up scenario-matrix-B). One answer of
   /api/smat/matrix fills the page: the columns (each date's source, VaR, ES, base PV, tail and gap) and the rows (a scenario day each, a
   cell per date: its P&L, or null where the date's window does not hold the day or the date is a gap). Everything else is computed here
   from it: PV (base PV + P&L), the change against the date before, each cell's rank, the tail outline (the date's VaR scenario and ES
   tail, outlined and marked V / E so it is not colour only), the sort, the tail-only filter, the chart of the selected row against -VaR.
   The rows are virtualised (only those in view are in the table), so 509 rows by 60 dates scroll as fast as 20. A big matrix comes as
   Arrow (drishti-table.js), a small one as JSON. By trade (a position whose trades are known) asks again with by=trade and the selected
   scenario. Every request goes to this console's own host. */
(function () {
  "use strict";
  var root = document.querySelector("[data-smat]");
  if (!root || !root.dataset.target) { return; }
  var $ = function (sel) { return root.querySelector(sel); };
  var ROW_H = 26;                       // the table's row height (scenario-matrix.css .sm-table td)
  var ARROW_CELLS = 4000;               // a matrix with more cells than this travels as Arrow (when Arrow JS is on the page)
  var S = {
    target: root.dataset.target, days: parseInt(root.dataset.days, 10) || 20, ccy: root.dataset.ccy || "",
    mode: "pnl", by: "scenario", outline: true, tailOnly: false, sort: "worst-last", answer: null, trades: null,
    selRow: null, selCol: -1, view: [], rankCache: {}
  };

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
    if (big && window.drishtiFetchTable && window.Arrow) {
      return window.drishtiFetchTable(url, { shape: "records", at: "rows" }).then(function (t) {
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
      var tr = el("tr", key(r) === S.selRow ? "sm-pick" : "");
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
    drawBody();
    var size = $("[data-sm-size]");
    size.textContent = S.view.length + (S.by === "trade" ? " trades" : " scenarios") + " × " + cols().length + " dates" + (S.answer.__format === "arrow" ? " · read as Arrow" : "");
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

  function drawChart(r) {
    var box = $("[data-sm-chart]");
    box.textContent = "";
    var cs = cols();
    if (!r || !cs.length) { box.appendChild(el("p", "sm-note", "Select a row of the matrix to chart it across the dates.")); return; }
    var W = 420, H = 220, L = 52, R = 10, T = 12, B = 26;
    var pts = cs.map(function (c, i) { return cell(r, i)[0]; });
    var vars = cs.map(function (c) { return c.gap || S.by === "trade" || S.mode !== "pnl" ? null : (c.var === null || c.var === undefined ? null : -c.var); });
    var all = pts.concat(vars).filter(function (v) { return v !== null; });
    if (!all.length) { box.appendChild(el("p", "sm-note", "No values for this row on these dates.")); return; }
    var lo = Math.min.apply(null, all.concat([0])), hi = Math.max.apply(null, all.concat([0]));
    if (hi === lo) { hi = lo + 1; }
    var pad = (hi - lo) * 0.08; lo -= pad; hi += pad;
    var x = function (i) { return L + (cs.length === 1 ? (W - L - R) / 2 : i * (W - L - R) / (cs.length - 1)); };
    var y = function (v) { return T + (hi - v) * (H - T - B) / (hi - lo); };
    var s = svg("svg", { viewBox: "0 0 " + W + " " + H, "class": "sm-svg", "aria-hidden": "true" });
    [hi - pad, (hi + lo) / 2, lo + pad].forEach(function (v) {
      s.appendChild(svg("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), "class": "sm-grid-line" }));
      var t = svg("text", { x: 2, y: y(v) + 4, "class": "sm-axis" }); t.textContent = fmt(v); s.appendChild(t);
    });
    s.appendChild(svg("line", { x1: L, x2: W - R, y1: y(0), y2: y(0), "class": "sm-zero" }));
    [0, cs.length - 1].forEach(function (i) {
      var t = svg("text", { x: Math.min(x(i), W - R - 34), y: H - 6, "class": "sm-axis" }); t.textContent = short(cs[i].date); s.appendChild(t);
    });
    function line(values, cls) {
      var seg = [];
      var flush = function () { if (seg.length > 1) { s.appendChild(svg("polyline", { points: seg.join(" "), "class": cls })); } seg = []; };
      values.forEach(function (v, i) { if (v === null) { flush(); } else { seg.push(x(i).toFixed(1) + "," + y(v).toFixed(1)); } });
      flush();
    }
    line(vars, "sm-varline");
    line(pts, "sm-line");
    pts.forEach(function (v, i) {
      if (v === null) { return; }
      var beyond = vars[i] !== null && v <= vars[i];
      var c = svg("circle", { cx: x(i), cy: y(v), r: i === S.selCol ? 4.5 : 2.6, "class": "sm-dot" + (beyond ? " sm-beyond" : "") + (i === S.selCol ? " sm-dot-sel" : "") });
      var t = svg("title", {}); t.textContent = cs[i].date + ": " + full(v) + (beyond ? " (beyond VaR)" : ""); c.appendChild(t);
      s.appendChild(c);
    });
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
    $("[data-sm-chart-title]").textContent = r ? (S.by === "trade" ? "Trade " + r.trade + " under " + S.trades.scenario : "Scenario " + r.scenario) : "Select a scenario";
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
  window.addEventListener("resize", function () { if (S.answer) { drawBody(); } });
  start();
})();
