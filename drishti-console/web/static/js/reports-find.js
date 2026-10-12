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
// The reports page: one box finds a row by any of its words (name, what, when, to); rows that do not match hide.
(function () {
  var box = document.querySelector('[data-rep-filter]');
  if (!box) { return; }
  var rows = Array.prototype.slice.call(document.querySelectorAll('#subscriptions tbody tr'));
  var none = document.querySelector('[data-rep-none]');
  box.addEventListener('input', function () {
    var words = box.value.toLowerCase().split(/\s+/).filter(Boolean), shown = 0;
    rows.forEach(function (r) {
      var text = r.textContent.toLowerCase(), hit = words.every(function (w) { return text.indexOf(w) >= 0; });
      r.hidden = !hit; if (hit) { shown++; }
    });
    if (none) { none.hidden = shown > 0 || !words.length; }
  });
}());
