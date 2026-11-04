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
/* The CCR lens of the pivot screen (CCUBE; docs/guides/EXPOSURE_GUIDE.md section 30; CCR_CUBE_LENS.md section 6.7, mock-ups A, B and C
   decided as views of one screen, A the default): the pivot is the pivot engine's own grid (pivot.js) on source ccr, the limit board; this
   file draws what the CCR cube adds. View A: the headline strip, the lens controls (they change the view's options or its first value and
   the engine answers again), the fan of the row clicked with the limit as a step line, the counterparties as a treemap. View B: a netting
   set's tiles, collateral effect, profile and trades. View C: a pre-deal copy of a booked trade (the exposure engine's exact what-if), the
   overlay, the group roll-up, the counterparty's SA-CCR EAD and FVA. Every number is a server's (/api/pivot/..., /api/pivot/ccr/...,
   /api/exposure/..., /api/saccr/..., /api/fva/...); this file only lays out and draws (a treemap's areas are layout). Keys: Q 95/99, K collateral, B view B, W pre-deal. */
(function () {
  "use strict";
  var S = window.PivotScreen;
  if (!S || S.root.getAttribute("data-lens") !== "ccr") { return; }
  var root = S.root;
  function $(s) { return root.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(root.querySelectorAll(s)); }
  var st = { answer: null, headline: null, scope: root.getAttribute("data-lens-scope") || "", nset: "", tab: root.getAttribute("data-lens-view") || "A" };

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function isNum(v) { return typeof v === "number" && isFinite(v); }
  function money(v) { return isNum(v) ? (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { maximumFractionDigits: 0 }) : "—"; }
  function compact(v) {
    if (!isNum(v)) { return "—"; }
    var a = Math.abs(v), s = v < 0 ? "−" : "";
    return a >= 1e9 ? s + (a / 1e9).toFixed(1) + "bn" : a >= 1e6 ? s + (a / 1e6).toFixed(1) + "m" : a >= 1e3 ? s + (a / 1e3).toFixed(1) + "k" : s + a.toFixed(0);
  }
  function pct(v) { return isNum(v) ? (v * 100).toFixed(1) + "%" : "—"; }
  function opts() { var v = S.view(); return (v && v.options) || {}; }
  function date() { var v = S.view(); return (v && v.date) || ""; }
  function q() { return opts().q || "0.95"; }
  function useClass(u) { return !isNum(u) ? "" : u > 1 ? "bad" : u >= ((st.headline || {}).warningAt || 0.8) ? "warn" : "ok"; }
  function get(path, params) {
    var p = Object.assign({ asOf: date() }, params || {});
    var qs = Object.keys(p).filter(function (k) { return p[k] !== "" && p[k] != null; }).map(function (k) { return k + "=" + encodeURIComponent(p[k]); }).join("&");
    return send("GET", path + (qs ? "?" + qs : ""));
  }
  function send(method, url, body) {
    var o = { method: method, headers: { "Accept": "application/json" }, credentials: "same-origin" };
    if (body !== undefined) { o.headers["Content-Type"] = "application/json"; o.body = JSON.stringify(body); }
    return fetch(url, o).then(function (r) {
      return r.text().then(function (t) {
        var j = {};
        try { j = t ? JSON.parse(t) : {}; } catch (e) { j = { detail: t }; }
        if (!r.ok) { throw new Error((j.code ? j.code + ": " : "") + (j.detail || j.message || r.statusText)); }
        return j;
      });
    });
  }
  function fail(el, e) { el.innerHTML = '<p class="pv-notice">' + esc(e.message) + ' <button type="button" class="pv-btn" data-ccr-retry>Retry</button></p>'; }
  function skel(el) { el.innerHTML = '<div class="mr-skel"></div>'; }
  function tile(label, value, sub, extra) {
    return '<div class="mr-tile"><span class="mr-tl">' + esc(label) + '</span><span class="mr-tv mono">' + value + "</span>" + (extra || "")
      + '<span class="mr-ts">' + sub + "</span></div>";
  }
  function bar(use) {
    if (!isNum(use)) { return ""; }
    return '<span class="mr-lbar ' + useClass(use) + '" title="' + pct(use) + ' of the limit"><span class="f" style="width:' + Math.min(100, use * 100 / 1.2).toFixed(1)
      + '%"></span><b class="tick" style="left:' + (80 / 1.2).toFixed(1) + '%"></b><b class="tick full" style="left:' + (100 / 1.2).toFixed(1) + '%"></b></span>';
  }

  // ---- A2: the headline strip ------------------------------------------------------------------------------------------------------
  function names(list) { return (list || []).map(function (r) { return esc(r.counterparty) + " " + esc(r.bucket) + " " + pct(r.use); }).join(" · "); }
  function headline() {
    var box = $("[data-ccr-tiles]");
    box.innerHTML = '<div class="mr-tile skel"></div><div class="mr-tile skel"></div><div class="mr-tile skel"></div>';
    S.call("POST", "/ccr/headline", { view: S.view() }).then(function (h) {
      st.headline = h;
      box.innerHTML = [
        tile("Peak PFE " + pct(h.confidence) + " · bank", money(h.peakPfe), "at " + esc(h.peakTenor || "—") + (isNum(h.pfe99) ? " · PFE 99% " + money(h.pfe99) : "")),
        tile("EPE · EEPE", compact(h.epe) + " · " + compact(h.eepe), "first year · CE " + compact(h.ce)),
        '<button type="button" class="mr-tile ccr-hit bad" data-ccr-filter="over">' + '<span class="mr-tl">Over limit</span><span class="mr-tv mono">' + h.overCount
          + '</span><span class="mr-ts">' + (names(h.over) || "none") + "</span></button>",
        '<button type="button" class="mr-tile ccr-hit warn" data-ccr-filter="near">' + '<span class="mr-tl">Near limit (≥' + Math.round((h.warningAt || 0.8) * 100)
          + '%)</span><span class="mr-tv mono">' + h.nearCount + '</span><span class="mr-ts">' + (names(h.near) || "none") + "</span></button>",
        tile("Run", esc(h.run), esc(h.runNote) + (h.note ? " · " + esc(h.note) : ""))
      ].join("");
    }).catch(function (e) { fail(box, e); });
  }

  // ---- A3: the lens controls -------------------------------------------------------------------------------------------------------
  function marks() {
    var v = S.view(), o = opts();
    var first = v && v.values[0] ? v.values[0].measure.replace(/\(.*$/, "") : "pfe";
    var on = { q: String(o.q || "0.95"), collateral: o.collateral || "lagged", measure: first };
    var natives = ((S.catalogue() || {}).measures || []).map(function (m) { return m.name; });
    $$("[data-ccr-seg] button").forEach(function (b) {
      var seg = b.parentNode.getAttribute("data-ccr-seg");
      b.setAttribute("aria-pressed", String(String(on[seg]) === b.getAttribute("data-v")));
      if (seg === "measure" && b.getAttribute("data-v") !== "cva") {
        b.disabled = natives.length > 0 && natives.indexOf(b.getAttribute("data-v")) < 0;
        b.title = b.disabled ? "its engine is not running here" : "";
      }
    });
  }
  var LABEL = { pfe: "Peak PFE", ee: "Peak EE", epe: "EPE", ead: "EAD SA-CCR", fva: "FVA" };
  function setLens(seg, val) {
    S.run(function (v) {
      if (seg === "measure") {
        var rest = v.values.filter(function (x) { return x.measure !== val + "()"; });
        v.values = [{ measure: val + "()", as: LABEL[val] || val }].concat(rest.slice(1)).slice(0, 4);
        return;
      }
      v.options = Object.assign({}, v.options || {});
      v.options[seg] = val;
    });
  }

  // ---- A6: the fan, and C2's overlay -----------------------------------------------------------------------------------------------
  function fanSvg(points, after, limitLabel) {
    var w = 640, h = 240, l = 44, r = 10, top = 16, base = h - 26;
    if (!points || !points.length) { return '<p class="pv-sub">No exposure paths for this scope on this date.</p>'; }
    var hi = 0;
    points.forEach(function (p, i) { hi = Math.max(hi, p.pfe || 0, p.ee || 0, p.limit || 0, after ? after[i] || 0 : 0); });
    hi = hi || 1;
    var n = points.length, dx = (w - l - r) / Math.max(1, n - 1);
    function x(i) { return l + i * dx; }
    function y(v) { return base - (v / hi) * (base - top); }
    function line(vals, cls) {
      return '<polyline class="' + cls + '" fill="none" points="' + vals.map(function (v, i) { return x(i).toFixed(1) + "," + y(v || 0).toFixed(1); }).join(" ") + '"/>';
    }
    var out = [];
    if (isNum(points[0].limit)) {
      var steps = [];
      points.forEach(function (p, i) { steps.push((i ? x(i - 0.5) : x(0)).toFixed(1) + "," + y(p.limit).toFixed(1), (i < n - 1 ? x(i + 0.5) : x(i)).toFixed(1) + "," + y(p.limit).toFixed(1)); });
      var shade = points.map(function (p, i) { return x(i).toFixed(1) + "," + y(p.pfe).toFixed(1); }).concat(points.slice().reverse().map(function (p, j) {
        return x(n - 1 - j).toFixed(1) + "," + y(p.limit).toFixed(1); }));
      out.push('<polygon class="head" points="' + shade.join(" ") + '"/>');
      out.push('<polyline class="lim" fill="none" points="' + steps.join(" ") + '"><title>' + esc(limitLabel || "limit") + "</title></polyline>");
    }
    out.push(line(points.map(function (p) { return p.ee; }), "ee"));
    out.push(line(points.map(function (p) { return p.pfe; }), "pfe"));
    if (after) { out.push(line(after, "after")); }
    var peak = 0, at = 0;
    points.forEach(function (p, i) { if ((p.pfe || 0) > peak) { peak = p.pfe; at = i; } });
    out.push('<circle class="pk" cx="' + x(at).toFixed(1) + '" cy="' + y(peak).toFixed(1) + '" r="3"/><text class="pkl" x="' + Math.min(w - 120, x(at) + 5).toFixed(1)
      + '" y="' + Math.max(12, y(peak) - 5).toFixed(1) + '">peak ' + compact(peak) + " (" + esc(points[at].tenor || "") + ")</text>");
    var every = Math.max(1, Math.ceil(n / 10));
    points.forEach(function (p, i) {
      if (i % every === 0 || i === n - 1) { out.push('<text class="ax" x="' + x(i).toFixed(1) + '" y="' + (h - 8) + '" text-anchor="middle">' + esc(p.tenor || "") + "</text>"); }
    });
    out.push('<text class="ax" x="' + (l - 4) + '" y="' + (top + 4) + '" text-anchor="end">' + compact(hi) + '</text><text class="ax" x="' + (l - 4) + '" y="' + base + '" text-anchor="end">0</text>');
    return '<svg class="ccr-fan" viewBox="0 0 ' + w + " " + h + '" width="100%" role="img" aria-label="EE and PFE by date with the limit">' + out.join("") + "</svg>"
      + '<p class="ccr-legend"><span class="k ee"></span>EE <span class="k pfe"></span>PFE ' + pct(Number(q())) + (after ? ' <span class="k after"></span>after' : "")
      + (isNum(points[0].limit) ? ' <span class="k lim"></span>limit' : "") + "</p>";
  }
  function fan(scope) {
    st.scope = scope || st.scope || "BANK";
    var box = $("[data-ccr-fan]");
    $("[data-ccr-fan-title]").innerHTML = esc(st.scope) + ' · PFE and EE fan <span class="pv-sub">limit step line, headroom shaded</span>';
    skel(box);
    S.call("GET", "/ccr/fan?" + ["scope=" + encodeURIComponent(st.scope), "q=" + encodeURIComponent(q()), "collateral=" + encodeURIComponent(opts().collateral || ""),
      "asOf=" + encodeURIComponent(date())].join("&")).then(function (f) {
      box.innerHTML = fanSvg(f.points, null, f.limitNote) + '<p class="pv-sub">' + (f.bindingTenor ? "Binding tenor: " + esc(f.bindingTenor) + ", headroom "
        + money(f.minHeadroom) + " · " : "") + esc(f.limitNote) + "</p>";
    }).catch(function (e) { fail(box, e); });
  }

  // ---- A4: the counterparties as a treemap (laid out here, every figure the engine's) ------------------------------------------------
  function grandKey(list) { var g = (list || []).filter(function (e) { return e.kind === "grand"; })[0]; return g ? g.key : ""; }
  function treemap() {
    var box = $("[data-ccr-tree]");
    skel(box);
    var v = { source: "ccr", date: date(), rows: ["COUNTERPARTY"], columns: [], values: [{ measure: "epe()", as: "EPE" }, { measure: "limit_use()", as: "Use" }],
      filters: {}, expanded: [], options: opts() };
    S.call("POST", "/query", { view: v }).then(function (a) {
      var gc = grandKey(a.columns), items = [];
      (a.rows || []).forEach(function (r) {
        if (r.kind !== "node") { return; }
        var c = (a.cells || {})[r.key + "|" + gc] || {}, vals = c.values || {};
        if (isNum(vals.EPE) && vals.EPE > 0) { items.push({ name: r.value, epe: vals.EPE, use: vals.Use }); }
      });
      if (!items.length) { box.innerHTML = '<p class="pv-sub">No counterparty with exposure on this date.</p>'; return; }
      items.sort(function (x, y) { return y.epe - x.epe; });
      var top = items[0].epe;                                   // the largest: areas are shares of it (layout only)
      box.innerHTML = items.slice(0, 24).map(function (i) {
        return '<button type="button" class="ccr-cell ' + useClass(i.use) + '" style="flex-grow:' + Math.max(1, Math.round(i.epe / top * 100))
          + '" data-ccr-scope="CPTY:' + esc(i.name) + '" title="' + esc(i.name) + " · EPE " + esc(money(i.epe)) + " · use " + pct(i.use) + '"><b>' + esc(i.name)
          + "</b><span>EPE " + compact(i.epe) + " · use " + pct(i.use) + "</span></button>";
      }).join("");
    }).catch(function (e) { fail(box, e); });
  }

  // ---- view B: a netting set -------------------------------------------------------------------------------------------------------
  function viewB(nset) {
    st.nset = nset || st.nset;
    var tiles = $("[data-ccr-b-tiles]");
    $("[data-ccr-nset]").value = st.nset;
    if (!st.nset) { tiles.innerHTML = '<p class="pv-sub">Type a netting set, or click one in the board (open a counterparty\'s row).</p>'; return; }
    skel(tiles);
    var scope = "NSET:" + st.nset, p = { scope: scope, q: q(), collateral: opts().collateral || "" };
    get("/api/exposure/scope", p).then(function (x) {
      tiles.innerHTML = [
        tile(x.label || st.nset, money(x.peakPfe), "peak PFE " + pct(x.confidence) + " at " + esc(x.peakPfeTenor || "—")),
        tile("EPE · EEPE", compact(x.epe) + " · " + compact(x.eepe), "first year"),
        tile("Current exposure", money(x.ce), "after collateral · " + esc(x.collateralModel) + " · MPOR " + esc(x.mporDays) + " days"),
        tile("Run", esc(x.runId), esc(x.calibration) + " · " + x.paths + " paths")
      ].join("");
      var prof = (x.profile || []).filter(function (r) { return r.role === "PRIMARY"; });
      var at = prof.filter(function (r) { return r.tenor === x.peakPfeTenor; })[0] || prof[0];
      $("[data-ccr-b-at]").textContent = at ? "at " + at.tenor + " (the peak PFE's date)" : "";
      $("[data-ccr-b-effect]").innerHTML = at ? effect(at) : '<p class="pv-sub">No primary date.</p>';
      $("[data-ccr-b-profile]").innerHTML = '<table class="mr-mini"><thead><tr><th class="l">Tenor</th><th>EE</th><th>PFE</th><th>EE uncoll.</th><th>Collateral</th></tr></thead><tbody>'
        + prof.map(function (r) {
          var up = (r.ee || 0) > (r.eeUncoll || 0), down = (r.ee || 0) < (r.eeUncoll || 0);
          return '<tr><td class="l mono">' + esc(r.tenor) + "</td><td>" + money(r.ee) + "</td><td>" + money(r.pfe) + "</td><td>" + money(r.eeUncoll)
            + '</td><td class="' + (up ? "up" : down ? "down" : "") + '">' + (up ? "raises" : down ? "lowers" : "—") + "</td></tr>";
        }).join("") + "</tbody></table>";
    }).catch(function (e) { fail(tiles, e); });
    var tr = $("[data-ccr-b-trades]");
    skel(tr);
    get("/api/exposure/scope/contributions", { scope: scope, q: q(), by: "trade", collateral: opts().collateral || "" }).then(function (c) {
      var rows = (c.rows || []).slice(0, 25);
      tr.innerHTML = rows.length ? '<table class="mr-mini"><thead><tr><th class="l">Trade</th><th>Contribution</th><th>Share</th><th>Incremental</th></tr></thead><tbody>'
        + rows.map(function (r) {
          return '<tr><td class="l mono"><a href="/t?cmd=' + encodeURIComponent("TRD " + r.key) + '">' + esc(r.label || r.key) + '</a></td><td class="' + (r.value < 0 ? "down" : "")
            + '">' + money(r.value) + "</td><td>" + pct(r.pct) + "</td><td>" + money(r.incremental) + "</td></tr>";
        }).join("") + "</tbody></table>" : '<p class="pv-sub">' + esc(c.note || "No trades.") + "</p>";
    }).catch(function (e) { fail(tr, e); });
  }
  function effect(r) {
    var hi = Math.max(r.ee || 0, r.eeUncoll || 0) || 1;
    function b(label, v, cls) {
      return '<div class="ccr-hbar"><span class="l">' + esc(label) + '</span><span class="t"><span class="f ' + cls + '" style="width:' + (Math.max(0, v) / hi * 100).toFixed(1)
        + '%"></span></span><span class="v mono">' + compact(v) + "</span></div>";
    }
    var up = (r.ee || 0) > (r.eeUncoll || 0), down = (r.ee || 0) < (r.eeUncoll || 0);
    return b("EE uncollateralised", r.eeUncoll, "unc") + b("EE after collateral", r.ee, "col") + '<p class="pv-sub">Collateral '
      + (up ? "raises the exposure (collateral posted, at risk over the margin period of risk)" : down ? "lowers the exposure" : "leaves the exposure unchanged") + ".</p>";
  }

  // ---- view C: pre-deal, the group, EAD and FVA --------------------------------------------------------------------------------------
  function viewC(form) {
    var tiles = $("[data-ccr-c-tiles]");
    var scope = (form && form.scope.value.trim()) || (/^(CPTY|GROUP):/.test(st.scope) ? st.scope : "");
    if (form) { scope = /:/.test(scope) ? scope : "CPTY:" + scope; }
    groupTree();
    if (!scope) { tiles.innerHTML = '<p class="pv-sub">Click a counterparty in view A, or type one in the form: its pre-deal check, EAD and FVA.</p>'; return; }
    $("[data-ccr-tray]").scope.value = scope;
    var cells = { ead: '<div class="mr-tile skel"></div>', fva: '<div class="mr-tile skel"></div>', pre: "" };
    function draw() { tiles.innerHTML = cells.pre + cells.ead + cells.fva; }
    draw();
    get("/api/saccr/scope", { scope: scope }).then(function (x) {
      var t = x.totals || {};
      cells.ead = tile("EAD SA-CCR · " + scope, money(t.ead), "RC " + compact(t.rc) + " · PFE " + compact(t.pfe) + " · " + (t.nettingSets || 0) + " sets");
      draw();
    }).catch(function (e) { cells.ead = tile("EAD SA-CCR", "—", esc(e.message)); draw(); });
    get("/api/fva/scope", { scope: scope }).then(function (x) {
      var f = x.figures || {};
      cells.fva = tile("FVA · " + scope, money(f.fva), "FCA " + compact(f.fca) + " · FBA " + compact(f.fba));
      draw();
    }).catch(function (e) { cells.fva = tile("FVA", "—", esc(e.message)); draw(); });
    if (!form) { return; }
    var cand = { tradeId: "CAND-1", paths: { copyOf: form.trade.value.trim(), factor: Number(form.factor.value) || 1 } };
    if (form.nset.value.trim()) { cand.nettingSet = form.nset.value.trim(); }
    var ov = $("[data-ccr-c-overlay]");
    skel(ov);
    Promise.all([S.call("POST", "/ccr/pre-deal?asOf=" + encodeURIComponent(date()), { scope: scope, method: "paths", q: Number(q()), collateral: opts().collateral || undefined, candidates: [cand] }),
      S.call("GET", "/ccr/fan?scope=" + encodeURIComponent(scope) + "&q=" + encodeURIComponent(q()) + "&asOf=" + encodeURIComponent(date()))]).then(function (rs) {
      var w = rs[0], f = rs[1], inc = w.incremental || {}, after = (w["with"] || {});
      cells.pre = tile("Incremental peak PFE", (inc.peakPfe > 0 ? "+" : "") + money(inc.peakPfe), compact((w.base || {}).peakPfe) + " → " + compact(after.peakPfe)
        + " · " + esc((w.flags || []).join(", ")))
        + tile("Headroom after", money(w.headroomAfter), isNum(w.limit) ? pct(w.useAfter) + " of the limit " + compact(w.limit) : esc(w.limitNote), bar(w.useAfter));
      draw();
      var prof = inc.profile || [];
      var afterLine = f.points.map(function (p) { var m = prof.filter(function (x) { return x.tenor === p.tenor; })[0]; return m ? m.pfe : p.pfe; });
      ov.innerHTML = fanSvg(f.points, afterLine, f.limitNote) + '<table class="mr-mini"><thead><tr><th class="l">Tenor</th><th>PFE before</th><th>after</th><th>Incremental</th></tr></thead><tbody>'
        + prof.map(function (x) { return '<tr><td class="l mono">' + esc(x.tenor) + "</td><td>" + money(x.pfeBase) + "</td><td>" + money(x.pfe) + "</td><td>" + money(x.incrementalPfe) + "</td></tr>"; }).join("")
        + "</tbody></table>";
    }).catch(function (e) { fail(ov, e); });
  }
  function groupTree() {
    var box = $("[data-ccr-c-group]");
    skel(box);
    var v = { source: "ccr", date: date(), rows: ["GROUP", "COUNTERPARTY", "NETTING_SET"], columns: [], filters: {}, expanded: [], expandTo: { rows: 3, columns: 1 },
      values: [{ measure: "pfe()", as: "Peak PFE" }, { measure: "epe()", as: "EPE" }, { measure: "limit_use()", as: "Use" }], options: opts() };
    S.call("POST", "/query", { view: v }).then(function (a) {
      var gc = grandKey(a.columns);
      box.innerHTML = '<table class="mr-mini"><thead><tr><th class="l">Group › counterparty › netting set</th><th>Peak PFE</th><th>EPE</th><th>Use</th></tr></thead><tbody>'
        + (a.rows || []).map(function (r) {
          var c = ((a.cells || {})[r.key + "|" + gc] || {}).values || {};
          var depth = r.kind === "grand" ? 0 : (r.depth || 0);
          return '<tr class="' + (r.kind === "grand" ? "tot" : "") + '"><td class="l" style="padding-left:' + (6 + depth * 14) + 'px">' + esc(r.kind === "grand" ? "Bank (its paths summed)" : r.value)
            + "</td><td>" + money(c["Peak PFE"]) + "</td><td>" + money(c.EPE) + "</td><td>" + pct(c.Use) + bar(c.Use) + "</td></tr>";
        }).join("") + "</tbody></table>";
    }).catch(function (e) { fail(box, e); });
  }

  // ---- the views -------------------------------------------------------------------------------------------------------------------
  function showTab(t) {
    st.tab = t;
    $$(".mr-tab[data-ccr-tab]").forEach(function (b) { b.setAttribute("aria-selected", String(b.getAttribute("data-ccr-tab") === t)); });
    $$("[data-ccr-pane]").forEach(function (p) { p.hidden = p.getAttribute("data-ccr-pane") !== t; });
    var main = $(".pv-main");
    if (main) { main.hidden = t !== "A"; }
    if (!st.answer) { return; }
    if (t === "B") { viewB(); }
    if (t === "C") { viewC(null); }
  }

  // ---- wiring ----------------------------------------------------------------------------------------------------------------------
  root.addEventListener("pivot:answer", function (ev) {
    st.answer = ev.detail.answer;
    marks();
    headline();
    treemap();
    fan(st.scope && !/^NSET:/.test(st.scope) ? st.scope : "BANK");
    if (/^NSET:/.test(st.scope)) { st.nset = st.scope.slice(5).split(",")[0]; }
    showTab(st.tab);
  });
  root.addEventListener("pivot:cell", function (ev) {
    var c = (ev.detail && ev.detail.coords) || {};
    if (c.NETTING_SET) { st.nset = c.NETTING_SET; fan("NSET:" + c.NETTING_SET); }
    else if (c.COUNTERPARTY) { fan("CPTY:" + c.COUNTERPARTY); }
    else if (c.GROUP) { fan("GROUP:" + c.GROUP); }
    else { fan("BANK"); }
  });
  root.addEventListener("click", function (ev) {
    var t = ev.target.closest("button[data-v], button[data-ccr-tab], [data-ccr-scope], [data-ccr-filter], [data-ccr-retry]");
    if (!t || !root.contains(t)) { return; }
    if (t.hasAttribute("data-ccr-retry")) { S.run(); return; }
    if (t.hasAttribute("data-ccr-tab")) { showTab(t.getAttribute("data-ccr-tab")); return; }
    if (t.hasAttribute("data-ccr-scope")) { fan(t.getAttribute("data-ccr-scope")); return; }
    if (t.hasAttribute("data-ccr-filter")) {
      var list = ((st.headline || {})[t.getAttribute("data-ccr-filter")] || []).map(function (r) { return r.counterparty; });
      if (list.length) { S.run(function (v) { v.filters = Object.assign({}, v.filters || {}, { COUNTERPARTY: { "in": list } }); }); }
      return;
    }
    var seg = t.closest("[data-ccr-seg]");
    if (seg && !t.disabled) { setLens(seg.getAttribute("data-ccr-seg"), t.getAttribute("data-v")); }
  });
  $("[data-ccr-nset-form]").addEventListener("submit", function (ev) { ev.preventDefault(); viewB($("[data-ccr-nset]").value.trim().replace(/^NSET:/i, "")); });
  $("[data-ccr-tray]").addEventListener("submit", function (ev) { ev.preventDefault(); viewC(ev.target); });
  document.addEventListener("keydown", function (ev) {
    if (ev.target.closest("input, textarea, select") || ev.ctrlKey || ev.metaKey || ev.altKey || !st.answer) { return; }
    var k = ev.key.toLowerCase();
    if (k === "q") { setLens("q", q() === "0.99" ? "0.95" : "0.99"); }
    else if (k === "k") { var m = ["lagged", "current", "none"], i = m.indexOf(opts().collateral || "lagged"); setLens("collateral", m[(i + 1) % 3]); }
    else if (k === "b") { showTab("B"); }
    else if (k === "w") { showTab("C"); $("[data-ccr-tray] input[name=trade]").focus(); ev.preventDefault(); }
  });
})();
