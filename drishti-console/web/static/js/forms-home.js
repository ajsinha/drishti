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
/* The forms home (/forms): the filter box hides packs and kinds that do not match, and the last forms opened in this browser are listed
   (form.js writes them). Without this script the page is the full list. */
(function () {
  'use strict';
  var root = document.querySelector('[data-forms-home]');
  if (!root) { return; }
  var box = root.querySelector('[data-forms-filter]'), none = root.querySelector('[data-forms-none]');
  var packs = Array.prototype.slice.call(root.querySelectorAll('[data-forms-pack]'));

  function apply() {
    var words = box.value.toLowerCase().split(/\s+/).filter(Boolean), shown = 0;
    packs.forEach(function (p) {
      var any = false;
      Array.prototype.forEach.call(p.querySelectorAll('.qh-kind'), function (k) {
        var hit = !words.length || words.every(function (w) { return k.getAttribute('data-text').indexOf(w) >= 0 || p.getAttribute('data-text').indexOf(w) >= 0; });
        k.parentNode.hidden = !hit;
        any = any || hit;
      });
      p.hidden = !any;
      if (any) { shown++; }
    });
    none.hidden = shown > 0;
  }
  box.addEventListener('input', apply);
  box.addEventListener('keydown', function (e) { if (e.key === 'Escape' && box.value) { box.value = ''; apply(); e.stopPropagation(); } });

  try {
    var list = JSON.parse(localStorage.getItem('drishti.forms.recent.' + (root.getAttribute('data-who') || '')) || '[]');
    var ul = root.querySelector('[data-forms-recent-list]');
    list.forEach(function (r) {
      if (!r || typeof r.href !== 'string' || r.href.indexOf('/form/') !== 0) { return; }
      var li = document.createElement('li'), a = document.createElement('a');
      a.className = 'lnk'; a.href = r.href; a.textContent = String(r.label || r.href);
      li.appendChild(a); ul.appendChild(li);
    });
    if (ul.children.length) { root.querySelector('[data-forms-recent]').hidden = false; }
  } catch (e) { /* storage refused or damaged: no recents */ }
  if (!window.matchMedia('(pointer: coarse)').matches) { box.focus(); }
})();
