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
   fetched the first time the drawer opens and never with the view. `?` toggles it, F1 opens it (F1 again, inside it, goes to the
   screen guide as everywhere: app.js). On a phone it is a bottom sheet that traps the focus; Esc closes it and the focus goes back. */
(function () {
  'use strict';
  var drawer = document.getElementById('aboutDrawer');
  var view = document.querySelector('[data-view]');
  if (!drawer || !view) { return; }
  var body = drawer.querySelector('[data-about-body]');
  var heading = drawer.querySelector('#aboutTitle');
  var status = drawer.querySelector('[data-about-status]');
  var openers = document.querySelectorAll('[data-about-open]');
  var phone = window.matchMedia ? window.matchMedia('(max-width: 640px)') : { matches: false, addEventListener: function () {} };
  var STORE = 'drishti.about.layers';
  var loaded = false, inflight = false, timer = null, opener = null, shownGen = '';

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

  function load(quiet) {
    if (inflight) { return; }
    inflight = true;
    var before = (body.querySelector('[data-layer="data"]') || {}).textContent;
    fetch(url(), { credentials: 'same-origin', headers: { Accept: 'text/html' } })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        body.innerHTML = html; loaded = true; applyRemembered();
        var card = body.querySelector('[data-about-generation]');
        shownGen = card ? card.getAttribute('data-about-generation') : '';
        var refresh = body.querySelector('[data-about-refresh]');
        if (refresh) { refresh.addEventListener('click', function (e) { e.preventDefault(); location.reload(); }); }
        if (quiet && status && before !== (body.querySelector('[data-layer="data"]') || {}).textContent) { status.textContent = 'About this page updated'; }
      })
      .catch(function () { body.innerHTML = '<p class="about-error" role="alert">About this page could not be loaded. Try again.</p>'; })
      .then(function () { inflight = false; });
  }

  function isOpen() { return !drawer.hidden; }
  function open() {
    if (isOpen()) { return; }
    opener = document.activeElement;
    drawer.hidden = false;
    drawer.setAttribute('aria-modal', phone.matches ? 'true' : 'false');
    openers.forEach(function (b) { b.setAttribute('aria-expanded', 'true'); });
    if (!loaded) { load(false); }
    heading.focus({ preventScroll: true });
  }
  function close() {
    if (!isOpen()) { return; }
    drawer.hidden = true;
    openers.forEach(function (b) { b.setAttribute('aria-expanded', 'false'); });
    if (status) { status.textContent = ''; }
    var back = opener && document.contains(opener) ? opener : openers[0];
    opener = null;
    if (back && back.focus) { back.focus({ preventScroll: true }); }
  }
  function toggle() { if (isOpen()) { close(); } else { open(); } }

  function typing(t) {
    return !!t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable || (t.closest && t.closest('.CodeMirror')));
  }

  // Captured, so a view's F1 reaches us before app.js (which then yields to a handled key); inside the open drawer F1 is left to app.js.
  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey) { return; }
    if (e.key === 'F1' && !e.altKey && !e.shiftKey) {
      if (isOpen()) { return; }
      e.preventDefault(); open(); return;
    }
    if (e.key === '?' && !e.altKey && !typing(e.target)) { e.preventDefault(); toggle(); return; }
    if (e.key === 'Escape' && isOpen()) { e.preventDefault(); close(); return; }
    if (e.key === 'Tab' && isOpen() && phone.matches) {                   // the bottom sheet is modal: the focus stays inside it
      var f = Array.prototype.filter.call(drawer.querySelectorAll('a[href], button, summary, [tabindex="0"], #aboutTitle'),
        function (el) { return el.checkVisibility ? el.checkVisibility() : el.offsetParent !== null; });   // not inside a closed layer
      if (!f.length) { return; }
      var first = f[0], last = f[f.length - 1];
      if (!drawer.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
      else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  }, true);

  openers.forEach(function (b) { b.addEventListener('click', toggle); });
  drawer.querySelector('[data-about-close]').addEventListener('click', close);

  // swipe down on the sheet's handle or header closes it (phone)
  var y0 = null;
  drawer.addEventListener('touchstart', function (e) { y0 = e.target.closest('.about-grip, header') ? e.touches[0].clientY : null; }, { passive: true });
  drawer.addEventListener('touchend', function (e) {
    if (y0 !== null && phone.matches && e.changedTouches[0].clientY - y0 > 60) { close(); }
    y0 = null;
  }, { passive: true });

  // a live frame that moved the generation: ask again, at most one in flight, no more than once in two seconds
  document.addEventListener('drishti:frame', function (e) {
    var g = e.detail && e.detail.generation;
    if (g === undefined) { return; }
    view.setAttribute('data-generation', String(g));
    if (!isOpen() || String(g) === shownGen || timer) { return; }
    timer = setTimeout(function () { timer = null; if (isOpen()) { load(true); } }, 2000);
  });
})();
