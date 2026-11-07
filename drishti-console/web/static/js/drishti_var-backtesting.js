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
/* Backtesting (terminal/var_backtesting.html; VAR_GUIDE.md section 18.11): fills the page from /api/var/backtesting?scope=. The chart is
   drishti-risk's risk.backtest (actual P&L against −VaR of the day before at the chosen confidence, the hypothetical P&L beside it,
   exceptions marked, the server's zone and count on the card); the zone counts, the bank and its desks and the exceptions are risk grids
   (<drishti-grid>, terminal layout). Every figure is the server's. A scope chip or a desk row redraws the page in place. */
(function () {
  "use strict";
  const root = document.querySelector("[data-backtesting]");
  if (!root) { return; }
  const R = window.Drishti.elements.risk;
  const core = window.Drishti.elements.core;
  const $ = (s) => root.querySelector(s);
  let scope = root.dataset.scope || "BANK";
  let q = 0;
  let data = null;
  let parts = null;

  const fmt = (v) => v == null || !isFinite(v) ? "—" : Math.round(v).toLocaleString("en-US");
  const mln = (v) => v == null || !isFinite(v) ? "—" : (v / 1e6).toFixed(2) + "m";
  const text = (v) => v == null || v === "" ? "—" : String(v);
  const num = () => "dg-num";
  function el(tag, attrs, t) {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => e.setAttribute(k, v));
    if (t != null) { e.textContent = t; }
    return e;
  }
  const pill = (z) => (["green", "amber", "red"].includes(z) ? el("span", { class: "rg-pill " + z }, z) : null);
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

  function notice(t) {
    const n = $("[data-bt-notice]");
    n.hidden = !t;
    n.textContent = t || "";
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
    counts();
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

  /** The zone card's counts: a heading row per confidence, then actual, hypothetical and risk-theoretical (the server's counts). */
  function counts() {
    const rows = [];
    (data.counts || []).forEach((c, i) => {
      const pct = (c.confidence * 100).toFixed(c.confidence * 100 % 1 ? 1 : 0) + "%";
      rows.push({ id: i + "h", label: pct + ", 1-day", count: "", zone: c.note ? c.note : "expected " + c.expected, head: true });
      rows.push({ id: i + "a", label: "Actual P&L", count: text(c.actual), zone: c.actualZone || "—" });
      rows.push({ id: i + "y", label: "Hypothetical P&L", count: text(c.hypothetical), zone: c.hypotheticalZone || "—" });
      rows.push({ id: i + "r", label: "Risk-theoretical P&L", count: text(c.riskTheoretical), zone: "" });
    });
    R.riskGrid($("[data-bt-counts]"), { title: "Exceptions by confidence", getRowId: (r) => r.id, rows: rows,
      getRowClass: (r) => (r.head ? "rg-head-row" : null),
      columns: [{ field: "label", header: "Confidence, 1-day", width: 170, sortable: false },
        { field: "count", header: "Exceptions", width: 90, sortable: false, cellClass: num },
        { field: "zone", header: "Zone", width: 120, sortable: false, cellRenderer: (x) => pill(x.value) }] });
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

  /** risk.backtest over the server's days: actual P&L against −VaR at the chosen confidence; the zone and count are the server's. */
  function chart() {
    const box = $("[data-bt-chart]");
    const rows = data.rows || [];
    if (!rows.length) { box.replaceChildren(el("p", { class: "rg-note" }, data.note || "no tested day yet")); return; }
    const c = (data.counts || [])[q] || {};
    const v = key(q), conf = c.confidence || (q ? 0.975 : 0.99);
    R.riskChart(box, { kind: "risk.backtest", data: { rows: rows }, id: "date", x: { field: "date", type: "category" },
      pnl: "actual", pnlTitle: "Actual P&L", actual: "hypothetical", actualTitle: "Hypothetical P&L", var: v, confidence: conf, window: rows.length,
      light: { exceptions: c.actual, zone: q === 0 ? data.zone : c.actualZone, multiplier: q === 0 ? data.multiplier : null },
      marks: [{ when: "hypothetical < -" + v, as: "point", tone: "warn", field: "hypothetical", name: "Hypothetical exception" }],
      y: { format: "compact" } }, { height: 320 });
    box.dataset.var = v;                                   // the confidence drawn (a test waits for it)
  }

  function table() {
    const sel = data.node;
    $("[data-bt-parts]").dataset.selected = sel;           // the scope drawn (its row marked)
    const cnt = (p, k, f) => { const c = p.counts || []; return c[k] && c[k][f] != null ? String(c[k][f]) : "—"; };
    const rows = (parts || []).map((p) => ({ node: p.node, scope: label(p), var: p.var, a99: cnt(p, 0, "actual"), h99: cnt(p, 0, "hypothetical"),
      a975: cnt(p, 1, "actual"), h975: cnt(p, 1, "hypothetical"), zone: p.zone || (p.applicable === false ? "n/a" : "—"),
      multiplier: p.multiplier != null ? Number(p.multiplier).toFixed(2) : "—", spark: p.spark || [] }));
    const count = (f, h, w) => ({ field: f, header: h, width: w || 90, cellClass: num });
    R.riskGrid($("[data-bt-parts]"), { title: "Bank and desks", rows: rows, getRowId: (r) => r.node,
      getRowClass: (r) => (r.node === sel ? "rg-sel" : null), getRowData: (r) => ({ node: r.node }),
      onRow: (r) => { scope = "NODE:" + r.node; load(); },
      columns: [{ field: "scope", header: "Scope", width: 150, pinned: "left",
        cellRenderer: (x) => el("a", { href: "/var/backtesting?scope=" + encodeURIComponent("NODE:" + x.row.node) }, x.text) },
        R.figureColumn("var", "VaR 99%", { format: mln }),
        count("a99", "99% actual"), count("h99", "99% hypo"), count("a975", "97.5% actual", 100), count("h975", "97.5% hypo", 100),
        { field: "zone", header: "Zone", width: 80, cellRenderer: (x) => pill(x.value) },
        count("multiplier", "Multiplier"),
        { id: "days", field: "spark", header: "Days", width: 190, sortable: false, valueGetter: (r) => (r.spark || []).length,
          cellRenderer: (x) => spark(x.row.spark) }] });
  }

  /** A desk's days in a cell: drishti-core's sparkline of its actual P&L (the server's points). */
  function spark(pts) {
    const s = el("span", { class: "rg-spark" });
    if (pts && pts.length > 1) { s.append(core.sparkline(document, pts.map((p) => p[0]), undefined, "dg")); }
    return s;
  }

  function exceptions() {
    $("[data-bt-exc-title]").textContent = (data.name || data.node || "") + " exceptions";
    const node = data.node;
    const rows = (data.exceptions || []).map((e) => Object.assign({}, e, { drivers: (e.drivers || []).join(", ") }));
    const n = (f, h) => R.figureColumn(f, h, { format: fmt, width: 120, tone: (v) => (v < 0 ? "bad" : null) });
    R.riskGrid($("[data-bt-exc]"), { title: "Exceptions", rows: rows, getRowId: (r) => r.date + "|" + r.which,
      columns: [{ field: "date", header: "Date", width: 100 }, n("var990", "VaR 99% (day before)"), n("actual", "Actual"), n("hypothetical", "Hypothetical"),
        n("riskTheoretical", "Risk-theoretical"), { field: "which", header: "Which", width: 90 }, n("excess", "Excess"),
        { field: "drivers", header: "Desks that drove it", width: 220 },
        { field: "prior", header: "Open", width: 140, cellRenderer: (x) => el("a", { href: "/var?scope=" + encodeURIComponent("NODE:" + node) + "&asOf=" + x.value }, "VaR of " + x.value) }] });
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
