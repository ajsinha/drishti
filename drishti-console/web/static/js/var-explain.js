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
/* Why it moved (docs/guides/VAR_GUIDE.md section 3.11; docs/lenses/OVERSIGHT_LENS.md section 4.1.1): the VaR page's tab that explains
   a scope's VaR change between two business dates in causes that add up exactly. One answer (GET /api/var/explain), three views over it
   behind a switch, Waterfall (the default, mock-up A), Matrix (B: every child node, the steps across) and Sentences (C); the choice is
   kept in the address (view=) and in the person's settings (varExplainView), and a switch redraws from the answer already fetched.

   It is not drawn by the VaR page's own charts: the answer is turned into a ViewModel of the standard panel kinds (metric, waterfall,
   table, hbar, markdown; docs/architecture/VIEWMODEL.md) and painted by the one renderer of every screen, Drishti Render
   (static/render/1.1.0, vendored with the console; CLIENT_RENDERER.md), so it looks and behaves like the rest of the product.
   Every step is a row of the Steps table that opens its drivers (drishti:row-click). On a phone (MOBILE_DISPLAY.md section 3) the answer
   in words is must, the steps as horizontal bars glance, the selected step's drivers more; the states and the matrix are desk. */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  var pane = root && root.querySelector('[data-pane="why"]');
  if (!pane) return;
  var host = pane.querySelector("[data-vw-host]");
  var VIEWS = ["waterfall", "matrix", "sentences"];
  var params = new URLSearchParams(location.search);
  var S = {
    view: VIEWS.indexOf(params.get("view")) >= 0 ? params.get("view") : (VIEWS.indexOf(pane.dataset.view) >= 0 ? pane.dataset.view : "waterfall"),
    from: params.get("efrom") || "", to: params.get("eto") || "", scope: null, answer: null, matrix: null, step: null, row: null, seq: 0
  };
  var lib = null;
  function renderer() { return lib || (lib = import("/static/render/1.1.0/drishti-render.js")); }
  var phone = function () { return window.matchMedia && window.matchMedia("(max-width: 640px)").matches; };

  // ---- numbers ----------------------------------------------------------------------------------------------------------------
  var NF = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
  function money(v) { return typeof v === "number" && isFinite(v) ? NF.format(v) : "—"; }
  function signed(v) { return typeof v === "number" && isFinite(v) ? (v >= 0 ? "+" : "−") + NF.format(Math.abs(v)) : "—"; }
  function pct(v) { return typeof v === "number" && isFinite(v) ? (v * 100).toFixed(1) + "%" : "—"; }
  function riseTone(v) { return v > 0 ? "neg" : v < 0 ? "pos" : "muted"; }       // a rise in VaR is more risk, not a gain
  function cell(label, text, tone, emphasis) { var c = { label: label, text: text }; if (tone) c.tone = tone; if (emphasis) c.emphasis = true; return c; }
  function row(cols, cells, ev, highlight) {
    var r = { cells: cells.map(function (t, i) { return typeof t === "object" ? Object.assign({ label: cols[i] }, t) : { label: cols[i], text: String(t) }; }),
              highlight: !!highlight, path: null };
    if (ev != null) r.ev = String(ev);
    return r;
  }
  function table(id, title, cols, numeric, rows, total, span, more) {
    var d = { columns: cols, numeric: numeric, rows: rows, search: false };
    if (total) d.total = total;
    if (more) d.more = more;
    return { id: id, kind: "table", title: title, area: "main", span: span || 12, inferred: false, empty: !rows.length, data: d };
  }
  function md(id, title, text, span) {
    return { id: id, kind: "markdown", title: title, area: "main", span: span || 12, inferred: false, empty: !text, data: { text: text } };
  }
  function metric(id, label, value, delta, caption, span) {
    return { id: id, kind: "metric", title: label, area: "main", span: span || 3, inferred: false, empty: false,
             data: { value: Object.assign({ label: label }, value), delta: delta, unit: "USD", caption: caption || "" } };
  }

  // ---- the answer -------------------------------------------------------------------------------------------------------------
  function ask(path, q) {
    var p = new URLSearchParams();
    Object.keys(q).forEach(function (k) { if (q[k]) p.set(k, q[k]); });
    return fetch("/api/var/" + path + "?" + p.toString(), { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; throw e; }
        return b;
      });
    });
  }

  function load(scope) {
    S.scope = scope;
    var seq = ++S.seq;
    pane.querySelector("[data-vw-msg]").textContent = "computing…";
    pane.dataset.state = "computing";
    syncLinks();
    return ask("explain", { scope: scope, from: S.from, to: S.to }).then(function (a) {
      if (seq !== S.seq) return;
      S.answer = a;
      S.matrix = null;
      S.step = defaultStep(a);
      pane.querySelector("[data-vw-from]").value = a.from;
      pane.querySelector("[data-vw-to]").value = a.to;
      pane.querySelector("[data-vw-msg]").textContent = "";
      return draw();
    }).catch(function (e) {
      if (seq !== S.seq) return;
      pane.dataset.state = "failed";
      pane.querySelector("[data-vw-msg]").textContent = e.message || String(e);
      host.textContent = "";
    });
  }

  /** The step opened first: the largest in size. */
  function defaultStep(a) {
    var best = null;
    (a.lines || []).forEach(function (l) { if (!best || Math.abs(l.effect) > Math.abs(best.effect)) best = l; });
    return best ? best.key : null;
  }

  function lineOf(key) { return (S.answer.lines || []).filter(function (l) { return l.key === key; })[0]; }
  function stepsOf(line) {
    return (S.answer.steps || []).filter(function (s) { return line && (line.steps || [line.key]).indexOf(s.key) >= 0; });
  }

  // ---- the views ---------------------------------------------------------------------------------------------------------------
  function vm(panels, phoneTiers) {
    var a = S.answer;
    return { contract: "1.0", ref: { kind: "var-explain", id: a.canonical }, title: { pill: "Why it moved", id: a.label + " · " + a.from + " to " + a.to },
             strip: [], panels: panels, keys: [], provenance: { source: "the VaR engine, GET /api/v1/var/explain" },
             phone: phoneTiers ? { panels: phoneTiers } : undefined };
  }

  function tiles() {
    var a = S.answer, first = (a.states || [])[0] || {}, last = (a.states || [])[a.states.length - 1] || {}, out = [];
    out.push(metric("t-from", "VaR " + a.from, { text: money(a.varFrom) }, null, (a.official && a.official.captured ? "as officially captured" : "the earlier date")));
    out.push(metric("t-to", "VaR " + a.to, { text: money(a.varTo), emphasis: true }, { text: signed(a.change), tone: riseTone(a.change) }, "the later date"));
    out.push(metric("t-change", "Change", { text: signed(a.change), tone: riseTone(a.change) }, null,
      a.addsUp ? "the steps add up exactly" : "the steps do NOT add up: see the warnings"));
    if (a.limit) {
      out.push(metric("t-limit", "Limit use", { text: pct(a.limit.useTo) }, { text: ((a.limit.useTo - a.limit.useFrom) * 100 >= 0 ? "+" : "−")
        + Math.abs((a.limit.useTo - a.limit.useFrom) * 100).toFixed(1) + " pts", tone: riseTone(a.limit.useTo - a.limit.useFrom) }, "was " + pct(a.limit.useFrom)));
    } else {
      out.push(metric("t-scen", "VaR scenario", { text: last.varScenario || "—" }, null, "was " + (first.varScenario || "—")));
    }
    return out;
  }

  function waterfallPanel() {
    var a = S.answer, steps = [], run = a.varFrom;
    steps.push({ label: "VaR " + a.from.slice(5), value: a.varFrom, from: 0, to: a.varFrom, text: money(a.varFrom), tone: "muted", total: true });
    (a.lines || []).forEach(function (l) {
      steps.push({ label: l.label, value: l.effect, from: run, to: run + l.effect, text: signed(l.effect), tone: riseTone(l.effect), total: false });
      run += l.effect;
    });
    steps.push({ label: "VaR " + a.to.slice(5), value: a.varTo, from: 0, to: a.varTo, text: money(a.varTo), tone: "muted", total: true });
    return { id: "waterfall", kind: "waterfall", title: "The steps from " + a.from + " to " + a.to + " (rises in VaR orange, falls blue)",
             area: "main", span: 7, inferred: false, empty: false, data: { steps: steps, unit: "USD" } };
  }

  function stepsTable(span) {
    var a = S.answer, cols = ["Step", "Effect", "VaR after", "VaR scenario after"];
    var rows = (a.lines || []).map(function (l) {
      var st = stepsOf(l), lastSt = st[st.length - 1] || {};
      return row(cols, [l.label, cell("Effect", signed(l.effect), riseTone(l.effect)), money(lastSt.varAfter),
        (lastSt.varScenarioChanged ? "→ " : "") + (lastSt.varScenarioAfter || "")], l.key, l.key === S.step);
    });
    var total = row(cols, [{ text: "Σ the change" }, cell("Effect", signed(a.sumOfSteps), riseTone(a.sumOfSteps), true), money(a.varTo), {}]);
    return table("steps", "Click a step for who drove it", cols, [false, true, true, false], rows, total, span || 5);
  }

  function driversPanel(span) {
    var line = lineOf(S.step), parts = stepsOf(line), out = [];
    if (!line) return [md("drivers", "Who drove it", "Choose a step.", span)];
    parts.forEach(function (s) {
      var d = s.drivers || {}, cols, rows, total = null, numeric;
      var title = s.label + ": " + signed(s.effect) + " — who drove it";
      if (d.kind === "children") {
        cols = [d.by === "node" ? "Child" : "Book", "Component before", "Component after", "Change", "Share"];
        numeric = [false, true, true, true, true];
        rows = (d.rows || []).map(function (r) {
          return row(cols, [r.label, money(r.componentBefore), money(r.componentAfter), cell("Change", signed(r.change), riseTone(r.change)), pct(r.share)]);
        });
        total = row(cols, [{ text: "Σ = the step" }, {}, {}, cell("Change", signed(d.sum), riseTone(d.sum), true), {}]);
      } else if (d.kind === "days") {
        cols = ["Scenario day", "P&L", "Rank", "In the tail", "The VaR scenario"];
        numeric = [false, true, true, false, false];
        rows = (d.rows || []).map(function (r) {
          return row(cols, [r.day, money(r.pnl), String(r.rank), r.inTail ? "yes (rank ≤ " + d.k + ")" : "no", r.varScenario ? "yes" : ""]);
        });
      } else if (d.kind === "trades" || d.kind === "books") {
        cols = [d.kind === "books" ? "Book" : "Trade", d.kind === "books" ? "How" : "Book", d.kind === "books" ? "Trades" : "Why", "Incremental VaR"];
        numeric = [false, false, d.kind === "books", true];
        rows = (d.rows || []).map(function (r) {
          return row(cols, d.kind === "books" ? [r.id, r.how, String(r.trades), cell("Incremental VaR", signed(r.incrementalVar), riseTone(r.incrementalVar))]
            : [r.id, r.book || "", r.reason || r.product || "", cell("Incremental VaR", signed(r.incrementalVar), riseTone(r.incrementalVar))]);
        });
        if (d.restLabel) rows.push(row(cols, [d.restLabel, "", "", cell("Incremental VaR", signed(d.rest), riseTone(d.rest))]));
        total = row(cols, [{ text: "Σ = the step" }, {}, {}, cell("Incremental VaR", signed(s.effect), riseTone(s.effect), true)]);
      } else {
        out.push(md("drivers-" + s.key, title, d.note || s.reads, span));
        return;
      }
      out.push(table("drivers-" + s.key, title, cols, numeric, rows, total, span,
        d.count > (d.shown || 0) ? (d.count - (d.shown || 0)) + " more in the last line" : null));
      if (d.note) out.push(md("note-" + s.key, "How to read it", d.note, span));
    });
    return out;
  }

  function statesTable() {
    var a = S.answer, cols = ["State", "After", "Trades", "Vectors of", "Days", "Scenarios", "VaR", "VaR scenario", "Step"];
    var rows = (a.states || []).map(function (s) {
      return row(cols, [s.id, s.what, String(s.trades), s.vectors, s.days, String(s.scenarios), money(s.var), s.varScenario || "",
        s.effect == null ? "" : cell("Step", signed(s.effect), riseTone(s.effect))]);
    });
    var total = row(cols, [{ text: "Σ" }, {}, {}, {}, {}, {}, {}, {}, cell("Step", signed(a.change), riseTone(a.change), true)]);
    return table("states", "The states: each a full VaR, each step the difference of two", cols,
      [false, false, true, false, false, true, true, false, true], rows, total, 12);
  }

  function orderFreeTable() {
    var o = S.answer.orderFree || {}, cols = ["Part", S.answer.from, S.answer.to, "Change"];
    var rows = (o.rows || []).map(function (r) {
      return row(cols, [r.label + (r.note ? " (" + r.note + ")" : ""), money(r.from), money(r.to), cell("Change", signed(r.change), riseTone(r.change))]);
    });
    var total = row(cols, [{ text: "Σ = the change" }, {}, {}, cell("Change", signed(o.total), riseTone(o.total), true)]);
    return table("orderfree", "Order-free: each " + (o.by === "node" ? "child's" : "book's") + " component VaR, later less earlier", cols,
      [false, true, true, true], rows, total, 7);
  }

  function notesPanel() {
    var a = S.answer, lines = [];
    lines.push("Order: " + (a.order || []).join(", ") + " (drishti.var.explain.order). Each step is the next state's VaR less this one's, so the steps add up "
      + "exactly; another order gives other step sizes with the same total. The Shapley variant, averaging every order, is planned.");
    if (a.official && a.official.note) lines.push("Start: " + a.official.note + ".");
    lines.push("Risk factors: the market step by curve, issuer and currency needs position sensitivities (planned); the exact steps above do not.");
    (a.warnings || []).forEach(function (w) { lines.push("Note: " + w + "."); });
    return md("notes", "How it was worked out", lines.join("\n\n"), 5);
  }

  function sentencesPanel(span) {
    return md("answer", "The answer", (S.answer.sentences || []).join(" "), span || 12);
  }

  function stepBars(id, title, lines) {
    return { id: id, kind: "hbar", title: title, area: "main", span: 12, inferred: false, empty: !lines.length,
             data: { bars: lines.map(function (l) { return { label: l.label, value: l.effect, text: signed(l.effect), tone: riseTone(l.effect), ev: l.key }; }) } };
  }

  function waterfallView() {
    return vm(tiles().concat([waterfallPanel(), stepsTable(5)], driversPanel(7), [notesPanel(), statesTable(), orderFreeTable()]));
  }

  function sentencesView() {
    var a = S.answer, cols = ["Cause", "Sequence", "Shapley"];
    var rows = (a.lines || []).map(function (l) { return row(cols, [l.label, cell("Sequence", signed(l.effect), riseTone(l.effect)), "planned"]); });
    var total = row(cols, [{ text: "Σ = the change" }, cell("Sequence", signed(a.sumOfSteps), riseTone(a.sumOfSteps), true), {}]);
    return vm([sentencesPanel(12), table("seq", "Sequence and Shapley, by cause (Shapley: planned, not built)", cols, [false, true, true], rows, total, 7),
      notesPanel()]);
  }

  function matrixView() {
    var m = S.matrix, cols = ["Node", "VaR " + S.answer.from].concat((m.steps || []).map(function (s) { return s.label; }), ["VaR " + S.answer.to, "Change"]);
    var numeric = cols.map(function (c, i) { return i > 0; });
    var rows = (m.rows || []).map(function (r, i) {
      var eff = r.effects || {};
      return row(cols, [(r.depth ? "  " : "") + r.label, money(r.varFrom)].concat((m.steps || []).map(function (s) {
        var v = eff[s.key]; return v == null ? "—" : cell(s.label, signed(v), riseTone(v));
      }), [money(r.varTo), cell("Change", signed(r.change), riseTone(r.change), true)]), "r" + i, S.row === i);
    });
    var panels = [table("matrix", "The bridge of every node: the steps across (click a row)", cols, numeric, rows, null, 12, m.more ? m.more + " more children" : null)];
    var sel = S.row != null ? (m.rows || [])[S.row] : null;
    if (sel) {
      panels.push(md("cell", sel.label, sel.sentence, 6));
      panels.push(stepBars("cell-steps", sel.label + ": the steps", (m.steps || []).map(function (s) { return { key: s.key, label: s.label, effect: (sel.effects || {})[s.key] || 0 }; })));
    }
    return vm(panels);
  }

  function phoneView() {
    var tiers = { answer: { tier: "must" }, bars: { tier: "glance" } };
    var panels = [sentencesPanel(12), stepBars("bars", "The steps (tap one for who drove it)", S.answer.lines || [])];
    driversPanel(12).forEach(function (p) { tiers[p.id] = { tier: "more" }; panels.push(p); });
    return vm(panels, tiers);
  }

  function draw() {
    if (!S.answer) return Promise.resolve();
    pane.querySelectorAll("[data-vw-view]").forEach(function (b) { b.setAttribute("aria-pressed", b.dataset.vwView === S.view ? "true" : "false"); });
    var make;
    if (phone()) {
      make = Promise.resolve(phoneView());
    } else if (S.view === "matrix") {
      make = S.matrix ? Promise.resolve(matrixView()) : ask("explain/matrix", { scope: S.scope, from: S.answer.from, to: S.answer.to }).then(function (m) {
        S.matrix = m; return matrixView();
      });
    } else {
      make = Promise.resolve(S.view === "sentences" ? sentencesView() : waterfallView());
    }
    return Promise.all([renderer(), make]).then(function (got) {
      var R = got[0], model = got[1];
      var v = R.render(host, model, { theme: "inherit", header: "none", help: false, tables: true, live: false, navigation: "host",
                                      device: phone() ? "phone" : "desk" });
      return v.ready;
    }).then(function () {
      pane.dataset.state = "ready";
      pane.dataset.painted = S.view + (phone() ? "-phone" : "");
      root.dispatchEvent(new CustomEvent("var:explained", { detail: { view: S.view, scope: S.scope } }));
    }).catch(function (e) {
      pane.dataset.state = "failed";
      pane.querySelector("[data-vw-msg]").textContent = e.message || String(e);
    });
  }

  // ---- the controls -----------------------------------------------------------------------------------------------------------
  function syncLinks() {
    var p = new URLSearchParams({ scope: S.scope || "BANK" });
    if (S.from) p.set("from", S.from);
    if (S.to) p.set("to", S.to);
    pane.querySelector("[data-vw-xlsx]").setAttribute("href", "/api/var/explain.xlsx?" + p.toString());
    var u = new URL(location.href);
    if (S.view !== "waterfall") u.searchParams.set("view", S.view); else u.searchParams.delete("view");
    if (S.from) u.searchParams.set("efrom", S.from); else u.searchParams.delete("efrom");
    if (S.to) u.searchParams.set("eto", S.to); else u.searchParams.delete("eto");
    history.replaceState(history.state, "", u.toString());
  }

  pane.querySelectorAll("[data-vw-view]").forEach(function (b) {
    b.addEventListener("click", function () {
      S.view = b.dataset.vwView;
      S.row = null;
      syncLinks();
      fetch("/api/settings", { method: "PATCH", credentials: "same-origin", headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ varExplainView: S.view }) }).catch(function () { /* the link still carries it */ });
      draw();
    });
  });
  pane.querySelector("[data-vw-dates]").addEventListener("submit", function (e) {
    e.preventDefault();
    S.from = pane.querySelector("[data-vw-from]").value || "";
    S.to = pane.querySelector("[data-vw-to]").value || "";
    load(S.scope);
  });
  host.addEventListener("drishti:row-click", function (e) {
    var d = e.detail || {};
    if ((d.panel === "steps" || d.panel === "bars") && d.event) { S.step = d.event; draw(); }
    else if (d.panel === "matrix" && d.event) { S.row = Number(String(d.event).slice(1)); draw(); }
    else if (d.panel === "cell-steps" && d.event) { S.view = "waterfall"; S.step = d.event; var r = (S.matrix.rows || [])[S.row]; S.row = null; if (r) window.VarExplorer ? window.VarExplorer.select(r.scope) : load(r.scope); }
  });
  var resized = null;
  window.addEventListener("resize", function () { clearTimeout(resized); resized = setTimeout(function () { if (!pane.hidden && S.answer) draw(); }, 200); });

  window.drishtiVarExplain = {
    /** The tab opened, or the page moved to another scope while it is open. */
    load: function (scope) { return scope === S.scope && S.answer ? draw() : load(scope); },
    state: S
  };
  document.dispatchEvent(new CustomEvent("var:explain-ready"));
})();
