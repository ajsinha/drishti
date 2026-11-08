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
/* Drishti Risk's Home (home/risk_landing.html; LENSES.md › The design): fills the landing's panels from the engines' answers, through the
   console's own /api/var/... and /api/exposure/...: the BANK headline (VaR, ES), its backtest (scope/history), the exposure headline (EPE,
   peak PFE), the hierarchy tree (VaR, limit use per node), the exposure tree (EPE and credit-limit use per counterparty), the intraday streams
   and the official records. Each panel is painted when its answer arrives; one refused or empty says so in place and the others carry on.
   Nothing is invented: a figure the engines do not serve is left out. VaR by desk and exposure by counterparty are risk grids (drishti-risk's
   preset: <drishti-grid>, terminal layout). Widths are set through the CSSOM (the page's policy allows no inline style). */
(function () {
  "use strict";
  var F = window.Drishti.elements.core;                                       // numbers by the server's rules (drishti-core)
  var R = window.Drishti.elements.risk;                                       // the risk grid preset (drishti-risk)
  var root = document.querySelector("[data-risk-landing]");
  if (!root) return;
  var landing = root.getAttribute("data-risk-landing");
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
  var money = F.formatter("compact", { decimals: function (a, u) { return u === "k" ? 1 : !u ? 0 : 2; } });
  function pct(u) { return u === null || u === undefined || isNaN(u) ? "—" : Math.round(u * 100) + "%"; }
  function tone(u) { return u === null || u === undefined || isNaN(u) ? "" : u >= 1 ? "r" : u >= 0.8 ? "w" : "ok"; }
  function text(sel, v) { $$(sel).forEach(function (el) { el.textContent = v; }); }
  function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt !== undefined) e.textContent = txt; return e; }
  function said(target, err) {
    if (!target) return;
    target.textContent = "";
    target.appendChild(el(target.tagName === "UL" ? "li" : "p", "rl-muted", "Not available: " + err.message));
  }
  function useBar(u) {
    var wrap = el("span", "rl-use");
    var track = el("span", "rl-track"), fill = el("span", "rl-fill " + tone(u));
    fill.style.width = Math.max(0, Math.min(1, u || 0)) * 100 + "%";
    track.appendChild(fill);
    wrap.appendChild(track);
    wrap.appendChild(el("span", "rl-pill " + tone(u), pct(u)));
    return wrap;
  }
  function link(href, label) { var a = el("a", "", label); a.href = href; return a; }
  /** A short ranked table (desks, counterparties): a risk grid of name (a link), the figure (the server's) and the limit use as a bar. */
  function ranked(box, rows, o) {
    if (!rows.length) { box.textContent = ""; box.appendChild(el("p", "rl-muted", o.empty)); return; }
    R.riskGrid(box, { title: o.title, rows: rows, getRowId: function (r) { return r.id; }, fitRows: 8, getRowData: function (r) { return { scope: r.scope }; },
      columns: [{ field: "name", header: o.name, width: 200, cellRenderer: function (x) { return link(x.row.href, x.value); } },
        R.figureColumn("figure", o.figure, { format: money, width: 100 }),
        R.figureColumn("use", "Limit use", { format: pct, width: 170, cellRenderer: function (x) {
          return x.value === null || x.value === undefined ? el("span", "rl-muted", "no limit") : useBar(x.value); } })] });
  }

  var breaches = { var: null, credit: null };
  function paintBreaches() {
    if (breaches.var === null && breaches.credit === null) return;
    var n = (breaches.var || 0) + (breaches.credit || 0);
    text("[data-rl-breaches]", String(n));
    text("[data-rl-breaches-s]", (breaches.var === null ? "VaR —" : breaches.var + " VaR") + " · " + (breaches.credit === null ? "credit —" : breaches.credit + " credit"));
    var t = $("[data-rl-tile=breaches]");
    if (t) t.classList.toggle("bad", n > 0);
  }

  // the BANK VaR headline and the page's business date
  get("/api/var/scope?scope=BANK").then(function (h) {
    text("[data-rl-var]", money(h.var));
    text("[data-rl-var-s]", "ES " + money(h.es) + " · " + Math.round((h.confidence || 0) * 100) + "% · 1 day");
    text("[data-rl-date]", h.businessDate || "—");
    text("[data-rl-ccy]", h.currency || "USD");
    if (h.intraday) text("[data-rl-intraday]", " · intraday: " + (typeof h.intraday === "string" ? h.intraday : (h.intraday.note || "applied")));
  }).catch(function (e) { text("[data-rl-var]", "—"); text("[data-rl-var-s]", e.message); });

  // its backtest: the light and the exceptions (scope/history)
  if ($("[data-rl-backtest]")) {
    get("/api/var/scope/history?scope=BANK").then(function (h) {
      var b = h.backtest || {};
      if (b.light === undefined) { text("[data-rl-backtest]", "—"); return; }
      text("[data-rl-backtest]", String(b.light).toLowerCase() + " · " + b.exceptions);
      text("[data-rl-backtest-s]", "exceptions in " + b.days + " days" + (b.kupiecPValue !== undefined && b.kupiecPValue !== null ? "; Kupiec p " + Number(b.kupiecPValue).toFixed(2) : ""));
      var t = $("[data-rl-tile=backtest]");
      if (t) t.classList.toggle("warn", String(b.light).toLowerCase() !== "green");
    }).catch(function (e) { text("[data-rl-backtest]", "—"); text("[data-rl-backtest-s]", e.message); });
  }

  // the bank's exposure headline
  get("/api/exposure/scope?scope=BANK").then(function (h) {
    text("[data-rl-epe]", money(h.epe));
    text("[data-rl-epe-s]", "PFE peak " + money(h.peakPfe));
  }).catch(function (e) { text("[data-rl-epe]", "—"); text("[data-rl-epe-s]", e.message); });

  // the exposure tree: counterparties with EPE and credit-limit use (A's table, the counts, the credit breaches)
  function counterparties(tree) {
    var out = [], sets = 0;
    (function walk(nodes) {
      (nodes || []).forEach(function (n) {
        if (typeof n.scope === "string" && n.scope.indexOf("CPTY:") === 0) {
          out.push(n);
          sets += (n.children || []).length;
        } else walk(n.children);
      });
    })(tree.groups || tree.children || tree.nodes || []);
    return { cptys: out, sets: sets };
  }
  get("/api/exposure/tree").then(function (t) {
    var c = counterparties(t);
    text("[data-rl-cpty]", String(c.cptys.length));
    text("[data-rl-cpty-s]", c.sets + " netting sets");
    breaches.credit = c.cptys.filter(function (n) { return n.limit && n.limit.use >= 1; }).length;
    paintBreaches();
    var body = $("[data-rl-cptys]");
    if (!body) return;
    var rows = c.cptys.filter(function (n) { return n.epe !== undefined; })
      .sort(function (a, b) { return ((b.limit && b.limit.use) || 0) - ((a.limit && a.limit.use) || 0) || (b.epe || 0) - (a.epe || 0); }).slice(0, 8)
      .map(function (n) { return { id: n.scope, scope: n.scope, name: n.label || n.id, href: "/exposure?scope=" + encodeURIComponent(n.scope), figure: n.epe, use: n.limit ? n.limit.use : null }; });
    ranked(body, rows, { title: "Exposure by counterparty", name: "Counterparty", figure: "EPE", empty: "No counterparty exposure on this date" });
  }).catch(function (e) { text("[data-rl-cpty]", "—"); breaches.credit = null; said($("[data-rl-cptys]"), e); });

  // the hierarchy tree: desks (A), the tree and the heat map (C), the VaR breaches
  var hierarchy = root.getAttribute("data-hierarchy") || "MGMT";
  function paintTree(t) {
    var nodes = t.nodes || [];
    breaches.var = nodes.filter(function (n) { return n.limitUse !== null && n.limitUse !== undefined && n.limitUse >= 1; }).length;
    paintBreaches();
    var desks = $("[data-rl-desks]");
    if (desks) {
      var leafLevel = nodes.filter(function (n) { return String(n.level || "").toUpperCase() === "DESK"; });
      var list = (leafLevel.length ? leafLevel : nodes.filter(function (n) { return n.depth === 2; }))
        .sort(function (a, b) { return (b.limitUse || 0) - (a.limitUse || 0) || (b.var || 0) - (a.var || 0); }).slice(0, 8)
        .map(function (n) { return { id: n.scope, scope: n.scope, name: n.name || n.id, href: "/var?scope=" + encodeURIComponent(n.scope) + "&hierarchy=" + hierarchy, figure: n.var,
          use: n.limitUse === undefined ? null : n.limitUse }; });
      ranked(desks, list, { title: "VaR by desk", name: "Desk", figure: "VaR", empty: "No desks in " + hierarchy });
    }
    var tree = $("[data-rl-tree]");
    if (tree) {
      text("[data-rl-tree-title]", t.title || hierarchy);
      paintHierarchy(tree, nodes);
      var hs = $("[data-rl-hierarchies]");
      if (hs) {
        hs.textContent = "";
        (t.hierarchies || [hierarchy]).forEach(function (h) {
          var b = el("button", h === hierarchy ? "on" : "", h);
          b.type = "button";
          b.setAttribute("aria-pressed", h === hierarchy ? "true" : "false");
          b.addEventListener("click", function () { hierarchy = h; loadTree(); });
          hs.appendChild(b);
        });
      }
    }
    var heat = $("[data-rl-heat]");
    if (heat) {
      heat.textContent = "";
      var cells = nodes.filter(function (n) { return n.limitUse !== null && n.limitUse !== undefined; })
        .sort(function (a, b) { return b.limitUse - a.limitUse; }).slice(0, 16);
      if (!cells.length) heat.appendChild(el("p", "rl-muted", "No limits set in " + hierarchy));
      cells.forEach(function (n) {
        var a = link("/var?scope=" + encodeURIComponent(n.scope) + "&hierarchy=" + hierarchy, "");
        a.className = "rl-cell " + tone(n.limitUse);
        a.appendChild(el("b", "", n.name || n.id));
        a.appendChild(el("span", "", "VaR " + pct(n.limitUse)));
        heat.appendChild(a);
      });
    }
  }
  /** The hierarchy as drishti-tree (drishti-risk's VaR hierarchy preset, its tree grid inside this panel): the server's VaR and limit
      use of every node, never summed here; open to the desks; a node's name a link to the VaR explorer at that scope. */
  function paintHierarchy(box, nodes) {
    var rows = nodes.map(function (n) {
      return { ID: n.id, PARENT: n.parent || null, LABEL: n.name || n.id, LEVEL: n.level, scope: n.scope, VAR: n["var"], LIMIT_USE: n.limitUse,
        LIMIT_NOTE: n.limitComparable === false ? "n/c" : "no limit" };
    });
    var opts = R.varHierarchyTree(rows, { title: "Node", label: "VaR hierarchy " + hierarchy, chrome: false, openDepth: 2, fields: ["VAR", "LIMIT_USE"],
      measures: { VAR: { label: "VaR", childSum: false, column: { width: 84 } }, LIMIT_USE: { label: "Limit use", column: { width: 120 } } },
      grid: { fitRows: 14, phoneWidth: 1, labelColumn: { header: hierarchy, width: 190, cellRenderer: function (c) {
        return link("/var?scope=" + encodeURIComponent(c.row.scope) + "&hierarchy=" + hierarchy, c.row.LABEL);
      } }, rowData: function (r) { return { scope: r.scope || null }; } } });
    var host = box.querySelector(":scope > drishti-tree");
    if (!host) { box.textContent = ""; host = document.createElement("drishti-tree"); box.appendChild(host); }
    host.options = opts;
  }
  function loadTree() {
    get("/api/var/tree?hierarchy=" + encodeURIComponent(hierarchy)).then(paintTree).catch(function (e) {
      breaches.var = null;
      said($("[data-rl-desks]"), e); said($("[data-rl-tree]"), e); said($("[data-rl-heat]"), e);
    });
  }
  loadTree();

  // Today (C) and the meta line: the intraday streams and the official records
  var feed = $("[data-rl-feed]");
  var lines = [];
  function paintFeed() {
    if (!feed) return;
    feed.textContent = "";
    if (!lines.length) { feed.appendChild(el("li", "rl-muted", "Nothing yet today")); return; }
    lines.sort(function (a, b) { return String(b.at).localeCompare(String(a.at)); }).forEach(function (l) {
      var li = el("li");
      li.appendChild(el("time", "mono", l.at ? String(l.at).slice(11, 16) || String(l.at) : ""));
      li.appendChild(el("span", "", l.text));
      feed.appendChild(li);
    });
  }
  Promise.all([
    get("/api/var/intraday/streams").then(function (s) {           // the PV topics' streams: their state and last message
      (Array.isArray(s) ? s : s.streams || []).forEach(function (st) {
        if (!st) return;
        lines.push({ at: st.lastMessage || st.windowOpened || "", text: "Intraday " + (st.connector || st.topic || "stream") + ": " + (st.state || "running")
          + (st.waiting ? ", " + st.waiting + " waiting" : "") });
      });
    }).catch(function () {}),
    get("/api/var/official").then(function (o) {                   // the date's official record, when there is one
      var d = o.date || o.businessDate || ((o.dates || [])[0] || {}).date || (o.dates || [])[0];
      if (d) {
        lines.push({ at: o.capturedAt || o.computedAt || "", text: "VaR official record captured for " + d });
        text("[data-rl-official]", " · VaR official record " + d);
      }
    }).catch(function () {}),
    get("/api/exposure/official").then(function (o) {
      var d = o.date || o.businessDate || ((o.dates || [])[0] || {}).date || (o.dates || [])[0];
      if (d) lines.push({ at: o.capturedAt || o.computedAt || "", text: "Exposure official record captured for " + d });
    }).catch(function () {})
  ]).then(function () {
    breaches.var !== null && breaches.var > 0 && lines.push({ at: "", text: breaches.var + " VaR limit(s) at or over 100% in " + hierarchy });
    paintFeed();
  });

  // B: the person's saved VaR baskets
  var baskets = $("[data-rl-baskets]");
  if (baskets) {
    get("/api/var/baskets").then(function (b) {
      baskets.textContent = "";
      var list = (b.baskets || b || []).slice(0, 6);
      if (!list.length) baskets.appendChild(el("li", "rl-muted", "No baskets yet: build one on the VaR page"));
      list.forEach(function (x) {
        var li = el("li");
        li.appendChild(link("/var?scope=" + encodeURIComponent("BASKET:" + (x.owner || "") + "/" + (x.name || "")), "★ " + (x.name || "")));
        baskets.appendChild(li);
      });
    }).catch(function (e) { said(baskets, e); });
  }
})();
