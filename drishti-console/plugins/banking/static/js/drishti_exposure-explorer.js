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
/* The exposure explorer (docs/guides/EXPOSURE_GUIDE.md section 3), after the approved combined mock-up. One page for any scope: the
   tree, the breadcrumb, a row of the counterparty board, a netting set in the contributions or a trade in the Trades tab moves every
   panel to the new scope in place (no page load); Back returns to the scope before. The headline tiles and the profile are always
   shown; the tabs below change with the scope's level: the counterparty board and the legal entities at the bank, the board at a legal
   entity or a group, the netting sets' contributions and the pre-deal what-if (into a netting set it picks) at a counterparty, the netting-set workbench (collateral, trades, pre-deal) at a
   netting set, the trade in its netting set at a trade; By tenor and Captures everywhere. Every view is a command (EXP <scope>) and a
   URL (/exposure?scope=...), so links, the command line and F1 work. The time from a click to the painted headline is kept on the root
   element (data-paint-ms) for the tests. How a figure was built is the one Explain's (drishti_explain.js): hover a tile, click it, or Explain ›. */
(function () {
  "use strict";
  var root = document.querySelector("[data-exposure]");
  if (!root || !window.Drishti.elements.risk) return;
  var R = window.Drishti.elements.risk, F = window.Drishti.elements.core;   // tables and the profile on Drishti Elements (drishti-risk)
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
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; e.body = b; throw e; }
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
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; e.body = b; throw e; }
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
  /** 1.23m, 412k, 2.31bn: the compact money of the mock-up (drishti-core's formats). */
  var m = F.formatter("compact", { decimals: function (a, u) { return u === "bn" ? 2 : u === "m" ? 1 : 0; }, minus: "-" });
  var int = R.riskInt;                                                         // whole units, "—" for none (drishti-risk)
  function pct(v, d) { return v === null || v === undefined || isNaN(v) ? "—" : (v * 100).toFixed(d === undefined ? 1 : d) + "%"; }

  // ---- the tables: every one a <drishti-grid> in the terminal layout (drishti-risk's preset); every figure the server's, as sent
  /** A grid in the pane (its box is in the page before the grid is made, so it measures itself); a row's scope is its data-scope. */
  function gridIn(pane, o) {
    var box = h("div", "xx-grid");
    pane.appendChild(box);
    return R.riskGrid(box, Object.assign({ getRowId: function (r, i) { return r.scope || r.id || String(i); },
      getRowData: function (r) { return r.scope ? { scope: r.scope } : null; },
      getRowClass: function (r) { return (r.scope ? "xx-click" : "") + (r.total ? " xx-total" : ""); } }, o));
  }
  function money(field, header, o) { return R.moneyColumn(field, header, m, Object.assign({ width: 110 }, o)); }   // drishti-risk's money column, the compact rule
  function share(field, header, o) { return R.figureColumn(field, header, Object.assign({ format: function (v) { return pct(v); }, width: 80 }, o || {})); }
  function whole(field, header, o) { return R.figureColumn(field, header, Object.assign({ format: function (v) { return int(v); }, additive: true, width: 90 }, o || {})); }
  function text(field, header, o) { return R.textColumn(field, header, Object.assign({ width: 150 }, o)); }
  /** A small EE line in a row (drishti-core's sparkline). */
  function spark(field) {
    return { id: "spark", field: field, header: "Profile (EE)", width: 124, sortable: false, filterable: false, valueGetter: function () { return ""; },
      cellRenderer: function (c) { var v = c.row[field]; return v && v.length ? F.sparkline(document, v, undefined, "xx") : null; } };
  }
  function useClass(u) { return u > 1 ? "crit" : u > 0.9 ? "warn" : "ok"; }
  function errorBox(host, e, retry) {
    var busy = window.Drishti.Busy && window.Drishti.Busy.problem(e);
    if (busy) {                                   // busy protection: the calm message in this panel, a countdown, tried again by itself
      window.Drishti.Busy.render(host, busy, { retry: retry || function () { select(S.scope, { noPush: true }); },
        keep: "the dates already open keep working." });
      return;
    }
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
      ["[data-xx-tiles]", ".xx-chartbox", "[data-xx-actions]"].forEach(function (s) { $(s).hidden = false; });
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
      document.dispatchEvent(new CustomEvent("drishti:exposure-painted", { detail: { scope: scope, ms: ms } }));
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
    ["[data-xx-tiles]", ".xx-chartbox", "[data-xx-actions]"].forEach(function (s) { $(s).hidden = true; });
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
    bindExplain(d);
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

  /**
   * The profile: drishti-risk's exposure profile (EE and PFE over the tenors, EEPE over the first year, the counterparty's limit when it
   * is at the page's confidence), with the uncollateralised EE and, on a netting set, the paths' 5–95% and 25–75% lines. Every figure is
   * the server's (EEPE and the peak included); the page only draws them.
   */
  function renderChart(d) {
    var prof = d.profile || [], set = d.kind === "netting-set";
    var at = 0;
    prof.forEach(function (r, i) { if (r.pfe > prof[at].pfe) at = i; });
    var log = prof.every(function (r) { return r.t > 0; });
    var draw = function (bands) {
      var rows = prof.map(function (r, i) {
        var x = { id: r.tenor, tenor: r.tenor, date: r.date, t: r.t, ee: r.ee, pfe: r.pfe, eeUncoll: r.eeUncoll };
        if (bands && bands[i]) { x.q5 = bands[i].q5; x.q25 = bands[i].q25; x.q75 = bands[i].q75; x.q95 = bands[i].q95; }
        return x;
      });
      var more = [{ field: "eeUncoll", name: "EE uncollateralised", tone: "muted", style: "dashed", opacity: 0.001 }];
      if (bands) {
        more.push({ field: "q95", name: "paths 95%", tone: "accent", style: "dotted", opacity: 0.001 }, { field: "q75", name: "paths 75%", tone: "accent", style: "dashed", opacity: 0.001 },
          { field: "q25", name: "paths 25%", tone: "accent", style: "dashed", opacity: 0.001 }, { field: "q5", name: "paths 5%", tone: "accent", style: "dotted", opacity: 0.001 });
      }
      var lim = d.limit && Math.abs(d.limit.confidence - S.q) < 1e-9 ? d.limit.limit : undefined;
      var peak = prof[at];
      R.riskChart($("[data-xx-chart]"), { kind: "risk.exposure-profile", title: "Exposure profile", subtitle: d.label + " · " + d.businessDate,
        data: { rows: rows }, id: "id", x: "t", ee: "ee", pfe: "pfe", pfeTitle: "PFE " + qLabel(), eepe: d.eepe, limit: lim, log: log, more: more,
        y: { format: "compact" },
        annotations: peak ? [{ type: "point", x: peak.t, y: peak.pfe, tone: "accent", label: "peak " + m(peak.pfe) + " · " + peak.tenor }] : [] }, { height: 230 });   // the drawing 230 px, the card's title and legend around it
      root.dataset.peak = peak ? peak.tenor : "";
    };
    draw(null);
    $("[data-xx-fan-legend]").hidden = true;
    if (set) {
      var seq = S.seq;
      api("scope/distribution", { scope: d.canonical, bands: "true" }).then(function (x) {
        if (seq !== S.seq) return;
        var b = x.bands || [];
        draw(b);
        root.dataset.fan = String(b.length);
        $("[data-xx-fan-legend]").hidden = !b.length;
      }).catch(function () { /* the profile stands without its fan */ });
    }
  }

  /** The one Explain (SHARED_SCREENS.md section 3, drishti_explain.js): each figure tile and Explain › carry their figure's request. */
  function bindExplain(d) {
    var req = function (measure) {
      var r = { kind: "exposure", measure: measure, scope: d.canonical, q: S.q, collateral: S.collateral };
      if (S.asOf) r.date = S.asOf;
      return r;
    };
    var b = $("[data-xx-explain]");
    if (b) b.setAttribute("data-explain", JSON.stringify(req("peak-pfe")));
    if (!window.Drishti.Explain || level(d) === "trade") return;
    ["peak-pfe", "epe", "eepe", "ead"].forEach(function (m, i) {
      var v = $('[data-xx-tiles] [data-tile="' + i + '"] .v');
      if (v) window.Drishti.Explain.bind(v, req(m));
    });
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
      var rows = shown.map(function (cp) {
        var l = cp.limit;
        return { scope: cp.scope, label: cp.label, ee: cp.ee, peakPfe: cp.peakPfe, epe: cp.epe, limit: l ? l.limit : null, use: l ? l.use : null, lim: l,
          sets: (cp.children || []).filter(function (s) { return !s.noPaths; }).length };
      });
      gridIn(pane, { title: "Counterparties", label: "Counterparties by peak PFE", rows: rows, fitRows: PAGE + 1,
        columns: [text("label", "Counterparty", { width: 200, pinned: "left" }), spark("ee"), money("peakPfe", "Peak PFE"), money("epe", "EPE", { additive: true }),
          money("limit", "Limit"),
          { id: "bar", field: "use", header: "Used", width: 84, sortable: false, valueGetter: function () { return ""; }, cellRenderer: function (c) {
            var l = c.row.lim;
            if (!l) return null;
            var b = h("span", "xx-bar"), i = h("i", useClass(l.use));
            i.style.width = Math.min(100, l.use * 100) + "%";
            b.appendChild(i);
            b.title = pct(l.use, 0) + " of the stored limit " + l.id + " (" + (l.measure || "") + ")";
            return b;
          } },
          share("use", "Use", { id: "chip", format: function (v) { return v === null || v === undefined ? "no limit" : pct(v, 0); },
            cellRenderer: function (c) { return c.row.lim ? h("span", "xx-chip " + useClass(c.row.use), pct(c.row.use, 0)) : h("span", "muted", "no limit"); } }),
          whole("sets", "Sets", { width: 64 })],
        onRow: function (r) { select(r.scope, { via: lev === "legal-entity" ? d.canonical : null }); } });
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
      var rows = (S.tree.legalEntities || []).slice().sort(function (a, b) { return (b.peakPfe || 0) - (a.peakPfe || 0); }).map(function (le) {
        return { scope: le.scope, label: le.label, ee: le.ee, peakPfe: le.peakPfe, note: le.note, epe: le.epe, nettingSets: le.nettingSets,
          share: bank.peakPfe ? le.peakPfe / bank.peakPfe : null };       // a ratio of two of the server's peaks, for reading only
      });
      gridIn(pane, { title: "Legal entities", rows: rows,
        columns: [text("label", "Legal entity", { width: 200, pinned: "left" }), spark("ee"),
          money("peakPfe", "Peak PFE", { format: function (v, r) { return r && r.note ? "batch" : m(v); } }), money("epe", "EPE", { additive: true }),
          whole("nettingSets", "Netting sets"), share("share", "Share of bank peak", { width: 130 })],
        onRow: function (r) { select(r.scope); } });
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
      // the contributions are the server's, scaled to add up to the scope's peak PFE: the total row is the server's peak, never a sum here
      var list = rows.map(function (r) {
        var n = setNode(r.nettingSet) || {};
        return { scope: "NSET:" + r.nettingSet, label: r.nettingSet + (r.partial ? " (part)" : ""), csa: n.csa || "", ee: n.ee, epe: r.epe, peakPfe: r.peakPfe,
          pfeContribution: r.pfeContribution, share: d.peakPfe ? r.pfeContribution / d.peakPfe : null };
      });
      list.push({ id: "TOTAL", total: true, label: "Total", pfeContribution: d.peakPfe, share: d.peakPfe ? 1 : null });
      gridIn(pane, { title: "Netting sets", label: "Contributions of the netting sets to the peak PFE", rows: list,
        columns: [text("label", "Netting set", { width: 190, pinned: "left" }), text("csa", "CSA", { width: 110 }), spark("ee"), money("epe", "EPE"),
          money("peakPfe", "Peak PFE alone", { width: 120, format: function (v, r) { return r && r.total ? "" : m(v); } }),
          money("pfeContribution", "PFE contribution", { width: 130 }), share("share", "Share")],
        onRow: function (r) { if (r.scope) select(r.scope); } });
    });
  }

  function tenor(pane, d) {
    pane.textContent = "";
    var prof = d.profile || [];
    tenorGrid(pane, "By tenor", prof.map(function (r) { return r.tenor; }),
      [["EE", "ee"], ["ENE", "ene"], ["EEE", "eee"], ["PFE " + qLabel(), "pfe"], ["PFE 99%", "pfe99"], ["EE uncollateralised", "eeUncoll"]].map(function (f) {
        return { id: f[1], figure: f[0], values: prof.map(function (r) { return r[f[1]]; }) };
      }), int);
    var p = h("p", "xx-note after", "USD. CSV of the profile: ");
    var a = h("a", null, "download");
    a.href = "/api/exposure/scope/profile?" + query({ scope: d.canonical, format: "csv" });
    p.appendChild(a);
    p.appendChild(document.createTextNode(" · "));
    var aj = h("a", null, "JSON Lines");
    aj.href = "/api/exposure/scope/profile?" + query({ scope: d.canonical, format: "jsonl" });
    p.appendChild(aj);
    p.appendChild(document.createTextNode(" · drishti.py exposure profile '" + d.canonical + "' --csv (or --jsonl) · GET /api/v1/risk/exposure/scope/profile?format=csv"));
    pane.appendChild(p);
  }

  /** A figure per row and a tenor per column (By tenor, a trade in its netting set): rows {id, figure, values[]}, fmt each cell's words. */
  function tenorGrid(pane, title, tenors, rows, fmt) {
    var data = rows.map(function (r) { var x = { id: r.id, figure: r.figure }; r.values.forEach(function (v, i) { x["t" + i] = v; }); return x; });
    return gridIn(pane, { title: title, rows: data,
      columns: [text("figure", "Figure", { width: 170, pinned: "left" })].concat(tenors.map(function (t, i) {
        return R.figureColumn("t" + i, t, { width: 92, format: function (v, r) { return fmt(v, r, i); } });
      })) });
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
      gridIn(pane, { title: "Trades", label: "The netting set's trades by EE contribution", fitRows: PAGE,
        rows: rows.slice(S.page * PAGE, (S.page + 1) * PAGE).map(function (r) {
          return { scope: "TRD:" + r.key, key: r.key, product: r.product || "", desk: r.desk || "", value: r.value, pct: r.pct, peakPfeAlone: r.peakPfeAlone };
        }),
        columns: [text("key", "Trade", { width: 150, pinned: "left", cellRenderer: function (c) { return trdLink(c.row.key); } }), text("product", "Product", { width: 130 }),
          text("desk", "Desk", { width: 130 }), money("value", "EE contribution", { width: 120 }), share("pct", "Share"), money("peakPfeAlone", "Peak PFE alone", { width: 120 })],
        onRow: function (r) { select(r.scope); } });
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
        + "netted and collateralised with the set, path by path, by the exposure engine; addon: a netting set of its own, uncollateralised, "
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
    gridIn(out, { title: "What-if by tenor", fitRows: 40, rows: prof.map(function (r) { return Object.assign({ id: r.tenor }, r); }),
      columns: [text("tenor", "Tenor", { width: 80, pinned: "left" }), money("eeBase", "EE before"), money("ee", "EE after"), money("pfeBase", "PFE before"),
        money("pfe", "PFE after"), money("incrementalPfe", "Incremental PFE", { width: 130, tone: function (v) { return v > 0 ? "warn" : null; } })] });
    out.appendChild(h("p", "xx-note after", w.note || ""));
  }

  function inset(pane, d) {
    var set = "NSET:" + d.trade.nettingSet;
    return Promise.all([setTrades(set), api("scope", { scope: set })]).then(function (x) {
      pane.textContent = "";
      var me = (x[0].rows || []).filter(function (r) { return r.key === d.trade.id; })[0], sp = x[1].profile || [];
      pane.appendChild(h("p", "xx-note", "The chart above is the trade alone. Its contribution to " + d.trade.nettingSet + " after netting and "
        + "collateral, by tenor (the shares of the set's trades add up to the set's EE at every date):"));
      var prof = me && me.profile ? me.profile : [];
      tenorGrid(pane, "In its netting set", sp.map(function (r) { return r.tenor; }), [
        { id: "contribution", figure: "EE contribution", values: sp.map(function (_, i) { return prof[i]; }) },
        { id: "set", figure: "Set EE", values: sp.map(function (r) { return r.ee; }) },
        { id: "share", figure: "Share of set EE", values: sp.map(function (r, i) { return r.ee ? prof[i] / r.ee : null; }) }],   // a ratio for reading
        function (v, r) { return r && r.id === "share" ? (v === null || v === undefined ? "—" : pct(v)) : int(v); });
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
      var box = h("div", "xx-compare");
      if (!list.length) pane.appendChild(h("p", "xx-note", "Nothing captured for this scope yet."));
      else gridIn(pane, { title: "Captures", rows: list.map(function (c) {
          return { id: c.id, businessDate: c.businessDate, capturedAt: String(c.capturedAt || "").replace("T", " ").slice(0, 19), owner: c.owner || "",
            peakPfe: c.peakPfe, epe: c.epe, note: c.note || "" };
        }),
        getRowData: function (r) { return { capture: r.id }; },
        columns: [text("id", "Capture", { width: 170, pinned: "left" }), text("businessDate", "Date", { width: 100 }), text("capturedAt", "Captured", { width: 150 }),
          text("owner", "By", { width: 100 }), money("peakPfe", "Peak PFE"), money("epe", "EPE"), text("note", "Note", { width: 180 }),
          { id: "act", header: "Actions", width: 230, sortable: false, filterable: false, valueGetter: function () { return ""; }, cellRenderer: function (c) {
            var act = h("span"), id = c.row.id;
            var cmp = h("button", "dg-cell-btn", "Compare with now");
            cmp.type = "button";
            cmp.addEventListener("click", function () { compare(box, id, "now"); });
            act.appendChild(cmp);
            act.appendChild(document.createTextNode(" "));
            var csv = h("a", null, "CSV");
            csv.href = "/api/exposure/captures/" + encodeURIComponent(id) + "/csv";
            act.appendChild(csv);
            act.appendChild(document.createTextNode(" "));
            var jsonl = h("a", null, "JSONL");
            jsonl.href = "/api/exposure/captures/" + encodeURIComponent(id) + "/jsonl";
            act.appendChild(jsonl);
            return act;
          } }] });
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
      var rows = [["Peak PFE", "peakPfe"], ["EPE", "epe"], ["EEPE", "eepe"], ["Peak EE", "peakEe"], ["CE", "ce"]].map(function (f) {
        return { id: f[1], figure: f[0], a: d.a[f[1]], b: d.b[f[1]], change: d.change[f[1]] };
      }).concat((d.pfeProfile || []).map(function (r) { return { id: "pfe-" + r.tenor, figure: "PFE " + r.tenor, a: r.a, b: r.b, change: r.change }; }));
      gridIn(box, { title: "Compare", fitRows: 40, rows: rows,
        columns: [text("figure", "Figure", { width: 130, pinned: "left" }), money("a", d.a.id, { width: 150 }), money("b", d.b.id === "now" ? "now" : d.b.id, { width: 150 }),
          money("change", "Change", { width: 110 })] });
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
    }).catch(function (e) { S.treeLoading = false; errorBox($("[data-xx-tree]"), e, loadTree); treeWaiters.splice(0).forEach(function (f) { f(null); }); });
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
  // ? is the keymap's list of keys (drishti_keys.js); F1 and the ? button open the exposure guide; the chart and the grids follow
  // their own size (drishti-chart, drishti-grid)

  history.replaceState({ scope: S.scope }, "", location.href);
  switches();
  official();
  loadTree();
  select(S.scope, { noPush: true });
  window.Drishti.ExposureExplorer = { select: select, state: S, back: goBack };
})();
