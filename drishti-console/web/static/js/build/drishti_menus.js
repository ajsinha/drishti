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
/* A small keyboard-first menu for the workbench: "Add panel...", "Bind field...", the ranked panel kinds offered when a field is
 * dropped on empty canvas, the File and Ship menus and the command palette all use it.
 *
 *   WB.menu.open({title, items: [{label, detail, badge, icon, value}], filter: true, at: {x, y} | anchor: element, empty, onPick(item), onClose()})
 *
 * The list is drishti-inputs' Listbox (its options, keys and active option; the active option is the selected one) under a search
 * field ranked by drishti-inputs' searchOptions; the box is placed by drishti-core (placeAt, placeUnder). Focus moves into the menu
 * (the field, or the list) and returns to where it was when the menu closes. Up and Down move, Enter picks, Escape and Tab close,
 * typing filters. Items are text only. */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
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
    var E = window.Drishti.elements, I = E.inputs, core = E.core;
    var back = document.activeElement;
    var box = WB.el('div', 'wb-menu', null, { role: 'dialog', 'aria-label': spec.title || 'Menu' });
    if (spec.title) { box.appendChild(WB.el('div', 'wb-menu-t', spec.title)); }
    var items = (spec.items || []).map(function (it) { return { label: String(it.label), detail: [it.detail, it.badge].filter(Boolean).join(' '), item: it }; });
    var list = new I.Listbox(document, { label: spec.title || 'Choices', followActive: true, onPick: function (o) { pick(o.item); },
      render: function (o, el) {
        var it = o.item;
        if (it.icon) { el.appendChild(WB.el('i', 'bi bi-' + it.icon + ' wb-menu-ic', null, { 'aria-hidden': 'true' })); }
        el.appendChild(WB.el('span', 'wb-menu-n', it.label));
        if (it.badge) { el.appendChild(WB.el('span', 'bs-role', it.badge)); }
        if (it.detail) { el.appendChild(WB.el('span', 'wb-menu-d', it.detail)); }
      } });
    list.el.classList.add('wb-menu-l');
    list.el.tabIndex = -1;
    var input = null;
    if (spec.filter) {
      input = WB.el('input', 'studio-in', null, { type: 'search', 'aria-label': 'Filter: ' + (spec.title || ''), autocomplete: 'off', 'aria-controls': list.id, role: 'combobox',
        'aria-expanded': 'true', 'aria-autocomplete': 'list' });
      box.appendChild(input);
    }
    box.appendChild(list.el);
    var owner = input || list.el;
    function paint() {
      list.render(I.searchOptions(items, input ? input.value : ''), function () { return false; }, { empty: spec.empty || 'Nothing matches.' });
      list.setActive(0, owner);
    }
    function pick(it) { close(true); if (spec.onPick) { spec.onPick(it); } }
    box.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' || e.key === 'Tab') { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); } close(true); return; }
      if (list.key(e, owner)) { e.stopPropagation(); }
    });
    if (input) { input.addEventListener('input', paint); }
    document.body.appendChild(box);
    paint();
    if (spec.anchor) { core.placeUnder(box, spec.anchor); } else { core.placeAt(box, spec.at.x, spec.at.y); }
    owner.focus({ preventScroll: true });
    var away = function (e) { if (!box.contains(e.target)) { close(false); } };
    document.addEventListener('mousedown', away, true);
    current = { box: box, back: back, spec: spec, away: away };
    return { close: close };
  }

  /** A question in the page instead of the browser's confirm()/prompt() (UX-29): themable, readable by screen readers, testable.
   *  WB.ask({title, message, ok, input: {label, value}}) -> Promise of true (confirmed), the typed text (with input), or null (cancelled). */
  WB.ask = function (spec) {
    return new Promise(function (resolve) {
      var back = document.activeElement, id = 'wbAsk' + (++seq);
      var wrap = WB.el('div', 'wb-ask'), box = WB.el('div', 'wb-ask-box', null, { role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': id + 't', 'aria-describedby': id + 'm' });
      box.appendChild(WB.el('h2', 'bs-h', spec.title, { id: id + 't' }));
      box.appendChild(WB.el('p', null, spec.message, { id: id + 'm' }));
      var input = null;
      if (spec.input) {
        var l = WB.el('label', null, spec.input.label, { 'for': id + 'i' });
        input = WB.el('input', 'studio-in mono', null, { type: 'text', id: id + 'i', autocomplete: 'off', spellcheck: 'false' });
        input.value = spec.input.value || '';
        box.appendChild(l); box.appendChild(input);
      }
      var bar = WB.el('div', 'bs-actions'), ok = WB.el('button', 'btn-pill btn-accent', spec.ok || 'OK', { type: 'button' }), no = WB.el('button', 'btn-pill btn-ghost', 'Cancel', { type: 'button' });
      bar.appendChild(ok); bar.appendChild(no); box.appendChild(bar); wrap.appendChild(box);
      function done(v) { wrap.remove(); if (back && back.focus && document.contains(back)) { back.focus({ preventScroll: true }); } resolve(v); }
      ok.addEventListener('click', function () { done(input ? input.value : true); });
      no.addEventListener('click', function () { done(null); });
      wrap.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); done(null); }
        else if (e.key === 'Enter' && input && e.target === input) { e.preventDefault(); done(input.value); }
        else if (e.key === 'Tab') {
          var f = Array.prototype.slice.call(box.querySelectorAll('input, button')), i = f.indexOf(document.activeElement);
          if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); } else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
        }
      });
      document.body.appendChild(wrap);
      (input || ok).focus();
    });
  };
  WB.menu = { open: open, close: close, isOpen: function () { return !!current; } };
})();
