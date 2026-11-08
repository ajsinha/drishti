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
   typed in one of these: drishti-inputs' code editor in Rachana (one line for an input, the whole text for a text area), the element kept
   as the value's holder (upgradeCodeArea). As you type, the word before the caret is completed from Rachana's function catalogue (the
   server's, with each function's signature and one line) and the names the editor knows (columns, aliases, measures, tables): the code
   editor's completion (Up and Down, Enter or Tab, Escape). The console never parses Rachana for meaning: the server checks every
   formula as typed and the problem shows at its line. */
(function () {
  'use strict';
  var E = (window.Drishti.editors = window.Drishti.editors || {});

  /** What completes a word: the editor's names first, then the functions (a name or a dotted part starting with it). */
  function entries(w, names) {
    w = String(w || '').toLowerCase();
    if (!w) { return []; }
    var ns = (names ? names() : []).filter(function (n) { var x = n.name.toLowerCase(); return x.indexOf(w) === 0 || x.indexOf('.' + w) > 0; })
      .map(function (n) { return { name: n.name, description: n.note || '', kind: 'field' }; });
    var fns = (E.functions || []).filter(function (f) { return f.name.toLowerCase().indexOf(w) === 0; })
      .map(function (f) { return { name: f.name, signature: f.signature || f.name + '()', description: String(f.description || '').split('. ')[0], kind: 'function' }; });
    return ns.concat(fns).slice(0, 12);
  }

  /** The code editor's completion hook over the editor's names ({@code names()}: [{name, note}]) and the functions (a Form's formula widget takes it as options.complete). */
  E.complete = function (names) { return function (c) { return entries(c.word.replace(/^[$@]/, ''), names); }; };

  /** Makes {@code el} (an input or textarea outside a Form) a Rachana input. {@code names()} answers the editor's names: [{name, note}]. */
  E.formula = function (el, names) {
    if (el.getAttribute('data-di-code')) { return el; }
    el.drishtiCodeOptions = { complete: E.complete(names) };
    el.setAttribute('data-di-code', 'rachana');
    if (el.isConnected) { window.Drishti.elements.inputs.upgradeCodeArea(el); }   // else drishti-inputs makes it one as it arrives in the page
    return el;
  };
})();
