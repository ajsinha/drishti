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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/*
 * Resizable panels and columns: the one component every screen uses (no screen writes its own drag handle).
 *
 * Declared by markup, bound by itself (on load and whenever such markup appears):
 *
 *   Splitter between a side panel and the main area: a handle inside the panel (the panel is its parent, or data-resize-target).
 *     <aside class="vx-side">… <div data-resize="panel" data-resize-key="<screen>.<panel>" data-resize-var="--vx-side"
 *                                   data-resize-min="220" data-resize-max="640" aria-label="Resize the Scopes panel"></div></aside>
 *     The width goes to the CSS variable data-resize-var (default --d-panel-w) on the panel's parent, so the layout's own CSS says
 *     where it lands: .vx-layout { grid-template-columns: var(--vx-side, 330px) minmax(0, 1fr); }. data-resize-edge="left" for a
 *     panel on the right (its handle on its left edge; dragging left widens it). Without data-resize-var the panel's own width is set.
 *
 *   Column resize on a table, tree or grid header: data-resize="columns" (+ data-resize-key) on
 *     - an HTML <table> (or its <thead> / header <tr>): a handle in each header cell; widths go to the header cells and the table is
 *       laid out fixed, so the body follows; or
 *     - a CSS-grid header row (its children are the columns): the widths go to the CSS variable data-resize-var (default --rz-cols)
 *       on the closest data-resize-scope element (or the header's parent), which the rows' CSS uses:
 *       .vx-row { grid-template-columns: var(--rz-cols, minmax(0, 1fr) 58px 58px 52px); }. data-resize-flex="<index>" names the
 *       column that takes up the rest of the row (it keeps the user's width as its least).
 *     data-resize-min (default 40 px) is the least any column can be, so a column never vanishes.
 *
 *   Widths are remembered per person and per mode: localStorage "drishti.resize.<key>[@<mode>]", where the mode is the closest
 *   data-resize-mode attribute (a screen sets it, e.g. data-resize-mode="parametric"; changing it re-applies that mode's widths).
 *   Never server state. Without localStorage nothing breaks: widths just are not kept.
 *
 *   Every handle: role separator, focusable, aria-valuenow (its width in px); drag; Left / Right move it (Shift: further); Home and a
 *   double-click reset it (the remembered width is forgotten). Below 640 px wide (phones) the handles are hidden and no remembered
 *   width is applied: the single-column layouts stay.
 *
 * Script API (window.Drishti.resize):
 *   scan(root)                         bind every data-resize element under root (done by itself on load and on DOM changes)
 *   handle(el, opts) -> el             the low-level handle the others are built on, for a screen with its own model of sizes
 *       opts: axis 'x' | 'y'; label; start(e) -> ctx; move(ctx, dx, dy, e); end(ctx); key(step, e) -> handled (step -1 / +1,
 *       x4 with Shift); reset(); value() -> {now, min, max} for ARIA. Pointer capture, the dragging state (html[data-rz-dragging],
 *       frames ignore the pointer), Arrow keys, Home, double-click and phone-off are handled here.
 *   reset(key)                         forget a remembered width set (all modes) and re-apply defaults
 *   phone()                            true when resizing is off (below 640 px)
 */
(function () {
  'use strict';
  var NS = 'drishti.resize.', PHONE = '(max-width: 639.98px)', STEP = 16;
  var mq = window.matchMedia ? window.matchMedia(PHONE) : null;
  function phone() { return !!(mq && mq.matches); }
  function num(v, d) { var n = parseFloat(v); return isFinite(n) ? n : d; }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  /* ---- memory: per person (this browser) and per mode; never shared server state --------------------------------------------- */
  function modeOf(el) { var m = el.closest && el.closest('[data-resize-mode]'); return m ? m.getAttribute('data-resize-mode') : ''; }
  function slot(key, el) { var m = modeOf(el); return NS + key + (m ? '@' + m : ''); }
  function load(key, el) {
    if (!key) { return null; }
    try { var s = window.localStorage.getItem(slot(key, el)); return s ? JSON.parse(s) : null; } catch (e) { return null; }
  }
  function store(key, el, v) {
    if (!key) { return; }
    try { if (v === null) { window.localStorage.removeItem(slot(key, el)); } else { window.localStorage.setItem(slot(key, el), JSON.stringify(v)); } } catch (e) { /* private mode, quota: not kept */ }
  }
  function forget(key) {
    try {
      var ls = window.localStorage, gone = [];
      for (var i = 0; i < ls.length; i++) { var k = ls.key(i); if (k === NS + key || k.indexOf(NS + key + '@') === 0) { gone.push(k); } }
      gone.forEach(function (k) { ls.removeItem(k); });
    } catch (e) { /* nothing kept */ }
  }

  /* ---- the handle: drag, keys, double-click, ARIA ------------------------------------------------------------------------------ */
  function aria(el, o) {
    if (!o.value) { return; }
    var v = o.value();
    if (!v) { return; }
    el.setAttribute('aria-valuenow', String(Math.round(v.now)));
    if (v.min != null) { el.setAttribute('aria-valuemin', String(Math.round(v.min))); }
    if (v.max != null) { el.setAttribute('aria-valuemax', String(Math.round(v.max))); }
  }
  function handle(el, o) {
    if (el.__rz) { el.__rz = o; aria(el, o); return el; }
    el.__rz = o;
    var y = o.axis === 'y';
    el.classList.add('rz-handle');
    if (y) { el.classList.add('rz-y'); }
    el.setAttribute('role', 'separator');
    el.setAttribute('aria-orientation', y ? 'horizontal' : 'vertical');
    if (!el.hasAttribute('tabindex') && el.tagName !== 'BUTTON') { el.tabIndex = 0; }
    if (o.label && !el.hasAttribute('aria-label')) { el.setAttribute('aria-label', o.label); }
    if (!el.title) { el.title = 'Drag to resize, or use the arrow keys; double-click or Home resets'; }
    aria(el, o);
    el.addEventListener('pointerdown', function (e) {
      var h = el.__rz;
      if (phone() || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
      e.preventDefault(); e.stopPropagation();
      try { el.setPointerCapture(e.pointerId); } catch (x) { /* synthetic events */ }
      var x0 = e.clientX, y0 = e.clientY, ctx = h.start ? h.start(e) : null, id = e.pointerId;
      el.classList.add('rz-dragging');
      document.documentElement.setAttribute('data-rz-dragging', y ? 'y' : 'x');
      function move(ev) { if (ev.pointerId === id && h.move) { h.move(ctx, ev.clientX - x0, ev.clientY - y0, ev); aria(el, h); } }
      function up(ev) {
        if (ev.pointerId !== id) { return; }
        el.removeEventListener('pointermove', move); el.removeEventListener('pointerup', up); el.removeEventListener('pointercancel', up);
        el.classList.remove('rz-dragging');
        document.documentElement.removeAttribute('data-rz-dragging');
        if (h.end) { h.end(ctx); }
        aria(el, h);
      }
      el.addEventListener('pointermove', move); el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
    });
    el.addEventListener('keydown', function (e) {
      var h = el.__rz, less = y ? 'ArrowUp' : 'ArrowLeft', more = y ? 'ArrowDown' : 'ArrowRight';
      if (phone()) { return; }
      if (e.key === 'Home') {
        if (h.reset) { e.preventDefault(); e.stopPropagation(); h.reset(); aria(el, h); }
        return;
      }
      if (e.key !== less && e.key !== more) { return; }
      var step = (e.key === more ? 1 : -1) * (e.shiftKey ? 4 : 1);
      if (h.key && h.key(step, e) !== false) { e.preventDefault(); e.stopPropagation(); aria(el, h); }
    });
    el.addEventListener('dblclick', function (e) {
      var h = el.__rz;
      if (phone() || !h.reset) { return; }
      e.preventDefault(); e.stopPropagation(); h.reset(); aria(el, h);
    });
    el.addEventListener('click', function (e) { e.stopPropagation(); });     // a handle inside a sortable header does not sort
    return el;
  }

  /* ---- splitter: a side panel's width ------------------------------------------------------------------------------------------- */
  function bindPanel(hd) {
    var panel = hd.getAttribute('data-resize-target') ? document.querySelector(hd.getAttribute('data-resize-target')) : hd.parentElement;
    if (!panel) { return; }
    var host = panel.parentElement, cssVar = hd.getAttribute('data-resize-var'), key = hd.getAttribute('data-resize-key');
    var left = hd.getAttribute('data-resize-edge') === 'left';
    var min = num(hd.getAttribute('data-resize-min'), 160), max = num(hd.getAttribute('data-resize-max'), 720);
    hd.classList.add('rz-panel');
    if (left) { hd.classList.add('rz-left'); }
    if (host && hd.parentElement !== host) { host.appendChild(hd); }    // out of the panel's scrolling: on the gutter, the host's full height
    if (host) { host.classList.add('rz-host'); }
    function place() {                                                  // the gutter's middle, beside the panel's edge
      if (!host) { return; }
      var hb = host.getBoundingClientRect(), pb = panel.getBoundingClientRect(), cs = getComputedStyle(host), gap = num(cs.columnGap, 0) || 0;
      var at = left ? pb.left - hb.left - gap / 2 : pb.right - hb.left + gap / 2;
      var inner = host.clientWidth - num(cs.paddingLeft, 0) - num(cs.paddingRight, 0);
      hd.style.left = Math.round(at - 5 + host.scrollLeft) + 'px';
      hd.hidden = panel.hidden || pb.width === 0 || pb.width >= inner - 2;   // stacked (the panel the whole width): nothing beside it
    }
    if (window.ResizeObserver) { var ro = new ResizeObserver(place); ro.observe(host); ro.observe(panel); }
    function maxNow() { return host ? Math.max(min, Math.min(max, host.getBoundingClientRect().width - 240)) : max; }
    function apply(w) {
      if (w == null) {
        if (cssVar) { host.style.removeProperty(cssVar); } else { panel.style.removeProperty('width'); panel.style.removeProperty('flex-basis'); }
        place();
        return;
      }
      w = clamp(w, min, maxNow()) + 'px';
      if (cssVar) { host.style.setProperty(cssVar, w); } else { panel.style.width = w; panel.style.flexBasis = w; }
      place();
    }
    function width() { return panel.getBoundingClientRect().width; }
    function set(w) { w = Math.round(clamp(w, min, maxNow())); apply(w); store(key, hd, w); }
    hd.__rzApply = function () { apply(phone() ? null : load(key, hd)); };
    handle(hd, {
      axis: 'x', label: 'Resize this panel',
      start: function () { return width(); },
      move: function (w0, dx) { apply(w0 + (left ? -dx : dx)); },
      end: function () { set(width()); },
      key: function (step) { set(width() + (left ? -step : step) * STEP); },
      reset: function () { store(key, hd, null); apply(null); },
      value: function () { return { now: width(), min: min, max: maxNow() }; }
    });
    hd.__rzApply();
  }

  /* ---- columns: an HTML table's header, or a CSS-grid header row ---------------------------------------------------------------- */
  function tableOf(h) { return h.tagName === 'TABLE' ? h : (h.tagName === 'THEAD' || h.tagName === 'TR') ? h.closest('table') : null; }
  /** A table's header as columns: each header cell one column wide (a leaf), by its column index (row and column spans counted). */
  function tableColumns(t) {
    var rows = t.tHead ? [].slice.call(t.tHead.rows) : (t.rows[0] ? [t.rows[0]] : []), taken = [], leaf = [], n = 0;
    rows.forEach(function (r, ri) {
      var c = 0;
      [].slice.call(r.cells).forEach(function (cell) {
        while (taken[ri] && taken[ri][c]) { c++; }
        var cs = cell.colSpan || 1, rs = cell.rowSpan || 1;
        for (var i = 0; i < rs; i++) { taken[ri + i] = taken[ri + i] || []; for (var j = 0; j < cs; j++) { taken[ri + i][c + j] = true; } }
        if (cs === 1 && (cell.tagName === 'TH' || !leaf[c])) { leaf[c] = cell; }      // a header cell, not a filter row's box
        c += cs; n = Math.max(n, c);
      });
    });
    return { n: n, leaf: leaf };
  }
  function headCells(h) {
    var t = tableOf(h);
    if (t) { var m = tableColumns(t), out = []; m.leaf.forEach(function (c) { if (c) { out.push(c); } }); return out; }
    return [].slice.call(h.children).filter(function (c) { return !c.classList.contains('rz-handle'); });
  }
  function bindColumns(h) {
    var table = tableOf(h);
    var key = h.getAttribute('data-resize-key'), min = num(h.getAttribute('data-resize-min'), 40);
    var flex = h.hasAttribute('data-resize-flex') ? num(h.getAttribute('data-resize-flex'), -1) : -1;
    var scopeSel = h.getAttribute('data-resize-scope'), cssVar = h.getAttribute('data-resize-var') || '--rz-cols';
    function scope() { return (scopeSel && h.closest(scopeSel)) || h.parentElement; }
    function count() { return table ? tableColumns(table).n : headCells(h).length; }
    function widths() {                                     // every column's width now, in px
      if (!table) { return headCells(h).map(function (c) { return Math.round(c.getBoundingClientRect().width); }); }
      var m = tableColumns(table), body = table.tBodies[0] && table.tBodies[0].rows[0], out = [];
      for (var i = 0; i < m.n; i++) {
        var c = m.leaf[i] || (body && body.cells.length === m.n ? body.cells[i] : null);
        out.push(c ? Math.round(c.getBoundingClientRect().width) : 80);
      }
      return out;
    }
    function cols() {                                       // the table's <colgroup> the widths go to (ours, or its own of the right size)
      var g = table.querySelector(':scope > colgroup'), n = count();
      if (g && !g.hasAttribute('data-rz') && g.children.length !== n) { return null; }
      if (!g) {
        g = document.createElement('colgroup'); g.setAttribute('data-rz', '');
        for (var i = 0; i < n; i++) { g.appendChild(document.createElement('col')); }
        table.insertBefore(g, table.tHead || table.tBodies[0] || null);
      }
      return g;
    }
    function apply(ws) {
      if (table) {
        var n = count();
        if (!ws || ws.length !== n) {
          table.classList.remove('rz-fixed'); table.style.removeProperty('width');
          var g0 = table.querySelector(':scope > colgroup');
          if (g0) { if (g0.hasAttribute('data-rz')) { g0.remove(); } else { [].forEach.call(g0.children, function (c) { c.style.removeProperty('width'); }); } }
          return;
        }
        var g = cols();
        if (!g) { return; }
        [].forEach.call(g.children, function (c, i) { c.style.width = Math.max(min, ws[i]) + 'px'; });
        table.classList.add('rz-fixed');
        table.style.width = ws.reduce(function (a, b) { return a + Math.max(min, b); }, 0) + 'px';
        return;
      }
      var s = scope();
      if (!s) { return; }
      if (!ws || ws.length !== count()) { s.style.removeProperty(cssVar); return; }
      s.style.setProperty(cssVar, ws.map(function (w, i) { w = Math.max(min, w); return i === flex ? 'minmax(' + w + 'px, 1fr)' : w + 'px'; }).join(' '));
    }
    /** Column i by dx: a grid row with a flexible column keeps its width (the flexible column gives or takes, or, for the flexible one
     *  itself, its neighbour does); a table grows or shrinks as a whole. Never below the least. */
    function resized(ws0, i, dx) {
      var ws = ws0.slice(), give = flex < 0 ? -1 : i === flex ? (i + 1 < ws.length ? i + 1 : i - 1) : flex;
      ws[i] = Math.max(min, ws0[i] + dx);
      if (give >= 0 && give !== i) {
        var room = ws0[give] - min, d = ws[i] - ws0[i];
        if (d > room) { d = room; ws[i] = ws0[i] + d; }
        ws[give] = ws0[give] - d;
      }
      return ws;
    }
    function saved() { return phone() ? null : load(key, h); }
    function keep(ws) { store(key, h, ws); }
    h.__rzApply = function () { apply(saved()); };
    var leafIndex = [];
    if (table) { tableColumns(table).leaf.forEach(function (c, i) { if (c) { leafIndex.push(i); } }); }
    headCells(h).forEach(function (c, k) {
      var i = table ? leafIndex[k] : k;
      if (c.querySelector(':scope > .rz-col')) { return; }
      var hd = document.createElement('span');
      hd.className = 'rz-col';
      hd.setAttribute('data-rz-col', String(i));
      c.classList.add('rz-cell');
      c.appendChild(hd);
      var name = (c.textContent || '').trim() || ('column ' + (i + 1));
      handle(hd, {
        axis: 'x', label: 'Resize the ' + name + ' column',
        start: function () { return widths(); },
        move: function (ws0, dx) { apply(resized(ws0, i, dx)); },
        end: function () { keep(widths()); },
        key: function (step) { apply(resized(widths(), i, step * STEP)); keep(widths()); },
        reset: function () { keep(null); apply(null); },
        value: function () { var w = widths()[i] || 0; return { now: w, min: min, max: null }; }
      });
    });
    h.__rzApply();
  }

  /* ---- binding: on load, on DOM changes, on a mode change, on crossing the phone width ------------------------------------------ */
  function bind(el) {
    var kind = el.getAttribute('data-resize');
    if (kind === 'panel') { if (!el.__rz) { bindPanel(el); } }
    else if (kind === 'columns') {
      var cs = headCells(el);
      if (cs.length && cs.some(function (c) { return !c.querySelector(':scope > .rz-col'); })) { bindColumns(el); }
    }
  }
  function scan(root) {
    root = root || document;
    if (root.nodeType === 1 && root.hasAttribute('data-resize')) { bind(root); }
    if (root.querySelectorAll) { root.querySelectorAll('[data-resize]').forEach(bind); }
  }
  function reapply() { document.querySelectorAll('[data-resize]').forEach(function (el) { if (el.__rzApply) { el.__rzApply(); } }); }
  function reset(key) {
    forget(key);
    document.querySelectorAll('[data-resize-key="' + key + '"]').forEach(function (el) { if (el.__rzApply) { el.__rzApply(); } });
  }
  var queued = false;
  function soon() { if (queued) { return; } queued = true; requestAnimationFrame(function () { queued = false; scan(document); }); }
  function start() {
    scan(document);
    if (window.MutationObserver) {
      new MutationObserver(function (ms) {
        for (var i = 0; i < ms.length; i++) {
          var m = ms[i];
          if (m.type === 'attributes') { if (m.attributeName === 'data-resize-mode') { reapply(); } continue; }
          var t = m.target;
          if (t.nodeType === 1 && t.closest('[data-resize="columns"]') && [].some.call(m.addedNodes, function (n) { return !(n.classList && n.classList.contains('rz-col')); })) { soon(); return; }
          for (var j = 0; j < m.addedNodes.length; j++) {
            var n = m.addedNodes[j];
            if (n.nodeType === 1 && !n.classList.contains('rz-col') && (n.hasAttribute('data-resize') || n.querySelector('[data-resize]') || n.closest('[data-resize="columns"]'))) { soon(); return; }
          }
        }
      }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-resize-mode'] });
    }
    if (mq) { (mq.addEventListener ? mq.addEventListener.bind(mq, 'change') : mq.addListener.bind(mq))(reapply); }
  }
  window.Drishti.resize = { scan: scan, handle: handle, reset: reset, phone: phone };
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', start); } else { start(); }
})();
