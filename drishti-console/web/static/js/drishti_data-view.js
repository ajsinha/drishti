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
/* Export data, EXPORT (DATA works too; docs/guides/DATA_GUIDE.md "Export data"; templates/terminal/data.html, routes/data_routes.py).
   The selection (date, columns, filter lines) lives in the address: every change rewrites it (replaceState), asks the server for the row
   count (debounced, the last answer wins), and fills the read-back, the Download link and the CLI line from what the server says. The
   filter lines are written as the server and the CLI read them: "DESK = DESK-COMM", "PRODUCT_TYPE in ComSwap,ComFut", "MTM between 1 and 5". */
(function () {
  "use strict";
  var root = document.querySelector("[data-dataview]");
  if (!root) { return; }

  // ---- the dataset list's filter --------------------------------------------------------------------------------------------------
  var find = root.querySelector("[data-find]");
  function filterList() {
    var t = (find.value || "").trim().toLowerCase();
    root.querySelectorAll("[data-group]").forEach(function (g) {
      var any = false;
      g.querySelectorAll("[data-row]").forEach(function (r) {
        var on = !t || r.getAttribute("data-text").indexOf(t) >= 0;
        r.hidden = !on;
        any = any || on;
      });
      g.hidden = !any;
    });
  }
  if (find) { find.addEventListener("input", filterList); filterList(); }

  var ds = JSON.parse(root.getAttribute("data-dataset") || "{}");
  var kind = root.getAttribute("data-kind") || "";
  if (!ds.kind || ds.refused) { return; }
  var sel = JSON.parse(root.getAttribute("data-sel") || "{}");
  var columns = (ds.columns || []).map(function (c) { return c.name; });

  var OPS = [["=", "equals"], ["!=", "not equals"], ["in", "one of"], ["contains", "contains"], ["starts", "starts with"],
             [">", "greater than"], ["<", "less than"], ["between", "between"], ["is empty", "is empty"], ["is not empty", "is not empty"]];
  var LINE = /^\s*([A-Za-z_][A-Za-z0-9_.\-]*)\s*(is\s+not\s+empty|is\s+empty|not\s+equals|equals|one\s+of|starts\s+with|starts|contains|between|greater\s+than|less\s+than|!=|<>|=|>|<|in\b)\s*(.*)$/i;
  var WORDS = {"equals": "=", "not equals": "!=", "<>": "!=", "one of": "in", "starts with": "starts", "greater than": ">", "less than": "<"};

  function parse(text) {
    var m = LINE.exec(text || "");
    if (!m) { return null; }
    var op = m[2].toLowerCase().replace(/\s+/g, " ");
    op = WORDS[op] || op;
    var v = m[3] || "";
    var v2 = "";
    if (op === "between") {
      var two = v.split(/\s+and\s+|\s*,\s*/i);
      v = two[0] || "";
      v2 = two[1] || "";
    }
    return {field: m[1], op: op, value: v, value2: v2};
  }

  function text(l) {
    if (!l.field) { return ""; }
    if (l.op === "is empty" || l.op === "is not empty") { return l.field + " " + l.op; }
    if (!String(l.value).trim()) { return ""; }
    if (l.op === "between") { return String(l.value2).trim() ? l.field + " between " + l.value.trim() + " and " + l.value2.trim() : ""; }
    return l.field + " " + l.op + " " + String(l.value).trim();
  }

  // ---- date ----------------------------------------------------------------------------------------------------------------------
  var dates = ds.dates || [];
  var dateSel = root.querySelector("[data-date]");
  var held = {};
  dates.forEach(function (d) { held[d] = true; });
  if (dateSel && !sel.date) { sel.date = dateSel.value; }
  var cal = root.querySelector("[data-cal]");
  var shown = (sel.date || dates[dates.length - 1] || "").slice(0, 7);

  function drawCal() {
    if (!cal || !shown) { return; }
    var y = +shown.slice(0, 4), mo = +shown.slice(5, 7) - 1;
    var first = new Date(Date.UTC(y, mo, 1));
    var days = new Date(Date.UTC(y, mo + 1, 0)).getUTCDate();
    var lead = (first.getUTCDay() + 6) % 7;
    var name = first.toLocaleString(undefined, {month: "long", year: "numeric", timeZone: "UTC"});
    var h = '<div class="dv-cal-h"><button type="button" class="dv-link" data-mo="-1" aria-label="Previous month">◀</button><span>' + name +
      '</span><button type="button" class="dv-link" data-mo="1" aria-label="Next month">▶</button></div><div class="dv-cal-g" role="grid">';
    ["M", "T", "W", "T", "F", "S", "S"].forEach(function (w) { h += '<span class="dv-cal-w" aria-hidden="true">' + w + "</span>"; });
    for (var i = 0; i < lead; i++) { h += "<span></span>"; }
    for (var d = 1; d <= days; d++) {
      var iso = shown + "-" + (d < 10 ? "0" : "") + d;
      h += held[iso] ? '<button type="button" class="dv-day has' + (iso === sel.date ? " on" : "") + '" data-day="' + iso + '" aria-label="' + iso +
        (iso === sel.date ? ', chosen' : '') + '">' + d + "</button>" : '<span class="dv-day">' + d + "</span>";
    }
    cal.innerHTML = h + "</div>";
  }
  if (cal) {
    cal.addEventListener("click", function (e) {
      var b = e.target.closest("button");
      if (!b) { return; }
      if (b.hasAttribute("data-mo")) {
        var y = +shown.slice(0, 4), mo = +shown.slice(5, 7) - 1 + (+b.getAttribute("data-mo"));
        var t = new Date(Date.UTC(y, mo, 1));
        shown = t.getUTCFullYear() + "-" + ("0" + (t.getUTCMonth() + 1)).slice(-2);
        drawCal();
        return;
      }
      sel.date = b.getAttribute("data-day");
      if (dateSel) { dateSel.value = sel.date; }
      drawCal();
      changed();
    });
  }
  if (dateSel) {
    dateSel.addEventListener("drishti:change", function () { sel.date = dateSel.value; shown = sel.date.slice(0, 7); drawCal(); changed(); });
  }

  // ---- columns -------------------------------------------------------------------------------------------------------------------
  var chosen = (sel.columns || "").split(",").map(function (c) { return c.trim(); }).filter(Boolean);
  var boxes = root.querySelectorAll("[data-col]");
  boxes.forEach(function (b) {
    b.checked = !chosen.length || chosen.indexOf(b.value) >= 0;
    b.closest("label").classList.toggle("on", b.checked);
    b.addEventListener("change", function () { b.closest("label").classList.toggle("on", b.checked); changed(); });
  });
  function setAll(on) {
    boxes.forEach(function (b) { b.checked = on; b.closest("label").classList.toggle("on", on); });
    changed();
  }
  var all = root.querySelector("[data-all]"), none = root.querySelector("[data-none]");
  if (all) { all.addEventListener("click", function () { setAll(true); }); }
  if (none) { none.addEventListener("click", function () { setAll(false); }); }
  function picked() {
    var on = [];
    boxes.forEach(function (b) { if (b.checked) { on.push(b.value); } });
    return on;
  }

  // ---- filter lines --------------------------------------------------------------------------------------------------------------
  var linesBox = root.querySelector("[data-lines]");
  var lines = (sel.where || []).map(parse).filter(Boolean);

  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return {"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]; }); }

  function drawLines() {
    var h = "";
    lines.forEach(function (l, i) {
      var unary = l.op === "is empty" || l.op === "is not empty";
      h += '<div class="dv-line" data-line="' + i + '"><span class="dv-and">' + (i ? "AND" : "") + "</span>" +
        '<drishti-select data-f label="Attribute">' + columns.map(function (c) { return '<option' + (c === l.field ? " selected" : "") + ">" + esc(c) + "</option>"; }).join("") +
        '</drishti-select><drishti-select data-o label="Operator">' + OPS.map(function (o) { return '<option value="' + esc(o[0]) + '"' + (o[0] === l.op ? " selected" : "") + ">" + o[1] + "</option>"; }).join("") +
        '</drishti-select><input data-v aria-label="Value" class="mono" placeholder="' + (l.op === "in" ? "a, b, c" : "value") + '" value="' + esc(l.value) + '"' + (unary ? " hidden" : "") + ">" +
        (l.op === "between" ? '<span class="dv-and">and</span><input data-v2 aria-label="Second value" class="mono" value="' + esc(l.value2) + '">' : "") +
        '<button type="button" class="dv-x" data-rm aria-label="Remove this line">×</button></div>';
    });
    linesBox.innerHTML = h;
  }
  linesBox.addEventListener("drishti:change", edit);                     // the attribute and the operator: drishti-inputs' Selects
  linesBox.addEventListener("input", edit);
  function edit(e) {
    var row = e.target.closest("[data-line]");
    if (!row) { return; }
    var l = lines[+row.getAttribute("data-line")];
    var redraw = false;
    if (e.target.hasAttribute("data-f")) { l.field = e.target.value; }
    if (e.target.hasAttribute("data-o")) { redraw = true; l.op = e.target.value; }   // the value boxes follow the operator (none, one, two)
    if (e.target.hasAttribute("data-v")) { l.value = e.target.value; }
    if (e.target.hasAttribute("data-v2")) { l.value2 = e.target.value; }
    if (redraw && e.type === "drishti:change") { drawLines(); }
    changed();
  }
  linesBox.addEventListener("click", function (e) {
    if (!e.target.hasAttribute("data-rm")) { return; }
    lines.splice(+e.target.closest("[data-line]").getAttribute("data-line"), 1);
    drawLines();
    changed();
  });
  root.querySelector("[data-add]").addEventListener("click", function () {
    lines.push({field: columns[0] || "", op: "=", value: "", value2: ""});
    drawLines();
    var last = linesBox.querySelector("[data-line]:last-child [data-v]");
    if (last) { last.focus(); }
  });

  // ---- the address, the count, the read-back -----------------------------------------------------------------------------------
  function query() {
    var p = new URLSearchParams();
    p.set("kind", kind);
    if (sel.date) { p.set("date", sel.date); }
    var cols = picked();
    if (cols.length && cols.length < boxes.length) { p.set("columns", cols.join(",")); }
    lines.map(text).filter(Boolean).forEach(function (w) { p.append("where", w); });
    return p;
  }

  function size(n) { return "≈ " + window.Drishti.elements.core.formatBytes(n, { small: " B" }); }

  function cli(p) {
    var out = "drishti.py data get " + kind + (p.get("date") ? " --date " + p.get("date") : "");
    if (p.get("columns")) { out += " \\\n  --columns " + p.get("columns"); }
    p.getAll("where").forEach(function (w) { out += ' \\\n  --where "' + w.replace(/"/g, '\\"') + '"'; });
    return out + " \\\n  -o " + kind + "_" + (p.get("date") || "latest") + ".jsonl.gz";
  }

  var $ = function (s) { return root.querySelector(s); };
  var timer = null, asked = 0;
  function changed() {
    var p = query();
    history.replaceState(null, "", "/data?" + p.toString());
    var nCols = picked().length;
    $("[data-colcount]").textContent = nCols + " of " + boxes.length + (nCols === boxes.length ? " · all" : "");
    var dl = $("[data-download]");
    var dp = new URLSearchParams(p); dp.delete("kind");
    dl.href = "/data/" + encodeURIComponent(kind) + "/download" + (dp.toString() ? "?" + dp.toString() : "");
    dl.textContent = "Download " + kind + "_" + (sel.date || "latest") + ".jsonl.gz";
    $("[data-cli]").textContent = cli(p);
    $("[data-rows]").textContent = "…";
    clearTimeout(timer);
    if (!nCols) { off("Choose at least one column."); return; }
    timer = setTimeout(function () { count(dp); }, 350);
  }

  function off(why) {
    var dl = $("[data-download]");
    dl.setAttribute("aria-disabled", "true");
    dl.classList.add("disabled");
    $("[data-rows]").textContent = "—";
    $("[data-total]").textContent = why || "";
  }

  function count(dp) {
    var mine = ++asked;
    fetch("/api/data/" + encodeURIComponent(kind) + "/count" + (dp.toString() ? "?" + dp.toString() : ""), {credentials: "same-origin"})
      .then(function (r) { return r.json().then(function (j) { return {ok: r.ok, j: j}; }); })
      .then(function (a) {
        if (mine !== asked) { return; }
        if (!a.ok) { off((a.j.code ? a.j.code + " " : "") + (a.j.detail || "the count failed")); return; }
        var j = a.j;
        $("[data-words]").textContent = j.words || "";
        $("[data-sql]").textContent = j.sql || "";
        $("[data-how]").textContent = "For reference only; it cannot be edited or run. " + (j.pushdown
          ? "Pushed down: the connector filters as it reads its columns."
          : "Filtered on the way as the rows stream (" + (j.read || "the source") + ").");
        $("[data-rows]").textContent = (j.complete ? "" : "at least ") + window.Drishti.elements.core.formatNumber(Number(j.rows));
        $("[data-total]").textContent = "of " + (j.complete ? "" : "at least ") + window.Drishti.elements.core.formatNumber(Number(j.total)) + (j.date ? " on the date" : " rows");
        $("[data-size]").textContent = size(j.gzipBytes || 0);
        var dl = $("[data-download]");
        var none = !j.rows;
        dl.setAttribute("aria-disabled", none ? "true" : "false");
        dl.classList.toggle("disabled", none);
        if (none) { $("[data-total]").textContent += " · no row matches: Download is off"; }
      })
      .catch(function () { if (mine === asked) { off("the count failed: try again"); } });
  }

  $("[data-download]").addEventListener("click", function (e) {
    if (this.getAttribute("aria-disabled") === "true") { e.preventDefault(); }
  });
  $("[data-copy]").addEventListener("click", function () {
    var done = function () { $("[data-copied]").textContent = "Link copied"; };
    if (navigator.clipboard) { navigator.clipboard.writeText(location.href).then(done, function () { $("[data-copied]").textContent = location.href; }); }
    else { $("[data-copied]").textContent = location.href; }
  });

  drawCal();
  drawLines();
  changed();

  // Busy protection (docs/guides/OPERATIONS_GUIDE.md "Busy protection"): the download did not start; the card counts down the server's Retry-After
  // and starts it again by itself (three times at most); the selection is kept.
  var busyHost = $("[data-busy-host]");
  if (busyHost && busyHost.getAttribute("data-busy") && window.Drishti.Busy) {
    var raw = JSON.parse(busyHost.getAttribute("data-busy"));
    var bp = window.Drishti.Busy.problem(raw);
    busyHost.__busyTries = parseInt(raw.tries || "0", 10) || 0;
    busyHost.__busyAt = Date.now();
    var again = function () {
      var u = $("[data-download]").href;
      location.assign(u + (u.indexOf("?") >= 0 ? "&" : "?") + "tries=" + busyHost.__busyTries);
      setTimeout(function () {                              // still here: the download started (a refusal comes back as a page)
        window.Drishti.Busy.done(busyHost);
        busyHost.textContent = "";
        var p = document.createElement("p");
        p.className = "text-muted-d dv-small";
        p.setAttribute("role", "status");
        p.textContent = "The download has started: see your browser's downloads.";
        busyHost.appendChild(p);
      }, 4000);
    };
    if (bp) {
      window.Drishti.Busy.render(busyHost, bp, { retry: again, keep: "your selection is kept and you can keep working on other pages.",
        cancel: function () { window.Drishti.Busy.done(busyHost); busyHost.textContent = ""; }, cli: cli(query()) + " --wait" });
    }
  }
}());
