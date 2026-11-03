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
/* The Engines menu (terminal/_topbar.html, mock-up engines-menu/A):
 * - the search box (shown when many engines run) filters the pages by name, description and command word as you type; an engine with no
 *   match folds away, the matched words are marked, Enter opens the first page left, Escape clears the box;
 * - on a phone (below 640 px) the columns fold like the lens menus' drawer: an engine's head opens its pages, one engine at a time;
 * - the box takes the focus when the menu opens on a desktop. */
(function () {
  'use strict';
  var menu = document.querySelector('[data-engines-menu]');
  if (!menu) { return; }
  var panel = menu.querySelector('[data-engines-panel]');
  var q = menu.querySelector('[data-engines-q]');
  var none = menu.querySelector('[data-engines-none]');
  var count = menu.querySelector('[data-engines-count]');
  var narrow = window.matchMedia ? window.matchMedia('(max-width: 639.98px)') : { matches: false };

  function mark(el, text, word) {
    /* the label again as text, with the matched word in a <mark>: never HTML from the box */
    el.textContent = '';
    var i = word ? text.toLowerCase().indexOf(word) : -1;
    if (i < 0) { el.textContent = text; return; }
    el.appendChild(document.createTextNode(text.slice(0, i)));
    var m = document.createElement('mark');
    m.className = 'eng-mark';
    m.textContent = text.slice(i, i + word.length);
    el.appendChild(m);
    el.appendChild(document.createTextNode(text.slice(i + word.length)));
  }

  function filter() {
    var word = (q.value || '').trim().toLowerCase();
    var total = 0, shown = 0;
    panel.querySelectorAll('.eng-col').forEach(function (col) {
      var left = 0;
      col.querySelectorAll('.mega-item[data-search]').forEach(function (a) {
        total++;
        var ok = !word || a.getAttribute('data-search').indexOf(word) >= 0;
        a.hidden = !ok;
        var l = a.querySelector('.eng-l');
        if (l) {
          if (!l.hasAttribute('data-text')) { l.setAttribute('data-text', l.textContent); }
          mark(l, l.getAttribute('data-text'), ok ? word : '');
        }
        if (ok) { left++; shown++; }
      });
      col.hidden = !!word && left === 0;
      col.classList.toggle('eng-open', !!word ? left > 0 : col === panel.querySelector('.eng-col'));
    });
    if (none) { none.hidden = !word || shown > 0; }
    if (count) {
      count.hidden = !word;
      count.textContent = word ? shown + ' of ' + total + ' pages · Enter opens the first' : '';
    }
  }

  if (q) {
    q.addEventListener('input', filter);
    q.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        var first = panel.querySelector('.eng-col:not([hidden]) .mega-item:not([hidden])');
        e.preventDefault();
        if (first) { first.click(); }
      } else if (e.key === 'Escape' && q.value) {
        e.stopPropagation();
        q.value = '';
        filter();
      }
    });
    menu.addEventListener('shown.bs.dropdown', function () {
      if (!narrow.matches) { q.focus(); }
    });
  }

  /* a phone: one engine open at a time; on a wider screen the heads are titles and every column shows */
  panel.addEventListener('click', function (e) {
    var head = e.target.closest && e.target.closest('[data-engine-head]');
    if (!head || !narrow.matches) { return; }
    var col = head.closest('.eng-col');
    var open = !col.classList.contains('eng-open');
    panel.querySelectorAll('.eng-col').forEach(function (c) {
      c.classList.toggle('eng-open', c === col && open);
      var h = c.querySelector('[data-engine-head]');
      if (h) { h.setAttribute('aria-expanded', c === col && open ? 'true' : 'false'); }
    });
  });
})();
