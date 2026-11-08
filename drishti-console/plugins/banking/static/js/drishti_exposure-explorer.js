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
   entity or a group, the netting sets' contributions at a counterparty, the netting-set workbench (collateral, trades) at a netting set
   (both with a link to Counterparties → Pre-deal check, the one place a new trade is checked), the trade in its netting set at a trade; By tenor and Captures everywhere. Every view is a command (EXP <scope>) and a
   URL (/exposure?scope=...), so links, the command line and F1 work. The time from a click to the painted headline is kept on the root
   element (data-paint-ms) for the tests. How a figure was built is the one Explain's (drishti-insight): hover a tile, click it, or Explain ›. */
(function () {
  "use strict";
  var root = document.querySelector("[data-exposure]");
  if (!root || !window.Drishti.elements.risk) return;
  var R = window.Drishti.elements.risk, F = window.Drishti.elements.core;   // tables and the profile on Drishti Elements (drishti-risk)
  var $ = function (sel) { return root.querySelector(sel); };
  var params = new URLSearchParams(location.search);
  var S = { scope: root.dataset.scope || "BANK", q: Number(params.get("q")) || 0.95, collateral: params.get("collateral") || "lagged",
            asOf: params.get("asOf") || "", tab: null, tree: null, head: null, seq: 0, back: [], via: {}, page: 0, board: 25,
            user: root.dataset.user || "" };
  var cache = new Map();
  var retired = params.get("from") || "";
  var TABS = {
    bank: [["board", "Counterparties"], ["les", "Legal entities"], ["tenor", "By tenor"]],
    "legal-entity": [["board", "Counterparties"], ["tenor", "By tenor"]],
    group: [["board", "Counterparties"], ["tenor", "By tenor"]],
    counterparty: [["sets", "Netting sets"], ["tenor", "By tenor"]],
    "netting-set": [["coll", "Collateral"], ["trades", "Trades"], ["tenor", "By tenor"]],
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
  /** The tone of a credit limit's status as the server says it (within, near by drishti.risk.limit-near, breach); none without one. */
  var USE_TONE = { within: "ok", near: "warn", breach: "crit" };
  function useClass(status) { return (status && USE_TONE[status]) || ""; }
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
      if (S.tree) showPath();                                                 // the way to the new scope opens again
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
      treeReady().then(function () { if (seq !== S.seq) return; renderCrumb(null); showPath(); });
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
          useClass(d.limit.status)]);
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

  /** The one Explain (SHARED_SCREENS.md section 3, drishti-insight): each figure tile and Explain › carry their figure's request. */
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
    predealLink();
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

  /** Counterparties → Pre-deal check, from a counterparty or a netting set (the explorer's old Pre-deal tab moved there). */
  function predealLink() {
    var a = $("[data-xx-predeal]"), lev = level(S.head), id = S.head ? S.head.canonical.slice(S.head.canonical.indexOf(":") + 1) : "";
    if (!a) return;
    a.hidden = lev !== "counterparty" && lev !== "netting-set";
    a.href = "/predeal?" + (lev === "counterparty" ? "counterparty=" : "nettingSet=") + encodeURIComponent(id);
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
            var b = h("span", "xx-bar"), i = h("i", useClass(l.status));
            i.style.width = Math.min(100, l.use * 100) + "%";
            b.appendChild(i);
            b.title = pct(l.use, 0) + " of the stored limit " + l.id + " (" + (l.measure || "") + ")";
            return b;
          } },
          share("use", "Use", { id: "chip", format: function (v) { return v === null || v === undefined ? "no limit" : pct(v, 0); },
            cellRenderer: function (c) { return c.row.lim ? h("span", "xx-chip " + useClass(c.row.lim.status), pct(c.row.use, 0)) : h("span", "muted", "no limit"); } }),
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
      if (S.tree) markTree();
    });
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

  var PANES = { board: board, les: les, sets: sets, tenor: tenor, coll: coll, trades: trades, inset: inset, captures: captures };

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

  /** The ids of the tree's nodes along the current scope: the bank, its legal entity, its counterparty, its netting set (a node's id is
      the path of scopes from the legal entity down, "|" between, so a counterparty under two legal entities is two nodes). */
  function openPath() {
    var keys = ["BANK"], p = path(S.head);
    var le = null, cp = null;
    p.concat([{ scope: S.scope, kind: S.head ? S.head.kind : "" }]).forEach(function (x) {
      if (/^LE:/.test(x.scope)) { le = x.scope; keys.push(le); }
      if (/^CPTY:/.test(x.scope)) { cp = x.scope; keys.push((le || "") + "|" + cp); }
      if (/^NSET:/.test(x.scope)) keys.push((le || "") + "|" + (cp || "") + "|" + x.scope);
    });
    return keys;
  }

  // The tree is drishti-tree (its tree grid, chrome off, inside this panel): the bank, its legal entities, their counterparties, their
  // netting sets and a set's trades (asked the first time its row opens: the 30 largest by EE contribution, the open trade wherever it
  // ranks, the rest in the Trades tab). Peak PFE is not additive: every figure is the server's for that node, never a sum of its children.
  function treeRows() {
    var d = S.tree, cps = counterparties(), rows = [];
    function row(id, parent, lvl, label, scope, value, more) {
      rows.push(Object.assign({ ID: id, PARENT: parent, LABEL: label, LEVEL: lvl, scope: scope, PFE: value === undefined ? null : value }, more || {}));
    }
    row("BANK", null, "BANK", "Bank", "BANK", (d.bankNode || {}).peakPfe);
    (d.legalEntities || []).forEach(function (le) {
      row(le.scope, "BANK", "LE", le.label, le.scope, le.peakPfe);
      cps.filter(function (cp) { return (cp.legalEntities || []).indexOf(le.id) >= 0; })
        .sort(function (a, b) { return (b.peakPfe || -1) - (a.peakPfe || -1); }).forEach(function (cp) {
          var k = le.scope + "|" + cp.scope;
          row(k, le.scope, "CPTY", cp.label, cp.scope, cp.peakPfe, { noPaths: !!cp.noPaths, via: le.scope });
          (cp.children || []).filter(function (st) { return st.legalEntity === le.id; }).forEach(function (st) {
            row(k + "|" + st.scope, k, "NSET", st.id, st.scope, st.peakPfe, { noPaths: !!st.noPaths, lazy: !st.noPaths });
          });
        });
    });
    return rows;
  }

  /** drishti-tree's loadChildren: a netting set's trades. */
  function loadTrades(req) {
    return setTrades(req.data.scope).then(function (c) {
      var rows = c.rows || [], top = rows.slice(0, 30);
      rows.slice(30).forEach(function (r) { if ("TRD:" + r.key === S.scope) top.push(r); });          // the open trade, wherever it ranks
      var out = top.map(function (r) { return { ID: req.id + "|TRD:" + r.key, PARENT: req.id, LABEL: r.key, LEVEL: "TRD", scope: "TRD:" + r.key, PFE: r.peakPfeAlone }; });
      if (rows.length > 30) out.push({ ID: req.id + "|more", PARENT: req.id, LABEL: "… " + (rows.length - 30) + " more in the Trades tab", LEVEL: "", scope: null, PFE: null });
      setTimeout(markTree, 0);                                                  // the open trade's row marked once it is in
      return out;
    }, function () { throw new Error("trades: not for your role"); });
  }

  /** The node's cell: its level and its name. */
  function nodeCell(c) {
    var r = c.row, s = h("span", "nm");
    if (r.LEVEL) s.appendChild(h("span", "lvl", r.LEVEL));
    s.appendChild(document.createTextNode(r.LABEL));
    s.title = r.scope ? words(r.scope) : "";
    return s;
  }

  function renderTree() {
    var t = $("[data-xx-tree]"), d = S.tree;
    if (!d) return;
    if (!d.t && S.head && S.head.profile) d.t = S.head.profile.map(function (r) { return r.t; });
    var data = { rows: treeRows(), idField: "ID", parentField: "PARENT", labelField: "LABEL", kindField: "LEVEL",
      measures: [{ field: "PFE", label: "Peak PFE", format: m, additive: false, column: { width: 84, format: function (v, r) { return r && r.noPaths ? "no paths" : v === null || v === undefined ? "" : m(v); } } }] };
    var el = t.querySelector(":scope > drishti-tree");
    if (el && el.tree) { el.tree.setData(data); showPath(); return; }
    t.textContent = "";
    el = document.createElement("drishti-tree");
    t.appendChild(el);
    el.options = { data: data, title: "Scope", label: "Scopes", chrome: false, mode: "grid", density: "terminal", gridLayout: "terminal", openDepth: 1,
      lazyText: "trades…", loadChildren: loadTrades,
      grid: { phoneWidth: 1, labelColumn: { header: "Scope", width: 200, cellRenderer: nodeCell },
        rowClass: function (r) { return (r.noPaths ? "xx-nopaths" : "") + (r.scope && r.scope === S.scope ? " xx-current" : "") || null; },
        rowData: function (r) { return { scope: r.scope || null, current: r.scope ? String(r.scope === S.scope) : null }; } } };
    el.tree.on("selectionChanged", function (e) {                    // a click on a row: that scope (one without paths: its no-paths state)
      var n = e.ids.length ? el.tree.model.node(e.ids[0]) : null;
      if (n && n.data.scope) select(n.data.scope, { via: n.data.via });
    });
    showPath();
  }

  /** The way to the current scope opened, its row marked. */
  function showPath() {
    var el = $("[data-xx-tree] > drishti-tree"), tree = el && el.tree;
    if (!tree) return;
    openPath().forEach(function (id) { if (tree.model.node(id) && !tree.model.isOpen(id)) tree.toggle(id, true); });
    markTree();
  }

  function markTree() {
    var el = $("[data-xx-tree] > drishti-tree"), tree = el && el.tree;
    if (!tree) return;
    var ids = openPath(), at = null;
    tree.model.all().forEach(function (n) {                                     // the scope's node on the open path (else the first)
      if (n.data.scope !== S.scope) return;
      if (!at || ids.indexOf(n.data.PARENT) >= 0 || ids.indexOf(n.id) >= 0) at = n;
    });
    tree.select(at ? at.id : null, { quiet: true });
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
