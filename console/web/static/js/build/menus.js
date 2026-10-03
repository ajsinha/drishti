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
/* A small keyboard-first menu for the workbench: "Add panel...", "Bind field...", the ranked panel kinds offered when a field is
 * dropped on empty canvas, and the autocomplete of expressions all use it.
 *
 *   WB.menu.open({title, items: [{label, detail, badge, value}], filter: true, at: {x, y} | anchor: element, onPick(item), onClose()})
 *
 * Focus moves into the menu (the filter box, or the list) and returns to where it was when the menu closes. Up and Down move,
 * Enter picks, Escape closes, typing filters. Items are text nodes only. */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var current = null, seq = 0;

  function close(restore) {
    if (!current) { return; }
    var c = current;
    current = null;
    c.box.remove();
    document.removeEventListener('mousedown', c.away, true);
    if (restore !== false && c.back && c.back.focus && document.contains(c.back)) { c.back.focus({ preventScroll: true }); }
    if (c.spec.onClose) { c.spec.onClose(); }
  }

  function open(spec) {
    close(false);
    var back = document.activeElement, id = 'wbMenu' + (++seq);
    var box = WB.el('div', 'wb-menu', null, { role: 'dialog', 'aria-label': spec.title || 'Menu' });
    if (spec.title) { box.appendChild(WB.el('div', 'wb-menu-t', spec.title)); }
    var input = null, list = WB.el('ul', 'wb-menu-l', null, { role: 'listbox', id: id, tabindex: '-1', 'aria-label': spec.title || 'Choices' });
    if (spec.filter) {
      input = WB.el('input', 'studio-in', null, { type: 'search', 'aria-label': 'Filter: ' + (spec.title || ''), autocomplete: 'off', 'aria-controls': id, role: 'combobox', 'aria-expanded': 'true' });
      box.appendChild(input);
    }
    box.appendChild(list);
    var shown = [], sel = 0;
    function paint() {
      list.textContent = '';
      var q = input ? input.value.trim().toLowerCase() : '';
      var rank = function (it) { var l = it.label.toLowerCase(); return l === q ? 0 : l.indexOf(q) === 0 ? 1 : l.indexOf(q) >= 0 ? 2 : 3; };
      shown = (spec.items || []).filter(function (it) { return !q || (it.label + ' ' + (it.detail || '') + ' ' + (it.badge || '')).toLowerCase().indexOf(q) >= 0; });
      if (q) { shown = shown.map(function (it, i) { return [rank(it), i, it]; }).sort(function (a, b) { return a[0] - b[0] || a[1] - b[1]; }).map(function (x) { return x[2]; }); }   // the name first, then where it is only described
      sel = Math.min(sel, Math.max(0, shown.length - 1));
      shown.forEach(function (it, i) {
        var li = WB.el('li', 'wb-menu-i', null, { role: 'option', id: id + '-' + i, 'aria-selected': i === sel ? 'true' : 'false' });
        if (it.icon) { li.appendChild(WB.el('i', 'bi bi-' + it.icon + ' wb-menu-ic', null, { 'aria-hidden': 'true' })); }
        li.appendChild(WB.el('span', 'wb-menu-n', it.label));
        if (it.badge) { li.appendChild(WB.el('span', 'bs-role', it.badge)); }
        if (it.detail) { li.appendChild(WB.el('span', 'wb-menu-d', it.detail)); }
        li.addEventListener('mousedown', function (e) { e.preventDefault(); });
        li.addEventListener('click', function () { pick(it); });
        list.appendChild(li);
      });
      if (!shown.length) { list.appendChild(WB.el('li', 'wb-menu-e', spec.empty || 'Nothing matches.')); }
      mark();
    }
    function mark() {
      Array.prototype.forEach.call(list.children, function (li, i) { li.setAttribute('aria-selected', i === sel ? 'true' : 'false'); });
      var li = list.children[sel];
      if (li && li.id) { (input || list).setAttribute('aria-activedescendant', li.id); if (li.scrollIntoView) { li.scrollIntoView({ block: 'nearest' }); } }
    }
    function pick(it) { close(true); if (spec.onPick) { spec.onPick(it); } }
    box.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { sel = Math.min(shown.length - 1, sel + 1); mark(); }
      else if (e.key === 'ArrowUp') { sel = Math.max(0, sel - 1); mark(); }
      else if (e.key === 'Enter') { if (shown[sel]) { pick(shown[sel]); } }
      else if (e.key === 'Escape') { close(true); }
      else if (e.key === 'Tab') { close(true); return; }
      else { return; }
      e.preventDefault(); e.stopPropagation();
    });
    if (input) { input.addEventListener('input', function () { sel = 0; paint(); }); }
    document.body.appendChild(box);
    var r = spec.anchor ? spec.anchor.getBoundingClientRect() : { left: spec.at.x, bottom: spec.at.y };
    box.style.left = Math.max(4, Math.min(r.left, window.innerWidth - 380)) + 'px';
    box.style.top = Math.max(4, Math.min(r.bottom + 4, window.innerHeight - 320)) + 'px';
    paint();
    (input || list).focus({ preventScroll: true });
    var away = function (e) { if (!box.contains(e.target)) { close(false); } };
    document.addEventListener('mousedown', away, true);
    current = { box: box, back: back, spec: spec, away: away };
    return { close: close };
  }

  WB.menu = { open: open, close: close, isOpen: function () { return !!current; } };
})();
