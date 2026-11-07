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
/* The VaR Playground (terminal/play.html; docs/architecture/VAR_PLAYGROUND.md, option A with option B's Rest of scope). The grid is drawn
   in windows: only the cells in view are in the page, the header rows and the scenario column kept in place while scrolling, so 500
   positions by 509 scenarios stay light. Selecting a cell shows its formula as the workbook writes it (the server's templates) and moves
   the explanation to the step that produced it, with the real numbers. The pickers ask again; Export to Excel downloads the workbook of the
   count chosen, its size, recalculation time and the applications that open it stated first. This file is the frame's screen: a later
   metric's playground gives its own columns, rows and steps. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var root = document.querySelector("[data-play]");
  if (!root || !root.querySelector("[data-pg-scroll]")) { return; }
  var RH = 22, W0 = 168, CW = 118, HEAD = 6;
  var HEAD_ROWS = ["Position", "Kind", "Currency", "Base PV", "Base FX (USD per unit)", "Input form"];
  var RES_ROWS = [["standaloneVar", "Standalone VaR"], ["componentVar", "Component VaR"], ["pct", "% of VaR"], ["incrementalVar", "Incremental VaR"],
    ["componentEs", "Component ES"], ["standaloneEs", "Standalone ES"], ["component10", "Component VaR, 10-day sum"]];
  var S = { data: null, view: "pnl", sel: { r: HEAD, c: 1 }, cols: [] };
  function $(s) { return root.querySelector(s); }
  var scroll = $("[data-pg-scroll]"), canvas = $("[data-pg-canvas]"), explain = $("[data-pg-explain]");

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
    canvas.innerHTML = "";
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
  function build() {
    var d = S.data, cols = [];
    d.positions.forEach(function (p, i) { cols.push({ kind: "pos", i: i, p: p }); });
    if (d.rest) { cols.push({ kind: "rest", i: d.positions.length, p: d.rest }); }
    cols.push({ kind: "portfolio" }, { kind: "rank" }, { kind: "mark" });
    S.cols = cols;
    S.rows = HEAD + d.scenarios.length + RES_ROWS.length;
    var lens = d.lens, f = d.figures;
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
    canvas.style.width = (W0 + cols.length * CW) + "px";
    canvas.style.height = ((S.rows + 1) * RH) + "px";
    var v = d.figures.varScenario;
    S.sel = { r: HEAD + Math.max(0, v), c: 1 };
    draw();
    if (d.parametric) { parametric(d); }
    scrollToSel(true);
    select(S.sel.r, S.sel.c);
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
        return { standaloneVar: money(d.figures.var), componentVar: money(d.figures.sumComponentVar), pct: "100.0%", componentEs: money(d.figures.es) }[k[0]] || "";
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

  var raf = 0;
  function draw() {
    raf = 0;
    if (!S.data) { return; }
    var st = scroll.scrollTop, sl = scroll.scrollLeft, h = scroll.clientHeight, w = scroll.clientWidth;
    var r0 = Math.max(HEAD, Math.floor(st / RH) + HEAD - 1), r1 = Math.min(S.rows - 1, Math.ceil((st + h) / RH) + 1);
    var c0 = Math.max(0, Math.floor((sl - W0) / CW) - 1), c1 = Math.min(S.cols.length - 1, Math.ceil((sl + w - W0) / CW) + 1);
    var out = [];
    function cell(r, ci, top, left, frozenTop, frozenLeft) {
      var col = S.cols[ci], sel = S.sel.r === r && S.sel.c === ci + 1;
      out.push('<div class="' + cellClass(col, r) + (sel ? " pg-sel" : "") + (frozenTop ? " pg-ft" : "") + (frozenLeft ? " pg-fl" : "") + '" data-r="' + r
        + '" data-c="' + (ci + 1) + '" data-t="' + top + '" data-l="' + left + '">' + esc(value(col, r)) + "</div>");
    }
    function label(r, top, frozenTop) {
      var d = S.data, j = r - HEAD, n = d.scenarios.length;
      var t = r < HEAD ? HEAD_ROWS[r] : j < n ? (j + 1) + " · " + d.scenarios[j] : RES_ROWS[j - n][1]
        + (RES_ROWS[j - n][0].indexOf("Var") > 0 && RES_ROWS[j - n][0] !== "incrementalVar" && j - n < 2 ? " " + confPct(d.lens.confidence) : "")
        + (RES_ROWS[j - n][0].indexOf("Es") > 0 ? " " + confPct(d.lens.esConfidence) : "");
      var sel = S.sel.r === r && S.sel.c === 0;
      out.push('<div class="pg-c pg-lbl' + (r < HEAD ? " pg-hd" : j >= n ? " pg-res" : "") + (sel ? " pg-sel" : "") + (frozenTop ? " pg-ft" : "")
        + ' pg-fl" data-r="' + r + '" data-c="0" data-t="' + top + '" data-l="' + sl + '"><span class="pg-rn">' + (r + 1) + "</span>" + esc(t) + "</div>");
    }
    for (var r = r0; r <= r1; r++) {
      var top = RH + r * RH;
      for (var ci = c0; ci <= c1; ci++) { cell(r, ci, top, W0 + ci * CW, false, false); }
      label(r, top, false);
    }
    for (var hr = 0; hr < HEAD; hr++) {                                // the header rows, kept in view
      var ht = st + RH + hr * RH;
      for (var hc = c0; hc <= c1; hc++) { cell(hr, hc, ht, W0 + hc * CW, true, false); }
      label(hr, ht, true);
    }
    for (var lc = c0; lc <= c1; lc++) {                                // the column letters
      out.push('<div class="pg-c pg-letter pg-ft" data-t="' + st + '" data-l="' + (W0 + lc * CW) + '">' + letter(lc + 1) + "</div>");
    }
    out.push('<div class="pg-c pg-letter pg-ft pg-fl" data-t="' + st + '" data-l="' + sl + '">A</div>');
    canvas.innerHTML = out.join("");
    var kids = canvas.children;                                        // placed through the CSSOM (the page's policy allows no inline styles)
    for (var k = 0; k < kids.length; k++) {
      kids[k].style.top = kids[k].getAttribute("data-t") + "px";
      kids[k].style.left = kids[k].getAttribute("data-l") + "px";
    }
  }
  scroll.addEventListener("scroll", function () { if (!raf) { raf = window.requestAnimationFrame(draw); } }, { passive: true });
  window.addEventListener("resize", function () { if (!raf) { raf = window.requestAnimationFrame(draw); } });

  canvas.addEventListener("click", function (e) {
    var t = e.target.closest("[data-r]");
    if (t) { select(+t.dataset.r, +t.dataset.c); draw(); }
  });
  scroll.addEventListener("keydown", function (e) {
    var m = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }[e.key];
    if (!m || !S.data) { return; }
    e.preventDefault();
    var r = Math.max(0, Math.min(S.rows - 1, S.sel.r + m[0])), c = Math.max(0, Math.min(S.cols.length, S.sel.c + m[1]));
    select(r, c);
    scrollToSel(false);
    draw();
  });
  function scrollToSel(center) {
    var top = RH + S.sel.r * RH, left = W0 + (S.sel.c - 1) * CW;
    var hdr = RH * (HEAD + 1);
    if (center) { scroll.scrollTop = Math.max(0, top - scroll.clientHeight / 2); return; }
    if (top - scroll.scrollTop < hdr) { scroll.scrollTop = Math.max(0, top - hdr); }
    if (top + RH > scroll.scrollTop + scroll.clientHeight) { scroll.scrollTop = top + RH - scroll.clientHeight; }
    if (S.sel.c > 0 && left - scroll.scrollLeft < W0) { scroll.scrollLeft = Math.max(0, left - W0); }
    if (S.sel.c > 0 && left + CW > scroll.scrollLeft + scroll.clientWidth) { scroll.scrollLeft = left + CW - scroll.clientWidth; }
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
    f.innerHTML = '<table class="pg-param-table">' + rows.map(function (r) { return "<tr><th>" + esc(r[0]) + '</th><td class="num">' + esc(r[1]) + "</td></tr>"; }).join("") + "</table>";
    $("[data-pg-param-steps]").innerHTML = (p.steps || []).map(function (s) { return "<li>" + esc(s) + "</li>"; }).join("");
    $("[data-pg-param-positions]").innerHTML = "<thead><tr><th>Position</th><th>μᵢ</th><th>σᵢ</th><th>cov(Pᵢ, P)</th><th>ρᵢ</th><th>Component VaR</th><th>Component ES</th><th>Standalone VaR</th></tr></thead><tbody>"
      + p.positions.map(function (r) {
        return "<tr><td>" + esc(r.id) + '</td><td class="num">' + money(r.mu) + '</td><td class="num">' + money(r.sigma) + '</td><td class="num">'
          + Number(r.cov).toExponential(4) + '</td><td class="num">' + num(r.rho, 4) + '</td><td class="num">' + money(r.componentVar) + '</td><td class="num">'
          + money(r.componentEs) + '</td><td class="num">' + money(r.standaloneVar) + "</td></tr>";
      }).join("") + "</tbody>";
    var c = p.columns, sc = d.scenarios;
    $("[data-pg-param-scenarios]").innerHTML = "<thead><tr><th>#</th><th>Date</th><th>wⱼ</th><th>vⱼ − μ</th><th>(vⱼ − μ)²</th></tr></thead><tbody>"
      + c.w.map(function (w, j) {
        return "<tr><td>" + (j + 1) + "</td><td>" + esc(sc[j]) + '</td><td class="num">' + num(w, 8) + '</td><td class="num">' + money(c.dev[j])
          + '</td><td class="num">' + Number(c.sq[j]).toExponential(4) + "</td></tr>";
      }).join("") + "</tbody>";
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
    var c = $("[data-pg-ccy]");
    if (!c.options.length) {
      fetch("/api/var/currencies", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : { listed: [] }; }).then(function (x) {
        var all = (x.listed || []).concat((x.available || []).filter(function (a) { return (x.listed || []).indexOf(a) < 0; }));
        if (all.indexOf(d.currency) < 0) { all.unshift(d.currency); }
        all.forEach(function (a) { var o = document.createElement("option"); o.value = a; o.textContent = a; c.appendChild(o); });
        c.value = d.currency;
      });
    } else { c.value = d.currency; }
  }
  function fill(sel, list, cur) {
    sel.innerHTML = "";
    (list || []).forEach(function (v) { var o = document.createElement("option"); o.value = String(v); o.textContent = confPct(v); sel.appendChild(o); });
    sel.value = String(cur);
  }
  function exportChoices() {
    var sel = $("[data-pg-count]"), ch = S.data["export"].choices;
    sel.innerHTML = "";
    ch.forEach(function (c) {
      var o = document.createElement("option");
      o.value = c.count === 0 ? "all" : String(c.count);
      o.textContent = c.label + (c.refused ? " (cannot be made)" : " · " + c.size);
      o.disabled = !!c.refused;
      if (c.refused) { o.title = c.refused; }
      if (c["default"]) { o.selected = true; }
      sel.appendChild(o);
    });
    estimate();
  }
  function estimate() {
    var sel = $("[data-pg-count]"), ch = S.data["export"].choices[sel.selectedIndex];
    if (!ch) { return; }
    $("[data-pg-estimate]").textContent = ch.refused ? ch.refused : "Workbook of " + ch.positions.toLocaleString("en-US") + " positions" + (S.data.total > ch.positions
      ? " (the other " + (S.data.total - ch.positions).toLocaleString("en-US") + " in Rest of scope)" : "") + ": about " + ch.size + ", " + ch.layout + ", recalculates in about "
      + ch.recalc + "; opens in " + ch.apps.join(", ") + ".";
  }
  $("[data-pg-count]").addEventListener("change", estimate);
  $("[data-pg-export]").addEventListener("click", function () {
    var q = params();
    q.set("count", $("[data-pg-count]").value);
    window.location.href = "/api/play/export.xlsx?" + q.toString();
  });
  $("[data-pg-copy]").addEventListener("click", function () {
    var t = $("[data-pg-formula]").textContent;
    if (navigator.clipboard) { navigator.clipboard.writeText(t); }
  });
  $("[data-pg-horizon]").addEventListener("change", function (e) {
    var v = e.target.value, d = root.dataset;
    d.h = v === "1d" ? "" : "10d";
    d.m = v === "10d-sum" ? "sum" : v === "10d-sqrt10" ? "sqrt10" : "";
    remember(); load();
  });
  $("[data-pg-q]").addEventListener("change", function (e) { root.dataset.q = e.target.value; remember(); load(); });
  $("[data-pg-esq]").addEventListener("change", function (e) { root.dataset.esq = e.target.value; remember(); load(); });
  $("[data-pg-ccy]").addEventListener("change", function (e) { root.dataset.ccy = e.target.value; remember(); load(); });
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
