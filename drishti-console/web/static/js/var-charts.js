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
/* The VaR explorer's charts (VAR_GUIDE.md section 3): plain SVG, no library, a few hundred elements at most, coloured by classes
   (var.css reads the theme's tokens), with a hover tip. The server sends summaries (bins, the worst 25, one series), never a vector
   per part, so every chart draws in a few milliseconds.

   VarCharts.histogram(host, {edges, counts, var, es, onPick})   bins with the VaR and ES lines; bins beyond VaR in red
   VarCharts.bars(host, rows, {height, labelEvery, line, onPick}) bars over ordered keys (the worst 25, P&L by scenario date)
   VarCharts.lines(host, keys, series, bars, {height})            -VaR and -ES lines over hypothetical P&L bars, exceptions marked */
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";

  function el(tag, attrs, parent) {
    var e = document.createElementNS(NS, tag);
    for (var k in attrs) { if (attrs[k] !== undefined && attrs[k] !== null) e.setAttribute(k, attrs[k]); }
    if (parent) parent.appendChild(e);
    return e;
  }

  /** 1.23m, -412k, 950: the compact money the mock-up shows. */
  function money(v) {
    if (v === null || v === undefined || isNaN(v)) return "—";
    var a = Math.abs(v), s = v < 0 ? "-" : "";
    if (a >= 1e9) return s + (a / 1e9).toFixed(2) + "bn";
    if (a >= 1e6) return s + (a / 1e6).toFixed(2) + "m";
    if (a >= 1e3) return s + (a / 1e3).toFixed(0) + "k";
    return s + a.toFixed(0);
  }

  function signed(v) { return (v > 0 ? "+" : "") + money(v); }

  var tip = null;
  function showTip(text, ev) {
    if (!tip) { tip = document.createElement("div"); tip.className = "vx-tip"; document.body.appendChild(tip); }
    tip.textContent = "";
    String(text).split("\n").forEach(function (line, i) {
      if (i) tip.appendChild(document.createElement("br"));
      tip.appendChild(document.createTextNode(line));
    });
    tip.hidden = false;
    var x = Math.min(ev.clientX + 12, window.innerWidth - tip.offsetWidth - 8);
    tip.style.left = x + "px";
    tip.style.top = (ev.clientY + 14) + "px";
  }
  function hideTip() { if (tip) tip.hidden = true; }
  function hover(node, text) {
    node.addEventListener("mousemove", function (ev) { showTip(typeof text === "function" ? text() : text, ev); });
    node.addEventListener("mouseleave", hideTip);
  }

  function svg(host, h) {
    host.textContent = "";
    var w = Math.max(280, host.clientWidth || 600);
    var s = el("svg", { width: "100%", height: h, viewBox: "0 0 " + w + " " + h, role: "img", preserveAspectRatio: "none", "class": "vx-svg" }, host);
    return { s: s, w: w, h: h };
  }

  function niceTicks(lo, hi, n) {
    var step0 = (hi - lo) / (n || 4) || 1, mag = Math.pow(10, Math.floor(Math.log10(Math.abs(step0)))), st = step0;
    [1, 2, 2.5, 5, 10].some(function (m) { if (m * mag >= step0) { st = m * mag; return true; } return false; });
    var out = [];
    for (var v = Math.ceil(lo / st) * st; v <= hi + 1e-9 && out.length < 12; v += st) out.push(v);
    return out;
  }

  function yAxis(g, x0, x1, Y, vals, fmt) {
    vals.forEach(function (v) {
      var yy = Y(v);
      el("line", { x1: x0, x2: x1, y1: yy, y2: yy, "class": "vx-grid" }, g);
      var t = el("text", { x: x0 - 4, y: yy + 3, "text-anchor": "end", "class": "vx-axis" }, g);
      t.textContent = fmt(v);
    });
  }

  /** Bins of the scenario P&Ls; bins wholly beyond -VaR in red; the VaR and ES lines labelled. */
  function histogram(host, d) {
    var c = svg(host, d.height || 210), s = c.s, w = c.w, h = c.h, m = { l: 46, r: 10, t: 16, b: 22 };
    var edges = d.edges, counts = d.counts, lo = edges[0], hi = edges[edges.length - 1];
    var cmax = Math.max.apply(null, counts.concat([1]));
    var X = function (v) { return m.l + (v - lo) / ((hi - lo) || 1) * (w - m.l - m.r); };
    var Y = function (n) { return h - m.b - n / cmax * (h - m.t - m.b); };
    yAxis(s, m.l, w - m.r, Y, niceTicks(0, cmax, 3), function (v) { return v; });
    niceTicks(lo, hi, 5).forEach(function (v) {
      var t = el("text", { x: X(v), y: h - 6, "text-anchor": "middle", "class": "vx-axis" }, s);
      t.textContent = money(v);
    });
    counts.forEach(function (n, i) {
      if (!n) return;
      var a = edges[i], b = edges[i + 1], x = X(a), bw = Math.max(1, X(b) - X(a) - 1.5);
      var r = el("rect", { x: x + 0.75, y: Y(n), width: bw, height: h - m.b - Y(n), rx: 1.5, "class": b <= -d.var ? "vx-bar tail" : "vx-bar" }, s);
      hover(r, money(a) + " to " + money(b) + "\n" + n + " scenario day" + (n === 1 ? "" : "s"));
      if (d.onPick) r.addEventListener("click", function () { d.onPick(a, b); });
    });
    [{ v: d.var, cls: "mk-var", label: "VaR " + money(d.var), dy: 0 }, { v: d.es, cls: "mk-es", label: "ES " + money(d.es), dy: 12 }].forEach(function (k) {
      if (k.v === null || k.v === undefined) return;
      var x = X(-k.v);
      el("line", { x1: x, x2: x, y1: m.t - 4, y2: h - m.b, "class": "vx-mark " + k.cls }, s);
      var t = el("text", { x: x + 4, y: m.t + 6 + k.dy, "class": "vx-mark-label " + k.cls }, s);
      t.textContent = k.label;
    });
  }

  /** Bars over ordered keys: rows [{k, v, cls, tip}]; a dashed line (the -VaR) when given. */
  function bars(host, rows, o) {
    o = o || {};
    var c = svg(host, o.height || 170), s = c.s, w = c.w, h = c.h, m = { l: 46, r: 8, t: 10, b: 20 };
    var vals = rows.map(function (r) { return r.v; }).concat([0, o.line ? o.line.v : 0]);
    var lo = Math.min.apply(null, vals), hi = Math.max.apply(null, vals);
    var Y = function (v) { return m.t + (hi - v) / ((hi - lo) || 1) * (h - m.t - m.b); }, bw = (w - m.l - m.r) / Math.max(1, rows.length);
    yAxis(s, m.l, w - m.r, Y, niceTicks(lo, hi, 3), money);
    el("line", { x1: m.l, x2: w - m.r, y1: Y(0), y2: Y(0), "class": "vx-zero" }, s);
    var gap = bw > 6 ? 2 : (bw > 2.5 ? 0.6 : 0);
    rows.forEach(function (d, i) {
      var a = Math.min(0, d.v), b = Math.max(0, d.v);
      el("rect", { x: m.l + i * bw + gap / 2, y: Y(b), width: Math.max(0.8, bw - gap), height: Math.max(1, Y(a) - Y(b)), rx: bw > 6 ? 2 : 0,
        "class": "vx-bar " + (d.cls || (d.v < 0 ? "neg" : "")) }, s);
      var hit = el("rect", { x: m.l + i * bw, y: m.t, width: Math.max(bw, 3), height: h - m.t - m.b, "class": "vx-hit", "data-key": d.k }, s);
      hover(hit, d.tip || (d.k + "\n" + signed(d.v)));
      if (o.onPick) hit.addEventListener("click", function () { o.onPick(d, i); });
      if (o.labelEvery && i % o.labelEvery === 0) {
        var t = el("text", { x: m.l + i * bw + bw / 2, y: h - 5, "text-anchor": "middle", "class": "vx-axis" }, s);
        t.textContent = d.label || d.k;
      }
    });
    if (o.line) {
      var y = Y(o.line.v);
      el("line", { x1: m.l, x2: w - m.r, y1: y, y2: y, "class": "vx-mark mk-var dashed" }, s);
      var t = el("text", { x: w - m.r - 2, y: y - 4, "text-anchor": "end", "class": "vx-mark-label mk-var" }, s);
      t.textContent = o.line.label;
    }
  }

  /** -VaR and -ES lines over the day's P&L bars (one axis: all currency), exceptions as red dots; a crosshair tip. */
  function lines(host, keys, series, pnl, o) {
    o = o || {};
    var c = svg(host, o.height || 250), s = c.s, w = c.w, h = c.h, m = { l: 50, r: 10, t: 12, b: 22 };
    if (!keys.length) return;
    var all = [0];
    series.forEach(function (x) { all = all.concat(x.data); });
    pnl.forEach(function (b) { all.push(b.v); });
    var lo = Math.min.apply(null, all), hi = Math.max.apply(null, all);
    var X = function (i) { return m.l + i / Math.max(1, keys.length - 1) * (w - m.l - m.r); };
    var Y = function (v) { return m.t + (hi - v) / ((hi - lo) || 1) * (h - m.t - m.b); };
    yAxis(s, m.l, w - m.r, Y, niceTicks(lo, hi, 4), money);
    el("line", { x1: m.l, x2: w - m.r, y1: Y(0), y2: Y(0), "class": "vx-zero" }, s);
    var bw = (w - m.l - m.r) / keys.length;
    pnl.forEach(function (b, i) {
      el("rect", { x: X(i) - bw / 2 + 0.3, y: Math.min(Y(0), Y(b.v)), width: Math.max(0.8, bw - 0.6), height: Math.max(0.5, Math.abs(Y(b.v) - Y(0))),
        "class": "vx-bar " + (b.exc ? "exc" : "pnl") }, s);
      if (b.exc) el("circle", { cx: X(i), cy: Y(b.v) + 5, r: 4, "class": "vx-exc" }, s);
    });
    series.forEach(function (x) {
      var d = x.data.map(function (v, i) { return (i ? "L" : "M") + X(i).toFixed(1) + " " + Y(v).toFixed(1); }).join("");
      el("path", { d: d, "class": "vx-line " + x.cls }, s);
    });
    [0, Math.floor(keys.length / 2), keys.length - 1].forEach(function (i) {
      var t = el("text", { x: X(i), y: h - 6, "text-anchor": i === 0 ? "start" : i === keys.length - 1 ? "end" : "middle", "class": "vx-axis" }, s);
      t.textContent = keys[i];
    });
    var cross = el("line", { y1: m.t, y2: h - m.b, "class": "vx-cross", opacity: 0 }, s);
    var hit = el("rect", { x: m.l, y: m.t, width: w - m.l - m.r, height: h - m.t - m.b, "class": "vx-hit" }, s);
    hit.addEventListener("mousemove", function (ev) {
      var bb = s.getBoundingClientRect(), px = (ev.clientX - bb.left) * (w / bb.width);
      var i = Math.max(0, Math.min(keys.length - 1, Math.round((px - m.l) / (w - m.l - m.r) * (keys.length - 1))));
      cross.setAttribute("x1", X(i)); cross.setAttribute("x2", X(i)); cross.setAttribute("opacity", 0.6);
      showTip(keys[i] + "\n" + series.map(function (x) { return x.name + " " + money(x.data[i]); }).join("\n")
        + "\nP&L " + signed(pnl[i] ? pnl[i].v : 0) + (pnl[i] && pnl[i].exc ? " · exception" : ""), ev);
    });
    hit.addEventListener("mouseleave", function () { cross.setAttribute("opacity", 0); hideTip(); });
  }

  window.VarCharts = { histogram: histogram, bars: bars, lines: lines, money: money, signed: signed, hideTip: hideTip };
})();
