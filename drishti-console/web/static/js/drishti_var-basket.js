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
/* "Construct your basket" (docs/guides/VAR_GUIDE.md section 3.5): the VaR of any mix of trades, positions, portfolios, books, desks, limit
   letters and counterparties, built in one sheet like Excel (drishti_var-basket-sheet.js draws the grid).

   The sheet has a column per kind of part and the Op column (+ adds the row, − takes it away; a leading "-" on an id takes it away too).
   It fills by typing in a cell, by the type-ahead above it (a hint lands in its kind's column; Add (+) or Take away (−) says which), by
   "+ basket" on any row of the explorer, or by pasting whole columns from Excel. "Start from the whole bank" (a checkbox, never a row)
   makes the basket the bank and the rows refine it. The sheet goes to the server as lists (POST /api/var/basket/sheet), which checks
   every cell (found, or not found with the reason in words; a minus that takes nothing away; a + under the whole bank) and computes the
   basket's VaR, ES, sum of the parts, diversification and trades: the console renders the answer and computes nothing. Under the figures
   the pre-deal what-if (POST /api/var/what-if, the server's VaR engine: no compute workers needed), then the name, sharing, Save and
   Open without saving. A saved basket opens back into the sheet column by column (Edit; an administrator's edit is saved under its owner); a portfolio is the bank's, so it opens as a copy (Edit
   a copy). Download as .xlsx gives the sheet back to Excel. The sheet lives on the VaR explorer, opened by "Construct your basket" or
   Alt+B, and survives a reload (this browser). */
(function () {
  "use strict";
  var KEY = (window.Drishti.key ? window.Drishti.key("drishti.varBasket2") : "drishti.varBasket2");   // per person: never the last one's
  var DEFAULT_COLS = [{ key: "TRD", title: "Trade" }, { key: "POS", title: "Position" }, { key: "PORT", title: "Portfolio" },
                      { key: "BOOK", title: "Book" }, { key: "DESK", title: "Desk" }, { key: "CPTY", title: "Counterparty" }];
  var OPEN_MAX = 4000;          // the longest scope text a link carries ("Open without saving"); a longer basket is saved first
  var T = { rows: [], wholeBank: false, mode: "+", whatif: [], saved: null, copyOf: null, name: "", share: "", dirty: false, columns: DEFAULT_COLS };
  var host = null, el = {}, grid = null, attached = null, popup = null, timer = null, hintTimer = null, hi = 0, hintRows = [], seq = 0;
  var checkSeq = 0, answer = null, checked = {}, flags = {}, lastScope = "";

  try {
    var kept = JSON.parse(localStorage.getItem(KEY) || "null");
    if (kept && Array.isArray(kept.rows)) T = Object.assign(T, kept);
    localStorage.removeItem("drishti.varBasket2"); localStorage.removeItem("drishti.varBasket");   // kept before baskets were per person: whose?
  } catch (e) { /* none kept */ }
  function persist() { try { localStorage.setItem(KEY, JSON.stringify(T)); } catch (e) { /* a private window, or too large: kept until the page closes */ } }


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
    return (v < 0 ? "−" : "") + Math.round(Math.abs(v)).toLocaleString("en-US");
  }
  function short(v) {
    if (v === null || v === undefined || isNaN(v)) return "—";
    var a = Math.abs(v), s = v < 0 ? "−" : "";
    if (a >= 1e9) return s + (a / 1e9).toFixed(2) + "bn";
    if (a >= 1e6) return s + (a / 1e6).toFixed(a >= 1e8 ? 0 : 1) + "m";
    if (a >= 1e3) return s + (a / 1e3).toFixed(a >= 1e5 ? 0 : 1) + "k";
    return s + Math.round(a);
  }
  /** The page's business date and reporting currency (the explorer keeps ccy in its link): every answer of the sheet in it. */
  function asOf() {
    var q = new URLSearchParams(location.search), p = q.get("asOf"), c = q.get("ccy");
    return (p ? "&asOf=" + encodeURIComponent(p) : "") + (c && /^[A-Za-z]{3}$/.test(c) ? "&ccy=" + encodeURIComponent(c.toUpperCase()) : "");
  }
  function get(path) {
    return fetch("/api/var/" + path + asOf(), { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(reply);
  }
  function post(path, body) {
    return fetch("/api/var/" + path + (asOf() ? "?" + asOf().slice(1) : ""), { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(body) }).then(reply);
  }
  function reply(r) {
    return r.json().catch(function () { return {}; }).then(function (b) {
      if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; throw e; }
      return b;
    });
  }
  function wordOf(scope) { var m = /^([A-Za-z]+):/.exec(scope || ""); return m ? m[1].toUpperCase() : scope === "BANK" ? "BANK" : "SCOPE"; }
  function idOf(scope) { return scope.indexOf(":") > 0 ? scope.slice(scope.indexOf(":") + 1) : scope; }
  function keys() { return T.columns.map(function (c) { return c.key; }); }
  function filled(row) { return keys().some(function (k) { return row[k]; }); }

  /** The sheet as the server takes it: whole bank, the Op column and each filled column, as lists cut to the filled rows. */
  function payload() {
    var n = T.rows.length;
    while (n > 0 && !filled(T.rows[n - 1])) n--;
    var out = { wholeBank: !!T.wholeBank, op: [] };
    for (var r = 0; r < n; r++) out.op.push(T.rows[r].op === "-" ? "-" : "+");
    keys().forEach(function (k) {
      var any = false, list = [];
      for (var r2 = 0; r2 < n; r2++) { var v = T.rows[r2][k] || ""; list.push(v); any = any || !!v; }
      if (any) out[k] = list;
    });
    return out;
  }
  function signature() { return JSON.stringify(payload()); }
  function parts() { var n = 0; T.rows.forEach(function (row) { keys().forEach(function (k) { if (row[k]) n++; }); }); return n; }
  function empty() { return !T.wholeBank && parts() === 0; }
  /** The basket's scope text as the server composed it (its last answer for what the sheet holds now), else "". */
  function compose() { return answer && answer.sig === signature() ? answer.scope || "" : ""; }
  function stateText() {
    var bits = [T.saved && !T.dirty ? "saved basket · " + T.saved.id : T.copyOf ? "a copy of the portfolio " + T.copyOf + " (unsaved)"
      : "unsaved basket" + (T.saved ? " (edited from " + T.saved.id + ", version " + T.saved.version + ")" : "")];
    bits.push(parts().toLocaleString("en-US") + " part" + (parts() === 1 ? "" : "s"));
    if (answer && answer.businessDate) bits.push("date " + answer.businessDate);
    return bits.join(" · ");
  }

  // ---------------------------------------------------------------------------------------------------------------- the sheet's screen
  function build() {
    var root = h("section", "vb");
    root.setAttribute("aria-label", "Construct your basket");
    root.dataset.varBasket = "";
    var head = h("div", "vb-head");
    head.appendChild(h("h2", "vb-title", "◫ Construct your basket"));
    el.state = h("span", "vb-state");
    el.state.dataset.vbState = "";
    head.appendChild(el.state);
    var right = h("span", "vb-head-r");
    el.mode = h("span", "vb-seg");
    el.mode.setAttribute("role", "radiogroup");
    el.mode.setAttribute("aria-label", "What a hint does: add or take away");
    [["+", "Add (+)"], ["-", "Take away (−)"]].forEach(function (o) {
      var b = h("button", "vb-segb", o[1]);
      b.type = "button";
      b.dataset.vbMode = o[0];
      b.setAttribute("role", "radio");
      b.addEventListener("click", function () { T.mode = o[0]; persist(); paint(); });
      el.mode.appendChild(b);
    });
    right.appendChild(el.mode);
    el.pick = h("select", "vb-input vb-pick");
    el.pick.setAttribute("aria-label", "Open a saved basket");
    el.pick.dataset.vbPick = "";
    right.appendChild(el.pick);
    el.close = btn("", "✕", "Close the sheet (Alt+B opens it again)");
    el.close.dataset.vbClose = "";
    right.appendChild(el.close);
    head.appendChild(right);
    root.appendChild(head);

    var row = h("div", "vb-row");
    var find = h("div", "vb-find");
    el.find = h("input", "vb-input mono");
    el.find.placeholder = "Find and add: a trade, position, portfolio, book, desk, limit letter, counterparty… (\"-\" first takes away)";
    el.find.setAttribute("aria-label", "Find and add to the basket");
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
    row.appendChild(h("span", "vb-note vb-how", "a hint lands in its kind's column; + basket on any explorer row does the same; or paste whole columns from Excel"));
    el.clear = btn("", "Clear");
    el.clear.dataset.vbClear = "";
    el.xlsx = btn("", "Download as .xlsx", "The sheet as a workbook: paste its columns back to open it again");
    el.xlsx.dataset.vbXlsx = "";
    row.appendChild(el.clear);
    row.appendChild(el.xlsx);
    root.appendChild(row);

    var bank = h("label", "vb-bank");
    el.bank = h("input");
    el.bank.type = "checkbox";
    el.bank.dataset.vbBank = "";
    bank.appendChild(el.bank);
    bank.appendChild(h("b", null, " Start from the whole bank"));
    bank.appendChild(h("span", "vb-note", " every trade you may see; the rows marked − take away from it (BANK − DESK:COMM − TRD:XYZ)"));
    root.appendChild(bank);

    grid = window.Drishti.VarBasketGrid.create({
      rows: function () { return T.rows; },
      columns: function () { return T.columns; },
      mode: function () { return T.mode; },
      changed: function (what, info) {
        changed(true);
        if (what === "paste") el.msg.textContent = "Pasted " + info.rows.toLocaleString("en-US") + " row" + (info.rows === 1 ? "" : "s")
          + (info.cut ? " (a sheet holds " + window.Drishti.VarBasketGrid.MAX_ROWS.toLocaleString("en-US") + " rows: the rest left out)" : "") + ".";
      },
      flag: function (k, r) { return flags[k + ":" + r] || null; },
      checked: function (k, r) { return checked[k + ":" + r]; }
    });
    root.appendChild(grid.root);

    el.checks = h("div", "vb-checks");
    el.checks.dataset.vbChecks = "";
    el.checks.setAttribute("role", "status");
    root.appendChild(el.checks);
    el.live = h("div", "vb-kpis");
    el.live.dataset.vbLive = "";
    el.live.setAttribute("aria-live", "polite");
    root.appendChild(el.live);

    el.wif = h("div", "vb-card vb-whatif");
    el.wif.dataset.vbWhatif = "";
    var wt = h("h3", null, "What-if trades");
    wt.appendChild(h("span", null, "pre-deal: a booked trade copied and scaled; before → after"));
    el.wif.appendChild(wt);
    el.wifList = h("div", "vb-wlist");
    el.wifList.dataset.vbWhatifList = "";
    el.wif.appendChild(el.wifList);
    var wrow = h("div", "vb-row");
    el.wifTrade = h("input", "vb-input mono vb-narrow");
    el.wifTrade.placeholder = "a booked trade (MX-30000121) or a scope (BOOK:BOOK-RATES-3)";
    el.wifTrade.setAttribute("aria-label", "The booked trade, or the scope, to copy");
    el.wifTrade.dataset.vbWhatifTrade = "";
    el.wifFactor = h("input", "vb-input mono vb-tiny");
    el.wifFactor.value = "1";
    el.wifFactor.setAttribute("aria-label", "Factor (−1: its unwind)");
    el.wifFactor.title = "Factor: 1 the same trade again, −1 its unwind, 0.5 half";
    el.wifFactor.dataset.vbWhatifFactor = "";
    el.wifRun = btn("", "+ What-if trade", "A copy of a booked trade, scaled, added to the basket: VaR and ES before and after");
    el.wifRun.dataset.vbWhatifRun = "";
    wrow.appendChild(el.wifTrade);
    wrow.appendChild(h("span", "vb-lbl", "× factor"));
    wrow.appendChild(el.wifFactor);
    wrow.appendChild(el.wifRun);
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
    el.msg = h("span", "vb-msg");
    el.msg.dataset.vbMsg = "";
    el.msg.setAttribute("aria-live", "polite");
    el.open = btn("", "Open without saving");
    el.open.dataset.vbOpen = "";
    el.save = btn("primary", "Save basket and open VaR");
    el.save.dataset.vbSave = "";
    [el.name, el.share, el.msg, el.open, el.save].forEach(function (x) { save.appendChild(x); });
    root.appendChild(save);
    wire(root);
    return root;
  }

  function wire(root) {
    el.find.addEventListener("input", function () { clearTimeout(hintTimer); hintTimer = setTimeout(hints, 160); });
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
      } else if (ev.key === "Enter") { ev.preventDefault(); pick(hintRows[hi]); }
    });
    el.find.addEventListener("blur", function () { setTimeout(closeHints, 200); });
    el.bank.addEventListener("change", function () { T.wholeBank = el.bank.checked; changed(true); });
    el.clear.addEventListener("click", function () {
      T.rows = []; T.whatif = []; T.saved = null; T.copyOf = null; T.name = ""; T.share = ""; T.dirty = false; T.wholeBank = false;
      el.wifOut.textContent = ""; el.msg.textContent = "";
      changed(false);
    });
    el.xlsx.addEventListener("click", download);
    el.pick.addEventListener("focus", listSaved);
    el.pick.addEventListener("change", function () { var v = el.pick.value; el.pick.value = ""; if (v) edit(v); });
    el.wifRun.addEventListener("click", function () {
      var id = el.wifTrade.value.trim().replace(/^TRD:/i, ""), f = Number(el.wifFactor.value);
      if (!id) { el.wifTrade.focus(); el.wifOut.textContent = "Name a booked trade (or a scope) to copy."; return; }
      if (isNaN(f) || f === 0) { el.wifOut.textContent = "The factor is a number other than 0 (−1: the trade's unwind)."; return; }
      T.whatif.push({ copyOf: id, factor: f });
      el.wifTrade.value = "";
      persist();
      paintWhatIf();
      runWhatIf();
    });
    el.wifList.addEventListener("click", function (ev) {
      var b = ev.target.closest("[data-vb-wdel]");
      if (!b) return;
      T.whatif.splice(+b.dataset.vbWdel, 1);
      persist();
      paintWhatIf();
      if (T.whatif.length) runWhatIf(); else { el.wifOut.textContent = ""; delete el.wifOut.dataset.done; }
    });
    el.name.addEventListener("input", function () { T.name = el.name.value; persist(); paint(); });
    el.share.addEventListener("input", function () { T.share = el.share.value; persist(); });
    el.open.addEventListener("click", function () {
      var s = compose();
      if (!s) { el.msg.textContent = "The server is still checking the sheet: a moment."; return; }
      if (s.length > OPEN_MAX) { el.msg.textContent = "This basket is too large to open without saving: name it and save it."; return; }
      go(s);
    });
    el.save.addEventListener("click", save);
    el.close.addEventListener("click", function () { hide(); });
    root.addEventListener("keydown", function (ev) { if (ev.key === "Escape" && popup && !grid.editing) hide(); });
  }

  // ---------------------------------------------------------------------------------------------------------------- hints
  function hints(addTyped) {
    var raw = el.find.value, q = raw.trim(), minus = /^[-−]/.test(q);
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
        r.appendChild(h("span", "k", (minus || T.mode === "-" ? "− " : "") + x.type));
        r.appendChild(h("span", "t mono", x.id));
        r.appendChild(h("span", "n", x.name));
        r.appendChild(h("span", "d mono", x.trades ? x.trades.toLocaleString("en-US") + " trade" + (x.trades === 1 ? "" : "s") + " · VaR " + short(x["var"])
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
    add(x.scope, { op: x.minus || T.mode === "-" ? "-" : "+" });
    el.find.value = "";
    closeHints();
    el.find.focus();
  }

  // ---------------------------------------------------------------------------------------------------------------- placing parts
  /** The column a scope lands in, and the cell's text there: {key, text}, or null when the sheet has no column for it (a rule). */
  function columnOf(scope) {
    var w = wordOf(scope), id = idOf(scope), has = keys();
    if (w === "NODE") {
      var leaf = id.slice(id.indexOf("~") + 1), m = /^(BOOK|DESK)-/.exec(leaf);
      if (m && id.indexOf("MGMT~") === 0) return { key: m[1], text: leaf };
      var hw = id.slice(0, id.indexOf("~"));
      if (hw && has.indexOf(hw) >= 0) return { key: hw, text: leaf };
      return { key: "DESK", text: "NODE:" + id };
    }
    if (w === "BASKET") return { key: "PORT", text: "BASKET:" + id };
    if (w === "LE" || w === "DIV") return { key: "DESK", text: w + ":" + id };
    if (has.indexOf(w) >= 0) return { key: w, text: id };
    return null;
  }

  /** Puts a part in its column: the first row whose cell there is free and whose sign is the part's (a blank row takes the sign). */
  function place(scope, op, quiet) {
    var at = columnOf(scope);
    if (!at) return null;
    var ids = /^(TRD|POS|CPTY)$/.test(at.key) && at.text.indexOf(",") > 0 ? at.text.split(",") : [at.text];
    var placed = null;
    ids.forEach(function (text) {
      text = text.trim();
      if (!text) return;
      var r = 0;
      while (r < T.rows.length && (T.rows[r][at.key] || (filled(T.rows[r]) && (T.rows[r].op || "+") !== op))) r++;
      while (T.rows.length <= r) T.rows.push({ op: op });
      if (!filled(T.rows[r])) T.rows[r].op = op;
      T.rows[r][at.key] = text;
      placed = { key: at.key, row: r };
    });
    if (!quiet && placed && grid) grid.select(placed.row, keys().indexOf(placed.key) + 1);
    return placed;
  }

  /** Adds a part (a hint, a row's "+ basket"): it lands in its kind's column and the sheet shows. */
  function add(scope, meta) {
    meta = meta || {};
    if (!scope) return;
    if (scope === "BANK") { T.wholeBank = true; changed(true); show(); return; }
    var op = meta.op === "-" ? "-" : "+";
    ensure();
    var at = place(scope, op);
    if (!at) { show(); el.msg.textContent = "The sheet has no column for " + scope + ": open it as a scope from the command line instead."; return; }
    changed(true);
    show();
    el.msg.textContent = (op === "-" ? "Taking away " : "Added ") + scope + " (row " + (at.row + 1) + ").";
  }

  function changed(dirty) {
    if (dirty && T.saved) T.dirty = true;
    persist();
    paint();
    clearTimeout(timer);
    timer = setTimeout(check, 350);
  }

  function paint() {
    countBadge();
    if (!host) return;
    el.state.textContent = stateText();
    el.state.className = "vb-state" + (T.saved && !T.dirty ? " saved" : "");
    el.mode.querySelectorAll("[data-vb-mode]").forEach(function (b) {
      var on = b.dataset.vbMode === T.mode;
      b.classList.toggle("on", on);
      b.setAttribute("aria-checked", on ? "true" : "false");
    });
    el.bank.checked = !!T.wholeBank;
    if (document.activeElement !== el.name) el.name.value = T.name || "";
    if (document.activeElement !== el.share) el.share.value = T.share || "";
    var any = !empty(), named = !!(T.name || "").trim();
    el.open.disabled = !any;
    el.clear.disabled = !any && !T.whatif.length;
    el.xlsx.disabled = !any;
    el.save.disabled = !any || !named;
    var editing = T.saved && T.saved.editable && same(T.name, T.saved.name);
    el.save.textContent = editing ? "Save as version " + (T.saved.version + 1) + " and open VaR" : T.copyOf ? "Save as your own basket and open VaR" : "Save basket and open VaR";
    el.save.title = !any ? "Add at least one part, or tick Start from the whole bank" : !named ? "Name the basket first"
      : editing ? "Keeps the name " + T.saved.id + (T.saved.own ? "" : " (an administrator's edit: saved under its owner, who is told)") : "Save as " + slug(T.name);
    if (grid) grid.draw();
    paintWhatIf();
  }
  function same(a, b) { return String(a || "").toLowerCase() === String(b || "").toLowerCase() || slug(a) === slug(b); }
  function slug(n) { return String(n || "").toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64); }
  function countBadge() {
    var n = parts() + (T.wholeBank ? 1 : 0);
    document.querySelectorAll("[data-basket-count]").forEach(function (c) { c.textContent = n.toLocaleString("en-US"); c.hidden = !n; });
  }

  // ---------------------------------------------------------------------------------------------------------------- the server's answer
  /** Sends the sheet to the server and renders its checks and figures (a later change wins). */
  function check() {
    if (!host) return;
    var body = payload(), sig = JSON.stringify(body), my = ++checkSeq;
    var snap = {};
    T.rows.forEach(function (row, r) { keys().forEach(function (k) { if (row[k]) snap[k + ":" + r] = row[k]; }); });
    if (empty()) {
      answer = null; checked = {}; flags = {};
      el.checks.textContent = "";
      el.live.textContent = "";
      el.live.appendChild(h("span", "vb-note", "Nothing in the basket yet: type in the sheet or above it, use \"+ basket\" on any row of the explorer, paste columns from Excel, or tick Start from the whole bank."));
      delete el.live.dataset.sig;
      paint();
      return;
    }
    el.live.classList.add("computing");
    post("basket/sheet", body).then(function (a) {
      if (my !== checkSeq) return;
      a.sig = sig;
      answer = a;
      checked = snap;
      flags = {};
      (a.cells || []).forEach(function (c) { flags[c.column + ":" + c.row] = c; });
      if (a.columns && a.columns.length) T.columns = a.columns.map(function (c) { return { key: c.key, title: c.title }; });
      persist();
      paint();
      renderChecks(a);
      renderFigures(a);
      var before = lastScope;
      lastScope = a.scope || "";
      if (attached && attached.onChange && lastScope.length <= OPEN_MAX) attached.onChange(lastScope, before);
      host.dispatchEvent(new CustomEvent("drishti:vb-live", { bubbles: true, detail: a }));
    }).catch(function (e) {
      if (my !== checkSeq) return;
      el.live.classList.remove("computing");
      el.live.textContent = "";
      var p = h("p", "vb-err", (e.status === 403 ? "Not for your role: " : "Could not check the sheet: ") + e.message);
      p.setAttribute("role", "alert");
      el.live.appendChild(p);
      el.live.dataset.sig = sig;
      el.live.dataset.error = e.message;
    });
  }

  function renderChecks(a) {
    el.checks.textContent = "";
    el.checks.appendChild(h("span", "pill g", a.found.toLocaleString("en-US") + " found"));
    if (a.notFound) el.checks.appendChild(h("span", "pill r", a.notFound.toLocaleString("en-US") + " not found"));
    if (a.flagged) el.checks.appendChild(h("span", "pill a", a.flagged.toLocaleString("en-US") + " adding or taking away nothing"));
    var list = h("ul", "vb-reasons");
    list.dataset.vbReasons = "";
    var colTitle = {};
    T.columns.forEach(function (c) { colTitle[c.key] = c.title; });
    (a.cells || []).slice(0, 8).forEach(function (c) {
      var li = h("li", c.status === "not-found" ? "bad" : "warn");
      li.dataset.vbReason = c.column + ":" + c.row;
      li.appendChild(h("b", null, "Row " + (c.row + 1) + ", " + (colTitle[c.column] || c.column) + ": "));
      li.appendChild(document.createTextNode(c.reason + (c.status === "not-found" ? " (left out of the figures, kept in the sheet until you remove it)" : "")));
      li.addEventListener("click", function () { grid.select(c.row, keys().indexOf(c.column) + 1); grid.root.focus({ preventScroll: true }); });
      list.appendChild(li);
    });
    if ((a.cells || []).length > 8) list.appendChild(h("li", "vb-more", "and " + (a.cells.length - 8).toLocaleString("en-US") + " more: each cell's reason is its tooltip"));
    if (a.cells && a.cells.length) el.checks.appendChild(list);
    if (a.notFound) {
      var rm = btn("", "Remove the not found");
      rm.dataset.vbRemoveMissing = "";
      rm.addEventListener("click", function () {
        (answer.cells || []).forEach(function (c) {
          var row = T.rows[c.row];
          if (c.status === "not-found" && row && row[c.column] === c.text) row[c.column] = "";
        });
        compact();
        changed(true);
      });
      el.checks.appendChild(rm);
    }
  }

  /** Drops rows left empty (after removing cells), keeping the order of the rest. */
  function compact() { T.rows = T.rows.filter(filled); }

  function renderFigures(a) {
    el.live.classList.remove("computing");
    el.live.textContent = "";
    el.live.dataset.sig = a.sig;
    el.live.dataset.var = a["var"] === null || a["var"] === undefined ? "" : String(a["var"]);
    el.live.dataset.trades = String(a.trades || 0);
    el.live.dataset.ccy = a.currency || "USD";
    delete el.live.dataset.error;
    var ccy = a.currency && a.currency !== "USD" ? " " + a.currency : "";
    var pct = function (x) { return x ? Math.round(x * 1000) / 10 + "%" : ""; };
    function kpi(k, v, s, cls, key) {
      var d = h("div", "vb-kpi" + (cls ? " " + cls : ""));
      d.dataset.vbKpi = key;
      d.appendChild(h("div", "k", k));
      d.appendChild(h("div", "v mono", v));
      if (s) d.appendChild(h("div", "s", s));
      el.live.appendChild(d);
    }
    if (a.empty) {
      kpi("VaR of the basket", "—", a.notice || "no trades", "edge-a", "var");
      return;
    }
    kpi("VaR " + pct(a.confidence) + " 1D of the basket", money(a["var"]) + ccy, "the engine's, follows every change", "", "var");
    kpi("ES " + pct(a.esConfidence), money(a.es) + ccy, null, "", "es");
    kpi("Sum of the parts", money(a.sumOfParts), a.wholeBank ? "the whole bank has no parts to add up" : (a.partsAdded || 0).toLocaleString("en-US") + " part" + (a.partsAdded === 1 ? "" : "s") + " added, each alone", "", "sum");
    kpi("Diversification", a.diversification === null || a.diversification === undefined ? "—" : money(a.diversification), null, "pos", "div");
    kpi("Trades", (a.trades || 0).toLocaleString("en-US"), "a trade in two parts counts once" + (a.takenAway ? "; " + a.takenAway.toLocaleString("en-US") + " taken away" : ""), "", "trades");
    el.live.appendChild(h("span", "vb-ms", "checked and computed by the server in " + a.computeMs + " ms"));
  }

  // ---------------------------------------------------------------------------------------------------------------- what-if
  function paintWhatIf() {
    if (!el.wifList) return;
    el.wifList.textContent = "";
    if (!T.whatif.length) return;
    var t = h("table", "vb-table");
    var hr = h("tr");
    ["Copy of", "Factor", ""].forEach(function (x, i) { hr.appendChild(h("th", i === 1 ? "n" : null, x)); });
    t.appendChild(h("thead")).appendChild(hr);
    var tb = h("tbody");
    T.whatif.forEach(function (w, k) {
      var tr = h("tr");
      tr.dataset.vbWhatifChip = w.copyOf;
      tr.appendChild(h("td", "mono", w.copyOf));
      tr.appendChild(h("td", "n mono", String(w.factor).replace("-", "−")));
      var td = h("td");
      var x = btn("", "×", "Remove the what-if " + w.copyOf);
      x.dataset.vbWdel = String(k);
      td.appendChild(x);
      tr.appendChild(td);
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    el.wifList.appendChild(t);
  }
  function openWhatIf() {
    show();
    el.wifTrade.focus();
  }
  function runWhatIf() {
    if (!T.whatif.length) return;
    var scope = compose();
    if (!scope) {
      if (empty()) { el.wifOut.textContent = "Fill the basket first: the what-if adds its trades to it."; return; }
      el.wifOut.textContent = "waiting for the server's check of the sheet…";
      var once = function () { host.removeEventListener("drishti:vb-live", once); runWhatIf(); };
      host.addEventListener("drishti:vb-live", once);
      return;
    }
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
        tr.appendChild(h("td", "n mono", short(r[1])));
        tr.appendChild(h("td", "n mono", short(r[2])));
        tr.appendChild(h("td", "n mono " + (r[3] > 0 ? "up" : "down"), (r[3] > 0 ? "+" : "") + short(r[3])));
        tb.appendChild(tr);
      });
      (w.items || []).forEach(function (it) {
        var tr = h("tr", "vb-sub");
        tr.appendChild(h("td", null, it.label + " alone " + short(it.standaloneVar)));
        tr.appendChild(h("td", "n mono", ""));
        tr.appendChild(h("td", "n mono", "component " + short(it.componentVar)));
        tr.appendChild(h("td", "n mono", (it.incrementalVar > 0 ? "+" : "") + short(it.incrementalVar)));
        tb.appendChild(tr);
      });
      t.appendChild(tb);
      el.wifOut.appendChild(t);
      el.wifOut.appendChild(h("p", "vb-note", "Pre-deal what-if by the server's VaR engine: " + (w.note || "")));
    }).catch(function (e) {
      el.wifOut.textContent = "";
      el.wifOut.dataset.done = "failed";
      var p = h("p", "vb-err", "What-if failed: " + e.message + (e.code ? " (" + e.code + ")." : ""));
      p.setAttribute("role", "alert");
      el.wifOut.appendChild(p);
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- save, open, edit
  function save() {
    var name = (T.name || "").trim();
    if (empty() || !name) { el.msg.textContent = "Name the basket and add at least one part (or tick Start from the whole bank)."; return; }
    el.msg.textContent = "saving…";
    var share = String(T.share || "").split(/[\s,]+/).filter(Boolean);
    var body = { name: name, title: name, sheet: payload(), sharedWith: share };
    if (T.saved && T.saved.editable && same(name, T.saved.name)) {
      body.name = T.saved.id.slice(T.saved.id.indexOf("/") + 1);
      body.version = T.saved.version;
      if (!T.saved.own) body.owner = T.saved.id.slice(0, T.saved.id.indexOf("/"));
    }
    post("baskets", body).then(function (b) {
      T.saved = { id: b.id, name: b.name || name, owner: b.owner, version: b.version, editable: true, own: !body.owner };
      T.copyOf = null; T.dirty = false; T.name = b.name || name;
      persist();
      paint();
      el.msg.textContent = "Saved as " + b.id + " (version " + b.version + (share.length ? ", shared read-only with " + share.join(", ") : ", private")
        + (body.owner ? "; saved under its owner, who is told" : "") + ").";
      document.dispatchEvent(new CustomEvent("drishti:vb-saved", { detail: b }));
      go("BASKET:" + b.id);
    }).catch(function (e) { el.msg.textContent = e.status === 409 ? e.message + " (Open a saved basket ▾ to load it again)" : e.message; });
  }
  function go(scope) {
    if (!scope) return;
    lastScope = compose() || lastScope;
    if (attached && attached.onOpen) { attached.onOpen(scope); return; }
    location.href = "/var?scope=" + encodeURIComponent(scope);
  }

  /** Lays a scope into the sheet, as the server lays it out: a saved basket (Edit), a portfolio's copy (Edit a copy), an ad-hoc scope. */
  function load(scope, quiet) {
    return get("basket/sheet?scope=" + encodeURIComponent(scope)).then(function (d) {
      var s = d.sheet || {}, cols = (d.columns || []).map(function (c) { return { key: c.key, title: c.title }; });
      if (cols.length) T.columns = cols;
      var n = (s.op || []).length, rows = [];
      keys().forEach(function (k) { n = Math.max(n, (s[k] || []).length); });
      for (var r = 0; r < n; r++) {
        var row = { op: (s.op || [])[r] === "-" ? "-" : "+" };
        keys().forEach(function (k) { var v = (s[k] || [])[r]; if (v) row[k] = v; });
        rows.push(row);
      }
      T.rows = rows;
      T.wholeBank = !!s.wholeBank;
      T.dirty = false;
      T.copyOf = null;
      T.saved = null;
      if (d.basket) {
        var b = d.basket;
        T.saved = { id: b.id, name: b.name, owner: b.owner, version: b.version, editable: !!b.editable, own: !!b.own };
        T.name = b.editable ? b.name : "";
        T.share = b.editable ? (b.sharedWith || []).join(", ") : "";
      } else if (d.portfolio) {
        T.copyOf = d.portfolio.id;
        T.name = "copy-of-" + String(d.portfolio.id).toLowerCase();
        T.share = "";
      }
      answer = null; checked = {}; flags = {};
      persist();
      lastScope = scope;
      if (grid) { grid.root.scrollTop = 0; grid.cur = { r: 0, c: 1 }; }
      changed(false);
      if (!quiet) {
        show();
        el.msg.textContent = d.basket ? (d.basket.editable ? "Editing " + d.basket.id + " (version " + d.basket.version + "): Save keeps its name as a new version."
          : "Opened " + d.basket.id + ", shared with you read-only: name it to save your own copy.")
          : d.portfolio ? d.portfolio.note + "." : "Opened " + scope + " in the sheet.";
        if (d.notice) el.msg.textContent += " " + d.notice.charAt(0).toUpperCase() + d.notice.slice(1) + ".";
      }
      return d;
    }).catch(function (e) {
      ensure();
      el.msg.textContent = "Could not open " + scope + ": " + e.message;
      if (!quiet) show();
      throw e;
    });
  }
  function edit(scope) { ensure(); return load(scope, false).catch(function () { return null; }); }   // said in the sheet

  /** The baskets the viewer may open, for "Open a saved basket ▾" (asked when the list is opened). */
  function listSaved() {
    get("baskets?").then(function (bs) {
      var cur = el.pick.value;
      el.pick.textContent = "";
      var o = h("option", null, "Open a saved basket ▾");
      o.value = "";
      el.pick.appendChild(o);
      bs.forEach(function (b) {
        var x = h("option", null, b.name + " · " + b.id + (b.fromSheet ? "" : " (scope)"));
        x.value = "BASKET:" + b.id;
        el.pick.appendChild(x);
      });
      el.pick.value = cur;
    }).catch(function () { /* none listed */ });
  }

  /** The sheet as a workbook, made by the server (it checks every cell again). */
  function download() {
    el.msg.textContent = "making the workbook…";
    fetch("/api/var/basket/sheet.xlsx" + (asOf() ? "?" + asOf().slice(1) : ""), { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name: T.name || "basket", sheet: payload() }) })
      .then(function (r) {
        if (!r.ok) return r.json().catch(function () { return {}; }).then(function (b) { throw new Error(b.detail || "HTTP " + r.status); });
        return r.blob();
      }).then(function (blob) {
        var a = h("a");
        a.href = URL.createObjectURL(blob);
        a.download = (slug(T.name) || "basket") + ".xlsx";
        a.dataset.vbDownloaded = String(blob.size);
        document.body.appendChild(a);
        a.click();
        setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
        el.msg.textContent = "Downloaded " + a.download + ": paste its Basket sheet back here to open it again.";
        el.xlsx.dataset.done = String(blob.size);
      }).catch(function (e) { el.msg.textContent = "Could not make the workbook: " + e.message; });
  }

  // ---------------------------------------------------------------------------------------------------------------- where it shows
  function ensure() {
    if (!host) { host = build(); paint(); listSaved(); }
    return host;
  }
  function show() {
    ensure();
    if (attached) {
      if (host.parentNode !== attached.strip) attached.strip.appendChild(host);
      attached.strip.hidden = false;
      host.classList.remove("popup");
    } else {
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
    }
    grid.draw();
    if (!answer || answer.sig !== signature()) { clearTimeout(timer); timer = setTimeout(check, 50); }
  }
  function hide() {
    if (attached) { attached.strip.hidden = true; attached.closed = true; return; }
    if (popup) popup.hidden = true;
  }
  function toggle() {
    var open = attached ? !attached.strip.hidden : popup && !popup.hidden;
    if (open && !attached) { hide(); return; }
    if (open && attached) { hide(); return; }
    if (attached) attached.closed = false;
    show();
    el.find.focus();
  }

  /** The explorer's strip: {strip: element, onOpen(scope), onChange(scope, before)}. */
  function attach(opts) {
    attached = opts;
    ensure();
    opts.strip.appendChild(host);
  }

  /**
   * The explorer opened a basket or an ad-hoc scope: the sheet holds its parts (a basket's, for editing) unless it holds them already
   * (the sheet's own basket, unchanged; the ad-hoc scope the sheet opened). The sheet itself opens only when asked.
   */
  function mirror(d) {
    if (!d || (d.kind !== "basket" && d.kind !== "adhoc")) return;
    if (d.kind === "basket" && d.basket && T.saved && T.saved.id === d.basket.id && !T.dirty && T.saved.version === d.basket.version && !empty()) return;
    if (d.kind === "adhoc" && (lastScope === d.scope || lastScope === d.canonical || compose() === d.scope || compose() === d.canonical)) return;
    ensure();
    load(d.kind === "basket" && d.basket ? "BASKET:" + d.basket.id : d.canonical || d.scope, true).catch(function () { /* said in the sheet */ });
  }

  // ---------------------------------------------------------------------------------------------------------------- Alt+B
  function start() {
    countBadge();
    // only where the page offers the basket (the explorer's "Construct your basket"); the landing page's Alt+B is its Back to
    window.Drishti.keys.register({ id: "var.basket.sheet", keys: ["Alt+B", "Alt+Shift+B"], scope: "screen", area: "var", group: "VaR", typing: true,
      description: "Construct your basket: open or close the sheet",
      when: function () { return !!document.querySelector("[data-vx-basket-open]") && !document.querySelector("[accesskey=b]"); }, run: function () { toggle(); } });
    // the explorer moved to another reporting currency: the basket's figures follow it
    document.addEventListener("drishti:var-currency", function () { if (host && !empty()) check(); });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();

  window.Drishti.VarBasket = { add: add, attach: attach, mirror: mirror, show: show, hide: hide, toggle: toggle, compose: compose, signature: signature,
                       edit: edit, openWhatIf: openWhatIf, isEmpty: empty, parts: parts, state: T, payload: payload,
                       answer: function () { return answer; }, check: function () { check(); } };
})();
