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
/* One Explain for every number (docs/architecture/SHARED_SCREENS.md section 3; USER_GUIDE.md, "Explain"; the approved mock-up
   docs/architecture/mockups/shared-screens/e3-explain.html, A with B as its preview and C through a link). One component on every page:

   - the preview (B): hover a number that carries data-explain (or focus it and press ?): a small card with the answer in one line and
     links to the four parts;
   - the drawer (A): click the number (or press E): a drawer on the right, the page still visible, four tabs the same everywhere, Formula
     (with the actual values), Inputs & lineage, Why it moved (since the previous date, steps that add up, drawn by the one renderer) and
     Workbook (the live-formula Excel), and the checks under them; Esc or ✕ closes it and the focus goes back to the number;
   - the full page (C): "Open as a page" (/explain?...), all four parts at once, for an auditor or for printing.

   A number opts in with data-explain='{"kind":"var","measure":"es","scope":"DESK:RATES"}' (and data-explain-tab to open another tab
   first); a page may name its main number with data-explain-primary, which E opens when no number is focused. Script API:
   Drishti.Explain.open(request, tab), .preview(anchor, request), .close(), .pageUrl(request, tab), .bind(el, request, tab).
   Everything is the server's (GET /api/explain, the person's own entitlements): the browser computes nothing, it places text. */
(function () {
  "use strict";
  var TABS = [["formula", "Formula"], ["inputs", "Inputs & lineage"], ["why", "Why it moved"], ["workbook", "Workbook"]];
  var cache = {};
  var drawer = null, pop = null, popFor = null, hoverTimer = null, lastAnchor = null, lib = null;
  var S = { req: null, tab: "formula", view: null, from: "", to: "", seq: 0 };

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function reqOf(el) {
    try { return JSON.parse(el.getAttribute("data-explain") || "{}"); } catch (e) { return null; }
  }
  function query(req, extra) {
    var p = new URLSearchParams();
    Object.keys(req).forEach(function (k) { if (k !== "body" && req[k] !== null && req[k] !== undefined && req[k] !== "") p.set(k, String(req[k])); });
    Object.keys(extra || {}).forEach(function (k) { if (extra[k]) p.set(k, extra[k]); });
    return p;
  }

  /** The server's answer for a part (cached per page: the same number asked twice is asked once). */
  function ask(req, part, extra) {
    var q = query(req, Object.assign({ part: part }, extra || {}));
    var key = q.toString() + (req.body ? JSON.stringify(req.body) : "");
    if (cache[key]) return cache[key];
    var init = { headers: { Accept: "application/json" }, credentials: "same-origin" };
    var url = "/api/explain?" + q.toString();
    if (req.body) {
      url = "/api/explain";
      init.method = "POST";
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(Object.assign({}, req.body, Object.fromEntries(q)));
    }
    var p = fetch(url, init).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error(b.detail || ("HTTP " + r.status)); e.status = r.status; throw e; }
        return b;
      });
    });
    cache[key] = p;
    p.catch(function () { delete cache[key]; });
    return p;
  }

  function pageUrl(req, tab) {
    var q = query(req, tab ? { tab: tab } : {});
    return "/explain?" + q.toString();
  }

  // ---------------------------------------------------------------------------------------------------------------- the preview (B)
  function closePreview() { if (pop) { pop.hidden = true; popFor = null; } }
  function preview(anchor, req) {
    if (!pop) {
      pop = h("div", "dx-pop");
      pop.setAttribute("role", "dialog");
      pop.setAttribute("aria-label", "Explain: preview");
      pop.dataset.explainPreview = "";
      pop.addEventListener("mouseleave", function () { if (!pop.contains(document.activeElement)) closePreview(); });
      document.body.appendChild(pop);
    }
    popFor = anchor;
    pop.hidden = false;
    pop.dataset.state = "loading";
    pop.textContent = "";
    pop.appendChild(h("p", "dx-muted", "explaining…"));
    place(anchor);
    return ask(req, "preview").then(function (a) {
      if (popFor !== anchor) return;
      pop.textContent = "";
      var x = h("button", "dx-x", "×");
      x.type = "button";
      x.setAttribute("aria-label", "Close the preview");
      x.addEventListener("click", closePreview);
      pop.appendChild(x);
      var head = h("p", "dx-pop-h");
      head.appendChild(h("b", null, a.title + " = " + a.text + (a.unit ? " " + a.unit : "")));
      pop.appendChild(head);
      pop.appendChild(h("p", null, a.preview || ""));
      var facts = (a.facts || []).slice();
      if (a.stored) facts.unshift("stored figure");
      if (facts.length) pop.appendChild(h("p", "dx-muted", facts.join(" · ")));
      var links = h("p", "dx-pop-links");
      TABS.forEach(function (t, i) {
        if (i) links.appendChild(document.createTextNode(" · "));
        var l = h("a", null, t[1] === "Inputs & lineage" ? "Inputs" : t[1]);
        l.href = pageUrl(req, t[0]);
        l.dataset.explainLink = t[0];
        l.addEventListener("click", function (ev) { ev.preventDefault(); closePreview(); open(req, t[0], anchor); });
        links.appendChild(l);
      });
      pop.appendChild(links);
      pop.dataset.state = "ready";
      place(anchor);
    }).catch(function (e) {
      if (popFor !== anchor) return;
      pop.textContent = "";
      pop.appendChild(h("p", "dx-err", e.message || String(e)));
      pop.dataset.state = "failed";
    });
  }
  function place(anchor) {
    if (window.innerWidth <= 600) { pop.classList.add("dx-sheet"); return; }       // a bottom sheet on a phone
    pop.classList.remove("dx-sheet");
    var r = anchor.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left + window.scrollX, window.scrollX + window.innerWidth - pop.offsetWidth - 8)) + "px";
    pop.style.top = (r.bottom + window.scrollY + 6) + "px";
  }

  // ---------------------------------------------------------------------------------------------------------------- the drawer (A)
  function ensureDrawer() {
    if (drawer) return drawer;
    drawer = h("aside", "dx-drawer");
    drawer.setAttribute("role", "dialog");
    drawer.setAttribute("aria-modal", "false");
    drawer.setAttribute("aria-labelledby", "dxTitle");
    drawer.tabIndex = -1;
    drawer.hidden = true;
    drawer.dataset.explainDrawer = "";
    drawer.innerHTML = '<div class="dx-head"><div><div class="dx-crumb" data-dx-crumb></div><h2 id="dxTitle" data-dx-title></h2>'
      + '<p class="dx-origin" data-dx-origin></p></div><button type="button" class="dx-x" data-dx-close aria-label="Close Explain (Esc)" title="Close (Esc)">✕</button></div>'
      + '<div class="dx-tabs" role="tablist" aria-label="Explain" data-dx-tabs></div><div class="dx-body" data-dx-body role="tabpanel"></div>'
      + '<div class="dx-foot"><div data-dx-checks></div><a class="dx-page" data-dx-page href="#">Open as a page ›</a></div>';
    var tabs = drawer.querySelector("[data-dx-tabs]");
    TABS.forEach(function (t) {
      var b = h("button", "dx-tab", t[1]);
      b.type = "button";
      b.setAttribute("role", "tab");
      b.dataset.dxTab = t[0];
      b.addEventListener("click", function () { show(t[0]); });
      tabs.appendChild(b);
    });
    tabs.addEventListener("keydown", function (ev) {
      if (ev.key !== "ArrowRight" && ev.key !== "ArrowLeft") return;
      var i = TABS.map(function (t) { return t[0]; }).indexOf(S.tab);
      i = (i + (ev.key === "ArrowRight" ? 1 : TABS.length - 1)) % TABS.length;
      show(TABS[i][0]);
      tabs.querySelector('[data-dx-tab="' + TABS[i][0] + '"]').focus();
    });
    drawer.querySelector("[data-dx-close]").addEventListener("click", close);
    document.body.appendChild(drawer);
    return drawer;
  }

  function open(req, tab, anchor) {
    ensureDrawer();
    clearTimeout(hoverTimer);                                        // a hover that began before the click opens no preview over the drawer
    closePreview();
    if (anchor) lastAnchor = anchor;
    S.req = req;
    S.view = null;
    S.from = req.from || "";
    S.to = req.to || "";
    var bar = document.querySelector("header.tbar");                 // below the console's top bar: the command line stays usable
    drawer.style.top = window.innerWidth > 640 && bar ? Math.max(0, Math.round(bar.getBoundingClientRect().bottom)) + "px" : "";
    drawer.hidden = false;
    drawer.classList.add("dx-open");
    drawer.querySelector("[data-dx-page]").href = pageUrl(req, tab || "formula");
    document.body.dataset.explainOpen = "1";
    show(tab || "formula");
    drawer.focus();
  }

  function close() {
    if (!drawer || drawer.hidden) return;
    drawer.hidden = true;
    drawer.classList.remove("dx-open");
    delete document.body.dataset.explainOpen;
    if (lastAnchor && document.contains(lastAnchor)) lastAnchor.focus();
  }

  function show(tab) {
    S.tab = tab;
    var seq = ++S.seq;
    drawer.querySelectorAll("[data-dx-tab]").forEach(function (b) {
      var on = b.dataset.dxTab === tab;
      b.setAttribute("aria-selected", on ? "true" : "false");
      b.tabIndex = on ? 0 : -1;
    });
    drawer.querySelector("[data-dx-page]").href = pageUrl(S.req, tab);
    var body = drawer.querySelector("[data-dx-body]");
    body.textContent = "";
    body.appendChild(h("p", "dx-muted", tab === "why" ? "computing why it moved…" : "explaining…"));
    drawer.dataset.state = "loading";
    var extra = tab === "why" ? { view: S.view, from: S.from, to: S.to } : null;
    return ask(S.req, tab, extra).then(function (a) {
      if (seq !== S.seq) return;
      heading(a);
      body.textContent = "";
      return part(body, a, tab, true).then(function () {
        if (seq !== S.seq) return;
        checks(drawer.querySelector("[data-dx-checks]"), a);
        drawer.dataset.state = "ready";
        drawer.dataset.tab = tab;
        document.dispatchEvent(new CustomEvent("drishti:explained", { detail: { tab: tab, kind: a.kind, measure: a.measure } }));
      });
    }).catch(function (e) {
      if (seq !== S.seq) return;
      body.textContent = "";
      body.appendChild(h("p", "dx-err", e.message || String(e)));
      drawer.dataset.state = "failed";
    });
  }

  function heading(a) {
    drawer.querySelector("[data-dx-crumb]").textContent = "Explain · " + a.subject + (a.asOf ? " · " + a.asOf : "");
    drawer.querySelector("[data-dx-title]").textContent = a.title + ": " + a.text + (a.unit ? " " + a.unit : "");
    drawer.querySelector("[data-dx-origin]").textContent = a.origin || "";
    drawer.querySelector("[data-dx-origin]").classList.toggle("dx-stored", !!a.stored);
  }

  // ---------------------------------------------------------------------------------------------------------------- the four parts
  function uc(host, u) {
    var p = h("p", "drishti_uc-banner");
    p.dataset.dxUc = u.id;
    var chip = h("span", "drishti_uc-chip", u.text || "Under construction");
    p.appendChild(chip);
    p.appendChild(document.createTextNode(" " + (u.about || "") + (u.release ? " (" + u.release + ")" : "")));
    host.appendChild(p);
  }

  /** One part of an answer into host; a promise (why it moved is painted by the renderer). */
  function part(host, a, tab, inDrawer) {
    var x = a[tab] || {};
    if (tab === "formula") {
      var pre = h("pre", "dx-formula");
      pre.textContent = (x.lines || []).join("\n");
      host.appendChild(pre);
      if (x.rachana) {
        var r = h("p", "dx-muted");
        r.appendChild(document.createTextNode("In Rachana (Pivot, BI, Sutras): "));
        r.appendChild(h("code", null, x.rachana));
        host.appendChild(r);
      }
      if (a.preview) host.appendChild(h("p", "dx-muted", a.preview));
    } else if (tab === "inputs") {
      var dl = h("dl", "dx-dl");
      (x.rows || []).forEach(function (row) { dl.appendChild(h("dt", null, row.label)); dl.appendChild(h("dd", "mono", row.value)); });
      host.appendChild(dl);
      if ((x.lineage || []).length) {
        host.appendChild(h("h3", "dx-h3", "Lineage"));
        var t = h("table", "dx-table");
        t.innerHTML = "<thead><tr><th>Source</th><th>What was read</th><th>Version</th></tr></thead>";
        var tb = h("tbody");
        x.lineage.forEach(function (l) {
          var tr = h("tr");
          tr.appendChild(h("td", "mono", l.source));
          tr.appendChild(h("td", null, l.read));
          tr.appendChild(h("td", "mono", l.version));
          tb.appendChild(tr);
        });
        t.appendChild(tb);
        host.appendChild(t);
      }
    } else if (tab === "workbook") {
      if (x.underConstruction) uc(host, x.underConstruction);
      var ul = h("ul", "dx-links");
      (x.links || []).forEach(function (l) {
        var li = h("li");
        var link = h("a", "dx-wb", l.label);
        link.href = l.href;
        link.dataset.dxWorkbook = "";
        li.appendChild(link);
        if (l.note) li.appendChild(h("span", "dx-muted", " · " + l.note));
        ul.appendChild(li);
      });
      if ((x.links || []).length) host.appendChild(ul);
      else if (!x.underConstruction) host.appendChild(h("p", "dx-muted", "No workbook for this number."));
      (a.links || []).forEach(function (l) {
        var p = h("p", "dx-muted");
        var link = h("a", null, l.label + " ›");
        link.href = l.href;
        p.appendChild(link);
        host.appendChild(p);
      });
    } else if (tab === "why") {
      return why(host, a, x, inDrawer);
    }
    return Promise.resolve();
  }

  function why(host, a, x, inDrawer) {
    if (!x.available) {
      if (x.underConstruction) uc(host, x.underConstruction);
      if (x.note) host.appendChild(h("p", "dx-muted", x.note));
      return Promise.resolve();
    }
    var bar = h("div", "dx-why-bar");
    if (inDrawer && (x.views || []).length > 1) {
      var seg = h("span", "dx-seg");
      seg.setAttribute("role", "group");
      seg.setAttribute("aria-label", "View");
      x.views.forEach(function (v) {
        var b = h("button", "dx-segb", v.charAt(0).toUpperCase() + v.slice(1));
        b.type = "button";
        b.dataset.dxView = v;
        b.setAttribute("aria-pressed", v === x.view ? "true" : "false");
        b.addEventListener("click", function () { S.view = v; show("why"); });
        seg.appendChild(b);
      });
      bar.appendChild(seg);
    }
    if (inDrawer) {
      var f = h("form", "dx-dates");
      f.innerHTML = '<label>From <input type="date" data-dx-from aria-label="From: the earlier business date"></label>'
        + '<label>To <input type="date" data-dx-to aria-label="To: the later business date"></label><button type="submit" class="dx-btn">Explain</button>';
      f.querySelector("[data-dx-from]").value = x.from || "";
      f.querySelector("[data-dx-to]").value = x.to || "";
      f.addEventListener("submit", function (ev) {
        ev.preventDefault();
        S.from = f.querySelector("[data-dx-from]").value || "";
        S.to = f.querySelector("[data-dx-to]").value || "";
        show("why");
      });
      bar.appendChild(f);
    }
    host.appendChild(bar);
    var paint = h("div", "dx-why");
    paint.dataset.dxWhy = x.view || "";
    host.appendChild(paint);
    var model = { contract: "1.0", ref: { kind: "explain", id: a.kind + ":" + a.subject }, title: { pill: "Why it moved", id: a.title + " · " + x.from + " to " + x.to },
                  strip: [], panels: x.panels || [], keys: [], provenance: { source: "the server's Explain, GET /api/v1/explain?kind=" + a.kind } };
    lib = lib || import("/static/render/2.0.0/drishti-render.js");
    return lib.then(function (R) {
      var v = R.render(paint, model, { theme: document.documentElement.getAttribute("data-theme") || "terminal", header: "none", help: false,
                                       tables: true, live: false, navigation: "host", device: window.innerWidth <= 640 ? "phone" : "desk" });
      return v.ready;
    }).then(function () { paint.dataset.painted = x.view || "waterfall"; });
  }

  function checks(host, a) {
    host.textContent = "";
    var list = (a.checks || []).slice();
    if (!list.length && !(a.underConstruction || []).length) return;
    host.appendChild(h("h3", "dx-h3", "Checks"));
    list.forEach(function (c) {
      var p = h("p", c.ok === true ? "dx-ok" : c.ok === false ? "dx-bad" : "dx-muted", (c.ok === true ? "✓ " : c.ok === false ? "✗ " : "· ") + c.text);
      host.appendChild(p);
    });
    (a.underConstruction || []).forEach(function (u) { uc(host, u); });
  }

  // ---------------------------------------------------------------------------------------------------------------- the full page (C)
  function renderPage(root) {
    var req = {};
    try { req = JSON.parse(root.getAttribute("data-explain-request") || "{}"); } catch (e) { req = {}; }
    var first = root.getAttribute("data-explain-tab") || "";
    root.dataset.state = "loading";
    return ask(req, "all").then(function (a) {
      root.querySelector("[data-dx-title]").textContent = a.title + ": " + a.text + (a.unit ? " " + a.unit : "");
      root.querySelector("[data-dx-crumb]").textContent = "Explain · " + a.subject + (a.asOf ? " · " + a.asOf : "");
      root.querySelector("[data-dx-origin]").textContent = a.origin || "";
      document.title = "Explain · " + a.title + (document.body.dataset.product ? " · " + document.body.dataset.product : "");
      var grid = root.querySelector("[data-dx-grid]");
      grid.textContent = "";
      var jobs = TABS.map(function (t) {
        var card = h("section", "dx-card");
        card.dataset.dxPart = t[0];
        card.id = "dx-" + t[0];
        card.appendChild(h("h2", null, t[1]));
        grid.appendChild(card);
        return part(card, a, t[0], false);
      });
      checks(root.querySelector("[data-dx-checks]"), a);
      return Promise.all(jobs).then(function () {
        root.dataset.state = "ready";
        if (first) { var c = root.querySelector("#dx-" + first); if (c) c.scrollIntoView(); }
      });
    }).catch(function (e) {
      root.querySelector("[data-dx-grid]").textContent = e.message || String(e);
      root.dataset.state = "failed";
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- the page's numbers
  function target(ev) { return ev.target && ev.target.closest ? ev.target.closest("[data-explain]") : null; }
  document.addEventListener("click", function (ev) {
    var el = target(ev);
    if (el && !(ev.target.closest("a[href], [data-explain-skip]") && !ev.target.closest("[data-explain-trigger]"))) {
      var req = reqOf(el);
      if (req && req.kind) { ev.preventDefault(); ev.stopPropagation(); open(req, el.getAttribute("data-explain-tab") || "formula", el); }
      return;
    }
    if (pop && !pop.hidden && !pop.contains(ev.target)) closePreview();
  }, true);
  document.addEventListener("mouseover", function (ev) {
    var el = target(ev);
    if (!el || el === popFor || !(window.matchMedia && window.matchMedia("(hover: hover)").matches)) return;
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(function () { var req = reqOf(el); if (req && req.kind && el.matches(":hover") && !(drawer && !drawer.hidden)) { lastAnchor = el; preview(el, req); } }, 350);
  });
  document.addEventListener("mouseout", function (ev) { if (target(ev)) clearTimeout(hoverTimer); });
  // the keys (the one keymap, drishti_keys.js): Escape closes the preview, then the drawer; E explains the focused number (else the
  // preview's, else the page's main one); ? on a focused number previews it (anywhere else ? is the keymap's list)
  var K = window.Drishti.keys;
  var focusedNumber = function () { return document.activeElement && document.activeElement.closest ? document.activeElement.closest("[data-explain]") : null; };
  K.register({ id: "explain.preview.close", keys: ["Escape"], scope: "dialog", area: "explain-preview", description: "Close the preview",
    typing: true, keepDefault: true, open: function () { return !!(pop && !pop.hidden); }, run: function () { closePreview(); } });
  K.register({ id: "explain.close", keys: ["Escape"], scope: "dialog", area: "explain", description: "Close Explain",
    open: function () { return !!(drawer && !drawer.hidden); }, run: function () { close(); } });
  K.register({ id: "explain.open", keys: ["E", "Shift+E"], scope: "global", group: "Explain", description: "Explain the number: its formula, inputs, why it moved, the workbook",
    run: function () {
      var el = focusedNumber() || (popFor && document.contains(popFor) ? popFor : null) || document.querySelector("[data-explain-primary][data-explain]");
      var req = el ? reqOf(el) : null;
      if (!(req && req.kind)) return false;
      open(req, el.getAttribute("data-explain-tab") || "formula", el);
    } });
  K.register({ id: "explain.preview", keys: ["?"], scope: "panel", area: "explain-number", group: "Explain", description: "On a number: preview how it was made",
    within: focusedNumber, run: function () {
      var f = focusedNumber(), r2 = f ? reqOf(f) : null;
      if (!(r2 && r2.kind)) return false;
      lastAnchor = f; preview(f, r2);
    } });

  var pageRoot = document.querySelector("[data-explain-page]");
  if (pageRoot) {
    renderPage(pageRoot);
    var printBtn = pageRoot.querySelector("[data-dx-print]");
    if (printBtn) printBtn.addEventListener("click", function () { window.print(); });
  }

  window.Drishti.Explain = {
    open: open, close: close, preview: preview, pageUrl: pageUrl,
    /** Marks el as an explainable number: hover previews, click opens the drawer (on tab), E opens it when focused. */
    bind: function (el, req, tab) {
      el.setAttribute("data-explain", JSON.stringify(req));
      if (tab) el.setAttribute("data-explain-tab", tab);
      if (!el.hasAttribute("tabindex") && !/A|BUTTON/.test(el.tagName)) el.tabIndex = 0;
      el.classList.add("dx-number");
      return el;
    },
    isOpen: function () { return !!(drawer && !drawer.hidden); },
    state: S
  };
})();
