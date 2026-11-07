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
/* The MR lens of the pivot screen (MCUBE; docs/guides/MR_CUBE_GUIDE.md; MR_CUBE_LENS.md section 6.7): the pivot is the pivot engine's own
   grid (drishti_pivot.js, drishti_pivot-grid.js) on source var; this file draws what the MR cube adds around it. Layout A: the headline strip, the lens
   controls (they change the view's options and the engine answers again), the time-travel slider, sparklines in the grid's cells and the
   rail of a clicked cell's tail and explain (the one Explain). Tab B: components (the engine's comp_var by the first row dimension), the waterfall, the
   view's histogram, the factor heat map. Tab C: the DV01 ladder, the what-if side by side, explain. Every number is the server's
   (/api/pivot/... and /api/pivot/mr/...); this file only draws. Keys: H 1D/10D, [ and ] step the date, W the what-if tray. */
(function () {
  "use strict";
  var S = window.Drishti.PivotScreen;
  if (!S || S.root.getAttribute("data-lens") !== "mr") { return; }
  var root = S.root;
  function $(s) { return root.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(root.querySelectorAll(s)); }
  var st = { versions: null, compare: null, tray: [], cell: null, answer: null };

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function isNum(v) { return typeof v === "number" && isFinite(v); }
  function money(v) { return isNum(v) ? (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { maximumFractionDigits: 0 }) : "—"; }
  function compact(v) {
    if (!isNum(v)) { return "—"; }
    var a = Math.abs(v), s = v < 0 ? "−" : "";
    return a >= 1e9 ? s + (a / 1e9).toFixed(1) + "bn" : a >= 1e6 ? s + (a / 1e6).toFixed(1) + "m" : a >= 1e3 ? s + (a / 1e3).toFixed(1) + "k" : s + a.toFixed(0);
  }
  function pct(v, d) { return isNum(v) ? (v * 100).toFixed(d == null ? 1 : d) + "%" : "—"; }
  function mr(method, path, body) { return S.call(method, "/mr" + path, body); }
  function opts() { var v = S.view(); return (v && v.options) || {}; }

  // ---- drawing (inline SVG in the theme's colours; nothing from a CDN) --------------------------------------------------------------
  function spark(values, w, h) {
    w = w || 64; h = h || 14;
    var xs = (values || []).map(function (v, i) { return isNum(v) ? [i, v] : null; }).filter(Boolean);
    if (xs.length < 2) { return ""; }
    var lo = Math.min.apply(null, xs.map(function (p) { return p[1]; })), hi = Math.max.apply(null, xs.map(function (p) { return p[1]; }));
    var n = values.length - 1 || 1, span = hi - lo || 1;
    var pts = xs.map(function (p) { return (p[0] / n * (w - 2) + 1).toFixed(1) + "," + (h - 2 - (p[1] - lo) / span * (h - 4)).toFixed(1); });
    return '<svg class="mr-spark" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + " " + h + '" aria-hidden="true"><polyline points="' + pts.join(" ")
      + '" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>';
  }
  function limitBar(use) {
    if (!isNum(use)) { return ""; }
    var cls = use > 1 ? "bad" : use >= 0.8 ? "warn" : "ok";
    return '<span class="mr-lbar ' + cls + '" title="' + pct(use) + ' of the limit"><span class="f" style="width:' + Math.min(100, use * 100 / 1.2).toFixed(1)
      + '%"></span><b class="tick" style="left:' + (80 / 1.2).toFixed(1) + '%"></b><b class="tick full" style="left:' + (100 / 1.2).toFixed(1) + '%"></b></span>';
  }
  function histogram(d, w, h) {
    w = w || 340; h = h || 150;
    if (!d || !d.counts || !d.counts.length) { return '<p class="pv-sub">No scenario P&amp;L for this cell.</p>'; }
    var n = d.counts.length, max = Math.max.apply(null, d.counts.concat([1])), lo = d.edges[0], hi = d.edges[n], span = hi - lo || 1;
    var bw = (w - 20) / n, top = 26, base = h - 20, out = [];
    function x(v) { return 10 + (v - lo) / span * (w - 20); }
    var varAt = -d.var, esEdge = d.tail && d.tail.length ? d.tail[d.tail.length - 1].pnl : varAt;
    for (var i = 0; i < n; i++) {
      var bh = d.counts[i] / max * (base - top);
      var cls = d.edges[i + 1] <= esEdge + 1e-9 ? "es" : (varAt >= d.edges[i] && varAt <= d.edges[i + 1] ? "v" : "");
      out.push('<rect class="bin ' + cls + '" x="' + (10 + i * bw + 0.5).toFixed(1) + '" y="' + (base - bh).toFixed(1) + '" width="' + Math.max(1, bw - 1).toFixed(1)
        + '" height="' + bh.toFixed(1) + '"><title>' + d.counts[i] + " scenarios, " + compact(d.edges[i]) + " to " + compact(d.edges[i + 1]) + "</title></rect>");
    }
    var vx = x(varAt);
    out.push('<line class="vline" x1="' + vx.toFixed(1) + '" x2="' + vx.toFixed(1) + '" y1="' + (top - 14) + '" y2="' + base + '"/>');
    out.push('<text class="vlab" x="' + (vx + 3).toFixed(1) + '" y="' + (top - 4) + '">V −VaR ' + compact(d.var) + " (#" + d.k + " of " + d.scenarios + ")</text>");
    out.push('<text class="elab" x="10" y="' + (top + 8) + '">E ES tail, ' + d.esCount + " worst</text>");
    if (lo < 0 && hi > 0) { out.push('<line class="zero" x1="' + x(0).toFixed(1) + '" x2="' + x(0).toFixed(1) + '" y1="' + top + '" y2="' + base + '"/>'); }
    out.push('<text class="ax" x="10" y="' + (h - 4) + '">' + compact(lo) + '</text><text class="ax" x="' + (w - 10) + '" y="' + (h - 4) + '" text-anchor="end">' + compact(hi) + "</text>");
    return '<svg class="mr-histo" viewBox="0 0 ' + w + " " + h + '" width="100%" role="img" aria-label="Scenario P&amp;L histogram: VaR ' + esc(money(d.var))
      + ", ES " + esc(money(d.es)) + '">' + out.join("") + "</svg>";
  }
  function waterfall(parts, total) {
    var w = 640, h = 260;
    var ps = parts.filter(function (p) { return isNum(p.value); }).sort(function (a, b) { return b.value - a.value; });
    if (!ps.length) { return '<p class="pv-sub">No components.</p>'; }
    var run = 0, hi = 0, lo = 0;
    ps.forEach(function (p) { run += p.value; hi = Math.max(hi, run); lo = Math.min(lo, run); });
    hi = Math.max(hi, total || 0);
    var top = 24, base = h - 28, span = hi - lo || 1, bw = (w - 20) / (ps.length + 1), out = [], at = 0;
    function y(v) { return top + (hi - v) / span * (base - top); }
    ps.forEach(function (p, i) {
      var a = at, b = at + p.value, x0 = 10 + i * bw;
      out.push('<rect class="' + (p.value < 0 ? "hedge" : "add") + '" x="' + (x0 + 3).toFixed(1) + '" y="' + y(Math.max(a, b)).toFixed(1) + '" width="' + (bw - 6).toFixed(1)
        + '" height="' + Math.max(1, Math.abs(y(a) - y(b))).toFixed(1) + '"><title>' + esc(p.label) + ": " + esc(money(p.value)) + "</title></rect>");
      out.push('<text class="wl" x="' + (x0 + bw / 2).toFixed(1) + '" y="' + (y(Math.max(a, b)) - 3).toFixed(1) + '" text-anchor="middle">' + compact(p.value) + "</text>");
      out.push('<text class="wx" x="' + (x0 + bw / 2).toFixed(1) + '" y="' + (h - 10) + '" text-anchor="middle">' + esc(String(p.label).slice(0, 10)) + "</text>");
      at = b;
    });
    var tx = 10 + ps.length * bw;
    out.push('<rect class="tot" x="' + (tx + 3).toFixed(1) + '" y="' + y(Math.max(0, total)).toFixed(1) + '" width="' + (bw - 6).toFixed(1) + '" height="'
      + Math.max(1, Math.abs(y(0) - y(total))).toFixed(1) + '"><title>Total ' + esc(money(total)) + "</title></rect>");
    out.push('<text class="wl tot" x="' + (tx + bw / 2).toFixed(1) + '" y="' + (y(Math.max(0, total)) - 3).toFixed(1) + '" text-anchor="middle">' + compact(total) + "</text>");
    out.push('<text class="wx" x="' + (tx + bw / 2).toFixed(1) + '" y="' + (h - 10) + '" text-anchor="middle">Total</text>');
    return '<svg class="mr-water" viewBox="0 0 ' + w + " " + h + '" width="100%" role="img" aria-label="VaR attribution waterfall">' + out.join("") + "</svg>";
  }
  function ladderBar(net, limit, status, scale) {
    var half = 50, s = scale || Math.abs(net) || 1, len = Math.min(half, Math.abs(net) / s * half), x = net >= 0 ? half : half - len;
    var cls = status === "breach" ? "bad" : status === "near" ? "warn" : "ok";
    var ticks = isNum(limit) ? '<b class="tick" style="left:' + Math.max(0, half - limit / s * half).toFixed(1) + '%"></b><b class="tick" style="left:'
      + Math.min(100, half + limit / s * half).toFixed(1) + '%"></b>' : "";
    return '<span class="mr-cbar ' + cls + '"><span class="f" style="left:' + x.toFixed(1) + "%;width:" + len.toFixed(1) + '%"></span><b class="zero"></b>' + ticks + "</span>";
  }
  function heat(v, full) {
    if (!isNum(v) || !full) { return ""; }
    var a = (Math.min(1, Math.abs(v) / full) * 0.55 + 0.05) * 100;
    return "background: color-mix(in srgb, var(" + (v >= 0 ? "--d-ok" : "--d-bad") + ") " + a.toFixed(0) + "%, transparent)";
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
        tile("Limit use", isNum(h.limitUse) ? pct(h.limitUse) : "—", isNum(h.limit) ? "of " + money(h.limit) : esc(h.limitNote || "no VaR limit on this scope"), limitBar(h.limitUse)),
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
      var drawn = 0;
      Object.keys(h.series).forEach(function (key) {
        var td = root.querySelector('[data-pv-grid] td[data-cell="' + (window.CSS && CSS.escape ? CSS.escape(key) : key) + '"][data-m="0"]');
        var svg = spark(h.series[key]);
        if (td && svg && !td.querySelector(".mr-spark")) { td.insertAdjacentHTML("beforeend", svg); drawn++; }
      });
      box.innerHTML = '<p class="pv-sub">' + (h.dates.length < 2 ? "One open date: a sparkline needs two. Open earlier dates with the slider."
        : drawn + " cells carry a sparkline of " + esc(h.measure) + " from " + esc(h.dates[0]) + " to " + esc(h.dates[h.dates.length - 1]) + ". " + esc(h.how)) + "</p>";
    }).catch(function (e) { box.innerHTML = '<p class="pv-sub">' + esc(e.message) + "</p>"; });
  }
  function tail(detail) {
    var key = detail.key, coords = detail.coords || {}, cell = detail.cell || {};
    st.cell = { coords: coords, scope: cell.scope, key: key };
    var name = Object.keys(coords).map(function (k) { return coords[k]; }).join(" × ") || "the view's total";
    $("[data-mr-tail-title]").innerHTML = esc(name) + ' <span class="pv-sub">tail</span>';
    $("[data-mr-tail]").innerHTML = '<div class="mr-skel"></div>';
    mr("POST", "/detail", { view: S.view(), coords: coords }).then(function (x) {
      $("[data-mr-tail]").innerHTML = histogram(x, 340, 150) + '<p class="pv-sub">VaR scenario ' + esc(x.varDate || "?") + " · " + (x.trades || 0)
        + " trades in " + (x.positions || 0) + " positions · " + esc(x.method || "") + "</p>";
    }).catch(function (e) { $("[data-mr-tail]").innerHTML = '<p class="pv-sub">' + esc(e.message) + "</p>"; });
    explain("[data-mr-explain]", "[data-mr-explain-dates]", cell.scope);
  }
  /** Explain the move (A7, C4): the one Explain of the cell's VaR (drishti_explain.js; SHARED_SCREENS.md section 3) at Why it moved, the
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
      $("[data-mr-comp]").innerHTML = '<table class="mr-mini wide"><thead><tr><th class="l">' + esc((a.rowDimensions || [])[0] || "") + "</th><th>Comp VaR</th><th>% of VaR</th><th>Standalone VaR</th></tr></thead><tbody>"
        + parts.map(function (p) {
          return '<tr><td class="l">' + esc(p.label) + '</td><td class="mono ' + (p.value < 0 ? "down" : "") + '">' + money(p.value) + '</td><td class="mono">'
            + pct(total ? p.value / total : null) + '</td><td class="mono">' + money(p.standalone) + "</td></tr>";
        }).join("") + '</tbody><tfoot><tr class="tot"><td class="l">Total</td><td class="mono">' + money(grand && grand["Comp VaR"]) + '</td><td class="mono">100.0%</td><td class="mono">'
        + money(total) + "</td></tr></tfoot></table>";
      $("[data-mr-waterfall]").innerHTML = waterfall(parts, total);
    }).catch(function (e) { $("[data-mr-comp]").innerHTML = '<p class="pv-sub">' + esc(e.message) + "</p>"; });
    mr("POST", "/detail", { view: S.view(), coords: {} }).then(function (x) {
      $("[data-mr-hist-title]").textContent = (x.scope || "the view") + " · " + x.scenarios + " scenario P&Ls · V and E marked";
      $("[data-mr-hist]").innerHTML = histogram(x, 520, 170);
    }).catch(function (e) { $("[data-mr-hist]").innerHTML = '<p class="pv-sub">' + esc(e.message) + "</p>"; });
    sens($("[data-mr-heat]"), { by: "QUALIFIER,TENOR" }, heatTable);
  }

  function sens(el, extra, draw) {
    var q = new URLSearchParams(Object.assign({ scope: opts().scope || "BANK" }, extra));
    if (st.answer && st.answer.date) { q.set("asOf", st.answer.date); }
    el.innerHTML = '<div class="mr-skel"></div>';
    mr("GET", "/sensitivities?" + q).then(function (s) { el.innerHTML = draw(s); }).catch(function (e) {
      el.innerHTML = '<p class="pv-sub">' + esc(/DRS-1020/.test(e.message) ? "The sensitivities engine is off on this server." : e.message) + "</p>";
    });
  }
  function heatTable(s) {
    if (!s.rows.length) { return '<p class="pv-sub">No sensitivities of the scope on ' + esc(s.date) + " (GIRR delta).</p>"; }
    var full = 0;
    s.rows.forEach(function (r) { r.cells.forEach(function (c) { if (isNum(c.net)) { full = Math.max(full, Math.abs(c.net)); } }); });
    return '<table class="mr-mini mr-heat"><thead><tr><th class="l">' + esc(s.by[0]) + " \\ " + esc(s.by[1]) + "</th>" + s.columns.map(function (c) { return "<th>" + esc(c) + "</th>"; }).join("")
      + "</tr></thead><tbody>" + s.rows.map(function (r) {
        return '<tr><th class="l">' + esc(r.key) + "</th>" + r.cells.map(function (c) {
          return '<td class="mono" style="' + heat(c.net, full) + '" title="' + (c.positions || 0) + ' positions">' + (isNum(c.net) ? (c.net < 0 ? "▼ " : "▲ ") + compact(c.net) : "·") + "</td>";
        }).join("") + "</tr>";
      }).join("") + '</tbody></table><p class="pv-sub">' + esc(s.currency) + " per bp · ▲ long (green), ▼ short (red), every cell printed · " + s.elements
      + " elements of " + s.records + " records</p>";
  }

  function tabC() {
    var pick = $("[data-mr-grid-pick]");
    sens($("[data-mr-ladder]"), { grid: pick.value || "BANK-IR-TENOR" }, function (s) {
      if (!pick.options.length) {
        pick.innerHTML = (s.grids || []).map(function (g) { return "<option" + (g === s.grid ? " selected" : "") + ">" + esc(g) + "</option>"; }).join("");
      }
      if (!s.rows.length) { return '<p class="pv-sub">No GIRR delta of the scope on ' + esc(s.date) + ".</p>"; }
      var scale = 0;
      s.rows.forEach(function (r) { r.cells.forEach(function (c) { scale = Math.max(scale, Math.abs(c.net || 0), c.limit || 0); }); });
      return '<table class="mr-mini mr-ladder"><thead><tr><th class="l">DV01 (' + esc(s.grid) + ")</th>" + s.columns.map(function (c) { return "<th>" + esc(c) + "</th>"; }).join("")
        + "<th>Net</th></tr></thead><tbody>" + s.rows.map(function (r) {
          return '<tr><th class="l">' + esc(r.key) + "</th>" + r.cells.map(function (c) {
            if (!isNum(c.net)) { return '<td class="pv-sub">·</td>'; }
            var badge = c.status === "breach" ? '<span class="mr-badge bad">breach</span>' : c.status === "near" ? '<span class="mr-badge warn">near</span>' : "";
            return '<td><span class="mono">' + compact(c.net) + (isNum(c.use) ? " · " + pct(c.use, 0) : "") + "</span>" + badge + ladderBar(c.net, c.limit, c.status, scale) + "</td>";
          }).join("") + '<td class="mono">' + compact(r.net) + "</td></tr>";
        }).join("") + '</tbody></table><p class="pv-sub">' + esc(s.limits) + " · centre zero: long right, short left · " + s.elements + " elements</p>";
    });
    whatIf();
    explain("[data-mr-explain2]", "[data-mr-explain2-dates]", (st.cell && st.cell.scope) || opts().scope || "BANK");
  }

  function whatIf() {
    $("[data-mr-tray-items]").textContent = st.tray.map(function (t) { return t.copyOf + " × " + t.factor; }).join(" · ");
    if (!st.tray.length) { return; }
    $("[data-mr-whatif]").innerHTML = '<div class="mr-skel"></div>';
    mr("POST", "/what-if", { view: S.view(), items: st.tray }).then(function (w) {
      $("[data-mr-whatif]").innerHTML = '<table class="mr-mini wide"><thead><tr><th class="l">Scope</th><th>Before</th><th>After</th><th>Incremental</th></tr></thead><tbody>'
        + w.rows.map(function (r) {
          var changed = isNum(r.incremental) && isNum(r.before) && Math.abs(r.incremental) > Math.abs(r.before) * 0.005;
          return '<tr><td class="l">' + esc(r.label) + "</td>" + (r.note ? '<td colspan="3" class="pv-sub">' + esc(r.note) + "</td>" : '<td class="mono">' + money(r.before)
            + '</td><td class="mono' + (changed ? " changed" : "") + '">' + money(r.after) + '</td><td class="mono ' + (r.incremental < 0 ? "down" : r.incremental > 0 ? "up" : "")
            + '">' + (r.incremental > 0 ? "+" : "") + money(r.incremental) + "</td>") + "</tr>";
        }).join("") + '</tbody></table><p class="pv-sub">' + esc(w.engine) + "</p>";
    }).catch(function (e) { $("[data-mr-whatif]").innerHTML = '<p class="pv-sub">' + esc(e.message) + "</p>"; });
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
  $("[data-mr-grid-pick]").addEventListener("change", tabC);
  document.addEventListener("keydown", function (ev) {
    if (ev.target.closest("input, textarea, select") || ev.ctrlKey || ev.metaKey || ev.altKey || !st.answer) { return; }
    var k = ev.key;
    if (k === "h" || k === "H") { setLens("horizon", opts().horizon ? "1d" : "10d"); }
    else if (k === "[") { step(-1); }
    else if (k === "]") { step(1); }
    else if (k === "w" || k === "W") { showTab("sensitivities"); $("[data-mr-tray] input").focus(); ev.preventDefault(); }
  });
})();
