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
/* Every table pages and can be walked with the keyboard: a pager under it (first, previous, next, last, rows per
   page) and ▲ ▼ buttons; click a row, or focus the table, then ↑ ↓ move the selection (turning pages), PgUp/PgDn page,
   Home/End jump, Enter opens the selected row's link. Tables that live updates re-render keep their page and
   selection. Rows per page: the user's choice, remembered per browser (25 by default). */
(function () {
  'use strict';
  var SIZES = [25, 50, 100, 250];
  var KEY = 'drishti.tableRows';
  var state = {};                      // a table's key -> {page, sel}, so a re-rendered table keeps its place

  function size() {
    try { var n = parseInt(localStorage.getItem(KEY), 10); return SIZES.indexOf(n) >= 0 ? n : 25; } catch (e) { return 25; }
  }
  function keyOf(t) {
    var panel = t.closest('[data-panel], section[id], .pnl');
    return location.pathname + '|' + (panel && (panel.getAttribute('data-panel') || panel.id) || '') + '|' +
      Array.prototype.indexOf.call(document.querySelectorAll('table.tbl'), t);
  }
  function rows(t) { return t.tBodies.length ? Array.prototype.slice.call(t.tBodies[0].rows) : []; }

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function button(label, title, fn) {
    var b = el('button', 'fk tbl-pg-b', label);
    b.type = 'button'; b.title = title; b.setAttribute('aria-label', title);
    b.addEventListener('click', fn);
    return b;
  }

  function enhance(t) {
    if (t.__paged || !t.tBodies.length || t.hasAttribute('data-plain')) { return; }   // data-plain: a form, not data
    t.__paged = true;
    var k = keyOf(t), st = state[k] || (state[k] = { page: 0, sel: -1 });
    var bar = el('div', 'tbl-pg'), info = el('span', 'tbl-pg-info mono');
    var sizeSel = el('select', 'tbl-pg-size');
    sizeSel.setAttribute('aria-label', 'Rows per page');
    SIZES.forEach(function (n) { var o = el('option', null, n + ' rows'); o.value = n; sizeSel.appendChild(o); });
    sizeSel.value = size();

    function pages() { return Math.max(1, Math.ceil(rows(t).length / size())); }
    function render() {
      var all = rows(t), per = size(), n = pages();
      st.page = Math.min(Math.max(0, st.page), n - 1);
      all.forEach(function (r, i) {
        r.hidden = Math.floor(i / per) !== st.page;
        r.classList.toggle('tbl-sel', i === st.sel);
        r.setAttribute('aria-selected', i === st.sel ? 'true' : 'false');
      });
      var from = all.length ? st.page * per + 1 : 0, to = Math.min(all.length, (st.page + 1) * per);
      info.textContent = from + '–' + to + ' of ' + all.length + (n > 1 ? ' · page ' + (st.page + 1) + ' of ' + n : '');
      bar.querySelectorAll('[data-pg=back]').forEach(function (b) { b.disabled = st.page === 0; });
      bar.querySelectorAll('[data-pg=fwd]').forEach(function (b) { b.disabled = st.page >= n - 1; });
    }
    function go(p) { st.page = p; render(); }
    function select(i, scroll) {
      var all = rows(t);
      if (!all.length) { return; }
      st.sel = Math.min(Math.max(0, i), all.length - 1);
      st.page = Math.floor(st.sel / size());
      render();
      if (scroll && all[st.sel].scrollIntoView) { all[st.sel].scrollIntoView({ block: 'nearest' }); }
    }
    function open() {
      var r = rows(t)[st.sel], a = r && r.querySelector('a[href]');
      if (a) { a.click(); }
    }

    var first = button('«', 'First page', function () { go(0); }), prev = button('‹', 'Previous page', function () { go(st.page - 1); });
    var next = button('›', 'Next page', function () { go(st.page + 1); }), last = button('»', 'Last page', function () { go(pages() - 1); });
    var up = button('▲', 'Previous row (↑)', function () { select(st.sel < 0 ? 0 : st.sel - 1, true); t.focus(); });
    var down = button('▼', 'Next row (↓)', function () { select(st.sel + 1, true); t.focus(); });
    [first, prev].forEach(function (b) { b.setAttribute('data-pg', 'back'); });
    [next, last].forEach(function (b) { b.setAttribute('data-pg', 'fwd'); });
    [first, prev, info, next, last, el('span', 'tbl-pg-gap'), up, down, sizeSel].forEach(function (x) { bar.appendChild(x); });
    var wrap = t.closest('.tbl-wrap') || t;
    wrap.parentNode.insertBefore(bar, wrap.nextSibling);

    sizeSel.addEventListener('change', function () {
      try { localStorage.setItem(KEY, sizeSel.value); } catch (e) { /* private window: this page only */ }
      document.querySelectorAll('.tbl-pg-size').forEach(function (s) { s.value = sizeSel.value; });
      document.dispatchEvent(new CustomEvent('drishti:table-size'));
    });
    document.addEventListener('drishti:table-size', function () { if (!t.isConnected) { return; } st.page = st.sel >= 0 ? Math.floor(st.sel / size()) : 0; render(); });

    t.tabIndex = 0;
    t.setAttribute('aria-label', (t.getAttribute('aria-label') || 'Table') + ': ↑ ↓ to move, Enter to open');
    t.addEventListener('click', function (e) {
      var tr = e.target.closest('tbody tr');
      if (tr) { st.sel = rows(t).indexOf(tr); render(); }
    });
    t.addEventListener('keydown', function (e) {
      if (e.target !== t && e.target.closest('input, select, textarea, button')) { return; }
      var per = size(), handled = true;
      switch (e.key) {
        case 'ArrowDown': select(st.sel + 1, true); break;
        case 'ArrowUp': select(st.sel < 0 ? 0 : st.sel - 1, true); break;
        case 'PageDown': select(st.sel < 0 ? per : st.sel + per, true); break;
        case 'PageUp': select(st.sel - per, true); break;
        case 'Home': select(0, true); break;
        case 'End': select(rows(t).length - 1, true); break;
        case 'Enter': if (st.sel >= 0) { open(); } else { handled = false; } break;
        default: handled = false;
      }
      if (handled) { e.preventDefault(); }
    });
    render();
  }

  function scan(root) {
    (root.querySelectorAll ? root.querySelectorAll('table.tbl') : []).forEach(enhance);
    if (root.matches && root.matches('table.tbl')) { enhance(root); }
  }
  scan(document);
  // Live updates and workspace panes replace panels: page their new tables too, at the same page.
  new MutationObserver(function (list) {
    list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { scan(n); } }); });
  }).observe(document.body, { childList: true, subtree: true });
})();
