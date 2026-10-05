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
/* Console-wide behaviour: theme menu. */
/* One message from a problem: its DRS code once, whether or not the detail already starts with it or repeats it (UX-10). */
window.drsMessage = function (p, fallback) {
  p = p || {};
  var code = p.code || 'Error', detail = String(p.detail || fallback || '');
  if (/^DRS-\d+$/.test(code)) { detail = detail.split(code + ': ').join('').split(code + ' ').join('').split(code).join('').trim(); }
  return detail ? code + ': ' + detail : code;
};
(function () {
  'use strict';
  var root = document.documentElement;
  var THEMES = ['terminal', 'light', 'wallstreet', 'blue', 'green', 'crimson', 'crimson-dark'];

  function apply(theme) {
    if (THEMES.indexOf(theme) < 0) { return; }
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-bs-theme', theme === 'light' || theme === 'crimson' ? 'light' : 'dark');
    try { localStorage.setItem('drishti.theme', theme); } catch (e) { /* ignore */ }
    if (root.hasAttribute('data-signed-in') && root.getAttribute('data-user-theme') !== theme) {   // follow the user to other browsers
      root.setAttribute('data-user-theme', theme);
      fetch('/api/settings', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ theme: theme }) })
        .catch(function () { /* offline: this browser still remembers */ });
    }
    document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-theme-choice') === theme);
    });
    document.dispatchEvent(new CustomEvent('drishti:theme', { detail: theme }));
  }

  // Business date: picking a date shows that day's data as a static snapshot. Weekends and holidays roll back to
  // the previous business day on the server; the box says so before it submits.
  // the top bar's form only (an embedded pane has none): one exception here would stop the rest of this file, the
  // workspace's pane keys included
  var asof = document.querySelector('form[data-asof]');
  var box = asof ? asof.querySelector('input[type="date"]') : null;
  if (asof && box) {
    var holidays = (box.getAttribute('data-holidays') || '').split(',');
    box.addEventListener('change', function () {
      if (!box.value) { return; }
      var d = new Date(box.value + 'T12:00:00Z').getUTCDay();
      if (d === 0 || d === 6 || holidays.indexOf(box.value) >= 0) {
        box.title = box.value + ' is not a business day: showing the business day before it';
      }
      asof.submit();
    });
    // Known at (a picked date only): what the date looked like at that time; a restatement shows as a difference.
    var known = asof.querySelector('[data-asof-known]');
    if (known) { known.addEventListener('change', function () { asof.submit(); }); }
    var knownBtn = asof.querySelector('[data-known-toggle]');
    if (knownBtn && known) {
      knownBtn.addEventListener('click', function () {
        knownBtn.hidden = true; known.hidden = false; known.focus();
        if (known.showPicker) { try { known.showPicker(); } catch (e) { /* not allowed here: typing works */ } }
      });
    }
  }

  // Pack switcher: choose which of your packs to see.
  document.addEventListener('submit', function (e) {
    var f = e.target.closest('[data-packs]');
    if (!f) { return; }
    e.preventDefault();
    var active = [].map.call(f.querySelectorAll('input[name="pack"]:checked'), function (i) { return i.value; });
    if (!active.length) { return; }
    fetch('/api/packs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ active: active }) })
      .then(function (r) { if (r.ok) { location.reload(); } });
  });

  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-packs-all], [data-packs-none]');
    if (!b) { return; }
    var all = b.hasAttribute('data-packs-all');
    b.closest('[data-packs]').querySelectorAll('input[name="pack"]').forEach(function (i) { i.checked = all; });
  });

  // F1: help for the screen you are on.
  document.addEventListener('keydown', function (e) {
    if (e.key === 'F1') {
      if (e.defaultPrevented) { return; }                  // About this page took it (a view: the drawer opens)
      e.preventDefault();
      var go = function () { window.location.href = '/help/context/' + encodeURIComponent(document.body.getAttribute('data-screen') || 'landing'); };
      var held = window.drishtiBeforeLeave ? window.drishtiBeforeLeave() : null;      // a page with an edit waiting sends it first (UX-22)
      if (held && held.then) { held.then(go, go); } else { go(); }
    }
  });

  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-theme-choice]');
    if (b) { apply(b.getAttribute('data-theme-choice')); }
  });
  apply(root.getAttribute('data-theme') || root.getAttribute('data-default-theme') || 'terminal');
})();

/* In a workspace pane (an embedded page): Alt+0..4 belong to the workspace, which moves the focus between its panes and
   its toolbar (workspace.js). The pane's document has the keys while it has the focus, so it hands them up, to its own
   origin only; the workspace acts only on messages from its own panes (UX-07). */
(function () {
  'use strict';
  if (window.parent === window || !document.body.classList.contains('embed')) { return; }
  document.addEventListener('keydown', function (e) {
    if (!e.altKey || e.ctrlKey || e.metaKey) { return; }
    var m = /^Digit([0-4])$/.exec(e.code || '') || /^([0-4])$/.exec(e.key || '');
    if (!m) { return; }
    e.preventDefault();
    try { window.parent.postMessage({ type: 'drishti:key', n: +m[1] }, location.origin); } catch (err) { /* not ours */ }
  }, true);
})();

/* Server colours (ADR-016): set from data attributes, since the content security policy allows no inline styles. */
(function () {
  'use strict';
  document.querySelectorAll('[data-srv-color]').forEach(function (el) {
    el.style.setProperty('--srv', el.getAttribute('data-srv-color'));
  });
})();

/* Build → Govern → Reviews shows how many proposals wait; asked when the menu opens, once (not on every page load). */
(function () {
  'use strict';
  var asked = false;
  document.addEventListener('show.bs.dropdown', function (e) {
    var menu = e.target && e.target.closest ? e.target.closest('.tbar-menu') : null;
    var badge = menu && menu.querySelector('[data-review-count]');
    if (!badge || asked) { return; }
    asked = true;
    fetch('/build/review-count', { headers: { Accept: 'application/json' } }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
      if (j && j.pending > 0) { badge.textContent = String(j.pending); badge.hidden = false; badge.setAttribute('aria-label', j.pending + ' waiting'); }
    }).catch(function () { asked = false; });
  });
})();
