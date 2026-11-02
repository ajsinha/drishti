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
/* The cube core's drawing (docs/guides/MR_CUBE_GUIDE.md section 6), shared by the MR cube (mcube.js) and the counterparty cube to come:
   the pivot grid with in-cell sparklines and limit bars, the totals and the Σ cells − total line, the tail histogram with its V and E
   markers, the attribution waterfall, the heat map and the ladder bars, all inline SVG in the theme's colours (nothing from a CDN). Every
   number is the server's answer: this file only draws it. window.DrishtiCube. */
(function () {
  "use strict";

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function isNum(v) { return typeof v === "number" && isFinite(v); }
  /** Money as the grid prints it: whole units, thousands separators, a real minus sign. */
  function money(v, d) {
    if (!isNum(v)) { return "—"; }
    return (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-US", { minimumFractionDigits: d || 0, maximumFractionDigits: d || 0 });
  }
  /** Short money for labels: 112.0m, 4.5k. */
  function compact(v) {
    if (!isNum(v)) { return "—"; }
    var a = Math.abs(v), s = v < 0 ? "−" : "";
    if (a >= 1e9) { return s + (a / 1e9).toFixed(1) + "bn"; }
    if (a >= 1e6) { return s + (a / 1e6).toFixed(1) + "m"; }
    if (a >= 1e3) { return s + (a / 1e3).toFixed(1) + "k"; }
    return s + a.toFixed(0);
  }
  function pct(v, d) { return isNum(v) ? (v * 100).toFixed(d == null ? 1 : d) + "%" : "—"; }

  function json(url, opts) {
    var o = Object.assign({ headers: { Accept: "application/json" }, credentials: "same-origin" }, opts || {});
    if (o.body && typeof o.body !== "string") {
      o.body = JSON.stringify(o.body);
      o.headers = Object.assign({ "Content-Type": "application/json" }, o.headers);
    }
    return fetch(url, o).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) {
          var e = new Error((b.code ? b.code + " " : "") + (b.detail || b.message || r.status));
          e.code = b.code; e.status = r.status; e.retryAfter = r.headers.get("Retry-After");
          throw e;
        }
        return b;
      });
    });
  }

  /** A sparkline: the values left to right (null: a gap), the last point marked; 70 × 16 by default. */
  function spark(values, w, h, cls) {
    w = w || 70; h = h || 16;
    var xs = (values || []).map(function (v, i) { return isNum(v) ? [i, v] : null; }).filter(Boolean);
    if (xs.length < 2) { return '<svg class="mc-spark ' + (cls || "") + '" width="' + w + '" height="' + h + '" aria-hidden="true"></svg>'; }
    var lo = Math.min.apply(null, xs.map(function (p) { return p[1]; })), hi = Math.max.apply(null, xs.map(function (p) { return p[1]; }));
    var n = values.length - 1 || 1, span = hi - lo || 1;
    var pts = xs.map(function (p) { return (p[0] / n * (w - 2) + 1).toFixed(1) + "," + (h - 2 - (p[1] - lo) / span * (h - 4)).toFixed(1); });
    var last = pts[pts.length - 1].split(",");
    return '<svg class="mc-spark ' + (cls || "") + '" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + " " + h + '" aria-hidden="true">'
      + '<polyline points="' + pts.join(" ") + '" fill="none" stroke="currentColor" stroke-width="1.2"/>'
      + '<circle cx="' + last[0] + '" cy="' + last[1] + '" r="1.8" fill="currentColor"/></svg>';
  }

  /** A limit bar: use of the limit, green under the warning level, amber from it, red over 100%, a tick at the warning level and at 100%. */
  function limitBar(use, warn) {
    if (!isNum(use)) { return ""; }
    warn = warn || 0.8;
    var cls = use > 1 ? "bad" : use >= warn ? "warn" : "ok";
    var w = Math.min(100, Math.max(0, use * 100 / 1.2));
    return '<span class="mc-lbar ' + cls + '" title="' + pct(use) + ' of the limit"><i style="width:' + w.toFixed(1) + '%"></i>'
      + '<b class="tick" style="left:' + (warn * 100 / 1.2).toFixed(1) + '%"></b><b class="tick full" style="left:' + (100 / 1.2).toFixed(1) + '%"></b></span>';
  }

  /**
   * The tail histogram (A6, B3): the scenario P&Ls in bins, the ES tail bins red and marked E, the VaR bin amber with a dashed line and V,
   * a zero line, the axis from the worst to the best. {@code d} is the server's cell detail (edges, counts, var, es, esCount, k).
   */
  function histogram(d, w, h) {
    w = w || 340; h = h || 150;
    if (!d || !d.counts || !d.counts.length) { return '<p class="muted">No scenario P&amp;L for this cell.</p>'; }
    var n = d.counts.length, max = Math.max.apply(null, d.counts.concat([1])), lo = d.edges[0], hi = d.edges[n], span = hi - lo || 1;
    var bw = (w - 20) / n, top = 26, base = h - 20, out = [];
    function x(v) { return 10 + (v - lo) / span * (w - 20); }
    var varAt = -d.var, esEdge = d.tail && d.tail.length ? d.tail[d.tail.length - 1].pnl : varAt;
    for (var i = 0; i < n; i++) {
      var bh = d.counts[i] / max * (base - top), x0 = 10 + i * bw;
      var mid = (d.edges[i] + d.edges[i + 1]) / 2, cls = d.edges[i + 1] <= esEdge + 1e-9 ? "es" : (varAt >= d.edges[i] && varAt <= d.edges[i + 1] ? "v" : "");
      out.push('<rect class="bin ' + cls + '" x="' + (x0 + 0.5).toFixed(1) + '" y="' + (base - bh).toFixed(1) + '" width="' + Math.max(1, bw - 1).toFixed(1)
        + '" height="' + bh.toFixed(1) + '"><title>' + d.counts[i] + " scenarios, " + compact(d.edges[i]) + " to " + compact(d.edges[i + 1]) + "</title></rect>");
      void mid;
    }
    var vx = x(varAt);
    out.push('<line class="vline" x1="' + vx.toFixed(1) + '" x2="' + vx.toFixed(1) + '" y1="' + (top - 14) + '" y2="' + base + '"/>');
    out.push('<text class="vlab" x="' + (vx + 3).toFixed(1) + '" y="' + (top - 4) + '">V −VaR ' + compact(d.var) + " (#" + d.k + " of " + d.scenarios + ")</text>");
    out.push('<text class="elab" x="10" y="' + (top + 8) + '">E ES tail, ' + d.esCount + " worst</text>");
    if (lo < 0 && hi > 0) {
      var zx = x(0);
      out.push('<line class="zero" x1="' + zx.toFixed(1) + '" x2="' + zx.toFixed(1) + '" y1="' + top + '" y2="' + base + '"/>');
    }
    out.push('<text class="ax" x="10" y="' + (h - 4) + '">' + compact(lo) + '</text><text class="ax" x="' + (w - 10) + '" y="' + (h - 4) + '" text-anchor="end">' + compact(hi) + "</text>");
    return '<svg class="mc-histo" viewBox="0 0 ' + w + " " + h + '" width="100%" role="img" aria-label="Scenario P&amp;L histogram: VaR '
      + esc(money(d.var)) + ", ES " + esc(money(d.es)) + '">' + out.join("") + "</svg>";
  }

  /** The attribution waterfall (B2): one bar per part sorted by size, red a positive component, green a hedge, the total amber, each labelled. */
  function waterfall(parts, total, w, h) {
    w = w || 640; h = h || 260;
    var ps = parts.filter(function (p) { return isNum(p.value); }).sort(function (a, b) { return b.value - a.value; });
    if (!ps.length) { return '<p class="muted">No components.</p>'; }
    var run = 0, hi = 0, lo = 0;
    ps.forEach(function (p) { run += p.value; hi = Math.max(hi, run); lo = Math.min(lo, run); });
    hi = Math.max(hi, total || 0); lo = Math.min(lo, 0);
    var top = 24, base = h - 28, span = hi - lo || 1, n = ps.length + 1, bw = (w - 20) / n, out = [], at = 0;
    function y(v) { return top + (hi - v) / span * (base - top); }
    ps.forEach(function (p, i) {
      var a = at, b = at + p.value, x0 = 10 + i * bw;
      out.push('<rect class="' + (p.value < 0 ? "hedge" : "add") + '" x="' + (x0 + 3).toFixed(1) + '" y="' + y(Math.max(a, b)).toFixed(1) + '" width="' + (bw - 6).toFixed(1)
        + '" height="' + Math.max(1, Math.abs(y(a) - y(b))).toFixed(1) + '" data-key="' + esc(p.key) + '"><title>' + esc(p.label) + ": " + esc(money(p.value)) + "</title></rect>");
      out.push('<text class="wl" x="' + (x0 + bw / 2).toFixed(1) + '" y="' + (y(Math.max(a, b)) - 3).toFixed(1) + '" text-anchor="middle">' + compact(p.value) + "</text>");
      out.push('<text class="wx" x="' + (x0 + bw / 2).toFixed(1) + '" y="' + (h - 10) + '" text-anchor="middle">' + esc(String(p.label).slice(0, 10)) + "</text>");
      at = b;
    });
    var tx = 10 + ps.length * bw;
    out.push('<rect class="tot" x="' + (tx + 3).toFixed(1) + '" y="' + y(Math.max(0, total)).toFixed(1) + '" width="' + (bw - 6).toFixed(1) + '" height="'
      + Math.max(1, Math.abs(y(0) - y(total))).toFixed(1) + '"><title>Total ' + esc(money(total)) + "</title></rect>");
    out.push('<text class="wl tot" x="' + (tx + bw / 2).toFixed(1) + '" y="' + (y(Math.max(0, total)) - 3).toFixed(1) + '" text-anchor="middle">' + compact(total) + "</text>");
    out.push('<text class="wx" x="' + (tx + bw / 2).toFixed(1) + '" y="' + (h - 10) + '" text-anchor="middle">Total</text>');
    return '<svg class="mc-water" viewBox="0 0 ' + w + " " + h + '" width="100%" role="img" aria-label="VaR attribution waterfall">' + out.join("") + "</svg>";
  }

  /** A centre-zero bar of a ladder bucket (C2): long right, short left, ticks at both limits; coloured by its use of the limit. */
  function ladderBar(net, limit, status, scale) {
    var w = 100, half = 50, s = scale || Math.abs(net) || 1;
    var len = Math.min(half, Math.abs(net) / s * half);
    var x = net >= 0 ? half : half - len;
    var cls = status === "breach" ? "bad" : status === "near" ? "warn" : "ok";
    var ticks = isNum(limit) ? '<b class="tick" style="left:' + Math.max(0, half - limit / s * half).toFixed(1) + '%"></b><b class="tick" style="left:'
      + Math.min(100, half + limit / s * half).toFixed(1) + '%"></b>' : "";
    return '<span class="mc-cbar ' + cls + '"><i style="left:' + x.toFixed(1) + "%;width:" + len.toFixed(1) + '%"></i><b class="zero"></b>' + ticks + "</span>";
    void w;
  }

  /** A heat-map cell's background: green for long, red for short, the intensity by size up to {@code full}. */
  function heat(v, full) {
    if (!isNum(v) || !full) { return ""; }
    var a = Math.min(1, Math.abs(v) / full) * 0.55 + 0.05;
    return v >= 0 ? "background: color-mix(in srgb, var(--d-ok, #3fb37f) " + (a * 100).toFixed(0) + "%, transparent)"
      : "background: color-mix(in srgb, var(--d-bad, #e05d5d) " + (a * 100).toFixed(0) + "%, transparent)";
  }

  window.DrishtiCube = { esc: esc, isNum: isNum, money: money, compact: compact, pct: pct, json: json, spark: spark, limitBar: limitBar,
    histogram: histogram, waterfall: waterfall, ladderBar: ladderBar, heat: heat };
})();
