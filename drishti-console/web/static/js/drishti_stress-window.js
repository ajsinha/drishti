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
/* The stress window selection (docs/guides/VAR_GUIDE.md section 18.9; terminal/stress_window.html): fills the shell from
   /api/var/stress-window (the server's analysis of a node) and /api/var/stress-window/proposals. The chart is drawn as SVG here (no
   library); the node chips, hierarchy and window length are kept in the address; a click on the chart or a candidate selects the
   window Freeze and propose sends. */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
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
  function svg(tag, attrs) {
    var e = document.createElementNS("http://www.w3.org/2000/svg", tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }
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

  function chart(d) {
    var box = $("[data-sw-chart]"); box.textContent = "";
    var xs = d.series.starts, ys = d.series["var"], n = ys.length;
    if (!n) return;
    var W = Math.max(600, box.clientWidth || 1000), H = box.clientHeight || 340, L = 56, R = 16, T = 22, B = 26;
    var max = Math.max.apply(null, ys), min = Math.min.apply(null, ys);
    if (d.today) { max = Math.max(max, d.today["var"]); min = Math.min(min, d.today["var"]); }
    var pad = (max - min) * 0.08 || 1; max += pad; min = Math.max(0, min - pad);
    var x = function (i) { return L + (W - L - R) * i / Math.max(1, n - 1); };
    var y = function (v) { return T + (H - T - B) * (1 - (v - min) / (max - min)); };
    var s = svg("svg", { viewBox: "0 0 " + W + " " + H, preserveAspectRatio: "none" });
    for (var k = 0; k <= 4; k++) {
      var v = min + (max - min) * k / 4;
      s.appendChild(svg("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), "class": "axis" }));
      var t = svg("text", { x: L - 6, y: y(v) + 4, "text-anchor": "end", "class": "tick" }); t.textContent = money(v); s.appendChild(t);
    }
    var lastYear = "";
    xs.forEach(function (day, i) {
      var yr = day.slice(0, 4);
      if (yr !== lastYear && day.slice(5, 7) === "01") {
        lastYear = yr;
        var tt = svg("text", { x: x(i), y: H - 8, "text-anchor": "middle", "class": "tick" }); tt.textContent = yr; s.appendChild(tt);
      }
    });
    var path = "", step = Math.max(1, Math.floor(n / 2000));
    for (var i = 0; i < n; i += step) path += (i ? "L" : "M") + x(i).toFixed(1) + "," + y(ys[i]).toFixed(1);
    path += "L" + x(n - 1).toFixed(1) + "," + y(ys[n - 1]).toFixed(1);
    s.appendChild(svg("path", { d: path + "L" + x(n - 1) + "," + y(min) + "L" + x(0) + "," + y(min) + "Z", "class": "area" }));
    s.appendChild(svg("path", { d: path, "class": "line" }));
    if (d.today) {
      s.appendChild(svg("line", { x1: L, x2: W - R, y1: y(d.today["var"]), y2: y(d.today["var"]), "class": "today" }));
      var tl = svg("text", { x: W - R, y: y(d.today["var"]) - 4, "text-anchor": "end", "class": "tick" });
      tl.textContent = "today's VaR " + money(d.today["var"]) + " (the last " + d.today.days + " days)"; s.appendChild(tl);
    }
    var at = function (first) { return xs.indexOf(first); };
    (d.candidates || []).forEach(function (c) {
      var i = at(c.first); if (i < 0) return;
      var cls = c.worst ? "worst" : c.current ? "current" : "";
      s.appendChild(svg("circle", { cx: x(i), cy: y(c["var"]), r: c.worst || c.current ? 5 : 3.5, "class": "mark-" + (cls || "cand") }));
      // the worst above its point, the current and the other candidates below theirs (close windows do not print over each other)
      var below = c.worst ? -6 : c.current ? 16 : 16 + 13 * ((c.rank || 0) % 2);
      var lab = svg("text", { x: x(i) + 7, y: y(c["var"]) + below, "class": "lab " + cls });
      lab.textContent = (c.worst ? "worst " : c.current ? "current " : "#" + c.rank + " ") + c.first + " · " + money(c["var"]);
      s.appendChild(lab);
    });
    if (S.selected && at(S.selected) >= 0) {
      var si = at(S.selected);
      s.appendChild(svg("line", { x1: x(si), x2: x(si), y1: T, y2: H - B, "class": "mark-sel" }));
    }
    s.addEventListener("click", function (ev) {
      var r = s.getBoundingClientRect(), px = (ev.clientX - r.left) * W / r.width;
      var i = Math.round((px - L) / (W - L - R) * (n - 1));
      if (i >= 0 && i < n) { S.selected = xs[i]; chart(d); compare(d); candidates(d); }
    });
    s.addEventListener("mousemove", function (ev) {
      var r = s.getBoundingClientRect(), px = (ev.clientX - r.left) * W / r.width;
      var i = Math.round((px - L) / (W - L - R) * (n - 1));
      if (i >= 0 && i < n) box.title = "window from " + xs[i] + ": stressed VaR " + whole(ys[i]);
    });
    box.appendChild(s);
  }

  function candidates(d) {
    var tb = $("[data-sw-cands] tbody"); tb.textContent = "";
    (d.candidates || []).forEach(function (c) {
      var tr = h("tr", c.first === S.selected ? "sel" : ""); tr.dataset.first = c.first;
      tr.appendChild(h("td", "", c.rank));
      tr.appendChild(h("td", "l", c.first + " to " + c.last));
      tr.appendChild(h("td", "mono", whole(c["var"])));
      tr.appendChild(h("td", "mono" + (c.vsCurrent > 0 ? " sw-pos" : ""), pct(c.vsCurrent)));
      var tag = h("td", "l");
      if (c.worst) tag.appendChild(h("span", "sw-tag worst", "worst"));
      if (c.current) tag.appendChild(h("span", "sw-tag current", "current"));
      tr.appendChild(tag);
      tr.appendChild(h("td", "l", c.worstDay + " · " + money(c.worstPnl)));
      tr.addEventListener("click", function () { S.selected = c.first; chart(d); compare(d); candidates(d); });
      tb.appendChild(tr);
    });
  }

  function byNode(d) {
    var tb = $("[data-sw-bynode] tbody"); tb.textContent = "";
    (d.byNode || []).forEach(function (m) {
      var tr = h("tr");
      var td = h("td", "l"), a = h("a", "", m.id.split("~").pop()); a.href = "#"; a.title = m.name;
      a.addEventListener("click", function (ev) { ev.preventDefault(); S.scope = m.id; load(); });
      td.appendChild(a); tr.appendChild(td);
      tr.appendChild(h("td", "mono", money(m.current)));
      tr.appendChild(h("td", "mono", money(m.worst)));
      tr.appendChild(h("td", "l mono", m.worstFirst || "—"));
      tr.appendChild(h("td", "mono" + (m.gap > 0 ? " sw-pos" : ""), pct(m.gap)));
      tb.appendChild(tr);
    });
  }

  function selectedWindow(d) {
    var i = d.series.starts.indexOf(S.selected);
    if (i < 0) return null;
    var c = (d.candidates || []).filter(function (x) { return x.first === S.selected; })[0];
    var v = d.series["var"][i];
    return { first: S.selected, last: c ? c.last : null, "var": v, var10: v * Math.sqrt(10), scenarios: d.length, worstDay: c ? c.worstDay : null };
  }

  function compare(d) {
    var tb = $("[data-sw-compare] tbody"); tb.textContent = "";
    var c = d.current || {}, p = selectedWindow(d) || {};
    var rows = [["Window", (c.first || "—") + (c.last ? " to " + c.last : ""), (p.first || "—") + (p.last ? " to " + p.last : "")],
                ["Stressed VaR 1-day", whole(c["var"]), whole(p["var"])], ["10-day √10", whole(c.var10), whole(p.var10)],
                ["Scenarios", c.scenarios || "—", p.scenarios || "—"], ["Worst day in it", c.worstDay || "—", p.worstDay || "—"],
                ["Approved", d.approval ? (d.approval.at || "").slice(0, 10) + " · " + d.approval.by : "—", "—"]];
    rows.forEach(function (r) {
      var tr = h("tr"); tr.appendChild(h("td", "l", r[0])); tr.appendChild(h("td", "mono", r[1])); tr.appendChild(h("td", "mono", r[2]));
      tb.appendChild(tr);
    });
  }

  function proposals() {
    var tb = $("[data-sw-proposals] tbody");
    get("stress-window/proposals?limit=20").then(function (b) {
      tb.textContent = "";
      (b.items || []).forEach(function (r) {
        var tr = h("tr");
        tr.appendChild(h("td", "l mono", r.id));
        tr.appendChild(h("td", "l", r.node ? r.node.id : ""));
        tr.appendChild(h("td", "l mono", r.proposed ? r.proposed.first + (r.proposed.last ? " to " + r.proposed.last : "") : ""));
        tr.appendChild(h("td", "mono", whole(r.proposed && r.proposed["var"])));
        var st = h("td", "l"); st.appendChild(h("span", "sw-tag " + r.status, r.status)); tr.appendChild(st);
        tr.appendChild(h("td", "l", r.proposedBy + " · " + (r.proposedAt || "").slice(0, 10)));
        tr.appendChild(h("td", "l", r.decidedBy ? r.decidedBy + " · " + (r.decidedAt || "").slice(0, 10) : "—"));
        var act = h("td", "l");
        if (r.status === "PROPOSED" && r.proposedBy !== root.dataset.user) {
          ["approve", "reject"].forEach(function (w) {
            var bt = h("button", "sw-btn small", w === "approve" ? "Approve" : "Reject"); bt.type = "button";
            bt.addEventListener("click", function () {
              var note = window.prompt((w === "approve" ? "Approve " : "Reject ") + r.id + ": a note for the record", "") ;
              if (note === null) return;
              post("stress-window/proposals/" + r.id + "/decision", { decision: w, note: note }).then(load).catch(function (e) { notice(e.message); });
            });
            act.appendChild(bt);
          });
        } else if (r.status === "PROPOSED") {
          act.textContent = "awaiting another person";
        }
        tr.appendChild(act);
        tb.appendChild(tr);
      });
      if (!(b.items || []).length) { var tr = h("tr"); var td = h("td", "l", "No proposal yet."); td.colSpan = 8; tr.appendChild(td); tb.appendChild(tr); }
    }).catch(function () { tb.textContent = ""; });
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
