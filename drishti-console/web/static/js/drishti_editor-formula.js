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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis);
/* The editors' Rachana input (docs/architecture/EDITORS.md section 7, "one grammar"): every formula, filter, row rule and computed column is
   typed in one of these. As you type, the word before the caret is completed from Rachana's function catalogue (the server's, with each
   function's signature and one line) and the names the editor knows (columns, aliases, measures, tables). Up and Down move, Enter or Tab
   takes, Escape closes. The console never parses Rachana for meaning: the server checks every formula as typed and the problem shows
   at its line. Built with DOM calls and textContent only. */
(function () {
  'use strict';
  var E = (window.Drishti.editors = window.Drishti.editors || {});
  var seq = 0;

  function word(text, at) {
    var m = /[A-Za-z_$@^][A-Za-z0-9_.$@^]*$/.exec(text.slice(0, at));
    return m ? { word: m[0], start: at - m[0].length } : { word: '', start: at };
  }

  /** Makes {@code el} (an input or textarea) a Rachana input. {@code names()} answers the editor's names: [{name, note}]. */
  E.formula = function (el, names) {
    if (el.getAttribute('data-formula-bound')) { return el; }
    el.setAttribute('data-formula-bound', '1');
    el.setAttribute('autocomplete', 'off');
    el.setAttribute('spellcheck', 'false');
    var id = 'edfx' + (++seq);
    var list = document.createElement('ul');
    list.className = 'ed-fx';
    list.id = id;
    list.setAttribute('role', 'listbox');
    list.hidden = true;
    el.setAttribute('aria-autocomplete', 'list');
    el.setAttribute('aria-controls', id);
    if (el.parentNode) { el.insertAdjacentElement('afterend', list); }      // else placed at the first completion (bound before it is in the page)
    var items = [], at = -1, ctx = null;

    function close() { list.hidden = true; items = []; at = -1; el.removeAttribute('aria-activedescendant'); }
    function choose(i) {
      var it = items[i];
      if (!it || !ctx) { return; }
      var v = el.value, insert = it.insert;
      el.value = v.slice(0, ctx.start) + insert + v.slice(el.selectionStart);
      var caret = ctx.start + (it.fn ? insert.length - 1 : insert.length);
      el.setSelectionRange(caret, caret);
      close();
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    function mark(i) {
      at = i;
      Array.prototype.forEach.call(list.children, function (li, j) { li.setAttribute('aria-selected', j === i ? 'true' : 'false'); });
      if (list.children[i]) { el.setAttribute('aria-activedescendant', list.children[i].id); }
    }
    function open() {
      ctx = word(el.value, el.selectionStart || 0);
      if (ctx.word.length < 1) { close(); return; }
      var w = ctx.word.toLowerCase();
      var fns = (E.functions || []).filter(function (f) { return f.name.toLowerCase().indexOf(w) === 0; })
        .map(function (f) { return { label: f.signature || f.name + '()', note: f.description || '', insert: f.name + '()', fn: true }; });
      var ns = (names ? names() : []).filter(function (n) { return n.name.toLowerCase().indexOf(w) === 0 || n.name.toLowerCase().indexOf('.' + w) > 0; })
        .map(function (n) { return { label: n.name, note: n.note || '', insert: n.name, fn: false }; });
      items = ns.concat(fns).slice(0, 12);
      while (list.firstChild) { list.removeChild(list.firstChild); }
      if (!items.length) { close(); return; }
      items.forEach(function (it, i) {
        var li = document.createElement('li');
        li.id = id + '-' + i;
        li.setAttribute('role', 'option');
        var b = document.createElement('b'); b.textContent = it.label; li.appendChild(b);
        if (it.note) { var s = document.createElement('small'); s.textContent = ' ' + String(it.note).split('. ')[0]; li.appendChild(s); }
        li.addEventListener('mousedown', function (e) { e.preventDefault(); choose(i); });
        list.appendChild(li);
      });
      if (!list.parentNode && el.parentNode) { el.insertAdjacentElement('afterend', list); }
      list.hidden = false;
      mark(0);
    }
    el.addEventListener('input', function (e) { if (e.isTrusted !== false) { open(); } });
    el.addEventListener('keydown', function (e) {
      if (list.hidden) { return; }
      if (e.key === 'ArrowDown') { e.preventDefault(); mark(Math.min(items.length - 1, at + 1)); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); mark(Math.max(0, at - 1)); }
      else if (e.key === 'Enter' || e.key === 'Tab') { if (at >= 0) { e.preventDefault(); choose(at); } }
      else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); }
    });
    el.addEventListener('blur', function () { setTimeout(close, 0); });
    return el;
  };
})();
