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
/* Help centre: filter guide cards as you type, fall back to full-text search, and copy code examples. */
(function () {
  'use strict';
  var input = document.querySelector('[data-help-filter]');
  if (input) {
    var none = document.querySelector('[data-help-none]');
    input.addEventListener('input', function () {
      var q = input.value.trim().toLowerCase(), shown = 0;
      document.querySelectorAll('.help-card[data-search]').forEach(function (c) {
        var ok = !q || c.getAttribute('data-search').indexOf(q) >= 0;
        c.hidden = !ok; if (ok) { shown++; }
      });
      document.querySelectorAll('.help-cat').forEach(function (cat) {
        cat.hidden = !!q && !cat.querySelector('.help-card:not([hidden])');
      });
      if (none) { none.hidden = shown > 0; }
    });
    var ft = document.querySelector('[data-help-fulltext]');
    if (ft) { ft.addEventListener('click', function (e) { e.preventDefault(); input.form.submit(); }); }
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
