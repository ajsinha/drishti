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
/* The exposure explorer (docs/guides/EXPOSURE_GUIDE.md section 3), after the approved combined mock-up. One page for any scope: the
   tree, the breadcrumb, a row of the counterparty board, a netting set in the contributions or a trade in the Trades tab moves every
   panel to the new scope in place (no page load); Back returns to the scope before. The headline tiles and the profile are always
   shown; the tabs below change with the scope's level: the counterparty board and the legal entities at the bank, the board at a legal
   entity or a group, the netting sets' contributions and the pre-deal what-if (into a netting set it picks) at a counterparty, the netting-set workbench (collateral, trades, pre-deal) at a
   netting set, the trade in its netting set at a trade; By tenor and Captures everywhere. Every view is a command (EXP <scope>) and a
   URL (/exposure?scope=...), so links, the command line and F1 work. The time from a click to the painted headline is kept on the root
   element (data-paint-ms) for the tests. */
(function () {
  "use strict";
  var root = document.querySelector("[data-exposure]");
  if (!root || !window.ExposureCharts) return;
  var C = window.ExposureCharts;
  var $ = function (sel) { return root.querySelector(sel); };
  var params = new URLSearchParams(location.search);
  var S = { scope: root.dataset.scope || "BANK", q: Number(params.get("q")) || 0.95, collateral: params.get("collateral") || "lagged",
            asOf: params.get("asOf") || "", tab: null, tree: null, head: null, seq: 0, back: [], via: {}, expanded: {}, page: 0, board: 25,
            user: root.dataset.user || "" };
  var cache = new Map();
  var retired = params.get("from") || "";
  var TABS = {
    bank: [["board", "Counterparties"], ["les", "Legal entities"], ["tenor", "By tenor"]],
    "legal-entity": [["board", "Counterparties"], ["tenor", "By tenor"]],
    group: [["board", "Counterparties"], ["tenor", "By tenor"]],
    counterparty: [["sets", "Netting sets"], ["predeal", "Pre-deal"], ["tenor", "By tenor"]],
    "netting-set": [["coll", "Collateral"], ["trades", "Trades"], ["predeal", "Pre-deal"], ["tenor", "By tenor"]],
    trade: [["inset", "In its netting set"], ["tenor", "By tenor (alone)"]],
    adhoc: [["sets", "Netting sets"], ["tenor", "By tenor"]]
  };
  var PAGE = 25;

  // ---------------------------------------------------------------------------------------------------------------- data
  function query(q, plain) {
    var p = new URLSearchParams();
    Object.keys(q || {}).forEach(function (k) { if (q[k] !== undefined && q[k] !== null && q[k] !== "") p.set(k, q[k]); });
    if (!plain) { p.set("q", S.q); if (!p.has("collateral")) p.set("collateral", S.collateral); }
    if (S.asOf) p.set("asOf", S.asOf);
    return p.toString();
  }
  function api(path, q, opts) {
    opts = opts || {};
    var url = "/api/exposure/" + path + "?" + query(q, opts.plain);
    if (!opts.fresh && cache.has(url)) return cache.get(url);
    var pr = fetch(url, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; throw e; }
        return b;
      });
    });
    cache.set(url, pr);
    pr.catch(function () { cache.delete(url); });
    return pr;
  }
  function send(path, body) {
    return fetch("/api/exposure/" + path + (S.asOf ? "?asOf=" + encodeURIComponent(S.asOf) : ""), {
      method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(body)
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; throw e; }
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
  var m = C.money;
  function int(v) { return v === null || v === undefined || isNaN(v) ? "—" : Math.round(v).toLocaleString("en-US"); }
  function pct(v, d) { return v === null || v === undefined || isNaN(v) ? "—" : (v * 100).toFixed(d === undefined ? 1 : d) + "%"; }
  function cell(tr, text, cls) { var td = h("td", cls, text); tr.appendChild(td); return td; }
  function table(heads, numeric) {
    var t = h("table", "xx-table"), tr = document.createElement("tr");
    heads.forEach(function (x, i) { tr.appendChild(h("th", numeric && numeric.indexOf(i) >= 0 ? "n" : null, x)); });
    t.appendChild(h("thead")).appendChild(tr);
    t.appendChild(h("tbody"));
    return t;
  }
  function wrap(t) { var d = h("div", "xx-scroll"); d.appendChild(t); return d; }
  function useClass(u) { return u > 1 ? "crit" : u > 0.9 ? "warn" : "ok"; }
  function errorBox(host, e) {
    host.textContent = "";
    var p = h("p", "xx-err", (e.status === 403 ? "Not for your role: " : "Could not compute: ") + e.message);
    p.setAttribute("role", "alert");
    host.appendChild(p);
  }
  function qLabel() { return Math.round(S.q * 100) + "%"; }
  function level(d) {
    if (!d) return "adhoc";
    if (d.kind === "trades" && d.trade) return "trade";
    return TABS[d.kind] ? d.kind : "adhoc";
  }
  function words(scope) { return "EXP " + scope; }
  function trdLink(id) {
    var a = h("a", null, "TRD " + id);
    a.href = "/go?q=" + encodeURIComponent("TRD " + id + " <GO>");
    a.title = "Open the trade's page (TRD " + id + ")";
    a.addEventListener("click", function (ev) { ev.stopPropagation(); });
    return a;
  }

  // ---------------------------------------------------------------------------------------------------------------- selecting a scope
  function select(scope, opts) {
    opts = opts || {};
    if (!opts.back && S.scope && S.scope !== scope && !opts.noPush) S.back.push(S.scope);
    if (opts.via) S.via[scope] = opts.via;
    S.scope = scope;
    S.page = 0;
    var seq = ++S.seq, t0 = performance.now();
    root.dataset.scope = scope;
    delete root.dataset.painted;
    if (!opts.noPush) {
      var u = new URL(location.href);
      u.searchParams.set("scope", scope);
      ["from", "capture"].forEach(function (k) { u.searchParams.delete(k); });
      history.pushState({ scope: scope }, "", u.toString());
    }
    $("[data-xx-cmd]").textContent = words(scope);
    var topIn = document.querySelector("#cmd, input[name=q]");
    if (topIn && document.activeElement !== topIn) topIn.value = words(scope) + " <GO>";
    $("[data-xx-tiles]").classList.add("computing");
    return api("scope", { scope: scope }).then(function (d) {
      if (seq !== S.seq) return;
      S.head = d;
      $("[data-xx-nopaths]").hidden = true;
      ["[data-xx-tiles]", ".xx-chartbox", "[data-xx-prov-box]"].forEach(function (s) { $(s).hidden = false; });
      $("[data-xx-tabs]").parentNode.hidden = false;
      noticeRetired(d);
      renderHead(d);
      renderTabs();
      Object.keys(openPath()).forEach(function (k) { delete S.expanded[k]; });   // the way to the new scope opens again
      if (S.tree) renderTree();
      var ms = Math.round(performance.now() - t0);
      root.dataset.painted = scope;
      root.dataset.paintMs = String(ms);
      $("[data-xx-timing]").textContent = "painted in " + ms + " ms · server " + d.provenance.computeMs + " ms, " + d.provenance.cache;
      if (!S.tree && !S.treeLoading) loadTree();
      document.dispatchEvent(new CustomEvent("exposure:painted", { detail: { scope: scope, ms: ms } }));
    }).catch(function (e) {
      if (seq !== S.seq) return;
      S.head = null;
      if (e.status === 404 && /no exposure paths/.test(e.message)) {
        noPaths(scope, e.message);
      } else {
        errorBox($("[data-xx-tiles]"), e);
      }
      root.dataset.painted = scope;
      treeReady().then(function () { if (seq !== S.seq) return; renderCrumb(null); Object.keys(openPath()).forEach(function (k) { delete S.expanded[k]; }); renderTree(); });
    });
  }

  function noticeRetired(d) {
    if (!retired) return;
    var n = $("[data-xx-notice]");
    n.hidden = false;
    n.textContent = retired + " was a stored exposure profile, retired in 2.9.0: " + words(d.canonical) + " is computed from the paths now.";
    retired = "";
  }

  /** A netting set or counterparty the reference data knows that the date's run does not cover: said so, never stale numbers. */
  function noPaths(scope, message) {
    var box = $("[data-xx-nopaths]");
    box.hidden = false;
    box.textContent = "";
    box.appendChild(h("b", null, message.replace(/^DRS-\d{4} /, "")));
    box.appendChild(h("p", "xx-note after", "The exposure engine computes from the day's simulated paths. When the upstream run covers this "
      + "scope its figures appear here; until then nothing is shown, never an old figure."));
    ["[data-xx-tiles]", ".xx-chartbox", "[data-xx-prov-box]"].forEach(function (s) { $(s).hidden = true; });
    $("[data-xx-tabs]").parentNode.hidden = true;
    root.dataset.noPaths = scope;
  }

  // ---------------------------------------------------------------------------------------------------------------- headline
  function peakTenorRow(d) {
    var at = (d.profile || []).filter(function (r) { return r.tenor === d.peakPfeTenor; })[0];
    return at || (d.profile || [])[0] || {};
  }

  function renderHead(d) {
    var run = d.provenance || {};
    $("[data-xx-meta]").textContent = "run " + d.runId + " · " + int(d.paths) + " paths · " + (d.profile || []).length + " tenors · " + d.businessDate;
    $("[data-xx-pfe-legend]").textContent = "PFE " + qLabel();
    var tiles;
    if (level(d) === "trade") {
      tiles = [["Peak PFE alone", m(d.peakPfe), "the trade as if it had no netting set, at " + (d.peakPfeTenor || "—")],
        ["EE contribution", "…", "in " + d.trade.nettingSet], ["Netting benefit", "…", "alone minus contribution"],
        ["Desk", d.trade.desk || "—", (d.trade.product || "") + (d.trade.assetClass ? " · " + d.trade.assetClass : "")]];
      tradeTiles(d);
    } else {
      tiles = [["Peak PFE " + qLabel(), m(d.peakPfe), "at " + (d.peakPfeTenor || "—") + " (" + (d.peakPfeDate || "") + ")"],
        ["EPE", m(d.epe), "first " + (d.horizonYears >= 1 ? "year" : d.horizonYears + " years") + ", time-weighted"],
        ["EEPE", m(d.eepe), "running max of EE"], ["EAD (IMM, info)", m(d.eadImm), "α " + d.alpha + " × EEPE"]];
      if (d.limit) {
        tiles.push(["Limit used", pct(d.limit.use, 0), "of " + m(d.limit.limit) + " · PFE " + Math.round(d.limit.confidence * 100) + "% peak " + m(d.limit.used),
          useClass(d.limit.use)]);
      } else {
        var r = peakTenorRow(d), ben = r.eeUncoll > 0 ? 1 - r.ee / r.eeUncoll : 0;
        tiles.push(["Collateral effect", S.collateral === "none" ? "—" : change(ben), S.collateral === "none" ? "the uncollateralised view"
          : "EE at " + (r.tenor || "") + " vs uncollateralised"]);
      }
    }
    var t = $("[data-xx-tiles]");
    t.textContent = "";
    t.classList.remove("computing");
    tiles.forEach(function (x, i) {
      var tile = h("div", "xx-tile");
      tile.dataset.tile = String(i);
      tile.appendChild(h("div", "k", x[0]));
      tile.appendChild(h("div", "v" + (x[3] ? " " + x[3] : ""), x[1]));
      tile.appendChild(h("div", "s", x[2]));
      t.appendChild(tile);
    });
    renderCrumb(d);
    renderChart(d);
    renderProv(d);
    var csv = $("[data-xx-csv]");
    csv.href = "/api/exposure/scope/profile?" + query({ scope: d.canonical, format: "csv" });
    var jl = $("[data-xx-jsonl]");
    if (jl) { jl.href = "/api/exposure/scope/profile?" + query({ scope: d.canonical, format: "jsonl" }); }
    document.title = "Exposure · " + d.label + (document.body.dataset.product ? " · " + document.body.dataset.product : "");
  }

  /** A trade's tiles that need its netting set: its EE contribution at the set's peak date and the netting benefit. */
  function tradeTiles(d) {
    var set = "NSET:" + d.trade.nettingSet, seq = S.seq;
    Promise.all([api("scope", { scope: set }), setTrades(set)]).then(function (x) {
      if (seq !== S.seq) return;
      var sh = x[0], rows = x[1].rows || [], me = rows.filter(function (r) { return r.key === d.trade.id; })[0];
      var t = $("[data-xx-tiles]");
      var c = me ? me.value : null;
      t.querySelector('[data-tile="1"] .v').textContent = m(c);
      t.querySelector('[data-tile="1"] .s').textContent = "at " + x[1].tenor + ", " + (me ? pct(me.pct) : "—") + " of " + sh.canonical.slice(5);
      t.querySelector('[data-tile="2"] .v').textContent = m(d.peakPfe - Math.max(c || 0, 0));
    }).catch(function () { /* the tiles keep their dots; the tab says why */ });
  }

  function renderCrumb(d) {
    var c = $("[data-xx-crumb]");
    c.textContent = "";
    if (S.back.length) {
      var b = h("button", null, "← Back");
      b.type = "button";
      b.dataset.xxBack = "1";
      b.addEventListener("click", goBack);
      c.appendChild(b);
      c.appendChild(h("span", null, "·"));
    }
    var parts = path(d).concat([{ scope: S.scope, label: d ? (level(d) === "trade" ? "TRD " + d.trade.id : d.label) : S.scope }]);
    parts.forEach(function (p, i) {
      if (i) c.appendChild(h("span", null, "›"));
      if (i === parts.length - 1) { c.appendChild(h("span", "cur", p.label)); return; }
      var a = h("a", null, p.label);
      a.href = "?scope=" + encodeURIComponent(p.scope);
      a.addEventListener("click", function (ev) { ev.preventDefault(); select(p.scope); });
      c.appendChild(a);
    });
  }

  /** The scopes above the current one (the bank, a legal entity, a counterparty, a netting set); a counterparty under the legal
   *  entity it was opened from. */
  function path(d) {
    var up = d ? (d.ancestors || []).slice() : treePath(S.scope);
    var via = S.via[S.scope];
    if (via) up = up.map(function (x) { return x.kind === "legal-entity" ? { scope: via, label: via.slice(3), kind: "legal-entity" } : x; });
    return up;
  }

  /** Where a scope without an answer (no paths) sits, from the tree: the bank, the netting set's legal entity and counterparty. */
  function treePath(scope) {
    var up = [{ scope: "BANK", label: "Bank", kind: "bank" }];
    if (!S.tree || scope === "BANK") return up;
    counterparties().forEach(function (cp) {
      (cp.children || []).forEach(function (s) {
        if (s.scope === scope) {
          up.push({ scope: "LE:" + s.legalEntity, label: s.legalEntity, kind: "legal-entity" });
          up.push({ scope: cp.scope, label: cp.label, kind: "counterparty" });
        }
      });
      if (cp.scope === scope && (cp.legalEntities || []).length) up.push({ scope: "LE:" + cp.legalEntities[0], label: cp.legalEntities[0], kind: "legal-entity" });
    });
    return up;
  }

  function renderChart(d) {
    var prof = d.profile || [], set = d.kind === "netting-set";
    var draw = function (bands) {
      C.profile($("[data-xx-chart]"), {
        tenors: prof.map(function (r) { return r.tenor; }), dates: prof.map(function (r) { return r.date; }), t: prof.map(function (r) { return r.t; }),
        series: [{ name: "EE uncollateralised", cls: "unc", data: prof.map(function (r) { return r.eeUncoll; }) },
          { name: "EE", cls: "ee", data: prof.map(function (r) { return r.ee; }), area: !set },
          { name: "PFE " + qLabel(), cls: "pfe", data: prof.map(function (r) { return r.pfe; }), peak: true }],
        bands: bands
      });
    };
    $("[data-xx-fan-legend]").hidden = !set;
    draw(null);
    if (set) {
      var seq = S.seq;
      api("scope/distribution", { scope: d.canonical, bands: "true" }).then(function (x) {
        if (seq !== S.seq) return;
        var b = x.bands || [];
        draw([{ cls: "xx-fan1", lo: b.map(function (r) { return r.q5; }), hi: b.map(function (r) { return r.q95; }) },
              { cls: "xx-fan2", lo: b.map(function (r) { return r.q25; }), hi: b.map(function (r) { return r.q75; }) }]);
        root.dataset.fan = String(b.length);
      }).catch(function () { /* the profile stands without its fan */ });
    }
  }

  function renderProv(d) {
    var p = d.provenance || {}, dl = $("[data-xx-prov]");
    dl.textContent = "";
    [["Scope", d.kind + " · " + d.canonical], ["Resolved", (p.resolved || []).join("; ") || p.scope],
      ["Netting sets", int(p.nettingSets) + " (" + int(p.wholeSets) + " whole, " + int(p.partialSets) + " cut)"], ["Trades", int(p.trades)],
      ["Outside your view", p.hiddenSets ? int(p.hiddenSets) + " netting sets, " + int(p.hiddenTrades) + " trades" : "none"],
      ["Run", p.runId + " · " + p.model + " " + (p.modelVersion || "") + " · seed " + p.seed], ["Calibration", p.calibration + " · " + p.valueBasis + " · " + p.precision],
      ["Grid", int(p.gridDates) + " dates (" + int(p.primaryDates) + " primary), look-back " + p.lookbackDays + " business days"],
      ["Method", p.method], ["Contributions", p.contributions], ["Flags", (d.flags || []).join(", ") || "none"],
      ["Warnings", (p.warnings || []).join("; ") || "none"], ["Tier", p.tier], ["Engine", p.engine], ["View", p.view],
      ["Members", p.membershipHash], ["Computed", String(p.computedAt || "").replace("T", " ").slice(0, 19) + " · " + p.computeMs + " ms · cache " + p.cache]]
      .forEach(function (r) { dl.appendChild(h("dt", null, r[0])); dl.appendChild(h("dd", null, r[1] === undefined ? "" : String(r[1]))); });
  }

  // ---------------------------------------------------------------------------------------------------------------- tabs
  function renderTabs() {
    var tabs = TABS[level(S.head)].concat([["captures", "Captures"]]), bar = $("[data-xx-tabs]");
    if (!S.tab || !tabs.some(function (x) { return x[0] === S.tab; })) S.tab = tabs[0][0];
    bar.textContent = "";
    tabs.forEach(function (x) {
      var b = h("button", "xx-tab", x[1]);
      b.type = "button";
      b.setAttribute("role", "tab");
      b.dataset.tab = x[0];
      b.setAttribute("aria-selected", x[0] === S.tab ? "true" : "false");
      b.addEventListener("click", function () { S.tab = x[0]; S.page = 0; renderTabs(); });
      bar.appendChild(b);
    });
    var pane = $("[data-xx-pane]"), seq = S.seq;
    pane.textContent = "computing…";
    delete root.dataset.tabPainted;
    var done = function () { if (seq === S.seq) root.dataset.tabPainted = S.tab; };
    var fn = PANES[S.tab];
    Promise.resolve(fn(pane, S.head)).then(done).catch(function (e) { if (seq === S.seq) { errorBox(pane, e); done(); } });
  }

  function counterparties() {
    var out = [];
    ((S.tree && S.tree.groups) || []).forEach(function (g) { (g.children || []).forEach(function (cp) { out.push(cp); }); });
    return out;
  }

  function board(pane, d) {
    return treeReady().then(function () {
      pane.textContent = "";
      var all = counterparties(), lev = level(d), id = d.canonical.slice(d.canonical.indexOf(":") + 1);
      if (lev === "legal-entity") all = all.filter(function (cp) { return (cp.legalEntities || []).indexOf(id) >= 0; });
      if (lev === "group") all = all.filter(function (cp) { return cp.group === id; });
      var withPaths = all.filter(function (cp) { return !cp.noPaths; }).sort(function (a, b) { return b.peakPfe - a.peakPfe; });
      var without = all.length - withPaths.length;
      var shown = withPaths.slice(0, S.board);
      pane.appendChild(h("p", "xx-note", withPaths.length + " counterparties with paths" + (lev === "bank" ? " across " + (S.tree.legalEntities || []).length
        + " legal entities" : " in " + d.label) + (without ? " (" + without + " without paths on " + d.businessDate + ")" : "") + " · sorted by peak PFE "
        + qLabel() + " · click a row to open it" + (lev === "legal-entity" ? " · figures of the whole counterparty" : "")));
      var t = table(["Counterparty", "Profile (EE)", "Peak PFE", "EPE", "Limit", "Used", "", "Sets"], [2, 3, 4, 7]), tb = t.tBodies[0];
      shown.forEach(function (cp) {
        var tr = document.createElement("tr");
        tr.className = "xx-click";
        tr.dataset.scope = cp.scope;
        cell(tr, cp.label);
        cell(tr, "").appendChild(C.spark(cp.ee, S.tree.t));
        cell(tr, m(cp.peakPfe), "n");
        cell(tr, m(cp.epe), "n");
        var l = cp.limit;
        cell(tr, l ? m(l.limit) : "—", "n");
        var bar = cell(tr, "");
        var chip = cell(tr, "");
        if (l) {
          var b = h("span", "xx-bar"), i = h("i", useClass(l.use));
          i.style.width = Math.min(100, l.use * 100) + "%";
          b.appendChild(i);
          b.title = pct(l.use, 0) + " of the stored limit " + l.id + " (" + (l.measure || "") + ")";
          bar.appendChild(b);
          chip.appendChild(h("span", "xx-chip " + useClass(l.use), pct(l.use, 0)));
        } else chip.appendChild(h("span", "muted", "no limit"));
        cell(tr, String((cp.children || []).filter(function (s) { return !s.noPaths; }).length), "n");
        tr.addEventListener("click", function () { select(cp.scope, { via: lev === "legal-entity" ? d.canonical : null }); });
        tb.appendChild(tr);
      });
      pane.appendChild(wrap(t));
      if (withPaths.length > shown.length) {
        var more = h("button", "xx-link", "Show all " + withPaths.length);
        more.type = "button";
        more.addEventListener("click", function () { S.board = 100000; renderTabs(); });
        pane.appendChild(h("p", "xx-pager")).appendChild(more);
      }
      pane.appendChild(h("p", "xx-note after", "Limit: the stored credit limit (credit-limit, read as stored); used: the computed peak PFE at the "
        + "limit's confidence over it. Chips: ok, warn above 90%, crit above 100%."));
    });
  }

  function les(pane, d) {
    return treeReady().then(function () {
      pane.textContent = "";
      var bank = S.tree.bankNode || {};
      var t = table(["Legal entity", "Profile (EE)", "Peak PFE", "EPE", "Netting sets", "Share of bank peak"], [2, 3, 4, 5]), tb = t.tBodies[0];
      (S.tree.legalEntities || []).slice().sort(function (a, b) { return (b.peakPfe || 0) - (a.peakPfe || 0); }).forEach(function (le) {
        var tr = document.createElement("tr");
        tr.className = "xx-click";
        tr.dataset.scope = le.scope;
        cell(tr, le.label);
        cell(tr, "").appendChild(C.spark(le.ee, S.tree.t));
        cell(tr, le.note ? "batch" : m(le.peakPfe), "n");
        cell(tr, m(le.epe), "n");
        cell(tr, int(le.nettingSets), "n");
        cell(tr, bank.peakPfe ? pct(le.peakPfe / bank.peakPfe) : "—", "n");
        tr.addEventListener("click", function () { select(le.scope); });
        tb.appendChild(tr);
      });
      pane.appendChild(wrap(t));
      pane.appendChild(h("p", "xx-note after", "A node's PFE is the PFE of everything it faces, from the summed paths, so the legal entities do "
        + "not add up to the bank. EPE adds up."));
    });
  }

  function setNode(id) {
    var out = null;
    counterparties().forEach(function (cp) { (cp.children || []).forEach(function (s) { if (s.id === id) out = s; }); });
    return out;
  }

  function sets(pane, d) {
    return treeReady().then(function () {
      pane.textContent = "";
      var rows = d.byNettingSet || [];
      if (!rows.length) { pane.appendChild(h("p", "xx-note", "No netting sets you may see below this scope (your role sees it in aggregate only).")); return; }
      pane.appendChild(h("p", "xx-note", "Contributions to the peak PFE " + qLabel() + " at " + d.peakPfeTenor + " (the paths around the quantile, "
        + "scaled to add up) · click a netting set for its workbench"));
      var t = table(["Netting set", "CSA", "Profile (EE)", "EPE", "Peak PFE alone", "PFE contribution", "Share"], [3, 4, 5, 6]), tb = t.tBodies[0], sum = 0;
      rows.forEach(function (r) {
        var n = setNode(r.nettingSet) || {}, tr = document.createElement("tr");
        tr.className = "xx-click";
        tr.dataset.scope = "NSET:" + r.nettingSet;
        cell(tr, r.nettingSet + (r.partial ? " (part)" : ""));
        cell(tr, n.csa || "");
        cell(tr, "").appendChild(C.spark(n.ee, S.tree.t));
        cell(tr, m(r.epe), "n");
        cell(tr, m(r.peakPfe), "n");
        cell(tr, m(r.pfeContribution), "n");
        cell(tr, d.peakPfe ? pct(r.pfeContribution / d.peakPfe) : "—", "n");
        sum += r.pfeContribution;
        tr.addEventListener("click", function () { select("NSET:" + r.nettingSet); });
        tb.appendChild(tr);
      });
      var tot = document.createElement("tr");
      tot.className = "xx-total";
      cell(tot, "Total");
      cell(tot, ""); cell(tot, ""); cell(tot, ""); cell(tot, "");
      cell(tot, m(sum), "n");
      cell(tot, d.peakPfe ? pct(sum / d.peakPfe) : "—", "n");
      tb.appendChild(tot);
      pane.appendChild(wrap(t));
    });
  }

  function tenor(pane, d) {
    pane.textContent = "";
    var prof = d.profile || [];
    var t = table(["Figure"].concat(prof.map(function (r) { return r.tenor; })), prof.map(function (_, i) { return i + 1; })), tb = t.tBodies[0];
    [["EE", "ee"], ["ENE", "ene"], ["EEE", "eee"], ["PFE " + qLabel(), "pfe"], ["PFE 99%", "pfe99"], ["EE uncollateralised", "eeUncoll"]].forEach(function (f) {
      var tr = document.createElement("tr");
      cell(tr, f[0]);
      prof.forEach(function (r) { cell(tr, int(r[f[1]]), "n"); });
      tb.appendChild(tr);
    });
    pane.appendChild(wrap(t));
    var p = h("p", "xx-note after", "USD. CSV of the profile: ");
    var a = h("a", null, "download");
    a.href = "/api/exposure/scope/profile?" + query({ scope: d.canonical, format: "csv" });
    p.appendChild(a);
    p.appendChild(document.createTextNode(" · "));
    var aj = h("a", null, "JSON Lines");
    aj.href = "/api/exposure/scope/profile?" + query({ scope: d.canonical, format: "jsonl" });
    p.appendChild(aj);
    p.appendChild(document.createTextNode(" · drishti.py exposure profile '" + d.canonical + "' --csv (or --jsonl) · GET /api/v1/exposure/scope/profile?format=csv"));
    pane.appendChild(p);
  }

  function kv(rows) {
    var dl = h("dl", "xx-kv");
    rows.forEach(function (r) { dl.appendChild(h("dt", null, r[0])); dl.appendChild(h("dd", r[2] || null, r[1])); });
    return dl;
  }

  /** Collateral's effect on EE as a signed change: a benefit of 0.65 is "−65%" (lower), a negative one (posted collateral raising the
   *  exposure) "+380%" (higher). */
  function change(benefit) {
    return benefit >= 0 ? "−" + pct(benefit, 0) : "+" + pct(-benefit, 0);
  }

  function coll(pane, d) {
    pane.textContent = "";
    var c = d.collateral || {}, a = c.atPeak || {}, two = h("div", "xx-two");
    two.appendChild(kv([["CSA", c.csa ? c.csa + " · " + c.label : "none (uncollateralised)"],
      ["Threshold (them / us)", m(c.thresholdThem) + " / " + m(c.thresholdUs)], ["Minimum transfer (them / us)", m(c.mtaThem) + " / " + m(c.mtaUs)],
      ["Independent amount", m(c.independentAmount)], ["Rounding", c.rounding ? m(c.rounding) : "none"],
      ["Margin period (MPOR)", c.mporDays ? c.mporDays + " business days" + (c.frequencyDays > 1 ? ", remargined every " + c.frequencyDays : "") : "—"],
      ["EE at " + a.tenor + " without collateral", m(a.eeUncoll)], ["Collateral on the paths at " + a.tenor + ", mean", m(a.collateralMean)],
      ["EE at " + a.tenor + " with collateral", m(a.ee)], ["Effect of collateral", change(a.benefit)]]));
    two.appendChild(kv([["Net MtM today", m(c.netMtm)], ["Collateral held today (net)", m(c.heldToday)], ["Initial margin held", m(c.imHeld)],
      ["Trades", int(c.trades)], ["Collateral model", c.model], ["Look-back used", c.lookbackUsed],
      ["Run-look-back flag", c.mporMismatch ? "MPOR_MISMATCH" : "none", c.mporMismatch ? "warn" : null],
      ["Today's collateral held at", (c.todaysCollateralAt || []).join(", ") || "—"]]));
    pane.appendChild(two);
    pane.appendChild(h("p", "xx-note after", "Lagged collateral: on each path what the CSA calls at the look-back date, a margin period of risk "
      + "earlier; a date whose look-back falls on or before today keeps today's collateral. Exposure = max(V − C − IM, 0)."));
  }

  /** The open netting set's trades with their EE contribution at its peak date, alone figures and EE contribution by date (cached). */
  function setTrades(set) {
    return api("scope", { scope: set }).then(function (sh) {
      return api("scope/contributions", { scope: set, by: "trade", figure: "EE", date: sh.peakPfeTenor, alone: "true", profile: "true" }).then(function (c) {
        c.tenor = sh.peakPfeTenor;
        return c;
      });
    });
  }

  function trades(pane, d) {
    return setTrades(d.canonical).then(function (c) {
      pane.textContent = "";
      var rows = c.rows || [];
      if (!rows.length) { pane.appendChild(h("p", "xx-note", c.note || "No trades you may see in this netting set.")); return; }
      var pages = Math.ceil(rows.length / PAGE);
      S.page = Math.min(S.page, pages - 1);
      pane.appendChild(h("p", "xx-note", "All " + rows.length + " trades, paged, by EE contribution at " + c.tenor + " (read from the lake on request) · a "
        + "row opens the trade's exposure · the id opens TRD"));
      var t = table(["Trade", "Product", "Desk", "EE contribution", "Share", "Peak PFE alone"], [3, 4, 5]), tb = t.tBodies[0];
      rows.slice(S.page * PAGE, (S.page + 1) * PAGE).forEach(function (r) {
        var tr = document.createElement("tr");
        tr.className = "xx-click";
        tr.dataset.scope = "TRD:" + r.key;
        cell(tr, "").appendChild(trdLink(r.key));
        cell(tr, r.product || "");
        cell(tr, r.desk || "");
        cell(tr, m(r.value), "n");
        cell(tr, pct(r.pct), "n");
        cell(tr, m(r.peakPfeAlone), "n");
        tr.addEventListener("click", function () { select("TRD:" + r.key); });
        tb.appendChild(tr);
      });
      pane.appendChild(wrap(t));
      var pg = h("div", "xx-pager");
      pg.appendChild(h("span", null, "page " + (S.page + 1) + " of " + pages));
      [["‹ previous", -1], ["next ›", 1]].forEach(function (x) {
        var b = h("button", "xx-link", x[0]);
        b.type = "button";
        b.disabled = S.page + x[1] < 0 || S.page + x[1] >= pages;
        b.dataset.xxPage = String(x[1]);
        b.addEventListener("click", function () { S.page += x[1]; trades(pane, d); });
        pg.appendChild(b);
      });
      pane.appendChild(pg);
      if (S.tree) renderTree();
    });
  }

  /**
   * The pre-deal what-if, on a netting set and on a counterparty: the candidate is a copy of a booked trade (its paths, scaled by a
   * factor; −1 its unwind). On a counterparty the person picks the netting set the new trade goes into (the largest by peak PFE first) and
   * sees the counterparty's figures (its credit limit's) or the set's.
   */
  function predeal(pane, d) {
    var onCp = d.kind === "counterparty";
    var sets0 = onCp ? (d.byNettingSet || []).slice().sort(function (a, b) { return (b.peakPfe || 0) - (a.peakPfe || 0); }) : [];
    var set = onCp ? (sets0.length ? "NSET:" + sets0[0].nettingSet : null) : d.canonical;
    if (!set) {
      pane.textContent = "";
      pane.appendChild(h("p", "xx-note", "No netting set of this counterparty you may see: nothing to deal into."));
      return Promise.resolve();
    }
    return Promise.all([setTrades(set), treeReady()]).then(function (x) {
      var rows = x[0].rows || [];
      pane.textContent = "";
      var two = h("div", "xx-two"), form = h("div", "xx-form");
      var sel = h("select", "xx-select");
      sel.dataset.xxCandidate = "1";
      var fill = function () {
        sel.textContent = "";
        rows.forEach(function (r) { var o = h("option", null, r.key + " · " + (r.product || "") + " · " + m(r.notional)); o.value = r.key; sel.appendChild(o); });
      };
      fill();
      var factor = h("input", "xx-input");
      factor.value = "1";
      factor.dataset.xxFactor = "1";
      factor.setAttribute("aria-label", "Factor");
      var method = h("select", "xx-select");
      method.dataset.xxMethod = "1";
      [["paths", "paths (exact, the same run)"], ["addon", "addon (conservative proxy)"]].forEach(function (o) { var e = h("option", null, o[1]); e.value = o[0]; method.appendChild(e); });
      var sc = h("select", "xx-select");
      sc.dataset.xxWhatifScope = "1";
      var cp = onCp ? { scope: d.canonical } : (d.ancestors || []).filter(function (a) { return a.kind === "counterparty"; })[0];
      var scopes = function () {
        sc.textContent = "";
        [[cp ? cp.scope : set, onCp ? "this counterparty (the credit limit's)" : "its counterparty (the credit limit's)"],
          [set, onCp ? "the netting set " + set.slice(5) : "this netting set"]].forEach(function (o) {
          var e = h("option", null, o[1]); e.value = o[0]; sc.appendChild(e);
        });
      };
      scopes();
      var fields = [["Candidate: like trade", sel], ["Factor (−1: its unwind)", factor], ["Method", method], ["Figures of", sc]];
      if (onCp) {
        var into = h("select", "xx-select");
        into.dataset.xxPredealSet = "1";
        sets0.forEach(function (s) {
          var o = h("option", null, s.nettingSet + " · peak PFE " + m(s.peakPfe));
          o.value = "NSET:" + s.nettingSet;
          into.appendChild(o);
        });
        into.addEventListener("change", function () {
          set = into.value;
          sel.disabled = true;
          setTrades(set).then(function (c) { rows = c.rows || []; fill(); scopes(); sel.disabled = false; });
        });
        fields.unshift(["Into the netting set", into]);
      }
      fields.forEach(function (r) { form.appendChild(h("label", null, r[0])); form.appendChild(r[1]); });
      var left = h("div");
      left.appendChild(form);
      var run = h("button", "btn btn-sm xx-btn primary", "Run what-if");
      run.type = "button";
      run.dataset.xxWhatif = "1";
      var chip = h("span", "xx-chip", "ready");
      var acts = h("div", "xx-actions");
      acts.appendChild(run);
      acts.appendChild(chip);
      left.appendChild(acts);
      left.appendChild(h("p", "xx-note after", "Pre-deal what-if: the candidate is a copy of a booked trade's paths on the day's run, scaled by the "
        + "factor, added to " + (onCp ? "the netting set picked above (the counterparty's largest first)" : "this netting set") + ". paths: its values "
        + "netted and collateralised with the set, path by path, by the Python exposure engine; addon: a netting set of its own, uncollateralised, "
        + "SA-CCR's supervisory factor (flagged proxy)."));
      var out = h("div");
      out.dataset.xxWhatifOut = "1";
      two.appendChild(left);
      two.appendChild(out);
      pane.appendChild(two);
      run.addEventListener("click", function () {
        var r = rows.filter(function (x) { return x.key === sel.value; })[0] || {}, f = Number(factor.value) || 1;
        chip.className = "xx-chip";
        chip.textContent = "computing…";
        var t0 = performance.now();
        var setId = set.slice(5);
        send("what-if", { scope: sc.value, method: method.value, q: S.q, collateral: S.collateral,
          candidates: [{ tradeId: "CANDIDATE-1", label: f + " × " + r.key, nettingSet: setId, paths: { copyOf: r.key, factor: f },
            assetClass: r.assetClass, notional: Math.abs((r.notional || 0) * f), maturityDate: r.maturity, mtm: (r.mtm || 0) * f, delta: f < 0 ? -1 : 1 }] })
          .then(function (w) {
            chip.className = "xx-chip " + (w.method === "addon" ? "proxy" : "ok");
            chip.textContent = (w.method === "addon" ? "proxy" : "exact") + " · done in " + ((performance.now() - t0) / 1000).toFixed(1) + " s";
            root.dataset.whatif = w.method;
            root.dataset.whatifScope = w.canonical;
            whatIfResult(out, w, d);
          }).catch(function (e) { chip.className = "xx-chip crit"; chip.textContent = "failed"; errorBox(out, e); });
      });
    });
  }

  function whatIfResult(out, w, d) {
    out.textContent = "";
    var b = w.base || {}, a = w["with"] || {}, inc = w.incremental || {};
    var cpNode = null;
    counterparties().forEach(function (cp) { if (cp.scope === w.canonical) cpNode = cp; });
    var rows = [["Candidate", (w.items && w.items[0] ? w.items[0].label : "") || String(w.candidates) + " candidate(s)"],
      ["Method", w.method + (w.flags && w.flags.length ? " · " + w.flags.join(", ") : "")], ["Figures of", w.canonical],
      ["Peak PFE before", m(b.peakPfe)], ["Peak PFE after", m(a.peakPfe)], ["Incremental", (inc.peakPfe >= 0 ? "+" : "") + m(inc.peakPfe), inc.peakPfe > 0 ? "warn" : null],
      ["EPE before / after", m(b.epe) + " / " + m(a.epe)]];
    if (cpNode && cpNode.limit && Math.abs(S.q - cpNode.limit.confidence) < 1e-9) {
      rows.push(["Limit headroom after", m(cpNode.limit.limit - a.peakPfe), a.peakPfe > cpNode.limit.limit ? "warn" : null]);
    }
    out.appendChild(kv(rows));
    var prof = inc.profile || [];
    var t = table(["Tenor", "EE before", "EE after", "PFE before", "PFE after", "Incremental PFE"], [1, 2, 3, 4, 5]), tb = t.tBodies[0];
    prof.forEach(function (r) {
      var tr = document.createElement("tr");
      cell(tr, r.tenor);
      cell(tr, m(r.eeBase), "n"); cell(tr, m(r.ee), "n"); cell(tr, m(r.pfeBase), "n"); cell(tr, m(r.pfe), "n"); cell(tr, m(r.incrementalPfe), "n");
      tb.appendChild(tr);
    });
    out.appendChild(wrap(t));
    out.appendChild(h("p", "xx-note after", w.note || ""));
  }

  function inset(pane, d) {
    var set = "NSET:" + d.trade.nettingSet;
    return Promise.all([setTrades(set), api("scope", { scope: set })]).then(function (x) {
      pane.textContent = "";
      var me = (x[0].rows || []).filter(function (r) { return r.key === d.trade.id; })[0], sp = x[1].profile || [];
      pane.appendChild(h("p", "xx-note", "The chart above is the trade alone. Its contribution to " + d.trade.nettingSet + " after netting and "
        + "collateral, by tenor (the shares of the set's trades add up to the set's EE at every date):"));
      var t = table(["Figure"].concat(sp.map(function (r) { return r.tenor; })), sp.map(function (_, i) { return i + 1; })), tb = t.tBodies[0];
      var prof = me && me.profile ? me.profile : [];
      [["EE contribution", function (i) { return int(prof[i]); }], ["Set EE", function (i) { return int(sp[i].ee); }],
        ["Share of set EE", function (i) { return sp[i].ee ? pct(prof[i] / sp[i].ee) : "—"; }]].forEach(function (f) {
        var tr = document.createElement("tr");
        cell(tr, f[0]);
        sp.forEach(function (_, i) { cell(tr, f[1](i), "n"); });
        tb.appendChild(tr);
      });
      pane.appendChild(wrap(t));
      var acts = h("div", "xx-actions");
      var open = h("a", "btn btn-sm xx-btn", "Open TRD " + d.trade.id);
      open.href = "/go?q=" + encodeURIComponent("TRD " + d.trade.id + " <GO>");
      var back = h("button", "btn btn-sm xx-btn", "Back to " + d.trade.nettingSet);
      back.type = "button";
      back.addEventListener("click", function () { select(set); });
      acts.appendChild(open);
      acts.appendChild(back);
      pane.appendChild(acts);
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- captures
  function captures(pane, d) {
    return api("captures", { scope: d.canonical }, { fresh: true, plain: true }).then(function (list) {
      pane.textContent = "";
      var bar = h("div", "xx-actions"), note = h("input", "xx-input");
      note.placeholder = "A note for the capture (optional)";
      note.dataset.xxCapNote = "1";
      note.setAttribute("aria-label", "Capture note");
      var b = h("button", "btn btn-sm xx-btn primary", "Capture exposure");
      b.type = "button";
      b.dataset.xxCapture = "1";
      var msg = h("span", "xx-note");
      b.addEventListener("click", function () { doCapture(note.value, msg); });
      bar.appendChild(note); bar.appendChild(b); bar.appendChild(msg);
      pane.appendChild(bar);
      var t = table(["Capture", "Date", "Captured", "By", "Peak PFE", "EPE", "Note", ""], [4, 5]), tb = t.tBodies[0];
      if (!list.length) { var tr0 = document.createElement("tr"); cell(tr0, "Nothing captured for this scope yet.", "xx-note").colSpan = 8; tb.appendChild(tr0); }
      list.forEach(function (c) {
        var tr = document.createElement("tr");
        tr.dataset.capture = c.id;
        cell(tr, c.id, "mono"); cell(tr, c.businessDate, "mono"); cell(tr, String(c.capturedAt || "").replace("T", " ").slice(0, 19), "mono");
        cell(tr, c.owner || ""); cell(tr, m(c.peakPfe), "n"); cell(tr, m(c.epe), "n"); cell(tr, c.note || "");
        var act = cell(tr, "");
        var cmp = h("button", "xx-link", "Compare with now");
        cmp.type = "button";
        cmp.addEventListener("click", function () { compare(box, c.id, "now"); });
        act.appendChild(cmp);
        act.appendChild(document.createTextNode(" "));
        var csv = h("a", null, "CSV");
        csv.href = "/api/exposure/captures/" + encodeURIComponent(c.id) + "/csv";
        act.appendChild(csv);
        act.appendChild(document.createTextNode(" "));
        var jsonl = h("a", null, "JSONL");
        jsonl.href = "/api/exposure/captures/" + encodeURIComponent(c.id) + "/jsonl";
        act.appendChild(jsonl);
        tb.appendChild(tr);
      });
      pane.appendChild(wrap(t));
      var box = h("div", "xx-compare");
      box.dataset.xxCompare = "1";
      pane.appendChild(box);
      if (list.length >= 2) {
        var two = h("button", "xx-link", "Compare the two newest");
        two.type = "button";
        two.addEventListener("click", function () { compare(box, list[1].id, list[0].id); });
        pane.insertBefore(h("p", "xx-pager"), box).appendChild(two);
      }
    });
  }

  function doCapture(note, msg) {
    if (msg) msg.textContent = "capturing…";
    send("captures", { scope: S.head.canonical, note: note || "", q: S.q, collateral: S.collateral }).then(function (c) {
      root.dataset.lastCapture = c.id;
      if (msg) msg.textContent = "Captured " + c.id + ": peak PFE " + m(c.peakPfe) + ", " + int(c.memberCount) + " trades.";
      if (S.tab !== "captures") S.tab = "captures";
      renderTabs();
    }).catch(function (e) { if (msg) msg.textContent = e.message; });
  }

  function compare(box, a, b) {
    box.textContent = "comparing…";
    api("compare", { a: a, b: b }, { fresh: true, plain: true }).then(function (d) {
      box.textContent = "";
      box.dataset.compared = a + "|" + b;
      box.appendChild(h("h3", "xx-note", "Compare " + d.a.id + " (" + d.a.businessDate + ") with " + (d.b.id === "now" ? "now" : d.b.id) + " (" + d.b.businessDate + ")"));
      var t = table(["", d.a.id, d.b.id === "now" ? "now" : d.b.id, "Change"], [1, 2, 3]), tb = t.tBodies[0];
      [["Peak PFE", "peakPfe"], ["EPE", "epe"], ["EEPE", "eepe"], ["Peak EE", "peakEe"], ["CE", "ce"]].forEach(function (f) {
        var tr = document.createElement("tr");
        cell(tr, f[0]); cell(tr, m(d.a[f[1]]), "n"); cell(tr, m(d.b[f[1]]), "n"); cell(tr, m(d.change[f[1]]), "n");
        tb.appendChild(tr);
      });
      (d.pfeProfile || []).forEach(function (r) {
        var tr = document.createElement("tr");
        cell(tr, "PFE " + r.tenor); cell(tr, m(r.a), "n"); cell(tr, m(r.b), "n"); cell(tr, m(r.change), "n");
        tb.appendChild(tr);
      });
      box.appendChild(wrap(t));
      if ((d.membersAdded || []).length || (d.membersRemoved || []).length) {
        box.appendChild(h("p", "xx-note after", "Trades added: " + (d.membersAdded.join(", ") || "none") + ". Removed: " + (d.membersRemoved.join(", ") || "none") + "."));
      }
    }).catch(function (e) { errorBox(box, e); });
  }

  var PANES = { board: board, les: les, sets: sets, tenor: tenor, coll: coll, trades: trades, predeal: predeal, inset: inset, captures: captures };

  // ---------------------------------------------------------------------------------------------------------------- the tree
  var treeWaiters = [];
  function treeReady() {
    if (S.tree) return Promise.resolve(S.tree);
    return new Promise(function (ok) { treeWaiters.push(ok); if (!S.treeLoading) loadTree(); });
  }

  function loadTree() {
    S.treeLoading = true;
    return api("tree", {}, { fresh: true }).then(function (d) {
      S.treeLoading = false;
      S.tree = d;
      if (!S.head) $("[data-xx-meta]").textContent = "run " + d.runId + " · " + (d.tenors || []).length + " tenors · " + d.businessDate;
      d.t = (S.head && S.head.profile ? S.head.profile.map(function (r) { return r.t; }) : null);
      renderTree();
      treeWaiters.splice(0).forEach(function (f) { f(d); });
    }).catch(function (e) { S.treeLoading = false; errorBox($("[data-xx-tree]"), e); treeWaiters.splice(0).forEach(function (f) { f(null); }); });
  }

  /** The keys of the rows open along the current scope: the bank, its legal entity, its counterparty, its netting set. */
  function openPath() {
    var keys = { BANK: true }, p = path(S.head);
    var le = null, cp = null;
    p.concat([{ scope: S.scope, kind: S.head ? S.head.kind : "" }]).forEach(function (x) {
      if (/^LE:/.test(x.scope)) { le = x.scope; keys[le] = true; }
      if (/^CPTY:/.test(x.scope)) { cp = x.scope; keys[(le || "") + "|" + cp] = true; }
      if (/^NSET:/.test(x.scope)) keys[(le || "") + "|" + (cp || "") + "|" + x.scope] = true;
    });
    return keys;
  }

  function treeRow(t, depth, lvl, label, scope, value, opts) {
    opts = opts || {};
    var row = h("div", "xx-row" + (opts.noPaths ? " nopaths" : ""));
    row.setAttribute("role", "treeitem");
    row.tabIndex = 0;
    row.dataset.scope = scope;
    row.style.paddingLeft = (6 + depth * 12) + "px";
    row.title = words(scope);
    if (opts.key) {
      var tw = h("button", "tw", opts.open ? "▾" : "▸");
      tw.type = "button";
      tw.tabIndex = -1;
      tw.setAttribute("aria-label", opts.open ? "Collapse" : "Expand");
      row.setAttribute("aria-expanded", opts.open ? "true" : "false");
      tw.addEventListener("click", function (ev) { ev.stopPropagation(); S.expanded[opts.key] = !opts.open; renderTree(); });
      row.appendChild(tw);
    } else row.appendChild(h("span", "tw", "·"));
    row.appendChild(h("span", "lvl", lvl));
    row.appendChild(h("span", "nm", label));
    row.appendChild(h("span", "n", opts.noPaths ? "no paths" : value === null || value === undefined ? "" : m(value)));
    row.addEventListener("click", function () { select(scope, { via: opts.via }); });
    row.addEventListener("keydown", function (ev) { if (ev.key === "Enter") select(scope, { via: opts.via }); });
    t.appendChild(row);
  }

  function renderTree() {
    var t = $("[data-xx-tree]"), d = S.tree;
    if (!d) return;
    if (!d.t && S.head && S.head.profile) d.t = S.head.profile.map(function (r) { return r.t; });
    t.textContent = "";
    var open = openPath(), cps = counterparties();
    var isOpen = function (k) { return S.expanded[k] === undefined ? !!open[k] : S.expanded[k]; };
    treeRow(t, 0, "BANK", "Bank", "BANK", (d.bankNode || {}).peakPfe, {});
    (d.legalEntities || []).forEach(function (le) {
      var leOpen = isOpen(le.scope);
      treeRow(t, 1, "LE", le.label, le.scope, le.peakPfe, { key: le.scope, open: leOpen });
      if (!leOpen) return;
      cps.filter(function (cp) { return (cp.legalEntities || []).indexOf(le.id) >= 0; })
        .sort(function (a, b) { return (b.peakPfe || -1) - (a.peakPfe || -1); }).forEach(function (cp) {
          var k = le.scope + "|" + cp.scope, cpOpen = isOpen(k);
          treeRow(t, 2, "CPTY", cp.label, cp.scope, cp.peakPfe, { key: k, open: cpOpen, noPaths: cp.noPaths, via: le.scope });
          if (!cpOpen) return;
          (cp.children || []).filter(function (s) { return s.legalEntity === le.id; }).forEach(function (s) {
            var sk = k + "|" + s.scope, sOpen = !s.noPaths && isOpen(sk);
            treeRow(t, 3, "NSET", s.id, s.scope, s.peakPfe, { key: s.noPaths ? null : sk, open: sOpen, noPaths: s.noPaths });
            if (sOpen) treeTrades(t, s.scope);
          });
        });
    });
    markTree();
  }

  /** The open netting set's trades below its row (from its contributions, largest EE contribution first; the rest in the Trades tab). */
  function treeTrades(t, set) {
    var key = "/api/exposure/scope?" + query({ scope: set });
    var holder = h("div");
    t.appendChild(holder);
    setTrades(set).then(function (c) {
      var rows = c.rows || [], top = rows.slice(0, 30);
      rows.slice(30).forEach(function (r) { if ("TRD:" + r.key === S.scope) top.push(r); });          // the open trade, wherever it ranks
      top.forEach(function (r) { treeRow(holder, 4, "TRD", r.key, "TRD:" + r.key, r.peakPfeAlone, {}); });
      if (rows.length > 30) holder.appendChild(h("div", "xx-row muted", "… " + (rows.length - 30) + " more in the Trades tab"));
      markTree();
    }).catch(function () { holder.appendChild(h("div", "xx-row muted", "trades: not for your role")); });
    void key;
  }

  function markTree() {
    root.querySelectorAll(".xx-row[data-scope]").forEach(function (r) { r.setAttribute("aria-current", r.dataset.scope === S.scope ? "true" : "false"); });
  }

  // ---------------------------------------------------------------------------------------------------------------- switches, back, start
  function goBack() {
    var prev = S.back.pop();
    if (prev) select(prev, { back: true });
  }

  function switches() {
    root.querySelectorAll("[data-xx-q]").forEach(function (b) {
      b.setAttribute("aria-pressed", Number(b.dataset.xxQ) === S.q ? "true" : "false");
      b.onclick = function () { S.q = Number(b.dataset.xxQ); persist(); switches(); S.tree = null; loadTree(); select(S.scope, { noPush: true }); };
    });
    root.querySelectorAll("[data-xx-coll]").forEach(function (b) {
      b.setAttribute("aria-pressed", b.dataset.xxColl === S.collateral ? "true" : "false");
      b.onclick = function () { S.collateral = b.dataset.xxColl; persist(); switches(); select(S.scope, { noPush: true }); };
    });
  }
  function persist() {
    var u = new URL(location.href);
    if (S.q !== 0.95) u.searchParams.set("q", S.q); else u.searchParams.delete("q");
    if (S.collateral !== "lagged") u.searchParams.set("collateral", S.collateral); else u.searchParams.delete("collateral");
    history.replaceState({ scope: S.scope }, "", u.toString());
  }

  function official() {
    var chip = $("[data-xx-official]");
    api("official", {}, { plain: true }).then(function (o) {
      chip.className = "xx-chip ok";
      chip.textContent = "official " + String(o.computedAt || "").slice(11, 16) + (o.resultVersion > 1 ? " · v" + o.resultVersion : "");
      chip.title = "The official end-of-day capture of " + o.businessDate + " (run " + o.runId + "), published to risk-results";
    }).catch(function (e) {
      chip.textContent = e.status === 403 ? "official: all counterparties only" : "official: not yet";
      chip.title = e.message;
    });
  }

  $("[data-xx-capture-side]").addEventListener("click", function () { doCapture("", null); });
  window.addEventListener("popstate", function (ev) { if (ev.state && ev.state.scope) select(ev.state.scope, { noPush: true, back: true }); });
  document.addEventListener("keydown", function (ev) {
    var tag = (ev.target && ev.target.tagName) || "";
    if (ev.key === "?" && !/INPUT|TEXTAREA|SELECT/.test(tag)) { ev.preventDefault(); location.href = $("[data-xx-help]").href; }
  });
  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (S.head) renderChart(S.head); }, 150);
  });

  history.replaceState({ scope: S.scope }, "", location.href);
  switches();
  official();
  loadTree();
  select(S.scope, { noPush: true });
  window.ExposureExplorer = { select: select, state: S, back: goBack };
})();
