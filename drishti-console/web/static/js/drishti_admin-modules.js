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
/* Admin → Modules (mock-up C): a click on a module (or its link) shows its detail in place; the filter chips (all, problems, off, development) set the
   matrix's own filter box (drishti-grid's console table), so sort, pages and keys are the grid's; the copy buttons put the install commands on the
   clipboard. One page load answers everything: no fetch, no polling. */
(function () {
  'use strict';
  var root = document.querySelector('[data-admin-modules]');
  if (!root) { return; }
  var said = root.querySelector('[data-modules-said]');

  function say(text) { if (said) { said.textContent = text; } }

  function show(id) {
    root.querySelectorAll('[data-module-detail]').forEach(function (s) { s.hidden = s.getAttribute('data-module-detail') !== id; });
    root.querySelectorAll('tr[data-module]').forEach(function (r) { r.classList.toggle('mod-row-on', r.getAttribute('data-module') === id); });
  }

  root.addEventListener('click', function (e) {
    var a = e.target.closest('[data-pick]');
    if (a && root.contains(a)) {
      e.preventDefault();
      var id = a.getAttribute('data-pick');
      show(id);
      try { history.replaceState(null, '', '?module=' + encodeURIComponent(id)); } catch (x) { /* a page that may not rewrite its address */ }
      var d = root.querySelector('[data-module-detail="' + id + '"]');
      if (d && d.scrollIntoView) { d.scrollIntoView({ block: 'nearest' }); }
      return;
    }
    var c = e.target.closest('[data-copy-fix]');
    if (c) {
      var text = c.getAttribute('data-copy-fix') || '';
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { say('Commands copied.'); }, function () { say('Select the commands and copy them.'); });
      } else { say('Select the commands and copy them.'); }
      return;
    }
    var f = e.target.closest('[data-filter]');
    if (f) {
      root.querySelectorAll('[data-filter]').forEach(function (b) {
        var on = b === f; b.classList.toggle('mod-chip-on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false');
      });
      var q = root.querySelector('.tbl-pg-q');                   // the grid's own filter box
      if (q) { q.value = f.getAttribute('data-filter'); q.dispatchEvent(new Event('input', { bubbles: true })); }
    }
  });
})();
