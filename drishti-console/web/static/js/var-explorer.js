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
/* The VaR explorer (docs/guides/VAR_GUIDE.md section 3). One page for any scope: a click on a tree row, a
   breadcrumb, a contribution row or a command moves every panel to the new scope in place (no page load). Each panel says when it is
   computing and is painted as soon as its answer arrives; answers are kept per scope and business date, and the children of the
   selected node are fetched ahead while the user reads, so drilling down is instant. The time from a click to the painted panels is
   shown in the status line and kept on the root element (data-paint-ms) for the tests.

   Ad-hoc scopes: "+ New scope" opens the builder: ids pasted, a rule, rows ticked and "Add to basket", unions and differences with
   any scope; the VaR follows every change; Save keeps it as a basket (private, shared read-only on request). "Capture VaR" stores an
   immutable snapshot that reopens exactly, compares with another capture or with now, and exports as CSV. */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  if (!root || !window.VarCharts) return;
  var C = window.VarCharts;
  var $ = function (sel) { return root.querySelector(sel); };
  var params = new URLSearchParams(location.search);
  var S = { scope: root.dataset.scope || "BANK", hierarchy: root.dataset.hierarchy || "MGMT", tab: "path", by: "child", page: 0,
            asOf: params.get("asOf") || "", tree: null, expanded: {}, children: {}, picked: {}, head: null, seq: 0, user: root.dataset.user || "" };
  var cache = new Map();
  var retired = params.get("from") || "";     // an id of the retired stored VaR (VAR-COMM, VN-<node>): opened at its scope

  /** Says where a retired id was opened; when no node matches it (unknown), the bank is shown instead. True when it moved on. */
  function retiredNotice(scope, d) {
    if (!retired) return false;
    var n = $("[data-vx-notice]"), id = retired;
    retired = "";
    n.hidden = false;
    if (scope !== "BANK" && d.provenance && (d.provenance.unknown || []).length) {
      n.textContent = id + " was a stored VaR record, retired in 2.8.0; no node matches it, so the bank is shown.";
      select("BANK");
      return true;
    }
    n.textContent = id + " was a stored VaR record, retired in 2.8.0: " + words(scope) + " is computed from the trades now.";
    return false;
  }

  // ---------------------------------------------------------------------------------------------------------------- data
  function api(path, q, fresh) {
    var p = new URLSearchParams();
    Object.keys(q || {}).forEach(function (k) { if (q[k] !== undefined && q[k] !== null && q[k] !== "") p.set(k, q[k]); });
    if (S.asOf) p.set("asOf", S.asOf);
    var key = path + "?" + p.toString();
    if (!fresh && cache.has(key)) return cache.get(key);
    var pr = fetch("/api/var/" + path + "?" + p.toString(), { headers: { Accept: "application/json" }, credentials: "same-origin" })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (b) {
          if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; throw e; }
          return b;
        });
      });
    cache.set(key, pr);
    pr.catch(function () { cache.delete(key); });
    return pr;
  }

  function send(method, path, body) {
    return fetch("/api/var/" + path + (S.asOf ? "?asOf=" + encodeURIComponent(S.asOf) : ""), {
      method: method, credentials: "same-origin", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: body ? JSON.stringify(body) : undefined
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; throw e; }
        return b;
      });
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- helpers
  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function int(v) { return v === null || v === undefined || isNaN(v) ? "—" : Math.round(v).toLocaleString("en-US"); }
  function pct(v) { return v === null || v === undefined || isNaN(v) ? "—" : (v * 100).toFixed(1) + "%"; }
  function cell(tr, text, cls) { var td = h("td", cls, text); tr.appendChild(td); return td; }
  function utilClass(u) { return u >= 0.9 ? "bad" : u >= 0.75 ? "warn" : "ok"; }
  function ubar(u, wide) {
    var span = h("span", "vx-ubar" + (wide ? " wide" : ""));
    var i = h("i", utilClass(u));
    i.style.width = Math.max(0, Math.min(100, u * 100)) + "%";
    span.appendChild(i);
    span.title = (u * 100).toFixed(0) + "% of limit";
    return span;
  }
  function chip(dir) { return h("span", "vx-chip " + (dir === "LONG" ? "long" : dir === "SHORT" ? "short" : ""), dir || ""); }
  function fresh(which, text, cls) {
    var e = root.querySelector('[data-vx-fresh="' + which + '"]');
    if (e) { e.textContent = text; e.className = "vx-fresh" + (which === "main" ? " vx-tab-fresh" : "") + (cls ? " " + cls : ""); }
  }
  function errorBox(host, e) {
    host.textContent = "";
    var p = h("p", "vx-err", (e.status === 403 ? "Not for your role: " : "Could not compute: ") + e.message);
    p.setAttribute("role", "alert");
    host.appendChild(p);
  }
  /** The command that opens a scope: VAR DESK DESK-RATES, VAR LE LE-NY, VAR POS <id>, VAR SCOPE <basket>, VAR SCOPE <scope text>. */
  function words(scope) {
    var m = /^NODE:(MGMT|LEGAL)~(.*)$/.exec(scope);
    if (m) {
      var n = S.byId && S.byId[m[1] + "~" + m[2]];
      var level = n ? n.level : /^DESK-/.test(m[2]) ? "desk" : /^BOOK-/.test(m[2]) ? "book" : /^DIV-/.test(m[2]) ? "division"
        : /^LE-/.test(m[2]) ? "legal-entity" : "";
      if (m[2] === "BANK") return "VAR BANK";
      var word = { desk: "DESK", book: "BOOK", division: "DIV", "legal-entity": "LE" }[level];
      return word ? "VAR " + word + " " + m[2] : "VAR SCOPE " + scope;
    }
    if (/^BASKET:/.test(scope)) return "VAR SCOPE " + scope.slice(7).replace(new RegExp("^" + S.user + "/"), "");
    if (/^(DESK|BOOK|LE|DIV|POS|TRD|CPTY|PORT):[^ ]+$/.test(scope)) return "VAR " + scope.replace(":", " ");
    return scope === "BANK" ? "VAR BANK" : "VAR SCOPE " + scope;
  }

  // ---------------------------------------------------------------------------------------------------------------- selecting a scope
  function select(scope, opts) {
    opts = opts || {};
    S.scope = scope;
    S.page = 0;
    var seq = ++S.seq;
    var t0 = performance.now();
    root.dataset.scope = scope;
    delete root.dataset.painted;
    if (!opts.noPush) {
      var u = new URL(location.href);
      u.searchParams.set("scope", scope);
      u.searchParams.delete("capture");
      history.pushState({ scope: scope }, "", u.toString());
    }
    $("[data-vx-cmd]").textContent = words(scope);
    var topIn = document.querySelector("#cmd, input[name=q]");
    if (topIn && document.activeElement !== topIn) topIn.value = words(scope) + " <GO>";
    markTree();
    fresh("main", "computing…", "computing");
    computingTiles();
    var head = api("scope", { scope: scope }).then(function (d) {
      if (seq !== S.seq) return;
      S.head = d;
      if (retiredNotice(scope, d)) return;
      renderHeadline(d);
      return d;
    }).catch(function (e) { if (seq === S.seq) { errorBox($("[data-vx-tiles]"), e); fresh("main", "failed", "stale"); } });
    var tab = renderTab(seq);
    Promise.all([head, tab]).then(function () {
      if (seq !== S.seq) return;
      var ms = Math.round(performance.now() - t0);
      root.dataset.painted = scope;
      root.dataset.paintMs = String(ms);
      var pv = S.head && S.head.provenance;
      $("[data-vx-timing]").textContent = "painted in " + ms + " ms" + (pv ? " · server " + pv.computeMs + " ms, " + pv.cache : "");
      fresh("main", pv ? "aggregated " + int(pv.trades) + " vectors in " + pv.computeMs + " ms" : "");
      document.dispatchEvent(new CustomEvent("var:painted", { detail: { scope: scope, ms: ms } }));
      if (!S.tree && !S.treeLoading) loadTree();                  // the tree failed while the data was still coming up: the engine answers now
      prefetchChildren();
    });
  }

  function renderTab(seq) {
    if (S.tab === "path") return loadPath(seq);
    if (S.tab === "hist") return loadHistory(seq);
    if (S.tab === "contrib") return loadContrib(seq);
    return loadCaptures(seq);
  }

  /** The children of the selected node fetched ahead (headline and path), so a drill is answered from memory. */
  function prefetchChildren() {
    if (!S.tree) return;
    var node = (S.scope.indexOf("NODE:") === 0) ? S.scope.slice(5) : null;
    var kids = S.tree.nodes.filter(function (n) { return node && n.parent === node; }).slice(0, 12);
    var go = function () {
      kids.forEach(function (n) { api("scope", { scope: n.scope }); api("scope/path", { scope: n.scope, page: 0 }); });
    };
    (window.requestIdleCallback || function (f) { return setTimeout(f, 50); })(go);
  }

  // ---------------------------------------------------------------------------------------------------------------- headline
  function computingTiles() {
    var t = $("[data-vx-tiles]");
    if (!t.children.length || t.querySelector(".vx-err")) {
      t.textContent = "";
      ["VaR 99% · 1 day", "ES 97.5%", "Sum of standalone VaR", "Diversification", "Limit utilisation", "Backtest exceptions"].forEach(function (l) {
        var d = h("div", "vx-tile"); d.appendChild(h("div", "l", l)); d.appendChild(h("div", "v", "…")); d.appendChild(h("div", "s", "computing"));
        t.appendChild(d);
      });
    }
    t.classList.add("computing");
  }

  function renderHeadline(d) {
    $("[data-vx-date]").textContent = d.businessDate;
    $("[data-vx-set]").textContent = (d.provenance.scenarioSet || "").split(" · ")[0];
    $("[data-vx-version]").textContent = d.provenance.vectors.split("data version ")[1] || "";
    $("[data-vx-n]").textContent = "(" + d.scenarios + " scenarios)";
    var conf = Math.round(d.confidence * 1000) / 10, esc = Math.round(d.esConfidence * 1000) / 10;
    $("[data-vx-var-legend]").textContent = "VaR " + conf + "% (" + d.k + (d.k === 1 ? "st" : d.k === 2 ? "nd" : d.k === 3 ? "rd" : "th") + " worst)";
    $("[data-vx-es-legend]").textContent = "ES " + esc + "% (mean of worst " + d.esCount + ")";
    var bt = d.backtest || {}, u = d.limitUse;
    var tiles = [
      ["VaR " + conf + "% · 1 day", C.money(d["var"]), d.k + "th worst of " + d.scenarios, null],
      ["ES " + esc + "%", C.money(d.es), "mean of worst " + d.esCount, null],
      ["Sum of standalone VaR", d.sumStandalone === null ? "—" : C.money(d.sumStandalone), d.childKind ? d.children + " " + d.childKind + (d.children === 1 ? "" : "s") : (d.children ? d.children + " parts" : "no parts"), null],
      ["Diversification", d.diversification === null ? "—" : "−" + C.money(d.diversification),
        d.diversification === null ? "single part" : (d.diversification / d.sumStandalone * 100).toFixed(1) + "% of standalone", null],
      ["Limit utilisation", u === null || u === undefined ? "—" : (u * 100).toFixed(0) + "%", d.limit ? "limit " + C.money(d.limit) : "no limit set", u === null || u === undefined ? null : utilClass(u)],
      ["Backtest exceptions (" + (bt.days || 0) + " d)", String(bt.exceptions === undefined ? "—" : bt.exceptions), (bt.light || "") + " zone · Basel", "light-" + (bt.light || "")]
    ];
    var t = $("[data-vx-tiles]");
    t.textContent = "";
    t.classList.remove("computing");
    tiles.forEach(function (x, i) {
      var tile = h("div", "vx-tile");
      tile.dataset.tile = String(i);
      tile.appendChild(h("div", "l", x[0]));
      var v = h("div", "v mono" + (x[3] && i === 4 ? " " + x[3] : ""));
      if (i === 5) { v.appendChild(h("span", "vx-light " + (bt.light || ""))); }
      v.appendChild(document.createTextNode(x[1]));
      tile.appendChild(v);
      tile.appendChild(h("div", "s mono", x[2]));
      t.appendChild(tile);
    });
    renderCrumb(d);
    renderProv(d.provenance, d);
    document.title = "VaR · " + d.label + (document.body.dataset.product ? " · " + document.body.dataset.product : "");
  }

  function renderCrumb(d) {
    var c = $("[data-vx-crumb]");
    c.textContent = "";
    var parts = (d.ancestors || []).slice();
    if (d.kind !== "node" || !parts.length || parts[parts.length - 1].scope !== "NODE:" + d.node) parts.push({ label: d.label, scope: d.canonical });
    if (d.kind !== "node" && d.kind !== "position" && d.kind !== "trade") parts = [{ label: "Ad-hoc", scope: null }, { label: d.label, scope: d.canonical }];
    parts.forEach(function (p, i) {
      if (i) c.appendChild(h("span", "sep", "›"));
      if (i === parts.length - 1) c.appendChild(h("span", "cur", p.label));
      else if (p.scope) {
        var a = h("a", null, p.label);
        a.href = "?scope=" + encodeURIComponent(p.scope);
        a.addEventListener("click", function (ev) { ev.preventDefault(); select(p.scope); });
        c.appendChild(a);
      } else c.appendChild(h("span", null, p.label));
    });
    var chipText = { portfolio: "portfolio", basket: "ad-hoc · saved basket", rule: "ad-hoc · rule", adhoc: "ad-hoc · unsaved", capture: "capture's members" }[d.kind];
    if (chipText) c.appendChild(h("span", "vx-chip amber", chipText));
  }

  function renderProv(p, d) {
    var dl = $("[data-vx-prov]");
    dl.textContent = "";
    [["Scope", d.kind + " " + d.label], ["Resolved", (p.resolved || []).join("; ") || p.scope], ["Positions", int(p.positions)],
      ["Trades", int(p.trades) + (p.failed ? " · " + p.failed + " without a vector" : "")],
      ["Outside your view", p.hidden ? int(p.hidden) + " trades not included" : "none"],
      ["Unknown ids", (p.unknown || []).length ? p.unknown.join(", ") : "none"],
      ["Warnings", (p.warnings || []).length ? p.warnings.join("; ") : "none"],
      ["Rows left out", int(p.rejected || 0) + " rejected" + ((p.rejections || []).length ? " (" + p.rejections.slice(0, 3).join("; ") + ")" : "")
        + " · " + int(p.excluded || 0) + " not live"],
      ["Vectors", p.vectors], ["Scenario set", p.scenarioSet], ["Method", p.method], ["ES", p.es], ["Components", p.components],
      ["Positions by", p.positionRules || ""],
      ["Engine", p.engine], ["View", p.view], ["Members", p.membershipHash],
      ["Computed", p.computedAt.replace("T", " ").slice(0, 19) + " · " + p.computeMs + " ms · cache " + p.cache]].forEach(function (r) {
      dl.appendChild(h("dt", null, r[0]));
      dl.appendChild(h("dd", "mono", r[1]));
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- P&L path
  function loadPath(seq) {
    ["[data-vx-histo]", "[data-vx-tail]", "[data-vx-series]"].forEach(function (s) { var e = $(s); if (!e.children.length) e.textContent = "computing…"; });
    return api("scope/path", { scope: S.scope, page: 0 }).then(function (d) {
      if (seq !== S.seq) return;
      S.path = d;
      drawPath(d);
      pathRows(d, false);
    }).catch(function (e) { if (seq === S.seq) errorBox($("[data-vx-histo]"), e); });
  }

  function drawPath(d) {
    C.histogram($("[data-vx-histo]"), { edges: d.histogram.edges, counts: d.histogram.counts, "var": d["var"], es: d.es });
    C.bars($("[data-vx-tail]"), d.worst.map(function (w, r) {
      return { k: "#" + w.rank, v: w.pnl, cls: r === d.k - 1 ? "var" : r < d.esCount ? "tail" : "",
               tip: "#" + w.rank + " · " + w.date + "\n" + C.signed(w.pnl) + (r === d.k - 1 ? "\nthe VaR scenario" : ""), date: w.date };
    }), { height: 210, labelEvery: 6, onPick: function (w) { drill(w.date); } });
    var first = d.dates[0];
    $("[data-vx-series-title]").textContent = "P&L by scenario date (" + d.dates[0] + " to " + d.dates[1] + ")";
    var dates = scenarioDates(d);
    C.bars($("[data-vx-series]"), d.series.map(function (v, i) {
      return { k: dates[i] || String(i), v: v, cls: v <= -d["var"] ? "tail" : v < 0 ? "neg" : "", date: dates[i] };
    }), { height: 150, line: { v: -d["var"], label: "−VaR" }, onPick: function (x) { drill(x.date); } });
    void first;
  }

  /** The scenario dates, oldest first (index i of the series is date i). */
  function scenarioDates(d) {
    if (d.scenarioDates) return d.scenarioDates;
    var out = new Array(d.series.length);
    (d.rows || []).concat(d.worst || []).forEach(function (r) { out[r.index] = r.date; });
    return out;
  }

  function pathRows(d, append) {
    var tb = $("[data-vx-pathtable] tbody");
    if (!append) tb.textContent = "";
    d.rows.forEach(function (r) {
      var tr = document.createElement("tr");
      if (r.rank === d.k) tr.className = "hl";
      cell(tr, String(r.rank), "n mono");
      cell(tr, r.date, "mono");
      cell(tr, int(r.pnl), "n mono " + (r.pnl < 0 ? "neg" : "pos"));
      var from = cell(tr, r.from || "—", "mono");
      if (r.fromScope) { from.classList.add("vx-linkcell"); from.addEventListener("click", function (ev) { ev.stopPropagation(); select(r.fromScope); }); }
      cell(tr, r.fromPnl === undefined ? "" : int(r.fromPnl), "n mono neg");
      tr.addEventListener("click", function () { drill(r.date); });
      tb.appendChild(tr);
    });
    var more = $("[data-vx-more]");
    more.hidden = (d.page + 1) * d.pageSize >= d.total;
    $("[data-vx-path-note]").textContent = "Rank " + d.k + " is the VaR scenario (highlighted). " + Math.min(d.total, (d.page + 1) * d.pageSize) + " of " + d.total + " scenarios shown.";
  }

  /** A scenario date's contributors beside the charts: which parts lost most that day. */
  function drill(date) {
    if (!date) return;
    var box = $("[data-vx-drill]");
    box.hidden = false;
    box.textContent = "computing the contributors on " + date + "…";
    api("scope/contributions", { scope: S.scope, scenario: date }).then(function (d) {
      box.textContent = "";
      var head = h("div", "vx-ph");
      head.appendChild(h("h2", null, "Contributors on " + date));
      head.appendChild(h("span", "vx-fresh", "scope P&L " + int(d.scenarioPnl) + " · rank " + d.scenarioRank + " of " + (S.path ? S.path.total : "")));
      var close = h("button", "btn btn-sm vx-btn", "Close");
      close.type = "button";
      close.addEventListener("click", function () { box.hidden = true; });
      head.appendChild(close);
      box.appendChild(head);
      var t = h("table", "vx-table");
      var thr = document.createElement("tr");
      ["Part", "P&L that day", "Component VaR"].forEach(function (x, i) { thr.appendChild(h("th", i ? "n" : null, x)); });
      t.appendChild(h("thead")).appendChild(thr);
      var tb = h("tbody");
      d.rows.slice(0, 15).forEach(function (r) {
        var tr = document.createElement("tr");
        cell(tr, r.label, "vx-linkcell");
        cell(tr, int(r.scenarioPnl), "n mono " + (r.scenarioPnl < 0 ? "neg" : "pos"));
        cell(tr, int(r.componentVar), "n mono");
        tr.addEventListener("click", function () { select(r.scope); });
        tb.appendChild(tr);
      });
      if (!d.rows.length) { var tr = document.createElement("tr"); cell(tr, "No parts below this scope.", "vx-note").colSpan = 3; tb.appendChild(tr); }
      t.appendChild(tb);
      box.appendChild(t);
    }).catch(function (e) { errorBox(box, e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- history
  function loadHistory(seq) {
    var host = $("[data-vx-hline]");
    if (!host.children.length) host.textContent = "computing…";
    return api("scope/history", { scope: S.scope }).then(function (d) {
      if (seq !== S.seq) return;
      var keys = d.rows.map(function (r) { return r.date; });
      C.lines(host, keys, [{ name: "−VaR", cls: "mk-var", data: d.rows.map(function (r) { return -r["var"]; }) },
                           { name: "−ES", cls: "mk-es", data: d.rows.map(function (r) { return -r.es; }) }],
              d.rows.map(function (r) { return { v: r.pnl, exc: r.exception }; }), { height: 260 });
      var bt = d.backtest;
      $("[data-vx-hist-note]").textContent = (d.mode === "official" ? "Official history. " : "Replay. ") + d.note + ". " + bt.exceptions
        + " exception" + (bt.exceptions === 1 ? "" : "s") + " in " + bt.days + " days: " + bt.light + " zone; Kupiec p = " + bt.kupiecPValue.toFixed(3)
        + ", Christoffersen p = " + bt.christoffersenPValue.toFixed(3) + ".";
      var tb = $("[data-vx-histtable] tbody");
      tb.textContent = "";
      d.rows.slice().reverse().forEach(function (r) {
        var tr = document.createElement("tr");
        if (r.exception) tr.className = "hl";
        cell(tr, r.date, "mono");
        cell(tr, C.money(r["var"]), "n mono");
        cell(tr, C.money(r.es), "n mono");
        cell(tr, C.signed(r.pnl), "n mono " + (r.pnl < 0 ? "neg" : "pos"));
        var td = cell(tr, "");
        if (r.exception) td.appendChild(h("span", "vx-chip bad", "exception"));
        tb.appendChild(tr);
      });
    }).catch(function (e) { if (seq === S.seq) errorBox(host, e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- contributions
  function loadContrib(seq) {
    var tb = $("[data-vx-ctable] tbody");
    if (!tb.children.length) { tb.textContent = ""; var tr = document.createElement("tr"); cell(tr, "computing…", "vx-note").colSpan = 8; tb.appendChild(tr); }
    return api("scope/contributions", { scope: S.scope, by: S.by === "child" ? "" : S.by }).then(function (d) {
      if (seq !== S.seq) return;
      renderContrib(d);
    }).catch(function (e) {
      if (seq !== S.seq) return;
      tb.textContent = "";
      var tr = document.createElement("tr");
      cell(tr, e.message, "vx-err").colSpan = 8;
      tb.appendChild(tr);
    });
  }

  function renderContrib(d) {
    var tb = $("[data-vx-ctable] tbody");
    tb.textContent = "";
    if (!d.rows.length) {
      var tr = document.createElement("tr");
      cell(tr, S.head && S.head.kind === "trade" ? "A trade has no parts: its P&L path is its own vector." :
        "No parts you may see below this scope (a position level your role sees in aggregate only, or none).", "vx-note").colSpan = 8;
      tb.appendChild(tr);
      return;
    }
    var total = d["var"] || 1;
    d.rows.forEach(function (r) {
      var tr = document.createElement("tr");
      tr.dataset.scope = r.scope;
      tr.className = "vx-click";
      var cb = h("input");
      cb.type = "checkbox";
      cb.checked = !!S.picked[r.scope];
      cb.setAttribute("aria-label", "Pick " + r.label);
      cb.addEventListener("click", function (ev) { ev.stopPropagation(); if (cb.checked) S.picked[r.scope] = r.label; else delete S.picked[r.scope]; pickState(); });
      cell(tr, "", "cb").appendChild(cb);
      var name = cell(tr, "", "vx-name");
      if (r.direction) name.appendChild(chip(r.direction));
      name.appendChild(document.createTextNode(" " + r.label + (r.quantity !== undefined ? " · " + int(r.quantity) : "") + (r.partial ? " (part)" : "")));
      cell(tr, int(r.standaloneVar), "n mono");
      cell(tr, int(r.componentVar), "n mono");
      cell(tr, pct(r.pct), "n mono");
      var b = cell(tr, "", "bar");
      var bar = h("span", "vx-ubar wide");
      var i = h("i", r.componentVar < 0 ? "hedge" : "var");
      i.style.width = Math.min(100, Math.abs(r.componentVar) / total * 100) + "%";
      bar.appendChild(i);
      b.appendChild(bar);
      cell(tr, r.marginalPer1m === null ? "—" : int(r.marginalPer1m), "n mono");
      cell(tr, C.signed(r.incrementalVar), "n mono");
      tr.addEventListener("click", function () { select(r.scope); });
      tb.appendChild(tr);
    });
    var t = document.createElement("tr");
    t.className = "vx-total";
    cell(t, "");
    cell(t, "Total");
    cell(t, int(d.sumStandalone), "n mono");
    cell(t, int(d.sumComponent), "n mono");
    cell(t, "100.0%", "n mono");
    cell(t, "diversification −" + C.money(d.diversification), "vx-note").colSpan = 3;
    tb.appendChild(t);
    $("[data-vx-ctable-note]").textContent = "Component VaR adds up to the scope's VaR (Euler, on the VaR scenario's neighbourhood). Incremental VaR = the scope's VaR minus its VaR without the row."
      + (d.truncated ? " Only the first parts are shown." : "") + " Click a row to drill in; tick rows and Add to basket.";
  }

  function pickState() {
    var n = Object.keys(S.picked).length;
    var b = $("[data-vx-add-basket]");
    b.disabled = n === 0;
    b.textContent = n ? "Add " + n + " to basket" : "Add to basket";
  }

  // ---------------------------------------------------------------------------------------------------------------- captures
  function loadCaptures(seq) {
    return api("captures", { scope: S.scope }, true).then(function (list) {
      if (seq !== S.seq) return;
      var tb = $("[data-vx-captable] tbody");
      tb.textContent = "";
      if (!list.length) { var tr = document.createElement("tr"); cell(tr, "Nothing captured for this scope yet.", "vx-note").colSpan = 8; tb.appendChild(tr); }
      list.forEach(function (c) {
        var tr = document.createElement("tr");
        tr.dataset.capture = c.id;
        cell(tr, c.id, "mono");
        cell(tr, c.businessDate, "mono");
        cell(tr, String(c.capturedAt).replace("T", " ").slice(0, 19), "mono");
        cell(tr, c.owner);
        cell(tr, int(c["var"]), "n mono");
        cell(tr, int(c.es), "n mono");
        cell(tr, c.note || "");
        var act = cell(tr, "");
        [["Reopen", function () { reopen(c.id); }], ["Compare with now", function () { compare(c.id, "now"); }]].forEach(function (a) {
          var b = h("button", "vx-link", a[0]); b.type = "button"; b.addEventListener("click", a[1]); act.appendChild(b); act.appendChild(document.createTextNode(" "));
        });
        var csv = h("a", "vx-link", "CSV");
        csv.href = "/api/var/captures/" + encodeURIComponent(c.id) + "/csv";
        act.appendChild(csv);
        tb.appendChild(tr);
      });
      if (list.length >= 2) {
        var tr2 = document.createElement("tr");
        var td = cell(tr2, "", "vx-note");
        td.colSpan = 8;
        var b = h("button", "vx-link", "Compare the two newest");
        b.type = "button";
        b.addEventListener("click", function () { compare(list[1].id, list[0].id); });
        td.appendChild(b);
        tb.appendChild(tr2);
      }
    }).catch(function (e) { if (seq === S.seq) errorBox($("[data-vx-compare]"), e); });
  }

  function doCapture(noteInput) {
    var msg = $("[data-vx-cap-msg]");
    msg.textContent = "capturing…";
    send("POST", "captures", { scope: S.scope, note: noteInput ? noteInput.value : "" }).then(function (c) {
      msg.textContent = "Captured " + c.id + ": VaR " + int(c["var"]) + ", " + int(c.memberCount) + " trades.";
      root.dataset.lastCapture = c.id;
      if (S.tab !== "captures") switchTab("captures"); else loadCaptures(S.seq);
    }).catch(function (e) { msg.textContent = e.message; });
  }

  function reopen(id) {
    var box = $("[data-vx-compare]");
    box.hidden = false;
    box.textContent = "reading " + id + "…";
    api("captures/" + encodeURIComponent(id), {}, true).then(function (c) {
      box.textContent = "";
      box.appendChild(h("h3", null, c.id + " · " + c.label + " · as captured " + String(c.capturedAt).slice(0, 19).replace("T", " ")));
      var dl = h("dl", "vx-capdl");
      [["Business date", c.businessDate], ["VaR", int(c["var"])], ["ES", int(c.es)], ["Members", int(c.memberCount) + " trades"],
        ["Data version", c.dataVersion], ["Scenario set", c.scenarioSet], ["Note", c.note || "—"]].forEach(function (r) {
        dl.appendChild(h("dt", null, r[0])); dl.appendChild(h("dd", "mono", String(r[1])));
      });
      box.appendChild(dl);
      box.dataset.reopened = c.id;
      box.dataset.var = String(c["var"]);
      var t = h("table", "vx-table");
      var thr = document.createElement("tr");
      ["Component", "Component VaR", "% of VaR"].forEach(function (x, i) { thr.appendChild(h("th", i ? "n" : null, x)); });
      t.appendChild(h("thead")).appendChild(thr);
      var tb = h("tbody");
      (c.components || []).slice(0, 12).forEach(function (r) {
        var tr = document.createElement("tr"); cell(tr, r.label); cell(tr, int(r.componentVar), "n mono"); cell(tr, pct(r.pct), "n mono"); tb.appendChild(tr);
      });
      t.appendChild(tb);
      box.appendChild(t);
    }).catch(function (e) { errorBox(box, e); });
  }

  function compare(a, b) {
    var box = $("[data-vx-compare]");
    box.hidden = false;
    box.textContent = "comparing…";
    api("compare", { a: a, b: b }, true).then(function (d) {
      box.textContent = "";
      box.dataset.compared = a + "|" + b;
      box.appendChild(h("h3", null, "Compare " + d.a.id + " (" + d.a.businessDate + ") with " + (d.b.id === "now" ? "now" : d.b.id) + " (" + d.b.businessDate + ")"));
      var t = h("table", "vx-table");
      var thr = document.createElement("tr");
      ["", d.a.id, d.b.id === "now" ? "now" : d.b.id, "Change"].forEach(function (x, i) { thr.appendChild(h("th", i ? "n" : null, x)); });
      t.appendChild(h("thead")).appendChild(thr);
      var tb = h("tbody");
      [["VaR", d.a["var"], d.b["var"], d.varChange], ["ES", d.a.es, d.b.es, d.esChange], ["Members", d.a.memberCount, d.b.memberCount, d.b.memberCount - d.a.memberCount]]
        .concat(d.components.slice(0, 10).map(function (r) { return [r.label || r.key, r.a, r.b, r.change]; })).forEach(function (r) {
          var tr = document.createElement("tr");
          cell(tr, r[0]); cell(tr, int(r[1]), "n mono"); cell(tr, int(r[2]), "n mono"); cell(tr, C.signed(r[3] || 0), "n mono " + ((r[3] || 0) > 0 ? "neg" : "pos"));
          tb.appendChild(tr);
        });
      t.appendChild(tb);
      box.appendChild(t);
      if (d.membersAdded.length || d.membersRemoved.length) {
        box.appendChild(h("p", "vx-note", "Trades added: " + (d.membersAdded.join(", ") || "none") + ". Removed: " + (d.membersRemoved.join(", ") || "none") + "."));
      }
    }).catch(function (e) { errorBox(box, e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- the tree
  function loadTree() {
    fresh("tree", "loading…", "computing");
    S.treeLoading = true;
    return api("tree", { hierarchy: S.hierarchy }).then(function (d) {
      S.treeLoading = false;
      S.tree = d;
      var byId = {};
      d.nodes.forEach(function (n) { byId[n.id] = n; if (n.depth < 2) S.expanded[n.scope] = true; });
      S.byId = byId;
      renderTree();
      renderAdhoc();
      fresh("tree", d.businessDate);
    }).catch(function (e) { S.treeLoading = false; errorBox($("[data-vx-tree]"), e); fresh("tree", "failed", "stale"); });
  }

  function treeRow(label, scope, depth, v, es, lim, opts) {
    var row = h("div", "vx-row" + (opts.hdr ? " hdr" : ""));
    row.setAttribute("role", "treeitem");
    row.tabIndex = 0;
    row.dataset.scope = scope;
    var nm = h("span", "nm");
    nm.style.paddingLeft = (depth * 9) + "px";
    var tw = h("button", "tw", opts.expandable ? (S.expanded[scope] ? "▾" : "▸") : opts.adhoc ? "◇" : "·");
    tw.type = "button";
    tw.tabIndex = -1;
    tw.setAttribute("aria-label", opts.expandable ? (S.expanded[scope] ? "Collapse" : "Expand") : "");
    if (opts.expandable) {
      row.setAttribute("aria-expanded", S.expanded[scope] ? "true" : "false");
      tw.addEventListener("click", function (ev) { ev.stopPropagation(); toggle(scope); });
    }
    nm.appendChild(tw);
    if (opts.direction) nm.appendChild(chip(opts.direction));
    nm.appendChild(document.createTextNode(" " + label));
    row.appendChild(nm);
    row.appendChild(h("span", "num", v === null || v === undefined ? "" : C.money(v)));
    row.appendChild(h("span", "num muted", es === null || es === undefined ? "" : C.money(es)));
    var l = h("span", "lim");
    if (lim !== null && lim !== undefined) l.appendChild(ubar(lim)); else if (opts.noLimitText) l.appendChild(h("span", "muted small", opts.noLimitText));
    row.appendChild(l);
    row.addEventListener("click", function () { select(scope); });
    row.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter") select(scope);
      if (ev.key === "ArrowRight" && opts.expandable && !S.expanded[scope]) toggle(scope);
      if (ev.key === "ArrowLeft" && opts.expandable && S.expanded[scope]) toggle(scope);
    });
    return row;
  }

  function renderTree() {
    var t = $("[data-vx-tree]");
    t.textContent = "";
    var hd = h("div", "vx-row hdr");
    ["Scope", "VaR", "ES", "Limit"].forEach(function (x, i) { hd.appendChild(h("span", i ? "num" : null, x)); });
    t.appendChild(hd);
    var shown = {};
    (S.tree.nodes || []).forEach(function (n) {
      var parent = n.parent && S.byId[n.parent];
      if (parent && (!shown[parent.id] || !S.expanded[parent.scope])) return;
      shown[n.id] = true;
      t.appendChild(treeRow(n.name, n.scope, n.depth, n["var"], n.es, n.limitUse, { expandable: n.expandable, noLimitText: "no limit" }));
      if (n.level === "book" && S.expanded[n.scope]) appendChildren(t, n.scope, n.depth + 1);
    });
    markTree();
  }

  /** A book's positions, or a position's trades, below its row (from the contributions of that scope). */
  function appendChildren(t, scope, depth) {
    var kids = S.children[scope];
    if (!kids) {
      var wait = h("div", "vx-row muted", "computing…");
      wait.dataset.waitFor = scope;
      t.appendChild(wait);
      api("scope/contributions", { scope: scope }).then(function (d) { S.children[scope] = d.rows; renderTree(); })
        .catch(function (e) { S.children[scope] = []; wait.textContent = e.message; });
      return;
    }
    kids.forEach(function (r) {
      var isPos = r.kind === "position";
      t.appendChild(treeRow((isPos ? r.label : r.key) + (r.quantity !== undefined ? " · " + C.money(r.quantity) : ""), r.scope, depth,
        r.standaloneVar, r.standaloneEs, null, { expandable: isPos, direction: r.direction, noLimitText: "c " + C.money(r.componentVar) }));
      if (isPos && S.expanded[r.scope]) appendChildren(t, r.scope, depth + 1);
    });
  }

  function toggle(scope) {
    S.expanded[scope] = !S.expanded[scope];
    renderTree();
  }

  function markTree() {
    root.querySelectorAll(".vx-row[data-scope]").forEach(function (r) { r.setAttribute("aria-current", r.dataset.scope === S.scope ? "true" : "false"); });
  }

  function renderAdhoc() {
    var t = $("[data-vx-adhoc]");
    t.textContent = "";
    ((S.tree && S.tree.portfolios) || []).forEach(function (p) {
      t.appendChild(treeRow(p.name + " · " + p.type.toLowerCase(), p.scope, 0, null, null, null, { adhoc: true }));
    });
    api("baskets", {}, true).then(function (bs) {
      S.baskets = bs;
      bs.forEach(function (b) {
        t.appendChild(treeRow("★ " + b.name + (b.owner !== S.user ? " (" + b.owner + ")" : ""), "BASKET:" + b.id, 0, null, null, null, { adhoc: true }));
      });
      fillCombine();
      markTree();
    }).catch(function () { fillCombine(); });
    markTree();
  }

  // ---------------------------------------------------------------------------------------------------------------- the scope builder
  var builder = $("[data-vx-builder]"), text = $("[data-vx-scope-text]"), timer = null;
  function openBuilder(seed) {
    builder.hidden = false;
    if (seed !== undefined) text.value = seed;
    text.focus();
  }
  function appendTerm(term, op) {
    var cur = text.value.trim();
    text.value = cur ? cur + " " + (op || "+") + " " + term : term;
    live();
  }
  function live() {
    clearTimeout(timer);
    timer = setTimeout(function () { var s = text.value.trim(); if (s) select(s); }, 350);
  }
  function fillCombine() {
    var sel = $("[data-vx-combine]");
    sel.textContent = "";
    var add = function (label, scope) { var o = document.createElement("option"); o.value = scope; o.textContent = label; sel.appendChild(o); };
    add("— a scope to combine —", "");
    (S.tree ? S.tree.nodes : []).forEach(function (n) { add(n.name + " (" + n.level + ")", n.scope); });
    (S.tree ? S.tree.portfolios : []).forEach(function (p) { add(p.name + " (portfolio)", p.scope); });
    (S.baskets || []).forEach(function (b) { add(b.name + " (basket)", "BASKET:" + b.id); });
  }
  text.addEventListener("input", live);
  $("[data-vx-new-scope]").addEventListener("click", function () { openBuilder(S.head && S.head.kind !== "node" ? S.head.canonical : ""); });
  $("[data-vx-builder-close]").addEventListener("click", function () { builder.hidden = true; });
  $("[data-vx-add-ids]").addEventListener("click", function () {
    var ids = $("[data-vx-paste]").value.split(/[\s,;]+/).filter(Boolean);
    var pos = ids.filter(function (x) { return /^POS-/i.test(x); }), trd = ids.filter(function (x) { return !/^POS-/i.test(x); });
    if (pos.length) appendTerm("POS:" + pos.join(","));
    if (trd.length) appendTerm("TRD:" + trd.join(","));
    $("[data-vx-paste]").value = "";
  });
  $("[data-vx-add-rule]").addEventListener("click", function () {
    var r = $("[data-vx-rule]").value.trim();
    if (r) appendTerm("WHERE:" + r);
  });
  $("[data-vx-union]").addEventListener("click", function () { var v = $("[data-vx-combine]").value; if (v) appendTerm(v, "+"); });
  $("[data-vx-minus]").addEventListener("click", function () { var v = $("[data-vx-combine]").value; if (v) appendTerm(v.indexOf(" ") >= 0 ? "(" + v + ")" : v, "-"); });
  $("[data-vx-add-basket]").addEventListener("click", function () {
    var scopes = Object.keys(S.picked);
    if (!scopes.length) return;
    openBuilder();
    var pos = [], trd = [], other = [];
    scopes.forEach(function (s) { if (s.indexOf("POS:") === 0) pos.push(s.slice(4)); else if (s.indexOf("TRD:") === 0) trd.push(s.slice(4)); else other.push(s); });
    if (pos.length) appendTerm("POS:" + pos.join(","));
    if (trd.length) appendTerm("TRD:" + trd.join(","));
    other.forEach(function (s) { appendTerm(s); });
    S.picked = {};
    pickState();
  });
  $("[data-vx-save]").addEventListener("click", function () {
    var msg = $("[data-vx-builder-msg]"), name = $("[data-vx-name]").value.trim();
    if (!name || !text.value.trim()) { msg.textContent = "Give the scope a name and at least one member."; return; }
    var share = $("[data-vx-share]").value.split(/[\s,]+/).filter(Boolean);
    send("POST", "baskets", { name: name, title: name, scope: text.value.trim(), sharedWith: share }).then(function (b) {
      msg.textContent = "Saved as " + b.id + " (" + b.type.toLowerCase() + ", version " + b.version + ").";
      root.dataset.saved = b.id;
      renderAdhoc();
      select("BASKET:" + b.id);
    }).catch(function (e) { msg.textContent = e.message; });
  });

  // ---------------------------------------------------------------------------------------------------------------- tabs, toggles, exports
  function switchTab(tab) {
    S.tab = tab;
    root.querySelectorAll(".vx-tab").forEach(function (x) { x.setAttribute("aria-selected", x.dataset.tab === tab ? "true" : "false"); });
    root.querySelectorAll("[data-pane]").forEach(function (p) { p.hidden = p.dataset.pane !== tab; });
    var seq = S.seq, t0 = performance.now();
    fresh("main", "computing…", "computing");
    renderTab(seq).then(function () {
      if (seq === S.seq) {
        root.dataset.tabPainted = tab;
        fresh("main", tab + " in " + Math.round(performance.now() - t0) + " ms");
      }
    });
  }
  root.querySelectorAll(".vx-tab").forEach(function (t) { t.addEventListener("click", function () { switchTab(t.dataset.tab); }); });
  root.querySelectorAll(".vx-segb").forEach(function (b) {
    b.addEventListener("click", function () {
      S.by = b.dataset.by;
      root.querySelectorAll(".vx-segb").forEach(function (x) { x.setAttribute("aria-pressed", x === b ? "true" : "false"); });
      loadContrib(S.seq);
    });
  });
  $("[data-vx-more]").addEventListener("click", function () {
    S.page++;
    api("scope/path", { scope: S.scope, page: S.page }).then(function (d) { pathRows(d, true); });
  });
  $("[data-vx-hierarchy]").value = S.hierarchy;
  $("[data-vx-hierarchy]").addEventListener("change", function (ev) { S.hierarchy = ev.target.value; loadTree(); });
  $("[data-vx-capture]").addEventListener("click", function () { doCapture($("[data-vx-cap-note]")); });
  $("[data-vx-capture-side]").addEventListener("click", function () { doCapture(null); });

  function csvOfPath(ev) {
    ev.preventDefault();
    if (!S.path) return;
    var lines = ["# scope: " + S.scope, "# business date: " + (S.head ? S.head.businessDate : ""), "rank,scenario_date,pnl"];
    var order = S.path.series.map(function (v, i) { return [v, i]; }).sort(function (a, b) { return a[0] - b[0] || a[1] - b[1]; });
    var dates = scenarioDates(S.path);
    order.forEach(function (o, r) { lines.push((r + 1) + "," + (dates[o[1]] || "#" + o[1]) + "," + o[0]); });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([lines.join("\n") + "\n"], { type: "text/csv" }));
    a.download = "var-path-" + S.scope.replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 60) + ".csv";
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }
  $("[data-vx-csv-path]").addEventListener("click", csvOfPath);
  $("[data-vx-csv]").addEventListener("click", csvOfPath);

  window.addEventListener("popstate", function (ev) { if (ev.state && ev.state.scope) select(ev.state.scope, { noPush: true }); });
  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (S.path && S.tab === "path") drawPath(S.path); }, 150);
  });

  // ---------------------------------------------------------------------------------------------------------------- start
  history.replaceState({ scope: S.scope }, "", location.href);
  loadTree();
  if (root.dataset.capture) {
    api("captures/" + encodeURIComponent(root.dataset.capture), {}, true).then(function (c) {
      select(c.scope, { noPush: true });
      switchTab("captures");
      reopen(c.id);
    }).catch(function (e) { select(S.scope, { noPush: true }); errorBox($("[data-vx-tiles]"), e); });
  } else {
    select(S.scope, { noPush: true });
  }
  window.VarExplorer = { select: select, switchTab: switchTab, state: S };
})();
