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
/* The pivot screen (terminal/pivot.html; PIVOT_GUIDE.md; mock-up A): the field list and four zones build a view; the server answers
   it (/api/pivot/query, every figure computed by the drishti-bicompute engine); a row opens lazily (/expand: only its children are
   computed), a cell shows its provenance, a selection is charted (a non-additive measure is never stacked: its parts stand beside the
   total with the diversification as its own bar), the Export menu downloads what the server writes. Nothing here aggregates. */
(function () {
  "use strict";
  /* Renderers up to 1.1.0 (static/render/1.1.0, published bytes) still draw a Table | Pivot switch on a table whose Sutra says pivot: and
     start this script as their "pivot" module. Its Pivot is a thin entry into this screen: the panel's rows as the source, a new window
     (the view stays as it was). Nothing is computed here. */
  var compat = {
    init: function (scope, options) {
      var on = function (e) {
        var b = e.target.closest && e.target.closest('[data-pv-tab="pivot"]');
        if (!b) { return; }
        e.preventDefault();
        var box = b.closest('.pv-box, section.pnl') || b.parentNode;
        var host = box && box.querySelector('[data-pivot]');
        var spec = {};
        try { spec = host ? JSON.parse(host.getAttribute('data-pivot') || '{}') : {}; } catch (err) { spec = {}; }
        var view = b.closest('.view[data-view]') || b.closest('[data-kind]');   // the view's own kind and id, not a panel's kind
        var panel = host ? host.getAttribute('data-pivot-panel') : null;
        var source = spec.source || (view && panel ? 'panel/' + view.getAttribute('data-kind') + '/' + panel + '/' + view.getAttribute('data-id') : null);
        if (!source) { return; }
        var url = '/pivot?source=' + encodeURIComponent(source);
        window.open(options && options.url ? options.url(url) : url, '_blank', 'noopener');
      };
      scope.addEventListener('click', on);
      return { dispose: function () { scope.removeEventListener('click', on); } };
    }
  };
  (window.drishtiModules = window.drishtiModules || {}).pivot = compat;
  var root = document.querySelector("[data-pivot-screen]");
  if (!root) { return; }
  var $ = function (s) { return root.querySelector(s); };
  var esc = window.PivotCore.esc;
  var fmt = window.PivotCore.fmt;
  var API = "/api/pivot";
  var state = {view: null, catalogue: null, answer: null, diff: null, grid: null, selection: [], focusField: null, chart: null};

  function notice(msg) {
    var n = $("[data-pv-notice]");
    n.textContent = msg || "";
    n.hidden = !msg;
  }

  function call(method, path, body) {
    var o = {method: method, headers: {"Accept": "application/json"}, credentials: "same-origin"};
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

  // ---- the field list and its four zones ---------------------------------------------------------------------------------------

  function defaultMeasure(f) {
    if (f.native) { return f.name + "()"; }
    return f.type === "number" ? "sum(" + f.name + ")" : "count_distinct(" + f.name + ")";
  }

  function inView(name) {
    var v = state.view;
    var ids = v.rows.concat(v.columns).map(function (d) { return String(d).split(":")[0]; });
    return ids.indexOf(name) >= 0 || Object.keys(v.filters || {}).indexOf(name) >= 0
      || v.values.some(function (x) { return x.measure.indexOf("(" + name) > 0 || x.measure === name + "()"; });
  }

  function drawFields() {
    var c = state.catalogue;
    var q = ($("[data-pv-field-search]").value || "").toLowerCase();
    var h = [];
    var groups = [["Fields", (c.fields || []).map(function (f) { return {name: f.name, type: f.type, note: f.note}; })],
      [c.provider === "var" ? "VaR engine" : "Measures", (c.measures || []).map(function (m) { return {name: m.name, type: m.kind, note: m.description, native: true, label: m.label}; })]];
    groups.forEach(function (g) {
      if (!g[1].length) { return; }
      h.push('<h3>' + esc(g[0]) + '</h3>');
      g[1].forEach(function (f) {
        if (q && f.name.toLowerCase().indexOf(q) < 0) { return; }
        h.push('<div class="pv-field" draggable="true" tabindex="0" data-field="' + esc(f.name) + '" data-native="' + (f.native ? 1 : 0) + '" data-type="' + esc(f.type) + '" title="' + esc(f.note || "") + '">'
          + '<input type="checkbox" tabindex="-1" aria-hidden="true"' + (inView(f.name) ? " checked" : "") + '> <span>' + esc(f.label || f.name) + '</span>'
          + '<span class="pv-type' + (f.type === "holistic" ? " hol" : "") + '">' + esc(f.type) + '</span></div>');
      });
    });
    $("[data-pv-fields]").innerHTML = h.join("") || '<p class="pv-sub">No field you may use.</p>';
    $("[data-pv-fields-sub]").textContent = (c.title || c.source) + (c.masked ? " · " + c.masked + " masked for you" : "");
  }

  var FUNCS = ["sum", "count", "avg", "min", "max", "median", "count_distinct", "var_samp", "stddev_samp"];

  function drawZones() {
    var v = state.view;
    function chip(text, zone, i, extra) {
      return '<span class="pv-zchip" draggable="true" data-zone-item="' + zone + ':' + i + '">' + extra + esc(text)
        + ' <button type="button" class="pv-x" data-remove="' + zone + ':' + i + '" aria-label="Remove ' + esc(text) + '">×</button></span>';
    }
    root.querySelector('[data-pv-zone="rows"] [data-pv-zone-items]').innerHTML = v.rows.map(function (d, i) { return chip(d, "rows", i, ""); }).join("");
    root.querySelector('[data-pv-zone="columns"] [data-pv-zone-items]').innerHTML = v.columns.map(function (d, i) { return chip(d, "columns", i, ""); }).join("");
    root.querySelector('[data-pv-zone="filters"] [data-pv-zone-items]').innerHTML = Object.keys(v.filters).map(function (k, i) {
      var f = v.filters[k];
      return chip(k + ((f.in && f.in.length) ? " in " + f.in.join(", ") : " (all)"), "filters", i, "");
    }).join("");
    root.querySelector('[data-pv-zone="values"] [data-pv-zone-items]').innerHTML = v.values.map(function (x, i) {
      var m = /^(\w+)\((.*)\)$/.exec(x.measure);
      var sel = m && FUNCS.indexOf(m[1]) >= 0 && m[2] ? '<select data-func="' + i + '" aria-label="Summarise by">' + FUNCS.map(function (f) {
        return '<option' + (f === m[1] ? " selected" : "") + '>' + f + '</option>'; }).join("") + '</select> ' : "";
      var label = x.as || x.measure;
      var cur = ((v.formats || {})[label] || {}).style || "";
      var fmt = '<select data-format="' + i + '" aria-label="Format of ' + esc(label) + '"><option value=""' + (cur ? "" : " selected") + '>plain</option>'
        + '<option value="scale"' + (cur === "scale" ? " selected" : "") + '>colour scale</option><option value="bars"' + (cur === "bars" ? " selected" : "")
        + '>data bars</option></select> ';
      return chip(label, "values", i, sel + fmt);
    }).join("");
    root.querySelector('[data-pv-zone="slicers"] [data-pv-zone-items]').innerHTML = (v.slicers || []).map(function (d, i) { return chip(d, "slicers", i, ""); }).join("");
  }

  function add(zone, field, isNative) {
    var v = state.view;
    if (zone === "values") {
      var t = (state.catalogue.fields || []).filter(function (f) { return f.name === field; })[0];
      var m = defaultMeasure(isNative ? {name: field, native: true} : (t || {name: field, type: "string"}));
      if (!v.values.some(function (x) { return x.measure === m; })) { v.values.push({measure: m}); }
    } else if (isNative) {
      notice(field + " is a measure: drop it on Values");
      return;
    } else if (zone === "slicers") {
      v.slicers = (v.slicers || []).filter(function (d) { return d !== field; }).concat([field]);
    } else if (zone === "filters") {
      var vals = window.prompt("Keep which values of " + field + "? (comma-separated; blank: all)", "");
      if (vals === null) { return; }
      v.filters[field] = {in: vals.split(",").map(function (s) { return s.trim(); }).filter(Boolean)};
    } else {
      v.rows = v.rows.filter(function (d) { return d !== field; });
      v.columns = v.columns.filter(function (d) { return d !== field; });
      v[zone].push(field);
      v.expanded = [];
    }
    run();
  }

  function remove(spec) {
    var p = spec.split(":");
    var v = state.view;
    if (p[0] === "filters") { delete v.filters[Object.keys(v.filters)[+p[1]]]; }
    else if (p[0] === "slicers") { var gone = v.slicers.splice(+p[1], 1)[0]; if (v.filters[gone]) { delete v.filters[gone]; } }
    else { v[p[0]].splice(+p[1], 1); }
    if (p[0] === "rows" || p[0] === "columns") { v.expanded = []; }
    if (!v.values.length) { v.values.push({measure: state.catalogue.provider === "var" ? "var()" : "count()"}); }
    run();
  }

  // ---- asking the server -------------------------------------------------------------------------------------------------------

  function viewBody() {
    var v = JSON.parse(JSON.stringify(state.view));
    v.subtotals = $("[data-pv-subtotals]").value;
    return v;
  }

  function estimate() {
    return call("POST", "/estimate", {view: viewBody()}).then(function (e) {
      var bar = $("[data-pv-estimate]");
      bar.innerHTML = '<i class="bi bi-speedometer2" aria-hidden="true"></i> ' + esc(e.words)
        + (e.confirm ? ' <button type="button" class="pv-btn primary" data-pv-run>Run</button>' : "");
      bar.hidden = false;
      bar.classList.toggle("warn", !!e.confirm);
      return e;
    }).catch(function () { return {}; });
  }

  function run(force) {
    notice("");
    drawZones();
    if (state.catalogue) { drawFields(); }
    $("[data-pv-meta]").textContent = "computing…";
    estimate().then(function (e) {
      if (e && e.confirm && !force) { $("[data-pv-meta]").textContent = "large: press Run to compute it"; return; }
      var cmp = $("[data-pv-compare]").value;
      var p = cmp ? call("POST", "/compare", {view: viewBody(), otherDate: cmp}).then(function (c) { state.diff = c.diff; return c.answer; })
        : call("POST", "/query", {view: viewBody()}).then(function (a) { state.diff = null; return a; });
      return p.then(show);
    }).catch(function (err) { $("[data-pv-meta]").textContent = ""; notice(err.message); });
  }

  function show(a) {
    state.answer = a;
    state.view = a.view;
    var comp = a.computed || {};
    $("[data-pv-meta]").textContent = (a.title || a.source) + " · " + (a.date ? "business date " + a.date : "current") + " · "
      + a.cellCount + " cells · " + comp.sets + " sets (" + comp.fromCache + " from the cache) · " + comp.queries + " queries · " + comp.ms + " ms";
    $("[data-pv-grid-title]").textContent = (a.measures || []).map(function (m) { return m.label; }).join(", ") + " by " + ((a.rowDimensions || []).join(" › ") || "total");
    $("[data-pv-grid-sub]").textContent = "rows " + ((a.rowDimensions || []).join(" › ") || "—") + (a.columnDimensions && a.columnDimensions.length ? " · columns " + a.columnDimensions.join(" › ") : "")
      + " · subtotals " + (a.view.subtotals || "below") + " · lazy: closed nodes are not computed · " + a.exactness;
    state.grid = window.PivotCore.render($("[data-pv-grid]"), a, {diff: state.diff, onToggle: toggle, onCell: provenance, onSelect: function (s) { state.selection = s; }, onRows: rows});
    drawZones();
    drawFields();
    drawSlicers();
  }

  // ---- slicers: a field's members as buttons, each a filter of the view (the members are the engine's, as the person may see them) ------
  function drawSlicers() {
    var box = $("[data-pv-slicers]");
    var list = state.view.slicers || [];
    box.hidden = !list.length;
    if (!list.length) { box.innerHTML = ""; return; }
    box.innerHTML = list.map(function (f, i) { return '<div class="pv-slicer" data-slicer="' + i + '"><b>' + esc(f) + '</b><span class="pv-sub">…</span></div>'; }).join("");
    list.forEach(function (f, i) {
      var others = {};
      Object.keys(state.view.filters || {}).forEach(function (k) { if (k !== f) { others[k] = state.view.filters[k]; } });
      var q = { source: state.view.source, date: state.view.date, rows: [f], values: ["count()"], filters: others, sort: {} };
      q.sort[f] = { by: "label" };
      call("POST", "/query", { view: q }).then(function (a) {
        var kept = ((state.view.filters || {})[f] || {}).in || [];
        var members = (a.rows || []).filter(function (r) { return r.kind === "node"; });
        var el = box.querySelector('[data-slicer="' + i + '"]');
        if (!el) { return; }
        el.innerHTML = '<b>' + esc(f) + '</b><button type="button" class="pv-sl" data-sl-all="' + esc(f) + '" aria-pressed="' + (!kept.length) + '">All</button>'
          + members.slice(0, 60).map(function (r) {
            return '<button type="button" class="pv-sl" data-sl-field="' + esc(f) + '" data-sl-value="' + esc(r.value) + '" aria-pressed="' + (kept.indexOf(r.value) >= 0) + '">'
              + esc(r.value) + '</button>';
          }).join("") + (members.length > 60 ? '<span class="pv-sub">' + (members.length - 60) + ' more: use a filter</span>' : "");
      }).catch(function (e) {
        var el = box.querySelector('[data-slicer="' + i + '"]');
        if (el) { el.querySelector(".pv-sub").textContent = e.message; }
      });
    });
  }

  function toggle(row, collapse, all) {
    var v = state.view;
    var path = row.path || [];
    if (collapse) {
      v.expanded = (v.expanded || []).filter(function (e) {
        return e.axis !== "rows" || e.path.length < path.length || JSON.stringify(e.path.slice(0, path.length)) !== JSON.stringify(path);
      });
      if ((v.expandTo || {}).rows > path.length) { v.expandTo = {rows: Math.max(1, path.length), columns: (v.expandTo || {}).columns || 1}; }
      run();
      return;
    }
    if (all) {
      v.expandTo = {rows: Math.max(v.rows.length, path.length + 1), columns: (v.expandTo || {}).columns || 1};
      run();
      return;
    }
    expandBy(row, null);
  }

  function expandBy(row, by) {
    notice("");
    $("[data-pv-meta]").textContent = "opening " + row.label + "…";
    call("POST", "/expand", {view: viewBody(), axis: "rows", path: row.path, by: by}).then(show).catch(function (e) { notice(e.message); });
  }

  // ---- provenance, rows, references -------------------------------------------------------------------------------------------

  function coordsOf(key) {
    var cell = (state.answer.cells || {})[key];
    return cell ? cell.coords : {};
  }

  function reference(key, m) {
    var id = state.answer.savedView;
    var c = coordsOf(key);
    var parts = Object.keys(c).map(function (k) { return k + "=" + c[k]; });
    return id ? "pivot://" + id + "/" + m.label + "[" + parts.join(", ") + "]" : "";
  }

  function provenance(key, m, td) {
    var pop = $("[data-pv-pop]");
    call("GET", "/provenance/" + encodeURIComponent(state.answer.provenance) + "?cell=" + encodeURIComponent(key)).then(function (p) {
      var cell = p.cell || {};
      var v = cell.values ? cell.values[m.label] : null;
      var fn = (p.functions || []).filter(function (f) { return f.label === m.label; })[0] || {};
      var ref = reference(key, m);
      var coords = cell.coords || {};
      pop.innerHTML = '<div class="pv-pop-h"><b>' + esc(m.label) + ' · ' + esc(Object.keys(coords).map(function (k) { return coords[k]; }).join(" · ") || "grand total")
        + ' = ' + esc(fmt(v, m)) + '</b> <span class="pv-tag">' + (m.exact === false ? "≈" : "exact") + '</span> <span class="pv-tag">' + esc(fn.kind || m.kind) + '</span>'
        + '<button type="button" class="pv-x" data-pop-close aria-label="Close">×</button></div><dl class="pv-kv">'
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
      pop.hidden = false;
      var r = td.getBoundingClientRect();
      var host = $(".pv-gridcard").getBoundingClientRect();
      pop.style.top = Math.max(40, r.bottom - host.top + 4) + "px";
      pop.style.left = Math.max(8, Math.min(r.left - host.left - 120, host.width - 440)) + "px";
      pop.querySelector("[data-pop-close]").onclick = function () { pop.hidden = true; };
      pop.querySelector("[data-pop-rows]").onclick = function () { rows(key, pop.querySelector("[data-pop-rowsbox]")); };
      var cp = pop.querySelector("[data-pop-copy]");
      if (cp) { cp.onclick = function () { navigator.clipboard && navigator.clipboard.writeText(ref); cp.textContent = "Copied"; }; }
    }).catch(function (e) { notice(e.message); });
  }

  function rows(key, box) {
    box = box || $("[data-pv-pop] [data-pop-rowsbox]");
    call("POST", "/rows", {view: viewBody(), coords: coordsOf(key), limit: 50}).then(function (r) {
      var h = ['<p class="pv-sub">' + r.rows.length + " of " + r.total + " rows" + (r.note ? " · " + esc(r.note) : "") + '</p><div class="pv-rows"><table class="pv-table"><thead><tr>'];
      r.columns.forEach(function (c) { h.push("<th>" + esc(c) + "</th>"); });
      h.push("</tr></thead><tbody>");
      r.rows.forEach(function (row) { h.push("<tr>" + row.map(function (x) { return "<td>" + esc(x) + "</td>"; }).join("") + "</tr>"); });
      h.push("</tbody></table></div>");
      if (box) { box.innerHTML = h.join(""); }
    }).catch(function (e) { notice(e.message); });
  }

  // ---- the chart of a selection -------------------------------------------------------------------------------------------------

  function chart() {
    var a = state.answer;
    if (!a) { return; }
    var sel = state.selection.length ? state.selection : [];
    var m = a.measures[sel.length ? sel[0].m : 0];
    var colKey = sel.length ? a.columns[sel[0].c].key : a.columns[a.columns.length - 1].key;
    // the rows charted: the selected ones, else the top level's members
    var picked = sel.length > 1 ? sel.map(function (s) { return a.rows[s.row]; }) : a.rows.filter(function (r) { return r.depth === 1 && r.kind !== "header"; });
    var holistic = m.kind === "holistic" && !m.plainSum;
    var names = [];
    var values = [];
    picked.forEach(function (r) {
      var c = a.cells[r.key + "|" + colKey];
      var v = c && c.values ? c.values[m.label] : null;
      if (v != null) { names.push(r.kind === "total" ? r.label.replace(/ total$/, "") : r.label); values.push(v); }
    });
    var grand = a.cells[a.rows[a.rows.length - 1].key + "|" + colKey];
    var total = grand && grand.values ? grand.values[m.label] : null;
    var note;
    var series;
    var colors = [];
    if (holistic && total != null && sel.length <= 1) {
      var sum = values.reduce(function (s, x) { return s + x; }, 0);
      names = names.concat(["Diversification", "Total"]);
      values = values.concat([total - sum, total]);
      note = m.label + " does not add up: the total is its own figure from its own members, so the parts stand beside it and the "
        + "diversification (total − Σ parts = " + fmt(total - sum, m) + ") is drawn as its own bar, never stacked.";
      colors = values.map(function (x, i) { return i === values.length - 1 ? "#d9a441" : i === values.length - 2 ? "#c87a52" : "#6f9fd8"; });
    } else {
      note = m.label + (m.plainSum ? " adds up: the bars sum to the total." : " by member; each figure is the cell's own.");
      colors = values.map(function () { return "#6f9fd8"; });
    }
    series = [{type: "bar", data: values.map(function (v, i) { return {value: v, itemStyle: {color: colors[i]}}; }), label: {show: true, position: "top", formatter: function (p) { return fmt(p.value, m); }}}];
    var card = $("[data-pv-chartcard]");
    card.hidden = false;
    $("[data-pv-chart-title]").textContent = "Chart of the selection: " + m.label + " by " + ((a.rowDimensions || [])[0] || "total");
    $("[data-pv-chart-sub]").textContent = "suggested: " + (holistic ? "bars beside the total (it does not stack)" : "bar") + " · linked to the pivot";
    $("[data-pv-chart-note]").textContent = note;
    var box = $("[data-pv-chart-box]");
    if (!window.echarts) { box.textContent = names.map(function (n, i) { return n + ": " + fmt(values[i], m); }).join(" · "); return; }
    var styles = getComputedStyle(document.body);
    var ink = styles.getPropertyValue("--d-ink").trim() || "#ddd";
    var muted = styles.getPropertyValue("--d-muted").trim() || "#999";
    state.chart = state.chart || window.echarts.init(box, null, {renderer: "svg"});
    state.chart.setOption({backgroundColor: "transparent", textStyle: {color: ink}, grid: {left: 70, right: 20, top: 30, bottom: 50},
      xAxis: {type: "category", data: names, axisLabel: {color: muted, interval: 0, rotate: names.length > 6 ? 30 : 0}},
      yAxis: {type: "value", axisLabel: {color: muted, formatter: function (v) { return fmt(v, m); }}, splitLine: {lineStyle: {color: muted, opacity: 0.2}}},
      tooltip: {trigger: "axis", valueFormatter: function (v) { return fmt(v, m); }}, series: series}, true);
    state.chart.off("click");
    state.chart.on("click", function (p) {
      var r = picked.filter(function (x) { return x.label === p.name; })[0];
      if (r && r.expandable && !r.expanded) { expandBy(r, null); }
    });
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
    var name = window.prompt("Save the view as (letters, digits, dashes)", (state.view.source || "pivot") + "-view");
    if (!name) { return; }
    var with_ = share ? window.prompt("Share with (people or role:<role>, comma-separated)", "") : "";
    call("POST", "/views", {name: name, view: viewBody(), sharedWith: (with_ || "").split(",").map(function (s) { return s.trim(); }).filter(Boolean)})
      .then(function (v) {
        state.answer.savedView = v.id;
        $("[data-pv-title]").textContent = v.id + " @" + v.version;
        notice("saved as " + v.id + " (version " + v.version + ")" + (v.sharedWith && v.sharedWith.length ? ", shared with " + v.sharedWith.join(", ") : "") + ": PIVOT @" + v.id);
      }).catch(function (e) { notice(e.message); });
  }

  // ---- start -----------------------------------------------------------------------------------------------------------------

  function open(source, date, view) {
    return call("GET", "/catalogue?source=" + encodeURIComponent(source) + (date ? "&date=" + encodeURIComponent(date) : "")).then(function (c) {
      state.catalogue = c;
      var ds = $("[data-pv-date]");
      ds.innerHTML = (c.dates || []).slice().reverse().map(function (d) { return '<option' + (d === c.date ? " selected" : "") + '>' + esc(d) + '</option>'; }).join("") || '<option value="">current</option>';
      $("[data-pv-compare]").innerHTML = '<option value="">off</option>' + (c.dates || []).slice().reverse().filter(function (d) { return d !== c.date; }).map(function (d) { return "<option>" + esc(d) + "</option>"; }).join("");
      if (view) {
        state.view = view;
      } else {
        var firstText = (c.fields || []).filter(function (f) { return f.type === "string"; })[0];
        var firstNum = (c.fields || []).filter(function (f) { return f.type === "number"; })[0];
        var values = c.provider === "var" ? [{measure: "var()", as: "VaR"}, {measure: "es()", as: "ES"}]
          : [{measure: "count()", as: "Rows"}].concat(firstNum ? [{measure: "sum(" + firstNum.name + ")"}] : []);
        state.view = {source: source, date: c.date, rows: firstText ? [firstText.name] : [], columns: [], values: values, filters: {}, expanded: []};
      }
      $("[data-pv-title]").textContent = c.title || source;
      run();
    });
  }

  function boot() {
    call("GET", "/sources").then(function (s) {
      var sel = $("[data-pv-source]");
      var src = root.getAttribute("data-source");
      sel.innerHTML = '<option value="">choose…</option>' + (s.sources || []).map(function (x) {
        return '<option value="' + esc(x.source) + '"' + (x.source === src ? " selected" : "") + '>' + esc(x.title) + ' (' + esc(x.source) + ')</option>'; }).join("");
      var id = root.getAttribute("data-view-id");
      if (id) {
        return call("GET", "/views/" + id).then(function (v) {
          $("[data-pv-title]").textContent = v.id + " @" + v.version;
          return open(v.view.source, v.view.date, v.view).then(function () { if (state.answer) { state.answer.savedView = v.id; } });
        });
      }
      if (src) { return open(src, root.getAttribute("data-date")); }
      $("[data-pv-meta]").textContent = "choose a source: a table of the Data view, or VaR";
    }).catch(function (e) { notice(e.message); });
  }

  // ---- wiring ---------------------------------------------------------------------------------------------------------------

  $("[data-pv-source]").onchange = function () { if (this.value) { state.view = null; open(this.value, ""); } };
  $("[data-pv-date]").onchange = function () { state.view.date = this.value; open(state.view.source, this.value, state.view); };
  $("[data-pv-compare]").onchange = function () { run(); };
  $("[data-pv-subtotals]").onchange = function () { run(); };
  $("[data-pv-field-search]").oninput = drawFields;
  $("[data-pv-chart]").onclick = chart;
  $("[data-pv-save]").onclick = function () { save(false); };
  $("[data-pv-share]").onclick = function () { save(true); };
  $("[data-pv-export-open]").onclick = function () {
    var p = $("[data-pv-export]");
    p.hidden = !p.hidden;
    this.setAttribute("aria-expanded", String(!p.hidden));
  };
  root.querySelectorAll("[data-pv-export]").forEach(function (b) {
    if (b.tagName === "BUTTON") { b.onclick = function () { exportAs(b.getAttribute("data-pv-export")); }; }
  });
  $("[data-pv-estimate]").onclick = function (e) { if (e.target.closest("[data-pv-run]")) { run(true); } };
  root.addEventListener("click", function (e) {
    var rm = e.target.closest("[data-remove]");
    if (rm) { remove(rm.getAttribute("data-remove")); return; }
    var f = e.target.closest(".pv-field");
    if (f) { state.focusField = f; root.querySelectorAll(".pv-field.on").forEach(function (x) { x.classList.remove("on"); }); f.classList.add("on"); f.focus();
      if (e.target.tagName === "INPUT" || e.detail === 2) { add(f.getAttribute("data-native") === "1" || f.getAttribute("data-type") === "number" ? "values" : "rows", f.getAttribute("data-field"), f.getAttribute("data-native") === "1"); } }
  });
  root.addEventListener("click", function (e) {
    var sl = e.target.closest("[data-sl-field], [data-sl-all]");
    if (!sl) { return; }
    var v = state.view;
    v.filters = v.filters || {};
    if (sl.hasAttribute("data-sl-all")) {
      delete v.filters[sl.getAttribute("data-sl-all")];
    } else {
      var f = sl.getAttribute("data-sl-field"), val = sl.getAttribute("data-sl-value");
      var kept = ((v.filters[f] || {}).in || []).slice();
      var at = kept.indexOf(val);
      if (at >= 0) { kept.splice(at, 1); } else { kept.push(val); }
      if (kept.length) { v.filters[f] = { in: kept }; } else { delete v.filters[f]; }
    }
    run();
  });
  root.addEventListener("change", function (e) {
    var fmt = e.target.closest("[data-format]");
    if (fmt) {
      var x = state.view.values[+fmt.getAttribute("data-format")];
      var label = x.as || x.measure;
      state.view.formats = state.view.formats || {};
      if (fmt.value) { state.view.formats[label] = { style: fmt.value }; } else { delete state.view.formats[label]; }
      state.grid = window.PivotCore.render($("[data-pv-grid]"), Object.assign({}, state.answer, { view: state.view }), {
        diff: state.diff, onToggle: toggle, onCell: provenance, onSelect: function (s) { state.selection = s; }, onRows: rows });
      return;
    }
    var fs = e.target.closest("[data-func]");
    if (fs) {
      var i = +fs.getAttribute("data-func");
      var x = state.view.values[i];
      x.measure = x.measure.replace(/^\w+\(/, fs.value + "(");
      if (fs.value === "count" && /\(\s*\)$/.test(x.measure)) { x.measure = "count()"; }
      delete x.as;
      run();
    }
  });
  root.addEventListener("dragstart", function (e) {
    var f = e.target.closest(".pv-field");
    if (f) { e.dataTransfer.setData("text/plain", JSON.stringify({field: f.getAttribute("data-field"), native: f.getAttribute("data-native") === "1"})); }
  });
  root.querySelectorAll("[data-pv-zone]").forEach(function (z) {
    z.addEventListener("dragover", function (e) { e.preventDefault(); z.classList.add("over"); });
    z.addEventListener("dragleave", function () { z.classList.remove("over"); });
    z.addEventListener("drop", function (e) {
      e.preventDefault();
      z.classList.remove("over");
      try { var d = JSON.parse(e.dataTransfer.getData("text/plain")); add(z.getAttribute("data-pv-zone"), d.field, d.native); } catch (err) { /* not a field */ }
    });
  });
  document.addEventListener("keydown", function (e) {
    var tag = (e.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea" || e.ctrlKey || e.metaKey || e.altKey) { return; }
    var k = e.key.toLowerCase();
    if (state.focusField && document.activeElement === state.focusField && "rcvfs".indexOf(k) >= 0) {
      e.preventDefault();
      add({r: "rows", c: "columns", v: "values", f: "filters", s: "slicers"}[k], state.focusField.getAttribute("data-field"), state.focusField.getAttribute("data-native") === "1");
      return;
    }
    if (!state.answer) { return; }
    if (k === "g") { e.preventDefault(); chart(); }
    else if (k === "x") { e.preventDefault(); $("[data-pv-export-open]").click(); }
    else if (/^[1-9]$/.test(k) && root.contains(document.activeElement)) {
      e.preventDefault();
      state.view.expandTo = {rows: +k, columns: (state.view.expandTo || {}).columns || 1};
      run();
    } else if (k === "e" && state.selection.length) {
      e.preventDefault();
      var row = state.answer.rows[state.selection[0].row];
      var by = window.prompt("Expand " + row.label + " by which field?", "");
      if (by) { expandBy(row, by.trim()); }
    }
  });
  boot();
})();
