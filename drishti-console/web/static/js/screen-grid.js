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
/* A screen laid out with layout: (SUTRA_NEXT.md 13; terminal/_grid.html, core/screen_grid.py). Draws the picture of named areas (the
   CSP allows no style attributes, so the template is set here), the tablet picture, the phone order; the split's divider (drag it, or
   focus it and use the arrows; its position is kept for the reader when the Sutra remembers it); tabs; sections and panels that fold
   (kept for the reader); and, with user-can-arrange, a handle to drag a panel to another place or area and a grip to resize it, with
   "Reset layout". The reader's arrangement is PUT whole to /me/arrange/<sutra>; it never changes the shared Sutra. */
(function () {
  'use strict';
  var TABLET = '(max-width: 1000px) and (min-width: 641px)', PHONE = '(max-width: 640px)';

  function cellsOf(row) { return String(row || '').trim().split(/\s+/).filter(Boolean); }
  function quoted(rows) { return rows.map(function (r) { return '"' + cellsOf(r).join(' ') + '"'; }).join(' '); }
  function fr(cols) { return cols.map(function (c) { return 'minmax(0, ' + (+c) + 'fr)'; }).join(' '); }

  function init(root) {
    if (!root || root.hasAttribute('data-sg-ready')) { return; }
    root.setAttribute('data-sg-ready', '');
    var cfg = {};
    try { cfg = JSON.parse(root.getAttribute('data-sg') || '{}'); } catch (e) { cfg = {}; }
    var sutra = root.getAttribute('data-sg-sutra') || '';
    var mine = cfg.mine || {};
    var cols = (cfg.columns || []).slice();
    var areasEl = root.querySelector('[data-sg-areas]');
    var saving = null, seq = 0;

    function save() {
      if (!sutra) { return Promise.resolve(); }
      var body = {};
      ['columns', 'collapsed', 'panels'].forEach(function (k) { if (mine[k] !== undefined) { body[k] = mine[k]; } });
      clearTimeout(saving);
      return new Promise(function (ok) {
        var ticket = ++seq;
        saving = setTimeout(function () {
          fetch('/me/arrange/' + encodeURIComponent(sutra), { method: 'PUT', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
            .then(function (r) { if (ticket === seq) { root.setAttribute('data-sg-saved', String(Date.now())); } var b = root.querySelector('[data-sg-reset]'); if (b) { b.hidden = false; } ok(r); })
            .catch(function () { ok(null); });
        }, 150);
      });
    }

    /* the picture */
    function picture() {
      if (!areasEl) { return; }
      var rows = cfg.picture || [], c = cols;
      if (window.matchMedia(TABLET).matches) {
        if ((cfg.tablet || []).length) { rows = cfg.tablet; c = cfg.tabletColumns && cfg.tabletColumns.length ? cfg.tabletColumns : null; }
        else {                                                   // the right column drops under the main one: the areas, one a row
          var seen = []; rows.forEach(function (r) { cellsOf(r).forEach(function (n) { if (n !== '.' && seen.indexOf(n) < 0) { seen.push(n); } }); });
          rows = seen; c = [12];
        }
      }
      var width = cellsOf(rows[0]).length || 1;
      areasEl.style.gridTemplateAreas = quoted(rows);
      areasEl.style.gridTemplateColumns = fr(c && c.length === width ? c : Array(width).fill(1));
      areasEl.querySelectorAll('[data-sg-area]').forEach(function (a) { a.style.gridArea = a.getAttribute('data-sg-area'); });
      placeSplit();
    }

    /* the phone order */
    var phone = cfg.phone || {};
    root.querySelectorAll('.pnl[data-panel]').forEach(function (p) {
      var n = phone[p.getAttribute('data-panel')];
      if (n !== undefined) { p.style.setProperty('--sg-po', n); }
    });
    root.querySelectorAll('.sg-tabbed').forEach(function (a) {
      var ns = [].map.call(a.querySelectorAll('.pnl[data-panel]'), function (p) { return phone[p.getAttribute('data-panel')] || 0; });
      a.style.setProperty('--sg-po', ns.length ? Math.min.apply(null, ns) : 0);
    });

    /* the split */
    var split = cfg.split, bar = null;
    function boundary() {                                        // the column index where the left area ends and the right begins
      var rows = cfg.picture || [];
      for (var r = 0; r < rows.length; r++) {
        var cs = cellsOf(rows[r]);
        for (var i = 0; i + 1 < cs.length; i++) { if (cs[i] === split.left && cs[i + 1] === split.right) { return i + 1; } }
      }
      return -1;
    }
    function placeSplit() {
      if (!bar) { return; }
      var l = areasEl.querySelector('[data-sg-area="' + split.left + '"]'), r = areasEl.querySelector('[data-sg-area="' + split.right + '"]');
      var stacked = window.matchMedia(TABLET).matches || window.matchMedia(PHONE).matches;
      if (!l || !r || stacked) { bar.hidden = true; return; }
      bar.hidden = false;
      var box = areasEl.getBoundingClientRect(), lb = l.getBoundingClientRect(), rb = r.getBoundingClientRect();
      var top = Math.max(lb.top, rb.top) - box.top, bottom = Math.min(lb.bottom, rb.bottom) - box.top;
      if (bottom - top < 40) { top = Math.min(lb.top, rb.top) - box.top; bottom = Math.max(lb.bottom, rb.bottom) - box.top; }
      bar.style.left = ((lb.right + rb.left) / 2 - box.left) + 'px';
      bar.style.top = top + 'px';
      bar.style.height = Math.max(40, bottom - top) + 'px';
      var k = boundary();
      bar.setAttribute('aria-valuenow', String(Math.round(cols.slice(0, k).reduce(function (a, b) { return a + b; }, 0) / 12 * 100)));
    }
    function nudge(k, delta) {
      var a = cols[k - 1] + delta, b = cols[k] - delta;
      if (a < 1.5 || b < 1.5) { return false; }
      cols[k - 1] = Math.round(a * 4) / 4; cols[k] = Math.round(b * 4) / 4;
      areasEl.style.gridTemplateColumns = fr(cols);
      placeSplit();
      return true;
    }
    function keep() { if (split.remember !== 'none') { mine.columns = cols.slice(); save(); } }
    if (split && areasEl && boundary() > 0) {
      var k = boundary();
      if (cols.length !== cellsOf((cfg.picture || [])[0]).length) { cols = Array(cellsOf(cfg.picture[0]).length).fill(12 / cellsOf(cfg.picture[0]).length); }
      bar = document.createElement('div');
      bar.className = 'sg-split';
      bar.setAttribute('role', 'separator');
      bar.setAttribute('aria-orientation', 'vertical');
      bar.setAttribute('aria-label', 'Divider between ' + split.left + ' and ' + split.right + (split.resizable ? ': drag, or use the arrow keys' : ''));
      bar.setAttribute('aria-valuemin', '0'); bar.setAttribute('aria-valuemax', '100');
      bar.setAttribute('data-sg-split', '');
      areasEl.appendChild(bar);
      if (split.resizable) {
        bar.tabIndex = 0;
        bar.addEventListener('keydown', function (e) {
          var d = e.key === 'ArrowLeft' ? -0.5 : e.key === 'ArrowRight' ? 0.5 : 0;
          if (d && nudge(k, d)) { e.preventDefault(); keep(); }
        });
        bar.addEventListener('pointerdown', function (e) {
          e.preventDefault();
          bar.setPointerCapture(e.pointerId);
          bar.classList.add('sg-dragging');
          var box = areasEl.getBoundingClientRect();
          function move(ev) {
            var want = (ev.clientX - box.left) / box.width * 12, have = cols.slice(0, k).reduce(function (a, b) { return a + b; }, 0);
            nudge(k, want - have);
          }
          function up() {
            bar.classList.remove('sg-dragging');
            bar.removeEventListener('pointermove', move); bar.removeEventListener('pointerup', up); bar.removeEventListener('pointercancel', up);
            keep();
          }
          bar.addEventListener('pointermove', move); bar.addEventListener('pointerup', up); bar.addEventListener('pointercancel', up);
        });
      }
      if (window.ResizeObserver) { new ResizeObserver(placeSplit).observe(areasEl); }
    }
    picture();
    window.matchMedia(TABLET).addEventListener('change', picture);
    window.matchMedia(PHONE).addEventListener('change', picture);

    /* tabs */
    root.querySelectorAll('.sg-tabbed').forEach(function (a) {
      var tabs = [].slice.call(a.querySelectorAll('[data-sg-tab]'));
      function pick(t, focus) {
        tabs.forEach(function (x) {
          var on = x === t, p = document.getElementById('p-' + x.getAttribute('data-sg-tab'));
          x.setAttribute('aria-selected', on ? 'true' : 'false'); x.tabIndex = on ? 0 : -1;
          if (p) { p.hidden = !on; p.setAttribute('role', 'tabpanel'); }
        });
        if (focus) { t.focus(); }
        if (bar) { placeSplit(); }
      }
      tabs.forEach(function (t, i) {
        t.addEventListener('click', function () { pick(t); });
        t.addEventListener('keydown', function (e) {
          var j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : -2;
          if (j !== -2) { e.preventDefault(); pick(tabs[(j + tabs.length) % tabs.length], true); }
        });
      });
      if (tabs.length) { pick(tabs[0]); }
    });

    /* folding: sections and collapsible panels, kept for the reader */
    function folded(id, closed) { mine.collapsed = mine.collapsed || {}; mine.collapsed[id] = closed; save(); if (bar) { placeSplit(); } }
    root.querySelectorAll('[data-sg-fold]').forEach(function (b) {
      b.addEventListener('click', function () {
        var sec = b.closest('[data-sg-section]'), body = sec.querySelector('[data-sg-in]'), closed = b.getAttribute('aria-expanded') === 'true';
        b.setAttribute('aria-expanded', closed ? 'false' : 'true');
        sec.classList.toggle('sg-closed', closed);
        body.hidden = closed;
        if (!closed && window.drishti && window.drishti.enhance) { window.drishti.enhance(body); }   // charts drawn in a closed section
        folded(sec.getAttribute('data-sg-section'), closed);
      });
    });
    Object.keys(cfg.fold || {}).forEach(function (pid) {
      var p = document.getElementById('p-' + pid), h = p && p.querySelector('.pnl-h');
      if (!h || h.querySelector('.sg-fold')) { return; }
      var b = document.createElement('button');
      b.type = 'button'; b.className = 'sg-fold'; b.setAttribute('data-sg-fold-panel', pid);
      b.setAttribute('aria-controls', 'p-' + pid);
      b.innerHTML = '<i class="bi bi-chevron-up" aria-hidden="true"></i>';
      function set(closed) {
        p.classList.toggle('sg-folded', closed);
        b.setAttribute('aria-expanded', closed ? 'false' : 'true');
        b.setAttribute('aria-label', (closed ? 'Open ' : 'Fold ') + (h.textContent || pid).trim().split('\n')[0]);
        b.firstChild.className = 'bi ' + (closed ? 'bi-chevron-down' : 'bi-chevron-up');
      }
      set(!!cfg.fold[pid]);
      b.addEventListener('click', function () { var c = !p.classList.contains('sg-folded'); set(c); folded(pid, c); });
      h.insertBefore(b, h.firstChild);
    });

    /* a reader arranging (user-can-arrange) */
    if (root.hasAttribute('data-sg-arrange')) { arrange(root, cfg, mine, save); }
    var reset = root.querySelector('[data-sg-reset]');
    if (reset) {
      reset.addEventListener('click', function () {
        fetch('/me/arrange/' + encodeURIComponent(sutra), { method: 'DELETE', credentials: 'same-origin' })
          .then(function () { location.reload(); });
      });
    }
  }

  function spanOf(p) { var m = /c-span-(\d+)/.exec(p.className); return m ? +m[1] : 12; }
  function setSpan(p, n) {
    p.className = p.className.replace(/\s*c-span-\d+/g, '') + (n < 12 ? ' c-span-' + n : '');
    p.setAttribute('data-span', String(n));
  }

  function arrange(root, cfg, mine, save) {
    var areas = cfg.mode === 'areas';
    function record() {
      var out = [], n = 0;
      root.querySelectorAll('[data-sg-cells]').forEach(function (c) {
        [].forEach.call(c.children, function (p) {
          if (!p.matches('.pnl[data-panel]')) { return; }
          var e = { id: p.getAttribute('data-panel'), span: spanOf(p), order: n++ };
          if (areas && c.getAttribute('data-sg-cells')) { e.area = c.getAttribute('data-sg-cells'); }
          out.push(e);
        });
      });
      mine.panels = out;
      return save();
    }
    var dragged = null;
    root.querySelectorAll('[data-sg-cells] > .pnl[data-panel]').forEach(function (p) {
      var h = p.querySelector('.pnl-h'), title = (p.querySelector('.pnl-h h3') || {}).textContent || p.getAttribute('data-panel');
      if (!h || h.querySelector('.sg-handle')) { return; }
      var handle = document.createElement('span');
      handle.className = 'sg-handle'; handle.textContent = '⋮⋮'; handle.title = 'Drag to move ' + title;
      handle.setAttribute('draggable', 'true'); handle.setAttribute('data-sg-handle', '');
      handle.addEventListener('dragstart', function (e) { dragged = p; e.dataTransfer.setData('text/x-sg-panel', p.getAttribute('data-panel')); e.dataTransfer.effectAllowed = 'move'; });
      handle.addEventListener('dragend', function () { dragged = null; });
      handle.tabIndex = 0; handle.setAttribute('role', 'button');
      handle.setAttribute('aria-label', 'Move ' + title + ': arrow up or down within its area, left or right to another area');
      handle.addEventListener('keydown', function (e) {                    // the keyboard's way to move a panel
        var cells = p.parentNode, all = [].slice.call(root.querySelectorAll('[data-sg-cells]')), i = all.indexOf(cells);
        if (e.key === 'ArrowUp' && p.previousElementSibling) { cells.insertBefore(p, p.previousElementSibling); }
        else if (e.key === 'ArrowDown' && p.nextElementSibling) { cells.insertBefore(p.nextElementSibling, p); }
        else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && all.length > 1) { all[(i + (e.key === 'ArrowLeft' ? all.length - 1 : 1)) % all.length].appendChild(p); }
        else { return; }
        e.preventDefault(); handle.focus(); record();
      });
      h.insertBefore(handle, h.firstChild);
      // captured: a table's own drag handling (its columns) must not swallow a panel being moved
      p.addEventListener('dragover', function (e) { if (dragged && dragged !== p) { e.preventDefault(); e.stopPropagation(); p.classList.add('sg-over'); } }, true);
      p.addEventListener('dragleave', function () { p.classList.remove('sg-over'); });
      p.addEventListener('drop', function (e) {
        p.classList.remove('sg-over');
        if (!dragged || dragged === p) { return; }
        e.preventDefault(); e.stopPropagation();
        p.parentNode.insertBefore(dragged, p);
        dragged = null;
        record();
      }, true);
      var grip = document.createElement('span');
      grip.className = 'sg-grip'; grip.tabIndex = 0; grip.setAttribute('role', 'slider'); grip.setAttribute('data-sg-grip', '');
      grip.setAttribute('aria-label', 'Width of ' + title + ' in columns of 12'); grip.setAttribute('aria-valuemin', '2'); grip.setAttribute('aria-valuemax', '12');
      grip.setAttribute('aria-valuenow', String(spanOf(p)));
      function to(n) { n = Math.max(2, Math.min(12, n)); setSpan(p, n); grip.setAttribute('aria-valuenow', String(n)); }
      grip.addEventListener('keydown', function (e) {
        var d = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
        if (d) { e.preventDefault(); to(spanOf(p) + d); record(); }
      });
      grip.addEventListener('pointerdown', function (e) {
        e.preventDefault(); e.stopPropagation();
        grip.setPointerCapture(e.pointerId);
        var cells = p.parentNode.getBoundingClientRect(), left = p.getBoundingClientRect().left;
        function move(ev) { to(Math.round((ev.clientX - left) / (cells.width / 12))); }
        function up() { grip.removeEventListener('pointermove', move); grip.removeEventListener('pointerup', up); record(); }
        grip.addEventListener('pointermove', move); grip.addEventListener('pointerup', up);
      });
      p.appendChild(grip);
    });
    root.querySelectorAll('[data-sg-cells]').forEach(function (c) {         // a drop on an area's free space: the end of that area
      c.addEventListener('dragover', function (e) { if (dragged) { e.preventDefault(); c.classList.add('sg-over-area'); } });
      c.addEventListener('dragleave', function () { c.classList.remove('sg-over-area'); });
      c.addEventListener('drop', function (e) {
        c.classList.remove('sg-over-area');
        if (!dragged) { return; }
        e.preventDefault();
        c.appendChild(dragged);
        record();
      });
    });
  }

  window.drishtiScreenGrid = { init: init };
  document.querySelectorAll('[data-sgrid]').forEach(init);
})();
