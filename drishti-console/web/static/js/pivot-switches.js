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
/* The pivot screen's switches, live pivots and cell comments (docs/guides/PIVOT_GUIDE.md: the screen's switches, live pivots, cell
   comments). Exact / Auto / ≈, Progressive and Use rollups are request options the server answers; the line beside them says how the
   answer was computed ("exact", "≈ from a sketch", "from rollup desk-month"), from the answer's computedAs. Live keeps the view current:
   the server pushes only the cells that moved and this script puts them in place (it never computes a figure). A saved view's cells carry
   their comments: a marker on the cell, the comments in the provenance popover, and a box to add one. Keys: L live, M comment. */
(function () {
  "use strict";
  var root = document.querySelector("[data-pivot-screen]");
  var box = root && root.querySelector("[data-pv-switches]");
  if (!root || !box) { return; }
  var $ = function (s) { return root.querySelector(s); };
  var esc = function (s) { return window.PivotCore ? window.PivotCore.esc(s) : String(s == null ? "" : s); };
  var screen = function () { return window.PivotScreen; };
  var live = {id: null, node: "", source: null, checked: null};
  var notes = {byCell: {}, list: [], view: null};

  // ---- the switches ------------------------------------------------------------------------------------------------------------
  function exactness() {
    var on = box.querySelector('input[name="pvExact"]:checked');
    return on ? on.value : "auto";
  }

  function options() {
    var e = exactness();
    return {exact: e === "exact" ? true : e === "approximate" ? false : "auto", progressive: $("[data-pv-progressive]").checked,
      rollups: $("[data-pv-rollups]").checked};
  }

  function computedLine(a, how) {
    var c = (a && a.computedAs) || {};
    var words = c.words || (a && a.exactness) || "";
    var el = $("[data-pv-computed]");
    var bits = [];
    if (how.phase === "approximate") { bits.push("≈ first answer: the exact one is computing"); }
    bits.push(words);
    if (live.id) { bits.push("live" + (live.checked ? " · checked " + live.checked : "")); }
    el.textContent = bits.filter(Boolean).join(" · ");
    el.classList.toggle("approx", c.exact === false || how.phase === "approximate");
    el.title = (c.routes || []).concat(c.topChosen || []).concat((c.sketches || []).map(function (s) { return s.measure + ": " + s.error; })).join("\n")
      || "every figure exact, from its own rows";
  }

  // ---- progressive: ≈ first, then exact, each drawn as it comes ----------------------------------------------------------------
  function sse(text, on) {
    var parts = text.split("\n\n");
    var rest = parts.pop();
    parts.forEach(function (block) {
      var ev = "message", data = [];
      block.split("\n").forEach(function (l) {
        if (l.indexOf("event:") === 0) { ev = l.slice(6).trim(); } else if (l.indexOf("data:") === 0) { data.push(l.slice(5).replace(/^ /, "")); }
      });
      if (data.length) { on(ev, JSON.parse(data.join("\n"))); }
    });
    return rest;
  }

  function progressive(body, show) {
    return fetch("/api/pivot/query/progressive", {method: "POST", credentials: "same-origin",
      headers: {"Content-Type": "application/json", "Accept": "text/event-stream"}, body: JSON.stringify(body)}).then(function (r) {
      if (!r.ok || !r.body) { throw new Error("the progressive answer could not start (" + r.status + ")"); }
      var reader = r.body.getReader(), dec = new TextDecoder(), buf = "";
      var pump = function () {
        return reader.read().then(function (x) {
          if (x.done) { return; }
          buf = sse(buf + dec.decode(x.value, {stream: true}), function (ev, data) {
            if (ev === "error") { throw new Error((data.code ? data.code + ": " : "") + data.error); }
            show(data, {phase: ev});
          });
          return pump();
        });
      };
      return pump();
    });
  }

  // ---- live pivots ---------------------------------------------------------------------------------------------------------------
  function stopLive() {
    if (live.off) { live.off(); }
    if (live.id) { screen().call("DELETE", "/live/" + encodeURIComponent(live.id)).catch(function () { /* already ended */ }); }
    live = {id: null, node: "", source: null, checked: null};
  }

  function flash(keys) {
    var grid = screen().grid();
    keys.forEach(function (k) {
      grid.querySelectorAll('td[data-cell="' + (window.CSS && CSS.escape ? CSS.escape(k) : k) + '"]').forEach(function (td) {
        td.classList.remove("pv-flash");
        void td.offsetWidth;                              // restart the animation
        td.classList.add("pv-flash");
      });
    });
  }

  function patched(p) {
    var a = JSON.parse(JSON.stringify(screen().answer()));
    (p.removed || []).forEach(function (k) { delete a.cells[k]; });
    Object.keys(p.cells || {}).forEach(function (k) { a.cells[k] = p.cells[k]; });
    ["provenance", "provenanceShared", "notes", "exactness", "computedAs", "computed", "version", "date"].forEach(function (k) {
      if (p[k] !== undefined) { a[k] = p[k]; }
    });
    screen().show(a, {patched: true, live: true});
    flash(Object.keys(p.cells || {}));
  }

  function startLive() {
    stopLive();
    var body = screen().body();
    body.progressive = false;
    return screen().call("POST", "/live", body).then(function (s) {
      live = {id: s.id, node: s.node || "", source: body.view && body.view.source, checked: null};
      screen().show(s.answer, {live: true});
      if (!window.DrishtiChannel) { screen().notice("the console's live channel did not load"); return; }
      // on the browser's one live channel (channel.js): pivotlive:<id>@<node>, the node that holds it
      live.off = window.DrishtiChannel.subscribe("pivotlive:" + s.id + (s.node ? "@" + s.node : ""), {
        patch: function (d) { patched(d || {}); },
        reshape: function (d) { screen().show((d || {}).answer, {live: true}); },
        status: function () {
          live.checked = new Date().toLocaleTimeString();
          computedLine(screen().answer(), {});
        },
        failed: function (d) { d = d || {}; screen().notice((d.code ? d.code + ": " : "") + (d.error || "the live pivot stopped")); },
        gone: function (d) { d = d || {}; screen().notice((d.code ? d.code + ": " : "") + (d.detail || "the live pivot's stream ended")); },
        ended: function (d) {
          d = d || {};
          screen().notice("live pivot ended: " + (d.why || "stopped"));
          if (live.off) { live.off(); }
          live = {id: null, node: "", source: null, checked: null};
          $("[data-pv-live]").checked = false;
          computedLine(screen().answer(), {});
        }
      });
    }).catch(function (e) {
      $("[data-pv-live]").checked = false;
      screen().notice(e.message);
    });
  }

  // ---- cell comments -------------------------------------------------------------------------------------------------------------
  function commentsPath() {
    var id = screen().savedView();
    return id ? "/views/" + id.split("/").map(encodeURIComponent).join("/") + "/comments" : null;
  }

  function loadComments(a) {
    var path = commentsPath();
    notes = {byCell: {}, list: [], view: screen().savedView()};
    if (!path) { mark(a); return; }
    screen().call("GET", path).then(function (r) {
      notes.list = r.comments || [];
      notes.list.forEach(function (c) { (notes.byCell[c.cellId] = notes.byCell[c.cellId] || []).push(c); });
      mark(a);
    }).catch(function () { /* not shared with this person, or saved elsewhere: no markers */ });
  }

  function mark(a) {
    var grid = screen().grid();
    grid.querySelectorAll(".pv-commented").forEach(function (td) { td.classList.remove("pv-commented", "pv-resolved"); });
    Object.keys((a && a.cells) || {}).forEach(function (k) {
      var list = notes.byCell[a.cells[k].key];
      if (!list) { return; }
      var open = list.some(function (c) { return !c.resolved; });
      grid.querySelectorAll('td[data-cell="' + (window.CSS && CSS.escape ? CSS.escape(k) : k) + '"]').forEach(function (td) {
        td.classList.add("pv-commented");
        td.classList.toggle("pv-resolved", !open);
        td.setAttribute("aria-description", list.length + " comment" + (list.length === 1 ? "" : "s") + (open ? "" : ", resolved"));
      });
    });
  }

  function commentHtml(c) {
    return '<li class="pv-cm' + (c.resolved ? " resolved" : "") + '" data-cm="' + esc(c.id) + '"><b>' + esc(c.by) + '</b> <span class="pv-sub">'
      + esc(String(c.at).replace("T", " ").slice(0, 16)) + (c.measure ? " · " + esc(c.measure) : "") + (c.older ? " · on an older version" : "")
      + (c.resolved ? " · resolved by " + esc(c.resolved.by) : "") + '</span><div>' + esc(c.text) + '</div>'
      + '<button type="button" class="pv-btn" data-cm-op="' + (c.resolved ? "reopen" : "resolve") + '">' + (c.resolved ? "Reopen" : "Resolve") + '</button> '
      + '<button type="button" class="pv-btn" data-cm-op="delete">Delete</button></li>';
  }

  function popover(pop, key, m, cell) {
    var a = screen().answer();
    var c = (a && a.computedAs) || {};
    var extra = document.createElement("div");
    extra.className = "pv-pop-x";
    var cellKey = a && a.cells && a.cells[key] ? a.cells[key].key : null;
    var list = cellKey ? (notes.byCell[cellKey] || []) : [];
    var path = commentsPath();
    extra.innerHTML = '<dl class="pv-kv"><dt>Answered</dt><dd>' + esc(c.words || a.exactness || "exact") + (live.id ? " · live" : "") + '</dd></dl>'
      + '<h3 class="pv-sub">Comments (' + list.length + ')</h3><ul class="pv-cms">' + list.map(commentHtml).join("") + '</ul>'
      + (path ? '<label class="pv-sub" for="pvCommentText">Add a comment on this cell</label><textarea id="pvCommentText" class="pv-input" rows="2" maxlength="2000" data-cm-text></textarea>'
        + '<label class="pv-sub"><input type="checkbox" data-cm-measure checked> about ' + esc(m.label) + ' only</label> '
        + '<button type="button" class="pv-btn primary" data-cm-add>Comment <kbd>M</kbd></button>'
        : '<p class="pv-sub">Save the view to comment on its cells.</p>');
    pop.appendChild(extra);
    var add = extra.querySelector("[data-cm-add]");
    if (add) {
      add.onclick = function () {
        var text = extra.querySelector("[data-cm-text]").value;
        screen().call("POST", path, {coords: (cell && cell.coords) || {}, measure: extra.querySelector("[data-cm-measure]").checked ? m.label : null, text: text})
          .then(function () { loadComments(screen().answer()); pop.hidden = true; screen().notice("comment added"); })
          .catch(function (e) { screen().notice(e.message); });
      };
    }
    extra.querySelectorAll("[data-cm-op]").forEach(function (b) {
      b.onclick = function () {
        var id = b.closest("[data-cm]").getAttribute("data-cm");
        var op = b.getAttribute("data-cm-op");
        var req = op === "delete" ? screen().call("DELETE", path + "/" + encodeURIComponent(id)) : screen().call("POST", path + "/" + encodeURIComponent(id) + "/" + op, {});
        req.then(function () { loadComments(screen().answer()); pop.hidden = true; }).catch(function (e) { screen().notice(e.message); });
      };
    });
  }

  // ---- after every answer the screen draws -----------------------------------------------------------------------------------------
  function shown(a, how) {
    computedLine(a, how || {});
    if (how && how.saved) { loadComments(a); return; }
    if (how && (how.patched || how.live)) { mark(a); return; }
    if (how && how.phase === "approximate") { mark(a); return; }
    if ($("[data-pv-live]").checked) { startLive(); }     // the view changed: its live view follows (the old one ends)
    if (notes.view !== screen().savedView()) { loadComments(a); } else { mark(a); }
  }

  box.addEventListener("change", function (e) {
    if (e.target.matches("[data-pv-live]")) {
      if (e.target.checked) { startLive(); } else { stopLive(); computedLine(screen().answer(), {}); }
      return;
    }
    if (screen() && screen().answer()) { screen().run(); }   // exact / auto / ≈, progressive or rollups changed: asked again
  });
  document.addEventListener("keydown", function (e) {
    var tag = (e.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea" || e.ctrlKey || e.metaKey || e.altKey) { return; }
    var k = e.key.toLowerCase();
    if (k === "l" && screen() && screen().answer()) {
      e.preventDefault();
      var cb = $("[data-pv-live]");
      cb.checked = !cb.checked;
      cb.dispatchEvent(new Event("change", {bubbles: true}));
    } else if (k === "m") {
      var t = root.querySelector("[data-pv-pop]:not([hidden]) [data-cm-text]");
      if (t) { e.preventDefault(); t.focus(); }
    }
  });
  window.addEventListener("pagehide", function () { if (live.off) { live.off(); } });   // the server ends it after idle-seconds

  window.PivotSwitches = {
    options: options,
    progressiveOn: function () { return $("[data-pv-progressive]").checked; },
    progressive: progressive,
    shown: shown,
    popover: popover,
    live: function () { return live.id; }
  };
})();
