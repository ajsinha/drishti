/* Project Drishti · Any data. Any domain. One grammar.
   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
   PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.

   Parametric VaR on the VaR explorer (docs/architecture/PARAMETRIC_VAR.md; VAR_GUIDE.md section 3.12): a toggle (Parametric; the link's
   pm=1, which the PVAR command opens with) that adds, beside the historical figures, the tiles (parametric VaR, parametric ES, the
   fat-tail gap), the tree's Parametric and Gap columns, and the normal curve over the P&L path's histogram with both markers, shaded
   tails and the gap bracket (drishti-risk's P&L distribution with the server's fitted normal). The Euler components and the worked figures are in the VaR Playground's Parametric tab.

   Every parametric figure has a ? that is the one Explain's (drishti-insight; SHARED_SCREENS.md section 3): hover for the preview, click
   for the drawer, its Formula the server's own lines (describe) with the numbers. The browser computes nothing: every number, curve point and line comes from the answer (the server-computes-risk rule). The
   Normal curve toggle (key N) is remembered per person (localStorage) and in the link (nc=0). */
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  if (!root) return;
  var ON = root.dataset.pm === "1";
  var $ = function (sel) { return root.querySelector(sel); };
  var R = window.Drishti.elements.risk, X = null, NC_KEY = "drishti.var.pvar.curve";
  var params = new URLSearchParams(location.search);
  var curve = params.get("nc") === "0" ? false : (function () { try { return localStorage.getItem(NC_KEY) !== "0"; } catch (e) { return true; } })();

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function money(v) { return R.riskMoney(v); }
  function pct(v) { return v === null || v === undefined ? "—" : (v >= 0 ? "+" : "") + (v * 100).toFixed(1) + "%"; }

  // ---------------------------------------------------------------------------------------------------------------- the ?
  /** The ? of a figure: the one Explain of its measure (pvar, pes, pgap) on its scope. */
  function qmark(measure, scope, title) {
    var b = h("button", "vp-q", "?");
    b.type = "button";
    b.setAttribute("aria-label", "Explain" + (title ? ": " + title : ""));
    b.setAttribute("data-explain", JSON.stringify(X ? X.explainReq(measure, scope) : { kind: "var", measure: measure, scope: scope }));
    return b;
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
    [["Parametric VaR " + conf + "% · " + days, money(p["var"]), "z·σ − μ = " + p.z.toFixed(4) + " × " + money(p.sigma) + " − (" + money(p.mu) + ")", "pvar"],
     ["Parametric ES " + esc + "%", money(p.es), "σ × " + p.esFactor.toFixed(4) + " − μ", "pes"],
     ["Fat-tail gap", (p.gap >= 0 ? "+" : "") + money(p.gap), pct(p.gapPct) + " · skew " + p.skew.toFixed(2) + " · ex. kurtosis " + p.excessKurtosis.toFixed(2),
      "pgap"]].forEach(function (x) {
      var tile = h("div", "vx-tile vp-tile");
      tile.dataset.vpTile = x[3];
      var l = h("div", "l", x[0]);
      l.appendChild(qmark(x[3], null, x[0]));
      tile.appendChild(l);
      tile.appendChild(h("div", "v mono" + (x[3] === "pgap" ? (p.gap > 0 ? " vp-warn" : "") : " vp-blue"), x[1]));
      tile.appendChild(h("div", "s mono", x[2]));
      t.appendChild(tile);
    });
  }

  /** The tree's Parametric and Gap columns (drishti-grid columns of the explorer's tree), the Parametric figure with the node's own ?. */
  function treeColumns() {
    var pv = function (r) { return r.parametric ? r.parametric["var"] : null; }, gap = function (r) { return r.parametric ? r.parametric.gap : null; };
    return [R.figureColumn("pvar", "Parametric", { valueGetter: pv, width: 104, format: function (v) { return v === null ? "" : money(v); },
        cellClass: function () { return "vp-blue"; },
        cellRenderer: function (c) {
          if (!c.row.parametric) return null;
          var s = h("span", "vp-cell", c.text);
          s.appendChild(qmark("pvar", c.row.scope, c.row.name));
          return s;
        } }),
      R.figureColumn("pgap", "Gap", { valueGetter: gap, width: 90, format: function (v) { return v === null ? "" : (v >= 0 ? "+" : "") + money(v); },
        cellClass: function (v) { return v > 0 ? "vp-warn" : null; },
        cellRenderer: function (c) {
          if (!c.row.parametric) return null;
          var s = h("span", null, c.text);
          s.title = pct(c.row.parametric.gapPct) + " of the parametric VaR";
          return s;
        } })];
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
    window.Drishti.keys.register({ id: "var.parametric.curve", keys: ["N", "Shift+N"], scope: "screen", area: "var", group: "VaR", keepDefault: true,
      description: "Parametric VaR: the normal curve on or off", run: function () { setCurve(!curve); } });
    $("[data-vp-play]").addEventListener("click", function (ev) {
      ev.preventDefault();
      location.href = playLink();
    });
  }

  window.Drishti.VarParametric = { start: start, headline: headline, treeColumns: treeColumns, on: function () { return ON; },
    curveOn: function () { return curve; } };
})();
