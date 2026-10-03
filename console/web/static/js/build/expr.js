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
/* Autocomplete for Rachana-EL expressions in the inspector's text fields: after "$" the paths of the shape ("$.legs", "$.book.name"),
 * after "@" the fields of the rows of the panel (when the panel's rows are "$.legs", "@.notional"), and a word becomes a function
 * name with its argument hints. The field keeps focus (an ARIA combobox: Up and Down choose, Enter or Tab take, Escape closes).
 * The server's parser is the judge of an expression; this only saves typing and typos.
 *
 *   WB.expr.attach(input, {paths: function () -> [{path, type}], rows: function () -> '$.legs' | '', functions: {name: {min, max}}}) */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var seq = 0;

  function token(input) {
    var v = input.value, pos = input.selectionStart == null ? v.length : input.selectionStart, i = pos;
    while (i > 0 && /[\w$@.\[\]'-]/.test(v[i - 1])) { i--; }
    return { from: i, to: pos, text: v.slice(i, pos) };
  }
  function candidates(t, src) {
    var out = [], q = t.text;
    if (q[0] === '$') {
      var seen = {};
      src.paths().forEach(function (p) {
        var plain = p.path.replace(/\[\]/g, '');
        if (plain !== '$' && plain.indexOf(q) === 0 && !seen[plain]) { seen[plain] = 1; out.push({ text: plain, hint: p.type }); }
      });
    } else if (q[0] === '@') {
      var rows = (src.rows() || '').replace(/\[\]$/, '');
      if (rows) {
        var prefix = rows + '[].';
        src.paths().forEach(function (p) {
          if (p.path.indexOf(prefix) === 0) { var rest = '@.' + p.path.slice(prefix.length); if (rest.indexOf(q) === 0) { out.push({ text: rest.replace(/\[\]/g, ''), hint: p.type }); } }
        });
      }
    } else if (/^[a-z]/i.test(q)) {
      Object.keys(src.functions || {}).sort().forEach(function (f) {
        if (f.toLowerCase().indexOf(q.toLowerCase()) === 0 && f !== q) {
          var s = src.functions[f];
          out.push({ text: f + '(', hint: s.min + (s.max !== s.min ? '-' + s.max : '') + ' argument' + (s.max === 1 ? '' : 's') });
        }
      });
    }
    return out.slice(0, 40);
  }

  function attach(input, src) {
    var id = 'wbx' + (++seq), box = WB.el('ul', 'wb-menu-l wb-expr', null, { role: 'listbox', id: id }), items = [], sel = 0;
    box.hidden = true;
    input.setAttribute('role', 'combobox'); input.setAttribute('aria-autocomplete', 'list'); input.setAttribute('aria-controls', id); input.setAttribute('aria-expanded', 'false');
    input.parentNode.appendChild(box);
    function hide() { box.hidden = true; input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); }
    function paint() {
      Array.prototype.forEach.call(box.children, function (li, i) { li.setAttribute('aria-selected', i === sel ? 'true' : 'false'); });
      if (box.children[sel]) { input.setAttribute('aria-activedescendant', box.children[sel].id); box.children[sel].scrollIntoView({ block: 'nearest' }); }
    }
    function show() {
      var t = token(input);
      items = t.text ? candidates(t, src) : [];
      box.textContent = '';
      if (!items.length) { hide(); return; }
      sel = 0;
      items.forEach(function (c, i) {
        var li = WB.el('li', 'wb-menu-i', null, { role: 'option', id: id + '-' + i, 'aria-selected': 'false' });
        li.appendChild(WB.el('span', 'wb-menu-n mono', c.text));
        li.appendChild(WB.el('span', 'wb-menu-d', c.hint || ''));
        li.addEventListener('mousedown', function (e) { e.preventDefault(); take(i); });
        box.appendChild(li);
      });
      box.hidden = false; input.setAttribute('aria-expanded', 'true'); paint();
    }
    function take(i) {
      var t = token(input), c = items[i];
      if (!c) { return; }
      input.value = input.value.slice(0, t.from) + c.text + input.value.slice(t.to);
      var at = t.from + c.text.length;
      input.setSelectionRange(at, at);
      hide();
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    input.addEventListener('input', show);
    input.addEventListener('blur', function () { setTimeout(hide, 120); });
    input.addEventListener('keydown', function (e) {
      if (box.hidden) { if (e.key === ' ' && e.ctrlKey) { e.preventDefault(); show(); } return; }
      if (e.key === 'ArrowDown') { sel = Math.min(items.length - 1, sel + 1); paint(); }
      else if (e.key === 'ArrowUp') { sel = Math.max(0, sel - 1); paint(); }
      else if (e.key === 'Enter' || e.key === 'Tab') { take(sel); }
      else if (e.key === 'Escape') { hide(); }
      else { return; }
      e.preventDefault(); e.stopPropagation();
    });
  }
  WB.expr = { attach: attach, candidates: candidates, token: token };
})();
