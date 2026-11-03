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
/* FRTB-SA (terminal/frtb.html; docs/guides/FRTB_SA_GUIDE.md sections 7 and 8). The capital screen (FRSA): the stack, the class × measure
   grid and the bucket drill, the three scenarios and the contributions. The playground (PLAY FRTB): the Choices panel, a switch compared
   side by side with the difference explained by step, the grid of weighted sensitivities with its formula bar and the explanation, Save
   recipe and Export to Excel; PLAY FRTB RUN <id> replays a recipe. Every figure is the server's: this file only draws. */
(function () {
  "use strict";
  var root = document.querySelector("[data-frtb]");
  if (!root) { return; }
  var mode = root.dataset.mode, scope = root.dataset.scope, recipe = root.dataset.recipe;
  if (!scope && !recipe) { return; }
  function $(s) { return root.querySelector(s); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function money(v) {
    if (v == null || isNaN(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function num(v, d) { return v == null ? "—" : Number(v).toLocaleString("en-US", { maximumFractionDigits: d == null ? 6 : d }); }
  var CLASS = { GIRR: "GIRR", CSR_NS: "CSR", EQ: "Equity", COMM: "Commodity", FX: "FX" };
  var SC = ["low", "medium", "high"];
  var state = { choices: {}, overrides: [], data: null };

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
  function stack(a) {
    var max = Math.max.apply(null, a.stack.map(function (s) { return Math.abs(s.value || 0); }).concat([1]));
    var widths = [];
    $("[data-fr-stack]").innerHTML = a.stack.map(function (s) {
      widths.push(s.value == null ? 0 : Math.max(1, Math.abs(s.value) / max * 100));
      return '<div class="fr-bar' + (s.label === "Total" ? " total" : "") + '"><span class="fr-bl">' + esc(s.label) + '</span><span class="fr-bt"><i></i></span>'
        + '<span class="fr-bv mono">' + (s.value == null ? "not computed" : money(s.value)) + "</span></div>";
    }).join("");
    // each bar's length through the CSSOM: a style attribute in markup is blocked by the console's CSP (style-src 'self')
    root.querySelectorAll("[data-fr-stack] .fr-bt i").forEach(function (i, n) { i.style.width = widths[n] + "%"; });
  }
  function classesGrid(a) {
    var rep = SC.indexOf(a.reported), rows = {};
    a.classes.forEach(function (c) { (rows[c.class] = rows[c.class] || {})[c.measure] = c; });
    var h = '<thead><tr><th class="l">Class (' + esc(a.reported) + ")</th><th>Delta</th><th>Vega</th><th>Curvature</th><th>Total</th></tr></thead><tbody>";
    Object.keys(rows).forEach(function (cls) {
      var t = 0;
      h += '<tr><td class="l">' + esc(CLASS[cls] || cls) + "</td>";
      ["DELTA", "VEGA", "CURVATURE"].forEach(function (m) {
        var c = rows[cls][m], v = c ? c[a.reported] : null;
        t += v || 0;
        h += '<td class="mono' + (c ? " fr-cell" : "") + '" data-cls="' + cls + '" data-m="' + m + '" tabindex="' + (c ? 0 : -1) + '">' + (c ? money(v) : "—") + "</td>";
      });
      h += '<td class="mono">' + money(t) + "</td></tr>";
    });
    h += '<tr class="tot"><td class="l">SBM</td><td colspan="3"></td><td class="mono">' + money(a.SBM) + "</td></tr>";
    $("[data-fr-classes]").innerHTML = h + "</tbody>";
    void rep;
  }
  function scenarios(a) {
    var h = '<thead><tr><th class="l">Class</th><th class="l">Measure</th>' + SC.map(function (s) { return "<th" + (s === a.binding ? ' class="bind"' : "") + ">" + s + "</th>"; }).join("")
      + "</tr></thead><tbody>";
    a.classes.forEach(function (c) {
      h += '<tr><td class="l">' + esc(CLASS[c.class] || c.class) + '</td><td class="l">' + esc(c.measure.toLowerCase()) + "</td>" + SC.map(function (s, i) {
        return '<td class="mono' + (s === a.binding ? " bind" : "") + '">' + money(c[s]) + (c.alternative[i] ? ' <span class="pill" title="the alternative specification applied (MAR21.4(5)(b))">alt</span>' : "") + "</td>";
      }).join("") + "</tr>";
    });
    h += '<tr class="tot"><td class="l" colspan="2">SBM</td>' + SC.map(function (s) { return '<td class="mono' + (s === a.binding ? " bind" : "") + '">' + money(a.sbm[s]) + "</td>"; }).join("") + "</tr>";
    $("[data-fr-scen]").innerHTML = h + "</tbody>";
  }
  function drill(cls, m) {
    var d = $("[data-fr-drill]");
    d.innerHTML = "<p class=\"muted\">loading " + esc(cls) + "…</p>";
    getJson("/api/frtb/explain?" + query({ "class": cls }).toString()).then(function (x) {
      var c = (x.classes || []).filter(function (y) { return y.measure === m; })[0];
      if (!c) { d.innerHTML = "<p>No " + esc(m.toLowerCase()) + " in " + esc(cls) + ".</p>"; return; }
      var h = "<h3>" + esc(CLASS[cls] || cls) + " " + esc(m.toLowerCase()) + "</h3><p class=\"mono\">" + SC.map(function (s, i) { return s + " " + money(c.charge[i]); }).join(" · ") + "</p>";
      c.buckets.forEach(function (b) {
        h += "<details><summary>Bucket " + esc(b.bucket) + " · K " + money(b.K[1]) + " · S " + money(b.S[1]) + (b.side ? " · side " + esc(b.side[1]) : "") + "</summary><table class=\"fr-mini\">";
        b.factors.forEach(function (f) {
          h += "<tr><td class=\"l mono\">" + esc(f.factor) + "</td><td class=\"mono\">" + (f.ws != null ? "WS " + money(f.ws) : "CVR+ " + money(f.cvrUp) + " / CVR− " + money(f.cvrDown)) + "</td></tr>";
        });
        h += "</table></details>";
      });
      d.innerHTML = h;
    }).catch(function (e) { d.innerHTML = "<p class=\"bad\">" + esc(e.message) + "</p>"; });
  }
  function contrib() {
    var by = $("[data-fr-by]").value;
    getJson("/api/frtb/contributions?" + query({ by: by }).toString()).then(function (c) {
      $("[data-fr-contrib]").innerHTML = '<thead><tr><th class="l">' + esc(c.by) + "</th><th>Contribution</th><th>Share</th></tr></thead><tbody>" + c.rows.slice(0, 200).map(function (r) {
        return '<tr><td class="l mono">' + esc(r.key) + '</td><td class="mono">' + money(r.contribution) + '</td><td class="mono">' + (r.share == null ? "—" : (r.share * 100).toFixed(1) + "%") + "</td></tr>";
      }).join("") + '<tr class="tot"><td class="l">Sum (' + (c.addsUp ? "adds up to the total" : "does not add up") + ')</td><td class="mono">' + money(c.sum) + "</td><td></td></tr></tbody>";
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
      var cell = ev.target.closest(".fr-cell");
      if (cell) { drill(cell.dataset.cls, cell.dataset.m); }
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
    var box = $("[data-fr-diff]");
    if (!d) { box.hidden = true; return; }
    box.hidden = false;
    box.innerHTML = "<h3>" + esc(d.label) + "</h3><p>" + esc(d.words) + "</p><div class=\"fr-side\"><div><b>This run</b><span class=\"mono\">" + money(d.base) + "</span><small>binding " + esc(d.baseScenario)
      + "</small></div><div><b>With the switch</b><span class=\"mono\">" + money(d.alternative) + "</span><small>binding " + esc(d.alternativeScenario) + "</small></div><div><b>Difference</b><span class=\"mono\">"
      + money(d.difference) + "</span></div></div><table class=\"fr-mini\"><tr><th class=\"l\">Class</th><th>This run</th><th>With the switch</th><th>Difference</th><th class=\"l\">Bucket</th><th class=\"l\">Where it arises</th></tr>"
      + d.lines.map(function (l) {
        return '<tr><td class="l">' + esc((CLASS[l["class"]] || l["class"]) + " " + l.measure.toLowerCase()) + '</td><td class="mono">' + money(l.base) + '</td><td class="mono">' + money(l.alternative)
          + '</td><td class="mono">' + money(l.difference) + '</td><td class="l">' + esc(l.bucket || "") + '</td><td class="l">' + esc(l.step) + (l.detail ? ": " + esc(l.detail) : "") + "</td></tr>";
      }).join("") + "</table>";
    void alt;
  }
  var STEP = { DELTA: 2, VEGA: 8, CURVATURE: 9 };     // the Explain steps 3, 9 and 10
  function grid(a) {
    var rows = a.grid || [];
    var h = '<thead><tr><th class="l">Class</th><th class="l">Measure</th><th class="l">Bucket</th><th class="l">Factor</th><th>s</th><th>RW</th><th>WS / CVR+</th><th>K low</th><th>K medium</th><th>K high</th></tr></thead><tbody>';
    rows.forEach(function (r, i) {
      h += '<tr data-i="' + i + '" tabindex="0"><td class="l">' + esc(CLASS[r["class"]] || r["class"]) + '</td><td class="l">' + esc(r.measure.toLowerCase()) + '</td><td class="l">' + esc(r.bucket)
        + '</td><td class="l mono">' + esc(r.factor) + '</td><td class="mono">' + money(r.s) + '</td><td class="mono">' + num(r.rw, 6) + '</td><td class="mono">' + money(r.ws != null ? r.ws : r.cvrUp)
        + "</td>" + r.K.map(function (k) { return '<td class="mono">' + money(k) + "</td>"; }).join("") + "</tr>";
    });
    if ((a.factors || 0) > rows.length) { h += '<tr><td class="l" colspan="10">' + (a.factors - rows.length) + " more factors: export a desk or a set of positions, or see the workbook</td></tr>"; }
    $("[data-fr-ws]").innerHTML = h + "</tbody>";
    var ex = $("[data-fr-explain]");
    ex.innerHTML = (a.explainSteps || []).map(function (s, i) { return '<details data-step="' + i + '"><summary>' + esc(s.title) + "</summary><p>" + esc(s.text) + "</p></details>"; }).join("");
  }
  function select(i) {
    var r = (state.data.grid || [])[i];
    if (!r) { return; }
    root.querySelectorAll("[data-fr-ws] tr.sel").forEach(function (t) { t.classList.remove("sel"); });
    var tr = root.querySelector('[data-fr-ws] tr[data-i="' + i + '"]');
    if (tr) { tr.classList.add("sel"); }
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
      grid(a);
    }).catch(function (e) { $("[data-fr-meta]").textContent = ""; notice(e.message, true); });
  }
  function playground() {
    play();
    root.addEventListener("change", function (ev) {
      var sw = ev.target.closest("[data-fr-sw]");
      if (sw) { state.choices[sw.dataset.frSw] = sw.value; recipe = ""; play(); }
      if (ev.target.matches("[data-fr-compare]")) { recipe = ""; play(); }
    });
    root.addEventListener("click", function (ev) {
      var tr = ev.target.closest("[data-fr-ws] tr[data-i]");
      if (tr) { select(+tr.dataset.i); }
    });
    root.addEventListener("keydown", function (ev) {
      var tr = ev.target.closest && ev.target.closest("[data-fr-ws] tr[data-i]");
      if (tr && ev.key === "Enter") { select(+tr.dataset.i); }
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
