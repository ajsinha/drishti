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
/* A table that drills down level by level (Sutra levels:, the drill macro of _macros/panels.html). One level shows at a time: the
   first, or the rows under the row that was opened, with the breadcrumb above (each step a button back to it) and the parent row on top.
   - Open: click a row's first cell, or select the row (↑ ↓, tables.js) and press Enter.
   - Up: Esc or Backspace in the drill, the Up button, a breadcrumb step, or the browser's Back (every level is a history entry).
   - Address: ?dr.<panel>=1.6 is the open level (row 6 under row 1); a copied address opens the same level, a reload keeps it.
   Going up selects the row that was open, so ↑ ↓ carry on from there. */
(function () {
  'use strict';
  var KEY = 'dr.';
  var active = null;                       // the drill last opened or clicked: Esc goes up in it even when focus left it

  /** The drill's name in the address: its panel id, and ~N for the N-th tab of a tabs panel (each leg's schedule has its own). */
  function idOf(root) {
    var id = root.getAttribute('data-drill');
    var pane = root.closest('.tab-pane, .col-box');
    if (pane && pane.parentElement) {
      var n = Array.prototype.filter.call(pane.parentElement.children, function (c) { return c.classList.contains('tab-pane') || c.classList.contains('col-box'); }).indexOf(pane);
      if (n > 0) { id += '~' + (n + 1); }
    }
    return id;
  }

  /** A drill in a tab that is not shown: its tab is opened (a shared address may point into the second leg). */
  function reveal(root) {
    var pane = root.closest('.tab-pane');
    if (pane && pane.hidden && pane.id) {
      var head = document.querySelector('[aria-controls="' + pane.id + '"]');
      if (head) { head.click(); }
    }
  }

  function param(id) { try { return new URLSearchParams(location.search).get(KEY + id) || ''; } catch (e) { return ''; } }

  function setParam(id, path, push) {
    var p = new URLSearchParams(location.search);
    if (path) { p.set(KEY + id, path); } else { p.delete(KEY + id); }
    var s = p.toString().replace(/%2C/g, ',').replace(/%3A/g, ':');
    var url = location.pathname + (s ? '?' + s : '') + location.hash;
    if (url === location.pathname + location.search + location.hash) { return; }
    try { history[push ? 'pushState' : 'replaceState'](Object.assign({}, history.state || {}, { drill: true }), '', url); } catch (e) { /* sandboxed */ }
  }

  function levelOf(root, path) {
    var found = null;
    Array.prototype.forEach.call(root.querySelectorAll(':scope > .dr-lv'), function (lv) { if (lv.getAttribute('data-dr-path') === path) { found = lv; } });
    return found;
  }

  function crumbOf(root, path) {
    var lv = levelOf(root, path);
    return lv ? lv.getAttribute('data-dr-crumb') || path : path;
  }

  function show(root, path, opts) {
    opts = opts || {};
    var lv = levelOf(root, path);
    if (!lv) { path = ''; lv = levelOf(root, ''); }
    var from = root.__drPath || '';
    if (opts.push) { active = root; }
    Array.prototype.forEach.call(root.querySelectorAll(':scope > .dr-lv'), function (x) {
      var on = x === lv;
      x.hidden = !on;
      x.classList.toggle('on', on);
    });
    root.__drPath = path;
    root.setAttribute('data-dr-at', path);
    // the breadcrumb: the root, then every row opened on the way here
    var ol = root.querySelector('[data-dr-crumbs]');
    while (ol.firstChild) { ol.removeChild(ol.firstChild); }
    var steps = [''];
    if (path) { path.split('.').forEach(function (_, i, parts) { steps.push(parts.slice(0, i + 1).join('.')); }); }
    steps.forEach(function (s, i) {
      var li = document.createElement('li');
      var label = s ? crumbOf(root, s) : (root.getAttribute('data-dr-root') || 'Top');
      if (i === steps.length - 1) {
        li.setAttribute('aria-current', 'location');
        li.textContent = label;
      } else {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'dr-step';
        b.textContent = label;
        b.setAttribute('data-dr-go', s);
        b.title = 'Back to ' + label;
        li.appendChild(b);
      }
      ol.appendChild(li);
    });
    var cap = lv.querySelector('caption');
    if (cap && path) {
      var li2 = document.createElement('li');
      li2.className = 'dr-what';
      li2.textContent = cap.firstChild ? cap.firstChild.textContent.trim() : '';
      ol.lastChild.removeAttribute('aria-current');
      li2.setAttribute('aria-current', 'location');
      if (li2.textContent) { ol.appendChild(li2); } else { ol.lastChild.setAttribute('aria-current', 'location'); }
    }
    var up = root.querySelector('[data-dr-up]');
    if (up) { up.hidden = !path; }
    if (opts.url !== false) { setParam(idOf(root), path, opts.push); }
    var t = lv.querySelector('table');
    // coming back up: the row that was open is marked and selected, so the keyboard carries on from it
    Array.prototype.forEach.call(root.querySelectorAll('tr.dr-came'), function (r) { r.classList.remove('dr-came'); });
    if (from && from.indexOf(path ? path + '.' : '') === 0 && from !== path) {
      var rest = from.slice(path ? path.length + 1 : 0).split('.')[0];
      var mine = path ? path + '.' + rest : rest;
      var row = lv.querySelector('tr[data-dr-row="' + mine + '"]');
      if (row) { row.classList.add('dr-came'); row.scrollIntoView({ block: 'nearest' }); row.click(); }
    }
    if (opts.focus && t) { t.focus({ preventScroll: false }); }
  }

  function up(root) {
    var p = root.__drPath || '';
    if (!p) { return false; }
    var cut = p.lastIndexOf('.');
    show(root, cut < 0 ? '' : p.slice(0, cut), { push: true, focus: true });
    return true;
  }

  function wire(root) {
    if (root.__drill) { return; }
    root.__drill = true;
    root.addEventListener('click', function (e) {
      var a = e.target.closest('a[data-dr-open]');
      if (a && root.contains(a)) {
        if (e.ctrlKey || e.metaKey || e.shiftKey) { return; }      // a new tab: the address opens the level there
        e.preventDefault();
        show(root, a.getAttribute('data-dr-open'), { push: true, focus: true });
        return;
      }
      var b = e.target.closest('[data-dr-go]');
      if (b) { show(root, b.getAttribute('data-dr-go'), { push: true, focus: true }); return; }
      if (e.target.closest('[data-dr-up]')) { up(root); }
    });
    root.addEventListener('pointerdown', function () { active = root; });
    root.addEventListener('focusin', function () { active = root; });
    var start = param(idOf(root));
    if (start) { reveal(root); }
    show(root, start, { url: false });
  }

  function scan(node) {
    var n = node || document;
    if (n.matches && n.matches('[data-drill]')) { wire(n); }
    Array.prototype.forEach.call(n.querySelectorAll ? n.querySelectorAll('[data-drill]') : [], wire);
  }

  /** The drill a key is for: the one the focus is in, else the one last used (a panel redrawn by a live update keeps its address). */
  function target(e) {
    var r = e.target && e.target.closest ? e.target.closest('[data-drill]') : null;
    if (r) { return r; }
    if (active && !active.isConnected) {
      var again = document.querySelector('[data-drill="' + active.getAttribute('data-drill') + '"]');
      active = again && idOf(again) === idOf(active) ? again : null;
    }
    var body = !e.target || e.target === document.body || e.target === document.documentElement;
    return body ? active : null;
  }

  document.addEventListener('keydown', function (e) {
    if (e.defaultPrevented || (e.target.closest && e.target.closest('input, select, textarea, [contenteditable]'))) { return; }
    var root = target(e);
    if (!root) { return; }
    if ((e.key === 'Escape' || e.key === 'Backspace' || (e.key === 'ArrowLeft' && !e.altKey)) && !e.ctrlKey && !e.metaKey && !e.altKey) {
      if (up(root)) { e.preventDefault(); e.stopPropagation(); }
    } else if (e.key === 'ArrowRight' && !e.altKey) {
      var sel = root.querySelector('.dr-lv.on tbody tr[aria-selected="true"] a[data-dr-open]');
      if (sel) { e.preventDefault(); sel.click(); }
    }
  });

  window.addEventListener('popstate', function () {
    Array.prototype.forEach.call(document.querySelectorAll('[data-drill]'), function (root) {
      var want = param(idOf(root));
      if (want !== (root.__drPath || '')) { show(root, want, { url: false, focus: true }); }
    });
  });
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', function () { scan(document); }); } else { scan(document); }
  new MutationObserver(function (list) {
    list.forEach(function (m) { Array.prototype.forEach.call(m.addedNodes, function (x) { if (x.nodeType === 1) { scan(x); } }); });
  }).observe(document.documentElement, { childList: true, subtree: true });
  window.drishtiDrill = { scan: scan, show: show, up: up };
})();
