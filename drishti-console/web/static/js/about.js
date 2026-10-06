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
/* About this page (docs/architecture/CONTEXT_HELP.md): a drawer on a view that says where the data came from, why the page looks
   like this and where next. The body is an HTML fragment (GET /v/{kind}/{id}/about, which asks the server's explain endpoint),
   fetched the first time the drawer opens and never with the view. The drawer is the page's one side drawer (COLLABORATION.md, Decision 9): it has
   two tabs, About and Discussion (discussion.js); this file owns the host (open, close, focus, the phone sheet, the tabs) and the About tab. `?` toggles it, F1 opens it (F1 again, inside it, goes to the
   screen guide as everywhere: app.js). The same fetch feeds the panel popovers and field hints (about-hints.js). On a phone it is a bottom sheet that traps the focus; Esc closes it and the focus goes back. */
(function () {
  'use strict';
  var me = document.currentScript;
  /** init(root, options): the About drawer under root (the document or a ShadowRoot: the element renders its own drawer markup).
      options: fetch(url, init) and url(path) (default: the page's own), reload() (the drawer's "refresh" link; default: reload the
      page), guideKey (false: F1 is not ours, the element has no screen guide). Returns the drawer's API (also window.drishtiAbout
      when the root is the document) or null when root has no drawer or view. Keys are bound on root, so a host page's own keys
      are never touched. */
  function init(root, options) {
  options = options || {};
  var doFetch = options.fetch || function (u, o) { return fetch(u, o); };
  var toUrl = options.url || function (p) { return p; };
  var setHtml = options.setHtml || function (el, html) { el.innerHTML = html; };       // the element passes its sanitising, Trusted Types writer
  var drawer = root.getElementById('aboutDrawer');
  var view = root.querySelector('[data-view]');
  if (!drawer || !view) { return null; }
  var body = drawer.querySelector('[data-about-body]');
  var heading = drawer.querySelector('#aboutTitle');
  var status = drawer.querySelector('[data-about-status]');
  var openers = root.querySelectorAll('[data-about-open]');
  var phone = window.matchMedia ? window.matchMedia('(max-width: 640px)') : { matches: false, addEventListener: function () {} };
  var STORE = 'drishti.about.layers';
  var tabEls = Array.prototype.slice.call(drawer.querySelectorAll('[role="tab"]')), tabPanels = drawer.querySelectorAll('[data-tab-panel]');
  var loaded = false, inflight = null, timer = null, opener = null, shownGen = '', current = 'about';

  function remembered() { try { return JSON.parse(localStorage.getItem(STORE) || '{}'); } catch (e) { return {}; } }
  function remember(layer, open) {
    var m = remembered(); m[layer] = open;
    try { localStorage.setItem(STORE, JSON.stringify(m)); } catch (e) { /* storage blocked: a convenience only */ }
  }
  function applyRemembered() {
    var m = remembered();
    body.querySelectorAll('details[data-layer]').forEach(function (d) {
      var k = d.getAttribute('data-layer');
      if (k in m) { d.open = !!m[k]; }
      d.addEventListener('toggle', function () { remember(k, d.open); });
    });
  }

  function url() {
    var g = view.getAttribute('data-generation');
    return drawer.getAttribute('data-about-url') + (g ? '?generation=' + encodeURIComponent(g) : '');
  }

  var index = null;
  function parseIndex() {
    var card = body.querySelector('[data-about-index]');
    try { index = card ? JSON.parse(card.getAttribute('data-about-index')) : null; } catch (e) { index = null; }
  }
  // One fetch at a time; the promise answers when the body is drawn (and the index parsed). Never rejects.
  function load(quiet) {
    if (inflight) { return inflight; }
    var before = (body.querySelector('[data-layer="data"]') || {}).textContent;
    inflight = doFetch(toUrl(url()), { credentials: 'same-origin', headers: { Accept: 'text/html' } })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        setHtml(body, html); loaded = true; applyRemembered(); parseIndex();
        var card = body.querySelector('[data-about-generation]');
        shownGen = card ? card.getAttribute('data-about-generation') : '';
        var refresh = body.querySelector('[data-about-refresh]');
        if (refresh) { refresh.addEventListener('click', function (e) { e.preventDefault(); if (options.reload) { options.reload(); } else { location.reload(); } }); }
        if (quiet && status && before !== (body.querySelector('[data-layer="data"]') || {}).textContent) { status.textContent = 'About this page updated'; }
        root.dispatchEvent(new CustomEvent('drishti:about', { detail: index }));
      })
      .catch(function () { setHtml(body, '<p class="about-error" role="alert">About this page could not be loaded. Try again.</p>'); index = null; })
      .then(function () { inflight = null; });
    return inflight;
  }
  // The glossary and panel text of this page for the panel popovers and field hints (about-hints.js): fetched once, shared with the drawer.
  function ensure() { return loaded ? Promise.resolve(index) : load(false).then(function () { return index; }); }
  // Opens the drawer at one entry: a glossary term (by field path) or an authored panel text (by panel id).
  function reveal(what) {
    open();
    ensure().then(function () {
      var el = what.term ? body.querySelector('[data-term="' + what.term.replace(/"/g, '') + '"]')
        : body.querySelector('[data-about-panel="' + String(what.panel || '').replace(/"/g, '') + '"]') || body.querySelector('[data-layer="glossary"] summary');
      if (!el) { return; }
      var d = el.closest('details'); if (d) { d.open = true; }
      el.setAttribute('tabindex', '-1');
      el.scrollIntoView({ block: 'nearest' });
      el.focus({ preventScroll: true });
    });
  }
  var api = { ensure: ensure, reveal: reveal, isOpen: function () { return !drawer.hidden; }, open: function (t) { open(t); }, close: function () { close(); },
    setTab: function (t) { setTab(t); }, tab: function () { return current; } };
  function isOpen() { return !drawer.hidden; }
  function announce() {
    openers.forEach(function (b) { b.setAttribute('aria-expanded', isOpen() && current === 'about' ? 'true' : 'false'); });
    root.dispatchEvent(new CustomEvent('drishti:drawer', { detail: { open: isOpen(), tab: current } }));
  }
  // the drawer shows one tab at a time: About (this file) or Discussion (discussion.js)
  function setTab(name) {
    current = name === 'discussion' ? 'discussion' : 'about';
    tabEls.forEach(function (t) { var on = t.getAttribute('data-tab') === current; t.setAttribute('aria-selected', on ? 'true' : 'false'); t.tabIndex = on ? 0 : -1; if (on) { heading.textContent = t.getAttribute('data-title'); } });
    tabPanels.forEach(function (p) { p.hidden = p.getAttribute('data-tab-panel') !== current; });
    drawer.setAttribute('data-tab', current);
    if (current === 'about' && isOpen() && !loaded) { load(false); }
    announce();
  }
  function open(tab) {
    if (isOpen()) { if (tab) { setTab(tab); } return; }
    opener = root.activeElement;
    drawer.hidden = false;
    drawer.setAttribute('aria-modal', phone.matches ? 'true' : 'false');
    setTab(tab || 'about');
    if (current === 'about') { heading.focus({ preventScroll: true }); }
  }
  function close() {
    if (!isOpen()) { return; }
    drawer.hidden = true;
    announce();
    if (status) { status.textContent = ''; }
    var back = opener && opener.isConnected ? opener : openers[0];
    opener = null;
    if (back && back.focus) { back.focus({ preventScroll: true }); }
  }
  function toggle() { if (isOpen()) { close(); } else { open(); } }

  function typing(t) {
    return !!t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable || (t.closest && t.closest('.CodeMirror')));
  }

  // Captured, so a view's F1 reaches us before app.js (which then yields to a handled key); inside the open drawer F1 is left to app.js.
  root.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey) { return; }
    if (e.key === 'F1' && !e.altKey && !e.shiftKey && options.guideKey !== false) {
      if (isOpen() && current === 'about') { return; }
      e.preventDefault(); setTab('about'); open('about'); heading.focus({ preventScroll: true }); return;       // F1 on the Discussion tab goes to About
    }
    if (e.key === '?' && !e.altKey && !typing(e.target)) {
      e.preventDefault();
      if (isOpen() && current !== 'about') { setTab('about'); heading.focus({ preventScroll: true }); } else { toggle(); }
      return;
    }
    if (e.key === 'Escape' && isOpen() && !root.querySelector('.about-pop:not([hidden]), .about-tip:not([hidden]), .disc-opts:not([hidden])')) { e.preventDefault(); close(); return; }   // a popover over the drawer closes first (about-hints.js)
    if (e.key === 'Tab' && isOpen() && phone.matches) {                   // the bottom sheet is modal: the focus stays inside it
      var f = Array.prototype.filter.call(drawer.querySelectorAll('a[href], button:not([disabled]), summary, textarea, select, input, [tabindex="0"], #aboutTitle'),
        function (el) { return el.checkVisibility ? el.checkVisibility() : el.offsetParent !== null; });   // not inside a closed layer
      if (!f.length) { return; }
      var first = f[0], last = f[f.length - 1];
      if (!drawer.contains(root.activeElement)) { e.preventDefault(); first.focus(); }
      else if (e.shiftKey && root.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && root.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  }, true);

  openers.forEach(function (b) { b.addEventListener('click', function () { if (isOpen() && current !== 'about') { setTab('about'); } else { toggle(); } }); });
  tabEls.forEach(function (t, n) {                                      // tabs: click, or the arrow keys (the selected tab is the one in the tab order)
    t.addEventListener('click', function () { setTab(t.getAttribute('data-tab')); });
    t.addEventListener('keydown', function (e) {
      var to = e.key === 'ArrowRight' ? (n + 1) % tabEls.length : e.key === 'ArrowLeft' ? (n - 1 + tabEls.length) % tabEls.length : e.key === 'Home' ? 0 : e.key === 'End' ? tabEls.length - 1 : -1;
      if (to < 0) { return; }
      e.preventDefault(); tabEls[to].focus(); setTab(tabEls[to].getAttribute('data-tab'));
    });
  });
  drawer.querySelector('[data-about-close]').addEventListener('click', close);

  // swipe down on the sheet's handle or header closes it (phone)
  var y0 = null;
  drawer.addEventListener('touchstart', function (e) { y0 = e.target.closest('.about-grip, header') ? e.touches[0].clientY : null; }, { passive: true });
  drawer.addEventListener('touchend', function (e) {
    if (y0 !== null && phone.matches && e.changedTouches[0].clientY - y0 > 60) { close(); }
    y0 = null;
  }, { passive: true });

  // a live frame that moved the generation: ask again, at most one in flight, no more than once in two seconds
  root.addEventListener('drishti:frame', function (e) {
    var g = e.detail && e.detail.generation;
    if (g === undefined) { return; }
    view.setAttribute('data-generation', String(g));
    if (!isOpen() || current !== 'about' || String(g) === shownGen || timer) { return; }
    timer = setTimeout(function () { timer = null; if (isOpen()) { load(true); } }, 2000);
  });
  return api;
  }
  (window.drishtiModules = window.drishtiModules || {}).about = { init: init };
  if (!(me && me.hasAttribute('data-manual'))) { window.drishtiAbout = init(document) || undefined; }
})();
