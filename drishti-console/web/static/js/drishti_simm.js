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
/* ISDA SIMM (terminal/simm.html; docs/guides/SIMM_GUIDE.md sections 7 and 8). The screen (SIMM): every netting set with IM to collect and
   post (computed, stored and labelled, or why not computed), the selected one's breakdown by product and risk class, and the margin
   against the counterparty's call. The playground (PLAY SIMM): the Choices panel (version, concentration, HVR), the side, a switch compared
   side by side with the difference explained by step, the grid of weighted sensitivities with its formula bar and the explanation, Save
   recipe and Export to Excel; PLAY SIMM RUN <id> replays a recipe. Every figure is the server's: this file only draws, every table a
   <drishti-grid> in the terminal layout (drishti-risk's preset). */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the grid preset (drishti-risk)
  var root = document.querySelector("[data-simm]");
  if (!root || !R) { return; }
  var mode = root.dataset.mode, ns = root.dataset.ns, cpty = root.dataset.cpty, recipe = root.dataset.recipe;
  function $(s) { return root.querySelector(s); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) { return v == null || v === "—" || isNaN(v) ? "—" : F.formatNumber(v); }
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
  // ---- the tables: drishti-grid through drishti-risk's preset (terminal layout); every figure the server's, as sent
  function grid(sel, o) { return R.riskGrid(typeof sel === "string" ? $(sel) : sel, o); }
  function fig(field, header, o) { return R.moneyColumn(field, header, money, o); }      // drishti-risk's money column, this screen's rule
  var txt = R.textColumn;
  function box(cls) { var d = document.createElement("div"); if (cls) { d.className = cls; } return d; }
  function breakdown(a) {
    var d = $("[data-sm-detail]");
    d.innerHTML = "<h3>" + esc(a.NETTING_SET || a.SCOPE) + "</h3><p class=\"mono\">collect " + money(a.IM_COLLECT) + " (" + esc(a.COLLECT_REGIME) + ") · post "
      + money(a.IM_POST) + " (" + esc(a.POST_REGIME) + ")</p>";
    var b1 = box("fr-drill-grid"), b2 = box("fr-drill-grid");
    d.appendChild(b1);
    d.appendChild(b2);
    grid(b1, { title: "By product class", rows: (a.BY_PRODUCT_CLASS || []).map(function (p, i) { return { id: String(i), product: p.PRODUCT_CLASS, collect: p.COLLECT, post: p.POST }; }),
      columns: [txt("product", "Product class", { width: 130 }), fig("collect", "Collect"), fig("post", "Post")] });
    grid(b2, { title: "By risk class", rows: (a.BY_RISK_CLASS || []).map(function (r, i) {
      return { id: String(i), label: r.PRODUCT_CLASS + " · " + r.RISK_CLASS, delta: r.DELTA, vega: r.VEGA, curvature: r.CURVATURE, total: r.TOTAL };
    }), columns: [txt("label", "Risk class", { width: 170 }), fig("delta", "Delta", { width: 100 }), fig("vega", "Vega", { width: 100 }), fig("curvature", "Curv.", { width: 100 }),
      fig("total", "IM", { width: 110 })] });
    var tail = document.createElement("div");
    tail.innerHTML = "<p class=\"muted\">add-ons " + money(a.ADDON_COLLECT) + " · " + esc(a.TRADES) + " records · parameter SHA-256 " + esc(String(a.PARAMETER_SHA256 || "").slice(0, 12))
      + "…</p><p><a class=\"pg-link\" href=\"/play/simm?nettingSet=" + encodeURIComponent(a.NETTING_SET || a.SCOPE) + "\">Open in the playground</a></p>";
    d.appendChild(tail);
  }

  // ------------------------------------------------------------------------------------------------------------------ the screen
  function select(id) {
    ns = id;
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
      var rows = l.rows.map(function (r) {
        return { id: r.nettingSet, ns: r.nettingSet, counterparty: r.counterparty, imCollect: r.imCollect, imPost: r.imPost, version: r.version || "", status: r.status, theirCall: r.theirCall };
      });
      // the server's sums (SIMM does not net across netting sets: the sum is said as one)
      rows.push({ id: "SUM", blank: true, ns: "Sum (" + l.computed + " computed; SIMM does not net across netting sets)", counterparty: "", imCollect: l.sumCollect, imPost: l.sumPost, version: "", status: "" });
      grid("[data-sm-sets]", { title: "Netting sets", label: "Every netting set: IM to collect and post", rows: rows,
        getRowData: function (r) { return r.blank ? null : { ns: r.ns }; }, getRowClass: function (r) { return r.blank ? "tot" : r.ns === ns ? "sel" : null; },
        columns: [txt("ns", "Netting set", { width: 200, pinned: "left" }), txt("counterparty", "Counterparty", { width: 150 }), fig("imCollect", "IM collect", { additive: true }),
          fig("imPost", "IM post", { additive: true }), txt("version", "Version", { width: 90 }), txt("status", "Status", { width: 150 }), fig("theirCall", "Their call")],
        onRow: function (r) { if (!r.blank) { select(r.ns); } } });
      $("[data-sm-meta]").textContent = l.date + " · default SIMM " + (l.defaultVersion || "none installed");
      if (l.notice) { notice(l.notice, true); }
      if (ns) { select(ns); }
    }).catch(function (e) { $("[data-sm-meta]").textContent = ""; notice(e.message, true); });
  }
  function against() {
    if (!ns) { $("[data-sm-against]").innerHTML = "<p class=\"muted\">Select a netting set.</p>"; return; }
    getJson("/api/simm/against?" + query().toString()).then(function (c) {
      var rows = [];
      c.rows.forEach(function (r, i) {
        rows.push({ id: "t" + i, side: r.direction, riskClass: "total", drishti: r.drishti, theirs: r.theirs, difference: r.difference, status: r.status, total: true });
        r.byRiskClass.forEach(function (x, k) { rows.push({ id: "t" + i + "-" + k, side: "", riskClass: x.riskClass, drishti: x.drishti, theirs: x.theirs, difference: x.difference, status: "" }); });
      });
      grid("[data-sm-against]", { title: "Against the counterparty", rows: rows, getRowClass: function (r) { return r.total ? "tot" : null; },
        columns: [txt("side", "Side", { width: 90, pinned: "left" }), txt("riskClass", "Risk class", { width: 140 }), fig("drishti", (document.body && document.body.dataset.product) || "Ours"),
          fig("theirs", "Their call"), fig("difference", "Difference"), txt("status", "Status", { width: 150 })] });
      if (c.note) { notice(c.note); }
    }).catch(function (e) { notice(e.message, true); });
  }
  function screen() {
    sets();
    root.addEventListener("click", function (ev) {
      var tab = ev.target.closest("[data-sm-tab]");
      if (tab) {
        root.querySelectorAll("[data-sm-tab]").forEach(function (t) { t.setAttribute("aria-selected", String(t === tab)); });
        root.querySelectorAll("[data-sm-pane]").forEach(function (p) { p.hidden = p.dataset.smPane !== tab.dataset.smTab; });
        if (tab.dataset.smTab === "against") { against(); }
      }
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
    var pane = $("[data-sm-diff]");
    if (!d) { pane.hidden = true; return; }
    pane.hidden = false;
    pane.innerHTML = "<h3>" + esc(d.label) + "</h3><p>" + esc(d.words) + "</p><div class=\"fr-side\"><div><b>This run</b><span class=\"mono\">" + money(d.base)
      + "</span></div><div><b>With the switch</b><span class=\"mono\">" + money(d.alternative) + "</span></div><div><b>Difference</b><span class=\"mono\">" + money(d.difference)
      + "</span></div></div>";
    var lines = box("fr-diff-grid");
    pane.appendChild(lines);
    grid(lines, { title: "Where the difference arises", rows: d.lines.map(function (l, i) {
      return { id: String(i), label: l.productClass + " · " + l.riskClass + " " + l.measure.toLowerCase(), base: l.base, alternative: l.alternative, difference: l.difference,
        bucket: l.bucket || "", step: l.step };
    }), columns: [txt("label", "Class", { width: 190, pinned: "left" }), fig("base", "This run"), fig("alternative", "With the switch", { width: 130 }), fig("difference", "Difference"),
      txt("bucket", "Bucket", { width: 80 }), txt("step", "Where it arises", { width: 300 })] });
  }
  var STEP = { DELTA: 8, VEGA: 11, CURVATURE: 13, BASE_CORR: 14 };     // the Explain entries of steps 4, 7, 9 and 10
  /** The weighted sensitivities, bucket by bucket: a row (clicked, or the focus moved by the keys) selects its formula and its step. */
  function wsGrid(a) {
    var rows = (a.grid || []).map(function (r, i) {
      return { id: String(i), i: i, product: r.productClass, riskClass: r.riskClass, measure: r.measure.toLowerCase(), bucket: r.bucket, factor: r.factor, s: r.s, rw: r.rw,
        hvr: r.hvr, cr: r.cr, ws: r.ws, K: r.K };
    });
    var g = grid("[data-sm-ws]", { title: "Weighted sensitivities", rows: rows, fitRows: 30, getRowData: function (r) { return { i: r.i }; },
      columns: [txt("product", "Product", { width: 100, pinned: "left" }), txt("riskClass", "Risk class", { width: 110 }), txt("measure", "Measure", { width: 90 }),
        txt("bucket", "Bucket", { width: 70 }), txt("factor", "Factor", { width: 160 }), fig("s", "s / VR / CVR"),
        R.figureColumn("rw", "RW", { width: 90, format: function (v) { return num(v, 6); } }), R.figureColumn("hvr", "HVR", { width: 70, format: function (v) { return num(v, 4); } }),
        R.figureColumn("cr", "CR", { width: 70, format: function (v) { return num(v, 4); } }), fig("ws", "WS"), fig("K", "K_b")],
      onRow: function (r) { pick(r.i); } });
    if (g) { g.on("focusChanged", function (f) { var x = f && g.model.rowAt(f.row); if (x && x.kind === "leaf") { pick(x.row.i); } }); }
    $("[data-sm-more]").textContent = (a.factors || 0) > rows.length ? (a.factors - rows.length) + " more factors: see the workbook" : "";
    $("[data-sm-explain]").innerHTML = (a.explainSteps || []).map(function (s, i) { return '<details data-step="' + i + '"><summary>' + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>"; }).join("");
  }
  function pick(i) {
    var r = (state.data.grid || [])[i];
    if (!r) { return; }
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
      wsGrid(a);
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
