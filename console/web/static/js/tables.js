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
     column also takes >, <, >= and <= (">1m"). In a table panel the box sits in the panel's heading bar (a Sutra turns
     it off with search: false); a table outside a panel gets a slim heading strip of its own. Other panel kinds
     (ladders, tabs, key-value lists) have no filter: search is for tables.
   - Page: first, previous, next, last and rows per page (remembered per browser, 25 by default); ▲ ▼ move a row.
   - Keys: focus the table (or click a row), then ↑ ↓ move (turning pages), PgUp/PgDn page, Home/End jump, Enter opens.
   Tables that live updates re-render keep their sort, filters, page and selection. A table with data-plain is left as
   it is (a form laid out as a table). */
(function () {
  'use strict';
  var SIZES = [25, 50, 100, 250];
  var KEY = 'drishti.tableRows';
  var state = {};                      // a table's key -> its view state, so a re-rendered table keeps its place

  function size() {
    try { var n = parseInt(localStorage.getItem(KEY), 10); return SIZES.indexOf(n) >= 0 ? n : 25; } catch (e) { return 25; }
  }
  function keyOf(t) {
    var panel = t.closest('[data-panel], section[id], .pnl');
    return location.pathname + '|' + (panel && (panel.getAttribute('data-panel') || panel.id) || '') + '|' +
      Array.prototype.indexOf.call(document.querySelectorAll('table.tbl'), t);
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

    function pages() { return Math.max(1, Math.ceil(visible().length / size())); }
    function render() {
      var vis = visible(), per = size(), n = pages();
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
      st.page = Math.floor(st.sel / size());
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
    [first, prev, info, next, last, el('span', 'tbl-pg-gap'), up, down, sizeSel].forEach(function (x) { bar.appendChild(x); });
    var wrap = t.closest('.tbl-wrap') || t;
    wrap.parentNode.insertBefore(bar, wrap.nextSibling);

    // The filter belongs to the table's heading, where it is seen: a table panel's heading bar, or a strip above a
    // table that has no panel heading. Panels of other kinds, and tables a Sutra marks search: false, have none.
    var panel = t.closest('.pnl[data-kind]');
    var searchable = !t.hasAttribute('data-no-search') && (!panel || panel.getAttribute('data-kind') === 'table');
    if (searchable) {
      var tools = el('span', 'tbl-search');
      tools.appendChild(quick); tools.appendChild(funnel);
      var head = panel && panel.querySelector(':scope > .pnl-h');
      if (head) {
        var old = head.querySelector('.tbl-search');
        if (old) { old.remove(); }
        head.insertBefore(tools, head.querySelector('.pnl-code'));
      } else {
        var strip = el('div', 'tbl-head');
        strip.appendChild(tools);
        wrap.parentNode.insertBefore(strip, wrap);
      }
    }

    quick.addEventListener('input', function () { st.q = quick.value.trim(); st.page = 0; st.sel = -1; filter(); render(); });
    sizeSel.addEventListener('change', function () {
      try { localStorage.setItem(KEY, sizeSel.value); } catch (e) { /* private window: this page only */ }
      document.querySelectorAll('.tbl-pg-size').forEach(function (s) { s.value = sizeSel.value; });
      document.dispatchEvent(new CustomEvent('drishti:table-size'));
    });
    document.addEventListener('drishti:table-size', function () { if (!t.isConnected) { return; } st.page = st.sel >= 0 ? Math.floor(st.sel / size()) : 0; render(); });

    t.tabIndex = 0;
    t.setAttribute('aria-label', (t.getAttribute('aria-label') || 'Table') + ': ↑ ↓ to move, Enter to open; click a heading to sort');
    t.addEventListener('click', function (e) {
      var tr = e.target.closest('tbody tr');
      if (tr) { st.sel = visible().indexOf(tr); render(); }
    });
    t.addEventListener('keydown', function (e) {
      if (e.target !== t && e.target.closest('input, select, textarea, button, th')) { return; }
      var per = size(), handled = true;
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

  function scan(root) {
    (root.querySelectorAll ? root.querySelectorAll('table.tbl') : []).forEach(enhance);
    if (root.matches && root.matches('table.tbl')) { enhance(root); }
  }
  scan(document);
  // Live updates and workspace panes replace panels: enhance their new tables too, with the same sort and filters.
  new MutationObserver(function (list) {
    list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { scan(n); } }); });
  }).observe(document.body, { childList: true, subtree: true });
})();
