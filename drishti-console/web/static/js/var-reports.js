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
/* Report review and sign-off (terminal/var_reports.html; VAR_GUIDE.md section 18.14) and period statistics (var_statistics.html, section
   18.10): fills the pages from /api/var/reports and /api/var/statistics. Every text is set with textContent; the server decides every
   rule (the steps' order, four eyes, a failed check blocking sign-off) and says why in words, shown here as they come. */
(function () {
  "use strict";
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
    const tb = $("[data-rp-pack]");
    tb.replaceChildren();
    if (!ok) { notice(j.detail); return null; }
    $("[data-rp-pack-sub]").textContent = (j.reports || []).length + " reports · fiscal year end " + j.fiscalYearEnd;
    (j.reports || []).forEach((r) => {
      if (r.report === report && !period) { period = r.currentPeriod; }
      const tr = el("tr", { class: r.report === report ? "sel" : "" });
      const a = el("a", { href: "/var/reports/" + r.report + "?period=" + encodeURIComponent(r.currentPeriod) }, r.report);
      const td = el("td");
      td.append(a);
      const gen = el("button", { type: "button", class: "rg-btn" }, "Generate " + r.currentPeriod);
      gen.addEventListener("click", () => generate(r.report, r.report === report && period ? period : r.currentPeriod));
      const g = el("td");
      g.append(gen);
      tr.append(td, el("td", {}, r.title), el("td", {}, r.currentPeriod), el("td", { class: "num" }, r.run ? "v" + r.run.version : "—"),
        el("td", {}, r.run ? r.run.state : "not generated"), g);
      tb.append(tr);
    });
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
      box.append(table(r));
      $("[data-rp-foot]").textContent = (r.footnotes || []).join(" ");
    }
    const card = $("[data-rp-narr-card]");
    card.hidden = false;
    $("[data-rp-facts]").textContent = "Facts: " + ((r.facts || {}).text || "");
    $("[data-rp-narrative]").value = (r.signoff || {}).narrative || "";
    sign();
    checks();
  }

  function table(r) {
    const thousands = r.units === "thousands";
    const t = el("table");
    const h = el("tr");
    h.append(el("th", {}, "1-day 99% VaR, USD" + (thousands ? " thousands" : "")));
    const names = { "period-end": "Period end", average: "Average", high: "High", low: "Low", "prior-period-end": (r.priorPeriod || "Prior") + " period end", movement: "Movement" };
    (r.columns || []).forEach((c) => h.append(el("th", { class: "num" }, names[c] || c)));
    const thead = el("thead");
    thead.append(h);
    const tb = el("tbody");
    (r.rows || []).forEach((row) => {
      const tr = el("tr", { class: row.total ? "total" : "" });
      const lab = el("td", {}, row.label + " ");
      if (row.code) { lab.append(el("span", { class: "rg-note mono" }, row.code)); }
      tr.append(lab);
      (r.columns || []).forEach((c) => {
        const cell = (row.cells || {})[c] || {};
        let text;
        if (cell.value == null) { text = cell.text || "—"; } else if (c === "movement") { text = (cell.value >= 0 ? "+" : "") + (cell.value * 100).toFixed(1) + "%" + (cell.flag ? " ⚑" : ""); } else { text = fmt(thousands ? cell.value / 1000 : cell.value); }
        const td = el("td", { class: "num" + (cell.value < 0 ? " neg" : "") + (cell.flag ? " flag" : ""), "data-cell": "", tabindex: "0" }, text);
        td.addEventListener("click", () => lineage(td, row, c, cell, text));
        td.addEventListener("keydown", (e) => { if (e.key === "Enter") { lineage(td, row, c, cell, text); } });
        tr.append(td);
      });
      tb.append(tr);
    });
    t.append(thead, tb);
    return t;
  }

  function lineage(td, row, col, cell, text) {
    root.querySelectorAll("td[data-cell].sel").forEach((x) => x.classList.remove("sel"));
    td.classList.add("sel");
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
    $("[data-rp-approve]").textContent = next ? "Approve as " + (titles[next] || next) : "Signed";
    $("[data-rp-approve]").disabled = !next;
    $("[data-rp-return]").disabled = !next || next === "maker";
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
    const tb = $("[data-rp-checks]");
    tb.replaceChildren();
    (run.checks || []).forEach((c) => {
      const tr = el("tr");
      const p = el("td");
      p.append(el("span", { class: "rg-pill " + c.status }, c.status));
      tr.append(p, el("td", {}, c.title || c.check), el("td", { class: "rg-note" }, c.evidence || ""));
      tb.append(tr);
    });
  }

  async function act(action, extra) {
    const body = Object.assign({ version: run.version, action: action, comment: $("[data-rp-comment]").value }, extra || {});
    const { ok, j } = await getJson("/api/var/reports/" + run.report + "/runs/" + encodeURIComponent(run.period) + "/signoff", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (!ok) { notice(j.detail); return; }
    notice("");
    $("[data-rp-comment]").value = "";
    run = j;
    draw();
    loadPack();
  }
  $("[data-rp-approve]").addEventListener("click", () => act("approve", { step: (run.signoff || {}).next }));
  $("[data-rp-return]").addEventListener("click", () => act("return"));
  $("[data-rp-save-narrative]").addEventListener("click", () => act("narrative", { narrative: $("[data-rp-narrative]").value }));

  loadPack().then(loadRun);
})();
