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
  var asof = document.querySelector('[data-asof]');
  if (asof) {
    var box = asof.querySelector('input[type="date"]');
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
      e.preventDefault();
      window.location.href = '/help/context/' + encodeURIComponent(document.body.getAttribute('data-screen') || 'landing');
    }
  });

  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-theme-choice]');
    if (b) { apply(b.getAttribute('data-theme-choice')); }
  });
  apply(root.getAttribute('data-theme') || root.getAttribute('data-default-theme') || 'terminal');
})();
