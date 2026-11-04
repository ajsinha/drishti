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
/* The VaR screen's layout (docs/guides/VAR_GUIDE.md section 3.1; the approved mock-up docs/lenses/mockups/var-layout/A.html and
   A-drawer.html). Two things, nothing else:

   - the baskets in two searchable drop-downs in the toolbar: System baskets (the bank's portfolios, from the market-risk pack's
     portfolio feed) and My baskets (the baskets saved, mine and those shared with me). Each is a button that opens a popup with a
     combobox (type to search the name and the description) over a listbox: ↑↓ move, Enter opens the scope, Esc closes; B and M open
     them from anywhere on the page. The rows and their actions (+ basket, Edit, Edit a copy, Delete) are the explorer's own
     (drishti_var-explorer.js hands them over with setItems); a choice moves the explorer (Drishti.VarExplorer.select);
   - How calculated (H): the provenance box as a drawer over the right of the screen (a bottom sheet on a phone), its content the
     explorer's as before (the server's text and figures; the browser computes nothing). Esc or ✕ closes it and the focus goes back to
     the link; its state is in the page's link (how=1), so a link opens it. */
(function () {
  "use strict";
  var root = document.querySelector("[data-var]");
  if (!root) return;
  var drawer = root.querySelector("[data-vx-drawer]"), how = root.querySelector("[data-vx-how]");
  var P = {}, current = "";

  function h(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function dialogOpen() { return !!document.querySelector("dialog[open]"); }

  // ---------------------------------------------------------------------------------------------------------------- the drop-downs
  root.querySelectorAll("[data-vx-pick]").forEach(function (el) {
    var key = el.dataset.vxPick;
    var p = P[key] = { key: key, el: el, btn: el.querySelector("[data-vx-pick-btn]"), pop: el.querySelector("[data-vx-pick-pop]"),
                       q: el.querySelector("[data-vx-pick-q]"), list: el.querySelector("[role=listbox]"), cur: el.querySelector("[data-vx-pick-cur]"),
                       n: el.querySelector("[data-vx-pick-n]"), items: [], active: -1, loaded: false };
    p.btn.addEventListener("click", function () { if (p.pop.hidden) open(key); else close(key, true); });
    p.btn.addEventListener("keydown", function (ev) {
      if (ev.key === "ArrowDown" && p.pop.hidden) { ev.preventDefault(); open(key); }
    });
    p.q.addEventListener("input", function () { filter(p); });
    p.q.addEventListener("keydown", function (ev) {
      var shown = visible(p);
      if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
        ev.preventDefault();
        if (!shown.length) return;
        var at = shown.indexOf(p.list.querySelector(".vx-opt.hi"));
        at = at < 0 ? (ev.key === "ArrowDown" ? 0 : shown.length - 1) : (at + (ev.key === "ArrowDown" ? 1 : shown.length - 1)) % shown.length;
        highlight(p, shown[at]);
      } else if (ev.key === "Enter") {
        ev.preventDefault();
        var hi = p.list.querySelector(".vx-opt.hi:not([hidden])") || (shown.length === 1 ? shown[0] : null);
        if (hi) choose(p, hi.dataset.scope);
      } else if (ev.key === "Escape") {
        ev.preventDefault();
        ev.stopPropagation();
        close(key, true);
      }
    });
    p.pop.addEventListener("keydown", function (ev) {           // Esc on a row's button closes the drop-down too
      if (ev.key === "Escape" && ev.target !== p.q && !dialogOpen()) { ev.stopPropagation(); close(key, true); }
    });
    p.list.addEventListener("click", function (ev) {
      if (ev.target.closest("[data-vx-edit], [data-vx-add]")) { setTimeout(function () { close(key, false); }, 0); return; }   // the sheet opens
      if (ev.target.closest("button")) return;                   // Delete: its dialog, the list stays
      var o = ev.target.closest(".vx-opt");
      if (o) choose(p, o.dataset.scope);
    }, true);
    p.list.addEventListener("mousemove", function (ev) { var o = ev.target.closest(".vx-opt"); if (o && !o.classList.contains("hi")) highlight(p, o); });
    el.addEventListener("focusout", function (ev) {
      var to = ev.relatedTarget;
      if (to && !el.contains(to) && !to.closest("dialog")) close(key, false);
    });
    var nb = el.querySelector("[data-vx-basket-new]");
    if (nb) nb.addEventListener("click", function () {
      close(key, false);
      var opener = root.querySelector("[data-vx-basket-open]");
      if (opener) opener.click();
    });
  });
  document.addEventListener("mousedown", function (ev) {
    Object.keys(P).forEach(function (k) { if (!P[k].pop.hidden && !P[k].el.contains(ev.target) && !ev.target.closest("dialog")) close(k, false); });
  });

  function open(key) {
    Object.keys(P).forEach(function (k) { if (k !== key) close(k, false); });
    var p = P[key];
    p.pop.hidden = false;
    p.btn.setAttribute("aria-expanded", "true");
    p.q.setAttribute("aria-expanded", "true");
    p.q.value = "";
    filter(p);
    var on = p.list.querySelector('.vx-opt[aria-selected="true"]');
    if (on) highlight(p, on);
    p.q.focus();
  }
  function close(key, focusButton) {
    var p = P[key];
    if (!p || p.pop.hidden) return;
    p.pop.hidden = true;
    p.btn.setAttribute("aria-expanded", "false");
    p.q.setAttribute("aria-expanded", "false");
    p.q.removeAttribute("aria-activedescendant");
    p.list.querySelectorAll(".vx-opt.hi").forEach(function (o) { o.classList.remove("hi"); });
    if (focusButton) p.btn.focus();
  }
  function choose(p, scope) {
    close(p.key, true);
    if (scope && window.Drishti.VarExplorer) window.Drishti.VarExplorer.select(scope);
  }
  function visible(p) { return Array.prototype.slice.call(p.list.querySelectorAll(".vx-opt:not([hidden])")); }
  function highlight(p, o) {
    p.list.querySelectorAll(".vx-opt.hi").forEach(function (x) { x.classList.remove("hi"); });
    o.classList.add("hi");
    p.q.setAttribute("aria-activedescendant", o.id);
    o.scrollIntoView({ block: "nearest" });
  }

  /** Type to search: the name and the description (and the id, the kind, the owner), any order of the words typed. */
  function filter(p) {
    var words = p.q.value.toLowerCase().split(/\s+/).filter(Boolean), n = 0;
    p.list.querySelectorAll(".vx-opt").forEach(function (o) {
      var hay = o.dataset.find || "";
      var ok = words.every(function (w) { return hay.indexOf(w) >= 0; });
      o.hidden = !ok;
      if (ok) n++;
      mark(o.querySelector(".lbl"), o.dataset.label, words[0] || "");
      if (!ok && o.classList.contains("hi")) { o.classList.remove("hi"); p.q.removeAttribute("aria-activedescendant"); }
    });
    p.list.querySelectorAll(".vx-grp").forEach(function (g) {
      var any = false;
      for (var s = g.nextElementSibling; s && !s.classList.contains("vx-grp"); s = s.nextElementSibling) if (s.classList.contains("vx-opt") && !s.hidden) any = true;
      g.hidden = !any;
    });
    var none = p.list.querySelector(".vx-none");
    if (none) none.remove();
    if (!n) {
      none = h("li", "vx-none", !p.loaded ? "loading…" : p.items.length ? "No basket matches “" + p.q.value + "”." : p.key === "mine"
        ? "No baskets yet: + New basket builds one." : "The system provides no baskets on this date.");
      none.setAttribute("role", "presentation");
      p.list.appendChild(none);
    }
    p.n.textContent = n === p.items.length ? n + (n === 1 ? " basket" : " baskets") : n + " of " + p.items.length;
  }
  /** The first match of the word typed, marked in the name (text nodes only: a name is never read as HTML). */
  function mark(lbl, text, word) {
    if (!lbl) return;
    lbl.textContent = "";
    var at = word ? text.toLowerCase().indexOf(word) : -1;
    if (at < 0) { lbl.textContent = text; return; }
    lbl.appendChild(document.createTextNode(text.slice(0, at)));
    lbl.appendChild(h("mark", null, text.slice(at, at + word.length)));
    lbl.appendChild(document.createTextNode(text.slice(at + word.length)));
  }

  /**
   * The explorer's rows for a drop-down: [{scope, label, desc, meta, chip, group, acts: [buttons]}]. A group starts where an item's
   * group differs from the one before ("Mine", "Shared with me"). The open drop-down keeps its search.
   */
  function setItems(key, items) {
    var p = P[key];
    if (!p) return;
    p.items = items;
    p.loaded = true;
    p.list.textContent = "";
    var group = null;
    items.forEach(function (it, i) {
      if (it.group && it.group !== group) {
        group = it.group;
        var g = h("li", "vx-grp", group);
        g.setAttribute("role", "presentation");
        p.list.appendChild(g);
      }
      var o = h("li", "vx-opt");
      o.id = "vxOpt-" + key + "-" + i;
      o.setAttribute("role", "option");
      o.setAttribute("aria-selected", it.scope === current ? "true" : "false");
      o.dataset.scope = it.scope;
      o.dataset.label = it.label;
      o.dataset.find = [it.label, it.desc, it.meta, it.chip, it.scope].join(" ").toLowerCase();
      o.title = it.label + (it.desc ? ": " + it.desc : "");
      var txt = h("span", "vx-opt-t");
      txt.appendChild(h("span", "lbl", it.label));
      if (it.desc || it.meta) txt.appendChild(h("span", "vx-opt-d", [it.desc, it.meta].filter(Boolean).join(" · ")));
      o.appendChild(txt);
      if (it.chip) o.appendChild(h("span", "vx-chip", it.chip));
      var acts = h("span", "vx-acts");
      (it.acts || []).forEach(function (b) { acts.appendChild(b); });
      o.appendChild(acts);
      p.list.appendChild(o);
    });
    filter(p);
    markCurrent(current);
  }

  /** The scope on the page: chosen in its drop-down (its name on the button), "choose…" in the other. */
  function markCurrent(scope) {
    current = scope || "";
    Object.keys(P).forEach(function (k) {
      var p = P[k], label = "";
      p.list.querySelectorAll(".vx-opt").forEach(function (o) {
        var on = o.dataset.scope === current;
        o.setAttribute("aria-selected", on ? "true" : "false");
        if (on) label = o.dataset.label;
      });
      p.cur.textContent = label || "choose…";
      p.cur.classList.toggle("muted", !label);
      p.el.classList.toggle("on", !!label);
    });
  }

  // ---------------------------------------------------------------------------------------------------------------- the drawer
  function isOpen() { return drawer && !drawer.hidden; }
  function setLink(on) {
    var u = new URL(location.href);
    if (on) u.searchParams.set("how", "1"); else u.searchParams.delete("how");
    if (u.toString() !== location.href) history.replaceState(history.state, "", u.toString());
  }
  function openHow(focus) {
    if (!drawer) return;
    drawer.hidden = false;
    drawer.classList.add("vx-open", "ph-open");                  // ph-open: a phone's More part shown (MOBILE_DISPLAY.md 8)
    how.setAttribute("aria-expanded", "true");
    how.classList.add("on");
    setLink(true);
    if (focus !== false) { var x = drawer.querySelector("[data-vx-how-close]"); (x || drawer).focus(); }
  }
  function closeHow() {
    if (!drawer) return;
    var inside = drawer.contains(document.activeElement) || document.activeElement === document.body;
    drawer.hidden = true;
    drawer.classList.remove("vx-open", "ph-open");
    how.setAttribute("aria-expanded", "false");
    how.classList.remove("on");
    setLink(false);
    if (inside) how.focus();
  }
  /** The drawer starts below the console's top bar (header.tbar, sticky): its measured height is --vx-header-h, kept current as the bar
      wraps or folds (ResizeObserver), so the command line, the date and the bar's icons stay visible and clickable. */
  var bar = document.querySelector("header.tbar");
  function headerHeight() {
    root.style.setProperty("--vx-header-h", (bar ? Math.max(0, Math.round(bar.getBoundingClientRect().bottom)) : 0) + "px");
  }
  headerHeight();
  if (bar && window.ResizeObserver) new ResizeObserver(headerHeight).observe(bar);
  window.addEventListener("resize", headerHeight);
  window.addEventListener("scroll", headerHeight, { passive: true });
  if (how) how.addEventListener("click", function () { if (isOpen()) closeHow(); else openHow(); });
  var x = drawer && drawer.querySelector("[data-vx-how-close]");
  if (x) x.addEventListener("click", closeHow);
  if (isOpen()) { drawer.classList.add("ph-open"); }
  // a phone's More on this page: "How this number was built" opens the bottom sheet
  document.addEventListener("click", function (ev) {
    if (ev.target.closest && ev.target.closest('[data-phone-reveal="prov"]')) openHow();
  }, true);
  window.addEventListener("popstate", function () {
    var on = new URLSearchParams(location.search).get("how") === "1";
    if (on !== isOpen()) { if (on) openHow(false); else closeHow(); }
  });

  // ---------------------------------------------------------------------------------------------------------------- keys B, M, H, Esc
  function typing(t) {
    return !!(t && t.closest && (t.closest("input, textarea, select, [contenteditable=''], [contenteditable=true], [data-var-basket], dialog")));
  }
  document.addEventListener("keydown", function (ev) {
    if (ev.defaultPrevented || ev.ctrlKey || ev.metaKey || ev.altKey || dialogOpen()) return;
    if (ev.key === "Escape") {
      if (isOpen() && !typing(ev.target)) { ev.preventDefault(); closeHow(); }
      return;
    }
    if (typing(ev.target)) return;
    var k = ev.key.toLowerCase();
    if (k === "b" && P.system) { ev.preventDefault(); open("system"); }
    else if (k === "m" && P.mine) { ev.preventDefault(); open("mine"); }
    else if (k === "h" && drawer) { ev.preventDefault(); if (isOpen()) closeHow(); else openHow(); }
  });

  window.Drishti.VarLayout = { setItems: setItems, mark: markCurrent, open: open, close: close, openHow: openHow, closeHow: closeHow, isOpen: isOpen };
})();
