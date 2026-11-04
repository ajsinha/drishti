/* Project Drishti · Any data. Any domain. One grammar.
   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
   PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.

   Parametric VaR on the VaR explorer (PVAR; docs/architecture/PARAMETRIC_VAR.md; VAR_GUIDE.md section 3.12): historical and parametric
   side by side. Three views on the same screen, A the default:

   A · Beside historical   the tiles (parametric VaR, parametric ES, the fat-tail gap), the tree's Parametric and Gap columns, the
                           normal curve over the P&L path's histogram with both markers, shaded tails and the gap bracket (var-charts.js)
   B · Distribution        the histogram large with the curve, the loss tail bin by bin (scenario days against the normal's expected),
                           the gap of each child of the scope
   C · Components          the Euler components (z·cov/σ, −μ, component, share, marginal per +1%, standalone, historical component)
                           that add up to the parametric VaR, the build-up bar and the correlation heat map (matrix=true)

   Every parametric figure has a ? that opens the server's own lines (describe): how it was computed, with the numbers; a bottom sheet on a
   phone. The browser computes nothing: every number, curve point and line comes from the answer (the server-computes-risk rule). The
   Normal curve toggle (key N) is remembered per person (localStorage) and in the link (nc=0); the view in the link (pv=a|b|c). */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  if (!root || root.dataset.pm !== "1") return;
  var $ = function (sel) { return root.querySelector(sel); };
  var C = window.VarCharts, X = null, VIEW = root.dataset.pv || "a", NC_KEY = "drishti.var.pvar.curve";
  var params = new URLSearchParams(location.search);
  var curve = params.get("nc") === "0" ? false : (function () { try { return localStorage.getItem(NC_KEY) !== "0"; } catch (e) { return true; } })();

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function money(v) { return v === null || v === undefined ? "—" : C.money(v); }
  function full(v) { return v === null || v === undefined ? "—" : Math.round(v).toLocaleString("en-US"); }
  function pct(v) { return v === null || v === undefined ? "—" : (v >= 0 ? "+" : "") + (v * 100).toFixed(1) + "%"; }

  // ---------------------------------------------------------------------------------------------------------------- the ? popover
  var pop = null, popFor = null;
  function closeTip() { if (pop) { pop.hidden = true; popFor = null; } }
  /** The ? of a figure: the server's lines, opened on click (and hover on a desktop), closed by its ×, Escape or another ?. */
  function qmark(lines, title) {
    var b = h("button", "vp-q", "?");
    b.type = "button";
    b.setAttribute("aria-label", "How it was calculated" + (title ? ": " + title : ""));
    b.addEventListener("click", function (ev) { ev.stopPropagation(); open(b, lines); });
    b.addEventListener("mouseenter", function () { if (window.matchMedia && window.matchMedia("(hover: hover)").matches && !popFor) open(b, lines, true); });
    return b;
  }
  function open(anchor, lines, peek) {
    if (!pop) {
      pop = h("div", "vp-pop");
      pop.setAttribute("role", "dialog");
      pop.setAttribute("aria-label", "How it was calculated");
      document.body.appendChild(pop);
      document.addEventListener("keydown", function (ev) { if (ev.key === "Escape") closeTip(); });
      document.addEventListener("click", function (ev) { if (pop && !pop.hidden && !pop.contains(ev.target)) closeTip(); });
    }
    if (popFor === anchor && !peek) { closeTip(); return; }
    pop.textContent = "";
    var x = h("button", "vp-pop-x", "×");
    x.type = "button";
    x.setAttribute("aria-label", "Close");
    x.addEventListener("click", closeTip);
    pop.appendChild(x);
    (lines || ["No explanation in the answer."]).forEach(function (line, i) { pop.appendChild(h(i === 0 ? "h4" : "p", i === 1 ? "vp-pop-status" : null, line)); });
    var link = h("p", "vp-pop-foot");
    var play = h("a", null, "Open PLAY PVAR");
    play.href = "/go?q=" + encodeURIComponent("PLAY PVAR " + (X ? X.words(X.state.scope).replace(/^VAR /, "") : "BANK"));
    var guide = h("a", null, "Guide");
    guide.href = "/help/var-parametric";
    link.appendChild(play);
    link.appendChild(document.createTextNode(" · "));
    link.appendChild(guide);
    pop.appendChild(link);
    pop.hidden = false;
    popFor = anchor;
    if (window.innerWidth <= 600) { pop.classList.add("sheet"); return; }   // a bottom sheet on a phone
    pop.classList.remove("sheet");
    var r = anchor.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - pop.offsetWidth - 8)) + "px";
    pop.style.top = (r.bottom + window.scrollY + 6) + "px";
  }

  // ---------------------------------------------------------------------------------------------------------------- view A
  /** The three parametric tiles under the historical ones; the switched-off notice when the answer has no block. */
  function headline(d) {
    var t = $("[data-vp-tiles]"), off = $("[data-vp-off]"), p = d.parametric;
    t.textContent = "";
    if (!p) {
      off.hidden = false;
      off.textContent = d.empty ? "No trades: nothing to fit." : "Parametric VaR is switched off on this server (drishti.var.parametric.enabled): the historical figures stand alone.";
      return;
    }
    off.hidden = true;
    var conf = Math.round(p.confidence * 1000) / 10, esc = Math.round(p.esConfidence * 1000) / 10, days = (d.lens || {}).horizon === "10D" ? "10 days" : "1 day";
    var gapLines = (p.describe || []).filter(function (l) { return /^Fat-tail gap/.test(l); });
    [["Parametric VaR " + conf + "% · " + days, money(p["var"]), "z·σ − μ = " + p.z.toFixed(4) + " × " + money(p.sigma) + " − (" + money(p.mu) + ")", p.describe, "pvar"],
     ["Parametric ES " + esc + "%", money(p.es), "σ × " + p.esFactor.toFixed(4) + " − μ", p.describe, "pes"],
     ["Fat-tail gap", (p.gap >= 0 ? "+" : "") + money(p.gap), pct(p.gapPct) + " · skew " + p.skew.toFixed(2) + " · ex. kurtosis " + p.excessKurtosis.toFixed(2),
      [p.describe[0].replace("Parametric VaR", "Fat-tail gap")].concat(gapLines, (p.notes || [])), "pgap"]].forEach(function (x) {
      var tile = h("div", "vx-tile vp-tile");
      tile.dataset.vpTile = x[4];
      var l = h("div", "l", x[0]);
      l.appendChild(qmark(x[3], x[0]));
      tile.appendChild(l);
      tile.appendChild(h("div", "v mono" + (x[4] === "pgap" ? (p.gap > 0 ? " vp-warn" : "") : " vp-blue"), x[1]));
      tile.appendChild(h("div", "s mono", x[2]));
      t.appendChild(tile);
    });
    if (VIEW === "b") drawB(d);
    if (VIEW === "c") loadC();
  }

  /** The tree's Parametric and Gap cells, each with the node's own ?. */
  function treeCells(row, n) {
    var p = n.parametric;
    var a = h("span", "num vp-blue", p ? money(p["var"]) : "");
    var g = h("span", "num" + (p && p.gap > 0 ? " vp-warn" : ""), p ? (p.gap >= 0 ? "+" : "") + money(p.gap) : "");
    if (p) {
      a.appendChild(qmark(p.describe, n.name));
      g.title = pct(p.gapPct) + " of the parametric VaR";
    }
    row.appendChild(a);
    row.appendChild(g);
  }

  // ---------------------------------------------------------------------------------------------------------------- view B
  function drawB(d) {
    var host = $("[data-vp-pane='b']");
    host.textContent = "";
    var S = X.state;
    host.appendChild(h("h3", null, "The distribution against the fitted normal"));
    var chart = h("div", "vx-chart vp-big");
    host.appendChild(chart);
    X.api("scope/path", { scope: S.scope, page: 0 }).then(function (pd) {
      if (!pd.parametric) { chart.textContent = "No parametric block in the answer."; return; }
      C.histogram(chart, { edges: pd.histogram.edges, counts: pd.histogram.counts, "var": pd["var"], es: pd.es, parametric: curve ? pd.parametric : null, height: 300 });
      var tbl = h("table", "vx-table vp-tail-table");
      var head = h("tr");
      ["P&L from", "to", "Scenario days", "The normal expects", "Difference"].forEach(function (x) { head.appendChild(h("th", null, x)); });
      tbl.appendChild(h("thead")).appendChild(head);
      var body = tbl.appendChild(h("tbody"));
      var e = pd.histogram.edges, c = pd.histogram.counts, ex = pd.parametric.expected;
      for (var i = 0; i < c.length && e[i + 1] <= 0; i++) {            // the loss side, worst first
        var tr = h("tr");
        tr.appendChild(h("td", "num", full(e[i])));
        tr.appendChild(h("td", "num", full(e[i + 1])));
        tr.appendChild(h("td", "num", String(c[i])));
        tr.appendChild(h("td", "num vp-blue", ex[i].toFixed(2)));
        tr.appendChild(h("td", "num" + (c[i] - ex[i] > 0.5 ? " vp-warn" : ""), (c[i] - ex[i] >= 0 ? "+" : "") + (c[i] - ex[i]).toFixed(2)));
        body.appendChild(tr);
      }
      host.appendChild(h("h3", null, "The loss tail, bin by bin"));
      host.appendChild(tbl);
    }).catch(function (e) { chart.textContent = e.message; });
    var nodes = (S.tree && S.tree.nodes) || [], top = nodes.filter(function (n) { return !n.parent; })[0];
    var cur = S.scope === "BANK" ? (top ? top.id : "") : String(S.scope).replace(/^NODE:/, "");
    var kids = nodes.filter(function (n) { return n.parent && n.parent === cur; });
    if (kids.length) {
      host.appendChild(h("h3", null, "The fat-tail gap of each child"));
      var t = h("table", "vx-table");
      var hr = h("tr");
      ["Child", "Historical VaR", "Parametric VaR", "Gap", "Gap %", ""].forEach(function (x) { hr.appendChild(h("th", null, x)); });
      t.appendChild(h("thead")).appendChild(hr);
      var tb = t.appendChild(h("tbody"));
      kids.forEach(function (n) {
        var p = n.parametric || {};
        var tr = h("tr");
        tr.appendChild(h("td", null, n.name));
        tr.appendChild(h("td", "num", full(n["var"])));
        tr.appendChild(h("td", "num vp-blue", full(p["var"])));
        tr.appendChild(h("td", "num" + (p.gap > 0 ? " vp-warn" : ""), full(p.gap)));
        tr.appendChild(h("td", "num", pct(p.gapPct)));
        var q = h("td");
        if (p.describe) q.appendChild(qmark(p.describe, n.name));
        tr.appendChild(q);
        tb.appendChild(tr);
      });
      host.appendChild(t);
    }
  }

  // ---------------------------------------------------------------------------------------------------------------- view C
  function loadC() {
    var host = $("[data-vp-pane='c']"), S = X.state;
    host.textContent = "computing…";
    X.api("scope/contributions", { scope: S.scope, by: "child", matrix: "true" }).then(function (d) {
      host.textContent = "";
      var p = d.parametric;
      if (!p) { host.textContent = "No parametric block in the answer."; return; }
      host.appendChild(h("h3", null, "Euler components: z·cov(Pᵢ, P)/σ_P − μᵢ, adding up to the parametric VaR " + full(p["var"])
        + (p.addsUp ? " exactly" : " (the parts shown are not all the scope)")));
      var t = h("table", "vx-table vp-comp");
      var hr = h("tr");
      ["Part", "z·cov/σ", "−μᵢ", "Component", "Share", "Per +1%", "Standalone", "Historical component", "ρ", ""].forEach(function (x) { hr.appendChild(h("th", null, x)); });
      t.appendChild(h("thead")).appendChild(hr);
      var tb = t.appendChild(h("tbody"));
      var rows = (d.rows || []).slice().sort(function (a, b) { return (b.pComponentVar || 0) - (a.pComponentVar || 0); });
      rows.forEach(function (r) {
        var tr = h("tr");
        tr.appendChild(h("td", null, r.label));
        tr.appendChild(h("td", "num", full(r.zCovOverSigma)));
        tr.appendChild(h("td", "num", full(-r.mu)));
        var cv = h("td", "num vp-blue", full(r.pComponentVar));
        tr.appendChild(cv);
        tr.appendChild(h("td", "num", r.pPct === null || r.pPct === undefined ? "—" : (r.pPct * 100).toFixed(1) + "%"));
        tr.appendChild(h("td", "num", full(r.pMarginalPer1pct)));
        tr.appendChild(h("td", "num", full(r.pStandaloneVar)));
        tr.appendChild(h("td", "num muted", full(r.componentVar)));
        tr.appendChild(h("td", "num", r.rho === undefined ? "—" : r.rho.toFixed(3)));
        var q = h("td");
        q.appendChild(qmark(r.describe, r.label));
        tr.appendChild(q);
        tb.appendChild(tr);
      });
      var foot = h("tr", "vp-total");
      foot.appendChild(h("td", null, "Σ"));
      foot.appendChild(h("td"));
      foot.appendChild(h("td"));
      foot.appendChild(h("td", "num vp-blue", full(p.pSumComponent)));
      foot.appendChild(h("td", "num", "100%"));
      foot.appendChild(h("td"));
      foot.appendChild(h("td", "num", full(p.pSumStandalone)));
      foot.appendChild(h("td", "num muted", full(d.sumComponent)));
      tb.appendChild(foot);
      host.appendChild(t);
      host.appendChild(h("p", "vx-note", "Diversification " + full(p.pDiversification) + " = Σ standalone − VaR. Historical components beside them for comparison."));
      buildUp(host, rows, p);
      if (p.matrix) heat(host, p.matrix);
    }).catch(function (e) { host.textContent = e.message; });
  }

  /** The build-up bar: each component's share of the parametric VaR, left to right (widths from the server's shares). */
  function buildUp(host, rows, p) {
    var bar = h("div", "vp-build");
    bar.setAttribute("aria-label", "The components building up to the parametric VaR");
    rows.forEach(function (r, i) {
      if (!(r.pPct > 0)) return;
      var seg = h("span", "vp-seg vp-seg-" + (i % 6), r.label);
      seg.style.width = (r.pPct * 100).toFixed(2) + "%";
      seg.title = r.label + ": " + full(r.pComponentVar) + " (" + (r.pPct * 100).toFixed(1) + "%)";
      bar.appendChild(seg);
    });
    host.appendChild(h("h3", null, "Building up to " + full(p["var"])));
    host.appendChild(bar);
  }

  /** The correlation heat map of the parts (from the server's matrix; the colour is the value's class, nothing computed). */
  function heat(host, m) {
    host.appendChild(h("h3", null, "Correlations between the parts" + (m.truncated ? " (the largest " + m.keys.length + ")" : "") + " · " + m.engine));
    var t = h("table", "vx-table vp-heat");
    var hr = h("tr");
    hr.appendChild(h("th"));
    m.labels.forEach(function (l) { hr.appendChild(h("th", null, l)); });
    t.appendChild(h("thead")).appendChild(hr);
    var tb = t.appendChild(h("tbody"));
    m.correlation.forEach(function (row, i) {
      var tr = h("tr");
      tr.appendChild(h("th", null, m.labels[i]));
      row.forEach(function (v) {
        var td = h("td", "num vp-c" + Math.max(-4, Math.min(4, Math.round(v * 4))), v.toFixed(2));
        td.title = m.labels[i] + " · ρ " + v.toFixed(4);
        tr.appendChild(td);
      });
      tb.appendChild(tr);
    });
    host.appendChild(t);
  }

  // ---------------------------------------------------------------------------------------------------------------- the frame
  function setView(v) {
    VIEW = v;
    root.dataset.pv = v;
    root.querySelectorAll("[data-vp-view]").forEach(function (b) { b.setAttribute("aria-pressed", b.dataset.vpView === v ? "true" : "false"); });
    $("[data-vp-pane='b']").hidden = v !== "b";
    $("[data-vp-pane='c']").hidden = v !== "c";
    var u = new URL(location.href);
    u.searchParams.set("pv", v);
    history.replaceState(history.state, "", u.toString());
    X.renderTree();
    if (v === "b") drawB({});
    if (v === "c") loadC();
  }

  function setCurve(on) {
    curve = on;
    $("[data-vp-curve]").checked = on;
    try { localStorage.setItem(NC_KEY, on ? "1" : "0"); } catch (e) { /* the link carries it */ }
    var u = new URL(location.href);
    if (on) u.searchParams.delete("nc"); else u.searchParams.set("nc", "0");
    history.replaceState(history.state, "", u.toString());
    X.redrawPath();
    if (VIEW === "b") drawB({});
  }

  function start(ex) {
    X = ex;
    $("[data-vp-curve]").checked = curve;
    $("[data-vp-curve]").addEventListener("change", function (e) { setCurve(e.target.checked); });
    root.querySelectorAll("[data-vp-view]").forEach(function (b) { b.addEventListener("click", function () { setView(b.dataset.vpView); }); });
    document.addEventListener("keydown", function (ev) {
      var tag = (ev.target && ev.target.tagName) || "";
      if ((ev.key === "n" || ev.key === "N") && !ev.ctrlKey && !ev.metaKey && !ev.altKey && !/INPUT|TEXTAREA|SELECT/.test(tag)) setCurve(!curve);
    });
    $("[data-vp-historical]").addEventListener("click", function (ev) {
      ev.preventDefault();
      location.href = "/go?q=" + encodeURIComponent(X.words(X.state.scope));
    });
    $("[data-vp-play]").addEventListener("click", function (ev) {
      ev.preventDefault();
      location.href = "/go?q=" + encodeURIComponent("PLAY PVAR " + X.words(X.state.scope).replace(/^VAR /, ""));
    });
    $("[data-vp-pane='b']").hidden = VIEW !== "b";
    $("[data-vp-pane='c']").hidden = VIEW !== "c";
  }

  window.VarParametric = { start: start, headline: headline, treeCells: treeCells, curveOn: function () { return curve; },
    view: function () { return VIEW; } };
})();
