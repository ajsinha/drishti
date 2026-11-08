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
/* The VaR explorer (docs/guides/VAR_GUIDE.md section 3). One page for any scope: a click on a tree row, a
   breadcrumb, a contribution row or a command moves every panel to the new scope in place (no page load). Each panel says when it is
   computing and is painted as soon as its answer arrives; answers are kept per scope and business date, and the children of the
   selected node are fetched ahead while the user reads, so drilling down is instant. The time from a click to the painted panels is
   shown in the status line and kept on the root element (data-paint-ms) for the tests.

   Ad-hoc scopes and baskets are one thing: "Construct your basket" (drishti_var-basket.js and drishti_var-basket-sheet.js), one sheet like Excel at the
   top of this page, opened when asked: a column per kind of part, the type-ahead, "+ basket" on every row of the tree and the
   contributions, pasted columns, "Start from the whole bank", the server's check of every cell and the basket's VaR live, a pre-deal
   what-if, Save (a basket, private or shared read-only) and Open. Opening a basket loads it back into the sheet. The baskets are listed
   in the toolbar's two drop-downs (2.9.11.b, drishti_var-layout.js): System baskets (the bank's, kept in Drishti's basket store) and My baskets (saved: mine, then
   shared with me); the sheet is collapsed until "+ New basket" (or Alt+B, + basket, Edit) and collapses again once saved. A saved
   basket of yours has "Edit" (an administrator's on everyone's; a basket shared with you and a system basket, the bank's, have "Edit a copy";
   a holder of var:system-baskets edits the system basket itself under the sheet)
   and "Delete", which asks in a dialog (drishti_scope-delete.js), then refreshes the list and says so. How a number was built is the one Explain's
   (drishti-insight): the VaR and ES tiles and Explain › carry their figure's request (bindExplain), the page's scope, date and lens. "Capture VaR" stores an immutable snapshot that reopens exactly, compares with
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
  if (!root || !window.Drishti.elements || !window.Drishti.elements.risk) return;
  var R = window.Drishti.elements.risk;                // drishti-risk: the grid and chart presets and the figures in words (ELEMENTS.md 4.8)
  var money = R.riskMoney, signed = R.riskSigned;
  var $ = function (sel) { return root.querySelector(sel); };
  var params = new URLSearchParams(location.search);
  var CCY_KEY = "drishti.var.ccy";
  function storedCcy() { try { return sessionStorage.getItem(CCY_KEY) || ""; } catch (e) { return ""; } }
  var S = { scope: root.dataset.scope || "BANK", hierarchy: root.dataset.hierarchy || "MGMT", tab: root.dataset.tab || "path", by: "child", page: 0,
            asOf: params.get("asOf") || "", tree: null, open: {}, children: {}, head: null, seq: 0, user: root.dataset.user || "",
            admin: root.dataset.admin === "true",            // an administrator: Delete on every basket listed (the server checks again)
            ccy: (root.dataset.ccy || params.get("ccy") || storedCcy() || "USD").toUpperCase(),
            // the lens (2.9.7): "" is the default (1-day; the setting's 10-day method; the configured confidences)
            h: root.dataset.h || "", m: root.dataset.m || "", q: root.dataset.q || "", esq: root.dataset.esq || "", lensStatus: null,
            // Normal or Stressed (VAR_GUIDE.md section 18.8): "" the date's own scenarios, "stressed" its stress scenario set (in USD)
            set: root.dataset.set === "stressed" ? "stressed" : "" };
  if (S.h !== "10d") { S.h = ""; S.m = ""; }
  var B = window.Drishti.VarBasket || null;
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
   * The answers read as tables (Arrow where the page loaded it, JSON otherwise; DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6): a contribution table by position
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
    if (S.set === "stressed") o.set = "stressed";
    return o;
  }

  /** The Parametric toggle on (pm=1; the PVAR command): the parametric blocks come with the answers; off, they are asked away
   *  (parametric=false: lean answers, historical only). */
  var PM = root.dataset.pm === "1", PARAMETRIC_PATHS = { scope: 1, "scope/path": 1, "scope/contributions": 1, tree: 1 };
  function api(path, q, fresh) {
    var p = new URLSearchParams();
    Object.keys(q || {}).forEach(function (k) { if (q[k] !== undefined && q[k] !== null && q[k] !== "") p.set(k, q[k]); });
    if (!PM && PARAMETRIC_PATHS[path] && !p.has("parametric")) p.set("parametric", "false");
    if (LENS_PATHS[path]) { var lp = lensParams(); Object.keys(lp).forEach(function (k) { if (!p.has(k)) p.set(k, lp[k]); }); }
    if (S.asOf) p.set("asOf", S.asOf);
    // stressed VaR is in USD, the stress set's own currency: the page's currency is not sent with it
    if (S.ccy && S.ccy !== "USD" && !p.has("ccy") && !(S.set === "stressed" && LENS_PATHS[path])) p.set("ccy", S.ccy);
    var key = path + "?" + p.toString();
    if (!fresh && cache.has(key)) return cache.get(key);
    var table = tableOf(path, q);
    var pr = table && window.Drishti.fetchTable
      ? window.Drishti.fetchTable("/api/var/" + path + "?" + p.toString(), { shape: table[0], at: table[1] }).then(function (t) {
          var d = t.answer();                          // the same answer as the JSON one, read from the typed columns (2.9.7)
          Object.defineProperty(d, "__table", { value: t, enumerable: false });
          return d;
        }, function (e) { var x = new Error(e.detail || e.message); x.status = e.status; x.code = e.code; x.body = e.body; throw x; })
      : fetch("/api/var/" + path + "?" + p.toString(), { headers: { Accept: "application/json" }, credentials: "same-origin" })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (b) {
          if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; e.body = b; throw e; }
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
  var int = R.riskInt, pct = R.riskPct;
  /** The tile's tone of the server's limit status (within, near by drishti.risk.limit-near, breach); none without a status. */
  var LIMIT_TONE = { within: "ok", near: "warn", breach: "bad" };
  function limitTone(status) { return (status && LIMIT_TONE[status]) || null; }
  function chip(dir) { return h("span", "vx-chip " + (dir === "LONG" ? "long" : dir === "SHORT" ? "short" : ""), dir || ""); }
  function fresh(which, text, cls) {
    var e = root.querySelector('[data-vx-fresh="' + which + '"]');
    if (e) { e.textContent = text; e.className = "vx-fresh" + (which === "main" ? " vx-tab-fresh" : "") + (cls ? " " + cls : ""); }
  }
  function errorBox(host, e, retry) {
    var busy = window.Drishti.Busy && window.Drishti.Busy.problem(e);
    if (busy) {                                   // busy protection: the calm message in this panel, a countdown, tried again by itself
      window.Drishti.Busy.render(host, busy, { retry: retry || function () { select(S.scope, { noPush: true }); },
        keep: "the dates already open keep working." });
      return;
    }
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
    var w = S.set === "stressed" ? " SVAR" : "";
    if (S.h === "10d") w += " 10D" + (S.m === "sum" ? " SUM" : S.m === "sqrt10" ? " SQRT" : "");
    if (S.q) w += " " + pctWord(S.q);
    if (S.esq) w += " ES " + pctWord(S.esq);
    return w;
  }
  function pctWord(f) { return String(Math.round(Number(f) * 10000) / 100); }
  /** The link's lens: h, m, q, esq (the default ones left out). */
  function lensLink(u) {
    [["h", S.h], ["m", S.h === "10d" ? S.m : ""], ["q", S.q], ["esq", S.esq], ["set", S.set]].forEach(function (x) {
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
      document.dispatchEvent(new CustomEvent("drishti:var-painted", { detail: { scope: scope, ms: ms } }));
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
    var days = ln.horizon === "10D" ? "10 days" : "1 day", stressed = ln.set === "STRESSED", avg = d.stressAverage || {};
    var tiles = [
      [(stressed ? "Stressed VaR " : "VaR ") + conf + "% · " + days + " · " + cur, d.empty ? "—" : money(d["var"]), d.empty ? "no trades: nothing to compute"
        : sq ? "1-day × √10 (3.162) · " + ordinal(d.k) + " worst of " + d.scenarios : ordinal(d.k) + " worst of " + d.scenarios + (sum ? " overlapping windows" : ""), null],
      [(stressed ? "Stressed ES " : "ES ") + esc + "% · " + (ln.horizon === "10D" ? "10 days · " : "") + cur, d.empty ? "—" : money(d.es), d.empty ? "no trades"
        : sq ? "1-day ES × √10" : "mean of worst " + d.esCount + (sum ? " windows" : ""), null],
      ["Sum of standalone VaR", d.sumStandalone === null ? "—" : money(d.sumStandalone), d.childKind ? d.children + " " + d.childKind + (d.children === 1 ? "" : "s") : (d.children ? d.children + " parts" : "no parts"), null],
      ["Diversification", d.diversification === null ? "—" : "−" + money(d.diversification),
        d.diversification === null ? "single part" : (d.diversification / d.sumStandalone * 100).toFixed(1) + "% of standalone", null],
      d.limitComparable === false
        ? ["Limit utilisation", "—", d.limitNote || "limit not comparable", "nc"]
        : ["Limit utilisation", u === null || u === undefined ? "—" : (u * 100).toFixed(0) + "%", d.limit ? "limit " + money(d.limit) + (cur !== "USD" ? " " + cur + " (set in USD, at today's rate)" : "") : "no limit set", limitTone(d.limitStatus)],
      stressed
        ? ["Stressed VaR · " + (avg.weeks || 12) + "-week average", avg.average === null || avg.average === undefined ? "—" : money(avg.average),
           avg.records ? "mean of " + avg.records + " official record" + (avg.records === 1 ? "" : "s") : (avg.note || "no official records"), null]
        : bt.applicable === false
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
      if (i === 5 && bt.applicable !== false && !stressed) { v.appendChild(h("span", "vx-light " + (bt.light || ""))); }
      v.appendChild(document.createTextNode(x[1]));
      tile.appendChild(v);
      tile.appendChild(h("div", "s mono" + (x[3] === "nc" ? " vx-nc" : ""), x[2]));
      t.appendChild(tile);
    });
    renderMethod(d);
    syncLensPicker(d.lens);
    renderCrumb(d);
    renderIntraday(d.intraday);
    bindExplain(d);
    if (PM && window.Drishti.VarParametric) window.Drishti.VarParametric.headline(d);   // the parametric tiles, each with its ? (drishti_var-parametric.js)
    document.title = "VaR · " + d.label + (document.body.dataset.product ? " · " + document.body.dataset.product : "");
  }

  function ordinal(k) { return k + (k % 100 >= 11 && k % 100 <= 13 ? "th" : k % 10 === 1 ? "st" : k % 10 === 2 ? "nd" : k % 10 === 3 ? "rd" : "th"); }

  /** The method strip (2.9.7): the horizon, its method and the confidences, as the mock-up says them. */
  function renderMethod(d) {
    var el = $("[data-vx-method]"), ln = d.lens || {};
    el.textContent = "";
    root.dataset.lens = (ln.set === "STRESSED" ? "S|" : "") + (ln.horizon || "1D") + (ln.method ? "-" + ln.method : "") + "|" + d.confidence + "|" + d.esConfidence;
    if (ln.set === "STRESSED" && ln.stressSet) {                    // which stress set: its id, its days, synthetic when it says so
      var ss = ln.stressSet;
      el.appendChild(h("b", null, "Stressed"));
      el.appendChild(document.createTextNode(" "));
      el.appendChild(h("span", "vx-pill", ss.id + " · " + ss.from + " … " + ss.to + (ss.synthetic ? " · synthetic" : "")));
      el.appendChild(document.createTextNode(" "));
    }
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

  /** The one Explain's request for a figure of this page (SHARED_SCREENS.md section 3): its scope, date, currency and lens. */
  function explainReq(measure, scope) {
    var r = Object.assign({ kind: "var", measure: measure, scope: scope || S.scope }, lensParams());
    if (S.asOf) r.date = S.asOf;
    if (S.ccy && S.ccy !== "USD" && S.set !== "stressed") r.ccy = S.ccy;
    return r;
  }

  /** The VaR and ES tiles and Explain ›: hover previews, a click (or E) opens the drawer (drishti-insight). */
  function bindExplain(d) {
    var E = window.Drishti.Explain, b = $("[data-vx-explain]");
    if (b) b.setAttribute("data-explain", JSON.stringify(explainReq("var")));
    if (!E || d.empty) return;
    var v = $('[data-vx-tiles] [data-tile="0"] .v'), es = $('[data-vx-tiles] [data-tile="1"] .v');
    if (v) E.bind(v, explainReq("var"));
    if (es) E.bind(es, explainReq("es"));
  }

  // ---------------------------------------------------------------------------------------------------------------- P&L path
  // Every chart is drishti-risk's (a <drishti-chart>), every table a <drishti-grid> in the terminal layout (the product owner's default
  // for VaR screens); the figures are the server's, as it sent them: nothing is summed or recomputed here.
  function loadPath(seq) {
    ["[data-vx-histo]", "[data-vx-tail]", "[data-vx-series]"].forEach(function (s) { var e = $(s); if (!e.children.length) e.textContent = "computing…"; });
    return api("scope/path", { scope: S.scope, page: 0 }).then(function (d) {
      if (seq !== S.seq) return;
      S.path = d;
      if (!d.series.some(function (v) { return v !== 0; })) {           // no trades (or none with a P&L): nothing to draw, said so
        ["[data-vx-histo]", "[data-vx-tail]", "[data-vx-series]"].forEach(function (s) { $(s).textContent = "No trades: no P&L path to draw."; });
        say($("[data-vx-pathtable]"), "No trades in this scope on the date.");
        S.pathRows = [];
        $("[data-vx-path-note]").textContent = "No trades in this scope on the date.";
        $("[data-vx-more]").hidden = true;
        return;
      }
      drawPath(d);
      pathRows(d, false);
    }).catch(function (e) { if (seq === S.seq) errorBox($("[data-vx-histo]"), e); });
  }

  function drawPath(d) {
    var ln = d.lens || (S.head && S.head.lens) || {}, p = PM && window.Drishti.VarParametric && window.Drishti.VarParametric.curveOn() ? d.parametric : null;
    var conf = { "var": ln.confidence || (S.head && S.head.confidence) || 0.99, es: ln.esConfidence || (S.head && S.head.esConfidence) || 0.975 };
    R.riskChart($("[data-vx-histo]"), { kind: "risk.pnl-distribution", binned: { edges: d.histogram.edges, counts: d.histogram.counts }, "var": d["var"], es: d.es,
      confidence: conf, value: { field: "pnl", title: "P&L", format: "compact" }, legend: "none",
      parametric: p && p.curve ? { "var": p["var"], curve: p.curve, gap: p.gap, gapPct: p.gapPct, describe: p.curveDescribe } : null }, { height: 210 });
    $("[data-vx-var-legend]").textContent = "VaR " + money(d["var"]);
    $("[data-vx-es-legend]").textContent = "ES " + money(d.es);
    R.riskChart($("[data-vx-tail]"), { kind: "bar", id: "key", x: { field: "key", title: "" }, y: { field: "pnl", format: "compact" }, legend: "none",
      data: { rows: d.worst.map(function (w, r) { return { key: "#" + w.rank, pnl: w.pnl, date: w.date, atVar: r === d.k - 1 ? 1 : 0, inTail: r < d.esCount ? 1 : 0 }; }) },
      colourWhen: [{ when: "atVar > 0", tone: "bad" }, { when: "inTail > 0", tone: "warn" }] },
      { height: 210, onPick: function (w) { drill(w.date); } });
    var win = d.lens && d.lens.method === "SUM";
    $("[data-vx-series-title]").textContent = (win ? "10-day P&L by window, each at its last day (" : d.lens && d.lens.method === "SQRT10"
      ? "P&L × √10 by scenario date (" : "P&L by scenario date (") + d.dates[0] + " to " + d.dates[1] + ")";
    var dates = scenarioDates(d);
    R.riskChart($("[data-vx-series]"), { kind: "bar", id: "key", x: { field: "key", title: "" }, y: { field: "pnl", format: "compact" }, legend: "none",
      data: { rows: d.series.map(function (v, i) { return { key: dates[i] || String(i), pnl: v, date: dates[i] }; }) },
      colourWhen: [{ when: "pnl <= " + (-d["var"]), tone: "bad" }, { when: "pnl < 0", tone: "warn" }],
      annotations: [{ type: "line", axis: "y", value: -d["var"], label: "−VaR", tone: "bad", style: "dashed" }] },
      { height: 150, onPick: function (x) { drill(x.date); } });
  }

  /** The scenario dates, oldest first (index i of the series is date i). */
  function scenarioDates(d) {
    if (d.scenarioDates) return d.scenarioDates;
    var out = new Array(d.series.length);
    (d.rows || []).concat(d.worst || []).forEach(function (r) { out[r.index] = r.date; });
    return out;
  }

  /** A message where a grid goes (computing, nothing to show, a failure); the next grid replaces it. */
  function say(host, text, cls) {
    host.textContent = "";
    var p = h("p", cls || "vx-note", text);
    if (cls === "vx-err") p.setAttribute("role", "alert");
    host.appendChild(p);
  }
  /** A button inside a grid's cell that does its own thing (the row's click is not also run). */
  function cellButton(text, cls, run) {
    var b = h("button", cls || "vx-link", text);
    b.type = "button";
    b.addEventListener("click", function (ev) { ev.stopPropagation(); run(ev); });
    return b;
  }
  /** A whole-unit figure column (P&L, component VaR ...): the text the explorer always printed; bad below zero when toned. */
  function intCol(field, header, o) {
    return R.figureColumn(field, header, Object.assign({ format: function (v) { return int(v); }, width: 120 }, o || {}));
  }
  var signTone = function (v) { return v < 0 ? "bad" : "good"; };

  function pathRows(d, append) {
    S.pathRows = (append ? S.pathRows || [] : []).concat(d.rows.map(function (r) {
      return Object.assign({}, r, { when: r.windowFrom ? r.windowFrom + " … " + r.date : r.date });
    }));
    R.riskGrid($("[data-vx-pathtable]"), { title: "Scenarios by rank", getRowId: function (r) { return String(r.rank); }, rows: S.pathRows,
      columns: [intCol("rank", "Rank", { format: function (v) { return String(v); }, width: 70 }),
        { field: "when", header: "Scenario date", width: 190 },
        intCol("pnl", "P&L", { tone: signTone }),
        { field: "from", header: "Largest loss from", width: 220, format: function (v) { return v || "—"; },
          cellRenderer: function (c) {
            if (!c.row.fromScope) return null;
            return cellButton(c.row.from || "—", "vx-link vx-linkcell", function () { select(c.row.fromScope); });
          } },
        { field: "fromCurrency", header: "Trade currency", width: 110 },
        intCol("fromPnl", "Its P&L", { format: function (v) { return v === undefined ? "" : int(v); }, tone: function () { return "bad"; } })],
      getRowClass: function (r) { return r.rank === d.k ? "vx-hl" : null; },
      getRowData: function (r) { return { rank: r.rank, date: r.date, hl: r.rank === d.k ? "1" : null }; },
      onRow: function (r) { drill(r.date); } });
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
      var g = h("div", "vx-grid");
      g.dataset.vxDrillGrid = "";
      box.appendChild(g);
      if (!d.rows.length) { say(g, "No parts below this scope."); return; }
      R.riskGrid(g, { title: "Contributors on " + date, rows: d.rows.slice(0, 15), getRowId: function (r) { return r.scope; },
        columns: [{ field: "label", header: "Part", width: 240 }, { field: "tradeCurrency", header: "Trade currency", width: 110 },
          intCol("scenarioPnl", "P&L that day", { tone: signTone }), intCol("componentVar", "Component VaR")],
        getRowData: function (r) { return { scope: r.scope }; }, onRow: function (r) { select(r.scope); } });
    }).catch(function (e) { errorBox(box, e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- history
  function loadHistory(seq) {
    var host = $("[data-vx-hline]");
    if (!host.children.length) host.textContent = "computing…";
    return api("scope/history", { scope: S.scope }).then(function (d) {
      if (seq !== S.seq) return;
      R.riskChart(host, { kind: "line", x: { field: "date", type: "category", title: "" }, y: { format: "compact" }, legend: "none",
        data: { rows: d.rows.map(function (r) { return { date: r.date, pnl: r.pnl, "var": r["var"], es: r.es, exc: r.exception ? 1 : 0 }; }) },
        series: [{ field: "pnl", name: "hypothetical P&L", tone: "link", opacity: 0.8 }, { field: "var", name: "−VaR", negate: true, tone: "bad", style: "dashed" },
                 { field: "es", name: "−ES", negate: true, tone: "accent" }],
        marks: [{ when: "exc > 0", as: "point", field: "pnl", tone: "bad", name: "backtest exception" }],
        annotations: [{ type: "line", axis: "y", value: 0, tone: "faint", style: "solid", showValue: false }] }, { height: 260 });
      var bt = d.backtest;
      $("[data-vx-hist-note]").textContent = bt.applicable === false ? "Replay. " + d.note + ". " + bt.note + "."
        : (d.mode === "official" ? "Official history. " : "Replay. ") + d.note + ". " + bt.exceptions
        + " exception" + (bt.exceptions === 1 ? "" : "s") + " in " + bt.days + " days: " + bt.light + " zone; Kupiec p = " + bt.kupiecPValue.toFixed(3)
        + ", Christoffersen p = " + bt.christoffersenPValue.toFixed(3) + ".";
      R.riskGrid($("[data-vx-histtable]"), { title: "VaR, ES and P&L by date", rows: d.rows.slice().reverse(), getRowId: function (r) { return r.date; }, fitRows: 18,
        columns: [{ field: "date", header: "Date", width: 110 }, R.figureColumn("var", "VaR", { format: money }), R.figureColumn("es", "ES", { format: money }),
          R.figureColumn("pnl", "P&L", { format: signed, tone: signTone }),
          { id: "exc", header: "", width: 96, sortable: false, valueGetter: function (r) { return r.exception ? "exception" : ""; },
            cellRenderer: function (c) { return c.row.exception ? h("span", "vx-chip bad", "exception") : null; } }],
        getRowClass: function (r) { return r.exception ? "vx-hl" : null; }, getRowData: function (r) { return { date: r.date, exception: r.exception ? "1" : null }; } });
    }).catch(function (e) { if (seq === S.seq) errorBox(host, e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- contributions
  function loadContrib(seq) {
    var host = $("[data-vx-ctable]");
    if (!host.querySelector("drishti-grid")) say(host, "computing…");
    return api("scope/contributions", { scope: S.scope, by: S.by === "child" ? "" : S.by }).then(function (d) {
      if (seq !== S.seq) return;
      renderContrib(d);
    }).catch(function (e) {
      if (seq !== S.seq) return;
      $("[data-vx-ctotal]").hidden = true;
      say(host, e.message, "vx-err");
    });
  }

  /**
   * The rows-shown line of a capped answer: "5,000 of 18,567 positions shown (the largest by gross size); totals are over all 18,567".
   * Empty when every part is listed.
   */
  function shownLine(d) {
    var shown = d.shown !== undefined && d.shown !== null ? d.shown : (d.rows || []).length;
    if (!d.truncated || !d.total || shown >= d.total) return "";
    var what = { position: "positions", trade: "trades" }[d.by] || "parts";
    return int(shown) + " of " + int(d.total) + " " + what + " shown (the largest by gross size); totals are over all " + int(d.total) + ".";
  }

  function renderContrib(d) {
    var host = $("[data-vx-ctable]"), tot = $("[data-vx-ctotal]");
    var shownEl = $("[data-vx-ctable-shown]");
    if (shownEl) {
      shownEl.textContent = shownLine(d);
      shownEl.hidden = !shownEl.textContent;
    }
    if (!d.rows.length) {
      tot.hidden = true;
      say(host, S.head && S.head.kind === "trade" ? "A trade has no parts: its P&L path is its own vector." :
        S.head && S.head.empty ? "No trades: nothing to break down." : "No parts you may see below this scope (a position level your role sees in aggregate only, or none).");
      return;
    }
    R.riskGrid(host, { title: "Contributions", rows: d.rows, getRowId: function (r) { return r.scope; },
      columns: [{ id: "name", header: "Scope", width: 300, pinned: "left",
          valueGetter: function (r) {
            return r.label + (r.quantity !== undefined ? " · " + int(r.quantity) : "") + (r.partial ? " (part)" : "") + (r.kind === "part" ? " · " + int(r.trades) + " trades" : "");
          },
          cellRenderer: function (c) {
            var s = h("span", "vx-name");
            if (c.row.direction) s.appendChild(chip(c.row.direction));
            s.appendChild(document.createTextNode(" " + c.text));
            s.appendChild(addButton(c.row.scope, { type: c.row.kind === "part" ? c.row.word : kindWord(c.row.kind), id: c.row.key, name: c.row.label }));
            return s;
          } },
        { field: "tradeCurrency", header: "Trade currency", width: 110 },
        intCol("standaloneVar", "Standalone VaR"), intCol("componentVar", "Component VaR"),
        R.figureColumn("pct", "% of total", { format: pct, width: 90 }),
        intCol("marginalPer1m", "Marginal (per 1m)", { format: function (v) { return v === null ? "—" : int(v); } }),
        R.figureColumn("incrementalVar", "Incremental VaR", { format: signed })],
      conditionalFormats: [{ column: "componentVar", type: "dataBar" }],
      getRowData: function (r) { return { scope: r.scope }; }, onRow: function (r) { select(r.scope); } });
    // the totals are the server's (the sum of component VaR is the scope's VaR by Euler; standalone VaRs do not add up to it)
    tot.hidden = false;
    tot.textContent = "";
    [["Total standalone", int(d.sumStandalone), "sumStandalone"], ["Total component", int(d.sumComponent), "sumComponent"], ["", "100.0%", null],
     ["", "diversification −" + money(d.diversification), "diversification"]].forEach(function (x) {
      var s = h("span", "vx-ctot");
      if (x[0]) s.appendChild(h("span", "k", x[0] + " "));
      var b = h("b", null, x[1]);
      if (x[2]) b.setAttribute("data-" + x[2].replace(/[A-Z]/g, function (c) { return "-" + c.toLowerCase(); }), String(d[x[2]]));
      s.appendChild(b);
      tot.appendChild(s);
    });
    $("[data-vx-ctable-note]").textContent = "Component VaR adds up to the scope's VaR (Euler, on the VaR scenario's neighbourhood). Incremental VaR = the scope's VaR minus its VaR without the row."
      + (d.truncated ? " " + shownLine(d) : "") + (d.by === "part" ? " The parts are the basket's, each alone in the standalone "
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
      var host = $("[data-vx-captable]"), more = $("[data-vx-cap-more]");
      more.hidden = true;
      more.textContent = "";
      if (!list.length) { say(host, "Nothing captured for this scope yet."); return; }
      var lensOf = function (c) { return (c.currency && c.currency !== "USD" ? " " + c.currency : "") + (c.horizon === "10D" ? " · 10D" + (c.method === "SUM" ? " sum" : " √10") : ""); };
      R.riskGrid(host, { title: "Captured results", rows: list, getRowId: function (c) { return c.id; },
        columns: [{ field: "id", header: "Capture", width: 150 }, { field: "businessDate", header: "Date", width: 100 },
          { id: "at", header: "Captured", width: 150, valueGetter: function (c) { return String(c.capturedAt).replace("T", " ").slice(0, 19); } },
          { field: "owner", header: "By", width: 90 },
          R.figureColumn("var", "VaR", { format: function (v, c) { return int(v) + lensOf(c); } }),
          R.figureColumn("es", "ES", { format: function (v, c) { return int(v) + lensOf(c); } }),
          { field: "note", header: "Note", width: 160, format: function (v) { return v || ""; } },
          { id: "acts", header: "", width: 260, sortable: false, valueGetter: function () { return ""; },
            cellRenderer: function (x) {
              var c = x.row, s = h("span", "vx-acts");
              s.appendChild(cellButton("Reopen", "vx-link", function () { reopen(c.id); }));
              s.appendChild(document.createTextNode(" "));
              s.appendChild(cellButton("Compare with now", "vx-link", function () { compare(c.id, "now"); }));
              [["CSV", "/csv"], ["JSONL", "/jsonl"]].forEach(function (a) {           // 2.9.4: the same rows, one a line
                var l = h("a", "vx-link", a[0]);
                l.href = "/api/var/captures/" + encodeURIComponent(c.id) + a[1];
                s.appendChild(document.createTextNode(" "));
                s.appendChild(l);
              });
              return s;
            } }],
        getRowData: function (c) { return { capture: c.id }; } });
      if (list.length >= 2) {
        more.hidden = false;
        more.appendChild(cellButton("Compare the two newest", "vx-link", function () { compare(list[1].id, list[0].id); }));
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
      if (c.basketNotice) {                              // a capture of a basket deleted since: it keeps its scope and members
        var gone = h("p", "vx-note vx-gone", c.basketNotice.charAt(0).toUpperCase() + c.basketNotice.slice(1) + ".");
        gone.setAttribute("role", "status");
        gone.dataset.vxBasketGone = "";
        box.appendChild(gone);
      }
      var dl = h("dl", "vx-capdl");
      [["Business date", c.businessDate], ["Horizon", c.lens || "1-day"], ["VaR", int(c["var"]) + (c.confidence ? " at " + pctWord(c.confidence) + "%" : "")],
        ["ES", int(c.es)], ["Members", int(c.memberCount) + " trades"],
        ["Data version", c.dataVersion], ["Scenario set", c.scenarioSet], ["Note", c.note || "—"]].forEach(function (r) {
        dl.appendChild(h("dt", null, r[0])); dl.appendChild(h("dd", "mono", String(r[1])));
      });
      box.appendChild(dl);
      box.dataset.reopened = c.id;
      box.dataset.var = String(c["var"]);
      var g = h("div", "vx-grid");
      box.appendChild(g);
      R.riskGrid(g, { title: "Components of " + c.id, rows: (c.components || []).slice(0, 12),
        columns: [{ field: "label", header: "Component", width: 240 }, intCol("componentVar", "Component VaR"), R.figureColumn("pct", "% of VaR", { format: pct, width: 90 })] });
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
      var rows = [["VaR", d.a["var"], d.b["var"], d.varChange], ["ES", d.a.es, d.b.es, d.esChange], ["Members", d.a.memberCount, d.b.memberCount, d.b.memberCount - d.a.memberCount]]
        .concat(d.components.slice(0, 10).map(function (r) { return [r.label || r.key, r.a, r.b, r.change]; }))
        .map(function (r, i) { return { i: i, what: r[0], a: r[1], b: r[2], change: r[3] || 0 }; });
      var g = h("div", "vx-grid");
      box.appendChild(g);
      R.riskGrid(g, { title: "Compare", rows: rows, getRowId: function (r) { return String(r.i); },
        columns: [{ field: "what", header: "", width: 220 }, intCol("a", d.a.id), intCol("b", d.b.id === "now" ? "now" : d.b.id),
          R.figureColumn("change", "Change", { format: signed, tone: function (v) { return v > 0 ? "bad" : "good"; } })] });
      if (d.membersAdded.length || d.membersRemoved.length) {
        box.appendChild(h("p", "vx-note", "Trades added: " + (d.membersAdded.join(", ") || "none") + ". Removed: " + (d.membersRemoved.join(", ") || "none") + "."));
      }
    }).catch(function (e) { errorBox(box, e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- currencies
  /** The tab "In other currencies", loaded only when opened: the scope's VaR and ES in each listed currency (from its PVs summed per
      trade currency, each with that currency's scenario FX), that VaR in the page's currency at today's rate, and the difference. */
  function loadCurrencies(seq) {
    var host = $("[data-vx-ccytable]");
    if (!host.querySelector("drishti-grid")) say(host, "computing…");
    root.dataset.ccyLoads = String(Number(root.dataset.ccyLoads || 0) + 1);
    return api("scope/currencies", { scope: S.scope }).then(function (d) {
      if (seq !== S.seq) return;
      $("[data-vx-ccy-note]").textContent = "The same scope in each reporting currency: " + d.note + ". Trade currencies in this scope: "
        + (d.tradeCurrencies || []).join(", ") + ".";
      var conf = S.head && S.head.confidence, esc = S.head && S.head.esConfidence;   // the headings say the answer's confidences
      var noted = function (f) { return function (v, r) { return r.note ? "" : f(v); }; };
      R.riskGrid(host, { title: "The scope in other currencies", rows: d.rows, getRowId: function (r) { return r.currency; },
        columns: [{ field: "currency", header: "Currency", width: 120, valueGetter: function (r) { return r.shown ? r.currency + " · shown" : r.currency; },
            cellRenderer: function (c) { return c.row.shown ? null : cellButton(c.row.currency, "vx-link", function () { setCcy(c.row.currency); }); } },
          R.figureColumn("var", conf ? "VaR " + Math.round(conf * 1000) / 10 + "%" : "VaR", { format: noted(money) }),
          R.figureColumn("es", esc ? "ES " + Math.round(esc * 1000) / 10 + "%" : "ES", { format: noted(money) }),
          R.figureColumn("varInPage", "VaR in " + d.currency + " at today's rate", { format: noted(money), width: 170 }),
          R.figureColumn("difference", "Difference", { width: 150, valueGetter: function (r) { return r.difference; },
            format: function (v, r) {
              if (r.note) return r.note;
              return v === null || v === undefined ? "—" : signed(v) + (r.differencePct === null || r.differencePct === undefined ? "" : " (" + (r.differencePct > 0 ? "+" : "") + (r.differencePct * 100).toFixed(1) + "%)");
            } })],
        getRowClass: function (r) { return r.shown ? "vx-shown" : null; }, getRowData: function (r) { return { ccy: r.currency, shown: r.shown ? "1" : null }; },
        onRow: function (r) { if (!r.shown) setCcy(r.currency); } });
    }).catch(function (e) {
      if (seq !== S.seq) return;
      say(host, e.message, "vx-err");
    });
  }

  var NAMES = { USD: "US dollar", EUR: "Euro", CAD: "Canadian dollar", JPY: "Japanese yen", GBP: "Pound sterling", CHF: "Swiss franc",
                AUD: "Australian dollar", NZD: "New Zealand dollar", SEK: "Swedish krona", NOK: "Norwegian krone", HKD: "Hong Kong dollar",
                SGD: "Singapore dollar", CNY: "Chinese yuan", INR: "Indian rupee", MXN: "Mexican peso", BRL: "Brazilian real" };
  function option(c, group) { return { value: c, label: c + (NAMES[c] ? " · " + NAMES[c] : ""), group: group || "" }; }
  /** A header picker's drishti-inputs Select (the <drishti-select> element's component). */
  function picker(sel) { return $(sel).component; }

  /** The header's Reporting currency: the listed currencies and "More…", which adds every other currency with FX on the date. */
  function loadPicker(more) {
    var sel = picker("[data-vx-ccy]");
    api("currencies", {}).then(function (d) {
      var listed = d.listed || ["USD"], list = listed.map(function (c) { return option(c); });
      var others = (d.available || []).filter(function (c) { return listed.indexOf(c) < 0; });
      if (more || listed.indexOf(S.ccy) < 0) {
        var g = "More currencies with FX on " + d.businessDate;
        others.forEach(function (c) { list.push(option(c, g)); });
        if (others.indexOf(S.ccy) < 0 && listed.indexOf(S.ccy) < 0) list.push(option(S.ccy, g));
      } else if (others.length) {
        list.push({ value: "__more", label: "More…", detail: others.length + " more with FX" });
      }
      sel.setOptions(list, S.ccy);
      root.dataset.ccyReady = "true";
    }).catch(function () { sel.setValue(S.ccy); });
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
    var sel = picker("[data-vx-ccy]");
    if (!sel.options.some(function (o) { return o.value === c; })) sel.setOptions(sel.options.concat([option(c)]), c);
    else sel.setValue(c);
    S.children = {};
    S.tree = null;
    loadTree();
    select(S.scope, { noPush: true });
    document.dispatchEvent(new CustomEvent("drishti:var-currency", { detail: { ccy: c } }));
  }
  // ---------------------------------------------------------------------------------------------------------------- the lens (2.9.7)
  function lensState() { return { h: S.h, m: S.m, q: S.q, esq: S.esq, set: S.set }; }

  function confOption(f, isDefault) { return { value: isDefault ? "" : String(f), label: pctWord(f) + "%" + (isDefault ? " · default" : "") }; }

  /** The Confidence and ES selects from the engine's configured lists (/status: confidences, esConfidences), the defaults marked. */
  function loadLensPicker() {
    api("status", {}).then(function (st) {
      S.lensStatus = st;
      [["[data-vx-conf]", st.confidences || [], st.confidence, "q"], ["[data-vx-esconf]", st.esConfidences || [], st.esConfidence, "esq"]].forEach(function (x) {
        if (S[x[3]] && Math.abs(Number(S[x[3]]) - x[2]) < 1e-9) S[x[3]] = "";      // the default named: kept as the default
        picker(x[0]).setOptions(x[1].map(function (f) { return confOption(f, Math.abs(f - x[2]) < 1e-9); }), S[x[3]] || "");
      });
      root.dataset.lensReady = "true";
    }).catch(function () { /* the selects keep their one option */ });
  }

  /** The selects as the answer says its lens (a command or a link may name the default 10-day method, or none). */
  function syncLensPicker(ln) {
    if (!ln) return;
    var hz = $("[data-vx-horizon]");
    hz.value = ln.horizon === "10D" ? "10d:" + (ln.method === "SUM" ? "sum" : "sqrt10") : "1d";
    $("[data-vx-scenarios]").value = ln.set === "STRESSED" ? "stressed" : "";
  }

  /** A new lens: every panel again, the link and the command, and the person's settings (varView) for the next visit. */
  function setLens(next) {
    Object.keys(next).forEach(function (k) { S[k] = next[k]; });
    if (S.h !== "10d") { S.h = ""; S.m = ""; }
    var u = lensLink(new URL(location.href));
    history.replaceState({ scope: S.scope, ccy: S.ccy, lens: lensState() }, "", u.toString());
    fetch("/api/settings", { method: "PATCH", credentials: "same-origin", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ varView: { h: S.h || "1d", m: S.m || null, q: S.q ? Number(S.q) : null, esq: S.esq ? Number(S.esq) : null,
                                        set: S.set || "normal" } })
    }).catch(function () { /* the link still carries it */ });
    S.children = {};
    S.tree = null;
    loadTree();
    select(S.scope, { noPush: true });
    document.dispatchEvent(new CustomEvent("drishti:var-lens", { detail: lensState() }));
  }
  // the header's pickers are <drishti-select>s (drishti-inputs): drishti:change carries the value picked
  $("[data-vx-horizon]").addEventListener("drishti:change", function (ev) {
    var v = String(ev.detail.value).split(":");
    setLens({ h: v[0] === "10d" ? "10d" : "", m: v[1] || "" });
  });
  $("[data-vx-conf]").addEventListener("drishti:change", function (ev) { setLens({ q: ev.detail.value }); });
  $("[data-vx-scenarios]").addEventListener("drishti:change", function (ev) { setLens({ set: ev.detail.value === "stressed" ? "stressed" : "" }); });
  $("[data-vx-esconf]").addEventListener("drishti:change", function (ev) { setLens({ esq: ev.detail.value }); });

  $("[data-vx-ccy]").addEventListener("drishti:change", function (ev) {
    var v = ev.detail.value, sel = picker("[data-vx-ccy]");
    if (v === "__more") { sel.setValue(S.ccy); loadPicker(true); sel.focus(); return; }
    setCcy(v);
  });

  // ---------------------------------------------------------------------------------------------------------------- the tree
  // A new lens, currency or hierarchy asks the tree again while an earlier ask may still be out: only the newest answer is drawn (an
  // earlier one landing last drew the old lens's figures under the new lens's tiles), and the tree is aria-busy until it is drawn.
  function loadTree() {
    var seq = S.treeSeq = (S.treeSeq || 0) + 1, box = $("[data-vx-tree]");
    fresh("tree", "loading…", "computing");
    S.treeLoading = true;
    box.setAttribute("aria-busy", "true");
    return api("tree", { hierarchy: S.hierarchy }).then(function (d) {
      if (seq !== S.treeSeq) return;
      S.treeLoading = false;
      box.removeAttribute("aria-busy");
      S.tree = d;
      fillHierarchies(d);
      var byId = {};
      d.nodes.forEach(function (n) { byId[n.id] = n; });
      S.byId = byId;
      renderTree();
      renderAdhoc();
      fresh("tree", d.businessDate);
    }).catch(function (e) {
      if (seq !== S.treeSeq) return;
      S.treeLoading = false;
      box.removeAttribute("aria-busy");
      if (e.status === 404 && S.hierarchy !== "MGMT") {             // a hierarchy the date has not: the management tree, said
        var n = $("[data-vx-notice]");
        n.hidden = false;
        n.textContent = (e.message || ("No hierarchy " + S.hierarchy + " on this date")) + " The management tree is shown.";
        setHierarchy("MGMT");
        return loadTree();
      }
      errorBox($("[data-vx-tree]"), e, loadTree);
      fresh("tree", "failed", "stale");
    });
  }

  /** The hierarchy selector from the date's hierarchies (every configured one, by its title), the current one chosen. */
  function fillHierarchies(d) {
    var sel = picker("[data-vx-hierarchy]");
    var info = d.hierarchyInfo || (d.hierarchies || []).map(function (id) { return { id: id, title: id }; });
    if (!info.length) return;
    sel.setOptions(info.map(function (x) { return { value: x.id, label: x.title || x.id, detail: (x.levels || []).join(" > ") }; }), d.hierarchy || S.hierarchy);
  }

  /** The hierarchy shown, kept in the page's link (hierarchy=LL). */
  function setHierarchy(id) {
    S.hierarchy = id;
    root.dataset.hierarchy = id;
    var u = new URL(location.href);
    if (id === "MGMT") u.searchParams.delete("hierarchy"); else u.searchParams.set("hierarchy", id);
    history.replaceState(history.state, "", u.toString());
  }

  // The tree is drishti-tree (its tree grid, through drishti-risk's VaR hierarchy preset) inside this panel: every figure is the
  // server's row as it came (VaR and ES never summed here); a book's positions and a position's trades come from the contributions of
  // that scope the first time its row opens ("computing…" until then) and are kept for the next drawing, as is what is open.
  /** A hierarchy node's row for the tree (2.9.7: "n/c" when the limit is of another horizon or confidence). */
  function nodeRow(n) {
    return { ID: n.id, PARENT: n.parent || null, LABEL: n.name, name: n.name, LEVEL: n.level, level: n.level, scope: n.scope, parametric: n.parametric,
      VAR: n["var"], ES: n.es, LIMIT_USE: n.limitUse, LIMIT_STATUS: n.limitStatus, LIMIT_NOTE: n.limitComparable === false ? "n/c" : "no limit",
      blank: !!n.blank, blankNote: n.blankNote || "" };
  }
  /** A book's positions, or a position's trades (the contributions of that scope), as rows under the part's row; a position's are lazy. */
  function partRows(parentId, kids) {
    return kids.map(function (r) {
      var isPos = r.kind === "position", name = (isPos ? r.label : r.key) + (r.quantity !== undefined ? " · " + money(r.quantity) : "");
      return { ID: parentId + "/" + r.scope, PARENT: parentId, LABEL: name, name: name, LEVEL: r.kind, kind: r.kind, scope: r.scope, direction: r.direction,
        VAR: r.standaloneVar, ES: r.standaloneEs, LIMIT_USE: null, LIMIT_NOTE: "c " + money(r.componentVar), lazy: isPos };
    });
  }
  function treeRows() {
    var out = [];
    function parts(id, scope) {                       // the parts already asked go in at once; true: still to come (lazy)
      var kids = S.children[scope];
      if (!kids || kids.error) return true;
      partRows(id, kids).forEach(function (r) { out.push(r); if (r.lazy) r.lazy = parts(r.ID, r.scope); });
      return false;
    }
    (S.tree.nodes || []).forEach(function (n) {
      var r = nodeRow(n);
      out.push(r);
      if (n.level === "book" && n.expandable) r.lazy = parts(n.id, n.scope);
    });
    return out;
  }
  /** drishti-tree's loadChildren: a part's children, asked the first time its row opens (an answer for an older lens or currency is dropped). */
  function loadChildren(req) {
    var scope = req.data.scope, box = S.children, have = box[scope];
    var ask = have ? Promise.resolve(have) : api("scope/contributions", { scope: scope }).then(function (d) { return d.rows; }, function (e) { return { error: e.message }; });
    return ask.then(function (rows) {
      if (S.children === box) box[scope] = rows;
      if (rows.error) throw new Error(rows.error);
      return partRows(req.id, rows);
    });
  }

  /** The scope's cell: the direction chip, the name, "+ basket". */
  function nameCell(c) {
    var r = c.row, s = h("span", "nm");
    if (r.direction) s.appendChild(chip(r.direction));
    s.appendChild(document.createTextNode(" "));
    if (r.blank) {                                     // a (Blank) node: muted, in italics, its ? the count of trades and the fix
      s.appendChild(h("span", "dg-blank", r.name));
      var q = h("span", "dg-blank-q", "?");
      q.title = r.blankNote;
      q.setAttribute("role", "img");
      q.setAttribute("aria-label", r.blankNote);
      s.appendChild(q);
    } else {
      s.appendChild(document.createTextNode(r.name));
    }
    s.appendChild(addButton(r.scope, { type: r.kind === "position" ? "POS" : r.kind === "trade" ? "TRD" : r.scope.split(":")[0], id: r.scope.replace(/^[A-Z]+:/, ""), name: r.name }));
    return s;
  }

  /** The tree's options: drishti-risk's VaR hierarchy with this screen's columns, its look of a scope's row and its lazy parts. */
  function treeOptions(rows, pcols, wkey) {
    var widths = {}, P = pcols && window.Drishti.VarParametric;
    try { widths = JSON.parse(localStorage.getItem(wkey) || "{}") || {}; } catch (e) { widths = {}; }
    return R.varHierarchyTree(rows, { title: "Scope", label: "Scope tree", chrome: false, openDepth: 2, lazyText: "computing…", loadChildren: loadChildren,
      fields: ["VAR", "ES", "LIMIT_USE"],
      measures: { VAR: { label: "VaR " + S.ccy, childSum: false, column: { width: 86 } },
                  ES: { label: "ES", column: { width: 80, cellClass: function () { return R.TONES.muted; } } },
                  LIMIT_USE: { label: "Limit", column: { width: 96, sortable: false } } },
      grid: { fitRows: 40, widths: widths, labelColumn: { header: "Scope", width: 230, pinned: "left", cellRenderer: nameCell },
        columns: P ? P.treeColumns() : [],                       // the Parametric and Gap columns, each with its ?
        rowClass: function (r) { return r.scope && r.scope === S.scope ? "vx-current" : null; },
        rowData: function (r) { return { scope: r.scope || null, current: r.scope ? String(r.scope === S.scope) : null }; },
        onResize: function (w) { try { localStorage.setItem(wkey, JSON.stringify(w)); } catch (e) { /* kept for this page only */ } } } });
  }

  /** The tree drawn: new rows into the same tree (what is open kept); made again when its columns change (the currency, Parametric). */
  function renderTree() {
    var t = $("[data-vx-tree]"), pcols = PM && !!window.Drishti.VarParametric, lay = t.closest(".vx-layout");
    if (lay) lay.setAttribute("data-resize-mode", pcols ? "parametric" : "historical");      // the side panel's width a person set, kept per mode
    t.classList.toggle("vp-cols", !!pcols);
    // the columns' widths a person set, kept per mode in this browser (drishti-grid's own resize handles)
    var wkey = "drishti.var.tree.widths." + (pcols ? "parametric" : "historical"), sig = wkey + "|" + S.ccy;
    var el = t.querySelector(":scope > drishti-tree"), opts = treeOptions(treeRows(), pcols, wkey);
    if (el && el.tree && el.dataset.sig === sig) { el.tree.setData(opts.data); markTree(); return; }
    if (!el) { el = document.createElement("drishti-tree"); t.textContent = ""; t.appendChild(el); }
    el.dataset.sig = sig;
    el.options = opts;
    var tree = el.tree;
    Object.keys(S.open).forEach(function (id) { if (tree.model.hasChildren(id)) tree.toggle(id, S.open[id]); });
    tree.on("nodeExpanded", function (e) { S.open[e.id] = true; });
    tree.on("nodeCollapsed", function (e) { S.open[e.id] = false; });
    tree.on("selectionChanged", function (e) {                 // a click on a row: that scope
      var n = e.ids.length ? tree.model.node(e.ids[0]) : null;
      if (n && n.data.scope) select(n.data.scope);
    });
    markTree();
  }

  function markTree() {
    var el = $("[data-vx-tree] > drishti-tree"), tree = el && el.tree;
    if (tree) {                                                                     // the current scope's row marked (vx-current)
      var at = tree.model.all().filter(function (n) { return n.data.scope === S.scope; })[0];
      tree.select(at ? at.id : null, { quiet: true });
    }
    if (window.Drishti.VarLayout) window.Drishti.VarLayout.mark(S.scope);          // the basket drop-downs name the scope when it is one of theirs
  }

  /** The baskets in the toolbar's two drop-downs (2.9.11.b, drishti_var-layout.js): System baskets are the bank's (the tree's answer: the
      basket store's system scope, VAR_GUIDE.md section 5.7), My baskets the saved baskets the engine lists for me (mine, then those shared
      with me). Each row keeps the actions the column had: + basket, Edit a copy (a system basket, a basket shared read-only), Edit and Delete. */
  function renderAdhoc() {
    var L = window.Drishti.VarLayout;
    if (!L) return;
    L.setItems("system", ((S.tree && S.tree.portfolios) || []).map(function (p) {
      var type = String(p.type || "").toLowerCase();
      return { scope: p.scope, label: p.name, desc: p.description || "", meta: [p.id, p.owner].filter(Boolean).join(" · "), chip: type,
               acts: [addButton(p.scope, { type: "PORT", id: p.scope.replace(/^[A-Z]+:/, ""), name: p.name }),
                      editButton(p.scope, p.name, true)] };            // the bank's: a copy (a holder of var:system-baskets edits it in the sheet)
    }));
    api("baskets", {}, true).then(function (bs) {
      S.baskets = bs;
      var own = bs.filter(function (b) { return b.owner === S.user; }), shared = bs.filter(function (b) { return b.owner !== S.user; });
      L.setItems("mine", own.concat(shared).map(function (b) {
        var scope = "BASKET:" + b.id, mine = b.owner === S.user || S.admin, acts = [addButton(scope, { type: "BASKET", id: b.id, name: b.name }),
          editButton(scope, b.name, !mine)];
        if (mine) acts.push(deleteButton(b));
        return { scope: scope, label: b.name, desc: b.description || "", meta: b.id + (b.sharedWith && b.sharedWith.length && b.owner === S.user
          ? " · shared with " + b.sharedWith.join(", ") : ""), group: b.owner === S.user ? "Mine" : "Shared with me", acts: acts };
      }));
      markTree();
    }).catch(function () { L.setItems("mine", []); });
    markTree();
  }

  /** "Edit" (a basket the viewer owns, or anyone's for an administrator) or "Edit a copy" (a system basket, a basket shared read-only): the sheet. */
  function editButton(scope, name, copy) {
    var x = h("button", "vx-add vx-edit", copy ? "Edit a copy" : "Edit");
    x.type = "button";
    x.dataset.vxEdit = scope;
    x.title = copy ? "Open a copy of " + scope + " in the basket's sheet, to save as your own basket" : "Open " + scope + " in the basket's sheet: Save keeps its name as a new version";
    x.setAttribute("aria-label", (copy ? "Edit a copy of " : "Edit ") + name);
    x.addEventListener("keydown", function (ev) { ev.stopPropagation(); });
    x.addEventListener("click", function (ev) {
      ev.stopPropagation();
      if (!B) return;
      if (strip) { strip.hidden = false; }
      B.edit(scope);
    });
    return x;
  }

  /** "Delete" on a saved basket the viewer owns (an administrator: on every basket listed): a confirm dialog (drishti_scope-delete.js). */
  function deleteButton(b) {
    var x = h("button", "vx-add vx-del", "Delete");
    x.type = "button";
    x.dataset.vxDelete = b.id;
    x.title = "Delete the basket " + b.id;
    x.setAttribute("aria-label", "Delete the basket " + b.name);
    x.addEventListener("keydown", function (ev) { ev.stopPropagation(); });
    x.addEventListener("click", function (ev) {
      ev.stopPropagation();
      if (!window.Drishti.ScopeDelete) return;
      window.Drishti.ScopeDelete.ask(b, { admin: S.admin, me: S.user }).then(function (deleted) {
        if (!deleted) return;
        cache.clear();
        if (B && B.state.saved && B.state.saved.id === b.id) B.state.saved = null;   // the sheet keeps its parts, as an unsaved basket
        var n = $("[data-vx-notice]");
        n.hidden = false;
        n.dataset.vxDeleted = b.id;
        n.textContent = "The basket " + b.name + " (BASKET:" + b.id + ") was deleted. Its captures are kept.";
        renderAdhoc();
        if (S.scope === "BASKET:" + b.id) {
          n.textContent += " It no longer exists, so this view has nothing to compute: pick another scope.";
          select(S.scope, { noPush: true });
        }
      });
    });
    return x;
  }

  // ---------------------------------------------------------------------------------------------------------------- the basket's sheet
  /** The basket's sheet as a strip at the top (drishti_var-basket.js): it moves the explorer when saved or opened, and while the explorer shows
      the sheet's own unsaved scope, every change moves it too (the VaR follows the sheet). */
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
    // the sheet opens only when asked (Construct your basket, Alt+B, + basket, Edit): a basket kept in this browser waits behind the link's count
    // saved: the basket joins My baskets (onOpen lists them again) and the sheet collapses (2.9.11.b)
    document.addEventListener("drishti:vb-saved", function (ev) { root.dataset.saved = ev.detail.id; B.hide(); });
    // a system basket saved or retired in the sheet (VAR_GUIDE.md section 5.7): System baskets lists the store as it is now
    document.addEventListener("drishti:vb-system-saved", function (ev) { root.dataset.systemSaved = ev.detail.id + "@" + ev.detail.version; S.tree = null; loadTree(); });
  }
  $("[data-vx-basket-open]").addEventListener("click", function () { if (B) { B.show(); var f = root.querySelector("[data-vb-find]"); if (f) f.focus(); } });
  $("[data-vx-whatif]").addEventListener("click", function () {
    if (!B) return;
    if (window.Drishti.Explain) window.Drishti.Explain.close();     // the Explain drawer gives way to the sheet it opens
    if (B.isEmpty() && S.head) B.add(S.head.kind === "basket" ? S.head.canonical : S.scope, { name: S.head.label });
    B.openWhatIf();
  });

  /** The tab in the link (tab=hist, contrib, captures, ccy). */
  function tabState(tab) {
    root.querySelectorAll(".vx-tab").forEach(function (x) { x.setAttribute("aria-selected", x.dataset.tab === tab ? "true" : "false"); });
    root.querySelectorAll("[data-pane]").forEach(function (p) { p.hidden = p.dataset.pane !== tab; });
  }

  // ---------------------------------------------------------------------------------------------------------------- tabs, toggles, exports
  function switchTab(tab) {
    S.tab = tab;
    var tu = new URL(location.href);
    if (tab === "path") tu.searchParams.delete("tab"); else tu.searchParams.set("tab", tab);
    history.replaceState(history.state, "", tu.toString());
    tabState(tab);
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
  $("[data-vx-hierarchy]").addEventListener("drishti:change", function (ev) { setHierarchy(ev.detail.value); loadTree(); });
  $("[data-vx-capture]").addEventListener("click", function () { doCapture($("[data-vx-cap-note]")); });

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
    $("[data-vx-scenarios]").value = S.set;
  })();
  loadPicker(false);
  loadLensPicker();
  loadTree();
  if (S.tab !== "path") tabState(S.tab);
  if (root.dataset.capture) {
    api("captures/" + encodeURIComponent(root.dataset.capture), {}, true).then(function (c) {
      select(c.scope, { noPush: true });
      switchTab("captures");
      reopen(c.id);
    }).catch(function (e) { select(S.scope, { noPush: true }); errorBox($("[data-vx-tiles]"), e); });
  } else {
    select(S.scope, { noPush: true });
  }
  // the Backtest link follows the scope and date in view (VAR <scope> BACKTEST, /var/backtesting)
  var bt = document.querySelector("[data-vx-backtest]");
  if (bt) bt.addEventListener("click", function (ev) {
    ev.preventDefault();
    location.href = "/var/backtesting?scope=" + encodeURIComponent(S.scope || "BANK") + (S.asOf ? "&asOf=" + encodeURIComponent(S.asOf) : "");
  });

  // the MR cube link: the Pivot's preset mr on the scope and date in view (MCUBE <scope> [DATE d], /pivot?preset=mr)
  var cube = document.querySelector("[data-vx-cube]");
  if (cube) cube.addEventListener("click", function (ev) {
    ev.preventDefault();
    location.href = "/pivot?preset=mr&text=" + encodeURIComponent((S.scope || "BANK") + (S.asOf ? " DATE " + S.asOf : ""));
  });

  window.Drishti.VarExplorer = { select: select, switchTab: switchTab, setCcy: setCcy, setLens: setLens, state: S, api: api, words: words,
    lensWords: lensWords, explainReq: explainReq, renderTree: function () { if (S.tree) renderTree(); },
    redrawPath: function () { if (S.path && S.path.series && S.path.series.some(function (v) { return v !== 0; })) drawPath(S.path); } };
  if (window.Drishti.VarParametric) window.Drishti.VarParametric.start(window.Drishti.VarExplorer);
})();
