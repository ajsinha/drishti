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
/* Entity views: function keys, tabs, bars (the charts: drishti_charts.js, on drishti-chart), the F9 raw drawer,
   breadcrumbs (per browser tab) and the market clock. */
(function () {
  'use strict';
  var me = document.currentScript, auto = !(me && me.hasAttribute('data-manual'));   // data-manual: a root-scoped caller (the element) runs init itself
  var view = auto ? document.querySelector('[data-view]') : null;

  // ---- clock ----------------------------------------------------------------------------------
  var clock = auto ? document.querySelector('[data-clock]') : null;
  if (clock) {
    var tz = clock.getAttribute('data-tz'), label = clock.getAttribute('data-tz-label');
    var tick = function () {
      try {
        clock.textContent = new Date().toLocaleTimeString('en-GB', { timeZone: tz, hour12: false }) + ' ' + label;
      } catch (e) { clock.textContent = new Date().toLocaleTimeString(); }
    };
    tick(); setInterval(tick, 1000);
  }

  /** The parts of a view that are the same in the console and inside an element: bars and tabs (its charts are drishti_charts.js's).
      init(root): root is the document or a ShadowRoot. Returns {enhance, redraw, updateChart, dispose}. */
  function init(root) {
    var undo = [];
  // ---- bars and gauges: widths from data-w (the CSP forbids inline styles) ---------------------
  function widths(root) {
    root.querySelectorAll('[data-w]').forEach(function (el) {
      var w = parseFloat(el.getAttribute('data-w')) || 0;
      requestAnimationFrame(function () { el.style.width = Math.max(0, Math.min(100, w)) + '%'; });
    });
  }

  // ---- tabs -----------------------------------------------------------------------------------
  function tabsIn(root) { root.querySelectorAll('[data-tabs]').forEach(function (box) {
    var heads = box.querySelectorAll('[role="tab"]');
    heads.forEach(function (h, i) {
      h.addEventListener('click', function () {
        heads.forEach(function (x, k) {
          x.classList.toggle('on', k === i); x.setAttribute('aria-selected', k === i ? 'true' : 'false');
          var pane = box.querySelector('[id="' + x.getAttribute('aria-controls') + '"]');   // root may be one panel (a live repaint), which has no getElementById
          if (pane) { pane.hidden = k !== i; }
        });
      });
    });
  }); }

  // ---- charts: drawn by drishti_charts.js (drishti-chart's panel charts); a panel swapped in is drawn there too -----------------
  widths(root); tabsIn(root);

  /** Hooks for drishti_live.js: re-enhance a replaced panel, and move a chart to new data without re-creating it. */
  var api = {
    dispose: function () { undo.splice(0).forEach(function (f) { f(); }); },
    enhance: function (r) {
      widths(r); tabsIn(r);
      if (root === document && window.Drishti.charts && r && r.querySelector && r.querySelector('[data-chart], [data-xchart], [data-surface]')) { window.Drishti.charts.draw(r); }
    },
    redraw: function () { if (root === document && window.Drishti.charts) { window.Drishti.charts.draw(document); } },
    updateChart: function (id, data) {
      var el = (root.querySelector ? root : document).querySelector('#p-' + CSS.escape(id) + ' .chart[data-chart]');
      if (!el) { return; }
      el.setAttribute('data-chart', JSON.stringify(data));
      // the chart's host (drishti_charts.js, in the page or the element) moves it to the new data
      el.dispatchEvent(new CustomEvent('drishti:chart-data', { bubbles: true, composed: false }));
      var foot = el.parentNode.querySelector('.chart-foot .mono');
      if (foot && data.markText) { foot.textContent = data.markText; }
    }
  };
  return api;
  }

  var reg = (window.drishtiModules = window.drishtiModules || {});
  reg.view = { init: init };
  if (!auto) { return; }
  window.Drishti.view = init(document);

  // ---- raw JSON drawer (F9) -------------------------------------------------------------------
  var raw = document.getElementById('rawDrawer');
  function toggleRaw() {
    if (!raw || !view) { return; }
    if (!raw.hidden) { raw.hidden = true; return; }
    raw.hidden = false;
    var body = raw.querySelector('[data-raw-body]'), meta = raw.querySelector('[data-raw-meta]'), text = raw.querySelector('[data-raw-text]');
    body.textContent = 'Loading…';
    rawJson = null;
    if (text) { text.textContent = ''; }
    fetch('/api/raw/' + encodeURIComponent(view.dataset.kind) + '/' + encodeURIComponent(view.dataset.id))
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d.provenance) { meta.textContent = d.provenance.source + ' · gen ' + d.provenance.generation; }
        var data = d.data !== undefined ? d.data : d;
        rawJson = JSON.stringify(data, null, 2);              // what Copy copies and Raw shows: the record as the server sent it
        if (text) { text.textContent = rawJson; }
        // the same structured tree as the Raw document viewer (drishti_raw-viewer.js); plain text where it is not loaded
        if (window.Drishti.Raw) { window.Drishti.Raw.render(body, data); } else { body.textContent = JSON.stringify(data, null, 2); }
      })
      .catch(function (e) { body.textContent = 'Could not load: ' + e; });
  }
  if (raw) { raw.querySelector('[data-raw-close]').addEventListener('click', toggleRaw); }
  var rawJson = null;
  if (raw) {   // F9: show the record as a tree or as plain JSON text, and copy it
    raw.querySelectorAll('[data-raw-mode]').forEach(function (b) {
      b.addEventListener('click', function () {
        var asText = b.getAttribute('data-raw-mode') === 'text';
        raw.querySelectorAll('[data-raw-mode]').forEach(function (o) { o.setAttribute('aria-pressed', String(o === b)); });
        raw.querySelector('[data-raw-body]').hidden = asText;
        var t = raw.querySelector('[data-raw-text]');
        if (t) { t.hidden = !asText; }
      });
    });
    var copyBtn = raw.querySelector('[data-raw-copy]'), copied = raw.querySelector('[data-raw-copied]');
    if (copyBtn) {
      copyBtn.addEventListener('click', function () {
        if (rawJson === null) { return; }
        var say = function (m) { if (copied) { copied.textContent = m; setTimeout(function () { copied.textContent = ''; }, 2000); } };
        var copy = window.Drishti.Raw && window.Drishti.Raw.copyText ? window.Drishti.Raw.copyText(rawJson)
          : (navigator.clipboard ? navigator.clipboard.writeText(rawJson) : Promise.reject(new Error('no clipboard')));
        copy.then(function () { say('Copied'); }, function () { say('Could not copy'); });
      });
    }
  }
  var rawFull = raw && raw.querySelector('[data-raw-full]');
  if (rawFull) {   // F9's drawer opens the full viewer: search, copy, download
    rawFull.addEventListener('click', function () {
      if (!window.Drishti.Raw || !view) { return; }
      raw.hidden = true;
      window.Drishti.Raw.open({ kind: view.dataset.kind, id: view.dataset.id, opener: document.querySelector('[data-fkey][data-action="raw"]') });
    });
  }

  // ---- embedded in a workspace pane: entity links become selections the workspace routes ------------
  if (view && view.hasAttribute('data-embed') && window.parent !== window) {
    document.addEventListener('click', function (e) {
      var a = e.target.closest('a[href^="/v/"]');
      if (!a) { return; }
      var parts = a.getAttribute('href').split('?')[0].split('/');
      e.preventDefault();
      window.parent.postMessage({ type: 'drishti:select', kind: decodeURIComponent(parts[2]), id: decodeURIComponent(parts[3]) }, location.origin);
    });
  }

  // ---- export and share (W18) ------------------------------------------------------------------
  if (view) {
    document.querySelectorAll('[data-export-panel]').forEach(function (a) {
      a.href = '/export/' + encodeURIComponent(view.dataset.kind) + '/' + encodeURIComponent(view.dataset.id) + '/' +
        encodeURIComponent(a.getAttribute('data-export-panel')) + '.' + (a.getAttribute('data-export-ext') || 'csv');
      a.hidden = false;
      var menu = a.closest('[data-export-menu]');           // one download button, CSV or JSON Lines in its menu
      if (menu) { menu.hidden = false; }
    });
    document.addEventListener('click', function (e) {      // a click elsewhere, or on a format, closes an open download menu
      document.querySelectorAll('[data-export-menu][open]').forEach(function (d) {
        if (!d.contains(e.target) || e.target.closest('a[data-export-panel]')) { d.open = false; }
      });
    });
  }
  document.querySelectorAll('[data-share]').forEach(function (b) {
    b.addEventListener('click', function () {
      var url = b.getAttribute('data-share');
      var label = b.querySelector('[data-share-label]');
      var done = function (text) { if (label) { label.textContent = text; setTimeout(function () { label.textContent = 'Share'; }, 2000); } };
      var copy = function () {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(url).then(function () { done('Link copied'); }, function () { window.prompt('Copy this link', url); });
        } else { window.prompt('Copy this link', url); }
      };
      // drishti_share.js (the share dialog) takes the click when collaboration is on; otherwise the button copies the link as it always did
      if (window.Drishti.Share) { window.Drishti.Share.open(b, copy); } else { copy(); }
    });
  });
  document.querySelectorAll('[data-print]').forEach(function (b) { b.addEventListener('click', function () { window.print(); }); });

  // ---- function keys --------------------------------------------------------------------------
  function run(btn) {
    var action = btn.getAttribute('data-action');
    if (action === 'panel') {
      var p = document.getElementById('p-' + btn.getAttribute('data-target'));
      if (p) { p.scrollIntoView({ behavior: 'smooth', block: 'start' }); p.focus({ preventScroll: true }); p.classList.remove('flash'); void p.offsetWidth; p.classList.add('flash'); }
    } else if (action === 'link') { window.location.href = btn.getAttribute('data-href'); }
    else if (action === 'raw') { toggleRaw(); }
    else if (action === 'back') { history.back(); }
    else if (action === 'impact' && view) {
      window.location.href = '/impact/' + encodeURIComponent(view.dataset.kind) + '/' + encodeURIComponent(view.dataset.id);
    }
  }
  document.querySelectorAll('[data-fkey]').forEach(function (b) { b.addEventListener('click', function () { run(b); }); });
  var K = window.Drishti.keys;          // the console's keymap; an element in a host page (no keymap) binds no page keys
  if (K) {
  K.register({ id: 'view.back', keys: ['Alt+ArrowLeft'], scope: 'screen', area: 'view', group: 'View', description: 'Back', typing: true,
    run: function () { history.back(); } });
  // Alt+Shift+1..9 (0 for F10) work as F1..F10: on Windows a laptop's F-keys are media keys until Fn is held, and Edge
  // keeps some for itself (F9: Immersive Reader), so the function keys of a page cannot be relied on there
  document.querySelectorAll('[data-fkey]').forEach(function (b) {
    var key = b.getAttribute('data-fkey') || '', m = /^F([1-9]|1[0-2])$/.exec(key);
    if (!m) { return; }
    var keys = [key].concat(+m[1] <= 10 ? ['Alt+Shift+' + (m[1] === '10' ? '0' : m[1])] : []);
    K.register({ id: 'view.fkey.' + key, keys: keys, scope: 'screen', area: 'view', group: 'Function keys', typing: true,
      description: (b.getAttribute('aria-label') || key).replace(/^F\d+\s*/, ''), run: function () { run(b); } });
  });
  }
  document.querySelectorAll('[data-fkey]').forEach(function (b) {
    var m = /^F([1-9]|10)$/.exec(b.getAttribute('data-fkey') || '');
    if (m) {
      var keys = b.getAttribute('data-fkey') + ' or Alt+Shift+' + (m[1] === '10' ? '0' : m[1]);
      b.title = b.title ? b.title + ' (' + keys + ')' : keys;
    }
  });
  if (K) { K.register({ id: 'view.raw.close', keys: ['Escape'], scope: 'dialog', area: 'raw-json', description: 'Close the raw JSON drawer', typing: true,
    open: function () { return !!raw && !raw.hidden; }, run: function () { raw.hidden = true; } }); }

  // ---- breadcrumbs: the path of views followed in this browser tab (not inside workspace panes) ---
  if (view && !view.hasAttribute('data-embed')) {
    var here = { label: view.dataset.label, url: location.pathname };
    var trail = [];
    try { trail = JSON.parse(sessionStorage.getItem('drishti.trail') || '[]'); } catch (e) { trail = []; }
    // a day opened from the history page (via=history, QA1-16): the way back is that history, with its range, not an earlier page's trail
    var back = document.querySelector('[data-pin-history]');
    if (back && new URLSearchParams(location.search).get('via') === 'history') {
      var ref = null;
      try { ref = document.referrer ? new URL(document.referrer) : null; } catch (e) { ref = null; }
      var hist = ref && ref.origin === location.origin && ref.pathname === back.getAttribute('href') ? ref.pathname + ref.search : back.getAttribute('href');
      back.setAttribute('href', hist);
      trail = [{ label: 'History of ' + here.label, url: hist }];
    }
    var at = trail.findIndex(function (c) { return c.url === here.url; });
    var CRUMBS = 5;                                      // the trail shows the last five views; older ones are kept for Back, behind an ellipsis
    trail = at >= 0 ? trail.slice(0, at + 1) : trail.concat([here]).slice(-12);
    try { sessionStorage.setItem('drishti.trail', JSON.stringify(trail)); } catch (e) { /* storage blocked */ }
    var nav = document.querySelector('[data-crumbs]');
    if (nav && trail.length > 1) {
      var shown = trail.slice(-CRUMBS);
      nav.innerHTML = '← ' + (trail.length > shown.length ? '<span class="crumb-more" title="' + (trail.length - shown.length) + ' earlier views">…</span><span class="sep">/</span>' : '') + shown.map(function (c, i) {
        var t = String(c.label).replace(/[&<>"']/g, '');
        return i === shown.length - 1 ? '<span>' + t + '</span>' : '<a href="' + encodeURI(c.url) + '">' + t + '</a>';
      }).join('<span class="sep">/</span>');
      nav.hidden = false;
    }
  }
})();
