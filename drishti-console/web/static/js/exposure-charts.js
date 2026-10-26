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
/* The exposure explorer's charts (EXPOSURE_GUIDE.md section 3): plain SVG, no library, coloured by classes (exposure.css reads the
   theme's tokens), with a crosshair tip. Time runs on a square-root scale, so the first weeks and the tenth year both read.

   ExposureCharts.profile(host, {tenors, t, series: [{name, cls, data, area, peak}], bands: [{cls, lo, hi}], height})
       lines over the tenors; bands (the path fan) under them; the peak of a series marked and labelled
   ExposureCharts.spark(values, t)    a small EE line for a table row (an SVG element)
   ExposureCharts.money(v)            1.23m, 412k, 2.31bn */
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";

  function el(tag, attrs, parent) {
    var e = document.createElementNS(NS, tag);
    for (var k in attrs) { if (attrs[k] !== undefined && attrs[k] !== null) e.setAttribute(k, attrs[k]); }
    if (parent) parent.appendChild(e);
    return e;
  }

  function money(v) {
    if (v === null || v === undefined || isNaN(v)) return "—";
    var a = Math.abs(v), s = v < 0 ? "-" : "";
    if (a >= 1e9) return s + (a / 1e9).toFixed(2) + "bn";
    if (a >= 1e6) return s + (a / 1e6).toFixed(1) + "m";
    if (a >= 1e3) return s + (a / 1e3).toFixed(0) + "k";
    return s + a.toFixed(0);
  }

  var tip = null;
  function showTip(text, ev) {
    if (!tip) { tip = document.createElement("div"); tip.className = "xx-tip"; document.body.appendChild(tip); }
    tip.textContent = text;
    tip.hidden = false;
    tip.style.left = Math.min(ev.clientX + 12, window.innerWidth - tip.offsetWidth - 8) + "px";
    tip.style.top = (ev.clientY + 14) + "px";
  }
  function hideTip() { if (tip) tip.hidden = true; }

  function ticks(hi) {
    if (!(hi > 0)) return [0];
    var step0 = hi / 4, mag = Math.pow(10, Math.floor(Math.log10(step0))), st = step0;
    [1, 2, 2.5, 5, 10].some(function (m) { if (m * mag >= step0) { st = m * mag; return true; } return false; });
    var out = [];
    for (var v = 0; out.length < 9; v += st) { out.push(v); if (v >= hi - 1e-9) break; }      // the last tick covers the largest value
    return out;
  }

  /** The profile: bands first (the fan), then the lines; the peak of the series marked {peak: true}. */
  function profile(host, d) {
    host.textContent = "";
    var h = d.height || 240, w = Math.max(320, host.clientWidth || 680), m = { l: 52, r: 14, t: 16, b: 24 };
    var s = el("svg", { width: "100%", height: h, viewBox: "0 0 " + w + " " + h, role: "img", "aria-label": "Exposure profile by tenor",
      preserveAspectRatio: "none", "class": "xx-svg" }, host);
    var t = d.t, n = t.length;
    if (!n) return;
    var tmax = Math.max.apply(null, t) || 1;
    var X = function (i) { return m.l + (w - m.l - m.r) * Math.sqrt(Math.max(0, t[i]) / tmax); };
    var all = [1];
    (d.series || []).forEach(function (x) { x.data.forEach(function (v) { if (v !== null && !isNaN(v)) all.push(v); }); });
    (d.bands || []).forEach(function (b) { b.hi.forEach(function (v) { all.push(v); }); });
    var tk = ticks(Math.max.apply(null, all) * 1.02), hi = tk[tk.length - 1] || 1;
    var Y = function (v) { return m.t + (h - m.t - m.b) * (1 - Math.max(0, v) / hi); };
    tk.forEach(function (v) {
      el("line", { x1: m.l, x2: w - m.r, y1: Y(v), y2: Y(v), "class": "xx-grid" }, s);
      el("text", { x: m.l - 5, y: Y(v) + 3, "text-anchor": "end" }, s).textContent = money(v);
    });
    var lastX = -100;
    d.tenors.forEach(function (lab, i) {
      if (X(i) - lastX < 26) return;
      lastX = X(i);
      el("text", { x: X(i), y: h - 7, "text-anchor": "middle" }, s).textContent = lab;
    });
    (d.bands || []).forEach(function (b) {
      var pts = b.hi.map(function (v, i) { return X(i).toFixed(1) + "," + Y(v).toFixed(1); })
        .concat(b.lo.map(function (v, i) { return X(i).toFixed(1) + "," + Y(v).toFixed(1); }).reverse());
      el("polygon", { points: pts.join(" "), "class": b.cls }, s);
    });
    (d.series || []).forEach(function (x) {
      var pts = x.data.map(function (v, i) { return X(i).toFixed(1) + "," + Y(v || 0).toFixed(1); }).join(" ");
      if (x.area) el("polygon", { points: X(0).toFixed(1) + "," + Y(0) + " " + pts + " " + X(n - 1).toFixed(1) + "," + Y(0), "class": "xx-area" }, s);
      el("polyline", { points: pts, "class": "xx-line " + x.cls, "data-series": x.name }, s);
      if (x.peak) {
        var at = 0;
        x.data.forEach(function (v, i) { if (v > x.data[at]) at = i; });
        var px = X(at), py = Y(x.data[at]), end = px > w - 170;
        el("circle", { cx: px, cy: py, r: 4, "class": "xx-peak" }, s);
        el("text", { x: end ? px - 7 : px + 7, y: py - 7, "text-anchor": end ? "end" : "start", "class": "xx-peak-label", "data-peak": d.tenors[at] }, s)
          .textContent = "peak " + money(x.data[at]) + " · " + d.tenors[at];
      }
    });
    var cross = el("line", { y1: m.t, y2: h - m.b, "class": "xx-cross", opacity: 0 }, s);
    var hit = el("rect", { x: m.l, y: m.t, width: w - m.l - m.r, height: h - m.t - m.b, "class": "xx-hit" }, s);
    hit.addEventListener("mousemove", function (ev) {
      var bb = s.getBoundingClientRect(), px = (ev.clientX - bb.left) * (w / bb.width), best = 0;
      for (var i = 1; i < n; i++) { if (Math.abs(X(i) - px) < Math.abs(X(best) - px)) best = i; }
      cross.setAttribute("x1", X(best)); cross.setAttribute("x2", X(best)); cross.setAttribute("opacity", 0.6);
      showTip(d.tenors[best] + (d.dates ? " · " + d.dates[best] : "") + "\n" + (d.series || []).map(function (x) { return x.name + " " + money(x.data[best]); }).join("\n")
        + (d.bands && d.bands.length ? "\npaths 5–95% " + money(d.bands[0].lo[best]) + " .. " + money(d.bands[0].hi[best]) : ""), ev);
    });
    hit.addEventListener("mouseleave", function () { cross.setAttribute("opacity", 0); hideTip(); });
  }

  /** A small EE line for a table row (an SVG element, 110 by 24). */
  function spark(values, t) {
    var W = 110, H = 24, s = el("svg", { width: W, height: H, viewBox: "0 0 " + W + " " + H, "class": "xx-spark", "aria-hidden": "true" });
    if (!values || !values.length) return s;
    var max = Math.max.apply(null, values.concat([1])), tmax = t && t.length ? Math.max.apply(null, t) || 1 : values.length - 1 || 1;
    var pts = values.map(function (v, i) {
      var x = t && t.length ? Math.sqrt(t[i] / tmax) : i / tmax;
      return (1 + (W - 2) * x).toFixed(1) + "," + (H - 2 - (H - 4) * Math.max(0, v) / max).toFixed(1);
    }).join(" ");
    el("polyline", { points: pts }, s);
    return s;
  }

  window.ExposureCharts = { profile: profile, spark: spark, money: money, hideTip: hideTip };
})();
