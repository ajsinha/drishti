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
/* Help centre (mock-up help-hub/A): an old address /help#cat-<id> opens its section's page; on a section's page the rows filter as you
   type (a group with none left folds away), falling back to a full-text search of the section; "all of Help" carries the words; code
   examples copy. */
(function () {
  'use strict';
  var hub = document.querySelector('[data-help-hub]');
  var m = hub && /^#cat-([\w-]+)$/.exec(location.hash || '');
  if (m) {
    var names = {};
    try { names = JSON.parse(hub.getAttribute('data-help-sections') || '{}'); } catch (e) { names = {}; }
    if (names[m[1]]) { location.replace('/help/section/' + encodeURIComponent(names[m[1]])); return; }
  }
  var input = document.querySelector('[data-help-filter]');
  if (input) {
    var none = document.querySelector('[data-help-none]');
    input.addEventListener('input', function () {
      var q = input.value.trim().toLowerCase(), shown = 0;
      document.querySelectorAll('[data-help-row][data-search]').forEach(function (r) {
        var ok = !q || r.getAttribute('data-search').indexOf(q) >= 0;
        r.hidden = !ok; if (ok) { shown++; }
      });
      document.querySelectorAll('[data-help-group]').forEach(function (g) {
        g.hidden = !!q && !g.querySelector('[data-help-row]:not([hidden])');
      });
      if (none) { none.hidden = shown > 0; }
    });
    var ft = document.querySelector('[data-help-fulltext]');
    if (ft) { ft.addEventListener('click', function (e) { e.preventDefault(); input.form.submit(); }); }
    var all = document.querySelector('[data-help-all]');
    if (all) {
      all.addEventListener('click', function (e) {
        var q = input.value.trim();
        if (q) { e.preventDefault(); location.href = '/help/search?q=' + encodeURIComponent(q); }
      });
    }
  }
  // Help's search boxes (the hub, a guide's, the results page's): the one search box over Help's pages (GET /api/search?in=help); a page
  // picked opens, Enter with none picked runs the full search (the form). A section's own filter and the Knowledge search stay as they are.
  if (window.Drishti.searchBox) {
    document.querySelectorAll('form.help-search[action="/help/search"]:not(.sec-search) input[name="q"]').forEach(function (q) {
      if (q.form.querySelector('input[name="section"]')) { return; }
      q.form.classList.add('drishti_search-host');
      window.Drishti.searchBox(q, { where: 'help', label: 'Help pages', minChars: 2, fetchOnFocus: false });
    });
  }
  document.querySelectorAll('[data-copy]').forEach(function (b) {
    b.addEventListener('click', function () {
      var code = b.closest('figure').querySelector('code').innerText;
      (navigator.clipboard ? navigator.clipboard.writeText(code) : Promise.reject()).then(function () {
        b.innerHTML = '<i class="bi bi-check2" aria-hidden="true"></i> Copied';
        setTimeout(function () { b.innerHTML = '<i class="bi bi-clipboard" aria-hidden="true"></i> Copy'; }, 1500);
      }).catch(function () { b.textContent = 'Select and copy'; });
    });
  });
  document.querySelectorAll('[data-unavailable]').forEach(function (a) {
    a.title = 'Not part of the in-app help: ' + a.getAttribute('data-unavailable');
    a.addEventListener('click', function (e) { e.preventDefault(); });
  });
})();
