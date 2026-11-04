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
/* The Exposure Playground (terminal/exposure_play.html; docs/guides/EXPOSURE_GUIDE.md section 29), PLAY EXP <netting set>: the choices,
   the headline figures and the profile per primary date, the first paths at a date with every collateral step, the trades' shares of EE
   at the contribution date, the formula bar (the workbook's formula of the column selected), the explanation and Export to Excel with
   each choice's size before it is made. Every figure is the server's (/api/exposure-play/grid, from /api/v1/exposure/playground as the
   person): this file only draws and formats. */
(function () {
  "use strict";
  var root = document.querySelector("[data-exp-play]");
  if (!root || !root.dataset.ns) { return; }
  var ns = root.dataset.ns;
  function $(s) { return root.querySelector(s); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function num(v, d) {
    if (v == null || isNaN(v)) { return "—"; }
    var x = Number(v);
    var small = Math.abs(x) < 1000;
    return (x < 0 ? "−" : "") + Math.abs(x).toLocaleString("en-US", { minimumFractionDigits: 0, maximumFractionDigits: d != null ? d : small ? 4 : 0 });
  }
  function pct(q) { return (Math.round(q * 10000) / 100) + "%"; }
  var state = { q: "", rule: "", collateral: "", date: "", at: "", trades: "", data: null };

  function query(extra) {
    var q = new URLSearchParams();
    q.set("nettingSet", ns);
    var asOf = new URLSearchParams(window.location.search).get("asOf");
    if (asOf) { q.set("asOf", asOf); }
    ["q", "rule", "collateral", "date", "at"].forEach(function (k) { if (state[k]) { q.set(k, state[k]); } });
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
    var n = $("[data-xp-notice]");
    n.hidden = !text;
    n.textContent = text || "";
    n.classList.toggle("bad", !!bad);
  }
  function formula(ref, key) {
    var f = state.data && state.data.formulas ? state.data.formulas[key] : null;
    $("[data-xp-ref]").textContent = ref;
    $("[data-xp-formula]").textContent = f || "a value of Data, as the simulator delivered it";
  }

  function figures(a) {
    var f = a.figures;
    var tiles = [["CE", f.ce, "today", "ce"], ["EPE", f.epe, "T = " + num(f.horizonYears, 4) + " y", "epe"], ["EEPE", f.eepe, "regulatory", "eepe"],
      ["Peak EE", f.peakEe, f.peakEeTenor, "ee"]];
    (f.peakPfe || []).forEach(function (p) { tiles.push(["Peak PFE " + pct(p.q), p.peakPfe, p.tenor, "peakPfe"]); });
    tiles.push(["EAD_IMM", f.eadImm, "alpha " + f.alpha + " × EEPE, informational", "eadImm"]);
    $("[data-xp-figures]").innerHTML = tiles.map(function (t) {
      return '<div class="xp-fig" data-key="' + t[3] + '" tabindex="0"><span class="k">' + esc(t[0]) + '</span><span class="v">' + num(t[1]) + '</span> <span class="s">'
        + esc(t[2] || "") + "</span></div>";
    }).join("");
  }
  function profile(a) {
    var qs = a.choices.confidences;
    var h = '<thead><tr><th class="l">Date</th><th data-key="t">t</th><th data-key="dt">Δt</th><th data-key="ee">EE</th><th data-key="ene">ENE</th>';
    qs.forEach(function (q) { h += '<th data-key="pfe">PFE ' + pct(q) + "</th>"; });
    h += '<th data-key="eee">EEE</th><th data-key="eeUncollateralised">EE uncoll.</th><th data-key="collateralEffect">Collateral effect</th><th data-key="weight">EPE weight</th></tr></thead><tbody>';
    a.profile.forEach(function (r) {
      h += '<tr><td class="l mono">' + esc(r.tenor) + ' <span class="muted">' + esc(r.date) + "</span></td><td class=\"mono\">" + num(r.t, 4) + '</td><td class="mono">' + num(r.dt, 4)
        + '</td><td class="mono">' + num(r.ee) + '</td><td class="mono">' + num(r.ene) + "</td>";
      r.pfe.forEach(function (v) { h += '<td class="mono">' + num(v) + "</td>"; });
      h += '<td class="mono">' + num(r.eee) + '</td><td class="mono">' + num(r.eeUncollateralised) + '</td><td class="mono">' + num(r.collateralEffect) + '</td><td class="mono">'
        + num(r.weight, 4) + "</td></tr>";
    });
    $("[data-xp-profile]").innerHTML = h + "</tbody>";
  }
  function paths(a) {
    var t = a.pathTable;
    var h = '<thead><tr><th class="l">Path</th><th data-key="v">V</th><th data-key="l">L (look-back)</th><th data-key="target">target</th><th data-key="rounded">rounded</th>'
      + '<th data-key="c">C</th><th data-key="e">E</th><th data-key="n">N</th><th data-key="uncollateralised">max(V, 0)</th></tr></thead><tbody>';
    t.rows.forEach(function (r) {
      h += '<tr><td class="l mono">' + r.path + "</td>" + ["v", "l", "target", "rounded", "c", "e", "n", "uncollateralised"].map(function (k) {
        return '<td class="mono">' + num(r[k]) + "</td>";
      }).join("") + "</tr>";
    });
    $("[data-xp-paths]").innerHTML = h + "</tbody>";
    $("[data-xp-paths-note]").textContent = "the first " + t.shown + " of the " + t.of + " paths at " + t.tenor + "; the figures use every one of them";
  }
  function contributions(a) {
    var c = a.contributions;
    var h = '<thead><tr><th class="l">Trade</th><th data-key="mtm0">Today\'s MTM</th><th data-key="eeShare">EE share</th><th>% of EE</th></tr></thead><tbody>';
    c.rows.forEach(function (r) {
      h += "<tr" + (r.rest ? ' class="xp-rest"' : "") + '><td class="l mono">' + esc(r.label) + '</td><td class="mono">' + num(r.mtm0) + '</td><td class="mono">' + num(r.eeShare)
        + '</td><td class="mono">' + num(r.pct, 1) + "</td></tr>";
    });
    $("[data-xp-contrib]").innerHTML = h + "</tbody>";
    $("[data-xp-contrib-note]").textContent = "EE at " + c.tenor + " is " + num(c.ee) + "; the shares add up to " + num(c.sum) + " (Euler: each trade keeps its value less its share "
      + "of the collateral on the paths with exposure); the largest " + c.shown + " of " + c.of + " shown, a negative share is a hedge";
  }
  function selects(a) {
    var ch = a.choices;
    $("[data-xp-q]").value = ch.confidences.join(",");
    $("[data-xp-rule]").value = ch.rankRule;
    $("[data-xp-collateral]").value = ch.collateralModel;
    var d = $("[data-xp-date]");
    d.innerHTML = '<option value="peak">peak EE (' + esc(a.figures.peakEeTenor) + ")</option>" + ch.tenors.map(function (t) { return "<option>" + esc(t) + "</option>"; }).join("");
    d.value = ch.contributionPeak ? "peak" : ch.contributionDate;
    var at = $("[data-xp-at]");
    at.innerHTML = ch.tenors.map(function (t) { return "<option>" + esc(t) + "</option>"; }).join("");
    at.value = a.pathTable.tenor;
    var tr = $("[data-xp-trades]");
    tr.innerHTML = a.export.estimates.map(function (e) {
      return '<option value="' + esc(e.choice) + '"' + (e.refused ? " disabled" : "") + ">" + esc(e.choice) + (e.restOfSet ? " + Rest of set" : "") + " · " + esc(e.size) + "</option>";
    }).join("");
    var def = a.export.estimates.filter(function (e) { return e.choice === (state.trades || a.export["default"]) && !e.refused; })[0]
      || a.export.estimates.filter(function (e) { return !e.refused; })[0];
    if (def) { tr.value = def.choice; }
    estimateNote();
  }
  function estimateNote() {
    var a = state.data;
    if (!a) { return; }
    var e = a.export.estimates.filter(function (x) { return x.choice === $("[data-xp-trades]").value; })[0];
    $("[data-xp-estimate]").textContent = e ? (e.refused ? e.refused : e.cells.toLocaleString("en-US") + " cells, " + e.size + ", recalculates in " + e.recalc + "; opens in "
      + e.apps.join(", ")) : "";
  }
  function explain(a) {
    $("[data-xp-explain]").innerHTML = (a.explainSteps || []).map(function (s, i) {
      return '<details data-step="' + i + '"' + (i === 0 ? " open" : "") + "><summary>" + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>";
    }).join("");
  }
  function meta(a) {
    $("[data-xp-meta]").textContent = [a.nettingSet, a.counterparty, a.businessDate, "run " + a.runId + (a.calibration ? " (" + a.calibration + ")" : ""),
      a.paths + " of " + a.runPaths + " paths", a.setTrades + " trades"].join(" · ");
    var rf = a.runFigures || {};
    var keys = Object.keys(rf);
    notice(keys.length ? "The figures are of the first " + a.paths + " paths; over all " + a.runPaths + ": " + keys.map(function (k) { return k + " " + num(rf[k]); }).join(", ") : "", false);
  }

  function load() {
    $("[data-xp-meta]").textContent = "loading…";
    getJson("/api/exposure-play/grid?" + query().toString()).then(function (a) {
      state.data = a;
      meta(a);
      figures(a);
      profile(a);
      paths(a);
      contributions(a);
      selects(a);
      explain(a);
    }).catch(function (e) {
      $("[data-xp-meta]").textContent = "";
      notice(e.message, true);
    });
  }

  root.addEventListener("click", function (ev) {
    var tab = ev.target.closest("[data-xp-tab]");
    if (tab) {
      root.querySelectorAll("[data-xp-tab]").forEach(function (b) { b.setAttribute("aria-selected", String(b === tab)); });
      root.querySelectorAll("[data-xp-pane]").forEach(function (p) { p.hidden = p.dataset.xpPane !== tab.dataset.xpTab; });
      return;
    }
    var k = ev.target.closest("[data-key]");
    if (k) {
      root.querySelectorAll(".sel").forEach(function (x) { x.classList.remove("sel"); });
      k.classList.add("sel");
      formula(k.textContent.trim().split("\n")[0], k.dataset.key);
      return;
    }
    if (ev.target.closest("[data-xp-export]")) {
      var q = query({ trades: $("[data-xp-trades]").value });
      q.delete("at");
      window.location.href = "/api/exposure-play/export.xlsx?" + q.toString();
    }
  });
  root.addEventListener("change", function (ev) {
    var t = ev.target;
    if (t.matches("[data-xp-trades]")) { state.trades = t.value; estimateNote(); return; }
    if (t.matches("[data-xp-q]")) { state.q = t.value.trim(); }
    if (t.matches("[data-xp-rule]")) { state.rule = t.value; }
    if (t.matches("[data-xp-collateral]")) { state.collateral = t.value; }
    if (t.matches("[data-xp-date]")) { state.date = t.value; }
    if (t.matches("[data-xp-at]")) { state.at = t.value; }
    load();
  });
  load();
})();
