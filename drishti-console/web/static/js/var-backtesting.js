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
/* Backtesting (terminal/var_backtesting.html; VAR_GUIDE.md section 18.11): fills the page from /api/var/backtesting?scope=. The chart is
   drawn here as SVG (no library): actual P&L bars, hypothetical P&L line, -VaR of the day before at 99% and 97.5%, exceptions marked
   (▲ actual, ○ hypothetical); every text is set with textContent. A scope chip or a desk row redraws the page in place. */
(function () {
  "use strict";
  const root = document.querySelector("[data-backtesting]");
  if (!root) { return; }
  const $ = (s) => root.querySelector(s);
  const NS = "http://www.w3.org/2000/svg";
  let scope = root.dataset.scope || "BANK";
  let q = 0;
  let data = null;
  let parts = null;

  const fmt = (v) => v == null || !isFinite(v) ? "—" : Math.round(v).toLocaleString("en-US");
  const mln = (v) => v == null || !isFinite(v) ? "—" : (v / 1e6).toFixed(2) + "m";
  function el(tag, attrs, text) {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => e.setAttribute(k, v));
    if (text != null) { e.textContent = text; }
    return e;
  }
  function svg(tag, attrs) {
    const e = document.createElementNS(NS, tag);
    Object.entries(attrs || {}).forEach(([k, v]) => e.setAttribute(k, v));
    return e;
  }
  const key = (i) => i === 0 ? "var990" : "var975";

  async function load() {
    $("[data-bt-source]").textContent = "loading…";
    const asOf = root.dataset.asof ? "&asOf=" + encodeURIComponent(root.dataset.asof) : "";
    const r = await fetch("/api/var/backtesting?scope=" + encodeURIComponent(scope) + asOf, { credentials: "same-origin" });
    const j = await r.json();
    if (!r.ok) { notice(j.detail || "the backtest could not be computed"); $("[data-bt-source]").textContent = ""; return; }
    data = j;
    if (j.parts && (!parts || j.parts.length > 1)) { parts = j.parts; }
    $("[data-bt-xlsx]").href = "/api/var/backtesting?format=xlsx&scope=" + encodeURIComponent(scope) + asOf;
    history.replaceState(null, "", "/var/backtesting?scope=" + encodeURIComponent(scope));
    draw();
  }

  function notice(text) {
    const n = $("[data-bt-notice]");
    n.hidden = !text;
    n.textContent = text || "";
  }

  function draw() {
    const d = data;
    $("[data-bt-source]").textContent = d.applicable === false ? (d.note || "") : (d.source || "") + (d.synthetic ? " · " + d.synthetic
      + " of the days on synthetic demo records" : "");
    notice(d.applicable === false ? d.note : d.short || "");
    chips();
    $("[data-bt-window]").textContent = d.days != null ? "Window " + d.days + " days to " + d.to : "";
    $("[data-bt-title]").textContent = (d.name || d.node) + " · VaR against actual and hypothetical P&L";
    $("[data-bt-zone-title]").textContent = (d.name || d.node) + " · zone and multiplier";
    $("[data-bt-zone-sub]").textContent = d.days != null ? d.days + " days to " + d.to : "";
    root.querySelectorAll("[data-lamp]").forEach((l) => l.classList.toggle("on", l.dataset.lamp === d.zone));
    $("[data-bt-zone]").textContent = d.zone || "—";
    const tb = $("[data-bt-counts]");
    tb.replaceChildren();
    (d.counts || []).forEach((c) => {
      const pct = (c.confidence * 100).toFixed(c.confidence * 100 % 1 ? 1 : 0) + "%";
      tb.append(rowOf([pct + ", 1-day", "", c.note ? c.note : "expected " + c.expected], true));
      tb.append(rowOf(["Actual P&L", c.actual, c.actualZone || "—"]));
      tb.append(rowOf(["Hypothetical P&L", c.hypothetical, c.hypotheticalZone || "—"]));
      tb.append(rowOf(["Risk-theoretical P&L", c.riskTheoretical, ""]));
    });
    $("[data-bt-mult]").textContent = d.multiplier != null ? Number(d.multiplier).toFixed(2) : "—";
    $("[data-bt-rule-short]").textContent = d.configuration ? "by the " + d.configuration.count + " count" : "";
    $("[data-bt-var]").textContent = mln(d.var);
    $("[data-bt-var-date]").textContent = d.asOf ? "official record, " + d.asOf : "";
    $("[data-bt-rule]").textContent = d.rule || "";
    $("[data-bt-tests]").textContent = d.tests ? "Kupiec p " + d.tests.kupiecP + " · Christoffersen p " + d.tests.christoffersenP
      + " (on the actual P&L)" : "";
    chart();
    table();
    exceptions();
  }

  function rowOf(cells, head) {
    const tr = el("tr");
    cells.forEach((c, i) => {
      const td = el("td", i ? { class: "num" } : {});
      if (head) { td.className = i ? "num rg-note" : "rg-note"; }
      if (i === 2 && ["green", "amber", "red"].includes(c)) { td.append(el("span", { class: "rg-pill " + c }, c)); } else { td.textContent = c == null ? "—" : c; }
      tr.append(td);
    });
    return tr;
  }

  function chips() {
    const box = $("[data-bt-scopes]");
    box.replaceChildren();
    (parts || []).forEach((p) => {
      const b = el("button", { type: "button", class: "rg-chip", "aria-pressed": String(p.node === data.node) }, label(p));
      b.addEventListener("click", () => { scope = "NODE:" + p.node; load(); });
      box.append(b);
    });
  }
  const label = (p) => p.level === "bank" ? "BANK" : (p.node || "").split("~").pop();

  function chart() {
    const box = $("[data-bt-chart]");
    box.replaceChildren();
    const rows = data.rows || [];
    if (!rows.length) { box.append(el("p", { class: "rg-note" }, data.note || "no tested day yet")); return; }
    const W = 1100, H = 340, L = 60, R = 10, T = 10, B = 24;
    let lo = 0, hi = 0;
    rows.forEach((r) => {
      [r.actual, r.hypothetical, -(r[key(0)] || 0), -(r[key(1)] || 0)].forEach((v) => { if (v != null && isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } });
    });
    lo *= 1.12; hi *= 1.08;
    const y = (v) => T + (hi - v) / (hi - lo || 1) * (H - T - B);
    const step = (W - L - R) / rows.length;
    const x = (i) => L + i * step + step / 2;
    const s = svg("svg", { viewBox: `0 0 ${W} ${H}`, role: "presentation" });
    for (let k = 0; k <= 4; k++) {                                  // the value axis: five ticks
      const v = lo + (hi - lo) * k / 4;
      s.append(svg("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), class: "axis", "stroke-opacity": ".35" }));
      const t = svg("text", { x: L - 6, y: y(v) + 3, "text-anchor": "end" });
      t.textContent = mln(v);
      s.append(t);
    }
    let month = "";
    rows.forEach((r, i) => {
      const m = r.date.slice(0, 7);
      if (m !== month) {
        month = m;
        const t = svg("text", { x: x(i), y: H - 6 });
        t.textContent = new Date(r.date + "T00:00:00Z").toLocaleString("en-US", { month: "short", year: "2-digit", timeZone: "UTC" });
        s.append(t);
      }
      const v = r.actual;
      s.append(svg("rect", { x: x(i) - step * 0.35, width: Math.max(1, step * 0.7), y: Math.min(y(0), y(v)), height: Math.abs(y(v) - y(0)),
        class: v >= 0 ? "gain" : "loss" }));
    });
    const line = (f, cls) => {
      const pts = rows.map((r, i) => (f(r) == null ? null : x(i).toFixed(1) + "," + y(f(r)).toFixed(1))).filter(Boolean);
      if (pts.length) { s.append(svg("polyline", { points: pts.join(" "), class: cls })); }
    };
    line((r) => r.hypothetical, "hypo");
    line((r) => r[key(1)] == null ? null : -r[key(1)], "var975");
    line((r) => r[key(0)] == null ? null : -r[key(0)], "var99");
    rows.forEach((r, i) => {
      const v = r[key(q)];
      if (v == null) { return; }
      if (r.actual < -v) {
        const yy = y(r.actual) + 6;
        s.append(svg("path", { d: `M${x(i)},${yy} l5,9 l-10,0 z`, class: "exc" }));
      }
      if (r.hypothetical < -v) { s.append(svg("circle", { cx: x(i), cy: y(r.hypothetical), r: 4, class: "exch" })); }
    });
    box.append(s);
  }

  function spark(p) {
    const pts = p.spark || [];
    const s = svg("svg", { viewBox: "0 0 180 26", class: "rg-spark", role: "presentation" });
    if (!pts.length) { return s; }
    let lo = 0, hi = 0;
    pts.forEach(([a, v]) => { lo = Math.min(lo, a, -v); hi = Math.max(hi, a); });
    const y = (v) => 2 + (hi - v) / (hi - lo || 1) * 22;
    const st = 180 / pts.length;
    pts.forEach(([a, v], i) => {
      s.append(svg("line", { x1: i * st, x2: i * st, y1: y(0), y2: y(a), class: a >= 0 ? "gain" : "loss" }));
      if (a < -v) { s.append(svg("circle", { cx: i * st, cy: 24, r: 1.6, class: "exc" })); }
    });
    s.append(svg("polyline", { points: pts.map(([, v], i) => (i * st).toFixed(1) + "," + y(-v).toFixed(1)).join(" "), class: "var99" }));
    return s;
  }

  function table() {
    const tb = $("[data-bt-parts]");
    tb.replaceChildren();
    (parts || []).forEach((p) => {
      const c = p.counts || [];
      const tr = el("tr", { "data-node": p.node, class: p.node === data.node ? "sel" : "" });
      const a = el("a", { href: "/var/backtesting?scope=" + encodeURIComponent("NODE:" + p.node) }, label(p));
      const td0 = el("td");
      td0.append(a);
      tr.append(td0, el("td", { class: "num" }, mln(p.var)));
      [[0, "actual"], [0, "hypothetical"], [1, "actual"], [1, "hypothetical"]].forEach(([k, f]) => tr.append(el("td", { class: "num" },
        c[k] && c[k][f] != null ? String(c[k][f]) : "—")));
      const z = el("td");
      if (p.zone) { z.append(el("span", { class: "rg-pill " + p.zone }, p.zone)); } else { z.textContent = p.applicable === false ? "n/a" : "—"; }
      tr.append(z, el("td", { class: "num" }, p.multiplier != null ? Number(p.multiplier).toFixed(2) : "—"));
      const sp = el("td");
      sp.append(spark(p));
      tr.append(sp);
      tr.addEventListener("click", (e) => { if (e.target.tagName !== "A") { e.preventDefault(); scope = "NODE:" + p.node; load(); } });
      tb.append(tr);
    });
  }

  function exceptions() {
    const tb = $("[data-bt-exc]");
    tb.replaceChildren();
    $("[data-bt-exc-title]").textContent = (data.name || data.node || "") + " exceptions";
    (data.exceptions || []).forEach((e) => {
      const tr = el("tr");
      [e.date, fmt(e.var990), fmt(e.actual), fmt(e.hypothetical), fmt(e.riskTheoretical), e.which, fmt(e.excess), (e.drivers || []).join(", ")]
        .forEach((v, i) => tr.append(el("td", { class: i > 0 && i < 7 && i !== 5 ? "num" + (String(v).startsWith("-") ? " neg" : "") : "" }, v)));
      const o = el("td");
      o.append(el("a", { href: "/var?scope=" + encodeURIComponent("NODE:" + data.node) + "&asOf=" + e.prior }, "VaR of " + e.prior));
      tr.append(o);
      tb.append(tr);
    });
    const u = data.untested || [];
    $("[data-bt-untested]").textContent = u.length ? u.length + " day(s) not tested: " + u.slice(0, 5).map((x) => x.date + " (" + x.reason + ")").join("; ") : "";
  }

  root.querySelectorAll("[data-bt-q]").forEach((b) => b.addEventListener("click", () => {
    q = Number(b.dataset.btQ);
    root.querySelectorAll("[data-bt-q]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    if (data) { chart(); }
  }));
  load();
})();
