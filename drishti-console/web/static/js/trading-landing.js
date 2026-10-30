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
/* Drishti Trading's Home (home/trading_landing.html; EDITIONS.md section 14): paints the landing from the console's answers, which
   core/trading_landing.py works out from the servers' own, read as the person: /api/home/trading/me (whose figures: the trader or desk,
   their books, and the View as picker when they may pick), then valuations (P&L today, by book and trader, movers, DV01, the largest
   counterparty), blotter (today's trades), risk (VaR, limits, positions), limits (limit use by book over the last business dates),
   pnl-var (the desk's P&L against VaR) and traders (by trader); the new-trade check posts to /api/home/trading/check. Each panel is painted
   when its answer arrives; one refused or empty says so in place and the others carry on. Nothing is invented: a figure the data cannot
   give is left out (DV01) or says why. The chart is SVG built through the DOM (the page's policy allows no inline style). */
(function () {
  "use strict";
  var root = document.querySelector("[data-trading-landing]");
  if (!root) return;
  var landing = root.getAttribute("data-trading-landing");
  var SVG = "http://www.w3.org/2000/svg";
  var FOR = landing === "desk" ? "?for=desk" : "";            // the Desk head landing of a trader: their desk
  var $ = function (sel) { return root.querySelector(sel); };
  var $$ = function (sel) { return Array.prototype.slice.call(root.querySelectorAll(sel)); };

  function get(path) {
    return fetch(path, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(answer);
  }
  function post(path, body) {
    return fetch(path, { method: "POST", headers: { Accept: "application/json", "Content-Type": "application/json" }, credentials: "same-origin",
      body: JSON.stringify(body) }).then(answer);
  }
  function answer(r) {
    return r.json().catch(function () { return {}; }).then(function (b) {
      if (!r.ok) { var e = new Error((b && (b.detail || b.reason)) || ("HTTP " + r.status)); e.code = b && b.code; throw e; }
      return b;
    });
  }
  function num(v) { return v === null || v === undefined || v === "" || isNaN(v) ? null : Number(v); }
  /** 1,840,000 → 1.84m; −142,000 → −142k; the sign as a real minus. */
  function money(v, signed) {
    v = num(v);
    if (v === null) return "—";
    var a = Math.abs(v), s = v < 0 ? "−" : signed && v > 0 ? "+" : "";
    if (a >= 1e9) return s + (a / 1e9).toFixed(2) + "bn";
    if (a >= 1e6) return s + (a / 1e6).toFixed(2) + "m";
    if (a >= 1e3) return s + (a / 1e3).toFixed(0) + "k";
    return s + a.toFixed(0);
  }
  function pct(v) { v = num(v); return v === null ? "—" : Math.round(v * 100) + "%"; }
  function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt !== undefined) e.textContent = txt; return e; }
  function go(cmd) { return "/go?q=" + encodeURIComponent(cmd + " <GO>"); }
  function link(cmd, label, cls) { var a = el("a", cls || "mono", label || cmd); a.href = go(cmd); return a; }
  function tone(e, v) { v = num(v); if (v !== null && v !== 0) e.classList.add(v > 0 ? "up" : "down"); return e; }
  function cell(tr, content, cls) { var td = el("td", cls || ""); if (content && content.nodeType) td.appendChild(content); else td.textContent = content; tr.appendChild(td); return td; }
  function said(target, msg, cols) {
    if (!target) return;
    target.textContent = "";
    msg = typeof msg === "string" ? msg : "Not available: " + (msg && msg.message || msg);
    if (target.tagName === "TBODY") { var tr = el("tr"), td = el("td", "tl-muted", msg); td.colSpan = cols || 3; tr.appendChild(td); target.appendChild(tr); }
    else target.appendChild(el("p", "tl-muted", msg));
  }
  function tile(id, value, sub, cls) {
    var box = $("[data-tl-tile='" + id + "']");
    if (!box) return null;
    var v = box.querySelector("[data-tl-v]");
    v.textContent = "";
    if (value && value.nodeType) v.appendChild(value); else v.textContent = value;
    box.querySelector("[data-tl-s]").textContent = sub || "";
    box.classList.remove("warn", "bad", "good");
    if (cls) box.classList.add(cls);
    return box;
  }
  var STATE_TILE = { breach: "bad", near: "warn", ok: "good" };

  // ---- who "my" is, and the View as picker ----------------------------------------------------------------------------------------
  function who(m) {
    var s = m.scope || {}, line = $("[data-tl-who]");
    line.textContent = "";
    if (!s.kind) {
      line.appendChild(document.createTextNode("Nobody yet: pick a trader or a desk to view as. The figures are read as you, with your roles."));
    } else {
      var name = el("b", "", s.name || s.id);
      line.appendChild(name);
      line.appendChild(document.createTextNode(s.kind === "desk" ? " · desk " + s.id + (s.head ? " · head " + s.head : "") : " · " + s.id +
        (s.deskName ? " · " + s.deskName : "")));
      if ((s.books || []).length) line.appendChild(document.createTextNode(" · " + s.books.join(", ")));
      var how = { config: "set by your administrator", record: "your trader record", choice: "your choice" }[s.source];
      if (how) line.appendChild(el("span", "tl-muted", " (" + how + ")"));
      (s.problems || []).forEach(function (p) { line.appendChild(el("span", "tl-muted", " · " + p)); });
    }
    root.setAttribute("data-tl-scope", s.kind ? s.kind + ":" + s.id : "");
    var form = $("[data-tl-viewas]");
    if (form && s.canPick && m.options) {
      var sel = $("[data-tl-as]");
      [["Traders", "trader", m.options.traders || []], ["Desks", "desk", m.options.desks || []]].forEach(function (g) {
        if (!g[2].length) return;
        var og = el("optgroup"); og.label = g[0];
        g[2].forEach(function (o) {
          var opt = el("option", "", o.name === o.id ? o.id : o.name + " · " + o.id);
          opt.value = g[1] + ":" + o.id;
          if (s.kind === g[1] && s.id === o.id) opt.selected = true;
          og.appendChild(opt);
        });
        sel.appendChild(og);
      });
      form.hidden = false;
    }
    return s;
  }

  // ---- A · Trader desk ------------------------------------------------------------------------------------------------------------
  function trader(m) {
    var s = m.scope;
    if (!$("[data-tl-check-card]")) return;
    if (!m.check) $("[data-tl-check-card]").hidden = true;
    var book = $("[data-tl-book]");
    (s.books || []).forEach(function (b) { var o = el("option", "", b); o.value = b; book.appendChild(o); });
    get("/api/home/trading/valuations" + FOR).then(function (v) {
      if (!v.available) throw new Error(v.reason);
      $$("[data-tl-date]").forEach(function (e) { e.textContent = v.businessDate + (v.dataDate && v.dataDate !== v.businessDate ? " (the latest valuations are of " + v.dataDate + ")" : ""); });
      $("[data-tl-count]").textContent = v.liveTrades.toLocaleString("en-US") + " live trades in my books" + (v.partial ? " (partial: " +
        v.trades.toLocaleString("en-US") + " of " + v.matched.toLocaleString("en-US") + " read)" : "");
      if (v.pnl === null) tile("pnl", "—", v.mtmMasked ? "MtM is masked for your role" : "no valuations on " + v.previousDate + ": no change to show");
      else tile("pnl", tone(el("span", "", money(v.pnl, true)), v.pnl), "MtM change since " + v.previousDate + " · " +
        v.valuedBoth.toLocaleString("en-US") + " trades valued both days", v.pnl > 0 ? "good" : v.pnl < 0 ? "bad" : null);
      if (v.dv01 !== null) {
        $("[data-tl-tile='dv01']").hidden = false;
        tile("dv01", money(v.dv01, true), "per bp, " + v.dv01Trades.toLocaleString("en-US") + " live trades");
      }
      var prod = $("[data-tl-product]");
      prod.textContent = "";
      prod.appendChild(el("option", "", v.products.length ? "Pick a product…" : "no product in your books"));
      prod.firstChild.value = "";
      v.products.forEach(function (p) { var o = el("option", "", p); o.value = p; prod.appendChild(o); });
      var dl = $("[data-tl-cps]");
      v.counterparties.forEach(function (c) { var o = el("option"); o.value = c; dl.appendChild(o); });
      quick(s, v);
    }).catch(function (e) {
      $$("[data-tl-date]").forEach(function (x) { x.textContent = "—"; });
      $("[data-tl-count]").textContent = "";
      tile("pnl", "—", e.message);
    });
    get("/api/home/trading/risk" + FOR).then(function (r) {
      if (!r.available) throw new Error(r.reason);
      var conf = num(r.confidence);
      $("[data-tl-var-k]").textContent = "My VaR" + (conf ? " (" + Math.round(conf * 100) + "%, 1-day)" : "");
      tile("var", money(r["var"]), r.intraday ? "intraday · " + (r.intraday.trades || 0) + " trades updated" + (r.intraday.lastAt ? " · " +
        String(r.intraday.lastAt).slice(11, 16) : "") : "end of day · " + (r.trades || 0).toLocaleString("en-US") + " trades");
      if (r.intraday) $("[data-tl-intraday]").textContent = "· intraday as of " + String(r.intraday.lastAt || "").slice(11, 16);
      var w = r.worst;
      if (w) tile("limit", pct(w.limitUse), w.book + " · limit " + money(w.limit) + ((r.books || []).length > 1 ? " · the highest of " + r.books.length + " books" : ""),
        STATE_TILE[w.state]);
      else tile("limit", "—", "no VaR limit set on my books");
      var body = $("[data-tl-positions]");
      body.textContent = "";
      if (!(r.positions || []).length) { said(body, r.positionsProblem || "No positions in the VaR answer", 3); return; }
      r.positions.forEach(function (p) {
        var tr = el("tr");
        cell(tr, p.scope ? (function () { var a = el("a", "", p.label); a.href = "/var?scope=" + encodeURIComponent(p.scope); return a; })() : p.label);
        cell(tr, tone(el("span", "", money(p.marketValue, true)), p.marketValue), "n");
        cell(tr, money(p.componentVar), "n");
        body.appendChild(tr);
      });
    }).catch(function (e) {
      tile("var", "—", e.message); tile("limit", "—", "");
      said($("[data-tl-positions]"), e, 3);
    });
    get("/api/home/trading/blotter" + FOR).then(function (b) {
      if (!b.available) throw new Error(b.reason);
      var c = b.counts || {};
      tile("trades", String(b.total), (c["new"] || 0) + " new · " + (c.amended || 0) + " amended · " + (c.cancelled || 0) + " cancelled" +
        (b.partial ? " · of " + b.matched : "") + (b.how === "valuations" ? " · " + b.note : ""));
      var body = $("[data-tl-blotter]");
      body.textContent = "";
      if (!b.rows.length) { said(body, "No trade of my books changed on " + b.businessDate, 5); return; }
      b.rows.slice(0, 12).forEach(function (r) {
        var tr = el("tr");
        tr.setAttribute("data-tl-event", r.event);
        cell(tr, r.time || "—");
        cell(tr, link("TRD " + r.trade, r.trade));
        cell(tr, r.counterparty || "", "tl-wide");
        cell(tr, r.notional === null || r.notional === undefined ? "—" : money(r.notional), "n");
        var p = el("td"); p.appendChild(el("span", "tl-pill " + r.event, r.event)); tr.appendChild(p);
        tr.title = (r.product || "") + (r.version ? " · version " + r.version : "") + (r.status ? " · " + r.status : "");
        body.appendChild(tr);
      });
    }).catch(function (e) { tile("trades", "—", e.message); said($("[data-tl-blotter]"), e, 5); });
    $("[data-tl-check]").addEventListener("submit", function (ev) { ev.preventDefault(); runCheck(); });
  }

  function quick(s, v) {
    var nav = $("[data-tl-quick]");
    var mine = [];
    if (s.kind === "trader") mine.push(["TRDR " + s.id, "me: books, trades, desk"]);
    if ((s.books || []).length) {
      mine.push(["VAR BOOK " + s.books[0], "my book's VaR, path and contributions"]);
      mine.push(["BOOK " + s.books[0], "my book: trades, positions, MtM"]);
    }
    if ((v.counterparties || []).length) mine.push(["CTRD " + v.counterparties[0], "my most traded counterparty's trades"]);
    mine.reverse().forEach(function (q) {
      if (nav.querySelector("[data-home-card='" + q[0] + "']")) return;
      var a = el("a", "tl-q"); a.href = go(q[0]); a.setAttribute("data-home-card", q[0]); a.setAttribute("data-tl-mine", "1");
      a.appendChild(el("code", "mono", q[0])); a.appendChild(el("span", "", q[1]));
      nav.insertBefore(a, nav.firstChild);
    });
  }

  var WORDS = { ok: "fits", near: "near", breach: "breaches" };
  function runCheck() {
    var f = $("[data-tl-check]"), btn = $("[data-tl-check-go]");
    var body = { product: f.product.value, side: f.side.value, notional: f.notional.value, counterparty: f.counterparty.value, book: f.book.value };
    var vb = $("[data-tl-verdict-var]"), xb = $("[data-tl-verdict-exp]");
    btn.disabled = true;
    root.removeAttribute("data-tl-checked");
    vb.textContent = "computing the VaR what-if…"; xb.textContent = "computing the pre-deal check…";
    post("/api/home/trading/check", body).then(function (c) {
      if (!c.ok) { vb.textContent = ""; vb.appendChild(el("span", "tl-muted", c.reason)); xb.textContent = ""; return; }
      verdictVar(vb, c.var || {});
      verdictExp(xb, c.exposure || {}, c.counterparty);
      $("[data-tl-check-note]").textContent = "Priced as " + (Math.round(c.factor * 1000) / 1000) + " × trade " + c.template.trade + " (" +
        c.template.product + ", " + money(c.template.notional) + (c.template.counterparty ? " with " + c.template.counterparty : "") + ", " +
        c.template.book + "): the VaR engine's what-if on " + (c.var && c.var.scope || "the book") + " and the exposure engine's pre-deal check on " +
        (c.exposure && c.exposure.scope || "the counterparty") + ".";
      root.setAttribute("data-tl-checked", (c.var || {}).state + "/" + (c.exposure || {}).state);
    }).catch(function (e) { vb.textContent = "Not available: " + e.message; xb.textContent = ""; })
      .then(function () { btn.disabled = false; });
  }
  function verdictVar(box, v) {
    box.textContent = "";
    if (v.state === "unavailable") { box.appendChild(el("b", "", "VaR what-if not available")); box.appendChild(el("br")); box.appendChild(el("span", "tl-muted", v.reason || "")); return; }
    var head = v.state === "none" ? "VaR " + (v.change >= 0 ? "rises" : "falls") + " (no limit set)" : v.state === "ok" ? "Fits my VaR" :
      v.state === "near" ? "Near my VaR limit" : "Breaches my VaR limit";
    box.appendChild(el("b", v.state === "none" ? "" : v.state, head));
    box.appendChild(el("br"));
    box.appendChild(document.createTextNode(money(v.before) + " → " + money(v.after) + (v.limit ? " · limit use " + pct(v.useBefore) + " → " + pct(v.useAfter) +
      " of " + money(v.limit) : "") + " · " + v.scope));
    box.setAttribute("data-tl-state", v.state);
  }
  function verdictExp(box, x, cp) {
    box.textContent = "";
    if (x.state === "unavailable") { box.appendChild(el("b", "", "Pre-deal check not available")); box.appendChild(el("br")); box.appendChild(el("span", "tl-muted", x.reason || "")); return; }
    var head = x.state === "none" ? cp + " has no credit limit" : "Counterparty " + WORDS[x.state] + (x.state === "near" ? " its limit" : x.state === "breach" ? " its limit" : "");
    box.appendChild(el("b", x.state === "none" ? "" : x.state, head));
    box.appendChild(el("br"));
    var q = num(x.confidence);
    box.appendChild(document.createTextNode(cp + " peak PFE" + (q ? " " + Math.round(q * 100) + "%" : "") + " " + money(x.before) + " → " + money(x.after) +
      (x.limit ? " · " + pct(x.useAfter) + " of limit " + money(x.limit) : "")));
    if (x.proxy) box.appendChild(el("span", "tl-muted", " · proxy (add-on: " + (x.fallback ? "the paths engine is not running" : "no netting set to deal into") + ")"));
    box.setAttribute("data-tl-state", x.state);
  }

  // ---- B · Desk head --------------------------------------------------------------------------------------------------------------
  function desk(m) {
    var s = m.scope;
    get("/api/home/trading/valuations" + FOR).then(function (v) {
      if (!v.available) throw new Error(v.reason);
      $$("[data-tl-date]").forEach(function (e) { e.textContent = v.businessDate + (v.dataDate && v.dataDate !== v.businessDate ? " (the latest valuations are of " + v.dataDate + ")" : ""); });
      $("[data-tl-mtm]").textContent = money(v.mtm, true);
      $("[data-tl-desk-count]").textContent = (s.books || []).length + " books · " + v.liveTrades.toLocaleString("en-US") + " live trades" +
        (v.partial ? " (partial)" : "");
      if (v.pnl === null) tile("pnl", "—", v.mtmMasked ? "MtM is masked for your role" : "no valuations on " + v.previousDate + ": no change to show");
      else {
        var best = (v.byBook || []).filter(function (b) { return b.pnl !== null; }).sort(function (a, b) { return b.pnl - a.pnl; })[0];
        tile("pnl", tone(el("span", "", money(v.pnl, true)), v.pnl), "MtM change since " + v.previousDate + (best ? " · best " + best.book + " " + money(best.pnl, true) : ""),
          v.pnl > 0 ? "good" : v.pnl < 0 ? "bad" : null);
      }
      var lc = v.largestCounterparty;
      if (lc) tile("cpty", pct(lc.share), "of the desk's positive MtM · " + lc.counterparty);
      else tile("cpty", "—", "no counterparty with a positive MtM");
      var body = $("[data-tl-movers]");
      body.textContent = "";
      if (!(v.movers || []).length) { said(body, v.hasPrevious ? "No trade's MtM moved" : "No valuations on " + v.previousDate + ": no overnight change", 3); return; }
      v.movers.slice(0, 8).forEach(function (x) {
        var tr = el("tr"), name = el("span");
        name.appendChild(link("TRD " + x.trade, x.trade));
        if (x.product) name.appendChild(document.createTextNode(" " + x.product));
        cell(tr, name); cell(tr, x.book || "");
        cell(tr, tone(el("span", "", money(x.change, true)), x.change), "n");
        body.appendChild(tr);
      });
    }).catch(function (e) { tile("pnl", "—", e.message); tile("cpty", "—", ""); said($("[data-tl-movers]"), e, 3); });
    get("/api/home/trading/risk" + FOR).then(function (r) {
      if (!r.available) throw new Error(r.reason);
      tile("var", money(r["var"]), r.limit ? (r.state === "breach" ? "above" : "within") + " the desk limit (" + pct(r.limitUse) + " of " + money(r.limit) + ")"
        : "no desk limit set", STATE_TILE[r.state]);
      if (r.intraday) $("[data-tl-intraday]").textContent = "· VaR intraday as of " + String(r.intraday.lastAt || "").slice(11, 16);
      var br = r.breaches || [], nr = r.nearLimit || [];
      tile("breaches", String(br.length), br.length ? br.map(function (b) { return b.book + " at " + pct(b.limitUse); }).join(" · ") : "no book above its limit",
        br.length ? "bad" : "good");
      tile("near", String(nr.length), (nr.length ? nr.map(function (b) { return b.book + " " + pct(b.limitUse); }).join(" · ") : "no book") +
        " at " + pct(r.near) + " or more", nr.length ? "warn" : null);
    }).catch(function (e) { tile("var", "—", e.message); tile("breaches", "—", ""); tile("near", "—", ""); });
    get("/api/home/trading/limits" + FOR).then(heat).catch(function (e) { said($("[data-tl-heat]"), e); });
    get("/api/home/trading/traders" + FOR).then(function (t) {
      var body = $("[data-tl-traders]");
      body.textContent = "";
      $("[data-tl-traders-note]").textContent = [t.note, t.varNote ? "VaR: " + t.varNote : null, t.hasPrevious === false ? "no valuations the day before: no P&L" : null]
        .filter(Boolean).join(" · ") || "VaR: each trader's component VaR in the desk (they add up to the desk's VaR)";
      if (!t.available) { said(body, t.reason || "No trader on this desk", 4); return; }
      t.rows.forEach(function (r) {
        var tr = el("tr");
        cell(tr, link("TRDR " + r.trader, r.name || r.trader, ""));
        cell(tr, r.pnl === null || r.pnl === undefined ? "—" : tone(el("span", "", money(r.pnl, true)), r.pnl), "n");
        cell(tr, r["var"] === null || r["var"] === undefined ? "—" : money(r["var"]), "n");
        cell(tr, r.trades !== null && r.trades !== undefined ? String(r.trades) : r.recordTrades !== null && r.recordTrades !== undefined ? r.recordTrades + "*" : "—", "n");
        body.appendChild(tr);
      });
    }).catch(function (e) { said($("[data-tl-traders]"), e, 4); });
    get("/api/home/trading/pnl-var" + FOR).then(pnlVar).catch(function (e) { pnlVar({ available: false, reason: e.message }); });
  }

  function heat(h) {
    var box = $("[data-tl-heat]");
    box.textContent = "";
    if (!h.available) { said(box, h.reason); $("[data-tl-heat-note]").textContent = ""; return; }
    box.style.setProperty("--tl-days", String(h.dates.length));
    box.appendChild(el("span", "h"));
    h.dates.forEach(function (d) { var x = el("span", "h", d.slice(5)); x.setAttribute("role", "columnheader"); box.appendChild(x); });
    h.books.forEach(function (b) {
      var name = el("span", "b"); name.appendChild(link("VAR BOOK " + b.book, b.book, "")); box.appendChild(name);
      b.cells.forEach(function (c) {
        var x = el("span", "c " + c.state, c.limitUse === null ? "—" : pct(c.limitUse));
        x.setAttribute("data-tl-cell", b.book + "@" + c.date);
        x.title = b.book + " " + c.date + ": " + (c.limitUse === null ? (c.why || "no limit") : "VaR " + money(c["var"]) + " of limit " + money(c.limit));
        box.appendChild(x);
      });
    });
    $("[data-tl-heat-note]").textContent = "Each cell says its percentage; the colour only repeats it (amber from " + pct(h.near) + ", red above 100%)." +
      ((h.missingDates || []).length ? " No official record on " + h.missingDates.join(", ") + "." : "");
  }

  function pnlVar(p) {
    var card = $("[data-tl-pnlvar-card]"), box = $("[data-tl-pnlvar]"), note = $("[data-tl-pnlvar-note]");
    if (!p.available) {
      box.textContent = "";
      box.appendChild(el("p", "tl-muted", "Left out: " + p.reason));
      card.setAttribute("data-tl-left-out", "1");
      note.textContent = "";
      return;
    }
    var rows = p.rows, W = 600, H = 140, mid = 70;
    var big = Math.max.apply(null, rows.map(function (r) { return Math.max(Math.abs(r.pnl), Math.abs(r["var"])); })) || 1;
    var y = function (v) { return mid - (v / big) * (mid - 8); }, step = W / rows.length, bw = Math.max(2, step * 0.6);
    var svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Daily desk P&L bars against the VaR line, " + rows.length + " days, " + p.exceptions + " exceptions");
    function add(tag, attrs, cls) { var n = document.createElementNS(SVG, tag); Object.keys(attrs).forEach(function (k) { n.setAttribute(k, attrs[k]); }); if (cls) n.setAttribute("class", cls); svg.appendChild(n); return n; }
    add("line", { x1: 0, y1: mid, x2: W, y2: mid }, "axis");
    rows.forEach(function (r, i) {
      var x = i * step + (step - bw) / 2, top = Math.min(y(r.pnl), mid), h = Math.max(1, Math.abs(y(r.pnl) - mid));
      add("rect", { x: x.toFixed(1), y: top.toFixed(1), width: bw.toFixed(1), height: h.toFixed(1) }, r.pnl >= 0 ? "gain" : "loss");
      if (r.exception) add("circle", { cx: (x + bw / 2).toFixed(1), cy: y(r.pnl).toFixed(1), r: 5 }, "exc");
    });
    add("polyline", { points: rows.map(function (r, i) { return (i * step + step / 2).toFixed(1) + "," + y(-r["var"]).toFixed(1); }).join(" ") }, "varline");
    box.textContent = "";
    box.appendChild(svg);
    note.textContent = p.exceptions + " exception" + (p.exceptions === 1 ? "" : "s") + " in " + p.days + " days (" + rows[0].date + " to " +
      rows[rows.length - 1].date + "): a loss beyond the green line, −VaR. Each day's hypothetical P&L against the VaR before it, from the official records.";
  }

  // ---- start ------------------------------------------------------------------------------------------------------------------------
  get("/api/home/trading/me" + FOR).then(function (m) {
    var s = who(m);
    if (landing === "explorer") return;
    if (!s.kind) {
      $$("[data-tl-v]").forEach(function (v) { v.textContent = "—"; });
      $$("[data-tl-s]").forEach(function (v) { v.textContent = "pick a trader or a desk to view as"; });
      $$("tbody").forEach(function (b) { said(b, "Pick a trader or a desk to view as", 5); });
      return;
    }
    if (landing === "desk") desk(m); else trader(m);
  }).catch(function (e) { $("[data-tl-who]").textContent = "Not available: " + e.message; });
})();
