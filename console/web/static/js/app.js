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
    root.setAttribute('data-bs-theme', theme === 'light' ? 'light' : 'dark');
    try { localStorage.setItem('drishti.theme', theme); } catch (e) { /* ignore */ }
    document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-theme-choice') === theme);
    });
    document.dispatchEvent(new CustomEvent('drishti:theme', { detail: theme }));
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
