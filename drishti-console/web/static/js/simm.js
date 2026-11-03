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
/* ISDA SIMM (terminal/simm.html; docs/guides/SIMM_GUIDE.md sections 7 and 8). The screen (SIMM): every netting set with IM to collect and
   post (computed, stored and labelled, or why not computed), the selected one's breakdown by product and risk class, and the margin
   against the counterparty's call. The playground (PLAY SIMM): the Choices panel (version, concentration, HVR), the side, a switch compared
   side by side with the difference explained by step, the grid of weighted sensitivities with its formula bar and the explanation, Save
   recipe and Export to Excel; PLAY SIMM RUN <id> replays a recipe. Every figure is the server's: this file only draws. */
(function () {
  "use strict";
  var root = document.querySelector("[data-simm]");
  if (!root) { return; }
  var mode = root.dataset.mode, ns = root.dataset.ns, cpty = root.dataset.cpty, recipe = root.dataset.recipe;
  function $(s) { return root.querySelector(s); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) {
    if (v == null || v === "—" || isNaN(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { minimumFractionDigits: 0, maximumFractionDigits: 0 });
  }
  function num(v, d) { return v == null ? "—" : Number(v).toLocaleString("en-US", { maximumFractionDigits: d == null ? 6 : d }); }
  var state = { choices: {}, overrides: [], version: "", side: "collect", data: null };

  function query(extra) {
    var q = new URLSearchParams();
    if (ns) { q.set("nettingSet", ns); }
    var asOf = new URLSearchParams(window.location.search).get("asOf");
    if (asOf) { q.set("asOf", asOf); }
    if (state.version) { q.set("version", state.version); }
    Object.keys(state.choices).forEach(function (k) { q.set(k, state.choices[k]); });
    state.overrides.forEach(function (o) { q.append("override", o); });
    Object.keys(extra || {}).forEach(function (k) { if (extra[k]) { q.set(k, extra[k]); } });
    return q;
  }
  function getJson(url, opts) {
    return fetch(url, Object.assign({ headers: { Accept: "application/json" }, credentials: "same-origin" }, opts || {})).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { throw new Error((b.code ? b.code + " " : "") + (b.detail || r.status)); }
        return b;
      });
    });
  }
  function notice(text, bad) {
    var n = $("[data-sm-notice]");
    n.hidden = !text;
    n.textContent = text || "";
    n.classList.toggle("bad", !!bad);
  }
  function meta(a) {
    var parts = [a.NETTING_SET || a.SCOPE, a.COUNTERPARTY, a.BUSINESS_DATE || "hand-built examples", a.CALCULATION_CURRENCY, "SIMM " + a.SIMM_VERSION];
    if (a.label) { parts.push(a.label); }
    if (!a.REGULATORY && a.KIND === "netting set") { parts.push("NOT THE REGULATORY FIGURE"); }
    $("[data-sm-meta]").textContent = parts.filter(Boolean).join(" · ");
    notice([a.notRegulatory, a.MISSING_TRADES ? a.MISSING_TRADES + " trades of the netting set have no sensitivities: incomplete" : ""].filter(Boolean).join(" · "),
      !!a.notRegulatory);
  }
  function breakdown(a) {
    var h = "<h3>" + esc(a.NETTING_SET || a.SCOPE) + "</h3><p class=\"mono\">collect " + money(a.IM_COLLECT) + " (" + esc(a.COLLECT_REGIME) + ") · post "
      + money(a.IM_POST) + " (" + esc(a.POST_REGIME) + ")</p><table class=\"fr-mini\"><tr><th class=\"l\">Product class</th><th>Collect</th><th>Post</th></tr>";
    (a.BY_PRODUCT_CLASS || []).forEach(function (p) { h += '<tr><td class="l">' + esc(p.PRODUCT_CLASS) + '</td><td class="mono">' + money(p.COLLECT) + '</td><td class="mono">' + money(p.POST) + "</td></tr>"; });
    h += "</table><table class=\"fr-mini\"><tr><th class=\"l\">Risk class</th><th>Delta</th><th>Vega</th><th>Curv.</th><th>IM</th></tr>";
    (a.BY_RISK_CLASS || []).forEach(function (r) {
      h += '<tr><td class="l">' + esc(r.PRODUCT_CLASS + " · " + r.RISK_CLASS) + '</td><td class="mono">' + money(r.DELTA) + '</td><td class="mono">' + money(r.VEGA)
        + '</td><td class="mono">' + money(r.CURVATURE) + '</td><td class="mono">' + money(r.TOTAL) + "</td></tr>";
    });
    h += "</table><p class=\"muted\">add-ons " + money(a.ADDON_COLLECT) + " · " + esc(a.TRADES) + " records · parameter SHA-256 " + esc(String(a.PARAMETER_SHA256 || "").slice(0, 12))
      + "…</p><p><a class=\"pg-link\" href=\"/play/simm?nettingSet=" + encodeURIComponent(a.NETTING_SET || a.SCOPE) + "\">Open in the playground</a></p>";
    $("[data-sm-detail]").innerHTML = h;
  }

  // ------------------------------------------------------------------------------------------------------------------ the screen
  function select(id) {
    ns = id;
    root.querySelectorAll("[data-sm-sets] tr.sel").forEach(function (t) { t.classList.remove("sel"); });
    var tr = root.querySelector('[data-sm-sets] tr[data-ns="' + CSS.escape(id) + '"]');
    if (tr) { tr.classList.add("sel"); }
    $("[data-sm-detail]").innerHTML = "<p class=\"muted\">loading " + esc(id) + "…</p>";
    getJson("/api/simm?" + query().toString()).then(function (a) { meta(a); breakdown(a); }).catch(function (e) { $("[data-sm-detail]").innerHTML = "<p class=\"bad\">" + esc(e.message) + "</p>"; });
    if (!$("[data-sm-pane=against]").hidden) { against(); }
  }
  function sets() {
    var q = new URLSearchParams();
    if (cpty) { q.set("counterparty", cpty); }
    var asOf = new URLSearchParams(window.location.search).get("asOf");   // the list follows the page's date, as the breakdown does
    if (asOf) { q.set("asOf", asOf); }
    getJson("/api/simm/netting-sets?" + q.toString()).then(function (l) {
      var h = '<thead><tr><th class="l">Netting set</th><th class="l">Counterparty</th><th>IM collect</th><th>IM post</th><th class="l">Version</th><th class="l">Status</th><th>Their call</th></tr></thead><tbody>';
      l.rows.forEach(function (r) {
        h += '<tr data-ns="' + esc(r.nettingSet) + '" tabindex="0"><td class="l mono">' + esc(r.nettingSet) + '</td><td class="l">' + esc(r.counterparty) + '</td><td class="mono">' + money(r.imCollect)
          + '</td><td class="mono">' + money(r.imPost) + '</td><td class="l">' + esc(r.version || "") + '</td><td class="l">' + esc(r.status) + '</td><td class="mono">' + money(r.theirCall) + "</td></tr>";
      });
      h += '<tr class="tot"><td class="l" colspan="2">Sum (' + l.computed + ' computed; SIMM does not net across netting sets)</td><td class="mono">' + money(l.sumCollect) + '</td><td class="mono">'
        + money(l.sumPost) + "</td><td colspan=\"3\"></td></tr>";
      $("[data-sm-sets]").innerHTML = h + "</tbody>";
      $("[data-sm-meta]").textContent = l.date + " · default SIMM " + (l.defaultVersion || "none installed");
      if (l.notice) { notice(l.notice, true); }
      if (ns) { select(ns); }
    }).catch(function (e) { $("[data-sm-meta]").textContent = ""; notice(e.message, true); });
  }
  function against() {
    if (!ns) { $("[data-sm-against]").innerHTML = "<tbody><tr><td>Select a netting set.</td></tr></tbody>"; return; }
    getJson("/api/simm/against?" + query().toString()).then(function (c) {
      var h = '<thead><tr><th class="l">Side</th><th class="l">Risk class</th><th>' + esc((document.body && document.body.dataset.product) || "Ours") + '</th><th>Their call</th><th>Difference</th><th class="l">Status</th></tr></thead><tbody>';
      c.rows.forEach(function (r) {
        h += '<tr class="tot"><td class="l">' + esc(r.direction) + '</td><td class="l">total</td><td class="mono">' + money(r.drishti) + '</td><td class="mono">' + money(r.theirs)
          + '</td><td class="mono">' + money(r.difference) + '</td><td class="l">' + esc(r.status) + "</td></tr>";
        r.byRiskClass.forEach(function (x) {
          h += '<tr><td></td><td class="l">' + esc(x.riskClass) + '</td><td class="mono">' + money(x.drishti) + '</td><td class="mono">' + money(x.theirs) + '</td><td class="mono">'
            + money(x.difference) + "</td><td></td></tr>";
        });
      });
      $("[data-sm-against]").innerHTML = h + "</tbody>";
      if (c.note) { notice(c.note); }
    }).catch(function (e) { notice(e.message, true); });
  }
  function screen() {
    sets();
    root.addEventListener("click", function (ev) {
      var tr = ev.target.closest("[data-sm-sets] tr[data-ns]");
      if (tr) { select(tr.dataset.ns); }
      var tab = ev.target.closest("[data-sm-tab]");
      if (tab) {
        root.querySelectorAll("[data-sm-tab]").forEach(function (t) { t.setAttribute("aria-selected", String(t === tab)); });
        root.querySelectorAll("[data-sm-pane]").forEach(function (p) { p.hidden = p.dataset.smPane !== tab.dataset.smTab; });
        if (tab.dataset.smTab === "against") { against(); }
      }
    });
    root.addEventListener("keydown", function (ev) {
      var tr = ev.target.closest && ev.target.closest("[data-sm-sets] tr[data-ns]");
      if (tr && ev.key === "Enter") { select(tr.dataset.ns); }
    });
  }

  // ------------------------------------------------------------------------------------------------------------ the playground
  function choices(list) {
    $("[data-sm-choices]").innerHTML = '<h3>Choices <span>the parameter version and every method choice; the default is the version\'s own</span></h3>' + list.map(function (s) {
      var opts = s.options.length ? s.options : [s.value];
      return '<label class="fr-switch' + (s.name !== "simm-parameters" && s.value !== s.default ? " changed" : "") + '"><b class="mono">' + esc(s.name) + "</b> <select data-sm-sw=\"" + esc(s.name) + "\">"
        + opts.map(function (o) { return "<option" + (o === s.value ? " selected" : "") + ">" + esc(o) + "</option>"; }).join("")
        + '</select> <span class="muted">default ' + esc(s.default) + " · " + esc(s.rule) + "</span><small>" + esc(s.why) + "</small></label>";
    }).join("");
    var cmp = $("[data-sm-compare]"), keep = cmp.value;
    cmp.innerHTML = '<option value="">(none)</option>' + list.map(function (s) {
      return s.options.filter(function (o) { return o !== s.value; }).map(function (o) { return '<option value="' + esc(s.name + "=" + o) + '">' + esc(s.name + ": " + o) + "</option>"; }).join("");
    }).join("");
    cmp.value = keep;
  }
  function diff(d) {
    var box = $("[data-sm-diff]");
    if (!d) { box.hidden = true; return; }
    box.hidden = false;
    box.innerHTML = "<h3>" + esc(d.label) + "</h3><p>" + esc(d.words) + "</p><div class=\"fr-side\"><div><b>This run</b><span class=\"mono\">" + money(d.base)
      + "</span></div><div><b>With the switch</b><span class=\"mono\">" + money(d.alternative) + "</span></div><div><b>Difference</b><span class=\"mono\">" + money(d.difference)
      + "</span></div></div><table class=\"fr-mini\"><tr><th class=\"l\">Class</th><th>This run</th><th>With the switch</th><th>Difference</th><th class=\"l\">Bucket</th><th class=\"l\">Where it arises</th></tr>"
      + d.lines.map(function (l) {
        return '<tr><td class="l">' + esc(l.productClass + " · " + l.riskClass + " " + l.measure.toLowerCase()) + '</td><td class="mono">' + money(l.base) + '</td><td class="mono">'
          + money(l.alternative) + '</td><td class="mono">' + money(l.difference) + '</td><td class="l">' + esc(l.bucket || "") + '</td><td class="l">' + esc(l.step) + "</td></tr>";
      }).join("") + "</table>";
  }
  var STEP = { DELTA: 8, VEGA: 11, CURVATURE: 13, BASE_CORR: 14 };     // the Explain entries of steps 4, 7, 9 and 10
  function grid(a) {
    var rows = a.grid || [];
    var h = '<thead><tr><th class="l">Product</th><th class="l">Risk class</th><th class="l">Measure</th><th class="l">Bucket</th><th class="l">Factor</th><th>s / VR / CVR</th><th>RW</th><th>HVR</th><th>CR</th><th>WS</th><th>K_b</th></tr></thead><tbody>';
    rows.forEach(function (r, i) {
      h += '<tr data-i="' + i + '" tabindex="0"><td class="l">' + esc(r.productClass) + '</td><td class="l">' + esc(r.riskClass) + '</td><td class="l">' + esc(r.measure.toLowerCase())
        + '</td><td class="l">' + esc(r.bucket) + '</td><td class="l mono">' + esc(r.factor) + '</td><td class="mono">' + money(r.s) + '</td><td class="mono">' + num(r.rw, 6)
        + '</td><td class="mono">' + num(r.hvr, 4) + '</td><td class="mono">' + num(r.cr, 4) + '</td><td class="mono">' + money(r.ws) + '</td><td class="mono">' + money(r.K) + "</td></tr>";
    });
    if ((a.factors || 0) > rows.length) { h += '<tr><td class="l" colspan="11">' + (a.factors - rows.length) + " more factors: see the workbook</td></tr>"; }
    $("[data-sm-ws]").innerHTML = h + "</tbody>";
    $("[data-sm-explain]").innerHTML = (a.explainSteps || []).map(function (s, i) { return '<details data-step="' + i + '"><summary>' + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>"; }).join("");
  }
  function pick(i) {
    var r = (state.data.grid || [])[i];
    if (!r) { return; }
    root.querySelectorAll("[data-sm-ws] tr.sel").forEach(function (t) { t.classList.remove("sel"); });
    var tr = root.querySelector('[data-sm-ws] tr[data-i="' + i + '"]');
    if (tr) { tr.classList.add("sel"); }
    $("[data-sm-ref]").textContent = r.factor;
    $("[data-sm-formula]").textContent = r.formula;
    var steps = root.querySelectorAll("[data-sm-explain] details"), want = STEP[r.measure] || 8;
    steps.forEach(function (d, k) { d.open = k === want; });
    if (steps[want]) { steps[want].scrollIntoView({ block: "nearest" }); }
  }
  function play() {
    var url = recipe ? "/api/simm/recipes/" + encodeURIComponent(recipe) : "/api/simm/playground?" + query({ side: state.side, compare: $("[data-sm-compare]").value }).toString();
    $("[data-sm-meta]").textContent = "loading…";
    getJson(url).then(function (a) {
      state.data = a;
      if (recipe) {
        ns = a.SCOPE;
        (a.choices || []).forEach(function (s) { if (s.name !== "simm-parameters") { state.choices[s.name] = s.value; } });
      }
      meta(a);
      if (recipe) { notice(a.reproduced + (a.recipe ? " (recipe " + a.recipe.id + ", saved " + a.recipe.created + ")" : ""), !/^reproduced/.test(a.reproduced)); }
      choices(a.choices || []);
      diff(a.compare);
      grid(a);
    }).catch(function (e) { $("[data-sm-meta]").textContent = ""; notice(e.message, true); });
  }
  function playground() {
    if (!ns && !recipe) { $("[data-sm-meta]").textContent = ""; return; }
    play();
    root.addEventListener("change", function (ev) {
      var sw = ev.target.closest("[data-sm-sw]");
      if (sw) {
        if (sw.dataset.smSw === "simm-parameters") { state.version = sw.value; } else { state.choices[sw.dataset.smSw] = sw.value; }
        recipe = "";
        play();
      }
      if (ev.target.matches("[data-sm-compare]")) { recipe = ""; play(); }
      if (ev.target.matches("[data-sm-side]")) { state.side = ev.target.value; recipe = ""; play(); }
    });
    root.addEventListener("click", function (ev) {
      var tr = ev.target.closest("[data-sm-ws] tr[data-i]");
      if (tr) { pick(+tr.dataset.i); }
    });
    root.addEventListener("keydown", function (ev) {
      var tr = ev.target.closest && ev.target.closest("[data-sm-ws] tr[data-i]");
      if (tr && ev.key === "Enter") { pick(+tr.dataset.i); }
    });
    $("[data-sm-save]").addEventListener("click", function () {
      getJson("/api/simm/recipes?" + query().toString(), { method: "POST" }).then(function (r) {
        notice("Saved as recipe " + r.id + ": " + r.command + " computes it again to the cent (inputs as known at " + r.knownAt + ").");
      }).catch(function (e) { notice(e.message, true); });
    });
    $("[data-sm-export]").addEventListener("click", function () {
      window.location.href = "/api/simm/export.xlsx?" + query({ side: state.side }).toString();
    });
  }
  if (mode === "play") { playground(); } else { screen(); }
}());
