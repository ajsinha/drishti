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
/* The VaR Playground (terminal/play.html; docs/guides/VAR_GUIDE.md, option A with option B's Rest of scope). The grid is
   drishti-grid (drishti-risk's terminal preset): only the cells in view are in the page, the header rows and the scenario column kept in
   place while scrolling, so 500 positions by 509 scenarios stay light. Selecting a cell shows its formula as the workbook writes it (the server's templates) and moves
   the explanation to the step that produced it, with the real numbers. The pickers ask again; Export to Excel downloads the workbook of the
   count chosen, its size, recalculation time and the applications that open it stated first. This file is the frame's screen: a later
   metric's playground gives its own columns, rows and steps. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the grid preset (drishti-risk)
  var root = document.querySelector("[data-play]");
  if (!root || !root.querySelector("[data-pg-grid]")) { return; }
  var W0 = 168, CW = 118, HEAD = 6;
  var HEAD_ROWS = ["Position", "Kind", "Currency", "Base PV", "Base FX (USD per unit)", "Input form"];
  var RES_ROWS = [["standaloneVar", "Standalone VaR"], ["componentVar", "Component VaR"], ["pct", "% of VaR"], ["incrementalVar", "Incremental VaR"],
    ["componentEs", "Component ES"], ["standaloneEs", "Standalone ES"], ["component10", "Component VaR, 10-day sum"]];
  var S = { data: null, view: "pnl", sel: { r: HEAD, c: 1 }, cols: [], grid: null };
  function $(s) { return root.querySelector(s); }
  var grid = $("[data-pg-grid]"), explain = $("[data-pg-explain]");

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) { return v == null || isNaN(v) ? "—" : F.formatNumber(v, { decimals: 2 }); }
  function num(v, d) { return v == null ? "—" : Number(v).toLocaleString("en-US", { maximumFractionDigits: d == null ? 6 : d }); }
  function pct(v) { return v == null ? "—" : (v * 100).toFixed(1) + "%"; }
  function letter(i) { var s = ""; for (var n = i; n >= 0; n = Math.floor(n / 26) - 1) { s = String.fromCharCode(65 + n % 26) + s; } return s; }
  function confPct(c) { var p = Math.round(c * 10000) / 100; return (p === Math.round(p) ? p.toFixed(0) : String(p)) + "%"; }

  // ------------------------------------------------------------------------------------------------------------ the request
  function params() {
    var d = root.dataset, q = new URLSearchParams();
    if (d.scope) { q.set("scope", d.scope); }
    if (d.ids) { q.set("ids", d.ids); }
    ["ccy", "h", "m", "q", "esq", "set"].forEach(function (k) { if (d[k]) { q.set(k, d[k]); } });
    var asOf = new URLSearchParams(window.location.search).get("asOf");
    if (asOf) { q.set("asOf", asOf); }
    if (d.measure) { q.set("measure", d.measure); }                 // the Parametric tab: the parametric figures, and the workbook's Parametric tab
    return q;
  }
  function load() {
    var q = params();
    q.set("view", S.view);
    $("[data-pg-meta]").textContent = "loading…";
    grid.textContent = "";
    S.grid = null;
    return fetch("/api/play/grid?" + q.toString(), { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { throw new Error(b.detail || ("HTTP " + r.status)); }
        return b;
      });
    }).then(function (d) { S.data = d; build(); }, function (e) {
      $("[data-pg-meta]").textContent = "";
      notice(e.message);
      explain.innerHTML = "";
    });
  }
  function notice(t) { var n = $("[data-pg-notice]"); n.textContent = t || ""; n.hidden = !t; }
  function remember() {
    var u = new URL(window.location.href), d = root.dataset;
    ["ccy", "h", "m", "q", "esq", "set"].forEach(function (k) { if (d[k]) { u.searchParams.set(k, d[k]); } else { u.searchParams.delete(k); } });
    window.history.replaceState(null, "", u.toString());
  }

  // ------------------------------------------------------------------------------------------------------------ the columns
  // The grid is drishti-grid in drishti-risk's terminal layout (ELEMENTS.md section 12): positions across as columns lettered as the
  // workbook's, scenarios and results down as rows; the six header rows (Position, Kind ... Input form) pinned under the letters and the
  // label column pinned on the left, so they stay in view as 500 positions by 509 scenarios scroll (only the cells in view are drawn).
  function build() {
    var d = S.data, cols = [];
    d.positions.forEach(function (p, i) { cols.push({ kind: "pos", i: i, p: p }); });
    if (d.rest) { cols.push({ kind: "rest", i: d.positions.length, p: d.rest }); }
    cols.push({ kind: "portfolio" }, { kind: "rank" }, { kind: "mark" });
    S.cols = cols;
    S.rows = HEAD + d.scenarios.length + RES_ROWS.length;
    var lens = d.lens;
    var h = lens.horizon === "1D" ? "1-day" : lens.method === "SUM" ? "10-day sum" : "10-day √10";
    $("[data-pg-path]").textContent = d.label + (d.label !== d.canonical ? "  (" + d.canonical + ")" : "");
    $("[data-pg-meta]").textContent = d.businessDate + " · " + d.currency + " · " + h + " " + confPct(lens.confidence) + " · " + d.scenarios.length
      + " scenarios (" + d.scenarios[0] + " to " + d.scenarios[d.scenarios.length - 1] + ") · " + d.shown.toLocaleString("en-US") + " of "
      + d.total.toLocaleString("en-US") + " positions" + (lens.set === "STRESSED" ? " · stressed" : "");
    notice(d.total > d.shown ? d.shown.toLocaleString("en-US") + " of " + d.total.toLocaleString("en-US") + " positions shown (the largest by |component VaR|); "
      + "the other " + (d.total - d.shown).toLocaleString("en-US") + " are combined in Rest of scope, so the portfolio is still the scope's and its VaR the VaR page's."
      : d.rest ? "Every position shown; Rest of scope holds no positions, only the difference between the scope's figure (summed from the rows as read) and its positions' stored rows." : "");
    pickers();
    exportChoices();
    var v = d.figures.varScenario;
    S.sel = { r: HEAD + Math.max(0, v), c: 1 };
    draw();
    if (d.parametric) { parametric(d); }
    select(S.sel.r, S.sel.c);
    focusSel();
  }

  function value(col, r) {
    var d = S.data, j = r - HEAD, n = d.scenarios.length;
    if (r < HEAD) {
      if (col.kind === "pos" || col.kind === "rest") {
        var p = col.p;
        return [p.id, col.kind === "rest" ? "rest (" + p.count + ")" : p.kind, p.currency, col.kind === "rest" ? "n/a" : money(p.basePv),
          col.kind === "rest" ? "" : num(p.baseFx), p.form][r];
      }
      return r === 0 ? { portfolio: "Portfolio", rank: "Rank", mark: "Mark" }[col.kind] : "";
    }
    if (j < n) {
      if (col.kind === "portfolio") { return money(d.portfolio[j]); }
      if (col.kind === "rank") { return String(d.rank[j]); }
      if (col.kind === "mark") { return d.mark[j]; }
      if (S.view === "fx" && col.kind === "pos") { return num(d.fx[col.p.currency].rates[j]); }
      if (S.view === "pv" && col.kind === "pos" && col.p.pv) { return money(col.p.pv[j]); }
      return money(col.p.pnl[j]);
    }
    var k = RES_ROWS[j - n];
    if (!k || !(col.kind === "pos" || col.kind === "rest")) {
      if (k && col.kind === "portfolio") {
        return { standaloneVar: money(d.figures["var"]), componentVar: money(d.figures.sumComponentVar), pct: "100.0%", componentEs: money(d.figures.es) }[k[0]] || "";
      }
      return "";
    }
    var x = col.p[k[0]];
    if (k[0] === "incrementalVar" && x == null) { return col.kind === "rest" ? "" : "top " + d.incrementalPositions + " only"; }
    return k[0] === "pct" ? pct(x) : money(x);
  }

  function cellClass(col, r) {
    var d = S.data, j = r - HEAD, n = d.scenarios.length, c = "pg-c";
    if (r < HEAD) { c += " pg-hd" + (r === 0 ? " pg-id" : "") + (col.kind === "pos" && r >= 2 ? " pg-in" : ""); }
    else if (j < n) {
      var raw = col.kind === "portfolio" ? d.portfolio[j] : col.kind === "pos" || col.kind === "rest" ? col.p.pnl[j] : null;
      if (raw != null && raw < 0 && S.view === "pnl" || col.kind === "portfolio" && d.portfolio[j] < 0) { c += " pg-neg"; }
      if (d.rank[j] === d.figures.k) { c += " pg-v"; }
      if (col.kind === "mark" && d.mark[j]) { c += " pg-mk"; }
      if (S.view !== "pnl" && col.kind === "pos") { c += " pg-in"; }
    } else { c += " pg-res"; }
    if (col.kind === "rest") { c += " pg-rest"; }
    if (col.kind === "portfolio") { c += " pg-pf"; }
    return c;
  }

  /** The label of a row (column A): a header row's name, a scenario's number and date, a result's name with its confidence. */
  function label(r) {
    var d = S.data, j = r - HEAD, n = d.scenarios.length;
    return r < HEAD ? HEAD_ROWS[r] : j < n ? (j + 1) + " · " + d.scenarios[j] : RES_ROWS[j - n][1]
      + (RES_ROWS[j - n][0].indexOf("Var") > 0 && RES_ROWS[j - n][0] !== "incrementalVar" && j - n < 2 ? " " + confPct(d.lens.confidence) : "")
      + (RES_ROWS[j - n][0].indexOf("Es") > 0 ? " " + confPct(d.lens.esConfidence) : "");
  }

  /** A cell as the workbook's: its text, its tint, its place (data-r, data-c) and the selection. */
  function drawn(ci) {
    return function (x) {
      var r = x.row.r, sp = document.createElement("span");
      sp.className = ci === 0 ? "pg-c pg-lbl" + (r < HEAD ? " pg-hd" : r - HEAD >= S.data.scenarios.length ? " pg-res" : "") : cellClass(S.cols[ci - 1], r);
      if (x.pinned !== undefined) sp.className += " pg-ft";
      if (ci === 0) sp.className += " pg-fl";
      if (S.sel.r === r && S.sel.c === ci) sp.className += " pg-sel";
      sp.dataset.r = String(r);
      sp.dataset.c = String(ci);
      if (ci === 0) { var rn = document.createElement("span"); rn.className = "pg-rn"; rn.textContent = String(r + 1); sp.appendChild(rn); }
      sp.appendChild(document.createTextNode(x.text));
      return sp;
    };
  }

  function draw() {
    if (!S.data) { return; }
    var columns = [{ id: "c0", header: "A", width: W0, pinned: "left", sortable: false, filterable: false, valueGetter: function (row) { return label(row.r); }, cellRenderer: drawn(0) }]
      .concat(S.cols.map(function (col, i) {
        return { id: "c" + (i + 1), header: letter(i + 1), width: CW, sortable: false, filterable: false, type: "text",
          valueGetter: function (row) { return value(col, row.r); }, cellRenderer: drawn(i + 1) };
      }));
    var rows = [];
    for (var r = HEAD; r < S.rows; r++) { rows.push({ r: r }); }
    var head = [];
    for (var k = 0; k < HEAD; k++) { head.push({ r: k }); }
    S.grid = R.riskGrid(grid, { title: "The playground grid", label: "The playground grid: positions across, scenarios down, results below", columns: columns,
      rows: rows, pinnedTopRows: head, getRowId: function (x) { return String(x.r); }, rowSelection: "none", fitRows: 0 });
    var moved = function (f) { if (f && f.row !== undefined && f.row !== null && f.row >= 0) { select(HEAD + f.row, f.col); S.grid.refresh(); } };
    S.grid.on("focusChanged", moved);                                  // the keys
    S.grid.on("cellClicked", moved);                                   // a click
    S.grid.on("pinnedCellClicked", function (p) { select(p.pinned, p.col); S.grid.refresh(); });
  }

  /** The selection's cell focused in the grid (scrolled into view; a header row is always in view). */
  function focusSel() {
    if (!S.grid || S.sel.r < HEAD) { return; }
    S.grid.focusCell(S.sel.r - HEAD, S.sel.c);
  }

  // ------------------------------------------------------------------------------------------------------------ the formula bar
  function pnlCol(i) { return letter(1 + i); }
  function formula(r, c) {
    var d = S.data, f = d.formulas, n = d.scenarios.length, j = r - HEAD, col = c > 0 ? S.cols[c - 1] : null, items = d.positions.length + (d.rest ? 1 : 0);
    var pf = letter(1 + items), rk = letter(2 + items);
    if (!col) { return { ref: "", f: r < HEAD ? HEAD_ROWS[r] : j < n ? "scenario " + (j + 1) + ", " + d.scenarios[j] : RES_ROWS[j - n][1] }; }
    if (r < HEAD) {
      if (col.kind !== "pos" && col.kind !== "rest") { return { ref: "", f: "" }; }
      var cc = ["A", "B", "C", "D", "E", "F"][r];
      return { ref: "Config!" + cc + (2 + col.i), f: "typed: " + value(col, r) };
    }
    var row = 4 + j;
    if (j < n) {
      if (col.kind === "pos") {
        if (S.view === "pv") { return { ref: "Data!" + pnlCol(col.i) + row, f: "typed (pasted): the scenario PV in " + col.p.currency + (col.p.form === "PnL" ? ", or the P&L as delivered" : "") }; }
        if (S.view === "fx") { return { ref: "FX!" + row, f: "typed: " + col.p.currency + " under scenario " + (j + 1) + ", USD per unit (" + d.fx[col.p.currency].source + ")" }; }
        return { ref: "PnL!" + pnlCol(col.i) + row, f: "=" + f.pnl.split("{D}").join(pnlCol(col.i) + row).split("{C}").join(String(2 + col.i)).split("{F}").join(String(row)) };
      }
      if (col.kind === "rest") { return { ref: "PnL!" + pnlCol(col.i) + row, f: "=Data!" + pnlCol(col.i) + row + "   (Rest of scope, entered as data)" }; }
      if (col.kind === "portfolio") { return { ref: "PnL!" + pf + row, f: "=SUM(B" + row + ":" + pnlCol(items - 1) + row + ")" }; }
      if (col.kind === "rank") {
        return { ref: "PnL!" + rk + row, f: "=SUMPRODUCT(($" + pf + "$4:$" + pf + "$" + (3 + n) + "<" + pf + row + ")*1)"
          + (j > 0 ? "+SUMPRODUCT(($" + pf + "$4:$" + pf + "$" + (row - 1) + "=" + pf + row + ")*1)" : "") + "+1" };
      }
      return { ref: "PnL!" + letter(3 + items) + row, f: '=IF(' + rk + row + '=VarRank,"V",IF(' + rk + row + '<=EsRank,"E",""))' };
    }
    var k = RES_ROWS[j - n];
    if (col.kind === "pos" || col.kind === "rest") {
      var t = f[k[0]] || "";
      if (k[0] === "pct") { return { ref: "Main", f: "=component VaR / VaROneDay" }; }
      if (k[0] === "incrementalVar" && !col.p.helperRange) { return { ref: "Main", f: "not computed (the top " + d.incrementalPositions + " by |component VaR| only)" }; }
      return { ref: "Main", f: "=" + t.split("{P}").join(col.p.pnlRange).split("{X}").join(col.p.helperRange || "") };
    }
    if (col.kind === "portfolio" && k[0] === "standaloneVar") { return { ref: "Main!VaROneDay", f: "=" + f["var"] }; }
    if (col.kind === "portfolio" && k[0] === "componentEs") { return { ref: "Main!ESOneDay", f: "=" + f.es }; }
    return { ref: "", f: "" };
  }

  function select(r, c) {
    S.sel = { r: r, c: c };
    var fm = formula(r, c);
    $("[data-pg-ref]").textContent = fm.ref || "—";
    $("[data-pg-formula]").textContent = fm.f || "";
    explainCell(r, c);
    var col = c > 0 ? S.cols[c - 1] : null, a = $("[data-pg-smat]");
    var id = col && col.kind === "pos" ? col.p.id : S.data.canonical;
    a.href = "/smat?id=" + encodeURIComponent(id) + (S.data.currency !== "USD" ? "&ccy=" + S.data.currency : "");
  }

  // ------------------------------------------------------------------------------------------------------------ the explanation
  function step(n, title, lines, on) {
    return '<div class="pg-step' + (on ? " on" : "") + '"><h3>' + n + ". " + esc(title) + "</h3>" + lines.map(function (l) {
      return '<div class="' + (l.indexOf("= ") === 0 || l.indexOf("=") > 0 && l.length < 140 ? "pg-calc mono" : "pg-say") + '">' + esc(l) + "</div>";
    }).join("") + "</div>";
  }
  function explainCell(r, c) {
    var d = S.data, f = d.figures, n = d.scenarios.length, j = r - HEAD, col = c > 0 ? S.cols[c - 1] : null;
    var lens = d.lens, ccy = d.currency, q = confPct(lens.confidence), eq = confPct(lens.esConfidence);
    var scen = j >= 0 && j < n ? j : f.varScenario, pos = col && (col.kind === "pos" || col.kind === "rest") ? col : S.cols[0];
    var focus = j >= n ? "result" : col && col.kind === "rank" ? "rank" : col && col.kind === "portfolio" ? "sum" : "cell";
    var h = "";
    h += '<div class="pg-hl"><div class="pg-hl-k">VaR ' + q + ", " + (lens.horizon === "1D" ? "1-day" : lens.method === "SUM" ? "10-day sum" : "10-day √10")
      + " · " + esc(d.label) + '</div><div class="pg-hl-v mono">' + money(f.headlineVar) + " " + ccy + '</div><div class="pg-hl-s">ES ' + eq + " "
      + money(f.headlineEs) + " · diversification " + money(f.diversification) + "</div></div>";
    var p = pos.p, fx = d.fx, cur = p.currency, rep = fx[ccy] || { base: 1, rates: [] };
    var lines = [];
    if (pos.kind === "rest") {
      lines = ["Rest of scope: " + (p.count ? "the summed P&L of the " + p.count.toLocaleString("en-US") + " positions not shown" : "no positions: rounding of the stored rows")
        + ", already in " + ccy + ", entered as data.", "= " + money(p.pnl[scen]) + " " + ccy];
    } else if (p.form === "PV") {
      var fs = fx[cur].rates[scen], fb = fx[cur].base, today = p.todayUsd;
      var usd = ccy === "USD" ? p.pnl[scen] : (p.pnl[scen] + today / rep.base) * rep.rates[scen] - today;
      var pv = (usd + today) / fs;
      lines = ["P&L(s) = PV(s) × FX(s) − PV(base) × FX(base)", "= " + money(pv) + " × " + num(fs) + " − (" + money(p.basePv) + " × " + num(fb) + ")",
        "= " + money(usd) + " USD", "Currency " + cur + "; FX is USD per unit, the scenario rate " + fx[cur].source + "."];
      if (ccy !== "USD") { lines.push("Into " + ccy + ": (p + P&L) / FX_" + ccy + "(s) − p / FX_" + ccy + "(base) = " + money(p.pnl[scen]) + " " + ccy); }
    } else {
      lines = ["Input form PnL: the P&L vector as delivered, in USD (no PV arithmetic).", "= " + money(p.pnl[scen]) + " " + ccy];
    }
    h += step(1, "The selected cell: " + p.id + ", scenario " + (scen + 1) + " (" + d.scenarios[scen] + ")", lines, focus === "cell");
    h += step(2, "Portfolio P&L, the sum across the row", ["v(" + (scen + 1) + ") = Σ positions" + (d.rest ? " + Rest of scope" : "") + " = " + money(d.portfolio[scen])], focus === "sum");
    h += step(3, "Ranked worst first", ["k = ⌈" + n + " × (1 − " + lens.confidence + ")⌉ = " + f.k, "Scenario " + (scen + 1) + " ranks " + d.rank[scen]
      + "; rank " + f.k + " is scenario " + (f.varScenario + 1) + ", " + f.varScenarioDate + " (marked V); ties broken by index."], focus === "rank");
    h += step(4, "VaR = −(the " + ordinal(f.k) + " worst)", ["VaR = −(" + money(-f["var"]) + ") = " + money(f["var"])], false);
    h += step(5, "ES " + eq + " = mean of the " + f.kEs + " worst", ["k_ES = ⌈" + n + " × (1 − " + lens.esConfidence + ")⌉ = " + f.kEs + "; ES = " + money(f.es),
      "The tail rows are marked E."], false);
    if (pos.kind === "pos" || pos.kind === "rest") {
      h += step(6, "Component VaR of " + p.id, ["window: ranks " + f.lo + " to " + f.hi + " (component-window " + lens.componentWindow + ")",
        "scale c = VaR / S = " + num(f.scale, 6) + " (S = " + money(f.smoothed) + ")", "component = −c × mean over the window = " + money(p.componentVar)
        + " (" + pct(p.pct) + " of VaR)", "component ES = " + money(p.componentEs) + "; standalone VaR " + money(p.standaloneVar)
        + (p.incrementalVar != null ? "; incremental VaR " + money(p.incrementalVar) : "")], focus === "result");
    }
    h += step(7, "10-day", ["√10: " + money(f["var"]) + " × 3.162278 = " + money(f.var10sqrt), "sum of 10 consecutive days: " + f.windows + " windows, k = " + f.k10
      + ": " + money(f.var10sum)], false);
    h += step(8, "Every confidence (the same ranked vector)", f.confidences.map(function (x) {
      return confPct(x.confidence) + " (" + ordinal(x.k) + "): " + money(x["var"]) + " · √10 " + money(x.var10sqrt) + " · sum " + money(x.var10sum);
    }), false);
    var ok = d.checks.every(function (x) { return x.ok; });
    h += '<div class="pg-check ' + (ok ? "ok" : "bad") + '">' + (ok ? "✓ Σ component VaR = VaR to the cent · sheet = engine (" + money(d.engine["var"]) + ")"
      : "✗ " + d.checks.filter(function (x) { return !x.ok; }).map(function (x) { return x.name; }).join(" · ")) + "</div>";
    explain.innerHTML = h;
  }
  /** The Parametric tab's panel: the server's figures, steps, positions and scenario columns placed as they come (nothing computed here). */
  function parametric(d) {
    var p = d.parametric, f = $("[data-pg-param-figures]");
    if (!f) { return; }
    var rows = [["n", num(p.n, 0)], ["Weighting", p.weighting + (p.lambda ? ", λ " + p.lambda : "")], ["μ", money(p.mu)], ["σ", money(p.sigma)],
      ["z", num(p.z, 6)], ["φ(z_ES)/(1 − c)", num(p.esFactor, 6)], ["Parametric VaR = z·σ − μ", money(p["var"])], ["Parametric ES", money(p.es)],
      ["Σ components", money(p.sumComponent)]];
    R.riskGrid(f, { title: "Parametric figures", rows: rows.map(function (r, i) { return { i: i, what: r[0], value: r[1] }; }), getRowId: function (r) { return String(r.i); },
      columns: [{ field: "what", header: "Figure", width: 200 }, { field: "value", header: "Value", width: 150, cellClass: function () { return "dg-num"; } }] });
    $("[data-pg-param-steps]").innerHTML = (p.steps || []).map(function (s) { return "<li>" + esc(s) + "</li>"; }).join("");
    var fig = function (field, header, fmt, w) { return R.figureColumn(field, header, { format: fmt, width: w || 120 }); };
    R.riskGrid($("[data-pg-param-positions]"), { title: "Each position", rows: p.positions, getRowId: function (r) { return r.id; }, fitRows: 14,
      columns: [{ field: "id", header: "Position", width: 140, pinned: "left" }, fig("mu", "μᵢ", money), fig("sigma", "σᵢ", money),
        fig("cov", "cov(Pᵢ, P)", function (v) { return Number(v).toExponential(4); }), fig("rho", "ρᵢ", function (v) { return num(v, 4); }, 80),
        fig("componentVar", "Component VaR", money), fig("componentEs", "Component ES", money), fig("standaloneVar", "Standalone VaR", money)] });
    var c = p.columns, sc = d.scenarios;
    R.riskGrid($("[data-pg-param-scenarios]"), { title: "Each scenario", fitRows: 10, getRowId: function (r) { return String(r.j); },
      rows: c.w.map(function (w, j) { return { j: j, n: j + 1, date: sc[j], w: w, dev: c.dev[j], sq: c.sq[j] }; }),
      columns: [fig("n", "#", function (v) { return String(v); }, 60), { field: "date", header: "Date", width: 110 }, fig("w", "wⱼ", function (v) { return num(v, 8); }),
        fig("dev", "vⱼ − μ", money), fig("sq", "(vⱼ − μ)²", function (v) { return Number(v).toExponential(4); })] });
  }

  function ordinal(k) { var s = ["th", "st", "nd", "rd"], v = k % 100; return k + (s[(v - 20) % 10] || s[v] || s[0]); }

  // ------------------------------------------------------------------------------------------------------------ pickers and export
  function pickers() {
    var d = S.data, lens = d.lens;
    var hz = $("[data-pg-horizon]");
    hz.value = lens.horizon === "1D" ? "1d" : lens.method === "SUM" ? "10d-sum" : "10d-sqrt10";
    fill($("[data-pg-q]"), lens.confidences, lens.confidence);
    fill($("[data-pg-esq]"), lens.esConfidences, lens.esConfidence);
    root.querySelectorAll("[data-pg-set]").forEach(function (b) { b.setAttribute("aria-pressed", String(b.dataset.pgSet === (lens.set === "STRESSED" ? "stressed" : "normal"))); });
    var c = $("[data-pg-ccy]").component;
    if (!c.options.length) {
      fetch("/api/var/currencies", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : { listed: [] }; }).then(function (x) {
        var all = (x.listed || []).concat((x.available || []).filter(function (a) { return (x.listed || []).indexOf(a) < 0; }));
        if (all.indexOf(d.currency) < 0) { all.unshift(d.currency); }
        c.setOptions(all, d.currency);
      });
    } else { c.setValue(d.currency); }
  }
  /** A picker (a <drishti-select>, drishti-inputs) filled with confidences, cur picked. */
  function fill(sel, list, cur) {
    sel.component.setOptions((list || []).map(function (v) { return { value: String(v), label: confPct(v) }; }), String(cur));
  }
  /** The export's choices: a refused one is shown disabled, its reason as its detail; the server's default picked. */
  function exportChoices() {
    var ch = S.data["export"].choices, def = ch.filter(function (c) { return c["default"]; })[0];
    var key = function (c) { return c.count === 0 ? "all" : String(c.count); };
    $("[data-pg-count]").component.setOptions(ch.map(function (c) {
      return { value: key(c), label: c.label + (c.refused ? " (cannot be made)" : " · " + c.size), disabled: !!c.refused, detail: c.refused || "" };
    }), def ? key(def) : undefined);
    estimate();
  }
  function estimate() {
    var v = $("[data-pg-count]").value, ch = S.data["export"].choices.filter(function (c) { return (c.count === 0 ? "all" : String(c.count)) === String(v); })[0];
    if (!ch) { return; }
    $("[data-pg-estimate]").textContent = ch.refused ? ch.refused : "Workbook of " + ch.positions.toLocaleString("en-US") + " positions" + (S.data.total > ch.positions
      ? " (the other " + (S.data.total - ch.positions).toLocaleString("en-US") + " in Rest of scope)" : "") + ": about " + ch.size + ", " + ch.layout + ", recalculates in about "
      + ch.recalc + "; opens in " + ch.apps.join(", ") + ".";
  }
  $("[data-pg-count]").addEventListener("drishti:change", estimate);
  $("[data-pg-export]").addEventListener("click", function () {
    var q = params();
    q.set("count", $("[data-pg-count]").value);
    window.location.href = "/api/play/export.xlsx?" + q.toString();
  });
  $("[data-pg-copy]").addEventListener("click", function () {
    var t = $("[data-pg-formula]").textContent;
    if (navigator.clipboard) { navigator.clipboard.writeText(t); }
  });
  $("[data-pg-horizon]").addEventListener("drishti:change", function (e) {
    var v = e.detail.value, d = root.dataset;
    d.h = v === "1d" ? "" : "10d";
    d.m = v === "10d-sum" ? "sum" : v === "10d-sqrt10" ? "sqrt10" : "";
    remember(); load();
  });
  $("[data-pg-q]").addEventListener("drishti:change", function (e) { root.dataset.q = e.detail.value; remember(); load(); });
  $("[data-pg-esq]").addEventListener("drishti:change", function (e) { root.dataset.esq = e.detail.value; remember(); load(); });
  $("[data-pg-ccy]").addEventListener("drishti:change", function (e) { root.dataset.ccy = e.detail.value; remember(); load(); });
  root.querySelectorAll("[data-pg-set]").forEach(function (b) {
    b.addEventListener("click", function () {
      root.dataset.set = b.dataset.pgSet === "stressed" ? "stressed" : "";
      if (root.dataset.set) { root.dataset.ccy = ""; }
      remember(); load();
    });
  });
  root.querySelectorAll("[data-pg-view]").forEach(function (b) {
    b.addEventListener("click", function () {
      root.querySelectorAll("[data-pg-view]").forEach(function (x) { x.setAttribute("aria-pressed", String(x === b)); });
      S.view = b.dataset.pgView;
      if (S.view === "pv" && S.data && !S.data.positions.some(function (p) { return p.pv; })) { load(); } else { draw(); select(S.sel.r, S.sel.c); }
    });
  });
  load();
})();
