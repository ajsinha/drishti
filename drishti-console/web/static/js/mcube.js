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
/* The MR cube page (terminal/mcube.html; docs/guides/MR_CUBE_GUIDE.md sections 5 to 8): landing A (the headline strip, the lens controls,
   the time-travel slider, the pivot with sparklines and limit bars, the right rail of tail, explain and provenance) and tabs B (components,
   the waterfall, the histogram, the factor heat map) and C (the ladder, the what-if side by side, explain). One view feeds the three; every
   figure is the server's answer (/api/mcube/... → /api/v1/cube/...), drawn by cube-core.js. Keyboard first: arrows, Enter, Backspace,
   R C F M H [ ] E P W S X. */
(function () {
  "use strict";
  var root = document.querySelector("[data-mcube]");
  var C = window.DrishtiCube;
  if (!root || !C) { return; }
  var esc = C.esc, money = C.money, compact = C.compact, pct = C.pct, isNum = C.isNum;
  function $(s) { return root.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(root.querySelectorAll(s)); }
  var LABEL = { VAR: "VaR", ES: "ES", COMP_VAR: "Comp VaR", COMP_ES: "Comp ES", INCR_VAR: "Incr VaR", PV: "PV", TRADES: "Trades", SVAR: "Stressed VaR",
    DV01: "DV01", CS01: "CS01", SBM: "SBM" };
  var SEG_MEASURES = ["VAR", "ES", "COMP_VAR", "PV", "DV01", "SBM"];
  var state = { view: null, answer: null, describe: null, versions: null, sel: { r: -1, c: -1 }, crumbs: [], tray: [], compare: null, busy: false };

  function notice(text, bad) {
    var n = $("[data-mc-notice]");
    n.hidden = !text;
    n.textContent = text || "";
    n.classList.toggle("bad", !!bad);
  }
  function first() { return (state.answer && state.answer.view.measures[0]) || "VAR"; }
  function vals(e) { return (e && e.values) || {}; }

  // ------------------------------------------------------------------------------------------------------------------ the request
  function body() {
    if (state.view) { return state.view; }
    if (root.dataset.viewId) { return { id: root.dataset.viewId }; }
    return { text: root.dataset.text };
  }
  function load(view) {
    if (view) { state.view = view; }
    var b = Object.assign({}, body());
    if (!b.id && !b.history) { b.history = 20; }
    $("[data-mc-meta]").textContent = "computing…";
    $("[data-mc-grid]").innerHTML = '<tbody><tr><td class="mc-skel" colspan="8">&nbsp;</td></tr><tr><td class="mc-skel" colspan="8">&nbsp;</td></tr></tbody>';
    return C.json("/api/mcube/query", { method: "POST", body: b }).then(function (a) {
      state.answer = a;
      state.view = a.view;
      state.view.history = 20;
      notice(a.notes ? a.notes.join(" · ") : "");
      render();
      if (!state.describe || state.describe.date !== a.date) { describe(); }
      if (!state.versions) { versions(); } else { slider(); }
      var tab = currentTab();
      if (tab !== "pivot") { showTab(tab); }
    }).catch(function (e) {
      $("[data-mc-meta]").textContent = "";
      $("[data-mc-grid]").innerHTML = "";
      notice(e.code === "DRS-9506" ? "The server is busy: the view did not start. Try again in " + (e.retryAfter || "a few") + " seconds; smaller views keep working."
        : e.message, true);
    });
  }
  function describe() {
    C.json("/api/mcube/describe?" + new URLSearchParams({ scope: state.view.scope, date: state.answer.date })).then(function (d) {
      state.describe = d;
      segments();
    }).catch(function () { /* the chips keep the view's own words */ });
  }
  function versions() {
    C.json("/api/mcube/versions").then(function (v) { state.versions = v; slider(); }).catch(function () { $("[data-mc-slider]").innerHTML = ""; });
  }

  // ------------------------------------------------------------------------------------------------------------------- rendering
  function render() {
    var a = state.answer;
    $("[data-mc-text]").textContent = a.text;
    $("[data-mc-input]").value = a.text;
    $("[data-mc-meta]").textContent = [a.canonical, a.date, a.headline && a.headline.currency, a.cellCount + " cells", a.computeMs + " ms",
      a.fromCache + " from the cache"].filter(Boolean).join(" · ");
    tiles();
    chips();
    segments();
    grid();
    state.sel = { r: -1, c: -1 };
    selectCell(-1, -1);
  }

  function tile(label, value, sub, extra) {
    return '<div class="mc-tile"><span class="mc-tl">' + esc(label) + '</span><span class="mc-tv mono">' + value + "</span>" + (extra || "")
      + '<span class="mc-ts">' + sub + "</span></div>";
  }
  function tiles() {
    var a = state.answer, h = a.headline || {}, d = a.diversification || {}, m = d.VAR ? "VAR" : Object.keys(d)[0];
    var div = m && d[m] ? (isNum(d[m].totalAcrossRows) ? d[m].totalAcrossRows : d[m].totalAcrossColumns) : null;
    var batches = (h.batches || []).join(", ");
    var intraday = h.intraday && h.intraday.batchCount ? " + " + h.intraday.batchCount + " intraday" : "";
    $("[data-mc-tiles]").innerHTML = [
      tile("VaR " + pct(h.confidence, 1) + " " + (h.lens || "") + " · " + a.canonical, money(h.var), (h.k ? h.k + "th worst of " + h.scenarios : "") + " · " + esc(h.currency || "")),
      tile("ES " + pct(h.esConfidence, 1), money(h.es), "mean of the " + (h.esCount || "?") + " worst"),
      tile("Limit use", isNum(h.limitUse) ? pct(h.limitUse) : "—", isNum(h.limit) ? "of " + money(h.limit) : "no VaR limit on this scope", C.limitBar(h.limitUse)),
      tile("Σ " + (a.rowDimensions[0] || "rows").toLowerCase() + " − total", money(div), "diversification" + (m ? " (" + (LABEL[m] || m) + ")" : "")),
      tile("Stressed VaR", "—", h.stressedOpen ? "set Stressed to see it per cell" : "no stress set open on " + esc(a.date)),
      tile("Data", esc(batches || "—") + esc(intraday), "data version " + esc(h.dataVersion || "?") + " · " + (h.trades || 0).toLocaleString("en-US") + " trades")
    ].join("");
  }

  function chips() {
    var v = state.view;
    $('[data-mc-dim-label="rows"]').textContent = v.rows.join(" › ") || "none";
    $('[data-mc-dim-label="columns"]').textContent = v.columns.join(" › ") || "none";
    var f = Object.keys(v.filters || {});
    $('[data-mc-dim-label="filters"]').textContent = f.length ? f.map(function (k) { return k + "=" + v.filters[k].join("|"); }).join(", ") : "all";
  }

  function segments() {
    var v = state.view;
    if (!v) { return; }
    var offered = state.describe ? state.describe.measures.map(function (m) { return m.id; }) : SEG_MEASURES;
    $('[data-mc-seg="measure"]').innerHTML = SEG_MEASURES.map(function (m) {
      var on = offered.indexOf(m) >= 0;
      return '<button type="button" data-v="' + m + '"' + (on ? "" : ' disabled title="its engine is off here"') + ">" + (LABEL[m] || m) + "</button>";
    }).join("");
    var l = v.lens || {};
    var hz = l.horizon === "10d" ? (l.method === "sum" ? "10d-sum" : "10d") : "1d";
    mark("measure", v.measures[0]);
    mark("horizon", hz);
    mark("confidence", l.confidence ? String(Number(l.confidence) > 1 ? Number(l.confidence) / 100 : l.confidence) : "0.99");
    mark("set", l.set || "normal");
    mark("ccy", (l.ccy || "USD").toUpperCase());
  }
  function mark(seg, v) {
    $$('[data-mc-seg="' + seg + '"] button').forEach(function (b) { b.setAttribute("aria-pressed", String(b.dataset.v === String(v))); });
  }

  function axisLabel(e) { return (e.labels || []).join(" › ") || e.key || "—"; }

  function grid() {
    var a = state.answer, m = first(), rows = a.rows, cols = a.columns;
    var byCell = {};
    a.cells.forEach(function (c) { byCell[c.r + "|" + c.c] = c; });
    var anyLimit = rows.some(function (r) { return isNum(vals(r).limitUse); });
    $("[data-mc-pivot-title]").innerHTML = esc((LABEL[m] || m) + " by " + (a.rowDimensions.join(" › ") || "—") + (a.columnDimensions.length ? " × "
      + a.columnDimensions.join(" › ") : "")) + ' <small class="muted">each cell its own vector re-aggregated · sparkline: the open dates · bar: limit use</small>';
    var h = '<thead><tr><th class="l">' + esc(a.rowDimensions.join(" › ") || "") + "</th>";
    cols.forEach(function (c, i) { h += '<th data-sort="' + i + '">' + esc(axisLabel(c)) + "</th>"; });
    h += '<th class="tot">' + (cols.length ? esc(a.rowDimensions[0] || "") + " " + esc(LABEL[m] || m) : esc(LABEL[m] || m)) + "</th>" + (anyLimit ? "<th>Limit</th>" : "") + "</tr></thead><tbody>";
    rows.forEach(function (r, ri) {
      h += '<tr><th class="l rowh" scope="row"><a href="#" data-drill="' + ri + '">' + esc(axisLabel(r)) + "</a></th>";
      cols.forEach(function (c, ci) { h += cellTd(byCell[ri + "|" + ci], ri, ci, m, ""); });
      h += cellTd(r, ri, -1, m, "tot");
      if (anyLimit) {
        var u = vals(r).limitUse;
        h += '<td class="mc-limit">' + (isNum(u) ? '<span class="mono">' + pct(u, 0) + "</span>" + C.limitBar(u) : '<span class="muted">·</span>') + "</td>";
      }
      h += "</tr>";
    });
    h += '</tbody><tfoot><tr class="mc-total"><th class="l" scope="row">' + esc(a.canonical) + " (re-aggregated)</th>";
    cols.forEach(function (c, ci) { h += cellTd(c, -1, ci, m, "tot"); });
    h += cellTd(a.total, -1, -1, m, "tot grand");
    if (anyLimit) { h += '<td class="mc-limit">' + (isNum(vals(a.total).limitUse) ? '<span class="mono">' + pct(vals(a.total).limitUse) + "</span>" + C.limitBar(vals(a.total).limitUse) : "") + "</td>"; }
    h += "</tr>";
    var d = (a.diversification || {})[m];
    if (d && rows.length) {
      h += '<tr class="mc-div"><th class="l" scope="row">Σ cells − total</th>';
      cols.forEach(function (c, ci) {
        var s = 0, any = false;
        rows.forEach(function (r, ri) { var x = vals(byCell[ri + "|" + ci])[m]; if (isNum(x)) { s += x; any = true; } });
        h += '<td class="mono">' + (any && isNum(vals(c)[m]) ? money(s - vals(c)[m]) : "") + "</td>";
      });
      h += '<td class="mono">' + money(d.totalAcrossRows) + "</td>" + (anyLimit ? "<td></td>" : "") + "</tr>";
    }
    h += "</tfoot>";
    $("[data-mc-grid]").innerHTML = h;
    $("[data-mc-crumbs]").innerHTML = state.crumbs.map(function (v, i) {
      return '<a href="#" data-crumb="' + i + '">' + esc(v.text || v.scope) + "</a>";
    }).join(" › ") + (state.crumbs.length ? " › " : "") + "<b>" + esc(a.text) + "</b>";
  }

  function cellTd(e, r, c, m, cls) {
    if (!e) { return '<td class="mc-dot muted" aria-label="no member here">·</td>'; }
    var v = vals(e)[m], note = e.notes && e.notes[m];
    return '<td class="mc-cell ' + cls + '" tabindex="-1" data-r="' + r + '" data-c="' + c + '"' + (note ? ' title="' + esc(note) + '"' : "") + '><span class="mono">'
      + (isNum(v) ? money(v) : note ? "n/a" : "—") + "</span>" + (e.spark ? C.spark(e.spark, 70, 16, cls.indexOf("tot") >= 0 ? "amber" : "") : "") + "</td>";
  }

  function entry(r, c) {
    var a = state.answer;
    if (r < 0 && c < 0) { return a.total; }
    if (c < 0) { return a.rows[r]; }
    if (r < 0) { return a.columns[c]; }
    for (var i = 0; i < a.cells.length; i++) { if (a.cells[i].r === r && a.cells[i].c === c) { return a.cells[i]; } }
    return null;
  }
  function cellName(r, c) {
    var a = state.answer, parts = [];
    parts.push(r >= 0 ? axisLabel(a.rows[r]) : a.canonical);
    if (c >= 0) { parts.push(axisLabel(a.columns[c])); }
    return parts.join(" × ");
  }

  // ------------------------------------------------------------------------------------------------------------------ the right rail
  function selectCell(r, c) {
    var e = entry(r, c);
    if (!e) { return; }
    state.sel = { r: r, c: c };
    $$(".mc-cell.sel").forEach(function (x) { x.classList.remove("sel"); });
    var td = $('.mc-cell[data-r="' + r + '"][data-c="' + c + '"]');
    if (td) { td.classList.add("sel"); td.focus({ preventScroll: true }); }
    $("[data-mc-tail-title]").innerHTML = esc(cellName(r, c)) + ' <small class="muted">tail</small>';
    $("[data-mc-tail]").innerHTML = '<div class="mc-skel tall"></div>';
    var a = state.answer;
    C.json("/api/mcube/cell", { method: "POST", body: { view: state.view, row: r >= 0 ? a.rows[r].key : "", column: c >= 0 ? a.columns[c].key : "" } })
      .then(function (x) {
        state.cell = x;
        $("[data-mc-tail]").innerHTML = C.histogram(x.detail, 340, 150) + '<p class="muted small">VaR scenario ' + esc(x.detail.varDate || "?") + " · "
          + (x.detail.trades || 0) + " trades in " + (x.detail.positions || 0) + " positions · hover a bin: its scenarios</p>";
      }).catch(function (err) { $("[data-mc-tail]").innerHTML = '<p class="bad">' + esc(err.message) + "</p>"; });
    provenance(r, c);
    explain("[data-mc-explain]", "[data-mc-explain-dates]", e.scope);
  }

  function provenance(r, c) {
    var a = state.answer, e = entry(r, c), p = a.provenanceShared || {};
    var rows = [["cell", cellName(r, c)], ["value", money(vals(e)[first()]) + " " + (LABEL[first()] || first())], ["business date", p.businessDate],
      ["data version", p.dataVersion], ["batches", (p.batches || []).join(", ")], ["scenario set", (p.scenarioSet || "") + " · " + (p.window || "")],
      ["method", p.method], ["members", (e.members || 0) + " trades"], ["scope", e.scope || "no scope text names this cell"],
      ["shards", typeof p.shards === "string" ? p.shards : JSON.stringify(p.shards)], ["computed", p.computeMs + " ms, " + p.fromCache + " of " + p.sets + " from the cache"],
      ["provenance id", a.provenance + " · " + r + "|" + c]];
    $("[data-mc-prov]").innerHTML = '<dl class="mc-dl">' + rows.map(function (x) {
      return "<dt>" + esc(x[0]) + '</dt><dd class="mono">' + esc(x[1] == null ? "—" : x[1]) + "</dd>";
    }).join("") + '</dl><p class="muted small">Links: <a href="/smat?scope=' + encodeURIComponent(e.scope || "") + '">SMAT</a> · <a href="/var?scope='
      + encodeURIComponent(e.scope || "") + '">VaR explorer</a> · API <span class="mono">GET /api/v1/cube/provenance/' + esc(a.provenance) + "?cell=" + r + "|" + c + "</span></p>";
  }

  function explain(target, datesEl, scope) {
    if (!scope) { $(target).innerHTML = '<p class="muted">No scope text names this cell: explain asks the engine of a scope.</p>'; return; }
    var q = new URLSearchParams({ scope: scope });
    if (state.compare) { q.set("from", state.compare); }
    if (state.view.date) { q.set("to", state.view.date); }
    $(target).innerHTML = '<div class="mc-skel"></div>';
    C.json("/api/mcube/explain?" + q).then(function (x) {
      $(datesEl).textContent = x.from + " → " + x.to;
      var h = '<table class="mc-mini"><tr><td class="l">VaR ' + esc(x.from) + (x.official && x.official.captured ? " (official)" : "") + '</td><td class="mono">' + money(x.varFrom) + "</td></tr>";
      (x.lines || []).forEach(function (l, i) {
        h += '<tr><td class="l">' + (i + 1) + " " + esc(l.label) + '</td><td class="mono ' + (l.effect > 0 ? "up" : l.effect < 0 ? "down" : "") + '">'
          + (l.effect > 0 ? "+" : "") + money(l.effect) + "</td></tr>";
      });
      h += '<tr class="tot"><td class="l">VaR ' + esc(x.to) + '</td><td class="mono">' + money(x.varTo) + "</td></tr></table>";
      h += '<p class="muted small">' + (x.addsUp ? "The exact steps add up to the change." : "The steps do not add up: see the engine's warnings.")
        + " " + esc(x.factorOverlay && x.factorOverlay.note || "") + "</p>";
      $(target).innerHTML = h;
    }).catch(function (e) { $(target).innerHTML = '<p class="muted">' + esc(e.message) + "</p>"; });
  }

  // ---------------------------------------------------------------------------------------------------------------- the slider
  function slider() {
    var v = state.versions, a = state.answer;
    if (!v || !a) { return; }
    var stops = v.dates || [];
    if (!stops.length) { $("[data-mc-slider]").innerHTML = ""; return; }
    var cur = a.date;
    $("[data-mc-slider]").innerHTML = '<span class="mc-track">' + stops.map(function (s) {
      var cls = (s.date === cur ? " asof" : "") + (s.date === state.compare ? " cmp" : "") + (s.official ? " official" : "");
      return '<button type="button" class="mc-stop' + cls + '" data-date="' + esc(s.date) + '" title="' + esc(s.date + (s.official ? " · official" : "")
        + (s.batches ? " · " + s.batches.join(", ") : "") + (s.open ? " · open" : " · opened when chosen")) + '">' + esc(s.date.slice(5)) + (s.official ? "·o" : "") + "</button>";
    }).join("") + '</span><span class="mc-asof mono">as of ' + esc(cur) + (state.compare ? " · compare " + esc(state.compare) : "")
      + ' · click a date: as of; shift-click: compare; [ and ] step</span>';
  }
  function step(d) {
    var stops = ((state.versions || {}).dates || []).map(function (s) { return s.date; });
    var i = stops.indexOf(state.answer.date) + d;
    if (i >= 0 && i < stops.length) { goDate(stops[i]); }
  }
  function goDate(date) {
    var v = Object.assign({}, state.view, { date: date });
    load(v);
  }

  // ------------------------------------------------------------------------------------------------------------------ the tabs
  function currentTab() { var t = $('.mc-tab[aria-selected="true"]'); return t ? t.dataset.mcTab : "pivot"; }
  function showTab(t) {
    $$(".mc-tab").forEach(function (b) { b.setAttribute("aria-selected", String(b.dataset.mcTab === t)); });
    $$("[data-mc-pane]").forEach(function (p) { p.hidden = p.dataset.mcPane !== t; });
    if (!state.answer) { return; }
    if (t === "tail") { tabB(); }
    if (t === "sensitivities") { tabC(); }
  }

  function tabB() {
    var v = Object.assign({}, state.view, { columns: [], measures: ["COMP_VAR", "VAR"], history: 0 });
    $("[data-mc-comp]").innerHTML = '<tr><td class="mc-skel">&nbsp;</td></tr>';
    C.json("/api/mcube/query", { method: "POST", body: v }).then(function (a) {
      var total = vals(a.total).VAR;
      var rows = a.rows.slice().sort(function (x, y) { return (vals(y).COMP_VAR || 0) - (vals(x).COMP_VAR || 0); });
      $("[data-mc-comp]").innerHTML = '<thead><tr><th class="l">' + esc(a.rowDimensions.join(" › ")) + "</th><th>Comp VaR</th><th>% of VaR</th><th>Standalone VaR</th></tr></thead><tbody>"
        + rows.map(function (r) {
          var cv = vals(r).COMP_VAR;
          return '<tr><td class="l">' + esc(axisLabel(r)) + '</td><td class="mono ' + (cv < 0 ? "down" : "") + '">' + money(cv) + '</td><td class="mono">'
            + pct(total ? cv / total : null) + '</td><td class="mono">' + money(vals(r).VAR) + "</td></tr>";
        }).join("") + '</tbody><tfoot><tr class="mc-total"><td class="l">' + esc(a.canonical) + '</td><td class="mono">' + money(vals(a.total).COMP_VAR)
        + '</td><td class="mono">100.0%</td><td class="mono">' + money(total) + "</td></tr></tfoot>";
      $("[data-mc-waterfall]").innerHTML = C.waterfall(rows.map(function (r) { return { key: r.key, label: axisLabel(r), value: vals(r).COMP_VAR }; }), total);
    }).catch(function (e) { $("[data-mc-comp]").innerHTML = '<tr><td class="bad">' + esc(e.message) + "</td></tr>"; });
    C.json("/api/mcube/cell", { method: "POST", body: { view: state.view, row: "", column: "" } }).then(function (x) {
      $("[data-mc-hist-title]").textContent = state.answer.canonical + " · " + x.detail.scenarios + " scenario P&Ls · V and E marked";
      $("[data-mc-hist]").innerHTML = C.histogram(x.detail, 520, 170);
    }).catch(function (e) { $("[data-mc-hist]").innerHTML = '<p class="muted">' + esc(e.message) + "</p>"; });
    sens($("[data-mc-heat]"), { by: "QUALIFIER,TENOR" }, heatTable);
  }

  function sens(el, extra, draw) {
    var q = new URLSearchParams(Object.assign({ scope: state.view.scope }, extra));
    if (state.view.date) { q.set("asOf", state.view.date); }
    el.innerHTML = '<div class="mc-skel tall"></div>';
    C.json("/api/mcube/sensitivities?" + q).then(function (s) { el.innerHTML = draw(s); }).catch(function (e) {
      el.innerHTML = '<p class="muted">' + esc(e.code === "DRS-5603" ? "The sensitivities engine is off on this server." : e.message) + "</p>";
    });
  }
  function heatTable(s) {
    if (!s.rows.length) { return '<p class="muted">No sensitivities of the scope on ' + esc(s.date) + " (GIRR delta).</p>"; }
    var full = 0;
    s.rows.forEach(function (r) { r.cells.forEach(function (c) { if (isNum(c.net)) { full = Math.max(full, Math.abs(c.net)); } }); });
    return '<table class="mc-grid mc-heat"><thead><tr><th class="l">' + esc(s.by[0]) + " \\ " + esc(s.by[1]) + "</th>" + s.columns.map(function (c) { return "<th>" + esc(c) + "</th>"; }).join("")
      + "</tr></thead><tbody>" + s.rows.map(function (r) {
        return '<tr><th class="l">' + esc(r.key) + "</th>" + r.cells.map(function (c) {
          return '<td class="mono" style="' + C.heat(c.net, full) + '" title="' + (c.positions || 0) + ' positions">' + (isNum(c.net) ? compact(c.net) : "·") + "</td>";
        }).join("") + "</tr>";
      }).join("") + '</tbody></table><p class="muted small">' + esc(s.currency) + " per bp · green long, red short, every cell printed · " + s.elements
      + " elements of " + s.records + " records · batches " + esc((s.batches || []).join(", ")) + "</p>";
  }

  function tabC() {
    var pick = $("[data-mc-grid-pick]");
    sens($("[data-mc-ladder]"), { grid: pick.value || "BANK-IR-TENOR" }, function (s) {
      if (!pick.options.length) {
        pick.innerHTML = (s.grids || []).map(function (g) { return "<option" + (g === s.grid ? " selected" : "") + ">" + esc(g) + "</option>"; }).join("");
      }
      if (!s.rows.length) { return '<p class="muted">No GIRR delta of the scope on ' + esc(s.date) + ".</p>"; }
      var scale = 0;
      s.rows.forEach(function (r) { r.cells.forEach(function (c) { scale = Math.max(scale, Math.abs(c.net || 0), c.limit || 0); }); });
      return '<table class="mc-grid mc-ladder"><thead><tr><th class="l">DV01 (' + esc(s.grid) + ")</th>" + s.columns.map(function (c) { return "<th>" + esc(c) + "</th>"; }).join("")
        + "<th>Net</th></tr></thead><tbody>" + s.rows.map(function (r) {
          return '<tr><th class="l">' + esc(r.key) + "</th>" + r.cells.map(function (c) {
            if (!isNum(c.net)) { return '<td class="muted">·</td>'; }
            var badge = c.status === "breach" ? '<span class="mc-badge bad">breach</span>' : c.status === "near" ? '<span class="mc-badge warn">near</span>' : "";
            return '<td><span class="mono">' + compact(c.net) + (isNum(c.use) ? " · " + pct(c.use, 0) : "") + "</span>" + badge + C.ladderBar(c.net, c.limit, c.status, scale) + "</td>";
          }).join("") + '<td class="mono">' + compact(r.net) + "</td></tr>";
        }).join("") + '</tbody></table><p class="muted small">' + esc(s.limits) + " · centre zero: long right, short left · " + s.elements + " elements</p>";
    });
    whatIf();
    var e = entry(state.sel.r, -1) || state.answer.total;
    explain("[data-mc-explain2]", "[data-mc-explain2-dates]", e.scope);
  }

  function whatIf() {
    $("[data-mc-tray-items]").textContent = state.tray.map(function (t) { return t.copyOf + " × " + t.factor; }).join(" · ");
    if (!state.tray.length) { return; }
    $("[data-mc-whatif]").innerHTML = '<div class="mc-skel tall"></div>';
    C.json("/api/mcube/what-if", { method: "POST", body: { view: state.view, items: state.tray } }).then(function (w) {
      $("[data-mc-whatif]").innerHTML = '<table class="mc-grid"><thead><tr><th class="l">Scope</th><th>Before</th><th>After</th><th>Incremental</th></tr></thead><tbody>'
        + w.rows.map(function (r) {
          var changed = isNum(r.incremental) && isNum(r.before) && Math.abs(r.incremental) > Math.abs(r.before) * 0.005;
          return '<tr><td class="l">' + esc(r.label) + "</td>" + (r.note ? '<td colspan="3" class="muted">' + esc(r.note) + "</td>" : '<td class="mono">' + money(r.before)
            + '</td><td class="mono' + (changed ? " changed" : "") + '">' + money(r.after) + '</td><td class="mono ' + (r.incremental < 0 ? "down" : r.incremental > 0 ? "up" : "")
            + '">' + (r.incremental > 0 ? "+" : "") + money(r.incremental) + "</td>") + "</tr>";
        }).join("") + '</tbody></table><p class="muted small">' + esc(w.engine) + "</p>";
    }).catch(function (e) { $("[data-mc-whatif]").innerHTML = '<p class="bad">' + esc(e.message) + "</p>"; });
  }

  // ------------------------------------------------------------------------------------------------------------- interactions
  function drill(r, c) {
    var a = state.answer;
    C.json("/api/mcube/cell", { method: "POST", body: { view: state.view, row: r >= 0 ? a.rows[r].key : "", column: c >= 0 ? a.columns[c].key : "" } })
      .then(function (x) {
        if (!x.drill || !x.drill.view) { notice(x.drill && x.drill.note || "the last level"); return; }
        state.crumbs.push(state.view);
        var v = x.drill.view;
        v.columns = state.view.columns.filter(function (d) { return v.rows.indexOf(d) < 0 && !(d in (v.filters || {})); });
        load(v);
      }).catch(function (e) { notice(e.message, true); });
  }
  function back() {
    if (state.crumbs.length) { load(state.crumbs.pop()); }
  }

  function setLens(seg, v) {
    var view = Object.assign({}, state.view), lens = Object.assign({}, view.lens || {});
    if (seg === "measure") {
      view.measures = [v].concat(view.measures.filter(function (m) { return m !== v; })).slice(0, 4);
    } else if (seg === "horizon") {
      delete lens.horizon; delete lens.method;
      if (v !== "1d") { lens.horizon = "10d"; if (v === "10d-sum") { lens.method = "sum"; } }
    } else if (seg === "confidence") {
      lens.confidence = v;
    } else if (seg === "set") {
      if (v === "normal") { delete lens.set; } else { lens.set = v; }
    } else if (seg === "ccy") {
      if (v === "USD") { delete lens.ccy; } else { lens.ccy = v; }
    }
    view.lens = lens;
    load(view);
  }

  function dimPicker(axis) {
    var box = $("[data-mc-dimpick]");
    var dims = state.describe ? state.describe.dimensions : [];
    if (!dims.length) { notice("the dimensions are still loading"); return; }
    var v = state.view;
    var cur = axis === "filters" ? Object.keys(v.filters || {}) : v[axis];
    box.innerHTML = '<span class="mc-dp-title">' + esc(axis === "filters" ? "Filter by" : "Put on " + axis) + ":</span>" + dims.map(function (d) {
      return '<button type="button" class="mc-chip ' + (cur.indexOf(d.id) >= 0 ? "on" : "") + '" data-pick="' + esc(d.id) + '" title="' + esc(d.note) + '">' + esc(d.id) + "</button>";
    }).join("") + (axis === "filters" ? ' <input class="pg-input mono" data-mc-fval placeholder="values: a|b" size="16">' : "")
      + ' <button type="button" class="pg-btn" data-pick="">none</button> <button type="button" class="pg-btn" data-close>close</button>';
    box.hidden = false;
    box.dataset.axis = axis;
  }
  function picked(id) {
    var axis = $("[data-mc-dimpick]").dataset.axis, v = Object.assign({}, state.view);
    v.rows = v.rows.slice(); v.columns = v.columns.slice(); v.filters = Object.assign({}, v.filters || {});
    if (axis === "filters") {
      if (!id) { v.filters = {}; } else {
        var vals2 = ($("[data-mc-fval]").value || "").split("|").map(function (s) { return s.trim(); }).filter(Boolean);
        if (!vals2.length) { notice("type the values to keep (a|b), then pick the dimension"); return; }
        v.filters[id] = vals2;
      }
    } else {
      var other = axis === "rows" ? "columns" : "rows";
      v[other] = v[other].filter(function (d) { return d !== id; });
      v[axis] = id ? [id] : [];
    }
    $("[data-mc-dimpick]").hidden = true;
    state.crumbs = [];
    load(v);
  }

  function save() {
    var name = window.prompt("Save this view as (a name; share it from the list later):", "morning");
    if (!name) { return; }
    var share = window.prompt("Share with (people or role:<role>, comma-separated; blank: private):", "");
    C.json("/api/mcube/views", { method: "POST", body: { name: name, view: state.view, sharedWith: share ? share.split(",").map(function (s) { return s.trim(); }) : [] } })
      .then(function (s) { notice("Saved as " + s.id + ": MCUBE @" + s.id + " opens it, as the reader."); listViews(); })
      .catch(function (e) { notice(e.message, true); });
  }
  function listViews() {
    C.json("/api/mcube/views").then(function (vs) {
      $("[data-mc-views]").innerHTML = '<option value="">Saved views…</option>' + vs.map(function (v) {
        return '<option value="' + esc(v.id) + '">' + esc(v.id + (v.owner ? "" : "")) + "</option>";
      }).join("");
    }).catch(function () { /* none */ });
  }
  function exportAs(format) {
    fetch("/api/mcube/export?format=" + format, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(state.view) })
      .then(function (r) {
        if (!r.ok) { return r.json().then(function (b) { throw new Error((b.code || "") + " " + (b.detail || r.status)); }); }
        var name = (r.headers.get("Content-Disposition") || "").replace(/.*filename="?([^"]+)"?.*/, "$1") || "cube." + format;
        return r.blob().then(function (b) {
          var a = document.createElement("a");
          a.href = URL.createObjectURL(b); a.download = name; document.body.appendChild(a); a.click(); a.remove();
        });
      }).catch(function (e) { notice(e.message, true); });
  }

  root.addEventListener("click", function (ev) {
    var t = ev.target.closest("button, a, td.mc-cell, [data-pick], [data-date]");
    if (!t || !root.contains(t)) { return; }
    if (t.matches("td.mc-cell")) { selectCell(+t.dataset.r, +t.dataset.c); return; }
    if (t.dataset.drill != null) { ev.preventDefault(); drill(+t.dataset.drill, -1); return; }
    if (t.dataset.crumb != null) { ev.preventDefault(); var i = +t.dataset.crumb, v = state.crumbs[i]; state.crumbs = state.crumbs.slice(0, i); load(v); return; }
    if (t.dataset.mcTab) { showTab(t.dataset.mcTab); return; }
    if (t.dataset.mcDim) { dimPicker(t.dataset.mcDim); return; }
    if (t.hasAttribute("data-close")) { $("[data-mc-dimpick]").hidden = true; return; }
    if (t.dataset.pick != null) { picked(t.dataset.pick); return; }
    if (t.dataset.date) {
      if (ev.shiftKey) { state.compare = t.dataset.date; slider(); } else { goDate(t.dataset.date); }
      return;
    }
    if (t.dataset.mcExport) { exportAs(t.dataset.mcExport); return; }
    if (t.hasAttribute("data-mc-save")) { save(); return; }
    var seg = t.closest("[data-mc-seg]");
    if (seg && t.dataset.v && !t.disabled) { setLens(seg.dataset.mcSeg, t.dataset.v); }
  });
  root.addEventListener("dblclick", function (ev) {
    var t = ev.target.closest("td.mc-cell");
    if (t) { drill(+t.dataset.r, +t.dataset.c); }
  });
  root.addEventListener("mouseover", function (ev) {
    var t = ev.target.closest("td.mc-cell");
    var pop = $("[data-mc-pop]");
    if (!t) { pop.hidden = true; return; }
    var e = entry(+t.dataset.r, +t.dataset.c);
    if (!e) { return; }
    var notes = e.notes ? Object.keys(e.notes).map(function (k) { return k + ": " + e.notes[k]; }).join(" · ") : "";
    pop.innerHTML = "<b>" + esc(cellName(+t.dataset.r, +t.dataset.c)) + "</b><br>" + Object.keys(vals(e)).map(function (k) {
      return esc(LABEL[k] || k) + " " + (k === "limitUse" ? pct(vals(e)[k]) : money(vals(e)[k]));
    }).join(" · ") + "<br>" + (e.members || 0) + " trades · " + esc(e.scope || "no scope text") + (e.cached ? " · from the cache" : "") + (notes ? "<br>" + esc(notes) : "");
    var r = t.getBoundingClientRect(), rr = root.getBoundingClientRect();
    pop.style.left = (r.left - rr.left) + "px";
    pop.style.top = (r.bottom - rr.top + 4) + "px";
    pop.hidden = false;
  });
  $("[data-mc-ask]").addEventListener("submit", function (ev) {
    ev.preventDefault();
    state.crumbs = [];
    state.view = null;
    root.dataset.viewId = "";
    root.dataset.text = $("[data-mc-input]").value.trim();
    load();
  });
  $("[data-mc-views]").addEventListener("change", function (ev) {
    if (!ev.target.value) { return; }
    state.view = null; state.crumbs = [];
    root.dataset.viewId = ev.target.value;
    load();
  });
  $("[data-mc-tray]").addEventListener("submit", function (ev) {
    ev.preventDefault();
    var f = ev.target;
    state.tray.push({ copyOf: f.trade.value.trim(), factor: Number(f.factor.value) || 1 });
    f.trade.value = "";
    whatIf();
  });
  $("[data-mc-grid-pick]").addEventListener("change", function () { tabC(); });

  document.addEventListener("keydown", function (ev) {
    if (ev.target.closest("input, textarea, select") || ev.ctrlKey || ev.metaKey || ev.altKey || !state.answer) { return; }
    var a = state.answer, s = state.sel, k = ev.key;
    var nr = a.rows.length, nc = a.columns.length;
    if (k === "ArrowDown" || k === "ArrowUp" || k === "ArrowLeft" || k === "ArrowRight") {
      var r = s.r, c = s.c;
      if (k === "ArrowDown") { r = r < 0 ? 0 : r + 1 >= nr ? -1 : r + 1; }
      if (k === "ArrowUp") { r = r < 0 ? nr - 1 : r - 1; }
      if (k === "ArrowRight") { c = c < 0 ? (nc ? 0 : -1) : c + 1 >= nc ? -1 : c + 1; }
      if (k === "ArrowLeft") { c = c < 0 ? nc - 1 : c - 1; }
      if (entry(r, c)) { selectCell(r, c); }
      ev.preventDefault();
    } else if (k === "Enter") { drill(s.r, s.c); ev.preventDefault(); }
    else if (k === "Backspace") { back(); ev.preventDefault(); }
    else if (k === "r" || k === "R") { dimPicker("rows"); }
    else if (k === "c" || k === "C") { dimPicker("columns"); }
    else if (k === "f" || k === "F") { dimPicker("filters"); }
    else if (k === "m" || k === "M") {
      var ms = SEG_MEASURES, i = ms.indexOf(first());
      setLens("measure", ms[(i + 1) % ms.length]);
    } else if (k === "h" || k === "H") { setLens("horizon", (state.view.lens || {}).horizon ? "1d" : "10d"); }
    else if (k === "[") { step(-1); } else if (k === "]") { step(1); }
    else if (k === "e" || k === "E") { var e = entry(s.r, s.c); explain("[data-mc-explain]", "[data-mc-explain-dates]", e && e.scope); }
    else if (k === "p" || k === "P") { provenance(s.r, s.c); $("[data-mc-prov]").scrollIntoView({ block: "nearest" }); }
    else if (k === "w" || k === "W") { showTab("sensitivities"); $("[data-mc-tray] input").focus(); ev.preventDefault(); }
    else if (k === "s" || k === "S") { save(); }
    else if (k === "x" || k === "X") { exportAs("csv"); }
  });

  showTab(root.dataset.tab || "pivot");
  listViews();
  load();
})();
