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
/* The pivot screen (terminal/pivot.html; PIVOT_GUIDE.md section 2): drawn by drishti-grid (Drishti Elements). It opens as the View picker
   in front of the compact line (mode views, C + B: Rows · Columns · Filters · Values on one line, a picker behind each), or the compact
   line alone (compact), or the Fields and Build panes (panes, A, behind Customize › and \); a preset, a Sutra's pivot: or the address
   names the mode. The catalogue of the source becomes the grid's fields with their metadata (group, display folder, hidden under More
   fields, the functions valid for each, the hierarchies as one item each: the server's), the wells build the view, and the grid asks the
   server for every answer through its serverPivot hook (/api/pivot/query, every figure computed by the
   drishti-bicompute engine; the console's policy clientAggregation: false, so VaR and ES are never summed in the browser). A cell's
   Explain is its provenance; the screen keeps the scan estimate, compare, slicers, the chart of a selection, export, save and share, and
   the switches (drishti_pivot-switches.js). Nothing here aggregates. */
(function () {
  "use strict";
  var root = document.querySelector("[data-pivot-screen]");
  if (!root) { return; }
  var G = window.Drishti.elements && window.Drishti.elements.grid;
  var $ = function (s) { return root.querySelector(s); };
  var API = "/api/pivot";
  // the aggregations only the engine computes, offered in the wells beside the grid's own (the grid never computes one here)
  var SERVER_FUNCS = [{name: "median", label: "Median"}, {name: "var_samp", label: "Variance"}, {name: "stddev_samp", label: "Standard deviation"}];
  var state = {catalogue: null, answer: null, diff: null, grid: null, selection: [], chart: null, savedView: null, slicers: [], force: false,
    how: null, decorators: [], fields: [], scales: {}, mode: null, viewEntry: null, viewSnap: null, snapNext: false, firstView: null, customFns: []};
  var USER = root.getAttribute("data-user") || "";
  var MODES = ["views", "compact", "panes"];
  // a per-cell figure's words in its value menu (the VaR source's measures: the engine's figure of the cell's own trades)
  var ADDS_UP = ["pv", "trades", "comp_var", "comp_es", "dv01", "cs01"];
  var VAR_NOTE = "Computed per cell by the VaR engine from the cell's own trades: a total is the VaR of its trades, never a sum of the cells (Σ − total = the diversification)";
  var VAR_SUM_NOTE = "Computed per cell by the VaR engine from the cell's own trades; it adds up: a total equals the sum of its parts";
  var sw = function () { return window.Drishti.PivotSwitches || null; };
  // the grid's figure text by the answer's own fields; a source's measure of before units that counts trades reads as a count (the
  // providers' knowledge stays here and in their packs, never in drishti-grid)
  var unitOf = function (m) { return m && (m.unit === null || m.unit === undefined) && /^trades/.test(m.id || "") ? Object.assign({}, m, {unit: "count"}) : m; };
  var fmt = G ? function (v, m) { return G.measureText(v, scaled(unitOf(m))); } : function (v) { return v == null ? "" : String(v); };
  function scaled(m) { var sc = m && state.scales[m.label]; return sc && sc.unit ? Object.assign({}, m, {scale: sc}) : m; }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return {"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]; });
  }

  // the screen's switches (drishti_pivot-switches.js): exact / auto / ≈, progressive, use rollups, live; each is a request option the server answers
  function asked(b) {
    var o = sw() ? sw().options() : {};
    Object.keys(o).forEach(function (k) { b[k] = o[k]; });
    return b;
  }

  function notice(msg) {
    var n = $("[data-pv-notice]");
    n.textContent = msg || "";
    n.hidden = !msg;
  }

  function call(method, path, body, signal) {
    var o = {method: method, headers: {"Accept": "application/json"}, credentials: "same-origin"};
    if (signal) { o.signal = signal; }
    if (body !== undefined) { o.headers["Content-Type"] = "application/json"; o.body = JSON.stringify(body); }
    return fetch(API + path, o).then(function (r) {
      return r.text().then(function (t) {
        var j = {};
        try { j = t ? JSON.parse(t) : {}; } catch (e) { j = {detail: t}; }
        if (!r.ok) { throw new Error((j.code ? j.code + ": " : "") + (j.detail || j.message || r.statusText)); }
        return j;
      });
    });
  }

  // ---- the catalogue as the grid's fields, a view as its wells ---------------------------------------------------------------------

  /** The catalogue's fields and measures as grid columns: the entity's fields, a document's paths by their grain ("Each leg (one row per
      leg)"), the engine's measures (never summed in the browser) and the measures the view names that are none of these (a custom
      function, var(99, 1d)). The engine's own fields (a parent key _p0, an all-elements filter ~all) are not offered. */
  function columnsOf(c, view) {
    var cols = [], ids = {}, levelLabel = {};
    function add(col) { if (!ids[col.id || col.field]) { ids[col.id || col.field] = 1; cols.push(col); } }
    (c.hierarchies || []).forEach(function (h) { (h.levels || []).forEach(function (l) { levelLabel[l.field] = l.label; }); });
    (c.fields || []).forEach(function (f) {
      if (/^_|~/.test(f.name)) { return; }
      var desc = f.description || f.note || "", m = /^path (\S+).*one value per ([\w ]+)/.exec(desc);
      var group = !m ? "Fields" : m[2] === "entity" ? (c.title || c.source) + ": its document" : "Each " + m[2] + " (one row per " + m[2] + ")";
      var num = f.type === "number", fns = Array.isArray(f.functions) ? f.functions : null;
      // the server's metadata: its display folder, hidden (More fields), the functions valid for it (none: the source's values are its measures)
      add({field: f.name, header: levelLabel[f.name] || (f.label ? cap(f.label) : f.name), type: num ? "number" : f.type === "date" ? "date" : "text", group: group, description: desc,
        enableRowGroup: true, enablePivot: true, enableValue: fns ? fns.length > 0 : true,
        aggFunc: fns && fns.length ? (fns.indexOf(num ? "sum" : "count_distinct") >= 0 ? (num ? "sum" : "count_distinct") : fns[0]) : num ? "sum" : "count_distinct",
        additive: num, role: "field", folder: f.folder || null, listHidden: !!f.hidden, funcs: fns || undefined, unit: f.unit || null});
    });
    var mg = c.provider === "var" ? "VaR engine" : "Measures";
    (c.measures || []).forEach(function (m) {
      var perCell = m.perCell === undefined ? m.kind !== "mergeable" : !!m.perCell;
      add({id: "m:" + m.name, field: m.name, header: m.label || m.name, type: "number", group: mg, description: m.description || "", measure: m.name + "()",
        additive: m.kind === "mergeable", enableRowGroup: false, enablePivot: false, enableValue: true, role: "measure", folder: m.folder || null,
        listHidden: !!m.hidden, perCell: perCell, unit: m.unit || null,
        perCellNote: c.provider === "var" ? (ADDS_UP.indexOf(m.name) >= 0 ? VAR_SUM_NOTE : VAR_NOTE) : null});
    });
    state.fields.forEach(add);                        // paths a person added (Path box)
    if (view) {
      view.rows.concat(view.columns).forEach(function (d) { if (!ids[d]) { add({field: d, header: d, group: "Your fields", enableRowGroup: true, enablePivot: true}); } });
      Object.keys(view.filters || {}).forEach(function (d) { if (!ids[d] && d.charAt(0) !== "_") { add({field: d, header: d, group: "Your fields"}); } });
      view.values.forEach(function (x) { if (!valueOf(x, cols)) { add(measureColumn(x)); } });
    }
    return cols;
  }

  function cap(s) { s = String(s || ""); return s.charAt(0).toUpperCase() + s.slice(1); }

  function measureColumn(x) {
    return {id: "x:" + x.measure, field: x.measure, header: x.as || x.measure, type: "number", group: "Your measures", measure: x.measure,
      additive: /^(count|sum)\(/.test(x.measure), enableRowGroup: false, enablePivot: false, enableValue: true, role: "measure", funcs: []};
  }

  /** A view's value ({measure, as}) as a well's value: func(field) over a field, a native measure, else its own measure column. The label
      is kept as it was (as, else the measure's text), so a saved view's references, formats and comments keep their names. */
  function valueOf(x, cols) {
    var label = x.as || x.measure, m = /^(\w+)\(([^()]*)\)$/.exec(x.measure), show = x.showAs ? {showAs: x.showAs} : {};
    var byId = function (id) { return cols.filter(function (c) { return (c.id || c.field) === id; })[0]; };
    var parts = m && m[2] ? m[2].split(",").map(function (a) { return a.trim(); }) : [];
    var col = parts.length ? byId(parts[0]) : null;
    var known = function (fn) { return G.aggFunc(fn) || SERVER_FUNCS.some(function (f) { return f.name === fn; }) || (col && Array.isArray(col.funcs) && col.funcs.indexOf(fn) >= 0); };
    if (col && known(m[1])) {
      var v = {colId: parts[0], aggFunc: m[1], as: label};
      if (parts.length > 1) { v.args = parts.slice(1).map(function (a) { return byId(a) ? a : isNaN(+a) ? a : +a; }); }
      return Object.assign(v, show);
    }
    if (m && !m[2] && byId("m:" + m[1])) { return Object.assign({colId: "m:" + m[1], as: label}, show); }
    if (byId("x:" + x.measure)) { return Object.assign({colId: "x:" + x.measure, as: label}, show); }
    return null;
  }

  function wellsOf(view, cols) {
    var filters = [];
    Object.keys(view.filters || {}).forEach(function (k) {
      if (k.charAt(0) === "_") { return; }             // the engine's own filters (a document view's grain): the server adds them again
      var f = view.filters[k] || {};
      filters.push({colId: k, filter: f.in && f.in.length ? {type: "set", values: f.in.slice()} : f.notIn && f.notIn.length ? {type: "set", excluded: f.notIn.slice()} : null});
    });
    return {rows: view.rows.slice(), columns: view.columns.slice(), values: view.values.map(function (x) { return valueOf(x, cols); }).filter(Boolean), filters: filters};
  }

  var WELL_KEYS = ["rows", "columns", "values", "filters", "expanded", "expandTo", "formats", "slicers", "valueFilters"];
  function extraOf(view) {
    var e = {};
    Object.keys(view).forEach(function (k) { if (WELL_KEYS.indexOf(k) < 0) { e[k] = view[k]; } });
    e.subtotals = view.subtotals || "below";
    return e;
  }

  function rulesOf(formats) {
    return Object.keys(formats || {}).map(function (label) { return {measure: label, type: formats[label].style === "bars" ? "dataBar" : "colourScale"}; });
  }

  /** The view now, in the server's PivotView shape: the grid's wells and open state, the screen's options, formats and slicers. */
  function viewBody() {
    var gr = state.grid;
    if (!gr) { return null; }
    var v = gr.model.pivotView();
    delete v.valueFilters;
    var formats = {};
    (gr.formats || []).forEach(function (r) { if (r.measure) { formats[r.measure] = {style: r.type === "dataBar" ? "bars" : "scale"}; } });
    if (Object.keys(formats).length) { v.formats = formats; }
    if (state.slicers.length) { v.slicers = state.slicers.slice(); }
    v.subtotals = $("[data-pv-subtotals]").value;
    return v;
  }

  // ---- asking the server: the grid's serverPivot hook -------------------------------------------------------------------------------

  function estimate(view, signal) {
    return call("POST", "/estimate", {view: view}, signal).then(function (e) {
      var bar = $("[data-pv-estimate]");
      bar.innerHTML = '<i class="bi bi-speedometer2" aria-hidden="true"></i> ' + esc(e.words)
        + (e.confirm ? ' <button type="button" class="pv-btn primary" data-pv-run>Run</button>' : "");
      bar.hidden = !e.confirm && $("[data-pv-info]").getAttribute("aria-expanded") !== "true";   // behind the i, unless it asks first
      bar.classList.toggle("warn", !!e.confirm);
      return e;
    }).catch(function () { return {}; });
  }

  /** The grid asks (the wells, a + or −, a level, the screen's options all end here): the estimate first, then compare, the progressive
      stream (≈ first, drawn as it comes, then exact) or one query. */
  function ask(req, signal) {
    notice("");
    var view = Object.assign({}, req.view, {formats: undefined});
    if (state.slicers.length) { view.slicers = state.slicers.slice(); }
    $("[data-pv-meta]").textContent = "computing…";
    var force = state.force;
    state.force = false;
    return estimate(view, signal).then(function (e) {
      if (e && e.confirm && !force) { $("[data-pv-meta]").textContent = "large: press Run to compute it"; return state.answer; }
      var cmp = $("[data-pv-compare]").value;
      if (!cmp && sw() && sw().progressiveOn()) {
        state.diff = null;
        var last = null;
        return sw().progressive(asked({view: view}), function (a, how) {
          if (signal && signal.aborted) { return; }
          last = a;
          if (how.phase === "approximate") { show(a, how, true); } else { state.how = how; }
        }).then(function () { return last ? settle(last, state.how || {}) : state.answer; });
      }
      var p = cmp ? call("POST", "/compare", asked({view: view, otherDate: cmp}), signal).then(function (c) { state.diff = c.diff; return c.answer; })
        : call("POST", "/query", asked({view: view}), signal).then(function (a) { state.diff = null; return a; });
      return p.then(function (a) { return settle(a, {}); });
    }).catch(function (err) {
      if (signal && signal.aborted) { throw err; }
      $("[data-pv-meta]").textContent = "";
      notice(err.message);
      throw err;
    });
  }

  /** The screen's bookkeeping of an answer the grid is about to draw (or drew: show). */
  function settle(a, how) {
    a.savedView = a.savedView || state.savedView;
    state.answer = a;
    state.selection = [];
    state.scales = G ? G.answerScales(a) : {};                    // one number format per measure: every figure in its column's unit
    if (state.snapNext) { state.snapNext = false; state.viewSnap = snapshot(viewBody()); }
    var comp = a.computed || {};
    $("[data-pv-meta]").textContent = (a.title || a.source) + " · " + (a.date ? "business date " + a.date : "current") + " · "
      + a.cellCount + " cells · " + comp.sets + " sets (" + comp.fromCache + " from the cache) · " + comp.queries + " queries · " + comp.ms + " ms";
    $("[data-pv-grid-title]").textContent = (a.measures || []).map(function (m) { return m.label; }).join(", ") + " by " + ((a.rowDimensions || []).join(" › ") || "total");
    $("[data-pv-grid-sub]").textContent = "rows " + ((a.rowDimensions || []).join(" › ") || "—") + (a.columnDimensions && a.columnDimensions.length ? " · columns " + a.columnDimensions.join(" › ") : "")
      + " · subtotals " + ((a.view || {}).subtotals || "below") + " · lazy: closed nodes are not computed · " + a.exactness
      + (Object.keys(state.scales).some(function (k) { return state.scales[k].unit; }) ? " · figures in " + Object.keys(state.scales).filter(function (k) { return state.scales[k].unit; })
        .map(function (k) { return k + " " + state.scales[k].unit; }).join(", ") : "");
    if (sw()) { sw().shown(a, how || {}); }      // the computed-as line, the live stream, the comments
    if (how && how.patched) { return a; }
    drawSlicers();
    var after = function () { root.dispatchEvent(new CustomEvent("drishti:pivot-answer", {detail: {answer: a, view: viewBody()}})); };
    window.setTimeout(after, 0);                 // once the grid holds the answer
    return a;
  }

  /** An answer from elsewhere (the progressive stream's ≈ one, a live pivot's answer or patch): drawn by the grid, kept by the screen. */
  function show(a, how, keepAsking) {
    how = how || {};
    settle(a, how);
    if (state.grid) { state.grid.model.showAnswer(a, {patched: !!how.patched, keepAsking: !!keepAsking}); }
  }

  // ---- what a cell carries: the change since the compared date, comments, a lens's marks; a row's breakdown and its diversification ----

  function cellKey(r, c) {
    var m = state.grid.model, x = m.rowAt(r), col = m.shownColumns()[c];
    return x && x.kind === "pivot" && col && !col.rowHeader ? x.entry.key + "|" + col.colKey : null;
  }

  function measureOfLabel(label) { return ((state.answer || {}).measures || []).filter(function (m) { return m.label === label; })[0] || {label: label}; }

  function decorate(ctx) {
    if (ctx.kind !== "pivot" || !ctx.entry) { return null; }
    var e = ctx.entry;
    if (ctx.rowHeader) {
      var bits = [];
      if ((e.kind === "header" || (e.kind === "node" && e.expanded)) && e.by && e.kind !== "others") { bits.push("by " + e.by); }
      // a gap is null when a part or the total has no figure (a capped measure): left out, never a partial sum
      var gaps = Object.keys(e.diversification || {}).filter(function (k) { return e.diversification[k] != null && Math.abs(e.diversification[k]) > 0.005; });
      if (gaps.length && (e.kind === "grand" || e.kind === "total" || e.expanded)) {
        bits.push("diversification " + gaps.map(function (k) { return (gaps.length > 1 ? k + " " : "") + fmt(-e.diversification[k], measureOfLabel(k)); }).join(", "));
      }
      return bits.length ? {after: " · " + bits.join(" · "), title: e.by ? "its children are broken down by " + e.by : null} : null;
    }
    var key = e.key + "|" + ctx.colKey, out = {cls: "", title: "", after: ""};
    var d = state.diff && state.diff[key];
    if (d && d.delta && d.delta[ctx.measure] != null && d.delta[ctx.measure] !== 0) {
      var dv = d.delta[ctx.measure];
      out.after = (dv > 0 ? " +" : " ") + fmt(dv, measureOfLabel(ctx.measure));
      out.cls += " pv-delta-" + (dv > 0 ? "up" : "down");
    }
    if (d && d.status) { out.after += " " + d.status; }
    state.decorators.forEach(function (fn) {
      var x = fn(key, ctx);
      if (!x) { return; }
      if (x.cls) { out.cls += " " + x.cls; }
      if (x.title) { out.title += (out.title ? "\n" : "") + x.title; }
      if (x.after) { out.after = x.after; }
    });
    return out.cls || out.title || out.after ? out : null;
  }

  // ---- slicers: a field's members as buttons, each a filter of the view (the members are the engine's, as the person may see them) ------

  function membersOf(field, others) {
    var v = viewBody() || {};
    var q = {source: v.source, date: v.date, rows: [field], values: [{measure: "count()"}], filters: others || {}, sort: {}};
    q.sort[field] = {by: "label"};
    return call("POST", "/query", {view: q}).then(function (a) {
      return (a.rows || []).filter(function (r) { return r.kind === "node"; }).map(function (r) {
        var c = (a.cells || {})[r.key + "|" + ((a.columns || [])[0] || {}).key];
        return {text: r.value, count: c && c.values ? c.values[(a.measures || [{}])[0].label] : undefined};
      });
    });
  }

  function filtersBut(f) {
    var v = viewBody() || {}, others = {};
    Object.keys(v.filters || {}).forEach(function (k) { if (k !== f) { others[k] = v.filters[k]; } });
    return others;
  }

  function drawSlicers() {
    var box = $("[data-pv-slicers]"), list = state.slicers;
    var pick = $("[data-pv-slicer-add]");
    if (pick && state.catalogue) {                                       // drishti-inputs' Select (<drishti-select>)
      pick.component.setOptions([{ value: "", label: "Add a slicer…" }].concat((state.catalogue.fields || []).filter(function (f) { return f.type !== "number" && !/^_|~/.test(f.name) && list.indexOf(f.name) < 0; })
        .map(function (f) { return { value: f.name, label: f.label || f.name }; })), "");
    }
    box.hidden = !list.length;
    if (!list.length) { box.innerHTML = ""; return; }
    box.innerHTML = list.map(function (f, i) { return '<div class="pv-slicer" data-slicer="' + i + '"><b>' + esc(f) + '</b><span class="pv-sub">…</span></div>'; }).join("");
    var v = viewBody() || {};
    list.forEach(function (f, i) {
      membersOf(f, filtersBut(f)).then(function (members) {
        var kept = ((v.filters || {})[f] || {}).in || [];
        var el = box.querySelector('[data-slicer="' + i + '"]');
        if (!el) { return; }
        el.innerHTML = '<b>' + esc(f) + '</b><button type="button" class="pv-x" data-sl-remove="' + esc(f) + '" aria-label="Remove the slicer ' + esc(f) + '">×</button>'
          + '<button type="button" class="pv-sl" data-sl-all="' + esc(f) + '" aria-pressed="' + (!kept.length) + '">All</button>'
          + members.slice(0, 60).map(function (m) {
            return '<button type="button" class="pv-sl" data-sl-field="' + esc(f) + '" data-sl-value="' + esc(m.text) + '" aria-pressed="' + (kept.indexOf(m.text) >= 0) + '">' + esc(m.text) + '</button>';
          }).join("") + (members.length > 60 ? '<span class="pv-sub">' + (members.length - 60) + ' more: use a filter</span>' : "");
      }).catch(function (e) {
        var el = box.querySelector('[data-slicer="' + i + '"]');
        if (el) { el.querySelector(".pv-sub").textContent = e.message; }
      });
    });
  }

  /** A slicer's choice is a member filter of the field: the Filters well holds it, so the grid asks again. */
  function setMembers(field, members) {
    var w = state.grid.model.wells;
    if (members && members.length) { w.setFilter(field, {type: "set", values: members}); } else if (w.has("filters", field)) { w.remove("filters", field); }
  }

  // ---- provenance: a cell's Explain ----------------------------------------------------------------------------------------------

  function coordsOf(key) {
    var cell = ((state.answer || {}).cells || {})[key];
    return cell ? cell.coords : {};
  }

  function reference(key, m) {
    var id = state.answer.savedView;
    var c = coordsOf(key);
    var parts = Object.keys(c).map(function (k) { return k + "=" + c[k]; });
    return id ? "pivot://" + id + "/" + m.label + "[" + parts.join(", ") + "]" : "";
  }

  /** The grid's Explain hook: the server's provenance of the cell, with its rows, its reference and (switches) its comments. */
  function explain(ctx) {
    var key = cellKey(ctx.row, ctx.col);
    if (!key || !ctx.measure) { return "Explain shows how a figure of the pivot was computed: select a figure."; }
    var m = measureOfLabel(ctx.measure);
    return call("GET", "/provenance/" + encodeURIComponent(state.answer.provenance) + "?cell=" + encodeURIComponent(key)).then(function (p) {
      var cell = p.cell || {};
      var v = cell.values ? cell.values[m.label] : null;
      var fn = (p.functions || []).filter(function (f) { return f.label === m.label; })[0] || {};
      var ref = reference(key, m);
      var coords = cell.coords || {};
      var pop = document.createElement("div");
      pop.className = "pv-pop";
      pop.setAttribute("data-pv-pop", "");
      pop.setAttribute("data-cell", key);
      pop.innerHTML = '<div class="pv-pop-h"><b>' + esc(m.label) + ' · ' + esc(Object.keys(coords).map(function (k) { return coords[k]; }).join(" · ") || "grand total")
        + ' = ' + esc(fmt(v, m)) + '</b> <span class="pv-tag">' + (m.exact === false ? "≈" : "exact") + '</span> <span class="pv-tag">' + esc(fn.kind || m.kind) + '</span></div><dl class="pv-kv">'
        + '<dt>How</dt><dd>' + esc(fn.function || m.id) + ': ' + esc(fn.how || "") + '</dd>'
        + '<dt>Members</dt><dd>' + esc(cell.members) + ' rows</dd>'
        + (cell.scope ? '<dt>Scope</dt><dd class="mono">' + esc(cell.scope) + '</dd>' : '<dt>Where</dt><dd class="mono">' + esc(Object.keys(coords).map(function (k) { return k + " = '" + coords[k] + "'"; }).join(" AND ") || "every row of the view") + '</dd>')
        + '<dt>Data</dt><dd>' + esc(p.source) + ' · ' + esc(p.date || "current") + ' · version ' + esc(p.version) + '</dd>'
        + '<dt>Filters</dt><dd>' + esc(p.filters) + '</dd>'
        + '<dt>Computed</dt><dd>' + esc(p.provider) + ' · ' + esc(p.queries) + ' queries · ' + esc(p.fromCache) + ' of ' + esc(p.sets) + ' sets from the cache · ' + esc(p.computeMs) + ' ms</dd>'
        + (cell.notes && cell.notes[m.label] ? '<dt>Note</dt><dd>' + esc(cell.notes[m.label]) + '</dd>' : "")
        + (ref ? '<dt>Reference</dt><dd class="mono">' + esc(ref) + '</dd>' : '<dt>Reference</dt><dd>save the view to name its cells</dd>')
        + '</dl><div class="pv-pop-b"><button type="button" class="pv-btn" data-pop-rows>Rows (' + esc(cell.members) + ')</button>'
        + (cell.scope && p.provider === "var" ? ' <a class="pv-btn" href="/var?scope=' + encodeURIComponent(cell.scope) + '">Ask the VaR engine</a>' : "")
        + (ref ? ' <button type="button" class="pv-btn" data-pop-copy>Copy reference</button>' : "") + '</div><div data-pop-rowsbox></div>';
      if (sw()) { sw().popover(pop, key, m, cell); }   // the cell's comments, and how the answer was computed
      pop.querySelector("[data-pop-rows]").onclick = function () { rows(key, pop.querySelector("[data-pop-rowsbox]")); };
      var cp = pop.querySelector("[data-pop-copy]");
      if (cp) { cp.onclick = function () { if (navigator.clipboard) { navigator.clipboard.writeText(ref); } cp.textContent = "Copied"; }; }
      return pop;
    });
  }

  function rowsOf(key, limit) {
    return call("POST", "/rows", {view: viewBody(), coords: coordsOf(key), limit: limit || 50});
  }

  function rows(key, box) {
    rowsOf(key).then(function (r) {
      var h = ['<p class="pv-sub">' + r.rows.length + " of " + r.total + " rows" + (r.note ? " · " + esc(r.note) : "") + '</p><div class="pv-rows"><table class="pv-rows-t"><thead><tr>'];
      r.columns.forEach(function (c) { h.push("<th>" + esc(c) + "</th>"); });
      h.push("</tr></thead><tbody>");
      r.rows.forEach(function (row) { h.push("<tr>" + row.map(function (x) { return "<td>" + esc(x) + "</td>"; }).join("") + "</tr>"); });
      h.push("</tbody></table></div>");
      if (box) { box.innerHTML = h.join(""); }
    }).catch(function (e) { notice(e.message); });
  }

  /** The grid's drill-through hook (the rows behind a cell, its context menu): the server's rows, as objects. */
  function drillThrough(ctx) {
    var key = cellKey(ctx.row, ctx.col);
    if (!key) { return Promise.resolve([]); }
    return rowsOf(key, 200).then(function (r) {
      return r.rows.map(function (row) { var o = {}; r.columns.forEach(function (c, i) { o[c] = row[i]; }); return o; });
    });
  }

  // ---- the chart of a selection -------------------------------------------------------------------------------------------------

  function chart() {
    var a = state.answer;
    if (!a) { return; }
    var sel = state.selection.length ? state.selection : [];
    var m = sel.length ? measureOfLabel(sel[0].measure) : a.measures[0];
    var colKey = sel.length ? sel[0].colKey : a.columns[a.columns.length - 1].key;
    // the rows charted: the selected ones, else the top level's members
    var picked = sel.length > 1 ? sel.map(function (s) { return s.entry; }) : a.rows.filter(function (r) { return r.depth === 1 && r.kind !== "header"; });
    var holistic = m.kind === "holistic" && !m.plainSum;
    var names = [];
    var values = [];
    picked.forEach(function (r) {
      var c = a.cells[r.key + "|" + colKey];
      var v = c && c.values ? c.values[m.label] : null;
      if (v != null) { names.push(r.kind === "total" ? r.label.replace(/ total$/, "") : r.label); values.push(v); }
    });
    var grandRow = a.rows.filter(function (r) { return r.kind === "grand"; })[0] || a.rows[a.rows.length - 1];
    var grand = a.cells[grandRow.key + "|" + colKey];
    var total = grand && grand.values ? grand.values[m.label] : null;
    // the engine's own gap of this column (Σ parts − total, PivotAnswer.diversification): drawn as total − Σ parts, never added up here
    var gap = ((grandRow.diversificationByColumn || {})[colKey] || {})[m.label];
    var note;
    if (holistic && total != null && gap != null && sel.length <= 1) {
      names = names.concat(["Diversification", "Total"]);
      values = values.concat([-gap, total]);
      note = m.label + " does not add up: the total is its own figure from its own members, so the parts stand beside it and the "
        + "diversification (total − Σ parts = " + fmt(-gap, m) + ", the engine's) is drawn as its own bar, never stacked.";
    } else {
      note = m.label + (m.plainSum ? " adds up: the bars sum to the total." : " by member; each figure is the cell's own.");
    }
    $("[data-pv-chartcard]").hidden = false;
    $("[data-pv-chart-title]").textContent = "Chart of the selection: " + m.label + " by " + ((a.rowDimensions || [])[0] || "total");
    $("[data-pv-chart-sub]").textContent = "suggested: " + (holistic ? "bars beside the total (it does not stack)" : "bar") + " · linked to the pivot";
    $("[data-pv-chart-note]").textContent = note;
    var box = $("[data-pv-chart-box]");
    var C = window.Drishti.elements && window.Drishti.elements.chart;
    if (!C) { box.textContent = names.map(function (n, i) { return n + ": " + fmt(values[i], m); }).join(" · "); return; }
    // drishti-chart: one bar per figure as the engine gave it (the diversification and the total beside the parts, never stacked)
    var roles = values.map(function (x, i) { return holistic && total != null && gap != null && sel.length <= 1 ? (i === values.length - 1 ? "total" : i === values.length - 2 ? "div" : "part") : "part"; });
    var spec = {kind: "bar", title: m.label, id: "id", data: {rows: names.map(function (n, i) { return {id: String(i), name: n, value: values[i], role: roles[i]}; })},
      x: "name", y: {field: "value", format: function (v) { return fmt(v, m); }, title: m.label}, series: [{field: "value", name: m.label}],
      labels: true, legend: "none", onClick: "none", colourWhen: [{when: "role == 'total'", tone: "warn"}, {when: "role == 'div'", tone: "bad"}]};
    if (state.chart) { state.chart.setSpec(spec); } else {
      state.chart = new C.Chart(box, spec, {density: "compact", height: 280});
      box.addEventListener("drishti:mark-clicked", function (e) {         // a member's bar opens its row in the pivot
        var r = (state.charted || []).filter(function (x) { return x.label === (e.detail || {}).category; })[0];
        if (r && r.expandable && !r.expanded) { state.grid.model.togglePivotNode("rows", r.path, true); }
      });
    }
    state.charted = picked;
  }

  // ---- export, save, share ---------------------------------------------------------------------------------------------------

  function exportAs(format) {
    var what = (root.querySelector('input[name="pvWhat"]:checked') || {}).value || "pivot";
    var body = {view: viewBody(), what: what, format: format};
    if (what === "rows") {
      if (!state.selection.length) { notice("select a cell first: its rows are exported"); return; }
      body.coords = coordsOf(state.selection[0].key);
    }
    fetch("/api/pivot/export", {method: "POST", credentials: "same-origin", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)})
      .then(function (r) {
        if (!r.ok) { return r.json().then(function (j) { throw new Error(j.detail || r.statusText); }); }
        var cd = r.headers.get("Content-Disposition") || "";
        var name = (/filename="([^"]+)"/.exec(cd) || [0, "pivot." + format])[1];
        return r.blob().then(function (b) {
          var a = document.createElement("a");
          a.href = URL.createObjectURL(b);
          a.download = name;
          document.body.appendChild(a);
          a.click();
          setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
        });
      }).catch(function (e) { notice(e.message); });
  }

  function save(share) {
    var v = viewBody();
    var name = window.prompt("Save the view as (letters, digits, dashes)", (v.source || "pivot") + "-view");
    if (!name) { return; }
    var with_ = share ? window.prompt("Share with (people or role:<role>, comma-separated)", "") : "";
    call("POST", "/views", {name: name, view: v, sharedWith: (with_ || "").split(",").map(function (s) { return s.trim(); }).filter(Boolean)})
      .then(function (s) {
        state.savedView = s.id;
        state.answer.savedView = s.id;
        if (sw()) { sw().shown(state.answer, {saved: true}); }
        $("[data-pv-title]").textContent = s.id + " @" + s.version;
        notice("saved as " + s.id + " (version " + s.version + ")" + (s.sharedWith && s.sharedWith.length ? ", shared with " + s.sharedWith.join(", ") : "") + ": PIVOT @" + s.id);
      }).catch(function (e) { notice(e.message); });
  }

  // ---- the grid ----------------------------------------------------------------------------------------------------------------

  /** Draws the grid for a catalogue and a view: its fields, its wells and open state, and asks once. */
  function mount(view) {
    var c = state.catalogue;
    var panes = !!state.grid && state.grid.model.layout === "panel";      // drawn again (a new field, a date): the panes stay open
    if (state.grid) { state.grid.destroy(); state.grid = null; }
    if (!G) { notice("the grid (the Elements library drishti-grid) did not load"); return; }
    var cols = columnsOf(c, view);
    var host = $("[data-pv-grid]");
    var mode = state.mode = modeOf(view);
    if (panes) { mode = "panes"; }
    var gr = state.grid = new G.Grid(host, {
      columns: cols, layout: mode === "panes" ? "panel" : "wells", density: "compact", title: c.title || c.source, subtitle: c.masked ? c.masked + " masked for you" : "",
      label: "The pivot: arrows move, right arrow opens a row, left closes it, Shift+right opens every level below, E explains a figure",
      clientAggregation: false, serverPivot: ask, serverFuncs: SERVER_FUNCS, valueFuncs: ["sum", "count", "avg", "min", "max", "count_distinct"],
      pivotFormat: function (m) { return G.answerFormat(scaled(unitOf(m))); }, explain: explain, decorateCell: decorate,
      drillThrough: drillThrough, members: function (colId) { return membersOf(colId, filtersBut(colId)); },
      conditionalFormats: rulesOf(view.formats), rowSelection: "multiple",
      hierarchies: c.hierarchies || [], showAs: c.showAs || [], views: mode === "views" || mode === "panes" ? viewsHook() : null,
      valueOptions: lensOptions, customFunctions: state.customFns, formulaEditor: formulaEditor});
    host.addEventListener("drishti:customize-changed", function (e) { keepMode(e.detail && e.detail.open ? "panes" : null); });
    // no expandTo (a view of before): the server's default, every column level open
    gr.model.setOpenState(view.expandTo ? view : Object.assign({}, view, {expandTo: {rows: 1, columns: Math.max(1, view.columns.length)}}));
    gr.model.pivotExtra = extraOf(view);
    state.slicers = (view.slicers || []).slice();
    $("[data-pv-subtotals]").value = view.subtotals === "above" ? "above" : "below";
    gr.setPivot(wellsOf(view, cols));
  }

  /** run(change): change the view as the server knows it (a lens's options, a test's arrangement), then ask again. */
  function applyView(change) {
    var v = viewBody();
    change(v);
    ["rows", "columns", "values", "expanded"].forEach(function (k) { v[k] = v[k] || []; });
    v.filters = v.filters || {};
    var gr = state.grid, cols = gr.model.columns.all();
    var known = function (id) { return cols.some(function (c) { return c.id === id; }); };
    var w = wellsOf(v, cols);
    var fits = v.rows.concat(v.columns).every(known) && w.values.length === v.values.length
      && Object.keys(v.filters).every(function (k) { return k.charAt(0) === "_" || known(k); });
    if (!fits) { mount(v); return; }                    // a field or measure the grid does not hold yet: drawn again with it
    // the same fields: the grid keeps its scroll and layout; its view changes and it asks once
    gr.model.pivotExtra = extraOf(v);
    gr.model.setOpenState(v.expandTo ? v : Object.assign({}, v, {expandTo: {rows: 1, columns: Math.max(1, v.columns.length)}}));
    state.slicers = (v.slicers || []).slice();
    $("[data-pv-subtotals]").value = v.subtotals === "above" ? "above" : "below";
    gr.setConditionalFormats(rulesOf(v.formats));
    if (JSON.stringify(gr.model.wells.getState()) !== JSON.stringify(w)) { gr.model.wells.setState(w); } else { again(); }
  }

  function open(source, date, view) {
    return call("GET", "/catalogue?source=" + encodeURIComponent(source) + (date ? "&date=" + encodeURIComponent(date) : "")).then(function (c) {
      state.catalogue = c;
      var dates = (c.dates || []).slice().reverse();                     // the date and Compare: drishti-inputs' Selects
      $("[data-pv-date]").component.setOptions(dates.length ? dates : [{ value: "", label: "current" }], dates.indexOf(c.date) >= 0 ? c.date : dates.length ? dates[0] : "");
      $("[data-pv-compare]").component.setOptions([{ value: "", label: "off" }].concat(dates.filter(function (d) { return d !== c.date; })), "");
      var preset = presetView();
      if (!view && (c.defaultView || preset)) {
        view = JSON.parse(JSON.stringify(c.defaultView || {source: source, rows: [], columns: [], values: [{measure: "var()", as: "VaR"}]}));
        if (preset) {
          var opts = Object.assign({}, view.options || {}, preset.options || {});
          Object.keys(preset).forEach(function (k) { if (preset[k] != null && k !== "options") { view[k] = preset[k]; } });
          view.options = opts;
        }
        view.date = view.date || c.date;
      }
      if (!view) {
        var firstText = (c.fields || []).filter(function (f) { return f.type === "string"; })[0];
        var firstNum = (c.fields || []).filter(function (f) { return f.type === "number"; })[0];
        var values = c.provider === "var" ? [{measure: "var()", as: "VaR"}, {measure: "es()", as: "ES"}]
          : [{measure: "count()", as: "Rows"}].concat(firstNum ? [{measure: "sum(" + firstNum.name + ")", as: cap(firstNum.label || firstNum.name)}] : []);
        view = {source: source, date: c.date, rows: firstText ? [firstText.name] : [], columns: [], values: values, filters: {}, expanded: []};
      }
      // a saved view, a preset or the source's default: the wells are always there
      ["rows", "columns", "values", "expanded"].forEach(function (k) { view[k] = view[k] || []; });
      if (!state.firstView) { state.firstView = JSON.parse(JSON.stringify(view)); state.snapNext = true; }
      if (!state.viewEntry && !state.savedView) { state.viewEntry = readyViews().filter(function (x) { return x.landing; })[0] || {id: "landing", name: landingName()}; }
      view.filters = view.filters || {};
      view.source = view.source || source;
      $("[data-pv-title]").textContent = c.title || source;
      var opened = openWith(view);
      if (opened) { view = opened; }
      mount(view);
      suggestions(c.source);
    });
  }

  /** An administrator's suggestions (PIVOT_GUIDE.md, documents): the paths people typed most, with Promote and Add to dataset. */
  function suggestions(source) {
    var box = $("[data-pv-paths]");
    box.innerHTML = "";
    call("GET", "/paths?source=" + encodeURIComponent(source)).then(function (r) {
      var list = (r.paths || []).slice(0, 5);
      if (!list.length) { return; }
      box.innerHTML = '<span class="pv-sub">Most used paths (administrator):</span> ' + list.map(function (p) {
        return '<span class="pv-sugg" title="' + esc(p.promote + "; or " + p.dataset) + '"><button type="button" class="pv-btn mono" data-pv-path-use="' + esc(p.path) + '">' + esc(p.path) + '</button> <span class="pv-sub">' + esc(String(p.uses)) + ' uses</span> '
          + (p.connector && p.grain === "entity" ? '<a class="pv-btn" href="/admin/connectors/' + encodeURIComponent(p.connector) + '">Promote</a> ' : "")
          + '<a class="pv-btn" href="/build/tables?new=table">Add to a table</a></span>';
      }).join(" ");
    }, function () { /* not an administrator: no suggestions */ });
  }

  /** A Rachana path ($.LEGS[0].CURRENCY, size($.LEGS)) is a field too: the server reads it from the documents; it goes to Rows. */
  function addPath(path) {
    path = (path || "").trim();
    if (!/^(\$|size\()/.test(path)) { notice("a path starts with $ (or size(...)): $.LEGS[0].CURRENCY"); return; }
    if (!state.fields.some(function (f) { return f.field === path; })) { state.fields.push({field: path, header: path, group: "Your paths", enableRowGroup: true, enablePivot: true}); }
    applyView(function (v) { if (v.rows.indexOf(path) < 0) { v.rows.push(path); } v.expanded = []; });
  }

  /** A partial view the page was opened with (MCUBE <scope> BY ... : its rows, columns, values and options), laid over the landing view. */
  function presetView() {
    var t = root.getAttribute("data-preset");
    if (!t) { return null; }
    root.removeAttribute("data-preset");
    try { return JSON.parse(t); } catch (e) { return null; }
  }

  function boot() {
    call("GET", "/functions").then(function (r) {
      (r.functions || []).forEach(function (f) {                  // the custom functions this person may use (usable: approved, or their own)
        state.customFns.push({name: f.name || f.ref, label: f.name || f.ref});
      });
    }, function () { /* no custom functions here */ });
    call("GET", "/sources").then(function (s) {
      var src = root.getAttribute("data-source");
      $("[data-pv-source]").component.setOptions([{ value: "", label: "choose…" }].concat((s.sources || []).map(function (x) {
        return { value: x.source, label: x.title + " (" + x.source + ")" }; })), (s.sources || []).some(function (x) { return x.source === src; }) ? src : "");
      var id = root.getAttribute("data-view-id");
      if (id) {
        return call("GET", "/views/" + id).then(function (v) {
          $("[data-pv-title]").textContent = v.id + " @" + v.version;
          state.savedView = v.id;
          return open(v.view.source, v.view.date, v.view).then(function () { $("[data-pv-title]").textContent = v.id + " @" + v.version; });
        });
      }
      if (src) { return open(src, root.getAttribute("data-date")); }
      $("[data-pv-meta]").textContent = "choose a source: a table (the datasets of Export data), or VaR";
    }).catch(function (e) { notice(e.message); });
  }

  // ---- the modes and the View picker (PIVOT_GUIDE.md sections 2 and 11) ----------------------------------------------------------------

  /** How the screen opens: the address's mode, else the person's kept choice of the panes, else the view's (a preset's, a Sutra's
      pivot: block), else views (the View picker in front of the compact line). */
  function modeOf(view) {
    var asked = root.getAttribute("data-mode");
    if (state.mode) { return state.mode; }                       // a view opened from the picker keeps the screen's mode
    if (MODES.indexOf(asked) >= 0) { return asked; }
    var kept = null;
    try { kept = window.localStorage.getItem("drishti.pivot.mode." + USER); } catch (e) { kept = null; }
    if (kept === "panes") { return "panes"; }
    return MODES.indexOf((view || {}).mode) >= 0 ? view.mode : "views";
  }

  /** The person's choice between the panes and the line, kept in this browser under their name (never shared between people). */
  function keepMode(m) {
    try { if (m) { window.localStorage.setItem("drishti.pivot.mode." + USER, m); } else { window.localStorage.removeItem("drishti.pivot.mode." + USER); } } catch (e) { /* not kept */ }
  }

  function snapshot(v) { return v ? JSON.stringify({rows: v.rows, columns: v.columns, values: v.values, filters: v.filters}) : null; }

  function landingName() { return state.catalogue && state.catalogue.defaultView ? "Landing" : "Default layout"; }

  /** What a view shows, in a line: its rows › … × its columns · its values. */
  function describe(v) {
    if (!v) { return ""; }
    var lab = function (d) { var f = ((state.catalogue || {}).fields || []).filter(function (x) { return x.name === d; })[0]; return f && f.label ? f.label : d; };
    return ((v.rows || []).map(lab).join(" › ") || "total") + ((v.columns || []).length ? " × " + v.columns.map(lab).join(" › ") : "")
      + " · " + (v.values || []).map(function (x) { return x.as || x.measure; }).join(", ");
  }

  /** The preset's ready-made views (config/pivot-presets.yaml views:), those the source can answer; the landing first when none is it. */
  function readyViews() {
    var list = [];
    try { list = JSON.parse(root.getAttribute("data-views") || "[]"); } catch (e) { list = []; }
    var known = {};
    ((state.catalogue || {}).fields || []).forEach(function (f) { known[f.name] = 1; });
    var out = list.filter(function (v) { return v.landing || (v.rows || []).concat(v.columns || []).every(function (d) { return known[d]; }); });
    if (!out.some(function (v) { return v.landing; })) { out.unshift({id: "landing", name: landingName(), landing: true}); }
    return out.map(function (v) { return Object.assign({}, v, {detail: v.detail || describe(v.landing ? (state.catalogue || {}).defaultView || state.firstView : v)}); });
  }

  /** A ready-made view laid over the landing (its source, date and options kept). */
  function laid(entry, base) {
    var land = JSON.parse(JSON.stringify((state.catalogue || {}).defaultView || state.firstView || {}));
    var v = Object.assign({}, base);
    var from = entry.landing ? land : entry;
    ["rows", "columns", "values"].forEach(function (k) { v[k] = (from[k] || []).slice(); });
    v.sort = from.sort || {};
    v.top = from.top || {};
    v.expanded = [];
    v.expandTo = from.expandTo || {rows: 1, columns: Math.max(1, (from.columns || []).length)};
    if (from.filters) { v.filters = from.filters; }
    v.options = Object.assign({}, base.options || {}, land.options || {}, entry.landing ? {} : entry.options || {}, (base.options || {}).scope ? {scope: base.options.scope} : {});
    return v;
  }

  /** The preset's view to open with (its view:), once, laid over what the page opened with. */
  function openWith(view) {
    var id = root.getAttribute("data-open-view");
    if (!id || root.getAttribute("data-view-id")) { return null; }
    root.removeAttribute("data-open-view");
    var e = readyViews().filter(function (x) { return x.id === id; })[0];
    if (!e) { return null; }
    state.viewEntry = e;
    state.snapNext = true;
    return laid(e, view);
  }

  /** The grid's views hook: lists the ready-made, my and shared views; opens and saves them (the saved views of section 11: no new store). */
  function viewsHook() {
    return {
      title: (root.getAttribute("data-preset-name") ? "the layouts for this cube" : "the layouts for this source"),
      readyTitle: root.getAttribute("data-preset-name") ? "Ready-made for the " + ($("[data-pv-title]").textContent || "cube") : "Ready-made",
      list: function () {
        var ready = readyViews();
        return call("GET", "/views").then(function (r) {
          var src = (viewBody() || {}).source, mine = [], shared = [];
          (r.views || []).forEach(function (x) {
            if (!x.view || (src && x.view.source !== src)) { return; }
            var e = {id: x.id, name: x.name || x.id, detail: describe(x.view), saved: x.id, badge: x.owner === USER ? ((x.sharedWith || []).length ? "shared by you" : "personal") : "shared · " + x.owner};
            (x.owner === USER ? mine : shared).push(e);
          });
          return {ready: ready, mine: mine, shared: shared};
        }, function () { return {ready: ready, mine: [], shared: []}; });
      },
      open: function (e) {
        state.viewEntry = e;
        if (e.saved) {
          return call("GET", "/views/" + e.saved).then(function (v) {
            state.savedView = v.id;
            state.snapNext = true;
            return open(v.view.source, v.view.date, v.view).then(function () { $("[data-pv-title]").textContent = v.id + " @" + v.version; });
          }).catch(function (err) { notice(err.message); });
        }
        state.savedView = null;
        state.snapNext = true;
        applyView(function (v) { var x = laid(e, v); Object.keys(v).forEach(function (k) { delete v[k]; }); Object.assign(v, x); });
        notice("");
        return null;
      },
      save: function (name, scope, with_) {
        var slug = String(name).trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "view";
        var who = scope === "shared" ? String(with_ || "").split(",").map(function (x) { return x.trim(); }).filter(Boolean) : [];
        if (scope === "shared" && !who.length) { return Promise.reject(new Error("say who it is shared with: people, or role:risk")); }
        return call("POST", "/views", {name: slug, view: viewBody(), sharedWith: who}).then(function (sv) {
          state.savedView = sv.id;
          state.viewEntry = {id: sv.id, name: slug, saved: sv.id};
          state.viewSnap = snapshot(viewBody());
          if (state.answer) { state.answer.savedView = sv.id; }
          $("[data-pv-title]").textContent = sv.id + " @" + sv.version;
          return {id: sv.id, name: slug};
        });
      },
      current: function () {
        if (!state.viewEntry) { return null; }
        return {name: state.viewEntry.name, dirty: !!state.viewSnap && !!state.grid && snapshot(viewBody()) !== state.viewSnap};
      },
      revert: function () { if (state.viewEntry) { viewsHook().open(state.viewEntry); } }
    };
  }

  /** A per-cell measure's options in its value menu: the MR lens's horizon, confidence and scenario set (the view's options). */
  function lensOptions(col) {
    var c = state.catalogue || {};
    if (c.provider !== "var" || !(c.notes || {}).lens) { return {items: []}; }
    var o = (viewBody() || {}).options || {};
    var items = [{heading: "VaR options (every value of the view)"},
      {id: "horizon:1d", label: "Horizon 1 day", checked: !o.horizon || o.horizon === "1d"}, {id: "horizon:10d", label: "Horizon 10 days (√10)", checked: o.horizon === "10d"},
      {id: "confidence:0.99", label: "Confidence 99%", checked: !o.confidence || String(o.confidence) === "0.99"}, {id: "confidence:0.975", label: "Confidence 97.5%", checked: String(o.confidence) === "0.975"},
      {id: "set:normal", label: "Scenario set normal", checked: !o.set || o.set === "normal"}, {id: "set:stressed", label: "Scenario set stressed", checked: o.set === "stressed"}];
    return {items: items, run: function (id) {
      var k = id.slice(0, id.indexOf(":")), val = id.slice(id.indexOf(":") + 1);
      applyView(function (v) { v.options = Object.assign({}, v.options || {}); v.options[k] = val; });
    }};
  }

  /** Custom ƒ…: the Rachana formula editor (drishti-inputs' code editor in a dialog); the server checks the formula before it is used. */
  function formulaEditor(o) {
    var dlg = $("[data-pv-formula]"), ta = dlg.querySelector("[data-pv-formula-text]"), problem = dlg.querySelector("[data-pv-formula-problem]");
    var I = window.Drishti.elements && window.Drishti.elements.inputs, C = window.Drishti.elements && window.Drishti.elements.core;
    dlg.querySelector("[data-pv-formula-field]").textContent = o.field ? "on " + o.field.header : "";
    dlg.querySelector("[data-pv-formula-label]").value = o.value && o.value.as ? o.value.as : "";
    problem.hidden = true;
    if (I && I.upgradeCodeArea && !ta.diCode) { try { ta.diCode = I.upgradeCodeArea(ta, {mode: "rachana", height: 90}); } catch (e) { ta.diCode = null; } }
    if (ta.diCode && ta.diCode.setValue) { ta.diCode.setValue(o.formula); } else { ta.value = o.formula; }
    var text = function () { return ta.diCode && ta.diCode.getValue ? ta.diCode.getValue() : ta.value; };
    var close = function () { if (C && C.closeDialog) { C.closeDialog(dlg); } else { dlg.close(); } };
    dlg.querySelector("[data-pv-formula-cancel]").onclick = close;
    dlg.querySelector("[data-pv-formula-ok]").onclick = function () {
      var f = String(text() || "").trim(), label = dlg.querySelector("[data-pv-formula-label]").value.trim();
      var v = viewBody();
      // the server checks it: the view with the formula as its one value is planned (the estimate), nothing computed
      call("POST", "/estimate", {view: {source: v.source, date: v.date, rows: [], columns: [], values: [{measure: f, as: label || f}], filters: {}, options: v.options}})
        .then(function () { close(); o.done(f, label); })
        .catch(function (err) { problem.textContent = err.message; problem.hidden = false; });
    };
    if (C && C.openDialog) { C.openDialog(dlg, {focus: "[data-pv-formula-label]"}); } else { dlg.showModal(); }
  }

  // ---- wiring ---------------------------------------------------------------------------------------------------------------

  function again() { if (state.grid) { state.grid.model.runPivot(); } }

  $("[data-pv-source]").addEventListener("drishti:change", function () { if (this.value) { state.fields = []; open(this.value, ""); } });
  $("[data-pv-date]").addEventListener("drishti:change", function () { var v = viewBody(); v.date = this.value; open(v.source, this.value, v); });
  $("[data-pv-compare]").addEventListener("drishti:change", again);
  $("[data-pv-subtotals]").addEventListener("drishti:change", function () { if (state.grid) { state.grid.model.setPivotOptions({subtotals: this.value}); } });
  $("[data-pv-chart]").onclick = chart;
  $("[data-pv-save]").onclick = function () { save(false); };
  $("[data-pv-share]").onclick = function () { save(true); };
  $("[data-pv-path]").addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); addPath(this.value); } });
  $("[data-pv-path-add]").onclick = function () { addPath($("[data-pv-path]").value); };
  $("[data-pv-slicer-add]").addEventListener("drishti:change", function () {
    if (!this.value) { return; }
    state.slicers = state.slicers.filter(function (d) { return d !== this.value; }, this).concat([this.value]);
    drawSlicers();
  });
  $("[data-pv-export-open]").onclick = function () {
    var p = $("[data-pv-export]");
    p.hidden = !p.hidden;
    this.setAttribute("aria-expanded", String(!p.hidden));
  };
  root.querySelectorAll("[data-pv-export]").forEach(function (b) {
    if (b.tagName === "BUTTON") { b.onclick = function () { exportAs(b.getAttribute("data-pv-export")); }; }
  });
  $("[data-pv-info]").onclick = function () {
    var on = this.getAttribute("aria-expanded") !== "true";
    this.setAttribute("aria-expanded", String(on));
    $("[data-pv-infobox]").hidden = !on;
    var bar = $("[data-pv-estimate]");
    if (bar.innerHTML) { bar.hidden = !on && !bar.classList.contains("warn"); }
  };
  $("[data-pv-estimate]").onclick = function (e) { if (e.target.closest("[data-pv-run]")) { state.force = true; again(); } };
  $("[data-pv-paths]").onclick = function (e) { var b = e.target.closest("[data-pv-path-use]"); if (b) { addPath(b.getAttribute("data-pv-path-use")); } };
  root.addEventListener("click", function (e) {
    var rm = e.target.closest("[data-sl-remove]");
    if (rm) {
      var gone = rm.getAttribute("data-sl-remove");
      state.slicers = state.slicers.filter(function (d) { return d !== gone; });
      setMembers(gone, null);
      drawSlicers();
      return;
    }
    var sl = e.target.closest("[data-sl-field], [data-sl-all]");
    if (!sl) { return; }
    if (sl.hasAttribute("data-sl-all")) { setMembers(sl.getAttribute("data-sl-all"), null); return; }
    var f = sl.getAttribute("data-sl-field"), val = sl.getAttribute("data-sl-value");
    var kept = (((viewBody().filters || {})[f] || {}).in || []).slice();
    var at = kept.indexOf(val);
    if (at >= 0) { kept.splice(at, 1); } else { kept.push(val); }
    setMembers(f, kept);
  });
  // a click on a figure: the selection the chart, the export and the cards read, and the cell's provenance (the grid's Explain)
  root.addEventListener("drishti:cell-clicked", function (e) {
    var d = e.detail || {};
    if (d.kind !== "pivot" || !d.measure) { return; }
    var key = cellKey(d.row, d.col);
    if (!key) { return; }
    var ref = {key: key, measure: d.measure, colKey: key.slice(key.lastIndexOf("|") + 1), entry: state.grid.model.rowAt(d.row).entry, row: d.row, col: d.col};
    var mods = d.mods || {};
    state.selection = mods.shift || mods.ctrl || mods.meta ? state.selection.concat([ref]) : [ref];
    var entry = (state.answer.cells || {})[key];
    root.dispatchEvent(new CustomEvent("drishti:pivot-cell", {detail: {key: key, measure: measureOfLabel(d.measure), cell: entry, coords: coordsOf(key)}}));
    if (!mods.shift) { state.grid.explain(d.row, d.col); }
  });
  // the Pivot's keys (the one keymap, drishti_keys.js; ? lists them); inside the grid its own keys (arrows, + and −, E explains a figure)
  var K = window.Drishti.keys;
  K.register({id: "pivot.chart", keys: ["G", "Shift+G"], scope: "screen", area: "pivot", group: "Pivot", description: "Chart the answer",
    when: function () { return !!state.answer; }, run: function () { chart(); }});
  K.register({id: "pivot.export", keys: ["X", "Shift+X"], scope: "screen", area: "pivot", group: "Pivot", description: "Export the answer",
    when: function () { return !!state.answer; }, run: function () { $("[data-pv-export-open]").click(); }});
  K.register({id: "pivot.expand.to", keys: ["1", "2", "3", "4", "5", "6", "7", "8", "9"], scope: "panel", area: "pivot", group: "Pivot", label: "1 … 9",
    description: "Open the rows to that level", within: function () { return root; }, when: function () { return !!state.answer; },
    run: function (e) {
      var k = e.key;
      if (!/^[1-9]$/.test(k)) { return false; }
      state.grid.model.expandPivotTo("rows", +k);
    }});
  [["R", "rows", "Rows"], ["C", "columns", "Columns"], ["V", "values", "Values"], ["F", "filters", "Filters"]].forEach(function (k) {
    K.register({id: "pivot.pick." + k[1], keys: [k[0]], scope: "screen", area: "pivot", group: "Pivot", description: "Open the " + k[2] + " picker",
      when: function () { return !!state.grid; }, run: function () { state.grid.openPicker(k[1]); }});
  });
  K.register({id: "pivot.views", keys: ["Shift+V"], scope: "screen", area: "pivot", group: "Pivot", description: "Open the View picker: ready-made, my and shared views",
    when: function () { return !!state.grid; }, run: function () { if (!state.grid.openViews()) { return false; } }});
  K.register({id: "pivot.customize", keys: ["\\"], scope: "screen", area: "pivot", group: "Pivot", label: "\\", description: "Customize: the Fields and Build panes, and back",
    when: function () { return !!state.grid; }, run: function () { state.grid.toggleCustomize(); }});
  K.register({id: "pivot.level", keys: ["Alt+ArrowUp", "Alt+ArrowDown"], scope: "screen", area: "pivot", group: "Pivot", label: "Alt+↑ ↓",
    description: "A hierarchy in Rows a level up or down", when: function () { return !!state.grid; },
    run: function (e) { state.grid.stepHierarchy(e.key === "ArrowDown" ? 1 : -1); }});
  K.register({id: "pivot.expand.by", keys: ["Shift+E"], scope: "screen", area: "pivot", group: "Pivot", description: "Expand the selected row by a field",
    when: function () { return !!state.answer && !!state.selection.length; },
    run: function () {
      var row = state.selection[0].entry;
      var by = window.prompt("Expand " + row.label + " by which field?", "");
      if (by) { state.grid.model.pivotState.toggle("rows", row.path, true, by.trim()); again(); }
    }});
  // The screen's one face for the code around it, which reads the view and the answer and never computes: a module's lens (its
  // plugin.yaml's screens.pivot script) changes the view's options and marks cells; the custom functions, explain and what-if cards (drishti_pivot-functions.js)
  // read the view, its answer and the selection; the switches, live pivots and comments (drishti_pivot-switches.js) send the request body
  // as asked, draw a pushed answer, mark the commented cells.
  window.Drishti.PivotScreen = {
    root: root,
    view: function () { return state.grid ? viewBody() : null; },
    answer: function () { return state.answer; },
    catalogue: function () { return state.catalogue; },
    selection: function () { return state.selection; },
    notice: notice,
    body: function () { return asked({view: viewBody()}); },
    call: call,
    show: show,
    fmt: fmt,
    esc: esc,
    // run(change): change the view (a lens's options), then ask again; run(): ask again as it is, past the estimate's confirmation
    run: function (change) {
      if (!state.grid) { return; }
      if (typeof change === "function") { applyView(change); } else { state.force = true; again(); }
    },
    reopen: function (date) { var v = viewBody(); if (v) { v.date = date; open(v.source, date, v); } },
    savedView: function () { return state.savedView; },
    mode: function () { return state.grid ? (state.grid.model.layout === "panel" ? "panes" : state.mode) : null; },
    grid: function () { return $("[data-pv-grid]"); },
    gridApi: function () { return state.grid; },        // drishti-grid's Grid (its model: the rows and texts as drawn)
    // decorate(fn): fn(cellKey, context) gives a cell's marks ({cls, title, after}); redraw() draws them again
    decorate: function (fn) { state.decorators.push(fn); },
    redraw: function () { if (state.grid) { state.grid.refresh(); } }
  };
  boot();
})();
