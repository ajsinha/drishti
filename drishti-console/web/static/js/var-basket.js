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
/* "Construct your basket" (docs/guides/VAR_GUIDE.md section 3.5): the VaR of any mix of portfolios, trades and counterparties.

   The tray lives on the VaR explorer (2.9.4: the top bar has no Basket button): a strip at the top, always shown on an ad-hoc scope or
   a basket, opened by "Construct your basket" or Alt+B (Alt+B does nothing on pages without the basket). Its contents stay in this
   browser, so a basket built earlier is there when the explorer opens. Typing shows hints from the
   engine (/api/var/hints: portfolios, saved baskets, desks, books, legal entities, counterparties, positions, trades, each with its
   type, id, name, trade count and standalone VaR; arrow keys and Enter pick, a leading "-" takes away); "+ Rule" adds a WHERE:
   condition; each chip is + or −. As you pick, the engine computes the selection (a trade in two chips counts once): its VaR and ES,
   its trades, the sum of the parts' standalone VaRs and the diversification; a chip that matches nothing is flagged at once. "+ What-if
   trade" adds a copy of a booked trade, scaled by a factor (−1: its unwind), and shows the VaR and ES before and after (the Python VaR
   engine, /api/var/what-if; when it is off the tray says so). Save keeps it as a basket (private, or shared read-only) and opens
   VAR SCOPE BASKET:<owner>/<name>; opening a saved basket loads its parts back into the tray. The tray survives a reload (this browser). */
(function () {
  "use strict";
  var KEY = "drishti.varBasket";
  var TYPES = { PORT: "portfolio", BASKET: "basket", DESK: "desk", BOOK: "book", LE: "legal entity", DIV: "division", NODE: "node",
                CPTY: "counterparty", POS: "position", TRD: "trade", WHERE: "rule", BANK: "bank", SCOPE: "scope" };
  var T = { items: [], whatif: [], saved: null, name: "", share: "", dirty: false };
  var host = null, el = {}, attached = null, popup = null, timer = null, hintTimer = null, hi = 0, hintRows = [], seq = 0, liveSeq = 0, lastScope = "";

  try { var kept = JSON.parse(localStorage.getItem(KEY) || "null"); if (kept && Array.isArray(kept.items)) T = Object.assign(T, kept); } catch (e) { /* none kept */ }
  function persist() { try { localStorage.setItem(KEY, JSON.stringify(T)); } catch (e) { /* a private window: the tray lives until the page closes */ } }

  // ---------------------------------------------------------------------------------------------------------------- helpers
  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function btn(cls, text, title) { var b = h("button", "btn btn-sm vb-btn " + (cls || ""), text); b.type = "button"; if (title) b.title = title; return b; }
  function money(v) {
    if (v === null || v === undefined || isNaN(v)) return "—";
    var a = Math.abs(v), s = v < 0 ? "−" : "";
    if (a >= 1e9) return s + (a / 1e9).toFixed(2) + "bn";
    if (a >= 1e6) return s + (a / 1e6).toFixed(a >= 1e8 ? 0 : 1) + "m";
    if (a >= 1e3) return s + (a / 1e3).toFixed(a >= 1e5 ? 0 : 1) + "k";
    return s + Math.round(a);
  }
  /** The page's business date and reporting currency (the explorer keeps ccy in its link, 2.9.4): every answer of the tray in it. */
  function asOf() {
    var q = new URLSearchParams(location.search), p = q.get("asOf"), c = q.get("ccy");
    return (p ? "&asOf=" + encodeURIComponent(p) : "") + (c && /^[A-Za-z]{3}$/.test(c) ? "&ccy=" + encodeURIComponent(c.toUpperCase()) : "");
  }
  function get(path) {
    return fetch("/api/var/" + path + asOf(), { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(answer);
  }
  function post(path, body) {
    return fetch("/api/var/" + path + (asOf() ? "?" + asOf().slice(1) : ""), { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(body) }).then(answer);
  }
  function answer(r) {
    return r.json().catch(function () { return {}; }).then(function (b) {
      if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; throw e; }
      return b;
    });
  }

  /** The tray as scope text: the chips to add joined by " + ", then those to take away by " - " (BANK first when none adds). */
  function compose(items) {
    items = items || T.items;
    var plus = items.filter(function (i) { return i.op === "+"; }), minus = items.filter(function (i) { return i.op === "-"; });
    if (!items.length) return "";
    var text = (plus.length ? plus : [{ scope: "BANK" }]).map(function (i) { return wrap(i.scope); }).join(" + ");
    minus.forEach(function (i) { text += " - " + wrap(i.scope); });
    return text;
  }
  function wrap(s) { return /\s[+\-&]\s/.test(s) && !/^WHERE:/.test(s) ? "(" + s + ")" : s; }
  function wordOf(scope) { var m = /^([A-Z]+):/.exec(scope || ""); return m ? m[1] : scope === "BANK" ? "BANK" : "SCOPE"; }
  function idOf(scope) { return scope.indexOf(":") > 0 ? scope.slice(scope.indexOf(":") + 1) : scope; }
  function same(a, b) { return String(a || "").toUpperCase() === String(b || "").toUpperCase(); }
  function stateText() {
    if (T.saved && !T.dirty) return "saved basket · " + T.saved;
    return "unsaved basket" + (T.saved ? " (edited from " + T.saved + ")" : "");
  }

  // ---------------------------------------------------------------------------------------------------------------- the tray
  function build() {
    var root = h("section", "vb");
    root.setAttribute("aria-label", "Construct your basket");
    root.dataset.varBasket = "";
    var head = h("div", "vb-head");
    var title = h("h2", "vb-title", "Construct your basket");
    el.state = h("span", "vb-state");
    el.state.dataset.vbState = "";
    head.appendChild(title);
    head.appendChild(el.state);
    head.appendChild(h("span", "vb-sub", "VaR of any mix of portfolios, trades and counterparties"));
    el.scope = h("span", "vb-scope mono");
    el.scope.dataset.vbScope = "";
    head.appendChild(el.scope);
    el.close = btn("", "Close", "Close the tray (Alt+B opens it again)");
    el.close.dataset.vbClose = "";
    head.appendChild(el.close);
    root.appendChild(head);

    var row = h("div", "vb-row");
    var find = h("div", "vb-find");
    el.find = h("input", "vb-input mono");
    el.find.placeholder = "Add a portfolio, basket, desk, book, legal entity, counterparty, position or trade: type for hints (\"-\" first takes away)";
    el.find.setAttribute("aria-label", "Add to the basket");
    el.find.setAttribute("autocomplete", "off");
    el.find.setAttribute("role", "combobox");
    el.find.setAttribute("aria-expanded", "false");
    el.find.setAttribute("aria-controls", "vbHints");
    el.find.dataset.vbFind = "";
    el.hints = h("div", "vb-hints");
    el.hints.id = "vbHints";
    el.hints.setAttribute("role", "listbox");
    el.hints.hidden = true;
    el.hints.dataset.vbHints = "";
    find.appendChild(el.find);
    find.appendChild(el.hints);
    row.appendChild(find);
    el.ruleBtn = btn("", "+ Rule", "Add a condition over the positions' columns (WHERE:)");
    el.ruleBtn.dataset.vbRule = "";
    el.wifBtn = btn("", "+ What-if trade", "A pre-deal what-if: a copy of a booked trade, scaled, added to the basket");
    el.wifBtn.dataset.vbWhatifOpen = "";
    row.appendChild(el.ruleBtn);
    row.appendChild(el.wifBtn);
    root.appendChild(row);

    el.ruleRow = h("div", "vb-row");
    el.ruleRow.hidden = true;
    el.rule = h("input", "vb-input mono");
    el.rule.placeholder = "CURRENCY == 'EUR' and DIRECTION == 'SHORT'";
    el.rule.setAttribute("aria-label", "Rule over the positions' columns");
    el.rule.dataset.vbRuleText = "";
    var addRule = btn("", "Add rule");
    addRule.dataset.vbRuleAdd = "";
    el.ruleRow.appendChild(el.rule);
    el.ruleRow.appendChild(addRule);
    root.appendChild(el.ruleRow);

    el.chips = h("div", "vb-chips");
    el.chips.dataset.vbChips = "";
    root.appendChild(el.chips);
    el.live = h("div", "vb-live");
    el.live.dataset.vbLive = "";
    el.live.setAttribute("aria-live", "polite");
    root.appendChild(el.live);
    el.notes = h("ul", "vb-notes");
    el.notes.dataset.vbNotices = "";
    el.notes.setAttribute("role", "status");
    root.appendChild(el.notes);

    el.wif = h("div", "vb-whatif");
    el.wif.hidden = true;
    el.wif.dataset.vbWhatif = "";
    var wrow = h("div", "vb-row");
    wrow.appendChild(h("span", "vb-lbl", "Pre-deal what-if: a copy of"));
    el.wifTrade = h("input", "vb-input mono vb-narrow");
    el.wifTrade.placeholder = "a booked trade, MX-30000121";
    el.wifTrade.setAttribute("aria-label", "The booked trade to copy");
    el.wifTrade.dataset.vbWhatifTrade = "";
    el.wifFactor = h("input", "vb-input mono vb-tiny");
    el.wifFactor.value = "1";
    el.wifFactor.setAttribute("aria-label", "Factor (−1: its unwind)");
    el.wifFactor.title = "Factor: 1 the same trade again, −1 its unwind, 0.5 half";
    el.wifFactor.dataset.vbWhatifFactor = "";
    var wadd = btn("primary", "Add and run what-if");
    wadd.dataset.vbWhatifRun = "";
    wrow.appendChild(el.wifTrade);
    wrow.appendChild(h("span", "vb-lbl", "× factor"));
    wrow.appendChild(el.wifFactor);
    wrow.appendChild(wadd);
    el.wif.appendChild(wrow);
    el.wifOut = h("div", "vb-whatif-out");
    el.wifOut.dataset.vbWhatifOut = "";
    el.wif.appendChild(el.wifOut);
    root.appendChild(el.wif);

    var save = h("div", "vb-row vb-save");
    el.name = h("input", "vb-input vb-name");
    el.name.placeholder = "Name this basket";
    el.name.setAttribute("aria-label", "Basket name");
    el.name.dataset.vbName = "";
    el.share = h("input", "vb-input vb-share");
    el.share.placeholder = "Share read-only with (users or roles, optional)";
    el.share.setAttribute("aria-label", "Share read-only with");
    el.share.dataset.vbShare = "";
    el.save = btn("primary", "Save basket and open VaR");
    el.save.dataset.vbSave = "";
    el.open = btn("", "Open without saving");
    el.open.dataset.vbOpen = "";
    el.clear = btn("", "Clear");
    el.clear.dataset.vbClear = "";
    el.msg = h("span", "vb-msg");
    el.msg.dataset.vbMsg = "";
    el.msg.setAttribute("aria-live", "polite");
    [el.name, el.share, el.save, el.open, el.clear, el.msg].forEach(function (x) { save.appendChild(x); });
    root.appendChild(save);
    wire(root, addRule, wadd);
    return root;
  }

  function wire(root, addRule, wadd) {
    el.find.addEventListener("input", function () {
      clearTimeout(hintTimer);
      hintTimer = setTimeout(hints, 160);
    });
    el.find.addEventListener("keydown", function (ev) {
      if (ev.key === "Escape") { closeHints(); return; }
      if (!hintRows.length) {
        if (ev.key === "Enter" && el.find.value.trim()) { ev.preventDefault(); clearTimeout(hintTimer); hints(true); }
        return;
      }
      if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
        ev.preventDefault();
        hi = (hi + (ev.key === "ArrowDown" ? 1 : hintRows.length - 1)) % hintRows.length;
        markHint();
      } else if (ev.key === "Enter") {
        ev.preventDefault();
        pick(hintRows[hi]);
      }
    });
    el.find.addEventListener("blur", function () { setTimeout(closeHints, 200); });
    el.ruleBtn.addEventListener("click", function () { el.ruleRow.hidden = !el.ruleRow.hidden; if (!el.ruleRow.hidden) el.rule.focus(); });
    var ruleAdd = function () {
      var r = el.rule.value.trim();
      if (!r) return;
      add("WHERE:" + r, { type: "WHERE", id: r, name: "rule" });
      el.rule.value = "";
      el.ruleRow.hidden = true;
    };
    addRule.addEventListener("click", ruleAdd);
    el.rule.addEventListener("keydown", function (ev) { if (ev.key === "Enter") { ev.preventDefault(); ruleAdd(); } });
    el.wifBtn.addEventListener("click", function () { openWhatIf(); });
    wadd.addEventListener("click", function () {
      var id = el.wifTrade.value.trim().replace(/^TRD:/i, "");
      var f = Number(el.wifFactor.value);
      if (!id) { el.wifOut.textContent = "Name a booked trade to copy."; return; }
      if (isNaN(f) || f === 0) { el.wifOut.textContent = "The factor is a number other than 0 (−1: the trade's unwind)."; return; }
      T.whatif.push({ copyOf: id, factor: f });
      el.wifTrade.value = "";
      changed(false);
      runWhatIf();
    });
    el.chips.addEventListener("change", function (ev) {
      var k = ev.target.dataset.vbOp;
      if (k !== undefined) { T.items[+k].op = ev.target.value; changed(true); }
    });
    el.chips.addEventListener("click", function (ev) {
      var b = ev.target.closest("[data-vb-del],[data-vb-wdel]");
      if (!b) return;
      if (b.dataset.vbDel !== undefined) T.items.splice(+b.dataset.vbDel, 1);
      else T.whatif.splice(+b.dataset.vbWdel, 1);
      changed(b.dataset.vbDel !== undefined);
      if (T.whatif.length) runWhatIf(); else el.wifOut.textContent = "";
    });
    el.name.addEventListener("input", function () { T.name = el.name.value; persist(); paint(); });
    el.share.addEventListener("input", function () { T.share = el.share.value; persist(); });
    el.clear.addEventListener("click", function () {
      T.items = []; T.whatif = []; T.saved = null; T.name = ""; T.share = ""; T.dirty = false;
      el.name.value = ""; el.share.value = ""; el.wifOut.textContent = ""; el.msg.textContent = "";
      changed(false);
    });
    el.open.addEventListener("click", function () { go(compose()); });
    el.save.addEventListener("click", save);
    el.close.addEventListener("click", function () { hide(); });
    root.addEventListener("keydown", function (ev) { if (ev.key === "Escape" && popup) hide(); });
  }

  // ---------------------------------------------------------------------------------------------------------------- hints
  function hints(addTyped) {
    var raw = el.find.value, q = raw.trim(), minus = /^-/.test(q);
    if (minus) q = q.slice(1).trim();
    if (!q) { closeHints(); return; }
    var my = ++seq;
    get("hints?q=" + encodeURIComponent(q) + "&limit=8").then(function (list) {
      if (my !== seq) return;
      hintRows = list.map(function (x) { x.minus = minus; return x; });
      el.hints.dataset.q = raw;
      hi = 0;
      el.hints.textContent = "";
      if (!hintRows.length) {
        el.hints.appendChild(h("div", "vb-hint none", "Nothing you may see matches \"" + q + "\". Type a word and an id to add it as written (TRD:MX-30000121)."));
        el.hints.hidden = false;
        return;
      }
      hintRows.forEach(function (x, i) {
        var r = h("div", "vb-hint" + (x.trades ? "" : " flagged"));
        r.setAttribute("role", "option");
        r.dataset.vbHint = x.scope;
        r.appendChild(h("span", "k", (minus ? "− " : "") + x.type));
        r.appendChild(h("span", "t mono", x.id));
        r.appendChild(h("span", "n", x.name));
        r.appendChild(h("span", "d mono", x.trades ? x.trades.toLocaleString("en-US") + " trade" + (x.trades === 1 ? "" : "s") + " · VaR " + money(x["var"])
          : (x.notice || "matches nothing")));
        r.addEventListener("mousedown", function (ev) { ev.preventDefault(); pick(hintRows[i]); });
        el.hints.appendChild(r);
      });
      el.hints.hidden = false;
      el.find.setAttribute("aria-expanded", "true");
      markHint();
      if (addTyped === true && hintRows.length === 1) pick(hintRows[0]);
    }).catch(function (e) {
      el.hints.textContent = "";
      el.hints.appendChild(h("div", "vb-hint none", e.status === 403 ? "Your role may not name trades one by one: " + e.message : e.message));
      el.hints.hidden = false;
    });
  }
  function markHint() {
    el.hints.querySelectorAll(".vb-hint").forEach(function (r, i) { r.classList.toggle("on", i === hi); r.setAttribute("aria-selected", i === hi ? "true" : "false"); });
  }
  function closeHints() { el.hints.hidden = true; el.hints.textContent = ""; delete el.hints.dataset.q; hintRows = []; el.find.setAttribute("aria-expanded", "false"); }
  function pick(x) {
    if (!x) return;
    add(x.scope, { type: x.type, id: x.id, name: x.name, trades: x.trades, "var": x["var"], notice: x.notice, op: x.minus ? "-" : "+" });
    el.find.value = "";
    closeHints();
    el.find.focus();
  }

  // ---------------------------------------------------------------------------------------------------------------- changes
  /** Adds a part (a row's "+ basket", a hint): once; it shows the tray. */
  function add(scope, meta) {
    meta = meta || {};
    if (!scope) return;
    var have = T.items.filter(function (i) { return same(i.scope, scope); })[0];
    if (have) { have.op = meta.op || "+"; }
    else {
      T.items.push({ op: meta.op || "+", scope: scope, type: meta.type || wordOf(scope), id: meta.id || idOf(scope), name: meta.name || "",
                     trades: meta.trades === undefined ? null : meta.trades, "var": meta["var"] === undefined ? null : meta["var"], notice: meta.notice || "" });
    }
    changed(true);
    show();
    if (el.msg) el.msg.textContent = "Added " + scope + ".";
  }

  function changed(dirty) {
    if (dirty && T.saved) T.dirty = true;
    persist();
    paint();
    clearTimeout(timer);
    timer = setTimeout(live, 300);
    var before = lastScope;
    lastScope = compose();
    if (attached && attached.onChange) attached.onChange(lastScope, before);
  }

  function paint() {
    countBadge();
    if (!el.chips) return;
    el.state.textContent = stateText();
    el.state.className = "vb-state" + (T.saved && !T.dirty ? " saved" : "");
    el.scope.textContent = compose() || "—";
    el.chips.textContent = "";
    if (!T.items.length && !T.whatif.length) el.chips.appendChild(h("span", "vb-note", "Nothing in the basket yet: type above, or use \"+ basket\" on any row of the explorer."));
    T.items.forEach(function (i, k) {
      var flagged = i.trades === 0;
      var c = h("span", "vb-chip" + (i.op === "-" ? " minus" : "") + (flagged ? " flagged" : ""));
      c.dataset.vbChip = i.scope;
      if (flagged) { c.title = i.notice || "matches nothing on the date"; c.dataset.flagged = "1"; }
      var s = h("select", "vb-op");
      s.setAttribute("aria-label", "Add or take away " + i.scope);
      s.dataset.vbOp = String(k);
      [["+", "+"], ["-", "−"]].forEach(function (o) { var e = h("option", null, o[1]); e.value = o[0]; if (o[0] === i.op) e.selected = true; s.appendChild(e); });
      c.appendChild(s);
      c.appendChild(h("span", "k", TYPES[i.type] || i.type));
      c.appendChild(h("span", "mono", i.id));
      if (i.trades !== null && i.trades !== undefined) c.appendChild(h("span", "t", flagged ? "matches nothing" : i.trades.toLocaleString("en-US") + " tr"));
      var x = h("button", "vb-x", "×");
      x.type = "button";
      x.dataset.vbDel = String(k);
      x.setAttribute("aria-label", "Remove " + i.scope);
      c.appendChild(x);
      el.chips.appendChild(c);
    });
    T.whatif.forEach(function (w, k) {
      var c = h("span", "vb-chip whatif");
      c.dataset.vbWhatifChip = w.copyOf;
      c.appendChild(h("span", "k", "what-if"));
      c.appendChild(h("span", "mono", (w.factor === 1 ? "" : w.factor + " × ") + w.copyOf));
      var x = h("button", "vb-x", "×");
      x.type = "button";
      x.dataset.vbWdel = String(k);
      x.setAttribute("aria-label", "Remove the what-if " + w.copyOf);
      c.appendChild(x);
      el.chips.appendChild(c);
    });
    if (document.activeElement !== el.name) el.name.value = T.name || "";
    if (document.activeElement !== el.share) el.share.value = T.share || "";
    var any = T.items.length > 0;
    el.open.disabled = !any;
    el.clear.disabled = !any && !T.whatif.length;
    el.save.disabled = !any || !(T.name || "").trim();
    el.save.title = !any ? "Pick at least one part" : !(T.name || "").trim() ? "Name the basket first" : "Save as " + slug(T.name) + " and open it in the VaR explorer";
    if (!any) { el.live.textContent = ""; el.notes.textContent = ""; }
  }
  function slug(n) { return String(n || "").toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64); }
  function countBadge() {
    document.querySelectorAll("[data-basket-count]").forEach(function (c) { c.textContent = String(T.items.length); c.hidden = !T.items.length; });
  }

  /** The selection computed by the engine: its headline and its parts (each chip alone; a trade in two chips once). */
  function live() {
    var scope = compose();
    if (!scope || !el.live) return;
    var my = ++liveSeq, t0 = performance.now();
    el.live.textContent = "computing…";
    el.live.className = "vb-live computing";
    var q = encodeURIComponent(scope);
    Promise.all([get("scope?scope=" + q), get("scope/contributions?scope=" + q + "&by=part")]).then(function (x) {
      if (my !== liveSeq) return;
      var d = x[0], c = x[1], ms = Math.round(performance.now() - t0);
      (c.rows || []).forEach(function (r) {
        T.items.forEach(function (i) { if (i.op === "+" && same(i.scope, r.key)) { i.trades = r.trades; i["var"] = r.standaloneVar; } });
      });
      var notices = (d.provenance && d.provenance.notices) || [];
      T.items.forEach(function (i) {
        var n = notices.filter(function (t) { return t.toUpperCase().indexOf(i.scope.toUpperCase()) === 0 || t.toUpperCase().indexOf(i.id.toUpperCase() + " ") === 0; })[0];
        if (n && i.op === "-") { i.trades = 0; i.notice = n; }
        if (n && i.trades === 0) i.notice = n;
      });
      persist();
      paint();
      el.live.className = "vb-live";
      el.live.textContent = "";
      el.live.dataset.var = String(d["var"]);
      el.live.dataset.trades = String(d.trades);
      el.live.dataset.scope = scope;
      if (d.empty) {
        el.live.appendChild(h("b", "vb-empty", "No trades"));
        el.live.appendChild(h("span", null, " · this selection holds no live trade with a P&L vector on " + d.businessDate + ": no VaR to show"));
      } else {
        el.live.appendChild(h("span", null, "VaR " + Math.round(d.confidence * 1000) / 10 + "% 1D of the selection "));
        el.live.appendChild(h("b", "mono", money(d["var"]) + (d.currency && d.currency !== "USD" ? " " + d.currency : "")));
        el.live.dataset.ccy = d.currency || "USD";
        var parts = c.rows || [];
        var rest = " · ES " + money(d.es) + " · " + d.trades.toLocaleString("en-US") + " trade" + (d.trades === 1 ? "" : "s");
        if (parts.length) rest += " · sum of the parts " + money(c.sumStandalone);
        el.live.appendChild(h("span", "mono", rest));
        if (parts.length > 1) {
          el.live.appendChild(h("span", null, " · diversification "));
          el.live.appendChild(h("span", "mono vb-div", "−" + money(c.diversification)));
        }
        el.live.appendChild(h("span", "vb-ms", " · computed in " + ms + " ms"));
      }
      el.notes.textContent = "";
      notices.filter(function (n) { return !/^no trades/.test(n); }).forEach(function (n) { el.notes.appendChild(h("li", null, n)); });
      host && host.dispatchEvent(new CustomEvent("vb:live", { bubbles: true, detail: d }));
    }).catch(function (e) {
      if (my !== liveSeq) return;
      el.live.className = "vb-live";
      el.live.textContent = (e.status === 403 ? "Not for your role: " : "Could not compute: ") + e.message;
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- what-if
  function openWhatIf() {
    show();
    el.wif.hidden = false;
    el.wifTrade.focus();
  }
  function runWhatIf() {
    var scope = compose();
    el.wif.hidden = false;
    if (!T.whatif.length) return;
    if (!scope) { el.wifOut.textContent = "Pick the basket first: the what-if adds its trade to it."; return; }
    el.wifOut.textContent = "computing the what-if…";
    delete el.wifOut.dataset.done;
    post("what-if", { scope: scope, items: T.whatif.map(function (w) { return { copyOf: w.copyOf, factor: w.factor }; }) }).then(function (w) {
      el.wifOut.textContent = "";
      el.wifOut.dataset.done = "ready";
      var b = w.before || {}, a = w.after || {}, ch = w.change || {};
      var t = h("table", "vb-table");
      var hr = h("tr");
      ["", "Before", "After", "Incremental"].forEach(function (x, i) { hr.appendChild(h("th", i ? "n" : null, x)); });
      t.appendChild(h("thead")).appendChild(hr);
      var tb = h("tbody");
      var cl = function (x) { return x ? Math.round(x * 1000) / 10 + "%" : ""; };   // the answer's confidences, never fixed here
      [[("VaR " + cl(w.confidence)).trim(), b["var"], a["var"], ch["var"]], [("ES " + cl(w.esConfidence)).trim(), b.es, a.es, ch.es]].forEach(function (r) {
        var tr = h("tr");
        tr.appendChild(h("td", null, r[0]));
        tr.appendChild(h("td", "n mono", money(r[1])));
        tr.appendChild(h("td", "n mono", money(r[2])));
        tr.appendChild(h("td", "n mono " + (r[3] > 0 ? "up" : "down"), (r[3] > 0 ? "+" : "") + money(r[3])));
        tb.appendChild(tr);
      });
      (w.items || []).forEach(function (it) {
        var tr = h("tr", "vb-sub");
        tr.appendChild(h("td", null, it.label + " alone " + money(it.standaloneVar)));
        tr.appendChild(h("td", "n mono", ""));
        tr.appendChild(h("td", "n mono", "component " + money(it.componentVar)));
        tr.appendChild(h("td", "n mono", (it.incrementalVar > 0 ? "+" : "") + money(it.incrementalVar)));
        tb.appendChild(tr);
      });
      t.appendChild(tb);
      el.wifOut.appendChild(t);
      el.wifOut.appendChild(h("p", "vb-note", "Pre-deal what-if by the Python VaR engine: " + (w.note || "")));
    }).catch(function (e) {
      el.wifOut.textContent = "";
      el.wifOut.dataset.done = "failed";
      var p = h("p", "vb-err", e.code === "DRS-5205" ? "The what-if cannot run here: " + e.message + " (" + e.code + ")." : "What-if failed: " + e.message);
      p.setAttribute("role", "alert");
      el.wifOut.appendChild(p);
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- save, open
  function save() {
    var scope = compose(), name = (T.name || "").trim();
    if (!scope || !name) { el.msg.textContent = "Name the basket and pick at least one part."; return; }
    el.msg.textContent = "saving…";
    var share = String(T.share || "").split(/[\s,]+/).filter(Boolean);
    post("baskets", { name: name, title: name, scope: scope, sharedWith: share }).then(function (b) {
      T.saved = b.id; T.dirty = false; T.name = b.name || name;
      persist();
      paint();
      el.msg.textContent = "Saved as " + b.id + " (version " + b.version + (share.length ? ", shared read-only with " + share.join(", ") : ", private") + ").";
      document.dispatchEvent(new CustomEvent("vb:saved", { detail: b }));
      go("BASKET:" + b.id);
    }).catch(function (e) { el.msg.textContent = e.message; });
  }
  function go(scope) {
    if (!scope) return;
    lastScope = compose();
    if (attached && attached.onOpen) { attached.onOpen(scope); return; }
    location.href = "/var?scope=" + encodeURIComponent(scope);
  }

  // ---------------------------------------------------------------------------------------------------------------- where it shows
  function ensure() {
    if (!host) { host = build(); paint(); }
    return host;
  }
  function show() {
    ensure();
    if (attached) {
      if (host.parentNode !== attached.strip) attached.strip.appendChild(host);
      attached.strip.hidden = false;
      host.classList.remove("popup");
      return;
    }
    if (!popup) {
      popup = h("div", "vb-shade");
      popup.dataset.vbPopup = "";
      popup.addEventListener("mousedown", function (ev) { if (ev.target === popup) hide(); });
      document.body.appendChild(popup);
    }
    host.classList.add("popup");
    host.setAttribute("role", "dialog");
    host.setAttribute("aria-modal", "true");
    popup.appendChild(host);
    popup.hidden = false;
    if (T.items.length) { clearTimeout(timer); timer = setTimeout(live, 50); }
  }
  function hide() {
    if (attached) { attached.strip.hidden = true; attached.closed = true; return; }
    if (popup) popup.hidden = true;
  }
  function toggle() {
    var open = attached ? !attached.strip.hidden : popup && !popup.hidden;
    if (open && !attached) { hide(); return; }
    if (attached) attached.closed = false;
    show();
    el.find.focus();
  }

  /** The explorer's strip: {strip: element, onOpen(scope), onChange(scope, before)}. */
  function attach(opts) {
    attached = opts;
    ensure();
    opts.strip.appendChild(host);
    lastScope = compose();
    if (T.items.length) { clearTimeout(timer); timer = setTimeout(live, 50); }
  }

  /** The explorer opened a basket or an ad-hoc scope: the tray shows its parts (a basket's, for editing). */
  function mirror(d) {
    if (!d || (d.kind !== "basket" && d.kind !== "adhoc")) return;
    var terms = d.terms || [];
    var same0 = d.kind === "adhoc" && compose() && (same(compose(), d.scope) || same(compose(), d.canonical));
    if (d.kind === "basket" && d.basket && T.saved === d.basket.id && !T.dirty && T.items.length) same0 = true;
    if (!same0 && terms.length) {
      T.items = terms.map(function (t) { return { op: t.op, scope: t.scope, type: t.word, id: idOf(t.scope), name: "", trades: null, "var": null, notice: "" }; });
      T.saved = d.kind === "basket" && d.basket ? d.basket.id : null;
      T.name = d.kind === "basket" && d.basket ? d.basket.name : T.name;
      T.share = d.kind === "basket" && d.basket ? (d.basket.sharedWith || []).join(", ") : T.share;
      T.dirty = false;
      persist();
      lastScope = compose();
      paint();
      clearTimeout(timer);
      timer = setTimeout(live, 50);
    }
    if (attached && !attached.closed) show();
  }

  // ---------------------------------------------------------------------------------------------------------------- Alt+B
  function start() {
    countBadge();
    document.addEventListener("keydown", function (ev) {
      if (ev.altKey && !ev.ctrlKey && !ev.metaKey && (ev.key === "b" || ev.key === "B" || ev.code === "KeyB")) {
        // only where the page offers the basket (the explorer's "Construct your basket"); the landing page's Alt+B is its Back to
        if (!document.querySelector("[data-vx-basket-open]") || document.querySelector("[accesskey=b]")) return;
        ev.preventDefault();
        toggle();
      }
    });
    // the explorer moved to another reporting currency: the selection's figures follow it
    document.addEventListener("var:currency", function () { if (T.items.length && el.live) live(); });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();

  window.VarBasket = { add: add, attach: attach, mirror: mirror, show: show, hide: hide, toggle: toggle, compose: compose,
                       openWhatIf: openWhatIf, state: T, live: function () { live(); } };
})();
