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
/* The designer's "Open first" choice (RACHANA_REFERENCE.md 31): the mode a person opens first, kept in their settings (/designer/mode). */
(function () {
  'use strict';
  document.querySelectorAll('[data-dz-default]').forEach(function (s) {
    if (s.dataset.wired) { return; }
    s.dataset.wired = '1';
    s.addEventListener('change', function () {
      fetch('/designer/mode', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: s.value }) })
        .then(function (r) { s.setAttribute('data-dz-saved', r.ok ? s.value : ''); s.title = r.ok ? 'Saved: you open ' + s.options[s.selectedIndex].text + ' first' : 'Not saved'; });
    });
  });
})();
