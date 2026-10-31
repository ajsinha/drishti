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
/* Drishti Market's Home (home/market_landing.html; EDITIONS.md › The design as built, section 13): paints the landing's panels from the console's answers, which
   core/market_landing.py works out from the market-data pack's own data: /api/home/market/desk (the tiles and the USD curves),
   /api/home/market/moves (the biggest moves, in sigma of each item's own history, or the largest moves, or none) and
   /api/home/market/quality (the checks); the Explorer's counts are the server's search through /api/home/search. Each panel is painted
   when its answer arrives; one refused or empty says so in place and the others carry on. A change is shown only when the answer has one
   (both business days hold real data). Nothing is invented. The chart is SVG built through the DOM (the page's policy allows no inline
   style). */
(function () {
  "use strict";
  var root = document.querySelector("[data-market-landing]");
  if (!root) return;
  var landing = root.getAttribute("data-market-landing");
  var SVG = "http://www.w3.org/2000/svg";
  var $ = function (sel) { return root.querySelector(sel); };
  var $$ = function (sel) { return Array.prototype.slice.call(root.querySelectorAll(sel)); };

  function get(path) {
    return fetch(path, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error((b && b.detail) || ("HTTP " + r.status)); e.code = b && b.code; throw e; }
        return b;
      });
    });
  }
  function num(v) { return v === null || v === undefined || v === "" || isNaN(v) ? null : Number(v); }
  function fixed(v, d) { v = num(v); return v === null ? "—" : v.toFixed(d); }
  function signed(v, d, unit) {
    v = num(v);
    if (v === null) return "";
    var t = Math.abs(v).toFixed(d);
    return (Number(t) === 0 ? "±" : v > 0 ? "+" : "−") + t + (unit || "");
  }
  function text(sel, v) { $$(sel).forEach(function (e) { e.textContent = v; }); }
  function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt !== undefined) e.textContent = txt; return e; }
  function go(cmd) { return "/go?q=" + encodeURIComponent(cmd + " <GO>"); }
  function link(href, label, cls) { var a = el("a", cls || "", label); a.href = href; return a; }
  function tone(e, v) { v = num(v); if (v !== null) e.classList.add(Math.abs(v) < 1e-9 ? "flat" : v > 0 ? "up" : "down"); return e; }
  function said(target, err, cols) {
    if (!target) return;
    target.textContent = "";
    var msg = typeof err === "string" ? err : "Not available: " + (err && err.message || err);
    if (target.tagName === "TBODY") { var tr = el("tr"), td = el("td", "ml-muted", msg); td.colSpan = cols || 4; tr.appendChild(td); target.appendChild(tr); }
    else target.appendChild(el(target.tagName === "UL" ? "li" : "p", "ml-muted", msg));
  }
  function cell(tr, content, cls) { var td = el("td", cls || ""); if (content && content.nodeType) td.appendChild(content); else td.textContent = content; tr.appendChild(td); }
  function count(v) { v = num(v); return v === null ? "—" : Math.round(v).toLocaleString("en-US"); }
  function short(d) { return d ? String(d).slice(5) : ""; }

  // ---- A · Market desk ------------------------------------------------------------------------------------------------------------
  // each tile: the value's decimals and unit, the change's unit and decimals (the 10Y rate in percent, its change in bp; vol points)
  var FORMAT = { ois10y: [2, "%", "bp", 1], slope: [1, "bp", "bp", 1], fxvol: [2, "%", "", 2], cds: [0, "bp", "bp", 1] };

  function tile(t) {
    var box = $("[data-ml-tile='" + t.id + "']");
    if (!box) return;
    var v = box.querySelector("[data-ml-v]"), s = box.querySelector("[data-ml-s]"), f = FORMAT[t.id] || [2, "", "", 2];
    v.textContent = t.value === null || t.value === undefined ? "—" : fixed(t.value, f[0]) + f[1];
    if (t.change && t.value !== null) {
      var c = el("small", "", signed(t.change.delta, f[3], f[2]));
      c.title = "against " + t.change.since;
      v.appendChild(document.createTextNode(" "));
      v.appendChild(tone(c, t.change.delta));
    }
    s.textContent = (t.sub || "") + (t.value !== null && !t.change ? " · no change: no history for the day before" : "");
  }

  function chart(target, curve, note, how) {
    var ois = curve.ois || [], proj = curve.projection || [], ago = curve.weekAgo;
    target.textContent = "";
    if (!ois.length && !proj.length) { said(target, "Not available: no USD curve points served"); return; }
    var all = ois.concat(proj).concat(ago ? ago.points : []);
    var rates = all.map(function (p) { return p.rate; });
    var lo = Math.min.apply(null, rates), hi = Math.max.apply(null, rates);
    var pad = Math.max((hi - lo) * 0.12, 0.05); lo -= pad; hi += pad;
    var W = 400, H = 170, L = 34, R = 392, T = 12, B = 140;
    var maxY = Math.max.apply(null, all.map(function (p) { return p.years; }));
    function x(y) { return L + (R - L) * Math.sqrt(y / maxY); }            // the short end opened up, as a desk reads a curve
    function yv(r) { return B - (B - T) * (r - lo) / (hi - lo); }
    var svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "USD zero curves: OIS" + (ago ? ", OIS on " + ago.date : "") + (proj.length ? ", projection" : ""));
    function add(tag, attrs, cls, txt) {
      var n = document.createElementNS(SVG, tag);
      Object.keys(attrs).forEach(function (k) { n.setAttribute(k, attrs[k]); });
      if (cls) n.setAttribute("class", cls);
      if (txt !== undefined) n.textContent = txt;
      svg.appendChild(n);
      return n;
    }
    for (var g = 0; g <= 3; g++) {
      var r = lo + (hi - lo) * g / 3, yy = yv(r);
      add("line", { x1: L, y1: yy, x2: R, y2: yy }, "grid");
      add("text", { x: 2, y: yy + 3 }, "lbl", r.toFixed(2));
    }
    ["1M", "2Y", "10Y", "30Y"].forEach(function (t) {
      var p = ois.concat(proj).filter(function (q) { return q.tenor === t; })[0];
      if (p) add("text", { x: Math.min(x(p.years) - 8, R - 20), y: H - 6 }, "lbl", t);
    });
    function line(pts, cls) {
      if (!pts.length) return;
      add("polyline", { points: pts.map(function (p) { return x(p.years).toFixed(1) + "," + yv(p.rate).toFixed(1); }).join(" ") }, cls);
    }
    if (ago) line(ago.points, "ago");
    line(proj, "proj");
    line(ois, "ois");
    if (ois.length) { var lo1 = ois[ois.length - 1]; add("circle", { cx: x(lo1.years), cy: yv(lo1.rate), r: 3.5 }, "dot-ois"); }
    if (proj.length) { var lp = proj[proj.length - 1]; add("circle", { cx: x(lp.years), cy: yv(lp.rate), r: 3.5 }, "dot-proj"); }
    target.appendChild(svg);
    how.textContent = ago ? "zero rates, today against " + ago.date : "zero rates, OIS against projection";
    note.textContent = "violet: OIS" + (ago ? " (solid " + (curve.date || "today") + ", dashed " + ago.date + ")" : "") +
      (proj.length ? " · " + "accent: projection" : "") + (ago ? "" : " · no history a week back, so no week-ago line");
  }

  function desk() {
    get("/api/home/market/desk").then(function (d) {
      text("[data-ml-date]", d.dataDate || d.businessDate || "—");
      text("[data-ml-curves]", count(d.curves));
      text("[data-ml-kinds]", count(d.kinds));
      if (d.dataDate && d.businessDate && d.dataDate < d.businessDate) text("[data-ml-lag]", "· business date " + d.businessDate + ": the latest data is of " + d.dataDate);
      (d.tiles || []).forEach(tile);
      d.curve.date = d.dataDate;
      chart($("[data-ml-curve]"), d.curve || {}, $("[data-ml-curve-note]"), $("[data-ml-curve-how]"));
    }).catch(function (e) {
      text("[data-ml-date]", "—");
      $$("[data-ml-tile] [data-ml-v]").forEach(function (v) { if (v.textContent === "…") v.textContent = "—"; });
      $$("[data-ml-tile] [data-ml-s]").forEach(function (s) { s.textContent = e.message; });
      said($("[data-ml-curve]"), e);
    });

    var ex = $("[data-ml-tile='exceptions']");
    get("/api/home/market/quality").then(function (q) {
      var n = (q.critical || []).length + (q.warnings || []).length, parts = {};
      (q.critical || []).concat(q.warnings || []).forEach(function (e) { parts[e.check] = (parts[e.check] || 0) + 1; });
      ex.querySelector("[data-ml-v]").textContent = count(n);
      ex.querySelector("[data-ml-s]").textContent = n ? Object.keys(parts).map(function (k) { return parts[k] + " " + k; }).join(" · ")
        : "none of " + count(q.checked) + " items checked";
      if (n) ex.classList.add((q.critical || []).length ? "bad" : "warn");
    }).catch(function (e) { ex.querySelector("[data-ml-v]").textContent = "—"; ex.querySelector("[data-ml-s]").textContent = e.message; });

    var body = $("[data-ml-moves]"), card = $("[data-ml-moves-card]");
    get("/api/home/market/moves").then(function (m) {
      body.textContent = "";
      if (m.mode === "none" || !(m.items || []).length) {
        text("[data-ml-moves-how]", "no history per date");
        said(body, "The lake holds no history per business date for these items, so there are no moves to rank: it needs at least two days.", 4);
        return;
      }
      var sigma = m.mode === "sigma";
      text("[data-ml-moves-how]", sigma ? "by standard deviations of their own history" : "the largest against the business day before (too little history for σ)");
      $("[data-ml-sigma-h]").textContent = sigma ? "σ" : "since";
      m.items.forEach(function (it) {
        var tr = el("tr"), name = el("span");
        name.appendChild(link(go(it.cmd), it.id, "mono"));
        name.appendChild(document.createTextNode(" " + it.field));
        cell(tr, name);
        cell(tr, tone(el("span", "", signed(it.move, it.unit === "%" ? 2 : 1, it.unit)), it.move), "n");
        cell(tr, sigma ? (it.sigma === null ? "—" : it.sigma.toFixed(1)) : short(it.since), "n");
        var tag = el("td");
        if (sigma && it.sigma !== null && it.sigma >= 3) tag.appendChild(el("span", "ml-pill w", "jump"));
        tr.appendChild(tag);
        tr.title = it.cmd + ": " + it.since + " to " + it.date + (it.days ? ", " + it.days + " daily changes before" : "");
        body.appendChild(tr);
      });
    }).catch(function (e) { said(body, e, 4); if (card) card.setAttribute("data-error", "1"); });
  }

  // ---- B · Explorer ---------------------------------------------------------------------------------------------------------------
  function explorer() {
    var sums = {};
    $$("[data-ml-group]").forEach(function (ul) {
      var gid = ul.getAttribute("data-ml-group"), jobs = [];
      Array.prototype.slice.call(ul.querySelectorAll("[data-ml-count]")).forEach(function (b) {
        var code = b.getAttribute("data-ml-count");
        jobs.push(get("/api/home/search?q=" + encodeURIComponent(code + " limit 1")).then(function (a) {
          b.textContent = count(a.matched);
          return num(a.matched) || 0;
        }).catch(function () { b.textContent = "—"; return 0; }));
      });
      Promise.all(jobs).then(function (ns) {
        sums[gid] = ns.reduce(function (a, b) { return a + b; }, 0);
        text("[data-ml-group-count='" + gid + "']", count(sums[gid]));
      });
    });
  }

  // ---- C · Quality ----------------------------------------------------------------------------------------------------------------
  var CHECKS = { stale: "Stale", gap: "Gaps (missing tenors)", forwards: "Discount factors and forwards", surface: "Vol surfaces" };

  function quality() {
    var body = $("[data-ml-exceptions]"), checks = $("[data-ml-checks]");
    get("/api/home/market/quality").then(function (q) {
      text("[data-ml-date]", q.businessDate || "—");
      var crit = q.critical || [], warn = q.warnings || [];
      text("[data-ml-critical]", count(crit.length));
      text("[data-ml-warnings]", count(warn.length));
      text("[data-ml-critical-s]", crit.length ? summary(crit) : "none");
      text("[data-ml-warnings-s]", warn.length ? summary(warn) : "none");
      text("[data-ml-clean]", q.cleanPct === null || q.cleanPct === undefined ? "—" : (q.cleanPct >= 99.95 || q.cleanPct === 0 ? q.cleanPct.toFixed(0) : q.cleanPct.toFixed(1)) + "%");
      text("[data-ml-clean-s]", "of " + count(q.checked) + " items checked");
      body.textContent = "";
      if (!crit.length && !warn.length) said(body, "No exceptions: every item checked is clean.", 4);
      [["critical", crit, "r"], ["warning", warn, "w"]].forEach(function (g) {
        if (!g[1].length) return;
        var head = el("tr", "ml-sev"), td = el("td", "", g[0] === "critical" ? "Critical" : "Warnings");
        td.colSpan = 4; head.appendChild(td); body.appendChild(head);
        g[1].forEach(function (e) {
          var tr = el("tr");
          tr.setAttribute("data-ml-exception", e.check);
          cell(tr, link(go(e.cmd), e.cmd, "mono"));
          cell(tr, e.check);
          cell(tr, e.detail);
          var p = el("td"); p.appendChild(el("span", "ml-pill " + g[2], g[0])); tr.appendChild(p);
          body.appendChild(tr);
        });
      });
      checks.textContent = "";
      (q.checks || []).forEach(function (c) {
        var tr = el("tr");
        cell(tr, CHECKS[c.id] || c.id);
        cell(tr, count(c.items), "n");
        cell(tr, count(c.flagged), "n");
        checks.appendChild(tr);
      });
      if (!(q.checks || []).length) said(checks, (q.problems || [])[0] || "no checks ran", 3);
    }).catch(function (e) {
      ["[data-ml-critical]", "[data-ml-warnings]", "[data-ml-clean]"].forEach(function (s) { text(s, "—"); });
      said(body, e, 4); said(checks, e, 3);
    });
  }
  function summary(list) {
    var by = {};
    list.forEach(function (e) { by[e.check] = (by[e.check] || 0) + 1; });
    return Object.keys(by).map(function (k) { return by[k] + " " + k; }).join(" · ");
  }

  if (landing === "explorer") explorer();
  else if (landing === "quality") quality();
  else desk();
})();
