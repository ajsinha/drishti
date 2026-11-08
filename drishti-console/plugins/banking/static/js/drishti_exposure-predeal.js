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
/* Counterparties → Pre-deal check (terminal/predeal.html; EXPOSURE_GUIDE.md section 18.9; the CCR cube mock-up C's pre-deal tiles and
   overlay). The trade is a drishti-inputs Form: the counterparty and its netting set (from the exposure tree the person sees), a booked
   trade to copy (the set's trades, from its contributions) or the trade described (asset class, maturity), the notional (money) and the
   side. Check asks the server's pre-deal service (POST /api/exposure/predeal) and draws its answer: the decision and its reasons, the
   limit and each tenor bucket within, near or over with before, after, increment, use and headroom (risk grids), the figures current
   against with the trade (EPE, EEPE, peak PFE, peak EE, CE, EAD), the profile overlay with the limit and the incremental PFE per tenor
   (drishti-risk on drishti-chart), the table by tenor. Every number is the server's, as sent; the page formats and places them. How a
   number was made is the one Explain's (drishti-insight, kind predeal): a tile, or Explain ›. A typed command (PREDEAL CP-X LIKE 1000001
   250M) arrives as ?command= and is checked at once. */
(function () {
  "use strict";
  var root = document.querySelector("[data-predeal]");
  if (!root || !window.Drishti.elements.risk) return;
  var R = window.Drishti.elements.risk, I = window.Drishti.elements.inputs;
  var $ = function (s) { return root.querySelector(s); };
  var asOf = root.dataset.asof || "";
  var money = R.riskMoney, signed = R.riskSigned, pct = R.riskPct;
  var LAMP = { approve: "green", refer: "amber", decline: "red", within: "green", near: "amber", breach: "red" };
  var WORD = { within: "within", near: "near the limit", breach: "over the limit", "no-dates": "no dates in it" };
  var CLASSES = ["RATES", "FX", "CREDIT", "EQUITY", "COMMODITY"];
  var S = { tree: null, sets: {}, trades: {}, answer: null, seq: 0 };

  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function pill(state) { return el("span", { class: "rg-pill " + (LAMP[state] || ""), "data-state": state || "" }, WORD[state] || state || "—"); }
  function q(params) {
    var p = new URLSearchParams();
    Object.keys(params).forEach(function (k) { if (params[k]) p.set(k, params[k]); });
    if (asOf) p.set("asOf", asOf);
    return p.toString();
  }
  function json(r) {
    return r.json().catch(function () { return {}; }).then(function (b) {
      if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; e.code = b.code; e.body = b; throw e; }
      return b;
    });
  }
  function get(path, params) {
    return fetch("/api/exposure/" + path + "?" + q(params || {}), { credentials: "same-origin", headers: { Accept: "application/json" } }).then(json);
  }
  function notice(t) { var n = $("[data-pd-notice]"); n.hidden = !t; n.textContent = t || ""; }
  function problem(e) {
    var busy = window.Drishti.Busy && window.Drishti.Busy.problem(e);
    if (busy) { window.Drishti.Busy.render($("[data-pd-notice]"), busy, { retry: check, keep: "the form keeps your trade." }); $("[data-pd-notice]").hidden = false; return; }
    notice((e.status === 403 ? "Not for your role: " : "The check could not be made: ") + e.message);
  }

  // ---------------------------------------------------------------------------------------------------------------- the trade (Form)
  function counterparties() {
    var out = [];
    ((S.tree && S.tree.groups) || []).forEach(function (g) { (g.children || []).forEach(function (cp) { if (!cp.noPaths) out.push(cp); }); });
    return out.sort(function (a, b) { return (b.peakPfe || 0) - (a.peakPfe || 0); });
  }

  /** The form's schema from the tree: the counterparties (largest peak PFE first) and their netting sets (a set follows its counterparty). */
  function schema() {
    var cps = counterparties(), sets = [], setLabels = [], parents = {};
    cps.forEach(function (cp) {
      (cp.children || []).filter(function (n) { return !n.noPaths; }).sort(function (a, b) { return (b.peakPfe || 0) - (a.peakPfe || 0); }).forEach(function (n) {
        sets.push(n.id);
        setLabels.push(n.id + " · peak PFE " + money(n.peakPfe) + (n.csa ? " · " + n.csa : ""));
        parents[n.id] = [cp.id];
        S.sets[n.id] = cp.id;
      });
    });
    return { type: "object", order: ["counterparty", "nettingSet", "method", "like", "assetClass", "maturity", "notional", "side", "mtm"],
      required: ["counterparty", "method"], properties: {
        counterparty: { type: "string", title: "Counterparty", widget: "select", hook: "pd-counterparty", enum: cps.map(function (c) { return c.id; }),
          enumLabels: cps.map(function (c) { return c.id + (c.label && c.label !== c.id ? " · " + c.label : "") + " · peak PFE " + money(c.peakPfe)
            + (c.limit ? " · limit " + money(c.limit.limit) + " (" + pct(c.limit.use) + " used)" : " · no limit"); }) },
        nettingSet: { type: "string", title: "Into the netting set", widget: "select", hook: "pd-set", enum: sets, enumLabels: setLabels,
          dependsOn: "counterparty", parents: parents, emptyLabel: "the template's, or the counterparty's only one",
          description: "Netting and collateral with the set's trades; left empty the server takes the template's set or the only one" },
        method: { type: "string", title: "The trade is", widget: "radio", hook: "pd-method", optionHook: "pd-method-option", enum: ["paths", "addon"],
          enumLabels: ["like a booked trade (exact)", "described (proxy, only refers)"], default: "paths" },
        like: { type: "string", title: "Like the trade", widget: "select", hook: "pd-like", enum: [], enumLabels: [], emptyLabel: "Pick a trade of the netting set",
          when: { field: "method", equals: "paths" }, description: "Its paths on the day's run are copied, scaled to the notional" },
        assetClass: { type: "string", title: "Asset class", widget: "select", hook: "pd-class", enum: CLASSES, when: { field: "method", equals: "addon" } },
        maturity: { type: "date", title: "Maturity", hook: "pd-maturity", when: { field: "method", equals: "addon" } },
        notional: { type: "money", title: "Notional", currency: "USD", hook: "pd-notional", minimum: 0,
          description: "Like a trade: blank copies it as it is" },
        side: { type: "string", title: "Side", widget: "radio", hook: "pd-side", optionHook: "pd-side-option", enum: ["same", "reverse"],
          enumLabels: ["the template's side", "the other side (reverse)"], default: "same", when: { field: "method", equals: "paths" } },
        mtm: { type: "money", title: "MtM today", currency: "USD", hook: "pd-mtm", description: "Blank: the copy's MtM, or 0 for a described trade" }
      } };
  }

  var form = null, fields = null;
  function makeForm() {
    fields = schema();
    var host = $("[data-pd-form]");
    host.textContent = "";
    form = new I.Form(host, { schema: fields, value: { method: "paths", side: "same", counterparty: root.dataset.counterparty || undefined,
      nettingSet: root.dataset.nettingSet || undefined }, submitLabel: "Check", submitHook: "pd-check", doneText: "", resetButton: false,
      label: "The trade", onSubmit: function (v) { return check(v); } });
    form.on("change", function (d) {
      if (d.path === "counterparty" || d.path === "nettingSet" || (d.path === "method" && d.value === "paths")) loadTrades();
    });
    if (root.dataset.nettingSet && !root.dataset.counterparty && S.sets[root.dataset.nettingSet]) {       // a netting set alone: its counterparty
      form.setField("counterparty", S.sets[root.dataset.nettingSet]);
      form.setField("nettingSet", root.dataset.nettingSet);
    }
    loadTrades();
  }

  /** The template's choices: the trades of the netting set picked (else the counterparty's largest), largest EE contribution first. */
  function loadTrades() {
    var cp = form.getField("counterparty"), set = form.getField("nettingSet");
    if (!set && cp) {
      var n = counterparties().filter(function (c) { return c.id === cp; })[0];
      var kids = n ? (n.children || []).filter(function (x) { return !x.noPaths; }).sort(function (a, b) { return (b.peakPfe || 0) - (a.peakPfe || 0); }) : [];
      set = kids.length ? kids[0].id : null;
    }
    var like = fields.properties.like;
    if (!set) { like.enum = []; like.enumLabels = []; form.redrawField("like"); return Promise.resolve(); }
    var seq = ++S.seq;
    var got = S.trades[set] || (S.trades[set] = get("scope", { scope: "NSET:" + set }).then(function (sh) {
      return get("scope/contributions", { scope: "NSET:" + set, by: "trade", figure: "EE", date: sh.peakPfeTenor, alone: "true" });
    }));
    return got.then(function (c) {
      if (seq !== S.seq) return;
      var rows = c.rows || [];
      like.enum = rows.map(function (r) { return r.key; });
      like.enumLabels = rows.map(function (r) { return r.key + " · " + (r.product || r.assetClass || "") + " · " + money(r.notional) + (r.maturity ? " · to " + String(r.maturity).slice(0, 10) : ""); });
      like.description = "The trades of " + set + " (its paths on the day's run are copied, scaled to the notional)";
      var cur = form.getField("like");
      if (cur && like.enum.indexOf(cur) < 0) like.enum.unshift(cur), like.enumLabels.unshift(cur);
      form.redrawField("like");
      root.dataset.trades = String(rows.length);
    }).catch(function (e) { delete S.trades[set]; problem(e); });
  }

  // ---------------------------------------------------------------------------------------------------------------- the check
  function body(v) {
    if (v.command) return { command: v.command };
    var b = { counterparty: v.counterparty, nettingSet: v.nettingSet || undefined, notional: v.notional === undefined ? undefined : v.notional,
      mtm: v.mtm === undefined ? undefined : v.mtm };
    if (v.method === "addon") { b.assetClass = v.assetClass; b.maturity = v.maturity; } else { b.like = v.like; b.side = v.side || "same"; }
    return b;
  }

  function check(v) {
    v = v || (form ? form.getValue() : {});
    if (!v.command && v.method !== "addon" && !v.like) { notice("Pick the trade to copy (Like the trade), or describe it."); return Promise.resolve(null); }
    notice("");
    delete root.dataset.decision;
    $("[data-pd-source]").textContent = "checking…";
    var t0 = performance.now();
    return fetch("/api/exposure/predeal" + (asOf ? "?asOf=" + encodeURIComponent(asOf) : ""), { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json" }, body: JSON.stringify(body(v)) }).then(json).then(function (a) {
      S.answer = a;
      draw(a, (performance.now() - t0) / 1000);
      return a;
    }).catch(function (e) { $("[data-pd-source]").textContent = ""; problem(e); return null; });
  }

  // ---------------------------------------------------------------------------------------------------------------- the answer
  function draw(a, secs) {
    var lim = a.limit, exp = a.exposure || {}, base = exp.base || {}, w = exp["with"] || {}, inc = exp.incremental || {};
    var status = lim ? lim.status : "";
    root.dataset.decision = a.decision;
    root.dataset.status = status || "none";
    $("[data-pd-word]").textContent = a.command || "PREDEAL";
    history.replaceState(null, "", "/predeal?command=" + encodeURIComponent(a.command || "") + (asOf ? "&asOf=" + encodeURIComponent(asOf) : ""));
    $("[data-pd-source]").textContent = [a.businessDate ? "business date " + a.businessDate : "", a.runId ? "run " + a.runId : "",
      a.confidence ? "PFE " + pct(a.confidence).replace(".0%", "%") : "", a.checkId ? a.checkId + " (audited)" : "", secs ? window.Drishti.elements.core.formatNumber(secs, { maxDecimals: 1 }) + " s" : ""]
      .filter(Boolean).join(" · ");
    $("[data-pd-verdict-card]").hidden = false;
    $("[data-pd-verdict-title]").textContent = (a.label || a.counterparty || "") + " · verdict";
    $("[data-pd-verdict-sub]").textContent = (a.method && a.method.exact ? "exact" : "proxy") + (a.nettingSet && a.nettingSet.id ? " · into " + a.nettingSet.id
      + (a.nettingSet.how ? " (" + a.nettingSet.how + ")" : "") : "");
    root.querySelectorAll("[data-lamp]").forEach(function (l) { l.classList.toggle("on", l.dataset.lamp === a.decision); });
    $("[data-pd-decision]").textContent = a.decision || "—";
    var np = lim ? pill(status) : el("span", { class: "rg-pill", "data-state": "none" }, "no limit applied");
    np.setAttribute("data-pd-status", "");
    np.title = "the limit and its tenor buckets: within, near (from " + (lim ? pct(lim.near) : "—") + ") or over";
    $("[data-pd-status]").replaceWith(np);
    $("[data-pd-reason]").textContent = a.reason || "";
    var ul = $("[data-pd-reasons]");
    ul.textContent = "";
    (a.reasons || []).slice(1).forEach(function (r) { ul.appendChild(el("li", {}, r)); });
    tiles(a, lim, base, w, inc);
    limits(lim);
    $("[data-pd-cli]").textContent = "drishti.py exposure predeal \"" + (a.command || "") + "\"";
    $("[data-pd-exposure]").href = "/exposure?scope=" + encodeURIComponent("CPTY:" + (a.counterparty || ""));
    $("[data-pd-explain]").setAttribute("data-explain", JSON.stringify(req("decision")));
    whatIf(a, lim, base, w, inc);
    fillForm(a);
  }

  /** A grid's Explain hook: the server's explanation of the check (the one Explain, kind predeal) in the grid's own pane, in words. */
  function explainHook(measureOf) {
    return function (ctx) {
      if (!window.Drishti.Explain || !S.answer) return "Explain needs a check first.";
      return window.Drishti.Explain.ask(req(measureOf(ctx)), "formula").then(function (x) {
        return [x.title || "", x.preview || ""].concat((x.formula && x.formula.lines) || []).filter(Boolean).join("\n");
      });
    };
  }

  function req(measure) {
    var r = { kind: "predeal", measure: measure, command: S.answer.command };
    if (S.answer.businessDate) r.date = S.answer.businessDate;
    return r;
  }

  /** The headline tiles (mock-up C1): each its figure as the server sent it, and the one Explain on it. */
  function tiles(a, lim, base, w, inc) {
    var box = $("[data-pd-tiles]");
    box.textContent = "";
    var t = a.template || {};
    var add = function (id, k, big, small, measure, state) {
      var d = el("div", { class: "rg-tile", "data-pd-tile": id });
      d.appendChild(el("div", { class: "rg-k" }, k));
      var v = el("div", { class: "rg-big" }, big);
      d.appendChild(v);
      var s = el("div", { class: "rg-s" }, small);
      if (state) { s.appendChild(document.createTextNode(" ")); s.appendChild(pill(state)); }
      d.appendChild(s);
      box.appendChild(d);
      if (measure && window.Drishti.Explain) window.Drishti.Explain.bind(v, req(measure));
    };
    add("trade", "New trade", t.tradeId ? (t.product || t.assetClass || "") + " like " + t.tradeId : (t.assetClass || "") + " to " + (t.maturity || ""),
      money(t.candidateNotional) + (t.side === "reverse" ? " · reverse" : "") + (t.factor !== undefined ? " · × " + t.factor : ""));
    if (lim) {
      add("use", "Limit use", pct(lim.useBefore) + " → " + pct(lim.useAfter), lim.measure + " · " + money(lim.after) + " of " + money(lim.limit),
        "decision", lim.status);
      add("headroom", "Headroom after", money(lim.headroomAfter), "before " + money(lim.headroomBefore), "headroom");
      add("increment", "Incremental " + lim.measure, signed(lim.increment), money(lim.before) + " → " + money(lim.after), "increment");
    } else {
      add("increment", "Incremental peak PFE", signed(inc.peakPfe), money(base.peakPfe) + " → " + money(w.peakPfe), "increment");
    }
    add("epe", "EPE", money(base.epe) + " → " + money(w.epe), "incremental " + signed(inc.epe));
    if (w.ead !== undefined) add("ead", "EAD (IMM)", money(base.ead) + " → " + money(w.ead), "α " + exp_alpha(a) + " × EEPE · incremental " + signed(inc.ead), "ead");
    add("saccr", "Incremental EAD (SA-CCR)", "—", a.saCcr && a.saCcr.status === "under-construction" ? "under construction (" + a.saCcr.release + ")" : "");
  }
  function exp_alpha(a) { return a.exposure && a.exposure.alpha !== undefined ? a.exposure.alpha : "—"; }

  /** The limit and each tenor bucket (a risk grid): before, after, increment, use, headroom and within, near or over. */
  function limits(lim) {
    var host = $("[data-pd-limits]");
    if (!lim) { host.textContent = ""; return; }
    var rows = [{ id: "limit", what: lim.id + " · " + lim.measure, limit: lim.limit, before: lim.before, after: lim.after, increment: lim.increment,
      useBefore: lim.useBefore, useAfter: lim.useAfter, headroomAfter: lim.headroomAfter, status: lim.status }]
      .concat((lim.buckets || []).map(function (b) { return Object.assign({ id: "b:" + b.bucket, what: "bucket " + b.bucket }, b); }));
    var m = function (f, h) { return R.figureColumn(f, h, { format: money, width: 96 }); };
    var p = function (f, h) { return R.figureColumn(f, h, { format: pct, width: 84 }); };
    R.riskGrid(host, { title: "The limit and its buckets", rows: rows, fitRows: rows.length, explain: explainHook(function () { return "headroom"; }), getRowId: function (r) { return r.id; },
      columns: [R.textColumn("what", "Limit", { width: 180, pinned: "left" }), m("limit", "Limit"), m("before", "Before"), m("after", "With the trade"),
        R.figureColumn("increment", "Increment", { format: signed, width: 96 }), p("useBefore", "Use before"), p("useAfter", "Use after"),
        R.figureColumn("headroomAfter", "Headroom", { format: money, width: 96, tone: function (v) { return v < 0 ? "bad" : null; } }),
        { field: "status", header: "Status", width: 120, sortable: false, cellRenderer: function (x) { return pill(x.value); } }] });
  }

  /** The what-if (mock-up C2): the profile with the trade over the profile before, the limit line, the incremental PFE per tenor. */
  function whatIf(a, lim, base, w, inc) {
    var prof = inc.profile || [];
    $("[data-pd-whatif]").hidden = !prof.length;
    $("[data-pd-tenor-card]").hidden = !prof.length;
    var rows = prof.map(function (r, i) { return Object.assign({ id: String(i) }, r); });
    var q = a.confidence ? pct(a.confidence).replace(".0%", "%") : "";
    var onPfe = lim && lim.confidence !== null && lim.confidence !== undefined;
    $("[data-pd-overlay-sub]").textContent = (a.label || a.counterparty || "") + " · PFE " + q + " before and with the trade" + (onPfe ? ", the limit" : "");
    if (prof.length) {
      R.riskChart($("[data-pd-overlay]"), { kind: "risk.exposure-profile", title: "Pre-deal overlay", data: { rows: rows }, id: "id", x: "t", ee: "ee", pfe: "pfe",
        pfeTitle: "PFE " + q + " with the trade", eepe: w.eepe !== undefined ? w.eepe : false, limit: onPfe ? lim.limit : undefined, log: true,
        more: [{ field: "pfeBase", name: "PFE " + q + " before", tone: "muted", opacity: 0 }, { field: "eeBase", name: "EE before", tone: "faint", opacity: 0 }],
        y: { format: "compact" } }, { height: 240 });
      R.riskChart($("[data-pd-bars]"), { kind: "bar", title: "Incremental PFE per tenor", id: "id", data: { rows: rows },
        x: { field: "tenor", title: "", order: rows.map(function (r) { return r.tenor; }) }, y: { field: "incrementalPfe", format: "compact" }, legend: "none" }, { height: 120 });
    }
    var figs = [["peakPfe", "Peak PFE " + q], ["epe", "EPE"], ["eepe", "EEPE"], ["peakEe", "Peak EE"], ["ce", "CE (today)"], ["ead", "EAD (IMM)"]]
      .filter(function (f) { return w[f[0]] !== undefined || base[f[0]] !== undefined; })
      .map(function (f) { return { id: f[0], figure: f[1], current: base[f[0]], with: w[f[0]], incremental: inc[f[0]] }; });
    R.riskGrid($("[data-pd-compare]"), { title: "Current and with the trade", rows: figs, fitRows: figs.length,
      explain: explainHook(function (ctx) { return ctx.rowId === "ead" ? "ead" : "increment"; }), getRowId: function (r) { return r.id; },
      columns: [R.textColumn("figure", "Figure", { width: 120, pinned: "left" }), R.figureColumn("current", "Current", { format: money, width: 100 }),
        R.figureColumn("with", "With the trade", { format: money, width: 110 }),
        R.figureColumn("incremental", "Incremental", { format: signed, width: 100, tone: function (v) { return v > 0 ? "warn" : v < 0 ? "good" : null; } })] });
    $("[data-pd-method-note]").textContent = a.method ? (a.method.name || a.method.id) + ": " + (a.method.text || "") : "";
    $("[data-pd-saccr]").textContent = a.saCcr ? a.saCcr.note || "" : "";
    R.riskGrid($("[data-pd-tenor]"), { title: "By tenor", rows: rows, fitRows: Math.min(rows.length, 40), explain: explainHook(function () { return "increment"; }), getRowId: function (r) { return r.id; },
      columns: [R.textColumn("tenor", "Tenor", { width: 70, pinned: "left" }), R.figureColumn("eeBase", "EE before", { format: money, width: 100 }),
        R.figureColumn("ee", "EE with", { format: money, width: 100 }), R.figureColumn("pfeBase", "PFE before", { format: money, width: 100 }),
        R.figureColumn("pfe", "PFE with", { format: money, width: 100 }),
        R.figureColumn("incrementalPfe", "Incremental PFE", { format: signed, width: 120, tone: function (v) { return v > 0 ? "warn" : null; } })] });
  }

  /** A typed command's trade put back in the form, so the next change starts from it. */
  function fillForm(a) {
    if (!form) return;
    var t = a.template || {};
    if (a.counterparty && form.getField("counterparty") !== a.counterparty) form.setField("counterparty", a.counterparty);
    if (a.nettingSet && a.nettingSet.how === "named" && form.getField("nettingSet") !== a.nettingSet.id) form.setField("nettingSet", a.nettingSet.id);
    var cp0 = form.getField("counterparty");
    var method = a.method && a.method.id === "addon" ? "addon" : "paths";
    if (form.getField("method") !== method) form.setField("method", method);
    if (method === "paths" && t.tradeId && form.getField("like") !== t.tradeId) {
      if (fields.properties.like.enum.indexOf(t.tradeId) < 0) { fields.properties.like.enum.unshift(t.tradeId); fields.properties.like.enumLabels.unshift(t.tradeId); }
      form.setField("like", t.tradeId);
    }
    if (cp0 !== form.getField("counterparty")) loadTrades();
  }

  // ---------------------------------------------------------------------------------------------------------------- start
  get("tree", {}).then(function (t) {
    S.tree = t;
    $("[data-pd-source]").textContent = "business date " + t.businessDate + " · run " + t.runId + " · " + counterparties().length + " counterparties";
    makeForm();
    root.dataset.ready = "1";
    if (root.dataset.command) check({ command: root.dataset.command });
  }).catch(function (e) {
    $("[data-pd-source]").textContent = "";
    problem(e);
    root.dataset.ready = "1";
    if (root.dataset.command) check({ command: root.dataset.command });     // the check works without the pickers
  });
})();
