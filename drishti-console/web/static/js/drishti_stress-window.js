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
/* The stress window selection (docs/guides/VAR_GUIDE.md section 18.9; terminal/stress_window.html): fills the shell from
   /api/var/stress-window (the server's analysis of a node) and /api/var/stress-window/proposals. The chart is a drishti-chart area (the server's stressed VaR for every window start, today's VaR,
   the candidates, the worst and the current marked, the selected window a line); the candidates, the nodes, current against proposed
   and the proposals are risk grids (<drishti-grid>, terminal layout). The node chips, hierarchy and window length are kept in the
   address; a click on the chart or a candidate selects the window Freeze and propose sends. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the risk grid preset and chart (drishti-risk)
  var root = document.querySelector("[data-swin]");
  if (!root) return;
  var S = { scope: root.dataset.scope || "BANK", hierarchy: root.dataset.hierarchy || "MGMT", length: root.dataset.length || "",
            data: null, selected: null };
  var $ = function (sel) { return root.querySelector(sel); };

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function tag(cls, text) { return h("span", "sw-tag " + cls, text); }
  function select(first) { S.selected = first; chart(S.data); compare(S.data); candidates(S.data); }
  var money = F.formatter("compact", { decimals: function (a, u) { return u === "k" ? 1 : !u ? 0 : 2; } });
  function whole(v) { return v === null || v === undefined || isNaN(v) ? "—" : Math.round(v).toLocaleString("en-US"); }
  function pct(v) { return v === null || v === undefined || isNaN(v) ? "—" : (v >= 0 ? "+" : "−") + Math.abs(v * 100).toFixed(1) + "%"; }
  function asOf() { var p = new URLSearchParams(location.search).get("asOf"); return p ? "&asOf=" + encodeURIComponent(p) : ""; }
  function answer(r) {
    return r.json().catch(function () { return {}; }).then(function (b) {
      if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; throw e; }
      return b;
    });
  }
  function get(path) { return fetch("/api/var/" + path, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(answer); }
  function post(path, body) {
    return fetch("/api/var/" + path, { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(body) }).then(answer);
  }
  function remember() {
    var q = new URLSearchParams(location.search);
    q.set("scope", S.scope); q.set("hierarchy", S.hierarchy);
    if (S.length) q.set("length", S.length); else q.delete("length");
    history.replaceState(null, "", location.pathname + "?" + q.toString());
  }
  function notice(text) { var n = $("[data-sw-notice]"); n.textContent = text || ""; n.hidden = !text; }

  function load() {
    remember();
    $("[data-sw-meta]").textContent = "loading…";
    var q = "stress-window?scope=" + encodeURIComponent(S.scope) + "&hierarchy=" + encodeURIComponent(S.hierarchy) +
      (S.length ? "&length=" + encodeURIComponent(S.length) : "") + asOf();
    get(q).then(function (d) { S.data = d; S.selected = d.worst ? d.worst.first : null; draw(); notice(""); })
      .catch(function (e) { $("[data-sw-meta]").textContent = ""; notice(e.message); });
    proposals();
  }

  function draw() {
    var d = S.data, hist = d.history;
    $("[data-sw-meta]").textContent = "which " + d.length + "-day period is worst for today's portfolio · positions of " + d.date +
      " · node-level P&L history " + hist.first + " to " + hist.last + (hist.synthetic ? " (synthetic, demo)" : "") + " · rolling " +
      d.length + "-day VaR " + Math.round(d.confidence * 1000) / 10 + "% 1-day for every window start (the " + ordinal(d.rank) + " worst day)";
    chips(d);
    var hs = $("[data-sw-hierarchy]"); hs.textContent = "";
    (d.hierarchies || [S.hierarchy]).forEach(function (x) { var o = h("option", "", x); o.value = x; o.selected = x === d.node.hierarchy; hs.appendChild(o); });
    var ls = $("[data-sw-length]");
    if (![].some.call(ls.options, function (o) { return +o.value === d.length; })) { var o = h("option", "", d.length + " days"); o.value = d.length; ls.appendChild(o); }
    ls.value = String(d.length);
    alert(d);
    chart(d);
    candidates(d);
    byNode(d);
    compare(d);
    $("[data-sw-chart-title]").textContent = d.node.name + " · stressed VaR of today's portfolio for every " + d.length + "-day window";
  }
  function ordinal(n) { return n + (n % 10 === 1 && n % 100 !== 11 ? "st" : n % 10 === 2 && n % 100 !== 12 ? "nd" : n % 10 === 3 && n % 100 !== 13 ? "rd" : "th"); }

  function chips(d) {
    var box = $("[data-sw-nodes]"); box.textContent = "";
    (d.nodes || []).forEach(function (n) {
      var b = h("button", "sw-chip", n.id.split("~").pop()); b.type = "button"; b.title = n.name + " (" + n.level + ")";
      b.setAttribute("aria-pressed", n.id === d.node.id ? "true" : "false");
      b.addEventListener("click", function () { S.scope = n.id; load(); });
      box.appendChild(b);
    });
  }

  function alert(d) {
    var a = $("[data-sw-alert]"), al = d.alert || {};
    a.textContent = "";
    if (!d.current) { a.hidden = true; notice(d.currentNote || ""); return; }
    a.hidden = false;
    var lead = h("b", "lead", al.raised ? "Alert: " : "No alert: ");
    a.appendChild(lead);
    a.appendChild(document.createTextNode(al.raised
      ? "the window starting " + d.worst.first + " gives a stressed VaR " + pct(al.gap) + " against the current window (" + d.current.first +
        " to " + d.current.last + ") for today's portfolio; the threshold is " + Math.round(al.threshold * 1000) / 10 + "% (" + d.alertThresholdSetting + ")."
      : "the current window (" + d.current.first + " to " + d.current.last + ") is within " + Math.round(al.threshold * 1000) / 10 +
        "% of the worst (" + pct(al.gap) + ")."));
  }

  /** The server's stressed VaR for every window start as an area; today's VaR a line; the candidates, the worst and the current points;
   *  the selected window a line across. A click on a point selects its window. */
  function chart(d) {
    var box = $("[data-sw-chart]");
    var xs = d.series.starts, ys = d.series["var"];
    if (!ys.length) { box.textContent = ""; return; }
    var rows = xs.map(function (s, i) { return { start: s, "var": ys[i] }; });
    var notes = [];
    if (d.today) { notes.push({ type: "line", axis: "y", value: d.today["var"], tone: "muted", label: "today's VaR " + money(d.today["var"]) + " (the last " + d.today.days + " days)", showValue: false }); }
    (d.candidates || []).forEach(function (c) {
      if (xs.indexOf(c.first) < 0) return;
      notes.push({ type: "point", x: c.first, y: c["var"], tone: c.worst ? "bad" : c.current ? "warn" : "ink",
        label: (c.worst ? "worst " : c.current ? "current " : "#" + c.rank + " ") + c.first + " · " + money(c["var"]) });
    });
    if (S.selected && xs.indexOf(S.selected) >= 0) { notes.push({ type: "line", axis: "x", value: S.selected, tone: "accent", style: "solid", label: "selected", showValue: false }); }
    R.riskChart(box, { kind: "area", data: { rows: rows }, id: "start", x: { field: "start", type: "category" }, y: { format: "compact", min: 0 },
      series: [{ field: "var", name: "stressed VaR, " + d.length + "-day window", tone: "link" }], annotations: notes, legend: "none", zoom: true },
      { height: 320, onPick: function (row) { if (row && row.start) select(row.start); } });
  }

  function candidates(d) {
    var rows = (d.candidates || []).map(function (c) { return Object.assign({}, c, { window: c.first + " to " + c.last, worstIn: c.worstDay + " · " + money(c.worstPnl) }); });
    R.riskGrid($("[data-sw-cands]"), { title: "Top candidates", rows: rows, getRowId: function (c) { return c.first; },
      getRowClass: function (c) { return c.first === S.selected ? "sw-sel" : null; }, getRowData: function (c) { return { first: c.first }; },
      onRow: function (c) { select(c.first); },
      columns: [{ field: "rank", header: "#", type: "number", width: 50 }, { field: "window", header: "Window", width: 200 },
        R.figureColumn("var", "Stressed VaR", { format: whole, width: 130 }),
        R.figureColumn("vsCurrent", "Against current", { format: pct, width: 120, tone: function (v) { return v > 0 ? "warn" : null; } }),
        { id: "tags", field: "first", header: "", width: 140, sortable: false, cellRenderer: function (x) {
          var s = h("span"); if (x.row.worst) s.appendChild(tag("worst", "worst")); if (x.row.current) s.appendChild(tag("current", "current")); return s; } },
        { field: "worstIn", header: "Worst day in it", width: 200 }] });
  }

  function byNode(d) {
    var rows = (d.byNode || []).map(function (m) { return Object.assign({}, m, { short: m.id.split("~").pop() }); });
    R.riskGrid($("[data-sw-bynode]"), { title: "By node", rows: rows, getRowId: function (m) { return m.id; },
      columns: [{ field: "short", header: "Node", width: 130, cellRenderer: function (x) {
          var a = h("a", "", x.value); a.href = "#"; a.title = x.row.name;
          a.addEventListener("click", function (ev) { ev.preventDefault(); S.scope = x.row.id; load(); });
          return a; } },
        R.figureColumn("current", "Current window", { format: money }), R.figureColumn("worst", "Its worst window", { format: money }),
        { field: "worstFirst", header: "Worst starts", width: 110, format: function (v) { return v || "—"; } },
        R.figureColumn("gap", "Gap", { format: pct, width: 90, tone: function (v) { return v > 0 ? "warn" : null; } })] });
  }

  function selectedWindow(d) {
    var i = d.series.starts.indexOf(S.selected);
    if (i < 0) return null;
    var c = (d.candidates || []).filter(function (x) { return x.first === S.selected; })[0];
    var v = d.series["var"][i];
    return { first: S.selected, last: c ? c.last : null, "var": v, var10: v * Math.sqrt(10), scenarios: d.length, worstDay: c ? c.worstDay : null };
  }

  function compare(d) {
    var c = d.current || {}, p = selectedWindow(d) || {};
    var rows = [["Window", (c.first || "—") + (c.last ? " to " + c.last : ""), (p.first || "—") + (p.last ? " to " + p.last : "")],
                ["Stressed VaR 1-day", whole(c["var"]), whole(p["var"])], ["10-day √10", whole(c.var10), whole(p.var10)],
                ["Scenarios", c.scenarios || "—", p.scenarios || "—"], ["Worst day in it", c.worstDay || "—", p.worstDay || "—"],
                ["Approved", d.approval ? (d.approval.at || "").slice(0, 10) + " · " + d.approval.by : "—", "—"]];
    R.riskGrid($("[data-sw-compare]"), { title: "Current against proposed", rows: rows.map(function (r) { return { what: r[0], current: String(r[1]), proposed: String(r[2]) }; }),
      getRowId: function (r) { return r.what; },
      columns: [{ field: "what", header: "", width: 150, sortable: false }, { field: "current", header: "Current", width: 190, sortable: false, cellClass: function () { return "dg-num"; } },
        { field: "proposed", header: "Proposed", width: 190, sortable: false, cellClass: function () { return "dg-num"; } }] });
  }

  function proposals() {
    var box = $("[data-sw-proposals]");
    get("stress-window/proposals?limit=20").then(function (b) {
      var rows = (b.items || []).map(function (r) {
        return { id: r.id, node: r.node ? r.node.id : "", window: r.proposed ? r.proposed.first + (r.proposed.last ? " to " + r.proposed.last : "") : "",
          "var": r.proposed && r.proposed["var"], status: r.status, by: r.proposedBy + " · " + (r.proposedAt || "").slice(0, 10),
          decided: r.decidedBy ? r.decidedBy + " · " + (r.decidedAt || "").slice(0, 10) : "—", mine: r.proposedBy === root.dataset.user };
      });
      if (!rows.length) { box.textContent = ""; box.appendChild(h("p", "sw-note", "No proposal yet.")); return; }
      R.riskGrid(box, { title: "Proposals", rows: rows, getRowId: function (r) { return r.id; },
        columns: [{ field: "id", header: "Id", width: 150 }, { field: "node", header: "Node", width: 130 }, { field: "window", header: "Proposed window", width: 190 },
          R.figureColumn("var", "Stressed VaR", { format: whole, width: 120 }),
          { field: "status", header: "Status", width: 110, cellRenderer: function (x) { return tag(x.value, x.value); } },
          { field: "by", header: "Proposed by", width: 160 }, { field: "decided", header: "Decided by", width: 160 },
          { id: "act", field: "status", header: "", width: 170, sortable: false, cellRenderer: function (x) {
            if (x.row.status !== "PROPOSED") return "";
            if (x.row.mine) return "awaiting another person";
            var go = h("a", "sw-btn small", "Decide in Approvals"); go.href = "/approvals/stress-window/" + encodeURIComponent(x.row.id);
            go.setAttribute("data-sw-approvals", x.row.id);
            return go; } }] });
    }).catch(function () { box.textContent = ""; });
  }

  $("[data-sw-hierarchy]").addEventListener("change", function (e) { S.hierarchy = e.target.value; S.scope = "BANK"; load(); });
  $("[data-sw-length]").addEventListener("change", function (e) { S.length = e.target.value; load(); });
  $("[data-sw-export]").addEventListener("click", function () {
    var d = S.data; if (!d) return;
    var lines = ["window_first_day,stressed_var_usd"];
    d.series.starts.forEach(function (s, i) { lines.push(s + "," + d.series["var"][i].toFixed(2)); });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([lines.join("\n") + "\n"], { type: "text/csv" }));
    a.download = "stress-windows_" + d.node.id.replace("~", "_") + "_" + d.date + "_" + d.length + "d.csv";
    document.body.appendChild(a); a.click(); a.remove();
  });
  $("[data-sw-propose]").addEventListener("click", function () {
    var d = S.data; if (!d || !S.selected) return;
    var note = window.prompt("Freeze this analysis and propose the window starting " + S.selected + " for " + d.node.id + ": a note", "");
    if (note === null) return;
    post("stress-window/proposals", { scope: d.node.id, hierarchy: d.node.hierarchy, date: d.date, length: d.length, first: S.selected, note: note })
      .then(function (r) { notice("Proposed as " + r.id + ": another person approves or rejects it."); proposals(); })
      .catch(function (e) { notice(e.message); });
  });
  load();
})();
