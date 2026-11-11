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
/* Report review and sign-off (terminal/var_reports.html; VAR_GUIDE.md section 18.14) and period statistics (var_statistics.html, section
   18.10): fills the pages from /api/var/reports and /api/var/statistics. Every text is set with textContent; the server decides every
   rule (the steps' order, four eyes, a failed check blocking sign-off) and says why in words, shown here as they come. The pilot pack,
   the report as it will be filed and the checks are risk grids (drishti-risk's preset: <drishti-grid>, terminal layout); a click on a
   report's cell (or Enter on it) opens its lineage. */
(function () {
  "use strict";
  const R = window.Drishti.elements.risk;
  const fmt = (v) => v == null || !isFinite(v) ? "—" : Math.round(v).toLocaleString("en-US");
  const mln = (v) => v == null || !isFinite(v) ? "—" : (v / 1e6).toFixed(2) + "m";
  function el(tag, attrs, text) {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => e.setAttribute(k, v));
    if (text != null) { e.textContent = text; }
    return e;
  }
  async function getJson(url, opts) {
    const r = await fetch(url, Object.assign({ credentials: "same-origin" }, opts || {}));
    let j = {};
    try { j = await r.json(); } catch (e) { j = { detail: "the server answered " + r.status }; }
    return { ok: r.ok, j };
  }
  function tile(box, k, big, s) {
    const t = el("div", { class: "rg-tile" });
    t.append(el("div", { class: "rg-k" }, k), el("div", { class: "rg-big" }, big), el("div", { class: "rg-s" }, s || ""));
    box.append(t);
  }
  function statTiles(box, st) {
    box.replaceChildren();
    if (!st || !st.periodEnd) { box.append(el("p", { class: "rg-note" }, (st && st.note) || "no official record in the period")); return; }
    tile(box, "Period end", mln(st.periodEnd.value), st.node + " " + st.periodEnd.date);
    const E = window.Drishti && window.Drishti.Explain;             // the one Explain › of the period end's VaR (VAR_GUIDE.md 3.11)
    if (E && st.node) { box.lastChild.append(E.link({ kind: "var", measure: "var", scope: String(st.node).indexOf(":") > 0 ? st.node : "NODE:" + st.node, date: st.periodEnd.date })); }
    tile(box, "Average", mln(st.average), st.records + " official records");
    tile(box, "High", mln(st.high.value), st.high.date);
    tile(box, "Low", mln(st.low.value), st.low.date);
    const a = Object.keys(st).find((k) => /^average\d+$/.test(k));
    if (a && st[a]) { tile(box, a.slice(7) + "-day average", mln(st[a].average), "for the capital formula, to " + st[a].to); }
    if (st.stressedAverage) {
      tile(box, "Stressed VaR, " + st.stressedAverage.weeks + "-week avg", mln(st.stressedAverage.average), st.stressedAverage.records + " records");
    }
  }

  // ------------------------------------------------------------------------------------------------------------ statistics
  const stats = document.querySelector("[data-statistics]");
  if (stats) {
    (async () => {
      const p = new URLSearchParams({ scope: stats.dataset.scope, period: stats.dataset.period, measure: stats.dataset.measure });
      const { ok, j } = await getJson("/api/var/statistics?" + p);
      const n = stats.querySelector("[data-st-notice]");
      if (!ok) { n.hidden = false; n.textContent = j.detail || "no statistics"; stats.querySelector("[data-st-meta]").textContent = ""; return; }
      stats.querySelector("[data-st-meta]").textContent = j.node + " · " + j.measure.toUpperCase() + " · " + j.period + " (" + j.from + " to " + j.to
        + ", fiscal year end " + j.fiscalYearEnd + ")" + (j.synthetic ? " · " + j.synthetic + " synthetic demo records" : "");
      if (j.previewNote) { n.hidden = false; n.textContent = "Preview: " + j.previewNote; }
      statTiles(stats.querySelector("[data-st-tiles]"), j);
      const m = j.missing || [];
      stats.querySelector("[data-st-missing]").textContent = m.length ? "Missing official records: " + m.join(", ") : "No official record missing in the period.";
    })();
  }

  // ------------------------------------------------------------------------------------------------------------ reports
  const root = document.querySelector("[data-reports]");
  if (!root) { return; }
  const $ = (s) => root.querySelector(s);
  let report = root.dataset.report;
  let period = root.dataset.period;
  let run = null;

  function notice(text) {
    const n = $("[data-rp-notice]");
    n.hidden = !text;
    n.textContent = text || "";
  }

  async function loadPack() {
    const { ok, j } = await getJson("/api/var/reports");
    if (!ok) { notice(j.detail); return null; }
    $("[data-rp-pack-sub]").textContent = (j.reports || []).length + " reports · fiscal year end " + j.fiscalYearEnd;
    (j.reports || []).forEach((r) => { if (r.report === report && !period) { period = r.currentPeriod; } });
    const rows = (j.reports || []).map((r) => ({ report: r.report, title: r.title, period: r.currentPeriod, run: r.run ? "v" + r.run.version : "—",
      state: r.run ? r.run.state : "not generated" }));
    R.riskGrid($("[data-rp-pack]"), { title: "Pilot pack", rows: rows, getRowId: (r) => r.report,
      getRowClass: (r) => (r.report === report ? "rg-sel" : null), getRowData: (r) => ({ report: r.report }),
      columns: [{ field: "report", header: "Report", width: 150, cellRenderer: (x) => el("a", { href: "/var/reports/" + x.value + "?period=" + encodeURIComponent(x.row.period) }, x.value) },
        { field: "title", header: "Title", width: 260 }, { field: "period", header: "Period", width: 100 },
        { field: "run", header: "Run", width: 60, cellClass: () => "dg-num" }, { field: "state", header: "State", width: 110 },
        { id: "generate", field: "period", header: "", width: 170, sortable: false, cellRenderer: (x) => {
          const gen = el("button", { type: "button", class: "dg-cell-btn" }, "Generate " + x.row.period);
          gen.addEventListener("click", () => generate(x.row.report, x.row.report === report && period ? period : x.row.period));
          return gen;
        } }] });
    return j;
  }

  async function generate(rep, per) {
    notice("generating…");
    const { ok, j } = await getJson("/api/var/reports/" + rep + "/runs?period=" + encodeURIComponent(per), { method: "POST",
      headers: { "Content-Type": "application/json" }, body: "{}" });
    if (!ok) { notice(j.detail); return; }
    notice("");
    location.href = "/var/reports/" + rep + "?period=" + encodeURIComponent(j.period) + "&version=" + j.version;
  }

  async function loadRun() {
    if (!report) { return; }
    const v = root.dataset.version ? "?version=" + root.dataset.version : "";
    const { ok, j } = await getJson("/api/var/reports/" + report + "/runs/" + encodeURIComponent(period) + v);
    if (!ok) { notice(j.detail); return; }
    run = j;
    draw();
  }

  function draw() {
    const r = run;
    $("[data-rp-command]").textContent = "VAR REPORT " + r.report + " " + r.period;
    $("[data-rp-meta]").textContent = "run v" + r.version + ", frozen " + r.frozenAt + " by " + r.frozenBy + " · read from the official records, never recomputed"
      + (r.synthetic ? " · " + r.synthetic + " synthetic demo records" : "");
    notice(r.previewNote ? "Preview: " + r.previewNote : "");
    if (r.statistics) { statTiles($("[data-rp-stats]"), r.statistics); }
    $("[data-rp-report]").hidden = false;
    $("[data-rp-title]").textContent = r.title;
    const box = $("[data-rp-table]");
    box.replaceChildren();
    if (r.type === "backtesting-chart") {
      const bt = r.backtest || {};
      const p = el("p", {}, "Zone " + bt.zone + ", multiplier " + bt.multiplier + " over " + bt.days + " days to " + bt.to + ". ");
      p.append(el("a", { href: "/var/backtesting?scope=" + encodeURIComponent("NODE:" + r.node) }, "Open the backtesting chart"));
      box.append(p);
      $("[data-rp-foot]").textContent = bt.rule || "";
    } else {
      table(box, r);
      $("[data-rp-foot]").textContent = (r.footnotes || []).join(" ");
    }
    const card = $("[data-rp-narr-card]");
    card.hidden = false;
    $("[data-rp-facts]").textContent = "Facts: " + ((r.facts || {}).text || "");
    $("[data-rp-narrative]").value = (r.signoff || {}).narrative || "";
    sign();
    checks();
  }

  /** The report as it will be filed: a row per line (the totals marked), a column per figure; each cell's text is the server's value in
   *  the report's units; a click on a cell (or Enter on the focused one) opens its lineage. */
  function table(box, r) {
    const thousands = r.units === "thousands";
    const names = { "period-end": "Period end", average: "Average", high: "High", low: "Low", "prior-period-end": (r.priorPeriod || "Prior") + " period end", movement: "Movement" };
    const cellOf = (row, c) => (row.cells || {})[c] || {};
    const textOf = (c, cell) => {
      if (cell.value == null) { return cell.text || "—"; }
      if (c === "movement") { return (cell.value >= 0 ? "+" : "") + (cell.value * 100).toFixed(1) + "%" + (cell.flag ? " ⚑" : ""); }
      return fmt(thousands ? cell.value / 1000 : cell.value);
    };
    const rows = (r.rows || []).map((row, i) => ({ id: String(i), line: row, label: row.label + (row.code ? " " + row.code : "") }));
    const columns = [{ field: "label", header: "1-day 99% VaR, USD" + (thousands ? " thousands" : ""), width: 300, pinned: "left" }].concat((r.columns || []).map((c) => ({
      id: c, header: names[c] || c, type: "number", additive: false, width: 130, valueGetter: (x) => cellOf(x.line, c).value,
      format: (v, x) => textOf(c, x && x.line ? cellOf(x.line, c) : { value: v }),
      cellClass: (v, x) => "rg-cell" + (cellOf(x.line, c).flag ? " dg-tone-warn" : "") })));
    const grid = R.riskGrid(box, { title: r.title || "Report", rows: rows, columns: columns, getRowId: (x) => x.id, fitRows: 40,
      getRowClass: (x) => (x.line.total ? "rg-total" : null) });
    const open = (d) => {
      if (!d || d.kind !== "leaf" || !d.data || d.columnId === "label") { return; }
      const cell = cellOf(d.data.line, d.columnId);
      lineage(d.data.line, d.columnId, cell, textOf(d.columnId, cell));
    };
    box._open = open;
    box._grid = grid;
    if (box._wired) { return; }
    box._wired = true;
    box.addEventListener("drishti:cell-clicked", (e) => box._open(e.detail));
    box.addEventListener("keydown", (e) => {
      const f = box._grid.model.navigator.focus;
      if (e.key === "Enter" && f) { e.preventDefault(); box._open(box._grid.model.cellContext(f.row, f.col)); }
    });
  }

  function lineage(row, col, cell, text) {
    $("[data-rp-lin-card]").hidden = false;
    const dl = $("[data-rp-lineage]");
    dl.replaceChildren();
    const add = (k, v) => { if (v != null && v !== "") { dl.append(el("dt", {}, k), el("dd", {}, typeof v === "object" ? JSON.stringify(v) : String(v))); } };
    const l = cell.lineage || {};
    add("Cell", row.label + " · " + col + " · " + text);
    add("Official record", l.record ? l.record + (l.resultVersion != null ? " · result v" + l.resultVersion : "") : null);
    add("Node", l.node);
    add("Date", l.date);
    add("Capture", l.captureId);
    add("Records", l.records);
    add("Period", l.period);
    add("Data versions", l.inputs);
    add("Computed", l.computed);
    add("Definition", l.definition);
    add("Run", run.report + " · " + run.period + " · run v" + run.version + " (frozen " + run.frozenAt + ")");
    add("Code", run.code);
    const links = $("[data-rp-lin-links]");
    links.replaceChildren();
    if (l.date && l.node) {
      links.append(el("a", { class: "rg-btn", href: "/var?scope=" + encodeURIComponent("NODE:" + l.node) + "&asOf=" + l.date }, "Open the VaR page of " + l.date));
      const E = window.Drishti && window.Drishti.Explain;           // the one Explain › of the cell's VaR (VAR_GUIDE.md 3.11)
      if (E) { links.append(" ", E.link({ kind: "var", measure: "var", scope: "NODE:" + l.node, date: l.date })); }
    }
  }

  function sign() {
    const s = run.signoff || {};
    $("[data-rp-sign-card]").hidden = false;
    const ol = $("[data-rp-steps]");
    ol.replaceChildren();
    const steps = s.steps || [];
    const chain = run.signOff || [];
    const titles = run.stepTitles || {};
    const li = (cls, title, sub) => {
      const x = el("li");
      const d = el("div");
      d.append(el("strong", {}, title), el("div", { class: "rg-note" }, sub));
      x.append(el("span", { class: "rg-dot " + cls, "aria-hidden": "true" }), d);
      ol.append(x);
    };
    li("done", "Generated", run.frozenBy + " · " + run.frozenAt);
    const cs = run.checksSummary || {};
    li(cs.fail ? "now" : "done", "Checks", cs.pass + " pass · " + cs.note + " note · " + cs.fail + " fail");
    chain.forEach((step, i) => {
      const done = steps[i];
      const title = titles[step] || step.charAt(0).toUpperCase() + step.slice(1);
      li(done ? "done" : i === steps.length ? "now" : "", title, done ? done.who + " · " + done.when + (done.comment ? " · “" + done.comment + "”" : "")
        : i === steps.length ? "waiting" : "");
    });
    li(s.state === "signed" ? "done" : "", "File", (run.outputs || []).join(" · ") + " to the reporting team");
    const next = s.next;
    const sign = $("[data-rp-approve]");                // the step is signed in the one Approvals queue, where the run is an item
    sign.textContent = next ? "Sign as " + (titles[next] || next.charAt(0).toUpperCase() + next.slice(1)) + " in Approvals" : "Signed";
    sign.href = "/approvals/var-report/" + encodeURIComponent(run.report + ":" + run.period + ":v" + run.version).replace(/%3A/g, ":");
    sign.classList.toggle("disabled", !next);
    if (!next) { sign.setAttribute("aria-disabled", "true"); } else { sign.removeAttribute("aria-disabled"); }
    $("[data-rp-save-narrative]").disabled = next !== "maker";
    $("[data-rp-narrative]").readOnly = next !== "maker";
    const files = $("[data-rp-files]");
    files.replaceChildren();
    (run.outputs || []).forEach((f) => {
      const signed = s.state === "signed";
      files.append(el("a", { class: "rg-btn", download: "", href: "/api/var/reports/" + run.report + "/runs/" + encodeURIComponent(run.period)
        + "/file?format=" + f + "&version=" + run.version + (signed ? "" : "&draft=true") }, signed ? f : f + " (draft)"));
    });
  }

  function checks() {
    $("[data-rp-checks-card]").hidden = false;
    const cs = run.checksSummary || {};
    $("[data-rp-checks-sub]").textContent = (run.checks || []).length + " · " + cs.pass + " pass, " + cs.note + " need a note, " + cs.fail
      + " fail; a failed check blocks sign-off, never generation";
    const rows = (run.checks || []).map((c, i) => ({ id: String(i), status: c.status, title: c.title || c.check, evidence: c.evidence || "" }));
    R.riskGrid($("[data-rp-checks]"), { title: "Checks", rows: rows, getRowId: (x) => x.id,
      columns: [{ field: "status", header: "Status", width: 80, cellRenderer: (x) => el("span", { class: "rg-pill " + x.value }, x.value) },
        { field: "title", header: "Check", width: 230 }, { field: "evidence", header: "Evidence", width: 320, cellClass: () => "dg-tone-muted" }] });
  }

  async function saveNarrative() {
    const body = { version: run.version, narrative: $("[data-rp-narrative]").value };
    const { ok, j } = await getJson("/api/var/reports/" + run.report + "/runs/" + encodeURIComponent(run.period) + "/narrative", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (!ok) { notice(j.detail); return; }
    notice("");
    run = j;
    draw();
    loadPack();
  }
  $("[data-rp-save-narrative]").addEventListener("click", saveNarrative);

  loadPack().then(loadRun);
})();
