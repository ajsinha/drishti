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
/* The Admin lists (Packs, Connectors, Artifacts) on a phone. The tables become cards in CSS (admin-packs.css, max-width 640 px): this script gives
   each cell the text of its column header as data-label, which the card shows before the value, and folds the page's introduction behind
   "About this page" so the list is the first thing on a small screen. On a wide screen the introduction stays open and the summary is hidden. */
(function () {
  'use strict';
  document.querySelectorAll('table[data-packs], table[data-list], table[data-archives]').forEach(function (t) {
    var heads = Array.prototype.map.call(t.querySelectorAll('thead th'), function (th) {
      var h = th.cloneNode(true);
      Array.prototype.forEach.call(h.querySelectorAll('.visually-hidden'), function (x) { x.remove(); });
      return h.textContent.trim();
    });
    t.setAttribute('data-cards', '');
    Array.prototype.forEach.call(t.querySelectorAll('tbody tr'), function (tr) {
      Array.prototype.forEach.call(tr.children, function (cell, i) {
        if (heads[i]) { cell.setAttribute('data-label', heads[i]); }
      });
    });
  });
  var phone = window.matchMedia && window.matchMedia('(max-width: 640px)').matches;
  document.querySelectorAll('details.adm-about').forEach(function (d) { if (phone) { d.removeAttribute('open'); } else { d.setAttribute('open', ''); } });
})();
