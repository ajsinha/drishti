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
/* The phone display (docs/architecture/MOBILE_DISPLAY.md, core/phone.py), the browser side.
 *
 * - Tells the console what this screen is: the drishti-device cookie (phone below 640 px, tablet to 1000 px, desk above), from the width
 *   at load and on rotation. An entity page drawn for another class is drawn again once (a first visit, a phone turned sideways).
 * - More on this page: a tap loads that panel in place (GET <page>/phone/<panel>), drawn as the desktop draws it.
 * - Show all on a compact (glance) panel: the whole panel replaces it.
 * - Show everything / Phone view: the whole page for this session, or back to the phone page.
 * - On desktop: copies the page's link, to open on a bigger screen.
 */
(function () {
  'use strict';
  var COOKIE = 'drishti-device', ALL = 'drishti-phone-all';

  function deviceOf(w) { return w < 640 ? 'phone' : (w <= 1000 ? 'tablet' : 'desk'); }
  function cookie(name) {
    var m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return m ? decodeURIComponent(m[1]) : '';
  }
  function setCookie(name, value, session) {
    document.cookie = name + '=' + encodeURIComponent(value) + '; path=/; SameSite=Lax' + (session ? '' : '; max-age=31536000');
  }

  function drawnFor() {
    var page = document.querySelector('[data-phone-page]');
    return page ? page.getAttribute('data-phone-page') : 'desk';
  }

  function sync(reload) {
    var now = deviceOf(window.innerWidth || document.documentElement.clientWidth || 1280);
    var was = cookie(COOKIE);
    if (was !== now) { setCookie(COOKIE, now); }
    var view = document.querySelector('[data-view]:not([data-embed])');
    if (!reload || !view || view.querySelector('[data-render-host]') || cookie(ALL) === '1' || document.querySelector('[data-phone-back]')) { return; }
    if (drawnFor() === now) { return; }
    var key = 'drishti-device-drawn:' + location.pathname + location.search;
    try {
      if (sessionStorage.getItem(key) === now) { return; }     // asked once for this class already (the phone display may be off)
      sessionStorage.setItem(key, now);
    } catch (e) { /* private mode: draw again anyway, once per load */ }
    location.reload();
  }

  /* The lens menus' phone form (LX1 option C): below 640 px the lens menu (waiting in a template in the bar) goes in first, and
     phone.css hides the subject menus it folds; above, it is taken out again, so a wide screen draws each item once. */
  function lensFold() {
    var tpl = document.querySelector('template[data-lens-fold-tpl]');
    if (!tpl) { return; }
    var have = document.querySelector('[data-lens-fold]');
    var narrow = (window.innerWidth || document.documentElement.clientWidth) < 640;
    if (narrow && !have) { tpl.after(tpl.content.cloneNode(true)); }
    else if (!narrow && have) { have.remove(); }
  }

  var t = null;
  window.addEventListener('resize', function () { clearTimeout(t); t = setTimeout(function () { sync(false); lensFold(); }, 250); });
  window.addEventListener('orientationchange', function () { setTimeout(function () { lensFold(); sync(true); }, 300); });
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', lensFold); } else { lensFold(); }
  sync(true);

  function base() {
    var page = document.querySelector('[data-phone-page]');
    var v = document.querySelector('[data-view]');
    var b = page ? page.getAttribute('data-phone-base') : (v ? '/v/' + encodeURIComponent(v.dataset.kind) + '/' + encodeURIComponent(v.dataset.id) : '');
    var q = page ? page.getAttribute('data-phone-query') : location.search.replace(/^\?/, '');
    return { path: b, query: q };
  }

  function fetchPanel(id, compact) {
    var b = base();
    var url = b.path + '/phone/' + encodeURIComponent(id) + '?' + (compact ? 'compact=1' : 'full=1') + (b.query ? '&' + b.query : '');
    return fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then(function (r) {
      if (!r.ok) { throw new Error('status ' + r.status); }
      return r.json();
    });
  }

  function place(html, into, replace) {
    var tpl = document.createElement('template');
    tpl.innerHTML = String(html || '').trim();
    var fresh = tpl.content.firstElementChild;
    if (!fresh) { return null; }
    if (replace) { into.replaceWith(fresh); } else { into.appendChild(fresh); }
    if (window.drishti && window.drishti.enhance) { window.drishti.enhance(fresh); }
    return fresh;
  }

  function failed(btn, label) {
    btn.disabled = false;
    btn.removeAttribute('aria-busy');
    btn.setAttribute('data-phone-failed', '');
    var t = btn.querySelector('.ph-row-t') || btn;
    t.textContent = label + ': could not be loaded. Tap to try again';
  }

  document.addEventListener('click', function (e) {
    var load = e.target.closest('[data-phone-load]');
    if (load) {
      var slot = load.closest('[data-phone-slot]');
      if (!load.hasAttribute('data-phone-label')) { load.setAttribute('data-phone-label', (load.querySelector('.ph-row-t') || load).textContent); }
      var label = load.getAttribute('data-phone-label');
      load.disabled = true;
      load.setAttribute('aria-busy', 'true');
      fetchPanel(load.getAttribute('data-phone-load'), false).then(function (got) {
        var fresh = place(got.html, slot, false);
        if (fresh) { load.remove(); slot.classList.add('ph-loaded'); fresh.focus({ preventScroll: true }); }
        else { failed(load, label); }
      }).catch(function () { failed(load, label); });
      return;
    }
    var all = e.target.closest('[data-phone-all]');
    if (all) {
      var panel = all.closest('[data-panel]');
      all.disabled = true;
      all.setAttribute('aria-busy', 'true');
      fetchPanel(all.getAttribute('data-phone-all'), false).then(function (got) {
        var fresh = place(got.html, panel, true);
        if (fresh) { fresh.focus({ preventScroll: true }); }
      }).catch(function () { all.disabled = false; all.removeAttribute('aria-busy'); all.textContent = 'Could not load the whole panel. Tap to try again'; });
      return;
    }
    if (e.target.closest('[data-phone-everything]')) { setCookie(ALL, '1', true); location.reload(); return; }
    if (e.target.closest('[data-phone-back]')) { document.cookie = ALL + '=; path=/; max-age=0; SameSite=Lax'; location.reload(); return; }
    var copy = e.target.closest('[data-phone-copy]');
    if (copy) {
      var said = document.querySelector('[data-phone-copied]');
      var link = copy.getAttribute('data-phone-copy') || location.href;
      var done = function () { if (said) { said.textContent = 'Link copied: open it on a desktop'; } };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(link).then(done, function () { if (said) { said.textContent = link; } });
      } else if (said) { said.textContent = link; }
    }
  });

  /* live.js: a tick of a compact panel fetches its compact form again, so a phone keeps five rows rather than the whole table */
  window.drishtiPhone = {
    refresh: function (id) {
      var old = document.getElementById('p-' + id);
      if (!old) { return; }
      fetchPanel(id, true).then(function (got) { if (got && got.html) { place(got.html, old, true); } }).catch(function () { /* the next tick tries again */ });
    }
  };
}());
