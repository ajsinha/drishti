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
/* The MR lens of the pivot screen (MCUBE; docs/guides/MR_CUBE_GUIDE.md; docs/lenses/README.md section 6.7): the pivot is the pivot engine's own
   grid (drishti_pivot.js, drishti_pivot-grid.js) on source var; this file draws what the MR cube adds around it. Layout A: the headline strip, the lens
   controls (they change the view's options and the engine answers again), the time-travel slider, sparklines in the grid's cells and the
   rail of a clicked cell's tail and explain (the one Explain). Tab B: components (the engine's comp_var by the first row dimension), the waterfall, the
   view's histogram, the factor heat map. Tab C: the DV01 ladder, the what-if side by side, explain. Every number is the server's
   (/api/pivot/... and /api/pivot/mr/...); this file only draws, with Drishti Elements: the histograms are drishti-risk's
   risk.pnl-distribution of the server's bins, the waterfall a drishti-chart waterfall, the components, heat map, ladder and what-if risk
   grids (<drishti-grid>, terminal layout), the sparklines drishti-core's. Keys: H 1D/10D, [ and ] step the date, W the what-if tray. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the risk grid preset and chart (drishti-risk)
  var S = window.Drishti.PivotScreen;
  if (!S || S.root.getAttribute("data-lens") !== "mr") { return; }
  var root = S.root;
  function $(s) { return root.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(root.querySelectorAll(s)); }
  var st = { versions: null, compare: null, tray: [], cell: null, answer: null, sparks: {} };

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function isNum(v) { return typeof v === "number" && isFinite(v); }
  function money(v) { return isNum(v) ? F.formatNumber(v) : "—"; }
  function compact(v) { return isNum(v) ? F.formatCompact(v, { decimals: 1, smallDecimals: 0 }) : "—"; }
  function pct(v, d) { return isNum(v) ? (v * 100).toFixed(d == null ? 1 : d) + "%" : "—"; }
  function mr(method, path, body) { return S.call(method, "/mr" + path, body); }
  function opts() { var v = S.view(); return (v && v.options) || {}; }

  // ---- drawing (Drishti Elements in the theme's colours) ----------------------------------------------------------------------------
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text !== undefined && text !== null) { e.textContent = text; } return e; }
  function note(box, text) { box.textContent = ""; box.appendChild(el("p", "pv-sub", text)); }
  /** A cell's sparkline: drishti-core's, of the values the server gave (two at least). */
  function spark(values) {
    var xs = (values || []).filter(isNum);
    if (xs.length < 2) { return null; }
    var s = el("span", "mr-spark");
    s.setAttribute("aria-hidden", "true");
    s.appendChild(F.sparkline(document, xs, undefined, "dg"));
    return s;
  }
  /** The limit's bar: its tone the server's status (within, near, breach), its near tick the server's rule (drishti.risk.limit-near). */
  var LIMIT_TONE = { within: "ok", near: "warn", breach: "bad" };
  function limitBar(use, status, near) {
    if (!isNum(use)) { return ""; }
    var cls = (status && LIMIT_TONE[status]) || "";
    return '<span class="mr-lbar ' + cls + '" title="' + pct(use) + ' of the limit"><span class="f" style="width:' + Math.min(100, use * 100 / 1.2).toFixed(1)
      + '%"></span>' + (isNum(near) ? '<b class="tick" style="left:' + (near * 100 / 1.2).toFixed(1) + '%"></b>' : "")
      + '<b class="tick full" style="left:' + (100 / 1.2).toFixed(1) + '%"></b></span>';
  }
  /** The server's scenario P&L of a scope as drishti-risk's P&L distribution: its bins, its VaR and ES (V and E on the lines). */
  function histogram(box, d, height) {
    box.textContent = "";
    if (!d || !d.counts || !d.counts.length) { note(box, "No scenario P&L for this cell."); return null; }
    var host = el("div", "mr-chart");
    box.appendChild(host);
    R.riskChart(host, { kind: "risk.pnl-distribution", data: { rows: [] }, binned: { edges: d.edges, counts: d.counts }, var: d.var, es: d.es,
      confidence: { var: d.confidence || 0.99, es: d.esConfidence || 0.975 }, value: { title: "scenario P&L", format: "compact" },
      subtitle: "V −VaR " + compact(d.var) + " (#" + d.k + " of " + d.scenarios + ") · E ES tail, " + d.esCount + " worst" }, { height: height });
    return host;
  }
  /** The Euler components as a waterfall to the total: adds bad, hedges good, the total the server's VaR. */
  function waterfall(box, parts, total) {
    var ps = parts.filter(function (p) { return isNum(p.value); }).sort(function (a, b) { return b.value - a.value; });
    if (!ps.length) { note(box, "No components."); return; }
    var rows = ps.map(function (p) { return { step: String(p.label), change: p.value }; }).concat([{ step: "Total", change: total, total: true }]);
    R.riskChart(box, { kind: "waterfall", data: { rows: rows }, id: "step", category: { field: "step" }, value: { field: "change", format: "compact" },
      totals: { field: "total" }, up: "bad", down: "good", total: "warn", labels: true, legend: "none" }, { height: 260 });
  }
  /** A DV01 cell of the ladder: the net and its use, the breach or near badge, the centre-zero bar against the limit (CSSOM widths). */
  function ladderCell(c, scale) {
    var box = el("span", "mr-lcell");
    if (!isNum(c.net)) { box.appendChild(el("span", "pv-sub", "·")); return box; }
    box.appendChild(el("span", "mono", compact(c.net) + (isNum(c.use) ? " · " + pct(c.use, 0) : "")));
    if (c.status === "breach" || c.status === "near") { box.appendChild(el("span", "mr-badge " + (c.status === "breach" ? "bad" : "warn"), c.status)); }
    var half = 50, s = scale || Math.abs(c.net) || 1, len = Math.min(half, Math.abs(c.net) / s * half);
    var bar = el("span", "mr-cbar " + (c.status === "breach" ? "bad" : c.status === "near" ? "warn" : "ok")), f = el("span", "f");
    f.style.left = (c.net >= 0 ? half : half - len).toFixed(1) + "%";
    f.style.width = len.toFixed(1) + "%";
    bar.appendChild(f);
    bar.appendChild(el("b", "zero"));
    if (isNum(c.limit)) {
      [Math.max(0, half - c.limit / s * half), Math.min(100, half + c.limit / s * half)].forEach(function (at) { var t = el("b", "tick"); t.style.left = at.toFixed(1) + "%"; bar.appendChild(t); });
    }
    box.appendChild(bar);
    return box;
  }
  /** A heat map cell: ▲ long or ▼ short with the figure, its ground the share of the largest (CSSOM). */
  function heatCell(c, full) {
    var s = el("span", "mr-hcell", isNum(c.net) ? (c.net < 0 ? "▼ " : "▲ ") + compact(c.net) : "·");
    s.title = (c.positions || 0) + " positions";
    if (isNum(c.net) && full) {
      var a = (Math.min(1, Math.abs(c.net) / full) * 0.55 + 0.05) * 100;
      s.style.background = "color-mix(in srgb, var(" + (c.net >= 0 ? "--d-ok" : "--d-bad") + ") " + a.toFixed(0) + "%, transparent)";
    }
    return s;
  }

  // ---- A2: the headline strip ------------------------------------------------------------------------------------------------------
  function tile(label, value, sub, extra) {
    return '<div class="mr-tile"><span class="mr-tl">' + esc(label) + '</span><span class="mr-tv mono">' + value + "</span>" + (extra || "")
      + '<span class="mr-ts">' + sub + "</span></div>";
  }
  function headline(a) {
    var box = $("[data-mr-tiles]");
    box.innerHTML = '<div class="mr-tile skel"></div><div class="mr-tile skel"></div><div class="mr-tile skel"></div>';
    mr("POST", "/headline", { view: S.view() }).then(function (h) {
      var div = null, first = (a.measures || [])[0];
      (a.rows || []).forEach(function (r) { if (r.kind === "grand" && r.diversification && first) { div = r.diversification[first.label]; } });
      var intraday = h.intraday && h.intraday.batchCount ? " + " + h.intraday.batchCount + " intraday" : "";
      box.innerHTML = [
        tile("VaR " + pct(h.confidence, 1) + " " + (h.lens || "") + " · " + h.scope, money(h.var), (h.k ? h.k + "th worst of " + h.scenarios : "") + " · " + esc(h.currency || "")),
        tile("ES " + pct(h.esConfidence, 1), money(h.es), "mean of the " + (h.esCount || "?") + " worst"),
        tile("Limit use", isNum(h.limitUse) ? pct(h.limitUse) : "—", isNum(h.limit) ? "of " + money(h.limit) : esc(h.limitNote || "no VaR limit on this scope"), limitBar(h.limitUse, h.limitStatus, h.limitNear)),
        tile("Σ " + ((a.rowDimensions || [])[0] || "rows").toLowerCase() + " − total", isNum(div) ? money(div) : "—", "diversification" + (first ? " (" + esc(first.label) + ")" : "")),
        tile("Stressed VaR", isNum(h.stressedVar) ? money(h.stressedVar) : "—", esc(isNum(h.stressedVar) ? "the scope on the stress set" : h.stressedNote || "")),
        tile("Data", esc((h.batches || []).join(", ") || "—") + esc(intraday), "data version " + esc(h.dataVersion || "?") + " · " + (h.trades || 0).toLocaleString("en-US")
          + " trades in " + (h.positions || 0).toLocaleString("en-US") + " positions")
      ].join("");
    }).catch(function (e) { box.innerHTML = '<p class="pv-notice">' + esc(e.message) + "</p>"; });
  }

  // ---- A3: the lens controls ---------------------------------------------------------------------------------------------------------
  function marks() {
    var o = opts(), v = S.view();
    var hz = o.horizon === "10d" ? (o.method === "sum" ? "10d-sum" : "10d") : "1d";
    var first = v && v.values[0] ? v.values[0].measure.replace(/\(.*$/, "") : "var";
    var on = { measure: first, horizon: hz, confidence: o.confidence || "0.99", set: o.set || "normal", ccy: (o.ccy || "USD").toUpperCase() };
    var natives = ((S.catalogue() || {}).measures || []).map(function (m) { return m.name; });
    $$("[data-mr-seg] button").forEach(function (b) {
      var seg = b.parentNode.getAttribute("data-mr-seg");
      b.setAttribute("aria-pressed", String(on[seg] === b.getAttribute("data-v")));
      if (seg === "measure") { b.disabled = natives.length > 0 && natives.indexOf(b.getAttribute("data-v")) < 0; b.title = b.disabled ? "its engine is not running here" : ""; }
    });
    $("[data-mr-scope]").value = o.scope || "BANK";
  }
  function setLens(seg, val) {
    S.run(function (v) {
      var o = v.options = Object.assign({}, v.options || {});
      if (seg === "measure") {
        var label = { "var": "VaR", es: "ES", comp_var: "Comp VaR", dv01: "DV01", sbm: "SBM" }[val];
        v.values = [{ measure: val + "()", as: label }].concat(v.values.filter(function (x) { return x.measure !== val + "()"; })).slice(0, 4);
      } else if (seg === "horizon") {
        delete o.horizon; delete o.method;
        if (val !== "1d") { o.horizon = "10d"; if (val === "10d-sum") { o.method = "sum"; } }
      } else if (seg === "confidence") {
        if (val === "0.99") { delete o.confidence; } else { o.confidence = val; }
      } else if (seg === "set") {
        if (val === "normal") { delete o.set; } else { o.set = val; delete o.ccy; }
      } else if (seg === "ccy") {
        if (val === "USD") { delete o.ccy; } else { o.ccy = val; }
      } else if (seg === "scope") {
        o.scope = val || "BANK";
        v.expanded = [];
      }
    });
  }

  // ---- A4: the slider ---------------------------------------------------------------------------------------------------------------
  function slider() {
    var v = st.versions, a = st.answer, box = $("[data-mr-slider]");
    if (!v || !a) { return; }
    var stops = v.dates || [];
    box.innerHTML = '<span class="mr-track">' + stops.map(function (s) {
      var cls = (s.date === a.date ? " asof" : "") + (s.date === st.compare ? " cmp" : "") + (s.official ? " official" : "");
      return '<button type="button" class="mr-stop' + cls + '" data-date="' + esc(s.date) + '" title="' + esc(s.date + (s.official ? " · official" : "")
        + (s.batches ? " · " + s.batches.join(", ") : "") + (s.open ? " · open" : " · opened when chosen")) + '">' + esc(s.date.slice(5)) + (s.official ? "·o" : "") + "</button>";
    }).join("") + '</span><span class="mr-asof mono">as of ' + esc(a.date) + (st.compare ? " · compare " + esc(st.compare) : "")
      + " · click: as of; shift-click: compare; [ and ] step</span>";
  }
  function step(d) {
    var stops = ((st.versions || {}).dates || []).map(function (s) { return s.date; });
    var i = stops.indexOf(st.answer.date) + d;
    if (i >= 0 && i < stops.length) { S.reopen(stops[i]); }
  }

  // ---- A5: sparklines in the grid's cells, A6 the tail, A7 explain --------------------------------------------------------------------
  function sparklines() {
    var sub = $("[data-mr-spark-sub]"), box = $("[data-mr-sparks]");
    mr("POST", "/history", { view: S.view() }).then(function (h) {
      sub.textContent = h.measure + " · " + h.dates.length + " open date" + (h.dates.length === 1 ? "" : "s") + (h.note ? " · " + h.note : "");
      var drawn = 0, cells = (st.answer && st.answer.cells) || {};
      st.sparks = {};
      Object.keys(h.series).forEach(function (key) {
        var line = spark(h.series[key]);                           // drishti-core's sparkline (an element)
        if (line && cells[key]) { st.sparks[key] = line; drawn++; }
      });
      S.redraw();                                                  // the grid draws them in the first measure's cells (sparkMark)
      box.innerHTML = '<p class="pv-sub">' + (h.dates.length < 2 ? "One open date: a sparkline needs two. Open earlier dates with the slider."
        : drawn + " cells carry a sparkline of " + esc(h.measure) + " from " + esc(h.dates[0]) + " to " + esc(h.dates[h.dates.length - 1]) + ". " + esc(h.how)) + "</p>";
    }).catch(function (e) { box.innerHTML = '<p class="pv-sub">' + esc(e.message) + "</p>"; });
  }
  /** A cell's sparkline, drawn by the grid after the figure (the screen's decorate): the first measure's cells only. */
  function sparkMark(key, ctx) {
    var line = st.sparks && st.sparks[key], first = st.answer && (st.answer.measures || [])[0];
    if (!line || !first || ctx.measure !== first.label) { return null; }
    return { after: line.cloneNode(true) };                      // a copy each draw: the grid redraws its rows
  }
  S.decorate(sparkMark);

  function tail(detail) {
    var key = detail.key, coords = detail.coords || {}, cell = detail.cell || {};
    st.cell = { coords: coords, scope: cell.scope, key: key };
    var name = Object.keys(coords).map(function (k) { return coords[k]; }).join(" × ") || "the view's total";
    $("[data-mr-tail-title]").innerHTML = esc(name) + ' <span class="pv-sub">tail</span>';
    $("[data-mr-tail]").innerHTML = '<div class="mr-skel"></div>';
    mr("POST", "/detail", { view: S.view(), coords: coords }).then(function (x) {
      var box = $("[data-mr-tail]");
      if (histogram(box, x, 170)) {
        box.appendChild(el("p", "pv-sub", "VaR scenario " + (x.varDate || "?") + " · " + (x.trades || 0) + " trades in " + (x.positions || 0) + " positions · " + (x.method || "")));
      }
    }).catch(function (e) { note($("[data-mr-tail]"), e.message); });
    explain("[data-mr-explain]", "[data-mr-explain-dates]", cell.scope);
  }
  /** Explain the move (A7, C4): the one Explain of the cell's VaR (drishti-insight; SHARED_SCREENS.md section 3) at Why it moved, the
      compare date (or the date before) to the view's: the card names it, hover previews it, a click opens the drawer. */
  function explain(target, datesEl, scope) {
    if (!scope) { $(target).innerHTML = '<p class="pv-sub">No scope text names this cell: explain asks the engine of a scope.</p>'; return; }
    var req = { kind: "var", measure: "var", scope: scope };
    if (st.compare) { req.from = st.compare; }
    if (st.answer && st.answer.date) { req.to = st.answer.date; req.date = st.answer.date; }
    $(datesEl).textContent = (req.from || "the date before") + " → " + (req.to || "the newest");
    var b = document.createElement("button");
    b.type = "button";
    b.className = "pv-btn";
    b.textContent = "Why it moved: " + scope + " ›";
    b.setAttribute("data-explain", JSON.stringify(req));
    b.setAttribute("data-explain-tab", "why");
    b.setAttribute("data-mr-explain-open", "");
    $(target).textContent = "";
    $(target).appendChild(b);
  }

  // ---- the tabs ------------------------------------------------------------------------------------------------------------------------
  function currentTab() { var t = $('.mr-tab[aria-selected="true"]'); return t ? t.getAttribute("data-mr-tab") : "pivot"; }
  function showTab(t) {
    $$(".mr-tab").forEach(function (b) { b.setAttribute("aria-selected", String(b.getAttribute("data-mr-tab") === t)); });
    $$("[data-mr-pane]").forEach(function (p) { p.hidden = p.getAttribute("data-mr-pane") !== t; });
    var main = $(".pv-main");
    if (main) { main.hidden = t !== "pivot"; }
    if (!st.answer) { return; }
    if (t === "tail") { tabB(); }
    if (t === "sensitivities") { tabC(); }
  }

  function tabB() {
    var v = S.view();
    v.columns = [];
    v.values = [{ measure: "comp_var()", as: "Comp VaR" }, { measure: "var()", as: "VaR" }];
    v.expanded = [];
    v.expandTo = { rows: 1, columns: 1 };
    v.rows = v.rows.slice(0, 1);
    $("[data-mr-comp]").innerHTML = '<div class="mr-skel"></div>';
    S.call("POST", "/query", { view: v }).then(function (a) {
      var grand = null, parts = [];
      var colKey = (a.columns[a.columns.length - 1] || {}).key;
      a.rows.forEach(function (r) {
        var c = a.cells[r.key + "|" + colKey];
        if (!c) { return; }
        if (r.kind === "grand") { grand = c.values; } else if (r.kind === "node" || r.kind === "others") { parts.push({ label: r.label, value: c.values["Comp VaR"], standalone: c.values.VaR }); }
      });
      var total = grand ? grand.VaR : null;
      parts.sort(function (x, y) { return (y.value || 0) - (x.value || 0); });
      // the share of VaR is the server's two figures divided for the reading; the total row is the server's grand total, never a sum here
      var rows = parts.map(function (p, i) { return { id: "p" + i, label: p.label, comp: p.value, share: total ? p.value / total : null, standalone: p.standalone }; })
        .concat([{ id: "total", label: "Total", comp: grand && grand["Comp VaR"], share: 1, standalone: total, total: true }]);
      R.riskGrid($("[data-mr-comp]"), { title: "Components", rows: rows, getRowId: function (r) { return r.id; }, getRowClass: function (r) { return r.total ? "mr-tot" : null; },
        columns: [{ field: "label", header: (a.rowDimensions || [])[0] || "", width: 170 },
          R.figureColumn("comp", "Comp VaR", { format: money, width: 130, tone: function (v) { return v < 0 ? "good" : null; } }),
          R.figureColumn("share", "% of VaR", { format: function (v) { return pct(v); }, width: 90 }),
          R.figureColumn("standalone", "Standalone VaR", { format: money, width: 140 })] });
      waterfall($("[data-mr-waterfall]"), parts, total);
    }).catch(function (e) { note($("[data-mr-comp]"), e.message); });
    mr("POST", "/detail", { view: S.view(), coords: {} }).then(function (x) {
      $("[data-mr-hist-title]").textContent = (x.scope || "the view") + " · " + x.scenarios + " scenario P&Ls · V and E marked";
      histogram($("[data-mr-hist]"), x, 220);
    }).catch(function (e) { note($("[data-mr-hist]"), e.message); });
    sens($("[data-mr-heat]"), { by: "QUALIFIER,TENOR" }, heatTable);
  }

  function sens(box, extra, draw) {
    var q = new URLSearchParams(Object.assign({ scope: opts().scope || "BANK" }, extra));
    if (st.answer && st.answer.date) { q.set("asOf", st.answer.date); }
    box.textContent = "";
    box.appendChild(el("div", "mr-skel"));
    mr("GET", "/sensitivities?" + q).then(function (s) { box.textContent = ""; draw(box, s); }).catch(function (e) {
      note(box, /DRS-1020/.test(e.message) ? "The sensitivities engine is off on this server." : e.message);
    });
  }
  /** A matrix of the server's sensitivities (rows by the first dimension, a column a bucket) as a risk grid; each cell drawn by draw(c). */
  function matrix(box, s, o) {
    var rows = s.rows.map(function (r) { var x = { id: r.key, key: r.key, net: r.net }; r.cells.forEach(function (c, i) { x["c" + i] = c; }); return x; });
    var cols = [{ field: "key", header: o.first, width: 120, pinned: "left" }].concat(s.columns.map(function (c, i) {
      return { id: "c" + i, header: String(c), type: "number", additive: false, width: o.width, sortable: false,
        valueGetter: function (r) { return r["c" + i] && isNum(r["c" + i].net) ? r["c" + i].net : null; }, format: function (v) { return isNum(v) ? compact(v) : "·"; },
        cellRenderer: function (x) { return o.cell(x.row["c" + i] || {}); } };
    })).concat(o.net ? [R.figureColumn("net", "Net", { format: compact, width: 90 })] : []);
    var host = el("div", "mr-gridhost");
    box.appendChild(host);
    R.riskGrid(host, { title: o.title, rows: rows, columns: cols, getRowId: function (r) { return r.id; } });
    box.appendChild(el("p", "pv-sub", o.foot));
  }
  function heatTable(box, s) {
    if (!s.rows.length) { note(box, "No sensitivities of the scope on " + s.date + " (GIRR delta)."); return; }
    var full = 0;
    s.rows.forEach(function (r) { r.cells.forEach(function (c) { if (isNum(c.net)) { full = Math.max(full, Math.abs(c.net)); } }); });
    matrix(box, s, { title: "Factor heat map", first: s.by[0] + " \\ " + s.by[1], width: 92, cell: function (c) { return heatCell(c, full); },
      foot: s.currency + " per bp · ▲ long (green), ▼ short (red), every cell printed · " + s.elements + " elements of " + s.records + " records" });
  }

  function tabC() {
    var pick = $("[data-mr-grid-pick]").component;                    // drishti-inputs' Select, filled with the grids on the first answer
    sens($("[data-mr-ladder]"), { grid: pick.getValue() || "BANK-IR-TENOR" }, function (box, s) {
      if (!pick.options.length) pick.setOptions(s.grids || [], s.grid);
      if (!s.rows.length) { note(box, "No GIRR delta of the scope on " + s.date + "."); return; }
      var scale = 0;
      s.rows.forEach(function (r) { r.cells.forEach(function (c) { scale = Math.max(scale, Math.abs(c.net || 0), c.limit || 0); }); });
      matrix(box, s, { title: "Sensitivity ladder", first: "DV01 (" + s.grid + ")", width: 130, net: true, cell: function (c) { return ladderCell(c, scale); },
        foot: s.limits + " · centre zero: long right, short left · " + s.elements + " elements" });
    });
    whatIf();
    explain("[data-mr-explain2]", "[data-mr-explain2-dates]", (st.cell && st.cell.scope) || opts().scope || "BANK");
  }

  function whatIf() {
    $("[data-mr-tray-items]").textContent = st.tray.map(function (t) { return t.copyOf + " × " + t.factor; }).join(" · ");
    if (!st.tray.length) { return; }
    var box = $("[data-mr-whatif]");
    box.textContent = "";
    box.appendChild(el("div", "mr-skel"));
    mr("POST", "/what-if", { view: S.view(), items: st.tray }).then(function (w) {
      box.textContent = "";
      var host = el("div", "mr-gridhost");
      box.appendChild(host);
      var rows = w.rows.map(function (r, i) { return Object.assign({ id: "w" + i }, r); });
      var val = function (f) { return function (r) { return r.note ? null : r[f]; }; };
      R.riskGrid(host, { title: "What-if", rows: rows, getRowId: function (r) { return r.id; },
        columns: [{ field: "label", header: "Scope", width: 170 },
          R.figureColumn("before", "Before", { valueGetter: val("before"), format: function (v, r) { return r && r.note ? r.note : money(v); }, width: 140 }),
          R.figureColumn("after", "After", { valueGetter: val("after"), format: function (v, r) { return r && r.note ? "" : money(v); }, width: 130,
            cellClass: function (v, r) { return isNum(r.incremental) && isNum(r.before) && Math.abs(r.incremental) > Math.abs(r.before) * 0.005 ? "mr-changed" : null; } }),
          R.figureColumn("incremental", "Incremental", { valueGetter: val("incremental"), width: 130,
            format: function (v, r) { return r && r.note ? "" : (v > 0 ? "+" : "") + money(v); }, tone: function (v) { return v < 0 ? "good" : v > 0 ? "bad" : null; } })] });
      box.appendChild(el("p", "pv-sub", w.engine));
    }).catch(function (e) { note(box, e.message); });
  }

  // ---- wiring ------------------------------------------------------------------------------------------------------------------------
  root.addEventListener("drishti:pivot-answer", function (ev) {
    st.answer = ev.detail.answer;
    marks();
    headline(st.answer);
    sparklines();
    if (!st.versions) {
      mr("GET", "/versions").then(function (v) { st.versions = v; slider(); }).catch(function () { $("[data-mr-slider]").innerHTML = ""; });
    } else { slider(); }
    var t = currentTab();
    if (t !== "pivot") { showTab(t); }
  });
  root.addEventListener("drishti:pivot-cell", function (ev) { tail(ev.detail); });
  root.addEventListener("click", function (ev) {
    var t = ev.target.closest("button[data-v], button[data-date], button[data-mr-tab]");
    if (!t || !root.contains(t)) { return; }
    if (t.hasAttribute("data-mr-tab")) { showTab(t.getAttribute("data-mr-tab")); return; }
    if (t.hasAttribute("data-date")) {
      if (ev.shiftKey) { st.compare = t.getAttribute("data-date"); slider(); } else { S.reopen(t.getAttribute("data-date")); }
      return;
    }
    var seg = t.closest("[data-mr-seg]");
    if (seg && !t.disabled) { setLens(seg.getAttribute("data-mr-seg"), t.getAttribute("data-v")); }
  });
  $("[data-mr-scope-form]").addEventListener("submit", function (ev) { ev.preventDefault(); setLens("scope", $("[data-mr-scope]").value.trim()); });
  $("[data-mr-tray]").addEventListener("submit", function (ev) {
    ev.preventDefault();
    var f = ev.target;
    st.tray.push({ copyOf: f.trade.value.trim(), factor: Number(f.factor.value) || 1 });
    f.trade.value = "";
    whatIf();
  });
  $("[data-mr-grid-pick]").addEventListener("drishti:change", tabC);
  var K = window.Drishti.keys, answered = function () { return !!st.answer; };
  K.register({ id: "mr.horizon", keys: ["H", "Shift+H"], scope: "screen", area: "mr-lens", group: "Market risk", description: "Switch the horizon: 1 day or 10 days",
    when: answered, run: function () { setLens("horizon", opts().horizon ? "1d" : "10d"); } });
  K.register({ id: "mr.step", keys: ["[", "]"], scope: "screen", area: "mr-lens", group: "Market risk", label: "[ ]", description: "The previous or next business date",
    when: answered, run: function (ev) { step(ev.key === "[" ? -1 : 1); } });
  K.register({ id: "mr.whatif", keys: ["W", "Shift+W"], scope: "screen", area: "mr-lens", group: "Market risk", description: "What if: the sensitivities tray",
    when: answered, run: function () { showTab("sensitivities"); $("[data-mr-tray] input").focus(); } });
})();
