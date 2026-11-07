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
/* The pivot screen's Why, What-if and Functions cards (PIVOT_GUIDE.md; mock-up A, scene 2): "why did it move" asks the server for the
   difference between two cells (or a cell on two dates) in steps that add up and draws them as a waterfall; a what-if sends an overlay and
   lists the cells it changes; the playground checks a custom function (the harness, the states after each part and merge, the cost, a
   little pivot of the sample) and saves it as a version; the list shows the functions and lets an approver withdraw one (a submitted version is approved in the one Approvals queue).
   Nothing here computes a figure: every number is the drishti-bicompute engine's. */
(function () {
  "use strict";
  var root = document.querySelector("[data-pivot-screen]");
  if (!root || !window.Drishti.PivotScreen) { return; }
  var S = window.Drishti.PivotScreen;
  var esc = S.esc;
  var gridFmt = S.fmt;
  /** A figure of these cards: as the grid formats it from 1 up; below 1, four significant digits (0.007, 0.006429), never rounded to
      0.01 or 0.00: a return or a rate is often that small. */
  var fmt = function (v) {
    if (typeof v !== "number" || !isFinite(v)) { return v == null ? "" : String(v); }
    if (v === 0 || Math.abs(v) >= 1) { return gridFmt(v); }
    return v.toLocaleString("en-US", {maximumSignificantDigits: 4});
  };
  var API = "/api/pivot";
  var $ = function (s) { return document.querySelector(s); };

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

  function fail(e) { S.notice(e.message || String(e)); }

  function open(which) {
    document.querySelectorAll("[data-pf-card]").forEach(function (c) {
      c.hidden = c.getAttribute("data-pf-card") !== which || !c.hidden;
    });
    var card = $("[data-pf-card='" + which + "']");
    if (!card || card.hidden) { return; }
    if (which === "explain") { prefillExplain(); }
    if (which === "functions") { list(); }
    var first = card.querySelector("input, select, textarea, button");
    if (first) { first.focus(); }
  }

  /** "DESK=EMEA|month=2026-10" or "DESK=EMEA, CCY=USD" as coordinates ("*": none). */
  function coords(text) {
    var out = {};
    String(text || "").split(/[|,]/).forEach(function (p) {
      var i = p.indexOf("=");
      if (i > 0) { out[p.slice(0, i).trim()] = p.slice(i + 1).trim(); }
    });
    return out;
  }

  function coordText(c) {
    return Object.keys(c).map(function (k) { return k + "=" + c[k]; }).join(", ");
  }

  // ---- why did it move ----------------------------------------------------------------------------------------------------------

  function prefillExplain() {
    var v = S.view();
    var by = $("[data-pf-by]");
    var dims = [];
    if (v) { dims = v.rows.concat(v.columns).map(String); }
    // any field of the source, as the field picker offers them (the source's catalogue; the engine's own fields _p0, ~all left out):
    // the view's rows and columns first
    var cat = S.catalogue() || {};
    var fields = (cat.fields || []).map(function (f) { return f.name; }).filter(function (n) { return !/^_|~/.test(n); });
    var all = dims.concat(fields.filter(function (f) { return dims.indexOf(f) < 0; }));
    if (!by.options.length || by.getAttribute("data-for") !== all.join(",")) {
      by.innerHTML = all.map(function (d) { return "<option>" + esc(d) + "</option>"; }).join("");
      by.setAttribute("data-for", all.join(","));
    }
    var sel = S.selection();
    if (sel && sel.length) {
      var c = coords(sel[0].key && sel[0].key.replace(/\|\*$/, "").replace(/^\*\|/, ""));
      $("[data-pf-from]").value = coordText(c);
      if (sel.length > 1) { $("[data-pf-to]").value = coordText(coords(sel[1].key)); }
      var fixed = Object.keys(c);
      var next = all.filter(function (d) { return fixed.indexOf(d.split(":")[0]) < 0 && fixed.indexOf(d) < 0; })[0];
      if (next) { by.value = next; }
    }
  }

  // the two dates of "Why did it move?": drishti-inputs' date pickers (typed T-1, ME or 6 Nov 2026, or a calendar), any day of the source
  var dates = {from: null, to: null};
  (function () {
    var I = window.Drishti.elements && window.Drishti.elements.inputs;
    if (!I) { return; }
    dates.from = new I.DatePicker($("[data-pf-from-date]"), {label: "First date", businessOnly: false, shortcuts: true, placeholder: "the view's date"});
    dates.to = new I.DatePicker($("[data-pf-to-date]"), {label: "Second date", businessOnly: false, shortcuts: true, placeholder: "the same date"});
  })();

  /** The one Explain (drishti_explain.js, SHARED_SCREENS.md section 3) of the difference between the two cells or dates, at Why it moved:
      the server's pivot explainer (kind pivot) answers with the steps that add up, drawn by the one renderer; this card only asks. */
  function explain() {
    var v = S.view();
    if (!v) { return fail(new Error("build a view first")); }
    var body = {view: v, from: coords($("[data-pf-from]").value), by: $("[data-pf-by]").value, order: $("[data-pf-order]").value};
    var to = $("[data-pf-to]").value.trim();
    if (to) { body.to = coords(to); }
    var fd = dates.from ? dates.from.getValue() || "" : "";
    var td = dates.to ? dates.to.getValue() || "" : "";
    if (fd) { body.fromDate = fd; }
    if (td) { body.toDate = td; }
    if (!window.Drishti.Explain) { return fail(new Error("Explain is not on this page")); }
    window.Drishti.Explain.open({kind: "pivot", measure: "cell", body: body}, "why", $("[data-pf-explain]"));
  }

  // ---- what-if ----------------------------------------------------------------------------------------------------------------------

  /** "PRICE*1.1 where DESK=RATES" or "NOTIONAL=0 where TRADE_ID=T-1" as a change. */
  function change(text) {
    var m = /^\s*([A-Za-z_][\w.]*)\s*(\*|=)\s*(-?[\d.eE+-]+)\s*(?:where\s+(.+))?$/.exec(text || "");
    if (!m) { return null; }
    var where = {};
    var c = coords(m[4] || "");
    Object.keys(c).forEach(function (k) { where[k] = c[k].split("/"); });
    var ch = {field: m[1], where: where};
    ch[m[2] === "*" ? "scale" : "set"] = +m[3];
    return ch;
  }

  function whatIf() {
    var v = S.view();
    if (!v) { return fail(new Error("build a view first")); }
    var o = {exclude: {}, changes: [], add: []};
    var ex = $("[data-pf-exclude]").value.trim();
    if (ex) {
      var i = ex.indexOf("=");
      if (i > 0) { o.exclude[ex.slice(0, i).trim()] = ex.slice(i + 1).split(",").map(function (x) { return x.trim(); }).filter(Boolean); }
    }
    var ch = change($("[data-pf-change]").value);
    if (ch) { o.changes.push(ch); }
    var add = $("[data-pf-add]").value.trim();
    if (add) {
      try { o.add = JSON.parse(add); } catch (err) { return fail(new Error("the added rows are a JSON list of objects")); }
    }
    call("POST", "/whatif", {view: v, whatIf: o}).then(function (r) {
      var rows = Object.keys(r.diff).filter(function (k) {
        var d = r.diff[k];
        return d.status || Object.keys(d.delta || {}).some(function (m) { return d.delta[m]; });
      });
      var html = ["<p>" + rows.length + " cell(s) change; differences are what-if minus original.</p><table><thead><tr><th>cell</th><th>change</th></tr></thead><tbody>"];
      rows.slice(0, 200).forEach(function (k) {
        var d = r.diff[k];
        var txt = d.status || Object.keys(d.delta).filter(function (m) { return d.delta[m]; }).map(function (m) {
          return m + " " + (d.delta[m] >= 0 ? "+" : "") + fmt(d.delta[m]);
        }).join("; ");
        html.push("<tr><td>" + esc(k.replace(/\|\*$/, "")) + "</td><td>" + esc(txt) + "</td></tr>");
      });
      html.push("</tbody></table>");
      $("[data-pf-whatif-result]").innerHTML = html.join("");
    }).catch(fail);
  }

  // ---- custom functions -------------------------------------------------------------------------------------------------------------

  function csv(text) {
    var lines = String(text || "").split(/\r?\n/).filter(function (l) { return l.trim(); });
    if (!lines.length) { return []; }
    var head = lines[0].split(",").map(function (h) { return h.trim(); });
    return lines.slice(1).map(function (l) {
      var cells = l.split(",");
      var row = {};
      head.forEach(function (h, i) {
        var x = (cells[i] || "").trim();
        row[h] = x === "" ? null : isNaN(+x) ? x : +x;
      });
      return row;
    });
  }

  function definition() {
    var d = {
      name: $("[data-pf-name]").value.trim(), language: $("[data-pf-lang]").value, code: $("[data-pf-code]").value,
      inputs: $("[data-pf-inputs]").value.split(",").map(function (x) { return x.trim(); }).filter(Boolean),
      kind: $("[data-pf-kind]").value, sample: csv($("[data-pf-sample]").value)
    };
    var entry = $("[data-pf-entry]").value.trim();
    if (entry) { d.entry = entry; }
    var g = $("[data-pf-group]").value.trim();
    if (g) { d.group = g; }
    return d;
  }

  function check() {
    call("POST", "/functions/test", definition()).then(function (r) {
      var h = r.harness;
      var html = ["<p class='" + (h.passed ? "pf-ok" : "pf-no") + "'>" + (h.passed ? "Passes the harness" : "Fails the harness: " + esc(h.firstFailure || ""))
        + "</p><p class='pv-sub'>" + esc(h.kind) + " · one pass " + esc(fmt(h.onePass)) + " · " + h.rows + " rows · " + h.nsPerRow + " ns a row · state "
        + h.stateBytes + " bytes</p><table><tbody>"];
      h.checks.forEach(function (c) { html.push("<tr><td>" + (c.passed ? "ok" : "NO") + "</td><td>" + esc(c.check) + "</td><td>" + esc(c.detail) + "</td></tr>"); });
      html.push("</tbody></table>");
      var pg = r.playground || {};
      if (pg.steps) {
        html.push("<h3>States, part by part</h3><table><thead><tr><th>step</th><th>state</th><th>bytes</th><th>value</th></tr></thead><tbody>");
        pg.steps.forEach(function (s) { html.push("<tr><td>" + esc(s.what) + "</td><td><code>" + esc(s.state) + "</code></td><td>" + s.bytes + "</td><td>" + esc(fmt(s.value)) + "</td></tr>"); });
        html.push("</tbody></table><p>Final " + esc(fmt(pg["final"])) + " · one pass " + esc(fmt(pg.onePass)) + (pg.same ? " · the same" : " · DIFFERENT") + "</p>");
      }
      if (pg.pivot) {
        html.push("<h3>In a pivot of the sample</h3><table><tbody>");
        pg.pivot.rows.forEach(function (x) { html.push("<tr><td>" + esc(x.group) + "</td><td>" + x.rows + " rows</td><td>" + esc(x.value) + "</td></tr>"); });
        html.push("<tr><td><b>Total</b></td><td>" + esc(pg.pivot.totalFrom) + "</td><td><b>" + esc(fmt(pg.pivot.total)) + "</b></td></tr></tbody></table>");
      }
      $("[data-pf-check-result]").innerHTML = html.join("");
    }).catch(fail);
  }

  function save() {
    call("POST", "/functions", definition()).then(function (f) {
      S.notice("Saved " + f.ref + " (" + f.kind + "): private until an approver who is not you approves it. Use it as " + f.ref + "(FIELD, ...)");
      list();
    }).catch(fail);
  }

  function list() {
    call("GET", "/functions").then(function (r) {
      var on = Object.keys(r.languages).filter(function (k) { return r.languages[k]; });
      $("[data-pf-langs]").textContent = "on here: " + on.join(", ") + (r.requireApproval ? " · sharing needs an approver who is not the author" : "");
      var html = ["<table><thead><tr><th>function</th><th>language</th><th>kind</th><th>status</th><th></th></tr></thead><tbody>"];
      r.functions.forEach(function (f) {
        html.push("<tr><td><code>" + esc(f.ref) + "</code></td><td>" + esc(f.language) + "</td><td>" + esc(f.kind) + "</td><td>" + esc(f.status)
          + "</td><td>" + (f.status === "submitted" ? "<a class='pv-btn' data-pf-approvals href='/approvals/function/" + esc(encodeURIComponent(f.id.replace("/", ":") + "@" + f.version).replace(/%3A/g, ":").replace(/%40/g, "@")) + "'>In Approvals</a> " : "")
          + "<button type='button' class='pv-btn' data-pf-act='revoke' data-pf-ref='" + esc(f.id + "@" + f.version) + "'>Withdraw</button> "
          + "<button type='button' class='pv-btn' data-pf-use='" + esc(f.ref + "(" + f.inputs.map(function (i) { return i.name; }).join(", ") + ")") + "'>Use</button></td></tr>");
      });
      html.push("</tbody></table>");
      $("[data-pf-list]").innerHTML = r.functions.length ? html.join("") : "<p class='pv-sub'>No functions yet: write one in the playground.</p>";
    }).catch(fail);
  }

  document.addEventListener("click", function (e) {
    var t = e.target.closest ? e.target.closest("[data-pf-open], [data-pf-explain], [data-pf-whatif], [data-pf-check], [data-pf-save], [data-pf-act], [data-pf-use]") : null;
    if (!t) { return; }
    if (t.hasAttribute("data-pf-open")) { open(t.getAttribute("data-pf-open")); }
    else if (t.hasAttribute("data-pf-explain")) { explain(); }
    else if (t.hasAttribute("data-pf-whatif")) { whatIf(); }
    else if (t.hasAttribute("data-pf-check")) { check(); }
    else if (t.hasAttribute("data-pf-save")) { save(); }
    else if (t.hasAttribute("data-pf-use")) {
      S.notice("Add it to Values as: " + t.getAttribute("data-pf-use") + " (put your fields in place of the inputs)");
    } else {
      var ref = t.getAttribute("data-pf-ref");
      var reason = window.prompt("Withdraw " + ref + ": your reason", "");
      if (reason === null) { return; }
      var slash = ref.indexOf("/");
      call("POST", "/functions/" + encodeURIComponent(ref.slice(0, slash)) + "/" + encodeURIComponent(ref.slice(slash + 1)) + "/" + t.getAttribute("data-pf-act"),
        {reason: reason, scope: "everyone"}).then(list).catch(fail);
    }
  });

  // W, I and U open the cards (the one keymap, drishti_keys.js)
  [["W", "explain", "Why did it move? Two cells or dates, explained"], ["I", "whatif", "What-if: private, never written to the lake"],
    ["U", "functions", "Custom functions"]].forEach(function (c) {
    window.Drishti.keys.register({id: "pivot.card." + c[1], keys: [c[0], "Shift+" + c[0]], scope: "screen", area: "pivot-cards", group: "Pivot", description: c[2],
      run: function () { open(c[1]); }});
  });
})();
