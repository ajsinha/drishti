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
/* FRTB-SA (terminal/frtb.html; docs/guides/FRTB_SA_GUIDE.md sections 7 and 8). The capital screen (FRSA): the stack, the class × measure
   grid and the bucket drill, the three scenarios and the contributions. The playground (PLAY FRTB): the Choices panel, a switch compared
   side by side with the difference explained by step, the grid of weighted sensitivities with its formula bar and the explanation, Save
   recipe and Export to Excel; PLAY FRTB RUN <id> replays a recipe. Every figure is the server's: this file only draws, with Drishti
   Elements: every table a <drishti-grid> in the terminal layout (drishti-risk's preset), the stack a drishti-chart bar chart. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the grid preset and charts (drishti-risk)
  var root = document.querySelector("[data-frtb]");
  if (!root || !R) { return; }
  var mode = root.dataset.mode, scope = root.dataset.scope, recipe = root.dataset.recipe;
  if (!scope && !recipe) { return; }
  function $(s) { return root.querySelector(s); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) { return v == null || isNaN(v) ? "—" : F.formatNumber(v, { decimals: 2 }); }
  function num(v, d) { return v == null ? "—" : Number(v).toLocaleString("en-US", { maximumFractionDigits: d == null ? 6 : d }); }
  var CLASS = { GIRR: "GIRR", CSR_NS: "CSR", EQ: "Equity", COMM: "Commodity", FX: "FX" };
  var SC = ["low", "medium", "high"];
  var state = { choices: {}, overrides: [], data: null };

  // ---- the tables: drishti-grid through drishti-risk's preset; a capital charge in money with two decimals, as the server rounds
  function grid(sel, o) { return R.riskGrid(typeof sel === "string" ? $(sel) : sel, o); }
  function fig(field, header, o) { return R.moneyColumn(field, header, money, o); }      // drishti-risk's money column, this screen's rule
  var txt = R.textColumn;
  function box(cls) { var d = document.createElement("div"); if (cls) { d.className = cls; } return d; }

  function query(extra) {
    var q = new URLSearchParams();
    if (scope) { q.set("scope", scope); }
    var asOf = new URLSearchParams(window.location.search).get("asOf");
    if (asOf) { q.set("asOf", asOf); }
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
    var n = $("[data-fr-notice]");
    n.hidden = !text;
    n.textContent = text || "";
    n.classList.toggle("bad", !!bad);
  }
  function meta(a) {
    var parts = [a.canonical || a.scope, a.date || "hand-built cases", a.currency, "binding " + a.binding];
    if (!a.regulatory) { parts.push("NOT THE REGULATORY FIGURE"); }
    if (a.estimate) { parts.push("contains curvature estimated from gamma"); }
    $("[data-fr-meta]").textContent = parts.join(" · ");
    var notes = [a.notRegulatory, a.estimateNote, a.drcNote, a.rraoNote].filter(Boolean);
    notice(notes.join(" · "), !a.regulatory);
  }

  // -------------------------------------------------------------------------------------------------------- the capital screen
  /** The capital stack (delta, vega, curvature, DRC, RRAO, the total) as drishti-chart's horizontal bars; a part not computed says so. */
  function stack(a) {
    var rows = a.stack.map(function (s, i) {
      return { id: String(i), label: s.label + (s.value == null ? " (not computed)" : ""), value: s.value == null ? null : s.value, total: s.label === "Total" ? 1 : 0 };
    });
    R.riskChart($("[data-fr-stack]"), { kind: "bar", orient: "horizontal", title: "Capital stack", data: { rows: rows }, id: "id", x: { field: "label", order: rows.map(function (r) { return r.label; }) },
      y: { field: "value", format: "amount2" }, labels: true, legend: "none", tone: "link", colourWhen: [{ when: "total > 0", tone: "warn" }], height: 40 + rows.length * 26 }, { height: 130 + rows.length * 26 });
  }
  /** Class × measure in the reported scenario; a measure's cell opens its buckets. The class total is the sum of its three charges
   *  (the SBM adds them within a class); SBM itself is the server's. */
  function classesGrid(a) {
    var rows = {}, out = [];
    a.classes.forEach(function (c) { (rows[c.class] = rows[c.class] || { id: c.class, cls: c.class, label: CLASS[c.class] || c.class })[c.measure] = c[a.reported]; });
    Object.keys(rows).forEach(function (k) { var r = rows[k]; r.total = ["DELTA", "VEGA", "CURVATURE"].reduce(function (t, m) { return t + (r[m] || 0); }, 0); r.isClass = true; out.push(r); });
    out.push({ id: "SBM", label: "SBM", total: a.SBM, sbm: true });
    var cell = function (m) {
      return fig(m, m.charAt(0) + m.slice(1).toLowerCase(), { format: function (v, r) { return r && r.sbm ? "" : v === undefined ? "—" : money(v); },
        cellClass: function (v, r) { return r && r.isClass && v !== undefined ? "fr-cell" : null; } });
    };
    grid("[data-fr-classes]", { title: "Charge by class and measure (" + a.reported + ")", rows: out,
      getRowClass: function (r) { return r.sbm ? "tot" : null; },
      columns: [txt("label", "Class (" + a.reported + ")", { pinned: "left" }), cell("DELTA"), cell("VEGA"), cell("CURVATURE"), fig("total", "Total", { additive: true })],
      onRow: function (r, e) {
        var col = e && e.detail && e.detail.columnId;
        if (r.isClass && /^(DELTA|VEGA|CURVATURE)$/.test(col || "") && r[col] !== undefined) { drill(r.cls, col); }
      } });
  }
  function scenarios(a) {
    var rows = a.classes.map(function (c, i) {
      var r = { id: String(i), label: CLASS[c.class] || c.class, measure: c.measure.toLowerCase(), alt: c.alternative };
      SC.forEach(function (s) { r[s] = c[s]; });
      return r;
    });
    var tot = { id: "SBM", label: "SBM", measure: "", total: true };
    SC.forEach(function (s) { tot[s] = a.sbm[s]; });
    rows.push(tot);
    grid("[data-fr-scen]", { title: "The three correlation scenarios", rows: rows, getRowClass: function (r) { return r.total ? "tot" : null; },
      columns: [txt("label", "Class", { pinned: "left" }), txt("measure", "Measure", { width: 100 })].concat(SC.map(function (s, i) {
        return fig(s, s + (s === a.binding ? " (binding)" : ""), { cellClass: function () { return s === a.binding ? "bind" : null; },
          cellRenderer: function (c) {
            if (!c.row.alt || !c.row.alt[i]) { return null; }
            var span = document.createElement("span");
            span.textContent = c.text + " ";
            var pill = document.createElement("span");
            pill.className = "pill";
            pill.title = "the alternative specification applied (MAR21.4(5)(b))";
            pill.textContent = "alt";
            span.appendChild(pill);
            return span;
          } });
      })) });
  }
  /** A measure's buckets (K_b and S_b per scenario, the side) and their factors: two grids in the drill pane. */
  function drill(cls, m) {
    var d = $("[data-fr-drill]");
    d.innerHTML = "<p class=\"muted\">loading " + esc(cls) + "…</p>";
    getJson("/api/frtb/explain?" + query({ "class": cls }).toString()).then(function (x) {
      var c = (x.classes || []).filter(function (y) { return y.measure === m; })[0];
      if (!c) { d.innerHTML = "<p>No " + esc(m.toLowerCase()) + " in " + esc(cls) + ".</p>"; return; }
      d.innerHTML = "<h3>" + esc(CLASS[cls] || cls) + " " + esc(m.toLowerCase()) + "</h3><p class=\"mono\">" + SC.map(function (s, i) { return s + " " + money(c.charge[i]); }).join(" · ") + "</p>";
      var b1 = box("fr-drill-grid"), b2 = box("fr-drill-grid");
      d.appendChild(b1);
      d.appendChild(b2);
      grid(b1, { title: "Buckets", rows: c.buckets.map(function (b) { return { id: String(b.bucket), bucket: b.bucket, K: b.K[1], S: b.S[1], side: b.side ? b.side[1] : "" }; }),
        columns: [txt("bucket", "Bucket", { width: 80 }), fig("K", "K (medium)"), fig("S", "S (medium)"), txt("side", "Side", { width: 70 })] });
      var factors = [];
      c.buckets.forEach(function (b) { b.factors.forEach(function (f, i) { factors.push({ id: b.bucket + "|" + i, bucket: b.bucket, factor: f.factor,
        value: f.ws != null ? "WS " + money(f.ws) : "CVR+ " + money(f.cvrUp) + " / CVR− " + money(f.cvrDown) }); }); });
      grid(b2, { title: "Factors", rows: factors, fitRows: 18,
        columns: [txt("bucket", "Bucket", { width: 70 }), txt("factor", "Factor", { width: 140 }), txt("value", "WS / CVR", { width: 200 })] });
    }).catch(function (e) { d.innerHTML = "<p class=\"bad\">" + esc(e.message) + "</p>"; });
  }
  function contrib() {
    var by = $("[data-fr-by]").value;
    getJson("/api/frtb/contributions?" + query({ by: by }).toString()).then(function (c) {
      var rows = c.rows.slice(0, 200).map(function (r, i) { return { id: String(i), key: r.key, contribution: r.contribution, share: r.share }; });
      rows.push({ id: "SUM", key: "Sum (" + (c.addsUp ? "adds up to the total" : "does not add up") + ")", contribution: c.sum, total: true });   // the server's sum
      grid("[data-fr-contrib]", { title: "Contributions by " + c.by, rows: rows, getRowClass: function (r) { return r.total ? "tot" : null; },
        columns: [txt("key", c.by, { width: 220, pinned: "left" }), fig("contribution", "Contribution", { additive: true }),
          R.figureColumn("share", "Share", { width: 80, format: function (v) { return v == null ? (v === undefined ? "" : "—") : (v * 100).toFixed(1) + "%"; } })] });
    }).catch(function (e) { notice(e.message, true); });
  }
  function capital() {
    getJson("/api/frtb/sa?" + query().toString()).then(function (a) {
      state.data = a;
      meta(a);
      stack(a);
      classesGrid(a);
      scenarios(a);
    }).catch(function (e) { $("[data-fr-meta]").textContent = ""; notice(e.message, true); });
    root.addEventListener("click", function (ev) {
      var tab = ev.target.closest("[data-fr-tab]");
      if (tab) {
        root.querySelectorAll("[data-fr-tab]").forEach(function (t) { t.setAttribute("aria-selected", String(t === tab)); });
        root.querySelectorAll("[data-fr-pane]").forEach(function (p) { p.hidden = p.dataset.frPane !== tab.dataset.frTab; });
        if (tab.dataset.frTab === "contrib") { contrib(); }
      }
    });
    $("[data-fr-by]").addEventListener("change", contrib);
  }

  // ------------------------------------------------------------------------------------------------------------ the playground
  function choices(list) {
    $("[data-fr-choices]").innerHTML = '<h3>Choices <span>every method choice is a switch; the default is the decided one</span></h3>' + list.map(function (s) {
      return '<label class="fr-switch' + (s.value !== s.default ? " changed" : "") + '"><b class="mono">' + esc(s.name) + "</b> <select data-fr-sw=\"" + esc(s.name) + "\">"
        + s.options.map(function (o) { return "<option" + (o === s.value ? " selected" : "") + ">" + esc(o) + "</option>"; }).join("")
        + '</select> <span class="muted">default ' + esc(s.default) + " · " + esc(s.rule) + "</span><small>" + esc(s.why) + "</small></label>";
    }).join("");
    var cmp = $("[data-fr-compare]"), keep = cmp.value;
    cmp.innerHTML = '<option value="">(none)</option>' + list.map(function (s) {
      return s.options.filter(function (o) { return o !== s.value; }).map(function (o) { return '<option value="' + esc(s.name + "=" + o) + '">' + esc(s.name + ": " + o) + "</option>"; }).join("");
    }).join("");
    cmp.value = keep;
  }
  function diff(d, alt) {
    var pane = $("[data-fr-diff]");
    if (!d) { pane.hidden = true; return; }
    pane.hidden = false;
    pane.innerHTML = "<h3>" + esc(d.label) + "</h3><p>" + esc(d.words) + "</p><div class=\"fr-side\"><div><b>This run</b><span class=\"mono\">" + money(d.base) + "</span><small>binding " + esc(d.baseScenario)
      + "</small></div><div><b>With the switch</b><span class=\"mono\">" + money(d.alternative) + "</span><small>binding " + esc(d.alternativeScenario) + "</small></div><div><b>Difference</b><span class=\"mono\">"
      + money(d.difference) + "</span></div></div>";
    var lines = box("fr-diff-grid");
    pane.appendChild(lines);
    grid(lines, { title: "Where the difference arises", rows: d.lines.map(function (l, i) {
      return { id: String(i), label: (CLASS[l["class"]] || l["class"]) + " " + l.measure.toLowerCase(), base: l.base, alternative: l.alternative, difference: l.difference,
        bucket: l.bucket || "", step: l.step + (l.detail ? ": " + l.detail : "") };
    }), columns: [txt("label", "Class", { width: 150, pinned: "left" }), fig("base", "This run"), fig("alternative", "With the switch", { width: 130 }), fig("difference", "Difference"),
      txt("bucket", "Bucket", { width: 80 }), txt("step", "Where it arises", { width: 320 })] });
    void alt;
  }
  var STEP = { DELTA: 2, VEGA: 8, CURVATURE: 9 };     // the Explain steps 3, 9 and 10
  /** The weighted sensitivities and curvature risks, bucket by bucket: a row selects its formula and its step of the explanation. */
  function wsGrid(a) {
    var rows = (a.grid || []).map(function (r, i) {
      return { id: String(i), i: i, label: CLASS[r["class"]] || r["class"], measure: r.measure.toLowerCase(), bucket: r.bucket, factor: r.factor, s: r.s, rw: r.rw,
        ws: r.ws != null ? r.ws : r.cvrUp, Klow: r.K[0], Kmedium: r.K[1], Khigh: r.K[2] };
    });
    var g = grid("[data-fr-ws]", { title: "Weighted sensitivities", rows: rows, fitRows: 30, getRowData: function (r) { return { i: r.i }; },
      columns: [txt("label", "Class", { width: 100, pinned: "left" }), txt("measure", "Measure", { width: 90 }), txt("bucket", "Bucket", { width: 70 }),
        txt("factor", "Factor", { width: 160 }), fig("s", "s"), R.figureColumn("rw", "RW", { width: 90, format: function (v) { return num(v, 6); } }),
        fig("ws", "WS / CVR+"), fig("Klow", "K low"), fig("Kmedium", "K medium"), fig("Khigh", "K high")],
      onRow: function (r) { select(r.i); } });
    // the keys walk the rows (the grid's own arrows) and the formula bar follows the focus
    if (g) { g.on("focusChanged", function (f) { var x = f && g.model.rowAt(f.row); if (x && x.kind === "leaf") { select(x.row.i); } }); }
    $("[data-fr-more]").textContent = (a.factors || 0) > rows.length ? (a.factors - rows.length) + " more factors: export a desk or a set of positions, or see the workbook" : "";
    var ex = $("[data-fr-explain]");
    ex.innerHTML = (a.explainSteps || []).map(function (s, i) { return '<details data-step="' + i + '"><summary>' + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>"; }).join("");
  }
  function select(i) {
    var r = (state.data.grid || [])[i];
    if (!r) { return; }
    $("[data-fr-ref]").textContent = r.factor;
    $("[data-fr-formula]").textContent = r.formula;
    var steps = root.querySelectorAll("[data-fr-explain] details");
    var want = 5 + (STEP[r.measure] || 2);
    steps.forEach(function (d, k) { d.open = k === want; });
    if (steps[want]) { steps[want].scrollIntoView({ block: "nearest" }); }
  }
  function play() {
    var url = recipe ? "/api/frtb/recipes/" + encodeURIComponent(recipe) : "/api/frtb/playground?" + query({ compare: $("[data-fr-compare]").value }).toString();
    $("[data-fr-meta]").textContent = "loading…";
    getJson(url).then(function (a) {
      state.data = a;
      if (recipe) {
        scope = a.scope;
        (a.choices || []).forEach(function (s) { state.choices[s.name] = s.value; });
      }
      meta(a);
      if (recipe) { notice(a.reproduced + (a.recipe ? " (recipe " + a.recipe.id + ", saved " + a.recipe.created + ")" : ""), !/^reproduced/.test(a.reproduced)); }
      choices(a.choices || []);
      diff(a.compare, a.compareAnswer);
      wsGrid(a);
    }).catch(function (e) { $("[data-fr-meta]").textContent = ""; notice(e.message, true); });
  }
  function playground() {
    play();
    root.addEventListener("change", function (ev) {
      var sw = ev.target.closest("[data-fr-sw]");
      if (sw) { state.choices[sw.dataset.frSw] = sw.value; recipe = ""; play(); }
      if (ev.target.matches("[data-fr-compare]")) { recipe = ""; play(); }
    });
    $("[data-fr-save]").addEventListener("click", function () {
      getJson("/api/frtb/recipes?" + query().toString(), { method: "POST" }).then(function (r) {
        notice("Saved as recipe " + r.id + ": " + r.command + " computes it again to the cent (inputs as known at " + r.knownAt + ").");
      }).catch(function (e) { notice(e.message, true); });
    });
    $("[data-fr-export]").addEventListener("click", function () {
      window.location.href = "/api/frtb/export.xlsx?" + query().toString();
    });
  }
  if (mode === "play") { playground(); } else { capital(); }
}());
