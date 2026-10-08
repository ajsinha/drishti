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
/* Panel zoom: expand one panel of a view to fill the view area, and restore it (USER_GUIDE.md, Zoom a panel).
   The panel is not moved: it gets the class `pnl-zoom`, which positions it over the view area (layout.css), so live patches
   keep landing where they did and a replaced panel is zoomed again by the observer below. The state is the URL hash
   `#zoom=<panelId>` (console only). Root-scoped like the other enhancers: init(root, options) with root the document or a
   ShadowRoot; inside <drishti-view> the panel fills the element's own box, the keys are the element's, the hash is left alone. */
(function () {
  'use strict';
  var me = document.currentScript;
  var ON = 'bi-arrows-fullscreen', OFF = 'bi-fullscreen-exit';
  var OVERLAYS = '.about:not([hidden]), .about-pop:not([hidden]), .about-tip:not([hidden]), .raw:not([hidden]), dialog[open], .disc-opts:not([hidden])';

  function init(root) {
    var isDoc = root === document, host = isDoc ? null : root.host;
    var undo = [], zoomed = null, ro = null, fsOn = false, queued = false, overlay = false, savedY = 0;
    function on(t, type, fn) { t.addEventListener(type, fn); undo.push(function () { t.removeEventListener(type, fn); }); }
    function byId(id) { return root.getElementById ? root.getElementById('p-' + id) : null; }
    function panels() { return Array.prototype.slice.call(root.querySelectorAll('section.pnl[data-panel]')); }
    function titleOf(p) { var h = p.querySelector('h3'); return ((h && h.textContent) || p.getAttribute('data-panel') || '').trim(); }
    function viewOf(p) { return (p && p.closest('.view, [data-view]')) || root.querySelector('.view, [data-view]'); }
    function btnOf(p) { return p ? p.querySelector(':scope > .pnl-h .pnl-zoom-btn') : null; }
    function layoutOn() { return !!root.querySelector('.layout-on'); }
    function first(e) { return (e.composedPath && e.composedPath()[0]) || e.target; }

    function paint(p, pressed) {
      var b = btnOf(p);
      if (!b) { return; }
      var t = titleOf(p), i = b.querySelector('i');
      b.setAttribute('aria-pressed', pressed ? 'true' : 'false');
      b.setAttribute('aria-label', (pressed ? 'Restore ' : 'Expand ') + t);
      b.title = (pressed ? 'Restore ' : 'Expand ') + t + ' (Z)';
      if (i) { i.classList.remove(pressed ? ON : OFF); i.classList.add(pressed ? OFF : ON); }
    }
    function decorate(p) {
      if (btnOf(p)) { return; }
      var code = p.querySelector(':scope > .pnl-h .pnl-code') || p.querySelector(':scope > .pnl-h');
      if (!code) { return; }
      var b = document.createElement('button'), i = document.createElement('i');
      b.type = 'button'; b.className = 'pnl-help pnl-zoom-btn'; b.setAttribute('data-zoom', '');
      b.setAttribute('aria-keyshortcuts', 'Z');
      i.className = 'bi ' + ON; i.setAttribute('aria-hidden', 'true');
      b.appendChild(i);
      code.insertBefore(b, code.querySelector('a.pnl-help[href*="/help/"], a[data-about-help]'));
      paint(p, !!zoomed && zoomed === p.getAttribute('data-panel'));
    }
    function resizeCharts(p) {
      var e = window.echarts;
      if (!e || !p) { return; }
      p.querySelectorAll('.chart, .xchart, .surface').forEach(function (el) { var c = e.getInstanceByDom(el); if (c && !c.isDisposed()) { c.resize(); } });
    }
    function measure() {                      // the console: the panel sits between the sticky top bar and the footer's function keys
      var p = zoomed && byId(zoomed), v = viewOf(p);
      if (!v || !isDoc) { return; }
      var bar = document.querySelector('.tbar'), foot = document.querySelector('footer.fkeys');
      var top = bar ? Math.max(0, Math.round(bar.getBoundingClientRect().bottom)) : 0;
      var bottom = foot ? Math.max(0, Math.round(window.innerHeight - foot.getBoundingClientRect().top)) : 0;
      v.style.setProperty('--zoom-top', top + 'px');
      v.style.setProperty('--zoom-bottom', bottom + 'px');
    }
    function fit(on) {                        // tables.js fits the page size of the zoomed panel's tables to its height, and puts it back
      try { root.dispatchEvent(new CustomEvent('drishti:table-fit', { detail: { on: on } })); } catch (e) { /* none */ }
    }
    function setHash(id) {
      if (!isDoc || !window.history || !history.replaceState) { return; }
      try { history.replaceState(history.state, '', location.pathname + location.search + (id ? '#zoom=' + encodeURIComponent(id) : '')); } catch (e) { /* sandboxed */ }
    }
    function watch(p) {
      if (ro) { ro.disconnect(); ro = null; }
      if (window.ResizeObserver && p) { ro = new ResizeObserver(function () { requestAnimationFrame(function () { resizeCharts(zoomed && byId(zoomed)); }); }); ro.observe(p); }
    }
    function apply(p) {                       // the classes and attributes of the zoomed state (also again after a live patch replaced the panel)
      var v = viewOf(p);
      p.classList.add('pnl-zoom');
      if (v) { v.classList.add('has-zoom'); }
      if (isDoc) { document.documentElement.classList.add('zoom-lock'); } else if (host) { host.setAttribute('data-zoomed', ''); }
      panels().forEach(function (o) {
        if (o === p) { o.removeAttribute('inert'); o.removeAttribute('aria-hidden'); }
        else { o.setAttribute('inert', ''); o.setAttribute('aria-hidden', 'true'); }
      });
      paint(p, true);
      measure();
    }
    function zoom(id, focus) {
      var p = id && byId(id);
      if (!p || !p.matches || !p.matches('section.pnl[data-panel]') || p.classList.contains('pnl-off') || layoutOn()) { return false; }
      if (zoomed && zoomed !== id) { unzoom(false); }
      zoomed = id;
      if (isDoc) {                              // a top bar that scrolls away (a phone) is brought back, so the panel sits below it; restore scrolls back
        var tb = document.querySelector('.tbar');
        savedY = window.pageYOffset;
        if (tb && !/sticky|fixed/.test(getComputedStyle(tb).position)) { window.scrollTo(0, 0); }
      }
      decorate(p);
      apply(p);
      setHash(id);
      watch(p);
      fit(true);
      if (focus !== false) { p.focus({ preventScroll: true }); }
      requestAnimationFrame(function () { resizeCharts(p); });
      return true;
    }
    function unzoom(returnFocus) {
      if (!zoomed) { return false; }
      var p = byId(zoomed);
      zoomed = null;
      watch(null);
      leaveFullscreen();
      panels().forEach(function (o) { o.removeAttribute('inert'); o.removeAttribute('aria-hidden'); o.classList.remove('pnl-zoom'); });
      fit(false);
      var v = viewOf(p);
      if (v) { v.classList.remove('has-zoom'); v.style.removeProperty('--zoom-top'); v.style.removeProperty('--zoom-bottom'); }
      if (isDoc) { document.documentElement.classList.remove('zoom-lock'); } else if (host) { host.removeAttribute('data-zoomed'); }
      if (p) { paint(p, false); }
      setHash(null);
      requestAnimationFrame(function () { resizeCharts(p); });
      if (isDoc && savedY) { window.scrollTo(0, savedY); }
      savedY = 0;
      if (returnFocus !== false && p) { (btnOf(p) || p).focus({ preventScroll: true }); }
      return true;
    }
    function toggle(p) { return zoomed === p.getAttribute('data-panel') ? unzoom(true) : zoom(p.getAttribute('data-panel')); }

    // ---- true fullscreen (wall displays), where the browser has it; silently nothing where it has not ---------------------
    function leaveFullscreen() {
      if (fsOn && document.exitFullscreen && document.fullscreenElement) { try { var r = document.exitFullscreen(); if (r && r.catch) { r.catch(function () {}); } } catch (e) { /* none */ } }
      fsOn = false;
    }
    function fullscreen(p) {
      if (!p.requestFullscreen || !document.fullscreenEnabled) { return; }
      if (document.fullscreenElement) { leaveFullscreen(); return; }
      if (zoomed !== p.getAttribute('data-panel') && !zoom(p.getAttribute('data-panel'))) { return; }
      try { var r = p.requestFullscreen(); fsOn = true; if (r && r.catch) { r.catch(function () { fsOn = false; }); } } catch (e) { fsOn = false; }
    }

    // ---- events -----------------------------------------------------------------------------------------------------
    on(root, 'click', function (e) {
      var t = first(e), b = t && t.closest && t.closest('[data-zoom]');
      var p = b && b.closest('section.pnl[data-panel]');
      if (p) { e.preventDefault(); toggle(p); }
    });
    function seeOverlay() { overlay = !!root.querySelector(OVERLAYS); }          // noted before any handler closes a drawer: Escape closes that, not the zoom
    root.addEventListener('keydown', seeOverlay, true);
    undo.push(function () { root.removeEventListener('keydown', seeOverlay, true); });
    on(root, 'keydown', function (e) {
      var tg = first(e);
      if (e.key === 'Escape') {
        if (!zoomed || e.defaultPrevented) { return; }
        if (overlay || (tg && tg.closest && tg.closest('[role="listbox"], [role="menu"], [aria-expanded="true"][aria-haspopup]'))) { return; }   // a drawer, popover or menu closes first
        e.preventDefault(); unzoom(true);
        return;
      }
      if ((e.key !== 'z' && e.key !== 'Z') || e.ctrlKey || e.altKey || e.metaKey || e.defaultPrevented) { return; }
      var tag = ((tg && tg.tagName) || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || (tg && tg.isContentEditable) || layoutOn()) { return; }
      var p = zoomed ? byId(zoomed) : (tg && tg.closest && tg.closest('section.pnl[data-panel]'));
      if (!p) { return; }
      e.preventDefault();
      if (e.shiftKey) { fullscreen(p); } else { toggle(p); }
    });
    if (isDoc) {
      on(window, 'resize', function () { if (zoomed) { measure(); } });
      on(window, 'hashchange', function () { var m = /^#zoom=(.+)$/.exec(location.hash); if (m) { zoom(decodeURIComponent(m[1])); } else { unzoom(false); } });
      on(document, 'fullscreenchange', function () { if (!document.fullscreenElement) { fsOn = false; } });
    }

    // ---- panels that appear or are replaced (a live patch): give them the button, and zoom a replaced zoomed panel again ----
    function rescan() {
      queued = false;
      panels().forEach(decorate);
      if (!zoomed) { return; }
      var p = byId(zoomed);
      if (!p) { zoomed = null; watch(null); return; }
      panels().forEach(function (o) { if (o !== p && !o.hasAttribute('inert')) { o.setAttribute('inert', ''); o.setAttribute('aria-hidden', 'true'); } });   // another panel was just replaced by a patch
      if (!p.classList.contains('pnl-zoom') || p.hasAttribute('inert')) {
        apply(p);
        watch(p);
        fit(true);
        var a = isDoc ? document.activeElement : root.activeElement;
        if (!a || a === document.body) { p.focus({ preventScroll: true }); }
        requestAnimationFrame(function () { resizeCharts(p); });
      }
    }
    var mo = new MutationObserver(function (list) {
      if (queued) { return; }
      for (var i = 0; i < list.length; i++) { if (list[i].addedNodes.length) { queued = true; queueMicrotask(rescan); return; } }
    });
    mo.observe(isDoc ? document.body : root, { childList: true, subtree: true });
    undo.push(function () { mo.disconnect(); });

    panels().forEach(decorate);
    if (isDoc) { var m = /^#zoom=(.+)$/.exec(location.hash); if (m) { zoom(decodeURIComponent(m[1])); } }   // reload or a shared link; an unknown panel is ignored

    return {
      zoom: zoom, restore: unzoom, toggle: toggle, scan: rescan, current: function () { return zoomed; },
      dispose: function () { unzoom(false); undo.splice(0).forEach(function (f) { f(); }); }
    };
  }
  (window.drishtiModules = window.drishtiModules || {}).zoom = { init: init };
  if (!(me && me.hasAttribute('data-manual'))) { window.drishtiZoom = init(document); }
})();
