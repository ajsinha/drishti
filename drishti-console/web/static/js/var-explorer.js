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

   Ad-hoc scopes and baskets are one thing: "Construct your basket" (var-basket.js), a strip at the top of this page whenever it
   shows an ad-hoc scope or a basket (or the tray holds parts): hints as you type, + / − chips, a rule, "+ basket" on every row of the
   tree and the contributions, the selection's VaR live, a pre-deal what-if, Save (a basket, private or shared read-only) and Open.
   Opening a basket loads its parts back into the tray. "Capture VaR" stores an immutable snapshot that reopens exactly, compares with
   another capture or with now, and exports as CSV.

   The reporting currency (2.9.4, the product owner's mock-up A): a select in the header strip lists the configured currencies and
   "More…" (every currency with scenario FX on the date); the whole page follows it (tiles, path, history, contributions, tree,
   captures, the basket). The choice stays for the session (sessionStorage) and goes into the link (ccy=EUR); VAR ... IN EUR says the
   same. The tab "In other currencies" is loaded only when opened: the scope in each listed currency, a click switches the page.

   The lens (2.9.7, the approved mock-up var-horizon-picker; VAR_GUIDE.md section 18.6): Horizon (1-day, 10-day √10, 10-day sum),
   Confidence and ES beside the currency, in the same selects. A choice redraws every panel (tiles, tree, path, history, contributions,
   captures, other currencies), goes into the link (h=10d&m=sum&q=0.99&esq=0.975) and the command (VAR BANK 10D SUM 97.5), and is kept
   in the person's settings (varView) for the next visit. The method strip under the tiles names the horizon, the method and the
   confidence; limit use shows only when the lens is the limit's own; backtest exceptions only at 1 day. */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  if (!root || !window.VarCharts) return;
  var C = window.VarCharts;
  var $ = function (sel) { return root.querySelector(sel); };
  var params = new URLSearchParams(location.search);
  var CCY_KEY = "drishti.var.ccy";
  function storedCcy() { try { return sessionStorage.getItem(CCY_KEY) || ""; } catch (e) { return ""; } }
  var S = { scope: root.dataset.scope || "BANK", hierarchy: root.dataset.hierarchy || "MGMT", tab: "path", by: "child", page: 0,
            asOf: params.get("asOf") || "", tree: null, expanded: {}, children: {}, head: null, seq: 0, user: root.dataset.user || "",
            ccy: (root.dataset.ccy || params.get("ccy") || storedCcy() || "USD").toUpperCase(),
            // the lens (2.9.7): "" is the default (1-day; the setting's 10-day method; the configured confidences)
            h: root.dataset.h || "", m: root.dataset.m || "", q: root.dataset.q || "", esq: root.dataset.esq || "", lensStatus: null };
  if (S.h !== "10d") { S.h = ""; S.m = ""; }
  var B = window.VarBasket || null;
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
  /**
   * The answers read as tables (Arrow where the page loaded it, JSON otherwise; DATA_FORMATS.md section 6): a contribution table by position
   * or by trade (hundreds to thousands of rows: 61% fewer bytes, painted 11% sooner). The P&L path and the few children of a node stay JSON,
   * where Arrow measured no better.
   */
  function tableOf(path, q) {
    return path === "scope/contributions" && q && (q.by === "position" || q.by === "trade") ? ["records", "rows"] : null;
  }

  /** The answers that take the lens, and the lens as the engine's parameters (2.9.7). */
  var LENS_PATHS = { scope: 1, "scope/path": 1, "scope/history": 1, "scope/contributions": 1, "scope/currencies": 1, tree: 1 };
  function lensParams() {
    var o = {};
    if (S.h === "10d") { o.horizon = "10d"; if (S.m) o.method = S.m; }
    if (S.q) o.confidence = S.q;
    if (S.esq) o.esConfidence = S.esq;
    return o;
  }

  function api(path, q, fresh) {
    var p = new URLSearchParams();
    Object.keys(q || {}).forEach(function (k) { if (q[k] !== undefined && q[k] !== null && q[k] !== "") p.set(k, q[k]); });
    if (LENS_PATHS[path]) { var lp = lensParams(); Object.keys(lp).forEach(function (k) { if (!p.has(k)) p.set(k, lp[k]); }); }
    if (S.asOf) p.set("asOf", S.asOf);
    if (S.ccy && S.ccy !== "USD" && !p.has("ccy")) p.set("ccy", S.ccy);
    var key = path + "?" + p.toString();
    if (!fresh && cache.has(key)) return cache.get(key);
    var table = tableOf(path, q);
    var pr = table && window.drishtiFetchTable
      ? window.drishtiFetchTable("/api/var/" + path + "?" + p.toString(), { shape: table[0], at: table[1] }).then(function (t) {
          var d = t.answer();                          // the same answer as the JSON one, read from the typed columns (2.9.7)
          Object.defineProperty(d, "__table", { value: t, enumerable: false });
          return d;
        }, function (e) { var x = new Error(e.detail || e.message); x.status = e.status; x.code = e.code; throw x; })
      : fetch("/api/var/" + path + "?" + p.toString(), { headers: { Accept: "application/json" }, credentials: "same-origin" })
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
    var qs = new URLSearchParams();
    if (S.asOf) qs.set("asOf", S.asOf);
    if (S.ccy && S.ccy !== "USD") qs.set("ccy", S.ccy);
    return fetch("/api/var/" + path + (qs.toString() ? "?" + qs.toString() : ""), {
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
    return scopeWords(scope) + lensWords() + (S.ccy && S.ccy !== "USD" ? " IN " + S.ccy : "");
  }
  /** The lens as the command says it (2.9.7): " 10D SUM 97.5 ES 95"; nothing for the default. */
  function lensWords() {
    var w = "";
    if (S.h === "10d") w += " 10D" + (S.m === "sum" ? " SUM" : S.m === "sqrt10" ? " SQRT" : "");
    if (S.q) w += " " + pctWord(S.q);
    if (S.esq) w += " ES " + pctWord(S.esq);
    return w;
  }
  function pctWord(f) { return String(Math.round(Number(f) * 10000) / 100); }
  /** The link's lens: h, m, q, esq (the default ones left out). */
  function lensLink(u) {
    [["h", S.h], ["m", S.h === "10d" ? S.m : ""], ["q", S.q], ["esq", S.esq]].forEach(function (x) {
      if (x[1]) u.searchParams.set(x[0], x[1]); else u.searchParams.delete(x[0]);
    });
    return u;
  }
  /** The configured hierarchies' scope words (2.9.5, the tree's hierarchyInfo): hierarchy id to word, LL to LL. */
  function hierWords() {
    var out = {};
    ((S.tree && S.tree.hierarchyInfo) || []).forEach(function (x) { if (x.word) out[x.id] = x.word; });
    return out;
  }
  function scopeWords(scope) {
    var m = /^NODE:([A-Z][A-Z0-9_]*)~(.*)$/.exec(scope);
    var hw = hierWords();
    if (m && hw[m[1]] && m[2] !== "BANK") return "VAR SCOPE " + hw[m[1]] + ":" + m[2];   // a configured hierarchy's node: VAR SCOPE LL:LL-0003
    if (m && (m[1] === "MGMT" || m[1] === "LEGAL")) {
      var n = S.byId && S.byId[m[1] + "~" + m[2]];
      var level = n ? n.level : /^DESK-/.test(m[2]) ? "desk" : /^BOOK-/.test(m[2]) ? "book" : /^DIV-/.test(m[2]) ? "division"
        : /^LE-/.test(m[2]) ? "legal-entity" : "";
      if (m[2] === "BANK") return "VAR BANK";
      var word = { desk: "DESK", book: "BOOK", division: "DIV", "legal-entity": "LE" }[level];
      return word ? "VAR " + word + " " + m[2] : "VAR SCOPE " + scope;
    }
    if (/^BASKET:/.test(scope)) return "VAR SCOPE " + scope.slice(7).replace(new RegExp("^" + S.user + "/"), "");
    if (m) return "VAR SCOPE " + scope;
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
      if (S.ccy && S.ccy !== "USD") u.searchParams.set("ccy", S.ccy); else u.searchParams.delete("ccy");
      lensLink(u);
      history.pushState({ scope: scope, ccy: S.ccy, lens: lensState() }, "", u.toString());
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
      renderNotices(d);
      if (B) B.mirror(d);
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
    if (S.tab === "ccy") return loadCurrencies(seq);
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
      ["VaR · " + (S.h === "10d" ? "10 days" : "1 day"), "ES", "Sum of standalone VaR", "Diversification", "Limit utilisation", "Backtest exceptions"].forEach(function (l) {
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
    var ln = d.lens || {}, sum = ln.method === "SUM", sq = ln.method === "SQRT10", unit = sum ? "windows" : "scenarios";
    $("[data-vx-n]").textContent = "(" + d.scenarios + " " + unit + ")";
    var conf = Math.round(d.confidence * 1000) / 10, esc = Math.round(d.esConfidence * 1000) / 10;
    $("[data-vx-var-legend]").textContent = "VaR " + conf + "% (" + ordinal(d.k) + " worst)";
    $("[data-vx-es-legend]").textContent = "ES " + esc + "% (mean of worst " + d.esCount + ")";
    var bt = d.backtest || {}, u = d.limitUse, cur = d.currency || "USD";
    var days = ln.horizon === "10D" ? "10 days" : "1 day";
    var tiles = [
      ["VaR " + conf + "% · " + days + " · " + cur, d.empty ? "—" : C.money(d["var"]), d.empty ? "no trades: nothing to compute"
        : sq ? "1-day × √10 (3.162) · " + ordinal(d.k) + " worst of " + d.scenarios : ordinal(d.k) + " worst of " + d.scenarios + (sum ? " overlapping windows" : ""), null],
      ["ES " + esc + "% · " + (ln.horizon === "10D" ? "10 days · " : "") + cur, d.empty ? "—" : C.money(d.es), d.empty ? "no trades"
        : sq ? "1-day ES × √10" : "mean of worst " + d.esCount + (sum ? " windows" : ""), null],
      ["Sum of standalone VaR", d.sumStandalone === null ? "—" : C.money(d.sumStandalone), d.childKind ? d.children + " " + d.childKind + (d.children === 1 ? "" : "s") : (d.children ? d.children + " parts" : "no parts"), null],
      ["Diversification", d.diversification === null ? "—" : "−" + C.money(d.diversification),
        d.diversification === null ? "single part" : (d.diversification / d.sumStandalone * 100).toFixed(1) + "% of standalone", null],
      d.limitComparable === false
        ? ["Limit utilisation", "—", d.limitNote || "limit not comparable", "nc"]
        : ["Limit utilisation", u === null || u === undefined ? "—" : (u * 100).toFixed(0) + "%", d.limit ? "limit " + C.money(d.limit) + (cur !== "USD" ? " " + cur + " (set in USD, at today's rate)" : "") : "no limit set", u === null || u === undefined ? null : utilClass(u)],
      bt.applicable === false
        ? ["Backtest exceptions", "—", "backtesting is 1-day: not shown at 10 days", null]
        : ["Backtest exceptions (" + (bt.days || 0) + " d)", String(bt.exceptions === undefined ? "—" : bt.exceptions), (bt.light || "") + " zone · Basel", "light-" + (bt.light || "")]
    ];
    var t = $("[data-vx-tiles]");
    t.textContent = "";
    t.classList.remove("computing");
    tiles.forEach(function (x, i) {
      var tile = h("div", "vx-tile");
      tile.dataset.tile = String(i);
      tile.appendChild(h("div", "l", x[0]));
      var v = h("div", "v mono" + (x[3] && i === 4 && x[3] !== "nc" ? " " + x[3] : ""));
      if (i === 5 && bt.applicable !== false) { v.appendChild(h("span", "vx-light " + (bt.light || ""))); }
      v.appendChild(document.createTextNode(x[1]));
      tile.appendChild(v);
      tile.appendChild(h("div", "s mono" + (x[3] === "nc" ? " vx-nc" : ""), x[2]));
      t.appendChild(tile);
    });
    renderMethod(d);
    syncLensPicker(d.lens);
    renderCrumb(d);
    renderIntraday(d.intraday);
    renderProv(d.provenance, d);
    document.title = "VaR · " + d.label + (document.body.dataset.product ? " · " + document.body.dataset.product : "");
  }

  function ordinal(k) { return k + (k % 100 >= 11 && k % 100 <= 13 ? "th" : k % 10 === 1 ? "st" : k % 10 === 2 ? "nd" : k % 10 === 3 ? "rd" : "th"); }

  /** The method strip (2.9.7): the horizon, its method and the confidences, as the mock-up says them. */
  function renderMethod(d) {
    var el = $("[data-vx-method]"), ln = d.lens || {};
    el.textContent = "";
    root.dataset.lens = (ln.horizon || "1D") + (ln.method ? "-" + ln.method : "") + "|" + d.confidence + "|" + d.esConfidence;
    el.appendChild(h("b", null, "Horizon"));
    el.appendChild(document.createTextNode(" " + (ln.horizon === "10D" ? "10-day" : "1-day") + " "));
    if (ln.method) el.appendChild(h("span", "vx-pill", ln.method === "SUM" ? "sum of days" : "√10"));
    el.appendChild(h("b", null, "Confidence"));
    el.appendChild(document.createTextNode(" VaR " + pctWord(d.confidence) + "%, ES " + pctWord(d.esConfidence) + "%"));
    var why = ln.method === "SUM" ? "each window adds 10 consecutive scenario days' P&L (positions as of today); " + ln.scenarios + " days give "
      + ln.windows + " windows; captures trends and clustered moves; still assumes today's sensitivities for options"
      : ln.method === "SQRT10" ? "the 1-day figures scaled by √10; assumes independent days and a roughly linear book; the P&L path, components and "
      + "contributions are the 1-day ones × √10" : "historical simulation, " + d.scenarios + " scenarios" + (d.provenance && d.provenance.scenarioSet
      ? " (" + String(d.provenance.scenarioSet).split(" · ")[1] + ")" : "") + " · equal weights";
    el.appendChild(h("span", null, "· " + why));
  }

  /** The intraday badge (VAR_GUIDE.md section 8.4): the figures include trades re-sent since the morning run; none, hidden. */
  function renderIntraday(i) {
    var b = $("[data-vx-intraday]");
    if (!i) { b.hidden = true; b.textContent = ""; delete root.dataset.intraday; return; }
    var at = String(i.lastAt || "").replace("T", " ").slice(0, 16);
    b.textContent = "intraday · " + int(i.trades) + " trade" + (i.trades === 1 ? "" : "s") + (at ? " · " + at : "");
    b.title = i.note + ". " + int(i.updates) + " updates in " + int(i.batchCount) + " batches" + ((i.batches || []).length ? " (latest: "
      + i.batches.slice(-3).join(", ") + ")" : "") + "; " + int(i.amended) + " amended, " + int(i.added) + " new, " + int(i.cancelled) + " cancelled.";
    b.hidden = false;
    root.dataset.intraday = String(i.updates);
  }

  /** What the scope names but cannot count, in words (an id that matches nothing, a trade not live on the date), and an empty scope. */
  function renderNotices(d) {
    var ul = $("[data-vx-notices]"), list = (d.provenance && d.provenance.notices) || [];
    ul.textContent = "";
    list.forEach(function (n) { ul.appendChild(h("li", null, n)); });
    ul.hidden = !list.length;
    root.dataset.empty = d.empty ? "true" : "false";
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
    var chipText = { portfolio: "portfolio", basket: "saved basket" + (d.basket ? " · " + d.basket.id : ""), rule: "ad-hoc · rule", adhoc: "unsaved basket",
                     capture: "capture's members" }[d.kind];
    if (chipText) c.appendChild(h("span", "vx-chip amber", chipText));
  }

  /** Per source system on the date: its form and grain, its records, how decided (VAR_GUIDE.md section 15.13). */
  function bySystem(m) {
    var names = Object.keys(m || {}).sort();
    if (!names.length) return "one form a trade";
    return names.map(function (k) {
      var x = m[k];
      return k + ": " + (x.form === "pv" ? "scenario PVs" : "P&L vectors") + " per " + x.grain + " (" + int(x.rows) + ")";
    }).join("; ");
  }

  function renderProv(p, d) {
    var dl = $("[data-vx-prov]");
    dl.textContent = "";
    var fx = p.fx || {};
    [["Scope", d.kind + " " + d.label], ["Reporting currency", (p.currency || "USD") + ", chosen in the header"],
      ["Input", p.input || "P&L vectors (USD)"],
      ["By source system", bySystem(p.inputsBySystem)],
      ["Intraday", p.intraday ? int(p.intraday.updates) + " updates of " + int(p.intraday.trades) + " trades in " + int(p.intraday.batchCount)
        + " batches, latest " + p.intraday.lastAt + "; the official records are the morning run's" : "none: the morning run"], ["Scenario FX", fx.scenarioFx || "none needed"], ["Cross rates", "via USD"],
      ["Resolved", (p.resolved || []).join("; ") || p.scope], ["Positions", int(p.positions)],
      ["Trades", int(p.trades) + (p.failed ? " · " + p.failed + " without a vector" : "")],
      ["Outside your view", p.hidden ? int(p.hidden) + " trades not included" : "none"],
      ["Unknown ids", (p.unknown || []).length ? p.unknown.join(", ") : "none"],
      ["Warnings", (p.warnings || []).length ? p.warnings.join("; ") : "none"],
      ["Rows left out", int(p.rejected || 0) + " rejected" + ((p.rejections || []).length ? " (" + p.rejections.slice(0, 3).join("; ") + ")" : "")
        + " · " + int(p.excluded || 0) + " not live"],
      ["Vectors", p.vectors + (p.tier ? " · " + p.tier : "")], ["Scenario set", p.scenarioSet], ["Method", p.method], ["ES", p.es], ["Components", p.components],
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
      if (!d.series.some(function (v) { return v !== 0; })) {           // no trades (or none with a P&L): nothing to draw, said so
        ["[data-vx-histo]", "[data-vx-tail]", "[data-vx-series]"].forEach(function (s) { $(s).textContent = "No trades: no P&L path to draw."; });
        $("[data-vx-pathtable] tbody").textContent = "";
        $("[data-vx-path-note]").textContent = "No trades in this scope on the date.";
        $("[data-vx-more]").hidden = true;
        return;
      }
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
    var first = d.dates[0], win = d.lens && d.lens.method === "SUM";
    $("[data-vx-series-title]").textContent = (win ? "10-day P&L by window, each at its last day (" : d.lens && d.lens.method === "SQRT10"
      ? "P&L × √10 by scenario date (" : "P&L by scenario date (") + d.dates[0] + " to " + d.dates[1] + ")";
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
      cell(tr, r.windowFrom ? r.windowFrom + " … " + r.date : r.date, "mono");
      cell(tr, int(r.pnl), "n mono " + (r.pnl < 0 ? "neg" : "pos"));
      var from = cell(tr, r.from || "—", "mono");
      if (r.fromScope) { from.classList.add("vx-linkcell"); from.addEventListener("click", function (ev) { ev.stopPropagation(); select(r.fromScope); }); }
      cell(tr, r.fromCurrency || "", "mono");
      cell(tr, r.fromPnl === undefined ? "" : int(r.fromPnl), "n mono neg");
      tr.addEventListener("click", function () { drill(r.date); });
      tb.appendChild(tr);
    });
    var more = $("[data-vx-more]");
    more.hidden = (d.page + 1) * d.pageSize >= d.total;
    var what = d.lens && d.lens.method === "SUM" ? "windows" : "scenarios";
    $("[data-vx-path-note]").textContent = "Rank " + d.k + " is the VaR " + (what === "windows" ? "window" : "scenario") + " (highlighted). "
      + Math.min(d.total, (d.page + 1) * d.pageSize) + " of " + d.total + " " + what + " shown.";
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
      ["Part", "Trade currency", "P&L that day", "Component VaR"].forEach(function (x, i) { thr.appendChild(h("th", i > 1 ? "n" : null, x)); });
      t.appendChild(h("thead")).appendChild(thr);
      var tb = h("tbody");
      d.rows.slice(0, 15).forEach(function (r) {
        var tr = document.createElement("tr");
        cell(tr, r.label, "vx-linkcell");
        cell(tr, r.tradeCurrency || "", "mono");
        cell(tr, int(r.scenarioPnl), "n mono " + (r.scenarioPnl < 0 ? "neg" : "pos"));
        cell(tr, int(r.componentVar), "n mono");
        tr.addEventListener("click", function () { select(r.scope); });
        tb.appendChild(tr);
      });
      if (!d.rows.length) { var tr = document.createElement("tr"); cell(tr, "No parts below this scope.", "vx-note").colSpan = 4; tb.appendChild(tr); }
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
      $("[data-vx-hist-note]").textContent = bt.applicable === false ? "Replay. " + d.note + ". " + bt.note + "."
        : (d.mode === "official" ? "Official history. " : "Replay. ") + d.note + ". " + bt.exceptions
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
        S.head && S.head.empty ? "No trades: nothing to break down." : "No parts you may see below this scope (a position level your role sees in aggregate only, or none).",
        "vx-note").colSpan = 8;
      tb.appendChild(tr);
      return;
    }
    var total = d["var"] || 1;
    d.rows.forEach(function (r) {
      var tr = document.createElement("tr");
      tr.dataset.scope = r.scope;
      tr.className = "vx-click";
      var name = cell(tr, "", "vx-name");
      if (r.direction) name.appendChild(chip(r.direction));
      name.appendChild(document.createTextNode(" " + r.label + (r.quantity !== undefined ? " · " + int(r.quantity) : "") + (r.partial ? " (part)" : "")
        + (r.kind === "part" ? " · " + int(r.trades) + " trades" : "")));
      name.appendChild(addButton(r.scope, { type: r.kind === "part" ? r.word : kindWord(r.kind), id: r.key, name: r.label }));
      var tc = cell(tr, r.tradeCurrency || "", "mono");
      if (r.tradeCurrencyFrom) tc.title = "from " + r.tradeCurrencyFrom;
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
    cell(t, "Total");
    cell(t, "");
    cell(t, int(d.sumStandalone), "n mono");
    cell(t, int(d.sumComponent), "n mono");
    cell(t, "100.0%", "n mono");
    cell(t, "diversification −" + C.money(d.diversification), "vx-note").colSpan = 3;
    tb.appendChild(t);
    $("[data-vx-ctable-note]").textContent = "Component VaR adds up to the scope's VaR (Euler, on the VaR scenario's neighbourhood). Incremental VaR = the scope's VaR minus its VaR without the row."
      + (d.truncated ? " Only the first parts are shown." : "") + (d.by === "part" ? " The parts are the basket's, each alone in the standalone "
      + "column; a trade in two parts counts once, in the first." : "") + " Click a row to drill in; + basket adds it to your basket.";
  }

  /** The scope word of a part's kind, for the basket's chip. */
  function kindWord(kind) {
    return { position: "POS", trade: "TRD", node: "NODE", desk: "NODE", book: "NODE", division: "NODE", "legal-entity": "NODE" }[kind] || "SCOPE";
  }

  /** "+ basket" on a row: adds its scope to the basket tray (the row's own click still drills in). */
  function addButton(scope, meta) {
    var b = h("button", "vx-add", "+ basket");
    b.type = "button";
    b.dataset.vxAdd = scope;
    b.title = "Add " + scope + " to your basket";
    b.setAttribute("aria-label", "Add " + (meta.name || scope) + " to your basket");
    b.addEventListener("click", function (ev) { ev.stopPropagation(); if (B) B.add(scope, meta); });
    b.addEventListener("keydown", function (ev) { ev.stopPropagation(); });
    return b;
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
        var cc = (c.currency && c.currency !== "USD" ? " " + c.currency : "") + (c.horizon === "10D" ? " · 10D" + (c.method === "SUM" ? " sum" : " √10") : "");
        cell(tr, int(c["var"]) + cc, "n mono");
        cell(tr, int(c.es) + cc, "n mono");
        cell(tr, c.note || "");
        var act = cell(tr, "");
        [["Reopen", function () { reopen(c.id); }], ["Compare with now", function () { compare(c.id, "now"); }]].forEach(function (a) {
          var b = h("button", "vx-link", a[0]); b.type = "button"; b.addEventListener("click", a[1]); act.appendChild(b); act.appendChild(document.createTextNode(" "));
        });
        var csv = h("a", "vx-link", "CSV");
        csv.href = "/api/var/captures/" + encodeURIComponent(c.id) + "/csv";
        act.appendChild(csv);
        var jl = h("a", "vx-link", "JSONL");                                  // 2.9.4: the same rows, one a line
        jl.href = "/api/var/captures/" + encodeURIComponent(c.id) + "/jsonl";
        act.appendChild(document.createTextNode(" "));
        act.appendChild(jl);
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
    var body = { scope: S.scope, note: noteInput ? noteInput.value : "" }, lp = lensParams();
    Object.keys(lp).forEach(function (k) { body[k] = lp[k]; });                // 2.9.7: captured through the page's lens, and it says so
    send("POST", "captures", body).then(function (c) {
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
      [["Business date", c.businessDate], ["Horizon", c.lens || "1-day"], ["VaR", int(c["var"]) + (c.confidence ? " at " + pctWord(c.confidence) + "%" : "")],
        ["ES", int(c.es)], ["Members", int(c.memberCount) + " trades"],
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

  // ---------------------------------------------------------------------------------------------------------------- currencies
  /** The tab "In other currencies", loaded only when opened: the scope's VaR and ES in each listed currency (from its PVs summed per
      trade currency, each with that currency's scenario FX), that VaR in the page's currency at today's rate, and the difference. */
  function loadCurrencies(seq) {
    var tb = $("[data-vx-ccytable] tbody");
    if (S.head && S.head.confidence) {                                // the headers say the answer's confidences
      var th = $("[data-vx-ccytable] thead").querySelectorAll("th");
      th[1].textContent = "VaR " + Math.round(S.head.confidence * 1000) / 10 + "%";
      th[2].textContent = "ES " + Math.round(S.head.esConfidence * 1000) / 10 + "%";
    }
    if (!tb.children.length) { var w = document.createElement("tr"); cell(w, "computing…", "vx-note").colSpan = 5; tb.appendChild(w); }
    root.dataset.ccyLoads = String(Number(root.dataset.ccyLoads || 0) + 1);
    return api("scope/currencies", { scope: S.scope }).then(function (d) {
      if (seq !== S.seq) return;
      $("[data-vx-ccy-inpage]").textContent = "VaR in " + d.currency + " at today's rate";
      $("[data-vx-ccy-note]").textContent = "The same scope in each reporting currency: " + d.note + ". Trade currencies in this scope: "
        + (d.tradeCurrencies || []).join(", ") + ".";
      tb.textContent = "";
      d.rows.forEach(function (r) {
        var tr = document.createElement("tr");
        tr.dataset.ccy = r.currency;
        if (r.shown) tr.className = "shown";
        var c = cell(tr, "");
        if (r.shown) c.textContent = r.currency + " · shown";
        else {
          var b = h("button", "vx-link", r.currency);
          b.type = "button";
          b.title = "Show the page in " + r.currency;
          b.addEventListener("click", function (ev) { ev.stopPropagation(); setCcy(r.currency); });
          c.appendChild(b);
        }
        if (r.note) { cell(tr, r.note, "vx-note").colSpan = 4; tb.appendChild(tr); return; }
        cell(tr, C.money(r["var"]), "n mono");
        cell(tr, C.money(r.es), "n mono");
        cell(tr, C.money(r.varInPage), "n mono");
        cell(tr, r.difference === null || r.difference === undefined ? "—"
          : C.signed(r.difference) + (r.differencePct === null || r.differencePct === undefined ? "" : " (" + (r.differencePct > 0 ? "+" : "") + (r.differencePct * 100).toFixed(1) + "%)"),
          "n mono");
        if (!r.shown) { tr.className = "vx-click"; tr.addEventListener("click", function () { setCcy(r.currency); }); }
        tb.appendChild(tr);
      });
    }).catch(function (e) {
      if (seq !== S.seq) return;
      tb.textContent = "";
      var tr = document.createElement("tr");
      cell(tr, e.message, "vx-err").colSpan = 5;
      tb.appendChild(tr);
    });
  }

  var NAMES = { USD: "US dollar", EUR: "Euro", CAD: "Canadian dollar", JPY: "Japanese yen", GBP: "Pound sterling", CHF: "Swiss franc",
                AUD: "Australian dollar", NZD: "New Zealand dollar", SEK: "Swedish krona", NOK: "Norwegian krone", HKD: "Hong Kong dollar",
                SGD: "Singapore dollar", CNY: "Chinese yuan", INR: "Indian rupee", MXN: "Mexican peso", BRL: "Brazilian real" };
  function option(c) { var o = document.createElement("option"); o.value = c; o.textContent = c + (NAMES[c] ? " · " + NAMES[c] : ""); return o; }

  /** The header's Reporting currency: the listed currencies and "More…", which adds every other currency with FX on the date. */
  function loadPicker(more) {
    var sel = $("[data-vx-ccy]");
    api("currencies", {}).then(function (d) {
      sel.textContent = "";
      var listed = d.listed || ["USD"];
      listed.forEach(function (c) { sel.appendChild(option(c)); });
      var others = (d.available || []).filter(function (c) { return listed.indexOf(c) < 0; });
      if (more || listed.indexOf(S.ccy) < 0) {
        var g = document.createElement("optgroup");
        g.label = "More currencies with FX on " + d.businessDate;
        others.forEach(function (c) { g.appendChild(option(c)); });
        if (others.indexOf(S.ccy) < 0 && listed.indexOf(S.ccy) < 0) g.appendChild(option(S.ccy));
        if (g.children.length) sel.appendChild(g);
      } else if (others.length) {
        var m = document.createElement("option");
        m.value = "__more";
        m.textContent = "More…";
        sel.appendChild(m);
      }
      sel.value = S.ccy;
      root.dataset.ccyReady = "true";
    }).catch(function () { sel.value = S.ccy; });
  }

  /** The whole page in another reporting currency: kept for the session, put in the link and the command, every panel reloaded. */
  function setCcy(c) {
    c = (c || "USD").toUpperCase();
    if (c === S.ccy) return;
    S.ccy = c;
    root.dataset.ccy = c;
    try { sessionStorage.setItem(CCY_KEY, c); } catch (e) { /* private window: the link still carries it */ }
    var u = new URL(location.href);
    if (c !== "USD") u.searchParams.set("ccy", c); else u.searchParams.delete("ccy");
    history.replaceState({ scope: S.scope, ccy: c }, "", u.toString());
    var sel = $("[data-vx-ccy]");
    if (![].some.call(sel.options, function (o) { return o.value === c; })) sel.appendChild(option(c));
    sel.value = c;
    S.children = {};
    S.tree = null;
    loadTree();
    select(S.scope, { noPush: true });
    document.dispatchEvent(new CustomEvent("var:currency", { detail: { ccy: c } }));
  }
  // ---------------------------------------------------------------------------------------------------------------- the lens (2.9.7)
  function lensState() { return { h: S.h, m: S.m, q: S.q, esq: S.esq }; }

  function confOption(f, isDefault) {
    var o = document.createElement("option");
    o.value = isDefault ? "" : String(f);
    o.textContent = pctWord(f) + "%" + (isDefault ? " · default" : "");
    o.dataset.f = String(f);
    return o;
  }

  /** The Confidence and ES selects from the engine's configured lists (/status: confidences, esConfidences), the defaults marked. */
  function loadLensPicker() {
    api("status", {}).then(function (st) {
      S.lensStatus = st;
      [["[data-vx-conf]", st.confidences || [], st.confidence, "q"], ["[data-vx-esconf]", st.esConfidences || [], st.esConfidence, "esq"]].forEach(function (x) {
        var sel = $(x[0]);
        sel.textContent = "";
        x[1].forEach(function (f) { sel.appendChild(confOption(f, Math.abs(f - x[2]) < 1e-9)); });
        if (S[x[3]] && Math.abs(Number(S[x[3]]) - x[2]) < 1e-9) S[x[3]] = "";      // the default named: kept as the default
        sel.value = S[x[3]] || "";
      });
      root.dataset.lensReady = "true";
    }).catch(function () { /* the selects keep their one option */ });
  }

  /** The selects as the answer says its lens (a command or a link may name the default 10-day method, or none). */
  function syncLensPicker(ln) {
    if (!ln) return;
    var hz = $("[data-vx-horizon]");
    hz.value = ln.horizon === "10D" ? "10d:" + (ln.method === "SUM" ? "sum" : "sqrt10") : "1d";
  }

  /** A new lens: every panel again, the link and the command, and the person's settings (varView) for the next visit. */
  function setLens(next) {
    Object.keys(next).forEach(function (k) { S[k] = next[k]; });
    if (S.h !== "10d") { S.h = ""; S.m = ""; }
    var u = lensLink(new URL(location.href));
    history.replaceState({ scope: S.scope, ccy: S.ccy, lens: lensState() }, "", u.toString());
    fetch("/api/settings", { method: "PATCH", credentials: "same-origin", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ varView: { h: S.h || "1d", m: S.m || null, q: S.q ? Number(S.q) : null, esq: S.esq ? Number(S.esq) : null } })
    }).catch(function () { /* the link still carries it */ });
    S.children = {};
    S.tree = null;
    loadTree();
    select(S.scope, { noPush: true });
    document.dispatchEvent(new CustomEvent("var:lens", { detail: lensState() }));
  }
  $("[data-vx-horizon]").addEventListener("change", function (ev) {
    var v = ev.target.value.split(":");
    setLens({ h: v[0] === "10d" ? "10d" : "", m: v[1] || "" });
  });
  $("[data-vx-conf]").addEventListener("change", function (ev) { setLens({ q: ev.target.value }); });
  $("[data-vx-esconf]").addEventListener("change", function (ev) { setLens({ esq: ev.target.value }); });

  $("[data-vx-ccy]").addEventListener("change", function (ev) {
    var v = ev.target.value;
    if (v === "__more") { ev.target.value = S.ccy; loadPicker(true); setTimeout(function () { ev.target.focus(); }, 0); return; }
    setCcy(v);
  });

  // ---------------------------------------------------------------------------------------------------------------- the tree
  function loadTree() {
    fresh("tree", "loading…", "computing");
    S.treeLoading = true;
    return api("tree", { hierarchy: S.hierarchy }).then(function (d) {
      S.treeLoading = false;
      S.tree = d;
      fillHierarchies(d);
      var byId = {};
      d.nodes.forEach(function (n) { byId[n.id] = n; if (n.depth < 2) S.expanded[n.scope] = true; });
      S.byId = byId;
      renderTree();
      renderAdhoc();
      fresh("tree", d.businessDate);
    }).catch(function (e) {
      S.treeLoading = false;
      if (e.status === 404 && S.hierarchy !== "MGMT") {             // a hierarchy the date has not: the management tree, said
        var n = $("[data-vx-notice]");
        n.hidden = false;
        n.textContent = (e.message || ("No hierarchy " + S.hierarchy + " on this date")) + " The management tree is shown.";
        setHierarchy("MGMT");
        return loadTree();
      }
      errorBox($("[data-vx-tree]"), e);
      fresh("tree", "failed", "stale");
    });
  }

  /** The hierarchy selector from the date's hierarchies (2.9.5: every configured one, by its title), the current one chosen. */
  function fillHierarchies(d) {
    var sel = $("[data-vx-hierarchy]");
    var info = d.hierarchyInfo || (d.hierarchies || []).map(function (id) { return { id: id, title: id }; });
    if (!info.length) return;
    var have = Array.prototype.map.call(sel.options, function (o) { return o.value + "|" + o.textContent; }).join(",");
    var want = info.map(function (x) { return x.id + "|" + (x.title || x.id) + (x.skipped ? " (not available)" : ""); }).join(",");
    if (have !== want) {
      sel.textContent = "";
      info.forEach(function (x) {
        var o = document.createElement("option");
        o.value = x.id;
        o.textContent = (x.title || x.id) + (x.skipped ? " (not available)" : "");
        if (x.skipped) { o.disabled = true; o.title = x.note || ""; }   // 2.9.5: skipped on this date (its column not in the data), said why
        sel.appendChild(o);
      });
    }
    sel.value = d.hierarchy || S.hierarchy;
  }

  /** The hierarchy shown, kept in the page's link (hierarchy=LL). */
  function setHierarchy(id) {
    S.hierarchy = id;
    root.dataset.hierarchy = id;
    var u = new URL(location.href);
    if (id === "MGMT") u.searchParams.delete("hierarchy"); else u.searchParams.set("hierarchy", id);
    history.replaceState(history.state, "", u.toString());
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
    if (scope) nm.appendChild(addButton(scope, { type: opts.word || scope.split(":")[0], id: scope.replace(/^[A-Z]+:/, ""), name: label }));
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
    ["Scope", "VaR " + S.ccy, "ES", "Limit"].forEach(function (x, i) { hd.appendChild(h("span", i ? "num" : null, x)); });
    t.appendChild(hd);
    var shown = {};
    (S.tree.nodes || []).forEach(function (n) {
      var parent = n.parent && S.byId[n.parent];
      if (parent && (!shown[parent.id] || !S.expanded[parent.scope])) return;
      shown[n.id] = true;
      t.appendChild(treeRow(n.name, n.scope, n.depth, n["var"], n.es, n.limitUse, { expandable: n.expandable,
        noLimitText: n.limitComparable === false ? "n/c" : "no limit" }));          // 2.9.7: a limit of another horizon or confidence
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
      markTree();
    }).catch(function () { /* no baskets listed */ });
    markTree();
  }

  // ---------------------------------------------------------------------------------------------------------------- the basket tray
  /** The basket tray as a strip at the top (var-basket.js): it moves the explorer when saved or opened, and while the explorer shows the
      tray's own unsaved scope, every change moves it too (the VaR follows as you pick). */
  var strip = $("[data-vx-basket]"), follow = null;
  if (B) {
    B.attach({
      strip: strip,
      onOpen: function (scope) { renderAdhoc(); select(scope); },
      onChange: function (scope, before) {
        if (!scope || !S.head || S.head.kind !== "adhoc" || (S.scope !== before && S.head.canonical !== before)) return;
        clearTimeout(follow);
        follow = setTimeout(function () { select(scope); }, 350);
      }
    });
    if (B.state.items.length) strip.hidden = false;
    document.addEventListener("vb:saved", function (ev) { root.dataset.saved = ev.detail.id; });
  }
  $("[data-vx-basket-open]").addEventListener("click", function () { if (B) { B.show(); var f = root.querySelector("[data-vb-find]"); if (f) f.focus(); } });
  $("[data-vx-whatif]").addEventListener("click", function () {
    if (!B) return;
    if (!B.state.items.length && S.head) B.add(S.head.kind === "basket" ? S.head.canonical : S.scope, { name: S.head.label });
    B.openWhatIf();
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
        fresh("main", (tab === "ccy" ? "currencies" : tab) + " in " + Math.round(performance.now() - t0) + " ms");
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
  $("[data-vx-hierarchy]").addEventListener("change", function (ev) { setHierarchy(ev.target.value); loadTree(); });
  $("[data-vx-capture]").addEventListener("click", function () { doCapture($("[data-vx-cap-note]")); });
  $("[data-vx-capture-side]").addEventListener("click", function () { doCapture(null); });

  function csvOfPath(ev) {
    ev.preventDefault();
    if (!S.path) return;
    var ln = (S.path && S.path.lens) || {};
    var lines = ["# scope: " + S.scope, "# business date: " + (S.head ? S.head.businessDate : ""), "# horizon: " + (ln.label || "1-day")
      + " · VaR " + pctWord(ln.confidence || 0.99) + "% · ES " + pctWord(ln.esConfidence || 0.975) + "%", "rank,scenario_date,pnl"];
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

  /** The path as JSON Lines (2.9.4): a scenario a line, ranked, keyed as the CSV's header (rank, scenario_date, pnl). */
  function jsonlOfPath(ev) {
    ev.preventDefault();
    if (!S.path) return;
    var order = S.path.series.map(function (v, i) { return [v, i]; }).sort(function (a, b) { return a[0] - b[0] || a[1] - b[1]; });
    var dates = scenarioDates(S.path), text = "";
    order.forEach(function (o, r) { text += JSON.stringify({ rank: r + 1, scenario_date: dates[o[1]] || "#" + o[1], pnl: o[0] }) + "\n"; });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "application/jsonl" }));
    a.download = "var-path-" + S.scope.replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 60) + ".jsonl";
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }
  var jsonlPath = $("[data-vx-jsonl-path]");
  if (jsonlPath) jsonlPath.addEventListener("click", jsonlOfPath);

  window.addEventListener("popstate", function (ev) {
    if (!ev.state || !ev.state.scope) return;
    if (ev.state.lens && JSON.stringify(ev.state.lens) !== JSON.stringify(lensState())) {
      Object.keys(ev.state.lens).forEach(function (k) { S[k] = ev.state.lens[k] || ""; });
      S.tree = null; S.children = {}; loadTree();
    }
    if (ev.state.ccy && ev.state.ccy !== S.ccy) { S.scope = ev.state.scope; setCcy(ev.state.ccy); return; }
    select(ev.state.scope, { noPush: true });
  });
  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (S.path && S.tab === "path" && S.path.series.some(function (v) { return v !== 0; })) drawPath(S.path); }, 150);
  });

  // ---------------------------------------------------------------------------------------------------------------- start
  (function () {                                       // the session's currency goes into the link the page opened with
    var u = new URL(location.href);
    if (S.ccy !== "USD" && u.searchParams.get("ccy") !== S.ccy) u.searchParams.set("ccy", S.ccy);
    try { sessionStorage.setItem(CCY_KEY, S.ccy); } catch (e) { /* the link carries it */ }
    root.dataset.ccy = S.ccy;
    lensLink(u);                                       // 2.9.7: the lens chosen last (the person's settings) goes into the link too
    history.replaceState({ scope: S.scope, ccy: S.ccy, lens: lensState() }, "", u.toString());
    $("[data-vx-horizon]").value = S.h === "10d" ? "10d:" + (S.m || "sqrt10") : "1d";
  })();
  loadPicker(false);
  loadLensPicker();
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
  window.VarExplorer = { select: select, switchTab: switchTab, setCcy: setCcy, setLens: setLens, state: S };
})();
