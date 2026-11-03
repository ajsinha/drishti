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
/* FVA (terminal/fva.html; docs/guides/FVA_GUIDE.md sections 5 and 6). The screen (FVA <scope>): one answer of /api/fva/scope drawn three
   ways, the decided mock-ups: A the netting set's FVA (tiles, the term bars by future date, the formula, how it was computed), B the
   netting sets by FVA with the selected one's bars, C a counterparty's sets side by side. The playground (PLAY FVA <scope>): the Choices,
   the terms by date with the formula bar, the explanation and Export to Excel. Every figure is the server's: this file only draws. */
(function () {
  "use strict";
  var root = document.querySelector("[data-fva]");
  if (!root) { return; }
  var mode = root.dataset.mode, scope = root.dataset.scope, view = root.dataset.view || "a";
  function $(s) { return root.querySelector(s); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) {
    if (v == null || isNaN(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { maximumFractionDigits: 0 });
  }
  function cents(v) {
    if (v == null || isNaN(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function num(v, d) { return v == null ? "—" : Number(v).toLocaleString("en-US", { maximumFractionDigits: d == null ? 6 : d }); }
  function bp(v) { return v == null ? "—" : num(v * 10000, 1) + " bp"; }
  var state = { choices: {}, data: null, selected: null };

  function query(extra) {
    var q = new URLSearchParams();
    if (scope) { q.set("scope", scope); }
    var asOf = new URLSearchParams(window.location.search).get("asOf");
    if (asOf) { q.set("asOf", asOf); }
    Object.keys(state.choices).forEach(function (k) { if (state.choices[k]) { q.set(k, state.choices[k]); } });
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
    var n = $("[data-fv-notice]");
    n.hidden = !text;
    n.textContent = text || "";
    n.classList.toggle("bad", !!bad);
  }
  var FLAG_WORDS = {
    REAL_WORLD_RUN: "the run is calibrated to history (REAL_WORLD), not to prices: FVA is shown for the demonstration and flagged",
    FUNDING_FALLBACK: "the named funding curve was found nowhere: the flat fallback spread is used",
    FLAT_DISCOUNT: "the discount curve was not found: a flat rate discounts",
    SETS_AGGREGATE: "your role sees some of these netting sets only in aggregate: the total alone",
    PATHS_DISCOUNTED: "the paths are already discounted: DF = 1"
  };
  function meta(a) {
    $("[data-fv-meta]").textContent = [a.label, a.businessDate, "run " + a.runId + " (" + (a.calibration || "calibration not stated") + ")",
      "funding " + (a.settings && a.settings.fundingSpread ? a.settings.fundingSpread.id : ""), "USD"].filter(Boolean).join(" · ");
    notice((a.flags || []).map(function (f) { return FLAG_WORDS[f] || f; }).join(" · "), (a.flags || []).indexOf("REAL_WORLD_RUN") >= 0);
  }
  function tile(k, v, s, cls, lead) {
    return '<div class="fv-tile' + (lead ? " lead" : "") + '"><div class="k">' + esc(k) + '</div><div class="v ' + (cls || "") + '">' + v + "</div>"
      + (s ? '<div class="s">' + esc(s) + "</div>" : "") + "</div>";
  }
  function setTiles(f, extra) {
    var sp = state.data && state.data.epeReading && state.data.epeReading.flatSpread ? bp(state.data.epeReading.spread) + " borrowing spread" : "the funding curve";
    $("[data-fv-tiles]").innerHTML = tile("FVA", money(f.fva), "FCA + FBA", "", true)
      + tile("FCA (funding cost)", money(f.fca), "EE × " + sp, "cost") + tile("FBA (funding benefit)", money(f.fba), "|ENE| × the lending spread", "benefit")
      + tile("CVA", "planned", "joins this engine later, on the same EE") + tile("EPE over the life", money(f.epeLife), "same paths as the exposure engine")
      + (extra || "");
  }

  /* term bars: FBA above the axis, FCA below, one pair per primary date (the server's terms) */
  function bars(dates, fca, fba, w, h) {
    w = w || 760; h = h || 240;
    var n = fca.length, mid = h / 2, max = 0, i;
    for (i = 0; i < n; i++) { max = Math.max(max, Math.abs(fca[i] || 0), Math.abs(fba[i] || 0)); }
    if (!max) { max = 1; }
    var x0 = 34, bw = Math.max(2, (w - x0 - 10) / Math.max(n, 1) - 3), s = (mid - 20) / max;
    var out = '<div class="fv-legend"><span><i style="background:var(--neg,#f2955a)"></i>FCA (funding cost)</span><span><i style="background:var(--pos,#5fd39a)"></i>FBA (funding benefit)</span></div>'
      + '<div class="fv-chart"><svg viewBox="0 0 ' + w + " " + h + '" role="img" aria-label="FCA and FBA by future date">';
    for (i = 0; i < n; i++) {
      var x = x0 + i * (bw + 3), hc = Math.abs(fca[i] || 0) * s, hb = Math.abs(fba[i] || 0) * s;
      out += '<rect class="fca" x="' + x.toFixed(1) + '" y="' + mid + '" width="' + bw.toFixed(1) + '" height="' + hc.toFixed(1) + '"><title>' + esc(dates[i])
        + ": FCA " + cents(fca[i]) + "</title></rect>";
      out += '<rect class="fba" x="' + x.toFixed(1) + '" y="' + (mid - hb).toFixed(1) + '" width="' + bw.toFixed(1) + '" height="' + hb.toFixed(1) + '"><title>'
        + esc(dates[i]) + ": FBA " + cents(fba[i]) + "</title></rect>";
      if (n <= 12 || i % Math.ceil(n / 10) === 0) {
        out += '<text x="' + (x + bw / 2).toFixed(1) + '" y="' + (h - 4) + '" text-anchor="middle">' + esc(dates[i]) + "</text>";
      }
    }
    out += '<line class="axis" x1="' + (x0 - 4) + '" x2="' + (w - 6) + '" y1="' + mid + '" y2="' + mid + '"/>'
      + '<text x="4" y="12">benefit above · cost below · per period, discounted, USD</text></svg></div>';
    return out;
  }
  function dates() { return (state.data.terms || []).map(function (t) { return t.tenor || t.date; }); }
  function setOf(id) { return (state.data.nettingSets || []).filter(function (s) { return s.nettingSet === id; })[0]; }

  /* view A: one netting set (the scope's own, or the one selected in B or C) */
  function viewA() {
    var a = state.data, s = state.selected ? setOf(state.selected) : null;
    if (!s && (a.nettingSets || []).length === 1) { s = a.nettingSets[0]; }
    var f = s || a.figures, fcaT = s ? s.fcaTerms : a.terms.map(function (t) { return t.fca; }), fbaT = s ? s.fbaTerms : a.terms.map(function (t) { return t.fba; });
    setTiles(f);
    $("[data-fv-a-title]").textContent = (s ? s.nettingSet + " · " + (s.csa || "") : a.label) + ": FVA by future date";
    $("[data-fv-a-chart]").innerHTML = bars(dates(), fcaT, fbaT);
    var st = a.settings || {}, sp = st.fundingSpread || {};
    $("[data-fv-formula]").innerHTML = "FCA = −Σ EE(t)·DF(t)·s<sub>B</sub>(t)·Δt = <b>" + cents(f.fca) + "</b><br>FBA = Σ |ENE(t)|·DF(t)·s<sub>L</sub>(t)·Δt = <b>"
      + cents(f.fba) + "</b><br>FVA = FCA + FBA = <b>" + cents(f.fva) + "</b><br>s<sub>B</sub>: " + esc(sp.id) + " (" + esc(sp.source) + ")"
      + (st.discountCurve ? "<br>DF: " + esc(st.discountCurve.id) + " (" + esc(st.discountCurve.source) + ")" : "<br>DF = 1 (the paths are discounted)")
      + "<br>EE point " + esc(st.eePoint) + " · FBA " + (st.fba ? "on" : "off") + " · survival " + (st.fundingSurvival ? "on" : "off");
    var p = a.provenance || {};
    $("[data-fv-prov]").innerHTML = '<table class="fr-mini"><tr><td class="l">Exposure</td><td class="mono">run ' + esc(p.exposure && p.exposure.runId) + ", "
      + esc(p.exposure && p.exposure.paths) + " paths, " + esc(p.exposure && p.exposure.primaryDates) + " primary dates, collateral " + esc(p.exposure && p.exposure.collateralModel)
      + '</td></tr><tr><td class="l">Engine</td><td>' + esc(p.engine) + '</td></tr><tr><td class="l">Computed</td><td class="mono">' + esc(p.computeMs) + " ms"
      + (p.cached ? " (the exposure answer cached)" : "") + '</td></tr></table><p><a class="pg-link" href="/play/fva?scope=' + encodeURIComponent(s ? "NSET:" + s.nettingSet : a.scope)
      + '">Open in the playground</a> · <a class="pg-link" href="/exposure?scope=' + encodeURIComponent(s ? "NSET:" + s.nettingSet : a.scope) + '">The exposure</a></p>';
  }
  /* view B: the netting sets by FVA */
  function viewB() {
    var a = state.data;
    setTiles(a.figures, tile("Netting sets", num(a.nettingSetCount, 0), a.setsShown ? "sorted by FVA, the costliest first" : "in aggregate only"));
    var h = '<tr><th class="l">Netting set</th><th class="l">Counterparty</th><th class="l">CSA</th><th>EPE life</th><th>FCA</th><th>FBA</th><th>FVA</th><th>CVA</th></tr>';
    (a.nettingSets || []).forEach(function (s) {
      h += '<tr data-ns="' + esc(s.nettingSet) + '" class="' + (state.selected === s.nettingSet ? "sel" : "") + '"><td class="l"><a href="#" data-pick="' + esc(s.nettingSet) + '">'
        + esc(s.nettingSet) + '</a></td><td class="l"><a href="/fva?scope=' + encodeURIComponent("CPTY:" + s.counterparty) + '&view=c">' + esc(s.counterparty) + "</a></td>"
        + '<td class="l muted">' + esc(s.csa) + '</td><td class="mono">' + money(s.epeLife) + '</td><td class="mono">' + money(s.fca) + '</td><td class="mono">' + money(s.fba)
        + '</td><td class="mono"><b>' + money(s.fva) + '</b></td><td class="muted">planned</td></tr>';
    });
    h += '<tr class="tot"><td class="l">Total</td><td></td><td></td><td></td><td class="mono">' + money(a.figures.fca) + '</td><td class="mono">' + money(a.figures.fba)
      + '</td><td class="mono"><b>' + money(a.figures.fva) + "</b></td><td></td></tr>";
    $("[data-fv-sets]").innerHTML = h;
    var s = state.selected ? setOf(state.selected) : (a.nettingSets || [])[0];
    $("[data-fv-b-title]").textContent = s ? s.nettingSet + ": by future date" : "No netting set to show";
    $("[data-fv-b-chart]").innerHTML = s ? bars(dates(), s.fcaTerms, s.fbaTerms) : "";
  }
  /* view C: a counterparty's sets side by side */
  function viewC() {
    var a = state.data, sets = a.nettingSets || [], top = a.largest;
    setTiles(a.figures, top ? tile("Largest", esc(top.nettingSet), num(top.share * 100, 0) + "% of FVA") : "");
    $("[data-fv-c-sets]").innerHTML = sets.map(function (s) {
      return '<section class="fv-panel"><h3><a href="#" data-pick="' + esc(s.nettingSet) + '">' + esc(s.nettingSet) + '</a></h3><div class="muted">' + esc(s.csa)
        + " · FVA " + money(s.fva) + " · CVA planned</div>" + bars(dates(), s.fcaTerms, s.fbaTerms, 520, 200) + "</section>";
    }).join("") || '<p class="muted">No netting set of this scope to show.</p>';
  }
  function draw() {
    if (!state.data) { return; }
    root.querySelectorAll("[data-fv-tab]").forEach(function (b) { b.setAttribute("aria-selected", b.dataset.fvTab === view ? "true" : "false"); });
    root.querySelectorAll("[data-fv-pane]").forEach(function (p) { p.hidden = p.dataset.fvPane !== view; });
    ({ a: viewA, b: viewB, c: viewC })[view]();
  }

  /* the playground */
  var COLS = [
    ["k", "k", function (t) { return t.k; }, "the primary date's number"],
    ["date", "Date", function (t) { return t.date; }, "the primary date"],
    ["t", "t", function (t) { return num(t.t, 4); }, "years from the business date"],
    ["dt", "Δt", function (t) { return num(t.dt, 4); }, "Δt_k = t_k − t_(k−1)"],
    ["df", "DF", function (t) { return num(t.df, 6); }, "DF(t_k): log-linear in the discount curve's DF between its points"],
    ["sb", "s_B", function (t) { return num(t.sb, 6); }, "s_B(t_k): the borrowing spread, linear between its points, flat beyond"],
    ["sl", "s_L", function (t) { return num(t.sl, 6); }, "s_L(t_k): the lending spread (the borrowing one when symmetric)"],
    ["survival", "S", function (t) { return t.survival == null ? "1" : num(t.survival, 6); }, "S_C(t_k)·S_B(t_k) with survival weighting, else 1"],
    ["ee", "EE", function (t) { return cents(t.ee); }, "EE(t_k): the exposure engine's expected exposure after the CSA (Data)"],
    ["ene", "ENE", function (t) { return cents(t.ene); }, "ENE(t_k): the expected negative exposure (Data)"],
    ["eeStar", "EE*", function (t) { return cents(t.eeStar); }, "EE*(t_k) = EE(t_k) × DF(t_k)"],
    ["eneStar", "ENE*", function (t) { return cents(t.eneStar); }, "ENE*(t_k) = ENE(t_k) × DF(t_k)"],
    ["fca", "FCA term", function (t) { return cents(t.fca); }, "−EE*(t_k) × s_B(t_k) × S × Δt_k (mid: the average of both ends' EE*)"],
    ["fba", "FBA term", function (t) { return cents(t.fba); }, "|ENE*(t_k)| × s_L(t_k) × S × Δt_k (0 with FBA off)"]
  ];
  function playground() {
    var a = state.data;
    setTiles(a.figures, a.epeReading && a.epeReading.flatSpread ? tile("The EPE reading", money(a.epeReading.product), "spread × life × EPE_life, before discounting") : "");
    var ch = a.choices || {}, h = "";
    ["eePoint", "fba", "survival"].forEach(function (k) {
      var c = ch[k] || {};
      h += '<label class="pg-chip">' + esc({ eePoint: "EE point", fba: "FBA", survival: "Survival weighting" }[k]) + ' <select data-ch="' + k + '">'
        + (c.options || []).map(function (o) { return "<option" + (o === c.value ? " selected" : "") + ">" + esc(o) + "</option>"; }).join("") + "</select></label>";
    });
    h += '<label class="pg-chip">Funding spread <input class="pg-input mono" data-ch="spread" size="18" value="' + esc(state.choices.spread || "") + '" placeholder="'
      + esc(ch.spread ? ch.spread.default : "") + '" title="' + esc(ch.spread ? ch.spread.hint : "") + '"></label>';
    $("[data-fv-choices]").innerHTML = h;
    var g = "<tr><th>Set</th>" + COLS.map(function (c) { return "<th>" + esc(c[1]) + "</th>"; }).join("") + "</tr>";
    (a.terms || []).forEach(function (t, i) {
      g += '<tr><td class="l mono">' + esc(a.scope) + "</td>" + COLS.map(function (c, j) {
        return '<td class="mono fr-cell" tabindex="0" data-cell="' + i + "," + j + '">' + c[2](t) + "</td>";
      }).join("") + "</tr>";
    });
    g += '<tr class="tot"><td class="l">Σ</td>' + COLS.map(function (c) {
      return '<td class="mono">' + (c[0] === "fca" ? cents(a.figures.fca) : c[0] === "fba" ? cents(a.figures.fba) : "") + "</td>";
    }).join("") + "</tr>";
    $("[data-fv-terms]").innerHTML = g;
    $("[data-fv-explain]").innerHTML = "<h3>How FVA is worked out</h3>" + (a.explainSteps || []).filter(function (s) { return !/^Term /.test(s.title); }).map(function (s) {
      return "<details><summary>" + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>";
    }).join("");
  }
  function cell(i, j) {
    var t = state.data.terms[i], c = COLS[j];
    $("[data-fv-ref]").textContent = "Terms!" + c[1] + (i + 5);
    $("[data-fv-fx]").textContent = c[3] + " = " + c[2](t);
  }

  function load() {
    if (!scope) {
      notice("Type a netting set or a counterparty (NSET:NS-..., CPTY:CP-...), or download the example workbook.", false);
      $("[data-fv-meta]").textContent = "";
      return;
    }
    var url = (mode === "play" ? "/api/fva/playground?" : "/api/fva/scope?") + query().toString();
    getJson(url).then(function (a) {
      state.data = a;
      meta(a);
      if (mode === "play") { playground(); } else { draw(); }
    }).catch(function (e) { notice(e.message, true); $("[data-fv-meta]").textContent = ""; });
  }

  root.addEventListener("click", function (e) {
    var tab = e.target.closest("[data-fv-tab]");
    if (tab) { view = tab.dataset.fvTab; draw(); return; }
    var pick = e.target.closest("[data-pick]");
    if (pick) { e.preventDefault(); state.selected = pick.dataset.pick; view = view === "c" ? "a" : view; draw(); return; }
    var td = e.target.closest("[data-cell]");
    if (td) { var p = td.dataset.cell.split(","); cell(+p[0], +p[1]); return; }
    if (e.target.closest("[data-fv-export]")) { var md = $("[data-fv-mode]"); window.location.href = "/api/fva/export.xlsx?" + query({ mode: md ? md.value : "" }).toString(); }
  });
  root.addEventListener("change", function (e) {
    var c = e.target.closest("[data-ch]");
    if (c) { state.choices[c.dataset.ch] = c.value.trim(); load(); }
  });
  load();
})();
