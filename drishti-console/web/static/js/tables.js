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
/* Every table sorts, filters, pages and can be walked with the keyboard.
   - Sort: click a column heading (again to reverse, a third time to restore the server's order). Numbers, amounts
     (1.5m, 250k, −30,205,543), percentages and dates sort as values, text alphabetically.
   - Filter: the box in the table's heading matches any cell; the funnel shows a box per column, where a number
     column also takes >, <, >= and <= (">1m"). It is on every table (table and ladder panels, pick lists, admin
     lists), in the panel's heading bar or a strip above a table without one; a Sutra turns it off with search: false.
   - Page: first, previous, next, last (greyed when there is nowhere to go) and rows per page (remembered per
     browser, 25 by default); ▲ ▼ move a row. All of these sit in the heading too, never under the table.
   - Keys: focus the table (or click a row), then ↑ ↓ move (turning pages), PgUp/PgDn page, Home/End jump, Enter opens.
   Tables that live updates re-render keep their sort, filters, page and selection. A table with data-plain is left as
   it is (a form laid out as a table). */
(function () {
  'use strict';
  var me = document.currentScript;
  var SIZES = [25, 50, 100, 250];
  var MIN_FIT = 5;
  var KEY = 'drishti.tableRows';
  /** init(root, options): sort, filter and page every table under root (the document or a ShadowRoot), now and as panels are
      swapped in. options.scope names the page for remembered state (default: the path). Returns {scan, dispose}. */
  function init(root, options) {
  options = options || {};
  var scope = options.scope || location.pathname;
  var state = {};                      // a table's key -> its view state, so a re-rendered table keeps its place

  function size() {
    try { var n = parseInt(localStorage.getItem(KEY), 10); return SIZES.indexOf(n) >= 0 ? n : 25; } catch (e) { return 25; }
  }
  function keyOf(t) {
    var panel = t.closest('[data-panel], section[id], .pnl');
    return scope + '|' + (panel && (panel.getAttribute('data-panel') || panel.id) || '') + '|' +
      Array.prototype.indexOf.call(root.querySelectorAll('table.tbl'), t);
  }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function button(label, title, fn) {
    var b = el('button', 'fk tbl-pg-b', label);
    b.type = 'button'; b.title = title; b.setAttribute('aria-label', title);
    b.addEventListener('click', fn);
    return b;
  }
  function cellText(r, i) { var c = r.cells[i]; return c ? c.textContent.replace(/\s+/g, ' ').trim() : ''; }

  // A cell as a sortable value: a number (amounts with k/m/bn, signs, separators, %), a date, or text.
  var MULT = { k: 1e3, m: 1e6, mm: 1e6, bn: 1e9, b: 1e9, tn: 1e12 };
  function value(text) {
    var t = text.replace(/[−–]/g, '-').replace(/,/g, '').replace(/\s/g, '');
    var m = t.match(/^([+-]?)(\d+(?:\.\d+)?)(k|m|mm|bn|b|tn)?(%|bp)?$/i);
    if (m) { var n = parseFloat(m[2]) * (m[3] ? MULT[m[3].toLowerCase()] : 1); return { n: m[1] === '-' ? -n : n }; }
    if (/^\d{4}-\d{2}-\d{2}/.test(t)) { return { n: Date.parse(t.slice(0, 10)) }; }
    return { s: text.toLowerCase() };
  }
  function compare(a, b) {
    if (a.n != null && b.n != null) { return a.n - b.n; }
    if (a.n != null) { return -1; }
    if (b.n != null) { return 1; }
    return a.s < b.s ? -1 : a.s > b.s ? 1 : 0;
  }
  // A column filter: "abc" contains (any case); ">1m", "<=0", "=5" compare numbers.
  function passes(text, q) {
    var m = q.match(/^\s*(>=|<=|>|<|=)\s*(.+)$/);
    if (m) {
      var v = value(text), w = value(m[2]);
      if (v.n == null || w.n == null) { return false; }
      return m[1] === '>' ? v.n > w.n : m[1] === '<' ? v.n < w.n : m[1] === '>=' ? v.n >= w.n : m[1] === '<=' ? v.n <= w.n : v.n === w.n;
    }
    return text.toLowerCase().indexOf(q.toLowerCase()) >= 0;
  }

  function enhance(t) {
    if (t.__paged || !t.tBodies.length || t.hasAttribute('data-plain')) { return; }
    t.__paged = true;
    var body = t.tBodies[0];
    var original = Array.prototype.slice.call(body.rows);            // the server's order, restored on a third click
    var heads = t.tHead && t.tHead.rows.length ? Array.prototype.slice.call(t.tHead.rows[0].cells) : [];
    var k = keyOf(t);
    var st = state[k] || (state[k] = { page: 0, sel: -1, col: -1, dir: 0, q: '', cols: {}, open: false });

    function all() { return Array.prototype.slice.call(body.rows); }
    function visible() { return all().filter(function (r) { return !r.__out; }); }

    // ---- sorting -----------------------------------------------------------------------------------------------
    function sort() {
      var rows = original.slice();
      if (st.col >= 0 && st.dir) {
        var keyed = rows.map(function (r, i) { return { r: r, v: value(cellText(r, st.col)), i: i }; });
        keyed.sort(function (a, b) { return st.dir * compare(a.v, b.v) || a.i - b.i; });
        rows = keyed.map(function (x) { return x.r; });
      }
      rows.forEach(function (r) { body.appendChild(r); });
      heads.forEach(function (h, i) {
        h.setAttribute('aria-sort', i === st.col && st.dir ? (st.dir > 0 ? 'ascending' : 'descending') : 'none');
      });
    }
    heads.forEach(function (h, i) {
      h.classList.add('tbl-sortable');
      h.tabIndex = 0;
      h.title = (h.title ? h.title + ' · ' : '') + 'Sort';
      function cycle() {
        if (st.col !== i) { st.col = i; st.dir = 1; } else { st.dir = st.dir === 1 ? -1 : st.dir === -1 ? 0 : 1; }
        st.sel = -1; st.page = 0; sort(); render();
      }
      h.addEventListener('click', cycle);
      h.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); cycle(); } });
    });

    // ---- filtering ---------------------------------------------------------------------------------------------
    var filterRow = null;
    function filter() {
      all().forEach(function (r) {
        var out = false;
        if (st.q) {
          var any = false;
          for (var i = 0; i < r.cells.length; i++) { if (passes(cellText(r, i), st.q)) { any = true; break; } }
          out = !any;
        }
        Object.keys(st.cols).forEach(function (c) { if (!out && st.cols[c] && !passes(cellText(r, +c), st.cols[c])) { out = true; } });
        r.__out = out;
      });
    }
    function columnFilters(show) {
      st.open = show;
      if (!show) { if (filterRow) { filterRow.remove(); filterRow = null; } return; }
      if (filterRow || !t.tHead) { return; }
      filterRow = el('tr', 'tbl-filter');
      heads.forEach(function (h, i) {
        var td = el('th'), inp = el('input', 'tbl-filter-in mono');
        inp.type = 'search'; inp.placeholder = 'filter'; inp.value = st.cols[i] || '';
        inp.setAttribute('aria-label', 'Filter ' + (h.textContent || 'column ' + (i + 1)).trim());
        inp.addEventListener('input', function () { st.cols[i] = inp.value.trim(); st.page = 0; st.sel = -1; filter(); render(); });
        inp.addEventListener('click', function (e) { e.stopPropagation(); });
        td.appendChild(inp); filterRow.appendChild(td);
      });
      t.tHead.appendChild(filterRow);
    }

    // ---- paging, selection and the bar -------------------------------------------------------------------------
    var bar = el('div', 'tbl-pg'), info = el('span', 'tbl-pg-info mono');
    var quick = el('input', 'tbl-pg-q mono');
    quick.type = 'search'; quick.placeholder = 'Filter rows'; quick.value = st.q;
    quick.setAttribute('aria-label', 'Filter the rows of this table');
    var funnel = button('⧩', 'Filter by column', function () { columnFilters(!st.open); funnel.classList.toggle('on', st.open); });
    var sizeSel = el('select', 'tbl-pg-size');
    sizeSel.setAttribute('aria-label', 'Rows per page');
    SIZES.forEach(function (n) { var o = el('option', null, n + ' rows'); o.value = n; sizeSel.appendChild(o); });
    sizeSel.value = size();

    // A zoomed panel fits its table to the height (fitOn): the page size is then a temporary one, `fitN` (measured) or the
    // reader's own pick while zoomed (`picked`); neither is remembered, and restoring puts the saved size back.
    var fitOn = false, fitN = null, picked = null;
    function perPage() { return picked || fitN || size(); }
    function pages() { return Math.max(1, Math.ceil(visible().length / perPage())); }
    function render() {
      var vis = visible(), per = perPage(), n = pages();
      st.page = Math.min(Math.max(0, st.page), n - 1);
      all().forEach(function (r) { r.hidden = true; r.classList.remove('tbl-sel'); r.setAttribute('aria-selected', 'false'); });
      vis.forEach(function (r, i) {
        r.hidden = Math.floor(i / per) !== st.page;
        if (i === st.sel) { r.classList.add('tbl-sel'); r.setAttribute('aria-selected', 'true'); }
      });
      var total = all().length, from = vis.length ? st.page * per + 1 : 0, to = Math.min(vis.length, (st.page + 1) * per);
      info.textContent = from + '–' + to + ' of ' + vis.length + (vis.length !== total ? ' (filtered from ' + total + ')' : '')
        + (n > 1 ? ' · page ' + (st.page + 1) + ' of ' + n : '');
      bar.querySelectorAll('[data-pg=back]').forEach(function (b) { b.disabled = st.page === 0; });
      bar.querySelectorAll('[data-pg=fwd]').forEach(function (b) { b.disabled = st.page >= n - 1; });
    }
    function go(p) { st.page = p; render(); }
    function select(i, scroll) {
      var vis = visible();
      if (!vis.length) { return; }
      st.sel = Math.min(Math.max(0, i), vis.length - 1);
      st.page = Math.floor(st.sel / perPage());
      render();
      if (scroll && vis[st.sel].scrollIntoView) { vis[st.sel].scrollIntoView({ block: 'nearest' }); }
    }
    function open() {
      var r = visible()[st.sel], a = r && r.querySelector('a[href]');
      if (a) { a.click(); }
    }

    var first = button('«', 'First page', function () { go(0); }), prev = button('‹', 'Previous page', function () { go(st.page - 1); });
    var next = button('›', 'Next page', function () { go(st.page + 1); }), last = button('»', 'Last page', function () { go(pages() - 1); });
    var up = button('▲', 'Previous row (↑)', function () { select(st.sel < 0 ? 0 : st.sel - 1, true); t.focus(); });
    var down = button('▼', 'Next row (↓)', function () { select(st.sel + 1, true); t.focus(); });
    [first, prev].forEach(function (b) { b.setAttribute('data-pg', 'back'); });
    [next, last].forEach(function (b) { b.setAttribute('data-pg', 'fwd'); });
    // Every control belongs to the table's heading, where it is seen: the panel's heading bar, or a strip above a
    // table that has no panel heading. The filter is for tables only: panels of other kinds, and tables a Sutra
    // marks search: false, page and sort but have no filter.
    var panel = t.closest('.pnl[data-kind]');
    var searchable = !t.hasAttribute('data-no-search');           // every table, unless its Sutra says search: false
    var nav = el('span', 'tbl-pg-nav');
    [first, prev, info, next, last].forEach(function (x) { nav.appendChild(x); });
    if (searchable) { bar.appendChild(quick); bar.appendChild(funnel); }
    [nav, up, down, sizeSel].forEach(function (x) { bar.appendChild(x); });
    var wrap = t.closest('.tbl-wrap') || t;
    var head = panel && panel.querySelector(':scope > .pnl-h');
    if (head && panel.querySelectorAll('table.tbl').length === 1) {
      var old = head.querySelector('.tbl-pg');
      if (old) { old.remove(); }
      bar.classList.add('in-head');
      head.insertBefore(bar, head.querySelector('.pnl-code'));
    } else {
      wrap.parentNode.insertBefore(bar, wrap);      // a strip above the table (pick lists, admin lists, tabs)
    }

    quick.addEventListener('input', function () { st.q = quick.value.trim(); st.page = 0; st.sel = -1; filter(); render(); });
    sizeSel.addEventListener('change', function () {
      if (fitOn) {                                        // zoomed: a pick holds until restore and is not remembered; "Fit" goes back to the measured size
        reflow(function () { picked = sizeSel.value === 'fit' ? null : parseInt(sizeSel.value, 10); });
        syncSel();
        return;
      }
      try { localStorage.setItem(KEY, sizeSel.value); } catch (e) { /* private window: this page only */ }
      root.querySelectorAll('.tbl-pg-size').forEach(function (s) { s.value = sizeSel.value; });
      root.dispatchEvent(new CustomEvent('drishti:table-size'));
    });
    root.addEventListener('drishti:table-size', onSize);
    function onSize() { if (!t.isConnected) { return; } st.page = st.sel >= 0 ? Math.floor(st.sel / perPage()) : 0; render(); syncSel(); }

    // Change the page size and keep the row that was at the top of the page in view.
    var anchor = null, anchorPage = -1;                  // the top row when the fit began, kept while the reader has not paged since
    function reflow(change) {
      if (anchor == null || st.page !== anchorPage) { anchor = st.page * perPage(); }
      change();
      st.page = Math.floor(anchor / perPage());
      render();
      anchorPage = st.page;
    }
    function syncSel() {
      var o = sizeSel.querySelector('option[value=fit]');
      if (fitOn && fitN) {
        if (!o) { o = el('option'); o.value = 'fit'; sizeSel.insertBefore(o, sizeSel.firstChild); }
        o.textContent = 'Fit (' + fitN + ')';
        sizeSel.value = picked ? picked : 'fit';
      } else {
        if (o) { o.remove(); }
        sizeSel.value = size();
      }
    }
    // The height the table may use: the panel body minus everything that is not a row (heading, footer, padding), over a row's height.
    function room() {
      var cont = t.closest('.pnl-b'), shown = all().filter(function (r) { return !r.hidden; });
      if (!cont || !t.offsetParent || !shown.length) { return 0; }
      var sum = shown.reduce(function (a, r) { return a + r.offsetHeight; }, 0);
      if (!sum) { return 0; }
      var cr = cont.getBoundingClientRect(), bottom = 0;
      Array.prototype.forEach.call(cont.children, function (c) { if (c.offsetParent) { bottom = Math.max(bottom, c.getBoundingClientRect().bottom); } });
      var content = bottom - cr.top + cont.scrollTop + (parseFloat(getComputedStyle(cont).paddingBottom) || 0);
      return Math.max(MIN_FIT, Math.floor((cont.clientHeight - (content - sum)) / (sum / shown.length)));
    }
    t.__tblFit = function (on) {
      if (!on) {
        if (!fitOn) { return; }
        reflow(function () { fitOn = false; fitN = null; picked = null; });
        anchor = null;
        syncSel();
        return;
      }
      var n = room();
      if (!n) { return; }
      var cont = t.closest('.pnl-b');
      fitOn = true;
      if (n !== fitN) { reflow(function () { fitN = n; }); }
      for (var i = 0; i < 3 && !picked && fitN > MIN_FIT && cont.scrollHeight > cont.clientHeight + 1; i++) {   // rows taller than the average: one fewer until nothing scrolls
        reflow(function () { fitN -= 1; });
      }
      syncSel();
    };
    if (zoomFit) { scheduleFit(); }

    t.tabIndex = 0;
    t.setAttribute('aria-label', (t.getAttribute('aria-label') || 'Table') + ': ↑ ↓ to move, Enter to open; click a heading to sort');
    t.addEventListener('click', function (e) {
      var tr = e.target.closest('tbody tr');
      if (tr) { st.sel = visible().indexOf(tr); render(); }
    });
    t.addEventListener('keydown', function (e) {
      if (e.target !== t && e.target.closest('input, select, textarea, button, th')) { return; }
      var per = perPage(), handled = true;
      switch (e.key) {
        case 'ArrowDown': select(st.sel + 1, true); break;
        case 'ArrowUp': select(st.sel < 0 ? 0 : st.sel - 1, true); break;
        case 'PageDown': select(st.sel < 0 ? per : st.sel + per, true); break;
        case 'PageUp': select(st.sel - per, true); break;
        case 'Home': select(0, true); break;
        case 'End': select(visible().length - 1, true); break;
        case 'Enter': if (st.sel >= 0) { open(); } else { handled = false; } break;
        default: handled = false;
      }
      if (handled) { e.preventDefault(); }
    });

    if (st.open) { columnFilters(true); funnel.classList.add('on'); }
    sort(); filter(); render();
  }

  function scan(node) {
    (node.querySelectorAll ? node.querySelectorAll('table.tbl') : []).forEach(enhance);
    if (node.matches && node.matches('table.tbl')) { enhance(node); }
  }
  // ---- a zoomed panel: its tables fit the height (zoom.js says so with drishti:table-fit; the class pnl-zoom is the state) ----
  var fitTimer = 0, fitRo = null, fitMo = null, fitSeen = null;
  function zoomedPanel() { return root.querySelector('.pnl.pnl-zoom'); }
  var zoomFit = false;
  function fitAll(on) {
    var p = on ? zoomedPanel() : root;
    (p ? p.querySelectorAll('table.tbl') : []).forEach(function (x) { if (x.__tblFit) { x.__tblFit(on); } });
    if (on && p) { watchFit(p); }
  }
  function scheduleFit() { if (!fitTimer && zoomFit) { fitTimer = setTimeout(function () { fitTimer = 0; if (zoomFit) { fitAll(true); } }, 100); } }
  function watchFit(p) {
    if (fitSeen === p) { return; }
    unwatchFit();
    fitSeen = p;
    var body = p.querySelector(':scope > .pnl-b') || p;
    if (window.ResizeObserver) { fitRo = new ResizeObserver(scheduleFit); fitRo.observe(body); }
    fitMo = new MutationObserver(function (list) {          // a tab or pane shown, rows added or removed by a live patch; not our own paging
      for (var i = 0; i < list.length; i++) {
        var m = list[i], tg = m.target;
        if (tg.nodeType !== 1 || (tg.closest && tg.closest('.tbl-pg')) || tg.tagName === 'TR') { continue; }
        scheduleFit();
        return;
      }
    });
    fitMo.observe(p, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });
  }
  function unwatchFit() {
    if (fitRo) { fitRo.disconnect(); fitRo = null; }
    if (fitMo) { fitMo.disconnect(); fitMo = null; }
    fitSeen = null;
  }
  function onFit(e) {
    var on = !!(e.detail && e.detail.on);
    zoomFit = on;
    if (!on) { if (fitTimer) { clearTimeout(fitTimer); fitTimer = 0; } unwatchFit(); }
    fitAll(on);
  }
  root.addEventListener('drishti:table-fit', onFit);
  zoomFit = !!zoomedPanel();                      // zoomed from the link before this ran
  scan(root);
  if (zoomFit) { fitAll(true); }
  // Live updates and workspace panes replace panels: enhance their new tables too, with the same sort and filters.
  var mo = new MutationObserver(function (list) {
    list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { scan(n); } }); });
  });
  mo.observe(root === document ? document.body : root, { childList: true, subtree: true });
  return { scan: scan, dispose: function () { mo.disconnect(); root.removeEventListener('drishti:table-fit', onFit); unwatchFit(); if (fitTimer) { clearTimeout(fitTimer); } } };
  }
  var reg = (window.drishtiModules = window.drishtiModules || {});
  reg.tables = { init: init };
  if (!(me && me.hasAttribute('data-manual'))) { init(document); }
})();
