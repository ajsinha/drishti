/* Project Drishti · Any data. Any domain. One grammar.
   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
   PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.

   Parametric VaR on the VaR explorer (docs/guides/VAR_GUIDE.md section 18.15; VAR_GUIDE.md section 3.12): a toggle (Parametric; the link's
   pm=1, which the PVAR command opens with) that adds, beside the historical figures, the tiles (parametric VaR, parametric ES, the
   fat-tail gap), the tree's Parametric and Gap columns, and the normal curve over the P&L path's histogram with both markers, shaded
   tails and the gap bracket (drishti_var-charts.js). The Euler components and the worked figures are in the VaR Playground's Parametric tab.

   Every parametric figure has a ? that opens the server's own lines (describe): how it was computed, with the numbers; a bottom sheet on a
   phone. The browser computes nothing: every number, curve point and line comes from the answer (the server-computes-risk rule). The
   Normal curve toggle (key N) is remembered per person (localStorage) and in the link (nc=0). */
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  if (!root) return;
  var ON = root.dataset.pm === "1";
  var $ = function (sel) { return root.querySelector(sel); };
  var C = window.Drishti.VarCharts, X = null, NC_KEY = "drishti.var.pvar.curve";
  var params = new URLSearchParams(location.search);
  var curve = params.get("nc") === "0" ? false : (function () { try { return localStorage.getItem(NC_KEY) !== "0"; } catch (e) { return true; } })();

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function money(v) { return v === null || v === undefined ? "—" : C.money(v); }
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
    var play = h("a", null, "Worked in the playground");
    play.href = playLink();
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

  /** The VaR Playground's Parametric tab for the scope shown. */
  function playLink() {
    return "/play?scope=" + encodeURIComponent(X ? X.state.scope : "BANK") + "&measure=parametric";
  }

  // ---------------------------------------------------------------------------------------------------------------- the tiles
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

  // ---------------------------------------------------------------------------------------------------------------- the frame
  function setCurve(on) {
    curve = on;
    $("[data-vp-curve]").checked = on;
    try { localStorage.setItem(NC_KEY, on ? "1" : "0"); } catch (e) { /* the link carries it */ }
    var u = new URL(location.href);
    if (on) u.searchParams.delete("nc"); else u.searchParams.set("nc", "0");
    history.replaceState(history.state, "", u.toString());
    X.redrawPath();
  }

  /** The Parametric toggle: the same link with pm=1 (on) or without it (off). */
  function setOn(on) {
    var u = new URL(location.href);
    if (on) u.searchParams.set("pm", "1"); else u.searchParams.delete("pm");
    u.searchParams.delete("nc");
    location.href = u.toString();
  }

  function start(ex) {
    X = ex;
    var on = $("[data-vp-on]");
    if (on) on.addEventListener("change", function (e) { setOn(e.target.checked); });
    if (!ON) return;
    $("[data-vp-curve]").checked = curve;
    $("[data-vp-curve]").addEventListener("change", function (e) { setCurve(e.target.checked); });
    document.addEventListener("keydown", function (ev) {
      var tag = (ev.target && ev.target.tagName) || "";
      if ((ev.key === "n" || ev.key === "N") && !ev.ctrlKey && !ev.metaKey && !ev.altKey && !/INPUT|TEXTAREA|SELECT/.test(tag)) setCurve(!curve);
    });
    $("[data-vp-play]").addEventListener("click", function (ev) {
      ev.preventDefault();
      location.href = playLink();
    });
  }

  window.Drishti.VarParametric = { start: start, headline: headline, treeCells: treeCells, on: function () { return ON; },
    curveOn: function () { return curve; } };
})();
