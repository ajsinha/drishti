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
/* SA-CCR (terminal/saccr.html; docs/guides/SACCR_GUIDE.md sections 7 and 8). The page (SACCR <scope>): one answer of the server
   (/api/saccr/scope) drawn three ways: A the EAD waterfall of the selected netting set with its hedging sets and the formula with its
   figures, B every netting set with the selected one's breakdown, C a counterparty's sets side by side. The playground (PLAY SACCR): the
   grid of each trade's terms with the formula bar and the explanation, the two switches, Export to Excel. Every figure is the server's,
   the waterfall's steps included: this file only draws. */
(function () {
  "use strict";
  var root = document.querySelector("[data-saccr]");
  if (!root) { return; }
  var mode = root.dataset.mode, scope = root.dataset.scope, view = root.dataset.view || "A";
  var state = { data: null, selected: null, filter: "all", sw: {} };
  function $(s) { return root.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(root.querySelectorAll(s)); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) {
    if (v == null || isNaN(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { maximumFractionDigits: 0 });
  }
  function num(v, d) { return v == null || isNaN(v) ? "—" : Number(v).toLocaleString("en-US", { maximumFractionDigits: d == null ? 6 : d }); }
  function query(extra) {
    var q = new URLSearchParams();
    q.set("scope", scope);
    var asOf = new URLSearchParams(window.location.search).get("asOf");
    if (asOf) { q.set("asOf", asOf); }
    Object.keys(state.sw).forEach(function (k) { q.set(k, state.sw[k]); });
    Object.keys(extra || {}).forEach(function (k) { if (extra[k]) { q.set(k, extra[k]); } });
    return q;
  }
  function getJson(url) {
    return fetch(url, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { throw new Error((b.code ? b.code + " " : "") + (b.detail || r.status)); }
        return b;
      });
    });
  }
  function notice(text, bad) {
    var n = $("[data-sc-notice]");
    n.hidden = !text;
    n.textContent = text || "";
    n.classList.toggle("bad", !!bad);
  }
  function meta(a) {
    var t = a.totals || {};
    $("[data-sc-meta]").textContent = [a.scope, a.kind, a.businessDate || "the hand-built cases", a.regulation, t.NETTING_SETS + " netting set" + (t.NETTING_SETS === 1 ? "" : "s"),
      "USD"].filter(Boolean).join(" · ");
    var warn = [];
    if (t.UNCLASSIFIED_TRADES) { warn.push(t.UNCLASSIFIED_TRADES + " live derivative" + (t.UNCLASSIFIED_TRADES === 1 ? "" : "s") + " without a classification row: the set is incomplete and its EAD leaves them out"); }
    notice(warn.join(" · "), warn.length > 0);
  }

  // ---- the waterfall (A, and each column of C) ------------------------------------------------------------------------------------
  function waterfall(steps, small) {
    if (!steps || !steps.length) { return '<p class="muted">no derivative: SA-CCR has nothing to measure</p>'; }
    var top = 0;
    steps.forEach(function (s) { top = Math.max(top, s.to, s.from); });
    var h = '<div class="sc-wf' + (small ? " small" : "") + '" role="img" aria-label="From RC to EAD">';
    steps.forEach(function (s) {
      var lo = Math.min(s.from, s.to), hi = Math.max(s.from, s.to);
      var b = top > 0 ? (lo / top) * 100 : 0, ht = top > 0 ? ((hi - lo) / top) * 100 : 0;
      h += '<div class="sc-step ' + esc(s.role) + '" title="' + esc(s.label) + ": " + money(s.value) + '"><span class="sc-val mono">' + num(s.to / 1e6, 2)
        + '</span><div class="sc-bar"><i style="bottom:' + b.toFixed(2) + "%;height:" + Math.max(ht, 0.6).toFixed(2) + '%"></i></div><span class="sc-lab">'
        + esc(s.label) + "</span></div>";
    });
    return h + "</div>";
  }
  function tiles(a) {
    var t = a.totals || {}, sel = a.selected || {};
    var one = (a.sets || []).length === 1;
    var h = "";
    function tile(k, v, s, cls) { h += '<div class="tile' + (cls ? " " + cls : "") + '"><div class="k">' + esc(k) + '</div><div class="v mono">' + v + '</div><div class="s">' + esc(s || "") + "</div></div>"; }
    if (one && sel.RC != null) {
      tile("Replacement cost (RC)", money(sel.RC), (sel.formula || [])[0]);
      tile("Aggregate add-on", money(sel.ADD_ON), (sel.classes || []).length + " asset class" + ((sel.classes || []).length === 1 ? "" : "es"));
      tile("Multiplier", num(sel.MULTIPLIER, 6), sel.MULTIPLIER < 1 ? "below 1: V − C is negative" : "floor not reached: V − C ≥ 0");
      tile("PFE", money(sel.PFE), "multiplier × add-on");
      tile("EAD", money(sel.EAD), sel.CAPPED ? "capped at the unmargined EAD" : "1.4 × (RC + PFE)", "ead");
    } else {
      tile("EAD", money(t.EAD), "the sum of " + t.NETTING_SETS + " netting sets", "ead");
      if (a.largest) { tile("Largest set", money(a.largest.EAD), a.largest.nettingSet + " (" + num(a.largest.share * 100, 0) + "%)"); }
      tile("RC + PFE", money((t.RC || 0) + (t.PFE || 0)), "summed over the sets");
      tile("Rows", num(t.ROWS, 0), (t.EXCLUDED_TRADES || 0) + " excluded (SFTs, cash) · " + (t.UNCLASSIFIED_TRADES || 0) + " unclassified");
      tile("RWA (hook)", money(t.RWA), "EAD × risk weight " + num(t.RISK_WEIGHT * 100, 0) + "% (informational)");
    }
    $("[data-sc-tiles]").innerHTML = h;
  }
  function drawA(a) {
    var s = a.selected;
    if (!s) { $("[data-sc-water]").innerHTML = '<p class="muted">no netting set</p>'; return; }
    $("[data-sc-water]").innerHTML = '<h3 class="sc-h">' + esc(s.nettingSet) + " · " + esc(s.counterparty || "") + " · " + esc(s.margining || s.status) + " · " + esc(s.status)
      + "</h3>" + waterfall(s.waterfall, false);
    var h = '<tr><th class="l">Asset class</th><th class="l">Hedging set</th><th class="l">Sub-keys (Σ D)</th><th>Effective notional</th><th>Add-on</th></tr>';
    (s.hedgingSets || []).forEach(function (x) {
      var subs = (x.SUB_KEYS || []).map(function (k) { return esc(k.key) + " " + num(k.D / 1e6, 1) + "m"; }).join(" · ");
      h += '<tr><td class="l">' + esc(x.assetClass) + '</td><td class="l mono">' + esc(x.hedgingSet) + '</td><td class="l small">' + subs + '</td><td class="mono">'
        + (x.EFFECTIVE_NOTIONAL == null ? "—" : money(x.EFFECTIVE_NOTIONAL)) + '</td><td class="mono">' + money(x.ADD_ON) + "</td></tr>";
    });
    (s.classes || []).forEach(function (c) {
      h += '<tr class="tot"><td class="l" colspan="4">' + esc(c.label) + ": class add-on (after the multiplier " + money(c.AFTER_MULTIPLIER) + ')</td><td class="mono">' + money(c.ADD_ON) + "</td></tr>";
    });
    $("[data-sc-hs]").innerHTML = h;
    var f = "<h3>The formula, with this set's figures</h3><pre class=\"sc-formula\">" + (s.formula || []).map(esc).join("\n") + "</pre>";
    f += '<p class="muted">' + esc(s.collateralNote || "") + "</p>";
    if ((s.flags || []).length) { f += '<p class="bad">' + esc(s.flags.join(", ")) + "</p>"; }
    if ((s.unclassifiedTrades || []).length) { f += '<p class="bad">unclassified: ' + esc(s.unclassifiedTrades.join(", ")) + "</p>"; }
    f += '<p><a class="pg-link" href="/play/saccr?scope=' + encodeURIComponent("NSET:" + s.nettingSet) + '">Open in the playground</a> · <a class="pg-link" href="/api/saccr/export.xlsx?scope='
      + encodeURIComponent("NSET:" + s.nettingSet) + '" download>Excel</a></p>';
    $("[data-sc-formula]").innerHTML = f;
  }
  function drawB(a) {
    var sets = (a.sets || []).filter(function (x) {
      return state.filter === "all" || (state.filter === "margined" ? x.margining === "margined" : state.filter === "unmargined" ? x.margining === "unmargined"
        : state.filter === "incomplete" ? x.status !== "computed" : true);
    });
    var h = '<tr><th class="l">Netting set</th><th class="l">Counterparty</th><th class="l">Margining</th><th>V</th><th>C</th><th>RC</th><th>Add-on</th><th>Multiplier</th><th>PFE</th><th>EAD</th></tr>';
    sets.forEach(function (x) {
      h += '<tr tabindex="0" data-ns="' + esc(x.nettingSet) + '"' + (a.selected && a.selected.nettingSet === x.nettingSet ? ' class="sel"' : "") + '><td class="l mono">' + esc(x.nettingSet)
        + (x.status !== "computed" ? ' <span class="badge">' + esc(x.status) + "</span>" : "") + '</td><td class="l">' + esc(x.counterparty || "") + '</td><td class="l">'
        + esc(x.margining || "—") + '</td><td class="mono">' + money(x.V) + '</td><td class="mono">' + money(x.C) + '</td><td class="mono">' + money(x.RC) + '</td><td class="mono">'
        + money(x.ADD_ON) + '</td><td class="mono">' + num(x.MULTIPLIER, 3) + '</td><td class="mono">' + money(x.PFE) + '</td><td class="mono b">' + money(x.EAD) + "</td></tr>";
    });
    h += '<tr class="tot"><td class="l" colspan="9">' + sets.length + " netting sets shown · EAD adds over sets</td><td class=\"mono b\">" + money((a.totals || {}).EAD) + "</td></tr>";
    $("[data-sc-sets]").innerHTML = h;
    $$("[data-sc-sets] tr[data-ns]").forEach(function (tr) {
      function pick() { load(tr.dataset.ns); }
      tr.addEventListener("click", pick);
      tr.addEventListener("keydown", function (e) { if (e.key === "Enter") { pick(); } });
    });
    var s = a.selected, d = $("[data-sc-detail]");
    if (!s) { return; }
    var t = "<h3>" + esc(s.nettingSet) + "</h3><table class=\"fr-mini\">";
    [["RC", s.RC], ["Aggregate add-on", s.ADD_ON], ["Multiplier", null, num(s.MULTIPLIER, 6)], ["PFE", s.PFE], ["EAD", s.EAD]].forEach(function (r) {
      t += '<tr><td class="l">' + esc(r[0]) + '</td><td class="mono">' + (r[2] || money(r[1])) + "</td></tr>";
    });
    t += '</table><table class="fr-mini"><tr><th class="l">Asset class</th><th>Add-on</th><th>After multiplier</th></tr>';
    (s.classes || []).forEach(function (c) { t += '<tr><td class="l">' + esc(c.label) + '</td><td class="mono">' + money(c.ADD_ON) + '</td><td class="mono">' + money(c.AFTER_MULTIPLIER) + "</td></tr>"; });
    t += '</table><p><a class="pg-link" href="/saccr?view=A&scope=' + encodeURIComponent("NSET:" + s.nettingSet) + '">Open the waterfall</a> · <a class="pg-link" href="/play/saccr?scope='
      + encodeURIComponent("NSET:" + s.nettingSet) + '">Playground</a></p>';
    d.innerHTML = t;
  }
  function drawC(a) {
    var h = "";
    (a.sets || []).forEach(function (x) {
      h += '<div class="sc-col panel"><h3 class="sc-h mono">' + esc(x.nettingSet) + '</h3><p class="muted">' + esc(x.margining || x.status) + " · V " + num((x.V || 0) / 1e6, 2) + "m · C "
        + num((x.C || 0) / 1e6, 2) + "m</p>" + waterfall(x.waterfall, true) + '<p class="mono small">RC ' + money(x.RC) + " · add-on " + money(x.ADD_ON) + " · multiplier "
        + num(x.MULTIPLIER, 3) + " · PFE " + money(x.PFE) + ' · <b>EAD ' + money(x.EAD) + '</b></p><p><a class="pg-link" href="/saccr?view=A&scope=' + encodeURIComponent("NSET:" + x.nettingSet)
        + '">Waterfall</a></p></div>';
    });
    $("[data-sc-cols]").innerHTML = h || '<p class="muted">no netting set</p>';
  }
  function provenance(a) {
    var h = "";
    Object.keys(a.provenance || {}).forEach(function (k) {
      var v = a.provenance[k];
      h += '<tr><td class="l">' + esc(k) + '</td><td class="l mono">' + esc(typeof v === "object" ? JSON.stringify(v) : v) + "</td></tr>";
    });
    $("[data-sc-prov]").innerHTML = h;
  }
  function draw() {
    var a = state.data;
    meta(a);
    tiles(a);
    drawA(a);
    drawB(a);
    drawC(a);
    provenance(a);
    var play = $("[data-sc-play]");
    if (play && a.selected) { play.href = "/play/saccr?scope=" + encodeURIComponent("NSET:" + a.selected.nettingSet); }
  }
  function load(selected) {
    state.selected = selected || state.selected;
    return getJson("/api/saccr/scope?" + query({ nettingSet: state.selected })).then(function (a) { state.data = a; draw(); })
      .catch(function (e) { notice(e.message, true); $("[data-sc-meta]").textContent = "not answered"; });
  }
  function show(v) {
    view = v;
    $$("[data-sc-tab]").forEach(function (b) { b.setAttribute("aria-selected", b.dataset.scTab === v ? "true" : "false"); });
    $$("[data-sc-view]").forEach(function (s) { s.hidden = s.dataset.scView !== v; });
    var u = new URL(window.location.href);
    u.searchParams.set("view", v);
    window.history.replaceState(null, "", u.toString());
  }

  // ---- the playground ------------------------------------------------------------------------------------------------------------
  var COLS = [["nettingSet", "Netting set", null, "the set"], ["tradeId", "Trade", null, "the trade"], ["driver", "Driver", 0, "0 the primary risk driver"],
    ["assetClass", "Class", null, "ASSET_CLASS"], ["hedgingSet", "Hedging set", null, "HEDGING_SET"], ["subKey", "Sub-key", null, "IR: the bucket of E; FX: the pair; credit, equity: the reference; commodity: the type"],
    ["S", "S", 4, "S = max(START in years, 0)"], ["E", "E", 4, "E = max(END in years, 10 business days)"], ["M", "M", 4, "M = max(MATURITY in years, 10 business days)"],
    ["SD", "SD", 6, "SD = (exp(−0.05 S) − exp(−0.05 E)) / 0.05 (IR, credit; else 1), CRE52.34"], ["d", "d", 0, "d = notional × SD"],
    ["delta", "δ", 6, "δ: ±1; options ±Φ(±d1); tranches ±15 / ((1 + 14 A)(1 + 14 D)), CRE52.38 to CRE52.41"], ["d1", "d1", 6, "d1 = (ln((P + λ)/(K + λ)) + ½ σ² T) / (σ √T)"],
    ["MF_UNMARGINED", "MF unmarg.", 6, "MF = √(min(M, 1)), CRE52.48"], ["MF_MARGINED", "MF marg.", 6, "MF = 1.5 √(MPOR / 250), CRE52.52"],
    ["D_UNMARGINED", "D unmarg.", 0, "D = δ × d × MF"], ["D_MARGINED", "D marg.", 0, "D = δ × d × MF margined"], ["SF", "SF", 4, "the supervisory factor, CRE52.72"]];
  function drawGrid(a) {
    var h = "<tr>" + COLS.map(function (c) { return '<th class="' + (c[2] == null ? "l" : "") + '">' + esc(c[1]) + "</th>"; }).join("") + "</tr>";
    (a.grid || []).forEach(function (r, i) {
      h += "<tr>" + COLS.map(function (c, j) {
        var v = r[c[0]];
        return '<td tabindex="0" data-r="' + i + '" data-c="' + j + '" class="' + (c[2] == null ? "l mono" : "mono") + '">' + (c[2] == null ? esc(v) : c[2] === 0 ? money(v) : num(v, c[2])) + "</td>";
      }).join("") + "</tr>";
    });
    $("[data-sc-grid]").innerHTML = h;
    $$("[data-sc-grid] td").forEach(function (td) {
      td.addEventListener("focus", function () {
        var c = COLS[+td.dataset.c];
        $("[data-sc-ref]").textContent = c[1] + " · row " + (+td.dataset.r + 1);
        $("[data-sc-fx]").textContent = c[3];
      });
    });
    var ex = "<h3>" + esc(a.scope) + " · EAD " + money((a.totals || {}).EAD) + "</h3>";
    if (a.gridShown < a.gridRows) { ex += '<p class="muted">' + a.gridShown + " of " + a.gridRows + " rows shown here; the workbook holds every row</p>"; }
    (a.explainSteps || []).forEach(function (s) { ex += '<details><summary>' + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>"; });
    $("[data-sc-explain]").innerHTML = ex;
  }
  function loadPlay() {
    return getJson("/api/saccr/playground?" + query()).then(function (a) { state.data = a; meta(a); drawGrid(a); })
      .catch(function (e) { notice(e.message, true); $("[data-sc-meta]").textContent = "not answered"; });
  }

  if (mode === "play") {
    $$("[data-sc-sw]").forEach(function (sel) {
      sel.addEventListener("change", function () { state.sw[sel.dataset.scSw] = sel.value; loadPlay(); });
    });
    $("[data-sc-export]").addEventListener("click", function () { window.location.href = "/api/saccr/export.xlsx?" + query().toString(); });
    loadPlay();
  } else {
    $$("[data-sc-tab]").forEach(function (b) { b.addEventListener("click", function () { show(b.dataset.scTab); }); });
    var f = $("[data-sc-filters]");
    f.innerHTML = ["all", "margined", "unmargined", "incomplete"].map(function (k) { return '<button type="button" class="pg-chip" data-f="' + k + '">' + k + "</button>"; }).join(" ");
    $$("[data-sc-filters] [data-f]").forEach(function (b) { b.addEventListener("click", function () { state.filter = b.dataset.f; if (state.data) { drawB(state.data); } }); });
    show(view);
    load();
  }
}());
